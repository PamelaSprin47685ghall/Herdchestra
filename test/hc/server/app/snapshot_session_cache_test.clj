(ns hc.server.app.snapshot-session-cache-test
  "Non-active session summary rows are cached across publishes.

  The cache key must carry the session `:revision`; `list-sessions`
  summaries used to omit it, so every row shared the key `[nil epoch]` and
  a history change that did not move the command epoch (for example an
  undo that flips one compression from `:applied` to `:reverted`) kept
  publishing the stale `:session/by-id` context forever.  The live client
  then never saw the revert even though `:session/history` was correct."
  (:require [clojure.test :refer [deftest is testing]]
            [hc.server.app.snapshot :as snapshot]
            [hc.server.sessions :as sessions]))

(defn- record
  "One committed session record whose only context is one compression."
  [revision state]
  {:session-id "s1"
   :revision revision
   :updated-at revision
   :status :idle
   :message-order []
   :messages {}
   :drafts {}
   :context {:compressions {"c1" {:compression-id "c1"
                                  :state state
                                  :message-ids []
                                  :applied-at 1
                                  :updated-at revision}}
             :compression-order ["c1"]}})

(defn- active-summary
  []
  (sessions/session-summary
   {:session-id "s2" :revision 5 :status :idle
    :message-order [] :messages {} :drafts {}}))

(defn- compression-state
  [tree session-id]
  (get-in tree [:session/by-id session-id :context :compressions 0 :state]))

(deftest non-active-summary-cache-follows-the-session-revision
  (let [cache (atom {})
        ;; `sessions` is the `list-sessions` projection; building it through
        ;; `sessions/session-summary` keeps this test honest about the
        ;; fields the publisher actually receives.
        opts (fn [revision state]
               {:sessions [(active-summary)
                           (sessions/session-summary (record revision state))]
                :session-records [(record revision state)]
                :owner-snapshots
                {"s2" {:state {:controller-id "client-1"
                               :observer-ids ["client-2" "client-3"]}}}
                :active-session "s2"
                :config {}
                :sync {:phase :synced}
                :session-cache cache})
        applied (snapshot/baseline (opts 1 :applied))
        reverted (snapshot/baseline (opts 2 :reverted))]
    (testing "the first publish carries the applied compression"
      (is (= :applied (compression-state applied "s1"))))
    (testing "a revision bump republishes the reverted state"
      (is (= :reverted (compression-state reverted "s1"))))
    (testing "the history projection agrees with the summary row"
      (is (= :reverted
             (get-in reverted [:session/history "s1" :context
                               :compressions 0 :state]))))
    (testing "the active Owner's presence reaches the summary"
      (is (= "client-1"
             (get-in reverted [:session/by-id "s2"
                               :session/controller-id])))
      (is (= ["client-2" "client-3"]
             (get-in reverted [:session/by-id "s2"
                               :session/observer-ids]))))
    (testing "a session without an Owner publishes empty presence"
      (is (contains? (get-in reverted [:session/by-id "s1"])
                     :session/controller-id))
      (is (nil? (get-in reverted [:session/by-id "s1"
                                  :session/controller-id])))
      (is (= [] (get-in reverted [:session/by-id "s1"
                                  :session/observer-ids]))))))

(deftest list-session-summaries-carry-the-revision
  (testing "the snapshot cache key can see a committed change"
    (is (= 7 (:revision (sessions/session-summary (record 7 :applied)))))))
