(ns hc.server.search.handle-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hc.server.search :as search]
            [hc.server.search.support :as support]))

(deftest handle-dispatches-search-commands
  (let [server (support/start-server
                {"/search"
                 (fn [exchange _]
                   (support/json-response!
                    exchange 200
                    {:web {:results [{:url "https://example.test/a"
                                      :title "A"}]}}))})
        service (support/search-service
                 {:services [(support/brave-service (:base server))]})]
    (try
      (testing "config is readable without a request"
        (let [result (search/handle! service :search/config {})
              text (pr-str result)]
          (is (:ok? result))
          (is (not (str/includes? text "brave-secret-key")))
          (is (= 0 (support/request-count server)))
          (is (not (str/includes? (pr-str (search/snapshot service))
                                  "brave-secret-key")))))

      (testing "run goes through the facade"
        (let [result (search/handle!
                      service :search/run
                      {:service-id "brave"
                       :mode :required
                       :queries [(support/query "q1" "hello")]
                       :refs (search/new-reference-set)})]
          (is (:ok? result))
          (is (= :ok (:status result)))
          (is (= 1 (support/request-count server)))))

      (testing "test-service is one explicit call"
        (support/reset-requests! server)
        (let [result (search/handle! service :search/test-service
                                     {:service-id "brave"})]
          (is (:ok? result))
          (is (true? (:test? result)))
          (is (true? (:service-call-made? result)))
          (is (= 1 (support/request-count server)))
          (is (str/includes? (:notice result) "one explicit test query"))
          (let [public (search/public-config service)
                last-test (get-in public [:config :services 0 :last-test])]
            (is (= :ok (:status last-test)))
            (is (not (str/includes? (pr-str public) "brave-secret-key"))))))

      (testing "an unknown command is refused"
        (let [result (search/handle! service :search/nope {})]
          (is (false? (:ok? result)))
          (is (= :SEARCH_COMMAND_UNKNOWN (get-in result [:error :code])))))
      (finally
        (support/stop! server)))))

(deftest failed-test-service-still-reports-the-attempt
  (let [server (support/start-server
                {"/search"
                 (fn [exchange _]
                   (support/response! exchange 401 "unauthorized"
                                      "text/plain"))})
        service (support/search-service
                 {:services [(support/brave-service (:base server))]})]
    (try
      (let [result (search/handle! service :search/test-service
                                   {:service-id "brave"})]
        (is (false? (:ok? result)))
        (is (= :SEARCH_CREDENTIAL_INVALID (get-in result [:error :code])))
        (is (true? (:service-call-made? result)))
        (is (= 1 (support/request-count server))))
      (finally
        (support/stop! server)))))
