(ns hc.core.steward-sphinx.docket-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.core.steward-sphinx.docket :as docket]))

(defn cognition
  []
  {:cognition-version 3
   :evidence {"git:c1" {:evidence-id "git:c1"
                        :kind :git-commit
                        :commit "c1"
                        :seq 1}}
   :assumptions {}
   :best-answer {:cognition-version 3
                 :non-final? true}})

(defn cognition-with-assumption
  []
  (-> (cognition)
      (assoc-in [:assumptions "a1"]
                {:assumption-id "a1"
                 :statement "src is synchronous"
                 :confidence 0.8
                 :watch-paths ["src"]
                 :basis ["git:c1"]})))

(defn cognition-with-stale-assumption
  []
  (-> (cognition-with-assumption)
      (assoc-in [:evidence "git:c2"]
                {:evidence-id "git:c2"
                 :kind :git-commit
                 :commit "c2"
                 :seq 2
                 :changed-paths ["src/b.clj"]})))

(defn frontier
  [reasons]
  {:frontier-id "frontier-1"
   :frontier-seq 1
   :reasons reasons
   :operator-intent {:revision 1 :text "make it better"}
   :capacity {:slots-total 4
              :slots-allocated 0
              :spare 4
              :absorbable 0
              :min-spare 1
              :missions []}
   :best-answer {:cognition-version 3
                 :non-final? true}
   :cognition-version 3
   :cba-version 3
   :pull-only? true})

(defn capacity-frontier
  [spare]
  (assoc (frontier [:capacity])
         :capacity {:slots-total 4
                    :slots-allocated (- 4 spare)
                    :spare spare
                    :absorbable 0
                    :min-spare 1
                    :missions []}))

(defn candidate
  [overrides]
  (merge {:candidate-id "c1"
          :title "Add a cache"
          :consequence 0.9
          :evidence-strength 0.9
          :dependency-cost 0.1
          :reversibility 0.9
          :opportunity-cost 0.1
          :demand 1
          :evidence-ids ["git:c1"]
          :assumption-ids []
          :depends-on []}
         overrides))

(defn decide
  ([docket frontier-value candidates]
   (decide docket frontier-value candidates nil))
  ([docket frontier-value candidates policy]
   (docket/decide docket
                  (cond-> {:request-id (str (random-uuid))
                           :frontier frontier-value
                           :cognition (cognition)
                           :candidates candidates}
                    policy (assoc :policy policy)))))

(deftest high-value-candidate-is-admitted
  (let [result (decide (docket/initial-docket)
                       (frontier [:operator-intent])
                       [(candidate {})])
        receipt (:receipt result)
        docket' (:docket result)
        mission (get-in docket' [:missions "mission-1"])]
    (is (:ok? result))
    (is (= :admit (:outcome receipt)))
    (is (= ["mission-1"] (:admitted receipt)))
    (is (false? (:idle? receipt)))
    (is (= :admitted (:status mission)))
    (is (= "c1" (:candidate-id mission)))
    (is (= "frontier-1" (:frontier-id mission)))
    (is (= 1 (:docket-version docket')))
    (is (<= 0.69 (get-in receipt [:decisions 0 :score]) 0.71))))

(deftest low-value-candidate-is-rejected
  (let [result (decide (docket/initial-docket)
                       (frontier [:operator-intent])
                       [(candidate {:consequence 0.1
                                    :evidence-strength 0.6
                                    :dependency-cost 0.9
                                    :opportunity-cost 0.9})])
        receipt (:receipt result)
        docket' (:docket result)]
    (is (= :reject (get-in receipt [:decisions 0 :outcome])))
    (is (= [:below-threshold] (get-in receipt [:decisions 0 :reasons])))
    (is (true? (:idle? receipt)))
    (is (= [] (:admitted receipt)))
    (is (contains? (:do-not-do docket') "c1"))
    (is (empty? (:missions docket')))
    (is (true? (get-in docket' [:do-not-do "c1" :reconsiderable?])))))

(deftest weak-evidence-is-deferred
  (let [result (decide (docket/initial-docket)
                       (frontier [:operator-intent])
                       [(candidate {:consequence 1.0
                                    :evidence-strength 0.2
                                    :dependency-cost 0.0
                                    :reversibility 1.0
                                    :opportunity-cost 0.0})])
        receipt (:receipt result)
        docket' (:docket result)]
    (is (= :defer (get-in receipt [:decisions 0 :outcome])))
    (is (= [:needs-more-evidence] (get-in receipt [:decisions 0 :reasons])))
    (is (contains? (:awaiting-evidence docket') "c1"))
    (is (= :evidence (get-in docket' [:awaiting-evidence "c1" :needed])))
    (is (empty? (:missions docket')))))

(deftest unmet-dependencies-are-deferred-until-completed
  (let [docket-with-dependency (assoc-in (docket/initial-docket)
                                         [:missions "mission-1"]
                                         {:mission-id "mission-1"
                                          :status :admitted})
        candidate-with-dependency (candidate {:depends-on ["mission-1"]})
        r1 (decide docket-with-dependency
                   (frontier [:operator-intent])
                   [candidate-with-dependency])
        docket1 (:docket r1)
        active (docket/transition-mission docket1
                                          {:mission-id "mission-1"
                                           :to :active})
        completed (docket/transition-mission (:docket active)
                                             {:mission-id "mission-1"
                                              :to :completed})
        r2 (decide (:docket completed)
                   (frontier [:operator-intent])
                   [candidate-with-dependency])]
    (is (= :defer (get-in r1 [:receipt :decisions 0 :outcome])))
    (is (= [:unmet-dependencies]
           (get-in r1 [:receipt :decisions 0 :reasons])))
    (is (contains? (:deferred docket1) "c1"))
    (is (:ok? active))
    (is (:ok? completed))
    (is (= :admit (get-in r2 [:receipt :decisions 0 :outcome])))))

(deftest capacity-only-frontier-checks-demand
  (let [too-large (decide (docket/initial-docket)
                          (capacity-frontier 1)
                          [(candidate {:demand 2})])
        operator (decide (docket/initial-docket)
                         (frontier [:operator-intent])
                         [(candidate {:demand 2})])]
    (is (= :defer (get-in too-large [:receipt :decisions 0 :outcome])))
    (is (= [:insufficient-capacity]
           (get-in too-large [:receipt :decisions 0 :reasons])))
    (is (= :admit (get-in operator [:receipt :decisions 0 :outcome])))))

(deftest ungrounded-candidate-is-rejected
  (let [result (decide (docket/initial-docket)
                       (frontier [:operator-intent])
                       [(candidate {:evidence-ids []})])]
    (is (= :reject (get-in result [:receipt :decisions 0 :outcome])))
    (is (= [:ungrounded] (get-in result [:receipt :decisions 0 :reasons])))
    (is (contains? (:do-not-do (:docket result)) "c1"))))

(deftest contradictory-candidate-is-rejected
  (let [result (decide (docket/initial-docket)
                       (frontier [:operator-intent])
                       [(candidate {:operator-aligned? false})])]
    (is (= :reject (get-in result [:receipt :decisions 0 :outcome])))
    (is (= [:contradicts-operator-intent]
           (get-in result [:receipt :decisions 0 :reasons])))))

(deftest irreversible-candidate-needs-operator-intent
  (let [irreversible (candidate {:reversibility 0.1})
        deferred (decide (docket/initial-docket)
                         (capacity-frontier 4)
                         [irreversible])
        allowed (decide (docket/initial-docket)
                        (capacity-frontier 4)
                        [irreversible]
                        {:allow-irreversible-without-intent? true})]
    (is (= :defer (get-in deferred [:receipt :decisions 0 :outcome])))
    (is (= [:needs-operator-intent]
           (get-in deferred [:receipt :decisions 0 :reasons])))
    (is (= :admit (get-in allowed [:receipt :decisions 0 :outcome])))))

(deftest capacity-only-frontier-rejects-contradictory-candidate
  (let [result (decide (docket/initial-docket)
                       (capacity-frontier 4)
                       [(candidate {:operator-aligned? false})])
        decision (get-in result [:receipt :decisions 0])]
    (is (= :reject (:outcome decision)))
    (is (= [:contradicts-operator-intent] (:reasons decision)))
    (is (false? (get-in decision [:gates :operator-aligned])))
    (is (contains? (:do-not-do (:docket result)) "c1"))))

(deftest contradiction-outranks-already-admitted
  (let [docket0 (assoc-in (docket/initial-docket)
                          [:missions "mission-1"]
                          {:mission-id "mission-1"
                           :candidate-id "c1"
                           :status :admitted})
        result (decide docket0
                       (capacity-frontier 4)
                       [(candidate {:operator-aligned? false})])]
    (is (= :reject (get-in result [:receipt :decisions 0 :outcome])))
    (is (some #{:already-admitted}
              (get-in result [:receipt :decisions 0 :reasons])))
    (is (some #{:contradicts-operator-intent}
              (get-in result [:receipt :decisions 0 :reasons])))))

(deftest capacity-only-frontier-does-not-self-certify-irreversibility
  (let [irreversible (candidate {:reversibility 0.1
                                 :operator-aligned? true})
        capacity (decide (docket/initial-docket)
                         (capacity-frontier 4)
                         [irreversible])
        operator (decide (docket/initial-docket)
                         (frontier [:operator-intent])
                         [irreversible])
        override (decide (docket/initial-docket)
                         (capacity-frontier 4)
                         [irreversible]
                         {:allow-irreversible-without-intent? true})]
    (is (= :defer (get-in capacity [:receipt :decisions 0 :outcome])))
    (is (= [:needs-operator-intent]
           (get-in capacity [:receipt :decisions 0 :reasons])))
    (is (false? (get-in capacity
                        [:receipt :decisions 0 :gates :reversibility])))
    (is (= :admit (get-in operator [:receipt :decisions 0 :outcome])))
    (is (= :admit (get-in override [:receipt :decisions 0 :outcome])))))

(deftest decision-requires-a-real-frontier
  (let [before (docket/initial-docket)
        base (frontier [:operator-intent])
        empty-reasons (assoc base :reasons [])
        bogus-reason (assoc base :reasons [:bogus])
        not-pull-only (assoc base :pull-only? false)
        bad-sequence (assoc base :frontier-seq 0)
        results (mapv #(docket/decide before
                                      {:request-id (str (random-uuid))
                                       :frontier %
                                       :cognition (cognition)
                                       :candidates [(candidate {})]})
                      [empty-reasons bogus-reason not-pull-only bad-sequence])]
    (is (every? #(= :INVALID_FRONTIER (get-in % [:error :code])) results))
    (is (every? #(= before (:state %)) results))))

(deftest capacity-frontier-validates-capacity
  (let [before (docket/initial-docket)
        malformed (assoc (capacity-frontier 4) :capacity {})
        absorbing (assoc-in (capacity-frontier 4)
                            [:capacity :missions]
                            [{:mission-id "m1"
                              :status :active
                              :absorbable 1}])
        malformed-result (docket/decide before
                                        {:request-id "bad-capacity"
                                         :frontier malformed
                                         :cognition (cognition)
                                         :candidates [(candidate {})]})
        absorbing-result (docket/decide before
                                        {:request-id "absorbing-capacity"
                                         :frontier absorbing
                                         :cognition (cognition)
                                         :candidates [(candidate {})]})
        boundary (decide before
                         (capacity-frontier 1)
                         [(candidate {:demand 1})])]
    (is (= :INVALID_FRONTIER (get-in malformed-result [:error :code])))
    (is (= :INVALID_FRONTIER (get-in absorbing-result [:error :code])))
    (is (= :admit (get-in boundary [:receipt :decisions 0 :outcome])))))

(deftest frontier-version-and-finality-are-required
  (let [before (docket/initial-docket)
        base (frontier [:operator-intent])
        final-answer (assoc-in base [:best-answer :non-final?] false)
        bad-cba-version (assoc base :cba-version 2)
        bad-cognition-version (assoc base :cognition-version 2)
        bad-best-version (assoc-in base
                                   [:best-answer :cognition-version]
                                   nil)
        missing-cognition-version
        (docket/decide before
                       {:request-id "missing-cognition-version"
                        :frontier base
                        :cognition (dissoc (cognition) :cognition-version)
                        :candidates [(candidate {})]})
        inconsistent-cognition
        (docket/decide before
                       {:request-id "bad-cognition-version"
                        :frontier base
                        :cognition (assoc-in (cognition)
                                             [:best-answer :cognition-version]
                                             2)
                        :candidates [(candidate {})]})
        final-cognition
        (docket/decide before
                       {:request-id "final-cognition"
                        :frontier base
                        :cognition (assoc-in (cognition)
                                             [:best-answer :non-final?]
                                             false)
                        :candidates [(candidate {})]})
        results (mapv (fn [frontier-value]
                        (docket/decide before
                                       {:request-id (str (random-uuid))
                                        :frontier frontier-value
                                        :cognition (cognition)
                                        :candidates [(candidate {})]}))
                      [final-answer bad-cba-version bad-cognition-version
                       bad-best-version])]
    (is (= :INVALID_FRONTIER (get-in (first results) [:error :code])))
    (is (= :STALE_FRONTIER (get-in (second results) [:error :code])))
    (is (= :STALE_FRONTIER (get-in (nth results 2) [:error :code])))
    (is (= :INVALID_FRONTIER (get-in (nth results 3) [:error :code])))
    (is (= :INVALID_COGNITION
           (get-in missing-cognition-version [:error :code])))
    (is (= :INVALID_COGNITION
           (get-in inconsistent-cognition [:error :code])))
    (is (= :INVALID_COGNITION
           (get-in final-cognition [:error :code])))))

(deftest unknown-dependency-is-a-wiring-error
  (let [before (docket/initial-docket)
        result (docket/decide before
                              {:request-id "unknown-dependency"
                               :frontier (frontier [:operator-intent])
                               :cognition (cognition)
                               :candidates [(candidate {:depends-on ["missing"]})]})]
    (is (not (:ok? result)))
    (is (= :INVALID_CANDIDATE (get-in result [:error :code])))
    (is (= ["missing"]
           (get-in result [:error :details :unknown-dependencies])))
    (is (= before (:state result)))))

(deftest rejected-candidate-keeps-its-record-when-deferred
  (let [low (candidate {:consequence 0.1
                        :evidence-strength 0.6
                        :dependency-cost 0.9
                        :opportunity-cost 0.9})
        weak-but-valuable (candidate {:consequence 1.0
                                      :evidence-strength 0.2
                                      :dependency-cost 0.0
                                      :reversibility 1.0
                                      :opportunity-cost 0.0})
        first-result (decide (docket/initial-docket)
                             (frontier [:operator-intent])
                             [low])
        second-result (decide (:docket first-result)
                              (frontier [:operator-intent])
                              [weak-but-valuable])
        docket2 (:docket second-result)]
    (is (contains? (:do-not-do docket2) "c1"))
    (is (= :defer (get-in second-result [:receipt :decisions 0 :outcome])))
    (is (true? (get-in docket2 [:awaiting-evidence "c1"
                                :previously-rejected?])))))

(deftest already-admitted-with-weak-evidence-stays-in-existing-mission
  (let [first-result (decide (docket/initial-docket)
                             (frontier [:operator-intent])
                             [(candidate {})])
        weak (candidate {:consequence 1.0
                         :evidence-strength 0.2
                         :dependency-cost 0.0
                         :reversibility 1.0
                         :opportunity-cost 0.0})
        second-result (decide (:docket first-result)
                              (frontier [:operator-intent])
                              [weak])
        docket2 (:docket second-result)]
    (is (= :defer (get-in second-result [:receipt :decisions 0 :outcome])))
    (is (some #{:already-admitted}
              (get-in second-result [:receipt :decisions 0 :reasons])))
    (is (= :existing-mission (get-in docket2 [:deferred "c1" :needed])))
    (is (not (contains? (:awaiting-evidence docket2) "c1")))))

(deftest planning-status-is-representable
  (let [docket0 (assoc-in (docket/initial-docket)
                          [:missions "mission-1"]
                          {:mission-id "mission-1"
                           :candidate-id "c1"
                           :status :admitted})
        planning (docket/transition-mission docket0
                                            {:mission-id "mission-1"
                                             :to :planning})
        failed (docket/transition-mission (:docket planning)
                                          {:mission-id "mission-1"
                                           :to :failed})
        planning-capacity
        (assoc-in (capacity-frontier 4)
                  [:capacity :missions]
                  [{:mission-id "mission-2"
                    :status :planning
                    :absorbable 0}])
        duplicate (docket/decide (:docket planning)
                                 {:request-id "planning-duplicate"
                                  :frontier (frontier [:operator-intent])
                                  :cognition (cognition)
                                  :candidates [(candidate {})]})
        capacity-result (docket/decide (docket/initial-docket)
                                       {:request-id "planning-capacity"
                                        :frontier planning-capacity
                                        :cognition (cognition)
                                        :candidates [(candidate {})]})]
    (is (:ok? planning))
    (is (= :planning (get-in planning [:mission :status])))
    (is (:ok? failed))
    (is (= :failed (get-in failed [:mission :status])))
    (is (= :defer (get-in duplicate [:receipt :decisions 0 :outcome])))
    (is (= [:already-admitted]
           (get-in duplicate [:receipt :decisions 0 :reasons])))
    (is (= :admit (get-in capacity-result
                          [:receipt :decisions 0 :outcome])))))

(deftest non-finite-policy-and-score-are-refused
  (let [infinite-policy {:weights {:consequence ##Inf
                                   :evidence 0.3
                                   :reversibility 0.1
                                   :dependency 0.1
                                   :opportunity 0.1}}
        overflow-policy {:weights {:consequence 1e308
                                   :evidence 1e308
                                   :reversibility 1e308
                                   :dependency 1e308
                                   :opportunity 1e308}}
        non-numeric-policy {:weights {:consequence "high"
                                      :evidence 0.3
                                      :reversibility 0.1
                                      :dependency 0.1
                                      :opportunity 0.1}}
        infinite-threshold {:admit-threshold ##Inf}
        nan-min-evidence {:min-evidence ##NaN}
        normalized (docket/normalize-policy infinite-policy)
        overflowed (docket/normalize-policy overflow-policy)
        non-numeric (docket/normalize-policy non-numeric-policy)
        bad-threshold (docket/normalize-policy infinite-threshold)
        bad-min-evidence (docket/normalize-policy nan-min-evidence)
        scored (docket/score-candidate infinite-policy (candidate {}))]
    (is (not (:ok? normalized)))
    (is (= :INVALID_POLICY (get-in normalized [:error :code])))
    (is (not (:ok? overflowed)))
    (is (= :INVALID_POLICY (get-in overflowed [:error :code])))
    (is (not (:ok? non-numeric)))
    (is (= :INVALID_POLICY (get-in non-numeric [:error :code])))
    (is (not (:ok? bad-threshold)))
    (is (= :INVALID_POLICY (get-in bad-threshold [:error :code])))
    (is (not (:ok? bad-min-evidence)))
    (is (= :INVALID_POLICY (get-in bad-min-evidence [:error :code])))
    (is (nil? (:score scored)))
    (is (= :INVALID_POLICY (get-in scored [:error :code])))))

(deftest stale-assumption-candidate-awaits-evidence
  (let [result (docket/decide
                (docket/initial-docket)
                {:request-id "stale-request"
                 :frontier (frontier [:operator-intent])
                 :cognition (cognition-with-stale-assumption)
                 :candidates [(candidate {:assumption-ids ["a1"]})]})
        docket' (:docket result)]
    (is (= :defer (get-in result [:receipt :decisions 0 :outcome])))
    (is (= [:stale-assumption]
           (get-in result [:receipt :decisions 0 :reasons])))
    (is (contains? (:awaiting-evidence docket') "c1"))))

(deftest unknown-evidence-is-a-wiring-error
  (let [before (docket/initial-docket)
        result (decide before
                       (frontier [:operator-intent])
                       [(candidate {:evidence-ids ["git:nope"]})])]
    (is (not (:ok? result)))
    (is (= :INVALID_CANDIDATE (get-in result [:error :code])))
    (is (= before (:state result)))))

(deftest decisions-are-idempotent
  (let [request {:request-id "same-request"
                 :frontier (frontier [:operator-intent])
                 :cognition (cognition)
                 :candidates [(candidate {})]}
        first-result (docket/decide (docket/initial-docket) request)
        replay-result (docket/decide (:docket first-result) request)
        conflict-result (docket/decide
                         (:docket first-result)
                         (assoc request :candidates
                                [(candidate {:candidate-id "c2"})]))]
    (is (:ok? first-result))
    (is (:ok? replay-result))
    (is (true? (:replayed? replay-result)))
    (is (= (:receipt first-result) (:receipt replay-result)))
    (is (= 1 (count (:missions (:docket replay-result)))))
    (is (not (:ok? conflict-result)))
    (is (= :DECISION_REQUEST_CONFLICT
           (get-in conflict-result [:error :code])))
    (is (= (:docket first-result) (:state conflict-result)))))

(deftest no-frontier-means-no-decision
  (let [before (docket/initial-docket)
        result (docket/decide before
                              {:request-id "no-frontier"
                               :frontier nil
                               :cognition (cognition)
                               :candidates []})]
    (is (not (:ok? result)))
    (is (= :NO_DECISION_FRONTIER (get-in result [:error :code])))
    (is (= before (:state result)))))

(deftest malformed-frontier-is-refused
  (let [before (docket/initial-docket)
        result (docket/decide before
                              {:request-id "bad-frontier"
                               :frontier {:frontier-id "f1"}
                               :cognition (cognition)
                               :candidates []})]
    (is (not (:ok? result)))
    (is (= :INVALID_FRONTIER (get-in result [:error :code])))
    (is (= before (:state result)))))

(deftest stale-frontier-is-refused
  (let [before (docket/initial-docket)
        stale-frontier (assoc-in (frontier [:operator-intent])
                                 [:best-answer :cognition-version] 2)
        result (docket/decide before
                              {:request-id "stale-frontier"
                               :frontier stale-frontier
                               :cognition (cognition)
                               :candidates []})]
    (is (not (:ok? result)))
    (is (= :STALE_FRONTIER (get-in result [:error :code])))
    (is (= before (:state result)))))

(deftest duplicate-candidate-ids-are-refused
  (let [before (docket/initial-docket)
        result (decide before
                       (frontier [:operator-intent])
                       [(candidate {}) (candidate {})])]
    (is (not (:ok? result)))
    (is (= :INVALID_CANDIDATES (get-in result [:error :code])))
    (is (= before (:state result)))))

(deftest no-candidates-is-legitimate-idleness
  (let [result (decide (docket/initial-docket)
                       (frontier [:operator-intent])
                       [])
        receipt (:receipt result)]
    (is (:ok? result))
    (is (true? (:idle? receipt)))
    (is (= :idle (:outcome receipt)))
    (is (= :no-candidates (:mode receipt)))
    (is (= [] (:admitted receipt)))
    (is (= 1 (count (:decision-log (:docket result)))))))

(deftest rejected-candidate-can-be-reconsidered
  (let [low (candidate {:consequence 0.1
                        :evidence-strength 0.6
                        :dependency-cost 0.9
                        :opportunity-cost 0.9})
        first-result (decide (docket/initial-docket)
                             (frontier [:operator-intent])
                             [low])
        docket1 (:docket first-result)
        second-result (decide docket1
                              (frontier [:operator-intent])
                              [(candidate {})])
        docket2 (:docket second-result)]
    (is (contains? (:do-not-do docket1) "c1"))
    (is (= :admit (get-in second-result [:receipt :decisions 0 :outcome])))
    (is (not (contains? (:do-not-do docket2) "c1")))
    (is (contains? (:missions docket2) "mission-1"))))

(deftest an-admitted-candidate-is-not-admitted-twice
  (let [first-result (decide (docket/initial-docket)
                             (frontier [:operator-intent])
                             [(candidate {})])
        docket1 (:docket first-result)
        second-result (decide docket1
                              (frontier [:operator-intent])
                              [(candidate {})])
        docket2 (:docket second-result)
        third-result (decide docket2
                             (frontier [:operator-intent])
                             [(candidate {:consequence 0.1
                                          :dependency-cost 0.9
                                          :opportunity-cost 0.9})])]
    (is (= 1 (count (:missions docket1))))
    (is (= :defer (get-in second-result [:receipt :decisions 0 :outcome])))
    (is (= [:already-admitted]
           (get-in second-result [:receipt :decisions 0 :reasons])))
    (is (= 1 (count (:missions docket2))))
    (is (= "mission-1"
           (get-in second-result
                   [:receipt :decisions 0 :existing-mission-id])))
    (is (= :defer (get-in third-result [:receipt :decisions 0 :outcome])))
    (is (some #{:already-admitted}
              (get-in third-result [:receipt :decisions 0 :reasons])))))

(deftest mission-transitions-are-small-and-explicit
  (let [docket0 (assoc-in (docket/initial-docket)
                          [:missions "mission-1"]
                          {:mission-id "mission-1" :status :admitted})
        active (docket/transition-mission docket0
                                          {:mission-id "mission-1"
                                           :to :active})
        completed (docket/transition-mission (:docket active)
                                             {:mission-id "mission-1"
                                              :to :completed})
        invalid (docket/transition-mission (:docket completed)
                                           {:mission-id "mission-1"
                                            :to :active})
        repeated (docket/transition-mission (:docket completed)
                                            {:mission-id "mission-1"
                                             :to :completed})]
    (is (:ok? active))
    (is (= :active (get-in active [:mission :status])))
    (is (= :completed (get-in completed [:mission :status])))
    (is (not (:ok? invalid)))
    (is (= :INVALID_MISSION_TRANSITION (get-in invalid [:error :code])))
    (is (:ok? repeated))
    (is (true? (:unchanged? repeated)))))

(deftest docket-summary-shows-the-living-record
  (let [result (decide (docket/initial-docket)
                       (frontier [:operator-intent])
                       [(candidate {})])
        summary (docket/docket-summary (:docket result))]
    (is (= 1 (:docket-version summary)))
    (is (= 1 (count (:missions summary))))
    (is (= [] (:awaiting-evidence summary)))
    (is (= [] (:do-not-do summary)))
    (is (= "make it better" (get-in summary [:intent :text])))))

(deftest policy-normalization
  (let [default (docket/normalize-policy nil)
        custom (docket/normalize-policy
                {:weights {:consequence 2
                           :evidence 2
                           :reversibility 1
                           :dependency 1
                           :opportunity 0}})
        raw-score (docket/score-candidate
                   {:weights {:consequence 2
                              :evidence 2
                              :reversibility 1
                              :dependency 1
                              :opportunity 0}}
                   (candidate {}))
        invalid (docket/normalize-policy
                 {:weights {:consequence 1}})]
    (is (:ok? default))
    (is (< (Math/abs (- 1.0 (reduce + (vals (get-in default [:value :weights])))))
           1e-9))
    (is (:ok? custom))
    (is (< (Math/abs (- 1.0 (reduce + (vals (get-in custom [:value :weights])))))
           1e-9))
    (is (< (Math/abs (- (/ 2.0 6.0)
                        (get-in custom [:value :weights :consequence])))
           1e-9))
    (is (number? (:score raw-score)))
    (is (nil? (:error raw-score)))
    (is (not (:ok? invalid)))
    (is (= :INVALID_POLICY (get-in invalid [:error :code])))))

(deftest candidate-normalization
  (let [result (docket/normalize-candidate
                {:candidate-id "c1"
                 :title "T"
                 :consequence 0.5
                 :evidence-strength 0.5
                 :dependency-cost 0.5
                 :reversibility 0.5
                 :opportunity-cost 0.5
                 :evidence-ids ["git:c1"]
                 :ignored "x"})
        invalid (docket/normalize-candidate
                 {:candidate-id "c1"
                  :title "T"})]
    (is (:ok? result))
    (is (= 0 (get-in result [:value :demand])))
    (is (not (contains? (get-in result [:value]) :ignored)))
    (is (not (:ok? invalid)))
    (is (= :INVALID_CANDIDATE (get-in invalid [:error :code])))))

(def gen-unit
  (gen/fmap #(/ % 100.0) (gen/choose 0 100)))

(deftest score-is-monotonic-in-benefits-and-costs
  (let [policy (:value (docket/normalize-policy nil))
        base (candidate {:evidence-ids []})
        score (fn [candidate] (:score (docket/score-candidate policy candidate)))]
    (is (:result
         (tc/quick-check
          100
          (prop/for-all [a gen-unit
                         b gen-unit]
            (let [[lo hi] (sort [a b])]
              (and
               (<= (score (assoc base :consequence lo))
                   (score (assoc base :consequence hi)))
               (<= (score (assoc base :evidence-strength lo))
                   (score (assoc base :evidence-strength hi)))
               (<= (score (assoc base :reversibility lo))
                   (score (assoc base :reversibility hi)))
               (>= (score (assoc base :dependency-cost lo))
                   (score (assoc base :dependency-cost hi)))
               (>= (score (assoc base :opportunity-cost lo))
                   (score (assoc base :opportunity-cost hi)))))))))))
