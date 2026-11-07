(ns hc.server.extensions.mcp.connection
  "Managed MCP connections and real tool calls.

  A probe is deliberately short-lived.  This namespace owns the other shape:
  one live connection per enabled server, an explicit open/close lifecycle,
  serialized `tools/call` requests and cancellation.  Authorization comes only
  from the configured session capability scope and role; there is no per-tool
  approval in the always-authorized operating mode.

  A call is reported as `:completed`, `:tool-error`, `:failed`, `:unknown`,
  `:cancelled` or `:not-started`.  `:unknown` is not a failure and never
  becomes a success: after a write-like request has been sent, a transport
  error may mean the remote side already acted.  Only a well-formed MCP result
  with `isError` false is `:completed`.

  This namespace does not decide whether a capability is enabled.  It asks the
  catalog and the session scope, then executes exactly the tool that passed
  those checks."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [hc.server.extensions.catalog :as catalog]
            [hc.server.extensions.common :as common]
            [hc.server.extensions.error :as error]
            [hc.server.extensions.mcp.json :as json]
            [hc.server.extensions.mcp.probe :as probe]
            [hc.server.extensions.mcp.state :as state]
            [hc.server.extensions.mcp.transport :as transport])
  (:import (java.util UUID)
           (java.util.concurrent TimeUnit)))

(def default-timeout-ms 10000)
(def max-timeout-ms 120000)
(def max-history 512)

(def role-capabilities
  {:viewer #{:mcp/call}
   :operator #{:mcp/call :mcp/write}
   :admin #{:mcp/call :mcp/write :mcp/destructive}})

(defn manager
  "Create an empty process-local connection manager."
  []
  (atom {:connections {}
         :calls {}
         :opened 0}))

(defn- fail
  [catalog code message details]
  {:ok? false
   :catalog catalog
   :error {:code code :message message :details details}})

(defn- connection-entry
  [manager server-id]
  (get-in @manager [:connections server-id]))

(defn live?
  [manager server-id]
  (boolean (some-> (connection-entry manager server-id) :connection)))

(defn- close-entry!
  [entry]
  (when-let [connection (:connection entry)]
    (try (transport/close! connection) (catch Throwable _ nil)))
  (when-let [closed? (:closed? entry)]
    (reset! closed? true))
  true)

(defn- apply-failure!
  [catalog server-id result]
  (state/apply-probe-result! catalog server-id result))

(defn open!
  "Open and handshake one server, keeping the connection live.

  A successful open records the connection and tool list but enables nothing.
  The operation is serialized per manager; a call on another server waits only
  for the transport handshake."
  ([manager catalog server-id] (open! manager catalog server-id {}))
  ([manager catalog server-id opts]
   (let [server (catalog/server (catalog/ensure catalog) server-id)
         timeout-ms (min max-timeout-ms
                         (max 1 (long (or (:timeout-ms opts) default-timeout-ms))))]
     (if-not server
       (fail catalog :MCP_SERVER_NOT_FOUND "server is not installed" {:id server-id})
       (let [existing (connection-entry manager server-id)]
         (if (and existing (:connection existing) (not @(:closed? existing)))
           {:ok? true :catalog catalog :server-id server-id
            :reused? true :tools (keys (get-in server [:tools]))}
           (let [connected (transport/connect! server (assoc opts :timeout-ms timeout-ms))]
             (if-not (:ok? connected)
               (let [error (:error connected)
                     result {:status (if (= :MCP_AUTH_REQUIRED (:code error))
                                       :needs-login
                                       :failed)
                             :stage :connect
                             :error error}
                     applied (apply-failure! catalog server-id result)]
                 (fail (:catalog applied)
                       (or (:code error) :MCP_TRANSPORT)
                       "server connection failed"
                       {:server-id server-id :error error}))
               (let [connection (:connection connected)
                     handshaked (try
                                  (probe/handshake! connection timeout-ms)
                                  (catch Exception t
                                    {:ok? false
                                     :stage :transport
                                     :error {:code :MCP_TRANSPORT
                                             :message (or (.getMessage ^Throwable t)
                                                          (str t))}}))]
                 (if-not (:ok? handshaked)
                   (do
                     (try (transport/close! connection) (catch Throwable _ nil))
                     (let [error (:error handshaked)
                           result {:status (if (= :MCP_AUTH_REQUIRED (:code error))
                                             :needs-login
                                             :failed)
                                   :stage (:stage handshaked)
                                   :error error}
                           applied (apply-failure! catalog server-id result)]
                       (fail (:catalog applied)
                             (or (:code error) :MCP_TRANSPORT)
                             "MCP handshake failed"
                             {:server-id server-id
                              :stage (:stage handshaked)
                              :error error})))
                   (let [applied (state/apply-probe-result!
                                  catalog server-id
                                  {:status :connected
                                   :tools (:tools handshaked)
                                   :server-info (:server-info handshaked)
                                   :protocol-version (:protocol-version handshaked)
                                   :duration-ms nil})
                         entry {:server-id server-id
                                :server (:server applied)
                                :connection connection
                                :lock (Object.)
                                :closed? (atom false)
                                :opened-at (common/now-ms)}]
                     (swap! manager (fn [m]
                                      (-> m
                                          (assoc-in [:connections server-id] entry)
                                          (update :opened inc))))
                     {:ok? true
                      :catalog (:catalog applied)
                      :server-id server-id
                      :server (:server applied)
                      :tools (:tools handshaked)
                      :reused? false})))))))))))

(defn refresh!
  "Close any live connection and open a fresh one.

  Use this for an explicit test/refresh: it forces a new handshake and tool
  list instead of reusing the previous process."
  [manager catalog server-id opts]
  (when-let [entry (connection-entry manager server-id)]
    (close-entry! entry))
  (swap! manager update :connections dissoc server-id)
  (open! manager catalog server-id opts))

(defn close!
  "Close one managed connection and mark the catalog disconnected."
  [manager catalog server-id]
  (let [entry (connection-entry manager server-id)]
    (when entry (close-entry! entry))
    (swap! manager update :connections dissoc server-id)
    (state/disconnect! catalog server-id)))

(defn shutdown!
  "Close every managed connection.  Returns the catalog after disconnects."
  [manager catalog]
  (reduce (fn [cat [server-id entry]]
            (close-entry! entry)
            (let [result (state/disconnect! cat server-id)]
              (if (:ok? result) (:catalog result) cat)))
          catalog
          (:connections @manager))
  (swap! manager assoc :connections {})
  catalog)

(defn- tool-of
  [server tool-name]
  (get (:tools server) (str tool-name)))

(defn- exclusion-reason
  [catalog scope server-id tool-name]
  (let [resolved (state/resolve-tools catalog scope)
        active (some #(and (= server-id (:server-id %))
                           (= (str tool-name) (:name %)))
                     (:tools resolved))]
    (if active
      nil
      (or (some (fn [excluded]
                  (when (and (= server-id (:server-id excluded))
                             (or (nil? (:tool excluded))
                                 (= (str tool-name) (:tool excluded))))
                    (:reason excluded)))
                (:excluded resolved))
          :not-callable))))

(defn- error-for-reason
  [reason server-id tool-name]
  (case reason
    :not-installed [:MCP_SERVER_NOT_FOUND "server is not installed"]
    :globally-disabled [:MCP_SERVER_DISABLED "server is globally disabled"]
    :needs-login [:MCP_AUTH_REQUIRED "server requires a login"]
    :connection-failed [:MCP_CONNECTION_FAILED "server connection failed"]
    :not-connected [:MCP_NOT_CONNECTED "server is not connected"]
    :tool-disabled [:MCP_TOOL_DISABLED "tool is not enabled"]
    :tool-unavailable [:MCP_TOOL_UNAVAILABLE "tool is not currently offered"]
    :no-enabled-tools [:MCP_TOOL_DISABLED "no tool is enabled"]
    [:MCP_SCOPE_DENIED "tool is not selected in this session"]))

(defn- scope-capabilities
  [scope]
  (let [explicit (:capabilities scope)
        have (if (some? explicit)
               (set explicit)
               (let [role (some-> (:role scope) name keyword)]
                 (get role-capabilities role #{})))
        limit (:capability-limit scope)]
    (if (set? limit)
      (set/intersection have limit)
      have)))

(defn- required-capabilities
  [tool]
  (cond-> #{:mcp/call}
    (not (:read-only? tool)) (conj :mcp/write)
    (:destructive? tool) (conj :mcp/destructive)))

(defn- missing-capabilities
  [scope tool]
  (let [have (scope-capabilities scope)
        need (required-capabilities tool)]
    (set (remove have need))))

(defn- check-tool!
  [catalog scope server-id tool-name]
  (let [catalog (catalog/ensure catalog)
        server (catalog/server catalog server-id)
        tool (tool-of server tool-name)]
    (cond
      (nil? server)
      {:ok? false :catalog catalog
       :error {:code :MCP_SERVER_NOT_FOUND :message "server is not installed"
               :details {:server-id server-id}}}

      (nil? tool)
      {:ok? false :catalog catalog
       :error {:code :MCP_TOOL_NOT_FOUND :message "tool is not present"
               :details {:server-id server-id :tool tool-name}}}

      :else
      (let [reason (exclusion-reason catalog scope server-id tool-name)]
        (if reason
          (let [[code message] (error-for-reason reason server-id tool-name)]
            {:ok? false :catalog catalog
             :error {:code code :message message
                     :details {:server-id server-id :tool tool-name
                               :reason reason}}})
          (let [missing (missing-capabilities scope tool)]
            (if (seq missing)
              {:ok? false :catalog catalog
               :error {:code :MCP_CAPABILITY_REQUIRED
                       :message "the session role lacks the required MCP capability"
                       :details {:server-id server-id :tool tool-name
                                 :missing (vec (sort-by str missing))}}}
              {:ok? true :catalog catalog :server server :tool tool})))))))

(defn- call-result
  [base result]
  (let [response (:result result)
        is-error? (boolean (and (map? response)
                                (or (json/field response :isError)
                                    (json/field response :is-error))))]
    (cond
      (not (map? response))
      (merge base {:ok? false :outcome :unknown
                   :error {:code :MCP_PROTOCOL
                           :message "tools/call result is not an object"}})

      is-error?
      (merge base {:ok? false :outcome :tool-error :result response})

      :else
      (merge base {:ok? true :outcome :completed :result response}))))

(defn- transport-failure
  [base tool result]
  (merge base
         {:ok? false
          :outcome (if (:read-only? tool) :failed :unknown)
          :error (:error result)}))

(defn- record-call!
  [manager call-id record]
  (swap! manager
         (fn [m]
           (let [calls (assoc-in (:calls m) [call-id] record)
                 calls (if (> (count calls) max-history)
                         (into {}
                               (take-last max-history
                                          (sort-by (fn [[_ v]] (or (:started-at v) 0))
                                                   calls)))
                         calls)]
             (assoc m :calls calls))))
  true)

(defn start-call!
  "Start one tool call and return a handle immediately.

  The returned `:future` resolves to a result map.  The call is serialized on
  the server connection.  Authorization is already decided by `check-tool!`
  from the configured session scope and role; no per-tool approval exists in
  always-authorized mode."
  ([manager catalog scope server-id tool-name arguments]
   (start-call! manager catalog scope server-id tool-name arguments {}))
  ([manager catalog scope server-id tool-name arguments opts]
   (let [checked (check-tool! catalog scope server-id tool-name)]
     (if-not (:ok? checked)
       checked
       (let [server (:server checked)
             tool (:tool checked)
             entry (connection-entry manager server-id)]
         (cond
           (nil? entry)
           (fail (:catalog checked) :MCP_NOT_CONNECTED
                 "server is not connected; open it before calling a tool"
                 {:server-id server-id})

           (not (:enabled? server))
           (fail (:catalog checked) :MCP_SERVER_DISABLED
                 "server is globally disabled" {:server-id server-id})

           :else
           (let [call-id (str (common/new-id :call))
                     started (common/now-ms)
                     sent? (atom false)
                     cancelled? (atom false)
                     timeout-ms (min max-timeout-ms
                                     (max 1 (long (or (:timeout-ms opts)
                                                      default-timeout-ms))))
                     base {:call-id call-id
                           :server-id server-id
                           :server-name (:name server)
                           :tool tool-name
                           :read-only? (boolean (:read-only? tool))
                           :destructive? (boolean (:destructive? tool))
                           :started-at started
                           :arguments (or arguments {})}
                     _ (record-call! manager call-id
                                     (merge base {:outcome :running
                                                  :ok? false
                                                  :sent? false
                                                  :duration-ms nil}))
                     future (future
                              (locking (:lock entry)
                                (if @cancelled?
                                  (let [result (merge base
                                                      {:ok? false
                                                       :outcome :not-started
                                                       :duration-ms 0})]
                                    (record-call! manager call-id result)
                                    result)
                                  (do
                                    (reset! sent? true)
                                    (let [response (transport/request!
                                                    (:connection entry)
                                                    "tools/call"
                                                    {:name (str tool-name)
                                                     :arguments (or arguments {})}
                                                    timeout-ms)
                                          result (if (:ok? response)
                                                   (call-result
                                                    base
                                                    (assoc response
                                                           :result (:result response)))
                                                   (transport-failure base tool response))
                                          result (assoc result
                                                        :duration-ms
                                                        (- (common/now-ms) started)
                                                        :sent? true)]
                                      (record-call! manager call-id result)
                                      result)))))]
                 {:ok? true
                  :catalog (:catalog checked)
                  :call-id call-id
                  :handle {:call-id call-id
                           :server-id server-id
                           :tool tool-name
                           :future future
                           :sent? sent?
                           :cancelled? cancelled?}})))))))

(defn await!
  "Wait for a started call.  A timeout reports `:running`, not failure."
  ([handle] (await! handle default-timeout-ms))
  ([handle timeout-ms]
   (let [result (deref (:future handle) (long timeout-ms) ::timeout)]
     (if (= ::timeout result)
       {:ok? false :outcome :running :handle handle
        :message "call is still running; wait again or cancel it"}
       result))))

(defn cancel!
  "Request cancellation of a running call.

  Before the request is sent this is a clean `:not-started` cancellation.  After
  the request is sent the transport is closed; the remote side may already have
  acted, so the eventual result remains `:unknown` rather than `:cancelled`."
  [manager catalog handle reason]
  (let [call-id (:call-id handle)
        entry (connection-entry manager (:server-id handle))]
    (cond
      (and (:future handle) (future-done? (:future handle)))
      {:ok? false :catalog catalog
       :error {:code :MCP_CALL_COMPLETED
               :message "call already completed"}}

      (nil? entry)
      {:ok? false :catalog catalog
       :error {:code :MCP_NOT_CONNECTED :message "connection is already closed"}}

      :else
      (do
        (reset! (:cancelled? handle) true)
        (let [sent? @(:sent? handle)]
          (when sent?
            (close-entry! entry)
            (swap! manager update :connections dissoc (:server-id handle)))
          (let [catalog (if sent?
                          (let [result (state/disconnect! catalog (:server-id handle))]
                            (if (:ok? result) (:catalog result) catalog))
                          catalog)
                outcome (if sent? :unknown :not-started)]
            {:ok? true
             :catalog catalog
             :call-id call-id
             :requested? true
             :connection-closed? (boolean sent?)
             :outcome outcome
             :note (if sent?
                     "request was sent; the remote side may already have acted"
                     "request was cancelled before it was sent")
             :reason reason}))))))

(defn call!
  "Open (if needed), start, and await one tool call."
  ([manager catalog scope server-id tool-name arguments]
   (call! manager catalog scope server-id tool-name arguments {}))
  ([manager catalog scope server-id tool-name arguments opts]
   (let [opened (if (live? manager server-id)
                  {:ok? true :catalog catalog}
                  (open! manager catalog server-id opts))]
     (if-not (:ok? opened)
       opened
       (let [started (start-call! manager (:catalog opened) scope
                                  server-id tool-name arguments opts)]
         (if-not (:ok? started)
           started
           (assoc (await! (:handle started)
                          (or (:await-ms opts) default-timeout-ms))
                  :catalog (:catalog started))))))))

(defn calls
  [manager]
  (sort-by :started-at (vals (:calls @manager))))

(defn connection-ids
  [manager]
  (keys (:connections @manager)))
