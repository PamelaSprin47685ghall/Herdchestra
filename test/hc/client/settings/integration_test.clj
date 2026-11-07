(ns hc.client.settings.integration-test
  "Contract tests for the real workbench/server settings lifecycle."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hc.client.settings.commands :as commands]
            [hc.client.settings.connection :as connection]
            [hc.client.settings.draft :as draft]
            [hc.client.settings.model :as model]
            [hc.client.settings.support :as support]))

(def server-connection
  {:id "a"
   :name "OpenAI"
   :kind :preset
   :base-url "https://api.example.com/v1"
   :models [{:id "m1" :name "M1" :capabilities {:vision :known}}]
   :has-key? true
   :auth {:location :instance :account-label "team@example.com"}})

(defn- state
  []
  (-> (support/state {:connections {"a" server-connection}
                      :models {"a" (:models server-connection)}
                      :roles {:main {:connection-id "a" :model-id "m1"
                                     :mode :explicit :confirmed? true}}})
      (support/apply-action {:type :settings/open})
      (support/apply-action {:type :settings/select-connection
                             :connection-id "a"})))

(deftest server-connection-payload-test
  (let [payload (connection/draft-payload
                 (get-in (state) [:drafts "a"]))
        fields (:fields payload)
        serialized (pr-str payload)]
    (testing "server connection shape"
      (is (= "a" (:id fields)))
      (is (= "OpenAI" (:name fields)))
      (is (= "https://api.example.com/v1" (:base-url fields)))
      (is (= :openai-compatible (:kind fields)))
      (is (= :cloud-key (:service-kind fields)))
      (is (true? (:enabled? fields)))
      (is (= "team@example.com" (get-in fields [:auth :account-label])))
      (is (= :instance (get-in fields [:auth :location])))
      (is (not (contains? fields :location)))
      (is (not (contains? fields :account-label))))
    (testing "models and keys survive without a plaintext secret"
      (is (= ["m1"] (mapv :id (:models fields))))
      (is (true? (:enabled? (first (:models fields)))))
      (is (= "connection-default" (:key-id (first (:keys fields)))))
      (is (not (contains? (first (:keys fields)) :secret)))
      (is (not (str/includes? serialized "secret")))
      (is (not (contains? fields :api-key))))))

(deftest disabled-connection-payload-test
  (let [state (-> (state)
                  (support/apply-action
                   {:type :settings/edit-field
                    :draft-id "a"
                    :field :enabled?
                    :value false}))
        draft (get-in state [:drafts "a"])
        fields (:fields (connection/draft-payload draft))]
    (is (false? (get-in draft [:fields :enabled?])))
    (is (true? (:dirty? draft)))
    (is (false? (:enabled? fields)))
    (testing "disabling does not drop the saved models"
      (is (= ["m1"] (mapv :id (:models fields)))))))

(deftest disabled-connection-survives-unrelated-edit-test
  (let [state (-> (state)
                  (support/apply-action
                   {:type :settings/edit-field
                    :draft-id "a"
                    :field :enabled?
                    :value false})
                  (support/apply-action
                   {:type :settings/edit-field
                    :draft-id "a"
                    :field :name
                    :value "Renamed while disabled"}))
        draft (get-in state [:drafts "a"])
        fields (:fields (connection/draft-payload draft))]
    (is (false? (get-in draft [:fields :enabled?]))
        "a later edit must not re-enable the connection")
    (is (false? (:enabled? fields)))
    (is (= "Renamed while disabled" (:name fields)))
    (testing "the disabled connection still carries its models"
      (is (= ["m1"] (mapv :id (:models fields)))))))

(deftest server-role-payload-test
  (let [state (state)
        state (support/apply-action state
                                    {:type :settings/select-model
                                     :role :main
                                     :connection-id "a"
                                     :model-id "m1"})
        state (support/apply-action state
                                    {:type :settings/save-role
                                     :role :main
                                     :command-id "role-1"})
        payload (get-in state [:commands "role-1" :payload])]
    (is (= {:connection "a" :model "m1" :mode :explicit :confirmed? true}
           (get-in payload [:roles :main])))
    (is (contains? (:roles payload) :utility))
    (is (not (contains? (:roles payload) :task))
        "an unset task role must be omitted, not sent as a mode-only binding")
    (is (= "a" (get-in payload [:roles :main :connection])))))

(deftest outer-test-receipt-applies-layers-test
  (let [state (-> (state)
                  (support/apply-action {:type :settings/test-connection
                                         :draft-id "a"
                                         :command-id "test-1"}))
        digest (get-in state [:commands "test-1" :config-digest])
        outer {:ok? true
               :command-id "test-1"
               :receipt {:status :complete
                         :config-digest digest
                         :layers {:reachable {:status :ok}
                                  :auth-ok {:status :ok}
                                  :models-listed {:status :ok}
                                  :minimal-request-ok {:status :ok}}}}
        next-state (:state (model/apply-receipt state outer))]
    (is (= :complete (get-in next-state [:tests "a" :status])))
    (is (re-find #"最小请求"
                 (get-in next-state [:tests "a" :conclusion])))
    (is (= :complete (get-in next-state [:commands "test-1" :status])))
    (is (some? (get-in next-state [:drafts "a"])))
    (is (not (draft/dirty? (get-in next-state [:drafts "a"]))))))

(deftest outer-save-receipt-clears-and-updates-test
  (let [state (-> (state)
                  (support/apply-action {:type :settings/edit-field
                                         :draft-id "a"
                                         :field :name
                                         :value "Renamed"})
                  (support/apply-action {:type :settings/save-connection
                                         :draft-id "a"
                                         :command-id "save-1"}))
        outer {:ok? true
               :command-id "save-1"
               :receipt {:status :accepted
                         :config-revision 2
                         :saved? true
                         :applied? false
                         :restart-required true}}
        next-state (:state (model/apply-receipt state outer))]
    (is (nil? (get-in next-state [:drafts "a"])))
    (is (= "Renamed" (get-in next-state [:server :connections "a" :name])))
    (is (= ["m1"] (mapv :id (get-in next-state [:server :connections "a" :models]))))
    (is (re-find #"重启" (get-in next-state [:ui :message :text])))
    (is (= :accepted (get-in next-state [:commands "save-1" :status])))))

(deftest outer-list-models-receipt-test
  (let [state (-> (state)
                  (support/apply-action {:type :settings/refresh-models
                                         :connection-id "a"
                                         :command-id "refresh-1"}))
        outer {:ok? true
               :command-id "refresh-1"
               :receipt {:status :accepted
                         :connections {"a" [{:id "m2" :name "M2"}]}
                         :model-list-status :configured-list-only}}
        next-state (:state (model/apply-receipt state outer))
        models (get-in next-state [:server :models "a"])]
    (is (= :configured-list-only
           (get-in next-state [:server :model-list-status "a"])))
    (is (= #{"m1" "m2"} (set (map :model-id models))))
    (is (= :not-returned
           (:availability (first (filter #(= "m1" (:model-id %)) models)))))))

(deftest session-model-selection-command-test
  (let [base (:state (model/apply-snapshot
                      (state)
                      {:session/by-id {"s1" {:command-epoch 7}}
                       :session/active-id "s1"
                       :sync {:phase :synced :command-epoch 7}}))
        state (support/apply-action base {:type :settings/select-session-model
                                         :session-id "s1"
                                         :connection-id "a"
                                         :model-id "m1"
                                         :mode :next-round
                                         :command-id "model-1"})
        envelope (first (:outbox state))
        payload (:payload envelope)]
    (is (= :session/select-model (:kind payload)))
    (is (= "s1" (:session-id payload)))
    (is (= "a" (:connection-id payload)))
    (is (= "m1" (:model-entry-id payload)))
    (is (= :next-round (:mode payload)))
    (is (= :queued (get-in state [:commands "model-1" :status])))))

(deftest server-snapshot-normalization-test
  (let [state (support/state)
        next-state (:state (model/apply-snapshot
                            state
                            {:settings/by-id {"a" server-connection}
                             :settings/models {"a" [{:id "m1" :name "M1"}]}
                             :session/active-id "s1"
                             :sync {:phase :synced :command-epoch 8}}))
        conn (get-in next-state [:server :connections "a"])]
    (is (= :cloud-key (:service-kind conn)))
    (is (= :openai-compatible (:protocol conn)))
    (is (= :instance (:location conn)))
    (is (= "team@example.com" (:account-label conn)))
    (is (= #{"m1"} (:enabled-models conn)))
    (is (= "s1" (get-in next-state [:ui :current-session-id])))
    (is (= 8 (get-in next-state [:sync :command-epoch])))))

(deftest local-selected-session-drives-settings-target
  (let [base (:state (model/apply-snapshot
                      (state)
                      {:session/selected-id "s2"
                       :session/active-id "s1"
                       :session/by-id {"s1" {:command-epoch 7}
                                       "s2" {:command-epoch 9}}
                       :sync {:phase :synced :command-epoch 7}}))
        state (support/apply-action
               base
               {:type :settings/select-session-model
                :session-id (get-in base [:ui :current-session-id])
                :connection-id "a"
                :model-id "m1"
                :command-id "model-local"})
        envelope (first (:outbox state))]
    (is (= "s2" (get-in base [:ui :current-session-id])))
    (is (= "s1" (get-in base [:ui :active-session-id])))
    (is (= "s2" (:session-id (:payload envelope)))
        "the settings command follows the client selection, not the server pointer")
    (is (= 9 (:command-epoch envelope)))))

(deftest feed-epoch-does-not-authorize-test
  (let [state (assoc-in (support/state) [:sync :epoch] 9)
        state (assoc-in state [:sync :command-epoch] nil)]
    (is (false? (commands/command-allowed? state)))
    (is (false? (:ok? (commands/queue state :settings/save-connection
                                      {:draft-id "a" :draft-version 1}))))))

(deftest per-session-model-epoch-test
  (let [base (support/state)
        state (:state (model/apply-snapshot
                       base
                       {:session/by-id {"s1" {:command-epoch 3}
                                        "s2" {:command-epoch 5}}
                        :session/active-id "s1"
                        :sync {:phase :synced :command-epoch 3}}))
        other (support/apply-action state
                                    {:type :settings/select-session-model
                                     :session-id "s2"
                                     :connection-id "a"
                                     :model-id "m1"
                                     :mode :next-round
                                     :command-id "model-s2"})
        active (support/apply-action state
                                     {:type :settings/select-session-model
                                      :session-id "s1"
                                      :connection-id "a"
                                      :model-id "m1"
                                      :mode :next-round
                                      :command-id "model-s1"})
        missing (support/apply-action state
                                      {:type :settings/select-session-model
                                       :session-id "s3"
                                       :connection-id "a"
                                       :model-id "m1"
                                       :mode :next-round
                                       :command-id "model-s3"})]
    (is (= 5 (:command-epoch (first (:outbox other)))))
    (is (= 3 (:command-epoch (first (:outbox active)))))
    (is (empty? (:outbox missing)))
    (is (re-find #"纪元" (get-in missing [:ui :message :text])))))

(defn- account-draft-state
  []
  (let [state (-> (support/state)
                  (support/apply-action {:type :settings/new-connection
                                         :path :account-login
                                         :draft-id "draft-auth"
                                         :key-id "auth-key"
                                         :auth-edit-id "auth-edit"}))
        draft-id (get-in state [:ui :selected-draft-id])]
    (-> state
        (support/apply-action {:type :settings/edit-field
                               :draft-id draft-id
                               :field :name
                               :value "Account Service"})
        (support/apply-action {:type :settings/edit-field
                               :draft-id draft-id
                               :field :base-url
                               :value "https://auth.example.com/v1"})
        (support/apply-action {:type :model-auth/providers
                               :providers [{:id "demo" :name "Demo"
                                            :configured? true
                                            :flows [:device-code]}]})
        (support/apply-action {:type :model-auth/begin
                               :provider-id "demo"
                               :connection-id draft-id
                               :flow-type :device-code
                               :command-id "auth-begin"}))))

(deftest model-auth-begin-and-authorize-test
  (let [state (account-draft-state)
        envelope (first (:outbox state))
        command-id (:command-id envelope)]
    (is (= :model-auth/begin (get-in envelope [:payload :kind])))
    (is (= "demo" (get-in envelope [:payload :provider-id])))
    (let [outer {:ok? true
                 :command-id command-id
                 :receipt {:status :authorized
                           :flow-id "flow-1"
                           :flow-type :device-code
                           :account {:provider-id "demo"
                                     :issuer "https://issuer.example.com"
                                     :subject "subject-1"
                                     :account-label "person@example.com"
                                     :connection-id "account-1"}
                           :authorize-url "https://auth.example.com/authorize"}}
          result (model/apply-receipt state outer)
          next-state (:state result)
          draft (get-in next-state [:drafts (get-in state [:ui :selected-draft-id])])]
      (is (= :authorized (get-in next-state [:ui :model-auth :status])))
      (is (= :account-login (get-in draft [:fields :service-kind])))
      (is (= "person@example.com" (get-in draft [:fields :account-label])))
      (is (= "account-1" (get-in draft [:fields :auth-ref])))
      (is (= "demo" (get-in draft [:fields :auth :oauth :provider-id])))
      (is (not (re-find #"access-token|refresh-token" (pr-str draft))))
      (is (every? nil? (map :secret (:keys draft))))
      (is (some #(= :open-url (:effect %)) (:effects result))))))

(deftest model-auth-state-is-scoped-to-the-selected-draft-test
  (let [state (account-draft-state)
        draft-a (get-in state [:ui :selected-draft-id])
        command-id (:command-id (first (:outbox state)))
        authorized {:ok? true
                    :command-id command-id
                    :receipt {:status :authorized
                              :flow-id "flow-a"
                              :flow-type :device-code
                              :account {:provider-id "demo"
                                        :issuer "https://issuer.example.com"
                                        :subject "subject-a"
                                        :account-label "person@example.com"
                                        :connection-id draft-a}}}
        authorized-state (:state (model/apply-receipt state authorized))
        state (-> (support/apply-action
                   authorized-state
                   {:type :settings/new-connection
                    :path :account-login
                    :draft-id "draft-b"})
                  (assoc-in [:ui :model-auth :accounts]
                            [{:connection-id draft-a
                              :account-label "person@example.com"}]))
        state (:state (model/apply-snapshot
                       state
                       {:model-auth/accounts
                        [{:connection-id draft-a
                          :account-label "person@example.com"}]}))
        auth (get-in state [:ui :model-auth])]
    (is (= :authorized (:status (get-in authorized-state [:ui :model-auth]))))
    (is (= "draft-b" (:connection-id auth)))
    (is (= :idle (:status auth)))
    (is (nil? (:account auth)))))

(deftest saved-account-hydrates-an-idle-oauth-draft-test
  (let [state (-> (support/state)
                  (support/apply-action
                   {:type :settings/new-connection
                    :path :account-login
                    :draft-id "draft-auth"}))
        state (:state (model/apply-snapshot
                       state
                       {:model-auth/accounts
                        [{:connection-id "draft-auth"
                          :account-label "person@example.com"
                          :provider-id "demo"}]}))
        auth (get-in state [:ui :model-auth])]
    (is (= :connected (:status auth)))
    (is (= "person@example.com" (get-in auth [:account :account-label])))))

(deftest late-oauth-receipt-does-not-patch-another-draft-test
  (let [state (account-draft-state)
        draft-a (get-in state [:ui :selected-draft-id])
        command-id (:command-id (first (:outbox state)))
        state (support/apply-action
               state
               {:type :settings/new-connection
                :path :account-login
                :draft-id "draft-b"})
        outer {:ok? true
               :command-id command-id
               :receipt {:status :authorized
                         :flow-id "flow-a"
                         :connection-id draft-a
                         :account {:provider-id "demo"
                                   :issuer "https://issuer.example.com"
                                   :subject "subject-a"
                                   :account-label "person@example.com"
                                   :connection-id draft-a}}}
        next-state (:state (model/apply-receipt state outer))
        draft-b (get-in next-state [:drafts "draft-b"])]
    (is (= :idle (get-in next-state [:ui :model-auth :status])))
    (is (= "draft-b" (get-in next-state [:ui :model-auth :connection-id])))
    (is (str/blank? (str (get-in draft-b [:fields :auth-ref]))))
    (is (empty? (get-in draft-b [:fields :auth])))))

(deftest role-save-reports-restart-requirement-test
  (let [state (-> (state)
                  (support/apply-action {:type :settings/select-model
                                         :role :main
                                         :connection-id "a"
                                         :model-id "m1"})
                  (support/apply-action {:type :settings/save-role
                                         :role :main
                                         :command-id "role-restart"}))
        outer {:ok? true
               :command-id "role-restart"
               :receipt {:status :accepted
                         :restart-required true}}
        next-state (:state (model/apply-receipt state outer))]
    (is (re-find #"重启" (get-in next-state [:ui :message :text])))
    (is (= "a" (get-in next-state [:server :roles :main :connection-id])))
    (is (nil? (get-in next-state [:role-drafts :main])))))

(deftest branch-command-epoch-map-authorizes-session-switch
  (let [base (:state (model/apply-snapshot
                      (state)
                      {:session/command-epochs {"branch" 11}
                       :session/selected-id "branch"
                       :sync {:phase :synced :command-epoch 7}}))
        state (support/apply-action
               base
               {:type :settings/select-session-model
                :session-id "branch"
                :connection-id "a"
                :model-id "m1"
                :mode :next-round
                :command-id "model-branch"})
        envelope (first (:outbox state))]
    (is (= 11 (:command-epoch envelope))
        "a branch epoch published outside :session/by-id is usable")
    (is (= "branch" (:session-id (:payload envelope))))
    (is (= :queued (get-in state [:commands "model-branch" :status])))))
