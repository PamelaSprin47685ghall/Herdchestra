(ns hc.compose.fleet.actors
  "Actual @task actor lifecycle bound to one fleet worktree.

  `hc.compose.mission.actors` is the slot lifecycle authority, but it reads a
  board and workspace from a mission tenure.  This adapter builds a minimal
  mission value whose sole execution tenure points at the swarm's own
  worktree board and workspace, so the real actor code registers slots on
  that board and emits real `:model/start` effects.

  The adapter is composition, not a second actor implementation:
  `mission.actors/wake-all` and `mission.actors/accept-model-event` remain the
  only lifecycle transitions.  The caller dispatches the returned model
  effects through a real model gateway and feeds terminal events back here.
  No session-global board, token, or `$W$` is involved.

  A transition board is published with a kernel compare-and-set against the
  board read before the transition.  A concurrent direct board command makes
  the CAS fail; the pure transition is retried on the newer board instead of
  overwriting the command.  The board CAS protects only the board: the
  caller must also serialize the actor-state read-modify-write for one
  swarm.  The fleet service does that with one monitor per swarm, re-reads
  the current state under it, and persists only after releasing it."
  (:require [clojure.string :as str]
            [hc.compose.fleet.runtime :as runtime]
            [hc.compose.mission.actors :as mission-actors]
            [hc.compose.mission.model :as model]
            [hc.core.swarm-stm :as swarm]
            [hc.core.ufss :as ufss]))

(defn- fail
  [code message details]
  {:ok? false
   :error (cond-> {:code code :message message}
            (some? details) (assoc :details details))})

(defn- tenure-id
  [swarm-id]
  (str "fleet-" swarm-id))

(defn- fleet-mission
  [runtime models]
  (let [tid (tenure-id (:swarm-id runtime))
        tenure {:tenure-id tid
                :epoch 1
                :lane :execution
                :status :active
                :milestone :execution
                :swarm-phase :active
                :initial? false
                :first-review? false
                :awaiting-user? false}]
    {:relay {:session-id (str "fleet-" (:swarm-id runtime))
             :lanes {:plan nil
                     :execution {tid tenure}}
             :retired-tenure-ids #{}
             :receipts {}
             :barriers []
             :next-tenure-seq 2}
     :boards {tid (runtime/board runtime)}
     :bindings {:execution
                {tid {:reviewer nil
                      :control-agent-id (:control-agent-id runtime)
                      :swarm {}
                      :root-tasks {}}}}
     :models models
     :retired {}
     :requests {}
     :reopens []}))

(defn- mission-with-runtime-board
  [state]
  (let [board (runtime/board (:runtime state))]
    (assoc-in (:mission state) [:boards (:tenure-id state)] board)))

(def ^:private max-board-attempts
  "Bound actor-transition retries when a concurrent board command wins."
  32)

(defn- publish-board!
  "Publish MISSION's board to RUNTIME's kernel with compare-and-set.

  BASE is the exact kernel board read before the actor transition.  When the
  kernel still holds BASE, the transition board is published and MISSION is
  returned with that published board.  Otherwise a concurrent direct command
  won; return `:conflict? true` so the caller retries the pure transition
  against the newer board instead of overwriting it.

  `replace-board!` only swaps the kernel atom; it does not call the kernel's
  `:on-events` persistence hook.  This function therefore performs no file or
  model I/O and is safe to call while the owner holds the per-swarm actor
  monitor."
  [runtime mission base]
  (let [tid (tenure-id (:swarm-id runtime))
        board (get-in mission [:boards tid])]
    (cond
      (nil? board)
      {:ok? true :mission mission}

      (swarm/replace-board! (:kernel runtime) base board)
      {:ok? true :mission (assoc-in mission [:boards tid]
                                    (runtime/board runtime))}

      :else
      {:ok? false :conflict? true})))

(defn- board-conflict
  [runtime attempts]
  (fail :BOARD_CONFLICT
        "the worktree board changed during the actor transition"
        {:swarm-id (:swarm-id runtime)
         :attempts attempts}))

(defn- empty-slot
  [slot-id]
  {:slot-id slot-id
   :agent-id nil
   :generation 0
   :role :worker
   :status :idle
   :context []
   :attempt-id nil
   :turn 0
   :last-event nil})

(defn- control-slot?
  [slot-id]
  (and slot-id
       (str/starts-with? (name slot-id) "fleet-control-")))

(defn- board-slot-actors
  [board]
  (->> (:actors board)
       vals
       (filter #(and (= :alive (:life-state %))
                     (not (control-slot? (:slot-id %)))))
       (group-by :slot-id)))

(defn- reconcile-slots
  "Bind persisted slot identities to the board's live actors.

  A slot whose recorded identity is gone is adopted from the board when the
  board has exactly one live actor in that slot.  Adoption starts an empty
  private context: the old identity's context must not be inherited.  A slot
  with no live board actor is left for `mission.actors/sync-tenure` to
  rebirth."
  [state board]
  (let [tid (:tenure-id state)
        tenure (get-in state [:actors :tenures tid])
        by-slot (board-slot-actors board)
        multiple (->> by-slot
                      (keep (fn [[slot-id actors]]
                              (when (> (count actors) 1)
                                {:slot-id slot-id
                                 :agent-ids (mapv :agent-id actors)})))
                      vec)
        slot-order (vec (distinct
                         (concat (or (:slot-order tenure)
                                     (:slot-ids (:actors state))
                                     [])
                                 (keys (:slots tenure)))))
        extra (->> (keys by-slot)
                   (remove (set slot-order))
                   (sort-by str)
                   vec)
        slot-order (into slot-order extra)]
    (if (seq multiple)
      (fail :MULTIPLE_ACTORS_IN_SLOT
            "the restored board has more than one live actor in one slot"
            {:swarm-id (:swarm-id state)
             :slots multiple})
      (let [{:keys [slots adopted]}
            (reduce
             (fn [{:keys [slots adopted]} slot-id]
               (let [slot (or (get slots slot-id) (empty-slot slot-id))
                     board-actor (first (get by-slot slot-id))
                     agent-id (:agent-id slot)]
                 (cond
                   (nil? board-actor)
                   {:slots (assoc slots slot-id slot)
                    :adopted adopted}

                   (= agent-id (:agent-id board-actor))
                   {:slots (assoc slots slot-id slot)
                    :adopted adopted}

                   :else
                   {:slots (assoc slots slot-id
                                  (assoc (empty-slot slot-id)
                                         :agent-id (:agent-id board-actor)
                                         :generation (:generation board-actor 1)
                                         :role (if (= (:agent-id board-actor)
                                                      (get-in board
                                                              [:token-slot
                                                               :holder-id]))
                                                 :holder
                                                 :worker)
                                         :last-event :recovered-adoption))
                    :adopted (conj adopted
                                   {:slot-id slot-id
                                    :old-agent-id agent-id
                                    :agent-id (:agent-id board-actor)})})))
             {:slots (:slots tenure)
              :adopted []}
             slot-order)
            tenure' (-> tenure
                        (assoc :slots slots
                               :slot-order slot-order
                               :next-turn (long (or (:next-turn tenure) 0))
                               :workspace (or (:workspace tenure)
                                              (:workspace (:runtime state)))))]
        {:ok? true
         :state (assoc-in state [:actors :tenures tid] tenure')
         :adopted adopted}))))

(defn- recovery-note
  [attempt-id]
  {:role :system
   :text (str "The fleet process restarted while model attempt "
              (or attempt-id "unknown")
              " was running. Its external outcome is unknown. Verify the "
              "worktree and board state before repeating any tool call, "
              "Shell session, or file application.")})

(defn- recovery-note?
  [message]
  (and (= :system (:role message))
       (str/starts-with? (str (:text message))
                         "The fleet process restarted while model attempt")))

(defn- recover-attempt-call
  [calls attempt-id now]
  (if (and attempt-id (contains? calls attempt-id))
    (update calls attempt-id
            (fn [call]
              (-> call
                  (assoc :status :outcome-unknown
                         :outcome :outcome-unknown
                         :recovered-at now)
                  (assoc :error
                         {:code :MODEL_ATTEMPT_OUTCOME_UNKNOWN
                          :message
                          "the fleet process restarted while this model attempt was running; its external outcome is unknown"}))))
    calls))

(defn- recover-slot
  [slot calls board now]
  (let [agent-id (:agent-id slot)
        alive? (and agent-id
                    (= :alive (get-in board [:actors agent-id :life-state])))
        attempt-id (:attempt-id slot)
        noted? (boolean (or (= :running (:status slot))
                            (some? attempt-id)))
        context (vec (:context slot))
        context' (if (and noted? (not (some recovery-note? context)))
                   (conj context (recovery-note attempt-id))
                   context)]
    {:slot (assoc slot
                  :status (if alive? :idle :dead)
                  :attempt-id nil
                  :context context'
                  :last-event (if noted?
                                :outcome-unknown
                                (:last-event slot)))
     :calls (recover-attempt-call calls attempt-id now)
     :attempt attempt-id
     :noted? noted?}))

(defn- recover-tenure
  [tenure board calls now]
  (let [{:keys [slots calls notes]}
        (reduce
         (fn [{:keys [slots calls notes]} [slot-id slot]]
           (let [recovered (recover-slot slot calls board now)]
             {:slots (assoc slots slot-id (:slot recovered))
              :calls (:calls recovered)
              :notes (cond-> notes
                       (:noted? recovered)
                       (conj {:slot-id slot-id
                              :attempt-id (:attempt recovered)}))}))
         {:slots {} :calls calls :notes []}
         (:slots tenure))]
    {:tenure (assoc tenure :slots slots)
     :calls calls
     :notes notes}))

(defn- recover-plan-worker
  [plan-worker calls now]
  (if (and plan-worker (= :running (:status plan-worker)))
    (let [attempt-id (:attempt-id plan-worker)
          context (vec (:context plan-worker))
          context' (if (some recovery-note? context)
                     context
                     (conj context (recovery-note attempt-id)))]
      {:plan-worker (assoc plan-worker
                           :status :idle
                           :attempt-id nil
                           :context context')
       :calls (recover-attempt-call calls attempt-id now)})
    {:plan-worker plan-worker
     :calls calls}))

(defn recover-state
  "Resolve actor attempts left running by the old process.

  A running slot or Plan worker becomes idle, its call is fixed as
  `:outcome-unknown`, and one system note tells the next turn to verify the
  worktree instead of repeating an external action.  The private context is
  retained.  No model effect is emitted."
  [state board {:keys [now] :or {now 0}}]
  (if-not (map? state)
    (fail :ACTOR_STATE_REQUIRED "actor state is required" {})
    (let [tenures (get-in state [:actors :tenures])
          recovered (reduce
                     (fn [{:keys [tenures calls notes]} [tid tenure]]
                       (let [result (recover-tenure tenure board calls now)]
                         {:tenures (assoc tenures tid (:tenure result))
                          :calls (:calls result)
                          :notes (into notes
                                       (map #(assoc % :tenure-id tid)
                                            (:notes result)))}))
                     {:tenures {}
                      :calls (get-in state [:actors :calls])
                      :notes []}
                     tenures)
          plan (recover-plan-worker (get-in state [:actors :plan-worker])
                                    (:calls recovered)
                                    now)
          state' (-> state
                     (assoc-in [:actors :tenures] (:tenures recovered))
                     (assoc-in [:actors :calls] (:calls plan))
                     (assoc-in [:actors :plan-worker] (:plan-worker plan)))]
      {:ok? true
       :state state'
       :recovered-attempts (vec (:notes recovered))
       :calls (:calls plan)})))

(defn- normalize-mission
  [mission runtime models]
  (let [tid (tenure-id (:swarm-id runtime))
        default (fleet-mission runtime models)
        tenure (get-in mission [:relay :lanes :execution tid])]
    (if (and (map? mission) (map? tenure))
      (-> mission
          (assoc :models models)
          (assoc-in [:boards tid] (runtime/board runtime))
          (assoc-in [:bindings :execution tid]
                    (merge (get-in default [:bindings :execution tid])
                           (get-in mission [:bindings :execution tid])
                           {:control-agent-id (:control-agent-id runtime)}))
          (assoc-in [:relay :lanes :execution tid]
                    (assoc tenure :status :active :swarm-phase :active)))
      default)))

(defn- ensure-tenure
  [actors tid runtime slot-ids]
  (let [slot-ids (vec (or slot-ids (:slot-ids actors) [:slot-a :slot-b]))
        existing (get-in actors [:tenures tid])
        slots (or (:slots existing) {})
        slots (reduce (fn [m slot-id]
                        (if (contains? m slot-id)
                          m
                          (assoc m slot-id (empty-slot slot-id))))
                      slots
                      slot-ids)
        slot-order (vec (distinct
                         (concat (or (:slot-order existing) slot-ids)
                                 (keys slots))))
        tenure {:slots slots
                :slot-order slot-order
                :next-turn (long (or (:next-turn existing) 0))
                :workspace (or (:workspace existing)
                               (:workspace runtime)
                               (get-in actors [:workspaces tid]))}]
    (-> actors
        (assoc-in [:tenures tid] tenure)
        (update :slot-ids #(vec (or % slot-ids)))
        (assoc-in [:workspaces tid] (:workspace runtime)))))

(defn restore!
  "Rebuild one live actor state from persisted STATE and RUNTIME.

  The runtime must already carry the recovered board.  A nil STATE creates a
  fresh slot state over that board (a legitimate rebirth from public work,
  not a replay of private memory).  Running attempts become
  `:outcome-unknown`; no model effect is emitted.  The caller wakes the
  restored state when the fleet is ready to resume it."
  [{:keys [runtime state models slot-ids now]
    :or {now 0}}]
  (if-not runtime
    (fail :RUNTIME_REQUIRED "an isolated swarm runtime is required" {})
    (let [tid (tenure-id (:swarm-id runtime))
          board (runtime/board runtime)
          state (or state {})
          persisted-models (or models (get-in state [:mission :models]))
          validated (try
                      (model/new-models persisted-models)
                      (catch Throwable t
                        {:error {:code :INVALID_MODELS
                                 :message (or (.getMessage t) (str t))}}))]
      (if (:error validated)
        (fail (get-in validated [:error :code])
              (get-in validated [:error :message])
              {:models persisted-models})
        (let [mission (normalize-mission (:mission state) runtime validated)
              actors (assoc (or (:actors state)
                                (mission-actors/new-state
                                 {:models validated
                                  :slot-ids (or slot-ids
                                                (:slot-ids state)
                                                [:slot-a :slot-b])
                                  :workspace-root (:workspace runtime)
                                  :workspaces {tid (:workspace runtime)}}))
                            :models validated)
              actors (ensure-tenure actors tid runtime slot-ids)
              recovered (recover-state
                         {:swarm-id (:swarm-id runtime)
                          :tenure-id tid
                          :mission mission
                          :actors actors}
                         board
                         {:now now})
              state' (:state recovered)
              reconciled (reconcile-slots state' board)]
          (if-not (:ok? reconciled)
            reconciled
            (let [state'' (:state reconciled)
                  mission' (assoc-in (:mission state'') [:boards tid] board)
                  synced (mission-actors/sync-tenure
                          mission' (:actors state'') tid)]
              (if-not (:ok? synced)
                (fail (get-in synced [:error :code] :ACTOR_SYNC_FAILED)
                      (get-in synced [:error :message]
                              "the restored actor state could not be synchronized")
                      {:swarm-id (:swarm-id runtime)
                       :error (:error synced)})
                (let [published (publish-board! runtime (:mission synced) board)]
                  (if-not (:ok? published)
                    (board-conflict runtime 1)
                    (let [mission'' (:mission published)
                          actors'' (:actors synced)
                          state''' (assoc state''
                                          :swarm-id (:swarm-id runtime)
                                          :tenure-id tid
                                          :runtime runtime
                                          :mission mission''
                                          :actors actors'')]
                      {:ok? true
                       :state state'''
                       :recovery {:attempts (:recovered-attempts recovered)
                                  :adopted (:adopted reconciled)
                                  :sync-effects (:effects synced)}
                       :effects []})))))))))))

(defn snapshot-state
  "Return the pure, persistable part of ACTOR-STATE.

  The live runtime, effects, and terminal call history are omitted.  Active
  model calls stay so an in-flight attempt can be fixed as
  `:outcome-unknown`; a late event for a dropped historical call is rejected
  as `:UNKNOWN_ATTEMPT`, which is safe after a restart."
  [state]
  (when state
    (let [tenures (vals (get-in state [:actors :tenures]))
          active (disj
                  (into #{}
                        (concat
                         (mapcat (fn [tenure]
                                   (keep :attempt-id (vals (:slots tenure))))
                                 tenures)
                         [(get-in state [:actors :plan-worker :attempt-id])]))
                  nil)
          calls (get-in state [:actors :calls])
          calls' (into {}
                       (filter (fn [[attempt-id _]]
                                 (contains? active attempt-id)))
                       calls)]
      (if (map? (:actors state))
        (-> state
            (select-keys [:swarm-id :tenure-id :mission :actors])
            (update :mission dissoc :boards)
            (assoc-in [:actors :calls] calls'))
        (select-keys state [:swarm-id :tenure-id :mission])))))

(defn wake-all
  "Wake every idle live slot from an existing actor STATE.

  The returned effects are the real `mission.actors` `:model/start` maps.
  Call this again after accepting a completion; a completed slot becomes idle
  and may receive the next turn.  The board is published with compare-and-set;
  a concurrent direct board command forces a pure retry instead of being
  overwritten."
  [state]
  (if-not state
    (fail :ACTOR_STATE_REQUIRED "actor state is required" {})
    (let [runtime (:runtime state)
          tid (:tenure-id state)]
      (loop [attempt 1]
        (let [mission (mission-with-runtime-board state)
              base (get-in mission [:boards tid])
              result (mission-actors/wake-all
                      mission (:actors state) tid)]
          (if-not (:ok? result)
            result
            (let [published (publish-board! runtime (:mission result) base)]
              (cond
                (:ok? published)
                {:ok? true
                 :state (assoc state
                               :mission (:mission published)
                               :actors (:actors result))
                 :effects (:effects result)}

                (and (:conflict? published)
                     (< attempt max-board-attempts))
                (recur (inc attempt))

                :else
                (board-conflict runtime attempt)))))))))

(defn start!
  "Build the actor state for RUNTIME and wake all live slots.

  Options:
  `:runtime` (required), `:models {:main <id> :task <id>}` (required),
  `:slot-ids` (default `[:slot-a :slot-b]`).

  Returns `{:ok? true :state ... :effects [...]}`.  Each effect is the real
  `mission.actors` `:model/start` map; its request carries this worktree as
  `:workspace` and this board as `:board-snapshot`.  The board in the runtime
  kernel is synchronized to include the registered actors."
  [{:keys [runtime models slot-ids]
    :or {slot-ids [:slot-a :slot-b]}}]
  (let [validated (try
                    (model/new-models models)
                    (catch Throwable t
                      {:error {:code :INVALID_MODELS
                               :message (or (.getMessage t) (str t))}}))]
    (cond
      (nil? runtime)
      (fail :RUNTIME_REQUIRED "an isolated swarm runtime is required" {})

      (:error validated)
      (fail (get-in validated [:error :code])
            (get-in validated [:error :message])
            {:models models})

      :else
      (let [tid (tenure-id (:swarm-id runtime))
            mission (fleet-mission runtime validated)
            actors (mission-actors/new-state
                    {:models validated
                     :slot-ids slot-ids
                     :workspace-root (:workspace runtime)
                     :workspaces {tid (:workspace runtime)}})
            state {:swarm-id (:swarm-id runtime)
                   :tenure-id tid
                   :runtime runtime
                   :mission mission
                   :actors actors}]
        (wake-all state)))))


(def ^:private terminal-events
  #{:completed :failed :cancelled})

(defn- terminal-event?
  [event]
  (contains? terminal-events (:event event)))

(defn- tool-call-id
  [attempt-id event]
  (or (:call-id event)
      (str attempt-id "/tool-" (:output-index event 0))))

(defn- live-call-state
  "Return CALL, SLOT, and whether ATTEMPT-ID is the slot's current running
  attempt.  The board and actor state use different nesting: calls live under
  `[:actors :calls]`, slots under `[:actors :tenures]`."
  [state attempt-id]
  (let [call (get-in state [:actors :calls attempt-id])
        tenure-id (:tenure-id call)
        slot-id (:slot-id call)
        slot (get-in state [:actors :tenures tenure-id :slots slot-id])]
    {:call call
     :slot slot
     :tenure-id tenure-id
     :slot-id slot-id
     :live? (boolean
             (and call slot
                  (= attempt-id (:attempt-id slot))
                  (= :running (:status slot))
                  (= slot-id (:slot-id call))
                  (= (:agent-id call) (:agent-id slot))))}))

(defn- board-actor-alive?
  "True when CALL's agent is still alive on the runtime board.

  A direct board command can retire an identity without updating the private
  actor state.  The tool-call reservation checks this boundary before an
  external side effect; result recording stays with the actor state so a
  later wake can still rebirth the slot."
  [state call]
  (let [board (when-let [runtime (:runtime state)]
                (runtime/board runtime))
        board-actor (get-in board [:actors (:agent-id call)])]
    (or (nil? board)
        (= :alive (:life-state board-actor)))))

(defn- stale-terminal
  "Record a terminal that no longer owns the slot as evidence.

  It never clears the slot's current attempt, so a late completion cannot
  cancel the next model turn or overwrite the new context."
  [state attempt-id reason]
  (let [call (get-in state [:actors :calls attempt-id])]
    {:ok? true
     :state (assoc-in state [:actors :calls attempt-id :status]
                      :stale-terminal)
     :effects [{:type :actor/stale-terminal
                :tenure-id (:tenure-id call)
                :slot-id (:slot-id call)
                :attempt-id attempt-id
                :reason reason}]}))

(defn accept-event
  "Feed one model event into the per-swarm actor state.

  STATE is the `:state` returned by `start!`.  The runtime board is synced
  after the transition with compare-and-set; a concurrent direct board
  command forces a pure retry instead of being overwritten.  A terminal for
  an attempt that already ended (a new turn is running, or the turn ended in
  a tool call) is recorded as stale evidence and never clears the current
  slot.  A completed event that published tool calls is deferred to the tool
  continuation instead of closing the slot.  Returns
  `{:ok? true :state ... :effects [...]}`."
  [state attempt-id event]
  (if-not state
    (fail :ACTOR_STATE_REQUIRED "actor state is required" {})
    (let [runtime (:runtime state)
          tid (:tenure-id state)
          {:keys [call slot live?]} (live-call-state state attempt-id)]
      (cond
        (and (terminal-event? event)
             (:tool-call-status call))
        (stale-terminal state attempt-id :tool-call-pending)

        (and (= :completed (:event event))
             (seq (:published-call-ids event))
             live?)
        {:ok? true
         :state state
         :effects [{:type :actor/tool-call-pending
                    :tenure-id (:tenure-id call)
                    :slot-id (:slot-id call)
                    :attempt-id attempt-id}]}

        (and (= :chunk (:event event))
             (contains? #{:completed :failed :cancelled :stale-terminal
                          :tool-call}
                        (:status call)))
        {:ok? true
         :state state
         :effects []}

        (and (terminal-event? event)
             slot
             (not live?))
        (stale-terminal state attempt-id :stale-attempt)

        :else
        (loop [attempt 1]
          (let [mission (mission-with-runtime-board state)
                base (get-in mission [:boards tid])
                result (mission-actors/accept-model-event
                        mission (:actors state) attempt-id event)]
            (if-not (:ok? result)
              result
              (let [published (publish-board! runtime (:mission result) base)]
                (cond
                  (:ok? published)
                  {:ok? true
                   :state (assoc state
                                 :mission (:mission published)
                                 :actors (:actors result))
                   :effects (:effects result)}

                  (and (:conflict? published)
                       (< attempt max-board-attempts))
                  (recur (inc attempt))

                  :else
                  (board-conflict runtime attempt))))))))))

(defn claim-tool-call
  "Reserve ATTEMPT-ID's tool call before its external side effect.

  The reservation is actor state, not a board change: the call records the
  tool-call id and `:tool-call-status :executing`.  The owner must serialize
  this transition with every other actor mutation for the same swarm; the
  fleet service does that with its per-swarm actor monitor.  A second claim
  for the same live attempt is rejected, so the side effect cannot be
  dispatched twice.  The tool itself runs outside the monitor;
  `continue-after-tool!` then records the result."
  [state attempt-id event]
  (let [{:keys [call live? tenure-id slot-id]}
        (live-call-state state attempt-id)]
    (cond
      (nil? call)
      (fail :UNKNOWN_ATTEMPT
            "no actor call for attempt-id"
            {:attempt-id attempt-id})

      (not live?)
      (fail :STALE_ATTEMPT
            "the tool call does not belong to the slot's current running attempt"
            {:attempt-id attempt-id
             :slot-id slot-id
             :current-attempt-id (get-in state
                                         [:actors :tenures tenure-id
                                          :slots slot-id :attempt-id])})

      (not (board-actor-alive? state call))
      (fail :STALE_ATTEMPT
            "the board actor is no longer alive"
            {:attempt-id attempt-id
             :slot-id slot-id
             :agent-id (:agent-id call)})

      (:tool-call-status call)
      (fail :TOOL_CALL_ALREADY_CLAIMED
            "this model attempt already claimed a tool call"
            {:attempt-id attempt-id
             :tool-call-id (tool-call-id attempt-id event)
             :claimed-tool-call-id (:tool-call-id call)})

      :else
      (let [tool-call-id (tool-call-id attempt-id event)]
        {:ok? true
         :state (assoc state :actors
                       (assoc-in (:actors state) [:calls attempt-id]
                                 (assoc call
                                        :tool-call-id tool-call-id
                                        :tool-call-status :executing)))
         :effects []}))))

(defn continue-after-tool!
  "Append one tool result to the calling slot and wake the next turn.

  The tool result is rendered as EDN+UFSS text in a `:role :tool` message.
  `mission.actors/wake-all` then builds the next model request from the
  updated context.  The board is published with compare-and-set; a concurrent
  direct board command forces a pure retry instead of being overwritten.
  A result for an attempt that no longer owns the slot is rejected as stale;
  a replayed tool-call id is recorded once and never starts a second turn.
  Returns `{:ok? true :state ... :effects [...]}`; the caller must start those
  real `:model/start` effects."
  [state attempt-id event tool-result]
  (let [{:keys [call live? tenure-id slot-id]}
        (live-call-state state attempt-id)
        tool-call-id (tool-call-id attempt-id event)
        failed? (not (contains? #{:succeeded :ok} (:status tool-result)))
        text (ufss/write-str tool-result)]
    (cond
      (nil? call)
      (fail :UNKNOWN_ATTEMPT
            "no live actor slot for this tool call"
            {:attempt-id attempt-id})

      (not live?)
      (fail :STALE_ATTEMPT
            "the tool result does not belong to the slot's current running attempt"
            {:attempt-id attempt-id
             :slot-id slot-id
             :current-attempt-id (get-in state
                                         [:actors :tenures tenure-id
                                          :slots slot-id :attempt-id])})

      (and (:tool-call-status call)
           (not= tool-call-id (:tool-call-id call)))
      (fail :TOOL_CALL_MISMATCH
            "the tool result does not match the claimed tool call"
            {:attempt-id attempt-id
             :tool-call-id tool-call-id
             :claimed-tool-call-id (:tool-call-id call)})

      :else
      (let [mission (mission-with-runtime-board state)
            accepted (mission-actors/accept-tool-result
                      mission (:actors state) attempt-id event text failed?)
            recorded? (some #(= :actor/tool-result (:type %))
                            (:effects accepted))]
        (if-not (:ok? accepted)
          accepted
          (if-not recorded?
            ;; The same tool-call id is already in the context, or no live
            ;; agent can receive it.  Do not start another model turn.
            {:ok? true
             :state state
             :effects (:effects accepted)}
            (let [actors' (-> (:actors accepted)
                              (assoc-in [:tenures tenure-id :slots slot-id
                                         :status] :idle)
                              (assoc-in [:tenures tenure-id :slots slot-id
                                         :attempt-id] nil)
                              (assoc-in [:calls attempt-id :status] :tool-call)
                              (assoc-in [:calls attempt-id
                                         :tool-call-status] :completed))
                  runtime (:runtime state)]
              (loop [attempt 1]
                (let [mission (mission-with-runtime-board state)
                      base (get-in mission [:boards tenure-id])
                      result (mission-actors/wake-all
                              mission actors' tenure-id)]
                  (if-not (:ok? result)
                    result
                    (let [published (publish-board! runtime
                                                    (:mission result) base)]
                      (cond
                        (:ok? published)
                        {:ok? true
                         :state (assoc state
                                       :mission (:mission published)
                                       :actors (:actors result))
                         :effects (:effects result)}

                        (and (:conflict? published)
                             (< attempt max-board-attempts))
                        (recur (inc attempt))

                        :else
                        (board-conflict runtime attempt)))))))))))))

(defn board
  [state]
  (when state
    (get-in state [:mission :boards (:tenure-id state)])))

(defn actors
  [state]
  (:actors state))

(defn workspace
  [state]
  (when state
    (get-in state [:runtime :workspace])))
