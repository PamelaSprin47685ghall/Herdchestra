(ns hc.server.gateway.sente-test
  (:require [clojure.core.async :as async]
            [clojure.test :refer [deftest is testing]]
            [hc.server.gateway.sente :as gateway-sente]
            [taoensso.sente :as sente]))

(deftest auth-origins-must-be-a-set-or-all
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"allowed-origins"
       (gateway-sente/start!
        (fn [_] nil)
        {:sch-adapter ::adapter
         :allowed-origins ["http://app.example"]}))))

(deftest auth-policy-is-separate-from-sentes-duplicate-origin-check
  (let [captured (atom nil)
        channel (async/chan)
        socket {:ch-recv channel
                :send-fn (fn [& _] nil)}
        handle (with-redefs [sente/make-channel-socket!
                             (fn [_ opts]
                               (reset! captured opts)
                               socket)]
                 (gateway-sente/start!
                  (fn [_] nil)
                  {:sch-adapter ::adapter
                   :allowed-origins #{"http://app.example"}
                   :trusted-hosts #{"app.example"}}))]
    (try
      (testing "Sente's weaker duplicate check stays disabled"
        (is (= :all (:allowed-origins @captured))
            "a set here would reject non-browser clients with no Origin"))
      (testing "the installed hook still enforces the configured policy"
        (let [authorize (:authorized?-fn @captured)]
          (is (true? (authorize
                      {:remote-addr "127.0.0.1"
                       :headers {"host" "app.example:7575"
                                 "origin" "http://app.example"}})))
          (is (false? (authorize
                       {:remote-addr "127.0.0.1"
                        :headers {"host" "app.example:7575"
                                  "origin" "http://evil.example"}})))
          (is (true? (authorize
                      {:remote-addr "127.0.0.1"
                       :headers {"host" "app.example:7575"}})))
          (is (false? (authorize
                       {:remote-addr "127.0.0.1"
                        :headers {"host" "evil.example:7575"}})))))
      (finally
        ((:stop! handle))))))

(deftest remote-mode-requires-an-authorization-hook
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"remote mode requires"
       (gateway-sente/start!
        (fn [_] nil)
        {:sch-adapter ::adapter
         :allow-remote? true}))))

(deftest allow-remote-must-be-a-boolean
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"allow-remote"
       (gateway-sente/start!
        (fn [_] nil)
        {:sch-adapter ::adapter
         :allow-remote? "true"}))))

(deftest remote-mode-with-an-authorization-hook-starts
  (let [channel (async/chan)
        socket {:ch-recv channel
                :send-fn (fn [& _] nil)}
        handle (with-redefs [sente/make-channel-socket!
                             (fn [_ _] socket)]
                 (gateway-sente/start!
                  (fn [_] nil)
                  {:sch-adapter ::adapter
                   :allow-remote? true
                   :authorized?-fn (fn [_] true)}))]
    (try
      (is (some? handle))
      (finally
        ((:stop! handle))))))

(deftest events-are-dispatched-to-handlers-off-the-receive-channel
  (let [channel (async/chan)
        received (atom [])
        socket {:ch-recv channel
                :send-fn (fn [& _] nil)}
        handle (with-redefs [sente/make-channel-socket!
                             (fn [_ _] socket)]
                 (gateway-sente/start!
                  (fn [event-msg] (swap! received conj event-msg))
                  {:sch-adapter ::adapter}))]
    (try
      (async/>!! channel {:event [:hc/test 1] :uid "u1" :client-id "u1"})
      (async/>!! channel {:event [:hc/test 2] :uid "u1" :client-id "u1"})
      (async/>!! channel {:event [:hc/test 3] :uid "u2" :client-id "u2"})
      (Thread/sleep 50)
      (let [deadline (+ (System/currentTimeMillis) 2000)]
        (loop []
          (when (and (< (count @received) 3)
                     (< (System/currentTimeMillis) deadline))
            (Thread/sleep 10)
            (recur))))
      (is (= 3 (count @received)))
      (is (= ["u1" "u1" "u2"] (mapv :uid @received))
          "per-uid ordering is preserved across the worker pool")
      (finally
        ((:stop! handle))))))

(deftest handler-errors-are-collected-not-fatal
  (let [channel (async/chan)
        errors (atom [])
        socket {:ch-recv channel
                :send-fn (fn [& _] nil)}
        handle (with-redefs [sente/make-channel-socket!
                             (fn [_ _] socket)]
                 (gateway-sente/start!
                  (fn [event-msg]
                    (when (= :boom (:event event-msg))
                      (throw (ex-info "handler boom" {:code :BOOM}))))
                  {:sch-adapter ::adapter
                   :on-error errors}))]
    (try
      (async/>!! channel {:event :boom :uid "u1" :client-id "u1"})
      (async/>!! channel {:event :ok :uid "u1" :client-id "u1"})
      (Thread/sleep 50)
      (let [deadline (+ (System/currentTimeMillis) 2000)]
        (loop []
          (when (and (empty? @errors)
                     (< (System/currentTimeMillis) deadline))
            (Thread/sleep 10)
            (recur))))
      (is (= 1 (count @errors)))
      (is (= :BOOM (-> @errors first ex-data :code)))
      (finally
        ((:stop! handle))))))

(deftest stop-drains-workers-and-closes-mailboxes
  (let [channel (async/chan)
        socket {:ch-recv channel
                :send-fn (fn [& _] nil)}
        handle (with-redefs [sente/make-channel-socket!
                             (fn [_ _] socket)]
                 (gateway-sente/start!
                  (fn [_] nil)
                  {:sch-adapter ::adapter}))]
    (let [result ((:stop! handle))]
      (is (true? (:ok? result)))
      (is (true? (:drained? result))
          "workers must drain and exit so stop reports a clean drain"))))

(deftest ws-keepalive-options-pass-through-to-sente
  (let [captured (atom nil)
        channel (async/chan)
        socket {:ch-recv channel
                :send-fn (fn [& _] nil)}
        handle (with-redefs [sente/make-channel-socket!
                             (fn [_ opts]
                               (reset! captured opts)
                               socket)]
                 (gateway-sente/start!
                  (fn [_] nil)
                  {:sch-adapter ::adapter
                   :ws-kalive-ms 30000
                   :ws-ping-timeout-ms 15000}))]
    (try
      (is (= 30000 (:ws-kalive-ms @captured)))
      (is (= 15000 (:ws-ping-timeout-ms @captured)))
      (finally
        ((:stop! handle))))))
