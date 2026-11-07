(ns hc.compose.fleet.slice-property-test
  "Property tests for slice admission: unique ids, known dependencies and
  acyclic graphs.  The module intentionally does not police goal/path
  disjointness; that remains the caller's assignment decision."
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.compose.fleet.slice :as slice]))

(defn- slice
  [id deps]
  {:slice-id id
   :goal (str "goal-" (name id))
   :inputs []
   :depends-on deps
   :done-when "done"})

(defn- ids
  [n]
  (mapv #(keyword (str "s" %)) (range n)))

(defspec valid-dags-are-admitted-and-preserve-order 50
  (prop/for-all [n (gen/choose 1 8)
                 raw-deps (gen/vector (gen/vector (gen/choose 0 7) 0 3) 8)]
    (let [slices (mapv (fn [i deps]
                         (slice (keyword (str "s" i))
                                (->> deps
                                     (filter #(< % i))
                                     distinct
                                     (mapv #(keyword (str "s" %))))))
                       (range n)
                       raw-deps)
          result (slice/validate-slices slices)]
      (and (:ok? result)
           (= (mapv :slice-id slices)
              (mapv :slice-id (:slices result)))))))

(defspec duplicate-ids-are-rejected 50
  (prop/for-all [n (gen/choose 1 6)
                 extra (gen/choose 0 5)]
    (let [base (mapv #(slice (keyword (str "s" %)) []) (range n))
          duplicate (slice (keyword (str "s" (min extra (dec n)))) [])
          result (slice/validate-slices (conj base duplicate))]
      (and (false? (:ok? result))
           (= :DUPLICATE_SLICE_ID (get-in result [:error :code]))))))

(defspec cycles-are-rejected 50
  (prop/for-all [n (gen/choose 2 8)]
    (let [slices (mapv (fn [i]
                         (slice (keyword (str "s" i))
                                [(keyword (str "s" (mod (inc i) n)))]))
                       (range n))
          result (slice/validate-slices slices)]
      (and (false? (:ok? result))
           (= :SLICE_DEPENDENCY_CYCLE (get-in result [:error :code]))))))
