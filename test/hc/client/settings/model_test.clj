(ns hc.client.settings.model-test
  (:require [clojure.test :refer [deftest is testing]]
            [hc.client.settings.connection :as connection]
            [hc.client.settings.draft :as draft]
            [hc.client.settings.model :as model]
            [hc.client.settings.support :as support]
            [hc.client.settings.view :as view]))

(defn- normalized [id name url & [opts]]
  (connection/normalize-connection (apply support/connection id name url [opts])))

(defn- fixture-state []
  (support/state
   {:connections {"a" (normalized "a" "Same" "https://a.example.com/v1")
                  "b" (normalized "b" "Same" "https://b.example.com/v1")}
    :models {"a" [(support/model "m" "Same Model"
                                 {:capabilities {:vision :unknown}
                                  :pricing :unknown})]
             "b" [(support/model "m" "Same Model"
                                 {:capabilities {:vision :known}
                                  :pricing {:free? true}})]}
    :roles {:main {:connection-id "a" :model-id "m" :mode :explicit
                   :confirmed? true}
            :utility {:mode :follow-main :confirmed? false}
            :embedding {:mode :unset}
            :vision {:mode :follow-main}}})) 

(deftest same-name-model-groups-test
  (let [state (fixture-state)
        groups (model/model-groups state)]
    (is (= 2 (count groups)))
    (is (= 2 (count (mapcat :rows groups))))
    (is (every? :duplicate-name? groups))
    (is (every? #(re-find #"example\.com" (:source-label %))
                (mapcat :rows groups)))))

(deftest role-status-test
  (let [state (fixture-state)
        cards (model/role-cards state)]
    (is (re-find #"检索不可用"
                 (:status (first (filter #(= :embedding (:role %)) cards)))))
    (is (re-find #"需要确认"
                 (:status (first (filter #(= :utility (:role %)) cards)))))
    (is (re-find #"当前回复|当前：|Same Model"
                 (:status (first (filter #(= :main (:role %)) cards))))))
  (let [task (first (filter #(= :task (:role %))
                            (model/role-cards (fixture-state))))]
    (is (= "任务模型" (:label task)))
    (is (re-find #"未选择任务模型" (:status task)))
    (is (nil? (:selection-label task)))))

(deftest task-role-save-sends-concrete-binding-test
  (let [state (-> (support/state
                   {:connections {"a" (normalized "a" "A" "https://a.example.com/v1")}
                    :models {"a" [(support/model "m" "Task Model")]}})
                  (support/apply-action {:type :settings/select-model
                                         :role :task
                                         :connection-id "a"
                                         :model-id "m"})
                  (support/apply-action {:type :settings/save-role
                                         :role :task
                                         :command-id "task-1"}))
        roles (get-in state [:commands "task-1" :payload :roles])]
    (is (= "a" (get-in roles [:task :connection])))
    (is (= "m" (get-in roles [:task :model])))
    (is (not (contains? roles :main))
        "an unset main role must not be persisted as a mode-only binding")
    (is (= :follow-main (get-in roles [:utility :mode])))
    (is (= :unset (get-in roles [:embedding :mode])))))

(deftest configured-main-role-survives-task-save-test
  (let [state (-> (fixture-state)
                  (support/apply-action {:type :settings/save-role
                                         :role :main
                                         :command-id "main-1"}))
        roles (get-in state [:commands "main-1" :payload :roles])]
    (is (= "a" (get-in roles [:main :connection])))
    (is (= "m" (get-in roles [:main :model])))
    (is (not (contains? roles :task))
        "an unset task role must be omitted rather than sent as :unset")))

(deftest configured-task-role-shows-selection-test
  (let [state (-> (fixture-state)
                  (support/apply-action {:type :settings/select-model
                                         :role :task
                                         :connection-id "b"
                                         :model-id "m"}))
        card (first (filter #(= :task (:role %))
                            (model/role-cards state)))]
    (is (re-find #"当前：" (:status card)))
    (is (re-find #"Same Model" (:selection-label card)))
    (is (true? (:dirty? card)))))

(deftest confirm-utility-test
  (let [state (fixture-state)
        confirmed (support/apply-action state
                                        {:type :settings/confirm-role
                                         :role :utility})
        card (first (filter #(= :utility (:role %))
                            (model/role-cards confirmed)))]
    (is (true? (:confirmed? card)))
    (is (re-find #"跟随主模型" (:status card)))))

(deftest snapshot-replaces-and-orphans-test
  (let [state (-> (fixture-state)
                  (support/apply-action {:type :settings/open})
                  (support/apply-action {:type :settings/select-connection
                                         :connection-id "a"}))
        snapshot {:settings/by-id {"b" (normalized "b" "Same" "https://b.example.com/v1")}
                  :settings/models {"b" [(support/model "m2" "Only B")]}
                  :settings/roles {:main {:connection-id "b" :model-id "m2"
                                          :mode :explicit :confirmed? true}}
                  :sync {:phase :synced :command-epoch 9}
                  :session/by-id {"s1" {:model-snapshot {:connection-id "b"
                                                         :model-id "m2"
                                                         :display-name "Only B"}}}}
        next-state (:state (model/apply-snapshot state snapshot))]
    (is (= #{"b"} (set (keys (get-in next-state [:server :connections])))))
    (is (= "Only B"
           (get-in next-state [:server :models "b" 0 :display-name])))
    (is (true? (get-in next-state [:drafts "a" :orphaned?])))
    (is (= "Same" (get-in next-state [:drafts "a" :fields :name])))
    (is (= 9 (get-in next-state [:sync :command-epoch])))
    (is (= "Only B"
           (get-in next-state [:server :session-model-snapshots "s1" :display-name])))
    (is (empty? (:tests next-state)))))

(deftest snapshot-does-not-resurrect-models-test
  (let [state (support/state {:connections {"a" (normalized "a" "A" "https://a.example.com/v1")}
                              :models {"a" [(support/model "old" "Old")]}})
        snapshot {:settings/by-id {"a" (normalized "a" "A" "https://a.example.com/v1")}
                  :settings/models {"a" [(support/model "new" "New")]}}
        next-state (:state (model/apply-snapshot state snapshot))]
    (is (= #{"new"} (set (map :model-id (get-in next-state [:server :models "a"])))))))

(deftest stale-server-revision-does-not-overwrite-test
  (let [state (-> (support/state
                   {:connections {"a" (normalized "a" "A" "https://a.example.com/v1")}
                    :models {"a" [(support/model "old" "Old")]}})
                  (assoc-in [:sync :server-revision] 7)
                  (assoc-in [:ui :section] :models)
                  (assoc-in [:ui :selected-role] :vision))
        stale {:server-revision 6
               :settings/by-id
               {"b" (normalized "b" "B" "https://b.example.com/v1")}
               :settings/models {"b" [(support/model "new" "New")]}
               :sync {:phase :synced :command-epoch 3 :server-revision 6}}
        next-state (:state (model/apply-snapshot state stale))]
    (is (= #{"a"} (set (keys (get-in next-state [:server :connections]))))
        "a delayed older baseline must not restore removed connections")
    (is (= 7 (get-in next-state [:sync :server-revision])))
    (is (= :models (get-in next-state [:ui :section])))
    (is (= :vision (get-in next-state [:ui :selected-role])))))

(deftest snapshot-preserves-local-section-and-role-test
  (let [state (-> (support/state
                   {:connections {"a" (normalized "a" "A" "https://a.example.com/v1")}
                    :models {"a" [(support/model "old" "Old")]}})
                  (assoc-in [:sync :server-revision] 7)
                  (assoc-in [:ui :section] :models)
                  (assoc-in [:ui :selected-role] :vision))
        fresh {:server-revision 8
               :settings/by-id
               {"b" (normalized "b" "B" "https://b.example.com/v1")}
               :settings/models {"b" [(support/model "new" "New")]}
               :sync {:phase :synced :command-epoch 4 :server-revision 8}}
        next-state (:state (model/apply-snapshot state fresh))]
    (is (= #{"b"} (set (keys (get-in next-state [:server :connections])))))
    (is (= 8 (get-in next-state [:sync :server-revision])))
    (is (= :models (get-in next-state [:ui :section]))
        "a server projection must not reset the operator's section")
    (is (= :vision (get-in next-state [:ui :selected-role]))
        "a server projection must not reset the operator's role tab")))

(deftest custom-gateway-and-unknown-labels-test
  (let [state (support/state
               {:connections {"a" (normalized "a" "OpenAI" "https://gateway.example.com/v1"
                                              {:official? true
                                               :official-hosts #{"openai.com"}})}
                :models {"a" [(support/model "m" "Mystery"
                                             {:capabilities {:vision :unknown}
                                              :pricing :unknown})]}})
        state (assoc-in state [:ui :section] :models)
        tree (view/settings-tree state)
        text (support/tree-text tree)]
    (is (re-find #"自定义主机（与官方地址不符）" text))
    (is (re-find #"视觉：尚未确认" text))
    (is (re-find #"未知" text))
    (is (not (re-find #"免费" text)))))

(deftest draft-preserved-in-snapshot-test
  (let [state (-> (fixture-state)
                  (support/apply-action {:type :settings/open})
                  (support/apply-action {:type :settings/select-connection
                                         :connection-id "a"})
                  (support/apply-action {:type :settings/edit-field
                                         :draft-id "a"
                                         :field :name
                                         :value "Draft survives"}))
        snapshot {:settings/by-id {"a" (normalized "a" "Same" "https://a.example.com/v1")}
                  :settings/models {"a" []}}
        next-state (:state (model/apply-snapshot state snapshot))]
    (is (= "Draft survives"
           (get-in next-state [:drafts "a" :fields :name])))
    (is (draft/dirty? (get-in next-state [:drafts "a"])))))

(deftest clean-draft-refresh-keeps-ui-input-test
  (let [connection (normalized "a" "A" "https://a.example.com/v1")
        state (-> (support/state {:connections {"a" connection}})
                  (assoc-in [:sync :server-revision] 1)
                  (assoc-in [:drafts "custom"]
                            (assoc (draft/draft-from-connection connection)
                                   :draft-id "custom"))
                  (assoc-in [:ui :selected-draft-id] "custom")
                  (assoc-in [:ui :manual-model-inputs "custom"] "half-typed")
                  (assoc-in [:ui :draft-history] ["custom"]))
        snapshot {:server-revision 2
                  :settings/by-id {"a" connection}
                  :sync {:phase :synced :command-epoch 7
                         :server-revision 2}}
        next-state (:state (model/apply-snapshot state snapshot))]
    (is (nil? (get-in next-state [:drafts "custom"])))
    (is (some? (get-in next-state [:drafts "a"])))
    (is (= "a" (get-in next-state [:ui :selected-draft-id])))
    (is (= "half-typed" (model/manual-model-input next-state "a")))
    (is (= ["a"] (get-in next-state [:ui :draft-history])))))

(defn- unsaved-local-state
  []
  (-> (support/state {:connections {"a" (normalized "a" "A"
                                                  "https://a.example.com/v1")}})
      (support/apply-action {:type :settings/new-connection
                             :path :local-service
                             :draft-id "draft-old"
                             :key-id "old-key"
                             :auth-edit-id "old-edit"})
      (support/apply-action {:type :settings/edit-field
                             :draft-id "draft-old"
                             :field :name
                             :value "Old local"})
      (support/apply-action {:type :settings/add-manual-model
                             :draft-id "draft-old"
                             :model-id "old-model"})
      (support/apply-action {:type :settings/edit-manual-model-input
                             :draft-id "draft-old"
                             :value "half-typed"})))

(deftest new-connection-does-not-reuse-unsaved-draft-test
  (let [state (unsaved-local-state)
        next-state (support/apply-action
                    state
                    {:type :settings/new-connection
                     :path :local-service
                     :draft-id "draft-new"
                     :key-id "new-key"
                     :auth-edit-id "new-edit"})
        old-draft (get-in next-state [:drafts "draft-old"])
        new-draft (get-in next-state [:drafts "draft-new"])]
    (is (= "draft-new" (get-in next-state [:ui :selected-draft-id])))
    (is (= "Old local" (get-in old-draft [:fields :name])))
    (is (= ["old-model"] (:manual-models old-draft)))
    (is (= #{"old-model"} (set (:enabled-models old-draft))))
    (is (= "half-typed" (model/manual-model-input next-state "draft-old")))
    (is (= :new (:mode new-draft)))
    (is (nil? (:connection-id new-draft)))
    (is (empty? (:models new-draft)))
    (is (empty? (:keys new-draft)))
    (is (empty? (:manual-models new-draft)))
    (is (empty? (:enabled-models new-draft)))
    (is (= "" (model/manual-model-input next-state "draft-new")))
    (is (= ["draft-old"] (get-in next-state [:ui :draft-history])))
    (is (= #{"draft-old" "draft-new"}
           (set (keys (:drafts next-state)))))
    (is (some #(= "draft-old" (:draft-id %))
              (model/draft-cards next-state)))))

(deftest new-connection-never-overwrites-an-existing-draft-id-test
  (let [state (unsaved-local-state)
        next-state (support/apply-action
                    state
                    {:type :settings/new-connection
                     :path :local-service
                     :draft-id "draft-old"})
        selected-id (get-in next-state [:ui :selected-draft-id])
        selected (get-in next-state [:drafts selected-id])]
    (is (not= "draft-old" selected-id))
    (is (= "Old local" (get-in next-state [:drafts "draft-old" :fields :name])))
    (is (= ["old-model"]
           (get-in next-state [:drafts "draft-old" :manual-models])))
    (is (= :new (:mode selected)))
    (is (empty? (:manual-models selected)))
    (is (= ["draft-old"] (get-in next-state [:ui :draft-history])))))

(deftest new-connection-does-not-alias-a-saved-connection-id-test
  (let [state (support/state
               {:connections {"a" (normalized "a" "A"
                                              "https://a.example.com/v1")}})
        next-state (support/apply-action
                    state
                    {:type :settings/new-connection
                     :path :local-service
                     :draft-id "a"})
        selected-id (get-in next-state [:ui :selected-draft-id])]
    (is (not= "a" selected-id))
    (is (nil? (get-in next-state [:drafts "a"])))
    (is (= "A" (get-in next-state [:server :connections "a" :name])))))

(deftest back-from-untouched-new-draft-restores-previous-test
  (let [state (unsaved-local-state)
        new-state (support/apply-action
                   state
                   {:type :settings/new-connection
                    :path :local-service
                    :draft-id "draft-new"})
        back-state (support/apply-action new-state
                                         {:type :settings/back-draft})]
    (is (= "draft-old" (get-in back-state [:ui :selected-draft-id])))
    (is (nil? (get-in back-state [:drafts "draft-new"])))
    (is (= "Old local" (get-in back-state [:drafts "draft-old" :fields :name])))
    (is (= ["old-model"]
           (get-in back-state [:drafts "draft-old" :manual-models])))
    (is (= "half-typed" (model/manual-model-input back-state "draft-old")))
    (is (empty? (get-in back-state [:ui :draft-history])))
    (is (re-find #"撤销新建" (get-in back-state [:ui :message :text])))))

(deftest new-connection-preserves-dirty-saved-connection-draft-test
  (let [state (-> (fixture-state)
                  (support/apply-action {:type :settings/open})
                  (support/apply-action {:type :settings/select-connection
                                         :connection-id "a"})
                  (support/apply-action {:type :settings/edit-field
                                         :draft-id "a"
                                         :field :name
                                         :value "Dirty A"}))
        new-state (support/apply-action
                   state
                   {:type :settings/new-connection
                    :path :local-service
                    :draft-id "draft-new"})
        back-state (support/apply-action new-state
                                         {:type :settings/back-draft})]
    (is (= "Dirty A" (get-in new-state [:drafts "a" :fields :name])))
    (is (= "draft-new" (get-in new-state [:ui :selected-draft-id])))
    (is (= "a" (get-in back-state [:ui :selected-draft-id])))
    (is (= "Dirty A" (get-in back-state [:drafts "a" :fields :name])))
    (is (draft/dirty? (get-in back-state [:drafts "a"])))
    (is (nil? (get-in back-state [:drafts "draft-new"])))))

(deftest back-from-edited-new-draft-keeps-both-drafts-test
  (let [state (-> (unsaved-local-state)
                  (support/apply-action
                   {:type :settings/new-connection
                    :path :local-service
                    :draft-id "draft-new"})
                  (support/apply-action {:type :settings/edit-field
                                         :draft-id "draft-new"
                                         :field :name
                                         :value "Keep me"}))
        back-state (support/apply-action state {:type :settings/back-draft})]
    (is (= "draft-old" (get-in back-state [:ui :selected-draft-id])))
    (is (= "Keep me" (get-in back-state [:drafts "draft-new" :fields :name])))
    (is (= "Old local" (get-in back-state [:drafts "draft-old" :fields :name])))
    (is (re-find #"仍保留" (get-in back-state [:ui :message :text])))))

(deftest back-keeps-new-draft-with-only-typed-model-input-test
  (let [state (-> (unsaved-local-state)
                  (support/apply-action
                   {:type :settings/new-connection
                    :path :local-service
                    :draft-id "draft-new"})
                  (support/apply-action {:type :settings/edit-manual-model-input
                                         :draft-id "draft-new"
                                         :value "typed-only"})
                  (support/apply-action {:type :settings/back-draft}))]
    (is (= "draft-old" (get-in state [:ui :selected-draft-id])))
    (is (some? (get-in state [:drafts "draft-new"])))
    (is (= "typed-only" (model/manual-model-input state "draft-new")))))

(deftest back-keeps-new-draft-with-active-login-test
  (let [state (-> (unsaved-local-state)
                  (support/apply-action
                   {:type :settings/new-connection
                    :path :account-login
                    :draft-id "draft-auth"})
                  (assoc-in [:ui :model-auth :connection-id] "draft-auth")
                  (assoc-in [:ui :model-auth :status] :pending)
                  (support/apply-action {:type :settings/back-draft}))]
    (is (= "draft-old" (get-in state [:ui :selected-draft-id])))
    (is (some? (get-in state [:drafts "draft-auth"])))))

(deftest selecting-a-saved-connection-keeps-unsaved-drafts-reachable-test
  (let [state (unsaved-local-state)
        new-state (support/apply-action
                   state
                   {:type :settings/new-connection
                    :path :local-service
                    :draft-id "draft-new"})
        selected (support/apply-action
                  new-state
                  {:type :settings/select-connection
                   :connection-id "a"})
        cards (model/draft-cards selected)]
    (is (= [] (get-in selected [:ui :draft-history])))
    (is (some #(= "draft-old" (:draft-id %)) cards))
    (is (some #(= "draft-new" (:draft-id %)) cards))
    (is (= "draft-old" (:draft-id (first cards))))
    (is (= "a" (get-in selected [:ui :selected-draft-id])))))

(deftest select-draft-restores-unsaved-input-test
  (let [state (unsaved-local-state)
        new-state (support/apply-action
                   state
                   {:type :settings/new-connection
                    :path :local-service
                    :draft-id "draft-new"})
        selected (support/apply-action new-state
                                       {:type :settings/select-draft
                                        :draft-id "draft-old"})]
    (is (= "draft-old" (get-in selected [:ui :selected-draft-id])))
    (is (= "Old local" (get-in selected [:drafts "draft-old" :fields :name])))
    (is (= "half-typed" (model/manual-model-input selected "draft-old")))
    (is (empty? (get-in selected [:ui :draft-history])))
    (is (some? (get-in selected [:drafts "draft-new"])))))

(deftest delete-requires-confirmation-test
  (let [state (-> (fixture-state)
                  (support/apply-action {:type :settings/open})
                  (support/apply-action {:type :settings/select-connection
                                         :connection-id "a"}))
        requested (support/apply-action state
                                        {:type :settings/request-delete
                                         :draft-id "a"})]
    (is (= "a" (get-in requested [:ui :confirm-delete-draft-id])))
    (is (empty? (:outbox requested)))))

(deftest cancel-delete-confirmation-test
  (let [state (-> (fixture-state)
                  (support/apply-action {:type :settings/open})
                  (support/apply-action {:type :settings/select-connection
                                         :connection-id "a"}))
        requested (support/apply-action state
                                        {:type :settings/request-delete
                                         :draft-id "a"})
        cancelled (support/apply-action requested
                                        {:type :settings/cancel-delete})]
    (is (= "a" (get-in requested [:ui :confirm-delete-draft-id])))
    (is (nil? (get-in cancelled [:ui :confirm-delete-draft-id])))
    (is (some? (get-in cancelled [:drafts "a"])))))

(deftest card-test-creates-draft-without-selection-test
  (let [state (support/state {:connections {"a" (normalized "a" "A" "https://a.example.com/v1")}})
        tested (support/apply-action state
                                     {:type :settings/test-connection
                                      :connection-id "a"
                                      :command-id "test-card"})]
    (is (some? (get-in tested [:drafts "a"])))
    (is (= :settings/test-connection
           (get-in tested [:commands "test-card" :kind])))
    (is (= :pending (get-in tested [:tests "a" :status])))))

(deftest test-submission-discloses-cost-and-fixed-content-test
  (let [state (-> (support/state
                   {:connections {"a" (normalized "a" "A"
                                                  "https://a.example.com/v1")}})
                  (support/apply-action {:type :settings/open})
                  (support/apply-action {:type :settings/select-connection
                                         :connection-id "a"}))
        tested (support/apply-action state
                                     {:type :settings/test-connection
                                      :draft-id "a"
                                      :command-id "test-cost"})
        message (get-in tested [:ui :message :text])]
    (is (re-find #"可能产生少量费用" message))
    (is (re-find #"Herdchestra connection test\. Reply with OK\." message))
    (is (re-find #"不携带草稿" message))))

(deftest newer-role-draft-survives-receipt-test
  (let [state (-> (fixture-state)
                  (support/apply-action {:type :settings/select-model
                                         :role :main
                                         :connection-id "a"
                                         :model-id "m"})
                  (support/apply-action {:type :settings/save-role
                                         :role :main
                                         :command-id "role-1"})
                  (support/apply-action {:type :settings/select-model
                                         :role :main
                                         :connection-id "b"
                                         :model-id "m"}))
        command (get-in state [:commands "role-1"])
        receipt {:command-id "role-1"
                 :status :accepted}
        next-state (:state (model/apply-receipt state receipt))]
    (is (= 1 (get-in command [:payload :role-version])))
    (is (= 2 (get-in next-state [:role-drafts :main :version])))
    (is (= "b" (get-in next-state [:role-drafts :main :connection-id])))
    (is (= "a" (get-in next-state [:server :roles :main :connection-id])))))

(deftest onboarding-checklist-test
  (let [empty-state (support/state)
        connected (support/state {:connections {"a" (normalized "a" "A" "https://a.example.com/v1")}
                                  :models {"a" [(support/model "m" "M")]}})
        with-main (assoc-in connected [:server :roles :main]
                            {:role :main :mode :explicit
                             :connection-id "a" :model-id "m" :confirmed? true})
        tested (assoc-in with-main [:tests "a"] {:status :complete})
        disabled-model (assoc-in connected [:server :models "a" 0 :enabled?]
                                 false)]
    (is (= :connection-type (:checklist-step (model/onboarding-status empty-state))))
    (is (= :done (:checklist-step (model/onboarding-status connected)))
        "a saved connection with an enabled chat model completes setup")
    (is (= :done (:checklist-step (model/onboarding-status with-main))))
    (is (= :done (:checklist-step (model/onboarding-status tested))))
    (is (:complete? (model/onboarding-status tested)))
    (is (= :model (:checklist-step (model/onboarding-status disabled-model)))
        "a disabled model is not enough")
    (is (not (:complete? (model/onboarding-status disabled-model))))))

(deftest onboarding-actions-test
  (let [state (support/state)
        dismissed (support/apply-action state {:type :settings/dismiss-onboarding})
        restarted (support/apply-action dismissed {:type :settings/start-onboarding})
        finished (support/apply-action restarted {:type :settings/finish-onboarding})]
    (is (true? (get-in dismissed [:ui :onboarding :dismissed?])))
    (is (false? (get-in restarted [:ui :onboarding :dismissed?])))
    (is (true? (get-in finished [:ui :onboarding :completed?])))))

(deftest test-view-marks-old-digest-stale-test
  (let [state (-> (support/state
                   {:connections
                    {"a" (normalized "a" "A" "https://a.example.com/v1")}
                    :models {"a" [(support/model "m" "M")]}})
                  (support/apply-action {:type :settings/open})
                  (support/apply-action {:type :settings/select-connection
                                         :connection-id "a"}))
        digest (connection/draft-digest (get-in state [:drafts "a"]))
        state (assoc-in state [:tests "a"]
                        {:status :complete
                         :config-digest digest
                         :layers {:reachable {:status :ok}}})
        edited (support/apply-action state
                                     {:type :settings/edit-field
                                      :draft-id "a"
                                      :field :name
                                      :value "Changed"})
        test (model/test-view edited "a")]
    (is (:stale? test))
    (is (= :stale (:status test)))
    (is (re-find #"配置已修改" (:status-label test)))))

(deftest test-conclusion-names-verified-auth-scope-test
  (is (re-find #"身份可用"
               (model/test-conclusion
                {:reachable :ok
                 :auth-ok :ok
                 :models-listed :failed
                 :minimal-request-ok :not-run}))))

(deftest edit-model-field-action-updates-draft-test
  (let [state (-> (fixture-state)
                  (support/apply-action {:type :settings/open})
                  (support/apply-action {:type :settings/select-connection
                                         :connection-id "a"})
                  (support/apply-action
                   {:type :settings/edit-model-field
                    :draft-id "a"
                    :model-id "m"
                    :field :temperature
                    :value "0"}))
        draft (get-in state [:drafts "a"])]
    (is (zero? (get-in draft [:models 0 :temperature])))
    (is (draft/dirty? draft))))


(deftest capability-edit-reaches-the-save-payload
  (let [state (-> (support/state {:connections {}})
                  (support/apply-action
                   {:type :settings/new-connection
                    :path :local-service
                    :draft-id "d1"
                    :key-id "k1"
                    :auth-edit-id "e1"})
                  (support/apply-action
                   {:type :settings/edit-field
                    :draft-id "d1"
                    :field :name
                    :value "Local"})
                  (support/apply-action
                   {:type :settings/edit-field
                    :draft-id "d1"
                    :field :base-url
                    :value "http://127.0.0.1:18099/v1"})
                  (support/apply-action
                   {:type :settings/set-location
                    :draft-id "d1"
                    :location :instance}))
        _ (is (some? state))
        with-model (support/apply-action
                    state
                    {:type :settings/add-manual-model
                     :draft-id "d1"
                     :model-id "manual-only"})
        with-tools (support/apply-action
                    with-model
                    {:type :settings/set-model-capability
                     :draft-id "d1"
                     :model-id "manual-only"
                     :capability :tools
                     :value :known})
        saved (support/apply-action
               with-tools
               {:type :settings/save-connection
                :draft-id "d1"})
        command (some #(when (= :settings/save-connection (:kind %)) %)
                      (vals (:commands saved)))
        models (get-in command [:payload :fields :models])]
    (is (some? command) "save-connection was not queued")
    (is (= {:tools true}
           (get-in (first (filter #(= "manual-only" (:id %)) models))
                   [:capabilities]))
        "the operator's tool declaration must reach the save payload")))
