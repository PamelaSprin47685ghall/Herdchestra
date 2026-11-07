(ns hc.compose.mission.host
  "Host boundary for the Plan/Reviewer/Swarm role lifecycle.

  This namespace binds three independent state machines into one committed
  mission value:

  - `hc.core.relay`: obligation ledgers, review windows, tenures, barriers;
  - `hc.core.swarm-stm`: the per-tenure swarm board and root tasks;
  - `hc.compose.mission.worker`: the one Plan dedicated worker per tenure.

  Every public command computes a pure candidate value, then publishes it
  with one compare-and-set on the host atom.  A relay barrier, its successor
  binding, the retirement of the old tenure's board, and the new Reviewer's
  model binding therefore become visible together or not at all.  Effects
  are dispatched only after the commit; a failed effect cannot roll back a
  committed transition.

  The host never performs model calls, file I/O, or shell work inside a
  transition.  `devops!` is a convenience that runs an injected worker
  function between two separate commits."
  (:require [clojure.string :as str]
            [hc.compose.mission.control :as control]
            [hc.compose.mission.model :as model]
            [hc.compose.mission.proof :as proof]
            [hc.compose.mission.worker :as worker]
            [hc.core.relay :as relay]
            [hc.core.relay.tenure :as relay-tenure]
            [hc.core.swarm-stm.board :as board]
            [hc.core.swarm-stm.commands :as commands]))

(defrecord MissionHost [state dispatch!])

(defn- write-gate-proof?
  [value]
  (proof/valid? value))

;; ---------------------------------------------------------------------------
;; Small result helpers

(defn- fail
  ([state code message]
   (fail state code message nil))
  ([state code message details]
   {:ok? false
    :state state
    :error (cond-> {:code code :message message}
             (some? details) (assoc :details details))
    :effects []}))

(defn- ok
  [state receipt effects]
  {:ok? true
   :state state
   :receipt receipt
   :effects (vec effects)})

(defn- normalize-lane
  [lane]
  (cond
    (keyword? lane) lane
    (string? lane) (keyword lane)
    (symbol? lane) (keyword (name lane))
    :else lane))

(defn- relay-failure
  [state result]
  (let [error (:error result)]
    (fail state (:code error) (:message error) (:details error))))

(defn- tenure-failure
  [state tenure-id]
  (if (contains? (:retired-tenure-ids (:relay state)) tenure-id)
    (fail state :TENURE_RETIRED
          "tenure has retired" {:tenure-id tenure-id})
    (fail state :UNKNOWN_TENURE
          "tenure-id does not identify a live tenure"
          {:tenure-id tenure-id})))

(defn- effect-failure
  [state applied]
  (let [error (:error applied)]
    (fail state (:code error) (:message error) (:details error))))

(defn- board-error
  "Pure command failures carry their code under `:result`; the kernel
  flattens the same map.  Read both shapes so the host never reports nil."
  [result]
  (or (:error result) (get-in result [:result :error])))

(defn- board-detail
  [result]
  (or (:detail result) (get-in result [:result :detail])))

;; ---------------------------------------------------------------------------
;; Construction and accessors

(defn initial-state
  "Build the initial committed mission state for one Plan tenure.

  Required: `:models {:main <id> :task <id>}`.  Optional: `:session-id`,
  `:user-input`, `:user-attachments`, `:draft`, `:successor-hint`, and
  `:plan-milestone`.
  This is a pure constructor; it creates no atom and dispatches nothing."
  [{:keys [session-id user-input user-attachments draft successor-hint models
           plan-milestone owner-instance-id]
    :or {session-id "mission-1"
         plan-milestone :S1}}]
  (let [models (model/new-models models)
        relay-state (relay/new-state
                     {:session-id session-id
                      :user-input user-input
                      :user-attachments user-attachments
                      :draft draft
                      :successor-hint successor-hint
                      :plan-milestone plan-milestone})
        plan (relay/current-tenure relay-state :plan)
        plan-binding (model/bind-role models :plan
                                      {:tenure-id (:tenure-id plan)
                                       :milestone (:milestone plan)
                                       :status :active
                                       :phase :review
                                       :awaiting-user? false
                                       :reviewed-in-tenure? false})
        state {:relay relay-state
               :owner-instance-id (or owner-instance-id
                                      (str (random-uuid)))
               :boards {}
               :bindings {:plan plan-binding
                          :plan-worker {:worker nil :next-seq 1}
                          :execution {}}
               :models models
               :retired {}
               :requests {}
               :devops-requests {}
               :reopens []
               :extensions {}
               :draft-claim nil}]
    state))

(defn create
  "Create a mission host with one initial Plan tenure.

  This is the convenience atom wrapper around `initial-state`.  `:dispatch!`
  receives one effect map after each successful commit; it must not mutate
  the host atom.  An Owner that commits mission state in its own store uses
  `initial-state` and `apply-command` instead."
  [{:keys [dispatch!] :as opts}]
  (->MissionHost (atom (initial-state opts)) dispatch!))

(defn snapshot
  "Return the current immutable mission state."
  [host]
  @(:state host))

(defn relay-of
  "Return the relay state from a committed mission state."
  [state]
  (:relay state))

(defn board-of
  "Return the board for TENURE-ID, or nil."
  [state tenure-id]
  (get-in state [:boards tenure-id]))

(defn current-board
  "Return the board for the sole execution tenure, or nil when none or many."
  [state]
  (when-let [tenure (relay/current-tenure (:relay state) :execution)]
    (board-of state (:tenure-id tenure))))

(defn tenure
  "Return the relay tenure for TENURE-ID, or nil."
  [state tenure-id]
  (relay/tenure-by-id (:relay state) tenure-id))

(defn relay-state
  [host]
  (:relay (snapshot host)))

(defn models
  [host]
  (:models (snapshot host)))

(defn model-for-role
  [host role]
  (model/model-for (models host) role))

(defn role-binding
  "Return the committed binding for a role identity.

  `:plan-worker` returns the worker record, not a model binding."
  [state {:keys [role tenure-id agent-id]}]
  (case (keyword role)
    :plan (get-in state [:bindings :plan])
    :reviewer (get-in state [:bindings :execution tenure-id :reviewer])
    :worker (get-in state [:bindings :execution tenure-id :swarm agent-id])
    :holder (get-in state [:bindings :execution tenure-id :swarm agent-id])
    :plan-worker (get-in state [:bindings :plan-worker :worker])
    nil))

;; ---------------------------------------------------------------------------
;; Commit and effect dispatch

(defn- dispatch-effects!
  [host effects]
  (if-let [dispatch! (:dispatch! host)]
    (reduce (fn [errors effect]
              (try
                (dispatch! effect)
                errors
                (catch Throwable t
                  (conj errors {:effect-type (:type effect)
                                :message (or (.getMessage t) (str t))}))))
            []
            effects)
    []))

(defn- commit!
  "Apply one pure transition with CAS.  The transition is retried on a lost
  CAS; it must therefore be free of external side effects.  Effects are
  dispatched only after the winning commit."
  [host transition]
  (loop []
    (let [old @(:state host)
          result (try
                   (transition old)
                   (catch Throwable t
                     (fail old :MISSION_TRANSITION_FAILED
                           (or (.getMessage t) (str t))
                           {:type (.getName (class t))})))]
      (if-not (:ok? result)
        result
        (let [candidate (:state result)]
          (if (compare-and-set! (:state host) old candidate)
            (let [dispatch-errors (dispatch-effects! host (:effects result))]
              (cond-> (assoc result :state candidate)
                (seq dispatch-errors) (assoc :dispatch-errors dispatch-errors)))
            (recur)))))))

;; ---------------------------------------------------------------------------
;; Central role bindings

(defn- plan-binding-for
  [state tenure reviewed-in-tenure?]
  (model/bind-role (:models state) :plan
                   {:tenure-id (:tenure-id tenure)
                    :epoch (:epoch tenure)
                    :milestone (:milestone tenure)
                    :status (:status tenure)
                    :phase (if (= :pending (:milestone tenure)) :handoff :review)
                    :awaiting-user? (boolean (:awaiting-user? tenure))
                    :relay-pending? (boolean (:relay-pending? tenure))
                    :reviewed-in-tenure? (boolean reviewed-in-tenure?)}))

(defn- reviewer-binding-for
  [state tenure]
  (model/bind-role (:models state) :reviewer
                   {:tenure-id (:tenure-id tenure)
                    :epoch (:epoch tenure)
                    :milestone (:milestone tenure)
                    :status (:status tenure)
                    :phase (cond
                             (= :completed (:status tenure)) :completed
                             (= :active (:swarm-phase tenure)) :swarm
                             :else :review)
                    :review-window (:review-window tenure)
                    :awaiting-user? (boolean (:awaiting-user? tenure))
                    :relay-pending? (boolean (:relay-pending? tenure))}))

(defn- sync-central-bindings
  "Rebuild the Plan and Reviewer bindings from committed relay tenures.

  This is the model-ownership checkpoint: a milestone, status, or phase
  change cannot leave a stale binding behind."
  [state]
  (let [relay* (:relay state)
        plan (relay/current-tenure relay* :plan)
        state (if plan
                (assoc-in state [:bindings :plan]
                          (plan-binding-for
                           state plan
                           (get-in state [:bindings :plan
                                          :reviewed-in-tenure?])))
                state)]
    (reduce (fn [s [tenure-id tenure]]
              (assoc-in s [:bindings :execution tenure-id :reviewer]
                        (reviewer-binding-for s tenure)))
            state
            (relay/execution-tenures relay*))))

;; ---------------------------------------------------------------------------
;; Board construction

(defn- new-execution-board
  "Create a board with one control actor.

  The control actor performs only structural host writes such as creating
  the root tasks mirrored from a Reviewer ledger.  It is not an LLM role and
  therefore has no model binding."
  []
  (let [result (commands/execute (board/empty-board)
                                 {:op :register-actor
                                  :slot-id :mission-control}
                                 {})]
    (if (:ok? result)
      {:board (:board result)
       :control-agent-id (get-in result [:result :agent-id])}
      (throw (ex-info "mission: cannot create board control actor"
                      {:result result})))))

(defn- create-root-tasks
  [board* actor-id tasks]
  (loop [b board*
         remaining (seq tasks)
         mappings {}]
    (if-let [task (first remaining)]
      (let [result (commands/execute
                    b
                    {:op :create-task
                     :actor-id actor-id
                     :ledger-key (:ledger-key task)
                     :goal (:goal task)
                     :remaining-work (:remaining-work task)
                     :done-when (:done-when task)}
                    {})]
        (if (:ok? result)
          (recur (:board result)
                 (next remaining)
                 (assoc mappings
                        (get-in result [:result :task-id])
                        {:ledger-key (:ledger-key task)
                         :relay-task-id (:task-id task)
                         :state :ready}))
          (fail nil
                (board-error result)
                "swarm board rejected a root task"
                (merge {:ledger-key (:ledger-key task)}
                       (board-detail result)))))
      {:ok? true
       :board b
       :root-tasks mappings})))

;; ---------------------------------------------------------------------------
;; Relay effect application

(defn- apply-effect
  [state effect]
  (case (:type effect)
    :execution/start
    (let [{:keys [board control-agent-id]} (new-execution-board)
          tenure (:tenure effect)
          tenure-id (:tenure-id tenure)
          binding (reviewer-binding-for state tenure)
          state' (-> state
                     (assoc-in [:boards tenure-id] board)
                     (assoc-in [:bindings :execution tenure-id]
                               {:reviewer binding
                                :control-agent-id control-agent-id
                                :swarm {}
                                :root-tasks {}}))]
      {:ok? true
       :state state'
       :effect (assoc effect
                      :reviewer-binding binding
                      :board-version (:board-version board))})

    :swarm/start
    (let [tenure-id (:tenure-id effect)
          execution (get-in state [:bindings :execution tenure-id])
          actor-id (:control-agent-id execution)
          board* (get-in state [:boards tenure-id])]
      (if (or (nil? execution) (nil? board*) (nil? actor-id))
        (fail nil :MISSION_BOARD_MISSING
              "swarm start has no committed board and control actor"
              {:tenure-id tenure-id
               :has-binding? (some? execution)
               :has-board? (some? board*)
               :control-agent-id actor-id})
        (let [created (create-root-tasks board* actor-id (:root-tasks effect))]
          (if-not (:ok? created)
            created
            (let [state' (-> state
                             (assoc-in [:boards tenure-id] (:board created))
                             (assoc-in [:bindings :execution tenure-id :root-tasks]
                                       (:root-tasks created))
                             ;; Keep the obligation text the Reviewer wrote.
                             ;; `replace-root-task` updates the live goal but
                             ;; must not erase what `reopen` restores.
                             (update-in
                              [:relay :lanes :execution tenure-id :root-tasks]
                              (fn [tasks]
                                (mapv (fn [task]
                                        (assoc task :original-obligation
                                               (or (:original-obligation task)
                                                   (:goal task))))
                                      tasks))))]
              {:ok? true
               :state state'
               :effect (assoc effect
                              :board-root-tasks (:root-tasks created)
                              :board-version (:board-version (:board created))
                              :wake :all)})))))

    :relay/barrier
    (let [lane (:lane effect)
          successor (:successor effect)
          retired-id (:retired-tenure-id effect)]
      (if (= :plan lane)
        (let [binding (plan-binding-for state successor false)]
          {:ok? true
           :state (-> state
                      (assoc-in [:bindings :plan] binding)
                      (assoc-in [:bindings :plan-worker]
                                {:worker nil
                                 :next-seq (get-in state
                                                   [:bindings :plan-worker :next-seq]
                                                   1)})
                      (update :retired assoc retired-id
                              {:lane :plan
                               :barrier-id (:barrier-id effect)
                               :successor-id (:tenure-id successor)}))
           :effect (assoc effect :successor-binding binding)})
        (let [{:keys [board control-agent-id]} (new-execution-board)
              tenure-id (:tenure-id successor)
              binding (reviewer-binding-for state successor)]
          {:ok? true
           :state (-> state
                      (update :boards dissoc retired-id)
                      (assoc-in [:boards tenure-id] board)
                      (update-in [:bindings :execution] dissoc retired-id)
                      (assoc-in [:bindings :execution tenure-id]
                                {:reviewer binding
                                 :control-agent-id control-agent-id
                                 :swarm {}
                                 :root-tasks {}})
                      (update :retired assoc retired-id
                              {:lane :execution
                               :barrier-id (:barrier-id effect)
                               :successor-id (:tenure-id successor)}))
           :effect (assoc effect
                          :successor-binding binding
                          :successor-board-version (:board-version board))})))

    ;; Promotion, completion, and ask only change the tenure fields that
    ;; `sync-central-bindings` rebuilds.  They have no board side effect.
    {:ok? true :state state :effect effect}))

(defn- apply-effects
  [state effects]
  (loop [s state
         remaining (seq effects)
         applied []]
    (if-let [effect (first remaining)]
      (let [result (apply-effect s effect)]
        (if (:ok? result)
          (recur (:state result) (next remaining) (conj applied (:effect result)))
          result))
      {:ok? true :state s :effects applied})))

(defn- finalize-relay-state
  [state]
  (sync-central-bindings state))

(defn- augment-receipt
  [receipt effects]
  (reduce (fn [r effect]
            (case (:type effect)
              :swarm/start
              (assoc r
                     :board-root-tasks (:board-root-tasks effect)
                     :board-version (:board-version effect))

              :execution/start
              (assoc r
                     :reviewer-binding (:reviewer-binding effect)
                     :board-version (:board-version effect))

              :relay/barrier
              (assoc r :successor-binding (:successor-binding effect))

              r))
          receipt
          effects))

;; ---------------------------------------------------------------------------
;; Board snapshots

(defn board-snapshot
  "Build the relay board snapshot from a committed swarm board.

  Board-derived counts are exact.  Facts owned outside the board
  (`:ff-ready?`, `:call-children`, `:report-pending`, `:pending-rebase`,
  `:holds-write-lock?`) come from FACTS and default to false/zero.  The
  closing check requires an explicit `:ff-ready? true`.  `:unhandled-inputs`
  counts the board's public input events that have not been handled; the
  relay barrier must not retire a tenure while one remains.  `:user-inputs`
  carries only handled raw text plus an optional `:message-id`; resolutions
  and agent reasoning stay on the board."
  ([board*]
   (board-snapshot board* {}))
  ([board* facts]
   (let [facts (or facts {})
         tasks (vals (:tasks board*))
         inputs (vals (:inputs board*))
         actions (vals (:actions board*))
         phase-count (fn [phase]
                       (count (filter #(= phase (:phase %)) actions)))
         roots (filter #(nil? (:parent-id %)) tasks)
         holder (get-in board* [:token-slot :holder-id])
         applying (phase-count :applying)
         running (phase-count :running)
         uncertain (phase-count :uncertain)]
     {:unresolved-tasks (count (remove #(= :done (:state %)) tasks))
      :unhandled-inputs (count (filter #(= :unhandled (:state %)) inputs))
      :user-inputs (->> inputs
                        (filter #(= :handled (:state %)))
                        (mapv (fn [input]
                                (cond-> {:text (:text input)}
                                  (and (string? (:message-id input))
                                       (not (str/blank? (:message-id input))))
                                  (assoc :message-id (:message-id input))
                                  (seq (:attachments input))
                                  (assoc :attachments (:attachments input))))))
      :inflight-actions (+ applying running uncertain)
      :applying applying
      :running running
      :uncertain uncertain
      :holder-retired? (or (nil? holder)
                           (not= :alive (get-in board* [:actors holder
                                                        :life-state])))
      :all-root-tasks-done? (every? #(= :done (:state %)) roots)
      :ff-ready? (true? (:ff-ready? facts))
      :call-children (long (or (:call-children facts) 0))
      :report-pending (long (or (:report-pending facts) 0))
      :pending-rebase (long (or (:pending-rebase facts) 0))
      :holds-write-lock? (true? (:holds-write-lock? facts))
      :unresolved-token-tasks
      (count (filter #(and (not= :done (:state %)) (:needs-token %)) tasks))})))

(defn board-summary
  "Return the flat relay snapshot for one execution tenure.

  This is the Owner-facing name.  `facts` carries host-owned counts such as
  `:ff-ready?`, `:call-children`, `:report-pending`, `:pending-rebase`, and
  `:holds-write-lock?`.  Board-derived `:unhandled-inputs` and raw
  `:user-inputs` are included so the barrier cannot discard or hide a public
  input event."
  ([state tenure-id]
   (board-summary state tenure-id {}))
  ([state tenure-id facts]
   (if-let [board* (get-in state [:boards tenure-id])]
     (board-snapshot board* facts)
     nil)))

;; ---------------------------------------------------------------------------
;; Request idempotency for host-owned board commands

(defn- cached-request
  [state request-id op payload]
  (when request-id
    (when-let [stored (get-in state [:requests request-id])]
      (if (= [op payload] [(:op stored) (:payload stored)])
        {:replayed true :receipt (:receipt stored)}
        {:conflict true :stored stored}))))

(defn- remember-request
  [state request-id op payload receipt]
  (if request-id
    (assoc-in state [:requests request-id]
              {:request-id request-id
               :op op
               :payload payload
               :receipt receipt})
    state))

(defn- replay-result
  [state cached]
  (if (:conflict cached)
    (fail state :REQUEST_ID_CONFLICT
          "request-id was already used with a different payload"
          {:request-id (get-in cached [:stored :request-id])})
    (ok state (assoc (:receipt cached) :replayed true) [])))

(defn- cached-devops-request
  "Devops call-id idempotency lives in its own namespace.

  A call-id is not a board request-id; sharing the `:requests` map would make
  a coincidentally equal id look like a payload conflict."
  [state call-id payload]
  (when call-id
    (when-let [stored (get-in state [:devops-requests call-id])]
      (if (= payload (:payload stored))
        {:replayed true :receipt (:receipt stored)}
        {:conflict true :stored stored}))))

(defn- remember-devops-request
  [state call-id payload receipt]
  (if call-id
    (assoc-in state [:devops-requests call-id]
              {:request-id call-id
               :payload payload
               :receipt receipt})
    state))

;; ---------------------------------------------------------------------------
;; Plan draft ownership

(defn- draft-plan-tenure
  "Resolve the Plan tenure named by TENURE-ID for a draft command.

  A draft command must name the current Plan tenure.  A nil or stale id is
  refused even when a successor is live, so a late program cannot write into
  the successor's review window or draft lock."
  [state tenure-id]
  (let [plan (relay/current-tenure (:relay state) :plan)]
    (cond
      (nil? plan)
      [nil (fail state :UNKNOWN_TENURE
                 "Plan tenure is missing"
                 {:lane :plan :tenure-id tenure-id})]

      (nil? tenure-id)
      [nil (fail state :UNKNOWN_TENURE
                 "Plan draft requires the pinned tenure-id"
                 {:lane :plan
                  :current-plan-tenure-id (:tenure-id plan)})]

      (not= tenure-id (:tenure-id plan))
      [nil (if (contains? (:retired-tenure-ids (:relay state)) tenure-id)
             (fail state :TENURE_RETIRED
                   "Plan tenure has retired"
                   {:tenure-id tenure-id
                    :current-plan-tenure-id (:tenure-id plan)})
             (fail state :UNKNOWN_TENURE
                   "tenure-id is not the current Plan tenure"
                   {:tenure-id tenure-id
                    :current-plan-tenure-id (:tenure-id plan)}))]

      :else [plan nil])))

(defn set-draft-state
  "Write the Plan draft through the relay ownership record.

  A Plan tenure is read-only until its first accepted review.  The relay
  fixes the first path and freezes the draft after an execution tenure
  exists.  This function adds the per-tenure write lock from §2.3.  The
  command must name the current Plan tenure so a late program cannot write
  into a successor's draft window."
  [state {:keys [path content tenure-id]}]
  (let [[plan error] (draft-plan-tenure state tenure-id)]
    (if error
      error
      (cond
        (:awaiting-user? plan)
        (fail state :AWAITING_USER
              "Plan is suspended awaiting a human answer"
              {:lane :plan :tenure-id (:tenure-id plan)})

        (not (get-in state [:bindings :plan :reviewed-in-tenure?]))
        (fail state :WRITE_LOCKED_BEFORE_FIRST_REVIEW
              "Plan may write only after its first accepted review in this tenure"
              {:lane :plan
               :tenure-id (:tenure-id plan)
               :path path})

        :else
        (let [claim (:draft-claim state)]
          (if (and claim (not= path (:path claim)))
            (fail state :DRAFT_PATH_LOCKED
                  "a different first-write draft claim owns this tenure"
                  {:path path :claimed-path (:path claim)})
            (let [result (relay/set-draft (:relay state)
                                          {:path path :content content})]
              (if-not (:ok? result)
                (relay-failure
                 (cond-> state
                   (and claim (= path (:path claim))) (dissoc :draft-claim))
                 result)
                (ok (-> state
                        (assoc :relay (:state result))
                        (dissoc :draft-claim))
                    (:receipt result)
                    [])))))))))

(defn claim-draft-state
  "Reserve the first Plan draft path in the Owner transaction.

  The claim is not the final draft: it blocks a concurrent first writer from
  taking another name.  The final `set-draft` promotes it and clears the
  claim; a failed program releases it.  The claim is bound to the pinned
  Plan tenure and is refused after that tenure retires."
  [state {:keys [path request-id call-id tenure-id]}]
  (let [[plan error] (draft-plan-tenure state tenure-id)]
    (if error
      error
      (let [claim (:draft-claim state)
            fixed-path (get-in state [:relay :draft :path])]
        (cond
          (:awaiting-user? plan)
          (fail state :AWAITING_USER
                "Plan is suspended awaiting a human answer"
                {:lane :plan :tenure-id (:tenure-id plan)})

          (not (get-in state [:bindings :plan :reviewed-in-tenure?]))
          (fail state :WRITE_LOCKED_BEFORE_FIRST_REVIEW
                "Plan may write only after its first accepted review in this tenure"
                {:lane :plan :tenure-id (:tenure-id plan) :path path})

          (not (control/draft-path? path))
          (fail state :INVALID_DRAFT_PATH
                "draft path must match plans/<name>.md"
                {:path path})

          (seq (relay/execution-tenures (:relay state)))
          (fail state :DRAFT_LOCKED_AFTER_HANDOFF
                "draft is frozen once an execution tenure exists"
                {:path path})

          fixed-path
          (if (= path fixed-path)
            (cond
              (and claim
                   (= request-id (:request-id claim))
                   (= path (:path claim)))
              (ok state
                  {:status :accepted
                   :outcome :draft-claim-replayed
                   :path path
                   :request-id request-id}
                  [])

              claim
              (fail state :DRAFT_NAME_TAKEN
                    "another draft revision already owns this path"
                    {:path path
                     :claimed-path (:path claim)
                     :request-id request-id})

              ;; A fixed-path revision still needs an Owner-side
              ;; qualification so two concurrent revisions cannot both
              ;; believe they are the only writer.
              request-id
              (ok (assoc state :draft-claim
                         {:path path
                          :request-id request-id
                          :call-id call-id
                          :owner-instance-id (:owner-instance-id state)
                          :tenure-id (:tenure-id plan)})
                  {:status :accepted
                   :outcome :draft-claimed
                   :path path
                   :request-id request-id}
                  [])

              :else
              (ok state
                  {:status :accepted
                   :outcome :draft-already-fixed
                   :path path
                   :request-id request-id}
                  []))
            (fail state :DRAFT_PATH_LOCKED
                  "draft path is already fixed"
                  {:path path :locked-path fixed-path}))

          (nil? claim)
          (ok (assoc state :draft-claim
                     {:path path
                      :request-id request-id
                      :call-id call-id
                      :owner-instance-id (:owner-instance-id state)
                      :tenure-id (:tenure-id plan)})
              {:status :accepted
               :outcome :draft-claimed
               :path path
               :request-id request-id}
              [])

          (and (= request-id (:request-id claim))
               (= path (:path claim)))
          (ok state
              {:status :accepted
               :outcome :draft-claim-replayed
               :path (:path claim)
               :request-id request-id}
              [])

          (= request-id (:request-id claim))
          (fail state :CALL_REPLAY_MISMATCH
                "the same claim request-id was reused with a different path"
                {:path path
                 :claimed-path (:path claim)
                 :request-id request-id})

          (= path (:path claim))
          (ok state
              {:status :accepted
               :outcome :draft-claimed
               :path path
               :request-id (:request-id claim)}
              [])

          :else
          (fail state :DRAFT_NAME_TAKEN
                "another first-write claim already owns a draft name"
                {:path path
                 :claimed-path (:path claim)
                 :request-id request-id}))))))

(defn check-draft-state
  "Validate a Plan draft write without changing the mission.

  This is the workspace commit's early eligibility check.  It applies the
  same tenure, write-lock, path and handoff rules as `claim-draft-state` but
  never creates a claim; the first-write claim still happens under W in the
  commit's pre-apply hook."
  [state {:keys [tenure-id path]}]
  (let [[plan error] (draft-plan-tenure state tenure-id)]
    (if error
      error
      (let [claim (:draft-claim state)
            fixed-path (get-in state [:relay :draft :path])]
        (cond
          (:awaiting-user? plan)
          (fail state :AWAITING_USER
                "Plan is suspended awaiting a human answer"
                {:lane :plan :tenure-id (:tenure-id plan)})

          (not (get-in state [:bindings :plan :reviewed-in-tenure?]))
          (fail state :WRITE_LOCKED_BEFORE_FIRST_REVIEW
                "Plan may write only after its first accepted review in this tenure"
                {:lane :plan :tenure-id (:tenure-id plan) :path path})

          (not (control/draft-path? path))
          (fail state :INVALID_DRAFT_PATH
                "draft path must match plans/<name>.md"
                {:path path})

          (seq (relay/execution-tenures (:relay state)))
          (fail state :DRAFT_LOCKED_AFTER_HANDOFF
                "draft is frozen once an execution tenure exists"
                {:path path})

          fixed-path
          (if (= path fixed-path)
            (ok state
                {:status :accepted
                 :outcome :draft-already-fixed
                 :path path
                 :tenure-id (:tenure-id plan)
                 :reviewed-in-tenure? true
                 :draft-path fixed-path
                 :milestone (:milestone plan)
                 :handoff? false}
                [])
            (fail state :DRAFT_PATH_LOCKED
                  "draft path is already fixed"
                  {:path path :locked-path fixed-path}))

          (and claim (not= path (:path claim)))
          (fail state :DRAFT_NAME_TAKEN
                "another first-write claim already owns a draft name"
                {:path path
                 :claimed-path (:path claim)
                 :request-id (:request-id claim)})

          :else
          (ok state
              {:status :accepted
               :outcome :draft-checked
               :path path
               :tenure-id (:tenure-id plan)
               :reviewed-in-tenure? true
               :draft-path path
               :milestone (:milestone plan)
               :handoff? false}
              []))))))

(defn release-draft-state
  "Release a first-write claim after a failed apply or program."
  [state {:keys [path request-id]}]
  (let [claim (:draft-claim state)]
    (cond
      (nil? claim)
      (ok state
          {:status :accepted :outcome :draft-claim-absent :path path}
          [])

      (and request-id (= request-id (:request-id claim)))
      (ok (dissoc state :draft-claim)
          {:status :accepted
           :outcome :draft-claim-released
           :path (:path claim)
           :request-id (:request-id claim)}
          [])

      :else
      (fail state :DRAFT_CLAIM_MISMATCH
            "draft claim belongs to another request"
            {:path path
             :request-id request-id
             :claimed-path (:path claim)
             :claimed-request-id (:request-id claim)}))))

(defn set-draft!
  [host request]
  (commit! host #(set-draft-state % request)))

;; ---------------------------------------------------------------------------
;; Review, resume, and relay retry

(defn review-state
  "Submit a Plan or Reviewer review through the mission host.

  The relay state, the swarm board root-task mirror, the new execution
  board, and the successor role bindings commit together.  `:board-facts`
  supplies host-owned quiescence facts for execution reviews."
  [state {:keys [lane tenure-id call-id findings board-facts]}]
  (let [lane (normalize-lane lane)
        executions (relay/execution-tenures (:relay state))
        tenure-id (or tenure-id
                      (when (= lane :execution)
                        (when (= 1 (count executions))
                          (-> executions vals first :tenure-id))))
        findings (or findings [])]
    (let [board* (when (= lane :execution)
                   (get-in state [:boards tenure-id]))
          snapshot (when board* (board-snapshot board* board-facts))
            tenure (relay/tenure-by-id (:relay state) tenure-id)
            ;; A reopened tenure is not the initial Reviewer and has already
            ;; had its first review window.  An empty review is the
            ;; independent successor check, not a milestone promotion.
            successor-relay? (and (= lane :execution)
                                  (empty? findings)
                                  tenure
                                  (not (:initial? tenure))
                                  (not (:first-review? tenure))
                                  (= :execution (:milestone tenure))
                                  (empty? (:ledger tenure)))
            result (cond
                     (and (= lane :execution)
                          (nil? tenure-id)
                          (> (count executions) 1))
                   (fail state :AMBIGUOUS_TENURE
                         "several execution tenures exist; pass :tenure-id"
                         {:execution-tenures (vec (sort (keys executions)))})

                     successor-relay?
                     ;; `maybe-relay` is the host hook for a pending
                     ;; barrier.  A reopened tenure has no swarm phase yet,
                     ;; so mark the retry explicitly; `relay!` retires the
                     ;; tenure and the marker disappears with it.
                     (relay/maybe-relay (assoc-in (:relay state)
                                                  [:lanes :execution tenure-id
                                                   :relay-pending?] true)
                                        {:lane :execution
                                  :tenure-id tenure-id
                                         :board snapshot})

                     :else
                   (relay/review (:relay state)
                                 {:call-id call-id
                                  :lane lane
                                  :tenure-id tenure-id
                                  :findings findings
                                  :board snapshot}))]
      (if-not (:ok? result)
        (relay-failure state result)
        (let [applied (apply-effects
                       (assoc state :relay (:state result))
                       (:effects result))]
          (if-not (:ok? applied)
            (effect-failure state applied)
            (let [state' (cond-> (:state applied)
                           (and (= lane :plan)
                                (true? (get-in result [:receipt :reviewed?]))
                                (relay/tenure-by-id
                                 (:relay (:state applied))
                                 tenure-id))
                           (assoc-in [:bindings :plan
                                      :reviewed-in-tenure?] true))
                  state' (finalize-relay-state state')]
              (ok state'
                  (augment-receipt (:receipt result) (:effects applied))
                  (:effects applied)))))))))

(defn review!
  [host request]
  (commit! host #(review-state % request)))

(defn resume-state
  "Clear an `:awaiting-user?` suspension after `(unpark ...)`."
  [state {:keys [lane tenure-id]}]
  (let [lane (normalize-lane lane)]
    (let [result (relay/resume (:relay state)
                               {:lane lane :tenure-id tenure-id})]
      (if-not (:ok? result)
        (relay-failure state result)
        (let [state' (finalize-relay-state
                      (assoc state :relay (:state result)))]
          (ok state' (:receipt result) []))))))

(defn mark-awaiting-state
  "Mark the tenure of a projected program park as awaiting a human answer."
  [state {:keys [lane tenure-id]}]
  (let [result (relay/mark-awaiting (:relay state)
                                    {:lane lane :tenure-id tenure-id})]
    (if-not (:ok? result)
      (relay-failure state result)
      (let [state' (finalize-relay-state (assoc state :relay (:state result)))]
        (ok state' (:receipt result) [])))))

(defn mark-awaiting!
  [host request]
  (commit! host #(mark-awaiting-state % request)))

(defn resume!
  [host request]
  (commit! host #(resume-state % request)))

(defn maybe-relay-state
  "Retry a pending execution barrier with a fresh board snapshot."
  [state {:keys [tenure-id board-facts]}]
  (let [board* (get-in state [:boards tenure-id])]
    (if (nil? board*)
      (fail state :MISSION_BOARD_MISSING
            "execution tenure has no committed board"
            {:tenure-id tenure-id})
      (let [snapshot (board-snapshot board* board-facts)
            result (relay/maybe-relay
                    (:relay state)
                    {:lane :execution
                     :tenure-id tenure-id
                     :board snapshot})]
        (if-not (:ok? result)
          (relay-failure state result)
          (let [applied (apply-effects
                         (assoc state :relay (:state result))
                         (:effects result))]
            (if-not (:ok? applied)
              (effect-failure state applied)
              (let [state' (finalize-relay-state (:state applied))]
                (ok state'
                    (augment-receipt (:receipt result) (:effects applied))
                    (:effects applied))))))))))

(defn maybe-relay!
  [host request]
  (commit! host #(maybe-relay-state % request)))

;; ---------------------------------------------------------------------------
;; Swarm actor lifecycle

(defn register-swarm-actor-state
  "Register one fixed-slot swarm actor on a live execution board.

  The actor is bound to the task model at registration.  The board's control
  actor is not a swarm role and has no model binding."
  [state {:keys [tenure-id slot-id]}]
  (let [tenure (relay/tenure-by-id (:relay state) tenure-id)
        board* (get-in state [:boards tenure-id])]
    (cond
                 (nil? tenure)
                 (tenure-failure state tenure-id)

                 (not= :execution (:lane tenure))
                 (fail state :TENURE_LANE_MISMATCH
                       "swarm actors belong to the execution lane"
                       {:tenure-id tenure-id :lane (:lane tenure)})

                 (not= :active (:status tenure))
                 (fail state :TENURE_RETIRED
                       "tenure is not active" {:tenure-id tenure-id})

                 (not= :active (:swarm-phase tenure))
                 (fail state :NOT_IN_SWARM
                       "swarm actors register during Phase B"
                       {:tenure-id tenure-id
                        :swarm-phase (:swarm-phase tenure)})

                 (nil? board*)
                 (fail state :MISSION_BOARD_MISSING
                       "execution tenure has no committed board"
                       {:tenure-id tenure-id})

                 :else
                 (let [result (commands/execute board*
                                                {:op :register-actor
                                                 :slot-id slot-id}
                                                {})]
                   (if-not (:ok? result)
                     (fail state (board-error result)
                           "swarm board rejected actor registration"
                           (board-detail result))
                     (let [agent-id (get-in result [:result :agent-id])
                           binding (model/bind-role
                                    (:models state) :worker
                                    {:tenure-id tenure-id
                                     :agent-id agent-id
                                     :slot-id (get-in result [:result :slot-id])
                                     :status :active
                                     :life-state :alive
                                     :phase :swarm})
                           state' (-> state
                                      (assoc-in [:boards tenure-id]
                                                (:board result))
                                      (assoc-in [:bindings :execution tenure-id
                                                 :swarm agent-id]
                                                binding))
                           receipt {:status :accepted
                                    :outcome :actor-registered
                                    :tenure-id tenure-id
                                    :agent-id agent-id
                                    :slot-id (:slot-id binding)
                                    :model-role (:model-role binding)
                                    :model-id (:model-id binding)
                                    :binding binding}]
                       (ok state' receipt
                           [{:type :swarm/actor-registered
                             :tenure-id tenure-id
                             :agent-id agent-id
                             :binding binding}])))))))

(defn register-swarm-actor!
  [host request]
  (commit! host #(register-swarm-actor-state % request)))

(defn- board-actor-binding
  [state tenure-id agent-id]
  (get-in state [:bindings :execution tenure-id :swarm agent-id]))

(defn- update-swarm-binding
  [state tenure-id agent-id f & args]
  (if (board-actor-binding state tenure-id agent-id)
    (apply update-in state
           [:bindings :execution tenure-id :swarm agent-id]
           f args)
    state))

(defn- mark-board-retirement
  [state tenure-id result]
  (let [retired-agent-id (get-in result [:result :retired-agent-id])]
    (if (and (get-in result [:result :retired?]) retired-agent-id)
      (update-swarm-binding state tenure-id retired-agent-id
                            #(assoc % :status :dead
                                    :life-state :dead
                                    :role :worker
                                    :death-reason :holder-retired))
      state)))

(defn- board-result-effects
  [tenure-id result extra]
  (let [woken (seq (get-in result [:result :woken]))
        retired? (get-in result [:result :retired?])
        retired-agent-id (get-in result [:result :retired-agent-id])]
    (cond-> (vec extra)
      woken
      (conj {:type :swarm/wake-all
             :tenure-id tenure-id
             :reasons (vec (sort-by str woken))})

      retired?
      (conj {:type :swarm/holder-retired
             :tenure-id tenure-id
             :agent-id retired-agent-id}))))

(defn- trusted-command-env
  "Translate the opaque internal proof into the board's under-W flag.

  A boolean supplied by request data is ignored.  Without the capability the
  board sees no proof and rejects non-empty read-set/verified-read-set."
  [env]
  (let [env (or env {})]
    (if (write-gate-proof? (:write-gate-proof env))
      (cond-> {:under-write-gate? true}
        (:verify-read-set env)
        (assoc :verify-read-set (:verify-read-set env)))
      {})))

(defn claim-task-state
  "Claim one ready board task for a registered swarm actor."
  [state {:keys [tenure-id actor-id task-id atomicity-reason]}]
  (let [tenure (relay/tenure-by-id (:relay state) tenure-id)
        board* (get-in state [:boards tenure-id])
        binding (board-actor-binding state tenure-id actor-id)]
    (cond
                 (nil? tenure)
                 (tenure-failure state tenure-id)

                 (not= :active (:swarm-phase tenure))
                 (fail state :NOT_IN_SWARM
                       "claim requires Phase B"
                       {:tenure-id tenure-id
                        :swarm-phase (:swarm-phase tenure)})

                 (nil? board*)
                 (fail state :MISSION_BOARD_MISSING
                       "execution tenure has no committed board"
                       {:tenure-id tenure-id})

                 (nil? binding)
                 (fail state :UNKNOWN_ACTOR_BINDING
                       "actor is not a registered swarm role"
                       {:tenure-id tenure-id :agent-id actor-id})

                 :else
                 (let [result (commands/execute
                               board*
                               {:op :claim
                                :actor-id actor-id
                                :task-id task-id
                                :atomicity-reason atomicity-reason}
                               {})]
                   (if-not (:ok? result)
                     (fail state (board-error result)
                           "swarm board rejected claim"
                           (board-detail result))
                     (let [claim-id (get-in result [:result :claim-id])
                           state' (-> state
                                      (assoc-in [:boards tenure-id]
                                                (:board result))
                                      (update-in
                                       [:bindings :execution tenure-id :root-tasks]
                                       (fn [m]
                                         (if (contains? m task-id)
                                           (assoc-in m [task-id :claim-id] claim-id)
                                           m))))
                           receipt {:status :accepted
                                    :outcome :task-claimed
                                    :tenure-id tenure-id
                                    :agent-id actor-id
                                    :task-id task-id
                                    :claim-id claim-id}]
                       (ok state' receipt
                           [{:type :swarm/task-claimed
                             :tenure-id tenure-id
                             :agent-id actor-id
                             :task-id task-id
                             :claim-id claim-id}])))))))

(defn claim-task!
  [host request]
  (commit! host #(claim-task-state % request)))

(defn take-or-die-state
  "Compete for the sole token slot and update the role binding atomically.

  A win binds the actor as holder; a loss marks its binding dead.  A holder
  that becomes empty in the same board commit is already retired by the
  board kernel, and its binding is marked dead here."
  [state {:keys [tenure-id actor-id request-id confirm]}]
  (let [payload {:tenure-id tenure-id
                 :actor-id actor-id
                 :confirm confirm}
        cached (cached-request state request-id :take-or-die payload)]
    (if cached
      (replay-result state cached)
      (let [tenure (relay/tenure-by-id (:relay state) tenure-id)
            board* (get-in state [:boards tenure-id])
            binding (board-actor-binding state tenure-id actor-id)]
        (cond
                     (nil? tenure)
                     (tenure-failure state tenure-id)

                     (not= :active (:swarm-phase tenure))
                     (fail state :NOT_IN_SWARM
                           "take-or-die requires Phase B"
                           {:tenure-id tenure-id
                            :swarm-phase (:swarm-phase tenure)})

                     (nil? board*)
                     (fail state :MISSION_BOARD_MISSING
                           "execution tenure has no committed board"
                           {:tenure-id tenure-id})

                     (nil? binding)
                     (fail state :UNKNOWN_ACTOR_BINDING
                           "actor is not a registered swarm role"
                           {:tenure-id tenure-id :agent-id actor-id})

                     :else
                     (let [result (commands/execute
                                   board*
                                   {:op :take-or-die
                                    :actor-id actor-id
                                    :request-id request-id
                                    :confirm confirm}
                                   {})]
                       (if-not (:ok? result)
                         (fail state (board-error result)
                               "swarm board rejected take-or-die"
                               (board-detail result))
                         (let [outcome (get-in result [:result :outcome])
                               retired? (get-in result [:result :retired?])
                               retired-agent-id
                               (get-in result [:result :retired-agent-id])
                               state' (assoc-in state [:boards tenure-id]
                                                (:board result))
                               state' (cond
                                        (and retired?
                                             (= retired-agent-id actor-id))
                                        (update-swarm-binding
                                         state' tenure-id actor-id
                                         #(assoc % :status :dead
                                                 :life-state :dead
                                                 :role :worker
                                                 :death-reason :holder-retired))

                                        (= :attempt-won outcome)
                                        (update-swarm-binding
                                         state' tenure-id actor-id
                                         #(assoc % :role :holder
                                                 :status :active
                                                 :life-state :alive
                                                 :term-id (get-in result
                                                                  [:result :term-id])))

                                        (= :attempt-lost outcome)
                                        (update-swarm-binding
                                         state' tenure-id actor-id
                                         #(assoc % :status :dead
                                                 :life-state :dead
                                                 :role :worker
                                                 :death-reason :attempt-lost))

                                        :else state')
                               receipt {:status :accepted
                                        :outcome :take-or-die
                                        :tenure-id tenure-id
                                        :agent-id actor-id
                                        :result outcome
                                        :term-id (get-in result [:result :term-id])
                                        :retired? retired?
                                        :retired-agent-id retired-agent-id}
                               state' (remember-request state' request-id
                                                        :take-or-die payload
                                                        receipt)]
                           (ok state' receipt
                               [{:type :swarm/take-or-die
                                 :tenure-id tenure-id
                                 :agent-id actor-id
                                 :outcome outcome
                                 :retired? retired?
                                 :retired-agent-id retired-agent-id}])))))))))

(defn take-or-die!
  [host request]
  (commit! host #(take-or-die-state % request)))

(defn finish-root-task-state
  "Finish one board root task and release its relay ledger key.

  The board finish and the relay root-task completion are computed from the
  same candidate and committed together.  When this was the last root, the
  atomic relay barrier retires the tenure, writes the context barrier, opens
  the successor Reviewer, and swaps the role bindings in the same commit.

  Non-empty `:read-set` requires the opaque `:write-gate-proof` capability
  and a `:verify-read-set` function; non-empty `:verified-read-set` requires
  the same capability and is recorded as the result-ref basis. The board
  validates both forms before any relay change."
  [state request]
  (let [{:keys [tenure-id actor-id task-id claim-id result-summary read-set
                verified-read-set board-facts request-id]} request
        payload (select-keys request
                             [:tenure-id :actor-id :task-id :claim-id
                              :result-summary :read-set :verified-read-set])]
    (let [cached (cached-request state request-id
                                 :finish-root-task payload)]
      (if cached
        (replay-result state cached)
        (let [tenure (relay/tenure-by-id (:relay state) tenure-id)
                         board* (get-in state [:boards tenure-id])
                         task (get-in board* [:tasks task-id])
                         binding (board-actor-binding state tenure-id actor-id)]
                     (cond
                       (nil? tenure)
                       (tenure-failure state tenure-id)

                       (not= :active (:swarm-phase tenure))
                       (fail state :NOT_IN_SWARM
                             "finish-root-task requires Phase B"
                             {:tenure-id tenure-id
                              :swarm-phase (:swarm-phase tenure)})

                       (nil? board*)
                       (fail state :MISSION_BOARD_MISSING
                             "execution tenure has no committed board"
                             {:tenure-id tenure-id})

                       (nil? task)
                       (fail state :UNKNOWN_TASK
                             "board task does not exist"
                             {:tenure-id tenure-id :task-id task-id})

                       (nil? (:ledger-key task))
                       (fail state :NOT_A_ROOT_TASK
                             "board task is not a relay root task"
                             {:tenure-id tenure-id :task-id task-id})

                       (nil? binding)
                       (fail state :UNKNOWN_ACTOR_BINDING
                             "actor is not a registered swarm role"
                             {:tenure-id tenure-id :agent-id actor-id})

                       :else
                       (let [env (trusted-command-env
                                  (merge request (:env request)))
                             board-result (commands/execute
                                           board*
                                           {:op :finish
                                            :actor-id actor-id
                                            :task-id task-id
                                            :claim-id claim-id
                                            :result-summary result-summary
                                            :read-set (vec (or read-set []))
                                            :verified-read-set
                                            (vec (or verified-read-set []))}
                                           env)]
                         (if-not (:ok? board-result)
                           (fail state (board-error board-result)
                                 "swarm board rejected finish"
                                 (board-detail board-result))
                           (let [board' (:board board-result)
                                 snapshot (board-snapshot board' board-facts)
                                 relay-result
                                 (relay/finish-root-task
                                  (:relay state)
                                  {:lane :execution
                                   :tenure-id tenure-id
                                   :ledger-key (:ledger-key task)
                                   :board snapshot})]
                             (if-not (:ok? relay-result)
                               (relay-failure state relay-result)
                               (let [state-with-board
                                     (-> state
                                         (assoc-in [:boards tenure-id] board')
                                         (update-in
                                          [:bindings :execution tenure-id
                                           :root-tasks task-id]
                                          #(assoc % :state :done))
                                         (mark-board-retirement tenure-id
                                                                board-result))
                                     effects (board-result-effects
                                              tenure-id board-result
                                              (:effects relay-result))
                                     applied (apply-effects
                                              (assoc state-with-board
                                                     :relay (:state relay-result))
                                              effects)]
                                 (if-not (:ok? applied)
                                   (effect-failure state applied)
                                   (let [state' (finalize-relay-state
                                                 (:state applied))
                                         receipt (augment-receipt
                                                  (merge (:receipt relay-result)
                                                         {:board-task-id task-id
                                                          :board-version
                                                          (:board-version board')
                                                          :result-ref
                                                          (get-in board-result
                                                                  [:result
                                                                   :result-ref])})
                                                  (:effects applied))
                                         state' (remember-request
                                                 state' request-id
                                                 :finish-root-task payload
                                                 receipt)]
                                     (ok state' receipt
                                         (:effects applied)))))))))))))))

(defn finish-root-task!
  [host request]
  (commit! host #(finish-root-task-state % request)))

(defn- relay-root-task
  [tenure ledger-key]
  (some #(when (= ledger-key (:ledger-key %)) %)
        (:root-tasks tenure)))

(defn- mark-relay-root-ready
  [tenure ledger-key]
  (update tenure :root-tasks
          (fn [tasks]
            (mapv (fn [task]
                    (if (= ledger-key (:ledger-key task))
                      (assoc task :state :ready)
                      task))
                  tasks))))

(defn- remove-relay-root-task
  [tenure ledger-key]
  (-> tenure
      (update :root-tasks
              (fn [tasks]
                (filterv #(not= ledger-key (:ledger-key %)) tasks)))
      (update :ledger dissoc ledger-key)))

(defn- replace-relay-root-task
  [tenure ledger-key replacement-task]
  (if-not (relay-root-task tenure ledger-key)
    nil
    (update tenure :root-tasks
            (fn [tasks]
              (mapv (fn [task]
                      (if (= ledger-key (:ledger-key task))
                        (-> task
                            (assoc :goal (:goal replacement-task)
                                   :remaining-work (:remaining-work replacement-task)
                                   :done-when (:done-when replacement-task)
                                   :needs-token (boolean (:needs-token replacement-task))
                                   :state :ready))
                        task))
                    tasks)))))

(defn- assoc-execution-tenure
  [relay-state tenure]
  (assoc-in relay-state [:lanes :execution (:tenure-id tenure)] tenure))

(defn- command-with-context
  [command tenure-id board-facts request]
  (cond-> (assoc command :tenure-id tenure-id)
    board-facts (assoc :board-facts board-facts)
    (and (contains? request :request-id)
         (not (contains? command :request-id)))
    (assoc :request-id (:request-id request))))

(defn cancel-root-task-state
  "Cancel a leaf root task and release its relay ledger key atomically.

  The board cancel removes the task and its ledger entry.  The relay mirror
  removes the same root task and ledger key.  When that was the last open
  root, the same transition attempts the relay barrier with the resulting
  board snapshot.  A non-quiescent board leaves a committed `:relay-pending`
  tenure for `maybe-relay`."
  [state {:keys [tenure-id actor-id task-id reason board-facts request-id]}]
  (let [payload {:tenure-id tenure-id
                 :actor-id actor-id
                 :task-id task-id
                 :reason reason}
        cached (cached-request state request-id :cancel-root-task payload)]
    (if cached
      (replay-result state cached)
      (let [tenure (relay/tenure-by-id (:relay state) tenure-id)
            board* (get-in state [:boards tenure-id])
            task (get-in board* [:tasks task-id])
            binding (board-actor-binding state tenure-id actor-id)
            ledger-key (:ledger-key task)]
        (cond
          (nil? tenure)
          (tenure-failure state tenure-id)

          (not= :active (:swarm-phase tenure))
          (fail state :NOT_IN_SWARM
                "root cancel requires Phase B"
                {:tenure-id tenure-id
                 :swarm-phase (:swarm-phase tenure)})

          (nil? board*)
          (fail state :MISSION_BOARD_MISSING
                "execution tenure has no committed board"
                {:tenure-id tenure-id})

          (nil? task)
          (fail state :UNKNOWN_TASK
                "board task does not exist"
                {:tenure-id tenure-id :task-id task-id})

          (nil? ledger-key)
          (fail state :NOT_A_ROOT_TASK
                "board task is not a relay root task"
                {:tenure-id tenure-id :task-id task-id})

          (nil? (relay-root-task tenure ledger-key))
          (fail state :RELAY_ROOT_TASK_MISSING
                "relay mirror has no root task for the board ledger key"
                {:tenure-id tenure-id
                 :task-id task-id
                 :ledger-key ledger-key})

          (nil? binding)
          (fail state :UNKNOWN_ACTOR_BINDING
                "actor is not a registered swarm role"
                {:tenure-id tenure-id :agent-id actor-id})

          :else
          (let [result (commands/execute
                        board*
                        {:op :cancel
                         :actor-id actor-id
                         :task-id task-id
                         :reason reason}
                        {})]
            (if-not (:ok? result)
              (fail state (board-error result)
                    "swarm board rejected root cancel"
                    (board-detail result))
              (let [board' (:board result)
                    tenure' (remove-relay-root-task tenure ledger-key)
                    relay' (assoc-execution-tenure (:relay state) tenure')
                    snapshot (board-snapshot board' board-facts)
                    relay-result
                    (if (empty? (:ledger tenure'))
                      (relay/maybe-relay relay'
                                         {:lane :execution
                                          :tenure-id tenure-id
                                          :board snapshot})
                      {:ok? true
                       :state relay'
                       :receipt {:status :accepted
                                 :outcome :root-cancelled
                                 :ledger-key ledger-key}
                       :effects []})]
                (if-not (:ok? relay-result)
                  (relay-failure state relay-result)
                  (let [state-with-board
                        (-> state
                            (assoc-in [:boards tenure-id] board')
                            (update-in [:bindings :execution tenure-id
                                        :root-tasks] dissoc task-id)
                            (mark-board-retirement tenure-id result))
                        effects (board-result-effects
                                 tenure-id result
                                 (into [{:type :swarm/task-cancelled
                                         :tenure-id tenure-id
                                         :task-id task-id
                                         :ledger-key ledger-key}]
                                       (:effects relay-result)))
                        applied (apply-effects
                                 (assoc state-with-board
                                        :relay (:state relay-result))
                                 effects)]
                    (if-not (:ok? applied)
                      (effect-failure state applied)
                      (let [state' (finalize-relay-state (:state applied))
                            receipt (augment-receipt
                                     (merge (:receipt relay-result)
                                            {:board-task-id task-id
                                             :ledger-key ledger-key
                                             :board-version (:board-version board')})
                                     (:effects applied))
                            state' (remember-request state' request-id
                                                     :cancel-root-task payload
                                                     receipt)]
                        (ok state' receipt (:effects applied))))))))))))))

(defn cancel-root-task!
  [host request]
  (commit! host #(cancel-root-task-state % request)))

(defn replace-root-task-state
  "Replace a leaf root task while keeping its relay obligation.

  The board replacement preserves `:ledger-key`; the relay mirror keeps the
  same root obligation and adopts the replacement goal/remaining-work/
  done-when.  No ledger key is released and no barrier is attempted because
  the obligation is still open."
  [state {:keys [tenure-id actor-id task-id replacement reason board-facts
                 request-id]}]
  (let [payload {:tenure-id tenure-id
                 :actor-id actor-id
                 :task-id task-id
                 :replacement replacement
                 :reason reason}
        cached (cached-request state request-id :replace-root-task payload)]
    (if cached
      (replay-result state cached)
      (let [tenure (relay/tenure-by-id (:relay state) tenure-id)
            board* (get-in state [:boards tenure-id])
            task (get-in board* [:tasks task-id])
            binding (board-actor-binding state tenure-id actor-id)
            ledger-key (:ledger-key task)]
        (cond
          (nil? tenure)
          (tenure-failure state tenure-id)

          (not= :active (:swarm-phase tenure))
          (fail state :NOT_IN_SWARM
                "root replace requires Phase B"
                {:tenure-id tenure-id
                 :swarm-phase (:swarm-phase tenure)})

          (nil? board*)
          (fail state :MISSION_BOARD_MISSING
                "execution tenure has no committed board"
                {:tenure-id tenure-id})

          (nil? task)
          (fail state :UNKNOWN_TASK
                "board task does not exist"
                {:tenure-id tenure-id :task-id task-id})

          (nil? ledger-key)
          (fail state :NOT_A_ROOT_TASK
                "board task is not a relay root task"
                {:tenure-id tenure-id :task-id task-id})

          (nil? (relay-root-task tenure ledger-key))
          (fail state :RELAY_ROOT_TASK_MISSING
                "relay mirror has no root task for the board ledger key"
                {:tenure-id tenure-id
                 :task-id task-id
                 :ledger-key ledger-key})

          (nil? binding)
          (fail state :UNKNOWN_ACTOR_BINDING
                "actor is not a registered swarm role"
                {:tenure-id tenure-id :agent-id actor-id})

          :else
          (let [result (commands/execute
                        board*
                        {:op :replace
                         :actor-id actor-id
                         :task-id task-id
                         :replacement replacement
                         :reason reason}
                        {})]
            (if-not (:ok? result)
              (fail state (board-error result)
                    "swarm board rejected root replace"
                    (board-detail result))
              (let [board' (:board result)
                    new-task-id (get-in result [:result :new-task-id])
                    new-task (get-in board' [:tasks new-task-id])
                    tenure' (replace-relay-root-task tenure ledger-key new-task)]
                (if (nil? tenure')
                  (fail state :RELAY_ROOT_TASK_MISSING
                        "relay mirror has no root task for the board ledger key"
                        {:tenure-id tenure-id
                         :task-id task-id
                         :ledger-key ledger-key})
                  (let [relay' (assoc-execution-tenure (:relay state) tenure')
                        old-binding (get-in state
                                            [:bindings :execution tenure-id
                                             :root-tasks task-id])
                        state-with-board
                        (-> state
                            (assoc-in [:boards tenure-id] board')
                            (update-in [:bindings :execution tenure-id
                                        :root-tasks] dissoc task-id)
                            (assoc-in [:bindings :execution tenure-id
                                       :root-tasks new-task-id]
                                      {:ledger-key ledger-key
                                       :relay-task-id (:relay-task-id old-binding)
                                       :state :ready})
                            (mark-board-retirement tenure-id result))
                        effects (board-result-effects
                                 tenure-id result
                                 [{:type :swarm/task-replaced
                                   :tenure-id tenure-id
                                   :old-task-id task-id
                                   :new-task-id new-task-id
                                   :ledger-key ledger-key}])
                        applied (apply-effects
                                 (assoc state-with-board :relay relay')
                                 effects)]
                    (if-not (:ok? applied)
                      (effect-failure state applied)
                      (let [state' (finalize-relay-state (:state applied))
                            receipt (augment-receipt
                                     {:status :accepted
                                      :outcome :root-replaced
                                      :tenure-id tenure-id
                                      :old-task-id task-id
                                      :new-task-id new-task-id
                                      :ledger-key ledger-key
                                      :board-version (:board-version board')}
                                     (:effects applied))
                            state' (remember-request state' request-id
                                                     :replace-root-task payload
                                                     receipt)]
                        (ok state' receipt (:effects applied))))))))))))))

(defn replace-root-task!
  [host request]
  (commit! host #(replace-root-task-state % request)))

(defn reopen-root-task-state
  "Reopen one done root task and restore its relay obligation atomically.

  The board re-registers its `:ledger-key`; the relay mirror returns to
  `:ready`; the relay ledger regains the original obligation text from the
  mirror; and the host root binding returns to `:ready`.  A pending barrier
  is cancelled because the board now has open work again.  The task is
  finished through the normal root-finish path later."
  [state {:keys [tenure-id actor-id task-id reason request-id]}]
  (let [payload {:tenure-id tenure-id
                 :actor-id actor-id
                 :task-id task-id
                 :reason reason}
        cached (cached-request state request-id :reopen-root-task payload)]
    (if cached
      (replay-result state cached)
      (let [tenure (relay/tenure-by-id (:relay state) tenure-id)
            board* (get-in state [:boards tenure-id])
            task (get-in board* [:tasks task-id])
            binding (board-actor-binding state tenure-id actor-id)
            ledger-key (:ledger-key task)
            mirror (when (and tenure ledger-key)
                     (relay-root-task tenure ledger-key))
            root-binding (get-in state
                                 [:bindings :execution tenure-id
                                  :root-tasks task-id])
            obligation (when mirror
                         (or (:original-obligation mirror)
                             (:goal mirror)
                             (:remaining-work mirror)
                             (:done-when mirror)))]
        (cond
          (nil? tenure)
          (tenure-failure state tenure-id)

          (not= :active (:swarm-phase tenure))
          (fail state :NOT_IN_SWARM
                "root reopen requires Phase B"
                {:tenure-id tenure-id
                 :swarm-phase (:swarm-phase tenure)})

          (nil? board*)
          (fail state :MISSION_BOARD_MISSING
                "execution tenure has no committed board"
                {:tenure-id tenure-id})

          (nil? task)
          (fail state :UNKNOWN_TASK
                "board task does not exist"
                {:tenure-id tenure-id :task-id task-id})

          (nil? ledger-key)
          (fail state :NOT_A_ROOT_TASK
                "board task is not a relay root task"
                {:tenure-id tenure-id :task-id task-id})

          (nil? mirror)
          (fail state :RELAY_ROOT_TASK_MISSING
                "relay mirror has no root task for the board ledger key"
                {:tenure-id tenure-id
                 :task-id task-id
                 :ledger-key ledger-key})

          (nil? root-binding)
          (fail state :RELAY_ROOT_TASK_MISSING
                "host root binding is missing for the board task"
                {:tenure-id tenure-id
                 :task-id task-id
                 :ledger-key ledger-key})

          (nil? binding)
          (fail state :UNKNOWN_ACTOR_BINDING
                "actor is not a registered swarm role"
                {:tenure-id tenure-id :agent-id actor-id})

          (contains? (:ledger tenure) ledger-key)
          (fail state :RELAY_LEDGER_CONFLICT
                "relay ledger already has the root obligation open"
                {:tenure-id tenure-id :ledger-key ledger-key})

          (not (and (string? obligation)
                    (not (str/blank? obligation))))
          (fail state :RELAY_ROOT_TASK_MISSING
                "relay mirror has no obligation text to restore"
                {:tenure-id tenure-id
                 :task-id task-id
                 :ledger-key ledger-key})

          :else
          (let [result (commands/execute
                        board*
                        {:op :reopen
                         :actor-id actor-id
                         :task-id task-id
                         :reason reason}
                        {})]
            (if-not (:ok? result)
              (fail state (board-error result)
                    "swarm board rejected root reopen"
                    (board-detail result))
              (let [board' (:board result)
                    tenure' (-> tenure
                                (assoc-in [:ledger ledger-key] obligation)
                                (mark-relay-root-ready ledger-key)
                                (assoc :relay-pending? false))
                    relay' (assoc-execution-tenure (:relay state) tenure')
                    state-with-board
                    (-> state
                        (assoc-in [:boards tenure-id] board')
                        (assoc-in [:bindings :execution tenure-id
                                   :root-tasks task-id :state] :ready))
                    effects (board-result-effects
                             tenure-id result
                             [{:type :swarm/root-reopened
                               :tenure-id tenure-id
                               :task-id task-id
                               :ledger-key ledger-key}])
                    applied (apply-effects
                             (assoc state-with-board :relay relay')
                             effects)]
                (if-not (:ok? applied)
                  (effect-failure state applied)
                  (let [state' (finalize-relay-state (:state applied))
                        receipt (augment-receipt
                                 {:status :accepted
                                  :outcome :root-reopened
                                  :tenure-id tenure-id
                                  :board-task-id task-id
                                  :ledger-key ledger-key
                                  :board-version (:board-version board')}
                                 (:effects applied))
                        state' (remember-request state' request-id
                                                 :reopen-root-task payload
                                                 receipt)]
                    (ok state' receipt (:effects applied))))))))))))

(defn reopen-root-task!
  [host request]
  (commit! host #(reopen-root-task-state % request)))

(defn- append-relay-user-input
  "Append one raw operator text and attachment references to relay history.

  The history contains only raw text and attachment reference metadata.  It
  is what the context barrier may show a successor; attachment content,
  resolutions, and predecessor reasoning never enter it."
  [relay* text attachments]
  (update relay* :user-inputs
          (fn [inputs]
            (let [inputs (vec (or (seq inputs)
                                  (when (and (string? (:user-input relay*))
                                             (not (str/blank? (:user-input relay*))))
                                    [{:text (:user-input relay*)}])
                                  []))
                  attachments (relay/normalize-user-attachments
                               (vec (or attachments [])))]
              (conj inputs
                    (cond-> {:text text}
                      (seq attachments) (assoc :attachments attachments)))))))

(defn reopen-state
  "Reopen a completed execution mission after a real operator message.

  The completed tenure is left untouched as terminal evidence.  A fresh
  execution tenure (`:initial? false`, `:first-review? true`) is added at
  `:execution` with its own board and Reviewer binding.  The operator text
  and attachment references become relay `:user-input`/`:user-inputs` so the
  new Reviewer and later barrier successors see them.  Barriers, retired
  tenures, draft, session id, and the completed tenure remain in the state.
  The transition is recorded in `:reopens`.

  The Owner must call this only from its operator-command branch; the
  `:reason :operator-message` check is a protocol guard, not an identity
  proof."
  [state {:keys [reason user-input user-attachments request-id]}]
  (let [user-attachments (relay/normalize-user-attachments
                          (vec (or user-attachments [])))
        payload {:reason reason
                 :user-input user-input
                 :user-attachments user-attachments}
        cached (cached-request state request-id :reopen payload)]
    (cond
      (not= :operator-message reason)
      (fail state :REOPEN_NOT_OPERATOR
            "only a real operator message may reopen a completed mission"
            {:reason reason})

      (not (and (string? user-input) (not (str/blank? user-input))))
      (fail state :INVALID_OPERATOR_INPUT
            "reopen requires non-blank operator text"
            {:user-input user-input})

      (not (and (string? request-id) (not (str/blank? request-id))))
      (fail state :REQUEST_ID_REQUIRED
            "reopen requires a non-blank request-id"
            {:request-id request-id})

      cached
      (replay-result state cached)

      :else
      (let [relay* (:relay state)
            completed (->> (relay/execution-tenures relay*)
                           vals
                           (filter #(and (= :completed (:status %))
                                         (= :completed (:milestone %))))
                           (sort-by :epoch)
                           last)]
        (if-not completed
          (fail state :MISSION_NOT_COMPLETED
                "no execution tenure has completed"
                {:completed-tenure-ids
                 (->> (relay/execution-tenures relay*)
                      vals
                      (filter #(and (= :completed (:status %))
                                    (= :completed (:milestone %))))
                      (map :tenure-id)
                      vec)})
          (let [next-epoch (:next-tenure-seq relay*)
                new-tenure (-> (relay-tenure/create-tenure
                                {:tenure-id (str "tenure-" next-epoch)
                                 :epoch next-epoch
                                 :lane :execution
                                 :milestone :execution
                                 :initial? false})
                               (assoc :reopened-from (:tenure-id completed)
                                      :reopen-reason :operator-message
                                      ;; The operator already supplied the new
                                      ;; input.  An empty first review must
                                      ;; relay to an independent successor,
                                      ;; not promote or ask again.
                                      :first-review? false))
                relay' (-> relay*
                           (assoc-in [:lanes :execution
                                      (:tenure-id new-tenure)] new-tenure)
                           (assoc :next-tenure-seq (inc next-epoch))
                           (append-relay-user-input user-input user-attachments)
                           (assoc :user-input user-input))
                {:keys [board control-agent-id]} (new-execution-board)
                binding (assoc (reviewer-binding-for state new-tenure)
                               :reopened-from (:tenure-id completed))
                reopen-record {:from-tenure-id (:tenure-id completed)
                               :to-tenure-id (:tenure-id new-tenure)
                               :reason :operator-message
                               :user-input user-input}
                state' (-> state
                           (assoc :relay relay')
                           (assoc-in [:boards (:tenure-id new-tenure)] board)
                           (assoc-in [:bindings :execution
                                      (:tenure-id new-tenure)]
                                     {:reviewer binding
                                      :control-agent-id control-agent-id
                                      :swarm {}
                                      :root-tasks {}})
                           (update :reopens (fnil conj []) reopen-record))
                receipt {:status :accepted
                         :outcome :reopened
                         :tenure-id (:tenure-id new-tenure)
                         :role :reviewer
                         :from-tenure-id (:tenure-id completed)
                         :operator-input user-input
                         :reviewer-binding binding
                         :board-version (:board-version board)}
                effect {:type :execution/start
                        :lane :execution
                        :tenure new-tenure
                        :draft-path (get-in state [:relay :draft :path])
                        :draft (get-in state [:relay :draft :content])
                        :draft-bytes (get-in state [:relay :draft :content])
                        :draft-length (count (str (get-in state
                                                          [:relay :draft
                                                           :content])))
                        :milestone :execution
                        :reviewer-binding binding
                        :board-version (:board-version board)
                        :reopen? true
                        :reopened-from (:tenure-id completed)
                        :operator-input user-input}
                state' (remember-request state' request-id :reopen payload
                                         receipt)]
            (ok state' receipt [effect])))))))

(defn reopen!
  [host request]
  (commit! host #(reopen-state % request)))

(defn- apply-board-command*
  [state tenure-id command env]
  (let [tenure (relay/tenure-by-id (:relay state) tenure-id)
        board* (get-in state [:boards tenure-id])]
    (cond
      (nil? tenure)
      (tenure-failure state tenure-id)

      (not= :active (:swarm-phase tenure))
      (fail state :NOT_IN_SWARM
            "board command requires Phase B"
            {:tenure-id tenure-id
             :swarm-phase (:swarm-phase tenure)})

      (nil? board*)
      (fail state :MISSION_BOARD_MISSING
            "execution tenure has no committed board"
            {:tenure-id tenure-id})

      :else
      (let [result (commands/execute board* command
                                     (trusted-command-env env))]
        (if-not (:ok? result)
          (fail state (board-error result)
                "swarm board rejected command"
                (board-detail result))
          (let [board' (:board result)
                state' (-> state
                           (assoc-in [:boards tenure-id] board')
                           (mark-board-retirement tenure-id result))
                woken (get-in result [:result :woken])
                retired? (get-in result [:result :retired?])
                retired-agent-id (get-in result [:result :retired-agent-id])
                effects (cond-> []
                          (seq woken)
                          (conj {:type :swarm/wake-all
                                 :tenure-id tenure-id
                                 :reasons (vec (sort-by str woken))})
                          retired?
                          (conj {:type :swarm/holder-retired
                                 :tenure-id tenure-id
                                 :agent-id retired-agent-id}))
                receipt (assoc (:result result)
                               :status :accepted
                               :tenure-id tenure-id)]
            (ok state' receipt effects)))))))

(defn board-command-state
  "Apply one swarm board command to a specific execution tenure.

  `:register-actor`, `:claim`, and `:take-or-die` delegate to their typed
  transitions so the role binding stays consistent.  A `:finish` on a root
  task delegates to `finish-root-task-state`; a child `:finish` stays a
  pure board command.  Root `:cancel`/`:replace`/`:reopen` delegate to their
  relay-aware transitions; `:create-task` is rejected because root creation
  belongs to the Reviewer ledger.

  ENV may contain only the internal `:write-gate-proof` token and
  `:verify-read-set`."
  [state {:keys [tenure-id command env board-facts] :as request}]
  (let [tenure-id (or tenure-id
                      (some-> (relay/current-tenure (:relay state) :execution)
                              :tenure-id))
        op (when (map? command) (keyword (:op command)))
        env (select-keys (or env {}) [:write-gate-proof :verify-read-set])]
    (cond
      (nil? tenure-id)
      (fail state :UNKNOWN_TENURE
            "board command needs an execution tenure-id"
            {:tenure-id tenure-id})

      (not (map? command))
      (fail state :INVALID_BOARD_COMMAND
            "board command must be a map"
            {:command command})

      :else
      (case op
        :register-actor
        (register-swarm-actor-state state (assoc command :tenure-id tenure-id))

        :claim
        (claim-task-state state (assoc command :tenure-id tenure-id))

        :take-or-die
        (take-or-die-state state (assoc command :tenure-id tenure-id))

        :finish
        (let [board* (get-in state [:boards tenure-id])
              task (get-in board* [:tasks (:task-id command)])]
          (if (:ledger-key task)
            (finish-root-task-state
             state
             (cond-> (command-with-context command tenure-id
                                           board-facts request)
               (seq env) (assoc :env env)))
            (apply-board-command* state tenure-id command env)))

        :cancel
        (let [board* (get-in state [:boards tenure-id])
              task (get-in board* [:tasks (:task-id command)])]
          (if (:ledger-key task)
            (cancel-root-task-state
             state
             (command-with-context command tenure-id board-facts request))
            (apply-board-command* state tenure-id command env)))

        :replace
        (let [board* (get-in state [:boards tenure-id])
              task (get-in board* [:tasks (:task-id command)])]
          (if (:ledger-key task)
            (replace-root-task-state
             state
             (command-with-context command tenure-id board-facts request))
            (apply-board-command* state tenure-id command env)))

        :reopen
        (let [board* (get-in state [:boards tenure-id])
              task (get-in board* [:tasks (:task-id command)])]
          (if (:ledger-key task)
            (reopen-root-task-state
             state
             (command-with-context command tenure-id board-facts request))
            (apply-board-command* state tenure-id command env)))

        :create-task
        (fail state :HOST_OWNS_ROOT_CREATION
              "root tasks are created from a Reviewer ledger, not by board command"
              {:tenure-id tenure-id})

        :batch
        (fail state :BATCH_BOARD_COMMAND_UNSUPPORTED
              "apply board commands one at a time"
              {:tenure-id tenure-id})

        (apply-board-command* state tenure-id command env)))))

;; ---------------------------------------------------------------------------
;; Plan dedicated worker

(defn devops-request-state
  "Reuse/steer the one Plan dedicated worker for the current tenure.

  The request is committed before the worker effect is dispatched.  A
  replayed call-id returns the original receipt and no effect."
  [state {:keys [tenure-id call-id verbose-intent]}]
  (let [payload {:tenure-id tenure-id
                 :verbose-intent verbose-intent}
        cached (cached-devops-request state call-id payload)]
    (if cached
      (replay-result state cached)
      (let [plan (relay/current-tenure (:relay state) :plan)
                       worker-state (get-in state [:bindings :plan-worker])
                       task-model (get-in state [:models :task])]
                   (cond
                     (nil? plan)
                     (fail state :UNKNOWN_TENURE
                           "Plan tenure is missing" {:lane :plan})

                     (not= tenure-id (:tenure-id plan))
                     (if (contains? (:retired-tenure-ids (:relay state))
                                    tenure-id)
                       (fail state :TENURE_RETIRED
                             "Plan tenure has retired"
                             {:tenure-id tenure-id})
                       (fail state :UNKNOWN_TENURE
                             "devops belongs to the current Plan tenure"
                             {:tenure-id tenure-id
                              :current-plan-tenure-id (:tenure-id plan)}))

                     (:awaiting-user? plan)
                     (fail state :AWAITING_USER
                           "Plan is suspended awaiting a human answer"
                           {:tenure-id tenure-id})

                     :else
                     (let [ensured (worker/ensure
                                    worker-state
                                    {:tenure-id tenure-id
                                     :model-id task-model
                                     :call-id call-id
                                     :verbose-intent verbose-intent})]
                       (if-not (:ok? ensured)
                         (fail state (get-in ensured [:error :code])
                               (get-in ensured [:error :message])
                               (get-in ensured [:error :details]))
                         (let [state' (assoc-in state
                                                [:bindings :plan-worker]
                                                (:state ensured))
                               receipt {:status :accepted
                                        :outcome :devops-requested
                                        :tenure-id tenure-id
                                        :call-id call-id
                                        :worker-id (:worker-id ensured)
                                        :reused? (:reused? ensured)
                                        :model-role :task
                                        :model-id task-model
                                        :capabilities worker/capabilities
                                        :contract worker/contract}
                               state' (remember-devops-request state' call-id
                                                               payload
                                                               receipt)]
                           (ok state' receipt [(:effect ensured)])))))))))

(defn devops-request!
  [host request]
  (commit! host #(devops-request-state % request)))

(defn devops-complete-state
  "Fix a Plan dedicated worker report and return it verbatim."
  [state {:keys [tenure-id worker-id call-id status report]}]
  (let [plan (relay/current-tenure (:relay state) :plan)
                   worker-state (get-in state [:bindings :plan-worker])
                   current (:worker worker-state)]
               (cond
                 (nil? plan)
                 (fail state :UNKNOWN_TENURE
                       "Plan tenure is missing" {:lane :plan})

                 (not= tenure-id (:tenure-id plan))
                 (if (contains? (:retired-tenure-ids (:relay state))
                                tenure-id)
                   (fail state :TENURE_RETIRED
                         "Plan tenure has retired"
                         {:tenure-id tenure-id
                          :current-plan-tenure-id (:tenure-id plan)})
                   (fail state :UNKNOWN_TENURE
                         "worker does not belong to the current Plan tenure"
                         {:tenure-id tenure-id
                          :current-plan-tenure-id (:tenure-id plan)}))

                 (or (nil? current)
                     (not= worker-id (:worker-id current)))
                 (fail state :UNKNOWN_WORKER
                       "worker does not belong to the current Plan tenure"
                       {:tenure-id tenure-id :worker-id worker-id})

                 :else
                 (let [completed (worker/complete current
                                                  {:call-id call-id
                                                   :status status
                                                   :report report})]
                   (if-not (:ok? completed)
                     (fail state (get-in completed [:error :code])
                           (get-in completed [:error :message])
                           (get-in completed [:error :details]))
                     (let [result (:result completed)
                           state' (assoc-in state [:bindings :plan-worker]
                                            (assoc worker-state
                                                   :worker (:worker completed)))
                           receipt (assoc result
                                          :status :accepted
                                          :outcome :devops-completed
                                          :worker-result-status (:status result)
                                          :call-id call-id
                                          :worker-id worker-id
                                          :worker-status (:status result)
                                          :worker-lifecycle-status
                                          (get-in completed [:worker :status]))]
                       (ok state' receipt [])))))))

(defn devops-complete!
  [host request]
  (commit! host #(devops-complete-state % request)))

(defn devops!
  "Synchronous host-side convenience around request/run/complete.

  RUN receives the committed spawn/steer effect and must return
  `{:status :ok|:failed|:interrupted :report \"...\"}`.  The function runs
  between two host commits, so it must not be called inside an Owner
  transaction.  The worker report is returned without summarization."
  [host {:keys [run] :as request}]
  (when-not (fn? run)
    (throw (ex-info "devops! requires a :run function"
                    {:code :RUN_FUNCTION_REQUIRED})))
  (let [requested (devops-request! host request)]
    (if-not (:ok? requested)
      requested
      (if-let [effect (first (:effects requested))]
        (let [outcome (try
                        (run (select-keys effect
                                          [:type :worker-id :tenure-id :call-id
                                           :verbose-intent :model-role :model-id
                                           :capabilities :contract]))
                        (catch Throwable t
                          {:status :failed
                           :report (or (.getMessage t) (str t))}))]
          (devops-complete! host
                            (merge (select-keys request [:tenure-id :call-id])
                                   {:worker-id (:worker-id effect)}
                                   outcome)))
        requested))))

;; ---------------------------------------------------------------------------
;; Nudge facts

(defn nudge-facts
  "Return the facts `hc.compose.nudge.policy/observation` needs for a role.

  The relay state supplies tenures and execution-tenure ambiguity; the host
  supplies the board that belongs to the selected execution tenure."
  [state {:keys [role tenure-id agent-id] :as facts}]
  (let [role (keyword role)
        relay* (:relay state)
        executions (relay/execution-tenures relay*)
        tenure (case role
                 :plan (relay/current-tenure relay* :plan)
                 (:reviewer :worker :holder)
                 (if tenure-id
                   (relay/tenure-by-id relay* tenure-id)
                   (when (= 1 (count executions))
                     (val (first executions))))
                 nil)
        execution-tenure (when (contains? #{:reviewer :worker :holder} role)
                           tenure)
        board* (when-let [tid (:tenure-id tenure)]
                 (get-in state [:boards tid]))]
    (merge facts
           {:role role
            :tenure tenure
            :execution-tenure execution-tenure
            :execution-tenures executions
            :board board*
            :agent-id agent-id
            :session-id (:session-id relay*)})))

;; ---------------------------------------------------------------------------
;; Invariants

(defn- violation
  [code message details]
  (cond-> {:code code :message message}
    (some? details) (assoc :details details)))

(defn- collect-violations
  [value]
  (cond
    (nil? value) []
    (map? value) [value]
    (sequential? value) (mapcat collect-violations value)
    :else []))

(defn check-invariants
  "Check relay, board, binding, and model-ownership invariants.

  Returns `{:ok? true :violations []}` or a concrete violation vector."
  [state]
  (let [relay* (:relay state)
        relay-check (relay/check-invariants relay*)
        models* (:models state)
        plan (relay/current-tenure relay* :plan)
        plan-binding (get-in state [:bindings :plan])
        plan-worker-state (get-in state [:bindings :plan-worker])
        plan-worker (:worker plan-worker-state)
        executions (relay/execution-tenures relay*)
        boards (:boards state)
        execution-bindings (get-in state [:bindings :execution])
        live-ids (set (keys executions))
        board-ids (set (keys boards))
        binding-ids (set (keys execution-bindings))
        retired (or (:retired-tenure-ids relay*) #{})
        execution-violations
        (mapcat
         (fn [[tenure-id tenure]]
           (let [binding (get-in execution-bindings [tenure-id])
                 reviewer (:reviewer binding)
                 board* (get boards tenure-id)
                 control-id (:control-agent-id binding)
                 swarm-bindings (:swarm binding)
                 root-bindings (:root-tasks binding)
                 board-tasks (:tasks board*)
                 board-roots (filter #(nil? (:parent-id %))
                                     (vals board-tasks))
                 board-actor-ids (set (keys (:actors board*)))
                 board-bound-ids (set (keys swarm-bindings))
                 holder-id (get-in board* [:token-slot :holder-id])
                 relay-root-keys (set (map :ledger-key (:root-tasks tenure)))
                 board-root-keys (set (keep :ledger-key board-roots))]
             (concat
              (when-not (and (map? binding) (map? reviewer))
                [(violation :EXECUTION_BINDING_MISSING
                            "live execution tenure has no Reviewer binding"
                            {:tenure-id tenure-id})])

              (model/binding-errors models* reviewer)

              (when (and reviewer
                         (not= tenure-id (:tenure-id reviewer)))
                [(violation :REVIEWER_TENURE_MISMATCH
                            "Reviewer binding names another tenure"
                            {:tenure-id tenure-id
                             :binding-tenure-id (:tenure-id reviewer)})])

              (when (and reviewer
                         (not= (:milestone tenure) (:milestone reviewer)))
                [(violation :REVIEWER_MILESTONE_STALE
                            "Reviewer binding milestone is stale"
                            {:tenure-id tenure-id
                             :tenure-milestone (:milestone tenure)
                             :binding-milestone (:milestone reviewer)})])

              (when (and reviewer
                         (not= (:status tenure) (:status reviewer)))
                [(violation :REVIEWER_STATUS_STALE
                            "Reviewer binding status is stale"
                            {:tenure-id tenure-id
                             :tenure-status (:status tenure)
                             :binding-status (:status reviewer)})])

              (when-not board*
                [(violation :EXECUTION_BOARD_MISSING
                            "live execution tenure has no board"
                            {:tenure-id tenure-id})])

              (when (and board* control-id
                         (not= :alive (get-in board* [:actors control-id
                                                      :life-state])))
                [(violation :CONTROL_ACTOR_NOT_ALIVE
                            "board control actor is not alive"
                            {:tenure-id tenure-id
                             :control-agent-id control-id})])

              (when (and board* (not= (set (keys board-tasks))
                                      (set (map :task-id (vals board-tasks)))))
                [(violation :BOARD_TASK_KEYS
                            "board task keys must equal task ids"
                            {:tenure-id tenure-id})])

              (when (and board* (seq (board/check-invariants board*)))
                [(violation :BOARD_INVARIANT
                            "swarm board violates its own invariants"
                            {:tenure-id tenure-id
                             :violations (board/check-invariants board*)})])

              (when (and board* (= :active (:swarm-phase tenure)))
                (when-not (= relay-root-keys board-root-keys)
                  [(violation :ROOT_TASK_MIRROR_MISMATCH
                              "relay root tasks and board roots disagree"
                              {:tenure-id tenure-id
                               :relay-root-keys (sort relay-root-keys)
                               :board-root-keys (sort board-root-keys)})]))

              (when (and board* root-bindings
                         (not= (set (keys root-bindings))
                               (set (filter #(contains? root-bindings %)
                                            (keys board-tasks)))))
                [(violation :ROOT_BINDING_MISMATCH
                            "root-task bindings must name board tasks"
                            {:tenure-id tenure-id
                             :binding-ids (sort (keys root-bindings))})])

              (for [agent-id board-bound-ids
                    :when (not (contains? board-actor-ids agent-id))]
                (violation :SWARM_BINDING_WITHOUT_ACTOR
                           "swarm binding has no board actor"
                           {:tenure-id tenure-id :agent-id agent-id}))

              (for [agent-id board-actor-ids
                    :when (and (not= agent-id control-id)
                               (not (contains? board-bound-ids agent-id)))]
                (violation :BOARD_ACTOR_WITHOUT_BINDING
                           "board actor has no model binding"
                           {:tenure-id tenure-id :agent-id agent-id}))

              (mapcat (fn [agent-id]
                        (let [binding (get swarm-bindings agent-id)
                              actor (get-in board* [:actors agent-id])]
                          (when (and actor binding)
                            (concat
                 (model/binding-errors models* binding)
                 (when (not= (if (= :holder (:role binding))
                               :holder
                               :normal)
                             (:role actor))
                   [(violation :SWARM_ROLE_STALE
                               "swarm binding role differs from the board actor"
                               {:tenure-id tenure-id
                                :agent-id agent-id
                                :binding-role (:role binding)
                                :expected-board-role
                                (if (= :holder (:role binding))
                                  :holder
                                  :normal)
                                :actor-role (:role actor)})])
                 (when (and (= :alive (:life-state actor))
                            (not= :alive (:life-state binding)))
                   [(violation :SWARM_LIFE_STALE
                               "live board actor has a dead binding"
                               {:tenure-id tenure-id :agent-id agent-id})])))))
                      board-bound-ids)

              (when holder-id
                (let [holder-binding (get swarm-bindings holder-id)]
                  (when-not (and holder-binding
                                 (= :holder (:role holder-binding))
                                 (= :alive (:life-state holder-binding)))
                    [(violation :HOLDER_BINDING_MISSING
                                "token holder has no live holder binding"
                                {:tenure-id tenure-id
                                 :holder-id holder-id})])))

              (when (and (= :completed (:status tenure))
                         (not (every? #(= :done (:state %)) board-roots)))
                [(violation :COMPLETED_WITH_OPEN_ROOTS
                            "completed tenure still has open board roots"
                            {:tenure-id tenure-id})]))))
         executions)
        raw-violations
        (concat
         (when-not (:ok? relay-check)
           (mapv #(violation :RELAY_INVARIANT
                             "relay invariant violated"
                             %)
                 (:violations relay-check)))

         (model/check-models models*)

         (when-not (and plan plan-binding)
           [(violation :PLAN_BINDING_MISSING
                       "Plan tenure or its binding is missing"
                       {:has-plan? (some? plan)
                        :has-binding? (some? plan-binding)})])

         (model/binding-errors models* plan-binding)

         (when (and plan plan-binding
                    (not= (:tenure-id plan) (:tenure-id plan-binding)))
           [(violation :PLAN_TENURE_MISMATCH
                       "Plan binding names another tenure"
                       {:tenure-id (:tenure-id plan)
                        :binding-tenure-id (:tenure-id plan-binding)})])

         (when (and plan plan-binding
                    (not= (:milestone plan) (:milestone plan-binding)))
           [(violation :PLAN_MILESTONE_STALE
                       "Plan binding milestone is stale"
                       {:tenure-milestone (:milestone plan)
                        :binding-milestone (:milestone plan-binding)})])

         (when (and plan-binding
                    (not (boolean? (:reviewed-in-tenure? plan-binding))))
           [(violation :PLAN_REVIEW_FLAG_INVALID
                       "Plan binding must record a boolean review flag"
                       {:reviewed-in-tenure?
                        (:reviewed-in-tenure? plan-binding)})])

         (when-not (and (map? plan-worker-state)
                        (integer? (:next-seq plan-worker-state))
                        (pos? (:next-seq plan-worker-state)))
           [(violation :PLAN_WORKER_STATE_INVALID
                       "Plan worker state needs a positive next-seq"
                       {:plan-worker-state plan-worker-state})])

         (when plan-worker
           (concat
            (worker/check-worker plan-worker)
            (when-not (= (:tenure-id plan) (:tenure-id plan-worker))
              [(violation :PLAN_WORKER_TENURE_MISMATCH
                          "Plan worker belongs to another tenure"
                          {:plan-tenure-id (:tenure-id plan)
                           :worker-tenure-id (:tenure-id plan-worker)})])
            (when-not (= (:task models*) (:model-id plan-worker))
              [(violation :PLAN_WORKER_MODEL_MISMATCH
                          "Plan worker does not use the configured task model"
                          {:expected (:task models*)
                           :actual (:model-id plan-worker)})])))

         (when-not (= board-ids live-ids)
           [(violation :BOARD_TENURE_MISMATCH
                       "boards must exist exactly for live execution tenures"
                       {:boards (sort board-ids)
                        :tenures (sort live-ids)})])

         (when-not (= binding-ids live-ids)
           [(violation :BINDING_TENURE_MISMATCH
                       "execution bindings must exist exactly for live tenures"
                       {:bindings (sort binding-ids)
                        :tenures (sort live-ids)})])

         (when (seq (filter retired (concat live-ids board-ids binding-ids)))
           [(violation :RETIRED_IDENTITY_LIVE
                       "retired tenure still has a board or binding"
                       {:retired-ids (sort (filter retired
                                                   (concat live-ids board-ids
                                                           binding-ids)))})])

         (for [tenure-id (keys (:retired state))
               :when (contains? live-ids tenure-id)]
           (violation :RETIRED_MAP_LIVE
                      "retired archive names a live tenure"
                      {:tenure-id tenure-id}))

         (for [[request-id stored] (:requests state)
               :when (not (and (string? request-id)
                               (map? stored)
                               (keyword? (:op stored))
                               (contains? stored :payload)
                               (map? (:receipt stored))))]
           (violation :REQUEST_CACHE_INVALID
                      "request cache entry is malformed"
                      {:request-id request-id
                       :entry stored}))

         (for [barrier (:barriers relay*)
               :let [successor-id (:to-tenure-id barrier)
                     successor (relay/tenure-by-id relay* successor-id)]
               :when successor
               :let [lane (:lane successor)
                     binding (if (= :plan lane)
                               plan-binding
                               (get-in execution-bindings
                                       [successor-id :reviewer]))]
               :when (nil? binding)]
           (violation :BARRIER_SUCCESSOR_UNBOUND
                      "barrier successor has no role binding"
                      {:barrier-id (:barrier-id barrier)
                       :successor-id successor-id
                       :lane lane}))

         execution-violations)
        violations (vec (mapcat collect-violations raw-violations))]
    {:ok? (empty? violations)
     :violations violations}))

;; ---------------------------------------------------------------------------
;; Pure state-in/state-out seam for an external Owner

(defn restore
  "Validate and normalize a committed mission state.

  Returns `{:ok? true :state state}` or `{:ok? false :state state :error ...}`.
  This function creates no atom.  It accepts either a mission state or an
  Owner state carrying the mission state under `:mission`.  Missing optional
  maps are filled with empty values; structural or ownership violations are
  reported instead of guessed away."
  ([state] (restore state {}))
  ([state {:keys [owner-instance-id]}]
   (let [owner-state (when (and (map? state) (map? (:mission state)))
                       state)
         mission (if owner-state (:mission state) state)
         ;; An Owner state is the session authority.  A legacy/seeded mission
         ;; document may omit :session-id; inherit the real id rather than
         ;; constructing an anonymous relay.  A genuinely missing id stays
         ;; structurally invalid (relay/new-state refuses it).
         mission (if (and (map? mission)
                          (not (and (string? (:session-id mission))
                                    (not (str/blank? (:session-id mission)))))
                          (and (string? (:session-id owner-state))
                               (not (str/blank? (:session-id owner-state)))))
                   (assoc mission :session-id (:session-id owner-state))
                   mission)
         state mission]
    (if-not (map? state)
      (fail state :INVALID_MISSION_STATE
            "mission state must be a map"
            {:state state})
      (let [state (-> state
                      ;; A seeded or restored owner-state document may omit
                      ;; these sections entirely; an empty map is the
                      ;; documented "no live relay/models" value, not an
                      ;; invalid mission.
                      (update :relay
                              #(or % (relay/new-state
                                      {:session-id (:session-id state)})))
                      (update :models #(or % {}))
                      (update :boards #(or % {}))
                      (update :retired #(or % {}))
                      (update :requests #(or % {}))
                      (update :devops-requests #(or % {}))
                      (update :reopens #(or % []))
                      (update :extensions #(or % {}))
                      ;; A non-map :bindings is a structural violation, not a
                      ;; missing section: keep it for the validation below
                      ;; instead of descending into it and throwing.
                      (update :bindings
                              #(if (or (nil? %) (map? %)) (or % {}) %))
                      ((fn [s]
                         (cond-> s
                           (map? (:bindings s))
                           (update-in [:bindings :execution] #(or % {}))

                           (map? (:bindings s))
                           (update-in [:bindings :plan-worker]
                                      #(or % {:worker nil :next-seq 1}))))))
            current-instance (or owner-instance-id
                                 (:owner-instance-id state))
            claim (:draft-claim state)
            instance-id? #(and (string? %)
                               (not (str/blank? %)))
            proven-dead? (and (instance-id? (:owner-instance-id claim))
                              (instance-id? current-instance)
                              (not= (:owner-instance-id claim)
                                    current-instance))
            state (cond-> state
                    (instance-id? current-instance)
                    (assoc :owner-instance-id current-instance)

                    proven-dead?
                    (dissoc :draft-claim))]
        (if-not (and (map? (:relay state))
                     (map? (:models state))
                     (map? (:bindings state))
                     (map? (get-in state [:bindings :execution]))
                     (map? (get-in state [:bindings :plan-worker]))
                     (map? (:boards state))
                     (every? map? (vals (:boards state)))
                     (map? (:retired state))
                     (map? (:requests state))
                     (vector? (:reopens state))
                     (every? map? (:reopens state))
                     (map? (:extensions state)))
          (fail state :INVALID_MISSION_STATE
                "mission state has missing or malformed required sections"
                {:keys (set (keys state))
                 :boards (when (map? (:boards state))
                           (set (keys (:boards state))))
                 :bindings (when (map? (:bindings state))
                             (set (keys (:bindings state))))})
          (let [checked (check-invariants state)]
            (if (:ok? checked)
              {:ok? true :state state}
              {:ok? false
               :state state
               :error {:code :INVALID_MISSION_STATE
                       :message "mission state violates invariants"
                       :details {:violations (:violations checked)}}}))))))))

(defn restore-host
  "Build the convenience atom host from a committed mission state.

  Prefer `restore` plus `apply-command` when an external Owner already owns
  the commit boundary."
  ([state] (restore-host state {}))
  ([state {:keys [dispatch!] :as opts}]
   (let [result (restore state opts)]
     (if (:ok? result)
       (->MissionHost (atom (:state result)) dispatch!)
       (throw (ex-info "cannot restore mission host"
                       (merge {:code :INVALID_MISSION_STATE}
                              (:error result)
                              {:state (:state result)})))))))

(defn extensions-put-state
  "Store one opaque extension value under a keyword key.

  Pair Hint uses `[:extensions :pair-hint]`; mission does not interpret the
  value.  The Owner commits it with `:mission` in the same CAS."
  [state {:keys [key value]}]
  (if-not (keyword? key)
    (fail state :INVALID_EXTENSION_KEY
          "extension key must be a keyword"
          {:key key})
    (ok (assoc-in state [:extensions key] value)
        {:status :accepted :outcome :extension-stored :key key}
        [])))

(defn apply-command
  "Apply one pure mission command to a committed state.

  This is the Owner-facing seam.  It never touches an atom and never
  dispatches.  The caller commits `:state` and its own Owner keys in one
  store CAS, then dispatches the returned plain-data `:effects`.

  Supported `:op` values: `:review`, `:mark-awaiting`, `:resume`, `:set-draft`,
  `:check-draft`, `:claim-draft`, `:release-draft`,
  `:maybe-relay`, `:register-swarm-actor`, `:claim-task`, `:take-or-die`,
  `:finish-root-task`, `:cancel-root-task`, `:replace-root-task`,
  `:reopen-root-task`,
  `:reopen`, `:extensions-put`, `:board-command`, `:devops-request`, and
  `:devops-complete`."
  [state {:keys [op] :as command}]
  (case (keyword op)
    :review (review-state state command)
    :mark-awaiting (mark-awaiting-state state command)
    :resume (resume-state state command)
    :set-draft (set-draft-state state command)
    :check-draft (check-draft-state state command)
    :claim-draft (claim-draft-state state command)
    :release-draft (release-draft-state state command)
    :maybe-relay (maybe-relay-state state command)
    :register-swarm-actor (register-swarm-actor-state state command)
    :claim-task (claim-task-state state command)
    :take-or-die (take-or-die-state state command)
    :finish-root-task (finish-root-task-state state command)
    :cancel-root-task (cancel-root-task-state state command)
    :replace-root-task (replace-root-task-state state command)
    :reopen-root-task (reopen-root-task-state state command)
    :reopen (reopen-state state command)
    :extensions-put (extensions-put-state state command)
    :board-command (board-command-state state command)
    :devops-request (devops-request-state state command)
    :devops-complete (devops-complete-state state command)
    (fail state :UNKNOWN_MISSION_COMMAND
          "unknown mission command"
          {:op op})))

(defn apply-command!
  "Atom-host wrapper around `apply-command`; useful for tests and tools."
  [host command]
  (commit! host #(apply-command % command)))
