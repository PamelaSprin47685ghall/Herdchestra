(ns hc.compose.fleet.restore
  "Persistable fleet current state and restart recovery.

  The fleet service owns immutable control state plus one runtime per
  worktree.  Control state is plain EDN.  Each runtime's durable part is its
  swarm board (tasks, actors, claims, actions, token slot, waiters, inputs,
  ledger, requests, and a bounded event tail), its actor state (slot
  identities, private contexts, and active calls), and the last observable
  write-gate status.  The live write gate, workspace runtime, and model
  handles contain process-local objects and are never serialized; restore
  rebuilds them and records the old gate status as recovery evidence.

  A snapshot contains the complete authoritative control state: slices,
  swarms, the FIFO completion queue, completion sequence, master version,
  reports, and a bounded tail of the event log, plus `:runtimes`.  Version 1
  snapshots are still accepted for migration and are reported as legacy;
  they carry no runtime state.

  `snapshot` returns persistable control state, worktree descriptors, and
  runtime state.  `validate` checks the structural invariants that can be
  proven without Git, including every persisted board.  `recover-state`
  resolves control-level in-flight state: a running swarm whose runtime
  survived keeps running with an explicit `:outcome-unknown` recovery marker
  so its board and actors can be resumed; a running swarm without a
  recoverable runtime becomes an explicit `:outcome-unknown` failure instead
  of a permanent `:running` row.  `recover-runtimes` marks every side-effecting
  action and model attempt left by the old process as `:uncertain` /
  `:outcome-unknown`; it never replays them.  `restore-control!` validates,
  recovers, and only then replaces the control atom.

  `file-store` writes through a temporary file, fsync, and one atomic rename.
  A corrupt or unsupported state file is reported as an error; it is never
  treated as an empty fleet."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [hc.compose.fleet.actors :as actors]
            [hc.compose.fleet.runtime :as runtime]
            [hc.compose.fleet.state :as state]
            [hc.core.swarm-stm.board :as swarm-board])
  (:import (java.io FileOutputStream)
           (java.nio.channels FileChannel)
           (java.nio.charset StandardCharsets)
           (java.nio.file AtomicMoveNotSupportedException CopyOption Files LinkOption
                          OpenOption Path Paths StandardCopyOption StandardOpenOption)
           (java.nio.file.attribute FileAttribute PosixFilePermissions)))

(def snapshot-version
  "Version of the persisted snapshot envelope, not the fleet state version."
  2)

(def supported-snapshot-versions
  "Envelope versions accepted by `validate`.  Version 1 carried only control
  state and is upgraded to version 2 on the next successful write."
  #{1 2})

(def default-max-events
  "Persist only the recent event tail.  Events are append-only history, not
  current-state authority, and root AGENTS.md §1.1 allows trimming them."
  1000)

(def default-max-board-events
  "Bound each worktree board's append-only event tail the same way.  Task,
  actor, claim, action, token, waiter, input, ledger, and request records are
  current state and are never trimmed."
  1000)

(defprotocol SnapshotStore
  (load-snapshot [store]
    "Return `{:status :missing}`, `{:status :ok :snapshot snapshot}`, or
    `{:status :corrupt :error error}`.  Never return an empty snapshot for an
    unreadable file.")
  (save-snapshot! [store snapshot]
    "Durably replace the stored snapshot.  Return `{:ok? true}` or
    `{:ok? false :error error}`."))

(defn- fail
  [code message details]
  {:ok? false
   :error (cond-> {:code code :message message}
            (some? details) (assoc :details details))})

(defn- nonblank?
  [value]
  (and (string? value) (not (str/blank? value))))

(defn- non-negative-int?
  [value]
  (and (integer? value) (not (neg? value))))

(defn- state-of
  [service]
  (if (and (map? service) (:control service))
    @(get-in service [:control :state])
    (:state service)))

(defn- bounded-events
  [events max-events]
  (let [events (vec (or events []))]
    (if (and (integer? max-events)
             (not (neg? max-events))
             (> (count events) max-events))
      (vec (take-last max-events events))
      events)))

(defn- bounded-board-events
  [board max-events]
  (if (and (map? board)
           (vector? (:events board)))
    (assoc board :events (bounded-events (:events board) max-events))
    board))

(defn- runtime-map
  [service]
  (let [runtimes (:runtimes service)]
    (cond
      (instance? clojure.lang.IDeref runtimes) @runtimes
      (map? runtimes) runtimes
      :else {})))

(defn- runtime-board
  [rt]
  (or (:board rt)
      (when (:kernel rt)
        (runtime/board rt))))

(defn- runtime-actor-state
  [rt]
  (let [actor (:actor rt)]
    (cond
      (nil? actor) nil
      (and (map? actor) (contains? actor :ok?)) (:state actor)
      (map? actor) actor
      :else nil)))

(defn- runtime-write-gate-status
  [rt]
  (let [gate (:write-gate rt)]
    (cond
      (nil? gate) nil
      (and (map? gate) (contains? gate :held?)) gate
      :else (runtime/write-gate-status rt))))

(defn- runtime-snapshot
  [rt max-board-events]
  (when-let [board (runtime-board rt)]
    (cond-> {:board (bounded-board-events board max-board-events)}
      (runtime-write-gate-status rt)
      (assoc :write-gate (runtime-write-gate-status rt))

      (runtime-actor-state rt)
      (assoc :actor (actors/snapshot-state (runtime-actor-state rt)))

      (:recovery rt)
      (assoc :recovery (:recovery rt))

      (:write-gate-recovery rt)
      (assoc :write-gate-recovery (:write-gate-recovery rt)))))

(defn snapshot
  "Return persistable fleet state for SERVICE.

  `:state` is the immutable control state.  `:worktrees` is the real
  descriptor list needed to reattach runtimes; it does not contain atoms.
  `:runtimes` carries each worktree's board, actor state, and observable
  write-gate status; live gates, workspace runtimes, model handles, and
  effects are not serialized.  `:max-events` bounds only the append-only
  control event tail, never a swarm, queued report, completion sequence, or
  master version.  `:max-board-events` bounds each board's event tail."
  ([service] (snapshot service {}))
  ([service {:keys [max-events max-board-events]
             :or {max-events default-max-events
                  max-board-events default-max-board-events}}]
   (let [state (state-of service)
         state (assoc state :events (bounded-events (:events state) max-events))
         mission-id (or (:mission-id service) (:mission-id state))
         master-ref (or (:master-ref service) (:master-ref state))]
     {:fleet/snapshot-version snapshot-version
      :fleet/version (:version state)
      :mission-id mission-id
      :master-ref master-ref
      :state state
      :runtimes (into {}
                      (keep (fn [[swarm-id rt]]
                              (when-let [runtime (runtime-snapshot
                                                  rt max-board-events)]
                                [swarm-id runtime])))
                      (runtime-map service))
      :worktrees (->> (:swarms state)
                      vals
                      (sort-by :created-seq)
                      (mapv (fn [swarm]
                              {:swarm-id (:swarm-id swarm)
                               :slice-id (:slice-id swarm)
                               :workspace (:workspace swarm)
                               :branch (:branch swarm)
                               :status (:status swarm)
                               :worktree-removed? (boolean (:worktree-removed? swarm))})))})))

(defn- swarm-violations
  [state swarm-id swarm]
  (let [status (:status swarm)
        completed? (true? (:completed? swarm))
        terminal? (contains? state/terminal-statuses status)]
    (cond-> []
      (not (nonblank? (str swarm-id)))
      (conj {:swarm-id swarm-id :reason :invalid-swarm-id})

      (not (contains? state/statuses status))
      (conj {:swarm-id swarm-id :reason :invalid-status :status status})

      (not (contains? (:slices state) (:slice-id swarm)))
      (conj {:swarm-id swarm-id :reason :unknown-slice
             :slice-id (:slice-id swarm)})

      (not (nonblank? (str (:workspace swarm))))
      (conj {:swarm-id swarm-id :reason :missing-worktree})

      (not (boolean? (:worktree-ready? swarm)))
      (conj {:swarm-id swarm-id :reason :invalid-worktree-ready})

      (not (boolean? (:completed? swarm)))
      (conj {:swarm-id swarm-id :reason :invalid-completed-flag})

      (not (boolean? (:integrating? swarm)))
      (conj {:swarm-id swarm-id :reason :invalid-integrating-flag})

      (and (contains? swarm :join-intent)
           (not (boolean? (:join-intent swarm))))
      (conj {:swarm-id swarm-id :reason :invalid-join-intent-flag})

      (not (boolean? (:worktree-removed? swarm)))
      (conj {:swarm-id swarm-id :reason :invalid-worktree-removed-flag})

      (not (non-negative-int? (:created-seq swarm)))
      (conj {:swarm-id swarm-id :reason :invalid-created-seq
             :created-seq (:created-seq swarm)})

      (and (contains? #{:queued :running} status)
           (not (true? (:worktree-ready? swarm))))
      (conj {:swarm-id swarm-id :reason :active-without-ready-worktree})

      (and (contains? #{:queued :running} status)
           (true? (:worktree-removed? swarm)))
      (conj {:swarm-id swarm-id :reason :active-worktree-removed})

      (and (= :queued status) completed?)
      (conj {:swarm-id swarm-id :reason :queued-with-completion})

      (and completed? (not (string? (:report swarm))))
      (conj {:swarm-id swarm-id :reason :completed-without-report})

      (and completed? (not (non-negative-int? (:board-version swarm))))
      (conj {:swarm-id swarm-id :reason :completed-without-board-version
             :board-version (:board-version swarm)})

      (and completed? (not (non-negative-int? (:completion-seq swarm))))
      (conj {:swarm-id swarm-id :reason :completed-without-completion-seq
             :completion-seq (:completion-seq swarm)})

      (and (= :joined status) (not (nonblank? (:ff-commit swarm))))
      (conj {:swarm-id swarm-id :reason :joined-without-ff-commit})

      (and (= :joined status) (not completed?))
      (conj {:swarm-id swarm-id :reason :joined-without-completion})

      (and (= :abandoned status) (nil? (:abandon-reason swarm)))
      (conj {:swarm-id swarm-id :reason :abandoned-without-reason})

      (and (= :failed status) (nil? (:failure swarm)))
      (conj {:swarm-id swarm-id :reason :failed-without-error})

      (and terminal? (not (non-negative-int? (:finished-at swarm))))
      (conj {:swarm-id swarm-id :reason :terminal-without-finish-time
             :finished-at (:finished-at swarm)})

      (and terminal? (not (non-negative-int? (:gc-after swarm))))
      (conj {:swarm-id swarm-id :reason :terminal-without-gc-deadline
             :gc-after (:gc-after swarm)})

      (and (some? (:rebase-rounds swarm))
           (not (non-negative-int? (:rebase-rounds swarm))))
      (conj {:swarm-id swarm-id :reason :invalid-rebase-rounds
             :rebase-rounds (:rebase-rounds swarm)})

      (and (some? (:master-version swarm))
           (not (non-negative-int? (:master-version swarm))))
      (conj {:swarm-id swarm-id :reason :invalid-swarm-master-version
             :master-version (:master-version swarm)}))))

(defn- slice-index-violations
  [state]
  (let [slices (:slices state)
        slice->swarm (:slice->swarm state)
        swarms (:swarms state)]
    (cond
      (not= (set (keys slices)) (set (keys slice->swarm)))
      [{:reason :slice-index-keys
        :slices (vec (sort (map str (keys slices))))
        :index (vec (sort (map str (keys slice->swarm))))}]

      (not= (set (vals slice->swarm)) (set (keys swarms)))
      [{:reason :slice-index-values
        :index (vec (sort (map str (vals slice->swarm))))
        :swarms (vec (sort (map str (keys swarms))))}]

      :else
      (into []
            (concat
             (keep (fn [[slice-id swarm-id]]
                     (let [swarm (get swarms swarm-id)]
                       (when-not (and swarm
                                      (= slice-id (:slice-id swarm))
                                      (= swarm-id (get slice->swarm slice-id)))
                         {:reason :slice-index-mismatch
                          :slice-id slice-id
                          :swarm-id swarm-id})))
                   slice->swarm)
             (keep (fn [[swarm-id swarm]]
                     (when-not (= swarm-id (get slice->swarm (:slice-id swarm)))
                       {:reason :swarm-slice-index-mismatch
                        :swarm-id swarm-id
                        :slice-id (:slice-id swarm)}))
                   swarms))))))

(defn- queue-violations
  [state]
  (let [queue (:completion-queue state)
        swarms (:swarms state)
        entries (mapv #(get swarms %) queue)
        seqs (mapv :completion-seq entries)
        completed-running (->> swarms
                               vals
                               (filter #(and (= :running (:status %))
                                             (true? (:completed? %))))
                               (mapv :swarm-id)
                               set)
        all-int? (every? non-negative-int? seqs)]
    (cond-> []
      (some nil? entries)
      (conj {:reason :unknown-queue-swarm
             :swarm-ids (mapv first (remove second (map vector queue entries)))})

      (some #(not (and (= :running (:status %))
                       (true? (:completed? %))))
            (remove nil? entries))
      (conj {:reason :queue-entry-not-completed-running
             :swarm-ids (->> entries
                             (filter #(and % (not (and (= :running (:status %))
                                                       (true? (:completed? %))))))
                             (mapv :swarm-id))})

      (not all-int?)
      (conj {:reason :queue-entry-without-completion-seq
             :completion-seqs seqs})

      (and all-int?
           (not (every? true? (map < seqs (rest seqs)))))
      (conj {:reason :queue-order-not-fifo
             :completion-seqs seqs})

      (not= (set queue) completed-running)
      (conj {:reason :queue-set-mismatch
             :queue (vec queue)
             :completed-running (vec (sort completed-running))}))))

(defn- integrating-violations
  [state]
  (let [queue (:completion-queue state)
        head (first queue)]
    (vec
     (keep (fn [[swarm-id swarm]]
             (when (and (:integrating? swarm)
                        (not (and (= swarm-id head)
                                  (= :running (:status swarm))
                                  (true? (:completed? swarm)))))
               {:swarm-id swarm-id
                :reason :integrating-not-queue-head}))
           (:swarms state)))))

(defn- join-recovery-violations
  [state]
  (let [head (first (:completion-queue state))]
    (vec
     (keep (fn [[swarm-id swarm]]
             (when-let [recovery (:join-recovery swarm)]
               (when (or (not (true? (:git-merged? recovery)))
                         (not= swarm-id head)
                         (not= :running (:status swarm))
                         (not (true? (:completed? swarm)))
                         (not (and (string? (:ff-commit recovery))
                                   (not (str/blank? (:ff-commit recovery)))))
                         (not (and (integer? (:rebase-rounds recovery))
                                   (not (neg? (:rebase-rounds recovery))))))
                 {:swarm-id swarm-id
                  :reason :invalid-join-recovery
                  :recovery recovery})))
           (:swarms state)))))

(defn- worktree-violations
  [state worktrees]
  (let [swarms (:swarms state)
        ids (mapv :swarm-id worktrees)]
    (cond
      (not= (count ids) (count (distinct ids)))
      [{:reason :duplicate-worktree-descriptor
        :swarm-ids (vec ids)}]

      (not= (set ids) (set (keys swarms)))
      [{:reason :worktree-descriptor-set-mismatch
        :worktrees (vec (sort (map str ids)))
        :swarms (vec (sort (map str (keys swarms))))}]

      :else
      (into []
            (keep (fn [worktree]
                    (let [swarm (get swarms (:swarm-id worktree))]
                      (when-not (and swarm
                                     (= (:workspace worktree) (:workspace swarm))
                                     (= (:status worktree) (:status swarm))
                                     (= (boolean (:worktree-removed? worktree))
                                        (boolean (:worktree-removed? swarm))))
                        {:swarm-id (:swarm-id worktree)
                         :reason :worktree-descriptor-mismatch})))
                  worktrees)))))

(defn- board-violations
  [swarm-id board]
  (cond
    (not (map? board))
    [{:swarm-id swarm-id :reason :invalid-board}]

    (not (non-negative-int? (:board-version board)))
    [{:swarm-id swarm-id :reason :invalid-board-version
      :board-version (:board-version board)}]

    (not (non-negative-int? (:event-seq board)))
    [{:swarm-id swarm-id :reason :invalid-board-event-seq
      :event-seq (:event-seq board)}]

    (not (vector? (:events board)))
    [{:swarm-id swarm-id :reason :invalid-board-events}]

    :else
    (try
      (let [board (swarm-board/derive board)]
        (mapv (fn [violation]
                {:swarm-id swarm-id
                 :reason :invalid-board-invariant
                 :violation violation})
              (swarm-board/check-invariants board)))
      (catch Throwable t
        [{:swarm-id swarm-id
          :reason :invalid-board-shape
          :message (or (ex-message t) (str t))}]))))

(defn- actor-violations
  [swarm-id actor]
  (let [mission (:mission actor)
        actors (:actors actor)]
    (cond-> []
      (not (map? actor))
      (conj {:swarm-id swarm-id :reason :invalid-actor-state})

      (and (map? actor) (not= swarm-id (:swarm-id actor)))
      (conj {:swarm-id swarm-id :reason :actor-swarm-id-mismatch
             :actor-swarm-id (:swarm-id actor)})

      (and (map? actor) (not (nonblank? (str (:tenure-id actor)))))
      (conj {:swarm-id swarm-id :reason :actor-tenure-id-missing})

      (and (map? actor) (not (map? mission)))
      (conj {:swarm-id swarm-id :reason :actor-mission-missing})

      (and (map? mission) (not (map? (:models mission))))
      (conj {:swarm-id swarm-id :reason :actor-mission-models-missing})

      (and (map? actor) (not (map? actors)))
      (conj {:swarm-id swarm-id :reason :actor-runtime-missing})

      (and (map? actors) (not (map? (:tenures actors))))
      (conj {:swarm-id swarm-id :reason :actor-tenures-missing})

      (and (map? actors) (not (map? (:models actors))))
      (conj {:swarm-id swarm-id :reason :actor-models-missing})

      (and (map? actors) (not (map? (:calls actors))))
      (conj {:swarm-id swarm-id :reason :actor-calls-missing})

      (and (map? actors)
           (some (fn [[_ call]] (not (map? call))) (:calls actors)))
      (conj {:swarm-id swarm-id :reason :actor-call-shape})

      (and (map? actors)
           (some (fn [[_ tenure]]
                   (or (not (map? tenure))
                       (not (map? (:slots tenure)))
                       (some (fn [[_ slot]] (not (map? slot)))
                             (:slots tenure))))
                 (:tenures actors)))
      (conj {:swarm-id swarm-id :reason :actor-slot-shape}))))

(defn- write-gate-violations
  [swarm-id gate]
  (cond
    (not (map? gate))
    [{:swarm-id swarm-id :reason :invalid-write-gate-status}]

    (and (contains? gate :held?) (not (boolean? (:held? gate))))
    [{:swarm-id swarm-id :reason :invalid-write-gate-held
      :held? (:held? gate)}]

    (and (contains? gate :queue) (not (non-negative-int? (:queue gate))))
    [{:swarm-id swarm-id :reason :invalid-write-gate-queue
      :queue (:queue gate)}]

    :else []))

(defn- runtime-violations
  [snapshot]
  (let [runtimes (:runtimes snapshot)
        swarms (get-in snapshot [:state :swarms])
        legacy? (= 1 (:fleet/snapshot-version snapshot))]
    (cond
      (and (not legacy?) (not (map? runtimes)))
      [{:reason :invalid-runtimes}]

      (not (or (nil? runtimes) (map? runtimes)))
      [{:reason :invalid-runtimes}]

      :else
      (let [runtimes (or runtimes {})]
        (into []
              (concat
               (keep (fn [[swarm-id _]]
                       (when-not (contains? swarms swarm-id)
                         {:swarm-id swarm-id
                          :reason :runtime-for-unknown-swarm}))
                     runtimes)
               (mapcat
                (fn [[swarm-id runtime]]
                  (if-not (map? runtime)
                    [{:swarm-id swarm-id :reason :invalid-runtime}]
                    (concat
                     (board-violations swarm-id (:board runtime))
                     (when (contains? runtime :write-gate)
                       (write-gate-violations swarm-id (:write-gate runtime)))
                     (when (contains? runtime :actor)
                       (actor-violations swarm-id (:actor runtime))))))
                runtimes)))))))

(defn validate
  "Validate a persistable fleet snapshot.

  Checks the structural invariants that can be proven without Git: the
  snapshot envelope version, mission identity, complete swarm/slice index,
  FIFO completion order, master-version/joined agreement, and worktree
  descriptors.  Every persisted worktree board must satisfy the swarm board
  invariants.  It does not claim the worktrees still exist."
  [snapshot]
  (let [state (:state snapshot)
        swarms (:swarms state)
        slices (:slices state)
        slice->swarm (:slice->swarm state)
        queue (:completion-queue state)
        events (:events state)
        worktrees (:worktrees snapshot)
        envelope-version (:fleet/snapshot-version snapshot)
        legacy? (= 1 envelope-version)
        runtimes (:runtimes snapshot)]
    (cond
      (not (map? snapshot))
      (fail :INVALID_SNAPSHOT "snapshot must be a map" {})

      (not (contains? supported-snapshot-versions envelope-version))
      (fail :INVALID_SNAPSHOT_VERSION
            "snapshot envelope version is missing or unsupported"
            {:supported supported-snapshot-versions
             :actual envelope-version})

      (not (nonblank? (:mission-id snapshot)))
      (fail :INVALID_MISSION_ID "snapshot mission-id must be non-blank"
            {:mission-id (:mission-id snapshot)})

      (not (map? state))
      (fail :INVALID_FLEET_STATE "snapshot state must be a map" {})

      (not= (:fleet/version snapshot) (:version state))
      (fail :FLEET_VERSION_MISMATCH
            "snapshot fleet version must equal the state version"
            {:snapshot-version (:fleet/version snapshot)
             :state-version (:version state)})

      (not= (:mission-id snapshot) (:mission-id state))
      (fail :MISSION_ID_MISMATCH
            "snapshot and state mission ids differ"
            {:snapshot-mission-id (:mission-id snapshot)
             :state-mission-id (:mission-id state)})

      (not (nonblank? (:worktree-root state)))
      (fail :INVALID_WORKTREE_ROOT "state worktree-root must be non-blank"
            {:worktree-root (:worktree-root state)})

      (not (nonblank? (:master-ref state)))
      (fail :INVALID_MASTER_REF "state master-ref must be non-blank"
            {:master-ref (:master-ref state)})

      (not (non-negative-int? (:retention-ms state)))
      (fail :INVALID_RETENTION "state retention-ms must be a non-negative integer"
            {:retention-ms (:retention-ms state)})

      (not (non-negative-int? (:version state)))
      (fail :INVALID_VERSION "state version must be a non-negative integer"
            {:version (:version state)})

      (not (non-negative-int? (:event-seq state)))
      (fail :INVALID_EVENT_SEQ "state event-seq must be a non-negative integer"
            {:event-seq (:event-seq state)})

      (not (non-negative-int? (:completion-seq state)))
      (fail :INVALID_COMPLETION_SEQ
            "state completion-seq must be a non-negative integer"
            {:completion-seq (:completion-seq state)})

      (not (non-negative-int? (:next-swarm-seq state)))
      (fail :INVALID_NEXT_SWARM_SEQ
            "state next-swarm-seq must be a non-negative integer"
            {:next-swarm-seq (:next-swarm-seq state)})

      (not (and (integer? (:master-version state))
                (not (neg? (:master-version state)))))
      (fail :INVALID_MASTER_VERSION "master-version must be a non-negative integer"
            {:master-version (:master-version state)})

      (not (map? swarms))
      (fail :INVALID_SWARMS "fleet swarms must be a map" {})

      (not (map? slices))
      (fail :INVALID_SLICES "fleet slices must be a map" {})

      (not (map? slice->swarm))
      (fail :INVALID_SLICE_INDEX "fleet slice->swarm must be a map" {})

      (not (vector? queue))
      (fail :INVALID_COMPLETION_QUEUE "completion queue must be a vector" {})

      (not (vector? events))
      (fail :INVALID_EVENTS "fleet events must be a vector" {})

      (not (vector? worktrees))
      (fail :INVALID_WORKTREES "snapshot worktrees must be a vector" {})

      (not= (count queue) (count (distinct queue)))
      (fail :DUPLICATE_QUEUE_ENTRY "completion queue contains a duplicate"
            {:queue queue})

      (some #(not (contains? swarms %)) queue)
      (fail :UNKNOWN_QUEUE_SWARM "completion queue names an unknown swarm"
            {:swarm-ids (vec (remove #(contains? swarms %) queue))})

      (not= (:master-version state)
            (count (filter #(= :joined (:status %)) (vals swarms))))
      (fail :MASTER_VERSION_MISMATCH
            "master-version must equal the number of joined swarms"
            {:master-version (:master-version state)
             :joined (count (filter #(= :joined (:status %)) (vals swarms)))})

      :else
      (let [violations (into []
                             (concat
                              (slice-index-violations state)
                              (queue-violations state)
                              (integrating-violations state)
                              (join-recovery-violations state)
                              (mapcat (fn [[swarm-id swarm]]
                                        (swarm-violations state swarm-id swarm))
                                      swarms)
                              (worktree-violations state worktrees)
                              (runtime-violations snapshot)))]
        (if (seq violations)
          (fail :INVALID_SWARM_STATE "fleet snapshot contains an invalid state"
                {:violations violations})
          {:ok? true
           :mission-id (:mission-id snapshot)
           :state state
           :runtimes (or runtimes {})
           :runtime-count (count (or runtimes {}))
           :legacy? legacy?
           :reattach (->> swarms
                          vals
                          (filter #(and (= :queued (:status %))
                                        (not (:worktree-removed? %))))
                          (sort-by :created-seq)
                          (mapv :swarm-id))
           :joinable (vec queue)
           :terminal (->> swarms
                          vals
                          (filter #(contains? state/terminal-statuses (:status %)))
                          (sort-by :created-seq)
                          (mapv :swarm-id))
           :uncertain (->> swarms
                           vals
                           (filter #(and (= :running (:status %))
                                         (not (:completed? %))))
                           (sort-by :created-seq)
                           (mapv :swarm-id))
           :resumable (->> swarms
                           vals
                           (filter #(and (= :running (:status %))
                                         (not (:completed? %))
                                         (not (:worktree-removed? %))
                                         (contains? (or runtimes {}) (:swarm-id %))))
                           (sort-by :created-seq)
                           (mapv :swarm-id))
           :unrestorable (->> swarms
                              vals
                              (filter :worktree-removed?)
                              (sort-by :created-seq)
                              (mapv :swarm-id))})))))

(defn restore-plan
  "Validate SNAPSHOT and return what a restore supervisor must reattach.

  It never mutates SERVICE.  `:reattach` names queued swarms whose worktree
  should get a runtime; `:joinable` names completed results waiting in the
  FIFO queue; `:resumable` names running swarms whose runtime snapshot can
  keep them running with `:outcome-unknown` evidence; `:uncertain` names
  every running swarm whose in-flight result cannot be confirmed;
  `:unrestorable` names swarms whose worktree was already GC'd."
  [service snapshot]
  (let [result (validate snapshot)]
    (if-not (:ok? result)
      result
      (let [state (:state result)
            service-state (state-of service)
            service-mission (or (:mission-id service) (:mission-id service-state))
            service-root (or (:worktree-root service) (:worktree-root service-state))
            service-master (or (:master-ref service) (:master-ref service-state))
            mismatch (cond-> []
                       (not= service-mission (:mission-id snapshot))
                       (conj {:field :mission-id
                              :service service-mission
                              :snapshot (:mission-id snapshot)})

                       (not= service-root (:worktree-root state))
                       (conj {:field :worktree-root
                              :service service-root
                              :snapshot (:worktree-root state)})

                       (not= service-master (:master-ref state))
                       (conj {:field :master-ref
                              :service service-master
                              :snapshot (:master-ref state)}))]
        (if (seq mismatch)
          (fail :FLEET_STATE_MISMATCH
                "the persisted fleet belongs to a different mission or worktree root"
                {:mismatches mismatch})
          (assoc result
                 :service-mission-id service-mission
                 :snapshot-mission-id (:mission-id snapshot)))))))

(defn- worktree-present?
  [workspace]
  (let [dir (io/file (str workspace))]
    (and (.isDirectory dir)
         (.exists (io/file dir ".git")))))

(defn- outcome-unknown-failure
  [swarm now]
  {:code :SWARM_IN_FLIGHT_OUTCOME_UNKNOWN
   :message
   "the fleet process restarted while this swarm was running; its in-flight outcome cannot be confirmed"
   :swarm-id (:swarm-id swarm)
   :workspace (:workspace swarm)
   :outcome-unknown? true
   :phase :running
   :recovery {:state :outcome-unknown
              :reason :process-restart
              :observed-status :running
              :recovered-at now}})

(defn- missing-worktree-failure
  [swarm now queued-result?]
  {:code :SWARM_WORKTREE_MISSING
   :message
   "the swarm worktree no longer exists; the retained result cannot be recovered"
   :swarm-id (:swarm-id swarm)
   :workspace (:workspace swarm)
   :queued-result? queued-result?
   :outcome-unknown? (not queued-result?)
   :recovery {:state :unrestorable
              :reason :worktree-missing
              :recovered-at now}})

(defn- append-board-events
  [board events]
  (reduce (fn [b event]
            (let [n (inc (:event-seq b))
                  committed (assoc event
                                   :event-seq n
                                   :at-version (:board-version b))]
              (-> b
                  (assoc :event-seq n)
                  (update :events (fnil conj []) committed))))
          board
          events))

(defn- board-recovery-events
  [actions uncertain-ids abandoned-ids woken]
  (concat
   (map (fn [action-id]
          (let [action (get actions action-id)]
            {:type :action-uncertain
             :action-id action-id
             :task-id (:task-id action)
             :agent-id (:agent-id action)
             :previous-phase (:phase action)
             :reason :process-restart}))
        uncertain-ids)
   (map (fn [action-id]
          (let [action (get actions action-id)]
            {:type :action-abandoned
             :action-id action-id
             :task-id (:task-id action)
             :agent-id (:agent-id action)
             :previous-phase (:phase action)
             :reason :process-restart}))
        abandoned-ids)
   (when (seq woken)
     [{:type :wake-all
       :reasons #{:process-restart}
       :woken woken}])))

(defn recover-board
  "Recover one persisted swarm board after a process restart.

  Actions that reached a side effect (`:applying` or `:running`) become
  `:uncertain` with explicit `:outcome-unknown` evidence; they are never
  replayed.  Actions that had not reached a side effect are removed.  A
  persisted `:uncertain` action stays uncertain.  Park registrations are
  cleared because the old process's parked continuations are gone, and one
  `:wake-all` event gives every former waiter a fresh observation.

  Returns `{:ok? true :board ... :changed? ... :uncertain-actions ...
  :abandoned-actions ... :cleared-waiters ...}` or an explicit error.  This
  function is pure."
  [board now]
  (let [pre-phases #{:claimed :preparing :waiting-gate}
        uncertain-phases #{:applying :running}
        actions (:actions board)]
    (cond
      (not (map? board))
      (fail :INVALID_BOARD "runtime board must be a map" {})

      (not (non-negative-int? (:board-version board)))
      (fail :INVALID_BOARD_VERSION
            "runtime board version must be a non-negative integer"
            {:board-version (:board-version board)})

      (not (non-negative-int? (:event-seq board)))
      (fail :INVALID_BOARD_EVENT_SEQ
            "runtime board event-seq must be a non-negative integer"
            {:event-seq (:event-seq board)})

      (not (map? actions))
      (fail :INVALID_BOARD_ACTIONS "runtime board actions must be a map" {})

      :else
      (let [abandoned-ids (->> actions
                               (keep (fn [[action-id action]]
                                       (when (contains? pre-phases (:phase action))
                                         action-id)))
                               vec)
            uncertain-ids (->> actions
                               (keep (fn [[action-id action]]
                                       (when (contains? uncertain-phases
                                                        (:phase action))
                                         action-id)))
                               vec)
            version (inc (:board-version board))
            actions' (reduce
                      (fn [m action-id]
                        (let [action (get m action-id)]
                          (assoc m action-id
                                 (-> action
                                     (assoc :phase :uncertain)
                                     (assoc :recovery
                                            {:state :outcome-unknown
                                             :reason :process-restart
                                             :previous-phase (:phase action)
                                             :recovered-at now})
                                     (assoc :updated-version version)))))
                      actions
                      uncertain-ids)
            actions' (apply dissoc actions' abandoned-ids)
            actors' (reduce
                     (fn [actors [agent-id actor]]
                       (let [active-id (:active-action-id actor)
                             active (get actions' active-id)]
                         (if (and active-id
                                  (not (and active
                                            (not= :finished (:phase active)))))
                           (assoc actors agent-id
                                  (assoc actor :active-action-id nil))
                           actors)))
                     (:actors board)
                     (:actors board))
            waiters (:waiters board)
            woken (set (keys waiters))
            changed? (boolean (or (seq abandoned-ids)
                                  (seq uncertain-ids)
                                  (seq waiters)))
            events (board-recovery-events actions uncertain-ids
                                          abandoned-ids woken)
            board' (cond-> board
                     true
                     (assoc :actions actions' :actors actors')

                     (seq waiters)
                     (assoc :waiters {}
                            :last-wake {:reasons #{:process-restart}
                                        :woken woken
                                        :at-version version})

                     changed?
                     (assoc :board-version version)

                     (seq events)
                     (append-board-events events))
            board' (swarm-board/derive board')
            violations (try
                         (swarm-board/check-invariants board')
                         (catch Throwable t
                           [{:invariant :board-recovery-check-failed
                             :message (or (ex-message t) (str t))}]))]
        (if (seq violations)
          (fail :INVALID_BOARD_STATE
                "the persisted worktree board is not structurally valid"
                {:violations violations})
          {:ok? true
           :board board'
           :changed? changed?
           :uncertain-actions uncertain-ids
           :abandoned-actions abandoned-ids
           :cleared-waiters woken})))))

(defn- write-gate-recovery
  [gate now]
  (when (map? gate)
    (let [held? (true? (:held? gate))
          queued (long (or (:queue gate) 0))]
      (when (or held? (pos? queued))
        {:previous (select-keys gate [:swarm-id :held? :owner :queue])
         :held? held?
         :owner (:owner gate)
         :queue queued
         :outcome-unknown? held?
         :queue-abandoned? (pos? queued)
         :reason :process-restart
         :recovered-at now}))))

(defn recover-runtimes
  "Recover every persisted worktree runtime.

  Each board is passed through `recover-board`.  The old write gate's
  observable status is retained as evidence; the live gate is not restored
  because a process-local semaphore cannot survive a restart.  Returns
  `{:ok? true :runtimes {swarm-id {:board ... :actor ... :recovery ...
  :write-gate-recovery ...}}}` or an explicit error.  It never dispatches a
  model turn, tool call, or write."
  [snapshot {:keys [now] :or {now 0}}]
  (let [runtimes (or (:runtimes snapshot) {})]
    (loop [remaining (seq runtimes)
           recovered {}
           summaries []]
      (if-let [[swarm-id runtime] (first remaining)]
        (let [board-result (recover-board (:board runtime) now)]
          (if-not (:ok? board-result)
            (fail :FLEET_RUNTIME_STATE_INVALID
                  "a persisted worktree runtime cannot be recovered"
                  {:swarm-id swarm-id
                   :error (:error board-result)})
            (let [gate-recovery
                  ;; `restore-control!` can persist the recovered runtime
                  ;; snapshots before a live gate exists.  In that window the
                  ;; old gate evidence is the only record; do not drop it on
                  ;; the next restart.
                  (if (contains? runtime :write-gate)
                    (write-gate-recovery (:write-gate runtime) now)
                    (:write-gate-recovery runtime))
                  recovery {:board (select-keys board-result
                                                [:changed? :uncertain-actions
                                                 :abandoned-actions
                                                 :cleared-waiters])
                            :write-gate gate-recovery}
                  entry (cond-> {:board (:board board-result)
                                 :recovery recovery}
                          (contains? runtime :actor)
                          (assoc :actor (:actor runtime))

                          gate-recovery
                          (assoc :write-gate-recovery gate-recovery))]
              (recur (next remaining)
                     (assoc recovered swarm-id entry)
                     (conj summaries
                           (assoc recovery :swarm-id swarm-id))))))
        {:ok? true
         :runtimes recovered
         :summaries summaries}))))

(defn recover-state
  "Resolve every in-flight state that a process restart cannot confirm.

  A running swarm without a completed result whose runtime snapshot and
  worktree survived stays `:running` and receives a
  `:recovery {:state :outcome-unknown ...}` marker.  Its board and actors
  are restored by `recover-runtimes`; the service resumes it with an
  explicit instruction to verify the worktree instead of replaying an
  action.  A running swarm without a recoverable runtime becomes `:failed`
  with `:SWARM_IN_FLIGHT_OUTCOME_UNKNOWN` and `:outcome-unknown? true`; its
  queued dependents fail through the normal dependency propagation.  A
  running swarm whose result is already in the FIFO queue stays joinable.
  An active swarm whose worktree disappeared fails explicitly.  A terminal
  swarm whose worktree is already gone is marked removed.  `:integrating?`
  is always cleared because the old process's Git lock and rebase are gone;
  a durable `:join-recovery` marker stays on the queue head so a real
  fast-forward is finalized without repeating Git or starting dependents
  twice.

  This function is pure.  WORKTREE-EXISTS? receives a swarm map and returns
  true when its worktree is still present.  RUNTIME-IDS is the set of
  swarms with a persisted runtime snapshot."
  [state {:keys [now worktree-exists? runtime-ids]
          :or {now 0
               worktree-exists? (fn [swarm]
                                  (worktree-present? (:workspace swarm)))
               runtime-ids #{}}}]
  (let [original state
        runtime-ids (set runtime-ids)
        integrating (->> (:swarms state)
                         (keep (fn [[swarm-id swarm]]
                                 (when (:integrating? swarm) swarm-id)))
                         vec)
        cleared (update state :swarms
                        (fn [swarms]
                          (into {}
                                (map (fn [[swarm-id swarm]]
                                       [swarm-id
                                        (cond-> (assoc swarm :integrating? false)
                                          (:integrating? swarm)
                                          (assoc :join-intent true))]))
                                swarms)))
        swarms (:swarms cleared)
        running-unconfirmed (->> swarms
                                 vals
                                 (filter #(and (= :running (:status %))
                                               (not (:completed? %))))
                                 (sort-by :created-seq)
                                 (mapv :swarm-id))
        missing-active (->> swarms
                            vals
                            (filter #(and (contains? #{:queued :running} (:status %))
                                          (not (worktree-exists? %))
                                          ;; A verified fast-forward already
                                          ;; lives in master; its queued
                                          ;; result stays joinable even when
                                          ;; the worktree is gone.
                                          (not (true? (get-in % [:join-recovery
                                                                  :git-merged?])))))
                            (sort-by :created-seq)
                            (mapv :swarm-id))
        missing-set (set missing-active)
        resumable (->> running-unconfirmed
                       (remove missing-set)
                       (filter #(contains? runtime-ids %))
                       vec)
        resumable-set (set resumable)
        uncertain (->> running-unconfirmed
                       (remove resumable-set)
                       (remove missing-set)
                       vec)
        terminal-missing (->> swarms
                              vals
                              (filter #(and (contains? state/terminal-statuses (:status %))
                                            (not (:worktree-removed? %))
                                            (not (worktree-exists? %))))
                              (sort-by :created-seq)
                              (mapv :swarm-id))
        to-fail (vec (distinct (concat uncertain missing-active)))
        uncertain-set (set uncertain)
        cleared (reduce (fn [s swarm-id]
                          (assoc-in s [:swarms swarm-id :recovery]
                                    {:state :outcome-unknown
                                     :reason :process-restart
                                     :observed-status :running
                                     :resumed? true
                                     :recovered-at now}))
                        cleared
                        resumable)
        recovered (loop [remaining to-fail
                         current cleared
                         failed-uncertain []
                         failed-missing []
                         errors []]
                    (if-let [swarm-id (first remaining)]
                      (let [swarm (get-in current [:swarms swarm-id])
                            queued-result? (true? (:completed? swarm))
                            failure (if (contains? missing-set swarm-id)
                                      (missing-worktree-failure
                                       swarm now queued-result?)
                                      (outcome-unknown-failure swarm now))
                            result (state/fail-swarm current swarm-id failure
                                                     {:now now :force? true})]
                        (if (:ok? result)
                          (recur (rest remaining)
                                 (assoc-in (:state result)
                                           [:swarms swarm-id :recovery]
                                           (:recovery failure))
                                 (if (contains? uncertain-set swarm-id)
                                   (conj failed-uncertain swarm-id)
                                   failed-uncertain)
                                 (if (contains? uncertain-set swarm-id)
                                   failed-missing
                                   (conj failed-missing swarm-id))
                                 errors)
                          (recur (rest remaining) current
                                 failed-uncertain failed-missing
                                 (conj errors {:swarm-id swarm-id
                                               :error (:error result)}))))
                      {:state current
                       :failed-uncertain failed-uncertain
                       :failed-missing failed-missing
                       :errors errors}))
        after-failures (:state recovered)
        after-failures (reduce (fn [s swarm-id]
                                 (:state (state/mark-worktree-removed s swarm-id)))
                               after-failures
                               terminal-missing)
        abort-rebase (->> integrating
                          (filter (fn [swarm-id]
                                    (let [swarm (get-in after-failures [:swarms swarm-id])]
                                      (and swarm
                                           (= :running (:status swarm))
                                           (true? (:completed? swarm))
                                           (worktree-exists? swarm)))))
                          vec)]
    {:ok? true
     :state after-failures
     :changed? (not= original after-failures)
     :cleared-integrating integrating
     :resumed resumable
     ;; Historical name: every running swarm whose in-flight result the
     ;; restart could not confirm, whether resumed or failed explicitly.
     :recovered (vec (concat resumable (:failed-uncertain recovered)))
     :failed-uncertain (:failed-uncertain recovered)
     :missing (:failed-missing recovered)
     :terminal-removed terminal-missing
     :abort-rebase abort-rebase
     :errors (:errors recovered)}))

(defn restore-control!
  "Validate SNAPSHOT, recover in-flight state, and replace SERVICE's control
  atom with the recovered state.

  Clears the service runtime map because those runtimes contain live atoms
  and cannot be restored from EDN.  Returns the reattach plan plus recovered
  runtime snapshots under `:runtimes`; the caller must rebuild each live
  runtime and reattach its actors before serving work.  `:legacy? true`
  means the input was a version-1 control-only snapshot; it carries no
  runtime state to recover.

  PERSIST! is called with the recovered state before the control atom is
  replaced.  When SERVICE has a live runtime atom, it is temporarily
  populated with the recovered runtime snapshots so PERSIST! cannot erase
  the boards/actors it just recovered; it is cleared again before return.
  A persistence failure returns
  `:FLEET_RECOVERY_PERSIST_FAILED` with the underlying error under
  `[:error :details :error]` and the recovered candidate under
  `:recovered-state`; the live control is untouched."
  ([service snapshot]
   (restore-control! service snapshot {}))
  ([service snapshot {:keys [now worktree-exists? persist!]
                      :or {now 0
                           worktree-exists? (fn [swarm]
                                              (worktree-present? (:workspace swarm)))}}]
  (let [plan (restore-plan service snapshot)]
     (if-not (:ok? plan)
       plan
       (let [runtime-result (recover-runtimes snapshot {:now now})]
         (if-not (:ok? runtime-result)
           runtime-result
           (let [recovered (recover-state
                            (:state plan)
                            {:now now
                             :worktree-exists? worktree-exists?
                             :runtime-ids (set (keys (:runtimes runtime-result)))})
                 state' (:state recovered)
                 runtimes-atom (:runtimes service)
                 publish!
                 (fn []
                   (when (instance? clojure.lang.IAtom runtimes-atom)
                     ;; The recovery write must not erase the runtime state it
                     ;; just recovered.  PERSIST! sees the recovered snapshots,
                     ;; not the empty live map that will be rebuilt afterwards.
                     (reset! runtimes-atom (:runtimes runtime-result)))
                   (let [saved (if persist!
                                 (try
                                   (persist! state')
                                   (catch Throwable t
                                     (fail :FLEET_RECOVERY_PERSIST_FAILED
                                           "the recovered fleet state could not be persisted"
                                           {:message (or (.getMessage t) (str t))})))
                                 {:ok? true})]
                     (if-not (:ok? saved)
                       (do
                         (when (instance? clojure.lang.IAtom runtimes-atom)
                           (reset! runtimes-atom {}))
                         (assoc (fail :FLEET_RECOVERY_PERSIST_FAILED
                                      "the recovered fleet state could not be persisted"
                                      {:error (:error saved)})
                                :recovered-state state'))
                       (do
                         (reset! (get-in service [:control :state]) state')
                         (when (instance? clojure.lang.IAtom runtimes-atom)
                           (reset! runtimes-atom {}))
                         {:ok? true
                          :state state'
                          :runtimes (:runtimes runtime-result)
                          :runtime-recovery (:summaries runtime-result)
                          :legacy? (:legacy? plan)
                          :reattach (->> (:swarms state')
                                         vals
                                         (filter #(and (= :queued (:status %))
                                                       (not (:worktree-removed? %))))
                                         (sort-by :created-seq)
                                         (mapv :swarm-id))
                          :joinable (:completion-queue state')
                          :resumed (:resumed recovered)
                          :failed-uncertain (:failed-uncertain recovered)
                          ;; `:uncertain` keeps its historical meaning: running
                          ;; swarms that could not be resumed and became
                          ;; explicit failures.
                          :uncertain (:failed-uncertain recovered)
                          :missing (:missing recovered)
                          :terminal-removed (:terminal-removed recovered)
                          :abort-rebase (:abort-rebase recovered)
                          :cleared-integrating (:cleared-integrating recovered)
                          :changed? (:changed? recovered)
                          :recovery-errors (:errors recovered)
                          :persisted? true}))))
                 monitor (get-in service [:control :monitor])]
             (if monitor
               (locking monitor (publish!))
               (publish!)))))))))

;; ---------------------------------------------------------------------------
;; Durable snapshot store

(defn- fsync-directory!
  [^Path dir]
  (try
    (with-open [channel (FileChannel/open
                         dir
                         (into-array OpenOption [StandardOpenOption/READ]))]
      (.force channel true))
    (catch Exception _ nil)))

(defn- write-file!
  [^Path path ^bytes bytes]
  (with-open [out (FileOutputStream. (.toFile path))]
    (.write out bytes)
    (.flush out)
    (.sync (.getFD out)))
  (try
    (Files/setPosixFilePermissions
     path
     (PosixFilePermissions/fromString "rw-------"))
    (catch UnsupportedOperationException _ nil)
    (catch Exception _ nil)))

(defrecord FileStore [^Path path lock]
  SnapshotStore
  (load-snapshot [_]
    (if-not (Files/exists path (make-array LinkOption 0))
      {:status :missing}
      (try
        (let [bytes (Files/readAllBytes path)
              text (String. ^bytes bytes StandardCharsets/UTF_8)
              value (edn/read-string
                     {:default (fn [tag _]
                                 (throw (ex-info "unknown tagged literal"
                                                 {:tag tag})))}
                     text)]
          (if (map? value)
            {:status :ok :snapshot value}
            {:status :corrupt
             :error {:code :FLEET_STATE_CORRUPT
                     :message "the fleet state file does not contain a map"
                     :path (str path)
                     :value-type (some-> value class str)}}))
        (catch Throwable t
          {:status :corrupt
           :error {:code :FLEET_STATE_CORRUPT
                   :message (or (ex-message t) (str t))
                   :path (str path)}}))))

  (save-snapshot! [_ snapshot]
    (locking lock
      (let [parent (.getParent path)]
        (try
          (when parent
            (Files/createDirectories parent (make-array FileAttribute 0)))
          (let [temp (Files/createTempFile
                      parent ".fleet-state-" ".edn"
                      (make-array FileAttribute 0))
                text (binding [*print-length* nil
                               *print-level* nil]
                       (pr-str snapshot))
                bytes (.getBytes ^String text StandardCharsets/UTF_8)]
            (try
              (write-file! temp bytes)
              (try
                (Files/move temp path
                            (into-array CopyOption
                                        [StandardCopyOption/ATOMIC_MOVE
                                         StandardCopyOption/REPLACE_EXISTING]))
                (catch AtomicMoveNotSupportedException e
                  (throw (ex-info
                          "the filesystem cannot publish the fleet state atomically"
                          {:code :FLEET_STATE_ATOMIC_MOVE_UNSUPPORTED
                           :path (str path)}
                          e))))
              (fsync-directory! parent)
              {:ok? true :path (str path)}
              (catch Throwable t
                (try (Files/deleteIfExists temp) (catch Throwable _ nil))
                (throw t))))
          (catch Throwable t
            {:ok? false
             :error {:code (or (:code (ex-data t)) :FLEET_STATE_WRITE_FAILED)
                     :message (or (ex-message t) (str t))
                     :path (str path)}}))))))

(defn file-store
  "Build a durable EDN snapshot store at PATH.

  Writes use a temporary file in the same directory, fsync the file and the
  directory, and publish with one atomic rename.  A filesystem without
  atomic moves fails explicitly instead of downgrading to a partial write."
  [path]
  (when-not (nonblank? (str path))
    (throw (ex-info "fleet state path must be non-blank"
                    {:error :INVALID_STATE_FILE :path path})))
  (->FileStore (-> (Paths/get (str path) (make-array String 0))
                   (.toAbsolutePath)
                   (.normalize))
               (Object.)))

(defn load!
  "Read STORE without interpreting fleet state.  See `load-snapshot`."
  [store]
  (try
    (let [result (load-snapshot store)]
      (if (and (map? result)
               (contains? #{:missing :ok :corrupt} (:status result)))
        result
        {:status :corrupt
         :error {:code :FLEET_STATE_CORRUPT
                 :message "the snapshot store returned an invalid result"}}))
    (catch Throwable t
      {:status :corrupt
       :error {:code :FLEET_STATE_CORRUPT
               :message (or (ex-message t) (str t))}})))

(defn save!
  "Validate SNAPSHOT, then durably replace STORE's snapshot.

  Validation happens before any file is touched.  A store must never accept
  a state that a restore would reject.  Only the current envelope version is
  written; a loaded version-1 snapshot is upgraded by building a fresh
  version-2 snapshot."
  [store snapshot]
  (if-not (= snapshot-version (:fleet/snapshot-version snapshot))
    {:ok? false
     :error {:code :FLEET_STATE_INVALID
             :cause {:code :INVALID_SNAPSHOT_VERSION
                     :message "only the current snapshot envelope can be written"
                     :supported snapshot-version
                     :actual (:fleet/snapshot-version snapshot)}}}
    (let [validated (validate snapshot)]
      (if-not (:ok? validated)
        {:ok? false
         :error (assoc (:error validated)
                       :code :FLEET_STATE_INVALID
                       :cause (:error validated))}
        (try
          (let [result (save-snapshot! store snapshot)]
            (if (and (map? result) (:ok? result))
              {:ok? true :path (:path result)}
              {:ok? false
               :error (or (and (map? result) (:error result))
                          {:code :FLEET_STATE_WRITE_FAILED
                           :message "the snapshot store rejected the write"})}))
          (catch Throwable t
            {:ok? false
             :error {:code :FLEET_STATE_WRITE_FAILED
                     :message (or (ex-message t) (str t))}}))))))
