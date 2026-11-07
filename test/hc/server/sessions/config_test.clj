(ns hc.server.sessions.config-test
  (:require [clojure.test :refer [deftest is testing]]
            [hc.server.sessions :as sessions]
            [hc.server.sessions.config :as config]
            [hc.server.sessions.connections :as connections]
            [hc.server.sessions.ids :as ids]
            [hc.server.sessions.test-support :as support]))

(deftest adding-a-connection-is-unique-and-ordered
  (let [context (support/build-context)
        connection (get-in context [:config :connections "conn-1"])]
    (is (= ["conn-1"] (get-in context [:config :connection-order])))
    (let [duplicate (config/add-connection (:config context) connection 2000)]
      (is (not (:ok? duplicate)))
      (is (= :CONNECTION_ID_TAKEN (get-in duplicate [:error :code]))))))

(deftest role-bindings-use-stable-model-ids
  (let [context (support/build-context)
        main (config/set-role-binding (:config context) :main
                                      (:connection-id context)
                                      (:model-entry-id context)
                                      2000)
        task (config/set-role-binding (:config main) :task
                                      (:connection-id context)
                                      (:model-entry-id context)
                                      2000)]
    (is (:ok? main))
    (is (:ok? task))
    (is (= (:model-entry-id context)
           (get-in task [:config :role-bindings :main :model-entry-id])))
    (is (= (:model-entry-id context)
           (get-in task [:config :role-bindings :task :model-entry-id])))
    (let [invalid (config/set-role-binding (:config task) :other
                                           (:connection-id context)
                                           (:model-entry-id context)
                                           2000)]
      (is (not (:ok? invalid)))
      (is (= :ROLE_INVALID (get-in invalid [:error :code]))))))

(deftest main-role-is-the-new-session-default
  (let [context (support/build-context)
        config (assoc-in (:config context) [:role-bindings :main]
                         {:connection-id (:connection-id context)
                          :model-entry-id (:model-entry-id context)})
        resolved (config/resolve-role-binding config :main)
        created (sessions/new-session
                 {:config config
                  :id-source (ids/counter-source)
                  :now 2000})]
    (is (:ok? resolved))
    (is (= (:model-entry-id context)
           (get-in resolved [:binding :model-entry-id])))
    (is (:ok? created))
    (is (= (:model-entry-id context)
           (get-in created [:session :model-binding :model-entry-id])))))

(deftest same-name-models-are-not-merged
  (let [source (ids/counter-source)
        base (config/new-config)
        conn-a (get-in (connections/new-connection
                        {:connection-id "conn-a"
                         :name "Personal"
                         :kind :preset
                         :default-base-url "https://a.example"
                         :now 1000})
                       [:connection])
        conn-b (get-in (connections/new-connection
                        {:connection-id "conn-b"
                         :name "Work"
                         :kind :preset
                         :default-base-url "https://b.example"
                         :now 1000})
                       [:connection])
        config-1 (:config (config/add-connection base conn-a 1000))
        config-2 (:config (config/add-connection config-1 conn-b 1000))
        refreshed-a (connections/refresh-models
                     (get-in config-2 [:connections "conn-a"])
                     [{:provider-model-id "same-model"
                       :display-name "Shared Name"
                       :capabilities {:text true}}]
                     source 2000)
        refreshed-b (connections/refresh-models
                     (get-in config-2 [:connections "conn-b"])
                     [{:provider-model-id "same-model"
                       :display-name "Shared Name"
                       :capabilities {:vision true}}]
                     source 2000)
        config-3 (-> config-2
                     (assoc-in [:connections "conn-a"] (:connection refreshed-a))
                     (assoc-in [:connections "conn-b"] (:connection refreshed-b)))
        grouped (config/models-by-display-name config-3)]
    (is (= 2 (count (get grouped "Shared Name"))))
    (is (= #{"conn-a" "conn-b"}
           (set (map :connection-id (get grouped "Shared Name")))))
    (is (= true (get-in (first (get grouped "Shared Name"))
                        [:capabilities :text])))
    (is (= true (get-in (second (get grouped "Shared Name"))
                        [:capabilities :vision])))))

(deftest binding-status-separates-availability-from-capability
  (let [context (support/build-context)
        binding (get-in context [:config :assistants (:assistant-id context)
                                 :default-model])
        resolved (config/resolve-binding (:config context)
                                         (:connection-id binding)
                                         (:model-entry-id binding))
        disabled (config/set-connection-enabled (:config context)
                                                (:connection-id context)
                                                false 2000)
        disabled-binding (get-in disabled [:config :assistants
                                           (:assistant-id context)
                                           :default-model])]
    (is (:ok? resolved))
    (is (= :available (:availability resolved)))
    (is (config/binding-usable? (:config context) (:binding resolved)))
    (is (= :connection-disabled
           (config/binding-status (:config disabled) disabled-binding)))
    (is (not (config/binding-usable? (:config disabled)
                                     disabled-binding)))))

(deftest an-expired-connection-auth-makes-the-binding-unusable
  (let [context (support/build-context)
        binding (get-in context [:config :assistants (:assistant-id context)
                                 :default-model])
        failed (:config (config/set-connection-auth-status
                         (:config context)
                         (:connection-id context)
                         :failed
                         2000))]
    (is (= :auth-expired (config/binding-status failed binding)))
    (is (not (config/binding-usable? failed binding)))
    (is (= :failed (get-in failed [:connections (:connection-id context)
                                   :auth-status])))))

(deftest changing-an-assistant-default-does-not-touch-session-snapshots
  (let [context (support/build-context)
        source (ids/counter-source)
        session (:session
                 (sessions/new-session
                  {:assistant-id (:assistant-id context)
                   :config (:config context)
                   :id-source source
                   :now 2000
                   :client-id "w1"}))
        old-binding (:model-binding session)
        changed (config/set-assistant-default-model
                 (:config context)
                 (:assistant-id context)
                 (:connection-id context)
                 (:model-entry-id context)
                 3000)]
    (is (:ok? changed))
    (is (= old-binding (:model-binding session)))
    (is (= (:model-entry-id old-binding)
           (get-in changed [:config :assistants (:assistant-id context)
                            :default-model :model-entry-id])))))

(deftest configuration-invariants-hold-after-operations
  (let [context (support/build-context)
        with-role (:config (config/set-role-binding
                            (:config context) :main
                            (:connection-id context)
                            (:model-entry-id context)
                            2000))
        with-credential (:config (config/record-auth-success
                                  with-role
                                  (:connection-id context)
                                  "cred-1"
                                  3000))
        with-model (:config (config/set-model-alias
                             with-credential
                             (:connection-id context)
                             (:model-entry-id context)
                             "local-name"
                             4000))]
    (is (empty? (config/check-invariants with-model)))))

(deftest configuration-invariants-report-an-unresolved-role
  (let [context (support/build-context)
        config (assoc-in (:config context) [:role-bindings :main]
                         {:connection-id "missing"
                          :model-entry-id "missing"})]
    (is (some #(= :role-binding-unresolved (:invariant %))
              (config/check-invariants config)))))
