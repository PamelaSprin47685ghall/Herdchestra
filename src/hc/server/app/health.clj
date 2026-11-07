(ns hc.server.app.health
  "Process health for operators and smoke tests.

  The gateway's own `/healthz` reports feed counters.  This handler is
  mounted ahead of the gateway and reports the facts an operator needs to
  decide whether the integrated process can accept work: store state,
  session count, model configuration and uptime.  It never reports a
  component as healthy when it was not started.  The application wraps this
  handler with `hc.server.gateway.auth/wrap` and the same policy as the
  gateway.  Only GET is accepted; other methods return 405."
  (:require [clojure.string :as str]
            [hc.version :as version]))

(defn- response
  [status body]
  {:status status
   :headers {"content-type" "application/edn; charset=utf-8"
             "cache-control" "no-store"}
   :body (pr-str body)})

(defn snapshot
  [{:keys [started-at store-open? session-count gateway-stats model-configured?
           config owners browser browser-errors publish-error components
           component-errors]
    :or {started-at (System/currentTimeMillis)}}]
  (let [started-at (or started-at (System/currentTimeMillis))
        store-open? (if (fn? store-open?)
                      (try (boolean (store-open?)) (catch Throwable _ false))
                      (boolean store-open?))
        session-count (if (fn? session-count)
                        (try (session-count) (catch Throwable _ nil))
                        session-count)
        gateway-stats (if (fn? gateway-stats)
                        (try (gateway-stats) (catch Throwable _ nil))
                        gateway-stats)
        component-errors (or component-errors {})
        ok? (and store-open?
                 (some? session-count)
                 (some? gateway-stats)
                 (empty? component-errors))]
    (cond-> {:ok ok?
             :uptime-ms (- (System/currentTimeMillis) started-at)
             :version version/version
             :store {:open? store-open?
                     :path (some-> config :db-path)}
             :sessions {:count session-count}
             :gateway gateway-stats
             :models {:configured? (boolean model-configured?)}
             :components (or components {})
             :component-errors component-errors
             :config (select-keys config [:host :port :workspace-root :data-dir
                                          :log-level])}
      (some? owners) (assoc :owners owners)
      (some? browser) (assoc :browser browser)
      (some? browser-errors) (assoc :browser-errors browser-errors)
      true (assoc :publish-error publish-error))))

(defn handler
  "Ring handler for `/healthz`.  Only GET is accepted."
  [system]
  (fn [request]
    (if (= :get (:request-method request))
      (let [body (snapshot system)]
        (response (if (:ok body) 200 503) body))
      {:status 405
       :headers {"content-type" "application/edn; charset=utf-8"
                 "cache-control" "no-store"
                 "allow" "GET"}
       :body (pr-str {:error :METHOD_NOT_ALLOWED
                      :message "healthz accepts GET only"
                      :method (:request-method request)})})))
