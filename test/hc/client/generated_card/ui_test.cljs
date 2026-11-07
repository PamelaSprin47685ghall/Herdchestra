(ns hc.client.generated-card.ui-test
  "Real React render tests for the generated form/chart card.

  These render the actual React element tree with `react-dom/server`; they do
  not inspect the model in isolation.  The form card must disclose generated
  content, labels, units, required fields, hidden conditional fields, and the
  local-vs-assistant action boundary.  The chart card must disclose axes,
  units, provenance, missing values, the shared data table, and export
  actions."
  (:require ["react" :as react]
            ["react-dom/server" :as rds]
            [cljs.test :refer-macros [deftest is testing run-tests]]
            [clojure.string :as str]
            [hc.client.generated-card.view :as view]))

(def form-card
  {:card-id "form-1"
   :kind :form
   :title "提交月度预算"
   :purpose "填写本月预算并选择是否附带说明。"
   :provenance :model
   :generated-by "assistant"
   :generated-at "2026-11-03T10:00:00Z"
   :form {:fields [{:name :amount
                    :type :number
                    :label "预算金额"
                    :unit "元"
                    :required? true
                    :default 100
                    :min 0
                    :max 10000}
                   {:name :date
                    :type :date
                    :label "生效日期"
                    :required? true
                    :default "2026-11-03"
                    :timezone "Asia/Shanghai"}
                   {:name :mode
                    :type :select
                    :label "预算模式"
                    :default "monthly"
                    :options [{:value "monthly" :label "按月"}
                              {:value "yearly" :label "按年"}]}
                   {:name :note
                    :type :textarea
                    :label "说明"
                    :max-length 120}
                   {:name :has-attachment
                    :type :boolean
                    :label "有附件"}
                   {:name :attachment
                    :type :text
                    :label "附件说明"
                    :visible-when {:field :has-attachment :equals true}}
                   {:name :token
                    :type :secret
                    :label "凭据"
                    :required? true}]}})

(def chart-card
  {:card-id "chart-1"
   :kind :chart
   :title "月度用量"
   :purpose "查看每月用量与费用；缺失值不按零处理。"
   :provenance :user
   :source "meter.csv"
   :time-range "2026-01..2026-05"
   :show-table? true
   :chart {:type :bar
           :x-label "月份"
           :y-label "数值"
           :series [{:key :usage :label "用量" :unit "kWh"}
                    {:key :cost :label "费用" :unit "元"}]
           :data [{:x "2026-01" :usage 120 :cost 30}
                  {:x "2026-02" :usage nil :cost 32}
                  {:x "2026-03" :usage 150 :cost nil}
                  {:x "2026-04" :usage 90 :cost 20}
                  {:x "2026-05" :usage 110 :cost 25}]}})

(defn- html
  [card]
  (rds/renderToStaticMarkup
   (react/createElement view/generated-card #js {:props {:card card}})))

(deftest form-card-renders-generated-disclosure-and-fields
  (let [markup (html form-card)]
    (testing "generated-content disclosure and provenance"
      (is (str/includes? markup "生成的交互内容"))
      (is (str/includes? markup "模型生成"))
      (is (str/includes? markup "提交月度预算"))
      (is (str/includes? markup "填写本月预算"))
      (is (str/includes? markup "data-generated=\"true\"")))
    (testing "field labels, units, required markers, and defaults"
      (is (str/includes? markup "预算金额"))
      (is (str/includes? markup "元"))
      (is (str/includes? markup "生效日期"))
      (is (str/includes? markup "Asia/Shanghai"))
      (is (str/includes? markup "hc-gencard-required"))
      (is (str/includes? markup "value=\"100\""))
      (is (str/includes? markup "type=\"date\"")))
    (testing "conditional field is not rendered while its condition is false"
      (is (not (str/includes? markup "附件说明"))))
    (testing "secret field is visible but explicitly local-only"
      (is (str/includes? markup "凭据"))
      (is (str/includes? markup "秘密字段只保留在本卡")))
    (testing "local and assistant actions are distinct"
      (is (str/includes? markup "仅保存到本卡"))
      (is (str/includes? markup "发送给助手"))
      (is (str/includes? markup "当前卡片只支持本地填写"))
      (is (str/includes? markup "本地填写、预览、保存不会发起模型请求")))))

(deftest chart-card-renders-axes-source-and-shared-table
  (let [markup (html chart-card)]
    (testing "chart disclosure, axes, units, and source"
      (is (str/includes? markup "生成的交互内容"))
      (is (str/includes? markup "用户提供"))
      (is (str/includes? markup "月度用量"))
      (is (str/includes? markup "月份"))
      (is (str/includes? markup "数值"))
      (is (str/includes? markup "kWh"))
      (is (str/includes? markup "meter.csv"))
      (is (str/includes? markup "2026-01..2026-05"))
      (is (str/includes? markup "role=\"img\"")))
    (testing "legend and exact-value readout"
      (is (str/includes? markup "用量 (kWh)"))
      (is (str/includes? markup "费用 (元)"))
      (is (str/includes? markup "悬停或聚焦数据点查看精确值")))
    (testing "shared data table includes missing values and totals"
      (is (str/includes? markup "隐藏数据"))
      (is (str/includes? markup "<table"))
      (is (str/includes? markup "2026-02"))
      (is (str/includes? markup "缺失"))
      (is (str/includes? markup "共 5 行"))
      (is (str/includes? markup "缺失值")))
    (testing "export and reset actions are local"
      (is (str/includes? markup "重置视图"))
      (is (str/includes? markup "导出图表 SVG"))
      (is (str/includes? markup "导出当前页 CSV"))
      (is (str/includes? markup "导出全部筛选 CSV"))
      (is (str/includes? markup "不会发起模型请求")))))

(deftest generating-and-failed-states-do-not-look-ready
  (let [generating (html (assoc form-card :state :generating))
        failed (html (assoc chart-card :state :failed))]
    (is (str/includes? generating "正在生成"))
    (is (str/includes? generating "卡片尚未完成生成，不能发送"))
    (is (str/includes? failed "生成未完成"))
    (is (str/includes? failed "文字说明"))))

(deftest illustrative-chart-is-marked
  (let [markup (html (assoc chart-card :illustrative? true))]
    (is (str/includes? markup "示意"))
    (is (str/includes? markup "不是真实测量"))
    (is (str/includes? markup "不是真实测量"))))

(defmethod cljs.test/report :end-run-tests
  [m]
  (when (pos? (+ (:fail m) (:error m)))
    (set! (.-exitCode js/process) 1)))

(defn -main
  [& _]
  (run-tests 'hc.client.generated-card.ui-test))

(set! *main-cli-fn* -main)
