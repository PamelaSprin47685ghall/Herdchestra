(ns hc.compose.owner.dispatch-test
  (:require [clojure.test :refer [deftest is testing]]
            [hc.compose.owner.dispatch :as dispatch]))

(defn- wait-for
  [pred timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (pred) true
        (< (System/currentTimeMillis) deadline)
        (do (Thread/sleep 10) (recur))
        :else false))))

(defn- messages-with
  [messages type]
  (filter #(= type (:type %)) @messages))

(deftest executor-success-posts-terminal-and-settles
  (let [messages (atom [])
        d (dispatch/create
           {:post! (fn [message] (swap! messages conj message) {:ok? true})
            :executors {:tool (fn [effect]
                                {:status :succeeded :payload (:n effect)})}})]
    (try
      (is (:ok? (dispatch/start! d {:kind :tool :attempt-id "a1"
                                    :call-id "c1" :n 7})))
      (is (wait-for #(seq (messages-with messages :dispatch/settled)) 3000))
      (is (= [{:type :call/terminal :call-id "c1" :attempt-id "a1"
               :status :succeeded :payload 7}]
             (vec (messages-with messages :call/terminal))))
      (is (empty? (dispatch/live-attempts d)))
      (finally
        (dispatch/stop! d)))))

(deftest executor-failure-is-data
  (let [messages (atom [])
        d (dispatch/create
           {:post! (fn [message] (swap! messages conj message) {:ok? true})
            :executors {:tool (fn [_]
                                {:status :failed
                                 :error {:kind :timeout :message "slow"}})}})]
    (try
      (dispatch/start! d {:kind :tool :attempt-id "a1" :call-id "c1"})
      (is (wait-for #(seq (messages-with messages :call/terminal)) 3000))
      (let [terminal (first (messages-with messages :call/terminal))]
        (is (= :failed (:status terminal)))
        (is (= :timeout (get-in terminal [:error :kind]))))
      (finally
        (dispatch/stop! d)))))

(deftest executor-throw-is-a-failure-not-a-crash
  (let [messages (atom [])
        d (dispatch/create
           {:post! (fn [message] (swap! messages conj message) {:ok? true})
            :executors {:tool (fn [_] (throw (ex-info "boom" {:kind :network})))}})]
    (try
      (dispatch/start! d {:kind :tool :attempt-id "a1" :call-id "c1"})
      (is (wait-for #(seq (messages-with messages :call/terminal)) 3000))
      (let [terminal (first (messages-with messages :call/terminal))]
        (is (= :failed (:status terminal)))
        (is (= :network (get-in terminal [:error :kind]))))
      (finally
        (dispatch/stop! d)))))

(deftest cancel-does-not-fabricate-a-terminal-for-a-live-executor
  (let [messages (atom [])
        started (promise)
        release (promise)
        d (dispatch/create
           {:post! (fn [message] (swap! messages conj message) {:ok? true})
            :executors {:shell (fn [_]
                                 (deliver started true)
                                 ;; The executor is live until this latch is
                                 ;; released; cancellation only signals it.
                                 (deref release 5000 :timeout)
                                 {:status :succeeded
                                  :payload {:exit 0}})}})]
    (try
      (dispatch/start! d {:kind :shell :attempt-id "a1" :call-id "c1"})
      (is (true? (deref started 3000 false)))
      (is (:ok? (dispatch/cancel! d "a1" :user-cancel)))
      ;; A cancel signal is not a terminal.  The executor is still live.
      (Thread/sleep 100)
      (is (empty? (messages-with messages :call/terminal)))
      (deliver release :exit)
      (is (wait-for #(seq (messages-with messages :call/terminal)) 3000))
      (let [terminal (first (messages-with messages :call/terminal))]
        (is (= :succeeded (:status terminal))
            "the executor's own result is the only terminal")
        (is (= {:exit 0} (:payload terminal))))
      (finally
        (deliver release :cleanup)
        (dispatch/stop! d)))))

(deftest cancel-resource-finds-a-live-attempt-by-canonical-id
  (let [messages (atom [])
        cancelled (atom 0)
        hooked (atom 0)
        d (dispatch/create
           {:post! (fn [message] (swap! messages conj message) {:ok? true})
            :executors
            {:shell {:run (fn [_]
                            (Thread/sleep 5000)
                            {:status :succeeded})
                     :cancel (fn [_] (swap! cancelled inc))}}
            :cancel-resource! (fn [_] (swap! hooked inc))})]
    (try
      (is (:ok? (dispatch/start! d {:kind :shell
                                    :attempt-id :attempt-1
                                    :call-id "tool-1"})))
      (is (wait-for #(= 1 (count (dispatch/live-attempts d))) 3000))
      (let [result (dispatch/cancel-resource!
                    d
                    {:kind :shell
                     :node-kind :attempt
                     :call-id "tool-1"
                     :attempt-id "attempt-1"
                     :reason :user-cancel})]
        (is (true? (:live? result)))
        (is (= :attempt-1 (:live-attempt-id result)))
        (is (= 1 @cancelled))
        (is (= 1 @hooked)))
      (finally
        (dispatch/stop! d)))))

(deftest cancel-resource-normalizes-raw-terminal-receipts
  (let [hook-result (atom nil)
        d (dispatch/create
           {:post! (fn [_] {:ok? true})
            :cancel-resource! (fn [_] @hook-result)})
        entry {:kind :tool :call-id "c1" :attempt-id "a1"}]
    (try
      (reset! hook-result
              {:status :CANCELLED
               :call-id "c1"
               :attempt-id "a1"
               :error {:kind :cancelled}})
      (let [result (dispatch/cancel-resource! d entry)]
        (is (= :cancelled (get-in result [:resource :terminal :status])))
        (is (= "c1" (get-in result [:resource :terminal :payload :call-id]))))

      (reset! hook-result
              {:status :SESSION_ENDED
               :request-id "c1"
               :result {:status :COMPLETED
                        :cancelled? true
                        :exit-code 143}})
      (let [result (dispatch/cancel-resource! d entry)]
        (is (= :cancelled (get-in result [:resource :terminal :status])))
        (is (= {:status :COMPLETED :cancelled? true :exit-code 143}
               (get-in result [:resource :terminal :payload]))))

      (reset! hook-result
              {:status :CANCELLED
               :cancel-after-apply? true
               :call-id "c1"})
      (is (nil? (get-in (dispatch/cancel-resource! d entry)
                        [:resource :terminal]))
          "a protected apply must finish before its terminal is synthesized")

      (reset! hook-result
              {:status :CANCELLING
               :cancel-pending? true
               :call-id "c1"})
      (is (nil? (get-in (dispatch/cancel-resource! d entry)
                        [:resource :terminal]))
          "a deferred cancellation is not a terminal")

      (reset! hook-result
              {:status :OUTCOME_UNKNOWN
               :cancel-unconfirmed? true
               :call-id "c1"})
      (is (= :outcome-unknown
             (get-in (dispatch/cancel-resource! d entry)
                     [:resource :terminal :status]))
          "an unconfirmed call stays unknown, never cancelled")
      (finally
        (dispatch/stop! d)))))

(deftest duplicate-attempt-is-idempotent
  (let [messages (atom [])
        d (dispatch/create
           {:post! (fn [message] (swap! messages conj message) {:ok? true})
            :executors {:tool (fn [_] (Thread/sleep 100) {:status :succeeded})}})]
    (try
      (is (:ok? (dispatch/start! d {:kind :tool :attempt-id "a1" :call-id "c1"})))
      (is (= {:ok? true :already-running? true
              :attempt-id "a1" :kind :tool}
             (dispatch/start! d {:kind :tool :attempt-id "a1"
                                 :call-id "c1"})))
      (finally
        (dispatch/stop! d)))))

(deftest timer-fires-once
  (let [messages (atom [])
        d (dispatch/create
           {:post! (fn [message] (swap! messages conj message) {:ok? true})})]
    (try
      (let [result (dispatch/start! d {:kind :timer :attempt-id "t1"
                                       :call-id "c1"
                                       :retry-at (+ (System/currentTimeMillis) 20)})]
        (is (:ok? result))
        (is (wait-for #(seq (messages-with messages :timer/fire)) 3000)))
      (finally
        (dispatch/stop! d)))))

(deftest timer-start-is-idempotent-by-attempt-id
  (let [d (dispatch/create
           {:post! (fn [_] {:ok? true})})
        retry-at (+ (System/currentTimeMillis) 60000)
        effect {:kind :timer
                :attempt-id "t1"
                :call-id "c1"
                :retry-at retry-at}]
    (try
      (is (:ok? (dispatch/start! d effect)))
      (is (true? (:already-scheduled?
                  (dispatch/start! d effect)))
          "the same attempt must not move the fixed retry instant")
      (is (= 1 (:timers (dispatch/stats d))))
      (is (:ok? (dispatch/start! d (assoc effect :attempt-id "t2"))))
      (is (= 1 (:timers (dispatch/stats d)))
          "a new attempt replaces the old timer without leaking one")
      (finally
        (dispatch/stop! d)))))

(deftest model-gateway-events-and-terminal
  (let [messages (atom [])
        d (dispatch/create
           {:post! (fn [message] (swap! messages conj message) {:ok? true})
            :model-gateway
            {:start! (fn [request deliver!]
                       (deliver! {:event :chunk :text "hel"})
                       (deliver! {:event :chunk :text "lo"})
                       (deliver! {:event :completed :text "hello"})
                       {:attempt-id (:attempt-id request)})
             :cancel! (fn [_] nil)}})]
    (try
      (is (:ok? (dispatch/start! d {:kind :model :attempt-id "a1"
                                    :call-id "c1"
                                    :request {:snapshot {:messages []}}})))
      (is (wait-for #(seq (messages-with messages :dispatch/settled)) 3000))
      (let [events (mapv :event
                         (keep #(get-in % [:event])
                               (messages-with messages :model/event)))]
        (is (= [:chunk :chunk :completed] events)))
      (is (empty? (messages-with messages :call/terminal)))
      (is (= :completed
             (-> (messages-with messages :model/event)
                 last :event :event)))
      (finally
        (dispatch/stop! d)))))

(deftest asynchronous-model-terminal-settles-the-attempt
  (let [messages (atom [])
        deliver-ref (promise)
        d (dispatch/create
           {:post! (fn [message] (swap! messages conj message) {:ok? true})
            :model-gateway
            {:start! (fn [request deliver!]
                       (deliver deliver-ref deliver!)
                       {:attempt-id (:attempt-id request)})
             :cancel! (fn [_] nil)}})]
    (try
      (is (:ok? (dispatch/start! d
                                 {:kind :model
                                  :attempt-id "a1"
                                  :call-id "c1"
                                  :request {:snapshot {:messages []}}})))
      (is (= 1 (count (dispatch/live-attempts d)))
          "the claimed attempt is visible while the gateway is live")
      (future
        ((deref deliver-ref)
         {:event :failed
          :error {:kind :model-tools-unavailable
                  :code :MODEL_TOOLS_UNAVAILABLE
                  :category :permanent}}))
      (is (wait-for #(seq (messages-with messages :dispatch/settled)) 3000))
      (is (empty? (dispatch/live-attempts d))
          "a terminal releases the dispatcher task slot")
      (let [event (:event (first (messages-with messages :model/event)))]
        (is (= :failed (:event event)))
        (is (= "a1" (:attempt-id event)))
        (is (= "c1" (:model-call-id event))))
      (finally
        (dispatch/stop! d)))))

(deftest model-gateway-nil-handle-posts-failed-terminal
  (let [messages (atom [])
        d (dispatch/create
           {:post! (fn [message] (swap! messages conj message) {:ok? true})
            :model-gateway
            {:start! (fn [_request _deliver!] nil)
             :cancel! (fn [_] nil)}})]
    (try
      (let [result (dispatch/start! d
                                    {:kind :model
                                     :attempt-id "a1"
                                     :call-id "c1"
                                     :request {:snapshot {:messages []}}})]
        (is (= :MODEL_START_FAILED (get-in result [:error :code])))
        (is (true? (:posted? result)))
        (let [terminal (first (messages-with messages :call/terminal))]
          (is (= :failed (:status terminal)))
          (is (= :model-start-failed (get-in terminal [:error :kind])))
          (is (= :permanent (get-in terminal [:error :category])))
          (is (= "a1" (:attempt-id terminal)))))
      (finally
        (dispatch/stop! d)))))

(deftest synchronous-start-failure-closes-the-attempt
  (let [messages (atom [])
        d (dispatch/create
           {:post! (fn [message] (swap! messages conj message) {:ok? true})})]
    (try
      (is (= :NO_EXECUTOR
             (get-in (dispatch/start! d {:kind :tool :attempt-id "a1"
                                         :call-id "c1"})
                     [:error :code])))
      (is (= :failed (:status (first (messages-with messages :call/terminal)))))
      (finally
        (dispatch/stop! d)))))
