(ns hc.compose.fleet.runtime
  "One real swarm runtime bound to one Git worktree.

  Every call to `start!` creates a fresh `$W$` write gate, a fresh swarm STM
  kernel/board, and a `hc.compose.workspace` runtime rooted at that swarm's
  worktree.  Nothing is shared with the session's global workspace or with a
  sibling swarm.  The board gets one fleet-control actor and one root task
  derived from the slice; the token slot stays free until a real worker takes
  it.

  This namespace does not launch a model worker.  `hc.compose.fleet.service`
  asks the application launcher to do that, and reports
  `:SWARM_EXECUTOR_UNAVAILABLE` when no real launcher exists.  A runtime alone
  is not evidence that a swarm is running."
  (:require [clojure.string :as str]
            [hc.compose.workspace :as workspace]
            [hc.core.swarm-stm :as swarm]
            [hc.core.workspace-gate :as wg]))

(defn- fail
  [code message details]
  {:ok? false
   :error (cond-> {:code code :message message}
            (some? details) (assoc :details details))})

(defn- command-ok?
  [result]
  (and (map? result)
       (not (:error result))
       (not (false? (:ok? result)))))

(defn- nonblank?
  [value]
  (and (string? value) (not (str/blank? value))))

(defn- uncertain-action-ids
  "Actions that may have reached a side effect and were never resolved.  A
  restored worktree must not admit a new write while one exists.  The service
  normally converts `:applying`/`:running` to `:uncertain` first; keeping all
  three here fails closed if a caller restores a raw board."
  [board]
  (->> (:actions board)
       (keep (fn [[action-id action]]
               (when (contains? #{:applying :running :uncertain}
                                (:phase action))
                 action-id)))
       (sort-by str)
       vec))

(defn- recovery-barrier-state
  "Describe why a restored worktree cannot yet admit writes.

  The old process-local `$W$` is gone; its held status is evidence, not a live
  permit.  A restored uncertain action is the same kind of evidence.  Either
  one keeps the new gate closed until an explicit acknowledgement."
  [board write-gate-recovery]
  (let [uncertain (uncertain-action-ids board)
        acknowledgement (get-in board [:recovery :acknowledgement])
        ;; A snapshot taken while this runtime held W for recovery records
        ;; its own marker as the gate owner.  Once the acknowledgement is
        ;; durable and no action is pending, that marker is not new evidence
        ;; and must not re-block every later restart.
        own-recovery-hold? (and (= :fleet-recovery-hold
                                   (:owner write-gate-recovery))
                                (true? (:acknowledged? acknowledgement))
                                (empty? uncertain))
        gate-held? (and (true? (:outcome-unknown? write-gate-recovery))
                        (not own-recovery-hold?))
        reasons (cond-> #{}
                  (seq uncertain) (conj :uncertain-actions)
                  gate-held? (conj :write-gate-held))]
    (when (seq reasons)
      {:blocked? true
       :reasons reasons
       :uncertain-actions uncertain
       :write-gate-recovery (when write-gate-recovery
                              (select-keys write-gate-recovery
                                           [:previous :held? :owner :queue
                                            :outcome-unknown? :queue-abandoned?
                                            :reason :recovered-at]))
       :recovered-at (:recovered-at write-gate-recovery)})))

(defn- recovery-admission
  [swarm-id barrier]
  (fn []
    (when-let [current @barrier]
      (when (:blocked? current)
        {:status :RECOVERY_UNRESOLVED
         :code :recovery-unresolved
         :message
         "the worktree has unresolved recovery evidence; resolve uncertain actions and acknowledge recovery before writing"
         :outcome :not-written
         :file-changed? false
         :details {:swarm-id swarm-id
                   :uncertain-actions (:uncertain-actions current)
                   :reasons (:reasons current)
                   :write-gate-recovery (:write-gate-recovery current)}}))))

(defn- install-recovery-barrier!
  "Hold the fresh `$W$` for recovery and refuse new admissions until
  `acknowledge-recovery!` clears the evidence.  The hold is a real permit, so
  the gate reports `:held? true`; it is not a claim that the old permit
  survived."
  [gate swarm-id barrier]
  (when (:blocked? @barrier)
    (let [permit (wg/acquire-write-gate! gate :fleet-recovery-hold)]
      (reset! barrier (assoc @barrier :permit permit))
      (wg/set-write-gate-admission! gate (recovery-admission swarm-id barrier))
      barrier)))

(defn- release-recovery-barrier!
  [gate barrier acknowledgement]
  (when-let [current @barrier]
    (when (:blocked? current)
      (wg/clear-write-gate-admission! gate)
      (when-let [permit (:permit current)]
        (wg/release-write-gate! permit))
      (reset! barrier (-> current
                          (assoc :blocked? false
                                 :acknowledgement
                                 (assoc acknowledgement :acknowledged? true))
                          (dissoc :permit))))))

(defn- board-roots
  [board]
  (filter #(nil? (:parent-id %)) (vals (:tasks board))))

(defn- root-counts
  [board]
  (let [roots (board-roots board)
        count-state (fn [state]
                      (count (filter #(= state (:state %)) roots)))]
    {:ready (count-state :ready)
     :waiting (count-state :waiting)
     :done (count-state :done)}))

(defn board
  [runtime]
  (swarm/snapshot (:kernel runtime)))

(defn board-version
  [runtime]
  (swarm/board-version (:kernel runtime)))

(defn token-slot
  [runtime]
  (assoc (or (get-in (board runtime) [:token-slot])
             {:holder-id nil :term-id 0 :state :free})
         :swarm-id (:swarm-id runtime)))

(defn write-gate-status
  [runtime]
  (let [barrier (some-> (:recovery-barrier runtime) deref)]
    (cond-> {:swarm-id (:swarm-id runtime)
             :held? (wg/write-gate-held? (:write-gate runtime))
             :owner (wg/write-gate-owner (:write-gate runtime))
             :queue (wg/write-gate-queued (:write-gate runtime))
             :recovery-blocked? false}
      (and barrier (:blocked? barrier))
      (assoc :recovery-blocked? true
             :recovery-reasons (:reasons barrier)
             :recovery-actions (:uncertain-actions barrier)
             :recovery-write-gate (:write-gate-recovery barrier))

      (and barrier (not (:blocked? barrier)) (:acknowledgement barrier))
      (assoc :recovery-blocked? false
             :recovery-acknowledgement (:acknowledgement barrier)))))

(defn observation
  "Real per-swarm board and authority facts.  This never invents a fleet
  status; the fleet service overlays the authoritative fleet row."
  [runtime]
  (let [b (board runtime)
        counts (root-counts b)]
    (cond-> {:swarm-id (:swarm-id runtime)
             :slice-id (:slice-id runtime)
             :worktree (:workspace runtime)
             :runtime-status (:status runtime)
             :board b
             :board-version (:board-version b)
             :board-last-event (some-> b :events last :type)
             :root-ready (:ready counts)
             :root-waiting (:waiting counts)
             :root-done (:done counts)
             :token-slot (token-slot runtime)
             :write-gate (write-gate-status runtime)}
      (:runtime-source runtime)
      (assoc :runtime-source (:runtime-source runtime))

      (:recovery runtime)
      (assoc :runtime-recovery (:recovery runtime))

      (:actor-recovery runtime)
      (assoc :actor-recovery (:actor-recovery runtime))

      (:write-gate-recovery runtime)
      (assoc :write-gate-recovery (:write-gate-recovery runtime))

      (:workspace-missing? runtime)
      (assoc :workspace-missing? true))))

(defn start!
  "Create the isolated runtime for one swarm worktree.

  Options:
  `:swarm-id`, `:slice-id`, `:workspace`, `:branch`, `:slice`
  `:limits` passed to the workspace bridge; `:clock` for receipts;
  `:on-board-event!` called after every committed board command so the
  service can persist direct conflict/program board changes.

  The worktree must already exist.  The function never creates or removes a
  worktree; `hc.compose.fleet.control` owns Git lifetime."
  [{:keys [swarm-id slice-id workspace branch slice limits clock
           evaluate-factory on-board-event!]
    :or {clock #(System/currentTimeMillis)}}]
  (let [workspace (when workspace (str workspace))
        slice (or slice {})]
    (cond
      (not (and (string? swarm-id) (not (str/blank? swarm-id))))
      (fail :INVALID_SWARM_ID "swarm-id must be a non-blank string"
            {:swarm-id swarm-id})

      (not (and (string? slice-id) (not (str/blank? slice-id))))
      (fail :INVALID_SLICE_ID "slice-id must be a non-blank string"
            {:slice-id slice-id})

      (not (and (string? workspace) (not (str/blank? workspace))))
      (fail :INVALID_WORKSPACE "workspace must be a non-blank path"
            {:workspace workspace})

      (not (and (string? branch) (not (str/blank? branch))))
      (fail :INVALID_BRANCH "branch must be a non-blank string"
            {:branch branch})

      (not (and (string? (:goal slice)) (not (str/blank? (:goal slice)))))
      (fail :INVALID_SLICE_GOAL "slice goal must be a non-blank string"
            {:slice-id slice-id :slice slice})

      (not (and (string? (:done-when slice))
                (not (str/blank? (:done-when slice)))))
      (fail :INVALID_DONE_WHEN "slice done-when must be a non-blank string"
            {:slice-id slice-id :slice slice})

      (not (.isDirectory (java.io.File. workspace)))
      (fail :SWARM_WORKTREE_MISSING "swarm workspace does not exist"
            {:swarm-id swarm-id :workspace workspace})

      (not (.exists (java.io.File. workspace ".git")))
      (fail :SWARM_WORKTREE_NOT_GIT "swarm workspace is not a Git worktree"
            {:swarm-id swarm-id :workspace workspace})

      :else
      (let [gate (wg/write-gate)
            kernel (swarm/create
                    {:on-events (fn [events]
                                  (when on-board-event!
                                    (on-board-event! events)))})
            eval-ref (atom nil)
            workspace-runtime (workspace/make-runtime
                               {:root workspace
                                :kernel kernel
                                :write-gate gate
                                :clock clock
                                :limits limits
                                :evaluate (when evaluate-factory
                                            (fn [request] (@eval-ref request)))})
            evaluator (when evaluate-factory
                        (evaluate-factory workspace-runtime))
            _ (reset! eval-ref evaluator)
            workspace-runtime (assoc workspace-runtime :evaluate evaluator)
            actor-result (swarm/execute!
                          kernel
                          {:op :register-actor
                           :slot-id (keyword (str "fleet-control-" swarm-id))})]
        (if-not (command-ok? actor-result)
          (fail :SWARM_ACTOR_REGISTRATION_FAILED
                "swarm board rejected the control actor"
                {:swarm-id swarm-id :result actor-result})
          (let [control-agent-id (:agent-id actor-result)
                task-result (swarm/execute!
                             kernel
                             {:op :create-task
                              :actor-id control-agent-id
                              :ledger-key slice-id
                              :goal (:goal slice)
                              :remaining-work (or (:remaining-work slice)
                                                  (:goal slice))
                              :done-when (:done-when slice)})]
            (if-not (command-ok? task-result)
              (fail :SWARM_ROOT_TASK_FAILED
                    "swarm board rejected the root task"
                    {:swarm-id swarm-id :result task-result})
              (let [runtime {:swarm-id swarm-id
                             :slice-id slice-id
                             :workspace workspace
                             :branch branch
                             :slice slice
                             :kernel kernel
                             :write-gate gate
                             :workspace-runtime workspace-runtime
                             :evaluator evaluator
                             :control-agent-id control-agent-id
                             :root-task-id (:task-id task-result)
                             :status :ready
                             :runtime-source :created
                             :clock clock
                             :recovery-barrier (atom nil)
                             :closed? (atom false)
                             :created-at (clock)}]
                {:ok? true
                 :runtime runtime}))))))))

(defn- control-agent-id-of
  [board swarm-id]
  (let [slot (keyword (str "fleet-control-" swarm-id))]
    (or (some (fn [[agent-id actor]]
                (when (= slot (:slot-id actor)) agent-id))
              (:actors board))
        (some (fn [[agent-id actor]]
                (when (and (:slot-id actor)
                           (str/starts-with? (name (:slot-id actor))
                                             "fleet-control-"))
                  agent-id))
              (:actors board)))))

(defn- root-task-id-of
  [board slice-id]
  (some (fn [[task-id task]]
          (when (and (nil? (:parent-id task))
                     (= slice-id (:ledger-key task)))
            task-id))
        (:tasks board)))

(defn- worktree-present?
  [workspace]
  (let [dir (java.io.File. workspace)]
    (and (.isDirectory dir)
         (.exists (java.io.File. dir ".git")))))

(defn restore!
  "Rebuild a live runtime from a persisted board and worktree identity.

  The board is adopted through `swarm/create {:initial-board ...}` and its
  invariants are checked before any actor or worker is attached.  A fresh
  process-local write gate and workspace runtime are created.  The old gate
  status is evidence, never a live permit; when it was held, or when the
  recovered board still has an `:uncertain` action, the new gate is held as
  `:fleet-recovery-hold` and refuses new admissions until
  `acknowledge-recovery!` records explicit evidence.  This function never
  creates a new board, actor, or root task."
  [{:keys [swarm-id slice-id workspace branch slice limits clock
           evaluate-factory board write-gate-recovery recovery status
           on-board-event!]
    :or {clock #(System/currentTimeMillis)}}]
  (let [workspace (when workspace (str workspace))
        slice (or slice {})]
    (cond
      (not (and (string? swarm-id) (not (str/blank? swarm-id))))
      (fail :INVALID_SWARM_ID "swarm-id must be a non-blank string"
            {:swarm-id swarm-id})

      (not (and (string? slice-id) (not (str/blank? slice-id))))
      (fail :INVALID_SLICE_ID "slice-id must be a non-blank string"
            {:slice-id slice-id})

      (not (and (string? workspace) (not (str/blank? workspace))))
      (fail :INVALID_WORKSPACE "workspace must be a non-blank path"
            {:workspace workspace})

      (not (and (string? branch) (not (str/blank? branch))))
      (fail :INVALID_BRANCH "branch must be a non-blank string"
            {:branch branch})

      (not (map? board))
      (fail :SWARM_BOARD_MISSING
            "a persisted runtime requires its worktree board"
            {:swarm-id swarm-id})

      :else
      (let [gate (wg/write-gate)
            barrier (atom (recovery-barrier-state board write-gate-recovery))
            kernel (swarm/create
                    {:initial-board board
                     :on-events (fn [events]
                                  (let [persisted (when on-board-event!
                                                    (on-board-event! events))
                                        ack (some #(when (= :recovery-acknowledged
                                                             (:type %))
                                                     %)
                                                  events)]
                                    (when (and ack
                                               (not (false? (:ok? persisted))))
                                      (release-recovery-barrier!
                                       gate barrier
                                       {:actor-id (:actor-id ack)
                                        :evidence (:evidence ack)
                                        :write-gate-held? (:write-gate-held? ack)
                                        :event-seq (:event-seq ack)
                                        :at-version (:at-version ack)}))
                                    persisted))})
            violations (swarm/check-invariants kernel)
            control-agent-id (control-agent-id-of board swarm-id)
            root-task-id (root-task-id-of board slice-id)]
        (cond
          (seq violations)
          (fail :SWARM_BOARD_INVALID
                "the persisted worktree board violates board invariants"
                {:swarm-id swarm-id
                 :violations (vec violations)})

          (nil? control-agent-id)
          (fail :SWARM_CONTROL_ACTOR_MISSING
                "the persisted board has no fleet-control actor"
                {:swarm-id swarm-id})

          (nil? root-task-id)
          (fail :SWARM_ROOT_TASK_MISSING
                "the persisted board has no root task for its slice"
                {:swarm-id swarm-id :slice-id slice-id})

          :else
          (let [_ (install-recovery-barrier! gate swarm-id barrier)
                eval-ref (atom nil)
                workspace-runtime (workspace/make-runtime
                                   {:root workspace
                                    :kernel kernel
                                    :write-gate gate
                                    :clock clock
                                    :limits limits
                                    :evaluate (when evaluate-factory
                                                (fn [request]
                                                  (@eval-ref request)))})
                evaluator (when evaluate-factory
                            (evaluate-factory workspace-runtime))
                _ (reset! eval-ref evaluator)
                workspace-runtime (assoc workspace-runtime :evaluate evaluator)
                runtime {:swarm-id swarm-id
                         :slice-id slice-id
                         :workspace workspace
                         :branch branch
                         :slice slice
                         :kernel kernel
                         :write-gate gate
                         :workspace-runtime workspace-runtime
                         :evaluator evaluator
                         :control-agent-id control-agent-id
                         :root-task-id root-task-id
                         :status (or status :queued)
                         :runtime-source :persisted
                         :runtime-recovered? true
                         :recovered? true
                         :recovery recovery
                         :write-gate-recovery write-gate-recovery
                         :clock clock
                         :recovery-barrier barrier
                         :workspace-missing? (not (worktree-present? workspace))
                         :closed? (atom false)
                         :created-at (clock)}]
            {:ok? true
             :runtime runtime}))))))

(defn acknowledge-recovery!
  "Resolve the restored worktree's recovery write barrier.

  REQUEST carries `:evidence`, a non-blank string describing the operator or
  agent verification, and an optional `:actor-id` (default: the fleet-control
  actor).  Every `:uncertain` action must already be resolved with its own
  `resolve-action` evidence; this function never changes an action phase and
  never fabricates success or failure for the old side effect.

  The acknowledgement is a board event.  The runtime releases the recovery
  hold on the new `$W$` only after the configured board-event hook confirms
  that event was persisted.  A persistence failure leaves the barrier in
  place and returns the underlying error."
  [runtime {:keys [actor-id evidence]
            :or {actor-id (:control-agent-id runtime)}}]
  (let [barrier-atom (:recovery-barrier runtime)
        current (when barrier-atom @barrier-atom)]
    (cond
      (nil? runtime)
      {:ok? false
       :error {:code :RUNTIME_REQUIRED
               :message "a fleet runtime is required"}}

      (nil? current)
      {:ok? true :already? true :released? false}

      (not (:blocked? current))
      {:ok? true
       :already? true
       :released? false
       :acknowledgement (:acknowledgement current)}

      (not (nonblank? evidence))
      {:ok? false
       :error {:code :RECOVERY_EVIDENCE_REQUIRED
               :message "recovery acknowledgement requires non-blank :evidence"
               :details {:swarm-id (:swarm-id runtime)}}}

      :else
      (let [pending (uncertain-action-ids (board runtime))]
        (if (seq pending)
          {:ok? false
           :error {:code :RECOVERY_ACTIONS_PENDING
                   :message "resolve every uncertain action before acknowledging recovery"
                   :details {:swarm-id (:swarm-id runtime)
                             :actions pending}}}
          (let [result (swarm/execute!
                        (:kernel runtime)
                        {:op :acknowledge-recovery
                         :actor-id actor-id
                         :evidence evidence
                         :write-gate-held?
                         (true? (get-in current [:write-gate-recovery
                                                 :outcome-unknown?]))})
                released? (not (:blocked? @barrier-atom))]
            (cond
              (and (:ok? result) released?)
              {:ok? true
               :released? true
               :result result
               :acknowledgement (get-in (board runtime)
                                        [:recovery :acknowledgement])}

              (:ok? result)
              {:ok? false
               :error {:code :RECOVERY_RELEASE_FAILED
                       :message
                       "recovery acknowledgement was committed but the write barrier was not released"
                       :details {:swarm-id (:swarm-id runtime)}}
               :result result}

              :else
              {:ok? false
               :error {:code (or (:error result) :RECOVERY_ACK_FAILED)
                       :message (or (:message result)
                                    "recovery acknowledgement was not persisted")
                       :details (cond-> {:swarm-id (:swarm-id runtime)}
                                  (contains? result :persisted?)
                                  (assoc :persisted? (:persisted? result))

                                  (:persist-error result)
                                  (assoc :persist-error (:persist-error result)))}
               :result result})))))))

(defn stop!
  "Close the runtime's local bookkeeping.  The worktree is not deleted; the
  fleet controller owns retention and GC."
  [runtime]
  (when runtime
    (reset! (:closed? runtime) true)
    (reset! (get-in runtime [:workspace-runtime :closed?]) true))
  {:ok? true :swarm-id (:swarm-id runtime)})
