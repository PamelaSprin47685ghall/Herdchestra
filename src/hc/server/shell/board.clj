(ns hc.server.shell.board
  "Board adapter for the managed shell path.

  The shell reuses the same adapter boundary as the protected file commit:
  eligibility is checked before the process starts, the running qualification
  is fixed in a short STM transition while W is held, and the result is
  published as a pure control update.  Shell does not call the file preparer
  or pretend to understand arbitrary command effects.

  Two implementations are provided:

  - `memory-board` is a deterministic reference adapter for tests;
  - `swarm-board` adapts `hc.core.swarm-stm` and validates the live actor,
    token holder, term, claim and `:ready` task before it starts an action.

  A request uses `:actor-id`, `:token-term`, `:task-id` and `:claim` (the
  `:claim-id` alias is accepted).  The holder/term and claim are revalidated
  by `begin-run!`; the early `check-eligibility!` call is only an early
  refusal, not the authoritative start gate."
  (:require [clojure.string :as str]
            [hc.core.swarm-stm :as swarm]
            [hc.server.shell.error :as err]))

(set! *warn-on-reflection* true)

(defprotocol ShellBoard
  (check-eligibility!
    [board request]
    "Throw an ordinary rejection when the actor, holder, term, claim or task
    is not eligible.  Return an eligibility snapshot (at least `:task-rev`,
    `:term-id` and `:claim-id`) when the request may wait for W.  The session
    passes this snapshot back to `begin-run!` so a task that changed meaning
    while queued is refused before the process starts.")
  (begin-run!
    [board request summary]
    "Revalidate eligibility inside the caller's W hold, register the running
    action responsibility, and return a permit for the publication methods.")
  (publish-finished!
    [board permit result]
    "Publish a finished result as a pure control update.  May throw an STM
    conflict; the session retries only this call.")
  (publish-uncertain!
    [board permit result]
    "Publish an uncertain outcome as a pure control update.  May throw an STM
    conflict; the session retries only this call."))

;; ---------------------------------------------------------------------------
;; request helpers

(defn claim-id
  [request]
  (or (:claim request) (:claim-id request)))

(defn token-term
  [request]
  (or (:token-term request) (:term-id request)))

(defn- nonblank?
  [x]
  (and (string? x) (not (str/blank? x))))

(defn- in-flight-phase?
  [phase]
  (contains? #{:applying :running :uncertain} phase))

(defn- action-actor
  [action]
  (or (:actor-id action) (:agent-id action)))

(defn- reject-in-flight!
  [actions request]
  (let [request-id (:request-id request)
        actor-id (:actor-id request)
        claim-id (claim-id request)
        task-id (:task-id request)
        conflicts (->> (vals actions)
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
                  :actions (mapv #(select-keys % [:action-id :request-id :phase
                                                   :task-id :claim-id])
                                 conflicts)}))))

(defn- check-responsibility!
  "The holder must still have this task in R(a) after begin-action, otherwise
  the swarm kernel would retire the holder between begin and advance."
  [task request]
  (when-not (or (:needs-token task)
                (= (:actor-id request) (:owner-id task)))
    (err/fail! :CLAIM_CHANGED :holder-has-no-responsibility
               "Shell task must be a token task or owned by the holder"
               {:task-id (:task-id request)
                :owner-id (:owner-id task)
                :needs-token (:needs-token task)})))

(defn- claim-actor
  [claim]
  (or (:actor-id claim) (:agent-id claim)))

(defn- eligibility-snapshot
  [snapshot request]
  {:task-rev (get-in snapshot [:tasks (:task-id request) :rev])
   :claim-id (claim-id request)
   :term-id (get-in snapshot [:token-slot :term-id])
   :actor-id (:actor-id request)})

(defn- check-queue-snapshot!
  "Refuse a request whose task meaning or token term changed while it waited
  for W.  The snapshot is captured by `check-eligibility!` before queueing."
  [request expected current]
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
      (err/fail! :TOKEN_TERM_STALE :term-mismatch
                 "Token term changed while the shell request waited for W"
                 {:request-term (:term-id expected)
                  :current-term (:term-id current)}))))

(defn- check-common
  [actor token claim task request]
  (let [actor-id (:actor-id request)
        term (token-term request)]
    (when-not (and actor (= :alive (:life-state actor)))
      (err/fail! :ACTOR_INACTIVE :actor-inactive
                 "Actor is not alive"
                 {:actor-id actor-id
                  :life-state (:life-state actor)}))
    (when-not (= actor-id (:holder-id token))
      (err/fail! :TOKEN_REQUIRED :not-holder
                 "Only the current token holder may start a managed shell"
                 {:actor-id actor-id
                  :holder-id (:holder-id token)}))
    (when-not (= term (:term-id token))
      (err/fail! :TOKEN_TERM_STALE :term-mismatch
                 "Shell request token term is not the current term"
                 {:actor-id actor-id
                  :request-term term
                  :current-term (:term-id token)}))
    (when (nil? claim)
      (err/fail! :CLAIM_CHANGED :unknown-claim
                 "Claim does not exist"
                 {:claim-id (claim-id request)}))
    (when-not (= actor-id (claim-actor claim))
      (err/fail! :CLAIM_CHANGED :claim-actor-mismatch
                 "Claim belongs to another actor"
                 {:claim-id (claim-id request)
                  :claim-actor-id (claim-actor claim)}))
    (when-not (= (:task-id request) (:task-id claim))
      (err/fail! :CLAIM_CHANGED :claim-task-mismatch
                 "Claim belongs to another task"
                 {:claim-id (claim-id request)
                  :claim-task-id (:task-id claim)
                  :task-id (:task-id request)}))
    ;; The task must exist before any task field is dereferenced; otherwise a
    ;; missing task is misreported as "claim is not the task's current claim".
    (when-not task
      (err/fail! :CLAIM_CHANGED :unknown-task
                 "Task does not exist"
                 {:task-id (:task-id request)}))
    (when-not (= (claim-id request) (:claim-id task))
      (err/fail! :CLAIM_CHANGED :claim-not-current
                 "Claim is not the task's current claim"
                 {:claim-id (claim-id request)
                  :current-claim-id (:claim-id task)}))
    (when-not (= :ready (:state task))
      (err/fail! :CLAIM_CHANGED :task-not-ready
                 "Shell task is no longer ready"
                 {:task-id (:task-id request)
                  :state (:state task)}))
    (check-responsibility! task request)))

;; ---------------------------------------------------------------------------
;; memory reference adapter

(declare publish-memory!)

(defrecord MemoryBoard [state]
  ShellBoard
  (check-eligibility! [_ request]
    (let [{:keys [actors token claims tasks actions]} @state
          task (get tasks (:task-id request))]
      (check-common (get actors (:actor-id request))
                    token
                    (get claims (claim-id request))
                    task
                    request)
      (reject-in-flight! actions request)
      {:task-rev (:rev task)
       :claim-id (claim-id request)
       :term-id (:term-id token)
       :actor-id (:actor-id request)}))

  (begin-run! [_ request summary]
    (let [{:keys [actors token claims tasks actions]} @state
          task (get tasks (:task-id request))
          current {:task-rev (:rev task)
                   :claim-id (claim-id request)
                   :term-id (:term-id token)
                   :actor-id (:actor-id request)}]
      (check-common (get actors (:actor-id request))
                    token
                    (get claims (claim-id request))
                    task
                    request)
      (check-queue-snapshot! request (:expected-eligibility request) current)
      (reject-in-flight! actions request)
      (let [action {:action-id (:request-id request)
                    :request-id (:request-id request)
                    :actor-id (:actor-id request)
                    :task-id (:task-id request)
                    :claim-id (claim-id request)
                    :phase :running
                    :summary summary
                    :started-at (java.util.Date.)}]
        (swap! state assoc-in [:actions (:request-id request)] action)
        {:board :memory
         :action-id (:action-id action)
         :request-id (:request-id request)
         :phase :running})))

  (publish-finished! [_ permit result]
    (publish-memory! state permit result :finished))

  (publish-uncertain! [_ permit result]
    (publish-memory! state permit result :uncertain)))

(defn- publish-memory!
  [state permit result target]
  (let [{:keys [fail-publish actions]} @state
        request-id (:request-id permit)
        action (get actions request-id)]
    (when (pos? (long (or fail-publish 0)))
      (swap! state update :fail-publish dec)
      (throw (ex-info "Simulated STM publication conflict"
                      {:code :STM_CONFLICT})))
    (when-not action
      (throw (ex-info "Shell action disappeared before publication"
                      {:code :UNKNOWN_ACTION :request-id request-id})))
    (if (= :finished (:phase action))
      {:published true :already? true :phase :finished}
      (do
        (swap! state assoc-in [:actions request-id :phase] target)
        (swap! state assoc-in [:actions request-id :result] result)
        {:published true :phase target}))))

(defn memory-board
  "Reference adapter with no actors, claims or tasks.  Configure it with the
  `set-*!` helpers in tests."
  []
  (->MemoryBoard (atom {:actors {}
                        :token {:holder-id nil :term-id 0}
                        :claims {}
                        :tasks {}
                        :actions {}
                        :fail-publish 0})))

(defn set-actor!
  [board actor-id alive?]
  (swap! (:state board) assoc-in [:actors actor-id]
         {:actor-id actor-id :life-state (if alive? :alive :dead)})
  board)

(defn set-token!
  [board holder-id term-id]
  (swap! (:state board) assoc :token {:holder-id holder-id :term-id term-id})
  board)

(defn set-claim!
  "Set a claim.  CLAIM is `{:claim-id :actor-id :task-id}`; a missing
  `:claim-id` uses the map key."
  [board claim]
  (let [id (:claim-id claim)
        task-id (:task-id claim)]
    (swap! (:state board)
           (fn [state]
             (cond-> (assoc-in state [:claims id]
                               (assoc claim :claim-id id
                                      :actor-id (or (:actor-id claim)
                                                    (:agent-id claim))))
               (get-in state [:tasks task-id])
               (assoc-in [:tasks task-id :claim-id] id))))
    board))

(defn set-task!
  [board task-id task-state & [extra]]
  (swap! (:state board)
         (fn [board-state]
           (let [claim-id (if (contains? (or extra {}) :claim-id)
                            (:claim-id extra)
                            (some (fn [[id claim]]
                                    (when (= task-id (:task-id claim)) id))
                                  (:claims board-state)))]
             (assoc-in board-state [:tasks task-id]
                       (merge {:task-id task-id
                               :state task-state
                               :claim-id claim-id}
                              extra)))))
  board)

(defn fail-publishes!
  [board n]
  (swap! (:state board) assoc :fail-publish (long n))
  board)

(defn board-action
  [board request-id]
  (get-in @(:state board) [:actions request-id]))

(defn board-state
  [board]
  @(:state board))

;; ---------------------------------------------------------------------------
;; swarm adapter

(defn- kernel-of
  [board]
  ((:kernel-ref board)))

(defn- ok-result?
  [out]
  (not= false (:ok? out)))

(defn- swarm-status
  [code]
  (case code
    (:ACTOR_INACTIVE :UNKNOWN_ACTOR :ACTOR_REQUIRED) :ACTOR_INACTIVE
    (:CLAIM_CHANGED :TASK_NOT_READY :TASK_DONE :NOT_OWNER) :CLAIM_CHANGED
    (:TOKEN_REQUIRED :ALREADY_HOLDER) :TOKEN_REQUIRED
    :ACTION_IN_FLIGHT :ACTION_IN_FLIGHT
    :BOARD_EVENT_PERSIST_FAILED :BOARD_EVENT_PERSIST_FAILED
    :HARD_FAILURE))

(defn- swarm-fail!
  [out]
  (let [{:keys [error detail]} (if (map? out) out {})
        status (swarm-status error)]
    (if (= :HARD_FAILURE status)
      (throw (ex-info (str "Board rejected the shell action: " (pr-str error))
                      {:code error :detail detail}))
      (err/fail! status error
                 "Board rejected the shell action"
                 {:board-error error :detail detail}))))

(defn- check-swarm
  [snapshot request]
  (let [actor-id (:actor-id request)
        token (:token-slot snapshot)
        claim (get-in snapshot [:claims (claim-id request)])
        task (get-in snapshot [:tasks (:task-id request)])]
    (check-common (get-in snapshot [:actors actor-id])
                  token claim task request)
    (reject-in-flight! (:actions snapshot) request)
    (eligibility-snapshot snapshot request)))

(defn- execute-or-fail!
  [execute-fn command]
  (let [out (execute-fn command)]
    (if (ok-result? out)
      out
      (swarm-fail! out))))

(defn- advance-with!
  ([execute-fn actor-id action-id phase]
   (advance-with! execute-fn actor-id action-id phase nil))
  ([execute-fn actor-id action-id phase result-ref]
   (execute-or-fail! execute-fn
                     (cond-> {:op :advance-action
                              :actor-id actor-id
                              :action-id action-id
                              :phase phase}
                       result-ref (assoc :result-ref result-ref)))))

(defn- checkpoint-published!
  "Force an already-committed shell action through the durable event hook.
  Memory is not evidence that the publication survived a restart."
  [execute-fn permit phase]
  (let [out (execute-fn {:op :checkpoint
                         :actor-id (:actor-id permit)
                         :reason :publication-retry})]
    (if (and (map? out) (ok-result? out))
      {:published true :already? true :phase phase}
      (throw (ex-info "Shell publication is still not durable"
                      {:code :STM_CONFLICT
                       :action-id (:action-id permit)
                       :persisted? (:persisted? out)
                       :board-error (:error out)
                       :detail (:detail out)})))))

(defn- begin-run-with!
  [snapshot-fn execute-fn request summary]
  (let [snapshot (snapshot-fn)
        current (check-swarm snapshot request)]
    (check-queue-snapshot! request (:expected-eligibility request) current)
    (let [begun (execute-or-fail!
                 execute-fn
                 {:op :begin-action
                  :actor-id (:actor-id request)
                  :task-id (:task-id request)
                  :claim-id (claim-id request)
                  :kind :shell
                  :request-id (:request-id request)})
          action-id (:action-id begun)]
      (when-not action-id
        (throw (ex-info "Board did not return a shell action id"
                        {:code :UNKNOWN_ACTION :result begun})))
      (try
        (advance-with! execute-fn (:actor-id request) action-id :waiting-gate)
        (advance-with! execute-fn (:actor-id request) action-id :running)
        {:board :shell
         :action-id action-id
         :actor-id (:actor-id request)
         :request-id (:request-id request)
         :phase :running
         :summary summary}
        (catch clojure.lang.ExceptionInfo e
          ;; The action was created but never reached :running.  There is no
          ;; public command to remove a :claimed/:waiting-gate action; report
          ;; the residual action and its permit so the session can close it
          ;; (publication from those phases advances to :running first)
          ;; instead of pretending it vanished.
          (throw (ex-info "Shell action could not reach :running"
                          {:code :SHELL_ACTION_NOT_RUNNING
                           :action-id action-id
                           :request-id (:request-id request)
                           :permit {:board :shell
                                    :action-id action-id
                                    :actor-id (:actor-id request)
                                    :request-id (:request-id request)
                                    :phase (get-in (snapshot-fn)
                                                   [:actions action-id :phase])}
                           :residual-phase (get-in (snapshot-fn)
                                                   [:actions action-id :phase])
                           :cause (err/throwable->data e)})))))))

(defn- publish-finished-with!
  [snapshot-fn execute-fn permit result]
  (let [action (get-in (snapshot-fn) [:actions (:action-id permit)])]
    (when-not action
      (throw (ex-info "Shell action disappeared before publication"
                      {:code :UNKNOWN_ACTION
                       :action-id (:action-id permit)})))
    (case (:phase action)
      :finished (checkpoint-published! execute-fn permit :finished)
      :uncertain (do (advance-with! execute-fn (action-actor action)
                                    (:action-id action) :finished result)
                     {:published true :phase :finished})
      :running (do (advance-with! execute-fn (action-actor action)
                                  (:action-id action) :finished result)
                   {:published true :phase :finished})
      ;; A failure between `:begin-action` and the running transition leaves
      ;; a pre-start action.  Close it instead of stranding it: the table
      ;; allows :claimed/:waiting-gate -> :running -> :finished.
      (:claimed :waiting-gate)
      (do (advance-with! execute-fn (action-actor action)
                         (:action-id action) :running)
          (advance-with! execute-fn (action-actor action)
                         (:action-id action) :finished result)
          {:published true :phase :finished})
      (throw (ex-info "Shell action is not in a publishable phase"
                      {:code :INVALID_ACTION_TRANSITION
                       :action-id (:action-id permit)
                       :phase (:phase action)})))))

(defn- publish-uncertain-with!
  [snapshot-fn execute-fn permit result]
  (let [action (get-in (snapshot-fn) [:actions (:action-id permit)])]
    (when-not action
      (throw (ex-info "Shell action disappeared before publication"
                      {:code :UNKNOWN_ACTION
                       :action-id (:action-id permit)})))
    (case (:phase action)
      :uncertain (checkpoint-published! execute-fn permit :uncertain)
      :finished (checkpoint-published! execute-fn permit :finished)
      :running (do (advance-with! execute-fn (action-actor action)
                                  (:action-id action) :uncertain result)
                   {:published true :phase :uncertain})
      (:claimed :waiting-gate)
      (do (advance-with! execute-fn (action-actor action)
                         (:action-id action) :running)
          (advance-with! execute-fn (action-actor action)
                         (:action-id action) :uncertain result)
          {:published true :phase :uncertain})
      (throw (ex-info "Shell action is not in a publishable phase"
                      {:code :INVALID_ACTION_TRANSITION
                       :action-id (:action-id permit)
                       :phase (:phase action)})))))

(defrecord SwarmBoard [kernel-ref]
  ShellBoard
  (check-eligibility! [_ request]
    (check-swarm (swarm/snapshot (kernel-of _)) request))

  (begin-run! [_ request summary]
    (let [kernel (kernel-of _)]
      (begin-run-with! #(swarm/snapshot kernel)
                       #(swarm/execute! kernel %)
                       request summary)))

  (publish-finished! [_ permit result]
    (let [kernel (kernel-of _)]
      (publish-finished-with! #(swarm/snapshot kernel)
                              #(swarm/execute! kernel %)
                              permit result)))

  (publish-uncertain! [_ permit result]
    (let [kernel (kernel-of _)]
      (publish-uncertain-with! #(swarm/snapshot kernel)
                               #(swarm/execute! kernel %)
                               permit result))))

(defn swarm-board
  "Adapt a swarm kernel.  KERNEL-OR-PROVIDER is either the kernel map returned
  by `hc.core.swarm-stm/create` or a zero-argument function returning it, so
  an application can wire the board before the Owner creates the kernel."
  [kernel-or-provider]
  (let [provider (cond
                   (map? kernel-or-provider) (constantly kernel-or-provider)
                   (ifn? kernel-or-provider) kernel-or-provider
                   :else (throw (IllegalArgumentException.
                                 "swarm-board requires a kernel map or provider fn")))]
    (->SwarmBoard provider)))

(defn- owner-failure
  "Normalize the several Owner refusal receipts to the board-failure shape
  used by `swarm-fail!`."
  [out]
  (let [inner (if (and (false? (:ok? out)) (map? (:result out)))
                (:result out)
                out)
        err (:error inner)
        code (if (map? err) (:code err) err)
        details (when (map? err)
                  (or (:details err)
                      (dissoc err :code :message)))]
    {:ok? false
     :error code
     :detail details
     :message (or (:message inner)
                  (when (map? err) (:message err)))}))

(defn- owner-execute!
  "Normalize an Owner command receipt to the command result map used by the
  shell adapter.  The Owner remains the only writer of its board.

  `submit-board-command!` returns a command result with `:status :accepted`
  on success, `{:status :refused :error ...}` on refusal, and
  `{:ok? false :error ...}` on timeout.  A test/app wrapper may instead
  return `{:ok? true :result ...}`."
  [execute-board-fn]
  (fn [command]
    (let [out (execute-board-fn command)]
      (cond
        (false? (:ok? out)) (owner-failure out)
        (= :refused (:status out)) (owner-failure out)
        (and (contains? out :error) (not (contains? out :result)))
        (owner-failure out)
        (contains? out :result) (:result out)
        :else out))))

(defrecord OwnerBoard [execute-fn snapshot-fn]
  ShellBoard
  (check-eligibility! [_ request]
    (check-swarm (snapshot-fn) request))

  (begin-run! [_ request summary]
    (begin-run-with! snapshot-fn (owner-execute! execute-fn) request summary))

  (publish-finished! [_ permit result]
    (publish-finished-with! snapshot-fn (owner-execute! execute-fn)
                             permit result))

  (publish-uncertain! [_ permit result]
    (publish-uncertain-with! snapshot-fn (owner-execute! execute-fn)
                              permit result)))

(defn owner-board
  "Adapt a synchronous Owner board command function.

  EXECUTE-BOARD-FN receives one command map and returns
  `{:ok? true :board board' :result command-result}` or
  `{:ok? false :result {:ok? false :error code :detail ...}}`.
  BOARD-SNAPSHOT-FN returns the Owner's current immutable board map.

  All eligibility checks and every `:begin-action`/`:advance-action` go
  through EXECUTE-BOARD-FN, so the Owner remains the only board writer."
  [execute-board-fn board-snapshot-fn]
  (when-not (ifn? execute-board-fn)
    (throw (IllegalArgumentException.
            "owner-board requires a synchronous execute-board function")))
  (when-not (ifn? board-snapshot-fn)
    (throw (IllegalArgumentException.
            "owner-board requires a board snapshot function")))
  (->OwnerBoard execute-board-fn board-snapshot-fn))

(defn swarm-kernel
  [board]
  (when (instance? SwarmBoard board)
    (kernel-of board)))
