(ns hc.server.search.integration-test
  (:require [clojure.test :refer [deftest is testing]]
            [hc.core.ufss :as ufss]
            [hc.server.diagnostics.service :as diagnostics]
            [hc.server.search :as search]
            [hc.server.search.context :as context]
            [hc.server.search.integration :as integration]
            [hc.server.search.support :as support]))

(defn- service
  [server]
  (support/search-service
   {:services [(support/searx-service (:base server))]}))

(deftest required-success-continues-with-structured-context
  (let [server (support/start-server
                {"/search"
                 (fn [exchange _]
                   (support/json-response!
                    exchange 200
                    {:results [{:url "https://example.test/a"
                                :title "Alpha"
                                :content "Alpha snippet"
                                :publishedDate "2026-10-01"}
                               {:url "https://example.test/b"
                                :title "Beta"
                                :content "Beta snippet"}]}))})]
    (try
      (let [result (integration/run-intent!
                    (service server)
                    {:mode :required
                     :service-id "searx"
                     :queries [{:query-id "q1" :text "herdchestra"}]}
                    {:text "herdchestra"
                     :turn-id "turn-1"
                     :session-id "s1"})]
        (is (:continue? result))
        (is (:ok? result))
        (is (= :ok (:status result)))
        (is (false? (:must-search-failed? result)))
        (is (= ["S1" "S2"] (get-in result [:context :labels])))
        (is (= :hc/search-context
               (:format (ufss/read-str (get-in result [:context :ufss-text])))))
        (is (= 2 (count (get-in result [:context :source-cards])))))
      (finally
        (support/stop! server)))))

(deftest required-failure-blocks-the-model
  (let [server (support/start-server
                {"/search"
                 (fn [exchange _]
                   (support/response! exchange 500 "boom" "text/plain"))})]
    (try
      (let [result (integration/run-intent!
                    (service server)
                    {:mode :required
                     :service-id "searx"
                     :queries [{:query-id "q1" :text "herdchestra"}]}
                    {:text "herdchestra"})]
        (is (false? (:continue? result)))
        (is (false? (:ok? result)))
        (is (= :failed (:status result)))
        (is (true? (:must-search-failed? result)))
        (is (= :failed (get-in result [:context :status])))
        (is (false? (get-in result [:context :searched?]))))
      (finally
        (support/stop! server)))))

(deftest auto-failure-continues-with-a-truthful-context
  (let [server (support/start-server
                {"/search"
                 (fn [exchange _]
                   (support/response! exchange 500 "boom" "text/plain"))})]
    (try
      (let [result (integration/run-intent!
                    (service server)
                    {:mode :auto
                     :service-id "searx"
                     :queries [{:query-id "q1" :text "herdchestra"}]}
                    {:text "herdchestra"})]
        (is (:continue? result))
        (is (false? (:ok? result)))
        (is (= :failed (:status result)))
        (is (false? (:must-search-failed? result)))
        (is (= :failed (get-in result [:context :status])))
        (is (false? (get-in result [:context :searched?]))))
      (finally
        (support/stop! server)))))

(deftest off-does-not-contact-the-service
  (let [server (support/start-server
                {"/search"
                 (fn [exchange _]
                   (support/json-response! exchange 200 {:results []}))})]
    (try
      (let [result (integration/run-intent! (service server)
                                            {:mode :off}
                                            {:text "hello"})]
        (is (:continue? result))
        (is (= :skipped (:status result)))
        (is (nil? (:context result)))
        (is (= 0 (support/request-count server))))
      (finally
        (support/stop! server)))))

(deftest missing-mode-is-refused-without-a-request
  (let [result (integration/run-intent! nil {} {:text "hello"})]
    (is (false? (:continue? result)))
    (is (= :SEARCH_MODE_INVALID (get-in result [:error :code])))
    (is (nil? (:context result)))))

(deftest missing-service-required-blocks-and-auto-continues
  (let [required (integration/run-intent!
                  nil
                  {:mode :required :service-id "stub"}
                  {:text "hello"})
        auto (integration/run-intent!
              nil
              {:mode :auto :service-id "stub"}
              {:text "hello"})]
    (is (false? (:continue? required)))
    (is (true? (:must-search-failed? required)))
    (is (= :SEARCH_UNAVAILABLE (get-in required [:error :code])))
    (is (true? (:continue? auto)))
    (is (false? (:must-search-failed? auto)))
    (is (nil? (:context auto)))))

(deftest provider-usage-is-recorded-only-when-supplied
  (let [server (support/start-server
                {"/search"
                 (fn [exchange _]
                   (support/json-response!
                    exchange 200
                    {:results [{:title "Alpha"
                                :url "https://example.test/a"
                                :content "Alpha snippet"}]
                     :usage {:credits 2}
                     :request_id "req-1"}))})
        seen (atom [])]
    (try
      (with-redefs [diagnostics/record-service-usage!
                    (fn [_ usage] (swap! seen conj usage))]
        (let [result (integration/run-intent!
                      (support/search-service
                       {:services [(support/tavily-service (:base server))]})
                      {:mode :required
                       :service-id "tavily"
                       :queries [{:query-id "q1" :text "herdchestra"}]}
                      {:text "herdchestra"
                       :turn-id "turn-1"
                       :session-id "s1"
                       :diagnostics {}})]
          (is (:continue? result))
          (is (= 1 (count @seen)))
          (is (= :per-credit (:unit (first @seen))))
          (is (== 2 (:quantity (first @seen))))
          (is (= "turn-1" (:turn-id (first @seen))))
          (is (= "s1" (:session-id (first @seen))))))
      (finally
        (support/stop! server)))))

(deftest context-is-injected-as-one-system-message
  (let [server (support/start-server
                {"/search"
                 (fn [exchange _]
                   (support/json-response!
                    exchange 200
                    {:results [{:url "https://example.test/a"
                                :title "Alpha"
                                :content "Alpha snippet"}]}))})]
    (try
      (let [result (integration/run-intent!
                    (service server)
                    {:mode :required
                     :service-id "searx"
                     :queries [{:query-id "q1" :text "herdchestra"}]}
                    {:text "herdchestra"})
            request (context/inject-request
                     {:snapshot {:messages [{:role :user :text "herdchestra"}]}}
                     (:context result))]
        (is (= :system (get-in request [:snapshot :messages 0 :role])))
        (is (= :user (get-in request [:snapshot :messages 1 :role])))
        (is (re-find #"Web search context \(UFSS\)"
                     (get-in request [:snapshot :messages 0 :text]))))
      (finally
        (support/stop! server)))))
