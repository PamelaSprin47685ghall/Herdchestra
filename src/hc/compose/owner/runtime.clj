(ns hc.compose.owner.runtime
  "Effectful Owner loop for one session.

  The mailbox thread is the only writer.  One message becomes one pure
  transition, one committed version, and then zero or more advisory
  notifications.  Guarded micro-actions run after the message commit.  Each
  prepared micro-action is committed before its dispatch effects leave the
  Owner.

  The Owner never blocks on a model, tool, Shell, report sink, or store
  commit.  Missionary tasks run outside this namespace; their terminal
  messages return through the mailbox.  Cancellation is dispatched per
  attempt and never interrupts this thread."
  (:require [clojure.string :as str]
            [hc.compose.owner.commands :as commands]
            [hc.compose.owner.compact :as compact]
            [hc.compose.owner.dispatch :as dispatch]
            [hc.compose.owner.guards :as guards]
            [hc.compose.owner.handlers :as handlers]
            [hc.compose.owner.mailbox :as mailbox]
            [hc.compose.owner.state :as state]
            [hc.compose.owner.store :as store]
            [hc.compose.guards.scheduler :as scheduler])
  (:import [java.util.concurrent Executors ThreadFactory TimeUnit]))

(def default-quota 32)

(defn- now-ms
  []
  (System/currentTimeMillis))

(defn- daemon-thread-factory
  [name]
  (reify ThreadFactory
    (newThread [_ runnable]
      (doto (Thread. ^Runnable runnable name)
        (.setDaemon true)))))

(defn- redact-internal-env
  "Remove in-process board-command env before a message leaves the Owner.

  The W capability and verifier are valid only inside the bridge's protected
  call.  They must not reach notifications, diagnostics, subscribers, or any
  other observer."
  [message]
  (if (and (map? message) (map? (:env message)))
    (update message :env dissoc :write-gate-proof :verify-read-set)
    message))

(defn- record-error!
  [runtime error-info]
  (let [error-info (if (contains? error-info :message)
                     (update error-info :message redact-internal-env)
                     error-info)
        entry (assoc error-info :at (now-ms))]
    (swap! (:stats runtime)
           (fn [stats]
             (-> stats
                 (update :errors inc)
                 (assoc :last-error entry))))
    (when-let [on-error (:on-error! (:config runtime))]
      (try
        (on-error entry)
        (catch Throwable _ nil)))
    entry))

(defn- record-event!
  [runtime event]
  (swap! (:notifications runtime)
         (fn [events] (vec (take-last 64 (conj (vec events) event)))))
  (when-let [on-effect! (:on-effect! (:config runtime))]
    (try
      (on-effect! event)
      (catch Throwable error
        (record-error! runtime {:phase :notification
                                :event event
                                :error {:kind :notification-handler-error
                                        :message (str (.getMessage error))}})))))

(defn- ctx
  [runtime now]
  (let [state (:state @(:state runtime))]
    {:now now
     :registry (:registry (:config runtime))
     :session-port (:session-port (:config runtime))
     :session-run-port (:session-run-port (:config runtime))
     :attachment-contract (:attachment-contract (:config runtime))
     :attachment-policy (:attachment-policy (:config runtime))
     :extension-tools-fn (:extension-tools-fn (:config runtime))
     :skill-context-fn (:skill-context-fn (:config runtime))
     :on-user-send! (:on-user-send! (:config runtime))
     :on-call-terminal! (:on-call-terminal! (:config runtime))
     :model-tools-supported (:model-tools-supported (:config runtime))
     :model-tools-source (:model-tools-source (:config runtime))
     :tool-result-renderer (:tool-result-renderer (:config runtime))
     :outbound-context-fn (:outbound-context-fn (:config runtime))
     :nudge-suppress (:nudge-suppress (:config runtime))
     :worker-run-program-fn (:worker-run-program-fn (:config runtime))
     :blob-store (:blob-store (:config runtime))
     :compact-threshold (:compact-threshold (:config runtime))
     :retry-policy (:retry-policy state)
     :max-tool-concurrency (get-in state [:config :max-tool-concurrency] 1)}))

(declare timer-pending?)

(defn- guard-ctx
  [runtime now]
  (let [state (:state @(:state runtime))]
    {:now now
     :retry-policy (:retry-policy state)
     :max-tool-concurrency (get-in state [:config :max-tool-concurrency] 1)
     :attachment-contract (:attachment-contract (:config runtime))
     :attachment-policy (:attachment-policy (:config runtime))
     :extension-tools-fn (:extension-tools-fn (:config runtime))
     :skill-context-fn (:skill-context-fn (:config runtime))
     :model-tools-supported (:model-tools-supported (:config runtime))
     :model-tools-source (:model-tools-source (:config runtime))
     :tool-result-renderer (:tool-result-renderer (:config runtime))
     :outbound-context-fn (:outbound-context-fn (:config runtime))
     :nudge-suppress (:nudge-suppress (:config runtime))
     :tenure-terminal? (or (state/tenure-terminal? state)
                           (let [run-id (get-in state [:cancel :active-run-id])]
                             ;; A cancelled run is the end of its tenure: no
                             ;; work may be invented for it.  A run that is
                             ;; still :cancelling is not terminal; its domain
                             ;; may still own a live Shell or :applying
                             ;; program, and the P4 assertion must stay open
                             ;; so cancellation work is never reported as an
                             ;; idle dead-end.
                             (= :cancelled
                                (get-in state [:cancel :nodes run-id
                                               :state]))))
     :inflight? (or (seq (:terminals state))
                    (some #(contains? #{:dispatching :running} (:state %))
                          (vals (:calls state)))
                    (seq (dispatch/live-attempts (:dispatcher runtime))))
     :timed-wait? (or (boolean (some #(= :retry-wait (:state %))
                                     (vals (:calls state))))
                      (timer-pending? runtime))
     :live-attempt-ids (set (map :attempt-id
                                 (dispatch/live-attempts
                                  (:dispatcher runtime))))}))

(defn- timer-pending?
  "True when the dispatcher holds a timer that will wake this Owner.

  A retry wait is armed by a guard in the same transition that sets
  `:retry-wait`, so the timer count is the wake guarantee.  There is no
  other committed deadline: the late-node cancel is immediate and owns no
  timer."
  [runtime]
  (pos? (long (:timers (dispatch/stats (:dispatcher runtime)) 0))))

(defn- reload!
  [runtime]
  (let [snapshot (store/load-state (:store runtime))]
    (reset! (:state runtime)
            {:version (:version snapshot) :state (:state snapshot)})
    snapshot))

;; ---------------------------------------------------------------------------
;; Outbox

(defn- drain-outbox!
  [runtime]
  (let [state (:state @(:state runtime))
        outbox (vec (:outbox state))
        counter (long (get-in state [:counters :outbox-seq] 0))
        pointer (long (or @(:outbox-drained runtime) 0))
        ;; The pointer is a high-water mark, never a vector index.  A pointer
        ;; below the counter is the ordinary pending window: keep it, or
        ;; every newly appended effect would replay all retained entries.  A
        ;; pointer above the counter can only come from a restart or a
        ;; hand-built state; start over so a fresh entry cannot be skipped.
        drained (if (> pointer counter) 0 pointer)
        _ (reset! (:outbox-drained runtime) drained)
        pending (filterv (fn [entry]
                           (> (long (or (:outbox-seq entry) 0)) drained))
                         outbox)]
    ;; The pointer advances only over entries this call actually delivered.
    ;; It must never be derived from the last entry in the vector: an effect
    ;; appended later in the same commit is still in the vector but was not
    ;; pending when the drain started, and treating it as drained loses its
    ;; side effect -- a lost `:cancel/resource` strands a cancelled run
    ;; forever.  When nothing is pending, the pointer catches up to the
    ;; committed counter, which is the only authority for "drained".
    (let [delivered (volatile! drained)]
      (doseq [entry pending]
        (vreset! delivered (long (or (:outbox-seq entry) 0)))
        (case (:type entry)
          :worker/run-program
          (let [job-id (:job-id entry)
                runner (get-in runtime [:config :worker-run-program-fn])]
            (if-not (ifn? runner)
              (mailbox/submit!
               (:mailbox runtime)
               {:type :worker/program-result
                :job-id job-id
                :outcome :failed
                :error {:code :WORKER_PROGRAM_UNAVAILABLE
                        :message "no worker program runner is configured"}})
              ;; The protected commit calls back into the Owner board port.
              ;; It must run outside the mailbox or it waits on itself.
              (future
                (let [job (future
                            (try
                              (runner (:request entry))
                              (catch Throwable t
                                {:status :failed
                                 :error {:code :WORKER_PROGRAM_FAILED
                                         :message (or (ex-message t)
                                                      (str (class t)))}})))
                      result (deref job 120000 ::timeout)]
                  (mailbox/submit!
                   (:mailbox runtime)
                   (if (= ::timeout result)
                     {:type :worker/program-result
                      :job-id job-id
                      :outcome :outcome-unknown
                      :error {:code :WORKER_PROGRAM_TIMEOUT
                              :message "worker program exceeded 120s"}}
                     {:type :worker/program-result
                      :job-id job-id
                      :result result}))))))

          :cancel/resource
          (let [result (dispatch/cancel-resource! (:dispatcher runtime) entry)
                hook (:resource result)
                terminal (:terminal hook)]
            (when (false? (:ok? hook))
              (record-error! runtime
                             {:phase :cancel-resource
                              :entry entry
                              :error (or (:error hook)
                                         {:code :CANCEL_RESOURCE_FAILED
                                          :message "cancel resource hook failed"})}))
            ;; The runtime default hook acknowledges an unmanaged
            ;; settled/parked resource.  There is no dispatcher task left to
            ;; report an exit, so turn that explicit configuration fact into
            ;; one cancellation observation.  A live attempt (LIVE? true) is
            ;; never settled this way: its executor terminal is the only
            ;; evidence that the external resource ended.
            (when (and (true? (:acknowledged? hook))
                       (not (true? (:live? result)))
                       (nil? terminal))
              (let [call-id (or (:call-id entry) (:node-id entry))
                    attempt-id (:attempt-id entry)]
                (when call-id
                  (mailbox/submit!
                   (:mailbox runtime)
                   (cond-> {:type :call/terminal
                            :call-id call-id
                            :status :cancelled
                            :error {:kind :cancel-resource-unmanaged
                                    :message "no managed cancel hook; resource treated as unmanaged"}
                            :source :cancel-resource-default}
                     attempt-id (assoc :attempt-id attempt-id))))))
            ;; A settled/parked resource has no dispatcher task left to post
            ;; a terminal.  When the cancel hook observed a final result,
            ;; feed it back through the normal P1 late branch so the
            ;; attempt/call resource-alive flag can clear and finish-run can
            ;; proceed.
            ;;
            ;; Only an explicit hook terminal is evidence that the external
            ;; resource ended.  A missing dispatcher task, an empty hook
            ;; receipt, or a bare stop acknowledgement proves only that the
            ;; cancel signal was sent; the outcome stays `:outcome-unknown`
            ;; until the real executor reports its terminal.  Never
            ;; synthesize a terminal from the cancel request itself.
            ;; The terminal status comes only from the hook's explicit
            ;; observation.  Never copy the cancellation node's own state
            ;; (`:cancelled`/`:outcome-unknown`) into a terminal: an outcome
            ;; already recorded by the walk is not new evidence that a live
            ;; Shell/process ended.  `:outcome-unknown` is especially
            ;; dangerous because it is a business outcome, not a settled
            ;; resource.
            (when (and (map? terminal)
                       (some? (:status terminal)))
              (let [call-id (or (:call-id terminal) (:call-id entry))
                    attempt-id (or (:attempt-id terminal) (:attempt-id entry))
                    hook-status (let [status (:status terminal)
                                      status (cond
                                               (keyword? status)
                                               (keyword
                                                (str/lower-case (name status)))
                                               (string? status)
                                               (keyword (str/lower-case status))
                                               :else status)]
                                  (state/normalize-outcome status))
                    error (:error terminal)]
                (when call-id
                  (mailbox/submit!
                   (:mailbox runtime)
                   (cond-> {:type :call/terminal
                            :call-id call-id
                            :status hook-status
                            :payload (:payload terminal)
                            :error error
                            :source :cancel-resource}
                     attempt-id (assoc :attempt-id attempt-id)))))))

          :cancel/timer
          (dispatch/cancel-timer! (:dispatcher runtime) (:call-id entry))

          :retry/rearm
          (let [result (dispatch/start! (:dispatcher runtime)
                                        {:kind :timer
                                         :call-id (:call-id entry)
                                         :attempt-id (or (:attempt-id entry)
                                                         (str "retry-"
                                                              (:call-id entry)))
                                         :retry-at (:retry-at entry)})]
            (when-not (:ok? result)
              (record-error! runtime
                             {:phase :retry-rearm
                              :entry entry
                              :error (:error result)})))

          :release/resource
          (record-event! runtime entry)

          (record-event! runtime entry)))
      (reset! (:outbox-drained runtime)
              (long (max (long (deref delivered)) counter))))))

;; ---------------------------------------------------------------------------
;; Message processing

(defn- commit-session-change!
  "Persist a changed Owner session before the Owner state is committed.

  The session record is the business authority for lifecycle decisions such
  as purge eligibility.  A receipt must not say `:accepted` before that
  record holds the accepted change, so this barrier runs before the
  owner-state commit.  A port without `:commit-session!` cannot claim a
  durable business change."
  [runtime previous-state next-state now]
  (let [previous (get-in previous-state [:session])
        session (get-in next-state [:session])
        commit-fn (get-in (:config runtime) [:session-port :commit-session!])]
    (if (= previous session)
      {:ok? true :session session}
      (if-not (ifn? commit-fn)
        {:ok? false
         :error {:code :SESSION_PERSISTENCE_UNAVAILABLE
                 :message
                 "the Owner session port cannot persist a business change"}}
        (try
          (let [result (commit-fn previous session now)]
            (if (and (map? result)
                     (:ok? result)
                     (map? (:session result)))
              {:ok? true :session (:session result)}
              {:ok? false
               :unknown? (true? (:unknown? result))
               :error (or (:error result)
                          {:code :SESSION_COMMIT_FAILED
                           :message "the Owner session commit failed"})}))
          (catch Throwable error
            {:ok? false
             ;; The port may have written before throwing.  A refusal would
             ;; tell the client the business change definitely did not
             ;; happen; only resynchronization is safe.
             :unknown? true
             :error {:code :SESSION_COMMIT_FAILED
                     :message (str "the Owner session commit failed: "
                                   (.getMessage error))}}))))))

(defn- compact-state-for-commit
  "Compact large call/cancel content before a commit when the state is over
  the configured threshold.  A missing blob store leaves the state as-is."
  [runtime state]
  (let [store (get-in runtime [:config :blob-store])
        threshold (long (or (get-in runtime [:config :compact-threshold])
                            1000000))]
    (if (and store
             (:put! store)
             (> (count (pr-str state)) threshold))
      (first (compact/compact-state state store))
      state)))

(defn- commit-message!
  [runtime message now]
  (loop [attempt 0]
    (let [{:keys [version state]} @(:state runtime)
          result (handlers/apply-message state message (ctx runtime now))
          candidate (compact-state-for-commit runtime (:state result))]
      (cond
        (= state candidate)
        {:receipt (:receipt result)
         :version version
         :changed? false}

        :else
        (let [session-commit (commit-session-change!
                              runtime state candidate now)]
          (if-not (:ok? session-commit)
            (do
              (record-error! runtime {:phase :session-commit
                                      :message message
                                      :error (:error session-commit)})
              {:receipt (cond-> {:status (if (:unknown? session-commit)
                                          :unknown
                                          :refused)
                                 :error (:error session-commit)}
                          (:unknown? session-commit)
                          (assoc :action :resync-state))
               :unknown? (:unknown? session-commit)
               :version version
               :changed? false})
            (let [next-state (assoc-in candidate
                                       [:session]
                                       (:session session-commit))
                  commit (try
                           (store/commit! (:store runtime) version next-state)
                           (catch Throwable error
                             {:ok? false
                              :unknown? true
                              :error {:code :STORE_COMMIT_FAILED
                                      :message (or (ex-message error)
                                                   (str (class error)))}}))]
              (if (:ok? commit)
                (do
                  (reset! (:state runtime)
                          {:version (:version commit) :state next-state})
                  (drain-outbox! runtime)
                  {:receipt (:receipt result)
                   :version (:version commit)
                   :changed? true})
                (if (and (= :VERSION_CONFLICT (get-in commit [:error :code]))
                         (< attempt 8))
                  (do
                    (reload! runtime)
                    (recur (inc attempt)))
                  (do
                    (record-error! runtime {:phase :commit
                                            :message message
                                            :error (:error commit)})
                    ;; The session record may already hold the business
                    ;; change.  The overall outcome is therefore unknown, not
                    ;; a refusal: the client must resynchronize and read the
                    ;; committed session.
                    {:receipt {:status :unknown
                               :error (:error commit)
                               :action :resync-state}
                     :unknown? true
                     :version version
                     :changed? false}))))))))))

(def ^:private call-effect-kinds
  #{:model :tool :shell :workspace :devops})

(defn- effect-still-claimed?
  "True when EFFECT still belongs to the committed dispatching attempt.

  A recovered scheduler handshake or a replayed effect must not start work
  for a call that a later transition already canceled, failed, or retried.
  Timers and reports own their own claim slots."
  [runtime effect]
  (if (contains? call-effect-kinds (:kind effect))
    (let [call (get-in (:state @(:state runtime))
                       [:calls (:call-id effect)])]
      (and call
           (= (state/normalize-attempt-id (:attempt-id call))
              (state/normalize-attempt-id (or (:attempt-id effect)
                                              (:attempt-id call))))
           (contains? #{:dispatching :running} (:state call))))
    true))

(defn- stop-pending?
  "True when an operator stop is queued or the active run is stopping.

  A prepared handshake must not start external work after the stop command
  was committed.  The pending cancel guard owns the cancel subtree; the
  runtime only discards the stale dispatch opportunity."
  [runtime]
  (let [state (:state @(:state runtime))
        run-id (get-in state [:cancel :active-run-id])]
    (or (seq (:pending-cancels state))
        ;; Only :cancelling blocks.  A :cancelled run has no live resource
        ;; left; its successor run is opened by the next real user message,
        ;; so blocking here would discard every dispatch after a stop.
        (contains? #{:cancelling}
                   (get-in state [:cancel :nodes run-id :state])))))

(defn- stop-blocks-dispatch?
  "True when a pending handshake would start a call after an operator stop.

  Report and timer effects still run so their claims close; a model or
  executor effect is discarded and P0 cancellation owns the call subtree."
  [runtime scheduler]
  (and (stop-pending? runtime)
       (some #(contains? call-effect-kinds (:kind %))
             (get-in scheduler [:pending-dispatch :dispatch]))))

(defn- dispatch-effects!
  [runtime effects]
  (doseq [effect effects]
    (if-not (effect-still-claimed? runtime effect)
      (record-event! runtime {:type :dispatch/skipped
                              :effect effect
                              :reason :stale-claim})
      (let [effect (if (and (contains? #{:model :tool :shell :workspace
                                         :devops}
                                       (:kind effect))
                            (nil? (:attempt-id effect)))
                     (assoc effect
                            :attempt-id
                            (get-in (:state @(:state runtime))
                                    [:calls (:call-id effect) :attempt-id]))
                     effect)
            result (dispatch/start! (:dispatcher runtime) effect)]
        (when-not (:ok? result)
          (record-error! runtime {:phase :dispatch
                                  :effect effect
                                  :error (:error result)})
          ;; A claim must never stay `:dispatching` without a terminal
          ;; message.  The dispatcher posts most failures itself; this is the
          ;; fallback for any failure that did not.  The committed claim is
          ;; authoritative for the attempt id when the effect itself lacks
          ;; one.
          (when (and (not (:posted? result))
                     (not (contains? #{:report :timer} (:kind effect))))
            (let [call-id (:call-id effect)
                  attempt-id (or (:attempt-id effect)
                                 (get-in (:state @(:state runtime))
                                         [:calls call-id :attempt-id]))]
              (when (and call-id attempt-id)
                (mailbox/submit!
                 (:mailbox runtime)
                 {:type :call/terminal
                  :call-id call-id
                  :attempt-id attempt-id
                  :status :failed
                  :error (or (:error result)
                             {:kind :dispatch-failed
                              :message "dispatch could not start the effect"})}))))
          (when (and (= :report (:kind effect))
                     (not (:posted? result)))
            (mailbox/submit! (:mailbox runtime)
                             {:type :report/delivered
                              :report-id (:report-id effect)
                              :attempt-id (:attempt-id effect)
                              :ok? false
                              :error (:error result)})))))))

(defn- blocked-signature
  [diagnostic]
  [(:reason diagnostic) (:used diagnostic) (:rejected-guards diagnostic)])

(defn- record-blocked!
  [runtime diagnostic now]
  (let [signature (blocked-signature diagnostic)]
    (when (not= signature @(:last-blocked-signature runtime))
      (let [{:keys [version state]} @(:state runtime)
            state' (state/append-diagnostic state (assoc diagnostic :at now))
            commit (store/commit! (:store runtime) version state')]
        (if (:ok? commit)
          (do
            (reset! (:last-blocked-signature runtime) signature)
            (swap! (:stats runtime) update :blocked inc)
            (reset! (:state runtime) {:version (:version commit) :state state'})
            (record-event! runtime
                           {:type :owner/blocked
                            :diagnostic (assoc diagnostic :at now)}))
          (record-error! runtime {:phase :blocked-diagnostic
                                  :error (:error commit)}))))))

(defn- finish-round!
  [runtime scheduler-value]
  (try
    (scheduler/finish-round scheduler-value)
    (catch clojure.lang.ExceptionInfo error
      (record-error! runtime {:phase :finish-round
                              :error (ex-data error)})
      scheduler-value)))

(def ^:private wake-fallback-call-id
  "owner/wake-fallback")

(defn- schedule-wake-fallback!
  "Best-effort self-wake when the mailbox refuses a wake.

  A dispatcher timer posts `:timer/fire`; it is not a call claim and never
  synthesizes a call terminal.  RETURN true when a timer was scheduled."
  [runtime reason now]
  (if (or @(:closed? runtime)
          @(:closed? (:dispatcher runtime)))
    false
    (let [attempt-id (str "owner-wake-" (name reason) "-" (System/nanoTime))
          result (dispatch/start! (:dispatcher runtime)
                                  {:kind :timer
                                   :call-id wake-fallback-call-id
                                   :attempt-id attempt-id
                                   :retry-at (+ (long now) 25)})]
      (if (:ok? result)
        true
        (do
          (record-error! runtime
                         {:phase :wake-fallback
                          :reason reason
                          :error (:error result)})
          false)))))

(defn- submit-wake!
  "Submit one wake; fall back to a dispatcher timer if the mailbox refuses."
  [runtime reason now]
  (let [accepted (mailbox/submit! (:mailbox runtime)
                                  {:type :wake :reason reason})]
    (if (:ok? accepted)
      true
      (do
        (record-error! runtime
                       {:phase :wake-submit
                        :reason reason
                        :error (:error accepted)})
        (schedule-wake-fallback! runtime reason now)))))

(defn- run-guards!
  "Run guarded micro-actions until the scheduler stops.

  The scheduler quota bounds one round; the runtime never adds a second,
  invisible cap.  When the quota is exhausted the runtime finishes the round
  and enqueues a visible wake.  A rejected requeue is recorded, not dropped."
  ;; A prepared handshake is completed or abandoned explicitly.  The runtime
  ;; never logs `:awaiting-commit`/`:awaiting-dispatch` and then stops with a
  ;; pending dispatch, because that would leave a claimed call undispatchable.
  [runtime now]
  (loop [conflicts 0
         ;; A round that ends and is re-enqueued by the host carries this
         ;; flag.  See the `:drained`/`:idle` branch for why it is needed.
         recheck? false]
    (let [{:keys [version state]} @(:state runtime)
          current @(:scheduler runtime)
          result (try
                   (scheduler/step (:catalog runtime) state
                                   (assoc (guard-ctx runtime now)
                                          :timer-pending? (timer-pending? runtime)
                                          :round-exhausted? recheck?)
                                   current)
                   (catch Throwable error
                     (record-error! runtime
                                    {:phase :guard
                                     :error {:kind :guard-exception
                                             :message (str (.getMessage error))
                                             :class (.getName (class error))}})
                     nil))]
      (when result
        (case (:status result)
          :prepared
          (let [transition (:transition result)]
            (if (stop-blocks-dispatch? runtime (:scheduler result))
              ;; The transition is not committed.  Drop the handshake and
              ;; skip this guard for the rest of the round; otherwise the
              ;; level-triggered step would prepare the same claim again and
              ;; spin while the operator stop is pending.
              (do
                (reset! (:scheduler runtime)
                        (-> (scheduler/abandon-pending! (:scheduler result))
                            (update :rejected-this-round conj
                                    (:guard-id transition))))
                (record-event! runtime
                               {:type :dispatch/skipped
                                :guard-id (:guard-id transition)
                                :reason :stop-pending})
                (recur conflicts recheck?))
              (let [commit (store/commit! (:store runtime)
                                          version
                                          (:state transition))]
                (if (:ok? commit)
                  (do
                    (reset! (:state runtime)
                            {:version (:version commit)
                             :state (:state transition)})
                    (reset! (:last-blocked-signature runtime) nil)
                    (swap! (:stats runtime) update :guard-transitions inc)
                    (let [dispatched (scheduler/after-commit
                                      (:scheduler result)
                                      (:transition-id transition))]
                      (reset! (:scheduler runtime) (:scheduler dispatched))
                      (drain-outbox! runtime)
                      (dispatch-effects! runtime (:dispatch dispatched))
                      (let [acknowledged (scheduler/ack-dispatch
                                          (:scheduler dispatched)
                                          (:transition-id transition))]
                        (reset! (:scheduler runtime)
                                (:scheduler acknowledged))))
                    (recur 0 recheck?))
                  (if (and (= :VERSION_CONFLICT
                              (get-in commit [:error :code]))
                           (< conflicts 8))
                    (do (reload! runtime) (recur (inc conflicts) recheck?))
                    (do
                      (record-error! runtime
                                     {:phase :guard-commit
                                      :guard-id (:guard-id transition)
                                      :error (:error commit)})
                      (submit-wake! runtime :guard-commit-conflict now)
                      nil))))))

          :rejected
          (do
            (record-error! runtime
                           {:phase :guard-rejected
                            :guard-id (:guard-id result)
                            :error (:error result)})
            (reset! (:scheduler runtime) (:scheduler result))
            (swap! (:stats runtime) update :rejections inc)
            (recur conflicts recheck?))

          :quota-exhausted
          (do
            (reset! (:scheduler runtime)
                    (finish-round! runtime (:scheduler result)))
            (submit-wake! runtime :quota-requeue now)
            nil)

          (:drained :idle)
          ;; Exhausting the round is not a dead end.  A guard may have
          ;; committed a one-time transition and then become false for the
          ;; rest of this round.  When a timer is still pending or the round
          ;; just drained, ask once more before reporting that
          ;; `no timer is pending`.  A retained unsettled turn needs no extra
          ;; step here: settling it is one committed transition that moves the
          ;; guard's own cursor, so a later cause in the same round or the
          ;; next message reaches it without a second, invisible pass.
          (if (and (not recheck?)
                   (or (timer-pending? runtime)
                       (pos? (long (:used (:scheduler result) 0)))))
            (do
              (reset! (:scheduler runtime)
                      (finish-round! runtime (:scheduler result)))
              (recur conflicts true))
            (do
              (reset! (:scheduler runtime)
                      (finish-round! runtime (:scheduler result)))
              nil))

          :blocked
          (if (and (not recheck?)
                   (timer-pending? runtime))
            (do
              (reset! (:scheduler runtime)
                      (finish-round! runtime (:scheduler result)))
              (recur conflicts true))
            (do
              (record-blocked! runtime (:diagnostic result) now)
              (reset! (:scheduler runtime)
                      (finish-round! runtime (:scheduler result)))
              nil))

          :awaiting-dispatch
          ;; The host committed the transition but lost the ack (for example
          ;; after a store exception or an interrupted round).  Replay the
          ;; handshake; dispatch is idempotent by attempt id.
          (if (stop-blocks-dispatch? runtime current)
            (do
              (reset! (:scheduler runtime)
                      (scheduler/abandon-pending! current))
              ;; The transition was already committed; its outbox entries
              ;; (for example cancel/resource) still need to run even though
              ;; the stale dispatch is abandoned.
              (drain-outbox! runtime)
              (recur conflicts recheck?))
            (let [transition-id (:transition-id result)
                  dispatched (scheduler/after-commit current transition-id)]
              (reset! (:scheduler runtime) (:scheduler dispatched))
              (drain-outbox! runtime)
              (dispatch-effects! runtime (:dispatch dispatched))
              (let [acknowledged (scheduler/ack-dispatch
                                  (:scheduler dispatched)
                                  transition-id)]
                (reset! (:scheduler runtime) (:scheduler acknowledged)))
              (recur conflicts recheck?)))

          :awaiting-commit
          ;; The host prepared a transition but did not commit it.  The
          ;; scheduler kept the candidate state; commit it now when the
          ;; version still matches, otherwise abandon it and let the guard
          ;; re-prepare from the committed state.
          (if (stop-blocks-dispatch? runtime current)
            (do
              (reset! (:scheduler runtime)
                      (scheduler/abandon-pending! current))
              (recur conflicts recheck?))
            (let [transition-id (:transition-id result)
                  pending (:pending-dispatch current)
                  candidate (:state pending)
                  version (:version @(:state runtime))]
              (if (map? candidate)
              (let [commit (store/commit! (:store runtime) version candidate)]
                (if (:ok? commit)
                  (do
                    (reset! (:state runtime)
                            {:version (:version commit) :state candidate})
                    (swap! (:stats runtime) update :guard-transitions inc)
                    (let [dispatched (scheduler/after-commit
                                      current transition-id)]
                      (reset! (:scheduler runtime) (:scheduler dispatched))
                      (drain-outbox! runtime)
                      (dispatch-effects! runtime (:dispatch dispatched))
                      (let [acknowledged (scheduler/ack-dispatch
                                          (:scheduler dispatched)
                                          transition-id)]
                        (reset! (:scheduler runtime)
                                (:scheduler acknowledged))))
                    (recur conflicts recheck?))
                  (do
                    (record-error! runtime
                                   {:phase :scheduler-recovery
                                    :status :awaiting-commit
                                    :guard-id (:guard-id pending)
                                    :error (:error commit)})
                    (if (and (= :VERSION_CONFLICT
                                (get-in commit [:error :code]))
                             (< conflicts 8))
                      (let [loaded (reload! runtime)]
                        (if (= candidate (:state loaded))
                          ;; The store already contains this exact candidate;
                          ;; the host lost the commit result, not the commit.
                          (do
                            (let [dispatched (scheduler/after-commit
                                              current transition-id)]
                              (reset! (:scheduler runtime)
                                      (:scheduler dispatched))
                              (drain-outbox! runtime)
                              (dispatch-effects! runtime
                                                 (:dispatch dispatched))
                              (let [acknowledged (scheduler/ack-dispatch
                                                  (:scheduler dispatched)
                                                  transition-id)]
                                (reset! (:scheduler runtime)
                                        (:scheduler acknowledged))))
                            (recur conflicts recheck?))
                          (do
                            (reset! (:scheduler runtime)
                                    (scheduler/abandon-pending! current))
                            (recur (inc conflicts) recheck?))))
                      (do
                        (reset! (:scheduler runtime)
                                (scheduler/abandon-pending! current))
                        (submit-wake! runtime :scheduler-recovery now)
                        nil)))))
              (do
                (record-error! runtime
                               {:phase :scheduler-recovery
                                :status :awaiting-commit
                                :guard-id (:guard-id pending)
                                :error {:kind :pending-state-missing}})
                (reset! (:scheduler runtime)
                        (scheduler/abandon-pending! current))
                (submit-wake! runtime :scheduler-recovery now)
                nil))))

          (do
            (record-error! runtime {:phase :scheduler
                                    :status (:status result)
                                    :transition-id (:transition-id result)})
            nil))))))

;; ---------------------------------------------------------------------------
;; Notifications

(defn- notify!
  [runtime message receipt changed?]
  (let [{:keys [version state]} @(:state runtime)
        last-version @(:last-notified-version runtime)]
    (when (or changed? (not= version last-version))
      (reset! (:last-notified-version runtime) version)
      (try
        (.execute ^java.util.concurrent.Executor (:notifier runtime)
                  ^Runnable
                  (fn []
                    (let [notification {:type :owner/committed
                                        :version version
                                        :state state
                                        :message (redact-internal-env
                                                  message)
                                        :receipt receipt}]
                      (doseq [listener (vals @(:subscriptions runtime))]
                        (try
                          (listener notification)
                          (catch Throwable error
                            (record-error!
                             runtime
                             {:phase :subscriber
                              :error {:kind :subscriber-error
                                      :message (str (.getMessage error))
                                      :class (.getName (class error))}})))))))
        (catch java.util.concurrent.RejectedExecutionException _
          nil)))))

;; ---------------------------------------------------------------------------
;; Public runtime

(declare stats)

(defn- process!
  [runtime message]
  (mailbox/assert-owner-thread! (:mailbox runtime))
  (swap! (:stats runtime) update :messages inc)
  (let [now ((:clock runtime))
        outcome (atom nil)]
    (try
      (let [result (commit-message! runtime message now)]
        (reset! outcome result)
        ;; An unknown commit must not be followed by a guard round: the
        ;; authoritative state is not known here.  The client is told to
        ;; resynchronize instead.
        (when-not (:unknown? result)
          (run-guards! runtime now))
        (notify! runtime message (:receipt result) (:changed? result))
        (when-let [reply (:reply-to message)]
          (deliver reply (:receipt result)))
        result)
      (catch Throwable error
        (when-let [reply (:reply-to message)]
          ;; A transition that returned is committed (or explicitly
          ;; unknown); a later guard/notify failure must not become a
          ;; fabricated refusal.  A throw before commit-message! returned
          ;; is a genuine refusal.
          (deliver reply (if @outcome
                           {:status :unknown
                            :action :resync-state
                            :error {:code :OWNER_ERROR
                                    :message (str (.getMessage error))
                                    :class (.getName (class error))}}
                           {:status :refused
                            :error {:code :OWNER_ERROR
                                    :message (str (.getMessage error))
                                    :class (.getName (class error))}})))
        (throw error)))))

(defn- ensure-command-gate
  "Backfill the session command gate on a loaded or seeded Owner state.

  A restored or externally seeded owner-state document may predate the gate
  (or omit it).  Without a positive `:command-epoch` the client cannot submit
  any command, so the first attach sees a permanent 'waiting for epoch'
  state.  An existing valid gate is never replaced."
  [state registry]
  (let [gate (:commands state)
        valid? (and (map? gate)
                    (integer? (:command-epoch gate))
                    (pos? (long (:command-epoch gate)))
                    (integer? (:retired-through gate))
                    (not (neg? (long (:retired-through gate))))
                    (<= (long (:retired-through gate))
                        (long (:command-epoch gate))))]
    (if valid?
      state
      (assoc state :commands
             (commands/new-state
              (or registry (commands/local-registry)))))))

(defn- initial-snapshot
  [opts]
  (let [loaded (when (:store opts)
                 (store/load-state (:store opts)))
        registry (or (:registry opts) (commands/local-registry))
        state (cond
                (and loaded (:state loaded)) (:state loaded)
                (:initial-state opts) (:initial-state opts)
                :else
                (state/initial-state
                 (select-keys opts
                              [:session-id :user-input :user-attachments
                               :draft :plan-milestone
                               :retry-policy :config :now :registry :models
                               :successor-hint :initial-session
                               :initial-conversation
                               :owner-instance-id])))]
    {:version (:version loaded {:version 0})
     :state (ensure-command-gate state registry)}))

(defn- reconcile-session-state!
  "Reconcile the loaded Owner session with the committed session record.

  The owner-state document and the session record are separate commits.  A
  previous process could have accepted a metadata command and persisted only
  its own state, so the committed session record can lag.  The session port's
  reconciliation commits that lag before this Owner accepts new work.  A
  missing or unreadable committed record fails startup instead of serving a
  session the store no longer holds."
  [opts state]
  (let [reconcile-fn (get-in opts [:session-port :reconcile-session!])]
    (if (or (not (ifn? reconcile-fn))
            (nil? (:session state)))
      state
      (let [result (try
                     (reconcile-fn (:session state))
                     (catch Throwable error
                       {:ok? false
                        :error {:code :SESSION_RECONCILE_FAILED
                                :message
                                (str "session reconciliation failed: "
                                     (.getMessage error))}}))]
        (if (:ok? result)
          (assoc state :session (:session result))
          (throw (ex-info "committed session could not be reconciled"
                          {:code :SESSION_RECONCILE_FAILED
                           :error (:error result)})))))))

(defn start!
  "Start one Owner runtime.

  Options are the `start-owner!` deps plus `:ephemeral?`.  Returns the
  runtime map; `hc.compose.owner/start-owner!` wraps it in a public handle."
  [opts]
  (let [opts (update opts :owner-instance-id
                     #(or % (str (random-uuid))))
        store (or (:store opts)
                  (when (:ephemeral? opts)
                    (store/memory-store)))
        _ (when-not store
            (throw (ex-info "start-owner! needs a :store or :ephemeral? true"
                            {:code :STORE_REQUIRED})))
        opts (assoc opts :store store)
        registry (or (:registry opts) (commands/local-registry))
        stored (store/load-state store)
        from-store? (boolean (some? (:state stored)))
        initial (if from-store?
                  stored
                  (initial-snapshot (assoc opts :registry registry)))
        restored (when from-store?
                   (state/restore (:state initial)
                                  {:owner-instance-id (:owner-instance-id opts)}))
        ;; A seeded or restored owner-state may carry a mission document that
        ;; predates the current invariants.  Keep the committed session and
        ;; its history, but start a fresh mission relay instead of refusing
        ;; the session forever (the client would never receive an epoch).
        restore-failed? (and from-store? (not (:ok? restored)))
        initial (if restore-failed?
                  (assoc initial
                         :state
                         (state/initial-state
                          (select-keys opts
                                       [:session-id :user-input
                                        :user-attachments :draft
                                        :plan-milestone :retry-policy :config
                                        :now :registry :models
                                        :successor-hint :initial-session
                                       :initial-conversation
                                        :owner-instance-id])))
                  initial)
        ;; The outbox is advisory.  A restart must not replay effects that
        ;; belonged to the previous process; the dispatcher has no matching
        ;; attempts or timers, so the vector is cleared.  The sequence
        ;; counter keeps its committed value.  Resetting it would renumber
        ;; the next appended effect at 1, and a drain pointer left ahead of
        ;; that -- or a pointer read as position instead of identity --
        ;; would then skip the effect silently.  Monotonic numbering across
        ;; restarts is what lets the pointer stay a plain high-water mark.
        state-value (reconcile-session-state!
                     opts
                     (-> (if (and restored (:ok? restored))
                           (:state restored)
                           (:state initial))
                         (assoc :outbox [])))
        _ (when-not (store/valid-state? state-value)
            (throw (ex-info "initial Owner state is invalid"
                            {:code :INVALID_INITIAL_STATE
                             :error (store/invalid-state-error state-value)})))
        loaded (if from-store?
                 (assoc initial :state state-value)
                 (let [commit (store/commit! store (:version initial)
                                             state-value)]
                   (if (:ok? commit)
                     {:version (:version commit) :state state-value}
                     ;; Another writer won the initial race; load its state.
                     (let [current (store/load-state store)]
                       (when-not (:state current)
                         (throw (ex-info "cannot commit initial Owner state"
                                         {:code :INITIAL_COMMIT_FAILED
                                          :error (:error commit)})))
                       current))))
        runtime-ref (promise)
        box (mailbox/create
             {:id (:session-id opts)
              :handler (fn [message]
                         ((deref runtime-ref) message))
              :on-error (fn [report]
                          (record-error! (deref runtime-ref)
                                         (assoc report :phase :mailbox)))})
        dispatcher (dispatch/create
                    {:post! (fn [message] (mailbox/submit! box message))
                     :executors (:executors opts)
                     :model-gateway (:model-gateway opts)
                     :report-sink! (:report-sink! opts)
                     ;; A missing hook is a configuration fact, not a hard
                     ;; error: a settled or parked resource has no dispatcher
                     ;; task left to report, and failing here would leave
                     ;; `:resource-alive?` set forever.  The explicit
                     ;; `:acknowledged?` marker lets the outbox turn this
                     ;; configuration fact into one cancellation observation
                     ;; only when no live attempt owns the resource.  A host
                     ;; with a managed process must still pass a real hook.
                     :cancel-resource! (or (:cancel-resource! opts)
                                           (fn [_]
                                             {:ok? true
                                              :cancelled? true
                                              :acknowledged? true}))
                     :clock (:clock opts)})
        runtime {:session-id (:session-id state-value)
                 :store store
                 :mailbox box
                 :dispatcher dispatcher
                 :notifier (Executors/newSingleThreadExecutor
                            (daemon-thread-factory
                             (str "hc-owner-notify-" (:session-id opts))))
                 :state (atom loaded)
                 :scheduler (atom (scheduler/new-scheduler
                                   {:quota (or (:quota opts) default-quota)
                                    :attempt-prefix
                                    (str "attempt-"
                                         (java.util.UUID/randomUUID)
                                         "-")}))
                 :catalog (guards/catalog)
                 :subscriptions (atom {})
                 :sub-seq (atom 0)
                 :notifications (atom [])
                 :outbox-drained (atom 0)
                 :last-blocked-signature (atom nil)
                 :last-notified-version (atom -1)
                 :closed? (atom false)
                 :clock (or (:clock opts) now-ms)
                 :config {:registry registry
                          :session-port (:session-port opts)
                          :session-run-port (:session-run-port opts)
                          :attachment-contract (or (:attachment-contract opts)
                                                   {:resolve? false})
                          :attachment-policy (:attachment-policy opts)
                          :extension-tools-fn (:extension-tools-fn opts)
                          :skill-context-fn (:skill-context-fn opts)
                          :on-user-send! (:on-user-send! opts)
                          :on-call-terminal! (:on-call-terminal! opts)
                          :model-tools-supported (:model-tools-supported opts)
                          :model-tools-source (:model-tools-source opts)
                          :tool-result-renderer (:tool-result-renderer opts)
                          :outbound-context-fn (:outbound-context-fn opts)
                          :nudge-suppress (:nudge-suppress opts)
                          :worker-run-program-fn (:worker-run-program-fn opts)
                          :blob-store (:blob-store opts)
                          :compact-threshold (:compact-threshold opts)
                          :on-effect! (:on-effect! opts)
                          :on-error! (:on-error! opts)
                          :close-store? (not (false? (:close-store? opts)))}
                 :stats (atom {:messages 0
                               :errors 0
                               :guard-transitions 0
                               :rejections 0
                               :blocked 0
                               :last-error nil})}]
    (deliver runtime-ref (fn [message] (process! runtime message)))
    (mailbox/start! box)
    (mailbox/submit! box {:type :recover})
    runtime))

(defn submit!
  "Submit one message from any thread.  The mailbox serializes it."
  [runtime message]
  (if @(:closed? runtime)
    {:ok? false :error {:code :OWNER_CLOSED
                        :message "Owner is stopping"}}
    (mailbox/submit! (:mailbox runtime) message)))

(defn submit-command!
  "Submit one client command envelope and wait for its receipt.

  The envelope is `{:command-id .. :command-epoch .. :payload {:kind ..}}`.
  The Owner produces the receipt in its serial transition; this call waits
  on a private promise.  A timeout returns `:status :unknown` with
  `:action :resync-state`, never a fabricated acceptance."
  ([runtime request] (submit-command! runtime request 5000))
  ([runtime request timeout-ms]
   (let [reply (promise)
         accepted (submit! runtime (assoc request
                                          :type :command
                                          :reply-to reply))]
     (if-not (:ok? accepted)
       (assoc accepted :status :refused)
       (let [receipt (deref reply (long (max 0 timeout-ms)) ::timeout)]
         (cond
           (= ::timeout receipt)
           {:status :unknown
            :error {:code :COMMAND_TIMEOUT
                    :message "Owner did not produce a receipt in time"
                    :action :resync-state}
            :message-id (:message-id accepted)}

           (false? (:ok? receipt))
           (-> receipt
               (dissoc :ok?)
               (assoc :status :refused))

           (nil? (:status receipt))
           (assoc receipt :status :accepted)

           :else receipt))))))

(defn submit-board-command!
  "Execute one board command through the Owner and wait for its receipt.

  Board adapters use this instead of a second swarm kernel.  The Owner stays
  the only writer; the caller blocks on its own thread, never on the Owner
  thread.  Idempotency comes from the command's `:request-id` and the board
  transition rules."
  ;; A worker program can queue behind one protected apply or a model turn.
  ;; Five seconds was shorter than that queue and produced :BOARD_TIMEOUT for
  ;; a command the Owner later applied.  Thirty seconds covers the normal
  ;; queue; the caller still gets an explicit resync receipt on timeout.
  ([runtime command] (submit-board-command! runtime command 30000))
  ([runtime command timeout-ms]
   (submit-board-command! runtime command timeout-ms {}))
  ([runtime command timeout-ms opts]
   (let [reply (promise)
         ;; Only the workspace bridge can construct the opaque proof after it
         ;; actually holds W.  A wire boolean is never translated here.
         env (select-keys opts [:write-gate-proof :verify-read-set])
         accepted (submit! runtime {:type :board/command
                                    :command command
                                    :env env
                                    :reply-to reply})]
     (if-not (:ok? accepted)
       {:ok? false :error (:error accepted)}
       (let [receipt (deref reply (long (max 0 timeout-ms)) ::timeout)]
         (if (= ::timeout receipt)
           {:ok? false
            :status :unknown
            :action :resync-state
            :error {:code :BOARD_TIMEOUT
                    :message "Owner did not report the board command result in time; re-read the board snapshot before retrying"}}
           receipt))))))

(defn subscribe!
  [runtime listener]
  (when-not (ifn? listener)
    (throw (ex-info "subscriber must be callable"
                    {:code :INVALID_SUBSCRIBER})))
  (let [id (swap! (:sub-seq runtime) inc)]
    (swap! (:subscriptions runtime) assoc id listener)
    (fn unsubscribe []
      (swap! (:subscriptions runtime) dissoc id)
      true)))

(defn snapshot
  [runtime]
  (let [{:keys [version state]} @(:state runtime)]
    {:version version
     :state state
     :stats (stats runtime)}))

(defn stats
  [runtime]
  (let [{:keys [version]} @(:state runtime)]
    (merge @(:stats runtime)
           {:version version
            :running? (not @(:closed? runtime))
            :queued (get (mailbox/stats (:mailbox runtime)) :queued 0)
            :mailbox (mailbox/stats (:mailbox runtime))
            :dispatch (dispatch/stats (:dispatcher runtime))})))

(defn await-idle!
  "Wait until the mailbox is empty, no attempt is live, and the state has
  settled.  A timeout is not an error."
  [runtime timeout-ms]
  (let [deadline (+ (now-ms) (long (max 0 timeout-ms)))]
    (loop []
      (let [box (mailbox/await-idle! (:mailbox runtime) 50)
            live (dispatch/live-attempts (:dispatcher runtime))
            idle? (and (:idle? box) (empty? live))]
        (cond
          idle? {:idle? true
                 :version (:version @(:state runtime))
                 :live 0}
          (<= (now-ms) deadline)
          (do (Thread/sleep 5) (recur))
          :else {:idle? false
                 :version (:version @(:state runtime))
                 :queued (:queued box)
                 :live (count live)})))))

(defn stop!
  "Stop accepting work, cancel live attempts, drain accepted messages, and
  close the store.  The Owner thread is never interrupted."
  ([runtime] (stop! runtime {}))
  ([runtime {:keys [timeout-ms] :as opts :or {timeout-ms 5000}}]
   ;; The CAS is the first mutation.  A `reset!` before it would make every
   ;; stop look like a replay, so `store/close!` could never run.
   (let [first-stop? (compare-and-set! (:closed? runtime) false true)]
     ;; External `submit!` already refuses because `:closed?` is set, but the
     ;; mailbox must still accept the dispatcher's internal terminals while
     ;; the cancellation domain settles.  Drain advisory effects first so a
     ;; pending `:cancel/resource` is not lost at shutdown.
     (drain-outbox! runtime)
     (let [cancelled (dispatch/stop! (:dispatcher runtime)
                                     :executor-shutdown)]
       ;; Let cancellation callbacks enqueue their terminal/settled notices
       ;; while admission is still open.
       (let [deadline (+ (now-ms) (long (min 2000 timeout-ms)))]
         (loop []
           (when (and (seq (dispatch/live-attempts (:dispatcher runtime)))
                      (< (now-ms) deadline))
             (Thread/sleep 5)
             (recur))))
       (mailbox/close-admission! (:mailbox runtime))
       (let [box (mailbox/stop! (:mailbox runtime)
                                {:timeout-ms timeout-ms})]
         (.shutdown ^java.util.concurrent.ExecutorService
                    (:notifier runtime))
         (.awaitTermination ^java.util.concurrent.ExecutorService
                            (:notifier runtime) 2 TimeUnit/SECONDS)
         (let [close? (if (contains? opts :close-store?)
                        (:close-store? opts)
                        (:close-store? (:config runtime)))
               ;; A mailbox that did not stop may still commit.  Closing the
               ;; store under a live handler would turn a legitimate commit
               ;; into `:STORE_CLOSED`; leave it open and report why.
               close-result (when (and first-stop? close?
                                       (:stopped? box))
                              (store/close! (:store runtime)))]
           {:stopped? (:stopped? box)
            :drained? (:drained? box)
            :cancelled (:cancelled cancelled)
            :version (:version @(:state runtime))
            :first-stop? first-stop?
            :store close-result
            :close-skipped (when (and first-stop? close?
                                      (not (:stopped? box)))
                             :mailbox-alive)
            :stats (stats runtime)}))))))
