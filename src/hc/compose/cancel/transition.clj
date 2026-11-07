(ns hc.compose.cancel.transition
  "Per-node cancellation and terminal transitions.

  Every public function is pure.  Cancellation only edits the immutable
  tree and returns advisory effects; it never interrupts a thread, touches a
  database commit, or disposes an execution resource.  An effect always says
  `:dispose? false` and `:thread-interrupt? false`.  The host may abort the
  named attempt, timer, write-gate wait, or managed process, then feed the
  final observation back through `accept-terminal` or `reconcile-unknown`.

  Unknown outcomes are preserved.  A running side-effecting call or action
  becomes `:outcome-unknown` (or `:uncertain`), not `:cancelled`.  A node
  that is already `:outcome-unknown` or `:uncertain` is never rewritten by
  a later cancellation or a late success.  Reconciliation needs explicit
  evidence; retry safety refuses an unknown outcome."
  (:require [clojure.string :as str]
            [hc.compose.cancel.model :as model]))

(def terminal-outcomes
  #{:succeeded :failed :cancelled :outcome-unknown})

(def reconciliation-outcomes
  #{:succeeded :failed :cancelled})

;; ---------------------------------------------------------------------------
;; Result values

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
  [state receipt]
  {:ok? true
   :state state
   :receipt receipt
   :effects []})

;; ---------------------------------------------------------------------------
;; Advisory effects

(defn- live-resource?
  "True only when the node explicitly records a live execution resource.

  A missing or nil flag is not evidence of a live resource.  The model fills
  the flag on construction, so this also keeps recovered or hand-built nodes
  from producing a phantom abort."
  [node]
  (true? (:resource-alive? node)))

(defn- abort-effect
  [node]
  (when (live-resource? node)
    (let [node-kind (:kind node)
          executor-kind (or (:executor-kind node)
                            (:resource-kind node))
          call-id (or (:call-id node)
                      (when (= :call node-kind) (:node-id node))
                      (when (= :attempt node-kind) (:parent-id node)))
          resource-id (or (:resource-id node)
                          (:process-id node)
                          (when (nil? call-id) (:node-id node)))]
      (cond-> {:effect :abort-resource
               :node-id (:node-id node)
               :node-kind node-kind
               ;; Keep `:kind` as the node kind for the existing Owner
               ;; `cancel-outbox` contract.  The executor kind, when the
               ;; node knows it, is explicit in `:executor-kind`.
               :kind node-kind
               :scope (case node-kind
                        :process :managed-process
                        :action :action-resource
                        :attempt-resource)
               :thread-interrupt? false
               :dispose? false}
        executor-kind (assoc :executor-kind executor-kind
                             :resource-kind executor-kind)
        call-id (assoc :call-id call-id)
        resource-id (assoc :resource-id resource-id)
        (:attempt-id node) (assoc :attempt-id (:attempt-id node))
        (:process-id node) (assoc :process-id (:process-id node))))))

(defn- model-stream-effect
  "Abort a live model stream.

  A model attempt is not a durable resource: it never keeps
  `:resource-alive?` set, so `finish-run` may close the run as soon as the
  walk records the cancellation.  It is still an execution resource owned by
  one `attempt-id` (an HTTP stream), and §5.3 requires the cancel signal to
  interrupt it.  Emitting the abort from the attempt node keeps one signal
  per attempt; the parent call node stays silent because it owns no stream
  of its own.

  Only a live attempt produces this effect.  A settled or unknown attempt is
  evidence, not work to signal, and a second cancel must not re-abort it."
  [node]
  (when (and (= :attempt (:kind node))
             (false? (:side-effecting? node))
             (contains? #{:dispatching :running} (:state node))
             (:attempt-id node))
    {:effect :abort-resource
     :node-id (:node-id node)
     :node-kind :attempt
     :kind :attempt
     :scope :model-stream
     :thread-interrupt? false
     :dispose? false
     :call-id (or (:call-id node) (:parent-id node))
     :attempt-id (:attempt-id node)}))

(defn- timer-effect
  [node]
  {:effect :cancel-timer
   :node-id (:node-id node)
   :kind (:kind node)
   :scope :timer
   :thread-interrupt? false
   :dispose? false})

(defn- release-effect
  [node resource]
  {:effect :release-resource
   :node-id (:node-id node)
   :kind (:kind node)
   :scope resource
   :thread-interrupt? false
   :dispose? false})

(defn- close-admission-effect
  [node]
  {:effect :close-admission
   :run-id (:node-id node)
   :scope :run
   :thread-interrupt? false
   :dispose? false})

;; ---------------------------------------------------------------------------
;; Cancellation

(defn- with-cancel-intent
  [node {:keys [reason root-reason origin command-id at]}]
  (cond-> (assoc node
                 :cancel-reason reason
                 :cancel-root-reason root-reason
                 :cancel-origin origin
                 :cancel-command-id command-id
                 :cancel-requested-at at)
    (not (model/terminal? node)) (assoc :cancel-requested? true)))

(defn- preserved-terminal
  "Preserve a fixed outcome while still terminating a live resource.

  A parked program can fix its call result and keep a live continuation.
  Cancellation must abort that resource without rewriting the result or the
  evidence.  A second cancellation is a no-op once the sweep was requested."
  [node ctx]
  (let [abort (abort-effect node)
        sweep? (and abort
                    (not (true? (:cancel-requested? node))))]
    {:node (if sweep?
             (assoc (with-cancel-intent node ctx) :cancel-requested? true)
             node)
     :effects (if sweep? [abort] [])
     :disposition :preserved}))

(defn- cancel-run
  [node {:keys [close-admission?] :as ctx}]
  (let [state (:state node)
        base (with-cancel-intent node ctx)]
    (cond
      (contains? #{:preparing :running} state)
      (let [closed? (= :closed (:admission node))
            node' (assoc base
                         :state :cancelling
                         :admission :closed
                         :cancellation {:command-id (:command-id ctx)
                                        :reason (:root-reason ctx)
                                        :origin (:origin ctx)
                                        :accepted-at (:at ctx)})]
        {:node node'
         :effects (cond-> []
                    (and close-admission? (not closed?))
                    (conj (close-admission-effect node)))
         :disposition :cancelled})

      (= :cancelling state)
      {:node (assoc base :admission :closed)
       :effects []
       :disposition :preserved}

      :else
      {:node node
       :effects []
       :disposition :preserved})))

(defn- cancel-call-like
  [node ctx allow-retry-wait?]
  (let [state (:state node)
        base (with-cancel-intent node ctx)
        ;; A side-effecting call owns a live resource; a model attempt owns
        ;; a live stream.  Both must receive one abort; neither flag may be
        ;; used as a quiescence gate here.
        abort (or (abort-effect node) (model-stream-effect node))
        live? (live-resource? node)]
    (cond
      (model/terminal? node)
      (preserved-terminal node ctx)

      (= :ready state)
      {:node (assoc base
                    ;; `:ready` normally owns no executor.  A recovered or
                    ;; hand-built node may still carry a live flag; never
                    ;; erase that evidence without a settlement.
                    :state :cancelled
                    :outcome :cancelled
                    :resource-alive? live?)
       :effects (if live? [abort] [])
       :disposition :cancelled}

      (and allow-retry-wait? (= :retry-wait state))
      {:node (assoc base
                    :state :cancelled
                    :outcome :cancelled
                    ;; The timer is withdrawn below.  If the node still
                    ;; carries a live executor flag, preserve it and abort;
                    ;; only a settlement may clear it.
                    :resource-alive? live?)
       :effects (cond-> [(timer-effect node)]
                  live? (conj abort))
       :disposition :cancelled}

      (contains? #{:dispatching :running} state)
      (let [outcome (if (true? (:side-effecting? node))
                      :outcome-unknown
                      :cancelled)]
        ;; The signal does not end the resource: an executor may still be
        ;; running.  `derive-resource-alive` marks `:dispatching`/`:running`
        ;; calls alive, and that flag is the only thing keeping `finish-run`
        ;; from closing the run.  Overwriting the state without re-deriving
        ;; it would report the resource as gone the moment it was signalled,
        ;; so the run could close while a Shell kept running.
        ;;
        ;; A model call/attempt is a stream, not a durable resource.  It
        ;; still receives the abort above, but it must not keep the run open:
        ;; no later settlement will ever clear a flag the walk invented.
        {:node (assoc base
                      :state outcome
                      :outcome outcome
                      :resource-alive?
                      (or (true? (:resource-alive? node))
                          (true? (:side-effecting? node))))
         :effects (if abort [abort] [])
         :disposition outcome})

      :else
      {:node node :effects [] :disposition :preserved})))

(defn- cancel-action
  [node ctx]
  (let [state (:state node)
        base (with-cancel-intent node ctx)
        abort (abort-effect node)
        live? (live-resource? node)]
    (case state
      :applying
      {:node (assoc base :cancel-after-apply? true)
       :effects []
       :disposition :deferred}

      :running
      (if (true? (:side-effecting? node))
        {:node (assoc base :state :uncertain :outcome :outcome-unknown)
         :effects (if abort [abort] [])
         :disposition :outcome-unknown}
        {:node (assoc base :state :finished :outcome :cancelled)
         :effects (if abort [abort] [])
         :disposition :cancelled})

      (:claimed :preparing)
      {:node (assoc base
                    :state :finished
                    :outcome :cancelled
                    :resource-alive? live?)
       :effects (if live? [abort] [])
       :disposition :cancelled}

      :waiting-gate
      {:node (assoc base
                    :state :finished
                    :outcome :cancelled
                    :resource-alive? live?)
       :effects (cond-> [(release-effect node :write-gate)]
                  live? (conj abort))
       :disposition :cancelled}

      :uncertain
      {:node base
       :effects (if (and abort
                         (not (true? (:cancel-requested? node))))
                  [abort]
                  [])
       :disposition :preserved}

      :finished
      (preserved-terminal node ctx)

      {:node node :effects [] :disposition :preserved})))

(defn- cancel-process
  [node ctx]
  (let [state (:state node)
        base (with-cancel-intent node ctx)
        abort (abort-effect node)]
    (case state
      (:starting :running)
      (let [unknown? (and (true? (:side-effecting? node))
                          (or (= :running state)
                              (true? (:started? node))))
            outcome (if unknown? :outcome-unknown :cancelled)]
        {:node (assoc base :state :stopping :outcome outcome)
         :effects (if abort [abort] [])
         :disposition outcome})

      :stopping
      {:node base
       :effects (if (and (live-resource? node)
                         (not (true? (:cancel-requested? node))))
                  [abort]
                  [])
       :disposition :preserved}

      :uncertain
      {:node base
       :effects (if (and abort
                         (not (true? (:cancel-requested? node))))
                  [abort]
                  [])
       :disposition :preserved}

      :exited
      (preserved-terminal node ctx)

      {:node node :effects [] :disposition :preserved})))

(defn cancel-node
  "Apply one cancellation signal to NODE.

  CTX carries `:reason` (this node's signal), `:root-reason`,
  `:origin`, `:command-id`, `:at`, and `:close-admission?`.

  Returns `{:node node' :effects [effect ...] :disposition d}` where the
  disposition is `:cancelled`, `:outcome-unknown`, `:deferred`, or
  `:preserved`."
  [node ctx]
  (case (:kind node)
    :run (cancel-run node ctx)
    :call (cancel-call-like node ctx true)
    :attempt (cancel-call-like node ctx false)
    :action (cancel-action node ctx)
    :process (cancel-process node ctx)
    {:node node :effects [] :disposition :preserved}))

;; ---------------------------------------------------------------------------
;; Terminal results and unknown-outcome reconciliation

(defn- node-outcome
  [node]
  (case (:kind node)
    (:call :attempt) (:state node)
    (:action :process) (or (:outcome node)
                           (when (model/uncertain? node)
                             :outcome-unknown))
    (:run) (:state node)
    nil))

(defn- append-diagnostic
  [state node diagnostic]
  (model/update-node state (:node-id node)
                     (fn [n]
                       (update n :late-results
                               (fnil #(vec (take-last 8 (conj % diagnostic)))
                                     [])))))

(defn- settlement-outcome?
  "True when OUTCOME is a settlement observation.

  `:outcome-unknown` is the absence of an executor result, not an
  observation that the executor ended.  It never proves a resource gone,
  whichever branch of `accept-terminal` receives it."
  [outcome]
  (contains? reconciliation-outcomes outcome))

(defn- clear-resource
  "A matched terminal observation ends the execution resource even when the
  business outcome was already fixed.  The outcome and evidence stay.

  Only a settlement observation (`:succeeded`, `:failed`, `:cancelled`)
  clears `:resource-alive?`.  An arriving `:outcome-unknown` is the absence
  of an executor result: it must stay live so a cancelled Shell that never
  reported keeps blocking `finish-run`.  A real later terminal from the same
  executor is the settlement observation that clears it and keeps the
  unknown as evidence; reconciliation with explicit evidence also clears it."
  [state node outcome]
  (if (and (true? (:resource-alive? node))
           (settlement-outcome? outcome))
    (model/assoc-node state (assoc node :resource-alive? false))
    state))

(defn- apply-terminal
  [state node outcome {:keys [error result evidence exit at]}]
  (let [node' (case (:kind node)
                (:call :attempt)
                (cond-> (assoc node
                               :state outcome
                               :outcome outcome
                               :finished-at at)
                  (some? result) (assoc :result result)
                  (some? error) (assoc :error error))

                :action
                (let [phase (if (= :outcome-unknown outcome)
                              :uncertain
                              :finished)]
                  (cond-> (assoc node
                                 :state phase
                                 :outcome outcome
                                 :finished-at at)
                    (:cancel-after-apply? node)
                    (assoc :cancel-applied-after-commit? true)

                    (some? result) (assoc :result result)
                    (some? error) (assoc :error error)))

                :process
                (let [state-name (if (= :outcome-unknown outcome)
                                   :uncertain
                                   :exited)]
                  (cond-> (assoc node
                                 :state state-name
                                 :outcome outcome
                                 :observed-at at)
                    (some? exit) (assoc :exit exit)))

                node)
        node' (if (some? evidence)
                (update node' :evidence (fnil conj []) evidence)
                node')
        ;; A settlement observation ends the execution resource; an arriving
        ;; `:outcome-unknown` never does.  It reports that no executor result
        ;; exists, so no one can prove the Shell or process stopped.  Keeping
        ;; the flag lets a real later terminal clear it (see `clear-resource`)
        ;; while `active-resources` still protects the live resource.
        node' (if (settlement-outcome? outcome)
                (assoc node' :resource-alive? false)
                node')]
    (model/assoc-node state node')))

(defn accept-terminal
  "Fix one terminal result for a call, attempt, action, or process.

  `:outcome` is `:succeeded`, `:failed`, `:cancelled`, or
  `:outcome-unknown`.  A duplicate terminal is idempotent.  A different
  terminal after one is fixed, a stale attempt id, or any result for an
  already-unknown outcome is stored as a late diagnostic and never rewrites
  the fixed outcome.

  Only a settlement outcome (`:succeeded`, `:failed`, `:cancelled`) clears
  `:resource-alive?`.  An `:outcome-unknown` reports the absence of an
  executor result, not the end of the resource, so it leaves the flag set
  and `finish-run` still waits.

  An `:applying` action is special: its current minimal commit must finish.
  A terminal result is accepted and `:cancel-applied-after-commit?` records
  that the cancellation takes effect after that result."
  [state {:keys [node-id attempt-id outcome error result evidence exit at]}]
  (let [node (model/node state node-id)
        expected-attempt-id (case (:kind node)
                              :call (:current-attempt-id node)
                              :attempt (or (:attempt-id node) (:node-id node))
                              nil)]
    (cond
      (nil? node)
      (failure state :NODE_NOT_FOUND
               "cancellation node does not exist"
               {:node-id node-id})

      (not (contains? terminal-outcomes outcome))
      (failure state :INVALID_TERMINAL
               "terminal outcome must be succeeded, failed, cancelled, or outcome-unknown"
               {:node-id node-id :outcome outcome})

      (= :run (:kind node))
      (failure state :RUN_TERMINAL_NOT_ACCEPTED
               "a run is closed by finish-run, not by accept-terminal"
               {:node-id node-id})

      (and attempt-id
           (contains? #{:call :attempt} (:kind node))
           (not= attempt-id expected-attempt-id))
      (let [state' (append-diagnostic
                    state node
                    {:kind :stale-attempt
                     :attempt-id attempt-id
                     :expected-attempt-id expected-attempt-id
                     :outcome outcome
                     :payload result
                     :error error
                     :at at})]
        {:ok? true
         :state state'
         :receipt {:status :late-diagnostic
                   :code :STALE_ATTEMPT
                   :node-id node-id
                   :attempt-id attempt-id}
         :effects []})

      (model/terminal? node)
      (if (= outcome (node-outcome node))
        (let [first-observation? (true? (:resource-alive? node))
              state (clear-resource state node outcome)
              state (if (and first-observation?
                             (or (some? result) (some? error)))
                      (append-diagnostic
                       state node
                       {:kind :late-terminal-observation
                        :outcome outcome
                        :payload result
                        :error error
                        :at at})
                      state)]
          (success state
                   {:status :duplicate
                    :node-id node-id
                    :outcome outcome}))
        (let [state (clear-resource state node outcome)
              state' (append-diagnostic
                      state node
                      {:kind :late-terminal
                       :outcome outcome
                       :fixed-outcome (node-outcome node)
                       :payload result
                       :error error
                       :at at})]
          {:ok? true
           :state state'
           :receipt {:status :late-diagnostic
                     :code :TERMINAL_ALREADY_FIXED
                     :node-id node-id
                     :fixed-outcome (node-outcome node)
                     :late-outcome outcome}
           :effects []}))

      ;; A cancel transition records `:outcome-unknown` precisely because no
      ;; executor result had arrived.  That is provisional evidence, not a
      ;; final outcome: if the real executor terminal arrives later it must
      ;; be able to replace the unknown and clear the resource.  Treating it
      ;; as permanently fixed leaves a live Shell flagged forever and the run
      ;; permanently `:cancelling`.  Keep the unknown as late evidence while
      ;; accepting the real outcome.
      (and (model/fixed-unknown? node)
           (= outcome (node-outcome node)))
      (let [first-observation? (true? (:resource-alive? node))
            state (clear-resource state node outcome)
            state (if (and first-observation?
                           (or (some? result) (some? error)))
                    (append-diagnostic
                     state node
                     {:kind :late-terminal-observation
                      :outcome outcome
                      :payload result
                      :error error
                      :at at})
                    state)]
        (success state
                 {:status :duplicate
                  :node-id node-id
                  :outcome outcome}))

      (and (model/uncertain-outcome? node)
           (not (model/fixed-unknown? node)))
      (let [state (apply-terminal state node outcome
                                  {:error error
                                   :result result
                                   :evidence evidence
                                   :exit exit
                                   :at at})
            state' (append-diagnostic
                    state node
                    {:kind :late-terminal
                     :outcome outcome
                     :fixed-outcome :outcome-unknown
                     :payload result
                     :error error
                     :at at})]
        (success state'
                 {:status :accepted
                  :node-id node-id
                  :outcome outcome
                  :at at}))

      (model/fixed-unknown? node)
      (if (= outcome (node-outcome node))
        (let [first-observation? (true? (:resource-alive? node))
              state (clear-resource state node outcome)
              state (if (and first-observation?
                             (or (some? result) (some? error)))
                      (append-diagnostic
                       state node
                       {:kind :late-terminal-observation
                        :outcome outcome
                        :payload result
                        :error error
                        :at at})
                      state)]
          (success state
                   {:status :duplicate
                    :node-id node-id
                    :outcome outcome}))
        (let [state (clear-resource state node outcome)
              state' (append-diagnostic
                      state node
                      {:kind :late-terminal
                       :outcome outcome
                       :fixed-outcome (node-outcome node)
                       :payload result
                       :error error
                       :at at})]
          {:ok? true
           :state state'
           :receipt {:status :late-diagnostic
                     :code :OUTCOME_UNKNOWN_PRESERVED
                     :node-id node-id
                     :fixed-outcome (node-outcome node)
                     :late-outcome outcome}
           :effects []}))

      :else
      (let [state' (apply-terminal state node outcome
                                   {:error error
                                    :result result
                                    :evidence evidence
                                    :exit exit
                                    :at at})]
        (success state' {:status :accepted
                         :node-id node-id
                         :outcome outcome
                         :at at})))))

(defn- valid-evidence?
  [evidence]
  (or (and (string? evidence) (not (str/blank? evidence)))
      (and (coll? evidence) (seq evidence))))

(defn reconcile-unknown
  "Resolve a node whose outcome is unknown with explicit evidence.

  `:outcome` must be `:succeeded`, `:failed`, or `:cancelled`.  Evidence is
  required: a non-blank string or a non-empty collection.  The transition
  records the evidence and never retries the external action."
  [state {:keys [node-id outcome evidence at]}]
  (let [node (model/node state node-id)]
    (cond
      (nil? node)
      (failure state :NODE_NOT_FOUND
               "cancellation node does not exist"
               {:node-id node-id})

      (= :run (:kind node))
      (failure state :RUN_NOT_RECONCILABLE
               "a run is closed by finish-run"
               {:node-id node-id})

      (true? (:reconciled? node))
      (if (= outcome (node-outcome node))
        (success state {:status :duplicate
                        :node-id node-id
                        :outcome (:outcome node)})
        (failure state :ALREADY_RECONCILED
                 "the unknown outcome was already reconciled"
                 {:node-id node-id
                  :reconciled-outcome (node-outcome node)
                  :requested-outcome outcome}))

      (not (model/uncertain-outcome? node))
      (failure state :NOT_OUTCOME_UNKNOWN
               "only an unknown outcome can be reconciled"
               {:node-id node-id
                :state (:state node)
                :outcome (:outcome node)})

      (not (contains? reconciliation-outcomes outcome))
      (failure state :INVALID_RECONCILIATION
               "reconciliation outcome must be succeeded, failed, or cancelled"
               {:node-id node-id :outcome outcome})

      (not (valid-evidence? evidence))
      (failure state :EVIDENCE_REQUIRED
               "reconciliation needs non-blank evidence"
               {:node-id node-id :evidence evidence})

      :else
      (let [reconciliation {:outcome outcome :evidence evidence :at at}
            node' (case (:kind node)
                    (:call :attempt)
                    (assoc node
                           :state outcome
                           :outcome outcome
                           :reconciled? true
                           :reconciliation reconciliation
                           :finished-at at)

                    :action
                    (assoc node
                           :state :finished
                           :outcome outcome
                           :reconciled? true
                           :reconciliation reconciliation
                           :finished-at at)

                    :process
                    (assoc node
                           :state :exited
                           :outcome outcome
                           :reconciled? true
                           :reconciliation reconciliation
                           :observed-at at)

                    node)
            node' (assoc node' :resource-alive? false)
            node' (update node' :evidence (fnil conj []) evidence)]
        (success (model/assoc-node state node')
                 {:status :reconciled
                  :node-id node-id
                  :outcome outcome
                  :at at})))))

(defn retry-safety
  "Read-only retry decision for NODE-ID.

  An unknown outcome is never safe to retry.  A failed result is retryable
  only when its error says `:retryable? true`; a side-effecting node must
  also say `:safe-to-retry? true`.  A cancellation is never a retryable
  upstream fault."
  [state node-id]
  (let [node (model/node state node-id)
        outcome (when node (node-outcome node))
        error (when node
                (or (:error node)
                    (get-in node [:result :error])))]
    (cond
      (nil? node)
      {:allowed? false :code :NODE_NOT_FOUND
       :message "cancellation node does not exist"}

      (model/uncertain-outcome? node)
      {:allowed? false :code :OUTCOME_UNKNOWN
       :message "the external outcome is unknown; reconcile before retrying"}

      (= :cancelled outcome)
      {:allowed? false :code :CANCELLED
       :message "an operator or parent cancellation is not a retryable fault"}

      (= :succeeded outcome)
      {:allowed? false :code :ALREADY_SUCCEEDED
       :message "the work already succeeded"}

      (= :failed outcome)
      (let [retryable? (true? (:retryable? error))
            safe? (if (true? (:side-effecting? node))
                    (true? (:safe-to-retry? node))
                    true)]
        (cond
          (and retryable? safe?)
          {:allowed? true :code :RETRYABLE
           :message "the failure is retryable and no duplicate side effect is expected"}

          retryable?
          {:allowed? false :code :SIDE_EFFECTS_NOT_SAFE
           :message "the failure is retryable but the action may have produced side effects"}

          :else
          {:allowed? false :code :NOT_RETRYABLE
           :message "the failure is not classified as retryable"}))

      :else
      {:allowed? false :code :NOT_FAILED
       :message "only a failed or unknown attempt may be considered for retry"})))

(defn- settle-failed-resources
  "Settle the local resources of a failed branch.

  A failed step may still own a retry timer, a write-gate wait, or a live
  executor.  The caller that records the failure must not leave those
  resources invisible to `finish-run`.  This is deliberately separate from
  `independent-failure`: an ordinary independent tool failure is a data
  value and must not cancel siblings, while a required-step failure has
  already decided that the group cannot succeed."
  [node]
  (let [live? (live-resource? node)
        state (:state node)]
    (cond
      (= :retry-wait state)
      {:resource-alive? false
       :effects [(timer-effect node)]}

      (and (= :action (:kind node)) (= :waiting-gate state))
      {:resource-alive? live?
       :effects (cond-> [(release-effect node :write-gate)]
                  live? (conj (abort-effect node)))}

      live?
      {:resource-alive? true
       :effects [(abort-effect node)]}

      :else
      {:resource-alive? false
       :effects []})))

(defn independent-failure
  "Record one independent branch failure as a data value.

  Only NODE-ID changes.  Siblings are untouched, no cancellation effect is
  produced, and the failure is stored as
  `{:result/status :failed :result/error ...}`.  This is the
  `run-independent-tools` rule from AGENTS.md §5.3.

  `:settle-resources?` is used by `accept-required-step-failure`.  It
  withdraws the failed branch's retry timer or write-gate wait and aborts a
  live executor, while still leaving the node's own outcome fixed as
  `:failed`.  A live executor keeps `:resource-alive? true` until a real
  settlement arrives."
  [state {:keys [node-id error at settle-resources?]}]
  (let [node (model/node state node-id)]
    (cond
      (nil? node)
      (failure state :NODE_NOT_FOUND
               "cancellation node does not exist"
               {:node-id node-id})

      (not (contains? #{:call :attempt :action} (:kind node)))
      (failure state :NODE_KIND_NOT_SUPPORTED
               "independent failure applies to a call, attempt, or action"
               {:node-id node-id :kind (:kind node)})

      (not (or (nil? error) (map? error)))
      (failure state :INVALID_ERROR
               "independent failure error must be a map"
               {:node-id node-id :error error})

      (and (= :action (:kind node))
           (= :applying (:state node)))
      (failure state :ACTION_IN_FLIGHT
               "an applying action must finish its current commit first"
               {:node-id node-id})

      (or (model/terminal? node)
          (model/uncertain-outcome? node))
      (let [settled (when (and settle-resources?
                               (live-resource? node))
                      (settle-failed-resources node))
            node' (if settled
                    (assoc node :cancel-requested? true)
                    node)]
        {:ok? true
         :state (model/assoc-node state node')
         :receipt {:status :preserved
                   :node-id node-id
                   :outcome (node-outcome node)
                   :siblings-untouched? true
                   :settled? (boolean settled)}
         :effects (vec (:effects settled))})

      :else
      (let [error (or error {})
            result {:status :failed :error error}
            settled (when settle-resources?
                      (settle-failed-resources node))
            node' (case (:kind node)
                    (:call :attempt)
                    (cond-> (assoc node
                                   :state :failed
                                   :outcome :failed
                                   :error error
                                   :result result
                                   :finished-at at)
                      settled (assoc :resource-alive?
                                     (:resource-alive? settled)))

                    :action
                    (cond-> (assoc node
                                   :state :finished
                                   :outcome :failed
                                   :error error
                                   :result result
                                   :finished-at at)
                      settled (assoc :resource-alive?
                                     (:resource-alive? settled))))]
        {:ok? true
         :state (model/assoc-node state node')
         :receipt {:status :failed
                   :node-id node-id
                   :independent? true
                   :siblings-untouched? true
                   :settled? (boolean settle-resources?)
                   :at at}
         :effects (vec (:effects settled))}))))
