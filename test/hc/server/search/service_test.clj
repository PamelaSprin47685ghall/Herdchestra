(ns hc.server.search.service-test
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

(defn- run-search
  [service request]
  (search/run! service request))

(deftest off-mode-does-not-contact-any-service
  (let [server (support/start-server
                {"/search" (fn [exchange _]
                             (support/json-response! exchange 200
                                                     {:results []}))})]
    (try
      (let [service (support/search-service
                     {:services [(support/searx-service (:base server))]})
            result (run-search service
                               {:service-id "searx"
                                :mode :off
                                :queries [(support/query "q1" "anything")]
                                :refs (search/new-reference-set)})]
        (is (:ok? result))
        (is (= :skipped (:status result)))
        (is (false? (:searched? result)))
        (is (= 0 (support/request-count server)))
        (is (= :not-started (get-in result [:queries 0 :status]))))
      (finally
        (support/stop! server)))))

(deftest off-mode-without-queries-is-a-no-op
  (let [server (support/start-server
                {"/search" (fn [exchange _]
                             (support/json-response! exchange 200
                                                     {:results []}))})]
    (try
      (let [service (support/search-service
                     {:services [(support/searx-service (:base server))]})
            result (run-search service
                               {:mode :off
                                :refs (search/new-reference-set)})]
        (is (:ok? result))
        (is (= :skipped (:status result)))
        (is (= [] (:queries result)))
        (is (= 0 (support/request-count server))))
      (finally
        (support/stop! server)))))

(deftest invalid-query-overrides-are-refused-before-any-request
  (let [server (support/start-server
                {"/search" (fn [exchange _]
                             (support/json-response! exchange 200
                                                     {:results []}))})]
    (try
      (let [service (support/search-service
                     {:services [(support/searx-service (:base server))]})]
        (doseq [query [(support/query "q1" "x" {:count 0})
                       (support/query "q1" "x" {:safe-search :unknown})
                       (support/query "q1" "x" {:date-range "yesterday"})
                       (support/query "q1" "x" {:include-raw-content? "yes"})]]
          (let [result (run-search service
                                   {:service-id "searx"
                                    :mode :required
                                    :queries [query]
                                    :refs (search/new-reference-set)})]
            (is (false? (:ok? result)))
            (is (= :SEARCH_QUERY_INVALID (get-in result [:error :code])))))
        (is (= 0 (support/request-count server))))
      (finally
        (support/stop! server)))))

(deftest request-maps-are-validated-before-a-request
  (let [server (support/start-server
                {"/search" (fn [exchange _]
                             (support/json-response! exchange 200
                                                     {:results []}))})]
    (try
      (let [service (support/search-service
                     {:services [(support/searx-service (:base server))]})]
        (doseq [[request code]
                [[{:service-id "searx"
                   :mode :required
                   :queries [(support/query "q1" "x")]
                   :refs "not-a-set"}
                  :SEARCH_SET_INVALID]
                 [{:service-id "searx"
                   :mode :required
                   :queries [(support/query "q1" "x")]
                   :documents "not-a-map"}
                  :SEARCH_DOCUMENTS_INVALID]]]
          (let [result (run-search service request)]
            (is (false? (:ok? result)))
            (is (= code (get-in result [:error :code])))))
        (is (= 0 (support/request-count server))))
      (finally
        (support/stop! server)))))

(deftest missing-selection-is-a-required-failure
  (let [server (support/start-server
                {"/search" (fn [exchange _]
                             (support/json-response! exchange 200
                                                     {:results []}))})]
    (try
      (let [service (support/search-service
                     {:services [(support/searx-service (:base server))]})
            result (run-search service
                               {:mode :required
                                :queries [(support/query "q1" "anything")]
                                :refs (search/new-reference-set)})]
        (is (false? (:ok? result)))
        (is (= :SEARCH_SERVICE_NOT_SELECTED (get-in result [:error :code])))
        (is (true? (:must-search-failed? result)))
        (is (= 0 (support/request-count server))))
      (finally
        (support/stop! server)))))

(deftest disabled-selection-never-falls-back
  (let [first-server (support/start-server
                      {"/search" (fn [exchange _]
                                   (support/json-response!
                                    exchange 200 {:results []}))})
        second-server (support/start-server
                       {"/search" (fn [exchange _]
                                    (support/json-response!
                                     exchange 200 {:results []}))})]
    (try
      (let [service (support/search-service
                     {:services [(support/searx-service
                                  (:base first-server)
                                  {:id "first" :enabled? false})
                                 (support/searx-service
                                  (:base second-server)
                                  {:id "second"})]})
            result (run-search service
                               {:service-id "first"
                                :mode :required
                                :queries [(support/query "q1" "anything")]
                                :refs (search/new-reference-set)})]
        (is (false? (:ok? result)))
        (is (= :SEARCH_SERVICE_DISABLED (get-in result [:error :code])))
        (is (true? (:must-search-failed? result)))
        (is (= 0 (support/request-count first-server)))
        (is (= 0 (support/request-count second-server))))
      (finally
        (support/stop! first-server)
        (support/stop! second-server)))))

(deftest selected-failure-never-uses-another-enabled-service
  (let [failing (support/start-server
                 {"/search" (fn [exchange _]
                              (support/response! exchange 500 "boom"
                                                 "text/plain"))})
        healthy (support/start-server
                 {"/search" (fn [exchange _]
                              (support/json-response!
                               exchange 200
                               {:results [{:url "https://example.test/a"
                                           :title "A"}]}))})]
    (try
      (let [service (support/search-service
                     {:services [(support/searx-service
                                  (:base failing) {:id "selected"})
                                 (support/searx-service
                                  (:base healthy) {:id "other"})]})
            result (run-search service
                               {:service-id "selected"
                                :mode :required
                                :queries [(support/query "q1" "anything")]
                                :refs (search/new-reference-set)})]
        (is (false? (:ok? result)))
        (is (true? (:must-search-failed? result)))
        (is (= :SEARCH_SERVICE_ERROR (get-in result [:error :code])))
        (is (= 1 (support/request-count failing)))
        (is (= 0 (support/request-count healthy))
            "an enabled service that was not selected is never contacted"))
      (finally
        (support/stop! failing)
        (support/stop! healthy)))))

(deftest auto-mode-failure-is-not-a-must-search-failure
  (let [server (support/start-server
                {"/search" (fn [exchange _]
                             (support/response! exchange 500 "boom"
                                                "text/plain"))})]
    (try
      (let [service (support/search-service
                     {:services [(support/searx-service (:base server))]})
            result (run-search service
                               {:service-id "searx"
                                :mode :auto
                                :queries [(support/query "q1" "anything")]
                                :refs (search/new-reference-set)})]
        (is (false? (:ok? result)))
        (is (false? (:must-search-failed? result)))
        (is (= :failed (:status result))))
      (finally
        (support/stop! server)))))

(deftest partial-success-keeps-successful-results
  (let [server (support/start-server
                {"/search"
                 (fn [exchange _]
                   (if (= "fail" (exchange-query exchange "q"))
                     (support/response! exchange 500 "boom" "text/plain")
                     (support/json-response!
                      exchange 200
                      {:results [{:url "https://example.test/ok"
                                  :title "OK"}]})))})]
    (try
      (let [service (support/search-service
                     {:services [(support/searx-service (:base server))]})
            result (run-search service
                               {:service-id "searx"
                                :mode :required
                                :queries [(support/query "q1" "fail")
                                          (support/query "q2" "ok")]
                                :refs (search/new-reference-set)})]
        (is (:ok? result))
        (is (= :partial (:status result)))
        (is (false? (:must-search-failed? result)))
        (is (= ["https://example.test/ok"]
               (get-in result [:refs :order])))
        (is (= 1 (count (:causes result)))))
      (finally
        (support/stop! server)))))

(deftest empty-results-are-not-a-network-failure
  (let [server (support/start-server
                {"/search" (fn [exchange _]
                             (support/json-response! exchange 200
                                                     {:results []}))})]
    (try
      (let [service (support/search-service
                     {:services [(support/searx-service (:base server))]})
            result (run-search service
                               {:service-id "searx"
                                :mode :required
                                :queries [(support/query "q1" "anything")]
                                :refs (search/new-reference-set)})]
        (is (:ok? result))
        (is (= :empty (:status result)))
        (is (true? (:searched? result)))
        (is (false? (:must-search-failed? result)))
        (is (= [] (get-in result [:refs :order]))))
      (finally
        (support/stop! server)))))

(deftest duplicate-urls-merge-and-provenance-survives
  (let [server (support/start-server
                {"/search"
                 (fn [exchange _]
                   (let [query (exchange-query exchange "q")]
                     (support/json-response!
                      exchange 200
                      {:results
                       (if (= "q1" query)
                         [{:url "https://example.test/a"
                           :title "Alpha"
                           :content "A snippet"
                           :publishedDate "2026-10-01"}
                          {:url "https://example.test/b"
                           :title "Beta"
                           :content "B snippet"}]
                         [{:url "https://example.test/a#section"
                           :title "Alpha again"
                           :content "A second snippet"}
                          {:url "https://example.test/c"
                           :title "Gamma"
                           :content "C snippet"}])})))})]
    (try
      (let [service (support/search-service
                     {:services [(support/searx-service (:base server))]})
            result (run-search service
                               {:service-id "searx"
                                :mode :required
                                :queries [(support/query "q1" "q1")
                                          (support/query "q2" "q2")]
                                :refs (search/new-reference-set)})
            sources (get-in result [:refs :sources])
            alpha (get sources "https://example.test/a")]
        (is (:ok? result))
        (is (= 3 (count sources)))
        (is (= ["q1" "q2"] (:queries alpha)))
        (is (= "2026-10-01" (:published-at alpha)))
        (is (nil? (get-in sources ["https://example.test/b" :published-at])))
        (is (nil? (get-in sources ["https://example.test/c" :published-at])))
        (is (= 2 (get-in result [:queries 0 :unique-count])))
        (is (= 2 (get-in result [:queries 1 :unique-count])))
        (let [provenance (get-in result [:provenance :sources])
              by-url (into {} (map (juxt :canonical-url identity)) provenance)]
          (is (= 3 (count provenance)))
          (is (true? (get-in by-url ["https://example.test/b"
                                     :published-at-unknown?])))
          (is (true? (get-in by-url ["https://example.test/c"
                                     :published-at-unknown?])))))
      (finally
        (support/stop! server)))))

(deftest cancellation-stops-pending-queries-and-keeps-returned-results
  (let [server (support/start-server
                {"/search"
                 (fn [exchange _]
                   (let [query (exchange-query exchange "q")]
                     (support/json-response!
                      exchange 200
                      {:results [{:url (str "https://example.test/" query)
                                  :title query}]})))})]
    (try
      (let [service (support/search-service
                     {:services [(support/searx-service (:base server))]})
            first-run (run-search service
                                  {:service-id "searx"
                                   :mode :required
                                   :queries [(support/query "q1" "q1")]
                                   :refs (search/new-reference-set)})
            second-run (run-search service
                                   {:service-id "searx"
                                    :mode :required
                                    :queries [(support/query "q2" "q2")]
                                    :refs (:refs first-run)
                                    :cancelled? (atom true)})]
        (is (:ok? first-run))
        (is (= 1 (support/request-count server)))
        (is (:ok? second-run))
        (is (true? (:cancelled? second-run)))
        (is (= :cancelled (:status second-run)))
        (is (= [:not-started] (mapv :status (:queries second-run))))
        (is (= 1 (support/request-count server))
            "a cancelled turn starts no new query")
        (is (= ["https://example.test/q1"]
               (get-in second-run [:refs :order]))
            "already returned results stay in the reference set"))
      (finally
        (support/stop! server)))))

(deftest cancellation-during-a-request-discards-that-response
  (let [cancelled? (atom false)
        server (support/start-server
                {"/search"
                 (fn [exchange _]
                   (reset! cancelled? true)
                   (support/json-response!
                    exchange 200
                    {:results [{:url "https://example.test/q1"
                                :title "Q1"}]}))})]
    (try
      (let [service (support/search-service
                     {:services [(support/searx-service (:base server))]})
            result (run-search service
                               {:service-id "searx"
                                :mode :required
                                :queries [(support/query "q1" "q1")]
                                :refs (search/new-reference-set)
                                :cancelled? cancelled?})]
        (is (:ok? result))
        (is (= :cancelled (:status result)))
        (is (= [:cancelled] (mapv :status (:queries result))))
        (is (= [] (get-in result [:refs :order])))
        (is (= 1 (support/request-count server))))
      (finally
        (support/stop! server)))))

(deftest key-rotation-requires-an-explicit-policy
  (let [server (support/start-server
                {"/search"
                 (fn [exchange _]
                   (if (= "first"
                          (support/request-header exchange
                                                  "x-subscription-token"))
                     (support/response! exchange 401 "unauthorized"
                                        "text/plain")
                     (support/json-response!
                      exchange 200
                      {:web {:results [{:url "https://example.test/a"
                                        :title "A"}]}})))})]
    (try
      (let [rotating (support/search-service
                      {:services
                       [(support/brave-service
                         (:base server)
                         {:credentials
                          {:keys [{:alias "first" :api-key "first"}
                                  {:alias "second" :api-key "second"}]
                           :rotation :on-auth-failure}})]})
            result (run-search rotating
                               {:service-id "brave"
                                :mode :required
                                :queries [(support/query "q1" "anything")]
                                :refs (search/new-reference-set)})]
        (is (:ok? result))
        (is (= 2 (support/request-count server)))
        (is (= ["first"]
               (mapv :key-alias (get-in result [:queries 0 :key-errors]))))
        (is (= "second" (get-in result [:queries 0 :key-alias]))))
      (finally
        (support/stop! server)))

    (let [server (support/start-server
                  {"/search"
                   (fn [exchange _]
                     (support/response! exchange 401 "unauthorized"
                                        "text/plain"))})]
      (try
        (let [fixed (support/search-service
                     {:services
                      [(support/brave-service
                        (:base server)
                        {:credentials
                         {:keys [{:alias "first" :api-key "first"}
                                 {:alias "second" :api-key "second"}]
                          :rotation :none}})]})
              result (run-search fixed
                                 {:service-id "brave"
                                  :mode :required
                                  :queries [(support/query "q1" "anything")]
                                  :refs (search/new-reference-set)})]
          (is (false? (:ok? result)))
          (is (= 1 (support/request-count server))
              "without the explicit policy the second key is not tried")
          (is (= :SEARCH_CREDENTIAL_INVALID (get-in result [:error :code]))))
        (finally
          (support/stop! server))))))

(deftest missing-first-key-does-not-silently-use-the-second
  (let [server (support/start-server
                {"/search" (fn [exchange _]
                             (support/json-response! exchange 200
                                                     {:web {:results []}}))})
        service (support/search-service
                 {:services
                  [(support/brave-service
                    (:base server)
                    {:credentials
                     {:keys [{:alias "first" :api-key-env "MISSING_KEY"}
                             {:alias "second" :api-key "second"}]
                      :rotation :none}})]})]
    (try
      (let [result (run-search service
                               {:service-id "brave"
                                :mode :required
                                :queries [(support/query "q1" "anything")]
                                :refs (search/new-reference-set)})]
        (is (false? (:ok? result)))
        (is (= :SEARCH_CREDENTIAL_MISSING (get-in result [:error :code])))
        (is (true? (:must-search-failed? result)))
        (is (= 0 (support/request-count server))))
      (finally
        (support/stop! server)))))
