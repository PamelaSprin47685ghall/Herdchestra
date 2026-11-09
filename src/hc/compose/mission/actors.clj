(ns hc.compose.mission.actors
  "Actual slot lifecycle for Plan dedicated workers and Swarm workers.

  This namespace owns the runtime identity layer above `hc.compose.mission`:

  - fixed swarm slots with never-reused agent ids;
  - wake-all model turns for every live slot;
  - per-agent message context (a reborn agent starts empty);
  - token holder death/rebirth from board state;
  - board-derived work prompts, never the Reviewer ledger;
  - one reused Plan dedicated worker per Plan tenure.

  The functions are pure state-in/state-out over `[mission-state actor-state]`.
  The caller (Owner) commits both values and dispatches the returned model
  start/cancel effects.  A real model gateway is injected by the caller; the
  actor state only records attempts and per-agent context.

  Model ownership is fixed here: every swarm worker, holder, and Plan
  dedicated worker call is `:task` with the configured task model id.  No
  actor call can inherit a Reviewer/Plan main-model context."
  (:require [clojure.string :as str]
            [hc.compose.mission.host :as host]
            [hc.compose.mission.model :as model]
            [hc.core.relay :as relay]
            [hc.core.swarm-stm.board :as board]
            [hc.core.ufss :as ufss]))

;; ---------------------------------------------------------------------------
;; State

(defn new-state
  "Create an empty actor runtime.

  Options: `:models {:main :task}`, `:slot-ids` (fixed slot keywords, default
  `[:slot-a :slot-b]`), `:workspace-root`, and `:workspaces` (tenure-id ->
  worktree path).  The workspace is per swarm worktree, never master by
  default."
  [{:keys [models slot-ids workspace-root workspaces]
    :or {slot-ids [:slot-a :slot-b]}}]
  {:models (model/new-models models)
   :slot-ids (vec slot-ids)
   :workspace-root workspace-root
   :workspaces (or workspaces {})
   :tenures {}
   :plan-worker nil
   :calls {}
   :next-call-seq 0})

(defn- fail
  [code message details]
  {:ok? false
   :error (cond-> {:code code :message message}
            (some? details) (assoc :details details))})

(defn- ok
  [mission actors effects]
  {:ok? true
   :mission mission
   :actors actors
   :effects (vec effects)})

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

(defn- tenure-state
  [actors tenure-id]
  (or (get-in actors [:tenures tenure-id])
      {:slots (into {}
                    (map (fn [slot-id] [slot-id (empty-slot slot-id)]))
                    (:slot-ids actors))
       :slot-order (:slot-ids actors)
       :next-turn 0
       :workspace (or (get-in actors [:workspaces tenure-id])
                      (:workspace-root actors))}))

(defn- assoc-tenure-state
  [actors tenure-id tenure-state]
  (assoc-in actors [:tenures tenure-id] tenure-state))

(defn- slot
  [actors tenure-id slot-id]
  (get-in actors [:tenures tenure-id :slots slot-id]))

(defn- assoc-slot
  [actors tenure-id slot-id slot-state]
  (assoc-in actors [:tenures tenure-id :slots slot-id] slot-state))

(defn- next-call-id
  [actors prefix]
  (let [n (inc (long (or (:next-call-seq actors) 0)))]
    [(assoc actors :next-call-seq n) (str prefix "-" n)]))

;; ---------------------------------------------------------------------------
;; Board-derived work

(def ^:private max-task-field-bytes
  "UTF-8 byte cap for one rendered task field."
  4096)

(def ^:private max-board-block-bytes
  "UTF-8 byte cap for the complete EDN+UFSS board block."
  32768)

(def ^:private omitted-task-id-limit
  "How many omitted task ids the board block lists."
  20)

(def ^:private truncatable-task-fields
  [:goal :remaining-work :done-when :handoff-note])

(defn- utf8-byte-length
  ^long [^String s]
  (alength (.getBytes s "UTF-8")))

(defn- utf8-code-point-length
  ^long [^long cp]
  (cond
    (< cp 0x80) 1
    (< cp 0x800) 2
    (< cp 0x10000) 3
    :else 4))

(defn- truncate-utf8
  "Return the longest prefix of S with at most LIMIT UTF-8 bytes.

  Iteration advances by whole code points, so a valid surrogate pair is
  never split.  A malformed lone surrogate stops the prefix instead of
  putting an isolated surrogate into the model-facing text."
  [^String s limit]
  (let [n (.length s)]
    (loop [i 0
           used 0]
      (if (>= i n)
        s
        (let [c (.charAt s i)
              pair? (and (Character/isHighSurrogate c)
                         (< (inc i) n)
                         (Character/isLowSurrogate (.charAt s (inc i))))
              cp (if pair?
                   (Character/toCodePoint c (.charAt s (inc i)))
                   (int c))
              size (if (and (Character/isSurrogate c) (not pair?))
                     0
                     (utf8-code-point-length cp))
              next-i (if pair? (+ i 2) (inc i))]
          (cond
            (zero? size) (subs s 0 i)
            (> (+ used size) limit) (subs s 0 i)
            :else (recur next-i (+ used size))))))))

(defn- truncated-field
  [task field]
  (let [value (get task field)]
    (when (and (string? value)
               (> (utf8-byte-length value) max-task-field-bytes))
      [field
       (truncate-utf8 value max-task-field-bytes)
       (utf8-byte-length value)])))

(defn- truncate-task-fields
  "Cap each rendered task field at max-task-field-bytes.

  A truncated task map gains :truncated? true and :full-bytes, the largest
  original byte length among its truncated fields.  Untouched task maps are
  returned as the same value, preserving the existing rendered shape."
  [task]
  (let [truncated (keep #(truncated-field task %) truncatable-task-fields)]
    (if (empty? truncated)
      task
      (reduce (fn [m [field text full-bytes]]
                (-> m
                    (assoc field text)
                    (assoc :truncated? true)
                    (update :full-bytes (fnil max 0) full-bytes)))
              task
              truncated))))

(defn- task-needs-truncation?
  [task]
  (boolean
   (some (fn [field]
           (let [value (get task field)]
             (and (string? value)
                  (> (utf8-byte-length value) max-task-field-bytes))))
         truncatable-task-fields)))

(defn- public-task-view
  "Project one task into the fields a swarm actor may act on.

  The work prompt prints this view as EDN+UFSS so a goal containing quotes
  or newlines stays one string value instead of breaking the prompt."
  [task]
  (truncate-task-fields
   (cond-> (select-keys task [:task-id :state :goal])
     (:needs-token task) (assoc :needs-token true)
     (:ledger-key task) (assoc :ledger-key (:ledger-key task)))))

(defn- task-entry
  [board* task]
  {:view (public-task-view task)
   :root? (nil? (:parent-id task))
   :progressable? (board/task-progressable? board* task)})

(defn- omission-groups
  "Tail-first omission order, keeping roots and progressable tasks longest."
  [entries]
  (let [tail (reverse entries)]
    (concat
     (filter #(and (not (:root? %)) (not (:progressable? %))) tail)
     (filter #(and (not (:root? %)) (:progressable? %)) tail)
     (filter #(and (:root? %) (not (:progressable? %))) tail)
     (filter #(and (:root? %) (:progressable? %)) tail))))

(defn- block-text
  [tenure-id agent-id responsibility progressable ready-entries waiting-entries
   omitted-ids responsibility-omitted]
  (let [omitted (set omitted-ids)
        ready (->> ready-entries
                   (remove #(contains? omitted (:task-id (:view %))))
                   (mapv :view))
        waiting (->> waiting-entries
                     (remove #(contains? omitted (:task-id (:view %))))
                     (mapv :view))
        block (cond-> {:tenure-id tenure-id
                       :agent-id agent-id
                       :responsibility responsibility
                       :global-progressable-reasons (mapv name progressable)
                       :ready-tasks ready
                       :waiting-tasks waiting}
                (or (pos? (count omitted-ids))
                    (pos? responsibility-omitted))
                (assoc :board-truncated? true
                       :omitted-task-count (count omitted-ids)
                       :omitted-task-ids
                       (vec (take omitted-task-id-limit omitted-ids)))
                (pos? responsibility-omitted)
                (assoc :responsibility-truncated? true
                       :omitted-responsibility-count responsibility-omitted))]
    (ufss/write-str block)))

(defn- board-work-block
  [board* tenure-id agent-id responsibility progressable ready waiting]
  (let [responsibility (vec responsibility)
        ready-entries (mapv #(task-entry board* %) ready)
        waiting-entries (mapv #(task-entry board* %) waiting)
        omission-ids (->> (concat ready-entries waiting-entries)
                          omission-groups
                          (mapv (comp :task-id :view)))]
    (loop [omitted []]
      (let [text (block-text tenure-id agent-id responsibility progressable
                             ready-entries waiting-entries omitted 0)]
        (if (<= (utf8-byte-length text) max-board-block-bytes)
          text
          (if (< (count omitted) (count omission-ids))
            (recur (conj omitted (nth omission-ids (count omitted))))
            (loop [responsibility responsibility
                   omitted-responsibility 0]
              (let [text (block-text tenure-id agent-id responsibility
                                     progressable ready-entries waiting-entries
                                     omitted omitted-responsibility)]
                (cond
                  (<= (utf8-byte-length text) max-board-block-bytes) text
                  (empty? responsibility)
                  (throw (ex-info "board work prompt block exceeds its byte limit"
                                  {:code :PROMPT_TOO_LARGE
                                   :limit max-board-block-bytes
                                   :bytes (utf8-byte-length text)}))
                  :else
                  (recur (pop responsibility)
                         (inc omitted-responsibility)))))))))))

(defn public-board-snapshot
  "Return the public board view a swarm actor may see.

  It contains tasks, public actor identities, token state, inputs, and the
  derived progress indexes.  It does not contain the Reviewer ledger,
  findings, private conversation, or another agent's messages."
  [mission tenure-id]
  (when-let [board* (host/board-of mission tenure-id)]
    {:board-version (:board-version board*)
     :tasks (let [tasks (:tasks board*)]
              (if (some task-needs-truncation? (vals tasks))
                (update-vals tasks truncate-task-fields)
                tasks))
     :actors (into {}
                   (map (fn [[agent-id actor]]
                          [agent-id
                           (select-keys actor
                                        [:agent-id :slot-id :role :life-state
                                         :active-action-id :generation])]))
                   (:actors board*))
     :token-slot (:token-slot board*)
     :inputs (:inputs board*)
     :derived (select-keys (:derived board*)
                           [:public-ready-tasks :token-tasks
                            :global-progressable? :progressable-reasons
                            :responsibility])}))

(defn board-work-prompt
  "Build a worker/holder prompt from the public board only.

  The Reviewer ledger, findings, private conversation, and other agents'
  messages never enter this text.  The task lists are printed as one
  EDN+UFSS block so goal text stays a single string value."
  [mission tenure-id agent-id]
  (let [board* (host/board-of mission tenure-id)
        tasks (vals (:tasks board*))
        responsibility (sort-by str (board/responsibility board* agent-id))
        progressable (sort-by str (board/progressable-reasons board*))
        ready (->> tasks
                   (filter #(and (= :ready (:state %))
                                 (not (:needs-token %))
                                 (nil? (:owner-id %))))
                   (sort-by (comp str :task-id)))
        waiting (->> tasks
                     (filter #(= :waiting (:state %)))
                     (sort-by (comp str :task-id)))
        holder-id (get-in board* [:token-slot :holder-id])
        role-line (if (= agent-id holder-id)
                    "You are the Swarm holder. Every unfinished :needs-token task is your responsibility."
                    "You are an ordinary Swarm worker. You cannot run black-box Shell.")]
    [{:role :system
      :text (str "You are a decentralized Swarm individual. "
                 role-line
                 " Work from the public STM board, split before claiming, "
                 "and never wait for a central director. Use only your own "
                 "clj-worker capabilities. Do not read or infer the "
                 "centralized review state.")}
     {:role :user
      :text (str "Agent " agent-id " on tenure " tenure-id ".\n"
                 "Your responsibility: "
                 (if (seq responsibility)
                   (str/join ", " (map str responsibility))
                   "none yet; look for public ready work or a valuable split.")
                 "\nGlobal progressable reasons: "
                 (if (seq progressable)
                   (str/join ", " (map name progressable))
                   "none")
                 "\nPublic board (EDN+UFSS):\n"
                 (board-work-block board* tenure-id agent-id responsibility
                                   progressable ready waiting)
                 "\nDecide your next legal action from this board.")}]))

;; ---------------------------------------------------------------------------
;; Slot synchronization and rebirth

(defn- actor-alive?
  [board* agent-id]
  (and agent-id
       (= :alive (get-in board* [:actors agent-id :life-state]))))

(defn- holder?
  [board* agent-id]
  (= agent-id (get-in board* [:token-slot :holder-id])))

(defn sync-tenure
  "Reconcile fixed slots with one execution board.

  A missing/dead board actor is replaced in the same slot with a fresh
  never-reused agent id and an empty private context.  A running attempt for
  the old identity is cancelled.  A live holder is marked `:holder`; other
  live actors are `:worker`."
  [mission actors tenure-id]
  (let [tenure (host/tenure mission tenure-id)
        board* (host/board-of mission tenure-id)]
    (cond
      (nil? tenure)
      (fail :UNKNOWN_TENURE "execution tenure does not exist"
            {:tenure-id tenure-id})

      (not= :active (:swarm-phase tenure))
      (fail :NOT_IN_SWARM "slot sync requires Phase B"
            {:tenure-id tenure-id
             :swarm-phase (:swarm-phase tenure)})

      (nil? board*)
      (fail :MISSION_BOARD_MISSING "execution tenure has no board"
            {:tenure-id tenure-id})

      :else
      (let [initial (tenure-state actors tenure-id)]
        (let [[mission actors effects]
              (reduce
               (fn [[mission actors effects] slot-id]
                 (let [current (slot actors tenure-id slot-id)
                       agent-id (:agent-id current)
                       alive? (actor-alive? board* agent-id)]
                   (if alive?
                     [mission
                      (assoc-slot actors tenure-id slot-id
                                  (assoc current
                                         :role (if (holder? board* agent-id)
                                                 :holder
                                                 :worker)))
                      effects]
                     (let [cancel-effect
                           (when (and (:attempt-id current)
                                      (= :running (:status current)))
                             [{:type :model/cancel
                               :attempt-id (:attempt-id current)
                               :tenure-id tenure-id
                               :slot-id slot-id
                               :reason :actor-dead}])
                           registered (host/register-swarm-actor-state
                                       mission
                                       {:tenure-id tenure-id
                                        :slot-id slot-id})]
                       (if-not (:ok? registered)
                         [mission
                          (assoc-slot actors tenure-id slot-id
                                      (assoc current :status :dead))
                          (conj effects
                                {:type :actor/register-failed
                                 :tenure-id tenure-id
                                 :slot-id slot-id
                                 :error (:error registered)})]
                         (let [new-agent-id (get-in registered [:receipt :agent-id])
                               next-generation (inc (long (:generation current 0)))
                               new-slot (-> (empty-slot slot-id)
                                            (assoc :agent-id new-agent-id
                                                   :generation next-generation
                                                   :role :worker
                                                   :status :idle
                                                   :context []))]
                           [(:state registered)
                            (assoc-slot actors tenure-id slot-id new-slot)
                            (into (vec (concat effects cancel-effect))
                                  [{:type :actor/rebirth
                                    :tenure-id tenure-id
                                    :slot-id slot-id
                                    :old-agent-id agent-id
                                    :agent-id new-agent-id
                                    :generation next-generation
                                    :model-role :task
                                    :model-id (model/model-for (:models actors)
                                                               :worker)}])]))))))
               [mission
                (assoc-tenure-state actors tenure-id initial)
                []]
               (:slot-order initial))]
          (ok mission actors effects))))))

;; ---------------------------------------------------------------------------
;; Wake all

(defn- model-request
  [actors mission {:keys [attempt-id role mission-role model-role tenure-id
                          agent-id messages extra]}]
  (let [mission-role (or mission-role role)
        model-role (or model-role
                       (model/model-role-for mission-role))]
    (merge {:attempt-id attempt-id
            ;; The provider sees the model role (:main/:task), not the
            ;; mission role.  The mission role stays explicit for routing.
            :role model-role
            :mission-role mission-role
            :model-role model-role
            :model-id (model/model-for (:models actors) mission-role)
            :tenure-id tenure-id
            :agent-id agent-id
            :messages messages
            :draft-path (get-in mission [:relay :draft :path])}
           extra)))

(defn- wake-all*
  "Start one model turn for every live slot that is idle.

  The request carries only that slot's own context plus the board-derived
  prompt.  All calls use the configured task model.  Effects are
  `:model/start` maps; the caller dispatches them to its model gateway.

  `:skip-agent-ids` keeps a blocked actor idle instead of marking its slot
  running for a model call the caller will discard.  The caller uses it for
  the admission-refusal limit; the slot must stay idle so a later successful
  admission can wake it again."
  [mission actors tenure-id {:keys [skip-agent-ids]}]
  (let [skip (set skip-agent-ids)
        synced (sync-tenure mission actors tenure-id)]
    (if-not (:ok? synced)
      synced
      (let [mission (:mission synced)
            actors (:actors synced)
            board* (host/board-of mission tenure-id)
            initial (tenure-state actors tenure-id)
            progressable? (board/global-progressable? board*)]
        (loop [mission mission
               actors actors
               slot-ids (:slot-order initial)
               turn (long (:next-turn initial))
               effects (vec (:effects synced))]
          (if-let [slot-id (first slot-ids)]
            (let [current (slot actors tenure-id slot-id)
                  agent-id (:agent-id current)
                  holder? (holder? board* agent-id)
                  role (if holder? :holder :worker)
                  should-wake? (and (= :idle (:status current))
                                    agent-id
                                    progressable?
                                    (not (contains? skip agent-id)))]
              (if-not should-wake?
                (recur mission actors (next slot-ids) turn effects)
                (let [[actors attempt-id] (next-call-id
                                           actors
                                           (str "swarm-" tenure-id "-"
                                                (name slot-id)))
                      turn (inc turn)
                      messages (into (:context current)
                                     (board-work-prompt
                                      mission tenure-id agent-id))
                      request (model-request
                               actors mission
                               {:attempt-id attempt-id
                                :role role
                                :model-role :task
                                :tenure-id tenure-id
                                :agent-id agent-id
                                :messages messages
                                :extra {:program-role :clj-worker
                                        :holder? holder?
                                        :s1? false
                                        :write-unlocked? false
                                        :workspace (get-in actors
                                                           [:tenures tenure-id
                                                            :workspace])
                                        :board-snapshot
                                        (public-board-snapshot mission tenure-id)
                                        :board-version (:board-version board*)}})
                      call {:kind :swarm
                            :attempt-id attempt-id
                            :tenure-id tenure-id
                            :slot-id slot-id
                            :agent-id agent-id
                            :role role
                            :model-role :task
                            :model-id (:task (:models actors))
                            :call-id attempt-id
                            :output ""}
                      actors (-> actors
                                 (assoc-in [:calls attempt-id] call)
                                 (assoc-in [:tenures tenure-id :next-turn] turn)
                                 (assoc-slot tenure-id slot-id
                                             (assoc current
                                                    :status :running
                                                    :attempt-id attempt-id
                                                    :turn turn
                                                    :role role
                                                    :context messages)))]
                  (recur mission actors (next slot-ids) turn
                         (conj effects
                               {:type :model/start
                                :tenure-id tenure-id
                                :slot-id slot-id
                                :attempt-id attempt-id
                                :request request})))))
            (ok mission actors effects)))))))

(defn wake-all
  "Start one model turn for every live slot that is idle."
  [mission actors tenure-id]
  (wake-all* mission actors tenure-id {}))

(defn wake-all-skipping
  "Like `wake-all`, but keep SKIP-AGENT-IDS idle and do not mark them running."
  [mission actors tenure-id skip-agent-ids]
  (wake-all* mission actors tenure-id {:skip-agent-ids skip-agent-ids}))

;; ---------------------------------------------------------------------------
;; Model events

(defn- finish-swarm-call
  [mission actors attempt-id event]
  (let [call (get-in actors [:calls attempt-id])
        tenure-id (:tenure-id call)
        slot-id (:slot-id call)
        current (slot actors tenure-id slot-id)]
    (if (or (nil? current)
            (not= (:agent-id call) (:agent-id current)))
      ;; The slot was reborn after this attempt started.  A late terminal
      ;; belongs to the dead identity and must not overwrite the new
      ;; identity's context or status.
      (ok mission
          (assoc-in actors [:calls attempt-id :status] :stale-terminal)
          [{:type :actor/stale-terminal
            :tenure-id tenure-id
            :slot-id slot-id
            :attempt-id attempt-id
            :call-agent-id (:agent-id call)
            :current-agent-id (:agent-id current)}])
      (let [text (or (:text event) (:output call) "")
            terminal (case (:event event)
                       :completed :idle
                       :failed :idle
                       :cancelled :idle
                       :idle)
            context (cond-> (:context current)
                      (= :completed (:event event))
                      (conj {:role :assistant :text text})
                      (#{:failed :cancelled} (:event event))
                      ;; This is a synthetic failure note, not a tool result.
                      ;; A :tool message without :name/:tool-call-id is
                      ;; rejected by the model adapter.
                      (conj {:role :system
                             :text (str "model " (name (:event event))
                                        ": " (or (:message event) ""))}))]
        (ok mission
            (-> actors
                (assoc-slot tenure-id slot-id
                            (assoc current
                                   :status terminal
                                   :attempt-id nil
                                   :last-event (:event event)
                                   :context context))
                (assoc-in [:calls attempt-id :status] (:event event)))
            [])))))

(defn accept-model-event
  "Accept one model event for a swarm or Plan-worker attempt.

  Chunks accumulate in the call record.  Completion appends the assistant
  message to that agent's private context and returns the slot to idle.
  Plan-worker completion also fixes the mission worker report."
  [mission actors attempt-id event]
  (let [call (get-in actors [:calls attempt-id])]
    (cond
      (nil? call)
      (fail :UNKNOWN_ATTEMPT "no actor call for attempt-id"
            {:attempt-id attempt-id})

      (= :swarm (:kind call))
      (case (:event event)
        :chunk
        (ok mission
            (update-in actors [:calls attempt-id :output] str
                       (or (:text event) ""))
            [])

        (:completed :failed :cancelled)
        (finish-swarm-call mission actors attempt-id event)

        (ok mission actors []))

      (= :plan-worker (:kind call))
      (case (:event event)
        :chunk
        (ok mission
            (update-in actors [:calls attempt-id :output] str
                       (or (:text event) ""))
            [])

        (:completed :failed :cancelled)
        (let [status (if (= :completed (:event event)) :ok (:event event))
              report (or (:text event) (:output call) "")
              completed (host/devops-complete-state
                         mission
                         {:tenure-id (:tenure-id call)
                          :worker-id (:worker-id call)
                          :call-id (:call-id call)
                          :status status
                          :report report})
              worker (get-in actors [:plan-worker])]
          (if-not (:ok? completed)
            (ok mission
                (-> actors
                    (assoc-in [:plan-worker :status] :idle)
                    (assoc-in [:plan-worker :attempt-id] nil)
                    (assoc-in [:calls attempt-id :status] (:event event)))
                [{:type :plan-worker/report-rejected
                  :attempt-id attempt-id
                  :error (:error completed)}])
            (ok (:state completed)
                (-> actors
                    (assoc-in [:plan-worker :status] :idle)
                    (assoc-in [:plan-worker :attempt-id] nil)
                    (assoc-in [:plan-worker :context]
                              (conj (vec (:context worker))
                                    {:role :assistant :text report}))
                    (assoc-in [:calls attempt-id :status] (:event event)))
                [{:type :plan-worker/report
                  :attempt-id attempt-id
                  :worker-id (:worker-id call)
                  :report report}])))

        (ok mission actors []))

      :else
      (fail :UNKNOWN_ATTEMPT_KIND "unsupported actor call kind"
            {:attempt-id attempt-id :kind (:kind call)}))))

(defn- slot-id-for-agent
  "Return the slot id holding AGENT-ID under TENURE-ID, or nil."
  [actors tenure-id agent-id]
  (some (fn [[slot-id slot]]
          (when (= agent-id (:agent-id slot)) slot-id))
        (get-in actors [:tenures tenure-id :slots])))

(defn- tool-result-recorded?
  "True when CONTEXT already holds a tool result for TOOL-CALL-ID.

  `:tool-call-id` is the call identity, so a replay of the same call is
  skipped while a legitimate repeated call (same tool, new id) still
  appends.  Both the slot and Plan-worker paths use this one rule."
  [context tool-call-id]
  (boolean (some #(= tool-call-id (:tool-call-id %)) context)))

(defn accept-tool-result
  "Append one tool result to the private context of the agent that called it.

  ATTEMPT-ID is the parent model attempt, that is the Owner call's
  `:actor-attempt-id`.  The message mirrors
  `hc.compose.fleet.actors/continue-after-tool!`: a `:role :tool` entry with
  `:tool-call-id`, `:name`, and the rendered `:text`.  It never touches the
  shared conversation, so a worker's tool output cannot leak into the
  central Plan/Reviewer snapshot.

  Delivery is recoverable.  When the parent attempt record is gone but the
  event still names a live `:tenure-id` and `:agent-id`, the slot is resolved
  by that identity, so a result is not dropped just because the call record
  was pruned.  A reborn slot is a different identity: the result is kept as
  evidence and never appended to the new agent's context.  A `:tool-call-id`
  already in the context is ignored, so a replayed projection stays
  idempotent.

  Returns `{:ok? true :mission :actors :effects}` or
  `{:ok? false :error}`.  A failure means no live agent can receive the
  result; the caller must make that an explicit terminal state rather than
  retrying forever."
  [mission actors attempt-id event text failed?]
  (let [call (get-in actors [:calls attempt-id])
        tenure-id (or (:tenure-id call) (:tenure-id event))
        agent-id (or (:agent-id call) (:agent-id event))
        tool-call-id (or (:call-id event)
                         (str attempt-id "/tool-"
                              (:output-index event 0)))
        message {:role :tool
                 :tool-call-id tool-call-id
                 :name (or (:name event) (:tool-name event)
                           (:member event))
                 :text text
                 :error? (boolean failed?)}]
    (cond
      ;; The Plan dedicated worker keeps its own context and attempt id.
      (and (= :plan-worker (:kind call))
           (= attempt-id (get-in actors [:plan-worker :attempt-id])))
      (let [context (vec (get-in actors [:plan-worker :context] []))]
        (if (tool-result-recorded? context tool-call-id)
          ;; Same call identity already recorded: a replay, not a new tool
          ;; call.  A legitimate repeated call carries a new :tool-call-id,
          ;; so it still appends.
          (ok mission actors [])
          (ok mission
              (assoc-in actors [:plan-worker :context]
                        (conj context message))
              [{:type :actor/tool-result
                :kind :plan-worker
                :attempt-id attempt-id
                :worker-id (:worker-id call)
                :tool-call-id tool-call-id}])))

      (= :plan-worker (:kind call))
      (ok mission
          (assoc-in actors [:calls attempt-id :tool-result-status]
                    :stale-agent)
          [{:type :actor/stale-tool-result
            :kind :plan-worker
            :attempt-id attempt-id
            :worker-id (:worker-id call)
            :tool-call-id tool-call-id}])

      :else
      (let [slot-id (or (:slot-id call)
                        (when (and tenure-id agent-id)
                          (slot-id-for-agent actors tenure-id agent-id)))
            current (when (and tenure-id slot-id)
                      (slot actors tenure-id slot-id))]
        (cond
          (nil? tenure-id)
          (fail :UNKNOWN_ATTEMPT
                "no actor call or tenure identifies this result"
                {:attempt-id attempt-id})

          (nil? slot-id)
          (fail :UNKNOWN_ACTOR_SLOT
                "no live actor slot holds this agent"
                {:attempt-id attempt-id
                 :tenure-id tenure-id
                 :agent-id agent-id})

          (nil? current)
          (fail :UNKNOWN_ACTOR_SLOT
                "the resolved actor slot is not committed"
                {:attempt-id attempt-id
                 :tenure-id tenure-id
                 :slot-id slot-id})

          (not= agent-id (:agent-id current))
          (ok mission
              (assoc-in actors [:calls attempt-id :tool-result-status]
                        :stale-agent)
              [{:type :actor/stale-tool-result
                :tenure-id tenure-id
                :slot-id slot-id
                :attempt-id attempt-id
                :tool-call-id tool-call-id
                :call-agent-id agent-id
                :current-agent-id (:agent-id current)}])

          (tool-result-recorded? (:context current) tool-call-id)
          (ok mission actors [])

          :else
          (ok mission
              (assoc-slot actors tenure-id slot-id
                          (update current :context conj message))
              [{:type :actor/tool-result
                :tenure-id tenure-id
                :slot-id slot-id
                :agent-id (:agent-id current)
                :attempt-id attempt-id
                :tool-call-id tool-call-id}]))))))

;; ---------------------------------------------------------------------------
;; Plan dedicated worker

(defn plan-worker-request
  "Ensure/steer the one Plan dedicated worker and start its model turn.

  The worker keeps its own context and worker id across calls in the same
  Plan tenure.  A new Plan tenure or a dead worker produces a fresh id and
  empty context.  Every call uses the task model."
  [mission actors {:keys [call-id verbose-intent]}]
  (let [plan (relay/current-tenure (:relay mission) :plan)]
    (if (nil? plan)
      (fail :UNKNOWN_TENURE "Plan tenure is missing" {:lane :plan})
      (let [requested (host/devops-request-state
                       mission
                       {:tenure-id (:tenure-id plan)
                        :call-id call-id
                        :verbose-intent verbose-intent})]
        (if-not (:ok? requested)
          requested
          (let [mission' (:state requested)
                worker-id (get-in requested [:receipt :worker-id])
                existing (:plan-worker actors)
                same-worker? (and existing
                                  (= worker-id (:worker-id existing))
                                  (not= :dead (:status existing)))
                base-context (if same-worker? (:context existing) [])
                [actors attempt-id] (next-call-id
                                     actors
                                     (str "plan-worker-" worker-id))
                messages (conj base-context
                               {:role :user :text verbose-intent})
                request (model-request
                         actors mission'
                         {:attempt-id attempt-id
                          :role :plan-worker
                          :model-role :task
                          :tenure-id (:tenure-id plan)
                          :agent-id worker-id
                          :messages messages
                          :extra {:program-role :dedicated-worker
                                  :holder? false
                                  :s1? false
                                  :write-unlocked? false
                                  :workspace (or (get-in actors
                                                         [:workspaces :plan])
                                                 (:workspace-root actors))
                                  :board-snapshot nil
                                  :worker-id worker-id}})
                call {:kind :plan-worker
                      :attempt-id attempt-id
                      :call-id call-id
                      :tenure-id (:tenure-id plan)
                      :worker-id worker-id
                      :role :plan-worker
                      :model-role :task
                      :model-id (:task (:models actors))
                      :output ""}
                actors (-> actors
                           (assoc :plan-worker
                                  {:tenure-id (:tenure-id plan)
                                   :worker-id worker-id
                                   :status :running
                                   :attempt-id attempt-id
                                   :steer-seq (inc (long (or (:steer-seq existing) 0)))
                                   :context messages})
                           (assoc-in [:calls attempt-id] call))]
            (ok mission' actors
                [{:type :model/start
                  :tenure-id (:tenure-id plan)
                  :worker-id worker-id
                  :attempt-id attempt-id
                  :request request}])))))))

(defn plan-worker-report
  "Return the last completed report for the current Plan worker, if any."
  [actors]
  (->> (:context (:plan-worker actors))
       (filter #(= :assistant (:role %)))
       last
       :text))

(defn context
  "Return one slot's private context, or the Plan worker's context."
  ([actors tenure-id slot-id]
   (:context (slot actors tenure-id slot-id)))
  ([actors]
   (:context (:plan-worker actors))))

(defn slots
  "Return the slot records for TENURE-ID."
  [actors tenure-id]
  (vals (get-in actors [:tenures tenure-id :slots])))

(defn idle-slot-ids
  "Return slot ids that are idle and have a live agent identity."
  [actors tenure-id]
  (->> (slots actors tenure-id)
       (filter #(and (= :idle (:status %)) (:agent-id %)))
       (map :slot-id)))

(defn workspace
  "Return the worktree path for TENURE-ID, or the Plan workspace."
  ([actors tenure-id]
   (or (get-in actors [:workspaces tenure-id])
       (get-in actors [:tenures tenure-id :workspace])
       (:workspace-root actors)))
  ([actors]
   (or (get-in actors [:workspaces :plan])
       (:workspace-root actors))))

(defn set-workspaces
  "Associate tenure-id -> worktree path without changing slot identity."
  [actors workspaces]
  (update actors :workspaces merge workspaces))
