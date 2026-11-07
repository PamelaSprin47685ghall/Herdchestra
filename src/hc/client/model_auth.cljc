(ns hc.client.model-auth
  "Pure browser state for third-party model account login.

  This namespace never holds an access token, refresh token, device code, or
  password.  It turns user actions into command effects and turns server
  receipts into display state.  The settings workspace owns command queueing,
  persistence, and the outer receipt table; this module only describes the
  next state and the effects a caller must run."
  (:refer-clojure :exclude [reduce])
  (:require [clojure.string :as str]))

(def status-labels
  {:idle "未开始"
   :starting "正在开始"
   :pending "等待你在浏览器完成登录"
   :authorized "已连接"
   :denied "账号授权被拒绝"
   :expired "授权已过期"
   :cancelled "已取消"
   :error "授权失败"
   :connected "已连接"
   :not-connected "未连接"
   :cancelling "正在取消"
   :logging-out "正在退出"
   :logging-in "正在登录"})

(def flow-type-labels
  {:authorization-code "浏览器回调"
   :device-code "设备码"})

(def initial-state
  {:providers []
   :selected-provider nil
   :selected-flow-type nil
   :connection-id nil
   :flow nil
   :status :idle
   :account nil
   :error nil
   :last-error nil
   :now-ms 0
   :copied? false
   :reveal-account? false})

(defn- result
  ([state] {:state state :effects []})
  ([state effects] {:state state :effects effects}))

(defn- message
  [state code text]
  (assoc state :error {:code code :message text}))

(defn- provider-by-id
  [state provider-id]
  (let [id-name (fn [value]
                  (if (keyword? value) (name value) (str value)))]
    (some #(when (= (id-name provider-id) (id-name (:id %))) %)
          (:providers state))))

(defn- selected-provider
  [state]
  (provider-by-id state (:selected-provider state)))

(defn masked-account
  "Mask an account label for a public screen."
  [label]
  (let [s (str (or label ""))]
    (cond
      (str/blank? s) "***"
      (str/includes? s "@")
      (let [[local domain] (str/split s #"@" 2)
            local (if (str/blank? local) "?" local)]
        (str (subs local 0 (min 1 (count local))) "***@"
             (or domain "")))
      (> (count s) 4) (str (subs s 0 2) "***" (subs s (- (count s) 2)))
      :else "***")))

(defn status-label
  [status]
  (get status-labels status "状态未知"))

(defn- value-name
  [value]
  (if (keyword? value) (name value) (str value)))

(defn account-label
  [state]
  (let [account (:account state)]
    (if (:reveal-account? state)
      (:account-label account)
      (or (:masked-account account)
          (masked-account (:account-label account))))))

(defn- provider-option
  [provider]
  {:id (:id provider)
   :name (:name provider)
   :description (:description provider)
   :flows (:flows provider)
   :configured? (boolean (:configured? provider))
   :client-id-env (:client-id-env provider)
   :base-url (:base-url provider)})

(defn- normalize-provider
  [provider]
  (let [id (or (:id provider) (:provider-id provider))]
    (assoc (provider-option provider)
           :id id
           :flows (vec (or (:flows provider) []))
           :configured? (not (false? (:configured? provider))))))

(defn set-providers
  [state providers]
  (let [providers (mapv normalize-provider providers)
        selected (or (some #(when (= (str (:selected-provider state))
                                     (str (:id %)))
                              (:id %))
                           providers)
                     (:id (first (filter :configured? providers)))
                     (:id (first providers)))]
    (assoc state :providers providers :selected-provider selected)))

(defn- selected-flow-type
  [state provider]
  (let [flows (set (:flows provider))
        requested (:selected-flow-type state)]
    (cond
      (contains? flows requested) requested
      (contains? flows :authorization-code) :authorization-code
      (contains? flows :device-code) :device-code
      :else nil)))

(defn- flow-pending?
  [flow]
  (= :pending (:status flow)))

(defn- flow-expired?
  [state flow]
  (let [expires-at (:expires-at-ms flow)
        now (:now-ms state)]
    (and expires-at now (>= (long now) (long expires-at)))))

(defn- begin-payload
  [state]
  (let [provider (selected-provider state)
        flow-type (selected-flow-type state provider)
        connection-id (:connection-id state)]
    (cond-> {:provider-id (:id provider)
             :flow-type flow-type
             :connection-id connection-id}
      (:replace? state) (assoc :replace? true))))

(defn begin
  "Start a login flow.  The caller supplies the current draft connection id."
  [state action]
  (let [state (assoc state
                     :connection-id (or (:connection-id action)
                                        (:connection-id state))
                     :selected-provider (or (:provider-id action)
                                            (:selected-provider state))
                     :selected-flow-type (or (:flow-type action)
                                             (:selected-flow-type state)))
        provider (selected-provider state)
        flow-type (selected-flow-type state provider)
        connection-id (:connection-id state)]
    (cond
      (nil? provider)
      (result (assoc (message state :MODEL_AUTH_PROVIDER_REQUIRED
                              "请先选择一个已支持的服务。")
                     :status :error))

      (not (:configured? provider))
      (result (assoc (message state :MODEL_AUTH_PROVIDER_NOT_CONFIGURED
                              (str "该服务尚未配置客户端身份"
                                   (when-let [env (:client-id-env provider)]
                                     (str "（" env "）"))
                                   "。"))
                     :status :error))

      (nil? flow-type)
      (result (assoc (message state :MODEL_AUTH_FLOW_UNSUPPORTED
                              "该服务没有可用的登录方式。")
                     :status :error))

      (str/blank? (str connection-id))
      (result (assoc (message state :MODEL_AUTH_CONNECTION_REQUIRED
                              "请先保存或选择一个连接草稿。")
                     :status :error))

      :else
      (result (assoc state :status :starting :error nil :last-error nil)
              [{:effect :submit
                :kind :model-auth/begin
                :payload (begin-payload state)}]))))

(defn- schedule-poll-effect
  [flow]
  (when (and flow (flow-pending? flow))
    {:effect :schedule-poll
     :flow-id (:flow-id flow)
     :after-ms (max 1000 (* 1000 (long (or (:interval-seconds flow) 2))))}))

(defn- begin-result
  [state receipt]
  (if (false? (:ok? receipt))
    (result (assoc state
                   :status :error
                   :error (:error receipt)))
    (let [receipt (or (:receipt receipt) receipt)
          flow receipt
          state (assoc state
                       :flow flow
                       :status (or (:status flow) :pending)
                       :account (or (:account flow) (:account state))
                       :error nil
                       :last-error (:last-error flow))
          effects (cond-> []
                    (:authorize-url flow)
                    (conj {:effect :open-url :url (:authorize-url flow)})

                    (schedule-poll-effect flow)
                    (conj (schedule-poll-effect flow)))]
      (result state effects))))

(defn- status-result
  [state receipt]
  (if (false? (:ok? receipt))
    (result (assoc state :status :error :error (:error receipt)))
    (let [receipt (or (:receipt receipt) receipt)
          status (:status receipt)
          state (assoc state
                       :flow (if (contains? receipt :flow-id)
                               receipt
                               (:flow state))
                       :status (or status (:status state))
                       :account (or (:account receipt) (:account state))
                       :last-error (:last-error receipt)
                       :error (when (= :error status) (:error receipt)))]
      (cond
        (= :authorized status)
        (result (assoc state :status :authorized :error nil)
                [{:effect :cancel-poll :flow-id (get-in state [:flow :flow-id])}])

        (= :logged-out status)
        (let [connection-id (or (:connection-id receipt)
                                (get-in state [:flow :connection-id])
                                (:connection-id state))
              accounts (remove (fn [account]
                                 (= (str connection-id)
                                    (str (:connection-id account))))
                               (or (:accounts state) []))]
          (result (assoc state :status :not-connected :account nil :flow nil
                         :error nil :accounts (vec accounts))
                [{:effect :cancel-poll :flow-id (get-in state [:flow :flow-id])}]))

        (= :not-connected status)
        (result (assoc state :status :not-connected :account nil :error nil))

        (= :pending status)
        (let [flow (:flow state)
              effect (schedule-poll-effect flow)]
          (result state (if effect [effect] [])))

        (contains? #{:denied :expired :cancelled :error} status)
        (result (assoc state :status status :error (:error receipt))
                [{:effect :cancel-poll :flow-id (get-in state [:flow :flow-id])}])

        :else
        (result state)))))

(defn- unwrap-receipt
  [receipt]
  (cond
    (not (map? receipt)) receipt
    (map? (:hc/receipt receipt)) (unwrap-receipt (:hc/receipt receipt))
    (map? (:receipt receipt)) (:receipt receipt)
    :else receipt))

(defn receipt-action
  "Map one gateway receipt to one action for `reduce`.

  The settings workspace can call this from its outer receipt handler.  Return
  nil when the receipt does not belong to model authentication."
  [kind receipt]
  (let [inner (unwrap-receipt receipt)
        kind (if (keyword? kind) kind (keyword (str kind)))]
    (case kind
      :model-auth/begin {:type :model-auth/begin-result :receipt inner}
      (:model-auth/poll :model-auth/status)
      {:type :model-auth/status-result :receipt inner}
      :model-auth/providers
      {:type :model-auth/providers :providers (:providers inner)}
      (:model-auth/cancel :model-auth/logout)
      {:type :model-auth/status-result :receipt inner}
      nil)))

(defn connection-patch
  "Return the draft fields to merge after a successful login.

  The result contains identity metadata only.  It never contains an access
  token, refresh token, device code, or client secret."
  [state]
  (let [account (:account state)
        provider (selected-provider state)
        status (:status state)]
    (when (and account (contains? #{:authorized :connected} status))
      (let [provider-id (or (:provider-id account) (:id provider))
            issuer (:issuer account)
            subject (:subject account)
            label (or (:account-label account)
                      (:account-label state)
                      subject)
            base-url (or (:base-url account) (:base-url provider))
            connection-id (or (:connection-id account)
                              (:connection-id state))]
        (cond-> {:service-kind :account-login
                 :auth {:oauth {:provider-id provider-id
                                :issuer issuer
                                :subject subject
                                :account-label label
                                :connection-id connection-id}}
                 :account-label label
                 :auth-ref connection-id}
          base-url (assoc :base-url base-url))))))

;; ---------------------------------------------------------------------------
;; Actions

(defn- select-provider
  [state action]
  (let [provider-id (or (:provider-id action) (:value action))
        provider (provider-by-id state provider-id)]
    (result (assoc state
                   :selected-provider (if provider (:id provider) provider-id)
                   :selected-flow-type (selected-flow-type
                                        state provider)
                   :error nil))))

(defn- select-flow-type
  [state action]
  (let [flow-type (or (:flow-type action) (:value action))
        flow-type (cond
                    (keyword? flow-type) flow-type
                    (string? flow-type) (keyword flow-type)
                    :else flow-type)]
    (result (assoc state :selected-flow-type flow-type :error nil))))

(defn- cancel
  [state]
  (if-let [flow-id (get-in state [:flow :flow-id])]
    (result (assoc state :status :cancelling)
            [{:effect :submit
              :kind :model-auth/cancel
              :payload {:flow-id flow-id}}])
    (result state)))

(defn- poll
  [state]
  (if-let [flow-id (get-in state [:flow :flow-id])]
    (result state
            [{:effect :submit
              :kind :model-auth/poll
              :payload {:flow-id flow-id}}])
    (result state)))

(defn- logout
  [state action]
  (let [connection-id (or (:connection-id action)
                          (get-in state [:account :connection-id])
                          (:connection-id state))]
    (if (str/blank? (str connection-id))
      (result (message state :MODEL_AUTH_CONNECTION_REQUIRED
                       "找不到要退出的账号连接。"))
      (result (assoc state :status :logging-out)
              [{:effect :submit
                :kind :model-auth/logout
                :payload {:connection-id connection-id}}]))))

(defn- status
  [state action]
  (let [connection-id (or (:connection-id action)
                          (:connection-id state))]
    (if (str/blank? (str connection-id))
      (result state)
      (result state
              [{:effect :submit
                :kind :model-auth/status
                :payload {:connection-id connection-id}}]))))

(defn- tick
  [state action]
  (let [state (assoc state :now-ms (:now-ms action))]
    (if (and (:flow state) (flow-expired? state (:flow state)))
      (result (assoc state :status :expired
                     :error {:code :MODEL_AUTH_FLOW_EXPIRED
                             :message "授权已过期，请重新开始。"})
              [{:effect :cancel-poll :flow-id (get-in state [:flow :flow-id])}])
      (result state))))

(defn- copy-code
  [state]
  (if-let [code (get-in state [:flow :user-code])]
    (result (assoc state :copied? true)
            [{:effect :copy-text :text code}])
    (result state)))

(defn reduce
  "Apply one action.  Return `{:state next-state :effects effects}`."
  [state action]
  (let [type (:type action)]
    (case type
      :model-auth/providers
      (result (set-providers state (:providers action)))

      :model-auth/select-provider
      (select-provider state action)

      :model-auth/select-flow-type
      (select-flow-type state action)

      :model-auth/begin
      (begin state action)

      :model-auth/begin-result
      (begin-result state (:receipt action))

      :model-auth/status-result
      (status-result state (:receipt action))

      :model-auth/status
      (status state action)

      :model-auth/poll
      (poll state)

      :model-auth/cancel
      (cancel state)

      :model-auth/logout
      (logout state action)

      :model-auth/tick
      (tick state action)

      :model-auth/copy-code
      (copy-code state)

      :model-auth/open-authorize
      (if-let [url (get-in state [:flow :authorize-url])]
        (result state [{:effect :open-url :url url}])
        (result state))

      :model-auth/reveal-account
      (result (update state :reveal-account? not))

      :model-auth/dismiss-error
      (result (assoc state :error nil :last-error nil))

      :model-auth/reset
      (result (assoc state
                     :flow nil :status :idle :account nil :error nil
                     :last-error nil :copied? false))

      :model-auth/error
      (result (assoc state :status :error
                     :error (or (:error action)
                                {:code :MODEL_AUTH_ERROR
                                 :message "登录失败。"})))

      (result state))))

(defn apply-snapshot
  "Apply a server snapshot that carries providers or saved accounts."
  [state snapshot]
  (let [state (cond-> state
                (contains? snapshot :model-auth/providers)
                (set-providers (:model-auth/providers snapshot))

                (contains? snapshot :model-auth/accounts)
                (assoc :accounts (:model-auth/accounts snapshot)))]
    ;; A saved account belongs to the connection selected in the draft.
    ;; Hydrate only an idle panel; a pending or logged-out panel stays as the
    ;; user left it and is never overwritten by a stale snapshot.
    (let [account (when (some? (:connection-id state))
                    (some (fn [account]
                            (when (= (str (:connection-id state))
                                     (str (:connection-id account)))
                              account))
                          (:accounts state)))]
      (if (and (= :idle (:status state)) account)
        (assoc state :account account :status :connected :error nil)
        state))))

;; ---------------------------------------------------------------------------
;; View tree

(defn- el
  ([tag] {:tag tag :attrs {} :children []})
  ([tag attrs] {:tag tag :attrs (or attrs {}) :children []})
  ([tag attrs & children]
   {:tag tag
    :attrs (or attrs {})
    :children (vec (remove nil? children))}))

(defn- button
  ([label action] (button label action {}))
  ([label action attrs]
   (el :button
       (merge {:type "button"
               :class "hc-button"
               :on-click {:action action}}
              attrs)
       label)))

(defn- field
  [label control & [hint]]
  (el :label {:class "hc-field"}
      (el :span {:class "hc-field__label"} label)
      control
      (when hint (el :small {:class "hc-field__hint"} hint))))

(defn- select
  [attrs options]
  (el :select (merge {:class "hc-select"} attrs)
      (mapv (fn [[value label]]
              (el :option {:value (value-name value)
                           :key (value-name value)} label))
            options)))

(defn- provider-select
  [state]
  (let [providers (:providers state)
        selected (:selected-provider state)]
    (field "账号服务"
           (select {:value (when selected (value-name selected))
                    :on-change {:action {:type :model-auth/select-provider}
                                :value :target-value}}
                   (mapv (fn [provider]
                           [(:id provider)
                            (str (:name provider)
                                 (when-not (:configured? provider)
                                   "（未配置客户端身份）"))])
                         providers))
           "只连接该服务的模型和额度，不同步本产品聊天记录。")))

(defn- flow-select
  [state]
  (let [provider (selected-provider state)
        flows (vec (:flows provider))]
    (when (> (count flows) 1)
      (field "登录方式"
             (select {:value (value-name
                              (or (selected-flow-type state provider)
                                  :authorization-code))
                      :on-change {:action {:type :model-auth/select-flow-type}
                                  :value :target-value}}
                     (mapv (fn [flow]
                             [flow (get flow-type-labels flow (name flow))])
                           flows))))))

(defn- pending-panel
  [state]
  (let [flow (:flow state)
        device? (= :device-code (:flow-type flow))
        account (:account state)]
    (el :section {:class "hc-model-auth hc-model-auth--pending"
                  :aria-live "polite"}
        (el :h3 {} (str "登录 "
                        (or (:name (selected-provider state)) "")))
        (if device?
          (el :div {}
              (el :p {} "在浏览器打开下面的地址，并输入设备码。")
              (el :p {}
                  (el :a {:href (:verification-uri flow)
                          :target "_blank"
                          :rel "noopener noreferrer"}
                      (:verification-uri flow)))
              (el :p {:class "hc-model-auth__code"}
                  (el :code {} (:user-code flow))
                  (button "复制设备码"
                          {:type :model-auth/copy-code}
                          {:class "hc-button hc-button--quiet"}))
              (when (:verification-uri-complete flow)
                (el :p {}
                    (el :a {:href (:verification-uri-complete flow)
                            :target "_blank"
                            :rel "noopener noreferrer"}
                        "打开完整授权地址"))))
          (el :p {} "等待你在浏览器完成登录。完成后回到这里，状态会自动更新。"))
        (when-let [expires-at (:expires-at flow)]
          (let [now (:now-ms state)
                remaining (when (and (number? expires-at)
                                     (number? now)
                                     (pos? now))
                            (max 0 (quot (- (long expires-at)
                                            (long now))
                                         1000)))]
            (el :small {:class "hc-field__hint"}
                (str "授权请求到期时间：" expires-at
                     (when (some? remaining)
                       (str "（剩余 " remaining " 秒）"))))))
        (when (:last-error state)
          (el :p {:class "hc-warning"} (:message (:last-error state))))
        (when account
          (el :p {:class "hc-muted"}
              (str "当前账号：" (account-label state))))
        (el :div {:class "hc-actions"}
            (when (:authorize-url flow)
              (button "重新打开授权页面"
                      {:type :model-auth/open-authorize}))
            (button "取消登录"
                    {:type :model-auth/cancel}
                    {:class "hc-button hc-button--quiet"})))))

(defn- authorized-panel
  [state]
  (let [account (:account state)
        provider-id (or (:provider-id account)
                        (get-in state [:flow :provider-id]))
        provider (some (fn [provider]
                         (when (= (str (:id provider)) (str provider-id))
                           provider))
                       (:providers state))]
    (el :section {:class "hc-model-auth hc-model-auth--authorized"}
        (el :h3 {} "账号已连接")
        (el :dl {:class "hc-details"}
            (el :dt {} "账号标识")
            (el :dd {} (account-label state))
            (el :dt {} "服务")
            (el :dd {} (or (get-in state [:flow :provider-name])
                           (:name provider)
                           (some-> provider-id name)
                           "未知"))
            (el :dt {} "授权范围")
            (el :dd {} (str/join " " (or (:scopes account) [])))
            (el :dt {} "最近同步")
            (el :dd {} (or (:last-synced-at account)
                           (:last-sync-at account)
                           "尚未同步")))
        (el :div {:class "hc-actions"}
            (button "刷新账号状态"
                    {:type :model-auth/status
                     :connection-id (or (:connection-id account)
                                        (:connection-id state))}
                    {:class "hc-button hc-button--quiet"})
            (button (if (:reveal-account? state)
                      "隐藏完整账号"
                      "显示完整账号")
                    {:type :model-auth/reveal-account}
                    {:class "hc-button hc-button--quiet"})
            (button "退出此账号"
                    {:type :model-auth/logout
                     :connection-id (or (:connection-id account)
                                        (:connection-id state))}
                    {:class "hc-button hc-button--danger"})))))

(defn- error-panel
  [state]
  (el :section {:class "hc-model-auth hc-model-auth--error" :role "alert"}
      (el :h3 {} "账号登录未完成")
      (el :p {} (or (get-in state [:error :message])
                    "授权没有完成，请重试。"))
      (when-let [code (get-in state [:error :code])]
        (el :small {:class "hc-field__hint"} (name code)))
      (el :div {:class "hc-actions"}
          (button "重新开始"
                  {:type :model-auth/begin
                   :connection-id (:connection-id state)
                   :provider-id (:selected-provider state)}
                  {:class "hc-button"})
          (button "关闭提示"
                  {:type :model-auth/dismiss-error}
                  {:class "hc-button hc-button--quiet"}))))

(defn login-panel
  "Return a settings-compatible hiccup tree for the account-login panel.

  Event descriptors live in attributes as `{:action {...}}`.  The tree has no
  password, username, or token input."
  [state]
  (let [status (:status state)]
    (el :section {:class "hc-panel hc-model-auth"}
        (el :h2 {} "第三方账号登录")
        (el :p {:class "hc-muted"}
            "使用服务自己的授权页面或设备码。Herdchastra 不会要求你粘贴第三方密码。")
        (when (empty? (:providers state))
          (el :p {:class "hc-warning"}
              "服务器尚未提供可用的账号服务。"))
        (provider-select state)
        (flow-select state)
        (case status
          (:idle :starting :logging-in)
          (el :div {:class "hc-actions"}
              (button "开始账号登录"
                      {:type :model-auth/begin
                       :connection-id (:connection-id state)
                       :provider-id (:selected-provider state)}
                      {:disabled (or (empty? (:providers state))
                                     (not (:configured? (selected-provider state))))}))

          :pending
          (pending-panel state)

          (:authorized :connected)
          (authorized-panel state)

          (:denied :expired :cancelled :error)
          (error-panel state)

          (:cancelling :logging-out)
          (el :p {:class "hc-muted"} (status-label status))

          (el :p {:class "hc-muted"} (status-label status))))))
