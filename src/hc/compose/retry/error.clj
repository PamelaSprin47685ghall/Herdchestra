(ns hc.compose.retry.error
  "Conservative classification of upstream faults for retry planning.

  The classification follows the AGENTS.md §5.4 table.  A caller may
  supply an explicit `:category`, an HTTP `:status`, or a `:kind`.  An
  unrecognized error is `:unknown` and never retryable: silence is not
  evidence that a repeated request is safe.

  Categories:

  | category           | meaning                                      |
  |--------------------|----------------------------------------------|
  | :transient         | network jitter, 429, short 5xx               |
  | :permanent         | bad credentials, missing model, bad request  |
  | :context           | input or context too long                    |
  | :model-correction  | tool arguments incomplete or invalid         |
  | :reconcile         | external outcome is unknown                  |
  | :cancelled         | the operator cancelled the work              |
  | :unknown           | unclassified; never retried automatically    |")

(def categories
  #{:transient :permanent :context :model-correction :reconcile :cancelled :unknown})

(def category-aliases
  "Upstream category names used by `hc.server.model-port.retry` and the
  LangChain4j adapter.  They are mapped into this package's semantic
  categories before any retry decision."
  {:network :transient
   :timeout :transient
   :rate-limited :transient
   :server-error :transient
   :upstream-unavailable :transient
   :declared-retryable :transient
   :auth :permanent
   :permission :permanent
   :insufficient-quota :permanent
   :invalid-model :permanent
   :input-invalid :permanent
   :input-too-long :context
   :context-length-exceeded :context
   :payload-too-large :context
   :content-refused :permanent
   :unsupported-feature :permanent
   :tool-arguments-invalid :model-correction
   :arguments-incomplete :model-correction
   :outcome-unknown :reconcile
   :unknown-outcome :reconcile
   :user-cancel :cancelled
   :user-cancelled :cancelled
   :unclassified :unknown})

(def disposition-aliases
  {:retry :transient
   :fail :permanent
   :new-attempt :model-correction
   :reconcile :reconcile
   :cancel :cancelled})

(def by-kind
  {:network :transient
   :timeout :transient
   :connection-reset :transient
   :rate-limited :transient
   :server-error :transient
   :service-unavailable :transient
   :overloaded :transient
   :http-408 :transient
   :http-425 :transient
   :http-429 :transient
   :http-500 :transient
   :http-502 :transient
   :http-503 :transient
   :http-504 :transient
   :http-501 :permanent
   :http-505 :permanent

   :auth :permanent
   :permission :permanent
   :invalid-credentials :permanent
   :unauthorized :permanent
   :forbidden :permanent
   :model-not-found :permanent
   :invalid-model :permanent
   :not-found :permanent
   :invalid-request :permanent
   :bad-request :permanent
   :insufficient-quota :permanent
   :input-invalid :permanent
   :input-too-long :context
   :context-length-exceeded :context
   :payload-too-large :context
   :content-refused :permanent
   :unsupported-feature :permanent

   :invalid-tool-arguments :model-correction
   :tool-arguments-invalid :model-correction
   :arguments-incomplete :model-correction
   :tool-validation-failed :model-correction

   :outcome-unknown :reconcile
   :unknown-outcome :reconcile

   :cancelled :cancelled
   :user-cancel :cancelled
   :user-cancelled :cancelled

   :unclassified :unknown
   :deadline-exceeded :permanent})

(defn status-category
  "Map an HTTP status to a retry category, or nil when unrecognized."
  [status]
  (when (integer? status)
    (condp == status
      408 :transient
      425 :transient
      429 :transient
      500 :transient
      502 :transient
      503 :transient
      504 :transient
      501 :permanent
      505 :permanent
      400 :permanent
      401 :permanent
      403 :permanent
      404 :permanent
      409 :permanent
      413 :context
      422 :model-correction
      nil)))

(defn remedy
  "The non-retry action suggested by a category."
  [category]
  (case category
    :transient :retry
    :context :adjust-context
    :model-correction :model-correction
    :reconcile :reconcile
    :permanent :none
    :cancelled :none
    :unknown :none
    :none))

(defn- keyword-status-category
  [status]
  (cond
    (contains? #{:outcome-unknown :unknown-outcome :unknown} status) :reconcile
    (contains? #{:cancelled :canceled} status) :cancelled
    (contains? by-kind status) (get by-kind status)
    :else nil))

(defn- partial-effects?
  "True when the failed attempt already produced side-effecting work.

  A model stream can publish tool calls before it fails.  Repeating that
  model request would publish them again, so the failure is reconcilable,
  not retryable, even when the transport error itself looks transient."
  [error]
  (or (true? (:partial? error))
      (seq (:published-call-ids error))
      (seq (:published-calls error))))

(defn classify
  "Classify an upstream error.

  Precedence: a partial-effect marker, then an explicit semantic category,
  an upstream category alias, an HTTP `:status`, a keyword `:status` or
  `:outcome`, `:kind`, `:disposition`, and finally a declared
  `:retryable? true`.  An invalid explicit category is treated as `:unknown`
  rather than silently falling through to a possibly optimistic kind.

  The adapter vocabulary from `hc.server.model-port.retry` (`:rate-limited`,
  `:server-error`, `:auth`, `:permission`, `:tool-arguments-invalid`, ...)
  is accepted as category aliases.  A failure that carries
  `:published-call-ids`, `:published-calls`, or `:partial? true` is
  `:reconcile` regardless of its transport category."
  [error]
  (let [error (if (map? error) error {})
        explicit (:category error)
        status (:status error)
        outcome (:outcome error)
        kind (:kind error)
        disposition (:disposition error)
        category
        (cond
          (partial-effects? error) :reconcile
          (contains? categories explicit) explicit
          (contains? category-aliases explicit) (get category-aliases explicit)
          (some? explicit) :unknown
          (and (integer? status) (status-category status))
          (status-category status)
          (and (keyword? status) (keyword-status-category status))
          (keyword-status-category status)
          (and (keyword? outcome) (keyword-status-category outcome))
          (keyword-status-category outcome)
          (contains? by-kind kind) (get by-kind kind)
          (contains? disposition-aliases disposition)
          (get disposition-aliases disposition)
          (true? (:retryable? error)) :transient
          :else :unknown)
        source
        (cond
          (partial-effects? error) :partial-effects
          (contains? categories explicit) :category
          (contains? category-aliases explicit) :category-alias
          (some? explicit) :invalid-category
          (and (integer? status) (status-category status)) :status
          (and (keyword? status) (keyword-status-category status))
          :status-keyword
          (and (keyword? outcome) (keyword-status-category outcome))
          :outcome-keyword
          (contains? by-kind kind) :kind
          (contains? disposition-aliases disposition) :disposition
          (true? (:retryable? error)) :retryable
          :else :default)
        ;; A transport category or a declared `:retryable?` must not hide a
        ;; hard authentication/permission failure or a permanent server
        ;; capability error.  The status is the stronger evidence here.
        status-veto? (and (integer? status)
                          (contains? #{401 403 501 505} status)
                          (= :transient category))
        category (if status-veto? :permanent category)
        source (if status-veto? :status-veto source)]
    {:category category
     :retryable? (= :transient category)
     :reconcilable? (= :reconcile category)
     :cancelled? (= :cancelled category)
     :remedy (remedy category)
     :source source
     :kind kind
     :status status}))

(defn retryable?
  [classification]
  (true? (:retryable? classification)))
