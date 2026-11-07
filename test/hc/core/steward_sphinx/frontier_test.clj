(ns hc.core.steward-sphinx.frontier-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.core.steward-sphinx.frontier :as frontier]))

(defn cognition
  [version]
  {:cognition-version version
   :best-answer {:answer-id (keyword (str "cba-" version))
                 :cognition-version version
                 :non-final? true
                 :summary "answer"}})

(defn capacity
  [total allocated missions]
  {:slots-total total
   :slots-allocated allocated
   :missions missions})

(defn intent
  [revision text]
  {:revision revision :text text})

(defn evaluate
  [state intent-value capacity-value cognition-value]
  (frontier/evaluate-frontier
   state
   {:intent intent-value
    :capacity capacity-value
    :cognition cognition-value}))

(defn primed-state
  []
  (:state
   (evaluate (frontier/initial-frontier-state)
             (intent 0 "hold")
             (capacity 4 0 [{:mission-id "m1"
                             :status :active
                             :absorbable 2}])
             (cognition 1))))

(deftest first-intent-is-a-frontier
  (let [result (evaluate (frontier/initial-frontier-state)
                         (intent 0 "first")
                         (capacity 4 4 [])
                         (cognition 1))]
    (is (:ok? result))
    (is (= [:operator-intent] (:reasons result)))
    (is (= [:operator-intent] (get-in result [:frontier :reasons])))
    (is (= "first" (get-in result [:frontier :operator-intent :text])))
    (is (true? (get-in result [:frontier :pull-only?])))))

(deftest new-current-best-answer-alone-does-not-summon-the-steward
  (let [state (primed-state)
        result (evaluate state
                         (intent 0 "hold")
                         (capacity 4 0 [{:mission-id "m1"
                                         :status :active
                                         :absorbable 2}])
                         (cognition 99))]
    (is (:ok? result))
    (is (nil? (:frontier result)))
    (is (= [] (:reasons result)))))

(deftest same-intent-does-not-repeat
  (let [state (primed-state)
        r1 (evaluate state (intent 0 "hold")
                     (capacity 4 0 [{:mission-id "m1"
                                     :status :active
                                     :absorbable 2}])
                     (cognition 2))
        state (get r1 :state)
        r2 (evaluate state (intent 0 "hold")
                     (capacity 4 0 [{:mission-id "m1"
                                     :status :active
                                     :absorbable 2}])
                     (cognition 3))]
    (is (nil? (:frontier r1)))
    (is (nil? (:frontier r2)))))

(deftest modified-text-at-same-revision-is-new-intent
  (let [state (primed-state)
        result (evaluate state
                         (intent 0 "hold, but faster")
                         (capacity 4 0 [{:mission-id "m1"
                                         :status :active
                                         :absorbable 2}])
                         (cognition 2))]
    (is (= [:operator-intent] (:reasons result)))
    (is (= "hold, but faster"
           (get-in result [:frontier :operator-intent :text])))))

(deftest received-at-is-part-of-the-intent-signature
  (let [state (primed-state)
        result (evaluate state
                         {:revision 0 :text "hold" :received-at 42}
                         (capacity 4 0 [{:mission-id "m1"
                                         :status :active
                                         :absorbable 2}])
                         (cognition 2))]
    (is (= [:operator-intent] (:reasons result)))
    (is (= 42 (get-in result [:frontier :operator-intent :received-at])))
    (is (= 42 (get-in result [:state :intent-cursor :received-at])))))

(deftest invalid-cognition-is-refused
  (let [state (primed-state)
        missing-version (evaluate state
                                  (intent 1 "go")
                                  (capacity 4 0 [])
                                  {:best-answer {:cognition-version 1
                                                 :non-final? true}})
        mismatched-version (evaluate state
                                     (intent 1 "go")
                                     (capacity 4 0 [])
                                     {:cognition-version 2
                                      :best-answer {:cognition-version 1
                                                    :non-final? true}})
        final-answer (evaluate state
                               (intent 1 "go")
                               (capacity 4 0 [])
                               {:cognition-version 1
                                :best-answer {:cognition-version 1
                                              :non-final? false}})]
    (is (= :INVALID_COGNITION (get-in missing-version [:error :code])))
    (is (= :INVALID_COGNITION (get-in mismatched-version [:error :code])))
    (is (= :INVALID_COGNITION (get-in final-answer [:error :code])))
    (is (= state (:state missing-version)))
    (is (= state (:state mismatched-version)))
    (is (= state (:state final-answer)))))

(deftest idle-capacity-is-a-frontier-once
  (let [state (primed-state)
        r1 (evaluate state (intent 0 "hold")
                     (capacity 4 0 [])
                     (cognition 2))
        state (get r1 :state)
        r2 (evaluate state (intent 0 "hold")
                     (capacity 4 0 [])
                     (cognition 3))]
    (is (= [:capacity] (:reasons r1)))
    (is (= 4 (get-in r1 [:frontier :capacity :spare])))
    (is (= 0 (get-in r1 [:frontier :capacity :absorbable])))
    (is (true? (:capacity-frontier? r1)))
    (is (true? (:capacity-frontier-emitted? r1)))
    (is (nil? (:frontier r2)))))

(deftest new-answer-with-idle-capacity-does-not-retrigger
  (let [state (primed-state)
        r1 (evaluate state (intent 0 "hold")
                     (capacity 4 0 [])
                     (cognition 2))
        state (get r1 :state)
        r2 (evaluate state (intent 0 "hold")
                     (capacity 4 0 [])
                     (cognition 99))]
    (is (= [:capacity] (:reasons r1)))
    (is (nil? (:frontier r2)))
    (is (= [] (:reasons r2)))
    (is (true? (:capacity-frontier? r2)))
    (is (false? (:capacity-frontier-emitted? r2)))))

(deftest absorbable-work-prevents-capacity-frontier
  (let [state (primed-state)
        result (evaluate state
                         (intent 0 "hold")
                         (capacity 8 0 [{:mission-id "m1"
                                         :status :active
                                         :absorbable 1}])
                         (cognition 2))]
    (is (nil? (:frontier result)))
    (is (false? (:capacity-frontier? result)))))

(deftest planning-work-prevents-capacity-frontier
  (let [state (primed-state)
        result (evaluate state
                         (intent 0 "hold")
                         (capacity 8 0 [{:mission-id "m1"
                                         :status :planning
                                         :absorbable 2}])
                         (cognition 2))]
    (is (nil? (:frontier result)))
    (is (false? (:capacity-frontier? result)))))

(deftest capacity-change-emits-a-new-frontier
  (let [state (primed-state)
        r1 (evaluate state (intent 0 "hold")
                     (capacity 4 0 [])
                     (cognition 2))
        state (get r1 :state)
        r2 (evaluate state (intent 0 "hold")
                     (capacity 8 0 [])
                     (cognition 3))]
    (is (= [:capacity] (:reasons r1)))
    (is (= [:capacity] (:reasons r2)))
    (is (= 8 (get-in r2 [:frontier :capacity :spare])))))

(deftest both-reasons-are-reported
  (let [state (primed-state)
        result (evaluate state
                         (intent 1 "go")
                         (capacity 4 0 [])
                         (cognition 2))]
    (is (= [:operator-intent :capacity] (:reasons result)))
    (is (= [:operator-intent :capacity]
           (get-in result [:frontier :reasons])))))

(deftest frontier-carries-the-published-answer
  (let [answer (cognition 7)
        state (primed-state)
        result (evaluate state
                         (intent 1 "go")
                         (capacity 4 0 [])
                         answer)]
    (is (= (:best-answer answer)
           (get-in result [:frontier :best-answer])))
    (is (= 7 (get-in result [:frontier :cba-version])))
    (is (= 7 (get-in result [:frontier :cognition-version])))))

(deftest frontier-ids-increment-only-on-emission
  (let [state (primed-state)
        r1 (evaluate state (intent 1 "go")
                     (capacity 4 0 [])
                     (cognition 2))
        state (get r1 :state)
        r2 (evaluate state (intent 1 "go")
                     (capacity 4 0 [])
                     (cognition 3))
        state (get r2 :state)
        r3 (evaluate state (intent 2 "go again")
                     (capacity 4 0 [])
                     (cognition 4))]
    (is (= "frontier-2" (get-in r1 [:frontier :frontier-id])))
    (is (nil? (:frontier r2)))
    (is (= "frontier-3" (get-in r3 [:frontier :frontier-id])))
    (is (= 3 (get-in r3 [:state :frontier-seq])))))

(deftest invalid-capacity-is-refused
  (let [state (primed-state)
        result (evaluate state
                         (intent 0 "hold")
                         (capacity 2 3 [])
                         (cognition 2))]
    (is (not (:ok? result)))
    (is (= :INVALID_CAPACITY (get-in result [:error :code])))
    (is (= state (:state result)))))

(deftest pull-only-property
  (is (:result
       (tc/quick-check
        50
        (prop/for-all [cba-version (gen/choose 0 1000)
                       absorbable (gen/choose 1 8)]
          (let [state (primed-state)
                result (evaluate state
                                 (intent 0 "hold")
                                 (capacity 8 0 [{:mission-id "m1"
                                                 :status :active
                                                 :absorbable absorbable}])
                                 (cognition cba-version))]
            (and (:ok? result)
                 (nil? (:frontier result))
                 (= [] (:reasons result)))))))))
