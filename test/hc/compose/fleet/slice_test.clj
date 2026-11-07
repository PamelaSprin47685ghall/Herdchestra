(ns hc.compose.fleet.slice-test
  (:require [clojure.test :refer [deftest is testing]]
            [hc.compose.fleet.slice :as slice]))

(defn- slice
  ([id] (slice id []))
  ([id deps]
   {:slice-id id
    :goal (str "goal " id)
    :inputs []
    :depends-on deps
    :done-when "the slice is complete"}))

(deftest validates-the-protocol-shape
  (let [result (slice/validate-slices [(slice "a") (slice "b" ["a"])])]
    (is (:ok? result))
    (is (= ["a" "b"] (mapv :slice-id (:slices result))))))

(deftest rejects-duplicate-slice-ids
  (let [result (slice/validate-slices [(slice "a") (slice "a")])]
    (is (false? (:ok? result)))
    (is (= :DUPLICATE_SLICE_ID (get-in result [:error :code])))))

(deftest rejects-unknown-dependencies
  (let [result (slice/validate-slices [(slice "a" ["missing"])])]
    (is (false? (:ok? result)))
    (is (= :UNKNOWN_DEPENDENCY (get-in result [:error :code])))))

(deftest rejects-self-and-cyclic-dependencies
  (let [self (slice/validate-slices [(slice "a" ["a"])])
        cycle (slice/validate-slices [(slice "a" ["b"])
                                      (slice "b" ["a"])])]
    (is (= :SELF_DEPENDENCY (get-in self [:error :code])))
    (is (= :SLICE_DEPENDENCY_CYCLE (get-in cycle [:error :code])))))

(deftest accepts-dependencies-on-existing-slices
  (let [result (slice/validate-slices [(slice "b" ["a"])] #{"a"})]
    (is (:ok? result))))

(deftest does-not-police-paths-or-goals
  (testing "disjointness is an assignment decision, not a path check"
    (let [overlapping {:slice-id "a"
                       :goal "same goal"
                       :inputs ["src/shared.clj"]
                       :depends-on []
                       :done-when "done"}
          other (assoc overlapping :slice-id "b")
          result (slice/validate-slices [overlapping other])]
      (is (:ok? result)
          "the runtime must not pretend that overlapping paths are disjoint"))))

(deftest requires-the-full-slice-shape
  (let [result (slice/validate-slices [{:slice-id "a" :goal "g"}])]
    (is (false? (:ok? result)))
    (is (= :MISSING_SLICE_FIELD (get-in result [:error :code])))))
