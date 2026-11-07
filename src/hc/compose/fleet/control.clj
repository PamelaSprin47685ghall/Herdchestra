(ns hc.compose.fleet.control
  "Effectful fork/join control for disjoint worktree swarms.

  The control object owns one immutable fleet state atom, a monitor for
  blocking `join!`, a fork lock, and a master integration lock.  It performs
  the real Git worktree and fast-forward operations through an explicit
  `hc.compose.fleet.git/GitPort`.  A Git failure is returned as data and the
  swarm becomes `:failed`; no result is fabricated.

  A verified fast-forward is persisted before it is published.  A failed
  write leaves the result pending under `:join-recovery`; no dependent
  starts, and a later join retries only the write.

  `join!` blocks only while the FIFO queue is empty and at least one swarm is
  still running.  It never cancels a sibling.  An unresolvable rebase
  conflict abandons only the named swarm, then join continues with the next
  queued result.  `abandon!` and `fail!` also name one swarm; unrelated
  swarms keep running."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [hc.compose.fleet.git :as git]
            [hc.compose.fleet.state :as state])
  (:import [java.util.concurrent.locks ReentrantLock]))

(defrecord FleetControl
    [state git launch-swarm! resolve-conflict! clock monitor fork-lock
     integration-lock allocating config])

(defn- now
  [control]
  ((:clock control)))

(defn new-control
  "Build a fleet control.

  Required:
  - `:worktree-root` directory for per-swarm worktrees
  - `:launch-swarm!` function called with
    `{:swarm-id :slice-id :workspace :branch :slice}`

  Git is explicit: pass `:git` (a `GitPort`) or `:repo` for the real
  `git-cli` adapter.  `:resolve-conflict!` is called with
  `{:swarm-id :slice-id :workspace :master-ref :round :conflict}` and must
  return `:resolved`/true to continue or `:abandon`/false to abandon.  After
  `:resolved`, the control stages the worktree with `git add -A` before
  continuing the rebase; a resolver does not have to stage.  There is no
  default conflict resolver; an absent resolver abandons only that swarm.

  `:persist!` is an optional function of one immutable fleet state.  It must
  return `{:ok? true}` or `{:ok? false :error error}`.  A successful
  transition is persisted before it becomes visible; a failed publish leaves
  the live state unchanged so a fork can roll back cleanly."
  [{:keys [mission-id worktree-root repo master-ref git launch-swarm!
           resolve-conflict! clock max-rebase-rounds retention-ms persist!]
    :or {master-ref "master"
         max-rebase-rounds 16
         retention-ms state/default-retention-ms}}]
  (when-not (and (string? worktree-root) (not (str/blank? worktree-root)))
    (throw (ex-info "fleet: worktree-root must be a non-blank string"
                    {:error :INVALID_WORKTREE_ROOT
                     :worktree-root worktree-root})))
  (when-not (fn? launch-swarm!)
    (throw (ex-info "fleet: launch-swarm! is required; the control will not pretend a swarm started"
                    {:error :LAUNCHER_REQUIRED})))
  (when-not (or git repo)
    (throw (ex-info "fleet: pass an explicit :git adapter or :repo path"
                    {:error :GIT_REQUIRED})))
  (when-not (and (integer? max-rebase-rounds) (pos? max-rebase-rounds))
    (throw (ex-info "fleet: max-rebase-rounds must be a positive integer"
                    {:error :INVALID_MAX_REBASE_ROUNDS
                     :max-rebase-rounds max-rebase-rounds})))
  (when-not (and (integer? retention-ms) (not (neg? retention-ms)))
    (throw (ex-info "fleet: retention-ms must be a non-negative integer"
                    {:error :INVALID_RETENTION
                     :retention-ms retention-ms})))
  (let [worktree-root (str (.getAbsoluteFile (io/file worktree-root)))
        git (or git (git/git-cli repo))
        control-state (state/new-state {:mission-id mission-id
                                        :worktree-root worktree-root
                                        :master-ref master-ref
                                        :retention-ms retention-ms})]
    (->FleetControl (atom control-state)
                    git
                    launch-swarm!
                    resolve-conflict!
                    (or clock #(System/currentTimeMillis))
                    (Object.)
                    (ReentrantLock.)
                    (ReentrantLock.)
                    (atom false)
                    {:mission-id mission-id
                     :worktree-root worktree-root
                     :master-ref master-ref
                     :max-rebase-rounds max-rebase-rounds
                     :retention-ms retention-ms
                     :persist! persist!})))

(defn snapshot
  "Return the current immutable fleet state."
  [control]
  @(:state control))

(defn fleet-rows
  "Steward-facing rows; joined rows include the full report."
  [control]
  (state/fleet-rows @(:state control)))

(defn- fork-shape
  [swarm]
  {:swarm-id (:swarm-id swarm)
   :slice-id (:slice-id swarm)
   :workspace (:workspace swarm)
   :branch (:branch swarm)
   :status (:status swarm)})

(defn- persist-state!
  "Persist one immutable state through the configured callback.

  Persistence is synchronous: a caller must not observe a transition that
  the restart path could not reconstruct.  A missing callback means this
  control was built for an in-memory caller or a test."
  [control state]
  (if-let [persist! (get-in control [:config :persist!])]
    (try
      (let [result (persist! state)]
        (cond
          (true? result) {:ok? true}
          (and (map? result) (true? (:ok? result))) {:ok? true}
          (and (map? result) (= :ok (:status result))) {:ok? true}
          :else
          {:ok? false
           :error (or (and (map? result) (:error result))
                      {:code :FLEET_STATE_PERSIST_FAILED
                       :message "fleet state persistence failed"})}))
      (catch Throwable t
        {:ok? false
         :error {:code :FLEET_STATE_PERSIST_FAILED
                 :message (or (.getMessage t) (str t))}}))
    {:ok? true}))

(defn- update-state!
  "Apply one pure transition under the monitor and wake join waiters.

  The transition becomes the live state after persistence is attempted.
  When persistence fails the result carries `:persisted? false` plus
  `:persist-error`: the in-memory fleet keeps serving instead of deadlocking
  on a stale `:integrating?` flag.  Fork publication is stricter and calls
  `persist-state!` directly before publishing.  Join publication is stricter
  too: `finalize-join!` persists the joined state before publishing and
  keeps a failed write as `:join-recovery`."
  [control transition-fn]
  (locking (:monitor control)
    (let [result (transition-fn @(:state control))]
      (if-not (:ok? result)
        result
        (if (= (:state result) @(:state control))
          (assoc result :persisted? true)
          (let [persisted (persist-state! control (:state result))]
            (reset! (:state control) (:state result))
            (.notifyAll ^Object (:monitor control))
            (if (:ok? persisted)
              (assoc result :persisted? true)
              (assoc result
                     :persisted? false
                     :persist-error (:error persisted)))))))))

(defn- clear-integrating!
  [control swarm-id]
  (update-state! control #(state/set-integrating % swarm-id false)))

(defn- launch-one!
  "Call the launcher for one swarm.  A synchronous failure closes the attempt
  by marking that swarm `:failed`; it does not touch any sibling."
  [control swarm]
  (let [failure!
        (fn [failure]
          (update-state! control
                         #(state/fail-swarm % (:swarm-id swarm) failure
                                            {:now (now control)}))
          {:ok? false :swarm-id (:swarm-id swarm) :error failure})]
    (try
      (let [result ((:launch-swarm! control)
                    {:swarm-id (:swarm-id swarm)
                     :slice-id (:slice-id swarm)
                     :workspace (:workspace swarm)
                     :branch (:branch swarm)
                     :slice (get-in @(:state control) [:slices (:slice-id swarm)])})]
        (if (or (false? result)
                (and (map? result)
                     (or (= :failed (:status result))
                         (some? (:error result)))))
          (failure! (if (and (map? result) (map? (:error result)))
                      (assoc (:error result)
                             :swarm-id (:swarm-id swarm)
                             :result result)
                      {:code :SWARM_LAUNCH_REJECTED
                       :message "launch-swarm! returned a failure value"
                       :swarm-id (:swarm-id swarm)
                       :result result}))
          (let [status (get-in @(:state control)
                               [:swarms (:swarm-id swarm) :status])]
            (if (= :running status)
              {:ok? true :swarm-id (:swarm-id swarm)}
              (failure! {:code :SWARM_LAUNCH_CLOSED
                         :message "launch-swarm! returned true after the swarm ended"
                         :swarm-id (:swarm-id swarm)
                         :status status})))))
      (catch Throwable t
        (failure! {:code :SWARM_LAUNCH_FAILED
                   :message (or (.getMessage t) (str t))
                   :swarm-id (:swarm-id swarm)})))))

(defn- launch-many!
  [control swarm-ids]
  (let [results (mapv (fn [swarm-id]
                        (if-let [swarm (get-in @(:state control) [:swarms swarm-id])]
                          (launch-one! control swarm)
                          {:ok? false
                           :swarm-id swarm-id
                           :error {:code :UNKNOWN_SWARM :swarm-id swarm-id}}))
                      swarm-ids)]
    {:launched (mapv :swarm-id (filter :ok? results))
     :failed (vec (remove :ok? results))}))

(defn- cleanup-created!
  "Rollback for worktrees created by a fork that never published.

  The branch was created by `create-worktree!` with `-b`, so this rollback
  removes both the worktree and that branch.  Normal retention GC does not
  delete branches.  An adapter exception becomes reported failure data."
  [control created]
  (mapv (fn [swarm]
          {:swarm-id (:swarm-id swarm)
           :worktree (try
                       (git/remove-worktree! (:git control) (:workspace swarm))
                       (catch Throwable t
                         {:status :failed
                          :code :CLEANUP_EXCEPTION
                          :message (or (.getMessage t) (str t))}))
           :branch (try
                     (git/delete-branch! (:git control) (:branch swarm))
                     (catch Throwable t
                       {:status :failed
                        :code :CLEANUP_EXCEPTION
                        :message (or (.getMessage t) (str t))}))})
        (reverse created)))

(defn- cleanup-ok?
  [entry]
  (and (= :ok (get-in entry [:worktree :status]))
       (= :ok (get-in entry [:branch :status]))))

(defn- create-worktrees!
  [control planned base]
  (let [created (atom [])]
    (try
      (doseq [swarm planned]
        (let [parent (.getParentFile (io/file (:workspace swarm)))]
          (when parent
            (.mkdirs parent)))
        (let [result (git/create-worktree! (:git control)
                                           {:workspace (:workspace swarm)
                                            :branch (:branch swarm)
                                            :base base})]
          (if (= :ok (:status result))
            (swap! created conj swarm)
            (throw (ex-info "fleet: worktree creation failed"
                            {:swarm swarm :git result})))))
      {:ok? true :created @created}
      (catch Throwable t
        (let [data (ex-data t)
              created @created
              cleanup (cleanup-created! control created)]
          {:ok? false
           :created created
           :failed-swarm (:swarm data)
           :git (or (:git data)
                    {:status :failed
                     :code :ADAPTER_EXCEPTION
                     :message (or (.getMessage t) (str t))})
           :cleanup cleanup
           :cleanup-failed (vec (remove cleanup-ok? cleanup))})))))

(defn- publish-fork!
  "Re-validate the fork under the monitor, mark worktrees ready, start ready
  swarms, and publish one state.  The fork lock makes the ids stable."
  [control slices swarm-ids]
  (locking (:monitor control)
    (let [fresh (state/fork @(:state control) slices)]
      (cond
        (not (:ok? fresh))
        {:ok? false :error (:error fresh)}

        (not= (vec (get-in fresh [:receipt :swarm-ids])) (vec swarm-ids))
        {:ok? false
         :error {:code :FORK_RACE
                 :message "fleet changed while worktrees were created"
                 :planned swarm-ids
                 :actual (get-in fresh [:receipt :swarm-ids])}}

        :else
        (let [ready (state/mark-worktree-ready (:state fresh) swarm-ids)]
          (if-not (:ok? ready)
            {:ok? false :error (:error ready)}
            (let [started (state/start-ready (:state ready))]
              (if-not (:ok? started)
                {:ok? false :error (:error started)}
                (let [persisted (persist-state! control (:state started))]
                  (if-not (:ok? persisted)
                    {:ok? false
                     :error (assoc (:error persisted) :stage :fork-publish)}
                    (do
                      (reset! (:state control) (:state started))
                      (.notifyAll ^Object (:monitor control))
                      {:ok? true
                       :state (:state started)
                       :started (get-in started [:receipt :started])})))))))))))

(defn fork!
  "Admit SLICES, create one real worktree per slice, then launch ready swarms.

  Returns `{:ok? true :state state :swarms [...]}` on full success.  A
  worktree failure leaves the fleet state unchanged and reports the cleanup
  of worktrees created during this call.  A launcher failure keeps the
  already-created fleet and marks only that swarm failed.

  The control's allocation flag is held for the whole call (worktree
  creation, publication, and launch) so a concurrent `join!` waits rather
  than concluding `:none` before the new swarms exist."
  [control slices]
  (let [fork-lock (:fork-lock control)]
    (.lock fork-lock)
    (locking (:monitor control)
      (reset! (:allocating control) true))
    (try
      (let [before @(:state control)
            plan (state/fork before slices)]
        (if-not (:ok? plan)
          {:ok? false :state @(:state control) :error (:error plan)}
          (let [swarm-ids (vec (get-in plan [:receipt :swarm-ids]))
                planned (mapv #(get-in plan [:state :swarms %]) swarm-ids)
                creation (create-worktrees! control planned (:master-ref before))]
            (if-not (:ok? creation)
              {:ok? false
               :state @(:state control)
               :error {:code (if (= :INSUFFICIENT_WORKTREES (:code (:git creation)))
                              :INSUFFICIENT_WORKTREES
                              :WORKTREE_CREATION_FAILED)
                       :message "fleet: worktree creation failed"
                       :failed-swarm (when-let [swarm (:failed-swarm creation)]
                                       (fork-shape swarm))
                       :git (:git creation)
                       :created (mapv fork-shape (:created creation))
                       :cleanup (:cleanup creation)
                       :cleanup-failed (:cleanup-failed creation)}}
              (let [published (publish-fork! control slices swarm-ids)]
                (if-not (:ok? published)
                  (let [cleanup (cleanup-created! control (:created creation))]
                    {:ok? false
                     :state @(:state control)
                     :error (assoc (:error published)
                                   :created (mapv fork-shape (:created creation))
                                   :cleanup cleanup
                                   :cleanup-failed (vec (remove cleanup-ok? cleanup)))})
                  (let [launch (launch-many! control (:started published))
                        swarms (mapv #(fork-shape (get-in @(:state control) [:swarms %]))
                                     swarm-ids)]
                    (if (seq (:failed launch))
                      {:ok? false
                       :state @(:state control)
                       :swarms swarms
                       :launched (:launched launch)
                       :error {:code :SWARM_LAUNCH_FAILED
                               :failed (:failed launch)}}
                      {:ok? true
                       :state @(:state control)
                       :swarms swarms}))))))))
      (finally
        (locking (:monitor control)
          (reset! (:allocating control) false)
          (.notifyAll ^Object (:monitor control)))
        (.unlock fork-lock)))))

(defn complete!
  "Record a swarm's full report and put its result in the FIFO queue.

  The caller supplies `:board-version`; it is copied into the
  `:result-queued` event.  No Git action happens here."
  [control swarm-id report opts]
  (update-state! control #(state/complete-swarm % swarm-id report opts)))

(defn abandon!
  "Abandon exactly SWARM-ID.  No sibling is cancelled."
  [control swarm-id reason]
  (update-state! control #(state/abandon-swarm % swarm-id reason
                                               {:now (now control)})))

(defn fail!
  "Fail exactly SWARM-ID after an explicit failure.  No sibling is cancelled."
  [control swarm-id error]
  (update-state! control #(state/fail-swarm % swarm-id error
                                            {:now (now control)})))

(defn- resolved-decision?
  [decision]
  (or (true? decision)
      (= :resolved decision)
      (= :resolved (:status decision))))

(defn- abandon-decision?
  [decision]
  (or (false? decision)
      (nil? decision)
      (= :abandon decision)
      (= :abandoned decision)
      (= :abandoned (:status decision))))

(defn- conflict-decision
  [control swarm round conflict]
  (if-let [resolver (:resolve-conflict! control)]
    (try
      (resolver {:swarm-id (:swarm-id swarm)
                 :slice-id (:slice-id swarm)
                 :workspace (:workspace swarm)
                 :master-ref (:master-ref @(:state control))
                 :round round
                 :conflict conflict})
      (catch Throwable t
        {:status :abandoned
         :reason {:code :CONFLICT_RESOLVER_FAILED
                  :message (or (.getMessage t) (str t))}}))
    {:status :abandoned
     :reason {:code :NO_CONFLICT_RESOLVER
              :message "no conflict resolver is configured"}}))

(defn- verify-rebased!
  [control swarm master-before]
  (let [git (:git control)
        master-ref (:master-ref @(:state control))
        master (git/repo-head git master-ref)]
    (cond
      (= :interrupted (:status master))
      {:status :interrupted :reason :interrupted :result master}

      (not= :ok (:status master))
      {:status :failed
       :error {:code :MASTER_HEAD_UNREADABLE
               :master-ref master-ref
               :result master}}

      (not= (:head master) (:head master-before))
      {:status :master-moved
       :reason :master-moved
       :master-before (:head master-before)
       :master-now (:head master)}

      :else
      (let [ancestor (git/ancestor? git (:workspace swarm) master-ref)]
        (cond
          (= :interrupted (:status ancestor))
          {:status :interrupted :reason :interrupted :result ancestor}

          (and (= :ok (:status ancestor))
               (:ancestor? ancestor))
          {:status :ok}

          :else
          {:status :failed
           :error {:code :REBASE_NOT_ANCESTOR
                   :master-ref master-ref
                   :ancestor ancestor}})))))

(defn- resolve-conflict-round!
  [control swarm round attempt conflict master-before]
  (let [git (:git control)
        decision (conflict-decision control swarm round conflict)]
    (cond
      (resolved-decision? decision)
      (let [staged (git/stage-all! git (:workspace swarm))]
        (cond
          (= :interrupted (:status staged))
          {:status :interrupted :reason :interrupted :result staged}

          (not= :ok (:status staged))
          (let [abort (git/rebase-abort! git (:workspace swarm))]
            {:status :abandoned
             :reason {:code :CONFLICT_STAGE_FAILED
                      :round round
                      :attempt attempt
                      :result staged
                      :abort abort}})

          :else
          (let [continued (git/rebase-continue! git (:workspace swarm))]
            (cond
              (= :ok (:status continued))
              (verify-rebased! control swarm master-before)

              (= :conflict (:status continued))
              {:status :retry :conflict continued}

              (= :interrupted (:status continued))
              {:status :interrupted :reason :interrupted :result continued}

              :else
              (let [abort (git/rebase-abort! git (:workspace swarm))]
                {:status :failed
                 :error {:code :REBASE_CONTINUE_FAILED
                         :round round
                         :attempt attempt
                         :result continued
                         :abort abort}})))))

      (abandon-decision? decision)
      (let [abort (git/rebase-abort! git (:workspace swarm))]
        {:status :abandoned
         :reason (merge {:code :REBASE_CONFLICT_UNRESOLVED
                         :round round
                         :attempt attempt}
                        (when (map? decision)
                          {:resolver decision})
                        {:abort abort})})

      :else
      (let [abort (git/rebase-abort! git (:workspace swarm))]
        {:status :abandoned
         :reason {:code :CONFLICT_RESOLVER_INVALID
                  :round round
                  :attempt attempt
                  :decision decision
                  :abort abort}}))))

(defn- rebase-with-conflicts!
  [control swarm round master-before]
  (let [git (:git control)
        master-ref (:master-ref @(:state control))
        first-attempt (git/rebase-onto! git (:workspace swarm) master-ref)]
    (cond
      (= :interrupted (:status first-attempt))
      {:status :interrupted :reason :interrupted :result first-attempt}

      (= :ok (:status first-attempt))
      (verify-rebased! control swarm master-before)

      (= :conflict (:status first-attempt))
      (loop [attempt 1
             conflict first-attempt]
        (if (> attempt (:max-rebase-rounds (:config control)))
          (let [abort (git/rebase-abort! git (:workspace swarm))]
            {:status :abandoned
             :reason {:code :REBASE_CONFLICT_UNRESOLVED
                      :attempts attempt
                      :abort abort}})
          (let [result (resolve-conflict-round!
                        control swarm round attempt conflict master-before)]
            (if (and (= :retry (:status result))
                     (:conflict result))
              (recur (inc attempt) (:conflict result))
              result))))

      :else
      {:status :failed
       :error {:code :REBASE_FAILED
               :round round
               :result first-attempt}})))

(defn- integration-failure
  [stage round swarm-id result]
  {:outcome :failed
   :error {:code :GIT_INTEGRATION_FAILED
           :stage stage
           :swarm-id swarm-id
           :round round
           :result result}})

(defn- join-interrupted
  [stage swarm-id result]
  {:outcome :interrupted
   :error {:code :JOIN_INTERRUPTED
           :stage stage
           :swarm-id swarm-id
           :result result}})

(defn- integrate-git!
  "Run the explicit rebase -> fast-forward sequence.  No state is changed.

  The worktree must be checked out on the swarm's recorded branch.  A
  detached HEAD or a foreign branch is rejected even when it points at the
  same commit, because `git rebase` would move that other ref and leave the
  swarm branch stale.  A branch head already equal to the master head is
  finalized without rebasing only when a persisted join intent or recovery
  marker proves an earlier integration attempt; a fresh branch with no
  commits goes through the normal rebase/ff path so a dirty worktree fails
  instead of being silently accepted.  An interrupted Git command is
  reported as `:interrupted`, never as a merge failure."
  [control swarm resumed?]
  (let [git (:git control)
        master-ref (:master-ref @(:state control))
        max-rounds (:max-rebase-rounds (:config control))
        swarm-id (:swarm-id swarm)
        worktree (git/head git (:workspace swarm))
        current (git/current-branch git (:workspace swarm))
        branch-head (git/branch-head git (:branch swarm))]
    (cond
      (= :interrupted (:status worktree))
      (join-interrupted :worktree swarm-id worktree)

      (not= :ok (:status worktree))
      {:outcome :failed
       :error {:code :WORKTREE_HEAD_UNREADABLE
               :swarm-id swarm-id
               :result worktree}}

      (= :interrupted (:status current))
      (join-interrupted :current-branch swarm-id current)

      (not= :ok (:status current))
      {:outcome :failed
       :error {:code :WORKTREE_BRANCH_UNREADABLE
               :swarm-id swarm-id
               :workspace (:workspace swarm)
               :result current}}

      (not= (:branch swarm) (:branch current))
      {:outcome :failed
       :error {:code :WORKTREE_BRANCH_MISMATCH
               :swarm-id swarm-id
               :workspace (:workspace swarm)
               :branch (:branch swarm)
               :current-branch (:branch current)
               :detached? (boolean (:detached? current))
               :worktree-head (:head worktree)}}

      (= :interrupted (:status branch-head))
      (join-interrupted :branch-head swarm-id branch-head)

      (not= :ok (:status branch-head))
      {:outcome :failed
       :error {:code :BRANCH_HEAD_UNREADABLE
               :swarm-id swarm-id
               :branch (:branch swarm)
               :result branch-head}}

      (not= (:head worktree) (:head branch-head))
      {:outcome :failed
       :error {:code :WORKTREE_BRANCH_MISMATCH
               :swarm-id swarm-id
               :branch (:branch swarm)
               :current-branch (:branch current)
               :detached? (boolean (:detached? current))
               :worktree-head (:head worktree)
               :branch-head (:head branch-head)}}

      :else
      (let [master (git/repo-head git master-ref)]
        (cond
          (= :interrupted (:status master))
          (join-interrupted :master swarm-id master)

          (not= :ok (:status master))
          {:outcome :failed
           :error {:code :MASTER_HEAD_UNREADABLE
                   :swarm-id swarm-id
                   :result master}}

          ;; A restart can follow a real fast-forward whose joined transition
          ;; was not persisted.  The persisted intent/recovery marker is the
          ;; evidence; branch equality alone is not.
          (and (true? resumed?)
               (= (:head master) (:head branch-head)))
          {:outcome :joined
           :swarm-id swarm-id
           :ff-commit (:head branch-head)
           :rebase-rounds 0
           :already-merged? true}

          :else
          (loop [round 1]
            (if (> round max-rounds)
              {:outcome :failed
               :error {:code :INTEGRATION_ROUNDS_EXHAUSTED
                       :swarm-id swarm-id
                       :rounds round}}
              (let [master-before (git/repo-head git master-ref)]
                (cond
                  (= :interrupted (:status master-before))
                  (join-interrupted :master-before swarm-id master-before)

                  (not= :ok (:status master-before))
                  {:outcome :failed
                   :error {:code :MASTER_HEAD_UNREADABLE
                           :swarm-id swarm-id
                           :result master-before}}

                  :else
                  (let [rebased (rebase-with-conflicts!
                                 control swarm round master-before)]
                    (case (:status rebased)
                      :abandoned
                      {:outcome :abandoned
                       :reason (:reason rebased)
                       :swarm-id swarm-id}

                      :interrupted
                      (join-interrupted :rebase swarm-id rebased)

                      :master-moved
                      (recur (inc round))

                      :failed
                      {:outcome :failed
                       :error (merge {:code :GIT_INTEGRATION_FAILED
                                      :stage :rebase
                                      :swarm-id swarm-id
                                      :round round}
                                     (:error rebased))}

                      :ok
                      (let [master-now (git/repo-head git master-ref)]
                        (cond
                          (= :interrupted (:status master-now))
                          (join-interrupted :master-now swarm-id master-now)

                          (not= :ok (:status master-now))
                          {:outcome :failed
                           :error {:code :MASTER_HEAD_UNREADABLE
                                   :swarm-id swarm-id
                                   :result master-now}}

                          (not= (:head master-before) (:head master-now))
                          (recur (inc round))

                          :else
                          (let [merged (git/ff-merge!
                                        git {:branch (:branch swarm)
                                             :ref master-ref})]
                            (cond
                              (= :interrupted (:status merged))
                              (join-interrupted :ff-merge swarm-id merged)

                              (= :ok (:status merged))
                              {:outcome :joined
                               :swarm-id swarm-id
                               :ff-commit (:head merged)
                               :rebase-rounds round}

                              :else
                              (let [master-after (git/repo-head git master-ref)]
                                (cond
                                  (= :interrupted (:status master-after))
                                  (join-interrupted :master-after swarm-id
                                                    master-after)

                                  (and (= :ok (:status master-after))
                                       (not= (:head master-now)
                                             (:head master-after)))
                                  (recur (inc round))

                                  :else
                                  (integration-failure
                                   :ff-merge round swarm-id merged))))))))))))))))))

(defn- pending-join-recovery
  "The live recovery evidence for a real fast-forward whose write failed."
  [swarm]
  (when (and (map? swarm)
             (true? (get-in swarm [:join-recovery :git-merged?])))
    (:join-recovery swarm)))

(defn- finalize-join!
  "Persist a verified fast-forward as a joined swarm.

  The fast-forward is irreversible, so the joined transition is persisted
  before it is published.  When the write fails, the live state keeps the
  FIFO head, its report, and the real `:ff-commit` under `:join-recovery`;
  no dependent starts, and the caller receives `:FLEET_STATE_PERSIST_FAILED`
  with `:git-merged? true`.  A later join retries only the write."
  [control swarm-id {:keys [ff-commit rebase-rounds]}]
  (let [attempt
        (locking (:monitor control)
          (let [state-value @(:state control)
                transition (try
                             (state/joined-swarm
                              state-value swarm-id
                              {:ff-commit ff-commit
                               :rebase-rounds rebase-rounds
                               :now (now control)})
                             (catch Throwable t
                               {:ok? false
                                :state state-value
                                :error {:code :JOIN_STATE_EXCEPTION
                                        :message (or (.getMessage t) (str t))}}))]
            (if-not (:ok? transition)
              (let [recovery (state/mark-join-recovery
                              state-value swarm-id
                              {:ff-commit ff-commit
                               :rebase-rounds rebase-rounds
                               :persist-error (:error transition)
                               :now (now control)})]
                (if (:ok? recovery)
                  (do
                    (reset! (:state control) (:state recovery))
                    (.notifyAll ^Object (:monitor control)))
                  ;; The transition is structurally invalid.  Clear the claim
                  ;; so a later join can retry; master/branch equality still
                  ;; prevents a repeated merge.
                  (clear-integrating! control swarm-id))
                {:ok? false
                 :error (assoc (:error transition)
                               :git-merged? true
                               :join-pending? true
                               :ff-commit ff-commit
                               :rebase-rounds rebase-rounds
                               :recovery (get-in (:state recovery)
                                                 [:swarms swarm-id :join-recovery]))})
              (let [persisted (persist-state! control (:state transition))]
                (if (:ok? persisted)
                  (do
                    (reset! (:state control) (:state transition))
                    (.notifyAll ^Object (:monitor control))
                    {:ok? true
                     :result (get-in transition [:receipt :result])
                     :started (get-in transition [:receipt :started])})
                  (let [recovery (state/mark-join-recovery
                                  state-value swarm-id
                                  {:ff-commit ff-commit
                                   :rebase-rounds rebase-rounds
                                   :persist-error (:error persisted)
                                   :now (now control)})]
                    (if (:ok? recovery)
                      (do
                        (reset! (:state control) (:state recovery))
                        (.notifyAll ^Object (:monitor control)))
                      (clear-integrating! control swarm-id))
                    {:ok? false
                     :error {:code :FLEET_STATE_PERSIST_FAILED
                             :stage :join-commit
                             :swarm-id swarm-id
                             :git-merged? true
                             :join-pending? true
                             :retryable? true
                             :ff-commit ff-commit
                             :rebase-rounds rebase-rounds
                             :persist-error (:error persisted)
                             :recovery (get-in (:state recovery)
                                               [:swarms swarm-id :join-recovery])}}))))))]
    (if (:ok? attempt)
      (do
        (launch-many! control (:started attempt))
        {:outcome :joined
         :result (:result attempt)})
      {:outcome :failed
       :error (:error attempt)})))

(defn- integrate!
  "Integrate one queued result under the master integration lock.

  Git runs only after the integration claim is durable.  A verified
  fast-forward is then persisted before it is published: a failed write
  keeps the result pending under `:join-recovery` and starts no dependent.
  A recovered pending join skips Git entirely and retries only the write.
  A Git failure marks that swarm failed and returns explicit error data; it
  does not return a fake join result."
  [control swarm-id]
  (let [lock (:integration-lock control)]
    (.lock lock)
    (try
      (let [candidate
            (locking (:monitor control)
              (let [state-value @(:state control)
                    swarm (get-in state-value [:swarms swarm-id])
                    recovery (pending-join-recovery swarm)]
                (cond
                  (not (and swarm
                            (= :running (:status swarm))
                            (:completed? swarm)
                            (= swarm-id (first (:completion-queue state-value)))))
                  {:status :skip}

                  recovery
                  {:status :recovery :recovery recovery}

                  (:integrating? swarm)
                  {:status :skip}

                  :else
                  (let [resumed? (or (true? (:join-intent swarm))
                                     (true? (get-in swarm
                                                    [:join-recovery :git-merged?])))
                        flagged (state/set-integrating state-value swarm-id true)]
                    (if-not (:ok? flagged)
                      {:status :skip}
                      (let [persisted (persist-state! control (:state flagged))]
                        (if-not (:ok? persisted)
                          {:status :persist-failed :error (:error persisted)}
                          (do
                            (reset! (:state control) (:state flagged))
                            (.notifyAll ^Object (:monitor control))
                            {:status :started :resumed? resumed?}))))))))]
        (case (:status candidate)
          :skip
          {:outcome :skip}

          :persist-failed
          {:outcome :failed
           :error (merge {:code :FLEET_STATE_PERSIST_FAILED
                          :stage :integration-claim
                          :swarm-id swarm-id}
                         (:error candidate))}

          :recovery
          (finalize-join! control swarm-id (:recovery candidate))

          :started
          (let [swarm (get-in @(:state control) [:swarms swarm-id])
                outcome (try
                          (integrate-git! control swarm (:resumed? candidate))
                          (catch Throwable t
                            {:outcome :failed
                             :error {:code :INTEGRATION_EXCEPTION
                                     :swarm-id swarm-id
                                     :message (or (.getMessage t) (str t))}}))]
            (case (:outcome outcome)
              :joined
              (finalize-join! control swarm-id
                              {:ff-commit (:ff-commit outcome)
                               :rebase-rounds (:rebase-rounds outcome)})

              :abandoned
              (let [result (update-state!
                            control
                            #(state/abandon-swarm
                              % swarm-id (:reason outcome)
                              {:now (now control) :force? true}))]
                (if (:ok? result)
                  {:outcome :abandoned}
                  (do
                    (clear-integrating! control swarm-id)
                    {:outcome :failed :error (:error result)})))

              :interrupted
              (let [swarm (get-in @(:state control) [:swarms swarm-id])
                    abort (when swarm
                            (try
                              (git/rebase-abort! (:git control)
                                                 (:workspace swarm))
                              (catch Throwable t
                                {:status :failed
                                 :code :REBASE_ABORT_FAILED
                                 :message (or (.getMessage t) (str t))})))]
                (clear-integrating! control swarm-id)
                {:outcome :interrupted
                 :error (assoc (:error outcome) :abort abort)})

              :failed
              (let [failure (merge {:code :GIT_INTEGRATION_FAILED}
                                   (:error outcome))
                    result (update-state!
                            control
                            #(state/fail-swarm
                              % swarm-id failure
                              {:now (now control) :force? true}))]
                (if (:ok? result)
                  {:outcome :failed :error failure}
                  (do
                    (clear-integrating! control swarm-id)
                    {:outcome :failed :error (:error result)})))))))
      (finally
        (.unlock lock)))))

(defn- await-change
  "Wait while the monitor is held.  Return `:notified`, `:timeout`, or
  `:interrupted`.  DEADLINE is a `System/nanoTime` value; the control clock is
  reserved for recorded timestamps and retention."
  [monitor deadline]
  (let [remaining-ms (fn []
                       (when deadline
                         (max 1 (long (/ (- deadline (System/nanoTime))
                                         1000000)))))]
    (cond
      (and deadline (>= (System/nanoTime) deadline))
      :timeout

      :else
      (try
        (if deadline
          (.wait ^Object monitor (remaining-ms))
          (.wait ^Object monitor))
        (if (and deadline (>= (System/nanoTime) deadline))
          :timeout
          :notified)
        (catch InterruptedException _
          (.interrupt (Thread/currentThread))
          :interrupted)))))

(defn- next-action
  [control deadline]
  (locking (:monitor control)
    (if @(:allocating control)
      (case (await-change (:monitor control) deadline)
        :notified [:retry]
        :timeout [:timeout]
        :interrupted [:interrupted])
      (let [join-state (state/join-state @(:state control))]
        (case (:status join-state)
          :ready [:integrate (:swarm-id join-state)]
          :blocked (case (await-change (:monitor control) deadline)
                     :notified [:retry]
                     :timeout [:timeout]
                     :interrupted [:interrupted])
          :deadlock [:deadlock join-state]
          :no-results [:no-results]
          :none [:none])))))

(defn join!
  "Take the first completed result in FIFO order, or block until one exists.

  Returns the joined result map, `:none`, `:no-results`, or
  `{:fleet/error ...}`.  `:no-results` means no swarm in the latest fork
  joined and at least one ended abandoned or failed, matching AGENTS.md
  §2.2.  The result contains
  only actual Git data: `:ff-commit` comes from a verified fast-forward.
  While a `fork!` is creating worktrees and launching swarms, join blocks
  on the control monitor instead of returning `:none`; the allocation flag
  is cleared and waiters are notified even when the fork fails.
  A queue head already held by another `join!` under the integration lock
  is likewise a blocking state, not a second integration attempt.
  Optional `:timeout-ms` bounds the wait; the default is unbounded as
  AGENTS.md §2.2/§5.3 require.  An abandoned result is not returned; join
  continues with the next queued result.  A verified fast-forward whose
  joined state cannot be persisted stays the queue head: join returns
  `:FLEET_STATE_PERSIST_FAILED` with `:git-merged? true`, starts no
  dependent, and a later call retries only the write.  A Git command
  interrupted by thread cancellation returns `:JOIN_INTERRUPTED` and leaves
  the result in the queue; it is never converted into a swarm failure."
  ([control]
   (join! control {}))
  ([control {:keys [timeout-ms]}]
   (let [deadline (when timeout-ms
                    (+ (System/nanoTime) (* (long timeout-ms) 1000000)))]
     (loop []
       (let [action (next-action control deadline)]
         (case (first action)
           :integrate
           (let [outcome (integrate! control (second action))]
             (case (:outcome outcome)
               :joined (:result outcome)
               :abandoned (recur)
               :interrupted {:fleet/error (assoc (:error outcome)
                                                 :swarm-id (second action))}
               :failed {:fleet/error (assoc (:error outcome)
                                            :swarm-id (second action))}
               :skip (recur)))

           :retry (recur)

           :timeout
           {:fleet/error {:code :JOIN_TIMEOUT
                          :timeout-ms timeout-ms}}

           :interrupted
           {:fleet/error {:code :JOIN_INTERRUPTED}}

           :deadlock
           {:fleet/error (merge {:code :FLEET_DEADLOCK}
                                (second action))}

           :no-results :no-results

           :none :none))))))

(defn resume-ready!
  "Start queued swarms whose worktree and dependencies are ready.

  This is the restart counterpart to `fork!`: restored queued swarms do not
  have an in-memory launch effect, so the service calls this once after
  rebuilding their runtimes.  A persistence failure suppresses the launch
  effects; the caller must treat the restore as failed rather than serve a
  swarm whose start was never recorded."
  [control]
  (let [result (update-state! control state/start-ready)]
    (if-not (:ok? result)
      result
      (if (false? (:persisted? result))
        result
        (assoc result
               :launch (launch-many! control (get-in result [:receipt :started])))))))

(defn gc!
  "Remove worktrees whose configured retention has elapsed.

  Only terminal, registered swarms whose workspace is the exact path this
  fleet created and whose explicit `:gc-after` deadline elapsed are eligible.
  A running or queued swarm is never touched.  Each removal is a real
  `git worktree remove`; a failure is reported and the worktree stays."
  [control]
  (let [lock (:integration-lock control)]
    (.lock lock)
    (try
      (let [current-time (now control)
            candidates (state/gc-candidates @(:state control) current-time)]
        (loop [remaining (seq candidates)
               removed []
               failed []]
          (if-let [swarm (first remaining)]
            (let [result (try
                           (git/remove-worktree! (:git control) (:workspace swarm))
                           (catch InterruptedException e
                             (.interrupt (Thread/currentThread))
                             {:status :interrupted
                              :code :GC_INTERRUPTED
                              :message (or (.getMessage e)
                                           "fleet GC was interrupted")})
                           (catch Throwable t
                             {:status :failed
                              :code :GC_EXCEPTION
                              :message (or (.getMessage t) (str t))}))]
              (cond
                (= :interrupted (:status result))
                (do
                  (update-state! control
                                 #(state/mark-gc-error % (:swarm-id swarm) result))
                  {:ok? false
                   :interrupted? true
                   :removed removed
                   :failed (conj failed {:swarm-id (:swarm-id swarm)
                                         :result result})
                   :state @(:state control)})

                (= :ok (:status result))
                (do
                  (update-state! control
                                 #(state/mark-worktree-removed % (:swarm-id swarm)))
                  (recur (next remaining)
                         (conj removed (:swarm-id swarm))
                         failed))

                :else
                (do
                  (update-state! control
                                 #(state/mark-gc-error % (:swarm-id swarm) result))
                  (recur (next remaining)
                         removed
                         (conj failed {:swarm-id (:swarm-id swarm)
                                       :result result})))))
            {:ok? true
             :removed removed
             :failed failed
             :state @(:state control)})))
      (finally
        (.unlock lock)))))

(defn fork
  "Spec-shaped fork wrapper.

  Returns `[{:swarm-id :slice-id :workspace} ...]` on success.  A resource
  shortage returns `:INSUFFICIENT_WORKTREES` as AGENTS.md §2.2 requires.
  Every other failure throws `ex-info` with the complete `fork!` result, so
  a caller cannot mistake a partial failure for a successful fork."
  [control slices]
  (let [result (fork! control slices)]
    (if (:ok? result)
      (mapv #(select-keys % [:swarm-id :slice-id :workspace])
            (:swarms result))
      (if (= :INSUFFICIENT_WORKTREES (get-in result [:error :code]))
        :INSUFFICIENT_WORKTREES
        (throw (ex-info "fleet: fork failed"
                        {:fleet/error (:error result)
                         :result result}))))))

(defn join
  "Spec-shaped join wrapper.  See `join!`."
  ([control] (join! control))
  ([control opts] (join! control opts)))
