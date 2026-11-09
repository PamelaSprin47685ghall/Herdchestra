(ns hc.server.app.steward-runtime
  "Adapt injected I/O to the pure Steward loop.

  The runtime owns one Steward document in a Store.  It serializes every
  read-modify-write of that document under one lock, runs
  `hc.compose.steward.loop/tick`, and executes the returned effects in order.

  Boundaries:

  - `hc.compose.steward.loop` stays pure; this namespace owns Git, model,
    Plan, fleet, Store, clock, and thread access.
  - A compare-and-set loss never overwrites another writer.  The runtime
    reloads the committed state, replays the same world, and retries a bounded
    number of times.
  - `on-join-result!` only appends to a queue and schedules a tick.  It never
    performs I/O on the fleet join thread.

  Injected functions:

    git-log-since-fn (fn [{:since sha-or-nil :ref string :now epoch-ms}]
                       -> {:ok? true :head sha :commits [raw-evidence]
                           :master-version n}
                       -> {:ok? false :error {:code ... :message ...}})
    model-port       (fn [effect] -> {:ok? true :value result})
                     or {:meditate! fn, :decide! fn}, or nil
    plan-start-fn    (fn [{:mission-id :intent-version :candidate-id :title}]
                       -> {:ok? true :session-id string}
                       -> {:ok? false :error {...}})
    fork-fn          (fn [slices] -> {:ok? true :swarms [{:swarm-id ...}]})
                     -> :INSUFFICIENT_WORKTREES
                     -> {:ok? false :error {...}}
    on-report-fn     (fn [report] -> any) ; exceptions are recorded, not thrown
    clock            (fn [] epoch-ms)"
  (:require [clojure.string :as str]
            [hc.compose.fleet.service :as fleet-service]
            [hc.compose.steward :as steward]
            [hc.compose.steward.loop :as loop]
            [hc.core.steward-sphinx :as ss]
            [hc.server.app.steward-store :as steward-store])
  (:import (java.util.concurrent Executor ExecutorService Executors
                                 ThreadFactory TimeUnit)
           (java.util.concurrent.atomic AtomicInteger)))

;; ---------------------------------------------------------------------------
;; Constants

(def default-interval-ms
  "Must stay equal to `hc.server.app.config`'s `[:steward :interval-ms]`
  default (60000); change both together."
  60000)
(def default-persist-attempts 3)
(def default-diagnostic-limit 64)
(def default-pending-evidence-limit 256)
(def default-effect-window-ms 300000)
(def max-seen-effects 1024)
(def default-report-ledger-limit 4096)
(def default-stop-timeout-ms 5000)
(def default-pending-limit 256)
(def max-snapshot-stalled 32)

(def empty-pending
  {:intent nil
   :evidence []
   :decisions []
   :revisions []
   :join-results []
   :overflow 0})

;; ---------------------------------------------------------------------------
;; Small helpers

(defn- error
  ([code message] (error code message nil nil))
  ([code message details]
   (error code message details nil))
  ([code message details at]
   (cond-> {:code code :message message}
     (some? details) (assoc :details details)
     (some? at) (assoc :at at))))

(defn- now-ms
  [rt]
  (let [clock (:clock rt)]
    (try
      (long (or (clock) 0))
      (catch Throwable _
        0))))

(defn- daemon-thread-factory
  [prefix]
  (let [counter (atom 0)]
    (reify ThreadFactory
      (newThread [_ runnable]
        (doto (Thread. runnable (str prefix "-" (swap! counter inc)))
          (.setDaemon true))))))

(def ^:private noop-executor
  (reify Executor
    (execute [_ _] nil)))

;; ---------------------------------------------------------------------------
;; Pending input queue

(declare record-diagnostic!)

(defn- take-pending!
  [rt]
  (let [[pending _] (swap-vals! (:pending rt) (constantly empty-pending))]
    pending))

(defn- restore-pending!
  "Put a taken PENDING value back before any newly queued input.

  The failed tick's inputs keep their original order.  If restoring would
  exceed a queue limit, the oldest items are dropped and counted; that loss
  is never silent."
  [rt pending]
  (let [evidence-limit (long (get-in (:config rt) [:max-pending-evidence]
                                     default-pending-evidence-limit))
        item-limit (long (get-in (:config rt) [:pending-limit]
                                 default-pending-limit))
        dropped (volatile! 0)
        dropped-by-queue (volatile! {})]
    (swap! (:pending rt)
           (fn [current]
             (vreset! dropped 0)
             (vreset! dropped-by-queue {})
             (let [merge-queue
                   (fn [key restored limit]
                     (let [combined (vec (concat restored (get current key)))
                           excess (max 0 (- (count combined) limit))
                           kept (if (pos? excess)
                                  (subvec combined excess)
                                  combined)]
                       (when (pos? excess)
                         (vswap! dropped + excess)
                         (vswap! dropped-by-queue update key (fnil + 0)
                                 excess))
                       kept))]
               (-> current
                   (assoc :evidence
                          (merge-queue :evidence (:evidence pending)
                                       evidence-limit))
                   (assoc :decisions
                          (merge-queue :decisions (:decisions pending)
                                       item-limit))
                   (assoc :revisions
                          (merge-queue :revisions (:revisions pending)
                                       item-limit))
                   (assoc :join-results
                          (merge-queue :join-results (:join-results pending)
                                       item-limit))
                   (update :overflow + (:overflow pending 0))
                   (cond-> (nil? (:intent current))
                     (assoc :intent (:intent pending)))))))
    (when (pos? @dropped)
      (swap! (:pending-overflow-dropped rt) + @dropped)
      (record-diagnostic! rt
                          {:type :diagnostic
                           :code :PENDING_OVERFLOW_DROPPED
                           :message
                           "the oldest restored pending input was dropped"
                           :details {:dropped @dropped
                                     :queues @dropped-by-queue}}))))

(defn- pending?
  [rt]
  (let [pending @(:pending rt)]
    (boolean (or (:intent pending)
                 (seq (:evidence pending))
                 (seq (:decisions pending))
                 (seq (:revisions pending))
                 (seq (:join-results pending))))))

(defn- pending-count
  [pending]
  (+ (count (:evidence pending))
     (count (:decisions pending))
     (count (:revisions pending))
     (count (:join-results pending))
     (if (:intent pending) 1 0)))

(defn- stopped-error
  []
  (error :STEWARD_RUNTIME_STOPPED "the Steward runtime is stopped"))

;; ---------------------------------------------------------------------------
;; Errors and diagnostics

(defn- record-diagnostic!
  [rt entry]
  (let [limit (long (get-in (:config rt) [:max-diagnostics]
                            default-diagnostic-limit))
        entry (-> entry
                  (dissoc :type)
                  (update :at #(or % (now-ms rt))))]
    (swap! (:diagnostics rt)
           (fn [items]
             (let [items (conj (vec items) entry)]
               (if (> (count items) limit)
                 (subvec items (- (count items) limit))
                 items))))))

(defn- record-error!
  [rt code message details]
  (let [entry (error code message details (now-ms rt))]
    (reset! (:last-error rt) entry)
    (record-diagnostic! rt (assoc entry :type :diagnostic))
    entry))

;; ---------------------------------------------------------------------------
;; Effect de-duplication

(defn- claim-effect!
  "Claim EFFECT for execution inside the configured window.

  `:persist` is always claimed.  The atom swap is the only arbiter, so two
  concurrent ticks cannot both dispatch one fingerprint."
  [rt effect]
  (if (= :persist (:type effect))
    true
    (let [window (long (get-in (:config rt) [:effect-window-ms]
                               default-effect-window-ms))
          now (now-ms rt)
          fingerprint (loop/effect-fingerprint effect)
          claimed? (volatile! false)]
      (vreset! claimed? false)
      (swap! (:seen rt)
             (fn [seen]
               (let [seen (into {}
                                (remove (fn [[_ at]]
                                          (>= (- now at) window)))
                                seen)
                     previous (get seen fingerprint)]
                 (if (and previous (< (- now previous) window))
                   seen
                   (do (vreset! claimed? true)
                       (assoc seen fingerprint now))))))
      (when (> (count @(:seen rt)) max-seen-effects)
        (swap! (:seen rt)
               (fn [seen]
                 (into {} (take-last max-seen-effects
                                     (sort-by val seen))))))
      @claimed?)))

(defn- release-effect!
  "Forget EFFECT after a failed external call, so a later tick retries it."
  [rt effect]
  (when (not= :persist (:type effect))
    (swap! (:seen rt) dissoc (loop/effect-fingerprint effect)))
  nil)

(defn- claim-report!
  "Claim one report delivery forever, bounded by the configured ledger size.

  Reports are terminal notifications, not retryable effects: a repeated join
  result must not notify twice even after the effect window elapsed.  The
  ledger keeps the newest `:report-ledger-limit` identities (default 4096)
  and evicts the oldest entry when full, so memory stays bounded."
  [rt effect]
  (let [fingerprint (loop/effect-fingerprint effect)
        limit (long (max 1 (get-in (:config rt) [:report-ledger-limit]
                                   default-report-ledger-limit)))
        claimed? (volatile! false)
        now (now-ms rt)]
    (vreset! claimed? false)
    (swap! (:report-ledger rt)
           (fn [ledger]
             (if (contains? ledger fingerprint)
               ledger
               (do
                 (vreset! claimed? true)
                 (let [ledger (assoc ledger fingerprint now)]
                   (if (> (count ledger) limit)
                     (into {} (take-last limit (sort-by val ledger)))
                     ledger))))))
    @claimed?))

(defn- release-report!
  "Forget a failed report delivery so a re-enqueued result can retry."
  [rt effect]
  (swap! (:report-ledger rt) dissoc (loop/effect-fingerprint effect))
  nil)

;; ---------------------------------------------------------------------------
;; Model dispatch

(defn- model-callable
  [rt effect]
  (let [port (:model-port rt)
        direct (:model-fn rt)]
    (or direct
        (when (fn? port) port)
        (when (map? port)
          (case (:type effect)
            :model/meditate (or (:meditate! port) (:model-fn port))
            :model/decide (or (:decide! port) (:model-fn port))
            nil)))))

(defn- revision-of
  [value]
  (cond
    (not (map? value)) nil
    (map? (:revision value)) (:revision value)
    (contains? value :revision) value
    (or (contains? value :summary)
        (contains? value :frontier-of-thought)) value
    :else nil))

(defn- decision-of
  [value effect]
  (cond
    (not (map? value)) nil
    (map? (:decision value)) (:decision value)
    (contains? value :request-id)
    (cond-> (select-keys value [:request-id :candidates :policy])
      (:frontier-id value) (assoc :frontier-id (:frontier-id value)))
    (sequential? (:candidates value))
    {:request-id (str "model/" (:frontier-id effect))
     :candidates (:candidates value)
     :policy (:policy value)}
    :else nil))

(declare submit-revision! submit-decision!)

(defn- interpret-model-result!
  [rt effect result]
  (let [envelope? (and (map? result) (contains? result :ok?))
        ok? (if envelope? (:ok? result) true)
        value (if (and envelope? (contains? result :value))
                (:value result)
                result)]
    (if-not ok?
      (do
        (release-effect! rt effect)
        (record-error! rt :STEWARD_MODEL_FAILED
                       (or (:message result)
                           "the model port refused the request")
                       (select-keys result [:code :error])))
      (case (:type effect)
        :model/meditate
        (when-let [revision (revision-of value)]
          (submit-revision! rt revision))

        :model/decide
        (when-let [decision (decision-of value effect)]
          (submit-decision! rt decision))

        nil))))

(defn- dispatch-model!
  [rt effect]
  (if-not (claim-effect! rt effect)
    {:ok? true :skipped? true :reason :duplicate}
    (if-let [call (model-callable rt effect)]
      (let [active ^AtomicInteger (:active-models rt)]
        (.execute ^Executor (or (:model-executor rt) noop-executor)
                  ^Runnable
                  (fn []
                    (.incrementAndGet active)
                    (try
                      (when @(:running? rt)
                        (interpret-model-result! rt effect (call effect)))
                      (catch Throwable t
                        (release-effect! rt effect)
                        (record-error! rt :STEWARD_MODEL_FAILED
                                       (or (.getMessage t) (str t))
                                       {:class (.getName (class t))
                                        :mission-id (:mission-id effect)}))
                      (finally
                        (.decrementAndGet active)))))
        {:ok? true :dispatched? true})
      (do
        (record-diagnostic!
         rt
         (assoc (select-keys effect [:role :mission-id :frontier-id
                                     :evidence-seq])
                :type :diagnostic
                :code :MODEL_PORT_UNAVAILABLE
                :message "no model port is injected for this effect"))
        {:ok? true :dispatched? false :reason :model-port-unavailable}))))

;; ---------------------------------------------------------------------------
;; Link patches

(defn- patch-state!
  "Record PATCH for MISSION-ID and merge it into the live state.

  The patch is replayed after a compare-and-set reload, so a dispatch that
  already happened is not forgotten by a lost write.  `:status` stays
  authoritative in the Docket; only `transition-mission` moves it."
  [rt mission-id patch target]
  (swap! (:link-patches rt) update mission-id merge patch)
  (swap! (:state rt)
         (fn [state]
           (let [linked (steward/link-mission state mission-id patch)]
             (if-not (:ok? linked)
               state
               (let [state' (:state linked)
                     status (get-in state' [:docket :missions mission-id
                                            :status])]
                 (if (and target (not= target status))
                   (let [moved (steward/transition-mission
                                state' mission-id target "runtime dispatch")]
                     (if (:ok? moved) (:state moved) state'))
                   state')))))))

(defn- apply-link-patches
  [state patches]
  (reduce-kv
   (fn [state mission-id patch]
     (if-not (get-in state [:docket :missions mission-id])
       state
       (let [linked (steward/link-mission state mission-id patch)]
         (if (:ok? linked) (:state linked) state))))
   state
   patches))

;; ---------------------------------------------------------------------------
;; Plan and fleet effects

(defn- execute-plan-start!
  [rt effect]
  (if-not (claim-effect! rt effect)
    {:ok? true :skipped? true :reason :duplicate}
    (let [call (:plan-start-fn rt)]
      (cond
        (nil? call)
        (do
          (record-diagnostic! rt {:type :diagnostic
                                  :code :PLAN_START_UNAVAILABLE
                                  :message "no plan-start function is injected"
                                  :mission-id (:mission-id effect)})
          {:ok? true :dispatched? false})

        :else
        (try
          (let [result (call (select-keys effect [:mission-id :intent-version
                                                  :candidate-id :title]))]
            (cond
              (and (map? result)
                   (:ok? result)
                   (string? (:session-id result))
                   (not (str/blank? (:session-id result))))
              (do
                (patch-state! rt (:mission-id effect)
                              {:session-id (:session-id result)
                               :plan-started-at (now-ms rt)}
                              :planning)
                {:ok? true :session-id (:session-id result)})

              (and (map? result) (:ok? result))
              (do
                (release-effect! rt effect)
                (record-error! rt :STEWARD_PLAN_SESSION_MISSING
                               "plan-start returned no session id"
                               {:mission-id (:mission-id effect)})
                {:ok? false})

              :else
              (let [failure (or (:error result)
                                {:code :STEWARD_PLAN_START_FAILED
                                 :message "plan-start refused the mission"})]
                (release-effect! rt effect)
                (record-error! rt (:code failure) (:message failure)
                               (merge {:mission-id (:mission-id effect)}
                                      (when (map? (:details failure))
                                        (:details failure))))
                {:ok? false :error failure})))
          (catch Throwable t
            (release-effect! rt effect)
            (record-error! rt :STEWARD_PLAN_START_FAILED
                           (or (.getMessage t) (str t))
                           {:class (.getName (class t))
                            :mission-id (:mission-id effect)})
            {:ok? false}))))))

(defn- fork-callable
  [rt]
  (or (:fork-fn rt)
      (when-let [service (:fleet rt)]
        (fn [slices] (fleet-service/fork! service slices)))))

(defn- execute-fork!
  [rt effect]
  (if-not (claim-effect! rt effect)
    {:ok? true :skipped? true :reason :duplicate}
    (let [call (fork-callable rt)]
      (cond
        (nil? call)
        (do
          (record-diagnostic! rt {:type :diagnostic
                                  :code :FORK_UNAVAILABLE
                                  :message "no fleet service or fork function is injected"
                                  :mission-id (:mission-id effect)})
          {:ok? true :dispatched? false})

        :else
        (try
          (let [result (call (:slices effect))]
            (cond
              (= :INSUFFICIENT_WORKTREES result)
              (do
                (release-effect! rt effect)
                (record-error! rt :STEWARD_FORK_INSUFFICIENT_WORKTREES
                               "the runtime has no worktree for the requested slices"
                               {:mission-id (:mission-id effect)
                                :slices (:slices effect)})
                {:ok? false})

              (and (map? result)
                   (:ok? result)
                   (seq (:swarms result)))
              (let [swarm-ids (mapv :swarm-id (:swarms result))]
                (patch-state! rt (:mission-id effect)
                              {:swarm-ids swarm-ids
                               :forked-at (now-ms rt)}
                              :active)
                {:ok? true :swarm-ids swarm-ids})

              (and (map? result) (:ok? result))
              (do
                (release-effect! rt effect)
                (record-error! rt :STEWARD_FORK_NO_SWARMS
                               "fork returned no swarms"
                               {:mission-id (:mission-id effect)})
                {:ok? false})

              :else
              (let [failure (or (:error result)
                                {:code :STEWARD_FORK_FAILED
                                 :message "fork refused the slices"})]
                (release-effect! rt effect)
                (record-error! rt (:code failure) (:message failure)
                               (merge {:mission-id (:mission-id effect)}
                                      (when (map? (:details failure))
                                        (:details failure))))
                {:ok? false :error failure})))
          (catch Throwable t
            (release-effect! rt effect)
            (record-error! rt :STEWARD_FORK_FAILED
                           (or (.getMessage t) (str t))
                           {:class (.getName (class t))
                            :mission-id (:mission-id effect)})
            {:ok? false}))))))

(defn- execute-report!
  [rt effect]
  (if-not (claim-report! rt effect)
    {:ok? true :skipped? true :reason :duplicate}
    (let [call (:on-report-fn rt)]
      (if-not call
        {:ok? true :delivered? false}
        (try
          (call (dissoc effect :at))
          {:ok? true :delivered? true}
          (catch Throwable t
            (release-report! rt effect)
            (record-error! rt :STEWARD_REPORT_FAILED
                           (or (.getMessage t) (str t))
                           (:mission-id effect))
            {:ok? false}))))))

(defn- execute-effect!
  [rt effect]
  (case (:type effect)
    :model/meditate (dispatch-model! rt effect)
    :model/decide (dispatch-model! rt effect)
    :plan/start (execute-plan-start! rt effect)
    :fleet/fork (execute-fork! rt effect)
    :report/emit (execute-report! rt effect)
    :diagnostic (do (record-diagnostic! rt effect)
                    {:ok? true :recorded? true})
    :persist {:ok? true :deferred? true}
    (do
      (record-diagnostic! rt (assoc effect :type :diagnostic
                                   :code :UNKNOWN_EFFECT
                                   :message "the loop emitted an unknown effect"))
      {:ok? false})))

;; ---------------------------------------------------------------------------
;; World collection

(defn- fetch-git!
  [rt now]
  (if-let [call (:git-log-since-fn rt)]
    (try
      (let [cursor @(:git-cursor rt)
            result (call {:since (:head cursor)
                          :ref (get-in (:config rt) [:git-ref] "master")
                          :now now})]
        (cond
          (true? (:ok? result))
          (let [normalized (mapv ss/normalize-evidence
                                 (or (:commits result) []))
                invalid (first (remove :ok? normalized))]
            (if invalid
              {:ok? false :error (:error invalid)}
              {:ok? true
               :head (:head result)
               :commits (mapv :value normalized)
               :master-version (:master-version result)}))

          (and (map? result) (:error result))
          {:ok? false :error (:error result)}

          :else
          {:ok? false
           :error (error :STEWARD_GIT_OBSERVATION_FAILED
                         "git-log-since returned an unrecognized value"
                         {:result result})}))
      (catch Throwable t
        {:ok? false
         :error (error :STEWARD_GIT_OBSERVATION_FAILED
                       (or (.getMessage t) (str t))
                       {:class (.getName (class t))})}))
    {:ok? true :head nil :commits [] :master-version nil}))

(defn- fetch-fleet-rows
  [rt]
  (if-let [service (:fleet rt)]
    (try
      {:ok? true :rows (vec (fleet-service/fleet-rows service))}
      (catch Throwable t
        {:ok? false
         :error (error :STEWARD_FLEET_OBSERVATION_FAILED
                       (or (.getMessage t) (str t))
                       {:class (.getName (class t))})}))
    {:ok? true :rows []}))

(defn- build-world
  [rt pending now]
  (let [config (:config rt)
        git-result (fetch-git! rt now)
        fleet-result (fetch-fleet-rows rt)
        stale? (or (not (:ok? git-result)) (not (:ok? fleet-result)))
        capacity (when (:ok? fleet-result)
                   {:rows (:rows fleet-result)
                    :slots-total (get config :slots-total 4)
                    :min-spare (get config :min-spare 1)})
        git (if (:ok? git-result)
              {:head (:head git-result)
               :new-commits (:commits git-result)
               :master-version (:master-version git-result)}
              {:head nil :new-commits [] :master-version nil})
        ;; Evidence submitted through `submit-evidence!` is already
        ;; normalized; it joins this tick's Git observation.  A stale Git
        ;; upstream suppresses decisions but never silently drops evidence.
        commits (vec (concat (:evidence pending)
                             (get-in git [:new-commits])))]
    (doseq [result [git-result fleet-result]
            :when (not (:ok? result))]
      (record-error! rt
                     (get-in result [:error :code])
                     (get-in result [:error :message])
                     (get-in result [:error :details])))
    {:ok? true
     :world {:now now
             :git (assoc git :new-commits commits)
             :intent (:intent pending)
             :capacity capacity
             :plan-stall-ms (get config :plan-stall-ms)
             :stale? stale?
             :decision (seq (:decisions pending))
             :fleet {:results (:join-results pending)}}}))

;; ---------------------------------------------------------------------------
;; Revisions and persistence

(defn- apply-revisions
  [rt state revisions]
  (reduce (fn [state revision]
            (let [result (steward/revise-cognition state revision)]
              (if (:ok? result)
                (:state result)
                (do
                  (record-error! rt
                                 (get-in result [:error :code])
                                 (get-in result [:error :message])
                                 (get-in result [:error :details]))
                  state))))
          state
          revisions))

(defn- advance-git-cursor!
  [rt world]
  (when-let [head (get-in world [:git :head])]
    (swap! (:git-cursor rt)
           merge {:head head
                  :master-version (get-in world [:git :master-version])
                  :last-ok-at (now-ms rt)})))

(defn- persist-with-retry!
  [rt world revisions]
  (let [max-attempts (long (get-in (:config rt) [:persist-attempts]
                                   default-persist-attempts))]
    (loop [attempt 0]
      (if-not @(:running? rt)
        {:ok? false :error (stopped-error)}
        (let [state (apply-link-patches @(:state rt) @(:link-patches rt))
            _ (reset! (:state rt) state)
            version @(:version rt)
            result (try
                     (steward-store/commit! (:store rt) version state)
                     (catch Throwable t
                       {:ok? false
                        :error (error :STEWARD_PERSIST_FAILED
                                      (or (.getMessage t) (str t))
                                      {:class (.getName (class t))})}))]
        (cond
          (:ok? result)
          (do
            (reset! (:persisted-state rt) state)
            (reset! (:version rt) (:version result))
            {:ok? true :attempts (inc attempt)})

          (and (= :VERSION_CONFLICT (get-in result [:error :code]))
               (< (inc attempt) max-attempts))
          (let [loaded (try
                         (steward-store/load-state (:store rt))
                         (catch Throwable t
                           {:load-error t}))]
            (if (:load-error loaded)
              (do
                (record-error! rt :STEWARD_PERSIST_RELOAD_FAILED
                               (or (.getMessage ^Throwable (:load-error loaded))
                                   (str (:load-error loaded)))
                               nil)
                {:ok? false :reloaded? false})
              (let [fresh (apply-revisions
                           rt (or (:state loaded) (steward/initial-state))
                           revisions)
                    replanned (loop/tick fresh world)
                    _ (reset! (:state rt) (:state replanned))]
                (reset! (:persisted-state rt)
                        (or (:state loaded) (steward/initial-state)))
                (reset! (:version rt) (long (or (:version loaded) 0)))
                (doseq [effect (:effects replanned)
                        :when (not= :persist (:type effect))]
                  (execute-effect! rt effect))
                (recur (inc attempt)))))

          :else
          (let [conflict? (= :VERSION_CONFLICT
                             (get-in result [:error :code]))]
            (when conflict?
              (swap! (:persist-conflicts rt) inc))
            (record-error!
             rt
             (if conflict? :persist-conflict :STEWARD_PERSIST_FAILED)
             (if conflict?
               "another writer committed the Steward state; this write was abandoned"
               (get-in result [:error :message]))
             (get-in result [:error :details]))
            {:ok? false :error (:error result)})))))))

(defn- run-once!
  [rt pending world]
  (let [before-revisions (apply-link-patches @(:state rt)
                                             @(:link-patches rt))
        state (apply-revisions rt before-revisions (:revisions pending))
        revisions-changed? (not= before-revisions state)
        _ (reset! (:state rt) state)
        result (loop/tick state world)
        _ (reset! (:state rt) (:state result))
        _ (doseq [effect (:effects result)
                  :when (not= :persist (:type effect))]
            (execute-effect! rt effect))
        requested? (some #(= :persist (:type %)) (:effects result))
        ;; Plan and fork dispatch may add link state after the loop returned.
        changed? (or requested?
                     revisions-changed?
                     (not= @(:state rt) @(:persisted-state rt))
                     (not= (:state result) @(:state rt)))
        persisted (if changed?
                    (persist-with-retry! rt world (:revisions pending))
                    {:ok? true :attempts 0 :skipped? true})
        _ (when (:ok? persisted)
            (advance-git-cursor! rt world))]
    (cond-> {:ok? (:ok? persisted)
             :committed? (:ok? persisted)
             :effects (count (:effects result))
             :observations (:observations result)
             :skipped (:skipped result)
             :persist-attempts (:attempts persisted)
             :persist-error (:error persisted)}
      (not (:ok? persisted))
      (assoc :error (:error persisted)))))

;; ---------------------------------------------------------------------------
;; Scheduling

(declare tick!)

(defn- schedule-tick!
  [rt]
  (when (and @(:running? rt)
             (compare-and-set! (:tick-scheduled? rt) false true))
    (try
      (.execute ^Executor (:tick-executor rt)
                ^Runnable
                (fn []
                  (try
                    (tick! rt)
                    (finally
                      (reset! (:tick-scheduled? rt) false)
                      (when (and @(:running? rt) (pending? rt))
                        (schedule-tick! rt))))))
      (catch Throwable t
        (reset! (:tick-scheduled? rt) false)
        (record-error! rt :STEWARD_DISPATCH_FAILED
                       (or (.getMessage t) (str t))
                       {:class (.getName (class t))})))))

;; ---------------------------------------------------------------------------
;; Public API

(defn- valid-intent-input?
  [intent]
  (and (map? intent)
       (integer? (:revision intent))
       (not (neg? (:revision intent)))
       (string? (:text intent))
       (not (str/blank? (:text intent)))))

(defn submit-intent!
  "Queue one operator intent.  A repeated identical intent is harmless."
  [rt intent]
  (if-not (and rt @(:running? rt))
    {:ok? false :error (error :STEWARD_RUNTIME_STOPPED
                              "the runtime is stopped")}
    (let [value (cond-> intent
                  (and (map? intent) (nil? (:received-at intent)))
                  (assoc :received-at (now-ms rt)))
          normalized (ss/normalize-intent value)]
      (cond
        (not (valid-intent-input? value))
        (let [failure (error :INVALID_INTENT
                             "operator intent needs :revision and non-blank :text"
                             {:intent intent})]
          (record-error! rt (:code failure) (:message failure) (:details failure))
          {:ok? false :error failure})

        (not (:ok? normalized))
        (let [failure (:error normalized)]
          (record-error! rt (:code failure) (:message failure) (:details failure))
          {:ok? false :error failure})

        :else
        (do
          (swap! (:pending rt) assoc :intent (:value normalized))
          (schedule-tick! rt)
          (if @(:running? rt)
            {:ok? true :queued? true :intent (:value normalized)}
            {:ok? false :error (stopped-error)}))))))

(defn submit-evidence!
  "Queue raw or normalized evidence.  The batch is all-or-nothing."
  [rt evidence]
  (if-not (and rt @(:running? rt))
    {:ok? false :error (error :STEWARD_RUNTIME_STOPPED
                              "the runtime is stopped")}
    (let [items (cond
                  (nil? evidence) []
                  (map? evidence) [evidence]
                  (sequential? evidence) (vec evidence)
                  :else nil)]
      (cond
        (nil? items)
        (let [failure (error :INVALID_EVIDENCE
                             "evidence must be a map or a sequential collection")]
          (record-error! rt (:code failure) (:message failure) (:details failure))
          {:ok? false :error failure})

        (empty? items)
        {:ok? true :queued? false :reason :empty}

        :else
        (let [normalized (mapv ss/normalize-evidence items)
              invalid (first (remove :ok? normalized))]
          (if invalid
            (let [failure (:error invalid)]
              (record-error! rt (:code failure) (:message failure)
                             (:details failure))
              {:ok? false :error failure})
            (let [values (mapv :value normalized)
                  limit (long (get-in (:config rt) [:max-pending-evidence]
                                      default-pending-evidence-limit))
                  dropped (volatile! 0)]
              (swap! (:pending rt)
                     (fn [pending]
                       (let [all (into (vec (:evidence pending)) values)
                             excess (max 0 (- (count all) limit))]
                         (vreset! dropped excess)
                         (cond-> (assoc pending :evidence
                                        (if (pos? excess)
                                          (subvec all excess)
                                          all))
                           (pos? excess) (update :overflow + excess)))))
              (when (pos? @dropped)
                (record-diagnostic! rt
                                    {:type :diagnostic
                                     :code :EVIDENCE_PENDING_OVERFLOW
                                     :message "oldest pending evidence was dropped"
                                     :details {:dropped @dropped}}))
              (schedule-tick! rt)
              (if @(:running? rt)
                {:ok? true
                 :queued? true
                 :evidence-ids (mapv :evidence-id values)}
                {:ok? false :error (stopped-error)}))))))))

(defn submit-decision!
  "Queue one or more model decisions.  The loop owns validation."
  [rt decision]
  (if-not (and rt @(:running? rt))
    {:ok? false :error (error :STEWARD_RUNTIME_STOPPED
                              "the runtime is stopped")}
    (let [items (cond
                  (nil? decision) []
                  (map? decision) [decision]
                  (sequential? decision) (vec (filter map? decision))
                  :else nil)
          invalid (first (remove #(and (string? (:request-id %))
                                       (not (str/blank? (:request-id %)))
                                       (sequential? (:candidates %)))
                                 items))]
      (cond
        (nil? items)
        (let [failure (error :INVALID_DECISION
                             "a decision must be a map or a sequential collection")]
          (record-error! rt (:code failure) (:message failure) (:details failure))
          {:ok? false :error failure})

        (empty? items)
        {:ok? true :queued? false :reason :empty}

        invalid
        (let [failure (error :INVALID_DECISION
                             "a decision needs a non-blank :request-id and sequential :candidates"
                             {:decision invalid})]
          (record-error! rt (:code failure) (:message failure) (:details failure))
          {:ok? false :error failure})

        :else
        (do
          (swap! (:pending rt) update :decisions into items)
          (schedule-tick! rt)
          (if @(:running? rt)
            {:ok? true
             :queued? true
             :request-ids (mapv :request-id items)}
            {:ok? false :error (stopped-error)}))))))

(defn submit-revision!
  "Queue one meditator revision for `steward/revise-cognition`."
  [rt revision]
  (if-not (and rt @(:running? rt))
    {:ok? false :error (error :STEWARD_RUNTIME_STOPPED
                              "the runtime is stopped")}
    (if-not (and (map? revision) (string? (:summary revision)))
      (let [failure (error :INVALID_REVISION
                           "a revision needs a :summary string"
                           {:revision revision})]
        (record-error! rt (:code failure) (:message failure) (:details failure))
        {:ok? false :error failure})
      (do
        (swap! (:pending rt) update :revisions conj revision)
        (schedule-tick! rt)
        (if @(:running? rt)
          {:ok? true :queued? true}
          {:ok? false :error (stopped-error)})))))

(defn tick!
  "Run one complete advance synchronously.

  The supervisor's `:tick!` and tests use this function.  It serializes with
  every other Store writer in this process.  A tick that reaches the Store
  after `stop!` began abandons its write and restores the inputs it took."
  [rt]
  (if (nil? rt)
    {:ok? false :error (error :STEWARD_RUNTIME_MISSING "no runtime" nil nil)}
    (let [active ^AtomicInteger (:active-ticks rt)]
      (.incrementAndGet active)
      (try
        (if-not @(:running? rt)
          {:ok? false :error (stopped-error)}
          (locking (:lock rt)
            (if-not @(:running? rt)
              {:ok? false :error (stopped-error)}
              (let [pending (take-pending! rt)
                    now (now-ms rt)]
                (try
                  (let [built (build-world rt pending now)]
                    (if-not (:ok? built)
                      (do
                        (restore-pending! rt pending)
                        (reset! (:state rt) @(:persisted-state rt))
                        {:ok? false :error (:error built)})
                      (let [result (run-once! rt pending (:world built))]
                        (reset! (:last-tick-at rt) now)
                        (if (:ok? result)
                          result
                          (do
                            (restore-pending! rt pending)
                            (reset! (:state rt) @(:persisted-state rt))
                            (assoc result :restored? true))))))
                  (catch Throwable t
                    (restore-pending! rt pending)
                    (reset! (:state rt) @(:persisted-state rt))
                    (let [failure (record-error!
                                   rt :STEWARD_TICK_FAILED
                                   (or (.getMessage t) (str t))
                                   {:class (.getName (class t))})]
                      {:ok? false :error failure})))))))
        (finally
          (.decrementAndGet active))))))

(defn on-plan-ready!
  "Plan handoff callback: mark one Mission plan-ready with its slices.

  This only updates in-memory state and schedules a tick; it never blocks on
  I/O.  The loop emits `:fleet/fork` on the next tick."
  [rt mission-id plan-info]
  (cond
    (nil? rt)
    {:ok? false :error (error :STEWARD_RUNTIME_MISSING "no runtime" nil nil)}

    (not @(:running? rt))
    {:ok? false :error (error :STEWARD_RUNTIME_STOPPED
                              "the runtime is stopped")}

    (nil? (get-in @(:state rt) [:docket :missions mission-id]))
    (do
      (let [failure (error :UNKNOWN_MISSION
                           "plan handoff names an unknown mission"
                           {:mission-id mission-id})]
        (record-error! rt (:code failure) (:message failure) (:details failure))
        {:ok? false :error failure}))

    (not (contains? #{:admitted :planning}
                    (get-in @(:state rt)
                            [:docket :missions mission-id :status])))
    (let [failure (error :MISSION_NOT_PLANNING
                         "plan handoff arrived after the mission left planning"
                         {:mission-id mission-id
                          :status (get-in @(:state rt)
                                          [:docket :missions mission-id
                                           :status])})]
      (record-error! rt (:code failure) (:message failure) (:details failure))
      {:ok? false :error failure})

    (nil? plan-info)
    (let [failure (error :INVALID_PLAN_HANDOFF
                         "plan handoff needs a map"
                         {:mission-id mission-id})]
      (record-error! rt (:code failure) (:message failure) (:details failure))
      {:ok? false :error failure})

    :else
    (let [patch (cond-> {:plan-status :plan-ready
                         :plan-ready-at (now-ms rt)}
                  (string? (:session-id plan-info))
                  (assoc :session-id (:session-id plan-info))

                  (sequential? (:slices plan-info))
                  (assoc :slices (vec (:slices plan-info)))

                  (some? (:plan-version plan-info))
                  (assoc :plan-version (:plan-version plan-info)))]
      (patch-state! rt mission-id patch :planning)
      (schedule-tick! rt)
      (if @(:running? rt)
        {:ok? true :queued? true :mission-id mission-id}
        {:ok? false :error (stopped-error)}))))

(defn on-join-result!
  "Fleet join callback.  Queues the result and returns immediately."
  [rt service result opts]
  (let [rt (or rt (:runtime opts))]
    (cond
      (nil? rt)
      {:ok? false :error (error :STEWARD_RUNTIME_MISSING "no runtime" nil nil)}

      (not @(:running? rt))
      {:ok? false :error (error :STEWARD_RUNTIME_STOPPED
                                "the runtime is stopped")}

      (contains? #{:none :no-results} result)
      {:ok? true :queued? false :reason result}

      (not (map? result))
      {:ok? true :queued? false :reason :unrecognized-result}

      :else
      (let [state @(:state rt)
            mission-id (or (:mission-id opts)
                           (:mission-id result)
                           (let [swarm-id (:swarm-id result)]
                             (when (string? swarm-id)
                               (some (fn [[id link]]
                                       (when (some #{swarm-id}
                                                   (:swarm-ids link))
                                         id))
                                     (:links state)))))]
        (swap! (:pending rt) update :join-results conj
               {:mission-id mission-id
                :result result
                :at (now-ms rt)})
        (schedule-tick! rt)
        (if @(:running? rt)
          {:ok? true :queued? true :mission-id mission-id}
          {:ok? false :error (stopped-error)})))))

(defn start!
  "Assemble one Steward runtime.

  Options:

    :store            required Store (`steward-store` or any Store)
    :git              a git-log-since function, or {:log-since-fn fn}
    :fleet            `hc.compose.fleet.service` value; used for rows and fork
    :fork-fn          overrides the fleet service fork call
    :plan-start-fn    Plan handoff launcher
    :model-port       model function or {:meditate! fn :decide! fn}
    :on-report-fn     report sink
    :clock            clock function, default System/currentTimeMillis
    :supervisor-clock used as the clock when :clock is absent
    :config           {:interval-ms :git-ref :plan-stall-ms :stop-timeout-ms
                       :persist-attempts :max-diagnostics
                       :max-pending-evidence :effect-window-ms
                       :slots-total :min-spare}
    :tick-executor    optional Executor for scheduled ticks
    :model-executor   optional Executor for model calls

  Returns `{:ok? true :runtime rt}` or `{:ok? false :error {...}}`.  The
  runtime does not start a thread; `child-spec` binds it to the supervisor."
  [{:keys [store git fleet supervisor-clock config model-port model-fn
           plan-start-fn fork-fn on-report-fn interval-ms clock
           tick-executor model-executor git-log-since-fn]}]
  (let [config (merge {:interval-ms default-interval-ms
                       :git-ref "master"
                       :persist-attempts default-persist-attempts
                       :max-diagnostics default-diagnostic-limit
                       :max-pending-evidence default-pending-evidence-limit
                       :effect-window-ms default-effect-window-ms
                       :report-ledger-limit default-report-ledger-limit
                       :stop-timeout-ms default-stop-timeout-ms
                       :pending-limit default-pending-limit
                       :plan-stall-ms loop/default-plan-stall-ms
                       :slots-total 4
                       :min-spare 1}
                      config
                      (when interval-ms {:interval-ms interval-ms}))
        clock (or clock supervisor-clock #(System/currentTimeMillis))
        git-fn (or (when (fn? git-log-since-fn) git-log-since-fn)
                   (cond
                     (fn? git) git
                     (map? git) (or (:log-since-fn git)
                                    (:git-log-since-fn git))
                     :else nil))
        fleet-service (cond
                        (nil? fleet) nil
                        (and (map? fleet) (some? (:service fleet)))
                        (:service fleet)
                        :else fleet)]
    (cond
      (nil? store)
      {:ok? false
       :error (error :STEWARD_RUNTIME_INVALID_OPTIONS
                     "a Steward Store is required")}

      (not (and (integer? (:interval-ms config))
                (pos? (:interval-ms config))))
      {:ok? false
       :error (error :STEWARD_RUNTIME_INVALID_OPTIONS
                     "interval-ms must be a positive integer"
                     {:interval-ms (:interval-ms config)})}

      (not (and (integer? (:plan-stall-ms config))
                (pos? (:plan-stall-ms config))))
      {:ok? false
       :error (error :STEWARD_RUNTIME_INVALID_OPTIONS
                     "plan-stall-ms must be a positive integer"
                     {:plan-stall-ms (:plan-stall-ms config)})}

      :else
      (try
        (let [{:keys [version state]} (steward-store/load-state store)
              initial (or state (steward/initial-state))
              model? (or model-fn model-port)
              owns-tick-executor? (nil? tick-executor)
              owns-model-executor? (and model? (nil? model-executor))
              tick-executor (or tick-executor
                                (Executors/newSingleThreadExecutor
                                 (daemon-thread-factory "hc-steward-tick")))
              model-executor (or model-executor
                                 (when model?
                                   (Executors/newCachedThreadPool
                                    (daemon-thread-factory
                                     "hc-steward-model"))))
              rt {:store store
                  :git-log-since-fn git-fn
                  :fleet fleet-service
                  :fork-fn fork-fn
                  :plan-start-fn plan-start-fn
                  :model-port model-port
                  :model-fn model-fn
                  :on-report-fn on-report-fn
                  :clock clock
                  :config config
                  :lock (Object.)
                  :state (atom initial)
                  :persisted-state (atom initial)
                  :version (atom (long (or version 0)))
                  :running? (atom true)
                  :pending (atom empty-pending)
                  :last-tick-at (atom nil)
                  :last-error (atom nil)
                  :persist-conflicts (atom 0)
                  :pending-overflow-dropped (atom 0)
                  :active-ticks (AtomicInteger. 0)
                  :active-models (AtomicInteger. 0)
                  :diagnostics (atom [])
                  :seen (atom {})
                  :report-ledger (atom {})
                  :link-patches (atom {})
                  :git-cursor (atom {:head nil
                                     :master-version nil
                                     :last-ok-at nil})
                  :tick-scheduled? (atom false)
                  :tick-executor tick-executor
                  :model-executor (or model-executor noop-executor)
                  :owns-tick-executor? owns-tick-executor?
                  :owns-model-executor? owns-model-executor?}]
          (when-not (:ok? (loop/check-invariants initial))
            (record-error! rt :STEWARD_STATE_INVALID
                           "the loaded Steward state violates an invariant"
                           {:errors (:errors
                                     (loop/check-invariants initial))}))
          {:ok? true :runtime rt})
        (catch Throwable t
          {:ok? false
           :error (error :STEWARD_RUNTIME_START_FAILED
                         (or (.getMessage t) (str t))
                         {:class (.getName (class t))})})))))

(defn- active-ticks
  [rt]
  (.get ^AtomicInteger (:active-ticks rt)))

(defn- active-models
  [rt]
  (.get ^AtomicInteger (:active-models rt)))

(defn- remaining-ms
  [deadline]
  (max 0 (long (/ (- deadline (System/nanoTime)) 1000000))))

(defn- shutdown-executor!
  [executor]
  (when (instance? ExecutorService executor)
    (try
      (.shutdownNow ^ExecutorService executor)
      (catch Throwable _ nil))))

(defn- await-executor!
  [executor deadline]
  (if-not (instance? ExecutorService executor)
    true
    (try
      (.awaitTermination ^ExecutorService executor
                         (remaining-ms deadline)
                         TimeUnit/MILLISECONDS)
      (catch InterruptedException _
        false))))

(defn- await-drained!
  [rt deadline]
  (loop []
    (let [ticks (active-ticks rt)
          models (active-models rt)]
      (cond
        (and (zero? ticks) (zero? models))
        {:drained? true :pending-ticks ticks :pending-models models}

        (>= (System/nanoTime) deadline)
        {:drained? false :pending-ticks ticks :pending-models models}

        :else
        (do
          (try
            (Thread/sleep 10)
            (catch InterruptedException _ nil))
          (recur))))))

(defn stop!
  "Stop the runtime and wait, bounded by `:stop-timeout-ms`.

  Returns:

    {:ok? bool
     :stopped? bool
     :terminated? bool
     :pending-ticks n
     :pending-models n
     :store-closed? bool
     :store-close-deferred? bool
     :error {:code ...}}        ; only when not terminated

  Only `:terminated? true` means no tick or model task remains and no later
  commit can start.  The caller must not close the shared Store until then.
  `:close-store? true` is honored only when termination succeeded."
  [rt]
  (if (nil? rt)
    {:ok? true
     :stopped? false
     :terminated? true
     :pending-ticks 0
     :pending-models 0
     :store-closed? false}
    (let [was-running? @(:running? rt)
          timeout (long (max 1 (get-in (:config rt) [:stop-timeout-ms]
                                       default-stop-timeout-ms)))
          deadline (+ (System/nanoTime) (* timeout 1000000))
          owned (cond-> []
                  (:owns-tick-executor? rt) (conj (:tick-executor rt))
                  (:owns-model-executor? rt) (conj (:model-executor rt)))]
      (reset! (:running? rt) false)
      (doseq [executor owned]
        (shutdown-executor! executor))
      (let [drained (await-drained! rt deadline)
            executors-terminated?
            (every? #(await-executor! % deadline) owned)
            ticks (active-ticks rt)
            models (active-models rt)
            terminated? (and (:drained? drained)
                             executors-terminated?
                             (zero? ticks)
                             (zero? models))
            result (cond-> {:ok? terminated?
                            :stopped? was-running?
                            :terminated? terminated?
                            :pending-ticks ticks
                            :pending-models models}
                     (not terminated?)
                     (assoc :error
                            (error
                             :STEWARD_STOP_TIMEOUT
                             "the Steward runtime did not drain before the stop deadline"
                             {:timeout-ms timeout
                              :pending-ticks ticks
                              :pending-models models})))
            closed? (when (and terminated?
                               (get-in (:config rt) [:close-store?] false))
                      (steward-store/close! (:store rt)))]
        (cond-> (assoc result :store-closed? (boolean closed?))
          (and (not terminated?)
               (get-in (:config rt) [:close-store?] false))
          (assoc :store-close-deferred? true))))))

(defn- stalled-missions
  "Bounded plan-stall view for the operator snapshot."
  [state now threshold]
  (->> (:links state)
       (keep (fn [[mission-id link]]
               (let [mission (get-in state [:docket :missions mission-id])
                     status (:status mission)
                     session-id (:session-id link)
                     started (:plan-started-at link)
                     stalled-ms (when (and (number? now)
                                           (number? started))
                                  (- now started))]
                 (when (and mission
                            (contains? #{:admitted :planning} status)
                            (string? session-id)
                            (not (str/blank? session-id))
                            (not= :plan-ready (:plan-status link))
                            (number? stalled-ms)
                            (> stalled-ms threshold))
                   {:mission-id mission-id
                    :stalled-ms stalled-ms}))))
       (sort-by (fn [{:keys [mission-id stalled-ms]}]
                  [(- stalled-ms) (str mission-id)]))
       (take max-snapshot-stalled)
       vec))

(defn snapshot
  "A bounded runtime summary.  Raw evidence never appears here."
  [rt]
  (when rt
    (let [pending @(:pending rt)
          state @(:state rt)
          now (now-ms rt)
          stall-threshold (long (or (get-in (:config rt) [:plan-stall-ms])
                                    loop/default-plan-stall-ms))
          stalled (stalled-missions state now stall-threshold)
          diagnostics (vec (take-last 20 @(:diagnostics rt)))
          last-error (some-> @(:last-error rt)
                             (select-keys [:code :message :at]))
          steward-summary (try
                            {:steward (steward/snapshot state)}
                            (catch Throwable t
                              {:steward nil
                               :snapshot-error
                               {:code :STEWARD_SNAPSHOT_FAILED
                                :message (or (.getMessage t) (str t))}}))]
      (merge
       steward-summary
       {:running? @(:running? rt)
        :last-tick-at @(:last-tick-at rt)
        :last-error last-error
        :persist-conflicts @(:persist-conflicts rt)
        :pending-count (pending-count pending)
        :dropped-count @(:pending-overflow-dropped rt)
        :reports-delivered (count @(:report-ledger rt))
        :stalled-missions stalled
        :pending {:intent? (some? (:intent pending))
                  :evidence-count (count (:evidence pending))
                  :decision-count (count (:decisions pending))
                  :revision-count (count (:revisions pending))
                  :join-result-count (count (:join-results pending))
                  :overflow (:overflow pending)}
        :git {:head (:head @(:git-cursor rt))
              :master-version (:master-version @(:git-cursor rt))
              :last-ok-at (:last-ok-at @(:git-cursor rt))}
        :diagnostics diagnostics}))))

(defn health
  "Health fields for the application health map.  Keep this bounded."
  [rt]
  (if (nil? rt)
    {:configured? false :started? false :status :unavailable}
    (let [running? @(:running? rt)
          last-error (some-> @(:last-error rt)
                             (select-keys [:code :message :at]))]
      {:configured? true
       :started? running?
       :status (if running? :started :stopped)
       :last-tick-at @(:last-tick-at rt)
       :persist-conflicts @(:persist-conflicts rt)
       :last-error last-error})))

(defn child-spec
  "A `hc.compose.supervisor` periodic child backed by RT.

  The supervisor calls `:start!` once, `:tick!` on the fixed delay, and
  `:stop!` in reverse start order."
  ([rt] (child-spec rt {}))
  ([rt {:keys [id interval-ms restart backoff-ms critical?]}]
   (when rt
     {:id (or id :steward-loop)
      :start! (fn [_ctx] rt)
      :tick! (fn [_ctx] (tick! rt))
      :stop! (fn [_handle] (stop! rt))
      :interval-ms (long (or interval-ms
                             (get-in (:config rt) [:interval-ms])
                             default-interval-ms))
      :restart (or restart :permanent)
      :backoff-ms (or backoff-ms [1000 60000])
      :critical? (boolean critical?)})))
