(ns hc.compose.steward.loop
  "One pure Steward advance.

  `tick` composes `hc.compose.steward` and returns explicit effects.  It
  never reads the clock, the file system, Git, a model, or a Store.  The host
  (`hc.server.app.steward-runtime`) executes the effects in order and commits
  the returned state.

  Effect order is fixed:

    1. `:model/meditate`  after new evidence was ingested
    2. `:model/decide`    at a newly pulled Decision Frontier
    3. `:plan/start`      one per admitted Mission without a Plan session
    4. `:fleet/fork`      one per plan-ready Mission without swarms
    5. `:report/emit`     one per join result, in world order
    6. `:diagnostic`      failures observed while composing
    7. `:persist`         only when the state changed; always last

  The same STATE and WORLD always produce the same value, including effect
  order.  Effects are at-least-once: a caller that reuses a WORLD after a
  successful tick will see the same effects again.  Use
  `effect-fingerprint` to de-duplicate inside a bounded window."
  (:require [clojure.string :as str]
            [hc.compose.steward :as steward]
            [hc.core.steward-sphinx :as ss]))

(def schema-version 1)
(def prompt-version 1)
(def default-plan-stall-ms 600000)
(def max-stall-reported 256)

(def ^:private max-evidence-ids-in-effect
  "The meditator gets the newest evidence ids; the count stays exact."
  64)

(def ^:private max-candidates-hint
  32)

(def ^:private problem-message
  {:INVALID_EVIDENCE "the Git observation was refused"
   :INVALID_OBSERVATION "the world observation was refused"
   :INVALID_INTENT "the operator intent was refused"
   :INVALID_CAPACITY "the capacity observation was refused"
   :INTENT_MISSING "no non-blank operator intent is available yet"
   :STALE_OBSERVATION "the upstream observation is stale"})

;; ---------------------------------------------------------------------------
;; Small helpers

(defn- problem
  [code message]
  (cond-> {:code code :message message}
    (nil? message) (assoc :message
                          (get problem-message code
                               "the Steward refused the observation"))))

(defn- diagnostic
  [stage code message details at]
  (cond-> {:type :diagnostic
           :code code
           :message message
           :stage stage
           :at at}
    (some? details) (assoc :details details)))

(defn- skipped
  [stage code details]
  (cond-> {:stage stage :reason code}
    (some? details) (assoc :details details)))

(defn- error-diagnostic
  [stage error at]
  (let [code (or (:code error) :STEWARD_REFUSED)
        message (or (:message error)
                    (get problem-message code "the Steward refused the observation"))]
    (diagnostic stage code message (:details error) at)))

(defn- valid-intent?
  [intent]
  (and (map? intent)
       (integer? (:revision intent))
       (not (neg? (:revision intent)))
       (string? (:text intent))
       (not (str/blank? (:text intent)))))

(defn- capacity-observation
  "Turn the run world's capacity description into a Sphinx capacity value.

  A `:rows` key means live fleet rows and is aggregated by
  `steward/fleet-capacity`.  A value without `:rows` is passed through and
  validated by `steward/observe-world`."
  [capacity]
  (cond
    (nil? capacity) nil
    (contains? capacity :rows) (steward/fleet-capacity
                                (:rows capacity)
                                (select-keys capacity [:slots-total :min-spare]))
    :else capacity))

(defn- normalize-commits
  "Validate and normalize every incoming commit.  All or nothing."
  [commits]
  (let [raw (vec (or commits []))
        results (mapv ss/normalize-evidence raw)
        invalid (first (remove :ok? results))]
    (if invalid
      {:ok? false :error (:error invalid)}
      {:ok? true :value (mapv :value results)})))

(defn- evidence-suffix
  "The ids appended after BEFORE in AFTER.

  The fast path uses the fact that `:evidence-order` only grows.  A caller
  that supplies an unrelated vector falls back to set subtraction."
  [before after]
  (let [before (vec (or before []))
        after (vec (or after []))
        n (count before)]
    (if (and (<= n (count after))
             (= before (subvec after 0 n)))
      (subvec after n)
      (let [seen (set before)]
        (vec (remove seen after))))))

(defn- observation-substance
  [observation]
  (some-> observation (dissoc :at)))

(defn- keep-observation
  "Keep AT stable until the observed substance changes.

  Without this, every periodic tick would rewrite `:last-observation` and
  commit an otherwise identical state."
  [previous next]
  (if (= (observation-substance previous)
         (observation-substance next))
    previous
    next))

;; ---------------------------------------------------------------------------
;; Phase 1: observation

(defn- observe-phase
  [state world now]
  (let [commits (get-in world [:git :new-commits])
        intent (when (valid-intent? (:intent world))
                 (select-keys (:intent world) [:revision :text :received-at]))
        capacity (capacity-observation (:capacity world))]
    (if (and (nil? intent) (nil? capacity) (empty? commits))
      {:state state
       :observations {:at now}
       :skipped []
       :diagnostics []}
      (let [normalized (normalize-commits commits)]
        (if-not (:ok? normalized)
          (let [error (:error normalized)]
            {:state state
             :observations {:at now}
             :skipped [(skipped :observe-world (:code error) (:details error))]
             :diagnostics [(error-diagnostic :observe-world error now)]})
          (let [before-order (get-in state [:cognition :evidence-order] [])
                before-observation (:last-observation state)
                result (steward/observe-world
                        state
                        {:now now
                         :intent intent
                         :capacity capacity
                         :git-commits (:value normalized)})]
            (if-not (:ok? result)
              (let [error (:error result)]
                {:state state
                 :observations {:at now}
                 :skipped [(skipped :observe-world (:code error) (:details error))]
                 :diagnostics [(error-diagnostic :observe-world error now)]})
              (let [observed (:state result)
                    observed (assoc observed
                                    :last-observation
                                    (keep-observation
                                     before-observation
                                     (:last-observation observed)))
                    after-order (get-in observed [:cognition :evidence-order] [])
                    new-ids (evidence-suffix before-order after-order)
                    evidence-seq (get-in observed [:cognition :evidence-seq] 0)
                    shown-ids (vec (take-last max-evidence-ids-in-effect
                                              new-ids))
                    meditate (when (seq new-ids)
                               {:type :model/meditate
                                :role :meditator
                                :evidence-ids shown-ids
                                :evidence-count (count new-ids)
                                :evidence-seq evidence-seq
                                :prompt-version prompt-version
                                :at now})]
                {:state observed
                 :observations
                 {:at now
                  :evidence-seq evidence-seq
                  :new-evidence-count (count new-ids)
                  :new-evidence-ids shown-ids
                  :intent-revision (get-in observed [:intent :revision])
                  :capacity-signature (ss/capacity-signature
                                       (:capacity observed))
                  :head (get-in world [:git :head])
                  :master-version (get-in world [:git :master-version])}
                 :meditate meditate
                 :skipped []
                 :diagnostics []}))))))))

;; ---------------------------------------------------------------------------
;; Phase 2: the Decision Frontier

(defn- bounded-hint-entry
  [entry]
  (select-keys entry [:candidate-id :title :needed :reasons :summary]))

(defn- candidates-hint
  "Bounded material the Steward model needs to propose candidates."
  [state frontier]
  (let [docket (:docket state)
        intent (:intent state)
        capacity (:capacity state)]
    {:intent-revision (:revision intent)
     :operator-intent (:text intent)
     :frontier-id (:frontier-id frontier)
     :frontier-reasons (vec (:reasons frontier))
     :slots-total (:slots-total capacity)
     :slots-allocated (:slots-allocated capacity)
     :spare (:spare capacity)
     :absorbable (:absorbable capacity)
     :open-missions
     (->> (vals (:missions docket))
          (filter #(contains? #{:admitted :planning :active} (:status %)))
          (sort-by :mission-id)
          (take max-candidates-hint)
          (mapv #(select-keys % [:mission-id :candidate-id :title :status
                                 :demand]))
          vec)
     :deferred
     (->> (vals (:deferred docket))
          (sort-by :candidate-id)
          (take max-candidates-hint)
          (mapv bounded-hint-entry)
          vec)
     :awaiting-evidence
     (->> (vals (:awaiting-evidence docket))
          (sort-by :candidate-id)
          (take max-candidates-hint)
          (mapv bounded-hint-entry)
          vec)
     :do-not-do
     (->> (vals (:do-not-do docket))
          (sort-by :candidate-id)
          (take max-candidates-hint)
          (mapv :candidate-id)
          vec)}))

(defn- frontier-phase
  [state world now]
  (cond
    (true? (:stale? world))
    {:state state
     :skipped [(skipped :frontier :STALE_OBSERVATION nil)]
     :diagnostics []}

    (not (valid-intent? (:intent state)))
    {:state state
     :skipped [(skipped :frontier :INTENT_MISSING nil)]
     :diagnostics []}

    :else
    (let [result (steward/evaluate-frontier state)]
      (if-not (:ok? result)
        (let [error (:error result)]
          {:state state
           :skipped [(skipped :frontier (:code error) (:details error))]
           :diagnostics [(error-diagnostic :frontier error now)]})
        (let [state' (:state result)
              frontier (:frontier result)]
          {:state state'
           :frontier frontier
           :effect (when frontier
                     {:type :model/decide
                      :role :steward
                      :frontier-id (:frontier-id frontier)
                      :frontier frontier
                      :cba (:best-answer frontier)
                      :candidates-hint (candidates-hint state' frontier)
                      :prompt-version prompt-version
                      :at now})
           :skipped []
           :diagnostics []})))))

;; ---------------------------------------------------------------------------
;; Phase 3: Docket decisions

(defn- decision-requests
  [world]
  (let [decision (:decision world)]
    (cond
      (nil? decision) []
      (map? decision) [decision]
      (sequential? decision) (vec (filter map? decision))
      :else [{:invalid decision}])))

(defn- decide-phase
  [state world now]
  (reduce
   (fn [acc request]
     (let [expected (:frontier-id request)
           actual (get-in (:state acc) [:frontier :last-frontier
                                        :frontier-id])]
       (if (and expected (not= expected actual))
         (-> acc
             (update :skipped conj
                     (skipped :decide :STALE_DECISION_FRONTIER
                              {:expected expected :actual actual}))
             (update :diagnostics conj
                     (diagnostic :decide :STALE_DECISION_FRONTIER
                                 "the decision answers an older frontier"
                                 {:expected expected :actual actual} now)))
         (let [result (steward/decide
                       (:state acc)
                       (select-keys request
                                    [:request-id :candidates :policy]))]
           (if (:ok? result)
             (-> acc
                 (assoc :state (:state result))
                 (update :receipts conj
                         (select-keys (:decision result)
                                      [:request-id :decision-id :frontier-id
                                       :docket-version :admitted :idle?
                                       :outcome :mode])))
             (let [error (:error result)]
               (-> acc
                   (update :skipped conj
                           (skipped :decide (:code error) (:details error)))
                   (update :diagnostics conj
                           (cond-> (error-diagnostic :decide error now)
                             (:request-id request)
                             (assoc :request-id
                                    (:request-id request)))))))))))
   {:state state
    :receipts []
    :skipped []
    :diagnostics []}
   (decision-requests world)))

;; ---------------------------------------------------------------------------
;; Phase 4: Plan handoff

(defn- plan-start-effect
  [mission link now]
  (when-not (or (:session-id link)
                (:plan-status link)
                (:swarm-ids link))
    {:type :plan/start
     :mission-id (:mission-id mission)
     :candidate-id (:candidate-id mission)
     :title (:title mission)
     :intent-version (or (get-in mission [:operator-intent :revision]) 0)
     :at now}))

(defn- record-plan-started
  [state mission-id now]
  (let [link (get-in state [:links mission-id])]
    (if (contains? link :plan-started-at)
      state
      (let [result (steward/link-mission state mission-id
                                         {:plan-started-at now})]
        (if (:ok? result) (:state result) state)))))

(defn- plan-start-phase
  "Emit plan starts and stamp the first attempt on the mission link.

  The timestamp is written when the effect is emitted; it is never
  overwritten by a retry, so a stall measures the earliest attempt."
  [state now]
  (reduce
   (fn [acc mission]
     (let [mission-id (:mission-id mission)
           link (get-in (:state acc) [:links mission-id])
           effect (plan-start-effect mission link now)]
       (if-not effect
         acc
         (-> acc
             (update :effects conj effect)
             (update :state record-plan-started mission-id now)))))
   {:state state :effects []}
   (->> (vals (get-in state [:docket :missions]))
        (filter #(= :admitted (:status %)))
        (sort-by :mission-id))))

;; ---------------------------------------------------------------------------
;; Phase 5: fork

(defn- link-plan-ready?
  [link]
  (or (= :plan-ready (:plan-status link))
      (= :plan-ready (:status link))))

(defn- fork-phase
  [state now]
  (reduce
   (fn [acc [mission-id link]]
     (let [mission (get-in state [:docket :missions mission-id])
           slices (:slices link)]
       (cond
         (nil? mission)
         acc

         (contains? #{:completed :failed :abandoned} (:status mission))
         acc

         (seq (:swarm-ids link))
         acc

         (not (link-plan-ready? link))
         acc

         (not (and (sequential? slices) (seq slices)))
         (-> acc
             (update :skipped conj
                     (skipped :fork :MISSION_SLICES_MISSING
                              {:mission-id mission-id}))
             (update :diagnostics conj
                     (diagnostic :fork :MISSION_SLICES_MISSING
                                 "a plan-ready mission has no slices to fork"
                                 {:mission-id mission-id} now)))

         :else
         (-> acc
             (update :effects conj
                     {:type :fleet/fork
                      :mission-id mission-id
                      :slices (vec slices)
                      :intent-version (or (get-in mission
                                                  [:operator-intent :revision])
                                          0)
                      :at now})))))
   {:effects [] :skipped [] :diagnostics []}
   (sort-by (comp str key) (:links state))))

;; ---------------------------------------------------------------------------
;; Phase 5b: plan stalls

(defn- stall-entries
  "Normalize the persisted stall ledger to `{mission-id reported-at}`.

  A set or vector is accepted for compatibility; it becomes a map so the
  oldest report can be evicted deterministically."
  [reported]
  (cond
    (map? reported)
    (into {} (map (fn [[mission-id at]]
                    [mission-id (or at 0)])
                  reported))

    (set? reported)
    (into {} (map (fn [mission-id] [mission-id 0]) reported))

    (sequential? reported)
    (into {} (map-indexed (fn [index mission-id] [mission-id index])
                          reported))

    :else
    {}))

(defn- prune-stall-ledger
  [reported]
  (let [entries (sort-by (fn [[mission-id at]]
                           [(or at 0) (str mission-id)])
                         reported)
        excess (max 0 (- (count entries) max-stall-reported))
        kept (if (pos? excess) (drop excess entries) entries)]
    {:reported (into {} kept)
     :dropped excess}))

(defn- stall-phase
  "Report plan-started missions whose handoff never became plan-ready.

  Pure: it only adds diagnostics and a bounded ledger to the state.  It does
  not move the mission, retry `:plan/start`, or fork anything."
  [state world now]
  (let [threshold (let [value (:plan-stall-ms world)]
                    (if (and (integer? value) (pos? value))
                      (long value)
                      default-plan-stall-ms))
        reported (stall-entries (:stall-reported state))
        previous-dropped (long (or (:stall-ledger-dropped state) 0))
        candidates
        (->> (:links state)
             (sort-by (comp str key))
             (keep (fn [[mission-id link]]
                     (let [mission (get-in state [:docket :missions
                                                  mission-id])
                           status (:status mission)
                           session-id (:session-id link)
                           started (:plan-started-at link)
                           stalled-ms (when (and (number? now)
                                                 (number? started))
                                        (- now started))]
                       (when (and mission
                                  (contains? #{:admitted :planning} status)
                                  (string? session-id)
                                  (not (str/blank? session-id))
                                  (not= :plan-ready (:plan-status link))
                                  (number? stalled-ms)
                                  (> stalled-ms threshold)
                                  (not (contains? reported mission-id)))
                         {:mission-id mission-id
                          :stalled-ms stalled-ms}))))
             vec)
        ledger (merge reported
                      (into {}
                            (map (fn [{:keys [mission-id]}]
                                   [mission-id now])
                                 candidates)))
        pruned (prune-stall-ledger ledger)
        dropped (+ previous-dropped (:dropped pruned))
        diagnostics
        (mapv (fn [{:keys [mission-id stalled-ms]}]
                {:type :diagnostic
                 :code :MISSION_PLAN_STALLED
                 :mission-id mission-id
                 :stalled-ms stalled-ms
                 :message "plan started but no plan-ready handoff arrived"
                 :at now})
              candidates)
        state' (if (and (empty? candidates)
                        (= reported (:reported pruned))
                        (= previous-dropped dropped))
                 state
                 (assoc state
                        :stall-reported (:reported pruned)
                        :stall-ledger-dropped dropped))]
    {:state state'
     :diagnostics diagnostics}))

;; ---------------------------------------------------------------------------
;; Phase 6: join results

(defn- join-outcome
  [result]
  (cond
    (not (map? result)) nil
    (contains? result :fleet/error) :failed
    (= :abandoned (:status result)) :abandoned
    :else :completed))

(defn- mission-id-of-result
  [state entry]
  (let [result (:result entry)]
    (or (:mission-id entry)
        (:mission-id result)
        (let [swarm-id (or (:swarm-id result) (:swarm-id entry))]
          (when (string? swarm-id)
            (some (fn [[mission-id link]]
                    (when (some #{swarm-id} (:swarm-ids link))
                      mission-id))
                  (:links state)))))))

(defn- advance
  [state mission-id to reason]
  (let [result (steward/transition-mission state mission-id to reason)]
    (if (:ok? result)
      {:ok? true :state (:state result)}
      {:ok? false :state state :error (:error result)})))

(defn- run-transition
  "Move MISSION-ID to TARGET, inserting the lifecycle step core requires.

  `:admitted` cannot move straight to `:completed` or `:failed`, so the
  intermediate step stays explicit here."
  [state mission-id target reason]
  (let [status (get-in state [:docket :missions mission-id :status])]
    (cond
      (nil? status)
      {:ok? false
       :state state
       :error (problem :UNKNOWN_MISSION
                       "join names a mission that is not in the Docket")}

      (= status target)
      {:ok? true :state state :unchanged? true}

      :else
      (let [prepared
            (case target
              :completed (if (contains? #{:admitted :planning} status)
                           (advance state mission-id :active reason)
                           {:ok? true :state state})
              :failed (if (= :admitted status)
                        (advance state mission-id :planning reason)
                        {:ok? true :state state})
              {:ok? true :state state})]
        (if (:ok? prepared)
          (advance (:state prepared) mission-id target reason)
          prepared)))))

(defn- report-effect
  [entry mission-id outcome state now]
  (let [result (:result entry)
        mission (get-in state [:docket :missions mission-id])
        fleet-error (:fleet/error result)
        swarm-id (or (:swarm-id result) (:swarm-id fleet-error))
        ff-commit (or (:ff-commit result) (:ff-commit fleet-error))]
    (cond-> {:type :report/emit
             :mission-id mission-id
             :outcome outcome
             :status (:status mission)
             :at now}
      (string? (:report result)) (assoc :report (:report result))
      (map? fleet-error)
      (assoc :error (select-keys fleet-error
                                 [:code :message :swarm-id :stage]))
      (string? ff-commit)
      (assoc :ff-commit ff-commit)
      (integer? (:rebase-rounds result))
      (assoc :rebase-rounds (:rebase-rounds result))
      (string? swarm-id)
      (assoc :swarm-id swarm-id))))

(defn- join-phase
  [state world now]
  (reduce
   (fn [acc entry]
     (let [result (:result entry)
           outcome (join-outcome result)
           mission-id (mission-id-of-result (:state acc) entry)]
       (cond
         (nil? mission-id)
         (-> acc
             (update :skipped conj
                     (skipped :join :UNKNOWN_JOIN_MISSION
                              {:swarm-id (:swarm-id result)}))
             (update :diagnostics conj
                     (diagnostic :join :UNKNOWN_JOIN_MISSION
                                 "a join result names no linked mission"
                                 {:swarm-id (:swarm-id result)} now)))

         (nil? outcome)
         (-> acc
             (update :skipped conj
                     (skipped :join :INVALID_JOIN_RESULT
                              {:mission-id mission-id}))
             (update :diagnostics conj
                     (diagnostic :join :INVALID_JOIN_RESULT
                                 "a join result is not a map"
                                 {:mission-id mission-id} now)))

         :else
         (let [moved (run-transition (:state acc) mission-id outcome
                                     (str "fleet/" (name outcome)))]
           (if-not (:ok? moved)
             (-> acc
                 (update :skipped conj
                         (skipped :join (get-in moved [:error :code])
                                  (get-in moved [:error :details])))
                 (update :diagnostics conj
                         (error-diagnostic :join (:error moved) now)))
             (-> acc
                 (assoc :state (:state moved))
                 (update :effects conj
                         (report-effect entry mission-id outcome
                                        (:state moved) now))))))))
   {:state state :effects [] :skipped [] :diagnostics []}
   (vec (or (get-in world [:fleet :results]) []))))

;; ---------------------------------------------------------------------------
;; The tick

(defn tick
  "One complete Steward advance.

  WORLD:

    {:now epoch-ms
     :git {:head \"sha\" :new-commits [...] :master-version n}
     :intent {:revision n :text \"...\"}       ; optional observation
     :capacity {:rows [...] :slots-total n :min-spare n}
     :plan-stall-ms n                          ; optional, default 600000
     :stale? bool
     :decision {:request-id \"...\" :candidates [...] :policy {...}}
     :fleet {:results [{:mission-id \"...\" :result {...}}]}}

  `:git :new-commits` may hold raw or already-normalized evidence.  A
  `:decision` may be one map or a sequential collection.  A stale world still
  ingests evidence but never pulls a frontier.

  Returns:

    {:state state'
     :effects [...]
     :observations {...}
     :skipped [{:stage ... :reason code ...}]}"
  [state world]
  (let [state (or state (steward/initial-state))
        world (if (map? world) world {})
        now (:now world)
        observed (observe-phase state world now)
        state0 (:state observed)
        pulled (frontier-phase state0 world now)
        state1 (:state pulled)
        decided (decide-phase state1 world now)
        state2 (:state decided)
        planned (plan-start-phase state2 now)
        state2' (:state planned)
        plans (:effects planned)
        forked (fork-phase state2' now)
        joined (join-phase state2' world now)
        stalled (stall-phase (:state joined) world now)
        state3 (:state stalled)
        effects (cond-> []
                  (:meditate observed) (conj (:meditate observed))
                  (:effect pulled) (conj (:effect pulled))
                  (seq plans) (into plans)
                  (seq (:effects forked)) (into (:effects forked))
                  (seq (:effects joined)) (into (:effects joined))
                  (seq (:diagnostics observed)) (into (:diagnostics observed))
                  (seq (:diagnostics pulled)) (into (:diagnostics pulled))
                  (seq (:diagnostics decided)) (into (:diagnostics decided))
                  (seq (:diagnostics forked)) (into (:diagnostics forked))
                  (seq (:diagnostics joined)) (into (:diagnostics joined))
                  (seq (:diagnostics stalled)) (into (:diagnostics stalled)))
        effects (cond-> effects
                  (not= state state3)
                  (conj {:type :persist :state state3 :at now}))]
    {:state state3
     :effects effects
     :observations
     (cond-> (merge (:observations observed)
                    {:at now
                     :frontier-triggered? (boolean (:effect pulled))
                     :frontier-id (when (:effect pulled)
                                    (:frontier-id (:effect pulled)))
                     :decision-receipts (vec (:receipts decided))
                     :plan-starts (count plans)
                     :forks (count (:effects forked))
                     :reports (count (:effects joined))
                     :plan-stalls (count (:diagnostics stalled))
                     :effect-count (count effects)})
       (seq (:skipped observed)) (assoc :observe-skipped true))
     :skipped (vec (concat (:skipped observed)
                           (:skipped pulled)
                           (:skipped decided)
                           (:skipped forked)
                           (:skipped joined)))}))

;; ---------------------------------------------------------------------------
;; Effect identity

(defn effect-fingerprint
  "A bounded, stable identity for one effect, or a vector for a collection.

  Volatile observation time and large payloads (`:state`, `:report`,
  `:frontier`, `:cba`, `:slices`, `:candidates-hint`) are excluded.  A host
  keeps the fingerprints it executed for a bounded window and skips a
  repeated fingerprint; `:persist` is never skipped."
  [effect]
  (cond
    (sequential? effect)
    (mapv effect-fingerprint effect)

    (map? effect)
    (case (:type effect)
      :model/meditate
      [:model/meditate (:role effect) (:evidence-seq effect)]

      :model/decide
      [:model/decide (:role effect) (:frontier-id effect)]

      :plan/start
      [:plan/start (:mission-id effect) (:intent-version effect)]

      :fleet/fork
      [:fleet/fork (:mission-id effect)
       (mapv :slice-id (:slices effect))]

      :report/emit
      [:report/emit (:mission-id effect) (:outcome effect)
       (:swarm-id effect) (:ff-commit effect)]

      :diagnostic
      [:diagnostic (:code effect) (:mission-id effect) (:request-id effect)]

      :persist
      [:persist]

      [(:type effect) (:mission-id effect)])

    :else
    nil))

;; ---------------------------------------------------------------------------
;; Invariants

(defn- invariant-error
  [code message details]
  (cond-> {:code code :message message}
    (some? details) (assoc :details details)))

(defn- unknown-links
  [state]
  (let [missions (get-in state [:docket :missions])
        links (:links state)]
    (if (and (map? missions) (map? links))
      (->> (keys links)
           (remove #(contains? missions %))
           (sort-by str)
           vec)
      [])))

(defn check-invariants
  "Check the core schema plus the loop layer's own linkage rules.

  Returns `{:ok? true :errors []}` or `{:ok? false :errors [{:code ...}]}`.
  The core facade owns the cognition, frontier, and Docket schemas; this
  function checks the versions it exposes plus the linkage the loop relies
  on."
  [state]
  (if-not (map? state)
    {:ok? false
     :errors [(invariant-error :INVALID_STATE
                               "steward state must be a map"
                               {:class (some-> state class .getName)})]}
    (let [cognition (:cognition state)
          frontier (:frontier state)
          docket (:docket state)
          unknown (unknown-links state)
          errors
          (cond-> []
            (not= schema-version (:schema-version state))
            (conj (invariant-error
                   :INVALID_SCHEMA_VERSION
                   "steward state schema-version must be 1"
                   {:schema-version (:schema-version state)}))

            (not (map? cognition))
            (conj (invariant-error :INVALID_COGNITION
                                   "cognition must be a map"
                                   {:class (some-> cognition class .getName)}))

            (and (map? cognition)
                 (not= 1 (:schema-version cognition)))
            (conj (invariant-error
                   :INVALID_COGNITION_SCHEMA
                   "cognition schema-version must be 1"
                   {:schema-version (:schema-version cognition)}))

            (not (map? frontier))
            (conj (invariant-error :INVALID_FRONTIER
                                   "frontier must be a map"
                                   {:class (some-> frontier class .getName)}))

            (and (map? frontier)
                 (not= 1 (:schema-version frontier)))
            (conj (invariant-error
                   :INVALID_FRONTIER_SCHEMA
                   "frontier schema-version must be 1"
                   {:schema-version (:schema-version frontier)}))

            (not (map? docket))
            (conj (invariant-error :INVALID_DOCKET
                                   "docket must be a map"
                                   {:class (some-> docket class .getName)}))

            (and (map? docket)
                 (not= 1 (:schema-version docket)))
            (conj (invariant-error
                   :INVALID_DOCKET_SCHEMA
                   "docket schema-version must be 1"
                   {:schema-version (:schema-version docket)}))

            (not (map? (get-in state [:docket :missions])))
            (conj (invariant-error :INVALID_DOCKET
                                   "docket needs a :missions map"
                                   {:missions (get-in state [:docket :missions])}))

            (not (map? (:links state)))
            (conj (invariant-error :INVALID_LINKS
                                   "links must be a map"
                                   {:class (some-> (:links state) class .getName)}))

            (not (and (integer? (get-in state [:cognition :cognition-version]))
                      (not (neg? (get-in state [:cognition :cognition-version])))))
            (conj (invariant-error
                   :INVALID_COGNITION_VERSION
                   "cognition needs a non-negative integer version"
                   {:cognition-version
                    (get-in state [:cognition :cognition-version])}))

            (not= (get-in state [:cognition :cognition-version])
                  (get-in state [:cognition :best-answer :cognition-version]))
            (conj (invariant-error
                   :STALE_BEST_ANSWER
                   "the Current Best Answer must carry the cognition version"
                   {:cognition-version
                    (get-in state [:cognition :cognition-version])
                    :cba-version
                    (get-in state [:cognition :best-answer :cognition-version])}))

            (not (true? (get-in state [:cognition :best-answer :non-final?])))
            (conj (invariant-error
                   :FINAL_BEST_ANSWER
                   "the Current Best Answer must stay non-final"
                   {:non-final?
                    (get-in state [:cognition :best-answer :non-final?])}))

            (seq unknown)
            (conj (invariant-error
                   :LINKS_UNKNOWN_MISSION
                   "a link names a mission that is not in the Docket"
                   {:mission-ids (vec unknown)})))]
      (if (empty? errors)
        {:ok? true :errors []}
        {:ok? false :errors errors}))))
