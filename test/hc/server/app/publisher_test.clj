(ns hc.server.app.publisher-test
  (:require [clojure.test :refer [deftest is testing]]
            [hc.server.app.snapshot :as snapshot]
            [hc.server.app.system :as system]
            [hc.server.gateway :as gateway]
            [hc.server.gateway.feed :as feed]
            [hc.server.gateway.patch :as patch]
            [hc.server.gateway.ports :as ports]
            [hc.server.gateway.sync-object :as sync-object]
            [hc.server.history.snapshot :as history-snapshot]))

(defn- test-gateway
  ([] (test-gateway {}))
  ([opts]
   (let [events (atom [])
         gw {:feed (feed/create {:now-fn (constantly "2026-11-05T00:00:00Z")})
             :stopped? (atom false)
             :opts (merge {:max-message-bytes (* 1024 1024)} opts)
             :socket {:send-fn (fn [_ event] (swap! events conj event))}}]
     (assoc gw :events events))))

(defn- subscribe!
  [gw]
  (let [events (:events gw)]
    (is (:ok? (gateway/subscribe! gw "u1" 1
                                  (fn [event] (swap! events conj event)))))
    (reset! events [])
    events))

(deftest unchanged-state-does-not-consume-a-sequence
  (let [gw (test-gateway)
        events (subscribe! gw)
        snapshot {:session/by-id {"s1" {:status "running"}}
                  :server/now 1000}
        first (system/publish-state! gw nil snapshot)]
    (is (:ok? first))
    (is (= :snapshot (:mode first)))
    (is (= ["STATE_SNAPSHOT"] (mapv :type @events)))
    (let [before (gateway/snapshot gw)
          result (system/publish-state! gw snapshot snapshot)]
      (is (:ok? result))
      (is (= :noop (:mode result)))
      (is (= before (gateway/snapshot gw)))
      (is (= 1 (count @events))))
    (testing "a clock-only tick does not publish"
      (let [before (gateway/snapshot gw)
            next-snapshot (assoc snapshot :server/now 2000)
            result (system/publish-state! gw snapshot next-snapshot)]
        (is (:ok? result))
        (is (= :noop (:mode result)))
        (is (= before (gateway/snapshot gw)))
        (is (= 1 (count @events)))))))

(deftest a-real-change-publishes-a-delta
  (let [history (into {}
                      (map (fn [i]
                             [(str "h" i)
                              {:id (str "h" i)
                               :role "assistant"
                               :content (apply str (repeat 200 "x"))}]))
                      (range 20))
        gw (test-gateway)
        events (subscribe! gw)
        old {:session/by-id {"s1" {:status "running"}}
             :message/by-id history
             :server/now 1000}
        _ (system/publish-state! gw nil old)
        new (-> old
                (assoc-in [:session/by-id "s1" :status] "idle")
                (assoc-in [:message/by-id "m1"]
                          {:id "m1" :role "assistant" :content "hello"})
                (assoc :server/now 2000))
        result (system/publish-state! gw old new)
        event (last @events)
        snapshot-event {:type :state-snapshot :snapshot new}]
    (is (:ok? result))
    (is (= :delta (:mode result)))
    (is (= "STATE_DELTA" (:type event)))
    (is (= new (:value (patch/apply-patch old (:delta event)))))
    (is (= new (:snapshot (gateway/snapshot gw))))
    (is (< (gateway/event-bytes event)
           (gateway/event-bytes snapshot-event))
        "the delta must be smaller than the complete snapshot")))

(deftest an-oversized-first-snapshot-returns-the-boundary-error
  (let [gw (test-gateway {:max-message-bytes 80})
        snapshot {:payload (apply str (repeat 200 "x"))}
        result (system/publish-state! gw nil snapshot)]
    (is (false? (:ok? result)))
    (is (= :snapshot (:mode result)))
    (is (= :SENTE_MESSAGE_TOO_LARGE
           (get-in result [:result :error :code])))))

(deftest an-oversized-first-snapshot-is-externalized-with-a-store
  (let [blobs (atom {})
        events (atom [])
        gw {:feed (feed/create {:now-fn (constantly "2026-11-05T00:00:00Z")})
            :store (ports/memory-blob-port blobs)
            :objects (sync-object/create-registry)
            :publish-lock (Object.)
            :stopped? (atom false)
            :opts {:max-message-bytes 512
                   :max-state-object-bytes (* 1024 1024)
                   :max-state-objects 8}
            :socket {:send-fn (fn [_ event] (swap! events conj event))}}
        snapshot {:payload (apply str (repeat 2000 "x"))}]
    (is (:ok? (gateway/subscribe! gw "u1" 1
                                  (fn [event] (swap! events conj event)))))
    (reset! events [])
    (let [result (system/publish-state! gw nil snapshot)
          event (last @events)]
      (is (:ok? result))
      (is (= :snapshot (:mode result)))
      (is (= "STATE_SNAPSHOT" (:type event)))
      (is (some? (:snapshot-ref event)))
      (is (nil? (:snapshot event)))
      (is (= snapshot (:snapshot (gateway/snapshot gw))))
      (is (= 1 (count @blobs))))))

(deftest publisher-retains-only-bounded-deltas
  (let [text-delta @#'system/text-delta
        prune @#'system/prune-to-live]
    (testing "the retained state is a length, not a second text copy"
      (is (= "hello" (text-delta "hello" 0)))
      (is (= " world" (text-delta "hello world" 5)))
      (is (nil? (text-delta "hello" 5)))
      (is (nil? (text-delta "hell" 5)))
      (is (nil? (text-delta "HELLO" 5))
          "a same-length replacement sends no delta, as before"))
    (testing "deleted sessions do not stay in the publisher maps"
      (let [m {:a 1 :b 2}]
        (is (identical? m (prune m #{:a :b}))
            "an unchanged live map is not copied")
        (is (= {:a 1} (prune m #{:a})))
        (is (= {} (prune m #{})))))))

(deftest unchanged-state-does-not-serialize-any-event
  (let [byte-calls (atom [])
        publish-calls (atom [])]
    (with-redefs [gateway/event-bytes
                  (fn [event] (swap! byte-calls conj (:type event)) 100)
                  gateway/publish!
                  (fn ([_ event] (swap! publish-calls conj event) {:ok? true})
                      ([_ event _size] (swap! publish-calls conj event)
                       {:ok? true}))]
      (let [snapshot {:messages {"m1" {:id "m1" :text "stable"}}
                      :server/now 1}
            result (system/publish-state! {} snapshot
                                          (assoc snapshot :server/now 2))]
        (is (= :noop (:mode result)))
        (is (empty? @byte-calls) "no event is printed for an unchanged tree")
        (is (empty? @publish-calls))))))

(deftest small-delta-skips-the-snapshot-size
  (let [byte-calls (atom [])
        publish-calls (atom [])]
    (with-redefs [gateway/event-bytes
                  (fn [event] (swap! byte-calls conj (:type event)) 100)
                  gateway/publish!
                  (fn ([_ event] (swap! publish-calls conj [event nil])
                       {:ok? true})
                      ([_ event size] (swap! publish-calls conj [event size])
                       {:ok? true}))]
      (let [previous {:messages {"m1" {:id "m1" :text "a"}}
                      :server/now 1}
            snapshot (assoc-in previous [:messages "m1" :text] "ab")
            result (system/publish-state! {} previous snapshot)]
        (is (= :delta (:mode result)))
        (is (= [:state-delta] @byte-calls)
            "the complete snapshot is not printed just to compare sizes")
        (is (= 1 (count @publish-calls)))
        (is (= 100 (second (first @publish-calls)))
            "publish reuses the delta size instead of printing it again")))))

(deftest large-delta-compares-and-reuses-the-chosen-size
  (let [byte-calls (atom [])
        publish-calls (atom [])]
    (with-redefs [gateway/event-bytes
                  (fn [event] (swap! byte-calls conj (:type event)) 20000)
                  gateway/publish!
                  (fn ([_ event] (swap! publish-calls conj [event nil])
                       {:ok? true})
                      ([_ event size] (swap! publish-calls conj [event size])
                       {:ok? true}))]
      (let [previous {:messages {"m1" {:id "m1" :text "a"}}
                      :server/now 1}
            snapshot (assoc-in previous [:messages "m1" :text] "ab")
            result (system/publish-state! {} previous snapshot)]
        (is (= :snapshot (:mode result)))
        (is (= [:state-delta :state-snapshot] @byte-calls)
            "a large delta is compared with the snapshot once")
        (is (= 1 (count @publish-calls)))
        (is (= 20000 (second (first @publish-calls)))
            "the chosen snapshot size is reused by publish")))))

(deftest baseline-cache-reuses-unchanged-projection
  (let [cache (atom nil)
        calls (atom 0)
        build (fn []
                (swap! calls inc)
                {:version @calls})]
    (is (= {:version 1} (#'system/cached-baseline cache [:k] build)))
    (is (= {:version 1} (#'system/cached-baseline cache [:k] build))
        "an unchanged key returns the previous map")
    (is (= 1 @calls) "the projection is built once")
    (is (= {:version 2} (#'system/cached-baseline cache [:k2] build)))
    (is (= 2 @calls) "a changed key rebuilds")))

(deftest baseline-cache-key-tracks-session-owner-and-materials
  (let [sessions [{:session-id "s1" :revision 1}]
        key (#'system/baseline-cache-key sessions {"s1" 1} "s1" 1 {} 1)]
    (is (= key (#'system/baseline-cache-key
                sessions {"s1" 1} "s1" 1 {} 1)))
    (is (not= key (#'system/baseline-cache-key
                   [{:session-id "s1" :revision 2}] {"s1" 1}
                   "s1" 1 {} 1))
        "a session revision invalidates")
    (is (not= key (#'system/baseline-cache-key
                   sessions {"s1" 2} "s1" 1 {} 1))
        "an owner version invalidates")
    (is (not= key (#'system/baseline-cache-key
                   sessions {"s1" 1} "s1" 1 {} 2))
        "a materials revision invalidates")))

(deftest baseline-rebuilds-only-changed-session-blocks
  (let [cache (atom {})
        session (fn [id revision]
                  {:session-id id
                   :title id
                   :status :active
                   :revision revision
                   :message-order ["m1"]
                   :messages {"m1" {:message-id "m1"
                                    :role :user
                                    :content "hi"
                                    :versions [{:version-id "v1"
                                                :content "hi"}]
                                    :current-version "v1"}}
                   :runs {}
                   :run-order []
                   :compressions {}})
        owner (fn [id]
                {:version 1
                 :state {:session {:session-id id}
                         :conversation [{:message-id "m1"
                                         :role :user
                                         :content "hi"}]
                         :calls {}
                         :commands {:command-epoch 1}
                         :runs {}
                         :tenures {}
                         :actors {}
                         :board nil}})
        base-opts (fn [records]
                    {:sessions (mapv #(select-keys % [:session-id :revision
                                                      :title])
                                     records)
                     :session-records records
                     :owner-snapshots {"s1" (owner "s1")
                                       "s2" (owner "s2")}
                     :active-session "s1"
                     :config {:models {}}
                     :sync {:phase :synced :command-epoch 1}
                     :session-cache cache})
        history-calls (atom {})
        conversation-calls (atom {})
        orig-history history-snapshot/session-history
        orig-conversation snapshot/conversation-view]
    (with-redefs [history-snapshot/session-history
                  (fn [s opts]
                    (swap! history-calls update (:session-id s) (fnil inc 0))
                    (orig-history s opts))
                  snapshot/conversation-view
                  (fn [& args]
                    (when (= 2 (count args))
                      (swap! conversation-calls update (second args) (fnil inc 0)))
                    (apply orig-conversation args))]
      (let [s1 (session "s1" 1)
            s2 (session "s2" 1)
            opts (base-opts [s1 s2])]
        (snapshot/baseline opts)
        (is (= {"s1" 1 "s2" 1} @history-calls))
        (is (= {"s1" 1 "s2" 1} @conversation-calls))
        (snapshot/baseline opts)
        (is (= {"s1" 2 "s2" 1} @history-calls)
            "the active history is fresh; the unchanged session is cached")
        (is (= {"s1" 1 "s2" 1} @conversation-calls)
            "both conversation views are cached by owner version")
        (snapshot/baseline (base-opts [s1 (session "s2" 2)]))
        (is (= {"s1" 3 "s2" 2} @history-calls)
            "only the changed session history is rebuilt")
        (is (= {"s1" 1 "s2" 1} @conversation-calls)
            "a session revision change does not rebuild the conversation")
        (snapshot/baseline (base-opts [s1]))
        (is (not (contains? (:history @cache) "s2"))
            "deleted sessions are pruned from the cache")))))

(deftest component-cache-reuses-unchanged-view
  (let [cache (atom {})
        calls (atom 0)
        build (fn []
                (swap! calls inc)
                {:value @calls})]
    (is (= {:value 1} (#'system/cached-component cache :materials 1 build)))
    (is (= {:value 1} (#'system/cached-component cache :materials 1 build))
        "an unchanged revision reuses the cached view")
    (is (= 1 @calls))
    (is (= {:value 2} (#'system/cached-component cache :materials 2 build)))
    (is (= 2 @calls) "a changed revision rebuilds")
    (is (= {:value 3} (#'system/cached-component cache :materials nil build)))
    (is (= 3 @calls) "a nil revision always rebuilds")))
