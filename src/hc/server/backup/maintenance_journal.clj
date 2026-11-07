(ns hc.server.backup.maintenance-journal
  "Durable maintenance journal shared by the supervisor and the offline CLI.

  The journal is one canonical EDN document under
  `<data-dir>/maintenance/state.edn`, written through a temporary file, fsync
  and one atomic rename.  It is the only cross-process hand-off: the offline
  CLI records an active restore before touching storage and finishes it only
  after every external side effect returns.  A process crash in that window
  leaves an active record; the next application start must block instead of
  opening a router.

  This namespace holds only the journal primitives and offline records.
  Supervisor worker orchestration stays in `hc.server.app.restore-supervisor`
  so neither namespace has to require the other."
  (:require [clojure.string :as str]
            [hc.server.backup.codec :as codec])
  (:import (java.io FileOutputStream)
           (java.lang ProcessHandle)
           (java.nio.channels FileChannel FileLock)
           (java.nio.file AtomicMoveNotSupportedException CopyOption Files
                          LinkOption OpenOption Path StandardCopyOption
                          StandardOpenOption)
           (java.nio.file.attribute FileAttribute PosixFilePermissions)
           (java.util.concurrent ThreadLocalRandom)
           (java.time Instant)
           (java.util UUID)))

(def journal-format :hc.maintenance/state)
(def journal-version 1)

(def terminal-statuses
  #{:complete :failed :stop-failed :restart-failed :failed-needs-attention
    :failed-needs-recovery :failed-commit-unproven :failed-before-commit
    :cancelled})

(def unsuccessful-terminal-statuses
  "Terminal states that still stand for a restore that did not complete.
  An acknowledgement does not turn them into success; it only records that an
  operator inspected the result and the current storage."
  #{:failed :failed-needs-attention :failed-needs-recovery
    :failed-commit-unproven :failed-before-commit})

(def blocking-terminal-statuses
  "Terminal failure states that keep the next start blocked until an operator
  acknowledges them.  `:stop-failed` proves that no write happened;
  `:restart-failed` and `:cancelled` are recorded but not failures of the
  stored data.  `:failed-before-commit` is the only status that proves the
  store was observed unchanged, so it is history; `:failed` stays blocking
  because it also covers records written before this evidence existed, and
  those cannot prove anything.  `blocking-failure?` exempts a `:failed`
  record that explicitly says `:restore-attempted? false`; that record was
  written before the restore thunk ran."
  #{:failed :failed-needs-attention :failed-needs-recovery
    :failed-commit-unproven})

(def success-statuses
  #{:complete :no-changes :already-imported :applied})

(defonce journal-lock (Object.))

(defn success-status?
  [status]
  (contains? success-statuses (keyword status)))

;; ---------------------------------------------------------------------------
;; Small utilities

(defn now-ms
  []
  (System/currentTimeMillis))

(defn iso
  [ms]
  (str (Instant/ofEpochMilli (long ms))))

(defn fail!
  ([code message data]
   (throw (ex-info message (cond-> {:error code :message message}
                             data (assoc :details data)))))
  ([code message data cause]
   (throw (ex-info message (cond-> {:error code :message message}
                             data (assoc :details data))
                   cause))))

(defn expected-error
  [t]
  (let [data (ex-data t)]
    {:code (or (:error data) (:code data) :BACKUP_MAINTENANCE_FAILED)
     :message (or (:message data) (ex-message t) (str t))
     :details (not-empty (dissoc data :error :code :message))}))

(defn terminal-status?
  [status]
  (contains? terminal-statuses (keyword status)))

(defn active-status?
  [status]
  (and (some? status) (not (terminal-status? status))))

(defn- data-dir
  [config]
  (let [dir (some-> config :data-dir str)]
    (when (and dir (not (str/blank? dir))) dir)))

(defn journal-path
  ^Path [config]
  (let [dir (data-dir config)]
    (when-not dir
      (fail! :BACKUP_MAINTENANCE_UNAVAILABLE
             "Maintenance needs a data directory"
             {:config (some-> config (select-keys [:data-dir]))}))
    (Path/of dir (into-array String ["maintenance" "state.edn"]))))

(defn- ensure-parent!
  [^Path path]
  (when-let [parent (.getParent path)]
    (Files/createDirectories parent (make-array FileAttribute 0))))

(defn- fsync-directory!
  [^Path dir]
  (try
    (with-open [channel (FileChannel/open
                         dir
                         (into-array OpenOption [StandardOpenOption/READ]))]
      (.force channel true))
    (catch Exception _ nil)))

(defn- write-bytes!
  [^Path path ^bytes bytes]
  (with-open [out (FileOutputStream. (.toFile path))]
    (.write out bytes)
    (.flush out)
    (.sync (.getFD out)))
  (try
    (Files/setPosixFilePermissions
     path
     (PosixFilePermissions/fromString "rw-------"))
    (catch Exception _ nil)))

(defn- atomic-write!
  [^Path target ^bytes bytes]
  (ensure-parent! target)
  (let [dir (.getParent target)
        temp (Files/createTempFile dir ".maintenance-" ".edn"
                                   (make-array FileAttribute 0))]
    (try
      (write-bytes! temp bytes)
      (try
        (Files/move temp target
                    (into-array CopyOption
                                [StandardCopyOption/ATOMIC_MOVE
                                 StandardCopyOption/REPLACE_EXISTING]))
        (catch AtomicMoveNotSupportedException e
          (fail! :BACKUP_MAINTENANCE_JOURNAL_FAILED
                 "The filesystem cannot publish the maintenance journal atomically"
                 {:path (str target)}
                 e)))
      (fsync-directory! dir)
      target
      (finally
        (try (Files/deleteIfExists temp) (catch Exception _ nil))))))

(defn state-digest
  [state]
  (codec/sha256-hex (codec/canonical-bytes state)))

(defn- encode-journal
  [state]
  {:format journal-format
   :format-version journal-version
   :digest (state-digest state)
   :state state})

(defn- invalid-journal
  [path code message]
  {:ok? false
   :path (str path)
   :error {:code code :message message}})

(defn read-journal
  "Read the durable journal.  A missing file is not an error.  A corrupt or
  newer journal is reported and never silently replaced."
  [config]
  (let [path (journal-path config)]
    (if-not (Files/exists path (make-array LinkOption 0))
      {:ok? true :state nil :path (str path)}
      (try
        (let [body (codec/edn-read (Files/readAllBytes path))]
          (cond
            (not= journal-format (:format body))
            (invalid-journal path :BACKUP_MAINTENANCE_JOURNAL_INVALID
                             "maintenance journal has an unknown format")

            (> (long (or (:format-version body) 0)) journal-version)
            (invalid-journal path :BACKUP_VERSION_UNSUPPORTED
                             "maintenance journal was written by a newer format")

            (not (map? (:state body)))
            (invalid-journal path :BACKUP_MAINTENANCE_JOURNAL_INVALID
                             "maintenance journal has no state map")

            (not= (:digest body) (state-digest (:state body)))
            (invalid-journal path :BACKUP_MAINTENANCE_JOURNAL_INVALID
                             "maintenance journal digest does not match")

            :else
            {:ok? true :state (:state body) :path (str path)}))
        (catch Throwable t
          (invalid-journal path :BACKUP_MAINTENANCE_JOURNAL_INVALID
                           (or (ex-message t) (str t))))))))

(defn write-journal!
  [config state]
  (let [path (journal-path config)]
    (try
      (atomic-write! path (codec/canonical-bytes (encode-journal state)))
      (catch Throwable t
        (if (= :BACKUP_MAINTENANCE_JOURNAL_FAILED (:error (ex-data t)))
          (throw t)
          (fail! :BACKUP_MAINTENANCE_JOURNAL_FAILED
                 "Could not write the maintenance journal"
                 {:path (str path)}
                 t))))
    state))

;; ---------------------------------------------------------------------------
;; Cross-process critical section
;;
;; The offline CLI runs in its own JVM, so `journal-lock` alone cannot order
;; two offline restores, or an offline restore and a supervisor.  The durable
;; side of that exclusion is one OS file lock on
;; `<data-dir>/maintenance/state.lock`.  It is taken before the in-process
;; monitor so every writer in every process observes the same order.
;;
;; The lock file is never deleted: unlinking an advisory-locked file would let
;; two processes lock different inodes under the same path.

(defn lock-path
  ^Path [config]
  (.resolve (.getParent (journal-path config)) "state.lock"))

(def ^:private lock-retry-ms 5)

;; The lock covers journal writes only, never a restore or a stop.  The
;; ceiling stays above one filesystem fsync stall but far below an operator's
;; patience, and a timeout refuses the caller instead of running without the
;; lock.
(def ^:private lock-timeout-ms 30000)

(defn- sleep!
  [ms]
  (Thread/sleep (long ms)))

(defn- retry-delay-ms
  []
  (+ (long lock-retry-ms)
     (.nextLong (ThreadLocalRandom/current) 0 (long lock-retry-ms))))

(defn- acquire-file-lock
  ^FileLock [^FileChannel channel ^Path path]
  (let [deadline (+ (now-ms) (long lock-timeout-ms))]
    (loop []
      (or (try
            (.tryLock channel)
            (catch Throwable _ nil))
          (if (>= (now-ms) deadline)
            (fail! :BACKUP_MAINTENANCE_LOCKED
                   "Another process is changing the maintenance journal"
                   {:path (str path)
                    :wait-ms lock-timeout-ms})
            (do (sleep! (retry-delay-ms))
                (recur)))))))

(defn- with-file-lock
  [config f]
  (let [path (lock-path config)]
    (ensure-parent! path)
    (with-open [channel (FileChannel/open
                         path
                         (into-array OpenOption
                                     [StandardOpenOption/CREATE
                                      StandardOpenOption/WRITE]))]
      (let [^FileLock handle (acquire-file-lock channel path)]
        (try
          (f)
          (finally
            (try (.release handle) (catch Throwable _ nil))))))))

(defn locked
  "Run THUNK inside the journal critical section: the OS file lock that excludes
  other processes, then the in-process monitor that excludes other threads.
  Reentrant for the current thread, so a locked caller may call a locked
  function again."
  [config thunk]
  (locking journal-lock
    (with-file-lock config thunk)))

(defn supervised-notice
  "Repair value a JOURNAL WRITE! call returns when its predecessor had to
  replace a FINISH! decision.  The supervisor reports it in the returned
  journal state: a replaced decision is never silent."
  [previous current]
  (when (map? previous)
    {:code :BACKUP_MAINTENANCE_RECORD_REPLACED
     :message
     "The journal kept the failure evidence of the replaced record"
     :previous {:maintenance-id (:maintenance-id previous)
                :status (:status previous)
                :needs-recovery? (:needs-recovery? previous)}}))

(defn- guarded
  "Update the journal inside one critical section.  LOADED is the journal read
  at the start of that section, so a decision taken outside the lock is already
  stale; the decision function still receives it for identity checks."
  [config f]
  (locked
   config
   (fn []
     (let [loaded (read-journal config)]
       (when-not (:ok? loaded)
         (throw (ex-info (or (get-in loaded [:error :message])
                             "maintenance journal is invalid")
                         (or (:error loaded)
                             {:code :BACKUP_MAINTENANCE_JOURNAL_INVALID
                              :message "maintenance journal is invalid"}))))
       (f loaded)))))

(defn update!
  "Read-modify-write one journal state under the cross-process lock.

  F receives the current state and returns the next state; nil leaves the
  journal unchanged.  `:expected-digest` refuses a state that another process
  replaced after the caller read it.  `:expect` receives the current state and
  may throw to refuse the write, so every decision happens inside the lock."
  [config {:keys [expected-digest expect f write]}]
  (guarded
   config
   (fn [loaded]
     (let [current (:state loaded)]
       (when (and expected-digest
                  (not= expected-digest (state-digest current)))
         (fail! :BACKUP_MAINTENANCE_STATE_CHANGED
                "The maintenance journal changed in another process"
                {:expected-digest expected-digest
                 :actual-digest (state-digest current)}))
       (when expect (expect current))
       (if-let [next (f current)]
         (:state (or (when write (write nil current next))
                     {:state (write-journal! config next)}))
         current)))))

(defn read-consistent
  "Read the journal at a moment when no writer holds the lock.  A decision
  taken from this value is re-checked by `update!` inside the lock."
  [config]
  (locked config #(read-journal config)))

;; ---------------------------------------------------------------------------
;; Restore evidence
;;
;; The plan fingerprint is a hash over the resolved restore plan.  The operator
;; confirmation of a failed restore binds the journal state, the plan and the
;; storage identity, so it cannot be copied to another journal or reused after
;; any of them changed.

(def restore-plan-fields
  "Request fields that fully determine which stored data a restore applies.
  The password is deliberately absent; a password change cannot alter the
  plan identity."
  [:kind :backup-id :recovery-point-id :mode :categories :resolutions
   :confirm-memory-reactivation? :include-expired? :no-prior-app-commands?])

(defn restore-plan-digest
  "Digest of the restore plan carried by journal STATE.  The request map is
  the authority for offline records; a supervisor record that keeps plan
  fields at the top level is read through the same set."
  [restore-state]
  (when (map? restore-state)
    (let [plan (or (when (map? (:request restore-state)) (:request restore-state))
                   (dissoc restore-state :request))]
      (codec/sha256-hex
       (codec/canonical-bytes
        (into (sorted-map)
              (keep (fn [k]
                      (when (contains? plan k)
                        [k (get plan k)])))
              restore-plan-fields))))))

(defn storage-identity
  "Best-effort identity of the store the restore wrote: the absolute store
  path, or the plain absolute path when the store does not exist yet.  It is
  evidence, not authority, and an unreadable path must not refuse the journal
  write that records the attempt."
  [path]
  (let [text (some-> path str str/trim)]
    (when-not (str/blank? text)
      (let [target (Path/of text (make-array String 0))]
        (try
          (.toRealPath target (make-array LinkOption 0))
          (catch Throwable _
            (.toAbsolutePath target)))))))

(defn- confirm-digest
  [state restore-digest storage-id]
  (codec/sha256-hex
   (codec/canonical-bytes
    {:state (state-digest state)
     :restore restore-digest
     :storage storage-id})))

(def acknowledgment-codes
  "`inspect-and-accept-partial` accepts restored data the operator compared
  with the store; `inspect-accept-unknown-commit` accepts a record whose
  storage transaction could be neither proven nor refuted; a CLI store path
  is required for both.  `restore-another-backup` already replaced the state,
  so it needs no store path."
  #{:inspect-and-accept-partial :inspect-accept-unknown-commit
    :restore-another-backup})

(defn storage-drift
  "Path facts an operator needs to compare before accepting a partial restore:
  the store path and modification time.  It is not a content hash; the store
  is open elsewhere while the operator investigates."
  [path]
  (try
    (let [target (Path/of (str path) (make-array String 0))
          attrs (Files/readAttributes
                 target
                 "java.nio.file.attribute.BasicFileAttributes"
                 (make-array LinkOption 0))]
      {:path (str target)
       :real-path (str (.toRealPath target (make-array LinkOption 0)))
       :size (long (.size attrs))
       :modified-at (str (.lastModifiedTime attrs))})
    (catch Throwable _ nil)))

;; `acknowledge!` refuses a non-blocking status; the predicate lives with the
;; start gate below.
(declare blocking-failure?)

(defn acknowledge!
  "Record that an operator inspected a terminal failure and accepts the
  current storage as the starting point.  It is the only unblocking path.

  It never rewrites the failure into a success: `:status` stays terminal, the
  restore evidence stays, and `:acknowledged? true` is the sole reason a later
  start is allowed to open a router.  The caller must pass the digests it
  inspected, so a changed journal, plan or store is refused rather than
  silently acknowledged."
  [config {:keys [maintenance-id expected-state-digest expected-restore-digest
                  expected-storage-drift code operator note]}]
  (when-not (and (string? operator) (not (str/blank? operator)))
    (fail! :BACKUP_MAINTENANCE_ACK_INVALID
           "An acknowledgement needs the operator identity"
           {:maintenance-id maintenance-id}))
  (when-not (contains? acknowledgment-codes (keyword code))
    (fail! :BACKUP_MAINTENANCE_ACK_INVALID
           "Unknown acknowledgement code"
           {:maintenance-id maintenance-id
            :codes acknowledgment-codes}))
  (when-not (= [] expected-storage-drift)
    (fail! :BACKUP_MAINTENANCE_STORE_UNCHANGED
           "The store still matches the failure evidence; accept it only after you changed something or restored another backup"
           {:maintenance-id maintenance-id
            :storage (seq expected-storage-drift)}))
  (update!
   config
   {:expected-digest expected-state-digest
    :expect
    (fn [state]
      (when-not (and state
                     (= (str maintenance-id) (str (:maintenance-id state))))
        (fail! :BACKUP_MAINTENANCE_NOT_FOUND
               "The maintenance record does not exist"
               {:maintenance-id maintenance-id}))
      (when (:acknowledged? state)
        (fail! :BACKUP_MAINTENANCE_ACK_INVALID
               "The maintenance record is already acknowledged"
               {:maintenance-id maintenance-id}))
      (when-not (blocking-failure? state)
        (fail! :BACKUP_MAINTENANCE_ACK_INVALID
               "Only a terminal failure that blocks startup can be acknowledged"
               {:maintenance-id maintenance-id
                :status (:status state)}))
      (let [status (keyword (:status state))
            committed? (or (= :failed-needs-recovery status)
                           (true? (:needs-recovery? state)))
            doubt? (and (not committed?)
                        (or (= :failed-commit-unproven status)
                            (true? (:commit-unproven? state))
                            ;; A generic attention record without storage
                            ;; evidence has the same unknown-commit meaning.
                            (= :failed-needs-attention status)))]
        (when (and committed?
                   (= :inspect-accept-unknown-commit (keyword code)))
          (fail! :BACKUP_MAINTENANCE_ACK_INVALID
                 "This failure has a proven partial restore; compare it with the store"
                 {:maintenance-id maintenance-id
                  :status (:status state)
                  :requires :inspect-and-accept-partial}))
        (when (and doubt?
                   (not (contains? #{:inspect-accept-unknown-commit
                                     :restore-another-backup}
                                   (keyword code))))
          (fail! :BACKUP_MAINTENANCE_ACK_INVALID
                 "This failure has no proven commit; acknowledge it as an unknown commit"
                 {:maintenance-id maintenance-id
                  :status (:status state)
                  :requires :inspect-accept-unknown-commit})))
      (when-not (= expected-restore-digest (restore-plan-digest state))
        (fail! :BACKUP_MAINTENANCE_STATE_CHANGED
               "The restore plan changed since the operator inspected it"
               {:maintenance-id maintenance-id
                :expected-restore-digest expected-restore-digest
                :actual-restore-digest (restore-plan-digest state)})))
    :f
    (fn [state]
      (let [restore-digest (restore-plan-digest state)
            storage-id (:storage-identity state)
            stamp (iso (now-ms))]
        (assoc state
               :acknowledged? true
               :acknowledged-at stamp
               :updated-at stamp
               :phase :done
               :acknowledgment
               (cond-> {:digest (confirm-digest state restore-digest
                                                storage-id)
                        :code (keyword code)
                        :operator operator
                        :at stamp
                        :maintenance-id (:maintenance-id state)
                        :status (:status state)
                        :restore-digest restore-digest
                        :storage (some-> storage-id str)
                        :storage-drift expected-storage-drift}
                 (not (str/blank? (str note)))
                 (assoc :note (str note))))))}))

;; ---------------------------------------------------------------------------
;; Offline CLI records
;;
;; The CLI and the supervisor share the document and lock, not a worker.
;; A journal that is not `:offline-cli` and is still active belongs to the
;; supervisor; the offline restore reuses it and leaves terminal recording to
;; the supervisor's own finish step.
;;
;; Every offline transition below runs inside `locked`, so two CLI processes,
;; or a CLI and a supervisor, cannot each decide from a stale read that they
;; may start.

(defn offline-record?
  [state]
  (= :offline-cli (get-in state [:request :source])))

(defn- journal-result
  "Keep only the restore facts the journal needs; never persist payloads,
  passwords or recovery-point state."
  [result]
  (when (map? result)
    (cond-> (select-keys result
                         [:status :mode :summary :permissions :error
                          :needs-attention? :needs-recovery? :rolled-back?
                          :recovery-point :applied :command-epoch
                          :command-retired-through :warnings
                          :store-observed? :store-unchanged? :store-committed?])
      (map? (:recovery-point result))
      (assoc :recovery-point (dissoc (:recovery-point result) :state)))))

(defn- restore-plan
  "The plan-bearing request an offline record keeps.  It is the evidence an
  operator confirms after a failure, so it holds no secret."
  [opts]
  (let [kind (or (:kind opts) :backup)]
    (into {:kind (keyword kind) :source :offline-cli}
          (keep (fn [k]
                  (let [v (get opts k)]
                    (when (some? v) [k v]))))
          (remove #{:kind} restore-plan-fields))))

(defn- refused-supervised-begin!
  "A supervisor reuse is allowed only for its own live record: the record must
  belong to the supervisor, be in the restore phase, and match the exact
  maintenance ID and request digest that the supervisor wrote.  The phase
  check matters: the supervisor marks `:status :restoring` *before* it calls
  the offline entry point, so the status alone cannot separate it from a live
  offline restore.  A caller that passes the flag alone cannot reuse or
  convert an offline record."
  [state maintenance-id expected-restore-digest]
  (when (or (offline-record? state)
            (not= :restoring (keyword (:phase state))))
    (fail! :BACKUP_MAINTENANCE_ACTIVE
           "An offline or supervised maintenance operation is already active"
           {:maintenance-id (:maintenance-id state)
            :status (:status state)}))
  (when (or (not= (str maintenance-id) (str (:maintenance-id state)))
            (not= (:request-digest state) expected-restore-digest))
    (fail! :BACKUP_MAINTENANCE_ACTIVE
           "The active supervisor record is not the request this caller holds"
           {:maintenance-id (:maintenance-id state)
            :status (:status state)}))
  state)

(defn blocking-failure?
  "True when STATE is a terminal failure that keeps the next start blocked
  until an operator acknowledges it.

  `:failed` is normally blocking because legacy records cannot prove what
  happened.  A record that explicitly says `:restore-attempted? false` was
  written before the restore thunk ran; no storage write was possible, so it
  does not block."
  [state]
  (and (map? state)
       (contains? blocking-terminal-statuses (keyword (:status state)))
       (not (and (= :failed (keyword (:status state)))
                 (false? (:restore-attempted? state))
                 (not (true? (:needs-attention? state)))
                 (not (true? (:needs-recovery? state)))
                 (not (true? (:commit-unproven? state)))
                 (not (true? (:storage-touched? state)))))))

(defn blocking-state
  "The journal state that keeps the next start blocked, or nil.

  Blocked:

    - any active record.  An offline CLI may be inside its storage
      transaction; a supervisor record belongs to an operation that did not
      reach a terminal decision;
    - a terminal failure that storage may have reached (`:failed`,
      `:failed-needs-attention`, `:failed-needs-recovery`,
      `:failed-commit-unproven`) until an operator acknowledges it.  A
      `:failed` record that explicitly says `:restore-attempted? false` is a
      pre-restore failure: no restore thunk ran, so it is history.

  Not blocked: `:failed-before-commit` proves that the restore was observed
  not to reach a commit; `:stop-failed` proves that no write happened;
  `:restart-failed` means the restore completed and starting the application
  is the recovery;
  `:complete`, `:no-changes`, `:already-imported` and `:cancelled` are not
  blocking failures."
  [state]
  (when (map? state)
    (let [status (keyword (:status state))]
      (when (or (active-status? status)
                (and (blocking-failure? state)
                     (not (true? (:acknowledged? state)))))
        state))))

(defn offline-restore-begin!
  "Record that an offline restore is about to touch storage.

  Returns the journal state.  The active check, the refusal and the write all
  run inside one cross-process critical section, so two concurrent offline
  restores cannot both start.  A supervisor restore reuses its own active
  record only when the caller proves it holds that exact request: the same
  maintenance ID and the same request digest the supervisor wrote.  Anything
  else is refused."
  [config opts]
  (let [maintenance-id (or (some-> (:offline-restore-id opts) str)
                           (str "offline-" (UUID/randomUUID)))
        supervised? (true? (:supervisor-restore? opts))]
    (locked
     config
     (fn []
       (let [loaded (read-journal config)]
         (when-not (:ok? loaded)
           (throw (ex-info (or (get-in loaded [:error :message])
                               "maintenance journal is invalid")
                           (or (:error loaded)
                               {:code :BACKUP_MAINTENANCE_JOURNAL_INVALID
                                :message "maintenance journal is invalid"}))))
         (let [existing (:state loaded)]
           (cond
             supervised?
             (cond
               (and existing (not (offline-record? existing)))
               (refused-supervised-begin!
                existing maintenance-id (:expected-restore-digest opts))

               existing
               (fail! :BACKUP_MAINTENANCE_ACTIVE
                      "An offline or supervised maintenance operation is already active"
                      {:maintenance-id (:maintenance-id existing)
                       :status (:status existing)})

               :else
               (fail! :BACKUP_MAINTENANCE_NOT_FOUND
                      "supervisor restore has no active journal record"
                      {:maintenance-id maintenance-id}))

             (and existing (active-status? (:status existing)))
             (fail! :BACKUP_MAINTENANCE_ACTIVE
                    "An offline or supervised maintenance operation is already active"
                    {:maintenance-id (:maintenance-id existing)
                     :status (:status existing)})

             (blocking-state existing)
             (fail! :BACKUP_MAINTENANCE_ACTIVE
                    "The previous restore failed and is not acknowledged; inspect it first"
                    {:maintenance-id (:maintenance-id existing)
                     :status (:status existing)
                     :needs-recovery? (:needs-recovery? existing)})

             :else
             (let [now (iso (now-ms))
                   state (cond-> {:maintenance-id maintenance-id
                                  :request (restore-plan opts)
                                  :request-digest "offline"
                                  :restore-plan-digest
                                  (restore-plan-digest (restore-plan opts))
                                  :storage-identity
                                  (some-> (:db-path opts)
                                          storage-identity
                                          str)
                                  :status :restoring
                                  :phase :restoring
                                  :storage-touched? false
                                  :created-at now
                                  :updated-at now
                                  :preview nil
                                  :staging nil
                                  :needs-attention? false
                                  :needs-recovery? false
                                  :retryable? false
                                  :observed-by-client? false
                                  :process {:pid (.pid (ProcessHandle/current))
                                            :started-at now}}
                           existing (assoc :previous
                                           (dissoc existing :previous)))]
               (write-journal! config state)
               state))))))))

(defn offline-restore-mark-committed!
  "Record, under the lock, that the storage transaction returned.  From this
  point the store may hold restored data even if a later external step throws."
  [config state storage-touched?]
  (if-not (offline-record? state)
    state
    (update!
     config
     {:expect (fn [current]
                (when-not (and current
                               (= (str (:maintenance-id state))
                                  (str (:maintenance-id current))))
                  (fail! :BACKUP_MAINTENANCE_ID_CONFLICT
                         "maintenance journal belongs to a different operation"
                         {:expected (:maintenance-id state)
                          :actual (:maintenance-id current)})))
      :f (fn [current]
           (let [committed? (or (true? storage-touched?)
                                (true? (:storage-touched? current)))]
             (cond-> (assoc current
                            :storage-touched? committed?
                            :store-unchanged? (and (not committed?)
                                                   (true? (:store-unchanged?
                                                           current)))
                            ;; A proven commit explains the doubt; only keep
                            ;; the flag when the commit is still unproven.
                            :commit-unproven? (and (not committed?)
                                                   (true? (:commit-unproven?
                                                           current)))
                            :updated-at (iso (now-ms)))
               committed? (assoc :committed-at (iso (now-ms))))))})))

(defn offline-restore-mark-commit-unproven!
  "Record, under the lock, that the restore threw while its storage
  transaction may have been in flight.  The finish turns this into
  `:failed-commit-unproven` unless the result later proves a commit."
  [config state]
  (if-not (offline-record? state)
    state
    (update!
     config
     {:expect (fn [current]
                (when-not (and current
                               (= (str (:maintenance-id state))
                                  (str (:maintenance-id current))))
                  (fail! :BACKUP_MAINTENANCE_ID_CONFLICT
                         "maintenance journal belongs to a different operation"
                         {:expected (:maintenance-id state)
                          :actual (:maintenance-id current)})))
      :f (fn [current]
           (assoc current
                  :commit-unproven? true
                  :updated-at (iso (now-ms))))})))

(defn partial-restore?
  "True when RESULT carries the adapter's in-transaction commit evidence:
  `:storage-restored?` proves that `storage/restore-state!` returned even
  though a later external step threw.  The adapter applies attachment bytes and
  skill files before that transaction, so a throw from those steps is not
  persistence evidence and must not be reported as recovery."
  [result]
  (or (true? (:storage-restored? (get-in result [:error :details])))
      (true? (:storage-restored? (get-in result [:error :data :details])))
      (true? (:storage-restored? (get-in result [:error :data])))))

(defn persistence-evidence
  "The three facts that may prove a storage transaction:

    :result       a map; `:command-epoch` and `:storage-restored?` mean the
                  adapter returned after the transaction;
    :committed?   a state flag; true means the writer marked the commit;
    :untouched?   a state flag or `:store-unchanged?`; true means the store
                  version was read before and after and did not move;
    :in-doubt?    a state flag; true means the writer threw while the
                  transaction may have been in flight (interrupted commit,
                  cancelled thread, or a throw after the fence).

  An exception alone is evidence of neither.  `failure-status` reads this map."
  [{:keys [result committed? untouched? in-doubt? error]}]
  (let [result (or result)
        error (or error (:error result))
        committed? (or (true? committed?)
                       (true? (:store-committed? result))
                       (some? (:command-epoch result))
                       (partial-restore? result)
                       (partial-restore? {:error error}))
        ;; Core reports `:needs-recovery? true` when it could not prove a
        ;; rollback.  That is doubt, not a commit, and it must not be read as
        ;; "nothing happened".
        in-doubt? (and (not committed?)
                       (or (true? in-doubt?)
                           (and (true? (:needs-recovery? result))
                                (not (true? (:rolled-back? result))))))]
    {:result result
     :committed? committed?
     :untouched? (and (not committed?)
                      (or (true? untouched?)
                          (true? (:store-unchanged? result))))
     :in-doubt? in-doubt?}))

(defn failure-status
  "Classify a failed offline restore from EVIDENCE (`persistence-evidence`).

    committed           -> `:failed-needs-recovery`, the store may hold
                           restored data;
    in doubt            -> `:failed-commit-unproven`, the commit cannot be
                           proven either way and is never read as nothing;
    untouched           -> `:failed-before-commit`, the store was observed
                           before and after with the same version;
    otherwise           -> `:failed`, no evidence at all (legacy records and
                           direct writes), which still blocks.

  A plain exception is never read as a proven pre-commit failure: without an
  observation it reaches `:failed`, and inside the observed region without a
  version change it reaches `:failed-before-commit`."
  [evidence]
  (let [{:keys [committed? untouched? in-doubt?]}
        (persistence-evidence evidence)]
    (cond
      committed? :failed-needs-recovery
      in-doubt? :failed-commit-unproven
      untouched? :failed-before-commit
      :else :failed)))

(defn offline-restore-finish!
  "Record the terminal offline restore result.  A failure keeps
  `:needs-attention? true`; it is never rewritten as a success.  A
  supervisor-owned record is left to the supervisor's finish step.

  EVIDENCE is `persistence-evidence`: what the caller proved about the storage
  transaction.  A proven commit is `:failed-needs-recovery`; a throw that the
  caller could not prove happened before the transaction is
  `:failed-commit-unproven`; an observed unchanged store is
  `:failed-before-commit`; no evidence at all is `:failed`.  Each status names
  exactly what is known, so no record calls an unproven commit
  `not committed`.

  A result the caller accepted as success is not re-judged as a failure: the
  caller owns the exit decision (`restore-result` in the CLI,
  `restore-confirmed?` in the in-process coordinator).  A flagged success keeps
  its `:needs-recovery? true` and stays `:complete` history, so it neither
  blocks the next start nor hides the flag in the journal.

  EXPECT, when supplied, runs inside the same lock before the write.  It
  refuses a record another process replaced, so a finish never overwrites an
  acknowledgement."
  ([config state result error]
   (offline-restore-finish! config state result error nil))
  ([config state result error {:keys [expect]}]
   (if-not (offline-record? state)
     state
     (let [marked (when (or (true? (:storage-touched? state))
                            (true? (:commit-unproven? state))
                            (true? (:store-unchanged? state)))
                    {:committed? (true? (:storage-touched? state))
                     :untouched? (true? (:store-unchanged? state))
                     :in-doubt? (true? (:commit-unproven? state))})
           evidence (persistence-evidence
                     (merge {:result (or result) :error error}
                            marked))
           error (or error (:error result))
           ok? (and (nil? error)
                    (success-status? (:status result))
                    (not (true? (:needs-attention? result)))
                    (not (true? (:needs-recovery? result))))
           success? (and ok? (nil? (:rolled-back? result)))
           cancelled? (= :cancelled (keyword (:status result)))
           status (cond
                    success? :complete
                    cancelled? :cancelled
                    :else (failure-status evidence))
           next (-> state
                    (assoc :status status
                           ;; `:restoring` is an active phase; a terminal record
                           ;; must leave it, or `active-status?` would keep
                           ;; treating a finished record as unfinished.
                           :phase (if (contains? #{:complete :cancelled} status)
                                    :done
                                    :failed)
                           :finished-at (iso (now-ms))
                           :updated-at (iso (now-ms))
                           :no-changes? (= :no-changes (:status result))
                           :already-imported? (= :already-imported
                                                (:status result))
                           :result (journal-result result)
                           :restore (journal-result result)
                           :restart {:ok? true
                                     :recovered? false
                                     :verification nil}
                           :recovered-after-restart? false
                           :needs-attention? (not (contains? #{:complete
                                                               :cancelled}
                                                             status))
                           :needs-recovery? (if success?
                                              (true? (:needs-recovery? result))
                                              (= :failed-needs-recovery status))
                           :storage-touched? (:committed? evidence)
                           :store-unchanged? (:untouched? evidence)
                           :commit-unproven? (:in-doubt? evidence)
                           :error (when-not success? error)))]
       (update! config
                {:expect
                 (fn [current]
                   (when-not (and current
                                  (= (str (:maintenance-id state))
                                     (str (:maintenance-id current))))
                     (fail! :BACKUP_MAINTENANCE_ID_CONFLICT
                            "maintenance journal belongs to a different operation"
                            {:expected (:maintenance-id state)
                             :actual (:maintenance-id current)}))
                   (when expect (expect current)))
                 :f (constantly next)})))))

(defn startup-blocking-state
  "The journal state that must keep a cold start blocked, or nil.

  `:supervisor-restart-id`, when supplied, is the maintenance ID of the
  supervisor's own in-process restart.  That restart marks the record
  `:restarting` immediately before it calls the start gate, so its own active
  record is not a cold-start hazard.  Every other caller supplies no ID and is
  subject to `blocking-state`."
  ([config]
   (startup-blocking-state config nil))
  ([config {:keys [supervisor-restart-id]}]
   (let [journal (read-consistent config)]
     (when-not (:ok? journal)
       (throw (ex-info (or (get-in journal [:error :message])
                           "maintenance journal is invalid")
                       (or (:error journal)
                           {:code :BACKUP_MAINTENANCE_JOURNAL_INVALID
                            :message "maintenance journal is invalid"}))))
     (let [state (:state journal)]
       (when (and state
                  (not (and supervisor-restart-id
                            (not (offline-record? state))
                            (= (str supervisor-restart-id)
                               (str (:maintenance-id state)))
                            (= :restarting (keyword (:status state))))))
         (blocking-state state))))))
