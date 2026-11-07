(ns hc.compose.owner.board
  "Owner-backed board adapters.

  The Owner holds the only authoritative swarm board.  A second kernel would
  create a second writer, so workspace commits and Shell sessions submit
  board commands through `:execute-board!` and wait for the Owner commit.

  The port is generic.  `workspace-adapter` implements
  `hc.core.workspace-gate.board/BoardAdapter`.  `shell-port` returns the
  four functions a server-side `ShellBoard` reify can delegate to; this
  namespace never requires `hc.server.*`.

  Eligibility is a read-only check on the committed snapshot.  A board
  command refusal before a file write is an ordinary rejection; a refusal
  after a file write is a hard failure.  The Owner never acquires W."
  (:require [hc.core.swarm-stm.board :as swarm-board]
            [hc.core.workspace-gate.board :as ws-board]
            [hc.core.workspace-gate.error :as err]))

;; ---------------------------------------------------------------------------
;; Port

(defn make-port
  "Build a board port over an Owner handle.

  HANDLE must provide `:execute-board!` and `:board-snapshot`."
  [handle]
  {:execute! (fn
               ([command] ((:execute-board! handle) command))
               ([command opts] ((:execute-board! handle) command 5000 opts)))
   :snapshot (:board-snapshot handle)})

(defn snapshot
  [port]
  ((:snapshot port)))

(defn execute!
  ([port command] ((:execute! port) command))
  ([port command opts] ((:execute! port) command opts)))

(defn board-version
  [port]
  (:board-version (snapshot port)))

;; ---------------------------------------------------------------------------
;; Eligibility and refusal mapping

(defn- claim-actor
  [claim]
  (or (:agent-id claim) (:actor-id claim)))

(defn- action-actor
  [action]
  (or (:agent-id action) (:actor-id action)))

(defn- in-flight-phase?
  [phase]
  (contains? #{:applying :running :uncertain} phase))

(defn- reject-in-flight!
  [board request]
  (let [actor-id (:actor-id request)
        claim-id (:claim-id request)
        task-id (:task-id request)
        request-id (:request-id request)
        conflicts (->> (:actions board)
                       vals
                       (filter #(and (in-flight-phase? (:phase %))
                                     (not= request-id (:request-id %))
                                     (or (= actor-id (action-actor %))
                                         (= claim-id (:claim-id %))
                                         (= task-id (:task-id %)))))
                       vec)]
    (when (seq conflicts)
      (err/fail! :ACTION_IN_FLIGHT :action-in-flight
                 "Task, claim or actor already has an in-flight action"
                 {:actor-id actor-id
                  :claim-id claim-id
                  :task-id task-id
                  :actions (mapv #(select-keys % [:action-id :request-id
                                                   :phase :task-id :claim-id])
                                 conflicts)}))))

(defn check-eligibility!
  "Read-only eligibility check against the committed board.

  For `:shell`, returns an eligibility snapshot
  `{:task-rev :claim-id :term-id :actor-id}` for `begin-run!` to revalidate
  while W is held.  For other kinds returns nil.  A refusal throws an
  ordinary `hc.core.workspace-gate.error` rejection."
  [port {:keys [actor-id claim-id task-id kind token-term term-id request-id]
         :as request}]
  (let [board (snapshot port)
        actor (get-in board [:actors actor-id])
        task (get-in board [:tasks task-id])
        claim (get-in board [:claims claim-id])
        current-term (get-in board [:token-slot :term-id])
        token-term (or token-term term-id)]
    (when-not (and actor (= :alive (:life-state actor)))
      (err/fail! :ACTOR_INACTIVE :actor-inactive
                 "Actor is not alive"
                 {:actor-id actor-id}))
    (when (and task claim-id (not= claim-id (:claim-id task)))
      (err/fail! :CLAIM_CHANGED :claim-changed
                 "Claim is not current"
                 {:claim-id claim-id
                  :current-claim-id (:claim-id task)}))
    (when (= kind :shell)
      (when-not (swarm-board/holder? board actor-id)
        (err/fail! :TOKEN_REQUIRED :not-holder
                   "Shell requires the current token holder"
                   {:actor-id actor-id
                    :holder-id (get-in board [:token-slot :holder-id])}))
      (when-not token-term
        (err/fail! :TOKEN_REQUIRED :term-missing
                   "Shell requires the current token term"
                   {:actor-id actor-id}))
      (when-not (= token-term current-term)
        (err/fail! :TOKEN_REQUIRED :term-mismatch
                   "Token term is stale"
                   {:token-term token-term
                    :current-term current-term}))
      (when-not task
        (err/fail! :CLAIM_CHANGED :unknown-task
                   "Shell task does not exist"
                   {:task-id task-id}))
      (when-not (= :ready (:state task))
        (err/fail! :CLAIM_CHANGED :task-not-ready
                   "Shell task is no longer ready"
                   {:task-id task-id
                    :state (:state task)}))
      (when-not claim
        (err/fail! :CLAIM_CHANGED :unknown-claim
                   "Shell claim does not exist"
                   {:claim-id claim-id}))
      (when-not (= actor-id (claim-actor claim))
        (err/fail! :CLAIM_CHANGED :claim-actor-mismatch
                   "Claim belongs to another actor"
                   {:claim-id claim-id
                    :claim-actor-id (claim-actor claim)}))
      (when-not (= task-id (:task-id claim))
        (err/fail! :CLAIM_CHANGED :claim-task-mismatch
                   "Claim belongs to another task"
                   {:claim-id claim-id
                    :claim-task-id (:task-id claim)
                    :task-id task-id}))
      (when-not (or (:needs-token task)
                    (= actor-id (:owner-id task)))
        (err/fail! :CLAIM_CHANGED :holder-has-no-responsibility
                   "Shell task must be a token task or owned by the holder"
                   {:task-id task-id
                    :owner-id (:owner-id task)
                    :needs-token (:needs-token task)}))
      (reject-in-flight! board request))
    (when (= kind :shell)
      {:task-rev (:rev task)
       :claim-id claim-id
       :term-id current-term
       :actor-id actor-id})))

(defn- command-result
  "Unwrap a wrapper receipt `{:ok? true :result command-result}`.  A real
  Owner receipt is already the command result."
  [receipt]
  (if (and (not (false? (:ok? receipt)))
           (map? (:result receipt)))
    (:result receipt)
    receipt))

(defn- receipt-ok?
  [receipt]
  (and (not (false? (:ok? receipt)))
       (not= :refused (:status receipt))))

(defn- receipt-failure
  "Return the failure map from either a direct Owner refusal
  `{:ok? false :error {:code ...}}` or a wrapper
  `{:ok? false :result {:ok? false :error ...}}`."
  [receipt]
  (if (and (false? (:ok? receipt)) (map? (:result receipt)))
    (:result receipt)
    receipt))

(defn- rejection-status
  [code]
  (case code
    (:ACTOR_INACTIVE :UNKNOWN_ACTOR) :ACTOR_INACTIVE
    (:CLAIM_CHANGED :NOT_ACTION_OWNER :TASK_NOT_READY :TASK_DONE
     :NOT_OWNER :UNKNOWN_TASK) :CLAIM_CHANGED
    (:TOKEN_REQUIRED :ALREADY_HOLDER) :TOKEN_REQUIRED
    :ACTION_IN_FLIGHT :ACTION_IN_FLIGHT
    :PREPARE_REJECTED))

(defn- board-failure
  [receipt]
  (let [result (receipt-failure receipt)
        err (:error result)
        code (if (map? err) (:code err) err)
        details (if (map? err) (:details err) (:detail result))]
    (err/fail! (rejection-status code)
               code
               (or (:message result)
                   (when (map? err) (:message err))
                   "board command rejected")
               (assoc (or details {}) :board-code code))))

(defn- publish-failure
  [result]
  (ex-info "board publication failed after the file or Shell effect"
           {:code :BOARD_PUBLISH_FAILED
            :status :HARD_FAILURE
            :outcome :unknown
            :board-error (:error result)}))

;; ---------------------------------------------------------------------------
;; Actions

(defn advance!
  "Advance one action to PHASE.

  A repeated target is a no-op.  A finished action is never rewritten.  A
  board refusal throws: before a write it is an ordinary rejection, after a
  write the caller treats it as a hard failure."
  ([port actor-id action-id phase]
   (advance! port actor-id action-id phase nil))
  ([port actor-id action-id phase result-ref]
   (let [current (get-in (snapshot port) [:actions action-id :phase])]
     (if (or (= current phase) (= :finished current))
       {:ok? true :already? true :phase current}
       (let [receipt (execute! port
                               (cond-> {:op :advance-action
                                        :actor-id actor-id
                                        :action-id action-id
                                        :phase phase}
                                 (some? result-ref)
                                 (assoc :result-ref result-ref)))]
         (if (receipt-ok? receipt)
           (command-result receipt)
           (throw (board-failure receipt))))))))

(defn begin-action!
  "Begin one board action and move it to its target phase.

  Shell actions pass through `:waiting-gate` before `:running`, matching the
  managed Shell contract.  Edit actions move directly to `:applying`."
  [port {:keys [actor-id task-id claim-id kind request-id token-term term-id]
         :as request} target-phase summary]
  (check-eligibility! port
                      (select-keys request
                                   [:actor-id :task-id :claim-id :kind
                                    :request-id :token-term :term-id]))
  (let [receipt (execute! port {:op :begin-action
                                :actor-id actor-id
                                :task-id task-id
                                :claim-id claim-id
                                :kind kind
                                :request-id request-id})
        result (command-result receipt)]
    (if-not (receipt-ok? receipt)
      (throw (board-failure receipt))
      (let [action-id (:action-id result)]
        (when-not action-id
          (throw (ex-info "board did not return an action id"
                          {:code :UNKNOWN_ACTION
                           :result result})))
        (if (= kind :shell)
          (do
            (advance! port actor-id action-id :waiting-gate)
            (advance! port actor-id action-id :running))
          (advance! port actor-id action-id target-phase))
        {:board :owner
         :actor-id actor-id
         :action-id action-id
         :request-id request-id
         :phase target-phase
         :summary summary}))))

(defn publish!
  [port permit phase result]
  (advance! port (:actor-id permit) (:action-id permit) phase result))

(defn publish-hard-failure!
  [port permit result]
  (let [response (execute! port {:op :advance-action
                                 :actor-id (:actor-id permit)
                                 :action-id (:action-id permit)
                                 :phase :uncertain
                                 :result-ref result})]
    (if (receipt-ok? response)
      (command-result response)
      (throw (publish-failure response)))))

;; ---------------------------------------------------------------------------
;; Adapters

(defn workspace-adapter
  "Return a `hc.core.workspace-gate.board/BoardAdapter` over PORT."
  [port]
  (reify ws-board/BoardAdapter
    (check-eligibility! [_ request]
      (check-eligibility! port (assoc request :kind :edit)))

    (begin-apply! [_ request summary]
      (begin-action! port (assoc request :kind :edit) :applying summary))

    (publish-applied! [_ permit result]
      (publish! port permit :finished result))

    (publish-failure! [_ permit failure]
      (publish-hard-failure! port permit failure))))

(defn begin-shell-run!
  "Revalidate the queued shell request while the caller holds W, then begin
  the action.  `:expected-eligibility` is the snapshot returned by
  `check-eligibility!` before queueing; a task revision or token term change
  is refused before any process starts."
  [port request summary]
  (let [expected (:expected-eligibility request)
        current (check-eligibility! port (assoc request :kind :shell))]
    (when expected
      (when (and (contains? expected :task-rev)
                 (not= (:task-rev expected) (:task-rev current)))
        (err/fail! :CLAIM_CHANGED :task-meaning-changed
                   "Shell task changed while the request waited for W"
                   {:task-id (:task-id request)
                    :expected-task-rev (:task-rev expected)
                    :current-task-rev (:task-rev current)}))
      (when (and (contains? expected :term-id)
                 (not= (:term-id expected) (:term-id current)))
        (err/fail! :TOKEN_REQUIRED :term-mismatch
                   "Token term changed while the shell request waited for W"
                   {:request-term (:term-id expected)
                    :current-term (:term-id current)})))
    (begin-action! port (assoc request :kind :shell) :running summary)))

(defn shell-port
  "Return the four functions a server-side `ShellBoard` reify delegates to.

  Keys: `:check-eligibility!`, `:begin-run!`, `:publish-finished!`,
  `:publish-uncertain!`."
  [port]
  {:check-eligibility! (fn [request]
                         (check-eligibility! port (assoc request :kind :shell)))
   :begin-run! (fn [request summary]
                 (begin-shell-run! port request summary))
   :publish-finished! (fn [permit result]
                        (publish! port permit :finished result))
   :publish-uncertain! (fn [permit result]
                         (publish! port permit :uncertain result))})
