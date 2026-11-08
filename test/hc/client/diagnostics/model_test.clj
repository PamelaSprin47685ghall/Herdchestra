(ns hc.client.diagnostics.model-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hc.client.diagnostics.model :as model]))

(defn- approx=
  [a b]
  (< (Math/abs (- (double a) (double b))) 1e-12))

(defn- stats
  []
  {:status :ok
   :empty? false
   :range {:all? false :from "2026-10-05" :to "2026-11-03"}
   :timezone "Asia/Shanghai"
   :zone-mode :query
   :filters {}
   :counts {:user-sends 3 :model-requests 5 :tool-calls 2 :active-days 2}
   :heatmap {"2026-11-03" {:user-sends 2 :model-requests 3 :tool-calls 1}}
   :tokens {:request-count 5
            :input-tokens 1000
            :output-tokens 100
            :cache-read-tokens 200
            :cache-write-tokens 0
            :total-tokens 1100
            :provenance {:input-tokens :reported
                         :output-tokens :reported
                         :cache-read-tokens :reported
                         :cache-write-tokens :reported}
            :total-provenance :reported
            :reported-requests 4
            :estimated-requests 1
            :unknown-requests 0
            :cache-mode :input-subset
            :cache-modes {:input-subset 5}
            :cache-subset? true
            :unknown-requests-by-field {}}
   :cost {:request-count 5
          :estimated-requests 3
          :partial-requests 1
          :unestimated-requests 2
          :actual-requests 0
          :unknown-currency-requests 0
          :invalid-price-requests 0
          :invalid-billing-requests 0
          :currency-mismatch-requests 0
          :by-currency {"USD" {:estimated-amount 0.0123M
                               :actual-amount nil
                               :estimated-requests 2
                               :partial-requests 1
                               :unestimated-requests 1
                               :actual-requests 0
                               :pricing-sources #{"user"}
                               :price-dates #{"2026-10-01"}}
                        "EUR" {:estimated-amount 0.004M
                               :actual-amount 0.005M
                               :estimated-requests 1
                               :partial-requests 0
                               :unestimated-requests 1
                               :actual-requests 1
                               :pricing-sources #{"service"}
                               :price-dates #{"2026-10-02"}}}}
   :service-cost {:usage-count 2
                  :estimated-usages 1
                  :unestimated-usages 1
                  :actual-usages 0
                  :reported-quantity-usages 1
                  :estimated-quantity-usages 0
                  :unknown-quantity-usages 1
                  :unknown-currency-usages 0
                  :invalid-price-usages 0
                  :by-currency {"USD" {:estimated-amount 0.02
                                       :actual-amount nil
                                       :estimated-usages 1
                                       :unestimated-usages 1
                                       :actual-usages 0
                                       :reported-quantity-usages 1
                                       :estimated-quantity-usages 0
                                       :unknown-quantity-usages 1
                                       :units {:per-image {:quantity 2
                                                           :usages 2
                                                           :estimated-amount 0.02}}}}
                  :by-unit {:per-image {:quantity 2 :usages 2
                                        :estimated-amount 0.02}}}
   :models [{:service-id "openai" :service-name "OpenAI" :model "gpt-x"
             :requests 3 :usage {} :cost {}}
            {:service-id "other" :service-name "Other" :model "m-1"
             :requests 1 :usage {} :cost {}}]
   :top-models {:top [{:service-id "openai" :service-name "OpenAI"
                       :model "gpt-x" :requests 3 :usage {} :cost {}}
                      {:service-id "other" :service-name "Other"
                       :model "m-1" :requests 1 :usage {} :cost {}}]
                :other nil}
   :turns [{:turn-id "t1"
            :at 1762185600000
            :counts {:user-sends 1 :model-requests 2 :tool-calls 1}
            :user-send {:assistant-id "assistant-a"
                        :model "gpt-x"
                        :temporary? false}
            :requests [{:request-id "r1"
                        :model "gpt-x"
                        :purpose :chat
                        :result :ok
                        :started-at 1762185600000
                        :finished-at 1762185601000
                        :usage {:input-tokens 1000
                                :output-tokens 100
                                :cache-read-tokens 200
                                :cache-write-tokens 0
                                :total-tokens 1100
                                :provenance {:input-tokens :reported
                                             :output-tokens :estimated
                                             :cache-read-tokens :reported
                                             :cache-write-tokens :reported}
                                :total-provenance :estimated}
                        :cost {:estimate {:status :complete
                                          :currency "USD"
                                          :amount 0.0123M
                                          :price-source "user"}
                               :billed nil}}]
            :tool-calls [{:tool-call-id "tool-1" :name "read" :result :ok}]
            :service-usages []
            :retry-count 1
            :failed-count 0}]
   :unknown-time {:user-sends 0 :model-requests 0 :tool-calls 0
                  :service-usages 0 :records {}}
   :excluded-unknown-time nil
   :last-updated 1762185600000
   :cached? true})

(deftest range-query-test
  (testing "presets use inclusive dates"
    (is (= {:from "2026-10-05" :to "2026-11-03" :all? false}
           (model/preset-range :last-30-days "2026-11-03")))
    (is (= {:from "2026-11-03" :to "2026-11-03" :all? false}
           (model/preset-range :today "2026-11-03"))))
  (testing "all range carries no dates"
    (let [state (assoc-in (model/initial-state) [:query :preset] :all)
          result (model/request-query state "2026-11-03")]
      (is (:ok? result))
      (is (true? (get-in result [:query :all])))
      (is (nil? (get-in result [:query :from])))))
  (testing "custom range rejects reversed dates"
    (let [state (-> (model/initial-state)
                    (assoc-in [:query :preset] :custom)
                    (assoc-in [:query :from] "2026-11-03")
                    (assoc-in [:query :to] "2026-11-01"))
          result (model/request-query state "2026-11-03")]
      (is (false? (:ok? result)))
      (is (= :range-reversed (get-in result [:error :code]))))))

(deftest token-and-cost-projection-test
  (let [s (stats)
        token (model/token-rows s)
        cost (model/cost-rows s)
        coverage (model/cost-coverage s)]
    (testing "cache is detail and total stays the server's total"
      (is (= 1100 (:value (some #(when (= :total (:id %)) %) (:rows token)))))
      (is (true? (:cache-subset? token)))
      (is (= "服务返回" (model/provenance-label :reported)))
      (is (= "服务返回" (model/provenance-label :provider-reported)))
      (is (= "估算" (model/provenance-label :estimated)))
      (is (= "未提供" (model/provenance-label :unknown))))
    (testing "currencies remain separate"
      (is (= ["EUR" "USD"] (mapv :currency cost)))
      (is (= 0.0123M (:estimated-amount (second cost))))
      (is (= 0.004M (:estimated-amount (first cost))))
      (is (nil? (:actual-amount (second cost)))))
    (testing "coverage counts unestimated requests without zeroing them"
      (is (= 5 (:request-count coverage)))
      (is (= 2 (:unestimated-requests coverage))))))

(deftest tagged-money-is-unwrapped-test
  (testing "a transit tagged amount exposes its representation"
    (is (= 0.003275M (model/unwrap-amount {:tag "f" :rep 0.003275M})))
    (is (= 0.003275 (model/amount-number {:tag "f" :rep "0.003275"})))
    (is (= 0.003275M (model/unwrap-amount 0.003275M))
        "plain numbers are not rewritten")
    (is (= "0.003275" (model/format-amount-number 0.003275M))))
  (testing "cost rows unwrap tagged estimates"
    (let [s (assoc-in (stats)
                      [:cost :by-currency "USD" :estimated-amount]
                      {:tag "f" :rep 0.003275M})
          cost (model/cost-rows s)]
      (is (= 0.003275M (:estimated-amount (second cost)))))))

(deftest unknown-fields-only-include-actual-gaps-test
  (testing "empty unknown lists are omitted so the UI does not warn"
    (let [s (assoc-in (stats) [:tokens :unknown-requests-by-field]
                      {:input-tokens []
                       :output-tokens []
                       :cache-read-tokens []
                       :cache-write-tokens []})
          token (model/token-rows s)]
      (is (= {} (:unknown-fields token)))
      (is (= :reported (:total-provenance token)))))
  (testing "a real unknown field is preserved with the total provenance"
    (let [s (-> (stats)
                (assoc-in [:tokens :unknown-requests-by-field]
                          {:output-tokens ["r1"]})
                (assoc-in [:tokens :total-provenance] :unknown))
          token (model/token-rows s)]
      (is (= {:output-tokens ["r1"]} (:unknown-fields token)))
      (is (= :unknown (:total-provenance token))))))

(deftest service-quantity-provenance-projection-test
  (let [service (model/service-cost-rows (stats))]
    (is (= 1 (:reported-quantity-usages service)))
    (is (= 0 (:estimated-quantity-usages service)))
    (is (= 1 (:unknown-quantity-usages service)))
    (is (= 1 (get-in service [:by-currency 0 :reported-quantity-usages])))
    (is (= 1 (get-in service [:by-currency 0 :unknown-quantity-usages])))))

(deftest cost-label-keeps-estimate-and-actual-separate-test
  (let [cost (:cost (stats))]
    (testing "a currency with both figures names both, never a sum"
      (is (re-find #"EUR 0.004 估算 / 0.005 实收"
                   (model/cost-summary-label cost)))
      (is (not (re-find #"0\.009" (model/cost-summary-label cost)))))
    (testing "hidden amounts stay hidden in compact labels"
      (is (= "金额已隐藏" (model/cost-summary-label cost true))))))

(deftest bundle-material-toggle-honors-explicit-checkbox-test
  (let [state (-> (model/initial-state)
                  (assoc-in [:bundle :request :materials]
                            [{:id :detailed-log :title "Log" :kind :log
                              :purpose "metadata" :include? true}]))
        unchecked (model/reduce-action
                   state
                   {:type :diagnostics/bundle-material-toggle
                    :material-id :detailed-log
                    :value false})
        checked (model/reduce-action
                 unchecked
                 {:type :diagnostics/bundle-material-toggle
                  :material-id :detailed-log
                  :value true})]
    (is (false? (get-in unchecked
                        [:state :bundle :request :materials 0 :include?])))
    (is (true? (get-in checked
                       [:state :bundle :request :materials 0 :include?])))))

(deftest receipt-application-test
  (let [state (model/apply-receipt
               (model/initial-state)
               {:ok? true
                :receipt {:status :complete
                          :statistics (stats)}})]
    (is (= :ok (:status state)))
    (is (true? (:loaded? state)))
    (is (= ["assistant-a"] (get-in state [:filter-options :assistants])))
    (is (= ["gpt-x" "m-1"]
           (mapv :model (get-in state [:filter-options :models])))))
  (let [state (model/apply-receipt
               (model/initial-state)
               {:ok? true
                :receipt {:status :complete
                          :statistics {:status :read-failed
                                       :message "disk"}}})]
    (is (= :read-failed (:status state)))
    (is (true? (get-in state [:error :retry?])))
    (is (nil? (:tokens (get-in state [:stats])))))
  (let [state (model/apply-receipt
               (model/initial-state)
               {:ok? false
                :error {:code :DIAGNOSTICS_UNAVAILABLE
                        :message "not wired"}})]
    (is (= :error (:status state)))
    (is (= "not wired" (get-in state [:error :message])))))

(deftest receipt-messages-do-not-stay-pending-test
  (let [state (-> (model/initial-state)
                  (assoc :message "正在读取统计…")
                  (model/apply-receipt {:ok? true
                                        :receipt {:status :complete
                                                  :statistics (stats)}}))]
    (is (nil? (:message state))))
  (let [log-state (-> (model/initial-state)
                      (assoc :message "正在请求开启详细日志…")
                      (model/apply-receipt {:ok? true
                                            :receipt {:status :accepted
                                                      :log {:enabled? true}}}))
        bundle-state (-> (model/initial-state)
                         (assoc :message "正在生成脱敏预览；不会上传。")
                         (model/apply-receipt {:ok? true
                                               :receipt {:status :complete
                                                         :bundle {:bundle-id "d"
                                                                  :sections []
                                                                  :transfer {:mode :local-only
                                                                             :automatic-upload? false
                                                                             :network? false
                                                                             :requires-user-action? true
                                                                             :uploaded? false}}}}))]
    (is (= "详细日志状态已更新。" (:message log-state)))
    (is (= "诊断预览已生成；尚未上传。" (:message bundle-state)))))

(deftest bundle-preview-edit-test
  (let [bundle {:bundle-id "diag-1"
                :sections [{:id :app
                            :title "Application"
                            :lines [{:id "app-version" :label "Version" :value "0.0.1"}
                                    {:id "app-os" :label "OS" :value "Linux"}]}]
                :extra-materials [{:id :detailed-log :title "Log" :kind :log
                                   :purpose "metadata" :included? false}]
                :redaction {:findings [{:path ["services" 0 "host"]
                                        :kind :host
                                        :placeholder "<redacted:host:1>"}]
                            :finding-count 1 :placeholder-count 1 :redacted? true}
                :excluded ["chat body" "secrets"]
                :transfer {:mode :local-only :automatic-upload? false
                           :network? false :requires-user-action? true}
                :warning "Review before sharing."}
        state (-> (model/initial-state)
                  (model/apply-receipt {:ok? true
                                        :receipt {:status :complete :bundle bundle}})
                  (model/reduce-action {:type :diagnostics/bundle-remove-line
                                        :line-id "app-os"})
                  :state)]
    (is (= :preview (get-in state [:bundle :status])))
    (is (= ["app-version"]
           (mapv :id (get-in (model/bundle-sections state) [0 :lines]))))
    (testing "removal goes through the server preview-edit command"
      (let [result (model/reduce-action
                    state
                    {:type :diagnostics/bundle-remove-line
                     :line-id "app-os"})]
        (is (= :diagnostics/bundle-remove-lines
               (get-in result [:effects 0 :kind])))
        (is (= ["app-os"] (get-in result [:effects 0 :line-ids])))))
    (is (= ["chat body" "secrets"] (:excluded bundle)))
    (testing "bundle request never adds chat or attachments"
      (let [request (model/bundle-request state "2026-11-03")]
        (is (not (contains? request :chat)))
        (is (not (contains? request :attachments)))
        (is (not (contains? request :request-body)))))))


(deftest log-and-export-receipt-test
  (testing "log status receipt replaces the displayed policy"
    (let [state (model/apply-receipt (model/initial-state)
                                     {:ok? true
                                      :receipt {:status :accepted
                                                :log {:enabled? true
                                                      :paused? false
                                                      :capturing? true
                                                      :mode :next-request
                                                      :retention-days 7
                                                      :entry-count 1}}})]
      (is (true? (get-in state [:log :enabled?])))
      (is (= 1 (get-in state [:log :entry-count])))))
  (testing "local export receipt never claims an upload"
    (let [state (model/apply-receipt (model/initial-state)
                                     {:ok? true
                                      :receipt {:status :local
                                                :artifact {:filename "diag.edn"
                                                           :content "{}"}
                                                :uploaded? false
                                                :automatic-upload? false
                                                :requires-user-action? true}})]
      (is (= :exported (get-in state [:bundle :status])))
      (is (false? (get-in state [:bundle :export :uploaded?])))
      (is (false? (get-in state [:bundle :export :automatic-upload?])))
      (testing "a new preview clears the previous export receipt"
        (let [next-state (model/apply-receipt
                          state
                          {:ok? true
                           :receipt {:status :complete
                                     :bundle {:bundle-id "diag-2"
                                              :sections []
                                              :transfer {:mode :local-only
                                                         :automatic-upload? false
                                                         :network? false
                                                         :requires-user-action? true}}}})]
          (is (nil? (get-in next-state [:bundle :export])))
          (is (= :preview (get-in next-state [:bundle :status]))))))))

(deftest price-preview-test
  (testing "fixed example uses 200 cache-hit tokens and per-million rates"
    (let [preview (model/price-preview {:currency "USD"
                                        :input "1"
                                        :output "2"
                                        :cache-read "0.5"
                                        :cache-write "0"})]
      (is (= 1100 (:total-tokens preview)))
      (is (= 200 (:cache-read preview)))
      (is (= 0 (:cache-write preview)))
      (is (approx= 0.0011 (:amount preview)))
      (is (false? (:partial? preview)))
      (is (true? (:estimated? preview)))
      (is (re-find #"USD" (:label preview)))))
  (testing "missing input or output is unestimated, not zero"
    (let [preview (model/price-preview {:currency "USD" :input "1"})]
      (is (nil? (:amount preview)))
      (is (re-find #"无法估算" (:label preview)))))
  (testing "missing cache-read rate prices only known parts"
    (let [preview (model/price-preview {:currency "USD"
                                        :input "1"
                                        :output "2"})]
      (is (approx= 0.0010 (:amount preview)))
      (is (true? (:partial? preview)))
      (is (re-find #"部分估算" (:label preview)))))
  (testing "cache-write rate is irrelevant when the example writes no cache"
    (let [preview (model/price-preview {:currency "USD"
                                        :input "1"
                                        :output "2"
                                        :cache-read "0.5"})]
      (is (approx= 0.0011 (:amount preview)))
      (is (false? (:partial? preview)))))
  (testing "a negative rate is invalid, never a negative amount"
    (let [preview (model/price-preview {:currency "USD"
                                        :input "-1"
                                        :output "2"})]
      (is (nil? (:amount preview)))
      (is (true? (:invalid? preview)))
      (is (re-find #"不能为负" (:label preview))))))

(deftest price-edit-clears-stale-saved-status-test
  (let [state (assoc-in (model/initial-state) [:price :status] :saved)
        result (model/reduce-action
                state
                {:type :diagnostics/price-set
                 :field :currency
                 :value "USD"})
        next-state (:state result)]
    (is (= :editing (get-in next-state [:price :status])))
    (is (= "USD" (get-in next-state [:price :currency])))
    (is (nil? (get-in next-state [:price :error])))))

(deftest feedback-draft-test
  (let [feedback {:title "发送失败" :steps "1. 打开会话" :expected "发送"
                  :actual "无响应"}
        text (model/feedback-text feedback)]
    (is (re-find #"发送失败" text))
    (is (re-find #"不会自动提交" text))
    (is (re-find #"mailto:" (model/mailto-url feedback)))))

(deftest bundle-material-merge-test
  (let [bundle {:bundle-id "diag-1"
                :sections []
                :extra-materials [{:id :detailed-log
                                   :title "Detailed request log"
                                   :kind :log
                                   :purpose "Show request metadata"
                                   :included? false
                                   :content ["entry"]}]}
        state (model/apply-receipt
               (model/initial-state)
               {:ok? true :receipt {:status :complete :bundle bundle}})
        toggled (:state
                 (model/reduce-action
                  state
                  {:type :diagnostics/bundle-material-toggle
                   :material-id :detailed-log}))
        material (first (get-in toggled [:bundle :request :materials]))
        request (model/bundle-request toggled "2026-11-03")
        sent (first (:extra-materials request))]
    (testing "the toggle copies the full server description"
      (is (true? (:include? material)))
      (is (= "Detailed request log" (:title material)))
      (is (= :log (:kind material)))
      (is (= "Show request metadata" (:purpose material)))
      (is (= ["entry"] (:content material))))
    (testing "the preview request sends the full material"
      (is (= material sent))
      (is (not (contains? sent :included?))))
    (testing "an explicit purpose edit wins over the server default"
      (let [cleared (:state
                     (model/reduce-action
                      toggled
                      {:type :diagnostics/bundle-material-purpose
                       :material-id :detailed-log
                       :value ""}))
            cleared-request (model/bundle-request cleared "2026-11-03")]
        (is (= "" (get-in cleared [:bundle :request :materials 0 :purpose])))
        (is (= "" (get-in cleared-request [:extra-materials 0 :purpose])))))
    (testing "the server's :included? flag is normalized to :include?"
      (is (false? (:include? (first (model/bundle-materials state))))))))

(deftest query-change-marks-statistics-stale-test
  (let [state (model/apply-receipt
               (model/initial-state)
               {:ok? true
                :receipt {:status :complete :statistics (stats)}})
        actions [{:type :diagnostics/select-range :preset :today}
                 {:type :diagnostics/set-custom-date
                  :field :from :value "2026-11-01"}
                 {:type :diagnostics/set-zone :value "UTC"}
                 {:type :diagnostics/set-zone-mode :value :record}
                 {:type :diagnostics/set-filter
                  :key :model :value "gpt-x"}
                 {:type :diagnostics/clear-filters}
                 {:type :diagnostics/set-top-n :value 3}]]
    (is (false? (:stats-stale? state)))
    (doseq [action actions]
      (is (true? (:stats-stale?
                  (:state (model/reduce-action state action))))
          (str "action did not mark statistics stale: " (:type action))))
    (testing "a fresh statistics receipt clears the stale mark"
      (is (false? (:stats-stale?
                   (model/apply-statistics
                    (assoc state :stats-stale? true)
                    (stats))))))
    (testing "the summary uses the returned range and filters"
      (is (= "2026-10-05 至 2026-11-03"
             (model/stats-range-label (stats))))
      (is (= "无筛选" (model/stats-filter-label (stats))))
      (is (= "模型 gpt-x"
             (model/stats-filter-label
              (assoc-in (stats) [:filters :model] "gpt-x")))))))

(deftest failed-read-keeps-cached-statistics-test
  (let [base (model/apply-receipt
              (model/initial-state)
              {:ok? true
               :receipt {:status :complete :statistics (stats)}})
        failed (model/apply-statistics
                base
                {:status :read-failed :message "disk read failed"})]
    (testing "the old statistics and timestamp survive a read failure"
      (is (= (stats) (:stats failed)))
      (is (= 1762185600000 (get-in failed [:stats :last-updated])))
      (is (= :read-failed (:status failed)))
      (is (= "disk read failed" (get-in failed [:stats-error :message])))
      (is (= (get-in base [:filter-options])
             (get-in failed [:filter-options]))))
    (testing "an invalid query also keeps the previous result"
      (let [invalid (model/apply-statistics
                     base
                     {:status :invalid-query
                      :message "bad range"})]
        (is (= (stats) (:stats invalid)))
        (is (= :invalid-query (:status invalid)))
        (is (= "bad range" (get-in invalid [:stats-error :message])))))
    (testing "a failure without a previous result leaves statistics nil"
      (let [empty (model/apply-statistics
                   (model/initial-state)
                   {:status :read-failed :message "disk"})]
        (is (nil? (:stats empty)))
        (is (false? (:loaded? empty)))))))

(deftest save-price-validation-test
  (let [base (assoc-in (model/initial-state)
                       [:price]
                       {:service-id "openai"
                        :model "gpt-x"
                        :currency "USD"
                        :input ""
                        :output ""
                        :cache-read ""
                        :cache-write ""})
        empty-result (model/reduce-action
                      base
                      {:type :diagnostics/save-price})
        negative-result (model/reduce-action
                         (assoc-in base [:price :input] "-1")
                         {:type :diagnostics/save-price})
        valid-result (model/reduce-action
                      (assoc-in base [:price :cache-read] "0")
                      {:type :diagnostics/save-price})]
    (testing "an empty price produces no effect"
      (is (empty? (:effects empty-result)))
      (is (= :error (get-in empty-result [:state :price :status]))))
    (testing "a negative rate is rejected, not sent"
      (is (empty? (:effects negative-result)))
      (is (re-find #"不能为负"
                   (get-in negative-result [:state :price :error :message]))))
    (testing "one non-negative rate is enough"
      (is (= 1 (count (:effects valid-result))))
      (is (zero? (get-in valid-result [:effects 0 :price :cache-read])))
      (is (= :saving (get-in valid-result [:state :price :status]))))))

(deftest receipt-error-unwrapping-test
  (testing "the outer error wins over the inner receipt error"
    (let [state (model/apply-receipt
                 (model/initial-state)
                 {:ok? false
                  :error {:message "outer failure"}
                  :receipt {:error {:message "inner failure"}}})]
      (is (= "outer failure" (get-in state [:error :message])))
      (is (= "outer failure" (:message state)))))
  (testing "the inner error is used when the outer has none"
    (let [state (model/apply-receipt
                 (model/initial-state)
                 {:ok? false
                  :receipt {:error {:message "inner failure"}}})]
      (is (= "inner failure" (get-in state [:error :message])))))
  (testing "a receipt without any error uses the generic message"
    (let [state (model/apply-receipt
                 (model/initial-state)
                 {:ok? false :receipt {:status :failed}})]
      (is (= "诊断命令被拒绝。" (get-in state [:error :message]))))))

(deftest transfer-messages-follow-the-receipt-test
  (let [remote (model/apply-receipt
                (model/initial-state)
                {:ok? true
                 :receipt {:status :complete
                           :bundle {:bundle-id "diag-remote"
                                    :sections []
                                    :transfer {:mode :remote
                                               :automatic-upload? true
                                               :network? true
                                               :uploaded? false}}}})]
    (testing "a remote preview is not called local-only"
      (is (not (str/includes? (:message remote) "尚未上传")))
      (is (str/includes? (:message remote) "remote"))))
  (testing "an uploaded export never says it did not upload"
    (let [state (model/apply-receipt
                 (model/initial-state)
                 {:ok? true
                  :receipt {:status :exported
                            :artifact {:id "blob-1" :filename "diag.edn"}
                            :uploaded? true
                            :automatic-upload? true}})]
      (is (not (str/includes? (:message state) "未上传")))
      (is (str/includes? (:message state) "已上传"))))
  (testing "an export without upload facts does not claim a local save"
    (let [state (model/apply-receipt
                 (model/initial-state)
                 {:ok? true
                  :receipt {:status :local
                            :artifact {:filename "diag.edn"
                                       :content "{}"}}})]
      (is (str/includes? (:message state) "未说明是否上传"))
      (is (not (str/includes? (:message state) "没有上传"))))))

(deftest remove-line-effect-records-previous-set-test
  (let [bundle {:bundle-id "diag-1"
                :sections [{:id :app
                            :title "Application"
                            :lines [{:id "app-version" :label "Version" :value "0.0.1"}
                                    {:id "app-os" :label "OS" :value "Linux"}]}]
                :transfer {:mode :local-only
                           :automatic-upload? false
                           :network? false
                           :requires-user-action? true}}
        state (-> (model/initial-state)
                  (model/apply-receipt {:ok? true
                                        :receipt {:status :complete
                                                  :bundle bundle}}))
        result (model/reduce-action
                state
                {:type :diagnostics/bundle-remove-line
                 :line-id "app-os"})
        effect (first (:effects result))]
    (is (= #{} (:previous-removed-lines effect)))
    (is (= #{"app-os"} (get-in (:state result) [:bundle :removed-lines])))
    (is (= #{"app-os"} (get-in effect [:bundle :removed-line-ids])))))

(deftest faq-query-action-test
  (let [state (model/initial-state)
        result (model/reduce-action
                state
                {:type :diagnostics/set-faq-query
                 :value "图片"})]
    (is (= "图片" (get-in result [:state :faq-query])))
    (is (= [] (:effects result)))
    (let [cleared (model/reduce-action
                   (:state result)
                   {:type :diagnostics/set-faq-query
                    :value nil})]
      (is (= "" (get-in cleared [:state :faq-query]))))))
