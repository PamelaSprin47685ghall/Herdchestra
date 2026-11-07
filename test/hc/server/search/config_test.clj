(ns hc.server.search.config-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hc.server.search :as search]
            [hc.server.search.config :as config]))

(defn- kinds
  [normalized]
  (mapv :kind (get-in normalized [:config :services])))

(deftest service-kind-is-explicit
  (testing "a display name never chooses a provider"
    (let [normalized (config/normalize
                      {:services [{:id "x" :name "Brave clone"
                                   :base-url "https://example.test/search"}]})]
      (is (false? (:ok? normalized)))
      (is (= :SEARCH_SERVICE_KIND_UNKNOWN
             (get-in normalized [:errors 0 :code])))
      (is (:invalid? (get-in normalized [:config :services 0])))))

  (testing "an unsupported kind is refused"
    (let [normalized (config/normalize
                      {:services [{:id "x" :kind :unknown
                                   :base-url "https://example.test/search"}]})]
      (is (false? (:ok? normalized)))
      (is (= :SEARCH_SERVICE_KIND_UNKNOWN
             (get-in normalized [:errors 0 :code])))))

  (testing "the three implemented kinds are accepted"
    (let [normalized (config/normalize
                      {:services [{:id "s" :kind :searxng
                                   :base-url "https://s.test/search"}
                                  {:id "b" :kind :brave
                                   :base-url "https://b.test/search"}
                                  {:id "t" :kind :tavily
                                   :base-url "https://t.test/search"}]})]
      (is (:ok? normalized))
      (is (= [:searxng :brave :tavily] (kinds normalized))))))

(deftest endpoint-has-no-second-secret-store
  (doseq [url ["https://example.test/search?api_key=secret"
               "https://user:pass@example.test/search"
               "https://example.test/search#fragment"
               "ftp://example.test/search"
               "not a url"]]
    (testing url
      (let [normalized (config/normalize
                        {:services [{:id "x" :kind :searxng
                                     :base-url url}]})]
        (is (false? (:ok? normalized)))
        (is (= :SEARCH_SERVICE_URL_INVALID
               (get-in normalized [:errors 0 :code])))))))

(deftest public-config-hides-inline-secret
  (let [normalized (config/normalize
                    {:services [{:id "b" :kind :brave
                                 :base-url "https://b.test/search"
                                 :credentials {:api-key "super-secret-value"}}]})
        public (config/public-config normalized {})
        text (pr-str public)]
    (is (:ok? normalized))
    (is (not (str/includes? text "super-secret-value")))
    (is (true? (get-in public [:config :services 0
                               :credentials :configured?])))
    (is (= [:inline]
           (mapv :source (get-in public [:config :services 0
                                         :credentials :keys]))))))

(deftest invalid-draft-endpoint-hides-query-secret
  (let [normalized (config/normalize
                    {:services [{:id "x" :kind :searxng
                                 :base-url "https://host.test/path?api_key=url-secret"}]})
        public (config/public-config normalized {})
        text (pr-str public)]
    (is (false? (:ok? normalized)))
    (is (not (str/includes? text "url-secret")))
    (is (str/includes? text "https://host.test/path"))))

(deftest public-config-hides-failed-draft-secret
  (let [normalized (config/normalize
                    {:services [{:id "b"
                                 :kind :not-a-kind
                                 :base-url "https://b.test/search"
                                 :credentials {:api-key "draft-secret"}}]})
        public (config/public-config normalized {})
        text (pr-str public)]
    (is (false? (:ok? normalized)))
    (is (not (str/includes? text "draft-secret")))
    (is (not (contains? (get-in public [:config :services 0]) :draft)))))

(deftest failed-draft-keeps-only-editable-fields
  (let [normalized (config/normalize
                    {:services [{:id "b"
                                 :kind :not-a-kind
                                 :base-url "https://b.test/search"
                                 :api-key "top-level-secret"
                                 :headers {:authorization "Bearer header-secret"}
                                 :preferences {:result-count 9
                                               :api-key "nested-secret"}}]})
        public (config/public-config normalized {})
        text (pr-str public)
        draft (get-in public [:config :services 0])]
    (is (false? (:ok? normalized)))
    (is (not (str/includes? text "top-level-secret")))
    (is (not (str/includes? text "header-secret")))
    (is (not (str/includes? text "nested-secret")))
    (is (= 9 (get-in draft [:preferences :result-count])))
    (is (not (contains? (:preferences draft) :api-key)))
    (is (not (contains? draft :headers)))))

(deftest duplicate-service-ids-are-refused
  (let [normalized (config/normalize
                    {:services [{:id "same" :kind :searxng
                                 :base-url "https://a.test/search"}
                                {:id "same" :kind :searxng
                                 :base-url "https://b.test/search"}]})]
    (is (false? (:ok? normalized)))
    (is (= :SEARCH_SERVICE_ID_DUPLICATE
           (:code (first (:errors normalized)))))
    (is (every? :invalid? (get-in normalized [:config :services])))))

(deftest preferences-are-validated
  (let [normalized (config/normalize
                    {:services [{:id "s" :kind :searxng
                                 :base-url "https://s.test/search"
                                 :preferences {:result-count 0}}]})]
    (is (false? (:ok? normalized)))
    (is (= :SEARCH_PREFERENCE_INVALID
           (get-in normalized [:errors 0 :code]))))

  (let [normalized (config/normalize
                    {:services [{:id "s" :kind :searxng
                                 :base-url "https://s.test/search"
                                 :preferences {:result-count 9
                                               :safe-search "strict"
                                               :date-range :week
                                               :categories ["news" "general"]}}]})
        preferences (get-in normalized [:config :services 0 :preferences])]
    (is (:ok? normalized))
    (is (= 9 (:result-count preferences)))
    (is (= :strict (:safe-search preferences)))
    (is (= :week (:date-range preferences)))
    (is (= ["news" "general"] (:categories preferences)))))

(deftest credentials-resolve-from-environment
  (let [normalized (config/normalize
                    {:services [{:id "b" :kind :brave
                                 :base-url "https://b.test/search"
                                 :credentials {:api-key-env "BRAVE_TEST_KEY"}}]})
        service (get-in normalized [:config :services 0])
        resolved (config/resolve-credentials service
                                             {"BRAVE_TEST_KEY" "from-env"})
        missing (config/resolve-credentials service {})]
    (is (:ok? normalized))
    (is (= ["from-env"] (mapv :value (:usable resolved))))
    (is (empty? (:usable missing)))
    (is (= :SEARCH_CREDENTIAL_MISSING (get-in missing [:error :code])))
    (is (not (str/includes? (pr-str missing) "from-env")))))

(deftest service-by-id-never-selects-a-default
  (let [normalized (config/normalize
                    {:services [{:id "only" :kind :searxng
                                 :base-url "https://s.test/search"}]})]
    (is (some? (config/service-by-id normalized "only")))
    (is (nil? (config/service-by-id normalized nil)))
    (is (nil? (config/service-by-id normalized "other")))))

(deftest facade-public-view-is-secret-free
  (let [service (search/service
                 {:config {:services [{:id "b" :kind :brave
                                       :base-url "https://b.test/search"
                                       :credentials {:api-key "facade-secret"}}]}
                  :env {}})
        text (pr-str (search/public-config service))]
    (is (not (str/includes? text "facade-secret")))
    (is (= ["b"] (mapv :id (search/list-services service))))))
