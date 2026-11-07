(ns hc.server.search.config
  "Configuration for independent web-search services.

  Authority: root AGENTS.md §5.5 and §7.4 旅程 6.1（搜索）.

  A service type is explicit.  It is never inferred from a display name.
  Credentials live in `:credentials`, never in a URL; a configured endpoint
  with a query string is refused because the service list would then become a
  second secret store.

  A service may be saved while a required credential is missing.  Saving is
  not testing and does not contact the service.  `run!` reports the missing
  credential instead of falling back to another service."
  (:require [clojure.string :as str]
            [hc.server.search.errors :as errors])
  (:import (java.net URI)))

(def service-kinds
  "Provider request shapes this package implements."
  #{:searxng :brave :tavily})

(def key-rotations
  "The only multi-key policy a user can enable.

  `:none` uses the first configured key only.  `:on-auth-failure` may try
  each configured key once, in order, and never retries a key."
  #{:none :on-auth-failure})

(def safe-search-values #{:off :moderate :strict})
(def search-depths #{:basic :advanced})
(def date-range-units #{:day :week :month :year})

(def default-preferences
  {:result-count 8
   :language nil
   :country nil
   :safe-search :moderate
   :date-range nil
   :search-depth :basic
   :include-raw-content? false
   :categories nil
   :engines nil})

(def ^:private draft-preference-keys
  "Only these preference fields are editable in a failed draft.

  A draft must not become a second, unvalidated secret store: unknown
  preference keys are dropped instead of echoed back to the settings page."
  [:result-count :language :country :safe-search :date-range :search-depth
   :include-raw-content? :categories :engines])

(def default-timeout-ms 8000)
(def max-result-count 50)
(def max-timeout-ms 60000)
(def min-timeout-ms 1000)

(defn- invalid
  [code message details]
  {:ok? false
   :error (errors/error code message details)})

(defn- http-url
  "Parse an endpoint URL that carries no user-info, query or fragment."
  [value]
  (when (errors/non-blank-string? value)
    (try
      (let [uri (URI. value)
            scheme (some-> (.getScheme uri) str/lower-case)]
        (when (and (contains? #{"http" "https"} scheme)
                   (not (str/blank? (.getHost uri)))
                   (nil? (.getUserInfo uri))
                   (nil? (.getRawQuery uri))
                   (nil? (.getRawFragment uri)))
          uri))
      (catch Exception _ nil))))

(defn valid-endpoint?
  [value]
  (some? (http-url value)))

(defn- normalize-key
  [raw index]
  (if-not (map? raw)
    (invalid :SEARCH_CREDENTIAL_INVALID
             "a credential key must be a map"
             {:index index})
    (let [alias (or (errors/trim-to-nil (:alias raw))
                    (str "key-" (inc index)))
          env-name (errors/trim-to-nil (:api-key-env raw))
          inline (:api-key raw)]
      (cond
        (and (some? env-name) (some? inline))
        (invalid :SEARCH_CREDENTIAL_INVALID
                 "a credential key names either :api-key-env or :api-key"
                 {:alias alias})

        (and (nil? env-name) (nil? inline))
        (invalid :SEARCH_CREDENTIAL_INVALID
                 "a credential key needs :api-key-env or :api-key"
                 {:alias alias})

        (and (some? inline) (not (errors/non-blank-string? inline)))
        (invalid :SEARCH_CREDENTIAL_INVALID
                 "an inline credential must be a non-blank string"
                 {:alias alias})

        :else
        {:ok? true
         :key (cond-> {:alias alias}
                env-name (assoc :source :env :env-name env-name)
                (some? inline) (assoc :source :inline :value inline))}))))

(defn- normalize-credentials
  [raw]
  (cond
    (nil? raw)
    {:ok? true :credentials {:keys [] :rotation :none}}

    (not (map? raw))
    (invalid :SEARCH_CREDENTIAL_INVALID
             "credentials must be a map"
             {:class (some-> raw class .getName)})

    :else
    (let [rotation (or (:rotation raw) :none)
          top-level (cond-> []
                      (some? (:api-key-env raw))
                      (conj {:alias "default" :api-key-env (:api-key-env raw)})
                      (some? (:api-key raw))
                      (conj {:alias "default" :api-key (:api-key raw)}))
          listed (:keys raw)
          raw-keys (cond
                     (and (seq top-level) (some? listed))
                     ::ambiguous
                     (seq top-level) top-level
                     (some? listed) listed
                     :else [])]
      (cond
        (not (contains? key-rotations rotation))
        (invalid :SEARCH_CREDENTIAL_INVALID
                 "credential rotation is unknown"
                 {:rotation rotation
                  :allowed (vec key-rotations)})

        (= ::ambiguous raw-keys)
        (invalid :SEARCH_CREDENTIAL_INVALID
                 "credentials must use either top-level fields or :keys"
                 {})

        (not (sequential? raw-keys))
        (invalid :SEARCH_CREDENTIAL_INVALID
                 "credential :keys must be a sequence"
                 {:keys listed})

        :else
        (let [normalized (mapv #(normalize-key %1 %2)
                               raw-keys
                               (range))
              failed (first (remove :ok? normalized))
              keys (mapv :key normalized)
              aliases (map :alias keys)]
          (cond
            failed
            failed

            (not= (count aliases) (count (distinct aliases)))
            (invalid :SEARCH_CREDENTIAL_INVALID
                     "credential aliases must be unique"
                     {:aliases aliases})

            :else
            {:ok? true
             :credentials {:keys keys :rotation rotation}}))))))

(defn- normalize-date-range
  [value]
  (cond
    (nil? value)
    {:ok? true :value nil}

    (contains? date-range-units value)
    {:ok? true :value value}

    (and (map? value)
         (errors/non-blank-string? (:from value))
         (errors/non-blank-string? (:to value)))
    {:ok? true :value {:from (:from value) :to (:to value)}}

    :else
    (invalid :SEARCH_PREFERENCE_INVALID
             "date-range must be :day, :week, :month, :year or {:from .. :to ..}"
             {:date-range value})))

(defn- string-list
  [value]
  (cond
    (nil? value) {:ok? true :value nil}
    (string? value) {:ok? true :value [value]}
    (sequential? value)
    (if (every? errors/non-blank-string? value)
      {:ok? true :value (vec value)}
      (invalid :SEARCH_PREFERENCE_INVALID
               "list entries must be non-blank strings"
               {:value value}))
    :else
    (invalid :SEARCH_PREFERENCE_INVALID
             "value must be a string or a sequence of strings"
             {:value value})))

(defn- normalize-preferences
  [raw]
  (if (and (some? raw) (not (map? raw)))
    (invalid :SEARCH_PREFERENCE_INVALID
             "preferences must be a map"
             {:class (some-> raw class .getName)})
    (let [raw (or raw {})
          result-count (or (:result-count raw)
                           (:result-count default-preferences))
          safe-search (or (:safe-search raw)
                          (:safe-search default-preferences))
          safe-search (if (string? safe-search)
                        (keyword (str/lower-case safe-search))
                        safe-search)
          search-depth (or (:search-depth raw)
                           (:search-depth default-preferences))
          search-depth (if (string? search-depth)
                         (keyword (str/lower-case search-depth))
                         search-depth)
          date-range (normalize-date-range (:date-range raw))
          categories (string-list (:categories raw))
          engines (string-list (:engines raw))]
      (cond
        (not (and (integer? result-count)
                  (<= 1 result-count max-result-count)))
        (invalid :SEARCH_PREFERENCE_INVALID
                 (str "result-count must be between 1 and " max-result-count)
                 {:result-count result-count})

        (not (contains? safe-search-values safe-search))
        (invalid :SEARCH_PREFERENCE_INVALID
                 "safe-search is unknown"
                 {:safe-search safe-search
                  :allowed (vec safe-search-values)})

        (not (contains? search-depths search-depth))
        (invalid :SEARCH_PREFERENCE_INVALID
                 "search-depth is unknown"
                 {:search-depth search-depth
                  :allowed (vec search-depths)})

        (and (some? (:include-raw-content? raw))
             (not (instance? Boolean (:include-raw-content? raw))))
        (invalid :SEARCH_PREFERENCE_INVALID
                 "include-raw-content? must be boolean"
                 {:include-raw-content? (:include-raw-content? raw)})

        (not (:ok? date-range))
        date-range

        (not (:ok? categories))
        categories

        (not (:ok? engines))
        engines

        (and (some? (:language raw))
             (not (errors/non-blank-string? (:language raw))))
        (invalid :SEARCH_PREFERENCE_INVALID
                 "language must be a non-blank string when present"
                 {:language (:language raw)})

        (and (some? (:country raw))
             (not (errors/non-blank-string? (:country raw))))
        (invalid :SEARCH_PREFERENCE_INVALID
                 "country must be a non-blank string when present"
                 {:country (:country raw)})

        :else
        {:ok? true
         :preferences (merge default-preferences
                             (select-keys raw [:language :country])
                             {:result-count result-count
                              :safe-search safe-search
                              :search-depth search-depth
                              :include-raw-content?
                              (boolean (:include-raw-content? raw false))
                              :date-range (:value date-range)
                              :categories (:value categories)
                              :engines (:value engines)})}))))

(defn- requires-credential?
  [kind]
  (contains? #{:brave :tavily} kind))

(defn- draft-endpoint
  "A display-safe endpoint for an invalid draft: no user-info, query or
  fragment, because those are the places a pasted URL carries a secret."
  [value]
  (try
    (let [uri (URI. (str value))
          scheme (.getScheme uri)
          host (.getHost uri)
          port (.getPort uri)]
      (when (and scheme host)
        (str scheme "://" host
             (when (pos? port) (str ":" port))
             (or (.getPath uri) ""))))
    (catch Exception _ nil)))

(defn- draft
  "Keep the editable service fields but never keep an inline secret.

  The settings page can show that a credential was configured; it cannot
  read the value back from a failed draft.  A URL query or user-info is
  removed for the same reason."
  [raw id]
  (let [base (select-keys raw [:name :kind :base-url :enabled? :timeout-ms])
        preferences (:preferences raw)]
    (cond-> (assoc base :id id)
      (map? preferences)
      (assoc :preferences (select-keys preferences draft-preference-keys))

      (contains? base :base-url)
      (assoc :base-url (draft-endpoint (:base-url base))))))

(defn- normalize-service
  [raw index]
  (if-not (map? raw)
    (invalid :SEARCH_SERVICE_INVALID
             "a search service must be a map"
             {:index index})
    (let [id (errors/trim-to-nil (:id raw))
          name (or (errors/trim-to-nil (:name raw)) id)
          kind (:kind raw)
          base-url (errors/trim-to-nil (:base-url raw))
          timeout-ms (or (:timeout-ms raw) default-timeout-ms)
          credentials (normalize-credentials (:credentials raw))
          preferences (normalize-preferences (:preferences raw))]
      (cond
        (nil? id)
        (assoc (invalid :SEARCH_SERVICE_INVALID
                        "a search service needs a non-blank :id"
                        {:index index})
               :draft (draft raw (str "draft-" (inc index))))

        (not (keyword? kind))
        (assoc (invalid :SEARCH_SERVICE_KIND_UNKNOWN
                        "service type must be an explicit keyword"
                        {:service-id id :kind kind})
               :draft (draft raw id))

        (not (contains? service-kinds kind))
        (assoc (invalid :SEARCH_SERVICE_KIND_UNKNOWN
                        "service type is not implemented"
                        {:service-id id
                         :kind kind
                         :allowed (vec (sort service-kinds))})
               :draft (draft raw id))

        (nil? (http-url base-url))
        (assoc (invalid :SEARCH_SERVICE_URL_INVALID
                        (str "base-url must be an http(s) endpoint without "
                             "user-info, query or fragment")
                        {:service-id id
                         :reason (str "http(s) endpoint without user-info, "
                                      "query or fragment")})
               :draft (draft raw id))

        (not (and (integer? timeout-ms)
                  (<= min-timeout-ms timeout-ms max-timeout-ms)))
        (assoc (invalid :SEARCH_SERVICE_INVALID
                        (str "timeout-ms must be between " min-timeout-ms
                             " and " max-timeout-ms)
                        {:service-id id :timeout-ms timeout-ms})
               :draft (draft raw id))

        (and (some? (:enabled? raw))
             (not (instance? Boolean (:enabled? raw))))
        (assoc (invalid :SEARCH_SERVICE_INVALID
                        "enabled? must be boolean"
                        {:service-id id :enabled? (:enabled? raw)})
               :draft (draft raw id))

        (not (:ok? credentials))
        (assoc (update credentials :error assoc :details
                        {:service-id id})
               :draft (draft raw id))

        (not (:ok? preferences))
        (assoc (update preferences :error assoc :details
                        {:service-id id})
               :draft (draft raw id))

        :else
        {:ok? true
         :service {:id id
                   :name (or name id)
                   :kind kind
                   :base-url base-url
                   :enabled? (not (false? (:enabled? raw)))
                   :timeout-ms (long timeout-ms)
                   :requires-credential? (requires-credential? kind)
                   :credentials (:credentials credentials)
                   :preferences (:preferences preferences)}}))))

(defn normalize
  "Validate a `:search` configuration map.

  Returns:

    {:ok? true|false
     :config {:services [...] :default-service-id ...}
     :errors [error ...]}

  Invalid services remain in `:config :services` as drafts with `:invalid?
  true` and their own `:error`, so the settings page can show what to fix.
  A duplicate ID invalidates every service that uses it; the adapter never
  chooses one arbitrarily."
  [raw]
  (let [raw (if (map? raw) raw {})
        raw-services (:services raw)]
    (if (and (some? raw-services) (not (sequential? raw-services)))
      {:ok? false
       :config {:services [] :default-service-id (:default-service-id raw)}
       :errors [(errors/error :SEARCH_CONFIG_INVALID
                              ":services must be a sequence"
                              {:services raw-services})]}
      (let [normalized (mapv #(normalize-service %1 %2)
                             (or raw-services [])
                             (range))
            services (mapv (fn [result]
                             (if (:ok? result)
                               (:service result)
                               (assoc (:draft result)
                                      :invalid? true
                                      :error (:error result))))
                           normalized)
            counts (frequencies (map :id services))
            duplicate-ids (into #{}
                                (keep (fn [[id n]] (when (> n 1) id)))
                                counts)
            duplicate-error (when (seq duplicate-ids)
                              (errors/error
                               :SEARCH_SERVICE_ID_DUPLICATE
                               "search service IDs must be unique"
                               {:ids (vec (sort duplicate-ids))}))
            services (mapv (fn [service]
                             (if (contains? duplicate-ids (:id service))
                               (assoc service
                                      :invalid? true
                                      :error duplicate-error)
                               service))
                           services)
            errors (cond-> (vec (keep :error normalized))
                     duplicate-error (conj duplicate-error))]
        {:ok? (empty? errors)
         :config {:services services
                  :default-service-id (:default-service-id raw)}
         :errors errors}))))

(defn service-by-id
  "Find a configured service by exact ID.  Returns nil when absent.

  This function never selects a default and never picks the first enabled
  service."
  [normalized id]
  (when (errors/non-blank-string? id)
    (first (filter #(= id (:id %))
                   (get-in normalized [:config :services])))))

(defn configured-service-ids
  [normalized]
  (mapv :id (get-in normalized [:config :services])))

(defn- credential-value
  [key env]
  (case (:source key)
    :env (get env (:env-name key))
    :inline (:value key)
    nil))

(defn resolve-credentials
  "Resolve configured credential references against ENV.

  Returns every configured key with its value available to the adapter and a
  secret-free view for errors.  The caller chooses how many keys to try;
  this function never rotates by itself."
  [service env]
  (let [credentials (:credentials service)
        keys (mapv (fn [key]
                     (assoc key :value (credential-value key env)))
                   (:keys credentials))
        usable (filterv #(errors/non-blank-string? (:value %)) keys)
        missing (mapv #(select-keys % [:alias :source :env-name])
                      (remove #(errors/non-blank-string? (:value %)) keys))]
    {:rotation (:rotation credentials)
     :keys keys
     :usable usable
     :missing missing
     :configured? (boolean (seq keys))
     :error (when (and (seq keys) (empty? usable))
              (errors/error :SEARCH_CREDENTIAL_MISSING
                            "no configured credential is available"
                            {:missing missing}))}))

(defn public-credentials
  "A secret-free credential view for settings and diagnostics."
  [credentials env]
  (let [keys (:keys credentials)]
    {:configured? (boolean (seq keys))
     :available? (boolean (some #(errors/non-blank-string?
                                  (credential-value % env))
                                keys))
     :source (cond
               (empty? keys) :none
               (every? #(= :env (:source %)) keys) :env
               (every? #(= :inline (:source %)) keys) :inline
               :else :mixed)
     :env-names (vec (keep :env-name keys))
     :key-aliases (mapv :alias keys)
     :rotation (:rotation credentials)
     :keys (mapv (fn [key]
                   (cond-> {:alias (:alias key)
                            :source (:source key)
                            :available? (errors/non-blank-string?
                                         (credential-value key env))}
                     (= :env (:source key))
                     (assoc :env-name (:env-name key))))
                 keys)}))

(defn public-service
  "A service view that is safe to send to the browser.

  Inline secret values are removed.  The endpoint has no query string by
  validation, so the displayed address is also the real target."
  [service env]
  (-> service
      (dissoc :credentials)
      (assoc :credentials (public-credentials (:credentials service) env))))

(defn public-config
  "A secret-free view of a normalized search configuration."
  [normalized env]
  (-> normalized
      (update-in [:config :services]
                 (fn [services]
                   (mapv #(if (:invalid? %)
                            (-> %
                                (dissoc :credentials :draft)
                                (assoc :credentials
                                       (public-credentials
                                        (or (:credentials %) {:keys []})
                                        env)))
                            (public-service % env))
                         services)))))
