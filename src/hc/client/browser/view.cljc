(ns hc.client.browser.view
  "Pure view tree for the managed-browser workbench pane.

  The tree is data: `{:tag :attrs :events :children}`.  It renders server
  observations and page cards, and dispatches only model actions.  It never
  invents a page state or marks a command complete."
  (:require [clojure.string :as str]
            [hc.client.browser.model :as model]))

(defn- el
  ([tag attrs] {:tag tag :attrs attrs :children []})
  ([tag attrs & children]
   {:tag tag :attrs attrs :children (vec children)}))

(defn- text
  [value]
  (str (or value "")))

(defn- action
  ([type] {:type type})
  ([type k v & kvs]
   (apply assoc {:type type} k v kvs)))

(defn- on-click
  [type & kvs]
  {:on-click {:action (apply action type kvs)}})

(defn- on-change
  [type & kvs]
  {:on-change {:action (apply action type kvs)
               :value :target-value}})

(defn- button
  [label act & [attrs]]
  (el :button
      (merge {:type "button"
              :class "wb-button wb-button-small"
              :events {:on-click {:action act}}}
             attrs)
      label))

(defn- primary-button
  [label act & [attrs]]
  (button label act (merge {:class "wb-button wb-button-primary wb-button-small"}
                           attrs)))

(defn- danger-button
  [label act & [attrs]]
  (button label act (merge {:class "wb-button wb-button-danger wb-button-small"}
                           attrs)))

(defn- badge
  [label tone]
  (el :span {:class "wb-badge" :data-tone (name (or tone :neutral))}
      (text label)))

(defn- field
  [label control & [hint]]
  (el :label {:class "hc-browser-field"}
      (el :span {:class "hc-browser-field-label"} (text label))
      control
      (when hint (el :small {:class "wb-muted"} (text hint)))))

(defn- text-input
  [value act & [attrs]]
  (el :input
      (merge {:type "text"
              :value (text value)
              :autocomplete "off"
              :class "hc-browser-input"
              :events {:on-change {:action act :value :target-value}}}
             attrs)))

(defn- select-input
  [value options act]
  (el :select {:class "hc-browser-select"
               :value (name (or value ""))
               :events {:on-change {:action act :value :target-value}}}
      (for [[label option-value] options]
        (el :option {:value (name option-value) :key (name option-value)}
            (text label)))))

(defn- scope-options
  []
  [["当前标签页" :current-tab]
   ["会话标签页" :session-tabs]
   ["指定站点" :site]])

(defn- limit-label
  [k]
  (case k
    :sensitive-detection "敏感字段识别"
    :screenshot "截图"
    :observation "页面观察"
    :iframes "iframe"
    :coordinates "元素定位"
    :external-browser "外部浏览器"
    :permission "网页读取许可"
    :outcome-unknown "未知结果"
    :page-content "页面内容"
    (name k)))

(defn- limit-value
  [v]
  (if (keyword? v) (name v) (str v)))

(defn- provider-label
  [provider]
  (cond
    (keyword? provider) (name provider)
    (string? provider) provider
    :else "未标注"))

(defn- permission-section
  [state]
  (let [permission (:permission state)
        form (:permission-form state)
        enabled? (:enabled? permission)]
    (el :section {:class "hc-browser-section" :aria-label "浏览器权限"}
        (el :div {:class "hc-browser-section-head"}
            (el :h2 {} "浏览器权限")
            (badge (if enabled? "已启用" "未启用")
                   (if enabled? :ok :neutral)))
        (if enabled?
          (el :div {}
              (el :div {:class "hc-browser-facts"}
                  (el :span {} (str "范围：" (name (or (:scope permission) :current-tab))))
                  (el :span {} (str "站点：" (if (seq (:sites permission))
                                              (str/join ", " (:sites permission))
                                              "—")))
                  (el :span {} (str "私密页读取："
                                    (if (:private-read-approved? permission)
                                      "已确认" "待确认")))
                  (el :span {} (str "接收模型服务："
                                    (provider-label (:model-provider permission)))))
              (el :div {:class "hc-browser-actions"}
                  (when-not (:private-read-approved? permission)
                    (primary-button "确认私密页读取"
                                    (action :browser/approve-private-read)))
                  (danger-button "停用浏览器"
                                 (action :browser/permission-disable))))
          (el :div {}
              (el :p {:class "wb-muted"}
                  "浏览器工具默认关闭。启用后，模型可读取选定页面内容；这些内容会发送给当前模型服务。登录态页面可能包含个人信息。")
              (when (:offline? state)
                (el :p {:class "wb-muted"}
                    "当前未配置 CDP 端点：仍可启用权限并查看地址栏；打开页面、观察与操作会如实报告未连接，不会伪装成功。"))
              (el :div {:class "hc-browser-form"}
                  (field "允许范围"
                         (select-input (:scope form) (scope-options)
                                       (action :browser/set-permission-scope)))
                  (field "站点（逗号或空格分隔）"
                         (text-input (:site-text form)
                                     (action :browser/set-permission-site)
                                     {:placeholder "example.com"
                                      :id "hc-browser-site"})
                         "站点范围必须填写至少一个域名。")
                  (primary-button "启用浏览器"
                                  (action :browser/permission-enable)
                                  {:id "hc-browser-enable"})))))))

(defn- tab-label
  [tab]
  (or (:title tab)
      (:url tab)
      (:tab-id tab)
      "未命名页面"))

(defn- tabs-section
  [state]
  (when (seq (:tab-order state))
    (let [filter-text (str/lower-case (str (:tab-filter state)))
          all-order (:tab-order state)
          order (if (str/blank? filter-text)
                  all-order
                  (filterv (fn [tab-id]
                             (let [tab (get-in state [:tabs tab-id])
                                   hay (str/lower-case
                                        (str (tab-label tab) " "
                                             (:url tab) " " (:site tab)))]
                               (str/includes? hay filter-text)))
                           all-order))]
      (el :section {:class "hc-browser-section" :aria-label "浏览器页面"}
          (el :div {:class "hc-browser-section-head"}
              (el :h3 {} "页面")
              (badge (str (count all-order) " 个标签") :neutral))
          (when (> (count all-order) 3)
            (text-input (:tab-filter state)
                        (action :browser/set-tab-filter)
                        {:placeholder "按标题或网址搜索标签页"
                         :aria-label "搜索标签页"
                         :id "hc-browser-tab-search"}))
          (el :div {:class "hc-browser-tabs" :role "tablist"}
              (for [tab-id order
                    :let [tab (get-in state [:tabs tab-id])
                          active? (= tab-id (:active-tab-id state))
                          closed? (= :closed (:status tab))]]
                (el :div {:class (str "hc-browser-tab"
                                      (when active? " hc-browser-tab-active")
                                      (when closed? " hc-browser-tab-closed"))
                          :key tab-id}
                    (button (tab-label tab)
                            (action :browser/select-tab :tab-id tab-id)
                            {:class (str "wb-button wb-button-small"
                                         (when active? " wb-button-primary"))
                             :aria-current (when active? "page")})
                    (let [authorized? (model/tab-authorized-for? state tab)]
                      (badge (if authorized? "模型可读" "未授权")
                             (if authorized? :ok :warn)))
                    (when (:loading? tab)
                      (badge "加载中" :warn))
                    (when closed?
                      (badge "已关闭" :neutral))
                    (if closed?
                      (button "重开"
                              (action :browser/reopen-tab :tab-id tab-id)
                              {:aria-label (str "重开 " (tab-label tab))
                               :disabled (not (model/command-allowed? state))})
                      (button "×"
                              (action :browser/close-tab :tab-id tab-id)
                              {:aria-label (str "关闭 " (tab-label tab))
                               :disabled (not (model/command-allowed? state))})))))
          (when (empty? order)
            (el :p {:class "wb-muted"} "没有匹配的标签页。"))))))

(defn- takeover-section
  [state]
  (let [tab (model/active-tab state)
        takeover (or (:takeover tab) :agent-active)
        reason (:takeover-reason tab)]
    (when tab
      (el :section {:class "hc-browser-section hc-browser-takeover"
                    :aria-label "人工接管"}
          (el :div {:class "hc-browser-section-head"}
              (el :h3 {} "人工接管")
              (badge (get model/takeover-labels takeover (name takeover))
                     (case takeover
                       :agent-active :ok
                       :read-only :warn
                       :operator :warn
                       :paused-manual :warn
                       :sensitive-paused :danger
                       :neutral)))
          (when reason
            (el :p {:class "wb-muted"} (str "原因：" (name reason))))
          (el :div {:class "hc-browser-actions"}
              (if (= takeover :agent-active)
                (el :div {:class "hc-browser-actions"}
                    (button "我来操作" (action :browser/takeover)
                            {:id "hc-browser-takeover"})
                    (button "报告手动操作" (action :browser/operator-input)
                            {:id "hc-browser-operator-input"}))
                (el :div {:class "hc-browser-actions"}
                    (select-input (:resume-mode state)
                                  [["交回代理" :agent] ["只读交回" :read-only]]
                                  (action :browser/set-resume-mode))
                    (primary-button "恢复代理" (action :browser/resume)
                                    {:id "hc-browser-resume"})
                    (button "报告手动操作" (action :browser/operator-input)
                            {:id "hc-browser-operator-input"}))))))))

(defn- node-label
  [node]
  (str/join " "
            (remove str/blank?
                    [(or (:ref node) "?")
                     (some-> (:tag node) name)
                     (some-> (:type node) name)
                     (or (:label node) (:field-name node) (:placeholder node))])))

(defn- observation-section
  [state]
  (if-let [observation (:observation state)]
    (let [selected (:selected-ref state)
          selected-node (model/selected-node state)
          sensitive? (true? (:sensitive? selected-node))]
      (el :section {:class "hc-browser-section hc-browser-observation"
                    :aria-label "页面观察"}
          (el :div {:class "hc-browser-section-head"}
              (el :h3 {} "页面观察")
              (when (:private? observation)
                (badge "可能包含个人信息" :warn))
              (when (pos? (long (or (:iframes observation) 0)))
                (badge "iframe 未读取" :warn)))
          (el :div {:class "hc-browser-facts"}
              (el :span {} (str "标题：" (or (:title observation) "—")))
              (el :span {} (str "地址：" (or (:url observation) "—")))
              (el :span {} (str "观察代号：" (or (:observation-id observation) "—")))
              (el :span {} (str "页面代次：" (or (:generation observation) "—"))))
          (el :pre {:class "hc-browser-text"} (text (:text observation)))
          (el :h4 {} "可操作元素")
          (if (seq (:nodes observation))
            (el :div {:class "hc-browser-nodes"}
                (for [node (:nodes observation)]
                  (el :div {:class (str "hc-browser-node"
                                        (when (= selected (:ref node))
                                          " hc-browser-node-selected")
                                        (when (:sensitive? node)
                                          " hc-browser-node-sensitive"))
                            :key (:ref node)}
                      (el :span {:class "hc-browser-node-label"} (node-label node))
                      (when (:sensitive? node)
                        (badge "敏感，操作员输入" :danger))
                      (button (if (= selected (:ref node)) "已选择" "选择")
                              (action :browser/select-node :ref (:ref node))))))
            (el :p {:class "wb-muted"} "本次观察没有可见的可操作元素。"))
          (el :h4 {} "动作")
          (el :div {:class "hc-browser-form"}
              (field "普通文本"
                     (text-input (:type-text state)
                                 (action :browser/set-type-text)
                                 {:placeholder "只输入普通文本；密码、验证码与支付凭据由操作员输入"})
                     (when sensitive?
                       "选中字段被标记为敏感，不能在聊天卡中输入。"))
              (el :div {:class "hc-browser-actions"}
                  (primary-button "输入选中" (action :browser/type)
                                  {:disabled (or sensitive?
                                                 (nil? selected-node)
                                                 (not (model/agent-action-allowed? state)))
                                   :id "hc-browser-type"})
                  (button "点击选中" (action :browser/click)
                          {:disabled (or (nil? selected-node)
                                         (not (model/agent-action-allowed? state)))
                           :id "hc-browser-click"})
                  (button "提交选中" (action :browser/submit)
                          {:disabled (or (nil? selected-node)
                                         (not (model/agent-action-allowed? state)))
                           :id "hc-browser-submit"})
                  (button "回车" (action :browser/press :key "Enter")
                          {:disabled (or (nil? selected-node)
                                         (not (model/agent-action-allowed? state)))
                           :id "hc-browser-enter"})
                  (button "Tab" (action :browser/press :key "Tab")
                          {:disabled (or (nil? selected-node)
                                         (not (model/agent-action-allowed? state)))
                           :id "hc-browser-tab"})
                  (button "Esc" (action :browser/press :key "Escape")
                          {:disabled (or (nil? selected-node)
                                         (not (model/agent-action-allowed? state)))
                           :id "hc-browser-escape"})
                  (button "滚动" (action :browser/scroll)
                          {:disabled (or (nil? selected-node)
                                         (not (model/agent-action-allowed? state)))
                           :id "hc-browser-scroll"})))
          (el :p {:class "wb-muted"}
              "动作引用本次观察；页面跳转、弹窗或手动操作后，服务器会拒绝旧目标并要求重新观察。")))
    (el :section {:class "hc-browser-section"}
        (el :h3 {} "页面观察")
        (el :p {:class "wb-muted"} "先打开页面并点击“观察”，再选择元素执行动作。"))))

(defn- card-result
  [card]
  (let [result (:result card)
        page-status (or (:page-status result) (:page-status card))]
    (cond
      (nil? result) "—"
      (map? result)
      (let [status (or (:status result) (if (:error result) :failed :ok))
            error (:error result)
            page-error (:page-error result)]
        (str (name status)
             (when page-status (str " / " (name page-status)))
             (when page-error (str " / " (name page-error)))
             (when error (str " / " (or (:code error) "") " "
                              (or (:message error) "")))))
      :else (str result))))

(defn- screenshot
  [card]
  (let [shot (:screenshot card)
        id (or (:id shot) (:blob-id shot))]
    (el :div {:class "hc-browser-shot"}
        (if id
          (el :img {:src (str "/blobs/" id)
                    :alt "历史页面截图"
                    :class "hc-browser-shot-image"})
          (el :span {:class "wb-muted"}
              (str "截图不可用"
                   (when-let [reason (or (:reason shot) (:status shot))]
                     (str "：" (name reason))))))
        (el :small {:class "wb-muted"} "历史截图，不是当前页面。"))))

(defn- cards-section
  [state]
  (let [cards (:cards state)
        meta (:cards-meta state)]
    (el :section {:class "hc-browser-section" :aria-label "页面卡"}
        (el :div {:class "hc-browser-section-head"}
            (el :h3 {} "页面卡")
            (when (:truncated? meta)
              (badge (str "已丢弃 " (:dropped meta) " 条较早记录") :warn)))
        (if (seq cards)
          (el :ol {:class "hc-browser-cards"}
              (for [card cards]
                (el :li {:class "hc-browser-card" :key (:card-id card)}
                    (el :div {:class "hc-browser-card-head"}
                        (el :strong {} (str "#" (:seq card) " "
                                            (get model/action-labels
                                                 (:action card)
                                                 (name (or (:action card) :action)))))
                        (el :span {:class "wb-muted"} (or (:at card) "—")))
                    (el :div {:class "hc-browser-facts"}
                        (el :span {} (str "站点：" (or (:site card) "—")))
                        (el :span {} (str "目标：" (or (:target-summary card) "—"))))
                    (el :div {:class "hc-browser-card-result"}
                        (card-result card))
                    (when-let [tab-id (:tab-id card)]
                      (button "回到页面"
                              (action :browser/select-tab :tab-id tab-id)))
                    (screenshot card))))
          (el :p {:class "wb-muted"} "还没有页面卡。")))))

(defn- limits-section
  [state]
  (let [limits (:limits state)]
    (el :section {:class "hc-browser-section hc-browser-limits"
                  :aria-label "浏览器限制"}
        (el :h3 {} "边界与限制")
        (el :ul {}
            (for [[k v] (sort-by key limits)]
              (el :li {:key (name k)}
                  (str (limit-label k) "：" (limit-value v)))))
        (el :li {} "页面内容可能包含个人信息；截图是历史视图。")
        (el :li {} "浏览器权限不随重启、分支或迁移继承。"))))

(defn- status-section
  [state]
  (let [commands (->> (:commands state)
                      vals
                      (sort-by :queued-at)
                      reverse)
        problems (filter #(contains? #{:failed :refused} (:status %)) commands)
        visible (take 4 (distinct (concat problems commands)))]
    (when (seq visible)
      (el :div {:class "hc-browser-command" :role "status"}
          (for [command visible]
            (el :div {:key (:command-id command)}
                (el :span {} (str (get model/action-labels (:kind command)
                                       (name (:kind command)))
                                  "："
                                  (get model/status-labels (:status command)
                                       (name (:status command)))))
                (when-let [message (:message command)]
                  (el :span {:class "wb-error"} (text message)))))))))

(defn- error-section
  [state]
  (when-let [error (:last-error state)]
    (el :div {:class "hc-browser-warning" :role "alert"}
        (el :span {} (text (or (:message error) error)))
        (button "关闭" (action :browser/dismiss-error)))))

(defn- address-section
  [state]
  (let [tab (model/active-tab state)
        enter-allowed? (model/agent-action-allowed? state)]
    (el :section {:class "hc-browser-section" :aria-label "地址与导航"}
        (el :div {:class "hc-browser-address-row"}
            ;; Inline events keep the address input independent of the
            ;; generic `text-input` wrapper.  The address bar must render even
            ;; when no CDP endpoint is configured or no tab is open yet.
            (el :input
                (cond-> {:type "text"
                         :value (text (:address state))
                         :autocomplete "off"
                         :class "hc-browser-input hc-browser-address"
                         :placeholder "https://example.com"
                         :aria-label "浏览器地址"
                         :on-change {:action (action :browser/set-address)
                                     :value :target-value}}
                  enter-allowed?
                  (assoc :on-key-down
                         {:action (action :browser/navigate)
                          :key :enter})))
            (el :span {:class "hc-browser-origin"}
                    (str "站点：" (or (:site tab) "—")))
            (primary-button "前往" (action :browser/navigate)
                            {:disabled (not (model/agent-action-allowed? state))
                             :id "hc-browser-navigate"})
            (button "新标签打开" (action :browser/open)
                    {:disabled (not (model/command-allowed? state))
                     :id "hc-browser-open"}))
        (el :div {:class "hc-browser-actions"}
            (button "后退" (action :browser/back)
                    {:disabled (not (model/agent-action-allowed? state))})
            (button "前进" (action :browser/forward)
                    {:disabled (not (model/agent-action-allowed? state))})
            (button "刷新" (action :browser/reload)
                    {:disabled (not (model/agent-action-allowed? state))})
            (button "观察" (action :browser/observe)
                    {:disabled (not (model/read-allowed? state))
                     :id "hc-browser-observe"})
            (button "关闭页面" (action :browser/close-tab)
                    {:disabled (or (nil? (:active-tab-id state))
                                   (not (model/command-allowed? state)))})
            (button (if (:stopped? state) "代理已停止" "停止代理")
                    (action :browser/stop)
                    {:disabled (or (:stopped? state)
                                   (not (model/command-allowed? state)))})))))

(defn browser-panel-tree
  "The complete browser pane tree for STATE."
  [state]
  (let [state (or state (model/initial-state))]
    (el :div {:class "hc-browser" :aria-label "受管浏览器"}
        (el :div {:class "hc-browser-header"}
            (el :div {}
                (el :h2 {} "受管浏览器")
                (el :p {:class "wb-muted"}
                    "地址栏显示服务器报告的真实地址；观察、动作、接管与页面卡都来自服务器当前态。"))
            (el :div {:class "hc-browser-actions"}
                (if (:offline? state)
                  (badge "未配置 CDP" :warn)
                  (badge "实例内浏览器" :neutral))
                (badge (if (model/command-allowed? state)
                         "已同步" "等待同步")
                       (if (model/command-allowed? state) :ok :warn))
                (button "刷新状态" (action :browser/refresh))))
        (error-section state)
        (status-section state)
        (permission-section state)
        (address-section state)
        (tabs-section state)
        (takeover-section state)
        (observation-section state)
        (cards-section state)
        (limits-section state))))
