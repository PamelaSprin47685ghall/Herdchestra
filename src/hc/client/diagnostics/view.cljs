(ns hc.client.diagnostics.view
  "React view for the statistics and diagnostics workspace.

  The view is controlled: it receives a plain state map and a dispatch
  callback.  It never starts a request on mount, never invents a statistic,
  and never uploads a diagnostic bundle.  All amounts are grouped by currency;
  no cross-currency total exists.  Unknown usage and unknown prices are shown
  as unknown, never as zero."
  (:require [clojure.string :as str]
            [com.fulcrologic.fulcro.dom :as dom]
            [hc.client.diagnostics.model :as model]
            ["react" :as react]))

(def diagnostics-css
  ".hc-diagnostics {
     --hc-fg: #17212b;
     --hc-muted: #4b5a68;
     --hc-bg: #ffffff;
     --hc-surface: #f5f7fa;
     --hc-surface-2: #e9eef4;
     --hc-border: #c5ced8;
     --hc-accent: #0b5f8a;
     --hc-accent-ink: #ffffff;
     --hc-positive: #1f6b3a;
     --hc-warning: #7a4a00;
     --hc-negative: #a3261f;
     color: var(--hc-fg);
     background: var(--hc-bg);
     overflow-wrap: anywhere;
   }
   @media (prefers-color-scheme: dark) {
     html[data-theme=\"auto\"] .hc-diagnostics,
     html[data-theme=\"system\"] .hc-diagnostics {
       --hc-fg: #eef4f9;
       --hc-muted: #c2ccd6;
       --hc-bg: #11161c;
       --hc-surface: #182029;
       --hc-surface-2: #202b36;
       --hc-border: #52606d;
       --hc-accent: #7fc4e8;
       --hc-accent-ink: #08131a;
       --hc-positive: #7ddc9a;
       --hc-warning: #ffd479;
       --hc-negative: #ff8a8a;
     }
   }
   html[data-theme=\"dark\"] .hc-diagnostics {
     --hc-fg: #eef4f9;
     --hc-muted: #c2ccd6;
     --hc-bg: #11161c;
     --hc-surface: #182029;
     --hc-surface-2: #202b36;
     --hc-border: #52606d;
     --hc-accent: #7fc4e8;
     --hc-accent-ink: #08131a;
     --hc-positive: #7ddc9a;
     --hc-warning: #ffd479;
     --hc-negative: #ff8a8a;
   }
   .hc-diagnostics__header h1 { margin: 0 0 .25rem; font-size: 1.35rem; }
   .hc-diagnostics__header p { margin: 0 0 .75rem; color: var(--hc-muted); }
   .hc-diagnostics__tabs {
     display: flex;
     flex-wrap: wrap;
     gap: .5rem;
     border-bottom: 1px solid var(--hc-border);
     margin-bottom: 1rem;
   }
   .hc-diagnostics__tab {
     min-height: 44px;
     padding: .5rem .85rem;
     border: 1px solid transparent;
     border-bottom: 3px solid transparent;
     background: transparent;
     color: var(--hc-fg);
     font: inherit;
     cursor: pointer;
   }
   .hc-diagnostics__tab[aria-selected=\"true\"] {
     border-bottom-color: var(--hc-accent);
     font-weight: 700;
   }
   .hc-diagnostics button, .hc-diagnostics select, .hc-diagnostics input,
   .hc-diagnostics textarea { font: inherit; }
   .hc-diagnostics button {
     min-height: 44px;
     padding: .45rem .8rem;
     border: 1px solid var(--hc-border);
     border-radius: .45rem;
     background: var(--hc-surface);
     color: var(--hc-fg);
     cursor: pointer;
   }
   .hc-diagnostics .hc-diag-button {
     display: inline-flex; align-items: center; justify-content: center;
     min-height: 44px; padding: .45rem .8rem;
     border: 1px solid var(--hc-border); border-radius: .45rem;
     background: var(--hc-surface); color: var(--hc-fg);
     font: inherit; text-decoration: none; cursor: pointer;
   }
   .hc-diagnostics button[aria-pressed=\"true\"] {
     background: var(--hc-accent);
     color: var(--hc-accent-ink);
     border-color: var(--hc-accent);
   }
   .hc-diagnostics button:disabled { cursor: not-allowed; opacity: .55; }
   .hc-diagnostics button:focus-visible,
   .hc-diagnostics input:focus-visible,
   .hc-diagnostics select:focus-visible,
   .hc-diagnostics textarea:focus-visible,
   .hc-diagnostics summary:focus-visible,
   .hc-diagnostics [tabindex]:focus-visible {
     outline: 3px solid var(--hc-accent);
     outline-offset: 2px;
   }
   .hc-diag-filters {
     display: grid;
     grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
     gap: .75rem;
     padding: .85rem;
     border: 1px solid var(--hc-border);
     border-radius: .6rem;
     background: var(--hc-surface);
     margin-bottom: 1rem;
   }
   .hc-diag-field { display: grid; gap: .25rem; color: var(--hc-muted); font-size: .9rem; }
   .hc-diag-field input, .hc-diag-field select, .hc-diag-field textarea {
     min-height: 44px;
     padding: .4rem .55rem;
     border: 1px solid var(--hc-border);
     border-radius: .35rem;
     background: var(--hc-bg);
     color: var(--hc-fg);
   }
   .hc-diag-field textarea { min-height: 6rem; resize: vertical; }
   .hc-diag-actions { display: flex; flex-wrap: wrap; gap: .5rem; align-items: end; }
   .hc-diag-span { grid-column: 1 / -1; }
   .hc-diag-check {
     display: flex; align-items: flex-start; gap: .5rem;
     min-height: 44px; color: var(--hc-fg); font-size: .9rem;
   }
   .hc-diag-check input {
     width: 1.25rem; height: 1.25rem; margin: .1rem 0 0; flex: none;
   }
   .hc-diag-facts {
     display: grid; grid-template-columns: minmax(8rem, auto) minmax(0, 1fr);
     gap: .3rem .9rem; margin: .5rem 0;
   }
   .hc-diag-facts dt { color: var(--hc-muted); font-weight: 600; }
   .hc-diag-facts dd { margin: 0; overflow-wrap: anywhere; }
   .hc-diag-summary {
     display: flex;
     flex-wrap: wrap;
     gap: .5rem 1.25rem;
     padding: .65rem .85rem;
     margin-bottom: 1rem;
     border-left: 4px solid var(--hc-accent);
     background: var(--hc-surface);
     color: var(--hc-muted);
     font-size: .9rem;
   }
   .hc-diag-cards {
     display: grid;
     grid-template-columns: repeat(auto-fit, minmax(170px, 1fr));
     gap: .75rem;
     margin-bottom: 1rem;
   }
   .hc-diag-card {
     border: 1px solid var(--hc-border);
     border-radius: .55rem;
     background: var(--hc-surface);
     padding: .8rem;
   }
   .hc-diag-card__value { font-size: 1.6rem; font-weight: 700; }
   .hc-diag-card__label { font-weight: 600; }
   .hc-diag-card__definition { color: var(--hc-muted); font-size: .82rem; }
   .hc-diag-panel {
     border: 1px solid var(--hc-border);
     border-radius: .6rem;
     background: var(--hc-surface);
     padding: .9rem;
     margin-bottom: 1rem;
   }
   .hc-diag-panel h2, .hc-diag-panel h3 { margin-top: 0; }
   .hc-diag-muted { color: var(--hc-muted); }
   .hc-diag-warning { color: var(--hc-warning); }
   .hc-diag-negative { color: var(--hc-negative); }
   .hc-diag-positive { color: var(--hc-positive); }
   .hc-diag-table-wrap { overflow-x: auto; max-width: 100%; }
   .hc-diag-table { border-collapse: collapse; width: 100%; min-width: 32rem; }
   .hc-diag-table th, .hc-diag-table td {
     border-bottom: 1px solid var(--hc-border);
     padding: .4rem .5rem;
     text-align: left;
     vertical-align: top;
   }
   .hc-diag-table th { color: var(--hc-muted); font-weight: 600; }
   .hc-diag-token-total { font-weight: 700; }
   .hc-diag-currency {
     border: 1px solid var(--hc-border);
     border-radius: .5rem;
     padding: .7rem;
     margin-bottom: .6rem;
     background: var(--hc-bg);
   }
   .hc-diag-currency__head { display: flex; justify-content: space-between; gap: .5rem; }
   .hc-diag-model-row { display: grid; grid-template-columns: minmax(10rem, 1fr) 2fr auto; gap: .5rem; align-items: center; margin-bottom: .4rem; }
   .hc-diag-bar { height: .7rem; border-radius: 999px; background: var(--hc-surface-2); overflow: hidden; }
   .hc-diag-bar__fill { height: 100%; background: var(--hc-accent); }
   .hc-diag-heatmap { display: flex; flex-wrap: wrap; gap: .35rem; }
   .hc-diag-day {
     min-width: 5.2rem;
     min-height: 44px;
     display: grid;
     place-items: center;
     border: 1px solid var(--hc-border);
     border-radius: .4rem;
     background: var(--hc-bg);
     color: var(--hc-fg);
     font-size: .78rem;
     line-height: 1.2;
     padding: .3rem;
     cursor: pointer;
   }
   .hc-diag-day[aria-pressed=\"true\"] { border-color: var(--hc-accent); box-shadow: inset 0 0 0 2px var(--hc-accent); }
   .hc-diag-day strong { font-size: .9rem; white-space: nowrap; }
   .hc-diag-turn { border-top: 1px solid var(--hc-border); padding: .45rem 0; }
   .hc-diag-turn summary { min-height: 44px; cursor: pointer; display: flex; flex-wrap: wrap; gap: .5rem 1rem; align-items: center; }
   .hc-diag-request {
     border-left: 3px solid var(--hc-border);
     padding: .4rem .6rem;
     margin: .4rem 0 .4rem .4rem;
     background: var(--hc-bg);
   }
   .hc-diag-pill {
     display: inline-block;
     padding: .05rem .45rem;
     border-radius: 999px;
     border: 1px solid var(--hc-border);
     font-size: .78rem;
   }
   .hc-diag-empty { padding: 1rem; border: 1px dashed var(--hc-border); border-radius: .5rem; color: var(--hc-muted); }
   .hc-diag-preview-line { display: grid; grid-template-columns: minmax(8rem, 1fr) 2fr auto; gap: .5rem; align-items: start; padding: .35rem 0; border-bottom: 1px solid var(--hc-border); }
   .hc-diag-preview-value { white-space: pre-wrap; }
   .hc-diag-transfer { border: 1px solid var(--hc-positive); border-radius: .5rem; padding: .6rem; margin: .6rem 0; }
   .hc-diag-message { border-left: 4px solid var(--hc-accent); background: var(--hc-surface-2); padding: .5rem .75rem; margin-bottom: .75rem; display: flex; justify-content: space-between; gap: .5rem; }
   .hc-diag-findings { max-height: 14rem; overflow: auto; }
   @media (max-width: 720px) {
     .hc-diag-model-row { grid-template-columns: 1fr; }
     .hc-diag-preview-line { grid-template-columns: 1fr; }
     .hc-diag-facts { grid-template-columns: 1fr; gap: .15rem; }
     .hc-diag-facts dd { margin-bottom: .35rem; }
     .hc-diagnostics__tabs { overflow-x: auto; flex-wrap: nowrap; }
   }
   @media (min-width: 1100px) {
     .hc-diag-columns { display: grid; grid-template-columns: 1fr 1fr; gap: 1rem; }
   }")

;; ---------------------------------------------------------------------------
;; Formatting helpers

(defn- value-or
  [value fallback]
  (if (or (nil? value) (= :unknown value) (= "" value))
    fallback
    value))

(defn- format-int
  [value]
  (cond
    (nil? value) "未提供"
    (= :unknown value) "未提供"
    (integer? value) (str value)
    (number? value) (str (long value))
    :else (str value)))

(defn- format-quantity
  [value]
  (cond
    (nil? value) "未提供"
    (= :unknown value) "未提供"
    (integer? value) (str value)
    (number? value) (let [text (str (double value))]
                      (if (str/ends-with? text ".0")
                        (subs text 0 (- (count text) 2))
                        text))
    :else (str value)))

(defn- amount-text
  [amount]
  (let [amount (model/unwrap-amount amount)
        number (model/amount-number amount)]
    (cond
      (some? number) (model/format-amount-number number)
      (or (nil? amount) (= :unknown amount)) nil
      :else "未知")))

(defn- with-currency
  [text currency]
  (if (and text currency (not (str/blank? (str currency))))
    (str currency " " text)
    text))

(defn- format-amount
  ([amount hide?] (format-amount amount hide? nil))
  ([amount hide? currency]
   (cond
     hide? "金额已隐藏"
     (nil? amount) "未估算"
     (= :unknown amount) "未估算"
     :else (let [text (amount-text amount)]
             (if text
               (with-currency text currency)
               "未估算")))))

(defn format-actual-amount
  "Format a service-reported actual charge.

  An absent actual amount means the service did not report one; it is not the
  same as an estimate that could not be calculated."
  ([amount] (format-actual-amount amount false))
  ([amount hide?]
   (cond
     hide? "金额已隐藏"
     (nil? amount) "无实收记录"
     (= :unknown amount) "无实收记录"
     :else (or (amount-text amount) "未知"))))

(defn- format-estimate-amount
  [amount hide?]
  (format-amount amount hide?))

(defn- format-ratio
  [value]
  (if (number? value)
    (str (.toFixed (* 100.0 value) 1) "%")
    "—"))

(defn- timestamp-label
  [value zone]
  (cond
    (number? value)
    (try
      (let [date (js/Date. value)
            options (let [options #js {:year "numeric" :month "2-digit"
                                       :day "2-digit" :hour "2-digit"
                                       :minute "2-digit"}]
                      (when-not (str/blank? (str zone))
                        (aset options "timeZone" zone))
                      options)]
        (.toLocaleString date "zh-CN" options))
      (catch :default _
        (str value)))

    (and (string? value) (not (str/blank? value)))
    value

    :else nil))

(defn- duration-label
  [started finished]
  (when (and (number? started) (number? finished)
             (<= started finished))
    (let [ms (- finished started)]
      (cond
        (< ms 1000) (str ms " ms")
        (< ms 60000) (str (.toFixed (/ ms 1000.0) 1) " s")
        :else (str (.toFixed (/ ms 60000.0) 1) " min")))))

(defn- on-value
  [dispatch action]
  (fn [event]
    (dispatch (assoc action :value (.. event -target -value)))))

(defn- on-checked
  [dispatch action]
  (fn [event]
    (dispatch (assoc action :value (.. event -target -checked)))))

(defn- button
  [label action dispatch & [attrs]]
  (dom/button (merge {:type "button"
                      :className "hc-diag-button"
                      :onClick (fn [_] (dispatch action))}
                     attrs)
              label))

(defn- field
  [label control & [hint]]
  (dom/label {:className "hc-diag-field"}
             (dom/span {} label)
             control
             (when hint (dom/small {:className "hc-diag-muted"} hint))))

(defn- select-field
  [label value options on-change]
  (field label
         (dom/select {:value (if (keyword? value)
                               (name value)
                               (str (or value "")))
                      :onChange on-change}
                     (for [{:keys [value label]} options]
                       (dom/option {:key (str value)
                                    :value (if (keyword? value)
                                             (name value)
                                             (str (or value "")))}
                                   label)))))

(defn- text-field
  [label value on-change & [opts]]
  (field label
         (dom/input (merge {:type "text"
                            :value (or value "")
                            :onChange on-change}
                           opts))))

(defn- textarea-field
  [label value on-change]
  (field label
         (dom/textarea {:value (or value "")
                        :onChange on-change})))

(defn- checkbox-field
  [label checked? on-change]
  (dom/label {:className "hc-diag-check"}
             (dom/input {:type "checkbox"
                         :checked (boolean checked?)
                         :onChange on-change})
             (dom/span {} label)))

(defn- safe-label
  "Render a keyword/string safely; a tool result may be a map, which `name`
  cannot accept.  Maps fall back to their status/outcome or a bounded EDN
  excerpt so the usage row never crashes the whole diagnostics page."
  [value]
  (cond
    (keyword? value) (name value)
    (symbol? value) (name value)
    (string? value) value
    (map? value) (or (some-> (or (:status value) (:outcome value) (:state value))
                             safe-label)
                     (let [text (pr-str value)]
                       (if (> (count text) 120)
                         (str (subs text 0 117) "...")
                         text)))
    (nil? value) "unknown"
    :else (str value)))

(defn- badge
  [text tone]
  (dom/span {:className "hc-diag-pill"
             :data-tone (name (or tone :neutral))}
            text))

(defn- yes-no-unknown
  [value]
  (cond
    (true? value) "是"
    (false? value) "否"
    :else "未提供"))

(defn- result-tone
  [result]
  (case (model/keyword-value result)
    :succeeded :positive
    :ok :positive
    :failed :negative
    :cancelled :warning
    :timeout :warning
    :outcome-unknown :warning
    :neutral))

(defn- fact-list
  [rows]
  (dom/dl {:className "hc-diag-facts"}
          (mapcat (fn [[label value]]
                    [(dom/dt {} label)
                     (dom/dd {} (value-or value "—"))])
                  rows)))

(defn- empty-panel
  [title text]
  (dom/div {:className "hc-diag-empty"}
           (dom/strong {} title)
           (dom/div {} text)))

;; ---------------------------------------------------------------------------
;; Statistics filters

(defn- range-buttons
  [state dispatch]
  (let [selected (get-in state [:query :preset])]
    (dom/div {:className "hc-diag-actions hc-diag-span" :role "group" :aria-label "时间范围"}
             (for [{:keys [id label]} model/range-presets]
               (dom/button {:key (name id)
                            :type "button"
                            :aria-pressed (if (= id selected) "true" "false")
                            :onClick (fn [_]
                                       (dispatch {:type :diagnostics/select-range
                                                  :preset id}))}
                           label)))))

(defn- filter-bar
  [state dispatch]
  (let [query (:query state)
        options (:filter-options state)
        filters (:filters query)
        custom? (= :custom (:preset query))
        synced? (true? (:can-submit? state))]
    (dom/section {:className "hc-diag-filters" :aria-label "统计筛选"}
                 (range-buttons state dispatch)
                 (when custom?
                   (dom/div {:className "hc-diag-actions hc-diag-span"}
                            (text-field "开始日期" (:from query)
                                        (on-value dispatch
                                                  {:type :diagnostics/set-custom-date
                                                   :field :from})
                                        {:type "date"})
                            (text-field "结束日期" (:to query)
                                        (on-value dispatch
                                                  {:type :diagnostics/set-custom-date
                                                   :field :to})
                                        {:type "date"})))
                 (text-field "时区" (:zone query)
                             (on-value dispatch {:type :diagnostics/set-zone})
                             {:placeholder "设备时区"})
                 (select-field "日期口径"
                               (:zone-mode query)
                               [{:value :query :label "当前时区"}
                                {:value :record :label "记录发生时区"}]
                               (on-value dispatch
                                         {:type :diagnostics/set-zone-mode}))
                 (select-field "助手"
                               (get filters :assistant-id)
                               (into [{:value "" :label "全部助手"}]
                                     (map (fn [id] {:value id :label id}))
                                     (:assistants options))
                               (on-value dispatch
                                         {:type :diagnostics/set-filter
                                          :key :assistant-id}))
                 (select-field "模型"
                               (get filters :model)
                               (into [{:value "" :label "全部模型"}]
                                     (map (fn [row]
                                            {:value (:model row)
                                             :label (str (:service-name row)
                                                         " / " (:model row))}))
                                     (:models options))
                               (on-value dispatch
                                         {:type :diagnostics/set-filter
                                          :key :model}))
                 (select-field "服务"
                               (get filters :service-id)
                               (into [{:value "" :label "全部服务"}]
                                     (map (fn [row]
                                            {:value (:service-id row)
                                             :label (str (or (:service-name row)
                                                             (:service-id row)))}))
                                     (:services options))
                               (on-value dispatch
                                         {:type :diagnostics/set-filter
                                          :key :service-id}))
                 (text-field "模型排行数量" (get query :top-n)
                             (on-value dispatch {:type :diagnostics/set-top-n})
                             {:inputMode "numeric"})
                 (dom/div {:className "hc-diag-actions hc-diag-span"}
                          (button "刷新统计"
                                  {:type :diagnostics/refresh}
                                  dispatch
                                  {:disabled (not synced?)})
                          (button "清除筛选"
                                  {:type :diagnostics/clear-filters}
                                  dispatch)
                          (when-not synced?
                            (dom/span {:className "hc-diag-warning"}
                                      "实时通道未就绪；不会伪造统计结果。"))))))

;; ---------------------------------------------------------------------------
;; Statistics panels

(defn- count-panel
  [stats]
  (when-let [cards (model/count-cards stats)]
    (dom/div {:className "hc-diag-cards"}
             (for [card cards]
               (dom/div {:className "hc-diag-card" :key (name (:id card))}
                        (dom/div {:className "hc-diag-card__label"} (:label card))
                        (dom/div {:className "hc-diag-card__value"}
                                 (format-int (:value card)))
                        (dom/div {:className "hc-diag-card__definition"}
                                 (:definition card)))))))

(defn- token-panel
  [stats]
  (when-let [token (model/token-rows stats)]
    (dom/section {:className "hc-diag-panel" :aria-labelledby "hc-diag-token-title"}
                 (dom/h2 {:id "hc-diag-token-title"} "Token 用量")
                 (dom/div {:className "hc-diag-table-wrap"}
                          (dom/table {:className "hc-diag-table"}
                                     (dom/thead {}
                                                (dom/tr {}
                                                        (dom/th {} "项目")
                                                        (dom/th {} "数量")
                                                        (dom/th {} "来源")))
                                     (dom/tbody {}
                                                (for [row (:rows token)]
                                                  (dom/tr {:key (name (:id row))
                                                           :className (when (:emphasis? row)
                                                                        "hc-diag-token-total")}
                                                          (dom/td {} (:label row)
                                                                  (when (:detail? row)
                                                                    (dom/span {:className "hc-diag-muted"}
                                                                              "（明细）")))
                                                          (dom/td {} (format-int (:value row)))
                                                          (dom/td {} (model/provenance-label
                                                                      (:provenance row))))))))
                 (dom/p {:className "hc-diag-muted"}
                        (let [modes (or (:cache-modes token)
                                        (when-let [mode (:cache-mode token)]
                                          {mode 1}))]
                          (cond
                            (and (= 1 (count modes))
                                 (contains? modes :additional-input))
                            "缓存 token 作为额外输入侧用量单独汇总；总量已按该口径计算。"

                            (and (= 1 (count modes))
                                 (contains? modes :input-subset))
                            "缓存命中与缓存写入只作为输入明细；在 :input-subset 口径下不会再次加进总量。"

                            (seq modes)
                            "本范围混合了缓存口径；各请求按各自口径计算，缓存不会重复加入总量。"

                            :else
                            "缓存只作为输入明细展示；不会重复加入总量。")))
                 (dom/p {:className "hc-diag-muted"}
                        (str "请求用量来源：服务返回 " (format-int (:reported-requests token))
                             "；估算 " (format-int (:estimated-requests token))
                             "；未提供 " (format-int (:unknown-requests token)) "。"))
                 (when (seq (:unknown-fields token))
                   (dom/p {:className "hc-diag-warning"}
                          (str "存在未提供字段："
                               (str/join "、" (map name (keys (:unknown-fields token))))
                               "。"
                               (if (= :unknown (:total-provenance token))
                                 "总量保持未知，不按 0 计算。"
                                 "总量仍按可确定字段计算；未提供的明细不按 0 计。")))))))

(defn- cost-panel
  [state stats dispatch]
  (let [hide? (:hide-amounts? state)
        coverage (model/cost-coverage stats)
        rows (model/cost-rows stats)
        service (model/service-cost-rows stats)]
    (dom/section {:className "hc-diag-panel" :aria-labelledby "hc-diag-cost-title"}
                 (dom/h2 {:id "hc-diag-cost-title"} "估算费用")
                 (dom/p {:className "hc-diag-muted"}
                        "除非服务返回实收金额，这里都是估算。不同币种分开汇总，不生成统一总额。")
                 (dom/div {:className "hc-diag-actions"}
                          (button (if hide? "显示金额" "隐藏金额")
                                  {:type :diagnostics/set-hide-amounts
                                   :value (not hide?)}
                                  dispatch))
                 (when coverage
                   (dom/p {:className "hc-diag-muted"}
                          (str "估算覆盖 " (format-int (:estimated-requests coverage))
                               " / " (format-int (:request-count coverage))
                               " 个请求；部分估算 " (format-int (:partial-requests coverage))
                               "；未估算 " (format-int (:unestimated-requests coverage))
                               "；服务实收 " (format-int (:actual-requests coverage)) "。")))
                 (if (seq rows)
                   (for [row rows]
                     (dom/div {:className "hc-diag-currency" :key (:currency row)}
                              (dom/div {:className "hc-diag-currency__head"}
                                       (dom/strong {} (str "币种 " (:currency row)))
                                       (dom/span {} (if hide? "金额已隐藏" "")))
                              (fact-list
                               [["估算金额" (format-amount (:estimated-amount row)
                                                          hide?
                                                          (:currency row))]
                                ["服务返回实收" (format-amount (:actual-amount row)
                                                              hide?
                                                              (:currency row))]
                                ["估算请求" (format-int (:estimated-requests row))]
                                ["部分估算" (format-int (:partial-requests row))]
                                ["未估算请求" (format-int (:unestimated-requests row))]
                                ["价格来源" (str/join "、" (:pricing-sources row))]
                                ["价格日期" (str/join "、" (:price-dates row))]])))
                   (dom/p {:className "hc-diag-warning"}
                          "没有可估算的金额。未知单价或未知用量不会显示为 0。"))
                 (when coverage
                   (when (pos? (or (:unknown-currency-requests coverage) 0))
                     (dom/p {:className "hc-diag-warning"}
                            (str "有 " (:unknown-currency-requests coverage)
                                 " 个请求没有币种，未并入任何币种汇总。")))
                   (when (pos? (or (:invalid-price-requests coverage) 0))
                     (dom/p {:className "hc-diag-warning"}
                            (str "有 " (:invalid-price-requests coverage)
                                 " 个请求的价格记录无效，未估算。")))
                   (when (pos? (or (:invalid-billing-requests coverage) 0))
                     (dom/p {:className "hc-diag-warning"}
                            (str "有 " (:invalid-billing-requests coverage)
                                 " 个服务实收记录无效，未计入。")))
                   (when (pos? (or (:currency-mismatch-requests coverage) 0))
                     (dom/p {:className "hc-diag-warning"}
                            (str "有 " (:currency-mismatch-requests coverage)
                                 " 个请求的估算币种与实收币种不同，分别列出，未相加。"))))
                 (when service
                   (dom/div {}
                            (dom/h3 {} "非 token 服务用量")
                            (dom/p {:className "hc-diag-muted"}
                                   (str "用量记录 " (format-int (:usage-count service))
                                        "；服务返回数量 "
                                        (format-int (:reported-quantity-usages service))
                                        "；估算数量 "
                                        (format-int (:estimated-quantity-usages service))
                                        "；数量未知 "
                                        (format-int (:unknown-quantity-usages service))
                                        "；已估算次数 "
                                        (format-int (:estimated-usages service))
                                        "；未估算次数 "
                                        (format-int (:unestimated-usages service))
                                        "。单位不会被换算成 token。"))
                            (for [row (:by-currency service)]
                              (dom/div {:className "hc-diag-currency" :key (:currency row)}
                                       (dom/strong {} (str "币种 " (:currency row)))
                                       (fact-list
                                        [["估算金额" (format-amount (:estimated-amount row)
                                                                   hide?
                                                                   (:currency row))]
                                         ["服务返回实收" (format-actual-amount
                                                        (:actual-amount row) hide?)]
                                         ["服务返回数量" (format-int (:reported-quantity-usages row))]
                                         ["估算数量" (format-int (:estimated-quantity-usages row))]
                                         ["数量未知" (format-int (:unknown-quantity-usages row))]
                                         ["已估算次数" (format-int (:estimated-usages row))]
                                         ["未估算次数" (format-int (:unestimated-usages row))]
                                         ["服务实收" (format-int (:actual-usages row))]])
                                       (for [unit (:units row)]
                                         (dom/p {:key (:unit unit) :className "hc-diag-muted"}
                                                (str (:unit unit) "：数量 "
                                                     (format-quantity (:quantity unit))
                                                     "；次数 " (format-int (:usages unit))
                                                     "；估算 " (format-amount
                                                                (:estimated-amount unit)
                                                                hide?))))))
                            (when (seq (:by-unit service))
                              (dom/div {:className "hc-diag-currency"}
                                       (dom/h4 {} "按单位汇总")
                                       (dom/p {:className "hc-diag-muted"}
                                              "服务用量按单位保留；没有币种的金额不并入任何币种汇总。")
                                       (for [unit (:by-unit service)]
                                         (dom/p {:key (:unit unit)
                                                 :className "hc-diag-muted"}
                                                (str (:unit unit) "：数量 "
                                                     (format-quantity (:quantity unit))
                                                     "；次数 " (format-int (:usages unit))
                                                     "；估算 "
                                                     (format-amount
                                                      (:estimated-amount unit) hide?)
                                                     (when (and (not hide?)
                                                                (:currency unit))
                                                       (str " " (:currency unit)))
                                                     (when (and (not hide?)
                                                                (nil? (:currency unit))
                                                                (some? (:estimated-amount unit)))
                                                       "（币种未提供）"))))))
                            (when (pos? (or (:unknown-currency-usages service) 0))
                              (dom/p {:className "hc-diag-warning"}
                                     (str "有 " (:unknown-currency-usages service)
                                          " 次服务用量没有币种；数量仍按单位列出，"
                                          "金额未并入任何币种汇总。"))))))))

(defn- model-panel
  [state stats]
  (let [distribution (model/model-distribution stats)
        hide? (:hide-amounts? state)]
    (dom/section {:className "hc-diag-panel" :aria-labelledby "hc-diag-model-title"}
                 (dom/h2 {:id "hc-diag-model-title"} "模型分布")
                 (dom/p {:className "hc-diag-muted"}
                        "排行按使用量，不宣称“最好”或“最快”。颜色不是唯一标识，名称与数量始终可读。")
                 (if (seq (:rows distribution))
                   (dom/div {}
                            (for [row (:rows distribution)]
                              (dom/div {:className "hc-diag-model-row"
                                        :key (str (:service-id row) "/" (:model row))}
                                       (dom/div {}
                                                (dom/strong {} (:model row))
                                                (dom/div {:className "hc-diag-muted"}
                                                         (:service-name row)))
                                       (dom/div {:className "hc-diag-bar"
                                                 :role "img"
                                                 :aria-label (str (:model row) " 请求占比 "
                                                                  (format-ratio (:share row)))}
                                                (dom/div {:className "hc-diag-bar__fill"
                                                          :style {:width (format-ratio (:share row))}}))
                                       (dom/div {}
                                                (dom/div {} (str (format-int (:requests row)) " 次请求"))
                                                (dom/div {:className "hc-diag-muted"}
                                                         (model/cost-summary-label
                                                          (:cost row) hide?))))))
                   (empty-panel "没有模型请求" "当前范围没有可显示的模型用量。"))
                 (when-let [other (:other distribution)]
                   (dom/details {}
                                (dom/summary {}
                                             (str "其他 " (format-int (:models other))
                                                  " 个模型，共 "
                                                  (format-int (:requests other)) " 次请求"))
                                (for [row (:remainder distribution)]
                                  (dom/div {:className "hc-diag-model-row"
                                            :key (str (:service-id row) "/" (:model row))}
                                           (dom/span {} (str (:service-name row) " / " (:model row)))
                                           (dom/span {} (str (format-int (:requests row)) " 次请求"))
                                           (dom/span {:className "hc-diag-muted"}
                                                     (model/cost-summary-label
                                                      (:cost row) hide?))))))
                 (dom/p {:className "hc-diag-muted"}
                        "价格记录按服务与模型区分；修改价格默认影响未来请求，旧记录保留生成时的价格与币种。"))))

(defn- heatmap-panel
  [state stats dispatch]
  (let [cells (model/heatmap-cells stats)
        selected (:selected-day state)
        zone (or (get-in state [:query :zone])
                 (get-in stats [:timezone]))
        assistants (when selected
                     (model/assistants-for-day stats selected zone))]
    (dom/section {:className "hc-diag-panel" :aria-labelledby "hc-diag-heatmap-title"}
                 (dom/h2 {:id "hc-diag-heatmap-title"} "活跃热力图")
                 (dom/p {:className "hc-diag-muted"}
                        "默认按当前设备时区划分自然日；切换时区可能改变日期归属，不修改记录原始发生时间。")
                 (if (seq cells)
                   (dom/div {:className "hc-diag-heatmap" :role "group" :aria-label "每日活动"}
                            (for [cell cells]
                              (dom/button {:key (:day cell)
                                           :type "button"
                                           :className "hc-diag-day"
                                           :aria-pressed (if (= selected (:day cell)) "true" "false")
                                           :aria-label (str (:day cell) "：用户发送 "
                                                            (:user-sends cell) "，模型请求 "
                                                            (:model-requests cell) "，工具调用 "
                                                            (:tool-calls cell))
                                           :onClick (fn [_]
                                                      (dispatch
                                                       {:type :diagnostics/select-day
                                                        :day (:day cell)}))}
                                          (dom/strong {} (:day cell))
                                          (dom/span {} (str (:user-sends cell) " 发送"))
                                          (dom/span {} (str (:model-requests cell) " 请求"))
                                          (dom/span {} (str (:tool-calls cell) " 工具")))))
                   (empty-panel "没有活跃日期" "当前范围没有发送、请求或工具调用记录。"))
                 (when selected
                   (let [cell (some #(when (= selected (:day %)) %) cells)]
                     (dom/div {:className "hc-diag-currency"}
                              (dom/strong {} (str selected " 明细"))
                              (fact-list
                               [["用户发送" (format-int (:user-sends cell))]
                                ["模型请求" (format-int (:model-requests cell))]
                                ["工具调用" (format-int (:tool-calls cell))]
                                ["涉及助手" (if (seq assistants)
                                              (str/join "、" assistants)
                                              "记录未提供助手标识")]])))))))

(defn- token-summary-rows
  [usage]
  (when usage
    [{:label "输入" :value (:input-tokens usage)
      :provenance (get-in usage [:provenance :input-tokens])}
     {:label "输出" :value (:output-tokens usage)
      :provenance (get-in usage [:provenance :output-tokens])}
     {:label "缓存命中" :value (:cache-read-tokens usage)
      :provenance (get-in usage [:provenance :cache-read-tokens])}
     {:label "缓存写入" :value (:cache-write-tokens usage)
      :provenance (get-in usage [:provenance :cache-write-tokens])}
     {:label "总 token" :value (:total-tokens usage)
      :provenance (:total-provenance usage)}]))

(defn- request-row
  [state request]
  (let [usage (:usage request)
        cost (:cost request)
        estimate (:estimate cost)
        billed (:billed cost)]
    (dom/div {:className "hc-diag-request" :key (:request-id request)}
             (dom/div {:className "hc-diag-actions"}
                      (dom/strong {} (or (:model request) "未知模型"))
                      (badge (str "用途：" (safe-label (:purpose request)))
                             :neutral)
                      (badge (str "结果：" (safe-label (:result request)))
                             (result-tone (:result request)))
                      (when (:retry? request)
                        (badge (str "重试 " (or (:retry-of request) "")) :warning))
                      (when (:recovery? request)
                        (badge (str "恢复 " (or (:recovery-of request) "")) :neutral)))
             (fact-list
              [["服务" (or (:service-name request) (:service-id request))]
               ["开始" (timestamp-label (:started-at request)
                                        (get-in state [:query :zone]))]
               ["结束" (timestamp-label (:finished-at request)
                                        (get-in state [:query :zone]))]
               ["耗时" (duration-label (:started-at request) (:finished-at request))]
               ["首字等待" (or (duration-label (:started-at request)
                                               (:first-token-at request))
                               "未记录")]
               ["模型生成时长" (if (number? (:generation-ms request))
                                 (str (:generation-ms request) " ms")
                                 "未记录")]
               ["估算" (if estimate
                         (str (format-estimate-amount (:amount estimate)
                                                      (:hide-amounts? state))
                              (when (and (not (:hide-amounts? state))
                                         (some? (:amount estimate))
                                         (:currency estimate))
                                (str " " (:currency estimate)))
                              "（" (safe-label (:status estimate)) "）")
                         "未估算")]
               ["估算来源" (or (:price-source estimate) "未提供")]
               ["服务实收" (str (format-actual-amount (:amount billed)
                                                       (:hide-amounts? state))
                                (when (and (not (:hide-amounts? state))
                                           (some? (:amount billed))
                                           (:currency billed))
                                  (str " " (:currency billed))))]
               ["实收来源" (or (:source billed) "未提供")]])
             (dom/div {:className "hc-diag-table-wrap"}
                      (dom/table {:className "hc-diag-table"}
                                 (dom/thead {}
                                            (dom/tr {} (dom/th {} "Token 项目")
                                                    (dom/th {} "数量")
                                                    (dom/th {} "来源")))
                                 (dom/tbody {}
                                            (for [row (token-summary-rows usage)]
                                              (dom/tr {:key (:label row)}
                                                      (dom/td {} (:label row))
                                                      (dom/td {} (format-int (:value row)))
                                                      (dom/td {} (model/provenance-label
                                                                  (:provenance row)))))))))))

(defn- service-usage-row
  [state usage]
  (let [cost (:cost usage)
        estimate (:estimate cost)
        billed (:billed cost)
        hide? (:hide-amounts? state)
        partial? (= :partial (:status estimate))]
    (dom/li {:key (or (:usage-id usage)
                      (str (:name usage) (:unit usage)))}
            (dom/div {}
                     (str (:name usage) " · "
                          (format-quantity (:quantity usage)) " "
                          (safe-label (:unit usage))
                          " · "
                          (model/provenance-label (:quantity-source usage))))
            (dom/div {:className "hc-diag-muted"}
                     (str "估算："
                          (format-estimate-amount (:amount estimate) hide?)
                          (when (and (not hide?)
                                     (some? (:amount estimate))
                                     (:currency estimate))
                            (str " " (:currency estimate)))
                          "；来源：" (or (:price-source estimate) "未提供")
                          (when partial? "（部分估算）")))
            (dom/div {:className "hc-diag-muted"}
                     (str "实收："
                          (format-actual-amount (:amount billed) hide?)
                          (when (and (not hide?)
                                     (some? (:amount billed))
                                     (:currency billed))
                            (str " " (:currency billed)))
                          "；来源：" (or (:source billed) "未提供"))))))

(defn- turn-panel
  [state stats]
  (let [turns (model/turns stats)]
    (dom/section {:className "hc-diag-panel" :aria-labelledby "hc-diag-turn-title"}
                 (dom/h2 {:id "hc-diag-turn-title"} "按轮次查看")
                 (dom/p {:className "hc-diag-muted"}
                        "一次用户发送不等于一次模型调用。这里分别显示用户发送、模型请求与工具调用；重试是新请求。")
                 (if (seq turns)
                   (for [turn turns]
                     (dom/details {:className "hc-diag-turn" :key (or (:turn-id turn) "unknown")}
                                  (dom/summary {}
                                               (dom/strong {} (str "轮次 " (or (:turn-id turn) "未知")))
                                               (dom/span {} (str "发送 " (format-int (get-in turn [:counts :user-sends]))
                                                                 " · 请求 " (format-int (get-in turn [:counts :model-requests]))
                                                                 " · 工具 " (format-int (get-in turn [:counts :tool-calls]))))
                                               (when (:at turn)
                                                 (dom/span {:className "hc-diag-muted"}
                                                           (timestamp-label (:at turn)
                                                                            (get-in state [:query :zone]))))
                                               (when (pos? (or (:retry-count turn) 0))
                                                 (badge (str "重试 " (:retry-count turn)) :warning))
                                               (when (pos? (or (:failed-count turn) 0))
                                                 (badge (str "失败 " (:failed-count turn)) :negative))
                                               (when (pos? (or (:cancelled-count turn) 0))
                                                 (badge (str "取消 " (:cancelled-count turn)) :warning)))
                                  (when-let [send (:user-send turn)]
                                    (fact-list
                                     [["用户发送" (str "助手 " (or (:assistant-id send) "未知")
                                                     " · 模型 " (or (:model send) "未知"))]
                                      ["发送时间" (timestamp-label (:at send)
                                                                   (get-in state [:query :zone]))]
                                      ["临时会话" (cond
                                                    (true? (:temporary? send)) "是"
                                                    (false? (:temporary? send)) "否"
                                                    :else "未提供")]]))
                                  (for [request (:requests turn)]
                                    (request-row state request))
                                  (when (seq (:tool-calls turn))
                                    (dom/div {}
                                             (dom/h4 {} "工具调用")
                                             (dom/ul {}
                                                    (for [tool (:tool-calls turn)]
                                                      (dom/li {:key (or (:tool-call-id tool) (str (:name tool) (:index tool)))}
                                                              (str (:name tool) " · "
                                                                   (safe-label (:result tool))
                                                                   " · " (timestamp-label (:at tool)
                                                                                           (get-in state [:query :zone]))))))))
                                  (when (seq (:service-usages turn))
                                    (dom/div {}
                                             (dom/h4 {} "服务用量")
                                             (dom/ul {}
                                                    (for [usage (:service-usages turn)]
                                                      (service-usage-row state usage)))))))
                   (empty-panel "没有轮次记录" "当前范围没有用户发送、模型请求或工具调用。")))))

(defn- unknown-time-panel
  [stats]
  (let [unknown (model/unknown-time stats)
        excluded (model/excluded-unknown-time stats)]
    (when (or (and unknown
                   (some #(and (number? %) (pos? %))
                         (vals (select-keys unknown
                                            [:user-sends :model-requests
                                             :tool-calls :service-usages]))))
              (and excluded
                   (some #(and (number? %) (pos? %))
                         (vals (select-keys excluded
                                            [:user-sends :model-requests
                                             :tool-calls :service-usages])))))
      (dom/section {:className "hc-diag-panel" :aria-labelledby "hc-diag-unknown-title"}
                   (dom/h2 {:id "hc-diag-unknown-title"} "时间未知的记录")
                   (dom/p {:className "hc-diag-muted"}
                          "没有可用时间的记录不会归到导入当天或“今天”。显式日期范围无法证明它们属于该范围，因此不计入范围总量；全部范围会在总量中包含它们。")
                   (when unknown
                     (fact-list
                      [["用户发送" (format-int (:user-sends unknown))]
                       ["模型请求" (format-int (:model-requests unknown))]
                       ["工具调用" (format-int (:tool-calls unknown))]
                       ["服务用量" (format-int (:service-usages unknown))]]))
                   (when excluded
                     (dom/p {:className "hc-diag-warning"}
                            (str "本范围排除了时间未知记录：发送 "
                                 (format-int (:user-sends excluded))
                                 "，请求 " (format-int (:model-requests excluded))
                                 "，工具 " (format-int (:tool-calls excluded))
                                 "，服务用量 " (format-int (:service-usages excluded)) "。")))))))

(defn- price-panel
  [state dispatch]
  (let [price (:price state)
        preview (model/price-preview price)
        services (:services (:filter-options state))
        models (:models (:filter-options state))
        service-models (filter #(= (:service-id price) (:service-id %)) models)
        options (if (seq service-models) service-models models)
        synced? (true? (:can-submit? state))]
    (dom/section {:className "hc-diag-panel" :aria-labelledby "hc-diag-price-title"}
                 (dom/h2 {:id "hc-diag-price-title"} "模型价格")
                 (dom/p {:className "hc-diag-muted"}
                        "单价按每 1,000,000 tokens 计价。请填写输入、输出及已支持的缓存价格；缺失项只估算可确定部分，缓存不会默认免费。")
                 (dom/div {:className "hc-diag-filters"}
                          (select-field "服务"
                                        (:service-id price)
                                        (into [{:value "" :label "选择服务"}]
                                              (map (fn [service]
                                                     {:value (:service-id service)
                                                      :label (str (or (:service-name service)
                                                                      (:service-id service)))}))
                                              services)
                                        (on-value dispatch
                                                  {:type :diagnostics/price-set
                                                   :field :service-id}))
                          (select-field "模型"
                                        (:model price)
                                        (into [{:value "" :label "选择模型"}]
                                              (map (fn [row]
                                                     {:value (:model row)
                                                      :label (str (:service-name row)
                                                                  " / " (:model row))}))
                                              options)
                                        (on-value dispatch
                                                  {:type :diagnostics/price-set
                                                   :field :model}))
                          (text-field "币种" (:currency price)
                                      (on-value dispatch
                                                {:type :diagnostics/price-set
                                                 :field :currency})
                                      {:placeholder "USD"})
                          (text-field "输入 / 百万 token" (:input price)
                                      (on-value dispatch
                                                {:type :diagnostics/price-set
                                                 :field :input})
                                      {:inputMode "decimal"})
                          (text-field "输出 / 百万 token" (:output price)
                                      (on-value dispatch
                                                {:type :diagnostics/price-set
                                                 :field :output})
                                      {:inputMode "decimal"})
                          (text-field "缓存命中 / 百万 token" (:cache-read price)
                                      (on-value dispatch
                                                {:type :diagnostics/price-set
                                                 :field :cache-read})
                                      {:inputMode "decimal"})
                          (text-field "缓存写入 / 百万 token" (:cache-write price)
                                      (on-value dispatch
                                                {:type :diagnostics/price-set
                                                 :field :cache-write})
                                      {:inputMode "decimal"})
                          (text-field "价格来源" (:source price)
                                      (on-value dispatch
                                                {:type :diagnostics/price-set
                                                 :field :source})
                                      {:placeholder "服务价格页或用户记录"})
                          (text-field "来源日期" (:source-date price)
                                      (on-value dispatch
                                                {:type :diagnostics/price-set
                                                 :field :source-date})
                                      {:type "date"}))
                 (dom/p {:className "hc-diag-muted"} (:label preview))
                 (dom/div {:className "hc-diag-actions"}
                          (button (if (= :saving (:status price))
                                    "正在保存…"
                                    "保存价格")
                                  {:type :diagnostics/save-price}
                                  dispatch
                                  {:disabled (or (not synced?)
                                                 (= :saving (:status price)))})
                          (when-not synced?
                            (dom/span {:className "hc-diag-warning"}
                                      "实时通道未就绪；价格不会发送。"))
                          (when (= :saved (:status price))
                            (dom/span {:className "hc-diag-positive"} "价格已保存。"))
                          (when-let [error (:error price)]
                            (dom/span {:className "hc-diag-negative"}
                                      (or (:message error) "价格未保存。")))))))

;; ---------------------------------------------------------------------------
;; Statistics tab

(defn- statistics-summary
  [state stats]
  (dom/div {:className "hc-diag-summary"}
           (dom/span {} (str "范围：" (model/stats-range-label stats)))
           (dom/span {} (str "筛选：" (model/stats-filter-label stats)))
           (dom/span {} (str "时区：" (or (:timezone stats) "设备时区")))
           (dom/span {} (str "日期口径："
                             (case (model/keyword-value (:zone-mode stats))
                               :record "记录发生时区"
                               :query "查询时区"
                               "未提供")))
           (when (:cached? stats)
             (dom/span {:className "hc-diag-warning"}
                       (str "已缓存统计；最后更新："
                            (or (timestamp-label (:last-updated stats)
                                                 (:timezone stats))
                                "未提供"))))
           (when (:last-updated stats)
             (dom/span {} (str "最后更新："
                               (or (timestamp-label
                                    (:last-updated stats)
                                    (:timezone stats))
                                   "未提供"))))
           (when (:stats-stale? state)
             (dom/span {:className "hc-diag-warning"}
                       "筛选已更改，请刷新统计。"))))

(defn- statistics-data
  [state stats dispatch]
  (dom/div {}
           (statistics-summary state stats)
           (count-panel stats)
           (token-panel stats)
           (cost-panel state stats dispatch)
           (model-panel state stats)
           (heatmap-panel state stats dispatch)
           (turn-panel state stats)
           (unknown-time-panel stats)
           (price-panel state dispatch)))

(defn- statistics-failure
  [state]
  (let [stats (:stats state)
        status (model/keyword-value (:status state))]
    (or (:stats-error state)
        (when (or (= :error status)
                  (= :read-failed status)
                  (= :invalid-query status)
                  (model/stats-failed? stats)
                  (model/stats-invalid? stats))
          (or (:error state)
              (when (model/stats-failed? stats)
                {:code :read-failed
                 :message (or (:message stats)
                              "统计读取失败；可以重试。")
                 :retry? true})
              (when (model/stats-invalid? stats)
                {:code :invalid-query
                 :message (or (:message stats)
                              "统计条件无效。")
                 :retry? false})
              {:code :read-failed
               :message "统计读取失败；可以重试。"
               :retry? true})))))

(defn- statistics-failure-panel
  [state error old-data? dispatch]
  (let [invalid? (= :invalid-query (:code error))
        stats (:stats state)]
    (dom/div {:className "hc-diag-panel" :role "alert"}
             (dom/h2 {} (if invalid? "统计条件无效" "统计读取失败"))
             (dom/p {} (or (:message error)
                           (if invalid?
                             "检查日期范围、时区与筛选条件。"
                             "统计读取失败；可以重试。")))
             (when old-data?
               (dom/p {:className "hc-diag-muted"}
                      (str "以下为上次成功读取的统计；最后更新："
                           (or (timestamp-label (:last-updated stats)
                                                (:timezone stats))
                               "未提供")
                           "。")))
             (dom/div {:className "hc-diag-actions"}
                      (if invalid?
                        (button "返回默认范围"
                                {:type :diagnostics/select-range
                                 :preset :last-30-days}
                                dispatch)
                        (button "重试"
                                {:type :diagnostics/retry}
                                dispatch
                                {:disabled (not (:can-submit? state))}))))))

(defn- statistics-tab
  [state dispatch]
  (let [stats (:stats state)
        status (:status state)
        failure (statistics-failure state)
        old-data? (and stats
                       (not (model/stats-failed? stats))
                       (not (model/stats-invalid? stats)))]
    (dom/div {}
             (filter-bar state dispatch)
             (cond
               (= :loading status)
               (dom/div {:className "hc-diag-empty" :role "status" :aria-live "polite"}
                        "正在读取统计；没有伪造的零值曲线。")

               (and failure old-data?)
               (dom/div {}
                        (statistics-failure-panel state failure true dispatch)
                        (statistics-data state stats dispatch))

               failure
               (statistics-failure-panel state failure false dispatch)

               (and stats (model/stats-empty? stats))
               (dom/div {}
                        (empty-panel "当前范围没有可计入的统计记录"
                                     "没有可计入范围的发送、请求或工具调用。这里不画看似正常的零值曲线；时间未知的记录在下方单列。")
                        (when-let [cards (model/count-cards stats)]
                          (dom/div {:className "hc-diag-cards"}
                                   (for [card cards]
                                     (dom/div {:className "hc-diag-card" :key (name (:id card))}
                                              (dom/div {:className "hc-diag-card__label"} (:label card))
                                              (dom/div {:className "hc-diag-card__value"} "0")
                                              (dom/div {:className "hc-diag-card__definition"}
                                                       (:definition card))))))
                        (unknown-time-panel stats))

               (nil? stats)
               (empty-panel "尚未读取统计"
                            "连接服务器后点击刷新；页面不会预先画零值。")

               :else
               (statistics-data state stats dispatch)))))

;; ---------------------------------------------------------------------------
;; Logs tab

(defn- logs-tab
  [state dispatch]
  (let [log (:log state)
        days (get state :log-days 1)
        synced? (true? (:can-submit? state))]
    (dom/div {}
             (dom/section {:className "hc-diag-panel" :aria-labelledby "hc-diag-log-title"}
                          (dom/h2 {:id "hc-diag-log-title"} "详细请求日志")
                          (dom/p {:className "hc-diag-muted"}
                                 "详细日志默认关闭。开启默认只记录下一次请求；定时模式最多保留 7 天。临时会话不会被记录。清空日志只删除详细条目，不删除聊天、助手或用量汇总。")
                          (when-not synced?
                            (dom/p {:className "hc-diag-warning"}
                                   "实时通道未就绪；日志策略不会发送。"))
                          (if log
                            (fact-list
                             [["状态" (cond
                                        (:paused? log) "已暂停"
                                        (:capturing? log) "正在记录"
                                        (:enabled? log) "已开启，等待请求"
                                        :else "已关闭")]
                              ["模式" (case (:mode log)
                                        :next-request "仅下一次请求"
                                        :timed "定时"
                                        "未设置")]
                              ["保留天数" (format-int (:retention-days log))]
                              ["条目数" (format-int (:entry-count log))]
                              ["最早条目" (or (timestamp-label (:oldest log) nil) "—")]
                              ["最新条目" (or (timestamp-label (:newest log) nil) "—")]
                              ["到期时间" (or (timestamp-label (:expires-at log) nil) "—")]
                              ["最近清空" (or (timestamp-label (:last-cleared-at log) nil) "—")]])
                            (dom/p {:className "hc-diag-warning"}
                                   "服务器尚未提供详细日志状态。"))
                          (dom/div {:className "hc-diag-actions"}
                                   (button "开启下一次请求"
                                           {:type :diagnostics/log-enable
                                            :mode :next-request}
                                           dispatch
                                           {:disabled (not synced?)})
                                   (select-field "定时天数"
                                                 days
                                                 (map (fn [day]
                                                        {:value day
                                                         :label (str day " 天")})
                                                      (range 1 8))
                                                 (on-value dispatch
                                                           {:type :diagnostics/log-set-days}))
                                   (button "开启定时记录"
                                           {:type :diagnostics/log-enable
                                            :mode :timed
                                            :days (long days)}
                                           dispatch
                                           {:disabled (not synced?)})
                                   (button "暂停"
                                           {:type :diagnostics/log-pause}
                                           dispatch
                                           {:disabled (not synced?)})
                                   (button "恢复"
                                           {:type :diagnostics/log-resume}
                                           dispatch
                                           {:disabled (not synced?)})
                                   (button "关闭"
                                           {:type :diagnostics/log-disable}
                                           dispatch
                                           {:disabled (not synced?)}))
                          (dom/div {:className "hc-diag-actions"}
                                   (if (:clear-log-confirm? state)
                                     (dom/div {}
                                              (dom/span {:className "hc-diag-warning"}
                                                        "确认清空详细日志？聊天、助手与用量汇总不变。")
                                              (button "确认清空"
                                                      {:type :diagnostics/log-clear}
                                                      dispatch
                                                      {:disabled (not synced?)})
                                              (button "取消"
                                                      {:type :diagnostics/log-clear-cancel}
                                                      dispatch))
                                     (button "清空详细日志"
                                             {:type :diagnostics/log-clear-request}
                                             dispatch
                                             {:disabled (not synced?)}))))
             (dom/section {:className "hc-diag-panel"}
                          (dom/h2 {} "请求详情口径")
                          (dom/p {:className "hc-diag-muted"}
                                 "本页只管理记录策略；详细条目不会在这里直接展开。需要查看条目时，在“诊断包”中显式包含日志材料并审阅其脱敏预览。已存储条目在进入预览前会遮蔽密钥、登录令牌、认证头和密码；密钥、登录令牌、认证头和密码始终遮蔽；内容未被记录时不会根据当前设置重建一份冒充当时请求的材料。")))))

;; ---------------------------------------------------------------------------
;; Bundle tab

(def error-category-options
  [{:value :unclassified :label "无法确定具体原因"}
   {:value :no-model :label "尚未选模型"}
   {:value :connection-disabled :label "供应商未启用"}
   {:value :model-removed :label "模型被移除"}
   {:value :invalid-credentials :label "凭据无效"}
   {:value :authorization-expired :label "账号授权过期"}
   {:value :quota-exhausted :label "额度不足"}
   {:value :rate-limited :label "服务限流"}
   {:value :network-unreachable :label "网络不可达"}
   {:value :response-timeout :label "响应迟迟未到"}
   {:value :input-too-large :label "输入超出容量"}
   {:value :attachment-unsupported :label "附件类型不支持"}
   {:value :partial-materials :label "部分资料不可读"}
   {:value :local-storage-full :label "本地空间不足"}
   {:value :local-data-unreadable :label "本地资料不可读"}
   {:value :external-file-moved :label "外部文件已移动"}
   {:value :permission-revoked :label "权限被撤销"}
   {:value :tool-failed :label "工具执行失败"}
   {:value :receipt-lost :label "结果回执丢失"}
   {:value :schedule-missed :label "任务错过时间"}
   {:value :backup-password-invalid :label "备份密码不正确"}
   {:value :import-format-unsupported :label "导入格式不支持"}
   {:value :restore-conflict :label "恢复发生冲突"}
   {:value :format-too-new :label "版本过新"}
   {:value :export-cancelled :label "导出被取消"}])

(defn- bundle-request-form
  [state dispatch]
  (let [request (get-in state [:bundle :request])
        synced? (true? (:can-submit? state))]
    (dom/section {:className "hc-diag-panel" :aria-labelledby "hc-diag-bundle-title"}
                 (dom/h2 {:id "hc-diag-bundle-title"} "准备诊断资料")
                 (dom/p {:className "hc-diag-muted"}
                        "默认包含应用版本、平台与系统版本、时间范围、错误类别；若已知服务，则包含脱敏服务信息。不包含对话正文、附件、密钥、私有路径或完整请求。生成预览由你点击发起；本页不会自动上传，实际传输状态以回执为准。")
                 (dom/div {:className "hc-diag-filters"}
                          (select-field "错误类别"
                                        (:error-category request)
                                        error-category-options
                                        (on-value dispatch
                                                  {:type :diagnostics/bundle-set
                                                   :key :error-category}))
                          (text-field "开始日期" (:from request)
                                      (on-value dispatch
                                                {:type :diagnostics/bundle-set
                                                 :key :from})
                                      {:type "date"})
                          (text-field "结束日期" (:to request)
                                      (on-value dispatch
                                                {:type :diagnostics/bundle-set
                                                 :key :to})
                                      {:type "date"})
                          (text-field "时区" (:zone request)
                                      (on-value dispatch
                                                {:type :diagnostics/bundle-set
                                                 :key :zone})
                                      {:placeholder "设备时区"})
                          (checkbox-field "包含错误详情"
                                          (:include-error-detail? request)
                                          (on-checked dispatch
                                                      {:type :diagnostics/bundle-set
                                                       :key :include-error-detail?}))
                          (checkbox-field "包含原始服务错误"
                                          (:include-raw-error? request)
                                          (on-checked dispatch
                                                      {:type :diagnostics/bundle-set
                                                       :key :include-raw-error?})))
                 (dom/div {:className "hc-diag-actions"}
                          (button "生成脱敏预览"
                                  {:type :diagnostics/bundle-preview}
                                  dispatch
                                  {:disabled (not synced?)})
                          (when-not synced?
                            (dom/span {:className "hc-diag-warning"}
                                      "实时通道未就绪；预览不会生成。"))
                          (dom/span {:className "hc-diag-muted"}
                                    "本页不会自动上传，也不会自动提交公开 issue。")))))

(defn- preview-line
  [state dispatch line]
  (dom/div {:className "hc-diag-preview-line" :key (:id line)}
           (dom/span {} (:label line))
           (dom/div {:className "hc-diag-preview-value"}
                    (if (map? (:value line))
                      (pr-str (:value line))
                      (value-or (:value line) "—")))
           (button "删去"
                   {:type :diagnostics/bundle-remove-line
                    :line-id (:id line)}
                   dispatch)))

(defn- bundle-preview
  [state dispatch]
  (let [bundle (model/bundle-value state)
        sections (model/bundle-sections state)
        redaction (:redaction bundle)
        transfer (:transfer bundle)
        export (:export (:bundle state))
        exporting? (= :exporting (get-in state [:bundle :status]))
        request (get-in state [:bundle :request])]
    (when bundle
      (dom/div {}
               (dom/section {:className "hc-diag-panel" :aria-labelledby "hc-diag-preview-title"}
                            (dom/h2 {:id "hc-diag-preview-title"} "脱敏预览")
                            (let [local-only? (model/transfer-local-only? bundle)]
                              (dom/div {:className "hc-diag-transfer"}
                                       (dom/strong {}
                                                    (if local-only?
                                                      "仅本地：没有上传"
                                                      (str "传输状态："
                                                           (name (or (:mode transfer)
                                                                     :unknown)))))
                                       (dom/div {}
                                                (str "传输方式：" (safe-label (:mode transfer))
                                                     "；已上传：" (yes-no-unknown (:uploaded? transfer))
                                                     "；自动上传：" (yes-no-unknown (:automatic-upload? transfer))
                                                     "；网络：" (yes-no-unknown (:network? transfer))
                                                     "；需要你选择位置：" (yes-no-unknown (:requires-user-action? transfer))))
                                       (dom/div {:className "hc-diag-muted"}
                                                "导出前请确认预览中没有你不想分享的内容。")))
                            (when (:warning bundle)
                              (dom/p {:className "hc-diag-warning"} (:warning bundle)))
                            (fact-list
                             [["诊断包 ID" (:bundle-id bundle)]
                              ["生成时间" (timestamp-label (:generated-at bundle) nil)]
                              ["脱敏字段" (format-int (:finding-count redaction))]
                              ["占位符" (format-int (:placeholder-count redaction))]
                              ["已忽略字段" (str/join "、" (:ignored-fields bundle))]
                              ["默认排除" (str/join "、" (:excluded bundle))]])
                            (when (seq (:findings redaction))
                              (dom/details {:className "hc-diag-findings"}
                                           (dom/summary {} "查看脱敏字段与占位符")
                                           (dom/ul {}
                                                  (for [finding (:findings redaction)]
                                                    (dom/li {:key (pr-str [(:path finding)
                                                                           (:kind finding)
                                                                           (:placeholder finding)])}
                                                            (str (pr-str (:path finding)) " · "
                                                                 (safe-label (:kind finding)) " · "
                                                                 (:placeholder finding)))))))
                            (when (or (nil? redaction)
                                      (not (:redacted? redaction)))
                              (dom/p {:className "hc-diag-warning"}
                                     "未发现已知凭据模式；这不等于正文没有个人信息。请逐行检查预览，必要时删去或隐藏后导出。"))
                            (dom/div {:className "hc-diag-actions"}
                                     (button "恢复所有删去行"
                                             {:type :diagnostics/bundle-restore-lines}
                                             dispatch)
                                     (button "重新生成预览"
                                             {:type :diagnostics/bundle-preview}
                                             dispatch))
                            (for [section sections]
                              (dom/div {:key (name (:id section))}
                                       (dom/h3 {} (:title section))
                                       (for [line (:lines section)]
                                         (preview-line state dispatch line)))))
               (dom/section {:className "hc-diag-panel"}
                            (dom/h2 {} "替换昵称与隐藏位置")
                            (dom/div {:className "hc-diag-filters"}
                                     (text-field "原始昵称" (:rename-original request)
                                                 (on-value dispatch
                                                           {:type :diagnostics/bundle-rename-input
                                                            :field :rename-original}))
                                     (text-field "替换为" (:rename-replacement request)
                                                 (on-value dispatch
                                                           {:type :diagnostics/bundle-rename-input
                                                            :field :rename-replacement})))
                            (dom/div {:className "hc-diag-actions"}
                                     (button "应用昵称替换"
                                             {:type :diagnostics/bundle-rename}
                                             dispatch)
                                     (button (if (:mask-hosts? request)
                                               "已隐藏域名"
                                               "隐藏域名")
                                             {:type :diagnostics/bundle-hide-hosts}
                                             dispatch
                                             {:aria-pressed (if (:mask-hosts? request) "true" "false")})
                                     (button (if (:mask-paths? request)
                                               "已隐藏路径"
                                               "隐藏路径")
                                             {:type :diagnostics/bundle-hide-paths}
                                             dispatch
                                             {:aria-pressed (if (:mask-paths? request) "true" "false")})))
               (when (seq (:extra-materials bundle))
                 (dom/section {:className "hc-diag-panel"}
                              (dom/h2 {} "额外材料")
                              (dom/p {:className "hc-diag-muted"}
                                     "每项默认不包含；勾选时请填写用途。禁止聊天正文、附件、密钥、私有路径和完整请求。")
                              (for [material (:extra-materials bundle)]
                                (let [shown (model/material-entry state
                                                                  (:id material))]
                                  (dom/div {:className "hc-diag-currency"
                                            :key (name (or (:id material) :material))}
                                           (checkbox-field (str (:title shown)
                                                                (when (:rejected? shown)
                                                                  (str "（已拒绝：" (safe-label (:rejected-reason shown)) "）")))
                                                           (:include? shown)
                                                           (on-checked dispatch
                                                                       {:type :diagnostics/bundle-material-toggle
                                                                        :material-id (:id shown)}))
                                           (text-field "用途"
                                                       (:purpose shown)
                                                       (on-value dispatch
                                                                 {:type :diagnostics/bundle-material-purpose
                                                                  :material-id (:id shown)})))))
                              (dom/div {:className "hc-diag-actions"}
                                       (button "应用材料选择并重新预览"
                                               {:type :diagnostics/bundle-material-apply}
                                               dispatch))))
               (when (seq (:rejected-materials bundle))
                 (dom/section {:className "hc-diag-panel"}
                              (dom/h2 {} "未包含的材料")
                              (dom/p {:className "hc-diag-warning"}
                                     "以下材料没有进入预览。聊天正文、附件、密钥、私有路径和完整请求会被拒绝；缺少用途的额外材料也不会包含。")
                              (dom/ul {}
                                      (for [material (:rejected-materials bundle)]
                                        (dom/li {:key (name (or (:id material) :material))}
                                                (str (or (:title material)
                                                         (name (or (:id material) :material)))
                                                     " · "
                                                     (safe-label (:kind material))
                                                     (when (:rejected-reason material)
                                                       (str " · " (safe-label (:rejected-reason material))))))))))
               (dom/section {:className "hc-diag-panel"}
                            (dom/h2 {} "导出")
                            (dom/p {:className "hc-diag-muted"}
                                   "导出由你点击发起；文件写入位置与是否上传以服务器回执为准；本页不会替你提交公开 issue。")
                            (dom/div {:className "hc-diag-actions"}
                                     (button (if exporting?
                                               "正在导出…"
                                               "导出诊断文件")
                                             {:type :diagnostics/bundle-export}
                                             dispatch
                                             {:disabled (or (not (:can-submit? state))
                                                            exporting?)})
                                     (when-not (:can-submit? state)
                                       (dom/span {:className "hc-diag-warning"}
                                                 "实时通道未就绪；导出命令不会发送。"))
                                     (when export
                                       (dom/span {:className (if (= :exported (model/keyword-value (:status export)))
                                                               "hc-diag-positive"
                                                               "hc-diag-muted")}
                                                 (str "导出状态：" (safe-label (:status export))
                                                      "；已上传：" (yes-no-unknown (:uploaded? export))
                                                      "；自动上传：" (yes-no-unknown (:automatic-upload? export)))))))))))

(defn- bundle-tab
  [state dispatch]
  (dom/div {}
           (bundle-request-form state dispatch)
           (cond
             (= :loading (get-in state [:bundle :status]))
             (dom/div {:className "hc-diag-empty" :role "status" :aria-live "polite"}
                      "正在生成脱敏预览；上传状态以回执为准。")

             (get-in state [:bundle :error])
             (dom/div {}
                      (dom/div {:className "hc-diag-panel" :role "alert"}
                               (dom/h2 {} "诊断操作未完成")
                               (dom/p {} (or (get-in state [:bundle :error :message])
                                             "预览或导出没有完成。"))
                               (button "重新生成预览"
                                       {:type :diagnostics/bundle-preview}
                                       dispatch))
                      (when (model/bundle-value state)
                        (bundle-preview state dispatch)))

             :else
             (bundle-preview state dispatch))))

;; ---------------------------------------------------------------------------
;; Feedback tab

(defn- feedback-tab
  [state dispatch]
  (let [feedback (:feedback state)
        url (model/mailto-url feedback)]
    (dom/div {}
             (dom/section {:className "hc-diag-panel" :aria-labelledby "hc-diag-feedback-title"}
                          (dom/h2 {:id "hc-diag-feedback-title"} "反馈草稿")
                          (dom/p {:className "hc-diag-muted"}
                                 "反馈由你选择邮件客户端或浏览器提交；产品不自动代发公开 issue，也不把完整诊断附件上传第三方。离开本页后草稿保留在当前浏览器会话中；刷新或关闭应用会丢失，请先复制或保存。")
                          (dom/div {:className "hc-diag-filters"}
                                   (text-field "标题" (:title feedback)
                                               (on-value dispatch
                                                         {:type :diagnostics/feedback-set
                                                          :field :title}))
                                   (textarea-field "复现步骤" (:steps feedback)
                                                   (on-value dispatch
                                                             {:type :diagnostics/feedback-set
                                                              :field :steps}))
                                   (textarea-field "预期结果" (:expected feedback)
                                                   (on-value dispatch
                                                             {:type :diagnostics/feedback-set
                                                              :field :expected}))
                                   (textarea-field "实际结果" (:actual feedback)
                                                   (on-value dispatch
                                                             {:type :diagnostics/feedback-set
                                                              :field :actual})))
                          (dom/div {:className "hc-diag-actions"}
                                   (button "复制草稿"
                                           {:type :diagnostics/feedback-copy}
                                           dispatch)
                                   (button "保存为本地文本"
                                           {:type :diagnostics/feedback-save}
                                           dispatch)
                                   (dom/a {:className "hc-diag-button"
                                           :href url
                                           :onClick (fn [event]
                                                      (.preventDefault event)
                                                      (dispatch {:type :diagnostics/feedback-open}))}
                                          "打开邮件草稿"))
                          (dom/p {:className "hc-diag-warning"}
                                 "发送前请确认草稿不含你不想分享的内容。诊断附件需要你单独选择并导出。")))))

;; ---------------------------------------------------------------------------
;; FAQ tab

(def faq-entries
  [{:title "消息无法发送"
    :keywords ["消息" "发送" "失败" "发不出" "回复" "模型" "额度"]
    :steps ["确认已经选中模型：在发送区或会话设置里查看当前模型，未选模型时发送会被拒绝。"
            "检查模型连接状态：服务未启用、凭据无效或额度不足都会让发送失败；去设置页检查该服务的状态。"
            "查看诊断页“用量与费用”或“详细日志”：最近一次失败请求会带结果与原因分类。"
            "额度不足与授权过期不同：额度问题去补充额度或更换模型；授权问题重新登录或刷新令牌。"]}
   {:title "图片无法发送或不支持"
    :keywords ["图片" "附件" "照片" "不支持" "上传" "发送失败"]
    :steps ["确认当前模型与服务支持图片输入：部分模型或手动连接没有声明图片能力，服务端会返回“附件类型不支持”。"
            "检查附件格式与大小：服务限制单条消息的附件类型与大小；超出限制时先压缩或换一种格式再发送。"
            "查看失败原因分类“附件类型不支持”或“输入超出容量”：前者换模型或去掉附件，后者缩短文本或减少附件。"
            "临时会话不会记录详细日志；需要排查时先在正常会话复现，再到诊断页开启详细日志。"]}
   {:title "账户授权过期"
    :keywords ["账户" "授权" "过期" "登录" "令牌" "token" "重新登录" "凭据"]
    :steps ["到设置页检查该服务的连接状态：授权过期会显示为凭据或授权类失败，而不是额度问题。"
            "重新登录或刷新该服务的授权令牌：多数服务重新授权后即可恢复发送。"
            "若是 API 密钥而非 OAuth 授权，检查密钥是否被轮换或撤销，并在服务商控制台更新密钥。"
            "额度不足与授权过期不同：授权过期先解决登录，额度不足去补充额度，两者不要混为一谈。"]}
   {:title "后台任务被结束"
    :keywords ["后台" "任务" "被结束" "终止" "停止" "定时" "计划" "错过时间"]
    :steps ["查看失败原因分类：应用关闭、系统休眠或计划错过时间都可能让后台任务被结束。"
            "检查任务计划与“错过时间”策略：错过的定时任务默认不会自动补跑，需要手动重新触发。"
            "确认应用在任务应运行的时间段内保持运行：桌面端被完全退出或系统休眠时，任务无法执行。"
            "任务被结束不影响已保存的用量与消息；重新安排任务或手动重发即可恢复。"]}
   {:title "导入失败"
    :keywords ["导入" "备份" "恢复" "文件" "失败" "格式" "迁移"]
    :steps ["确认导入文件来自本应用的导出：诊断包、聊天备份或配置文件都有各自的格式，不能混用。"
            "检查文件版本：版本过新的文件不能强行导入，需要先升级应用再导入；旧版本文件按提示升级或选择兼容模式。"
            "查看失败原因分类：“导入格式不支持”“恢复发生冲突”“备份密码不正确”“版本过新”分别对应不同处理。"
            "备份需要密码时确认密码正确；恢复冲突时先备份当前数据，再选择合并或覆盖策略。"]}])

(defn- faq-query-matches?
  [query entry]
  (let [needle (str/lower-case (str/trim (str query)))
        haystack (str/lower-case
                  (str/join " "
                            (concat [(:title entry)]
                                    (:keywords entry)
                                    (:steps entry))))]
    (or (str/blank? needle)
        (str/includes? haystack needle))))

(defn- faq-tab
  [state dispatch]
  (let [query (:faq-query state)
        entries (filterv #(faq-query-matches? query %) faq-entries)]
    (dom/div {}
             (dom/section {:className "hc-diag-panel" :aria-labelledby "hc-diag-faq-title"}
                          (dom/h2 {:id "hc-diag-faq-title"} "常见问题")
                          (dom/p {:className "hc-diag-muted"}
                                 "五类常见问题的自助排查；搜索会按标题、关键词与排查步骤过滤。")
                          (text-field "搜索常见问题" query
                                      (on-value dispatch
                                                {:type :diagnostics/set-faq-query})
                                      {:placeholder "例如：图片、授权、导入"})
                          (if (seq entries)
                            (for [{:keys [title keywords steps]} entries]
                              (dom/details {:className "hc-diag-turn" :key title}
                                           (dom/summary {}
                                                        (dom/strong {} title)
                                                        (dom/span {:className "hc-diag-muted"}
                                                                  (str "关键词："
                                                                       (str/join "、" keywords))))
                                           (dom/ol {}
                                                 (for [step steps]
                                                   (dom/li {:key step} step)))))
                            (empty-panel "没有匹配的问题"
                                         "换个关键词试试，或清空搜索框查看全部常见问题。"))))))

;; ---------------------------------------------------------------------------
;; Root

(def tabs
  [{:id :statistics :label "用量与费用"}
   {:id :logs :label "详细日志"}
   {:id :bundle :label "诊断包"}
   {:id :feedback :label "反馈草稿"}
   {:id :faq :label "常见问题"}])

(defn- header-description
  [state]
  (let [bundle (model/bundle-value state)
        transfer (when bundle (model/field bundle :transfer))
        export (get-in state [:bundle :export])
        mode (model/keyword-value (model/field transfer :mode))
        uploaded? (or (true? (model/field export :uploaded?))
                      (model/field transfer :uploaded?))
        automatic? (or (true? (model/field export :automatic-upload?))
                       (model/field transfer :automatic-upload?))]
    (cond
      (and bundle
           (not (true? (model/field export :uploaded?)))
           (model/transfer-local-only? bundle))
      "用量与费用按服务返回、估算与未知分开显示；诊断包只在本机生成与导出。"

      (true? uploaded?)
      "用量与费用按服务返回、估算与未知分开显示；回执显示诊断包已上传。"

      (some? transfer)
      (str "用量与费用按服务返回、估算与未知分开显示；诊断包传输方式："
           (name (or mode :unknown))
           "；自动上传：" (yes-no-unknown automatic?)
           "；是否上传以回执为准。")

      :else
      "用量与费用按服务返回、估算与未知分开显示；诊断包传输状态以回执为准。")))

(defn- diagnostics-tree
  [state dispatch]
  (let [state (or state (model/initial-state))
        tab (or (:tab state) :statistics)]
    (dom/section {:className "hc-diagnostics" :aria-label "统计与诊断"}
                 (dom/style #js {:key "hc-diagnostics-style"} diagnostics-css)
                 (dom/header {:className "hc-diagnostics__header"}
                             (dom/h1 {} "统计与诊断")
                             (dom/p {} (header-description state)))
                 (dom/nav {:className "hc-diagnostics__tabs" :role "tablist" :aria-label "诊断分区"}
                          (for [{:keys [id label]} tabs]
                            (dom/button {:key (name id)
                                         :type "button"
                                         :role "tab"
                                         :className "hc-diagnostics__tab"
                                         :aria-selected (if (= id tab) "true" "false")
                                         :onClick (fn [_]
                                                    (dispatch {:type :diagnostics/select-tab
                                                               :tab id}))}
                                        label)))
                 (when (:message state)
                   (dom/div {:className "hc-diag-message" :role "status"}
                            (dom/span {} (:message state))
                            (button "关闭"
                                    {:type :diagnostics/dismiss-message}
                                    dispatch
                                    {:className "hc-diag-button"})))
                 (case tab
                   :statistics (statistics-tab state dispatch)
                   :logs (logs-tab state dispatch)
                   :bundle (bundle-tab state dispatch)
                   :feedback (feedback-tab state dispatch)
                   :faq (faq-tab state dispatch)
                   (statistics-tab state dispatch)))))

(defn DiagnosticsView
  "React function component wrapper.  Hooks must run inside a component, not
  while a caller constructs an element tree."
  [props]
  (let [state (or (.-state props) (model/initial-state))
        dispatch (or (.-dispatch props) (fn [_] nil))]
    (react/useEffect
     (fn []
       (when (and (:can-submit? state)
                  (= :idle (:status state)))
         (dispatch {:type :diagnostics/refresh}))
       js/undefined)
     #js [(:can-submit? state) (:status state)])
    (diagnostics-tree state dispatch)))

(defn diagnostics-element
  "Render the diagnostics workspace from STATE.  DISPATCH receives actions."
  [state dispatch]
  (react/createElement DiagnosticsView
                        #js {:state state
                             :dispatch dispatch}))
