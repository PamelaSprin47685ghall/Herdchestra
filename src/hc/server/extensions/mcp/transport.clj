(ns hc.server.extensions.mcp.transport
  "Dispatch one MCP connection shape to its transport."
  (:require [hc.server.extensions.mcp.http :as http]
            [hc.server.extensions.mcp.stdio :as stdio]))

(defn connect!
  [server opts]
  (case (get-in server [:transport :type])
    :stdio (stdio/connect! server opts)
    :http (http/connect! server opts)
    :sse (http/connect! server opts)
    {:ok? false
     :error {:code :MCP_TRANSPORT_INVALID
             :message "unsupported MCP transport"
             :details {:transport (get-in server [:transport :type])}}}))

(defn request!
  [connection method params timeout-ms]
  (case (:kind connection)
    :stdio (stdio/request! connection method params timeout-ms)
    (:http :sse) (http/request! connection method params timeout-ms)
    {:ok? false
     :error {:code :MCP_TRANSPORT_INVALID
             :message "unsupported MCP connection"}}))

(defn notify!
  [connection method params]
  (case (:kind connection)
    :stdio (stdio/notify! connection method params)
    (:http :sse) (http/notify! connection method params)
    {:ok? false
     :error {:code :MCP_TRANSPORT_INVALID
             :message "unsupported MCP connection"}}))

(defn close!
  [connection]
  (case (:kind connection)
    :stdio (stdio/close! connection)
    (:http :sse) (http/close! connection)
    {:ok? true}))
