(ns hc.server.model-adapter
  "Public entry point for the LangChain4j low-level model adapter.

  The application owns provider configuration and secrets.  It calls
  `create-model-port` once and passes the returned object to the Owner.  The
  port implements exactly `hc.server.model-port.contract/ModelPort`; the
  control functions below are optional diagnostics and shutdown aids."
  (:require [hc.server.model-adapter.config :as config]
            [hc.server.model-adapter.port :as port]
            [hc.server.model-adapter.providers :as providers])
  (:import [java.lang AutoCloseable]
           [java.util.concurrent Executors ThreadFactory]
           [java.util.concurrent.atomic AtomicLong]))

(defn model-configured?
  "True when the configuration names at least one provider role.

  This is structural.  A missing provider module or an unreachable endpoint is
  not reported as \"not configured\"; `create-model-port` reports those
  explicitly."
  [configuration]
  (config/model-configured? configuration))

(defn- thread-factory
  [prefix]
  (let [counter (AtomicLong.)]
    (reify ThreadFactory
      (newThread [_ runnable]
        (doto (Thread. runnable (str prefix "-" (.incrementAndGet counter)))
          (.setDaemon true))))))

(defn- not-configured!
  []
  (throw (ex-info "no model connection and role are configured"
                  {:error/kind :model-not-configured})))

(defn- configured-targets
  [normalized]
  (vec
   (for [[connection-id connection] (:connections normalized)
         :when (not (false? (:enabled? connection)))
         [model-id model] (:models connection)
         :when (not (false? (:enabled? model)))]
     [connection-id model-id])))

(defn- role-targets
  [normalized]
  (->> (:roles normalized)
       vals
       (map (fn [{:keys [connection model]}]
              [connection model]))
       distinct
       set))

(defn- close-built-model!
  [model]
  (when (instance? AutoCloseable model)
    (try (.close ^AutoCloseable model) (catch Throwable _ nil))))

(defn- close-built-profiles!
  "Close models this factory built before a later build step failed.

  Custom profiles carry caller-injected models and are never closed here."
  [profiles]
  (doseq [profile profiles
          :when (:owns-models? profile)]
    (close-built-model! (:chat-model profile))
    (close-built-model! (:streaming-chat-model profile))))

(defn create-model-port
  "Build one ModelPort from application configuration.

  Throws `ex-info` with `{:error/kind :model-not-configured}` when the
  configuration names no role.  Throws `:provider-module-missing`,
  `:provider-build-failed`, or `:invalid-model-adapter-config` for explicit
  configuration failures on a role target.  A broken model that no role
  references is omitted so it cannot block the port; selecting it later fails
  explicitly.  It never returns a fake port and never substitutes a test
  double for a real provider."
  [configuration]
  (when (or (not (map? configuration))
            (empty? (:connections configuration)))
    (not-configured!))
  (let [normalized (config/normalize-config configuration)
        targets (configured-targets normalized)
        required (role-targets normalized)]
    (when (empty? (:roles normalized))
      (not-configured!))
    (let [profiles (reduce
                    (fn [built [connection-id model-id]]
                      (let [connection (get-in normalized
                                               [:connections connection-id])
                            model (get-in connection [:models model-id])]
                        (try
                          (assoc built [connection-id model-id]
                                 (providers/build-profile connection model))
                          (catch Throwable t
                            (if (contains? required [connection-id model-id])
                              (do
                                (close-built-profiles! (vals built))
                                (throw t))
                              built)))))
                    {}
                    targets)
          executor (Executors/newFixedThreadPool
                    (:executor-threads normalized)
                    (thread-factory "hc-model-adapter"))
          close-fns (into []
                          (comp (mapcat :close-fns) (distinct))
                          (vals profiles))]
      (try
        (port/create {:id (str (random-uuid))
                      :roles (:roles normalized)
                      :default-role (:default-role normalized)
                      :profiles profiles
                      :parse-arguments (:parse-arguments normalized)
                      :attachment-loader (:attachment-loader normalized)
                      :credential-resolver (:credential-resolver normalized)
                      :executor executor
                      :close-models? (:close-models? normalized)
                      :close-fns close-fns})
        (catch Throwable t
          (close-built-profiles! (vals profiles))
          (.shutdownNow executor)
          (throw t))))))

(defn create-port
  "Alias for `create-model-port`."
  [configuration]
  (create-model-port configuration))

(defn stop-port!
  "Cancel active attempts and stop the port.  Idempotent.

  Provider models are closed only when the configuration sets
  `:close-models? true`.  Explicit custom `:close-fn` callbacks always run."
  [port]
  (when (satisfies? port/ModelAdapterControl port)
    (port/close-port! port)))

(defn stats
  "Counters for one port, or nil for a foreign object."
  [port]
  (when (satisfies? port/ModelAdapterControl port)
    (port/stats port)))

(defn await-attempt
  "Wait for one attempt's terminal event, or return `{:status :timeout ...}`."
  ([port attempt-id] (await-attempt port attempt-id 5000))
  ([port attempt-id timeout-ms]
   (when (satisfies? port/ModelAdapterControl port)
     (port/await-attempt port attempt-id timeout-ms))))

(def supported-provider-kinds
  "Provider kinds this package can build when the matching module is present."
  #{:openai :openai-compatible :ollama :custom})
