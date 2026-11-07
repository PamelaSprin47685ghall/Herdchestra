(ns hc.compose.cancel
  "Cancellation tree and independent branch cancellation (AGENTS.md §1.1-6,
  §3.2, §5.3).

  The public transitions are pure.  A host commits the returned state and
  may then execute the advisory effects.  Effects only name an attempt,
  timer, write-gate wait, managed process, or run admission.  They always
  say `:thread-interrupt? false` and `:dispose? false`: the database commit
  thread and FileChannel writer are never interrupted, and unclaimed
  evidence is never destroyed.

  Operator cancellation is accepted only from a real operator origin and
  only with `:user-cancel`.  `park`, `ask`, relay retirement, and slot
  reincarnation are programmatic suspends and are refused.  Cancellation
  walks the tree breadth-first.  The root keeps the operator reason;
  descendants carry `:parent-terminated`, while `:cancel-root-reason`
  preserves the root cause on every node.

  Three independent-branch shapes are explicit:

    * `accept-independent-failure` changes one branch and leaves siblings
      untouched;
    * `accept-first-accepted` fixes one accepted result and cancels the
      remaining active siblings with `:sibling-accepted`;
    * `accept-required-step-failure` fails one step and cancels the group's
      remaining active branches with `:parent-terminated`.

  `accept-terminal`, `reconcile-unknown`, and `retry-safety` keep unknown
  outcomes honest: a late result is diagnostic, reconciliation needs
  evidence, and an unknown outcome is never retried automatically."
  (:require [clojure.string :as str]
            [hc.compose.cancel.model :as model]
            [hc.compose.cancel.reason :as reason]
            [hc.compose.cancel.transition :as transition]))

;; ---------------------------------------------------------------------------
;; Result helpers

(defn- failure
  ([state code message]
   (failure state code message nil))
  ([state code message details]
   {:ok? false
    :state state
    :error (cond-> {:code code :message message}
             (some? details) (assoc :details details))
    :effects []}))

(defn- success
  [state receipt effects]
  {:ok? true
   :state state
   :receipt receipt
   :effects (vec effects)})

(defn- valid-command-id?
  [command-id]
  (and (string? command-id)
       (not (str/blank? command-id))))

(defn- ancestor-id?
  "True when ANCESTOR-ID is an ancestor of NODE-ID in STATE.

  The walk is cycle-safe: a malformed parent chain cannot spin.  A missing
  node or ancestor returns false."
  [state ancestor-id node-id]
  (loop [id (:parent-id (model/node state node-id))
         seen #{}]
    (cond
      (= id ancestor-id) true
      (nil? id) false
      (contains? seen id) false
      :else (recur (:parent-id (model/node state id))
                   (conj seen id)))))

;; ---------------------------------------------------------------------------
;; Construction and inspection

(def new-state
  "Create a session with one run and no children."
  model/new-state)

(def add-node
  "Add one node to a cancellation tree."
  model/add-node)

(def add-nodes
  "Add nodes in order to a cancellation tree."
  model/add-nodes)

(defn cancel-tree
  "Return the cancellation tree rooted at RUN-ID as plain data.

  This is an observation helper for UI and tests.  It never mutates state."
  [state run-id]
  (let [run-id (or run-id (:active-run-id state))
        run (model/node state run-id)]
    (when (= :run (:kind run))
      (mapv (fn [node]
              (select-keys node
                           [:node-id :kind :parent-id :state :outcome
                            :admission :side-effecting? :resource-alive?
                            :cancel-requested? :cancel-reason
                            :cancel-root-reason :group-id]))
            (model/breadth-first state run-id)))))

(defn active-resources
  "Node ids under RUN-ID that still own a live execution resource.

  An execution state is live even when it is terminal from the business
  point of view: a parked program or a stopped-but-not-yet-observed shell
  keeps `:resource-alive? true`.  The run must not close until the late
  terminal observation clears that flag."
  [state run-id]
  (let [run-id (or run-id (:active-run-id state))
        run (model/node state run-id)]
    (if (= :run (:kind run))
      (->> (model/descendant-nodes state run-id)
           (filter #(or (model/executing? %)
                        (true? (:resource-alive? %))))
           (mapv :node-id))
      [])))

(defn cancellation-quiescent?
  "True when RUN-ID has no live execution resource left."
  [state run-id]
  (let [run-id (or run-id (:active-run-id state))
        run (model/node state run-id)]
    (and (= :run (:kind run))
         (empty? (active-resources state run-id)))))

(defn actionable-resources
  "Unsignalled :call node ids under RUN-ID that a cancellation signal can
  still act on.

  A `:retry-wait` call owns a scheduled retry timer that must be cancelled.
  A `:dispatching` or `:running` call owns the attempt that a terminal
  observation has to clear.  Both can be acted on without waiting for a
  clock, and both stop qualifying once the signal lands: `:retry-wait`
  becomes `:cancelled`, a live call becomes `:outcome-unknown`/`:cancelled`
  with `:cancel-requested? true`.  A node whose cancel transition is
  `:preserved` keeps its state and emits no effect, so it is never selected
  again and cannot spin.

  A call that already carries `:cancel-requested? true` is not selected:
  the signal has already been issued and this helper must not spin.  A
  recovered owner that must re-signal a live resource uses
  `active-resources` and its own recovery path, not this helper.  Actions
  and processes are also excluded here; their live resources are reported
  by `active-resources`."
  [state run-id]
  (->> (active-resources state run-id)
       (filter (fn [node-id]
                 (let [node (model/node state node-id)]
                   (and (= :call (:kind node))
                        (or (contains? #{:retry-wait :dispatching :running}
                                       (:state node))
                            (true? (:resource-alive? node)))
                        (not (true? (:cancel-requested? node)))))))
       vec))

;; ---------------------------------------------------------------------------
;; Cancellation propagation

(defn cancel-subtrees
  "Cancel one or more subtrees in breadth-first order.

  Options:

    :root-ids          non-empty collection of existing node ids
    :reason            one of `hc.compose.cancel.reason/reasons`
    :origin            signal origin
    :operator?         true only for a real operator control
    :close-admission?  close a run's admission gate when one is visited
    :operator-root-check?  require every operator root to be a run
    :command-id        optional stable command id
    :at                optional timestamp

  A root receives `:reason`; every descendant receives
  `:parent-terminated`.  All nodes keep `:cancel-root-reason`.

  The result is `{:ok? true :state state' :receipt receipt :effects [..]}`
  or `{:ok? false :state state :error {...} :effects []}`."
  [state {:keys [root-ids reason origin operator? close-admission?
                 operator-root-check? command-id at]
          :or {operator? false
               close-admission? false
               operator-root-check? true}}]
  (let [root-ids (vec (distinct (or root-ids [])))
        overlap (some (fn [ancestor]
                        (some (fn [descendant]
                                (when (and (not= ancestor descendant)
                                           (ancestor-id? state ancestor descendant))
                                  [ancestor descendant]))
                              root-ids))
                      root-ids)]
    (cond
      (empty? root-ids)
      (failure state :NO_CANCEL_ROOTS
               "cancellation needs at least one root")

      (not (reason/reason? reason))
      (failure state :INVALID_CANCEL_REASON
               "unknown cancellation reason"
               {:reason reason})

      (reason/programmatic-origin? origin)
      (failure state :NOT_A_CANCELLATION
               "park, ask, relay retirement, and slot reincarnation are not cancellations"
               {:origin origin})

      (and (= :user-cancel reason)
           (not (and operator?
                     (reason/operator-origin? origin))))
      (failure state :NOT_OPERATOR_CANCEL
               "only a real operator origin may use :user-cancel"
               {:origin origin :reason reason})

      (and (not= :user-cancel reason)
           (or operator?
               (reason/operator-origin? origin)))
      (failure state :INVALID_CANCEL_REASON
               "an operator origin must carry :user-cancel"
               {:origin origin :reason reason})

      (and (some? command-id)
           (not (valid-command-id? command-id)))
      (failure state :INVALID_COMMAND_ID
               "command-id must be a non-blank string"
               {:command-id command-id})

      (some #(nil? (model/node state %)) root-ids)
      (failure state :NODE_NOT_FOUND
               "a cancellation root does not exist"
               {:root-ids root-ids
                :missing (vec (remove #(model/node state %) root-ids))})

      overlap
      (failure state :OVERLAPPING_CANCEL_ROOTS
               "cancellation roots must not contain an ancestor and its descendant"
               {:root-ids root-ids
                :overlap overlap})

      (and operator-root-check?
           (= :user-cancel reason)
           operator?
           (not (every? #(= :run (:kind (model/node state %))) root-ids)))
      (failure state :OPERATOR_CANCEL_REQUIRES_RUN_ROOT
               "an operator cancellation must root at a run"
               {:root-ids root-ids
                :non-run-roots
                (vec (remove #(= :run (:kind (model/node state %))) root-ids))})

      :else
      (let [root-set (set root-ids)
            ctx {:root-reason reason
                 :origin origin
                 :command-id command-id
                 :operator? (boolean operator?)
                 :at at
                 :close-admission? (or (boolean close-admission?)
                                       (boolean operator?))}
            walked
            (loop [queue (into clojure.lang.PersistentQueue/EMPTY root-ids)
                   seen #{}
                   state state
                   order []
                   dispositions {}
                   effects []]
              (if (empty? queue)
                {:state state
                 :order order
                 :dispositions dispositions
                 :effects effects}
                (let [id (peek queue)
                      queue (pop queue)]
                  (if (contains? seen id)
                    (recur queue seen state order dispositions effects)
                    (let [node (model/node state id)
                          node-reason (if (contains? root-set id)
                                        reason
                                        (reason/descendant-reason reason))
                          {:keys [node disposition] :as step}
                          (transition/cancel-node node
                                                  (assoc ctx :reason node-reason))
                          state (model/assoc-node state node)
                          step-effects (:effects step)]
                      (recur (into queue (map :node-id (model/children state id)))
                             (conj seen id)
                             state
                             (conj order id)
                             (assoc dispositions id disposition)
                             (into effects step-effects)))))))
            {:keys [state order dispositions effects]} walked
            ids-with (fn [wanted]
                       (->> order
                            (filter #(= wanted (get dispositions %)))
                            vec))
            receipt {:status :accepted
                     :reason reason
                     :root-reason reason
                     :descendant-reason :parent-terminated
                     :origin origin
                     :command-id command-id
                     :operator? (boolean operator?)
                     :root-ids root-ids
                     :cancelled (ids-with :cancelled)
                     :outcome-unknown (ids-with :outcome-unknown)
                     :deferred (ids-with :deferred)
                     :preserved (ids-with :preserved)
                     :effect-count (count effects)
                     :closed-admission?
                     (boolean
                      (some (fn [id]
                              (let [node (model/node state id)]
                                (and (= :run (:kind node))
                                     (= :closed (:admission node)))))
                            order))
                     :at at}]
        (success state receipt effects)))))

(defn cancel-subtree
  "Cancel one subtree.  See `cancel-subtrees`."
  [state {:keys [root-id] :as opts}]
  (if (nil? root-id)
    (failure state :NO_CANCEL_ROOT
             "cancellation needs a :root-id")
    (cancel-subtrees state (-> opts
                               (dissoc :root-id)
                               (assoc :root-ids [root-id])))))

(defn cancel-run-late-nodes
  "Cancel one live node that appeared under a cancelling run after the
  cancellation signal.

  `accept-operator-cancel` walks the subtree that existed when Esc arrived.
  A call created later by an in-flight model turn (a tool call is usually
  authorized between the two) is committed under a already `:cancelling`
  run and would otherwise keep `:resource-alive? true` forever, because
  `finish-run` refuses `:RESOURCES_NOT_QUIESCENT` and no second operator
  signal is coming.

  The transition belongs to the domain that is already draining: it takes
  that domain's reason and origin rather than assuming an operator stop.
  An executor shutdown that is draining must not have its late node
  relabelled as `:user-cancel`, and an operator domain must not lose its
  `:operator-esc` origin.  `cancel-subtrees` already checks the pairing of
  origin and reason, so this function adds no second opinion.

  A caller repeats this until `cancellation-quiescent?` holds; a node that
  is already terminal without a live resource is left untouched."
  [state {:keys [run-id node-id reason origin at]}]
  (let [run-id (or run-id (:active-run-id state))
        run (model/node state run-id)
        node (model/node state node-id)
        ;; An operator stop and a shutdown are both cancellations, but only
        ;; the operator form may claim `:operator?`.  Passing it for
        ;; `:executor-shutdown` makes `cancel-subtrees` refuse the domain's
        ;; own reason as `:INVALID_CANCEL_REASON`.  For every operator input
        ;; this is exactly the old hard-coded `true`, and the single pairing
        ;; check stays in `cancel-subtrees`.
        operator? (reason/operator-cancel? {:origin origin :reason reason})]
    (cond
      (nil? run)
      (failure state :RUN_NOT_FOUND "run does not exist" {:run-id run-id})

      (not= :cancelling (:state run))
      (failure state :RUN_NOT_CANCELLING
               "late nodes are cancelled only under a cancelling run"
               {:run-id run-id :state (:state run)})

      (nil? node)
      (failure state :NODE_NOT_FOUND
               "a late cancellation root does not exist"
               {:node-id node-id})

      (not (ancestor-id? state run-id node-id))
      (failure state :NODE_NOT_UNDER_RUN
               "a late cancellation node must belong to the cancelling run"
               {:run-id run-id :node-id node-id})

      :else
      (cancel-subtrees state {:root-ids [node-id]
                              :reason reason
                              :origin origin
                              :operator? operator?
                              ;; This is the late-node path for a run that
                              ;; already closed its admission.  Its root is
                              ;; intentionally a child, not the run itself.
                              :operator-root-check? false
                              :close-admission? false
                              :at at}))))

;; ---------------------------------------------------------------------------
;; Operator cancellation

(defn accept-operator-cancel
  "Accept a real operator Esc/Stop for one run.

  The transition is idempotent by `:command-id`.  A replay with the same
  payload returns the original receipt and no effects.  A different payload
  under the same command id is refused.  The run's admission gate closes
  before propagation.  A programmatic suspend origin is refused and leaves
  the state unchanged."
  [state {:keys [session-id run-id command-id origin reason at]
          :or {reason :user-cancel}}]
  (let [run-id (or run-id (:active-run-id state))
        payload {:session-id (or session-id (:session-id state))
                 :run-id run-id
                 :command-id command-id
                 :origin origin
                 :reason reason}]
    (cond
      (not (valid-command-id? command-id))
      (failure state :COMMAND_ID_REQUIRED
               "operator cancellation needs a non-blank :command-id"
               {:command-id command-id})

      (and (some? session-id)
           (not= session-id (:session-id state)))
      (failure state :SESSION_NOT_FOUND
               "session does not match this cancellation tree"
               {:session-id session-id})

      (reason/programmatic-origin? origin)
      (failure state :NOT_A_CANCELLATION
               "park, ask, relay retirement, and slot reincarnation are not cancellations"
               {:origin origin})

      (not (reason/operator-origin? origin))
      (failure state :NOT_OPERATOR_CANCEL
               "operator cancellation needs an operator origin"
               {:origin origin})

      (not= :user-cancel reason)
      (failure state :INVALID_CANCEL_REASON
               "operator cancellation must use :user-cancel"
               {:reason reason})

      :else
      (let [existing (get-in state [:receipts command-id])]
        (cond
          (and existing (= payload (:payload existing)))
          {:ok? true
           :state state
           :receipt (:receipt existing)
           :effects []
           :replayed? true}

          existing
          (failure state :COMMAND_ID_CONFLICT
                   "command-id was already used with a different payload"
                   {:command-id command-id
                    :existing-payload (:payload existing)
                    :incoming-payload payload})

          :else
          (let [run (model/node state run-id)]
            (cond
              (nil? run)
              (failure state :RUN_NOT_FOUND
                       "run does not exist"
                       {:run-id run-id})

              (not= :run (:kind run))
              (failure state :RUN_NOT_FOUND
                       "target is not a run"
                       {:run-id run-id :kind (:kind run)})

              (not= (:session-id state) (:session-id run))
              (failure state :SESSION_MISMATCH
                       "run belongs to a different session"
                       {:run-id run-id
                        :run-session-id (:session-id run)
                        :session-id (:session-id state)})

              (= :cancelled (:state run))
              (let [receipt {:status :already-cancelled
                             :session-id (:session-id state)
                             :run-id run-id
                             :command-id command-id
                             :reason :user-cancel
                             :origin origin
                             :admission :closed
                             :closed-admission? true
                             :at at}
                    state' (-> state
                               (assoc-in [:nodes run-id :admission] :closed)
                               (assoc-in [:receipts command-id]
                                         {:payload payload :receipt receipt}))]
                {:ok? true
                 :state state'
                 :receipt receipt
                 :effects []})

              (contains? #{:succeeded :failed} (:state run))
              (failure state :RUN_TERMINAL
                       "run already ended; cancellation has nothing to stop"
                       {:run-id run-id :state (:state run)})

              (= :cancelling (:state run))
              (let [receipt {:status :already-cancelling
                             :session-id (:session-id state)
                             :run-id run-id
                             :command-id command-id
                             :reason :user-cancel
                             :origin origin
                             :existing-reason (get-in run [:cancellation :reason])
                             :admission :closed
                             :closed-admission? true
                             :at at}
                    state' (-> state
                               (assoc-in [:nodes run-id :admission] :closed)
                               (assoc-in [:receipts command-id]
                                         {:payload payload :receipt receipt}))]
                {:ok? true
                 :state state'
                 :receipt receipt
                 :effects []})

              :else
              (let [result (cancel-subtrees
                            state
                            {:root-ids [run-id]
                             :reason :user-cancel
                             :origin origin
                             :operator? true
                             :close-admission? true
                             :command-id command-id
                             :at at})]
                (if-not (:ok? result)
                  result
                  (let [receipt (assoc (:receipt result)
                                       :session-id (:session-id state)
                                       :run-id run-id)
                        state' (assoc-in (:state result)
                                         [:receipts command-id]
                                         {:payload payload :receipt receipt})]
                    {:ok? true
                     :state state'
                     :receipt receipt
                     :effects (:effects result)}))))))))))

;; ---------------------------------------------------------------------------
;; Closing the run

(defn- node-outcome
  [node]
  (case (:kind node)
    (:call :attempt) (:state node)
    (:action :process) (or (:outcome node)
                           (when (model/uncertain? node)
                             :outcome-unknown))
    (:run) (:state node)
    nil))

(defn finish-run
  "Close a cancelling run after every live execution resource is gone.

  Unknown outcomes may remain; they are preserved for reconciliation.  A
  terminal node with `:resource-alive? true` (a parked program or an
  in-flight shell not yet observed) still blocks closure until a matching
  terminal observation clears the flag.  A partial result is a projection,
  never a run state."
  [state {:keys [run-id at]}]
  (let [run-id (or run-id (:active-run-id state))
        run (model/node state run-id)]
    (cond
      (nil? run)
      (failure state :RUN_NOT_FOUND
               "run does not exist"
               {:run-id run-id})

      (not= :run (:kind run))
      (failure state :RUN_NOT_FOUND
               "target is not a run"
               {:run-id run-id :kind (:kind run)})

      (= :cancelled (:state run))
      (success state {:status :already-finished
                      :run-id run-id
                      :outcome (:outcome run)
                      :at at}
               [])

      (not= :cancelling (:state run))
      (failure state :RUN_NOT_CANCELLING
               "only a cancelling run can be closed"
               {:run-id run-id :state (:state run)})

      :else
      (let [active (active-resources state run-id)]
        (if (seq active)
          (failure state :RESOURCES_NOT_QUIESCENT
                   "live execution resources remain; abort and observe them first"
                   {:run-id run-id :active active})
          (let [work (filter #(contains? #{:call :attempt :action :process}
                                         (:kind %))
                             (model/descendant-nodes state run-id))
                succeeded (count (filter #(= :succeeded (node-outcome %))
                                         work))
                other (count (remove #(= :succeeded (node-outcome %))
                                     work))
                outcome (if (and (pos? succeeded) (pos? other))
                          :partial
                          :cancelled)
                run' (assoc run
                            :state :cancelled
                            :outcome outcome
                            :admission :closed
                            :finished-at at)]
            (success (model/assoc-node state run')
                     {:status :cancelled
                      :run-id run-id
                      :outcome outcome
                      :at at}
                     [])))))))

;; ---------------------------------------------------------------------------
;; Terminal results, unknown outcomes, and late evidence

(defn accept-terminal
  "Fix a terminal result for a call, attempt, action, or process."
  [state request]
  (transition/accept-terminal state request))

(defn reconcile-unknown
  "Resolve an unknown outcome with explicit evidence."
  [state request]
  (transition/reconcile-unknown state request))

(defn retry-safety
  "Read-only retry decision.  Unknown outcomes are never retried."
  [state node-id]
  (transition/retry-safety state node-id))

(defn record-late-report
  "Store a report that arrived after its run was cancelled.

  The report remains queryable as unaccepted evidence.  It never restarts
  the run, the queue, or the parent task."
  [state {:keys [run-id report at]}]
  (let [run-id (or run-id (:active-run-id state))
        run (model/node state run-id)]
    (cond
      (nil? run)
      (failure state :RUN_NOT_FOUND
               "run does not exist"
               {:run-id run-id})

      (not (contains? #{:cancelling :cancelled} (:state run)))
      (failure state :RUN_NOT_CANCELLED
               "a late report is diagnostic only after cancellation"
               {:run-id run-id :state (:state run)})

      :else
      (let [state' (update-in state [:nodes run-id :late-reports]
                              (fnil conj [])
                              {:report report :at at :accepted? false})]
        (success state'
                 {:status :diagnostic
                  :run-id run-id
                  :report-stored? true
                  :accepted? false
                  :restarted? false
                  :at at}
                 [])))))

;; ---------------------------------------------------------------------------
;; Independent branch combinators

(defn group-nodes
  "Nodes sharing GROUP-ID in construction order."
  [state group-id]
  (if (nil? group-id)
    []
    (->> (:nodes state)
         vals
         (filter #(= group-id (:group-id %)))
         (sort-by #(or (:node-seq %) 0))
         vec)))

(defn- active-sibling-ids
  [nodes excluded-id]
  (->> nodes
       (remove #(= excluded-id (:node-id %)))
       ;; A terminal or unknown sibling whose result is fixed may still own
       ;; a parked executor.  Cancellation must abort that resource without
       ;; rewriting the state or the evidence.  Only a terminal/unknown node
       ;; with no live resource is genuinely preserved.
       (remove (fn [node]
                 (and (or (model/terminal? node)
                          (model/uncertain? node))
                      (not (true? (:resource-alive? node))))))
       (mapv :node-id)))

(defn accept-independent-failure
  "Record one independent branch failure without cancelling siblings."
  [state request]
  (transition/independent-failure state request))

(defn accept-first-accepted
  "Accept the first result of a competing group and cancel the rest.

  The winner is fixed first.  Every other still-active, non-unknown sibling
  is cancelled with `:sibling-accepted`; their descendants carry
  `:parent-terminated`.  Unknown siblings are preserved.  There is no
  sibling cancellation for multi-worktree join; this combinator is only for
  one goal with competing attempts."
  [state {:keys [group-id winner-id outcome error at command-id]}]
  (let [nodes (group-nodes state group-id)
        winner (some #(when (= winner-id (:node-id %)) %) nodes)
        outcome (or outcome :succeeded)]
    (cond
      (nil? group-id)
      (failure state :GROUP_ID_REQUIRED
               "first-accepted needs a :group-id")

      (empty? nodes)
      (failure state :GROUP_NOT_FOUND
               "no nodes carry this group id"
               {:group-id group-id})

      (nil? winner)
      (failure state :WINNER_NOT_IN_GROUP
               "winner does not belong to the group"
               {:group-id group-id :winner-id winner-id})

      (not (contains? #{:succeeded :failed :cancelled} outcome))
      (failure state :INVALID_WINNER_OUTCOME
               "an accepted winner must have a definite outcome"
               {:winner-id winner-id :outcome outcome})

      (model/uncertain-outcome? winner)
      (failure state :WINNER_OUTCOME_UNKNOWN
               "an unknown outcome cannot be the accepted winner"
               {:winner-id winner-id})

      (and (= :action (:kind winner))
           (= :applying (:state winner)))
      (failure state :ACTION_IN_FLIGHT
               "an applying winner must finish its current commit first"
               {:winner-id winner-id})

      :else
      (let [accepted (if (model/terminal? winner)
                       (success state {:status :duplicate
                                       :node-id winner-id
                                       :outcome (node-outcome winner)}
                                [])
                       (transition/accept-terminal state
                                                   {:node-id winner-id
                                                    :outcome outcome
                                                    :error error
                                                    :at at}))]
        (if-not (:ok? accepted)
          accepted
          (let [siblings (active-sibling-ids nodes winner-id)
                cancelled (if (seq siblings)
                            (cancel-subtrees
                             (:state accepted)
                             {:root-ids siblings
                              :reason :sibling-accepted
                              :origin :first-accepted
                              :command-id command-id
                              :at at})
                            (success (:state accepted)
                                     {:status :accepted
                                      :cancelled []
                                      :outcome-unknown []
                                      :deferred []
                                      :preserved []
                                      :effect-count 0
                                      :closed-admission? false}
                                     []))]
            (if-not (:ok? cancelled)
              cancelled
              (let [winner-outcome (node-outcome
                                    (get-in (:state cancelled)
                                            [:nodes winner-id]))
                    receipt (merge (:receipt cancelled)
                                   {:status :first-accepted
                                    :group-id group-id
                                    :winner-id winner-id
                                    :winner-outcome winner-outcome
                                    :accepted (:receipt accepted)})]
                (success (:state cancelled)
                         receipt
                         (:effects cancelled))))))))))

(defn accept-required-step-failure
  "Fail one required step and cancel the group's remaining active branches.

  This is `run-required-steps`: the group cannot succeed after one step
  fails, so its local temporary resources are withdrawn.  Siblings receive
  `:parent-terminated`; unknown siblings are preserved.  The failed step
  remains the first-class failure value.  The failed step's own retry
  timer, write-gate wait, and live executor are settled before the sibling
  cancellation is applied."
  [state {:keys [group-id failed-id error at command-id]}]
  (let [nodes (group-nodes state group-id)
        failed (some #(when (= failed-id (:node-id %)) %) nodes)]
    (cond
      (nil? group-id)
      (failure state :GROUP_ID_REQUIRED
               "required-step failure needs a :group-id")

      (empty? nodes)
      (failure state :GROUP_NOT_FOUND
               "no nodes carry this group id"
               {:group-id group-id})

      (nil? failed)
      (failure state :FAILED_STEP_NOT_IN_GROUP
               "failed step does not belong to the group"
               {:group-id group-id :failed-id failed-id})

      (model/uncertain-outcome? failed)
      (failure state :FAILED_OUTCOME_UNKNOWN
               "an unknown outcome cannot be declared a failed step"
               {:failed-id failed-id})

      (and (model/terminal? failed)
           (not= :failed (node-outcome failed)))
      (failure state :FAILED_STEP_NOT_FAILED
               "the step already has a non-failed terminal outcome"
               {:failed-id failed-id
                :outcome (node-outcome failed)})

      :else
      (let [failed-result (transition/independent-failure
                           state
                           {:node-id failed-id
                            :error error
                            :at at
                            :settle-resources? true})]
        (if-not (:ok? failed-result)
          failed-result
          (let [siblings (active-sibling-ids nodes failed-id)
                cancelled (if (seq siblings)
                            (cancel-subtrees
                             (:state failed-result)
                             {:root-ids siblings
                              :reason :parent-terminated
                              :origin :required-step-failure
                              :command-id command-id
                              :at at})
                            (success (:state failed-result)
                                     {:status :accepted
                                      :cancelled []
                                      :outcome-unknown []
                                      :deferred []
                                      :preserved []
                                      :effect-count 0
                                      :closed-admission? false}
                                     []))]
            (if-not (:ok? cancelled)
              cancelled
              (let [effects (vec (concat (:effects failed-result)
                                         (:effects cancelled)))
                    receipt (merge (:receipt cancelled)
                                   {:status :required-step-failure
                                    :group-id group-id
                                    :failed-id failed-id
                                    :failed-outcome (node-outcome
                                                     (get-in (:state cancelled)
                                                             [:nodes failed-id]))
                                    :failed (:receipt failed-result)
                                    :effect-count (count effects)})]
                (success (:state cancelled)
                         receipt
                         effects)))))))))

;; ---------------------------------------------------------------------------
;; Other explicit cancellation reasons

(defn cancel-due-deadline
  "Cancel a subtree because its deadline expired."
  [state {:keys [root-id command-id at]}]
  (cancel-subtree state
                  {:root-id root-id
                   :reason :deadline-exceeded
                   :origin :deadline
                   :command-id command-id
                   :close-admission? true
                   :at at}))

(defn cancel-due-executor-shutdown
  "Cancel a run because its executor is shutting down."
  [state {:keys [root-id command-id at]}]
  (let [root-id (or root-id (:active-run-id state))]
    (cancel-subtree state
                    {:root-id root-id
                     :reason :executor-shutdown
                     :origin :executor-shutdown
                     :command-id command-id
                     :close-admission? true
                     :at at})))
