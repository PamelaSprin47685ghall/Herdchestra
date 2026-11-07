(ns hc.server.extensions.mcp.probe
  "One short-lived MCP handshake.

  The probe opens a connection, sends `initialize`, sends the initialized
  notification, and reads `tools/list` pages.  It deliberately never sends
  `tools/call`: a listed tool is a declaration, not an execution permit.  The
  connection is closed before the result is returned, so no child process or
  HTTP stream becomes a hidden background owner."
  (:require [clojure.string :as str]
            [hc.server.extensions.common :as common]
            [hc.server.extensions.mcp.state :as state]
            [hc.server.extensions.mcp.transport :as transport]
            [hc.version :as version]))

(def default-timeout-ms 5000)
(def max-timeout-ms 60000)
(def protocol-version "2024-11-05")
(def max-tool-pages 100)

(defn- field
  [m k]
  (or (get m (name k)) (get m k)))

(defn- auth-error?
  [error]
  (= :MCP_AUTH_REQUIRED (:code error)))

(defn- failure
  [stage error duration-ms]
  {:status :failed
   :stage stage
   :error error
   :duration-ms duration-ms})

(defn- needs-login
  [stage error duration-ms]
  {:status :needs-login
   :stage stage
   :error error
   :duration-ms duration-ms})

(defn- list-tools!
  [connection timeout-ms]
  (loop [cursor nil
         tools []
         pages 0]
    (if (>= pages max-tool-pages)
      {:ok? false
       :error {:code :MCP_TOOL_PAGE_LIMIT
               :message "server returned too many tool pages"
               :details {:limit max-tool-pages}}}
      (let [result (transport/request! connection "tools/list"
                                       (if cursor {"cursor" cursor} {})
                                       timeout-ms)]
        (if-not (:ok? result)
          result
          (let [payload (:result result)
                page (field payload :tools)
                next (field payload :nextCursor)]
            (cond
              (not (vector? page))
              {:ok? false
               :error {:code :MCP_PROTOCOL
                       :message "tools/list result has no tools array"
                       :details {:result payload}}}

              (not (every? (fn [tool]
                             (and (map? tool)
                                  (string? (field tool :name))
                                  (not (str/blank? (field tool :name)))
                                  (or (nil? (field tool :description))
                                      (string? (field tool :description)))
                                  (or (nil? (field tool :inputSchema))
                                      (map? (field tool :inputSchema)))))
                           page))
              {:ok? false
               :error {:code :MCP_PROTOCOL
                       :message "tools/list contains a malformed tool"
                       :details {:tools page}}}

              (and next (not (string? next)))
              {:ok? false
               :error {:code :MCP_PROTOCOL
                       :message "tools/list cursor is not a string"
                       :details {:cursor next}}}

              :else
              (if next
                (recur next (into tools page) (inc pages))
                {:ok? true :tools (into tools page)}))))))))

(defn- initialize!
  [connection timeout-ms]
  (let [result (transport/request!
                connection "initialize"
                {"protocolVersion" protocol-version
                 "capabilities" {}
                 "clientInfo" {"name" "herdchestra" "version" version/version}}
                timeout-ms)]
    (if-not (:ok? result)
      result
      (let [initialized (transport/notify! connection "notifications/initialized" {})]
        (if-not (:ok? initialized)
          initialized
          {:ok? true
           :result (:result result)
           :protocol-version (field (:result result) :protocolVersion)
           :server-info (field (:result result) :serverInfo)})))))

(defn handshake!
  "Initialize an already-connected transport and read its tool list.

  Returns `{:ok? true :tools ... :server-info ... :protocol-version ...}` or
  `{:ok? false :stage :initialize|:tools/list :error {...}}`.  The caller owns
  the connection and must close it."
  [connection timeout-ms]
  (let [initialized (initialize! connection timeout-ms)]
    (if-not (:ok? initialized)
      {:ok? false :stage :initialize :error (:error initialized)}
      (let [listed (list-tools! connection timeout-ms)]
        (if-not (:ok? listed)
          {:ok? false :stage :tools/list :error (:error listed)}
          {:ok? true
           :tools (:tools listed)
           :server-info (:server-info initialized)
           :protocol-version (:protocol-version initialized)})))))

(defn probe!
  "Open, initialize, list tools, and close one server connection.

  `opts` supports `:timeout-ms`, `:connect-fn` (a test seam with the same
  contract as `transport/connect!`) and `:transport-fn` (unused by production
  callers).  The returned map is suitable for `state/apply-probe-result!`."
  [server opts]
  (let [started (System/nanoTime)
        timeout-ms (min max-timeout-ms
                        (max 1 (long (or (:timeout-ms opts) default-timeout-ms))))
        opts (assoc opts :timeout-ms timeout-ms)
        connected ((or (:connect-fn opts) transport/connect!) server opts)]
    (if-not (:ok? connected)
      (let [duration-ms (quot (- (System/nanoTime) started) 1000000)
            error (:error connected)]
        (if (auth-error? error)
          (needs-login :connect error duration-ms)
          (failure :connect error duration-ms)))
      (let [connection (:connection connected)]
        (try
          (let [handshaked (handshake! connection timeout-ms)]
            (if-not (:ok? handshaked)
              (let [duration-ms (quot (- (System/nanoTime) started) 1000000)
                    error (:error handshaked)
                    stage (:stage handshaked)]
                (if (auth-error? error)
                  (needs-login stage error duration-ms)
                  (failure stage error duration-ms)))
              {:status :connected
               :tools (:tools handshaked)
               :server-info (:server-info handshaked)
               :protocol-version (:protocol-version handshaked)
               :duration-ms (quot (- (System/nanoTime) started) 1000000)}))
          (catch Exception t
            (failure :transport
                     {:code :MCP_TRANSPORT
                      :message (or (.getMessage ^Throwable t) (str t))}
                     (quot (- (System/nanoTime) started) 1000000)))
          (finally
            (transport/close! connection)))))))

(defn probe-and-apply!
  "Probe one installed server and record the observation.

  A successful probe does not enable the server or any tool; it only records
  the connection and merges the tool list with new tools disabled."
  ([catalog id] (probe-and-apply! catalog id {}))
  ([catalog id opts]
   (let [server (get-in catalog [:mcp :servers id])]
     (if-not server
       {:ok? false
        :catalog catalog
        :error {:code :MCP_SERVER_NOT_FOUND
                :message "server is not installed"
                :details {:id id}}}
       (let [result (probe! server opts)
             applied (state/apply-probe-result! catalog id result)]
         (if (:ok? applied)
           (assoc applied :probe result)
           applied))))))
