(ns hc.server.app.settings
  "Application-level settings command handler.

  Provider connections and role bindings are configuration, not session
  domain state.  This namespace persists them through the session
  persistence port and reports honestly whether a change is effective now or
  only after restart.  Secrets are never returned in a snapshot; inline
  secrets are not persisted to the current-state store."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [hc.server.model-auth.app :as model-auth-app]
            [hc.server.app.snapshot :as snapshot]
            [hc.server.sessions.credentials :as credentials]
            [hc.server.sessions.ports :as ports])
  (:import (java.io ByteArrayOutputStream InputStream)
           (java.net URI)
           (java.net.http HttpClient HttpClient$Redirect HttpRequest
                          HttpRequest$BodyPublishers HttpResponse$BodyHandlers)
           (java.nio.charset StandardCharsets)
           (java.time Duration)))

(def ^:private secret-fields
  #{:api-key :secret :token :password :client-secret :access-token
    :refresh-token :private-key})

(defn- config-revision
  [config]
  (long (or (:revision config) 0)))

(def ^:private launch-connection-ids-key :launch-connection-ids)
(def ^:private removed-connection-ids-key :removed-connection-ids)

(defn- connection-id-set
  [connections]
  (into #{} (keep :id) connections))

(defn- connection-list
  [connections]
  (if (sequential? connections) (vec connections) []))

(defn- stored-id-set
  "Read one provenance id set from a persisted document.

  A malformed value is treated as absent.  Origin is never inferred from a
  credential: an inline `:api-key` says nothing about who created the
  connection, and guessing from it can delete a connection the operator
  saved through the settings UI."
  [persisted key]
  (let [ids (get persisted key)]
    (cond
      (set? ids) ids
      (sequential? ids) (set ids)
      :else #{})))

(defn- settings-document
  "Build the persisted settings document from the launch and stored documents.

  The document keeps every connection the operator has settings for.  A
  launch-declared connection the current launch no longer lists stays in
  the document but is hidden from the effective config, so a launch edit
  revokes a connection without erasing its saved fields; declaring it again
  restores them.  `:launch-connection-ids` is the union of the stored
  provenance and the current launch ids.

  A legacy document without provenance keeps every connection.  Its origin
  is unknown, and a stale row is recoverable while a deleted operator
  connection is not."
  [launch persisted]
  (let [persisted (or persisted {})
        launch-connections (connection-list (:connections launch))
        persisted-connections (connection-list (:connections persisted))
        persisted-ids (set (map :id persisted-connections))
        ;; A launch-declared connection must exist in the persisted document
        ;; too, or a backup taken from the store cannot carry it and a
        ;; restore to another instance loses the model configuration.  A
        ;; user-saved row always wins; only missing ids are seeded.
        merged-connections
        (vec (concat persisted-connections
                     (map #(assoc % :source :launch)
                          (remove #(contains? persisted-ids (:id %))
                                  launch-connections))))
        launch-ids (connection-id-set launch-connections)
        inherited (into (stored-id-set persisted launch-connection-ids-key)
                        launch-ids)
        removed (stored-id-set persisted removed-connection-ids-key)]
    (cond-> {:connections merged-connections
             launch-connection-ids-key inherited
             removed-connection-ids-key removed}
      (contains? persisted :roles)
      (assoc :roles (:roles persisted))

      (contains? persisted :default-role)
      (assoc :default-role (:default-role persisted))

      ;; Assistants and appearance preferences live in the same committed
      ;; document.  A connection or role save must not drop them.
      (contains? persisted :assistants)
      (assoc :assistants (:assistants persisted))

      (contains? persisted :appearance)
      (assoc :appearance (:appearance persisted))

      ;; Backup target configuration lives in the same committed document
      ;; under [:backup :targets].  The backup service CAS-writes it
      ;; independently; a settings save must not silently discard it.
      (contains? persisted :backup)
      (assoc :backup (:backup persisted))

      (contains? persisted :revision)
      (assoc :revision (:revision persisted)))))

(defn merge-model-config
  "Apply one persisted settings document to the launch model configuration.

  The launch configuration is the instance-level authorization: it decides
  which connections exist.  The settings document may override fields of a
  launch-declared connection and may add connections the operator created in
  the settings UI.  A persisted connection whose id is in
  `:launch-connection-ids` but absent from the current launch configuration
  is hidden, so editing the launch file to remove a provider revokes it
  instead of letting the settings copy resurrect it.  The persisted document
  keeps the hidden connection; a later launch declaration restores its
  saved fields, and an unrelated save never erases them.

  An operator deletion is recorded under `:removed-connection-ids`, so
  deleting a launch-declared connection also survives its launch
  declaration.  Saving a connection clears its tombstone and records
  whether the current launch still declares it.

  Both keys are provenance, not provider configuration.  The adapter
  projection (`app.system/model-configuration`) drops them before the model
  adapter or any client sees the map."
  [launch persisted]
  (let [launch (or launch {})
        persisted (or persisted {})
        launch-connections (connection-list (:connections launch))
        launch-ids (connection-id-set launch-connections)
        inherited (into (stored-id-set persisted launch-connection-ids-key)
                        launch-ids)
        removed (stored-id-set persisted removed-connection-ids-key)
        persisted-connections (connection-list (:connections persisted))
        persisted-by-id (into {}
                              (keep (fn [connection]
                                      (when (some? (:id connection))
                                        [(:id connection) connection])))
                              persisted-connections)
        merged-connections
        (->> (concat
              (map (fn [connection]
                     (merge connection
                            (get persisted-by-id (:id connection))))
                   launch-connections)
              (remove (fn [connection]
                        (or (contains? launch-ids (:id connection))
                            (contains? inherited (:id connection))))
                      persisted-connections))
             (remove (fn [connection]
                       (contains? removed (:id connection))))
             vec)]
    (cond-> {:connections merged-connections
             launch-connection-ids-key inherited
             removed-connection-ids-key removed}
      (contains? launch :roles)
      (assoc :roles (:roles launch))

      (contains? persisted :roles)
      (assoc :roles (:roles persisted))

      (contains? launch :default-role)
      (assoc :default-role (:default-role launch))

      (contains? persisted :default-role)
      (assoc :default-role (:default-role persisted)))))

(defn- persisted-config
  "Read the persisted config document, or nil when none is stored.

  `app.system/effective-config` merges only the model fields into the launch
  config, so the settings service recovers both the model body and the CAS
  revision from the same persistence port.  Otherwise a restart starts with
  an empty model view and revision 0, and the first save either loses the
  persisted connections or conflicts with every persisted revision above
  zero."
  [persistence]
  (when persistence
    (let [result (ports/read-config persistence)]
      (when (= :found (:status result))
        (:config result)))))

(defn- connection-by-id
  [config id]
  (first (filter #(= id (:id %)) (:connections config))))

(defn- replace-connection
  [config connection]
  (let [id (:id connection)
        connections (vec (:connections config))
        present? (some #(= id (:id %)) connections)]
    (assoc config
           :connections
           (if present?
             (mapv #(if (= id (:id %)) connection %) connections)
             (conj connections connection)))))

(defn- normalize-role-binding
  "Normalize one role binding to the committed settings shape.

  The settings document is read by the provider adapter, the fleet launcher,
  and the tool-capability gate, which agree on `:connection`/`:model`.  The
  sessions layer names the same identities `:connection-id`/`:model-entry-id`,
  and some callers use `:model-id`.  Store the shape every consumer accepts
  instead of forwarding whichever alias arrived; otherwise a role saved in
  the sessions shape renders without a model in the settings view and is
  rejected by the adapter as an unknown field."
  [binding]
  (if-not (map? binding)
    binding
    (let [connection (or (:connection binding) (:connection-id binding))
          model (or (:model binding)
                    (:model-entry-id binding)
                    (:model-id binding))]
      (cond-> (dissoc binding :connection-id :model-entry-id :model-id)
        (some? connection) (assoc :connection connection)
        (some? model) (assoc :model model)))))

(defn- normalize-roles
  [roles]
  (if (map? roles)
    (into {}
          (map (fn [[role binding]] [role (normalize-role-binding binding)]))
          roles)
    roles))

(defn- effective-config
  "Merge DOCUMENT into LAUNCH and normalize role bindings."
  [launch document]
  (let [config (merge-model-config launch document)]
    (if (map? (:roles config))
      (update config :roles normalize-roles)
      config)))

(defn- drop-secrets
  "Keep environment-variable references, drop inline secret values."
  [connection]
  (apply dissoc connection secret-fields))

;; ---------------------------------------------------------------------------
;; Reachability probe

(defn- http-client []
  (-> (HttpClient/newBuilder)
      (.connectTimeout (Duration/ofSeconds 5))
      (.followRedirects HttpClient$Redirect/NEVER)
      (.build)))

(defn- probe-failure-message
  "Describe a probe failure without echoing the request URI or credentials."
  [exception]
  (cond
    (instance? java.net.UnknownHostException exception) "unknown host"
    (instance? java.net.ConnectException exception) "connection refused"
    (instance? java.net.http.HttpTimeoutException exception) "connection timed out"
    (instance? javax.net.ssl.SSLException exception) "TLS handshake failed"
    :else "connection probe failed"))

(def ^:private minimal-request-message
  "Herdchestra connection test. Reply with OK.")

(def ^:private minimal-request-timeout-seconds 20)

(def ^:private minimal-request-response-limit (* 64 1024))

(defn- connection-provider
  "Map a connection to the provider wire shape used by the test probe.

  This follows `hc.server.model-adapter.config`: `:kind` wins, then
  `:protocol`, and the default is OpenAI.  `:preset` is the legacy alias for
  an OpenAI-compatible endpoint."
  [connection]
  (let [kind (keyword (or (:kind connection) (:protocol connection) :openai))]
    (case kind
      (:openai :openai-compatible :preset) :openai-compatible
      :ollama :ollama
      :custom :custom
      :unknown)))

(defn- connection-models
  "Return the connection's models as a vector, accepting the legacy map form."
  [connection]
  (let [models (:models connection)]
    (cond
      (map? models)
      (mapv (fn [[id model]]
              (if (map? model)
                (assoc model :id (or (:id model) (:model-id model) id))
                {:id id}))
            models)

      (sequential? models) (vec models)
      :else [])))

(defn- model-id
  "Return the wire model name, matching the adapter's `:model-name` order."
  [model]
  (let [id (or (:model-name model) (:provider-model-id model)
               (:model-id model) (:id model) (:name model))]
    (cond
      (string? id) (when-not (str/blank? id) id)
      (keyword? id) (name id)
      (some? id) (let [id (str id)]
                   (when-not (str/blank? id) id))
      :else nil)))

(defn- selected-test-model
  "Return the first model entry enabled for CONNECTION, or nil.

  A connection with no enabled model has nothing to prove.  The probe must
  not invent a model the operator did not select."
  [connection]
  (some (fn [model]
          (when (and (map? model)
                     (not (false? (:enabled? model)))
                     (model-id model))
            model))
        (connection-models connection)))

(defn- minimal-request-path
  [provider]
  (case provider
    :openai-compatible "/chat/completions"
    nil))

(defn- minimal-request-uri
  [connection provider]
  (let [base (:base-url connection)
        path (minimal-request-path provider)]
    (when (and path (string? base) (not (str/blank? base)))
      (try (URI/create (str (str/replace base #"/+$" "") path))
           (catch Exception _ nil)))))

(defn- minimal-request-body
  "Build the fixed test request.

  No sampling parameter is forced: a token cap that is too small can make a
  reasoning model return an empty reply, which would be a false failure.  The
  fixed prompt asks for a short reply, and the test warning names the possible
  usage."
  [model]
  {:model (model-id model)
   :messages [{:role "user" :content minimal-request-message}]
   :stream false})

(defn- response-content
  [content]
  (cond
    (string? content) content
    (sequential? content)
    (->> content
         (keep (fn [part]
                 (when (map? part)
                   (let [text (or (:text part) (get part "text"))]
                     (when (string? text) text)))))
         (str/join "\n"))
    :else nil))

(defn- response-reply
  [provider body]
  (case provider
    :openai-compatible
    (response-content (get-in body [:choices 0 :message :content]))
    nil))

(defn- read-test-response
  "Read at most the test response limit.  Never buffers an unbounded body."
  [^InputStream input]
  (with-open [input input]
    (let [^ByteArrayOutputStream output (ByteArrayOutputStream.)
          buffer (byte-array 4096)]
      (loop [total 0]
        (if (>= total minimal-request-response-limit)
          {:text (String. (.toByteArray output) StandardCharsets/UTF_8)
           :truncated? true}
          (let [read (.read input buffer)]
            (if (neg? read)
              {:text (String. (.toByteArray output) StandardCharsets/UTF_8)
               :truncated? false}
              (let [keep (min read (- minimal-request-response-limit total))]
                (.write output buffer 0 keep)
                (recur (+ total keep))))))))))

(defn- minimal-request-failure-message
  [status]
  (cond
    (= 401 status) "authentication rejected"
    (= 403 status) "access denied"
    (= 404 status) "model or endpoint not found"
    (= 408 status) "model request timed out"
    (= 429 status) "rate limited; retry later"
    (<= 500 status 599) "provider error"
    :else (str "model request was refused (HTTP " status ")")))

(defn- minimal-request!
  "Send one fixed, non-streaming chat request and report whether it replied.

  The request carries no session history, attachment, tool schema, or user
  draft.  A 2xx status is not enough: the response must contain reply text.
  Error values never contain the URI, request body, response body, or
  credential."
  [client connection provider model api-key]
  (try
    (let [uri (minimal-request-uri connection provider)]
      (if (nil? uri)
        {:status :failed
         :message "connection has no valid model endpoint"}
        (let [body (json/write-str (minimal-request-body model))
              builder (-> (HttpRequest/newBuilder uri)
                          (.timeout (Duration/ofSeconds
                                     minimal-request-timeout-seconds))
                          (.header "Content-Type" "application/json")
                          (.header "Accept" "application/json")
                          (.POST (HttpRequest$BodyPublishers/ofString body)))
              builder (if (and api-key (not (str/blank? api-key)))
                        (.header builder "Authorization"
                                 (str "Bearer " api-key))
                        builder)
              response (.send client (.build builder)
                              (HttpResponse$BodyHandlers/ofInputStream))
              status (.statusCode response)
              {:keys [text truncated?]} (read-test-response (.body response))]
          (cond
            (not (<= 200 status 299))
            {:status :failed
             :http-status status
             :message (minimal-request-failure-message status)}

            truncated?
            {:status :failed
             :http-status status
             :message "model response exceeded the test size limit"}

            :else
            (let [parsed (try (json/read-str text :key-fn keyword)
                              (catch Exception _ nil))
                  reply (response-reply provider parsed)]
              (if (str/blank? (str reply))
                {:status :failed
                 :http-status status
                 :message "model response contained no reply text"}
                {:status :ok
                 :http-status status
                 :model (model-id model)}))))))
    (catch InterruptedException _
      (.interrupt (Thread/currentThread))
      {:status :failed :message "model request was interrupted"})
    (catch java.net.http.HttpTimeoutException _
      {:status :failed :message "model request timed out"})
    (catch Exception e
      {:status :failed :message (probe-failure-message e)})))

(defn- probe-connection
  "Run the four connection-test layers in order.

  The model-list GET proves reachability and authentication.  It never
  claims that a model can generate.  When the connection has an enabled model
  and an OpenAI-compatible endpoint, one non-streaming chat request proves
  the fourth layer.  A failed model list does not prevent that request: a
  manually added model remains testable when the service does not implement
  `/models`.  A rejected authentication does prevent it, because the probe
  must not send a model request with a credential the service already
  refused."
  [connection]
  (let [base (:base-url connection)
        api-key (or (:api-key connection)
                    (some-> (:api-key-env connection) System/getenv))
        uri (when (and (string? base) (not (str/blank? base)))
              (try (URI/create (str (str/replace base #"/+$" "") "/models"))
                   (catch Exception _ nil)))]
    (cond
      (nil? uri)
      {:reachable {:status :failed
                   :message "connection has no valid base-url"}
       :auth-ok {:status :not-run}
       :models-listed {:status :not-run}
       :minimal-request-ok {:status :not-run}}

      :else
      (try
        (let [client (http-client)
              builder (-> (HttpRequest/newBuilder uri)
                          (.timeout (Duration/ofSeconds 5))
                          (.GET))
              builder (if (and api-key (not (str/blank? api-key)))
                        (.header builder "Authorization" (str "Bearer " api-key))
                        builder)
              response (.send client (.build builder)
                              (HttpResponse$BodyHandlers/discarding))
              status (.statusCode response)
              layers {:reachable {:status :ok :http-status status}
                      :auth-ok (if (or (= 401 status) (= 403 status))
                                 {:status :failed :http-status status}
                                 {:status :ok :http-status status})
                      :models-listed (if (<= 200 status 299)
                                       {:status :ok :http-status status}
                                       {:status :failed :http-status status})}
              auth-ok? (= :ok (get-in layers [:auth-ok :status]))
              provider (connection-provider connection)
              model (selected-test-model connection)]
          (assoc layers
                 :minimal-request-ok
                 (cond
                   (not auth-ok?)
                   {:status :not-run
                    :reason "authentication failed; no model request was sent"}

                   (nil? model)
                   {:status :not-run
                    :reason "no enabled model is selected"}

                   (not= :openai-compatible provider)
                   {:status :not-run
                    :reason "this protocol has no minimal request probe"}

                   :else
                   (minimal-request! client connection provider model api-key))))
        (catch InterruptedException _
          (.interrupt (Thread/currentThread))
          {:reachable {:status :failed
                       :message "connection probe was interrupted"}
           :auth-ok {:status :not-run}
           :models-listed {:status :not-run}
           :minimal-request-ok {:status :not-run}})
        (catch Exception e
          {:reachable {:status :failed :message (probe-failure-message e)}
           :auth-ok {:status :not-run}
           :models-listed {:status :not-run}
           :minimal-request-ok {:status :not-run}})))))

(declare config)

(defn- oauth-connection?
  [connection]
  (or (= :oauth (:kind connection))
      (= :account-login (:service-kind connection))
      (= :oauth (get-in connection [:auth :type]))
      (= :oauth (get-in connection [:auth :mode]))))

(defn- merge-test-connection
  "Merge the submitted fields with the stored connection for one test.

  The client intentionally omits stored secret references.  Keep the stored
  credential table so the test uses the same key selection path as a real
  request; never persist the resolved secret or the use-count update."
  [service payload]
  (let [submitted (:fields payload)
        id (or (:connection-id payload) (:id submitted))
        existing (when (some? id)
                   (connection-by-id (config service) (str id)))]
    (cond
      (and (map? existing) (map? submitted))
      (cond-> (-> (merge existing submitted)
                  (assoc :id (str id)
                         :credentials (or (:credentials existing) {}))
                  (dissoc :keys :credential-order))
        (seq (:credential-order existing))
        (assoc :credential-order (:credential-order existing)))

      (map? submitted) submitted
      :else existing)))

(defn- payload-credential
  "Return the one-time secret to use for an unsaved key edit, if any."
  [payload]
  (->> (:credentials payload)
       (filter (fn [credential]
                 (and (not (false? (:enabled? credential)))
                      (string? (:secret credential))
                      (not (str/blank? (:secret credential))))))
       (sort-by (fn [credential]
                  [(or (:priority credential) 0)
                   (str (:key-id credential))]))
       first))

(defn- resolve-test-auth
  "Resolve the credential the real request path would use.

  OAuth connections go through `hc.server.model-auth`, including refresh and
  reauthorization errors.  API-key connections use a one-time submitted
  secret when present, otherwise the stored key selected by the connection
  policy.  The result is ephemeral and is never written back."
  [service payload connection model-auth]
  (cond
    (oauth-connection? connection)
    (if-not model-auth
      {:auth-error {:code :MODEL_AUTH_UNAVAILABLE
                    :message "model-auth component is not running"}}
      (let [result (try
                     (model-auth-app/inject-models
                      model-auth {:connections [connection]})
                     (catch Throwable _
                       {:models {:connections [connection]}
                        :errors {(:id connection)
                                 {:code :MODEL_AUTH_TOKEN_FAILED
                                  :message "model-auth token resolution failed"}}}))
            error (get-in result [:errors (:id connection)])
            injected (first (get-in result [:models :connections]))]
        (if error
          {:auth-error error}
          (if (str/blank? (str (:api-key injected)))
            {:auth-error {:code :MODEL_AUTH_TOKEN_MISSING
                          :message "model-auth returned no access token"}}
            {:connection (assoc connection :api-key (:api-key injected))}))))

    (payload-credential payload)
    {:connection (assoc connection
                        :api-key (:secret (payload-credential payload)))}

    (and (seq (:credentials connection))
         (nil? (:secret-store service)))
    {:auth-error {:code :SECRET_STORE_REQUIRED
                  :message "stored credentials need a SecretStore"}}

    (seq (:credentials connection))
    (let [resolved (credentials/resolve-credential
                    (:secret-store service) connection
                    (System/currentTimeMillis))]
      (if (:ok? resolved)
        {:connection (assoc connection :api-key (:secret resolved))}
        {:auth-error (:error resolved)}))

    :else
    {:connection connection}))

(defn- test-layers
  [connection auth-error]
  (if auth-error
    {:reachable {:status :not-run
                 :reason "authentication could not be resolved"}
     :auth-ok (cond-> {:status :failed
                       :message (:message auth-error)}
                (:code auth-error) (assoc :code (:code auth-error)))
     :models-listed {:status :not-run}
     :minimal-request-ok {:status :not-run}}
    (probe-connection connection)))

;; ---------------------------------------------------------------------------
;; Service

(defn service
  "Create a settings service.

  `:persistence` is a `SessionPersistence` and is the hydration source.
  `:config` is the launch model configuration.  The launch configuration
  decides connection existence: a persisted connection the launch no longer
  declares is hidden from the effective config.  The persisted document
  itself keeps the connection, so a later launch declaration restores its
  saved fields and an unrelated save never erases them.  A persisted
  document also overrides the fields of the connections the launch still
  declares, adds connections created through the settings UI, and keeps an
  operator deletion (see `merge-model-config`).  The document's `:revision`
  is restored for compare-and-set.  `:missing` or `:error` from the
  persistence port leaves CONFIG in place.
  `:on-change` is called with the new config after a successful persist.
  Role bindings are normalized to the committed config shape
  (`:connection`/`:model`) on hydration and save; the sessions aliases
  (`:connection-id`/`:model-entry-id`) stay accepted at the boundary."
  [{:keys [persistence config on-change apply! secret-store]
    :or {config {}}}]
  (let [persisted (persisted-config persistence)
        launch (or config {})
        ;; The document is the committed shape; keep its roles canonical so
        ;; a connection save cannot write the sessions aliases back.
        normalize-document (fn [document]
                             (if (map? (:roles document))
                               (update document :roles normalize-roles)
                               document))
        document (normalize-document (settings-document launch persisted))
        ;; Persist the launch-seeded connections once so a backup of this
        ;; store includes them.  The commit is best-effort; the in-memory
        ;; document already carries them.
        bootstrapped (when (and persistence
                               (> (count (connection-list (:connections document)))
                                  (count (connection-list (:connections persisted)))))
                       (try
                         (let [r (ports/commit-config!
                                  persistence
                                  (long (or (:revision persisted) 0))
                                  (dissoc document :revision))]
                           r)
                         (catch Throwable _t
                           nil)))
        ;; A bootstrap commit can lose the CAS race.  Re-read the stored
        ;; document so the revision and content describe the same state;
        ;; pairing the conflict revision with the stale local document would
        ;; let the next save overwrite the other writer silently.
        conflict? (= :conflict (:status bootstrapped))
        persisted (if conflict?
                    (or (persisted-config persistence) persisted)
                    persisted)
        document (if conflict?
                   (normalize-document (settings-document launch persisted))
                   document)
        revision (if conflict?
                   (or (:revision persisted) (:revision launch))
                   (or (:revision bootstrapped) (:revision persisted) (:revision launch)))
        document (cond-> document
                   (some? revision) (assoc :revision revision))
        config (cond-> (effective-config launch document)
                 (some? revision) (assoc :revision revision))
        state (atom {:config config :document document})]
    {:state state
     :lock (Object.)
     :persistence persistence
     :launch launch
     :launch-ids (connection-id-set (connection-list (:connections launch)))
     :on-change on-change
     :apply! apply!
     :secret-store secret-store}))

(defn- with-write-lock
  "Serialize one settings document write.

  Save-connection, save-role, and delete-connection each read the document,
  compute the effective config, and compare-and-set one revision.  Running
  two of them concurrently lets the second commit observe the first writer's
  revision and return `SETTINGS_CONFLICT`; the refused save keeps only the
  browser draft, so a capability declaration can look saved while the
  committed config still lacks it.  The lock covers the whole read/merge/
  commit, not just the store CAS."
  [service f]
  (locking (or (:lock service) (:state service) service)
    (f)))

(defn config
  [service]
  (:config @(:state service)))

(defn document
  "The committed settings document, including assistants and appearance."
  [service]
  (:document @(:state service)))

(defn snapshot
  [service]
  ;; Assistants live in the committed document, not the model projection;
  ;; the settings view needs them to expose :settings/assistants.
  (snapshot/settings-view
   (assoc (config service)
          :assistants (:assistants (document service)))))

(defn- commit-config!
  [service next-config next-document]
  (let [persistence (:persistence service)
        current (config service)
        revision (config-revision current)
        result (ports/commit-config! persistence revision
                                     (dissoc next-document :revision))]
    (case (:status result)
      :committed
      (let [next (assoc next-config :revision (:revision result))
            next-document (assoc next-document :revision (:revision result))
            applied (when-let [f (:apply! service)]
                      (try (f next)
                           (catch Throwable t
                             {:ok? false
                              :error {:code :SETTINGS_APPLY_FAILED
                                      :message (or (.getMessage t) (str t))}})))]
        (swap! (:state service) assoc
               :config next
               :document next-document)
        (when-let [f (:on-change service)] (f next))
        (if (and applied (false? (:ok? applied)))
          {:ok? false
           :error (or (:error applied)
                      {:code :SETTINGS_APPLY_FAILED
                       :message "settings were saved but not applied"})
           :receipt {:status :refused
                     :config-revision (:revision result)
                     :saved? true
                     :applied? false}}
          {:ok? true :receipt {:status :accepted
                               :config-revision (:revision result)
                               :saved? true
                               :applied? (if applied
                                           (not (false? (:applied? applied)))
                                           false)
                               :apply-result applied
                               :restart-required (not (and applied
                                                           (:applied? applied)))}}))

      :conflict
      {:ok? false
       :error {:code :SETTINGS_CONFLICT
               :message "settings changed in another writer; re-read before saving"
               :details {:revision (:revision result)}}}

      {:ok? false
       :error (or (:error result)
                  {:code :SETTINGS_COMMIT_FAILED
                   :message "settings could not be committed"})})))

(defn- handle-save-connection!
  [service payload]
  (let [submitted (:fields payload)
        id (or (:connection-id payload) (:id submitted))]
    (cond
      (not (map? submitted))
      {:ok? false :error {:code :SETTINGS_INVALID
                          :message "connection fields must be a map"}}

      (str/blank? (str id))
      {:ok? false :error {:code :SETTINGS_INVALID
                          :message "connection-id is required"}}

      :else
      (let [existing (connection-by-id (config service) (str id))
            ;; The client sends the editable connection, not the server's
            ;; credential table.  Merge the stored connection first so a
            ;; rename or model edit never drops credentials, test results,
            ;; or other server-owned fields.
            connection (snapshot/sanitize-connection
                        (cond-> (-> (merge existing submitted)
                                    (assoc :id (str id))
                                    (assoc :credentials
                                           (or (:credentials existing) {}))
                                    (dissoc :keys :credential-order))
                          (seq (:credential-order existing))
                          (assoc :credential-order
                                 (:credential-order existing))))
            applied
            (if (:secret-store service)
              (credentials/apply-settings-credentials!
               (:secret-store service)
               connection
               payload
               (System/currentTimeMillis))
              {:ok? true :connection connection})
            connection (if (:ok? applied)
                         (assoc (drop-secrets (:connection applied))
                                :id (str id))
                         connection)]
        (if-not (:ok? applied)
          {:ok? false :error (:error applied)}
          (let [connection-id (str id)
                launch-ids (:launch-ids service)
                next-document
                (-> (document service)
                    (replace-connection connection)
                    (update removed-connection-ids-key
                            (fnil disj #{}) connection-id)
                    ;; A connection the current launch declares stays under
                    ;; launch control.  An id the launch does not declare is
                    ;; an operator-created connection: dropping it from the
                    ;; inherited set keeps it visible after a restart.
                    (update launch-connection-ids-key
                            (fn [ids]
                              (let [ids (or ids #{})]
                                (if (contains? launch-ids connection-id)
                                  (conj ids connection-id)
                                  (disj ids connection-id))))))
                next-config (effective-config (:launch service) next-document)]
            (commit-config! service next-config next-document)))))))

(defn- handle-save-role!
  [service payload]
  (let [roles (normalize-roles (:roles payload))
        next-document (assoc (document service) :roles (or roles {}))
        next-config (effective-config (:launch service) next-document)]
    (commit-config! service next-config next-document)))

(defn- normalize-assistant-entry
  "Normalize one submitted assistant to the persisted shape.

  The client sends `{id name revision enabled? default-model}`; the id is
  the map key.  Unknown fields are dropped so a malformed payload cannot
  smuggle transport or credential data into the committed document."
  [id assistant]
  (when (map? assistant)
    (let [id (str (or (:id assistant) (:assistant-id assistant) id))]
      (when-not (str/blank? id)
        (cond-> {:id id
                 :assistant-id (str (or (:assistant-id assistant) id))
                 :name (str (or (:name assistant) (:display-name assistant) id))
                 :revision (long (or (:revision assistant) 1))
                 :enabled? (not (false? (:enabled? assistant)))}
          (:display-name assistant)
          (assoc :display-name (str (:display-name assistant)))
          (map? (:default-model assistant))
          (assoc :default-model
                 (select-keys (:default-model assistant)
                              [:connection-id :model-id :model-entry-id])))))))

(defn- handle-save-assistants!
  "Persist the assistant map from `:settings/save-assistants`.

  The payload carries the desired assistant map under `:assistants`.
  `:delete-assistant-id` removes one entry; the submitted map is then
  authoritative for every other id, so a delete cannot resurrect through a
  stale copy.  Without `:assistants` the persisted map minus the deleted id
  is kept.  The built-in `default` assistant may be edited but never
  deleted: removing it would leave new sessions without a stable identity."
  [service payload]
  (let [submitted (:assistants payload)
        delete-id (some-> (:delete-assistant-id payload) str)
        persisted (or (:assistants (document service)) {})
        submitted-map
        (when (map? submitted)
          (reduce-kv (fn [m id assistant]
                       (if-let [normalized (normalize-assistant-entry id assistant)]
                         (assoc m (:id normalized) normalized)
                         m))
                     {}
                     submitted))
        merged (cond
                 (and (map? submitted) (nil? delete-id))
                 ;; A save replaces the whole assistant map; the client sends
                 ;; every assistant it wants kept.
                 submitted-map

                 (map? submitted)
                 ;; A delete carries the post-delete map; trust it over the
                 ;; persisted copy so the removed id stays removed.
                 (dissoc submitted-map delete-id)

                 delete-id
                 (dissoc persisted delete-id)

                 :else persisted)
        ;; A save that omits `default` keeps the persisted built-in entry;
        ;; a delete of `default` is refused below, so it is never lost here.
        merged (if (and (nil? delete-id)
                        (not= "default" delete-id)
                        (contains? persisted "default")
                        (not (contains? merged "default")))
                 (assoc merged "default" (get persisted "default"))
                 merged)
        next-document (assoc (document service) :assistants merged)
        next-config (effective-config (:launch service) next-document)]
    (cond
      (and delete-id (= "default" delete-id))
      {:ok? false
       :error {:code :SETTINGS_INVALID
               :message "the built-in assistant cannot be deleted"}}

      :else
      (commit-config! service next-config next-document))))

(defn- handle-delete-connection!
  [service payload model-auth]
  (let [id (str (:connection-id payload))
        existing (connection-by-id (config service) id)
        oauth? (oauth-connection? existing)
        oauth-result
        (cond
          (not oauth?) {:ok? true}
          (nil? model-auth)
          {:ok? false
           :error {:code :MODEL_AUTH_UNAVAILABLE
                   :message "model-auth component is not running"}}
          :else
          (try
            (model-auth-app/handle! model-auth
                                    {:kind :model-auth/logout
                                     :connection-id (str id)})
            (catch Throwable _
              {:ok? false
               :error {:code :MODEL_AUTH_LOGOUT_FAILED
                       :message "model-auth logout failed"}})))
        credential-result
        (if (and (:ok? oauth-result)
                 existing
                 (:secret-store service)
                 (seq (:credentials existing)))
          (credentials/delete-connection-credentials!
           (:secret-store service) existing (System/currentTimeMillis))
          {:ok? true})]
    (cond
      (not (:ok? oauth-result))
      {:ok? false
       :error (or (:error oauth-result)
                  {:code :SETTINGS_OAUTH_LOGOUT_FAILED
                   :message "the account credential could not be removed"})}

      (not (:ok? credential-result))
      {:ok? false
       :error {:code :SETTINGS_CREDENTIAL_DELETE_FAILED
               :message "stored connection credentials could not be deleted"
               :details {:errors (mapv #(select-keys % [:code])
                                       (:errors credential-result))}}}

      :else
      (let [next-document (-> (document service)
                              (update :connections
                                      (fn [connections]
                                        (vec (remove #(= id (:id %))
                                                     connections))))
                              (update removed-connection-ids-key
                                      (fnil conj #{}) id))
            next-config (effective-config (:launch service) next-document)]
        (commit-config! service next-config next-document)))))

(defn handle!
  "Handle one `:settings/*` payload.

  Returns `{:ok? true :receipt ...}` or `{:ok? false :error ...}`.  The
  caller is responsible for delivering it to the client.  OPTIONS may carry
  `:model-auth`, the live model-auth component used to resolve an OAuth
  access token for `:settings/test-connection` and to log out the account
  when an `:account-login` connection is deleted."
  ([service payload] (handle! service payload {}))
  ([service payload options]
   (let [kind (:kind payload)
         model-auth (:model-auth options)]
     (case kind
      :settings/save-connection
      (with-write-lock service #(handle-save-connection! service payload))
      :settings/save-role
      (with-write-lock service #(handle-save-role! service payload))
      :settings/save-assistants
      (with-write-lock service #(handle-save-assistants! service payload))
      :settings/delete-connection
      (with-write-lock service
                       #(handle-delete-connection! service payload model-auth))
      :settings/list-models
      {:ok? true
       :receipt {:status :accepted
                 :connections (into {}
                                    (map (fn [connection]
                                           [(:id connection)
                                            (snapshot/settings-models-view
                                             (:models connection))]))
                                    (:connections (config service)))
                 :model-list-status :configured-list-only}}
      :settings/test-connection
      (let [connection (merge-test-connection service payload)]
        (if-not (map? connection)
          {:ok? false :error {:code :SETTINGS_CONNECTION_UNKNOWN
                              :message "connection is not configured"
                              :details {:connection-id (:connection-id payload)}}}
          (let [{:keys [connection auth-error]}
                (resolve-test-auth service payload connection model-auth)]
            {:ok? true
             :receipt {:status :complete
                       :command-id (:command-id payload)
                       :config-digest (:config-digest payload)
                       :layers (test-layers connection auth-error)}})))
      :settings/cancel-test
      {:ok? true :receipt {:status :accepted :command-id (:command-id payload)
                           :cancelled? true}}
      {:ok? false
       :error {:code :SETTINGS_UNKNOWN_COMMAND
               :message "unknown settings command"
               :details {:kind kind}}}))))
