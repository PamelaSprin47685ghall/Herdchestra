(ns hc.server.search.providers-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hc.server.search :as search]
            [hc.server.search.providers.common :as common]
            [hc.server.search.support :as support]))

(defn- run-one
  [service service-id]
  (search/run! service
               {:service-id service-id
                :mode :required
                :queries [(support/query "q1" "herdchestra test")]
                :refs (search/new-reference-set)}))

(defn- searx-routes
  []
  {"/search"
   (fn [exchange _]
     (support/json-response!
      exchange 200
      {:results [{:url "https://example.test/a"
                  :title "Alpha"
                  :content "Alpha snippet"
                  :publishedDate "2026-10-01"}
                 {:url "https://example.test/b"
                  :title ""
                  :content "Beta snippet"}
                 {:url "not a url"
                  :title "Broken"}]}))})

(deftest searxng-request-and-result-shape
  (let [server (support/start-server (searx-routes))]
    (try
      (let [service (support/search-service
                     {:services [(support/searx-service (:base server))]})
            result (run-one service "searx")
            request (first (support/requests server))
            sources (get-in result [:refs :sources])]
        (is (:ok? result))
        (is (= :ok (:status result)))
        (is (false? (:must-search-failed? result)))
        (is (str/includes? (:query request) "format=json"))
        (is (str/includes? (:query request) "q=herdchestra+test"))
        (is (= 2 (count sources)))
        (is (= "2026-10-01"
               (get-in sources ["https://example.test/a" :published-at])))
        (is (nil? (get-in sources ["https://example.test/b" :published-at])))
        (is (= 1 (get-in result [:queries 0 :invalid-count])))
        (is (= 2 (get-in result [:queries 0 :valid-count]))))
      (finally
        (support/stop! server)))))

(deftest brave-request-and-result-shape
  (let [server (support/start-server
                {"/search"
                 (fn [exchange _]
                   (support/json-response!
                    exchange 200
                    {:web {:results
                           [{:title "Alpha"
                             :url "https://example.test/a"
                             :description "Alpha snippet"
                             :page_age "2026-10-01T08:00:00Z"
                             :age "2 days ago"}
                            {:title "Beta"
                             :url "https://example.test/b"
                             :description "Beta snippet"
                             :age "3 hours ago"}]}}))})]
    (try
      (let [service (support/search-service
                     {:services [(support/brave-service (:base server))]})
            result (run-one service "brave")
            request (first (support/requests server))
            sources (get-in result [:refs :sources])]
        (is (:ok? result))
        (is (= "brave-secret-key"
               (get-in request [:headers "x-subscription-token" 0])))
        (is (= "2026-10-01T08:00:00Z"
               (get-in sources ["https://example.test/a" :published-at])))
        (is (nil? (get-in sources ["https://example.test/b" :published-at]))
            "a human age phrase is not a publication date"))
      (finally
        (support/stop! server)))))

(deftest tavily-request-and-full-text
  (let [seen (atom nil)
        server (support/start-server
                {"/search"
                 (fn [exchange _]
                   (let [body (json/read-str (support/request-body exchange)
                                             :key-fn keyword)]
                     (reset! seen body)
                     (support/json-response!
                      exchange 200
                      {:results
                       [{:title "Alpha"
                         :url "https://example.test/a"
                         :content "Alpha snippet"
                         :raw_content "The complete article body."
                         :published_date "2026-10-02"}
                        {:title "Beta"
                         :url "https://example.test/b"
                         :content "Beta snippet"}]
                       :usage {:credits 2}
                       :request_id "req-1"})))})]
    (try
      (let [service (support/search-service
                     {:services [(support/tavily-service (:base server))]})
            result (run-one service "tavily")
            source (get-in result [:refs :sources "https://example.test/a"])
            document (get-in result [:documents "https://example.test/a"])]
        (is (:ok? result))
        (is (= "tavily-secret-key" (:api_key @seen)))
        (is (= "herdchestra test" (:query @seen)))
        (is (= :full (:content-status source)))
        (is (= "The complete article body." (:text document)))
        (is (= :search-result (:retrieval document)))
        (is (= "2026-10-02" (:published-at source)))
        (let [usage (first (:service-usages result))]
          (is (= "tavily:req-1" (:usage-id usage)))
          (is (= :per-credit (:unit usage)))
          (is (== 2 (:quantity usage)))
          (is (= :provider-reported (:quantity-source usage)))
          (is (= "tavily" (:service-id usage)))
          (is (= "search" (:name usage)))))
      (finally
        (support/stop! server)))))

(deftest tavily-without-reported-usage-records-nothing
  (let [server (support/start-server
                {"/search"
                 (fn [exchange _]
                   (support/json-response!
                    exchange 200
                    {:results [{:title "Alpha"
                                :url "https://example.test/a"
                                :content "Alpha snippet"}]
                     :request_id "req-no-usage"}))})]
    (try
      (let [service (support/search-service
                     {:services [(support/tavily-service (:base server))]})
            result (run-one service "tavily")]
        (is (:ok? result))
        (is (= [] (:service-usages result))
            "result count is not billed usage"))
      (finally
        (support/stop! server)))))

(defn- failing-service
  [status]
  (support/start-server
   {"/search"
    (fn [exchange _]
      (support/response! exchange status "failure" "text/plain"))}))

(deftest http-failures-are-distinct-and-truthful
  (doseq [[status expected]
          [[401 :SEARCH_CREDENTIAL_INVALID]
           [403 :SEARCH_ACCESS_DENIED]
           [429 :SEARCH_QUOTA_EXCEEDED]
           [500 :SEARCH_SERVICE_ERROR]
           [302 :SEARCH_REDIRECT_REFUSED]]]
    (testing (str "status " status)
      (let [server (failing-service status)]
        (try
          (let [service (support/search-service
                         {:services [(support/searx-service (:base server))]})
                result (run-one service "searx")]
            (is (false? (:ok? result)))
            (is (= :failed (:status result)))
            (is (true? (:must-search-failed? result)))
            (is (= expected (get-in result [:error :code])))
            (is (= [] (get-in result [:refs :order]))))
          (finally
            (support/stop! server)))))))

(deftest invalid-json-is-a-service-response-failure
  (let [server (support/start-server
                {"/search"
                 (fn [exchange _]
                   (support/response! exchange 200 "not json" "application/json"))})]
    (try
      (let [service (support/search-service
                     {:services [(support/searx-service (:base server))]})
            result (run-one service "searx")]
        (is (false? (:ok? result)))
        (is (= :SEARCH_RESPONSE_INVALID (get-in result [:error :code]))))
      (finally
        (support/stop! server)))))

(deftest missing-results-list-is-a-response-failure
  (let [server (support/start-server
                {"/search"
                 (fn [exchange _]
                   (support/json-response! exchange 200 {:unexpected true}))})]
    (try
      (let [service (support/search-service
                     {:services [(support/searx-service (:base server))]})
            result (run-one service "searx")]
        (is (false? (:ok? result)))
        (is (= :SEARCH_RESPONSE_INVALID (get-in result [:error :code]))))
      (finally
        (support/stop! server)))))

(deftest service-errors-never-echo-the-credential
  (let [server (failing-service 401)]
    (try
      (let [service (support/search-service
                     {:services [(support/brave-service (:base server))]})
            result (run-one service "brave")
            text (pr-str result)]
        (is (= :SEARCH_CREDENTIAL_INVALID (get-in result [:error :code])))
        (is (not (str/includes? text "brave-secret-key"))))
      (finally
        (support/stop! server)))))

(deftest publication-dates-are-real-dates
  (is (= "2026-10-01" (common/published-at "2026-10-01")))
  (is (= "2026-10-01T08:00:00Z"
         (common/published-at "2026-10-01T08:00:00Z")))
  (is (= "2026-10-01 08:00:00"
         (common/published-at "2026-10-01 08:00:00")))
  (doseq [value ["2026-13-45" "2026-02-30" "2026-10-01T99" "yesterday"]]
    (is (nil? (common/published-at value)) value)))

(deftest tavily-top-level-credits-are-not-billed-usage
  (let [server (support/start-server
                {"/search"
                 (fn [exchange _]
                   (support/json-response!
                    exchange 200
                    {:results [{:title "Alpha"
                                :url "https://example.test/a"
                                :content "Alpha snippet"}]
                     :credits 3
                     :request_id "req-top-credits"}))})]
    (try
      (let [service (support/search-service
                     {:services [(support/tavily-service (:base server))]})
            result (run-one service "tavily")]
        (is (:ok? result))
        (is (= [] (:service-usages result))))
      (finally
        (support/stop! server)))))
