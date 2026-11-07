(ns hc.compose.guards.claim
  "Claim-before-dispatch validation for the guarded-command scheduler.

  A prepare function returns a pure plan:

    {:state state'
     :claim {:path [:calls \"c1\" :state]
             :work-id \"c1\"
             :attempt-path [:calls \"c1\" :attempt-id]
             :from :ready
             :to :dispatching}
     :dispatch [{:kind :model :payload {...}}]}

  The scheduler assigns the attempt id and passes it to prepare.  This
  namespace checks that state' already records that claim before any dispatch
  effect becomes visible.  Dispatch effects are normalized to carry the same
  attempt id.

  A state-only micro-action returns a plan without `:claim` and with an empty
  `:dispatch` vector.  A plan that changes neither state nor dispatch is
  rejected: an enabled guard must make progress or become false.")

(def ready-states
  "Work states that a claim may consume."
  #{:ready :pending})

(def claimed-states
  "Work states that a claim may produce before dispatch."
  #{:claimed :dispatching})

(def ^:private missing ::missing)

(defn- error
  [code message details]
  {:ok? false
   :error (cond-> {:code code :message message}
            (some? details) (assoc :details details))})

(defn- path?
  [x]
  (vector? x))

(defn- state-at
  [state path]
  (if (path? path)
    (get-in state path missing)
    missing))

(defn- attach-attempt
  "Attach ATTEMPT-ID to a plan that does not record it yet.

  A one-argument prepare function cannot know the scheduler's attempt id.
  The scheduler injects the id into the claim, the claim's attempt path, and
  every dispatch effect that lacks one.  An existing different id stays in
  place so validation can reject it."
  [plan attempt-id]
  (let [claim (:claim plan)]
    (if-not (map? claim)
      plan
      (let [attempt-path (:attempt-path claim)
            state (:state plan)
            current (state-at state attempt-path)
            state' (if (and (path? attempt-path)
                            (or (= missing current) (nil? current)))
                     (assoc-in state attempt-path attempt-id)
                     state)
            dispatch (:dispatch plan)
            dispatch' (if (sequential? dispatch)
                        (mapv (fn [effect]
                                (if (and (map? effect)
                                         (not (contains? effect :attempt-id)))
                                  (assoc effect :attempt-id attempt-id)
                                  effect))
                              dispatch)
                        dispatch)]
        (-> plan
            (assoc :state state')
            (assoc :claim (assoc claim :attempt-id attempt-id))
            (assoc :dispatch dispatch'))))))

(defn- validate-dispatch
  [dispatch attempt-id claim]
  (reduce
   (fn [result [index effect]]
     (if-not (:ok? result)
       (reduced result)
       (cond
         (not (map? effect))
         (error :DISPATCH_EFFECT_INVALID
                "Each dispatch effect must be a map."
                {:index index :effect effect})

         (nil? (:kind effect))
         (error :DISPATCH_KIND_MISSING
                "Each dispatch effect must name its kind."
                {:index index :effect effect})

         (and (contains? effect :attempt-id)
              (not= attempt-id (:attempt-id effect)))
         (error :DISPATCH_ATTEMPT_MISMATCH
                "A dispatch effect carries an attempt id that differs from the claim."
                {:index index
                 :expected attempt-id
                 :actual (:attempt-id effect)})

         :else
         (update result :dispatch conj (assoc effect :attempt-id attempt-id)))))
   {:ok? true
    :claim claim
    :dispatch []}
   (map-indexed vector dispatch)))

(defn- validate-claim
  [state new-state claim dispatch attempt-id]
  (let [path (:path claim)
        attempt-path (:attempt-path claim)
        from (:from claim)
        to (:to claim)
        work-id (:work-id claim)]
    (cond
      (not (map? claim))
      (error :CLAIM_NOT_MAP "A claim must be a map." {:claim claim})

      (not (path? path))
      (error :CLAIM_PATH_INVALID
             "A claim needs a vector :path to the work state."
             {:claim claim})

      (nil? work-id)
      (error :CLAIM_WORK_ID_MISSING
             "A claim needs a :work-id."
             {:claim claim})

      (not (contains? ready-states from))
      (error :CLAIM_FROM_INVALID
             "A claim must start from :ready or :pending."
             {:claim claim :from from})

      (not (contains? claimed-states to))
      (error :CLAIM_TO_INVALID
             "A claim must end at :claimed or :dispatching."
             {:claim claim :to to})

      (not (path? attempt-path))
      (error :CLAIM_ATTEMPT_PATH_INVALID
             "A claim needs a vector :attempt-path."
             {:claim claim})

      (not= from (state-at state path))
      (error :CLAIM_SOURCE_MISMATCH
             "The work state does not match the claim's :from state."
             {:claim claim
              :expected from
              :actual (state-at state path)})

      (not= to (state-at new-state path))
      (error :CLAIM_TARGET_MISMATCH
             "The prepared state does not contain the claim's :to state."
             {:claim claim
              :expected to
              :actual (state-at new-state path)})

      (not= attempt-id (state-at new-state attempt-path))
      (error :CLAIM_ATTEMPT_NOT_RECORDED
             "The prepared state does not record the assigned attempt id."
             {:claim claim
              :expected attempt-id
              :actual (state-at new-state attempt-path)})

      :else
      (validate-dispatch dispatch
                         attempt-id
                         (assoc claim :attempt-id attempt-id)))))

(defn validate-plan
  "Validate and normalize a prepare plan.

  STATE is the committed state that the guard observed.  ATTEMPT-ID is the
  fresh id that the scheduler assigned.  Return

    {:ok? true  :state state' :claim claim-or-nil :dispatch effects}
    {:ok? false :error {:code ... :message ... :details ...}}

  `:state` is the normalized prepared state.  Normalization adds ATTEMPT-ID
  to the claim and to every dispatch effect.  When a prepare function cannot
  receive the attempt id, normalization also writes it to `:attempt-path` in
  state'.  Validation rejects a plan unless state' records the claim at
  `:path` and the attempt id at `:attempt-path` before dispatch."
  [state plan attempt-id]
  (cond
    (not (map? plan))
    (error :PLAN_NOT_MAP "A prepare function must return a map." {:plan plan})

    (not (map? (:state plan)))
    (error :PLAN_STATE_MISSING
           "A prepare plan must contain a :state map."
           {:plan plan})

    (not (sequential? (:dispatch plan [])))
    (error :DISPATCH_NOT_SEQUENTIAL
           "A prepare plan's :dispatch must be sequential."
           {:dispatch (:dispatch plan)})

    (nil? attempt-id)
    (error :ATTEMPT_ID_MISSING
           "The scheduler must assign an attempt id before prepare."
           {})

    :else
    (let [normalized (attach-attempt plan attempt-id)
          new-state (:state normalized)
          dispatch (vec (:dispatch normalized []))
          claim (:claim normalized)]
      (cond
        (and (= state new-state) (empty? dispatch))
        (error :NO_PROGRESS
               "A prepare plan must change state or dispatch work."
               {})

        (and (nil? claim) (seq dispatch))
        (error :DISPATCH_WITHOUT_CLAIM
               "A dispatch effect requires a claim."
               {:dispatch dispatch})

        (nil? claim)
        {:ok? true :state new-state :claim nil :dispatch dispatch}

        :else
        (let [checked (validate-claim state new-state claim dispatch
                                      attempt-id)]
          (if (:ok? checked)
            (assoc checked :state new-state)
            checked))))))
