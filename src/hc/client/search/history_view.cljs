(ns hc.client.search.history-view
  "React view for global history search.

  The panel is a modal overlay owned by one module atom; the pure state lives
  in `hc.client.search.history` so the CLJ tests do not need React.  Command
  receipts arrive through the existing sync-bridge listener channel and are
  matched by command id, never by a guessed status.

  Opening is possible from the navigation entry (`#hc-history-search`) and
  from `Ctrl/Cmd+Shift+F`.  Escape closes the panel before the shell's stop
  gesture sees it.  Every click on a hit names the exact session and message;
  a historical version is confirmed first, and a missing session is reported
  instead of opening a same-titled session."
  (:require ["react" :as react]
            [clojure.string :as str]
            [com.fulcrologic.fulcro.application :as application]
            [com.fulcrologic.fulcro.components :as comp]
            [com.fulcrologic.fulcro.dom :as dom]
            [hc.client.search.history :as history]
            [hc.client.sessions.labels :as session-labels]
            [hc.client.workbench.commands :as commands]
            [hc.client.workbench.model :as model]
            [hc.client.workbench.mutations :as mutations]
            [hc.client.workbench.routing :as routing]
            [hc.client.workbench.sync-bridge :as bridge]))

(defonce state
  (atom history/initial-state))

(defonce ^:private pending-search
  (atom nil))

(defonce ^:private pending-index
  (atom nil))

(defonce ^:private return-focus
  (atom nil))

(defn- style
  [m]
  (clj->js m))

(defn open?
  []
  (history/open? @state))

(defn open!
  "Open the panel and remember the element that should regain focus."
  []
  (when-not (open?)
    (when (exists? js/document)
      (let [active (.-activeElement js/document)]
        (when (and active
                   (not (contains? #{"BODY" "HTML"}
                                   (some-> active .-tagName))))
          (reset! return-focus active)))))
  (swap! state #(-> % history/open (dissoc :jump-error :confirm)))
  nil)

(defn close!
  []
  (swap! state #(-> % history/close (dissoc :confirm)))
  (when-let [node @return-focus]
    (when (and node (fn? (.-focus node)))
      (js/setTimeout (fn [] (.focus node)) 0)))
  (reset! return-focus nil)
  nil)

(defn toggle!
  []
  (if (open?) (close!) (open!)))

;; ---------------------------------------------------------------------------
;; Receipt routing

(defn- event-name
  [event]
  (let [value (or (:name event) (:event event) (:type event))]
    (cond
      (keyword? value) value
      (string? value) (keyword value)
      :else nil)))

(defn- receipt-event?
  [event]
  (contains? #{:hc/command-receipt :hc/receipt} (event-name event)))

(defn- on-receipt!
  [event]
  (when (receipt-event? event)
    (let [value (or (:value event) (:data event) event)
          command-id (history/receipt-command-id value)
          payload (history/receipt-payload value)
          error (history/receipt-error value)
          pending @pending-search]
      (cond
        (and command-id pending (= command-id (:command-id pending)))
        (let [status (history/receipt-status value)
              current @state
              still-current?
              (and (= (:query pending)
                      (str/trim (or (:query current) "")))
                   (= (:filters pending) (:filters current)))]
          (reset! pending-search nil)
          (cond
            (not still-current?)
            ;; The operator changed the words or the conditions while this
            ;; command was in flight.  Its answer belongs to the old question.
            nil

            error
            (swap! state history/apply-error error)

            payload
            (swap! state history/apply-result payload)

            ;; A terminal receipt without a handler payload is a wiring or
            ;; server failure, not an eternal loading state.
            (contains? #{:accepted :applied :replayed :ok
                         :rejected :refused :failed :error} status)
            (swap! state
                   history/apply-error
                   {:code :HISTORY_SEARCH_FAILED
                    :message (if (contains? #{:accepted :applied :replayed :ok}
                                             status)
                               "服务器回执未携带搜索结果"
                               "服务器未接受历史搜索命令")})))

        (and command-id (= command-id @pending-index))
        (let [status (history/receipt-status value)]
          (cond
            error
            (do (reset! pending-index nil)
                (swap! state history/apply-index-error error))

            payload
            (do (reset! pending-index nil)
                (swap! state history/apply-index-status payload))

            (contains? #{:accepted :applied :replayed :ok
                         :rejected :refused :failed :error} status)
            (do
              (reset! pending-index nil)
              (swap! state
                     history/apply-index-error
                     {:code :HISTORY_SEARCH_STATUS_FAILED
                      :message "服务器回执未携带索引状态"}))))))))

(defn install!
  "Register the receipt listener once.  `main` calls this during startup."
  []
  (bridge/ensure-event-listener! ::receipt on-receipt!))

;; ---------------------------------------------------------------------------
;; Commands

(defn- request-index-status!
  [app]
  (when app
    (if (bridge/command-allowed?)
      (let [result (commands/search-history-status! app)]
        (if (:ok? result)
          (reset! pending-index (:command-id result))
          (swap! state history/apply-index-error
                 {:code (:reason result)
                  :message (history/error-label {:code (:reason result)})})))
      ;; The panel still opens offline; the status row says why it is
      ;; unavailable instead of emitting a blocked-command notice.
      (swap! state history/apply-index-error {:code :connection-not-ready}))))

(defn- submit-search!
  [app]
  (let [snapshot @state
        query (str/trim (or (:query snapshot) ""))]
    (if (str/blank? query)
      (swap! state history/apply-error {:code :SEARCH_QUERY_INVALID})
      (let [result (commands/search-history! app query (:filters snapshot))]
        (if (:ok? result)
          (do
            (reset! pending-search {:command-id (:command-id result)
                                    :query query
                                    :filters (:filters snapshot)})
            (swap! state #(-> % history/begin-search
                              (dissoc :jump-error :confirm))))
          (swap! state history/apply-error
                 {:code (or (:reason result) :connection-not-ready)}))))))

(defn- find-message-node
  [message-id]
  (when (exists? js/document)
    (some (fn [node]
            (when (= message-id (.getAttribute node "data-message-id"))
              node))
          (array-seq (.querySelectorAll js/document "[data-message-id]")))))

(defn- locate-message!
  "Scroll to MESSAGE-ID after the session view commits.  A hit outside the
  rendered history window is reported honestly instead of pretending the
  conversation jumped to it."
  [app message-id attempt]
  (js/setTimeout
   (fn []
     (if-let [node (find-message-node message-id)]
       (do
         (doseq [details (array-seq (.querySelectorAll node "details"))]
           (set! (.-open details) true))
         (when (fn? (.-scrollIntoView node))
           (.scrollIntoView node #js {:block "center"})))
       (if (< attempt 8)
         (locate-message! app message-id (inc attempt))
         (commands/show-notice!
          app :info
          "已打开会话；该命中不在当前可见的消息窗口中，请加载更早消息后再定位。"))))
   120))

(defn- jump-to-hit!
  [app hit historical?]
  (let [session-id (:session-id hit)
        message-id (:message-id hit)
        db (application/current-state app)]
    (when (and historical? (:version-id hit))
      ;; Selecting a historical version is an explicit, server-confirmed
      ;; action.  It is sent only after the confirmation bar accepted it.
      (if (some? (bridge/command-epoch-for-session session-id))
        (commands/select-version! app session-id message-id (:version-id hit))
        (commands/show-notice!
         app :info
         "会话正在打开；历史版本尚未切换，请稍后再次打开该命中。")))
    ;; The session Owner is lazy.  Ensuring it and fetching the newest window
    ;; here mirrors the session list's selection path so a recent hit has a
    ;; real message entity to scroll to.
    (commands/ensure-session-owner! app session-id)
    (when (model/missing-session-messages? db session-id)
      (commands/fetch-history-window! app session-id {}))
    (comp/transact! app [(mutations/select-session {:session-id session-id})])
    (routing/navigate! app :sessions session-id)
    (close!)
    (locate-message! app message-id 0)))

(defn- open-hit!
  [app hit]
  (when app
    (let [db (application/current-state app)
          session-id (:session-id hit)]
      (cond
        (nil? (model/session-by-id db session-id))
        (swap! state assoc :jump-error
               (str "该会话已删除或不可访问，无法打开：" session-id))

        (true? (:historical-version? hit))
        (swap! state assoc :confirm hit)

        :else
        (do
          (swap! state dissoc :jump-error :confirm)
          (jump-to-hit! app hit false))))))

(defn- confirm-hit!
  [app]
  (let [hit (:confirm @state)]
    (when hit
      (swap! state dissoc :confirm :jump-error)
      (jump-to-hit! app hit true))))

(defn- selected-hit
  [snapshot]
  (let [hits (vec (:hits snapshot))
        index (long (or (:selected-index snapshot) 0))]
    (when (seq hits)
      (nth hits (max 0 (min (dec (count hits)) index))))))

;; ---------------------------------------------------------------------------
;; Filter helpers

(defn- ms->local-input
  [ms]
  (when (and (number? ms) (pos? ms))
    (let [date (js/Date. ms)
          pad #(.padStart (str %) 2 "0")]
      (str (.-getFullYear date) "-"
           (pad (inc (.-getMonth date))) "-"
           (pad (.-getDate date)) "T"
           (pad (.-getHours date)) ":"
           (pad (.-getMinutes date))))))

(defn- local-input->ms
  [value]
  (when (and (string? value) (not (str/blank? value)))
    (let [ms (.getTime (js/Date. value))]
      (when-not (js/isNaN ms) (long ms)))))

(defn- status-value
  [filters]
  (some-> (:status filters) name))

(defn- chips
  [filters]
  (cond-> []
    (:assistant-id filters)
    (conj {:key :assistant-id
           :label (str "助手：" (:assistant-id filters))
           :aria (str "移除助手筛选：" (:assistant-id filters))})

    (:status filters)
    (conj {:key :status
           :label (str "状态：" (case (:status filters)
                                 :active "活跃"
                                 :archived "归档"
                                 (name (:status filters))))
           :aria "移除状态筛选"})

    (some? (:from-ms filters))
    (conj {:key :from-ms :label "起始时间" :aria "移除起始时间筛选"})

    (some? (:to-ms filters))
    (conj {:key :to-ms :label "结束时间" :aria "移除结束时间筛选"})

    (true? (:include-versions? filters))
    (conj {:key :include-versions?
           :label "含历史版本" :aria "移除历史版本筛选"})

    (true? (:has-attachments? filters))
    (conj {:key :has-attachments?
           :label "仅有附件" :aria "移除附件筛选"})))

(defn- remove-filter!
  [k]
  (swap! state history/set-filters {k nil}))

;; ---------------------------------------------------------------------------
;; Component

(defn- prop
  [props k]
  (if (map? props)
    (get props k)
    (when props (aget props (name k)))))

(defn- focusables
  [root]
  (when root
    (vec (array-seq
          (.querySelectorAll
           root
           "button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [href], [tabindex]:not([tabindex='-1'])")))))

(defn- panel-keydown!
  [app event]
  (let [key (.-key event)
        target (.-target event)
        tag (some-> target .-tagName)
        text-control? (contains? #{"INPUT" "TEXTAREA" "SELECT"} tag)]
    (case key
      "Escape"
      (do (.preventDefault event)
          (.stopPropagation event)
          (close!))

      "Tab"
      (let [nodes (focusables (.-currentTarget event))
            first-node (first nodes)
            last-node (peek nodes)
            active (.-activeElement js/document)]
        (cond
          (and (not (.-shiftKey event)) (= active last-node))
          (do (.preventDefault event) (some-> first-node .focus))

          (and (.-shiftKey event) (= active first-node))
          (do (.preventDefault event) (some-> last-node .focus))))

      "ArrowDown"
      (do (.preventDefault event)
          (swap! state history/move-selection 1))

      "ArrowUp"
      (do (.preventDefault event)
          (swap! state history/move-selection -1))

      "Enter"
      (when (and (not text-control?)
                 (not= "BUTTON" tag))
        (.preventDefault event)
        (when-let [hit (selected-hit @state)]
          (open-hit! app hit)))

      nil)))

(defn history-search-panel
  "React overlay.  Pass `{:app <fulcro-app>}`.

  The component is always mounted by the shell; it returns nil while closed.
  Hooks are declared unconditionally before the closed branch."
  [js-props]
  (let [app (prop js-props :app)
        [snapshot set-snapshot!] (react/useState @state)
        [assistant-draft set-assistant-draft!]
        (react/useState (fn [] (or (get-in @state [:filters :assistant-id]) "")))
        [from-draft set-from-draft!]
        (react/useState (fn [] (or (ms->local-input
                                    (get-in @state [:filters :from-ms])) "")))
        [to-draft set-to-draft!]
        (react/useState (fn [] (or (ms->local-input
                                    (get-in @state [:filters :to-ms])) "")))
        input-ref (react/useRef nil)
        open? (:open? snapshot)
        filters (or (:filters snapshot) {})
        status (:status snapshot)
        hits (:hits snapshot)
        index (:index snapshot)
        db (when (and app open?) (application/current-state app))
        assistant-labels
        (if db
          (into {} (map (juxt :id :label)) (model/assistant-options db))
          {})]
    (react/useEffect
     (fn []
       (add-watch state ::panel (fn [_ _ _ next] (set-snapshot! next)))
       (fn [] (remove-watch state ::panel)))
     #js [])
    (react/useEffect
     (fn []
       (when open?
         (when-let [node (.-current input-ref)]
           (.focus node))
         (when app
           (request-index-status! app)))
       js/undefined)
     #js [open? app])
    (when open?
      (dom/div
       {:className "hc-history-search-backdrop"
        :role "presentation"
        :style (style {:position "fixed"
                       :inset 0
                       :zIndex 1000
                       :background "rgba(0,0,0,.42)"
                       :display "flex"
                       :alignItems "flex-start"
                       :justifyContent "center"
                       :padding "6vh 12px 12px"})
        :onMouseDown
        (fn [event]
          (when (= (.-target event) (.-currentTarget event))
            (close!)))}
       (dom/section
        {:className "hc-history-search-panel"
         :id "hc-history-search-panel"
         :role "dialog"
         :aria-modal "true"
         :aria-busy (if (= :loading status) "true" "false")
         :aria-labelledby "hc-history-search-title"
         :onKeyDown (fn [event] (panel-keydown! app event))
         :style (style {:width "min(760px, 100%)"
                        :maxHeight "86vh"
                        :overflow "auto"
                        :background "var(--wb-surface, #fff)"
                        :color "var(--wb-ink, #111)"
                        :border "1px solid var(--wb-line, #c8c8cc)"
                        :borderRadius "14px"
                        :boxShadow "0 18px 60px rgba(0,0,0,.35)"
                        :padding "16px"
                        :display "flex"
                        :flexDirection "column"
                        :gap "10px"})}
        (dom/div {:style (style {:display "flex"
                                 :alignItems "center"
                                 :justifyContent "space-between"
                                 :gap "8px"})}
                 (dom/h2 {:id "hc-history-search-title"
                          :style (style {:margin 0 :fontSize "1.08rem"})}
                         "全局历史搜索")
                 (dom/button {:type "button"
                              :className "wb-button wb-button-small"
                              :aria-label "关闭全局历史搜索"
                              :onClick (fn [_] (close!))}
                             "关闭"))
        (dom/p {:className "wb-muted"
                :style (style {:margin 0 :fontSize ".84rem"})}
               "跨会话检索已索引的消息；命中回主库核验。Ctrl/Cmd+Shift+F 打开，Esc 关闭。")
        (dom/div {:style (style {:display "flex" :gap "8px" :flexWrap "wrap"})}
                 (dom/input
                  {:ref input-ref
                   :id "hc-history-search-input"
                   :type "search"
                   :className "wb-input"
                   :aria-label "搜索历史关键词"
                   :aria-keyshortcuts "Control+Shift+F Meta+Shift+F"
                   :placeholder "搜索历史关键词"
                   :value (:query snapshot)
                   :style (style {:flex "1 1 260px" :minHeight 44})
                   :onChange (fn [event]
                               (swap! state history/set-query
                                      (.. event -target -value)))
                   :onKeyDown
                   (fn [event]
                     (when (and (= "Enter" (.-key event))
                                (not (.-isComposing event)))
                       (.preventDefault event)
                       (let [snapshot @state]
                         ;; First Enter searches; Enter on an unchanged result
                         ;; set opens the selected hit.
                         (if (and (= :loaded (:status snapshot))
                                  (seq (:hits snapshot))
                                  (= (str/trim (or (:query snapshot) ""))
                                     (:result-query snapshot)))
                           (when-let [hit (selected-hit snapshot)]
                             (open-hit! app hit))
                           (submit-search! app)))))})
                 (dom/button {:type "button"
                              :className "hc-button hc-button-primary"
                              :id "hc-history-search-submit"
                              :onClick (fn [_] (submit-search! app))}
                             "搜索"))
        ;; Index status is read-only.  There is deliberately no rebuild
        ;; button: the browser handler always refuses rebuild.
        (dom/div {:className "wb-muted"
                  :role "status"
                  :style (style {:display "flex"
                                 :gap "8px"
                                 :alignItems "center"
                                 :flexWrap "wrap"
                                 :fontSize ".84rem"})}
                 (dom/span nil
                           (or (history/index-status-label index)
                               "索引状态：未读取"))
                 (dom/button {:type "button"
                              :className "wb-button wb-button-small"
                              :onClick (fn [_] (request-index-status! app))}
                             "刷新索引状态")
                 (when (= :error (:state index))
                   (dom/span {:role "alert"}
                             (if (= :connection-not-ready
                                    (get-in index [:error :code]))
                               "实时连接尚未就绪，无法读取索引状态"
                               (history/error-label (:error index)))))
                 (when-let [last-error (:last-error index)]
                   (dom/span {:role "alert"}
                             (str "索引上次错误：" last-error)))
                 (when (= :missing (:state index))
                   (dom/span nil
                             "服务端尚未重建索引；搜索可能没有结果。")))
        ;; Filters
        (dom/div {:style (style {:display "flex"
                                 :gap "8px"
                                 :alignItems "center"
                                 :flexWrap "wrap"})}
                 (dom/input
                  {:type "text"
                   :className "wb-input"
                   :aria-label "按助手 ID 筛选"
                   :placeholder "助手 ID（可选）"
                   :value assistant-draft
                   :style (style {:minWidth 160 :minHeight 40})
                   :onChange (fn [event]
                               (set-assistant-draft! (.. event -target -value)))
                   :onKeyDown
                   (fn [event]
                     (when (= "Enter" (.-key event))
                       (.preventDefault event)
                       (swap! state history/set-filters
                              {:assistant-id assistant-draft})))})
                 (dom/button {:type "button"
                              :className "wb-button wb-button-small"
                              :onClick (fn [_]
                                         (swap! state history/set-filters
                                                {:assistant-id assistant-draft}))}
                             "按助手筛选")
                 (dom/select
                  {:className "wb-input"
                   :aria-label "按会话状态筛选"
                   :value (or (status-value filters) "")
                   :style (style {:minHeight 40})
                   :onChange (fn [event]
                               (swap! state history/set-filters
                                      {:status (let [value (.. event -target -value)]
                                                 (when-not (str/blank? value)
                                                   value))}))}
                  (dom/option {:value ""} "全部状态")
                  (dom/option {:value "active"} "活跃")
                  (dom/option {:value "archived"} "归档"))
                 (dom/label {:className "wb-muted"
                             :style (style {:display "flex" :gap 4
                                            :alignItems "center"})}
                            (dom/input {:type "checkbox"
                                        :checked (true? (:include-versions? filters))
                                        :onChange
                                        (fn [event]
                                          (swap! state history/set-filters
                                                 {:include-versions?
                                                  (when (.-checked (.-target event))
                                                    true)}))})
                            "含历史版本")
                 (dom/label {:className "wb-muted"
                             :style (style {:display "flex" :gap 4
                                            :alignItems "center"})}
                            (dom/input {:type "checkbox"
                                        :checked (true? (:has-attachments? filters))
                                        :onChange
                                        (fn [event]
                                          (swap! state history/set-filters
                                                 {:has-attachments?
                                                  (when (.-checked (.-target event))
                                                    true)}))})
                            "仅有附件"))
        (dom/div {:style (style {:display "flex"
                                 :gap "8px"
                                 :alignItems "center"
                                 :flexWrap "wrap"
                                 :fontSize ".84rem"})}
                 (dom/label {:className "wb-muted"}
                            "从 "
                            (dom/input
                             {:type "datetime-local"
                              :aria-label "起始时间"
                              :value from-draft
                              :onChange (fn [event]
                                          (set-from-draft!
                                           (.. event -target -value)))}))
                 (dom/label {:className "wb-muted"}
                            "到 "
                            (dom/input
                             {:type "datetime-local"
                              :aria-label "结束时间"
                              :value to-draft
                              :onChange (fn [event]
                                          (set-to-draft!
                                           (.. event -target -value)))}))
                 (dom/button
                  {:type "button"
                   :className "wb-button wb-button-small"
                   :onClick
                   (fn [_]
                     (swap! state history/set-filters
                            {:from-ms (local-input->ms from-draft)
                             :to-ms (local-input->ms to-draft)}))}
                  "应用时间")
                 (when (seq (chips filters))
                   (dom/button {:type "button"
                                :className "wb-button wb-button-small"
                                :onClick (fn [_]
                                           (swap! state history/clear-filters))}
                               "清除全部筛选")))
        (when (seq (chips filters))
          (dom/div {:style (style {:display "flex"
                                   :gap "6px"
                                   :flexWrap "wrap"})}
                   (for [{:keys [key label aria]} (chips filters)]
                     (dom/span
                      {:key (name key)
                       :className "wb-badge"
                       :data-tone "neutral"
                       :style (style {:display "inline-flex"
                                      :gap "4px"
                                      :alignItems "center"})}
                      label
                      (dom/button
                       {:type "button"
                        :className "wb-button wb-button-small"
                        :aria-label aria
                        :onClick (fn [_] (remove-filter! key))}
                       "×")))))
        (when-let [jump-error (:jump-error snapshot)]
          (dom/div {:role "alert"
                    :className "wb-error"
                    :style (style {:fontSize ".86rem"})}
                   jump-error))
        (when-let [confirm (:confirm snapshot)]
          (let [context (history/format-hit-context confirm)]
            (dom/div
             {:role "alertdialog"
              :aria-label "确认打开历史版本"
              :className "wb-badge"
              :data-tone "warn"
              :style (style {:display "flex"
                             :flexDirection "column"
                             :gap "6px"
                             :padding "10px"
                             :borderRadius "10px"})}
             (dom/strong nil "该命中来自历史版本")
             (dom/span nil
                       (str "继续将切换到版本 "
                            (or (:version-id confirm) "未知")
                            "，并定位到消息 "
                            (or (:message-id confirm) "未知")
                            "。"))
             (dom/span {:className "wb-muted"} (:snippet confirm))
             (dom/div {:style (style {:display "flex" :gap "8px"})}
                      (dom/button
                       {:type "button"
                        :className "hc-button hc-button-primary"
                        :onClick (fn [_] (confirm-hit! app))}
                       "继续打开历史版本")
                      (dom/button
                       {:type "button"
                        :className "hc-button"
                        :onClick (fn [_] (swap! state dissoc :confirm))}
                       "取消")))))
        ;; Result/loading/error area
        (cond
          (= :loading status)
          (dom/div {:className "wb-muted"
                    :role "status"
                    :aria-live "polite"}
                   "正在搜索历史…")

          (= :error status)
          (dom/div {:role "alert"
                    :style (style {:display "flex"
                                   :flexDirection "column"
                                   :gap "6px"})}
                   (dom/strong nil
                               (history/error-label (:error snapshot)))
                   (dom/button {:type "button"
                                :className "wb-button wb-button-small"
                                :onClick (fn [_] (submit-search! app))}
                               "重试"))

          (= :loaded status)
          (dom/div {:className "wb-muted"
                    :role "status"
                    :aria-live "polite"}
                   (str "命中 " (:count snapshot) " 条"
                        (when (:truncated? snapshot)
                          "（服务器结果已截断，仍有更多命中）")))

          :else
          (dom/div {:className "wb-muted"
                    :role "status"}
                   "输入关键词后按 Enter 搜索"))

        (when (and (= :loaded status) (empty? hits))
          (dom/p {:className "wb-muted"} "没有符合条件的会话"))

        (when (seq hits)
          (dom/ol
           {:className "hc-history-hits"
            :aria-label "历史搜索结果"
            :style (style {:listStyle "none"
                           :margin 0
                           :padding 0
                           :display "flex"
                           :flexDirection "column"
                           :gap "8px"})}
           (for [[index hit] (map-indexed vector hits)]
             (let [context (history/format-hit-context hit)
                   selected? (= index (:selected-index snapshot))
                   hit-key (str (:session-id hit) "/" (:message-id hit)
                                "/" (:version-id hit) "/" index)]
               (dom/li
                {:key hit-key
                 :style (style {:margin 0})}
                (dom/button
                 {:type "button"
                  :className (str "hc-history-hit"
                                  (when selected? " is-selected"))
                  :aria-current (when selected? "true")
                  :onMouseEnter (fn [_]
                                  (swap! state assoc :selected-index index))
                  :onClick (fn [_] (open-hit! app hit))
                  :style (style {:display "flex"
                                 :flexDirection "column"
                                 :gap "4px"
                                 :width "100%"
                                 :textAlign "left"
                                 :padding "10px"
                                 :borderRadius "10px"
                                 :border (str "1px solid "
                                              (if selected?
                                                "var(--wb-accent, #0a84ff)"
                                                "var(--wb-line, #d8d8dc)"))
                                 :background (if selected?
                                               "var(--wb-surface-2, #f2f2f7)"
                                               "var(--wb-surface, #fff)")
                                 :color "inherit"
                                 :font "inherit"
                                 :cursor "pointer"})}
                 (dom/div {:style (style {:display "flex"
                                          :gap "8px"
                                          :alignItems "center"
                                          :flexWrap "wrap"})}
                          (dom/strong nil
                                      (session-labels/title-label
                                       (:session-title context)))
                          (when-let [badge (:version-badge context)]
                            (dom/span {:className "wb-badge"
                                       :data-tone "warn"}
                                      badge)))
                 (dom/small {:className "wb-muted"}
                            (str (or (get assistant-labels (:assistant-id hit))
                                     (:assistant context))
                                 " · " (:time context)))
                 (dom/p {:style (style {:margin 0
                                        :fontSize ".88rem"
                                        :whiteSpace "pre-wrap"})}
                        (:snippet hit))))))))
        (dom/p {:className "wb-muted"
                :style (style {:margin 0 :fontSize ".78rem"})}
               "↑/↓ 选择 · Enter 打开选中结果 · Tab 在控件与结果间移动 · Esc 关闭"))))))

(defn panel-element
  "Create the overlay element for the shell.  Hooks stay inside this
  component instead of leaking into the shell's hook order."
  [app]
  (react/createElement history-search-panel #js {:app app}))
