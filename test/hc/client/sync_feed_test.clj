(ns hc.client.sync-feed-test
  "Cross-layer behavioral checks between the atomic gateway feed and the pure
  client adapter.  This test intentionally loads `hc.server.gateway.feed` only;
  it does not start Sente or the application server."
  (:require [clojure.test :refer [deftest is testing]]
            [hc.client.sync :as sync]
            [hc.server.gateway.feed :as feed]))

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

(deftest baseline-delta-new-epoch-and-reconnect
  (let [sink (collector)
        f (feed/create {:snapshot {:session/by-id {"s1" {:id "s1" :status "running"}}
                                   :sync {:command-epoch 9}}
                        :now-fn (constantly "t0")})
        client (atom (connected-client))]
    (testing "the baseline carries feed epoch and Owner command epoch separately"
      (feed/subscribe! f "u1" 1 (:send-fn sink))
      (apply-events! client (:events sink))
      (is (= :synced (sync/phase @client)))
      (is (= 1 (:epoch @client)))
      (is (= 0 (:seq @client)))
      (is (= 9 (sync/command-epoch @client))))

    (testing "a delta advances the feed sequence"
      (feed/publish-delta! f [{"op" "replace"
                               "path" "/session~1by-id/s1/status"
                               "value" "idle"}])
      (apply-events! client (:events sink))
      (is (= "idle" (get-in @client [:db :session/by-id "s1" :status])))
      (is (= 1 (:seq @client))))

    (testing "a new epoch replaces the whole tree and clears removed entities"
      (feed/publish-snapshot! f {:session/by-id {"s2" {:id "s2"}}
                                 :sync {:command-epoch 11}}
                              {:new-epoch? true})
      (apply-events! client (:events sink))
      (is (= 2 (:epoch @client)))
      (is (= 0 (:seq @client)))
      (is (= 11 (sync/command-epoch @client)))
      (is (not (contains? (get-in @client [:db :session/by-id]) "s1")))
      (is (= "s2" (get-in @client [:db :session/by-id "s2" :id]))))

    (testing "reconnect creates a new subscription generation"
      (swap! client (fn [state] (sync/channel-opened (sync/connect state))))
      (feed/subscribe! f "u1" 2 (:send-fn sink))
      (apply-events! client (:events sink))
      (is (= :synced (sync/phase @client)))
      (is (= 2 (:subscription @client)))
      (is (= 2 (:epoch @client)))
      (is (= 0 (:seq @client))))

    (testing "a late event from the old subscription is dropped"
      (let [before @client
            after (sync/receive before {:type "STATE_DELTA"
                                        :delta []
                                        :epoch 2
                                        :seq 1
                                        :subscription-id 1})]
        (is (= :stale-subscription (get-in after [:last-outcome :reason])))
        (is (= (:server before) (:server after)))
        (is (= 0 (:seq after)))))

    (testing "an older subscription id cannot replace the current one"
      (let [result (feed/subscribe! f "u1" 1 (:send-fn sink))]
        (is (false? (:ok? result)))
        (is (= :STALE_SUBSCRIPTION (get-in result [:error :code])))
        (is (= 2 (get-in result [:error :current-subscription])))))))

(deftest stale-subscription-resync-heals
  (let [sink (collector)
        f (feed/create {:max-pending 1
                        :now-fn (constantly "t0")})
        _ (feed/subscribe! f "u1" 1 (:send-fn sink))]
    (feed/ack! f "u1" 1 0)
    (feed/publish-delta! f [{"op" "add" "path" "/a" "value" 1}])
    (feed/publish-delta! f [{"op" "add" "path" "/b" "value" 2}])
    (is (:stale? (feed/subscription f "u1" 1)))
    (is (= "hc/resync-required"
           (:name (last @(:events sink)))))
    (testing "a stale subscription can request a fresh baseline"
      (let [result (feed/resync! f "u1" 1)]
        (is (:ok? result))
        (is (= "STATE_SNAPSHOT" (:type (last @(:events sink)))))
        (is (= 2 (:seq (last @(:events sink)))))
        (is (not (:stale? (feed/subscription f "u1" 1))))))))
