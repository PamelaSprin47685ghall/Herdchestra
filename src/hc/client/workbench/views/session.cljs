(ns hc.client.workbench.views.session
  "Session list and mount point for the conversation-ui surface.

  The conversation surface itself is `hc.client.conversation.view/
  conversation-page`; this namespace owns only the shell's session selection,
  route navigation, and callback wiring.  It does not render a second message
  list, composer, or ask card."
  (:require ["react" :as react]
            [clojure.string :as str]
            [com.fulcrologic.fulcro.application :as application]
            [com.fulcrologic.fulcro.components :as comp]
            [com.fulcrologic.fulcro.dom :as dom]
            [hc.client.conversation.a11y :as a11y]
            [hc.client.conversation.attachments.surface :as attachments-surface]
            [hc.client.conversation.draft :as draft]
            [hc.client.conversation.stream :as stream]
            [hc.client.materials :as materials]
            [hc.client.search.model :as search-model]
            [hc.client.search.view :as search-view]
            [hc.client.sessions.controls :as session-controls]
            [hc.client.sessions.controls.ui :as session-controls-ui]
            [hc.client.workbench.commands :as commands]
            [hc.client.workbench.conversation-bridge :as conversation-bridge]
            [hc.client.workbench.model :as model]
            [hc.client.workbench.mutations :as mutations]
            [hc.client.workbench.routing :as routing]
            [hc.client.workbench.settings-bridge :as settings-bridge]
            [hc.client.workbench.views.common :as common]))

(defn- platform
  "Input mode is not viewport width.  A narrow desktop window still uses the
  desktop Enter-to-send default; a coarse-pointer device without a fine
  pointer uses the mobile newline default."
  [explicit]
  (or explicit
      (if (and (exists? js/window)
               (fn? (.-matchMedia js/window))
               (.-matches (.matchMedia js/window "(pointer: coarse)"))
               (not (.-matches (.matchMedia js/window "(any-pointer: fine)"))))
        :mobile
        :desktop)))

(defn- ensure-session-messages!
  "Ask for a fresh baseline when a session summary says it has messages but
  the local normalized table has none.

  A complete baseline carries every visible session's history, including a
  branch's source.  If the table is nonetheless missing entities the summary
  advertises, the snapshot is inconsistent or pruned.  Selection itself is
  local; the server baseline is the only source of the missing entities, so
  request one instead of rendering a false empty conversation.  A normal
  branch/origin selection already has the messages and stays a local
  navigation."
  [this session-id]
  (when (model/missing-session-messages? (application/current-state this)
                                         session-id)
    (commands/request-resync! this :selected-session-messages-missing)))

(defn select-session!
  "Select one session from any workbench surface.

  The shell's navigation drawer and the session list share this path so a
  narrow-window operator gets the same selection, baseline check, and focus
  behavior as the wide list."
  [this session-id]
  (comp/transact! this [(mutations/select-session {:session-id session-id})])
  (routing/navigate! this :sessions session-id)
  (ensure-session-messages! this session-id))

(defn- session-row
  [this props db session selected-id selectable?]
  (let [{:keys [id title status updated-at model-label]} session
        details (common/session-detail status updated-at model-label)]
    (dom/li {:key id :className "wb-session-item"}
            (if selectable?
              (dom/button {:type "button"
                           :className "wb-list-button"
                           :aria-current (if (= id selected-id) "true" "false")
                           :onClick (fn [_]
                                      (select-session! this id))}
                          (dom/div {:className "wb-list-main"}
                                   (dom/strong {} title)
                                   (when (seq details)
                                     (dom/small {} details))))
              (dom/div {:className "wb-list-main"}
                       (dom/strong {} title)
                       (when (seq details)
                         (dom/small {} details))))
            (react/createElement
             session-controls-ui/SessionControls
             #js {:session session
                  :connection (:ui/connection props)
                  :command (session-controls/latest-command (:ui/command db) id)
                  :on-command (fn [{:keys [session-id kind payload]}]
                                (commands/session-metadata!
                                 this (or session-id id) kind payload))
                  :on-notice (fn [tone text]
                               (commands/show-notice! this tone text))}))))

(defn- session-list
  [this props sessions selected-id]
  (let [db (application/current-state this)
        query (or (:ui/session-filter props) "")
        limit (if (str/blank? query)
                (max 200 (long (or (:ui/session-limit props) 200)))
                ;; A filter is already a narrowing action; show every match
                ;; instead of hiding a hit behind the render window.
                (count sessions))
        visible (vec (take limit sessions))
        selected (some #(when (= selected-id (:id %)) %) sessions)
        deleted (session-controls/deleted-sessions (:ui/session-list props))
        list-command (->> (vals (or (:ui/command props) {}))
                          (filter #(= :session/list (:kind %)))
                          (sort-by #(str (or (:created-at %) "")))
                          last)
        list-pending? (contains? #{:created :submitting :sent}
                                 (:status list-command))
        list-view (model/command-view list-command)]
    (dom/aside {:className "wb-session-list" :aria-label "会话列表"}
             (dom/div {:className "wb-session-list-header"}
                      (dom/h2 {} "会话")
                      (dom/span {:className "wb-muted"}
                                (str (count visible) " / " (count sessions) " 条"))
                      (dom/button {:type "button"
                                   :className "wb-button wb-button-small"
                                   :aria-label "新建会话"
                                   :onClick (fn [_]
                                              (commands/create-session!
                                               this {:title "新会话"}))}
                                  "新建")
                      (dom/button {:type "button"
                                   :className "wb-button wb-button-small"
                                   :aria-label "新建临时会话"
                                   :onClick (fn [_]
                                              (commands/create-session!
                                               this {:title "临时会话"
                                                     :temporary? true}))}
                                  "临时"))
             (dom/input {:id "wb-session-search"
                         :type "search"
                         :className "wb-input wb-session-search"
                         :placeholder "过滤会话"
                         :aria-label "过滤会话"
                         :value query
                         :onChange (fn [event]
                                     (comp/transact!
                                      this
                                      [(mutations/set-session-filter
                                        {:value (.. event -target -value)})]))})
             (when (:temporary? selected)
               (dom/div {:className "wb-session-list-header"
                         :role "group"
                         :aria-label "临时会话操作"}
                        (dom/button {:type "button"
                                     :className "wb-button wb-button-small"
                                     :aria-label "转为普通会话"
                                     :onClick (fn [_]
                                                (commands/transfer-session!
                                                 this selected-id))}
                                    "转为普通")
                        (dom/button {:type "button"
                                     :className "wb-button wb-button-small"
                                     :aria-label "结束临时会话"
                                     :onClick (fn [_]
                                                (commands/discard-session!
                                                 this selected-id))}
                                    "结束临时")))
             (if (seq sessions)
               (dom/ul {:className "wb-list"}
                       (for [session visible]
                         (session-row this props db session selected-id true))
                       (when (< limit (count sessions))
                         (dom/li {:className "wb-session-more"}
                                 (dom/button
                                  {:type "button"
                                   :className "wb-button wb-button-small"
                                   :aria-label "显示更多会话"
                                   :onClick (fn [_]
                                              (comp/transact!
                                               this
                                               [(mutations/set-session-limit
                                                 {:limit (+ limit 200)})]))}
                                  (str "显示更多会话（还有 "
                                       (- (count sessions) limit) " 条）")))))
               (common/empty-state (if (str/blank? query)
                                     "没有可见会话"
                                     "没有匹配会话")
                                   (model/empty-state :sessions)))
             (dom/details {:className "wb-deleted-sessions"}
                          (dom/summary {}
                                       (str "已删除会话"
                                            (when (seq deleted)
                                              (str "（" (count deleted) "）"))))
                          (dom/div {:className "wb-row"}
                                   (dom/button {:type "button"
                                                :className "wb-button wb-button-small"
                                                :disabled list-pending?
                                                :onClick (fn [_]
                                                           (commands/session-list!
                                                            this {:include-deleted? true}))}
                                               (cond
                                                 list-pending? "正在加载…"
                                                 (seq deleted) "刷新已删除会话"
                                                 :else "加载已删除会话")))
                          (when list-view
                            (dom/p #js {:className "wb-muted"
                                        :role "status"
                                        :aria-live "polite"}
                                   (session-controls/command-status-text list-view)))
                          (if (seq deleted)
                            (dom/ul {:className "wb-list"}
                                    (for [session deleted]
                                      (session-row this props db session nil false)))
                            (dom/p {:className "wb-muted"}
                                   "尚未加载，或没有普通已删除会话。临时会话不进入已删除区。"))))))

(defn- asks-for-session [props session-id]
  (into {}
        (filter (fn [[_ ask]]
                  (= session-id (:session-id ask))))
        (or (:ui/ask props) {})))

(defn- conversation-commands
  "Translate workbench command kinds to the conversation surface's display
  vocabulary.  The wire payload keeps the canonical `:send-message` etc."
  [commands]
  (into {}
        (map (fn [[id command]]
               [id (update command :kind
                           (fn [kind]
                             (case kind
                               :send-message :send
                               :stop-run :stop
                               :answer-ask :unpark
                               kind)))]))
        (or commands {})))

(defn- ConversationWithAttachments
  [js-props]
  (attachments-surface/conversation-with-attachments
   (.-props js-props)))

(defn- branch-origin-banner
  "Show the branch source as a real jump when the source is still visible.

  A deleted or otherwise absent source is named as unavailable; it is never
  rendered as a button that cannot navigate."
  [this db selected-id]
  (when-let [{:keys [session-id visible? title origin-message-id]}
             (model/branch-origin db selected-id)]
    (dom/div {:className "wb-branch-origin" :role "note"}
             (dom/span {:className "wb-badge" :data-tone "neutral"} "分支")
             (when (and origin-message-id
                        (empty? (model/messages-for-session db selected-id)))
               (dom/span {:className "wb-muted"}
                         "该位置之前没有消息，分支从空会话开始。"))
             (if visible?
               (dom/button {:type "button"
                            :className "wb-button wb-button-small"
                            :aria-label (str "打开来源会话：" title)
                            :onClick (fn [_]
                                       (comp/transact!
                                        this
                                        [(mutations/select-session
                                          {:session-id session-id})])
                                       (routing/navigate! this :sessions
                                                          session-id)
                                       ;; The baseline normally carries the
                                       ;; source history; request one only if
                                       ;; the local table actually lacks the
                                       ;; advertised messages.
                                       (ensure-session-messages!
                                        this session-id))}
                           (str "来源会话：" title))
               (dom/span {:className "wb-muted"}
                         (str "来源会话已删除或不可见（" session-id "）"))))))

(defn- SearchControlsHost
  "React bridge that subscribes to the Fulcro state atom.  `session-page` is a
  plain function, not a Fulcro component, so reading `current-state` once
  would freeze the search selection; this host re-renders on every database
  change and passes the same `:ui/search-state` source to the control."
  [js-props]
  (let [app (aget js-props "app")
        session-id (aget js-props "sessionId")
        services (aget js-props "services")
        disabled? (aget js-props "disabled")
        on-change! (aget js-props "onChange")
        [db set-db!] (react/useState (application/current-state app))
        [service-id set-service-id!]
        (react/useState
         (some-> (get-in db [:ui/search-state session-id :service-id])
                 str not-empty))
        watch-key (str "hc-search-controls-" session-id)
        change!
        (fn [change]
          (when-let [sid (:service-id change)]
            (set-service-id! sid)
            (reset! search-view/selected-service-id sid))
          (when (fn? on-change!) (on-change! change)))]
    (react/useEffect
     (fn []
       (let [state-atom (:com.fulcrologic.fulcro.application/state-atom app)
             watch (fn [_ _ _ new] (set-db! new))]
         (add-watch state-atom watch-key watch)
         (fn [] (remove-watch state-atom watch-key))))
     #js [session-id])
    (let [state (assoc (search-model/state-for db session-id)
                       :service-id service-id)]
      (search-view/search-controls
       {:state state
        :value service-id
        :services services
        :error (search-model/selection-error db state)
        :disabled? disabled?
        :on-change! change!}))))

(defn session-page
  [this props]
  (let [db (application/current-state this)
        sessions (model/filter-sessions (model/sessions db)
                                        (:ui/session-filter props))
        selected-id (model/selected-session-id db)
        session (model/session-by-id db selected-id)
        session' (when session (model/session-with-model session))
        messages (if selected-id (model/messages-for-session db selected-id) [])
        ;; Keep the local stream honest.  The conversation surface derives
        ;; "unattended run" from the session summary itself, so forcing
        ;; `:streaming?` here would hide the "停止无人值守运行" label.
        stream-state (or (get (:ui/stream props) selected-id) (stream/initial))
        draft (get (:ui/draft props) selected-id)
        materials-state (or (:ui/materials db) (materials/initial-state))
        connection (:ui/connection props)
        search-state (search-model/state-for
                      {:ui/search-state (:ui/search-state props)}
                      selected-id)
        search-services (search-model/services db)
        search-error (search-model/selection-error db search-state)
        ;; `:run-error` is the server's active-session error.  A local
        ;; selection may point elsewhere, so only the session's own
        ;; `:last-error` is safe in that case.
        run-error (or (when (= selected-id (model/field db :session/active-id))
                        (model/field db :run-error))
                      (model/field session :last-error))
        run-error-text (when run-error
                         (or (search-model/command-error-label {:error run-error})
                             (model/field run-error :message)
                             (when (string? run-error) run-error)
                             "本轮未完成；请查看错误详情"))
        layout (a11y/layout-for-width (:ui/viewport-width props))
        callbacks {:send! (fn [{:keys [session-id snapshot]}]
                            (let [guard (model/send-guard materials-state)]
                              (cond
                                (and (not= :off (:mode search-state)) search-error)
                                (commands/show-notice! this :warn search-error)

                                guard
                                (commands/show-notice! this :warn (:message guard))

                                :else
                                (let [search-state
                                      (assoc search-state
                                             :service-id
                                             (or @search-view/selected-service-id
                                                 (:service-id search-state)))]
                                  (commands/send-snapshot!
                                   this session-id
                                   (assoc (materials/attach-turn-selection
                                           materials-state snapshot)
                                          :search (search-model/command-payload
                                                   search-state
                                                   (:text snapshot))))))))
                   :stop! (fn [{:keys [session-id]}]
                            (commands/stop-run! this session-id :operator-button))
                   :edit-draft! (fn [change]
                                  (conversation-bridge/edit-draft!
                                   (comp/any->app this) change))
                   :insert-transcript! (fn [{:keys [session-id text insert-at]}]
                                         (let [db (application/current-state this)
                                               change (model/insert-transcript-change
                                                       (draft/draft (:ui/draft db)
                                                                    session-id)
                                                       session-id text insert-at
                                                       (str (js/Date.)))]
                                           (when change
                                             (conversation-bridge/edit-draft!
                                              (comp/any->app this) change))))
                   :branch! (fn [{:keys [session-id message-id]}]
                              (commands/branch-session! this session-id message-id nil))
                   :history/edit-message! (fn [{:keys [session-id message-id content attachments citations]}]
                                            (commands/edit-message! this session-id message-id
                                                                    content attachments citations))
                   :regenerate! (fn [{:keys [session-id message-id]}]
                                  (commands/regenerate! this session-id message-id))
                   :history/select-version! (fn [{:keys [session-id message-id version-id]}]
                                               (commands/select-version! this session-id message-id version-id))
                   :history/branch! (fn [{:keys [session-id before-message-id]}]
                                      (commands/branch-session! this session-id before-message-id nil))
                   :history/set-boundary! (fn [{:keys [session-id message-id]}]
                                             (commands/set-context-boundary! this session-id message-id))
                   :history/clear-boundary! (fn [{:keys [session-id]}]
                                              (commands/clear-context-boundary! this session-id))
                   :history/prepare-compression! (fn [{:keys [session-id message-ids summary]}]
                                                    (commands/prepare-compression! this session-id message-ids summary))
                   :history/apply-compression! (fn [{:keys [session-id compression-id summary]}]
                                                  (commands/apply-compression! this session-id compression-id summary))
                   :history/undo-compression! (fn [{:keys [session-id compression-id]}]
                                                 (commands/undo-compression! this session-id compression-id))
                   :history/create-from-summary! (fn [{:keys [session-id compression-id]}]
                                                   (commands/create-session-from-summary!
                                                    this session-id compression-id nil))
                   :history/pin-attachment! (fn [{:keys [session-id attachment-id]}]
                                               (commands/pin-attachment! this session-id attachment-id))
                   :history/unpin-attachment! (fn [{:keys [session-id attachment-id]}]
                                                 (commands/unpin-attachment! this session-id attachment-id))
                   :submit-ask! (fn [{:keys [session-id ask-id text selected]}]
                                  (commands/answer-ask! this {:session-id session-id
                                                              :ask-id ask-id
                                                              :text text
                                                              :selected selected}))
                   :skip-ask! (fn [{:keys [session-id ask-id]}]
                                (conversation-bridge/skip-ask!
                                 (comp/any->app this)
                                 {:session-id session-id
                                  :ask-id ask-id}))
                   :scroll! (fn [{:keys [session-id] :as observation}]
                              (conversation-bridge/scroll!
                               (comp/any->app this) session-id observation))
                   :resync! (fn [_]
                              (commands/request-resync! this :operator-conversation))
                   :settings! (fn [_]
                                (routing/navigate! this :settings nil))
                   :aux! (fn [_]
                           (comp/transact!
                            this
                            [(mutations/toggle-aux
                              {:open? true
                               :trigger-id "hc-header-action-open-aux"})]))
                   :fix! (fn [{:keys [intent]}]
                           (if (= :check-connection intent)
                             (commands/request-resync! this :operator-conversation)
                             (case intent
                               :select-model
                               (do
                                 (settings-bridge/dispatch!
                                  {:type :settings/select-section
                                   :section :models})
                                 (commands/show-notice!
                                  this :warn
                                  "请在设置页选择可用模型；当前草稿已保留。")
                                 (routing/navigate! this :settings nil))
                               :fix-attachments
                               (commands/show-notice!
                                this :warn
                                "仍有附件未准备好；请在会话输入区处理。")
                               (commands/show-notice!
                                this :warn
                                "当前操作需要服务器设置命令面。"))))}
        conversation-props {:session-id selected-id
                            :session session'
                            :messages messages
                            :draft (or draft {:text "" :version 0})
                            :stream stream-state
                            :ask (asks-for-session props selected-id)
                            :command (conversation-commands (:ui/command props))
                            :connection connection
                            :search-state search-state
                            :blocked (:ui/blocked props)
                            :find-seq (:ui/find-seq props)
                            :layout layout
                            :platform (platform (:ui/platform props))
                            :callbacks callbacks}]
    (dom/div {:className "wb-session-layout"}
             (session-list this props sessions selected-id)
             (dom/section {:className "wb-conversation" :aria-label "会话内容"}
                          (branch-origin-banner this db selected-id)
                          (if selected-id
                            (dom/div {:className "wb-conversation-mount"}
                                     (when (:active? (:ui/nudge props))
                                       (dom/div {:className "wb-badge"
                                                 :data-tone "warn"
                                                 :role "status"
                                                 :style {:margin "8px 16px"}}
                                                "自动继续中（无人值守）"))
                                     (when run-error-text
                                       (dom/div {:className "wb-error"
                                                 :role "alert"
                                                 :style {:padding "8px 16px"}}
                                                run-error-text))
                                     (react/createElement
                                      SearchControlsHost
                                      #js {:app (comp/any->app this)
                                           :sessionId selected-id
                                           :services search-services
                                           :disabled (not (:synced? connection))
                                           :onChange (fn [changes]
                                                       (comp/transact!
                                                        this
                                                        [(mutations/update-search-state
                                                          {:session-id selected-id
                                                           :changes changes})]))})
                                     (react/createElement
                                      ConversationWithAttachments
                                      ;; Key by session only.  A send receipt
                                      ;; must not remount the conversation
                                      ;; while its run is streaming; the
                                      ;; surface adopts an explicitly cleared
                                      ;; draft on its own.
                                      #js {:key selected-id
                                           :props {:conversation-props conversation-props
                                                   :model (:model session')
                                                   :base-url nil}}))
                            (common/empty-state
                             "尚未选择会话"
                             (str (model/connection-label connection)
                                  "。"
                                  (when-not (:synced? connection)
                                    " 连接建立后，服务器当前态才会出现在这里。"))))))))
