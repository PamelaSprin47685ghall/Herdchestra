(ns hc.client.search.view
  "Search controls and source cards.

  The component is a pure renderer over `hc.client.search.model`.  It never
  invents a source, a date or a success state; it only shows the operator's
  mode/service choice and facts returned by the server."
  (:require ["react" :as react]
            [clojure.string :as str]
            [com.fulcrologic.fulcro.dom :as dom]
            [hc.client.search.model :as model]))

(def ^:private css
  ".hc-search-controls{display:flex;flex-direction:column;gap:8px;margin:8px 16px;padding:10px;border:1px solid var(--wb-line);border-radius:12px;background:var(--wb-surface);color:var(--wb-ink)}
.hc-search-head{display:flex;align-items:center;justify-content:space-between;gap:8px;flex-wrap:wrap}
.hc-search-title{font-weight:600;font-size:.92rem}
.hc-search-modes{display:flex;gap:6px;flex-wrap:wrap}
.hc-search-mode{min-height:44px;padding:6px 10px;border:1px solid var(--wb-line);border-radius:9px;background:var(--wb-surface);color:var(--wb-ink);font:inherit;cursor:pointer}
.hc-search-mode[aria-pressed=true]{background:var(--wb-accent);border-color:var(--wb-accent);color:var(--wb-accent-ink)}
.hc-search-mode:focus-visible,.hc-search-row select:focus-visible{outline:3px solid var(--wb-accent);outline-offset:2px}
.hc-search-row{display:flex;align-items:center;gap:8px;flex-wrap:wrap}
.hc-search-row select{min-height:44px;min-width:220px;max-width:100%;padding:6px 8px;border:1px solid var(--wb-line);border-radius:9px;background:var(--wb-surface);color:var(--wb-ink);font:inherit}
.hc-search-status{font-size:.82rem;color:var(--wb-ink-muted)}
.hc-search-error{color:var(--wb-danger);font-size:.84rem}
.hc-search-sources{margin:4px 0 0;padding:0;list-style:none;display:flex;flex-direction:column;gap:8px;max-height:40vh;overflow:auto;overscroll-behavior:contain}
.hc-source-card{border:1px solid var(--wb-line);border-radius:10px;padding:8px 10px;background:var(--wb-surface)}
.hc-source-head{display:flex;gap:8px;align-items:baseline;flex-wrap:wrap}
.hc-source-label{font-weight:700;font-size:.84rem}
.hc-source-title{font-weight:600;overflow-wrap:anywhere}
.hc-source-meta{font-size:.78rem;color:var(--wb-ink-muted);display:flex;gap:8px;flex-wrap:wrap;margin-top:3px}
.hc-source-snippet{font-size:.86rem;margin:6px 0 0;white-space:pre-wrap;overflow-wrap:anywhere}
.hc-source-link{font-size:.82rem;overflow-wrap:anywhere}
.hc-search-visually-hidden{position:absolute!important;width:1px;height:1px;padding:0;margin:-1px;overflow:hidden;clip:rect(0,0,0,0);white-space:nowrap;border:0}
@media (max-width:599px){.hc-search-controls{margin-left:10px;margin-right:10px}.hc-search-row select{min-width:100%}}")

(defn- style [m]
  (clj->js m))

(defn- class-names [& values]
  (str/join " " (remove nil? values)))

(defn- keywordize [value]
  (cond
    (keyword? value) value
    (string? value) (keyword (str/lower-case value))
    :else value))

(defn- content-label [status]
  (case (keywordize status)
    :full "已读全文"
    :partial "已读部分"
    :summary "摘要"
    :blocked "读取受阻"
    :unknown "状态未知"
    nil))

(defn- source-card
  [{:keys [label number title url site published-at published-at-unknown?
           fetched-at snippet content-status]}]
  (dom/li {:className "hc-source-card" :key (or number label url)}
          (dom/div {:className "hc-source-head"}
                   (dom/span {:className "hc-source-label"}
                             (if number (str "[" number "]") (str "[" label "]")))
                   (dom/span {:className "hc-source-title"} (or title url "来源")))
          (dom/div {:className "hc-source-meta"}
                   (when site (dom/span nil (str "站点：" site)))
                   (dom/span nil (str "发布："
                                      (if published-at-unknown?
                                        "未知"
                                        (or published-at "未知"))))
                   (dom/span nil (str "抓取：" (or fetched-at "未知")))
                   (when-let [label (content-label content-status)]
                     (dom/span nil label)))
          (when (and url (not (str/blank? url)))
            (dom/a {:className "hc-source-link"
                    :href url
                    :target "_blank"
                    :rel "noreferrer"}
                   url))
          (when (and snippet (not (str/blank? snippet)))
            (dom/p {:className "hc-source-snippet"} snippet))))

(defn source-cards
  "Render source cards from already-normalized cards or message citations."
  [sources]
  (let [sources (if (and (sequential? sources)
                         (every? map? sources))
                  sources
                  [])]
    (when (seq sources)
      (dom/ul {:className "hc-search-sources" :aria-label "搜索来源"}
              (for [source sources]
                (source-card source))))))

(defn- mode-button
  [mode current on-change! disabled?]
  (dom/button {:className "hc-search-mode"
               :type "button"
               :key (name mode)
               :aria-pressed (if (= mode current) "true" "false")
               :disabled (boolean disabled?)
               :onClick (fn [_]
                          (when (fn? on-change!)
                            (on-change! {:mode mode})))}
              (model/mode-labels mode)))

(defonce selected-service-id
  ;; Last service chosen by the operator.  Display and the send payload use
  ;; this local value, so a stale Fulcro props snapshot cannot show "未选择"
  ;; after a real selection.
  (atom nil))

(defn- service-select
  [service-id services disabled? on-change!]
  (dom/label {:className "hc-search-row"}
             (dom/span nil "服务")
             (dom/select {:value (or service-id "")
                          :disabled (boolean disabled?)
                          :aria-label "选择独立搜索服务"
                          :onChange (fn [event]
                                      (when (fn? on-change!)
                                        (on-change! {:service-id
                                                     (let [value (.-value (.-target event))]
                                                       (when-not (str/blank? value) value))})))}
                        (dom/option {:value ""} "未选择")
                        (for [service services]
                          (dom/option {:key (:id service)
                                       :value (str (:id service))
                                       :disabled (not (:selectable? service))}
                                      (:label service))))))

(defn SearchControls
  "React component for the search mode/service control and last-run sources.

  Props: `{:state ... :services [...] :error string :disabled? bool
  :on-change! fn}`.  `on-change!` receives `{:mode ...}` or `{:service-id ...}`."
  [js-props]
  (let [props (if (map? (.-props js-props))
                (.-props js-props)
                (js->clj (.-props js-props) :keywordize-keys true))
        state (or (:state props) model/initial-state)
        service-id (or (:value props)
                       (some-> (:service-id state) str not-empty)
                       @selected-service-id)
        services (or (:services props) [])
        error (:error props)
        disabled? (boolean (:disabled? props))
        on-change! (:on-change! props)
        last-run (:last-run state)
        cards (model/source-cards state)
        error-text (or error (model/run-error-label state))
        open? (:open? state)
        selected-service (some (fn [service]
                                 (when (= service-id (str (:id service)))
                                   service))
                               services)]
    (dom/div {:className "hc-search-controls"
              :role "group"
              :aria-label "联网搜索"}
             (dom/style {:dangerouslySetInnerHTML #js {:__html css}})
             (dom/div {:className "hc-search-head"}
                      (dom/span {:className "hc-search-title"} "联网搜索")
                      (dom/button {:className "hc-button"
                                   :type "button"
                                   :aria-expanded (if open? "true" "false")
                                   :onClick (fn [_]
                                              (when (fn? on-change!)
                                                (on-change! {:open? (not open?)})))}
                                  (if open? "收起" "展开")))
             (when open?
               (dom/div {:className "hc-search-modes"
                         :role "radiogroup"
                         :aria-label "搜索模式"}
                        (mapv (fn [mode]
                                (mode-button mode
                                             (keywordize (:mode state))
                                             on-change!
                                             disabled?))
                              model/modes)))
             (when open?
               (dom/div {:className "hc-search-status"}
                        (model/mode-descriptions
                         (keywordize (:mode state)))))
             (when open?
               (service-select service-id services disabled? on-change!))
             (when (and open? selected-service (:last-test selected-service))
               (dom/div {:className "hc-search-status"}
                        (str (get-in selected-service [:last-test :label])
                             (when-let [at (get-in selected-service
                                                   [:last-test :tested-at])]
                               (str " · " at)))))
             (when (and open?
                        (not= :off (keywordize (:mode state)))
                        (str/blank? (str service-id)))
               (dom/div {:className "hc-search-error" :role "alert"}
                        "请选择搜索服务"))
             (when (and open? error-text)
               (dom/div {:className "hc-search-error" :role "alert"}
                        error-text))
             (when (and open? last-run)
               (dom/div {:className "hc-search-status"}
                        (str (model/run-status-label state)
                             (when (model/must-search-failed? state)
                               " · 本轮必须搜索失败"))))
             (when (and open? (seq cards))
               (dom/div nil
                        (dom/div {:className "hc-search-status"} "来源")
                        (source-cards cards)))
             (when (and open? (pos? (model/omitted-source-count state)))
               (dom/div {:className "hc-search-status"}
                        (str "另有 " (model/omitted-source-count state)
                             " 条来源未显示（服务器预算或上限）。"))))))

(defn search-controls
  "Convenience entry for callers that pass a plain map.

  Render `SearchControls` as a real React element.  Calling it as a plain
  function would bind its hooks to the caller and make `useState` updates
  invisible to this component."
  [props]
  (react/createElement SearchControls #js {:props props}))
