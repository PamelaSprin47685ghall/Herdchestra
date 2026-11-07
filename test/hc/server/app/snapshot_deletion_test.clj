(ns hc.server.app.snapshot-deletion-test
  "Behavioral checks for permanent-deletion anti-resurrection in the client
  baseline.  Deleted sessions and hidden messages must not enter the client
  tables, while live owner-only conversation extras must survive."
  (:require [clojure.test :refer [deftest is testing]]
            [hc.server.app.snapshot :as snapshot]
            [hc.server.history.message :as message]
            [hc.server.sessions :as sessions]
            [hc.server.sessions.ids :as ids]))

(defn- base-session
  [session-id]
  (:session (sessions/new-session
             {:session-id session-id
              :owner-id (str "owner-" session-id)
              :now 1000
              :id-source (ids/counter-source {:prefix session-id})})))

(defn- add-message
  [session message-id role text opts]
  (let [result (message/new-message message-id role text
                                    (assoc opts :now (or (:now opts) 1000)))]
    (is (:ok? result) (pr-str (:error result)))
    (-> session
        (assoc-in [:messages message-id] (:message result))
        (update :message-order (fnil conj []) message-id))))

(defn- owner-conversation
  [messages]
  {:state {:conversation messages
           :commands {:command-epoch 3}}})

(deftest deleted-session-owner-conversation-is-not-published
  (let [visible (base-session "s1")
        baseline (snapshot/baseline
                  {:sessions [visible]
                   :session-records [visible]
                   :owner-snapshots
                   {"s1" (owner-conversation [])
                    "s2" (owner-conversation
                          [{:message-id "m-deleted"
                            :role :assistant
                            :content "must not return"}])}
                   :active-session "s1"})]
    (is (not (contains? (:session/by-id baseline) "s2")))
    (is (not (contains? (:message/by-id baseline) "m-deleted")))
    (is (not (contains? (set (:message/order baseline)) "m-deleted")))
    (is (= "s1" (:session/active-id baseline)))))

(deftest deleted-or-folded-assistant-message-is-not-published
  (let [session (-> (base-session "s1")
                    (add-message "m0" :user "visible" {})
                    (add-message "m1" :assistant "deleted answer" {})
                    (add-message "m2" :assistant "folded answer" {})
                    (assoc-in [:messages "m1" :deleted?] true)
                    (assoc-in [:messages "m2" :folded?] true))
        baseline (snapshot/baseline
                  {:sessions [session]
                   :session-records [session]
                   :owner-snapshots
                   {"s1" (owner-conversation
                          [{:message-id "m1"
                            :role :assistant
                            :content "deleted answer"}
                           {:message-id "m2"
                            :role :assistant
                            :content "folded answer"}
                           {:message-id "extra-report"
                            :role :assistant
                            :content "live extra"}])}
                   :active-session "s1"})]
    (is (contains? (:message/by-id baseline) "m0"))
    (is (not (contains? (:message/by-id baseline) "m1")))
    (is (not (contains? (:message/by-id baseline) "m2")))
    (is (contains? (:message/by-id baseline) "extra-report")
        "owner-only live extras are not tombstoned and must survive")
    (is (= (set (keys (:message/by-id baseline)))
           (set (:message/order baseline))))))

(deftest deleted-active-session-falls-back-to-a-visible-session
  (let [visible (base-session "s1")
        baseline (snapshot/baseline
                  {:sessions [visible]
                   :session-records [visible]
                   :owner-snapshots
                   {"s1" (owner-conversation [])
                    "s2" (owner-conversation
                          [{:message-id "m2"
                            :role :assistant
                            :content "deleted active"}])}
                   :active-session "s2"})]
    (is (= "s1" (:session/active-id baseline)))
    (is (not (contains? (:session/by-id baseline) "s2")))
    (is (not (contains? (:message/by-id baseline) "m2")))
    (is (= #{"s1"} (set (keys (:session/by-id baseline)))))))
