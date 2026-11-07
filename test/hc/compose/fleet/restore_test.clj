(ns hc.compose.fleet.restore-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [hc.compose.fleet.restore :as restore]
            [hc.compose.fleet.support :as support]
            [hc.core.swarm-stm :as swarm]))

(defn- slice
  ([id] (slice id []))
  ([id deps]
   {:slice-id id
    :goal (str "goal " id)
    :inputs []
    :depends-on deps
    :done-when "done"}))

(defn- base-swarm
  [id status created-seq]
  (cond-> {:swarm-id id
           :slice-id (str "slice-" id)
           :workspace (str "/worktrees/" id)
           :branch (str "hc/mission/" id)
           :status status
           :worktree-ready? true
           :completed? false
           :integrating? false
           :report nil
           :ff-commit nil
           :rebase-rounds 0
           :board-version 0
           :completion-seq nil
           :root-ready 0
           :root-waiting 0
           :root-done 0
           :master-version 0
           :created-seq created-seq
           :last-event nil
           :finished-at nil
           :gc-after nil
           :worktree-removed? false}
    (= :joined status)
    (assoc :completed? true
           :report "full report"
           :ff-commit "ff-sha"
           :board-version 1
           :completion-seq 1
           :master-version 1
           :finished-at 10
           :gc-after 20)

    (= :abandoned status)
    (assoc :abandon-reason {:code :STALE}
           :finished-at 10
           :gc-after 20)

    (= :failed status)
    (assoc :failure {:code :BOOT_FAILED}
           :finished-at 10
           :gc-after 20)))

(defn- completed-swarm
  [id created-seq completion-seq]
  (assoc (base-swarm id :running created-seq)
         :completed? true
         :report (str "report-" id)
         :board-version 1
         :completion-seq completion-seq))

(defn- worktrees
  [state]
  (->> (:swarms state)
       vals
       (sort-by :created-seq)
       (mapv (fn [swarm]
               {:swarm-id (:swarm-id swarm)
                :slice-id (:slice-id swarm)
                :workspace (:workspace swarm)
                :branch (:branch swarm)
                :status (:status swarm)
                :worktree-removed? (boolean (:worktree-removed? swarm))}))))

(defn- snapshot
  []
  (let [swarms {"swarm-1" (base-swarm "swarm-1" :joined 1)
                "swarm-2" (base-swarm "swarm-2" :running 2)}
        state {:mission-id "mission"
               :worktree-root "/worktrees"
               :master-ref "master"
               :retention-ms 1000
               :version 3
               :event-seq 0
               :events []
               :master-version 1
               :completion-seq 1
               :next-swarm-seq 2
               :slices {"slice-swarm-1" (slice "slice-swarm-1")
                        "slice-swarm-2" (slice "slice-swarm-2")}
               :slice->swarm {"slice-swarm-1" "swarm-1"
                              "slice-swarm-2" "swarm-2"}
               :swarms swarms
               :completion-queue []}]
    {:fleet/snapshot-version 2
     :fleet/version 3
     :mission-id "mission"
     :master-ref "master"
     :state state
     :runtimes {}
     :worktrees (worktrees state)}))

(defn- queued-snapshot
  []
  (let [swarms {"swarm-1" (completed-swarm "swarm-1" 1 1)
                "swarm-2" (completed-swarm "swarm-2" 2 2)}
        state {:mission-id "mission"
               :worktree-root "/worktrees"
               :master-ref "master"
               :retention-ms 1000
               :version 5
               :event-seq 0
               :events []
               :master-version 0
               :completion-seq 2
               :next-swarm-seq 2
               :slices {"slice-swarm-1" (slice "slice-swarm-1")
                        "slice-swarm-2" (slice "slice-swarm-2")}
               :slice->swarm {"slice-swarm-1" "swarm-1"
                              "slice-swarm-2" "swarm-2"}
               :swarms swarms
               :completion-queue ["swarm-1" "swarm-2"]}]
    {:fleet/snapshot-version 2
     :fleet/version 5
     :mission-id "mission"
     :master-ref "master"
     :state state
     :runtimes {}
     :worktrees (worktrees state)}))

(defn- inflight-snapshot
  []
  (let [swarms {"swarm-1" (base-swarm "swarm-1" :running 1)
                "swarm-2" (base-swarm "swarm-2" :queued 2)
                "swarm-3" (base-swarm "swarm-3" :queued 3)}
        state {:mission-id "mission"
               :worktree-root "/worktrees"
               :master-ref "master"
               :retention-ms 1000
               :version 2
               :event-seq 0
               :events []
               :master-version 0
               :completion-seq 0
               :next-swarm-seq 3
               :slices {"slice-swarm-1" (slice "slice-swarm-1")
                        "slice-swarm-2" (slice "slice-swarm-2"
                                               ["slice-swarm-1"])
                        "slice-swarm-3" (slice "slice-swarm-3")}
               :slice->swarm {"slice-swarm-1" "swarm-1"
                              "slice-swarm-2" "swarm-2"
                              "slice-swarm-3" "swarm-3"}
               :swarms swarms
               :completion-queue []}]
    {:fleet/snapshot-version 2
     :fleet/version 2
     :mission-id "mission"
     :master-ref "master"
     :state state
     :runtimes {}
     :worktrees (worktrees state)}))

(deftest validates-a-real-fleet-snapshot
  (let [result (restore/validate (snapshot))]
    (is (:ok? result))
    (is (= 0 (:runtime-count result)))
    (is (false? (:legacy? result)))
    (is (= [] (:reattach result)))
    (is (= [] (:joinable result)))
    (is (= ["swarm-1"] (:terminal result)))
    (is (= ["swarm-2"] (:uncertain result)))
    (is (= [] (:resumable result)))
    (is (= [] (:unrestorable result)))))

(deftest validates-a-legacy-v1-snapshot
  (let [legacy (-> (snapshot)
                   (assoc :fleet/snapshot-version 1)
                   (dissoc :runtimes))
        result (restore/validate legacy)]
    (is (:ok? result))
    (is (true? (:legacy? result)))
    (is (= 0 (:runtime-count result)))
    (is (= {} (:runtimes result)))))

(deftest rejects-an-unknown-runtime-swarm
  (let [bad (assoc-in (snapshot) [:runtimes "swarm-9"]
                      {:board (swarm/empty-board)})
        result (restore/validate bad)]
    (is (false? (:ok? result)))
    (is (= :INVALID_SWARM_STATE (get-in result [:error :code])))
    (is (some #(= :runtime-for-unknown-swarm (:reason %))
              (get-in result [:error :details :violations])))))

(deftest rejects-an-invalid-runtime-board
  (let [board (assoc (swarm/empty-board)
                     :token-slot {:holder-id :agent-9
                                  :term-id 1
                                  :state :held})
        bad (assoc-in (snapshot) [:runtimes "swarm-2"] {:board board})
        result (restore/validate bad)]
    (is (false? (:ok? result)))
    (is (some #(= :invalid-board-invariant (:reason %))
              (get-in result [:error :details :violations])))))

(defn- board-with-running-and-claimed-actions
  []
  (let [kernel (swarm/create {})
        actor-a (swarm/execute! kernel {:op :register-actor :slot-id :slot-a})
        actor-b (swarm/execute! kernel {:op :register-actor :slot-id :slot-b})
        task-a (swarm/execute! kernel
                               {:op :create-task
                                :actor-id (:agent-id actor-a)
                                :ledger-key "slice-a"
                                :goal "goal a"
                                :remaining-work "remaining a"
                                :done-when "done a"})
        task-b (swarm/execute! kernel
                               {:op :create-task
                                :actor-id (:agent-id actor-b)
                                :ledger-key "slice-b"
                                :goal "goal b"
                                :remaining-work "remaining b"
                                :done-when "done b"})
        claim-a (swarm/execute! kernel
                                {:op :claim
                                 :actor-id (:agent-id actor-a)
                                 :task-id (:task-id task-a)
                                 :atomicity-reason "a"})
        claim-b (swarm/execute! kernel
                                {:op :claim
                                 :actor-id (:agent-id actor-b)
                                 :task-id (:task-id task-b)
                                 :atomicity-reason "b"})
        action-a (swarm/execute! kernel
                                 {:op :begin-action
                                  :actor-id (:agent-id actor-a)
                                  :task-id (:task-id task-a)
                                  :claim-id (:claim-id claim-a)
                                  :kind :edit})
        action-b (swarm/execute! kernel
                                 {:op :begin-action
                                  :actor-id (:agent-id actor-b)
                                  :task-id (:task-id task-b)
                                  :claim-id (:claim-id claim-b)
                                  :kind :edit})]
    (swarm/execute! kernel
                    {:op :advance-action
                     :actor-id (:agent-id actor-a)
                     :action-id (:action-id action-a)
                     :phase :running})
    {:board (swarm/snapshot kernel)
     :agent-a (:agent-id actor-a)
     :agent-b (:agent-id actor-b)
     :action-a (:action-id action-a)
     :action-b (:action-id action-b)}))

(deftest recovers-a-board-without-replaying-side-effects
  (let [{:keys [board agent-a agent-b action-a action-b]}
        (board-with-running-and-claimed-actions)
        result (restore/recover-board board 1000)
        recovered (:board result)]
    (is (:ok? result))
    (is (:changed? result))
    (is (= [action-a] (:uncertain-actions result)))
    (is (= [action-b] (:abandoned-actions result)))
    (is (= :uncertain (get-in recovered [:actions action-a :phase])))
    (is (= :outcome-unknown
           (get-in recovered [:actions action-a :recovery :state])))
    (is (= :running
           (get-in recovered [:actions action-a :recovery :previous-phase])))
    (is (= action-a (get-in recovered [:actors agent-a :active-action-id])))
    (is (nil? (get-in recovered [:actions action-b]))
        "a claimed action that never touched a side effect is removed")
    (is (nil? (get-in recovered [:actors agent-b :active-action-id])))
    (is (> (:board-version recovered) (:board-version board)))
    (is (some #(= :action-uncertain (:type %))
              (:events recovered)))
    (is (some #(= :action-abandoned (:type %))
              (:events recovered)))
    (is (empty? (swarm/check-board-invariants recovered)))))

(deftest recovers-runtime-gate-evidence
  (let [{:keys [board action-a]} (board-with-running-and-claimed-actions)
        snapshot (assoc-in (snapshot) [:runtimes "swarm-2"]
                           {:board board
                            :write-gate {:swarm-id "swarm-2"
                                         :held? true
                                         :owner :agent-a
                                         :queue 1}})
        result (restore/recover-runtimes snapshot {:now 1000})
        runtime (get-in result [:runtimes "swarm-2"])
        gate (:write-gate-recovery runtime)]
    (is (:ok? result))
    (is (= :uncertain
           (get-in runtime [:board :actions action-a :phase])))
    (is (true? (:outcome-unknown? gate)))
    (is (true? (:queue-abandoned? gate)))
    (is (= :process-restart (:reason gate)))
    (is (true? (get-in gate [:previous :held?])))))

(deftest running-in-flight-swarm-with-runtime-resumes
  (let [snapshot (assoc-in (inflight-snapshot) [:runtimes "swarm-1"]
                           {:board (swarm/empty-board)})
        result (restore/recover-state
                (:state snapshot)
                {:now 1000
                 :worktree-exists? (constantly true)
                 :runtime-ids #{"swarm-1"}})
        state (:state result)]
    (is (:ok? result))
    (is (= ["swarm-1"] (:resumed result)))
    (is (= [] (:failed-uncertain result)))
    (is (= ["swarm-1"] (:recovered result)))
    (is (= :running (get-in state [:swarms "swarm-1" :status])))
    (is (= :outcome-unknown
           (get-in state [:swarms "swarm-1" :recovery :state])))
    (is (true? (get-in state [:swarms "swarm-1" :recovery :resumed?])))
    (is (= :queued (get-in state [:swarms "swarm-2" :status])))
    (is (= :queued (get-in state [:swarms "swarm-3" :status])))))

(deftest validates-an-empty-fleet-snapshot
  (let [state {:mission-id "mission"
               :worktree-root "/worktrees"
               :master-ref "master"
               :retention-ms 1000
               :slices {}
               :swarms {}
               :slice->swarm {}
               :completion-queue []
               :completion-seq 0
               :next-swarm-seq 0
               :version 0
               :event-seq 0
               :events []
               :master-version 0}
        snap (restore/snapshot {:mission-id "mission"
                                :master-ref "master"
                                :state state})
        result (restore/validate snap)]
    (is (:ok? result))
    (is (= [] (:reattach result)))
    (is (= [] (:joinable result)))
    (is (= [] (:terminal result)))))

(deftest rejects-joined-without-real-ff-evidence
  (let [bad (update-in (snapshot) [:state :swarms "swarm-1"] dissoc :ff-commit)
        result (restore/validate bad)]
    (is (false? (:ok? result)))
    (is (= :INVALID_SWARM_STATE (get-in result [:error :code])))
    (is (some #(= :joined-without-ff-commit (:reason %))
              (get-in result [:error :details :violations])))))

(deftest rejects-duplicate-or-unknown-queue-entries
  (let [duplicate (assoc-in (snapshot) [:state :completion-queue]
                            ["swarm-2" "swarm-2"])
        unknown (assoc-in (snapshot) [:state :completion-queue] ["swarm-9"])]
    (is (= :DUPLICATE_QUEUE_ENTRY
           (get-in (restore/validate duplicate) [:error :code])))
    (is (= :UNKNOWN_QUEUE_SWARM
           (get-in (restore/validate unknown) [:error :code])))))

(deftest rejects-a-non-fifo-completion-queue
  (let [bad (assoc-in (queued-snapshot) [:state :completion-queue]
                      ["swarm-2" "swarm-1"])
        result (restore/validate bad)]
    (is (false? (:ok? result)))
    (is (= :INVALID_SWARM_STATE (get-in result [:error :code])))
    (is (some #(= :queue-order-not-fifo (:reason %))
              (get-in result [:error :details :violations])))))

(deftest rejects-a-master-version-that-disagrees-with-joined
  (let [bad (assoc-in (snapshot) [:state :master-version] 2)
        result (restore/validate bad)]
    (is (false? (:ok? result)))
    (is (= :MASTER_VERSION_MISMATCH (get-in result [:error :code])))))

(deftest recovers-a-pending-merged-join-without-losing-its-evidence
  (let [swarm (assoc (completed-swarm "swarm-1" 1 1)
                     :integrating? true
                     :join-recovery {:git-merged? true
                                     :ff-commit "ff-real"
                                     :rebase-rounds 2
                                     :persist-error {:code :DISK_FULL}
                                     :recovered-at 1000})
        state {:mission-id "mission"
               :worktree-root "/worktrees"
               :master-ref "master"
               :retention-ms 1000
               :version 5
               :event-seq 0
               :events []
               :master-version 0
               :completion-seq 1
               :next-swarm-seq 1
               :slices {"slice-swarm-1" (slice "slice-swarm-1")}
               :slice->swarm {"slice-swarm-1" "swarm-1"}
               :swarms {"swarm-1" swarm}
               :completion-queue ["swarm-1"]}
        snap {:fleet/snapshot-version 2
              :fleet/version 5
              :mission-id "mission"
              :master-ref "master"
              :state state
              :runtimes {}
              :worktrees (worktrees state)}
        valid (restore/validate snap)]
    (is (:ok? valid))
    (is (= ["swarm-1"] (:joinable valid)))
    (let [recovered (restore/recover-state state
                                           {:now 1001
                                            :worktree-exists? (constantly true)})]
      (is (:ok? recovered))
      (is (false? (get-in recovered [:state :swarms "swarm-1" :integrating?])))
      (is (true? (get-in recovered [:state :swarms "swarm-1"
                                    :join-recovery :git-merged?])))
      (is (= "ff-real" (get-in recovered [:state :swarms "swarm-1"
                                          :join-recovery :ff-commit])))
      (is (= ["swarm-1"] (:abort-rebase recovered))
          "the old claim still triggers a stale-rebase abort attempt"))
    (let [missing (restore/recover-state state
                                         {:now 1002
                                          :worktree-exists? (constantly false)})]
      (is (:ok? missing))
      (is (= :running (get-in missing [:state :swarms "swarm-1" :status]))
          "a verified merge stays joinable without its worktree")
      (is (= [] (:missing missing)))
      (is (true? (get-in missing [:state :swarms "swarm-1"
                                  :join-recovery :git-merged?]))))
    (let [bad (assoc-in snap [:state :swarms "swarm-1"
                              :join-recovery :ff-commit] nil)
          result (restore/validate bad)]
      (is (false? (:ok? result)))
      (is (some #(= :invalid-join-recovery (:reason %))
                (get-in result [:error :details :violations]))))))

(deftest restore-control-resets-state-and-reports-in-flight-recovery
  (let [control-state (atom {:version 0 :swarms {} :slices {}
                             :completion-queue [] :master-version 0})
        runtimes (atom {"swarm-9" {:swarm-id "swarm-9"}})
        service {:control {:state control-state}
                 :runtimes runtimes
                 :mission-id "mission"
                 :worktree-root "/worktrees"
                 :master-ref "master"}
        result (restore/restore-control!
                service (snapshot)
                {:now 1000 :worktree-exists? (constantly true)})]
    (is (:ok? result))
    (is (= ["swarm-2"] (:uncertain result)))
    (is (= 1 (get-in @control-state [:master-version])))
    (is (= :failed (get-in @control-state [:swarms "swarm-2" :status])))
    (is (= :SWARM_IN_FLIGHT_OUTCOME_UNKNOWN
           (get-in @control-state [:swarms "swarm-2" :failure :code])))
    (is (true? (get-in @control-state
                       [:swarms "swarm-2" :failure :outcome-unknown?])))
    (is (= :outcome-unknown
           (get-in @control-state [:swarms "swarm-2" :recovery :state])))
    (is (= {} @runtimes)
        "live runtime atoms are cleared, not falsely serialized")))

(deftest restore-keeps-the-fifo-queue-and-master-version
  (let [control-state (atom {:version 0 :swarms {} :slices {}
                             :completion-queue [] :master-version 0})
        service {:control {:state control-state}
                 :runtimes (atom {})
                 :mission-id "mission"
                 :worktree-root "/worktrees"
                 :master-ref "master"}
        result (restore/restore-control!
                service (queued-snapshot)
                {:now 1000 :worktree-exists? (constantly true)})]
    (is (:ok? result))
    (is (= ["swarm-1" "swarm-2"] (get-in @control-state [:completion-queue])))
    (is (= 0 (get-in @control-state [:master-version])))
    (is (= ["swarm-1" "swarm-2"] (:joinable result)))
    (is (= [] (:reattach result)))
    (is (= :running (get-in @control-state [:swarms "swarm-1" :status])))
    (is (= "report-swarm-1"
           (get-in @control-state [:swarms "swarm-1" :report])))))

(deftest running-in-flight-swarm-fails-and-propagates-dependencies
  (let [result (restore/recover-state (:state (inflight-snapshot))
                                      {:now 1000
                                       :worktree-exists? (constantly true)})
        state (:state result)]
    (is (:ok? result))
    (is (= ["swarm-1"] (:recovered result)))
    (is (= [] (:missing result)))
    (is (= :failed (get-in state [:swarms "swarm-1" :status])))
    (is (= :SWARM_IN_FLIGHT_OUTCOME_UNKNOWN
           (get-in state [:swarms "swarm-1" :failure :code])))
    (is (true? (get-in state
                       [:swarms "swarm-1" :failure :outcome-unknown?])))
    (is (= :failed (get-in state [:swarms "swarm-2" :status])))
    (is (= :DEPENDENCY_TERMINAL
           (get-in state [:swarms "swarm-2" :failure :code])))
    (is (= :queued (get-in state [:swarms "swarm-3" :status])))
    (is (= [] (:completion-queue state)))
    (is (= 0 (:master-version state)))
    (is (:changed? result))))

(deftest missing-active-worktree-fails-that-swarm-and-its-dependents
  (let [state (:state (inflight-snapshot))
        result (restore/recover-state
                state
                {:now 1000
                 :worktree-exists? (fn [swarm]
                                     (not= "swarm-3" (:swarm-id swarm)))})
        recovered (:state result)]
    (is (:ok? result))
    (is (= ["swarm-3"] (:missing result)))
    (is (= :SWARM_WORKTREE_MISSING
           (get-in recovered [:swarms "swarm-3" :failure :code])))
    (is (= :unrestorable
           (get-in recovered [:swarms "swarm-3" :recovery :state])))
    (is (= :failed (get-in recovered [:swarms "swarm-2" :status])))
    (is (= :DEPENDENCY_TERMINAL
           (get-in recovered [:swarms "swarm-2" :failure :code])))
    (is (= :failed (get-in recovered [:swarms "swarm-1" :status])))))

(deftest worktree-removed-is-explicitly-unrestorable
  (let [snap (snapshot)
        snap (-> snap
                 (assoc-in [:state :swarms "swarm-1" :worktree-removed?] true)
                 (update :worktrees
                         (fn [worktrees]
                           (mapv #(if (= "swarm-1" (:swarm-id %))
                                    (assoc % :worktree-removed? true)
                                    %)
                                 worktrees))))
        result (restore/validate snap)]
    (is (:ok? result))
    (is (= ["swarm-1"] (:unrestorable result)))
    (is (= [] (:reattach result)))))

(deftest restore-refuses-a-different-mission-or-worktree-root
  (let [service {:mission-id "other"
                 :worktree-root "/worktrees"
                 :master-ref "master"}
        result (restore/restore-plan service (snapshot))]
    (is (false? (:ok? result)))
    (is (= :FLEET_STATE_MISMATCH (get-in result [:error :code]))))
  (let [service {:mission-id "mission"
                 :worktree-root "/elsewhere"
                 :master-ref "master"}
        result (restore/restore-plan service (snapshot))]
    (is (false? (:ok? result)))
    (is (= :FLEET_STATE_MISMATCH (get-in result [:error :code])))))

(deftest restore-persistence-failure-keeps-the-live-control
  (let [control-state (atom {:version 0 :swarms {} :slices {}
                             :completion-queue [] :master-version 0})
        service {:control {:state control-state}
                 :runtimes (atom {})
                 :mission-id "mission"
                 :worktree-root "/worktrees"
                 :master-ref "master"}
        result (restore/restore-control!
                service (snapshot)
                {:now 1000
                 :worktree-exists? (constantly true)
                 :persist! (fn [_]
                             {:ok? false
                              :error {:code :DISK_FULL}} )})]
    (is (false? (:ok? result)))
    (is (= :FLEET_RECOVERY_PERSIST_FAILED (get-in result [:error :code])))
    (is (some? (:recovered-state result)))
    (is (= 0 (get-in @control-state [:version]))
        "the live control is not replaced when recovery cannot be persisted")))

(defn- file-store-snapshot
  []
  (let [report "report with \"quotes\", \\backslash, 中文, and\nnewlines"
        snap (snapshot)]
    (-> snap
        (assoc-in [:state :swarms "swarm-1" :report] report)
        (assoc-in [:runtimes "swarm-2"] {:board (swarm/empty-board)})
        (update :worktrees
                (fn [worktrees]
                  (mapv #(if (= "swarm-1" (:swarm-id %))
                           (assoc % :status :joined)
                           %)
                        worktrees))))))

(deftest file-store-round-trips-the-complete-current-state
  (support/with-temp-dir [root "hc-fleet-restore-"]
    (let [path (str root "/fleet/state.edn")
          store (restore/file-store path)
          snap (file-store-snapshot)
          saved (restore/save! store snap)
          loaded (restore/load! store)]
      (is (:ok? saved))
      (is (.isFile (io/file path)))
      (is (= :ok (:status loaded)))
      (is (= snap (:snapshot loaded)))
      (is (:ok? (restore/validate (:snapshot loaded))))
      (is (= "report with \"quotes\", \\backslash, 中文, and\nnewlines"
             (get-in loaded [:snapshot :state :swarms "swarm-1" :report]))))))

(deftest file-store-reports-corruption-and-never-erases-it
  (support/with-temp-dir [root "hc-fleet-restore-"]
    (let [path (str root "/fleet/state.edn")]
      (io/make-parents path)
      (spit path "{not edn")
      (let [store (restore/file-store path)
            loaded (restore/load! store)]
        (is (= :corrupt (:status loaded)))
        (is (= :FLEET_STATE_CORRUPT (get-in loaded [:error :code])))
        (is (= "{not edn" (slurp path))
            "a corrupt file is evidence, not an empty fleet to overwrite")))))

(deftest file-store-rejects-an-invalid-snapshot-before-writing
  (support/with-temp-dir [root "hc-fleet-restore-"]
    (let [path (str root "/fleet/state.edn")
          store (restore/file-store path)
          bad (assoc-in (snapshot) [:fleet/snapshot-version] 99)
          saved (restore/save! store bad)]
      (is (false? (:ok? saved)))
      (is (= :FLEET_STATE_INVALID (get-in saved [:error :code])))
      (is (= :INVALID_SNAPSHOT_VERSION
             (get-in saved [:error :cause :code])))
      (is (not (.exists (io/file path)))))))
