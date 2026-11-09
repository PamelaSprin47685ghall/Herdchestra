(ns hc.compose.mission.devops
  "Real Plan dedicated worker execution.

  `hc.compose.mission.worker` owns the contract and the host commits the
  spawn/steer request before any external work starts.  This namespace is the
  other half: one live worker session per Plan tenure, reused and steered for
  every `(devops ...)` call, running on the task model, with a read-only tool
  surface and a final report returned verbatim.

  The module has no dependency on `hc.server`.  The model gateway and the
  read-only tools are injected ports:

    :model {:start! (fn [request deliver!] handle)
            :cancel! (fn [handle])}
    :tools {:execute! (fn [{:keys [name arguments]}] -> result)
            :cat ..., :stat ..., :ls ..., :where ..., :whereAll ...,
            :call ...}

  A tool result is `{:status :ok|:failed :text \"...\"}`.  Structured tool
  values are already EDN+UFSS text.  A model event is
  `{:event :chunk|:completed|:failed|:cancelled ...}` as delivered by the
  model port.  The runner returns the host's completion shape:
  `{:status :ok|:failed|:interrupted :report \"...\"}`.

  `park` and `unpark` are worker-loop continuations, not injected tools.
  `park` stores a named continuation and interrupts the current devops call;
  a later steer can `unpark` it exactly once.  Stale and unknown names are
  explicit tool errors.

  Reports are never summarized, truncated, or moved to an artifact."
  (:require [clojure.string :as str]
            [hc.compose.mission.worker :as worker]
            [hc.core.ufss :as ufss]))

(def default-max-turns
  "Bound one worker turn loop.  This is a loop guard, not a report limit."
  16)

(def system-prompt
  (str "You are the Plan dedicated worker for one repository. "
       "You investigate facts and return a final report to the Plan.\n"
       "You may use only these read-only tools: cat, stat, ls, where, "
       "whereAll, park, unpark, call.  You cannot write, delete, move, "
       "split, claim, finish, review, or take a token.\n"
       "Use tools when they reduce uncertainty.  Do not invent file "
       "contents or command results.  When the investigation is complete, "
       "return the full report as your final assistant message.  The Plan "
       "receives that text verbatim."))

(def tool-specs
  "Tool declarations for the Plan dedicated worker capability surface."
  [{:name "cat"
    :description "Read a UTF-8 file inside the workspace."
    :parameters {:type "object"
                 :properties {:path {:type "string"}}
                 :required ["path"]}}
   {:name "stat"
    :description "Read file or directory metadata."
    :parameters {:type "object"
                 :properties {:path {:type "string"}}
                 :required ["path"]}}
   {:name "ls"
    :description "List a directory inside the workspace."
    :parameters {:type "object"
                 :properties {:path {:type "string"}}
                 :required ["path"]}}
   {:name "where"
    :description "Find one exact text occurrence."
    :parameters {:type "object"
                 :properties {:content {:type "string"}
                              :needle {:type "string"}}
                 :required ["content" "needle"]}}
   {:name "whereAll"
    :description "Find every exact text occurrence."
    :parameters {:type "object"
                 :properties {:content {:type "string"}
                              :needle {:type "string"}}
                 :required ["content" "needle"]}}
   {:name "park"
    :description (str "Suspend this investigation and return a parked receipt. "
                      "The same worker can resume the named continuation on a "
                      "later devops call with unpark.")
    :parameters {:type "object"
                 :properties {:name {:type "string"}
                              :payload {}}
                 :required []}}
   {:name "unpark"
    :description (str "Resume one parked continuation exactly once and inject "
                      "the value into its result.")
    :parameters {:type "object"
                 :properties {:name {:type "string"}
                              :value {}}
                 :required ["name"]}}
   {:name "call"
    :description "Invoke one host-approved read-only helper."
    :parameters {:type "object"
                 :properties {:name {:type "string"}
                              :arguments {:type "object"}}
                 :required ["name"]}}])

(def tool-names
  (into #{} (map :name tool-specs)))

(def scope-parameter-spec
  "The flat Scope annotation every non-exempt tool call must carry.

  This mirrors `hc.server.app.prompts/scope-schema`; compose cannot depend on
  server, so the shape is repeated here.  The model must see the same
  `:scope` requirement on `devops` as on `clj-plan`."
  {:type "object"
   :description
   "Flat Scope annotation for this turn; see # Scope in the system prompt."
   :properties
   {:kind {:type "string"
           :enum ["reset-scope" "keep-last" "remove-last" "me-too"]}
    :new-scope-goal {:type "string"}
    :new-scope-retention-budget {:type "integer" :minimum 1}
    :replacement {:type "string"}}
   :required ["kind"]})

(def devops-tool-spec
  "The single `devops` tool a Plan model may call.  The Plan supplies only
  the investigation intent; the host owns the worker identity, model, and
  report path.  `:scope` is required exactly like `clj-plan`."
  {:name "devops"
   :description (str "Run the Plan dedicated worker synchronously on the "
                     "task model and return its full report.  The worker is "
                     "read-only and is reused across calls in this tenure. "
                     "Every call carries the flat :scope annotation.")
   :parameters {:type "object"
                :properties {:verbose-intent {:type "string"}
                             :scope scope-parameter-spec}
                :required ["verbose-intent" "scope"]}})

(defn allowed-tool?
  [name]
  (and (contains? worker/capabilities (keyword name))
       (contains? tool-names (str name))
       (not (contains? worker/forbidden-capabilities (keyword name)))))

(defn- invalid!
  [message details]
  (throw (ex-info message (assoc details :code :INVALID_DEVOPS_CONFIG))))

(defn- now
  [runner]
  ((:clock runner)))

(defn- render
  [value]
  (try
    (ufss/write-str value)
    (catch Throwable error
      (ufss/write-str
       {:status :UNSUPPORTED_OPERATION
        :message "value could not be rendered as EDN+UFSS"
        :detail (or (.getMessage error) (str error))}))))

(defn- text
  [value]
  (cond
    (string? value) value
    (nil? value) ""
    :else (render value)))

(defn- failure
  [message]
  {:status :failed :report (str message)})

(defn- session-view
  [session]
  (-> (select-keys session
                   [:worker-id :tenure-id :model-id :model-role :status
                    :pending-call-id :last-report :last-status :turn-count
                    :created-at :updated-at])
      (assoc :parked-names
             (->> (:continuations session)
                  (keep (fn [[name continuation]]
                          (when (= :parked (:state continuation)) name)))
                  sort
                  vec))))

(defn- new-session
  [runner effect]
  (let [worker-id (:worker-id effect)
        tenure-id (:tenure-id effect)
        model-id (:model-id effect)]
    (when-not (and (string? worker-id) (not (str/blank? worker-id)))
      (invalid! "devops effect needs a non-blank :worker-id"
                {:effect effect}))
    (when-not (and (string? tenure-id) (not (str/blank? tenure-id)))
      (invalid! "devops effect needs a non-blank :tenure-id"
                {:effect effect}))
    (when-not (and (string? model-id) (not (str/blank? model-id)))
      (invalid! "devops effect needs a non-blank :model-id"
                {:effect effect}))
    (when-not (= :task (:model-role effect))
      (invalid! "devops worker must use the task model"
                {:model-role (:model-role effect)
                 :model-id model-id}))
    {:worker-id worker-id
     :tenure-id tenure-id
     :model-id model-id
     :model-role :task
     :status :idle
     :messages []
     :pending-call-id nil
     :attempt-seq 0
     :turn-count 0
     :continuations {}
     :park-seq 0
     :last-report nil
     :last-status nil
     :cancel-requested? false
     :waiter nil
     :handle nil
     :cancel-fn nil
     :lock (Object.)
     :created-at (now runner)
     :updated-at (now runner)}))

(defn- get-session
  [runner worker-id]
  (get @(:sessions runner) worker-id))

(defn- put-session!
  [runner session]
  (swap! (:sessions runner) assoc (:worker-id session)
         (assoc session :updated-at (now runner)))
  session)

(defn- update-session!
  [runner worker-id f & args]
  (let [session (get-session runner worker-id)]
    (when session
      (apply swap! (:sessions runner)
             update-in [worker-id]
             f args))
    (get-session runner worker-id)))

(defn- append-user!
  [session effect]
  (update session :messages conj
          {:role :user :text (text (:verbose-intent effect))}))

(defn- start-session
  [runner effect]
  (let [session (new-session runner effect)
        session (-> session
                    (assoc :messages [{:role :system :text system-prompt}])
                    (append-user! effect))]
    (put-session! runner session)))

(defn- existing-session
  [runner effect]
  (let [session (get-session runner (:worker-id effect))]
    (cond
      (nil? session) (start-session runner effect)
      (not= (:tenure-id session) (:tenure-id effect))
      (start-session runner effect)
      :else
      (put-session! runner
                    (-> session
                        (assoc :model-id (:model-id effect)
                               :model-role :task
                               :status :idle)
                        (append-user! effect))))))

(defn- continuation-tool!
  [runner session call]
  (let [name (str (:name call))
        arguments (or (:arguments call) {})
        session (or (get-session runner (:worker-id session)) session)]
    (case name
      "park"
      (let [park-name (let [requested (:name arguments)]
                        (if (and (string? requested)
                                 (not (str/blank? requested)))
                          requested
                          (str (:worker-id session) "-park-"
                               (inc (long (or (:park-seq session) 0))))))
            existing (get-in session [:continuations park-name])]
        (if existing
          {:status :failed
           :text (render {:code :NAME_TAKEN :name park-name})
           :session session}
          (let [payload (:payload arguments)
                receipt {:name park-name
                         :state :parked
                         :reason :explicit
                         :payload payload}
                continuation {:name park-name
                              :state :parked
                              :payload payload
                              :created-at (now runner)}
                session' (put-session! runner
                                       (-> session
                                           (assoc-in [:continuations park-name]
                                                     continuation)
                                           (update :park-seq inc)))]
            {:status :ok
             :text (render receipt)
             :interrupt? true
             :park-receipt receipt
             :session session'})))

      "unpark"
      (let [park-name (:name arguments)
            continuation (when (string? park-name)
                           (get-in session [:continuations park-name]))]
        (cond
          (not (and (string? park-name) (not (str/blank? park-name))))
          {:status :failed
           :text (render {:code :PROGRAM_UNKNOWN
                          :message "unpark needs a non-blank :name"})
           :session session}

          (nil? continuation)
          {:status :failed
           :text (render {:code :PROGRAM_UNKNOWN :name park-name})
           :session session}

          (= :resumed (:state continuation))
          {:status :failed
           :text (render {:code :STALE_CONTROL :name park-name})
           :session session}

          :else
          (let [value (:value arguments)
                receipt {:name park-name :state :resumed :value value}
                session' (put-session! runner
                                       (assoc-in session
                                                 [:continuations park-name]
                                                 (assoc continuation
                                                        :state :resumed
                                                        :value value
                                                        :resumed-at (now runner))))]
            {:status :ok
             :text (render receipt)
             :session session'})))

      nil)))

(defn- execute-tool!
  [runner session call]
  (or (continuation-tool! runner session call)
      (let [name (str (:name call))
            arguments (or (:arguments call) {})
            tools (:tools runner)]
        (cond
          (not (allowed-tool? name))
          {:status :failed
           :text (str "tool is not permitted for the Plan dedicated worker: " name)
           :session session}

          (and (map? tools) (ifn? (:execute! tools)))
          (try
            (let [result ((:execute! tools)
                          {:name name
                           :arguments arguments
                           :worker-id (:worker-id session)
                           :call-id (:call-id call)})]
              (cond
                (map? result)
                {:status (if (= :ok (:status result)) :ok :failed)
                 :text (text (or (:text result) (:report result) ""))
                 :session session}
                (string? result)
                {:status :ok :text result :session session}
                :else
                {:status :failed :text (text result) :session session}))
            (catch Throwable t
              {:status :failed
               :text (str "tool " name " failed: "
                          (or (.getMessage t) (str t)))
               :session session}))

          (and (map? tools) (ifn? (get tools (keyword name))))
          (try
            (let [result ((get tools (keyword name)) arguments)]
              (cond
                (map? result)
                {:status (if (= :ok (:status result)) :ok :failed)
                 :text (text (or (:text result) (:report result) ""))
                 :session session}
                (string? result)
                {:status :ok :text result :session session}
                :else
                {:status :failed :text (text result) :session session}))
            (catch Throwable t
              {:status :failed
               :text (str "tool " name " failed: "
                          (or (.getMessage t) (str t)))
               :session session}))

          :else
          {:status :failed
           :text (str "tool " name " is not available in this host")
           :session session}))))

(def ^:private workspace-note
  "workspace may have changed; re-read the facts you rely on")

(defn- call-result-text
  "Merge the best-effort workspace note into one EDN+UFSS value.

  The Plan worker's `call` is best-effort read-only.  The host cannot prove
  the command changed nothing, so the note is part of the structured value
  rather than text appended after it."
  [text]
  (let [value (try
                (ufss/read-str text)
                (catch Throwable _ nil))]
    (render
     (if (map? value)
       (assoc value
              :workspace-may-have-changed true
              :note workspace-note)
       {:result text
        :workspace-may-have-changed true
        :note workspace-note}))))

(defn- tool-message
  [call result]
  {:role :tool
   :tool-call-id (:id call)
   :name (str (:name call))
   :text (if (= "call" (str (:name call)))
           (call-result-text (:text result))
           (:text result))
   :error? (not= :ok (:status result))})

(defn- normalize-calls
  [call-id calls]
  (mapv (fn [index call]
          ;; The model adapter's assistant message contract allows only
          ;; `:id`, `:name`, and `:arguments` on each tool call.  The port
          ;; event carries bookkeeping fields (`:call-id`, `:output-index`,
          ;; `:tool-name`) that must not leak into the replayed message;
          ;; the event's name field is `:tool-name`, not `:name`.
          {:id (or (:id call) (str call-id "-tool-" index))
           :name (or (:name call) (:tool-name call))
           :arguments (:arguments call)})
        (range)
        calls))

(defn- model-request
  [runner session effect turn attempt-seq]
  (let [attempt-id (str (:worker-id session) "-" (:pending-call-id session)
                        "-" turn "-" attempt-seq)
        call-id (:pending-call-id session)
        snapshot {:role :task
                  :mission-role :plan-worker
                  :messages (vec (:messages session))
                  :tools tool-specs
                  :required-capabilities [:tools]
                  :temperature 0.2
                  :stream? true}]
    {:attempt-id attempt-id
     :call-id call-id
     :snapshot snapshot
     :required-capabilities #{:tools}}))

(defn- await-event
  [runner session request]
  (let [done (promise)
        partial (atom "")
        deliver! (fn [event]
                   (when (= :chunk (:event event))
                     (when (= :text (:channel event))
                       (swap! partial str (:text event))))
                   (when (contains? #{:completed :failed :cancelled}
                                    (:event event))
                     (deliver done event))
                   true)
        model (:model runner)]
    (swap! (:sessions runner) update-in [(:worker-id session)]
           assoc :waiter done :partial partial)
    (let [handle (if (:cancel-requested? (get-session runner
                                                       (:worker-id session)))
                   (do (deliver done {:event :cancelled
                                      :reason :user-cancel
                                      :partial-text @partial})
                       nil)
                   (try
                     ((:start! model) request deliver!)
                     (catch Throwable t
                       (deliver done {:event :failed
                                      :error {:message (or (.getMessage t)
                                                           (str t))}})
                       nil)))]
      (swap! (:sessions runner) update-in [(:worker-id session)]
             assoc :handle handle
             :cancel-fn (fn []
                          (when handle
                            (try ((:cancel! model) handle)
                                 (catch Throwable _ nil))))))
    (let [event (if (:timeout-ms runner)
                  (deref done (:timeout-ms runner)
                         {:event :failed
                          :error {:message "devops worker timed out"}})
                  @done)]
      (swap! (:sessions runner) update-in [(:worker-id session)]
             assoc :handle nil :cancel-fn nil :waiter nil :partial nil)
      (assoc event :partial-text @partial))))

(defn- run-loop
  [runner session effect]
  (loop [session session
         turn 1]
    (if (> turn (:max-turns runner))
      (failure (str "devops worker exceeded " (:max-turns runner)
                    " tool turns without a final report"))
      (let [attempt-seq (inc (long (or (:attempt-seq session) 0)))
            session (assoc session :attempt-seq attempt-seq)
            request (model-request runner session effect turn attempt-seq)
            event (await-event runner session request)
            partial (:partial-text event)
            session (put-session! runner (update session :turn-count inc))]
        (case (:event event)
          :completed
          (let [calls (normalize-calls (:call-id request)
                                       (or (:tool-calls event) []))
                content (or (:text event) partial "")]
              (if (seq calls)
              (let [assistant (cond-> {:role :assistant
                                       :tool-calls calls}
                                (not (str/blank? content))
                                (assoc :text content))
                    [session executions interrupt]
                    (reduce
                     (fn [[session executions interrupt] call]
                       (if interrupt
                         [session executions interrupt]
                         (let [result (execute-tool! runner session call)]
                           [(or (:session result) session)
                            (conj executions [call result])
                            (when (:interrupt? result) result)])))
                     [session [] nil]
                     calls)
                    tool-messages (mapv (fn [[call result]]
                                          (tool-message call result))
                                        executions)
                    session (put-session! runner
                                          (update session :messages
                                                  into
                                                  (concat [assistant]
                                                          tool-messages)))]
                (if interrupt
                  {:status :interrupted
                   :report (text (:text interrupt))}
                  (recur session (inc turn))))
              (let [report (text content)]
                (put-session! runner
                              (update session :messages conj
                                      {:role :assistant :text report}))
                {:status :ok :report report})))

          :failed
          (failure (or (get-in event [:error :message])
                       "devops worker model failed"))

          :cancelled
          {:status :interrupted
           :report (let [report (or (:partial-text event) "")]
                     (if (str/blank? report)
                       "devops worker interrupted before producing a report"
                       report))}

          (failure (str "devops worker received an unknown event: "
                        (render (:event event)))))))))

(defn- finish-session!
  [runner worker-id result]
  (swap! (:sessions runner)
         update-in [worker-id]
         (fn [session]
           (-> session
               (assoc :status :idle
                      :pending-call-id nil
                      :waiter nil
                      :cancel-requested? false
                      :last-report (:report result)
                      :last-status (:status result))
               (assoc :updated-at (now runner)))))
  result)

(defn- run-effect!
  [runner effect]
  (let [model-id (:model-id effect)
        model-role (:model-role effect)]
    (when (and (or (nil? model-role) (= :task model-role))
               (or (nil? model-id)
                   (and (string? model-id) (str/blank? model-id))))
      (throw (ex-info "the :task model role is not configured; configure :task model to run devops workers"
                      {:code :ROLE_UNCONFIGURED
                       :remedy :configure-task-model
                       :effect effect}))))
  (let [session (existing-session runner effect)
        worker-id (:worker-id session)]
    (locking (:lock session)
      (let [session (put-session! runner
                                  (assoc session
                                         :status :running
                                         :pending-call-id (:call-id effect)
                                         :cancel-requested? false))
            result (try
                     (run-loop runner session effect)
                     (catch Throwable t
                       (failure (str "devops worker failed: "
                                     (or (.getMessage t) (str t))))))]
        (finish-session! runner worker-id result)))))

(defn- cancel-session!
  [runner session reason]
  (let [session (get-session runner (:worker-id session))]
    (if-not session
      {:ok? false
       :error {:code :UNKNOWN_WORKER
               :message "worker session does not exist"}}
      (let [waiter (:waiter session)]
        (when-let [cancel-fn (:cancel-fn session)]
          (try (cancel-fn) (catch Throwable _ nil)))
        (when waiter
          (deliver waiter {:event :cancelled
                           :reason reason
                           :partial-text (if-let [partial (:partial session)]
                                           @partial
                                           "")}))
        (swap! (:sessions runner)
               update-in [(:worker-id session)]
               assoc :cancel-requested? true
               :status :cancelled)
        {:ok? true
         :worker-id (:worker-id session)
         :call-id (:pending-call-id session)
         :cancelled? true}))))

(defrecord DevopsRunner [model tools sessions max-turns timeout-ms clock])

(defn runner
  "Create one real Plan dedicated worker runner.

  Required:
  - `:model` map with `:start!` and `:cancel!`.

  Optional:
  - `:tools` executor map; see the namespace docstring.
  - `:max-turns` positive integer, default 16.
  - `:timeout-ms` positive integer or nil (unbounded).
  - `:clock` `(fn [] epoch-ms)`.

  The returned map has `:run!`, `:cancel!`, `:close!`, and `:sessions`."
  [{:keys [model tools max-turns timeout-ms clock]
    :or {max-turns default-max-turns
         clock #(System/currentTimeMillis)}}]
  (when-not (and (map? model) (ifn? (:start! model)) (ifn? (:cancel! model)))
    (invalid! "devops runner needs :model with :start! and :cancel!"
              {:model model}))
  (when-not (and (integer? max-turns) (pos? max-turns))
    (invalid! "devops runner :max-turns must be a positive integer"
              {:max-turns max-turns}))
  (when-not (or (nil? timeout-ms)
                (and (integer? timeout-ms) (pos? timeout-ms)))
    (invalid! "devops runner :timeout-ms must be a positive integer or nil"
              {:timeout-ms timeout-ms}))
  (let [runner (->DevopsRunner model tools (atom {}) max-turns timeout-ms
                               (or clock #(System/currentTimeMillis)))]
    {:run! (fn [effect]
             (try
               (run-effect! runner effect)
               (catch Throwable t
                 (let [data (ex-data t)
                       message (or (.getMessage t) (str t))]
                   (if (= :ROLE_UNCONFIGURED (:code data))
                     {:status :failed
                      :report message
                      :error {:code :ROLE_UNCONFIGURED
                              :remedy (:remedy data)
                              :message message}}
                     (failure (str "devops worker failed: " message)))))))
     :cancel! (fn [{:keys [worker-id call-id] :as request}]
                (let [session (or (when worker-id
                                    (get-session runner worker-id))
                                  (when call-id
                                    (some (fn [session]
                                            (when (= call-id
                                                     (:pending-call-id session))
                                              session))
                                          (vals @(:sessions runner)))))]
                  (if session
                    (cancel-session! runner session
                                     (or (:reason request) :user-cancel))
                    {:ok? false
                     :error {:code :UNKNOWN_WORKER
                             :message "worker session does not exist"
                             :worker-id worker-id
                             :call-id call-id}})))
     :close! (fn []
               (doseq [session (vals @(:sessions runner))]
                 (when (:cancel-fn session)
                   (try ((:cancel-fn session)) (catch Throwable _ nil))))
               (reset! (:sessions runner) {})
               {:closed? true})
     :sessions (fn []
                 (mapv session-view (vals @(:sessions runner))))
     ::runner runner}))
