(ns hc.server.gateway.sync-object-test
  (:require [clojure.test :refer [deftest is testing]]
            [hc.server.gateway :as gateway]
            [hc.server.gateway.feed :as feed]
            [hc.server.gateway.ports :as ports]
            [hc.server.gateway.sync-object :as sync-object]))

(defn- make-gw
  ([] (make-gw nil {}))
  ([store] (make-gw store {}))
  ([store opts]
   (let [out (atom [])
         gw {:feed (feed/create {:now-fn (constantly "2026-11-05T00:00:00Z")})
             :owner nil
             :store store
             :objects (sync-object/create-registry)
             :publish-lock (Object.)
             :socket {:send-fn (fn [uid event] (swap! out conj [uid event]))}
             :out out
             :stopped? (atom false)
             :opts (merge {:max-message-bytes 512
                           :max-state-object-bytes (* 1024 1024)
                           :max-state-objects 8}
                          opts)}]
     (assoc gw :handler (fn [_] {:status 200})))))

(defn- sent-events
  [gw]
  (mapv (comp second second) @(:out gw)))

(defn- subscribe!
  [gw uid sid]
  (let [reply (atom nil)]
    (gateway/handle-event! gw
                           {:uid uid
                            :?reply-fn #(reset! reply %)
                            :event [:hc/subscribe {:subscription-id sid}]})
    @reply))

(defn- resync!
  [gw uid sid]
  (let [reply (atom nil)]
    (gateway/handle-event! gw
                           {:uid uid
                            :?reply-fn #(reset! reply %)
                            :event [:hc/resync {:subscription-id sid}]})
    @reply))

(defn- big-string
  [n]
  (apply str (repeat n "x")))

(defn- token-of
  [gw]
  (let [event (last (sent-events gw))]
    (or (get-in event [:snapshot-ref :token])
        (get-in event [:delta-ref :token]))))

(defn- bytes-for
  [gw blobs token]
  (let [entry (sync-object/lookup (:objects gw) token)]
    (get @blobs (:blob-id entry))))

(deftest oversized-snapshot-becomes-a-state-object
  (let [blobs (atom {})
        store (ports/memory-blob-port blobs)
        gw (make-gw store)
        _ (subscribe! gw "u1" 1)
        snapshot {:payload (big-string 2000)}
        result (gateway/publish! gw {:type :state-snapshot
                                     :snapshot snapshot})]
    (is (:ok? result))
    (is (= 1 (:epoch result)))
    (is (= 1 (:seq result)))
    (let [event (last (sent-events gw))
          ref (:snapshot-ref event)
          bytes (bytes-for gw blobs (:token ref))]
      (is (= "STATE_SNAPSHOT" (:type event)))
      (is (nil? (:snapshot event)))
      (is (= :state-snapshot (:kind ref)))
      (is (= 1 (:epoch ref)))
      (is (= 1 (:seq ref)))
      (is (= "edn" (:encoding ref)))
      (is (some? bytes))
      (is (= snapshot (sync-object/decode bytes)))
      (is (= (alength ^bytes bytes) (:size ref)))
      (is (= (sync-object/digest-bytes bytes) (:digest ref)))
      (is (< (gateway/event-bytes event) 512))
      (is (= snapshot (:snapshot (gateway/snapshot gw)))))))

(deftest oversized-delta-becomes-a-state-object
  (let [blobs (atom {})
        store (ports/memory-blob-port blobs)
        gw (make-gw store)
        _ (subscribe! gw "u1" 1)
        big (big-string 2000)
        delta [{"op" "add" "path" "/big" "value" big}]
        result (gateway/publish! gw {:type :state-delta :delta delta})]
    (is (:ok? result))
    (let [event (last (sent-events gw))
          ref (:delta-ref event)
          bytes (bytes-for gw blobs (:token ref))]
      (is (= "STATE_DELTA" (:type event)))
      (is (nil? (:delta event)))
      (is (= :state-delta (:kind ref)))
      (is (= 1 (:epoch ref)))
      (is (= 1 (:seq ref)))
      (is (= delta (sync-object/decode bytes)))
      (is (= big (or (get-in (gateway/snapshot gw) [:snapshot :big])
                     (get-in (gateway/snapshot gw) [:snapshot "big"])))))))

(deftest oversized-state-without-a-store-fails-honestly
  (let [gw (make-gw nil)
        before (gateway/snapshot gw)
        result (gateway/publish! gw {:type :state-snapshot
                                     :snapshot {:payload (big-string 2000)}})]
    (is (false? (:ok? result)))
    (is (= :SENTE_MESSAGE_TOO_LARGE (get-in result [:error :code])))
    (is (string? (get-in result [:error :details :hint])))
    (is (= before (gateway/snapshot gw)))
    (is (empty? (sent-events gw)))))

(deftest object-limit-is-enforced-before-the-feed-advances
  (let [blobs (atom {})
        store (ports/memory-blob-port blobs)
        gw (make-gw store {:max-state-object-bytes 256})
        before (gateway/snapshot gw)
        result (gateway/publish! gw {:type :state-snapshot
                                     :snapshot {:payload (big-string 2000)}})]
    (is (false? (:ok? result)))
    (is (= :STATE_OBJECT_TOO_LARGE (get-in result [:error :code])))
    (is (= 256 (get-in result [:error :details :limit])))
    (is (= before (gateway/snapshot gw)))
    (is (empty? @blobs))))

(deftest large-baselines-use-a-reference
  (let [blobs (atom {})
        store (ports/memory-blob-port blobs)
        gw (make-gw store)
        snapshot {:payload (big-string 2000)}
        _ (is (:ok? (gateway/publish! gw {:type :state-snapshot
                                          :snapshot snapshot})))
        reply (subscribe! gw "u1" 1)
        event (last (sent-events gw))
        ref (:snapshot-ref event)]
    (is (:ok? reply))
    (is (nil? (:snapshot event)))
    (is (= 1 (:epoch ref)))
    (is (= 1 (:seq ref)))
    (is (= snapshot
           (sync-object/decode
            (bytes-for gw blobs (:token ref)))))
    (testing "two subscribers at one boundary share the same object"
      (subscribe! gw "u2" 1)
      (is (= (:token ref) (token-of gw))))
    (testing "resync uses the same baseline reference"
      (let [reply (resync! gw "u1" 1)
            event (last (sent-events gw))]
        (is (:ok? reply))
        (is (= (:token ref) (get-in event [:snapshot-ref :token])))))))

(deftest an-old-epoch-object-is-rejected
  (let [blobs (atom {})
        store (ports/memory-blob-port blobs)
        gw (make-gw store)
        _ (subscribe! gw "u1" 1)
        _ (gateway/publish! gw {:type :state-snapshot
                                :snapshot {:payload (big-string 2000)}})
        token (token-of gw)]
    (is (some? (sync-object/lookup (:objects gw) token)))
    (let [resolved (sync-object/resolve-object (:objects gw) 2 token)]
      (is (false? (:ok? resolved)))
      (is (= 410 (:status resolved)))
      (is (= :SYNC_OBJECT_STALE_EPOCH (get-in resolved [:error :code]))))
    (testing "the reference survives a new epoch until the LRU evicts it"
      (is (:ok? (gateway/publish! gw {:type :state-snapshot
                                      :snapshot {:payload (big-string 2000)}
                                      :new-epoch? true})))
      (is (some? (sync-object/lookup (:objects gw) token))))))

(deftest the-registry-is-bounded
  (let [blobs (atom {})
        store (ports/memory-blob-port blobs)
        gw (make-gw store {:max-state-objects 2})
        _ (subscribe! gw "u1" 1)
        tokens (mapv (fn [n]
                       (let [result (gateway/publish!
                                     gw
                                     {:type :state-snapshot
                                      :snapshot {:n n
                                                 :payload (big-string 1000)}})]
                         (is (:ok? result))
                         (token-of gw)))
                     (range 3))]
    (is (nil? (sync-object/lookup (:objects gw) (first tokens))))
    (is (some? (sync-object/lookup (:objects gw) (second tokens))))
    (is (some? (sync-object/lookup (:objects gw) (last tokens))))))

(deftest a-mismatched-reference-does-not-advance-the-feed
  (let [f (feed/create {:now-fn (constantly "t0")})
        sink (atom [])
        _ (feed/subscribe! f "u1" 1 (fn [event] (swap! sink conj event)))
        before (feed/snapshot f)
        ref {:token (apply str (repeat 32 "a"))
             :digest (str "sha256-" (apply str (repeat 64 "b")))
             :size 10
             :encoding "edn"
             :kind :state-snapshot
             :epoch 1
             :seq 99}
        result (feed/publish-snapshot! f {:a 1} {:ref ref})]
    (is (false? (:ok? result)))
    (is (= :STATE_OBJECT_BOUNDARY_MISMATCH (get-in result [:error :code])))
    (is (= before (feed/snapshot f)))
    (is (= 1 (count @sink)))))

(deftest a-mismatched-baseline-reference-falls-back-inline
  (let [f (feed/create {:snapshot {:a 1} :now-fn (constantly "t0")})
        sink (atom [])
        ref {:token (apply str (repeat 32 "a"))
             :digest (str "sha256-" (apply str (repeat 64 "b")))
             :size 10
             :encoding "edn"
             :kind :state-snapshot
             :epoch 99
             :seq 99}
        result (feed/subscribe! f "u1" 1 (fn [event] (swap! sink conj event))
                                {:baseline-ref ref})]
    (is (:ok? result))
    (is (= {:a 1} (:snapshot (first @sink))))
    (is (nil? (:snapshot-ref (first @sink))))))

(deftest the-wire-bytes-must-round-trip-exactly
  (testing "a lone surrogate is rejected instead of reaching the client as ?"
    (let [blobs (atom {})
          store (ports/memory-blob-port blobs)
          gw (make-gw store)
          _ (subscribe! gw "u1" 1)
          before (gateway/snapshot gw)
          value {:payload (str (big-string 2000) (char 0xD800))}
          result (gateway/publish! gw {:type :state-snapshot
                                       :snapshot value})]
      (is (false? (:ok? result)))
      (is (= :STATE_OBJECT_ENCODING_FAILED (get-in result [:error :code])))
      (is (= before (gateway/snapshot gw)))
      (is (empty? @blobs))))
  (testing "a value that prints as a reader macro is rejected, not read"
    (let [blobs (atom {})
          store (ports/memory-blob-port blobs)
          gw (make-gw store)
          _ (subscribe! gw "u1" 1)
          before (gateway/snapshot gw)
          value {:payload (big-string 2000)
                 :danger (symbol "#= (System/exit 0)")}
          result (gateway/publish! gw {:type :state-snapshot
                                       :snapshot value})]
      (is (false? (:ok? result)))
      (is (= :STATE_OBJECT_ENCODING_FAILED (get-in result [:error :code])))
      (is (= before (gateway/snapshot gw)))
      (is (empty? @blobs)))))

(deftest a-non-positive-object-registry-limit-fails-before-storing
  (doseq [limit [0 -1]]
    (testing (str "limit " limit)
      (let [blobs (atom {})
            store (ports/memory-blob-port blobs)
            gw (make-gw store {:max-state-objects limit})
            _ (subscribe! gw "u1" 1)
            before (gateway/snapshot gw)
            result (gateway/publish! gw
                                     {:type :state-snapshot
                                      :snapshot {:payload (big-string 2000)}})]
        (is (false? (:ok? result)))
        (is (= :STATE_OBJECT_TOO_LARGE (get-in result [:error :code])))
        (is (= limit (get-in result [:error :details :limit])))
        (is (= before (gateway/snapshot gw)))
        (is (empty? @blobs))))))

(deftest client-reader-incompatible-values-are-rejected
  (doseq [[label value] [["ratio" 1/3]
                         ["small bigint" (bigint 5)]
                         ["bigint beyond JavaScript precision"
                          9007199254740993N]
                         ["bigdecimal" 1.5M]
                         ["character" \a]
                         ["regex" #"x"]]]
    (testing label
      (let [blobs (atom {})
            store (ports/memory-blob-port blobs)
            gw (make-gw store)
            _ (subscribe! gw "u1" 1)
            before (gateway/snapshot gw)
            result (gateway/publish! gw
                                     {:type :state-snapshot
                                      :snapshot {:payload (big-string 2000)
                                                 :danger value}})]
        (is (false? (:ok? result)))
        (is (= :STATE_OBJECT_ENCODING_FAILED (get-in result [:error :code])))
        (is (= before (gateway/snapshot gw)))
        (is (empty? @blobs)))))
  (testing "the largest exactly representable integer is still accepted"
    (let [blobs (atom {})
          store (ports/memory-blob-port blobs)
          gw (make-gw store)
          _ (subscribe! gw "u1" 1)
          result (gateway/publish! gw
                                   {:type :state-snapshot
                                    :snapshot {:payload (big-string 2000)
                                               :safe 9007199254740991}})]
      (is (:ok? result))
      (is (= 1 (count @blobs))))))

(deftest manual-subscribe-externalizes-an-oversized-baseline
  (let [blobs (atom {})
        store (ports/memory-blob-port blobs)
        gw (make-gw store)
        _ (feed/publish-snapshot! (:feed gw)
                                  {:payload (big-string 2000)}
                                  {})
        result (gateway/subscribe! gw "u1" 1
                                   (fn [event]
                                     (swap! (:out gw) conj [nil event])))
        event (second (last @(:out gw)))]
    (is (:ok? result))
    (is (= "STATE_SNAPSHOT" (:type event)))
    (is (some? (get-in event [:snapshot-ref :token])))
    (is (nil? (:snapshot event)))
    (is (= 1 (count @blobs)))))
