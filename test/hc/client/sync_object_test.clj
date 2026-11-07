(ns hc.client.sync-object-test
  "Consumer behavior and error recovery for large AG-UI state objects.

  The adapter never fetches bytes.  These tests supply the verified payload
  that the transport would produce, and prove that the confirmed copy only
  advances at the reference boundary."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.client.sync :as sync]
            [hc.client.sync.object :as object]))

(def ^:private token
  (apply str (repeat 32 "a")))

(def ^:private token-2
  (apply str (repeat 32 "c")))

(def ^:private digest
  (str "sha256-" (apply str (repeat 64 "b"))))

(def ^:private digest-2
  (str "sha256-" (apply str (repeat 64 "d"))))

(def ^:private snapshot-ref
  {:token token
   :digest digest
   :size 10
   :encoding "edn"
   :kind :state-snapshot
   :epoch 1
   :seq 0})

(def ^:private delta-ref
  (assoc snapshot-ref :kind :state-delta :seq 1))

(def ^:private base-server
  {:session/by-id {"s1" {:id "s1" :status :running}}})

(defn- connected
  []
  (-> (sync/initial-state)
      sync/connect
      sync/channel-opened))

(defn- synced
  ([]
   (synced base-server 1 0))
  ([server epoch seq]
   (sync/receive (connected)
                 1
                 {:type "STATE_SNAPSHOT"
                  :snapshot server
                  :epoch epoch
                  :seq seq
                  :subscription-id 1})))

(defn- result
  [state]
  (get-in state [:last-outcome :result]))

(defn- reason
  [state]
  (get-in state [:last-outcome :reason]))

(defn- snapshot-event
  ([ref] (snapshot-event ref 1 0))
  ([ref epoch seq]
   {:type "STATE_SNAPSHOT"
    :snapshot-ref ref
    :epoch epoch
    :seq seq
    :subscription-id 1}))

(defn- delta-event
  ([ref] (delta-event ref 1 1))
  ([ref epoch seq]
   {:type "STATE_DELTA"
    :delta-ref ref
    :epoch epoch
    :seq seq
    :subscription-id 1}))

(defn- inline-delta
  ([ops] (inline-delta ops 1 1))
  ([ops epoch seq]
   {:type "STATE_DELTA"
    :delta ops
    :epoch epoch
    :seq seq
    :subscription-id 1}))

(defn- verified
  ([kind value] (verified kind value token digest))
  ([kind value token digest]
   {:token token
    :digest digest
    :size 10
    :kind kind
    :verified? true
    :value value}))

(deftest snapshot-reference-does-not-advance-the-confirmed-copy
  (let [state (sync/receive (connected) 1 (snapshot-event snapshot-ref))]
    (is (= :awaiting-object (result state)))
    (is (= :connected-awaiting-sync (sync/phase state)))
    (is (false? (sync/command-allowed? state)))
    (is (nil? (:seq state)))
    (is (= {} (:server state)))
    (is (= token (get-in state [:pending-object :ref :token])))
    (is (= :state-snapshot (get-in state [:pending-object :kind])))))

(deftest verified-snapshot-object-establishes-the-baseline
  (let [state (sync/receive (connected) 1 (snapshot-event snapshot-ref))
        done (sync/complete-object state
                                   (verified :state-snapshot base-server))]
    (is (= :synced (sync/phase done)))
    (is (= 1 (:epoch done)))
    (is (= 0 (:seq done)))
    (is (= base-server (:server done)))
    (is (true? (sync/command-allowed? done)))
    (is (nil? (:pending-object done)))
    (is (empty? (:deferred-events done)))
    (is (= token (get-in done [:last-object :token])))
    (is (= digest (get-in done [:last-object :digest])))))

(deftest a-token-mismatch-does-not-touch-the-current-pending-object
  (let [state (sync/receive (connected) 1 (snapshot-event snapshot-ref))
        stale (sync/complete-object state
                                    (verified :state-snapshot base-server
                                              token-2 digest-2))]
    (is (= :dropped (result stale)))
    (is (= :token-mismatch (reason stale)))
    (is (some? (:pending-object stale)))
    (is (= token (get-in stale [:pending-object :ref :token])))))

(deftest corrupt-or-unverified-objects-force-a-fresh-baseline
  (let [pending (sync/receive (connected) 1 (snapshot-event snapshot-ref))]
    (doseq [[label object-result expected]
            [["digest" (verified :state-snapshot base-server token digest-2)
              :object-digest-mismatch]
             ["size" (assoc (verified :state-snapshot base-server) :size 11)
              :object-size-mismatch]
             ["unverified" (assoc (verified :state-snapshot base-server)
                                  :verified? false)
              :object-unverified]
             ["shape" (verified :state-snapshot [:not :a :map])
              :object-payload-invalid]
             ["kind" (assoc (verified :state-snapshot base-server)
                            :kind :state-delta)
              :object-kind-mismatch]]]
      (testing label
        (let [state (sync/complete-object pending object-result)]
          (is (= :resync-required (result state)))
          (is (= expected (reason state)))
          (is (= :connected-awaiting-sync (sync/phase state)))
          (is (nil? (:pending-object state)))
          (is (false? (sync/command-allowed? state))))))))

(deftest a-delta-reference-defers-later-events-and-drains-in-order
  (let [state (synced)
        state (sync/receive state 1 (delta-event delta-ref))
        state (sync/receive state 1
                            (inline-delta [{"op" "add" "path" "/a"
                                            "value" 1}]
                                          1 2))
        state (sync/receive state 1
                            (inline-delta [{"op" "add" "path" "/b"
                                            "value" 2}]
                                          1 3))]
    (is (= 0 (:seq state)))
    (is (= 2 (count (:deferred-events state))))
    (is (false? (sync/command-allowed? state)))
    (let [done (sync/complete-object
                state
                (verified :state-delta
                          [{"op" "add" "path" "/first" "value" 0}]))]
      (is (= 3 (:seq done)))
      (is (= 0 (get-in done [:server :first])))
      (is (= 1 (get-in done [:server :a])))
      (is (= 2 (get-in done [:server :b])))
      (is (true? (sync/command-allowed? done)))
      (is (empty? (:deferred-events done))))))

(deftest a-deferred-reference-chains-a-second-fetch
  (let [state (synced)
        state (sync/receive state 1 (delta-event delta-ref))
        state (sync/receive state 1
                            (delta-event (assoc delta-ref
                                                :token token-2
                                                :digest digest-2
                                                :seq 2)
                                         1 2))
        state (sync/receive state 1
                            (inline-delta [{"op" "add" "path" "/late"
                                            "value" 3}]
                                          1 3))
        first-done (sync/complete-object
                    state
                    (verified :state-delta
                              [{"op" "add" "path" "/one" "value" 1}]))]
    (is (= 1 (:seq first-done)))
    (is (= token-2 (get-in first-done [:pending-object :ref :token])))
    (is (= 1 (count (:deferred-events first-done))))
    (let [second-done (sync/complete-object
                       first-done
                       (verified :state-delta
                                 [{"op" "add" "path" "/two" "value" 2}]
                                 token-2 digest-2))]
      (is (= 3 (:seq second-done)))
      (is (= 1 (get-in second-done [:server :one])))
      (is (= 2 (get-in second-done [:server :two])))
      (is (= 3 (get-in second-done [:server :late])))
      (is (nil? (:pending-object second-done))))))

(deftest duplicate-pending-reference-is-ignored
  (let [state (sync/receive (connected) 1 (snapshot-event snapshot-ref))
        state (sync/receive state 1 (snapshot-event snapshot-ref))]
    (is (= :dropped (result state)))
    (is (= :duplicate-pending-object (reason state)))
    (is (empty? (:deferred-events state)))))

(deftest a-backlog-overflow-requires-a-fresh-baseline
  (let [state (-> (synced)
                  (sync/receive 1 (delta-event delta-ref)))
        state (reduce (fn [state n]
                        (sync/receive state 1
                                      (inline-delta
                                       [{"op" "add"
                                         "path" (str "/k" n)
                                         "value" n}]
                                       1 (inc n))))
                      state
                      (range 33))]
    (is (= :resync-required (result state)))
    (is (= :object-backlog (reason state)))
    (is (= :connected-awaiting-sync (sync/phase state)))
    (is (nil? (:pending-object state)))
    (is (empty? (:deferred-events state)))))

(deftest stale-and-gapped-references-are-rejected
  (testing "an older epoch reference is dropped without a pending object"
    (let [state (synced base-server 2 0)
          state (sync/receive state 1
                              (snapshot-event (assoc snapshot-ref
                                                     :epoch 1
                                                     :seq 1)
                                              1 1))]
      (is (= :dropped (result state)))
      (is (= :stale-epoch (reason state)))
      (is (nil? (:pending-object state)))))
  (testing "a delta reference with a sequence gap requires resynchronization"
    (let [state (synced)
          state (sync/receive state 1
                              (delta-event (assoc delta-ref :seq 3)
                                           1 3))]
      (is (= :resync-required (result state)))
      (is (= :delta-gap (reason state)))
      (is (nil? (:pending-object state))))))

(deftest object-failure-and-reconnect-clear-pending-state
  (let [pending (sync/receive (connected) 1 (snapshot-event snapshot-ref))
        failed (sync/object-failed pending :state-object-failed)]
    (is (= :resync-required (result failed)))
    (is (= :state-object-failed (reason failed)))
    (is (= :connected-awaiting-sync (sync/phase failed)))
    (is (nil? (:pending-object failed)))
    (is (false? (sync/command-allowed? failed))))
  (let [pending (sync/receive (connected) 1 (snapshot-event snapshot-ref))
        reconnected (sync/connect pending)]
    (is (= :connecting (sync/phase reconnected)))
    (is (= 2 (:subscription reconnected)))
    (is (nil? (:pending-object reconnected)))
    (is (empty? (:deferred-events reconnected)))))

(deftest ambiguous-inline-and-reference-payloads-force-resync
  (let [state (sync/receive (connected)
                            1
                            (assoc (snapshot-event snapshot-ref)
                                   :snapshot {:a 1}))]
    (is (= :resync-required (result state)))
    (is (= :ambiguous-snapshot (reason state)))
    (is (nil? (:pending-object state))))
  (let [state (sync/receive (synced)
                            1
                            (assoc (delta-event delta-ref)
                                   :delta [{"op" "add"
                                            "path" "/x"
                                            "value" 1}]))]
    (is (= :resync-required (result state)))
    (is (= :delta-invalid (reason state)))
    (is (nil? (:pending-object state)))))

(deftest reference-validation-is-strict
  (is (:ok? (object/valid-ref :state-snapshot snapshot-ref 1 0)))
  (doseq [[label ref]
          [["token" (assoc snapshot-ref :token "not-hex")]
           ["digest" (assoc snapshot-ref :digest "sha256-nope")]
           ["size" (assoc snapshot-ref :size -1)]
           ["size beyond Long" (assoc snapshot-ref
                                       :size (bigint "999999999999999999999999999999"))]
           ["encoding" (assoc snapshot-ref :encoding "json")]
           ["kind" (assoc snapshot-ref :kind :state-delta)]
           ["epoch" (assoc snapshot-ref :epoch 2)]
           ["seq" (assoc snapshot-ref :seq 9)]]]
    (testing label
      (let [checked (object/valid-ref :state-snapshot ref 1 0)]
        (is (false? (:ok? checked)))
        (is (= :STATE_OBJECT_REF_INVALID (get-in checked [:error :code])))))))

(deftest ed-text-decoding-is-honest
  (let [ok (sync/decode-object-text "{:a 1 :b [2 3]}")]
    (is (:ok? ok))
    (is (= {:a 1 :b [2 3]} (:value ok))))
  (let [bad (sync/decode-object-text "{:a")]
    (is (false? (:ok? bad)))
    (is (= :STATE_OBJECT_DECODE_FAILED (get-in bad [:error :code])))))

(defspec snapshot-ref-hydration-equals-inline
  50
  (prop/for-all [snapshot (gen/map gen/keyword gen/small-integer)]
    (let [inline (sync/receive (connected) 1
                               {:type "STATE_SNAPSHOT"
                                :snapshot snapshot
                                :epoch 1
                                :seq 0
                                :subscription-id 1})
          pending (sync/receive (connected) 1 (snapshot-event snapshot-ref))
          hydrated (sync/complete-object
                    pending
                    (verified :state-snapshot snapshot))]
      (and (= (:server inline) (:server hydrated))
           (= (:epoch inline) (:epoch hydrated))
           (= (:seq inline) (:seq hydrated))
           (= (sync/phase inline) (sync/phase hydrated))
           (= (sync/command-allowed? inline)
              (sync/command-allowed? hydrated))))))

(defspec delta-ref-hydration-equals-inline
  50
  (prop/for-all [value gen/small-integer]
    (let [ops [{"op" "add" "path" "/x" "value" value}]
          inline (sync/receive (synced) 1 (inline-delta ops))
          pending (sync/receive (synced) 1 (delta-event delta-ref))
          hydrated (sync/complete-object
                    pending
                    (verified :state-delta ops))]
      (and (= (:server inline) (:server hydrated))
           (= (:seq inline) (:seq hydrated))
           (= (sync/phase inline) (sync/phase hydrated))))))
