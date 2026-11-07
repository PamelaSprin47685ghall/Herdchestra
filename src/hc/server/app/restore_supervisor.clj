(ns hc.server.app.restore-supervisor
  "Process-level supervised maintenance restore.

  The live Sente command path never restores a whole store.  A maintenance
  request is validated and previewed while the application is still live,
  the selected backup object is staged locally, and only then does a single
  worker stop the application, run the offline restore, and start a fresh
  system.  The HTTP/Sente listener is intentionally down during that window;
  the durable journal under `<data-dir>/maintenance/state.edn` is the only
  observable state, and the client reconnects and asks
  `:backup/maintenance-status` for the terminal result.

  Safety rules:

    - preview and explicit `:confirm? true` are required before any stop;
    - a stop that is not positively confirmed never reaches restore;
    - a changed restore plan is rejected by the preview fingerprint before
      a recovery point or a write;
    - a process restart during restore is never re-run; it is reported as
      failed/unknown;
    - the same maintenance ID is idempotent, and a second active request is
      refused;
    - passwords and recovery-point state never enter the journal."
  (:require [clojure.string :as str]
            [hc.server.app.commands :as commands]
            [hc.server.app.system :as system]
            [hc.server.backup.codec :as codec]
            [hc.server.backup.command-epoch :as backup-command-epoch]
            [hc.server.commands.durable :as command-durable]
            [hc.server.commands.storage :as command-storage]
            [hc.server.backup.file-target :as file-target]
            [hc.server.backup.maintenance :as backup-maintenance]
            [hc.server.backup.maintenance-journal :as journal]
            [hc.server.backup.offline :as offline]
            [hc.server.backup.port :as port]
            [hc.server.backup.service :as backup-service])
  (:import (java.io FileOutputStream)
           (java.lang ProcessHandle)
           (java.nio.channels FileChannel)
           (java.nio.file AtomicMoveNotSupportedException CopyOption Files
                          LinkOption OpenOption Path StandardCopyOption
                          StandardOpenOption)
           (java.nio.file.attribute FileAttribute PosixFilePermissions)
           (java.time Instant)
           (java.util UUID)
           (java.util.regex Pattern)))

(def ^:private id-pattern
  (Pattern/compile "^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$"))

(defonce ^:private runtime
  (atom {:config nil :system nil :maintenance nil}))

(def ^:private terminal-statuses journal/terminal-statuses)
(def ^:private success-statuses journal/success-statuses)

;; Holds the maintenance ID of the one live worker, or nil.
(defonce ^:private worker (atom nil))

;; ---------------------------------------------------------------------------
;; Small utilities

(defn- now-ms
  []
  (System/currentTimeMillis))

(defn- iso
  [ms]
  (str (Instant/ofEpochMilli (long ms))))

(defn- fail!
  ([code message data]
   (throw (ex-info message (cond-> {:error code :message message}
                             data (assoc :details data)))))
  ([code message data cause]
   (throw (ex-info message (cond-> {:error code :message message}
                             data (assoc :details data))
                   cause))))

(defn- expected-error
  [t]
  (let [data (ex-data t)]
    {:code (or (:error data) (:code data) :BACKUP_MAINTENANCE_FAILED)
     :message (or (:message data) (ex-message t) (str t))
     :details (not-empty (dissoc data :error :code :message))}))

(defn- terminal-status?
  [status]
  (contains? terminal-statuses (keyword status)))

(defn- active-status?
  [status]
  (and (some? status) (not (terminal-status? status))))

(defn- normalize-mode
  [mode]
  (let [mode (cond
               (keyword? mode) mode
               (string? mode) (keyword mode)
               :else mode)]
    (when-not (contains? #{:merge :replace} mode)
      (fail! :BACKUP_MODE_INVALID
             "Restore mode must be :merge or :replace"
             {:mode mode}))
    mode))

(defn- assert-id!
  [id label]
  (when-not (and (string? id)
                 (not (str/blank? id))
                 (re-matches id-pattern id))
    (fail! :BACKUP_MAINTENANCE_ID_INVALID
           (str label " is not a safe maintenance identifier")
           {:field label :value id}))
  id)

;; ---------------------------------------------------------------------------
;; Journal
;;
;; One document, one lock, one codec: hc.server.backup.maintenance-journal.
;; The supervisor only owns the worker phases.

(defn- data-dir
  [config]
  (let [dir (some-> config :data-dir str)]
    (when (and dir (not (str/blank? dir))) dir)))

(def journal-path
  journal/journal-path)

(defn- staging-root
  ^Path [config maintenance-id]
  (let [dir (data-dir config)]
    (when-not dir
      (fail! :BACKUP_MAINTENANCE_UNAVAILABLE
             "Maintenance needs a data directory"
             {:maintenance-id maintenance-id}))
    (Path/of dir (into-array String ["maintenance" "staging" maintenance-id]))))

(def read-journal
  journal/read-journal)

(defn- write-journal!
  [config state]
  (journal/write-journal! config state))

(defn- update-journal!
  "Update the supervisor's journal record inside the cross-process journal
  lock.  FIN receives the record inside the same critical section that reads
  it, so a CLI writer cannot land between the read and the write.  Phase
  writes deliberately keep their progress fields; terminal writes go through
  `write-journal-guarded!`."
  [config maintenance-id f]
  (journal/locked
   config
   (fn []
     (let [loaded (read-journal config)]
       (when-not (:ok? loaded)
         (throw (ex-info (or (get-in loaded [:error :message])
                             "maintenance journal is invalid")
                         (:error loaded))))
       (let [state (:state loaded)]
         (when-not state
           (fail! :BACKUP_MAINTENANCE_NOT_FOUND
                  "maintenance journal has no operation"
                  {:maintenance-id maintenance-id}))
         (when-not (= (str maintenance-id) (str (:maintenance-id state)))
           (fail! :BACKUP_MAINTENANCE_ID_CONFLICT
                  "maintenance journal belongs to a different operation"
                  {:expected (:maintenance-id state)
                   :actual maintenance-id}))
         (write-journal! config (f state)))))))

(defn- write-journal-guarded!
  "Write one journal state inside the cross-process journal lock.

  F receives the current state and returns the next state or nil.  VERIFY!,
  when supplied, runs before F and may throw.  SUPERVISED? keeps a record F
  replaces under `:previous` and stamps `journal/supervised-notice` only when
  the replaced record held a finished decision (a terminal status, an
  acknowledgement, or another operation).  A routine phase write of the same
  operation is not a replaced decision and carries no notice."
  [config f {:keys [verify! supervised?]}]
  (journal/locked
   config
   (fn []
     (let [loaded (read-journal config)]
       (when-not (:ok? loaded)
         (throw (ex-info (or (get-in loaded [:error :message])
                             "maintenance journal is invalid")
                         (:error loaded))))
       (let [previous (:state loaded)]
         (when verify!
           (verify! previous))
         (let [next (f previous)]
           (if next
             (let [replaced? (and previous next
                                  (or (terminal-status? (:status previous))
                                      (true? (:acknowledged? previous))
                                      (not= (str (:maintenance-id previous))
                                            (str (:maintenance-id next)))))
                   next (if (and supervised? replaced?)
                          (-> next
                              (assoc :previous (dissoc previous :previous))
                              (assoc-in [:restore :supervised-notice]
                                        (journal/supervised-notice previous next)))
                          next)]
               (write-journal! config next)
               next)
             previous)))))))

(defn- rotation-needed?
  [state]
  (and state
       (or (contains? #{:restoring :restarting} (keyword (:status state)))
           (:restore state))))

(defn- rotate-open-store!
  "Rotate the restored command gate through an already-open STORE.

  The store is owned by `system/start!`; this function never opens it a
  second time.  A missing pre-restore window or a corrupt restored window is
  a refusal, not a silent epoch reuse."
  [config state store]
  (let [pre-window (:command-window state)
        gate (command-durable/gate
              (command-storage/store
               store {:partition "commands/app" :id "app"}))]
    (if (nil? pre-window)
      {:ok? false
       :error {:code :COMMAND_EPOCH_WINDOW_MISSING
               :message "The pre-restore application command window was not recorded"
               :details {:maintenance-id (:maintenance-id state)}}}
      (let [result (backup-command-epoch/rotate-gate! gate pre-window)]
        (if (and (:ok? result)
                 (integer? (:epoch result))
                 (> (long (:epoch result))
                    (long (or (:floor result) 0))))
          (do
            (update-journal! config (:maintenance-id state)
                             #(assoc %
                                     :command-epoch-rotated? true
                                     :command-epoch (:epoch result)
                                     :command-retired-through
                                     (:retired-through result)
                                     :updated-at (iso (now-ms))))
            (assoc result :rotated? true))
          {:ok? false
           :error (or (:error result)
                      {:code :COMMAND_EPOCH_ROTATE_FAILED
                       :message
                       "Could not fence the restored application command epoch"})})))))

(defn store-opened!
  "Rotate the restored command gate after `system/start!` opens the store and
  before any router, Owner, or listener starts.

  This is the only cold-start rotation path.  `prepare!` never opens the
  store, so a corrupt or locked store still reaches the recovery entry
  instead of becoming a generic start failure.  Returns
  `{:ok? true|false :rotated? boolean ...}`; the caller blocks startup on a
  false result."
  [store]
  (let [{:keys [config]} @runtime]
    (if-not config
      {:ok? true :rotated? false}
      (let [journal (try (read-journal config)
                         (catch Throwable t
                           {:ok? false :error (expected-error t)}))]
        (if-not (:ok? journal)
          {:ok? false :error (:error journal)}
          (let [state (:state journal)]
            (cond
              (nil? state) {:ok? true :rotated? false}
              (journal/offline-record? state) {:ok? true :rotated? false}
              (journal/blocking-state state) {:ok? true :rotated? false}
              (not (rotation-needed? state)) {:ok? true :rotated? false}
              (:command-epoch-rotated? state)
              {:ok? true
               :rotated? true
               :epoch (:command-epoch state)
               :retired-through (:command-retired-through state)}
              :else
              (try
                (rotate-open-store! config state store)
                (catch Throwable t
                  {:ok? false :error (expected-error t)})))))))))

;; ---------------------------------------------------------------------------
;; Public projections

(defn- public-request
  [payload]
  (let [categories (some->> (:categories payload)
                            (map keyword)
                            sort
                            vec)
        resolutions (when-let [resolutions (:resolutions payload)]
                      (when (seq resolutions)
                        (into (sorted-map)
                              (map (fn [[k v]]
                                     [(if (vector? k)
                                        (mapv keyword k)
                                        (keyword k))
                                      (keyword v)]))
                              resolutions)))]
    (cond-> {:kind (case (keyword (:kind payload))
                     :backup/maintenance-restore :backup
                     :backup/maintenance-restore-recovery-point :recovery-point
                     (keyword (:kind payload)))
             :source :supervisor}
      (:target-id payload) (assoc :target-id (:target-id payload))
      (:backup-id payload) (assoc :backup-id (:backup-id payload))
      (:recovery-point-id payload)
      (assoc :recovery-point-id (:recovery-point-id payload))
      (:mode payload) (assoc :mode (normalize-mode (:mode payload)))
      (:preview-fingerprint payload)
      (assoc :preview-fingerprint (:preview-fingerprint payload))
      (seq categories) (assoc :categories categories)
      resolutions (assoc :resolutions resolutions)
      (contains? payload :confirm-memory-reactivation?)
      (assoc :confirm-memory-reactivation?
             (true? (:confirm-memory-reactivation? payload)))
      (contains? payload :include-expired?)
      (assoc :include-expired? (true? (:include-expired? payload))))))

(defn- request-digest
  [maintenance-id request]
  (codec/sha256-hex
   (codec/canonical-bytes {:maintenance-id maintenance-id
                           :request request})))

(defn- public-error
  [error]
  (when (map? error)
    (cond-> (select-keys error [:code :message :cause-code])
      (:details error)
      (assoc :details (select-keys (:details error)
                                   [:path :backup-id :recovery-point-id
                                    :cause-code :reason :storage-restored?
                                    :maintenance-id])))))

(defn- public-recovery-point
  [point]
  (when point
    (select-keys point [:id :created-at :reason :scope :kind :status
                        :bytes :state-bytes :integrity])))

(defn- public-applied
  [applied]
  (when applied
    (cond-> (select-keys applied [:status :restored])
      (:error applied) (assoc :error (public-error (:error applied))))))

(defn- public-restore-result
  [result]
  (when (map? result)
    (cond-> (select-keys result
                         [:status :mode :summary :permissions
                          :incomplete-attachments :relink-required :needs-review
                          :needs-reconfiguration :replace-all?
                          :memory-reactivation-required? :warnings
                          :rolled-back? :needs-recovery? :restored-from
                          :store-observed? :store-unchanged? :store-committed?
                          :backup])
      (:error result) (assoc :error (public-error (:error result)))
      (:recovery-point result)
      (assoc :recovery-point (public-recovery-point (:recovery-point result)))
      (:applied result) (assoc :applied (public-applied (:applied result)))
      (:pruned result)
      (assoc :pruned (select-keys (:pruned result) [:deleted :keep-ids :error])))))

(defn- public-stop
  [stop]
  (let [value (or (:value stop) stop)]
    (when (map? value)
      (cond-> (select-keys value [:stopped? :drained? :store-closed?
                                  :components :in-flight-at-stop])
        (:failures value)
        (assoc :failures
               (mapv (fn [failure]
                       {:component (:component failure)
                        :error (public-error (:error failure))})
                     (:failures value)))))))

(defn- public-restart
  [restart]
  (let [value (or (:value restart) restart)]
    (when (map? value)
      (cond-> (select-keys value [:ok? :blocked? :url :verification :error
                                  :command-epoch])
        (contains? value :ok?)
        (assoc :status (if (true? (:ok? value)) :complete :failed))))))

(defn- public-preview
  [preview]
  (when preview
    (cond-> (select-keys preview
                         [:mode :selected-categories :summary :conflicts
                          :placeholders :missing-relations :relink-required
                          :needs-review :incomplete-attachments
                          :memory-reactivation :expired-skipped :permissions
                          :recovery-point :no-changes? :already-imported?
                          :replace-all? :memory-reactivation-required?
                          :fingerprint :warnings])
      (:conflicts preview)
      (assoc :conflicts
             (mapv (fn [conflict]
                     (select-keys conflict
                                  [:category :id :imported-id :name :reason
                                   :default-resolution :resolution :choices]))
                   (:conflicts preview))))))

(defn- public-health
  [health]
  (select-keys health [:ok :store :sessions :gateway :started-at]))

(defn- public-state
  [state]
  (when state
    (let [restore (:restore state)
          restart (:restart state)
          request (:request state)]
      (cond-> (select-keys state
                           [:maintenance-id :request :request-digest :status
                            :phase :created-at :updated-at :finished-at
                            :preview :stop :restore :restart :verification
                            :error :message :needs-attention? :retryable?
                            :needs-recovery? :commit-unproven?
                            :storage-touched? :store-unchanged?
                            :restore-attempted? :acknowledged?
                            :recovered-after-restart? :observed-by-client?
                            :observed-at :no-changes? :already-imported?
                            :data-applied? :health :recovery-entry
                            :staging :previous :process])
        true (assoc :id (:maintenance-id state)
                    :backup-id (:backup-id request)
                    :mode (:mode request)
                    :result (or restore (:result state))
                    :recovery-point (or (:recovery-point restore)
                                        (:recovery-point state))
                    :verification (or (:verification restart)
                                      (:verification state)))
        (:error state) (assoc :error (public-error (:error state)))
        (:stop state) (assoc :stop (public-stop (:stop state)))
        (:restart state) (assoc :restart (public-restart (:restart state)))
        (:preview state) (assoc :preview (public-preview (:preview state)))))))

;; ---------------------------------------------------------------------------
;; Runtime installation

(declare reconcile-state)

(defn- live-process?
  "True when STATE names a live process other than this one."
  [state]
  (when-let [pid (get-in state [:process :pid])]
    (try
      (let [handle (ProcessHandle/of (long pid))]
        (and (.isAlive handle)
             (not= (.pid handle) (.pid (ProcessHandle/current)))))
      (catch Throwable _ false))))

(defn- live-maintenance?
  "True when an active record is still owned by a live maintenance worker.

  This process's worker atom is the first check; another process is detected
  by the recorded PID.  A cold start after a crash sees a dead PID and may
  reconcile.  A second process must not reinterpret a record that a live
  writer still owns."
  [state]
  (and (active-status? (:status state))
       (or (some? @worker)
           (live-process? state))))

(defn- reconcile-journal!
  "Reconcile the durable journal before the start gate reads it.

  A cold start never re-runs a restore: an interrupted supervisor record is
  first turned into the terminal status its evidence supports.  The write is
  guarded, so a concurrent CLI writer is never overwritten.  A write failure
  leaves the active record in place, and the start gate then blocks instead of
  opening a router on an unreconciled restore.  Returns the state read after
  the attempt, or nil when the record belongs to the offline CLI (its evidence
  is not the supervisor's to reinterpret)."
  [config system]
  (let [journal (read-journal config)]
    (when-not (:ok? journal)
      (throw (ex-info (or (get-in journal [:error :message])
                          "maintenance journal is invalid")
                      {:error :BACKUP_MAINTENANCE_JOURNAL_INVALID
                       :details (:error journal)})))
    ;; The offline CLI owns its record's evidence.  The supervisor must not
    ;; reinterpret an offline `:restoring` record as a supervisor outcome.
    (when (and (:state journal)
               (not (journal/offline-record? (:state journal)))
               (not (live-maintenance? (:state journal))))
      (try
        (write-journal-guarded!
         config
         (fn [current]
           (when (and current
                      (not (journal/offline-record? current))
                      (not (live-maintenance? current)))
             (let [next (reconcile-state current system)]
               (when (not= next current) next))))
         {:supervised? true})
        (catch Throwable _ nil))
      (:state (read-journal config)))))

(defn prepare!
  "Set the process configuration and the maintenance handler map.  Call before
  the first system starts so the handler can be passed into the backup
  service.

  An interrupted supervisor record is reconciled before the start gate sees
  it.  This function never opens or writes the store: the command-epoch
  rotation runs later, through `store-opened!`, after `system/start!` has
  opened the store and before any router exists.  A blocked record therefore
  leaves the store untouched until an operator acknowledges it."
  [{:keys [config maintenance]}]
  (reset! runtime {:config config :system nil :maintenance maintenance})
  (when config
    (reconcile-journal! config nil))
  nil)

(defn install!
  "Publish the current live system.  Called after every successful start."
  [system]
  (swap! runtime assoc :system system)
  system)

(defn- clear-system!
  []
  (swap! runtime assoc :system nil)
  nil)

(defn current-system
  []
  (:system @runtime))

(defn stop-current!
  "Stop the live system, if any.  Used by the process shutdown hook so a
  restart does not leave the previous listener or store running."
  []
  (when-let [system (:system @runtime)]
    (let [result (try
                   (system/stop! system)
                   (catch Throwable t
                     {:stopped? false
                      :error (expected-error t)}))]
      (when (and (true? (:stopped? result))
                 (true? (:store-closed? result)))
        (clear-system!))
      result)))

;; ---------------------------------------------------------------------------
;; Preview and staging

(defn- require-backup-service
  [system]
  (or (:backup system)
      (fail! :BACKUP_MAINTENANCE_UNAVAILABLE
             "The backup service is not available for maintenance"
             {})))

(defn- preview-backup!
  [service payload]
  (let [result (backup-service/preview! service payload)]
    (if (:ok? result)
      (let [preview (:receipt result)]
        (when-not (:fingerprint preview)
          (fail! :BACKUP_MAINTENANCE_PREVIEW_INVALID
                 "The backup preview did not produce a plan fingerprint"
                 {:backup-id (:backup-id payload)}))
        preview)
      (throw (ex-info (or (get-in result [:error :message])
                          "backup preview failed")
                      (or (:error result)
                          {:error :BACKUP_FAILED}))))))

(defn- recovery-point-preview!
  [service payload]
  (let [result (backup-service/recovery-points service)]
    (if-not (:ok? result)
      (throw (ex-info (or (get-in result [:error :message])
                          "recovery points are not readable")
                      (or (:error result)
                          {:error :BACKUP_FAILED})))
      (let [points (get-in result [:receipt :recovery-points])
            id (str (:recovery-point-id payload))
            point (some #(when (= id (str (:id %))) %) points)]
        (when-not point
          (fail! :BACKUP_NOT_FOUND
                 "Recovery point does not exist"
                 {:recovery-point-id id}))
        (when-not (= :verified (:integrity point))
          (fail! :BACKUP_RECOVERY_POINT_INVALID
                 "Recovery point failed its integrity check"
                 {:recovery-point-id id
                  :integrity (:integrity point)}))
        {:mode :recovery-point
         :recovery-point (public-recovery-point point)
         :no-changes? false
         :already-imported? false}))))

(defn- stage-backup!
  [service payload config maintenance-id]
  (let [target (backup-service/target-port service payload)
        backup-id (:backup-id payload)
        bytes (port/get-object target backup-id)]
    (when-not (bytes? bytes)
      (fail! :BACKUP_NOT_FOUND
             "The selected backup object is missing from its target"
             {:backup-id backup-id}))
    (let [root (staging-root config maintenance-id)
          store (file-target/store (str root))
          stored (port/put-object! store {:id backup-id :bytes bytes})]
      (try
        (Files/setPosixFilePermissions
         root (PosixFilePermissions/fromString "rwx------"))
        (Files/setPosixFilePermissions
         (file-target/object-path (str root) backup-id)
         (PosixFilePermissions/fromString "rw-------"))
        (catch Exception _ nil))
      {:root (str root)
       :backup-id backup-id
       :sha256 (:sha256 stored)
       :size (:size stored)
       :integrity (:integrity stored)})))

;; ---------------------------------------------------------------------------
;; Worker

(defn- stop-for-maintenance!
  [config maintenance-id]
  (let [system (:system @runtime)
        router (:router system)
        drain-ms (long (or (get-in config [:maintenance :drain-timeout-ms])
                           5000))
        _ (when-not router
            (fail! :BACKUP_MAINTENANCE_UNAVAILABLE
                   "The command router is not available"
                   {:maintenance-id maintenance-id}))
        _ (update-journal! config maintenance-id
                           #(assoc % :status :stopping :phase :stopping
                                   :updated-at (iso (now-ms))))
        gate (commands/maintenance-state router)
        _ (when-not (:active? gate)
            (commands/begin-maintenance! router))
        drained? (commands/drain! router drain-ms)
        in-flight (:in-flight (commands/maintenance-state router))
        receipt-grace-ms (long (or (get-in config
                                             [:maintenance :receipt-grace-ms])
                                   250))
        _ (when (and drained? (pos? receipt-grace-ms))
            ;; The command handler decrements its in-flight count after the
            ;; gateway send call.  A short grace keeps a queued receipt from
            ;; racing the HTTP/Sente stop on very fast local restores.
            (Thread/sleep receipt-grace-ms))
        ;; Re-check the gate after the grace window.  A request that arrived
        ;; between drain and the stop would both reopen ordinary writes and add
        ;; an in-flight handler; either fact refuses the restore before any
        ;; storage is touched.
        gate-after (when drained? (commands/maintenance-state router))
        _ (when (and drained?
                     (or (not (:active? gate-after))
                         (pos? (long (:in-flight gate-after)))))
            (fail! :BACKUP_MAINTENANCE_GATE_REOPENED
                   "The application began accepting commands again during the stop"
                   {:maintenance-id maintenance-id
                    :in-flight (:in-flight gate-after)}))
        command-window (when drained?
                         (backup-command-epoch/capture-router router))
        _ (when (and drained? (nil? command-window))
            (fail! :COMMAND_EPOCH_WINDOW_UNAVAILABLE
                   "The application command window is unavailable after drain"
                   {:maintenance-id maintenance-id}))
        _ (when drained?
            (update-journal! config maintenance-id
                             #(assoc % :command-window command-window
                                     :updated-at (iso (now-ms)))))
        stop-result (when drained? (system/stop! system))]
    (if-not drained?
      (let [result {:stopped? false
                    :drained? false
                    :in-flight-at-stop in-flight
                    :failures [{:component :drain
                                :error {:code :MAINTENANCE_DRAIN_TIMEOUT
                                        :message
                                        "in-flight command handlers did not finish before the maintenance deadline"}}]}]
        (update-journal! config maintenance-id
                         #(assoc % :stop (public-stop result)
                                 :updated-at (iso (now-ms))))
        result)
      (do
        (let [confirmed? (and (true? (:stopped? stop-result))
                              (true? (:store-closed? stop-result)))
              result (if confirmed?
                       (assoc stop-result :command-window command-window)
                       (-> (or stop-result {})
                           (assoc :stopped? false)
                           (assoc :command-window command-window)
                           (update :failures (fnil conj [])
                                   {:component :store
                                    :error
                                    {:code :STORE_NOT_CLOSED
                                     :message
                                     "the store was not confirmed closed; restore was not attempted"}})))]
          ;; The journal first: `:stopped?` is the proof the restore may run,
          ;; and a crash between the two writes must not lose it.
          (update-journal! config maintenance-id
                           #(assoc % :stop (public-stop result)
                                   :updated-at (iso (now-ms))))
          (when (:stopped? result)
            (clear-system!))
          result)))))

(defn- offline-opts
  [config payload state]
  (let [data-dir (data-dir config)
        request (:request state)
        staging (get-in state [:staging :root])
        workspace-root (:workspace-root config)
        workspaces (or (get-in config [:backup :workspaces])
                       (when workspace-root
                         [{:id "app-workspace"
                           :name (or (some-> (Path/of (str workspace-root)
                                                      (make-array String 0))
                                             .getFileName
                                             str)
                                     "app-workspace")
                           :kind :external
                           :path (str workspace-root)}]))]
    (merge {:db-path (:db-path config)
            :data-dir data-dir
            :supervisor-restore? true
            :offline-restore-id (:maintenance-id state)
            :expected-restore-digest (:request-digest state)
            :blob-root (:blob-root config)
            :extensions-root (or (get-in config [:extensions-root])
                                 (str data-dir "/extensions"))
            :workspace-store-root (or (get-in config [:workspace-store-root])
                                      (str data-dir "/workspaces"))
            :workspace-root workspace-root
            :workspaces workspaces}
           (when staging {:target-root staging})
           (select-keys payload
                        [:backup-id :password :mode :categories :resolutions
                         :confirm-memory-reactivation? :include-expired?])
           (when (= :backup (:kind request))
             {:expected-fingerprint (get-in state [:preview :fingerprint])})
          (when (= :recovery-point (:kind request))
            {:recovery-point-id (:recovery-point-id request)}))))

(defn- restore-for-maintenance!
  "Run the offline restore for the supervisor and keep its evidence.

  A normal return and a throw are both written with the raw result, so a later
  finish can classify the commit itself instead of reading a projected map.
  The throw record carries what the exception data proved
  (`:store-observed?`, `:store-unchanged?`, `:store-committed?`); the finish
  turns that into `:failed-needs-recovery`, `:failed-before-commit`,
  `:failed-commit-unproven`, or `:failed`."
  [config payload state]
  (let [maintenance-id (:maintenance-id state)
        _ (update-journal! config maintenance-id
                           #(assoc % :status :restoring :phase :restoring
                                   :updated-at (iso (now-ms))))
        opts (offline-opts config payload state)
        kind (get-in state [:request :kind])]
    (try
      (let [result (case kind
                     :backup (offline/restore! opts)
                     :recovery-point (offline/restore-recovery-point! opts)
                     (fail! :BACKUP_MAINTENANCE_INVALID
                            "Unknown maintenance restore kind"
                            {:kind kind}))]
        (update-journal! config maintenance-id
                         #(assoc % :restore (public-restore-result result)
                                 :updated-at (iso (now-ms))))
        result)
      (catch Throwable t
        (let [result (merge {:status :failed
                             :error (expected-error t)}
                            (select-keys (ex-data t)
                                         [:store-observed?
                                          :store-unchanged?
                                          :store-committed?]))]
          (try
            (update-journal! config maintenance-id
                             #(assoc % :restore (public-restore-result result)
                                     :updated-at (iso (now-ms))))
            (catch Throwable _ nil))
          result)))))

(defn- start-after-maintenance!
  [config maintenance maintenance-id]
  (update-journal! config maintenance-id
                   #(assoc % :status :restarting :phase :restarting
                           :updated-at (iso (now-ms))))
  (let [started (try
                  (system/start! config
                                 {:maintenance maintenance
                                  ;; This is the supervisor's own
                                  ;; in-process restart.  It may pass the
                                  ;; start gate for the record it just
                                  ;; marked `:restarting`; a cold start
                                  ;; has no such ID.
                                  :maintenance-id maintenance-id})
                  (catch Throwable t
                    {:blocked? true
                     :error (expected-error t)}))
        epoch (try
                (:command-epoch (:state (read-journal config)))
                (catch Throwable _ nil))]
    (if (or (:blocked? started) (:error started))
      (let [result {:ok? false
                    :blocked? (:blocked? started)
                    :command-epoch epoch
                    :error (or (:error started)
                               {:code :APP_RESTART_FAILED
                                :message
                                "application start returned a blocked result"})}]
        (try
          (update-journal! config maintenance-id
                           #(assoc % :restart (public-restart result)
                                   :updated-at (iso (now-ms))))
          (catch Throwable _ nil))
        ;; A blocked start may have constructed components before the
        ;; failure.  Stop them now; a blocked result is not installable and
        ;; the shutdown hook must not be the only cleanup path.
        (when-let [partial (:partial-system started)]
          (try (system/stop! partial) (catch Throwable _ nil)))
        result)
      (do
        (install! started)
        (let [;; A freshly started system may still be finishing its
              ;; first state publish or session reconciliation.  Wait a
              ;; bounded window instead of failing on one early read; the
              ;; previous single check turned a slow start into a false
              ;; "restart failed" receipt.
              health (loop [attempt 0]
                       (let [h (try
                                 (system/health started)
                                 (catch Throwable t
                                   {:ok? false
                                    :error (expected-error t)}))]
                         (if (or (true? (:ok h)) (>= attempt 50))
                           h
                           (do (Thread/sleep 100)
                               (recur (inc attempt))))))
              verification (public-health health)
              ok? (and (true? (:ok health))
                       (string? (:url started)))]
          (update-journal! config maintenance-id
                           #(assoc % :restart
                                   {:ok? ok?
                                    :url (:url started)
                                    :command-epoch epoch
                                    :verification verification}
                                   :verification verification
                                   :updated-at (iso (now-ms))))
          (if ok?
            {:ok? true
             :url (:url started)
             :command-epoch epoch
             :verification verification}
            {:ok? false
             :url (:url started)
             :command-epoch epoch
             :verification verification
             :error {:code :APP_HEALTH_FAILED
                     :message
                     "application started but its health check failed"}}))))))

(defn- status-message
  [status]
  (case (keyword status)
    :complete "维护恢复完成；应用已重启，等待客户端重连核对。"
    :failed "恢复未完成，且没有观察到提交；应用已重启。请核对恢复点与错误详情。"
    :failed-needs-recovery
    "恢复可能已写入存储，但后续步骤失败；应用已重启。请对照恢复点核对后再确认。"
    :failed-commit-unproven
    "恢复失败，且无法证明存储是否已提交；应用已重启。没有自动重跑，请核对存储后再确认。"
    :failed-before-commit
    "恢复没有触及存储；应用已重启，当前资料保持原样。"
    :stop-failed "停止写入失败，恢复没有开始；当前资料没有改变。"
    :restart-failed "恢复可能已完成，但应用没有成功重启；需要操作员处理。"
    :failed-needs-attention "恢复与重启均未完成；需要人工处理。"
    :cancelled "维护已取消。"
    "维护状态未知；请重新连接并核对。"))

(defn- finish!
  "Write the supervisor's terminal record inside the cross-process journal
  lock.

  The record must still hold the restore plan this worker planned, or the
  write is refused instead of finishing another operation.  The caller passes
  that plan digest as `:planned-request-digest` in RUN-RESULT metadata.

  A CLI `acknowledge` may land while the worker runs.  Inside the same
  critical section the writer inspects the current record: an acknowledged
  record that proves a commit is kept, because a proven partial restore is
  exactly what the operator inspected; every other acknowledgement wins over
  the finish, and the finish evidence stays under `:previous`.  Either way the
  replacement carries `journal/supervised-notice`, so a replaced decision is
  never silent."
  [config maintenance-id run-result]
  (write-journal-guarded!
   config
   (fn [current]
     (when-not (and current (= (str maintenance-id)
                               (str (:maintenance-id current))))
       (fail! :BACKUP_MAINTENANCE_ID_CONFLICT
              "maintenance journal belongs to a different operation"
              {:expected (:maintenance-id current)
               :actual maintenance-id}))
     (when-not (journal/offline-record? current)
       (let [planned (:planned-request-digest (meta run-result))]
         (when (and planned
                    (not= (str planned) (str (:request-digest current))))
           (fail! :BACKUP_MAINTENANCE_ID_CONFLICT
                  "the journal record no longer holds this restore plan"
                  {:maintenance-id maintenance-id}))))
     (let [restore-value (get-in run-result [:restore :value])
           restore (public-restore-result restore-value)
           stop (public-stop (:stop run-result))
           restart (public-restart (:restart run-result))
           status (keyword (:status run-result))
           restore-status (:status restore-value)
           missing-point? (and (= :complete status)
                               (not (contains? #{:no-changes :already-imported}
                                               restore-status))
                               (nil? (get-in restore-value [:recovery-point :id])))
           status (if missing-point? :failed status)
           error (cond
                   missing-point? {:code :BACKUP_RECOVERY_POINT_MISSING
                                   :message
                                   "restore reported success without a recovery point"}
                   :else (or (public-error (get-in run-result [:restore :error]))
                             (public-error (:error restore-value))
                             (public-error (get-in run-result [:stop :error]))
                             (public-error (get-in run-result [:restart :error]))))
           ;; Core's `:needs-recovery?` on a failed result means "rollback not
           ;; proven", which is doubt, not a proven commit.
           in-doubt? (or (true? (:commit-unproven? current))
                         (and (not (true? (:rolled-back? restore-value)))
                              (true? (:needs-recovery? restore-value))))
           proof (journal/persistence-evidence
                  {:result restore-value
                   :in-doubt? in-doubt?})
           committed? (:committed? proof)
           acknowledged? (true? (:acknowledged? current))
           original-status (keyword (:status run-result))
           ;; A failed restore names what is known about its commit: proven
           ;; partial data, an unprovable commit, an observed unchanged store,
           ;; or no observation at all.  The same storage evidence must be
           ;; named when the restart also failed: a safe pre-commit failure is
           ;; history even if the restart needs another start, and a proven
           ;; commit is a recovery obligation either way.
           status (cond
                    (contains? #{:failed :failed-needs-attention} status)
                    (cond
                      committed? :failed-needs-recovery
                      (:untouched? proof) :failed-before-commit
                      in-doubt? :failed-commit-unproven
                      :else status)

                    :else status)
           restart-failed? (= :failed-needs-attention original-status)
           message (if (and restart-failed?
                             (not= :failed-needs-attention status))
                     (case status
                       :failed-needs-recovery
                       "恢复可能已写入存储，且应用重启失败；请核对存储后再确认。"
                       :failed-before-commit
                       "恢复没有触及存储，但应用重启失败；下一次启动即恢复。"
                       :failed-commit-unproven
                       "恢复失败且无法证明存储是否已提交，应用也未重启；请核对存储后再确认。"
                       (status-message status))
                     (status-message status))]
       ;; An acknowledgement made by another process is preserved, never
       ;; erased; the terminal write still records this worker's own evidence.
       (-> current
           (assoc :status status
                  :phase :done
                  :updated-at (iso (now-ms))
                  :finished-at (iso (now-ms))
                  :needs-attention? (boolean
                                     (and (not= :failed-before-commit status)
                                          (or (:needs-attention? run-result)
                                              missing-point?)))
                  :needs-recovery? (= :failed-needs-recovery status)
                  :commit-unproven? (= :failed-commit-unproven status)
                  :store-unchanged? (= :failed-before-commit status)
                  :storage-touched? committed?
                 ;; Every finish status except `:stop-failed` runs after the
                 ;; restore thunk was attempted.  A bare `:failed` finish must
                 ;; not be mistaken for a safe pre-restore record.
                 :restore-attempted? (not= :stop-failed status)
                  :retryable? (= :stop-failed status)
                  :stop stop
                  :restore restore
                  :restart restart
                  ;; The data may already be applied even when the restart
                  ;; is unconfirmed.  Say both facts, never one without the
                  ;; other.
                  :data-applied? (boolean committed?)
                  :health (or (:verification restart)
                              (:verification current))
                  :recovery-entry
                  (when (and committed?
                             (not (true? (get-in run-result [:restart :ok?]))))
                    {:action :operator
                     :options [:retry-restart :manual-restart]})
                  :error error
                  :message message
                  :recovered-after-restart? false
                  :no-changes? (or (:no-changes? current)
                                   (= :no-changes restore-status))
                  :already-imported? (or (:already-imported? current)
                                         (= :already-imported
                                            restore-status))
                  ;; Another process may have acknowledged while this worker
                  ;; ran.  The status can be rewritten, the operator's
                  ;; comparison evidence never is.
                  :acknowledged? acknowledged?
                  :acknowledged-at (:acknowledged-at current)
                  :acknowledgment (:acknowledgment current)))))
   {:supervised? true}))

(defn- finish-crash!
  "Record an unexpected worker throw as `:failed-needs-attention`.

  An acknowledgement or an unproven commit recorded by another writer during
  the crash is not erased: their flags stay on the record, so the crash record
  never claims more certainty than the journal already has.  A record another
  operation replaced, or a same-ID record with a different request digest, is
  left untouched; a crashed worker cannot label a different maintenance
  request."
  [config maintenance-id planned-digest t]
  (try
    (write-journal-guarded!
     config
     (fn [current]
       (when (and current
                  (= (str maintenance-id) (str (:maintenance-id current)))
                  (or (nil? planned-digest)
                      (= planned-digest (:request-digest current))))
         (assoc current
                :status :failed-needs-attention
                :phase :done
                :updated-at (iso (now-ms))
                :finished-at (iso (now-ms))
                :error (expected-error t)
                :needs-attention? true
                :message
                "维护协调器异常结束；需要人工核对，系统没有自动重跑恢复。")))
     {:supervised? true})
    (catch Throwable _ nil)))

(defn- release-gate!
  [system]
  (when-let [router (:router system)]
    (try
      (commands/end-maintenance! router)
      (catch Throwable _ nil))))

(defn- no-op-state
  [state preview]
  (let [status (cond
                 (:no-changes? preview) :no-changes
                 (:already-imported? preview) :already-imported
                 :else nil)]
    (assoc state
           :status :complete
           :phase :done
           :finished-at (iso (now-ms))
           :no-changes? (= :no-changes status)
           :already-imported? (= :already-imported status)
           :result {:status status}
           :message (case status
                      :no-changes
                      "预览显示没有需要写入的变化；没有停机，当前资料未改变。"
                      :already-imported
                      "此备份此前已导入；本次没有写入，当前资料未改变。"
                      "维护不需要写入。"))))

(defn- prepare-maintenance!
  "Run the live preview and staging after the request has already been
  accepted.  This keeps the command receipt fast (the client gets its
  maintenance ID before any potentially slow target read) while still
  refusing to stop the application when the preview or fingerprint is stale.
  A preparation failure is a terminal failed journal entry with no stop."
  [config system payload state]
  (let [maintenance-id (:maintenance-id state)
        kind (get-in state [:request :kind])]
    (try
      (let [service (require-backup-service system)
            preview (case kind
                      :backup (preview-backup! service payload)
                      :recovery-point (recovery-point-preview! service payload))
            _ (when-let [expected (:preview-fingerprint payload)]
                (when-not (= (str expected) (str (:fingerprint preview)))
                  (fail! :BACKUP_PREVIEW_STALE
                         "The preview changed before maintenance; preview again"
                         {:expected expected
                          :actual (:fingerprint preview)})))
            no-op? (or (:no-changes? preview) (:already-imported? preview))
            staging (when (and (= kind :backup) (not no-op?))
                      (stage-backup! service payload config maintenance-id))
            prepared (cond-> (assoc state
                                    :preview (public-preview preview)
                                    :staging staging
                                    :updated-at (iso (now-ms)))
                       no-op? (no-op-state preview))]
        (update-journal! config maintenance-id (constantly prepared))
        (when no-op?
          (release-gate! system))
        {:ok? true :no-op? (boolean no-op?) :state prepared})
      (catch Throwable t
        (let [error (expected-error t)
              failed (assoc state
                            :status :failed
                            :phase :done
                            :error error
                            :needs-attention? false
                            :retryable? true
                            :restore-attempted? false
                            :storage-touched? false
                            :message
                            "维护预检失败；没有停止写入，当前资料未改变。"
                            :updated-at (iso (now-ms))
                            :finished-at (iso (now-ms)))]
          (try
            (update-journal! config maintenance-id (constantly failed))
            (catch Throwable _ nil))
          (release-gate! system)
          {:ok? false :error error :state failed})))))

(defn- run-worker!
  "Run the maintenance phases on the worker thread.

  The offline entry point stamps its own throws (`:store-observed?`,
  `:store-unchanged?`, `:store-committed?`), and `restore-for-maintenance!`
  stores that evidence in the supervisor record.  A throw that escapes this
  function is an unexpected coordinator failure, not a second chance to
  classify the restore; it becomes the crash record."
  [{:keys [state payload]}]
  (let [{:keys [config maintenance]} @runtime
        maintenance-id (:maintenance-id state)]
    (try
      (let [prepared (prepare-maintenance!
                      config (:system @runtime) payload state)]
        (when (and (:ok? prepared) (not (:no-op? prepared)))
          (let [prepared-state (:state prepared)
                result (backup-maintenance/run!
                        {:stop! #(stop-for-maintenance!
                                  config maintenance-id)
                         :restore! #(restore-for-maintenance!
                                     config payload prepared-state)
                         :start! #(start-after-maintenance!
                                   config maintenance maintenance-id)
                         :on-progress (fn [_phase _data] nil)})]
            (when (= :stop-failed (:status result))
              (release-gate! (:system @runtime)))
            (finish! config maintenance-id
                     (vary-meta result assoc
                                :planned-request-digest
                                (:request-digest prepared-state))))))
      (catch Throwable t
        (finish-crash! config maintenance-id (:request-digest state) t))
      (finally
        (when (= maintenance-id @worker)
          (reset! worker nil))))))

(defn- start-worker!
  [{:keys [state] :as work}]
  (let [maintenance-id (:maintenance-id state)
        thread (Thread. ^Runnable (fn [] (run-worker! work))
                        "hc-restore-supervisor")]
    (.setDaemon thread true)
    (when-not (compare-and-set! worker nil maintenance-id)
      (fail! :BACKUP_MAINTENANCE_ACTIVE
             "Another maintenance worker is still running"
             {:maintenance-id @worker}))
    (try
      (.start thread)
      (catch Throwable t
        (reset! worker nil)
        (throw t)))))

;; ---------------------------------------------------------------------------
;; Request handling

(defn- request-receipt
  [state]
  {:ok? true :receipt (public-state state)})

(defn- request-restore!
  [payload]
  (let [{:keys [config system]} @runtime
        _ (when-not config
            (fail! :BACKUP_MAINTENANCE_UNAVAILABLE
                   "Maintenance is not configured for this process"
                   {}))
        _ (when-not (true? (:confirm? payload))
            (fail! :BACKUP_RESTORE_UNCONFIRMED
                   "Maintenance restore needs explicit confirmation"
                   {:maintenance-id (:maintenance-id payload)}))
        kind (keyword (:kind payload))
        maintenance-id (assert-id! (str (:maintenance-id payload))
                                   :maintenance-id)
        _ (case kind
            :backup
            (do
              (when (str/blank? (str (:backup-id payload)))
                (fail! :BACKUP_ID_INVALID
                       "A backup ID is required"
                       {:maintenance-id maintenance-id}))
              (when (empty? (or (:categories payload) []))
                (fail! :BACKUP_CATEGORY_REQUIRED
                       "At least one restore category is required"
                       {:maintenance-id maintenance-id}))
              (normalize-mode (:mode payload)))
            :recovery-point
            (when (str/blank? (str (:recovery-point-id payload)))
              (fail! :BACKUP_ID_INVALID
                     "A recovery-point ID is required"
                     {:maintenance-id maintenance-id}))
            (fail! :BACKUP_MAINTENANCE_INVALID
                   "Unknown maintenance restore kind"
                   {:kind kind}))
        request (public-request (assoc payload :kind kind))
        digest (request-digest maintenance-id request)
        journal (read-journal config)
        _ (when-not (:ok? journal)
            (throw (ex-info (or (get-in journal [:error :message])
                                "maintenance journal is invalid")
                            (:error journal))))
        existing (:state journal)]
    (cond
      (and existing
           (= (str maintenance-id) (str (:maintenance-id existing)))
           (not= digest (:request-digest existing)))
      (fail! :BACKUP_MAINTENANCE_ID_CONFLICT
             "The maintenance ID was already used with a different request"
             {:maintenance-id maintenance-id})

      (and existing
           (= (str maintenance-id) (str (:maintenance-id existing)))
           (= digest (:request-digest existing)))
      (request-receipt existing)

      (and existing (active-status? (:status existing)))
      (fail! :BACKUP_MAINTENANCE_ACTIVE
             "Another maintenance operation is active"
             {:maintenance-id (:maintenance-id existing)
              :status (:status existing)})

      :else
      (let [_ (when-not system
                (fail! :BACKUP_MAINTENANCE_UNAVAILABLE
                       "The application is not running; cannot accept a restore"
                       {:maintenance-id maintenance-id}))
            _ (when-not (:backup system)
                (fail! :BACKUP_MAINTENANCE_UNAVAILABLE
                       "The backup service is not available for maintenance"
                       {:maintenance-id maintenance-id}))
            state {:maintenance-id maintenance-id
                   :request request
                   :request-digest digest
                   ;; The acknowledgement binds the journal state, the plan
                   ;; and this store identity, exactly as the offline path
                   ;; does.  Without it a supervisor failure could be
                   ;; acknowledged against a different store.
                   :storage-identity (some-> (:db-path config)
                                             journal/storage-identity
                                             str)
                   :status :accepted
                   :phase :validating
                   :created-at (iso (now-ms))
                   :updated-at (iso (now-ms))
                   :preview nil
                   :staging nil
                   :previous (when (and existing
                                        (terminal-status? (:status existing)))
                               (dissoc (public-state existing) :previous))
                   :process {:pid (.pid (ProcessHandle/current))
                             :started-at (iso (now-ms))}
                   :needs-attention? false
                   :retryable? false
                   :observed-by-client? false}]
        (let [locked
              (journal/locked
               config
               (fn []
                 (let [current (:state (read-journal config))]
                   ;; Another process may have written between the unlocked
                   ;; pre-check and this critical section.  Re-run the same-ID
                   ;; decision here: a different digest is a conflict, and the
                   ;; same digest is an idempotent receipt, never a second
                   ;; worker.
                   (cond
                     (and current
                          (= (str maintenance-id)
                             (str (:maintenance-id current)))
                          (not= digest (:request-digest current)))
                     (fail! :BACKUP_MAINTENANCE_ID_CONFLICT
                            "The maintenance ID was already used with a different request"
                            {:maintenance-id maintenance-id})

                     (and current
                          (= (str maintenance-id)
                             (str (:maintenance-id current)))
                          (= digest (:request-digest current)))
                     {:existing current}

                     current
                     (do
                       (when (active-status? (:status current))
                         (fail! :BACKUP_MAINTENANCE_ACTIVE
                                "Another maintenance operation is active"
                                {:maintenance-id (:maintenance-id current)
                                 :status (:status current)}))
                       (when (journal/blocking-state current)
                         (fail! :BACKUP_MAINTENANCE_ACTIVE
                                "The previous restore failed and is not acknowledged; inspect it first"
                                {:maintenance-id (:maintenance-id current)
                                 :status (:status current)
                                 :needs-recovery? (:needs-recovery? current)}))
                       ;; An acknowledged terminal record is history, not an
                       ;; active request.  The new record keeps it under
                       ;; `:previous`; refusing here would make the
                       ;; documented acknowledgement path a dead end.
                       ;; The pre-lock read may be stale; keep the record this
                       ;; write actually replaces, not the one it saw earlier.
                       (let [next (if (terminal-status? (:status current))
                                    (assoc state
                                           :previous
                                           (dissoc (public-state current)
                                                   :previous))
                                    state)]
                         (write-journal! config next)
                         {:written next}))

                     :else
                     (let [next (dissoc state :previous)]
                       (write-journal! config next)
                       {:written next})))))]
          (if-let [existing (:existing locked)]
            (request-receipt existing)
            (let [written (or (:written locked) state)]
              (try
                (commands/begin-maintenance! (:router system))
                (start-worker! {:state written :payload payload})
                (request-receipt written)
                (catch Throwable t
                  (try
                    (commands/end-maintenance! (:router system))
                    (catch Throwable _ nil))
                  (let [error (expected-error t)
                        failed (-> written
                                 (assoc :status :failed
                                        :phase :done
                                        :finished-at (iso (now-ms))
                                        :updated-at (iso (now-ms))
                                        :error error
                                        :needs-attention? false
                                        :retryable? true
                                        :restore-attempted? false
                                        :storage-touched? false
                                        :message
                                        "维护未能启动；没有停止写入，当前资料未改变。"))]
                    (try
                      ;; One guarded write: under the shared lock, only when
                      ;; the record is still this request's `:accepted`
                      ;; record.  A worker that already moved the record
                      ;; forward is not overwritten.
                      (write-journal-guarded!
                       config
                       (fn [current]
                         (if (and current
                                  (= (str maintenance-id)
                                     (str (:maintenance-id current)))
                                  (= digest (:request-digest current))
                                  (= :accepted (:status current)))
                           failed
                           current))
                       {})
                      (catch Throwable _ nil))
                    {:ok? false :error error}))))))))))

;; ---------------------------------------------------------------------------
;; Status and reconciliation

(defn status
  "Return the maintenance capability and the latest journal state.  This is
  safe to call while the application is live; it does not mutate anything."
  []
  (let [{:keys [config system]} @runtime
        journal (if config
                  (read-journal config)
                  {:ok? true :state nil})
        state (:state journal)
        view (public-state state)
        drain-ms (long (or (get-in config [:maintenance :drain-timeout-ms])
                           5000))]
    (cond-> {:available? (and (some? config) (:ok? journal))
             :mode (if config :supervised :offline-only)
             :reason (cond
                       (nil? config) :NO_IN_PROCESS_QUIESCE
                       (not (:ok? journal)) :MAINTENANCE_JOURNAL_INVALID
                       :else nil)
             :command "pixi run backup"
             :journal (select-keys journal [:path :ok?])
             :quiesce {:supported? (some? config)
                       :drain-timeout-ms drain-ms}
             :restart {:supported? (some? config)}
             :reconnect {:supported? (some? config)}
             :active (when (active-status? (:status state)) view)
             :last view}
      (not (:ok? journal)) (assoc :journal-error (:error journal))
      (nil? system) (assoc :system :stopped))))

(defn- observe-client!
  [config state]
  (if (and (terminal-status? (:status state))
           (not (:observed-by-client? state))
           (or (:restart state)
               (:recovered-after-restart? state)))
    (write-journal-guarded!
     config
     (fn [current]
       (if (and current
                (= (str (:maintenance-id current))
                   (str (:maintenance-id state)))
                (not (:observed-by-client? current)))
         (assoc current
                :observed-by-client? true
                :observed-at (iso (now-ms))
                :updated-at (iso (now-ms)))
         current))
     {})
    state))

(defn- status-command!
  [payload]
  (let [{:keys [config]} @runtime
        _ (when-not config
            (fail! :BACKUP_MAINTENANCE_UNAVAILABLE
                   "Maintenance is not configured for this process"
                   {}))
        journal (read-journal config)
        _ (when-not (:ok? journal)
            (throw (ex-info (or (get-in journal [:error :message])
                                "maintenance journal is invalid")
                            (:error journal))))
        state (:state journal)
        requested (some-> (:maintenance-id payload) str)]
    (cond
      (nil? state)
      {:ok? false
       :error {:code :BACKUP_MAINTENANCE_NOT_FOUND
               :message "No maintenance operation has been recorded"}}

      (and requested
           (not= requested (str (:maintenance-id state))))
      {:ok? false
       :error {:code :BACKUP_MAINTENANCE_NOT_FOUND
               :message "The requested maintenance operation does not exist"
               :details {:maintenance-id requested}}}

      :else
      (let [state (observe-client! config state)]
        {:ok? true :receipt (public-state state)}))))

(defn handle-command!
  "Handle one `:backup/maintenance-*` payload.  The live Sente router calls
  this only for maintenance control; ordinary backup commands still go to the
  backup service."
  [payload]
  (try
    (case (keyword (:kind payload))
      :backup/maintenance-restore
      (request-restore! (assoc payload :kind :backup))
      :backup/maintenance-restore-recovery-point
      (request-restore! (assoc payload :kind :recovery-point))
      :backup/maintenance-status (status-command! payload)
      {:ok? false
       :error {:code :BACKUP_COMMAND_UNKNOWN
               :message "unknown maintenance command"
               :details {:kind (:kind payload)}}})
    (catch clojure.lang.ExceptionInfo e
      {:ok? false :error (expected-error e)})
    (catch Throwable t
      {:ok? false
       :error {:code :BACKUP_MAINTENANCE_FAILED
               :message (or (ex-message t) (str t))}})))

;; ---------------------------------------------------------------------------
;; Startup reconciliation

(defn- restore-success?
  [restore]
  (and (map? restore)
       (not (false? (:ok? restore)))
       (not (true? (:needs-attention? restore)))
       (not (true? (:needs-recovery? restore)))
       (nil? (:error restore))
       (contains? success-statuses (keyword (:status restore)))))

(defn- current-verification
  [system]
  (when system
    (try
      (public-health (system/health system))
      (catch Throwable t
        {:ok? false :error (expected-error t)}))))

(defn- reconcile-state
  [state system]
  (let [status (keyword (:status state))
        restore (:restore state)
        restore-ok? (restore-success? restore)
        restore-known? (some? restore)
        health (current-verification system)
        base (-> state
                 (assoc :updated-at (iso (now-ms))
                        :finished-at (or (:finished-at state) (iso (now-ms)))
                        :phase :done
                        :recovered-after-restart? true
                        :restore-attempted? true
                        :verification health))]
    (cond
      (terminal-status? status)
      state

      (contains? #{:accepted :validating} status)
      (-> base
          (assoc :status :failed
                 :error {:code :BACKUP_MAINTENANCE_INTERRUPTED
                         :message
                         "maintenance was interrupted before stopping writers; no restore was attempted"}
                 :needs-attention? false
                 :retryable? true
                 :restore-attempted? false
                 :storage-touched? false
                 :message "维护在停止写入前中断；恢复未开始，当前资料未改变。"))

      (= status :stopping)
      (-> base
          (assoc :status :failed
                 :error {:code :BACKUP_MAINTENANCE_INTERRUPTED
                         :message
                         "maintenance was interrupted while stopping writers; no restore was attempted"}
                 :needs-attention? false
                 :retryable? true
                 :restore-attempted? false
                 :storage-touched? false
                 :message "维护在停止写入阶段中断；恢复未开始，当前资料未改变。"))

      (= status :restoring)
      (cond
        restore-ok?
        (-> base
            (assoc :status :complete
                   :restart {:ok? true :recovered? true
                             :verification health}
                   :error nil
                   :needs-attention? false
                   :needs-recovery? false
                   :commit-unproven? false
                   :store-unchanged? false
                   :message "恢复已完成；进程重启后应用已重新运行，等待客户端核对。"))

        restore-known?
        (let [in-doubt? (or (true? (:commit-unproven? state))
                            (and (not (true? (:rolled-back? restore)))
                                 (true? (:needs-recovery? restore))))
              proof (journal/persistence-evidence
                     {:result restore
                      :in-doubt? in-doubt?})
              status (cond
                       (:committed? proof) :failed-needs-recovery
                       (:untouched? proof) :failed-before-commit
                       in-doubt? :failed-commit-unproven
                       :else :failed)]
          (-> base
              (assoc :status status
                     :restart {:ok? true :recovered? true
                               :verification health}
                     :error (or (:error restore)
                                {:code :BACKUP_MAINTENANCE_RESTORE_FAILED
                                 :message
                                 "restore failed before the process restarted"})
                     :needs-attention? (not= :failed-before-commit status)
                     :needs-recovery? (= :failed-needs-recovery status)
                     :commit-unproven? (= :failed-commit-unproven status)
                     :store-unchanged? (= :failed-before-commit status)
                     :message "恢复失败；进程重启后应用已重新运行，但恢复结果需要人工核对。")))

        :else
        (-> base
            (assoc :status :failed-needs-attention
                   :restart {:ok? true :recovered? true
                             :verification health}
                   :error {:code :BACKUP_MAINTENANCE_OUTCOME_UNKNOWN
                           :message
                           "process restarted during restore; the restore outcome is unknown and was not re-run"}
                   :needs-attention? true
                   :message "恢复过程中进程重启，结果未知；系统没有自动重跑恢复。")))

      (= status :restarting)
      (cond
        restore-ok?
        (-> base
            (assoc :status :complete
                   :restart {:ok? true :recovered? true
                             :verification health}
                   :error nil
                   :needs-attention? false
                   :needs-recovery? false
                   :commit-unproven? false
                   :store-unchanged? false
                   :message "恢复已完成；进程重启后应用已重新运行，等待客户端核对。"))

        :else
        (let [in-doubt? (true? (:commit-unproven? state))
              proof (journal/persistence-evidence
                     {:result restore
                      :in-doubt? in-doubt?})
              status (cond
                       (and restore-known? (:committed? proof))
                       :failed-needs-recovery
                       (and restore-known? (:untouched? proof))
                       :failed-before-commit
                       (and restore-known? (:in-doubt? proof))
                       :failed-commit-unproven
                       restore-known? :failed
                       in-doubt?
                       :failed-commit-unproven
                       :else :failed-needs-attention)]
          (-> base
              (assoc :status status
                     :restart {:ok? true :recovered? true
                               :verification health}
                     :error (or (:error restore)
                                {:code :BACKUP_MAINTENANCE_OUTCOME_UNKNOWN
                                 :message
                                 "process restarted after restore; the restore outcome is unknown"})
                     :needs-attention? (not= :failed-before-commit status)
                     :needs-recovery? (= :failed-needs-recovery status)
                     :commit-unproven? (= :failed-commit-unproven status)
                     :store-unchanged? (= :failed-before-commit status)
                     :message "应用在恢复后重启；恢复结果需要人工核对。"))))

      :else
      state)))

(defn startup!
  "Reconcile an interrupted maintenance journal after a cold process start.
  It never re-runs a restore.  A supervisor restore that finished before the
  restart is marked complete; an unobservable one becomes
  failed-needs-attention.  Offline CLI records are left untouched.

  `prepare!` already reconciles before the start gate; this second pass is
  idempotent and leaves a terminal record unchanged.  A journal failure is not
  allowed to turn a successful start into a failed one, so it is swallowed
  here."
  []
  (let [{:keys [config system]} @runtime]
    (when (and config (not @worker))
      (try
        (reconcile-journal! config system)
        (catch Throwable _ nil)))))

