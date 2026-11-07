(ns hc.server.gateway.feed-test
  (:require [clojure.test :refer [deftest is testing]]
            [hc.server.gateway.feed :as feed]))

(defn- fixed-feed
  ([] (fixed-feed {}))
  ([opts]
   (feed/create (merge {:now-fn (constantly "2026-11-03T00:00:00Z")}
                       opts))))

(defn- collector
  []
  (let [events (atom [])]
    {:events events
     :send-fn (fn [event] (swap! events conj event))}))

(defn- types
  [events]
  (mapv :type @events))

(deftest baseline-is-atomic-and-new-subscriptions-see-current-tree
  (let [f (fixed-feed {:snapshot {:session/by-id {"s1" {:id "s1" :status "running"}}}})
        a (collector)
        result (feed/subscribe! f "u1" 1 (:send-fn a))]
    (is (:ok? result))
    (is (= 1 (:epoch result)))
    (is (= 0 (:seq result)))
    (is (= ["STATE_SNAPSHOT"] (types (:events a))))
    (is (= "running" (get-in (first @(:events a))
                             [:snapshot :session/by-id "s1" :status])))
    (is (= 1 (:subscription-id (first @(:events a)))))

    (let [delta [{"op" "replace"
                  "path" "/session~1by-id/s1/status"
                  "value" "idle"}]
          published (feed/publish-delta! f delta)]
      (is (:ok? published))
      (is (= 1 (:seq published)))
      (is (= ["STATE_SNAPSHOT" "STATE_DELTA"] (types (:events a))))
      (is (= 1 (:seq (second @(:events a)))))
      (is (= delta (:delta (second @(:events a))))))

    (testing "a later subscription receives the patched tree at the current sequence"
      (let [b (collector)
            result (feed/subscribe! f "u2" 1 (:send-fn b))]
        (is (= 1 (:seq result)))
        (is (= "idle" (get-in (first @(:events b))
                              [:snapshot :session/by-id "s1" :status])))
        (is (= ["STATE_SNAPSHOT"] (types (:events b))))))))

(deftest new-epoch-resets-sequence-and-heals-stale-subscriptions
  (let [f (fixed-feed {:snapshot {:a 1}})
        a (collector)
        _ (feed/subscribe! f "u1" 1 (:send-fn a))
        _ (feed/publish-delta! f [{"op" "replace" "path" "/a" "value" 2}])
        _ (feed/subscribe! f "u2" 1 (:send-fn (collector)))
        ;; Supersede u2 so its old entry is stale/removed.
        _ (feed/subscribe! f "u2" 2 (:send-fn (collector)))
        result (feed/publish-snapshot! f {:a 3} {:new-epoch? true})]
    (is (:ok? result))
    (is (= 2 (:epoch result)))
    (is (= 0 (:seq result)))
    (is (= ["STATE_SNAPSHOT" "STATE_DELTA" "STATE_SNAPSHOT"] (types (:events a))))
    (is (= 2 (:epoch (last @(:events a)))))
    (is (= 0 (:seq (last @(:events a)))))
    (is (= {:a 3} (:snapshot (last @(:events a)))))))

(deftest superseded-subscription-stops-receiving-state
  (let [f (fixed-feed)
        a (collector)
        b (collector)
        _ (feed/subscribe! f "u1" 1 (:send-fn a))
        _ (feed/subscribe! f "u1" 2 (:send-fn b))
        result (feed/publish-delta! f [{"op" "add" "path" "/x" "value" 1}])]
    (is (= 1 (:delivered result)))
    (is (= 1 (count @(:events a))))
    (is (= ["STATE_SNAPSHOT" "STATE_DELTA"] (types (:events b))))
    (is (nil? (feed/subscription f "u1" 1)))
    (is (some? (feed/subscription f "u1" 2)))))

(deftest an-older-subscription-id-cannot-replace-a-newer-one
  (let [f (fixed-feed)
        a (collector)
        b (collector)
        _ (feed/subscribe! f "u1" 1 (:send-fn a))
        _ (feed/subscribe! f "u1" 3 (:send-fn b))
        result (feed/subscribe! f "u1" 2 (:send-fn a))]
    (is (not (:ok? result)))
    (is (= :STALE_SUBSCRIPTION (get-in result [:error :code])))
    (is (= 3 (get-in result [:error :current-subscription])))
    (is (= 3 (:subscription-id (feed/subscription f "u1" 3))))
    (is (nil? (feed/subscription f "u1" 2)))
    (is (= ["STATE_SNAPSHOT"] (types (:events b))))))

(deftest ack-window-marks-slow-subscription-stale-and-snapshot-heals
  (let [f (fixed-feed {:max-pending 2})
        a (collector)
        _ (feed/subscribe! f "u1" 1 (:send-fn a))
        _ (feed/ack! f "u1" 1 0)
        _ (feed/publish-delta! f [{"op" "add" "path" "/a" "value" 1}])
        _ (feed/publish-delta! f [{"op" "add" "path" "/b" "value" 2}])
        result (feed/publish-delta! f [{"op" "add" "path" "/c" "value" 3}])]
    (is (= 0 (:delivered result)))
    (is (= 1 (:stale result)))
    (is (= "CUSTOM" (:type (last @(:events a)))))
    (is (= "hc/resync-required" (:name (last @(:events a)))))
    (is (:stale? (feed/subscription f "u1" 1)))
    (testing "a delta never heals a stale subscription"
      (let [result (feed/publish-delta! f [{"op" "add" "path" "/d" "value" 4}])]
        (is (= 0 (:delivered result)))
        (is (= "CUSTOM" (:type (last @(:events a)))))))
    (testing "a full snapshot heals it"
      (let [result (feed/publish-snapshot! f {:healed true} {})]
        (is (= 1 (:delivered result)))
        (is (not (:stale? (feed/subscription f "u1" 1))))
        (is (= "STATE_SNAPSHOT" (:type (last @(:events a)))))))))

(deftest resync-heals-a-stale-subscription-without-changing-its-id
  (let [f (fixed-feed {:max-pending 1})
        a (collector)
        _ (feed/subscribe! f "u1" 1 (:send-fn a))
        _ (feed/ack! f "u1" 1 0)
        _ (feed/publish-delta! f [{"op" "add" "path" "/a" "value" 1}])
        _ (feed/publish-delta! f [{"op" "add" "path" "/b" "value" 2}])
        stale (feed/subscription f "u1" 1)]
    (is (:stale? stale))
    (let [result (feed/resync! f "u1" 1)]
      (is (:ok? result))
      (is (= 1 (:subscription-id result)))
      (is (not (:stale? (feed/subscription f "u1" 1))))
      (is (= "STATE_SNAPSHOT" (-> @(:events a) last :type))))))

(deftest send-failure-marks-subscription-stale
  (let [f (fixed-feed)
        calls (atom 0)
        send-fn (fn [_]
                  (when (> (swap! calls inc) 1)
                    (throw (ex-info "transport gone" {}))))
        _ (feed/subscribe! f "u1" 1 send-fn)
        result (feed/publish-delta! f [{"op" "add" "path" "/x" "value" 1}])]
    (is (= 0 (:delivered result)))
    (is (:stale? (feed/subscription f "u1" 1)))
    (is (= 0 (:delivered (feed/publish-delta! f [{"op" "add" "path" "/y" "value" 2}]))))))

(deftest messages-snapshot-replaces-messages-and-passes-extensions
  (let [f (fixed-feed {:snapshot {:message/by-id {"m1" {:id "m1" :role "user"}}}})
        a (collector)
        _ (feed/subscribe! f "u1" 1 (:send-fn a))
        messages [{:id "m2" :role "assistant" :content "hello"}]
        result (feed/publish-messages! f messages {:hc/roles ["assistant"]})]
    (is (:ok? result))
    (is (= messages (:messages (last @(:events a)))))
    (is (= ["assistant"] (:hc/roles (last @(:events a)))))
    (is (= messages (:messages (:snapshot (feed/snapshot f)))))
    (is (nil? (get-in (feed/snapshot f) [:snapshot :message/by-id])))))

(deftest a-delta-that-replaces-the-root-with-a-non-map-is-rejected
  (let [f (fixed-feed {:snapshot {:a 1}})
        before (feed/snapshot f)
        result (feed/publish-delta!
                f
                [{"op" "replace" "path" "" "value" []}])]
    (is (not (:ok? result)))
    (is (= :STATE_DELTA_INVALID (get-in result [:error :code])))
    (is (= before (feed/snapshot f)))))

(deftest broadcast-does-not-consume-the-sequence
  (let [f (fixed-feed)
        a (collector)
        _ (feed/subscribe! f "u1" 1 (:send-fn a))
        result (feed/broadcast! f {:type "CUSTOM" :name "hc/ping" :value 1})]
    (is (= 1 (:delivered result)))
    (is (= 0 (:seq (feed/snapshot f))))
    (is (= 2 (count @(:events a))))
    (is (= 1 (:subscription-id (last @(:events a)))))))

(deftest unsubscribe-and-close-stop-delivery
  (let [f (fixed-feed)
        a (collector)
        _ (feed/subscribe! f "u1" 1 (:send-fn a))
        _ (feed/unsubscribe! f "u1" 1)
        result (feed/publish-delta! f [{"op" "add" "path" "/x" "value" 1}])]
    (is (= 0 (:delivered result)))
    (is (= 1 (count @(:events a))))
    (let [b (collector)]
      (feed/subscribe! f "u2" 1 (:send-fn b))
      (is (= 1 (:removed (feed/close! f))))
      (is (= 0 (:subscriptions (feed/stats f))))
      (is (= 0 (:delivered (feed/publish-delta! f [{"op" "add" "path" "/y" "value" 2}])))))))
