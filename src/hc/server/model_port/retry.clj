(ns hc.server.model-port.retry
  "Upstream failure classification shared with the Owner.

  A business continuation is a new model request.  An upstream retry repeats
  the same request after a transient failure.  The attempt/retry caps, the
  absolute deadline, and the fixed `:retry-at` belong to
  `hc.compose.retry.planner`; this namespace owns only the vocabulary and the
  transport declaration check that both layers share.")

(def retryable-kinds
  #{:network :transport :timeout :rate-limited :server-error
    :upstream-unavailable})

(def non-retryable-kinds
  #{:auth :permission :insufficient-quota :invalid-model
    :input-too-long :content-refused})

(def special-kinds
  {:tool-arguments-invalid :new-attempt
   :outcome-unknown :reconcile
   :user-cancel :cancel})

(defn- result [disposition category retryable? reason]
  {:disposition disposition
   :category category
   :retryable? retryable?
   :reason reason})

(defn classify-http-status
  "Classify a bare HTTP status when no provider error kind is available."
  [status]
  (cond
    (= 429 status) (result :retry :rate-limited true "rate limited")
    (= 408 status) (result :retry :timeout true "request timed out")
    (= 425 status) (result :retry :timeout true "request retried too early")
    (= 402 status) (result :fail :insufficient-quota false
                           "the provider reports no remaining quota")
    (= 404 status) (result :fail :invalid-model false
                           "the provider does not have the requested model")
    (= 413 status) (result :fail :input-too-long false
                           "request payload exceeds the model input limit")
    (and (integer? status) (<= 500 status 599))
    (result :retry :server-error true "upstream server error")
    (= 401 status)
    (result :fail :auth false "credential rejected")

    (= 403 status)
    (result :fail :permission false "permission denied")
    (contains? #{400 422} status)
    (result :fail :input-invalid false "request rejected as invalid")
    :else
    (result :fail :unclassified false "unclassified upstream failure")))

(defn classify
  "Classify one upstream failure.

  `:disposition` is one of `:retry`, `:fail`, `:new-attempt`, `:reconcile`,
  or `:cancel`.  A tool-argument failure is a new attempt with corrected
  input, not a blind replay.  An unknown external outcome is reconciled, not
  retried.  User cancellation never enters the retry budget."
  [{:keys [kind http-status retryable?]}]
  (cond
    (contains? retryable-kinds kind)
    (result :retry kind true "transient upstream failure")

    (contains? non-retryable-kinds kind)
    (result :fail kind false "configuration, quota, or content rejection")

    (contains? special-kinds kind)
    (let [disposition (get special-kinds kind)]
      (result disposition kind false "requires a non-retry path"))

    (some? http-status)
    (classify-http-status http-status)

    (true? retryable?)
    (result :retry :declared-retryable true "declared retryable by adapter")

    :else
    (result :fail :unclassified false "unclassified upstream failure")))

(defn validate-transport
  "Check that implicit transport retries are explicitly disabled.

  LangChain4j providers sit on HTTP clients that may retry on their own.  An
  implicit retry doubles the budget and hides the attempt count, so `nil` is
  rejected: the adapter must state `false` or `0`."
  [{:keys [implicit-retries]}]
  (cond
    (or (false? implicit-retries)
        (and (number? implicit-retries) (zero? implicit-retries)))
    {:ok true :implicit-retries 0}

    (nil? implicit-retries)
    {:ok false
     :error {:kind :implicit-retries-unknown
             :message "the transport must declare implicit retries disabled"}}

    :else
    {:ok false
     :error {:kind :implicit-retries-enabled
             :message "implicit transport retries would double the attempt budget"
             :implicit-retries implicit-retries}}))
