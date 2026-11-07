(ns hc.compose.owner.report-integration-test
  (:require [clojure.test :refer [deftest is testing]]
            [hc.compose.owner :as owner]
            [hc.compose.owner.commands :as commands]
            [hc.compose.owner.guards :as guards]
            [hc.compose.owner.handlers :as handlers]
            [hc.compose.owner.state :as state]
            [hc.compose.reports.delivery :as delivery]))

(defn- base-state
  []
  (state/initial-state {:session-id "s1"
                        :now 1000
                        :registry (commands/local-registry)}))

(defn- with-pending-report
  [state]
  (let [result (delivery/register
                (:reports state)
                {:report-id "rep-1"
                 :parent-call-id "call-9"
                 :payload {:kind :llm-report
                           :text "full report\nwith \"quotes\" and # hash"}
                 :resource-ref {:attempt-id "a-1"}})]
    (assoc state :reports (:state result))))

(defn- with-claim
  [state claim]
  (assoc-in state [:report-claims "rep-1"] claim))

(defn- guard
  [id]
  (some #(when (= id (:id %)) %) (guards/catalog)))

(defn- apply-delivered
  [state message now]
  (handlers/apply-message state message
                          {:now now :registry (commands/local-registry)}))

(deftest report-delivery-failure-never-becomes-terminal
  (let [start (-> (base-state)
                  with-pending-report
                  (with-claim {:state :dispatching
                               :attempt-id "a-1"
                               :attempts 1}))
        final
        (loop [state start
               attempt 2
               now 2000]
          (if (> attempt 8)
            state
            (let [state (-> state
                            (assoc-in [:report-claims "rep-1" :state]
                                      :dispatching)
                            (assoc-in [:report-claims "rep-1" :attempt-id]
                                      (str "a-" attempt))
                            (update-in [:report-claims "rep-1" :attempts]
                                       (fnil inc 0)))
                  result (apply-delivered
                          state
                          {:type :report/delivered
                           :report-id "rep-1"
                           :attempt-id (str "a-" attempt)
                           :ok? false
                           :error {:code :SINK_DOWN :message "down"}}
                          now)
                  state' (:state result)
                  claim (get-in state' [:report-claims "rep-1"])]
              (is (= :pending
                     (get-in state' [:reports :reports "rep-1" :phase])))
              (is (= :retry-wait (:state claim)))
              (is (not= :failed (:state claim)))
              (is (= :report-retry (get-in result [:receipt :outcome])))
              (is (= (+ now (state/report-retry-delay (:attempts claim)))
                     (:retry-at claim)))
              (recur state' (inc attempt) (+ now 1000)))))]
    (is (= :pending (get-in final [:reports :reports "rep-1" :phase])))
    (is (= :retry-wait (get-in final [:report-claims "rep-1" :state])))
    (is (= :REPORT_DELIVERY_RETRY
           (-> final :diagnostics last :code)))))

(deftest pending-report-without-a-claim-is-repaired
  (let [state (with-pending-report (base-state))
        ensure (guard :ensure-report-claim)
        deliver (guard :deliver-child-report)]
    (is (nil? (get-in state [:report-claims "rep-1"])))
    (let [work ((:enabled? ensure) state {:now 1000})
          repaired (:state ((:prepare ensure) state {:now 1000} work nil))]
      (is (= {:report-id "rep-1"} work))
      (is (= :pending (get-in repaired [:report-claims "rep-1" :state])))
      (is (= {:report-id "rep-1"
              :report (get-in repaired [:reports :reports "rep-1"])}
             ((:enabled? deliver) repaired {:now 1000}))))
    (testing "a legacy :failed claim is repaired instead of staying terminal"
      (let [legacy (with-claim state {:state :failed :attempts 4})
            work ((:enabled? ensure) legacy {:now 1000})
            repaired (:state ((:prepare ensure) legacy {:now 1000} work nil))]
        (is (= :pending (get-in repaired [:report-claims "rep-1" :state])))
        (is (= 4 (get-in repaired [:report-claims "rep-1" :attempts])))))))

(deftest cancelling-parent-acknowledges-parent-terminated
  (let [base (base-state)
        [state _call] (state/create-call
                       base
                       {:call-id "call-9"
                        :kind :model
                        :role :plan
                        :lane :plan
                        :agent-id :plan
                        :request {:snapshot {}}
                        :created-at 1000})
        run-id (get-in state [:cancel :active-run-id])
        state (assoc-in state [:cancel :nodes run-id :state] :cancelling)
        report {:report-id "rep-1"
                :parent-call-id "call-9"
                :payload {:kind :llm-report :text "full"}}
        result (handlers/apply-message
                state
                {:type :child/report :report report}
                {:now 2000 :registry (commands/local-registry)})]
    (is (= :parent-terminated (get-in result [:receipt :outcome])))
    (is (= :parent-terminated
           (get-in result
                   [:state :acceptance :accepted "call-9" :ack :status])))
    (is (empty? (get-in result [:state :conversation])))
    (is (= :report/ack (get-in result [:state :outbox 0 :type])))))

(deftest report-retry-guards-arm-and-wake-without-a-busy-loop
  (let [state (-> (base-state)
                  with-pending-report
                  (with-claim {:state :retry-wait
                               :attempts 2
                               :retry-at 1500
                               :timer {:state :ready :retry-at 1500}}))
        schedule (guard :schedule-report-retry)
        wake (guard :wake-report-retry)
        deliver (guard :deliver-child-report)]
    (testing "the future retry is armed exactly once"
      (let [work ((:enabled? schedule) state {:now 1000})
            plan ((:prepare schedule) state {:now 1000} work "timer-1")]
        (is (= {:report-id "rep-1" :retry-at 1500} work))
        (is (= :dispatching
               (get-in plan [:state :report-claims "rep-1" :timer :state])))
        (is (= [{:kind :timer
                 :call-id "report-rep-1"
                 :retry-at 1500
                 :reason :report-retry}]
               (:dispatch plan)))
        (is (nil? ((:enabled? schedule)
                   (:state plan) {:now 1000})))))
    (testing "the wake guard is level-triggered at the due time"
      (is (nil? ((:enabled? wake) state {:now 1499})))
      (let [work ((:enabled? wake) state {:now 1500})
            plan ((:prepare wake) state {:now 1500} work nil)]
        (is (= {:report-id "rep-1"} work))
        (is (= :pending
               (get-in plan [:state :report-claims "rep-1" :state])))
        (is (nil? (get-in plan [:state :report-claims "rep-1" :timer])))))
    (testing "the delivery guard claims a fresh attempt"
      (let [due (:state ((:prepare wake) state {:now 1500}
                         ((:enabled? wake) state {:now 1500}) nil))
            work ((:enabled? deliver) due {:now 1500})
            plan ((:prepare deliver) due {:now 1500} work "a-3")]
        (is (= "rep-1" (:report-id work)))
        (is (= :dispatching
               (get-in plan [:state :report-claims "rep-1" :state])))
        (is (= 3 (get-in plan [:state :report-claims "rep-1" :attempts])))
        (is (= :report (get-in plan [:dispatch 0 :kind])))))
    (testing "the backoff is bounded"
      (is (= 5000 (state/report-retry-delay 100))))))

(deftest delivered-claim-waits-for-ack-then-redelivers
  (let [state (-> (base-state)
                  with-pending-report
                  (with-claim {:state :dispatching
                               :attempt-id "a-1"
                               :attempts 1}))
        success (:state
                 (apply-delivered
                  state
                  {:type :report/delivered
                   :report-id "rep-1"
                   :attempt-id "a-1"
                   :ok? true}
                  2000))
        claim (get-in success [:report-claims "rep-1"])
        due (+ 2000 state/report-ack-timeout-ms)
        schedule (guard :schedule-report-retry)
        wake (guard :wake-report-retry)]
    (is (= :delivered (:state claim)))
    (is (= due (:retry-at claim)))
    (is (= :pending (get-in success [:reports :reports "rep-1" :phase])))
    (is (= {:report-id "rep-1" :retry-at due}
           ((:enabled? schedule) success {:now 2000})))
    (is (nil? ((:enabled? wake) success {:now (dec due)})))
    (let [work ((:enabled? wake) success {:now due})
          woke (:state ((:prepare wake) success {:now due} work nil))]
      (is (= {:report-id "rep-1"} work))
      (is (= :pending (get-in woke [:report-claims "rep-1" :state])))
      (is (= :deliver (:action (delivery/next-action (:reports woke))))))))

(deftest report-bridge-routes-across-owners
  (let [child-messages (atom [])
        parent-messages (atom [])
        child {:session-id "child"
               :submit! (fn [message]
                          (swap! child-messages conj message)
                          {:ok? true})}
        parent {:session-id "parent"
                :submit! (fn [message]
                           (swap! parent-messages conj message)
                           {:ok? true})}
        bridge (owner/report-bridge
                (fn [] child)
                (fn [session-id]
                  (case session-id
                    "child" child
                    "parent" parent
                    nil)))
        report {:report-id "rep-1"
                :parent-call-id "call-9"
                :parent-session-id "parent"
                :payload {:kind :llm-report :text "full"}
                :resource-ref {:attempt-id "a-1"}}]
    (testing "the sink routes to the parent and names the child"
      (is (= {:ok? true}
             ((:report-sink! bridge) report)))
      (is (= [{:type :child/report
               :report (assoc report :child-session-id "child")}]
             @parent-messages))
      (is (empty? @child-messages)))
    (testing "the acknowledgement routes back to the child"
      (let [ack {:report-id "rep-1"
                 :parent-call-id "call-9"
                 :status :accepted
                 :child-session-id "child"}]
        ((:on-effect! bridge) {:type :report/ack :ack ack})
        (is (= [{:type :report/ack :ack ack}] @child-messages))))
    (testing "a missing parent is a retryable sink failure"
      (reset! parent-messages [])
      (let [result ((:report-sink! bridge)
                    (assoc report :parent-session-id "missing"))]
        (is (false? (:ok? result)))
        (is (= :PARENT_OWNER_UNAVAILABLE
               (get-in result [:error :code])))))
    (testing "a report without a parent session stays with the child"
      (reset! child-messages [])
      (is (= {:ok? true}
             ((:report-sink! bridge) (dissoc report :parent-session-id))))
      (is (= "child"
             (get-in @child-messages [0 :report :child-session-id]))))))

(deftest report-bridge-forwards-non-ack-effects
  (let [events (atom [])
        bridge (owner/report-bridge
                (constantly nil)
                (constantly nil)
                {:forward-effect! #(swap! events conj %)})]
    ((:on-effect! bridge) {:type :owner/blocked
                           :diagnostic {:code :BLOCKED}})
    (is (= [{:type :owner/blocked
             :diagnostic {:code :BLOCKED}}]
           @events)
        "a composed handler must receive non-report effects")))
