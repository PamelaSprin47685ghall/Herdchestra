(ns hc.server.app.fleet-gc
  "Application-owned periodic retention GC for fleet worktrees.

  The application starts one scheduler while its fleet service is live.  Each
  tick calls `hc.compose.fleet.service/gc!`; eligibility stays in the fleet
  control: only registered terminal swarms whose explicit `:gc-after`
  deadline elapsed and whose workspace is the exact system-managed path are
  removed.  A running or queued swarm is never a candidate.

  Timing uses a fixed delay, so a wall-clock jump cannot produce a burst of
  ticks.  One lock serializes timer and manual ticks.  `stop!` flips the
  stopped flag, shuts the executor down, and waits for an in-flight tick; no
  tick starts after it returns.  The scheduler thread is a daemon, so a
  forgotten handle cannot keep the JVM alive.

  The scheduler is deliberately separate from `hc.compose.fleet.service`:
  the fleet control stays a mechanical, explicit API, and the application
  owns the process timer that calls it."
  (:import (java.util.concurrent Executors ScheduledExecutorService
                                 ThreadFactory TimeUnit)
           (java.util.concurrent.atomic AtomicLong)))

(def default-interval-ms
  "One hour.  Retention is measured in days, so an hourly sweep frees a
  worktree soon after its deadline without polling Git aggressively."
  (* 60 60 1000))

(def default-stop-timeout-ms
  "How long `stop!` waits for an in-flight sweep before interrupting it."
  5000)

(defn- fail
  [code message details]
  {:ok? false
   :error (cond-> {:code code :message message}
            (some? details) (assoc :details details))})

(defn- daemon-thread-factory
  [prefix]
  (let [counter (AtomicLong.)]
    (reify ThreadFactory
      (newThread [_ runnable]
        (doto (Thread. ^Runnable runnable
                       (str prefix "-" (.incrementAndGet counter)))
          (.setDaemon true))))))

(defn- new-runtime
  [{:keys [service interval-ms clock stop-timeout-ms]}]
  {:service service
   :interval-ms (long interval-ms)
   :clock clock
   :stop-timeout-ms (long stop-timeout-ms)
   :executor nil
   :stopped? (atom false)
   :tick-lock (Object.)
   :state (atom {:tick-count 0
                 :last-tick-at nil
                 :last-result nil
                 :last-error nil})})

(defn- result-summary
  "Keep scheduler evidence bounded.  The full control state stays in the
  control; the timer must not retain every report from the last sweep."
  [result]
  (select-keys result [:ok? :interrupted? :removed :failed :error]))

(defn- run-sweep
  [runtime]
  (let [at ((:clock runtime))
        result (try
                 ((:gc! (:service runtime)) (:service runtime))
                 (catch Throwable t
                   (fail :FLEET_GC_TICK_FAILED
                         "the fleet GC sweep failed"
                         {:message (or (.getMessage t) (str t))})))
        result (if (map? result)
                 result
                 (fail :FLEET_GC_RESULT_INVALID
                       "the fleet GC sweep did not return a result map"
                       {:result result}))]
    (swap! (:state runtime)
           (fn [state]
             (-> state
                 (update :tick-count inc)
                 (assoc :last-tick-at at
                        :last-result (result-summary result)
                        :last-error (cond
                                      (:interrupted? result)
                                      {:code :FLEET_GC_INTERRUPTED
                                       :message "the fleet GC sweep was interrupted"}

                                      (not (:ok? result))
                                      (:error result)

                                      :else nil)))))
    result))

(defn tick-once!
  "Run one GC sweep unless the scheduler has stopped.

  Timer and manual calls are serialized by one lock.  A stopped scheduler
  refuses a late tick instead of removing a worktree after application
  shutdown began."
  [runtime]
  (if @(:stopped? runtime)
    (fail :FLEET_GC_STOPPED "the fleet GC scheduler is stopped" nil)
    (locking (:tick-lock runtime)
      (if @(:stopped? runtime)
        (fail :FLEET_GC_STOPPED "the fleet GC scheduler is stopped" nil)
        (run-sweep runtime)))))

(defn tick-now!
  "Run one sweep immediately, serialized with the timer tick."
  [runtime]
  (tick-once! runtime))

(defn start!
  "Start the periodic fleet GC scheduler.

  Required: `:service`, a fleet service exposing `gc!`.

  Options:
  - `:interval-ms` fixed delay between sweeps (default one hour);
  - `:clock` epoch-ms function used for recorded tick times;
  - `:stop-timeout-ms` how long `stop!` waits for an in-flight sweep;
  - `:run-immediately?` run the first sweep at start (default true).

  Returns `{:ok? true :gc runtime}` or `{:ok? false :error ...}`."
  [{:keys [service interval-ms clock stop-timeout-ms run-immediately?]}]
  (let [interval-ms (or interval-ms default-interval-ms)
        clock (or clock #(System/currentTimeMillis))
        stop-timeout-ms (or stop-timeout-ms default-stop-timeout-ms)
        run-immediately? (if (nil? run-immediately?) true run-immediately?)]
    (cond
      (not (and (map? service) (fn? (:gc! service))))
      (fail :FLEET_GC_SERVICE_REQUIRED
            "fleet GC needs a service exposing gc!"
            {:service? (some? service)})

      (not (and (integer? interval-ms) (pos? interval-ms)))
      (fail :FLEET_GC_INTERVAL_INVALID
            "fleet GC interval-ms must be a positive integer"
            {:interval-ms interval-ms})

      (not (and (integer? stop-timeout-ms) (not (neg? stop-timeout-ms))))
      (fail :FLEET_GC_STOP_TIMEOUT_INVALID
            "fleet GC stop-timeout-ms must be a non-negative integer"
            {:stop-timeout-ms stop-timeout-ms})

      (not (fn? clock))
      (fail :FLEET_GC_CLOCK_INVALID
            "fleet GC clock must be a function"
            {:clock clock})

      :else
      (let [runtime (new-runtime {:service service
                                  :interval-ms interval-ms
                                  :clock clock
                                  :stop-timeout-ms stop-timeout-ms})
            executor (Executors/newSingleThreadScheduledExecutor
                      (daemon-thread-factory "hc-fleet-gc"))
            runtime (assoc runtime :executor executor)]
        (try
          (.scheduleWithFixedDelay
           ^ScheduledExecutorService executor
           ^Runnable (fn [] (tick-once! runtime))
           (long (if run-immediately? 0 interval-ms))
           (long interval-ms)
           TimeUnit/MILLISECONDS)
          {:ok? true :gc runtime}
          (catch Throwable t
            (.shutdownNow executor)
            (fail :FLEET_GC_TIMER_FAILED
                  "the fleet GC timer could not start"
                  {:message (or (.getMessage t) (str t))})))))))

(defn status
  "Return scheduler facts without running a sweep, or nil for no scheduler."
  [runtime]
  (when runtime
    (let [state @(:state runtime)
          ^ScheduledExecutorService executor (:executor runtime)]
      {:running? (boolean (and executor (not (.isShutdown executor))))
       :stopped? (boolean @(:stopped? runtime))
       :terminated? (boolean (and executor (.isTerminated executor)))
       :interval-ms (:interval-ms runtime)
       :tick-count (:tick-count state)
       :last-tick-at (:last-tick-at state)
       :last-result (:last-result state)
       :last-error (:last-error state)})))

(defn stop!
  "Stop intake and wait for an in-flight sweep.  Idempotent.

  Returns `{:stopped? boolean :terminated? boolean}`.  A sweep that does not
  finish within `:stop-timeout-ms` is interrupted; both fields report the
  executor's real state instead of pretending the thread is gone."
  [runtime]
  (if-not runtime
    {:stopped? true :terminated? true}
    (let [^ScheduledExecutorService executor (:executor runtime)]
      (compare-and-set! (:stopped? runtime) false true)
      (if-not executor
        {:stopped? true :terminated? true}
        (do
          (.shutdown executor)
          (let [timeout (long (:stop-timeout-ms runtime))
                terminated?
                (try
                  (if (.awaitTermination executor timeout TimeUnit/MILLISECONDS)
                    true
                    (do
                      (.shutdownNow executor)
                      (try
                        (.awaitTermination executor 1000 TimeUnit/MILLISECONDS)
                        (catch InterruptedException _
                          (.interrupt (Thread/currentThread))
                          false))))
                  (catch InterruptedException _
                    (.interrupt (Thread/currentThread))
                    (.shutdownNow executor)
                    false))]
            {:stopped? (boolean terminated?)
             :terminated? (boolean terminated?)}))))))
