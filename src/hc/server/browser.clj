(ns hc.server.browser
  "Public facade for the managed browser capability.

  Authority: root `AGENTS.md` §7.4 旅程 6.4（开浏览器）, §6.4 and E2E 14.

  Composition:

      (def port (browser/connect! {:endpoint \"http://127.0.0.1:9222\"}))
      (def svc  (browser/service {:port port
                                  :screenshot-store store-fn
                                  :publish! owner-sink}))
      (def exec (browser/executor svc))

  `connect!` attaches to an operator-owned Chrome and never stops it.
  `launch!` starts an isolated headless Chrome for tests and smoke checks.
  The service is the state authority; the port is the only browser effect."
  (:require [clojure.string :as str]
            [hc.server.browser.cdp :as cdp]
            [hc.server.browser.executor :as executor]
            [hc.server.browser.model :as model]
            [hc.server.browser.port :as port]
            [hc.server.browser.service :as service])
  (:import [java.io ByteArrayInputStream]))

(defn service
  "Build one managed-browser service.  See `hc.server.browser.service/service`."
  [opts]
  (service/service opts))

(defn- offline-failure
  "A structured refusal for every page effect when no CDP endpoint is
  configured.  Permission and snapshot stay usable so the workbench can show
  the address bar and the enable control instead of hiding the whole pane."
  []
  {:status :failed
   :error {:code :browser-not-configured
           :message "受管浏览器未配置 CDP 端点；页面操作不可用。"}})

(defn offline-port
  "A BrowserPort that has no Chrome behind it.

  Only the permission/snapshot surface is meaningful without a CDP endpoint.
  Every page effect returns a structured refusal; nothing pretends a tab was
  opened or a click happened."
  []
  (reify port/BrowserPort
    (open-tab! [_ _] (offline-failure))
    (close-tab! [_ _] (offline-failure))
    (navigate! [_ _ _] (offline-failure))
    (history! [_ _ _] (offline-failure))
    (page-info! [_ _] (offline-failure))
    (observe! [_ _ _] (offline-failure))
    (inspect! [_ _ _] (offline-failure))
    (act! [_ _ _] (offline-failure))
    (screenshot! [_ _] (offline-failure))
    (tab-status [_ _] {:status :closed})
    (subscribe-operator-input! [_ _]
      {:ok? false
       :error {:code :operator-input-unavailable
               :message "受管浏览器未配置 CDP 端点。"}})
    (close! [_] nil)))

(defn offline-service
  "Build the permission-only managed-browser service used when no CDP endpoint
  is configured.  The workbench still renders the address bar and the enable
  control; page effects are refused with `:browser-not-configured`."
  [opts]
  (assoc (service (assoc opts
                         :port (offline-port)
                         :offline? true))
         :offline? true))

(defn executor
  "Build the Owner executor for SVC."
  [svc]
  (executor/executor svc))

(defn tool-specs
  "JSON-schema tool definitions for the model request snapshot."
  []
  (executor/tool-specs))

(defn browser-request?
  "True when a tool-call payload belongs to the managed browser."
  [request]
  (executor/browser-request? request))

(defn routing-executor
  "Route browser tool calls to BROWSER-EXEC and others to FALLBACK."
  [browser-exec fallback-exec]
  (executor/routing-executor browser-exec fallback-exec))

(defn launch!
  "Launch an isolated headless Chrome.  The adapter owns and stops it."
  [opts]
  (cdp/launch! opts))

(defn connect!
  "Connect to an already-running Chrome DevTools endpoint."
  [opts]
  (cdp/connect! opts))

(defn endpoint
  "Resolve the CDP endpoint from an application config map or
  `HC_BROWSER_ENDPOINT`.  Returns nil when the browser is not configured."
  [config]
  (let [configured (get-in config [:browser :endpoint])
        configured (when (and configured (not (str/blank? (str configured))))
                     (str configured))]
    (or configured
        (not-empty (System/getenv "HC_BROWSER_ENDPOINT")))))

(defn connect-config!
  "Connect using an application config map.  Returns nil when no endpoint is
  configured; connection failures still throw `:chrome-connect-failed`."
  [config]
  (when-let [endpoint (endpoint config)]
    (connect! {:endpoint endpoint})))

(defn permission!
  [svc request]
  (service/permission! svc request))

(defn approve-private-read!
  [svc]
  (service/approve-private-read! svc))

(defn set-model-provider!
  [svc provider]
  (service/set-model-provider! svc provider))

(defn open-tab!
  [svc request]
  (service/open-tab! svc request))

(defn navigate!
  [svc request]
  (service/navigate! svc request))

(defn observe!
  [svc request]
  (service/observe! svc request))

(defn act!
  [svc request]
  (service/act! svc request))

(defn takeover!
  [svc request]
  (service/takeover! svc request))

(defn operator-input!
  [svc request]
  (service/operator-input! svc request))

(defn resume!
  [svc request]
  (service/resume! svc request))

(defn close-tab!
  [svc request]
  (service/close-tab! svc request))

(defn stop!
  [svc]
  (service/stop! svc))

(defn shutdown!
  [svc]
  (service/shutdown! svc))

(defn snapshot
  [svc]
  (service/snapshot svc))

(defn cards
  [svc]
  (service/cards svc))

(defn report
  [svc]
  (service/report svc))

(defn report-text
  [svc]
  (service/report-text svc))

(defn- command-error
  [code message details]
  (cond-> {:ok? false
           :error {:code code :message message}}
    details (assoc-in [:error :details] details)))

(defn command!
  "Handle one workbench `:browser/*` command payload.

  This is the app-facing adapter.  It preserves the concrete action for
  `:browser/act`; the previous router overwrote it with `:act`, which made
  every act command fail.  It returns the router shape
  `{:ok? true :receipt {...}}` or `{:ok? false :error {...}}`.

  `:browser/permission`, `:browser/takeover`, `:browser/resume` and
  `:browser/operator-input` are accepted here because this adapter is for the
  workbench, not for the model.  The model executor keeps its separate
  operator-only refusal."
  [svc payload]
  (let [kind (:kind payload)
        accepted (fn [value] {:ok? true :receipt {:status :accepted :result value}})]
    (try
      (case kind
        :browser/permission
        {:ok? true :receipt {:status :accepted
                             :state (service/permission! svc payload)}}

        :browser/snapshot
        {:ok? true :receipt {:status :accepted
                             :snapshot (service/snapshot svc)}}

        :browser/cards
        {:ok? true :receipt {:status :accepted
                             :cards (service/cards svc)}}

        :browser/open-tab
        (accepted (service/open-tab! svc payload))

        :browser/navigate
        (accepted (service/navigate! svc payload))

        :browser/back
        (accepted (service/history! svc (assoc payload :direction :back)))

        :browser/forward
        (accepted (service/history! svc (assoc payload :direction :forward)))

        :browser/reload
        (accepted (service/history! svc (assoc payload :direction :reload)))

        :browser/observe
        (accepted (service/observe! svc payload))

        :browser/act
        (let [action (or (:action payload) (:act payload) (:browser-action payload))
              action (if (string? action) (keyword action) action)]
          (if (contains? model/action-kinds action)
            (accepted (service/act! svc (assoc payload :action action)))
            (command-error :BROWSER_ACTION_REQUIRED
                           "browser/act requires a concrete action"
                           {:action action
                            :allowed (vec (sort model/action-kinds))})))

        :browser/takeover
        (accepted (service/takeover! svc payload))

        :browser/operator-input
        (accepted (service/operator-input! svc payload))

        :browser/resume
        (accepted (service/resume! svc payload))

        :browser/close-tab
        (accepted (service/close-tab! svc payload))

        :browser/stop
        (accepted (service/stop! svc))

        (command-error :BROWSER_KIND_UNKNOWN
                       "unknown browser command kind"
                       {:kind kind}))
      (catch Throwable t
        (command-error :BROWSER_COMMAND_FAILED
                       (or (.getMessage t) "browser command failed")
                       {:kind kind
                        :class (.getName (class t))})))))

(defn health
  "Serializable browser health for `/healthz` and diagnostics."
  [svc]
  (service/health svc))

(defn blob-screenshot-store
  "Adapt a BlobStorePort `put-blob!` function to the service's
  `:screenshot-store` signature.  The store receives PNG bytes and the tab
  metadata; it returns a blob id or `{:blob-id ...}`."
  [put-blob!]
  (fn [bytes opts]
    (with-open [in (ByteArrayInputStream. ^bytes bytes)]
      (put-blob! in (assoc opts :mime-type "image/png")))))

(defn ref-screenshot-store
  "Adapt `put-blob!` and `add-ref!` to a screenshot store that keeps the
  object referenced by the session partition.

  A screenshot is not an artifact, but it is durable user-visible data.  The
  reference is what stops the blob cleaner from reclaiming it after the
  safety window."
  [put-blob! add-ref!]
  (fn [bytes opts]
    (with-open [in (ByteArrayInputStream. ^bytes bytes)]
      (let [stored (put-blob! in (assoc opts :mime-type "image/png"))
            id (or (:id stored) (:blob-id stored)
                   (when (string? stored) stored))]
        (when (and id add-ref!)
          (add-ref! id opts))
        {:status :stored
         :blob-id id
         :size (:size stored)
         :sha256 (:sha256 stored)}))))

(defn browser-error
  [code message & [details next-step]]
  (model/browser-error code message details next-step))

(def sensitive-target?
  "Best-effort sensitive-field classifier.  See
  `hc.server.browser.model/sensitive-target?`."
  model/sensitive-target?)
