(ns hc.server.sessions.test-support
  "Deterministic builders shared by the session tests."
  (:require [hc.server.commands :as commands]
            [hc.server.sessions :as sessions]
            [hc.server.sessions.config :as config]
            [hc.server.sessions.connections :as connections]
            [hc.server.sessions.ids :as ids]))

(defn build-context
  "Build one connection, one credential, one listed model, and one assistant.

  Returns the configuration plus the stable IDs a test needs."
  ([] (build-context {}))
  ([{:keys [source now connection-id connection-name base-url
            provider-model-id display-name capabilities assistant-id]
     :or {now 1000
          connection-id "conn-1"
          connection-name "Personal"
          base-url "https://api.example.com"
          provider-model-id "gpt-x"
          display-name "GPT X"
          capabilities {:text true :tools true}
          assistant-id "asst-1"}}]
   (let [source (or source (ids/counter-source))
         new-conn (connections/new-connection
                   {:connection-id connection-id
                    :name connection-name
                    :kind :preset
                    :default-base-url base-url
                    :now now})
         _ (when-not (:ok? new-conn)
             (throw (ex-info "test connection invalid"
                             {:error (:error new-conn)})))
         config-1 (config/add-connection (config/new-config)
                                         (:connection new-conn)
                                         now)
         _ (when-not (:ok? config-1)
             (throw (ex-info "test add-connection failed"
                             {:error (:error config-1)})))
         credential (connections/add-credential
                     (get-in (:config config-1) [:connections connection-id])
                     {:credential-id "cred-1"
                      :alias "default"
                      :secret-ref "vault://test/cred-1"
                      :masked-key "sk-...test"
                      :priority 0}
                     now)
         _ (when-not (:ok? credential)
             (throw (ex-info "test credential invalid"
                             {:error (:error credential)})))
         config-2 (assoc-in (:config config-1)
                            [:connections connection-id]
                            (:connection credential))
         refreshed (connections/refresh-models
                    (get-in config-2 [:connections connection-id])
                    [{:provider-model-id provider-model-id
                      :display-name display-name
                      :capabilities capabilities
                      :pricing {:status :unknown}}]
                    source now)
         _ (when-not (:ok? refreshed)
             (throw (ex-info "test model refresh failed"
                             {:error (:error refreshed)})))
         config-3 (assoc-in config-2
                            [:connections connection-id]
                            (:connection refreshed))
         model-entry-id (first (get-in refreshed [:connection :model-order]))
         assistant (config/add-assistant
                    config-3
                    {:assistant-id assistant-id
                     :name "Default"
                     :default-model {:connection-id connection-id
                                     :model-entry-id model-entry-id}}
                    now)
         _ (when-not (:ok? assistant)
             (throw (ex-info "test assistant invalid"
                             {:error (:error assistant)})))]
     {:source source
      :now now
      :connection-id connection-id
      :model-entry-id model-entry-id
      :assistant-id assistant-id
      :config (:config assistant)})))

(defn new-test-session
  ([ctx] (new-test-session ctx {}))
  ([{:keys [source now assistant-id config]} opts]
   (let [result (sessions/new-session
                 (merge {:assistant-id assistant-id
                         :config config
                         :id-source source
                         :now now
                         :client-id "w1"}
                        opts))]
     (if (:ok? result)
       (assoc (:session result)
              ::gate (:state (commands/issue-epoch (commands/new-state))))
       (throw (ex-info "test session invalid"
                       {:error (:error result)}))))))

(defn attach-gate
  "Attach a fresh Owner-like gate to a session created outside this helper."
  [session]
  (assoc session ::gate (:state (commands/issue-epoch (commands/new-state)))))

(defn gate
  "The test session's Owner-like command gate."
  [session]
  (::gate session))

(defn execute
  ([session type payload opts]
   (execute session (str "cmd-" (random-uuid)) 1 type payload opts))
  ([session command-id epoch type payload opts]
   (let [result (sessions/execute
                 session
                 {:command-id command-id
                  :command-epoch epoch
                  :client-id (or (:client-id opts) "w1")
                  :type type
                  :payload payload}
                 (assoc opts :gate (gate session)))]
     (if (:session result)
       (assoc result :session (assoc (:session result)
                                     ::gate (:gate-state result)))
       result))))

(defn execute-request
  "Execute a full command request against the test session's gate."
  [session request opts]
  (let [result (sessions/execute session request
                                 (assoc opts :gate (gate session)))]
    (if (:session result)
      (assoc result :session (assoc (:session result)
                                    ::gate (:gate-state result)))
      result)))

(defn issue-epoch
  [session now]
  (let [result (sessions/issue-command-epoch (gate session) now)]
    (assoc result
           :session (assoc session ::gate (:gate-state result)))))

(defn metadata-epoch
  [session]
  (sessions/command-epoch (gate session)))

(defn ok-session
  [result]
  (when-not (:ok? result)
    (throw (ex-info "expected success"
                    {:error (:error result) :receipt (:receipt result)})))
  (:session result))
