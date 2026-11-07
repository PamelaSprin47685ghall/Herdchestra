(ns hc.core.steward-sphinx.cognition
  "Standing Sphinx cognition: evidence, assumptions, claims, and the Current
  Best Answer.

  The Sphinx is a pure state machine.  The root question never closes.  A Git
  commit or another observation enters as evidence; assumptions whose watched
  paths were touched become invalid; claims that rest on invalid assumptions
  leave the Current Best Answer.  A revision is accepted only when new
  evidence has entered since the previous revision, so repeated reasoning
  cannot masquerade as progress.

  The namespace does not call a model.  The caller submits the summary, the
  frontier of thought, assumptions, and claims; this namespace enforces the
  evidence discipline and derives the published answer."
  (:require [hc.core.steward-sphinx.evidence :as evidence]
            [hc.core.steward-sphinx.util :as util]))

(def schema-version 1)

(def default-answer-summary
  "No evidence-grounded answer yet; the root question remains open.")

(def default-frontier-of-thought
  "No Git evidence has been observed yet.")

(def known-dimensions
  "Dimensions named by the specification.  `:open` is the explicit escape for
  a dimension nobody has named yet."
  #{:correctness :reliability :architecture :simplicity :performance
    :security :accessibility :ux :open})

(declare rebuild-best-answer)

(defn- invalid
  [code message details]
  (util/fail code message details))

(defn- bump-version
  [state]
  (update state :cognition-version inc))

(defn- duplicate-values
  [items k]
  (->> items
       (map k)
       frequencies
       (keep (fn [[value n]] (when (> n 1) value)))
       sort
       vec))

(defn- ancestor-commits
  "All observed commits reachable from COMMIT through `:parents`, including
  COMMIT itself.  The incoming commit is not yet in `:evidence`, but its
  parents may already be observed."
  [state commit]
  (if (nil? commit)
    #{}
    (loop [pending [commit]
           seen #{}]
      (if-let [current (peek pending)]
        (if (contains? seen current)
          (recur (pop pending) seen)
          (let [parents (get-in state [:evidence (str "git:" current) :parents] [])]
            (recur (into (pop pending) parents) (conj seen current))))
        seen))))

(defn assumption-status
  "Derive the standing of one assumption under the current evidence.

  An assumption is invalidated when any evidence newer than its newest basis
  evidence touches one of its watched paths.  The status is derived, not
  stored, so ingestion order cannot hide a stale assumption."
  [cognition assumption]
  (cond
    (nil? assumption)
    {:status :missing}

    (:retired? assumption)
    {:status :retired}

    :else
    (let [basis-seq (reduce max
                            0
                            (map #(get-in cognition [:evidence % :seq] 0)
                                 (:basis assumption)))
          newer (->> (vals (:evidence cognition))
                     (filter #(> (:seq %) basis-seq))
                     (sort-by :seq))
          hit (first (filter #(evidence/affects-assumption? assumption %) newer))]
      (if hit
        {:status :invalidated
         :invalidated-by (:evidence-id hit)
         :invalidated-at-seq (:seq hit)}
        {:status :active}))))

(defn assumption-view
  [cognition assumption]
  (merge (select-keys assumption
                      [:assumption-id :statement :confidence :watch-paths
                       :basis :retired?])
         (assumption-status cognition assumption)))

(defn claim-evaluation
  "Decide whether a claim still belongs to the Current Best Answer."
  [cognition claim]
  (let [missing-evidence (remove #(contains? (:evidence cognition) %)
                                 (:evidence-ids claim))
        missing-assumptions (remove #(contains? (:assumptions cognition) %)
                                    (:assumption-ids claim))
        statuses (map (fn [assumption-id]
                        [assumption-id
                         (assumption-status
                          cognition
                          (get-in cognition [:assumptions assumption-id]))])
                      (:assumption-ids claim))
        retired (->> statuses
                     (filter (fn [[_ status]] (= :retired (:status status))))
                     (map first)
                     vec)
        invalidated (->> statuses
                         (filter (fn [[_ status]]
                                   (= :invalidated (:status status))))
                         vec)]
    (cond
      (= :withdrawn (:status claim))
      {:active? false :reason :withdrawn}

      (seq missing-evidence)
      {:active? false
       :reason :missing-evidence
       :evidence-ids (vec missing-evidence)}

      (seq missing-assumptions)
      {:active? false
       :reason :missing-assumption
       :assumption-ids (vec missing-assumptions)}

      (seq retired)
      {:active? false
       :reason :retired-assumption
       :assumption-ids retired}

      (seq invalidated)
      {:active? false
       :reason :invalidated-assumption
       :assumption-ids (mapv first invalidated)
       :invalidated-by (mapv (fn [[_ status]] (:invalidated-by status))
                             invalidated)}

      :else
      {:active? true})))

(defn claim-view
  [cognition claim]
  (merge (select-keys claim
                      [:claim-id :statement :dimension :confidence :weight
                       :evidence-ids :assumption-ids :status])
         (claim-evaluation cognition claim)))

(defn- weighted-confidence
  [claims]
  (if (empty? claims)
    0.0
    (let [total-weight (reduce + 0.0 (map #(double (or (:weight %) 1.0)) claims))
          weighted (reduce + 0.0
                           (map #(* (double (or (:weight %) 1.0))
                                    (double (:confidence %)))
                                claims))]
      (if (and (pos? total-weight)
               (util/finite-number? total-weight)
               (util/finite-number? weighted))
        (/ weighted total-weight)
        0.0))))

(defn- question-views
  [state]
  (let [root (get-in state [:questions :root])
        sub-questions (->> (:questions state)
                           (remove (fn [[question-id _]]
                                     (= question-id :root)))
                           (sort-by (comp str key))
                           (map val)
                           vec)]
    {:root root :sub-questions sub-questions}))

(defn rebuild-best-answer
  "Derive the Current Best Answer from evidence, assumptions, claims and
  questions.  The answer is always present and always non-final."
  [state]
  (let [claims (vals (:claims state))
        evaluated (map (fn [claim] [claim (claim-evaluation state claim)])
                       claims)
        active (for [[claim evaluation] evaluated
                     :when (:active? evaluation)]
                 claim)
        excluded (for [[claim evaluation] evaluated
                       :when (and (not (:active? evaluation))
                                  (not= :withdrawn (:reason evaluation)))]
                   (merge {:claim-id (:claim-id claim)}
                          (select-keys evaluation
                                       [:reason :assumption-ids
                                        :evidence-ids :invalidated-by])))
        withdrawn (for [[claim evaluation] evaluated
                        :when (= :withdrawn (:reason evaluation))]
                    (:claim-id claim))
        invalidated-assumptions (->> (vals (:assumptions state))
                                     (filter (fn [assumption]
                                               (= :invalidated
                                                  (:status
                                                   (assumption-status state assumption)))))
                                     (map :assumption-id)
                                     sort
                                     vec)
        cited-evidence (->> active
                            (mapcat :evidence-ids)
                            distinct
                            sort
                            vec)
        evidence-map (into (sorted-map)
                           (keep (fn [evidence-id]
                                   (when-let [record (get-in state [:evidence evidence-id])]
                                     [evidence-id (evidence/evidence-summary record)])))
                           cited-evidence)
        question-views (question-views state)
        open-questions (vec
                        (concat
                         (when (= :open (get-in question-views [:root :status]))
                           [:root])
                         (->> (:sub-questions question-views)
                              (filter #(= :open (:status %)))
                              (map :question-id)
                              vec)))
        resolved-questions (->> (:sub-questions question-views)
                                (filter #(= :resolved (:status %)))
                                (map :question-id)
                                vec)
        latest-evidence-id (peek (:evidence-order state))
        latest-evidence (when-let [record (get-in state [:evidence latest-evidence-id])]
                          (evidence/evidence-summary record))
        shown-assumption-ids (->> (concat (mapcat :assumption-ids active)
                                          (mapcat :assumption-ids excluded)
                                          invalidated-assumptions)
                                  (filter #(contains? (:assumptions state) %))
                                  distinct
                                  sort
                                  vec)
        assumptions (mapv (fn [assumption-id]
                            (assumption-view
                             state
                             (get-in state [:assumptions assumption-id])))
                          shown-assumption-ids)
        version (:cognition-version state)]
    {:answer-id (keyword (str "cba-" version))
     :root-question evidence/root-question
     :summary (get-in state [:answer-meta :summary] default-answer-summary)
     :frontier-of-thought (get-in state [:answer-meta :frontier-of-thought]
                                  default-frontier-of-thought)
     :claims (mapv #(claim-view state %) active)
     :excluded-claims (vec excluded)
     :withdrawn-claim-ids (vec (sort withdrawn))
     :confidence (weighted-confidence active)
     :evidence-ids cited-evidence
     :evidence evidence-map
     :assumption-ids (->> active
                          (mapcat :assumption-ids)
                          distinct
                          sort
                          vec)
     :assumptions assumptions
     :invalidated-assumption-ids invalidated-assumptions
     :questions (vec (concat [(:root question-views)]
                             (:sub-questions question-views)))
     :open-question-ids open-questions
     :resolved-question-ids (vec resolved-questions)
     :root-question-status :open
     :latest-evidence-id latest-evidence-id
     :latest-evidence latest-evidence
     :latest-git-evidence-id (get-in state [:git :latest-evidence-id])
     :based-on-commit (get-in state [:git :head])
     :cognition-version version
     :revision (get-in state [:reasoning :revision] 0)
     :non-final? true
     :stale? (boolean (seq excluded))}))

(defn initial-cognition
  "A Sphinx that has observed no evidence yet.

  The root question is open and the Current Best Answer exists as an explicit
  no-answer.  It cannot be revised until evidence enters."
  []
  (let [state {:schema-version schema-version
               :root-question evidence/root-question
               :cognition-version 0
               :evidence-seq 0
               :reasoning {:last-revised-evidence-seq 0
                           :revision 0}
               :git {:head nil
                     :previous-head nil
                     :last-observed-commit nil
                     :observed-commits []
                     :latest-evidence-id nil
                     :history []}
               :evidence (sorted-map)
               :evidence-order []
               :assumptions (sorted-map)
               :claims (sorted-map)
               :questions {:root {:question-id :root
                                  :question evidence/root-question
                                  :status :open
                                  :permanent? true}}
               :answer-meta {:summary default-answer-summary
                             :frontier-of-thought default-frontier-of-thought}
               :best-answer nil}]
    (assoc state :best-answer (rebuild-best-answer state))))

(defn- invalidated-assumption-ids
  [state]
  (->> (vals (:assumptions state))
       (filter #(= :invalidated (:status (assumption-status state %))))
       (map :assumption-id)
       sort
       vec))

(defn ingest-evidence
  "Ingest one Git commit or observation.

  Duplicate evidence is a no-op.  A duplicate identity with different content
  is `:EVIDENCE_CONFLICT`.  New evidence invalidates stale assumptions and
  immediately degrades the Current Best Answer before any new reasoning.

  For Git evidence, `:head` advances unless the incoming commit is an ancestor
  of the current head; `:last-observed-commit` always follows the ingestion
  order, and `:based-on-commit` stays on `:head`."
  [state raw]
  (let [normalized (evidence/normalize-evidence raw)]
    (if-not (:ok? normalized)
      (util/fail-state state
                       (get-in normalized [:error :code])
                       (get-in normalized [:error :message])
                       (get-in normalized [:error :details]))
      (let [incoming (:value normalized)
            evidence-id (:evidence-id incoming)]
        (if-let [existing (get-in state [:evidence evidence-id])]
          (if (= (dissoc existing :seq) incoming)
            {:ok? true
             :state state
             :evidence existing
             :new? false
             :invalidated-assumption-ids []
             :newly-invalidated-assumption-ids []
             :best-answer (:best-answer state)}
            (util/fail-state
             state
             :EVIDENCE_CONFLICT
             "evidence identity already names different content"
             {:evidence-id evidence-id
              :existing existing
              :incoming incoming}))
          (let [before-invalidated (set (invalidated-assumption-ids state))
                evidence-seq (inc (:evidence-seq state))
                incoming (assoc incoming :seq evidence-seq)
                state (-> state
                          (assoc-in [:evidence evidence-id] incoming)
                          (update :evidence-seq inc)
                          (update :evidence-order conj evidence-id))
                state (if (= :git-commit (:kind incoming))
                        (let [previous-head (get-in state [:git :head])
                              incoming-commit (:commit incoming)
                              head-ancestors (ancestor-commits state previous-head)
                              incoming-ancestors (ancestor-commits state incoming-commit)
                              ancestor? (contains? head-ancestors incoming-commit)
                              head-advanced? (or (nil? previous-head)
                                                 (not ancestor?))
                              continuity (cond
                                           (nil? previous-head) :initial
                                           (some #{previous-head} (:parents incoming)) :linear
                                           :else :discontinuous)
                              next-head (if head-advanced?
                                          incoming-commit
                                          previous-head)]
                          (-> state
                              (assoc-in [:git :previous-head] previous-head)
                              (assoc-in [:git :last-observed-commit] incoming-commit)
                              (assoc-in [:git :head] next-head)
                              (assoc-in [:git :latest-evidence-id] evidence-id)
                              (update-in [:git :observed-commits]
                                         conj incoming-commit)
                              (update-in [:git :history]
                                         conj {:from previous-head
                                               :to incoming-commit
                                               :continuity continuity
                                               :evidence-id evidence-id
                                               :head-advanced? head-advanced?
                                               :head next-head
                                               :descendant? (contains?
                                                             incoming-ancestors
                                                             previous-head)})))
                        state)
                state (bump-version state)
                state (assoc state :best-answer (rebuild-best-answer state))
                after-invalidated (set (invalidated-assumption-ids state))
                newly-invalidated (->> after-invalidated
                                       (remove before-invalidated)
                                       sort
                                       vec)
                continuity (when (= :git-commit (:kind incoming))
                             (last (get-in state [:git :history])))]
            {:ok? true
             :state state
             :evidence incoming
             :new? true
             :continuity continuity
             :invalidated-assumption-ids (vec (sort after-invalidated))
             :newly-invalidated-assumption-ids newly-invalidated
             :best-answer (:best-answer state)}))))))

(defn- normalize-assumption
  [state raw]
  (let [assumption-id (:assumption-id raw)
        statement (:statement raw)
        confidence (:confidence raw)
        watch-paths (:watch-paths raw [])
        basis (:basis raw)
        retired? (:retired? raw false)]
    (cond
      (not (map? raw))
      (invalid :INVALID_ASSUMPTION "assumption must be a map" {:assumption raw})

      (not (util/non-blank-string? assumption-id))
      (invalid :INVALID_ASSUMPTION
               "assumption needs a non-blank :assumption-id"
               {:assumption raw})

      (not (util/non-blank-string? statement))
      (invalid :INVALID_ASSUMPTION
               "assumption needs a non-blank :statement"
               {:assumption-id assumption-id})

      (not (util/unit-number? confidence))
      (invalid :INVALID_ASSUMPTION
               "assumption :confidence must be in [0, 1]"
               {:assumption-id assumption-id :confidence confidence})

      (not (sequential? watch-paths))
      (invalid :INVALID_ASSUMPTION
               "assumption :watch-paths must be sequential"
               {:assumption-id assumption-id :watch-paths watch-paths})

      (not (every? util/non-blank-string? watch-paths))
      (invalid :INVALID_ASSUMPTION
               "assumption :watch-paths must contain non-blank strings"
               {:assumption-id assumption-id :watch-paths watch-paths})

      (not (sequential? basis))
      (invalid :INVALID_ASSUMPTION
               "assumption :basis must be sequential"
               {:assumption-id assumption-id :basis basis})

      (empty? basis)
      (invalid :INVALID_ASSUMPTION
               "assumption :basis must name at least one evidence record"
               {:assumption-id assumption-id})

      (not (every? #(contains? (:evidence state) %) basis))
      (invalid :INVALID_ASSUMPTION
               "assumption :basis names unknown evidence"
               {:assumption-id assumption-id
                :unknown (vec (remove #(contains? (:evidence state) %) basis))})

      (not (boolean? retired?))
      (invalid :INVALID_ASSUMPTION
               "assumption :retired? must be boolean"
               {:assumption-id assumption-id :retired? retired?})

      :else
      {:ok? true
       :value {:assumption-id assumption-id
               :statement statement
               :confidence (double confidence)
               :watch-paths (evidence/normalize-paths watch-paths)
               :basis (util/sorted-distinct basis)
               :retired? retired?}})))

(defn- normalize-claim
  [state raw]
  (let [claim-id (:claim-id raw)
        statement (:statement raw)
        confidence (:confidence raw)
        weight (or (:weight raw) 1.0)
        evidence-ids (:evidence-ids raw)
        assumption-ids (or (:assumption-ids raw) [])
        dimension (or (:dimension raw) :open)]
    (cond
      (not (map? raw))
      (invalid :INVALID_CLAIM "claim must be a map" {:claim raw})

      (not (util/non-blank-string? claim-id))
      (invalid :INVALID_CLAIM "claim needs a non-blank :claim-id" {:claim raw})

      (not (util/non-blank-string? statement))
      (invalid :INVALID_CLAIM "claim needs a non-blank :statement"
               {:claim-id claim-id})

      (not (util/unit-number? confidence))
      (invalid :INVALID_CLAIM "claim :confidence must be in [0, 1]"
               {:claim-id claim-id :confidence confidence})

      (not (util/positive-number? weight))
      (invalid :INVALID_CLAIM "claim :weight must be positive"
               {:claim-id claim-id :weight weight})

      (not (sequential? evidence-ids))
      (invalid :INVALID_CLAIM "claim :evidence-ids must be sequential"
               {:claim-id claim-id :evidence-ids evidence-ids})

      (not (every? util/non-blank-string? evidence-ids))
      (invalid :INVALID_CLAIM
               "claim :evidence-ids must contain non-blank strings"
               {:claim-id claim-id :evidence-ids evidence-ids})

      (empty? evidence-ids)
      (invalid :INVALID_CLAIM
               "claim needs at least one evidence record"
               {:claim-id claim-id})

      (not (every? #(contains? (:evidence state) %) evidence-ids))
      (invalid :INVALID_CLAIM "claim cites unknown evidence"
               {:claim-id claim-id
                :unknown (vec (remove #(contains? (:evidence state) %) evidence-ids))})

      (not (sequential? assumption-ids))
      (invalid :INVALID_CLAIM "claim :assumption-ids must be sequential"
               {:claim-id claim-id :assumption-ids assumption-ids})

      (not (every? util/non-blank-string? assumption-ids))
      (invalid :INVALID_CLAIM
               "claim :assumption-ids must contain non-blank strings"
               {:claim-id claim-id :assumption-ids assumption-ids})

      (not (every? #(contains? (:assumptions state) %) assumption-ids))
      (invalid :INVALID_CLAIM "claim cites an unknown assumption"
               {:claim-id claim-id
                :unknown (vec (remove #(contains? (:assumptions state) %) assumption-ids))})

      (not (keyword? dimension))
      (invalid :INVALID_CLAIM "claim :dimension must be a keyword"
               {:claim-id claim-id :dimension dimension})

      (not (contains? known-dimensions dimension))
      (invalid :INVALID_CLAIM
               "claim :dimension must be a known dimension"
               {:claim-id claim-id
                :dimension dimension
                :supported known-dimensions})

      :else
      (let [inactive (->> assumption-ids
                          (map (fn [assumption-id]
                                 [assumption-id
                                  (assumption-status
                                   state
                                   (get-in state [:assumptions assumption-id]))]))
                          (remove (fn [[_ status]] (= :active (:status status))))
                          vec)]
        (if (seq inactive)
          (invalid :INVALID_CLAIM
                   "claim cites an assumption that is not active"
                   {:claim-id claim-id
                    :inactive (mapv (fn [[assumption-id status]]
                                      {:assumption-id assumption-id
                                       :status (:status status)})
                                    inactive)})
          {:ok? true
           :value {:claim-id claim-id
                   :statement statement
                   :confidence (double confidence)
                   :weight (double weight)
                   :evidence-ids (util/sorted-distinct evidence-ids)
                   :assumption-ids (util/sorted-distinct assumption-ids)
                   :dimension dimension
                   :status :active}})))))

(defn revise-best-answer
  "Submit a new thought frontier, assumptions, and claims.

  The revision is refused with `:NO_NEW_EVIDENCE` when no evidence entered
  since the previous revision.  Confidence may rise or fall; it is not
  monotonic.  Every claim must cite known evidence and active assumptions."
  [state revision]
  (let [last-revised (get-in state [:reasoning :last-revised-evidence-seq] 0)
        current-evidence-seq (:evidence-seq state)
        summary (:summary revision)
        frontier-of-thought (:frontier-of-thought revision)
        raw-assumptions (or (:assumptions revision) [])
        raw-claims (or (:claims revision) [])
        withdraw-claim-ids (or (:withdraw-claim-ids revision) [])]
    (cond
      (not (map? revision))
      (util/fail-state state :INVALID_REVISION "revision must be a map" nil)

      (<= current-evidence-seq last-revised)
      (util/fail-state
       state
       :NO_NEW_EVIDENCE
       "repeated reasoning is not new information; ingest new evidence first"
       {:last-revised-evidence-seq last-revised
        :current-evidence-seq current-evidence-seq})

      (not (util/non-blank-string? summary))
      (util/fail-state state :INVALID_REVISION
                       "revision needs a non-blank :summary" nil)

      (not (util/non-blank-string? frontier-of-thought))
      (util/fail-state state :INVALID_REVISION
                       "revision needs a non-blank :frontier-of-thought" nil)

      (not (sequential? raw-assumptions))
      (util/fail-state state :INVALID_REVISION
                       "revision :assumptions must be sequential" nil)

      (not (sequential? raw-claims))
      (util/fail-state state :INVALID_REVISION
                       "revision :claims must be sequential" nil)

      (not (sequential? withdraw-claim-ids))
      (util/fail-state state :INVALID_REVISION
                       "revision :withdraw-claim-ids must be sequential" nil)

      :else
      (let [assumption-results (util/normalize-all
                                #(normalize-assumption state %)
                                raw-assumptions)]
        (if-not (:ok? assumption-results)
          (util/fail-state state
                           (get-in assumption-results [:error :code])
                           (get-in assumption-results [:error :message])
                           (get-in assumption-results [:error :details]))
          (let [duplicate-assumptions (duplicate-values (:values assumption-results)
                                                        :assumption-id)]
            (if (seq duplicate-assumptions)
              (util/fail-state state :INVALID_REVISION
                               "revision contains duplicate assumption ids"
                               {:assumption-ids duplicate-assumptions})
              (let [prospective (update state :assumptions
                                        merge
                                        (into {} (map (juxt :assumption-id identity)
                                                       (:values assumption-results))))
                    claim-results (util/normalize-all
                                   #(normalize-claim prospective %)
                                   raw-claims)]
                (if-not (:ok? claim-results)
                  (util/fail-state state
                                   (get-in claim-results [:error :code])
                                   (get-in claim-results [:error :message])
                                   (get-in claim-results [:error :details]))
                  (let [duplicate-claims (duplicate-values (:values claim-results)
                                                           :claim-id)]
                    (if (seq duplicate-claims)
                      (util/fail-state state :INVALID_REVISION
                                       "revision contains duplicate claim ids"
                                       {:claim-ids duplicate-claims})
                      (let [withdraw (util/sorted-distinct withdraw-claim-ids)
                            unknown-withdraw (remove #(contains? (:claims prospective) %)
                                                     withdraw)]
                        (if (seq unknown-withdraw)
                          (util/fail-state state :INVALID_REVISION
                                           "revision withdraws an unknown claim"
                                           {:unknown-claims (vec unknown-withdraw)})
                          (let [assumptions (into {}
                                          (map (fn [assumption]
                                                 (let [existing (get-in state [:assumptions
                                                                                (:assumption-id assumption)])]
                                                   [(:assumption-id assumption)
                                                    (merge existing
                                                           assumption
                                                           {:created-at-evidence-seq
                                                            (or (:created-at-evidence-seq existing)
                                                                current-evidence-seq)
                                                            :revised-at-evidence-seq
                                                            current-evidence-seq})])))
                                          (:values assumption-results))
                        claims (into {}
                                     (map (fn [claim]
                                            (let [existing (get-in state [:claims (:claim-id claim)])]
                                              [(:claim-id claim)
                                               (merge existing
                                                      claim
                                                      {:status :active
                                                       :created-at-evidence-seq
                                                       (or (:created-at-evidence-seq existing)
                                                           current-evidence-seq)
                                                       :revised-at-evidence-seq
                                                       current-evidence-seq})])))
                                     (:values claim-results))
                        state' (-> state
                                   (update :assumptions merge assumptions)
                                   (update :claims merge claims)
                                   (update :claims
                                           (fn [claim-map]
                                             (reduce (fn [m claim-id]
                                                       (assoc-in m [claim-id :status]
                                                                 :withdrawn))
                                                     claim-map
                                                     withdraw)))
                                   (assoc :answer-meta
                                          {:summary summary
                                           :frontier-of-thought frontier-of-thought})
                                   (assoc-in [:reasoning :last-revised-evidence-seq]
                                             current-evidence-seq)
                                   (update-in [:reasoning :revision] inc)
                                   bump-version)
                        state' (assoc state' :best-answer
                                      (rebuild-best-answer state'))]
                    {:ok? true
                     :state state'
                     :best-answer (:best-answer state')
                     :revision (get-in state' [:reasoning :revision])}))))))))))))))

(defn open-question
  "Open a local sub-question under the permanent root question."
  [state {:keys [question-id question]}]
  (cond
    (not (util/non-blank-string? question-id))
    (util/fail-state state :INVALID_QUESTION
                     "question needs a non-blank :question-id" nil)

    (= question-id "root")
    (util/fail-state state :INVALID_QUESTION
                     "`root` is reserved for the permanent root question" nil)

    (contains? (:questions state) question-id)
    (util/fail-state state :QUESTION_ALREADY_EXISTS
                     "question id already exists" {:question-id question-id})

    (not (util/non-blank-string? question))
    (util/fail-state state :INVALID_QUESTION
                     "question needs a non-blank :question" nil)

    :else
    (let [state' (-> state
                     (assoc-in [:questions question-id]
                               {:question-id question-id
                                :question question
                                :status :open
                                :permanent? false})
                     bump-version)
          state' (assoc state' :best-answer (rebuild-best-answer state'))]
      {:ok? true :state state' :best-answer (:best-answer state')})))

(defn resolve-question
  "Resolve a local sub-question with named evidence.

  The root question can never be resolved.  Resolution is local closure, not
  the end of the Sphinx."
  [state {:keys [question-id answer evidence-ids]}]
  (let [question (get-in state [:questions question-id])]
    (cond
      (or (= question-id :root) (= question-id "root"))
      (util/fail-state state :ROOT_QUESTION_NEVER_CLOSES
                       "the root question is permanent and never resolves" nil)

      (nil? question)
      (util/fail-state state :UNKNOWN_QUESTION
                       "question id is not registered" {:question-id question-id})

      (= :resolved (:status question))
      (util/fail-state state :QUESTION_ALREADY_RESOLVED
                       "question is already resolved" {:question-id question-id})

      (not (util/non-blank-string? answer))
      (util/fail-state state :INVALID_QUESTION
                       "resolution needs a non-blank :answer"
                       {:question-id question-id})

      (not (sequential? evidence-ids))
      (util/fail-state state :INVALID_QUESTION
                       "resolution :evidence-ids must be sequential"
                       {:question-id question-id})

      (empty? evidence-ids)
      (util/fail-state state :INVALID_QUESTION
                       "resolution needs at least one evidence record"
                       {:question-id question-id})

      (not (every? #(contains? (:evidence state) %) evidence-ids))
      (util/fail-state state :INVALID_QUESTION
                       "resolution cites unknown evidence"
                       {:question-id question-id
                        :unknown (vec (remove #(contains? (:evidence state) %)
                                              evidence-ids))})

      :else
      (let [state' (-> state
                       (assoc-in [:questions question-id]
                                 (merge question
                                        {:status :resolved
                                         :answer answer
                                         :evidence-ids (util/sorted-distinct evidence-ids)
                                         :resolved-at-evidence-seq (:evidence-seq state)}))
                       bump-version)
            state' (assoc state' :best-answer (rebuild-best-answer state'))]
        {:ok? true :state state' :best-answer (:best-answer state')}))))

(defn best-answer
  [state]
  (:best-answer state))

(defn cognition-version
  [state]
  (:cognition-version state))

(defn evidence-count
  [state]
  (count (:evidence state)))
