(ns hc.compose.owner.state
  "Initial committed state for one Owner.

  The state is plain EDN.  It contains no thread, task, socket, or store
  handle.  The Owner commits it as one document; `hc.compose.owner.store`
  defines the compare-and-set boundary."
  (:require [clojure.string :as str]
            [hc.compose.cancel :as cancel]
            [hc.compose.cancel.model :as model]
            [hc.compose.mission :as mission]
            [hc.compose.mission.actors :as actors]
            [hc.compose.owner.commands :as commands]
            [hc.compose.reports.acceptance :as acceptance]
            [hc.compose.reports.delivery :as delivery]
            [hc.compose.retry.planner :as retry]
            [hc.compose.retry.policy :as retry-policy]
            [hc.core.relay :as relay]
            [hc.core.scope.admission :as scope-admission]
            [hc.core.scope.ledger :as scope-ledger]
            [hc.core.scope.projection :as scope-projection]
            [hc.core.scope.runtime :as scope-runtime]))

(def default-config
  {:max-tool-concurrency 1
   :swarm-slots [:slot-a :slot-b]})

(def report-retry-base-ms
  "First redelivery delay after a report sink failure."
  250)

(def report-retry-max-ms
  "Upper bound for the report redelivery delay.  Delivery never becomes
  terminal on a sink failure; the delay only prevents a busy loop."
  5000)

(def report-ack-timeout-ms
  "How long a successful sink delivery waits for the parent acknowledgement
  before the child redelivers.  The parent returns the stored ack again."
  5000)

(defn report-retry-delay
  "Exponential redelivery delay for ATTEMPTS, capped at the maximum."
  [attempts]
  (let [attempts (long (max 0 (or attempts 0)))
        shift (min attempts 5)]
    (min report-retry-max-ms
         (bit-shift-left report-retry-base-ms shift))))

(def default-models
  "Placeholder model ids for tests and ephemeral Owners.

  A real session must pass `:models {:main <id> :task <id>}`.  The mission
  host records both the model class and the concrete id."
  {:main "main-model" :task "task-model"})

(defn project
  "Derive the `:relay` and `:board` projections from `:mission`.

  `:mission` is authoritative.  The projections are a convenience for
  guards and adapters that need the sole execution tenure's board.  They are
  recomputed in the same Owner transition, never written independently."
  [state]
  (let [mission-state (:mission state)
        relay (or (mission/relay-of mission-state) (:relay state))
        active (filter #(= :active (:status %))
                       (vals (relay/execution-tenures relay)))
        tenure (when (= 1 (count active)) (first active))
        board (when tenure
                (mission/board-of mission-state (:tenure-id tenure)))]
    (assoc state :relay relay :board board)))

(defn with-mission
  "Replace mission state and refresh both projections."
  [state mission-state]
  (-> state
      (assoc :mission mission-state)
      ;; A mission transition can change relay readiness.  A previous
      ;; barrier rejection must not suppress the guard forever.
      (assoc :relay-barrier-rejections {})
      project))

(defn restore
  "Validate a committed Owner state through the mission restore contract.

  Scope is cold-started: records and book history stay, but the retention
  budget and the in-memory turn are cleared.

  Returns `{:ok? true :state state}` or `{:ok? false :error ...}`."
  ([state] (restore state {}))
  ([state opts]
  (let [result (mission/restore state opts)]
    (if (:ok? result)
      (let [state (with-mission state (:state result))
            state (if (map? (:actors state))
                    state
                    (assoc state :actors
                           (actors/new-state
                            {:models (get-in state [:mission :models])
                             :slot-ids (get-in state [:config :swarm-slots]
                                               [:slot-a :slot-b])
                             :workspace-root (get-in state [:config :workspace-root])
                             :workspaces (get-in state [:config :swarm-workspaces]
                                                 {})})))
            state (assoc state :scope
                         (scope-runtime/cold-start-state (:scope state)))
            ;; Presence is connection-scoped, not business state.  A restart
            ;; has no live clients, so a stale controller id must not fence
            ;; the first client that reconnects.  The next attach (or the
            ;; first session command) establishes control again.
            state (assoc state :controller-id nil :observer-ids [])]
        {:ok? true :state state})
      {:ok? false :error (:error result)}))))

(defn initial-state
  "Build the first committed state for SESSION-ID.

  Options:

    :session-id       non-blank string
    :models           `{:main <id> :task <id>}`; defaults are test-only
    :user-input       optional first user text
    :user-attachments optional user attachment reference metadata
    :draft            optional `{:path \"plans/<name>.md\" :content \"...\"}`
    :successor-hint   optional successor hint
    :plan-milestone   `:S1` (default), `:S2`, `:S3`, or `:pending`
    :retry-policy     retry policy map, normalized here
    :config           extra config merged over `default-config`
    :now              epoch ms
    :registry         command registry, default `local-registry`
    :initial-session  optional session metadata map
    :initial-conversation optional conversation entries projected from the
                      session's visible history; used when the session
                      already carries messages (summary or branch sessions)
    :owner-instance-id stable process identity used to prove a persisted
                      draft claim belongs to a dead continuation"
  [{:keys [session-id models user-input user-attachments draft successor-hint
           plan-milestone retry-policy config now registry initial-session
           owner-instance-id initial-conversation]
    :or {plan-milestone :S1
         config {}
         now (System/currentTimeMillis)}}]
  (when-not (and (string? session-id) (not (str/blank? session-id)))
    (throw (ex-info "session-id must be a non-blank string"
                    {:code :INVALID_SESSION_ID :session-id session-id})))
  (let [policy-result (retry-policy/normalize-policy (or retry-policy {}))]
    (when-not (:ok? policy-result)
      (throw (ex-info "invalid retry policy"
                      {:code :INVALID_RETRY_POLICY
                       :error (:error policy-result)})))
    (let [registry (or registry (commands/local-registry))
          mission-state
          (mission/initial-state
           {:session-id session-id
            :models (or models default-models)
            :user-input user-input
            :user-attachments user-attachments
            :draft draft
            :successor-hint successor-hint
            :plan-milestone plan-milestone
            :owner-instance-id owner-instance-id})
          state
          {:session-id session-id
           :mission mission-state
           :actors (actors/new-state
                    {:models (:models mission-state)
                     :slot-ids (get-in config [:swarm-slots]
                                       (:swarm-slots default-config))
                     :workspace-root (:workspace-root config)
                     :workspaces (or (:swarm-workspaces config) {})})
           :relay (:relay mission-state)
           :board nil
           :calls {}
           :runs {}
           :cancel (cancel/new-state {:session-id session-id
                                      :run-id (str session-id "-run")})
           :reports delivery/empty-state
           :acceptance acceptance/empty-state
           :commands (commands/new-state registry)
           ;; Multi-client tmux presence.  The first attached client is the
           ;; controller; every other client is an observer in attach order.
           ;; These fields are presence only: detaching never cancels a run.
           :controller-id nil
           :observer-ids []
           :session initial-session
           :conversation (vec (or initial-conversation []))
           :drafts {}
           :scope {:book (scope-ledger/new-book)
                   :records []}
           :terminals []
           :pending-cancels []
           :pending-turns []
           :nudges {}
           :nudge-seq 0
           :progress-seq 0
           :turn nil
           :turn-history []
           :report-claims {}
           ;; Consecutive tool admission refusals per
           ;; [tenure-id agent-id member].  A successful admission clears the
           ;; key; the Nudge policy blocks the turn after the limit instead
           ;; of repeating the same impossible request forever.
           :tool-refusals {}
           ;; Trusted worker programs are scheduled asynchronously; the job
           ;; map is the receipt/outcome store keyed by job-id.
           :worker-jobs {}
           ;; Keys already reported as :TOOL_ADMISSION_REFUSED_LIMIT for an
           ;; actor turn.  A successful admission removes the key; this
           ;; stops wake-actors from emitting the same diagnostic every round.
           :actor-refusal-reported #{}
           :relay-barrier-rejections {}
           :diagnostics []
           :outbox []
           :counters {:next-call 0}
           :retry-policy (:policy policy-result)
           :config (merge default-config config)
           :created-at now
           :updated-at now}]
      (project state))))

(defn execution-tenures
  [state]
  (relay/execution-tenures (:relay state)))

(defn active-execution-tenures
  "Live execution tenures.  A completed tenure stays as evidence."
  [state]
  (filter #(= :active (:status %)) (vals (execution-tenures state))))

(defn sole-active-execution-tenure
  "The only active execution tenure, or nil when none or several exist."
  [state]
  (let [tenures (vec (active-execution-tenures state))]
    (when (= 1 (count tenures))
      (first tenures))))

(defn sole-execution-tenure
  "The only active execution tenure, or nil when none or several exist."
  [state]
  (sole-active-execution-tenure state))

(defn tenure-by-id
  [state tenure-id]
  (relay/tenure-by-id (:relay state) tenure-id))

(defn mission-completed?
  "True when an execution tenure has reached the completed milestone.

  The execution lane is the mission's terminal lane.  A completed tenure
  stays as evidence; it is not reopened in place."
  [state]
  (and (empty? (active-execution-tenures state))
       (boolean
        (some #(or (= :completed (:status %))
                   (= :completed (:milestone %)))
              (vals (execution-tenures state))))))

(defn latest-active-execution-tenure
  "The active execution tenure with the highest epoch, or nil."
  [state]
  (last (sort-by :epoch (active-execution-tenures state))))

(defn latest-execution-tenure
  "The execution tenure with the highest epoch, or nil."
  [state]
  (last (sort-by :epoch (vals (execution-tenures state)))))

(defn all-tenures
  [state]
  (let [plan (relay/current-tenure (:relay state) :plan)]
    (cond-> (vec (vals (execution-tenures state)))
      plan (conj plan))))

(defn tenure-terminal?
  "True when every live tenure has reached its final milestone.

  A retired tenure is gone from the live lanes.  A Plan tenure stays active
  after handoff, so its milestone decides."
  [state]
  (let [tenures (all-tenures state)]
    (and (seq tenures)
         (every? #(or (= :completed (:milestone %))
                      (= :retired (:status %)))
                 tenures))))

(defn admission-open?
  [state]
  (let [run-id (get-in state [:cancel :active-run-id])
        run (get-in state [:cancel :nodes run-id])]
    (and (not= :closed (:admission run))
         ;; A run that already reached a terminal state owns no dispatch
         ;; domain, whatever its admission flag says.  The cancellation walk
         ;; closes the gate itself, but a run can also finish through
         ;; `finish-run` or a hand-built/recovered state where the flag is
         ;; stale; treating that as open lets a settle or a claim dispatch a
         ;; Nudge into a stopped run.
         (not (contains? #{:cancelled :succeeded :failed} (:state run))))))

(defn open-successor-run
  "Open a fresh run with open admission, for a real user message.

  Only a run that has already reached `:cancelled` may be succeeded: the
  cancelled run has no live resource left, because `hc.compose.cancel/finish-run`
  closes it only through the quiescence check.  A run that is still
  `:cancelling` keeps its closed admission and stays active, so no second
  admission exists while a Shell, workspace action, or `:applying` program of
  the cancelled run can still act.  The old run stays in the tree as evidence."
  [state]
  (let [run-id (get-in state [:cancel :active-run-id])
        run (get-in state [:cancel :nodes run-id])]
    (if (= :cancelled (:state run))
      (let [runs (count (filter #(= :run (:kind %))
                                (vals (get-in state [:cancel :nodes]))))
            new-id (str run-id "-r" (inc runs))]
        (-> state
            (update :cancel cancel/add-node
                    {:node-id new-id
                     :kind :run
                     :parent-id nil
                     :state :running
                     :admission :open})
            (assoc-in [:cancel :active-run-id] new-id)
            ;; The queued turns are served by this run now; the request has
            ;; been honoured and must not reopen a second run later.
            (update :cancel dissoc :reopen-requested?)))
      state)))

(defn successor-run-pending?
  "True when a turn was accepted while the active run was still cancelling.

  The successor run is opened by `open-successor-run-guard` in the same
  transition that proves the cancellation domain quiescent.  The flag is the
  operator's retained intent to continue, not a second admission."
  [state]
  (true? (get-in state [:cancel :reopen-requested?])))

(defn run-cancelling?
  "True when the active run is still draining an accepted cancellation.

  A turn accepted in this state cannot be served yet: admission is closed
  until `cancel/finish-run` proves no live Shell, workspace action, or
  `:applying` program remains."
  [state]
  (let [run-id (get-in state [:cancel :active-run-id])]
    (= :cancelling (get-in state [:cancel :nodes run-id :state]))))

(defn control-agent-id
  "The mission control actor for one execution tenure's board."
  [state tenure-id]
  (get-in state [:mission :bindings :execution tenure-id :control-agent-id]))

(defn board-facts
  "Owner-owned facts the mission board snapshot needs.

  The board itself comes from mission state.  Calls and reports belong to the
  Owner, so they are supplied here."
  [state]
  (let [host (or (get-in state [:config :board-facts]) {})
        live-calls (count (filter #(contains? #{:dispatching :running
                                                :retry-wait}
                                              (:state %))
                                  (vals (:calls state))))
        pending-reports (count (filter #(= :pending (:phase %))
                                       (vals (get-in state [:reports :reports]))))]
    {:call-children live-calls
     :report-pending pending-reports
     ;; The Owner never acquires W itself, but a workspace bridge can.  A
     ;; host that knows the truth must supply it; a silent false would let
     ;; the relay barrier pass while a write is in progress.
     :pending-rebase (long (or (:pending-rebase host) 0))
     :holds-write-lock? (true? (:holds-write-lock? host))
     :ff-ready? (true? (:ff-ready? state))}))

;; ---------------------------------------------------------------------------
;; Call records and cancellation nodes

(defn call
  [state call-id]
  (get-in state [:calls call-id]))

(defn put-call
  [state call]
  (assoc-in state [:calls (:call-id call)] call))

(defn append-diagnostic
  [state diagnostic]
  (update state :diagnostics
          (fn [diagnostics]
            (vec (take-last 32 (conj (vec diagnostics) diagnostic))))))

(defn append-outbox
  "Append EFFECTS with fresh monotonic `:outbox-seq` numbers.

  The counter is the only authority, but a recovered or hand-built state may
  still hold entries numbered above it.  Starting from the highest existing
  number keeps `drain-outbox!` from skipping a newly appended entry, which
  would silently drop a later `:cancel/resource` effect."
  [state effects]
  (let [effects (vec effects)]
    (if (empty? effects)
      state
      (let [highest (reduce (fn [highest effect]
                              (max (long highest)
                                   (long (or (:outbox-seq effect) 0))))
                            0
                            (:outbox state))
            start (inc (max (long (get-in state [:counters :outbox-seq] 0))
                            highest))
            numbered (map-indexed (fn [index effect]
                                    (assoc effect
                                           :outbox-seq
                                           (+ start index)))
                                  effects)]
        (-> state
            (assoc-in [:counters :outbox-seq]
                      (+ start (dec (count effects))))
            (update :outbox
                    (fn [outbox]
                      (vec (take-last 256
                                      (into (vec outbox) numbered))))))))))

(defn normalize-attempt-id
  "Return one canonical attempt-id string.

  Scheduler attempt ids are keywords; model gateways and transport envelopes
  may return the same id as a string, sometimes with a leading colon.  Call,
  run, message, cancellation-node, and terminal keys must use one spelling or
  a completed model event looks stale and the assistant message stays
  streaming forever."
  [attempt-id]
  (cond
    (nil? attempt-id) nil
    (keyword? attempt-id) (name attempt-id)
    (string? attempt-id)
    (if (str/starts-with? attempt-id ":")
      (subs attempt-id 1)
      attempt-id)
    :else (str attempt-id)))

(defn attempt-node-id
  [call-id attempt-id]
  (str call-id "#" (normalize-attempt-id attempt-id)))

(defn add-call-node
  [state call]
  (update state :cancel cancel/add-node
          {:node-id (:call-id call)
           :kind :call
           :parent-id (get-in state [:cancel :active-run-id])
           :state (:state call)
           :side-effecting? (not= :model (:kind call))}))

(defn add-attempt-node
  [state call attempt-id]
  (let [attempt-id (normalize-attempt-id attempt-id)]
    (update state :cancel cancel/add-node
            {:node-id (attempt-node-id (:call-id call) attempt-id)
             :kind :attempt
             :parent-id (:call-id call)
             :state :dispatching
             :attempt-id attempt-id
             :current-attempt-id attempt-id
             :side-effecting? (not= :model (:kind call))
             ;; A model attempt is a stream, not an external resource.  A
             ;; Shell/workspace/devops attempt is a live resource until its
             ;; executor reports a settlement.
             :resource-alive? (not= :model (:kind call))})))

(defn set-node-state
  "Move one cancellation node to NODE-STATE.

  A state change that starts execution also starts the resource.  Call and
  attempt nodes derive `:resource-alive?` from their state, so advancing a
  node from `:ready` to `:dispatching` must mark it alive; otherwise the
  node keeps the `false` it got at construction, `active-resources` never
  counts a call that is really running, and `finish-run` can close over it.

  Only a start raises the flag.  Nothing here ever lowers it: ending a
  resource is the job of `accept-terminal`, and lowering it here would eat
  the very observation that proves the resource ended."
  [state node-id node-state]
  (if-let [node (get-in state [:cancel :nodes node-id])]
    (assoc-in state [:cancel :nodes node-id]
              (assoc node
                     :state node-state
                     :resource-alive?
                     (or (true? (:resource-alive? node))
                         (and (true? (:side-effecting? node))
                              (case (:kind node)
                                :call (contains? #{:dispatching :running
                                                   :retry-wait}
                                                 node-state)
                                :attempt (contains? #{:dispatching :running}
                                                    node-state)
                                false)))))
    state))

(defn accept-node
  [state node-id attempt-id outcome payload error now]
  (let [attempt-id (normalize-attempt-id attempt-id)
        result (cancel/accept-terminal
                (:cancel state)
                (cond-> {:node-id node-id :outcome outcome :at now}
                  (some? attempt-id) (assoc :attempt-id attempt-id)
                  (some? payload) (assoc :result payload)
                  (some? error) (assoc :error error)))]
    (if (:ok? result)
      (assoc state :cancel (:state result))
      (append-diagnostic state
                         (merge {:source :cancel-accept-terminal
                                 :node-id node-id
                                 :outcome outcome
                                 :at now}
                                (select-keys (:error result)
                                             [:code :message :details]))))))

(defn normalize-outcome
  [status]
  (if (contains? #{:succeeded :failed :cancelled :outcome-unknown} status)
    status
    :failed))

(def live-states
  "Call phases that own a live execution resource or a pending retry."
  #{:dispatching :running :retry-wait})

(def tool-kinds
  "Call kinds that run a blocking tool executor."
  #{:tool :shell :workspace :devops})

(defn live-calls
  [state pred]
  (filter #(and (contains? live-states (:state %)) (pred %))
          (vals (:calls state))))

(defn live-call-for-agent?
  "True when the agent owns any ready or live model/tool/Shell/workspace call.

  `except-call-id` lets a model claim ignore its own ready candidate while
  still waiting for an older ready tool call.  A ready tool belongs to the
  previous Scope turn; starting the next model turn before it settles would
  let a late child settle against the new turn."
  ([state agent-id] (live-call-for-agent? state agent-id nil))
  ([state agent-id except-call-id]
   (boolean
    (some (fn [call]
            (and (= agent-id (:agent-id call))
                 (not= except-call-id (:call-id call))
                 (contains? #{:ready :dispatching :running :retry-wait}
                            (:state call))))
          (vals (:calls state))))))

(defn pending-model-for-agent?
  "True when a model call for the agent is ready or live.

  A ready call is pending dispatch; it must suppress Nudge even though it is
  not yet a live execution resource."
  [state agent-id]
  (boolean
   (some (fn [call]
           (and (= agent-id (:agent-id call))
                (= :model (:kind call))
                (contains? #{:ready :dispatching :running :retry-wait}
                           (:state call))))
         (vals (:calls state)))))

(defn nudge-call?
  "True when CALL-ID names a call this Owner created as a Nudge.

  The marker is retained for publisher/UI filtering and for tests.  It is
  deliberately not a suppression bit: every settled turn, including a
  Nudge-authored turn, is judged again by `settle-turn` so continuation
  cannot end by accident."
  [state call-id]
  (boolean (get-in state [:calls call-id :nudge?])))

(defn live-for-agent?
  [state agent-id kinds]
  (boolean
   (seq (live-calls state #(and (= agent-id (:agent-id %))
                                (contains? kinds (:kind %)))))))

(defn live-model-for-agent?
  "True when the agent owns a model call in dispatching, running, or
  retry-wait.  A retry-wait call still owns the agent's model slot: injecting
  a Nudge or a second model request before the retry fires would starve it."
  [state agent-id]
  (live-for-agent? state agent-id #{:model}))

(defn actor-call?
  "True when CALL-ID names a swarm actor or Plan-worker model call.

  Those calls read and write their own agent `:context`; their assistant text
  belongs there and must never enter the Owner's shared conversation, which
  is the central Plan/Reviewer snapshot.  A worker reply that leaks into the
  conversation would both break that isolation and move the Pair Hint prefix
  chain for every central request."
  [state call-id]
  (boolean (or (get-in state [:calls call-id :actor-attempt-id])
               (get-in state [:calls call-id :worker-nudge?]))))

(defn conversation-stable?
  "True when no model run is still writing into the shared conversation.

  A run in `:started` or `:streaming` owns one assistant message whose `:text`
  is still growing.  A request that freezes that message records a digest no
  later request can match, so a turn that renders the shared conversation must
  not open while one is live — even when the streaming agent is a different
  lane.

  An actor run is ignored: swarm slots and the Plan worker keep their text in
  their own `:context`, and `put-assistant` never writes it here, so such a
  run cannot move this conversation or its prefix chain.  Workers therefore
  keep running concurrently with a user turn."
  [state]
  (not-any? (fn [run]
              (and (contains? #{:started :streaming} (:status run))
                   (not (actor-call? state (:call-id run)))))
            (vals (:runs state))))

(defn live-tool-count
  [state agent-id]
  (count (live-calls state #(and (= agent-id (:agent-id %))
                                 (contains? tool-kinds (:kind %))))))

(defn call-node-terminal?
  [state call-id]
  (contains? #{:succeeded :failed :cancelled :outcome-unknown}
             (get-in state [:cancel :nodes call-id :state])))

(defn parent-call-active?
  "True when a child report may still be accepted by CALL-ID.

  The call must exist, its cancellation node must not be terminal, and its
  owning run must not be cancelling or terminal.  A report for a cancelling
  parent is acknowledged as `:parent-terminated`; it is stored for audit but
  never appended to the parent conversation."
  [state call-id]
  (let [call (call state call-id)
        node (get-in state [:cancel :nodes call-id])
        run (get-in state [:cancel :nodes (:parent-id node)])]
    (and (some? call)
         (not (call-node-terminal? state call-id))
         (not (contains? #{:cancelling :cancelled :succeeded :failed}
                         (:state run))))))

(defn create-call
  "Create a ready call record and its cancellation node.

  Returns `[state call]`."
  [state {:keys [call-id kind role lane tenure-id agent-id parent-call-id
                 request nudge-id actor-attempt-id created-at]}]
  (let [generated? (nil? call-id)
        [n call-id]
        (if generated?
          (loop [n (inc (get-in state [:counters :next-call] 0))]
            (let [candidate (str "call-" n)]
              (if (contains? (:calls state) candidate)
                (recur (inc n))
                [n candidate])))
          [(get-in state [:counters :next-call] 0) call-id])
        call {:call-id call-id
              :kind kind
              :role role
              :lane lane
              :tenure-id tenure-id
              :agent-id agent-id
              :parent-call-id parent-call-id
              :state :ready
              :attempt-id nil
              :request request
              :retry nil
              :retry-due? false
              :retry-planned? false
              :last-error nil
              :result nil
              :timer {:state :ready}
              :nudge-id nudge-id
              :actor-attempt-id actor-attempt-id
              :output ""
              :created-at created-at}]
    [(cond-> state
       generated? (assoc-in [:counters :next-call] n)
       true (put-call call)
       true (add-call-node call))
     call]))

(defn terminal-call
  [state call outcome payload error now]
  (let [call (-> call
                 (assoc :state outcome
                        :result payload
                        :error error
                        :last-error error
                        :attempt-id nil
                        :finished-at now)
                 (assoc-in [:retry :phase] outcome))]
    (accept-node (put-call state call) (:call-id call) nil outcome payload error now)))

;; ---------------------------------------------------------------------------
;; Model run lifecycle

(defn run-id
  [call-id attempt-id]
  (str call-id "#" (normalize-attempt-id attempt-id)))

(defn run-terminal?
  [run]
  (contains? #{:finished :failed :cancelled :outcome-unknown}
             (:status run)))

(defn start-run
  "Create one model run for a claimed model attempt.

  Tool calls are not runs.  A retry creates a new run because it has a new
  attempt id; the previous run stays as evidence."
  [state call attempt-id now]
  (if-not (= :model (:kind call))
    state
    (let [attempt-id (normalize-attempt-id attempt-id)
          id (run-id (:call-id call) attempt-id)]
      (if (contains? (:runs state) id)
        state
        (assoc-in state [:runs id]
                  {:run-id id
                   :call-id (:call-id call)
                   :attempt-id attempt-id
                   :role (:role call)
                   :lane (:lane call)
                   :tenure-id (:tenure-id call)
                   :agent-id (:agent-id call)
                   :status :started
                   :nudge? (boolean (:nudge? call))
                   :nudge-id (get-in call [:request :snapshot :nudge-id])
                   :context (:context call)
                   :context-attempt-id (:context-attempt-id call)
                   :text ""
                   :message-id (str "assistant-" (:call-id call) "-" attempt-id)
                   :started-at now
                   :finished-at nil
                   :cancel-reason nil
                   :error nil
                   :late-events []
                   :tool-call-ids []})))))

(defn ensure-run
  [state call-id attempt-id now]
  (if (get-in state [:runs (run-id call-id attempt-id)])
    state
    (if-let [current (call state call-id)]
      (start-run state current attempt-id now)
      state)))

(defn run-for-attempt
  [state call-id attempt-id]
  (get-in state [:runs (run-id call-id attempt-id)]))

(defn active-run-for-call
  [state call-id]
  (some (fn [run]
          (when (and (= call-id (:call-id run))
                     (contains? #{:started :streaming} (:status run)))
            run))
        (vals (:runs state))))

(defn- assistant-message
  [run text streaming? now extra]
  (merge {:role :assistant
          :message-id (:message-id run)
          :run-id (:run-id run)
          :call-id (:call-id run)
          :text text
          :streaming? (boolean streaming?)
          :at now}
         (cond-> {}
           (:nudge? run) (assoc :nudge? true)
           (:nudge-id run) (assoc :nudge-id (:nudge-id run)))
         extra))

(defn- put-assistant
  "Write one run's assistant message into the conversation.

  An actor run is skipped: its text already lives in that agent's private
  `:context` (`hc.compose.mission.actors/accept-model-event`), and the
  Owner conversation is the central snapshot.  Writing it here would leak a
  worker reply into the Plan/Reviewer context and move the Pair Hint chain.

  A blank write never shrinks an answer that already has text.  The
  message id names one visible slot for one call, so a later terminal,
  cancellation, or retry for the same slot may change the text but must not
  erase it: the outbound copy would then stop being a prefix of the request
  that already carried that text, and Pair Hint would report `:PREFIX_DRIFT`
  at this message for a change no reader ever made."
  [state run text streaming? now extra]
  (if (actor-call? state (:call-id run))
    state
    (let [blank? (str/blank? (str text))
          previous (some (fn [message]
                           (when (= (:message-id run) (:message-id message))
                             message))
                         (:conversation state))
          ;; Keep the recorded text unless the new write carries text.
          text (if (and blank? (seq (str (or (:text previous) ""))))
                 (:text previous)
                 text)
          message (assistant-message run text streaming? now extra)
          message-id (:message-id run)
          exists? (some? previous)]
      (cond
        (and blank? (not exists?))
        state

        exists?
        (update state :conversation
                (fn [messages]
                  (mapv (fn [existing]
                          (if (= message-id (:message-id existing))
                            message
                            existing))
                        messages)))

        :else
        (update state :conversation conj message)))))

(defn record-late-run-event
  "Keep a late event as bounded evidence.  It never changes run text."
  [state run-id event now]
  (if-let [run (get-in state [:runs run-id])]
    (-> state
        (update-in [:runs run-id :late-events]
                   (fn [events]
                     (vec (take-last 8 (conj (vec events)
                                             (assoc event :at now))))))
        (append-diagnostic {:code :LATE_MODEL_EVENT
                            :run-id run-id
                            :event (:event event)
                            :at now}))
    (append-diagnostic {:code :LATE_MODEL_EVENT_UNKNOWN_RUN
                        :run-id run-id
                        :event (:event event)
                        :at now})))

(defn append-run-text
  "Append one streaming fragment and update the assistant message."
  [state id text now]
  (if-let [run (get-in state [:runs id])]
    (if (run-terminal? run)
      (record-late-run-event state id {:event :chunk :text text} now)
      (let [text (str (or (:text run) "") (or text ""))
            run (assoc run :text text :status :streaming)
            state (assoc-in state [:runs id] run)]
        (put-assistant state run text true now {})))
    state))

(defn finish-run
  "Fix one run terminal and finalize its assistant message.

  A repeated terminal is a no-op.  A different terminal after one is fixed
  is late evidence."
  ([state id status now]
   (finish-run state id status now {}))
  ([state id status now {:keys [text error cancel-reason] :as extra}]
   (if-let [run (get-in state [:runs id])]
     (cond
       (and (run-terminal? run) (= status (:status run)))
       state

       (run-terminal? run)
       (record-late-run-event state id {:event :terminal :status status} now)

       :else
       ;; A terminal that carries no text keeps the text already streamed.
       ;; Taking a blank payload literally would erase what the operator
       ;; saw and what the outbound copy already carried.
       (let [text (if (str/blank? (str text))
                    (str (or (:text run) ""))
                    text)
             run (assoc run
                        :status status
                        :finished-at now
                        :text text
                        :cancel-reason cancel-reason
                        :error error
                        :search-status (:search-status extra)
                        :search-degraded? (:search-degraded? extra)
                        :search-failed? (:search-failed? extra))
             state (assoc-in state [:runs id] run)]
         (put-assistant state run text false now
                        (merge (cond-> {:status status}
                                 cancel-reason
                                 (assoc :cancel-reason cancel-reason)
                                 (= status :cancelled)
                                 (assoc :cancelled? true)
                                 (= status :failed)
                                 (assoc :failed? true)
                                 (= status :finished)
                                 (assoc :finished? true))
                               (dissoc extra :text :error
                                       :cancel-reason)))))
     state)))

(defn finish-run-for-attempt
  [state call-id attempt-id outcome payload error now]
  (let [status (case outcome
                 :succeeded :finished
                 :failed :failed
                 :cancelled :cancelled
                 :outcome-unknown :outcome-unknown
                 :failed)
        id (run-id call-id attempt-id)
        text (or (:text payload) (get-in state [:runs id :text]))]
    (finish-run state id status now {:text text :error error})))

(defn finish-runs-for-call
  "Finish every active run for CALL-ID with the cancellation outcome."
  [state call-id outcome now reason]
  (let [status (case outcome
                 :succeeded :finished
                 :failed :failed
                 :cancelled :cancelled
                 :outcome-unknown :outcome-unknown
                 :cancelled)]
    (reduce (fn [state run]
              (if (and (= call-id (:call-id run))
                       (contains? #{:started :streaming} (:status run)))
                (finish-run state (:run-id run) status now
                            {:cancel-reason reason})
                state))
            state
            (vals (:runs state)))))

;; ---------------------------------------------------------------------------


(defn board-summary
  "Derive the board snapshot one execution tenure's relay barrier expects.

  With one argument, use the sole execution tenure.  With several live
  tenures, pass the specific `tenure-id`.  A missing board returns nil; the
  caller must refuse or retry rather than treat absence as quiescence."
  ([state]
   (board-summary state (some-> (sole-execution-tenure state) :tenure-id)))
  ([state tenure-id]
   (when tenure-id
     (mission/board-summary (:mission state) tenure-id
                            (board-facts state)))))

(defn attachment-id
  [attachment]
  (or (:attachment-id attachment)
      (:id attachment)))

(defn attachment-reference
  [attachment]
  (or (attachment-id attachment)
      (:send-blob-id attachment)
      (:blob-id attachment)))

(defn attachment-content?
  "True when the attachment already carries real content.

  A text route carries `:send-text`; a native route may carry `:content` or
  `:parts`.  A bare blob reference is not content."
  [attachment]
  (or (not (str/blank? (str (:content attachment))))
      (not (str/blank? (str (:send-text attachment))))
      (boolean (seq (:parts attachment)))))

(defn attachment-problem
  "Return the first explicit attachment problem in a model request.

  A reference is acceptable only when the model gateway declares that it
  resolves references.  The Owner never silently drops an attachment."
  [request {:keys [resolve?] :as _contract}]
  (let [messages (get-in request [:snapshot :messages])]
    (some (fn [message]
            (some (fn [attachment]
                    (cond
                      (nil? (attachment-reference attachment))
                      {:code :ATTACHMENT_REFERENCE_REQUIRED
                       :message-id (:message-id message)
                       :attachment attachment}

                      (attachment-content? attachment)
                      nil

                      (and resolve?
                           (or (:send-blob-id attachment)
                               (:blob-id attachment)))
                      nil

                      :else
                      {:code :ATTACHMENT_UNRESOLVED
                       :message-id (:message-id message)
                       :attachment-id (attachment-id attachment)
                       :blob-id (or (:send-blob-id attachment)
                                    (:blob-id attachment))}))
                  (:attachments message)))
          messages)))

(defn scope-book
  [state]
  (get-in state [:scope :book]))

(defn- retain-scope-records
  "Keep every reset/remove boundary record and the last 512 keep records.

  Boundary facts are needed for cold-start projection and Pair Hint cursors;
  dropping an old reset or erase would make a restart lose the boundary."
  [existing records]
  (let [all (into (vec existing) records)
        keep-indexes (keep-indexed
                      (fn [index record]
                        (when (= :keep (:type record)) index))
                      all)
        keep-set (set (take-last 512 keep-indexes))]
    (->> all
         (keep-indexed
          (fn [index record]
            (when (or (not= :keep (:type record))
                      (contains? keep-set index))
              record)))
         vec)))

(defn apply-scope-result
  [state book records]
  (-> state
      (assoc-in [:scope :book] book)
      (update-in [:scope :records]
                 (fn [existing]
                   (retain-scope-records existing records)))))

(defn scope-admission-check
  [state member annotation]
  (scope-admission/check
   {:member member
    :annotation annotation
    :retention (scope-ledger/retention (scope-book state))
    :admission (scope-admission/admission)}))

(defn settle-tool-scope
  "Settle one tool call's Scope annotation in the same Owner transition."
  [state call observed now]
  (if (or (:scope-exempt? call) (nil? (:scope-annotation call)))
    state
    (let [{:keys [book result]}
          (scope-ledger/settle-turn
           (scope-book state)
           (:scope-turn-id call)
           (:scope-annotation call)
           {:call-id (:call-id call) :observed observed})]
      (if (:ok result)
        (apply-scope-result state book (:records result))
        (append-diagnostic
         state
         (assoc result
                :code (or (:code result) :SCOPE_SETTLE_REFUSED)
                :call-id (:call-id call)
                :at now))))))

(defn live-scope-children?
  "True when a model turn still has a live child tool call.

  Closing the Scope turn before these settle would let a fast `:me-too`
  default to keep-last and turn a later true annotation into only
  `:confirmed`."
  [state turn-id]
  (boolean
   (some (fn [call]
           (and (= turn-id (:scope-turn-id call))
                (not (:scope-exempt? call))
                (contains? #{:ready :dispatching :running :retry-wait}
                           (:state call))))
         (vals (:calls state)))))

(defn close-model-turn-scope
  [state turn-id now]
  (let [{:keys [book result]} (scope-ledger/close-turn (scope-book state)
                                                       turn-id)]
    (cond
      (:ignored result)
      (append-diagnostic
       state
       (assoc result
              :code :SCOPE_CLOSE_STALE
              :turn-id turn-id
              :at now))

      (:ok result)
      (apply-scope-result state book (:records result))

      :else
      (append-diagnostic
       state
       (assoc result
              :code (or (:code result) :SCOPE_CLOSE_REFUSED)
              :turn-id turn-id
              :at now)))))

(defn- message->scope-item
  [message]
  (let [payload (cond-> {:text (:text message)}
                  (contains? message :attachments)
                  (assoc :attachments (:attachments message))
                  (contains? message :quotes)
                  (assoc :quotes (:quotes message))
                  (contains? message :citations)
                  (assoc :citations (:citations message))
                  (contains? message :message-id)
                  (assoc :message-id (:message-id message)))]
    (case (:role message)
      :user (assoc payload :kind :message :role :operator)
      :assistant (assoc payload :kind :message :role :assistant)
      :system (assoc payload :kind :message :role :system)
      :tool (cond-> {:kind :tool-result
                     :member (or (:member message) (:name message))
                     :call-id (or (:call-id message) (:tool-call-id message))
                     :text (:text message)}
              (boolean? (:error? message))
              (assoc :error? (:error? message)))
      :tool-call (cond-> {:kind :tool-call
                          :member (or (:member message) (:name message))
                          :call-id (or (:call-id message) (:tool-call-id message))
                          :scope (:scope message)}
                   (contains? message :arguments)
                   (assoc :arguments (:arguments message)))
      (assoc payload :kind :message :role :operator))))

(defn- scope-item->message
  [item]
  (case (:kind item)
    :message
    (cond-> {:role (case (:role item)
                     :operator :user
                     :assistant :assistant
                     :system :system
                     :user)
             :text (:text item)}
      (contains? item :attachments) (assoc :attachments (:attachments item))
      (contains? item :quotes) (assoc :quotes (:quotes item))
      (contains? item :citations) (assoc :citations (:citations item))
      (contains? item :message-id) (assoc :message-id (:message-id item)))
    :tool-call
    {:role :assistant
     :tool-calls [(cond-> {:id (:call-id item)
                           :name (:member item)}
                    (contains? item :arguments)
                    (assoc :arguments (:arguments item)))]}
    :tool-result
    (cond-> {:role :tool
             :tool-call-id (:call-id item)
             :name (:member item)
             :call-id (:call-id item)
             :member (:member item)
             :text (:text item)}
      (boolean? (:error? item)) (assoc :error? (:error? item))
      (nil? (:call-id item)) (dissoc :tool-call-id :call-id)
      (nil? (:member item)) (dissoc :name :member))
    item))

(defn- applied-compressions
  "Applied compressions in apply order.

  `:compression-order` is authoritative; compressions missing from it fall
  back to `:applied-at`.  Only `:applied` records substitute: a draft,
  reverted or cancelled range keeps sending its raw messages.  This mirrors
  `hc.server.history.context/applied-compressions`; compose cannot require
  the server namespace, so the ordering rule is restated here over the
  plain session `:context` map the Owner already carries."
  [session]
  (let [context (:context session)
        compressions (:compressions context)
        compressions (if (map? compressions) compressions {})
        ordered-ids (vec (or (:compression-order context) []))
        ordered (into [] (keep #(get compressions %)) ordered-ids)
        ordered-id-set (set (map :compression-id ordered))
        remaining (->> (vals compressions)
                       (remove #(contains? ordered-id-set (:compression-id %)))
                       (filter #(= :applied (:state %)))
                       (sort-by :applied-at)
                       vec)]
    (->> (concat ordered remaining)
         (filter #(= :applied (:state %)))
         vec)))

(defn- compression-substitute
  "Replace each fully projected compression range with one summary message.

  A compression substitutes only when every `:message-id` it covers is
  present in MESSAGES; a partially projected range keeps its raw messages
  so the model never receives a summary for history it cannot see.  The
  summary takes the position of the range's first surviving message and is
  emitted once.  Nudge calls and user turns share this projection, so both
  stop resending replaced history next to the summary."
  [messages compressions]
  (if (empty? compressions)
    messages
    (let [present (into #{} (keep :message-id) messages)
          eligible (filterv (fn [{:keys [message-ids] :as compression}]
                              (and (vector? message-ids)
                                   (seq message-ids)
                                   (every? #(contains? present %) message-ids)
                                   (string? (:summary compression))))
                            compressions)
          cover (into {}
                      (mapcat (fn [{:keys [message-ids] :as compression}]
                                (map (fn [message-id]
                                       [message-id compression])
                                     message-ids)))
                      eligible)
          emitted (volatile! #{})]
      (vec
       (reduce (fn [out message]
                 (if-let [compression (get cover (:message-id message))]
                   (let [compression-id (:compression-id compression)]
                     (if (contains? @emitted compression-id)
                       out
                       (do (vswap! emitted conj compression-id)
                           (conj out {:role :system
                                      :text (:summary compression)
                                      :compression-id compression-id}))))
                   (conj out message)))
               []
               messages)))))

(defn project-conversation
  "Project the outbound model conversation through Scope.

  Returns `{:ok? true :messages [...]}` or `{:ok? false :error ...}`.  The
  input is never mutated.  The projection keeps operator messages and the
  current Scope boundary; it drops old tool traffic before that boundary."
  [state messages]
  (let [book (scope-book state)
        items (mapv message->scope-item messages)
        result (scope-projection/project
                items
                {:settled? true
                 :reset-call-id (scope-ledger/last-reset-call-id book)
                 :erase (scope-ledger/erases book)})]
    (if (sequential? result)
      {:ok? true
       :messages (compression-substitute
                  (mapv scope-item->message result)
                  (applied-compressions (:session state)))}
      {:ok? false :error result})))

(defn progress!
  "Record one observable advance for Nudge budget accounting."
  [state]
  (update state :progress-seq (fnil inc 0)))

(defn- recover-cancel-node
  "Drop process-local resource claims for one cancellation node.

  A restarted process has no local thread, child process, or parked program
  for any action or process node.  Live action/process states become
  `:uncertain` with `:outcome-unknown`; terminal nodes keep their outcome and
  evidence and only lose the local resource flag.

  The restart is not evidence that an external process is still alive, and it
  gives this process no handle to it.  A node that still claims a live
  resource stays `:outcome-unknown` with the flag intact; the previous
  `:cancel-requested?` marker is preserved.  It must not be cleared to make a
  fresh process re-signal an unknown external action, because that would
  replay a side effect against a resource this process cannot observe."
  [node now]
  (let [kind (:kind node)
        state (:state node)]
    (cond-> (cond
      (and (= :action kind)
           (contains? #{:applying :running} state))
      (assoc node
             :state :uncertain
             :outcome :outcome-unknown
             :resource-alive? false
             :recovered-orphan? true
             :recovered-at now)

      (and (= :process kind)
           (contains? #{:starting :running :stopping} state))
      (assoc node
             :state :uncertain
             :outcome :outcome-unknown
             :resource-alive? false
             :recovered-orphan? true
             :recovered-at now)

      (and (true? (:resource-alive? node))
           (contains? #{:succeeded :failed :cancelled :outcome-unknown
                        :finished :exited :uncertain}
                      state))
      (assoc node :resource-alive? false)

      :else node)
      ;; "Already signalled" is a fact about the previous process: its abort
      ;; cannot reach anything here.  A node that still owns a live resource
      ;; must be signalable again, or the recovered run can never close.
      (and (true? (:resource-alive? node))
           (true? (:cancel-requested? node)))
      (assoc :cancel-requested? false))))

(defn- queued-terminal-for-call?
  "True when a committed terminal already names this call and attempt.

  Recovery must not overwrite an observation that was committed but not yet
  accepted by P1.  The terminal stays in `:terminals`; the first guard round
  after `:recover` consumes it normally."
  [state call-id attempt-id]
  (and attempt-id
       (some (fn [terminal]
               (and (= call-id (:call-id terminal))
                    (= (normalize-attempt-id attempt-id)
                       (normalize-attempt-id (:attempt-id terminal)))))
             (:terminals state))))

(defn recover-state
  "Repair one committed state after a process restart.

  A call that was `:dispatching` or `:running` has no live task after a
  restart.  Its external outcome is unknown, so it becomes
  `:outcome-unknown` and is never retried automatically.  A `:retry-wait`
  call is resumed immediately: a due retry becomes `:ready`; a future retry
  re-arms its fixed timer without moving `:retry-at`.

  Every report that is still `:pending` gets a fresh `:pending` claim.  This
  includes a claim left in `:dispatching`, `:delivered`, or `:retry-wait`:
  the parent may have accepted the report, but the child cannot know it
  received the acknowledgement.  Redelivery is idempotent at the parent.
  Claims for reports that are acknowledged, reclaimed, or absent are removed
  so no orphan claim survives the restart.  The function is idempotent."
  [state now]
  (let [state
        (reduce-kv
         (fn [state call-id call]
           (let [call-state (:state call)]
             (cond
               (contains? #{:dispatching :running} call-state)
               (let [attempt-id (:attempt-id call)
                     node-id (when attempt-id
                               (attempt-node-id call-id attempt-id))]
                 (if (queued-terminal-for-call? state call-id attempt-id)
                   state
                   (let [state (if (and node-id
                                        (get-in state
                                                [:cancel :nodes node-id]))
                                 (accept-node state node-id attempt-id
                                              :outcome-unknown nil
                                              {:kind :recovered-orphan} now)
                                 state)
                         state (accept-node state call-id nil :outcome-unknown
                                            nil {:kind :recovered-orphan} now)
                         state (assoc-in state [:calls call-id]
                                         (-> call
                                             (assoc :state :outcome-unknown
                                                    :attempt-id nil
                                                    :finished-at now)
                                             (assoc-in [:retry :phase]
                                                       :outcome-unknown)))
                         state (finish-runs-for-call state call-id
                                                     :outcome-unknown now
                                                     :recovered-orphan)]
                     (append-diagnostic
                      state
                      {:code :ORPHANED_ATTEMPT
                       :call-id call-id
                       :attempt-id attempt-id
                       :at now}))))

               (= :retry-wait call-state)
               (let [result (retry/resume-retry-wait
                             {:state (:retry call)
                              :policy (:retry-policy state)
                              :now now})]
                 (if-not (:ok? result)
                   (-> state
                       (assoc-in [:calls call-id]
                                 (-> call
                                     (assoc :state :outcome-unknown
                                            :retry-error (:error result))
                                     (assoc-in [:retry :phase]
                                               :outcome-unknown)))
                       (accept-node call-id nil :outcome-unknown nil
                                    (:error result) now)
                       (finish-runs-for-call call-id :outcome-unknown now
                                             :retry-resume-failed)
                       (append-diagnostic
                        (merge {:code :RETRY_RESUME_FAILED
                                :call-id call-id
                                :at now}
                               (select-keys (:error result)
                                            [:code :message :details]))))
                   (case (:action result)
                     :claim
                     (assoc-in state [:calls call-id]
                               (-> call
                                   (assoc :state :ready :retry-due? true)
                                   (assoc-in [:timer] {:state :ready})))

                     :rearm-timer
                     (-> state
                         (assoc-in [:calls call-id]
                                   (assoc call :state :retry-wait
                                          :retry (:state result)))
                         (append-outbox
                          [{:type :retry/rearm
                            :call-id call-id
                            :attempt-id (or (get-in result [:state :attempt-id])
                                            (:attempt-id call))
                            :retry-at (:retry-at result)}]))

                     (:fail :reconcile :cancel)
                     (let [retry-state (:state result)
                           phase (:phase retry-state)
                           failure (:failure retry-state)]
                       (-> state
                           (assoc-in [:calls call-id]
                                     (assoc call
                                            :state phase
                                            :retry retry-state
                                            :last-error failure))
                           (accept-node call-id nil phase failure nil now)
                           (finish-runs-for-call call-id phase now
                                                 (:reason failure))))

                     :terminal state
                     state)))

               :else state)))
         state
         (:calls state))
        pending-reports (into {}
                              (filter (fn [[_ report]]
                                        (= :pending (:phase report))))
                              (get-in state [:reports :reports]))
        claims (into {}
                     (map (fn [[report-id _]]
                            (let [claim (get-in state
                                                [:report-claims report-id]
                                                {})]
                              [report-id
                               (-> claim
                                   (assoc :state :pending
                                          :attempt-id nil
                                          :retry-at nil
                                          :timer nil
                                          :last-error nil)
                                   (update :attempts (fnil identity 0)))])))
                     pending-reports)
        state (assoc state :report-claims claims)
        state (update-in state [:cancel :nodes]
                         (fn [nodes]
                           (into {}
                                 (map (fn [[id node]]
                                        [id (recover-cancel-node node now)]))
                                 (or nodes {}))))]
    (assoc state :updated-at now)))
