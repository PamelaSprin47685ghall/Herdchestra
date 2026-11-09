(ns hc.server.recovery
  "Restart recovery for the current-state store.

  Root AGENTS.md §7.1 requires a scan of every open record before new work is
  dispatched, and §5.3 requires reports to be re-delivered until the parent
  confirms.  The six windows are classified in `hc.server.recovery.classify`;
  this facade performs the external probe and report delivery, then commits the
  safe state changes atomically through `hc.server.storage`.

  Two rules dominate:

  - an existing store that fails to open blocks dispatch and yields a recovery
    entry; it is never replaced by a new empty store;
  - a possibly repeated side effect becomes `:outcome-unknown` unless the
    obligation is explicitly `:safe-to-repeat?`.  Recovery never re-runs an
    operation merely because the old process died.

  The caller supplies `:probe` and `:deliver-report` when it can observe or
  contact the outside world.  Without them, the corresponding results are
  `:unknown` and the conservative branch is chosen."

  (:require [hc.server.recovery.classify :as classify]
            [hc.server.recovery.plan :as plan]
            [hc.server.storage :as storage]
            [hc.server.storage.error :as err]
            [hc.server.storage.layout :as layout])
  (:import (java.util UUID)))

(defn new-generation
  "Return one process-generation value.  Use the same value for
  `recover-all!`'s `:current-generation` and for every obligation's
  `:executor-generation` in this process."
  []
  (str (UUID/randomUUID)))

(defn- recovery-options
  "Operator actions that fit the failure.  A lock conflict must not suggest a
  restore before the other process stops."
  [code]
  (case code
    :STORAGE_LOCKED
    [:stop-other-process :retry :choose-another-path]

    (:STORAGE_NOT_FOUND :STORAGE_NOT_INITIALIZED)
    [:create-new-store :choose-another-path]

    [:inspect-directory :restore-from-backup :choose-another-path]))

(defn recovery-entry
  "Open a store for recovery without creating one by default.

  Returns `{:status :opened :store store}` or, for an expected storage
  failure, `{:status :blocked :error .. :path .. :hint .. :recovery-entry ..}`.
  A blocked result is the recovery entry: the application must stop side-effect
  dispatch and show the hint, never initialize a new database over the old one."
  [opts]
  (let [opts (merge {:create? false :verify-integrity? true} opts)]
    (if-not (and (string? (:path opts)) (pos? (count (:path opts))))
      {:status :blocked
       :error {:error :STORAGE_INVALID_OPTIONS
               :message "recovery-entry needs a non-empty :path"
               :data {:path (:path opts)}}
       :path (:path opts)
       :hint "Pass the store directory as :path."
       :recovery-entry {:action :operator
                        :options [:choose-another-path]}}
      (let [p (layout/path (:path opts))]
        (try
          {:status :opened :store (storage/open-store opts)}
          (catch clojure.lang.ExceptionInfo e
            (if (storage/expected-error? e)
              {:status :blocked
               :error (storage/error-describe e)
               :path (str p)
               :hint (layout/recovery-hint (storage/error-code e) p)
               :recovery-entry {:action :operator
                                :options (recovery-options
                                          (storage/error-code e))}}
              (throw e))))))))

(defn- run-probe [probe obligation]
  (if probe
    (try
      (let [r (probe obligation)]
        (cond
          (keyword? r) {:state r}
          (map? r) r
          :else {:state :unknown :evidence r}))
      (catch Throwable e
        {:state :unknown :error (or (ex-message e) (str (class e)))}))
    {:state :unknown}))

(defn- run-delivery [deliver obligation]
  (if deliver
    (try
      (let [r (deliver obligation)]
        (cond
          (keyword? r) r
          (true? (:accepted? r)) :accepted
          (false? (:accepted? r)) :rejected
          :else :unknown))
      (catch Throwable _ :unknown))
    :unknown))

(defn- decide-fn
  "Build the per-obligation decision function.  Probes and deliveries are
  external reads/actions; classification itself is pure."
  [{:keys [current-generation probe deliver-report now-ms]}]
  (fn [obligation]
    (let [probe-result    (when (classify/needs-probe? obligation
                                                         {:current-generation current-generation})
                            (run-probe probe obligation))
          delivery-result (when (contains? #{:report-pending :report-accepted}
                                           (:phase obligation))
                            (run-delivery deliver-report obligation))]
      (classify/classify obligation
                         {:current-generation current-generation
                          :now-ms now-ms
                          :probe-result (:state probe-result)
                          :probe-error (:error probe-result)
                          :delivery-result delivery-result}))))

(defn scan
  "Classify every open obligation without changing state.

  `opts`: `:partition` or `:all-partitions?`, `:session-id`, `:now-ms`,
  `:current-generation`, `:probe`, `:deliver-report`.  Without a partition the
  scan covers every initialized partition.  A session scan includes
  partition-scoped obligations unless `:include-partition-scope? false`.
  Passing `:probe`/`:deliver-report` makes the scan perform external reads or
  deliveries; omit them for a pure local classification."
  [store opts]
  (when-some [generation (:current-generation opts)]
    (when-not (or (string? generation) (number? generation))
      (err/raise :STORAGE_INVALID_OPTIONS
                 ":current-generation must be a string or a number"
                 {:current-generation generation})))
  (let [all?      (or (:all-partitions? opts) (nil? (:partition opts)))
        partition (:partition opts)
        result    (storage/list-open-obligations
                   store
                   (cond-> {:session-id (:session-id opts)
                            :now-ms (:now-ms opts)
                            :include-partition-scope?
                            (if (contains? opts :include-partition-scope?)
                              (:include-partition-scope? opts)
                              true)}
                     all? (assoc :all-partitions? true)
                     (not all?) (assoc :partition partition)))
        p         (plan/plan (:obligations result)
                             {:now-ms (:now-ms opts)
                              :at (or (:at opts) (str (java.time.Instant/now)))
                              :decide (decide-fn opts)})]
    (assoc p
           :status :ok
           :partition partition
           :partitions (:partitions result)
           :version (:version result)
           :store-version (:store-version result))))

(defn recover!
  "Classify and apply the safe recovery transitions.

  Returns
    {:status :ok
     :scanned n
     :applied [finding ..]
     :pending-reports [obligation ..]
     :pending-retries [obligation ..]
     :blocked-retries [obligation ..]
     :outcome-unknown [obligation ..]
     :attention [finding ..]
     :commits [{:version .. :store-version .. :session-id ..} ..]
     :version partition-version}

  `:pending-reports` must be delivered by the supervisor.  If
  `:deliver-report` is supplied, an accepted report is closed in the same
  recovery transaction; otherwise it stays open for the supervisor.

  `:retry-call` obligations are owner-owned indexes: the caller must start
  the owning session, whose runtime owns the request, effects, timer, and
  terminal outcome.  They are returned in `:pending-retries`.  A legacy
  storage `:retry-wait` without that kind has no claimer or dispatcher: it
  is kept open and returned in `:blocked-retries`."
  [store opts]
  (let [{:keys [updates-by-scope] :as scanned} (scan store opts)
        commits   (vec
                   (for [[[partition session-id] docs]
                         (sort-by (fn [[[p sid] _]] [(str p) (str sid)])
                                  updates-by-scope)
                         :when (seq docs)]
                     (let [r (storage/commit-migration!
                              store
                              {:migration-id (str "recovery-" (UUID/randomUUID))
                               :partition partition
                               :session-id session-id
                               :reason :startup-recovery
                               :obligations {:put docs}})]
                       {:partition partition
                        :session-id session-id
                        :version (:version r)
                        :store-version (:store-version r)})))]
    {:status :ok
     :partition (:partition scanned)
     :partitions (:partitions scanned)
     :scanned (:scanned scanned)
     :applied (:applied scanned)
     :pending-reports (:pending-reports scanned)
     :pending-retries (:pending-retries scanned)
     :blocked-retries (:blocked-retries scanned)
     :outcome-unknown (:outcome-unknown scanned)
     :attention (:attention scanned)
     :commits commits
     :version (or (:version (peek commits)) (:version scanned))
     :store-version (or (:store-version (peek commits)) (:store-version scanned))}))

(defn recover-all!
  "Startup wrapper: recover every initialized partition and return a status
  value instead of throwing an expected storage failure.

  Returns the `recover!` result with `:status :ok`, or
  `{:status :error :error description :path ..}`.  The application must stop
  side-effect dispatch on `:error`."
  [store opts]
  (try
    (recover! store (assoc opts :all-partitions? true))
    (catch clojure.lang.ExceptionInfo e
      (if (storage/expected-error? e)
        (let [code (storage/error-code e)
              p    (layout/path (storage/store-path store))]
          {:status :error
           :error (assoc (storage/error-describe e)
                         :details
                         {:cause code
                          :cause-details
                          (not-empty (dissoc (ex-data e)
                                             :error :storage/expected?))})
           :path (str p)
           :hint (layout/recovery-hint code p)
           :recovery-entry {:action :operator
                            :options (recovery-options code)}})
        (throw e)))))

(defn blocked-entry
  "Convert a failed `recover-all!` result into the startup blocked map used by
  `recovery-entry`.  Returns nil for a successful result."
  [result]
  (when (and (map? result) (not= :ok (:status result)))
    (merge {:status :blocked
            :recovery-entry {:action :operator
                             :options [:inspect-directory
                                       :restore-from-backup
                                       :choose-another-path]}}
           result
           {:status :blocked})))

(defn blocked?
  "True when a recovery entry could not open the store."
  [entry]
  (= :blocked (:status entry)))

(defn blocked-retries
  "Return the storage retry obligations that recovery kept open.

  These obligations have no claimer or dispatcher.  A future supervisor that
  implements the complete claim/dispatch/close loop can consume this list;
  recovery itself never rewrites them."
  [result]
  (vec (:blocked-retries result)))

(defn blocked-retries?
  "True when RESULT contains at least one unclaimed storage retry."
  [result]
  (boolean (seq (blocked-retries result))))
