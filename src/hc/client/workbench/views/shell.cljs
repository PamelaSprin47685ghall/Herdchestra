(ns hc.client.workbench.views.shell
  "Root workbench composition.

  One Fulcro root wraps one Framework7 App/View/Page.  Navigation has a single
  authority: `:ui/route` in Fulcro, written by `hc.client.workbench.routing`.
  Wide viewports keep navigation and an initially open detail column; narrow
  viewports turn them into drawers with a backdrop and focus-return target."
  (:require ["react" :as react]
            [clojure.string :as str]
            [com.fulcrologic.fulcro.application :as application]
            [com.fulcrologic.fulcro.components :as comp]
            [com.fulcrologic.fulcro.dom :as dom]
            [hc.client.conversation.a11y :as a11y]
            [hc.client.conversation.draft :as draft]
            [hc.client.files.browser :as files]
            [hc.client.sessions.controls :as session-controls]
            [hc.client.workbench.commands :as commands]
            [hc.client.workbench.model :as model]
            [hc.client.workbench.mutations :as mutations]
            [hc.client.workbench.routing :as routing]
            [hc.client.workbench.views.assistants :as assistants]
            [hc.client.workbench.views.board :as board]
            [hc.client.workbench.views.browser :as browser]
            [hc.client.workbench.views.common :as common]
            [hc.client.workbench.views.materials :as materials]
            [hc.client.workbench.views.session :as session]
            [hc.client.workbench.views.settings :as settings]))

(def glyphs
  {:sessions "◎"
   :board "▦"
   :assistants "◇"
   :materials "▤"
   :settings "⚙"})

(defn- navigate! [this route session-id]
  (routing/navigate! this route session-id))

(defn- FocusEffect
  "Apply a focus request after React commits the new drawer state."
  [js-props]
  (let [target (.-target js-props)
        nonce (.-nonce js-props)]
    (react/useEffect
     (fn []
       (common/focus-id! target)
       js/undefined)
     #js [target nonce]))
  nil)

(defn- topbar
  [this props]
  (let [connection (:ui/connection props)
        route (:ui/route props)
        sidebar-open (:ui/sidebar-open props)
        aux-open (:ui/aux-open props)]
    (dom/header {:className "wb-topbar"}
                (dom/button {:type "button"
                             :id "wb-menu"
                             :className "wb-icon-button wb-menu-button"
                             :aria-label (if sidebar-open "关闭导航" "打开导航")
                             :aria-expanded (if sidebar-open "true" "false")
                             :aria-controls "wb-nav-drawer"
                             :onClick (fn [_]
                                        (comp/transact!
                                         this
                                         [(mutations/toggle-sidebar
                                           {:trigger-id "wb-menu"})]))}
                            "☰")
                (dom/div {:className "wb-brand"}
                         (dom/strong {} "Herdchestra")
                         (dom/span {} "工作台"))
                (dom/div {:className "wb-topbar-spacer"})
                (dom/button {:type "button"
                             :className "wb-badge"
                             :data-tone (name (:tone connection))
                             :aria-label (str "连接状态：" (:phase-label connection) "；打开设置")
                             :onClick (fn [_] (navigate! this :settings nil))}
                            (dom/span {:aria-hidden "true"} "●")
                            (dom/span {} (:phase-label connection)))
                (dom/button {:type "button"
                             :id "wb-aux-toggle"
                             :className "wb-icon-button"
                             :aria-label (if aux-open "关闭详情" "打开详情")
                             :aria-expanded (if aux-open "true" "false")
                             :aria-controls "wb-aux"
                             :onClick (fn [_]
                                        (comp/transact!
                                         this
                                         [(mutations/toggle-aux
                                           {:trigger-id "wb-aux-toggle"})]))}
                            "≡"))))

(defn- nav
  [this props]
  (let [route (:ui/route props)
        connection (:ui/connection props)
        sidebar-open (:ui/sidebar-open props)
        db (application/current-state this)
        overlay? (a11y/overlay-layout? (:ui/viewport-width props))
        query (or (:ui/session-filter props) "")
        sessions (model/filter-sessions (model/sessions db) query)
        deleted (session-controls/deleted-sessions (:ui/session-list props))
        selected-id (model/selected-session-id db)]
    (dom/nav {:id "wb-nav-drawer"
              :className "wb-nav"
              :data-open (if sidebar-open "true" "false")
              :tabIndex -1
              :aria-label "顶层工作区"}
                ;; The drawer covers the topbar trigger at phone widths, so
                ;; the open drawer needs its own close control.  Wide layout
                ;; keeps the navigation as a permanent column and does not
                ;; render this button.
                (dom/div {:className "wb-nav-head"}
                         (dom/div {:className "wb-nav-section"} "工作区")
                         (when overlay?
                           (dom/button
                            {:type "button"
                             :id "wb-nav-close"
                             :className "wb-icon-button wb-button-small"
                             :aria-label "关闭导航"
                             :onClick
                             (fn [_]
                               (comp/transact!
                                this
                                [(mutations/close-overlay {:id :nav})]))}
                            "×")))
                (for [{:keys [route label meta icon]} model/nav-items]
                  (dom/button {:key (name route)
                               :type "button"
                               :className "wb-nav-item"
                               :aria-current (if (= route (:ui/route props)) "page" nil)
                               :onClick (fn [_] (navigate! this route nil))}
                              (common/icon (get glyphs route icon))
                              (dom/span {:className "wb-nav-label"}
                                        (dom/strong {} label)
                                        (dom/span {:className "wb-nav-meta"} meta))))
                ;; The drawer layout hides the session column.  Without this
                ;; block a 360px window could not switch or create sessions.
                (when overlay?
                  (dom/div {:className "wb-nav-sessions"}
                           (dom/div {:className "wb-nav-section"} "会话")
                           (dom/input {:id "wb-nav-session-search"
                                       :type "search"
                                       :className "wb-input wb-session-search"
                                       :placeholder "过滤会话"
                                       :aria-label "过滤会话"
                                       :value query
                                       :onChange
                                       (fn [event]
                                         (comp/transact!
                                          this
                                          [(mutations/set-session-filter
                                            {:value (.. event -target -value)})]))})
                           (dom/button {:type "button"
                                        :id "wb-nav-new-session"
                                        :className "wb-button wb-button-small wb-nav-new"
                                        :aria-label "新建会话"
                                        :onClick
                                        (fn [_]
                                          (routing/navigate! this :sessions nil)
                                          (commands/create-session!
                                           this {:title "新会话"}))}
                                       "新建会话")
                           (if (seq sessions)
                             (dom/ul {:className "wb-nav-session-list"}
                                     (for [session sessions]
                                       (dom/li {:key (:id session)}
                                               (dom/button
                                                {:type "button"
                                                 :className "wb-nav-session"
                                                 :aria-current
                                                 (if (= (:id session) selected-id)
                                                   "true"
                                                   "false")
                                                 :onClick
                                                 (fn [_]
                                                   (session/select-session!
                                                    this (:id session)))}
                                                (dom/strong {} (:title session))
                                                (let [details
                                                      (common/session-detail
                                                       (:status session)
                                                       (:updated-at session)
                                                       nil)]
                                                  (when (seq details)
                                                    (dom/small {} details)))))))
                             (dom/p {:className "wb-muted"}
                                    (if (str/blank? query)
                                      "没有可见会话"
                                      "没有匹配会话")))))
                (when overlay?
                  (dom/details {:className "wb-deleted-sessions wb-nav-deleted"}
                               (dom/summary {}
                                            (str "已删除会话"
                                                 (when (seq deleted)
                                                   (str "（" (count deleted) "）"))))
                               (dom/div {:className "wb-row"}
                                        (dom/button
                                         {:type "button"
                                          :id "wb-nav-load-deleted"
                                          :className "wb-button wb-button-small"
                                          :onClick (fn [_]
                                                     (commands/session-list!
                                                      this {:include-deleted? true}))}
                                         "加载已删除会话"))
                               (for [session deleted]
                                 (dom/div {:className "wb-nav-deleted-row"
                                           :key (:id session)}
                                          (dom/strong {} (:title session))
                                          (dom/small {}
                                                     (str "删除时间："
                                                          (or (:deleted-at session) "—")
                                                          " · 预计清理：删除后 30 天"))
                                          (dom/button
                                           {:type "button"
                                            :className "wb-button wb-button-small"
                                            :aria-label (str "恢复：" (:title session))
                                            :onClick (fn [_]
                                                       (commands/session-metadata!
                                                        this (:id session)
                                                        :session/restore {}))}
                                           "恢复")
                                          (dom/button
                                           {:type "button"
                                            :className "wb-button wb-button-small"
                                            :aria-label (str "永久删除：" (:title session))
                                            :onClick (fn [_]
                                                       (when (js/confirm
                                                              "永久删除这份已删除会话？此操作不可撤销；工作区文件不受影响。")
                                                         (commands/session-metadata!
                                                          this (:id session)
                                                          :session/purge
                                                          {:confirm? true})))}
                                           "永久删除")))))
                (dom/div {:className "wb-nav-section"} "服务器")
                (common/facts
                 [["阶段" (:phase-label connection)]
                  ["epoch" (:epoch connection)]
                  ["seq" (:seq connection)]])
                (dom/p {:className "wb-muted" :style {:font-size "0.78em"}}
                       (:description connection)))))

(defn- aux-content
  [this props]
  (let [db (application/current-state this)
        route (:ui/route props)
        connection (:ui/connection props)
        selected-id (model/selected-session-id db)
        session (when selected-id (model/session-by-id db selected-id))
        summary (when session (model/session-summary session))
        branch (when selected-id (model/branch-origin db selected-id))
        draft (when selected-id (draft/draft (:ui/draft props) selected-id))
        command (when selected-id (model/session-command db selected-id))]
    (case route
      :sessions
      (dom/div {}
               (dom/h2 {} "会话详情")
               (if summary
                 (dom/div {}
                          (common/facts
                           [["标题" (:title summary)]
                            ["会话 ID" (:id summary)]
                            ["状态" (or (common/session-status-label
                                        (:status summary))
                                       "—")]
                            ["消息数" (:message-count summary)]
                            ["模型" (:model-label summary)]
                            ["分支来源"
                             (if branch
                               (if (:visible? branch)
                                 (:title branch)
                                 (str (:session-id branch) "（已删除或不可见）"))
                               "—")]
                            ["草稿版本" (:version draft)]
                            ["保存状态" (or (draft/save-label draft) "已保存")]
                            ["命令" (:status-label command)]])
                          (common/copy-button (:id summary) "复制会话 ID" nil))
                 (common/empty-state "未选择会话"
                                     "选择会话后显示服务器实体与本地草稿事实。")))

      :board
      (dom/div {}
               (dom/h2 {} "看板同步")
               (common/facts
                [["swarm 数" (count (model/swarms db))]
                 ["任务数" (count (model/tasks db))]
                 ["epoch" (:epoch connection)]
                 ["seq" (:seq connection)]
                 ["最近确认" (:synced-at connection)]]))

      :assistants
      (let [roles (into {} (map (juxt :role identity))
                        (model/role-cards db))]
        (dom/div {}
                 (dom/h2 {} "角色与连接")
                 (common/facts
                  [["连接数" (count (model/connection-cards db))]
                   ["主模型" (or (get-in roles [:main :selection-label])
                                 "未设置")]
                   ["任务模型" (or (get-in roles [:task :selection-label])
                                   "未设置")]
                   ["辅助模型" (or (get-in roles [:utility :status])
                                   "未声明")]
                   ["嵌入模型" (or (get-in roles [:embedding :status])
                                   "未声明")]
                   ["视觉模型" (or (get-in roles [:vision :status])
                                   "未声明")]])))

      :materials
      (let [materials-state (:ui/materials props)]
        (dom/div {}
                 (dom/h2 {} "材料概览")
                 (common/facts
                  [["工作区文件" (count (model/workspace-files db))]
                   ["产物" (count (model/artifacts db))]
                   ["长期记忆" (count (:memories materials-state))]
                   ["世界书" (count (:books materials-state))]
                   ["指令卡" (count (:cards materials-state))]
                   ["材料 revision" (:revision materials-state)]
                   ["同步序号" (:seq connection)]])))

      :settings
      (dom/div {}
               (dom/h2 {} "本地状态")
               (common/facts
                [["主题" (common/theme-label (:ui/theme props))]
                 ["字号" (str (:ui/font-scale props) "%")]
                 ["草稿存储" (common/draft-storage-label
                             (:ui/draft-storage props))]
                 ["订阅代号" (:subscription connection)]
                 ["epoch" (:epoch connection)]
                 ["seq" (:seq connection)]]))

      (dom/div {} (dom/h2 {} "详情")))))

(defn- sources-panel
  "Project the source cards the server attached to the selected session."
  [this]
  (let [db (application/current-state this)
        selected-id (model/selected-session-id db)
        sources (if selected-id (model/session-sources db selected-id) [])]
    (dom/div {}
             (dom/h2 {} "来源")
             (if (seq sources)
               (dom/ul {:className "wb-list"}
                       (for [[index source] (map-indexed vector sources)]
                         (dom/li {:key (str index "-" (:label source))
                                  :className "wb-source-row"}
                                 (if (:url source)
                                   (dom/a {:href (:url source)
                                           :target "_blank"
                                           :rel "noopener noreferrer"}
                                          (:label source))
                                   (dom/span {} (:label source)))
                                 (when (:kind source)
                                   (dom/small {}
                                              (let [kind (:kind source)]
                                                (if (keyword? kind)
                                                  (name kind)
                                                  (str kind))))))))
               (dom/p {:className "wb-muted"}
                      "服务器未报告该会话的来源。")))))

(defn- aux
  [this props]
  (let [aux-open (:ui/aux-open props)
        tab (or (:ui/aux-tab props) :details)]
    (dom/aside {:id "wb-aux"
                :className "wb-aux"
                :data-open (if aux-open "true" "false")
                :aria-label "上下文详情"}
                (dom/div {:className "wb-spread"}
                         (dom/strong {} "上下文")
                         (dom/button {:type "button"
                                      :id "wb-aux-close"
                                      :className "wb-icon-button wb-button-small"
                                      :aria-label "关闭详情"
                                      :onClick (fn [_]
                                                 (comp/transact!
                                                  this
                                                  [(mutations/toggle-aux
                                                    {:open? false})]))}
                                     "×"))
                ;; The wide third column is the file/browser/source sidebar,
                ;; not only a facts panel.  The narrow drawer reuses the same
                ;; tabs.
                (common/tabs [{:id :details :label "详情"}
                              {:id :files :label "文件"}
                              {:id :browser :label "浏览器"}
                              {:id :sources :label "来源"}]
                             tab
                             (fn [next]
                               (comp/transact!
                                this
                                [(mutations/set-tab {:group :ui/aux-tab
                                                     :tab next})])))
                (case tab
                  :files (files/files-pane this (:ui/files props))
                  :browser (browser/browser-pane (application/current-state this))
                  :sources (sources-panel this)
                  (aux-content this props)))))

(defn- backdrop
  [this props]
  (let [db (application/current-state this)
        top (peek (model/overlay-stack db))]
    (when (and top
               (a11y/overlay-layout? (:ui/viewport-width props)))
      (dom/button {:type "button"
                   :className "wb-drawer-backdrop"
                   :aria-label "关闭覆盖层"
                   :tabIndex -1
                   :onClick (fn [_]
                              (comp/transact!
                               this
                               [(mutations/close-overlay
                                 {:id (:id top)})]))}))))

(defn- route-view
  [this props]
  (case (:ui/route props)
    :board (board/board-page this props)
    :assistants (assistants/assistants-page this props)
    :materials (materials/materials-page this props)
    :settings (settings/settings-page this props)
    (session/session-page this props)))

(defn- notices
  [this props]
  (common/notice-stack (:ui/notices props)
                       (fn [id]
                         (comp/transact! this [(mutations/dismiss-notice {:id id})]))))

(defn workbench-content
  "The responsive shell inside the Framework7 page.

  Kept separate from the Framework7 wrappers so consumer tests can mount the
  real chrome in jsdom.  The caller supplies the Fulcro component/app as
  `this`; every control transacts against that same root."
  [this props]
  (dom/div {:className "wb-shell"}
           (react/createElement FocusEffect
                                #js {:target (:ui/focus-target props)
                                     :nonce (:ui/focus-seq props)})
           (topbar this props)
           (dom/div {:className "wb-body"
                     :data-aux-open (if (:ui/aux-open props) "true" "false")}
                    (nav this props)
                    (dom/main {:className "wb-main" :id "wb-main"}
                              (route-view this props))
                    (aux this props))
           (backdrop this props)
           (notices this props)
           (dom/div {:className "wb-sr-only"
                     :role "status"
                     :aria-live "polite"
                     :aria-atomic "true"}
                    (:description (:ui/connection props)))))

(defn workbench
  "Root render.  Framework7 App wraps the single View/Page; the workbench
  content is a normal Flexbox shell inside the page."
  [this props]
  (common/ui-app
   #js {:theme "auto" :name "Herdchestra" :id "hc-app"}
   (common/ui-view
    #js {:main true :url "/" :browserHistory false}
    (common/ui-page
     #js {:pageContent false}
     (workbench-content this props)))))
