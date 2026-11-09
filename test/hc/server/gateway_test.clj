(ns hc.server.gateway-test
  (:require [clojure.test :refer [are deftest is testing]]
            [hc.server.commands :as commands]
            [hc.server.gateway :as gateway]
            [hc.server.gateway.feed :as feed]
            [hc.server.gateway.ports :as ports]))

(defn- make-gw
  [owner]
  (let [out (atom [])
        gw {:feed (feed/create {:now-fn (constantly "2026-11-03T00:00:00Z")})
            :owner owner
            :store nil
            :socket {:send-fn (fn [uid event] (swap! out conj [uid event]))}
            :out out
            :stopped? (atom false)
            :opts {}}]
    (assoc gw :handler (fn [_] {:status 200}))))

(defn- subscribe!
  [gw uid sid]
  (let [reply (atom nil)]
    (gateway/handle-event! gw
                           {:uid uid
                            :?reply-fn #(reset! reply %)
                            :event [:hc/subscribe {:subscription-id sid}]})
    @reply))

(defn- command!
  [gw uid request]
  (let [request (if (contains? request :subscription-id)
                  request
                  (assoc request :subscription-id 1))
        reply (atom nil)]
    (gateway/handle-event! gw
                           {:uid uid
                            :?reply-fn #(reset! reply %)
                            :event [:hc/command request]})
    @reply))

(defn- sent-events
  [gw]
  (mapv (comp second second) @(:out gw)))

(defn- receipt-events
  [gw]
  (->> (sent-events gw)
       (filter #(= "CUSTOM" (:type %)))
       (filter #(= "hc/command-receipt" (:name %)))
       (mapv :value)))

(defn- fake-owner
  [calls]
  (ports/owner-port
   (fn [request reply]
     (swap! calls conj request)
     (reply {:ok? true
             :replayed? false
             :receipt {:status :accepted
                       :command-id (:command-id request)
                       :command-epoch (:command-epoch request)}}))
   (fn [query]
     (let [command-id (if (map? query) (:command-id query) query)]
       (when (= "cmd-1" command-id)
         {:status :accepted :command-id command-id :command-epoch 1})))))

(defn- submit-failure
  "Submit one command to OWNER and return the receipt error map."
  [owner]
  (let [gw (make-gw owner)
        _ (subscribe! gw "u1" 1)]
    (command! gw "u1" {:command-id "cmd-failure"
                       :command-epoch 1
                       :payload {}})
    (get-in (first (receipt-events gw)) [:error])))

(deftest subscribe-and-command-round-trip
  (let [calls (atom [])
        gw (make-gw (fake-owner calls))
        reply (subscribe! gw "u1" 1)]
    (is (:ok? reply))
    (is (= 1 (:subscription-id reply)))
    (is (= 1 (count @(:out gw))))
    (is (= "STATE_SNAPSHOT" (-> (sent-events gw) first :type)))

    (let [ack (command! gw "u1" {:command-id "cmd-1"
                                :command-epoch 1
                                :payload {:text "hello"}})]
      (is (:ok? ack))
      (is (= :submitted (:status ack)))
      (is (= [{:command-id "cmd-1"
               :command-epoch 1
               :payload {:text "hello"}
               :client-id nil
               :uid "u1"
               :subscription-id 1}]
             @calls)))
    (is (= [{:ok? true
             :replayed? false
             :receipt {:status :accepted
                       :command-id "cmd-1"
                       :command-epoch 1}
             :command-id "cmd-1"
             :command-epoch 1}]
           (receipt-events gw)))))

(deftest stale-subscription-cannot-command
  (let [calls (atom [])
        gw (make-gw (fake-owner calls))
        _ (subscribe! gw "u1" 1)
        _ (subscribe! gw "u1" 2)
        reply (command! gw "u1" {:command-id "cmd-1"
                                 :command-epoch 1
                                 :subscription-id 1
                                 :payload {}})]
    (is (= :STALE_SUBSCRIPTION (get-in reply [:error :code])))
    (is (empty? @calls))
    (testing "a command without a subscription is refused"
      (let [gw (make-gw (fake-owner calls))
            reply (command! gw "u2" {:command-id "cmd-2"
                                     :command-epoch 1
                                     :payload {}})]
        (is (= :STALE_SUBSCRIPTION (get-in reply [:error :code])))))))

(deftest owner-unavailable-and-owner-failure
  (let [gw (make-gw nil)
        _ (subscribe! gw "u1" 1)
        reply (command! gw "u1" {:command-id "cmd-1"
                                 :command-epoch 1
                                 :payload {}})]
    (is (= :OWNER_UNAVAILABLE (get-in reply [:error :code]))))
  (let [gw (make-gw (ports/owner-port
                     (fn [_ _] (throw (ex-info "boom" {})))
                     (fn [_] nil)))
        _ (subscribe! gw "u1" 1)
        _ (command! gw "u1" {:command-id "cmd-1"
                             :command-epoch 1
                             :payload {}})]
    (let [err (get-in (first (receipt-events gw)) [:error])]
      (is (= :COMMAND_SUBMIT_FAILED (:code err)))
      (is (= :unknown (get-in err [:details :cause])))
      (is (string? (get-in err [:details :class]))))))

(deftest lower-ex-data-survives-the-stable-gateway-code
  (testing "command submit keeps the Owner's code and details"
    (let [gw (make-gw (ports/owner-port
                       (fn [_ _]
                         (throw (ex-info "lower"
                                         {:code :SOME_SPECIFIC_CODE
                                          :details {:field :x}})))
                       (fn [_] nil)))
          _ (subscribe! gw "u1" 1)
          _ (command! gw "u1" {:command-id "cmd-ex"
                               :command-epoch 1
                               :payload {}})
          err (get-in (first (receipt-events gw)) [:error])]
      (is (= :COMMAND_SUBMIT_FAILED (:code err)))
      (is (= :SOME_SPECIFIC_CODE (get-in err [:details :cause])))
      (is (= {:field :x} (get-in err [:details :cause-details])))))
  (testing "receipt read keeps the Owner's code and details"
    (let [gw (make-gw (ports/owner-port
                       (fn [_ _] {:ok? true})
                       (fn [_]
                         (throw (ex-info "receipt-low"
                                         {:code :RECEIPT_LOWER
                                          :details {:q 1}})))))
          _ (subscribe! gw "u1" 1)
          reply (atom nil)]
      (gateway/handle-event! gw
                             {:uid "u1"
                              :?reply-fn #(reset! reply %)
                              :event [:hc/receipt {:command-id "cmd-r"
                                                   :subscription-id 1}]})
      (is (= :COMMAND_RECEIPT_FAILED (get-in @reply [:error :code])))
      (is (= :RECEIPT_LOWER (get-in @reply [:error :details :cause])))
      (is (= {:q 1} (get-in @reply [:error :details :cause-details]))))))

(deftest gateway-never-replaces-a-lower-code-with-an-anonymous-failure
  (are [lower-code lower-details]
      (let [err (submit-failure
                 (ports/owner-port
                  (fn [_ _]
                    (throw (ex-info "lower"
                                    {:code lower-code
                                     :details lower-details})))
                  (fn [_] nil)))
            details (:details err)]
        (and (= :COMMAND_SUBMIT_FAILED (:code err))
             (not= lower-code (:code err))
             (map? details)
             (seq details)
             (= lower-code (:cause details))
             (= lower-details (:cause-details details))))
    :ACTIVE_TURN_EXISTS {:turn-id "turn-7"}
    :UNKNOWN_TASK {:task-id "task-9"}
    :CLAIM_CHANGED {:expected 1 :actual 2}))

(deftest a-throwable-without-ex-data-stays-diagnosable
  (let [err (submit-failure
             (ports/owner-port
              (fn [_ _] (throw (RuntimeException. "plain")))
              (fn [_] nil)))
        details (:details err)]
    (is (= :COMMAND_SUBMIT_FAILED (:code err)))
    (is (= :unknown (:cause details)))
    (is (re-find #"RuntimeException" (:class details)))
    (is (not (contains? details :cause-details)))))

(deftest command-domain-refusal-becomes-a-custom-error
  (let [domain (atom (:state (commands/issue-epoch (commands/new-state))))
        owner (ports/owner-port
               (fn [request reply]
                 (let [result (commands/accept @domain request)]
                   (when (:ok? result)
                     (reset! domain (:state result)))
                   (reply result)))
               (fn [query]
                 (commands/receipt-for @domain query)))
        gw (make-gw owner)
        _ (subscribe! gw "u1" 1)]
    (command! gw "u1" {:command-id "cmd-ok"
                       :command-epoch 1
                       :payload {:text "hello"}})
    (command! gw "u1" {:command-id "cmd-retired"
                       :command-epoch 99
                       :payload {:text "late"}})
    (is (= :COMMAND_EPOCH_UNKNOWN
           (get-in (last (receipt-events gw)) [:error :code])))
    (let [reply (atom nil)]
      (gateway/handle-event! gw
                             {:uid "u1"
                              :?reply-fn #(reset! reply %)
                              :event [:hc/receipt {:command-id "cmd-ok"
                                                   :subscription-id 1}]})
      (is (= {:status :accepted
              :command-id "cmd-ok"
              :command-epoch 1}
             (:receipt @reply))))))

(deftest asynchronous-owner-reply-becomes-a-custom-event
  (let [pending (atom nil)
        gw (make-gw (ports/owner-port
                     (fn [request reply] (reset! pending [request reply]))
                     (fn [_] nil)))
        _ (subscribe! gw "u1" 1)
        ack (command! gw "u1" {:command-id "cmd-9"
                               :command-epoch 1
                               :payload {:x 1}})]
    (is (:ok? ack))
    (is (empty? (receipt-events gw)))
    (let [[request reply] @pending]
      (is (= "cmd-9" (:command-id request)))
      (reply {:ok? true :receipt {:status :accepted :command-id "cmd-9"}}))
    (is (= [{:ok? true
             :receipt {:status :accepted :command-id "cmd-9"}
             :command-id "cmd-9"
             :command-epoch 1}]
           (receipt-events gw)))))

(deftest receipt-query-uses-the-callback
  (let [gw (make-gw (fake-owner (atom [])))
        _ (subscribe! gw "u1" 1)
        reply (atom nil)]
    (gateway/handle-event! gw
                           {:uid "u1"
                            :?reply-fn #(reset! reply %)
                            :event [:hc/receipt {:command-id "cmd-1"
                                                 :subscription-id 1}]})
    (is (= {:ok? true
            :command-id "cmd-1"
            :receipt {:status :accepted :command-id "cmd-1" :command-epoch 1}}
           @reply))))

(deftest ack-and-resync
  (let [gw (make-gw nil)
        _ (subscribe! gw "u1" 1)
        _ (gateway/publish! gw {:type :state-delta
                                :delta [{"op" "add" "path" "/x" "value" 1}]})
        ack (atom nil)]
    (gateway/handle-event! gw
                           {:uid "u1"
                            :?reply-fn #(reset! ack %)
                            :event [:hc/ack {:subscription-id 1 :seq 1}]})
    (is (= 1 (:acked-seq @ack)))
    (let [before (count @(:out gw))
          reply (atom nil)]
      (gateway/handle-event! gw
                             {:uid "u1"
                              :?reply-fn #(reset! reply %)
                              :event [:hc/resync {:subscription-id 1}]})
      (is (:ok? @reply))
      (is (= (inc before) (count @(:out gw))))
      (is (= "STATE_SNAPSHOT" (-> (sent-events gw) last :type))))))

(deftest publish-validation-and-epoch
  (let [gw (make-gw nil)
        _ (subscribe! gw "u1" 1)]
    (is (= {:epoch 1 :seq 0}
           (select-keys (gateway/snapshot gw) [:epoch :seq])))
    (let [result (gateway/publish! gw {:type :state-snapshot
                                       :snapshot {:a 1}
                                       :new-epoch? true})]
      (is (:ok? result))
      (is (= 2 (:epoch result)))
      (is (= 0 (:seq result))))
    (let [before (gateway/snapshot gw)
          result (gateway/publish! gw {:type :state-delta
                                       :delta [{"op" "replace"
                                                "path" "/missing"
                                                "value" 1}]})]
      (is (not (:ok? result)))
      (is (= before (gateway/snapshot gw))))
    (let [result (gateway/publish! gw {:type :messages-snapshot
                                       :messages [{:id "m1" :role "user"}]})]
      (is (:ok? result))
      (is (= [{:id "m1" :role "user"}]
             (get-in (gateway/snapshot gw) [:snapshot :messages]))))
    (is (= :EVENT_TYPE_UNSUPPORTED
           (get-in (gateway/publish! gw {:type :run-started}) [:error :code])))
    (gateway/stop! gw)
    (is (= :GATEWAY_STOPPED
           (get-in (gateway/publish! gw {:type :state-snapshot :snapshot {}})
                   [:error :code])))))

(deftest stop-reports-sente-drain-and-can-retry
  (let [drained? (atom false)
        gw {:feed (feed/create {:now-fn (constantly "2026-11-03T00:00:00Z")})
            :stopped? (atom false)
            :sente {:stop! (fn [] {:ok? true :drained? @drained?})}}
        first (gateway/stop! gw)
        second (gateway/stop! gw)]
    (is (false? (:ok? first)))
    (is (false? (:stopped? first)))
    (is (false? (:drained? first)))
    (is (false? (:stopped? second)))
    (reset! drained? true)
    (let [third (gateway/stop! gw)]
      (is (true? (:ok? third)))
      (is (true? (:stopped? third)))
      (is (true? (:drained? third))))))

(deftest receipt-query-without-subscription-id-uses-the-current-subscription
  ;; D-26: the client receipt sweep sends `[:hc/receipt ...]` without a
  ;; reply-fn.  A query that omits `:subscription-id` must still be checked
  ;; against the uid's current subscription and answered on it; an explicit
  ;; stale generation is refused instead.
  (let [calls (atom [])
        gw (make-gw (fake-owner calls))
        _ (subscribe! gw "u1" 1)
        _ (subscribe! gw "u1" 2)
        ;; A fire-and-forget query with no subscription id is answered on
        ;; the current generation.
        _ (gateway/handle-event! gw
                                {:uid "u1"
                                 :event [:hc/receipt {:command-id "cmd-1"
                                                      :session-id "s-1"}]})
        answered (receipt-events gw)
        ;; The same query carrying the retired generation is dropped by
        ;; subscription-check; with no reply-fn nothing reaches the client.
        before (count (receipt-events gw))
        _ (gateway/handle-event! gw
                                {:uid "u1"
                                 :event [:hc/receipt {:command-id "cmd-1"
                                                      :session-id "s-1"
                                                      :subscription-id 1}]})]
    (is (= 1 (count answered))
        "the subscription-id-less query produced one receipt event")
    (is (= {:status :accepted :command-id "cmd-1" :command-epoch 1}
           (:receipt (first answered))))
    (is (= before (count (receipt-events gw)))
        "the stale-generation query stayed silent")
    ;; With a reply-fn the stale refusal is at least observable.
    (let [reply (atom nil)]
      (gateway/handle-event! gw
                             {:uid "u1"
                              :?reply-fn #(reset! reply %)
                              :event [:hc/receipt {:command-id "cmd-1"
                                                   :session-id "s-1"
                                                   :subscription-id 1}]})
      (is (= :STALE_SUBSCRIPTION
             (get-in @reply [:error :code]))))))
