(ns hc.compose.retry.planner
  "Pure bounded retry planner for upstream faults (AGENTS.md §5.2, §5.4).

  The planner owns decisions and state patches only.  It never dispatches,
  sleeps, reads a clock, or touches the network.  The Owner calls it from
  the P2 guard, commits the returned state, and only then dispatches the
  claimed attempt.

  Two budgets bound every retry: `:max-attempts` (includes the first
  attempt) and `:max-retries` (excludes it), plus an absolute call-wide
  `:deadline-at`.  A plan fixes `:retry-at` once; a duplicate failure
  signal cannot move it.  `:deadline-at` is fixed when the call starts and
  no retry extends it.

  Side effects are first-class.  A missing `:effects` map is treated as
  unknown, never as harmless.  Pending effects must be coordinated before
  any retry.  Unknown effects block automatic retry unless the operation
  is declared `:repeat-safe?` and the policy explicitly opts in with
  `:retry-unknown-effects?`."
  (:require [clojure.string :as str]
            [hc.compose.retry.error :as error]
            [hc.compose.retry.policy :as policy]))

(def call-phases
  #{:ready :dispatching :running :retry-wait :succeeded :failed :cancelled
    :outcome-unknown})

(def plan-phases
  #{:dispatching :running})

(def terminal-phases
  #{:succeeded :failed :outcome-unknown})

(def effect-statuses
  #{:none :settled :pending :unknown})

(defn- invalid
  [code message details]
  {:ok? false
   :error {:code code
           :message message
           :details details}})

(defn- non-blank-string?
  [x]
  (and (string? x) (not (str/blank? x))))

(defn- non-negative-integer?
  [x]
  (and (integer? x)
       (<= 0 x Long/MAX_VALUE)))

(defn- state-max-attempts
  [state policy]
  (or (:max-attempts state) (:max-attempts policy)))

(defn- state-max-retries
  [state policy]
  (or (:max-retries state) (:max-retries policy)))

(defn- state-deadline
  "The call-wide absolute deadline.  `:deadline` is the persisted
  recovery-facing name; `:deadline-at` is kept as a compatibility alias."
  [state policy]
  (or (:deadline-at state)
      (:deadline state)
      (when (and (non-negative-integer? (:started-at state))
                 (non-negative-integer? (:deadline-ms policy)))
        (+' (:started-at state) (:deadline-ms policy)))))

(defn- normalize-effects
  [state]
  (let [effects (:effects state)]
    (if (map? effects)
      {:status (if (contains? effect-statuses (:status effects))
                 (:status effects)
                 :unknown)
       :repeat-safe? (true? (:repeat-safe? effects))}
      {:status :unknown
       :repeat-safe? false})))

(defn- state-problem
  "Return `[problem-code message]` for an unusable call record, else nil."
  [state]
  (if-not (map? state)
    [:state-not-map "state must be a map"]
    (let [phase (:phase state)
          attempts (:attempts-used state)
          retries (:retries-used state)]
      (cond
        (not (non-blank-string? (:call-id state)))
        [:bad-call-id ":call-id must be a non-blank string"]

        (not (non-blank-string? (:attempt-id state)))
        [:bad-attempt-id ":attempt-id must be a non-blank string"]

        (not (contains? call-phases phase))
        [:bad-phase ":phase must be a known call phase"]

        (not (non-negative-integer? attempts))
        [:bad-attempts-used
         ":attempts-used must be a non-negative integer"]

        (not (non-negative-integer? retries))
        [:bad-retries-used ":retries-used must be a non-negative integer"]

        (and (= :ready phase) (not (zero? attempts)))
        [:ready-with-attempts
         "a :ready call must not have started an attempt"]

        (and (not= :ready phase) (zero? attempts))
        [:started-without-attempt
         "a started call must have at least one attempt"]

        (or (> retries attempts)
            (and (pos? attempts) (= retries attempts)))
        [:bad-retry-count
         ":retries-used must be less than :attempts-used for a started call"]

        (and (contains? state :max-attempts)
             (not (non-negative-integer? (:max-attempts state))))
        [:bad-max-attempts ":max-attempts must be a non-negative integer"]

        (and (contains? state :max-retries)
             (not (non-negative-integer? (:max-retries state))))
        [:bad-max-retries ":max-retries must be a non-negative integer"]

        (and (contains? state :deadline)
             (not (non-negative-integer? (:deadline state))))
        [:bad-deadline ":deadline must be a non-negative integer"]

        (not (or (non-negative-integer? (:deadline-at state))
                 (non-negative-integer? (:deadline state))))
        [:bad-deadline
         "one of :deadline or :deadline-at must be a non-negative integer"]

        (and (non-negative-integer? (:deadline-at state))
             (non-negative-integer? (:deadline state))
             (not= (:deadline-at state) (:deadline state)))
        [:deadline-mismatch ":deadline and :deadline-at must agree"]

        (not (or (nil? (:retry-at state))
                 (non-negative-integer? (:retry-at state))))
        [:bad-retry-at ":retry-at must be nil or a non-negative integer"]

        (and (contains? state :cancelled?)
             (not (or (nil? (:cancelled? state))
                      (boolean? (:cancelled? state)))))
        [:bad-cancelled-flag ":cancelled? must be a boolean when present"]

        :else
        nil))))

(defn- failure-problem
  "Return `[problem-code message]` for a missing or unusable failure, else nil."
  [failure]
  (cond
    (not (map? failure))
    [:failure-not-map ":failure must be an upstream failure map"]

    (empty? failure)
    [:failure-empty ":failure must not be empty"]

    :else
    nil))

(defn- prepare
  [state policy now jitter-offset-ms]
  (let [policy-result (policy/normalize-policy (or policy {}))]
    (cond
      (not (:ok? policy-result))
      policy-result

      (not (non-negative-integer? now))
      (invalid :INVALID_NOW "now must be a non-negative integer (epoch ms)"
               {:now now})

      (and (some? jitter-offset-ms)
           (not (non-negative-integer? jitter-offset-ms)))
      (invalid :INVALID_JITTER
               "jitter offset must be a non-negative integer (ms)"
               {:jitter-offset-ms jitter-offset-ms})

      :else
      (if-let [[problem message] (state-problem state)]
        (invalid :INVALID_STATE message {:problem problem})
        {:ok? true
         :policy (:policy policy-result)}))))

(defn init-call
  "Create a call record at the moment its first attempt is claimed.

  The absolute deadline is fixed here from `:now` and `:deadline-ms`.
  `:effects` starts as `:none` because no external action has happened
  yet.  The host must replace it after an attempt reports a failure."
  [{:keys [call-id attempt-id now policy]}]
  (let [policy-result (policy/normalize-policy (or policy {}))]
    (cond
      (not (non-blank-string? call-id))
      (invalid :INVALID_STATE ":call-id must be a non-blank string"
               {:call-id call-id})

      (not (non-blank-string? attempt-id))
      (invalid :INVALID_STATE ":attempt-id must be a non-blank string"
               {:attempt-id attempt-id})

      (not (non-negative-integer? now))
      (invalid :INVALID_NOW "now must be a non-negative integer (epoch ms)"
               {:now now})

      (not (:ok? policy-result))
      policy-result

      :else
      (let [p (:policy policy-result)
            deadline (+' now (:deadline-ms p))]
        (if (> deadline Long/MAX_VALUE)
          (invalid :DEADLINE_OVERFLOW
                   "the call deadline exceeds the representable epoch range"
                   {:now now :deadline-ms (:deadline-ms p)})
          {:ok? true
           :policy p
           :state {:call-id call-id
                   :attempt-id attempt-id
                   :phase :dispatching
                   :attempts-used 1
                   :retries-used 0
                   :max-attempts (:max-attempts p)
                   :max-retries (:max-retries p)
                   :started-at now
                   :deadline deadline
                   :deadline-at deadline
                   :retry-at nil
                   :failure nil
                   :last-error nil
                   :decision-reason nil
                   :effects {:status :none
                             :repeat-safe? true}}})))))

(defn- decision-error
  [code message details]
  {:decision :error
   :error {:code code
           :message message
           :details details}})

(defn- invalid-candidate
  [error]
  {:enabled? false
   :decision :error
   :reason :invalid-input
   :error error})

(defn- plan-decision*
  [p state failure now jitter-offset-ms]
  (let [classification (error/classify failure)
        effects (normalize-effects state)
        cancelled? (or (true? (:cancelled? state))
                       (:cancelled? classification))]
    (cond
      (contains? terminal-phases (:phase state))
      (decision-error :CALL_TERMINAL
                      "the call already has a fixed terminal phase"
                      {:phase (:phase state)})

      (= :cancelled (:phase state))
      {:decision :cancelled
       :reason :user-cancel
       :remedy :none
       :classification classification}

      cancelled?
      (case (:status effects)
        :pending
        {:decision :outcome-unknown
         :reason :cancelled-with-pending-effects
         :remedy :reconcile
         :classification classification
         :effects effects}

        :unknown
        {:decision :outcome-unknown
         :reason :cancelled-with-unknown-effects
         :remedy :reconcile
         :classification classification
         :effects effects}

        (if (= :reconcile (:category classification))
          {:decision :outcome-unknown
           :reason :cancelled-with-published-effects
           :remedy :reconcile
           :classification classification
           :effects effects}
          {:decision :cancelled
           :reason :user-cancel
           :remedy :none
           :classification classification}))

      (= :ready (:phase state))
      (decision-error :CALL_NOT_STARTED
                      "the call has not started; there is no failure to plan"
                      {:phase (:phase state)})

      (or (= :retry-wait (:phase state))
          (some? (:retry-at state)))
      (decision-error :RETRY_ALREADY_PLANNED
                      "a retry is already planned; :retry-at is fixed"
                      {:retry-at (:retry-at state)})

      (not (contains? plan-phases (:phase state)))
      (decision-error :CALL_TERMINAL
                      "the call phase cannot accept a retry plan"
                      {:phase (:phase state)})

      (= :pending (:status effects))
      {:decision :outcome-unknown
       :reason :side-effects-pending
       :remedy :reconcile
       :classification classification
       :effects effects}

      (and (= :unknown (:status effects))
           (not (and (:repeat-safe? effects)
                     (:retry-unknown-effects? p))))
      {:decision :outcome-unknown
       :reason :side-effects-unknown
       :remedy :reconcile
       :classification classification
       :effects effects}

      (= :reconcile (:category classification))
      {:decision :outcome-unknown
       :reason :error-outcome-unknown
       :remedy :reconcile
       :classification classification
       :effects effects}

      (contains? #{:permanent :context :model-correction :unknown}
                 (:category classification))
      {:decision :failed
       :reason (if (= :unknown (:category classification))
                 :unknown-error
                 :non-retryable-error)
       :remedy (if (= :unknown (:category classification))
                 :none
                 (:remedy classification))
       :classification classification
       :effects effects}

      (>= (:attempts-used state) (state-max-attempts state p))
      {:decision :failed
       :reason :attempts-exhausted
       :remedy :none
       :classification classification
       :effects effects}

      (>= (:retries-used state) (state-max-retries state p))
      {:decision :failed
       :reason :retries-exhausted
       :remedy :none
       :classification classification
       :effects effects}

      (>= now (state-deadline state p))
      {:decision :failed
       :reason :deadline-exceeded
       :remedy :none
       :classification classification
       :effects effects}

      :else
      (let [retry-number (inc (:retries-used state))
            jitter (if (some? jitter-offset-ms)
                     jitter-offset-ms
                     (policy/deterministic-jitter
                      (:jitter-ms p)
                      [(:call-id state) (:attempt-id state) retry-number]))
            delay (policy/delay-ms p retry-number jitter)
            retry-at (+' now delay)
            retry {:attempt-number (inc (:attempts-used state))
                   :retry-number retry-number
                   :delay-ms delay
                   :retry-at retry-at}]
        (if (>= retry-at (state-deadline state p))
          {:decision :failed
           :reason :deadline-before-retry
           :remedy :none
           :classification classification
           :effects effects
           :retry retry}
          {:decision :retry-wait
           :reason :transient-error
           :remedy :retry
           :classification classification
           :effects effects
           :retry retry})))))

(defn- failure-record
  [decision failure]
  (case (:decision decision)
    :failed
    {:kind (if (contains? #{:attempts-exhausted :retries-exhausted
                            :deadline-exceeded :deadline-before-retry}
                          (:reason decision))
             :retry-budget-exhausted
             (or (:kind failure) :retry-failed))
     :reason (:reason decision)
     :error failure}

    :outcome-unknown
    {:kind (or (:kind failure) :outcome-unknown)
     :reason (:reason decision)
     :error failure}

    :cancelled
    {:kind :cancelled
     :reason :user-cancel
     :error failure}

    nil))

(defn- apply-plan-decision
  [state decision policy]
  (let [deadline (state-deadline state policy)
        base (assoc state
                    :retry-at nil
                    :failure nil
                    :decision-reason nil
                    :max-attempts (state-max-attempts state policy)
                    :max-retries (state-max-retries state policy)
                    :deadline deadline
                    :deadline-at deadline)]
    (case (:decision decision)
      :retry-wait
      (assoc base
             :phase :retry-wait
             :retry-at (get-in decision [:retry :retry-at])
             :decision-reason (:reason decision))

      :failed
      (assoc base
             :phase :failed
             :decision-reason (:reason decision))

      :outcome-unknown
      (assoc base
             :phase :outcome-unknown
             :decision-reason (:reason decision))

      :cancelled
      (assoc base
             :phase :cancelled
             :decision-reason :user-cancel)

      base)))

(defn plan-candidate
  "Pure P2 guard input.  `:enabled?` is true only when `plan-retry` would
  schedule a bounded retry.  All other outcomes are reported with a
  `:reason` so the Owner can close or reconcile the call."
  [{:keys [state policy failure now jitter-offset-ms]}]
  (let [prepared (prepare state policy now jitter-offset-ms)]
    (if-not (:ok? prepared)
      (invalid-candidate (:error prepared))
      (if-let [[problem message] (failure-problem failure)]
        (invalid-candidate
         (:error (invalid :INVALID_FAILURE message
                          {:problem problem :failure failure})))
        (let [decision (plan-decision* (:policy prepared) state failure now
                                       jitter-offset-ms)]
          (cond-> {:enabled? (= :retry-wait (:decision decision))
                   :decision (:decision decision)
                   :reason (or (:reason decision)
                               (get-in decision [:error :code]))
                   :remedy (:remedy decision)
                   :classification (:classification decision)}
            (:retry decision) (assoc :retry (:retry decision))
            (:effects decision) (assoc :effects (:effects decision))
            (:error decision) (assoc :error (:error decision))))))))

(defn plan-retry
  "Plan a retry after an upstream failure, or fix a terminal decision.

  Returns `{:ok? true :decision ... :state ...}` for `:retry-wait`,
  `:failed`, `:outcome-unknown`, and `:cancelled`.  A scheduled retry
  leaves `:attempts-used` and `:retries-used` unchanged: the attempt is
  counted when `claim-due` accepts it.

  Returns `{:ok? false :error ...}` for invalid input or a call that
  already has a fixed plan or terminal phase."
  [{:keys [state policy failure now jitter-offset-ms]}]
  (let [prepared (prepare state policy now jitter-offset-ms)]
    (if-not (:ok? prepared)
      prepared
      (if-let [[problem message] (failure-problem failure)]
        (invalid :INVALID_FAILURE message
                 {:problem problem :failure failure})
        (let [decision (plan-decision* (:policy prepared) state failure now
                                       jitter-offset-ms)]
          (if (= :error (:decision decision))
            {:ok? false
             :error (:error decision)}
            (cond-> {:ok? true
                     :decision (:decision decision)
                     :reason (:reason decision)
                     :remedy (:remedy decision)
                     :classification (:classification decision)
                     :state (-> (apply-plan-decision
                                 state decision (:policy prepared))
                                (assoc :last-error failure
                                       :failure (failure-record decision failure)))}
              (:retry decision) (assoc :retry (:retry decision))
              (:effects decision) (assoc :effects (:effects decision)))))))))

(defn- claim-decision*
  [p state now]
  (let [effects (normalize-effects state)
        cancelled? (true? (:cancelled? state))]
    (cond
      (contains? terminal-phases (:phase state))
      (decision-error :CALL_TERMINAL
                      "the call already has a fixed terminal phase"
                      {:phase (:phase state)})

      (= :cancelled (:phase state))
      {:decision :cancelled
       :reason :user-cancel}

      cancelled?
      (case (:status effects)
        :pending
        {:decision :outcome-unknown
         :reason :cancelled-with-pending-effects
         :effects effects}

        :unknown
        {:decision :outcome-unknown
         :reason :cancelled-with-unknown-effects
         :effects effects}

        {:decision :cancelled
         :reason :user-cancel})

      (not= :retry-wait (:phase state))
      (decision-error :NO_RETRY_PLANNED
                      "the call is not waiting for a retry"
                      {:phase (:phase state)})

      (nil? (:retry-at state))
      (decision-error :NO_RETRY_PLANNED
                      "the call is in :retry-wait without a :retry-at"
                      {:phase (:phase state)})

      (= :pending (:status effects))
      {:decision :outcome-unknown
       :reason :side-effects-pending
       :effects effects}

      (and (= :unknown (:status effects))
           (not (and (:repeat-safe? effects)
                     (:retry-unknown-effects? p))))
      {:decision :outcome-unknown
       :reason :side-effects-unknown
       :effects effects}

      (>= (:attempts-used state) (state-max-attempts state p))
      {:decision :failed
       :reason :attempts-exhausted}

      (>= (:retries-used state) (state-max-retries state p))
      {:decision :failed
       :reason :retries-exhausted}

      (>= now (state-deadline state p))
      {:decision :failed
       :reason :deadline-exceeded}

      (< now (:retry-at state))
      (decision-error :RETRY_NOT_DUE
                      "the fixed retry time has not arrived"
                      {:retry-at (:retry-at state)
                       :now now})

      :else
      {:decision :claimed
       :reason :retry-due
       :attempt {:attempt-number (inc (:attempts-used state))
                 :retry-number (inc (:retries-used state))
                 :previous-attempt-id (:attempt-id state)}})))

(defn claim-candidate
  "Pure P2 guard input for the due-retry micro-action.

  `:enabled?` is true only when the fixed retry time has arrived, the
  prior effects are safe, both budgets remain, and the call-wide deadline
  has not passed."
  [{:keys [state policy now]}]
  (let [prepared (prepare state policy now nil)]
    (if-not (:ok? prepared)
      {:enabled? false
       :decision :error
       :reason :invalid-input
       :error (:error prepared)}
      (let [decision (claim-decision* (:policy prepared) state now)]
        (cond-> {:enabled? (= :claimed (:decision decision))
                 :decision (:decision decision)
                 :reason (or (:reason decision)
                             (get-in decision [:error :code]))}
          (:attempt decision) (assoc :attempt (:attempt decision))
          (:effects decision) (assoc :effects (:effects decision))
          (:error decision) (assoc :error (:error decision)))))))

(defn claim-due
  "Claim a due retry with a fresh `:attempt-id`.

  The caller supplies the unique attempt identity because this namespace
  is pure.  On `:claimed`, both attempt counters increment exactly once,
  `:retry-at` clears, and the call-wide deadline is preserved.  Terminal
  conditions discovered while waiting are returned as `:failed`,
  `:outcome-unknown`, or `:cancelled` so the Owner can close the call in
  the same commit."
  [{:keys [state policy now attempt-id]}]
  (let [prepared (prepare state policy now nil)]
    (if-not (:ok? prepared)
      prepared
      (let [decision (claim-decision* (:policy prepared) state now)]
        (case (:decision decision)
          :error
          {:ok? false
           :error (:error decision)}

          :claimed
          (cond
            (not (non-blank-string? attempt-id))
            (invalid :MISSING_ATTEMPT_ID
                     "a fresh non-blank :attempt-id is required"
                     {:attempt-id attempt-id})

            (= attempt-id (:attempt-id state))
            (invalid :DUPLICATE_ATTEMPT_ID
                     "the retry must use a new :attempt-id"
                     {:attempt-id attempt-id})

            :else
            {:ok? true
             :decision :claimed
             :reason :retry-due
             :attempt (assoc (:attempt decision) :attempt-id attempt-id)
             :state (-> state
                        (assoc :phase :dispatching
                               :attempt-id attempt-id
                               :attempts-used (inc (:attempts-used state))
                               :retries-used (inc (:retries-used state))
                               :retry-at nil
                               :failure nil
                               :effects {:status :none
                                         :repeat-safe? true}
                               :decision-reason nil
                               :max-attempts (state-max-attempts
                                              state (:policy prepared))
                               :max-retries (state-max-retries
                                             state (:policy prepared))
                               :deadline (state-deadline
                                          state (:policy prepared))
                               :deadline-at (state-deadline
                                             state (:policy prepared))))})

          :failed
          {:ok? true
           :decision :failed
           :reason (:reason decision)
           :state (assoc state
                         :phase :failed
                         :retry-at nil
                         :failure {:kind :retry-budget-exhausted
                                   :reason (:reason decision)}
                         :decision-reason (:reason decision))}

          :outcome-unknown
          {:ok? true
           :decision :outcome-unknown
           :reason (:reason decision)
           :effects (:effects decision)
           :state (assoc state
                         :phase :outcome-unknown
                         :retry-at nil
                         :failure {:kind (or (:kind (:last-error state))
                                             :outcome-unknown)
                                   :reason (:reason decision)}
                         :decision-reason (:reason decision))}

          :cancelled
          {:ok? true
           :decision :cancelled
           :reason :user-cancel
           :state (assoc state
                         :phase :cancelled
                         :retry-at nil
                         :failure {:kind :cancelled
                                   :reason :user-cancel}
                         :decision-reason :user-cancel)})))))

(defn resume-retry-wait
  "Pure startup/resume decision for one persisted `:retry-wait` state.

  Returns `{:ok? true :action ... :reason ... :state ...}` where action is:

    :terminal      the call is already terminal; do not touch it
    :cancel        cancellation wins; fix :cancelled
    :reconcile     pending/unknown effects; fix :outcome-unknown
    :fail          the finite budget or absolute deadline is spent; fix
                   :failed with `:failure {:kind :retry-budget-exhausted}`
    :claim         the fixed `:retry-at` is due; the Owner may claim it
    :rearm-timer   the retry is still in the future; re-arm a timer for the
                   persisted `:retry-at` without moving it

  The persisted `:retry-at` is never recomputed or extended.  A due retry
  stays in `:retry-wait` so `claim-due` can still consume it."
  [{:keys [state policy now]}]
  (let [prepared (prepare state policy now nil)]
    (if-not (:ok? prepared)
      prepared
      (let [p (:policy prepared)
            base (assoc state
                        :max-attempts (state-max-attempts state p)
                        :max-retries (state-max-retries state p)
                        :deadline (state-deadline state p)
                        :deadline-at (state-deadline state p))
            effects (normalize-effects base)
            terminal-state
            (fn [phase reason failure]
              (assoc base
                     :phase phase
                     :retry-at nil
                     :failure failure
                     :decision-reason reason))]
        (cond
          (contains? terminal-phases (:phase base))
          {:ok? true :action :terminal :reason :terminal :state base}

          (= :cancelled (:phase base))
          {:ok? true :action :cancel :reason :user-cancel :state base}

          (not= :retry-wait (:phase base))
          (invalid :NOT_RETRY_WAIT
                   "resume-retry-wait needs a :retry-wait state"
                   {:phase (:phase base)})

          (= :pending (:status effects))
          {:ok? true
           :action :reconcile
           :reason (if (true? (:cancelled? base))
                     :cancelled-with-pending-effects
                     :side-effects-pending)
           :state (terminal-state :outcome-unknown
                                  (if (true? (:cancelled? base))
                                    :cancelled-with-pending-effects
                                    :side-effects-pending)
                                  {:kind :outcome-unknown
                                   :reason (if (true? (:cancelled? base))
                                             :cancelled-with-pending-effects
                                             :side-effects-pending)})}

          (and (= :unknown (:status effects))
               (or (true? (:cancelled? base))
                   (not (and (:repeat-safe? effects)
                             (:retry-unknown-effects? p)))))
          {:ok? true
           :action :reconcile
           :reason (if (true? (:cancelled? base))
                     :cancelled-with-unknown-effects
                     :side-effects-unknown)
           :state (terminal-state :outcome-unknown
                                  (if (true? (:cancelled? base))
                                    :cancelled-with-unknown-effects
                                    :side-effects-unknown)
                                  {:kind :outcome-unknown
                                   :reason (if (true? (:cancelled? base))
                                             :cancelled-with-unknown-effects
                                             :side-effects-unknown)})}

          (true? (:cancelled? base))
          {:ok? true
           :action :cancel
           :reason :user-cancel
           :state (terminal-state :cancelled :user-cancel
                                  {:kind :cancelled :reason :user-cancel})}

          (>= (:attempts-used base) (state-max-attempts base p))
          {:ok? true
           :action :fail
           :reason :attempts-exhausted
           :state (terminal-state :failed :attempts-exhausted
                                  {:kind :retry-budget-exhausted
                                   :reason :attempts-exhausted})}

          (>= (:retries-used base) (state-max-retries base p))
          {:ok? true
           :action :fail
           :reason :retries-exhausted
           :state (terminal-state :failed :retries-exhausted
                                  {:kind :retry-budget-exhausted
                                   :reason :retries-exhausted})}

          (>= now (state-deadline base p))
          {:ok? true
           :action :fail
           :reason :deadline-exceeded
           :state (terminal-state :failed :deadline-exceeded
                                  {:kind :retry-budget-exhausted
                                   :reason :deadline-exceeded})}

          (nil? (:retry-at base))
          (invalid :NO_RETRY_PLANNED
                   "a :retry-wait state needs a persisted :retry-at"
                   {:phase (:phase base)})

          (<= (:retry-at base) now)
          {:ok? true
           :action :claim
           :reason :retry-due
           :retry-at (:retry-at base)
           :state base}

          :else
          {:ok? true
           :action :rearm-timer
           :reason :retry-scheduled
           :retry-at (:retry-at base)
           :state base})))))

(defn retry-wait?
  [state]
  (= :retry-wait (:phase state)))

(defn due?
  "True when the call is waiting and its fixed `:retry-at` has arrived.
  This says nothing about budgets, effects, or the deadline; use
  `claim-candidate` for the complete guard."
  [state now]
  (and (retry-wait? state)
       (integer? (:retry-at state))
       (integer? now)
       (<= (:retry-at state) now)))

(defn terminal?
  "True for `:succeeded`, `:failed`, `:cancelled`, and `:outcome-unknown`.
  `:outcome-unknown` fixes the call result; reconciliation is external and
  any further work uses a new call identity."
  [state]
  (or (contains? terminal-phases (:phase state))
      (= :cancelled (:phase state))))
