(ns hc.server.app.owner-event-broadcast-test
  "Owner advisory effects reach the AG-UI feed, not the model gateway.

  The session Owner used to broadcast `:hc/ask` through its *model* gateway:
  that map has no feed, the broadcast threw, and the catch hid it.  A real
  S1 ask parked on the server while the browser saw nothing.  These tests
  hold the forwarder to the real gateway resolved from the system map."
  (:require [clojure.test :refer [deftest is testing]]
            [hc.compose.owner :as owner]
            [hc.compose.owner.state :as state]
            [hc.server.app.system :as system]
            [hc.server.gateway :as gateway]
            [hc.server.gateway.feed :as feed]))

(defn- test-gateway
  []
  (let [events (atom [])
        gw {:feed (feed/create {:now-fn (constantly "2026-11-05T00:00:00Z")})
            :stopped? (atom false)
            :opts {:max-message-bytes (* 1024 1024)}
            :socket {:send-fn (fn [_ event] (swap! events conj event))}}]
    (is (:ok? (gateway/subscribe! gw "browser-1" 1
                                  (fn [event] (swap! events conj event)))))
    (reset! events [])
    (assoc gw :events events)))

(defn- forward!
  [system-ref session-id event]
  (#'system/forward-owner-event! system-ref session-id event))

(defn- wait-for
  [pred timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (pred) true
        (< (System/currentTimeMillis) deadline)
        (do (Thread/sleep 20) (recur))
        :else false))))

(deftest parked-ask-reaches-the-ag-ui-feed
  (let [gw (test-gateway)
        system-ref (atom {:gateway gw})
        ask {:type :hc/ask
             :ask-id "ask-call-1/tool-0"
             :name "ask-number"
             :program-name "ask-number"
             :question "E2E-08 请给出一个数字"
             :parked? true
             :call-id "call-1/tool-0"
             :session-id "session-1"
             :at 1793858702907}]
    (forward! system-ref "session-1" ask)
    (let [events @(:events gw)
          custom (first (filter #(= "CUSTOM" (:type %)) events))]
      (is (= 1 (count events)) (pr-str events))
      (is (= "hc/ask" (:name custom)))
      (is (= "ask-call-1/tool-0" (get-in custom [:value :ask-id])))
      (is (= "E2E-08 请给出一个数字" (get-in custom [:value :question])))
      (is (= "ask-number" (get-in custom [:value :name])))
      (is (= "session-1" (get-in custom [:value :thread-id])))
      (is (= "session-1" (get-in custom [:value :session-id]))))))

(deftest ask-resolved-and-worker-results-also-reach-the-feed
  (let [gw (test-gateway)
        system-ref (atom {:gateway gw})]
    (forward! system-ref "session-1"
              {:type :hc/ask-resolved :ask-id "ask-1" :name "ask-prog"})
    (forward! system-ref "session-1"
              {:type :worker/program-result :job-id "job-1"
               :outcome :applied})
    (is (= ["hc/ask-resolved" "worker/program-result"]
           (mapv :name @(:events gw))))))

(deftest unrelated-effects-do-not-consume-the-feed
  (let [gw (test-gateway)
        system-ref (atom {:gateway gw})]
    (forward! system-ref "session-1" {:type :owner/blocked})
    (forward! system-ref "session-1" {:type :swarm/wake-all})
    (is (empty? @(:events gw)))))

(deftest a-missing-gateway-is-not-an-error
  (let [system-ref (atom {})]
    (is (nil? (forward! system-ref "session-1"
                        {:type :hc/ask :ask-id "ask-1"})))
    (is (nil? (forward! system-ref "session-1"
                        {:type :hc/ask :ask-id "ask-1"})))
    (testing "the model gateway shape is not a feed; it must not throw"
      (reset! system-ref {:gateway {:start! (fn [_ _] nil)
                                    :cancel! (fn [_] nil)}})
      (is (nil? (forward! system-ref "session-1"
                          {:type :hc/ask :ask-id "ask-1"}))))))

(deftest owner-outbox-reaches-the-browser-feed
  ;; The whole server chain: a parked plan tool result commits an outbox
  ;; entry, the runtime drains it into :on-effect!, and the app forwarder
  ;; broadcasts it on the AG-UI gateway the browser subscribed to.
  (let [base (state/initial-state
              {:session-id "ask-forward"
               :models {:main "main-model" :task "task-model"}})
        [base call] (state/create-call
                     base
                     {:call-id "ask-call"
                      :kind :tool
                      :role :plan
                      :lane :plan
                      :tenure-id "tenure-1"
                      :agent-id :plan
                      :created-at 1})
        parked {:status :succeeded
                :value {:state :parked
                        :payload {:question "E2E-08 请给出一个数字"}
                        :name "ask-e2e-08"
                        :call-id "ask-call"}}
        seeded (state/terminal-call base call :succeeded parked nil 2)
        gw (test-gateway)
        system-ref (atom {:gateway gw})
        effects (atom [])
        handle (owner/start-owner!
                {:session-id "ask-forward"
                 :ephemeral? true
                 :initial-state seeded
                 :on-effect! (fn [event]
                               (swap! effects conj event)
                               (forward! system-ref "ask-forward" event))})]
    (try
      ((:submit! handle) {:type :wake})
      (is (wait-for (fn [] (some #(= :hc/ask (:type %)) @effects)) 3000)
          (pr-str @effects))
      (is (wait-for #(seq @(:events gw)) 3000))
      (let [custom (first (filter #(= "hc/ask" (:name %)) @(:events gw)))]
        (is (some? custom) (pr-str @(:events gw)))
        (is (= "E2E-08 请给出一个数字" (get-in custom [:value :question])))
        (is (= "ask-ask-call" (get-in custom [:value :ask-id])))
        (is (= "ask-forward" (get-in custom [:value :thread-id]))))
      (finally
        ((:stop! handle))))))
