(ns hc.core.workspace-gate.gate
  "The single global workspace write entrance W.

  All protected commits and all managed shell sessions in one workspace use
  the same `WriteGate`.  Admission is FIFO-like (a fair semaphore), never
  selected by task title or model capability, and never transferred by a
  timeout.  A waiter may leave only through an explicit stop request."
  (:require [hc.core.workspace-gate.error :as err])
  (:import (java.util.concurrent Semaphore TimeUnit)
           (java.util.concurrent.atomic AtomicLong AtomicReference)))

(set! *warn-on-reflection* true)

(defrecord WriteGate [^Semaphore semaphore
                      ^AtomicReference owner
                      ^AtomicLong grants
                      ^AtomicLong current-grant
                      ^AtomicReference admission])

(defn write-gate
  "Create an empty fair write gate."
  []
  (->WriteGate (Semaphore. 1 true)
               (AtomicReference. nil)
               (AtomicLong. 0)
               (AtomicLong. 0)
               (AtomicReference. nil)))

(defn held?
  [gate]
  (zero? (.availablePermits ^Semaphore (:semaphore gate))))

(defn owner
  [gate]
  (.get ^AtomicReference (:owner gate)))

(defn queued
  "Number of waiters currently queued.  Observability only; admission order
  is the semaphore's fair order."
  [gate]
  (.getQueueLength ^Semaphore (:semaphore gate)))

(defn grants
  "Monotonic count of successful admissions.  Used by tests and diagnostics."
  [gate]
  (.get ^AtomicLong (:grants gate)))

(defn current-grant
  "Monotonic id of the admission currently owning W, or the last admission
  after release.  Used to reject an old Prepared value after re-acquisition."
  [gate]
  (.get ^AtomicLong (:current-grant gate)))

(defn set-admission!
  "Install an admission predicate.  The predicate returns nil to admit, or a
  classified rejection map (`:status`, `:outcome`, ...) to refuse.  A refusal
  happens before the caller waits, so a recovery barrier can fail closed
  without hiding W behind an unrelated holder."
  [gate f]
  (when-not (or (nil? f) (ifn? f))
    (throw (IllegalArgumentException. "write admission must be a function or nil")))
  (.set ^AtomicReference (:admission gate) f)
  gate)

(defn clear-admission!
  "Remove the admission predicate.  Already queued callers are unaffected."
  [gate]
  (.set ^AtomicReference (:admission gate) nil)
  gate)

(defn admission
  "Return the current admission predicate, or nil."
  [gate]
  (.get ^AtomicReference (:admission gate)))

(defn- admission-failure
  [gate]
  (when-let [f (.get ^AtomicReference (:admission gate))]
    (try
      (let [result (f)]
        (when result
          (if (and (map? result) (contains? result :status))
            result
            {:status :RECOVERY_UNRESOLVED
             :code :recovery-unresolved
             :message "write admission is blocked"})))
      (catch Throwable t
        {:status :RECOVERY_UNRESOLVED
         :code :recovery-admission-failed
         :message (or (ex-message t) (str t))}))))

(defn- check-admission!
  [gate]
  (when-let [failure (admission-failure gate)]
    (throw (ex-info (or (:message failure) "write admission is blocked")
                    (merge err/not-written failure)))))

(defn- grant! [gate owner-id]
  (let [grant-id (.incrementAndGet ^AtomicLong (:grants gate))]
    (.set ^AtomicLong (:current-grant gate) grant-id)
    (.set ^AtomicReference (:owner gate) owner-id)
    {:gate gate :owner owner-id :grant-id grant-id}))

(defn acquire!
  "Block until this caller owns W.

  `opts` supports `:stop?` (a zero-argument predicate) and `:poll-ms`
  (default 50).  A stopped waiter leaves without acquiring and returns an
  ordinary `:WAITING_FOR_WRITE_GATE` rejection; the gate is not transferred.
  An admission predicate is checked before waiting; its classified rejection
  is thrown as an ordinary rejection.  With no `:stop?` this call blocks
  indefinitely by design."
  ([gate] (acquire! gate nil {}))
  ([gate owner-id] (acquire! gate owner-id {}))
  ([gate owner-id {:keys [stop? poll-ms] :or {poll-ms 50}}]
   (check-admission! gate)
   (if stop?
     (try
       (loop []
         (when (stop?)
           (err/fail! :WAITING_FOR_WRITE_GATE :cancelled-while-waiting
                      "Stopped while waiting for the workspace write gate"
                      {:owner owner-id :queued (queued gate)
                       :cancelled? true :queued? true}))
         (if (.tryAcquire ^Semaphore (:semaphore gate) (long poll-ms) TimeUnit/MILLISECONDS)
           (grant! gate owner-id)
           (recur)))
       (catch InterruptedException _
         (.interrupt (Thread/currentThread))
         (err/fail! :WAITING_FOR_WRITE_GATE :interrupted-while-waiting
                    "Interrupted while waiting for the workspace write gate"
                    {:owner owner-id :cancelled? false})))
     (try
       (.acquire ^Semaphore (:semaphore gate))
       (grant! gate owner-id)
       (catch InterruptedException _
         (.interrupt (Thread/currentThread))
         (err/fail! :WAITING_FOR_WRITE_GATE :interrupted-while-waiting
                    "Interrupted while waiting for the workspace write gate"
                    {:owner owner-id}))))))

(defn try-acquire!
  "Bounded admission for tests and diagnostic tools.  Production commits use
  `acquire!`; this helper never changes W's no-timeout transfer rule.  The
  admission predicate still applies."
  ([gate owner-id] (try-acquire! gate owner-id 0))
  ([gate owner-id timeout-ms]
   (check-admission! gate)
   (when (.tryAcquire ^Semaphore (:semaphore gate) (long timeout-ms) TimeUnit/MILLISECONDS)
     (grant! gate owner-id))))

(defn release!
  "Release W.  The permit owner must match the current owner."
  [permit]
  (let [gate (:gate permit)
        expected (:owner permit)
        owner-ref ^AtomicReference (:owner gate)]
    (when-not (held? gate)
      (throw (IllegalStateException. "Write gate is not held")))
    (when-not (= (:grant-id permit) (current-grant gate))
      (throw (IllegalStateException.
              (str "Write gate release uses a stale grant: expected "
                   (current-grant gate) ", actual " (:grant-id permit)))))
    (when-not (.compareAndSet owner-ref expected nil)
      (throw (IllegalStateException.
              (str "Write gate released by non-owner: expected " (pr-str expected)
                   ", actual " (pr-str (.get owner-ref))))))
    (.release ^Semaphore (:semaphore gate))
    nil))

(defmacro with-write-gate
  "Evaluate body while holding W, releasing it on every exit path."
  [[binding gate & [owner-id]] & body]
  `(let [permit# (acquire! ~gate ~owner-id)]
     (try
       (let [~binding permit#]
         ~@body)
       (finally
         (release! permit#)))))
