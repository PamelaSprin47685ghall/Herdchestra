(ns hc.server.search.providers.brave
  "Brave Search API.

  The subscription token is sent only to the configured endpoint.  The
  human-readable `age` field is not a publication date; only `page_age` is
  accepted, because it is the machine-readable timestamp Brave returns."
  (:require [hc.server.search.errors :as errors]
            [hc.server.search.http :as http]
            [hc.server.search.providers.common :as common]
            [hc.server.search.providers.protocol :as protocol]))

(def ^:private max-brave-results 20)

(defn- safe-search
  [value]
  (case value
    :off "off"
    :strict "strict"
    "moderate"))

(defn- freshness
  [requested]
  (cond
    (nil? requested)
    (common/date-filter nil :none nil nil)

    (= requested :day)
    (common/date-filter requested :freshness "pd" nil)

    (= requested :week)
    (common/date-filter requested :freshness "pw" nil)

    (= requested :month)
    (common/date-filter requested :freshness "pm" nil)

    (= requested :year)
    (common/date-filter requested :freshness "py" nil)

    (and (map? requested) (:from requested) (:to requested))
    (common/date-filter requested :freshness
                        (str (:from requested) "to" (:to requested))
                        nil)

    :else
    (common/date-filter requested :none nil
                        (str "Brave needs both dates for an exact range; "
                             "the query text was not rewritten"))))

(defrecord BraveProvider [client]
  protocol/SearchProvider
  (-search [_ service request]
    (if-not (errors/non-blank-string? (:api-key request))
      (errors/refusal :SEARCH_CREDENTIAL_MISSING
                      "the search service has no available credential"
                      {:service-id (:id service)})
      (let [preferences (common/effective-preferences service request)
            filter (freshness (:date-range preferences))
            count (min max-brave-results (:count preferences))
            params {"q" (:query request)
                    "count" (str count)
                    "search_lang" (:language preferences)
                    "country" (:country preferences)
                    "safesearch" (safe-search (:safe-search preferences))
                    "freshness" (:freshness filter)}
            uri (common/query-uri (:base-url service) params)
            response (http/request-json!
                      {:client client
                       :method "GET"
                       :uri uri
                       :headers {"accept" "application/json"
                                 "x-subscription-token" (:api-key request)
                                 "user-agent" "Herdchestra/0.1 search"}
                       :timeout-ms (:timeout-ms service)
                       :cancelled? (:cancelled? request)
                       :service-id (:id service)})]
        (if-not (:ok? response)
          response
          (let [body (:value response)
                error (common/response-error body (:id service))
                web (:web body)
                raws (when (map? web) (:results web))]
            (cond
              error
              error

              (and (some? web) (not (map? web)))
              (errors/refusal :SEARCH_RESPONSE_INVALID
                              "the search service web member is not an object"
                              {:service-id (:id service)
                               :http-status (:status response)})

              (and (some? raws) (not (sequential? raws)))
              (errors/refusal :SEARCH_RESPONSE_INVALID
                              "the search service response has no results list"
                              {:service-id (:id service)
                               :http-status (:status response)})

              :else
              (let [normalized (common/normalize-results
                                service request
                                (map (fn [raw]
                                       {:url (:url raw)
                                        :title (:title raw)
                                        :snippet (:description raw)
                                        :published-at (:page_age raw)})
                                     (take (:count preferences)
                                           (or raws []))))]
                {:ok? true
                 :http-status (:status response)
                 :results (:results normalized)
                 :documents (:documents normalized)
                 :invalid-count (:invalid-count normalized)
                 :date-filter filter}))))))))

(defn provider
  [client]
  (->BraveProvider client))
