(ns hc.compose.owner.tool-call-identity-e2e-test
  "End-to-end identity test: a real model-port tool-call event must become an
  Owner tool call even when the provider spells its id like an Owner model
  call.  The port namespaces project ids by attempt; the Owner namespaces its
  own call by the parent model call and output index.  Neither layer may
  silently drop a legitimate call as a duplicate."
  (:require [clojure.test :refer [deftest is testing]]
            [hc.compose.owner.commands :as commands]
            [hc.compose.owner.handlers :as handlers]
            [hc.compose.owner.state :as state]
            [hc.server.model-port.call-identity :as identity]
            [hc.server.model-port.stream :as stream]))

(def ^:private model-call-id "call-1")
(def ^:private attempt-id "attempt-0001")

(def ^:private tool-arguments
  {:source "(defprogram e2e01-review [] (review {:findings []}))"
   :intent "record the E2E requirement ledger"
   :scope {:kind :reset-scope
           :new-scope-goal "tool identity"
           :new-scope-retention-budget 8}})

(defn- fixture
  []
  (let [registry (commands/local-registry)
        base (state/initial-state {:session-id "identity-e2e"
                                   :now 1000
                                   :registry registry})]
    {:registry registry
     :state (assoc-in base [:calls model-call-id]
                      {:call-id model-call-id
                       :kind :model
                       :role :plan
                       :lane :plan
                       :agent-id :plan
                       :tenure-id "tenure-1"
                       :state :running
                       :attempt-id attempt-id
                       :request {:snapshot {:role :plan}}})}))

(defn- apply-port-event
  [state registry event]
  (handlers/apply-message
   state
   {:type :model/event
    :event (assoc event :model-call-id model-call-id)}
   {:now 2000 :registry registry}))

(defn- port-event
  [attempt output-index provider-id]
  (let [result (stream/accept-fragment
                (stream/new-assembler)
                (identity/new-registry)
                {:attempt-id attempt
                 :output-index output-index
                 :provider-call-id provider-id
                 :tool-name "clj-plan"
                 :arguments tool-arguments
                 :complete? true})]
    (is (true? (:ok result)))
    (:event result)))

(deftest owner-model-call-does-not-swallow-a-model-port-tool-call
  (let [base (fixture)
        event (port-event attempt-id 0 model-call-id)
        result (apply-port-event (:state base) (:registry base) event)]
    (testing "the port id is globally namespaced and cannot be the Owner id"
      (is (= "attempt-0001/tool-0" (:call-id event)))
      (is (not= model-call-id (:call-id event))))
    (testing "the Owner admits the tool call instead of treating it as a replay"
      (is (= :accepted (get-in result [:receipt :status])))
      (is (= :tool-call-created (get-in result [:receipt :outcome])))
      (is (= "call-1/tool-0" (get-in result [:receipt :call-id]))))
    (testing "the Owner model call keeps its identity and state"
      (is (= :model (get-in result [:state :calls model-call-id :kind])))
      (is (= :running (get-in result [:state :calls model-call-id :state]))))
    (testing "the admitted call is a child of that model call"
      (is (= :ready (get-in result [:state :calls "call-1/tool-0" :state])))
      (is (= model-call-id
             (get-in result [:state :calls "call-1/tool-0" :parent-call-id])))
      (is (= "call-1/tool-0"
             (some-> (last (get-in result [:state :conversation])) :call-id))))))

(deftest duplicate-provider-events-merge-and-same-arguments-stay-two-calls
  (let [base (fixture)
        assembler (stream/new-assembler)
        registry (identity/new-registry)
        fragment (fn [index]
                   {:attempt-id attempt-id
                    :output-index index
                    :provider-call-id (str "provider-" index)
                    :tool-name "clj-plan"
                    :arguments tool-arguments
                    :complete? true})
        first (stream/accept-fragment assembler registry (fragment 0))
        duplicate (stream/accept-fragment (:assembler first)
                                          (:registry first)
                                          (fragment 0))
        second (stream/accept-fragment (:assembler first)
                                       (:registry first)
                                       (fragment 1))
        applied-first (apply-port-event (:state base) (:registry base)
                                        (:event first))
        applied-duplicate (apply-port-event (:state applied-first)
                                            (:registry base)
                                            (:event first))
        applied-second (apply-port-event (:state applied-first)
                                         (:registry base)
                                         (:event second))]
    (testing "the same provider event is merged once by the port"
      (is (nil? (:event duplicate)))
      (is (= :merged (:status duplicate)))
      (is (= 1 (count (get-in duplicate [:registry :calls])))))
    (testing "the two legitimate same-argument calls keep distinct identities"
      (is (= "attempt-0001/tool-0" (:call-id (:event first))))
      (is (= "attempt-0001/tool-1" (:call-id (:event second))))
      (is (not= (:call-id (:event first)) (:call-id (:event second)))))
    (testing "the Owner accepts both calls"
      (is (= :accepted (get-in applied-first [:receipt :status])))
      (is (= :accepted (get-in applied-second [:receipt :status])))
      (is (= :ready (get-in applied-second [:state :calls "call-1/tool-0" :state])))
      (is (= :ready (get-in applied-second [:state :calls "call-1/tool-1" :state])))
      (is (= 2 (count (filter (fn [[call-id _]]
                                (contains? #{"call-1/tool-0" "call-1/tool-1"}
                                           call-id))
                              (:calls (:state applied-second)))))))
    (testing "a replayed event is still ignored as a duplicate, not a refusal"
      (is (= :ignored (get-in applied-duplicate [:receipt :status])))
      (is (= :duplicate-tool-call (get-in applied-duplicate [:receipt :reason])))
      (is (= (:state applied-first) (:state applied-duplicate))))))

(deftest the-same-provider-call-in-another-attempt-is-another-project-call
  (let [first (port-event "attempt-0001" 0 "provider-shared")
        second (port-event "attempt-0002" 0 "provider-shared")]
    (is (= "attempt-0001/tool-0" (:call-id first)))
    (is (= "attempt-0002/tool-0" (:call-id second)))
    (is (not= (:call-id first) (:call-id second))
        "a retry attempt never reuses another attempt's project identity")))

(deftest a-retry-at-the-same-output-index-gets-a-distinct-owner-call
  (let [base (fixture)
        first-event (port-event attempt-id 0 "provider-1")
        first-result (apply-port-event (:state base) (:registry base)
                                       first-event)
        first-id (get-in first-result [:receipt :call-id])
        retried (assoc-in (:state first-result) [:calls model-call-id
                                                 :attempt-id]
                          "attempt-0002")
        second-event (port-event "attempt-0002" 0 "provider-2")
        second-result (apply-port-event retried (:registry base)
                                        second-event)
        second-id (get-in second-result [:receipt :call-id])]
    (is (= "call-1/tool-0" first-id))
    (is (= "call-1/tool-0#attempt-0002" second-id))
    (is (not= first-id second-id))
    (is (= :ready (get-in second-result [:state :calls second-id :state])))
    (is (= :ready (get-in second-result [:state :calls first-id :state]))
        "the old attempt's call stays as evidence and is not reused")))
