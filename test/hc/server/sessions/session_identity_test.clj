(ns hc.server.sessions.session-identity-test
  "Deterministic fixture for the E2E02 restart report.

  Two sessions carry the same display model name from two providers.  Each
  user message is appended through its own session id and binding; the
  committed records must keep exactly one message each.  This distinguishes a
  server cross-session leak from a harness that selected/sent to the wrong
  session id."
  (:require [clojure.test :refer [deftest is testing]]
            [hc.server.sessions :as sessions]
            [hc.server.sessions.config :as config]
            [hc.server.sessions.ids :as ids]
            [hc.server.sessions.ports :as ports]
            [hc.server.sessions.test-support :as support]))

(defn- two-provider-config
  []
  (let [alpha (support/build-context
               {:connection-id "alpha"
                :connection-name "Alpha Provider"
                :provider-model-id "same-model"
                :display-name "Same Model"})
        beta (support/build-context
              {:connection-id "beta"
               :connection-name "Beta Provider"
               :provider-model-id "same-model"
               :display-name "Same Model"})
        added (config/add-connection
               (:config alpha)
               (get-in (:config beta) [:connections "beta"])
               1000)]
    (is (:ok? added))
    (:config added)))

(defn- model-id
  [config connection-id]
  (first (get-in config [:connections connection-id :model-order])))

(defn- create!
  [config session-id owner-id connection-id now]
  (sessions/new-session
   {:session-id session-id
    :owner-id owner-id
    :config config
    :connection-id connection-id
    :model-entry-id (model-id config connection-id)
    :id-source (ids/counter-source)
    :now now}))

(defn- append!
  [session content now]
  (sessions/apply-command
   session
   {:kind :session/append-message
    :role :user
    :content content}
   {:now now}))

(deftest sessions-keep-their-own-messages-and-bindings
  (let [config (two-provider-config)
        alpha (create! config "s-alpha" "o-alpha" "alpha" 1000)
        beta (create! config "s-beta" "o-beta" "beta" 2000)
        _ (is (:ok? alpha))
        _ (is (:ok? beta))
        alpha-message (append! (:session alpha) "E2E02 Alpha 消息" 3000)
        beta-message (append! (:session beta) "E2E02 Beta 消息" 4000)
        _ (is (:ok? alpha-message))
        _ (is (:ok? beta-message))
        alpha-session (:session alpha-message)
        beta-session (:session beta-message)]
    (testing "each in-memory session has only its own message"
      (is (= ["E2E02 Alpha 消息"]
             (mapv :content (sessions/visible-messages alpha-session))))
      (is (= ["E2E02 Beta 消息"]
             (mapv :content (sessions/visible-messages beta-session)))))
    (testing "each session keeps its own provider binding"
      (is (= "alpha"
             (get-in alpha-session [:model-binding :connection-id])))
      (is (= "beta"
             (get-in beta-session [:model-binding :connection-id]))))
    (testing "the committed records stay isolated"
      (let [persistence (ports/memory-persistence)
            _ (is (= :committed
                     (:status (sessions/commit-session!
                               persistence alpha-session))))
            _ (is (= :committed
                     (:status (sessions/commit-session!
                               persistence beta-session))))
            stored-alpha (:session (ports/read-session persistence "s-alpha"))
            stored-beta (:session (ports/read-session persistence "s-beta"))]
        (is (= ["E2E02 Alpha 消息"]
               (mapv :content (sessions/visible-messages stored-alpha))))
        (is (= ["E2E02 Beta 消息"]
               (mapv :content (sessions/visible-messages stored-beta))))
        (is (= "alpha"
               (get-in stored-alpha [:model-binding :connection-id])))
        (is (= "beta"
               (get-in stored-beta [:model-binding :connection-id])))))))
