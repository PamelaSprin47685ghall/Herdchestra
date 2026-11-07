(ns hc.client.generated-card.model
  "Pure model for model-generated interactive cards (root AGENTS.md §6.5 UX-20).

  A card is plain EDN.  This namespace owns labels, form defaults and
  validation, conditional-field visibility, the exact submission payload,
  chart filtering/sorting/paging, chart geometry inputs, the data-table view,
  CSV export, and the readable text fallback.  It performs no I/O and never
  starts a model request.

  The React surface is `hc.client.generated-card.view`.  The conversation
  surface mounts it for an assistant message's `:generated-card` field."
  (:require [clojure.string :as str]))

;;; ---------------------------------------------------------------------------
;;; Small helpers

(defn- present?
  [value]
  (and (some? value)
       (not (and (string? value) (str/blank? value)))))

(defn- as-keyword
  [value]
  (cond
    (keyword? value) value
    (string? value) (keyword value)
    :else value))

(defn- as-vector
  [value]
  (cond
    (nil? value) []
    (vector? value) value
    (sequential? value) (vec value)
    :else [value]))

(defn- round-int
  [value]
  #?(:clj (Math/round (double value))
     :cljs (js/Math.round value)))

(defn- ceil-int
  [value]
  #?(:clj (long (Math/ceil (double value)))
     :cljs (js/Math.ceil value)))

(defn- abs-number
  [value]
  #?(:clj (Math/abs (double value))
     :cljs (js/Math.abs value)))

(declare normalize-form normalize-chart series)

(defn- finite-number?
  [value]
  (and (number? value)
       #?(:clj (Double/isFinite (double value))
          :cljs (js/Number.isFinite value))))

(defn parse-number
  "Parse a number or numeric string.  Returns nil for blank, invalid, NaN, or
  infinity.  Keeps the original value available to callers that need it."
  [value]
  (cond
    (finite-number? value) value
    (string? value)
    (let [trimmed (str/trim value)]
      (when (and (seq trimmed)
                 (re-matches #"[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?" trimmed))
        (let [parsed #?(:clj (try (Double/parseDouble trimmed)
                                  (catch NumberFormatException _ nil))
                        :cljs (js/parseFloat trimmed))]
          (when (and (finite-number? parsed)) parsed))))
    :else nil))

(defn- numeric-string?
  [value]
  (some? (parse-number value)))

(defn- iso-date?
  [value]
  (boolean
   (and (string? value)
        (re-matches #"\d{4}-\d{2}-\d{2}" value))))

(defn- fmt-number
  "Display a number without adding locale-specific grouping.  Preserve the
  original precision; trim only a trailing .0 for integers."
  [value]
  (cond
    (not (number? value)) (str value)
    (and (integer? value)) (str value)
    (and (finite-number? value) (== (double value) (round-int value)))
    #?(:clj (str (long value)) :cljs (str (js/Math.round value)))
    :else (str value)))

(defn- normalize-key
  [value]
  (as-keyword value))

;;; ---------------------------------------------------------------------------
;;; Card shell

(defn kind
  [card]
  (or (as-keyword (:kind card))
      (when (contains? card :chart) :chart)
      :form))

(defn card-id
  [card]
  (or (:card-id card) (:id card)))

(defn title
  [card]
  (or (not-empty (str (:title card)))
      "生成的交互内容"))

(defn purpose
  [card]
  (or (:purpose card) (:description card) ""))

(defn state
  [card]
  (as-keyword (or (:state card) :ready)))

(defn state-label
  [card]
  (case (state card)
    :generating "正在生成"
    :failed "生成未完成"
    :ready nil
    "状态未知"))

(defn generated?
  [card]
  (not (false? (:generated? card))))

(defn generated-label
  [card]
  (when (generated? card) "生成的交互内容"))

(defn generated-by
  [card]
  (or (:generated-by card) (:author card)))

(defn generated-at
  [card]
  (or (:generated-at card) (:at card)))

(defn provenance
  [card]
  (as-keyword (or (:provenance card) :model)))

(defn provenance-label
  [card]
  (case (provenance card)
    :model "模型生成"
    :user "用户提供"
    :assumption "假设示例"
    :mixed "混合来源"
    "来源未标注"))

(defn illustrative?
  [card]
  (boolean (:illustrative? card)))

(defn source
  [card]
  (or (:source card) (:source-label card) ""))

(defn source-label
  [card]
  (or (:source-label card) "数据来源"))

(defn text-fallback
  [card]
  (or (:text-fallback card) (:fallback card) ""))

(defn ready?
  [card]
  (= :ready (state card)))

(defn normalize-card
  "Normalize a card map without losing unknown presentation fields.  Missing
  kind is inferred from `:form`/`:chart`; defaults are explicit so the view
  never has to guess."
  [card]
  (let [card (or card {})
        kind (kind card)]
    (cond-> (assoc card
                   :kind kind
                   :state (state card)
                   :generated? (generated? card)
                   :provenance (provenance card))
      (= kind :form) (update :form normalize-form)
      (= kind :chart) (update :chart normalize-chart))))

;;; ---------------------------------------------------------------------------
;;; Form fields

(defn fields
  [card]
  (as-vector (get-in card [:form :fields])))

(defn field-name
  [field]
  (normalize-key (or (:name field) (:id field))))

(defn field-type
  [field]
  (as-keyword (or (:type field) :text)))

(defn field-label
  [field]
  (or (not-empty (str (:label field)))
      (name (field-name field))
      "字段"))

(defn field-unit
  [field]
  (or (:unit field) (:suffix field)))

(defn field-help
  [field]
  (or (:help field) (:description field) (:hint field)))

(defn field-required?
  [field]
  (boolean (:required? field)))

(defn field-secret?
  [field]
  (boolean (or (:secret? field)
               (= :secret (field-type field)))))

(defn field-options
  [field]
  (as-vector (:options field)))

(defn field-default
  [field]
  (if (contains? field :default)
    (:default field)
    (case (field-type field)
      :boolean false
      :number nil
      :range (or (:min field) 0)
      :select (let [option (first (field-options field))]
                (if (map? option) (:value option) option))
      "")))

(defn normalize-field
  [field]
  (let [field (or field {})
        type (field-type field)
        options (mapv (fn [option]
                        (if (map? option)
                          (update option :value normalize-key)
                          {:value option :label (str option)}))
                      (field-options field))
        default (field-default field)
        default (if (= :select type)
                  (or (some (fn [option]
                              (let [value (:value option)]
                                (when (= (if (keyword? value) (name value) (str value))
                                         (str default))
                                  value)))
                            options)
                      default)
                  default)]
    (assoc field
           :name (field-name field)
           :type type
           :label (field-label field)
           :required? (field-required? field)
           :secret? (field-secret? field)
           :options options
           :default default)))

(defn normalize-form
  [form]
  (let [form (or form {})]
    (assoc form
           :fields (mapv normalize-field (as-vector (:fields form)))
           :submit-label (or (:submit-label form) "发送给助手")
           :local-label (or (:local-label form) "仅保存到本卡")
           :preview-label (or (:preview-label form) "预览将发送内容"))))

(defn initial-values
  "Initial local values for every field, including hidden conditional fields.
  Hidden fields keep their drafts; they are simply excluded from submission."
  [card]
  (into {}
        (map (fn [field]
               [(field-name field) (field-default field)]))
        (fields card)))

(defn field-value
  [values field]
  (get values (field-name field)))

(defn visible-field?
  "Conditional visibility.  `:visible-when` is one of:
    {:field <name> :equals <value>}
    {:field <name> :in [<value> ...]}
    {:field <name> :truthy? true}
  A missing condition means visible."
  [values field]
  (let [condition (:visible-when field)]
    (if-not (map? condition)
      true
      (let [other (get values (normalize-key (:field condition)))]
        (cond
          (contains? condition :equals) (= other (:equals condition))
          (contains? condition :in) (contains? (set (:in condition)) other)
          (contains? condition :truthy?) (if (:truthy? condition)
                                           (boolean other)
                                           (not (boolean other)))
          :else true)))))

(defn visible-fields
  [card values]
  (filterv #(visible-field? values %) (fields card)))

(defn hidden-fields
  [card values]
  (remove #(visible-field? values %) (fields card)))

(defn option-label
  [field value]
  (some (fn [option]
          (when (= value (:value option)) (:label option)))
        (field-options field)))

(defn display-value
  "Human-readable value.  Secrets are always masked; callers must not use this
  to build a submission payload."
  [field value]
  (cond
    (field-secret? field) (if (present? value) "••••••" "")
    (nil? value) ""
    (= :boolean (field-type field)) (if value "是" "否")
    (= :select (field-type field)) (or (option-label field value) (str value))
    (and (= :number (field-type field)) (some? (parse-number value)))
    (str (fmt-number (parse-number value))
         (when-let [unit (field-unit field)] (str " " unit)))
    (and (= :range (field-type field)) (some? (parse-number value)))
    (str (fmt-number (parse-number value))
         (when-let [unit (field-unit field)] (str " " unit)))
    (and (= :date (field-type field)) (present? value))
    (str value
         (when-let [timezone (:timezone field)] (str " · " timezone)))
    :else (str value)))

(defn validate-field
  "Return an error string or nil.  Validation runs on the raw local value;
  hidden fields are not validated because they are not submitted."
  [field value]
  (let [type (field-type field)
        required? (field-required? field)
        blank? (not (present? value))]
    (cond
      (and required? blank?) "必填"
      (and (= type :number) (not blank?))
      (if-let [number (parse-number value)]
        (let [min (parse-number (:min field))
              max (parse-number (:max field))]
          (cond
            (and (some? min) (< number min))
            (str "不能小于 " (fmt-number (:min field)))
            (and (some? max) (> number max))
            (str "不能大于 " (fmt-number (:max field)))
            :else nil))
        "请输入数字")
      (and (= type :range) (not blank?))
      (if-let [number (parse-number value)]
        (let [min (parse-number (:min field))
              max (parse-number (:max field))]
          (cond
            (and (some? min) (< number min))
            (str "不能小于 " (fmt-number (:min field)))
            (and (some? max) (> number max))
            (str "不能大于 " (fmt-number (:max field)))
            :else nil))
        "请输入数字")
      (and (= type :date) (not blank?))
      (if (iso-date? value) nil "请使用 YYYY-MM-DD 格式")
      (and (= type :select) (not blank?))
      (if (some #(= value (:value %)) (field-options field))
        nil
        "请选择有效选项")
      (and (contains? field :max-length)
           (string? value)
           (> (count value) (:max-length field)))
      (str "最多 " (:max-length field) " 个字符")
      :else nil)))

(defn validate
  "Validate visible fields.  Returns {:ok? bool :errors {name message}
  :first-error name}.  Hidden fields are intentionally not validated."
  [card values]
  (let [validated (remove field-secret? (visible-fields card values))
        errors (reduce (fn [acc field]
                         (let [name (field-name field)
                               error (validate-field field (get values name))]
                           (if error (assoc acc name error) acc)))
                       {}
                       validated)]
    {:ok? (empty? errors)
     :errors errors
     :first-error (first (keep (fn [field]
                                 (let [name (field-name field)]
                                   (when (contains? errors name) name)))
                               validated))}))

(defn submission-preview
  "Rows the operator will actually send.  Hidden fields and secrets are not
  values in the payload; secrets are called out so the UI can explain the
  exclusion."
  [card values]
  (let [visible (visible-fields card values)
        rows (mapv (fn [field]
                     (let [secret? (field-secret? field)]
                       {:name (field-name field)
                        :label (field-label field)
                        :unit (field-unit field)
                        :secret? secret?
                        :value (if secret?
                                 nil
                                 (get values (field-name field)))
                        :display (if secret?
                                   "已排除（秘密字段）"
                                   (display-value field (get values (field-name field))))}))
                   visible)]
    {:rows rows
     :secret-fields (mapv field-name (filter field-secret? visible))
     :hidden-fields (mapv field-name (hidden-fields card values))}))

(defn submission
  "Build the exact local submission payload.  Only visible, non-secret fields
  are included.  Validation must pass first; on failure returns the validation
  map with :ok? false and no :values."
  [card values]
  (let [check (validate card values)]
    (if-not (:ok? check)
      check
      (let [visible (visible-fields card values)
            fields (remove field-secret? visible)
            payload (into {}
                          (map (fn [field]
                                 (let [name (field-name field)
                                       value (get values name)
                                       type (field-type field)]
                                   [name (cond
                                           (= type :number) (or (parse-number value) value)
                                           (= type :range) (or (parse-number value) value)
                                           (= type :boolean) (boolean value)
                                           :else value)])))
                          fields)]
        {:ok? true
         :card-id (card-id card)
         :values payload
         :fields (mapv field-name fields)
         :excluded-secret-fields (mapv field-name (filter field-secret? visible))
         :hidden-fields (mapv field-name (hidden-fields card values))
         :text (str (title card) "\n"
                    (when-let [p (not-empty (str (purpose card)))] (str p "\n"))
                    (str/join "\n"
                              (map (fn [field]
                                     (str "- " (field-label field) "："
                                          (display-value field (get values (field-name field)))))
                                   fields)))}))))

(defn form-text
  "Readable text for copy/fallback."
  [card values]
  (let [{:keys [rows]} (submission-preview card values)]
    (str (title card) "\n"
         (when-let [p (not-empty (str (purpose card)))] (str p "\n"))
         (when (seq rows)
           (str/join "\n"
                     (map (fn [{:keys [label display]}]
                            (str "- " label "：" display))
                          rows)))
         "\n来源：" (provenance-label card))))

;;; ---------------------------------------------------------------------------
;;; Chart and table

(defn chart
  [card]
  (or (:chart card) {}))

(defn chart-type
  [card]
  (as-keyword (or (:type (chart card)) :bar)))

(defn x-label
  [card]
  (or (:x-label (chart card)) "类别"))

(defn y-label
  [card]
  (or (:y-label (chart card)) "数值"))

(defn chart-units
  "Distinct units declared by the chart or its series, in display order."
  [card]
  (->> (concat (when-let [unit (:unit (chart card))] [unit])
               (keep :unit (series card)))
       distinct
       vec))

(defn chart-unit
  "The single shared unit, or nil when the chart mixes units."
  [card]
  (let [units (chart-units card)]
    (when (= 1 (count units)) (first units))))

(defn chart-unit-label
  "Axis unit label: the shared unit, `多单位` for a mixed chart, or nil."
  [card]
  (let [units (chart-units card)]
    (cond
      (= 1 (count units)) (first units)
      (seq units) "多单位"
      :else nil)))

(defn time-range
  [card]
  (or (:time-range (chart card))
      (:range (chart card))
      (:time-range card)
      (:range card)))

(defn series
  [card]
  (let [chart (chart card)
        explicit (as-vector (:series chart))
        inferred-keys (->> (:data chart)
                           (as-vector)
                           (mapcat keys)
                           (remove #(contains? #{:x :label :id "x" "label" "id"} %))
                           distinct
                           vec)]
    (if (seq explicit)
      (mapv (fn [index item]
              (let [item (if (map? item) item {:key item})]
                (cond-> (assoc item
                               :key (normalize-key (or (:key item) (:id item)))
                               :label (or (:label item) (name (normalize-key (:key item))))
                               :unit (or (:unit item) (:unit chart))
                               :color (or (:color item)
                                          (nth ["#0a84ff" "#34c759" "#ff9f0a" "#bf5af2"]
                                               (mod index 4))))
                  (nil? (:key item)) (assoc :key (normalize-key (first inferred-keys))))))
            (range)
            explicit)
      (mapv (fn [index key]
              {:key key
               :label (name key)
               :unit (:unit chart)
               :color (nth ["#0a84ff" "#34c759" "#ff9f0a" "#bf5af2"] (mod index 4))})
            (range)
            inferred-keys))))

(defn rows
  [card]
  (let [raw (as-vector (get-in card [:chart :data]))]
    (mapv (fn [row]
            (if (map? row)
              (into {}
                    (map (fn [[k v]] [(normalize-key k) v]))
                    row)
              {:x row}))
          raw)))

(defn normalize-chart
  [chart]
  (let [chart (or chart {})]
    (assoc chart
           :type (as-keyword (or (:type chart) :bar))
           :series (as-vector (:series chart))
           :data (as-vector (:data chart))
           :page-size (or (:page-size chart) 8))))

(defn cell-value
  [row series-key]
  (get row (normalize-key series-key)))

(defn missing?
  [value]
  (or (nil? value)
      (and (string? value) (str/blank? value))
      (and (number? value) (not (finite-number? value)))))

(defn row-has-value?
  [row series-list]
  (boolean (some (fn [s] (not (missing? (cell-value row (:key s))))) series-list)))

(defn filter-rows
  "Filter rows by `{:x value :hide-missing? bool}`.  `:x` nil/`:all` means all
  categories.  `:hide-missing?` drops rows where every series value is
  missing; individual missing cells remain visible in the table."
  [rows filter-spec]
  (let [filter-spec (or filter-spec {})
        x (:x filter-spec)
        hide? (boolean (:hide-missing? filter-spec))
        series-list (or (:series filter-spec)
                        (mapv (fn [k] {:key k})
                              (remove #{:x} (distinct (mapcat keys rows)))))]
    (cond->> rows
      (and (present? x) (not= :all x) (not= "all" x))
      (filter (fn [row] (= (str (:x row)) (str x))))

      hide?
      (filter (fn [row] (row-has-value? row (or series-list [])))))))

(defn active-filter-labels
  [filter-spec]
  (let [filter-spec (or filter-spec {})
        x (:x filter-spec)]
    (cond-> []
      (and (present? x) (not= :all x) (not= "all" x))
      (conj (str "类别：" x))
      (:hide-missing? filter-spec)
      (conj "隐藏全缺失行"))))

(defn columns
  [card]
  (into [{:key :x :label (x-label card) :type :string :unit nil}]
        (map (fn [s]
               {:key (:key s)
                :label (:label s)
                :type :number
                :unit (:unit s)}))
        (series card)))

(defn- column-for
  [card key]
  (some #(when (= (:key %) (normalize-key key)) %) (columns card)))

(defn sort-rows
  "Type-aware display sort.  Missing values stay last in both directions.
  Sorting never changes the underlying rows."
  [rows columns sort-spec]
  (if-let [{:keys [key direction]} sort-spec]
    (let [column (some #(when (= (:key %) (normalize-key key)) %) columns)
          direction (if (= :desc (as-keyword direction)) :desc :asc)
          indexed (map-indexed vector rows)
          compare-row (fn [[ia a] [ib b]]
                        (let [va (get a (normalize-key key))
                              vb (get b (normalize-key key))
                              ma (missing? va)
                              mb (missing? vb)]
                          (cond
                            (and ma mb) (compare ia ib)
                            ma 1
                            mb -1
                            (= :number (:type column))
                            (let [na (parse-number va)
                                  nb (parse-number vb)
                                  cmp (compare na nb)]
                              (if (zero? cmp) (compare ia ib)
                                  (if (= direction :desc) (- cmp) cmp)))
                            :else
                            (let [cmp (compare (str va) (str vb))]
                              (if (zero? cmp) (compare ia ib)
                                  (if (= direction :desc) (- cmp) cmp))))))]
      (mapv second (sort compare-row indexed)))
    (vec rows)))

(defn page-size
  [card]
  (max 1 (long (or (get-in card [:chart :page-size]) 8))))

(defn page-count
  [rows card]
  (max 1 (ceil-int (/ (double (count rows)) (page-size card)))))

(defn clamp-page
  [page rows card]
  (let [count (page-count rows card)]
    (max 0 (min (long (or page 0)) (dec count)))))

(defn page-rows
  [rows page card]
  (let [size (page-size card)
        page (clamp-page page rows card)
        start (* page size)]
    (subvec (vec rows) start (min (count rows) (+ start size)))))

(defn page-info
  [rows page card]
  (let [total (count rows)
        size (page-size card)
        count (page-count rows card)
        page (clamp-page page rows card)
        start (if (zero? total) 0 (inc (* page size)))
        end (min total (+ (* page size) size))]
    {:page page
     :page-count count
     :start start
     :end end
     :total total
     :complete? (<= total (* (inc page) size))}))

(defn missing-count
  [rows series-list]
  (reduce + 0
          (for [row rows
                s series-list
                :when (missing? (cell-value row (:key s)))]
            1)))

(defn series-stats
  [rows series-list]
  (mapv (fn [s]
          (let [values (keep (fn [row]
                               (let [v (cell-value row (:key s))]
                                 (when-not (missing? v) (parse-number v))))
                             rows)
                n (count values)]
            {:key (:key s)
             :label (:label s)
             :unit (:unit s)
             :count n
             :min (when (pos? n) (apply min values))
             :max (when (pos? n) (apply max values))
             :sum (when (pos? n) (reduce + values))
             :average (when (pos? n) (/ (reduce + values) n))}))
        series-list))

(defn summary
  [card rows filter-spec]
  (let [series-list (series card)
        filtered (filter-rows rows filter-spec)]
    {:total (count rows)
     :visible (count filtered)
     :missing (missing-count filtered series-list)
     :stats (series-stats filtered series-list)
     :filters (active-filter-labels filter-spec)}))

(defn- csv-cell
  [value]
  (let [text (cond
               (missing? value) ""
               (number? value) (fmt-number value)
               :else (str value))]
    (if (re-find #"[,\"\n\r]" text)
      (str "\"" (str/replace text "\"" "\"\"") "\"")
      text)))

(defn csv
  "CSV for the current filtered rows.  Metadata comments record the title,
  source, filter, and whether the export is complete or the current page.
  Values are never rounded for display.  `:current-page?` selects the page
  from the full filtered ROWS; the page count always describes that full set."
  ([card rows filter-spec] (csv card rows filter-spec {}))
  ([card rows filter-spec {:keys [page current-page?]}]
   (let [rows (vec rows)
         export-rows (if current-page? (page-rows rows page card) rows)
         series-list (series card)
         header (into [(x-label card)]
                      (map (fn [s]
                             (str (:label s)
                                  (when-let [unit (:unit s)] (str " (" unit ")")))))
                      series-list)
         body (map (fn [row]
                     (into [(csv-cell (:x row))]
                           (map (fn [s] (csv-cell (cell-value row (:key s)))))
                           series-list))
                   export-rows)
         page (clamp-page page rows card)
         meta (cond-> [(str "# 标题: " (title card))
                       (str "# 来源: " (source card))
                       (str "# 筛选: " (if (seq (active-filter-labels filter-spec))
                                         (str/join "；" (active-filter-labels filter-spec))
                                         "无"))
                       (str "# 范围: " (if current-page?
                                         (str "当前页 " (inc page)
                                              " / " (page-count rows card))
                                         "全部筛选结果"))]
                (illustrative? card) (conj "# 示意：非真实测量"))]
     (str (str/join "\n" meta) "\n"
          (str/join "," header) "\n"
          (str/join "\n" (map #(str/join "," %) body))
          (when (seq body) "\n")))))

(defn chart-domain
  "Numeric domain for the visible values.  Bar charts include zero; line
  charts use the data range.  Missing values are never treated as zero."
  [card rows]
  (let [series-list (series card)
        values (vec (for [row rows
                          s series-list
                          :let [v (cell-value row (:key s))]
                          :when (not (missing? v))
                          :let [n (parse-number v)]
                          :when (some? n)]
                      n))]
    (when (seq values)
      (let [lo (apply min values)
            hi (apply max values)
            lo (if (= :bar (chart-type card)) (min 0 lo) lo)
            hi (if (= :bar (chart-type card)) (max 0 hi) hi)
            span (- hi lo)
            span (if (zero? span) (max 1 (abs-number hi)) span)
            pad (* span 0.08)]
        [(- lo pad) (+ hi pad)]))))

(defn y-ticks
  [domain]
  (when domain
    (let [[lo hi] domain
          step (/ (- hi lo) 4.0)]
      (mapv #(+ lo (* step %)) (range 5)))))

(defn series-points
  "Points for a line chart, skipping missing values.  X positions are the
  visible row indexes; the view maps them to pixels."
  [card rows]
  (mapv (fn [s]
          {:key (:key s)
           :label (:label s)
           :unit (:unit s)
           :color (:color s)
           :points (vec (keep-indexed
                         (fn [index row]
                           (let [v (cell-value row (:key s))]
                             (when-not (missing? v)
                               {:index index
                                :x (:x row)
                                :value (parse-number v)})))
                         rows))})
        (series card)))

(defn chart-text
  "Readable text fallback/copy for a chart card."
  [card rows filter-spec]
  (let [{:keys [total visible missing stats]} (summary card rows filter-spec)]
    (str (title card) "\n"
         (when-let [p (not-empty (str (purpose card)))] (str p "\n"))
         (str "横轴：" (x-label card) "；纵轴：" (y-label card)
              (when-let [unit (chart-unit-label card)] (str "（" unit "）")) "\n")
         (when-let [range (time-range card)] (str "时间范围：" range "\n"))
         (str "来源：" (provenance-label card)
              (when-let [s (not-empty (str (source card)))] (str " · " s)) "\n")
         (str "数据：" total " 行，当前 " visible " 行，缺失 " missing " 个")
         (when (seq (active-filter-labels filter-spec))
           (str "；筛选：" (str/join "；" (active-filter-labels filter-spec))))
         "\n"
         (str/join "\n"
                   (map (fn [{:keys [label unit min max average]}]
                          (str "- " label (when unit (str "（" unit "）"))
                               "：最小 " (if (some? min) (fmt-number min) "无")
                               "，最大 " (if (some? max) (fmt-number max) "无")
                               "，平均 " (if (some? average) (fmt-number average) "无")))
                        stats)))))
