(ns hc.server.extensions
  "Extension catalog facade: skills and MCP are independent capability families.

  A caller owns one catalog value and commits the returned value with the
  surrounding domain state.  Both families start disabled.  Neither family
  can grant the other's permission, and this namespace performs no tool call
  or skill execution."
  (:require [hc.server.extensions.catalog :as catalog]
            [hc.server.extensions.mcp :as mcp]
            [hc.server.extensions.mcp.connection :as connection]
            [hc.server.extensions.skill :as skill]))

;; Catalog

(defn empty-catalog
  []
  (catalog/empty-catalog))

(defn summary
  [catalog]
  (catalog/summary catalog))

;; Skills

(defn preview-skill
  [source]
  (skill/preview source))

(defn preview-skill-candidates
  [source]
  (skill/preview-candidates source))

(defn install-skill!
  ([catalog root candidate] (skill/install! catalog root candidate))
  ([catalog root candidate opts] (skill/install! catalog root candidate opts)))

(defn install-skill-source!
  ([catalog root source] (skill/install-source! catalog root source))
  ([catalog root source opts] (skill/install-source! catalog root source opts)))

(defn enable-skill!
  [catalog id enabled?]
  (skill/set-enabled! catalog id enabled?))

(defn bind-skill!
  [catalog id assistant-id]
  (skill/bind! catalog id assistant-id))

(defn unbind-skill!
  [catalog id assistant-id]
  (skill/unbind! catalog id assistant-id))

(defn delete-skill!
  [catalog root id]
  (skill/delete! catalog root id))

(defn copy-skill!
  [catalog root id opts]
  (skill/copy! catalog root id opts))

(defn resolve-skills
  [catalog scope]
  (skill/resolve-skills catalog scope))

(defn compare-skill-update
  [catalog id candidate]
  (skill/compare-update catalog id candidate))

(defn apply-skill-update!
  ([catalog id candidate] (skill/apply-update! catalog id candidate))
  ([catalog id candidate opts] (skill/apply-update! catalog id candidate opts)))

(defn list-skills
  [catalog]
  (skill/list-skills catalog))

(defn public-skill
  [skill]
  (skill/public-skill skill))

(defn public-resolution
  [resolved]
  (skill/public-resolution resolved))

(defn public-skill-candidate
  [candidate]
  (skill/public-candidate candidate))

(defn validate-skill-candidate
  [candidate]
  (skill/validate-candidate candidate))

(defn read-skill-file
  [catalog id path]
  (skill/read-file catalog id path))

(defn active-skill-context
  [catalog scope]
  (skill/active-context catalog scope))

;; MCP

(defn normalize-mcp-server
  [server]
  (mcp/normalize server))

(defn add-mcp-server!
  ([catalog server] (mcp/add! catalog server))
  ([catalog server opts] (mcp/add! catalog server opts)))

(defn update-mcp-server!
  ([catalog id server] (mcp/update! catalog id server))
  ([catalog id server opts] (mcp/update! catalog id server opts)))

(defn remove-mcp-server!
  [catalog id]
  (mcp/remove! catalog id))

(defn enable-mcp-server!
  [catalog id enabled?]
  (mcp/set-enabled! catalog id enabled?))

(defn probe-mcp-config!
  ([server-config] (mcp/probe-config! server-config))
  ([server-config opts] (mcp/probe-config! server-config opts)))

(defn probe-mcp-server!
  ([catalog id] (mcp/probe! catalog id))
  ([catalog id opts] (mcp/probe! catalog id opts)))

(defn set-mcp-tool-enabled!
  ([catalog id tool-name enabled?]
   (mcp/set-tool-enabled! catalog id tool-name enabled?))
  ([catalog id tool-name enabled? opts]
   (mcp/set-tool-enabled! catalog id tool-name enabled? opts)))

(defn approve-mcp-tool!
  [catalog id tool-name]
  (mcp/approve-tool! catalog id tool-name))

(defn begin-mcp-login!
  [catalog id]
  (mcp/begin-login! catalog id))

(defn complete-mcp-login!
  [catalog id token account]
  (mcp/complete-login! catalog id token account))

(defn logout-mcp!
  [catalog id]
  (mcp/logout! catalog id))

(defn disconnect-mcp-server!
  [catalog id]
  (mcp/disconnect! catalog id))

(defn resolve-mcp-tools
  [catalog scope]
  (mcp/resolve-tools catalog scope))

(defn mcp-tool-callable?
  [catalog scope server-id tool-name]
  (mcp/tool-callable? catalog scope server-id tool-name))

(defn mcp-tool-definitions
  [catalog scope]
  (mcp/tool-definitions catalog scope))

(defn create-mcp-connection-manager
  []
  (connection/manager))

(defn open-mcp-connection!
  ([manager catalog server-id] (connection/open! manager catalog server-id))
  ([manager catalog server-id opts] (connection/open! manager catalog server-id opts)))

(defn refresh-mcp-connection!
  ([manager catalog server-id] (connection/refresh! manager catalog server-id {}))
  ([manager catalog server-id opts]
   (connection/refresh! manager catalog server-id opts)))

(defn close-mcp-connection!
  [manager catalog server-id]
  (connection/close! manager catalog server-id))

(defn shutdown-mcp-connections!
  [manager catalog]
  (connection/shutdown! manager catalog))

(defn start-mcp-call!
  ([manager catalog scope server-id tool-name arguments]
   (connection/start-call! manager catalog scope server-id tool-name arguments))
  ([manager catalog scope server-id tool-name arguments opts]
   (connection/start-call! manager catalog scope server-id tool-name arguments opts)))

(defn await-mcp-call!
  ([handle] (connection/await! handle))
  ([handle timeout-ms] (connection/await! handle timeout-ms)))

(defn call-mcp-tool!
  ([manager catalog scope server-id tool-name arguments]
   (connection/call! manager catalog scope server-id tool-name arguments))
  ([manager catalog scope server-id tool-name arguments opts]
   (connection/call! manager catalog scope server-id tool-name arguments opts)))

(defn cancel-mcp-call!
  [manager catalog handle reason]
  (connection/cancel! manager catalog handle reason))

(defn mcp-call-history
  [manager]
  (connection/calls manager))

(defn mcp-server-view
  [server]
  (mcp/view server))

(defn mcp-catalog-view
  [catalog]
  (mcp/catalog-view catalog))

(defn preview-mcp-import
  [value]
  (mcp/import-preview value))

(defn import-mcp!
  ([catalog previewed] (mcp/import! catalog previewed))
  ([catalog previewed opts] (mcp/import! catalog previewed opts)))
