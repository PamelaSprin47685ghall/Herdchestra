(ns hc.compose.guards.scheduler
  "Pure level-triggered guarded-command scheduler (AGENTS.md §5.2).

  The scheduler holds no state outside its immutable value.  It does not read
  clocks, start threads, write storage, or call a model.  A host owns the
  committed domain state and the atomic commit boundary.

  Host protocol
  -------------
  1. Call `wake` when committed state changes or an external message arrives.
     Repeated wakes coalesce into one pending notification.
  2. Call `step` with the committed domain state.  It returns at most one
     prepared micro-action.
  3. When `step` returns `:prepared`, atomically commit the transition
     `:state` and the returned scheduler value.
  4. Call `after-commit` with the transition id.  Only this call releases the
     dispatch effects.  Hand them to the execution layer.
  5. Call `ack-dispatch` after the execution layer accepts the effects.
  6. Call `finish-round` when the round drains, blocks, or reaches its quota.

  Claim-before-dispatch
  ---------------------
  A prepare function claims work in `:state` and records the attempt id at
  `:attempt-path`.  `after-commit` releases dispatch effects only after the
  host reports the commit.  Every effect carries that same attempt id.  A
  dispatch effect cannot exist without a claim, and a claim cannot exist
  without the matching state transition.

  Quota
  -----
  `:quota` bounds the attempted micro-actions in one round.  A rejected plan
  consumes quota too, so a broken guard cannot spin.  When the quota is
  spent and a guard is still enabled, `step` returns `:quota-exhausted` with
  `:continue? true` and sets a `:quota-requeue` wakeup.  The host must
  re-enqueue the scheduler.  The scheduler never sleeps while enabled work
  remains.

  Priority and fairness
  ---------------------
  The scheduler evaluates enabled guards by rank.  It evaluates P0 before P1,
  P1 before P2, and so on.  Inside one rank, a cursor rotates the order so
  that every guard gets a turn before an earlier guard repeats.

  Dead ends
  ---------
  When no guard is enabled and the caller reports an active tenure with no
  in-flight work and no timer, `step` returns an explicit `:blocked`
  diagnostic.  An absent context key does not assert a dead end."
  (:require [hc.compose.guards.claim :as claim]
            [hc.compose.guards.priority :as priority]))

(def default-quota
  "Default maximum number of attempted micro-actions in one round."
  32)

;; ---------------------------------------------------------------------------
;; Construction

(defn- invalid!
  [code message details]
  (throw (ex-info message (assoc details :code code))))

(defn- callable?
  "True for functions and other IFn values that are not data."
  [x]
  (and (ifn? x)
       (not (or (keyword? x) (map? x) (set? x) (vector? x)))))

(defn- supports-arity?
  "True when F declares an invoke method with N parameters."
  [f n]
  (boolean
   (some (fn [^java.lang.reflect.Method method]
           (and (= "invoke" (.getName method))
                (= n (count (.getParameterTypes method)))))
         (.getDeclaredMethods (class f)))))

(defn- variadic?
  [f]
  (instance? clojure.lang.RestFn f))

(defn- wrap-enabled
  "Normalize a one-argument enabled? function to the two-argument form."
  [f]
  (cond
    (supports-arity? f 2)
    f

    (and (not (variadic? f)) (supports-arity? f 1))
    (fn [state _] (f state))

    :else
    f))

(defn- wrap-prepare
  "Normalize a one-argument prepare function to the four-argument form."
  [f]
  (cond
    (supports-arity? f 4)
    f

    (and (not (variadic? f)) (supports-arity? f 1))
    (fn [state _ _ _] (f state))

    :else
    f))

(defn guard
  "Build one guard.

  ID is a keyword.  The three-argument form omits the priority; the catalog
  assigns it.  PRIORITY is a keyword or integer accepted by
  `hc.compose.guards.priority/rank`.

  ENABLED? is a pure function `(fn [state ctx])` or `(fn [state])`.  It
  returns nil or false when the guard is disabled, or a work description
  when it is enabled.  PREPARE is a pure function
  `(fn [state ctx work attempt-id])` or `(fn [state])` that returns a plan
  accepted by `hc.compose.guards.claim/validate-plan`.

  Neither function may start a thread, write storage, or dispatch work."
  ([id enabled? prepare]
   (guard id nil enabled? prepare))
  ([id priority enabled? prepare]
   (when-not (keyword? id)
     (invalid! :INVALID_GUARD "A guard id must be a keyword." {:id id}))
   (when-not (or (nil? priority) (priority/valid? priority))
     (invalid! :INVALID_GUARD "A guard priority must be P0 through P4."
               {:id id :priority priority}))
   (when-not (callable? enabled?)
     (invalid! :INVALID_GUARD "A guard needs an enabled? function."
               {:id id :enabled? enabled?}))
   (when-not (callable? prepare)
     (invalid! :INVALID_GUARD "A guard needs a prepare function."
               {:id id :prepare prepare}))
   (cond-> {:id id
            :enabled? (wrap-enabled enabled?)
            :prepare (wrap-prepare prepare)}
     (some? priority) (assoc :priority priority))))

(defn- normalize-guard
  [priority g]
  (when-not (map? g)
    (invalid! :INVALID_CATALOG "Every guard must be a map." {:guard g}))
  (when-not (keyword? (:id g))
    (invalid! :INVALID_CATALOG "A guard id must be a keyword." {:guard g}))
  (when-not (callable? (:enabled? g))
    (invalid! :INVALID_CATALOG "A guard needs an enabled? function."
              {:guard g}))
  (when-not (callable? (:prepare g))
    (invalid! :INVALID_CATALOG "A guard needs a prepare function."
              {:guard g}))
  (let [priority (or priority (:priority g) :p3-progress)]
    (when-not (priority/valid? priority)
      (invalid! :INVALID_CATALOG "A guard priority must be P0 through P4."
                {:guard g :priority priority}))
    (assoc g
           :priority priority
           :enabled? (wrap-enabled (:enabled? g))
           :prepare (wrap-prepare (:prepare g)))))

(defn- group-guards
  [priority value]
  (cond
    (map? value)
    [(normalize-guard priority value)]

    (sequential? value)
    (mapv #(normalize-guard priority %) value)

    :else
    (invalid! :INVALID_CATALOG
              "A priority group must contain a guard or a sequence of guards."
              {:priority priority :value value})))

(defn catalog
  "Validate GUARDS and return them as a vector.

  GUARDS is either a map of priority to guards or a flat vector.  The map
  form assigns the priority from the group:

    {:p0 [control-guard]
     :p1 [closure-guard]
     :p3 [progress-guard]}

  A flat vector guard may carry `:priority`.  A guard without one defaults
  to P3 progress.  Every guard needs a unique keyword `:id`, an `:enabled?`
  function, and a `:prepare` function.

  An empty catalog is valid: the scheduler reports a blocked assertion when
  the tenure is active and no other work exists."
  [guards]
  (let [guards (cond
                 (map? guards)
                 (vec (mapcat (fn [[priority group]]
                                (when-not (priority/valid? priority)
                                  (invalid! :INVALID_CATALOG
                                            "A priority group needs a P0 through P4 key."
                                            {:priority priority}))
                                (group-guards priority group))
                              guards))

                 (sequential? guards)
                 (mapv #(normalize-guard nil %) guards)

                 :else
                 (invalid! :INVALID_CATALOG
                           "A catalog must be a priority map or a guard vector."
                           {:guards guards}))
        ids (map :id guards)]
    (when-not (= (count ids) (count (distinct ids)))
      (invalid! :INVALID_CATALOG "Guard ids must be unique." {:ids ids}))
    guards))

(defn new-scheduler
  "Return a fresh scheduler value.

  Options:

    :quota             positive integer, default `default-quota`
    :attempt-prefix    prefix for generated attempt ids
    :transition-prefix prefix for generated transition ids"
  ([] (new-scheduler {}))
  ([{:keys [quota attempt-prefix transition-prefix]
     :or {quota default-quota
          attempt-prefix "attempt"
          transition-prefix "transition"}}]
   (when-not (and (integer? quota) (pos? quota))
     (throw (ex-info "Quota must be a positive integer."
                     {:code :INVALID_QUOTA :quota quota})))
   (doseq [[key value] [[:attempt-prefix attempt-prefix]
                        [:transition-prefix transition-prefix]]]
     (when-not (and (string? value) (pos? (count value)))
       (throw (ex-info "An id prefix must be a non-empty string."
                       {:code :INVALID_ID_PREFIX
                        :key key
                        :value value}))))
   {:quota (long quota)
    :used 0
    :round 0
    :cursor {}
    :attempt-seq 0
    :transition-seq 0
    :attempt-prefix attempt-prefix
    :transition-prefix transition-prefix
    :wakeup {:pending? false :reason nil :coalesced 0}
    :pending-dispatch nil
    :rejected-this-round #{}
    :last-commit nil
    :last-rejection nil}))

;; ---------------------------------------------------------------------------
;; Wakeups

(defn wake
  "Merge one wake notification into SCHEDULER.

  The first reason wins.  Later wakes while one is pending only increment
  `:coalesced`.  Wakeups are advisory: `step` always reads the committed
  state, so one evaluation covers every merged notification."
  [scheduler reason]
  (if (get-in scheduler [:wakeup :pending?])
    (update-in scheduler [:wakeup :coalesced] inc)
    (assoc scheduler :wakeup {:pending? true
                              :reason reason
                              :coalesced 0})))

(defn- consume-wakeup
  [scheduler]
  (assoc scheduler :wakeup {:pending? false
                            :reason nil
                            :coalesced 0}))

;; ---------------------------------------------------------------------------
;; Candidate selection

(defn- guard-by-id
  [catalog id]
  (some #(when (= id (:id %)) %) catalog))

(defn- candidate-of
  [g state ctx]
  (when-let [work ((:enabled? g) state ctx)]
    {:guard-id (:id g)
     :priority (:priority g)
     :rank (priority/rank (:priority g))
     :work work}))

(defn enabled-candidates
  "Return every enabled guard as a candidate map, in declaration order.

  A candidate is `{:guard-id :priority :rank :work}`.  The function calls
  only the guards' `:enabled?` functions and never calls `:prepare`."
  [catalog state ctx]
  (into [] (keep #(candidate-of % state ctx)) catalog))

(defn candidates
  "Return the enabled candidates of the highest enabled rank.

  Lower ranks are not evaluated while a higher rank has an enabled
  candidate.  Inside one rank, `:cursor` rotates the order after the guard
  that ran last.  Guards that already rejected a plan in this round are
  omitted."
  [catalog state ctx scheduler]
  (or (loop [groups (->> catalog
                         (sort-by #(priority/rank (:priority %)))
                         (partition-by #(priority/rank (:priority %))))]
        (when-let [group (first groups)]
          (let [enabled (into []
                              (keep #(candidate-of % state ctx))
                              (remove #(contains? (:rejected-this-round scheduler)
                                                  (:id %))
                                      group))]
            (if (seq enabled)
              (vec (priority/rotate-after
                    enabled
                    (get (:cursor scheduler) (:rank (first enabled)))))
              (recur (rest groups))))))
      []))

(defn next-candidate
  "Return the next candidate that `step` would prepare, or nil."
  [catalog state ctx scheduler]
  (first (candidates catalog state ctx scheduler)))

;; ---------------------------------------------------------------------------
;; Id allocation and preparation

(defn- next-id
  [scheduler key prefix-key]
  (let [n (inc (get scheduler key))]
    [(assoc scheduler key n)
     (keyword (str (get scheduler prefix-key) "-" n))]))

(defn- prepare-candidate
  [catalog state ctx scheduler candidate]
  (let [g (guard-by-id catalog (:guard-id candidate))
        [scheduler attempt-id] (next-id scheduler :attempt-seq :attempt-prefix)
        [scheduler transition-id] (next-id scheduler :transition-seq
                                           :transition-prefix)
        plan ((:prepare g) state ctx (:work candidate) attempt-id)
        checked (claim/validate-plan state plan attempt-id)]
    (if (:ok? checked)
      (let [scheduler' (-> scheduler
                           (update :used inc)
                           (assoc-in [:cursor (:rank candidate)] (:id g))
                           (assoc :pending-dispatch
                                  {:transition-id transition-id
                                   :attempt-id attempt-id
                                   :guard-id (:id g)
                                   :state (:state checked)
                                   :dispatch (:dispatch checked)
                                   :committed? false})
                           (assoc :last-commit nil
                                  :last-rejection nil)
                           (consume-wakeup))]
        {:status :prepared
         :transition {:transition-id transition-id
                      :guard-id (:id g)
                      :priority (:priority g)
                      :rank (:rank candidate)
                      :attempt-id attempt-id
                      :state (:state checked)
                      :claim (:claim checked)
                      :dispatch-count (count (:dispatch checked))}
         :scheduler scheduler'})
      (let [scheduler' (-> scheduler
                           (update :used inc)
                           (assoc-in [:cursor (:rank candidate)] (:id g))
                           (update :rejected-this-round conj (:id g))
                           (assoc :last-rejection (:error checked))
                           (consume-wakeup))]
        {:status :rejected
         :guard-id (:id g)
         :error (:error checked)
         :scheduler scheduler'}))))

;; ---------------------------------------------------------------------------
;; Dead-end detection

(defn- active-dead-end?
  [ctx]
  (and (false? (:tenure-terminal? ctx))
       (not (true? (:inflight? ctx)))
       (not (true? (:timed-wait? ctx)))))

(defn- dead-end
  [scheduler ctx]
  (if (active-dead-end? ctx)
    {:status :blocked
     :diagnostic {:code :BLOCKED
                  :reason (if (seq (:rejected-this-round scheduler))
                            :all-guards-rejected
                            :no-enabled-guard)
                  :priority :p4-blocked
                  :rank priority/p4-blocked
                  :round (:round scheduler)
                  :used (:used scheduler)
                  :rejected-guards (vec (sort (:rejected-this-round scheduler)))
                  :message (str "No guard is enabled, no work is in flight, "
                                "and no timer is pending.")}
     :scheduler (consume-wakeup scheduler)}
    {:status (if (pos? (:used scheduler)) :drained :idle)
     :scheduler (consume-wakeup scheduler)}))

;; ---------------------------------------------------------------------------
;; Step and dispatch handshake

(defn- pending-status
  [scheduler]
  (when-let [pending (:pending-dispatch scheduler)]
    (if (:committed? pending)
      {:status :awaiting-dispatch
       :transition-id (:transition-id pending)
       :scheduler scheduler}
      {:status :awaiting-commit
       :transition-id (:transition-id pending)
       :scheduler scheduler})))

(defn step
  "Select and prepare at most one micro-action.

  STATE is the committed domain state.  CTX is read-only context for the
  guards, for example `:now`, `:tenure-terminal?`, `:inflight?`, and
  `:timed-wait?`.  The scheduler never calls a clock.

  Return one of:

    {:status :prepared :transition t :scheduler s'}
    {:status :rejected :guard-id id :error e :scheduler s'}
    {:status :quota-exhausted :continue? true :scheduler s'}
    {:status :drained :scheduler s'}
    {:status :idle :scheduler s'}
    {:status :blocked :diagnostic d :scheduler s'}
    {:status :awaiting-commit :transition-id id :scheduler s}
    {:status :awaiting-dispatch :transition-id id :scheduler s}

  A prepared transition contains `:state` and `:claim`, but never `:dispatch`.
  The host must commit `:state` first, then call `after-commit`."
  [catalog state ctx scheduler]
  (or (pending-status scheduler)
      (let [cs (candidates catalog state ctx scheduler)]
        (cond
          (>= (:used scheduler) (:quota scheduler))
          (if (seq cs)
            {:status :quota-exhausted
             :continue? true
             :scheduler (assoc scheduler
                               :wakeup {:pending? true
                                        :reason :quota-requeue
                                        :coalesced 0})}
            (dead-end scheduler ctx))

          (seq cs)
          (prepare-candidate catalog state ctx scheduler (first cs))

          :else
          (dead-end scheduler ctx)))))

(defn after-commit
  "Release the dispatch effects for TRANSITION-ID.

  The host MUST atomically commit the transition `:state` before this call.
  This function cannot observe storage.  The order is explicit in the API:
  `step` withholds the effects, and only this call returns them, after the
  host reports the commit.

  Repeated calls before `ack-dispatch` return the same effects.  The host
  must make dispatch idempotent by `:attempt-id`."
  [scheduler transition-id]
  (let [pending (:pending-dispatch scheduler)]
    (cond
      (nil? pending)
      {:status :unknown-transition :dispatch [] :scheduler scheduler}

      (not= transition-id (:transition-id pending))
      {:status :unknown-transition :dispatch [] :scheduler scheduler}

      (:committed? pending)
      {:status :dispatch
       :transition-id transition-id
       :dispatch (:dispatch pending)
       :replayed? true
       :scheduler scheduler}

      :else
      {:status :dispatch
       :transition-id transition-id
       :dispatch (:dispatch pending)
       :replayed? false
       :scheduler (assoc-in scheduler
                            [:pending-dispatch :committed?] true)})))

(defn ack-dispatch
  "Clear the pending dispatch for TRANSITION-ID.

  The host calls this after the execution layer accepts the effects.  A
  transition that is not committed cannot be acknowledged."
  [scheduler transition-id]
  (let [pending (:pending-dispatch scheduler)]
    (cond
      (nil? pending)
      {:status :unknown-transition :scheduler scheduler}

      (not= transition-id (:transition-id pending))
      {:status :unknown-transition :scheduler scheduler}

      (not (:committed? pending))
      {:status :not-committed :scheduler scheduler}

      :else
      {:status :acked
       :scheduler (assoc scheduler
                         :pending-dispatch nil
                         :last-commit transition-id)})))

(defn abandon-pending!
  "Drop one uncommitted or unacknowledged dispatch handshake.

  This is the explicit recovery path when the host cannot finish the
  commit/after-commit/ack sequence.  The guard is level-triggered, so the
  next round re-prepares the same work from committed state.  The host must
  not call this after a successful dispatch; use `ack-dispatch` then."
  [scheduler]
  (-> scheduler
      (assoc :pending-dispatch nil
             :used 0
             :rejected-this-round #{}
             :last-rejection nil)
      (update :round inc)))

(defn quota-remaining
  "Return the number of micro-actions still allowed in this round."
  [scheduler]
  (max 0 (- (:quota scheduler) (:used scheduler))))

(defn finish-round
  "Start the next quota round.

  The host calls this after `:drained`, `:idle`, `:blocked`, or
  `:quota-exhausted`.  A pending commit or dispatch blocks the round: the
  host must finish that handshake first."
  [scheduler]
  (when (:pending-dispatch scheduler)
    (throw (ex-info "Cannot finish a round with a pending dispatch."
                    {:code :ROUND_HAS_PENDING_DISPATCH
                     :transition-id (get-in scheduler
                                            [:pending-dispatch :transition-id])})))
  (-> scheduler
      (assoc :used 0
             :rejected-this-round #{}
             :last-rejection nil)
      (update :round inc)))
