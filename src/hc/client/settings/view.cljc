(ns hc.client.settings.view
  "Pure hiccup-style tree for the settings workspace.

  The tree carries `:events` descriptors instead of functions.  The React
  adapter resolves an action descriptor to a callback.  Tests can inspect the
  tree on the JVM; the same tree renders the browser UI."
  (:require [clojure.string :as str]
            [hc.client.appearance.view :as appearance-view]
            [hc.client.model-auth :as model-auth]
            [hc.client.settings.catalog :as catalog]
            [hc.client.settings.commands :as commands]
            [hc.client.settings.connection :as connection]
            [hc.client.settings.draft :as draft]
            [hc.client.settings.model :as model]
            [hc.client.settings.util :as util]))

(defn- el
  ([tag] {:tag tag :attrs {} :children []})
  ([tag attrs] {:tag tag :attrs (or attrs {}) :children []})
  ([tag attrs & children]
   {:tag tag
    :attrs (or attrs {})
    :children (vec (remove nil? children))}))

(defn- act
  [type & kvs]
  (assoc (apply hash-map kvs) :type type))

(defn- ev
  [action]
  {:action action})

(defn- ev-value
  [action]
  {:action action :value :target-value})

(defn- ev-checked
  [action]
  {:action action :value :target-checked})

(defn- button
  ([label action]
   (button label action {}))
  ([label action attrs]
   (el :button
       (merge {:type "button"
               :class "hc-button"}
              attrs
              {:on-click (ev action)})
       label)))

(defn- badge
  [text state & [attrs]]
  (el :span
      (merge {:class (str "hc-badge hc-badge--" (name (or state :unknown)))}
             attrs)
      text))

(def ^:private new-connection-actions
  [[:cloud-key "新建服务密钥连接"]
   [:account-login "使用账号登录"]
   [:local-service "新建本地模型服务连接"]])

(defn- field
  [label control & [hint]]
  (el :label {:class "hc-field"}
      (el :span {:class "hc-field__label"} label)
      control
      (when hint (el :small {:class "hc-field__hint"} hint))))

(defn- input
  [attrs]
  (el :input (merge {:class "hc-input"} attrs)))

(defn- select
  [attrs options]
  (el :select (merge {:class "hc-select"} attrs)
      (mapv (fn [[value label]]
              (el :option {:value (name value) :key (name value)} label))
            options)))

(defn- section-tabs
  [state]
  (let [section (get-in state [:ui :section] :providers)
        tabs [[:providers "供应商与连接"]
              [:models "模型与角色"]
              [:data "数据中心"]
              [:appearance "外观"]]]
    (el :nav {:class "hc-tabs" :aria-label "设置分区"}
        (mapv (fn [[id label]]
                (button label
                        (act :settings/select-section :section id)
                        {:key (name id)
                         :class (str "hc-tab" (when (= section id) " hc-tab--active"))
                         :aria-current (when (= section id) "page")}))
              tabs))))

(defn- sync-banner
  [state can-submit?]
  (let [phase (get-in state [:sync :phase] :disconnected)
        transport? (get-in state [:ui :transport-available?])
        label (case phase
                :synced "已同步"
                :connecting "正在连接"
                :connected-awaiting-sync "已连接，等待同步"
                :interrupted "连接中断；保留最后确认状态"
                :disconnected "未连接"
                "连接状态未知")]
    (el :div {:class (str "hc-sync hc-sync--" (name phase))
              :role "status"}
        (el :strong {} label)
        (el :span {} (if can-submit?
                       "命令通道可用。"
                       "同步完成前不能保存、测试或删除；草稿仍保留。"))
        (when (and can-submit? (not transport?))
          (el :span {:class "hc-warning"} "命令传输尚未接入；按钮不会伪造成功。")))))

(defn- message-banner
  [state]
  (when-let [message (get-in state [:ui :message])]
    (el :div {:class (str "hc-message hc-message--" (name (:kind message :info)))
              :role (if (= :error (:kind message)) "alert" "status")}
        (el :span {} (:text message))
        (button "关闭" (act :settings/dismiss-message)
                {:class "hc-button hc-button--quiet"}))))

(defn- connection-card
  [state summary]
  (let [can-submit? (and (commands/command-allowed? state)
                         (get-in state [:ui :transport-available?]))]
    (el :article {:class (str "hc-connection-card"
                              (when (:selected? summary) " hc-connection-card--selected"))
                  :key (:connection-id summary)
                  :aria-current (when (:selected? summary) "true")}
        (el :header {:class "hc-connection-card__header"}
            (el :h3 {} (:name summary)
                " "
                (badge (:official-label summary)
                       (if (:official? summary) :known :unknown)))
            (el :p {:class "hc-muted"} (:source-label summary)))
        (el :dl {:class "hc-details"}
            (el :dt {} "实际主机")
            (el :dd {} (:actual-host summary))
            (el :dt {} "服务类型")
            (el :dd {} (:service-kind-label summary))
            (el :dt {} "协议")
            (el :dd {} (:protocol-label summary))
            (el :dt {} "状态")
            (el :dd {} (:status-label summary))
            (el :dt {} "最近测试")
            (el :dd {} (:last-tested-label summary))
            (when (:draft-status summary)
              (el :dt {} "草稿")
              (el :dd {} (:draft-status summary)))
            (when (:account-label summary)
              (el :dt {} "账号标识")
              (el :dd {} (:account-label summary))))
        (when (:host-warning summary)
          (el :p {:class "hc-warning"} (:host-warning summary)))
        (el :div {:class "hc-actions"}
            (button "编辑"
                    (act :settings/select-connection
                         :connection-id (:connection-id summary))
                    {:aria-pressed (when (:selected? summary) "true")})
            (button "测试连接"
                    (act :settings/test-connection
                         :connection-id (:connection-id summary))
                    {:disabled (not can-submit?)})))))

(defn- key-secret-cell
  [state draft key]
  (let [revealed? (contains? (get-in state [:ui :revealed] #{})
                             [(:draft-id draft) (:key-id key)])
        auth-state (:auth-state key)
        secret (:secret key)]
    (el :div {:class "hc-secret"}
        (case auth-state
          :set
          (el :div {:class "hc-secret__input"}
              (input {:type (if revealed? "text" "password")
                      :value (or secret "")
                      :autocomplete "new-password"
                      :aria-label (str "密钥 " (:alias key) " 的新值")
                      :placeholder "输入新密钥"
                      :on-input (ev-value
                                 (act :settings/edit-key-secret
                                      :draft-id (:draft-id draft)
                                      :key-id (:key-id key)))})
              (button (if revealed? "隐藏" "显示")
                      (act :settings/toggle-secret
                           :draft-id (:draft-id draft)
                           :key-id (:key-id key))
                      {:class "hc-button hc-button--quiet"})
              (button "复制"
                      (act :settings/copy-secret
                           :draft-id (:draft-id draft)
                           :key-id (:key-id key))
                      {:class "hc-button hc-button--quiet"
                       :disabled (str/blank? (str secret))}))

          :clear
          (el :div {:class "hc-secret__state"}
              (el :span {:class "hc-warning"} "保存后清除该密钥")
              (button "取消清除"
                      (act :settings/edit-key-field
                           :draft-id (:draft-id draft)
                           :key-id (:key-id key)
                           :field :auth-state
                           :value :unchanged)
                      {:class "hc-button hc-button--quiet"}))

          (el :div {:class "hc-secret__state"}
              (el :span {} (if (:has-key? key) "已保存（不可回显）" "未设置"))
              (input {:type "password"
                      :value ""
                      :autocomplete "new-password"
                      :aria-label (str "替换密钥 " (:alias key))
                      :placeholder "输入新值以替换"
                      :on-input (ev-value
                                 (act :settings/edit-key-secret
                                      :draft-id (:draft-id draft)
                                      :key-id (:key-id key)))})
              (button "清除"
                      (act :settings/clear-key-secret
                           :draft-id (:draft-id draft)
                           :key-id (:key-id key))
                      {:class "hc-button hc-button--quiet"
                       :disabled (not (:has-key? key))}))))))

(defn- key-row
  [state draft key]
  (el :tr {:key (:key-id key)}
      (el :td {}
          (input {:type "text"
                  :value (:alias key)
                  :aria-label "密钥别名"
                  :on-input (ev-value
                             (act :settings/edit-key-field
                                  :draft-id (:draft-id draft)
                                  :key-id (:key-id key)
                                  :field :alias))}))
      (el :td {}
          (input {:type "number"
                  :value (:priority key)
                  :min "1"
                  :aria-label "密钥优先级"
                  :on-input (ev-value
                             (act :settings/edit-key-field
                                  :draft-id (:draft-id draft)
                                  :key-id (:key-id key)
                                  :field :priority))}))
      (el :td {}
          (el :label {:class "hc-check"}
              (input {:type "checkbox"
                      :checked (not (false? (:enabled? key)))
                      :on-change (ev-checked
                                  (act :settings/toggle-key
                                       :draft-id (:draft-id draft)
                                       :key-id (:key-id key)))})
              "启用"))
      (el :td {}
          (badge (connection/key-status-label key) (:status key))
          (when-let [detail (connection/key-status-detail key)]
            (el :small {:class "hc-muted"} (str " " detail)))
          (when (:masked-key key)
            (el :small {:class "hc-muted"} (str " " (:masked-key key)))))
      (el :td {} (key-secret-cell state draft key))
      (el :td {}
          (button "移除"
                  (act :settings/remove-key
                       :draft-id (:draft-id draft)
                       :key-id (:key-id key))
                  {:class "hc-button hc-button--danger hc-button--quiet"}))))

(defn- keys-table
  [state draft]
  (el :div {:class "hc-keys"}
      (el :div {:class "hc-section-heading"}
          (el :h3 {} "认证密钥")
          (button "新增密钥"
                  (act :settings/add-key :draft-id (:draft-id draft))
                  {:class "hc-button hc-button--quiet"}))
      (el :p {:class "hc-muted"}
          "密钥默认遮蔽；复制需要显式点击。未改动保存保持原值，清空可见输入不会删除已有密钥。密钥值只提交一次给服务器，保存为加密引用；不会进入配置、快照或日志。")
      (if (empty? (:keys draft))
        (el :p {:class "hc-muted"}
            (if (= :local-service (get-in draft [:fields :service-kind]))
              "本地服务不需要密钥；如服务要求令牌，可新增。"
              "至少需要一个启用的密钥。"))
        (el :table {:class "hc-table"}
            (el :thead {}
                (el :tr {}
                    (el :th {} "别名")
                    (el :th {} "优先级")
                    (el :th {} "启用")
                    (el :th {} "状态")
                    (el :th {} "密钥")
                    (el :th {} "操作")))
            (el :tbody {}
                (mapv #(key-row state draft %) (:keys draft)))))))

(defn- draft-connection
  [state draft]
  (let [saved (get-in state [:server :connections (:connection-id draft)])
        fields (:fields draft)]
    (connection/normalize-connection
     (merge saved
            fields
            {:connection-id (:connection-id draft)
             :auth-keys (mapv (fn [key]
                                (merge key
                                       {:has-key? (or (:has-key? key)
                                                      (= :set (:auth-state key)))
                                        :auth-revision (:auth-edit-id key)}))
                              (:keys draft))
              :manual-models (:manual-models draft)
              :enabled-models (:enabled-models draft)}))))

(defn- draft-list
  [state]
  (let [cards (model/draft-cards state)]
    (when (seq cards)
      (el :section {:class "hc-panel hc-unsaved-drafts"
                    :aria-labelledby "hc-unsaved-drafts-title"}
          (el :h2 {:id "hc-unsaved-drafts-title"} "未保存草稿")
          (el :p {:class "hc-muted"}
              "新建连接，或连接在服务器被移除后，未保存输入保留在这里；切换草稿不会覆盖任何一边。")
          (el :ul {:class "hc-draft-list"}
              (mapv (fn [card]
                      (el :li {:key (:draft-id card)
                               :class (str "hc-draft-list__item"
                                           (when (:selected? card)
                                             " hc-draft-list__item--selected"))}
                          (el :div {}
                              (el :strong {} (:name card))
                              (el :span {:class "hc-muted"}
                                  (str " · 草稿 " (:short-id card)
                                       " · " (:path-label card)
                                       " · " (:status-label card)
                                       " · 版本 " (:version card)
                                       (when (pos? (:manual-model-count card))
                                         (str " · " (:manual-model-count card)
                                              " 个手动模型")))))
                          (button (if (:selected? card) "正在编辑" "继续编辑")
                                  (act :settings/select-draft
                                       :draft-id (:draft-id card))
                                  {:class "hc-button hc-button--quiet"
                                   :aria-pressed (when (:selected? card) "true")})))
                    cards))))))

(defn- editor
  [state draft can-submit?]
  (if-not draft
    (el :section {:class "hc-panel"}
        (el :h2 {} "连接编辑")
        (el :p {:class "hc-muted"} "选择已有连接，或从上方新建连接。"))
    (let [fields (:fields draft)
          service-kind (:service-kind fields)
          local? (= :local-service service-kind)
          new? (= :new (:mode draft))
          effective (draft-connection state draft)
          errors (connection/draft-validation-errors draft)
          existing? (some? (:connection-id draft))]
      (el :section {:class "hc-panel" :aria-labelledby "hc-editor-title"}
          (el :header {:class "hc-editor__header"}
              (el :h2 {:id "hc-editor-title"}
                  (if new?
                    (str "新建连接：" (draft/path-label draft)
                         (when-not (str/blank? (str (:name fields)))
                           (str " · " (draft/display-name draft))))
                    (str "编辑连接：" (draft/display-name draft))))
              (when (or (model/previous-draft state)
                        (model/discardable-new-draft? state draft))
                (button (if (model/discardable-new-draft? state draft)
                          "撤销新建并返回"
                          "返回上一条草稿")
                        (act :settings/back-draft)
                        {:class "hc-button hc-button--quiet"})))
          (when (:orphaned? draft)
            (el :p {:class "hc-warning"}
                "该连接已在服务器移除；草稿仍保留，可另存为新连接。"))
          (el :p {:class "hc-muted"}
              (if new?
                "这是独立的新连接草稿；保存成功前不会出现在连接列表，也不会继承其他连接的模型或密钥。"
                "正在编辑已保存连接；未保存修改在关闭设置页后仍保留。显示名可以重复，实际主机与账号标识始终单独显示。"))
          (when (seq errors)
            (el :div {:class "hc-error" :role "alert"}
                (el :strong {} "还需处理：")
                (el :ul {} (mapv (fn [error]
                                 (el :li {:key (pr-str (:field error))}
                                     (:message error)))
                               errors))))
          (el :div {:class "hc-form"}
              (field "显示名称"
                     (input {:type "text"
                             :value (str (:name fields))
                             :on-input (ev-value
                                        (act :settings/edit-field
                                             :draft-id (:draft-id draft)
                                             :field :name))}))
              (el :div {:class "hc-field"}
                  (el :span {:class "hc-field__label"} "连接状态")
                  (el :span {:class "hc-check"}
                      (el :span {}
                          (if (false? (:enabled? fields))
                            "已停用"
                            "已启用"))
                      (button (if (false? (:enabled? fields))
                                "启用此连接"
                                "停用此连接")
                              (act :settings/edit-field
                                   :draft-id (:draft-id draft)
                                   :field :enabled?
                                   :value (false? (:enabled? fields)))
                              {:class "hc-button hc-button--quiet"}))
                  (el :small {:class "hc-field__hint"}
                      "保存后，已绑定此连接的会话不能发送；可随时重新启用。"))
              (field "基础地址"
                     (input {:type "url"
                             :value (str (:base-url fields))
                             :placeholder "https://api.example.com/v1"
                             :on-input (ev-value
                                        (act :settings/edit-field
                                             :draft-id (:draft-id draft)
                                             :field :base-url))})
                     "修改地址后，实际主机与官方/自定义标签会立即重新计算。")
              (field "实际主机"
                     (el :output {:class "hc-output"} (:actual-host effective))
                     "该值由基础地址计算，不由显示名决定。")
              (field "连接路径"
                     (select {:value (name service-kind)
                              :on-change (ev-value
                                          (act :settings/set-path
                                               :draft-id (:draft-id draft)))}
                             (mapv (fn [kind]
                                     [kind (get connection/service-kind-labels kind)])
                                   connection/service-kinds)))
              (field "协议"
                     (select {:value (name (:protocol fields))
                              :on-change (ev-value
                                          (act :settings/edit-field
                                               :draft-id (:draft-id draft)
                                               :field :protocol))}
                             (mapv (fn [[protocol label]] [protocol label])
                                   connection/protocol-labels)))
              (when local?
                (el :div {:class "hc-local"}
                    (field "模型服务位置"
                           (select {:value (name (:location fields))
                                    :on-change (ev-value
                                                (act :settings/set-location
                                                     :draft-id (:draft-id draft)))}
                                   (mapv (fn [location]
                                           [location (get connection/local-location-labels location)])
                                         connection/local-locations)))
                    (el :p {:class "hc-note"} (:location-note effective))))
              (when (not= :local-service service-kind)
                (field "账号标识"
                       (input {:type "text"
                               :value (str (:account-label fields))
                               :placeholder "账号、组织或地址；不要填写密钥"
                               :on-input (ev-value
                                          (act :settings/edit-field
                                               :draft-id (:draft-id draft)
                                               :field :account-label))})))
              (when (not= :account-login service-kind)
                (field "密钥环境变量"
                       (input {:type "text"
                               :value (str (:api-key-env fields))
                               :placeholder "例如 OPENAI_API_KEY；不要填写密钥本身"
                               :on-input (ev-value
                                          (act :settings/edit-field
                                               :draft-id (:draft-id draft)
                                               :field :api-key-env))})
                       "服务器只保存变量名；密钥值不进入配置、快照或本页命令。"))
              (when (= :account-login service-kind)
                (el :div {:class "hc-auth-status"}
                    (model-auth/login-panel
                     (get-in state [:ui :model-auth]))
                    (el :p {:class "hc-note"}
                        (if (util/present? (str (:auth-ref fields)))
                          (str "草稿认证引用：" (:auth-ref fields)
                               "；令牌只保存在服务器。")
                          "尚未完成账号登录；本页不接收账号密码或令牌。"))))
              (when (not= :account-login service-kind)
                (field "多密钥策略"
                       (select {:value (name (:key-strategy fields))
                                :on-change (ev-value
                                            (act :settings/set-key-strategy
                                                 :draft-id (:draft-id draft)))}
                               (mapv (fn [strategy]
                                       [strategy (get connection/key-strategy-labels strategy)])
                                     connection/key-strategies))))
              (when (:host-warning effective)
                (el :p {:class "hc-warning"} (:host-warning effective)))
              (if (= :account-login service-kind)
                (el :p {:class "hc-note"}
                    "账号登录使用 OAuth 令牌；不会使用 API 密钥或多密钥轮询。")
                (keys-table state draft))
              (el :div {:class "hc-manual-model"}
                  (field "手动模型标识"
                         (input {:type "text"
                                 :value (model/manual-model-input
                                         state (:draft-id draft))
                                 :placeholder "列举接口不可用时填写"
                                 :on-input (ev-value
                                            (act :settings/edit-manual-model-input
                                                 :draft-id (:draft-id draft)))}))
                  (button "加入模型"
                          (act :settings/add-manual-model
                               :draft-id (:draft-id draft))))
              (when (seq (:manual-models draft))
                (el :ul {:class "hc-chip-list"}
                    (mapv (fn [model-id]
                            (el :li {:key model-id :class "hc-chip"}
                                model-id
                                (button "移除"
                                        (act :settings/remove-manual-model
                                             :draft-id (:draft-id draft)
                                             :model-id model-id)
                                        {:class "hc-button hc-button--quiet"})))
                          (:manual-models draft))))
              (when (and new? (empty? (:manual-models draft)))
                (el :p {:class "hc-muted"}
                    "新草稿尚未添加模型；不会显示或继承其他连接的手动模型。"))
              (el :div {:class "hc-draft-footer"}
                  (el :span {:class (str "hc-draft-status hc-draft-status--"
                                         (name (draft/status draft)))}
                      (draft/status-label draft))
                  (el :span {:class "hc-muted"}
                      (str "草稿 " (draft/short-id draft)
                           " · 版本 " (:version draft)
                           " · 配置摘要 " (util/short-digest
                                           (connection/draft-digest draft))))
                  (el :div {:class "hc-actions"}
                      (button "保存连接"
                              (act :settings/save-connection
                                   :draft-id (:draft-id draft))
                              {:disabled (or (not can-submit?)
                                             (not (draft/dirty? draft)))})
                      (button "测试当前配置"
                              (act :settings/test-connection
                                   :draft-id (:draft-id draft))
                              {:disabled (not can-submit?)})
                      (when existing?
                        (button "删除连接"
                                (act :settings/request-delete
                                     :draft-id (:draft-id draft))
                                {:class "hc-button hc-button--danger"}))))
              (when (get-in state [:ui :confirm-delete-draft-id])
                (el :div {:class "hc-confirm" :role "alert"}
                    (el :p {} "再次确认删除该连接？已保存配置会移除；历史消息中的模型来源仍保留。")
                    (button "确认删除"
                            (act :settings/confirm-delete
                                 :draft-id (:draft-id draft))
                            {:class "hc-button hc-button--danger"})
                    (button "取消"
                            (act :settings/cancel-delete)
                            {:class "hc-button hc-button--quiet"})))
              (el :p {:class "hc-note"}
                  "保存连接只保存配置，不自动发聊天、生成标题或产生其他付费任务。"))))))

(defn- draft-model-by-id
  [draft model-id]
  (some (fn [model]
          (when (= (str model-id)
                   (str (or (:model-id model) (:id model) (:name model))))
            model))
        (:models draft)))

(defn- number-text
  [value]
  (if (number? value) (str value) ""))

(defn- detail-row
  [label control source limit]
  (el :tr {}
      (el :th {:scope "row"} label)
      (el :td {} control)
      (el :td {:class "hc-muted"} source)
      (el :td {:class "hc-muted"} limit)))

(defn- model-detail
  "Render editable model values with their source and limit.

  The three columns are intentionally explicit: the operator can see the
  current value, whether it came from the service or a local edit, and the
  bound that validation will apply."
  [state draft row]
  (let [model-id (:model-id row)
        model (or (draft-model-by-id draft model-id) row)
        pricing (:pricing model)
        pricing-map (if (map? pricing) pricing {})
        reasoning (:reasoning model)
        reasoning-config (if (map? reasoning) reasoning {})
        selected (or (:selected reasoning-config)
                     (when (keyword? reasoning) reasoning)
                     (:default reasoning-config)
                     :default)
        reasoning-options (catalog/reasoning-options model)
        reasoning-options (if (some #(= :default (:level %)) reasoning-options)
                            reasoning-options
                            (into [{:level :default :label "使用默认"}]
                                  reasoning-options))
        reasoning-options (if (some #(= selected (:level %)) reasoning-options)
                            reasoning-options
                            (conj reasoning-options
                                  {:level selected
                                   :label (catalog/reasoning-label model)}))
        capacity-source (or (:capacity-source model)
                            (when (map? (:context-window model))
                              (:source (:context-window model)))
                            :unknown)
        action (fn [field]
                 (ev-value
                  (act :settings/edit-model-field
                       :draft-id (:draft-id draft)
                       :model-id model-id
                       :field field)))
        text-input (fn [field value placeholder]
                     (input {:type "text"
                             :value (str (or value ""))
                             :placeholder placeholder
                             :on-input (action field)}))
        number-input (fn [field value placeholder]
                       (input {:type "number"
                               :step "any"
                               :value (number-text value)
                               :placeholder placeholder
                               :on-input (action field)}))]
    (el :section {:class "hc-model-detail"
                  :aria-label (str (:display-name row) " 模型详情与有效配置")}
        (el :h4 {} "模型详情与有效配置")
        (el :p {:class "hc-note"}
            "留空表示未知或使用默认；数字 0 是真实值，不等同于未设置。")
        (el :table {:class "hc-table hc-model-detail__table"}
            (el :thead {}
                (el :tr {}
                    (el :th {} "项目")
                    (el :th {} "值")
                    (el :th {} "来源")
                    (el :th {} "限制")))
            (el :tbody {}
                (detail-row
                 "显示名"
                 (text-input :display-name
                             (or (:display-name model) (:name model) model-id)
                             "列表中的称呼")
                 "本地配置"
                 "非空")
                (detail-row
                 "模型标识"
                 (text-input :model-id
                             (or (:model-id model) (:id model) model-id)
                             "服务识别的标识")
                 "本地配置"
                 "非空；连接内唯一")
                (detail-row
                 "上下文容量"
                 (number-input :context-window
                               (:context-window model)
                               "未知")
                 (or (catalog/capacity-source-label capacity-source)
                     "尚未确认")
                 "≥ 0；未知留空")
                (detail-row
                 "容量来源"
                 (select {:value (name (or capacity-source :unknown))
                          :on-change (action :capacity-source)}
                         [[:unknown "未知"]
                          [:user "用户设置"]
                          [:estimate "估算"]
                          [:provider "服务返回"]])
                 "本地配置"
                 "只影响展示口径")
                (detail-row
                 "最大回复长度"
                 (number-input :max-output (:max-output model)
                               "使用服务默认")
                 "本地配置"
                 "≥ 0；留空=服务默认")
                (detail-row
                 "温度"
                 (number-input :temperature (:temperature model)
                               "使用默认")
                 "本地配置"
                 "≥ 0；留空=使用默认")
                (detail-row
                 "top-p"
                 (number-input :top-p (:top-p model)
                               "使用默认")
                 "本地配置"
                 "0–1；留空=使用默认")
                (detail-row
                 "输入价格"
                 (number-input :pricing-input
                               (get pricing-map :input)
                               "未知")
                 "本地配置"
                 "≥ 0；未知留空")
                (detail-row
                 "输出价格"
                 (number-input :pricing-output
                               (get pricing-map :output)
                               "未知")
                 "本地配置"
                 "≥ 0；未知留空")
                (detail-row
                 "价格币种"
                 (text-input :pricing-currency
                             (:currency pricing-map)
                             "例如 CNY")
                 "本地配置"
                 "填写价格时必填")
                (detail-row
                 "价格单位"
                 (select {:value (name (or (:unit pricing-map) :unknown))
                          :on-change (action :pricing-unit)}
                         [[:unknown "未设置"]
                          [:per-million-tokens "百万 tokens"]
                          [:per-request "请求"]
                          [:per-image "图片"]
                          [:per-second "秒"]])
                 "本地配置"
                 "填写价格时建议填写")
                (detail-row
                 "推理档位"
                 (el :select {:class "hc-select"
                              :value (name selected)
                              :on-change (action :reasoning)}
                     (mapv (fn [option]
                             (el :option
                                 {:value (name (:level option))
                                  :disabled (:disabled? option)}
                                 (:label option)))
                           reasoning-options))
                 "模型声明/用户设置"
                 "仅离散档位；不能关闭时显示最低可用"))))))

(defn- model-row
  [state group row selected-role can-submit?]
  (let [draft-id (:draft-id (model/selected-draft state))
        draft (model/selected-draft state)
        can-edit-enabled? (and draft (= (:connection-id draft) (:connection-id group)))
        capabilities (catalog/normalize-capabilities (:capabilities row))
        capability-sources
        (catalog/normalize-capability-sources (:capability-sources row))
        user-capabilities? (seq capability-sources)
        selected? (:selected-for-role? row)
        session-id (get-in state [:ui :current-session-id])
        running? (boolean (get-in state [:ui :session-running?]))
        ;; A typed id that a listing did not return is still selectable: the
        ;; service may not expose a model list at all.  Only an explicit
        ;; disable or an unavailable connection blocks selection.
        selectable? (not (contains? #{:disabled :unavailable}
                                     (:availability-state row)))
        switch-plan (catalog/round-switch-plan
                     running?
                     (catalog/selection (:connection-id row) (:model-id row))
                     session-id)]
    (el :article {:class (str "hc-model-row"
                              (when (:selected? row) " hc-model-row--selected")
                              (when selected? " hc-model-row--role-selected"))
                  :key (pr-str (:row-key row))
                  :aria-checked (when selected? "true")}
        (el :header {:class "hc-model-row__header"}
            (el :div {}
                (el :h3 {} (:display-name row)
                    (when (:duplicate-display-name? row)
                      (el :span {:class "hc-muted"} " · 同名模型，按连接区分")))
                (el :p {:class "hc-muted"}
                    (el :code {} (:full-id row))
                    " · 来源：" (:source-label row))
                (when (:alias row)
                  (el :p {:class "hc-muted"} "本地别名：" (:alias row)))
                (when (:notes row)
                  (el :p {:class "hc-muted"} "备注：" (:notes row))))
            (el :div {:class "hc-actions"}
                (button (if selected? "已选为当前角色" "选为当前角色")
                        (act :settings/select-model
                             :connection-id (:connection-id row)
                             :model-id (:model-id row)
                             :role selected-role)
                        {:aria-pressed (when selected? "true")})
                (when session-id
                  (el :div {:class "hc-model-switch"}
                      (button (:label switch-plan)
                              (act :settings/select-session-model
                                   :session-id session-id
                                   :connection-id (:connection-id row)
                                   :model-id (:model-id row)
                                   :mode (:mode switch-plan))
                              {:class "hc-button hc-button--quiet"
                               :disabled (or (not can-submit?)
                                             (not selectable?))})
                      (when running?
                        (button "停止当前轮并切换"
                                (act :settings/select-session-model
                                     :session-id session-id
                                     :connection-id (:connection-id row)
                                     :model-id (:model-id row)
                                     :mode :stop-and-switch)
                                {:class "hc-button hc-button--quiet"
                                 :disabled (or (not can-submit?)
                                               (not selectable?))}))
                      (el :small {:class "hc-muted"}
                          (:attribution-note switch-plan))))
                (when-not can-edit-enabled?
                  (button "编辑能力"
                          (act :settings/edit-model-capabilities
                               :connection-id (:connection-id group))
                          {:class "hc-button hc-button--quiet"}))))
        (el :div {:class "hc-badges" :aria-label "能力与可用性"}
            (badge (:availability-label row) (:availability-state row))
            (badge (:official-label row)
                   (if (:official? group) :known :unknown))
            (when user-capabilities?
              (badge "用户设置" :known))
            (mapv (fn [cap]
                    (el :span {:class (str "hc-badge hc-badge--" (name (:state cap)))
                               :title (:aria-label cap)
                               :aria-label (:aria-label cap)
                               :key (name (:capability cap))}
                        (:text cap)))
                  (:capability-badges row)))
        (el :dl {:class "hc-model-meta"}
            (el :dt {} "价格")
            (el :dd {} (:pricing-label row))
            (el :dt {} "上下文容量")
            (el :dd {} (:capacity-label row))
            (el :dt {} "推理档位")
            (el :dd {} (:reasoning-label row))
            (el :dt {} "连接")
            (el :dd {} (:source-label row)))
        (when (:not-returned? row)
          (el :p {:class "hc-warning"}
              "此次列表未返回该模型；启用状态、别名与历史引用保留。"))
        (when can-edit-enabled?
          (el :label {:class "hc-check"}
              (input {:type "checkbox"
                      :checked (contains? (set (:enabled-models draft))
                                          (:model-id row))
                      :on-change (ev-checked
                                  (act :settings/toggle-enabled-model
                                       :draft-id draft-id
                                       :model-id (:model-id row)))})
              "在连接中启用"))
        (when can-edit-enabled?
          (el :fieldset {:class "hc-capability-editor"}
              (el :legend {}
                  "能力设置"
                  (when user-capabilities?
                    (el :span {:class "hc-muted"} " · 用户设置")))
              (el :p {:class "hc-note"}
                  "手动修改是用户声明，第一次实际请求仍可能被服务拒绝；未知能力不会默认支持。")
              (el :div {:class "hc-capability-fields"}
                  (mapv
                   (fn [capability]
                     (let [state (get capabilities capability :unknown)
                           user? (contains? capability-sources capability)]
                       (el :label {:class "hc-capability-field"
                                   :key (name capability)}
                           (el :span {:class "hc-capability-name"}
                               (get catalog/capability-labels capability)
                               (when user?
                                 (el :span {:class "hc-muted"}
                                     " · 用户设置")))
                           (el :select
                               {:value (name state)
                                :aria-label
                                (str (get catalog/capability-labels capability)
                                     "能力")
                                :on-change
                                (ev-value
                                 (act :settings/set-model-capability
                                      :draft-id draft-id
                                      :model-id (:model-id row)
                                      :capability capability))}
                               (mapv (fn [[option label]]
                                       (el :option {:value (name option)
                                                    :selected (= option state)}
                                           label))
                                     catalog/capability-edit-options)))))
                   catalog/capability-order))
              (when user-capabilities?
                (button "恢复已知信息"
                        (act :settings/restore-model-capabilities
                             :draft-id draft-id
                             :model-id (:model-id row))
                        {:class "hc-button hc-button--quiet"}))
              (button "保存连接"
                      (act :settings/save-connection
                           :draft-id draft-id)
                      {:class "hc-button hc-button--quiet"
                       :disabled (not can-submit?)})))
        (when can-edit-enabled?
          (model-detail state draft row))
        (el :p {:class "hc-note"}
            (if user-capabilities?
              "能力含用户设置；图标只表示已声明或已知，不代表当前会话已有执行许可。"
              "能力图标只表示已声明或已知，不代表当前会话已有执行许可。")))))

(defn- model-group
  [state group selected-role can-submit?]
  (el :section {:class "hc-model-group" :key (:group-key group)}
      (el :header {:class "hc-model-group__header"}
          (el :h3 {} (:connection-name group)
              " "
              (badge (:official-label group)
                     (if (:official? group) :known :unknown)))
          (el :p {:class "hc-muted"} (:source-label group))
          (badge (if (:connection-enabled? group) "已启用" "已停用")
                 (if (:connection-enabled? group) :known :disabled))
          (when (:duplicate-name? group)
            (el :p {:class "hc-note"} "连接显示名重复；以实际主机区分，不合并模型。"))
          (button "刷新模型列表"
                  (act :settings/refresh-models
                       :connection-id (:connection-id group))
                  {:disabled (not can-submit?)})
          (button "编辑连接"
                  (act :settings/select-connection
                       :connection-id (:connection-id group))
                  {:class "hc-button hc-button--quiet"}))
      (when (:listing-unavailable? group)
        (el :p {:class "hc-warning"}
            "模型列表接口不可用；现有模型与启用状态保留，可手动填写模型标识。"))
      (if (empty? (:rows group))
        (el :p {:class "hc-muted"}
            (if (:connection-enabled? group)
              "此次列表未返回模型；可刷新或手动填写模型标识。"
              "连接已停用且没有模型行；在“供应商与连接”编辑连接可重新启用。"))
        (el :div {:class "hc-model-list"}
            (mapv #(model-row state group % selected-role can-submit?)
                  (:rows group))))))

(defn- models-section
  [state groups can-submit?]
  (let [selected-role (get-in state [:ui :selected-role] :main)
        session-id (get-in state [:ui :current-session-id])
        session-model (when session-id
                        (get-in state [:server :session-model-snapshots
                                       session-id]))]
    (el :section {:class "hc-panel" :aria-labelledby "hc-models-title"}
        (el :h2 {:id "hc-models-title"} "模型选择")
        (el :p {:class "hc-muted"}
            "按连接分组；同名模型绝不合并。每行显示完整标识、连接来源、能力与可用性。")
        (el :p {:class "hc-note"}
            "生成中切换只用于下一轮；当前回复仍归属原模型。停止当前轮并切换是独立动作。")
        (when session-id
          (el :p {:class "hc-muted"}
              (str "当前会话模型："
                   (or (:display-name session-model)
                       (:model-id session-model)
                       "尚未记录")
                   (when-let [host (:actual-host session-model)]
                     (str " · " host)))))
        (el :div {:class "hc-role-tabs" :role "tablist" :aria-label "模型角色"}
            (mapv (fn [role]
                    (button (get model/role-labels role)
                            (act :settings/select-role :role role)
                            {:key (name role)
                             :class (str "hc-tab" (when (= role selected-role) " hc-tab--active"))
                             :role "tab"
                             :aria-selected (when (= role selected-role) "true")}))
                  model/role-order))
        (if (empty? groups)
          (el :p {:class "hc-warning"} "尚未配置连接；请先在“供应商与连接”新建连接。")
          (mapv #(model-group state % selected-role can-submit?) groups)))))

(defn- role-card
  [state role can-submit?]
  (let [card (some #(when (= role (:role %)) %) (model/role-cards state))]
    (el :article {:class "hc-role-card" :key (name role)}
        (el :h3 {} (:label card))
        (el :p {:class "hc-muted"} (:description card))
        (el :p {:class "hc-role-status"} (:status card))
        (when (:selection-label card)
          (el :p {:class "hc-muted"} (:selection-label card)))
        (el :div {:class "hc-actions"}
            (button "选择模型"
                    (act :settings/select-role :role role)
                    {:class "hc-button hc-button--quiet"})
            (when (contains? #{:utility :vision} role)
              (button "跟随主模型"
                      (act :settings/set-role-mode :role role :mode :follow-main)
                      {:class "hc-button hc-button--quiet"}))
            (when (and (= :utility role)
                       (= :follow-main (get-in card [:binding :mode]))
                       (not (:confirmed? card)))
              (button "确认跟随"
                      (act :settings/confirm-role :role role)
                      {:class "hc-button hc-button--quiet"}))
            (when (:dirty? card)
              (button "保存角色绑定"
                      (act :settings/save-role :role role)
                      {:disabled (not can-submit?)}))))))

(defn- roles-section
  [state can-submit?]
  (el :section {:class "hc-panel" :aria-labelledby "hc-roles-title"}
      (el :h2 {:id "hc-roles-title"} "模型角色")
      (el :p {:class "hc-muted"}
          "主模型服务 Steward、Plan 与 Reviewer；任务模型（@task）服务 Plan dedicated worker 与蜂群，必须单独选择，未选择时蜂群无法启动。角色故障局部说明，不互相冒充。")
      (el :div {:class "hc-role-grid"}
          (mapv #(role-card state % can-submit?) model/role-order))))

(defn- fixed-test-content
  [text]
  (el :p {:class "hc-note"}
      "固定测试内容：" (el :code {} text)))

(defn- test-panel
  [state draft can-submit?]
  (let [test (model/test-view state (:draft-id draft)
                                 #?(:cljs (js/Date.now)
                                    :clj (System/currentTimeMillis)))]
    (el :section {:class "hc-panel" :aria-labelledby "hc-test-title"}
        (el :h2 {:id "hc-test-title"} "连接测试")
        (el :p {:class "hc-warning"} (:warning test))
        (when (:stale? test)
          (el :p {:class "hc-warning" :role "status"}
              "配置已修改；下面的层结果来自旧摘要，仅作参考，请重新测试。"))
        (when-let [request-text (:request-text test)]
          (fixed-test-content request-text))
        (when-let [copy (:waiting-copy test)]
          (el :div {:class "hc-warning" :role "status"}
              (el :p {} (:body copy))
              (el :p {:class "hc-muted"}
                  "等待期间不会显示为“正在思考”；可取消当前测试。")))
        (el :p {:class "hc-muted"}
            "测试结果分四层；前三层成功不冒充“所选模型可生成”。"
            (when (:short-digest test)
              (str " 当前配置摘要：" (:short-digest test) "。")))
        (el :table {:class "hc-table hc-test-layers"}
            (el :thead {}
                (el :tr {} (el :th {} "层级") (el :th {} "结果") (el :th {} "说明")))
            (el :tbody {}
                (mapv (fn [layer]
                        (el :tr {:key (name (:layer layer))}
                            (el :td {} (:label layer))
                            (el :td {} (badge (:status-label layer) (:state layer)))
                            (el :td {} (or (:detail layer) "—"))))
                      (:layers test))))
        (when (:conclusion test)
          (el :p {:class "hc-test-conclusion"} (:conclusion test)))
        (when (seq (:stale-results test))
          (el :div {:class "hc-stale-results"}
              (el :h3 {} "旧配置的测试结果")
              (el :p {:class "hc-muted"} "迟到结果按配置摘要保留，不覆盖当前选择。")
              (el :ul {}
                  (mapv (fn [result]
                          (el :li {:key (pr-str [(:config-digest result) (:at result)])}
                              "配置 " (util/short-digest (:config-digest result))
                              "：" (case (:status result)
                                    :complete "已完成"
                                    :cancelled "已取消"
                                    "结果未知")))
                        (:stale-results test)))))
        (el :div {:class "hc-actions"}
            (button "测试当前配置"
                    (act :settings/test-connection :draft-id (:draft-id draft))
                    {:disabled (not can-submit?)})
            (when (contains? #{:pending :running} (:status test))
              (button "取消测试"
                      (act :settings/cancel-test :draft-id (:draft-id draft))
                      {:class "hc-button hc-button--quiet"}))))))

(defn- onboarding-panel
  [state]
  (let [{:keys [step complete? dismissed? completed? has-enabled-chat-model?
                tested?]}
        (model/onboarding-status state)
        visible? (not (or dismissed? completed? complete?))]
    (when visible?
      (el :section {:class "hc-onboarding" :aria-labelledby "hc-onboarding-title"}
          (el :h2 {:id "hc-onboarding-title"} "首次配置")
          (el :p {:class "hc-muted"}
              "无需注册本产品云账号；未配置模型时仍可浏览界面、编写草稿。草稿在进出设置页后保留。首次启动会在会话页显示连接引导；测试成功只是证据，不代替已启用模型。")
          (el :ol {:class "hc-onboarding__steps"}
              (mapv (fn [[id label ready?]]
                      (el :li {:key (name id)
                               :class (str "hc-onboarding__step"
                                           (when (= step id) " hc-onboarding__step--current")
                                           (when ready? " hc-onboarding__step--done"))}
                          (el :strong {} label)
                          (el :span {}
                              (if ready? "已完成" "待完成"))))
                    [[:connection "1 选择连接路径" (pos? (count (get-in state [:server :connections])))]
                     [:model "2 选择并启用聊天模型" has-enabled-chat-model?]
                     [:test "3 测试连接（可选证据）" tested?]
                     [:done "4 完成" complete?]]))
          (el :div {:class "hc-actions"}
              (case step
                :connection
                (el :div {:class "hc-actions"}
                    (mapv (fn [[kind label]]
                            (button label
                                    (act :settings/new-connection :path kind)
                                    {:key (name kind)
                                     :class (if (= :cloud-key kind)
                                              "hc-button"
                                              "hc-button hc-button--quiet")}))
                          new-connection-actions))

                :model
                (button "打开模型选择"
                        (act :settings/select-section :section :models))

                :test
                (button "打开连接测试"
                        (act :settings/select-section :section :providers))

                :done
                (button "完成配置"
                        (act :settings/finish-onboarding))

                nil)
              (button "稍后配置"
                      (act :settings/dismiss-onboarding)
                      {:class "hc-button hc-button--quiet"}))))))

(defn- appearance-section
  [state]
  (appearance-view/settings-tree (:appearance state)))

(defn- placeholder-section
  [title text]
  (el :section {:class "hc-panel"}
      (el :h2 {} title)
      (el :p {:class "hc-muted"} text)))

(defn settings-tree
  "Return the complete settings tree for STATE."
  [state]
  (let [state (or state (model/initial-state))
        can-submit? (and (commands/command-allowed? state)
                         (get-in state [:ui :transport-available?]))
        draft (model/selected-draft state)
        section (get-in state [:ui :section] :providers)
        connections (model/connection-cards state)
        groups (model/model-groups state)]
    (el :div {:class "hc-settings" :role "region" :aria-label "设置与数据中心"}
        (el :header {:class "hc-settings__header"}
            (el :h1 {} "设置与数据中心")
            (el :p {:class "hc-muted"}
                (case section
                  :data "用量、费用、详细日志与备份；诊断包只在本机生成。"
                  :appearance "主题、字号、对比度与动效；改动先在本页预览。"
                  "供应商连接、模型角色、测试与草稿。关闭页面不会清除草稿。"))
            (sync-banner state can-submit?))
        (message-banner state)
        (section-tabs state)
        (when (contains? #{:providers :models} section)
          (onboarding-panel state))
        (case section
          :providers
          (el :div {:class "hc-settings__body"}
              (el :section {:class "hc-panel" :aria-labelledby "hc-connections-title"}
                  (el :h2 {:id "hc-connections-title"} "供应商连接")
                  (el :p {:class "hc-muted"}
                      "显示名可重复；详情必须显示实际主机、协议/服务类型与账号标识。自定义网关不会因名称而标成官方。")
                  (el :div {:class "hc-new-connection"}
                      (el :span {} "新建连接：")
                      (mapv (fn [[kind label]]
                              (button label
                                      (act :settings/new-connection :path kind)
                                      {:key (name kind)
                                       :class "hc-button hc-button--quiet"}))
                            new-connection-actions))
                  (el :p {:class "hc-note"}
                      "每次新建都打开一份独立草稿；已有草稿会保留在“未保存草稿”，可随时返回。")
                  (el :div {:class "hc-test-disclosure"}
                      (el :p {:class "hc-warning"}
                          "“测试连接”与“测试当前配置”在连接支持探测时会发送一次固定测试请求，可能产生少量费用；不携带草稿、附件、会话历史或工具定义。")
                      (fixed-test-content model/test-request-text))
                  (if (empty? connections)
                    (el :p {:class "hc-warning"}
                        "尚无已保存连接。新建连接先形成草稿；保存成功后才出现在列表。")
                    (el :div {:class "hc-connection-list"}
                        (mapv #(connection-card state %) connections)))
                  (draft-list state)
                  (editor state draft can-submit?))
              (test-panel state (or draft {:draft-id nil}) can-submit?))

          :models
          (el :div {:class "hc-settings__body"}
              (models-section state groups can-submit?)
              (roles-section state can-submit?))

          :data nil

          :appearance
          (appearance-section state)

          (placeholder-section "设置" "未知设置分区。"))
        (when (not= :data section)
          (el :footer {:class "hc-settings__footer"}
              (when (contains? #{:error :unsaved :failed}
                               (get-in state [:ui :draft-storage-status]))
                (el :p {:class "hc-warning" :role "status"}
                    "草稿暂未保存到本地；可复制或另存，关闭页面后可能丢失。"))
              (el :p {:class "hc-muted"}
                  "保存连接不自动触发聊天或付费任务；测试是独立动作，支持探测时会发送固定内容并可能产生少量费用。")
              (when draft
                (el :p {:class "hc-muted"}
                    (str "当前草稿：" (draft/status-label draft)
                         "（版本 " (:version draft) "）。"))))))))
