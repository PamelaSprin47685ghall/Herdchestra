(ns hc.compose.mission.surface-test
  (:require [clojure.test :refer [deftest is testing]]
            [hc.compose.mission :as mission]
            [hc.compose.mission.surface :as surface]
            [hc.compose.mission.support :as support]))

(defn- cmd!
  [state command]
  (let [result (mission/apply-command state command)]
    (is (:ok? result) (pr-str (:error result)))
    result))

(deftest program-role-map-is-complete
  (is (= :clj-plan (surface/program-role-for :plan)))
  (is (= :clj-review (surface/program-role-for :reviewer)))
  (is (= :clj-worker (surface/program-role-for :worker)))
  (is (= :clj-worker (surface/program-role-for :holder)))
  (is (= :dedicated-worker (surface/program-role-for :plan-worker)))
  (is (= :dedicated-worker (surface/program-role-for :dedicated-worker)))
  (is (nil? (surface/program-role-for :unknown))))

(deftest plan-context-carries-s1-and-write-unlock
  (let [state (mission/initial-state
               {:session-id "surface"
                :user-input "Build"
                :models (support/models)
                :draft {:path "plans/surface.md" :content "Draft"}})
        before (surface/program-context
                state
                {:role :plan
                 :readable-paths #{"attachments/a.txt"}})
        unlocked (cmd! state {:op :review
                              :lane :plan
                              :tenure-id (get-in state [:relay :lanes :plan
                                                        :tenure-id])
                              :call-id "surface-plan-review"
                              :findings [{:key "k" :value "v"}]})
        after (surface/program-context
               (:state unlocked)
               {:role :plan})]
    (is (:ok? before) (pr-str (:error before)))
    (is (= :clj-plan (get-in before [:context :program-role])))
    (is (= :main (get-in before [:context :model-role])))
    (is (= "main-model" (get-in before [:context :model-id])))
    (is (true? (get-in before [:context :s1?])))
    (is (false? (get-in before [:context :holder?])))
    (is (false? (get-in before [:context :write-unlocked?])))
    (is (= "plans/surface.md" (get-in before [:context :draft-path])))
    (is (= #{"attachments/a.txt" "plans/surface.md"}
           (get-in before [:context :readable-paths])))
    (is (:ok? after))
    (is (true? (get-in after [:context :write-unlocked?])))))

(deftest reviewer-worker-and-holder-contexts
  (let [h (support/host)
        _ (support/drive-plan-to-pending! h)
        _ (support/start-execution! h)
        execution-id (support/sole-execution-id h)
        phase-a (mission/snapshot h)
        reviewer (surface/program-context phase-a
                                          {:role :reviewer
                                           :tenure-id execution-id})
        _ (support/start-swarm! h execution-id
                                [{:key "k" :value "Do it"}])
        state (mission/snapshot h)
        closed (surface/program-context state {:role :reviewer
                                               :tenure-id execution-id})
        worker-registered (cmd! state {:op :register-swarm-actor
                                       :tenure-id execution-id
                                       :slot-id :slot-a})
        state (:state worker-registered)
        worker-id (get-in worker-registered [:receipt :agent-id])
        worker (surface/program-context state {:role :worker
                                               :tenure-id execution-id
                                               :agent-id worker-id})]
    (is (= :clj-review (get-in reviewer [:context :program-role])))
    (is (= :main (get-in reviewer [:context :model-role])))
    (is (= "main-model" (get-in reviewer [:context :model-id])))
    (is (false? (get-in reviewer [:context :holder?])))
    (testing "Phase B closes the Reviewer tool surface"
      (is (= :REVIEW_WINDOW_CLOSED (get-in closed [:error :code]))))
    (is (= :clj-worker (get-in worker [:context :program-role])))
    (is (= :task (get-in worker [:context :model-role])))
    (is (= "task-model" (get-in worker [:context :model-id])))
    (is (false? (get-in worker [:context :holder?])))
    (testing "holder context follows the board token slot"
      (let [task-id (first (support/root-task-ids h execution-id))
            updated (cmd! state {:op :board-command
                                 :tenure-id execution-id
                                 :command {:op :update
                                           :actor-id worker-id
                                           :task-id task-id
                                           :needs-token true}})
            taken (cmd! (:state updated)
                        {:op :take-or-die
                         :tenure-id execution-id
                         :actor-id worker-id
                         :request-id "surface-token"
                         :confirm :i-hereby-confirm-i-die-without-regret})
            holder (surface/program-context (:state taken)
                                            {:role :holder
                                             :tenure-id execution-id
                                             :agent-id worker-id})]
        (is (:ok? taken))
        (is (= :holder (get-in holder [:context :role])))
        (is (true? (get-in holder [:context :holder?])))
        (is (= :task (get-in holder [:context :model-role])))
        (is (= "task-model" (get-in holder [:context :model-id])))))
    (testing "wrong role/agent is refused"
      (let [wrong-role (surface/program-context
                        state {:role :holder
                               :tenure-id execution-id
                               :agent-id worker-id})
            wrong-agent (surface/program-context
                         state {:role :worker
                                :tenure-id execution-id
                                :agent-id :agent-unknown})]
        (is (= :ROLE_BINDING_MISMATCH (get-in wrong-role [:error :code])))
        (is (= :UNKNOWN_ACTOR_BINDING (get-in wrong-agent [:error :code])))))))

(deftest plan-worker-context-uses-task-model
  (let [state (mission/initial-state {:session-id "surface"
                                      :user-input "Build"
                                      :models (support/models)})
        context (surface/program-context state {:role :plan-worker})]
    (is (:ok? context))
    (is (= :plan-worker (get-in context [:context :role])))
    (is (= :dedicated-worker (get-in context [:context :program-role])))
    (is (= :task (get-in context [:context :model-role])))
    (is (= "task-model" (get-in context [:context :model-id])))
    (is (false? (get-in context [:context :s1?])))
    (is (false? (get-in context [:context :holder?])))))

(deftest ambiguous-execution-tenure-is-refused-without-id
  (let [h (support/host)
        _ (support/drive-plan-to-pending! h)
        plan-id (support/plan-tenure-id h)
        first-handoff (cmd! (mission/snapshot h)
                            {:op :review
                             :lane :plan
                             :tenure-id plan-id
                             :call-id "surface-handoff-1"
                             :findings []})
        second-handoff (cmd! (:state first-handoff)
                             {:op :review
                              :lane :plan
                              :tenure-id plan-id
                              :call-id "surface-handoff-2"
                              :findings []})
        state (:state second-handoff)
        ambiguous (surface/program-context state {:role :reviewer})
        first-id (get-in first-handoff [:receipt :execution-tenure-id])
        specific (surface/program-context state {:role :reviewer
                                                 :tenure-id first-id})]
    (is (= :AMBIGUOUS_TENURE (get-in ambiguous [:error :code])))
    (is (:ok? specific))
    (is (= first-id (get-in specific [:context :tenure-id])))))

(deftest plan-awaiting-user-blocks-the-write-unlock
  (let [state (mission/initial-state
               {:session-id "surface-awaiting"
                :user-input "Build"
                :models (support/models)})
        plan-id (get-in state [:relay :lanes :plan :tenure-id])
        unlocked (cmd! state {:op :review
                              :lane :plan
                              :tenure-id plan-id
                              :call-id "surface-unlock"
                              :findings [{:key "k" :value "v"}]})
        before (surface/program-context (:state unlocked) {:role :plan})
        awaiting (assoc-in (:state unlocked)
                           [:relay :lanes :plan :awaiting-user?] true)
        after (surface/program-context awaiting {:role :plan})]
    (is (true? (get-in before [:context :write-unlocked?])))
    (is (false? (get-in after [:context :write-unlocked?]))
        "an awaiting-user Plan must not advertise the draft write")))
