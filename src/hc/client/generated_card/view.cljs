(ns hc.client.generated-card.view
  "React/Framework7-compatible surface for generated interactive cards.

  UX-20 rules made structural here:

  - the card is labelled “生成的交互内容” and never borrows system settings,
    permissions, or account chrome;
  - editing, filtering, sorting, paging, chart inspection, local save, and
    reset never call a model or the network;
  - only the explicit “发送给助手” action invokes the host callback, after a
    visible preview of the exact visible, non-secret fields;
  - a numeric chart states axes, units, time range, source, missing values,
    and has a data table using the same filter and sort;
  - form state survives theme, resize, full-screen, and re-render because it
    is local component state, not a regenerated server snapshot."
  (:require ["react" :as react]
            [clojure.string :as str]
            [com.fulcrologic.fulcro.dom :as dom]
            [hc.client.generated-card.model :as model]))

;;; ---------------------------------------------------------------------------
;;; Helpers

(defn- style [m] (clj->js m))

(defn- class-names [& values]
  (str/join " " (remove nil? values)))

(defn- call! [callbacks key value]
  (when-let [callback (get callbacks key)]
    (when (fn? callback) (callback value))))

(defn- clipboard!
  [text]
  (when (and text (pos? (count text)) (exists? js/navigator))
    (try
      (if-let [clipboard (.-clipboard js/navigator)]
        (.writeText clipboard text)
        (when (exists? js/document)
          (let [node (.createElement js/document "textarea")]
            (set! (.-value node) text)
            (set! (.-position (.-style node)) "fixed")
            (set! (.-opacity (.-style node)) "0")
            (.appendChild js/document.body node)
            (.select node)
            (.execCommand js/document "copy")
            (.removeChild js/document.body node))))
      (catch :default _ nil))))

(defn- download-text!
  [text filename mime]
  (when (and text (exists? js/document) (exists? js/Blob) (exists? js/URL))
    (try
      (let [blob (js/Blob. #js [text] #js {:type (or mime "text/plain;charset=utf-8")})
            url (.createObjectURL js/URL blob)
            anchor (.createElement js/document "a")]
        (set! (.-href anchor) url)
        (set! (.-download anchor) filename)
        (.appendChild js/document.body anchor)
        (.click anchor)
        (.removeChild js/document.body anchor)
        (js/setTimeout #(.revokeObjectURL js/URL url) 1000))
      (catch :default _ nil))))

(defn- focus-element!
  [id]
  (when (exists? js/document)
    (when-let [node (.getElementById js/document id)]
      (try
        (.focus node)
        (catch :default _ nil)))))

(defn- card-dom-id
  [card suffix]
  (str "hc-gencard-" (or (model/card-id card) "card") "-" suffix))

(defn- fmt-value
  [value]
  (cond
    (model/missing? value) "缺失"
    (number? value) (str value)
    :else (str value)))

(defn- format-tick
  [value]
  (let [rounded (js/Math.round value)]
    (if (== rounded value)
      (str rounded)
      (.toFixed value 2))))

(defn- xml-escape
  [text]
  (-> (str text)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- pct
  [value total]
  (if (pos? total) (* 100.0 (/ (double value) total)) 0))

;;; ---------------------------------------------------------------------------
;;; CSS

(def ^:private css
  ".hc-gencard{margin:10px 0;border:1px solid var(--f7-list-item-border-color,#d8d8dc);border-radius:14px;background:var(--f7-card-bg-color,#fff);color:var(--f7-text-color,#111);overflow:hidden}
.hc-gencard *{box-sizing:border-box}
.hc-gencard-head{display:flex;align-items:flex-start;gap:12px;flex-wrap:wrap;padding:12px 14px;border-bottom:1px solid var(--f7-list-item-border-color,#e4e4e8);background:linear-gradient(180deg,var(--f7-card-bg-color,#fff),var(--f7-page-bg-color,#fafafa))}
.hc-gencard-head-main{flex:1;min-width:220px}
.hc-gencard-badges{display:flex;gap:6px;flex-wrap:wrap;margin:0 0 4px}
.hc-gencard-badge{display:inline-flex;align-items:center;min-height:24px;padding:2px 8px;border-radius:999px;font-size:.74rem;font-weight:600;background:#e3f2fd;color:#0d3c6e;border:1px solid #90caf9}
.hc-gencard-badge-warn{background:#fff3e0;color:#6d3b00;border-color:#ffb74d}
.hc-gencard-badge-muted{background:#f1f3f4;color:#444;border-color:#d0d4d8}
.hc-gencard h3{margin:0;font-size:1.02rem;line-height:1.35}
.hc-gencard-purpose{margin:4px 0 0;font-size:.86rem;color:var(--f7-label-color,#555);line-height:1.45}
.hc-gencard-meta{display:flex;gap:10px;flex-wrap:wrap;margin-top:4px;font-size:.76rem;color:var(--f7-label-color,#555)}
.hc-gencard-actions{display:flex;gap:6px;flex-wrap:wrap;align-items:center}
.hc-gencard-body{padding:12px 14px}
.hc-gencard-note{margin:8px 0;padding:8px 10px;border-radius:10px;background:#f1f8ff;border:1px solid #b3d7ff;color:#0d3c6e;font-size:.84rem;line-height:1.45}
.hc-gencard-note-warn{background:#fff8e1;border-color:#ffcc80;color:#6d3b00}
.hc-gencard-form{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px}
.hc-gencard-field{display:flex;flex-direction:column;gap:4px;min-width:0}
.hc-gencard-field-wide{grid-column:1/-1}
.hc-gencard-field label{font-size:.86rem;font-weight:600;line-height:1.35}
.hc-gencard-required{color:#c62828;margin-left:3px}
.hc-gencard-unit{font-weight:400;color:var(--f7-label-color,#555)}
.hc-gencard-field input,.hc-gencard-field select,.hc-gencard-field textarea{width:100%;min-height:44px;padding:8px 10px;border:1px solid var(--f7-list-item-border-color,#c8c8cc);border-radius:10px;font:inherit;background:var(--f7-card-bg-color,#fff);color:inherit}
.hc-gencard-field textarea{min-height:92px;resize:vertical}
.hc-gencard-field input[type=range]{min-height:44px;padding:0}
.hc-gencard-field input:focus,.hc-gencard-field select:focus,.hc-gencard-field textarea:focus{outline:3px solid var(--f7-color-primary,#0a84ff);outline-offset:1px}
.hc-gencard-field[data-invalid=true] input,.hc-gencard-field[data-invalid=true] select,.hc-gencard-field[data-invalid=true] textarea{border-color:#c62828}
.hc-gencard-help{font-size:.78rem;color:var(--f7-label-color,#555);line-height:1.4}
.hc-gencard-error{font-size:.78rem;color:#b71c1c;font-weight:600;line-height:1.4}
.hc-gencard-range-row{display:flex;align-items:center;gap:10px}
.hc-gencard-range-row output{min-width:72px;font-weight:600}
.hc-gencard-check{display:flex;align-items:center;gap:8px;min-height:44px;padding:6px 8px;border:1px solid var(--f7-list-item-border-color,#d8d8dc);border-radius:10px}
.hc-gencard-check input{width:22px;height:22px;min-height:0}
.hc-gencard-actions-row{display:flex;gap:8px;flex-wrap:wrap;align-items:center;margin-top:12px}
.hc-gencard-button{min-height:44px;min-width:44px;padding:8px 14px;border-radius:10px;border:1px solid var(--f7-list-item-border-color,#c8c8cc);background:var(--f7-card-bg-color,#fff);color:inherit;font:inherit;cursor:pointer}
.hc-gencard-button-primary{background:var(--f7-color-primary,#0a84ff);border-color:var(--f7-color-primary,#0a84ff);color:#fff}
.hc-gencard-button[disabled],.hc-gencard-button[aria-disabled=true]{opacity:.55;cursor:not-allowed}
.hc-gencard-preview{margin:10px 0;border:1px solid var(--f7-list-item-border-color,#d8d8dc);border-radius:10px;padding:10px;background:var(--f7-page-bg-color,#fafafa)}
.hc-gencard-preview h4{margin:0 0 6px;font-size:.9rem}
.hc-gencard-preview ul{margin:0;padding-left:18px}
.hc-gencard-preview li{margin:3px 0;font-size:.84rem;line-height:1.4}
.hc-gencard-preview .hc-gencard-muted{color:var(--f7-label-color,#555)}
.hc-gencard-chart-toolbar{display:flex;gap:8px;flex-wrap:wrap;align-items:flex-end;margin-bottom:10px}
.hc-gencard-chart-toolbar label{display:flex;flex-direction:column;gap:3px;font-size:.8rem;font-weight:600;color:var(--f7-label-color,#444)}
.hc-gencard-chart-toolbar select,.hc-gencard-chart-toolbar input{min-height:44px;padding:6px 8px;border:1px solid var(--f7-list-item-border-color,#c8c8cc);border-radius:10px;font:inherit;background:var(--f7-card-bg-color,#fff);color:inherit}
.hc-gencard-filter-chips{display:flex;gap:6px;flex-wrap:wrap;margin:4px 0 8px}
.hc-gencard-chip{display:inline-flex;align-items:center;min-height:28px;padding:2px 8px;border-radius:999px;background:#eef4ff;border:1px solid #c7dbff;color:#123e78;font-size:.76rem}
.hc-gencard-chart{position:relative;border:1px solid var(--f7-list-item-border-color,#d8d8dc);border-radius:12px;background:var(--f7-card-bg-color,#fff);overflow:auto}
.hc-gencard-chart svg{display:block;width:100%;height:auto;min-width:520px}
.hc-gencard-chart-empty{padding:24px 12px;text-align:center;color:var(--f7-label-color,#555);font-size:.88rem}
.hc-gencard-legend{display:flex;gap:12px;flex-wrap:wrap;margin:8px 0;font-size:.8rem}
.hc-gencard-legend span{display:inline-flex;align-items:center;gap:5px}
.hc-gencard-swatch{display:inline-block;width:12px;height:12px;border-radius:3px;border:1px solid rgba(0,0,0,.15)}
.hc-gencard-readout{margin:6px 0;padding:7px 9px;border-radius:8px;background:#f1f3f4;font-size:.82rem;min-height:32px}
.hc-gencard-summary{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:8px;margin:8px 0}
.hc-gencard-stat{border:1px solid var(--f7-list-item-border-color,#e0e0e4);border-radius:10px;padding:7px 9px;background:var(--f7-page-bg-color,#fafafa);font-size:.8rem;line-height:1.4}
.hc-gencard-stat strong{display:block;font-size:.9rem}
.hc-gencard-table-wrap{overflow-x:auto;margin-top:10px;border:1px solid var(--f7-list-item-border-color,#d8d8dc);border-radius:10px}
.hc-gencard table{border-collapse:collapse;min-width:100%;font-size:.82rem}
.hc-gencard th,.hc-gencard td{border-bottom:1px solid var(--f7-list-item-border-color,#e4e4e8);padding:7px 9px;text-align:left;vertical-align:top;white-space:nowrap}
.hc-gencard th{background:var(--f7-page-bg-color,#fafafa);font-weight:600}
.hc-gencard th button{min-height:32px;padding:2px 4px;border:0;background:transparent;color:inherit;font:inherit;font-weight:600;cursor:pointer;text-align:left}
.hc-gencard th button:focus{outline:3px solid var(--f7-color-primary,#0a84ff);outline-offset:1px}
.hc-gencard td.hc-gencard-missing{color:#9a6700;font-style:italic}
.hc-gencard-pager{display:flex;gap:8px;flex-wrap:wrap;align-items:center;justify-content:space-between;margin-top:8px;font-size:.8rem;color:var(--f7-label-color,#555)}
.hc-gencard-export{display:flex;gap:8px;flex-wrap:wrap;margin-top:10px}
.hc-gencard-fallback{margin-top:10px}
.hc-gencard-fallback pre{max-height:220px;overflow:auto;padding:10px;border:1px solid var(--f7-list-item-border-color,#d8d8dc);border-radius:10px;background:var(--f7-page-bg-color,#fafafa);white-space:pre-wrap;overflow-wrap:anywhere}
.hc-gencard-fullscreen{position:fixed;inset:0;z-index:12000;margin:0;border-radius:0;overflow:auto;max-height:100vh}
.hc-gencard-fullscreen .hc-gencard-body{max-width:1100px;margin:0 auto}
.hc-gencard-status{font-size:.8rem;color:var(--f7-label-color,#555)}
.hc-gencard-status[data-tone=error]{color:#b71c1c}
.hc-gencard-status[data-tone=ok]{color:#1b5e20}
.hc-visually-hidden{position:absolute!important;width:1px;height:1px;padding:0;margin:-1px;overflow:hidden;clip:rect(0,0,0,0);white-space:nowrap;border:0}
@media (max-width:699px){.hc-gencard-form{grid-template-columns:1fr}.hc-gencard-head{padding:10px}.hc-gencard-body{padding:10px}.hc-gencard-chart svg{min-width:460px}}
@media (prefers-reduced-motion:reduce){.hc-gencard *{scroll-behavior:auto!important;transition:none!important;animation:none!important}}
@media print{.hc-gencard{break-inside:avoid;border-color:#999}.hc-gencard-actions,.hc-gencard-export,.hc-gencard-pager{display:none!important}.hc-gencard-fullscreen{position:static;overflow:visible}}")

;;; ---------------------------------------------------------------------------
;;; Chart layout (shared by React SVG and SVG export)

(def ^:private chart-width 680)
(def ^:private chart-height 300)
(def ^:private chart-pad {:left 56 :right 18 :top 32 :bottom 44})

(declare partition-by-index)

(defn- chart-layout
  [card rows]
  (let [domain (model/chart-domain card rows)
        series (model/series card)
        x-values (vec (distinct (map :x rows)))
        x-count (count x-values)
        plot-w (- chart-width (:left chart-pad) (:right chart-pad))
        plot-h (- chart-height (:top chart-pad) (:bottom chart-pad))
        y (fn [value]
            (let [[lo hi] domain]
              (+ (:top chart-pad)
                 (* plot-h (- 1 (/ (- (double value) lo) (- hi lo)))))))
        x-center (fn [index]
                   (+ (:left chart-pad)
                      (* (/ plot-w (max 1 x-count)) (+ index 0.5))))
        group-w (/ plot-w (max 1 x-count))
        bar-w (max 3 (/ (* group-w 0.62) (max 1 (count series))))
        baseline (when domain (y 0))
        bars (vec
              (for [[xi x] (map-indexed vector x-values)
                    [si s] (map-indexed vector series)
                    :let [row (some #(when (= (str (:x %)) (str x)) %) rows)
                          value (when row (model/cell-value row (:key s)))]
                    :when (and row (not (model/missing? value)) (some? (model/parse-number value)))]
                {:key (str xi "-" si)
                 :x x
                 :series s
                 :value (model/parse-number value)
                 :x0 (+ (:left chart-pad)
                        (* xi group-w)
                        (/ (- group-w (* bar-w (count series))) 2)
                        (* si bar-w))
                 :y0 (if (neg? (model/parse-number value)) baseline (y (model/parse-number value)))
                 :y1 (if (neg? (model/parse-number value)) (y (model/parse-number value)) baseline)
                 :width bar-w}))
        lines (vec
               (for [s series]
                 {:series s
                  :groups (->> (model/series-points card rows)
                               (filter #(= (:key %) (:key s)))
                               first
                               :points
                               (partition-by-index))}))]
    {:width chart-width
     :height chart-height
     :plot {:x (:left chart-pad)
            :y (:top chart-pad)
            :width plot-w
            :height plot-h}
     :domain domain
     :x-values x-values
     :x-center x-center
     :x-ticks (mapv (fn [[index x]] {:index index :x x :cx (x-center index)})
                    (map-indexed vector x-values))
     :y-ticks (mapv (fn [value] {:value value :y (y value)}) (model/y-ticks domain))
     :baseline baseline
     :y y
     :bars bars
     :lines lines
     :series series}))

(defn- partition-by-index
  "Group consecutive point indexes so a missing value breaks a line."
  [points]
  (reduce (fn [groups {:keys [index] :as point}]
            (let [last-group (peek groups)
                  last-index (some-> last-group peek :index)]
              (if (and last-group (= (inc (or last-index -2)) index))
                (conj (pop groups) (conj last-group point))
                (conj groups [point]))))
          []
          points))

;;; ---------------------------------------------------------------------------
;;; Chart SVG

(defn- axis-lines
  [{:keys [plot y-ticks x-ticks baseline]}]
  (dom/g nil
         (for [{:keys [value y]} y-ticks]
           (dom/g {:key (str "y-" value)}
                  (dom/line {:x1 (:x plot) :x2 (+ (:x plot) (:width plot))
                             :y1 y :y2 y
                             :stroke "#e4e4e8" :strokeWidth 1})
                  (dom/text {:x (- (:x plot) 8) :y (+ y 4)
                             :textAnchor "end" :fontSize 11 :fill "#555"}
                            (format-tick value))))
         (when baseline
           (dom/line {:x1 (:x plot) :x2 (+ (:x plot) (:width plot))
                      :y1 baseline :y2 baseline
                      :stroke "#9aa0a6" :strokeWidth 1.5}))
         (for [{:keys [cx x]} x-ticks]
           (dom/text {:key (str "x-" x) :x cx
                      :y (+ (:y plot) (:height plot) 18)
                      :textAnchor "middle" :fontSize 11 :fill "#555"}
                     (str x)))))

(defn- bar-elements
  [card bars selected set-selected!]
  (for [{:keys [key x series value x0 y0 y1 width]} bars]
    (dom/rect {:key key
               :x x0 :y (min y0 y1) :width width
               :height (max 2 (js/Math.abs (- y1 y0)))
               :fill (:color series)
               :tabIndex 0
               :role "img"
               :aria-label (str (:label series) " " x "：" value
                                (when-let [unit (:unit series)] (str " " unit)))
               :onMouseEnter #(set-selected! {:x x :series series :value value})
               :onFocus #(set-selected! {:x x :series series :value value})
               :onTouchStart #(set-selected! {:x x :series series :value value})
               :style (style {:cursor "pointer"})}
              (dom/title nil (str (:label series) " " x "：" value
                                  (when-let [unit (:unit series)] (str " " unit)))))))

(defn- line-elements
  [layout lines selected set-selected!]
  (for [{:keys [series groups]} lines
        [gi group] (map-indexed vector groups)]
    (dom/g {:key (str (:key series) "-" gi)}
           (when (> (count group) 1)
             (dom/polyline {:points (str/join " "
                                              (map (fn [{:keys [index value]}]
                                                     (str ((:x-center layout) index) ","
                                                          ((:y layout) value)))
                                                   group))
                            :fill "none"
                            :stroke (:color series)
                            :strokeWidth 2.5
                            :strokeLinejoin "round"
                            :strokeLinecap "round"}))
           (for [{:keys [index x value]} group]
             (dom/circle {:key (str (:key series) "-" index)
                          :cx ((:x-center layout) index)
                          :cy ((:y layout) value)
                          :r 5
                          :fill (:color series)
                          :stroke "#fff" :strokeWidth 2
                          :tabIndex 0
                          :role "img"
                          :aria-label (str (:label series) " " x "：" value
                                           (when-let [unit (:unit series)] (str " " unit)))
                          :onMouseEnter #(set-selected! {:x x :series series :value value})
                          :onFocus #(set-selected! {:x x :series series :value value})
                          :onTouchStart #(set-selected! {:x x :series series :value value})
                          :style (style {:cursor "pointer"})}
                         (dom/title nil (str (:label series) " " x "：" value
                                             (when-let [unit (:unit series)] (str " " unit)))))))))

(defn- chart-svg
  [card rows selected set-selected!]
  (let [layout (chart-layout card rows)]
    (if-not (:domain layout)
      (dom/div {:className "hc-gencard-chart-empty"} "当前筛选没有可绘制的数值。")
      (dom/div {:className "hc-gencard-chart"}
               (dom/svg {:viewBox (str "0 0 " (:width layout) " " (:height layout))
                         :role "img"
                         :aria-label (str (model/title card) "，"
                                          (model/x-label card) "与"
                                          (model/y-label card)
                                          (when-let [unit (model/chart-unit-label card)]
                                            (str "，单位 " unit)))}
                        (axis-lines layout)
                        (if (= :line (model/chart-type card))
                          (line-elements layout (:lines layout) selected set-selected!)
                          (bar-elements card (:bars layout) selected set-selected!))
                        (dom/text {:x (+ (:x (:plot layout)) (/ (:width (:plot layout)) 2))
                                   :y (- (:height layout) 6)
                                   :textAnchor "middle" :fontSize 11 :fill "#555"}
                                  (model/x-label card))
                        (dom/text {:x 14
                                   :y (+ (:y (:plot layout)) (/ (:height (:plot layout)) 2))
                                   :textAnchor "middle" :fontSize 11 :fill "#555"
                                   :transform (str "rotate(-90 14 "
                                                   (+ (:y (:plot layout)) (/ (:height (:plot layout)) 2))
                                                   ")")}
                                  (str (model/y-label card)
                                       (when-let [unit (model/chart-unit-label card)]
                                         (str "（" unit "）")))))))
      ))

;;; ---------------------------------------------------------------------------
;;; Form

(defn- field-dom-id
  [card field]
  (str (card-dom-id card (name (model/field-name field))) "-input"))

(defn- field-help-id
  [card field]
  (str (field-dom-id card field) "-help"))

(defn- field-error-id
  [card field]
  (str (field-dom-id card field) "-error"))

(defn- field-control
  [card field value on-change! disabled?]
  (let [id (field-dom-id card field)
        type (model/field-type field)
        common {:id id
                :name (name (model/field-name field))
                :value (or value "")
                :disabled disabled?
                :onChange on-change!
                :aria-invalid (boolean (:error field))
                :aria-describedby (str (field-help-id card field)
                                       (when (:error field) (str " " (field-error-id card field))))}]
    (case type
      :textarea
      (dom/textarea (assoc common :rows 3))

      :select
      (dom/select common
                  (for [option (model/field-options field)]
                    (dom/option {:key (str (:value option)) :value (:value option)}
                                (:label option))))

      :boolean
      (dom/div {:className "hc-gencard-check"}
               (dom/input (assoc (dissoc common :value)
                                 :type "checkbox"
                                 :checked (boolean value)))
               (dom/label {:htmlFor id}
                          (model/field-label field)
                          (when (model/field-required? field)
                            (dom/span {:className "hc-gencard-required" :aria-hidden "true"} "*"))))

      :range
      (dom/div {:className "hc-gencard-range-row"}
               (dom/input (assoc common
                                 :type "range"
                                 :min (or (:min field) 0)
                                 :max (or (:max field) 100)
                                 :step (or (:step field) 1)
                                 :value (or value (or (:min field) 0))))
               (dom/output {:htmlFor id}
                           (str (model/display-value field value))))

      :number
      (dom/input (assoc common
                        :type "number"
                        :min (:min field)
                        :max (:max field)
                        :step (or (:step field) "any")
                        :inputMode "decimal"))

      :date
      (dom/input (assoc common :type "date"))

      :secret
      (dom/input (assoc common :type "password" :autoComplete "off"))

      (dom/input (assoc common :type "text")))))

(defn- field-view
  [card field values errors on-change! disabled?]
  (let [name (model/field-name field)
        value (get values name)
        error (get errors name)
        type (model/field-type field)
        wide? (contains? #{:textarea :secret} type)]
    (dom/div {:className (class-names "hc-gencard-field"
                                      (when wide? "hc-gencard-field-wide"))
              :data-invalid (boolean error)
              :key (str name)}
             (when-not (= :boolean type)
               (dom/label {:htmlFor (field-dom-id card field)}
                          (model/field-label field)
                          (when (model/field-required? field)
                            (dom/span {:className "hc-gencard-required" :aria-hidden "true"} "*"))
                          (when-let [unit (model/field-unit field)]
                            (dom/span {:className "hc-gencard-unit"} (str " · " unit)))))
             (field-control card (assoc field :error error) value (on-change! field) disabled?)
             (when (model/field-secret? field)
               (dom/div {:className "hc-gencard-help"}
                        "秘密字段只保留在本卡，不会发送给助手。"))
             (when-let [help (model/field-help field)]
               (dom/div {:className "hc-gencard-help" :id (field-help-id card field)}
                        help))
             (when error
               (dom/div {:className "hc-gencard-error"
                         :id (field-error-id card field)
                         :role "alert"}
                        error)))))

(defn- preview-view
  [card values]
  (let [{:keys [rows secret-fields hidden-fields]} (model/submission-preview card values)]
    (dom/div {:className "hc-gencard-preview"}
             (dom/h4 nil "预览将发送内容")
             (when (seq rows)
               (dom/ul nil
                       (for [{:keys [name label display secret?]} rows]
                         (dom/li {:key (str name)}
                                 (dom/strong nil (str label "："))
                                 (dom/span {:className (when secret? "hc-gencard-muted")}
                                           display)))))
             (when (seq secret-fields)
               (dom/div {:className "hc-gencard-help"}
                        (str "秘密字段不会发送给助手：" (str/join "、" (map name secret-fields)))))
             (when (seq hidden-fields)
               (dom/div {:className "hc-gencard-help"}
                        (str "隐藏字段不会提交：" (str/join "、" (map name hidden-fields)))))
             (when (empty? rows)
               (dom/div {:className "hc-gencard-help"} "当前没有可见字段。")))))

(defn- form-card
  [card callbacks values set-values!]
  (let [[errors set-errors!] (react/useState {})
        [status set-status!] (react/useState :editing)
        [preview? set-preview?] (react/useState false)
        [local-saved? set-local-saved!] (react/useState false)
        [submitted set-submitted!] (react/useState nil)
        disabled? (not (model/ready? card))
        fields (model/visible-fields card values)
        on-change! (fn [field]
                     (fn [event]
                       (let [target (.-target event)
                             raw (.-value target)
                             value (case (model/field-type field)
                                     :boolean (.-checked target)
                                     :select (or (some (fn [option]
                                                         (when (= (str (:value option)) (str raw))
                                                           (:value option)))
                                                       (model/field-options field))
                                                 raw)
                                     raw)]
                         (set-values! (fn [current]
                                        (assoc current (model/field-name field) value)))
                         (set-errors! (fn [current]
                                        (dissoc current (model/field-name field))))
                         (set-local-saved! false)
                         (set-status! :editing)
                         (set-submitted! nil))))
        save-local! (fn [_]
                      (let [secret-fields (->> (model/fields card)
                                               (filter model/field-secret?)
                                               (mapv model/field-name))
                            safe-values (apply dissoc values secret-fields)]
                        (set-local-saved! true)
                        (call! callbacks :on-local-save!
                               {:card-id (model/card-id card)
                                :values safe-values
                                :excluded-secret-fields secret-fields})))
        submit! (fn [_]
                  (let [check (model/validate card values)]
                    (if-not (:ok? check)
                      (do (set-errors! (:errors check))
                          (set-status! :invalid)
                          (when-let [first-error (:first-error check)]
                            (focus-element! (field-dom-id card {:name first-error}))))
                      (let [payload (model/submission card values)]
                        (set-errors! {})
                        (set-submitted! payload)
                        (set-status! :submitted)
                        (call! callbacks :on-submit! payload)))))
        can-send? (and (model/ready? card)
                       (fn? (:on-submit! callbacks))
                       (not= :submitted status))
        send-reason (cond
                      (not (model/ready? card)) "卡片尚未完成生成，不能发送"
                      (not (fn? (:on-submit! callbacks))) "当前卡片只支持本地填写"
                      (= :submitted status) "已发送给助手"
                      :else nil)]
    (dom/div nil
             (dom/style {:dangerouslySetInnerHTML #js {:__html css}})
             (when-let [label (model/state-label card)]
               (dom/div {:className "hc-gencard-note hc-gencard-note-warn"} label))
             (when (seq fields)
               (dom/div {:className "hc-gencard-form"}
                        (for [field fields]
                          (field-view card field values errors on-change! disabled?))))
             (when preview?
               (preview-view card values))
             (when local-saved?
               (dom/div {:className "hc-gencard-note"} "已保存到本卡（未发送给助手）。"))
             (when submitted
               (dom/div {:className "hc-gencard-note"}
                        (str "已提交给助手：" (count (:fields submitted)) " 个字段。"
                             (when (seq (:excluded-secret-fields submitted))
                               (str " 已排除秘密字段 "
                                    (str/join "、" (map name (:excluded-secret-fields submitted))))))))
             (when (= :invalid status)
               (dom/div {:className "hc-gencard-status" :data-tone "error" :role "alert"}
                        "请修正标出的字段后再提交。"))
             (dom/div {:className "hc-gencard-actions-row"}
                      (dom/button {:className "hc-gencard-button"
                                   :type "button"
                                   :onClick #(set-preview? (not preview?))}
                                  (if preview? "隐藏预览" "预览将发送内容"))
                      (dom/button {:className "hc-gencard-button"
                                   :type "button"
                                   :onClick save-local!}
                                  (get-in card [:form :local-label] "仅保存到本卡"))
                      (dom/button {:className "hc-gencard-button hc-gencard-button-primary"
                                   :type "button"
                                   :disabled (not can-send?)
                                   :aria-disabled (not can-send?)
                                   :onClick submit!}
                                  (get-in card [:form :submit-label] "发送给助手")))
             (when send-reason
               (dom/div {:className "hc-gencard-help"} send-reason))
             (dom/div {:className "hc-gencard-help"}
                      "本地填写、预览、保存不会发起模型请求；“发送给助手”只在点击后把上方预览的字段交给宿主。")
             (when (and (= :submitted status) submitted)
               (dom/button {:className "hc-gencard-button"
                            :type "button"
                            :onClick #(do (set-status! :editing)
                                          (set-submitted! nil))}
                           "修改后可再次发送")))))

;;; ---------------------------------------------------------------------------
;;; Chart card

(defn- sort-indicator
  [sort key]
  (when (= (:key sort) key)
    (if (= :desc (:direction sort)) " ▼" " ▲")))

(defn- table-view
  [card rows columns sort set-sort! page set-page! filter]
  (let [sorted (model/sort-rows rows columns sort)
        paged (model/page-rows sorted page card)
        info (model/page-info sorted page card)]
    (dom/div nil
             (dom/div {:className "hc-gencard-table-wrap"}
                      (dom/table nil
                                 (dom/caption {:className "hc-visually-hidden"}
                                              (str (model/title card) "数据表"))
                                 (dom/thead nil
                                            (dom/tr nil
                                                    (for [column columns]
                                                      (dom/th {:key (str (:key column))
                                                               :aria-sort (cond
                                                                            (not= (:key sort) (:key column)) "none"
                                                                            (= :desc (:direction sort)) "descending"
                                                                            :else "ascending")}
                                                              (dom/button {:type "button"
                                                                           :onClick (fn [_]
                                                                                      (set-sort! (if (and (= (:key sort) (:key column))
                                                                                                          (= :asc (:direction sort)))
                                                                                                   {:key (:key column) :direction :desc}
                                                                                                   {:key (:key column) :direction :asc})))}
                                                                          (str (:label column)
                                                                               (when-let [unit (:unit column)]
                                                                                 (str " (" unit ")"))
                                                                               (sort-indicator sort (:key column))))))))
                                 (dom/tbody nil
                                            (for [[index row] (map-indexed vector paged)]
                                              (dom/tr {:key (str "row-" index)}
                                                      (for [column columns]
                                                        (let [value (if (= :x (:key column))
                                                                      (:x row)
                                                                      (model/cell-value row (:key column)))
                                                              missing? (and (not= :x (:key column))
                                                                            (model/missing? value))]
                                                          (dom/td {:key (str (:key column) "-" index)
                                                                   :className (when missing? "hc-gencard-missing")}
                                                                  (if missing? "缺失" (fmt-value value))))))))))
             (dom/div {:className "hc-gencard-pager"}
                      (dom/span nil
                                (str "显示 " (:start info) "–" (:end info)
                                     " / 共 " (:total info) " 行"
                                     (when-not (:complete? info) "（还有更多）")))
                      (dom/div {:className "hc-gencard-actions"}
                               (dom/button {:className "hc-gencard-button"
                                            :type "button"
                                            :disabled (zero? (:page info))
                                            :onClick #(set-page! (max 0 (dec (:page info))))}
                                           "上一页")
                               (dom/span nil (str "第 " (inc (:page info)) " / " (:page-count info) " 页"))
                               (dom/button {:className "hc-gencard-button"
                                            :type "button"
                                            :disabled (>= (inc (:page info)) (:page-count info))
                                            :onClick #(set-page! (inc (:page info)))}
                                           "下一页"))))))

(defn- svg-export-string
  [card rows filter]
  (let [layout (chart-layout card rows)
        [lo hi] (:domain layout)
        title (model/title card)
        source (str (model/provenance-label card)
                    (when-let [s (not-empty (str (model/source card)))] (str " · " s)))
        filters (model/active-filter-labels filter)
        header (str title " | " (model/x-label card) " / " (model/y-label card)
                    (when-let [unit (model/chart-unit-label card)] (str " (" unit ")"))
                    " | " source
                    (when (seq filters) (str " | 筛选: " (str/join "；" filters)))
                    (when-let [range (model/time-range card)] (str " | 时间范围: " range))
                    (when (model/illustrative? card) " | 示意：非真实测量"))]
    (if-not (:domain layout)
      (str "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"680\" height=\"120\">"
           "<text x=\"12\" y=\"40\" font-family=\"sans-serif\" font-size=\"14\">"
           (xml-escape (str header " — 没有可绘制数据"))
           "</text></svg>")
      (let [plot (:plot layout)
            bars (:bars layout)
            lines (:lines layout)]
        (str "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"" (:width layout)
             "\" height=\"" (:height layout) "\" viewBox=\"0 0 " (:width layout) " " (:height layout) "\">"
             "<title>" (xml-escape header) "</title>"
             "<rect width=\"100%\" height=\"100%\" fill=\"white\"/>"
             "<text x=\"12\" y=\"16\" font-family=\"sans-serif\" font-size=\"13\" font-weight=\"600\" fill=\"#111\">"
             (xml-escape header) "</text>"
             (apply str
                    (for [{:keys [value y]} (:y-ticks layout)]
                      (str "<line x1=\"" (:x plot) "\" x2=\"" (+ (:x plot) (:width plot))
                           "\" y1=\"" y "\" y2=\"" y "\" stroke=\"#e4e4e8\"/>"
                           "<text x=\"" (- (:x plot) 8) "\" y=\"" (+ y 4)
                           "\" text-anchor=\"end\" font-size=\"11\" fill=\"#555\">"
                           (format-tick value) "</text>")))
             (when-let [baseline (:baseline layout)]
               (str "<line x1=\"" (:x plot) "\" x2=\"" (+ (:x plot) (:width plot))
                    "\" y1=\"" baseline "\" y2=\"" baseline "\" stroke=\"#9aa0a6\"/>"))
             (apply str
                    (for [{:keys [cx x]} (:x-ticks layout)]
                      (str "<text x=\"" cx "\" y=\"" (+ (:y plot) (:height plot) 18)
                           "\" text-anchor=\"middle\" font-size=\"11\" fill=\"#555\">"
                           (xml-escape x) "</text>")))
             (if (= :line (model/chart-type card))
               (apply str
                      (for [{:keys [series groups]} lines
                            group groups]
                        (str (when (> (count group) 1)
                               (str "<polyline fill=\"none\" stroke=\"" (:color series)
                                    "\" stroke-width=\"2.5\" points=\""
                                    (str/join " " (map (fn [{:keys [index value]}]
                                                         (str ((:x-center layout) index) ","
                                                              ((:y layout) value)))
                                                       group))
                                    "\"/>"))
                             (apply str
                                    (for [{:keys [index value]} group]
                                      (str "<circle cx=\"" ((:x-center layout) index)
                                           "\" cy=\"" ((:y layout) value)
                                           "\" r=\"4\" fill=\"" (:color series) "\"/>"))))))
               (apply str
                      (for [{:keys [x0 y0 y1 width series value x]} bars]
                        (str "<rect x=\"" x0 "\" y=\"" (min y0 y1) "\" width=\"" width
                             "\" height=\"" (max 2 (js/Math.abs (- y1 y0)))
                             "\" fill=\"" (:color series) "\"><title>"
                             (xml-escape (str (:label series) " " x "：" value))
                             "</title></rect>"))))
             "<text x=\"12\" y=\"" (- chart-height 4)
             "\" font-family=\"sans-serif\" font-size=\"10\" fill=\"#555\">"
             (xml-escape (str/join " · "
                                   (map (fn [s]
                                          (str (:label s)
                                               (when-let [unit (:unit s)] (str " (" unit ")"))))
                                        (model/series card))))
             "</text>"
             "</svg>")))))

(defn- chart-card
  [card callbacks filter set-filter!]
  (let [all-rows (model/rows card)
        series (model/series card)
        columns (model/columns card)
        [sort set-sort!] (react/useState nil)
        [page set-page!] (react/useState 0)
        [show-table? set-show-table!] (react/useState (boolean (:show-table? card)))
        [selected set-selected!] (react/useState nil)
        x-values (vec (distinct (map :x all-rows)))
        filtered (model/filter-rows all-rows (assoc filter :series series))
        sorted (model/sort-rows filtered columns sort)
        info (model/page-info sorted page card)
        summary (model/summary card all-rows (assoc filter :series series))
        filter-labels (model/active-filter-labels filter)
        reset! (fn [_]
                 (set-filter! {})
                 (set-sort! nil)
                 (set-page! 0)
                 (set-selected! nil))
        export-csv! (fn [current?]
                      (download-text!
                       (model/csv card
                                  sorted
                                  (assoc filter :series series)
                                  {:page page :current-page? current?})
                       (str (or (model/card-id card) "generated-card")
                            (if current? "-page" "-all") ".csv")
                       "text/csv;charset=utf-8"))
        export-svg! (fn [_]
                      (download-text! (svg-export-string card filtered filter)
                                      (str (or (model/card-id card) "generated-card") ".svg")
                                      "image/svg+xml;charset=utf-8"))]
    (dom/div nil
             (dom/style {:dangerouslySetInnerHTML #js {:__html css}})
             (when-let [label (model/state-label card)]
               (dom/div {:className "hc-gencard-note hc-gencard-note-warn"} label))
             (when (model/illustrative? card)
               (dom/div {:className "hc-gencard-note hc-gencard-note-warn"}
                        "示意内容：不是真实测量，不能当作实测数据。"))
             (dom/div {:className "hc-gencard-chart-toolbar"}
                      (dom/label nil
                                 "类别"
                                 (dom/select {:value (if (or (nil? (:x filter)) (= :all (:x filter)))
                                                       "all"
                                                       (str (:x filter)))
                                              :onChange (fn [event]
                                                          (let [value (.-value (.-target event))]
                                                            (set-filter! (fn [current]
                                                                           (assoc current :x (if (= "all" value) :all value))))
                                                            (set-page! 0)
                                                            (set-selected! nil)))}
                                             (dom/option {:value "all"} "全部")
                                             (for [x x-values]
                                               (dom/option {:key (str x) :value (str x)} (str x)))))
                      (dom/label {:style (style {:flexDirection "row" :alignItems "center" :minHeight 44})}
                                 (dom/input {:type "checkbox"
                                             :checked (boolean (:hide-missing? filter))
                                             :onChange (fn [event]
                                                         (set-filter! (fn [current]
                                                                        (assoc current :hide-missing? (.-checked (.-target event)))))
                                                         (set-page! 0)
                                                         (set-selected! nil))})
                                 "隐藏全缺失行")
                      (dom/button {:className "hc-gencard-button" :type "button" :onClick reset!}
                                  "重置视图"))
             (when (seq filter-labels)
               (dom/div {:className "hc-gencard-filter-chips"}
                        (for [label filter-labels]
                          (dom/span {:className "hc-gencard-chip" :key label} label))
                        (dom/button {:className "hc-gencard-button" :type "button" :onClick reset!}
                                    "清除筛选")))
             (dom/div {:className "hc-gencard-legend"}
                      (for [s series]
                        (dom/span {:key (str (:key s))}
                                  (dom/span {:className "hc-gencard-swatch"
                                             :style (style {:backgroundColor (:color s)})})
                                  (str (:label s)
                                       (when-let [unit (:unit s)] (str " (" unit ")"))))))
             (chart-svg card filtered selected set-selected!)
             (dom/div {:className "hc-gencard-readout" :role "status" :aria-live "polite"}
                      (if selected
                        (str (:x selected) " · " (:label (:series selected)) "："
                             (:value selected)
                             (when-let [unit (:unit (:series selected))] (str " " unit)))
                        "悬停或聚焦数据点查看精确值。"))
             (dom/div {:className "hc-gencard-summary"}
                      (dom/div {:className "hc-gencard-stat"}
                               (dom/strong nil (str (:visible summary) " / " (:total summary) " 行"))
                               "当前筛选 / 全部")
                      (dom/div {:className "hc-gencard-stat"}
                               (dom/strong nil (str (:missing summary)))
                               "缺失值")
                      (for [[si {:keys [label unit min max average count]}] (map-indexed vector (:stats summary))]
                        (dom/div {:className "hc-gencard-stat" :key (str si "-" label)}
                                 (dom/strong nil (str label (when unit (str " (" unit ")"))))
                                 (str "最小 " (if (some? min) (str min) "无")
                                      " · 最大 " (if (some? max) (str max) "无")
                                      " · 平均 " (if (some? average) (str average) "无")
                                      " · " count " 个值"))))
             (dom/div {:className "hc-gencard-actions-row"}
                      (dom/button {:className "hc-gencard-button" :type "button"
                                   :onClick #(set-show-table! (not show-table?))}
                                  (if show-table? "隐藏数据" "查看数据"))
                      (dom/button {:className "hc-gencard-button" :type "button" :onClick export-svg!}
                                  "导出图表 SVG")
                      (dom/button {:className "hc-gencard-button" :type "button"
                                   :onClick #(export-csv! true)}
                                  "导出当前页 CSV")
                      (dom/button {:className "hc-gencard-button" :type "button"
                                   :onClick #(export-csv! false)}
                                  "导出全部筛选 CSV"))
             (when show-table?
               (table-view card filtered columns sort set-sort! page set-page! filter))
             (dom/div {:className "hc-gencard-help"}
                      (str "图表数据来源：" (model/provenance-label card)
                           (when-let [s (not-empty (str (model/source card)))] (str " · " s))
                           (when-let [range (model/time-range card)] (str " · 时间范围 " range))
                           "。筛选、排序、分页和本地查看不会发起模型请求。")))))

;;; ---------------------------------------------------------------------------
;;; Shell

(defn- fallback-view
  [card values filter]
  (let [text (or (not-empty (str (model/text-fallback card)))
                 (case (model/kind card)
                   :chart (model/chart-text card (model/rows card) filter)
                   (model/form-text card values)))]
    (dom/details {:className "hc-gencard-fallback"}
                 (dom/summary nil "文字说明")
                 (dom/pre nil text)
                 (dom/button {:className "hc-gencard-button" :type "button"
                              :onClick #(clipboard! text)}
                             "复制文字"))))

(defn- unwrap-props
  [props]
  (cond
    (and (map? props) (contains? props :card)) props
    (and (map? props) (contains? props :props)) (:props props)
    (and (object? props) (some? (.-props props))) (.-props props)
    (and (object? props) (some? (.-card props))) (js->clj props :keywordize-keys true)
    :else {:card props}))

(defn generated-card
  "Render one generated form/chart card as a React component.

  Props: `{:card <card map> :on-submit! (fn [submission])
  :on-local-save! (fn [{:keys [card-id values]}])}`.  The component never
  performs a model request; `:on-submit!` is the only host action and is
  labelled “发送给助手”.

  Mount it as an element, not by calling it as a plain function:
  `(react/createElement generated-card #js {:props props})` or use
  `generated-card-element`.  Calling a hook-using component directly inside
  another render would merge its hooks into the caller."
  [props]
  (let [props (unwrap-props props)
        card (model/normalize-card (:card props))
        callbacks (select-keys props [:on-submit! :on-local-save!])
        [fullscreen? set-fullscreen!] (react/useState false)
        [values set-values!] (react/useState
                              (when (= :form (model/kind card))
                                (model/initial-values card)))
        [filter set-filter!] (react/useState {})
        form-values (when (= :form (model/kind card)) (or values {}))]
    (react/useEffect
     (fn []
       (let [handler (fn [event]
                       (when (and (exists? js/document)
                                  (= "Escape" (.-key event))
                                  fullscreen?)
                         (set-fullscreen! false)))]
         (when (exists? js/document)
           (.addEventListener js/document "keydown" handler))
         (fn []
           (when (exists? js/document)
             (.removeEventListener js/document "keydown" handler)))))
     #js [fullscreen?])
    (dom/section {:className (class-names "hc-gencard" (when fullscreen? "hc-gencard-fullscreen"))
                  :role "group"
                  :aria-labelledby (card-dom-id card "title")
                  :data-generated "true"
                  :data-kind (name (model/kind card))}
                 (dom/style {:dangerouslySetInnerHTML #js {:__html css}})
                 (dom/div {:className "hc-gencard-head"}
                          (dom/div {:className "hc-gencard-head-main"}
                                   (dom/div {:className "hc-gencard-badges"}
                                            (when-let [label (model/generated-label card)]
                                              (dom/span {:className "hc-gencard-badge"} label))
                                            (dom/span {:className "hc-gencard-badge hc-gencard-badge-muted"}
                                                      (model/provenance-label card))
                                            (when (model/illustrative? card)
                                              (dom/span {:className "hc-gencard-badge hc-gencard-badge-warn"}
                                                        "示意")))
                                   (dom/h3 {:id (card-dom-id card "title")} (model/title card))
                                   (when-let [purpose (not-empty (str (model/purpose card)))]
                                     (dom/p {:className "hc-gencard-purpose"} purpose))
                                   (dom/div {:className "hc-gencard-meta"}
                                            (when-let [by (model/generated-by card)]
                                              (dom/span nil (str "生成者：" by)))
                                            (when-let [at (model/generated-at card)]
                                              (dom/span nil (str "时间：" at)))
                                            (when-let [state (model/state-label card)]
                                              (dom/span nil state))))
                          (dom/div {:className "hc-gencard-actions"}
                                   (dom/button {:className "hc-gencard-button" :type "button"
                                                :onClick #(set-fullscreen! (not fullscreen?))}
                                               (if fullscreen? "退出全屏" "全屏"))
                                   (dom/button {:className "hc-gencard-button" :type "button"
                                                :onClick #(clipboard!
                                                           (case (model/kind card)
                                                             :chart (model/chart-text card (model/rows card) filter)
                                                             (model/form-text card form-values)))}
                                               "复制文字")))
                 (dom/div {:className "hc-gencard-body"}
                          (case (model/kind card)
                            :chart (chart-card card callbacks filter set-filter!)
                            (form-card card callbacks form-values set-values!))
                          (fallback-view card form-values filter)))))


(defn generated-card-element
  "Safe host helper: create a real React element instead of calling the
  component as a function.  Use this from conversation/message renderers."
  [props]
  (react/createElement generated-card #js {:props props}))
