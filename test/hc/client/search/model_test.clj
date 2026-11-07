(ns hc.client.search.model-test
  (:require [clojure.test :refer [deftest is testing]]
            [hc.client.search.model :as model]))

(defn- db
  [services]
  {:search {:ok? true
            :config {:services services}
            :errors []}})

(defn- service
  ([id] (service id {}))
  ([id opts]
  (merge {:id id
          :name id
          :kind :searxng
          :enabled? true
          :requires-credential? false
          :credentials {:available? true}}
         opts)))

(deftest modes-and-command-payload
  (let [state model/initial-state]
    (is (= :off (:mode state)))
    (is (= {:mode :off} (model/command-payload state "hello")))
    (is (nil? (model/selection-error {} state)))
    (let [auto (model/set-mode state :auto)]
      (is (= :auto (:mode auto)))
      (is (= "请选择搜索服务" (model/selection-error {} auto)))
      (is (= {:mode :auto :service-id "s1"
              :queries [{:query-id "q1" :text "hello"}]}
             (model/command-payload (model/set-service auto "s1") "hello"))))))

(deftest search-selection-is-per-session
  (let [db {:ui/search-state {"s1" {:mode :required :service-id "s1"}
                              "s2" {:mode :off}}}]
    (is (= :required (:mode (model/state-for db "s1"))))
    (is (= "s1" (:service-id (model/state-for db "s1"))))
    (is (= :off (:mode (model/state-for db "s2"))))
    (is (= :off (:mode (model/state-for db "new"))))
    (is (= {:mode :off} (model/command-payload (model/state-for db "new")
                                               "hello")))))

(deftest service-selection-is-explicit
  (let [services [(service "ok")
                  (service "off" {:enabled? false})
                  (service "bad" {:invalid? true
                                  :error {:code :SEARCH_SERVICE_KIND_UNKNOWN
                                          :message "unknown kind"}})
                  (service "key" {:requires-credential? true
                                  :credentials {:available? false}})]
        db (db services)
        state (model/set-mode model/initial-state :required)]
    (is (= ["ok" "off" "bad" "key"] (mapv :id (model/services db))))
    (is (:selectable? (first (model/services db))))
    (is (= "服务已停用" (:reason (second (model/services db)))))
    (is (string? (:reason (nth (model/services db) 2))))
    (is (= "缺少可用凭据" (:reason (nth (model/services db) 3))))
    (is (= "请选择搜索服务" (model/selection-error db state)))
    (is (= "服务已停用"
           (model/selection-error db (model/set-service state "off"))))
    (is (= "缺少可用凭据"
           (model/selection-error db (model/set-service state "key"))))
    (is (nil? (model/selection-error db (model/set-service state "ok"))))))

(deftest source-cards-keep-dates-separate
  (let [run {:status :ok
             :searched? true
             :must-search-failed? false
             :sources [{:label "S1"
                        :title "Alpha"
                        :url "https://example.test/a"
                        :site "example.test"
                        :published-at "2026-10-01"
                        :fetched-at "2026-11-03T10:00:00Z"
                        :snippet "Alpha snippet"
                        :content-status :summary}
                       {:label "S2"
                        :title "Beta"
                        :url "https://example.test/b"
                        :site "example.test"
                        :published-at nil
                        :fetched-at "2026-11-03T10:00:00Z"
                        :snippet "Beta snippet"
                        :content-status :summary}]}
        state (model/record-run model/initial-state run)
        cards (model/source-cards state)]
    (is (= "已搜索" (model/run-status-label state)))
    (is (= ["S1" "S2"] (mapv :label cards)))
    (is (= "2026-10-01" (:published-at (first cards))))
    (is (false? (:published-at-unknown? (first cards))))
    (is (nil? (:published-at (second cards))))
    (is (true? (:published-at-unknown? (second cards))))
    (is (= "2026-11-03T10:00:00Z" (:fetched-at (second cards))))
    (is (not (contains? (second cards) :text)))))

(deftest failure-labels-are-actionable
  (let [state (model/record-run
               model/initial-state
               {:ok? false
                :status :failed
                :must-search-failed? true
                :searched? false
                :error {:code :SEARCH_CREDENTIAL_INVALID
                        :message "credential rejected"}})]
    (is (= "搜索失败" (model/run-status-label state)))
    (is (true? (model/must-search-failed? state)))
    (is (= "搜索凭据失效，请在设置中修复"
           (model/run-error-label state)))
    (is (= [] (model/source-cards state)))))

(deftest rejected-search-receipts-have-a-specific-label
  (is (= "搜索服务不可达"
         (model/command-error-label
          {:kind :send-message
           :error {:code :SEARCH_SERVICE_UNREACHABLE}})))
  (is (= "搜索凭据失效，请在设置中修复"
         (model/command-error-label
          {:kind :send-message
           :error {:code :SEARCH_CREDENTIAL_INVALID}})))
  (is (= "本轮必须搜索失败"
         (model/command-error-label
          {:kind :send-message
           :error {:code :SEARCH_REQUIRED_FAILED}})))
  (is (nil? (model/command-error-label
             {:kind :send-message
              :error {:code :SOMETHING_ELSE}}))))

(deftest message-sources-use-citation-numbers
  (let [sources (model/message-sources
                 {:citations [{:title "Alpha"
                               :url "https://example.test/a"
                               :citation-number 1
                               :published-at nil
                               :fetched-at "2026-11-03T10:00:00Z"}
                              {:title "Beta"
                               :url "https://example.test/b"
                               :citation-number 2
                               :published-at "2026-10-02"}]})]
    (is (= [1 2] (mapv :number sources)))
    (is (true? (:published-at-unknown? (first sources))))
    (is (= "2026-10-02" (:published-at (second sources)))))
  (let [sources (model/message-sources
                 {:sources []
                  :citations [{:title "Cited"
                               :url "https://example.test/cited"
                               :citation-number 3}]})]
    (is (= 1 (count sources)))
    (is (= 3 (:number (first sources))))))

(deftest duplicate-sources-merge-and-sort
  (let [state (model/record-run
               model/initial-state
               {:status :ok
                :sources [{:citation-number 2
                           :canonical-url "https://example.test/a"
                           :url "https://example.test/a?utm=1"
                           :title "Alpha"
                           :queries ["q1"]}
                          {:citation-number 1
                           :url "https://example.test/b"
                           :title "Beta"}
                          {:citation-number 3
                           :canonical-url "https://example.test/a"
                           :url "https://example.test/a?utm=2"
                           :title "Alpha again"
                           :queries ["q2"]}]})
        cards (model/source-cards state)]
    (is (= 2 (count cards)))
    (is (= [1 2] (mapv :number cards)))
    (is (= "Beta" (:title (first cards))))
    (is (= ["q1" "q2"] (:queries (second cards))))
    (is (= "Alpha" (:title (second cards))))))

(deftest source-lists-are-not-truncated
  (let [sources (mapv (fn [n]
                        {:citation-number n
                         :url (str "https://example.test/" n)
                         :title (str "S" n)})
                      (range 1 26))
        state (model/record-run model/initial-state
                                {:status :ok :sources sources})]
    (is (= 25 (count (model/source-cards state))))
    (is (= 25 (count (model/message-sources {:citations sources}))))))

(deftest omitted-source-count-is-visible
  (let [state (model/record-run model/initial-state
                                {:status :ok
                                 :source-cards []
                                 :omitted-source-count 4})]
    (is (= 4 (model/omitted-source-count state))))
  (let [state (model/record-run model/initial-state
                                {:status :ok
                                 :omitted {:sources 2}})]
    (is (= 2 (model/omitted-source-count state)))))

(deftest message-sources-sort-by-citation-number
  (let [sources (model/message-sources
                 {:citations [{:citation-number 3 :title "C"}
                              {:citation-number 1 :title "A"}
                              {:citation-number 2 :title "B"}]})]
    (is (= [1 2 3] (mapv :number sources)))
    (is (= ["A" "B" "C"] (mapv :title sources)))))

(deftest all-search-error-codes-have-labels
  (doseq [[code expected]
          {:SEARCH_SERVICE_INVALID "所选搜索服务配置无效"
           :SEARCH_CREDENTIAL_MISSING "所选搜索服务缺少可用凭据"
           :SEARCH_REDIRECT_REFUSED "搜索服务要求重定向，已拒绝"
           :SEARCH_RESPONSE_TOO_LARGE "搜索服务响应过大"
           :SEARCH_MODE_INVALID "搜索模式无效"
           :SEARCH_SERVICE_ERROR "搜索服务错误"}]
    (is (= expected
           (model/command-error-label {:error {:code code}})))))

(deftest search-panel-starts-closed
  (is (false? (:open? model/initial-state))))

(deftest service-selection-accepts-keyword-and-string-ids
  (let [services [(service "local-stub")]
        db (db services)
        base (model/set-mode model/initial-state :required)]
    (doseq [id ["local-stub" :local-stub]]
      (let [state (assoc base :service-id id)]
        (is (= "local-stub" (:id (model/selected-service db state)))
            (str "selected service resolves for " (pr-str id)))
        (is (nil? (model/selection-error db state)))
        (is (= "local-stub" (:service-id (model/command-payload state "q"))))))))
