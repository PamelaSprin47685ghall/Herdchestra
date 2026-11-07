(ns hc.compose.owner.session-run-bridge-test
  "Real Owner-runtime smoke for explicit history regeneration.

  The test uses the real session command port, the real session-run bridge,
  the real Owner guard/claim/dispatch loop, and a deterministic fake model
  gateway.  It proves the model terminal appends a version to the target
  message instead of creating a second assistant message."
  (:require [clojure.test :refer [deftest is]]
            [hc.compose.owner :as owner]
            [hc.server.history :as history]
            [hc.server.history.message :as message]
            [hc.server.history.run-port :as run-port]
            [hc.server.sessions :as sessions]
            [hc.server.sessions.ids :as ids]
            [hc.server.sessions.ports :as ports]))

(defn- add-message
  [session message-id role text now]
  (let [result (message/new-message message-id role text {:now now})]
    (is (:ok? result))
    (-> session
        (assoc-in [:messages message-id] (:message result))
        (update :message-order (fnil conj []) message-id))))

(defn- session-with-history
  []
  (let [created (sessions/new-session
                 {:session-id "bridge-session"
                  :owner-id "bridge-owner"
                  :now 1000
                  :id-source (ids/counter-source {:prefix "bridge"})})
        session (:session created)]
    (-> session
        (assoc :model-binding {:connection-id "local"
                               :model-entry-id "test"})
        (add-message "m1" :user "question" 1100)
        (add-message "m2" :assistant "old answer" 1200)
        (assoc :activity-at 1200))))

(defn- command!
  [owner command-id payload]
  ((:submit-command! owner)
   {:command-id command-id
    :command-epoch 1
    :payload payload}))

(deftest owner-regenerate-terminal-appends-one-version
  (let [session (session-with-history)
        persistence (ports/memory-persistence)
        committed (sessions/commit-session! persistence session)
        _ (is (= :committed (:status committed)))
        session (:session committed)
        session-port (sessions/session-port
                      {:delegate history/apply-command
                       :outbound-context history/outbound-context
                       :persistence persistence})
        run-port {:start (fn [session effect opts]
                           (run-port/start session effect opts))
                  :accept (fn [session request]
                            (run-port/accept session request))}
        gateway {:start! (fn [_request deliver!]
                           (deliver! {:event :completed
                                      :text "regenerated answer"
                                      :usage {:prompt-tokens 2
                                              :completion-tokens 3}})
                           {:ok? true})
                 :cancel! (fn [_] nil)}
        owner (owner/start-owner!
               {:session-id "bridge-session"
                :store (owner/memory-store)
                :initial-session session
                :session-port session-port
                :session-run-port run-port
                :model-gateway gateway
                :close-store? true})]
    (try
      (let [receipt (command! owner "bridge-c1"
                              {:kind :history/regenerate
                               :session-id "bridge-session"
                               :message-id "m2"})]
        (is (= :accepted (:status receipt)))
        ((:await-idle! owner) 8000)
        (let [live (get-in ((:snapshot owner)) [:state :session])
              versions (message/message-history live "m2")]
          (is (= 2 (count versions)))
          (is (= "old answer" (get-in versions [0 :content])))
          (is (= "regenerated answer" (get-in versions [1 :content])))
          (is (= :regenerated (get-in versions [1 :source])))
          (is (= {:prompt-tokens 2 :completion-tokens 3}
                 (get-in versions [1 :usage])))
          (is (= 1 (get-in live [:messages "m2" :current-version])))
          (is (= 2 (count (:message-order live))))
          (is (= :completed (get-in live [:runs
                                          (get-in receipt [:run-id])
                                          :state])))
          (is (empty? (history/check-invariants live)))))
      (finally
        ((:stop! owner))))))

(deftest missing-run-port-closes-the-accepted-run
  (let [session (session-with-history)
        persistence (ports/memory-persistence)
        committed (sessions/commit-session! persistence session)
        _ (is (= :committed (:status committed)))
        session (:session committed)
        session-port (sessions/session-port
                      {:delegate history/apply-command
                       :outbound-context history/outbound-context
                       :persistence persistence})
        owner (owner/start-owner!
               {:session-id "bridge-session"
                :store (owner/memory-store)
                :initial-session session
                :session-port session-port
                :session-run-port {}
                :model-gateway {:start! (fn [_ _] nil)
                                :cancel! (fn [_] nil)}
                :close-store? true})]
    (try
      (let [receipt (command! owner "bridge-c2"
                              {:kind :history/regenerate
                               :session-id "bridge-session"
                               :message-id "m2"})]
        (is (= :accepted (:status receipt)))
        ((:await-idle! owner) 8000)
        (let [live (get-in ((:snapshot owner)) [:state :session])
              run-id (get-in receipt [:run-id])
              run (get-in live [:runs run-id])]
          (is (= :cancelled (:state run)))
          (is (= :SESSION_RUN_PORT_UNAVAILABLE
                 (get-in run [:failure :reason])))
          (is (= :cancelled
                 (get-in live [:calls (get-in run [:call-order 0]) :state])))
          (is (not= :running (:state run)))))
      (finally
        ((:stop! owner))))))
