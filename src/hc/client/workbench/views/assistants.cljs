(ns hc.client.workbench.views.assistants
  "Assistant and model-role overview.

  The workbench has one committed settings projection at `:ui/settings`.
  This view reads role ownership from that projection and opens the real
  settings editor for a role or connection; it never keeps a second profile
  table or invents a model binding.  The task role (`:task`) is a first-class
  row because Plan workers and Swarm actors cannot start without it."
  (:require [com.fulcrologic.fulcro.application :as application]
            [com.fulcrologic.fulcro.components :as comp]
            [com.fulcrologic.fulcro.dom :as dom]
            [hc.client.workbench.commands :as commands]
            [hc.client.workbench.model :as model]
            [hc.client.workbench.mutations :as mutations]
            [hc.client.workbench.routing :as routing]
            [hc.client.workbench.settings-bridge :as settings-bridge]
            [hc.client.workbench.views.common :as common]))

(defn- open-models!
  [this]
  (settings-bridge/dispatch! {:type :settings/select-section
                              :section :models})
  (routing/navigate! this :settings nil))

(defn- open-role!
  [this role]
  (settings-bridge/dispatch! {:type :settings/select-section
                              :section :models})
  (settings-bridge/dispatch! {:type :settings/select-role
                              :role role})
  (routing/navigate! this :settings nil))

(defn- open-connection!
  [this connection-id]
  (settings-bridge/dispatch! {:type :settings/select-section
                              :section :providers})
  (when connection-id
    (settings-bridge/dispatch! {:type :settings/select-connection
                                :connection-id connection-id}))
  (routing/navigate! this :settings nil))

(defn- synced-label
  "The last confirmed boundary is an epoch number on the wire; a raw
  13-digit value is not a user-facing time."
  [value]
  (cond
    (number? value)
    (try
      (.toLocaleString (js/Date. value) "zh-CN" #js {:hour12 false})
      (catch :default _ (str value)))

    (string? value) value
    :else "—"))

(defn- role-badge
  [card]
  (let [follow? (= :follow-main (:mode card))
        configured? (boolean (:selection-label card))]
    [(cond
       (and follow? configured?) "跟随主模型"
       follow? "跟随来源缺失"
       configured? "已绑定"
       :else "未设置")
     (cond
       (and follow? configured?) :neutral
       configured? :ok
       :else :warn)]))

(defn- role-row
  [this card]
  (let [role (:role card)
        [badge-label badge-tone] (role-badge card)
        configured? (or (:selection-label card)
                        (= :follow-main (:mode card)))]
    (dom/li {:key (name role)}
            (dom/div {:className "wb-list-button" :style {:cursor "default"}}
                     (dom/div {:className "wb-list-main"}
                              (dom/strong {:title (:label card)} (:label card))
                              (dom/small {:title (:status card)} (:status card))
                              (when (:description card)
                                (dom/small {:title (:description card)}
                                           (:description card)))
                              (when-let [selection (:selection-label card)]
                                (dom/small {:title (str "选择：" selection)}
                                           (str "选择：" selection))))
                     (common/badge badge-label badge-tone)
                     (dom/button {:type "button"
                                  :className "wb-button wb-button-small"
                                  :aria-label (str (if configured? "更换" "配置")
                                                   (:label card))
                                  :onClick (fn [_] (open-role! this role))}
                                 (if configured? "更换" "配置"))))))

(defn- roles-tab
  [this db]
  (let [cards (model/role-cards db)]
    (dom/ul {:className "wb-list"}
            (for [card cards]
              (role-row this card)))))

(defn- connections-tab
  [this db]
  (let [cards (model/connection-cards db)]
    (if (seq cards)
      (dom/ul {:className "wb-list"}
              (for [card cards]
                (let [summary-line
                      (str (or (:source-label card)
                               (:actual-host card)
                               "地址未声明")
                           " · "
                           (or (:service-kind-label card)
                               "类型未声明")
                           " · "
                           (or (:status-label card)
                               (name (or (:status card) :unknown)))
                           " · "
                           (:model-count card)
                           " 个模型")
                      tested-line (str "最近测试："
                                       (or (:last-tested-label card)
                                           "未测试"))]
                  (dom/li {:key (:connection-id card)}
                          (dom/div {:className "wb-list-button" :style {:cursor "default"}}
                                   (dom/div {:className "wb-list-main"}
                                            (dom/strong {:title (:name card)} (:name card))
                                            (dom/small {:title summary-line} summary-line)
                                            (dom/small {:title tested-line} tested-line))
                                   (when (:official? card)
                                     (common/badge "预设服务" :neutral))
                                   (when-not (:enabled? card)
                                     (common/badge "已停用" :warn))
                                   (common/copy-button (:connection-id card)
                                                       "复制 ID" nil)
                                   (dom/button {:type "button"
                                                :className "wb-button wb-button-small"
                                                :aria-label (str "配置连接 " (:name card))
                                                :onClick (fn [_]
                                                           (open-connection!
                                                            this (:connection-id card)))}
                                               "配置"))))))
      (common/empty-state
       "没有供应商连接"
       "服务器尚未提供连接配置。请在设置中新建连接；凭据不会由客户端保存或显示。"))))

(defn assistants-page
  [this props]
  (let [db (application/current-state this)
        tab (or (:ui/assistants-tab props) :roles)
        connection (:ui/connection props)
        cards (model/role-cards db)
        main (some #(when (= :main (:role %)) %) cards)
        task (some #(when (= :task (:role %)) %) cards)
        resync (get (model/availability {:connection connection}) :resync)]
    (dom/div {:className "wb-content hc-assistants"}
             (dom/style
              {:dangerouslySetInnerHTML
               #js {:__html (str ".hc-assistants .wb-list-main strong{white-space:normal;overflow-wrap:anywhere}"
                                 ".hc-assistants .wb-list-main small{white-space:normal;overflow:visible;text-overflow:clip;overflow-wrap:anywhere}"
                                 ".hc-assistants .wb-list-button{cursor:default}"
                                 "@media(max-width:599px){.hc-assistants .wb-button-small{min-height:44px;min-width:44px}}")}})
             (dom/div {:className "wb-content-inner"}
                      (dom/div {:className "wb-page-title"}
                               (dom/div {}
                                        (dom/h1 {} "助手与角色")
                                        (dom/p {} "查看主模型（Steward / Plan / Reviewer）与任务模型（@task）的归属、供应商连接与能力状态。绑定在设置中修改，服务器回执后才生效；客户端不显示密钥。"))
                               (dom/div {:className "wb-row"}
                                        (common/badge (model/connection-label connection)
                                                      (:tone connection))
                                        (dom/button {:type "button"
                                                     :className "wb-button wb-button-small"
                                                     :onClick (fn [_] (open-models! this))}
                                                    "打开模型设置")
                                        (dom/button {:type "button"
                                                     :className "wb-button wb-button-small"
                                                     :disabled (not (:enabled? resync))
                                                     :title (when-not (:enabled? resync)
                                                              (model/availability-reason resync))
                                                     :onClick (fn [_]
                                                                (commands/request-resync! this :operator-assistants))}
                                                    "请求重同步")))
                      (common/tabs [{:id :roles :label "模型归属"}
                                    {:id :connections :label "供应商连接"}]
                                   tab
                                   (fn [next]
                                     (comp/transact! this
                                                     [(mutations/set-tab {:group :ui/assistants-tab
                                                                          :tab next})])))
                      (case tab
                        :connections (connections-tab this db)
                        (roles-tab this db))
                      (common/facts
                       [["主模型" (or (:selection-label main) "未设置")]
                        ["任务模型" (or (:selection-label task) "未设置")]
                        ["连接数" (count (model/connection-cards db))]
                        ["同步 epoch" (:epoch connection)]
                        ["同步序号" (:seq connection)]
                        ["最近确认" (synced-label (:synced-at connection))]])))))
