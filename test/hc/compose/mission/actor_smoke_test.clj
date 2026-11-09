(ns hc.compose.mission.actor-smoke-test
  "Actual slot lifecycle smoke with a stub model gateway.

  The test drives the real mission state through a stub model: every
  `:model/start` effect is delivered to the stub, its events return through
  `accept-model-event`, and the resulting actor/mission state is inspected.
  It proves wake-all, per-agent empty context, @task model ownership, holder
  death/rebirth, and Plan dedicated worker reuse without a live provider."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hc.compose.mission :as mission]
            [hc.compose.mission.actors :as actors]
            [hc.compose.mission.support :as support]
            [hc.core.ufss :as ufss]))

(defn- request-text
  [request]
  (str/join "\n" (map :text (:messages request))))

(defn- cmd!
  [state command]
  (let [result (mission/apply-command state command)]
    (is (:ok? result) (pr-str (:error result)))
    result))

(defn- deliver-stub!
  "Deliver one chunk and one completion for every model-start effect."
  [mission-state actor-state effects]
  (reduce
   (fn [[mission-state actor-state requests] effect]
     (if (= :model/start (:type effect))
       (let [request (:request effect)
             events [{:event :chunk :text "thinking"}
                     {:event :completed
                      :text (str "done " (:agent-id request))}]
             [mission-state actor-state]
             (reduce (fn [[mission-state actor-state] event]
                       (let [result (actors/accept-model-event
                                     mission-state actor-state
                                     (:attempt-id effect) event)]
                         [(:mission result) (:actors result)]))
                     [mission-state actor-state]
                     events)]
         [mission-state actor-state (conj requests request)])
       [mission-state actor-state requests]))
   [mission-state actor-state []]
   effects))

(deftest swarm-slots-use-task-model-and-per-agent-context
  (let [h (support/host)
        _ (support/drive-plan-to-pending! h)
        _ (support/start-execution! h)
        execution-id (support/sole-execution-id h)
        _ (support/start-swarm! h execution-id
                                [{:key "a" :value "Do a"}
                                 {:key "b" :value "Do b"}])
        mission-state (mission/snapshot h)
        actor-state (actors/new-state {:models (support/models)
                                       :slot-ids [:slot-a :slot-b]
                                       :workspaces
                                       {execution-id "worktrees/slice-a"}})
        synced (actors/sync-tenure mission-state actor-state execution-id)]
    (is (:ok? synced) (pr-str (:error synced)))
    (let [rebirths (filter #(= :actor/rebirth (:type %)) (:effects synced))
          agents (mapv :agent-id rebirths)]
      (is (= 2 (count rebirths)))
      (is (= 2 (count (distinct agents))))
      (is (every? keyword? agents))
      (testing "wake-all starts every live slot on the task model"
        (let [woken (actors/wake-all (:mission synced) (:actors synced)
                                     execution-id)
              starts (filter #(= :model/start (:type %)) (:effects woken))]
          (is (:ok? woken))
          (is (= 2 (count starts)))
          (doseq [effect starts]
            (is (= :task (get-in effect [:request :role])))
            (is (= :worker (get-in effect [:request :mission-role])))
            (is (= :task (get-in effect [:request :model-role])))
            (is (= "task-model" (get-in effect [:request :model-id])))
            (is (= :clj-worker (get-in effect [:request :program-role])))
            (is (= "worktrees/slice-a"
                   (get-in effect [:request :workspace])))
            (is (map? (get-in effect [:request :board-snapshot])))
            (is (not (contains? (get-in effect [:request :board-snapshot])
                                :ledger))))
          (let [[mission-after actor-after requests]
                (deliver-stub! (:mission woken) (:actors woken)
                               (:effects woken))]
            (is (= 2 (count requests)))
            (is (every? #(= :task (:model-role %)) requests))
            (is (every? #(not (str/includes? (request-text %)
                                             "Reviewer ledger"))
                        requests))
            (doseq [slot-id [:slot-a :slot-b]]
              (let [agent-id (get-in actor-after
                                     [:tenures execution-id :slots slot-id
                                      :agent-id])
                    other (->> agents (remove #{agent-id}) first)
                    text (str/join "\n" (map :text (actors/context
                                                    actor-after
                                                    execution-id slot-id)))]
                (is (str/includes? text (str agent-id)))
                (is (not (str/includes? text (str other))))))
            (testing "holder death and rebirth create a fresh empty identity"
              (let [holder-id (get-in actor-after
                                      [:tenures execution-id :slots :slot-a
                                       :agent-id])
                    mission-with-token
                    (update-in mission-after
                               [:boards execution-id :tasks]
                               assoc :token-task
                               {:task-id :token-task
                                :state :ready
                                :needs-token true
                                :owner-id nil
                                :claim-id nil
                                :rev 0
                                :wait-for #{}
                                :child-ids []
                                :result-refs []})
                    won (cmd! mission-with-token
                              {:op :take-or-die
                               :tenure-id execution-id
                               :actor-id holder-id
                               :request-id "actor-holder-1"
                               :confirm :i-hereby-confirm-i-die-without-regret})
                    claimed (cmd! (:state won)
                                  {:op :board-command
                                   :tenure-id execution-id
                                   :command {:op :claim
                                             :actor-id holder-id
                                             :task-id :token-task
                                             :atomicity-reason "test"}})
                    taken (cmd! (:state claimed)
                                {:op :board-command
                                 :tenure-id execution-id
                                 :command {:op :finish
                                           :actor-id holder-id
                                           :task-id :token-task
                                           :claim-id (get-in (:state claimed)
                                                             [:boards execution-id
                                                              :tasks :token-task
                                                              :claim-id])
                                           :result-summary "done"}})
                    resynced (actors/sync-tenure (:state taken) actor-after
                                                 execution-id)
                    rebirth (some #(when (and (= :actor/rebirth (:type %))
                                              (= :slot-a (:slot-id %)))
                                     %)
                                  (:effects resynced))]
                (is (:ok? taken) (pr-str (:error taken)))
                (is (:ok? resynced) (pr-str (:error resynced)))
                (is rebirth)
                (is (= holder-id (:old-agent-id rebirth)))
                (is (not= holder-id (:agent-id rebirth)))
                (is (= [] (actors/context (:actors resynced)
                                          execution-id :slot-a)))
                (let [rewoken (actors/wake-all (:mission resynced)
                                               (:actors resynced)
                                               execution-id)
                      start (some #(when (= :slot-a (:slot-id %)) %)
                                  (:effects rewoken))
                      new-agent (get-in rewoken
                                        [:actors :tenures execution-id
                                         :slots :slot-a :agent-id])]
                  (is start)
                  (is (= new-agent (get-in start [:request :agent-id])))
                  (is (= :task (get-in start [:request :model-role])))
                  (is (= "task-model" (get-in start [:request :model-id])))
                  (is (not (str/includes?
                            (request-text (:request start))
                            (str holder-id)))
                      "the reborn agent must not inherit the old context"))))))))))

(deftest plan-worker-reuses-task-model-and-own-context
  (let [h (support/host)
        _ (support/drive-plan-to-pending! h)
        mission-state (mission/snapshot h)
        actor-state (actors/new-state {:models (support/models)
                                       :workspace-root "worktrees/plan"})
        requested (actors/plan-worker-request
               mission-state actor-state
               {:call-id "actor-devops-1"
                :verbose-intent "Inspect the repository"})]
    (is (:ok? requested) (pr-str (:error requested)))
    (let [start (clojure.core/first (:effects requested))
          request (:request start)]
      (is (= :task (:role request)))
      (is (= :plan-worker (:mission-role request)))
      (is (= :task (:model-role request)))
      (is (= "task-model" (:model-id request)))
      (is (= :dedicated-worker (:program-role request)))
      (is (= "worktrees/plan" (:workspace request)))
      (is (nil? (:board-snapshot request)))
      (is (str/includes? (request-text request) "Inspect the repository"))
      (is (not (str/includes? (request-text request) "Swarm")))
      (let [completed (actors/accept-model-event
                       (:mission requested) (:actors requested)
                       (:attempt-id start)
                       {:event :completed :text "First report"})
            reused (actors/plan-worker-request
                    (:mission completed) (:actors completed)
                    {:call-id "actor-devops-2"
                     :verbose-intent "Inspect more"})]
        (is (:ok? reused) (pr-str (:error reused)))
        (is (= (get-in requested [:effects 0 :request :agent-id])
               (get-in reused [:effects 0 :request :agent-id]))
            "the same Plan tenure reuses the same dedicated worker")
        (is (= :task (get-in reused [:effects 0 :request :model-role])))
        (is (= "task-model" (get-in reused [:effects 0 :request :model-id])))
        (is (str/includes?
             (request-text (get-in reused [:effects 0 :request]))
             "First report")
            "the reused worker keeps its own prior report")
        (is (str/includes?
             (request-text (get-in reused [:effects 0 :request]))
             "Inspect more"))))))

(deftest board-work-prompt-carries-one-edn-ufss-board-block
  (let [h (support/host)
        _ (support/drive-plan-to-pending! h)
        _ (support/start-execution! h)
        execution-id (support/sole-execution-id h)
        goal "quote \" and\nsecond line"
        _ (support/start-swarm! h execution-id [{:key "slice" :value goal}])
        prompt (actors/board-work-prompt (mission/snapshot h)
                                         execution-id :worker-1)
        text (:text (last prompt))
        block (second
               (re-find
                #"(?s)\nPublic board \(EDN\+UFSS\):\n(.*)\nDecide your next legal action from this board\."
                text))]
    (is (some? block) "the prompt carries a structured board block")
    (when block
      (let [value (ufss/read-str block)]
        (is (= execution-id (:tenure-id value)))
        (is (= :worker-1 (:agent-id value)))
        (is (= [goal] (mapv :goal (:ready-tasks value))))))
    (is (not (str/includes? text "\\\""))
        "quotes and newlines use UFSS fences, not backslash escapes")
    (is (str/includes? text (str execution-id)))))

(deftest holder-conflict-leaves-one-holder-and-rebirths-the-loser
  (let [h (support/host)
        _ (support/drive-plan-to-pending! h)
        _ (support/start-execution! h)
        execution-id (support/sole-execution-id h)
        _ (support/start-swarm! h execution-id [{:key "token" :value "Needs token"}])
        mission-state (mission/snapshot h)
        task-id (first (support/root-task-ids h execution-id))
        actors0 (actors/new-state {:models (support/models)
                                   :slot-ids [:slot-a :slot-b]
                                   :workspaces
                                   {execution-id "worktrees/slice-a"}})
        initial-sync (actors/sync-tenure mission-state actors0 execution-id)
        mission-state (:mission initial-sync)
        actors (:actors initial-sync)
        agent-a (get-in actors [:tenures execution-id :slots :slot-a :agent-id])
        agent-b (get-in actors [:tenures execution-id :slots :slot-b :agent-id])
        updated (cmd! mission-state {:op :board-command
                             :tenure-id execution-id
                             :command {:op :update
                                       :actor-id agent-a
                                       :task-id task-id
                                       :needs-token true}})
        won (cmd! (:state updated)
                  {:op :take-or-die
                   :tenure-id execution-id
                   :actor-id agent-a
                   :request-id "conflict-holder-a"
                   :confirm :i-hereby-confirm-i-die-without-regret})
        lost (cmd! (:state won)
                   {:op :take-or-die
                    :tenure-id execution-id
                    :actor-id agent-b
                    :request-id "conflict-holder-b"
                    :confirm :i-hereby-confirm-i-die-without-regret})
        after-loss (:state lost)
        synced (actors/sync-tenure after-loss actors execution-id)]
    (is (= :attempt-won (get-in won [:receipt :result])))
    (is (= :attempt-lost (get-in lost [:receipt :result])))
    (is (= agent-a (get-in after-loss [:boards execution-id
                                       :token-slot :holder-id])))
    (is (= :dead (get-in after-loss [:bindings :execution execution-id
                                     :swarm agent-b :life-state])))
    (is (:ok? synced) (pr-str (:error synced)))
    (let [rebirth (some #(when (and (= :actor/rebirth (:type %))
                                    (= :slot-b (:slot-id %)))
                           %)
                        (:effects synced))
          new-b (get-in synced [:actors :tenures execution-id
                                :slots :slot-b :agent-id])]
      (is rebirth)
      (is (= agent-b (:old-agent-id rebirth)))
      (is (not= agent-b new-b))
      (is (= [] (actors/context (:actors synced) execution-id :slot-b)))
      (is (= "task-model" (get-in synced [:actors :models :task])))
      (is (:ok? (mission/check-invariants (:mission synced)))))))

(deftest late-old-terminal-cannot-overwrite-reborn-slot
  (let [h (support/host)
        _ (support/drive-plan-to-pending! h)
        _ (support/start-execution! h)
        execution-id (support/sole-execution-id h)
        _ (support/start-swarm! h execution-id [{:key "k" :value "Do it"}])
        mission-state (mission/snapshot h)
        actor-state (actors/new-state {:models (support/models)
                                       :slot-ids [:slot-a :slot-b]
                                       :workspaces
                                       {execution-id "worktrees/slice-a"}})
        synced (actors/sync-tenure mission-state actor-state execution-id)
        mission-state (:mission synced)
        actor-state (:actors synced)
        woken (actors/wake-all mission-state actor-state execution-id)
        start (some #(when (= :slot-a (:slot-id %)) %) (:effects woken))
        attempt-id (:attempt-id start)
        old-agent (get-in woken [:actors :tenures execution-id
                                 :slots :slot-a :agent-id])
        mission-with-token
        (update-in (:mission woken)
                   [:boards execution-id :tasks]
                   assoc :token-task
                   {:task-id :token-task
                    :state :ready
                    :needs-token true
                    :owner-id nil
                    :claim-id nil
                    :rev 0
                    :wait-for #{}
                    :child-ids []
                    :result-refs []})
        won (cmd! mission-with-token
                  {:op :take-or-die
                   :tenure-id execution-id
                   :actor-id old-agent
                   :request-id "late-terminal-holder"
                   :confirm :i-hereby-confirm-i-die-without-regret})
        claimed (cmd! (:state won)
                      {:op :board-command
                       :tenure-id execution-id
                       :command {:op :claim
                                 :actor-id old-agent
                                 :task-id :token-task
                                 :atomicity-reason "test"}})
        taken (cmd! (:state claimed)
                    {:op :board-command
                     :tenure-id execution-id
                     :command {:op :finish
                               :actor-id old-agent
                               :task-id :token-task
                               :claim-id (get-in (:state claimed)
                                                 [:boards execution-id
                                                  :tasks :token-task
                                                  :claim-id])
                               :result-summary "done"}})
        reborn (actors/sync-tenure (:state taken) (:actors woken)
                                   execution-id)
        new-agent (get-in reborn [:actors :tenures execution-id
                                  :slots :slot-a :agent-id])
        late (actors/accept-model-event
              (:mission reborn) (:actors reborn) attempt-id
              {:event :completed :text "late old answer"})
        slot-after (get-in late [:actors :tenures execution-id
                                 :slots :slot-a])]
    (is (:ok? taken))
    (is (:ok? reborn))
    (is (not= old-agent new-agent))
    (is (:ok? late))
    (is (= :stale-terminal
           (get-in late [:actors :calls attempt-id :status])))
    (is (= new-agent (:agent-id slot-after)))
    (is (not (some #(str/includes? (str (:text %)) "late old answer")
                   (:context slot-after))))))

(deftest failed-model-turn-uses-a-system-note-not-a-bare-tool-message
  (let [h (support/host)
        _ (support/drive-plan-to-pending! h)
        _ (support/start-execution! h)
        execution-id (support/sole-execution-id h)
        _ (support/start-swarm! h execution-id [{:key "k" :value "Do it"}])
        mission-state (mission/snapshot h)
        actor-state (actors/new-state {:models (support/models)
                                       :slot-ids [:slot-a :slot-b]
                                       :workspaces
                                       {execution-id "worktrees/slice-a"}})
        synced (actors/sync-tenure mission-state actor-state execution-id)
        woken (actors/wake-all (:mission synced) (:actors synced) execution-id)
        start (some #(when (= :slot-a (:slot-id %)) %) (:effects woken))
        failed (actors/accept-model-event
                (:mission woken) (:actors woken) (:attempt-id start)
                {:event :failed :message "network down"})
        context (get-in failed [:actors :tenures execution-id
                                :slots :slot-a :context])
        note (last context)]
    (is (:ok? failed))
    (is (= :system (:role note)))
    (is (str/includes? (str (:text note)) "network down"))
    (is (nil? (:name note)))
    (is (= :failed (get-in failed [:actors :calls
                                   (:attempt-id start) :status])))))

(deftest tool-results-stay-in-the-agent-context
  (let [mission-state (mission/initial-state {:models (support/models)})
        base (-> (actors/new-state {:models (support/models)
                                    :slot-ids [:slot-a]})
                 (assoc-in [:tenures "tenure-1" :slots :slot-a]
                           {:slot-id :slot-a
                            :agent-id :worker-1
                            :status :running
                            :context [{:role :user :text "work"}]
                            :attempt-id "att-1"})
                 (assoc-in [:calls "att-1"]
                           {:kind :swarm
                            :attempt-id "att-1"
                            :tenure-id "tenure-1"
                            :slot-id :slot-a
                            :agent-id :worker-1}))
        result (actors/accept-tool-result
                mission-state base "att-1"
                {:call-id "tool-1" :name "read_file"}
                "file body" false)
        context (get-in result [:actors :tenures "tenure-1"
                                :slots :slot-a :context])
        tool (last context)]
    (is (:ok? result) (pr-str (:error result)))
    (is (= 2 (count context))
        "the result is appended to the calling agent's private context")
    (is (= {:role :tool
            :tool-call-id "tool-1"
            :name "read_file"
            :text "file body"
            :error? false}
           tool)
        "the result keeps its own tool call id and name")
    (testing "a replay with the same tool-call-id appends nothing"
      (let [again (actors/accept-tool-result
                   (:mission result) (:actors result) "att-1"
                   {:call-id "tool-1" :name "read_file"}
                   "file body" false)]
        (is (:ok? again))
        (is (= 2 (count (get-in again [:actors :tenures "tenure-1"
                                      :slots :slot-a :context]))))))
    (testing "a legitimate repeated call carries a new id and appends"
      ;; The model may call the same tool twice in one turn.  Each call has
      ;; its own provider id, so the result must not be collapsed with the
      ;; earlier one.
      (let [repeat (actors/accept-tool-result
                    (:mission result) (:actors result) "att-1"
                    {:call-id "tool-1b" :name "read_file"}
                    "second body" false)]
        (is (:ok? repeat))
        (is (= 3 (count (get-in repeat [:actors :tenures "tenure-1"
                                        :slots :slot-a :context]))))
        (is (= "tool-1b"
               (:tool-call-id (last (get-in repeat
                                            [:actors :tenures "tenure-1"
                                             :slots :slot-a
                                             :context])))))))
    (testing "a reborn slot never receives the dead identity's result"
      (let [reborn (assoc-in (:actors result)
                             [:tenures "tenure-1" :slots :slot-a :agent-id]
                             :worker-2)
            stale (actors/accept-tool-result
                   mission-state reborn "att-1"
                   {:call-id "tool-2" :name "read_file"} "old" false)]
        (is (:ok? stale))
        (is (= 2 (count (get-in stale [:actors :tenures "tenure-1"
                                       :slots :slot-a :context]))))
        (is (some #(= :actor/stale-tool-result (:type %)) (:effects stale)))))
    (testing "an unknown parent attempt is rejected"
      (let [missing (actors/accept-tool-result
                     mission-state base "nope"
                     {:call-id "tool-3"} "x" false)]
        (is (false? (:ok? missing)))
        (is (= :UNKNOWN_ATTEMPT (get-in missing [:error :code])))))))

(deftest tool-result-falls-back-to-the-live-agent-identity
  ;; The parent attempt record can be gone while the agent is still live.
  ;; The tenure and agent on the event are enough to keep the responsibility
  ;; recoverable; the result must not be dropped.
  (let [mission-state (mission/initial-state {:models (support/models)})
        actors (-> (actors/new-state {:models (support/models)
                                      :slot-ids [:slot-a]})
                   (assoc-in [:tenures "tenure-1" :slots :slot-a]
                             {:slot-id :slot-a
                              :agent-id :worker-1
                              :status :running
                              :context []}))
        result (actors/accept-tool-result
                mission-state actors "pruned-attempt"
                {:call-id "tool-9"
                 :name "read_file"
                 :tenure-id "tenure-1"
                 :agent-id :worker-1}
                "recovered" false)
        context (get-in result [:actors :tenures "tenure-1"
                                :slots :slot-a :context])]
    (is (:ok? result) (pr-str (:error result)))
    (is (= 1 (count context)))
    (is (= "tool-9" (:tool-call-id (last context)))))
  (testing "no live slot means the result is undeliverable"
    (let [mission-state (mission/initial-state {:models (support/models)})
          actors (-> (actors/new-state {:models (support/models)
                                        :slot-ids [:slot-a]})
                     (assoc-in [:tenures "tenure-1" :slots :slot-a]
                               {:slot-id :slot-a
                                :agent-id :worker-2
                                :status :running
                                :context []}))
          result (actors/accept-tool-result
                  mission-state actors "pruned-attempt"
                  {:call-id "tool-9"
                   :tenure-id "tenure-1"
                   :agent-id :worker-1}
                  "lost" false)]
      (is (false? (:ok? result)))
      (is (= :UNKNOWN_ACTOR_SLOT (get-in result [:error :code]))))))

(deftest plan-worker-tool-result-stays-in-its-context
  (let [mission-state (mission/initial-state {:models (support/models)})
        actors (-> (actors/new-state {:models (support/models)
                                      :slot-ids [:slot-a]})
                   (assoc :plan-worker {:attempt-id "pw-1"
                                        :worker-id "w1"
                                        :context []})
                   (assoc-in [:calls "pw-1"]
                             {:kind :plan-worker
                              :attempt-id "pw-1"
                              :worker-id "w1"
                              :tenure-id "tenure-1"}))
        result (actors/accept-tool-result
                mission-state actors "pw-1"
                {:call-id "tool-pw" :name "read_file"}
                "file body" false)
        context (get-in result [:actors :plan-worker :context])]
    (is (:ok? result) (pr-str (:error result)))
    (is (= 1 (count context)))
    (is (= "tool-pw" (:tool-call-id (last context))))
    (testing "a replay with the same tool-call-id appends nothing"
      (let [again (actors/accept-tool-result
                   (:mission result) (:actors result) "pw-1"
                   {:call-id "tool-pw" :name "read_file"}
                   "file body" false)]
        (is (:ok? again))
        (is (empty? (:effects again)))
        (is (= 1 (count (get-in again [:actors :plan-worker :context]))))))
    (testing "a legitimate repeated call carries a new id and appends"
      (let [repeat (actors/accept-tool-result
                    (:mission result) (:actors result) "pw-1"
                    {:call-id "tool-pw-2" :name "read_file"}
                    "second body" false)]
        (is (:ok? repeat))
        (is (= 2 (count (get-in repeat [:actors :plan-worker :context]))))
        (is (= "tool-pw-2"
               (:tool-call-id (last (get-in repeat
                                            [:actors :plan-worker
                                             :context])))))))
    (testing "a steered attempt is stale, not appended"
      (let [steered (assoc-in (:actors result) [:plan-worker :attempt-id]
                              "pw-2")
            stale (actors/accept-tool-result
                   mission-state steered "pw-1"
                   {:call-id "tool-pw-2"} "late" false)]
        (is (:ok? stale))
        (is (= 1 (count (get-in stale [:actors :plan-worker :context]))))
        (is (some #(= :actor/stale-tool-result (:type %))
                  (:effects stale)))))))
