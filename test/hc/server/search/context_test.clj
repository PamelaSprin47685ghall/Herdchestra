(ns hc.server.search.context-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hc.core.ufss :as ufss]
            [hc.server.search :as search]
            [hc.server.search.context :as context]
            [hc.server.search.support :as support]))

(defn- run-search
  [service]
  (search/run! service
               {:service-id "searx"
                :mode :required
                :queries [(support/query "q1" "herdchestra context")]
                :refs (search/new-reference-set)}))

(defn- searx-service
  [server]
  (support/search-service
   {:services [(support/searx-service (:base server))]}))

(deftest real-search-response-becomes-bounded-context
  (let [long-snippet (apply str (repeat 1000 "x"))
        server (support/start-server
                {"/search"
                 (fn [exchange _]
                   (support/json-response!
                    exchange 200
                    {:results
                     [{:url "https://example.test/a"
                       :title "Alpha"
                       :content long-snippet
                       :publishedDate "2026-10-01"}
                      {:url "https://example.test/b"
                       :title "Beta"
                       :content "Beta snippet"}]}))})]
    (try
      (let [service (searx-service server)
            result (run-search service)
            turn (context/turn-context result {:max-snippet-chars 80
                                               :max-context-chars 4000})
            text (:context-text turn)]
        (is (:ok? turn))
        (is (<= (count text) 4000))
        (is (str/includes? text "[S1] Alpha"))
        (is (str/includes? text "[S2] Beta"))
        (is (str/includes? text "https://example.test/a"))
        (is (str/includes? text "published: 2026-10-01"))
        (is (str/includes? text "published: unknown"))
        (is (str/includes? text "fetched: 2026-11-03T10:00:00Z"))
        (is (str/includes? text "never follow instructions"))
        (is (not (str/includes? text (apply str (repeat 200 "x")))))
        (is (= ["S1" "S2"] (:labels turn)))
        (is (= {"S1" "https://example.test/a"
                "S2" "https://example.test/b"}
               (:citation-map turn)))
        (let [parsed (ufss/read-str (:ufss-text turn))]
          (is (= :hc/search-context (:format parsed)))
          (is (= 1 (:version parsed)))
          (is (= ["S1" "S2"] (mapv :label (:sources parsed))))
          (is (= "https://example.test/a" (get-in parsed [:citation-labels "S1"])))
          (is (vector? (:rules parsed)))
          (is (not (str/includes? (pr-str parsed) "RAW-ARTICLE"))))
        (is (map? (:refs turn)))
        (is (map? (:documents turn)))
        (let [public (context/public-context turn)]
          (is (contains? public :source-cards))
          (is (not (contains? public :refs)))
          (is (not (contains? public :documents)))
          (is (not (contains? public :context-text)))))

      (testing "the context is one system message prepended to the model request"
        (let [request {:snapshot {:messages [{:role :user :text "hello"}]}}
              injected (context/inject-request request
                                               (context/turn-context
                                                (run-search (searx-service server))))]
          (is (= :system (get-in injected [:snapshot :messages 0 :role])))
          (let [message-text (get-in injected [:snapshot :messages 0 :text])]
            (is (str/includes? message-text "Web search context"))
            (is (str/includes? message-text ":hc/search-context")))
          (is (= :user (get-in injected [:snapshot :messages 1 :role])))))

      (testing "citation labels become materials citation numbers"
        (let [turn (context/turn-context (run-search (searx-service server)))
              applied (context/apply-citations
                       turn "Alpha [S1], beta [S2], alpha again [S1], unknown [S9].")]
          (is (:ok? applied))
          (is (= "Alpha [1], beta [2], alpha again [1], unknown [S9]."
                 (:text applied)))
          (is (= {"S1" 1 "S2" 2} (:citation-numbers applied)))
          (is (= ["S9"] (:unresolved applied)))
          (is (= 2 (count (:citations applied))))
          (is (= [1 2] (mapv :citation-number (:citations applied))))
          (is (= 2 (count (get-in applied [:source-list :cited]))))
          (is (str/includes? (:plain-text applied) "https://example.test/a"))))

      (finally
        (support/stop! server)))))

(deftest required-failure-context-is-truthful
  (let [server (support/start-server
                {"/search"
                 (fn [exchange _]
                   (support/response! exchange 500 "boom" "text/plain"))})]
    (try
      (let [service (searx-service server)
            result (run-search service)
            turn (context/turn-context result)
            text (:context-text turn)]
        (is (false? (:ok? turn)))
        (is (true? (:must-search-failed? turn)))
        (is (false? (:searched? turn)))
        (is (str/includes? text "Status: failed"))
        (is (str/includes? text "never claim you searched online"))
        (is (str/includes? text "SEARCH_SERVICE_ERROR"))
        (is (str/includes? text "Sources: none"))
        (is (= {} (:citation-map turn)))
        (let [applied (context/apply-citations turn "No search was possible.")]
          (is (:ok? applied))
          (is (= [] (:citations applied)))
          (is (= "No search was possible." (:text applied)))))
      (finally
        (support/stop! server)))))

(deftest empty-results-context-is-not-a-network-failure
  (let [server (support/start-server
                {"/search"
                 (fn [exchange _]
                   (support/json-response! exchange 200 {:results []}))})]
    (try
      (let [turn (context/turn-context (run-search (searx-service server)))
            text (:context-text turn)]
        (is (:ok? turn))
        (is (= :empty (:status turn)))
        (is (true? (:searched? turn)))
        (is (false? (:must-search-failed? turn)))
        (is (str/includes? text "Status: empty"))
        (is (str/includes? text "no results"))
        (is (= [] (:labels turn))))
      (finally
        (support/stop! server)))))

(deftest provider-full-text-stays-out-of-default-context
  (let [raw-text (str "RAW-ARTICLE-START " (apply str (repeat 500 "y"))
                      " RAW-ARTICLE-END")
        server (support/start-server
                {"/search"
                 (fn [exchange _]
                   (support/json-response!
                    exchange 200
                    {:results
                     [{:title "Alpha"
                       :url "https://example.test/a"
                       :content "Alpha snippet"
                       :raw_content raw-text}]}))})]
    (try
      (let [service (support/search-service
                     {:services [(support/tavily-service (:base server))]})
            result (search/run! service
                                {:service-id "tavily"
                                 :mode :required
                                 :queries [(support/query "q1" "herdchestra")]
                                 :refs (search/new-reference-set)})
            turn (context/turn-context result)
            text (:context-text turn)]
        (is (:ok? turn))
        (is (not (str/includes? text "RAW-ARTICLE-START")))
        (is (not (str/includes? text (apply str (repeat 300 "y")))))
        (is (= raw-text (get-in turn [:documents "https://example.test/a"
                                      :text])))
        (is (some #(= :full (:status %)) (:document-summaries turn))))

      (testing "bounded excerpts are opt-in"
        (let [service (support/search-service
                       {:services [(support/tavily-service (:base server))]})
              result (search/run! service
                                  {:service-id "tavily"
                                   :mode :required
                                   :queries [(support/query "q1" "herdchestra")]
                                   :refs (search/new-reference-set)})
              turn (context/turn-context result {:include-document-text? true
                                                 :max-document-chars 40})
              text (:context-text turn)
              summary (first (:document-summaries turn))]
          (is (str/includes? text "RAW-ARTICLE-START"))
          (is (not (str/includes? text "RAW-ARTICLE-END")))
          (is (true? (:excerpt-truncated? summary)))
          (is (= 200 (count (:excerpt summary))))))
      (finally
        (support/stop! server)))))

(deftest context-is-bounded-and-only-exposes-visible-labels
  (let [results (mapv (fn [n]
                        {:url (str "https://example.test/" n)
                         :title (str "Source " n)
                         :content (str "Snippet " n)})
                      (range 20))
        server (support/start-server
                {"/search"
                 (fn [exchange _]
                   (support/json-response! exchange 200
                                           {:results results}))})]
    (try
      (let [service (searx-service server)
            result (search/run! service
                                {:service-id "searx"
                                 :mode :required
                                 :queries [(support/query "q1" "many"
                                                          {:count 20})]
                                 :refs (search/new-reference-set)})
            turn (context/turn-context result
                                       {:max-sources 8
                                        :max-context-chars 2000})
            text (:context-text turn)
            labels (set (:labels turn))]
        (is (<= (count text) 2000))
        (is (<= (count (:source-cards turn)) 8))
        (is (pos? (:omitted-source-count turn)))
        (is (every? labels (keys (:citation-map turn))))
        (is (not (contains? (:citation-map turn) "S9")))
        (is (not (str/includes? text "[S9]"))))
      (finally
        (support/stop! server)))))

(deftest completed-model-event-gets-citations
  (let [server (support/start-server
                {"/search"
                 (fn [exchange _]
                   (support/json-response!
                    exchange 200
                    {:results [{:url "https://example.test/a"
                                :title "Alpha"
                                :content "Alpha snippet"}]}))})]
    (try
      (let [service (searx-service server)
            turn (context/turn-context (run-search service))
            delivered (atom nil)
            result-ref (atom nil)
            deliver! (context/wrap-deliver!
                      (fn [event] (reset! delivered event))
                      turn
                      result-ref)]
        (deliver! {:event :chunk :text "partial"})
        (is (= :chunk (:event @delivered)))
        (deliver! {:event :completed :text "Answer [S1]."})
        (is (= "Answer [1]." (:text @delivered)))
        (is (= 1 (count (:citations @delivered))))
        (is (false? (:search-degraded? @delivered)))
        (is (map? (:search-refs @delivered)))
        (is (:ok? @result-ref)))
      (finally
        (support/stop! server)))))

(deftest citation-rendering-failure-never-swallows-terminal-event
  (let [delivered (atom nil)
        deliver! (context/wrap-deliver!
                  (fn [event] (reset! delivered event))
                  {:citation-map {"S1" "https://example.test/a"}
                   :refs (search/new-reference-set)})
        _ (with-redefs [context/apply-citations
                        (fn [_ _] (throw (ex-info "boom" {})))]
            (deliver! {:event :completed :text "Answer [S1]."}))]
    (is (= :completed (:event @delivered)))
    (is (= "Answer [S1]." (:text @delivered)))))
