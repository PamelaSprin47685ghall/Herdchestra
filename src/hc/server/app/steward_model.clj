(ns hc.server.app.steward-model
  "Model-call adapter for the Steward and the Standing Sphinx.

  The Steward runtime emits two effects:

    {:type :model/meditate :role :meditator ...}
    {:type :model/decide   :role :steward   ...}

  `model-fn` turns those effects into bounded model calls on the existing
  low-level ModelPort.  It never creates an HTTP client and never uses the
  LangChain4j high-level `AiServices`.

  The returned function satisfies the runtime contract:

    (f effect) -> {:ok? true :value result :usage usage}
                | {:ok? false :error {...} :usage usage}

  Prompt data leaves the process as one EDN+UFSS value.  Model text is read
  back with `hc.core.ufss/read-str-safe` plus JSON conveniences, then shape
  checked before it can become a revision or a decision.

  Readers are optional host functions.  Each receives the effect map and
  returns either a plain value or `{:ok? true :value ...}`:

    :evidence-reader (fn [effect]) -> [evidence ...]
    :cba-reader      (fn [effect]) -> cba map
    :frontier-reader (fn [effect]) -> frontier map

  A reader refusal is `{:ok? false :error {...}}`.  The effect's own `:cba`
  and `:frontier` win over reader values for `:model/decide`.

  The request snapshot keeps the semantic role and the program role:

    :role         :meditator | :steward
    :program-role :clj-meditator | :clj-steward
    :model-role   the provider role, normally :main
    :tools        [] for the Meditator; one spec per Steward capability

  The role system message starts with `[[hc-role-prompt]]`.  That is the
  marker `hc.server.app.prompts/decorate-request` recognizes: it skips a
  second role prompt but still injects the program tool.  A gateway port
  (`{:start! :cancel!}`) receives the semantic snapshot and decorates it.
  A raw ModelPort receives a provider snapshot with `:role` replaced by
  `:model-role` and the gateway-only keys removed; the adapter applies the
  same `decorate-request` once before that conversion.  The Steward tool
  specs are derived from `hc.server.program.capability/roles`, not from a
  second hard-coded list."
  (:require [clojure.string :as str]
            [hc.compose.retry.error :as retry-error]
            [hc.compose.retry.policy :as retry-policy]
            [hc.compose.mission.model :as mission-model]
            [hc.core.ufss :as ufss]
            [hc.server.app.prompts :as prompts]
            [hc.server.model-port.contract :as contract]
            [hc.server.program.capability :as capability])
  (:import (java.util UUID)))

(def prompt-version
  "The output contract version carried by a Steward effect."
  1)

(def default-max-attempts 2)
(def default-timeout-ms 60000)
(def default-cache-size 64)
(def max-error-chars 2000)
(def max-evidence-records 64)

(def default-role-targets
  "Both Steward roles use the main model.  A host may override this map."
  {:meditator :main
   :steward :main})

(def default-system-suffix
  "\n\n# Steward model adapter\nDo not run a program and do not call tools. Reply with exactly one EDN map that matches the :output-shape in the user message. Leave no prose or code fence before or after the map.")

;; ---------------------------------------------------------------------------
;; Small helpers

(defn- error
  [code message details]
  (cond-> {:code code :message message}
    (some? details) (assoc :details details)))

(defn- unknown-usage
  []
  {:prompt-tokens :unknown
   :completion-tokens :unknown
   :source :unknown})

(defn- non-blank-string?
  [value]
  (and (string? value) (not (str/blank? value))))

(defn- identifier?
  [value]
  (or (non-blank-string? value) (keyword? value)))

(defn- finite-number?
  [value]
  (and (number? value)
       (let [value (double value)]
         (and (not (Double/isNaN value))
              (not (Double/isInfinite value))))))

(defn- unit-number?
  [value]
  (and (finite-number? value) (<= 0.0 value 1.0)))

(defn- non-negative-integer?
  [value]
  (and (integer? value) (not (neg? value))))

(defn- positive-integer?
  [value]
  (and (integer? value) (pos? value)))

(defn- truncate
  [text limit]
  (let [text (if (string? text) text "")]
    (if (> (.length ^String text) (long limit))
      (subs text 0 (min (long limit) (.length ^String text)))
      text)))

(defn- invalid-options!
  [message details]
  (throw (ex-info message (assoc details :code :STEWARD_MODEL_INVALID_OPTIONS))))

(defn- invalid-shape
  [task message details]
  {:ok? false
   :error (error :STEWARD_MODEL_INVALID_SHAPE message
                 (merge {:task task
                         :missing []
                         :invalid []
                         :unexpected []}
                        details))})

(defn- unparseable
  [raw reason]
  {:ok? false
   :error (error :STEWARD_MODEL_UNPARSEABLE
                 "the model response is not exactly one EDN map"
                 {:raw (truncate raw max-error-chars)
                  :reason reason})})

(defn- unexpected-keys
  [value allowed]
  (vec (sort-by str (remove allowed (keys value)))))

(defn- keywordize-keys
  "Normalize JSON string keys to keywords before shape validation.

  `read-str-safe` accepts EDN and, with `:json true`, JSON conveniences.
  JSON object keys arrive as strings; the model output contract uses
  keywords."
  [value]
  (cond
    (map? value)
    (into {} (map (fn [[k v]]
                    [(if (string? k) (keyword k) k)
                     (keywordize-keys v)]))
          value)

    (vector? value) (mapv keywordize-keys value)
    (sequential? value) (mapv keywordize-keys value)
    :else value))

;; ---------------------------------------------------------------------------
;; Usage

(defn- usage-of
  "Project a port usage report onto the Steward model usage shape.

  A missing token count is `:unknown`, never zero.  A flat legacy map is
  accepted as well as the report-shaped `{:usage {...} :sources {...}}`."
  [event]
  (let [report (:usage event)
        flat (if (and (map? report) (map? (:usage report)))
               (:usage report)
               report)
        prompt (get flat :input-tokens :unknown)
        completion (get flat :output-tokens :unknown)
        prompt (if (and (integer? prompt) (not (neg? prompt))) prompt :unknown)
        completion (if (and (integer? completion) (not (neg? completion)))
                     completion
                     :unknown)
        known? (or (not= :unknown prompt) (not= :unknown completion))
        source (cond
                 (not known?) :unknown
                 (= :reported (get-in report [:sources :input-tokens])) :provider
                 (= :reported (get-in report [:sources :output-tokens])) :provider
                 (nil? (:usage report)) :provider
                 :else :unknown)]
    {:prompt-tokens prompt
     :completion-tokens completion
     :source source}))

;; ---------------------------------------------------------------------------
;; Readers

(defn- reader-result
  [reader effect label]
  (if (nil? reader)
    {:ok? true :value nil}
    (try
      (let [value (reader effect)]
        (cond
          (and (map? value) (contains? value :ok?))
          (if (:ok? value)
            {:ok? true :value (:value value)}
            {:ok? false
             :error (or (:error value)
                        (error :STEWARD_MODEL_READER_FAILED
                               "the host reader refused the value"
                               {:reader label}))})

          :else
          {:ok? true :value value}))
      (catch Throwable t
        {:ok? false
         :error (error :STEWARD_MODEL_READER_FAILED
                       (or (.getMessage t) "the host reader failed")
                       {:reader label
                        :class (.getName (class t))})}))))

(defn- covered-evidence
  [records ids]
  (let [allowed (set (filter non-blank-string? ids))
        records (cond
                  (nil? records) []
                  (sequential? records) records
                  (and (map? records) (sequential? (:evidence records)))
                  (:evidence records)
                  :else nil)]
    (when (nil? records)
      (throw (ex-info "evidence reader must return a sequence" {})))
    (->> records
         (filter map?)
         (filter (fn [record]
                   (or (empty? allowed)
                       (contains? allowed (:evidence-id record)))))
         (take max-evidence-records)
         vec)))

(defn- cba-evidence
  [cba ids]
  (let [evidence (:evidence cba)
        ids (vec (filter non-blank-string? ids))]
    (if (map? evidence)
      (->> ids
           (keep (fn [id]
                   (get evidence id
                        {:evidence-id id})))
           (take max-evidence-records)
           vec)
      [])))

;; ---------------------------------------------------------------------------
;; Prompt payloads

(def meditate-output-shape
  {:revision
   {:summary "non-blank string"
    :frontier-of-thought "non-blank string"
    :confidence "number in [0,1] (optional)"
    :assumptions
    [{:assumption-id "string"
      :statement "string"
      :confidence "number in [0,1]"
      :watch-paths ["repo/relative/path"]
      :basis ["git:<commit>"]}]
    :claims
    [{:claim-id "string"
      :statement "string"
      :confidence "number in [0,1]"
      :weight "positive number"
      :dimension "keyword such as :correctness"
      :evidence-ids ["git:<commit>"]
      :assumption-ids ["string"]}]
    :questions
    [{:question-id "string"
      :question "string"}]}})

(def candidate-shape
  {:candidate-id "string"
   :title "string"
   :goal "string"
   :consequence "number in [0,1]"
   :evidence "number in [0,1]"
   :reversibility "number in [0,1]"
   :dependency "number in [0,1]"
   :opportunity "number in [0,1]"
   :demand "non-negative integer"
   :evidence-ids ["git:<commit>"]
   :assumption-ids ["string"]
   :depends-on ["mission-id"]
   :operator-aligned? "boolean (optional)"})

(def decide-output-shape
  {:request-id "non-blank string"
   :candidates [candidate-shape]
   :policy "optional map; omit for the default policy"})

(defn- program-role-for
  [task]
  (case task
    :meditate :clj-meditator
    :decide :clj-steward
    nil))

(defn- system-text
  [prompt suffix]
  (str prompts/role-prompt-marker "\n" prompt
       (or suffix default-system-suffix)))

(defn- task-payload
  [task effect prompt-version evidence cba frontier]
  (case task
    :meditate
    {:task :meditate
     :role (:role effect)
     :program-role (program-role-for :meditate)
     :prompt-version prompt-version
     :evidence evidence
     :evidence-ids (vec (:evidence-ids effect))
     :evidence-count (:evidence-count effect)
     :evidence-seq (:evidence-seq effect)
     :cba cba
     :frontier frontier
     :output-shape meditate-output-shape}

    :decide
    {:task :decide
     :role (:role effect)
     :program-role (program-role-for :decide)
     :prompt-version prompt-version
     :evidence evidence
     :evidence-ids (vec (or (:evidence-ids cba) []))
     :cba cba
     :frontier frontier
     :candidates-hint (or (:candidates-hint effect) {})
     :candidate-shape candidate-shape
     :output-shape decide-output-shape}))

(defn- capability-tool
  [capability-key]
  {:name (name capability-key)
   :description (str "Run the " (name capability-key)
                     " Steward capability with one EDN map argument.")
   :parameters {:type "object"
                :properties {:input {:type "object"
                                     :description "One EDN map argument."}}}
   :strict? false})

(defn- tools-for
  "The Steward tool face, derived from the program capability set.

  The Meditator has no tool face.  A tool name is the capability name, so a
  capability set change is visible without a second hard-coded list."
  [program-role]
  (case program-role
    :clj-steward
    (->> (capability/capabilities :clj-steward)
         (sort-by str)
         (mapv capability-tool))

    []))

(defn- request-snapshot
  [role program-role model-role messages max-tokens model connection tools]
  (cond-> {:role role
           :program-role program-role
           :model-role model-role
           :messages messages
           :tools tools
           :stream? false}
    (some? max-tokens) (assoc :max-tokens max-tokens)
    (identifier? model) (assoc :model model)
    (identifier? connection) (assoc :connection connection)))

(defn- provider-snapshot
  "The raw ModelPort snapshot.

  A gateway port performs prompt/tool decoration and maps the semantic role
  to the provider role itself.  A raw ModelPort does not, so this fallback
  removes the gateway-only keys and sends the provider role."
  [snapshot]
  (-> snapshot
      (dissoc :program-role :model-role)
      (assoc :role (or (:model-role snapshot) :main))))

;; ---------------------------------------------------------------------------
;; Shape validation

(defn- meditate-shape-error
  [message details]
  (invalid-shape :meditate message details))

(defn- check-optional-field
  [revision field predicate expected]
  (if (contains? revision field)
    (when-not (predicate (get revision field))
      (meditate-shape-error
       (str "revision field " field " does not match the output shape")
       {:field field :expected expected :found (get revision field)}))
    nil))

(defn- validate-meditate
  [parsed]
  (let [unexpected (unexpected-keys parsed #{:revision})
        revision (:revision parsed)]
    (cond
      (seq unexpected)
      (meditate-shape-error "revision output has unexpected top-level keys"
                            {:unexpected unexpected :expected [:revision]})

      (not (contains? parsed :revision))
      (meditate-shape-error "revision output is missing :revision"
                            {:missing [:revision] :expected [:revision]})

      (not (map? revision))
      (meditate-shape-error ":revision must be a map"
                            {:field :revision :expected "map"
                             :found (some-> revision class .getName)})

      :else
      (or (check-optional-field revision :summary non-blank-string? "non-blank string")
          (check-optional-field revision :frontier-of-thought
                                non-blank-string? "non-blank string")
          (check-optional-field revision :confidence unit-number? "number in [0,1]")
          (check-optional-field revision :assumptions
                                #(and (sequential? %) (every? map? %))
                                "sequence of maps")
          (check-optional-field revision :claims
                                #(and (sequential? %) (every? map? %))
                                "sequence of maps")
          (check-optional-field revision :questions
                                #(and (sequential? %) (every? map? %))
                                "sequence of maps")
          (check-optional-field revision :withdraw-claim-ids
                                #(and (sequential? %)
                                      (every? non-blank-string? %))
                                "sequence of strings")
          {:ok? true :value {:revision revision}}))))

(defn- candidate-error
  [index message details]
  (invalid-shape :decide message
                 (assoc details :candidate-index index)))

(def candidate-keys
  "Candidate keys accepted before normalization to the core shape."
  #{:candidate-id :title :goal :summary :rationale
    :consequence :evidence :evidence-strength :evidence-ids
    :reversibility :dependency :dependency-cost
    :opportunity :opportunity-cost :demand
    :assumption-ids :depends-on :operator-aligned?})

(defn- normalize-candidate
  [index candidate context-evidence-ids]
  (if-not (map? candidate)
    (candidate-error index "candidate must be a map"
                     {:field :candidate
                      :expected "map"
                      :invalid [:candidate]
                      :found (some-> candidate class .getName)})
    (let [candidate-id (:candidate-id candidate)
          title (or (when (non-blank-string? (:title candidate))
                      (:title candidate))
                    (when (non-blank-string? (:goal candidate))
                      (:goal candidate)))
          consequence (:consequence candidate)
          evidence-key (cond
                         (contains? candidate :evidence-strength)
                         :evidence-strength
                         (contains? candidate :evidence) :evidence
                         :else nil)
          evidence-strength (when evidence-key
                              (get candidate evidence-key))
          reversibility (:reversibility candidate)
          dependency-key (cond
                           (contains? candidate :dependency) :dependency
                           (contains? candidate :dependency-cost) :dependency-cost
                           :else nil)
          dependency (if dependency-key
                       (get candidate dependency-key)
                       0)
          opportunity-key (cond
                            (contains? candidate :opportunity) :opportunity
                            (contains? candidate :opportunity-cost)
                            :opportunity-cost
                            :else nil)
          opportunity (if opportunity-key
                        (get candidate opportunity-key)
                        0)
          demand (get candidate :demand 0)
          evidence-ids (get candidate :evidence-ids context-evidence-ids)
          assumption-ids (get candidate :assumption-ids [])
          depends-on (get candidate :depends-on [])
          operator-aligned (:operator-aligned? candidate)
          summary (or (when (non-blank-string? (:summary candidate))
                        (:summary candidate))
                      (when (non-blank-string? (:goal candidate))
                        (:goal candidate)))
          missing (cond-> []
                    (not (contains? candidate :candidate-id))
                    (conj :candidate-id)

                    (and (not (contains? candidate :title))
                         (not (contains? candidate :goal)))
                    (conj :title)

                    (not (contains? candidate :consequence))
                    (conj :consequence)

                    (nil? evidence-key)
                    (conj :evidence)

                    (not (contains? candidate :reversibility))
                    (conj :reversibility))
          invalid (cond-> []
                    (and (contains? candidate :candidate-id)
                         (not (non-blank-string? candidate-id)))
                    (conj :candidate-id)

                    (and (or (contains? candidate :title)
                             (contains? candidate :goal))
                         (not (non-blank-string? title)))
                    (conj :title)

                    (and (contains? candidate :goal)
                         (not (non-blank-string? (:goal candidate))))
                    (conj :goal)

                    (and (contains? candidate :summary)
                         (not (non-blank-string? (:summary candidate))))
                    (conj :summary)

                    (and (contains? candidate :rationale)
                         (not (non-blank-string? (:rationale candidate))))
                    (conj :rationale)

                    (and (contains? candidate :consequence)
                         (not (unit-number? consequence)))
                    (conj :consequence)

                    (and evidence-key
                         (not (unit-number? evidence-strength)))
                    (conj evidence-key)

                    (and (contains? candidate :reversibility)
                         (not (unit-number? reversibility)))
                    (conj :reversibility)

                    (and dependency-key
                         (not (unit-number? dependency)))
                    (conj dependency-key)

                    (and opportunity-key
                         (not (unit-number? opportunity)))
                    (conj opportunity-key)

                    (and (contains? candidate :demand)
                         (not (non-negative-integer? demand)))
                    (conj :demand)

                    (and (contains? candidate :evidence-ids)
                         (not (and (sequential? evidence-ids)
                                   (every? non-blank-string? evidence-ids))))
                    (conj :evidence-ids)

                    (and (contains? candidate :assumption-ids)
                         (not (and (sequential? assumption-ids)
                                   (every? non-blank-string? assumption-ids))))
                    (conj :assumption-ids)

                    (and (contains? candidate :depends-on)
                         (not (and (sequential? depends-on)
                                   (every? non-blank-string? depends-on))))
                    (conj :depends-on)

                    (and (contains? candidate :operator-aligned?)
                         (not (boolean? operator-aligned)))
                    (conj :operator-aligned?))
          unexpected (vec (sort-by str (remove candidate-keys
                                                (keys candidate))))]
      (if (or (seq missing) (seq invalid) (seq unexpected))
        (candidate-error index "candidate does not match :candidate-shape"
                         {:candidate-id candidate-id
                          :missing (vec (sort-by str missing))
                          :invalid (vec (sort-by str invalid))
                          :unexpected unexpected
                          :expected candidate-shape})
        {:ok? true
         :value
         (cond-> {:candidate-id candidate-id
                  :title title
                  :consequence (double consequence)
                  :evidence-strength (double evidence-strength)
                  :dependency-cost (double dependency)
                  :reversibility (double reversibility)
                  :opportunity-cost (double opportunity)
                  :demand demand
                  :evidence-ids (vec evidence-ids)
                  :assumption-ids (vec assumption-ids)
                  :depends-on (vec depends-on)}
           (some? summary)
           (assoc :summary summary)

           (non-blank-string? (:rationale candidate))
           (assoc :rationale (:rationale candidate))

           (some? operator-aligned)
           (assoc :operator-aligned? operator-aligned))}))))

(defn- validate-decide
  [parsed effect cba]
  (let [allowed #{:request-id :candidates :policy :frontier-id}
        unexpected (unexpected-keys parsed allowed)
        candidates (:candidates parsed)
        request-id (:request-id parsed)
        policy (:policy parsed)
        context-ids (vec (filter non-blank-string? (:evidence-ids cba)))]
    (cond
      (seq unexpected)
      (invalid-shape :decide "decision output has unexpected top-level keys"
                     {:unexpected unexpected
                      :expected (vec (sort-by str allowed))})

      (not (contains? parsed :candidates))
      (invalid-shape :decide "decision output is missing :candidates"
                     {:missing [:candidates]
                      :expected (vec (sort-by str allowed))})

      (not (sequential? candidates))
      (invalid-shape :decide ":candidates must be sequential"
                     {:field :candidates :expected "sequence of maps"
                      :found (some-> candidates class .getName)})

      (and (contains? parsed :policy)
           (some? policy)
           (not (map? policy)))
      (invalid-shape :decide ":policy must be a map when present"
                     {:field :policy :expected "map"
                      :found (some-> policy class .getName)})

      (and (some? request-id) (not (string? request-id)))
      (invalid-shape :decide ":request-id must be a string when present"
                     {:field :request-id :expected "string"
                      :found (some-> request-id class .getName)})

      :else
      (let [normalized (map-indexed
                        (fn [index candidate]
                          (normalize-candidate index candidate context-ids))
                        candidates)
            invalid (first (remove :ok? normalized))]
        (if invalid
          invalid
          {:ok? true
           :value (cond-> {:request-id (if (non-blank-string? request-id)
                                         request-id
                                         (str "model/"
                                              (:frontier-id effect)))
                           :candidates (mapv :value normalized)
                           :frontier-id (:frontier-id effect)}
                    (map? policy) (assoc :policy policy))})))))

;; ---------------------------------------------------------------------------
;; Parsing

(defn- parse-text
  [text task effect cba]
  (let [text (if (string? text) text "")]
    (if (str/blank? text)
      (unparseable text {:error :empty})
      (let [read (ufss/read-str-safe text {:json true})]
        (if (contains? read :error)
          (unparseable text
                       (select-keys (:error read)
                                    [:type :error :offset :line :column
                                     :expected :found]))
          (let [value (keywordize-keys (:ok read))]
            (cond
              (not (map? value))
              (invalid-shape task
                             "the model response must be one EDN map"
                             {:expected "map"
                              :found (some-> value class .getName)})

              (= task :meditate)
              (validate-meditate value)

              (= task :decide)
              (validate-decide value effect cba)

              :else
              (invalid-shape task "unknown model task"
                             {:expected #{:meditate :decide}}))))))))

;; ---------------------------------------------------------------------------
;; Port calls

(defn- gateway-port?
  [port]
  (and (map? port)
       (ifn? (:start! port))
       (ifn? (:cancel! port))))

(defn- deliver-terminal!
  [terminal event]
  (when (contains? #{:completed :failed :cancelled} (:event event))
    (deliver terminal event))
  {:ok true})

(defn- receiver
  [terminal]
  (reify contract/Receiver
    (deliver! [_ event]
      (deliver-terminal! terminal event))))

(defn- start-attempt!
  [port request terminal]
  (if (gateway-port? port)
    {:kind :gateway
     :handle ((:start! port)
              request
              (fn [event] (deliver-terminal! terminal event)))}
    (let [snapshot (:snapshot request)
          ;; A raw port does no decoration, so apply the single canonical
          ;; prompt/tool decoration here.  The Meditator is text-only: the
          ;; tools-less mode adds its prompt and removes the tool surface.
          tools-supported? (= :clj-steward (:program-role snapshot))
          decorated (:snapshot
                     (prompts/decorate-request
                      {:snapshot snapshot}
                      {:tools-supported? tools-supported?}))
          request (assoc request :snapshot (provider-snapshot decorated))]
      {:kind :raw
       :handle (contract/start! port request (receiver terminal))})))

(defn- cancel-attempt!
  [port attempt]
  (case (:kind attempt)
    :gateway (try ((:cancel! port) (:handle attempt))
                  (catch Throwable _))
    (try (contract/cancel! port (:handle attempt))
         (catch Throwable _))))

(defn- classify-result
  [result]
  (if (:timeout? result)
    {:category :transient :retryable? true :remedy :retry :source :timeout}
    (retry-error/classify
     (merge (or (:error result) {})
            (select-keys (:event result)
                         [:retryable? :category :disposition :status])))))

(defn- call-once
  [port snapshot fingerprint attempt-number remaining-ms]
  (if (<= (long remaining-ms) 0)
    {:ok? false
     :timeout? true
     :usage (unknown-usage)
     :error (error :STEWARD_MODEL_TIMEOUT
                   "the model call exceeded its total time budget"
                   {:attempt attempt-number})}
    (let [attempt-id (str (UUID/randomUUID))
          call-id (pr-str fingerprint)
          terminal (promise)
          request (contract/request
                   {:attempt-id attempt-id
                    :call-id call-id
                    :snapshot snapshot})
          attempt (start-attempt! port request terminal)]
      (let [event (deref terminal (long remaining-ms) ::timeout)]
        (if (= ::timeout event)
          (do
            (cancel-attempt! port attempt)
            {:ok? false
             :timeout? true
             :usage (unknown-usage)
             :error (error :STEWARD_MODEL_TIMEOUT
                           "the model call exceeded its total time budget"
                           {:attempt attempt-number
                            :remaining-ms remaining-ms})})
          (case (:event event)
            :completed
            {:ok? true
             :text (:text event)
             :usage (usage-of event)
             :event event}

            :failed
            {:ok? false
             :usage (usage-of event)
             :error (or (:error event)
                        (error :STEWARD_MODEL_CALL_FAILED
                               "the model call failed" nil))
             :event event}

            :cancelled
            {:ok? false
             :usage (usage-of event)
             :error (error :STEWARD_MODEL_CANCELLED
                           "the model call was cancelled"
                           {:reason (:reason event)})
             :event event}

            {:ok? false
             :usage (usage-of event)
             :error (error :STEWARD_MODEL_CALL_FAILED
                           "the model port returned an unknown terminal event"
                           {:event (:event event)})}))))))

(defn- start-error-code
  [data]
  (let [reason (:reason data)
        kind (:error/kind data)]
    (cond
      (contains? #{:invalid-request :invalid-snapshot} kind)
      :STEWARD_MODEL_INVALID_REQUEST

      (contains? #{:port-invalid :receiver-invalid} reason)
      :STEWARD_MODEL_PORT_INVALID

      :else :STEWARD_MODEL_CALL_FAILED)))

(defn- call-with-retries
  [opts effect snapshot fingerprint]
  (let [{:keys [port policy timeout-ms max-attempts clock]} opts
        deadline (+ (long (clock)) (long timeout-ms))]
    (loop [attempt 1
           last-usage (unknown-usage)]
      (let [remaining (- deadline (long (clock)))
            result (try
                     (call-once port snapshot fingerprint attempt remaining)
                     (catch InterruptedException _
                       (.interrupt (Thread/currentThread))
                       {:ok? false
                        :usage last-usage
                        :error (error :STEWARD_MODEL_CANCELLED
                                      "the model call was interrupted" nil)})
                     (catch Throwable t
                       (let [data (ex-data t)]
                         {:ok? false
                          :usage last-usage
                          :error (merge
                                  (select-keys data
                                               [:kind :category :status
                                                :retryable? :disposition])
                                  (error (start-error-code data)
                                         (or (.getMessage t)
                                             "the model port rejected the call")
                                         (select-keys data
                                                      [:reason :error/kind
                                                       :path :unknown
                                                       :class])))})))]
        (if (:ok? result)
          result
          (let [classification (classify-result result)
                usage (or (:usage result) last-usage)
                retry? (and (:retryable? classification)
                            (< attempt max-attempts)
                            (pos? (- deadline (long (clock)))))
                category (:category classification)
                code (cond
                       (:timeout? result) :STEWARD_MODEL_TIMEOUT
                       (= :cancelled category) :STEWARD_MODEL_CANCELLED
                       (= :context category) :STEWARD_MODEL_CONTEXT_TOO_LONG
                       (and (= :permanent category)
                            (or (= :auth (:category (:error result)))
                                (contains? #{401 403}
                                           (:status (:error result)))))
                       :STEWARD_MODEL_AUTH
                       (and (= :permanent category)
                            (contains? #{:model-not-found :invalid-model}
                                       (:kind (:error result))))
                       :STEWARD_MODEL_NOT_FOUND
                       (= :permanent category) :STEWARD_MODEL_CALL_FAILED
                       (= :transient category) :STEWARD_MODEL_RETRY_EXHAUSTED
                       :else :STEWARD_MODEL_CALL_FAILED)
                final {:ok? false
                       :usage usage
                       :error (error code
                                     (or (get-in result [:error :message])
                                         "the model call failed")
                                     {:attempts attempt
                                      :category category
                                      :classification
                                      (select-keys classification
                                                   [:category :retryable?
                                                    :remedy :source])
                                      :provider
                                      (select-keys (or (:error result) {})
                                                   [:code :kind :category
                                                    :status :message])})}]
            (if retry?
              (let [remaining-now (max 0 (- deadline (long (clock))))
                    delay (min remaining-now
                               (retry-policy/delay-ms
                                policy attempt
                                (retry-policy/deterministic-jitter
                                 (:jitter-ms policy)
                                 [fingerprint attempt])))]
                (if (pos? remaining-now)
                  (let [slept? (try
                                 (when (pos? delay)
                                   (Thread/sleep (long delay)))
                                 true
                                 (catch InterruptedException _
                                   (.interrupt (Thread/currentThread))
                                   false))]
                    (if slept?
                      (recur (inc attempt) usage)
                      {:ok? false
                       :usage usage
                       :error (error :STEWARD_MODEL_CANCELLED
                                     "the model retry wait was interrupted"
                                     {:attempts attempt})}))
                  final))
              final)))))))

;; ---------------------------------------------------------------------------
;; Effective options

(defn- role-target
  [roles effect-role]
  (let [fallback (or (mission-model/model-role-for effect-role)
                     (get default-role-targets effect-role)
                     :main)
        target (or (get roles effect-role)
                   (get roles :default)
                   fallback)]
    (cond
      (keyword? target) target
      (string? target) (keyword target)
      (map? target) (or (:role target) (:model-role target) fallback)
      :else fallback)))

(defn- role-overrides
  [roles effect-role]
  (let [target (get roles effect-role (get roles :default :main))]
    (when (map? target)
      (select-keys target [:connection :model :provider]))))

(defn- read-invocation-data
  [opts effect task]
  (let [{:keys [evidence-reader cba-reader frontier-reader]} opts
        evidence-ids (vec (:evidence-ids effect))
        cba-result (reader-result cba-reader effect :cba-reader)
        frontier-result (reader-result frontier-reader effect :frontier-reader)]
    (cond
      (not (:ok? cba-result)) {:ok? false :error (:error cba-result)}
      (not (:ok? frontier-result)) {:ok? false :error (:error frontier-result)}
      :else
      (let [effect-cba (when (map? (:cba effect)) (:cba effect))
            effect-frontier (when (map? (:frontier effect)) (:frontier effect))
            reader-cba (:value cba-result)
            reader-frontier (:value frontier-result)]
        (cond
          (and (some? reader-cba) (not (map? reader-cba)))
          {:ok? false
           :error (error :STEWARD_MODEL_READER_FAILED
                         "cba-reader must return a map"
                         {:reader :cba-reader})}

          (and (some? reader-frontier) (not (map? reader-frontier)))
          {:ok? false
           :error (error :STEWARD_MODEL_READER_FAILED
                         "frontier-reader must return a map"
                         {:reader :frontier-reader})}

          :else
          (let [cba (or effect-cba reader-cba {})
                frontier (or effect-frontier reader-frontier {})
                cba-ids (vec (or (:evidence-ids cba) evidence-ids))
                evidence-result (reader-result evidence-reader effect
                                               :evidence-reader)]
            (if-not (:ok? evidence-result)
              {:ok? false :error (:error evidence-result)}
              (let [records (if (nil? (:value evidence-result))
                              (cba-evidence cba cba-ids)
                              (try
                                (covered-evidence (:value evidence-result)
                                                  evidence-ids)
                                (catch Throwable t
                                  {:reader-error t})))]
                (if (and (map? records) (:reader-error records))
                  {:ok? false
                   :error (error :STEWARD_MODEL_READER_FAILED
                                 "evidence reader returned an invalid value"
                                 {:reader :evidence-reader})}
                  {:ok? true
                   :evidence records
                   :cba cba
                   :frontier frontier
                   :evidence-ids evidence-ids})))))))))

(defn- prompt-for
  [prompts role]
  (let [prompt (or (get prompts role)
                   (case role
                     :meditator (get prompts :meditate)
                     :steward (get prompts :decide)
                     nil)
                   (case role
                     :meditator prompts/meditator-prompt
                     :steward prompts/steward-prompt
                     nil))]
    (when (non-blank-string? prompt) prompt)))

;; ---------------------------------------------------------------------------
;; LRU

(defn- cache-get
  [cache key]
  (let [{:keys [entries order]} @cache]
    (when (contains? entries key)
      (swap! cache
             (fn [{:keys [entries order]}]
               {:entries entries
                :order (conj (vec (remove #{key} order)) key)}))
      (get entries key))))

(defn- cache-put
  [cache key value size]
  (swap! cache
         (fn [{:keys [entries order]}]
           (let [order (conj (vec (remove #{key} order)) key)
                 [order entries] (if (> (count order) size)
                                   (let [victim (first order)]
                                     [(subvec order 1)
                                      (dissoc entries victim)])
                                   [order entries])]
             {:entries (assoc entries key value)
              :order order}))))

;; ---------------------------------------------------------------------------
;; Public constructor

(defn effect-fingerprint
  "Stable identity for one Steward **model** effect.

  A repeated decision effect reuses its previous successful result.  The
  decision key carries both `:frontier-id` and (when present) `:request-id`.
  This is a model-only LRU key, not a general effect fingerprint: a
  non-model effect throws `:EFFECT_OUTSIDE_MODEL_DOMAIN` instead of folding
  into `[:unknown ...]`.  The host's general deduplication identity is
  `hc.compose.steward.loop/effect-fingerprint`."
  [effect]
  (case (:type effect)
    :model/meditate
    [:model/meditate (:role effect) (:evidence-seq effect)
     (:evidence-count effect) (vec (or (:evidence-ids effect) []))
     (:prompt-version effect)]

    :model/decide
    [:model/decide (:role effect) (:frontier-id effect)
     (:request-id effect) (:prompt-version effect)]

    (throw (ex-info
            "effect-fingerprint only accepts Steward model effects"
            {:code :EFFECT_OUTSIDE_MODEL_DOMAIN
             :details {:type (:type effect)
                       :allowed #{:model/meditate :model/decide}}}))))

(defn model-fn
  "Build the Steward model function from a low-level ModelPort.

  Options:

    :port             required ModelPort or gateway {:start! :cancel!}
    :prompts          optional `{:meditator ... :steward ...}` overrides
    :roles            role -> keyword/map; default maps both roles to :main
    :model-id         optional explicit model id for the snapshot
    :connection       optional explicit connection id
    :evidence-reader  optional `(fn [effect]) -> evidence records`
    :cba-reader       optional `(fn [effect]) -> cba map`
    :frontier-reader  optional `(fn [effect]) -> frontier map`
    :max-tokens       optional positive output-token limit
    :max-attempts     total attempts including the first, default 2
    :timeout-ms       total deadline in milliseconds, default 60000
    :cache-size       successful-result LRU entries, default 64
    :retry-policy     optional retry policy overrides
    :clock            optional `(fn [] epoch-ms)`"
  [opts]
  (when-not (map? opts)
    (invalid-options! "model-fn needs an option map" {:opts opts}))
  (let [{:keys [port prompts roles evidence-reader cba-reader frontier-reader
                model-id connection max-tokens max-attempts timeout-ms
                cache-size clock system-suffix]
         retry-config :retry-policy} opts
        prompt-version (or (:prompt-version opts)
                           hc.server.app.steward-model/prompt-version)
        max-attempts (or max-attempts default-max-attempts)
        timeout-ms (or timeout-ms default-timeout-ms)
        cache-size (or cache-size default-cache-size)
        clock (or clock #(System/currentTimeMillis))
        roles-opt roles
        roles (merge default-role-targets roles-opt)
        policy-result (retry-policy/normalize-policy
                       (merge {:max-attempts max-attempts
                               :deadline-ms timeout-ms
                               :base-delay-ms 500
                               :max-delay-ms 2000
                               :jitter-ms 250}
                              retry-config
                              {:max-attempts max-attempts
                               :deadline-ms timeout-ms}))]
    (when-not (or (satisfies? contract/ModelPort port)
                  (gateway-port? port))
      (invalid-options! "model-fn needs a low-level ModelPort or a gateway map"
                        {:port (some-> port class .getName)}))
    (when-not (:ok? policy-result)
      (invalid-options! "invalid retry policy" (:error policy-result)))
    (doseq [[label reader] [[:evidence-reader evidence-reader]
                            [:cba-reader cba-reader]
                            [:frontier-reader frontier-reader]]]
      (when-not (or (nil? reader) (ifn? reader))
        (invalid-options! (str label " must be a function")
                          {:reader label})))
    (when-not (or (nil? prompts) (map? prompts))
      (invalid-options! "prompts must be a map" {:prompts prompts}))
    (when-not (or (nil? roles-opt) (map? roles-opt))
      (invalid-options! "roles must be a map" {:roles roles-opt}))
    (when-not (and (positive-integer? max-attempts))
      (invalid-options! "max-attempts must be a positive integer"
                        {:max-attempts max-attempts}))
    (when-not (positive-integer? timeout-ms)
      (invalid-options! "timeout-ms must be a positive integer"
                        {:timeout-ms timeout-ms}))
    (when-not (positive-integer? cache-size)
      (invalid-options! "cache-size must be a positive integer"
                        {:cache-size cache-size}))
    (when-not (or (nil? max-tokens)
                  (and (positive-integer? max-tokens)))
      (invalid-options! "max-tokens must be a positive integer"
                        {:max-tokens max-tokens}))
    (let [cache (atom {:entries {} :order []})
          policy (:policy policy-result)]
      (fn [effect]
        (try
          (let [effect-type (:type effect)
                task (case effect-type
                       :model/meditate :meditate
                       :model/decide :decide
                       nil)
                expected-role (case task
                                :meditate :meditator
                                :decide :steward
                                nil)
                role (:role effect)]
            (cond
              (not (map? effect))
              {:ok? false
               :usage (unknown-usage)
               :error (error :STEWARD_MODEL_INVALID_EFFECT
                             "the Steward effect must be a map"
                             {:effect effect})}

              (nil? task)
              {:ok? false
               :usage (unknown-usage)
               :error (error :STEWARD_MODEL_UNKNOWN_EFFECT
                             "the Steward model adapter does not handle this effect"
                             {:type effect-type})}

              (and (contains? effect :prompt-version)
                   (not= (:prompt-version effect) prompt-version))
              {:ok? false
               :usage (unknown-usage)
               :error (error :STEWARD_MODEL_PROMPT_VERSION
                             "the effect and the adapter disagree on the prompt version"
                             {:effect (:prompt-version effect)
                              :adapter prompt-version})}

              (not= role expected-role)
              {:ok? false
               :usage (unknown-usage)
               :error (error :STEWARD_MODEL_INVALID_EFFECT
                             "the effect names the wrong role for this task"
                             {:type effect-type
                              :role role
                              :expected-role expected-role})}

              :else
              (let [fingerprint (effect-fingerprint effect)]
                (if-let [cached (cache-get cache fingerprint)]
                  cached
                  (let [system-prompt (prompt-for prompts role)]
                    (if-not system-prompt
                      {:ok? false
                       :usage (unknown-usage)
                       :error (error :STEWARD_MODEL_PROMPT_MISSING
                                     "no system prompt is configured for the Steward role"
                                     {:role role})}
                      (let [target (role-target roles role)
                            program-role (program-role-for task)
                            tools (tools-for program-role)
                            overrides (role-overrides roles role)
                            model (or model-id (:model overrides))
                            connection (or connection (:connection overrides)
                                           (:provider overrides))
                            data-result (read-invocation-data opts effect task)]
                        (if-not (:ok? data-result)
                          {:ok? false
                           :usage (unknown-usage)
                           :error (:error data-result)}
                          (let [payload (task-payload
                                         task effect prompt-version
                                         (:evidence data-result)
                                         (:cba data-result)
                                         (:frontier data-result))
                                system (system-text system-prompt system-suffix)
                                user (ufss/write-str payload)
                                snapshot (request-snapshot
                                          role
                                          program-role
                                          target
                                          [{:role :system :text system}
                                           {:role :user :text user}]
                                          max-tokens model connection tools)
                                call (call-with-retries
                                      {:port port
                                       :policy policy
                                       :timeout-ms timeout-ms
                                       :max-attempts max-attempts
                                       :clock clock}
                                      effect snapshot fingerprint)]
                            (if-not (:ok? call)
                              call
                              (let [parsed (parse-text (:text call) task effect
                                                       (:cba data-result))]
                                (if-not (:ok? parsed)
                                  (assoc parsed :usage (:usage call))
                                  (let [result {:ok? true
                                                :value (:value parsed)
                                                :usage (:usage call)}]
                                    (cache-put cache fingerprint result
                                               cache-size)
                                    result)))))))))))))
          (catch InterruptedException _
            (.interrupt (Thread/currentThread))
            {:ok? false
             :usage (unknown-usage)
             :error (error :STEWARD_MODEL_CANCELLED
                           "the model call was interrupted" nil)})
          (catch Exception t
            {:ok? false
             :usage (unknown-usage)
             :error (error :STEWARD_MODEL_INTERNAL
                           (or (.getMessage t)
                               "the Steward model adapter failed")
                           {:class (.getName (class t))})}))))))
