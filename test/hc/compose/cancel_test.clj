(ns hc.compose.cancel-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.compose.cancel :as cancel]
            [hc.compose.cancel.model :as model]))

;; ---------------------------------------------------------------------------
;; Helpers

(defn- node
  [state node-id]
  (get-in state [:nodes node-id]))

(defn- sample-state []
  (-> (cancel/new-state {:session-id "session-1" :run-id "run-1"})
      (cancel/add-nodes
       [{:node-id "call-running"
         :kind :call
         :parent-id "run-1"
         :state :running
         :side-effecting? true
         :current-attempt-id "attempt-running"
         :group-id "g-calls"}
        {:node-id "attempt-running"
         :kind :attempt
         :parent-id "call-running"
         :state :running
         :side-effecting? true}
        {:node-id "call-ready"
         :kind :call
         :parent-id "run-1"
         :state :ready
         ;; A call that never started owns no external resource.  The
         ;; `default-node` fallback is `true` on purpose ("a call may have
         ;; effects unless it says otherwise"), so a fixture that means "pure,
         ;; not yet dispatched" must say so explicitly.
         :side-effecting? false
         :group-id "g-calls"}
        {:node-id "call-retry"
         :kind :call
         :parent-id "run-1"
         :state :retry-wait
         :side-effecting? true}
        {:node-id "call-succeeded"
         :kind :call
         :parent-id "run-1"
         :state :succeeded}
        {:node-id "call-unknown"
         :kind :call
         :parent-id "run-1"
         :state :outcome-unknown}
        {:node-id "action-applying"
         :kind :action
         :parent-id "run-1"
         :state :applying
         :action-kind :edit
         :side-effecting? true}
        {:node-id "action-running"
         :kind :action
         :parent-id "run-1"
         :state :running
         :action-kind :shell
         :side-effecting? true}
        {:node-id "action-board"
         :kind :action
         :parent-id "run-1"
         :state :running
         :action-kind :board
         :side-effecting? false}
        {:node-id "action-waiting"
         :kind :action
         :parent-id "run-1"
         :state :waiting-gate
         :action-kind :edit
         :side-effecting? true}
        {:node-id "action-uncertain"
         :kind :action
         :parent-id "run-1"
         :state :uncertain
         :action-kind :shell
         :side-effecting? true
         :outcome :outcome-unknown}
        {:node-id "action-finished"
         :kind :action
         :parent-id "run-1"
         :state :finished
         :action-kind :edit
         :side-effecting? true
         :outcome :succeeded}
        {:node-id "process-running"
         :kind :process
         :parent-id "action-running"
         :state :running
         :side-effecting? true}
        {:node-id "process-uncertain"
         :kind :process
         :parent-id "action-uncertain"
         :state :uncertain
         :side-effecting? true
         :outcome :outcome-unknown}])))

(defn- operator-cancel
  [state]
  (cancel/accept-operator-cancel
   state
   {:session-id "session-1"
    :run-id "run-1"
    :command-id "command-1"
    :origin :operator-esc
    :at 1000}))

;; ---------------------------------------------------------------------------
;; Operator cancellation

(deftest operator-cancel-closes-admission-and-propagates
  (let [state (sample-state)
        result (operator-cancel state)
        state' (:state result)
        receipt (:receipt result)]
    (is (true? (:ok? result)))
    (is (empty? (model/validate-state state')))
    (testing "the run records the intent before propagation"
      (is (= :cancelling (get-in state' [:nodes "run-1" :state])))
      (is (= :closed (get-in state' [:nodes "run-1" :admission])))
      (is (= :user-cancel (get-in state' [:nodes "run-1" :cancellation :reason])))
      (is (= "command-1" (get-in state' [:nodes "run-1" :cancellation :command-id]))))
    (testing "running side-effecting work becomes unknown, not cancelled"
      (is (= :outcome-unknown (get-in state' [:nodes "call-running" :state])))
      (is (= :outcome-unknown (get-in state' [:nodes "attempt-running" :state])))
      (is (= :uncertain (get-in state' [:nodes "action-running" :state])))
      (is (= :outcome-unknown (get-in state' [:nodes "action-running" :outcome])))
      (is (= :stopping (get-in state' [:nodes "process-running" :state]))))
    (testing "not-yet-started work is cancelled"
      (is (= :cancelled (get-in state' [:nodes "call-ready" :state])))
      (is (= :cancelled (get-in state' [:nodes "call-retry" :state])))
      (is (= :finished (get-in state' [:nodes "action-waiting" :state])))
      (is (= :cancelled (get-in state' [:nodes "action-waiting" :outcome])))
      (is (= :finished (get-in state' [:nodes "action-board" :state]))))
    (testing "applying work is deferred, not interrupted"
      (is (= :applying (get-in state' [:nodes "action-applying" :state])))
      (is (true? (get-in state' [:nodes "action-applying" :cancel-after-apply?]))))
    (testing "fixed and uncertain outcomes are preserved"
      (is (= :uncertain (get-in state' [:nodes "action-uncertain" :state])))
      (is (= :outcome-unknown (get-in state' [:nodes "action-uncertain" :outcome])))
      (is (= :finished (get-in state' [:nodes "action-finished" :state])))
      (is (= :succeeded (get-in state' [:nodes "action-finished" :outcome])))
      (is (= :succeeded (get-in state' [:nodes "call-succeeded" :state])))
      (is (= :outcome-unknown (get-in state' [:nodes "call-unknown" :state])))
      (is (= :uncertain (get-in state' [:nodes "process-uncertain" :state]))))
    (testing "the root cause is preserved on every touched node"
      (is (= :user-cancel (get-in state' [:nodes "run-1" :cancel-root-reason])))
      (doseq [id ["call-running" "call-ready" "attempt-running"
                  "action-running" "action-applying" "process-running"]]
        (is (= :user-cancel (get-in state' [:nodes id :cancel-root-reason])))
        (is (= :parent-terminated
               (get-in state' [:nodes id :cancel-reason])))))
    (testing "the receipt partitions every visited node exactly once"
      (let [visited (set (concat (:cancelled receipt)
                                 (:outcome-unknown receipt)
                                 (:deferred receipt)
                                 (:preserved receipt)))]
        (is (= (set (keys (:nodes state))) visited))
        (is (true? (:closed-admission? receipt)))
        (is (= :accepted (:status receipt)))))
    (testing "effects abort resources but never dispose or interrupt"
      (is (every? #(not (:dispose? %)) (:effects result)))
      (is (every? #(not (:thread-interrupt? %)) (:effects result)))
      (is (some #(and (= :close-admission (:effect %))
                      (= "run-1" (:run-id %)))
                (:effects result)))
      (is (some #(and (= :cancel-timer (:effect %))
                      (= "call-retry" (:node-id %)))
                (:effects result)))
      (is (some #(and (= :release-resource (:effect %))
                      (= "action-waiting" (:node-id %)))
                (:effects result)))
      (is (not-any? #(and (= :abort-resource (:effect %))
                          (= "action-applying" (:node-id %)))
                    (:effects result))))))

(deftest cancellation-tree-is-breadth-first
  (let [tree (cancel/cancel-tree (sample-state) "run-1")
        index (zipmap (map :node-id tree) (range))]
    (is (= "run-1" (:node-id (first tree))))
    (is (every? (fn [{:keys [node-id parent-id]}]
                  (or (nil? parent-id)
                      (< (get index parent-id) (get index node-id))))
                tree))
    (is (= ["run-1" "call-running" "call-ready" "call-retry"
            "call-succeeded" "call-unknown" "action-applying"
            "action-running" "action-board" "action-waiting"
            "action-uncertain" "action-finished" "attempt-running"
            "process-running" "process-uncertain"]
           (map :node-id tree)))))

(deftest programmatic-suspends-are-not-cancellations
  (let [state (sample-state)]
    (doseq [origin [:park :ask :relay-retirement :slot-reincarnation]]
      (let [result (cancel/accept-operator-cancel
                    state
                    {:command-id (str "command-" (name origin))
                     :origin origin})]
        (is (false? (:ok? result)))
        (is (= :NOT_A_CANCELLATION (get-in result [:error :code])))
        (is (= state (:state result)))))
    (testing "an operator origin cannot carry an internal reason"
      (let [result (cancel/cancel-subtree
                    state
                    {:root-id "call-ready"
                     :reason :sibling-accepted
                     :origin :operator-esc})]
        (is (false? (:ok? result)))
        (is (= :INVALID_CANCEL_REASON (get-in result [:error :code])))
        (is (= state (:state result)))))
    (testing "only a real operator flag may use :user-cancel"
      (let [result (cancel/cancel-subtree
                    state
                    {:root-id "call-ready"
                     :reason :user-cancel
                     :origin :operator-esc
                     :operator? false})]
        (is (false? (:ok? result)))
        (is (= :NOT_OPERATOR_CANCEL (get-in result [:error :code])))
        (is (= state (:state result)))))))

(deftest operator-cancel-is-idempotent-by-command-id
  (let [first (operator-cancel (sample-state))
        replay (cancel/accept-operator-cancel
                (:state first)
                {:session-id "session-1"
                 :run-id "run-1"
                 :command-id "command-1"
                 :origin :operator-esc
                 :at 1001})
        conflict (cancel/accept-operator-cancel
                  (:state first)
                  {:session-id "session-1"
                   :run-id "run-1"
                   :command-id "command-1"
                   :origin :operator-stop
                   :at 1002})
        missing (cancel/accept-operator-cancel
                 (sample-state)
                 {:origin :operator-esc})]
    (is (true? (:replayed? replay)))
    (is (empty? (:effects replay)))
    (is (= (:receipt first) (:receipt replay)))
    (is (= (:state first) (:state replay)))
    (is (false? (:ok? conflict)))
    (is (= :COMMAND_ID_CONFLICT (get-in conflict [:error :code])))
    (is (= (:state first) (:state conflict)))
    (is (false? (:ok? missing)))
    (is (= :COMMAND_ID_REQUIRED (get-in missing [:error :code])))))

(deftest a-second-command-while-cancelling-is-a-no-op
  (let [first (operator-cancel (sample-state))
        second (cancel/accept-operator-cancel
                (:state first)
                {:command-id "command-2"
                 :origin :operator-stop
                 :at 1001})]
    (is (true? (:ok? second)))
    (is (= :already-cancelling (get-in second [:receipt :status])))
    (is (empty? (:effects second)))
    (is (= :closed (get-in second [:state :nodes "run-1" :admission])))))

(deftest terminal-run-cannot-be-cancelled
  (let [state (cancel/new-state {:session-id "session-1"
                                 :run-id "run-1"
                                 :run-state :succeeded})
        result (cancel/accept-operator-cancel
                state
                {:command-id "command-1"
                 :origin :operator-esc})]
    (is (false? (:ok? result)))
    (is (= :RUN_TERMINAL (get-in result [:error :code])))))

;; ---------------------------------------------------------------------------
;; Deferred applying and closing

(deftest applying-action-finishes-before-cancellation-takes-effect
  (let [state (-> (cancel/new-state {:session-id "session-1"
                                     :run-id "run-1"})
                  (cancel/add-nodes
                   [{:node-id "call-done"
                     :kind :call
                     :parent-id "run-1"
                     :state :succeeded}
                    {:node-id "call-cancelled"
                     :kind :call
                     :parent-id "run-1"
                     :state :ready}
                    {:node-id "action-applying"
                     :kind :action
                     :parent-id "run-1"
                     :state :applying
                     :action-kind :edit
                     :side-effecting? true}
                    {:node-id "process-running"
                     :kind :process
                     :parent-id "action-applying"
                     :state :running
                     :side-effecting? true}]))
        cancelled (operator-cancel state)
        state (:state cancelled)
        active (cancel/active-resources state "run-1")
        blocked (cancel/finish-run state {:at 1001})
        applied (cancel/accept-terminal
                 state
                 {:node-id "action-applying"
                  :outcome :succeeded
                  :result {:status :applied}
                  :at 1002})
        state' (:state applied)
        process-done (cancel/accept-terminal
                      state'
                      {:node-id "process-running"
                       :outcome :cancelled
                       :at 1003})
        finished (cancel/finish-run (:state process-done) {:at 1004})]
    (is (some #{"action-applying"} active))
    (is (false? (:ok? blocked)))
    (is (= :RESOURCES_NOT_QUIESCENT (get-in blocked [:error :code])))
    (is (= :finished (get-in state' [:nodes "action-applying" :state])))
    (is (= :succeeded (get-in state' [:nodes "action-applying" :outcome])))
    (is (true? (get-in state' [:nodes "action-applying"
                              :cancel-applied-after-commit?])))
    (is (true? (:ok? finished)))
    (is (= :cancelled (get-in finished [:state :nodes "run-1" :state])))
    (is (= :partial (get-in finished [:state :nodes "run-1" :outcome])))
    (is (= :partial (get-in finished [:receipt :outcome])))))

(deftest finish-run-only-closes-a-cancelling-run
  (let [running (cancel/new-state {:run-id "run-1"})
        cancelled (assoc-in running [:nodes "run-1" :state] :cancelled)
        not-cancelling (cancel/finish-run running {:at 1})
        already (cancel/finish-run cancelled {:at 2})]
    (is (= :RUN_NOT_CANCELLING (get-in not-cancelling [:error :code])))
    (is (= :already-finished (get-in already [:receipt :status])))
    (is (= :cancelled (get-in already [:state :nodes "run-1" :state])))))

;; ---------------------------------------------------------------------------
;; Late results and unknown outcomes

(deftest late-results-are-diagnostic-and-unknown-outcomes-stay-unknown
  (let [cancelled (operator-cancel (sample-state))
        state (:state cancelled)
        late (cancel/accept-terminal
              state
              {:node-id "call-running"
               :outcome :succeeded
               :result {:text "late shell output" :exit-code 0}
               :error {:kind :late-diagnostic}
               :at 1001})
        state' (:state late)
        reconciled (cancel/reconcile-unknown
                    state
                    {:node-id "call-running"
                     :outcome :succeeded
                     :evidence "provider receipt 42"
                     :at 1002})
        duplicate (cancel/reconcile-unknown
                   (:state reconciled)
                   {:node-id "call-running"
                    :outcome :succeeded
                    :evidence "provider receipt 42"
                    :at 1003})
        conflict (cancel/reconcile-unknown
                  (:state reconciled)
                  {:node-id "call-running"
                   :outcome :failed
                   :evidence "different evidence"
                   :at 1004})]
    (is (true? (:ok? late)))
    (is (= :late-diagnostic (get-in late [:receipt :status])))
    (is (= :TERMINAL_ALREADY_FIXED (get-in late [:receipt :code])))
    (is (= :outcome-unknown (get-in state' [:nodes "call-running" :state])))
    (is (false? (get-in state' [:nodes "call-running" :resource-alive?])))
    (is (= :succeeded
           (get-in state' [:nodes "call-running" :late-results 0 :outcome])))
    (is (= {:text "late shell output" :exit-code 0}
           (get-in state' [:nodes "call-running" :late-results 0 :payload])))
    (is (= {:kind :late-diagnostic}
           (get-in state' [:nodes "call-running" :late-results 0 :error])))
    (is (= :OUTCOME_UNKNOWN
           (:code (cancel/retry-safety state "call-running"))))
    (is (true? (:ok? reconciled)))
    (is (= :succeeded (get-in reconciled [:state :nodes "call-running" :state])))
    (is (true? (get-in reconciled [:state :nodes "call-running" :reconciled?])))
    (is (= "provider receipt 42"
           (get-in reconciled [:state :nodes "call-running" :evidence 0])))
    (is (= :duplicate (get-in duplicate [:receipt :status])))
    (is (= :ALREADY_RECONCILED (get-in conflict [:error :code])))))

(deftest terminal-outcomes-still-sweep-live-resources
  (let [state (-> (cancel/new-state {:session-id "session-1" :run-id "run-1"})
                  (cancel/add-nodes
                   [{:node-id "call-parked"
                     :kind :call
                     :parent-id "run-1"
                     :state :succeeded
                     :result {:parked? true}
                     :resource-alive? true}
                    {:node-id "call-parked#attempt-1"
                     :kind :attempt
                     :parent-id "call-parked"
                     :state :succeeded
                     :attempt-id "attempt-1"
                     :resource-alive? true}
                    {:node-id "action-parked"
                     :kind :action
                     :parent-id "run-1"
                     :state :finished
                     :outcome :succeeded
                     :action-kind :edit
                     :resource-alive? true}
                    {:node-id "process-parked"
                     :kind :process
                     :parent-id "run-1"
                     :state :exited
                     :outcome :succeeded
                     :resource-alive? true}]))
        result (operator-cancel state)
        state' (:state result)
        call-node (node state' "call-parked")
        attempt-node (node state' "call-parked#attempt-1")
        action-node (node state' "action-parked")
        process-node (node state' "process-parked")
        aborts (filter #(= :abort-resource (:effect %)) (:effects result))
        swept (filter #(true? (:cancel-requested? %))
                      [call-node attempt-node action-node process-node])]
    (is (true? (:ok? result)))
    (is (= 4 (count swept)))
    (is (= :succeeded (:state call-node)))
    (is (= {:parked? true} (:result call-node)))
    (is (= :succeeded (:state attempt-node)))
    (is (= :finished (:state action-node)))
    (is (= :exited (:state process-node)))
    (is (= 4 (count aborts)))
    (is (every? #(not (:dispose? %)) aborts))
    (is (every? #(not (:thread-interrupt? %)) aborts))
    (is (some #(and (= "call-parked" (:call-id %))
                    (= "attempt-1" (:attempt-id %)))
              aborts))
    (testing "live terminal resources block run closure"
      (is (= #{"call-parked" "call-parked#attempt-1"
               "action-parked" "process-parked"}
             (set (cancel/active-resources state' "run-1"))))
      (is (false? (cancel/cancellation-quiescent? state' "run-1")))
      (let [blocked (cancel/finish-run state' {:run-id "run-1" :at 2})]
        (is (false? (:ok? blocked)))
        (is (= :RESOURCES_NOT_QUIESCENT
               (get-in blocked [:error :code])))))
    (testing "a second cancellation does not duplicate the resource sweep"
      (let [again (cancel/cancel-subtree
                   state'
                   {:root-id "call-parked"
                    :reason :parent-terminated
                    :origin :required-step-failure})]
        (is (true? (:ok? again)))
        (is (empty? (:effects again)))))
    (testing "a late terminal observation clears the resource and keeps evidence"
      (let [observed-call (cancel/accept-terminal
                           state'
                           {:node-id "call-parked"
                            :outcome :failed
                            :result {:exit-code 143 :output "late shell"}
                            :error {:kind :late-shell}
                            :at 3})
            state'' (:state observed-call)
            observed-attempt (cancel/accept-terminal
                              state''
                              {:node-id "call-parked#attempt-1"
                               :outcome :succeeded
                               :result {:exit-code 0}
                               :at 4})
            state''' (:state observed-attempt)
            observed-action (cancel/accept-terminal
                             state'''
                             {:node-id "action-parked"
                              :outcome :succeeded
                              :at 5})
            state'''' (:state observed-action)
            observed-process (cancel/accept-terminal
                              state''''
                              {:node-id "process-parked"
                               :outcome :succeeded
                               :at 6})
            finished (cancel/finish-run (:state observed-process)
                                        {:run-id "run-1" :at 7})]
        (is (false? (get-in state'' [:nodes "call-parked"
                                     :resource-alive?])))
        (is (= :succeeded (get-in state'' [:nodes "call-parked" :state])))
        (is (= {:exit-code 143 :output "late shell"}
               (get-in state'' [:nodes "call-parked"
                                :late-results 0 :payload])))
        (is (= {:kind :late-shell}
               (get-in state'' [:nodes "call-parked"
                                :late-results 0 :error])))
        (is (= {:exit-code 0}
               (get-in state''' [:nodes "call-parked#attempt-1"
                                 :late-results 0 :payload])))
        (is (empty? (cancel/active-resources (:state observed-process)
                                             "run-1")))
        (is (true? (:ok? finished)))
        (is (= :cancelled (get-in finished [:state :nodes "run-1" :state])))))))

(deftest reconciliation-needs-a-fixed-unknown-and-evidence
  (let [state (sample-state)
        not-unknown (cancel/reconcile-unknown
                     state
                     {:node-id "call-succeeded"
                      :outcome :succeeded
                      :evidence "x"})
        no-evidence (cancel/reconcile-unknown
                     (:state (operator-cancel state))
                     {:node-id "call-running"
                      :outcome :succeeded})
        invalid-outcome (cancel/reconcile-unknown
                         (:state (operator-cancel state))
                         {:node-id "call-running"
                          :outcome :outcome-unknown
                          :evidence "x"})]
    (is (= :NOT_OUTCOME_UNKNOWN (get-in not-unknown [:error :code])))
    (is (= :EVIDENCE_REQUIRED (get-in no-evidence [:error :code])))
    (is (= :INVALID_RECONCILIATION (get-in invalid-outcome [:error :code])))))

(deftest retry-safety-refuses-cancelled-and-unsafe-side-effects
  (let [cancelled (operator-cancel (sample-state))
        safe-state (cancel/add-node
                    (cancel/new-state {:run-id "run-1"})
                    {:node-id "call-failed"
                     :kind :call
                     :parent-id "run-1"
                     :state :failed
                     :side-effecting? true
                     :safe-to-retry? true
                     :error {:kind :timeout :retryable? true}})
        unsafe-state (cancel/add-node
                      (cancel/new-state {:run-id "run-1"})
                      {:node-id "call-failed"
                       :kind :call
                       :parent-id "run-1"
                       :state :failed
                       :side-effecting? true
                       :error {:kind :timeout :retryable? true}})]
    (is (= :CANCELLED
           (:code (cancel/retry-safety (:state cancelled) "call-ready"))))
    (is (true? (:allowed? (cancel/retry-safety safe-state "call-failed"))))
    (is (= :SIDE_EFFECTS_NOT_SAFE
           (:code (cancel/retry-safety unsafe-state "call-failed"))))))

(deftest late-reports-are-stored-without-restarting
  (let [before (cancel/record-late-report
                (sample-state)
                {:report "late" :at 1})
        cancelled (operator-cancel (sample-state))
        after (cancel/record-late-report
               (:state cancelled)
               {:report "late report" :at 2})]
    (is (false? (:ok? before)))
    (is (= :RUN_NOT_CANCELLED (get-in before [:error :code])))
    (is (true? (:ok? after)))
    (is (false? (get-in after [:receipt :restarted?])))
    (is (= "late report"
           (get-in after [:state :nodes "run-1" :late-reports 0 :report])))
    (is (= :cancelling (get-in after [:state :nodes "run-1" :state])))))

;; ---------------------------------------------------------------------------
;; Independent branch combinators

(deftest independent-failure-changes-one-branch
  (let [state (-> (cancel/new-state {:session-id "session-1" :run-id "run-1"})
                  (cancel/add-nodes
                   [{:node-id "call-a"
                     :kind :call
                     :parent-id "run-1"
                     :state :running
                     :side-effecting? true
                     :safe-to-retry? true
                     :group-id "g"}
                    {:node-id "call-b"
                     :kind :call
                     :parent-id "run-1"
                     :state :running
                     :group-id "g"}
                    {:node-id "call-c"
                     :kind :call
                     :parent-id "run-1"
                     :state :outcome-unknown
                     :group-id "g"}]))
        result (cancel/accept-independent-failure
                state
                {:node-id "call-a"
                 :error {:kind :timeout :retryable? true}
                 :at 1})
        applying-state (cancel/add-node
                        (cancel/new-state {:run-id "run-1"})
                        {:node-id "action-applying"
                         :kind :action
                         :parent-id "run-1"
                         :state :applying
                         :action-kind :edit})
        applying (cancel/accept-independent-failure
                  applying-state
                  {:node-id "action-applying"
                   :error {:kind :timeout :retryable? true}})]
    (is (true? (:ok? result)))
    (is (= :failed (get-in result [:state :nodes "call-a" :state])))
    (is (= :failed (get-in result [:state :nodes "call-a" :outcome])))
    (is (= {:status :failed :error {:kind :timeout :retryable? true}}
           (get-in result [:state :nodes "call-a" :result])))
    (is (= (node state "call-b") (node (:state result) "call-b")))
    (is (= (node state "call-c") (node (:state result) "call-c")))
    (is (empty? (:effects result)))
    (is (true? (:siblings-untouched? (:receipt result))))
    (is (true? (:allowed? (cancel/retry-safety (:state result) "call-a"))))
    (is (= :ACTION_IN_FLIGHT (get-in applying [:error :code])))
    (is (= applying-state (:state applying)))))

(deftest first-accepted-cancels-only-active-siblings
  (let [state (-> (cancel/new-state {:session-id "session-1" :run-id "run-1"})
                  (cancel/add-nodes
                   [{:node-id "call-win"
                     :kind :call
                     :parent-id "run-1"
                     :state :running
                     :group-id "g"}
                    {:node-id "call-ready"
                     :kind :call
                     :parent-id "run-1"
                     :state :ready
                     ;; A pure sibling: nothing was dispatched, so losing
                     ;; the race cancels it immediately.
                     :side-effecting? false
                     :group-id "g"}
                    {:node-id "call-lose"
                     :kind :call
                     :parent-id "run-1"
                     :state :running
                     :side-effecting? true
                     :group-id "g"}
                    {:node-id "call-unknown"
                     :kind :call
                     :parent-id "run-1"
                     :state :outcome-unknown
                     :group-id "g"}
                    {:node-id "call-done"
                     :kind :call
                     :parent-id "run-1"
                     :state :succeeded
                     :group-id "g"}]))
        result (cancel/accept-first-accepted
                state
                {:group-id "g"
                 :winner-id "call-win"
                 :outcome :succeeded
                 :at 1})
        state' (:state result)
        unknown-winner (cancel/accept-first-accepted
                        state
                        {:group-id "g"
                         :winner-id "call-unknown"
                         :outcome :succeeded})
        missing-winner (cancel/accept-first-accepted
                        state
                        {:group-id "g"
                         :winner-id "call-missing"
                         :outcome :succeeded})]
    (is (true? (:ok? result)))
    (is (= :succeeded (get-in state' [:nodes "call-win" :state])))
    (is (= :cancelled (get-in state' [:nodes "call-ready" :state])))
    (is (= :sibling-accepted
           (get-in state' [:nodes "call-ready" :cancel-reason])))
    (is (= :outcome-unknown (get-in state' [:nodes "call-lose" :state])))
    (is (= :sibling-accepted
           (get-in state' [:nodes "call-lose" :cancel-reason])))
    (is (= (node state "call-unknown") (node state' "call-unknown")))
    (is (= (node state "call-done") (node state' "call-done")))
    (is (= :first-accepted (get-in result [:receipt :status])))
    (is (= :succeeded (get-in result [:receipt :winner-outcome])))
    (is (every? #(not (:dispose? %)) (:effects result)))
    (is (= :WINNER_OUTCOME_UNKNOWN (get-in unknown-winner [:error :code])))
    (is (= :WINNER_NOT_IN_GROUP (get-in missing-winner [:error :code])))))

(deftest required-step-failure-cancels-the-group
  (let [state (-> (cancel/new-state {:session-id "session-1" :run-id "run-1"})
                  (cancel/add-nodes
                   [{:node-id "call-fail"
                     :kind :call
                     :parent-id "run-1"
                     :state :ready
                     ;; Pure, undispatched calls: they own no external
                     ;; resource, so a cancel ends them at once.  The
                     ;; `default-node` fallback is `true` for real calls.
                     :side-effecting? false
                     :group-id "g"}
                    {:node-id "call-other"
                     :kind :call
                     :parent-id "run-1"
                     :state :ready
                     :side-effecting? false
                     :group-id "g"}
                    {:node-id "call-unknown"
                     :kind :call
                     :parent-id "run-1"
                     :state :outcome-unknown
                     :group-id "g"}]))
        result (cancel/accept-required-step-failure
                state
                {:group-id "g"
                 :failed-id "call-fail"
                 :error {:kind :bad-input}
                 :at 1})
        state' (:state result)
        unknown-failure (cancel/accept-required-step-failure
                         state
                         {:group-id "g"
                          :failed-id "call-unknown"
                          :error {:kind :x}})
        succeeded-failure (cancel/accept-required-step-failure
                           (cancel/add-node
                            state
                            {:node-id "call-done"
                             :kind :call
                             :parent-id "run-1"
                             :state :succeeded
                             :group-id "g"})
                           {:group-id "g"
                            :failed-id "call-done"
                            :error {:kind :x}})]
    (is (true? (:ok? result)))
    (is (= :failed (get-in state' [:nodes "call-fail" :state])))
    (is (= :cancelled (get-in state' [:nodes "call-other" :state])))
    (is (= :parent-terminated
           (get-in state' [:nodes "call-other" :cancel-reason])))
    (is (= (node state "call-unknown") (node state' "call-unknown")))
    (is (= :required-step-failure (get-in result [:receipt :status])))
    (is (= :failed (get-in result [:receipt :failed-outcome])))
    (is (= :FAILED_OUTCOME_UNKNOWN (get-in unknown-failure [:error :code])))
    (is (= :FAILED_STEP_NOT_FAILED (get-in succeeded-failure [:error :code])))))

;; ---------------------------------------------------------------------------
;; Other reasons and run isolation

(deftest deadline-and-executor-shutdown-close-admission
  (let [state (sample-state)
        deadline (cancel/cancel-due-deadline
                  state
                  {:root-id "run-1" :command-id "deadline-1" :at 1})
        shutdown (cancel/cancel-due-executor-shutdown
                  state
                  {:command-id "shutdown-1" :at 2})]
    (is (true? (:ok? deadline)))
    (is (= :deadline-exceeded
           (get-in deadline [:state :nodes "run-1" :cancellation :reason])))
    (is (= :closed (get-in deadline [:state :nodes "run-1" :admission])))
    (is (= :deadline-exceeded
           (get-in deadline [:state :nodes "call-running" :cancel-root-reason])))
    (is (true? (:ok? shutdown)))
    (is (= :executor-shutdown
           (get-in shutdown [:state :nodes "run-1" :cancellation :reason])))
    (is (= :closed (get-in shutdown [:state :nodes "run-1" :admission])))))

(deftest cancellation-of-one-run-does-not-touch-another
  (let [state (-> (cancel/new-state {:session-id "session-1" :run-id "run-1"})
                  (cancel/add-nodes
                   [{:node-id "call-1"
                     :kind :call
                     :parent-id "run-1"
                     :state :running
                     :side-effecting? true}
                    {:node-id "run-2"
                     :kind :run
                     :parent-id nil
                     :state :running
                     :session-id "session-1"}
                    {:node-id "call-2"
                     :kind :call
                     :parent-id "run-2"
                     :state :running
                     :side-effecting? true}]))
        result (cancel/accept-operator-cancel
                state
                {:run-id "run-1"
                 :command-id "command-1"
                 :origin :operator-esc
                 :at 1})]
    (is (true? (:ok? result)))
    (is (= (node state "run-2") (node (:state result) "run-2")))
    (is (= (node state "call-2") (node (:state result) "call-2")))
    (is (not (some #{"call-2"} (map :node-id (cancel/cancel-tree (:state result) "run-1")))))))

(deftest tree-validation-rejects-illegal-wiring
  (let [state (cancel/new-state {:session-id "session-1" :run-id "run-1"})]
    (is (thrown? clojure.lang.ExceptionInfo
                 (cancel/add-node state
                                 {:node-id "attempt-1"
                                  :kind :attempt
                                  :parent-id "run-1"
                                  :state :running})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (cancel/add-node state
                                 {:node-id "call-1"
                                  :kind :call
                                  :parent-id "run-1"
                                  :state :unknown})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (cancel/add-node state
                                 {:node-id "action-1"
                                  :kind :action
                                  :parent-id "run-1"
                                  :state :running
                                  :action-kind :teleport})))
    (let [with-call (cancel/add-node state
                                    {:node-id "call-1"
                                     :kind :call
                                     :parent-id "run-1"
                                     :state :ready})]
      (is (thrown? clojure.lang.ExceptionInfo
                   (cancel/add-node with-call
                                   {:node-id "call-1"
                                    :kind :call
                                    :parent-id "run-1"
                                    :state :ready})))
      (is (empty? (model/validate-state with-call))))
    (testing "validation returns problems for malformed state instead of throwing"
      (is (seq (model/validate-state
                {:cancel-version 1
                 :session-id "session-1"
                 :active-run-id "run-1"
                 :nodes {"run-1" {:node-id "run-1"
                                  :kind :run
                                  :parent-id nil
                                  :state :running}}
                 :receipts {}}))))))

;; ---------------------------------------------------------------------------
;; Property generators

(def call-states
  [:ready :dispatching :running :retry-wait
   :succeeded :failed :cancelled :outcome-unknown])

(def attempt-states
  [:dispatching :running :succeeded :failed :cancelled :outcome-unknown])

(def action-states
  [:claimed :preparing :waiting-gate :applying :running
   :finished :uncertain])

(def process-states
  [:starting :running :stopping :exited :uncertain])

(def gen-call-spec
  (gen/let [state (gen/elements call-states)
            side-effecting? gen/boolean]
    {:state state :side-effecting? side-effecting?}))

(def gen-attempt-spec
  (gen/let [state (gen/elements attempt-states)
            side-effecting? gen/boolean]
    {:state state :side-effecting? side-effecting?}))

(def gen-action-spec
  (gen/let [state (gen/elements action-states)
            action-kind (gen/elements [:board :edit :shell])]
    (cond-> {:state state :action-kind action-kind}
      (= :finished state) (assoc :outcome :succeeded)
      (= :uncertain state) (assoc :outcome :outcome-unknown))))

(def gen-process-spec
  (gen/let [state (gen/elements process-states)
            side-effecting? gen/boolean]
    (cond-> {:state state :side-effecting? side-effecting?}
      (= :exited state) (assoc :outcome :succeeded)
      (= :uncertain state) (assoc :outcome :outcome-unknown))))

(def gen-tree
  (gen/let [calls (gen/vector (gen/tuple gen-call-spec gen-attempt-spec) 0 4)
            actions (gen/vector (gen/tuple gen-action-spec gen-process-spec) 0 3)]
    (let [call-nodes
          (mapv (fn [i [call attempt]]
                  (let [call-id (str "call-" i)
                        attempt-id (str "attempt-" i)]
                    (list
                     (assoc call
                            :node-id call-id
                            :kind :call
                            :parent-id "run-1"
                            :current-attempt-id attempt-id
                            :group-id "g")
                     (assoc attempt
                            :node-id attempt-id
                            :kind :attempt
                            :parent-id call-id))))
                (range)
                calls)
          action-nodes
          (mapv (fn [i [action process]]
                  (let [action-id (str "action-" i)]
                    (list
                     (assoc action
                            :node-id action-id
                            :kind :action
                            :parent-id "run-1")
                     (assoc process
                            :node-id (str "process-" i)
                            :kind :process
                            :parent-id action-id))))
                (range)
                actions)]
      (vec (concat (mapcat identity call-nodes)
                   (mapcat identity action-nodes))))))

(defn- state-of
  [specs]
  (cancel/add-nodes
   (cancel/new-state {:session-id "session-1" :run-id "run-1"})
   specs))

(def ^:private forbidden-live-states
  #{:ready :dispatching :running :retry-wait
    :claimed :preparing :waiting-gate :starting})

(defspec operator-cancel-preserves-fixed-outcomes 60
  (prop/for-all [specs gen-tree]
    (let [state (state-of specs)
          result (operator-cancel state)
          state' (:state result)
          receipt (:receipt result)
          visited (set (concat (:cancelled receipt)
                               (:outcome-unknown receipt)
                               (:deferred receipt)
                               (:preserved receipt)))]
      (and (true? (:ok? result))
           (empty? (model/validate-state state'))
           (= :cancelling (get-in state' [:nodes "run-1" :state]))
           (= :closed (get-in state' [:nodes "run-1" :admission]))
           (= (set (keys (:nodes state))) visited)
           (every? (fn [[id before]]
                     (let [after (get-in state' [:nodes id])]
                       (cond
                         (= id "run-1") true
                         (model/terminal? before)
                         (if (true? (:resource-alive? before))
                           (and (= (:state before) (:state after))
                                (= (:outcome before) (:outcome after))
                                (= (:result before) (:result after))
                                (= (:evidence before) (:evidence after)))
                           (= before after))
                         (model/uncertain? before)
                         (and (= (:state before) (:state after))
                              (= (:outcome before) (:outcome after)))
                         :else true)))
                   (:nodes state))
           (every? (fn [effect]
                     (and (not (:dispose? effect))
                          (not (:thread-interrupt? effect))))
                   (:effects result))
           (let [replay (cancel/accept-operator-cancel
                         state'
                         {:session-id "session-1"
                          :run-id "run-1"
                          :command-id "command-1"
                          :origin :operator-esc
                          :at 1001})]
             (and (true? (:replayed? replay))
                  (empty? (:effects replay))
                  (= state' (:state replay))))))))

(defspec independent-failure-changes-only-the-failed-node 60
  (prop/for-all [specs gen-tree
                 index gen/nat]
    (let [state (state-of specs)
          call-ids (->> specs
                        (filter #(= :call (:kind %)))
                        (map :node-id)
                        vec)]
      (if (empty? call-ids)
        true
        (let [node-id (nth call-ids (mod index (count call-ids)))
              before-node (node state node-id)
              result (cancel/accept-independent-failure
                      state
                      {:node-id node-id
                       :error {:kind :timeout :retryable? false}
                       :at 1})
              state' (:state result)
              after-node (node state' node-id)]
          (and (true? (:ok? result))
               (empty? (:effects result))
               (if (or (model/terminal? before-node)
                       (model/uncertain-outcome? before-node))
                 (and (= :preserved (get-in result [:receipt :status]))
                      (= before-node after-node))
                 (and (= :failed (:state after-node))
                      (= :failed (:outcome after-node))))
               (every? (fn [[id before]]
                         (or (= id node-id)
                             (= before (node state' id))))
                       (:nodes state))))))))

(defspec first-accepted-preserves-fixed-and-unknown-siblings 60
  (prop/for-all [specs gen-tree
                 index gen/nat]
    (let [state (state-of specs)
          group-ids (->> specs
                         (filter #(= "g" (:group-id %)))
                         (map :node-id)
                         vec)]
      (if (empty? group-ids)
        true
        (let [winner-id (nth group-ids (mod index (count group-ids)))
              winner (node state winner-id)
              result (cancel/accept-first-accepted
                      state
                      {:group-id "g"
                       :winner-id winner-id
                       :outcome :succeeded
                       :at 1})]
          (if (model/uncertain-outcome? winner)
            (= :WINNER_OUTCOME_UNKNOWN (get-in result [:error :code]))
            (let [state' (:state result)
                  expected-winner-state (if (model/terminal? winner)
                                          (:state winner)
                                          :succeeded)]
              (and (true? (:ok? result))
                   (= expected-winner-state
                      (get-in state' [:nodes winner-id :state]))
                   (every? (fn [[id before]]
                             (let [after (get-in state' [:nodes id])]
                               (cond
                                 (= id winner-id) true
                                 (= :attempt (:kind before)) true
                                 (not= "g" (:group-id before)) (= before after)
                                 (model/terminal? before) (= before after)
                                 (model/uncertain? before)
                                 (and (= (:state before) (:state after))
                                      (= (:outcome before) (:outcome after)))
                                 :else
                                 (not (contains? forbidden-live-states
                                                 (:state after))))))
                           (:nodes state))
                   (every? #(not (:dispose? %)) (:effects result))))))))))

(defspec cancel-tree-orders-parents-before-children 40
  (prop/for-all [specs gen-tree]
    (let [state (state-of specs)
          tree (cancel/cancel-tree state "run-1")
          index (zipmap (map :node-id tree) (range))]
      (and (= "run-1" (:node-id (first tree)))
           (every? (fn [{:keys [node-id parent-id]}]
                     (or (nil? parent-id)
                         (< (get index parent-id) (get index node-id))))
                   tree)))))

(deftest a-cancelled-live-call-keeps-its-resource-until-a-settlement
  ;; The two halves of the same rule: the stop signal asks the executor to
  ;; stop, and only a settlement observation (succeeded/failed/cancelled)
  ;; proves the resource ended.  An `:outcome-unknown` observation reports
  ;; that no executor result exists, so it must leave the claim in place.
  (let [result (operator-cancel (sample-state))
        cancelled (:state result)
        call (get-in cancelled [:nodes "call-running"])]
    (testing "a signalled live call keeps its resource and receives an abort"
      (is (true? (:ok? result)))
      (is (= :outcome-unknown (:state call)))
      (is (true? (:resource-alive? call)))
      (is (some #{"call-running"} (cancel/active-resources cancelled "run-1")))
      (is (some (fn [effect]
                  (and (= :abort-resource (:effect effect))
                       (= "call-running" (:call-id effect))))
                (:effects result)))
      (is (not (cancel/cancellation-quiescent? cancelled "run-1"))
          "a live resource must keep finish-run refusing"))
    (testing "an unknown observation never clears the claim"
      (let [observed (:state (cancel/accept-terminal
                              cancelled
                              {:node-id "call-running"
                               :outcome :outcome-unknown
                               :error {:kind :cancelled}
                               :at 2000}))]
        (is (= :outcome-unknown (get-in observed [:nodes "call-running"
                                                  :state])))
        (is (true? (get-in observed [:nodes "call-running"
                                     :resource-alive?])))
        (is (some #{"call-running"}
                  (cancel/active-resources observed "run-1")))))
    (testing "a real executor terminal ends it and keeps the unknown as evidence"
      (let [ended (:state (cancel/accept-terminal
                           cancelled
                           {:node-id "call-running"
                            :outcome :cancelled
                            :at 3000}))]
        (is (false? (get-in ended [:nodes "call-running" :resource-alive?])))
        (is (= :outcome-unknown
               (get-in ended [:nodes "call-running" :state]))
            "the fixed unknown is evidence, not a state to rewrite")
        (is (= :late-terminal
               (get-in ended [:nodes "call-running" :late-results 0 :kind])))
        (is (not (some #{"call-running"}
                       (cancel/active-resources ended "run-1"))))))))

(deftest fixed-unknown-action-and-process-are-not-rewritten
  (let [state (-> (cancel/new-state {:session-id "session-1" :run-id "run-1"})
                  (cancel/add-nodes
                   [{:node-id "action-uncertain"
                     :kind :action
                     :parent-id "run-1"
                     :state :uncertain
                     :action-kind :shell
                     :side-effecting? true
                     :outcome :outcome-unknown
                     :resource-alive? true}
                    {:node-id "process-uncertain"
                     :kind :process
                     :parent-id "action-uncertain"
                     :state :uncertain
                     :side-effecting? true
                     :outcome :outcome-unknown
                     :resource-alive? true}
                    {:node-id "process-stopping"
                     :kind :process
                     :parent-id "run-1"
                     :state :stopping
                     :side-effecting? true
                     :outcome :outcome-unknown
                     :resource-alive? true}]))
        action (cancel/accept-terminal
                state
                {:node-id "action-uncertain"
                 :outcome :succeeded
                 :result {:applied true}
                 :at 1})
        process (cancel/accept-terminal
                 state
                 {:node-id "process-uncertain"
                  :outcome :failed
                  :error {:kind :late}
                  :at 2})
        provisional (cancel/accept-terminal
                     state
                     {:node-id "process-stopping"
                      :outcome :cancelled
                      :at 3})]
    (testing "a fixed unknown action keeps its state and outcome"
      (is (= :late-diagnostic (get-in action [:receipt :status])))
      (is (= :OUTCOME_UNKNOWN_PRESERVED (get-in action [:receipt :code])))
      (is (= :uncertain
             (get-in action [:state :nodes "action-uncertain" :state])))
      (is (= :outcome-unknown
             (get-in action [:state :nodes "action-uncertain" :outcome])))
      (is (false? (get-in action [:state :nodes "action-uncertain"
                                  :resource-alive?])))
      (is (= {:applied true}
             (get-in action [:state :nodes "action-uncertain"
                             :late-results 0 :payload])))
      (is (= :OUTCOME_UNKNOWN
             (get-in (cancel/retry-safety (:state action)
                                          "action-uncertain")
                     [:code]))))
    (testing "a fixed unknown process keeps its state and outcome"
      (is (= :late-diagnostic (get-in process [:receipt :status])))
      (is (= :OUTCOME_UNKNOWN_PRESERVED (get-in process [:receipt :code])))
      (is (= :uncertain
             (get-in process [:state :nodes "process-uncertain" :state])))
      (is (= :outcome-unknown
             (get-in process [:state :nodes "process-uncertain" :outcome])))
      (is (false? (get-in process [:state :nodes "process-uncertain"
                                   :resource-alive?]))))
    (testing "a provisional stopping unknown accepts a real settlement"
      (is (= :accepted (get-in provisional [:receipt :status])))
      (is (= :exited
             (get-in provisional [:state :nodes "process-stopping" :state])))
      (is (= :cancelled
             (get-in provisional [:state :nodes "process-stopping" :outcome])))
      (is (false? (get-in provisional [:state :nodes "process-stopping"
                                       :resource-alive?]))))))

(deftest first-accepted-and-required-step-sweep-live-siblings
  (let [state (-> (cancel/new-state {:session-id "session-1" :run-id "run-1"})
                  (cancel/add-nodes
                   [{:node-id "winner"
                     :kind :call
                     :parent-id "run-1"
                     :state :running
                     :side-effecting? true
                     :group-id "g"}
                    {:node-id "done-live"
                     :kind :call
                     :parent-id "run-1"
                     :state :succeeded
                     :side-effecting? true
                     :resource-alive? true
                     :group-id "g"}
                    {:node-id "unknown-live"
                     :kind :call
                     :parent-id "run-1"
                     :state :outcome-unknown
                     :side-effecting? true
                     :resource-alive? true
                     :group-id "g"}
                    {:node-id "uncertain-action"
                     :kind :action
                     :parent-id "run-1"
                     :state :uncertain
                     :action-kind :shell
                     :side-effecting? true
                     :outcome :outcome-unknown
                     :resource-alive? true
                     :group-id "g"}]))
        first (cancel/accept-first-accepted
               state
               {:group-id "g" :winner-id "winner" :outcome :succeeded :at 1})
        state' (:state first)
        aborts (filter #(= :abort-resource (:effect %)) (:effects first))]
    (is (true? (:ok? first)))
    (is (= :succeeded (get-in state' [:nodes "winner" :state])))
    (is (= :succeeded (get-in state' [:nodes "done-live" :state])))
    (is (true? (get-in state' [:nodes "done-live" :cancel-requested?])))
    (is (= :outcome-unknown (get-in state' [:nodes "unknown-live" :state])))
    (is (true? (get-in state' [:nodes "unknown-live" :cancel-requested?])))
    (is (= :uncertain (get-in state' [:nodes "uncertain-action" :state])))
    (is (true? (get-in state' [:nodes "uncertain-action" :cancel-requested?])))
    (is (= #{"done-live" "unknown-live" "uncertain-action"}
           (set (map :node-id aborts))))
    (is (every? #(true? (get-in state' [:nodes (:node-id %)
                                                :resource-alive?]))
                aborts))
    (is (= #{"done-live" "unknown-live" "uncertain-action"}
           (set (cancel/active-resources state' "run-1"))))
    (is (false? (:ok? (cancel/finish-run state' {:run-id "run-1"})))))
  (let [state (-> (cancel/new-state {:session-id "session-1" :run-id "run-1"})
                  (cancel/add-nodes
                   [{:node-id "failed"
                     :kind :call
                     :parent-id "run-1"
                     :state :ready
                     :side-effecting? false
                     :group-id "g"}
                    {:node-id "done-live"
                     :kind :call
                     :parent-id "run-1"
                     :state :succeeded
                     :side-effecting? true
                     :resource-alive? true
                     :group-id "g"}
                    {:node-id "unknown-live"
                     :kind :call
                     :parent-id "run-1"
                     :state :outcome-unknown
                     :side-effecting? true
                     :resource-alive? true
                     :group-id "g"}]))
        result (cancel/accept-required-step-failure
                state
                {:group-id "g" :failed-id "failed"
                 :error {:kind :bad-input} :at 2})
        state' (:state result)
        aborts (filter #(= :abort-resource (:effect %)) (:effects result))]
    (is (true? (:ok? result)))
    (is (= :failed (get-in state' [:nodes "failed" :state])))
    (is (= #{"done-live" "unknown-live"}
           (set (map :node-id aborts))))
    (is (= #{"done-live" "unknown-live"}
           (set (cancel/active-resources state' "run-1"))))
    (is (false? (:ok? (cancel/finish-run state' {:run-id "run-1"}))))))

(deftest required-step-failure-settles-the-failed-branch
  (let [base (cancel/new-state {:session-id "session-1" :run-id "run-1"})
        retry-state (-> base
                        (cancel/add-node
                         {:node-id "failed"
                          :kind :call
                          :parent-id "run-1"
                          :state :retry-wait
                          :side-effecting? true
                          :resource-alive? true
                          :group-id "g"})
                        (cancel/add-node
                         {:node-id "other"
                          :kind :call
                          :parent-id "run-1"
                          :state :ready
                          :side-effecting? false
                          :group-id "g"}))
        retry (cancel/accept-required-step-failure
               retry-state
               {:group-id "g" :failed-id "failed"
                :error {:kind :timeout} :at 1})
        retry-node (get-in retry [:state :nodes "failed"])]
    (is (true? (:ok? retry)))
    (is (= :failed (:state retry-node)))
    (is (false? (:resource-alive? retry-node)))
    (is (some #(and (= :cancel-timer (:effect %))
                    (= "failed" (:node-id %)))
              (:effects retry))))
  (let [base (cancel/new-state {:session-id "session-1" :run-id "run-1"})
        gate-state (-> base
                       (cancel/add-node
                        {:node-id "failed"
                         :kind :action
                         :parent-id "run-1"
                         :state :waiting-gate
                         :action-kind :edit
                         :side-effecting? true
                         :resource-alive? true
                         :group-id "g"}))
        gate (cancel/accept-required-step-failure
              gate-state
              {:group-id "g" :failed-id "failed"
               :error {:kind :bad-input} :at 2})
        gate-node (get-in gate [:state :nodes "failed"])]
    (is (true? (:ok? gate)))
    (is (= :finished (:state gate-node)))
    (is (true? (:resource-alive? gate-node)))
    (is (some #(and (= :release-resource (:effect %))
                    (= :write-gate (:scope %)))
              (:effects gate)))
    (is (some #(= :abort-resource (:effect %)) (:effects gate))))
  (let [base (cancel/new-state {:session-id "session-1" :run-id "run-1"})
        running-state (-> base
                          (cancel/add-node
                           {:node-id "failed"
                            :kind :call
                            :parent-id "run-1"
                            :state :running
                            :side-effecting? true
                            :resource-alive? true
                            :group-id "g"}))
        running (cancel/accept-required-step-failure
                 running-state
                 {:group-id "g" :failed-id "failed"
                  :error {:kind :timeout} :at 3})
        running-node (get-in running [:state :nodes "failed"])
        cancelling (-> (:state running)
                       (assoc-in [:nodes "run-1" :state] :cancelling)
                       (assoc-in [:nodes "run-1" :admission] :closed))
        blocked (cancel/finish-run cancelling {:run-id "run-1"})]
    (is (true? (:ok? running)))
    (is (= :failed (:state running-node)))
    (is (true? (:resource-alive? running-node)))
    (is (some #(and (= :abort-resource (:effect %))
                    (= "failed" (:node-id %)))
              (:effects running)))
    (is (false? (:ok? blocked)))
    (is (= :RESOURCES_NOT_QUIESCENT (get-in blocked [:error :code])))
    (let [ended (cancel/accept-terminal
                 cancelling
                 {:node-id "failed" :outcome :failed :at 4})]
      (is (false? (get-in ended [:state :nodes "failed"
                                 :resource-alive?])))
      (is (true? (:ok? (cancel/finish-run (:state ended)
                                         {:run-id "run-1"})))))))

(deftest stale-attempt-terminals-are-diagnostic
  (let [state (-> (cancel/new-state {:session-id "session-1" :run-id "run-1"})
                  (cancel/add-nodes
                   [{:node-id "call"
                     :kind :call
                     :parent-id "run-1"
                     :state :running
                     :side-effecting? true
                     :current-attempt-id "a2"}
                    {:node-id "call-no-current"
                     :kind :call
                     :parent-id "run-1"
                     :state :running
                     :side-effecting? true}
                    {:node-id "attempt-a1"
                     :kind :attempt
                     :parent-id "call"
                     :state :running
                     :attempt-id "a1"
                     :side-effecting? true}]))
        stale-call (cancel/accept-terminal
                    state
                    {:node-id "call" :attempt-id "a1"
                     :outcome :succeeded :at 1})
        stale-nil (cancel/accept-terminal
                   state
                   {:node-id "call-no-current" :attempt-id "a1"
                    :outcome :succeeded :at 2})
        stale-attempt (cancel/accept-terminal
                       state
                       {:node-id "attempt-a1" :attempt-id "a0"
                        :outcome :succeeded :at 3})
        matched (cancel/accept-terminal
                 state
                 {:node-id "call" :attempt-id "a2"
                  :outcome :succeeded :at 4})]
    (is (= :STALE_ATTEMPT (get-in stale-call [:receipt :code])))
    (is (= :running (get-in stale-call [:state :nodes "call" :state])))
    (is (= :stale-attempt
           (get-in stale-call [:state :nodes "call"
                               :late-results 0 :kind])))
    (is (= :STALE_ATTEMPT (get-in stale-nil [:receipt :code])))
    (is (= :running
           (get-in stale-nil [:state :nodes "call-no-current" :state])))
    (is (= :STALE_ATTEMPT (get-in stale-attempt [:receipt :code])))
    (is (= :running
           (get-in stale-attempt [:state :nodes "attempt-a1" :state])))
    (is (= :accepted (get-in matched [:receipt :status])))
    (is (= :succeeded (get-in matched [:state :nodes "call" :state])))))

(deftest cancellation-preserves-live-flags-on-non-execution-states
  (let [state (-> (cancel/new-state {:session-id "session-1" :run-id "run-1"})
                  (cancel/add-nodes
                   [{:node-id "ready-live"
                     :kind :call
                     :parent-id "run-1"
                     :state :ready
                     :side-effecting? true
                     :resource-alive? true}
                    {:node-id "retry-live"
                     :kind :call
                     :parent-id "run-1"
                     :state :retry-wait
                     :side-effecting? true
                     :resource-alive? true}
                    {:node-id "claimed-live"
                     :kind :action
                     :parent-id "run-1"
                     :state :claimed
                     :action-kind :edit
                     :side-effecting? true
                     :resource-alive? true}
                    {:node-id "waiting-live"
                     :kind :action
                     :parent-id "run-1"
                     :state :waiting-gate
                     :action-kind :edit
                     :side-effecting? true
                     :resource-alive? true}]))
        result (operator-cancel state)
        state' (:state result)
        aborts (filter #(= :abort-resource (:effect %)) (:effects result))
        timers (filter #(= :cancel-timer (:effect %)) (:effects result))
        releases (filter #(= :release-resource (:effect %)) (:effects result))]
    (is (true? (:ok? result)))
    (doseq [id ["ready-live" "retry-live" "claimed-live" "waiting-live"]]
      (is (true? (get-in state' [:nodes id :resource-alive?])))
      (is (true? (get-in state' [:nodes id :cancel-requested?]))))
    (is (= #{"ready-live" "retry-live" "claimed-live" "waiting-live"}
           (set (map :node-id aborts))))
    (is (= #{"retry-live"} (set (map :node-id timers))))
    (is (= #{"waiting-live"} (set (map :node-id releases))))
    (is (false? (:ok? (cancel/finish-run state' {:run-id "run-1"}))))))

(deftest abort-effects-carry-routing-fields
  (let [state (-> (cancel/new-state {:session-id "session-1" :run-id "run-1"})
                  (cancel/add-nodes
                   [{:node-id "call-shell"
                     :kind :call
                     :parent-id "run-1"
                     :state :running
                     :side-effecting? true
                     :resource-alive? true
                     :executor-kind :shell}
                    {:node-id "action-shell"
                     :kind :action
                     :parent-id "run-1"
                     :state :running
                     :action-kind :shell
                     :side-effecting? true
                     :resource-alive? true
                     :executor-kind :shell}
                    {:node-id "process-1"
                     :kind :process
                     :parent-id "run-1"
                     :state :running
                     :side-effecting? true
                     :resource-alive? true
                     :process-id "process-1"
                     :executor-kind :shell}]))
        result (operator-cancel state)
        aborts (filter #(= :abort-resource (:effect %)) (:effects result))
        by-id (into {} (map (juxt :node-id identity) aborts))]
    (is (true? (:ok? result)))
    (is (= :call (get-in by-id ["call-shell" :node-kind])))
    (is (= :call (get-in by-id ["call-shell" :kind])))
    (is (= :shell (get-in by-id ["call-shell" :executor-kind])))
    (is (= "call-shell" (get-in by-id ["call-shell" :call-id])))
    (is (= :action (get-in by-id ["action-shell" :node-kind])))
    (is (= "action-shell" (get-in by-id ["action-shell" :resource-id])))
    (is (nil? (get-in by-id ["action-shell" :call-id])))
    (is (= :process (get-in by-id ["process-1" :node-kind])))
    (is (= "process-1" (get-in by-id ["process-1" :resource-id])))
    (is (= "process-1" (get-in by-id ["process-1" :process-id])))))

(deftest late-node-cancel-rejects-a-node-from-another-run
  (let [base (-> (cancel/new-state {:session-id "session-1" :run-id "run-1"})
                 (cancel/add-node
                  {:node-id "run-2"
                   :kind :run
                   :parent-id nil
                   :state :running
                   :session-id "session-1"})
                 (cancel/add-node
                  {:node-id "call-2"
                   :kind :call
                   :parent-id "run-2"
                   :state :running
                   :side-effecting? true}))
        cancelling (:state
                    (cancel/accept-operator-cancel
                     base
                     {:run-id "run-1"
                      :command-id "command-1"
                      :origin :operator-esc
                      :at 1}))
        result (cancel/cancel-run-late-nodes
                cancelling
                {:run-id "run-1"
                 :node-id "call-2"
                 :reason :user-cancel
                 :origin :operator-esc
                 :at 2})]
    (is (false? (:ok? result)))
    (is (= :NODE_NOT_UNDER_RUN (get-in result [:error :code])))
    (is (= cancelling (:state result)))
    (is (= :running (get-in result [:state :nodes "call-2" :state])))))

(deftest cancel-roots-reject-overlap-and-operator-non-run-roots
  (let [state (-> (cancel/new-state {:session-id "session-1" :run-id "run-1"})
                  (cancel/add-node
                   {:node-id "call-1"
                    :kind :call
                    :parent-id "run-1"
                    :state :running
                    :side-effecting? true})
                  (cancel/add-node
                   {:node-id "attempt-1"
                    :kind :attempt
                    :parent-id "call-1"
                    :state :running
                    :side-effecting? true}))
        overlap (cancel/cancel-subtrees
                 state
                 {:root-ids ["call-1" "attempt-1"]
                  :reason :parent-terminated
                  :origin :required-step-failure
                  :at 1})
        operator-call (cancel/cancel-subtree
                       state
                       {:root-id "call-1"
                        :reason :user-cancel
                        :origin :operator-esc
                        :operator? true
                        :at 2})
        operator-run (cancel/cancel-subtree
                      state
                      {:root-id "run-1"
                       :reason :user-cancel
                       :origin :operator-esc
                       :operator? true
                       :at 3})]
    (is (= :OVERLAPPING_CANCEL_ROOTS (get-in overlap [:error :code])))
    (is (= state (:state overlap)))
    (is (= :OPERATOR_CANCEL_REQUIRES_RUN_ROOT
           (get-in operator-call [:error :code])))
    (is (= state (:state operator-call)))
    (is (true? (:ok? operator-run)))
    (is (= :closed (get-in operator-run [:state :nodes "run-1" :admission])))))

(deftest first-accepted-rejects-an-applying-winner
  (let [state (-> (cancel/new-state {:session-id "session-1" :run-id "run-1"})
                  (cancel/add-nodes
                   [{:node-id "action-applying"
                     :kind :action
                     :parent-id "run-1"
                     :state :applying
                     :action-kind :edit
                     :side-effecting? true
                     :group-id "g"}
                    {:node-id "call-other"
                     :kind :call
                     :parent-id "run-1"
                     :state :ready
                     :side-effecting? false
                     :group-id "g"}]))
        result (cancel/accept-first-accepted
                state
                {:group-id "g"
                 :winner-id "action-applying"
                 :outcome :succeeded
                 :at 1})]
    (is (false? (:ok? result)))
    (is (= :ACTION_IN_FLIGHT (get-in result [:error :code])))
    (is (= state (:state result)))))

(deftest finish-run-projects-partial-from-actions
  (let [state (-> (cancel/new-state {:session-id "session-1" :run-id "run-1"})
                  (cancel/add-nodes
                   [{:node-id "action-done"
                     :kind :action
                     :parent-id "run-1"
                     :state :finished
                     :action-kind :edit
                     :side-effecting? true
                     :outcome :succeeded}
                    {:node-id "action-cancelled"
                     :kind :action
                     :parent-id "run-1"
                     :state :finished
                     :action-kind :edit
                     :side-effecting? true
                     :outcome :cancelled}]))
        cancelled (operator-cancel state)
        finished (cancel/finish-run (:state cancelled)
                                    {:run-id "run-1" :at 2})]
    (is (true? (:ok? finished)))
    (is (= :partial (get-in finished [:state :nodes "run-1" :outcome])))
    (is (= :partial (get-in finished [:receipt :outcome])))))

(deftest validate-state-rejects-inconsistent-cancellation-state
  (let [open-cancelling (cancel/new-state
                         {:session-id "session-1"
                          :run-id "run-1"
                          :run-state :cancelling
                          :admission :open})
        bad-next (assoc (cancel/new-state
                         {:session-id "session-1" :run-id "run-1"})
                        :next-node-seq 1)
        bad-outcome (cancel/add-node
                     (cancel/new-state
                      {:session-id "session-1" :run-id "run-1"})
                     {:node-id "call-bad"
                      :kind :call
                      :parent-id "run-1"
                      :state :succeeded
                      :outcome :failed})
        bad-action-outcome (cancel/add-node
                            (cancel/new-state
                             {:session-id "session-1" :run-id "run-1"})
                            {:node-id "action-bad"
                             :kind :action
                             :parent-id "run-1"
                             :state :finished
                             :action-kind :edit
                             :outcome :banana})]
    (is (some #(= :cancelling-run-open-admission (:problem %))
              (model/validate-state open-cancelling)))
    (is (some #(= :invalid-next-node-seq (:problem %))
              (model/validate-state bad-next)))
    (is (some #(= :outcome-state-mismatch (:problem %))
              (model/validate-state bad-outcome)))
    (is (some #(= :invalid-outcome (:problem %))
              (model/validate-state bad-action-outcome)))))

(deftest actionable-resources-excludes-signalled-calls
  (let [state (-> (cancel/new-state {:session-id "session-1" :run-id "run-1"})
                  (cancel/add-nodes
                   [{:node-id "call-live"
                     :kind :call
                     :parent-id "run-1"
                     :state :outcome-unknown
                     :side-effecting? true
                     :resource-alive? true
                     :cancel-requested? true}
                    {:node-id "call-retry"
                     :kind :call
                     :parent-id "run-1"
                     :state :retry-wait
                     :side-effecting? true
                     :resource-alive? true
                     :cancel-requested? false}]))]
    (is (= ["call-retry"] (cancel/actionable-resources state "run-1")))
    (is (= #{"call-live" "call-retry"}
           (set (cancel/active-resources state "run-1"))))))

(deftest a-stopping-process-with-a-live-resource-gets-an-abort
  (let [state (-> (cancel/new-state {:session-id "session-1" :run-id "run-1"})
                  (cancel/add-node
                   {:node-id "process-stopping"
                    :kind :process
                    :parent-id "run-1"
                    :state :stopping
                    :side-effecting? true
                    :resource-alive? true}))
        result (cancel/cancel-subtree
                state
                {:root-id "process-stopping"
                 :reason :parent-terminated
                 :origin :required-step-failure
                 :at 1})
        node (get-in result [:state :nodes "process-stopping"])
        again (cancel/cancel-subtree
               (:state result)
               {:root-id "process-stopping"
                :reason :parent-terminated
                :origin :required-step-failure
                :at 2})]
    (is (true? (:ok? result)))
    (is (= :stopping (:state node)))
    (is (true? (:resource-alive? node)))
    (is (true? (:cancel-requested? node)))
    (is (some #(and (= :abort-resource (:effect %))
                    (= "process-stopping" (:node-id %)))
              (:effects result)))
    (is (empty? (:effects again)))))

(deftest a-live-model-attempt-is-aborted-without-blocking-quiescence
  ;; §5.3 requires the cancel signal to interrupt the model HTTP stream for
  ;; one attempt-id.  A model attempt is not a durable resource, so it must
  ;; not keep the run open; it is still a live stream and must be signalled.
  (let [state (-> (cancel/new-state {:session-id "session-1" :run-id "run-1"})
                  (cancel/add-nodes
                   [{:node-id "call-model"
                     :kind :call
                     :parent-id "run-1"
                     :state :running
                     :side-effecting? false
                     :current-attempt-id "attempt-model"}
                    {:node-id "attempt-model"
                     :kind :attempt
                     :parent-id "call-model"
                     :state :running
                     :attempt-id "attempt-model"
                     :current-attempt-id "attempt-model"
                     :side-effecting? false}]))
        result (operator-cancel state)
        state' (:state result)
        effects (:effects result)]
    (is (true? (:ok? result)) (pr-str result))
    (is (= :cancelled (:state (node state' "attempt-model"))))
    (is (some #(and (= :abort-resource (:effect %))
                    (= "attempt-model" (:node-id %))
                    (= "call-model" (:call-id %))
                    (= "attempt-model" (:attempt-id %))
                    (= :model-stream (:scope %))
                    (false? (:thread-interrupt? %))
                    (false? (:dispose? %)))
              effects)
        (pr-str effects))
    (is (not-any? #(and (= :abort-resource (:effect %))
                        (= "call-model" (:node-id %)))
                  effects)
        "the call node owns no stream of its own; one abort per attempt")
    (is (true? (cancel/cancellation-quiescent? state' "run-1"))
        "a model stream must not block run closure as a durable resource")))
