(ns hc.server.app.background-loops
  "Adapters from long-lived background loops to `hc.compose.supervisor`
  child specs.

  The supervisor owns the timer, the restart policy, and the backoff.  This
  namespace owns one loop callback, one bounded handle, and honest per-loop
  counters for health and diagnostics.  It starts no thread, opens no store,
  and never owns domain state.

  Callback contract (all callbacks are injected):

    fleet-gc-child
      :gc-fn         (fn [retention-ms] result)  one fleet GC sweep

    retention-gc-child
      :service-ref   atom/IDeref or plain value holding the retention runtime
      :purge-fn      (fn [service] result)       one session-retention sweep
      :blob-gc-fn    (fn [service] result)       one blob-retention sweep

    steward-loop-child
      :tick-fn       (fn [] result)              one Steward advance

  Every loop result with `:ok? false` is a failed tick.  A thrown callback is
  caught and becomes `{:ok? false :error {:code :BACKGROUND_TICK_FAILED
  :message ... :at epoch-ms}}`.  `:tick!` never throws and never returns a
  full payload.

  Optional lifecycle hooks for every child:

    :start-fn         (fn [] result)  an underlying start; a result map with
                                      `:ok? false` is a rejected start
    :stop-fn          (fn [resource]) bounded, idempotent cleanup
    :restart          :permanent|:transient|:temporary (default :permanent)
    :backoff-ms       [min-ms max-ms] (default [1000 60000])
    :stop-timeout-ms  bound for :stop-fn (default 5000)
    :critical?        startup requirement (default false)

  `:start!` returns a handle map consumable by `:stop!`.  A rejected start
  returns `{:ok? false :error ...}` with the underlying error preserved."
  (:require [hc.server.app.retention-gc :as retention-gc]))

(def child-order
  "The supervisor starts children in this order and stops them in reverse."
  [:steward-loop :fleet-gc :retention-gc])

(def default-restart
  :permanent)

(def default-backoff-ms
  [1000 60000])

(def default-stop-timeout-ms
  5000)

(def max-error-message-length
  "Bound one stored error message.  Full payloads never enter a handle."
  500)

(def ^:private restart-policies
  #{:permanent :transient :temporary})

;; ---------------------------------------------------------------------------
;; Small helpers

(defn- invalid!
  [code message details]
  (throw (ex-info message (assoc details :code code))))

(defn- positive-integer?
  [value]
  (and (integer? value)
       (pos? value)
       (<= value Long/MAX_VALUE)))

(defn- non-negative-integer?
  [value]
  (and (integer? value)
       (<= 0 value Long/MAX_VALUE)))

(defn- bounded-message
  [message]
  (let [message (str (or message "background loop failed"))]
    (if (> (count message) max-error-message-length)
      (str (subs message 0 (- max-error-message-length 3)) "...")
      message)))

(defn- error-map
  [value]
  (if (map? value) value {}))

(defn- bounded-error
  "A bounded error value: code, message, and optional epoch time only."
  [value]
  (let [value (error-map value)]
    (cond-> {:code (or (:code value) :BACKGROUND_LOOP_ERROR)
             :message (bounded-message (:message value))}
      (some? (:at value)) (assoc :at (:at value)))))

(defn- error
  [code message at]
  (cond-> {:code code
           :message (bounded-message message)}
    (some? at) (assoc :at at)))

(defn- failure-result?
  [result]
  (and (map? result) (false? (:ok? result))))

(defn- failure-error
  [result at]
  (let [value (:error result)]
    (if (map? value)
      (bounded-error (cond-> value
                       (not (contains? value :at)) (assoc :at at)))
      (error :BACKGROUND_TICK_FAILED
             (if (nil? value)
               "a background loop tick returned :ok? false"
               (str value))
             at))))

(defn- ctx-now
  [ctx]
  (let [clock (:clock ctx)]
    (try
      (long (if (fn? clock) (clock) (System/currentTimeMillis)))
      (catch Throwable _
        (System/currentTimeMillis)))))

(defn- deref-ref
  [ref]
  (if (instance? clojure.lang.IDeref ref)
    @ref
    ref))

(defn- service-value
  "Extract a runtime from a service ref value.

  A start receipt `{:ok? true :service ...}` (retention) or
  `{:ok? true :gc ...}` (fleet) yields its runtime.  A rejected start yields
  nil; ticks then report an unavailable service.  Any other value is
  already the service."
  [value]
  (cond
    (and (map? value) (false? (:ok? value))) nil
    (and (map? value) (true? (:ok? value)))
    (or (:service value) (:runtime value) (:handle value) (:gc value))
    :else value))

(defn- resolve-service
  [handle service-ref]
  (or (:resource handle)
      (service-value (deref-ref service-ref))))

;; ---------------------------------------------------------------------------
;; Child state and handle

(defn- new-state
  [id]
  (atom {:id id
         :running? false
         :runs 0
         :failures 0
         :consecutive-failures 0
         :last-run-at nil
         :last-error nil}))

(defn- record-start-success!
  [state]
  (swap! state assoc :running? true :consecutive-failures 0))

(defn- record-success!
  [state at]
  (swap! state
         (fn [state]
           (-> state
               (update :runs inc)
               (assoc :last-run-at at
                      :consecutive-failures 0)))))

(defn- record-failure!
  [state at failure]
  (swap! state
         (fn [state]
           (-> state
               (update :failures inc)
               (update :consecutive-failures inc)
               (assoc :last-error
                      (bounded-error
                       (assoc (error-map failure) :at at)))))))

(defn- normalize-start-result
  [start-fn service-ref at]
  (try
    (let [result (if (fn? start-fn)
                   (start-fn)
                   (deref-ref service-ref))]
      (cond
        (and (map? result) (false? (:ok? result)))
        {:ok? false
         :error (let [value (:error result)]
                  (cond
                    (map? value)
                    (cond-> value
                      (not (contains? value :at)) (assoc :at at))

                    (nil? value)
                    (error :BACKGROUND_START_FAILED
                           "the child rejected its start"
                           at)

                    :else
                    (error :BACKGROUND_START_FAILED (str value) at)))}

        (and (map? result) (true? (:ok? result)))
        {:ok? true
         :resource (or (:handle result)
                       (:service result)
                       (:resource result)
                       (:runtime result)
                       (:gc result))}

        :else
        {:ok? true :resource result}))
    (catch Throwable t
      {:ok? false
       :error (error :BACKGROUND_START_FAILED
                     (or (.getMessage t) (str t))
                     at)})))

(defn- run-bounded
  "Run F on a daemon thread and wait at most TIMEOUT-MS.  Never interrupts.

  A timeout is a failed stop, not an optimistic success.  Only a normal
  in-bounds return produces `:ok? true`."
  [label f arg timeout-ms]
  (let [outcome (atom nil)
        ^Thread thread
        (Thread.
         ^Runnable
         (fn []
           (try
             (reset! outcome {:ok? true :value (f arg)})
             (catch Throwable t
               (reset! outcome
                       {:ok? false
                        :error (error :BACKGROUND_STOP_FAILED
                                      (or (.getMessage t) (str t))
                                      nil)}))))
         (str "hc-background-stop-" (name label) "-" (System/nanoTime)))]
    (.setDaemon thread true)
    (.start thread)
    (let [timeout (long (max 0 timeout-ms))]
      ;; Join with a positive slice so a zero timeout still synchronizes on
      ;; thread termination before the outcome atom is read.
      (.join thread (long (max 1 timeout))))
    (if (.isAlive thread)
      {:ok? false
       :timed-out? true
       :error (error :BACKGROUND_STOP_TIMEOUT
                     "the stop callback did not finish within :stop-timeout-ms"
                     (System/currentTimeMillis))}
      (or @outcome
          {:ok? false
           :error (error :BACKGROUND_STOP_FAILED
                         "the stop callback produced no result"
                         (System/currentTimeMillis))}))))

(defn- stop-handle!
  "Stop one handle.  Idempotent, bounded, and it never throws."
  [handle]
  (try
    (if-not (map? handle)
      {:ok? true :stopped? false :timed-out? false}
      (let [state (:state handle)
            [previous _] (swap-vals!
                          state
                          (fn [state]
                            (if (:running? state)
                              (assoc state :running? false)
                              state)))]
        (if-not (:running? previous)
          {:ok? true :stopped? false :timed-out? false}
          (let [stop-fn (:stop-fn handle)]
            (if-not (fn? stop-fn)
              {:ok? true :stopped? true :timed-out? false}
              (let [result (run-bounded (:id handle)
                                        stop-fn
                                        (:resource handle)
                                        (long (or (:stop-timeout-ms handle)
                                                  default-stop-timeout-ms)))
                    result (if (map? result)
                             result
                             {:ok? false
                              :error (error :BACKGROUND_STOP_FAILED
                                            "the stop callback returned an invalid value"
                                            nil)})
                    at (System/currentTimeMillis)
                    value (:value result)
                    inner-failure? (and (map? value)
                                        (false? (:ok? value)))
                    unconfirmed? (or inner-failure?
                                     (and (map? value)
                                          (contains? value :terminated?)
                                          (false? (:terminated? value))))
                    ok? (and (boolean (:ok? result))
                             (not unconfirmed?))
                    stop-error (cond
                                 (not (:ok? result))
                                 (or (:error result)
                                     (error :BACKGROUND_STOP_FAILED
                                            "the stop callback failed"
                                            at))

                                 inner-failure?
                                 (or (when (map? (:error value))
                                       (:error value))
                                     (error :BACKGROUND_STOP_FAILED
                                            "the stop callback reported :ok? false"
                                            at))

                                 unconfirmed?
                                 (error :BACKGROUND_STOP_UNCONFIRMED
                                        "the stop callback did not confirm termination"
                                        at)

                                 :else nil)]
                (when stop-error
                  (swap! state assoc
                         :last-error
                         (bounded-error (assoc stop-error :at at))))
                (cond-> {:ok? ok?
                         :stopped? true
                         :timed-out? (boolean (:timed-out? result))}
                  (contains? result :value)
                  (assoc :result value)

                  (some? stop-error)
                  (assoc :error stop-error))))))))
    (catch Throwable t
      {:ok? false
       :stopped? true
       :timed-out? false
       :error (error :BACKGROUND_STOP_FAILED
                     (or (.getMessage t) (str t))
                     nil)})))

;; ---------------------------------------------------------------------------
;; Tick execution

(defn- run-tick!
  [id state handle work-fn ctx]
  (try
    (let [at (ctx-now ctx)]
      (cond
        (not (map? handle))
        {:ok? false
         :error (error :BACKGROUND_LOOP_NOT_STARTED
                       "the background loop has no start handle"
                       at)}

        (not (:running? @state))
        {:ok? false
         :error (error :BACKGROUND_LOOP_STOPPED
                       "the background loop is stopped"
                       at)}

        :else
        (try
          (let [result (work-fn handle ctx at)]
            (if (failure-result? result)
              (let [failure (failure-error result at)]
                (record-failure! state (or (:at failure) at) failure)
                {:ok? false :error failure})
              (do
                (record-success! state at)
                {:ok? true :id id :at at})))
          (catch Throwable t
            (let [failure (error :BACKGROUND_TICK_FAILED
                                 (or (.getMessage t) (str t))
                                 at)]
              (record-failure! state at failure)
              {:ok? false :error failure})))))
    (catch Throwable t
      ;; Keep the "never throws" contract even if a state update or clock
      ;; read fails unexpectedly.
      {:ok? false
       :error (error :BACKGROUND_TICK_FAILED
                     (or (.getMessage t) (str t))
                     nil)})))

(defn- sweep-outcome
  [sweep! service at]
  (try
    (let [result (sweep! service)]
      (if (failure-result? result)
        {:ok? false :error (failure-error result at)}
        {:ok? true}))
    (catch Throwable t
      {:ok? false
       :error (error :BACKGROUND_TICK_FAILED
                     (or (.getMessage t) (str t))
                     at)})))

(defn- retention-work
  [purge-fn blob-gc-fn service-ref]
  (fn [handle ctx at]
    (let [service (resolve-service handle service-ref)]
      (if (nil? service)
        {:ok? false
         :error (error :BACKGROUND_SERVICE_UNAVAILABLE
                       "the retention service is not attached"
                       at)}
        (let [session (sweep-outcome purge-fn service at)
              blob (sweep-outcome blob-gc-fn service at)]
          (if (and (:ok? session) (:ok? blob))
            {:ok? true}
            {:ok? false
             :error (or (:error session)
                        (:error blob)
                        (error :BACKGROUND_TICK_FAILED
                               "a retention sweep failed"
                               at))}))))))

;; ---------------------------------------------------------------------------
;; Spec construction

(defn- normalize-interval
  [id opts]
  (let [value (:interval-ms opts)]
    (when-not (positive-integer? value)
      (invalid! :BACKGROUND_INTERVAL_INVALID
                "background loop :interval-ms must be a positive integer"
                {:loop id :interval-ms value}))
    (long value)))

(defn- normalize-restart
  [id opts]
  (let [value (or (:restart opts) default-restart)]
    (when-not (contains? restart-policies value)
      (invalid! :BACKGROUND_RESTART_INVALID
                "background loop :restart must be :permanent, :transient, or :temporary"
                {:loop id :restart value}))
    value))

(defn- normalize-backoff
  [id opts]
  (let [value (or (:backoff-ms opts) default-backoff-ms)]
    (when-not (and (sequential? value)
                   (= 2 (count value))
                   (positive-integer? (first value))
                   (positive-integer? (second value))
                   (<= (long (first value)) (long (second value))))
      (invalid! :BACKGROUND_BACKOFF_INVALID
                "background loop :backoff-ms must be [min-ms max-ms], 0 < min <= max"
                {:loop id :backoff-ms value}))
    [(long (first value)) (long (second value))]))

(defn- normalize-stop-timeout
  [id opts]
  (let [value (or (:stop-timeout-ms opts) default-stop-timeout-ms)]
    (when-not (non-negative-integer? value)
      (invalid! :BACKGROUND_STOP_TIMEOUT_INVALID
                "background loop :stop-timeout-ms must be a non-negative integer"
                {:loop id :stop-timeout-ms value}))
    (long value)))

(defn- base-child-spec
  [id opts work-fn]
  (let [interval-ms (normalize-interval id opts)
        restart (normalize-restart id opts)
        backoff-ms (normalize-backoff id opts)
        stop-timeout-ms (normalize-stop-timeout id opts)
        start-fn (:start-fn opts)
        stop-fn (:stop-fn opts)
        service-ref (:service-ref opts)
        state (new-state id)]
    (when (and (some? start-fn) (not (fn? start-fn)))
      (invalid! :BACKGROUND_START_FN_INVALID
                "background loop :start-fn must be a function"
                {:loop id :start-fn start-fn}))
    (when (and (some? stop-fn) (not (fn? stop-fn)))
      (invalid! :BACKGROUND_STOP_FN_INVALID
                "background loop :stop-fn must be a function"
                {:loop id :stop-fn stop-fn}))
    {:id id
     :start! (fn [ctx]
               (let [at (ctx-now ctx)
                     result (normalize-start-result start-fn service-ref at)]
                 (if (:ok? result)
                   (do
                     (record-start-success! state)
                     (when (and (instance? clojure.lang.IAtom service-ref)
                                (some? (:resource result)))
                       ;; Publish the unwrapped runtime for callers that fill a
                       ;; ref after construction (the fleet supervisor pattern).
                       (reset! service-ref (:resource result)))
                     {:id id
                      :state state
                      :resource (:resource result)
                      :stop-fn stop-fn
                      :stop-timeout-ms stop-timeout-ms
                      :started-at at})
                   (do
                     (record-failure! state at (:error result))
                     {:ok? false :error (:error result)}))))
     :stop! stop-handle!
     :tick! (fn [ctx]
              (let [ctx (or ctx {})]
                (run-tick! id state (:handle ctx) work-fn ctx)))
     :interval-ms interval-ms
     :restart restart
     :backoff-ms backoff-ms
     :critical? (boolean (:critical? opts))
     ::state state}))

;; ---------------------------------------------------------------------------
;; Public adapters

(defn fleet-gc-child
  "Build the `:fleet-gc` periodic child.

  Required:
  - `:gc-fn`       `(fn [retention-ms] result)`; one fleet GC sweep;
  - `:interval-ms` positive integer.

  Optional:
  - `:retention-ms` non-negative integer passed to `:gc-fn` (may be nil);
  - `:critical?`, `:restart`, `:backoff-ms`, `:stop-timeout-ms`,
    `:start-fn`, `:stop-fn`.

  Returns a `hc.compose.supervisor` child spec."
  [{:keys [gc-fn retention-ms] :as opts}]
  (when-not (fn? gc-fn)
    (invalid! :BACKGROUND_GC_FN_REQUIRED
              "fleet GC child needs a :gc-fn function"
              {:loop :fleet-gc}))
  (when-not (or (nil? retention-ms)
                (non-negative-integer? retention-ms))
    (invalid! :BACKGROUND_RETENTION_INVALID
              "fleet GC :retention-ms must be a non-negative integer"
              {:loop :fleet-gc :retention-ms retention-ms}))
  (base-child-spec
   :fleet-gc
   opts
   (fn [_handle _ctx _at]
     (gc-fn retention-ms))))

(def retention-start-opt-keys
  "The exact option keys read by `hc.server.app.retention-gc/start!`.

  Source: `src/hc/server/app/retention_gc.clj`, `start!`.  The background
  adapter rejects unknown keys instead of silently dropping them, so a
  wiring typo cannot disappear."
  #{:registry :store :blob-root :clock :session-interval-ms :blob-interval-ms
    :session-days :blob-days :run-immediately? :purge-session! :blob-cleanup!
    :stop-timeout-ms})

(defn- validate-retention-start-opts!
  [start-opts]
  (when-not (map? start-opts)
    (invalid! :BACKGROUND_START_OPTS_INVALID
              "retention :start-opts must be a map"
              {:loop :retention-gc :start-opts start-opts}))
  (let [unknown (remove retention-start-opt-keys (keys start-opts))]
    (when (seq unknown)
      (invalid! :BACKGROUND_START_OPTS_INVALID
                "retention :start-opts contains keys the real start! does not read"
                {:loop :retention-gc :unknown (vec unknown)})))
  start-opts)

(defn retention-gc-child
  "Build the `:retention-gc` periodic child.

  Required:
  - `:purge-fn`    `(fn [service] result)`; one session-retention sweep;
  - `:blob-gc-fn`  `(fn [service] result)`; one blob-retention sweep;
  - `:interval-ms` positive integer;
  - `:service-ref`, `:start-fn`, or `:start-opts`.

  `:service-ref` may be an atom, another IDeref, or a plain runtime.  A deref
  value of `{:ok? true :service runtime}` unwraps to the runtime.

  `:start-opts` is the real `retention-gc/start!` option map.  The adapter
  validates it against `retention-start-opt-keys`, calls the real `start!`,
  and defaults `:stop-fn` to the real `retention-gc/stop!`.  Supply either
  `:start-fn` or `:start-opts`, never both.

  Source contract: `src/hc/server/app/retention_gc.clj`, functions `start!`,
  `stop!`, `purge-expired-deleted!`, `gc-blobs!`, and `status`.

  Returns a `hc.compose.supervisor` child spec."
  [{:keys [service-ref purge-fn blob-gc-fn start-fn stop-fn start-opts]
    :as opts}]
  (when-not (fn? purge-fn)
    (invalid! :BACKGROUND_PURGE_FN_REQUIRED
              "retention GC child needs a :purge-fn function"
              {:loop :retention-gc}))
  (when-not (fn? blob-gc-fn)
    (invalid! :BACKGROUND_BLOB_GC_FN_REQUIRED
              "retention GC child needs a :blob-gc-fn function"
              {:loop :retention-gc}))
  (when (and (some? start-fn) (some? start-opts))
    (invalid! :BACKGROUND_START_OPTS_CONFLICT
              "retention GC child needs either :start-fn or :start-opts, not both"
              {:loop :retention-gc}))
  (when-not (or (some? service-ref) (fn? start-fn) (some? start-opts))
    (invalid! :BACKGROUND_SERVICE_REF_REQUIRED
              "retention GC child needs :service-ref, :start-fn, or :start-opts"
              {:loop :retention-gc}))
  (let [start-opts (when (some? start-opts)
                     (validate-retention-start-opts! start-opts))
        opts (if (some? start-opts)
               (assoc opts
                      :start-fn (fn [] (retention-gc/start! start-opts))
                      :stop-fn (or stop-fn
                                   (fn [service]
                                     (retention-gc/stop! service))))
               opts)]
    (base-child-spec
     :retention-gc
     opts
     (retention-work purge-fn blob-gc-fn service-ref))))

(defn steward-loop-child
  "Build the `:steward-loop` periodic child.

  Required:
  - `:tick-fn`     `(fn [] result)`; one Steward advance;
  - `:interval-ms` positive integer.

  Optional: `:critical?`, `:restart`, `:backoff-ms`, `:stop-timeout-ms`,
  `:start-fn`, `:stop-fn`.

  Returns a `hc.compose.supervisor` child spec."
  [{:keys [tick-fn] :as opts}]
  (when-not (fn? tick-fn)
    (invalid! :BACKGROUND_TICK_FN_REQUIRED
              "steward loop child needs a :tick-fn function"
              {:loop :steward-loop}))
  (base-child-spec
   :steward-loop
   opts
   (fn [_handle _ctx _at]
     (tick-fn))))

(defn- state-of
  [candidate]
  (cond
    (and (map? candidate)
         (instance? clojure.lang.IAtom (:state candidate)))
    (:state candidate)

    (and (map? candidate)
         (instance? clojure.lang.IAtom (::state candidate)))
    (::state candidate)

    :else nil))

(defn- summary
  [state]
  (let [value @state]
    {:id (:id value)
     :running? (boolean (:running? value))
     :runs (long (or (:runs value) 0))
     :failures (long (or (:failures value) 0))
     :consecutive-failures (long (or (:consecutive-failures value) 0))
     :last-run-at (:last-run-at value)
     :last-error (:last-error value)}))

(defn status
  "Return a bounded background loop summary.

  Accepts one handle (from `:start!`), one spec (from a `*-child` helper),
  or a sequential collection of either.  A collection yields a vector in the
  input order; nil and unknown entries are skipped.  The summary contains
  only `:id`, `:running?`, `:runs`, `:failures`, `:consecutive-failures`,
  `:last-run-at`, and the bounded `:last-error`.  It never contains a sweep
  payload or a service object."
  [handle-or-specs]
  (try
    (cond
      (nil? handle-or-specs)
      nil

      (sequential? handle-or-specs)
      (into []
            (keep (fn [candidate]
                    (when-let [state (state-of candidate)]
                      (summary state))))
            handle-or-specs)

      :else
      (when-let [state (state-of handle-or-specs)]
        (summary state)))
    (catch Throwable _
      nil)))

(defn child-specs
  "Build the supervisor children from configured loop options.

  `loops` is a map keyed by `:steward-loop`, `:fleet-gc`, and `:retention-gc`.
  An absent (or nil) entry is omitted.  The result follows `child-order`:
  `[:steward-loop :fleet-gc :retention-gc]`.  An empty or nil map yields []."
  [loops]
  (when-not (or (nil? loops) (map? loops))
    (invalid! :BACKGROUND_LOOPS_INVALID
              "background loop options must be a map"
              {:loops loops}))
  (let [loops (or loops {})]
    (into []
          (keep (fn [[id build]]
                  (when (some? (get loops id))
                    (build (get loops id)))))
          [[:steward-loop steward-loop-child]
           [:fleet-gc fleet-gc-child]
           [:retention-gc retention-gc-child]])))
