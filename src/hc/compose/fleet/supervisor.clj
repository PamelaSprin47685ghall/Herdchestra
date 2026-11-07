(ns hc.compose.fleet.supervisor
  "Fleet-owned launch/event/conflict supervisor.

  This namespace owns the mechanical loop around the per-worktree actor
  state.  It does not implement a model provider or a clj-worker evaluator;
  both are injected:

  - `:start-effect!` starts one real `:model/start` request;
  - `:execute-tool!` runs one tool call against the selected worktree
    runtime (the app should use `hc.compose.mission.worktree`);
  - `:resolve-conflict!` asks the named holder to resolve a rebase conflict;
    absent or refusing means abandon that swarm only.

  `make` returns a supervisor with a real `:launch-swarm!` function.  The
  service can be attached after construction through `:service-ref`, which
  avoids a constructor cycle:

      (def sup (supervisor/make {:service-ref ref ...}))
      (def svc (service/start! {... :launch-swarm! (:launch sup) ...}))
      (reset! ref svc)

  The supervisor never marks a swarm running; the service/control does that
  only after the launcher returns success.")

(defn- fail
  [code message details]
  {:ok? false
   :error (cond-> {:code code :message message}
            (some? details) (assoc :details details))})

(defn mission-role
  "Return the mission role (`:worker`/`:holder`) of a model request or call.

  `mission.actors/model-request` exposes the provider-facing model role in
  `:role` (`:main`/`:task`) and the mission role in `:mission-role`.  A
  call record stores the mission role directly.  Callers must not infer a
  holder from the model role."
  [request]
  (or (:mission-role request) (:role request)))

(defn- service-of
  [supervisor]
  (let [ref (:service-ref supervisor)
        value (if (instance? clojure.lang.IDeref ref) @ref ref)]
    value))

(defn- record-attempt!
  [supervisor effect]
  (when-let [attempt-id (:attempt-id effect)]
    (swap! (:attempts supervisor) assoc attempt-id
           {:swarm-id (:swarm-id effect)
            :slot-id (:slot-id effect)
            :tenure-id (:tenure-id effect)}))
  effect)

(defn- rejected-effect?
  [result]
  (or (false? result)
      (and (map? result)
           (or (false? (:ok? result))
               (= :failed (:status result))
               (some? (:error result))))))

(defn- start-effects!
  "Start real model requests and forward real cancellation effects.

  `mission.actors/sync-tenure` emits `:model/cancel` when a dead slot is
  reborn.  The supervisor must forward that signal to the injected cancel
  function; otherwise the old provider stream keeps running after its actor
  is gone.  Cancellation is best effort: a refused cancel is recorded as
  evidence but does not block the starts in the same batch.  A refused start
  still fails the whole launch, as before."
  [supervisor swarm-id effects]
  (try
    (let [started (atom 0)
          cancelled (atom 0)
          cancel-errors (atom [])]
      (doseq [effect effects]
        (when-let [on-effect! (:on-effect! supervisor)]
          (try (on-effect! (assoc effect :swarm-id swarm-id))
               (catch Throwable _ nil)))
        (case (:type effect)
          :model/start
          (let [started-result ((:start-effect! supervisor)
                                (record-attempt! supervisor
                                                 (assoc effect :swarm-id swarm-id)))]
            (when (rejected-effect? started-result)
              (throw (ex-info "the model gateway rejected a swarm start effect"
                              {:swarm-id swarm-id
                               :effect effect
                               :result started-result})))
            (swap! started inc))

          :model/cancel
          (let [cancel! (or (:cancel-effect! supervisor)
                            (:start-effect! supervisor))]
            (try
              (let [result (cancel! (assoc effect :swarm-id swarm-id))]
                (swap! cancelled inc)
                (when (rejected-effect? result)
                  (swap! cancel-errors conj
                         {:attempt-id (:attempt-id effect)
                          :result result})))
              (catch Throwable t
                (swap! cancel-errors conj
                       {:attempt-id (:attempt-id effect)
                        :error {:message (or (.getMessage t) (str t))
                                :class (.getName (class t))}}))))

          nil))
      {:ok? true
       :swarm-id swarm-id
       :started @started
       :cancelled @cancelled
       :cancel-errors @cancel-errors})
    (catch Throwable t
      (let [data (ex-data t)
            effect (:effect data)]
        (fail :SWARM_MODEL_START_FAILED
              "the injected model gateway rejected a swarm start effect"
              (cond-> {:swarm-id swarm-id
                       :message (or (.getMessage t) (str t))}
                (some? effect)
                (assoc :effect effect
                       :attempt-id (:attempt-id effect))

                (contains? data :result)
                (assoc :result (:result data))))))))

(declare handle-event!)

(defn make
  "Build a supervisor.

  Required: `:service-ref` (atom or service map) and `:start-effect!`.
  Optional: `:cancel-effect!` (defaults to `:start-effect!`),
  `:execute-tool!`, `:resolve-conflict!`, `:on-effect!`.
  The real fleet service exposes `:claim-tool-call!`; the supervisor calls
  it before `:execute-tool!` so one model attempt cannot run the same tool
  side effect twice.

  The returned map exposes:
  - `:launch` — the `:launch-swarm!` function for `service/start!`;
  - `:handle-event!` — route one model event for a swarm;
  - `:resolve-conflict!` — conflict callback for `service/start!`;
  - `:attempts` — attempt-id -> swarm/slot/tenure index."
  [{:keys [service-ref start-effect! cancel-effect! execute-tool!
           resolve-conflict! on-effect!]}]
  (when-not (fn? start-effect!)
    (throw (ex-info "fleet supervisor requires :start-effect!"
                    {:error :START_EFFECT_REQUIRED})))
  (let [attempts (atom {})
        services (atom {})
        self (atom nil)
        base {:service-ref service-ref
              :start-effect! start-effect!
              :cancel-effect! cancel-effect!
              :execute-tool! execute-tool!
              :resolve-conflict! resolve-conflict!
              :on-effect! on-effect!
              :attempts attempts
              :services services
              :stopped? (atom false)
              :handle-event! (fn [swarm-id event]
                               (handle-event! @self swarm-id event))}
        launch (fn [{:keys [swarm-id actor service]}]
                 ;; The application publishes its service ref only after
                 ;; `service/start!` returns.  A restored running swarm may
                 ;; start a model turn during that call, so retain the live
                 ;; service with the launched swarm and use it until the
                 ;; global ref is available.
                 (when service
                   (swap! services assoc swarm-id service))
                 (if (or (nil? actor)
                         (and (nil? (:state actor))
                              (empty? (:effects actor))))
                   (fail :SWARM_ACTOR_MISSING
                         "launcher requires non-empty actor state from the fleet service"
                         {:swarm-id swarm-id})
                   (start-effects! base swarm-id (:effects actor))))
        start-all (fn [effects]
                    (let [results (mapv (fn [[swarm-id es]]
                                          (start-effects! base swarm-id es))
                                        (group-by :swarm-id effects))]
                      {:ok? (every? :ok? results)
                       :results results}))
        supervisor (assoc base :launch launch :start-effects! start-all)]
    (reset! self supervisor)
    supervisor))

(defn- wake!
  [supervisor swarm-id]
  (let [service (service-of supervisor)]
    (if (and service (:wake-actors! service))
      ((:wake-actors! service) swarm-id)
      {:ok? true :effects []})))

(defn handle-event!
  "Route one model event for SWARM-ID.

  - `:chunk` updates the real actor call record;
  - `:completed` updates the actor and starts the next idle turns;
  - `:failed`/`:cancelled` update the actor and do not start a new turn;
  - `:tool-call` first reserves the call through the service
    (`:claim-tool-call!`), then delegates to `:execute-tool!` and feeds the
    result back through `:continue-actor-after-tool!`.  The app's model
    gateway remains responsible for the model conversation.  A terminal
    completion that already published tool calls is deferred to that
    continuation instead of clearing the slot."
  [supervisor swarm-id event]
  (let [service (or (service-of supervisor)
                    (get @(:services supervisor) swarm-id))
        attempt-id (:attempt-id event)
        known (get @(:attempts supervisor) attempt-id)]
    (cond
      @(:stopped? supervisor)
      (fail :SUPERVISOR_STOPPED
            "the fleet supervisor is stopped; late events are diagnostic only"
            {:swarm-id swarm-id :attempt-id attempt-id})

      (nil? service)
      (fail :SERVICE_NOT_ATTACHED
            "the fleet service has not been attached to the supervisor"
            {:swarm-id swarm-id})

      (and attempt-id (not= swarm-id (:swarm-id known)) known)
      (fail :ATTEMPT_SWARM_MISMATCH
            "the attempt belongs to another swarm"
            {:attempt-id attempt-id
             :event-swarm swarm-id
             :attempt-swarm (:swarm-id known)})

      (= :tool-call (:event event))
      (if-let [execute-tool! (:execute-tool! supervisor)]
        ;; The real service always supplies :claim-tool-call!.  A minimal
        ;; test service may omit it and keep the older check-then-execute
        ;; behavior; production never takes that path.
        (let [claim! (:claim-tool-call! service)
              claimed (when claim!
                        (claim! swarm-id attempt-id event))]
          (if (and claim! (not (:ok? claimed)))
            (fail (get-in claimed [:error :code] :TOOL_CALL_REJECTED)
                  (get-in claimed [:error :message]
                          "the actor rejected the tool call")
                  (merge {:swarm-id swarm-id :attempt-id attempt-id}
                         (:error claimed)))
            (let [runtime ((:runtime service) swarm-id)
                  actor-state (get-in runtime [:actor :state])
                  call (get-in actor-state [:actors :calls attempt-id])
                  ;; The fleet actor state nests both calls and slots under
                  ;; :actors.  A lookup that omits it makes every tool call
                  ;; look stale before the executor runs.
                  slot (get-in actor-state
                               [:actors :tenures (:tenure-id call)
                                :slots (:slot-id call)])
                  live-call? (and call
                                  (= attempt-id (:attempt-id call))
                                  slot
                                  (= attempt-id (:attempt-id slot))
                                  (= (:slot-id call) (:slot-id slot))
                                  (= (:agent-id call) (:agent-id slot))
                                  (= :running (:status slot)))]
              (if-not live-call?
                (fail :UNKNOWN_ATTEMPT
                      "no live actor call owns this tool event"
                      {:swarm-id swarm-id :attempt-id attempt-id})
                (let [tool-result
                      (try
                        (execute-tool! {:swarm-id swarm-id
                                        :runtime runtime
                                        :actor-state actor-state
                                        :attempt call
                                        :slot slot
                                        :mission-role (mission-role call)
                                        :board-snapshot
                                        (when-let [snapshot (:board-snapshot service)]
                                          (snapshot swarm-id))
                                        :event event})
                        (catch Throwable t
                          {:status :failed
                           :error {:code :TOOL_EXECUTION_FAILED
                                   :message (or (.getMessage t) (str t))
                                   :swarm-id swarm-id}}))
                      continue! (:continue-actor-after-tool! service)
                      continued (when continue!
                                  (continue! swarm-id attempt-id
                                             event tool-result))]
                  (cond
                    (nil? continue!)
                    (fail :TOOL_CONTINUATION_MISSING
                          "the service cannot continue the actor after a tool result"
                          {:swarm-id swarm-id :attempt-id attempt-id})

                    (not (:ok? continued))
                    (fail (get-in continued [:error :code]
                                  :TOOL_CONTINUATION_FAILED)
                          (get-in continued [:error :message]
                                  "the actor could not continue after the tool result")
                          {:swarm-id swarm-id :attempt-id attempt-id})

                    :else
                    (let [started (start-effects! supervisor swarm-id
                                                  (:effects continued))]
                      {:ok? true
                       :swarm-id swarm-id
                       :tool-result tool-result
                       :continued continued
                       :started started})))))))
        (fail :TOOL_EXECUTOR_MISSING
              "no tool executor is attached to the supervisor"
              {:swarm-id swarm-id}))

      (and (= :completed (:event event))
           (seq (:published-call-ids event)))
      ;; The model turn ended in tool calls.  The :tool-call path owns the
      ;; continuation; accepting this terminal would clear the slot before the
      ;; tool result is recorded.
      {:ok? true
       :swarm-id swarm-id
       :deferred :tool-call
       :accepted {:ok? true :effects []}}

      (contains? #{:chunk :completed :failed :cancelled} (:event event))
      (let [accepted ((:accept-actor-event! service)
                      swarm-id attempt-id event)]
        (if-not (:ok? accepted)
          (fail (get-in accepted [:error :code] :ACTOR_EVENT_REJECTED)
                (get-in accepted [:error :message] "actor event was rejected")
                {:swarm-id swarm-id :event (:event event)})
          (if (= :completed (:event event))
            (let [woken (wake! supervisor swarm-id)
                  started (if (:ok? woken)
                            (start-effects! supervisor swarm-id
                                            (:effects woken))
                            woken)]
              {:ok? true
               :swarm-id swarm-id
               :accepted accepted
               :wake woken
               :started started})
            {:ok? true
             :swarm-id swarm-id
             :accepted accepted})))

      :else
      (fail :UNKNOWN_MODEL_EVENT
            "unsupported model event"
            {:swarm-id swarm-id :event (:event event)}))))

(defn resolve-conflict!
  "Conflict callback for `service/start!`.

  Delegates to the injected holder resolver with the swarm/runtime context.
  An absent resolver, a throw, or a non-resolved decision abandons only that
  swarm; it never touches a sibling."
  [supervisor request]
  (if-let [resolver (:resolve-conflict! supervisor)]
    (try
      (let [service (service-of supervisor)
            runtime (when (and service (:runtime service))
                      ((:runtime service) (:swarm-id request)))
            decision (resolver (assoc request :runtime runtime))]
        (if (or (true? decision)
                (= :resolved decision)
                (= :resolved (:status decision)))
          decision
          (or decision :abandon)))
      (catch Throwable t
        {:status :abandoned
         :reason {:code :CONFLICT_RESOLVER_FAILED
                  :message (or (.getMessage t) (str t))
                  :swarm-id (:swarm-id request)}}))
    {:status :abandoned
     :reason {:code :CONFLICT_RESOLVER_UNAVAILABLE
              :swarm-id (:swarm-id request)}}))

(defn stop!
  "Stop the supervisor and cancel every indexed attempt.

  `:cancel-attempt!` is an optional injected function receiving one
  attempt-id.  After this call, `handle-event!` refuses late events, so no
  fleet writer can mutate state after shutdown.  Returns
  `{:stopped? true :cancelled n}`; cancellation confirmation remains the
  model port's responsibility."
  [supervisor {:keys [cancel-attempt!]}]
  (let [attempt-ids (vec (keys @(:attempts supervisor)))
        cancelled (reduce (fn [n attempt-id]
                            (if cancel-attempt!
                              (try
                                (cancel-attempt! attempt-id)
                                (inc n)
                                (catch Throwable _ n))
                              n))
                          0
                          attempt-ids)]
    (reset! (:stopped? supervisor) true)
    (reset! (:attempts supervisor) {})
    (reset! (:services supervisor) {})
    {:stopped? true
     :cancelled cancelled
     :attempts (count attempt-ids)}))
