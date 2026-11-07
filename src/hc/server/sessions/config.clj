(ns hc.server.sessions.config
  "Global session configuration: provider connections, assistants, and model
  role bindings.

  Authority: AGENTS.md §2.1 and §5.4 (main model vs task model), §7.4 旅程 1.2–1.3（引导与选模型）
  (assistant defaults and same-name models), and §5.1 (existing sessions keep
  their creation-time model).

  The configuration is one EDN value with its own revision.  It contains no
  live client and no raw secret.  A session copies a model binding snapshot
  at creation; later configuration changes therefore affect new sessions
  only, unless the user explicitly selects a model for an existing session."
  (:require [hc.server.sessions.connections :as connections]
            [hc.server.sessions.ids :as ids]))

(def roles
  #{:main :task})

(defn new-config
  []
  {:revision 0
   :connections {}
   :connection-order []
   :assistants {}
   :role-bindings {}
   :created-at nil
   :updated-at nil})

(defn- error
  [config code message details]
  {:ok? false
   :config config
   :error (cond-> {:code code :message message}
            (some? details) (assoc :details details))})

(defn- bump
  [config now]
  (-> config
      (update :revision inc)
      (assoc :updated-at now)))

(defn- with-connection
  [config connection-id f now]
  (if-let [connection (get-in config [:connections connection-id])]
    (let [result (f connection)]
      (if (:ok? result)
        {:ok? true
         :config (-> config
                     (assoc-in [:connections connection-id] (:connection result))
                     (bump now))
         :result (dissoc result :connection)}
        (error config (:code (:error result))
               (:message (:error result))
               (:details (:error result)))))
    (error config :CONNECTION_NOT_FOUND
           "connection does not exist"
           {:connection-id connection-id})))

;; ---------------------------------------------------------------------------
;; Connections

(defn add-connection
  [config connection now]
  (cond
    (contains? (:connections config) (:connection-id connection))
    (error config :CONNECTION_ID_TAKEN
           "connection-id already exists"
           {:connection-id (:connection-id connection)})

    (seq (connections/check-connection-invariants connection))
    (error config :CONNECTION_INVALID
           "connection value is not valid"
           {:violations (connections/check-connection-invariants connection)})

    :else
    {:ok? true
     :config (-> config
                 (assoc-in [:connections (:connection-id connection)] connection)
                 (update :connection-order (fnil conj []) (:connection-id connection))
                 (bump now))}))

(defn set-custom-base-url
  [config connection-id value now]
  (with-connection config connection-id
                   #(connections/set-custom-base-url % value now)
                   now))

(defn set-connection-enabled
  [config connection-id enabled? now]
  (with-connection config connection-id
                   #(connections/set-connection-enabled % enabled? now)
                   now))

(defn mark-connection-removed
  "Mark a connection removed without deleting its history.

  A removed connection is unavailable for new bindings but its record and
  model entries remain readable for old sessions."
  [config connection-id now]
  (with-connection config connection-id
                   (fn [connection]
                     {:ok? true
                      :connection (assoc connection
                                         :removed? true
                                         :enabled? false
                                         :updated-at now)})
                   now))

(defn set-connection-auth-status
  [config connection-id status now]
  (with-connection config connection-id
                   #(connections/set-auth-status % status now)
                   now))

(defn request-connection-test
  [config connection-id now]
  (with-connection config connection-id
                   #(connections/request-connection-test % now)
                   now))

(defn record-connection-test-result
  [config connection-id result now]
  (with-connection config connection-id
                   #(connections/record-test-result % result now)
                   now))

;; ---------------------------------------------------------------------------
;; Credentials

(defn add-credential
  [config connection-id credential now]
  (with-connection config connection-id
                   #(connections/add-credential % credential now)
                   now))

(defn update-credential
  [config connection-id credential-id changes now]
  (with-connection config connection-id
                   #(connections/update-credential % credential-id changes now)
                   now))

(defn set-credential-enabled
  [config connection-id credential-id enabled? now]
  (with-connection config connection-id
                   #(connections/set-credential-enabled
                     % credential-id enabled? now)
                   now))

(defn record-auth-failure
  [config connection-id credential-id failure now]
  (with-connection config connection-id
                   #(connections/record-auth-failure
                     % credential-id failure now)
                   now))

(defn record-auth-success
  [config connection-id credential-id now]
  (with-connection config connection-id
                   #(connections/record-auth-success % credential-id now)
                   now))

(defn record-rate-limit
  [config connection-id credential-id retry-at now]
  (with-connection config connection-id
                   #(connections/record-rate-limit
                     % credential-id retry-at now)
                   now))

(defn choose-credential
  ([config connection-id now]
   (choose-credential config connection-id now {}))
  ([config connection-id now options]
   (if-let [connection (get-in config [:connections connection-id])]
     (let [result (connections/choose-credential connection now options)]
       (if (:ok? result)
         {:ok? true
          :config (-> config
                      (assoc-in [:connections connection-id] (:connection result))
                      (bump now))
          :credential (:credential result)
          :public (:public result)}
         (error config (:code (:error result))
                (:message (:error result))
                (:details (:error result)))))
     (error config :CONNECTION_NOT_FOUND
            "connection does not exist"
            {:connection-id connection-id}))))

;; ---------------------------------------------------------------------------
;; Models

(defn add-manual-model
  [config connection-id model id-source now]
  (with-connection config connection-id
                   #(connections/add-manual-model % model id-source now)
                   now))

(defn refresh-models
  [config connection-id observed id-source now]
  (with-connection config connection-id
                   #(connections/refresh-models % observed id-source now)
                   now))

(defn mark-model-list-unavailable
  [config connection-id reason now]
  (with-connection config connection-id
                   #(connections/mark-model-list-unavailable % reason now)
                   now))

(defn set-model-enabled
  [config connection-id model-entry-id enabled? now]
  (with-connection config connection-id
                   #(connections/set-model-enabled
                     % model-entry-id enabled? now)
                   now))

(defn set-model-alias
  [config connection-id model-entry-id alias now]
  (with-connection config connection-id
                   #(connections/set-model-alias % model-entry-id alias now)
                   now))

(defn set-model-notes
  [config connection-id model-entry-id notes now]
  (with-connection config connection-id
                   #(connections/set-model-notes % model-entry-id notes now)
                   now))

(defn record-model-result
  [config connection-id model-entry-id outcome now]
  (with-connection config connection-id
                   #(connections/record-model-result
                     % model-entry-id outcome now)
                   now))

;; ---------------------------------------------------------------------------
;; Binding resolution

(defn resolve-binding
  "Resolve a configuration reference to an immutable binding snapshot.

  The binding is returned even when the connection is currently disabled or
  the model list did not return the entry; `:availability` reports that
  separate fact.  A missing connection or model entry is an error, never a
  silent fallback by display name."
  [config connection-id model-entry-id]
  (cond
    (nil? config)
    (error nil :CONFIG_REQUIRED "configuration is required" {})

    (nil? (get-in config [:connections connection-id]))
    (error config :CONNECTION_NOT_FOUND
           "connection does not exist"
           {:connection-id connection-id})

    (nil? (get-in config [:connections connection-id :models model-entry-id]))
    (error config :MODEL_ENTRY_NOT_FOUND
           "model entry does not exist on this connection"
           {:connection-id connection-id
            :model-entry-id model-entry-id})

    :else
    (let [connection (get-in config [:connections connection-id])
          model (get-in config [:connections connection-id
                                :models model-entry-id])]
      {:ok? true
       :config config
       :binding (connections/model-binding-snapshot connection model)
       :availability (connections/model-availability connection model)})))

(defn binding-status
  "Return the current availability of a stored binding snapshot."
  [config binding]
  (if (nil? binding)
    :none
    (let [result (resolve-binding config
                                  (:connection-id binding)
                                  (:model-entry-id binding))]
      (if (:ok? result)
        (:availability result)
        :missing))))

(defn binding-usable?
  "True when a request may be sent with this binding.

  A listed model that the provider did not return this time remains usable:
  the listing endpoint may be flaky.  A disabled connection, expired auth, a
  disabled model, or a model the provider explicitly removed is not usable."
  [config binding]
  (contains? #{:available :not-returned :unconfirmed}
             (binding-status config binding)))

(defn add-assistant
  [config {:keys [assistant-id name default-model] :as assistant} now]
  (cond
    (not (ids/valid-id? assistant-id))
    (error config :ASSISTANT_ID_INVALID
           "assistant-id must be a non-blank string"
           {:assistant-id assistant-id})

    (contains? (:assistants config) assistant-id)
    (error config :ASSISTANT_ID_TAKEN
           "assistant-id already exists"
           {:assistant-id assistant-id})

    (and default-model
         (not (and (ids/valid-id? (:connection-id default-model))
                   (ids/valid-id? (:model-entry-id default-model)))))
    (error config :ASSISTANT_DEFAULT_MODEL_INVALID
           "default model needs connection-id and model-entry-id"
           {:default-model default-model})

    (and default-model
         (not (:ok? (resolve-binding config
                                     (:connection-id default-model)
                                     (:model-entry-id default-model)))))
    (error config :ASSISTANT_DEFAULT_MODEL_INVALID
           "default model does not resolve"
           {:default-model default-model})

    :else
    {:ok? true
     :config (-> config
                 (assoc-in [:assistants assistant-id]
                           {:assistant-id assistant-id
                            :name (or name assistant-id)
                            :default-model (when default-model
                                             (select-keys default-model
                                                          [:connection-id
                                                           :model-entry-id]))
                            :created-at now
                            :updated-at now})
                 (bump now))}))

(defn set-assistant-default-model
  "Set an assistant's default model reference.

  Existing sessions keep their own binding snapshot; only sessions created
  after this change read the new default."
  [config assistant-id connection-id model-entry-id now]
  (let [resolved (resolve-binding config connection-id model-entry-id)]
    (cond
      (nil? (get-in config [:assistants assistant-id]))
      (error config :ASSISTANT_NOT_FOUND
             "assistant does not exist"
             {:assistant-id assistant-id})

      (not (:ok? resolved))
      (error config (:code (:error resolved))
             (:message (:error resolved))
             (:details (:error resolved)))

      :else
      {:ok? true
       :config (-> config
                   (assoc-in [:assistants assistant-id :default-model]
                             {:connection-id connection-id
                              :model-entry-id model-entry-id})
                   (assoc-in [:assistants assistant-id :updated-at] now)
                   (bump now))})))

(defn default-assistant-id
  "The default assistant id for a new session, or nil when none exists."
  [config]
  (or (some-> (get-in config [:assistants "default"]) :id)
      (some-> (first (sort (keys (:assistants config)))))))

(defn assistant-binding
  "Resolve an assistant's current default binding for a new session."
  [config assistant-id]
  (cond
    (nil? config)
    (error nil :CONFIG_REQUIRED "configuration is required" {})

    (nil? (get-in config [:assistants assistant-id]))
    (error config :ASSISTANT_NOT_FOUND
           "assistant does not exist"
           {:assistant-id assistant-id})

    (nil? (get-in config [:assistants assistant-id :default-model]))
    {:ok? true :binding nil :availability nil}

    :else
    (resolve-binding config
                     (get-in config [:assistants assistant-id
                                     :default-model :connection-id])
                     (get-in config [:assistants assistant-id
                                     :default-model :model-entry-id]))))

(declare role-binding)

(defn resolve-role-binding
  "Resolve a configured role binding to a model binding snapshot.

  This is the discoverable entry for a new session's default model.  The
  app configuration uses `:main` or `:task`; a missing role is not an error
  for session creation, it simply means no default was selected."
  [config role]
  (let [binding (role-binding config role)]
    (cond
      (nil? binding)
      {:ok? true :binding nil :availability nil :role role}

      (not (ids/valid-id? (:connection-id binding)))
      (error config :ROLE_BINDING_INVALID
             "role binding has no connection-id"
             {:role role :binding binding})

      (not (ids/valid-id? (:model-entry-id binding)))
      (error config :ROLE_BINDING_INVALID
             "role binding has no model-entry-id"
             {:role role :binding binding})

      :else
      (resolve-binding config
                       (:connection-id binding)
                       (:model-entry-id binding)))))

(defn set-role-binding
  "Bind one role to a connection/model reference.

  The roles are `:main` (Steward, Plan, Reviewer) and `:task` (Plan dedicated
  worker and Swarm workers/holders)."
  [config role connection-id model-entry-id now]
  (let [resolved (resolve-binding config connection-id model-entry-id)]
    (cond
      (not (contains? roles role))
      (error config :ROLE_INVALID
             "role must be :main or :task"
             {:role role})

      (not (:ok? resolved))
      (error config (:code (:error resolved))
             (:message (:error resolved))
             (:details (:error resolved)))

      :else
      {:ok? true
       :config (-> config
                   (assoc-in [:role-bindings role]
                             {:connection-id connection-id
                              :model-entry-id model-entry-id
                              :set-at now})
                   (bump now))})))

(defn role-binding
  [config role]
  (get-in config [:role-bindings role]))

(defn models-by-display-name
  "Group model entries by their display name without merging them.

  Each entry keeps its connection identity, so two providers offering the
  same model name remain distinguishable."
  [config]
  (reduce-kv
   (fn [result connection-id connection]
     (reduce-kv
      (fn [result model-entry-id model]
        (let [name (or (:alias model)
                       (:display-name model)
                       (:provider-model-id model))]
          (update result name (fnil conj [])
                  {:connection-id connection-id
                   :connection-name (:name connection)
                   :model-entry-id model-entry-id
                   :provider-model-id (:provider-model-id model)
                   :availability (connections/model-availability connection model)
                   :capabilities (:capabilities model)
                   :pricing (:pricing model)})))
      result
      (:models connection)))
   {}
   (:connections config)))

(defn model-views
  [config connection-id]
  (let [connection (get-in config [:connections connection-id])]
    (if-not connection
      (error config :CONNECTION_NOT_FOUND
             "connection does not exist"
             {:connection-id connection-id})
      {:ok? true
       :models (mapv #(connections/public-model connection %)
                     (keep (fn [id] (get-in connection [:models id]))
                           (:model-order connection)))})))

(defn connection-views
  [config]
  (mapv (fn [connection-id]
          (let [connection (get-in config [:connections connection-id])]
            (-> connection
                connections/connection-view
                (select-keys [:connection-id :name :kind :effective-base-url
                              :official? :display-host :enabled? :removed?
                              :auth-status :rotation-policy :list-status
                              :last-test :created-at :updated-at])
                (assoc :credential-count (count (:credentials connection))
                       :model-count (count (:models connection))))))
        (:connection-order config)))

(defn check-invariants
  "Return a vector of configuration invariant violations."
  [config]
  (if-not (map? config)
    [{:invariant :config-not-a-map}]
    (let [connections (:connections config)
          connection-ids (set (keys connections))
          order (set (:connection-order config))
          assistants (:assistants config)
          role-bindings (:role-bindings config)
          global-model-ids (->> (vals connections)
                                (mapcat #(keys (:models %)))
                                vec)
          duplicate-model-ids (->> global-model-ids
                                   frequencies
                                   (keep (fn [[id n]] (when (> n 1) id)))
                                   vec)
          connection-violation?
          (some (fn [[connection-id connection]]
                  (or (not= connection-id (:connection-id connection))
                      (seq (connections/check-connection-invariants
                            connection))))
                connections)
          assistant-violation?
          (some (fn [[_ assistant]]
                  (let [default (:default-model assistant)]
                    (and default
                         (not (:ok? (resolve-binding
                                     config
                                     (:connection-id default)
                                     (:model-entry-id default)))))))
                assistants)
          role-violation?
          (some (fn [[role binding]]
                  (or (not (contains? roles role))
                      (not (:ok? (resolve-binding
                                  config
                                  (:connection-id binding)
                                  (:model-entry-id binding))))))
                role-bindings)]
      (cond-> []
        (not (integer? (:revision config)))
        (conj {:invariant :config-revision-invalid
               :revision (:revision config)})

        (or (not= connection-ids order)
            (not= (count (:connection-order config))
                  (count (keys connections))))
        (conj {:invariant :connection-order-mismatch
               :order (:connection-order config)
               :keys (keys connections)})

        (seq duplicate-model-ids)
        (conj {:invariant :model-entry-id-not-global
               :model-entry-ids duplicate-model-ids})

        connection-violation?
        (conj {:invariant :connection-invalid})

        assistant-violation?
        (conj {:invariant :assistant-default-model-unresolved})

        role-violation?
        (conj {:invariant :role-binding-unresolved})))))
