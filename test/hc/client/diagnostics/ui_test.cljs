(ns hc.client.diagnostics.ui-test
  "Real React render tests for the diagnostics workspace.

  Run with the diagnostics Node harness (see package AGENTS.md).  The tests
  render the actual React element tree with `react-dom/server`; they do not
  inspect source strings."
  (:require ["react-dom/server" :as rds]
            [cljs.test :refer-macros [deftest is testing run-tests]]
            [clojure.string :as str]
            [hc.client.diagnostics.fixture :as fixture]
            [hc.client.diagnostics.model :as model]
            [hc.client.diagnostics.view :as view]))

(defn- render
  [state]
  (rds/renderToStaticMarkup
   (view/diagnostics-element state (fn [_] nil))))

(defn- bundle-state
  []
  (-> (fixture/state)
      (model/apply-receipt {:ok? true
                            :receipt {:status :complete
                                      :bundle fixture/bundle}})
      (assoc :tab :bundle)))

(deftest statistics-render-test
  (let [markup (render (fixture/state))]
    (testing "distinct counts are named and defined"
      (is (str/includes? markup "用户发送次数"))
      (is (str/includes? markup "模型请求次数"))
      (is (str/includes? markup "工具调用次数"))
      (is (str/includes? markup "一次供应商请求，包含重试")))
    (testing "token provenance and cache detail are explicit"
      (is (str/includes? markup "服务返回"))
      (is (str/includes? markup "估算"))
      (is (str/includes? markup "未提供"))
      (is (str/includes? markup "缓存命中（输入明细）"))
      (is (str/includes? markup "不会再次加进总量"))
      (is (not (str/includes? markup "存在未提供字段")))
      (is (str/includes? markup "重试 r1"))
      (is (str/includes? markup "2.5 per-second"))
      (is (str/includes? markup "服务返回"))
      (is (str/includes? markup "服务返回数量"))
      (is (str/includes? markup "数量未知")))
    (testing "currencies stay separate"
      (is (str/includes? markup "币种 USD"))
      (is (str/includes? markup "币种 EUR"))
      (is (str/includes? markup "不同币种分开汇总"))
      (is (str/includes? markup "未估算")))
    (testing "model distribution, heatmap, turns, unknown time"
      (is (str/includes? markup "模型分布"))
      (is (str/includes? markup "排行按使用量"))
      (is (str/includes? markup "活跃热力图"))
      (is (str/includes? markup "aria-label=\"每日活动\""))
      (is (str/includes? markup "按轮次查看"))
      (is (str/includes? markup "一次用户发送不等于一次模型调用"))
      (is (str/includes? markup "时间未知的记录")))
    (testing "filters and refresh are present"
      (is (str/includes? markup "最近 30 天"))
      (is (str/includes? markup "刷新统计"))
      (is (str/includes? markup "日期口径")))
    (testing "scoped responsive CSS is present"
      (is (str/includes? markup "@media (prefers-color-scheme: dark)"))
      (is (str/includes? markup "@media (max-width: 720px)"))
      (is (str/includes? markup "min-height: 44px"))
      (is (str/includes? markup ":focus-visible"))
      (is (str/includes? markup "overflow-wrap: anywhere")))))

(deftest unknown-warning-is-conditional-test
  (let [base (fixture/state)
        cache-only (-> base
                       (assoc-in [:stats :tokens :unknown-requests-by-field]
                                 {:cache-read-tokens ["r1"]})
                       (assoc-in [:stats :tokens :total-provenance] :reported))
        total-unknown (-> base
                          (assoc-in [:stats :tokens :unknown-requests-by-field]
                                    {:output-tokens ["r1"]})
                          (assoc-in [:stats :tokens :total-provenance] :unknown))
        cache-markup (render cache-only)
        total-markup (render total-unknown)]
    (testing "a known total does not claim to be unknown"
      (is (str/includes? cache-markup "总量仍按可确定字段计算"))
      (is (not (str/includes? cache-markup "总量保持未知"))))
    (testing "an unknown total is stated explicitly"
      (is (str/includes? total-markup "总量保持未知")))))

(deftest string-last-updated-is-not-hidden-test
  (let [markup (render (-> (fixture/state)
                           (assoc-in [:stats :last-updated]
                                     "2026-11-03T00:00:00Z")))]
    (is (str/includes? markup "2026-11-03T00:00:00Z"))))

(deftest empty-and-failure-states-test
  (testing "no-data is an explicit empty state, not a zero curve"
    (let [unknown (:unknown-time fixture/stats)
          excluded (:excluded-unknown-time fixture/stats)
          markup (render (-> (fixture/state)
                             (assoc :stats {:status :no-data
                                            :empty? true
                                            :counts {:user-sends 0
                                                     :model-requests 0
                                                     :tool-calls 0
                                                     :active-days 0}
                                            :unknown-time unknown
                                            :excluded-unknown-time excluded})
                             (assoc :status :no-data)))]
      (is (str/includes? markup "当前范围没有可计入的统计记录"))
      (is (str/includes? markup "不画看似正常的零值曲线"))
      (testing "unknown-time records stay visible even when the range is empty"
        (is (str/includes? markup "时间未知的记录"))
        (is (str/includes? markup "本范围排除了时间未知记录")))))
  (testing "read failure offers retry and no token curve"
    (let [markup (render (-> (fixture/state)
                             (assoc :stats {:status :read-failed
                                            :message "disk read failed"})
                             (assoc :status :read-failed
                                    :error {:message "disk read failed"})))]
      (is (str/includes? markup "统计读取失败"))
      (is (str/includes? markup "disk read failed"))
      (is (str/includes? markup "重试"))
      (is (not (str/includes? markup "总 token"))))))

(deftest logs-render-test
  (let [markup (render (assoc (fixture/state) :tab :logs))]
    (testing "log policy is described honestly"
      (is (str/includes? markup "详细日志默认关闭"))
      (is (str/includes? markup "最多保留 7 天"))
      (is (str/includes? markup "仅下一次请求"))
      (is (str/includes? markup "清空详细日志"))
      (is (str/includes? markup "不删除聊天")))
    (testing "secrets stay masked in the detail contract"
      (is (str/includes? markup "会遮蔽密钥、登录令牌、认证头和密码"))
      (is (str/includes? markup "不会根据当前设置重建")))))

(deftest bundle-render-test
  (let [markup (render (bundle-state))]
    (testing "the preview is local-only and not uploaded"
      (is (str/includes? markup "仅本地：没有上传"))
      (is (str/includes? markup "自动上传：否"))
      (is (str/includes? markup "网络：否"))
      (is (str/includes? markup "需要你选择位置：是"))
      (is (str/includes? markup "不会自动上传")))
    (testing "redaction and editable preview lines are visible"
      (is (str/includes? markup "脱敏预览"))
      (is (str/includes? markup "占位符"))
      (is (str/includes? markup "&lt;redacted:host:1&gt;"))
      (is (str/includes? markup "删去"))
      (is (str/includes? markup "隐藏域名"))
      (is (str/includes? markup "隐藏路径"))
      (is (str/includes? markup "应用昵称替换")))
    (testing "extra materials default excluded and export is explicit"
      (is (str/includes? markup "Detailed request log"))
      (is (str/includes? markup "每项默认不包含"))
      (is (str/includes? markup "导出诊断文件"))
      (is (str/includes? markup "不会替你提交公开 issue")))))

(deftest bundle-transfer-claims-follow-the-receipt-test
  (let [remote-bundle (assoc fixture/bundle
                             :transfer {:mode :remote
                                        :automatic-upload? true
                                        :network? true
                                        :requires-user-action? false
                                        :uploaded? false})
        state (-> (fixture/state)
                  (model/apply-receipt {:ok? true
                                        :receipt {:status :complete
                                                  :bundle remote-bundle}})
                  (assoc :tab :bundle))
        markup (render state)]
    (testing "a non-local transfer is never labelled local-only"
      (is (not (str/includes? markup "仅本地：没有上传")))
      (is (not (str/includes? markup "只在本机生成与导出")))
      (is (str/includes? markup "传输状态：remote"))
      (is (str/includes? markup "诊断包传输方式：remote"))
      (is (str/includes? markup "自动上传：是"))
      (is (str/includes? markup "网络：是"))))
  (testing "an uploaded receipt is named as uploaded"
    (let [uploaded-bundle (assoc fixture/bundle
                                 :transfer {:mode :remote
                                            :automatic-upload? true
                                            :network? true
                                            :requires-user-action? false
                                            :uploaded? true})
          state (-> (fixture/state)
                    (model/apply-receipt {:ok? true
                                          :receipt {:status :complete
                                                    :bundle uploaded-bundle}})
                    (assoc :tab :bundle))
          markup (render state)]
      (is (str/includes? markup "回执显示诊断包已上传"))
      (is (str/includes? markup "回执显示已上传"))
      (is (not (str/includes? markup "尚未上传")))
      (is (not (str/includes? markup "只在本机生成与导出"))))))

(deftest bundle-redaction-without-findings-is-not-a-guarantee-test
  (let [bundle (assoc fixture/bundle
                      :redaction {:findings []
                                  :finding-count 0
                                  :placeholder-count 0
                                  :redacted? false})
        state (-> (fixture/state)
                  (model/apply-receipt {:ok? true
                                        :receipt {:status :complete
                                                  :bundle bundle}})
                  (assoc :tab :bundle))
        markup (render state)]
    (is (str/includes? markup "未发现已知凭据模式"))
    (is (str/includes? markup "不等于正文没有个人信息"))))

(deftest uploaded-export-does-not-leave-local-header-test
  (let [state (-> (bundle-state)
                  (assoc-in [:bundle :export]
                            {:status :exported
                             :uploaded? true
                             :automatic-upload? true}))
        markup (render state)]
    (is (str/includes? markup "回执显示诊断包已上传"))
    (is (not (str/includes? markup "只在本机生成与导出")))))

(deftest bundle-rejected-material-is-not-silently-dropped-test
  (let [bundle (assoc fixture/bundle
                      :rejected-materials [{:id :chat
                                            :title "Chat body"
                                            :kind :chat-body
                                            :rejected-reason :forbidden-kind}])
        state (-> (fixture/state)
                  (model/apply-receipt {:ok? true
                                        :receipt {:status :complete
                                                  :bundle bundle}})
                  (assoc :tab :bundle))
        markup (render state)]
    (is (str/includes? markup "未包含的材料"))
    (is (str/includes? markup "Chat body"))
    (is (str/includes? markup "forbidden-kind"))))

(deftest cancelled-request-renders-unknown-usage-test
  (let [cancelled-turn {:turn-id "t-cancelled"
                        :at 1762185600000
                        :counts {:user-sends 1 :model-requests 1 :tool-calls 0}
                        :user-send {:assistant-id "assistant-a"
                                    :model "gpt-x"
                                    :temporary? false}
                        :requests [{:request-id "r-cancelled"
                                    :model "gpt-x"
                                    :purpose :chat
                                    :result :cancelled
                                    :started-at 1762185600000
                                    :finished-at 1762185600500
                                    :usage {:input-tokens :unknown
                                            :output-tokens :unknown
                                            :cache-read-tokens :unknown
                                            :cache-write-tokens :unknown
                                            :total-tokens :unknown
                                            :provenance {:input-tokens :unknown
                                                         :output-tokens :unknown
                                                         :cache-read-tokens :unknown
                                                         :cache-write-tokens :unknown}
                                            :total-provenance :unknown}
                                    :cost {:estimate {:status :unestimated
                                                      :currency nil
                                                      :amount nil}
                                           :billed nil}}]
                        :tool-calls []
                        :service-usages []
                        :retry-count 0
                        :failed-count 0
                        :cancelled-count 1}
        markup (render (-> (fixture/state)
                           (assoc-in [:stats :turns] [cancelled-turn])
                           (assoc-in [:stats :tokens]
                                     {:request-count 1
                                      :input-tokens :unknown
                                      :output-tokens :unknown
                                      :cache-read-tokens :unknown
                                      :cache-write-tokens :unknown
                                      :total-tokens :unknown
                                      :provenance {:input-tokens :unknown
                                                   :output-tokens :unknown
                                                   :cache-read-tokens :unknown
                                                   :cache-write-tokens :unknown}
                                      :total-provenance :unknown
                                      :reported-requests 0
                                      :estimated-requests 0
                                      :unknown-requests 1
                                      :unknown-requests-by-field
                                      {:input-tokens ["r-cancelled"]
                                       :output-tokens ["r-cancelled"]}})
                           (assoc-in [:stats :cost] nil)))]
    (testing "cancelled requests stay visible with their confirmed state"
      (is (str/includes? markup "结果：cancelled"))
      (is (str/includes? markup "取消 1")))
    (testing "unknown usage and missing price are not shown as zero"
      (is (str/includes? markup "未提供"))
      (is (str/includes? markup "未估算"))
      (is (str/includes? markup "总量保持未知")))))

(deftest feedback-render-test
  (let [markup (render (assoc (fixture/state) :tab :feedback))]
    (is (str/includes? markup "反馈草稿"))
    (is (str/includes? markup "复现步骤"))
    (is (str/includes? markup "打开邮件草稿"))
    (is (str/includes? markup "不自动代发"))
    (is (str/includes? markup "上传第三方"))))

(deftest disconnected-state-test
  (let [markup (render (-> (fixture/state)
                           (assoc :can-submit? false)
                           (assoc :stats nil)
                           (assoc :status :idle)))]
    (is (str/includes? markup "尚未读取统计"))
    (is (str/includes? markup "不会预先画零值"))
    (is (str/includes? markup "实时通道未就绪；不会伪造统计结果"))))

(deftest statistics-summary-follows-returned-range-test
  (let [markup (render (-> (fixture/state)
                           (assoc-in [:query :preset] :today)
                           (assoc :stats-stale? true)))]
    (testing "the summary uses the receipt's range and filters"
      (is (str/includes? markup "范围：2026-10-05 至 2026-11-03"))
      (is (str/includes? markup "筛选：无筛选")))
    (testing "a pending query change is called out"
      (is (str/includes? markup "筛选已更改，请刷新统计")))))

(deftest read-failure-keeps-cached-render-test
  (let [markup (render (-> (fixture/state)
                           (assoc :stats-error {:code :read-failed
                                                :message "disk read failed"
                                                :retry? true})
                           (assoc :status :read-failed
                                  :error {:message "disk read failed"})))]
    (testing "the failure banner keeps the retry"
      (is (str/includes? markup "disk read failed"))
      (is (str/includes? markup "重试")))
    (testing "the previous statistics stay visible"
      (is (str/includes? markup "上次成功读取"))
      (is (str/includes? markup "2026-10-05 至 2026-11-03"))
      (is (str/includes? markup "总 token")))))

(deftest service-usage-separates-estimate-and-actual-test
  (let [state (assoc-in (fixture/state)
                        [:stats :turns 0 :service-usages 0 :cost]
                        {:estimate {:status :complete
                                    :currency "USD"
                                    :amount 0.02
                                    :price-source "service"}
                         :billed {:amount 0.03
                                  :currency "EUR"
                                  :source "service-reported"}})
        markup (render state)]
    (is (str/includes? markup "估算：0.02 USD；来源：service"))
    (is (str/includes? markup "实收：0.03 EUR；来源：service-reported")))
  (testing "a missing actual charge is not called unestimated"
    (let [markup (render (fixture/state))]
      (is (str/includes? markup "实收：无实收记录"))
      (is (str/includes? markup "估算：0.02 USD")))))

(deftest service-unit-summary-and-unknown-currency-test
  (let [markup (render (fixture/state))]
    (is (str/includes? markup "按单位汇总"))
    (is (str/includes? markup "币种未提供"))
    (is (str/includes? markup "次服务用量没有币种"))))

(deftest missing-duration-is-explicit-test
  (let [base (render (fixture/state))
        recorded (render (-> (fixture/state)
                             (assoc-in [:stats :turns 0 :requests 0
                                        :first-token-at]
                                       (+ 1762185600000 1000))
                             (assoc-in [:stats :turns 0 :requests 0
                                        :generation-ms]
                                       1500)))]
    (testing "absent first-token and generation times say 未记录"
      (is (str/includes? base "未记录")))
    (testing "present times render normally"
      (is (str/includes? recorded "1.0 s"))
      (is (str/includes? recorded "1500 ms")))))

(deftest bundle-material-checkbox-reflects-include-test
  (let [unchecked (render (bundle-state))
        checked (render (-> (bundle-state)
                            (assoc-in [:bundle :request :materials]
                                      [{:id :detailed-log
                                        :title "Detailed request log"
                                        :kind :log
                                        :purpose "metadata"
                                        :include? true}])))]
    (is (str/includes? unchecked "Detailed request log"))
    (is (not (str/includes? unchecked "checked=\"\"")))
    (is (str/includes? checked "checked=\"\""))))

(defmethod cljs.test/report :end-run-tests
  [m]
  (when (pos? (+ (:fail m) (:error m)))
    (set! (.-exitCode js/process) 1)))

(defn -main
  [& _]
  (run-tests 'hc.client.diagnostics.ui-test))

(set! *main-cli-fn* -main)

(deftest tagged-money-renders-as-currency-and-number-test
  (let [state (-> (fixture/state)
                  (assoc-in [:stats :cost :by-currency "USD" :estimated-amount]
                            {:tag "f" :rep 0.003275})
                  (assoc-in [:stats :cost :by-currency "EUR" :estimated-amount]
                            0.02)
                  (assoc-in [:stats :cost :by-currency "GBP" :estimated-amount]
                            {:tag "f" :rep "not-a-number"}))
        markup (render state)]
    (testing "a tagged estimate is a currency amount, not a TaggedValue"
      (is (str/includes? markup "USD 0.003275"))
      (is (not (str/includes? markup "TaggedValue"))))
    (testing "a plain number keeps its currency and exact value"
      (is (str/includes? markup "EUR 0.02")))
    (testing "an unwrap that is not numeric is unknown, never zero"
      (is (str/includes? markup "GBP 未知")))))
