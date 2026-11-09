(ns hc.server.gateway-feed-invariants-test
  "Cross-layer invariants between the atomic gateway feed and the pure client
  adapter (AGENTS.md §7.3).

  The server feed owns the epoch, the monotonic sequence and the full tree.
  The client adapter owns snapshot replacement, stale-epoch rejection and the
  normalized display copy.  These tests drive both pure layers directly; they
  start no Sente, HTTP or application server."
  (:require [clojure.test :refer [deftest is testing]]
            [hc.client.sync :as sync]
            [hc.server.gateway.feed :as feed])
  (:import [java.util Random]))

(defn- collector
  []
  (let [events (atom [])]
    {:events events
     :send-fn (fn [event] (swap! events conj event))}))

(defn- connected-client
  []
  (-> (sync/initial-state)
      sync/connect
      sync/channel-opened))

(defn- apply-events!
  [client events]
  (doseq [event @events]
    (swap! client sync/receive event))
  (reset! events []))

(deftest snapshot-replacement-does-not-revive-removed-entities
  (let [sink (collector)
        initial {:session/by-id {"a" {:id "a" :status "running"}
                                 "b" {:id "b" :status "running"}}
                 :sync {:command-epoch 3}}
        f (feed/create {:snapshot initial :now-fn (constantly "t0")})
        _ (feed/subscribe! f "u1" 1 (:send-fn sink))
        client (atom (connected-client))]
    (apply-events! client (:events sink))
    (is (= #{"a" "b"}
           (set (keys (get-in @client [:db :session/by-id])))))

    (testing "the delta removes B from the server tree and the client view"
      (let [removed (feed/publish-delta! f [{"op" "remove"
                                             "path" "/session~1by-id/b"}])]
        (is (:ok? removed))
        (is (= 1 (:seq removed)))
        (is (not (contains? (get-in (feed/snapshot f)
                                    [:snapshot :session/by-id])
                            "b")))
        (apply-events! client (:events sink))
        (is (= #{"a"}
               (set (keys (get-in @client [:db :session/by-id])))))))

    (testing "a new snapshot replaces the tree instead of merging into it"
      (let [fresh {:session/by-id {"a" {:id "a" :status "idle"}}
                   :sync {:command-epoch 3}}
            replaced (feed/publish-snapshot! f fresh {})
            ;; A Fulcro database may still hold the pre-removal table.  The
            ;; merge must drop every server-owned key before adding the new
            ;; view, or B would reappear purely from the old database.
            stale-db {:ui/draft {:text "keep"}
                      :session/by-id {"a" {:id "a" :status "running"}
                                      "b" {:id "b" :status "running"}}}]
        (is (:ok? replaced))
        (is (= 2 (:seq replaced)))
        (apply-events! client (:events sink))
        (is (= #{"a"}
               (set (keys (get-in @client [:db :session/by-id]))))
            "the adapter view drops B on snapshot replacement")
        (is (not (contains? (get-in @client [:server :session/by-id]) "b")))
        (let [merged (sync/merge-into-db stale-db @client)]
          (is (= #{"a"} (set (keys (:session/by-id merged))))
              "the Fulcro merge cannot revive the removed entity")
          (is (= "idle" (get-in merged [:session/by-id "a" :status])))
          (is (= {:text "keep"} (:ui/draft merged))
              "local UI keys survive the replacement"))))))

(deftest late-delta-from-an-old-epoch-is-dropped
  (let [sink (collector)
        f (feed/create {:snapshot {:n 0} :now-fn (constantly "t0")})
        _ (feed/subscribe! f "u1" 1 (:send-fn sink))
        client (atom (connected-client))]
    (apply-events! client (:events sink))
    (let [early (feed/publish-delta! f [{"op" "add"
                                         "path" "/early"
                                         "value" true}])
          early-event (last @(:events sink))]
      (is (:ok? early))
      (is (= 1 (:seq early-event)))
      (is (= 1 (:epoch early-event)))
      (apply-events! client (:events sink))
      (is (= 1 (:seq @client)))
      (is (true? (get-in @client [:server :early])))

      (testing "a new epoch replaces the tree and resets the sequence"
        (let [switch (feed/publish-snapshot! f {:n 10} {:new-epoch? true})
              switch-event (last @(:events sink))]
          (is (:ok? switch))
          (is (= 2 (:epoch switch)))
          (is (= 0 (:seq switch)))
          (is (= 2 (:epoch switch-event)))
          (is (= 0 (:seq switch-event)))
          (apply-events! client (:events sink))
          (is (= 2 (:epoch @client)))
          (is (= 0 (:seq @client)))
          (is (not (contains? (:server @client) :early)))))

      (testing "a late delta stamped with the old epoch mutates nothing"
        (let [before @client
              after (sync/receive before early-event)]
          (is (= :state-delta (get-in after [:last-outcome :kind])))
          (is (= :dropped (get-in after [:last-outcome :result])))
          (is (= :stale-epoch (get-in after [:last-outcome :reason])))
          (is (= (:server before) (:server after)))
          (is (= (:db before) (:db after)))
          (is (= (:seq before) (:seq after)))
          (is (= :synced (sync/phase after)))
          (is (true? (sync/command-allowed? after)))
          (is (empty? @(:events sink))
              "replaying the event emits no server event")))

      (testing "the feed stamps new deltas with the current epoch"
        (let [next-result (feed/publish-delta! f [{"op" "add"
                                                   "path" "/late"
                                                   "value" 1}])
              next-event (last @(:events sink))]
          (is (:ok? next-result))
          (is (= 2 (:epoch next-event)))
          (is (= 1 (:seq next-event))))))))

(deftest published-sequences-never-repeat-or-regress
  (let [sink (collector)
        f (feed/create {:snapshot {} :now-fn (constantly "t0")})
        _ (feed/subscribe! f "u1" 1 (:send-fn sink))
        client (atom (connected-client))
        _ (apply-events! client (:events sink))
        ;; A fixed seed keeps the failure reproducible while still mixing the
        ;; three publishing paths.
        rng (Random. 20261109)
        steps 48
        seqs (loop [index 0
                    previous 0
                    seen []]
               (if (= index steps)
                 seen
                 (let [kind (.nextInt rng 3)
                       result (case kind
                                0 (feed/publish-delta!
                                   f [{"op" "add"
                                       "path" (str "/key-" index)
                                       "value" index}])
                                1 (feed/publish-snapshot! f {:step index} {})
                                2 (feed/publish-messages!
                                   f [{:id (str "m-" index) :role "user"}] {}))
                       events @(:events sink)
                       event (first events)
                       expected (inc previous)]
                   (is (:ok? result)
                       (str "publish " index " failed: " (pr-str result)))
                   (is (= expected (:seq result)))
                   (is (= 1 (count events))
                       "one accepted publish emits one event")
                   (is (= expected (:seq event)))
                   (is (= 1 (:epoch event)))
                   (is (or (empty? seen) (< (peek seen) expected))
                       "the new sequence is strictly greater than the last")
                   (doseq [event events]
                     (swap! client sync/receive event))
                   (reset! (:events sink) [])
                   (is (= expected (:seq @client)))
                   (is (= :synced (sync/phase @client)))
                   (recur (inc index) expected (conj seen expected)))))]
    (is (= (vec (range 1 (inc steps))) seqs))
    (is (= steps (count (set seqs)))))

  (testing "a new epoch is the only reset and it restarts at zero"
    (let [sink (collector)
          f (feed/create {:snapshot {} :now-fn (constantly "t0")})
          _ (feed/subscribe! f "u1" 1 (:send-fn sink))
          client (atom (connected-client))]
      (apply-events! client (:events sink))
      (dotimes [index 3]
        (feed/publish-delta! f [{"op" "add"
                                 "path" (str "/k" index)
                                 "value" index}])
        (apply-events! client (:events sink)))
      (is (= 3 (:seq @client)))
      (let [result (feed/publish-snapshot! f {:clean true} {:new-epoch? true})
            event (last @(:events sink))]
        (is (= 2 (:epoch result)))
        (is (= 0 (:seq result)))
        (is (= 0 (:seq event)))
        (swap! client sync/receive event)
        (is (= 2 (:epoch @client)))
        (is (= 0 (:seq @client)))
        (let [delta (feed/publish-delta! f [{"op" "add"
                                             "path" "/after-reset"
                                             "value" 1}])
              delta-event (last @(:events sink))]
          (is (= 1 (:seq delta)))
          (is (= 2 (:epoch delta-event))))))))

(deftest ack-window-forces-a-full-baseline
  (let [sink (collector)
        f (feed/create {:snapshot {}
                        :max-pending 1
                        :now-fn (constantly "t0")})
        _ (feed/subscribe! f "u1" 1 (:send-fn sink))
        client (atom (connected-client))]
    (apply-events! client (:events sink))
    (feed/ack! f "u1" 1 0)

    (let [first (feed/publish-delta! f [{"op" "add"
                                         "path" "/a"
                                         "value" 1}])]
      (is (= 1 (:seq first)))
      (is (= "STATE_DELTA" (:type (last @(:events sink)))))
      (apply-events! client (:events sink))
      (is (= 1 (:seq @client))))

    (testing "crossing the acknowledgement window stops delta delivery"
      (let [second (feed/publish-delta! f [{"op" "add"
                                            "path" "/b"
                                            "value" 2}])
            notice (last @(:events sink))]
        (is (= 2 (:seq second)))
        (is (= 1 (:stale second)))
        (is (= 0 (:delivered second)))
        (is (= "CUSTOM" (:type notice)))
        (is (= "hc/resync-required" (:name notice)))
        (is (not-any? #(= "STATE_DELTA" (:type %)) @(:events sink))
            "the lagging subscriber receives no further delta")
        (is (not-any? #(and (= "STATE_DELTA" (:type %))
                             (= 2 (:seq %)))
                      @(:events sink)))
        (is (true? (:stale? (feed/subscription f "u1" 1))))))

    (apply-events! client (:events sink))
    (is (= 1 (:seq @client)))
    (is (= :synced (sync/phase @client)))

    (testing "resynchronization sends a complete baseline, not a delta"
      (let [result (feed/resync! f "u1" 1)
            event (last @(:events sink))]
        (is (:ok? result))
        (is (= "STATE_SNAPSHOT" (:type event)))
        (is (= 2 (:seq event)))
        (is (= {"a" 1 "b" 2} (:snapshot event)))
        (is (false? (:stale? (feed/subscription f "u1" 1))))
        (swap! client sync/receive event)
        (is (= 2 (:seq @client)))
        (is (= :synced (sync/phase @client)))
        (is (= 2 (get-in @client [:server "b"]))
            "the full baseline carries work the delta window skipped")
        (is (= 1 (get-in @client [:server "a"])))))))
