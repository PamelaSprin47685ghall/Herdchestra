(ns hc.server.extensions.mcp
  "Public MCP configuration and activation surface.

  The sequence is deliberately four separate facts:

      added configuration -> tested connection -> enabled tools -> session scope

  A successful test proves only that the server answered and listed tools.  It
  does not enable a tool.  This namespace never calls `tools/call`; execution
  belongs to the execution boundary."
  (:require [hc.server.extensions.catalog :as catalog]
            [hc.server.extensions.mcp.config :as config]
            [hc.server.extensions.mcp.import :as import]
            [hc.server.extensions.mcp.probe :as probe]
            [hc.server.extensions.mcp.state :as state]))

(defn normalize
  [server]
  (config/normalize server))

(defn add!
  "Normalize and add one server.  The new server is disabled."
  ([catalog server] (add! catalog server {}))
  ([catalog server opts]
   (let [normalized (config/normalize server)]
     (if-not (:ok? normalized)
       {:ok? false :catalog (catalog/ensure catalog) :error (:error normalized)}
       (let [result (state/add-server! catalog (:server-config normalized)
                                       (update opts :source
                                               #(or % {:kind :manual})))]
         (cond-> result
           (:ok? result)
           (assoc :warnings (:warnings normalized)
                  :unknown-fields (:unknown-fields normalized)
                  :has-secrets? (:has-secrets? normalized))))))))

(defn update!
  "Normalize and update one server.  Credentials and target changes are
  reported; nothing is enabled by the update."
  ([catalog id server] (update! catalog id server {}))
  ([catalog id server opts]
   (let [normalized (config/normalize server)]
     (if-not (:ok? normalized)
       {:ok? false :catalog (catalog/ensure catalog) :error (:error normalized)}
       (let [result (state/update-server! catalog id (:server-config normalized)
                                          (merge {:source {:kind :manual}}
                                                 opts))]
         (cond-> result
           (:ok? result)
           (assoc :warnings (:warnings normalized)
                  :unknown-fields (:unknown-fields normalized)
                  :has-secrets? (:has-secrets? normalized))))))))

(defn remove!
  [catalog id]
  (state/remove-server! catalog id))

(defn set-enabled!
  [catalog id enabled?]
  (state/set-server-enabled! catalog id enabled?))

(defn probe-config!
  "Test a draft configuration without adding it to the catalog."
  ([server-config] (probe-config! server-config {}))
  ([server-config opts]
   (let [normalized (if (map? (:transport server-config))
                      {:ok? true :server-config server-config}
                      (config/normalize server-config))]
     (if-not (:ok? normalized)
       normalized
       (probe/probe! (:server-config normalized) opts)))))

(defn probe!
  "Test one installed server and record the result.  Tools remain disabled."
  ([catalog id] (probe/probe-and-apply! catalog id {}))
  ([catalog id opts] (probe/probe-and-apply! catalog id opts)))

(defn set-tool-enabled!
  ([catalog id tool-name enabled?]
   (state/set-tool-enabled! catalog id tool-name enabled?))
  ([catalog id tool-name enabled? opts]
   (state/set-tool-enabled! catalog id tool-name enabled? opts)))

(defn approve-tool!
  [catalog id tool-name]
  (state/approve-tool! catalog id tool-name))

(defn begin-login!
  [catalog id]
  (state/begin-login! catalog id))

(defn complete-login!
  [catalog id token account]
  (state/complete-login! catalog id token account))

(defn logout!
  [catalog id]
  (state/logout! catalog id))

(defn disconnect!
  [catalog id]
  (state/disconnect! catalog id))

(defn resolve-tools
  [catalog scope]
  (state/resolve-tools catalog scope))

(defn tool-callable?
  [catalog scope server-id tool-name]
  (state/tool-callable? catalog scope server-id tool-name))

(defn tool-definitions
  [catalog scope]
  (state/tool-definitions catalog scope))

(defn view
  [server]
  (state/server-view server))

(defn catalog-view
  [catalog]
  (state/catalog-view catalog))

(defn import-preview
  "Parse and normalize an import without changing the catalog."
  [value]
  (import/preview value))

(defn import!
  "Apply an import preview.  New servers are disabled; duplicates default to a
  new name rather than overwriting an existing connection."
  ([catalog previewed] (import! catalog previewed {}))
  ([catalog previewed opts]
   (import/import! catalog previewed opts)))
