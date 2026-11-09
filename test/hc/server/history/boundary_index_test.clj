(ns hc.server.history.boundary-index-test
  "Equivalence of the 2-arity boundary-index (precomputed order index) with
  the 1-arity form.

  The optimization removed the per-message rebuild of the order index in
  outbound-context.  These tests pin the map contract: the boundary still
  selects the manual boundary message position, or reports an ineffective
  nil index when that message is absent.  Relay, scope-reset and compaction
  markers are message metadata in this model; boundary-index itself reads
  only `[:context :boundary :message-id]`."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :refer [for-all]]
            [hc.server.history.context :as context]))

(defn- order-map
  [session]
  (into {}
        (map-indexed (fn [index message-id] [message-id index])
                     (:message-order session))))

(defn- boundary-consistent?
  [session]
  (= (context/boundary-index session (order-map session))
     (context/boundary-index session)))

(defn- session-with
  [order messages context]
  {:session-id "s1"
   :message-order order
   :messages messages
   :context context})

(defn- with-boundary
  [session message-id]
  (assoc-in session [:context :boundary] {:message-id message-id}))

(defn- plain-message
  [message-id]
  {:message-id message-id
   :role :user
   :versions [{:version-id (str message-id "-v1")
               :content message-id
               :attachments []
               :citations []
               :quotes []
               :tool-records []}]
   :current-version 0
   :deleted? false
   :folded? false})

(deftest interleaved-relay-scope-reset-and-compaction-positions
  (let [order (mapv #(str "m" %) (range 20))
        kinds {0 :relay
               3 :scope-reset
               5 :compaction
               7 :relay
               11 :scope-reset
               15 :relay
               19 :compaction}
        messages (into {}
                       (map-indexed
                        (fn [index message-id]
                          [message-id
                           (cond-> (plain-message message-id)
                             (contains? kinds index)
                             (assoc :boundary-kind (kinds index)))])
                        order))
        base (session-with order messages {})
        positions (vec (sort (keys kinds)))]
    (testing "every kind at a different position keeps the same index"
      (doseq [position positions
              :let [message-id (nth order position)
                    session (with-boundary base message-id)
                    two-arity (context/boundary-index session (order-map session))
                    one-arity (context/boundary-index session)]]
        (is (= one-arity two-arity)
            (str "arities disagree at " position))
        (is (= {:index position
                :effective? true
                :message-id message-id}
               one-arity))))
    (testing "the first message is position zero"
      (let [session (with-boundary base (first order))
            result (context/boundary-index session (order-map session))]
        (is (= 0 (:index result)))
        (is (= order (subvec order (:index result))))
        (is (= [] (subvec order 0 (:index result))))
        (is (= result (context/boundary-index session)))))
    (testing "a boundary after the last message is ineffective, not clamped"
      (let [session (with-boundary base "after-last")
            result (context/boundary-index session (order-map session))]
        (is (= {:index nil :effective? false :message-id "after-last"}
               result))
        (is (= result (context/boundary-index session)))))
    (testing "a nil boundary and a zero position are both index zero"
      (let [nil-result (context/boundary-index base (order-map base))
            zero-result (context/boundary-index
                         (with-boundary base (first order))
                         (order-map base))]
        (is (= 0 (:index nil-result) (:index zero-result)))
        (is (:effective? nil-result))
        (is (:effective? zero-result))
        (is (= nil-result (context/boundary-index base)))))))

(deftest consecutive-boundaries-do-not-offset-the-slice
  (let [order (mapv #(str "m" %) (range 5))
        messages (into {} (map (fn [id] [id (plain-message id)]) order))
        base (session-with order messages {})]
    (doseq [position (range (dec (count order)))]
      (let [left (with-boundary base (nth order position))
            right (with-boundary base (nth order (inc position)))
            left-result (context/boundary-index left (order-map left))
            right-result (context/boundary-index right (order-map right))]
        (is (= position (:index left-result)))
        (is (= (inc position) (:index right-result)))
        (is (= left-result (context/boundary-index left)))
        (is (= right-result (context/boundary-index right)))
        (is (seq (subvec order (:index left-result)))
            "the slice from a boundary is never empty")
        (is (seq (subvec order (:index right-result))))))))

(deftest deleted-and-folded-neighbours-keep-the-same-index
  (let [order (mapv #(str "m" %) (range 4))
        hidden (into {}
                     (map (fn [index message-id]
                            [message-id
                             (cond-> (plain-message message-id)
                               (= index 1) (assoc :deleted? true)
                               (= index 2) (assoc :folded? true))])
                          (range) order))
        base (session-with order hidden {})
        clean (session-with order
                            (into {} (map (fn [id] [id (plain-message id)])
                                          order))
                            {})]
    (doseq [index (range (count order))
            :let [message-id (nth order index)
                  hidden-session (with-boundary base message-id)
                  clean-session (with-boundary clean message-id)
                  hidden-result (context/boundary-index
                                 hidden-session (order-map hidden-session))
                  clean-result (context/boundary-index
                                clean-session (order-map clean-session))]]
      (is (= index (:index hidden-result)))
      (is (= index (:index clean-result)))
      (is (= hidden-result clean-result))
      (is (= hidden-result (context/boundary-index hidden-session))))))

(deftest stale-compression-does-not-change-the-boundary
  (let [order (mapv #(str "m" %) (range 4))
        messages (into {} (map (fn [id] [id (plain-message id)]) order))
        stale {:compression-id "c1"
               :state :applied
               :stale? true
               :message-ids ["m1" "m2"]
               :summary "stale summary"}
        current (assoc stale :stale? false)
        variants [(session-with order messages {})
                  (session-with order messages
                                {:compressions {"c1" stale}})
                  (session-with order messages
                                {:compressions {"c1" stale}
                                 :compression-order ["c1"]})
                  (session-with order messages
                                {:compressions {"c1" current}
                                 :compression-order ["c1"]})]]
    (doseq [session variants
            message-id ["m0" "m1" "m2" "m3"]
            :let [with-boundary (with-boundary session message-id)
                  result (context/boundary-index with-boundary
                                                  (order-map with-boundary))]]
      (is (= result (context/boundary-index with-boundary)))
      (is (= (get (order-map with-boundary) message-id)
             (:index result)))
      (is (:effective? result)))))

(deftest empty-and-single-message-sessions
  (let [empty-session (session-with [] {} {})
        empty-result (context/boundary-index empty-session
                                             (order-map empty-session))
        empty-missing (with-boundary empty-session "missing")
        missing-result (context/boundary-index empty-missing
                                               (order-map empty-missing))]
    (is (= {:index 0 :effective? true :message-id nil} empty-result))
    (is (= empty-result (context/boundary-index empty-session)))
    (is (= {:index nil :effective? false :message-id "missing"}
           missing-result))
    (is (= missing-result (context/boundary-index empty-missing))))
  (let [order ["m0"]
        session (session-with order {"m0" (plain-message "m0")} {})
        nil-result (context/boundary-index session (order-map session))
        zero-result (context/boundary-index (with-boundary session "m0")
                                            (order-map session))
        missing (with-boundary session "missing")
        missing-result (context/boundary-index missing (order-map missing))
        nil-id (assoc-in session [:context :boundary] {})
        nil-id-result (context/boundary-index nil-id (order-map nil-id))]
    (is (= 0 (:index nil-result) (:index zero-result)
            (:index nil-id-result)))
    (is (:effective? nil-result))
    (is (:effective? zero-result))
    (is (:effective? nil-id-result))
    (is (= {:index nil :effective? false :message-id "missing"}
           missing-result))
    (doseq [s [session (with-boundary session "m0") missing nil-id]]
      (is (boundary-consistent? s)))))

(defspec boundary-index-arity-equivalence-under-random-boundaries
  30
  (for-all [positions (gen/set (gen/choose 0 999)
                               {:num-elements 100})
            pick (gen/choose -1 1000)]
    (let [order (mapv #(str "p" %) (range 1000))
          marked (set positions)
          messages (into {}
                         (map (fn [index]
                                (let [message-id (nth order index)]
                                  [message-id
                                   (cond-> (plain-message message-id)
                                     (contains? marked index)
                                     (assoc :boundary-kind
                                            (nth [:relay :scope-reset
                                                  :compaction]
                                                 (mod index 3)))
                                     (zero? (mod index 7))
                                     (assoc :deleted? true))]))
                              (range 1000)))
          boundary-id (cond
                        (neg? pick) nil
                        (>= pick 1000) "after-last"
                        :else (nth order pick))
          base (session-with order messages
                             (cond-> {}
                               (some? boundary-id)
                               (assoc :boundary {:message-id boundary-id})))
          index-map (order-map base)
          expected (context/boundary-index base index-map)]
      (and (= expected (context/boundary-index base))
           (every? (fn [rounds]
                     (let [session (assoc-in base
                                             [:context :recent-rounds]
                                             rounds)]
                       (= (context/boundary-index session
                                                   (order-map session))
                          (context/boundary-index session)
                          (context/boundary-index base))))
                   [nil 0 3])))))
