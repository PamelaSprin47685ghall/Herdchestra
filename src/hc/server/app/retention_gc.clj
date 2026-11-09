(ns hc.server.app.retention-gc
  "Application-owned retention scheduler for deleted sessions and blobs.

  Two independent sweeps share one daemon `ScheduledExecutorService`:

  - session retention permanently removes committed `:deleted` sessions
    whose `:deleted-at` is at least `:session-days` old (30 by default);
  - blob retention reclaims objects without a current reference after a
    `:blob-days` safety window (7 by default).

  Both use `scheduleWithFixedDelay`, so a wall-clock jump cannot produce a
  burst of ticks.  Each sweep is serialized by its own lock; timer and manual
  calls cannot overlap.  `stop!` flips the stopped flag, shuts the executor
  down, and waits a bounded time for an in-flight sweep.  The thread is a
  daemon, so a forgotten handle cannot keep the JVM alive.

  Session invariants:

  - only the committed record decides eligibility; a live Owner is never
    merged in, because a lagging live value must not create a deletion the
    business commit did not make;
  - a temporary session is never a retention candidate, even when it carries
    a deletion timestamp;
  - a restored session has `:deleted-at nil` and is therefore not eligible;
  - a backup preserves the original `:deleted-at`, so a restore does not
    restart the retention clock;
  - the injected `purge-session!` performs the existing permanent-delete
    order: stop the Owner and require `:stopped?`/`:drained?`, then purge.

  Blob invariants:

  - the default `blob-cleanup!` wraps
    `hc.server.blob-store.coordinator/cleanup-storage!`, which reads one
    coherent reference set under the blob-store lock;
  - a reference read failure aborts before any deletion;
  - young objects, referenced objects, and unrecognized filesystem entries
    are never deleted.

  A failed sweep is never reported as success.  `status` keeps the last
  outcome, error, run count, and run time for each sweep."
  (:require [clojure.string :as str]
            [hc.server.blob-store.coordinator :as blob-coordinator]
            [hc.server.sessions.registry :as sessions-registry])
  (:import (java.util.concurrent Executors ScheduledExecutorService
                                 ScheduledFuture ThreadFactory TimeUnit)
           (java.util.concurrent.atomic AtomicLong)))

(set! *warn-on-reflection* true)

(def day-ms
  "Milliseconds in one day.  Retention is configured in whole days."
  (* 24 60 60 1000))

(def default-session-days
  "A soft-deleted session stays recoverable for 30 days."
  30)

(def default-blob-days
  "An unreferenced blob is retained for a seven-day safety window."
  7)

(def default-interval-ms
  "One day between sweeps.  Retention is measured in days, so a tighter timer
  only adds load."
  day-ms)

(def default-stop-timeout-ms
  "How long `stop!` waits for an in-flight sweep before interrupting it."
  5000)

(def ^:private max-retained-items
  "Bound the evidence retained in the scheduler state."
  20)

(defn- fail
  [code message details]
  {:ok? false
   :outcome :error
   :error (cond-> {:code code :message message}
            (some? details) (assoc :details details))})

(defn- throwable-error
  "Convert a throwable to an error map, preserving an `ex-data` code."
  [default-code ^Throwable throwable]
  (let [data (ex-data throwable)
        code (or (:code data) (:error data) default-code)
        details (when (and (map? data)
                           (seq (dissoc data :code :error :storage/expected?)))
                  (dissoc data :code :error :storage/expected?))]
    (cond-> {:code code
             :message (or (.getMessage throwable) (str throwable))
             :class (.getName (class throwable))}
      (seq details) (assoc :details details))))

(defn- daemon-thread-factory
  [prefix]
  (let [counter (AtomicLong.)]
    (reify ThreadFactory
      (newThread [_ runnable]
        (doto (Thread. ^Runnable runnable
                       (str prefix "-" (.incrementAndGet counter)))
          (.setDaemon true))))))

(defn- positive-integer?
  [value]
  (and (integer? value)
       (pos? value)
       (<= value Long/MAX_VALUE)))

(defn- retention-days?
  [value]
  (and (positive-integer? value)
       (<= value (quot Long/MAX_VALUE day-ms))))

(defn- clock-now
  [runtime]
  (let [value ((:clock runtime))]
    (when-not (number? value)
      (throw (ex-info "retention clock must return epoch milliseconds"
                      {:value value})))
    (long value)))

(defn- retention-window-ms
  [days]
  (* (long days) day-ms))

(defn- expired-deletion?
  "True when SESSION is a committed, non-temporary soft delete past DAYS."
  [session now days]
  (and (= :deleted (:status session))
       (not (:temporary? session))
       (integer? (:deleted-at session))
       ;; Subtract rather than add: a timestamp near Long/MAX_VALUE must not
       ;; overflow the eligibility check.
       (<= (long (:deleted-at session))
           (- (long now) (retention-window-ms days)))))

(defn- purge-failure
  [session-id result]
  (cond
    (not (map? result))
    {:session-id session-id
     :error {:code :RETENTION_PURGE_RESULT_INVALID
             :message "purge-session! did not return a result map"
             :details {:result result}}}

    (not= :purged (:status result))
    (cond-> {:session-id session-id
             :result (select-keys result [:status :error])
             :error (or (:error result)
                        {:code :RETENTION_PURGE_FAILED
                         :message "purge-session! did not report :purged"
                         :details {:status (:status result)}})}
      (:session-id result) (assoc :reported-session-id (:session-id result)))

    :else nil))

(defn- current-session-summary
  [runtime session-id]
  (try
    (sessions-registry/get-committed-session-summary
     (:registry runtime)
     session-id)
    (catch Throwable t
      {:status :error
       :error (throwable-error :RETENTION_SESSION_READ_FAILED t)})))

(defn- purge-still-eligible?
  "Re-read one session before deleting it.

  The listing and the purge are separate operations.  A session may have
  been restored and re-deleted with a fresh `:deleted-at` in between, so the
  original eligibility is not proof for the destructive step.  A read
  failure skips the deletion and is recorded as a failure; it never becomes
  a guess."
  [runtime session-id now days]
  (let [loaded (current-session-summary runtime session-id)]
    (case (:status loaded)
      :found
      (let [session (:session loaded)]
        (cond
          (not= :deleted (:status session))
          {:ok? false :reason :not-deleted}

          (not (integer? (:deleted-at session)))
          {:ok? false
           :reason :missing-deleted-at
           :error {:code :RETENTION_DELETED_AT_MISSING
                   :message
                   "a deleted session has no integer :deleted-at timestamp"
                   :details {:session-id session-id}}}

          (not (expired-deletion? session now days))
          {:ok? false :reason :not-expired}

          :else
          {:ok? true}))

      :missing
      {:ok? false :reason :missing}

      {:ok? false
       :reason :read-failed
       :error (or (:error loaded)
                  {:code :RETENTION_SESSION_READ_FAILED
                   :message
                   "session summary read did not return found or missing"
                   :details {:status (:status loaded)}})})))

(defn- session-sweep
  [runtime now]
  (let [listed (sessions-registry/deleted-session-summaries (:registry runtime))
        days (:session-days runtime)]
    (if-not (= :ok (:status listed))
      (fail :RETENTION_SESSION_LIST_FAILED
            "deleted sessions could not be listed"
            {:status (:status listed)
             :error (:error listed)})
      (let [sessions (:sessions listed)
            eligible (filterv #(expired-deletion? % now days) sessions)
            dry-run? (boolean (:dry-run? runtime))
            plan (mapv :session-id eligible)]
        (if dry-run?
          {:ok? true
           :outcome :dry-run
           :at now
           :dry-run? true
           :retention-ms (retention-window-ms days)
           :eligible plan
           :planned plan
           :purged []
           :skipped []
           :failed []
           :errors []}
          (loop [remaining (seq eligible)
                 purged []
                 skipped []
                 failed []]
            (if-let [session (first remaining)]
              (let [session-id (:session-id session)
                    check (purge-still-eligible?
                           runtime session-id now days)]
                (if-not (:ok? check)
                  (if (:error check)
                    (recur (next remaining)
                           purged
                           skipped
                           (conj failed {:session-id session-id
                                         :error (:error check)}))
                    (recur (next remaining)
                           purged
                           (conj skipped {:session-id session-id
                                          :reason (:reason check)})
                           failed))
                  (let [result (try
                                 ((:purge-session! runtime) session-id)
                                 (catch Throwable t
                                   (let [error (throwable-error
                                                :RETENTION_PURGE_FAILED t)]
                                     {:status :error
                                      :session-id session-id
                                      :error
                                      (update error :details
                                              #(assoc (or % {})
                                                      :session-id
                                                      session-id))})))
                        failure (purge-failure session-id result)]
                    (if failure
                      (recur (next remaining) purged skipped
                             (conj failed failure))
                      (recur (next remaining) (conj purged session-id)
                             skipped failed)))))
              {:ok? (empty? failed)
               :outcome (if (seq failed) :partial :completed)
               :at now
               :dry-run? false
               :retention-ms (retention-window-ms days)
               :eligible plan
               :purged purged
               :skipped skipped
               :failed failed
               :errors []})))))))

(defn- purge-error
  "The error a health reader sees when a session sweep did not fully succeed."
  [result]
  (cond
    ;; A fatal result means the pass could not run (session listing failed,
    ;; clock invalid, or an unexpected throw).  A completed pass with
    ;; per-session failures has `:outcome :partial`, not `:error`.
    (= :error (:outcome result))
    {:code :RETENTION_SESSION_SWEEP_FAILED
     :message (or (get-in result [:error :message])
                  "the session retention sweep could not run")
     :details {:cause (get-in result [:error :code]
                              :RETENTION_SESSION_SWEEP_FAILED)}}

    (seq (:failed result))
    {:code :RETENTION_SESSION_PURGE_FAILED
     :message "one or more deleted sessions could not be purged"
     :details {:failed-count (count (:failed result))
               :failed (vec (take max-retained-items (:failed result)))
               :purged (count (:purged result))
               :skipped (count (:skipped result))
               :eligible (count (:eligible result))}}

    :else nil))

(defn- session-result-summary
  [result]
  (let [retried (:retried result)
        summary (assoc (select-keys result
                                    [:ok? :outcome :dry-run? :at
                                     :retention-ms :error])
                       :retried (vec (take max-retained-items retried))
                       :retried-count (count retried))]
    (cond-> summary
      (seq (:eligible result))
      (assoc :eligible (vec (take max-retained-items (:eligible result)))
             :eligible-count (count (:eligible result)))

      (seq (:purged result))
      (assoc :purged (vec (take max-retained-items (:purged result)))
             :purged-count (count (:purged result)))

      (seq (:skipped result))
      (assoc :skipped (vec (take max-retained-items (:skipped result)))
             :skipped-count (count (:skipped result)))

      (seq (:failed result))
      (assoc :failed (vec (take max-retained-items (:failed result)))
             :failed-count (count (:failed result))))))

(defn- run-session-sweep
  [runtime]
  (let [now-result (try
                     {:ok? true :at (clock-now runtime)}
                     (catch Throwable t
                       {:ok? false
                        :error {:code :RETENTION_CLOCK_INVALID
                                :message
                                (or (.getMessage t) (str t))
                                :details
                                {:cause (or (:code (ex-data t))
                                            (:error (ex-data t))
                                            :RETENTION_CLOCK_INVALID)
                                 :cause-details
                                 (not-empty (dissoc (ex-data t)
                                                    :code :error
                                                    :storage/expected?))}}}))
        at (or (:at now-result) (System/currentTimeMillis))
        result (if (:ok? now-result)
                 (try
                   (session-sweep runtime at)
                   (catch Throwable t
                     {:ok? false
                      :outcome :error
                      :at at
                      :error (throwable-error
                              :RETENTION_SESSION_SWEEP_FAILED t)}))
                 (fail :RETENTION_CLOCK_INVALID
                       "the retention clock did not return epoch milliseconds"
                       (:error now-result)))]
    (let [previous-failed (or (:session-failed-ids @(:state runtime)) #{})
          retried (filterv #(contains? previous-failed %)
                           (:eligible result))
          result (assoc result
                        :retried retried
                        :retried-count (count retried))]
      (swap! (:state runtime)
             (fn [state]
               (-> state
                   (update :session-runs inc)
                   (assoc :session-last-result (session-result-summary result)
                          :session-last-error (purge-error result)
                          :session-failed-ids
                          (set (keep :session-id (:failed result)))
                          :last-run-at at))))
      result)))

(defn- blob-error
  [result]
  (cond
    ;; A fatal result means the pass could not run (reference read failed,
    ;; clock invalid, or an unexpected throw).  Partial cleanup is a
    ;; completed pass with per-object errors.
    (= :error (:outcome result))
    (or (:error result)
        {:code :RETENTION_BLOB_SWEEP_FAILED
         :message "the blob retention sweep did not succeed"})

    (seq (:errors result))
    (or (:error result)
        {:code :RETENTION_BLOB_PARTIAL
         :message "blob cleanup reported errors"
         :details {:errors (vec (take max-retained-items (:errors result)))
                   :error-count (count (:errors result))}})

    (not (:ok? result))
    (or (:error result)
        {:code :RETENTION_BLOB_SWEEP_FAILED
         :message "the blob retention sweep did not succeed"})

    :else nil))

(defn- blob-result-summary
  [result]
  (cond-> (select-keys result
                       [:ok? :outcome :dry-run? :at :safety-window-ms
                        :scanned :error])
    (seq (:deleted result))
    (assoc :deleted (vec (take max-retained-items (:deleted result)))
           :deleted-count (count (:deleted result)))

    (seq (:temp-deleted result))
    (assoc :temp-deleted (vec (take max-retained-items (:temp-deleted result)))
           :temp-deleted-count (count (:temp-deleted result)))

    (seq (:errors result))
    (assoc :errors (vec (take max-retained-items (:errors result)))
           :error-count (count (:errors result)))))

(defn- normalize-blob-result
  [result at window]
  (if (map? result)
    (let [outcome (:outcome result)
          ok? (and (contains? #{:completed :dry-run} outcome)
                   (empty? (:errors result))
                   (not (false? (:ok? result)))
                   (nil? (:error result)))]
      (cond-> (assoc result
                     :ok? ok?
                     :at at
                     :safety-window-ms window)
        (not ok?)
        (assoc :error (or (:error result)
                          {:code :RETENTION_BLOB_PARTIAL
                           :message "blob cleanup did not complete"
                           :details {:outcome outcome
                                     :error-count (count (:errors result))
                                     :errors (vec (take max-retained-items
                                                        (:errors result)))}}))))
    (fail :RETENTION_BLOB_RESULT_INVALID
          "blob-cleanup! did not return a result map"
          {:result result})))

(defn- run-blob-sweep
  [runtime opts]
  (let [now-result (try
                     {:ok? true :at (clock-now runtime)}
                     (catch Throwable t
                       {:ok? false
                        :error {:code :RETENTION_CLOCK_INVALID
                                :message
                                (or (.getMessage t) (str t))
                                :details
                                {:cause (or (:code (ex-data t))
                                            (:error (ex-data t))
                                            :RETENTION_CLOCK_INVALID)
                                 :cause-details
                                 (not-empty (dissoc (ex-data t)
                                                    :code :error
                                                    :storage/expected?))}}}))
        window (retention-window-ms (:blob-days runtime))
        result (if (:ok? now-result)
                 (let [cleanup-opts {:safety-window-ms window
                                     :now (:at now-result)
                                     :dry-run? (boolean (:dry-run? opts))}]
                   (try
                     (normalize-blob-result
                      ((:blob-cleanup! runtime) cleanup-opts)
                      (:at now-result)
                      window)
                     (catch Throwable t
                       {:ok? false
                        :outcome :error
                        :at (:at now-result)
                        :safety-window-ms window
                        :error (throwable-error
                                :RETENTION_BLOB_SWEEP_FAILED t)})))
                 (fail :RETENTION_CLOCK_INVALID
                       "the retention clock did not return epoch milliseconds"
                       (:error now-result)))]
    (swap! (:state runtime)
           (fn [state]
             (-> state
                 (update :blob-runs inc)
                 (assoc :blob-last-result (blob-result-summary result)
                        :blob-last-error (blob-error result)
                        :last-run-at (or (:at now-result)
                                         (System/currentTimeMillis))))))
    result))

(defn- stopped-failure
  []
  (fail :RETENTION_GC_STOPPED
        "the retention scheduler is stopped"
        nil))

(defn purge-expired-deleted!
  "Run one session-retention sweep and return its result.

  Options: `:dry-run? true` reports eligible session IDs without calling
  `purge-session!`.  A single purge failure is recorded and the sweep
  continues; it is retried on the next sweep because the record stays
  deleted and past the window.

  Returns the sweep map from `(run-session-sweep ...)`, or a stopped
  refusal."
  ([runtime] (purge-expired-deleted! runtime {}))
  ([runtime {:keys [dry-run?]}]
   (if @(:stopped? runtime)
     (stopped-failure)
     (locking (:session-lock runtime)
       (if @(:stopped? runtime)
         (stopped-failure)
         (run-session-sweep (assoc runtime :dry-run? (boolean dry-run?))))))))

(defn gc-blobs!
  "Run one blob-retention sweep and return its result.

  The injected `blob-cleanup!` receives
  `{:safety-window-ms n :now epoch-ms :dry-run? bool}`.  The default wrapper
  calls `blob-store.coordinator/cleanup-storage!`, which aborts before any
  deletion when the reference read fails."
  ([runtime] (gc-blobs! runtime {}))
  ([runtime opts]
   (if @(:stopped? runtime)
     (stopped-failure)
     (locking (:blob-lock runtime)
       (if @(:stopped? runtime)
         (stopped-failure)
         (run-blob-sweep runtime opts))))))

(defn status
  "Return scheduler facts without running a sweep, or nil for no scheduler.

  `:session-last-error` and `:blob-last-error` are nil only when the matching
  sweep fully succeeded.  A partial blob cleanup or a failed session purge
  remains visible until the next fully successful sweep."
  [service]
  (when service
    (let [state @(:state service)
          ^ScheduledExecutorService executor (:executor service)]
      {:running? (boolean (and executor (not (.isShutdown executor))))
       :stopped? (boolean @(:stopped? service))
       :terminated? (boolean (and executor (.isTerminated executor)))
       :session-interval-ms (:session-interval-ms service)
       :blob-interval-ms (:blob-interval-ms service)
       :session-days (:session-days service)
       :blob-days (:blob-days service)
       :session-runs (:session-runs state)
       :blob-runs (:blob-runs state)
       :session-last-result (:session-last-result state)
       :blob-last-result (:blob-last-result state)
       :session-last-error (:session-last-error state)
       :blob-last-error (:blob-last-error state)
       :last-run-at (:last-run-at state)})))

(defn start!
  "Start the periodic retention scheduler.

  Required:
  - `:registry`  a session registry used to list deleted session summaries;
  - either `:purge-session!` or a registry whose
    `hc.server.sessions.registry/purge-deleted-session!` is the injected
    permanent-delete function;
  - either `:blob-cleanup!` or `:store` plus `:blob-root`.

  Options:
  - `:purge-session!`   `(fn [session-id] result)`; the result is successful
                        only when `:status` is `:purged`;
  - `:blob-cleanup!`    `(fn [{:keys [safety-window-ms now dry-run?]}]
                        result)`; the default reads current blob references
                        from `:store` and calls
                        `blob-store.coordinator/cleanup-storage!` with
                        `:safety-window-ms` = `:blob-days` days;
  - `:store`            an open storage handle for the default blob cleanup;
  - `:blob-root`        the managed blob directory;
  - `:clock`            epoch-ms function (default `System/currentTimeMillis`);
  - `:session-interval-ms` fixed delay for session retention (default daily);
  - `:blob-interval-ms` fixed delay for blob retention (default daily);
  - `:session-days`     deleted-session retention window (default 30);
  - `:blob-days`        unreferenced-blob safety window (default 7);
  - `:run-immediately?` run the first sweep at start (default false);
  - `:stop-timeout-ms`  how long `stop!` waits for an in-flight sweep.

  Returns `{:ok? true :service service}` or `{:ok? false :error ...}`."
  [{:keys [registry store blob-root clock session-interval-ms
           blob-interval-ms session-days blob-days run-immediately?
           purge-session! blob-cleanup! stop-timeout-ms]}]
  (let [clock (if (some? clock) clock #(System/currentTimeMillis))
        session-interval-ms (or session-interval-ms default-interval-ms)
        blob-interval-ms (or blob-interval-ms default-interval-ms)
        session-days (or session-days default-session-days)
        blob-days (or blob-days default-blob-days)
        stop-timeout-ms (or stop-timeout-ms default-stop-timeout-ms)
        purge-session!
        (if (some? purge-session!)
          purge-session!
          (when registry
            (fn [session-id]
              (sessions-registry/purge-deleted-session!
               registry session-id))))
        blob-cleanup!
        (if (some? blob-cleanup!)
          blob-cleanup!
          (when (and store
                     (string? blob-root)
                     (not (str/blank? blob-root)))
            (fn [cleanup-opts]
              (blob-coordinator/cleanup-storage!
               store blob-root cleanup-opts))))]
    (cond
      (nil? registry)
      (fail :RETENTION_REGISTRY_REQUIRED
            "retention GC needs a session registry"
            nil)

      (not (fn? purge-session!))
      (fail :RETENTION_PURGE_REQUIRED
            "retention GC needs a purge-session! function"
            {:registry? (some? registry)})

      (not (fn? blob-cleanup!))
      (fail :RETENTION_BLOB_CLEANUP_REQUIRED
            "retention GC needs blob-cleanup! or an open store and blob-root"
            {:store? (some? store)
             :blob-root (some-> blob-root str)})

      (not (fn? clock))
      (fail :RETENTION_CLOCK_INVALID
            "retention GC clock must be a function"
            {:clock clock})

      (not (positive-integer? session-interval-ms))
      (fail :RETENTION_SESSION_INTERVAL_INVALID
            "retention session-interval-ms must be a positive integer"
            {:session-interval-ms session-interval-ms})

      (not (positive-integer? blob-interval-ms))
      (fail :RETENTION_BLOB_INTERVAL_INVALID
            "retention blob-interval-ms must be a positive integer"
            {:blob-interval-ms blob-interval-ms})

      (not (retention-days? session-days))
      (fail :RETENTION_SESSION_DAYS_INVALID
            "retention session-days must be a positive integer within the epoch range"
            {:session-days session-days})

      (not (retention-days? blob-days))
      (fail :RETENTION_BLOB_DAYS_INVALID
            "retention blob-days must be a positive integer within the epoch range"
            {:blob-days blob-days})

      (not (and (integer? stop-timeout-ms)
                (<= 0 stop-timeout-ms Long/MAX_VALUE)))
      (fail :RETENTION_STOP_TIMEOUT_INVALID
            "retention stop-timeout-ms must be a non-negative integer"
            {:stop-timeout-ms stop-timeout-ms})

      (not (or (nil? run-immediately?) (boolean? run-immediately?)))
      (fail :RETENTION_RUN_IMMEDIATELY_INVALID
            "retention run-immediately? must be a boolean"
            {:run-immediately? run-immediately?})

      :else
      (let [runtime {:registry registry
                     :clock clock
                     :session-interval-ms (long session-interval-ms)
                     :blob-interval-ms (long blob-interval-ms)
                     :session-days (long session-days)
                     :blob-days (long blob-days)
                     :purge-session! purge-session!
                     :blob-cleanup! blob-cleanup!
                     :dry-run? false
                     :stop-timeout-ms (long stop-timeout-ms)
                     :executor nil
                     :tasks (atom [])
                     :stopped? (atom false)
                     :session-lock (Object.)
                     :blob-lock (Object.)
                     :state (atom {:session-runs 0
                                   :blob-runs 0
                                   :session-last-result nil
                                   :blob-last-result nil
                                   :session-last-error nil
                                   :blob-last-error nil
                                   :session-failed-ids #{}
                                   :last-run-at nil})}
            executor (Executors/newSingleThreadScheduledExecutor
                      (daemon-thread-factory "hc-retention-gc"))
            runtime (assoc runtime :executor executor)
            eager-delay (long (if run-immediately? 0 session-interval-ms))
            blob-delay (long (if run-immediately? 0 blob-interval-ms))]
        (try
          (swap! (:tasks runtime) conj
                 (.scheduleWithFixedDelay
                  ^ScheduledExecutorService executor
                  ^Runnable (fn [] (purge-expired-deleted! runtime))
                  eager-delay
                  (long session-interval-ms)
                  TimeUnit/MILLISECONDS))
          (swap! (:tasks runtime) conj
                 (.scheduleWithFixedDelay
                  ^ScheduledExecutorService executor
                  ^Runnable (fn [] (gc-blobs! runtime))
                  blob-delay
                  (long blob-interval-ms)
                  TimeUnit/MILLISECONDS))
          {:ok? true :service runtime}
          (catch Throwable t
            (reset! (:stopped? runtime) true)
            (doseq [^ScheduledFuture task @(:tasks runtime)]
              (try
                (.cancel task false)
                (catch Throwable _ nil)))
            (.shutdownNow executor)
            (fail :RETENTION_TIMER_FAILED
                  "the retention timer could not start"
                  {:message (or (.getMessage t) (str t))})))))))

(defn stop!
  "Stop intake and wait for in-flight sweeps.  Idempotent.

  Returns `{:stopped? boolean :terminated? boolean}`; both are true only
  when the executor terminated within the bound.  A sweep that does not
  finish within `:stop-timeout-ms` is interrupted; the fields report the
  executor's real state instead of pretending the thread is gone."
  [service]
  (if-not service
    {:stopped? true :terminated? true}
    (let [^ScheduledExecutorService executor (:executor service)]
      (compare-and-set! (:stopped? service) false true)
      (if-not executor
        {:stopped? true :terminated? true}
        (do
          ;; Cancel delayed tasks first.  ScheduledThreadPoolExecutor would
          ;; otherwise keep a task whose initial delay is still pending, and
          ;; `shutdown` would wait for it to expire.
          (doseq [^ScheduledFuture task @(:tasks service)]
            (try
              (.cancel task false)
              (catch Throwable _ nil)))
          (.shutdown executor)
          (let [timeout (long (:stop-timeout-ms service))
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
