(ns hc.compose.mission.worker
  "Plan dedicated worker contract (root AGENTS.md §2.3, §4.1).

  Each Plan tenure owns at most one dedicated worker.  The worker:

  - runs on the task model;
  - has no swarm board and no token;
  - can read and locate text, park/unpark, and run black-box shell commands;
  - has no write, mkdir, rm, mv, split, claim, finish, or review method;
  - is reused and steered for every `(devops ...)` call in the tenure;
  - after death, is replaced by exactly one fresh worker on the next call;
  - returns its final report verbatim, without host summarization.

  The state is plain EDN: `{:worker worker-or-nil :next-seq n}`.  This
  namespace never launches a process.  `ensure` returns a spawn/steer effect
  for the host to dispatch after the state commit."
  (:refer-clojure :exclude [ensure])
  (:require [clojure.string :as str]))

(def capabilities
  "The complete Plan dedicated worker capability surface."
  #{:cat :stat :ls :where :whereAll :park :unpark :call})

(def forbidden-capabilities
  "Capabilities that must never appear on a Plan dedicated worker."
  #{:write :mkdir :rm :mv :split :claim :finish :cancel :replace :release
    :reopen :comment :update :take-or-die :review :board :token :wake-all
    :ask})

(def contract
  "Declared contract for a Plan dedicated worker."
  {:role :plan-worker
   :model-role :task
   :board? false
   :token? false
   :write? false
   :one-per-tenure? true
   :reuse :steer
   :report :verbatim
   :capabilities capabilities
   :forbidden-capabilities forbidden-capabilities})

(defn new-worker
  "Create one idle worker bound to TENURE-ID and MODEL-ID."
  [{:keys [worker-id tenure-id model-id generation]}]
  (when-not (and (string? worker-id) (not (str/blank? worker-id)))
    (throw (ex-info "worker-id must be a non-blank string"
                    {:code :INVALID_WORKER_ID :worker-id worker-id})))
  (when-not (and (string? tenure-id) (not (str/blank? tenure-id)))
    (throw (ex-info "tenure-id must be a non-blank string"
                    {:code :INVALID_TENURE_ID :tenure-id tenure-id})))
  (when-not (and (string? model-id) (not (str/blank? model-id)))
    (throw (ex-info "model-id must be a non-blank string"
                    {:code :INVALID_MODEL_ID :model-id model-id})))
  {:worker-id worker-id
   :tenure-id tenure-id
   :role :plan-worker
   :model-role :task
   :model-id model-id
   :capabilities capabilities
   :board? false
   :token? false
   :write? false
   :status :idle
   :life-state :alive
   :generation (long (or generation 1))
   :steer-seq 0
   :pending-call-id nil
   :last-intent nil
   :last-report nil
   :last-status nil
   :report-count 0})

(defn- fail
  [code message details]
  {:ok? false
   :error (cond-> {:code code :message message}
            (some? details) (assoc :details details))})

(defn live?
  [worker]
  (and worker
       (not (contains? #{:dead :retired} (:status worker)))
       (= :alive (:life-state worker))))

(defn reusable?
  "True when WORKER can serve another devops call for TENURE-ID."
  [worker tenure-id]
  (and (live? worker)
       (= tenure-id (:tenure-id worker))))

(defn- ensure-request-valid
  [{:keys [tenure-id call-id verbose-intent]}]
  (cond
    (not (and (string? tenure-id) (not (str/blank? tenure-id))))
    (fail :INVALID_TENURE_ID
          "devops requires a non-blank tenure-id"
          {:tenure-id tenure-id})

    (not (and (string? call-id) (not (str/blank? call-id))))
    (fail :CALL_ID_REQUIRED
          "devops requires a non-blank call-id"
          {:call-id call-id})

    (not (and (string? verbose-intent) (not (str/blank? verbose-intent))))
    (fail :VERBOSE_INTENT_REQUIRED
          "devops requires a non-blank verbose-intent"
          {:verbose-intent verbose-intent})

    :else nil))

(defn ensure
  "Reuse or create the one worker for TENURE-ID.

  STATE is `{:worker worker-or-nil :next-seq n}`.  The returned state has
  the worker marked running with the new pending call.  The effect is either
  `:worker/steer` (same live worker) or `:worker/spawn` (first call or after
  death).  There is no `:DEVOPS_IN_FLIGHT` rejection and no second live
  worker for one tenure."
  [state {:keys [tenure-id model-id call-id verbose-intent] :as request}]
  (or (ensure-request-valid request)
      (when-not (and (string? model-id) (not (str/blank? model-id)))
        (fail :INVALID_MODEL_ID
              "devops requires the configured task model id"
              {:model-id model-id}))
      (let [current (:worker state)
            next-seq (long (or (:next-seq state) 1))
            reuse? (reusable? current tenure-id)]
        (if reuse?
          (let [worker (-> current
                           (assoc :status :running
                                  :pending-call-id call-id
                                  :last-intent verbose-intent)
                           (update :steer-seq inc))
                effect {:type :worker/steer
                        :worker-id (:worker-id worker)
                        :tenure-id tenure-id
                        :call-id call-id
                        :verbose-intent verbose-intent
                        :model-role :task
                        :model-id model-id
                        :capabilities capabilities
                        :reused? true}]
            {:ok? true
             :state {:worker worker :next-seq next-seq}
             :reused? true
             :worker-id (:worker-id worker)
             :effect effect})
          (let [generation (if current (inc (long (:generation current 0))) 1)
                worker (new-worker {:worker-id (str "plan-worker-" next-seq)
                                    :tenure-id tenure-id
                                    :model-id model-id
                                    :generation generation})
                worker (-> worker
                           (assoc :status :running
                                  :pending-call-id call-id
                                  :last-intent verbose-intent)
                           (update :steer-seq inc))
                effect {:type :worker/spawn
                        :worker-id (:worker-id worker)
                        :tenure-id tenure-id
                        :call-id call-id
                        :verbose-intent verbose-intent
                        :model-role :task
                        :model-id model-id
                        :generation generation
                        :contract contract
                        :reused? false}]
            {:ok? true
             :state {:worker worker :next-seq (inc next-seq)}
             :reused? false
             :worker-id (:worker-id worker)
             :effect effect})))))

(defn complete
  "Fix the final report of a worker call.

  The report is stored and returned exactly as supplied.  A report whose
  call-id does not match the pending call is rejected as stale; the worker
  is not killed and the host can query or steer it again."
  [worker {:keys [call-id status report]}]
  (cond
    (nil? worker)
    (fail :UNKNOWN_WORKER "worker does not exist" {:call-id call-id})

    (not= :alive (:life-state worker))
    (fail :WORKER_DEAD
          "worker is no longer alive; the next devops call creates a fresh one"
          {:worker-id (:worker-id worker)
           :life-state (:life-state worker)})

    (not (contains? #{:ok :failed :interrupted} status))
    (fail :INVALID_WORKER_STATUS
          "worker status must be :ok, :failed, or :interrupted"
          {:status status})

    (not (string? report))
    (fail :INVALID_WORKER_REPORT
          "worker report must be a string"
          {:report report})

    (or (nil? (:pending-call-id worker))
        (not= call-id (:pending-call-id worker)))
    (fail :STALE_WORKER_CALL
          "report call-id does not match the pending call"
          {:call-id call-id
           :pending-call-id (:pending-call-id worker)})

    :else
    (let [worker' (-> worker
                      (assoc :status :idle
                             :pending-call-id nil
                             :last-report report
                             :last-status status)
                      (update :report-count inc))]
      {:ok? true
       :worker worker'
       :result {:status status
                :report report
                :worker-id (:worker-id worker)
                :tenure-id (:tenure-id worker)}})))

(defn mark-dead
  "Record worker death.  The next ensure creates a fresh identity."
  [worker reason]
  (when worker
    (assoc worker
           :status :dead
           :life-state :dead
           :pending-call-id nil
           :death-reason reason)))

(defn check-worker
  "Return contract violations for WORKER."
  [worker]
  (if-not worker
    []
    (cond-> []
      (not= :plan-worker (:role worker))
      (conj {:code :WORKER_ROLE_INVALID :role (:role worker)})

      (not= :task (:model-role worker))
      (conj {:code :WORKER_MODEL_ROLE_INVALID
             :model-role (:model-role worker)})

      (not (every? capabilities (:capabilities worker)))
      (conj {:code :WORKER_CAPABILITY_MISSING
             :capabilities (:capabilities worker)})

      (seq (filter forbidden-capabilities (:capabilities worker)))
      (conj {:code :WORKER_FORBIDDEN_CAPABILITY
             :forbidden (vec (filter forbidden-capabilities
                                     (:capabilities worker)))})

      (or (:board? worker) (:token? worker) (:write? worker))
      (conj {:code :WORKER_EXTRA_AUTHORITY
             :board? (:board? worker)
             :token? (:token? worker)
             :write? (:write? worker)})

      (not (and (string? (:worker-id worker))
                (not (str/blank? (:worker-id worker)))))
      (conj {:code :WORKER_ID_INVALID :worker-id (:worker-id worker)})

      (not (and (string? (:tenure-id worker))
                (not (str/blank? (:tenure-id worker)))))
      (conj {:code :WORKER_TENURE_INVALID :tenure-id (:tenure-id worker)})

      (not (or (and (= :running (:status worker))
                    (some? (:pending-call-id worker)))
               (and (not= :running (:status worker))
                    (nil? (:pending-call-id worker)))))
      (conj {:code :WORKER_PENDING_STATE_INVALID
             :status (:status worker)
             :pending-call-id (:pending-call-id worker)}))))
