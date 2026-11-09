(ns hc.compose.steward-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.compose.steward :as steward]
            [hc.core.steward-sphinx :as ss]))

(defn- commit
  ([n] (commit n ["src/hc"]))
  ([n paths]
   {:kind :git-commit
    :commit n
    :parents []
    :changed-paths paths
    :subject (str "commit " n)}))

(def ^:private evidence-commit (commit "aaa"))

(defn- candidate
  ([] (candidate "candidate-1"))
  ([candidate-id]
   {:candidate-id candidate-id
    :title "Improve reliability"
    :consequence 0.9
    :evidence-strength 0.9
    :dependency-cost 0.1
    :reversibility 0.9
    :opportunity-cost 0.1
    :demand 1
    :evidence-ids ["git:aaa"]
    :operator-aligned? true}))

(defn- observed-state
  []
  (let [s1 (:state (steward/ingest-git-evidence
                    (steward/initial-state)
                    evidence-commit))
        s2 (:state (steward/observe-world
                    s1
                    {:intent {:revision 1
                              :text "Keep the repository healthy"
                              :received-at 100}
                     :capacity {:slots-total 4
                                :slots-allocated 4
                                :min-spare 1
                                :missions []}
                     :now 100}))
        s3 (:state (steward/evaluate-frontier s2))]
    s3))

(defn- decided-state
  []
  (:state (steward/decide (observed-state)
                          {:request-id "request-1"
                           :candidates [(candidate)]})))

(deftest initial-state-is-explicit-and-empty
  (let [state (steward/initial-state {:steward-id "repo-steward"})]
    (is (= 1 (:schema-version state)))
    (is (= "repo-steward" (:steward-id state)))
    (is (= {:revision 0 :text "" :received-at 0} (:intent state)))
    (is (= {:slots-total 4
            :slots-allocated 0
            :min-spare 1
            :missions []}
           (:capacity state)))
    (is (= 0 (ss/cognition-version (:cognition state))))
    (is (= 0 (:frontier-seq (:frontier state))))
    (is (= 0 (get-in state [:docket :docket-version])))
    (is (= {} (:links state)))
    (is (nil? (:last-observation state)))
    (let [snapshot (steward/snapshot state)]
      (is (nil? (:frontier snapshot)))
      (is (nil? (:last-observation snapshot)))
      (is (= 0 (:cognition-version snapshot)))
      (is (= [] (-> snapshot :docket-summary :missions)))))
  (is (= "steward" (:steward-id (steward/initial-state)))))

(deftest ingest-git-evidence-updates-cognition
  (let [state (steward/initial-state)
        result (steward/ingest-git-evidence state evidence-commit)]
    (is (true? (:ok? result)))
    (is (true? (:new? result)))
    (is (= 1 (ss/cognition-version (get-in result [:state :cognition]))))
    (is (= ["git:aaa"] (get-in result [:state :cognition :evidence-order])))
    (testing "the same evidence is a no-op"
      (let [again (steward/ingest-git-evidence (:state result)
                                               evidence-commit)]
        (is (true? (:ok? again)))
        (is (false? (:new? again)))
        (is (= (:state result) (:state again)))))
    (testing "a conflicting identity leaves the state unchanged"
      (let [conflict (steward/ingest-git-evidence
                      (:state result)
                      (assoc evidence-commit :changed-paths ["docs"]))]
        (is (false? (:ok? conflict)))
        (is (= :EVIDENCE_CONFLICT (get-in conflict [:error :code])))
        (is (= (:state result) (:state conflict)))))
    (testing "malformed evidence is refused"
      (let [invalid (steward/ingest-git-evidence
                     state
                     {:kind :git-commit :commit "" :parents []})]
        (is (false? (:ok? invalid)))
        (is (= :INVALID_EVIDENCE (get-in invalid [:error :code])))
        (is (= state (:state invalid)))))))

(deftest revise-cognition-needs-new-evidence
  (let [state (steward/initial-state)
        refused (steward/revise-cognition
                 state
                 {:summary "A better answer"
                  :frontier-of-thought "No evidence yet."})]
    (is (false? (:ok? refused)))
    (is (= :NO_NEW_EVIDENCE (get-in refused [:error :code])))
    (is (= state (:state refused))))
  (let [state (get-in (steward/ingest-git-evidence
                       (steward/initial-state)
                       evidence-commit)
                      [:state])
        revised (steward/revise-cognition
                 state
                 {:summary "Reliability is the strongest next step."
                  :frontier-of-thought "One commit observed."
                  :claims [{:claim-id "claim-1"
                            :statement "The change is small and reviewable."
                            :confidence 0.7
                            :dimension :reliability
                            :evidence-ids ["git:aaa"]}]})]
    (is (true? (:ok? revised)))
    (is (= 1 (:revision revised)))
    (is (= "Reliability is the strongest next step."
           (get-in revised [:state :cognition :best-answer :summary])))
    (is (= 2 (ss/cognition-version (get-in revised [:state :cognition]))))))

(deftest observe-world-ingests-and-normalizes
  (let [state (steward/initial-state)
        result (steward/observe-world
                state
                {:git-commits [(commit "a" ["src"])
                               (commit "b" ["docs"])]
                 :intent {:revision 2
                          :text "Keep the build green"
                          :received-at 10}
                 :capacity {:slots-total 4
                            :slots-allocated 1
                            :min-spare 1
                            :missions [{:mission-id "mission-1"
                                        :status :active
                                        :absorbable 0
                                        :demand 1}]}
                 :now "2026-11-09T00:00:00Z"})]
    (is (true? (:ok? result)))
    (is (= 2 (ss/evidence-count (get-in result [:state :cognition]))))
    (is (= {:revision 2
            :text "Keep the build green"
            :received-at 10}
           (get-in result [:state :intent])))
    (is (= 3 (get-in result [:state :capacity :spare])))
    (is (= 0 (get-in result [:state :capacity :absorbable])))
    (is (= "2026-11-09T00:00:00Z"
           (get-in result [:state :last-observation :at])))
    (is (= 2 (get-in result [:state :last-observation :git-commit-count])))
    (is (= "git:b"
           (get-in result [:state :last-observation
                           :latest-evidence-id])))
    (is (= 2 (get-in result [:state :last-observation
                             :intent-revision])))
    (testing "a repeated observation of the same world is stable"
      (let [again (steward/observe-world
                   (:state result)
                   {:git-commits [(commit "b" ["docs"])]
                    :intent (:intent result)
                    :capacity (get-in result [:state :capacity])})]
        (is (true? (:ok? again)))
        (is (= 2 (ss/evidence-count (get-in again [:state :cognition]))))
        (is (= (:state result)
               (assoc-in (:state again)
                         [:last-observation]
                         (get-in result [:state :last-observation]))))))))

(deftest observe-world-is-atomic
  (let [state (:state (steward/observe-world
                       (steward/initial-state)
                       {:git-commits [(commit "a")]}))]
    (testing "an invalid intent changes nothing"
      (let [result (steward/observe-world
                    state
                    {:git-commits [(commit "b")]
                     :intent {:revision -1 :text "bad"}})]
        (is (false? (:ok? result)))
        (is (= :INVALID_INTENT (get-in result [:error :code])))
        (is (= state (:state result)))))
    (testing "an invalid capacity changes nothing"
      (let [result (steward/observe-world
                    state
                    {:git-commits [(commit "b")]
                     :capacity {:slots-total 1 :slots-allocated 2}})]
        (is (false? (:ok? result)))
        (is (= :INVALID_CAPACITY (get-in result [:error :code])))
        (is (= state (:state result)))))
    (testing "a malformed commit changes nothing"
      (let [result (steward/observe-world
                    state
                    {:git-commits [(commit "b") {:kind :git-commit}]
                     :intent {:revision 3 :text "valid" :received-at 3}})]
        (is (false? (:ok? result)))
        (is (= :INVALID_EVIDENCE (get-in result [:error :code])))
        (is (= state (:state result)))))
    (testing "a conflict inside the batch discards earlier commits"
      (let [result (steward/observe-world
                    state
                    {:git-commits [(commit "b")
                                   (assoc (commit "a")
                                          :changed-paths ["docs"])]})]
        (is (false? (:ok? result)))
        (is (= :EVIDENCE_CONFLICT (get-in result [:error :code])))
        (is (= state (:state result)))))
    (testing "a non-sequential batch is refused"
      (let [result (steward/observe-world state {:git-commits :nope})]
        (is (false? (:ok? result)))
        (is (= :INVALID_OBSERVATION (get-in result [:error :code])))
        (is (= state (:state result)))))
    (testing "a non-map observation is refused"
      (let [result (steward/observe-world state [:not :a :map])]
        (is (false? (:ok? result)))
        (is (= :INVALID_OBSERVATION (get-in result [:error :code])))
        (is (= state (:state result)))))))

(deftest partial-state-never-throws
  (let [bare {:schema-version 1}]
    (testing "ingest-git-evidence supplies an initial cognition"
      (let [result (steward/ingest-git-evidence bare evidence-commit)]
        (is (true? (:ok? result)))
        (is (= 1 (ss/cognition-version (get-in result [:state :cognition]))))
        (is (= {:schema-version 1}
               (select-keys (:state result) [:schema-version])))))
    (testing "revise-cognition supplies an initial cognition"
      (let [result (steward/revise-cognition
                    bare
                    {:summary "A partial state"
                     :frontier-of-thought "No evidence yet."})]
        (is (false? (:ok? result)))
        (is (= :NO_NEW_EVIDENCE (get-in result [:error :code])))
        (is (= bare (:state result)))))
    (testing "observe-world supplies an initial cognition"
      (let [result (steward/observe-world bare
                                          {:git-commits [evidence-commit]})]
        (is (true? (:ok? result)))
        (is (= 1 (ss/evidence-count (get-in result [:state :cognition]))))
        (is (some? (get-in result [:state :last-observation])))))
    (testing "observe-world remains atomic without a cognition"
      (let [result (steward/observe-world
                    bare
                    {:git-commits [{:kind :git-commit}]})]
        (is (false? (:ok? result)))
        (is (= :INVALID_EVIDENCE (get-in result [:error :code])))
        (is (= bare (:state result)))))
    (testing "evaluate-frontier refuses a missing intent"
      (let [result (steward/evaluate-frontier bare)]
        (is (false? (:ok? result)))
        (is (= :INVALID_INTENT (get-in result [:error :code])))
        (is (= bare (:state result)))))
    (testing "decide refuses a missing frontier"
      (let [result (steward/decide bare {:request-id "request-bare"
                                         :candidates []})]
        (is (false? (:ok? result)))
        (is (= :NO_DECISION_FRONTIER (get-in result [:error :code])))
        (is (= bare (:state result)))))
    (testing "mission helpers report an unknown mission"
      (let [link (steward/link-mission bare "mission-1" {})
            transition (steward/transition-mission
                        bare "mission-1" :planning "plan")]
        (is (false? (:ok? link)))
        (is (= :UNKNOWN_MISSION (get-in link [:error :code])))
        (is (false? (:ok? transition)))
        (is (= :UNKNOWN_MISSION (get-in transition [:error :code])))))
    (testing "snapshot renders a bare state"
      (let [snapshot (steward/snapshot bare)]
        (is (map? snapshot))
        (is (= 0 (:cognition-version snapshot)))
        (is (= {} (:links snapshot)))
        (is (vector? (get-in snapshot [:docket-summary :missions])))
        (is (nil? (:frontier snapshot)))))))

(deftest evaluate-frontier-is-pull-only
  (let [initial (steward/initial-state)
        before-intent (steward/evaluate-frontier initial)
        observed (:state (steward/observe-world
                          initial
                          {:intent {:revision 1
                                    :text "Improve reliability"
                                    :received-at 1}
                           :capacity {:slots-total 4
                                      :slots-allocated 4
                                      :min-spare 1
                                      :missions []}}))
        first (steward/evaluate-frontier observed)
        second (steward/evaluate-frontier (:state first))]
    (is (false? (:ok? before-intent)))
    (is (= :INVALID_INTENT (get-in before-intent [:error :code])))
    (is (true? (:triggered? first)))
    (is (= [:operator-intent] (:reasons first)))
    (is (= "frontier-1" (get-in first [:frontier :frontier-id])))
    (is (= "frontier-1"
           (get-in first [:state :frontier :last-frontier :frontier-id])))
    (is (false? (:triggered? second)))
    (is (nil? (:frontier second)))
    (is (= [] (:reasons second)))
    (testing "a changed capacity emits a capacity frontier"
      (let [observed (:state (steward/observe-world
                              (:state second)
                              {:capacity {:slots-total 6
                                          :slots-allocated 2
                                          :min-spare 1
                                          :missions []}}))
            capacity-frontier (steward/evaluate-frontier observed)]
        (is (true? (:triggered? capacity-frontier)))
        (is (= [:capacity] (:reasons capacity-frontier)))
        (is (= "frontier-2"
               (get-in capacity-frontier [:frontier :frontier-id])))))))

(deftest decide-admits-and-links
  (let [state (observed-state)
        result (steward/decide state
                               {:request-id "request-1"
                                :candidates [(candidate)]})
        next-state (:state result)
        receipt (:decision result)
        mission-id (first (:admitted receipt))]
    (is (true? (:ok? result)))
    (is (= "mission-1" mission-id))
    (is (= :admit (:outcome receipt)))
    (is (= :admitted (get-in next-state
                             [:docket :missions mission-id :status])))
    (is (= :admitted (get-in next-state [:links mission-id :status])))
    (is (= "request-1"
           (get-in next-state [:links mission-id :request-id])))
    (is (= "frontier-1"
           (get-in next-state [:links mission-id :frontier-id])))
    (testing "the same request replays the original receipt"
      (let [replay (steward/decide next-state
                                   {:request-id "request-1"
                                    :candidates [(candidate)]})]
        (is (true? (:ok? replay)))
        (is (= next-state (:state replay)))
        (is (= receipt (:decision replay)))))
    (testing "a different payload under the same request id conflicts"
      (let [conflict (steward/decide next-state
                                     {:request-id "request-1"
                                      :candidates [(candidate "other")]})]
        (is (false? (:ok? conflict)))
        (is (= :DECISION_REQUEST_CONFLICT
               (get-in conflict [:error :code])))
        (is (= next-state (:state conflict)))))
    (testing "deciding without a frontier is refused"
      (let [refused (steward/decide (steward/initial-state)
                                    {:request-id "request-2"
                                     :candidates [(candidate)]})]
        (is (false? (:ok? refused)))
        (is (= :NO_DECISION_FRONTIER (get-in refused [:error :code])))))))

(deftest mission-links-mirror-docket
  (let [mission-id "mission-1"
        state (decided-state)
        planning (steward/transition-mission
                  state mission-id :planning "plan first")]
    (is (true? (:ok? planning)))
    (is (= :planning
           (get-in planning [:state :docket :missions mission-id :status])))
    (is (= :planning
           (get-in planning [:state :links mission-id :status])))
    (is (= "plan first"
           (get-in planning [:state :links mission-id :status-reason])))
    (testing "an invalid transition changes nothing"
      (let [refused (steward/transition-mission
                     (:state planning) mission-id :completed "skip")]
        (is (false? (:ok? refused)))
        (is (= :INVALID_MISSION_TRANSITION
               (get-in refused [:error :code])))
        (is (= (:state planning) (:state refused)))))
    (testing "link data cannot override the Docket status"
      (let [linked (steward/link-mission
                    (:state planning)
                    mission-id
                    {:workspace "/tmp/slice"
                     :swarm-id "swarm-1"
                     :mission-id "wrong"
                     :status :completed})]
        (is (true? (:ok? linked)))
        (is (= "/tmp/slice"
               (get-in linked [:state :links mission-id :workspace])))
        (is (= :planning
               (get-in linked [:state :links mission-id :status])))
        (is (= mission-id
               (get-in linked [:state :links mission-id :mission-id])))))
    (testing "a non-map link is refused"
      (let [refused (steward/link-mission state mission-id :not-a-map)]
        (is (false? (:ok? refused)))
        (is (= :INVALID_LINK (get-in refused [:error :code])))))
    (testing "unknown missions cannot be linked"
      (let [refused (steward/link-mission state "mission-404" {})]
        (is (false? (:ok? refused)))
        (is (= :UNKNOWN_MISSION (get-in refused [:error :code])))))
    (testing "the full lifecycle reaches completion"
      (let [active (:state (steward/transition-mission
                            (:state planning) mission-id :active "execute"))
            completed (steward/transition-mission
                       active mission-id :completed "done")]
        (is (true? (:ok? completed)))
        (is (= :completed
               (get-in completed
                       [:state :docket :missions mission-id :status])))
        (is (= :completed
               (get-in completed [:state :links mission-id :status])))
        (is (= "done"
               (get-in completed
                       [:state :links mission-id :status-reason])))))))

(deftest fleet-capacity-aggregates-fleet-statuses
  (let [rows [{:swarm-id "s1" :mission-id "m1" :status :running}
              {:swarm-id "s2" :mission-id "m1" :status :queued}
              {:swarm-id "s3" :mission-id "m2" :status :joined}
              {:swarm-id "s4" :mission-id "m3" :status :abandoned}
              {:swarm-id "s5" :mission-id "m4" :status :failed}
              {:swarm-id "s6" :mission-id "m5" :status :unknown}]
        capacity (steward/fleet-capacity rows {:slots-total 2 :min-spare 1})
        normalized (ss/normalize-capacity capacity)]
    (is (= 2 (:slots-total capacity)))
    (is (= 1 (:slots-allocated capacity)))
    (is (= [{:mission-id "m1" :status :active :absorbable 1 :demand 2}
            {:mission-id "m2" :status :completed :absorbable 0 :demand 1}
            {:mission-id "m3" :status :abandoned :absorbable 0 :demand 1}
            {:mission-id "m4" :status :failed :absorbable 0 :demand 1}]
           (:missions capacity)))
    (is (true? (:ok? normalized)))
    (is (= 1 (get-in normalized [:value :spare])))
    (is (false? (ss/capacity-frontier? (:value normalized))))
    (testing "running work with no queued work exposes a capacity frontier"
      (let [frontier-capacity
            (steward/fleet-capacity [{:mission-id "m1" :status :running}]
                                    {:slots-total 2 :min-spare 1})
            frontier-normalized (ss/normalize-capacity frontier-capacity)]
        (is (= 1 (get-in frontier-normalized [:value :spare])))
        (is (true? (ss/capacity-frontier?
                    (:value frontier-normalized))))))
    (testing "the total grows to cover over-allocated rows"
      (let [capacity (steward/fleet-capacity
                      [{:swarm-id "s1" :status :running}
                       {:swarm-id "s2" :status :running}
                       {:swarm-id "s3" :status :running}]
                      {:slots-total 2 :min-spare 1})
            normalized (ss/normalize-capacity capacity)]
        (is (= 3 (:slots-total capacity)))
        (is (= 3 (:slots-allocated capacity)))
        (is (= 0 (get-in normalized [:value :spare])))))
    (testing "an empty fleet is a valid observation"
      (is (= {:slots-total 4
              :slots-allocated 0
              :min-spare 1
              :missions []}
             (steward/fleet-capacity [] {}))))))

(deftest snapshot-is-bounded
  (let [snapshot (steward/snapshot (decided-state))]
    (is (= #{:steward-id :cognition-version :best-answer :frontier
             :docket-summary :links :last-observation}
           (set (keys snapshot))))
    (is (= 1 (:cognition-version snapshot)))
    (is (= "mission-1"
           (-> snapshot :docket-summary :missions first :mission-id)))
    (is (= "frontier-1" (get-in snapshot [:frontier :frontier-id])))
    (is (nil? (get-in snapshot [:frontier :best-answer]))
        "the Current Best Answer is stored once at the top level")
    (is (= ["mission-1"] (keys (:links snapshot))))))

;; ---------------------------------------------------------------------------
;; Properties

(defspec initial-state-always-has-the-contract-shape 20
  (prop/for-all [steward-id (gen/one-of [gen/string-alphanumeric
                                         (gen/return nil)])]
    (let [state (steward/initial-state
                 (when steward-id {:steward-id steward-id}))]
      (and (= 1 (:schema-version state))
           (= (or steward-id "steward") (:steward-id state))
           (= {} (:links state))
           (= 0 (ss/cognition-version (:cognition state)))
           (= 0 (:frontier-seq (:frontier state)))
           (= 0 (get-in state [:docket :docket-version]))))))

(defspec unique-git-evidence-is-monotone 30
  (prop/for-all [n (gen/choose 0 6)]
    (let [state (reduce (fn [s index]
                          (:state
                           (steward/ingest-git-evidence
                            s
                            (commit (str "commit-" index)))))
                        (steward/initial-state)
                        (range n))]
      (and (= n (ss/evidence-count (:cognition state)))
           (= n (ss/cognition-version (:cognition state)))
           (= n (get-in state [:cognition :best-answer
                               :cognition-version]))))))

(defspec invalid-observation-does-not-mutate 20
  (prop/for-all [commits (gen/choose 0 4)
                 revision (gen/choose -10 -1)]
    (let [state (reduce (fn [s index]
                          (:state
                           (steward/ingest-git-evidence
                            s
                            (commit (str "commit-" index)))))
                        (steward/initial-state)
                        (range commits))
          result (steward/observe-world
                  state
                  {:intent {:revision revision :text "bad"}})]
      (and (false? (:ok? result))
           (= state (:state result))))))

(defspec frontier-emits-once-per-world 20
  (prop/for-all [revision (gen/choose 1 50)
                 text (gen/not-empty gen/string-alphanumeric)]
    (let [observed (:state (steward/observe-world
                            (steward/initial-state)
                            {:intent {:revision revision
                                      :text text
                                      :received-at revision}
                             :capacity {:slots-total 4
                                        :slots-allocated 4
                                        :min-spare 1
                                        :missions []}}))
          first (steward/evaluate-frontier observed)
          second (steward/evaluate-frontier (:state first))]
      (and (true? (:ok? first))
           (true? (:triggered? first))
           (= [:operator-intent] (:reasons first))
           (false? (:triggered? second))
           (nil? (:frontier second))))))

(defspec decide-replays-the-original-receipt 20
  (prop/for-all [request-id (gen/not-empty gen/string-alphanumeric)]
    (let [state (observed-state)
          first (steward/decide state {:request-id request-id
                                       :candidates [(candidate)]})
          second (steward/decide (:state first)
                                 {:request-id request-id
                                  :candidates [(candidate)]})]
      (and (true? (:ok? first))
           (true? (:ok? second))
           (= (:decision first) (:decision second))
           (= (:state first) (:state second))))))

(defspec every-admitted-mission-is-linked 20
  (prop/for-all [n (gen/choose 0 4)]
    (let [state (observed-state)
          candidates (mapv (fn [index]
                             (candidate (str "candidate-" index)))
                           (range n))
          result (steward/decide state {:request-id "batch"
                                        :candidates candidates})
          next-state (:state result)
          admitted (get-in result [:decision :admitted])]
      (and (true? (:ok? result))
           (= (count admitted)
              (count (filter (fn [link]
                               (= :admitted (:status link)))
                             (vals (:links next-state)))))
           (every? (fn [mission-id]
                     (= :admitted
                        (get-in next-state [:links mission-id :status])))
                   admitted)))))

(defspec transition-mirrors-the-docket-status 20
  (prop/for-all [target (gen/elements [:planning :active :abandoned])]
    (let [state (decided-state)
          result (steward/transition-mission
                  state "mission-1" target "reason")]
      (and (true? (:ok? result))
           (= target
              (get-in result [:state :docket :missions "mission-1" :status]))
           (= target
              (get-in result [:state :links "mission-1" :status]))))))

(def gen-fleet-status
  (gen/elements [:running :queued :joined :abandoned :failed :unknown]))

(def gen-fleet-row
  (gen/hash-map :swarm-id gen/string-alphanumeric
                :slice-id gen/string-alphanumeric
                :status gen-fleet-status))

(defspec fleet-capacity-always-normalizes 40
  (prop/for-all [rows (gen/vector gen-fleet-row 0 10)
                 slots-total (gen/choose 0 8)
                 min-spare (gen/choose 1 3)]
    (let [capacity (steward/fleet-capacity
                    rows
                    {:slots-total slots-total :min-spare min-spare})
          normalized (ss/normalize-capacity capacity)]
      (and (true? (:ok? normalized))
           (= (:slots-total capacity)
              (max slots-total (:slots-allocated capacity)))
           (= (- (:slots-total capacity) (:slots-allocated capacity))
              (get-in normalized [:value :spare]))
           (= min-spare (:min-spare capacity))))))
