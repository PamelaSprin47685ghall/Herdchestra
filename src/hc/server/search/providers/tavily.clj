(ns hc.server.search.providers.tavily
  "Tavily Search API.

  Tavily accepts the API key in the JSON body.  That body is never included
  in an error or a diagnostic view.  `raw_content`, when requested, is kept as
  a separate document and is not treated as a citation by itself."
  (:require [clojure.data.json :as json]
            [hc.server.diagnostics.service-usage :as service-usage]
            [hc.server.search.errors :as errors]
            [hc.server.search.http :as http]
            [hc.server.search.providers.common :as common]
            [hc.server.search.providers.protocol :as protocol])
  (:import (java.nio.charset StandardCharsets)))

(def ^:private max-tavily-results 20)

(defn- date-filter
  [requested]
  (cond
    (nil? requested)
    (common/date-filter nil :none nil nil)

    (contains? #{:day :week :month :year} requested)
    (common/date-filter requested :days
                        (case requested
                          :day 1
                          :week 7
                          :month 30
                          :year 365)
                        nil)

    (and (map? requested) (:from requested) (:to requested))
    (common/date-filter requested :start-end
                        {:start_date (:from requested)
                         :end_date (:to requested)}
                        nil)

    :else
    (common/date-filter requested :none nil
                        (str "Tavily needs both dates for an exact range; "
                             "the query text was not rewritten"))))

(defrecord TavilyProvider [client]
  protocol/SearchProvider
  (-search [_ service request]
    (if-not (errors/non-blank-string? (:api-key request))
      (errors/refusal :SEARCH_CREDENTIAL_MISSING
                      "the search service has no available credential"
                      {:service-id (:id service)})
      (let [preferences (common/effective-preferences service request)
            filter (date-filter (:date-range preferences))
            body (cond-> {"api_key" (:api-key request)
                          "query" (:query request)
                          "max_results" (min max-tavily-results
                                             (:count preferences))
                          "search_depth" (name (:search-depth preferences))
                          "include_answer" false
                          "include_raw_content"
                          (:include-raw-content? preferences)
                          "topic" "general"}
                   (= :days (:applied filter))
                   (assoc "days" (:params filter))

                   (= :start-end (:applied filter))
                   (assoc "start_date" (get-in filter [:params :start_date])
                          "end_date" (get-in filter [:params :end_date])))
            response (http/request-json!
                      {:client client
                       :method "POST"
                       :uri (:base-url service)
                       :headers {"accept" "application/json"
                                 "content-type" "application/json"
                                 "user-agent" "Herdchestra/0.1 search"}
                       :body (.getBytes (json/write-str body)
                                        StandardCharsets/UTF_8)
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
              (let [request-id (:request_id body)
                    usage-id (str "tavily:"
                                  (or request-id
                                      (str (:id service) ":"
                                           (:query-id request) "@"
                                           (:fetched-at request))))
                    service-usage
                    (when (number? (get-in body [:usage :credits]))
                      (try
                        (service-usage/search-credits
                         body
                         {:usage-id usage-id
                          :turn-id (:turn-id request)
                          :session-id (:session-id request)
                          :service-id (:id service)
                          :service-name (:name service)
                          :name "search"
                          :at (:fetched-at request)
                          :result :ok})
                        (catch Exception _ nil)))
                    normalized (common/normalize-results
                                service request
                                (map (fn [raw]
                                       {:url (:url raw)
                                        :title (:title raw)
                                        :snippet (:content raw)
                                        :published-at (:published_date raw)
                                        :raw-content (:raw_content raw)})
                                     (take (:count preferences) raws)))]
                {:ok? true
                 :http-status (:status response)
                 :results (:results normalized)
                 :documents (:documents normalized)
                 :invalid-count (:invalid-count normalized)
                 :service-usages (cond-> []
                                   service-usage (conj service-usage))
                 :date-filter filter}))))))))

(defn provider
  [client]
  (->TavilyProvider client))
