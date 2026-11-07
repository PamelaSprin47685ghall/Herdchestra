(ns hc.server.app.devops
  "Application adapter for the real Plan dedicated worker.

  `hc.compose.mission.devops` owns the worker loop.  This namespace supplies
  the two host-owned ports:

  - the session model gateway (`:start!` / `:cancel!`);
  - the read-only tool executor backed by `hc.server.files` and
    `hc.core.text-location`.

  The adapter never exposes a write, shell, or board capability.  `park` and
  `unpark` are handled by the worker loop before this executor: `park`
  interrupts the current devops call with a parked receipt, and a later
  steer on the same worker can `unpark` that continuation exactly once."
  (:require [clojure.string :as str]
            [hc.compose.mission.devops :as devops]
            [hc.core.text-location :as text-location]
            [hc.server.files :as files]))

(defn- result-text
  [result]
  (cond
    (string? result) result
    (map? result)
    (cond
      (contains? result :text) (str (:text result))
      (contains? result :report) (str (:report result))
      :else (pr-str result))
    :else (str result)))

(defn- tool-ok
  [result]
  {:status :ok :text (result-text result)})

(defn- tool-failed
  [message]
  {:status :failed :text (str message)})

(defn- read-path
  [files-service path]
  (let [result (files/read-text files-service (or path ""))]
    (if (:ok? result)
      {:ok? true :text (:text result)}
      {:ok? false
       :error (or (get-in result [:error :message])
                  "file read failed")})))

(defn tool-executor
  "Build the read-only tool executor for one workspace.

  `:files-service` is required.  `:helpers` optionally maps helper names to
  functions used by the `call` capability.  No helper is available unless the
  host supplies it."
  [{:keys [files-service helpers]}]
  (when-not files-service
    (throw (ex-info "devops tool executor needs :files-service"
                    {:code :FILES_SERVICE_REQUIRED})))
  {:execute!
   (fn [{:keys [name arguments]}]
     (let [arguments (or arguments {})
           name (str name)]
       (try
         (case name
           "cat"
           (let [read (read-path files-service (:path arguments))]
             (if (:ok? read)
               (tool-ok (:text read))
               (tool-failed (:error read))))

           "stat"
           (tool-ok (files/stat-path files-service (:path arguments)))

           "ls"
           (tool-ok (files/list-entries files-service (:path arguments)))

           "where"
           (let [read (if (contains? arguments :content)
                        {:ok? true :text (:content arguments)}
                        (read-path files-service (:path arguments)))]
             (if (:ok? read)
               (tool-ok (text-location/where {:content (:text read)
                                              :needle (:needle arguments)}))
               (tool-failed (:error read))))

           "whereAll"
           (let [read (if (contains? arguments :content)
                        {:ok? true :text (:content arguments)}
                        (read-path files-service (:path arguments)))]
             (if (:ok? read)
               (tool-ok (text-location/whereAll
                         {:content (:text read)
                          :needle (:needle arguments)}))
               (tool-failed (:error read))))

           "call"
           (let [helper-name (keyword (str (:name arguments)))
                 helper (get helpers helper-name)]
             (if (fn? helper)
               (tool-ok (helper (:arguments arguments)))
               (tool-failed (str "no host helper named " (:name arguments)))))

           (tool-failed (str "unknown devops tool " name)))
         (catch Throwable t
           (tool-failed (or (.getMessage t) (str t)))))))})

(defn runner
  "Build a real Plan worker runner for one session.

  Options:
  - `:model-gateway` the session gateway with `:start!` / `:cancel!`.
  - `:files-service` the workspace read-only service.
  - `:helpers` optional read-only helper functions for `call`.
  - `:max-turns`, `:timeout-ms`, `:clock` passed to the worker runner."
  [{:keys [model-gateway files-service] :as opts}]
  (when-not (and (map? model-gateway)
                 (ifn? (:start! model-gateway))
                 (ifn? (:cancel! model-gateway)))
    (throw (ex-info "devops runner needs a session model gateway"
                    {:code :MODEL_GATEWAY_REQUIRED})))
  (devops/runner (assoc opts
                        :model model-gateway
                        :tools (tool-executor opts))))

(defn run-devops!
  "Run one synchronous Plan devops call through an injected command port.

  `:command!` receives the mission command map and returns the mission host
  result shape `{:ok? true :receipt ... :effects [...]}` or a refusal.  The
  application supplies it from its single Owner commit boundary; this
  function never commits state itself.

  The returned value is the completion result.  The report is the worker's
  full text, not a summary."
  [{:keys [runner command!]} {:keys [tenure-id call-id verbose-intent]
                              :as request}]
  (when-not (fn? command!)
    (throw (ex-info "run-devops! needs a :command! function"
                    {:code :COMMAND_PORT_REQUIRED})))
  (when-not (and (string? tenure-id) (not (str/blank? tenure-id)))
    (throw (ex-info "run-devops! needs a non-blank :tenure-id"
                    {:code :TENURE_ID_REQUIRED})))
  (when-not (and (string? call-id) (not (str/blank? call-id)))
    (throw (ex-info "run-devops! needs a non-blank :call-id"
                    {:code :CALL_ID_REQUIRED})))
  (when-not (and (string? verbose-intent) (not (str/blank? verbose-intent)))
    (throw (ex-info "run-devops! needs a non-blank :verbose-intent"
                    {:code :VERBOSE_INTENT_REQUIRED})))
  (let [requested (command! {:op :devops-request
                             :tenure-id tenure-id
                             :call-id call-id
                             :verbose-intent verbose-intent})]
    (if (false? (:ok? requested))
      requested
      (let [receipt (or (:receipt requested) requested)
            effect (or (first (:effects requested))
                       (when (:worker-id receipt)
                         {:type (if (:reused? receipt)
                                  :worker/steer
                                  :worker/spawn)
                          :worker-id (:worker-id receipt)
                          :tenure-id tenure-id
                          :call-id call-id
                          :verbose-intent verbose-intent
                          :model-role (or (:model-role receipt) :task)
                          :model-id (:model-id receipt)
                          :capabilities (:capabilities receipt)
                          :contract (:contract receipt)}))]
        (if-not (and (:worker-id effect) (:model-id effect))
          {:ok? false
           :error {:code :DEVOPS_EFFECT_MISSING
                   :message "devops request receipt has no worker identity"
                   :receipt receipt}}
        (let [outcome (try
                        ((:run! runner) effect)
                        (catch Throwable t
                          {:status :failed
                           :report (or (.getMessage t) (str t))}))]
          (command! {:op :devops-complete
                     :tenure-id tenure-id
                     :call-id call-id
                     :worker-id (:worker-id effect)
                     :status (:status outcome)
                     :report (:report outcome)}))
          )))))
