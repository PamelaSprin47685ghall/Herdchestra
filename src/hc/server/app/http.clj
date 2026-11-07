(ns hc.server.app.http
  "HTTP listener owned by the application shell.

  The gateway owns protocol routes and the static fallback owns bytes.  This
  namespace only binds a socket, reports the actual port (including the
  ephemeral port chosen for `:port 0`), and stops the listener without
  interrupting in-flight commits."
  (:require [org.httpkit.server :as hk]))

(def default-options
  {:max-body (* 2 1024 1024)
   :max-ws (* 1024 1024)
   :max-line 8192
   :server-header "Herdchestra"
   :legacy-return-value? false})

(defn start!
  "Start an HTTP listener for HANDLER.

  Returns `{:server s :port p :stop! f}`.  `:port` is the real bound port.
  The stop function waits up to `:timeout-ms` (default 5000) for active
  requests to finish, then returns
  `{:status :stopped|:running|:stopping :stopped? boolean}`.  Only
  `:stopped` is a successful stop."
  [{:keys [handler host port] :as options}]
  (when-not (ifn? handler)
    (throw (ex-info "HTTP start requires a Ring handler"
                    {:error/kind :http-invalid
                     :field :handler})))
  (let [default-timeout (or (:timeout-ms options) 5000)
        options (merge default-options
                       (select-keys options [:max-body :max-ws :max-line
                                             :server-header]))
        server (hk/run-server handler
                              (assoc options
                                     :ip (or host "127.0.0.1")
                                     :port (or port 0)))
        bound-port (hk/server-port server)]
    (letfn [(stop!
              ([] (stop! {}))
              ([{:keys [timeout-ms] :or {timeout-ms default-timeout}}]
               (let [result (hk/server-stop! server {:timeout timeout-ms})]
                 (when (instance? clojure.lang.IPending result)
                   (deref result timeout-ms ::timeout))
                 (let [status (hk/server-status server)]
                   {:status status
                    :stopped? (= :stopped status)}))))]
      {:server server
       :port bound-port
       :host (or host "127.0.0.1")
       :stop! stop!})))

(defn url
  "The browser-facing URL for a bound listener."
  [{:keys [host port]}]
  (let [host (if (and host (not (contains? #{"0.0.0.0" "::" ""} host)))
               host
               "127.0.0.1")]
    (str "http://" host ":" port "/")))
