(ns hc.client.settings.view-test
  (:require [clojure.test :refer [deftest is testing]]
            [hc.client.settings.connection :as connection]
            [hc.client.settings.draft :as draft]
            [hc.client.settings.model :as model]
            [hc.client.settings.support :as support]
            [hc.client.settings.view :as view]))

(defn- normalized [id name url & [opts]]
  (connection/normalize-connection (apply support/connection id name url [opts])))

(defn- fixture []
  (-> (support/state
       {:connections {"a" (normalized "a" "Same" "https://a.example.com/v1")
                      "b" (normalized "b" "Same" "https://b.example.com/v1")}
        :models {"a" [(support/model "m" "Same Model"
                                     {:capabilities {:vision :unknown}
                                      :pricing :unknown})]
                 "b" [(support/model "m" "Same Model"
                                     {:capabilities {:vision :known}
                                      :pricing {:free? true}})]}
        :roles {:main {:connection-id "a" :model-id "m"
                       :mode :explicit :confirmed? true}}})
      (support/apply-action {:type :settings/open})
      (support/apply-action {:type :settings/select-connection
                             :connection-id "a"})
      (support/apply-action {:type :settings/edit-field
                             :draft-id "a"
                             :field :name
                             :value "Draft Name"})))

(deftest settings-tree-shows-both-same-name-rows-test
  (let [state (assoc-in (fixture) [:ui :section] :models)
        tree (view/settings-tree state)
        text (support/tree-text tree)]
    (is (re-find #"同名模型，按连接区分" text))
    (is (re-find #"a\.example\.com" text))
    (is (re-find #"b\.example\.com" text))
    (is (re-find #"视觉：尚未确认" text))
    (is (re-find #"视觉" text))
    (is (re-find #"免费（服务声明）" text))))

(deftest settings-tree-shows-draft-and-warning-test
  (let [state (fixture)
        tree (view/settings-tree state)
        text (support/tree-text tree)]
    (is (re-find #"有未保存修改" text))
    (is (re-find #"可能产生少量费用" text))
    (is (re-find #"Herdchestra connection test\. Reply with OK\." text))
    (is (re-find #"草稿" text))))

(deftest test-view-discloses-possible-charge-and-fixed-content-test
  (let [state (fixture)
        test (model/test-view state "a")]
    (is (re-find #"可能产生少量费用" (:warning test)))
    (is (= model/test-request-text (:request-text test)))
    (is (re-find #"不携带草稿、附件、会话历史或工具定义" (:warning test)))))

(deftest no-probe-test-view-hides-request-text-test
  (let [state (-> (support/state
                   {:connections
                    {"a" (normalized "a" "A" "https://a.example.com/v1"
                                     {:test-mode :none})}})
                  (support/apply-action {:type :settings/open})
                  (support/apply-action {:type :settings/select-connection
                                         :connection-id "a"}))
        test (model/test-view state "a")]
    (is (nil? (:request-text test)))
    (is (re-find #"无副作用" (:warning test)))))

(deftest save-button-disabled-before-sync-test
  (let [state (assoc-in (fixture) [:sync :phase] :connecting)
        tree (view/settings-tree state)
        save-button (first (filter #(re-find #"保存连接"
                                             (support/tree-text %))
                                   (support/buttons tree)))]
    (is save-button)
    (is (true? (get-in save-button [:attrs :disabled])))))

(deftest custom-host-warning-in-tree-test
  (let [state (support/state
               {:connections {"a" (normalized "a" "OpenAI"
                                              "https://gateway.example.com/v1"
                                              {:official? true
                                               :official-hosts #{"openai.com"}})}})
        text (support/tree-text (view/settings-tree state))]
    (is (re-find #"自定义主机（与官方地址不符）" text))
    (is (re-find #"实际主机" text))))

(deftest unknown-price-never-free-in-tree-test
  (let [state (support/state
               {:connections {"a" (normalized "a" "A" "https://a.example.com/v1")}
                :models {"a" [(support/model "m" "Mystery" {:pricing :unknown})]}})
        state (assoc-in state [:ui :section] :models)
        text (support/tree-text (view/settings-tree state))]
    (is (re-find #"未知" text))
    (is (not (re-find #"免费" text)))))

(deftest models-section-has-role-tabs-test
  (let [state (assoc-in (fixture) [:ui :section] :models)
        text (support/tree-text (view/settings-tree state))]
    (is (re-find #"主模型" text))
    (is (re-find #"任务模型" text))
    (is (re-find #"辅助模型" text))
    (is (re-find #"嵌入模型" text))
    (is (re-find #"视觉模型" text))
    (is (re-find #"检索不可用" text))))

(deftest onboarding-panel-test
  (let [text (support/tree-text (view/settings-tree (support/state)))]
    (is (re-find #"首次配置" text))
    (is (re-find #"无需注册" text))
    (is (re-find #"1 选择连接路径" text)))
  (let [state (support/apply-action (support/state)
                                    {:type :settings/dismiss-onboarding})
        text (support/tree-text (view/settings-tree state))]
    (is (not (re-find #"首次配置" text)))))

(deftest appearance-section-test
  (let [state (assoc-in (support/state) [:ui :section] :appearance)
        text (support/tree-text (view/settings-tree state))]
    (is (re-find #"外观与无障碍" text))
    (is (re-find #"字号" text))
    (is (re-find #"跟随系统" text))
    (is (re-find #"高对比度" text))
    (is (re-find #"减少动态" text))))

(deftest account-login-editor-does-not-demand-api-keys-test
  (let [state (-> (support/state)
                  (support/apply-action
                   {:type :settings/new-connection
                    :path :account-login
                    :draft-id "auth-draft"}))
        text (support/tree-text (view/settings-tree state))]
    (is (re-find #"第三方账号登录" text))
    (is (re-find #"不会使用 API 密钥" text))
    (is (not (re-find #"认证密钥" text)))
    (is (not (re-find #"至少需要一个启用的密钥" text)))))

(defn- old-local-draft-state
  []
  (-> (support/state)
      (support/apply-action {:type :settings/new-connection
                             :path :local-service
                             :draft-id "draft-old"
                             :key-id "old-key"
                             :auth-edit-id "old-edit"})
      (support/apply-action {:type :settings/edit-field
                             :draft-id "draft-old"
                             :field :name
                             :value "旧本地草稿"})
      (support/apply-action {:type :settings/add-manual-model
                             :draft-id "draft-old"
                             :model-id "old-model"})))

(defn- new-local-with-old-draft-state
  []
  (-> (old-local-draft-state)
      (support/apply-action {:type :settings/new-connection
                             :path :local-service
                             :draft-id "draft-new"
                             :key-id "new-key"
                             :auth-edit-id "new-edit"})))

(defn- button-action
  [tree label]
  (some (fn [button]
          (when (= label (support/tree-text button))
            (get-in button [:attrs :on-click :action])))
        (support/buttons tree)))

(deftest local-new-button-starts-an-independent-draft-test
  (let [state (old-local-draft-state)
        tree (view/settings-tree state)
        action (button-action tree "新建本地模型服务连接")
        next-state (support/apply-action state action)
        selected-id (get-in next-state [:ui :selected-draft-id])
        next-tree (view/settings-tree next-state)
        text (support/tree-text next-tree)]
    (is (= :settings/new-connection (:type action)))
    (is (= :local-service (:path action)))
    (is (nil? (:draft-id action)))
    (is (not= "draft-old" selected-id))
    (is (= "旧本地草稿"
           (get-in next-state [:drafts "draft-old" :fields :name])))
    (is (= ["old-model"]
           (get-in next-state [:drafts "draft-old" :manual-models])))
    (is (empty? (get-in next-state [:drafts selected-id :manual-models])))
    (is (= "" (model/manual-model-input next-state selected-id)))
    (is (re-find #"新建连接：本地模型服务" text))
    (is (not (re-find #"old-model" text)))))

(deftest new-local-editor-has-clear-identity-test
  (let [state (new-local-with-old-draft-state)
        tree (view/settings-tree state)
        heading (first (filter #(= "hc-editor-title" (get-in % [:attrs :id]))
                               (support/nodes tree)))
        text (support/tree-text tree)]
    (is (= "新建连接：本地模型服务" (support/tree-text heading)))
    (is (re-find #"独立的新连接草稿" text))
    (is (re-find #"不会继承其他连接的模型或密钥" text))
    (is (re-find #"新建本地模型服务连接" text))
    (is (re-find #"撤销新建并返回" text))
    (is (not (re-find #"old-model" text))
        "a fresh local draft must not render the previous draft's model chips")))

(deftest unsaved-drafts-list-keeps-old-draft-reachable-test
  (let [state (new-local-with-old-draft-state)
        tree (view/settings-tree state)
        text (support/tree-text tree)
        draft-items (support/nodes-with-class tree "hc-draft-list__item")]
    (is (re-find #"未保存草稿" text))
    (is (re-find #"旧本地草稿" text))
    (is (re-find #"1 个手动模型" text))
    (is (= 2 (count draft-items)))
    (is (some #(re-find #"继续编辑" (support/tree-text %)) draft-items))
    (is (some #(re-find #"正在编辑" (support/tree-text %)) draft-items))))

(deftest edited-new-draft-offers-return-not-undo-test
  (let [state (-> (new-local-with-old-draft-state)
                  (support/apply-action {:type :settings/edit-field
                                         :draft-id "draft-new"
                                         :field :name
                                         :value "Keep me"}))
        tree (view/settings-tree state)
        heading (first (filter #(= "hc-editor-title" (get-in % [:attrs :id]))
                               (support/nodes tree)))
        text (support/tree-text tree)]
    (is (= "新建连接：本地模型服务 · Keep me"
           (support/tree-text heading)))
    (is (re-find #"返回上一条草稿" text))
    (is (not (re-find #"撤销新建并返回" text)))))

(deftest continuing-old-draft-restores-its-model-chips-test
  (let [state (-> (new-local-with-old-draft-state)
                  (support/apply-action {:type :settings/select-draft
                                         :draft-id "draft-old"}))
        text (support/tree-text (view/settings-tree state))]
    (is (re-find #"old-model" text))
    (is (re-find #"旧本地草稿" text))))

(deftest editor-identity-names-the-saved-connection-test
  (let [state (-> (support/state
                   {:connections {"a" (normalized "a" "Work gateway"
                                                  "https://work.example.com/v1")}})
                  (support/apply-action {:type :settings/open})
                  (support/apply-action {:type :settings/select-connection
                                         :connection-id "a"}))
        tree (view/settings-tree state)
        heading (first (filter #(= "hc-editor-title" (get-in % [:attrs :id]))
                               (support/nodes tree)))]
    (is (= "编辑连接：Work gateway" (support/tree-text heading)))))

(deftest connection-editor-toggles-enable-without-model-rows-test
  (let [state (-> (support/state
                   {:connections {"a" (normalized "a" "Local only"
                                                  "http://127.0.0.1:11434/v1"
                                                  {:service-kind :local-service
                                                   :location :instance-host
                                                   :enabled? true})}})
                  (support/apply-action {:type :settings/open})
                  (support/apply-action {:type :settings/select-connection
                                         :connection-id "a"}))
        tree (view/settings-tree state)
        text (support/tree-text tree)
        toggle (some (fn [button]
                       (when (= "停用此连接" (support/tree-text button))
                         button))
                     (support/buttons tree))
        draft (get-in state [:drafts "a"])
        action (get-in toggle [:attrs :on-click :action])
        disabled-state (support/apply-action state action)
        disabled-draft (get-in disabled-state [:drafts "a"])
        disabled-tree (view/settings-tree disabled-state)
        disabled-text (support/tree-text disabled-tree)
        enable-toggle (some (fn [button]
                              (when (= "启用此连接" (support/tree-text button))
                                button))
                            (support/buttons disabled-tree))
        enable-action (get-in enable-toggle [:attrs :on-click :action])
        enabled-state (support/apply-action disabled-state enable-action)
        enabled-draft (get-in enabled-state [:drafts "a"])]
    (is (re-find #"连接状态" text))
    (is (re-find #"已启用" text))
    (is toggle
        "a connection with no model rows still has a disable button")
    (is (= :settings/edit-field (:type action)))
    (is (= :enabled? (:field action)))
    (is (false? (:value action))
        "the disable button sends the next value, not the current one")
    (is (false? (get-in disabled-draft [:fields :enabled?])))
    (is (true? (:dirty? disabled-draft)))
    (is (not= (connection/draft-digest draft)
              (connection/draft-digest disabled-draft)))
    (is (false? (:enabled? (connection/server-connection disabled-draft))))
    (is (re-find #"已停用" disabled-text))
    (is enable-toggle
        "the disabled editor offers an enable button")
    (is (= :settings/edit-field (:type enable-action)))
    (is (= :enabled? (:field enable-action)))
    (is (true? (:value enable-action)))
    (is (true? (get-in enabled-draft [:fields :enabled?])))
    (is (not (draft/dirty? enabled-draft))
        "re-enabling restores the saved digest")
    (is (= :clean (draft/status enabled-draft)))))

(deftest disabled-connection-is-labelled-in-models-section-test
  (let [state (-> (support/state
                   {:connections {"a" (normalized "a" "Local only"
                                                  "http://127.0.0.1:11434/v1"
                                                  {:service-kind :local-service
                                                   :location :instance-host
                                                   :enabled? false})}
                    :models {"a" []}})
                  (support/apply-action {:type :settings/open}))
        text (support/tree-text
              (view/settings-tree (assoc-in state [:ui :section] :models)))]
    (is (re-find #"已停用" text))
    (is (re-find #"没有模型行" text))
    (is (re-find #"供应商与连接" text))
    (is (re-find #"编辑连接" text))))

(deftest model-capability-editor-declares-tools-test
  (let [state (-> (support/state
                   {:connections {"a" (normalized "a" "A"
                                                  "https://a.example.com/v1")}
                    :models {"a" [(support/model "m" "Manual Model"
                                                 {:source :manual
                                                  :capabilities {}})]}})
                  (support/apply-action {:type :settings/open})
                  (support/apply-action {:type :settings/select-connection
                                         :connection-id "a"})
                  (support/apply-action
                   {:type :settings/set-model-capability
                    :draft-id "a"
                    :model-id "m"
                    :capability :tools
                    :value "known"})
                  (assoc-in [:ui :section] :models))
        draft (get-in state [:drafts "a"])
        text (support/tree-text (view/settings-tree state))]
    (is (true? (get-in draft [:models 0 :capabilities :tools])))
    (is (= :user (get-in draft [:models 0 :capability-sources :tools])))
    (is (re-find #"能力设置" text))
    (is (re-find #"用户设置" text))
    (is (re-find #"声明支持" text))
    (is (re-find #"恢复已知信息" text))))

(deftest edit-capability-button-opens-the-draft-in-place-test
  (let [state (-> (support/state
                   {:connections {"a" (normalized "a" "A"
                                                  "https://a.example.com/v1")}
                    :models {"a" [(support/model "m" "Manual Model")]}})
                  (assoc-in [:ui :section] :models))
        action (button-action (view/settings-tree state) "编辑能力")
        next-state (support/apply-action state action)
        draft (get-in next-state [:drafts "a"])]
    (is (= :settings/edit-model-capabilities (:type action)))
    (is (= :models (get-in next-state [:ui :section]))
        "opening a capability draft must not navigate away from the rows")
    (is (= "a" (get-in next-state [:ui :selected-draft-id])))
    (is (= ["m"] (mapv :model-id (:models draft))))
    (is (contains? (set (:enabled-models draft)) "m")
        "a seeded model stays enabled in the draft")
    (is (not (draft/dirty? draft)))))

(deftest storage-failure-shows-persistent-warning-test
  (let [state (assoc-in (support/state) [:ui :draft-storage-status] :failed)
        text (support/tree-text (view/settings-tree state))]
    (is (re-find #"草稿暂未保存" text))))

(deftest model-detail-shows-value-source-and-limit-test
  (let [state (assoc-in (fixture) [:ui :section] :models)
        text (support/tree-text (view/settings-tree state))]
    (is (re-find #"模型详情与有效配置" text))
    (is (re-find #"显示名" text))
    (is (re-find #"模型标识" text))
    (is (re-find #"温度" text))
    (is (re-find #"top-p" text))
    (is (re-find #"值" text))
    (is (re-find #"来源" text))
    (is (re-find #"限制" text))))

(deftest running-session-offers-stop-and-switch-test
  (let [state (-> (fixture)
                  (assoc-in [:ui :section] :models)
                  (assoc-in [:ui :current-session-id] "s1")
                  (assoc-in [:ui :session-running?] true))
        tree (view/settings-tree state)
        text (support/tree-text tree)
        action (button-action tree "停止当前轮并切换")]
    (is (re-find #"用于下一轮" text))
    (is (= :settings/select-session-model (:type action)))
    (is (= :stop-and-switch (:mode action)))
    (is (= "s1" (:session-id action)))))

(deftest first-new-draft-can-be-discarded-test
  (let [state (support/apply-action
               (support/state)
               {:type :settings/new-connection
                :path :local-service
                :draft-id "draft-only"})
        tree (view/settings-tree state)
        action (button-action tree "撤销新建并返回")
        after (support/apply-action state action)]
    (is (= :settings/back-draft (:type action)))
    (is (nil? (get-in after [:drafts "draft-only"])))
    (is (nil? (get-in after [:ui :selected-draft-id])))))
