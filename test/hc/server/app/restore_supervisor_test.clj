(ns hc.server.app.restore-supervisor-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hc.server.app.restore-supervisor :as supervisor]
            [hc.server.app.system :as system]
            [hc.server.backup :as backup]
            [hc.server.backup.command-epoch :as backup-command-epoch]
            [hc.server.backup.offline :as offline]
            [hc.server.backup.maintenance-journal :as journal]
            [hc.server.backup.storage-adapter :as storage-adapter]
            [hc.server.storage :as storage])
  (:import (java.nio.file Files OpenOption)
           (java.nio.file.attribute FileAttribute)))

(def ^:private password "correct horse battery staple")

(defn- temp-dir
  []
  (str (Files/createTempDirectory "hc-restore-supervisor-" (make-array FileAttribute 0))))

(defn- delete-tree!
  [path]
  (doseq [file (reverse (file-seq (io/file path)))]
    (try
      (.delete ^java.io.File file)
      (catch Exception _ nil))))

(defn- config
  []
  {:data-dir (temp-dir)})

(defn- journal
  [config]
  (#'supervisor/read-journal config))

(defn- write-state!
  [config state]
  (#'supervisor/write-journal! config state))

(defn- base-state
  [maintenance-id status]
  {:maintenance-id maintenance-id
   :request {:kind :backup :backup-id "backup-1" :mode :merge
             :categories [:conversations]}
   :request-digest "digest"
   :status status
   :phase (if (= status :restoring) :restoring :done)
   :created-at "2026-01-01T00:00:00Z"
   :updated-at "2026-01-01T00:00:00Z"
   :preview {:mode :merge :fingerprint "fingerprint"}
   :needs-attention? false
   :retryable? false
   :observed-by-client? false})

(defn- request-payload
  [maintenance-id]
  {:kind :backup/maintenance-restore
   :maintenance-id maintenance-id
   :backup-id "backup-1"
   :mode :merge
   :categories [:conversations]
   :confirm? true
   :password password})

(deftest same-maintenance-id-is-idempotent
  (let [cfg (config)
        id "maintenance-1"
        payload (request-payload id)
        request (#'supervisor/public-request payload)
        digest (#'supervisor/request-digest id request)
        state (assoc (base-state id :restoring)
                     :request request
                     :request-digest digest)]
    (try
      (supervisor/prepare! {:config cfg :maintenance nil})
      (write-state! cfg state)
      (let [reply (supervisor/handle-command! payload)]
        (is (true? (:ok? reply)))
        (is (= :restoring (get-in reply [:receipt :status])))
        (is (= id (get-in reply [:receipt :maintenance-id]))))
      (is (nil? (get-in (:state (journal cfg)) [:request :password]))
          "the journal never stores the restore password")
      (is (nil? (get-in (:state (journal cfg)) [:request :password-confirm]))
          "the journal never stores the password confirmation")
    (finally
      (delete-tree! (:data-dir cfg))))))

(deftest same-id-different-request-is-refused
  (let [cfg (config)
        id "maintenance-2"
        payload (request-payload id)
        request (#'supervisor/public-request payload)
        digest (#'supervisor/request-digest id request)]
    (try
      (supervisor/prepare! {:config cfg :maintenance nil})
      (write-state! cfg (assoc (base-state id :restoring)
                               :request request
                               :request-digest digest))
      (let [reply (supervisor/handle-command!
                   (assoc payload :backup-id "backup-2"))]
        (is (false? (:ok? reply)))
        (is (= :BACKUP_MAINTENANCE_ID_CONFLICT
               (get-in reply [:error :code]))))
    (finally
      (delete-tree! (:data-dir cfg))))))

(deftest another-active-maintenance-is-refused
  (let [cfg (config)
        id "maintenance-3"
        payload (request-payload id)
        request (#'supervisor/public-request payload)
        digest (#'supervisor/request-digest id request)]
    (try
      (supervisor/prepare! {:config cfg :maintenance nil})
      (write-state! cfg (assoc (base-state id :restoring)
                               :request request
                               :request-digest digest))
      (let [reply (supervisor/handle-command!
                   (assoc payload :maintenance-id "maintenance-4"))]
        (is (false? (:ok? reply)))
        (is (= :BACKUP_MAINTENANCE_ACTIVE (get-in reply [:error :code]))))
    (finally
      (delete-tree! (:data-dir cfg))))))

(deftest confirmation-is-required
  (let [cfg (config)]
    (try
      (supervisor/prepare! {:config cfg :maintenance nil})
      (let [reply (supervisor/handle-command!
                   (assoc (request-payload "maintenance-5")
                          :confirm? false))]
        (is (false? (:ok? reply)))
        (is (= :BACKUP_RESTORE_UNCONFIRMED (get-in reply [:error :code]))))
    (finally
      (delete-tree! (:data-dir cfg))))))

(deftest tampered-journal-is-not-trusted
  (let [cfg (config)
        path (#'supervisor/journal-path cfg)]
    (try
      (supervisor/prepare! {:config cfg :maintenance nil})
      (write-state! cfg (base-state "maintenance-6" :complete))
      (let [bytes (Files/readAllBytes path)
            text (String. ^bytes bytes "UTF-8")
            changed (.getBytes (str/replace text "maintenance-6" "maintenance-9")
                               "UTF-8")]
        (Files/write path changed (make-array OpenOption 0)))
      (let [result (journal cfg)]
        (is (false? (:ok? result)))
        (is (= :BACKUP_MAINTENANCE_JOURNAL_INVALID
               (get-in result [:error :code]))))
    (finally
      (delete-tree! (:data-dir cfg))))))

(deftest terminal-status-is-observed-by-client
  (let [cfg (config)
        state (assoc (base-state "maintenance-7" :complete)
                     :restart {:ok? true :url "http://127.0.0.1:1"}
                     :restore {:status :complete
                               :recovery-point {:id "rp-1"}})]
    (try
      (supervisor/prepare! {:config cfg :maintenance nil})
      (write-state! cfg state)
      (let [reply (supervisor/handle-command!
                   {:kind :backup/maintenance-status
                    :maintenance-id "maintenance-7"})]
        (is (true? (:ok? reply)))
        (is (= :complete (get-in reply [:receipt :status])))
        (is (true? (get-in reply [:receipt :observed-by-client?])))
        (is (= "rp-1" (get-in reply [:receipt :recovery-point :id]))))
      (is (true? (get-in (:state (journal cfg)) [:observed-by-client?])))
    (finally
      (delete-tree! (:data-dir cfg))))))

(deftest unknown-maintenance-id-is-honest
  (let [cfg (config)]
    (try
      (supervisor/prepare! {:config cfg :maintenance nil})
      (write-state! cfg (base-state "maintenance-8" :complete))
      (let [reply (supervisor/handle-command!
                   {:kind :backup/maintenance-status
                    :maintenance-id "missing"})]
        (is (false? (:ok? reply)))
        (is (= :BACKUP_MAINTENANCE_NOT_FOUND (get-in reply [:error :code]))))
    (finally
      (delete-tree! (:data-dir cfg))))))

(deftest recovery-point-state-is-never-projected
  (let [public (#'supervisor/public-restore-result
                {:status :complete
                 :recovery-point {:id "rp-1"
                                  :created-at "2026-01-01T00:00:00Z"
                                  :state {:categories {:conversations
                                                       [{:text "secret"}]}}}})]
    (is (= "rp-1" (get-in public [:recovery-point :id])))
    (is (nil? (get-in public [:recovery-point :state])))))

(deftest interrupted-active-states-are-never-rerun
  (let [base (base-state "maintenance-9" :restoring)]
    (is (= :failed-needs-attention
           (:status (#'supervisor/reconcile-state base nil))))
    (is (true? (:needs-attention?
                (#'supervisor/reconcile-state base nil))))
    (is (nil? (:restore (#'supervisor/reconcile-state base nil))))
    (is (= :complete
           (:status (#'supervisor/reconcile-state
                     (assoc base :restore {:status :complete}) nil))))
    (is (= :failed
           (:status (#'supervisor/reconcile-state
                     (assoc base :restore {:status :failed
                                           :error {:code :TEST_RESTORE}})
                     nil))))
    (is (= :failed-needs-recovery
           (:status (#'supervisor/reconcile-state
                     (assoc base :restore {:status :failed
                                           :store-observed? true
                                           :store-committed? true})
                     nil)))
        "a failed restore that proved its commit reconciles to recovery")
    (is (= :failed-commit-unproven
           (:status (#'supervisor/reconcile-state
                     (assoc base
                            :commit-unproven? true
                            :restore {:status :failed})
                     nil)))
        "an unproven commit reconciles to its own status")
    (is (= :failed-before-commit
           (:status (#'supervisor/reconcile-state
                     (assoc base :restore {:status :failed
                                           :store-observed? true
                                           :store-unchanged? true})
                     nil)))
        "a store observed unchanged reconciles to history")
    (let [stopping (base-state "maintenance-10" :stopping)
          reconciled (#'supervisor/reconcile-state stopping nil)]
      (is (= :failed (:status reconciled)))
      (is (true? (:retryable? reconciled)))
      (is (false? (:needs-attention? reconciled)))
      (is (false? (:restore-attempted? reconciled))
          "no restore thunk ran, so the failure cannot have touched storage")
      (is (nil? (:restore reconciled))))
    (doseq [status [:accepted :validating]]
      (let [reconciled (#'supervisor/reconcile-state
                        (base-state (str "maintenance-" (name status)) status)
                        nil)]
        (is (= :failed (:status reconciled)))
        (is (true? (:retryable? reconciled)))
        (is (false? (:needs-attention? reconciled)))
        (is (false? (:restore-attempted? reconciled)))))))

(deftest no-change-preview-is-a-complete-no-op
  (let [state (base-state "maintenance-11" :accepted)
        next (#'supervisor/no-op-state state {:no-changes? true})]
    (is (= :complete (:status next)))
    (is (= :done (:phase next)))
    (is (true? (:no-changes? next)))
    (is (= :no-changes (get-in next [:result :status])))))

(deftest offline-restore-journal-survives-a-crash-and-blocks-startup
  (let [cfg (config)]
    (try
      (supervisor/prepare! {:config cfg :maintenance nil})
      (let [state (journal/offline-restore-begin! cfg {:kind :backup})]
        (is (= :restoring (:status state)))
        (is (= :offline-cli (get-in state [:request :source])))
        (is (= (:maintenance-id state)
               (:maintenance-id (journal/startup-blocking-state cfg)))
            "an unfinished offline restore is visible to startup")
        (testing "a second restore cannot start over the active journal"
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo
               #"already active"
               (journal/offline-restore-begin! cfg {:kind :backup})))))
      (finally
        (delete-tree! (:data-dir cfg))))))

(deftest a-finish-preserves-an-acknowledgment-and-records-its-evidence
  (let [cfg (config)
        id "maintenance-13"]
    (try
      (supervisor/prepare! {:config cfg :maintenance nil})
      (write-state! cfg (assoc (base-state id :failed-needs-recovery)
                               :phase :done
                               :request-digest "digest-1"
                               :acknowledged? true
                               :acknowledged-at "2026-01-01T00:00:00Z"
                               :acknowledgment {:operator "operator"}))
      (let [next (#'supervisor/finish!
                  cfg id
                  {:status :failed
                   :restore {:value {:status :failed
                                     :store-observed? true
                                     :store-committed? true}}
                   :stop {}
                   :restart {}})]
        (is (= :failed-needs-recovery (:status next)))
        (is (true? (:acknowledged? next))
            "the operator's decision is never erased by a late finish")
        (is (= "operator" (get-in next [:acknowledgment :operator])))
        (is (true? (:storage-touched? next)))
        (is (= :failed-needs-recovery (:status (:previous next))))
        (is (some? (get-in next [:restore :supervised-notice]))
            "replacing a finished decision stays visible"))
      (finally
        (delete-tree! (:data-dir cfg))))))

(deftest a-routine-finish-carries-no-replacement-notice
  (let [cfg (config)
        id "maintenance-14"]
    (try
      (supervisor/prepare! {:config cfg :maintenance nil})
      (write-state! cfg (assoc (base-state id :restoring)
                               :phase :restoring
                               :request-digest "digest-1"))
      (let [next (#'supervisor/finish!
                  cfg id
                  {:status :failed
                   :restore {:value {:status :failed}}
                   :stop {}
                   :restart {}})]
        (is (nil? (get-in next [:restore :supervised-notice])))
        (is (nil? (:previous next))
            "the in-progress record is not a replaced decision"))
      (finally
        (delete-tree! (:data-dir cfg))))))

(defn- offline-failure!
  "Finish an offline record with a failure that observed an untouched store.
  `:store-unchanged? true` is what `offline/run-observed-restore` stamps when
  it saw the same store version before and after; only that observation makes
  `:failed-before-commit` honest."
  [cfg state]
  (let [error {:code :BACKUP_RESTORE_FAILED
               :message "restore refused before writing"}]
    (journal/offline-restore-finish!
     cfg state
     {:status :failed :needs-attention? true :error error
      :store-observed? true :store-unchanged? true}
     error)))

(defn- restart-blocked?
  "Run the real startup gate for a journal that must block.  It returns the
  blocked result before opening the store, so no store is needed.  Use
  `journal/startup-blocking-state` for the open cases: a real start needs a
  complete configuration."
  [cfg]
  (let [d (:data-dir cfg)
        started (system/start! (assoc cfg
                                      :db-path (str d "/state.db")
                                      :blob-root (str d "/blobs")
                                      :worktree-root (str d "/worktrees")
                                      :artifact-root (str d "/artifacts")))]
    started))

(defn- supervisor-state
  [maintenance-id status]
  (assoc (base-state maintenance-id status)
         :request {:kind :backup :source :supervisor :backup-id "backup-1"
                   :mode :merge :categories [:conversations]}))

(deftest supervisor-terminal-failures-block-the-cold-start
  (let [cfg (config)]
    (try
      (supervisor/prepare! {:config cfg :maintenance nil})
      (testing "a proven commit is not treated as history"
        (write-state! cfg (-> (supervisor-state "maintenance-s1"
                                                :failed-needs-recovery)
                              (assoc :needs-recovery? true
                                     :storage-touched? true)))
        (is (= "maintenance-s1"
               (:maintenance-id (journal/startup-blocking-state cfg))))
        (let [started (restart-blocked? cfg)]
          (is (true? (:blocked? started)))
          (is (= :BACKUP_MAINTENANCE_RESTORE_FAILED
                 (get-in started [:error :code])))
          (is (true? (get-in started [:error :needs-recovery?])))
          (is (= "maintenance-s1"
                 (get-in started [:error :maintenance-id])))))
      (testing "an unproven commit blocks with its own flag"
        (write-state! cfg (-> (supervisor-state "maintenance-s2"
                                                :failed-commit-unproven)
                              (assoc :commit-unproven? true)))
        (let [started (restart-blocked? cfg)]
          (is (true? (:blocked? started)))
          (is (= :BACKUP_MAINTENANCE_RESTORE_FAILED
                 (get-in started [:error :code])))
          (is (true? (get-in started [:error :commit-unproven?])))
          (is (re-find #"neither proven nor refuted"
                       (get-in started [:error :message])))))
      (finally
        (delete-tree! (:data-dir cfg))))))

(deftest supervisor-safe-failures-and-acknowledgments-do-not-block
  (let [cfg (config)]
    (try
      (supervisor/prepare! {:config cfg :maintenance nil})
      (testing "contradictory pre-restore evidence still blocks"
        (is (true? (journal/blocking-failure?
                    {:status :failed
                     :restore-attempted? false
                     :storage-touched? true})))
        (is (false? (journal/blocking-failure?
                     {:status :failed
                      :restore-attempted? false
                      :storage-touched? false
                      :needs-attention? false}))))
      (testing "a failure before the restore thunk is history"
        (write-state! cfg (-> (supervisor-state "maintenance-preflight"
                                                :failed)
                              (assoc :restore-attempted? false
                                     :retryable? true
                                     :needs-attention? false)))
        (is (nil? (journal/startup-blocking-state cfg)))
        (let [state (:state (journal cfg))]
          (is (= :BACKUP_MAINTENANCE_ACK_INVALID
                 (try
                   (journal/acknowledge!
                    cfg {:maintenance-id (:maintenance-id state)
                         :expected-state-digest (journal/state-digest state)
                         :expected-restore-digest
                         (journal/restore-plan-digest state)
                         :expected-storage-drift []
                         :code :inspect-and-accept-partial
                         :operator "operator"})
                   nil
                   (catch clojure.lang.ExceptionInfo e
                     (:error (ex-data e)))))
              "a failure that does not block has nothing to acknowledge")))
      (testing "a restore that never reached a commit is history"
        (write-state! cfg (-> (supervisor-state "maintenance-safe"
                                                :failed-before-commit)
                              (assoc :store-unchanged? true)))
        (is (nil? (journal/startup-blocking-state cfg))))
      (testing "an acknowledged failure is not a cold-start hazard"
        (write-state! cfg (-> (supervisor-state "maintenance-ack"
                                                :failed-needs-recovery)
                              (assoc :needs-recovery? true
                                     :acknowledged? true
                                     :acknowledged-at "2026-01-01T00:00:00Z")))
        (is (nil? (journal/startup-blocking-state cfg))))
      (finally
        (delete-tree! (:data-dir cfg))))))

(deftest supervisor-active-record-is-allowed-only-for-its-own-restart
  (let [cfg (config)]
    (try
      (supervisor/prepare! {:config cfg :maintenance nil})
      (write-state! cfg (assoc (supervisor-state "maintenance-restarting"
                                                 :restarting)
                               :phase :restarting))
      (is (= "maintenance-restarting"
             (:maintenance-id (journal/startup-blocking-state cfg)))
          "a cold start must not open on an active record")
      (is (nil? (journal/startup-blocking-state
                 cfg {:supervisor-restart-id "maintenance-restarting"}))
          "the supervisor's own in-process restart is the one exception")
      (is (= "maintenance-restarting"
             (:maintenance-id
              (journal/startup-blocking-state
               cfg {:supervisor-restart-id "another-maintenance"})))
          "an unrelated start cannot borrow the exception")
      (finally
        (delete-tree! (:data-dir cfg))))))

(deftest prepare-reconciles-an-interrupted-supervisor-record
  (let [cfg (config)]
    (try
      (write-state! cfg (supervisor-state "maintenance-crashed" :restoring))
      (supervisor/prepare! {:config cfg :maintenance nil})
      (let [state (:state (journal cfg))]
        (is (= :failed-needs-attention (:status state))
            "an interrupted restore with no result is not treated as success")
        (is (true? (:needs-attention? state)))
        (is (true? (:restore-attempted? state)))
        (is (nil? (:restore state))))
      (is (= "maintenance-crashed"
             (:maintenance-id (journal/startup-blocking-state cfg)))
          "the reconciled record still blocks the cold start")
      (finally
        (delete-tree! (:data-dir cfg))))))

(deftest prepare-leaves-a-live-maintenance-record-alone
  (let [cfg (config)]
    (try
      (write-state! cfg (supervisor-state "maintenance-live" :restoring))
      (reset! @#'supervisor/worker "maintenance-live")
      (supervisor/prepare! {:config cfg :maintenance nil})
      (is (= :restoring (:status (:state (journal cfg))))
          "a live worker's active record is not reinterpreted")
      (is (= "maintenance-live"
             (:maintenance-id (journal/startup-blocking-state cfg)))
          "the live record still blocks any other start")
      (finally
        (reset! @#'supervisor/worker nil)
        (delete-tree! (:data-dir cfg))))))

(deftest prepare-does-not-rotate-a-store-it-will-block
  (let [cfg (config)
        rotated (atom false)]
    (try
      (write-state! cfg (-> (supervisor-state "maintenance-blocked"
                                              :failed-needs-recovery)
                            (assoc :needs-recovery? true)))
      (with-redefs [backup-command-epoch/rotate-store!
                    (fn [& _]
                      (reset! rotated true)
                      {:ok? true :epoch 2})]
        (supervisor/prepare! {:config cfg :maintenance nil}))
      (is (false? @rotated)
          "a blocked store is not changed before the operator acknowledges it")
      (finally
        (delete-tree! (:data-dir cfg))))))

(deftest a-restart-failure-keeps-the-storage-evidence
  (let [cfg (config)]
    (try
      (supervisor/prepare! {:config cfg :maintenance nil})
      (testing "an untouched store is history even when the restart failed"
        (write-state! cfg (base-state "maintenance-ra1" :restarting))
        (let [next (#'supervisor/finish!
                    cfg "maintenance-ra1"
                    {:status :failed-needs-attention
                     :needs-attention? true
                     :restore {:value {:status :failed
                                       :store-observed? true
                                       :store-unchanged? true}}
                     :stop {}
                     :restart {:ok? false
                               :error {:code :APP_START_FAILED}}})]
          (is (= :failed-before-commit (:status next)))
          (is (false? (:needs-attention? next))
              "an untouched store is history, not an attention item")
          (is (true? (:store-unchanged? next)))
          (is (false? (:needs-recovery? next)))
          (is (false? (:commit-unproven? next)))
          (is (nil? (journal/startup-blocking-state cfg))
              "a safe pre-commit failure must not block the next start")))
      (testing "a proven commit stays a recovery obligation"
        (write-state! cfg (base-state "maintenance-ra2" :restarting))
        (let [next (#'supervisor/finish!
                    cfg "maintenance-ra2"
                    {:status :failed-needs-attention
                     :needs-attention? true
                     :restore {:value {:status :failed
                                       :store-observed? true
                                       :store-committed? true}}
                     :stop {}
                     :restart {:ok? false}})]
          (is (= :failed-needs-recovery (:status next)))
          (is (true? (:needs-recovery? next)))
          (is (true? (:storage-touched? next)))
          (is (some? (journal/startup-blocking-state cfg)))))
      (testing "doubt stays unproven"
        (write-state! cfg (assoc (base-state "maintenance-ra3" :restarting)
                                 :commit-unproven? true))
        (let [next (#'supervisor/finish!
                    cfg "maintenance-ra3"
                    {:status :failed-needs-attention
                     :needs-attention? true
                     :restore {:value {:status :failed}}
                     :stop {}
                     :restart {:ok? false}})]
          (is (= :failed-commit-unproven (:status next)))
          (is (true? (:commit-unproven? next)))
          (is (some? (journal/startup-blocking-state cfg)))))
      (finally
        (delete-tree! (:data-dir cfg))))))

(deftest acknowledgment-code-follows-storage-evidence
  (let [cfg (config)]
    (try
      (supervisor/prepare! {:config cfg :maintenance nil})
      (testing "a generic attention record needs the unknown-commit code"
        (write-state! cfg (supervisor-state "maintenance-attn"
                                            :failed-needs-attention))
        (let [state (:state (journal cfg))
              ack {:maintenance-id (:maintenance-id state)
                   :expected-state-digest (journal/state-digest state)
                   :expected-restore-digest
                   (journal/restore-plan-digest state)
                   :expected-storage-drift []
                   :operator "operator"}]
          (is (= :BACKUP_MAINTENANCE_ACK_INVALID
                 (try
                   (journal/acknowledge!
                    cfg (assoc ack :code :inspect-and-accept-partial))
                   nil
                   (catch clojure.lang.ExceptionInfo e
                     (:error (ex-data e))))))
          (is (true? (:acknowledged?
                      (journal/acknowledge!
                       cfg (assoc ack :code
                                  :inspect-accept-unknown-commit)))))))
      (testing "a proven commit needs the partial code even under :failed"
        (write-state! cfg (-> (supervisor-state "maintenance-failed-commit"
                                                :failed)
                              (assoc :needs-recovery? true
                                     :storage-touched? true)))
        (let [state (:state (journal cfg))
              ack {:maintenance-id (:maintenance-id state)
                   :expected-state-digest (journal/state-digest state)
                   :expected-restore-digest
                   (journal/restore-plan-digest state)
                   :expected-storage-drift []
                   :operator "operator"}]
          (is (= :BACKUP_MAINTENANCE_ACK_INVALID
                 (try
                   (journal/acknowledge!
                    cfg (assoc ack :code
                               :inspect-accept-unknown-commit))
                   nil
                   (catch clojure.lang.ExceptionInfo e
                     (:error (ex-data e))))))))
      (finally
        (delete-tree! (:data-dir cfg))))))

(deftest a-crash-record-cannot-relabel-another-operation
  (let [cfg (config)
        other (supervisor-state "maintenance-other" :failed-needs-recovery)]
    (try
      (supervisor/prepare! {:config cfg :maintenance nil})
      (write-state! cfg other)
      (#'supervisor/finish-crash! cfg "maintenance-old" nil
                                  (ex-info "worker crashed" {}))
      (let [state (:state (journal cfg))]
        (is (= "maintenance-other" (:maintenance-id state)))
        (is (= :failed-needs-recovery (:status state))
            "a crashed worker cannot overwrite another operation"))
      (finally
        (delete-tree! (:data-dir cfg))))))

(deftest a-no-op-guarded-write-keeps-the-journal
  (let [cfg (config)
        state (supervisor-state "maintenance-noop" :complete)]
    (try
      (supervisor/prepare! {:config cfg :maintenance nil})
      (write-state! cfg state)
      (#'supervisor/write-journal-guarded! cfg (constantly nil) {})
      (let [loaded (journal cfg)]
        (is (true? (:ok? loaded))
            "a nil update must not publish an empty journal")
        (is (= "maintenance-noop" (get-in loaded [:state :maintenance-id])))
        (is (= :complete (get-in loaded [:state :status]))))
      (finally
        (delete-tree! (:data-dir cfg))))))

(deftest a-same-id-request-raced-into-the-lock-is-a-conflict
  (let [cfg (config)
        id "maintenance-race"
        payload (request-payload id)
        request (#'supervisor/public-request payload)
        existing (assoc (base-state id :complete)
                        :request request
                        :request-digest "other-digest")
        calls (atom 0)
        read-real @#'supervisor/read-journal]
    (try
      (supervisor/prepare! {:config cfg :maintenance nil})
      (write-state! cfg existing)
      (supervisor/install! {:backup {} :router {}})
      (with-redefs [supervisor/read-journal
                    (fn [_config]
                      (if (= 1 (swap! calls inc))
                        {:ok? true :state nil :path "test"}
                        {:ok? true :state existing :path "test"}))]
        (let [reply (supervisor/handle-command! payload)]
          (is (false? (:ok? reply)))
          (is (= :BACKUP_MAINTENANCE_ID_CONFLICT
                 (get-in reply [:error :code])))))
      (is (= "other-digest" (:request-digest (:state (read-real cfg))))
          "the raced same-ID record is not overwritten")
      (finally
        (delete-tree! (:data-dir cfg))))))

(deftest offline-failure-before-storage-is-history
  (let [cfg (config)]
    (try
      (supervisor/prepare! {:config cfg :maintenance nil})
      (let [state (journal/offline-restore-begin! cfg {:kind :backup})
            finished (offline-failure! cfg state)]
        (is (= :failed-before-commit (:status finished)))
        (is (true? (:needs-attention? finished)))
        (is (false? (:needs-recovery? finished))
            "nothing was written, so no recovery is claimed")
        (is (nil? (journal/startup-blocking-state cfg))
            "a proven pre-commit failure is history and opens the next start")
        (is (some? (journal/offline-restore-begin! cfg {:kind :backup}))
            "a new restore may replace a proven pre-commit history record"))
      (finally
        (delete-tree! (:data-dir cfg))))))

(deftest offline-failure-after-storage-commit-blocks-startup-until-acknowledged
  (let [cfg (config)]
    (try
      (supervisor/prepare! {:config cfg :maintenance nil})
      (let [state (journal/offline-restore-begin! cfg {:kind :backup
                                                       :backup-id "backup-1"})
            error {:code :BACKUP_WORKSPACE_RESTORE_FAILED
                   :message "workspace files could not be restored"
                   :details {:storage-restored? true}}
            finished (journal/offline-restore-finish!
                      cfg state
                      {:status :failed :needs-attention? true :error error}
                      error)]
        (is (= :failed-needs-recovery (:status finished))
            "a throw after the storage transaction is not a clean failure")
        (is (true? (:needs-recovery? finished)))
        (is (true? (:needs-attention? finished)))
        (is (not (contains? finished :acknowledged?)))
        (testing "the start gate now refuses instead of opening a router"
          (let [started (restart-blocked? cfg)]
            (is (true? (:blocked? started)))
            (is (= :BACKUP_OFFLINE_RESTORE_FAILED
                   (get-in started [:error :code])))
            (is (true? (get-in started [:error :needs-recovery?]))))))
      (finally
        (delete-tree! (:data-dir cfg))))))

(deftest an-unproven-commit-blocks-startup-with-its-own-code
  (let [cfg (config)]
    (try
      (supervisor/prepare! {:config cfg :maintenance nil})
      (let [state (journal/offline-restore-begin! cfg {:kind :backup
                                                       :backup-id "backup-1"})
            marked (journal/offline-restore-mark-commit-unproven! cfg state)
            finished (journal/offline-restore-finish!
                      cfg marked
                      {:status :failed
                       :error {:code :BACKUP_RESTORE_FAILED}}
                      {:code :BACKUP_RESTORE_FAILED})]
        (is (= :failed-commit-unproven (:status finished)))
        (is (false? (:needs-recovery? finished))
            "a blocking failure does not claim a commit it could not prove")
        (let [started (restart-blocked? cfg)]
          (is (true? (:blocked? started)))
          (is (= :BACKUP_OFFLINE_RESTORE_FAILED (get-in started [:error :code])))
          (is (true? (get-in started [:error :commit-unproven?])))
          (is (re-find #"neither proven nor refuted"
                       (get-in started [:error :message])))
          (is (re-find #"inspect-accept-unknown-commit"
                       (:hint started)))))
      (finally
        (delete-tree! (:data-dir cfg))))))

(deftest acknowledgment-requires-the-digests-the-operator-inspected
  (let [cfg (config)]
    (try
      (supervisor/prepare! {:config cfg :maintenance nil})
      (let [state (journal/offline-restore-begin! cfg {:kind :backup
                                                       :backup-id "backup-1"})
            error {:code :BACKUP_WORKSPACE_RESTORE_FAILED
                   :message "workspace files could not be restored"
                   :details {:storage-restored? true}}
            finished (journal/offline-restore-finish!
                      cfg state
                      {:status :failed :needs-attention? true :error error}
                      error)
            _ (is (= :failed-needs-recovery (:status finished)))
            state-digest (journal/state-digest finished)
            plan-digest (journal/restore-plan-digest finished)
            ack {:maintenance-id (:maintenance-id finished)
                 :expected-state-digest state-digest
                 :expected-restore-digest plan-digest
                 :expected-storage-drift []
                 :code :inspect-and-accept-partial
                 :operator "operator@example"
                 :note "compared with recovery point rp-1"}]
        (testing "a stale journal digest is refused"
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo
               #"changed in another process"
               (journal/acknowledge!
                cfg (assoc ack :expected-state-digest "stale")))))
        (testing "a stale plan digest is refused"
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo
               #"restore plan changed"
               (journal/acknowledge!
                cfg (assoc ack :expected-restore-digest "stale")))))
        (testing "an operator is required"
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo
               #"operator identity"
               (journal/acknowledge! cfg (dissoc ack :operator)))))
        (testing "an unchanged store is not accepted"
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo
               #"store still matches"
               (journal/acknowledge!
                cfg (assoc ack :expected-storage-drift
                           (journal/storage-drift
                            (str (:data-dir cfg) "/state.db")))))))
        (testing "the failure is kept, only the acknowledgment is added"
          (let [acked (journal/acknowledge! cfg ack)]
            (is (= :failed-needs-recovery (:status acked)))
            (is (= (:error finished) (:error acked)))
            (is (true? (:acknowledged? acked)))
            (is (= "operator@example"
                   (get-in acked [:acknowledgment :operator])))
            (is (= :inspect-and-accept-partial
                   (get-in acked [:acknowledgment :code])))
            (is (nil? (journal/startup-blocking-state cfg))
                "the acknowledgment is the documented unblocking path")
            (testing "a second acknowledgment is refused, not idempotent"
              (is (thrown-with-msg?
                   clojure.lang.ExceptionInfo
                   #"already acknowledged"
                   (journal/acknowledge!
                    cfg (assoc ack :expected-state-digest
                               (journal/state-digest acked)))))))))
      (finally
        (delete-tree! (:data-dir cfg))))))

(deftest failed-restore-without-a-proven-commit-is-not-a-clean-failure
  (let [cfg (config)]
    (try
      (supervisor/prepare! {:config cfg :maintenance nil})
      (let [state (journal/offline-restore-begin! cfg {:kind :backup})
            error {:code :BACKUP_RESTORE_FAILED
                   :message "apply failed"}
            marked (journal/offline-restore-mark-commit-unproven! cfg state)
            finished (journal/offline-restore-finish!
                      cfg marked
                      {:status :failed
                       :error error
                       :rolled-back? :unknown
                       :needs-recovery? true}
                      error)]
        (is (= :failed-commit-unproven (:status finished))
            "an unknown rollback is not a rollback")
        (is (false? (:needs-recovery? finished))
            "no commit was proven, so recovery is not claimed")
        (is (true? (:commit-unproven? finished)))
        (is (true? (some? (journal/startup-blocking-state cfg)))))
      (finally
        (delete-tree! (:data-dir cfg))))))

(deftest a-new-restore-cannot-start-over-an-unacknowledged-failure
  (let [cfg (config)]
    (try
      (supervisor/prepare! {:config cfg :maintenance nil})
      (let [state (journal/offline-restore-begin! cfg {:kind :backup})]
        (journal/offline-restore-finish!
         cfg state
         {:status :failed
          :needs-attention? true
          :error {:code :BACKUP_WORKSPACE_RESTORE_FAILED
                  :details {:storage-restored? true}}}
         {:code :BACKUP_WORKSPACE_RESTORE_FAILED})
        (is (= :BACKUP_MAINTENANCE_ACTIVE
               (try
                 (journal/offline-restore-begin! cfg {:kind :backup})
                 nil
                 (catch clojure.lang.ExceptionInfo e
                   (:error (ex-data e)))))
            "the previous result must be inspected before it is overwritten"))
      (finally
        (delete-tree! (:data-dir cfg))))))

(deftest failure-classification-keeps-the-unknown-as-needs-recovery
  (testing "no storage evidence and no commit mark is a clean refusal"
    (is (= :failed
           (journal/failure-status
            {:result {:status :failed
                      :error {:code :BACKUP_PASSWORD_INVALID}}
             :error {:code :BACKUP_PASSWORD_INVALID}}))))
  (testing "a crash mark from a previous step still counts as committed"
    (is (= :failed-needs-recovery
           (journal/failure-status
            {:result {:status :failed}
             :committed? true
             :error {:code :BACKUP_WORKSPACE_RESTORE_FAILED}}))))
  (testing "adapter evidence that storage was restored is enough"
    (is (= :failed-needs-recovery
           (journal/failure-status
            {:result {:status :failed
                      :error {:code :BACKUP_EXTENSIONS_RESTORE_FAILED
                              :details {:storage-restored? true}}}}))))
  (testing "a physical version bump proves the commit"
    (is (= :failed-needs-recovery
           (journal/failure-status
            {:result {:status :failed :store-committed? true}}))))
  (testing "an observed unchanged store is history, not a commit"
    (is (= :failed-before-commit
           (journal/failure-status
            {:result {:status :failed :store-observed? true
                      :store-unchanged? true}})))
    (is (= :failed-before-commit
           (journal/failure-status
            {:result {:status :failed}
             :untouched? true}))))
  (testing "doubt is its own status, never `:failed`"
    (is (= :failed-commit-unproven
           (journal/failure-status
            {:result {:status :failed
                      :error {:code :BACKUP_RESTORE_FAILED
                              :details {:rolled-back? :unknown}}}
             :in-doubt? true})))
    (is (= :failed-commit-unproven
           (journal/failure-status
            {:result {:status :failed
                      :error {:code :BACKUP_RESTORE_FAILED}}
             :in-doubt? true})))
    (is (= :failed-needs-recovery
           (journal/failure-status
            {:result {:status :failed
                      :error {:code :BACKUP_RESTORE_FAILED}}
             :in-doubt? true
             :committed? true}))
        "a proven commit outranks doubt")))

(deftest offline-entry-point-records-what-the-store-saw
  (let [cfg (config)]
    (try
      (testing "a store that never opened is a clean pre-commit failure"
        (is (thrown? clojure.lang.ExceptionInfo
                     (with-redefs [storage/open-store
                                   (fn [_]
                                     (throw (ex-info
                                             "cannot open"
                                             {:error
                                              :BACKUP_STORE_OPEN_FAILED})))]
                       (offline/restore!
                        {:db-path (str (:data-dir cfg) "/missing.db")
                         :data-dir (:data-dir cfg)
                         :backup-id "backup-1"}))))
        (let [state (:state (journal cfg))]
          (is (= :failed-before-commit (:status state)))
          (is (false? (:needs-recovery? state)))
          (is (false? (:commit-unproven? state)))
          (is (nil? (journal/startup-blocking-state cfg))
              "the restore never entered the storage region")))
      (testing "a throw after the storage fence needs an operator"
        (is (thrown? clojure.lang.ExceptionInfo
                     (with-redefs [storage/open-store (fn [_] ::store)
                                   storage/store-info (fn [_] {:version 7})
                                   storage-adapter/domain (fn [_ _] ::domain)
                                   backup/restore!
                                   (fn [& _]
                                     (throw (ex-info "workspace write failed"
                                                     {:error
                                                      :BACKUP_WORKSPACE_RESTORE_FAILED
                                                      :storage-restored? true})))
                                   storage/close-store! (fn [_] true)]
                       (offline/restore!
                        {:db-path (str (:data-dir cfg) "/state.db")
                         :data-dir (:data-dir cfg)
                         :backup-id "backup-1"}))))
        (let [state (:state (journal cfg))]
          (is (= :failed-needs-recovery (:status state)))
          (is (true? (:needs-recovery? state)))
          (is (= :offline-cli (get-in state [:request :source])))
          (is (nil? (get-in state [:request :password]))
              "the journal never stores the restore password")
          (is (some? (journal/startup-blocking-state cfg))
              "the next start stays blocked")))
      (finally
        (delete-tree! (:data-dir cfg))))))

(deftest a-proven-clean-throw-is-history
  (let [cfg (config)]
    (try
      (is (thrown? clojure.lang.ExceptionInfo
                   (with-redefs [storage/open-store (fn [_] ::store)
                                 storage/store-info (fn [_] {:version 7})
                                 storage-adapter/domain (fn [_ _] ::domain)
                                 backup/restore!
                                 (fn [& _]
                                   (throw (ex-info "password refused"
                                                   {:error :BACKUP_PASSWORD_INVALID
                                                    :store-unchanged? true})))
                                 storage/close-store! (fn [_] true)]
                     (offline/restore!
                      {:db-path (str (:data-dir cfg) "/state.db")
                       :data-dir (:data-dir cfg)
                       :backup-id "backup-1"}))))
      (let [state (:state (journal cfg))]
        (is (= :failed-before-commit (:status state)))
        (is (false? (:needs-recovery? state)))
        (is (false? (:commit-unproven? state)))
        (is (true? (:store-unchanged? state)))
        (is (nil? (journal/startup-blocking-state cfg))
            "a store observed before and after unchanged is not blocked"))
      (finally
        (delete-tree! (:data-dir cfg))))))

(deftest a-version-bump-without-adapter-evidence-needs-recovery
  (let [cfg (config)
        versions (atom [7 8])]
    (try
      (is (thrown? clojure.lang.ExceptionInfo
                   (with-redefs [storage/open-store (fn [_] ::store)
                                 storage/store-info
                                 (fn [_] {:version (let [v (first @versions)]
                                                     (swap! versions rest)
                                                     v)})
                                 storage-adapter/domain (fn [_ _] ::domain)
                                 backup/restore!
                                 (fn [& _]
                                   (throw (ex-info "cancelled after commit"
                                                   {:error :BACKUP_RESTORE_FAILED})))
                                 storage/close-store! (fn [_] true)]
                     (offline/restore!
                      {:db-path (str (:data-dir cfg) "/state.db")
                       :data-dir (:data-dir cfg)
                       :backup-id "backup-1"}))))
      (let [state (:state (journal cfg))]
        (is (= :failed-needs-recovery (:status state)))
        (is (true? (:needs-recovery? state)))
        (is (true? (:storage-touched? state)))
        (is (= :failed-needs-recovery
               (:status (journal/startup-blocking-state cfg)))
            "a proven partial restore keeps the next start blocked"))
      (finally
        (delete-tree! (:data-dir cfg))))))

(deftest an-interrupted-throw-is-never-nothing-happened
  (let [cfg (config)]
    (try
      (is (thrown? clojure.lang.ExceptionInfo
                   (with-redefs [storage/open-store (fn [_] ::store)
                                 ;; A version that cannot be read proves
                                 ;; neither a commit nor an untouched store.
                                 storage/store-info (fn [_] {:version nil})
                                 storage-adapter/domain (fn [_ _] ::domain)
                                 backup/restore!
                                 (fn [& _] (throw (InterruptedException. "cancel")))
                                 storage/close-store! (fn [_] true)]
                     (offline/restore!
                      {:db-path (str (:data-dir cfg) "/state.db")
                       :data-dir (:data-dir cfg)
                       :backup-id "backup-1"}))))
      (let [state (:state (journal cfg))]
        (is (= :failed-commit-unproven (:status state)))
        (is (true? (:commit-unproven? state)))
        (is (false? (:needs-recovery? state)))
        (is (= :failed-commit-unproven
               (:status (journal/startup-blocking-state cfg)))
            "doubt keeps the next start blocked"))
      (finally
        (delete-tree! (:data-dir cfg))))))

(deftest unproven-commits-need-their-own-acknowledgment
  (let [cfg (config)]
    (try
      (let [state (journal/offline-restore-begin!
                   cfg {:kind :backup :backup-id "backup-1"})
            marked (journal/offline-restore-mark-commit-unproven! cfg state)
            failed (journal/offline-restore-finish!
                    cfg marked
                    {:status :failed
                     :error {:code :BACKUP_RESTORE_FAILED}}
                    {:code :BACKUP_RESTORE_FAILED})]
        (is (= :failed-commit-unproven (:status failed)))
        (is (true? (:commit-unproven? failed)))
        (testing "the wrong acknowledgment code is refused"
          (is (= :BACKUP_MAINTENANCE_ACK_INVALID
                 (try
                   (journal/acknowledge!
                    cfg {:maintenance-id (:maintenance-id failed)
                         :expected-state-digest (journal/state-digest failed)
                         :expected-restore-digest
                         (journal/restore-plan-digest failed)
                         :expected-storage-drift []
                         :code :inspect-and-accept-partial
                         :operator "operator"})
                   nil
                   (catch clojure.lang.ExceptionInfo e
                     (:error (ex-data e)))))))
        (testing "the unknown-commit acknowledgment unblocks the start"
          (journal/acknowledge!
           cfg {:maintenance-id (:maintenance-id failed)
                :expected-state-digest (journal/state-digest failed)
                :expected-restore-digest
                (journal/restore-plan-digest failed)
                :expected-storage-drift []
                :code :inspect-accept-unknown-commit
                :operator "operator"})
          (let [acked (:state (journal cfg))]
            (is (true? (:acknowledged? acked)))
            (is (= "operator" (get-in acked [:acknowledgment :operator])))
            (is (= :failed-commit-unproven (:status acked))
                "the acknowledgment never rewrites the failure into success")))
        (is (nil? (journal/startup-blocking-state cfg))))
      (finally
        (delete-tree! (:data-dir cfg))))))

(deftest a-finish-never-overwrites-an-acknowledgment
  (let [cfg (config)]
    (try
      (let [state (journal/offline-restore-begin!
                   cfg {:kind :backup :backup-id "backup-1"})
            marked (journal/offline-restore-mark-commit-unproven! cfg state)
            failed (journal/offline-restore-finish!
                    cfg marked
                    {:status :failed :error {:code :BACKUP_RESTORE_FAILED}}
                    {:code :BACKUP_RESTORE_FAILED})
            _ (journal/acknowledge!
               cfg {:maintenance-id (:maintenance-id failed)
                    :expected-state-digest (journal/state-digest failed)
                    :expected-restore-digest (journal/restore-plan-digest failed)
                    :expected-storage-drift []
                    :code :inspect-accept-unknown-commit
                    :operator "operator"})
            refused (atom nil)]
        (is (= :BACKUP_MAINTENANCE_STATE_CHANGED
               (try
                 (journal/offline-restore-finish!
                  cfg state
                  {:status :failed :error {:code :BACKUP_RESTORE_FAILED}}
                  {:code :BACKUP_RESTORE_FAILED}
                  {:expect
                   (fn [current]
                     (when (true? (:acknowledged? current))
                       (reset! refused current)
                       (journal/fail!
                        :BACKUP_MAINTENANCE_STATE_CHANGED
                        "acknowledged while failing"
                        {})))})
                 nil
                 (catch clojure.lang.ExceptionInfo e
                   (:error (ex-data e))))))
        (is (true? (:acknowledged? @refused)))
        (is (true? (get-in (:state (journal cfg)) [:acknowledged?]))
            "the operator decision survives a late finish")
        (is (nil? (journal/startup-blocking-state cfg))))
      (finally
        (delete-tree! (:data-dir cfg))))))

(deftest concurrent-offline-restores-cannot-both-start
  (let [cfg (config)]
    (try
      (supervisor/prepare! {:config cfg :maintenance nil})
      (let [start (java.util.concurrent.CountDownLatch. 1)
            results (atom [])
            run (fn []
                  (fn []
                    (.await start)
                    (try
                      (swap! results conj
                             [:ok (journal/offline-restore-begin!
                                   cfg {:kind :backup})])
                      (catch clojure.lang.ExceptionInfo e
                        (swap! results conj [:refused (ex-data e)])))))
            first-thread (Thread. ^Runnable (run))
            second-thread (Thread. ^Runnable (run))]
        (.start first-thread)
        (.start second-thread)
        (.countDown start)
        (.join first-thread 10000)
        (.join second-thread 10000)
        (is (= 2 (count @results)))
        (is (= 1 (count (filter #(= :ok (first %)) @results)))
            "exactly one begin succeeds; the check is inside the lock")
        (is (= :BACKUP_MAINTENANCE_ACTIVE
               (get-in (second (first (filter #(= :refused (first %))
                                              @results)))
                       [:error])))
        (is (some? (java.nio.file.Files/exists
                    (journal/lock-path cfg)
                    (make-array java.nio.file.LinkOption 0)))
            "the cross-process lock is a persistent journal file"))
      (finally
        (delete-tree! (:data-dir cfg))))))

(deftest supervisor-reuse-needs-the-exact-supervisor-request
  (let [cfg (config)
        id "maintenance-12"]
    (try
      (supervisor/prepare! {:config cfg :maintenance nil})
      (write-state! cfg (assoc (base-state id :restoring)
                               :request-digest "digest-1"))
      (testing "the same ID and digest reuse the supervisor record"
        (is (= id (:maintenance-id
                   (journal/offline-restore-begin!
                    cfg {:kind :backup
                         :supervisor-restore? true
                         :offline-restore-id id
                         :expected-restore-digest "digest-1"})))))
      (testing "a guessed flag without the request digest is refused"
        (is (= :BACKUP_MAINTENANCE_ACTIVE
               (try
                 (journal/offline-restore-begin!
                  cfg {:kind :backup
                       :supervisor-restore? true
                       :offline-restore-id id
                       :expected-restore-digest "digest-2"})
                 nil
                 (catch clojure.lang.ExceptionInfo e
                   (:error (ex-data e)))))))
      (testing "an offline record is never converted into a supervisor record"
        ;; The earlier supervisor record is terminal, so a plain offline begin
        ;; may replace it; the reuse must still refuse to adopt that record.
        (write-state! cfg (assoc (base-state id :complete)
                                 :request-digest "digest-1"))
        (let [offline (journal/offline-restore-begin! cfg {:kind :backup})]
          (is (= :BACKUP_MAINTENANCE_ACTIVE
                 (try
                   (journal/offline-restore-begin!
                    cfg {:kind :backup
                         :supervisor-restore? true
                         :offline-restore-id (:maintenance-id offline)
                         :expected-restore-digest "offline"})
                   nil
                   (catch clojure.lang.ExceptionInfo e
                     (:error (ex-data e))))))))
      (finally
        (delete-tree! (:data-dir cfg))))))

(deftest restart-failure-names-applied-data-and-health
  (let [state {:maintenance-id "m1"
               :status :restart-failed
               :data-applied? true
               :health {:ok false :store {:open? true}}
               :restart {:ok? false
                         :error {:code :APP_HEALTH_FAILED
                                 :message "application started but its health check failed"}}
               :restore {:value {:status :complete}}
               :recovery-entry {:action :operator
                                :options [:retry-restart :manual-restart]}}
        public ((resolve 'hc.server.app.restore-supervisor/public-state) state)]
    (is (true? (:data-applied? public))
        "a restart failure must not claim the applied data was not applied")
    (is (= :failed (get-in public [:restart :status])))
    (is (= false (get-in public [:health :ok])))
    (is (= [:retry-restart :manual-restart]
           (get-in public [:recovery-entry :options]))
        "the operator gets a retry and a manual-restart entry")))
