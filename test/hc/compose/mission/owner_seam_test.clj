(ns hc.compose.mission.owner-seam-test
  "Pure state-in/state-out seam used by hc.compose.owner."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hc.compose.mission :as mission]
            [hc.compose.mission.support :as support]))

(def ^:private call-counter (atom 0))

(defn- call-id
  []
  (str "seam-" (swap! call-counter inc)))

(defn- cmd!
  [state command]
  (let [result (mission/apply-command state command)]
    (is (:ok? result) (pr-str (:error result)))
    result))

(defn- drive-to-execution
  [state]
  (let [plan-id (get-in state [:relay :lanes :plan :tenure-id])
        state (-> state
                  (as-> s (cmd! s {:op :review :lane :plan :tenure-id plan-id
                                   :call-id (call-id) :findings []}))
                  :state
                  (as-> s (cmd! s {:op :resume :lane :plan :tenure-id plan-id}))
                  :state
                  (as-> s (cmd! s {:op :review :lane :plan :tenure-id plan-id
                                   :call-id (call-id) :findings []}))
                  :state
                  (as-> s (cmd! s {:op :review :lane :plan
                                   :tenure-id (get-in s [:relay :lanes :plan
                                                         :tenure-id])
                                   :call-id (call-id) :findings []}))
                  :state
                  (as-> s (cmd! s {:op :set-draft
                                   :path "plans/seam.md"
                                   :content "Seam draft."
                                   :tenure-id (get-in s [:relay :lanes :plan
                                                         :tenure-id])}))
                  :state
                  (as-> s (cmd! s {:op :review :lane :plan
                                   :tenure-id (get-in s [:relay :lanes :plan
                                                         :tenure-id])
                                   :call-id (call-id) :findings []}))
                  :state
                  (as-> s (cmd! s {:op :review :lane :plan
                                   :tenure-id (get-in s [:relay :lanes :plan
                                                         :tenure-id])
                                   :call-id (call-id) :findings []}))
                  :state
                  (as-> s (cmd! s {:op :review :lane :plan
                                   :tenure-id (get-in s [:relay :lanes :plan
                                                         :tenure-id])
                                   :call-id (call-id) :findings []})))]
    (:state state)))

(defn- drive-to-completed
  [state]
  (let [state (drive-to-execution state)
        execution-id (-> state :relay :lanes :execution keys first)
        swarm (cmd! state {:op :review :lane :execution
                           :tenure-id execution-id
                           :call-id (call-id)
                           :findings [{:key "k" :value "Do it"}]})
        state (:state swarm)
        registered (cmd! state {:op :register-swarm-actor
                                :tenure-id execution-id
                                :slot-id :slot-a})
        state (:state registered)
        worker-id (get-in registered [:receipt :agent-id])
        task-id (-> state :boards (get execution-id) :tasks keys first)
        claimed (cmd! state {:op :claim-task
                             :tenure-id execution-id
                             :actor-id worker-id
                             :task-id task-id
                             :atomicity-reason "one root"})
        state (:state claimed)
        finished (cmd! state {:op :finish-root-task
                              :tenure-id execution-id
                              :actor-id worker-id
                              :task-id task-id
                              :claim-id (get-in claimed [:receipt :claim-id])
                              :result-summary "done"
                              :board-facts {:ff-ready? true}})
        state (:state finished)
        reviewer-id (-> state :relay :lanes :execution keys first)
        closing (cmd! state {:op :review :lane :execution
                             :tenure-id reviewer-id
                             :call-id (call-id)
                             :findings []})
        state (:state closing)
        completed (cmd! state {:op :review :lane :execution
                               :tenure-id reviewer-id
                               :call-id (call-id)
                               :findings []
                               :board-facts (support/quiescent-facts)})]
    (:state completed)))

(deftest initial-state-and-restore-are-pure
  (let [state (mission/initial-state {:session-id "seam"
                                      :user-input "Build"
                                      :user-attachments
                                      [{:attachment-id "a1"
                                        :blob-id "blob-1"
                                        :name "spec.txt"
                                        :content "secret body"
                                        :resolution "agent summary"}]
                                      :models (support/models)})
        restored (mission/restore state)]
    (is (map? state))
    (is (nil? (:mission state)))
    (is (= :S1 (get-in state [:relay :lanes :plan :milestone])))
    (is (= :plan (get-in state [:bindings :plan :role])))
    (is (= :main (get-in state [:bindings :plan :model-role])))
    (is (= [{:attachment-id "a1"
             :blob-id "blob-1"
             :name "spec.txt"}]
           (get-in state [:relay :user-inputs 0 :attachments])))
    (is (not (contains? (get-in state [:relay :user-inputs 0
                                       :attachments 0])
                        :content)))
    (is (:ok? (mission/check-invariants state)))
    (is (nil? (mission/current-board state)))
    (is (= (:relay state) (mission/relay-of state)))
    (is (:ok? restored))
    (is (= state (:state restored)))
    (testing "an Owner state carrying :mission is unwrapped"
      (is (= state (:state (mission/restore {:mission state
                                             :calls {}})))))
    (testing "a malformed state is rejected, not guessed"
      (let [bad (mission/restore (assoc state :models nil))]
        (is (false? (:ok? bad)))
        (is (= :INVALID_MISSION_STATE (get-in bad [:error :code])))))))

(deftest apply-command-is-state-in-state-out
  (let [state (mission/initial-state {:session-id "seam"
                                      :user-input "Build"
                                      :models (support/models)})
        plan-id (get-in state [:relay :lanes :plan :tenure-id])
        result (cmd! state {:op :review :lane :plan :tenure-id plan-id
                            :call-id (call-id) :findings []})]
    (is (= :ask (get-in result [:receipt :outcome])))
    (is (false? (get-in state [:relay :lanes :plan :awaiting-user?]))
        "the input state is not mutated")
    (is (true? (get-in result [:state :relay :lanes :plan
                               :awaiting-user?])))
    (is (= :relay/ask (get-in (first (:effects result)) [:type])))
    (testing "unknown commands fail without state change"
      (let [bad (mission/apply-command state {:op :unknown})]
        (is (false? (:ok? bad)))
        (is (= :UNKNOWN_MISSION_COMMAND (get-in bad [:error :code])))
        (is (= state (:state bad)))))))

(deftest board-command-delegates-and-finish-root-task-crosses-relay
  (let [state (-> (mission/initial-state {:session-id "seam"
                                          :user-input "Build"
                                          :models (support/models)})
                  drive-to-execution)
        execution-id (-> state :relay :lanes :execution keys first)
        swarm (cmd! state {:op :review :lane :execution
                           :tenure-id execution-id
                           :call-id (call-id)
                           :findings [{:key "k" :value "Do it"}]})
        state (:state swarm)
        task-id (-> state :boards (get execution-id) :tasks keys first)
        registered (cmd! state {:op :board-command
                                :tenure-id execution-id
                                :command {:op :register-actor :slot-id :slot-a}})
        state (:state registered)
        worker-id (get-in registered [:receipt :agent-id])
        binding (get-in state [:bindings :execution execution-id
                               :swarm worker-id])]
    (is (= :swarm-started (get-in swarm [:receipt :outcome])))
    (is (= :task (:model-role binding)))
    (testing "typed board command delegation keeps bindings consistent"
      (let [claimed (cmd! state {:op :board-command
                                 :tenure-id execution-id
                                 :command {:op :claim
                                           :actor-id worker-id
                                           :task-id task-id
                                           :atomicity-reason "one root"}})
            claim-id (get-in claimed [:receipt :claim-id])
            state (:state claimed)]
        (is (keyword? claim-id))
        (is (= :task-claimed (get-in claimed [:receipt :outcome])))
        (testing "a board summary is flat and tenure-scoped"
          (let [summary (mission/board-summary state execution-id
                                               {:ff-ready? true
                                                :report-pending 0})]
            (is (= 1 (:unresolved-tasks summary)))
            (is (= 0 (:unhandled-inputs summary)))
            (is (= [] (:user-inputs summary)))
            (is (= 0 (:inflight-actions summary)))
            (is (true? (:holder-retired? summary)))
            (is (false? (:all-root-tasks-done? summary)))
            (is (true? (:ff-ready? summary)))
            (is (= 0 (:unresolved-token-tasks summary)))))
        (testing "a root :finish board command crosses the relay barrier"
          (let [finished (cmd! state {:op :board-command
                                      :tenure-id execution-id
                                      :command {:op :finish
                                                :actor-id worker-id
                                                :task-id task-id
                                                :claim-id claim-id
                                                :result-summary "done"}
                                      :board-facts {:ff-ready? true}})
                next-state (:state finished)
                next-id (-> next-state :relay :lanes :execution keys first)]
            (is (= :relayed (get-in finished [:receipt :outcome])))
            (is (nil? (mission/board-of next-state execution-id)))
            (is (= :main (get-in next-state [:bindings :execution next-id
                                             :reviewer :model-role])))))))))

(deftest devops-pure-seam-reuses-worker-and-keeps-report
  (let [state (mission/initial-state {:session-id "seam"
                                      :user-input "Build"
                                      :models (support/models)})
        plan-id (get-in state [:relay :lanes :plan :tenure-id])
        unlocked (cmd! state {:op :review :lane :plan :tenure-id plan-id
                              :call-id (call-id)
                              :findings [{:key "k" :value "v"}]})
        state (:state unlocked)
        requested (cmd! state {:op :devops-request
                               :tenure-id plan-id
                               :call-id "devops-seam-1"
                               :verbose-intent "inspect"})
        worker-id (get-in requested [:receipt :worker-id])
        completed (cmd! (:state requested)
                        {:op :devops-complete
                         :tenure-id plan-id
                         :worker-id worker-id
                         :call-id "devops-seam-1"
                         :status :ok
                         :report "verbatim\nreport"})
        replayed (cmd! (:state completed)
                       {:op :devops-request
                        :tenure-id plan-id
                        :call-id "devops-seam-1"
                        :verbose-intent "inspect"})]
    (is (= :plan-worker (get-in requested [:receipt :contract :role])))
    (is (= :task (get-in requested [:receipt :model-role])))
    (is (= "verbatim\nreport" (get-in completed [:receipt :report])))
    (is (true? (get-in replayed [:receipt :replayed])))
    (is (empty? (:effects replayed)))
    (is (:ok? (mission/check-invariants (:state replayed))))))

(deftest root-cancel-releases-relay-ledger-and-barriers
  (let [state (-> (mission/initial-state {:session-id "seam"
                                          :user-input "Build"
                                          :models (support/models)})
                  drive-to-execution)
        execution-id (-> state :relay :lanes :execution keys first)
        swarm (cmd! state {:op :review :lane :execution
                           :tenure-id execution-id
                           :call-id (call-id)
                           :findings [{:key "a" :value "Do a"}
                                      {:key "b" :value "Do b"}]})
        state (:state swarm)
        registered (cmd! state {:op :register-swarm-actor
                                :tenure-id execution-id
                                :slot-id :slot-a})
        state (:state registered)
        worker-id (get-in registered [:receipt :agent-id])
        by-key (into {} (map (juxt :ledger-key identity)
                             (vals (get-in state [:boards execution-id
                                                  :tasks]))))
        a-task (:task-id (get by-key "a"))
        b-task (:task-id (get by-key "b"))
        cancelled (cmd! state {:op :board-command
                               :tenure-id execution-id
                               :command {:op :cancel
                                         :actor-id worker-id
                                         :task-id a-task
                                         :reason "obsolete"}})
        after-cancel (:state cancelled)]
    (is (= :root-cancelled (get-in cancelled [:receipt :outcome])))
    (is (= #{"b"} (set (keys (get-in after-cancel
                                     [:relay :lanes :execution execution-id
                                      :ledger])))))
    (is (= #{"b"} (set (map :ledger-key
                            (get-in after-cancel
                                    [:relay :lanes :execution execution-id
                                     :root-tasks])))))
    (is (= #{"b"} (set (keep :ledger-key
                             (vals (get-in after-cancel
                                           [:boards execution-id :tasks]))))))
    (is (not (contains? (get-in after-cancel
                                [:bindings :execution execution-id
                                 :root-tasks]) a-task)))
    (is (= :active (get-in after-cancel
                           [:relay :lanes :execution execution-id
                            :swarm-phase])))
    (testing "cancelling the last root raises the relay barrier"
      (let [finished (cmd! after-cancel
                           {:op :cancel-root-task
                            :tenure-id execution-id
                            :actor-id worker-id
                            :task-id b-task
                            :reason "no longer needed"
                            :board-facts {:ff-ready? true}})
            next-state (:state finished)
            next-id (-> next-state :relay :lanes :execution keys first)]
        (is (= :relayed (get-in finished [:receipt :outcome])))
        (is (nil? (mission/board-of next-state execution-id)))
        (is (= :main (get-in next-state [:bindings :execution next-id
                                         :reviewer :model-role])))))))

(deftest root-replace-keeps-ledger-and-updates-mirror
  (let [state (-> (mission/initial-state {:session-id "seam"
                                          :user-input "Build"
                                          :models (support/models)})
                  drive-to-execution)
        execution-id (-> state :relay :lanes :execution keys first)
        swarm (cmd! state {:op :review :lane :execution
                           :tenure-id execution-id
                           :call-id (call-id)
                           :findings [{:key "k" :value "Old goal"}]})
        state (:state swarm)
        registered (cmd! state {:op :register-swarm-actor
                                :tenure-id execution-id
                                :slot-id :slot-a})
        state (:state registered)
        worker-id (get-in registered [:receipt :agent-id])
        old-task (first (vals (get-in state [:boards execution-id :tasks])))
        replaced (cmd! state {:op :board-command
                              :tenure-id execution-id
                              :command {:op :replace
                                        :actor-id worker-id
                                        :task-id (:task-id old-task)
                                        :reason "better scope"
                                        :replacement {:goal "New goal"
                                                      :remaining-work "New goal"
                                                      :done-when "New goal is done"}}})
        after-replace (:state replaced)
        new-task-id (get-in replaced [:receipt :new-task-id])
        new-task (get-in after-replace
                         [:boards execution-id :tasks new-task-id])]
    (is (= :root-replaced (get-in replaced [:receipt :outcome])))
    (is (= #{"k"} (set (keys (get-in after-replace
                                     [:relay :lanes :execution execution-id
                                      :ledger])))))
    (is (= "New goal"
           (->> (get-in after-replace
                        [:relay :lanes :execution execution-id :root-tasks])
                (filter #(= "k" (:ledger-key %)))
                first
                :goal)))
    (is (= "k" (:ledger-key new-task)))
    (is (= "New goal" (:goal new-task)))
    (is (= :ready (:state new-task)))
    (is (= :active (get-in after-replace
                           [:relay :lanes :execution execution-id
                            :swarm-phase]))
        "replace does not release the obligation or raise a barrier")
    (testing "the replacement can later finish and raise the barrier"
      (let [claimed (cmd! after-replace
                          {:op :claim-task
                           :tenure-id execution-id
                           :actor-id worker-id
                           :task-id new-task-id
                           :atomicity-reason "replacement root"})
            finished (cmd! (:state claimed)
                           {:op :finish-root-task
                            :tenure-id execution-id
                            :actor-id worker-id
                            :task-id new-task-id
                            :claim-id (get-in claimed [:receipt :claim-id])
                            :result-summary "replacement done"
                            :board-facts {:ff-ready? true}})]
        (is (= :relayed (get-in finished [:receipt :outcome])))
        (is (nil? (mission/board-of (:state finished) execution-id)))))))

(deftest reopen-requires-completed-and-operator
  (let [initial (mission/initial-state {:session-id "seam"
                                        :user-input "Build"
                                        :models (support/models)})
        not-completed (mission/apply-command
                       initial
                       {:op :reopen
                        :reason :operator-message
                        :user-input "Continue"
                        :request-id "reopen-not-completed"})]
    (is (false? (:ok? not-completed)))
    (is (= :MISSION_NOT_COMPLETED (get-in not-completed [:error :code]))))
  (let [completed (drive-to-completed
                   (mission/initial-state {:session-id "seam"
                                           :user-input "Build"
                                           :models (support/models)}))
        wrong-reason (mission/apply-command
                      completed
                      {:op :reopen
                       :reason :agent-message
                       :user-input "Continue"
                       :request-id "reopen-wrong-reason"})
        blank-input (mission/apply-command
                     completed
                     {:op :reopen
                      :reason :operator-message
                      :user-input "   "
                      :request-id "reopen-blank"})
        missing-request (mission/apply-command
                         completed
                         {:op :reopen
                          :reason :operator-message
                          :user-input "Continue"})]
    (is (= :REOPEN_NOT_OPERATOR (get-in wrong-reason [:error :code])))
    (is (= :INVALID_OPERATOR_INPUT (get-in blank-input [:error :code])))
    (is (= :REQUEST_ID_REQUIRED (get-in missing-request [:error :code])))
    (is (:ok? (mission/check-invariants completed)))))

(deftest reopen-creates-fresh-reviewer-and-empty-review-relays
  (let [completed (drive-to-completed
                   (mission/initial-state {:session-id "seam"
                                           :user-input "Build"
                                           :models (support/models)}))
        completed-id (-> completed :relay :lanes :execution keys first)
        barriers-before (count (get-in completed [:relay :barriers]))
        draft-before (get-in completed [:relay :draft])
        session-before (get-in completed [:relay :session-id])
        reopened (cmd! completed
                       {:op :reopen
                        :reason :operator-message
                        :user-input "Continue with the next slice"
                        :user-attachments
                        [{:attachment-id "a2"
                          :blob-id "blob-2"
                          :name "next.txt"
                          :content "secret body"
                          :resolution "agent summary"}]
                        :request-id "reopen-1"})
        state (:state reopened)
        new-id (get-in reopened [:receipt :tenure-id])
        new-tenure (get-in state [:relay :lanes :execution new-id])]
    (is (= :reopened (get-in reopened [:receipt :outcome])))
    (is (= :reviewer (get-in reopened [:receipt :role])))
    (is (= completed-id (get-in reopened [:receipt :from-tenure-id])))
    (is (not= completed-id new-id))
    (testing "the completed tenure remains as terminal evidence"
      (is (= :completed (get-in state
                                [:relay :lanes :execution completed-id
                                 :status])))
      (is (= :completed (get-in state
                                [:relay :lanes :execution completed-id
                                 :milestone])))
      (is (some? (mission/board-of state completed-id))))
    (testing "the new tenure is immediately reviewable without a new ask"
      (is (= :active (:status new-tenure)))
      (is (= :execution (:milestone new-tenure)))
      (is (false? (:initial? new-tenure)))
      (is (false? (:first-review? new-tenure)))
      (is (some? (mission/board-of state new-id)))
      (is (= :main (get-in state [:bindings :execution new-id
                                  :reviewer :model-role]))))
    (testing "operator text and history survive"
      (is (= "Continue with the next slice"
             (get-in state [:relay :user-input])))
      (is (= ["Build" "Continue with the next slice"]
             (mapv :text (get-in state [:relay :user-inputs]))))
      (is (= [{:attachment-id "a2"
               :blob-id "blob-2"
               :name "next.txt"}]
             (get-in state [:relay :user-inputs 1 :attachments])))
      (is (= draft-before (get-in state [:relay :draft])))
      (is (= session-before (get-in state [:relay :session-id])))
      (is (>= (count (get-in state [:relay :barriers])) barriers-before))
      (is (= completed-id (get-in state [:reopens 0 :from-tenure-id])))
      (is (= new-id (get-in state [:reopens 0 :to-tenure-id]))))
    (testing "reopen is idempotent by request-id"
      (let [replay (cmd! state
                         {:op :reopen
                          :reason :operator-message
                          :user-input "Continue with the next slice"
                          :user-attachments
                          [{:attachment-id "a2"
                            :blob-id "blob-2"
                            :name "next.txt"
                            :content "secret body"
                            :resolution "agent summary"}]
                          :request-id "reopen-1"})
            conflict (mission/apply-command
                      state
                      {:op :reopen
                       :reason :operator-message
                       :user-input "Different text"
                       :request-id "reopen-1"})]
        (is (true? (get-in replay [:receipt :replayed])))
        (is (empty? (:effects replay)))
        (is (= :REQUEST_ID_CONFLICT (get-in conflict [:error :code])))))
    (testing "an empty first review relays to an independent successor"
      (let [relayed (cmd! state {:op :review
                                 :lane :execution
                                 :tenure-id new-id
                                 :call-id (call-id)
                                 :findings []})
            after (:state relayed)
            successor-id (->> (get-in after
                                      [:relay :lanes :execution])
                              keys
                              (remove #{completed-id new-id})
                              first)]
        (is (= :relayed (get-in relayed [:receipt :outcome])))
        (is (contains? (get-in after [:relay :retired-tenure-ids]) new-id))
        (is (some? successor-id))
        (is (some? (mission/board-of after successor-id)))
        (is (= :main (get-in after [:bindings :execution successor-id
                                    :reviewer :model-role])))
        (is (= "Continue with the next slice"
               (get-in after [:relay :user-input])))
        (is (str/includes?
             (:user-input (:context (last (get-in after [:relay :barriers]))))
             "Continue with the next slice"))
        (is (= [{:attachment-id "a2"
                 :blob-id "blob-2"
                 :name "next.txt"}]
               (:user-attachments
                (:context (last (get-in after [:relay :barriers]))))))
        (is (:ok? (mission/check-invariants after)))))))

(deftest reopen-nonempty-review-enters-swarm
  (let [completed (drive-to-completed
                   (mission/initial-state {:session-id "seam"
                                           :user-input "Build"
                                           :models (support/models)}))
        completed-id (-> completed :relay :lanes :execution keys first)
        reopened (cmd! completed
                       {:op :reopen
                        :reason :operator-message
                        :user-input "Add the missing integration test"
                        :request-id "reopen-swarm"})
        state (:state reopened)
        new-id (get-in reopened [:receipt :tenure-id])
        swarm (cmd! state {:op :review
                           :lane :execution
                           :tenure-id new-id
                           :call-id (call-id)
                           :findings [{:key "integration"
                                       :value "Add the integration test"}]})
        after (:state swarm)]
    (is (= :swarm-started (get-in swarm [:receipt :outcome])))
    (is (= :active (get-in after
                           [:relay :lanes :execution new-id :swarm-phase])))
    (is (= :completed (get-in after
                              [:relay :lanes :execution completed-id :status])))
    (is (= #{"integration"}
           (set (map :ledger-key
                     (vals (get-in after [:boards new-id :tasks]))))))
    (is (:ok? (mission/check-invariants after)))))
