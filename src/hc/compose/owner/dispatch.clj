(ns hc.compose.owner.dispatch
  "Missionary supervision for claimed attempts.

  The Owner commits a claim before it calls `start!`.  This namespace turns
  one dispatch effect into exactly one Missionary task, a model gateway
  request, a timer, or a report delivery.  Every terminal path posts one
  discrete message back to the Owner mailbox:

    {:type :call/terminal :call-id .. :attempt-id .. :status .. :payload ..}
    {:type :report/delivered :report-id .. :ok? .. :error ..}
    {:type :dispatch/settled :attempt-id ..}

  Cancellation is per attempt.  It interrupts only the executor thread that
  runs that attempt; it never touches the Owner thread, a store commit, or a
  FileChannel writer.  Late results stay diagnostic: the Owner rejects any
  terminal whose attempt id is no longer current.

  The namespace depends on Missionary and Clojure only.  It does not require
  hc.server.*.  The model gateway and report sink are injected functions, so
  the composition layer never reaches into a server adapter."
  (:require [missionary.core :as m])
  (:import [java.util.concurrent Executors ScheduledExecutorService TimeUnit]
           [java.util.concurrent.atomic AtomicLong]
           [missionary Cancelled]))

(def statuses
  #{:succeeded :failed :cancelled :outcome-unknown})

(def effect-kinds
  #{:model :tool :shell :workspace :devops :timer :report})

(def side-effecting-kinds
  "Executor kinds that own an external process, write, or remote effect.

  Their result is evidence about the outside world, so a missing or
  unrecognized result is never success: it is an unknown outcome that keeps
  `:resource-alive?` set until a real observation arrives."
  #{:tool :shell :workspace :devops})

(defn- now-ms
  []
  (System/currentTimeMillis))

(defn- invalid!
  [code message details]
  (throw (ex-info message (assoc details :code code))))

(defn- error-data
  [^Throwable error]
  (cond
    (instance? Cancelled error)
    {:kind :cancelled :message "attempt cancelled"}

    (instance? InterruptedException error)
    {:kind :cancelled :message "attempt interrupted"}

    (instance? clojure.lang.ExceptionInfo error)
    ;; The gateway and executors attach the real failure contract to ex-data:
    ;; search refusals, provider categories, and actionable codes.  Dropping
    ;; it would turn every start failure into an anonymous executor error and
    ;; hide the reason from the Nudge classifier and the user.
    (let [data (ex-data error)
          kind (or (:kind data) (:error/kind data) :executor-error)
          data (dissoc data :error/kind)]
      (merge {:kind kind
              :message (or (not-empty (str (:message data)))
                           (not-empty (str (.getMessage error)))
                           (.getName (class error)))
              :class (.getName (class error))}
             data))

    :else
    {:kind :executor-error
     :message (or (not-empty (str (.getMessage error)))
                  (.getName (class error)))
     :class (.getName (class error))}))

(defn- normalize-result
  "Turn an executor value into `{:status :payload :error}`.

  A map with a known `:status` is authoritative.  A map with `:ok? false`
  is a failure.

  Silence is never success for a side-effecting kind.  A nil return, a
  non-map, or a map with an unrecognized `:status` proves nothing about a
  Shell, workspace action, tool, or devops worker that may still be running,
  so it becomes `:outcome-unknown` with an explicit error.  P1 then leaves
  `:resource-alive?` set and `finish-run` keeps refusing until the executor
  or the cancel hook reports a real terminal.

  A model attempt owns no external resource, so an unrecognized value stays
  a success payload there: the gateway's answer is the whole result."
  [result kind]
  (let [side-effecting? (contains? side-effecting-kinds kind)
        unrecognized
        (fn [status]
          {:status :outcome-unknown
           :payload result
           :error {:kind :executor-result-unrecognized
                   :message "executor returned no recognized status; its external outcome is unknown"
                   :status status}})]
    (if (map? result)
      (let [status (:status result)]
        (cond
          (contains? statuses status)
          {:status status
           :payload (if (contains? result :payload)
                      (:payload result)
                      (dissoc result :status))
           :error (:error result)}

          (false? (:ok? result))
          {:status :failed
           :payload result
           :error (or (:error result)
                      {:kind :executor-error
                       :message "executor returned :ok? false"})}

          side-effecting?
          (unrecognized status)

          :else
          {:status :succeeded :payload result :error nil}))
      (if side-effecting?
        {:status :outcome-unknown
         :payload result
         :error {:kind :executor-result-missing
                 :message "executor returned no result; its external outcome is unknown"}}
        {:status :succeeded :payload result :error nil}))))

(defn- model-start-error
  "Preserve the gateway's failure contract and mark it permanent.

  A model start failure has not sent a request, but it is not a reason to
  repeat a required-search or configuration failure.  Cancellation keeps
  its own kind; search refusals keep their specific kind and code."
  [^Throwable error]
  (let [data (error-data error)]
    (if (= :cancelled (:kind data))
      data
      (cond-> (merge {:kind :model-start-failed
                      :message (or (:message data)
                                   (not-empty (str (.getMessage error)))
                                   (.getName (class error)))
                      :class (.getName (class error))}
                     data)
        (not (contains? #{:search-required-failed :search-unavailable}
                        (:kind data)))
        (assoc :kind :model-start-failed)

        true
        (assoc :category :permanent)))))

(defn create
  "Create a dispatcher.

  Options:

    :post!          `(fn [message])`, required; normally the Owner mailbox
    :executors      map of `:tool`, `:shell`, `:workspace` to a
                    `(fn [effect] result)` or
                    `{:run (fn [effect] result) :cancel (fn [effect])}`;
                    each run function executes on `m/blk`
    :model-gateway  `{:start! (fn [request deliver!] handle)
                      :cancel! (fn [handle])}`
                    `deliver!` receives one port event and returns the
                    receiver result; a wrapper must pass through
                    `{:ok? false}` unchanged, because the model port treats
                    it as a refusal and terminates the attempt
    :report-sink!   `(fn [report] result)`
    :cancel-resource! `(fn [{:keys [kind call-id attempt-id reason]}])`
                    fallback stop hook for a settled/parked attempt;
                    :kind is the executor kind, not the cancellation node kind
    :clock          `(fn [] epoch-ms)`, default `System/currentTimeMillis`
    :scheduler      optional ScheduledExecutorService for timers

  A synchronous start failure is posted as a terminal failure for the
  claimed attempt, so the Owner never waits for a task that never started."
  [{:keys [post! executors model-gateway report-sink! cancel-resource!
           clock scheduler]}]
  (when-not (ifn? post!)
    (invalid! :INVALID_POST "dispatch needs a :post! function" {}))
  (when (and (some? executors) (not (map? executors)))
    (invalid! :INVALID_EXECUTORS ":executors must be a map" {}))
  (let [timer-scheduler
        (or scheduler
            (Executors/newSingleThreadScheduledExecutor
             (reify java.util.concurrent.ThreadFactory
               (newThread [_ runnable]
                 (doto (Thread. ^Runnable runnable "hc-owner-timers")
                   (.setDaemon true))))))]
    {:post! post!
     :executors (or executors {})
     :model-gateway model-gateway
     :report-sink! report-sink!
     :cancel-resource! cancel-resource!
     :clock (or clock now-ms)
     :timer-scheduler timer-scheduler
     :owns-scheduler? (nil? scheduler)
     :closed? (atom false)
     :tasks (atom {})
     :timers (atom {})
     :dropped (AtomicLong. 0)
     :started (AtomicLong. 0)
     :settled (AtomicLong. 0)}))

(defn- post!
  [dispatcher message]
  (let [result ((:post! dispatcher) message)]
    (when (false? (:ok? result))
      (.incrementAndGet ^AtomicLong (:dropped dispatcher)))
    result))

(defn- settle!
  [dispatcher attempt-id]
  (swap! (:tasks dispatcher) dissoc attempt-id)
  (.incrementAndGet ^AtomicLong (:settled dispatcher)))

(defn- register!
  [dispatcher attempt-id entry]
  (swap! (:tasks dispatcher) assoc attempt-id
         (assoc entry :attempt-id attempt-id :started-at ((:clock dispatcher))))
  (.incrementAndGet ^AtomicLong (:started dispatcher))
  {:ok? true :attempt-id attempt-id :kind (:kind entry)})

(defn- cancelled-entry?
  [entry]
  (some? (deref (:reason entry))))

(defn- canonical-attempt-id
  "Return one spelling for an attempt id across the scheduler's keyword form
  and the cancellation tree's normalized string form.

  Keep in step with `hc.compose.owner.state/normalize-attempt-id`; this
  namespace deliberately requires only Clojure and Missionary."
  [attempt-id]
  (cond
    (nil? attempt-id) nil
    (keyword? attempt-id) (name attempt-id)
    (string? attempt-id)
    (if (.startsWith ^String attempt-id ":")
      (subs attempt-id 1)
      attempt-id)
    :else (str attempt-id)))

(defn- live-attempt-key
  "Find the dispatcher task key matching ATTEMPT-ID under canonical identity.

  The scheduler keys tasks by keyword; an Owner cancellation node stores the
  normalized string form.  Exact lookup alone would miss a live attempt and
  skip its cancel thunk."
  [dispatcher attempt-id]
  (let [wanted (canonical-attempt-id attempt-id)
        tasks @(:tasks dispatcher)]
    (or (when (contains? tasks attempt-id) attempt-id)
        (some (fn [key]
                (when (= wanted (canonical-attempt-id key)) key))
              (keys tasks)))))

(defn- post-settled!
  [dispatcher entry reason]
  (settle! dispatcher (:attempt-id entry))
  (post! dispatcher (cond-> {:type :dispatch/settled
                             :attempt-id (:attempt-id entry)
                             :call-id (:call-id entry)
                             :kind (:kind entry)}
                      (some? reason) (assoc :reason reason))))

;; ---------------------------------------------------------------------------
;; Model gateway

(defn- start-model!
  [dispatcher effect]
  (let [gateway (:model-gateway dispatcher)
        attempt-id (:attempt-id effect)
        call-id (:call-id effect)]
    (if-not gateway
      (do
        (post! dispatcher
               {:type :call/terminal
                :call-id call-id
                :attempt-id attempt-id
                :status :failed
                :error {:kind :model-start-failed
                        :category :permanent
                        :message "no model gateway is configured"}})
        {:ok? false
         :posted? true
         :error {:code :NO_MODEL_GATEWAY
                 :kind :model-start-failed
                 :category :permanent
                 :message "dispatch has no :model-gateway"}})
      (let [entry {:kind :model
                   :attempt-id attempt-id
                   :call-id call-id
                   :reason (atom nil)
                   :handle (atom nil)
                   :cancel (atom nil)
                   :cancel-upstream (atom nil)}
            done (m/dfv)
            deliver! (fn [event]
                       (let [event (-> event
                                       (assoc :attempt-id attempt-id)
                                       (update :call-id #(or % call-id))
                                       (assoc :model-call-id call-id))
                             accepted (post! dispatcher
                                              {:type :model/event
                                               :event event})]
                         (when (contains? #{:completed :failed :cancelled}
                                          (:event event))
                           (done event))
                         accepted))
            request (assoc (:request effect)
                           :attempt-id attempt-id
                           :call-id call-id)
            posted? (atom false)
            start-error (atom nil)
            handle (try
                     ((:start! gateway) request deliver!)
                     (catch Throwable error
                       (let [error (model-start-error error)]
                         (reset! start-error error)
                         (post! dispatcher
                                {:type :call/terminal
                                 :call-id call-id
                                 :attempt-id attempt-id
                                 :status :failed
                                 :error error}))
                       (reset! posted? true)
                       nil))]
        (if (nil? handle)
          (do
            (when-not @posted?
              (post! dispatcher
                     {:type :call/terminal
                      :call-id call-id
                      :attempt-id attempt-id
                      :status :failed
                      :error (or @start-error
                                 {:code :MODEL_START_FAILED
                                  :kind :model-start-failed
                                  :category :permanent
                                  :message "model gateway did not start the request"})})
              (reset! posted? true))
            {:ok? false
             :posted? true
             :error (or @start-error
                        {:code :MODEL_START_FAILED
                         :kind :model-start-failed
                         :category :permanent
                         :message "model gateway did not start the request"})})
          (let [_ (register! dispatcher attempt-id entry)
                supervisor (m/sp (m/? done) ::terminal)
                cancel (supervisor
                        (fn [_] (post-settled! dispatcher entry nil))
                        (fn [error]
                          (post-settled! dispatcher entry
                                         (if (cancelled-entry? entry)
                                           @(:reason entry)
                                           (error-data error)))))]
            (reset! (:handle entry) handle)
            (reset! (:cancel entry) cancel)
            (reset! (:cancel-upstream entry)
                    (fn []
                      (when-let [cancel-model (:cancel! gateway)]
                        (cancel-model handle))))
            {:ok? true :attempt-id attempt-id :kind :model}))))))

;; ---------------------------------------------------------------------------
;; Blocking executors

(defn- executor-for
  [dispatcher kind]
  (get (:executors dispatcher) kind))

(defn- start-executor!
  [dispatcher effect]
  (let [kind (:kind effect)
        attempt-id (:attempt-id effect)
        call-id (:call-id effect)
        executor (executor-for dispatcher kind)
        run-fn (if (map? executor) (:run executor) executor)
        cancel-fn (when (map? executor) (:cancel executor))]
    (if-not (ifn? run-fn)
      (do
        (post! dispatcher
               {:type :call/terminal
                :call-id call-id
                :attempt-id attempt-id
                :status :failed
                :error {:kind :invalid-request
                        :message (str "no executor is configured for " kind)}})
        {:ok? false
         :posted? true
         :error {:code :NO_EXECUTOR
                 :message (str "dispatch has no executor for " kind)}})
      (let [entry {:kind kind
                   :attempt-id attempt-id
                   :call-id call-id
                   :reason (atom nil)
                   ;; The executor's own terminal, kept so the teardown
                   ;; notice can defer to it instead of overwriting it.
                   :result (atom nil)
                   :cancel (atom nil)
                   :cancel-upstream
                   (atom (when (ifn? cancel-fn)
                           (fn [] (cancel-fn effect))))}
            _ (register! dispatcher attempt-id entry)
            task (m/via-call m/blk (fn [] (run-fn effect)))
            cancel (task
                    (fn [result]
                      (let [{:keys [status payload error]}
                            (normalize-result result (:kind entry))
                            ;; The executor finished on its own and reported a
                            ;; terminal.  Record it before settling: the
                            ;; settled notice below must not become this
                            ;; attempt's outcome when the executor already
                            ;; said what happened.
                            _ (reset! (:result entry)
                                      {:status status :payload payload
                                       :error error})]
                        (post! dispatcher
                               (cond-> {:type :call/terminal
                                        :call-id call-id
                                        :attempt-id attempt-id
                                        :status status
                                        :payload payload}
                                 (some? error) (assoc :error error))))
                      (post-settled! dispatcher entry nil))
                    (fn [error]
                      (let [reason @(:reason entry)
                            reported @(:result entry)
                            ;; This callback runs when the Missionary task ends.
                            ;; For a side-effecting executor, task cancellation
                            ;; stops the wrapper, not the external process.  A
                            ;; spawned Shell can still be running and has
                            ;; reported nothing, so a `:cancelled` terminal
                            ;; here would let P1 clear `:resource-alive?` and
                            ;; open a successor under a live process.  Preserve
                            ;; the unknown outcome; only the executor's own
                            ;; terminal or an explicit hook terminal settles it.
                            ;;
                            ;; When the executor did report, its result wins:
                            ;; the teardown notice is a fallback for silence,
                            ;; never a replacement for an answer we already
                            ;; have.
                            side-effecting? (not= :model (:kind entry))
                            ;; A teardown caused by our cancellation is not
                            ;; evidence the external process ended.  A plain
                            ;; throw is confirmed failure, even for a
                            ;; side-effecting executor: nothing was cancelled
                            ;; and the executor told us it failed.
                            cancelled? (or (some? reason)
                                           (instance? Cancelled error))
                            status (cond
                                     reported (:status reported)
                                     (and cancelled? side-effecting?)
                                     :outcome-unknown
                                     cancelled? :cancelled
                                     :else :failed)
                            terminal-error (cond
                                             reported (:error reported)
                                             (and cancelled? side-effecting?)
                                             (or (when reason
                                                   {:kind :cancelled
                                                    :message "cancellation requested; external outcome unknown"})
                                                 (error-data error))
                                             (and cancelled?
                                                  (not side-effecting?))
                                             {:kind :cancelled :message "cancelled"}
                                             :else
                                             (error-data error))]
                        (post! dispatcher
                               (cond-> {:type :call/terminal
                                        :call-id call-id
                                        :attempt-id attempt-id
                                        :status status}
                                 (and reported (some? (:payload reported)))
                                 (assoc :payload (:payload reported))
                                 (some? terminal-error)
                                 (assoc :error terminal-error)))
                        (post-settled! dispatcher entry reason))))]
        (reset! (:cancel entry) cancel)
        {:ok? true :attempt-id attempt-id :kind kind}))))

;; ---------------------------------------------------------------------------
;; Timers

(defn- start-timer!
  [dispatcher effect]
  (let [call-id (:call-id effect)
        retry-at (:retry-at effect)
        attempt-id (:attempt-id effect)]
    (if-not (and (integer? retry-at) (not (neg? retry-at)))
      (do
        (post! dispatcher
               {:type :dispatch/failed
                :call-id call-id
                :attempt-id attempt-id
                :error {:kind :invalid-request
                        :message "timer needs a non-negative integer :retry-at"}})
        {:ok? false
         :error {:code :INVALID_TIMER
                 :message "timer needs a non-negative integer :retry-at"}})
      (let [now ((:clock dispatcher))
            timer-id (str "timer-" attempt-id)
            existing (get @(:timers dispatcher) call-id)]
        (if (and existing
                 (= attempt-id (:attempt-id existing)))
          ;; The scheduler handshake is at-least-once.  Replaying the same
          ;; attempt must not move the fixed retry instant.
          {:ok? true
           :attempt-id attempt-id
           :timer-id timer-id
           :already-scheduled? true}
          (let [delay (max 0 (- (long retry-at) (long now)))
                _ (when existing
                    (.cancel ^java.util.concurrent.Future
                             (:future existing)
                             false))
                future (.schedule
                        ^ScheduledExecutorService (:timer-scheduler dispatcher)
                        ^Runnable
                        (fn []
                          (swap! (:timers dispatcher)
                                 (fn [timers]
                                   (if (= attempt-id
                                          (get-in timers [call-id :attempt-id]))
                                     (dissoc timers call-id)
                                     timers)))
                          (post! dispatcher
                                 {:type :timer/fire
                                  :call-id call-id
                                  :timer-id timer-id
                                  :retry-at retry-at}))
                        (long delay)
                        TimeUnit/MILLISECONDS)]
            (swap! (:timers dispatcher) assoc call-id
                   {:future future
                    :attempt-id attempt-id
                    :retry-at retry-at})
            {:ok? true
             :attempt-id attempt-id
             :timer-id timer-id
             :delay-ms delay}))))))

;; ---------------------------------------------------------------------------
;; Reports

(defn- start-report!
  [dispatcher effect]
  (let [report-id (:report-id effect)
        attempt-id (:attempt-id effect)
        sink (:report-sink! dispatcher)]
    (if-not (ifn? sink)
      (do
        (post! dispatcher
               {:type :report/delivered
                :report-id report-id
                :attempt-id attempt-id
                :ok? false
                :error {:code :NO_REPORT_SINK
                        :message "no report sink is configured"}})
        {:ok? false
         :posted? true
         :error {:code :NO_REPORT_SINK
                 :message "dispatch has no :report-sink!"}})
      (let [entry {:kind :report
                   :attempt-id attempt-id
                   :report-id report-id
                   :call-id nil
                   :reason (atom nil)
                   :cancel (atom nil)}
            _ (register! dispatcher attempt-id entry)
            task (m/via-call m/blk (fn [] (sink (:report effect))))
            cancel (task
                    (fn [result]
                      (post! dispatcher
                             {:type :report/delivered
                              :report-id report-id
                              :attempt-id attempt-id
                              :ok? (not (false? (:ok? result)))
                              :result result
                              :error (:error result)})
                      (post-settled! dispatcher entry nil))
                    (fn [error]
                      (post! dispatcher
                             {:type :report/delivered
                              :report-id report-id
                              :attempt-id attempt-id
                              :ok? false
                              :error (if (instance? Cancelled error)
                                       {:code :CANCELLED
                                        :message "report delivery cancelled"}
                                       (error-data error))})
                      (post-settled! dispatcher entry @(:reason entry))))]
        (reset! (:cancel entry) cancel)
        {:ok? true :attempt-id attempt-id :kind :report}))))

;; ---------------------------------------------------------------------------
;; Public operations

(defn- report-effect?
  [effect]
  (and (map? effect) (= :report (:kind effect))))

(defn- post-report-start-failure!
  [dispatcher effect error]
  (post! dispatcher
         {:type :report/delivered
          :report-id (:report-id effect)
          :attempt-id (:attempt-id effect)
          :ok? false
          :error error}))

(defn- ensure-call-terminal!
  "Close a claimed call when a start failure did not post a terminal.

  A model or executor effect that never reaches its execution layer must not
  leave the call in `:dispatching`.  The error is preserved as the terminal
  cause; the later terminal guard still decides whether a retry is allowed."
  [dispatcher effect result]
  (if (or (:ok? result)
          (:posted? result)
          (contains? #{:timer :report} (:kind effect))
          (nil? (:attempt-id effect))
          (nil? (:call-id effect)))
    result
    (let [error (or (:error result)
                    {:kind :dispatch-failed
                     :message "dispatch could not start the effect"})]
      (post! dispatcher
             {:type :call/terminal
              :call-id (:call-id effect)
              :attempt-id (:attempt-id effect)
              :status :failed
              :error error})
      (assoc result :posted? true))))

(defn start!
  "Start one dispatch effect.  The Owner calls this only after it commits
  the claim.  Returns `{:ok? true :attempt-id .. :kind ..}` or a structured
  failure.  A failure also posts a terminal message for the claimed attempt."
  [dispatcher effect]
  (cond
    @(:closed? dispatcher)
    (let [error {:code :DISPATCHER_CLOSED
                 :message "dispatcher is closed"}]
      (if (report-effect? effect)
        (do
          (post-report-start-failure! dispatcher effect error)
          {:ok? false :posted? true :error error})
        (do
          (post! dispatcher
                 {:type :call/terminal
                  :call-id (:call-id effect)
                  :attempt-id (:attempt-id effect)
                  :status :failed
                  :error {:kind :executor-shutdown
                          :message "dispatcher is closed"}})
          {:ok? false :posted? true :error error})))

    (not (map? effect))
    {:ok? false
     :error {:code :INVALID_EFFECT
             :message "a dispatch effect must be a map"}}

    (not (contains? effect-kinds (:kind effect)))
    (do
      (post! dispatcher
             {:type :call/terminal
              :call-id (:call-id effect)
              :attempt-id (:attempt-id effect)
              :status :failed
              :error {:kind :invalid-request
                      :message (str "unknown effect kind " (pr-str (:kind effect)))}})
      {:ok? false
       :posted? true
       :error {:code :INVALID_EFFECT
               :message (str "unknown effect kind " (pr-str (:kind effect)))}})

    (nil? (:attempt-id effect))
    (let [error {:code :MISSING_ATTEMPT_ID
                 :message "a dispatch effect needs a committed :attempt-id"}]
      (if (report-effect? effect)
        (do
          (post-report-start-failure! dispatcher effect error)
          {:ok? false :posted? true :error error})
        {:ok? false :error error}))

    (contains? @(:tasks dispatcher) (:attempt-id effect))
    ;; The scheduler may replay `after-commit` after a host interruption.
    ;; Dispatch is idempotent by attempt id: a running attempt is already the
    ;; requested work, not a new failure.
    {:ok? true
     :already-running? true
     :attempt-id (:attempt-id effect)
     :kind (:kind effect)}

    :else
    (ensure-call-terminal!
     dispatcher effect
     (try
       (case (:kind effect)
         :model (start-model! dispatcher effect)
         :timer (start-timer! dispatcher effect)
         :report (start-report! dispatcher effect)
         (start-executor! dispatcher effect))
       (catch Throwable t
         (let [message (or (ex-message t) (str (class t)))]
           (if (report-effect? effect)
             (let [error {:code :DISPATCH_EXCEPTION :message message}]
               (post-report-start-failure! dispatcher effect error)
               {:ok? false :posted? true :error error})
             (let [model? (= :model (:kind effect))
                   error (if model?
                           (model-start-error t)
                           {:kind :executor-error
                            :message message
                            :class (.getName (class t))})]
               (post! dispatcher
                      {:type :call/terminal
                       :call-id (:call-id effect)
                       :attempt-id (:attempt-id effect)
                       :status :failed
                       :error error})
               {:ok? false
                :posted? true
                :error {:code :DISPATCH_EXCEPTION :message message}}))))))))

(defn- cancel-entry!
  "Record the cancel reason and signal the external resource.

  For a side-effecting executor the task carries the only channel that
  reports the real result.  Cancelling that Missionary task destroys the
  success callback, so a Shell that later returns
  `{:status :cancelled}` would never be published.  Signal the executor's
  own cancel instead, leave the task running, and let its success callback
  post the terminal.  A genuinely stuck executor simply stays live and keeps
  the run `:cancelling`, which is the honest state."
  [dispatcher entry reason]
  (reset! (:reason entry) reason)
  (when-let [cancel-upstream (some-> (:cancel-upstream entry) deref)]
    (try (cancel-upstream) (catch Throwable _ nil)))
  (when (= :model (:kind entry))
    (when-let [cancel @(:cancel entry)]
      (try (cancel) (catch Throwable _ nil))))
  (when (not= :model (:kind entry))
    ;; The executor still runs.  Drop the dispatcher entry now so stop does
    ;; not wait on it and a second cancel is a no-op; the task's success
    ;; callback remains the authority and posts the real terminal.
    (settle! dispatcher (:attempt-id entry))))

(defn cancel!
  "Cancel one live attempt.

  The reason is recorded before the cancel thunk runs, so the failure
  callback reports `:cancelled` with the right cause.  An unknown attempt is
  a no-op: a late result may have already settled it."
  [dispatcher attempt-id reason]
  (if-let [key (live-attempt-key dispatcher attempt-id)]
    (let [entry (get @(:tasks dispatcher) key)]
      (cancel-entry! dispatcher entry reason)
      {:ok? true :attempt-id key :reason reason})
    {:ok? false
     :error {:code :UNKNOWN_ATTEMPT
             :message "attempt is not running"
             :details {:attempt-id attempt-id}}}))

(def ^:private resource-succeeded-statuses
  #{:COMPLETED :completed :APPLIED :applied})

(def ^:private resource-failed-statuses
  #{:FAILED :failed})

(def ^:private resource-cancelled-statuses
  #{:CANCELLED :cancelled})

(def ^:private resource-unknown-statuses
  #{:UNCERTAIN :uncertain :OUTCOME_UNKNOWN :outcome-unknown})

(defn- normalize-resource-status
  [status]
  (cond
    (contains? resource-succeeded-statuses status) :succeeded
    (contains? resource-failed-statuses status) :failed
    (contains? resource-cancelled-statuses status) :cancelled
    (contains? resource-unknown-statuses status) :outcome-unknown
    :else nil))

(defn- resource-terminal
  "Extract one call-terminal map from a cancel hook receipt.

  Hooks may return the explicit `{:terminal {...}}` contract or a raw
  program/shell receipt.  A still-committing apply, a running shell, or an
  unknown program is not terminal and returns nil."
  [resource]
  (when (map? resource)
    (cond
      (map? (:terminal resource))
      (:terminal resource)

      (true? (:cancel-after-apply? resource))
      nil

      (true? (:cancel-pending? resource))
      nil

      (= :SESSION_ENDED (:status resource))
      (let [result (:result resource)
            status (normalize-resource-status (:status result))]
        (when status
          {:status (if (and (= :succeeded status)
                            (true? (:cancelled? result)))
                     :cancelled
                     status)
           :payload result
           :error (:error result)}))

      :else
      (when-let [status (normalize-resource-status (:status resource))]
        {:status status
         :payload resource
         :error (:error resource)}))))

(defn cancel-resource!
  "Cancel a resource named by an Owner outbox entry.

  A live attempt gets the normal per-attempt cancellation.  ATTEMPT-ID may
  arrive as the cancellation tree's normalized string or the scheduler's
  keyword; the live task is found by canonical identity.  The configured
  `:cancel-resource!` hook is always called as well, so a settled/parked
  program or a shell call whose executor thread ignored interruption still
  receives its explicit stop signal.  The entry's `:kind` is the executor
  kind (`:tool`, `:shell`, `:workspace`, `:devops`), so the hook can route
  it.  The hook must be idempotent."
  [dispatcher {:keys [kind call-id attempt-id reason] :as entry}]
  (let [live-key (live-attempt-key dispatcher attempt-id)
        live? (boolean live-key)
        cancel-result (if live?
                        (cancel! dispatcher live-key
                                 (or reason :user-cancel))
                        {:ok? true :live? false})
        hook (:cancel-resource! dispatcher)
        resource-result
        (if (ifn? hook)
          (try
            (hook (select-keys entry
                               [:kind :call-id :attempt-id :reason]))
            (catch Throwable t
              {:ok? false
               :error {:code :CANCEL_RESOURCE_FAILED
                       :message (or (ex-message t) (str (class t)))}}))
          {:ok? false
           :error {:code :NO_CANCEL_RESOURCE_HOOK
                   :message "no :cancel-resource! hook is configured"}})
        terminal (resource-terminal resource-result)
        resource-result
        (if (and (map? resource-result)
                 (nil? (:terminal resource-result))
                 (some? terminal))
          (assoc resource-result :terminal terminal)
          resource-result)]
    {:ok? (and (not (false? (:ok? cancel-result)))
               (not (false? (:ok? resource-result))))
     :live? live?
     :live-attempt-id live-key
     :cancel cancel-result
     :resource resource-result}))

(defn cancel-timer!
  "Cancel a scheduled retry timer for CALL-ID.  Idempotent."
  [dispatcher call-id]
  (if-let [entry (get @(:timers dispatcher) call-id)]
    (do
      (.cancel ^java.util.concurrent.Future (:future entry) false)
      (swap! (:timers dispatcher) dissoc call-id)
      {:ok? true :call-id call-id})
    {:ok? true :call-id call-id :already-cancelled? true}))

(defn live-attempts
  "Return one map per running attempt."
  [dispatcher]
  (->> (vals @(:tasks dispatcher))
       (map #(select-keys % [:attempt-id :call-id :kind :started-at :report-id]))
       (sort-by :started-at)
       vec))

(defn stop!
  "Cancel every live attempt and timer with REASON, then stop the timer
  scheduler.  This does not interrupt the Owner thread and does not dispose
  evidence."
  ([dispatcher] (stop! dispatcher :executor-shutdown))
  ([dispatcher reason]
   (reset! (:closed? dispatcher) true)
   (let [ids (vec (keys @(:tasks dispatcher)))]
     (doseq [entry (vals @(:tasks dispatcher))]
       (cancel-entry! dispatcher entry reason))
     ;; Let cancellation callbacks settle before dropping the registry.
     (let [deadline (+ (now-ms) 2000)]
       (loop []
         (when (and (seq @(:tasks dispatcher))
                    (< (now-ms) deadline))
           (Thread/sleep 5)
           (recur))))
     (reset! (:tasks dispatcher) {})
     (doseq [[_ entry] @(:timers dispatcher)]
       (.cancel ^java.util.concurrent.Future (:future entry) false))
     (reset! (:timers dispatcher) {})
     (when (:owns-scheduler? dispatcher)
       (.shutdownNow ^ScheduledExecutorService (:timer-scheduler dispatcher)))
     {:ok? true
      :cancelled ids
      :reason reason})))

(defn stats
  [dispatcher]
  {:live (count @(:tasks dispatcher))
   :timers (count @(:timers dispatcher))
   :started (.get ^AtomicLong (:started dispatcher))
   :settled (.get ^AtomicLong (:settled dispatcher))
   :dropped (.get ^AtomicLong (:dropped dispatcher))
   :closed? @(:closed? dispatcher)})
