(ns hc.server.backup.offline
  "Offline whole-store backup/restore entry points.

  The application router refuses a live restore because it cannot prove that
  Owners and writers have stopped.  These functions are the explicit offline
  path: the caller stops the application, opens the store here, restores with
  `:quiescent? true`, fences the application command epoch when data changed,
  then closes the store.  They never run inside a live Owner or gateway
  callback."
  (:require [clojure.string :as str]
            [hc.server.backup :as backup]
            [hc.server.backup.error :as err]
            [hc.server.backup.file-recovery :as file-recovery]
            [hc.server.backup.file-target :as file-target]
            [hc.server.backup.maintenance-journal :as journal]
            [hc.server.backup.storage-adapter :as storage-adapter]
            [hc.server.backup.workspace :as workspace]
            [hc.server.gateway.ports :as blob-port]
            [hc.server.storage :as storage]))

(defn- open-store
  ([db-path] (open-store db-path false))
  ([db-path create?]
   (storage/open-store {:path (str db-path)
                        :create? (boolean create?)
                        :durability :sync})))

(defn- store-version
  "The store's last committed metadata version, or nil when the store is not
  open or cannot be read.  `hc.server.storage/store-info` keeps this fact after
  a close, and `storage/restore-state!` bumps it in the same transaction that
  writes the restored data."
  [store]
  (try
    (:version (storage/store-info store))
    (catch Throwable _ nil)))

(defn- target-root
  [{:keys [data-dir target-root]}]
  (or target-root
      (when data-dir (str data-dir "/backups"))
      (err/fail! :BACKUP_TARGET_INVALID
                 "Offline backup needs :target-root or :data-dir"
                 {:data-dir data-dir})))

(defn- recovery-root
  [{:keys [data-dir recovery-root]}]
  (or recovery-root
      (when data-dir (str data-dir "/recovery"))
      (err/fail! :BACKUP_TARGET_INVALID
                 "Offline recovery needs :recovery-root or :data-dir"
                 {:data-dir data-dir})))

(defn- side-sources
  [{:keys [data-dir extensions-root blob-root blob-store workspace-store
           workspace-store-root workspaces]}]
  (let [extensions-root (or extensions-root
                            (when data-dir (str data-dir "/extensions")))
        blob-root (or blob-root
                      (when data-dir (str data-dir "/blobs")))
        blob-store (or blob-store
                       (when blob-root (blob-port/blob-store-port blob-root)))
        workspace-store-root (or workspace-store-root
                                 (when data-dir (str data-dir "/workspaces")))
        workspace-store (or workspace-store
                            (when workspace-store-root
                              (workspace/store workspace-store-root
                                               {:workspaces workspaces})))]
    {:extensions-root extensions-root
     :blob-store blob-store
     :workspace-store workspace-store}))

(defn- domain
  [store opts]
  (storage-adapter/domain store
                          (merge {:quiescent? true}
                                 (side-sources opts)
                                 (select-keys opts
                                              [:no-prior-app-commands?]))))

(defn- with-store
  ([db-path f] (with-store db-path false f))
  ([db-path create? f]
   (let [store (open-store db-path create?)]
     (try
       (f store)
       (finally
         (storage/close-store! store))))))

(defn- run-observed-restore
  "Run one restore against an opened store and stamp the result with what the
  caller can prove about the commit:

    :store-observed?     always present inside this region; false means the
                         store version was unreadable, so neither a commit nor
                         an untouched store can be proven;
    :store-unchanged?    true only when the version was readable before and is
                         identical after;
    :store-committed?    true only when the version advanced.

  The stamp travels through `ex-data` on a throw and through the result map on
  a return.  A throw that never reaches this function carries none of the keys
  and is a failure outside the storage region.  `run-uninterrupted` keeps a
  cancellation from interrupting the second observation."
  [store restore-fn]
  (let [before (store-version store)
        observed? (some? before)]
    (storage/run-uninterrupted
     (fn []
       (try
         (let [result (restore-fn)
               after (store-version store)
               committed? (and observed? (some? after) (not= before after))
               result (cond-> (or result {})
                        true (assoc :store-observed? observed?)
                        (and observed? (some? after) (= before after))
                        (assoc :store-unchanged? true)
                        committed? (assoc :store-committed? true))]
           result)
         (catch Throwable t
           (let [after (store-version store)
                 committed? (and observed? (some? after) (not= before after))]
             (throw (ex-info (or (ex-message t) (str t))
                             (merge (ex-data t)
                                    {:store-observed? observed?}
                                    (when (and observed? (some? after)
                                               (= before after))
                                      {:store-unchanged? true})
                                    (when committed? {:store-committed? true}))
                             t)))))))))

(defn- with-fence-result
  "Lift the same-transaction fence evidence into the offline result."
  [result]
  (let [result (if (map? (:recovery-point result))
                 (update result :recovery-point dissoc :state)
                 result)]
    (if-let [epoch (get-in result [:applied :command-epoch])]
      (assoc result
             :command-epoch epoch
             :command-retired-through
             (get-in result [:applied :command-retired-through]))
      result)))

(defn- config
  "The supervisor journal needs `:data-dir`; the restore path requires it."
  [opts]
  (let [data-dir (some-> (:data-dir opts) str)]
    (when (and data-dir (not (str/blank? data-dir)))
      {:data-dir data-dir})))

(defn- throwable-error
  [t]
  (try
    (let [data (ex-data t)]
      {:code (or (:error data) (:code data) :BACKUP_RESTORE_FAILED)
       :message (or (:message data) (ex-message t) (str t))
       :details (not-empty (dissoc data :error :code :message))})
    (catch Throwable _
      {:code :BACKUP_RESTORE_FAILED
       :message (str t)})))

(defn- storage-fence?
  "True when the restore result proves that the storage transaction returned:
  the adapter writes the applied command epoch in that same transaction."
  [result]
  (or (some? (:command-epoch result))
      (journal/partial-restore? result)))

(defn- no-acknowledgment-changed?
  "Refuse a terminal write that would replace an acknowledgement another
  process recorded while this restore was failing.  The failure evidence goes
  into `:restore`; the acknowledgement and its terminal status win."
  [current]
  (when (and current (true? (:acknowledged? current)))
    (journal/fail!
     :BACKUP_MAINTENANCE_STATE_CHANGED
     "The journal was acknowledged while the restore was failing"
     {:maintenance-id (:maintenance-id current)})))

(defn- journaled-restore!
  "Run one offline restore behind the supervisor's durable journal.

  The journal is written before the storage transaction and finished only
  after the external side effects return.  A process crash in that window
  leaves an active `:restoring` record; the next application start blocks
  instead of opening a router.

  A failure is classified by the evidence the restore returned.  A result or a
  throw proves a commit through `:store-committed?`, `:command-epoch` or
  `:storage-restored?`; it proves an untouched store only through
  `:store-unchanged?`.  A throw inside the observed region without either
  proof is `:failed-commit-unproven`: restored data may be on disk, and the
  next start stays blocked until an operator compares the journal evidence
  with the store and acknowledges it
  (`hc.server.backup.maintenance-journal/acknowledge!`).  A throw outside the
  observed region (the store never opened) is `:failed`.  No automatic re-run
  exists, and nothing here claims a rollback that did not happen."
  [opts restore-fn]
  (let [config (or (config opts)
                   (err/fail! :BACKUP_MAINTENANCE_UNAVAILABLE
                              "Offline restore needs :data-dir for its journal"
                              {:data-dir (:data-dir opts)}))
        state (journal/offline-restore-begin!
               config (assoc opts
                             :kind (or (:kind opts) :backup)
                             :supervisor-restore?
                             (true? (:supervisor-restore? opts))))]
    (try
      (let [raw (restore-fn)
            result (with-fence-result raw)
            _ (journal/offline-restore-mark-committed!
               config state (storage-fence? result))]
        (journal/offline-restore-finish! config state result nil)
        result)
      (catch Throwable t
        (let [error (throwable-error t)
              data (ex-data t)
              observed-key? (contains? data :store-observed?)
              observed? (true? (:store-observed? data))
              committed? (or (true? (:store-committed? data))
                             (true? (:storage-restored? data)))
              unchanged? (and (not committed?)
                              (or (true? (:store-unchanged? data))
                                  ;; No observation key means the throw happened
                                  ;; before `run-observed-restore`, i.e. while
                                  ;; opening the store.  This restore wrote
                                  ;; nothing, so it is a proven pre-commit
                                  ;; failure, not doubt.
                                  (not observed-key?)))
              ;; Doubt exists only inside the observed region: the restore
              ;; opened the store but could prove neither outcome.  An
              ;; unreadable version is doubt, not a pre-store failure.
              in-doubt? (and observed-key? (not committed?) (not unchanged?))
              marked (when in-doubt?
                       (journal/offline-restore-mark-commit-unproven!
                        config state))]
          (try
            (journal/offline-restore-finish!
             config (or marked state)
             {:status :failed
              :error error
              :needs-attention? true
              :store-observed? observed?
              :store-unchanged? unchanged?
              :store-committed? committed?}
             error
             {:expect no-acknowledgment-changed?})
            (catch Throwable _ nil))
          (throw t))))))

(defn backup!
  "Create a backup while the application is stopped."
  [{:keys [db-path password encrypt? categories include-secrets?
           confirm-plaintext-secrets? include-deleted? include-temp?
           include-cache? include-external-files? now device app-version]
    :as opts}]
  (let [target-root (target-root opts)
        ;; An absent `:encrypt?` means "not specified", not "false": the
        ;; core policy defaults a new backup to encrypted.  Passing a nil
        ;; `:encrypt?` key through would look like an explicit opt-out.
        encrypt? (if (some? encrypt?) encrypt? (:encrypt opts))]
    (with-store db-path
      (fn [store]
        (backup/backup! (domain store opts)
                        (file-target/store target-root)
                        (cond-> {:password password
                                 :categories categories
                                 :include-secrets? include-secrets?
                                 :confirm-plaintext-secrets? confirm-plaintext-secrets?
                                 :include-deleted? include-deleted?
                                 :include-temp? include-temp?
                                 :include-cache? include-cache?
                                 :include-external-files? include-external-files?}
                          (some? encrypt?) (assoc :encrypt? encrypt?)
                          now (assoc :now now)
                          device (assoc :device device)
                          app-version (assoc :app-version app-version)))))))

(defn preview!
  "Preview a restore while the application is stopped.  Nothing is written."
  [{:keys [db-path backup-id password mode categories resolutions
           confirm-memory-reactivation? include-expired?]
    :as opts}]
  (let [target-root (target-root opts)]
    (with-store db-path
      (fn [store]
        (backup/preview-restore (domain store opts)
                                (file-target/store target-root)
                                backup-id
                                (cond-> {:password password
                                         :mode mode
                                         :categories categories
                                         :resolutions resolutions
                                         :confirm-memory-reactivation? confirm-memory-reactivation?
                                         :include-expired? include-expired?}))))))

(defn restore!
  "Restore a backup while the application is stopped.

  The store is opened, restored through one atomic storage transaction, and
  closed.  The storage adapter writes the fenced application command gate in
  that same transaction, so a crash cannot leave restored data with the old
  epoch.  A failed restore returns the backup result with `:status :failed`.
  The store is closed in every case."
  [{:keys [db-path backup-id password mode categories resolutions
           confirm-memory-reactivation? include-expired?
           expected-fingerprint]
    :as opts}]
  (let [target-root (target-root opts)
        recovery-root (recovery-root opts)]
    (journaled-restore!
     (assoc opts :kind :backup)
     (fn []
       ;; A restore into a data directory the application never opened must
       ;; create its target store first.  `:create? true` still refuses a
       ;; corrupt, foreign or marker-only path, so it cannot erase history.
       (with-store
         db-path true
         (fn [store]
           (run-observed-restore
            store
            #(backup/restore! (domain store opts)
                              (file-target/store target-root)
                              (file-recovery/store recovery-root)
                              backup-id
                              (cond-> {:password password
                                       :mode mode
                                       :categories categories
                                       :resolutions resolutions
                                       :confirm-memory-reactivation? confirm-memory-reactivation?
                                       :include-expired? include-expired?
                                       :expected-fingerprint expected-fingerprint
                                       :no-prior-app-commands? (:no-prior-app-commands? opts)
                                       :quiescent? true})))))))))

(defn restore-recovery-point!
  "Roll back to a local recovery point while the application is stopped.

  The rollback writes the fenced application command gate in the same storage
  transaction as the restored data."
  [{:keys [db-path recovery-point-id]
    :as opts}]
  (let [recovery-root (recovery-root opts)]
    (journaled-restore!
     (assoc opts :kind :recovery-point)
     (fn []
       (with-store db-path
         (fn [store]
           (run-observed-restore
            store
            #(backup/restore-recovery-point!
              (domain store opts)
              (file-recovery/store recovery-root)
              recovery-point-id
              {:quiescent? true}))))))))

(defn list-backups
  [opts]
  (backup/list-backups (file-target/store (target-root opts))))

(defn list-recovery-points
  [opts]
  (backup/list-recovery-points (file-recovery/store (recovery-root opts))))
