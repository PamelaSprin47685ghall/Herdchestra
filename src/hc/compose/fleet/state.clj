(ns hc.compose.fleet.state
  "Pure fork/join fleet state for disjoint worktree swarms.

  The state is plain EDN.  It records slices, swarms, the completion queue,
  the master version, and an append-only event log.  Join is a FIFO queue,
  not a race: a completed swarm enters the queue exactly once, and
  `joined-swarm` removes exactly the head.  There is no sibling cancellation
  transition.  A swarm can be abandoned only by naming that swarm; its
  dependents fail because their dependency can never be joined, while every
  unrelated swarm keeps running.

  `mark-join-recovery` keeps a verified fast-forward whose joined write
  failed as the queue head, so a later write-only retry can finalize it
  without repeating Git or starting a dependent twice.

  This namespace never touches Git or the filesystem.  `hc.compose.fleet.control`
  performs the explicit Git integration and only then calls `joined-swarm`."
  (:require [clojure.string :as str]
            [hc.compose.fleet.slice :as slice]))

(def statuses
  #{:queued :running :joined :abandoned :failed})

(def terminal-statuses
  #{:joined :abandoned :failed})

(def default-retention-ms
  "Seven days, as AGENTS.md §2.2/§5.3 require before worktree GC."
  (* 7 24 60 60 1000))

(def default-max-events
  "Live event-log tail.  The persisted snapshot uses the same bound."
  1000)

(defn- fail
  [state code message details]
  {:ok? false
   :state state
   :error (cond-> {:code code :message message}
            (some? details) (assoc :details details))
   :effects []})

(defn- safe-component
  [value]
  (let [text (str value)
        text (str/replace text #"[^A-Za-z0-9._-]+" "-")
        text (str/replace text #"(^\.+|\.+$)" "")
        text (str/replace text #"-{2,}" "-")]
    (if (str/blank? text) "mission" text)))

(defn- worktree-path
  [state swarm-id]
  (str (str/replace (:worktree-root state) #"/+$" "")
       "/"
       (safe-component (:mission-id state))
       "/"
       swarm-id))

(defn- branch-name
  [state swarm-id]
  (str "hc/" (safe-component (:mission-id state)) "/" swarm-id))

(defn new-state
  "Create an empty fleet state.

  WORKTREE-ROOT is the directory that will hold one child directory per
  swarm.  MISSION-ID is used only for a readable path and branch prefix."
  [{:keys [mission-id worktree-root master-ref retention-ms]
    :or {mission-id "mission"
         master-ref "master"
         retention-ms default-retention-ms}}]
  (when-not (and (string? worktree-root) (not (str/blank? worktree-root)))
    (throw (ex-info "fleet: worktree-root must be a non-blank string"
                    {:error :INVALID_WORKTREE_ROOT
                     :worktree-root worktree-root})))
  {:mission-id (or mission-id "mission")
   :worktree-root worktree-root
   :master-ref (or master-ref "master")
   :retention-ms (or retention-ms default-retention-ms)
   :slices {}
   :swarms {}
   :slice->swarm {}
   :completion-queue []
   :completion-seq 0
   :next-swarm-seq 0
   :fork-seq 0
   :version 0
   :event-seq 0
   :events []
   :master-version 0})

(defn- add-event
  [state event]
  (let [event-id (inc (:event-seq state))]
    (-> state
        (update :event-seq inc)
        (update :events
                (fn [events]
                  (vec (take-last default-max-events
                                  (conj events
                                        (assoc event :event-id event-id)))))))))

(defn- attach-last-event
  [state event]
  (if-let [swarm-id (:swarm-id event)]
    (if (get-in state [:swarms swarm-id])
      (assoc-in state [:swarms swarm-id :last-event] event)
      state)
    state))

(defn- transition
  "Record a successful transition: one fleet-version bump and its events."
  [state state' events effects receipt]
  (let [state' (update state' :version inc)
        fleet-version (:version state')
        events (mapv #(assoc % :fleet-version fleet-version) events)
        state' (reduce attach-last-event
                       (reduce add-event state' events)
                       events)]
    {:ok? true
     :state state'
     :events events
     :effects (vec effects)
     :receipt receipt}))

(defn- no-op
  [state receipt]
  {:ok? true :state state :events [] :effects [] :receipt receipt})

(defn- deps-joined?
  [state slice]
  (every? (fn [dep]
            (= :joined
               (get-in state [:swarms (get-in state [:slice->swarm dep]) :status])))
          (:depends-on slice)))

(defn- terminal-dependency
  [state slice]
  (some (fn [dep]
          (let [swarm (get-in state [:swarms (get-in state [:slice->swarm dep])])]
            (when (and swarm
                       (contains? #{:abandoned :failed} (:status swarm)))
              {:dependency dep
               :status (:status swarm)
               :swarm-id (:swarm-id swarm)})))
        (:depends-on slice)))

(defn fork
  "Admit SLICES and assign one queued swarm per slice.

  Worktrees are not created here.  The caller must create them, then call
  `mark-worktree-ready` and `start-ready`.  All new swarms start `:queued`
  because their workspace is not yet verified.  Dependencies are checked
  against slices already in the fleet; a dependency that already ended in
  `:abandoned` or `:failed` is rejected instead of queueing forever."
  [state slices]
  (let [initial-state state
        existing-ids (set (keys (:slices state)))
        validation (slice/validate-slices slices existing-ids)]
    (if-not (:ok? validation)
      (fail state (get-in validation [:error :code])
            (get-in validation [:error :message])
            (get-in validation [:error :details]))
      (let [new-slices (:slices validation)
            fork-seq (inc (or (:fork-seq state) 0))]
        (if-let [bad (some #(terminal-dependency state %) new-slices)]
          (fail state :DEPENDENCY_TERMINAL
                "a dependency already ended without a joinable result"
                bad)
          (loop [remaining (seq new-slices)
                 next-seq (:next-swarm-seq state)
                 state state
                 events []
                 swarm-ids []]
            (if-let [slice (first remaining)]
              (let [n (inc next-seq)
                    swarm-id (str "swarm-" n)
                    workspace (worktree-path state swarm-id)
                    branch (branch-name state swarm-id)
                    swarm {:swarm-id swarm-id
                           :slice-id (:slice-id slice)
                           :workspace workspace
                           :branch branch
                           :status :queued
                           :worktree-ready? false
                           :completed? false
                           :integrating? false
                           :join-intent false
                           :fork-seq fork-seq
                           :report nil
                           :ff-commit nil
                           :rebase-rounds 0
                           :board-version 0
                           :completion-seq nil
                           :root-ready 0
                           :root-waiting 0
                           :root-done 0
                           :master-version (:master-version state)
                           :created-seq n
                           :last-event nil
                           :finished-at nil
                           :gc-after nil
                           :worktree-removed? false}]
                (recur (next remaining)
                       n
                       (-> state
                           (assoc-in [:slices (:slice-id slice)] slice)
                           (assoc-in [:swarms swarm-id] swarm)
                           (assoc-in [:slice->swarm (:slice-id slice)] swarm-id)
                           (assoc :next-swarm-seq n
                                  :fork-seq fork-seq))
                       (conj events {:type :fork
                                     :swarm-id swarm-id
                                     :slice-id (:slice-id slice)
                                     :workspace workspace})
                       (conj swarm-ids swarm-id)))
              (transition initial-state state events [] {:status :forked
                                                         :swarm-ids swarm-ids}))))))))

(defn mark-worktree-ready
  "Mark SWARM-IDS as having verified worktrees.  This does not start them."
  [state swarm-ids]
  (let [ids (vec (distinct swarm-ids))
        unknown (remove #(get-in state [:swarms %]) ids)
        queued (filter #(= :queued (get-in state [:swarms % :status])) ids)]
    (cond
      (seq unknown)
      (fail state :UNKNOWN_SWARM "worktree-ready names an unknown swarm"
            {:swarm-ids (vec unknown)})

      (empty? queued)
      (no-op state {:status :worktree-ready :swarm-ids []})

      :else
      (let [state' (reduce (fn [s id]
                             (assoc-in s [:swarms id :worktree-ready?] true))
                           state queued)
            events (mapv (fn [id]
                           {:type :worktree-ready
                            :swarm-id id
                            :slice-id (get-in state [:swarms id :slice-id])})
                         queued)]
        (transition state state' events [] {:status :worktree-ready
                                            :swarm-ids queued})))))

(defn start-ready
  "Start queued swarms whose worktree is ready and whose dependencies joined.

  Starting is a state transition plus a `[:launch swarm-id]` effect.  It does
  not cancel or reorder any other swarm."
  [state]
  (let [started (->> (:swarms state)
                     vals
                     (sort-by :created-seq)
                     (filter (fn [swarm]
                               (and (= :queued (:status swarm))
                                    (:worktree-ready? swarm)
                                    (not (:integrating? swarm))
                                    (deps-joined? state (get-in state [:slices (:slice-id swarm)])))))
                     (mapv :swarm-id))]
    (if (empty? started)
      (no-op state {:status :none :started []})
      (let [state' (reduce (fn [s id]
                             (assoc-in s [:swarms id :status] :running))
                           state started)
            events (mapv (fn [id]
                           {:type :swarm-started
                            :swarm-id id
                            :slice-id (get-in state [:swarms id :slice-id])
                            :workspace (get-in state [:swarms id :workspace])})
                         started)
            effects (mapv (fn [id] [:launch id]) started)]
        (transition state state' events effects {:status :started
                                                 :started started})))))

(defn complete-swarm
  "Record a swarm's final report and enqueue its result once.

  REPORT is the full LLM-written text.  It is stored verbatim.  BOARD-VERSION
  is the board version at completion and is copied into the `:result-queued`
  event required by AGENTS.md §2.2."
  [state swarm-id report {:keys [board-version] :as _opts}]
  (let [swarm (get-in state [:swarms swarm-id])]
    (cond
      (nil? swarm)
      (fail state :UNKNOWN_SWARM "complete names an unknown swarm"
            {:swarm-id swarm-id})

      (not= :running (:status swarm))
      (fail state :SWARM_NOT_RUNNING "only a running swarm can complete"
            {:swarm-id swarm-id :status (:status swarm)})

      (:completed? swarm)
      (fail state :ALREADY_COMPLETED "swarm already enqueued its result"
            {:swarm-id swarm-id})

      (not (string? report))
      (fail state :INVALID_REPORT "report must be the full report string"
            {:swarm-id swarm-id})

      (not (and (integer? board-version) (not (neg? board-version))))
      (fail state :INVALID_BOARD_VERSION
            "board-version must be a non-negative integer"
            {:swarm-id swarm-id :board-version board-version})

      :else
      (let [completion-seq (inc (:completion-seq state))
            state' (-> state
                       (assoc-in [:swarms swarm-id :completed?] true)
                       (assoc-in [:swarms swarm-id :recovery] nil)
                       (assoc-in [:swarms swarm-id :report] report)
                       (assoc-in [:swarms swarm-id :board-version] board-version)
                       (assoc-in [:swarms swarm-id :completion-seq] completion-seq)
                       (update :completion-seq inc)
                       (update :completion-queue conj swarm-id))
            event {:type :result-queued
                   :swarm-id swarm-id
                   :slice-id (:slice-id swarm)
                   :board-version board-version}]
        (transition state state' [event] [[:notify-join]]
                    {:status :queued
                     :swarm-id swarm-id
                     :slice-id (:slice-id swarm)
                     :completion-seq completion-seq})))))

(defn- propagate-dependency-failures
  "Fail queued swarms whose dependency can no longer join.

  This is dependency propagation, not sibling cancellation: only queued
  dependents of the named terminal slice change.  Running and unrelated
  swarms are untouched."
  [state cause-swarm-id cause now]
  (loop [state state
         failed #{cause-swarm-id}
         changed? true
         failed-ids []
         events []]
    (if-not changed?
      {:state state :failed-ids failed-ids :events events}
      (let [newly (->> (:slice->swarm state)
                       (keep (fn [[slice-id swarm-id]]
                               (let [swarm (get-in state [:swarms swarm-id])]
                                 (when (and (= :queued (:status swarm))
                                            (not (:completed? swarm))
                                            (some (fn [dep]
                                                    (contains? failed
                                                               (get-in state [:slice->swarm dep])))
                                                  (:depends-on (get-in state [:slices slice-id]))))
                                   {:swarm-id swarm-id
                                    :slice-id slice-id
                                    :dependency (some (fn [dep]
                                                        (when (contains? failed
                                                                         (get-in state [:slice->swarm dep]))
                                                          dep))
                                                      (:depends-on (get-in state [:slices slice-id])))}))))
                       vec)]
        (if (empty? newly)
          {:state state :failed-ids failed-ids :events events}
          (let [state' (reduce (fn [s {:keys [swarm-id dependency]}]
                                 (-> s
                                     (assoc-in [:swarms swarm-id :status] :failed)
                                     (assoc-in [:swarms swarm-id :failure]
                                               {:code :DEPENDENCY_TERMINAL
                                                :cause cause
                                                :dependency dependency})
                                     (assoc-in [:swarms swarm-id :finished-at] now)
                                     (assoc-in [:swarms swarm-id :gc-after]
                                               (+ now (:retention-ms state)))
                                     (update :completion-queue
                                             (fn [q] (vec (remove #{swarm-id} q))))))
                               state newly)
                new-events (mapv (fn [{:keys [swarm-id slice-id dependency]}]
                                   {:type :failed
                                    :swarm-id swarm-id
                                    :slice-id slice-id
                                    :dependency dependency
                                    :reason :dependency-terminal})
                                 newly)]
            (recur state'
                   (into failed (map :swarm-id newly))
                   true
                   (into failed-ids (map :swarm-id newly))
                   (into events new-events))))))))

(defn- finish-terminal
  [state swarm-id status details now]
  (let [swarm (get-in state [:swarms swarm-id])
        state' (-> state
                   (assoc-in [:swarms swarm-id :status] status)
                   (assoc-in [:swarms swarm-id :completed?] false)
                   (assoc-in [:swarms swarm-id :integrating?] false)
                   (assoc-in [:swarms swarm-id :join-intent] false)
                   (assoc-in [:swarms swarm-id :join-recovery] nil)
                   (update-in [:swarms swarm-id]
                              dissoc :report :board-version :completion-seq)
                   (assoc-in [:swarms swarm-id :finished-at] now)
                   (assoc-in [:swarms swarm-id :gc-after]
                             (+ now (:retention-ms state)))
                   (update :completion-queue
                           (fn [q] (vec (remove #{swarm-id} q)))))
        state' (reduce (fn [s [k v]]
                         (assoc-in s [:swarms swarm-id k] v))
                       state' details)
        cause (if (= status :abandoned)
                {:swarm-id swarm-id
                 :abandon-reason (:abandon-reason details)}
                {:swarm-id swarm-id
                 :failure (:failure details)})
        propagated (propagate-dependency-failures state' swarm-id cause now)
        event (assoc {:type status
                      :swarm-id swarm-id
                      :slice-id (:slice-id swarm)}
                     (if (= status :abandoned) :reason :error)
                     (or (:abandon-reason details) (:failure details)))]
    (transition state (:state propagated)
                (into [event] (:events propagated))
                [[:notify-join]]
                {:status status
                 :swarm-id swarm-id
                 :slice-id (:slice-id swarm)
                 :failed-dependents (:failed-ids propagated)})))

(defn abandon-swarm
  "Abandon exactly SWARM-ID.

  A completed result is withdrawn from the queue.  A queue head whose real
  fast-forward is already merged is refused unless `:force?` is set, because
  the Git merge cannot be undone.  Queued dependents fail; no running
  sibling is touched.  The worktree is retained until `gc!`.  Idempotent for
  an already-abandoned swarm."
  [state swarm-id reason {:keys [now force?] :or {now 0}}]
  (let [swarm (get-in state [:swarms swarm-id])]
    (cond
      (nil? swarm)
      (fail state :UNKNOWN_SWARM "abandon names an unknown swarm"
            {:swarm-id swarm-id})

      (and (:integrating? swarm) (not force?))
      (fail state :SWARM_INTEGRATING "cannot abandon a swarm during join"
            {:swarm-id swarm-id})

      (and (true? (get-in swarm [:join-recovery :git-merged?]))
           (not force?))
      (fail state :SWARM_JOIN_PENDING
            "the real fast-forward is already merged; finish the join"
            {:swarm-id swarm-id
             :ff-commit (get-in swarm [:join-recovery :ff-commit])})

      (= :abandoned (:status swarm))
      (no-op state {:status :abandoned
                    :swarm-id swarm-id
                    :slice-id (:slice-id swarm)
                    :already? true})

      (contains? terminal-statuses (:status swarm))
      (fail state :SWARM_ALREADY_TERMINAL "swarm already ended"
            {:swarm-id swarm-id :status (:status swarm)})

      (nil? reason)
      (fail state :INVALID_ABANDON_REASON "abandon requires a reason"
            {:swarm-id swarm-id})

      :else
      (finish-terminal state swarm-id :abandoned
                       {:abandon-reason reason}
                       now))))

(defn fail-swarm
  "Fail exactly SWARM-ID after an explicit runtime or Git failure.

  A completed result is withdrawn from the queue.  A queue head whose real
  fast-forward is already merged is refused unless `:force?` is set, because
  the Git merge cannot be undone.  Queued dependents fail; unrelated swarms
  keep their state."
  [state swarm-id error {:keys [now force?] :or {now 0}}]
  (let [swarm (get-in state [:swarms swarm-id])]
    (cond
      (nil? swarm)
      (fail state :UNKNOWN_SWARM "failure names an unknown swarm"
            {:swarm-id swarm-id})

      (and (:integrating? swarm) (not force?))
      (fail state :SWARM_INTEGRATING "cannot fail a swarm during join"
            {:swarm-id swarm-id})

      (and (true? (get-in swarm [:join-recovery :git-merged?]))
           (not force?))
      (fail state :SWARM_JOIN_PENDING
            "the real fast-forward is already merged; finish the join"
            {:swarm-id swarm-id
             :ff-commit (get-in swarm [:join-recovery :ff-commit])})

      (= :failed (:status swarm))
      (no-op state {:status :failed
                    :swarm-id swarm-id
                    :slice-id (:slice-id swarm)
                    :already? true})

      (contains? terminal-statuses (:status swarm))
      (fail state :SWARM_ALREADY_TERMINAL "swarm already ended"
            {:swarm-id swarm-id :status (:status swarm)})

      (nil? error)
      (fail state :INVALID_FAILURE "failure requires an error value"
            {:swarm-id swarm-id})

      :else
      (finish-terminal state swarm-id :failed
                       {:failure error}
                       now))))

(defn joined-swarm
  "Record a successful rebase + fast-forward for the queue head.

  This is the only transition that advances `:master-version` and the only
  way a result becomes `:joined`.  The caller must already have run the real
  Git integration; this function cannot invent an `:ff-commit`."
  [state swarm-id {:keys [ff-commit rebase-rounds now]
                   :or {now 0}}]
  (let [swarm (get-in state [:swarms swarm-id])
        head (first (:completion-queue state))]
    (cond
      (nil? swarm)
      (fail state :UNKNOWN_SWARM "join names an unknown swarm"
            {:swarm-id swarm-id})

      (not= :running (:status swarm))
      (fail state :SWARM_NOT_RUNNING "only a running swarm can join"
            {:swarm-id swarm-id :status (:status swarm)})

      (not (:completed? swarm))
      (fail state :SWARM_NOT_COMPLETED "swarm has no completed result"
            {:swarm-id swarm-id})

      (not= swarm-id head)
      (fail state :RESULT_NOT_HEAD "join must take the FIFO queue head"
            {:swarm-id swarm-id :head head})

      (not (and (string? ff-commit) (not (str/blank? ff-commit))))
      (fail state :INVALID_FF_COMMIT
            "join requires the actual fast-forward commit"
            {:swarm-id swarm-id :ff-commit ff-commit})

      (not (and (integer? rebase-rounds) (not (neg? rebase-rounds))))
      (fail state :INVALID_REBASE_ROUNDS
            "rebase-rounds must be a non-negative integer"
            {:swarm-id swarm-id :rebase-rounds rebase-rounds})

      :else
      (let [master-version (inc (:master-version state))
            state' (-> state
                       (update :completion-queue (fn [q] (vec (rest q))))
                       (assoc-in [:swarms swarm-id :status] :joined)
                       (assoc-in [:swarms swarm-id :recovery] nil)
                       (assoc-in [:swarms swarm-id :integrating?] false)
                       (assoc-in [:swarms swarm-id :join-intent] false)
                       (assoc-in [:swarms swarm-id :join-recovery] nil)
                       (assoc-in [:swarms swarm-id :ff-commit] ff-commit)
                       (assoc-in [:swarms swarm-id :rebase-rounds] rebase-rounds)
                       (assoc-in [:swarms swarm-id :master-version] master-version)
                       (assoc-in [:swarms swarm-id :finished-at] now)
                       (assoc-in [:swarms swarm-id :gc-after]
                                 (+ now (:retention-ms state)))
                       (assoc :master-version master-version))
            event {:type :joined
                   :swarm-id swarm-id
                   :slice-id (:slice-id swarm)
                   :ff-commit ff-commit
                   :rebase-rounds rebase-rounds
                   :report (:report swarm)}
            joined (transition state state' [event] [[:notify-join]]
                               {:status :joined
                                :result {:swarm-id swarm-id
                                         :slice-id (:slice-id swarm)
                                         :workspace (:workspace swarm)
                                         :report (:report swarm)
                                         :ff-commit ff-commit
                                         :rebase-rounds rebase-rounds}})
            started (start-ready (:state joined))]
        (-> started
            (update :effects into (:effects joined))
            (update :receipt assoc
                    :status :joined
                    :result (get-in joined [:receipt :result])
                    :started (get-in started [:receipt :started])))))))

(defn mark-join-recovery
  "Keep a real fast-forward that could not be persisted as an open join.

  A fast-forward cannot be undone.  When the joined transition cannot be
  written, the FIFO head stays in place with its report, completion
  sequence, and verified `:ff-commit`.  The integration flag is set so
  `abandon-swarm` and `fail-swarm` refuse to discard the obligation, while
  `join-state` reports the head as ready for a write-only retry.  This
  transition is live-only: the caller must not report it as durable.  It is
  recorded only before a dependent is dispatched, so the marker also proves
  that no dependent started for this join."
  [state swarm-id {:keys [ff-commit rebase-rounds persist-error now]}]
  (let [swarm (get-in state [:swarms swarm-id])
        head (first (:completion-queue state))]
    (cond
      (nil? swarm)
      (fail state :UNKNOWN_SWARM "join recovery names an unknown swarm"
            {:swarm-id swarm-id})

      (not= :running (:status swarm))
      (fail state :SWARM_NOT_RUNNING
            "only a running swarm can keep a merged result"
            {:swarm-id swarm-id :status (:status swarm)})

      (not (:completed? swarm))
      (fail state :SWARM_NOT_COMPLETED
            "swarm has no completed result to recover"
            {:swarm-id swarm-id})

      (not= swarm-id head)
      (fail state :RESULT_NOT_HEAD
            "join recovery must keep the FIFO queue head"
            {:swarm-id swarm-id :head head})

      (not (and (string? ff-commit) (not (str/blank? ff-commit))))
      (fail state :INVALID_FF_COMMIT
            "join recovery requires the actual fast-forward commit"
            {:swarm-id swarm-id :ff-commit ff-commit})

      (not (and (integer? rebase-rounds) (not (neg? rebase-rounds))))
      (fail state :INVALID_REBASE_ROUNDS
            "rebase-rounds must be a non-negative integer"
            {:swarm-id swarm-id :rebase-rounds rebase-rounds})

      :else
      (let [recovery {:git-merged? true
                      :ff-commit ff-commit
                      :rebase-rounds rebase-rounds
                      :persist-error persist-error
                      :recovered-at now}]
        {:ok? true
         :state (-> state
                    (assoc-in [:swarms swarm-id :integrating?] true)
                    (assoc-in [:swarms swarm-id :join-recovery] recovery))
         :receipt {:status :join-recovery
                   :swarm-id swarm-id
                   :slice-id (:slice-id swarm)
                   :ff-commit ff-commit
                   :rebase-rounds rebase-rounds
                   :recovery recovery}}))))

(defn set-integrating
  "Set the internal integration flag without publishing an event."
  [state swarm-id value]
  (if (get-in state [:swarms swarm-id])
    {:ok? true
     :state (cond-> (assoc-in state [:swarms swarm-id :integrating?]
                              (boolean value))
              (true? value)
              (assoc-in [:swarms swarm-id :join-intent] true))}
    (fail state :UNKNOWN_SWARM "integrating flag names an unknown swarm"
          {:swarm-id swarm-id})))

(defn join-state
  "Describe what `join` should do next.

  :ready     the FIFO queue has a result, including a real fast-forward
             whose joined write failed and awaits a write-only retry
  :blocked   the queue is empty, but a swarm is still running or allocating
  :none       no work remains and at least one swarm joined, or the fleet
              is empty
  :no-results no swarm in the latest fork joined and at least one ended
              abandoned or failed; earlier forks cannot mask this
  :deadlock   queued work can never start; report it instead of inventing
              `:none` or a fake result"
  [state]
  (let [queue (:completion-queue state)
        all-swarms (vals (:swarms state))
        latest-fork (reduce max 0 (map #(or (:fork-seq %) 0) all-swarms))
        latest-swarms (filter #(= latest-fork (or (:fork-seq %) 0)) all-swarms)
        swarms all-swarms
        active (filter #(and (= :running (:status %))
                             (not (:completed? %)))
                       swarms)
        queued (filter #(= :queued (:status %)) swarms)
        orphan (filter #(and (= :running (:status %))
                             (:completed? %)
                             (not (some (fn [id] (= id (:swarm-id %))) queue)))
                       swarms)
        joined? (some #(= :joined (:status %)) latest-swarms)
        terminal? (some #(contains? terminal-statuses (:status %)) latest-swarms)]
    (cond
      (seq queue)
      (let [head (first queue)
            swarm (get-in state [:swarms head])]
        ;; A real fast-forward whose joined transition could not be
        ;; persisted stays the queue head.  It is ready for the write-only
        ;; retry, not blocked behind its own integration claim.
        (if (and (:integrating? swarm)
                 (not (true? (get-in swarm [:join-recovery :git-merged?]))))
          {:status :blocked
           :integrating [head]}
          {:status :ready :swarm-id head}))
      (seq orphan) {:status :deadlock
                    :reason :orphan-result
                    :swarm-ids (mapv :swarm-id orphan)}
      (seq active) {:status :blocked
                    :running (mapv :swarm-id active)}
      (some (complement :worktree-ready?) queued)
      {:status :blocked
       :allocating (mapv :swarm-id (filter (complement :worktree-ready?) queued))}
      (seq queued) {:status :deadlock
                    :reason :unsatisfied-dependencies
                    :swarm-ids (mapv :swarm-id queued)}
      (and (not joined?) terminal?) {:status :no-results}
      :else {:status :none})))

(defn fleet-rows
  "Steward-facing fleet rows in creation order.

  A joined row carries the full report verbatim.  `:queued-count` is 1 while
  that swarm's result waits in the join queue, otherwise 0.
  `:master-version` is the current fleet master version;
  `:swarm-master-version` is the last master version this swarm integrated."
  [state]
  (->> (:swarms state)
       vals
       (sort-by :created-seq)
       (mapv (fn [swarm]
               (cond-> {:swarm-id (:swarm-id swarm)
                        :slice-id (:slice-id swarm)
                        :worktree (:workspace swarm)
                        :status (:status swarm)
                        :root-ready (:root-ready swarm)
                        :root-waiting (:root-waiting swarm)
                        :root-done (:root-done swarm)
                        :queued-count (if (and (= :running (:status swarm))
                                               (:completed? swarm))
                                        1
                                        0)
                        :last-event (get-in swarm [:last-event :type])
                        :master-version (:master-version state)
                        :swarm-master-version (:master-version swarm)}
                 (= :joined (:status swarm))
                 (assoc :report (:report swarm))

                 (:ff-commit swarm)
                 (assoc :ff-commit (:ff-commit swarm))

                 (:abandon-reason swarm)
                 (assoc :abandon-reason (:abandon-reason swarm))

                 (:failure swarm)
                 (assoc :error (:failure swarm))

                 (:recovery swarm)
                 (assoc :recovery (:recovery swarm)))))))

(defn swarm-result
  "The public join result for a joined swarm, or nil."
  [state swarm-id]
  (let [swarm (get-in state [:swarms swarm-id])]
    (when (and swarm (= :joined (:status swarm)))
      {:swarm-id swarm-id
       :slice-id (:slice-id swarm)
       :workspace (:workspace swarm)
       :report (:report swarm)
       :ff-commit (:ff-commit swarm)
       :rebase-rounds (:rebase-rounds swarm)})))

(defn terminal-swarms
  "Terminal swarms whose worktree has not been removed yet."
  [state]
  (->> (:swarms state)
       vals
       (filter #(and (contains? terminal-statuses (:status %))
                     (not (:worktree-removed? %))))
       (sort-by :created-seq)
       vec))

(defn managed-worktree?
  "True only when SWARM's workspace is the exact path this fleet allocated
  under its configured `:worktree-root`.

  GC deletes directories.  An exact path comparison against the path the
  fleet itself computed keeps a restored or hand-edited state from naming a
  shared path such as the master worktree, a user project, or another
  mission's directory."
  [state swarm]
  (and (string? (:worktree-root state))
       (map? swarm)
       (string? (:workspace swarm))
       (= (:workspace swarm) (worktree-path state (:swarm-id swarm)))))

(defn gc-candidates
  "Terminal, registered worktrees whose explicit retention deadline elapsed.

  A candidate must be a terminal swarm, must have been created by this fleet
  (its workspace equals the managed path), and must carry a real `:gc-after`
  deadline at or before NOW.  A missing deadline is not treated as expired,
  and a running or queued swarm is never eligible even if a caller corrupts
  its `:gc-after`.  A terminal swarm does not have to be `:worktree-ready?`;
  a legacy record with a managed path is still collectable."
  [state now]
  (if-not (integer? now)
    []
    (->> (terminal-swarms state)
         (filter (fn [swarm]
                   (and (integer? (:gc-after swarm))
                        (<= (long (:gc-after swarm)) (long now))
                        (managed-worktree? state swarm))))
         vec)))

(defn mark-worktree-removed
  [state swarm-id]
  (if (get-in state [:swarms swarm-id])
    {:ok? true
     :state (-> state
                (assoc-in [:swarms swarm-id :worktree-removed?] true)
                (assoc-in [:swarms swarm-id :gc-error] nil))}
    (fail state :UNKNOWN_SWARM "GC names an unknown swarm"
          {:swarm-id swarm-id})))

(defn mark-gc-error
  [state swarm-id error]
  (if (get-in state [:swarms swarm-id])
    {:ok? true
     :state (assoc-in state [:swarms swarm-id :gc-error] error)}
    (fail state :UNKNOWN_SWARM "GC names an unknown swarm"
          {:swarm-id swarm-id})))
