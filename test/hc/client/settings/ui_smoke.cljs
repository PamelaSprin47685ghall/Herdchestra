(ns hc.client.settings.ui-smoke
  "Node smoke check for the real React settings UI.

  Run:
    clojure -M:cljs:client -co '{:target :nodejs
                                 :output-to \"target/settings-ui/main.js\"
                                 :output-dir \"target/settings-ui\"
                                 :node-modules-dirs [\"/tmp/hc-settings-ui/node_modules\"]}'
      -m hc.client.settings.ui-smoke

  The check renders the component with React DOM server and asserts the
  disambiguation, unknown labels, and receipt-gated draft clearing."
  (:require [clojure.string :as str]
            [hc.client.settings :as settings]
            [hc.client.settings.connection :as connection]
            ["react-dom/server" :as react-dom-server]))

(defn- connection
  [id name url opts]
  (connection/normalize-connection
   (merge {:connection-id id
           :name name
           :base-url url
           :service-kind :cloud-key
           :protocol :openai-compatible
           :status :connected
           :test-mode :minimal-request
           :auth-keys [{:key-id (str id "-key")
                        :alias "主密钥"
                        :has-key? true
                        :auth-revision (str id "-rev")
                        :status :ready}]}
          opts)))

(defn- model
  [id display-name opts]
  (merge {:model-id id
          :display-name display-name
          :capabilities {:vision :unknown}
          :availability :available
          :pricing :unknown
          :context-window :unknown}
         opts))

(defn- check [condition message]
  (when-not condition
    (throw (js/Error. (str "SMOKE FAIL: " message)))))

(defn- includes? [text needle]
  (str/includes? text needle))

(def state
  (let [a (connection "a" "Same" "https://a.example.com/v1" {})
        b (connection "b" "Same" "https://b.example.com/v1"
                      {:official? true :official-hosts #{"b.example.com"}})]
    (-> (settings/initial-state
         {:connections {"a" a "b" b}
          :models {"a" [(model "m" "Same Model" {})]
                   "b" [(model "m" "Same Model" {:pricing :unknown})]}
          :roles {:main {:connection-id "a" :model-id "m"
                         :mode :explicit :confirmed? true}}
          :sync {:phase :synced :command-epoch 7}
          :transport-available? true})
        (settings/apply-snapshot {:settings/by-id {"a" a "b" b}
                                  :settings/models
                                  {"a" [(model "m" "Same Model"
                                               {:pricing :unknown})]
                                   "b" [(model "m" "Same Model"
                                               {:pricing :unknown})]}})
        (settings/open-settings))))

(defn- act [state action]
  (:state (settings/reduce-action state action)))

(defn -main []
  (let [models-state (assoc-in state [:ui :section] :models)
        html (react-dom-server/renderToStaticMarkup
              (settings/settings-element models-state (fn [_]) true))]
    (check (includes? html "同名模型，按连接区分")
           "same-name models are not disambiguated")
    (check (includes? html "Same Model")
           "the model display name is missing")
    (check (includes? html "任务模型")
           "the @task role tab is missing")
    (check (includes? html "视觉：尚未确认")
           "unknown capability is not shown")
    (check (includes? html "a.example.com")
           "connection A source is missing")
    (check (includes? html "b.example.com")
           "connection B source is missing")
    (check (includes? html "未知")
           "unknown pricing is missing")
    (check (not (includes? html "免费"))
           "unknown pricing was labelled free"))

  (let [provider-state (-> state
                           (act {:type :settings/select-connection
                                 :connection-id "a"})
                           (act {:type :settings/edit-field
                                 :draft-id "a"
                                 :field :name
                                 :value "Draft survives"}))
        html (react-dom-server/renderToStaticMarkup
              (settings/settings-element provider-state (fn [_]) true))]
    (check (includes? html "Draft survives")
           "draft edit is not rendered")
    (check (includes? html "有未保存修改")
           "draft status is not rendered")
    (check (includes? html "新建本地模型服务连接")
           "the explicit local-connection entry is missing")
    (check (includes? html "每次新建都打开一份独立草稿")
           "the new-draft preservation note is missing")
    (check (includes? html "可能产生少量费用")
           "the possible test charge is not disclosed before the click")
    (check (includes? html "Herdchestra connection test. Reply with OK.")
           "the fixed test content is not shown before the click")
    (check (includes? html "保存连接")
           "save action is missing"))

  (let [state-atom (atom (act state {:type :settings/select-connection
                                     :connection-id "a"}))
        seen (atom [])
        submit! (fn [envelope]
                  (swap! seen conj envelope)
                  nil)]
    (settings/dispatch! state-atom
                        {:type :settings/edit-field
                         :draft-id "a"
                         :field :name
                         :value "Submitted"}
                        {:submit! submit!})
    (settings/dispatch! state-atom
                        {:type :settings/save-connection
                         :draft-id "a"}
                        {:submit! submit!})
    (check (= 1 (count @seen)) "save did not produce exactly one command")
    (let [envelope (first @seen)]
      (check (= :settings/save-connection (get-in envelope [:payload :kind]))
             "wrong command kind")
      (check (= 7 (:command-epoch envelope)) "wrong command epoch")
      (check (string? (:command-id envelope)) "missing command id"))
    (check (some? (get-in @state-atom [:drafts "a"]))
           "draft cleared before a receipt")
    (let [envelope (first @seen)
          receipt {:command-id (:command-id envelope)
                   :status :accepted
                   :draft-id "a"
                   :draft-version (get-in envelope [:payload :draft-version])
                   :config-digest (get-in envelope [:payload :config-digest])
                   :saved-config-digest (get-in envelope [:payload :config-digest])}]
      (reset! state-atom (settings/apply-receipt @state-atom receipt))
      (check (nil? (get-in @state-atom [:drafts "a"]))
             "draft was not cleared by the accepted receipt")))

  (let [state-atom (atom (act state {:type :settings/select-connection
                                     :connection-id "a"}))
        command-id (atom nil)
        submit! (fn [envelope]
                  (reset! command-id (:command-id envelope))
                  {:command-id (:command-id envelope)
                   :status :accepted
                   :draft-id "a"
                   :draft-version (get-in envelope [:payload :draft-version])
                   :config-digest (get-in envelope [:payload :config-digest])
                   :saved-config-digest (get-in envelope [:payload :config-digest])})]
    (settings/dispatch! state-atom
                        {:type :settings/edit-field
                         :draft-id "a"
                         :field :name
                         :value "Sync submitted"}
                        {:submit! submit!})
    (settings/dispatch! state-atom
                        {:type :settings/save-connection
                         :draft-id "a"}
                        {:submit! submit!})
    (check (map? @state-atom) "sync receipt replaced the state map")
    (check (nil? (get-in @state-atom [:drafts "a"]))
           "sync accepted receipt did not clear the exact draft")
    (check (= :accepted (get-in @state-atom [:commands @command-id :status]))
           "sync receipt was not recorded"))

  (let [state-atom (atom (act state {:type :settings/select-connection
                                     :connection-id "a"}))]
    (settings/dispatch! state-atom
                        {:type :settings/save-connection
                         :draft-id "a"}
                        {:submit! (fn [_] false)})
    (check (= :outcome-unknown
              (some-> @state-atom :commands vals first :status))
           "a false transport result must be outcome-unknown, not success")
    (check (empty? (:outbox @state-atom))
           "a false transport result must not leave a stuck outbox"))

  (let [appearance-state (assoc state
                                :appearance {:theme :dark :font-scale 120
                                             :contrast :high
                                             :reduced-motion :reduce}
                                :ui (assoc (:ui state) :section :appearance))
        html (react-dom-server/renderToStaticMarkup
              (settings/settings-element appearance-state (fn [_]) true))]
    (check (includes? html "外观与无障碍") "appearance section missing")
    (check (includes? html "跟随系统") "theme controls missing")
    (check (includes? html "高对比度") "contrast control missing")
    (check (includes? html "减少动态") "reduced-motion control missing")
    (check (includes? html "aria-pressed=\"true\"") "theme pressed state missing")
    (check (includes? html "type=\"button\"") "theme button type missing"))

  (let [auth-state (-> (settings/initial-state
                        {:sync {:phase :synced :command-epoch 7}
                         :transport-available? true})
                       (act {:type :settings/new-connection
                             :path :account-login
                             :draft-id "draft-auth"
                             :key-id "k" :auth-edit-id "e"})
                       (act {:type :model-auth/providers
                             :providers [{:id "demo" :name "Demo"
                                          :configured? true
                                          :flows [:device-code]}]}))
        html (react-dom-server/renderToStaticMarkup
              (settings/settings-element auth-state (fn [_]) true))]
    (check (includes? html "首次配置") "onboarding panel missing")
    (check (includes? html "无需注册") "onboarding copy missing")
    (check (includes? html "第三方账号登录") "model-auth panel missing")
    (check (includes? html "开始账号登录") "model-auth begin action missing")
    (check (includes? html "设备码") "device-code flow missing"))

  (println "SMOKE OK"))
