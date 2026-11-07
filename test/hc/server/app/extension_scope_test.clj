(ns hc.server.app.extension-scope-test
  "Trusted extension scope is derived from session state, never the payload."
  (:require [clojure.test :refer [deftest is testing]]
            [hc.server.app.commands :as commands]
            [hc.server.sessions.registry :as registry]))

(defn- scope-for
  [session payload]
  (with-redefs [registry/get-session
                (fn [_ session-id]
                  {:status :found :session (assoc session :session-id session-id)})]
    ((var-get #'commands/extension-scope)
     {:registry {}}
     payload)))

(deftest role-fails-closed-to-viewer
  (let [result (scope-for {:assistant-id "a1"} {:session-id "s1"})]
    (is (= :viewer (get-in result [:scope :role])))
    (is (= #{} (get-in result [:scope :assistant-servers])))
    (is (= {:mode :follow} (get-in result [:scope :session])))))

(deftest role-follows-the-session-capability-scope
  (testing "a write capability grants operator"
    (is (= :operator
           (get-in (scope-for {:capability-scope {:mcp #{:mcp/write}}}
                              {:session-id "s1"})
                   [:scope :role]))))
  (testing "a destructive capability grants admin"
    (is (= :admin
           (get-in (scope-for {:capability-scope {:mcp #{:mcp/write
                                                        :mcp/destructive}}}
                              {:session-id "s1"})
                   [:scope :role]))))
  (testing "an unrelated capability set grants viewer"
    (is (= :viewer
           (get-in (scope-for {:capability-scope {:tools #{:shell}}}
                              {:session-id "s1"})
                   [:scope :role])))))

(deftest payload-claims-cannot-raise-the-role
  (let [result (scope-for {:capability-scope #{}}
                          {:session-id "s1"
                           :scope {:role :admin
                                   :assistant-servers #{:everything}}})]
    (is (= :viewer (get-in result [:scope :role])))
    (is (= #{} (get-in result [:scope :assistant-servers])))))
