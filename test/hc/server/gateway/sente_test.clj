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
