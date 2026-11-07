(ns hc.compose.owner.scope-state-test
  "Cold-start behavior of the Owner's persisted Scope state."
  (:require [clojure.test :refer [deftest is]]
            [hc.compose.owner.guards :as guards]
            [hc.compose.owner.handlers :as handlers]
            [hc.compose.owner.state :as state]
            [hc.core.scope.ledger :as ledger]))

(defn- established-book []
  (:book (ledger/settle-turn (ledger/new-book) "turn-1"
                             {:kind :reset-scope :goal "g" :budget 4}
                             {:call-id "r1"})))

(deftest restore-clears-the-scope-budget-and-keeps-records
  (let [initial (state/initial-state {:session-id "scope-restore"})
        records [{:v 1 :type :reset :turn-id "turn-1" :call-id "r1"
                  :goal "g" :budget 4 :remaining 4}]
        persisted (assoc initial :scope {:book (established-book)
                                         :records records})
        restored (state/restore persisted)]
    (is (:ok? restored))
    (let [scope (:scope (:state restored))]
      (is (false? (get-in scope [:book :retention :established?])))
      (is (= 0 (get-in scope [:book :retention :remaining])))
      (is (= records (:records scope)))
      (is (= "r1" (get-in scope [:book :last-reset :call-id]))))))

(deftest restore-keeps-book-history-when-records-are-gone
  (let [initial (state/initial-state {:session-id "scope-restore-old"})
        persisted (assoc initial :scope {:book (established-book)
                                         :records []})
        restored (state/restore persisted)]
    (is (:ok? restored))
    (let [scope (:scope (:state restored))]
      (is (false? (get-in scope [:book :retention :established?])))
      (is (= "r1" (get-in scope [:book :last-reset :call-id]))))))

(deftest restore-clears-a-proven-dead-draft-claim-only
  (let [initial (state/initial-state {:session-id "draft-restart"
                                      :owner-instance-id "old"})
        claim {:path "plans/a.md"
               :request-id "claim-1"
               :call-id "call-1"
               :owner-instance-id "old"}
        persisted (assoc-in initial [:mission :draft-claim] claim)
        same (state/restore persisted {:owner-instance-id "old"})
        dead (state/restore persisted {:owner-instance-id "new"})
        legacy (state/restore
                (update-in persisted [:mission :draft-claim]
                           dissoc :owner-instance-id)
                {:owner-instance-id "new"})]
    (is (:ok? same))
    (is (= "plans/a.md"
           (get-in same [:state :mission :draft-claim :path])))
    (is (:ok? dead))
    (is (nil? (get-in dead [:state :mission :draft-claim])))
    (is (= "new"
           (get-in dead [:state :mission :owner-instance-id])))
    (is (:ok? legacy))
    (is (= "plans/a.md"
           (get-in legacy [:state :mission :draft-claim :path])))))

(deftest close-model-turn-scope-ignores-a-stale-turn
  (let [book (:book (ledger/settle-turn (ledger/new-book) "turn-1"
                                        {:kind :reset-scope :goal "g" :budget 2}
                                        {:call-id "r1"}))
        book (:book (ledger/settle-turn book "turn-2" {:kind :me-too} {}))
        initial (state/initial-state {:session-id "scope-stale"})
        state (assoc initial :scope {:book book :records []})
        state' (state/close-model-turn-scope state "turn-1" 0)]
    (is (= "turn-2" (get-in state' [:scope :book :turn :id])))
    (is (false? (get-in state' [:scope :book :turn :settled?])))
    (is (= 2 (get-in state' [:scope :book :retention :remaining])))
    (is (some #(= :SCOPE_CLOSE_STALE (:code %)) (:diagnostics state')))))

(deftest apply-scope-result-keeps-boundary-records
  (let [initial (state/initial-state {:session-id "scope-records"})
        keeps (vec (repeat 600 {:v 1 :type :keep :remaining 1}))
        state (assoc initial :scope {:book (ledger/new-book)
                                     :records keeps})
        state' (state/apply-scope-result
                state
                (ledger/new-book)
                [{:v 1 :type :remove :call-id "e1"
                  :replacement "lesson" :spin 1}])
        records (get-in state' [:scope :records])]
    (is (= 1 (count (filter #(= :remove (:type %)) records))))
    (is (= 512 (count (filter #(= :keep (:type %)) records))))
    (is (= :remove (:type (last records))))))

(deftest close-model-turn-scope-with-no-scope-activity-is-a-no-op
  (let [initial (state/initial-state {:session-id "scope-empty"})
        state' (state/close-model-turn-scope initial "call-1" 0)]
    (is (nil? (get-in state' [:scope :book :turn])))
    (is (empty? (:diagnostics state')))))

(deftest a-ready-tool-blocks-a-new-model-claim
  (let [initial (state/initial-state {:session-id "scope-ready"})
        state (assoc initial :calls
                     {"model-1" {:call-id "model-1" :kind :model
                                 :state :ready :agent-id :plan
                                 :created-at 1}
                      "tool-1" {:call-id "tool-1" :kind :tool
                                :state :ready :agent-id :plan
                                :scope-turn-id "model-0"
                                :scope-exempt? false
                                :created-at 0}})
        guard (guards/claim-model-request-guard)]
    (is (nil? ((:enabled? guard) state {})))
    (is (= {:call-id "model-1"}
           ((:enabled? guard)
            (assoc-in state [:calls "tool-1" :state] :succeeded)
            {})))))

(deftest duplicate-tool-call-is-ignored-before-scope-admission
  (let [initial (state/initial-state {:session-id "scope-dup"})
        state (assoc initial :calls
                     {"model-1" {:call-id "model-1" :kind :model
                                 :state :running :attempt-id "a1"
                                 :role :plan :lane :plan :agent-id :plan}
                      "model-1/tool-0"
                      {:call-id "model-1/tool-0" :kind :tool
                       :state :running}})
        result (handlers/apply-message
                state
                {:type :model/event
                 :event {:event :tool-call
                         :model-call-id "model-1"
                         :attempt-id "a1"
                         :call-id "tool-1"
                         :payload {:executor :tool :name "cat"}}}
                {:now 0})]
    (is (= :ignored (get-in result [:receipt :status])))
    (is (= :duplicate-tool-call (get-in result [:receipt :reason])))
    (is (= state (:state result)))
    (is (empty? (:conversation (:state result))))))

(deftest admission-refusal-appends-a-paired-tool-result
  (let [initial (state/initial-state {:session-id "scope-refusal"})
        state (assoc initial :calls
                     {"model-1" {:call-id "model-1" :kind :model
                                 :state :running :attempt-id "a1"
                                 :role :plan :lane :plan :agent-id :plan}})
        result (handlers/apply-message
                state
                {:type :model/event
                 :event {:event :tool-call
                         :model-call-id "model-1"
                         :attempt-id "a1"
                         :call-id "tool-1"
                         :arguments {:path "x"}
                         :payload {:executor :tool :name "cat"}}}
                {:now 0})
        conversation (:conversation (:state result))]
    (is (= :refused (get-in result [:receipt :status])))
    (is (= [:tool-call :tool] (mapv :role conversation)))
    (is (= "model-1/tool-0" (:call-id (first conversation))))
    (is (= {:path "x"} (:arguments (first conversation))))
    (is (= "model-1/tool-0" (:tool-call-id (second conversation))))
    (is (nil? (get-in result [:state :calls "model-1/tool-0"])))))
