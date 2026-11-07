(ns hc.server.search.providers.searxng
  "SearXNG JSON search.

  The configured endpoint is the full `/search` URL.  SearXNG accepts no
  credential for a public instance; a private instance that needs one is not
  supported here, and the adapter does not invent an authentication scheme."
  (:require [clojure.string :as str]
            [hc.server.search.errors :as errors]
            [hc.server.search.http :as http]
            [hc.server.search.providers.common :as common]
            [hc.server.search.providers.protocol :as protocol]))

(defn- safe-search
  [value]
  (case value
    :off "0"
    :strict "2"
    "1"))

(defn- time-range
  [value]
  (case value
    :day "day"
    :week "week"
    :month "month"
    :year "year"
    nil))

(defn- date-filter
  [requested]
  (cond
    (nil? requested)
    (common/date-filter nil :none nil nil)

    (contains? #{:day :week :month :year} requested)
    (common/date-filter requested :time-range nil nil)

    :else
    (common/date-filter requested :none nil
                        (str "SearXNG cannot apply an exact date range; "
                             "the query text was not rewritten"))))

(defrecord SearxngProvider [client]
  protocol/SearchProvider
  (-search [_ service request]
    (let [preferences (common/effective-preferences service request)
          params {"q" (:query request)
                  "format" "json"
                  "pageno" "1"
                  "language" (:language preferences)
                  "safesearch" (safe-search (:safe-search preferences))
                  "time_range" (time-range (:date-range preferences))
                  "categories" (some->> (:categories preferences)
                                        (str/join ","))
                  "engines" (some->> (:engines preferences)
                                     (str/join ","))}
          uri (common/query-uri (:base-url service) params)
          response (http/request-json!
                    {:client client
                     :method "GET"
                     :uri uri
                     :headers {"accept" "application/json"
                               "user-agent" "Herdchestra/0.1 search"}
                     :timeout-ms (:timeout-ms service)
                     :cancelled? (:cancelled? request)
                     :service-id (:id service)})]
      (if-not (:ok? response)
        response
        (let [body (:value response)
              error (common/response-error body (:id service))
              raws (:results body)]
          (cond
            error
            error

            (not (map? body))
            (errors/refusal :SEARCH_RESPONSE_INVALID
                            "the search service response is not an object"
                            {:service-id (:id service)
                             :http-status (:status response)})

            (not (contains? body :results))
            (errors/refusal :SEARCH_RESPONSE_INVALID
                            "the search service response has no results list"
                            {:service-id (:id service)
                             :http-status (:status response)})

            (not (or (nil? raws) (sequential? raws)))
            (errors/refusal :SEARCH_RESPONSE_INVALID
                            "the search service results list is not a sequence"
                            {:service-id (:id service)
                             :http-status (:status response)})

            :else
            (let [raws (vec (take (:count preferences) raws))
                  normalized (common/normalize-results
                              service request
                              (map (fn [raw]
                                     {:url (:url raw)
                                      :title (:title raw)
                                      :snippet (:content raw)
                                      :published-at (:publishedDate raw)})
                                   raws))]
              {:ok? true
               :http-status (:status response)
               :results (:results normalized)
               :documents (:documents normalized)
               :invalid-count (:invalid-count normalized)
               :date-filter (date-filter (:date-range preferences))})))))))

(defn provider
  [client]
  (->SearxngProvider client))
