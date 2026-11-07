(ns hc.server.extensions.mcp.state
  "Catalog transitions for MCP servers and their tools.

  Connection is a short-lived observation, not a permission.  A successful
  probe only records that the server answered and which tools it listed.  Every
  newly discovered tool starts disabled and pending review.  Existing tool
  selections survive a refresh, while a removed tool becomes unavailable and
  never appears in a session's callable list.

  A global server disable is final for every session.  Session scope can only
  narrow the set further."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [hc.server.extensions.catalog :as catalog]
            [hc.server.extensions.common :as common]
            [hc.server.extensions.error :as error]
            [hc.server.extensions.mcp.config :as config]
            [hc.server.extensions.mcp.json :as json]))

(defn- fail
  [catalog code message details]
  {:ok? false
   :catalog catalog
   :error {:code code :message message :details details}})

(defn- canonical
  [value]
  (cond
    (map? value) (into (sorted-map)
                       (map (fn [[k v]] [k (canonical v)]))
                       value)
    (vector? value) (mapv canonical value)
    (sequential? value) (mapv canonical value)
    :else value))

(defn- tool-digest
  [description schema annotations]
  (common/sha256 (common/utf8-bytes
                  (json/write-str (canonical {:description (or description "")
                                              :schema (or schema {})
                                              :annotations (or annotations {})})))))

(defn- field
  [m k]
  (or (get m (name k)) (get m k)))

(defn- normalize-tool
  [raw]
  (let [raw (if (map? raw) raw {})
        name (field raw :name)
        description (field raw :description)
        schema (or (field raw :inputSchema) (field raw :input-schema) {})
        annotations (or (field raw :annotations) {})]
    (when-not (and (string? name) (not (str/blank? name)))
      (error/fail! :MCP_TOOL_INVALID "tool name must be a non-blank string"
                   {:tool raw}))
    (when-not (or (nil? description) (string? description))
      (error/fail! :MCP_TOOL_INVALID "tool description must be a string"
                   {:tool name}))
    (when-not (map? schema)
      (error/fail! :MCP_TOOL_INVALID "tool input schema must be an object"
                   {:tool name}))
    (when-not (map? annotations)
      (error/fail! :MCP_TOOL_INVALID "tool annotations must be an object"
                   {:tool name}))
    {:name name
     :description (or description "")
     :input-schema schema
     :annotations annotations
     :read-only? (boolean (field annotations :readOnlyHint))
     :destructive? (boolean (field annotations :destructiveHint))
     :idempotent? (boolean (field annotations :idempotentHint))
     :open-world? (boolean (field annotations :openWorldHint))
     :description-digest (tool-digest description schema annotations)}))

(defn- merge-tools
  [existing listed now]
  (let [listed (map normalize-tool listed)
        listed-names (set (map :name listed))
        fresh (reduce
               (fn [tools {:keys [name description input-schema annotations
                                  read-only? destructive? idempotent? open-world?
                                  description-digest]}]
                 (let [old (get existing name)
                       changed? (and old
                                     (not= description-digest
                                           (:description-digest old)))]
                   (assoc tools name
                          (cond-> {:name name
                                   :description description
                                   :input-schema input-schema
                                   :annotations annotations
                                   :read-only? read-only?
                                   :destructive? destructive?
                                   :idempotent? idempotent?
                                   :open-world? open-world?
                                   :description-digest description-digest
                                   :available? true
                                   :removed? false
                                   :last-seen-at now
                                   :description-changed? (boolean changed?)}
                            old (assoc :enabled? (boolean (:enabled? old))
                                       :review-required?
                                       (boolean (or (:review-required? old)
                                                    changed?))
                                       :new? false)
                            (nil? old) (assoc :enabled? false
                                              :review-required? true
                                              :new? true)))))
               {}
               listed)
        retained (reduce-kv
                  (fn [tools name tool]
                    (if (contains? listed-names name)
                      tools
                      (assoc tools name
                             (assoc tool
                                    :available? false
                                    :removed? true))))
                  fresh
                  existing)]
    retained))

(defn new-server
  "Build a disabled server record from a normalized configuration."
  ([server-config] (new-server server-config {}))
  ([server-config opts]
   {:id (or (:id opts) (common/new-id :mcp))
    :name (:name server-config)
    :transport (:transport server-config)
    :enabled? false
    :connection {:status :unconnected
                 :checked-at nil
                 :duration-ms nil
                 :server-info nil
                 :protocol-version nil
                 :error nil}
    :auth {:state :none}
    :tools {}
    :source (or (:source opts) {:kind :manual})
    :created-at (common/now-ms)
    :updated-at (common/now-ms)}))

(defn add-server!
  "Add a disabled server.  The name may be repeated; the ID is the identity."
  ([catalog server-config] (add-server! catalog server-config {}))
  ([catalog server-config opts]
   (let [catalog (catalog/ensure catalog)
         id (or (:id opts) (common/new-id :mcp))]
     (if (catalog/server catalog id)
       (fail catalog :MCP_SERVER_ID_TAKEN "server id is already present"
             {:id id})
       (let [server (new-server server-config (assoc opts :id id))]
         {:ok? true
          :catalog (catalog/put-server catalog server)
          :server server
          :name-conflict? (boolean (catalog/server-by-name catalog (:name server)))})))))

(defn- changed-fields
  [old new]
  (->> (set/union (set (keys old)) (set (keys new)))
       (filter #(not= (get old %) (get new %)))
       (sort)
       vec))

(defn update-server!
  "Replace the configuration of an existing server.

  A changed target clears the old tool list and authentication.  A credential
  rotation keeps the tool selection but resets the connection so the server
  must answer again.  The enable flag is preserved; nothing is enabled by the
  update itself."
  [catalog id server-config opts]
  (let [catalog (catalog/ensure catalog)
        old (catalog/server catalog id)]
    (if-not old
      (fail catalog :MCP_SERVER_NOT_FOUND "server is not installed" {:id id})
      (let [old-transport (:transport old)
            new-transport (:transport server-config)
            identity-changed? (not= (config/identity {:transport old-transport})
                                    (config/identity {:transport new-transport}))
            credentials-changed? (or (not= (:headers old-transport)
                                           (:headers new-transport))
                                     (not= (:env old-transport)
                                           (:env new-transport)))
            now (common/now-ms)
            updated (cond-> (assoc old
                                   :name (:name server-config)
                                   :transport new-transport
                                   :connection {:status :unconnected
                                                :checked-at nil
                                                :duration-ms nil
                                                :server-info nil
                                                :protocol-version nil
                                                :error nil}
                                   :updated-at now)
                      identity-changed? (assoc :tools {})
                      (or identity-changed? credentials-changed?)
                      (assoc :auth {:state :none})
                      (:source opts) (assoc :source (:source opts)))]
        {:ok? true
         :catalog (catalog/put-server catalog updated)
         :server updated
         :diff {:identity-changed? identity-changed?
                :credentials-changed? credentials-changed?
                :changed-fields (changed-fields old-transport new-transport)}}))))

(defn remove-server!
  [catalog id]
  (let [catalog (catalog/ensure catalog)]
    (if-not (catalog/server catalog id)
      (fail catalog :MCP_SERVER_NOT_FOUND "server is not installed" {:id id})
      {:ok? true
       :catalog (catalog/remove-server catalog id)})))

(defn set-server-enabled!
  [catalog id enabled?]
  (let [catalog (catalog/ensure catalog)]
    (if-let [server (catalog/server catalog id)]
      (let [updated (assoc server :enabled? (boolean enabled?))]
        {:ok? true
         :catalog (catalog/put-server catalog updated)
         :server updated})
      (fail catalog :MCP_SERVER_NOT_FOUND "server is not installed" {:id id}))))

(defn- connection-status
  [server]
  (get-in server [:connection :status] :unconnected))

(defn- auth-state
  [server]
  (get-in server [:auth :state] :none))

(defn- live-connection?
  [server]
  (and (= :connected (connection-status server))
       (not (#{:pending-login :needs-login} (auth-state server)))))

(defn- enabled-tools
  [server]
  (filter #(and (:enabled? %) (:available? %)) (vals (:tools server))))

(defn server-state
  "The five user-visible states from the specification."
  [server]
  (cond
    (nil? server) :unconnected
    (#{:pending-login :needs-login} (auth-state server)) :needs-login
    (= :needs-login (connection-status server)) :needs-login
    (= :connection-failed (connection-status server)) :connection-failed
    (not (live-connection? server)) :unconnected
    (or (not (:enabled? server)) (empty? (enabled-tools server))) :connected-not-enabled
    :else :available))

(defn apply-probe-result!
  "Record the result of one probe.  Tools are merged; no tool is enabled by a
  successful connection."
  [catalog id result]
  (let [catalog (catalog/ensure catalog)
        server (catalog/server catalog id)
        now (common/now-ms)]
    (if-not server
      (fail catalog :MCP_SERVER_NOT_FOUND "server is not installed" {:id id})
      (let [status (:status result)
            connection {:status (if (= :failed status)
                                  :connection-failed
                                  status)
                        :checked-at now
                        :duration-ms (:duration-ms result)
                        :server-info (:server-info result)
                        :protocol-version (:protocol-version result)
                        :error (:error result)}
            updated (case status
                      :connected
                      (assoc server
                             :connection connection
                             :tools (merge-tools (:tools server)
                                                 (or (:tools result) [])
                                                 now)
                             :updated-at now)
                      :needs-login
                      (assoc server
                             :connection connection
                             :auth (if (#{:pending-login} (auth-state server))
                                     (:auth server)
                                     {:state :needs-login})
                             :updated-at now)
                      :failed
                      (assoc server :connection connection :updated-at now)
                      (assoc server :connection connection :updated-at now))]
        {:ok? true
         :catalog (catalog/put-server catalog updated)
         :server updated
         :state (server-state updated)}))))

(defn- find-tool
  [server tool-name]
  (when-let [server server]
    (get (:tools server) (str tool-name))))

(defn approve-tool!
  "Clear the review flag without enabling the tool."
  [catalog id tool-name]
  (let [catalog (catalog/ensure catalog)
        server (catalog/server catalog id)
        tool (find-tool server tool-name)]
    (cond
      (nil? server) (fail catalog :MCP_SERVER_NOT_FOUND "server is not installed" {:id id})
      (nil? tool) (fail catalog :MCP_TOOL_NOT_FOUND "tool is not present"
                        {:server-id id :tool tool-name})
      :else
      (let [updated (assoc-in server [:tools (:name tool) :review-required?] false)]
        {:ok? true
         :catalog (catalog/put-server catalog updated)
         :server updated}))))

(defn set-tool-enabled!
  "Enable or disable one tool.

  Enabling an unavailable tool is refused.  A newly discovered or materially
  changed tool requires an explicit review first; disabling is always allowed."
  ([catalog id tool-name enabled?]
   (set-tool-enabled! catalog id tool-name enabled? {}))
  ([catalog id tool-name enabled? opts]
   (let [catalog (catalog/ensure catalog)
         server (catalog/server catalog id)
         tool (find-tool server tool-name)]
     (cond
       (nil? server) (fail catalog :MCP_SERVER_NOT_FOUND "server is not installed" {:id id})
       (nil? tool) (fail catalog :MCP_TOOL_NOT_FOUND "tool is not present"
                         {:server-id id :tool tool-name})
       (and enabled? (not (:available? tool)))
       (fail catalog :MCP_TOOL_UNAVAILABLE
             "tool is not currently offered by the server"
             {:server-id id :tool (:name tool)})
       (and enabled? (:review-required? tool) (not (:approve-review? opts)))
       (fail catalog :MCP_TOOL_REVIEW_REQUIRED
             "tool must be reviewed before it can be enabled"
             {:server-id id :tool (:name tool)})
       :else
       (let [updated (assoc-in server [:tools (:name tool)]
                               (cond-> (assoc tool :enabled? (boolean enabled?))
                                 (and enabled? (:approve-review? opts))
                                 (assoc :review-required? false)))]
         {:ok? true
          :catalog (catalog/put-server catalog updated)
          :server updated})))))

(defn begin-login!
  "Start an OAuth flow for one server.  The returned token must accompany the
  callback; a callback for another server or an older attempt is refused."
  [catalog id]
  (let [catalog (catalog/ensure catalog)
        server (catalog/server catalog id)]
    (if-not server
      (fail catalog :MCP_SERVER_NOT_FOUND "server is not installed" {:id id})
      (let [token (str (common/new-id :oauth))
            updated (assoc server :auth {:state :pending-login
                                         :token token
                                         :server-id id
                                         :started-at (common/now-ms)})]
        {:ok? true
         :catalog (catalog/put-server catalog updated)
         :token token
         :server updated}))))

(defn complete-login!
  [catalog id token account]
  (let [catalog (catalog/ensure catalog)
        server (catalog/server catalog id)]
    (cond
      (nil? server)
      (fail catalog :MCP_SERVER_NOT_FOUND "server is not installed" {:id id})

      (not= :pending-login (auth-state server))
      (fail catalog :MCP_LOGIN_NOT_PENDING
            "no login is pending for this server" {:id id})

      (or (not= token (get-in server [:auth :token]))
          (not= id (get-in server [:auth :server-id])))
      (fail catalog :MCP_OAUTH_CALLBACK_MISMATCH
            "callback does not belong to this login attempt" {:id id})

      :else
      (let [updated (assoc server
                           :auth {:state :authenticated
                                  :account account
                                  :completed-at (common/now-ms)}
                           :connection {:status :unconnected
                                        :checked-at nil
                                        :duration-ms nil
                                        :server-info nil
                                        :protocol-version nil
                                        :error nil})]
        {:ok? true
         :catalog (catalog/put-server catalog updated)
         :server updated}))))

(defn logout!
  "Forget locally stored credentials for one server.

  Secret-looking header and environment entries are removed; non-secret
  entries remain.  Tool selections are retained, but the connection must be
  tested again."
  [catalog id]
  (let [catalog (catalog/ensure catalog)
        server (catalog/server catalog id)]
    (if-not server
      (fail catalog :MCP_SERVER_NOT_FOUND "server is not installed" {:id id})
      (let [transport (:transport server)
            without-secrets (fn [m]
                              (into {}
                                    (remove (fn [[k _]] (common/secret-key? k)))
                                    (or m {})))
            updated (assoc server
                           :transport (cond-> transport
                                        (:headers transport)
                                        (assoc :headers (without-secrets (:headers transport)))
                                        (:env transport)
                                        (assoc :env (without-secrets (:env transport))))
                           :auth {:state :none}
                           :connection {:status :unconnected
                                        :checked-at nil
                                        :duration-ms nil
                                        :server-info nil
                                        :protocol-version nil
                                        :error nil})]
        {:ok? true
         :catalog (catalog/put-server catalog updated)
         :server updated}))))

(defn disconnect!
  "Mark a server unconnected without discarding its tool selections."
  [catalog id]
  (let [catalog (catalog/ensure catalog)
        server (catalog/server catalog id)]
    (if-not server
      (fail catalog :MCP_SERVER_NOT_FOUND "server is not installed" {:id id})
      (let [updated (assoc server
                           :connection {:status :unconnected
                                        :checked-at nil
                                        :duration-ms nil
                                        :server-info nil
                                        :protocol-version nil
                                        :error nil})]
        {:ok? true
         :catalog (catalog/put-server catalog updated)
         :server updated}))))


(defn reset-connections
  "Clear live connection observations after a process restart.

  No transport survives the process, so a persisted `:connected` state would
  be a lie.  Tool selections and authentication state remain; only the
  connection observation is reset to `:unconnected`."
  [catalog]
  (update-in (catalog/ensure catalog) [:mcp :servers]
             (fn [servers]
               (into {}
                     (map (fn [[id server]]
                            [id (assoc server
                                       :connection {:status :unconnected
                                                    :checked-at nil
                                                    :duration-ms nil
                                                    :server-info nil
                                                    :protocol-version nil
                                                    :error nil})]))
                     (or servers {})))))

(defn- tool-public
  [tool]
  (select-keys tool
               [:name :description :input-schema :annotations :read-only?
                :destructive? :idempotent? :open-world? :enabled? :available?
                :removed? :review-required? :description-changed? :new?
                :last-seen-at]))

(defn server-view
  "A settings-page view.  Credentials are masked by `config/public-view`."
  [server]
  (let [public (config/public-view server)]
    (assoc public
           :state (server-state server)
           :connection (select-keys (:connection server)
                                    [:status :checked-at :duration-ms
                                     :server-info :protocol-version :error])
           :auth (select-keys (:auth server) [:state :account :started-at :completed-at])
           :tools (->> (vals (:tools server))
                       (sort-by :name)
                       (mapv tool-public))
           :enabled-tool-count (count (enabled-tools server)))))

(defn catalog-view
  [catalog]
  (->> (vals (get-in (catalog/ensure catalog) [:mcp :servers]))
       (sort-by :name)
       (mapv server-view)))

(defn resolve-tools
  "Return the tools callable in one session scope.

  `scope` is `{:assistant-servers #{id} :session {:mode :follow|:custom
  :servers #{id}}}`.  Session custom narrows or replaces the assistant list;
  it cannot override a global server disable, a failed connection or a
  disabled tool."
  [catalog scope]
  (let [catalog (catalog/ensure catalog)
        session (or (:session scope) {})
        mode (or (:mode session) :follow)
        selected (if (= :custom mode)
                   (set (or (:servers session) #{}))
                   (set (or (:assistant-servers scope) #{})))]
    (reduce
     (fn [acc id]
       (let [server (catalog/server catalog id)]
         (cond
           (nil? server)
           (update acc :excluded conj {:server-id id :reason :not-installed})

           (not (:enabled? server))
           (update acc :excluded conj {:server-id id :name (:name server)
                                       :reason :globally-disabled})

           (#{:needs-login} (server-state server))
           (update acc :excluded conj {:server-id id :name (:name server)
                                       :reason :needs-login})

           (#{:connection-failed} (server-state server))
           (update acc :excluded conj {:server-id id :name (:name server)
                                       :reason :connection-failed})

           (not (live-connection? server))
           (update acc :excluded conj {:server-id id :name (:name server)
                                       :reason :not-connected})

           :else
           (let [tools (sort-by :name (vals (:tools server)))
                 active (filter #(and (:enabled? %) (:available? %)) tools)
                 excluded (remove #(and (:enabled? %) (:available? %)) tools)
                 acc (reduce (fn [acc tool]
                               (update acc :excluded conj
                                       {:server-id id
                                        :name (:name server)
                                        :tool (:name tool)
                                        :reason (cond
                                                  (not (:available? tool)) :tool-unavailable
                                                  (not (:enabled? tool)) :tool-disabled
                                                  :else :not-active)}))
                             acc excluded)
                 acc (if (seq active)
                       (update acc :tools into
                               (map (fn [tool]
                                      {:server-id id
                                       :server-name (:name server)
                                       :name (:name tool)
                                       :description (:description tool)
                                       :input-schema (:input-schema tool)
                                       :annotations (:annotations tool)
                                       :read-only? (:read-only? tool)
                                       :destructive? (:destructive? tool)}))
                               active)
                       (update acc :excluded conj
                               {:server-id id :name (:name server)
                                :reason :no-enabled-tools}))]
             acc))))
     {:tools [] :excluded []}
     (sort selected))))

(defn tool-callable?
  [catalog scope server-id tool-name]
  (boolean
   (some #(and (= server-id (:server-id %))
               (= tool-name (:name %)))
         (:tools (resolve-tools catalog scope)))))

(defn tool-definitions
  "Model-facing tool definitions for one session scope.

  Names are server-qualified so two servers may offer the same tool name.
  Execution must map the qualified name back through `:metadata`."
  [catalog scope]
  (mapv (fn [{:keys [server-id server-name name description input-schema]}]
          {:name (str server-id "/" name)
           :description description
           :parameters input-schema
           :metadata {:server-id server-id
                      :server-name server-name
                      :tool name}})
        (:tools (resolve-tools catalog scope))))
