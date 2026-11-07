(ns hc.server.search.smoke-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hc.server.search :as search]
            [hc.server.search.support :as support])
  (:import (java.net URLDecoder)))

(defn- exchange-query
  [exchange name]
  (when-let [[_ value] (re-find (re-pattern (str "(?:^|&)" name "=([^&]*)"))
                                (str (.getRawQuery (.getRequestURI exchange))))]
    (URLDecoder/decode value "UTF-8")))

(defn- routes
  [brave-counter]
  {"/search"
   (fn [exchange _]
     (let [query (exchange-query exchange "q")]
       (support/json-response!
        exchange 200
        {:results
         (if (= "q1" query)
           [{:url "https://news.example/a"
             :title "Alpha"
             :content "Alpha snippet"
             :publishedDate "2026-10-01"}
            {:url "https://news.example/b"
             :title "Beta"
             :content "Beta snippet"}]
           [{:url "https://news.example/a#section"
             :title "Alpha again"
             :content "Alpha second snippet"}
            {:url "https://news.example/c"
             :title "Gamma"
             :content "Gamma snippet"}])})))

   "/fail"
   (fn [exchange _]
     (support/response! exchange 500 "boom" "text/plain"))

   "/brave"
   (fn [exchange _]
     (swap! brave-counter inc)
     (support/json-response!
      exchange 200
      {:web {:results [{:url "https://brave.example/x"
                        :title "Brave result"}]}}))

   "/article"
   (fn [exchange _]
     (support/response! exchange 200
                        (str "<html><head><title>Alpha article</title></head>"
                             "<body><p>The article body explains the event.</p>"
                             "</body></html>")
                        "text/html"))})

(deftest configured-search-end-to-end
  (let [brave-counter (atom 0)
        server (support/start-server (routes brave-counter))
        searx (support/searx-service (:base server) {:id "searx-main"})
        brave (support/brave-service (:base server)
                                     {:id "brave-backup"
                                      :base-url (support/url server "/brave")})
        service (support/search-service {:services [searx brave]})
        result (search/run! service
                            {:service-id "searx-main"
                             :mode :required
                             :queries [(support/query "q1" "q1")
                                       (support/query "q2" "q2")]
                             :refs (search/new-reference-set)
                             :documents {}})]
    (try
      (testing "one selected service answers and the other is never contacted"
        (is (:ok? result))
        (is (= :ok (:status result)))
        (is (true? (:searched? result)))
        (is (false? (:must-search-failed? result)))
        (is (= 0 @brave-counter)))

      (testing "URL dedup and publication provenance"
        (let [sources (get-in result [:refs :sources])
              alpha (get sources "https://news.example/a")]
          (is (= 3 (count sources)))
          (is (= ["q1" "q2"] (:queries alpha)))
          (is (= "2026-10-01" (:published-at alpha)))
          (is (nil? (get-in sources ["https://news.example/b"
                                     :published-at])))
          (is (nil? (get-in sources ["https://news.example/c"
                                     :published-at])))
          (is (= 3 (count (get-in result [:provenance :sources]))))))

      (testing "citation numbers follow first citation"
        (let [refs (get-in result [:refs])
              alpha (search/cite-search-source refs "https://news.example/a")
              beta (search/cite-search-source (:refs alpha)
                                              "https://news.example/b")
              listed (search/search-source-list (:refs beta))]
          (is (:ok? alpha))
          (is (= 1 (:number alpha)))
          (is (:ok? beta))
          (is (= 2 (:number beta)))
          (is (= 2 (count (:cited listed))))
          (is (= 1 (count (:other listed))))
          (let [plain (search/search-plain-text (:refs beta))]
            (is (str/includes? plain "[1]"))
            (is (str/includes? plain "https://news.example/a"))
            (is (str/includes? plain "published: unknown")))))

      (testing "retrieval refuses a URL that no search result returned"
        (let [refs (get-in result [:refs])
              fetched (search/fetch! service
                                     {:url "https://news.example/not-a-source"
                                      :refs refs
                                      :documents (:documents result)
                                      :read-at "2026-11-03T11:00:00Z"})]
          (is (false? (:ok? fetched)))
          (is (= :SEARCH_SOURCE_UNKNOWN (get-in fetched [:error :code])))
          (is (= 3 (count (get-in refs [:sources])))
              "a refused retrieval leaves the reference set unchanged")))

      (testing "the local article endpoint is readable when it is the source"
        (let [page (support/url server "/article")
              refs (search/add-search-results
                    (search/new-reference-set)
                    {:query-id "local"
                     :fetched-at "2026-11-03T09:00:00Z"
                     :results [{:url page
                                :title "Saved local title"
                                :snippet "Saved local snippet"}]})
              fetched (search/fetch! service
                                     {:url page
                                      :refs (:refs refs)
                                      :documents {}
                                      :read-at "2026-11-03T11:00:00Z"})]
          (is (:ok? fetched))
          (is (= :full (:status fetched)))
          (is (str/includes? (get-in fetched [:document :text])
                             "The article body explains the event."))
          (is (= "Alpha article" (get-in fetched [:document :title])))
          (is (= :full (get-in fetched [:refs :sources page
                                        :content-status])))))

      (testing "the public configuration contains no credential"
        (let [text (pr-str (search/public-config service))]
          (is (not (str/includes? text "brave-secret-key")))
          (is (str/includes? text "brave-backup"))))

      (testing "a required failure never falls back to the enabled service"
        (let [offline (support/search-service
                       {:services
                        [{:id "offline"
                          :name "Offline"
                          :kind :searxng
                          :base-url "http://127.0.0.1:1/search"
                          :enabled? true
                          :timeout-ms 1000}
                         brave]})
              failed (search/run! offline
                                  {:service-id "offline"
                                   :mode :required
                                   :queries [(support/query "q1" "q1")]
                                   :refs (search/new-reference-set)})]
          (is (false? (:ok? failed)))
          (is (true? (:must-search-failed? failed)))
          (is (= :failed (:status failed)))
          (is (contains? #{:SEARCH_SERVICE_UNREACHABLE :SEARCH_TIMEOUT}
                         (get-in failed [:error :code])))
          (is (= 0 @brave-counter))))

      (testing "a selected 500 is also a truthful required failure"
        (let [broken (support/search-service
                      {:services
                       [(support/searx-service (:base server)
                                               {:id "broken"
                                                :base-url (support/url server
                                                                       "/fail")})
                        brave]})
              failed (search/run! broken
                                  {:service-id "broken"
                                   :mode :required
                                   :queries [(support/query "q1" "q1")]
                                   :refs (search/new-reference-set)})]
          (is (false? (:ok? failed)))
          (is (true? (:must-search-failed? failed)))
          (is (= :SEARCH_SERVICE_ERROR (get-in failed [:error :code])))
          (is (= 0 @brave-counter))))
      (finally
        (support/stop! server)))))
