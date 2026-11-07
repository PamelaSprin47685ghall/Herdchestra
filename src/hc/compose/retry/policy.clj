(ns hc.compose.retry.policy
  "Pure policy for bounded upstream-fault retries.

  All times are epoch milliseconds.  This namespace never reads a clock,
  never uses randomness, and never performs I/O.  The caller supplies
  `now`; jitter is either supplied explicitly or derived deterministically
  from the call identity.

  `:max-attempts` includes the first attempt.  `:max-retries` excludes it.
  They are independent caps: a call may also spend attempts on non-retry
  work such as a corrected tool request, so the two numbers need not
  differ by one.  When a caller supplies only one cap, the other is
  derived by the natural one-to-one relation.")

(def default-policy
  "Conservative defaults: one initial attempt plus three retries, bounded
  by a two-minute total deadline and a thirty-second delay cap."
  {:max-attempts 4
   :max-retries 3
   :deadline-ms 120000
   :base-delay-ms 500
   :max-delay-ms 30000
   :jitter-ms 250
   :retry-unknown-effects? false})

(defn- invalid
  [message details]
  {:ok? false
   :error {:code :INVALID_POLICY
           :message message
           :details details}})

(defn- integer-at-least?
  [x minimum]
  (and (integer? x)
       (<= minimum x Long/MAX_VALUE)))

(defn normalize-policy
  "Validate and normalize a retry policy.

  Returns `{:ok? true :policy p}` or `{:ok? false :error {...}}`.  Extra
  keys are discarded so a committed policy is deterministic and small.
  Supplying `:max-attempts` alone derives `:max-retries`, and vice versa.
  Supplying both keeps both independently."
  ([]
   (normalize-policy {}))
  ([policy]
   (if-not (map? (or policy {}))
     (invalid "policy must be a map" {:policy policy})
     (let [provided (or policy {})
           has-attempts? (contains? provided :max-attempts)
           has-retries? (contains? provided :max-retries)
           merged (merge default-policy provided)
           merged (cond
                    (and has-attempts?
                         (not has-retries?)
                         (integer? (:max-attempts merged)))
                    (assoc merged :max-retries (dec (:max-attempts merged)))

                    (and has-retries?
                         (not has-attempts?)
                         (integer? (:max-retries merged)))
                    (assoc merged
                           :max-attempts
                           (min (dec Long/MAX_VALUE)
                                (+' 1 (:max-retries merged))))

                    :else
                    merged)
           {:keys [max-attempts max-retries deadline-ms base-delay-ms
                   max-delay-ms jitter-ms retry-unknown-effects?]} merged]
       (cond
         (not (integer-at-least? max-attempts 1))
         (invalid ":max-attempts must be an integer >= 1"
                  {:max-attempts max-attempts})

         (not (integer-at-least? max-retries 0))
         (invalid ":max-retries must be an integer >= 0"
                  {:max-retries max-retries})

         (not (integer-at-least? deadline-ms 1))
         (invalid ":deadline-ms must be an integer >= 1"
                  {:deadline-ms deadline-ms})

         (not (integer-at-least? base-delay-ms 0))
         (invalid ":base-delay-ms must be an integer >= 0"
                  {:base-delay-ms base-delay-ms})

         (not (integer-at-least? max-delay-ms 0))
         (invalid ":max-delay-ms must be an integer >= 0"
                  {:max-delay-ms max-delay-ms})

         (> base-delay-ms max-delay-ms)
         (invalid ":max-delay-ms must be >= :base-delay-ms"
                  {:base-delay-ms base-delay-ms
                   :max-delay-ms max-delay-ms})

         (not (integer-at-least? jitter-ms 0))
         (invalid ":jitter-ms must be an integer >= 0"
                  {:jitter-ms jitter-ms})

         (not (boolean? retry-unknown-effects?))
         (invalid ":retry-unknown-effects? must be a boolean"
                  {:retry-unknown-effects? retry-unknown-effects?})

         :else
         {:ok? true
          :policy (select-keys merged (keys default-policy))})))))

(defn backoff-delay
  "Capped exponential delay before retry number `retry-number` (1-based).

  Retry 1 uses `:base-delay-ms`; each later retry doubles, never above
  `:max-delay-ms`.  A zero base delay stays zero.  The loop stops as soon
  as the cap is reached or the remaining exponent is exhausted, so an
  unreasonably large retry number cannot spin."
  [policy retry-number]
  (let [{:keys [base-delay-ms max-delay-ms]} policy
        retry-number (long (max 1 retry-number))]
    (if (zero? base-delay-ms)
      0
      (loop [delay (long base-delay-ms)
             remaining (dec retry-number)]
        (if (or (<= remaining 0)
                (>= delay max-delay-ms))
          (min delay max-delay-ms)
          (let [next-delay (*' 2 delay)]
            (if (>= next-delay max-delay-ms)
              max-delay-ms
              (recur next-delay (dec remaining)))))))))

(defn deterministic-jitter
  "A stable offset in [0, `jitter-ms`) derived from `seed`.

  The hash spreads retries; it is not a security primitive.  `seed` must
  be a stable value such as `[call-id attempt-id retry-number]`, not a map
  whose iteration order may vary."
  [jitter-ms seed]
  (if (and (integer? jitter-ms) (pos? jitter-ms))
    (mod (hash (pr-str seed)) jitter-ms)
    0))

(defn delay-ms
  "Backoff plus jitter, capped by `:max-delay-ms`.

  `jitter-offset-ms` is an already-chosen non-negative offset.  Pass 0 for
  no jitter, or the result of `deterministic-jitter`."
  [policy retry-number jitter-offset-ms]
  (let [{:keys [max-delay-ms]} policy
        base (backoff-delay policy retry-number)
        jitter (or jitter-offset-ms 0)]
    (min max-delay-ms (+' base jitter))))
