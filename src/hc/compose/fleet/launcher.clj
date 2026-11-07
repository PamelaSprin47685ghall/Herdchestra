(ns hc.compose.fleet.launcher
  "Thin sequencing adapter from fleet actor effects to an injected model
  gateway.

  This namespace does not start models, run tools, or claim that a swarm is
  alive.  `make` returns the `:launch-swarm!` function shape expected by
  `hc.compose.fleet.service`.  It forwards every real `:model/start` effect
  to the application's `:start-effect!` function and returns success only if
  all calls return without throwing.

  The application still owns the model gateway, the per-worktree clj-worker
  tool loop, and the later `service/accept-actor-event!` /
  `service/wake-actors!` calls.  A helper that pretends to provide those
  would be a fake executor.")

(defn- effect-failure?
  [result]
  (or (false? result)
      (and (map? result)
           (or (false? (:ok? result))
               (= :failed (:status result))
               (some? (:error result))))))

(defn- fail
  [code message details]
  {:status :failed
   :error (cond-> {:code code :message message}
            (some? details) (assoc :details details))})

(defn make
  "Build a `:launch-swarm!` function from an injected `:start-effect!`.

  `:start-effect!` receives one real effect map plus `:swarm-id`:

    {:type :model/start
     :swarm-id \"swarm-1\"
     :tenure-id \"fleet-swarm-1\"
     :slot-id :slot-a
     :attempt-id \"swarm-fleet-swarm-1-slot-a-1\"
     :request {... :workspace ... :board-snapshot ...}}

  The function must start the real model request and later route events
  through `service/accept-actor-event!`; the launcher cannot do that for it.
  `:cancel-effect!` receives `:model/cancel` effects.  It defaults to
  `:start-effect!` so a host that branches on `:type` can use one function;
  a cancel failure is recorded but does not turn a successful start into a
  false launch failure.
  `:on-effect!` is an optional observability hook for every effect,
  including `:actor/rebirth`.  Its exceptions are ignored so diagnostics
  cannot turn a successful start into a false failure.

  The returned function returns `{:ok? true :swarm-id ... :started n}` on
  success, or `{:status :failed :error {...}}` if `:start-effect!` throws or
  returns `false`/a failure map.  The service treats the latter as an explicit
  launch failure."
  [{:keys [start-effect! cancel-effect! on-effect!]}]
  (when-not (fn? start-effect!)
    (throw (ex-info "fleet launcher requires :start-effect!"
                    {:error :START_EFFECT_REQUIRED})))
  (fn [{:keys [swarm-id actor]}]
    (if-not actor
      (fail :SWARM_ACTOR_MISSING
            "launcher requires actor state from the fleet service"
            {:swarm-id swarm-id})
      (let [effects (vec (:effects actor))
            model-effects (filter #(= :model/start (:type %)) effects)
            cancel-effects (filter #(= :model/cancel (:type %)) effects)
            cancel! (or cancel-effect! start-effect!)
            cancel-errors (atom [])]
        (try
          (doseq [effect effects]
            (when on-effect!
              (try
                (on-effect! (assoc effect :swarm-id swarm-id))
                (catch Throwable _ nil))))
          (doseq [effect cancel-effects]
            (try
              (let [result (cancel! (assoc effect :swarm-id swarm-id))]
                (when (effect-failure? result)
                  (swap! cancel-errors conj
                         {:attempt-id (:attempt-id effect)
                          :result result})))
              (catch Throwable t
                (swap! cancel-errors conj
                       {:attempt-id (:attempt-id effect)
                        :error {:message (or (.getMessage t) (str t))}}))))
          (doseq [effect model-effects]
            (let [result (start-effect! (assoc effect :swarm-id swarm-id))]
              (when (effect-failure? result)
                (throw (ex-info "model gateway rejected a swarm start effect"
                                {:result result
                                 :attempt-id (:attempt-id effect)})))))
          {:ok? true
           :swarm-id swarm-id
           :started (count model-effects)
           :cancelled (count cancel-effects)
           :cancel-errors @cancel-errors
           :effects (count effects)}
          (catch Throwable t
            (fail :SWARM_MODEL_START_FAILED
                  "the injected model gateway rejected a swarm start effect"
                  {:swarm-id swarm-id
                   :message (or (.getMessage t) (str t))})))))))
