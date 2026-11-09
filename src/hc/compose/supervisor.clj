(ns hc.compose.supervisor
  "Process-level supervision for long-lived background loops.

  One supervisor owns the process lifecycle of a small set of independent
  children, such as `:sphinx-loop`, `:steward-loop`, `:fleet-gc`, and
  `:retention-gc`.  It is not an Owner and it never mutates session state.
  Each child gets one explicit context and one dedicated daemon worker
  thread.  A child failure stays inside that child.

  Two child kinds share one contract:

  - periodic: the child provides `:tick!` and `:interval-ms`.  The worker
    calls the tick on a fixed delay.  A thrown tick is counted, the child
    state becomes `:backing-off`, and the next attempt waits a bounded
    exponential backoff.  Siblings never notice.
  - lifecycle: `:start!` returns a handle and `:stop!` releases it.  The
    worker watches the handle and restarts it according to `:restart`.

  `start!` starts every child once, in declaration order, and then launches
  the workers.  `stop!` signals every child first, then stops them in
  reverse start order and waits for an in-flight tick.  `status` reports
  facts.  `trigger!` runs one unit synchronously for deterministic tests and
  event-driven wake-ups.

  Callbacks must not call back into the same supervisor synchronously.  The
  supervisor holds a per-child lock while it calls `:start!`, `:tick!`, and
  `:stop!`."
  (:require [hc.compose.retry.policy :as retry-policy])
  (:import (java.util.concurrent CancellationException ExecutionException
                                 Future TimeUnit TimeoutException)
           (java.util.concurrent.atomic AtomicLong)
           (java.util.concurrent.locks ReentrantLock)))

(def default-backoff-ms
  "First retry waits 500 ms.  Each later retry doubles, capped at 30 s."
  [500 30000])

(def default-poll-ms
  "Liveness poll slice for lifecycle handles and for the stop flag."
  100)

(def default-idle-poll-ms
  "A failed or finished child still owns a worker.  The worker idles on this
  slice so `trigger!` and `stop!` can wake it without a busy loop."
  60000)

(def default-stop-timeout-ms
  "Total time `stop!` gives all children to finish."
  5000)

(def ^:private restart-policies #{:permanent :transient :temporary})

(def ^:private thread-counter (AtomicLong.))

;; ---------------------------------------------------------------------------
;; Errors and small validators

(defn- failure
  ([code message]
   (failure code message nil))
  ([code message details]
   {:ok? false
    :error (cond-> {:code code :message message}
             (some? details) (assoc :details details))}))

(defn- error-of
  "Convert a throwable to a data value.  A throwable `ex-data` `:code` wins."
  [default-code ^Throwable throwable]
  (let [data (ex-data throwable)
        details (when (map? data) (dissoc data :code))]
    (cond-> {:code (or (:code data) default-code)
             :message (or (.getMessage throwable) (str throwable))
             :class (.getName (class throwable))}
      (seq details) (assoc :details details))))

(defn- positive-integer?
  [value]
  (and (integer? value)
       (pos? value)
       (<= value Long/MAX_VALUE)))

(defn- normalize-backoff
  [value]
  (if (nil? value)
    default-backoff-ms
    (when (and (sequential? value)
               (= 2 (count value))
               (positive-integer? (first value))
               (positive-integer? (second value))
               (<= (long (first value)) (long (second value))))
      [(long (first value)) (long (second value))])))

(defn backoff-delay
  "Capped exponential delay for the N-th consecutive failure.

  BOUNDS is `[min-ms max-ms]` with positive integers and `min-ms <= max-ms`.
  N is 1-based: the first failure waits `min-ms`, and each later failure
  doubles the delay until `max-ms`.  The function is pure and never spins,
  even for an enormous N."
  [bounds consecutive-failures]
  (when-not (and (sequential? bounds)
                 (= 2 (count bounds))
                 (positive-integer? (first bounds))
                 (positive-integer? (second bounds))
                 (<= (long (first bounds)) (long (second bounds))))
    (throw (ex-info "backoff bounds must be [min-ms max-ms], 0 < min <= max"
                    {:bounds bounds})))
  (let [[min-ms max-ms] bounds]
    (retry-policy/backoff-delay
     {:base-delay-ms (long min-ms)
      :max-delay-ms (long max-ms)}
     (max 1 (long (or consecutive-failures 1))))))

;; ---------------------------------------------------------------------------
;; Child specification

(defn- child-spec-error
  [index id code message details]
  (failure code message
           (merge {:index index}
                  (when (some? id) {:child-id id})
                  details)))

(defn- normalize-child
  [index spec]
  (let [id (when (map? spec) (:id spec))
        periodic? (and (map? spec) (contains? spec :tick!))]
    (cond
      (not (map? spec))
      (child-spec-error index nil :CHILD_SPEC_INVALID
                        "each supervisor child must be a map" {:spec spec})

      (not (keyword? id))
      (child-spec-error index nil :CHILD_ID_INVALID
                        "a supervisor child needs a keyword :id" {:spec spec})

      (not (fn? (:start! spec)))
      (child-spec-error index id :CHILD_START_REQUIRED
                        "a supervisor child needs a :start! function" nil)

      (not (fn? (:stop! spec)))
      (child-spec-error index id :CHILD_STOP_REQUIRED
                        "a supervisor child needs a :stop! function" nil)

      (and periodic? (not (fn? (:tick! spec))))
      (child-spec-error index id :CHILD_TICK_INVALID
                        ":tick! must be a function" {:tick! (:tick! spec)})

      (and periodic? (not (positive-integer? (:interval-ms spec))))
      (child-spec-error index id :CHILD_INTERVAL_REQUIRED
                        "a periodic child needs a positive :interval-ms"
                        {:interval-ms (:interval-ms spec)})

      (and (not periodic?) (contains? spec :interval-ms))
      (child-spec-error index id :CHILD_INTERVAL_WITHOUT_TICK
                        ":interval-ms needs a :tick! function" nil)

      (and (not periodic?) (contains? spec :alive?)
           (not (fn? (:alive? spec))))
      (child-spec-error index id :CHILD_ALIVE_INVALID
                        ":alive? must be a function" {:alive? (:alive? spec)})

      (and periodic? (contains? spec :alive?))
      (child-spec-error index id :CHILD_ALIVE_NOT_PERIODIC
                        ":alive? is only meaningful for lifecycle children"
                        nil)

      (and (contains? spec :poll-ms)
           (not (positive-integer? (:poll-ms spec))))
      (child-spec-error index id :CHILD_POLL_INVALID
                        ":poll-ms must be a positive integer"
                        {:poll-ms (:poll-ms spec)})

      (and (contains? spec :poll-ms) periodic?)
      (child-spec-error index id :CHILD_POLL_NOT_PERIODIC
                        ":poll-ms is only meaningful for lifecycle children"
                        nil)

      (not (contains? restart-policies (get spec :restart :permanent)))
      (child-spec-error index id :CHILD_RESTART_INVALID
                        ":restart must be :permanent, :transient, or :temporary"
                        {:restart (:restart spec)})

      (nil? (normalize-backoff (:backoff-ms spec)))
      (child-spec-error index id :CHILD_BACKOFF_INVALID
                        ":backoff-ms must be [min-ms max-ms], 0 < min <= max"
                        {:backoff-ms (:backoff-ms spec)})

      (and (contains? spec :critical?) (not (boolean? (:critical? spec))))
      (child-spec-error index id :CHILD_CRITICAL_INVALID
                        ":critical? must be a boolean"
                        {:critical? (:critical? spec)})

      :else
      {:ok? true
       :child {:id id
               :kind (if periodic? :periodic :lifecycle)
               :start! (:start! spec)
               :stop! (:stop! spec)
               :tick! (:tick! spec)
               :interval-ms (when periodic? (long (:interval-ms spec)))
               :restart (get spec :restart :permanent)
               :backoff-ms (normalize-backoff (:backoff-ms spec))
               :critical? (boolean (:critical? spec))
               :alive? (:alive? spec)
               :poll-ms (long (or (:poll-ms spec) default-poll-ms))}})))

(defn- normalize-children
  [children]
  (cond
    (not (vector? children))
    (failure :CHILDREN_INVALID
             "supervisor :children must be a vector"
             {:children children})

    (empty? children)
    (failure :CHILDREN_EMPTY "supervisor needs at least one child" nil)

    :else
    (loop [index 0
           seen #{}
           normalized []]
      (if (= index (count children))
        {:ok? true :children normalized}
        (let [result (normalize-child index (nth children index))]
          (if-not (:ok? result)
            result
            (let [id (get-in result [:child :id])]
              (if (contains? seen id)
                (child-spec-error index id :CHILD_ID_DUPLICATE
                                  "supervisor child :id values must be unique"
                                  nil)
                (recur (inc index)
                       (conj seen id)
                       (conj normalized (:child result)))))))))))

;; ---------------------------------------------------------------------------
;; Runtime records

(defn- new-child
  [spec index]
  {:id (:id spec)
   :spec spec
   :index index
   :state (atom {:state :stopped
                 :runs 0
                 :failures 0
                 :consecutive-failures 0
                 :backoff-ms 0
                 :last-run-at nil
                 :last-error nil})
   :handle (atom nil)
   :stop-requested? (atom false)
   ;; The stop loop marks a child attempted before it takes the child lock.
   ;; A worker releases the handle itself only when its stop attempt timed
   ;; out; otherwise the stop loop owns the callback order.
   :stop-attempted? (atom false)
   :stop-processed? (atom false)
   :wake-counter (atom 0)
   :wake (Object.)
   :lock (ReentrantLock.)
   :thread (atom nil)
   :start-seq (atom nil)})

(defn- now-ms
  [sup]
  (try
    (long ((:clock sup)))
    (catch Throwable _
      (System/currentTimeMillis))))

(defn- halted?
  [sup child]
  (or @(:stop-requested? child)
      (not= :running @(:lifecycle sup))))

(defn- child-context
  [sup child]
  {:id (:id child)
   :clock (:clock sup)
   :stop-requested? (fn [] (boolean @(:stop-requested? child)))})

;; ---------------------------------------------------------------------------
;; Waiting and waking

(defn- wake!
  "Let a waiting worker re-read the child state.  Safe without a waiter."
  [child]
  (swap! (:wake-counter child) inc)
  (let [^Object wake (:wake child)]
    (locking wake
      (.notifyAll wake))))

(defn- wait-ms!
  "Wait up to MS unless the child must stop or a wake-up arrives.

  Returns `:stopped`, `:woken`, or `:elapsed`.  A wake-up means the worker
  must recompute its delay; the caller wakes it after `trigger!`.  Long
  waits use bounded slices, so a huge interval cannot overflow a deadline."
  [child ms]
  (let [^Object wake (:wake child)
        total (long (max 0 ms))]
    (locking wake
      (let [counter @(:wake-counter child)]
        (loop [remaining total
               since (System/nanoTime)]
          (cond
            @(:stop-requested? child) :stopped

            (not= counter @(:wake-counter child)) :woken

            (<= remaining 0) :elapsed

            :else
            (do
              (.wait wake (long (min remaining 60000)))
              (let [now (System/nanoTime)
                    elapsed (long (quot (- now since) 1000000))]
                (recur (max 0 (- remaining elapsed)) now)))))))))

(defn- request-stop!
  [child]
  (reset! (:stop-requested? child) true)
  (wake! child))

;; ---------------------------------------------------------------------------
;; Child lock

(defn- call-locked
  [child f]
  (let [^ReentrantLock lock (:lock child)]
    (.lock lock)
    (try
      (f)
      (finally
        (.unlock lock)))))

(defn- call-locked-with-timeout
  [child timeout-ms f]
  (let [^ReentrantLock lock (:lock child)]
    (if (.tryLock lock (long (max 0 timeout-ms)) TimeUnit/MILLISECONDS)
      (try
        (f)
        (finally
          (.unlock lock)))
      ::lock-timeout)))

;; ---------------------------------------------------------------------------
;; State transitions

(defn- record-success!
  [child at count-run?]
  (swap! (:state child)
         (fn [state]
           (let [state (assoc state
                              :state :running
                              :consecutive-failures 0
                              :backoff-ms 0)]
             (if count-run?
               (-> state
                   (update :runs inc)
                   (assoc :last-run-at at))
               state)))))

(defn- record-failure!
  [child phase error next-state at]
  (let [retry? (= :backing-off next-state)
        bounds (get-in child [:spec :backoff-ms])]
    (swap! (:state child)
           (fn [state]
             (let [consecutive (inc (long (:consecutive-failures state)))]
               (-> state
                   (assoc :state next-state
                          :failures (inc (long (:failures state)))
                          :consecutive-failures consecutive
                          :backoff-ms (if retry?
                                        (backoff-delay bounds consecutive)
                                        0)
                          :last-error (assoc error
                                             :phase phase
                                             :at at))))))))

(defn- mark-stopped!
  [child]
  (swap! (:state child)
         (fn [state]
           (if (= :failed (:state state))
             state
             (assoc state :state :stopped :backoff-ms 0)))))

;; ---------------------------------------------------------------------------
;; Child actions.  The caller holds the child lock.

(defn- start-rejection
  "A start callback may follow the repository idiom and return
  `{:ok? false :error ...}` instead of throwing.  Such a value is a
  rejected start, never a handle."
  [value]
  (when (and (map? value) (false? (:ok? value)))
    (let [error (:error value)]
      (merge {:code :CHILD_START_REJECTED
              :message "the child rejected its start"}
             (when (map? error) error)))))

(defn- start-child-locked!
  [sup child force?]
  (let [at (now-ms sup)]
    (cond
      @(:stop-requested? child)
      {:outcome :stopped :at at}

      (and (not force?) (some? @(:handle child)))
      {:outcome :already-running :at at}

      :else
      (let [spec (:spec child)
            context (child-context sup child)]
        (try
          (let [handle ((:start! spec) context)]
            (if-let [rejection (start-rejection handle)]
              (let [retry? (not= :temporary (:restart spec))
                    state (record-failure! child :start rejection
                                           (if retry? :backing-off :failed)
                                           at)]
                {:outcome :failed
                 :at at
                 :error (:last-error state)
                 :retry? retry?
                 :state (:state state)
                 :backoff-ms (:backoff-ms state)})
              (do
                (when (nil? @(:start-seq child))
                  (reset! (:start-seq child)
                          (swap! (:start-counter sup) inc)))
                (reset! (:handle child) {:value handle})
                (record-success! child at (= :lifecycle (:kind spec)))
                {:outcome :ok
                 :at at
                 :handle handle})))
          (catch Throwable t
            (let [retry? (not= :temporary (:restart spec))
                  state (record-failure! child :start
                                         (error-of :CHILD_START_FAILED t)
                                         (if retry? :backing-off :failed)
                                         at)]
              {:outcome :failed
               :at at
               :error (:last-error state)
               :retry? retry?
               :state (:state state)
               :backoff-ms (:backoff-ms state)})))))))

(defn- tick-child-locked!
  [sup child]
  (let [entry @(:handle child)
        at (now-ms sup)]
    (cond
      @(:stop-requested? child)
      {:outcome :stopped :at at}

      (nil? entry)
      {:outcome :not-running :at at}

      :else
      (try
        (let [result ((get-in child [:spec :tick!])
                      (assoc (child-context sup child)
                             :handle (:value entry)))]
          (if (and (map? result) (false? (:ok? result)))
            (let [tick-error (if (map? (:error result))
                               (:error result)
                               {:code :CHILD_TICK_FAILED
                                :message "child tick returned :ok? false"
                                :at at})
                  state (record-failure! child :tick tick-error
                                         :backing-off at)]
              {:outcome :failed
               :at at
               :error (:last-error state)
               :backoff-ms (:backoff-ms state)
               :consecutive-failures (:consecutive-failures state)})
            (do
              (record-success! child at true)
              {:outcome :ok :at at :result result})))
        (catch Throwable t
          (let [state (record-failure! child :tick
                                       (error-of :CHILD_TICK_FAILED t)
                                       :backing-off
                                       at)]
            {:outcome :failed
             :at at
             :error (:last-error state)
             :backoff-ms (:backoff-ms state)
             :consecutive-failures (:consecutive-failures state)}))))))

(defn- release-handle-locked!
  "Detach ENTRY from the child and call `:stop!` once.

  A returned map with `:ok? false` has not confirmed termination; its
  `:error` is kept and the stop result is a failure.  Any other return
  (including a missing `:ok?`) confirms the stop.  The caller holds the
  child lock, and a stale entry is not stopped twice."
  [child entry at]
  (if (and (some? entry) (identical? entry @(:handle child)))
    (do
      (reset! (:handle child) nil)
      (try
        (let [result ((get-in child [:spec :stop!]) (:value entry))]
          (if (and (map? result) (false? (:ok? result)))
            (let [error (or (when (map? (:error result)) (:error result))
                            {:code :CHILD_STOP_FAILED
                             :message "the child stop reported :ok? false"
                             :at at})]
              (swap! (:state child)
                     assoc
                     :last-error
                     (assoc error :phase :stop :at at))
              {:ok? false :error error :result result})
            {:ok? true :result result}))
        (catch Throwable t
          (let [error (error-of :CHILD_STOP_FAILED t)]
            (swap! (:state child)
                   assoc
                   :last-error
                   (assoc error :phase :stop :at at))
            {:ok? false :error error}))))
    {:ok? true :released? false}))

;; ---------------------------------------------------------------------------
;; Lifecycle exit detection

(defn- stale-entry?
  [child entry]
  (or @(:stop-requested? child)
      (not (identical? entry @(:handle child)))))

(defn- await-exit
  "Block until ENTRY ends or becomes stale.

  Returns `{:status :normal|:abnormal :error ...}` or `::stale`.  A `Future`
  gives a normal or abnormal reason.  A `Thread` that ends counts as an
  abnormal exit.  An `:alive?` probe can only observe death, so death is
  abnormal.  Any other handle is not observable; the child stays running
  until `stop!` or `trigger!` changes the entry."
  [child entry]
  (let [spec (:spec child)
        value (:value entry)
        alive? (:alive? spec)
        poll-ms (long (:poll-ms spec))]
    (cond
      (fn? alive?)
      (loop []
        (if (stale-entry? child entry)
          ::stale
          (let [alive-now? (try
                             (boolean (alive? value))
                             (catch Throwable _
                               false))]
            (if alive-now?
              (do (wait-ms! child poll-ms)
                  (recur))
              {:status :abnormal
               :error {:code :CHILD_LIVENESS_FAILED
                       :message "the child handle reported that it is not alive"}}))))

      (instance? Future value)
      (loop []
        (if (stale-entry? child entry)
          ::stale
          (let [result
                (try
                  (.get ^Future value poll-ms TimeUnit/MILLISECONDS)
                  {:status :normal}
                  (catch TimeoutException _
                    ::timeout)
                  (catch CancellationException _
                    {:status :normal})
                  (catch ExecutionException e
                    (let [cause (or (.getCause e) e)]
                      {:status :abnormal
                       :error (error-of :CHILD_EXITED_ABNORMALLY cause)})))]
            (if (= ::timeout result)
              (recur)
              result))))

      (instance? Thread value)
      (loop []
        (if (stale-entry? child entry)
          ::stale
          (do
            (.join ^Thread value poll-ms)
            (if (.isAlive ^Thread value)
              (recur)
              {:status :abnormal
               :error {:code :CHILD_THREAD_EXITED
                       :message "the child thread ended"}}))))

      :else
      (loop []
        (if (stale-entry? child entry)
          ::stale
          (do (wait-ms! child poll-ms)
              (recur)))))))

(defn- handle-exit-locked!
  [sup child entry exit]
  (let [at (now-ms sup)]
    (if-not (and (identical? entry @(:handle child))
                 (not @(:stop-requested? child)))
      {:outcome :stale :at at}
      (let [normal? (= :normal (:status exit))
            restart? (case (get-in child [:spec :restart])
                       :permanent true
                       :transient (not normal?)
                       :temporary false)
            stop-result (release-handle-locked! child entry at)
            stop-error (:error stop-result)
            exit-error (or (:error exit)
                           {:code :CHILD_EXITED
                            :message "the child handle ended"})]
        (cond
          restart?
          (let [state (record-failure! child :exit exit-error
                                       :backing-off at)]
            {:outcome :restarting
             :at at
             :error (:last-error state)
             :backoff-ms (:backoff-ms state)
             :stop-error stop-error})

          normal?
          (do
            (mark-stopped! child)
            {:outcome :stopped :at at :stop-error stop-error})

          :else
          (let [state (record-failure! child :exit exit-error :failed at)]
            {:outcome :failed
             :at at
             :error (:last-error state)
             :stop-error stop-error}))))))

;; ---------------------------------------------------------------------------
;; Error reporting

(defn- notify-error!
  "Call `:on-error` with a small event map.  A throwing callback is ignored."
  [sup child phase error at]
  (when-let [callback (:on-error sup)]
    (let [state @(:state child)]
      (try
        (callback {:child-id (:id child)
                   :phase phase
                   :error error
                   :at at
                   :consecutive-failures (:consecutive-failures state)
                   :backoff-ms (:backoff-ms state)})
        (catch Throwable _
          nil)))))

;; ---------------------------------------------------------------------------
;; Worker loops

(defn- periodic-loop
  [sup child]
  (loop []
    (when-not (halted? sup child)
      (let [{:keys [state backoff-ms]} @(:state child)
            entry @(:handle child)]
        (cond
          (and (nil? entry) (contains? #{:failed :stopped} state))
          (case (wait-ms! child default-idle-poll-ms)
            :stopped nil
            :woken (recur)
            :elapsed (recur))

          (nil? entry)
          (let [delay (if (= :backing-off state) backoff-ms 0)]
            (case (wait-ms! child delay)
              :stopped nil
              :woken (recur)
              :elapsed
              (let [{:keys [outcome] :as result}
                    (call-locked child #(start-child-locked!
                                         sup child false))]
                (when (= :failed outcome)
                  (notify-error! sup child :start (:error result)
                                  (:at result)))
                (recur))))

          (= :running state)
          (case (wait-ms! child (:interval-ms (:spec child)))
            :stopped nil
            :woken (recur)
            :elapsed
            (let [{:keys [outcome] :as result}
                  (call-locked child #(tick-child-locked! sup child))]
              (when (= :failed outcome)
                (notify-error! sup child :tick (:error result)
                                (:at result)))
              (recur)))

          (= :backing-off state)
          (case (wait-ms! child backoff-ms)
            :stopped nil
            :woken (recur)
            :elapsed
            (let [{:keys [outcome] :as result}
                  (call-locked child #(tick-child-locked! sup child))]
              (when (= :failed outcome)
                (notify-error! sup child :tick (:error result)
                                (:at result)))
              (recur)))

          :else
          (case (wait-ms! child default-idle-poll-ms)
            :stopped nil
            :woken (recur)
            :elapsed (recur)))))))

(defn- lifecycle-loop
  [sup child]
  (loop []
    (when-not (halted? sup child)
      (let [{:keys [state backoff-ms]} @(:state child)
            entry @(:handle child)]
        (cond
          (and (nil? entry) (contains? #{:failed :stopped} state))
          (case (wait-ms! child default-idle-poll-ms)
            :stopped nil
            :woken (recur)
            :elapsed (recur))

          (nil? entry)
          (let [delay (if (= :backing-off state) backoff-ms 0)]
            (case (wait-ms! child delay)
              :stopped nil
              :woken (recur)
              :elapsed
              (let [{:keys [outcome] :as result}
                    (call-locked child #(start-child-locked!
                                         sup child false))]
                (when (= :failed outcome)
                  (notify-error! sup child :start (:error result)
                                  (:at result)))
                (recur))))

          (= :running state)
          (let [exit (await-exit child entry)]
            (if (= ::stale exit)
              (recur)
              (let [{:keys [outcome error stop-error] :as result}
                    (call-locked child #(handle-exit-locked!
                                         sup child entry exit))]
                (when (and (= :failed outcome) error)
                  (notify-error! sup child :exit error (:at result)))
                (when stop-error
                  (notify-error! sup child :stop stop-error (:at result)))
                (recur))))

          :else
          (case (wait-ms! child default-idle-poll-ms)
            :stopped nil
            :woken (recur)
            :elapsed (recur)))))))

(defn- worker-run
  [sup child]
  (try
    (case (:kind (:spec child))
      :periodic (periodic-loop sup child)
      :lifecycle (lifecycle-loop sup child))
    (catch Throwable t
      (let [at (now-ms sup)
            state (record-failure! child :worker
                                   (error-of :CHILD_WORKER_FAILED t)
                                   :failed at)]
        (notify-error! sup child :worker (:last-error state) at)))
    (finally
      (when (and @(:stop-requested? child)
                 @(:stop-attempted? child)
                 (not @(:stop-processed? child)))
        (let [^ReentrantLock lock (:lock child)
              stop-error
              (when (.tryLock lock)
                (try
                  (let [entry @(:handle child)
                        at (now-ms sup)
                        result (release-handle-locked! child entry at)]
                    (mark-stopped! child)
                    (reset! (:stop-processed? child) true)
                    (when-not (:ok? result) (:error result)))
                  (finally
                    (.unlock lock))))]
          (when stop-error
            (notify-error! sup child :stop stop-error (now-ms sup))))))))

(defn- launch-worker!
  [sup child]
  (let [^Thread thread
        (Thread.
         ^Runnable (fn [] (worker-run sup child))
         (str "hc-supervisor-" (name (:id child)) "-"
              (.incrementAndGet ^AtomicLong thread-counter)))]
    (.setDaemon thread true)
    (reset! (:thread child) thread)
    (.start thread)))

(defn- ensure-worker!
  "A trigger may revive a child whose worker already exited.  Start a fresh
  worker when the old thread is gone and the supervisor still runs."
  [sup child]
  (when (= :running @(:lifecycle sup))
    (let [^Thread thread @(:thread child)]
      (when (or (nil? thread) (not (.isAlive thread)))
        (launch-worker! sup child)))))

;; ---------------------------------------------------------------------------
;; Trigger

(defn- trigger-locked!
  [sup child]
  (if (halted? sup child)
    {:outcome :supervisor-stopped :at (now-ms sup)}
    (let [spec (:spec child)
          outcome
          (case (:kind spec)
            :periodic
            (if (nil? @(:handle child))
              (let [started (start-child-locked! sup child true)]
                (if (= :ok (:outcome started))
                  (assoc (tick-child-locked! sup child)
                         :action :start-and-tick)
                  (assoc started :action :start)))
              (assoc (tick-child-locked! sup child) :action :tick))

            :lifecycle
            (if (nil? @(:handle child))
              (assoc (start-child-locked! sup child true) :action :start)
              (let [entry @(:handle child)
                    at (now-ms sup)
                    stop-result (release-handle-locked! child entry at)]
                (assoc (start-child-locked! sup child true)
                       :action :restart
                       :stop-result stop-result))))]
      (ensure-worker! sup child)
      outcome)))

(defn- receipt-for
  [child-id {:keys [outcome action at result error stop-result]}]
  (cond-> (case outcome
            :ok
            {:ok? true
             :child-id child-id
             :action action
             :at at
             :result result}

            :not-running
            {:ok? false
             :child-id child-id
             :action action
             :at at
             :error {:code :CHILD_NOT_RUNNING
                     :message "the child is not running"}}

            :supervisor-stopped
            {:ok? false
             :child-id child-id
             :action action
             :at at
             :error {:code :SUPERVISOR_STOPPED
                     :message "the supervisor is not running"}}

            :stopped
            {:ok? false
             :child-id child-id
             :action action
             :at at
             :error {:code :SUPERVISOR_STOPPED
                     :message "the supervisor is not running"}}

            {:ok? false
             :child-id child-id
             :action action
             :at at
             :error (or error
                        {:code :CHILD_TRIGGER_FAILED
                         :message "the child failed"})})
    (and (some? stop-result) (not (:ok? stop-result)))
    (assoc :stop-error (:error stop-result))))

(defn trigger!
  "Run one unit for CHILD-ID synchronously and return a receipt.

  A periodic child runs one tick.  A lifecycle child restarts: it stops the
  current handle and starts a new one, or it starts a stopped/failed child.
  The call also clears a pending backoff wait.  A child exception becomes a
  receipt with `:ok? false`; it never escapes."
  [sup child-id]
  (cond
    (not (map? sup))
    (failure :SUPERVISOR_INVALID "trigger! needs a supervisor" nil)

    (not= :running @(:lifecycle sup))
    (failure :SUPERVISOR_STOPPED "the supervisor is not running"
             {:child-id child-id})

    :else
    (if-let [child (get (:by-id sup) child-id)]
      (let [outcome (call-locked child #(trigger-locked! sup child))]
        (wake! child)
        (when (= :failed (:outcome outcome))
          (notify-error! sup child
                          (if (= :start (:action outcome)) :start :tick)
                          (:error outcome)
                          (:at outcome)))
        (when (and (some? (:stop-result outcome))
                   (not (:ok? (:stop-result outcome))))
          (notify-error! sup child :stop
                          (get-in outcome [:stop-result :error])
                          (:at outcome)))
        (receipt-for child-id outcome))
      (failure :CHILD_NOT_FOUND "no supervisor child has this id"
               {:child-id child-id}))))

;; ---------------------------------------------------------------------------
;; Stop

(defn- stop-order
  [children]
  (let [started (->> children
                     (filter #(some? @(:start-seq %)))
                     (sort-by #(long @(:start-seq %)) >))
        never-started (remove #(some? @(:start-seq %)) children)]
    (concat started never-started)))

(defn- remaining-ms
  [start-nanos timeout-ms]
  (let [elapsed (long (quot (- (System/nanoTime) start-nanos) 1000000))]
    (max 0 (- (long timeout-ms) elapsed))))

(defn- join-worker!
  [child timeout-ms]
  (if-let [^Thread thread @(:thread child)]
    (let [timeout-ms (long (max 0 timeout-ms))]
      ;; A zero remaining slice must not become Thread.join(0), which waits
      ;; forever.  It is a non-blocking liveness check instead.
      (when (pos? timeout-ms)
        (.join thread timeout-ms))
      (not (.isAlive thread)))
    true))

(defn- stop-child!
  [sup child timeout-ms]
  (reset! (:stop-attempted? child) true)
  (let [outcome (call-locked-with-timeout
                 child timeout-ms
                 (fn []
                   (let [entry @(:handle child)
                         at (now-ms sup)]
                     {:stop-result (release-handle-locked! child entry at)
                      :at at})))]
    (if (= ::lock-timeout outcome)
      (let [at (now-ms sup)]
        {:timed-out? true
         :ok? false
         :at at
         :error {:code :CHILD_STOP_LOCK_TIMEOUT
                 :message
                 "the child stop could not acquire its lock within the remaining timeout"
                 :at at}})
      (do
        (mark-stopped! child)
        (reset! (:stop-processed? child) true)
        (let [stop-result (:stop-result outcome)]
          {:timed-out? false
           :ok? (boolean (:ok? stop-result))
           :stop-result stop-result
           :error (:error stop-result)
           :at (:at outcome)})))))

(defn- do-stop!
  [sup timeout-ms]
  (let [children (:children sup)
        start-nanos (System/nanoTime)]
    (doseq [child children]
      (request-stop! child))
    (loop [pending (stop-order children)
           stopped []
           timed-out []
           results []]
      (if-let [child (first pending)]
        (let [lock-ms (remaining-ms start-nanos timeout-ms)
              stop (stop-child! sup child lock-ms)
              join-ms (remaining-ms start-nanos timeout-ms)
              joined? (join-worker! child join-ms)
              failure? (not (:ok? stop))]
          (when (and failure? (not (:timed-out? stop)))
            (notify-error! sup child :stop (:error stop) (:at stop)))
          (recur (next pending)
                 (if joined? (conj stopped (:id child)) stopped)
                 (if joined? timed-out (conj timed-out (:id child)))
                 (conj results {:child (:id child)
                                :ok? (boolean (:ok? stop))
                                :error (:error stop)})))
        {:ok? (every? :ok? results)
         :stopped stopped
         :timed-out timed-out
         :failures (into []
                         (keep (fn [{:keys [child ok? error]}]
                                 (when-not ok?
                                   {:child child
                                    :error (or error
                                               {:code :CHILD_STOP_FAILED
                                                :message
                                                "the child did not confirm its stop"})})))
                         results)}))))

(defn- stop-internal!
  [sup timeout-ms]
  (reset! (:lifecycle sup) :stopping)
  (let [result (do-stop! sup timeout-ms)]
    (reset! (:lifecycle sup) :stopped)
    (reset! (:stop-result sup) result)
    result))

(defn stop!
  "Stop every child and return `{:ok? :stopped :timed-out :failures}`.

  Children stop in reverse start order.  An in-flight tick finishes before
  its `:stop!` runs.  The first call caches its result; later calls return
  the same map and do not repeat any callback.  `:timeout-ms` bounds the
  whole stop, not one child.  A worker that still runs is reported in
  `:timed-out`; it is never interrupted, and it exits after its current
  action finishes.

  `:ok?` is true only when every child stop confirmed termination.  A
  child `:stop!` that returns `{:ok? false ...}`, throws, or cannot take
  its lock within the remaining timeout is a failure in `:failures`; a
  successful lock/join is not sufficient evidence.  `:stopped` and
  `:timed-out` remain the callback/join evidence vectors."
  ([sup]
   (stop! sup {}))
  ([sup options]
   (let [timeout-ms (get options :timeout-ms default-stop-timeout-ms)]
     (cond
       (not (and (integer? timeout-ms)
                 (<= 0 timeout-ms Long/MAX_VALUE)))
       (merge {:ok? false :stopped [] :timed-out [] :failures []}
              {:error {:code :STOP_TIMEOUT_INVALID
                       :message "stop :timeout-ms must be a non-negative integer"
                       :details {:timeout-ms timeout-ms}}})

       (not (map? sup))
       (merge {:ok? false :stopped [] :timed-out [] :failures []}
              (failure :SUPERVISOR_INVALID
                       "stop! needs a supervisor" nil))

       :else
       (locking (:stop-lock sup)
         (if (= :stopped @(:lifecycle sup))
           @(:stop-result sup)
           (try
             (stop-internal! sup (long timeout-ms))
             (catch Throwable t
               (let [error {:code :SUPERVISOR_STOP_FAILED
                            :message (or (.getMessage t) (str t))}
                     result {:ok? false
                             :stopped []
                             :timed-out (mapv :id (:children sup))
                             :failures (mapv (fn [child]
                                               {:child (:id child)
                                                :error error})
                                             (:children sup))
                             :error error}]
                 (reset! (:stop-result sup) result)
                 (reset! (:lifecycle sup) :stopped)
                 result)))))))))

;; ---------------------------------------------------------------------------
;; Status

(defn status
  "Return supervisor facts without running any child action.

  `:running?` describes the supervisor itself: it still accepts supervision.
  Each child state is one of `:running`, `:backing-off`, `:stopped`, or
  `:failed`.  A child that threw is never reported as `:running`.  `:runs`
  counts successful ticks for periodic children and successful starts for
  lifecycle children.  `:last-run-at` is the last success; `:last-error` is
  historical and survives a later recovery."
  [sup]
  (if-not (map? sup)
    {:running? false :stopped? true :children {}}
    (let [lifecycle @(:lifecycle sup)]
      {:running? (= :running lifecycle)
       :stopped? (= :stopped lifecycle)
       :children (into {}
                       (map (fn [child]
                              (let [state @(:state child)]
                                [(:id child)
                                 {:id (:id child)
                                  :state (:state state)
                                  :runs (:runs state)
                                  :failures (:failures state)
                                  :consecutive-failures
                                  (:consecutive-failures state)
                                  :backoff-ms (:backoff-ms state)
                                  :last-run-at (:last-run-at state)
                                  :last-error (:last-error state)}])))
                       (:children sup))})))

;; ---------------------------------------------------------------------------
;; Start

(defn start!
  "Start one process-level supervisor.

  Options:

  - `:children`   vector of child specs (see the namespace docstring);
  - `:clock`      epoch-ms function (default `System/currentTimeMillis`);
  - `:on-error`   optional `(fn [event])`; a throw in it is ignored.

  A child spec is:

      {:id keyword
       :start! (fn [ctx] handle)
       :stop!  (fn [handle])
       :tick!  (fn [ctx])        ; optional, makes the child periodic
       :interval-ms positive-int ; required with :tick!
       :restart :permanent|:transient|:temporary   ; default :permanent
       :backoff-ms [min-ms max-ms]                 ; default [500 30000]
       :critical? boolean                          ; default false
       :alive? (fn [handle])     ; optional lifecycle liveness probe
       :poll-ms positive-int}    ; liveness poll slice, default 100

  `:start!` receives `{:id :clock :stop-requested?}` and `:tick!` receives
  the same map plus `:handle`, the value returned by `:start!`.
  A `:start!` return of `{:ok? false :error ...}` is a rejected start, not
  a handle.  Throwing is also a rejected start.

  A `:critical? true` child that fails its first start aborts the whole
  start and rolls back the children that already started.  All other start
  failures become a degraded child that the worker retries with backoff.
  `:critical?` applies to startup only: a later failure of any child never
  stops a sibling.

  Returns `{:ok? true :supervisor sup :started [ids] :degraded [ids]}` or
  `{:ok? false :error {...}}`."
  [options]
  (let [normalized (normalize-children (:children options))]
    (if-not (:ok? normalized)
      normalized
      (let [clock (:clock options)
            on-error (:on-error options)]
        (cond
          (and (some? clock) (not (fn? clock)))
          (failure :CLOCK_INVALID "supervisor :clock must be a function"
                   {:clock clock})

          (and (some? on-error) (not (fn? on-error)))
          (failure :ON_ERROR_INVALID
                   "supervisor :on-error must be a function" nil)

          :else
          (let [child-runtimes (mapv (fn [index spec] (new-child spec index))
                                     (range)
                                     (:children normalized))
                sup {:children child-runtimes
                     :by-id (into {} (map (juxt :id identity)) child-runtimes)
                     :clock (or clock #(System/currentTimeMillis))
                     :on-error on-error
                     :lifecycle (atom :running)
                     :stop-lock (Object.)
                     :stop-result (atom nil)
                     :start-counter (atom 0)}
                started (atom [])
                degraded (atom [])
                critical-failure (atom nil)]
            (doseq [child child-runtimes]
              (when-not @critical-failure
                (let [{:keys [outcome] :as result}
                      (call-locked child #(start-child-locked!
                                           sup child false))]
                  (if (= :ok outcome)
                    (swap! started conj (:id child))
                    (swap! degraded conj (:id child)))
                  (when-not (= :ok outcome)
                    (notify-error! sup child :start (:error result)
                                    (:at result)))
                  (when (and (not= :ok outcome)
                             (:critical? (:spec child)))
                    (reset! critical-failure
                            {:child-id (:id child)
                             :error (:error result)})))))
            (if-let [critical @critical-failure]
              (let [rolled-back (stop-internal! sup default-stop-timeout-ms)]
                {:ok? false
                 :error {:code :CRITICAL_CHILD_START_FAILED
                         :message "a critical supervisor child could not start"
                         :details (assoc critical
                                         :stopped (:stopped rolled-back)
                                         :timed-out (:timed-out rolled-back))}})
              (do
                (try
                  (doseq [child child-runtimes]
                    (launch-worker! sup child))
                  {:ok? true
                   :supervisor sup
                   :started @started
                   :degraded @degraded}
                  (catch Throwable t
                    (let [rolled-back
                          (stop-internal! sup default-stop-timeout-ms)]
                      {:ok? false
                       :error
                       {:code :SUPERVISOR_WORKER_START_FAILED
                        :message "a supervisor worker could not start"
                        :details
                        {:cause (or (.getMessage t) (str t))
                         :stopped (:stopped rolled-back)
                         :timed-out (:timed-out rolled-back)}}})))))))))))
