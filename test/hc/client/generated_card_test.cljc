(ns hc.client.generated-card-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hc.client.generated-card :as card]
            [hc.client.generated-card.model :as model]))

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
   :illustrative? false
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

(deftest form-initial-values-and-visibility
  (let [values (card/initial-values form-card)]
    (is (= 100 (:amount values)))
    (is (= "2026-11-03" (:date values)))
    (is (= "monthly" (:mode values)))
    (is (false? (:has-attachment values)))
    (is (contains? values :attachment))
    (is (not-any? #(= :attachment (card/field-name %))
                  (card/visible-fields form-card values)))
    (is (some #(= :attachment (card/field-name %))
              (card/visible-fields form-card (assoc values :has-attachment true))))
    (is (some #(= :token (card/field-name %))
              (card/visible-fields form-card values)))))

(deftest form-validation-and-submission
  (let [values (card/initial-values form-card)]
    (testing "valid defaults submit typed visible non-secret fields"
      (let [result (card/submission form-card values)]
        (is (:ok? result))
        (is (= 100 (:amount (:values result))))
        (is (= "2026-11-03" (:date (:values result))))
        (is (= "monthly" (:mode (:values result))))
        (is (not (contains? (:values result) :token)))
        (is (not (contains? (:values result) :attachment)))
        (is (some #{:token} (:excluded-secret-fields result)))
        (is (some #{:attachment} (:hidden-fields result)))
        (is (str/includes? (:text result) "预算金额：100 元"))))
    (testing "required and numeric bounds"
      (let [result (card/validate form-card (assoc values :amount ""))]
        (is (false? (:ok? result)))
        (is (= :amount (:first-error result)))
        (is (= "必填" (get-in result [:errors :amount]))))
      (let [result (card/validate form-card (assoc values :amount 20000))]
        (is (false? (:ok? result)))
        (is (str/includes? (get-in result [:errors :amount]) "不能大于")))
      (let [result (card/validate form-card (assoc values :amount "abc"))]
        (is (false? (:ok? result)))
        (is (= "请输入数字" (get-in result [:errors :amount])))))
    (testing "date format and select options"
      (is (= "请使用 YYYY-MM-DD 格式"
             (get-in (card/validate form-card (assoc values :date "11/03/2026"))
                     [:errors :date])))
      (is (= "请选择有效选项"
             (get-in (card/validate form-card (assoc values :mode "weekly"))
                     [:errors :mode]))))
    (testing "hidden conditional fields are not validated or submitted"
      (let [bad-hidden (assoc values :has-attachment false :attachment "")
            result (card/submission form-card bad-hidden)]
        (is (:ok? result))
        (is (not (contains? (:values result) :attachment)))))
    (testing "secret fields are excluded even when required and blank"
      (let [result (card/submission form-card (assoc values :token ""))]
        (is (:ok? result))
        (is (not (contains? (:values result) :token)))))
    (testing "preview labels secrets and hidden fields"
      (let [{:keys [rows secret-fields hidden-fields]}
            (card/submission-preview form-card values)]
        (is (some #(= :token (:name %)) rows))
        (is (some #(= "已排除（秘密字段）" (:display %)) rows))
        (is (= [:token] secret-fields))
        (is (some #{:attachment} hidden-fields))))))

(deftest form-field-formatting
  (let [amount {:name :amount :type :number :label "预算" :unit "元"}
        date {:name :date :type :date :label "日期" :timezone "Asia/Shanghai"}
        flag {:name :flag :type :boolean :label "开关"}
        mode {:name :mode :type :select :label "模式"
              :options [{:value "a" :label "A"}]}]
    (is (= "100 元" (card/display-value amount 100)))
    (is (= "2026-11-03 · Asia/Shanghai" (card/display-value date "2026-11-03")))
    (is (= "是" (card/display-value flag true)))
    (is (= "A" (card/display-value mode "a")))
    (is (= "••••••" (card/display-value {:name :secret :type :secret :secret? true} "x")))))

(deftest chart-series-columns-and-filters
  (let [series (card/series chart-card)
        rows (card/rows chart-card)
        columns (card/columns chart-card)]
    (is (= [:usage :cost] (mapv :key series)))
    (is (= ["用量" "费用"] (mapv :label series)))
    (is (= ["kWh" "元"] (mapv :unit series)))
    (is (= [:x :usage :cost] (mapv :key columns)))
    (is (= ["月份" "用量" "费用"] (mapv :label columns)))
    (testing "category and all-missing filters use the same rows"
      (is (= ["2026-01"] (mapv :x (card/filter-rows rows {:x "2026-01"}))))
      (is (= 5 (count (card/filter-rows rows {:hide-missing? true}))))
      (is (= 1 (count (card/filter-rows [{:x "a" :usage 1}
                                         {:x "b" :usage nil}]
                                        {:hide-missing? true}))))
      (is (= 5 (count (card/filter-rows rows {:x :all :hide-missing? false}))))
      (is (= ["类别：2026-01" "隐藏全缺失行"]
             (card/active-filter-labels {:x "2026-01" :hide-missing? true}))))
    (testing "sorting is type-aware and keeps missing values last"
      (let [asc (card/sort-rows rows columns {:key :usage :direction :asc})
            desc (card/sort-rows rows columns {:key :usage :direction :desc})]
        (is (= [90 110 120 150 nil]
               (mapv #(get % :usage) asc)))
        (is (= [150 120 110 90 nil]
               (mapv #(get % :usage) desc)))
        (is (= [20 25 30 32 nil]
               (mapv #(get % :cost)
                     (card/sort-rows rows columns {:key :cost :direction :asc})))))
    (testing "paging reports completeness"
      (let [sorted (card/sort-rows rows columns {:key :usage :direction :asc})
            page (card/page-rows sorted 0 (assoc-in chart-card [:chart :page-size] 2))
            info (card/page-info sorted 0 (assoc-in chart-card [:chart :page-size] 2))]
        (is (= 2 (count page)))
        (is (= {:page 0 :page-count 3 :start 1 :end 2 :total 5 :complete? false} info)))))))

(deftest chart-summary-domain-and-points
  (let [rows (card/rows chart-card)
        summary (card/summary chart-card rows {})
        domain (card/chart-domain chart-card rows)
        points (card/series-points chart-card rows)]
    (testing "missing values are excluded from stats, never zero"
      (is (= 5 (:total summary)))
      (is (= 5 (:visible summary)))
      (is (= 2 (:missing summary)))
      (let [usage (first (:stats summary))]
        (is (= 4 (:count usage)))
        (is (= 90 (:min usage)))
        (is (= 150 (:max usage)))
        (is (= 470 (:sum usage)))))
    (testing "bar domain includes zero and y ticks cover the range"
      (is (<= (first domain) 0))
      (is (>= (second domain) 150))
      (is (= 5 (count (card/y-ticks domain)))))
    (testing "series points skip missing values and retain x labels"
      (let [usage (first points)]
        (is (= 4 (count (:points usage))))
        (is (= ["2026-01" "2026-03" "2026-04" "2026-05"]
               (mapv :x (:points usage))))))
    (testing "empty filter has an explicit empty chart and summary"
      (let [empty-rows (card/filter-rows rows {:x "2026-99"})]
        (is (empty? empty-rows))
        (is (nil? (card/chart-domain chart-card empty-rows)))
        (is (= 0 (:visible (card/summary chart-card rows {:x "2026-99"}))))))))

(deftest chart-csv-and-text
  (let [rows (card/rows chart-card)
        filter {:x "2026-01" :hide-missing? false}
        text (card/csv chart-card rows filter)
        all (card/csv chart-card rows {} {:page 1 :current-page? true})]
    (testing "CSV carries title, source, filter, header units, and exact values"
      (is (str/includes? text "# 标题: 月度用量"))
      (is (str/includes? text "# 来源: meter.csv"))
      (is (str/includes? text "# 筛选: 类别：2026-01"))
      (is (str/includes? text "月份,用量 (kWh),费用 (元)"))
      (is (str/includes? text "2026-01,120,30")))
    (testing "current-page export names the page range and keeps all columns"
      (is (str/includes? all "# 范围: 当前页 1 / 1"))
      (is (str/includes? all "2026-01,120,30")))
    (testing "chart text states axes, unit, provenance, and missing count"
      (let [text (card/chart-text chart-card rows {})]
        (is (str/includes? text "横轴：月份；纵轴：数值"))
        (is (str/includes? text "来源：用户提供 · meter.csv"))
        (is (str/includes? text "缺失 2 个"))))))

(deftest card-shell-labels
  (is (= "生成的交互内容" (card/generated-label form-card)))
  (is (= "模型生成" (card/provenance-label form-card)))
  (is (= "用户提供" (card/provenance-label chart-card)))
  (is (= :chart (card/kind chart-card)))
  (is (= :form (card/kind form-card)))
  (is (nil? (card/state-label form-card)))
  (is (= "正在生成" (card/state-label (assoc form-card :state :generating))))
  (is (= "生成未完成" (card/state-label (assoc form-card :state :failed))))
  (is (card/illustrative? (assoc chart-card :illustrative? true))))


(deftest mixed-chart-units-are-not-labelled-as-one-series
  (is (= "多单位" (card/chart-unit-label chart-card)))
  (is (nil? (card/chart-unit chart-card)))
  (let [single (assoc-in chart-card [:chart :series] [{:key :usage :label "用量" :unit "kWh"}])]
    (is (= "kWh" (card/chart-unit-label single)))
    (is (= "kWh" (card/chart-unit single)))))


(deftest normalized-select-defaults-use-option-values
  (let [normalized (card/normalize-card form-card)
        values (card/initial-values normalized)]
    (is (= :monthly (:mode values)))
    (is (:ok? (card/validate normalized values)))
    (is (:ok? (card/validate normalized (assoc values :mode :yearly))))
    (is (= :yearly (:mode (:values (card/submission normalized
                                                    (assoc values :mode :yearly))))))))
