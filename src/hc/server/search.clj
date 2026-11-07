(ns hc.server.search
  "Configured web search and on-demand retrieval.

  Authority: root AGENTS.md §5.5 and §7.4 旅程 6.1（搜索）.

  The adapter contacts exactly one explicitly selected independent search
  service.  It never selects a default, never tries another enabled service
  after a failure, and never reports a failed required search as success.

  Search references remain `hc.server.materials.search` values: duplicate
  canonical URLs merge there, publication time stays separate from fetch
  time, and citation numbers belong to the message.  Full text is kept in a
  separate `:documents` map because a reference set intentionally carries no
  page body.

  Usage:

    (def svc (search/service {:config search-config}))

    (search/run! svc
                 {:service-id \"brave-main\"
                  :mode :required
                  :queries [{:query-id \"q1\" :text \"...\"}]
                  :refs (search/new-reference-set)})

  The caller supplies the real query ids, the selected service id and any
  cancellation flag.  This namespace reads a clock only through the injected
  `:clock`."
  (:refer-clojure :exclude [run!])
  (:require [clojure.string :as str]
            [hc.server.materials.search :as refs]
            [hc.server.search.config :as config]
            [hc.server.search.errors :as errors]
            [hc.server.search.http :as http]
            [hc.server.search.providers :as providers]
            [hc.server.search.providers.protocol :as protocol]
            [hc.server.search.retrieve :as retrieve])
  (:import (java.time Instant)))

(def modes
  "`关闭 / 自动 / 本轮必须搜索`.  A caller that invokes this adapter has
  already decided that search is allowed; `:off` exists so the same request
  shape can be recorded without a network call."
  #{:off :auto :required})

(def test-query "herdchestra search connection test")
(def max-query-chars 4000)

(defn service
  "Create a search service.

  Options:

    :config               the `:search` section of the application config
    :env                  credential environment map (default System/getenv)
    :clock                zero-argument function returning an ISO string
    :http-client          java.net.http.HttpClient
    :allow-private-hosts? permit retrieval from private addresses (tests and
                          explicitly configured local environments only)"
  [{:keys [config env clock http-client allow-private-hosts?]}]
  {:config (config/normalize config)
   :env (or env (System/getenv))
   :clock (or clock (fn [] (str (Instant/now))))
   :client (or http-client (http/default-client))
   :allow-private-hosts? (boolean allow-private-hosts?)
   :tests (atom {})})

(defn public-config
  "A secret-free view of the configured services.

  An in-process last-test summary is attached when `test-service!` ran.  It
  contains status, time, HTTP status and a secret-free error, never a
  credential."
  [service]
  (let [public (config/public-config (:config service) (:env service))
        tests (if-let [tests (:tests service)] @tests {})]
    (if (empty? tests)
      public
      (update-in public [:config :services]
                 (fn [services]
                   (mapv (fn [configured]
                           (if-let [last-test (get tests (:id configured))]
                             (assoc configured :last-test last-test)
                             configured))
                         services))))))

(defn list-services
  [service]
  (get-in (public-config service) [:config :services]))

(defn configured-service-ids
  [service]
  (config/configured-service-ids (:config service)))

(defn canonical-url
  "The reference-set URL identity, re-exported for callers."
  [url]
  (refs/canonical-url url))

;; Reference-set helpers, so a caller does not need two namespaces.
(def new-reference-set refs/new-set)
(def add-search-results refs/add-results)
(def cite-search-source refs/cite)
(def search-citation-number refs/citation-number)
(def search-citation-marker refs/citation-marker)
(def search-source-list refs/source-list)
(def search-plain-text refs/plain-text)
(def check-search-references refs/check)

;; ---------------------------------------------------------------------------
;; Time and cancellation

(defn- now
  [service]
  (let [value ((:clock service))]
    (if (string? value) value (str value))))

(defn- cancelled?
  [value]
  (cond
    (nil? value) false
    (fn? value) (boolean (value))
    (instance? clojure.lang.IDeref value) (boolean @value)
    :else (boolean value)))

;; ---------------------------------------------------------------------------
;; Request validation

(defn- query-error
  [message details]
  (errors/error :SEARCH_QUERY_INVALID message details))

(defn- normalize-queries
  [queries]
  (let [queries (if (sequential? queries) (vec queries) nil)]
    (cond
      (nil? queries)
      {:ok? false :error (query-error "queries must be a sequence"
                                      {:queries queries})}

      (empty? queries)
      {:ok? false :error (query-error "at least one query is required"
                                      {})}

      :else
      (let [normalized
            (mapv (fn [index query]
                    (if-not (map? query)
                      {:ok? false
                       :error (query-error "a query must be a map"
                                           {:index index})}
                      (let [query-id (:query-id query)
                            text (:text query)
                            query-count (:count query)
                            safe-search (let [value (:safe-search query)]
                                          (if (string? value)
                                            (keyword (str/lower-case value))
                                            value))
                            date-range (:date-range query)]
                        (cond
                          (not (errors/non-blank-string? query-id))
                          {:ok? false
                           :error (query-error
                                   "query-id must be a non-blank string"
                                   {:index index :query-id query-id})}

                          (not (errors/non-blank-string? text))
                          {:ok? false
                           :error (query-error
                                   "query text must be a non-blank string"
                                   {:index index :query-id query-id})}

                          (> (count text) max-query-chars)
                          {:ok? false
                           :error (query-error
                                   (str "query text exceeds " max-query-chars
                                        " characters")
                                   {:index index
                                    :query-id query-id
                                    :length (count text)})}

                          (and (some? query-count)
                               (not (and (integer? query-count)
                                         (<= 1 query-count 50))))
                          {:ok? false
                           :error (query-error
                                   "query count must be an integer between 1 and 50"
                                   {:index index
                                    :query-id query-id
                                    :count query-count})}

                          (and (some? (:language query))
                               (not (errors/non-blank-string?
                                     (:language query))))
                          {:ok? false
                           :error (query-error
                                   "query language must be a non-blank string"
                                   {:index index :query-id query-id})}

                          (and (some? (:country query))
                               (not (errors/non-blank-string?
                                     (:country query))))
                          {:ok? false
                           :error (query-error
                                   "query country must be a non-blank string"
                                   {:index index :query-id query-id})}

                          (and (some? safe-search)
                               (not (contains? config/safe-search-values
                                               safe-search)))
                          {:ok? false
                           :error (query-error
                                   "query safe-search is unknown"
                                   {:index index
                                    :query-id query-id
                                    :safe-search (:safe-search query)})}

                          (and (some? date-range)
                               (not (or (contains? config/date-range-units
                                                   date-range)
                                        (and (map? date-range)
                                             (errors/non-blank-string?
                                              (:from date-range))
                                             (errors/non-blank-string?
                                              (:to date-range))))))
                          {:ok? false
                           :error (query-error
                                   "query date-range is invalid"
                                   {:index index
                                    :query-id query-id
                                    :date-range date-range})}

                          (and (some? (:include-raw-content? query))
                               (not (instance? Boolean
                                               (:include-raw-content? query))))
                          {:ok? false
                           :error (query-error
                                   "query include-raw-content? must be boolean"
                                   {:index index :query-id query-id})}

                          (and (some? (:fetched-at query))
                               (not (errors/non-blank-string?
                                     (:fetched-at query))))
                          {:ok? false
                           :error (query-error
                                   "query fetched-at must be a non-blank string"
                                   {:index index :query-id query-id})}

                          :else
                          {:ok? true
                           :query (assoc
                                   (select-keys
                                    query
                                    [:query-id :text :count :language :country
                                     :date-range :include-raw-content?
                                     :fetched-at])
                                   :safe-search safe-search)}))))
                  (range)
                  queries)
            failed (first (remove :ok? normalized))
            ids (mapv (comp :query-id :query) (filter :ok? normalized))]
        (cond
          failed
          failed

          (not= (count ids) (count (distinct ids)))
          {:ok? false
           :error (query-error "query ids must be unique"
                               {:query-ids ids})}

          :else
          {:ok? true :queries (mapv :query normalized)})))))

(defn- normalize-mode
  [mode]
  (if (contains? modes mode)
    {:ok? true :mode mode}
    {:ok? false
     :error (errors/error :SEARCH_MODE_INVALID
                          "search mode must be :off, :auto or :required"
                          {:mode mode :allowed (vec (sort modes))})}))

;; ---------------------------------------------------------------------------
;; Service and credential selection

(defn- service-summary
  [service env]
  (when service
    (-> (select-keys service
                     [:id :name :kind :base-url :enabled?
                      :timeout-ms :requires-credential?])
        (assoc :credentials
               (config/public-credentials (:credentials service) env)))))

(defn- select-service
  "Resolve exactly the service named by the request.

  This function never considers another service, even when one is enabled
  and the selected service is disabled or broken."
  [service request]
  (let [id (:service-id request)]
    (cond
      (not (errors/non-blank-string? id))
      {:ok? false
       :service nil
       :error (errors/error :SEARCH_SERVICE_NOT_SELECTED
                            "no search service is selected for this turn"
                            {})}

      :else
      (if-let [configured (config/service-by-id (:config service) id)]
        (cond
          (:invalid? configured)
          {:ok? false
           :service configured
           :error (or (:error configured)
                      (errors/error :SEARCH_SERVICE_INVALID
                                    "the selected search service is invalid"
                                    {:service-id id}))}

          (not (:enabled? configured))
          {:ok? false
           :service configured
           :error (errors/error :SEARCH_SERVICE_DISABLED
                                "the selected search service is disabled"
                                {:service-id id})}

          :else
          {:ok? true :service configured})

        {:ok? false
         :service nil
         :error (errors/error :SEARCH_SERVICE_NOT_FOUND
                              "the selected search service is not configured"
                              {:service-id id})}))))

(defn- credential-missing
  [service alias missing]
  (errors/error :SEARCH_CREDENTIAL_MISSING
                "the selected search service has no available credential"
                (cond-> {:service-id (:id service)}
                  alias (assoc :alias alias)
                  (seq missing) (assoc :missing missing))))

(defn- candidate-keys
  "Return the keys to try, in order.

  `:none` uses only the first configured key.  `:on-auth-failure` may try
  each configured key once; it never retries the same key."
  [service env]
  (if-not (:requires-credential? service)
    {:ok? true :keys [nil]}
    (let [resolved (config/resolve-credentials service env)
          rotation (:rotation resolved)]
      (if (= :on-auth-failure rotation)
        (if (seq (:usable resolved))
          {:ok? true :keys (:usable resolved)}
          {:ok? false
           :error (credential-missing service nil (:missing resolved))})
        (let [first-key (first (:keys resolved))]
          (if (and first-key (errors/non-blank-string? (:value first-key)))
            {:ok? true :keys [first-key]}
            {:ok? false
             :error (credential-missing service
                                        (:alias first-key)
                                        (:missing resolved))}))))))

(defn- auth-failure?
  [result]
  (and (not (:ok? result))
       (= :SEARCH_CREDENTIAL_INVALID (get-in result [:error :code]))))

(defn- attempt-keys
  "Call the provider with one key, or with each key once under the explicit
  `:on-auth-failure` policy."
  [provider service request keys]
  (loop [remaining (vec (if (seq keys) keys [nil]))
         key-errors []]
    (if (empty? remaining)
      {:ok? false
       :error (credential-missing service nil [])}
      (let [key (first remaining)
            result (protocol/-search
                    provider service
                    (cond-> request
                      key (assoc :api-key (:value key)
                                 :key-alias (:alias key))))
            more? (seq (rest remaining))]
        (if (and (auth-failure? result) more?)
          (recur (rest remaining)
                 (conj key-errors
                       {:key-alias (:alias key)
                        :error (:error result)}))
          (cond-> result
            key (assoc :key-alias (:alias key))
            (seq key-errors) (assoc :key-errors key-errors)))))))

;; ---------------------------------------------------------------------------
;; Documents and provenance

(defn- merge-documents
  [documents new-documents]
  (reduce-kv
   (fn [merged canonical document]
     (let [existing (get merged canonical)]
       (if (and existing
                (>= (long (or (:length existing) 0))
                    (long (or (:length document) 0))))
         merged
         (assoc merged canonical document))))
   (or documents {})
   (or new-documents {})))

(defn- source-view
  [reference-set canonical]
  (when-let [source (get-in reference-set [:sources canonical])]
    (assoc source :published-at-unknown? (nil? (:published-at source)))))

(defn- document-view
  [document]
  (when document
    (select-keys document
                 [:url :site :title :content-type :read-at :status :retrieval
                  :truncated? :length])))

(defn- provenance
  [service env queries reference-set touched documents]
  {:service (service-summary service env)
   :queries queries
   :sources (mapv #(source-view reference-set %) touched)
   :documents (mapv #(document-view (get documents %))
                    (filter #(contains? documents %) touched))})

;; ---------------------------------------------------------------------------
;; One query

(defn- query-record
  [query request service status]
  (merge {:query-id (:query-id query)
          :text (:text query)
          :service-id (:id service)
          :status status}
         (select-keys request
                      [:started-at :finished-at :http-status :result-count
                       :valid-count :invalid-count :unique-count
                       :date-filter :key-alias :key-errors :error
                       :attempted?])))

(defn- run-query
  [service provider selected query reference-set documents request]
  (let [query-id (:query-id query)
        started-at (now service)
        fetched-at (or (:fetched-at query) (:fetched-at request)
                       (now service))
        cancelled? (cancelled? (:cancelled? request))]
    (if cancelled?
      {:query (query-record query {:started-at started-at} selected
                             :not-started)
       :refs reference-set
       :documents documents
       :service-usages []
       :touched []
       :success? false
       :cancelled? true}
      (let [provider-request {:query (:text query)
                              :query-id query-id
                              :count (:count query)
                              :language (:language query)
                              :country (:country query)
                              :safe-search (:safe-search query)
                              :date-range (:date-range query)
                              :include-raw-content?
                              (:include-raw-content? query)
                              :fetched-at fetched-at
                              :turn-id (:turn-id request)
                              :session-id (:session-id request)
                              :timeout-ms (:timeout-ms selected)
                              :cancelled? (:cancelled? request)}
            result (attempt-keys provider selected provider-request
                                 (:keys request))]
        (if-not (:ok? result)
          {:query (query-record
                   query
                   {:started-at started-at
                    :finished-at (now service)
                    :attempted? true
                    :key-alias (:key-alias result)
                    :key-errors (:key-errors result)
                    :error (:error result)}
                   selected
                   (if (= :SEARCH_CANCELLED (get-in result [:error :code]))
                     :cancelled
                     :failed))
           :refs reference-set
           :documents documents
           :service-usages []
           :touched []
           :success? false
           :cancelled? (= :SEARCH_CANCELLED
                          (get-in result [:error :code]))}
          (let [added (refs/add-results
                       reference-set
                       {:query-id query-id
                        :fetched-at fetched-at
                        :results (:results result)})]
            (if-not (:ok? added)
              {:query (query-record
                       query
                       {:started-at started-at
                        :finished-at (now service)
                        :attempted? true
                        :key-alias (:key-alias result)
                        :key-errors (:key-errors result)
                        :error (:error added)}
                       selected
                       :failed)
               :refs reference-set
               :documents documents
               :service-usages (vec (:service-usages result))
               :touched []
               :success? false
               :cancelled? false}
              (let [touched (vec (distinct (concat (:added added)
                                                   (:merged added))))
                    result-count (count (:results result))]
                {:query (query-record
                         query
                         {:started-at started-at
                          :finished-at (now service)
                          :attempted? true
                          :http-status (:http-status result)
                          :result-count result-count
                          :valid-count result-count
                          :invalid-count (:invalid-count result)
                          :unique-count (count touched)
                          :date-filter (:date-filter result)
                          :key-alias (:key-alias result)
                          :key-errors (:key-errors result)}
                         selected
                         (if (pos? result-count) :ok :empty))
                 :refs (:refs added)
                 :documents (merge-documents documents (:documents result))
                 :service-usages (vec (:service-usages result))
                 :touched touched
                 :success? true
                 :cancelled? false}))))))))

;; ---------------------------------------------------------------------------
;; Aggregation

(defn- failure-before-queries
  [mode selected service error reference-set documents queries]
  (let [queries (mapv #(assoc % :status :not-started :error error) queries)]
    {:ok? false
     :status :failed
     :mode mode
     :service (service-summary selected (:env service))
     :queries queries
     :refs reference-set
     :documents documents
     :service-usages []
     :provenance (provenance selected (:env service) queries
                             reference-set [] documents)
     :searched? false
     :attempted? false
     :must-search-failed? (= mode :required)
     :cancelled? false
     :error error
     :causes [error]}))

(defn- aggregate
  [service selected mode queries reference-set documents service-usages touched]
  (let [successful (filterv #(contains? #{:ok :empty} (:status %)) queries)
        failed (filterv #(= :failed (:status %)) queries)
        cancelled-queries (filterv #(contains? #{:cancelled :not-started}
                                                (:status %))
                                   queries)
        cancelled? (boolean (seq cancelled-queries))
        status (cond
                 (and cancelled? (empty? successful)) :cancelled
                 (and cancelled? (seq successful)) :partial
                 (empty? successful) :failed
                 (seq failed) :partial
                 (every? #(= :empty (:status %)) successful) :empty
                 :else :ok)
        errors (vec (keep :error failed))
        must-search-failed? (and (= mode :required)
                                 (= status :failed)
                                 (empty? successful))
        ok? (not= status :failed)]
    (cond-> {:ok? ok?
             :status status
             :mode mode
             :service (service-summary selected (:env service))
             :queries queries
             :refs reference-set
             :documents documents
             :service-usages service-usages
             :provenance (provenance selected (:env service) queries
                                     reference-set touched documents)
             :searched? (boolean (seq successful))
             :attempted? (boolean (some :attempted? queries))
             :must-search-failed? must-search-failed?
             :cancelled? cancelled?
             :causes errors}
      (and (not ok?) (seq errors)) (assoc :error (first errors))
      (and (not ok?) (empty? errors))
      (assoc :error (errors/error :SEARCH_ALL_QUERIES_FAILED
                                  "every search query failed"
                                  {:service-id (:id selected)})))))

;; ---------------------------------------------------------------------------
;; Public operations

(defn run!
  "Run the configured search for this turn.

  REQUEST:

    {:service-id \"brave-main\"      ; required unless mode is :off
     :mode :off|:auto|:required     ; required
     :queries [{:query-id \"q1\" :text \"...\"} ...]
     :refs reference-set             ; optional, defaults to an empty set
     :documents {canonical-url ...}  ; optional prior full-text map
     :fetched-at \"...\"             ; optional; defaults to the clock
     :cancelled? atom}               ; optional

  A required search that never obtains a successful service response returns
  `:ok? false`, `:status :failed` and `:must-search-failed? true`.  A
  successful service response with zero results returns `:status :empty`;
  that is not a network failure and the caller must still not claim that
  facts were found.

  The selected service is the only service contacted.  A failure never falls
  back to another enabled service."
  [service request]
  (let [mode-result (normalize-mode (:mode request))
        query-result (normalize-queries (:queries request))
        reference-set (or (:refs request) (refs/new-set))
        documents (or (:documents request) {})]
    (cond
      (not (:ok? mode-result))
      (failure-before-queries (:mode request) nil service
                              (:error mode-result)
                              reference-set documents [])

      (and (not= :off (:mode mode-result))
           (not (:ok? query-result)))
      (failure-before-queries (:mode mode-result) nil service
                              (:error query-result)
                              reference-set documents [])

      (and (some? (:refs request)) (not (map? (:refs request))))
      (failure-before-queries (:mode mode-result) nil service
                              (errors/error :SEARCH_SET_INVALID
                                            "refs must be a reference set"
                                            {})
                              reference-set documents
                              (if (:ok? query-result)
                                (:queries query-result)
                                []))

      (and (some? (:documents request))
           (not (map? (:documents request))))
      (failure-before-queries (:mode mode-result) nil service
                              (errors/error :SEARCH_DOCUMENTS_INVALID
                                            "documents must be a map"
                                            {})
                              reference-set documents
                              (if (:ok? query-result)
                                (:queries query-result)
                                []))

      (= :off (:mode mode-result))
      (let [queries (if (:ok? query-result)
                      (mapv #(assoc % :status :not-started)
                            (:queries query-result))
                      [])]
        {:ok? true
         :status :skipped
         :mode :off
         :service nil
         :queries queries
         :refs reference-set
         :documents documents
         :service-usages []
         :provenance (provenance nil (:env service) queries
                                 reference-set [] documents)
         :searched? false
         :attempted? false
         :must-search-failed? false
         :cancelled? false
         :causes []})

      :else
      (let [selected (select-service service request)]
        (if-not (:ok? selected)
          (failure-before-queries (:mode mode-result)
                                  (:service selected) service
                                  (:error selected)
                                  reference-set documents
                                  (:queries query-result))
          (let [configured (:service selected)
                provider (providers/provider (:kind configured)
                                             (:client service))
                keys-result (candidate-keys configured (:env service))]
            (cond
              (nil? provider)
              (failure-before-queries
               (:mode mode-result) configured service
               (errors/error :SEARCH_SERVICE_KIND_UNKNOWN
                             "the selected service type has no provider"
                             {:service-id (:id configured)
                              :kind (:kind configured)})
               reference-set documents (:queries query-result))

              (not (:ok? keys-result))
              (failure-before-queries
               (:mode mode-result) configured service
               (:error keys-result)
               reference-set documents (:queries query-result))

              :else
              (let [run-one (fn [state query]
                              (let [result (run-query
                                            service provider configured query
                                            (:refs state)
                                            (:documents state)
                                            {:keys (:keys keys-result)
                                             :fetched-at (:fetched-at request)
                                             :turn-id (:turn-id request)
                                             :session-id (:session-id request)
                                             :cancelled? (:cancelled? request)})]
                                {:queries (conj (:queries state)
                                                (:query result))
                                 :refs (:refs result)
                                 :documents (:documents result)
                                 :service-usages (into (:service-usages state)
                                                       (:service-usages result))
                                 :touched (into (:touched state)
                                                (:touched result))}))
                    state (reduce run-one
                                  {:queries [] :refs reference-set
                                   :documents documents :service-usages []
                                   :touched []}
                                  (:queries query-result))]
                (aggregate service configured (:mode mode-result)
                           (:queries state) (:refs state)
                           (:documents state)
                           (vec (distinct (:service-usages state)))
                           (vec (distinct (:touched state))))))))))))

(defn fetch!
  "Fetch the full text of one URL that a search result already returned.

  The URL must be present in `:refs`; this prevents a model from turning
  arbitrary prose into a server-side request.  No search credential is sent
  to the target page.  A blocked, partial or failed read keeps the saved
  title and snippet."
  [service request]
  (if (and (some? (:documents request))
           (not (map? (:documents request))))
    {:ok? false
     :error (errors/error :SEARCH_DOCUMENTS_INVALID
                          "documents must be a map"
                          {})}
    (let [result (retrieve/fetch! service request)]
      (if (and (:ok? result) (nil? (:documents result)))
        (assoc result :documents (or (:documents request) {}))
        result))))

(defn test-service!
  "Run one explicit, small test query against the selected service.

  This is the only operation a settings page may call after an explicit
  \"test connection\" action.  Saving a service never calls it."
  [service request]
  (let [id (:service-id request)
        result (run! service
                     {:service-id id
                      :mode :required
                      :queries [{:query-id "connection-test"
                                 :text test-query
                                 :count 1}]
                      :refs (refs/new-set)
                      :documents {}
                      :fetched-at (now service)
                      :cancelled? (:cancelled? request)})]
    (when (and (errors/non-blank-string? id) (:tests service))
      (swap! (:tests service) assoc id
             {:status (if (:ok? result) :ok :failed)
              :tested-at (now service)
              :http-status (get-in result [:queries 0 :http-status])
              :result-count (get-in result [:queries 0 :result-count])
              :error (when-not (:ok? result) (:error result))}))
    (assoc result
           :test? true
           :service-call-made? (boolean (some :attempted? (:queries result)))
           :notice (str "this test sends one explicit test query; with key "
                        "rotation enabled it may try each configured key once, "
                        "and it is not saved automatically"))))

(defn snapshot
  "The settings-facing search state.  Contains no credential value."
  [service]
  {:search (public-config service)})

(defn handle!
  "Application command handler for the `:search/*` kinds.

  The app router passes the payload through unchanged.  `:service-id` is
  always the client's explicit selection; this function never fills it from a
  default.

    :search/config       {} -> secret-free configuration
    :search/run          run! request
    :search/fetch        fetch! request
    :search/test-service test-service! request
  "
  [service kind payload]
  (case kind
    :search/config
    {:ok? true :search (public-config service)}

    :search/run
    (run! service (or payload {}))

    :search/fetch
    (fetch! service (or payload {}))

    :search/test-service
    (test-service! service (or payload {}))

    {:ok? false
     :error (errors/error :SEARCH_COMMAND_UNKNOWN
                          "unknown search command"
                          {:kind kind
                           :allowed [:search/config :search/run
                                     :search/fetch :search/test-service]})}))
