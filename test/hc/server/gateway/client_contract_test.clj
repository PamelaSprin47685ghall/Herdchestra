(ns hc.server.gateway.client-contract-test
  "Cross-layer check: server gateway events are accepted by the pure client
  adapter.  This does not make hc.server depend on hc.client; only the test
  loads both sides to prove the wire contract."
  (:require [clojure.test :refer [deftest is testing]]
            [hc.client.sync :as sync]
            [hc.server.gateway :as gateway]
            [hc.server.gateway.diff :as diff]
            [hc.server.gateway.feed :as feed]
            [hc.server.gateway.ports :as ports]
            [hc.server.gateway.sync-object :as sync-object]))

(defn- make-gw
  ([] (make-gw {:session/by-id {}}))
  ([snapshot]
   (let [out (atom [])
         gw {:feed (feed/create {:now-fn (constantly "2026-11-03T00:00:00Z")
                                 :snapshot snapshot})
             :owner nil
             :store nil
             :socket {:send-fn (fn [uid event] (swap! out conj [uid event]))}
             :out out
             :stopped? (atom false)
             :opts {}}]
     (assoc gw :handler (fn [_] {:status 200})))))

(defn- connect-client
  []
  (-> (sync/initial-state)
      sync/connect
      sync/channel-opened))

(deftest gateway-events-drive-the-client-adapter
  (let [gw (make-gw)
        client (atom (connect-client))
        _ (gateway/subscribe! gw "u1" 1
                              (fn [event] (swap! client sync/receive event)))]
    (testing "baseline snapshot establishes the synced boundary"
      (is (= :synced (sync/phase @client)))
      (is (true? (sync/command-allowed? @client)))
      (is (= 1 (:epoch @client)))
      (is (= 0 (:seq @client))))

    (testing "a delta advances the sequence and updates the display copy"
      (is (:ok? (gateway/publish!
                 gw
                 {:type :state-delta
                  :delta [{"op" "add" "path" "/session~1by-id/s1"
                           "value" {"id" "s1" :status "running"}}]})))
      (is (= 1 (:seq @client)))
      (is (= "running" (get-in @client [:db :session/by-id "s1" :status]))))

    (testing "a messages snapshot is accepted at the next sequence"
      (is (:ok? (gateway/publish!
                 gw
                 {:type :messages-snapshot
                  :messages [{:id "m1" :role "user" :content "hello"}]
                  :hc/roles ["user"]})))
      (is (= 2 (:seq @client)))
      (is (= "hello" (get-in @client [:db :message/by-id "m1" :content]))))

    (testing "a new epoch snapshot replaces the whole tree"
      (is (:ok? (gateway/publish!
                 gw
                 {:type :state-snapshot
                  :snapshot {:instance/id "i1"}
                  :new-epoch? true})))
      (is (= 2 (:epoch @client)))
      (is (= 0 (:seq @client)))
      (is (= "i1" (get-in @client [:db :instance/id])))
      (is (nil? (get-in @client [:db :session/by-id "s1"]))))))

(deftest client-drops-a-late-event-from-an-old-subscription
  (let [client (connect-client)
        baseline {:type "STATE_SNAPSHOT"
                  :snapshot {:x 1}
                  :epoch 1
                  :seq 0
                  :subscription-id 1}
        client (sync/receive client baseline)
        late {:type "STATE_DELTA"
              :delta [{"op" "replace" "path" "/x" "value" 2}]
              :epoch 1
              :seq 1
              :subscription-id 0}]
    (is (= :synced (sync/phase client)))
    (let [result (sync/receive client late)]
      (is (= :stale-subscription (get-in result [:last-outcome :reason])))
      (is (= 0 (:seq result)))
      (is (= {:x 1} (:server result))))))

(deftest generated-state-deltas-drive-the-client-adapter
  (let [old {:session/by-id {"s1" {:status "running" :messages [1 2]}}
             :message/by-id {}
             :server/now 1000}
        new (-> old
                (assoc-in [:session/by-id "s1" :status] "idle")
                (assoc-in [:message/by-id "m1"]
                          {:id "m1" :role "user" :content "hello"})
                (assoc :server/now 2000))
        ops (->> (conj (diff/diff (dissoc old :server/now)
                                  (dissoc new :server/now))
                       {"op" "replace" "path" "/server~1now" "value" 2000})
                 (mapv (fn [op]
                         (into {} (map (fn [[k v]] [(name k) v])) op))))
        gw (make-gw old)
        client (atom (connect-client))]
    (is (:ok? (gateway/subscribe! gw "u1" 1
                                  (fn [event] (swap! client sync/receive event)))))
    (is (= :synced (sync/phase @client)))
    (is (:ok? (gateway/publish! gw {:type :state-delta :delta ops})))
    (is (= 1 (:seq @client)))
    (is (= new (:server @client)))
    (is (= "idle" (get-in @client [:db :session/by-id "s1" :status])))
    (is (= "hello" (get-in @client [:db :message/by-id "m1" :content])))))

(deftest a-large-snapshot-reference-completes-the-client-baseline
  (let [blobs (atom {})
        store (ports/memory-blob-port blobs)
        out (atom [])
        gw {:feed (feed/create {:now-fn (constantly "2026-11-05T00:00:00Z")})
            :owner nil
            :store store
            :objects (sync-object/create-registry)
            :publish-lock (Object.)
            :socket {:send-fn (fn [uid event] (swap! out conj [uid event]))}
            :out out
            :stopped? (atom false)
            :opts {:max-message-bytes 512
                   :max-state-object-bytes (* 1024 1024)
                   :max-state-objects 8}}
        client (atom (connect-client))
        snapshot {:payload (apply str (repeat 2000 "x"))}]
    (is (:ok? (gateway/subscribe! gw "u1" 1
                                  (fn [event]
                                    (swap! client sync/receive event)))))
    (is (:ok? (gateway/publish! gw {:type :state-snapshot
                                    :snapshot snapshot})))
    (is (= :awaiting-object (get-in @client [:last-outcome :result])))
    (is (false? (sync/command-allowed? @client)))
    ;; The initial inline baseline established epoch 1 / seq 0.  The
    ;; reference consumes seq 1 but must not advance the confirmed copy.
    (is (= 0 (:seq @client)))
    (is (= 1 (:epoch @client)))
    (let [ref (get-in @client [:pending-object :ref])
          entry (sync-object/lookup (:objects gw) (:token ref))
          bytes (get @blobs (:blob-id entry))
          completed (sync/complete-object
                     @client
                     {:token (:token ref)
                      :digest (sync-object/digest-bytes bytes)
                      :size (alength ^bytes bytes)
                      :kind :state-snapshot
                      :verified? true
                      :value (sync-object/decode bytes)})]
      (is (= :synced (sync/phase completed)))
      (is (true? (sync/command-allowed? completed)))
      (is (= snapshot (:server completed)))
      (is (= 1 (:seq completed)))
      (is (nil? (:pending-object completed))))))
