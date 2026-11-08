(ns hc.client.workbench.views.common
  "Small Framework7 React wrappers and accessible presentation helpers.

  Framework7 React components are wrapped with Fulcro's `react-factory` so a
  CLJS props map (or a JS props object for camelCase Framework7 options) can be
  passed directly.  The shell does not rebuild Framework7's visual language;
  it supplies the workbench-specific composition around it."
  (:require ["framework7/lite" :as Framework7]
            ["framework7-react" :as Framework7ReactModule
             :refer [App View Page Navbar NavLeft NavTitle
                     NavRight PageContent Button Icon
                     Preloader Block BlockTitle Link Badge]]
            [clojure.string :as str]
            [com.fulcrologic.fulcro.algorithms.react-interop :as interop]
            [com.fulcrologic.fulcro.dom :as dom]))

;; Framework7 React registers its router hooks and the `f7ready` event bus only
;; after `Framework7.use(Framework7React)`.  Without this call, `Page` mounts
;; before `f7events` exists and throws on the first layout effect.
(.use (.-default Framework7) (.-default Framework7ReactModule))

(def ui-app (interop/react-factory App))
(def ui-view (interop/react-factory View))
(def ui-page (interop/react-factory Page))
(def ui-navbar (interop/react-factory Navbar))
(def ui-nav-left (interop/react-factory NavLeft))
(def ui-nav-title (interop/react-factory NavTitle))
(def ui-nav-right (interop/react-factory NavRight))
(def ui-page-content (interop/react-factory PageContent))
(def ui-button (interop/react-factory Button))
(def ui-icon (interop/react-factory Icon))
(def ui-preloader (interop/react-factory Preloader))
(def ui-block (interop/react-factory Block))
(def ui-block-title (interop/react-factory BlockTitle))
(def ui-link (interop/react-factory Link))
(def ui-badge (interop/react-factory Badge))

(defn badge
  [text tone]
  (dom/span {:className "wb-badge" :data-tone (name (or tone :neutral))} text))

(defn icon
  "A readable text glyph.  The glyph is decorative; the adjacent label carries
  the accessible name."
  [glyph]
  (dom/span {:className "wb-nav-icon" :aria-hidden "true"} glyph))

(def session-status-labels
  "User-facing labels for a session's internal status.

  `active`, `idle` and `finished` describe bookkeeping, not work the operator
  needs to see; they return nil and the caller omits the row.  The remaining
  words map onto the three user-side states where they apply."
  {:running "进行中"
   :starting "进行中"
   :streaming "进行中"
   :dispatching "进行中"
   :applying "进行中"
   :stopping "正在停止"
   :failed "未交付"
   :error "未交付"
   :completed "已交付"
   :archived "已归档"
   :deleted "已删除"})

(defn session-status-label
  [status]
  (get session-status-labels (keyword status)))

(defn time-label
  "Format an epoch-millisecond number or an ISO timestamp for the operator.

  Unparseable values are returned unchanged: a human label such as `刚刚`
  must not disappear merely because it is not a date."
  [value]
  (when (some? value)
    (try
      (let [value' (if (and (string? value)
                            (re-matches #"^\d+(\.\d+)?$" value))
                     (js/parseFloat value)
                     value)
            date (js/Date. value')]
        (if (js/isNaN (.getTime date))
          (str value)
          (.toLocaleString date "zh-CN"
                           #js {:hour12 false
                                :year "numeric"
                                :month "2-digit"
                                :day "2-digit"
                                :hour "2-digit"
                                :minute "2-digit"})))
      (catch :default _
        (str value)))))

(defn- text-value
  [value]
  (cond
    (nil? value) nil
    (keyword? value) (name value)
    :else (str value)))

(defn session-detail
  "One compact row detail line: status, update time and model.

  Empty parts are removed instead of leaving stray separators or the raw
  internal status/time that used to leak into the list."
  [status updated-at model-label]
  (->> [(session-status-label status)
        (time-label updated-at)
        (text-value model-label)]
       (remove str/blank?)
       (str/join " · ")))

(defn theme-label
  [theme]
  (get {:system "跟随系统" :light "浅色" :dark "深色"}
       (keyword theme)
       "—"))

(defn draft-storage-label
  [status]
  (get {:available "可用" :unavailable "不可用" :unknown "未知"}
       (keyword status)
       "—"))

(defn empty-state
  [title body]
  (dom/div {:className "wb-empty"}
           (dom/strong {} title)
           (dom/div {} body)))

(defn facts
  [rows]
  (dom/dl {:className "wb-facts"}
          (mapcat (fn [[label value]]
                    [(dom/dt {} label)
                     (dom/dd {} (or value "—"))])
                  rows)))

(defn copy-text!
  "Copy text to the clipboard.  Calls `on-result` with `:copied` or
  `:unavailable`; no UI claim is made before the browser reports back."
  [text on-result]
  (try
    (if (and (exists? js/navigator.clipboard)
             (fn? (.-writeText js/navigator.clipboard)))
      (-> (.writeText js/navigator.clipboard (str text))
          (.then (fn [] (when on-result (on-result :copied))))
          (.catch (fn [_] (when on-result (on-result :unavailable)))))
      (do
        (let [el (.createElement js/document "textarea")]
          (set! (.-value el) (str text))
          (set! (.-position (.-style el)) "fixed")
          (set! (.-opacity (.-style el)) "0")
          (.appendChild js/document.body el)
          (.select el)
          (let [ok? (try (.execCommand js/document "copy") (catch :default _ false))]
            (.removeChild js/document.body el)
            (when on-result (on-result (if ok? :copied :unavailable)))))))
    (catch :default _
      (when on-result (on-result :unavailable)))))

(defn copy-button
  [text label on-result]
  (dom/button {:type "button"
               :className "wb-button wb-button-small"
               :onClick (fn [_] (copy-text! text on-result))}
              label))

(defn focus-id!
  "Focus a rendered control by id, if it still exists."
  [id]
  (when (and id (exists? js/document))
    (some-> (.getElementById js/document id) (.focus))))

(defn notice-stack
  [notices on-dismiss]
  (dom/div {:className "wb-toast-stack" :aria-live "polite"}
           ;; Keep the visual stack short enough that it cannot grow over the
           ;; sticky composer/send row.  Older notices remain in state and
           ;; reappear only if newer ones are dismissed.
           (for [{:keys [id tone text]} (take-last 3 notices)]
             (dom/div {:key id
                       :className "wb-toast"
                       :data-tone (name (or tone :info))}
                      (dom/div {:className "wb-spread"}
                               (dom/span {} text)
                               (dom/button {:type "button"
                                            :className "wb-icon-button wb-button-small"
                                            :aria-label "关闭通知"
                                            :onClick (fn [_] (on-dismiss id))}
                                           "×"))))))

(defn- tab-keydown
  "Move selection and focus with the ARIA tabs arrow-key contract.

  The tablist renders every tab, so roving focus stays inside this list.
  Home/End jump to the first/last tab; Left/Right wrap."
  [event items selected on-select]
  (let [key (.-key event)
        ids (mapv :id items)
        n (count ids)
        current (let [idx (.indexOf (to-array ids) selected)]
                  (if (neg? idx) 0 idx))
        target (when (pos? n)
                 (case key
                   "ArrowRight" (mod (inc current) n)
                   "ArrowLeft" (mod (dec current) n)
                   "Home" 0
                   "End" (dec n)
                   nil))]
    (when (and target (not= target current))
      (.preventDefault event)
      (on-select (nth ids target))
      (let [list (.-parentElement (.-currentTarget event))
            buttons (.querySelectorAll list "[role='tab']")]
        (when-let [tab (aget buttons target)]
          (.focus tab))))))

(defn tabs
  [items selected on-select]
  (dom/div {:className "wb-tabs" :role "tablist"}
           (for [{:keys [id label]} items]
             (dom/button {:key (name id)
                          :type "button"
                          :role "tab"
                          :aria-selected (if (= id selected) "true" "false")
                          :tabIndex (if (= id selected) 0 -1)
                          :className "wb-tab"
                          :onKeyDown (fn [event]
                                       (tab-keydown event items selected
                                                    on-select))
                          :onClick (fn [_] (on-select id))}
                         label))))
