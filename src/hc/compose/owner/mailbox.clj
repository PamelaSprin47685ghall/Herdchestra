(ns hc.compose.owner.mailbox
  "Serial single-writer mailbox for one Owner.

  Any thread may call `submit!`.  Only the mailbox thread calls the handler.
  The handler must not block on model, file, Shell, or network I/O; it
  performs one short state transition and returns.  A handler exception is
  recorded and reported, and never kills the loop.

  `stop!` never interrupts the mailbox thread.  Interrupting a thread in the
  middle of a state commit would expose a half-written transaction.  Stop
  closes admission first, then lets the accepted queue drain."
  (:import [java.util.concurrent LinkedBlockingQueue TimeUnit]
           [java.util.concurrent.atomic AtomicBoolean AtomicLong]))

(def ^:private message-id-key
  :hc.compose.owner.mailbox/message-id)

(def ^:private sentinel
  "A wake token.  The loop never treats it as a message."
  (Object.))

(def default-capacity
  "Upper bound on queued messages when the caller does not choose one.

  4096 is far above normal bursts, but a stalled handler must not grow the
  queue until the process runs out of memory.  Callers that can handle
  `:MAILBOX_FULL` may pass a smaller `:capacity`."
  4096)

(defn- now-ms
  []
  (System/currentTimeMillis))

(defn create
  "Create a mailbox.

  Options:

    :id        readable thread-name suffix
    :capacity  optional positive bound on queued messages (default
               `default-capacity`)
    :handler   `(fn [message] ...)`, called only on the mailbox thread
    :on-error  `(fn [{:message-id :error :thread}])`, called after a handler
               exception; it must not throw

  The default is bounded.  A queue that accepts provider callbacks without
  limit is a memory leak waiting for a stalled handler; `:MAILBOX_FULL` is
  the explicit backpressure signal.  Pass a smaller `:capacity` when the
  caller can handle it sooner."
  [{:keys [id capacity handler on-error]}]
  (when-not (ifn? handler)
    (throw (ex-info "mailbox needs a handler function"
                    {:code :INVALID_HANDLER})))
  (when (and (some? capacity)
             (not (and (integer? capacity) (pos? capacity))))
    (throw (ex-info "mailbox capacity must be a positive integer"
                    {:code :INVALID_CAPACITY :capacity capacity})))
  (let [capacity (or capacity default-capacity)]
    {:id (or id "owner")
     :queue (LinkedBlockingQueue. (int capacity))
     :capacity capacity
     :handler (atom handler)
     :on-error (atom on-error)
     :lock (Object.)
     :running? (AtomicBoolean. false)
     :closed? (AtomicBoolean. false)
     :processing? (AtomicBoolean. false)
     :thread (atom nil)
     :submitted (AtomicLong. 0)
     :rejected (AtomicLong. 0)
     :processed (AtomicLong. 0)
     :errors (AtomicLong. 0)
     :last-error (atom nil)}))

(defn set-handler!
  "Replace the handler.  This is safe only before `start!` or while the
  mailbox is idle; the host owns that decision."
  [mailbox handler]
  (when-not (ifn? handler)
    (throw (ex-info "mailbox needs a handler function"
                    {:code :INVALID_HANDLER})))
  (reset! (:handler mailbox) handler)
  mailbox)

(defn submit!
  "Enqueue one message from any thread.

  Returns `{:ok? true :message-id n}` or
  `{:ok? false :error {:code :MAILBOX_CLOSED|:MAILBOX_FULL|:INVALID_MESSAGE}}`.
  The message id is also attached under `:hc.compose.owner.mailbox/message-id`."
  [mailbox message]
  (cond
    (.get ^AtomicBoolean (:closed? mailbox))
    {:ok? false :error {:code :MAILBOX_CLOSED
                        :message "mailbox is closed to new messages"}}

    (not (map? message))
    {:ok? false :error {:code :INVALID_MESSAGE
                        :message "a mailbox message must be a map"
                        :details {:class (some-> message class .getName)}}}

    :else
    (let [id (.incrementAndGet ^AtomicLong (:submitted mailbox))
          message (assoc message message-id-key id)
          accepted? (.offer ^LinkedBlockingQueue (:queue mailbox) message)]
      (if accepted?
        (do
          (locking (:lock mailbox)
            (.notifyAll ^Object (:lock mailbox)))
          {:ok? true :message-id id})
        (do
          (.incrementAndGet ^AtomicLong (:rejected mailbox))
          {:ok? false
           :error {:code :MAILBOX_FULL
                   :message "mailbox queue is at capacity"
                   :details {:capacity (:capacity mailbox)}}})))))

(defn- invoke-handler
  [mailbox message]
  (.set ^AtomicBoolean (:processing? mailbox) true)
  (try
    (let [result ((deref (:handler mailbox)) message)]
      (.incrementAndGet ^AtomicLong (:processed mailbox))
      {:status :processed
       :message-id (get message message-id-key)
       :result result})
    (catch Throwable error
      (.incrementAndGet ^AtomicLong (:errors mailbox))
      (let [report {:message-id (get message message-id-key)
                    :error error
                    :thread (.getName (Thread/currentThread))
                    :at (now-ms)}]
        (reset! (:last-error mailbox) report)
        (when-let [on-error (deref (:on-error mailbox))]
          (try
            (on-error report)
            (catch Throwable _ nil)))
        (assoc report :status :error)))
    (finally
      (.set ^AtomicBoolean (:processing? mailbox) false)
      (locking (:lock mailbox)
        (.notifyAll ^Object (:lock mailbox))))))

(defn run-one!
  "Wait up to TIMEOUT-MS for one message and process it.

  Returns `{:status :processed|:error|:empty}`.  Only the mailbox thread may
  call this while the loop runs; tests may call it directly."
  [mailbox timeout-ms]
  (let [message (.poll ^LinkedBlockingQueue (:queue mailbox)
                       (long (max 0 timeout-ms))
                       TimeUnit/MILLISECONDS)]
    (cond
      (nil? message) {:status :empty}
      (identical? message sentinel) {:status :empty}
      :else (invoke-handler mailbox message))))

(defn- loop!
  [mailbox]
  (loop []
    (when (or (.get ^AtomicBoolean (:running? mailbox))
              (not (.isEmpty ^LinkedBlockingQueue (:queue mailbox))))
      (run-one! mailbox 100)
      (recur)))
  (.set ^AtomicBoolean (:running? mailbox) false)
  (locking (:lock mailbox)
    (.notifyAll ^Object (:lock mailbox))))

(defn start!
  "Start the single mailbox thread.  Returns `{:ok? true :thread t}` or
  `{:ok? false :error {:code :ALREADY_RUNNING}}`."
  [mailbox]
  (if-not (.compareAndSet ^AtomicBoolean (:running? mailbox) false true)
    {:ok? false :error {:code :ALREADY_RUNNING
                        :message "mailbox thread is already running"}}
    (let [thread (Thread.
                  ^Runnable (fn [] (loop! mailbox))
                  (str "hc-owner-" (:id mailbox)))]
      (.setDaemon thread true)
      (reset! (:thread mailbox) thread)
      (.start thread)
      {:ok? true :thread thread})))

(defn stop!
  "Close admission, drain accepted messages, and join the thread.

  The thread is never interrupted.  A handler that blocks longer than
  TIMEOUT-MS leaves the thread alive; `:stopped?` is then false and the
  caller must decide how to proceed."
  ([mailbox] (stop! mailbox {}))
  ([mailbox {:keys [timeout-ms] :or {timeout-ms 5000}}]
   (.set ^AtomicBoolean (:closed? mailbox) true)
   (.set ^AtomicBoolean (:running? mailbox) false)
   (.offer ^LinkedBlockingQueue (:queue mailbox) sentinel)
   (locking (:lock mailbox)
     (.notifyAll ^Object (:lock mailbox)))
   (let [thread (deref (:thread mailbox))]
     (when thread
       (.join ^Thread thread (long (max 0 timeout-ms))))
     {:stopped? (or (nil? thread) (not (.isAlive ^Thread thread)))
      :drained? (.isEmpty ^LinkedBlockingQueue (:queue mailbox))
      :queued (.size ^LinkedBlockingQueue (:queue mailbox))
      :processed (.get ^AtomicLong (:processed mailbox))
      :errors (.get ^AtomicLong (:errors mailbox))
      :rejected (.get ^AtomicLong (:rejected mailbox))})))

(defn close-admission!
  "Refuse new messages but keep processing accepted ones."
  [mailbox]
  (.set ^AtomicBoolean (:closed? mailbox) true)
  {:ok? true :closed? true})

(defn await-idle!
  "Wait until the queue is empty and no handler is running.

  The result must be stable for two short observations.  The mailbox thread
  can poll a message and have an empty queue for a few microseconds before
  `processing?` turns true; a single observation can report idle in that
  window and lose a wake.  Returns `{:idle? bool :queued n :waited-ms n}`.
  A timeout is not an error; the caller inspects `:idle?`."
  [mailbox timeout-ms]
  (let [started (now-ms)
        deadline (+ started (long (max 0 timeout-ms)))]
    (loop [stable 0]
      (let [idle? (and (.isEmpty ^LinkedBlockingQueue (:queue mailbox))
                       (not (.get ^AtomicBoolean (:processing? mailbox))))
            remaining (- deadline (now-ms))]
        (cond
          (and idle? (>= stable 2))
          {:idle? true
           :queued 0
           :waited-ms (- (now-ms) started)}

          (<= remaining 0) {:idle? false
                            :queued (.size ^LinkedBlockingQueue (:queue mailbox))
                            :waited-ms (- (now-ms) started)}

          idle?
          (do
            (Thread/sleep 5)
            (recur (inc stable)))

          :else
          (do
            (locking (:lock mailbox)
              (when (and (not (.isEmpty ^LinkedBlockingQueue (:queue mailbox)))
                         (pos? remaining))
                (.wait ^Object (:lock mailbox) (long (min remaining 50)))))
            (Thread/sleep 1)
            (recur 0)))))))

(defn owner-thread?
  "True when the current thread is this mailbox's thread."
  [mailbox]
  (identical? (Thread/currentThread) (deref (:thread mailbox))))

(defn assert-owner-thread!
  "Throw unless the current thread is the mailbox thread.  The Owner calls
  this before every commit."
  [mailbox]
  (when-not (owner-thread? mailbox)
    (throw (ex-info "state transition ran outside the Owner thread"
                    {:code :NOT_OWNER_THREAD
                     :thread (.getName (Thread/currentThread))})))
  true)

(defn stats
  [mailbox]
  {:id (:id mailbox)
   :queued (.size ^LinkedBlockingQueue (:queue mailbox))
   :capacity (:capacity mailbox)
   :submitted (.get ^AtomicLong (:submitted mailbox))
   :rejected (.get ^AtomicLong (:rejected mailbox))
   :processed (.get ^AtomicLong (:processed mailbox))
   :errors (.get ^AtomicLong (:errors mailbox))
   :processing? (.get ^AtomicBoolean (:processing? mailbox))
   :running? (.get ^AtomicBoolean (:running? mailbox))
   :closed? (.get ^AtomicBoolean (:closed? mailbox))
   :thread (some-> (deref (:thread mailbox)) .getName)
   :last-error (some-> (deref (:last-error mailbox))
                       (dissoc :error)
                       (assoc :error-class (some-> (deref (:last-error mailbox))
                                                   :error
                                                   class
                                                   .getName)))})
