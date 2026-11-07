(ns hc.core.pair-hint.language
  "One-time deterministic language resolution and lifetime binding.

  Ladder: explicit environment variable -> system config preference ->
  IDE/system locale -> default `:en` with the default recorded.  An explicit
  value that is not `:en` or `:zh-CN` fails immediately; it never falls back to
  the other language.  Once bound, retry, failure, compaction, and process
  restart do not re-run the ladder."
  (:require [clojure.string :as str]
            [hc.core.pair-hint.resources :as resources]))

(def language-env-var
  "Default explicit environment variable for session language."
  "HERDCHESTRA_LANGUAGE")

(defn- lookup
  "Look up a key in a map that may use strings or keywords."
  [m k]
  (cond
    (not (associative? m)) nil
    (contains? m k) (get m k)
    (keyword? k) (get m (name k))
    (string? k) (get m (keyword k))
    :else nil))

(defn- present?
  [m k]
  (cond
    (not (associative? m)) false
    (contains? m k) true
    (keyword? k) (contains? m (name k))
    (string? k) (contains? m (keyword k))
    :else false))

(defn normalize-language
  "Strictly normalize a language value.

  Only exact `:en` / `:zh-CN` keywords or strings are accepted.  `:EN`,
  `:zh`, `:zh_CN`, and `:fr` are invalid rather than silently rewritten."
  [value]
  (let [candidate
        (cond
          (keyword? value) value
          (and (string? value) (not (str/blank? value))) (keyword value)
          :else nil)]
    (when (resources/valid-language? candidate)
      candidate)))

(defn- invalid-language!
  [value source]
  (throw (ex-info "pair-hint: language value is not supported"
                  {:error :PAIR_HINT_LANGUAGE_INVALID
                   :source source
                   :value value
                   :supported resources/supported-languages})))

(defn locale->language
  "Map a locale tag to one of the two supported languages.

  Matching is by primary language tag: `zh`, `zh-CN`, `zh_CN.UTF-8`, and
  `zh-Hant-TW` all select `:zh-CN`; `en`, `en-US`, and `en_US.UTF-8` select
  `:en`.  Anything else has no supported match."
  [locale]
  (when (and locale (not (str/blank? (str locale))))
    (let [s (-> (str locale)
                (str/replace #"^:" "")
                str/lower-case)]
      (cond
        (re-find #"^zh(?:[-_]|$)" s) :zh-CN
        (re-find #"^en(?:[-_]|$)" s) :en
        :else nil))))

(defn- default-result
  []
  {:language :en
   :source :default
   :raw nil
   :undetermined? true
   :recorded? true})

(defn resolve-language
  "Resolve a session language exactly once.

  Options:
  - `:explicit-language` direct caller override (highest)
  - `:env` map containing `:env-var` (default `HERDCHESTRA_LANGUAGE`)
  - `:config` map with `:language`
  - `:ide-locale`, `:locale` locale tags

  Returns `{:language ... :source ... :raw ... :undetermined? ...}`.  The
  default is recorded as `:source :default` and `:undetermined? true`; it is
  not a silent fallback from an invalid explicit value."
  [{:keys [explicit-language env config ide-locale locale env-var]
    :or {env-var language-env-var}}]
  (cond
    (some? explicit-language)
    (let [language (or (normalize-language explicit-language)
                       (invalid-language! explicit-language :explicit))]
      {:language language
       :source :explicit
       :raw explicit-language
       :undetermined? false
       :recorded? false})

    (present? env env-var)
    (let [raw (lookup env env-var)
          language (or (normalize-language raw)
                       (invalid-language! raw :env))]
      {:language language
       :source :env
       :raw raw
       :undetermined? false
       :recorded? false})

    (present? config :language)
    (let [raw (lookup config :language)
          language (or (normalize-language raw)
                       (invalid-language! raw :config))]
      {:language language
       :source :config
       :raw raw
       :undetermined? false
       :recorded? false})

    :else
    (or (when-let [language (locale->language ide-locale)]
          {:language language
           :source :ide-locale
           :raw ide-locale
           :undetermined? false
           :recorded? false})
        (when-let [language (locale->language locale)]
          {:language language
           :source :locale
           :raw locale
           :undetermined? false
           :recorded? false})
        (default-result))))

(defn validate-session-language
  "Formal runtime check: language is legal and both resources are present."
  [session]
  (let [language (:language session)]
    (when-not (resources/valid-language? language)
      (invalid-language! language :session))
    (when-not (resources/resource-pair? (:resources session))
      (throw (ex-info "pair-hint: session resources are not a complete pair"
                      {:error :PAIR_HINT_RESOURCE_MISSING
                       :language language})))
    session))

(defn bind-session
  "Resolve and bind a language for a new session.

  The result is a plain map suitable for persistence.  Loading validates that
  both resource files exist; a missing half throws."
  ([] (bind-session {}))
  ([opts]
   (let [resolved (resolve-language opts)
         resources (resources/load-pair
                    (select-keys opts [:resources :loader :paths]))]
     (merge resolved
            {:resources resources
             :language-bound? true}))))

(defn resume-session
  "Resume a persisted session without re-running the resolution ladder.

  The persisted `:language` is authoritative.  A malformed language field is
  an immediate failure, not a reason to consult environment or locale."
  [persisted opts]
  (let [language (or (normalize-language (:language persisted))
                     (invalid-language! (:language persisted) :persisted))
        load-opts (cond-> (select-keys opts [:loader :paths])
                    (:resources persisted)
                    (assoc :resources (:resources persisted)))
        resources (resources/load-pair load-opts)]
    (merge persisted
           {:language language
            :resources resources
            :language-bound? true})))

(defn inherit-language
  "Bind a child session or swarm slot to its task's language.

  An explicit task language is allowed only when it equals the parent's bound
  language.  Supplying an invalid value is `:PAIR_HINT_LANGUAGE_INVALID`; a
  valid but different value is `:PAIR_HINT_LANGUAGE_INHERITANCE_CONFLICT`."
  [parent task-language]
  (let [parent-language (:language parent)]
    (when-not (resources/valid-language? parent-language)
      (invalid-language! parent-language :parent))
    (when-not (resources/resource-pair? (:resources parent))
      (throw (ex-info "pair-hint: parent resources are not a complete pair"
                      {:error :PAIR_HINT_RESOURCE_MISSING
                       :language parent-language})))
    (when (some? task-language)
      (let [task-language (or (normalize-language task-language)
                              (invalid-language! task-language :task))]
        (when (not= task-language parent-language)
          (throw (ex-info "pair-hint: child language must inherit the task language"
                          {:error :PAIR_HINT_LANGUAGE_INHERITANCE_CONFLICT
                           :parent parent-language
                           :task task-language})))))
    {:language parent-language
     :source :inherited
     :language-source :inherited
     :parent-language parent-language
     :resources (:resources parent)
     :language-bound? true}))

(defn child-session
  "Create a child session bound to the parent's language."
  ([parent] (child-session parent nil {}))
  ([parent task-language] (child-session parent task-language {}))
  ([parent task-language opts]
   (merge (inherit-language parent task-language)
          (select-keys opts [:session-id :task-id]))))
