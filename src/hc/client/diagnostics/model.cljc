(ns hc.client.diagnostics.model
  "Pure state and projections for the statistics and diagnostics workspace.

  The server owns usage records, prices, detailed-log policy, and the local
  diagnostic bundle.  This namespace only keeps the browser's display state
  and turns command receipts into that state.  It never invents a zero for an
  unknown quantity, never adds different currencies, and never calls a bundle
  preview an upload.

  A statistic is only displayed after the server returns it.  A failed read
  keeps `:status :read-failed` and an explicit retry; it does not become an
  empty curve."
  (:require [clojure.string :as str])
  #?(:clj (:import [java.net URLEncoder]
                   [java.time Instant LocalDate ZoneId]
                   [java.time.format DateTimeParseException])))

;; ---------------------------------------------------------------------------
;; Field access

(defn field
  "Read a field by keyword or its string form.  Transit may deliver either."
  [m k]
  (when (map? m)
    (if (contains? m k)
      (get m k)
      (get m (if (keyword? k) (subs (str k) 1) (keyword k))))))

(defn- non-blank
  [value]
  (and (string? value) (not (str/blank? value))))

(defn keyword-value
  [value]
  (cond
    (keyword? value) value
    (string? value) (keyword value)
    :else value))

(defn- number-value
  [value]
  (cond
    (number? value) value
    (and (string? value) (not (str/blank? value)))
    (try
      #?(:clj (Double/parseDouble value)
         :cljs (let [number (js/parseFloat value)]
                 (when-not (js/isNaN number) number)))
      (catch #?(:clj Exception :cljs :default) _ nil))
    :else nil))

(defn- integer-value
  [value]
  (let [number (number-value value)]
    (when (and number (not (neg? number)))
      (long number))))

(defn tagged-rep
  "Return the representation of a transit tagged value, or nil.

  The server rounds money as a tagged literal such as `#f 0.003275`.  The
  browser transport decodes that into a `TaggedValue` object whose `tag` and
  `rep` fields are the only useful display data.  A plain map with `:tag` and
  `:rep` is accepted too so tests can exercise the same path without a
  transit reader."
  [value]
  (cond
    (and (map? value) (contains? value :tag) (contains? value :rep))
    (:rep value)

    (and (map? value) (contains? value "tag") (contains? value "rep"))
    (get value "rep")

    :else
    #?(:cljs
       (when (and (some? value)
                  (not (number? value))
                  (not (string? value))
                  (some? (.-tag value))
                  (some? (.-rep value)))
         (.-rep value))
       :clj
       (try
         (when (some? (.tag value))
           (.rep value))
         (catch Throwable _ nil)))))

(defn unwrap-amount
  "Return a tagged money value's representation; leave plain values alone."
  [value]
  (let [rep (tagged-rep value)]
    (if (some? rep) rep value)))

(defn amount-number
  "Parse an amount to a number, accepting tagged and string representations."
  [value]
  (number-value (unwrap-amount value)))

(defn format-amount-number
  "Format a money number without scientific notation.

  The server keeps six decimal places.  Trailing zeros are trimmed so an
  exact zero is `0`, a half cent is `0.005`, and the E2E21 estimate stays
  `0.003275`."
  [value]
  (cond
    (integer? value) (str value)
    (number? value)
    #?(:clj (str value)
       :cljs (let [text (.toFixed value 6)]
               (-> text
                   (str/replace #"0+$" "")
                   (str/replace #"\.$" ""))))
    :else (str value)))

;; ---------------------------------------------------------------------------
;; Dates and timezones

(def range-presets
  [{:id :today :label "今天"}
   {:id :last-7-days :label "最近 7 天"}
   {:id :last-30-days :label "最近 30 天"}
   {:id :all :label "全部"}
   {:id :custom :label "自定义"}])

(defn- zone-id
  [zone]
  #?(:clj (try
            (if (non-blank zone)
              (ZoneId/of zone)
              (ZoneId/systemDefault))
            (catch Exception _ nil))
     :cljs nil))

(defn today-in-zone
  "Return today's local date in ZONE as `YYYY-MM-DD`.

  In the browser this uses `Intl.DateTimeFormat` so the date matches the zone
  the server will use for buckets.  Without a usable zone it falls back to the
  device date."
  [zone]
  #?(:clj (str (LocalDate/now (or (zone-id zone) (ZoneId/systemDefault))))
     :cljs
     (try
       (let [options #js {:year "numeric" :month "2-digit" :day "2-digit"}
             options (if (non-blank zone)
                       (do (aset options "timeZone" zone) options)
                       options)
             formatter (js/Intl.DateTimeFormat. "en-CA" options)
             parts (.formatToParts formatter (js/Date.))
             values (into {}
                          (map (fn [part]
                                 [(.-type part) (.-value part)]))
                          (array-seq parts))]
         (str (get values "year") "-"
              (get values "month") "-"
              (get values "day")))
       (catch :default _
         (subs (.toISOString (js/Date.)) 0 10)))))

(defn add-days
  "Add DAYS to an ISO local date.  Invalid input returns nil."
  [date days]
  (when (non-blank date)
    #?(:clj
       (try
         (str (.plusDays (LocalDate/parse date) (long days)))
         (catch DateTimeParseException _ nil))
       :cljs
       (try
         (let [parsed (js/Date. (str date "T00:00:00Z"))]
           (when-not (js/isNaN (.getTime parsed))
             (.setUTCDate parsed (+ (.getUTCDate parsed) (long days)))
             (subs (.toISOString parsed) 0 10)))
         (catch :default _ nil)))))

(defn valid-date?
  [value]
  (and (non-blank value)
       (boolean (re-matches #"\d{4}-\d{2}-\d{2}" value))))

(defn preset-range
  "Return `{:from :to :all?}` for PRESET at TODAY.

  The end date is inclusive.  `:custom` returns nil because the caller's
  explicit dates are authoritative."
  [preset today]
  (case (keyword-value preset)
    :today {:from today :to today :all? false}
    :last-7-days {:from (add-days today -6) :to today :all? false}
    :last-30-days {:from (add-days today -29) :to today :all? false}
    :all {:from nil :to nil :all? true}
    :custom nil
    nil))

(defn day-of-epoch
  "Bucket one epoch-millisecond instant into a local date in ZONE."
  [epoch-ms zone]
  (when (number? epoch-ms)
    #?(:clj
       (try
         (str (.toLocalDate
               (.atZone (Instant/ofEpochMilli (long epoch-ms))
                        (or (zone-id zone) (ZoneId/systemDefault)))))
         (catch Exception _ nil))
       :cljs
       (try
         (let [options #js {:year "numeric" :month "2-digit" :day "2-digit"}
               options (if (non-blank zone)
                         (do (aset options "timeZone" zone) options)
                         options)
               formatter (js/Intl.DateTimeFormat. "en-CA" options)
               parts (.formatToParts formatter (js/Date. epoch-ms))
               values (into {}
                            (map (fn [part]
                                   [(.-type part) (.-value part)]))
                            (array-seq parts))]
           (str (get values "year") "-"
                (get values "month") "-"
                (get values "day")))
         (catch :default _
           (subs (.toISOString (js/Date. epoch-ms)) 0 10))))))

;; ---------------------------------------------------------------------------
;; Initial state

(defn initial-bundle
  []
  {:status :idle
   :value nil
   :error nil
   :request {:error-category :unclassified
             :from nil
             :to nil
             :zone nil
             :include-error-detail? false
             :include-raw-error? false
             :rename-original ""
             :rename-replacement ""
             :mask-hosts? false
             :mask-paths? false
             :materials []}
   :removed-lines #{}
   :export nil})

(defn initial-price
  []
  {:service-id nil
   :model nil
   :currency ""
   :input ""
   :output ""
   :cache-read ""
   :cache-write ""
   :source ""
   :source-date ""
   :status :idle
   :error nil})

(defn initial-feedback
  []
  {:title ""
   :steps ""
   :expected ""
   :actual ""
   :status :idle})

(defn initial-state
  []
  {:status :idle
   :loaded? false
   :stats nil
   :stats-stale? false
   :stats-error nil
   :error nil
   :message nil
   :query {:preset :last-30-days
           :from nil
           :to nil
           :zone nil
           :zone-mode :query
           :top-n 8
           :filters {:assistant-id nil
                     :model nil
                     :service-id nil}}
   :filter-options {:assistants []
                    :models []
                    :services []}
   :selected-day nil
   :hide-amounts? false
   :tab :statistics
   :can-submit? false
   :log nil
   :log-loaded? false
   :log-days 1
   :clear-log-confirm? false
   :faq-query ""
   :pending {}
   :bundle (initial-bundle)
   :price (initial-price)
   :feedback (initial-feedback)})

;; ---------------------------------------------------------------------------
;; Query construction and validation

(defn filters
  [state]
  (into {}
        (remove (comp nil? val))
        (get-in state [:query :filters])))

(defn request-query
  "Build the statistics command query for STATE at TODAY.

  Returns `{:ok? true :query ...}` or `{:ok? false :error {...}}`."
  [state today]
  (let [query (:query state)
        preset (keyword-value (:preset query :last-30-days))
        zone (:zone query)
        zone-mode (keyword-value (:zone-mode query :query))
        top-n (get query :top-n 8)
        preset-range (preset-range preset today)
        range (if (= :custom preset)
                {:from (:from query) :to (:to query) :all? false}
                (or preset-range {:from nil :to nil :all? false}))
        from (:from range)
        to (:to range)]
    (cond
      (not (contains? #{:today :last-7-days :last-30-days :all :custom}
                      preset))
      {:ok? false
       :error {:code :range-invalid
               :message "选择有效的时间范围。"}}

      (and (= :custom preset) (not (valid-date? from)))
      {:ok? false
       :error {:code :date-invalid
               :message "自定义范围需要有效的开始日期。"}}

      (and (= :custom preset) (not (valid-date? to)))
      {:ok? false
       :error {:code :date-invalid
               :message "自定义范围需要有效的结束日期。"}}

      (and (valid-date? from) (valid-date? to) (pos? (compare from to)))
      {:ok? false
       :error {:code :range-reversed
               :message "结束日期不能早于开始日期。"}}

      (not (contains? #{:query :record} zone-mode))
      {:ok? false
       :error {:code :zone-mode-invalid
               :message "时区口径必须是当前时区或记录发生时区。"}}

      (and (some? top-n)
           (not (and (integer? top-n) (not (neg? top-n)))))
      {:ok? false
       :error {:code :top-n-invalid
               :message "模型数量必须是非负整数。"}}

      :else
      {:ok? true
       :query (cond-> {:zone-mode zone-mode
                       :top-n top-n
                       :filters (filters state)}
                (:all? range) (assoc :all true)
                (and (not (:all? range)) from) (assoc :from from)
                (and (not (:all? range)) to) (assoc :to to)
                (non-blank zone) (assoc :zone zone))})))

(defn range-label
  [query]
  (let [preset (keyword-value (:preset query :last-30-days))]
    (case preset
      :custom (str (or (:from query) "?") " 至 " (or (:to query) "?"))
      (or (:label (some #(when (= preset (:id %)) %) range-presets))
          "自定义"))))

(defn stats-range-label
  "The range the returned statistics actually cover, not the pending query."
  [stats]
  (let [range (field stats :range)
        from (field range :from)
        to (field range :to)]
    (cond
      (true? (field range :all?)) "全部"
      (and (non-blank from) (non-blank to)) (str from " 至 " to)
      (non-blank from) (str from " 起")
      (non-blank to) (str "截至 " to)
      :else "未提供")))

(defn stats-filter-label
  "The filters the returned statistics were computed with."
  [stats]
  (let [filters (field stats :filters)
        assistant (field filters :assistant-id)
        model (field filters :model)
        service (field filters :service-id)
        parts (cond-> []
                (non-blank (str assistant))
                (conj (str "助手 " assistant))
                (non-blank (str model))
                (conj (str "模型 " model))
                (non-blank (str service))
                (conj (str "服务 " service)))]
    (if (seq parts)
      (str/join "；" parts)
      "无筛选")))

;; ---------------------------------------------------------------------------
;; Statistics projections

(defn stats-status
  [stats]
  (keyword-value (field stats :status)))

(defn stats-empty?
  [stats]
  (or (= :no-data (stats-status stats))
      (true? (field stats :empty?))))

(defn stats-failed?
  [stats]
  (= :read-failed (stats-status stats)))

(defn stats-invalid?
  [stats]
  (= :invalid-query (stats-status stats)))

(defn provenance-label
  [value]
  (case (keyword-value value)
    :reported "服务返回"
    :provider-reported "服务返回"
    :estimated "估算"
    :unknown "未提供"
    nil "未提供"
    "未知"))

(defn count-cards
  [stats]
  (let [counts (field stats :counts)]
    (when counts
      [{:id :user-sends
        :label "用户发送次数"
        :value (field counts :user-sends)
        :definition "一次被接受的用户提交"}
       {:id :model-requests
        :label "模型请求次数"
        :value (field counts :model-requests)
        :definition "一次供应商请求，包含重试"}
       {:id :tool-calls
        :label "工具调用次数"
        :value (field counts :tool-calls)
        :definition "一次已执行的工具调用"}
       {:id :active-days
        :label "活跃天数"
        :value (field counts :active-days)
        :definition "有发送、请求或工具调用的自然日"}])))

(defn token-rows
  [stats]
  (when-let [tokens (field stats :tokens)]
    (let [provenance (field tokens :provenance)
          rows [{:id :input
                 :label "输入 token"
                 :value (field tokens :input-tokens)
                 :provenance (field provenance :input-tokens)}
                {:id :output
                 :label "输出 token"
                 :value (field tokens :output-tokens)
                 :provenance (field provenance :output-tokens)}
                {:id :cache-read
                 :label "缓存命中（输入明细）"
                 :value (field tokens :cache-read-tokens)
                 :provenance (field provenance :cache-read-tokens)
                 :detail? true}
                {:id :cache-write
                 :label "缓存写入（输入明细）"
                 :value (field tokens :cache-write-tokens)
                 :provenance (field provenance :cache-write-tokens)
                 :detail? true}
                {:id :total
                 :label "总 token"
                 :value (field tokens :total-tokens)
                 :provenance (field tokens :total-provenance)
                 :emphasis? true}]]
      {:rows rows
       :cache-modes (field tokens :cache-modes)
       :cache-mode (keyword-value (field tokens :cache-mode))
       :cache-subset? (field tokens :cache-subset?)
       :reported-requests (field tokens :reported-requests)
       :estimated-requests (field tokens :estimated-requests)
       :unknown-requests (field tokens :unknown-requests)
       :unknown-fields (into {}
                             (filter (comp seq val))
                             (field tokens :unknown-requests-by-field))
       :total-provenance (field tokens :total-provenance)
       :request-count (field tokens :request-count)})))

(defn cost-coverage
  [stats]
  (when-let [cost (field stats :cost)]
    {:request-count (field cost :request-count)
     :estimated-requests (field cost :estimated-requests)
     :partial-requests (field cost :partial-requests)
     :unestimated-requests (field cost :unestimated-requests)
     :actual-requests (field cost :actual-requests)
     :unknown-currency-requests (field cost :unknown-currency-requests)
     :invalid-price-requests (field cost :invalid-price-requests)
     :invalid-billing-requests (field cost :invalid-billing-requests)
     :currency-mismatch-requests (field cost :currency-mismatch-requests)}))

(defn cost-rows
  "One row per currency.  Different currencies are never combined."
  [stats]
  (when-let [cost (field stats :cost)]
    (->> (field cost :by-currency)
         (map (fn [[currency entry]]
                {:currency (if (keyword? currency) (name currency) (str currency))
                 :estimated-amount (unwrap-amount (field entry :estimated-amount))
                 :actual-amount (unwrap-amount (field entry :actual-amount))
                 :estimated-requests (field entry :estimated-requests)
                 :partial-requests (field entry :partial-requests)
                 :unestimated-requests (field entry :unestimated-requests)
                 :actual-requests (field entry :actual-requests)
                 :pricing-sources (vec (sort (map str (field entry :pricing-sources))))
                 :price-dates (vec (sort (map str (field entry :price-dates))))}))
         (sort-by :currency)
         vec)))

(defn service-cost-rows
  [stats]
  (when-let [service-cost (field stats :service-cost)]
    {:usage-count (field service-cost :usage-count)
     :estimated-usages (field service-cost :estimated-usages)
     :unestimated-usages (field service-cost :unestimated-usages)
     :actual-usages (field service-cost :actual-usages)
     :reported-quantity-usages
     (field service-cost :reported-quantity-usages)
     :estimated-quantity-usages
     (field service-cost :estimated-quantity-usages)
     :unknown-quantity-usages
     (field service-cost :unknown-quantity-usages)
     :unknown-currency-usages (field service-cost :unknown-currency-usages)
     :invalid-price-usages (field service-cost :invalid-price-usages)
     :by-currency (->> (field service-cost :by-currency)
                       (map (fn [[currency entry]]
                              {:currency (if (keyword? currency)
                                           (name currency)
                                           (str currency))
                               :estimated-amount (unwrap-amount (field entry :estimated-amount))
                               :actual-amount (unwrap-amount (field entry :actual-amount))
                               :estimated-usages (field entry :estimated-usages)
                               :unestimated-usages (field entry :unestimated-usages)
                               :actual-usages (field entry :actual-usages)
                               :reported-quantity-usages
                               (field entry :reported-quantity-usages)
                               :estimated-quantity-usages
                               (field entry :estimated-quantity-usages)
                               :unknown-quantity-usages
                               (field entry :unknown-quantity-usages)
                               :units (->> (field entry :units)
                                           (map (fn [[unit values]]
                                                  {:unit (if (keyword? unit)
                                                           (name unit)
                                                           (str unit))
                                                   :quantity (field values :quantity)
                                                   :usages (field values :usages)
                                                   :estimated-amount
                                                   (unwrap-amount (field values :estimated-amount))}))
                                           (sort-by :unit)
                                           vec)}))
                       (sort-by :currency)
                       vec)
     :by-unit (->> (field service-cost :by-unit)
                   (map (fn [[unit values]]
                          {:unit (if (keyword? unit) (name unit) (str unit))
                           :currency (when-let [currency (field values :currency)]
                                       (if (keyword? currency)
                                         (name currency)
                                         (str currency)))
                           :quantity (field values :quantity)
                           :usages (field values :usages)
                           :estimated-amount (unwrap-amount (field values :estimated-amount))}))
                   (sort-by :unit)
                   vec)}))

(defn model-distribution
  [stats]
  (let [models (vec (or (field stats :models) []))
        top (field stats :top-models)
        top-rows (vec (or (field top :top) models))
        other (field top :other)
        other-requests (or (field other :requests) 0)
        total (+ other-requests
                 (reduce + 0 (keep #(field % :requests) top-rows)))
        rows (mapv (fn [row]
                     {:service-id (field row :service-id)
                      :service-name (or (field row :service-name)
                                        (field row :service-id)
                                        "未知服务")
                      :model (or (field row :model) "未知模型")
                      :requests (or (field row :requests) 0)
                      :usage (field row :usage)
                      :cost (field row :cost)
                      :share (if (pos? total)
                               (/ (double (or (field row :requests) 0))
                                  total)
                               0.0)})
                   top-rows)
        shown (set (map (juxt :service-id :model) rows))
        remainder (into []
                        (remove (fn [row]
                                  (contains? shown
                                             [(field row :service-id)
                                              (field row :model)])))
                        models)]
    {:rows rows
     :other (when other
              {:requests (field other :requests)
               :models (field other :models)})
     :remainder (mapv (fn [row]
                        {:service-id (field row :service-id)
                         :service-name (or (field row :service-name)
                                           (field row :service-id))
                         :model (or (field row :model) "未知模型")
                         :requests (field row :requests)
                         :usage (field row :usage)
                         :cost (field row :cost)})
                      remainder)
     :total-requests total}))

(defn cost-summary-label
  "A compact per-currency label.  No cross-currency total is produced."
  ([cost] (cost-summary-label cost false))
  ([cost hide?]
   (if hide?
     "金额已隐藏"
     (let [entries (field cost :by-currency)]
       (if (seq entries)
         (->> entries
              (map (fn [[currency entry]]
                     (let [estimated (unwrap-amount (field entry :estimated-amount))
                           actual (unwrap-amount (field entry :actual-amount))
                           label (fn [value]
                                   (if (number? value)
                                     (format-amount-number value)
                                     (str value)))
                           amount (cond
                                    (and (some? estimated) (some? actual))
                                    (str (label estimated) " 估算 / "
                                         (label actual) " 实收")

                                    (some? estimated) (str (label estimated) " 估算")
                                    (some? actual) (str (label actual) " 实收")
                                    :else "未估算")]
                       (str (if (keyword? currency) (name currency) currency)
                            " " amount))))
              (sort)
              (str/join "；"))
         "未估算")))))

(defn heatmap-cells
  [stats]
  (->> (field stats :heatmap)
       (map (fn [[day counts]]
              {:day (str day)
               :user-sends (or (field counts :user-sends) 0)
               :model-requests (or (field counts :model-requests) 0)
               :tool-calls (or (field counts :tool-calls) 0)}))
       (sort-by :day)
       vec))

(defn assistants-for-day
  "Assistant IDs visible in the turn rows for DAY, when the server sent them."
  [stats day zone]
  (->> (field stats :turns)
       (keep (fn [turn]
               (when (= day (day-of-epoch (field turn :at) zone))
                 (some-> (field turn :user-send)
                         (field :assistant-id)))))
       (remove nil?)
       distinct
       sort
       vec))

(defn turns
  [stats]
  (vec (or (field stats :turns) [])))

(defn unknown-time
  [stats]
  (field stats :unknown-time))

(defn excluded-unknown-time
  [stats]
  (field stats :excluded-unknown-time))

(defn filter-options
  [state stats]
  (let [current (:filter-options state)
        turns (or (field stats :turns) [])
        models (or (field stats :models) [])
        assistants (->> (concat (get current :assistants)
                                (keep #(some-> (field % :user-send)
                                               (field :assistant-id))
                                      turns))
                        (remove nil?)
                        distinct
                        sort
                        vec)
        model-options (->> (concat (get current :models)
                                   (map (fn [row]
                                          {:service-id (field row :service-id)
                                           :service-name (field row :service-name)
                                           :model (field row :model)})
                                        models))
                           (remove #(nil? (:model %)))
                           distinct
                           (sort-by (juxt (comp str :service-id)
                                          (comp str :model)))
                           vec)
        services (->> (concat (get current :services)
                              (map (fn [row]
                                     {:service-id (field row :service-id)
                                      :service-name (field row :service-name)})
                                   models))
                      (remove #(nil? (:service-id %)))
                      distinct
                      (sort-by (comp str :service-id))
                      vec)]
    {:assistants assistants
     :models model-options
     :services services}))

;; ---------------------------------------------------------------------------
;; Receipt application

(defn- error-message
  [error]
  (or (field error :message)
      (some-> (field error :code) name)
      "诊断命令未完成。"))

(defn apply-statistics
  [state stats]
  (let [stats (or stats {})
        status (stats-status stats)
        error (cond
                (= :read-failed status)
                {:code :read-failed
                 :message (or (field stats :message)
                              "统计读取失败；可以重试。")
                 :retry? true}
                (= :invalid-query status)
                {:code (or (field stats :error-code)
                           :invalid-query)
                 :message (or (field stats :message)
                              (field (field stats :error) :message)
                              "统计条件无效。")
                 :retry? false}
                :else nil)]
    (if error
      ;; A failed read is not a new result.  Keep the last successful
      ;; statistics and its timestamp so the view can show them under a
      ;; failure banner instead of replacing them with a failed map.
      (-> state
          (assoc :status status
                 :loaded? (or (:loaded? state) (some? (:stats state)))
                 :stats-error error
                 :error error
                 :message nil))
      (-> state
          (assoc :stats stats
                 :stats-error nil
                 :stats-stale? false
                 :loaded? true
                 :status (case status
                           :no-data :no-data
                           :ok)
                 :error nil
                 :message nil)
          (assoc :filter-options (filter-options state stats))))))

(defn apply-log
  [state log]
  (-> state
      (assoc :log log
             :log-loaded? true
             :message "详细日志状态已更新。")))

(defn transfer-local-only?
  "True only when the receipt says the bundle stayed local and no upload
  happened.  Missing or contradictory transfer facts are not local-only."
  [value]
  (let [transfer (field value :transfer)]
    (and (= :local-only (keyword-value (field transfer :mode)))
         (false? (field transfer :automatic-upload?))
         (false? (field transfer :network?))
         (not (true? (field transfer :uploaded?))))))

(defn- transfer-message
  [transfer]
  (let [mode (keyword-value (field transfer :mode))
        uploaded? (field transfer :uploaded?)
        mode-text (when (some? mode)
                    (str "；传输方式 " (name mode)))]
    (cond
      (true? uploaded?) (str "回执显示已上传" mode-text "。")
      (= :local-only mode) "回执显示尚未上传。"
      (some? mode) (str "传输方式 " (name mode) "；回执显示已上传："
                        (if (false? uploaded?) "否" "未说明") "。")
      (false? uploaded?) "回执显示尚未上传。"
      :else "回执未说明是否上传，也未提供传输方式。")))

(defn apply-bundle
  [state bundle]
  (-> state
      (assoc-in [:bundle :value] bundle)
      (assoc-in [:bundle :status] :preview)
      (assoc-in [:bundle :error] nil)
      (assoc-in [:bundle :export] nil)
      (assoc-in [:bundle :removed-lines] #{})
      (assoc :message
             (str "诊断预览已生成；"
                  (if (transfer-local-only? bundle)
                    "尚未上传。"
                    (str (transfer-message (field bundle :transfer))
                         (when (true? (field (field bundle :transfer)
                                             :automatic-upload?))
                           "回执显示自动上传已开启。")))))))

(defn apply-export
  [state result]
  (let [status (keyword-value (field result :status))
        artifact (field result :artifact)
        content? (some? (field artifact :content))
        artifact-id (field artifact :id)
        uploaded? (field result :uploaded?)
        automatic? (field result :automatic-upload?)
        base (cond
               (and (= :exported status) (false? uploaded?))
               (str "诊断包已写入服务器本地内容区"
                    (when artifact-id (str "（" artifact-id "）"))
                    "。")

               (true? uploaded?)
               "诊断包已导出。"

               content?
               "诊断包已准备为本地文件。"

               :else
               "诊断包已准备好。")]
    (-> state
        (assoc-in [:bundle :export] result)
        (assoc-in [:bundle :status] :exported)
        (assoc :message
               (str base
                    (transfer-message result)
                    (when (true? automatic?)
                      "回执显示自动上传已开启。"))))))

(defn apply-price-receipt
  [state receipt]
  (-> state
      (assoc-in [:price :status] :saved)
      (assoc-in [:price :error] nil)
      (assoc :message
             (str "价格已保存：" (or (field receipt :model) "模型")
                  "。旧记录保留原价格依据。"))))

(defn apply-receipt
  "Apply one command receipt value to STATE.

  Receipt values are the narrowed maps delivered as
  `CUSTOM :hc/command-receipt`.  A failed result is explicit; it never becomes
  a successful empty page."
  [state receipt]
  (let [outer receipt
        ok? (field outer :ok?)
        inner (or (field outer :receipt) outer)
        error (or (field outer :error)
                  (field inner :error)
                  {:message "诊断命令被拒绝。"})]
    (if (false? ok?)
      (-> state
          (assoc :status :error
                 :error error
                 :message (error-message error)))
      (cond
        (some? (field inner :statistics))
        (apply-statistics state (field inner :statistics))

        (some? (field inner :log))
        (apply-log state (field inner :log))

        (some? (field inner :bundle))
        (apply-bundle state (field inner :bundle))

        (or (some? (field inner :artifact))
            (contains? #{:local :exported} (keyword-value (field inner :status))))
        (apply-export state inner)

        (and (some? (field inner :service-id))
             (some? (field inner :model)))
        (apply-price-receipt state inner)

        :else
        (assoc state :message "诊断命令已接受。")))))

;; ---------------------------------------------------------------------------
;; Bundle helpers

(defn bundle-value
  [state]
  (get-in state [:bundle :value]))

(defn normalize-material
  "One material with a single inclusion key.

  The server preview returns `:included?`; the request uses `:include?`.
  Accept either and keep `:include?` so the checkbox has one source."
  [material]
  (let [include? (cond
                   (some? (:include? material)) (:include? material)
                   (some? (:included? material)) (:included? material)
                   :else false)]
    (-> material
        (assoc :include? (boolean include?))
        (dissoc :included?))))

(defn bundle-materials
  "The material list used by the editor and the preview request.

  Server descriptions supply id, title, kind, purpose, and content.  The
  user's explicit request supplies the include flag and the purpose.  A
  material that only the server listed is still sent with its full fields."
  [state]
  (let [server (mapv normalize-material
                     (or (get-in state [:bundle :value :extra-materials]) []))
        request (mapv normalize-material
                      (or (get-in state [:bundle :request :materials]) []))
        ids (distinct (remove nil? (concat (map :id server)
                                           (map :id request))))
        server-by-id (into {} (map (juxt :id identity)) server)
        request-by-id (into {} (map (juxt :id identity)) request)]
    (mapv (fn [id]
            (let [server-material (get server-by-id id)
                  request-material (get request-by-id id)
                  purpose (cond
                            (nil? request-material)
                            (or (:purpose server-material) "")

                            (contains? request-material :purpose)
                            (:purpose request-material)

                            :else
                            (or (:purpose server-material) ""))]
              (merge {:id id
                      :title (name id)
                      :kind :unknown
                      :purpose ""
                      :include? false}
                     request-material
                     server-material
                     {:id id
                      :include? (if request-material
                                  (boolean (:include? request-material))
                                  (boolean (:include? server-material)))
                      :purpose purpose})))
          ids)))

(defn material-entry
  "One material with its server description and current user choice."
  [state id]
  (or (some #(when (= id (:id %)) %) (bundle-materials state))
      (merge {:id id
              :title (name id)
              :kind :unknown
              :purpose ""
              :include? false}
             (some-> (some #(when (= id (:id %)) %)
                           (get-in state [:bundle :value :extra-materials]))
                     normalize-material))))

(defn bundle-sections
  "Return the preview sections with user-removed lines filtered out."
  [state]
  (let [removed (get-in state [:bundle :removed-lines] #{})
        sections (or (get-in state [:bundle :value :sections])
                     (get-in state [:bundle :value :content :sections])
                     [])]
    (mapv (fn [section]
            (update section :lines
                    (fn [lines]
                      (filterv #(not (contains? removed (field % :id)))
                               (or lines [])))))
          sections)))

(defn bundle-request
  "Build the request for `:diagnostics/bundle-preview` from STATE.

  The request contains only explicit user choices.  Chat bodies, attachments,
  and full requests are never added here."
  [state today]
  (let [request (get-in state [:bundle :request])
        stats (:stats state)
        stats-range (field stats :range)
        zone (or (:zone request)
                 (get-in state [:query :zone])
                 (field stats :timezone))
        from (or (:from request)
                 (field stats-range :from)
                 (get-in state [:query :from]))
        to (or (:to request)
               (field stats-range :to)
               (get-in state [:query :to]))
        rename-original (:rename-original request)
        rename-replacement (:rename-replacement request)
        rename (when (and (non-blank rename-original)
                          (non-blank rename-replacement))
                 {rename-original rename-replacement})
        services (mapv (fn [service]
                         {:connection-id (:service-id service)
                          :display-name (:service-name service)})
                       (get-in state [:filter-options :services]))
        materials (bundle-materials state)]
    (cond-> {:error-category (keyword-value (:error-category request))
             :time-range {:from from :to to :zone zone}
             :services services
             :extra-materials materials
             :include-error-detail? (true? (:include-error-detail? request))
             :include-raw-error? (true? (:include-raw-error? request))}
      rename (assoc :rename rename)
      (:mask-hosts? request) (assoc :mask-hosts? true)
      (:mask-paths? request) (assoc :mask-paths? true))))

(defn bundle-for-export
  [state]
  (let [bundle (bundle-value state)
        removed (get-in state [:bundle :removed-lines] #{})
        sections (bundle-sections state)]
    (when bundle
      (-> bundle
          (assoc :sections sections)
          (assoc-in [:content :sections] sections)
          (assoc :removed-line-ids removed)))))

(defn- update-material
  [state id f]
  (let [materials (bundle-materials state)
        materials (if (some #(= id (:id %)) materials)
                    materials
                    (conj materials (material-entry state id)))]
    (assoc-in state [:bundle :request :materials]
              (mapv (fn [material]
                      (if (= id (:id material)) (f material) material))
                    materials))))

(defn- update-request
  [state k v]
  (assoc-in state [:bundle :request k] v))

;; ---------------------------------------------------------------------------
;; Price helpers

(defn price-preview
  "Fixed-example preview for the price editor.

  The example is deliberately the same shape as the acceptance case:
  1000 input tokens, 200 of them cache hits, 0 cache writes, and 100 output
  tokens.  The cache fields are prices, not token counts.  A missing input or
  output rate leaves the amount unestimated; a missing cache-read rate prices
  only the known components and labels the result partial.  A negative rate is
  invalid and never produces a negative amount."
  [price]
  (let [input-rate (number-value (:input price))
        output-rate (number-value (:output price))
        cache-read-rate (number-value (:cache-read price))
        cache-write-rate (number-value (:cache-write price))
        currency (str/trim (str (or (:currency price) "")))
        example-input 1000
        example-cache-read 200
        example-cache-write 0
        example-output 100
        negative? (boolean
                   (some #(and (number? %) (neg? %))
                         [input-rate output-rate
                          cache-read-rate cache-write-rate]))
        missing-base? (or (nil? input-rate) (nil? output-rate))
        partial? (and (not missing-base?) (nil? cache-read-rate))
        amount (when (and (not negative?) (not missing-base?))
                 (+ (* (/ (- example-input example-cache-read
                             example-cache-write)
                          1000000.0)
                       input-rate)
                    (* (/ example-output 1000000.0) output-rate)
                    (if (some? cache-read-rate)
                      (* (/ example-cache-read 1000000.0) cache-read-rate)
                      0)
                    (if (some? cache-write-rate)
                      (* (/ example-cache-write 1000000.0) cache-write-rate)
                      0)))
        amount-label (when amount
                       #?(:clj (format "%.6f" (double amount))
                          :cljs (.toFixed amount 6)))
        label (cond
                negative? "无法估算：单价不能为负。"
                missing-base? "无法估算：需要输入与输出单价；缓存缺省不按免费处理。"
                partial? (str (if (str/blank? currency) "币种未设置" currency)
                              " " amount-label
                              "（部分估算：缓存命中单价缺失，不计入缓存费用）")
                :else (str (if (str/blank? currency) "币种未设置" currency)
                           " " amount-label "（固定示例估算）"))]
    {:input example-input
     :cache-read example-cache-read
     :cache-write example-cache-write
     :output example-output
     :total-tokens (+ example-input example-output)
     :currency currency
     :amount amount
     :estimated? (boolean amount)
     :invalid? negative?
     :partial? (boolean (and amount partial?))
     :label label}))

(defn price-payload
  [price]
  (let [number-field (fn [k]
                       (let [value (get price k)]
                         (when (and (string? value)
                                    (not (str/blank? value)))
                           (number-value value))))]
    (cond-> {:currency (str/trim (str (or (:currency price) "")))
             :unit :per-million-tokens}
      (non-blank (:source price)) (assoc :source (:source price))
      (non-blank (:source-date price)) (assoc :source-date (:source-date price))
      (some? (number-field :input)) (assoc :input (number-field :input))
      (some? (number-field :output)) (assoc :output (number-field :output))
      (some? (number-field :cache-read))
      (assoc :cache-read (number-field :cache-read))
      (some? (number-field :cache-write))
      (assoc :cache-write (number-field :cache-write)))))

(def price-rate-keys
  [:input :output :cache-read :cache-write])

(defn price-rate-state
  "Classify one rate field as `:empty`, `:valid`, `:negative`, or `:invalid`."
  [price k]
  (let [raw (get price k)]
    (cond
      (or (nil? raw) (and (string? raw) (str/blank? raw))) :empty
      (nil? (number-value raw)) :invalid
      (neg? (number-value raw)) :negative
      :else :valid)))

(defn price-validation
  "Validate the price editor before it produces a save effect.

  At least one of input, output, cache-read, or cache-write must be a
  non-negative number.  A negative or non-numeric rate is rejected instead of
  being silently dropped from the payload."
  [price]
  (let [service (:service-id price)
        model (:model price)
        currency (:currency price)
        states (map (fn [k] [k (price-rate-state price k)]) price-rate-keys)
        states-by-key (into {} states)
        negative (some (fn [[k state]] (when (= :negative state) k)) states)
        invalid (some (fn [[k state]] (when (= :invalid state) k)) states)
        valid? (some (fn [[_ state]] (= :valid state)) states)]
    (cond
      (not (non-blank (str service)))
      {:ok? false :message "价格需要选择服务。"}

      (not (non-blank (str model)))
      {:ok? false :message "价格需要选择模型。"}

      (not (non-blank (str currency)))
      {:ok? false :message "价格需要填写币种。"}

      negative
      {:ok? false
       :message (str "单价不能为负：" (name negative) "。")}

      invalid
      {:ok? false
       :message (str "单价必须是数字：" (name invalid) "。")}

      (not valid?)
      {:ok? false
       :message "价格需要至少一个非负数值单价：输入、输出、缓存命中或缓存写入。"}

      :else
      {:ok? true
       :message nil
       :rates states-by-key})))

;; ---------------------------------------------------------------------------
;; Feedback helpers

(defn feedback-text
  [feedback]
  (str "标题：" (or (:title feedback) "") "\n\n"
       "复现步骤：\n" (or (:steps feedback) "") "\n\n"
       "预期结果：\n" (or (:expected feedback) "") "\n\n"
       "实际结果：\n" (or (:actual feedback) "") "\n\n"
       "请确认此草稿不包含你不想分享的内容。产品不会自动提交公开 issue，也不会上传诊断附件。"))

(defn mailto-url
  [feedback]
  (str "mailto:?subject="
       #?(:clj (URLEncoder/encode
                (str "[Herdchestra] " (or (:title feedback) "反馈"))
                "UTF-8")
          :cljs (js/encodeURIComponent
                 (str "[Herdchestra] " (or (:title feedback) "反馈"))))
       "&body="
       #?(:clj (URLEncoder/encode (feedback-text feedback) "UTF-8")
          :cljs (js/encodeURIComponent (feedback-text feedback)))))

;; ---------------------------------------------------------------------------
;; Action reducer

(defn- mark-message
  [state message]
  (assoc state :message message))

(defn- submit-effect
  [kind payload]
  [{:effect :diagnostics-command
    :payload (assoc payload :kind kind)}])

(defn restore-failed-send
  "Restore optimistic state after a command could not be sent.

  Statistics must not stay `:loading`; a preview or export must return to the
  last preview (or an explicit error); a price save must leave `:saving`.
  The failure message stays visible, and an optimistic line removal is rolled
  back to the set recorded before the click."
  [state kind message previous-removed-lines]
  (let [kind (keyword-value kind)
        state (if (and (= kind :diagnostics/bundle-remove-lines)
                       (set? previous-removed-lines))
                (assoc-in state [:bundle :removed-lines] previous-removed-lines)
                state)]
    (case kind
      :diagnostics/statistics
      (cond-> state
        (= :loading (:status state)) (assoc :status :idle)
        true (assoc :error nil))

      :diagnostics/bundle-preview
      (-> state
          (assoc-in [:bundle :status]
                    (if (bundle-value state) :preview :error))
          (assoc-in [:bundle :error] {:message message}))

      :diagnostics/bundle-export
      (-> state
          (assoc-in [:bundle :status]
                    (if (bundle-value state) :preview :error))
          (assoc-in [:bundle :error] {:message message}))

      :diagnostics/save-price
      (-> state
          (assoc-in [:price :status] :error)
          (assoc-in [:price :error] {:message message}))

      (if (and (keyword? kind)
               (str/starts-with? (name kind) "bundle-"))
        (-> state
            (assoc-in [:bundle :status]
                      (if (bundle-value state) :preview :error))
            (assoc-in [:bundle :error] {:message message}))
        state))))

(defn restore-unknown-send
  "Clear an optimistic status after a lost receipt without claiming a result.

  The command may have run on the server.  Keep its pending entry routable and
  leave any optimistic line removal in place; only the status that was waiting
  for a receipt returns to a neutral value."
  [state kind]
  (let [kind (keyword-value kind)]
    (case kind
      :diagnostics/statistics
      (cond-> state
        (= :loading (:status state)) (assoc :status :idle))

      :diagnostics/save-price
      (assoc-in state [:price :status] :editing)

      (if (and (keyword? kind)
               (str/starts-with? (name kind) "bundle-"))
        (assoc-in state [:bundle :status]
                  (if (bundle-value state) :preview :idle))
        state))))

(defn reduce-action
  "Apply one UI action.  Returns `{:state state' :effects [...]}`.

  Effects are data for the bridge: `:request-statistics`,
  `:diagnostics-command`, `:bundle-preview`, `:bundle-export`, `:save-price`,
  `:copy-text`, `:open-mailto`, `:download-text`, and `:notify`."
  [state action]
  (let [state (or state (initial-state))
        type (:type action)
        [state effects]
        (case type
          :diagnostics/select-tab
          [(assoc state :tab (:tab action)) nil]

          :diagnostics/select-range
          [(-> state
               (assoc-in [:query :preset] (:preset action))
               (assoc :stats-stale? true)) nil]

          :diagnostics/set-custom-date
          [(-> state
               (assoc-in [:query (:field action)] (:value action))
               (assoc :stats-stale? true)) nil]

          :diagnostics/set-zone
          [(-> state
               (assoc-in [:query :zone] (:value action))
               (assoc :stats-stale? true)) nil]

          :diagnostics/set-zone-mode
          [(-> state
               (assoc-in [:query :zone-mode] (:value action))
               (assoc :stats-stale? true)) nil]

          :diagnostics/set-filter
          [(-> state
               (assoc-in [:query :filters (:key action)]
                         (when-not (str/blank? (str (:value action)))
                           (:value action)))
               (assoc :stats-stale? true))
           nil]

          :diagnostics/clear-filters
          [(-> state
               (assoc-in [:query :filters]
                         {:assistant-id nil :model nil :service-id nil})
               (assoc :stats-stale? true)) nil]

          :diagnostics/set-top-n
          (let [value (integer-value (:value action))]
            (if (some? value)
              [(-> state
                   (assoc-in [:query :top-n] value)
                   (assoc :stats-stale? true)) nil]
              [(mark-message state "模型数量必须是非负整数。") nil]))

          :diagnostics/select-day
          [(assoc state :selected-day (:day action)) nil]

          :diagnostics/set-faq-query
          [(assoc state :faq-query (str (or (:value action) ""))) nil]

          :diagnostics/set-hide-amounts
          [(assoc state :hide-amounts? (boolean (:value action))) nil]

          :diagnostics/refresh
          [(-> state
               (assoc :status :loading :error nil)
               (mark-message "正在读取统计…"))
           [{:effect :request-statistics}]]

          :diagnostics/retry
          [(-> state
               (assoc :status :loading :error nil)
               (mark-message "正在重试统计读取…"))
           [{:effect :request-statistics}]]

          :diagnostics/apply-statistics
          [(apply-statistics state (:stats action)) nil]

          :diagnostics/apply-log
          [(apply-log state (:log action)) nil]

          :diagnostics/apply-error
          [(-> state
               (assoc :status :error
                      :error (or (:error action)
                                 {:message "诊断命令未完成。"})
                      :message (error-message (:error action)))) nil]

          :diagnostics/apply-receipt
          [(apply-receipt state (:receipt action)) nil]

          :diagnostics/dismiss-message
          [(assoc state :message nil) nil]

          :diagnostics/log-enable
          [(mark-message state "正在请求开启详细日志…")
           (submit-effect :diagnostics/log-enable
                          {:mode (keyword-value (or (:mode action) :next-request))
                           :days (:days action)})]

          :diagnostics/log-set-days
          [(assoc state :log-days (max 1 (min 7 (or (integer-value (:value action)) 1)))) nil]

          :diagnostics/log-disable
          [(mark-message state "正在关闭详细日志…")
           (submit-effect :diagnostics/log-disable {})]

          :diagnostics/log-pause
          [(mark-message state "正在暂停详细日志…")
           (submit-effect :diagnostics/log-pause {})]

          :diagnostics/log-resume
          [(mark-message state "正在恢复详细日志…")
           (submit-effect :diagnostics/log-resume {})]

          :diagnostics/log-clear-request
          [(assoc state :clear-log-confirm? true) nil]

          :diagnostics/log-clear-cancel
          [(assoc state :clear-log-confirm? false) nil]

          :diagnostics/log-clear
          [(assoc state :clear-log-confirm? false)
           (submit-effect :diagnostics/log-clear {})]

          :diagnostics/bundle-set
          [(update-request state (:key action) (:value action)) nil]

          :diagnostics/bundle-preview
          [(-> state
               (assoc-in [:bundle :status] :loading)
               (assoc-in [:bundle :error] nil)
               (mark-message "正在生成脱敏预览；上传状态以回执为准。"))
           [{:effect :bundle-preview}]]

          :diagnostics/bundle-remove-line
          (let [previous (get-in state [:bundle :removed-lines] #{})
                next-state (-> state
                               (update-in [:bundle :removed-lines]
                                          conj (:line-id action))
                               (assoc-in [:bundle :export] nil))]
            [next-state
             [{:effect :bundle-edit
               :kind :diagnostics/bundle-remove-lines
               :line-ids [(:line-id action)]
               :previous-removed-lines previous
               :bundle (bundle-for-export next-state)}]])

          :diagnostics/bundle-restore-lines
          [(-> state
               (assoc-in [:bundle :removed-lines] #{})
               (assoc-in [:bundle :status] :loading)
               (assoc-in [:bundle :export] nil)
               (mark-message "正在重新生成完整预览…"))
           [{:effect :bundle-preview}]]

          :diagnostics/bundle-rename-input
          [(assoc-in state [:bundle :request (:field action)] (:value action)) nil]

          :diagnostics/bundle-rename
          (let [request (get-in state [:bundle :request])]
            (if (and (non-blank (:rename-original request))
                     (non-blank (:rename-replacement request)))
              [(-> state
                   (assoc-in [:bundle :status] :loading)
                   (assoc-in [:bundle :export] nil)
                   (mark-message "正在应用昵称替换并重新脱敏…"))
               [{:effect :bundle-edit
                 :kind :diagnostics/bundle-rename
                 :bundle (bundle-for-export state)
                 :rename {(:rename-original request)
                          (:rename-replacement request)}}]]
              [(mark-message state "昵称替换需要原始名称和替换名称。") nil]))

          :diagnostics/bundle-hide-hosts
          (let [hidden? (get-in state [:bundle :request :mask-hosts?])
                next-state (-> state
                               (assoc-in [:bundle :status] :loading)
                               (assoc-in [:bundle :export] nil)
                               (update-in [:bundle :request :mask-hosts?] not)
                               (mark-message "正在切换域名遮蔽并重新脱敏…"))]
            [next-state
             (if hidden?
               [{:effect :bundle-preview}]
               [{:effect :bundle-edit
                 :kind :diagnostics/bundle-hide-hosts
                 :bundle (bundle-for-export state)}])])

          :diagnostics/bundle-hide-paths
          (let [hidden? (get-in state [:bundle :request :mask-paths?])
                next-state (-> state
                               (assoc-in [:bundle :status] :loading)
                               (assoc-in [:bundle :export] nil)
                               (update-in [:bundle :request :mask-paths?] not)
                               (mark-message "正在切换路径遮蔽并重新脱敏…"))]
            [next-state
             (if hidden?
               [{:effect :bundle-preview}]
               [{:effect :bundle-edit
                 :kind :diagnostics/bundle-hide-paths
                 :bundle (bundle-for-export state)}])])

          :diagnostics/bundle-material-toggle
          (let [id (:material-id action)
                current (material-entry state id)
                next-include? (if (contains? action :value)
                                (boolean (:value action))
                                (not (boolean (:include? current))))
                next-state (update-material
                            state id
                            (fn [material]
                              (let [purpose (:purpose material)]
                                (assoc material
                                       :include? next-include?
                                       :purpose (if (and (string? purpose)
                                                         (not (str/blank? purpose)))
                                                  purpose
                                                  "用户选择包含此材料")))))]
            [next-state nil])

          :diagnostics/bundle-material-purpose
          [(update-material state (:material-id action)
                            #(assoc % :purpose (:value action))) nil]

          :diagnostics/bundle-material-apply
          [(-> state
               (assoc-in [:bundle :status] :loading)
               (assoc-in [:bundle :export] nil)
               (mark-message "正在应用额外材料选择并重新脱敏…"))
           [{:effect :bundle-preview}]]

          :diagnostics/bundle-export
          (if (bundle-value state)
            [(-> state
                 (assoc-in [:bundle :status] :exporting)
                 (assoc-in [:bundle :export] nil)
                 (mark-message "正在准备诊断文件；上传状态以回执为准。"))
             [{:effect :bundle-export}]]
            [(mark-message state "先生成诊断预览，再导出。") nil])

          :diagnostics/price-set
          [(-> state
               (assoc-in [:price (:field action)] (:value action))
               (assoc-in [:price :status] :editing)
               (assoc-in [:price :error] nil))
           nil]

          :diagnostics/save-price
          (let [price (:price state)
                payload (price-payload price)
                validation (price-validation price)]
            (if (:ok? validation)
              [(-> state
                   (assoc-in [:price :status] :saving)
                   (assoc-in [:price :error] nil)
                   (mark-message "正在保存价格…"))
               [{:effect :save-price
                 :service-id (:service-id price)
                 :model (:model price)
                 :price payload}]]
              [(-> state
                   (assoc-in [:price :status] :error)
                   (assoc-in [:price :error]
                             {:message (:message validation)})
                   (mark-message (:message validation)))
               nil]))

          :diagnostics/feedback-set
          [(assoc-in state [:feedback (:field action)] (:value action)) nil]

          :diagnostics/feedback-copy
          [(assoc state :feedback
                  (assoc (:feedback state) :status :copied))
           [{:effect :copy-text :text (feedback-text (:feedback state))}]]

          :diagnostics/feedback-save
          [(assoc state :feedback
                  (assoc (:feedback state) :status :saved))
           [{:effect :download-text
             :filename "herdchestra-feedback.txt"
             :content (feedback-text (:feedback state))}]]

          :diagnostics/feedback-open
          [(assoc state :feedback
                  (assoc (:feedback state) :status :opened))
           [{:effect :open-mailto :url (mailto-url (:feedback state))}]]

          [(mark-message state (str "未知诊断动作：" (name (or type :unknown)))) nil])]
    {:state state
     :effects (vec (or effects []))}))
