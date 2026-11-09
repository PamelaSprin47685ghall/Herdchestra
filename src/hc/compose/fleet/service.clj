(ns hc.compose.fleet.service
  "Application-facing fleet service.

  One service belongs to one repository/mission at the application level, not
  to an individual session Owner.  It owns a real `hc.compose.fleet.control`,
  a real Git adapter, and the per-swarm runtimes created for launched slices.
  It never reuses the session's global workspace gate.

  The service creates a per-swarm runtime before calling the application
  launcher.  The launcher receives `:runtime` and must start a real swarm
  worker.  If no launcher is configured, or it reports failure, the swarm is
  reported as failed with `:SWARM_EXECUTOR_UNAVAILABLE`; the service never
  marks a swarm running merely because a worktree and board exist.

  Observation is explicit:

    (observation service)
    ;; {:mission-id ...
    ;;  :fleet-version N :master-version N
    ;;  :swarms [row ...] :events [...]}

    (snapshot-tables service)
    ;; {:swarm/by-id {...} :token-slot/by-id {...} :write-gate/by-id {...}
    ;;  :fleet/version N :fleet/master-version N :fleet/events [...]}

  Each row carries its own `:board`, `:token-slot`, and `:write-gate`.  There
  is no synthetic `\"local\"` row and no shared gate projection."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [hc.compose.fleet.actors :as actors]
            [hc.compose.fleet.control :as control]
            [hc.compose.fleet.git :as git]
            [hc.compose.fleet.restore :as restore]
            [hc.compose.fleet.runtime :as runtime]
            [hc.compose.fleet.state :as state]
            [hc.core.swarm-stm :as swarm]))

(declare runtime board-snapshot wake-actors! accept-actor-event!
         continue-actor-after-tool! actor-state worktree-runtimes
         runtime-for-workspace gc! sync-runtime-status! stop-named-swarm!
         claim-tool-call!)

(defn- fail
  [code message details]
  {:ok? false
   :error (cond-> {:code code :message message}
            (some? details) (assoc :details details))})

(defn- launch-failure?
  "The launcher must return `true` or a map without failure.  Anything else,
  including nil, is an explicit failure.  This is the poka-yoke against a
  caller that forgets to start a worker."
  [result]
  (cond
    (true? result) false
    (and (map? result)
         (not (false? (:ok? result)))
         (not= :failed (:status result))
         (nil? (:error result))) false
    :else true))

(defn- unavailable-launcher
  [request]
  {:status :failed
   :error {:code :SWARM_EXECUTOR_UNAVAILABLE
           :message "no real swarm executor is attached to the application"
           :swarm-id (:swarm-id request)}})

(defn- preflight
  [git-adapter master-ref]
  (if-not (git/available? git-adapter)
    (fail :GIT_UNAVAILABLE
          "the Git executable is not available"
          {:master-ref master-ref})
    (let [head (git/repo-head git-adapter master-ref)]
      (if (= :ok (:status head))
        {:ok? true :head (:head head)}
        (fail :MASTER_REF_UNAVAILABLE
              "the configured master ref cannot be read"
              {:master-ref master-ref :result head})))))

(defn- worktree-present?
  [workspace]
  (let [dir (io/file (str workspace))]
    (and (.isDirectory dir)
         (.exists (io/file dir ".git")))))

(defn- persist-state!
  "Persist one control state through the service's configured store.

  The last persistence error is kept for `status`; a successful write clears
  it.  The snapshot includes the current runtime map, so one atomic file
  replace carries the control state and the per-worktree board/actor state
  that existed at that boundary.  Without a store the service stays fully
  in-memory."
  [service state]
  (if-let [store (:store service)]
    (let [monitor (get-in service [:control :monitor])
          write! (fn []
                   (let [snapshot (restore/snapshot
                                   (-> service
                                       (dissoc :control)
                                       (assoc :state state)))
                         result (restore/save! store snapshot)]
                     (if (:ok? result)
                       (do (reset! (:persist-error service) nil) result)
                       (do (reset! (:persist-error service) (:error result))
                           result))))]
      (if monitor
        (locking monitor (write!))
        (write!)))
    {:ok? true}))

(defn- persist-current-state!
  "Read and persist the current control state under the control monitor.

  A board-event persist must not read an older control state, wait for a
  newer control transition to publish, and then overwrite the file with the
  older snapshot.  Reading and writing under the same monitor closes that
  interleaving; `persist-state!` re-enters the monitor for control-driven
  writes that already hold it."
  [service]
  (let [monitor (get-in service [:control :monitor])
        write! (fn []
                 (persist-state! service
                                 (control/snapshot (:control service))))]
    (if monitor
      (locking monitor (write!))
      (write!))))

(defn- persist-runtime!
  "Persist the current control state plus every live runtime.

  Board and actor transitions are synchronous.  A failure is recorded for
  `status` and returned; it never throws into a board command or a model
  event handler."
  [service]
  (try
    (persist-current-state! service)
    (catch Throwable t
      (let [error {:code :FLEET_STATE_PERSIST_FAILED
                   :message (or (ex-message t) (str t))}]
        (reset! (:persist-error service) error)
        {:ok? false :error error}))))

(defn- unknown-swarm
  [swarm-id]
  {:ok? false
   :error {:code :UNKNOWN_SWARM
           :message "no runtime exists for this swarm"
           :swarm-id swarm-id}})

(defn- actor-missing
  [swarm-id]
  {:ok? false
   :error {:code :SWARM_ACTOR_MISSING
           :message "this swarm was launched without an actor state"
           :swarm-id swarm-id}})

(defn- actor-lock
  "Return SWARM-ID's stable actor monitor, creating it on first use.

  The monitor is separate from the board kernel: the board CAS protects the
  public board, while this monitor serializes the read-modify-write of the
  private actor state that surrounds it."
  [service swarm-id]
  (or (get @(:actor-locks service) swarm-id)
      (get (swap! (:actor-locks service)
                  (fn [locks]
                    (if (contains? locks swarm-id)
                      locks
                      (assoc locks swarm-id
                             (java.util.concurrent.locks.ReentrantLock.)))))
           swarm-id)))

(defn- mutate-actor!
  "Run one actor-state transition under SWARM-ID's actor monitor.

  The runtime is re-read after the monitor is acquired, so two concurrent
  model events cannot both compute from the same stale actor state.  The
  board CAS inside TRANSITION is in-memory; TRANSITION must not perform file,
  model, or tool I/O.  A successful result replaces the runtime's `:actor`
  before the monitor is released.  The caller persists afterwards, outside
  the monitor."
  [service swarm-id transition]
  (if-not (get @(:runtimes service) swarm-id)
    (unknown-swarm swarm-id)
    (let [^java.util.concurrent.locks.ReentrantLock lock
          (actor-lock service swarm-id)]
      (.lock lock)
      (try
        (let [rt (get @(:runtimes service) swarm-id)]
          (cond
            (nil? rt)
            (unknown-swarm swarm-id)

            (nil? (:actor rt))
            (actor-missing swarm-id)

            :else
            (let [actor (:actor rt)
                  result (transition actor)
                  result (if (and (:ok? result)
                                  (contains? actor :recovery))
                           (assoc result :recovery (:recovery actor))
                           result)]
              (if-not (:ok? result)
                result
                (let [[_ after]
                      (swap-vals! (:runtimes service)
                                  (fn [runtimes]
                                    (if (contains? runtimes swarm-id)
                                      (update runtimes swarm-id
                                              assoc :actor result)
                                      runtimes)))]
                  (if (contains? after swarm-id)
                    result
                    (unknown-swarm swarm-id)))))))
        (finally
          (.unlock lock))))))

(defn- runtime-board-events!
  "Persist after a direct board command committed on SWARM-ID's kernel.

  Conflict resolution and worktree program execution mutate the board
  without going through a control transition.  The kernel's post-CAS
  `:on-events` hook is the durable seam for those commands."
  [service swarm-id]
  (when (get @(:runtimes service) swarm-id)
    (persist-runtime! service)))

(defn- runtime-options
  [service swarm]
  {:swarm-id (:swarm-id swarm)
   :slice-id (:slice-id swarm)
   :workspace (:workspace swarm)
   :branch (:branch swarm)
   :slice (get-in (control/snapshot (:control service))
                  [:slices (:slice-id swarm)])
   :clock (:clock service)
   :limits (:limits service)
   :evaluate-factory (:evaluate-factory service)
   :on-board-event! (fn [_events]
                      (runtime-board-events! service (:swarm-id swarm)))})

(defn- runtime-failure!
  "Fail one active swarm after its runtime could not be restored."
  [service swarm error]
  (let [result (control/fail! (:control service) (:swarm-id swarm)
                              {:code :SWARM_RUNTIME_REATTACH_FAILED
                               :message
                               "the restored swarm runtime could not be reattached"
                               :swarm-id (:swarm-id swarm)
                               :error error})]
    (cond
      (not (:ok? result))
      {:ok? false :error (:error result)}

      (false? (:persisted? result))
      (fail :FLEET_STATE_PERSIST_FAILED
            "the reattach failure could not be persisted"
            {:error (:persist-error result)})

      :else
      {:ok? true :failed (:swarm-id swarm)})))

(defn- attach-actors!
  "Restore or initialize the actor state for RUNTIME.

  A persisted actor state keeps its slot identities and private context.  A
  nil state creates fresh slots over the recovered public board; that is a
  legitimate rebirth, not a replay of an old model attempt."
  [service swarm snap runtime now]
  (let [state (:actor snap)
        models (:models service)]
    (if (or state models)
      (actors/restore!
       {:runtime runtime
        :state state
        :models models
        :slot-ids (:slot-ids service)
        :now now})
      {:ok? true :state nil :effects []})))

(defn- reattach-one!
  [service swarm snap launch! now]
  (let [created (if snap
                  (runtime/restore!
                   (merge (runtime-options service swarm)
                          {:board (:board snap)
                           :write-gate-recovery (:write-gate-recovery snap)
                           :recovery (:recovery snap)
                           :status (:status swarm)}))
                  (runtime/start! (runtime-options service swarm)))]
    (if-not (:ok? created)
      {:ok? false
       :error (assoc (:error created) :swarm-id (:swarm-id swarm))}
      (let [runtime (:runtime created)
            actor (attach-actors! service swarm snap runtime now)]
        (if-not (:ok? actor)
          {:ok? false
           :error (assoc (:error actor) :swarm-id (:swarm-id swarm))}
          (let [runtime (assoc runtime
                               :actor actor
                               :actor-recovery (:recovery actor)
                               :status (:status swarm)
                               :runtime-recovered? (boolean snap)
                               :runtime-source (if snap :persisted :recreated))]
            (swap! (:runtimes service) assoc (:swarm-id swarm) runtime)
            (if (and (= :running (:status swarm))
                     (not (:completed? swarm)))
              (let [launched (launch! swarm)]
                (if (:ok? launched)
                  {:ok? true
                   :reattached (:swarm-id swarm)
                   :resumed (:swarm-id swarm)}
                  {:ok? false :error (:error launched)}))
              {:ok? true :reattached (:swarm-id swarm)})))))))

(defn- reattach-runtimes!
  "Rebuild every runtime that has persisted state.

  A persisted board and actor state are restored as-is; only a queued swarm
  with no runtime snapshot (legacy v1 or a missing runtime entry) gets a
  fresh runtime, explicitly marked `:runtime-source :recreated`.  A running
  swarm whose board survived
  is resumed; a joinable completed swarm is not woken."
  [service plan launch!]
  (let [state (control/snapshot (:control service))
        snaps (:runtimes plan)
        now ((:clock service))
        swarms (sort-by :created-seq (vals (:swarms state)))]
    (loop [remaining swarms
           reattached []
           failed []
           errors []
           resumed []]
      (if-let [swarm (first remaining)]
        (let [swarm-id (:swarm-id swarm)
              snap (get snaps swarm-id)
              active? (or (= :queued (:status swarm))
                          (and (= :running (:status swarm))
                               (not (:completed? swarm))))
              worktree? (worktree-present? (:workspace swarm))]
          (if (or snap
                  (and (= :queued (:status swarm)) worktree?))
            (let [result (reattach-one! service swarm snap launch! now)]
              (if (:ok? result)
                (recur (rest remaining)
                       (conj reattached swarm-id)
                       failed
                       errors
                       (cond-> resumed
                         (:resumed result) (conj swarm-id)))
                (let [error (:error result)]
                  (if active?
                    (let [failed-result (runtime-failure! service swarm error)]
                      (if (:ok? failed-result)
                        (recur (rest remaining)
                               reattached
                               (conj failed swarm-id)
                               (conj errors {:swarm-id swarm-id :error error})
                               resumed)
                        failed-result))
                    (recur (rest remaining)
                           reattached
                           failed
                           (conj errors {:swarm-id swarm-id :error error})
                           resumed)))))
            (recur (rest remaining)
                   reattached
                   failed
                   (if active?
                     (conj errors
                           {:swarm-id swarm-id
                            :error
                            {:code :SWARM_RUNTIME_STATE_MISSING
                             :message
                             "no persisted runtime is available for this active swarm"}})
                     errors)
                   resumed)))
        {:ok? true
         :reattached reattached
         :failed failed
         :errors errors
         :resumed resumed}))))

(defn- abort-stale-rebases!
  "Best-effort abort of a rebase the old process left in a worktree.

  The recovered state already cleared `:integrating?`; this restores the
  worktree's branch so the next `join!` can verify branch identity instead of
  failing on a detached HEAD.  A Git error is reported by the later join, not
  hidden here."
  [service plan]
  (doseq [swarm-id (:abort-rebase plan)]
    (when-let [swarm (get-in (:state plan) [:swarms swarm-id])]
      (try
        (git/rebase-abort! (:git service) (:workspace swarm))
        (catch Throwable _ nil)))))

(defn- restore-service!
  "Load, validate, recover, and reattach one persisted fleet.

  A missing file initializes a fresh durable state.  A corrupt, unsupported,
  or mission-mismatched file fails the start; it is never replaced with an
  empty fleet."
  [service launch!]
  (let [loaded (restore/load! (:store service))]
    (case (:status loaded)
      :missing
      (let [saved (persist-current-state! service)]
        (if (:ok? saved)
          (do
            (reset! (:restored service) {:status :fresh})
            {:ok? true :status :fresh})
          (fail :FLEET_STATE_PERSIST_FAILED
                "the fleet state file could not be initialized"
                {:error (:error saved)})))

      :ok
      (let [result (restore/restore-control!
                    service (:snapshot loaded)
                    {:now ((:clock service))
                     :worktree-exists? (fn [swarm]
                                         (worktree-present? (:workspace swarm)))
                     :persist! (fn [state] (persist-state! service state))})]
        (if-not (:ok? result)
          result
          (let [_ (abort-stale-rebases! service result)
                reattached (reattach-runtimes! service result launch!)]
            (if-not (:ok? reattached)
              reattached
              (let [resumed (control/resume-ready! (:control service))]
                (cond
                  (not (:ok? resumed))
                  (fail :FLEET_RESTORE_RESUME_FAILED
                        "the restored fleet could not resume ready swarms"
                        {:error (:error resumed)})

                  (false? (:persisted? resumed))
                  (fail :FLEET_STATE_PERSIST_FAILED
                        "the resumed fleet state could not be persisted"
                        {:error (:persist-error resumed)})

                  :else
                  (let [persisted (persist-runtime! service)]
                    (sync-runtime-status! service)
                    (let [restored-state
                          (assoc (select-keys
                                  result
                                  [:reattach :joinable :resumed
                                   :failed-uncertain :uncertain :missing
                                   :terminal-removed :abort-rebase
                                   :cleared-integrating :changed?
                                   :recovery-errors :legacy?
                                   :runtime-recovery])
                                 :status :restored
                                 :reattached (:reattached reattached)
                                 :runtime-errors (:errors reattached)
                                 :persisted? (:ok? persisted)
                                 :persist-error (:error persisted)
                                 :resume-started
                                 (get-in resumed [:receipt :started]))]
                      (reset! (:restored service) restored-state)
                      (assoc restored-state :ok? true :plan result)))))))))

      (fail :FLEET_STATE_CORRUPT
            "the fleet state file cannot be read; refusing to start with an empty fleet"
            {:error (:error loaded)}))))

(defn start!
  "Create a fleet service for one repository/mission.

  Required: `:mission-id`, `:worktree-root`, and either `:repo` or `:git`.
  `:launch-swarm!` is optional; without it every fork fails explicitly with
  `:SWARM_EXECUTOR_UNAVAILABLE`.

  Other options: `:master-ref` (default \"master\"), `:resolve-conflict!`,
  `:clock`, `:retention-ms`, `:max-rebase-rounds`, `:limits`, `:preflight?`
  (default true), `:models {:main <id> :task <id>}`, `:slot-ids`,
  `:on-join-result!` (a `(fn [service result opts])` callback invoked after
  every `join!`, used by the application to synchronize Owner state),
  `:state-file` (durable EDN path), or `:store` (a
  `hc.compose.fleet.restore/SnapshotStore`).

  With `:state-file` or `:store`, `start!` loads and validates the persisted
  fleet before returning.  A missing file initializes a fresh durable state;
  a corrupt or mismatched file fails the start instead of silently creating
  an empty fleet.  Every persisted worktree board/actor state is recovered
  before it is reattached; in-flight actions become `:uncertain` and
  in-flight model attempts become `:outcome-unknown` with a verification
  note, never a replay.  A running swarm whose runtime survived resumes with
  that evidence; without a recoverable runtime it becomes an explicit
  `:outcome-unknown` failure.  Completed results stay in the FIFO queue for
  `join!`; queued swarms resume when their dependencies are already joined.
  Legacy v1 state has no runtime snapshots, so only its queued swarms (or a
  queued swarm whose v2 runtime entry is missing) get a fresh runtime,
  explicitly marked `:runtime-source :recreated`.

  When `:models` is supplied, the service builds the real per-worktree
  `fleet.actors` state before calling the launcher.  The launcher receives
  `:actor` with real `:model/start` effects.  Without `:models`, no @task
  slots are registered and the launcher must still return explicit failure
  until it can start a real actor loop.

  Returns `{:ok? true :service ...}` or `{:ok? false :error ...}`.  The
  service is constructed but does not fork anything."
  [{:keys [mission-id repo worktree-root master-ref git launch-swarm!
           stop-swarm! resolve-conflict! clock retention-ms max-rebase-rounds
           limits preflight? models slot-ids evaluate-factory state-file store
           on-join-result!]
    :or {master-ref "master"
         preflight? true
         clock #(System/currentTimeMillis)}}]
  (cond
    (not (and (string? mission-id) (not (str/blank? mission-id))))
    (fail :INVALID_MISSION_ID "mission-id must be a non-blank string"
          {:mission-id mission-id})

    (not (and (string? worktree-root) (not (str/blank? worktree-root))))
    (fail :INVALID_WORKTREE_ROOT "worktree-root must be a non-blank path"
          {:worktree-root worktree-root})

    (not (or git repo))
    (fail :GIT_REQUIRED "pass an explicit :git adapter or :repo path" {})

    (not (or (nil? state-file)
             (and (string? state-file) (not (str/blank? state-file)))))
    (fail :INVALID_STATE_FILE "state-file must be a non-blank string"
          {:state-file state-file})

    :else
    (let [git-adapter (or git (git/git-cli repo))
          checked (if preflight?
                    (preflight git-adapter master-ref)
                    {:ok? true})]
      (if-not (:ok? checked)
        checked
        (try
          (let [worktree-root (str (.getAbsoluteFile (io/file worktree-root)))
                retention-ms (or retention-ms state/default-retention-ms)
                max-rebase-rounds (or max-rebase-rounds 16)
                store (or store
                          (when state-file (restore/file-store state-file)))
                persist-error (atom nil)
                runtimes (atom {})
                self (atom nil)
                launcher (or launch-swarm! unavailable-launcher)
                launch!
                (fn [swarm]
                  (let [swarm-id (:swarm-id swarm)
                        existing (get @runtimes swarm-id)
                        created
                        (cond
                          (and existing (:actor existing))
                          {:ok? true :runtime existing}

                          (and existing (:recovered? existing))
                          {:ok? true :runtime existing}

                          :else
                          (runtime/start!
                           {:swarm-id swarm-id
                            :slice-id (:slice-id swarm)
                            :workspace (:workspace swarm)
                            :branch (:branch swarm)
                            :slice (:slice swarm)
                            :clock clock
                            :limits limits
                            :evaluate-factory evaluate-factory
                            :on-board-event!
                            (fn [_events]
                              (when-let [service @self]
                                (runtime-board-events! service swarm-id)))}))]
                    (if-not (:ok? created)
                      {:ok? false :error (:error created)}
                      (let [rt (:runtime created)
                            _ (when-not (identical? rt existing)
                                ;; Publish the runtime before actor creation
                                ;; and launch so an asynchronous model event
                                ;; can find the swarm it belongs to.
                                (swap! runtimes assoc swarm-id
                                       (assoc rt :status
                                              (or (:status swarm) :running))))
                            restored (:actor existing)
                            actor-result
                            (cond
                              (and restored (:state restored))
                              (if models
                                (if-let [service @self]
                                  (mutate-actor!
                                   service swarm-id
                                   (fn [actor]
                                     (actors/wake-all (:state actor))))
                                  {:ok? false
                                   :error {:code :SERVICE_NOT_READY
                                           :message
                                           "the fleet service is not published yet"
                                           :swarm-id swarm-id}})
                                restored)

                              models
                              (let [started
                                    (actors/start!
                                     {:runtime rt
                                      :models models
                                      :slot-ids slot-ids})]
                                (when (:ok? started)
                                  (swap! runtimes update swarm-id
                                         (fn [current]
                                           (assoc (or current rt)
                                                  :actor started))))
                                started)

                              :else
                              {:ok? true :state nil :effects []})]
                        (if-not (:ok? actor-result)
                          (do
                            (swap! runtimes update swarm-id
                                   (fn [current]
                                     (let [current (or current rt)]
                                       (assoc current
                                              :status :failed
                                              :actor-error (:error actor-result)))))
                            (when-let [service @self]
                              (persist-runtime! service))
                            {:ok? false :error (:error actor-result)})
                          (let [actor actor-result
                                launched (try
                                       (launcher (cond-> (assoc swarm
                                                                :runtime rt
                                                                :actor actor)
                                                   @self
                                                   (assoc :service @self)))
                                       (catch Throwable t
                                         {:status :failed
                                          :error {:code :SWARM_LAUNCH_FAILED
                                                  :message (or (.getMessage t) (str t))
                                                  :swarm-id swarm-id}}))]
                        (if (launch-failure? launched)
                          (let [error (if (and (map? launched)
                                               (map? (:error launched)))
                                        (:error launched)
                                        {:code :SWARM_EXECUTOR_UNAVAILABLE
                                         :message "launcher did not start a real swarm worker"
                                         :swarm-id swarm-id
                                         :result launched})]
                            (swap! runtimes update swarm-id
                                   (fn [current]
                                     (let [current (or current rt)]
                                       (assoc current
                                              :status :failed
                                              :launch-error error
                                              :actor (or (:actor current)
                                                         actor)))))
                            (when-let [service @self]
                              (persist-runtime! service))
                            {:ok? false :error error})
                          (let [actor' (if (and (map? launched)
                                                (:actor launched))
                                         (:actor launched)
                                         actor)]
                            (swap! runtimes update swarm-id
                                   (fn [current]
                                     (let [current (or current rt)]
                                       (assoc current
                                              :status :running
                                              :actor
                                              (if (and (map? launched)
                                                       (:actor launched))
                                                (:actor launched)
                                                (or (:actor current)
                                                    actor'))))))
                            (when-let [service @self]
                              (persist-runtime! service))
                            {:ok? true}))))))))
                persist-service {:mission-id mission-id
                                 :master-ref master-ref
                                 :store store
                                 :runtimes runtimes
                                 :persist-error persist-error}
                control (control/new-control
                         {:mission-id mission-id
                          :worktree-root worktree-root
                          :repo repo
                          :master-ref master-ref
                          :git git-adapter
                          :launch-swarm! launch!
                          :resolve-conflict! resolve-conflict!
                          :clock clock
                          :retention-ms retention-ms
                          :max-rebase-rounds max-rebase-rounds
                          :persist! (fn [state]
                                      (persist-state! persist-service state))})
                service {:mission-id mission-id
                         :repo repo
                         :worktree-root worktree-root
                         :master-ref master-ref
                         :state-file state-file
                         :store store
                         :git git-adapter
                         :control control
                         :runtimes runtimes
                         :launcher launcher
                         :launch-runtime! launch!
                         :stop-swarm! stop-swarm!
                         :runtime (fn [swarm-id] (runtime @self swarm-id))
                         :board-snapshot (fn [swarm-id] (board-snapshot @self swarm-id))
                         :wake-actors! (fn [swarm-id] (wake-actors! @self swarm-id))
                         :accept-actor-event!
                         (fn [swarm-id attempt-id event]
                           (accept-actor-event! @self swarm-id attempt-id event))
                         :continue-actor-after-tool!
                         (fn [swarm-id attempt-id event result]
                           (continue-actor-after-tool! @self swarm-id attempt-id event result))
                         :claim-tool-call!
                         (fn [swarm-id attempt-id event]
                           (claim-tool-call! @self swarm-id attempt-id event))
                         :actor-state (fn [swarm-id] (actor-state @self swarm-id))
                         :worktree-runtimes (fn [] (worktree-runtimes @self))
                         :runtime-for-workspace (fn [workspace]
                                                  (runtime-for-workspace @self workspace))
                         :gc! (fn [service] (gc! service))
                         :on-join-result! on-join-result!
                         :models models
                         :slot-ids slot-ids
                         :limits limits
                         :evaluate-factory evaluate-factory
                         :clock clock
                         :closed? (atom false)
                         :actor-locks (atom {})
                         :restored (atom nil)
                         :persist-error persist-error}
                _ (reset! self service)
                restored (if store
                           (restore-service! service launch!)
                           {:ok? true :status :disabled})]
            (if (:ok? restored)
              {:ok? true :service service :restored restored}
              (do
                (doseq [[swarm-id rt] @runtimes]
                  (stop-named-swarm! service swarm-id {:reason :restore-failed})
                  (runtime/stop! rt))
                restored)))
          (catch Throwable t
            (fail :FLEET_START_FAILED
                  "fleet control could not be constructed"
                  {:message (or (.getMessage t) (str t))})))))))

(defn- sync-runtime-status!
  [service]
  (let [statuses (into {} (map (juxt :swarm-id :status))
                       (control/fleet-rows (:control service)))]
    (swap! (:runtimes service)
           (fn [runtimes]
             (into {}
                   (map (fn [[swarm-id rt]]
                          [swarm-id (assoc rt :status
                                           (get statuses swarm-id
                                                (:status rt)))]))
                   runtimes)))))

(defn fleet-rows
  "Authoritative fleet rows overlaid with real per-swarm board/W/token data.
  No row exists unless the fleet admitted a slice and the launcher created a
  runtime.  Joined rows keep the full report."
  [service]
  (sync-runtime-status! service)
  (mapv (fn [row]
          (if-let [rt (get @(:runtimes service) (:swarm-id row))]
            (merge row (runtime/observation rt))
            row))
        (control/fleet-rows (:control service))))

(def fleet-row-fields
  "Bounded Steward-facing row fields from root AGENTS.md §2.2."
  [:swarm-id :slice-id :worktree :status
   :root-ready :root-waiting :root-done
   :queued-count :last-event :master-version])

(def max-observation-events
  "The observation carries only the event-log tail, matching the snapshot."
  1000)

(defn- bounded-events
  [events]
  (vec (take-last max-observation-events events)))

(defn- summary-row
  [row]
  (cond-> (select-keys row fleet-row-fields)
    (= :joined (:status row))
    (assoc :report (:report row))))

(defn observation
  "The `:fleet` observation for Pair Hint and application snapshots."
  [service]
  (let [state (control/snapshot (:control service))]
    {:mission-id (:mission-id service)
     :fleet-version (:version state)
     :master-version (:master-version state)
     :swarms (fleet-rows service)
     :events (bounded-events (:events state))}))

(defn observation-summary
  "Bounded Steward observation: no board, task, action, or agent detail.

  Joined rows keep the full report.  The event log is a bounded tail."
  [service]
  (let [observation (observation service)]
    (-> observation
        (update :swarms #(mapv summary-row %))
        (update :events bounded-events))))

(defn snapshot-tables
  "Normalized tables for the client snapshot.

  Rows live under `:swarm/by-id` with their own nested `:board`.  Token slots
  and write gates are keyed by swarm id.  The task/action/agent tables are not
  flattened here because per-board IDs are only unique inside one worktree;
  the client reads them from the nested board."
  [service]
  (let [rows (fleet-rows service)
        by-id (into {} (map (juxt :swarm-id identity)) rows)
        token-slots (into {}
                          (keep (fn [row]
                                  (when-let [slot (:token-slot row)]
                                    [(:swarm-id row) slot])))
                          rows)
        write-gates (into {}
                          (keep (fn [row]
                                  (when-let [gate (:write-gate row)]
                                    [(:swarm-id row) gate])))
                          rows)
        state (control/snapshot (:control service))]
    {:swarm/by-id by-id
     ;; Fleet mode must not leave a stale single-board projection behind.
     :task/by-id {}
     :action/by-id {}
     :agent/by-id {}
     :board/version nil
     :board/version-by-swarm
     (into {} (keep (fn [row]
                      (when-let [version (:board-version row)]
                        [(:swarm-id row) version])))
           rows)
     :token-slot/by-id token-slots
     :write-gate/by-id write-gates
     :fleet/version (:version state)
     :fleet/master-version (:master-version state)
     :fleet/events (bounded-events (:events state))}))

(defn fork
  "Spec-shaped fork: a compact `[{:swarm-id :slice-id :workspace}]` vector.

  A resource shortage returns `:INSUFFICIENT_WORKTREES`; other failures
  throw `ex-info` with the complete `fork!` result."
  [service slices]
  (let [result (control/fork (:control service) slices)]
    (sync-runtime-status! service)
    result))

(defn fork!
  [service slices]
  (let [result (control/fork! (:control service) slices)]
    (sync-runtime-status! service)
    result))

(defn complete!
  [service swarm-id report opts]
  (let [result (control/complete! (:control service) swarm-id report opts)]
    (sync-runtime-status! service)
    result))

(defn join!
  ([service] (join! service {}))
  ([service opts]
   (let [result (control/join! (:control service) opts)]
     (sync-runtime-status! service)
     ;; The callback is an application observation seam (for example, an
     ;; Owner update): it runs after the service state reflects the join and
     ;; never changes the joined result.  A callback failure must not turn a
     ;; real Git fast-forward into a reported join failure.
     (when-let [on-result! (:on-join-result! service)]
       (try
         (on-result! service result opts)
         (catch Throwable _ nil)))
     result)))

(defn- stop-named-swarm!
  "Stop only the named swarm's execution resource.  The application supplies
  this hook; without it, control state still changes and the launcher owns
  termination.  No sibling is addressed."
  [service swarm-id reason]
  (when-let [stop! (:stop-swarm! service)]
    (try
      (stop! {:swarm-id swarm-id
              :runtime (get @(:runtimes service) swarm-id)
              :reason reason})
      (catch Throwable t
        {:ok? false
         :error {:code :SWARM_STOP_FAILED
                 :message (or (.getMessage t) (str t))
                 :swarm-id swarm-id}}))))

(defn abandon!
  [service swarm-id reason]
  (let [result (control/abandon! (:control service) swarm-id reason)]
    (when (:ok? result)
      (stop-named-swarm! service swarm-id reason))
    (sync-runtime-status! service)
    result))

(defn- normalize-workspace
  "Return WORKSPACE as an absolute path string, or nil."
  [workspace]
  (when (some? workspace)
    (str (.getAbsoluteFile (io/file (str workspace))))))

(defn- swarm-id-for-workspace
  "Find the swarm that owns WORKSPACE, or nil.

  The live runtime selector answers first.  The control state is the
  fallback because a terminal swarm may no longer have a runtime while its
  registered `:workspace` is still authoritative."
  [service workspace]
  (or (some-> (runtime-for-workspace service workspace) :swarm-id)
      (let [wanted (normalize-workspace workspace)]
        (some (fn [[swarm-id swarm]]
                (when (= wanted (normalize-workspace (:workspace swarm)))
                  swarm-id))
              (:swarms (control/snapshot (:control service)))))))

(defn- terminated-holder-board
  "Return BOARD with the current token holder logically terminated.

  Pure.  The holder's claims, actions, ordinary ownership, park registration,
  and token slot are cleared in the same immutable value, so the published
  board still satisfies the board invariants.  The identity row remains with
  `:life-state :terminated` and one `:holder-terminated` event records why.
  Park registrations are cleared because the whole worktree runtime is being
  discarded; a parked sibling must not survive it."
  [board reason]
  (let [holder (get-in board [:token-slot :holder-id])]
    (if (nil? holder)
      board
      (let [version (inc (long (or (:board-version board) 0)))
            event-seq (inc (long (or (:event-seq board) 0)))
            removed-claim-ids (into #{}
                                    (keep (fn [[claim-id claim]]
                                            (when (= holder (:agent-id claim))
                                              claim-id)))
                                    (:claims board))
            claims (into {}
                         (remove (fn [[_ claim]]
                                   (= holder (:agent-id claim))))
                         (:claims board))
            actions (into {}
                          (remove (fn [[_ action]]
                                    (= holder (:agent-id action))))
                          (:actions board))
            tasks (into {}
                        (map (fn [[task-id task]]
                               [task-id
                                (cond-> task
                                  (contains? removed-claim-ids (:claim-id task))
                                  (assoc :claim-id nil)

                                  (= holder (:owner-id task))
                                  (assoc :owner-id nil))]))
                        (:tasks board))
            event {:type :holder-terminated
                   :agent-id holder
                   :reason reason
                   :event-seq event-seq
                   :at-version version}]
        (swarm/derive
         (-> board
             (assoc :claims claims
                    :actions actions
                    :tasks tasks
                    :board-version version
                    :event-seq event-seq)
             (assoc :waiters {})
             (assoc-in [:token-slot :holder-id] nil)
             (assoc-in [:token-slot :term-id]
                       (inc (long (or (get-in board [:token-slot :term-id]) 0))))
             (assoc-in [:token-slot :state] :free)
             (update-in [:actors holder]
                        #(-> %
                             (assoc :role :normal)
                             (assoc :life-state :terminated)
                             (assoc :active-action-id nil)
                             (assoc :dead-at-version version)))
             (update :events conj event)))))))

(defn- terminate-holder!
  "Mark RUNTIME's current token holder terminated with a board compare-and-set.

  A concurrent board command is retried against the newer board instead of
  being overwritten.  The function is a no-op when no holder exists."
  [runtime reason]
  (if-not (:kernel runtime)
    {:ok? true :terminated? false}
    (loop [attempt 0]
      (let [kernel (:kernel runtime)
            before (swarm/snapshot kernel)
            holder (get-in before [:token-slot :holder-id])]
        (cond
          (nil? holder)
          {:ok? true :terminated? false}

          (swarm/replace-board! kernel before
                                (terminated-holder-board before reason))
          {:ok? true
           :terminated? true
           :holder-id holder
           :board (swarm/snapshot kernel)}

          (< attempt 32)
          (recur (inc attempt))

          :else
          {:ok? false
           :error {:code :BOARD_TERMINATION_CONFLICT
                   :message
                   "the swarm board kept changing; the holder was not marked terminated"
                   :swarm-id (:swarm-id runtime)}})))))

(defn abandon-by-workspace!
  "Abandon the swarm that owns WORKSPACE after an explicit REASON.

  This is the holder-side selector for a request that names only its
  worktree.  A blank reason is refused before any state changes.  The swarm
  is located through `runtime-for-workspace` or the control state's
  normalized `:workspace`; an unknown worktree is `:SWARM_NOT_FOUND`.

  A real swarm runs the normal `abandon!` transition, then stops its live
  worktree runtime and marks the current token holder `:terminated` on the
  board.  The worktree itself is retained for the configured retention
  window; abandonment is not deletion."
  [service workspace reason]
  (let [reason-text (some-> reason str)]
    (cond
      (str/blank? reason-text)
      (fail :ABANDON_REASON_REQUIRED
            "abandon requires a non-blank :reason string"
            {:workspace (some-> workspace str)})

      :else
      (if-let [swarm-id (swarm-id-for-workspace service workspace)]
        (let [result (abandon! service swarm-id reason)]
          (if-not (:ok? result)
            (assoc result :kind :abandon :swarm-id swarm-id)
            (let [rt (get @(:runtimes service) swarm-id)
                  termination (when rt (terminate-holder! rt reason))]
              (when rt
                (runtime/stop! rt))
              (persist-runtime! service)
              (cond-> {:ok? true
                       :kind :abandon
                       :swarm-id swarm-id
                       :status :abandoned
                       :reason reason}
                (false? (:ok? termination))
                (assoc :termination-error (:error termination))))))
        (fail :SWARM_NOT_FOUND
              "no swarm owns this worktree"
              {:workspace (some-> workspace str)})))))

(defn fail!
  [service swarm-id error]
  (let [result (control/fail! (:control service) swarm-id error)]
    (when (:ok? result)
      (stop-named-swarm! service swarm-id {:error error}))
    (sync-runtime-status! service)
    result))

(defn accept-actor-event!
  "Feed one model event into the named swarm's real @task actor state.

  The runtime board is synchronized by `fleet.actors`.  The caller remains
  responsible for dispatching any returned effects.  An unknown swarm or a
  swarm launched without `:models` is an explicit error.  The actor
  read-modify-write is serialized per swarm; persistence happens after the
  monitor is released."
  [service swarm-id attempt-id event]
  (let [result (mutate-actor!
                service swarm-id
                (fn [actor]
                  (actors/accept-event (:state actor) attempt-id event)))]
    (when (:ok? result)
      ;; Chunks are partial model output.  The attempt is fixed as
      ;; `:outcome-unknown` after a crash; persisting every chunk would
      ;; write a redundant transcript without making the outcome
      ;; confirmable.
      (when-not (= :chunk (:event event))
        (persist-runtime! service)))
    result))

(defn wake-actors!
  "Wake idle live @task slots for SWARM-ID and return real model effects.

  Call after `accept-actor-event!` completes a turn.  A slot that is still
  running is not woken again.  Concurrent wakes are serialized, so an idle
  slot produces one start effect, not one per caller."
  [service swarm-id]
  (let [result (mutate-actor!
                service swarm-id
                (fn [actor]
                  (let [woken (actors/wake-all (:state actor))]
                    (if (:ok? woken)
                      (update woken :effects
                              (fn [effects]
                                (mapv #(assoc % :swarm-id swarm-id)
                                      effects)))
                      woken))))]
    (when (:ok? result)
      (persist-runtime! service))
    result))

(defn continue-actor-after-tool!
  "Append a tool result to the named swarm's actor and wake the next turn.

  TOOL-RESULT is the executor value.  The actor state and runtime board are
  updated by `fleet.actors`; returned effects are real `:model/start` maps.
  The transition is serialized per swarm, so a stale or replayed result
  cannot clear a newer attempt or start a second turn."
  [service swarm-id attempt-id event tool-result]
  (let [result (mutate-actor!
                service swarm-id
                (fn [actor]
                  (actors/continue-after-tool!
                   (:state actor) attempt-id event tool-result)))]
    (when (:ok? result)
      (persist-runtime! service))
    result))

(defn claim-tool-call!
  "Reserve one external tool call before its side effect.

  The reservation is serialized with every other actor mutation for the
  swarm.  A second claim for the same attempt is rejected instead of running
  the tool twice.  The caller executes the tool outside the actor monitor and
  then calls `continue-actor-after-tool!`."
  [service swarm-id attempt-id event]
  (let [result (mutate-actor!
                service swarm-id
                (fn [actor]
                  (actors/claim-tool-call
                   (:state actor) attempt-id event)))]
    (when (:ok? result)
      (persist-runtime! service))
    result))

(defn actor-state
  "Return the real @task actor state for SWARM-ID, or nil."
  [service swarm-id]
  (:actor (get @(:runtimes service) swarm-id)))

(defn runtime
  "Return the isolated runtime for SWARM-ID, or nil.

  The returned map contains `:workspace`, `:kernel`, `:write-gate`,
  `:workspace-runtime`, `:board`, `:token-slot`, and `:actor`.  Use
  `:workspace-runtime` for `workspace/run-program!`; never fall back to the
  session root."
  [service swarm-id]
  (get @(:runtimes service) swarm-id))

(defn board-snapshot
  "Return the real per-swarm board for SWARM-ID, or nil."
  [service swarm-id]
  (when-let [rt (get @(:runtimes service) swarm-id)]
    (runtime/board rt)))

(defn runtime-for-workspace
  "Return the runtime whose worktree is WORKSPACE, or nil.

  This is the executor-side selector for a request that names only its
  workspace.  It never returns a master/session runtime."
  [service workspace]
  (some (fn [[_ rt]]
          (when (= (str workspace) (str (:workspace rt)))
            rt))
        @(:runtimes service)))

(defn worktree-runtimes
  "Return `tenure-id -> runtime` for `hc.compose.mission.worktree`.

  The tenure id is the one stored in actor state, or `fleet-<swarm-id>` when
  actors are not configured.  Pass this map directly to
  `hc.compose.mission.worktree/execute-program!`."
  [service]
  (into {}
        (map (fn [[swarm-id rt]]
               [(or (get-in rt [:actor :state :tenure-id])
                    (str "fleet-" swarm-id))
                rt]))
        @(:runtimes service)))

(defn gc!
  "Run one retention sweep.

  A stopped service refuses the sweep instead of letting a late timer or
  command remove a worktree after application shutdown began."
  [service]
  (if @(:closed? service)
    (fail :FLEET_CLOSED
          "the fleet service is stopped"
          {:mission-id (:mission-id service)})
    (let [result (control/gc! (:control service))]
      (let [state (control/snapshot (:control service))
            removed (into (set (:removed result))
                          (keep (fn [[swarm-id swarm]]
                                  (when (:worktree-removed? swarm)
                                    swarm-id)))
                          (:swarms state))]
        (doseq [swarm-id removed]
          (swap! (:runtimes service) dissoc swarm-id)))
      (sync-runtime-status! service)
      (persist-runtime! service)
      result)))

(defn state
  [service]
  (control/snapshot (:control service)))

(defn status
  [service]
  (let [state (control/snapshot (:control service))]
    {:available? true
     :closed? (boolean @(:closed? service))
     :mission-id (:mission-id service)
     :master-ref (:master-ref service)
     :fleet-version (:version state)
     :master-version (:master-version state)
     :swarm-count (count (:swarms state))
     :runtime-count (count @(:runtimes service))
     :actor-count (count (filter :actor (vals @(:runtimes service))))
     :runtime-recovered
     (count (filter :runtime-recovered? (vals @(:runtimes service))))
     :runtime-recreated
     (count (filter #(= :recreated (:runtime-source %))
                    (vals @(:runtimes service))))
     :restored @(:restored service)
     :persist-error @(:persist-error service)}))

(defn stop!
  [service]
  (reset! (:closed? service) true)
  (let [saved (persist-current-state! service)]
    (doseq [[swarm-id rt] @(:runtimes service)]
      (stop-named-swarm! service swarm-id {:reason :service-stop})
      (runtime/stop! rt))
    {:ok? true
     :mission-id (:mission-id service)
     :persisted? (:ok? saved)
     :persist-error (:error saved)}))
