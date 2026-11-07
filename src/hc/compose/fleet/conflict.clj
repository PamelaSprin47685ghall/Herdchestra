(ns hc.compose.fleet.conflict
  "Holder-owned rebase conflict resolution.

  `control` detects a real Git conflict and calls `:resolve-conflict!` with
  the named swarm's worktree.  This namespace gives the live token holder a
  real board task, waits for the holder to finish it, then stages that
  worktree with `git add -A` so `rebase --continue` can retry.

  A missing holder, a rejected task, a vanished task, holder failure, or a
  timeout abandons only the named swarm.  This module never resolves a
  conflict by itself, never stages another worktree, and never uses a shell."
  (:require [hc.compose.fleet.git :as git]
            [hc.core.swarm-stm :as swarm]))

(defn- resolved?
  [decision]
  (or (true? decision)
      (= :resolved decision)
      (= :resolved (:status decision))))

(defn- abandoned
  [request reason]
  {:status :abandoned
   :swarm-id (:swarm-id request)
   :reason reason})

(defn- command-ok?
  [result]
  (and (map? result)
       (not (:error result))
       (not (false? (:ok? result)))))

(defn- sleep!
  [ms]
  (try
    (Thread/sleep (long ms))
    true
    (catch InterruptedException _
      (.interrupt (Thread/currentThread))
      false)))

(defn- service-value
  [service service-ref]
  (or service
      (when service-ref
        (if (instance? clojure.lang.IDeref service-ref)
          @service-ref
          service-ref))))

(defn- create-conflict-task!
  [runtime request task-id]
  (swarm/execute!
   (:kernel runtime)
   {:op :create-task
    :actor-id (:control-agent-id runtime)
    :ledger-key task-id
    :goal (str "Resolve rebase conflict for slice "
               (:slice-id request) ": " (:conflict request))
    :remaining-work (str "Edit the conflicting files in "
                         (:workspace request)
                         ", then finish this task.")
    :done-when "conflict files are edited and the task is finished"
    :needs-token true}))

(defn- wait-for-task
  [service request task-id deadline timeout-ms poll-ms clock]
  (loop []
    (let [board ((:board-snapshot service) (:swarm-id request))
          task (get-in board [:tasks task-id])]
      (cond
        (= :done (:state task))
        :resolved

        (nil? task)
        (abandoned request {:code :CONFLICT_TASK_DISAPPEARED
                            :swarm-id (:swarm-id request)
                            :task-id task-id})

        (>= (clock) deadline)
        (abandoned request {:code :HOLDER_CONFLICT_TIMEOUT
                            :swarm-id (:swarm-id request)
                            :task-id task-id
                            :timeout-ms timeout-ms})

        :else
        (if (sleep! poll-ms)
          (recur)
          (abandoned request {:code :HOLDER_CONFLICT_INTERRUPTED
                              :swarm-id (:swarm-id request)
                              :task-id task-id
                              :timeout-ms timeout-ms}))))))

(defn holder-resolver
  "Create a real holder conflict opportunity on the swarm board.

  On a conflict it verifies a live token holder, creates a `:needs-token`
  conflict task with a unique ledger key, wakes the holder through
  `service/wake-actors!`, and waits for the holder to finish that task.

  Options: `:service` or `:service-ref` (atom), `:timeout-ms` (default
  300000), `:poll-ms` (default 25), `:clock`."
  [{:keys [service service-ref timeout-ms poll-ms clock start-effects!]}]
  (when-not (or service service-ref)
    (throw (ex-info "holder conflict resolver requires :service or :service-ref"
                    {:error :SERVICE_REQUIRED})))
  (let [timeout-ms (or timeout-ms 300000)
        poll-ms (or poll-ms 25)
        clock (or clock #(System/currentTimeMillis))]
    (fn [request]
      (let [service (service-value service service-ref)
            swarm-id (:swarm-id request)
            runtime (when service ((:runtime service) swarm-id))
            board (when service ((:board-snapshot service) swarm-id))
            holder (get-in board [:token-slot :holder-id])
            holder-actor (get-in board [:actors holder])]
        (cond
          (nil? service)
          (abandoned request {:code :SERVICE_NOT_ATTACHED
                              :swarm-id swarm-id})

          (nil? runtime)
          (abandoned request {:code :NO_WORKTREE_RUNTIME
                              :swarm-id swarm-id})

          (or (nil? holder)
              (not= :alive (:life-state holder-actor)))
          (abandoned request {:code :NO_LIVE_HOLDER
                              :swarm-id swarm-id
                              :holder-id holder})

          :else
          (let [task-id (str "fleet-conflict-" swarm-id "-"
                             (:round request 0) "-"
                             (java.util.UUID/randomUUID))
                created (create-conflict-task! runtime request task-id)]
            (if-not (command-ok? created)
              (abandoned request {:code :CONFLICT_TASK_REJECTED
                                  :swarm-id swarm-id
                                  :result created})
              (let [created-task-id (:task-id created)
                    wake ((:wake-actors! service) swarm-id)]
                (if-not (:ok? wake)
                  (abandoned request {:code :HOLDER_WAKE_FAILED
                                      :swarm-id swarm-id
                                      :result wake})
                  (let [started (when start-effects!
                                  (start-effects! (:effects wake)))]
                    (if (and started (not (:ok? started)))
                      (abandoned request {:code :HOLDER_EFFECTS_NOT_STARTED
                                          :swarm-id swarm-id
                                          :result started})
                      (wait-for-task service request created-task-id
                                     (+ (clock) timeout-ms)
                                     timeout-ms poll-ms clock))))))))))))

(defn make-resolver
  "Build a `:resolve-conflict!` callback from a holder decision function.

  `:git` is required.  `:holder-resolve!` returns `:resolved`/true or an
  abandoned decision.  A resolved decision is staged with `git add -A` in the
  same worktree before returning `:resolved`."
  [{:keys [git holder-resolve!]}]
  (when-not git
    (throw (ex-info "fleet conflict resolver requires :git"
                    {:error :GIT_REQUIRED})))
  (fn [request]
    (let [decision (if holder-resolve!
                     (try
                       (holder-resolve! request)
                       (catch Throwable t
                         (abandoned request
                                    {:code :CONFLICT_HOLDER_FAILED
                                     :message (or (.getMessage t) (str t))
                                     :swarm-id (:swarm-id request)})))
                     (abandoned request
                                {:code :CONFLICT_HOLDER_UNAVAILABLE
                                 :swarm-id (:swarm-id request)}))]
      (cond
        (resolved? decision)
        (let [staged (git/stage-all! git (:workspace request))]
          (if (= :ok (:status staged))
            :resolved
            (abandoned request
                       {:code :CONFLICT_STAGE_FAILED
                        :swarm-id (:swarm-id request)
                        :result staged})))

        (or (and (map? decision)
                 (contains? #{:abandon :abandoned} (:status decision)))
            (false? decision)
            (nil? decision)
            (= :abandon decision)
            (= :abandoned decision))
        (if (map? decision)
          decision
          (abandoned request
                     {:code :CONFLICT_HOLDER_ABANDONED
                      :swarm-id (:swarm-id request)
                      :decision decision}))

        :else
        (abandoned request
                   {:code :CONFLICT_HOLDER_INVALID
                    :swarm-id (:swarm-id request)
                    :decision decision})))))

(defn make-board-resolver
  "Build the complete holder resolver for app/fleet wiring.

  Options: `:git` (real GitPort), `:service-ref` (atom holding the fleet
  service), `:timeout-ms`, `:poll-ms`, `:clock`."
  [{:keys [git service-ref timeout-ms poll-ms clock start-effects!]}]
  (make-resolver
   {:git git
    :holder-resolve! (holder-resolver
                      {:service-ref service-ref
                       :timeout-ms timeout-ms
                       :poll-ms poll-ms
                       :clock clock
                       :start-effects! start-effects!})}))
