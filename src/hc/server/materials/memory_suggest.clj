(ns hc.server.materials.memory-suggest
  "Automatic memory candidates from recent conversation rounds.

  Authority: root AGENTS.md §5.3 and §7.4 旅程 5.6（记忆）.

  The generator is split into four explicit parts:

    prepare   validate one request and build the model prompt
    complete  run exactly one low-level model-port attempt
    parse     read the model's JSON array into raw candidates
    review    filter, normalize, and persist candidates as `:candidate`

  `prepare` and `review` are pure.  `complete` uses
  `hc.server.model-port.contract/ModelPort` directly: one `start-request!`,
  one receiver, no AiServices loop and no automatic tool round-trip.  A model
  response is prose, not evidence; a candidate is never injected before an
  explicit approval.

  The caller supplies a session record or an explicit message vector.  This
  namespace reads no store, clock, or network client."
  (:require [clojure.string :as str]
            [hc.server.materials.errors :as errors]
            [hc.server.materials.memory :as memory]
            [hc.server.model-port.contract :as contract])
  (:import (java.nio.charset StandardCharsets)
           (java.security MessageDigest)
           (java.util HexFormat UUID)))

(def ^:private default-recent-rounds 10)
(def ^:private default-max-transcript-chars 12000)
(def ^:private default-timeout-ms 180000)

(def ^:private system-prompt
  (str "You extract long-term memory candidates from a conversation.\n"
       "Return ONLY a JSON array. Each item must be an object with:\n"
       "  \"text\": one self-contained statement, at most 2000 characters\n"
       "  \"category\": one of identity-background, working-style, "
       "expression-preference, long-term-requirement\n"
       "  \"epistemic\": one of fact, preference, requirement, assumption\n"
       "  \"confidence\": a number between 0 and 1, or null\n"
       "  \"source-message-id\": the message id the statement came from\n"
       "  \"quote\": the exact source substring, or an empty string\n"
       "Rules:\n"
       "- Never include passwords, API keys, tokens, private keys, or full "
       "payment card numbers. Omit such an item entirely.\n"
       "- Mark an unconfirmed guess as assumption; never present it as fact.\n"
       "- Do not invent facts that the conversation does not support.\n"
       "- Prefer durable preferences and requirements over temporary plans.\n"
       "- Return [] when nothing is worth remembering."))

(defn- unicode-length
  [^String value]
  (.codePointCount value 0 (.length value)))

(defn- unicode-prefix
  [^String value limit]
  (if (<= (unicode-length value) limit)
    value
    (.substring value 0 (.offsetByCodePoints value 0 limit))))

(defn- sha256
  [^String value]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (.formatHex (HexFormat/of)
                (.digest digest (.getBytes value StandardCharsets/UTF_8)))))

(defn- invalid
  [code message details]
  {:ok? false
   :error (errors/error code message details)})

(defn- non-blank? [value]
  (errors/non-blank-string? value))

(defn- normalize-category
  [value]
  (let [category (if (keyword? value) value (some-> value keyword))]
    (when (contains? memory/categories category)
      category)))

(defn- normalize-message
  [message]
  (when (map? message)
    (let [message-id (:message-id message)
          role (if (keyword? (:role message))
                 (:role message)
                 (some-> (:role message) keyword))
          content (or (:content message) (:text message) (:body message))]
      (when (and (non-blank? message-id)
                 (contains? #{:user :assistant :system :tool} role)
                 (string? content))
        {:message-id message-id
         :role role
         :content content}))))

(defn- normalize-messages
  [messages]
  (when (sequential? messages)
    (let [normalized (mapv normalize-message messages)]
      (when (every? some? normalized)
        normalized))))

(defn messages-from-session
  "Read the visible conversation messages from a session record, oldest first.

  The session map is the one the history domain keeps: `:message-order` plus
  `:messages`.  Deleted, folded, and nudge messages are excluded.  A versioned
  message contributes its current version's content.  A blank message is
  omitted because it carries no reviewable text.

  This function is pure.  It does not read a store and does not choose the
  recent-round window; `prepare` does that with the same round rule as the
  history projection (a user message is one round; tool traffic adds none)."
  [session]
  (let [order (vec (or (:message-order session) []))
        messages (:messages session)]
    (->> order
         (keep (fn [message-id]
                 (when (map? messages)
                   (get messages message-id))))
         (remove #(or (:deleted? %) (:folded? %)))
         (remove #(or (:nudge? %) (:nudge-id %)))
         (keep (fn [message]
                 (let [versions (:versions message)
                       index (:current-version message)
                       version (if (and (vector? versions)
                                        (integer? index)
                                        (<= 0 index)
                                        (< index (count versions)))
                                 (nth versions index)
                                 (first versions))
                       content (or (:content version)
                                   (:content message)
                                   (:text message)
                                   (:body message))
                       role (if (keyword? (:role message))
                              (:role message)
                              (some-> (:role message) keyword))]
                   (when (and (non-blank? (:message-id message))
                              (contains? #{:user :assistant :system :tool} role)
                              (string? content)
                              (not (str/blank? content)))
                     {:message-id (:message-id message)
                      :role role
                      :content content}))))
         vec)))

(defn- user-indexes
  [messages]
  (keep-indexed (fn [index message]
                  (when (= :user (:role message)) index))
                messages))

(defn- recent-round-window
  [messages rounds]
  (let [indexes (user-indexes messages)
        start (when (seq indexes)
                (if (>= (count indexes) rounds)
                  (nth indexes (- (count indexes) rounds))
                  0))]
    (if (nil? start)
      []
      (subvec (vec messages) start))))

(defn- selected-messages
  [messages source-range]
  (let [rounds (:recent-rounds source-range)
        ids (:message-ids source-range)]
    (cond
      (and (integer? rounds) (pos? rounds))
      (recent-round-window messages rounds)

      (and (vector? ids) (seq ids))
      (let [wanted (set ids)]
        (vec (filter #(contains? wanted (:message-id %)) messages)))

      :else messages)))

(defn- bounded-window
  "Keep the newest messages that fit LIMIT characters.

  Returns `{:messages [...] :dropped n}`.  The newest message is always kept,
  even when it alone exceeds the limit; the prompt then shows a bounded
  prefix of it.  Dropping older context is reported, never silent."
  [messages limit]
  (let [rendered (mapv (fn [message]
                         (str "[" (:message-id message) "] "
                              (name (:role message)) ": "
                              (:content message)))
                       messages)]
    (loop [remaining (reverse (map vector messages rendered))
           used 0
           kept []
           texts []
           dropped 0]
      (if-let [[message text] (first remaining)]
        (let [truncated? (and (empty? kept)
                              (> (unicode-length text) limit))
              text (if truncated? (unicode-prefix text limit) text)
              size (unicode-length text)
              fits? (or (empty? kept) (<= (+ used size) limit))]
          (if fits?
            (recur (next remaining)
                   (+ used size)
                   (conj kept message)
                   (conj texts text)
                   dropped)
            (recur (next remaining)
                   used
                   kept
                   texts
                   (inc dropped))))
        {:messages (vec (reverse kept))
         :texts (vec (reverse texts))
         :dropped dropped}))))

(defn- normalize-source-range
  [source-range]
  (let [source-range (or source-range {:recent-rounds default-recent-rounds})]
    (cond
      (not (map? source-range))
      (invalid :MEMORY_SUGGEST_RANGE_INVALID
               "source-range must be a map"
               {:source-range source-range})

      (and (some? (:recent-rounds source-range))
           (not (and (integer? (:recent-rounds source-range))
                     (pos? (:recent-rounds source-range)))))
      (invalid :MEMORY_SUGGEST_RANGE_INVALID
               "recent-rounds must be a positive integer"
               {:recent-rounds (:recent-rounds source-range)})

      (and (some? (:message-ids source-range))
           (not (and (vector? (:message-ids source-range))
                     (seq (:message-ids source-range))
                     (every? non-blank? (:message-ids source-range)))))
      (invalid :MEMORY_SUGGEST_RANGE_INVALID
               "message-ids must be a non-empty vector of non-blank ids"
               {:message-ids (:message-ids source-range)})

      (and (nil? (:recent-rounds source-range))
           (nil? (:message-ids source-range)))
      {:ok? true :source-range {:recent-rounds default-recent-rounds}}

      :else
      {:ok? true :source-range (select-keys source-range
                                            [:recent-rounds :message-ids])})))

(defn- normalize-categories
  [categories]
  (let [categories (or categories (vec memory/category-order))]
    (cond
      (not (and (sequential? categories) (seq categories)))
      (invalid :MEMORY_SUGGEST_CATEGORIES_INVALID
               "categories must be a non-empty sequence"
               {:categories categories})

      :else
      (let [normalized (mapv normalize-category categories)]
        (if (some nil? normalized)
          (invalid :MEMORY_SUGGEST_CATEGORIES_INVALID
                   "categories contain an unknown category"
                   {:categories (vec categories)
                    :allowed (vec memory/category-order)})
          {:ok? true :categories (vec (distinct normalized))})))))

(defn- normalize-binding
  [binding]
  (let [binding (or binding {:role :main})
        role (get {:main :main
                   :task :task
                   :utility :task}
                  (keyword (or (:role binding) :main)))
        connection (or (:connection binding) (:connection-id binding))
        model (or (:model binding) (:model-id binding)
                  (:model-entry-id binding))]
    (cond
      (not (map? binding))
      (invalid :MEMORY_SUGGEST_BINDING_INVALID
               "model-binding must be a map"
               {:model-binding binding})

      (nil? role)
      (invalid :MEMORY_SUGGEST_BINDING_INVALID
               "model-binding role must be :main, :task, or :utility"
               {:role (:role binding)})

      (and (some? connection) (not (non-blank? connection)))
      (invalid :MEMORY_SUGGEST_BINDING_INVALID
               "connection must be a non-blank string"
               {:connection connection})

      (and (some? model) (not (non-blank? model)))
      (invalid :MEMORY_SUGGEST_BINDING_INVALID
               "model must be a non-blank string"
               {:model model})

      :else
      {:ok? true
       :model-binding (cond-> {:role role}
                        (non-blank? connection)
                        (assoc :connection connection)
                        (non-blank? model)
                        (assoc :model model))})))

(defn prepare
  "Validate one suggestion request and build the single model prompt.

  REQUEST:

    {:session-id    non-blank conversation id
     :messages      [{:message-id :role :content} ...] oldest -> newest
     :source-range  {:recent-rounds n} or {:message-ids [...]}
     :categories    optional subset of the four memory categories
     :scope         memory scope, default :global
     :model-binding optional {:role :main|:task|:utility
                              :connection :model} or session-id/model-id keys
     :max-transcript-chars optional positive integer
     :max-candidates optional positive integer, default 20}

  The model sees the bounded transcript only.  A message dropped by the
  character budget is reported in `:dropped-message-count`."
  [request]
  (let [request (or request {})
        messages (normalize-messages (:messages request))
        source-range (normalize-source-range (:source-range request))
        categories (normalize-categories (:categories request))
        scope (memory/normalize-scope (or (:scope request) :global))
        binding (normalize-binding (:model-binding request))
        limit (or (:max-transcript-chars request) default-max-transcript-chars)
        max-candidates (or (:max-candidates request) 20)]
    (cond
      (not (map? request))
      (invalid :MEMORY_SUGGEST_REQUEST_INVALID
               "suggestion request must be a map" {:request request})

      (not (non-blank? (:session-id request)))
      (invalid :MEMORY_SUGGEST_SESSION_REQUIRED
               "suggestion needs a non-blank :session-id"
               {:session-id (:session-id request)})

      (nil? messages)
      (invalid :MEMORY_SUGGEST_MESSAGES_INVALID
               "messages must be a sequence of {:message-id :role :content}"
               {:messages (:messages request)})

      (not (:ok? source-range))
      source-range

      (not (:ok? categories))
      categories

      (nil? scope)
      (invalid :MEMORY_SUGGEST_SCOPE_INVALID
               "scope must be global, assistant or project"
               {:scope (:scope request)})

      (not (:ok? binding))
      binding

      (not (and (integer? limit) (pos? limit)))
      (invalid :MEMORY_SUGGEST_LIMIT_INVALID
               "max-transcript-chars must be a positive integer"
               {:max-transcript-chars limit})

      (not (and (integer? max-candidates) (pos? max-candidates)))
      (invalid :MEMORY_SUGGEST_MAX_CANDIDATES_INVALID
               "max-candidates must be a positive integer"
               {:max-candidates max-candidates})

      (empty? messages)
      (invalid :MEMORY_SUGGEST_NO_MESSAGES
               "the selected range contains no messages"
               {:source-range (:source-range source-range)})

      :else
      (let [selected (selected-messages messages (:source-range source-range))
            explicit-ids? (some? (:message-ids (:source-range source-range)))
            selected (if (and (empty? selected) (not explicit-ids?))
                       messages
                       selected)
            bounded (bounded-window selected limit)
            user-text (str/join "\n\n" (:texts bounded))]
        (cond
          (and explicit-ids? (empty? selected))
          (invalid :MEMORY_SUGGEST_RANGE_EMPTY
                   "none of the requested message ids exist in the range"
                   {:message-ids (:message-ids (:source-range source-range))})

          (empty? (:messages bounded))
          (invalid :MEMORY_SUGGEST_NO_MESSAGES
                   "the selected range contains no messages"
                   {:source-range (:source-range source-range)})

          :else
          {:ok? true
           :session-id (:session-id request)
           :categories (:categories categories)
           :scope scope
           :model-binding (:model-binding binding)
           :source-range (:source-range source-range)
           :source-messages (:messages bounded)
           :included-message-ids (mapv :message-id (:messages bounded))
           :dropped-message-count (:dropped bounded)
           :max-candidates max-candidates
           :prompt {:messages [{:role :system :text system-prompt}
                               {:role :user :text user-text}]}})))))

;; ---------------------------------------------------------------------------
;; One low-level model attempt

(defn- attempt-id
  []
  (str "memory-suggest-" (UUID/randomUUID)))

(defn- completion-text
  [chunks event]
  (let [text (:text event)]
    (if (and (string? text) (not (str/blank? text)))
      text
      (apply str chunks))))

(defn port-completer
  "Adapt one `ModelPort` into a synchronous completion function.

  Exactly one attempt starts.  `:completed` returns `{:ok? true :text ...}`;
  `:failed` and `:cancelled` return their error.  A timeout is reported as
  `:MEMORY_SUGGEST_TIMEOUT`, never as an empty response."
  ([port] (port-completer port {}))
  ([port {:keys [timeout-ms] :or {timeout-ms default-timeout-ms}}]
   (when-not (satisfies? contract/ModelPort port)
     (throw (ex-info "memory suggestion needs a ModelPort"
                     {:error/kind :invalid-port})))
   (fn [{:keys [snapshot]}]
     (let [reply (promise)
           chunks (atom [])
           attempt (attempt-id)
           request (contract/request {:attempt-id attempt
                                      :call-id attempt
                                      :snapshot snapshot})
           receiver (reify contract/Receiver
                      (deliver! [_ event]
                        (case (:event event)
                          :chunk
                          (do (when (string? (:text event))
                                (swap! chunks conj (:text event)))
                              {:ok true})

                          :completed
                          (do (deliver reply
                                       {:ok? true
                                        :text (completion-text @chunks event)})
                              {:ok true})

                          :failed
                          (do (deliver reply
                                       {:ok? false
                                        :error (or (:error event)
                                                   {:code :MEMORY_SUGGEST_MODEL_FAILED
                                                    :message "model attempt failed"})})
                              {:ok true})

                          :cancelled
                          (do (deliver reply
                                       {:ok? false
                                        :error (or (:error event)
                                                   {:code :MEMORY_SUGGEST_CANCELLED
                                                    :message "model attempt was cancelled"})})
                              {:ok true})

                          {:ok true})))]
       (try
         (let [handle (try
                        (contract/start-request! port request receiver)
                        (catch Throwable t
                          (deliver reply
                                   {:ok? false
                                    :error {:code :MEMORY_SUGGEST_MODEL_FAILED
                                            :message (or (ex-message t) (str t))}})
                          nil))
               result (deref reply
                             (long timeout-ms)
                             ::timeout)]
           (if (= ::timeout result)
             (do
               (when (contract/handle? handle)
                 (try
                   (contract/cancel-request! port handle)
                   (catch Throwable _ nil)))
               {:ok? false
                :error {:code :MEMORY_SUGGEST_TIMEOUT
                        :message "the memory suggestion model did not finish before the deadline"
                        :timeout-ms timeout-ms}})
             result))
         (catch Throwable t
           {:ok? false
            :error {:code :MEMORY_SUGGEST_MODEL_FAILED
                    :message (or (ex-message t) (str t))}}))))))

(defn complete
  "Run one completion for PREPARED.  Returns `{:ok? true :text ...}` or a
  structured refusal."
  ([port prepared] (complete port prepared {}))
  ([port prepared opts]
   (let [completer (port-completer port opts)]
     (completer {:snapshot (assoc (:prompt prepared)
                                  :role (get-in prepared [:model-binding :role])
                                  :connection (get-in prepared [:model-binding :connection])
                                  :model (get-in prepared [:model-binding :model]))}))))

;; ---------------------------------------------------------------------------
;; Parsing and normalization

(def ^:private raw-excerpt-limit 500)

(defn- safe-excerpt
  "A bounded raw response fragment, or nil when it contains sensitive data.

  The caller can show the fragment in a diagnostic; a credential never
  leaves the server through a parse error."
  [text]
  (when (string? text)
    (let [excerpt (unicode-prefix text raw-excerpt-limit)]
      (when-not (seq (memory/sensitive-kinds excerpt))
        excerpt))))

(defn- strip-code-fence
  [text]
  (if (str/includes? text "```")
    (or (some->> (re-seq #"(?s)```(?:json)?\s*(.*?)```" text)
                 first
                 second)
        text)
    text))

(defn- slice-between
  [text open close]
  (let [start (str/index-of text open)
        end (str/last-index-of text close)]
    (when (and start end (< start end))
      (subs text start (inc end)))))

(defn- json-read-str
  []
  (try
    (requiring-resolve 'clojure.data.json/read-str)
    (catch Throwable _ nil)))

(defn- read-json-value
  [read-str text]
  (try
    {:ok? true :value (read-str text :key-fn keyword)}
    (catch Throwable t
      {:ok? false :message (or (ex-message t) (str t))})))

(defn- json-candidates
  "Parse one model response into `{:ok? true :items [...]}` or a structured
  error.

  Accepted shapes:

    - a bare JSON array;
    - an object with a `candidates` array;
    - either shape wrapped in a Markdown code fence or surrounded by prose.

  The error carries the reader's message (which includes a position when the
  reader reports one); it never carries a thrown exception."
  [text]
  (let [read-str (json-read-str)]
    (cond
      (nil? read-str)
      {:ok? false
       :error {:message "clojure.data.json is not on the classpath"
               :kind :json-unavailable}}

      (not (string? text))
      {:ok? false
       :error {:message "the model response is not a string"
               :kind :not-string}}

      (str/blank? text)
      {:ok? false
       :error {:message "the model response is empty"
               :kind :empty}}

      :else
      (let [candidate (strip-code-fence text)
            attempts (->> [candidate
                           (slice-between candidate "[" "]")
                           (slice-between candidate "{" "}")]
                          (remove nil?)
                          distinct
                          vec)
            results (mapv #(read-json-value read-str %) attempts)
            success (first (filter :ok? results))
            failure (last results)]
        (if-not success
          {:ok? false
           :error {:message (:message failure)
                   :kind :unreadable}}
          (let [value (:value success)]
            (cond
              (vector? value)
              {:ok? true :items value}

              (and (map? value) (vector? (:candidates value)))
              {:ok? true :items (:candidates value)}

              :else
              {:ok? false
               :error {:message (str "the response must be a JSON array or an "
                                     "object with a candidates array")
                       :kind :not-candidates
                       :value-kind (cond
                                     (map? value) :object
                                     (vector? value) :array
                                     :else :scalar)}})))))))

(defn- normalize-epistemic
  [raw]
  (let [assumption? (or (true? (:assumption? raw))
                        (= :assumption (:epistemic raw))
                        (= "assumption" (:epistemic raw)))
        epistemic (if (keyword? (:epistemic raw))
                    (:epistemic raw)
                    (some-> (:epistemic raw) keyword))]
    (cond
      assumption? :assumption
      (contains? memory/epistemic-kinds epistemic) epistemic
      :else :fact)))

(defn- normalize-confidence
  [value]
  (if (and (number? value) (<= 0 (double value) 1))
    (double value)
    :unknown))

(defn- find-span
  [content quote]
  (when (and (string? quote) (not (str/blank? quote)) (string? content))
    (let [start (str/index-of content quote)]
      (when (some? start)
        [start (+ start (count quote))]))))

(defn- default-source-id
  [messages]
  (or (some (fn [message]
              (when (= :user (:role message)) (:message-id message)))
            (reverse messages))
      (:message-id (last messages))))

(defn- raw-candidate
  [prepared raw]
  (when (map? raw)
    (let [text (errors/trimmed-body (:text raw))
          category (normalize-category (:category raw))
          messages (:source-messages prepared)
          message-ids (set (map :message-id messages))
          requested-source (when (non-blank? (:source-message-id raw))
                             (:source-message-id raw))
          source-id (if (contains? message-ids requested-source)
                      requested-source
                      (default-source-id messages))
          source-message (some #(when (= source-id (:message-id %)) %)
                               messages)
          quote (when (string? (:quote raw))
                  (str/trim (:quote raw)))
          span (find-span (:content source-message) quote)
          epistemic (normalize-epistemic raw)]
      {:text text
       :category category
       :epistemic epistemic
       :assumption? (= :assumption epistemic)
       :confidence (normalize-confidence (:confidence raw))
       :source-message-id source-id
       :quote (when (and quote (not (str/blank? quote))) quote)
       :span (or span :unknown)})))

(defn parse
  "Read the model's response into normalized candidates.

  Three shapes are accepted: a bare JSON array, an object with a
  `candidates` array, and either shape wrapped in a Markdown code fence or
  surrounded by prose.

  A malformed response is a structured refusal, never an exception and never
  an empty success.  Its details carry `:parse-error` (the reader message,
  including a position when the reader reports one), `:parse-error-kind`,
  and a bounded `:raw-excerpt` that is omitted and marked `:redacted?` when
  it contains sensitive data.  Unknown categories are reported as
  `:category-filtered`."
  [prepared text]
  (let [parsed (json-candidates text)
        excerpt (safe-excerpt text)]
    (if-not (:ok? parsed)
      {:ok? false
       :error (errors/error
               :MEMORY_SUGGEST_RESPONSE_INVALID
               "the model response is not a readable JSON candidate list"
               (cond-> {:parse-error (get-in parsed [:error :message])
                        :parse-error-kind (get-in parsed [:error :kind])}
                 (get-in parsed [:error :value-kind])
                 (assoc :value-kind (get-in parsed [:error :value-kind]))

                 (some? excerpt)
                 (assoc :raw-excerpt excerpt)

                 (and (string? text) (nil? excerpt))
                 (assoc :redacted? true)))}
      (let [categories (set (:categories prepared))
            candidates (mapv #(raw-candidate prepared %) (:items parsed))
            valid (filterv some? candidates)
            empty-count (count (filter #(or (nil? %) (empty? (:text %)))
                                       candidates))
            category-filtered
            (count (filter #(and (some? %) (nil? (:category %))) valid))
            kept (filterv #(and (some? %)
                                (seq (:text %))
                                (contains? categories (:category %)))
                          valid)]
        {:ok? true
         :candidates kept
         :skipped-reasons (cond-> {}
                            (pos? empty-count)
                            (assoc :empty empty-count)
                            (pos? category-filtered)
                            (assoc :category-filtered category-filtered))}))))

(def ^:private repair-output-limit 4000)

(defn repair-prepared
  "Build the one repair prompt for a failed parse.

  The repair request keeps the same model binding and source selection.  It
  carries the previous output and the reader's error message, bounded so a
  runaway response cannot grow the prompt without limit."
  [prepared previous-text parse-error]
  (let [previous (if (string? previous-text)
                   (unicode-prefix previous-text repair-output-limit)
                   "")
        message (or (:message parse-error) "invalid JSON")]
    (assoc prepared
           :repair? true
           :prompt
           {:messages
            [{:role :system
              :text (str "Return only one valid JSON array. "
                         "No prose, no Markdown, no code fence. "
                         "Each item must have text, category, epistemic, "
                         "confidence, source-message-id, and quote.")}
             {:role :user
              :text (str "The previous response could not be parsed: "
                         message ".\n\nPrevious response:\n"
                         previous
                         "\n\nReturn the corrected JSON array only.")}]})))

;; ---------------------------------------------------------------------------
;; Review and persistence

(defn- truncate-body
  [text]
  (if (> (unicode-length text) memory/max-body-chars)
    [(unicode-prefix text memory/max-body-chars) true]
    [text false]))

(def ^:private normalization-pattern
  "Whitespace and common punctuation only.  Symbols such as `+`, `#`, `%`,
  `@`, and `&` stay significant so `C++` and `C` are not merged."
  #"[\s.,;:!?。，、；：！？'\"“”‘’()\[\]{}<>《》【】…—–-]+")

(defn- normalized-body
  [body]
  (when (string? body)
    (let [normalized (-> body
                         str/lower-case
                         (str/replace normalization-pattern ""))]
      (when (seq normalized) normalized))))

(defn- same-content?
  "Exact hash, or the same text after whitespace/punctuation folding.

  This suppresses a re-suggestion that differs only by punctuation or
  spacing.  It is deliberately not semantic matching: different words stay
  different candidates."
  [body hash record]
  (or (= hash (:body-hash record))
      (let [normalized (normalized-body body)]
        (and normalized
             (= normalized (normalized-body (:body record)))))))

(defn- same-content-record
  [memories body category scope statuses]
  (let [hash (memory/body-hash body)
        key (memory/scope-key scope)]
    (some (fn [[id record]]
            (when (and (contains? statuses (:status record))
                       (same-content? body hash record)
                       (= category (:category record))
                       (= key (memory/scope-key (:scope record))))
              [(:status record) id]))
          memories)))

(defn- candidate-id
  [session-id text category scope source-message-id]
  (str "mem-suggest-"
       (subs (sha256 (pr-str [session-id text category
                              (memory/scope-key scope) source-message-id]))
             0 24)))

(defn- review-one
  [state prepared candidate]
  (let [[body truncated?] (truncate-body (:text candidate))
        scope (:scope prepared)
        category (:category candidate)
        session-id (:session-id prepared)
        memories (:memories state)
        excerpt (or (:quote candidate)
                    (unicode-prefix body 200))
        sensitive (vec (distinct (concat (memory/sensitive-kinds (:text candidate))
                                         (memory/sensitive-kinds excerpt))))]
    (cond
      (seq sensitive)
      {:state state :skipped :sensitive
       :reason {:sensitive (vec sensitive)}}

      (same-content-record memories body category scope #{:archived})
      {:state state :skipped :archived}

      (same-content-record memories body category scope #{:deleted})
      {:state state :skipped :deleted}

      (same-content-record memories body category scope #{:rejected})
      {:state state :skipped :already-rejected}

      (same-content-record memories body category scope #{:active})
      {:state state :skipped :already-active}

      (same-content-record memories body category scope #{:candidate})
      {:state state :skipped :already-candidate}

      :else
      (let [id (candidate-id session-id body category scope
                             (:source-message-id candidate))
            source {:session-id session-id
                    :message-ids [(:source-message-id candidate)]
                    :excerpt (if (> (unicode-length excerpt)
                                    memory/max-excerpt-chars)
                               (unicode-prefix excerpt memory/max-excerpt-chars)
                               excerpt)}
            proposal {:id id
                      :body body
                      :category category
                      :scope scope
                      :epistemic (:epistemic candidate)
                      :always? false
                      :source source
                      :reason :memory-suggest
                      :truncated? truncated?}
            result (memory/propose state proposal)]
        (cond
          (and (:ok? result) (:suppressed? result))
          {:state (:state result)
           :skipped (case (:reason result)
                      :previously-rejected :already-rejected
                      :already-active :already-active
                      :refused)}

          (:ok? result)
          (if (:replayed? result)
            {:state (:state result) :skipped :already-candidate}
            {:state (:state result)
             :candidate (cond-> {:id (:id (:memory result))
                                 :text body
                                 :category category
                                 :scope scope
                                 :status :pending-review
                                 :source {:message-id (:source-message-id candidate)
                                          :span (:span candidate)
                                          :excerpt (get-in (:memory result)
                                                           [:source :excerpt])}
                                 :confidence (:confidence candidate)
                                 :assumption? (:assumption? candidate)}
                          truncated? (assoc :truncated? true))})

          :else
          {:state state
           :skipped :refused
           :reason {:refused (:error result)}})))))

(defn review
  "Filter normalized candidates against STATE and persist the accepted ones
  as `:candidate`.  Nothing becomes active here.

  Skip reasons:

    :sensitive          credential or full financial account found
    :archived           an archived record with the same content exists
    :deleted            a deletion tombstone with the same content exists
    :already-rejected   an identical candidate was rejected before
    :already-active     an identical active memory exists
    :already-candidate  an identical candidate is already waiting
    :refused            the memory domain refused the proposal

  Returns `{:ok? true :state ... :candidates [...] :skipped n
  :skipped-reasons {...}}`."
  [state prepared candidates]
  (let [base-revision (:revision state)
        limit (let [value (:max-candidates prepared)]
                (if (and (integer? value) (pos? value)) value 20))
        dropped (max 0 (- (count candidates) limit))
        candidates (vec (take limit candidates))
        result
        (reduce
         (fn [{:keys [state] :as acc} candidate]
           (let [reviewed (review-one state prepared candidate)
                 acc (assoc acc :state (:state reviewed))
                 acc (if (:candidate reviewed)
                       (update acc :candidates conj (:candidate reviewed))
                       acc)
                 acc (if-let [reason (:skipped reviewed)]
                       (update-in acc [:skipped-reasons reason] (fnil inc 0))
                       acc)]
             (if-let [reason (:reason reviewed)]
               (update acc :reasons conj reason)
               acc)))
         {:state state
          :candidates []
          :skipped-reasons {}
          :reasons []}
         candidates)]
    (cond-> (assoc result
                   :ok? true
                   :skipped (reduce + 0 (vals (:skipped-reasons result)))
                   :truncated? (pos? dropped)
                   :dropped-candidates dropped
                   :effects [])
      ;; One review transaction is one state transition.  Each propose bumps
      ;; the revision for its own record; the batch must advance it exactly
      ;; once so the store's compare-and-set accepts the commit.
      (seq (:candidates result))
      (update :state assoc :revision (inc (long (or base-revision 0)))))))

(defn review-parsed
  "Review an already parsed response and attach transcript/attempt evidence.

  PARSED is the value returned by `parse` (or an equivalent map with
  `:candidates` and `:skipped-reasons`).  `:attempts` is copied from it so a
  repaired response reports two attempts."
  [state prepared parsed]
  (let [reviewed (review state prepared (:candidates parsed))
        merged (merge-with + (:skipped-reasons parsed)
                           (:skipped-reasons reviewed))]
    (assoc reviewed
           :skipped (reduce + 0 (vals merged))
           :skipped-reasons merged
           :included-message-ids (:included-message-ids prepared)
           :dropped-message-count (:dropped-message-count prepared)
           :attempts (:attempts parsed))))

(defn generate
  "Convenience composition for callers that already hold the model text.

  PREPARED is the result of `prepare`; TEXT is the model response.  Returns
  the `review` result, or a parse refusal.  This single-shot helper does not
  retry; the service owns the one repair attempt."
  [state prepared text]
  (let [parsed (parse prepared text)]
    (if-not (:ok? parsed)
      parsed
      (review-parsed state prepared (assoc parsed :attempts 1)))))
