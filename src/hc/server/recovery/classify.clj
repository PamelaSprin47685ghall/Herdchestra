(ns hc.server.recovery.classify
  "Pure classification for the six restart windows in root AGENTS.md §7.1.

  The windows, in the spec's order:

    1. claimed, not dispatched        :phase :ready
    2. dispatched, start unobserved   :phase :dispatching
    3. external start not observed    :phase :started-unobserved
    4. running, executor gone         :phase :running (stale generation)
    5. child terminal, parent pending :phase :report-pending
    6. parent accepted, child unsure  :phase :report-accepted

  The rules are deliberately conservative:

  - nothing is ever re-run blindly;
  - a possibly repeated side effect becomes :outcome-unknown unless the
    obligation is explicitly :safe-to-repeat?;
  - an unconfirmable :running obligation never stays :running forever;
  - a report is re-delivered until the parent confirms, and closing it is a
    separate, idempotent state change;
  - a `:retry-call` storage obligation is an owner-owned durable index.  It
    carries the session/call and budget, not the request; the Owner still
    owns the request, effects, timer, and terminal outcome.  Recovery keeps
    it in `:await-retry` so startup can start the owning session.  A legacy
    storage `:retry-wait` without that kind has no request or owner, so it is
    kept open and reported for explicit blocking/diagnosis.

  This namespace is pure.  The facade performs the external probe and report
  delivery and passes their results in `ctx`.")

(def terminal-probe-states
  #{:succeeded :failed :cancelled})

(defn- attempts-used [o]
  (or (:attempts-used o)
      (some-> (:retries-used o) (+ 1))))

(defn- max-attempts [o]
  (or (:max-attempts o)
      (some-> (:max-retries o) (+ 1))))

(defn- deadline [o]
  (or (:deadline o) (:deadline-at o)))

(defn retry-exhausted?
  "True when the finite retry budget is spent.

  The contract accepts either attempt accounting (`:attempts-used` /
  `:max-attempts`, both including the first attempt) or retry accounting
  (`:retries-used` / `:max-retries`, both excluding the first), plus an
  optional absolute `:deadline` (alias `:deadline-at`).  This guard closes
  claimed or dispatched call obligations whose budget is spent.  It is not a
  retry scheduler."
  [obligation now-ms]
  (or (and (integer? (attempts-used obligation))
           (integer? (max-attempts obligation))
           (>= (attempts-used obligation) (max-attempts obligation)))
      (and (number? (deadline obligation))
           (number? now-ms)
           (>= now-ms (deadline obligation)))))

(defn- result [action reason & {:as extra}]
  (merge {:action action :reason reason} extra))

(defn- unsupported-retry-decision
  "Keep a legacy storage retry open and report it.

  The storage obligation channel has no request, executor, or consumer for a
  retry.  A future producer must add that complete loop before it writes this
  phase.  Until then, recovery must not invent `:failed` or `:ready`: either
  would lose a responsibility that a real supervisor could still claim."
  [obligation now-ms]
  (let [retry-at (:retry-at obligation)]
    (result :inspect :storage-retry-unsupported
            :error :STORAGE_RETRY_UNSUPPORTED
            :retry-at retry-at
            :due? (and (number? retry-at)
                       (number? now-ms)
                       (<= retry-at now-ms)))))

(defn needs-probe?
  "True when recovery must ask the outside world before deciding."
  [obligation {:keys [current-generation]}]
  (let [phase (:phase obligation)]
    (case phase
      (:started-unobserved) true
      :running (not (and (some? current-generation)
                         (= current-generation (:executor-generation obligation))))
      false)))

(defn classify
  "Classify one obligation.

  `ctx`:
    :current-generation  this process's executor generation
    :probe-result        :succeeded | :failed | :cancelled | :running |
                         :not-started | :unknown
    :probe-error         string when the probe itself failed
    :delivery-result     :accepted | :rejected | :unknown
    :now-ms              clock used for retry deadlines

  Returns a decision map.  `:next-phase` and `:open?` are present only when
  the stored obligation must change."
  [obligation {:keys [current-generation probe-result probe-error
                      delivery-result now-ms] :as ctx}]
  (let [id    (:obligation/id obligation)
        phase (:phase obligation)
        probe (or probe-result (when probe-error :unknown))
        base  {:obligation-id id
               :phase phase
               :probe-result probe
               :probe-error probe-error}
        ;; A previous recovery version moved a due retry to :ready.  No
        ;; storage consumer claims such a record, so keep it open and report
        ;; it as blocked instead of rewriting its responsibility.  Started/
        ;; running attempts keep their probe-driven handling because an
        ;; external executor may have acted.
        legacy-retry? (and (= phase :ready)
                           (some? (:retry-at obligation)))
        retry-call? (and (= :retry-call (:obligation/kind obligation))
                         (contains? #{:ready :retry-wait} phase))]
    (cond
      (false? (:open? obligation))
      (merge base (result :none :already-closed))

      retry-call?
      (let [retry-at (:retry-at obligation)]
        (merge base (result :await-retry :retry-call-owned-by-owner
                            :retry-at retry-at
                            :due? (and (number? retry-at)
                                       (number? now-ms)
                                       (<= retry-at now-ms)))))

      legacy-retry?
      (merge base (unsupported-retry-decision obligation now-ms))

      (= phase :ready)
      (cond
        (retry-exhausted? obligation now-ms)
        (merge base (result :finish-failure :retry-budget-exhausted
                            :next-phase :failed :open? false
                            :failure {:kind :retry-budget-exhausted
                                      :attempts-used (attempts-used obligation)
                                      :max-attempts (max-attempts obligation)
                                      :retries-used (:retries-used obligation)
                                      :max-retries (:max-retries obligation)
                                      :deadline (deadline obligation)}))

        :else
        (merge base (result :dispatch :awaiting-claim)))

      (= phase :retry-wait)
      (merge base (unsupported-retry-decision obligation now-ms))

      (= phase :dispatching)
      (cond
        (retry-exhausted? obligation now-ms)
        (merge base (result :finish-failure :retry-budget-exhausted
                            :next-phase :failed :open? false
                            :failure {:kind :retry-budget-exhausted
                                      :attempts-used (attempts-used obligation)
                                      :max-attempts (max-attempts obligation)
                                      :retries-used (:retries-used obligation)
                                      :max-retries (:max-retries obligation)
                                      :deadline (deadline obligation)}))

        (:idempotent? obligation)
        (merge base (result :redispatch :idempotent-redispatch
                            :next-phase :ready :open? true))

        :else
        (merge base (result :interrupt :start-unobserved-not-idempotent
                            :next-phase :outcome-unknown :open? true)))

      (= phase :started-unobserved)
      (cond
        (contains? terminal-probe-states probe)
        (merge base (result :finished :external-terminal
                            :next-phase :closed :open? false))

        (and (= probe :not-started) (:safe-to-repeat? obligation))
        (merge base (result :redispatch :confirmed-not-started-safe-to-repeat
                            :next-phase :ready :open? true))

        :else
        (merge base (result :outcome-unknown :external-start-unconfirmed
                            :next-phase :outcome-unknown :open? true)))

      (= phase :running)
      (if (and (some? current-generation)
               (= current-generation (:executor-generation obligation)))
        (merge base (result :live :executor-still-current))
        (cond
          (contains? terminal-probe-states probe)
          (merge base (result :finished :external-terminal
                              :next-phase :closed :open? false))

          (and (= probe :not-started) (:safe-to-repeat? obligation))
          (merge base (result :redispatch :confirmed-not-started-safe-to-repeat
                              :next-phase :ready :open? true))

          :else
          (merge base (result :outcome-unknown :executor-gone-unconfirmed
                              :next-phase :outcome-unknown :open? true))))

      (#{:report-pending :report-accepted} phase)
      (if (= delivery-result :accepted)
        (merge base (result :close-report :parent-accepted
                            :next-phase :report-accepted :open? false))
        (merge base (result :redeliver :awaiting-parent-confirmation
                            :next-phase phase :open? true)))

      (= phase :outcome-unknown)
      (merge base (result :inspect :awaiting-operator-reconciliation))

      (#{:closed :done :failed :cancelled} phase)
      (cond-> (merge base (result :none :terminal))
        (true? (:open? obligation)) (assoc :open? false))

      :else
      (merge base (result :inspect :unknown-phase
                          :error :UNKNOWN_OBLIGATION_PHASE)))))
