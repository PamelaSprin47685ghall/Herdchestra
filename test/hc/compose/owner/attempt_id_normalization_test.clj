(ns hc.compose.owner.attempt-id-normalization-test
  "Attempt-id spelling must not keep an assistant message streaming forever.

  The scheduler stores attempt ids as keywords; model gateways and transport
  envelopes may return the same id as a string, sometimes with a leading
  colon.  Call, run, message, cancellation-node, and terminal keys must share
  one spelling."
  (:require [clojure.test :refer [deftest is]]
            [hc.compose.guards.scheduler :as scheduler]
            [hc.compose.owner.handlers :as handlers]
            [hc.compose.owner.guards :as guards]
            [hc.compose.owner.state :as state]))

(deftest attempt-id-keys-normalize-to-one-string
  (is (= (state/run-id "call-1" :attempt-1)
         (state/run-id "call-1" "attempt-1")
         (state/run-id "call-1" ":attempt-1")))
  (is (= (state/attempt-node-id "call-1" :attempt-1)
         (state/attempt-node-id "call-1" "attempt-1")
         (state/attempt-node-id "call-1" ":attempt-1"))))

(deftest completed-event-with-string-attempt-id-clears-streaming
  (let [base (state/initial-state
              {:session-id "attempt-normalization"
               :models {:main "main-model" :task "task-model"}})
        [base call] (state/create-call
                     base
                     {:kind :model
                      :role :plan
                      :lane :plan
                      :agent-id :plan
                      :request {:snapshot {:messages []}}})
        call (assoc call :state :running :attempt-id :attempt-1)
        base (-> base
                 (state/put-call call)
                 (state/add-attempt-node call :attempt-1)
                 (state/start-run call :attempt-1 1))
        result (handlers/apply-message
                base
                {:type :model/event
                 :event {:event :completed
                         :call-id (:call-id call)
                         ;; transport/string spelling, not the scheduler keyword
                         :attempt-id "attempt-1"
                         :text "answer"}}
                {:now 2})
        after (:state result)
        run (get-in after [:runs (state/run-id (:call-id call) "attempt-1")])
        assistant (some #(when (= :assistant (:role %)) %) (:conversation after))
        prepared (scheduler/step
                  (scheduler/catalog [(guards/accept-call-terminal-guard)])
                  after {:now 3}
                  (scheduler/new-scheduler {:quota 8}))
        fixed (:state (:transition prepared))]
    ;; The model-event handler queues the terminal; the P1 closure guard
    ;; owns the later call-state transition.  Both halves are asserted here
    ;; so a lost terminal cannot hide behind a finished run/message.
    (is (= :finished (:status run)))
    (is (false? (:streaming? assistant)))
    (is (= "answer" (:text assistant)))
    (is (= 1 (count (filter #(= :succeeded (:status %))
                            (:terminals after)))))
    (is (= :prepared (:status prepared)))
    (is (= :succeeded (get-in fixed [:calls (:call-id call) :state])))))
