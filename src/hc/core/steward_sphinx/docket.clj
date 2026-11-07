(ns hc.core.steward-sphinx.docket
  "Steward Docket and admission decisions.

  A decision is made only at a Stewardship Decision Frontier.  The Steward
  weighs consequence, evidence strength, causal dependencies, reversibility,
  and opportunity cost.  The result is one of:

  - admit a Mission to the Docket;
  - defer it while awaiting evidence or a condition;
  - reject it into the do-not-do register;
  - keep the system legitimately idle.

  Decisions are idempotent by `:request-id`.  The namespace is pure; it does
  not start Plan, fork worktrees, or write files."
  (:require [hc.core.steward-sphinx.cognition :as cognition]
            [hc.core.steward-sphinx.frontier :as frontier]
            [hc.core.steward-sphinx.util :as util]))

(def schema-version 1)

(def default-policy
  {:weights {:consequence 0.4
             :evidence 0.3
             :reversibility 0.1
             :dependency 0.1
             :opportunity 0.1}
   :admit-threshold 0.5
   :min-evidence 0.5
   :min-reversibility 0.5
   :allow-irreversible-without-intent? false})

(def decision-log-limit 256)

(def ^:private decision-factors
  #{:consequence :evidence :reversibility :dependency :opportunity})

(def mission-statuses
  #{:admitted :planning :active :completed :abandoned :failed})

(def mission-transitions
  {:admitted #{:planning :active :abandoned}
   :planning #{:active :abandoned :failed}
   :active #{:completed :abandoned :failed}
   :completed #{}
   :abandoned #{}
   :failed #{}})

(defn initial-docket
  []
  {:schema-version schema-version
   :docket-version 0
   :next-mission-seq 0
   :next-decision-seq 0
   :missions (sorted-map)
   :awaiting-evidence (sorted-map)
   :deferred (sorted-map)
   :do-not-do (sorted-map)
   :decisions (sorted-map)
   :decision-log []
   :last-decision nil
   :intent nil})

(defn- invalid
  [code message details]
  (util/fail code message details))

(defn normalize-policy
  "Merge a policy with defaults and normalize its finite weights to sum to one."
  [policy]
  (if-not (or (nil? policy) (map? policy))
    (invalid :INVALID_POLICY "policy must be a map" {:policy policy})
    (let [policy (merge default-policy policy)
          weights (:weights policy)
          total (when (and (map? weights)
                           (every? util/finite-number? (vals weights)))
                  (reduce + 0.0 (vals weights)))]
      (cond
        (not (map? weights))
        (invalid :INVALID_POLICY "policy :weights must be a map" nil)

        (not= decision-factors (set (keys weights)))
        (invalid :INVALID_POLICY
                 "policy :weights must name exactly the five decision factors"
                 {:expected decision-factors :actual (set (keys weights))})

        (not (every? #(and (util/finite-number? (get weights %))
                           (not (neg? (get weights %))))
                     decision-factors))
        (invalid :INVALID_POLICY
                 "policy weights must be finite non-negative numbers"
                 {:weights weights})

        (not (and (util/finite-number? total) (pos? total)))
        (invalid :INVALID_POLICY
                 "policy weights must have a finite positive sum"
                 {:weights weights :total total})

        (not (util/unit-number? (:admit-threshold policy)))
        (invalid :INVALID_POLICY
                 "policy :admit-threshold must be in [0, 1]"
                 {:admit-threshold (:admit-threshold policy)})

        (not (util/unit-number? (:min-evidence policy)))
        (invalid :INVALID_POLICY
                 "policy :min-evidence must be in [0, 1]"
                 {:min-evidence (:min-evidence policy)})

        (not (util/unit-number? (:min-reversibility policy)))
        (invalid :INVALID_POLICY
                 "policy :min-reversibility must be in [0, 1]"
                 {:min-reversibility (:min-reversibility policy)})

        (not (boolean? (:allow-irreversible-without-intent? policy)))
        (invalid :INVALID_POLICY
                 "policy :allow-irreversible-without-intent? must be boolean"
                 {:allow-irreversible-without-intent?
                  (:allow-irreversible-without-intent? policy)})

        :else
        {:ok? true
         :value (assoc policy
                       :weights (into {}
                                      (map (fn [[factor weight]]
                                             [factor (/ (double weight) total)]))
                                      weights))}))))

(defn normalize-candidate
  "Validate one candidate Mission and strip unrelated keys."
  [raw]
  (let [candidate-id (:candidate-id raw)
        title (:title raw)
        consequence (:consequence raw)
        evidence-strength (:evidence-strength raw)
        dependency-cost (:dependency-cost raw)
        reversibility (:reversibility raw)
        opportunity-cost (:opportunity-cost raw)
        demand (:demand raw 0)
        evidence-ids (:evidence-ids raw)
        assumption-ids (:assumption-ids raw [])
        depends-on (:depends-on raw [])
        operator-aligned (:operator-aligned? raw)]
    (cond
      (not (map? raw))
      (invalid :INVALID_CANDIDATE "candidate must be a map" {:candidate raw})

      (not (util/non-blank-string? candidate-id))
      (invalid :INVALID_CANDIDATE
               "candidate needs a non-blank :candidate-id" {:candidate raw})

      (not (util/non-blank-string? title))
      (invalid :INVALID_CANDIDATE
               "candidate needs a non-blank :title"
               {:candidate-id candidate-id})

      (not (util/unit-number? consequence))
      (invalid :INVALID_CANDIDATE
               "candidate :consequence must be in [0, 1]"
               {:candidate-id candidate-id :consequence consequence})

      (not (util/unit-number? evidence-strength))
      (invalid :INVALID_CANDIDATE
               "candidate :evidence-strength must be in [0, 1]"
               {:candidate-id candidate-id :evidence-strength evidence-strength})

      (not (util/unit-number? dependency-cost))
      (invalid :INVALID_CANDIDATE
               "candidate :dependency-cost must be in [0, 1]"
               {:candidate-id candidate-id :dependency-cost dependency-cost})

      (not (util/unit-number? reversibility))
      (invalid :INVALID_CANDIDATE
               "candidate :reversibility must be in [0, 1]"
               {:candidate-id candidate-id :reversibility reversibility})

      (not (util/unit-number? opportunity-cost))
      (invalid :INVALID_CANDIDATE
               "candidate :opportunity-cost must be in [0, 1]"
               {:candidate-id candidate-id :opportunity-cost opportunity-cost})

      (not (util/non-negative-integer? demand))
      (invalid :INVALID_CANDIDATE
               "candidate :demand must be a non-negative integer"
               {:candidate-id candidate-id :demand demand})

      (not (sequential? evidence-ids))
      (invalid :INVALID_CANDIDATE
               "candidate :evidence-ids must be sequential"
               {:candidate-id candidate-id :evidence-ids evidence-ids})

      (not (every? util/non-blank-string? evidence-ids))
      (invalid :INVALID_CANDIDATE
               "candidate :evidence-ids must contain non-blank strings"
               {:candidate-id candidate-id :evidence-ids evidence-ids})

      (not (sequential? assumption-ids))
      (invalid :INVALID_CANDIDATE
               "candidate :assumption-ids must be sequential"
               {:candidate-id candidate-id :assumption-ids assumption-ids})

      (not (every? util/non-blank-string? assumption-ids))
      (invalid :INVALID_CANDIDATE
               "candidate :assumption-ids must contain non-blank strings"
               {:candidate-id candidate-id :assumption-ids assumption-ids})

      (not (sequential? depends-on))
      (invalid :INVALID_CANDIDATE
               "candidate :depends-on must be sequential"
               {:candidate-id candidate-id :depends-on depends-on})

      (not (every? util/non-blank-string? depends-on))
      (invalid :INVALID_CANDIDATE
               "candidate :depends-on must contain non-blank strings"
               {:candidate-id candidate-id :depends-on depends-on})

      (not (or (nil? operator-aligned) (boolean? operator-aligned)))
      (invalid :INVALID_CANDIDATE
               "candidate :operator-aligned? must be boolean when present"
               {:candidate-id candidate-id :operator-aligned? operator-aligned})

      :else
      {:ok? true
       :value
       (cond-> {:candidate-id candidate-id
                :title title
                :consequence (double consequence)
                :evidence-strength (double evidence-strength)
                :dependency-cost (double dependency-cost)
                :reversibility (double reversibility)
                :opportunity-cost (double opportunity-cost)
                :demand demand
                :evidence-ids (util/sorted-distinct evidence-ids)
                :assumption-ids (util/sorted-distinct assumption-ids)
                :depends-on (util/sorted-distinct depends-on)}
         (some? operator-aligned)
         (assoc :operator-aligned? operator-aligned)

         (util/non-blank-string? (:summary raw))
         (assoc :summary (:summary raw))

         (util/non-blank-string? (:rationale raw))
         (assoc :rationale (:rationale raw)))})))

(defn- normalized-weights
  "Return finite non-negative weights normalized to sum to one, or nil when
  the policy weights are invalid."
  [policy]
  (let [weights (:weights policy)
        total (when (and (map? weights)
                         (= decision-factors (set (keys weights)))
                         (every? #(and (util/finite-number? %)
                                       (not (neg? %)))
                                 (vals weights)))
                (reduce + 0.0 (vals weights)))]
    (when (and total
               (util/finite-number? total)
               (pos? total))
      (into {}
            (map (fn [[factor weight]]
                   [factor (/ (double weight) total)]))
            weights))))

(defn score-candidate
  "Weighted decision score in the interval [-1, 1].

  Benefits are consequence, evidence strength, and reversibility; costs are
  dependency and opportunity cost.  The weights are normalized defensively;
  a policy without finite non-negative weights for exactly the five decision
  factors returns `{:score nil :components {} :error ...}`."
  [policy candidate]
  (if-let [weights (normalized-weights policy)]
    (let [components {:consequence (* (:consequence candidate)
                                      (:consequence weights))
                      :evidence (* (:evidence-strength candidate)
                                   (:evidence weights))
                      :reversibility (* (:reversibility candidate)
                                        (:reversibility weights))
                      :dependency (- (* (:dependency-cost candidate)
                                        (:dependency weights)))
                      :opportunity (- (* (:opportunity-cost candidate)
                                         (:opportunity weights)))}
          score (reduce + 0.0 (vals components))]
      {:score (double score)
       :components components})
    {:score nil
     :components {}
     :error (util/error
             :INVALID_POLICY
             "score-candidate needs finite non-negative weights for exactly the five decision factors"
             {:policy policy})}))

(defn- validate-grounding
  [cognition candidate]
  (let [known-evidence (set (keys (:evidence cognition)))
        known-assumptions (set (keys (:assumptions cognition)))
        missing-evidence (->> (:evidence-ids candidate)
                              (remove known-evidence)
                              sort
                              vec)
        missing-assumptions (->> (:assumption-ids candidate)
                                 (remove known-assumptions)
                                 sort
                                 vec)]
    (cond
      (seq missing-evidence)
      (invalid :INVALID_CANDIDATE
               "candidate cites unknown evidence"
               {:candidate-id (:candidate-id candidate)
                :unknown-evidence missing-evidence})

      (seq missing-assumptions)
      (invalid :INVALID_CANDIDATE
               "candidate cites unknown assumptions"
               {:candidate-id (:candidate-id candidate)
                :unknown-assumptions missing-assumptions})

      :else
      {:ok? true})))

(defn- stale-assumptions
  [cognition candidate]
  (->> (:assumption-ids candidate)
       (keep (fn [assumption-id]
               (let [status (cognition/assumption-status
                             cognition
                             (get-in cognition [:assumptions assumption-id]))]
                 (when (not= :active (:status status))
                   {:assumption-id assumption-id
                    :status (:status status)}))))
       vec))

(defn- unmet-dependencies
  [docket candidate]
  (->> (:depends-on candidate)
       (remove (fn [mission-id]
                 (= :completed (get-in docket [:missions mission-id :status]))))
       vec))

(defn- unknown-dependencies
  [docket candidate]
  (->> (:depends-on candidate)
       (remove #(contains? (:missions docket) %))
       vec))

(defn- existing-mission
  [docket candidate]
  (->> (vals (:missions docket))
       (filter (fn [mission]
                 (and (= (:candidate-id candidate) (:candidate-id mission))
                      (contains? #{:admitted :planning :active}
                                 (:status mission)))))
       (sort-by :mission-id)
       first))

(defn- evaluate-candidate
  [docket frontier cognition candidate policy]
  (let [{:keys [score components]} (score-candidate policy candidate)
        frontier-reasons (set (:reasons frontier))
        operator-frontier? (contains? frontier-reasons :operator-intent)
        capacity-only? (and (contains? frontier-reasons :capacity)
                            (not operator-frontier?))
        spare (get-in frontier [:capacity :spare] 0)
        unmet (unmet-dependencies docket candidate)
        existing (existing-mission docket candidate)
        stale (stale-assumptions cognition candidate)
        capacity-ok? (or (not capacity-only?)
                         (<= (:demand candidate) spare))
        reversibility-ok? (or (>= (:reversibility candidate)
                                  (:min-reversibility policy))
                              (true? (:allow-irreversible-without-intent? policy))
                              (and operator-frontier?
                                   (true? (:operator-aligned? candidate))))
        reasons (cond-> []
                  (false? (:operator-aligned? candidate))
                  (conj :contradicts-operator-intent)

                  (empty? (:evidence-ids candidate))
                  (conj :ungrounded)

                  (seq stale)
                  (conj :stale-assumption)

                  existing
                  (conj :already-admitted)

                  (< (:evidence-strength candidate)
                     (:min-evidence policy))
                  (conj :needs-more-evidence)

                  (seq unmet)
                  (conj :unmet-dependencies)

                  (not capacity-ok?)
                  (conj :insufficient-capacity)

                  (not reversibility-ok?)
                  (conj :needs-operator-intent)

                  (< score (:admit-threshold policy))
                  (conj :below-threshold))
        hard-reasons #{:contradicts-operator-intent :ungrounded
                       :below-threshold}
        outcome (cond
                  (some #{:contradicts-operator-intent} reasons) :reject
                  (some #{:already-admitted} reasons) :defer
                  (some hard-reasons reasons) :reject
                  (seq reasons) :defer
                  :else :admit)]
    {:candidate-id (:candidate-id candidate)
     :outcome outcome
     :score score
     :components components
     :reasons reasons
     :gates {:evidence (not (some #{:ungrounded :needs-more-evidence
                                    :stale-assumption} reasons))
             :dependencies (empty? unmet)
             :not-duplicate (nil? existing)
             :capacity capacity-ok?
             :reversibility reversibility-ok?
             :operator-aligned (not (some #{:contradicts-operator-intent}
                                          reasons))}
     :demand (:demand candidate)
     :evidence-ids (:evidence-ids candidate)
     :assumption-ids (:assumption-ids candidate)
     :depends-on (:depends-on candidate)
     :unmet-dependencies unmet
     :existing-mission-id (when existing (:mission-id existing))
     :stale-assumptions stale}))

(defn- defer-needed
  [reasons]
  (cond
    (some #{:already-admitted} reasons) :existing-mission
    (some #{:needs-more-evidence :stale-assumption} reasons) :evidence
    (some #{:unmet-dependencies} reasons) :dependencies
    (some #{:insufficient-capacity} reasons) :capacity
    (some #{:needs-operator-intent} reasons) :operator-intent
    :else :condition))

(defn- mission
  [docket decision candidate frontier new-version]
  (let [mission-seq (inc (:next-mission-seq docket))]
    [(str "mission-" mission-seq)
     (cond-> {:mission-id (str "mission-" mission-seq)
              :candidate-id (:candidate-id candidate)
              :title (:title candidate)
              :status :admitted
              :demand (:demand candidate)
              :evidence-ids (:evidence-ids candidate)
              :assumption-ids (:assumption-ids candidate)
              :depends-on (:depends-on candidate)
              :frontier-id (:frontier-id frontier)
              :operator-intent (:operator-intent frontier)
              :cba-version (get-in frontier [:best-answer :cognition-version])
              :docket-version new-version
              :score (:score decision)}
       (util/non-blank-string? (:summary candidate))
       (assoc :summary (:summary candidate))

       (util/non-blank-string? (:rationale candidate))
       (assoc :rationale (:rationale candidate)))]))

(defn- apply-decision
  [docket decision candidate frontier new-version]
  (let [candidate-id (:candidate-id decision)]
    (case (:outcome decision)
      :admit
      (let [[mission-id mission-record] (mission docket decision candidate
                                                 frontier new-version)]
        [(-> docket
             (update :next-mission-seq inc)
             (assoc-in [:missions mission-id] mission-record)
             (update :awaiting-evidence dissoc candidate-id)
             (update :deferred dissoc candidate-id)
             (update :do-not-do dissoc candidate-id))
         (assoc decision :mission-id mission-id)])

      :defer
      (let [needed (defer-needed (:reasons decision))
            entry (cond-> {:candidate-id candidate-id
                           :title (:title candidate)
                           :reasons (:reasons decision)
                           :needed needed
                           :score (:score decision)
                           :frontier-id (:frontier-id frontier)
                           :docket-version new-version}
                    (util/non-blank-string? (:summary candidate))
                    (assoc :summary (:summary candidate))

                    (contains? (:do-not-do docket) candidate-id)
                    (assoc :previously-rejected? true))

            evidence-defer? (= :evidence needed)
            docket' (if evidence-defer?
                      (-> docket
                          (assoc-in [:awaiting-evidence candidate-id] entry)
                          (update :deferred dissoc candidate-id))
                      (-> docket
                          (assoc-in [:deferred candidate-id] entry)
                          (update :awaiting-evidence dissoc candidate-id)))]
        [docket' decision])

      :reject
      (let [reason (or (some #{:contradicts-operator-intent
                               :ungrounded :below-threshold}
                             (:reasons decision))
                       :below-threshold)
            entry {:candidate-id candidate-id
                   :title (:title candidate)
                   :reason reason
                   :reasons (:reasons decision)
                   :score (:score decision)
                   :evidence-ids (:evidence-ids candidate)
                   :assumption-ids (:assumption-ids candidate)
                   :frontier-id (:frontier-id frontier)
                   :docket-version new-version
                   :reconsiderable? true}]
        [(-> docket
             (assoc-in [:do-not-do candidate-id] entry)
             (update :awaiting-evidence dissoc candidate-id)
             (update :deferred dissoc candidate-id))
         decision]))))

(defn- trim-log
  [log]
  (let [n (count log)]
    (if (> n decision-log-limit)
      (subvec (vec log) (- n decision-log-limit))
      (vec log))))

(defn decide
  "Decide a batch of candidate Missions at one frontier.

  Options:

  - `:request-id` non-blank string, required for idempotence;
  - `:frontier` the frontier value emitted by `evaluate-frontier`;
  - `:cognition` the Sphinx state that produced the Current Best Answer;
  - `:candidates` a sequential collection of candidate maps;
  - `:policy` optional decision policy.

  The frontier must carry non-empty reasons drawn from `:operator-intent` or
  `:capacity`, `:pull-only? true`, a consistent `:cba-version` and
  `:cognition-version`, and a non-final best answer whose version matches the
  supplied cognition.  A capacity reason also requires a capacity observation
  accepted by `normalize-capacity` and `capacity-frontier?`.

  Returns `{:ok? true :state docket' :docket docket' :receipt receipt}` or a
  refusal.  The receipt has `:idle? true` when no Mission was admitted."
  [docket opts]
  (let [request-id (:request-id opts)
        payload {:frontier (:frontier opts)
                 :candidates (:candidates opts)
                 :policy (:policy opts)
                 :cognition (:cognition opts)}]
    (cond
      (not (util/non-blank-string? request-id))
      (util/fail-state docket :INVALID_REQUEST
                       "decision needs a non-blank :request-id" nil)

      :else
      (if-let [existing (get-in docket [:decisions request-id])]
        (if (= payload (:payload existing))
          {:ok? true
           :state docket
           :docket docket
           :receipt (:receipt existing)
           :replayed? true}
          (util/fail-state docket :DECISION_REQUEST_CONFLICT
                           "request id already names a different decision payload"
                           {:request-id request-id}))
        (let [frontier (:frontier opts)
              cognition (:cognition opts)
              raw-candidates (:candidates opts)]
          (cond
            (not (map? frontier))
            (util/fail-state docket :NO_DECISION_FRONTIER
                             "the Steward acts only at a Decision Frontier"
                             {:frontier frontier})

            (not (util/non-blank-string? (:frontier-id frontier)))
            (util/fail-state docket :INVALID_FRONTIER
                             "frontier needs a non-blank :frontier-id"
                             {:frontier frontier})

            (not (and (sequential? (:reasons frontier))
                      (seq (:reasons frontier))
                      (every? #{:operator-intent :capacity}
                              (:reasons frontier))))
            (util/fail-state docket :INVALID_FRONTIER
                             "frontier needs non-empty reasons drawn from :operator-intent or :capacity"
                             {:frontier frontier})

            (not (true? (:pull-only? frontier)))
            (util/fail-state docket :INVALID_FRONTIER
                             "frontier must be marked :pull-only? true"
                             {:pull-only? (:pull-only? frontier)})

            (and (contains? frontier :frontier-seq)
                 (not (util/positive-integer? (:frontier-seq frontier))))
            (util/fail-state docket :INVALID_FRONTIER
                             "frontier :frontier-seq must be a positive integer"
                             {:frontier-seq (:frontier-seq frontier)})

            (not (map? (:operator-intent frontier)))
            (util/fail-state docket :INVALID_FRONTIER
                             "frontier needs an :operator-intent map"
                             {:frontier frontier})

            (not (util/non-negative-integer?
                  (get-in frontier [:operator-intent :revision])))
            (util/fail-state docket :INVALID_FRONTIER
                             "frontier intent needs a non-negative :revision"
                             {:operator-intent (:operator-intent frontier)})

            (not (util/non-blank-string?
                  (get-in frontier [:operator-intent :text])))
            (util/fail-state docket :INVALID_FRONTIER
                             "frontier intent needs non-blank :text"
                             {:operator-intent (:operator-intent frontier)})

            (not (map? (:capacity frontier)))
            (util/fail-state docket :INVALID_FRONTIER
                             "frontier needs a :capacity map"
                             {:frontier frontier})

            (not (map? (:best-answer frontier)))
            (util/fail-state docket :INVALID_FRONTIER
                             "frontier needs a :best-answer map"
                             {:frontier frontier})

            (not (util/non-negative-integer?
                  (get-in frontier [:best-answer :cognition-version])))
            (util/fail-state docket :INVALID_FRONTIER
                             "frontier best answer needs a non-negative integer version"
                             {:cba-version
                              (get-in frontier [:best-answer :cognition-version])})

            (not (true? (get-in frontier [:best-answer :non-final?])))
            (util/fail-state docket :INVALID_FRONTIER
                             "frontier best answer must be non-final"
                             {:non-final?
                              (get-in frontier [:best-answer :non-final?])})

            (not= (:cba-version frontier)
                  (get-in frontier [:best-answer :cognition-version]))
            (util/fail-state docket :STALE_FRONTIER
                             "frontier :cba-version does not match its best answer"
                             {:cba-version (:cba-version frontier)
                              :best-answer-version
                              (get-in frontier [:best-answer :cognition-version])})

            (not= (:cognition-version frontier)
                  (get-in frontier [:best-answer :cognition-version]))
            (util/fail-state docket :STALE_FRONTIER
                             "frontier :cognition-version does not match its best answer"
                             {:cognition-version (:cognition-version frontier)
                              :best-answer-version
                              (get-in frontier [:best-answer :cognition-version])})

            (not (map? cognition))
            (util/fail-state docket :INVALID_COGNITION
                             "decision needs the Sphinx cognition"
                             {:cognition cognition})

            (not (util/non-negative-integer? (:cognition-version cognition)))
            (util/fail-state docket :INVALID_COGNITION
                             "cognition needs a non-negative integer version"
                             {:cognition-version (:cognition-version cognition)})

            (not= (:cognition-version cognition)
                  (get-in cognition [:best-answer :cognition-version]))
            (util/fail-state docket :INVALID_COGNITION
                             "cognition and its best answer disagree on version"
                             {:cognition-version (:cognition-version cognition)
                              :best-answer-version
                              (get-in cognition
                                      [:best-answer :cognition-version])})

            (not (true? (get-in cognition [:best-answer :non-final?])))
            (util/fail-state docket :INVALID_COGNITION
                             "cognition best answer must be non-final"
                             {:non-final?
                              (get-in cognition [:best-answer :non-final?])})

            (not= (get-in frontier [:best-answer :cognition-version])
                  (:cognition-version cognition))
            (util/fail-state docket :STALE_FRONTIER
                             "frontier and cognition name different answer versions"
                             {:frontier-version
                              (get-in frontier [:best-answer :cognition-version])
                              :cognition-version (:cognition-version cognition)})

            (not (sequential? raw-candidates))
            (util/fail-state docket :INVALID_CANDIDATES
                             "candidates must be sequential"
                             {:candidates raw-candidates})

            :else
            (let [capacity-reason? (boolean (some #{:capacity}
                                                  (:reasons frontier)))
                  raw-capacity (:capacity frontier)
                  capacity-result (when capacity-reason?
                                    (frontier/normalize-capacity raw-capacity))
                  validated-capacity (:value capacity-result)
                  frontier (if capacity-reason?
                             (assoc frontier :capacity validated-capacity)
                             frontier)
                  policy-result (normalize-policy (:policy opts))
                  candidate-results (util/normalize-all normalize-candidate
                                                        raw-candidates)]
              (cond
                (and capacity-reason? (not (:ok? capacity-result)))
                (util/fail-state docket :INVALID_FRONTIER
                                 "capacity frontier has an invalid capacity observation"
                                 {:capacity raw-capacity
                                  :error (:error capacity-result)})

                (and capacity-reason?
                     (not (frontier/capacity-frontier? validated-capacity)))
                (util/fail-state docket :INVALID_FRONTIER
                                 "capacity frontier is not meaningful"
                                 {:capacity validated-capacity})

                (not (:ok? policy-result))
                (util/fail-state docket
                                 (get-in policy-result [:error :code])
                                 (get-in policy-result [:error :message])
                                 (get-in policy-result [:error :details]))

                (not (:ok? candidate-results))
                (util/fail-state docket
                                 (get-in candidate-results [:error :code])
                                 (get-in candidate-results [:error :message])
                                 (get-in candidate-results [:error :details]))

                :else
                (let [policy (:value policy-result)
                      candidates (:values candidate-results)
                      duplicate-ids (->> candidates
                                         (map :candidate-id)
                                         frequencies
                                         (keep (fn [[candidate-id n]]
                                                 (when (> n 1) candidate-id)))
                                         sort
                                         vec)
                      duplicate-result (when (seq duplicate-ids)
                                         (invalid
                                          :INVALID_CANDIDATES
                                          "candidates contain duplicate ids"
                                          {:candidate-ids duplicate-ids}))
                      unknown-dependency-failure
                      (some (fn [candidate]
                              (when-let [unknown
                                         (seq (unknown-dependencies docket
                                                                    candidate))]
                                {:candidate-id (:candidate-id candidate)
                                 :unknown-dependencies (vec unknown)}))
                            candidates)
                      grounding-results (map #(validate-grounding cognition %)
                                             candidates)
                      grounding-failure (first (remove :ok? grounding-results))]
                  (cond
                    duplicate-result
                    (util/fail-state docket
                                     (get-in duplicate-result [:error :code])
                                     (get-in duplicate-result [:error :message])
                                     (get-in duplicate-result [:error :details]))

                    unknown-dependency-failure
                    (util/fail-state docket :INVALID_CANDIDATE
                                     "candidate depends on unknown missions"
                                     unknown-dependency-failure)

                    grounding-failure
                    (util/fail-state docket
                                     (get-in grounding-failure [:error :code])
                                     (get-in grounding-failure [:error :message])
                                     (get-in grounding-failure [:error :details]))

                    :else
                    (let [decisions (mapv #(evaluate-candidate docket frontier
                                                               cognition % policy)
                                          candidates)
                          candidate-map (into {} (map (juxt :candidate-id identity)
                                                      candidates))
                          new-version (inc (:docket-version docket))
                          [docket' decisions']
                          (reduce (fn [[d decisions] decision]
                                    (let [candidate (get candidate-map
                                                         (:candidate-id decision))
                                          [d' decision'] (apply-decision
                                                          d decision candidate
                                                          frontier new-version)]
                                      [d' (conj decisions decision')]))
                                  [docket []]
                                  decisions)
                          decision-id (str "decision-"
                                           (inc (:next-decision-seq docket)))
                          admitted (->> decisions'
                                        (keep #(when (= :admit (:outcome %))
                                                 (:mission-id %)))
                                        vec)
                          receipt {:request-id request-id
                                   :decision-id decision-id
                                   :frontier-id (:frontier-id frontier)
                                   :docket-version new-version
                                   :cba-version (get-in frontier
                                                        [:best-answer
                                                         :cognition-version])
                                   :operator-intent (:operator-intent frontier)
                                   :decisions decisions'
                                   :admitted admitted
                                   :idle? (empty? admitted)
                                   :outcome (if (empty? admitted) :idle :admit)
                                   :mode (if (empty? candidates)
                                           :no-candidates
                                           :evaluated)}
                          docket'' (-> docket'
                                       (assoc :docket-version new-version)
                                       (update :next-decision-seq inc)
                                       (assoc :intent (:operator-intent frontier))
                                       (assoc :last-decision receipt)
                                       (assoc-in [:decisions request-id]
                                                 {:payload payload
                                                  :receipt receipt})
                                       (update :decision-log
                                               (fn [log]
                                                 (trim-log (conj (vec log)
                                                                 receipt)))))]
                      {:ok? true
                       :state docket''
                       :docket docket''
                       :receipt receipt})))))))))))

(defn transition-mission
  "Move one Mission through its small lifecycle.

  `:admitted -> :planning|:active|:abandoned`;
  `:planning -> :active|:abandoned|:failed`;
  `:active -> :completed|:abandoned|:failed`.  Repeating the current status
  is a no-op."
  [docket {:keys [mission-id to reason]}]
  (let [mission (get-in docket [:missions mission-id])
        from (:status mission)]
    (cond
      (nil? mission)
      (util/fail-state docket :UNKNOWN_MISSION
                       "mission id is not in the Docket"
                       {:mission-id mission-id})

      (not (contains? mission-statuses to))
      (util/fail-state docket :INVALID_MISSION_STATUS
                       "unknown mission status"
                       {:mission-id mission-id :status to})

      (= from to)
      {:ok? true :state docket :docket docket :mission mission :unchanged? true}

      (not (contains? (get mission-transitions from #{}) to))
      (util/fail-state docket :INVALID_MISSION_TRANSITION
                       "mission cannot move to that status"
                       {:mission-id mission-id :from from :to to})

      :else
      (let [docket' (-> docket
                        (assoc-in [:missions mission-id :status] to)
                        (assoc-in [:missions mission-id :status-reason] reason)
                        (update :docket-version inc))]
        {:ok? true
         :state docket'
         :docket docket'
         :mission (get-in docket' [:missions mission-id])}))))

(defn docket-summary
  "The living Docket as the Steward and operator can observe it."
  [docket]
  {:docket-version (:docket-version docket)
   :intent (:intent docket)
   :missions (vec (sort-by :mission-id (vals (:missions docket))))
   :awaiting-evidence (vec (sort-by :candidate-id
                                    (vals (:awaiting-evidence docket))))
   :deferred (vec (sort-by :candidate-id (vals (:deferred docket))))
   :do-not-do (vec (sort-by :candidate-id (vals (:do-not-do docket))))
   :last-decision (:last-decision docket)})
