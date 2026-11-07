(ns hc.compose.owner.guards
  "Domain guard catalog for one Owner (AGENTS.md §5.2).

  Every guard is pure.  `:enabled?` reads committed state and returns a
  work description.  `:prepare` returns one state transition plus, when
  external work starts, a claim and dispatch effects.  The scheduler commits
  the state before the Owner dispatches anything.

  The catalog composes the existing modules instead of reimplementing them:

    * `hc.compose.cancel` owns cancellation propagation and terminal
      acceptance;
    * `hc.compose.reports.*` owns report delivery and acknowledgement;
    * `hc.compose.retry.*` owns retry budgets, delays, and effect safety;
    * `hc.compose.nudge.policy` owns the unattended-stop decision;
    * `hc.core.relay` owns review ledgers, tenures, and the relay barrier;
    * `hc.core.swarm-stm` owns board transitions and responsibility.

  Priority groups follow §5.2: P0 control and cancel, P1 closure and
  reports, P2 retry, P3 new work and Nudge."
  (:require [clojure.string :as str]
            [hc.compose.cancel :as cancel]
            [hc.compose.cancel.reason :as reason]
            [hc.compose.mission :as mission]
            [hc.compose.mission.actors :as actors]
            [hc.compose.mission.surface :as surface]
            [hc.compose.nudge.policy :as nudge]
            [hc.compose.reports.acceptance :as acceptance]
            [hc.compose.owner.state :as state]
            [hc.compose.reports.delivery :as delivery]
            [hc.compose.retry.planner :as retry]
            [hc.compose.guards.scheduler :as scheduler]
            [hc.core.relay :as relay]
            [hc.core.relay.barrier :as barrier]
            [hc.core.scope.runtime :as scope-runtime]
            [hc.core.swarm-stm.board :as board]
            [hc.core.ufss :as ufss]))

;; ---------------------------------------------------------------------------
;; State helpers

(defn- render-ufss
  "Render structured system data as EDN+UFSS.

  A value that cannot be printed becomes a small printable refusal.  The
  model must never receive `pr-str` escape noise for a structured receipt."
  [value]
  (try
    (ufss/write-str value)
    (catch Throwable error
      (ufss/write-str
       {:status :UNSUPPORTED_OPERATION
        :error :UNSUPPORTED_OPERATION
        :message "Structured value could not be rendered as EDN+UFSS"
        :class (.getName (class error))}))))

(defn- render-tool-result
  "Render a tool result with the injected program renderer when present.

  The default is EDN+UFSS.  A renderer failure falls back to the printable
  UFSS refusal, never to `pr-str` escape noise."
  [ctx value]
  (let [renderer (:tool-result-renderer ctx)]
    (if renderer
      (try
        (renderer value)
        (catch Throwable _
          (render-ufss value)))
      (render-ufss value))))

;; P0: operator cancellation

(defn- cancel-outbox
  "Translate cancellation-tree effects into outbox entries.

  Every entry carries the signal reason it was produced for, so a late-node
  cancel never reports an operator stop that did not happen."
  ([state effects]
   (cancel-outbox state effects :user-cancel))
  ([state effects reason]
   (reduce
    (fn [out effect]
      (case (:effect effect)
        :abort-resource
        (let [;; New cancel effects carry the node kind separately from the
              ;; executor kind.  Old effects only have `:kind` (the node
              ;; kind), so both paths must remain readable.
              node-kind (or (:node-kind effect) (:kind effect))
              call-id (or (:call-id effect)
                          (when (= :call node-kind)
                            (:node-id effect)))
              executor-kind (or (:executor-kind effect)
                                (:resource-kind effect)
                                (when call-id
                                  (get-in state [:calls call-id :kind]))
                                node-kind)]
          (cond-> out
            (or (:attempt-id effect) call-id)
            (conj (cond-> {:type :cancel/resource
                           :node-id (:node-id effect)
                           :node-kind node-kind
                           :kind executor-kind
                           :resource-kind executor-kind
                           :reason reason}
                    (:attempt-id effect)
                    (assoc :attempt-id (:attempt-id effect))
                    (:resource-id effect)
                    (assoc :resource-id (:resource-id effect))
                    call-id
                    (assoc :call-id call-id)))))

        :cancel-timer
        ;; `drain-outbox!` withdraws a retry timer by call id, not by the
        ;; attempt id that `dispatch/start!` schedules it under.  The call's
        ;; current attempt is the resource the timer was armed for; the tree
        ;; only knows the node id.
        (conj out (cond-> {:type :cancel/timer
                           :call-id (:node-id effect)
                           :reason reason}
                    (get-in state [:calls (:node-id effect) :attempt-id])
                    (assoc :attempt-id
                           (get-in state [:calls (:node-id effect)
                                         :attempt-id]))))

        :release-resource
        (conj out {:type :release/resource
                   :node-id (:node-id effect)
                   :scope (:scope effect)})

        :close-admission
        (conj out {:type :admission/closed
                   :run-id (:run-id effect)})

        out))
    []
    effects)))

(defn- sync-calls-from-cancel
  [state now]
  (reduce-kv
   (fn [state call-id call]
     (let [node (get-in state [:cancel :nodes call-id])
           node-state (:state node)
           ;; A late terminal for the *attempt* must not rewrite the call's
           ;; own phase: only the call node (`:kind :call`) owns that state.
           call-terminal?
           (and (= :call (:kind node))
                (contains? #{:succeeded :failed :cancelled :outcome-unknown}
                           node-state))
           attempt-node (when-let [attempt-id (:attempt-id call)]
                          (get-in state
                                  [:cancel :nodes
                                   (state/attempt-node-id call-id attempt-id)]))
           ;; The current attempt node is the execution resource owner.  When
           ;; the cancel walk signals it, the call record must follow even if
           ;; the call's own node was already terminal/preserved; otherwise a
           ;; live shell keeps `:attempt-id` and `dispatch-effects!` treats
           ;; its abort as a stale claim.  An attempt node that already
           ;; reached a terminal state is settled evidence: clear the call's
           ;; attempt id in that case and only in that case.
           attempt-terminal? (and (= :attempt (:kind attempt-node))
                                  (contains? #{:succeeded :failed :cancelled
                                               :outcome-unknown}
                                             (:state attempt-node)))
           attempt-signalled? (and (= :attempt (:kind attempt-node))
                                   (= :cancelling (:state attempt-node)))
           settled-attempt? (and node attempt-terminal?)]
       (if (and node
                (or call-terminal? settled-attempt?))
         (let [state
               (cond
                 attempt-signalled?
                 (-> state
                     (assoc-in [:calls call-id :state]
                               (if (true? (:side-effecting? attempt-node))
                                 :outcome-unknown
                                 :cancelled))
                     (assoc-in [:calls call-id :outcome]
                               (if (true? (:side-effecting? attempt-node))
                                 :outcome-unknown
                                 :cancelled))
                     (assoc-in [:calls call-id :resource-alive?] true)
                     (assoc-in [:calls call-id :retry :phase] :cancelled)
                     (state/finish-runs-for-call call-id :cancelled now
                                                 (:cancel-reason attempt-node)))

                 call-terminal?
                 (let [state
                       (-> state
                           (assoc-in [:calls call-id]
                                     (-> call
                                         (assoc :state node-state
                                                :outcome (:outcome node)
                                                :attempt-id (:attempt-id call))
                                         (assoc-in [:retry :phase] node-state)))
                           (state/finish-runs-for-call call-id node-state now
                                                       (:cancel-reason node)))
                       ;; A call node fixed by the cancel walk must not keep a
                       ;; stale live flag.  `accept-resources` is the only
                       ;; quiescence gate, so a terminal call that still
                       ;; claims a resource blocks `finish-run` forever while
                       ;; nothing is left to observe.  A side-effecting call
                       ;; keeps the flag only while its attempt is genuinely
                       ;; live.
                       ;; A call the cancellation walk fixed as `:cancelled`
                       ;; cannot own a live resource: that disposition is only
                       ;; written for work that had not started.  Any other
                       ;; live outcome (`:outcome-unknown`, `:uncertain`) is
                       ;; kept exactly as it is -- a shell that was signalled
                       ;; but has not exited still owns its process.
                       canceled-without-start? (= :cancelled node-state)
                       node-live? (if canceled-without-start?
                                    false
                                    (true? (:resource-alive? node)))]
                   (assoc-in state [:cancel :nodes call-id :resource-alive?]
                             node-live?))
                 :else state)]
           (if settled-attempt?
             (assoc-in state [:calls call-id :attempt-id] nil)
             state))
         state)))
   state
   (:calls state)))

(defn operator-cancel-guard
  "P0: consume one pending operator cancel.

  The guard pops the pending entry even when the cancellation is refused, so
  a bad signal cannot spin.  A refusal becomes a diagnostic."
  []
  (scheduler/guard
   :accept-operator-cancel
   :p0-control
   (fn [state _]
     (first (:pending-cancels state)))
   (fn [state ctx work _]
     (let [now (:now ctx)
           state (update state :pending-cancels #(vec (rest %)))
           run-id (get-in state [:cancel :active-run-id])
           resource-nodes
           (fn [call-id]
             (->> (vals (get-in state [:cancel :nodes]))
                  (filter (fn [node]
                            (and (true? (:resource-alive? node))
                                 (or (= call-id (:node-id node))
                                     (= call-id (:parent-id node))
                                     (= call-id (:call-id node))))))
                  vec))
           live-call-ids
           (->> (:calls state)
                (filter (fn [call]
                          (let [call-id (:call-id call)
                                node (get-in state [:cancel :nodes call-id])]
                            (and node
                                 (= run-id (:parent-id node))
                                 (or (contains? #{:ready :dispatching :running
                                                  :retry-wait}
                                                (:state call))
                                     (seq (resource-nodes call-id)))))))
                (mapv :call-id))
           settled-effects
           (for [call-id live-call-ids
                 :let [call (state/call state call-id)]
                 :when (not (contains? #{:ready :dispatching :running
                                         :retry-wait}
                                       (:state call)))
                 node (resource-nodes call-id)]
             {:effect :abort-resource
              :node-id (:node-id node)
              :kind (:kind node)
              :call-id call-id
              :attempt-id (:attempt-id node)})
           ;; A second operator signal for a run that has ALREADY closed is
           ;; the only case that needs a re-emitted abort.  The first stop
           ;; walks the subtree and publishes its own effects; repeating them
           ;; there posts a second `:cancelled` terminal for the same attempt
           ;; (`:LATE_TERMINAL_AFTER_CANCEL`) and races `finish-run`.  After a
           ;; restart the outbox is gone, so the original abort no longer
           ;; exists and a new stop must re-signal, but only for call and
           ;; attempt nodes that are settled and hold no live resource.
           ;;
           ;; Model attempts are streams, not resources: they are excluded in
           ;; both cases, because `accept-operator-cancel` already produced
           ;; their abort and re-aborting them duplicates a stream cancel.
           reopen? (= :cancelled (:state (get-in state [:cancel :nodes
                                                         run-id])))
           unknown-effects
           (when reopen?
             (for [call-id (->> (:calls state)
                                (filter (fn [[_ call]]
                                          (let [node (get-in state
                                                             [:cancel :nodes
                                                              (:call-id call)])]
                                            (and (= :outcome-unknown
                                                    (:state call))
                                                 (not= :model (:kind call))
                                                 node
                                                 (= run-id (:parent-id node))
                                                 (not (true?
                                                       (:resource-alive?
                                                        node)))))))
                                (map key))
                   :let [call (state/call state call-id)]
                   node (into (when-let [n (get-in state [:cancel :nodes
                                                           call-id])]
                                [n])
                              (filter (fn [n]
                                        (and (= call-id (:call-id n))
                                             (not= :call (:kind n))
                                             (not (true?
                                                   (:resource-alive? n)))))
                                      (vals (get-in state
                                                    [:cancel :nodes]))))
                   :when (or (= :call (:kind node))
                             (= call-id (:parent-id node)))]
               {:effect :abort-resource
                :node-id (:node-id node)
                :kind (:kind node)
                :call-id call-id
                :attempt-id (or (:attempt-id node) (:attempt-id call))}))
           run-result (cancel/accept-operator-cancel
                       (:cancel state)
                       {:run-id run-id
                        :command-id (:command-id work)
                        :origin (:origin work)
                        :reason :user-cancel
                        :at now})
           state (if (:ok? run-result)
                   (assoc state :cancel (:state run-result))
                   state)
           ;; `accept-operator-cancel` already walked the whole run subtree,
           ;; including every call and attempt under it.  A second walk over
           ;; the same nodes finds them `:cancelling`/`:cancelled`, returns
           ;; `:preserved` with no effects, and would publish nothing useful.
           ;; Only nodes whose terminal observation has not arrived yet need
           ;; an extra abort: `settled-effects`, plus `unknown-effects` when a
           ;; second stop hits an already closed run.
           ;; `accept-operator-cancel` walks the run subtree and returns the
           ;; abort/timer effects for the live nodes it signalled.  Those
           ;; effects are `:effect`-shaped (`:abort-resource`,
           ;; `:cancel-timer`, ...), not executable `:kind` effects.  They
           ;; must be numbered into the outbox in this same commit; handing
           ;; them to `:dispatch` makes `dispatch-effects!` treat them as a
           ;; stale claim and drop the abort, which strands a live Shell and
           ;; the whole cancellation domain.
           effects (vec (concat (:effects run-result)
                                (when reopen? unknown-effects)
                                settled-effects))
           effects (vec (remove (fn [effect]
                                  (and (= :cancel-timer (:effect effect))
                                       (some (fn [other]
                                               (and (= :cancel-timer
                                                       (:effect other))
                                                    (= (:node-id effect)
                                                       (:node-id other))))
                                             (:effects run-result))))
                                effects))]
       (if (:ok? run-result)
         (let [state (sync-calls-from-cancel state now)
               ;; An operator cancel ends the current responsibility streak.
               ;; The next turn starts with a clean refusal count; otherwise
               ;; a blocked actor would stay fenced after the operator
               ;; explicitly stopped the run and asked to continue.
               state (assoc state
                            :tool-refusals {}
                            :actor-refusal-reported #{})
               state (state/append-outbox
                      state
                      (cancel-outbox state effects :user-cancel))
               ;; Nothing live was left to signal, or the walk ended the
               ;; last resource itself, so this very commit can close the
               ;; domain.  Without this the close waits for another call
               ;; terminal, and a cancellation whose last resource already
               ;; settled never produces one: the run would stay
               ;; `:cancelling` with nothing left to wait for.
               state (if (cancel/cancellation-quiescent? (:cancel state)
                                                         run-id)
                       (let [finished (cancel/finish-run (:cancel state)
                                                         {:run-id run-id
                                                          :at now})]
                         (if (:ok? finished)
                           (assoc state :cancel (:state finished))
                           state))
                       state)]
           {:state state})
         {:state (state/append-diagnostic
                  state
                  (merge {:code :OPERATOR_CANCEL_REJECTED
                          :origin (:origin work)
                          :run-id run-id
                          :at now
                          :command-id (:command-id work)}
                         (select-keys (:error run-result)
                                      [:code :message :details])))})))))

;; ---------------------------------------------------------------------------
;; P1: terminal acceptance

(defn- scope-after-terminal
  [state call outcome payload error now]
  (let [tool? (contains? #{:tool :shell :workspace :devops} (:kind call))
        state (if (and tool? (contains? #{:succeeded :failed} outcome))
                (state/settle-tool-scope state call
                                         (or payload error) now)
                state)
        state (if tool? (state/progress! state) state)
        turn-id (:scope-turn-id call)
        model (when turn-id (state/call state turn-id))
        model-terminal? (and model
                             (= :model (:kind model))
                             (contains? #{:succeeded :failed :cancelled
                                          :outcome-unknown}
                                        (:state model)))
        close? (cond
                 (= :model (:kind call))
                 (not (state/live-scope-children? state (:call-id call)))

                 (and tool? turn-id model-terminal?)
                 (not (state/live-scope-children? state turn-id))

                 :else false)]
    (if close?
      (state/close-model-turn-scope state (or turn-id (:call-id call)) now)
      state)))

(defn- set-model-turn
  [state call outcome error payload now]
  (if (= :model (:kind call))
    (let [degraded? (or (true? (:search-degraded? payload))
                        (true? (:search-failed? payload))
                        (true? (:search-degraded? error))
                        (true? (:search-failed? error)))
          snapshot (:snapshot (:request call))
          model-binding (cond-> {}
                          (some? (:connection snapshot))
                          (assoc :connection (:connection snapshot))
                          (some? (:model snapshot))
                          (assoc :model (:model snapshot)))
          failure (when (contains? #{:failed :outcome-unknown} outcome)
                    (or error (:last-error call)))
          turn {:role (:role call)
                :agent-id (:agent-id call)
                :tenure-id (:tenure-id call)
                :call-id (:call-id call)
                ;; A Nudge continues the same turn.  Keep the model identity
                ;; frozen into the turn request so a later session selection
                ;; cannot reroute the continuation.
                :model-binding model-binding
                :failure failure
                :failure-outcome outcome
                ;; The cause that reached the Owner, when the call has no
                ;; raw error of its own.  A required search refusal raised
                ;; before the request left is committed by P1 and reported by
                ;; P2 as an attempt failure, so the call's terminal error is
                ;; the retry plan's shape; the original cause stays on the
                ;; call's cancellation node.  The Nudge needs that cause, not
                ;; a nil.
                :failure-facts (or error
                                   (get-in state [:cancel :nodes
                                                  (:call-id call)
                                                  :error]))
                ;; A permanent category is already terminal evidence for the
                ;; Nudge.  The transient and retry-exhausted cases stay
                ;; false: their budget is the bounded retry the policy must
                ;; not pre-empt.
                :permanent-failure? (and failure
                                         (= :permanent (:category failure)))
                :search-degraded? degraded?
                :search-status (:search-status payload)
                :ended? true
                :at now}
          ;; An ended turn that is still present is one `settle-turn` has not
          ;; answered: every committed answer clears it.  Overwriting it with
          ;; a later terminal would drop the stop that still owes a decision
          ;; (its failure, tenure, and agent) and leave the newer turn stale
          ;; instead.  Keep the pending turn; its own decision runs as soon
          ;; as its agent is free.
          pending? (true? (:ended? (:turn state)))]
      (if pending?
        state
        (assoc state :turn turn)))
    state))

(defn accept-call-terminal-guard
  "P1: accept one dispatcher terminal message.

  A stale attempt id is diagnostic only.  A failure leaves the call running
  until P2 decides whether a retry is allowed; that keeps the four layers
  (`call`, `attempt`, `run`, `action`) separate."
  []
  (scheduler/guard
   :accept-call-terminal
   :p1-closure
   (fn [state _]
     (first (:terminals state)))
   (fn [state ctx work _]
     (let [{:keys [call-id attempt-id status payload error]} work
           state (update state :terminals #(vec (rest %)))
           current (state/call state call-id)
           same-attempt? (and current
                              (= (state/normalize-attempt-id attempt-id)
                                 (state/normalize-attempt-id
                                  (:attempt-id current))))
           attempt-id (if same-attempt?
                        (:attempt-id current)
                        attempt-id)
           now (:now ctx)]
       (cond
         (nil? current)
         {:state (state/append-diagnostic state
                                    {:code :UNKNOWN_CALL
                                     :call-id call-id
                                     :at now})}

         (state/call-node-terminal? state call-id)
         (let [outcome (state/normalize-outcome status)
               call-node-id call-id
               ;; The call node is already terminal (the cancel walk fixed
               ;; it).  The terminal still belongs to the live attempt
               ;; recorded on the call, not to a stale id that a second
               ;; attempt or a restart may have left in the queue.
               live-attempt-id (state/normalize-attempt-id
                                (:attempt-id current))
               terminal-attempt-id (state/normalize-attempt-id attempt-id)
               ;; Only the current attempt may clear its own resource.  A
               ;; terminal for an older attempt is evidence, never authority
               ;; over the live attempt's `:resource-alive?`.
               ;; The cancellation walk clears the call's `:attempt-id`, so
               ;; `live-attempt-id` can be nil when the terminal arrives.
               ;; The terminal still names the attempt that owned the
               ;; resource: use its own id then.  Without that fallback the
               ;; attempt node keeps `:resource-alive? true`, the run can
               ;; never quiesce, and `finish-run` refuses forever.
               resolved-attempt-id (or live-attempt-id terminal-attempt-id)
               attempt-node-id (when (and resolved-attempt-id
                                          (or (nil? live-attempt-id)
                                              (nil? terminal-attempt-id)
                                              (= terminal-attempt-id
                                                 live-attempt-id)))
                                 (state/attempt-node-id call-id
                                                        resolved-attempt-id))
               attempt-id (or live-attempt-id terminal-attempt-id)
               ;; A terminal that arrives after the cancellation walk fixed
               ;; the outcome is still the observation that ends the
               ;; execution resource.  Keep the fixed outcome and the
               ;; evidence, and clear `:resource-alive?` on both nodes
               ;; directly: without that, `active-resources` never empties
               ;; and `finish-run` refuses `:RESOURCES_NOT_QUIESCENT`
               ;; forever.
               clear (fn [state node-id]
                       (if (get-in state [:cancel :nodes node-id])
                         (assoc-in state
                                   [:cancel :nodes node-id :resource-alive?]
                                   false)
                         state))
               state (state/accept-node state call-node-id nil outcome
                                        payload error now)
               ;; `:outcome-unknown` is not an observation that the
               ;; executor ended; keep the flag so `active-resources` still
               ;; protects a live Shell/process until a real terminal or an
               ;; explicit hook terminal arrives.
               state (if (and attempt-node-id
                              (not= :outcome-unknown outcome))
                       (-> (state/accept-node state attempt-node-id
                                              attempt-id outcome payload error
                                              now)
                           (clear attempt-node-id))
                       state)
               ;; The call node owns no live resource; clear it only after
               ;; the matching live attempt was observed (or when no live
               ;; attempt id exists at all).
               state (if (and (not= :outcome-unknown outcome)
                              (or (nil? live-attempt-id)
                                  attempt-node-id))
                       (clear state call-node-id)
                       state)
               entry {:status status
                      :attempt-id attempt-id
                      :payload payload
                      :error error
                      :at now}
               current (update current :late-results
                               (fnil #(vec (take-last 8 (conj % entry))) []))
               state (state/put-call state current)]
           {:state (state/append-diagnostic
                    state
                    (merge {:code :LATE_TERMINAL_AFTER_CANCEL
                            :call-id call-id
                            :attempt-id attempt-id
                            :status status
                            :at now}
                           entry))})

         (not same-attempt?)
         {:state (state/append-diagnostic state
                                    {:code :STALE_ATTEMPT
                                     :call-id call-id
                                     :attempt-id attempt-id
                                     :current-attempt-id (:attempt-id current)
                                     :at now})}

         :else
         (let [outcome (state/normalize-outcome status)
               node-id (state/attempt-node-id call-id attempt-id)
               ;; Only a settlement observation ends an execution resource.
               ;; `:outcome-unknown` reports that no executor result exists,
               ;; so it proves nothing about a live Shell, workspace action,
               ;; or `:applying` program: the flag must stay set and
               ;; `finish-run` must keep refusing until a real terminal
               ;; arrives.  `cancel/transition` already enforces this, and
               ;; this branch must not defeat it by clearing the flag here.
               ;; A later settlement for the same node clears it through
               ;; `accept-node`, and the duplicate branch of
               ;; `accept-terminal` re-observes an unchanged outcome only
               ;; after the flag is already false.
               settlement? (contains? #{:succeeded :failed :cancelled}
                                      outcome)
               state (-> (state/accept-node state node-id attempt-id outcome
                                            payload error now)
                         (cond-> (and settlement?
                                      (get-in state [:cancel :nodes node-id]))
                           (assoc-in [:cancel :nodes node-id :resource-alive?]
                                     false)))
               ;; The call node records the same observation under the same
               ;; rule.  `cancel/accept-terminal` clears its flag only for a
               ;; settlement outcome, so an `:outcome-unknown` leaves the
               ;; call live exactly like the attempt above.  A settlement
               ;; clears it in `terminal-call` below; leaving the call's
               ;; `:attempt-id` in place for an unknown outcome keeps it
               ;; eligible for the late settlement that ends it.
               current (if settlement?
                         (assoc current :attempt-id nil :finished-at now)
                         current)]
           (let [state (assoc state :cancel
                              (:state (cancel/accept-terminal
                                       (:cancel state)
                                       {:node-id call-id
                                        :outcome outcome
                                        :result payload
                                        :error error
                                        :at now})))]
           (case outcome
             :succeeded
             {:state (-> (state/terminal-call state current :succeeded payload nil now)
                         (state/finish-run-for-attempt call-id attempt-id
                                                       :succeeded payload nil now)
                         (scope-after-terminal current :succeeded payload nil now)
                         (set-model-turn current :succeeded nil payload now))}

             :failed
             (if (= :devops (:kind current))
               (let [unknown-error
                     (or error
                         {:kind :devops-outcome-unknown
                          :message "devops worker failed; its external outcome is unknown"})]
                 {:state (-> (state/terminal-call state current
                                                  :outcome-unknown payload
                                                  unknown-error now)
                             (state/finish-run-for-attempt call-id attempt-id
                                                           :outcome-unknown
                                                           payload unknown-error now)
                             (scope-after-terminal current :outcome-unknown
                                                   payload unknown-error now)
                             (set-model-turn current :failed error payload now))})
               ;; A retryable failure leaves the call running so P2 can plan
               ;; the retry; the attempt node is already terminal.
               {:state (-> (state/put-call state
                                           (-> current
                                               (assoc :state :running
                                                      :last-error
                                                      (or error
                                                          {:kind :unknown
                                                           :message "attempt failed"})
                                                      :retry-planned? false
                                                      :result payload)
                                               (assoc-in [:retry :phase] :running)))
                           (state/finish-run-for-attempt call-id attempt-id
                                                         :failed payload error now)
                           (scope-after-terminal current :failed payload error now)
                           (set-model-turn current :failed error payload now))})

             (:cancelled :outcome-unknown)
             {:state (-> (state/terminal-call state current outcome payload error now)
                         (state/finish-run-for-attempt call-id attempt-id
                                                       outcome payload error now)
                         (scope-after-terminal current outcome payload error now)
                         (set-model-turn current outcome error payload now))}

             {:state (state/append-diagnostic state
                                        {:code :INVALID_TERMINAL
                                         :call-id call-id
                                         :status status
                                         :at now})}))))))))

(defn- late-node-target
  "One live node under the cancelling run that still needs a cancel signal.

  Only a `:retry-wait` call qualifies: it owns a scheduled timer that no
  other guard withdraws.  Every other live state was signalled by the
  operator walk itself (`:outcome-unknown`, `:uncertain`, `:stopping`), or
  by a dispatch that cannot start under a closed admission.  The operator
  walk clears `:cancel-requested?` on nothing, so this predicate cannot
  spin: after the signal the node leaves `:retry-wait`."
  [state run-id]
  (let [run (get-in state [:cancel :nodes run-id])]
    (some (fn [node-id]
            (let [node (get-in state [:cancel :nodes node-id])]
              (when (not (true? (:cancel-requested? node)))
                {:node-id node-id
                 :reason (or (:cancel-root-reason run) :parent-terminated)
                 :origin (:cancel-origin run)
                 :operator? (reason/operator-origin? (:cancel-origin run))})))
          (cancel/actionable-resources (:cancel state) run-id))))

(defn cancel-late-nodes-guard
  "P0: extend a stop to a resource that the operator walk left unsignalled.

  Cancellation is a level, not an edge: while the run is `:cancelling`, any
  live resource under it must carry the cancel signal, even if it was
  committed by an event that raced the operator command.  Without this
  guard `finish-run` can never reach quiescence and the cancelled run blocks
  the successor run and every queued user turn forever.

  The guard is not time-based.  A grace period would only end when some
  unrelated message woke the Owner, and a dispatched timer would add a
  second, unowned owner of the same decision.  The transition it performs is
  idempotent and complete on its own: the node changes state, and its abort
  or timer-cancel effect goes to the outbox in the same commit.  A
  `:dispatching` or `:running` call keeps `:resource-alive? true`, so the
  node stays in `active-resources` and `finish-run` still waits for the real
  terminal; the later P1 observation clears the flag.  Nothing is reported
  as finished that has not reported."
  []
  (scheduler/guard
   :cancel-late-nodes
   :p0-control
   (fn [state _]
     (let [run-id (get-in state [:cancel :active-run-id])
           run (get-in state [:cancel :nodes run-id])]
       (when (= :cancelling (:state run))
         (when-let [target (late-node-target state run-id)]
           (assoc target :run-id run-id)))))
   (fn [state ctx work _]
     (let [run-id (:run-id work)
           now (:now ctx)
           ;; The operator domain keeps its established path unchanged.
           ;; Another draining domain -- an executor shutdown, say -- does
           ;; not become an operator stop: it keeps its own reason and
           ;; origin, which is why it cannot claim operator status here.
           result (if (:operator? work)
                    (cancel/cancel-run-late-nodes
                     (:cancel state)
                     {:run-id run-id
                      :node-id (:node-id work)
                      :reason (:reason work)
                      :origin (:origin work)
                      :at now})
                    (cancel/cancel-subtrees
                     (:cancel state)
                     {:root-ids [(:node-id work)]
                      :reason (:reason work)
                      :origin (:origin work)
                      :operator? false
                      :close-admission? false
                      :at now}))]
       (if (:ok? result)
         (let [receipt (:receipt result)]
           ;; A prepare that returns no `:claim` and no `:dispatch` is a
           ;; state-only plan; the runtime never delivers a sibling
           ;; `:outbox` key.  The effects must be numbered into the outbox
           ;; here or the stop signal is silently dropped.
           ;; `cancel-run-late-nodes` receives and returns the cancel subtree,
           ;; not the Owner state.  Put it back under `:cancel` before the
           ;; outbox entries look up call kinds, then re-sync the call records
           ;; so a `:retry-wait` call reaches `:cancelled` in this commit.
           (let [state (assoc state :cancel (:state result))
                 state (sync-calls-from-cancel state now)]
             {:state (-> state
                         (state/append-diagnostic
                          {:code :LATE_NODE_CANCELLED
                           :run-id run-id
                           :node-id (:node-id work)
                           :reason (:reason work)
                           :origin (:origin work)
                           :cancelled (:cancelled receipt)
                           :outcome-unknown (:outcome-unknown receipt)
                           :at now})
                         (state/append-outbox
                          (cancel-outbox state (:effects result)
                                         (:reason work))))})
         {:state (state/append-diagnostic
                  state
                  (merge {:code :LATE_NODE_CANCEL_REJECTED
                          :run-id run-id
                          :node-id (:node-id work)
                          :at now}
                         (select-keys (:error result)
                                      [:code :message :details])))}))))))

(defn- parked-ask
  "Return the ask payload of a parked program receipt, or nil.

  The executor may wrap the program receipt under `:value`; both shapes are
  accepted.  A park with a `:question` payload is the S1 `ask`; other parks
  (`where` ambiguity, deadline) are not ask cards."
  [value call]
  (let [program (if (and (map? value)
                         (map? (:value value))
                         (contains? (:value value) :state))
                  (:value value)
                  value)]
    (when (and (map? program)
               (= :parked (:state program))
               (string? (get-in program [:payload :question])))
      (let [call-id (:call-id call)]
        {:ask-id (or (:ask-id program) (str "ask-" call-id))
         :name (:name program)
         :program-name (:name program)
         :question (get-in program [:payload :question])
         ;; The card must echo the tenure it belongs to.  Without it the
         ;; answer cannot name a live parked tenure and the program stays
         ;; parked forever.
         :lane (:lane call)
         :tenure-id (:tenure-id call)
         :parked? true}))))

(defn project-tool-result-guard
  "P1: project one terminal tool, shell, or workspace result.

  The receipt is stored in the call and cancellation node.  This guard
  appends it once, before a P3 model claim can read the next snapshot.  A
  failed attempt that P2 will retry is not terminal yet, so only the final
  call result is projected.

  The destination follows the call's owner.  A tool call under an
  actor-owned model call goes to that agent's private `:context`
  (`hc.compose.mission.actors/accept-tool-result`), carrying the same
  `:tool-call-id` and name; a central call's result goes to the shared
  `:conversation`.  The result is never written to both, and a routing
  failure records `:ACTOR_TOOL_RESULT_UNROUTED` instead of leaking it."
  []
  (scheduler/guard
   :project-tool-result
   :p1-closure
   (fn [state _]
     (some (fn [call]
             (when (and (contains? #{:tool :shell :workspace :devops}
                                   (:kind call))
                        (contains? #{:succeeded :failed :cancelled
                                     :outcome-unknown}
                                   (:state call))
                        (not (:result-projected? call)))
               {:call-id (:call-id call)}))
           (sort-by :created-at (vals (:calls state)))))
   (fn [state ctx work _]
     (let [call (state/call state (:call-id work))
           value (or (:result call)
                     (:last-error call)
                     (:retry-error call)
                     (get-in state [:cancel :nodes (:call-id call) :error]))
           ;; A structured result with a :text field must still render as
           ;; the whole EDN+UFSS value.  Only a result that already is a
           ;; string can bypass the structured renderer.
           text (if (string? value)
                  value
                  (render-tool-result ctx value))
           message {:role :tool
                    :text text
                    :call-id (:call-id call)
                    :tool-call-id (:call-id call)
                    :member (:member call)
                    :name (or (:member call)
                              (some-> (:kind call) name))
                    :status (:state call)
                    :error? (not= :succeeded (:state call))
                    :at (:now ctx)}
           ;; A tool call under an actor-owned model call belongs to that
           ;; agent.  Its result goes to the agent's private `:context`,
           ;; never to the shared conversation: the central Plan/Reviewer
           ;; snapshot must not see a worker's tool output.
           actor-attempt-id (get-in state [:calls (:parent-call-id call)
                                            :actor-attempt-id])]
       (if actor-attempt-id
         (let [result (actors/accept-tool-result
                       (:mission state)
                       (:actors state)
                       actor-attempt-id
                       {:call-id (:call-id call)
                        :name (:name message)
                        :output-index 0
                        :tenure-id (:tenure-id call)
                        :agent-id (:agent-id call)}
                       text
                       (not= :succeeded (:state call)))
               stale? (some #(= :actor/stale-tool-result (:type %))
                            (:effects result))
               projected (assoc-in state [:calls (:call-id call)
                                          :result-projected?]
                                   true)]
           (if (:ok? result)
             {:state (-> projected
                         (assoc :mission (:mission result))
                         (assoc :actors (:actors result))
                         (assoc-in [:calls (:call-id call) :result-delivery]
                                   {:status (if stale? :stale-agent :delivered)
                                    :at (:now ctx)})
                         ;; The actor transition's own effects are the audit
                         ;; trail (delivered / stale identity).  Surface them
                         ;; instead of dropping them with the state.
                         (state/append-outbox (:effects result)))}
             ;; No live agent can receive the result.  Keep it out of the
             ;; conversation, make the failure explicit on the call, and
             ;; report it once.  The call stays projected, so the guard
             ;; cannot spin on a permanent routing failure.
             (let [error (:error result)
                   diagnostic {:code :TOOL_RESULT_UNDELIVERABLE
                               :severity :error
                               :message "The tool result could not be delivered to its agent; that turn cannot continue with this result."
                               :remedy :inspect
                               :call-id (:call-id call)
                               :actor-attempt-id actor-attempt-id
                               :reason (:code error)
                               :at (:now ctx)}]
               {:state (-> projected
                           (assoc-in [:calls (:call-id call) :result-delivery]
                                     {:status :undeliverable
                                      :code (:code error)
                                      :at (:now ctx)})
                           (state/append-diagnostic
                            (assoc diagnostic :error error))
                           (state/append-outbox
                            [{:type :owner/blocked
                              :source :tool-result
                              :diagnostic diagnostic
                              :at (:now ctx)}]))})))
         (let [ask (parked-ask value call)
               state (-> state
                         (update :conversation conj message)
                         (assoc-in [:calls (:call-id call) :result-projected?]
                                   true))
               ;; A program park runs outside the relay, so the relay never
               ;; learns that this tenure waits for a human.  Mark it in the
               ;; same commit as the ask event; otherwise `answer-ask` finds
               ;; no parked tenure and the program can never resume.
               state (if ask
                       (let [marked (mission/apply-command
                                     (:mission state)
                                     {:op :mark-awaiting
                                      :lane (:lane ask)
                                      :tenure-id (:tenure-id ask)})]
                         (if (:ok? marked)
                           (state/with-mission state (:state marked))
                           (state/append-diagnostic
                            state
                            {:code :ASK_TENURE_MARK_FAILED
                             :call-id (:call-id call)
                             :lane (:lane ask)
                             :tenure-id (:tenure-id ask)
                             :error (select-keys (:error marked)
                                                 [:code :message])
                             :at (:now ctx)})))
                       state)
               state (cond-> state
                       ask
                       (state/append-outbox
                        [(assoc ask
                                :type :hc/ask
                                :call-id (:call-id call)
                                :session-id (:session-id state)
                                :at (:now ctx))]))]
           {:state state}))))))

(defn recover-lost-model-terminal-guard
  "P1: close a model call whose attempt is gone and no terminal arrived.

  A transport callback can be dropped before it reaches the mailbox.  After a
  grace period, an in-flight model call with no live attempt and no queued
  terminal becomes :outcome-unknown instead of staying :dispatching forever."
  []
  (scheduler/guard
   :recover-lost-model-terminal
   :p1-closure
   (fn [state ctx]
     (some (fn [call]
             (let [attempt-id (:attempt-id call)
                   canonical (state/normalize-attempt-id attempt-id)
                   live-ids (set (map state/normalize-attempt-id
                                      (:live-attempt-ids ctx)))
                   queued-terminal? (some
                                     (fn [terminal]
                                       (and (= (:call-id call)
                                               (:call-id terminal))
                                            (= canonical
                                               (state/normalize-attempt-id
                                                (:attempt-id terminal)))))
                                     (:terminals state))]
               (when (and (= :model (:kind call))
                          (contains? #{:dispatching :running} (:state call))
                          attempt-id
                          (not (contains? live-ids canonical))
                          (not queued-terminal?)
                          (>= (:now ctx)
                              (+ (or (:started-at call) 0) 5000)))
                 {:call-id (:call-id call)})))
           (vals (:calls state))))
   (fn [state ctx work _]
     (let [call (state/call state (:call-id work))
           attempt-id (:attempt-id call)
           attempt-node-id (when attempt-id
                             (state/attempt-node-id (:call-id call)
                                                    attempt-id))
           error {:kind :model-terminal-lost
                  :message "model attempt ended without a terminal event"}
           now (:now ctx)]
       {:state (-> (if (and attempt-node-id
                            (get-in state [:cancel :nodes attempt-node-id]))
                     (-> (state/accept-node state attempt-node-id attempt-id
                                            :outcome-unknown nil error now)
                         ;; A model stream owns no external resource.  A lost
                         ;; terminal must not leave the attempt node live, or
                         ;; finish-run can never close the cancellation domain.
                         (assoc-in [:cancel :nodes attempt-node-id
                                    :resource-alive?] false))
                     state)
                   (state/terminal-call call :outcome-unknown nil error now)
                   (state/finish-run-for-attempt (:call-id call)
                                                 attempt-id
                                                 :outcome-unknown nil error now)
                   (state/append-diagnostic
                    (assoc error :call-id (:call-id call) :at now)))}))))

(defn finish-cancelled-run-guard
  "P1: close a cancelling run after every live resource is gone.

  Unknown outcomes do not block closure; they remain as evidence."
  []
  (scheduler/guard
   :finish-cancelled-run
   :p1-closure
   (fn [state _]
     (let [run-id (get-in state [:cancel :active-run-id])
           run (get-in state [:cancel :nodes run-id])]
       (when (and run
                  (= :cancelling (:state run))
                  (cancel/cancellation-quiescent? (:cancel state) run-id))
         {:run-id run-id})))
   (fn [state ctx work _]
     (let [result (cancel/finish-run (:cancel state)
                                     {:run-id (:run-id work)
                                      :at (:now ctx)})]
       (if (:ok? result)
         {:state (-> (assoc state :cancel (:state result))
                     ;; A closed parent must not project a late accepted
                     ;; report.  The acceptance state is the single gate for
                     ;; pending projections and later acknowledgements.
                     (assoc :acceptance
                            (acceptance/terminate (:acceptance state))))}
         {:state (state/append-diagnostic
                  state
                  (merge {:code :FINISH_CANCELLED_RUN_REJECTED
                          :run-id (:run-id work)
                          :at (:now ctx)}
                         (select-keys (:error result)
                                      [:code :message :details])))})))))

;; ---------------------------------------------------------------------------
;; P1: report delivery

(defn ensure-report-claim-guard
  "P1: repair a pending report that has no usable delivery claim.

  A report is registered with a claim in the same transition, and restart
  recovery rebuilds claims for every pending report.  This guard covers a
  missing or legacy `:failed` claim without inventing a claim transition
  whose source state does not exist.  It is state-only; the delivery guard
  claims the repaired record on the next round."
  []
  (scheduler/guard
   :ensure-report-claim
   :p1-closure
   (fn [state _]
     (some (fn [[report-id report]]
             (let [claim (get-in state [:report-claims report-id])]
               (when (and (= :pending (:phase report))
                          (or (nil? claim)
                              (= :failed (:state claim))))
                 {:report-id report-id})))
           (get-in state [:reports :reports])))
   (fn [state _ work _]
     (let [report-id (:report-id work)
           claim (get-in state [:report-claims report-id] {})]
       {:state (assoc-in state [:report-claims report-id]
                         {:state :pending
                          :attempts (:attempts claim 0)
                          :attempt-id nil
                          :retry-at nil
                          :timer nil
                          :last-error nil})}))))

(defn deliver-child-report-guard
  "P1: deliver one pending child report.

  The claim lives in `:report-claims`, not in the delivery record, so the
  delivery state machine keeps its documented shape."
  []
  (scheduler/guard
   :deliver-child-report
   :p1-closure
   (fn [state _]
     (let [action (delivery/next-action (:reports state))
           report-id (or (:report-id action)
                         (get-in action [:report :report-id]))
           claim (get-in state [:report-claims report-id])]
       (when (and (= :deliver (:action action))
                  (= :pending (:state claim)))
         {:report-id report-id :report (:report action)})))
   (fn [state _ work attempt-id]
     (let [report-id (:report-id work)
           claim (get-in state [:report-claims report-id])]
       {:state (assoc-in state [:report-claims report-id]
                         (assoc claim
                                :state :dispatching
                                :attempt-id attempt-id
                                :attempts (inc (:attempts claim 0))
                                :retry-at nil
                                :timer nil
                                :last-error nil))
        :claim {:path [:report-claims report-id :state]
                :work-id report-id
                :attempt-path [:report-claims report-id :attempt-id]
                :from :pending
                :to :dispatching}
        :dispatch [{:kind :report
                    :report-id report-id
                    :report (:report work)}]}))))

(defn reclaim-child-report-guard
  "P1: reclaim resources after the parent acknowledged a report."
  []
  (scheduler/guard
   :reclaim-child-report
   :p1-closure
   (fn [state _]
     (let [action (delivery/next-action (:reports state))]
       (when (= :reclaim (:action action))
         {:report-id (:report-id action)})))
   (fn [state ctx work _]
     (let [result (delivery/mark-reclaimed (:reports state)
                                           (:report-id work))]
       {:state (if (:ok? result)
                 (-> state
                     (assoc :reports (:state result))
                     (update :report-claims dissoc (:report-id work)))
                 (state/append-diagnostic state
                                    {:code :REPORT_RECLAIM_FAILED
                                     :report-id (:report-id work)
                                     :at (:now ctx)}))}))))

(defn schedule-report-retry-guard
  "P1: schedule one timer for a future report redelivery or ack wait.

  A failed sink attempt moves the claim to `:retry-wait` with a bounded
  exponential `:retry-at`.  A successful sink attempt moves it to
  `:delivered` with an acknowledgement timeout.  This guard arms the timer
  exactly once; the timer message wakes the Owner when the report is due
  again."
  []
  (scheduler/guard
   :schedule-report-retry
   :p1-closure
   (fn [state ctx]
     (some (fn [[report-id claim]]
             (let [retry-at (:retry-at claim)]
               (when (and (contains? #{:retry-wait :delivered}
                                     (:state claim))
                          (= :pending
                             (get-in state
                                     [:reports :reports report-id :phase]))
                          (integer? retry-at)
                          (> retry-at (:now ctx))
                          (= :ready (get-in claim [:timer :state])))
                 {:report-id report-id :retry-at retry-at})))
           (:report-claims state)))
   (fn [state _ work _]
     (let [report-id (:report-id work)
           retry-at (:retry-at work)]
       {:state (assoc-in state [:report-claims report-id :timer]
                         {:state :dispatching
                          :retry-at retry-at})
        :claim {:path [:report-claims report-id :timer :state]
                :work-id report-id
                :attempt-path [:report-claims report-id :timer :attempt-id]
                :from :ready
                :to :dispatching}
        :dispatch [{:kind :timer
                    :call-id (str "report-" report-id)
                    :retry-at retry-at
                    :reason :report-retry}]}))))

(defn wake-report-retry-guard
  "P1: make a due report redelivery claimable again.

  The transition is state-only.  The delivery guard then claims the report
  with a fresh attempt id, so every retry keeps the same claim-before-
  dispatch protocol.  This guard also fires when a successful delivery
  waits too long for the parent acknowledgement."
  []
  (scheduler/guard
   :wake-report-retry
   :p1-closure
   (fn [state ctx]
     (some (fn [[report-id claim]]
             (let [retry-at (:retry-at claim)]
               (when (and (contains? #{:retry-wait :delivered}
                                     (:state claim))
                          (= :pending
                             (get-in state
                                     [:reports :reports report-id :phase]))
                          (integer? retry-at)
                          (<= retry-at (:now ctx)))
                 {:report-id report-id})))
           (:report-claims state)))
   (fn [state _ work _]
     (let [report-id (:report-id work)]
       {:state (assoc-in state [:report-claims report-id]
                         (-> (get-in state [:report-claims report-id])
                             (assoc :state :pending
                                    :attempt-id nil
                                    :retry-at nil
                                    :timer nil)))}))))

;; ---------------------------------------------------------------------------
;; P1: parent-side report projection

(defn project-accepted-report-guard
  "P1: project one accepted child report into the parent conversation.

  The acceptance transition already stored the full payload.  Projection is
  separate so a crash between acceptance and append recovers from
  `pending-projections`."
  []
  (scheduler/guard
   :project-accepted-report
   :p1-closure
   (fn [state _]
     (first (acceptance/pending-projections (:acceptance state))))
   (fn [state ctx work _]
     (let [payload (:payload work)
           ;; The visible text stays the report's own text so publishers and
           ;; existing clients keep reading it verbatim.  The structured
           ;; payload is retained alongside it; dropping it lost kind,
           ;; truncation byte counts, and terminal metadata.
           text (or (:text payload)
                    (if (string? payload) payload (render-ufss payload)))
           projected (acceptance/mark-projected (:acceptance state)
                                                (:parent-call-id work))]
       {:state (-> state
                   (update :conversation conj
                           ;; A report is not an orphan tool result: it has no
                           ;; matching assistant tool-call, and strict model
                           ;; adapters reject that pair.  A user-role message
                           ;; with the report's own text is always legal and
                           ;; still carries the structured payload.
                           {:role :user
                            :text text
                            :payload payload
                            :payload-edn (when (not (string? payload))
                                           (render-ufss payload))
                            :terminal (:terminal work)
                            :status (or (get-in work [:terminal :status])
                                        :report)
                            :report-id (:report-id work)
                            :parent-call-id (:parent-call-id work)
                            :at (:now ctx)})
                   (assoc :acceptance (:state projected)))}))))

;; ---------------------------------------------------------------------------
;; P1: relay barrier retry

(defn relay-barrier-guard
  "P1: retry a pending relay barrier when the board is ready.

  `relay/maybe-relay` performs the atomic retire, barrier write, and
  successor creation.  The guard only enables when the barrier predicates
  already hold, so a refusal cannot spin."
  []
  (scheduler/guard
   :trigger-relay-barrier
   :p1-closure
   (fn [state _]
     (some (fn [tenure]
             (when (:relay-pending? tenure)
               (let [tenure-id (:tenure-id tenure)
                     board (state/board-summary state tenure-id)
                     rejected (get-in state
                                      [:relay-barrier-rejections tenure-id])]
                 (cond
                   (nil? board)
                   (when-not (:missing-board? rejected)
                     {:tenure-id tenure-id :missing-board? true})

                   (and (barrier/execution-relay-ready? tenure board)
                        (nil? rejected))
                   {:tenure-id tenure-id :board board}))))
           (vals (state/execution-tenures state))))
   (fn [state ctx work _]
     (let [tenure-id (:tenure-id work)
           now (:now ctx)]
       (if (:missing-board? work)
         {:state (-> state
                     (assoc-in [:relay-barrier-rejections tenure-id]
                               {:missing-board? true :at now})
                     (state/append-diagnostic
                      {:code :RELAY_BOARD_MISSING
                       :severity :error
                       :message "relay-pending tenure has no committed board; refusing to treat absence as quiescence"
                       :remedy :retry-after-board-commit
                       :tenure-id tenure-id
                       :at now}))}
         (let [result (mission/apply-command
                       (:mission state)
                       {:op :maybe-relay
                        :tenure-id tenure-id
                        :board-facts (state/board-facts state)})]
           (if (:ok? result)
             {:state (-> (state/with-mission state (:state result))
                         (state/append-outbox (:effects result)))}
             {:state (-> state
                         ;; `:relay` is a projection.  Record the refusal in
                         ;; Owner state instead of writing the derived map;
                         ;; any mission transition clears it and re-enables
                         ;; the guard.
                         (assoc-in [:relay-barrier-rejections tenure-id]
                                   (merge {:at now}
                                          (select-keys (:error result)
                                                       [:code :message
                                                        :details])))
                         (state/append-diagnostic
                          (merge {:code :RELAY_BARRIER_REJECTED
                                  :tenure-id tenure-id
                                  :at now}
                                 (select-keys (:error result)
                                              [:code :message :details]))))})))))))

;; ---------------------------------------------------------------------------
;; P2: retry planning and due retries

(defn- retry-terminal-error
  "Preserve the classified last failure while recording the retry outcome.

  The publisher/client reads the call error first.  Keeping the original
  `:kind`/`:code`/`:message` makes a search-required failure visible instead
  of replacing it with the generic `:retry-exhausted` label."
  [current result outcome]
  (let [cause (:last-failure current)
        reason (:reason result)
        fallback-kind (case outcome
                        :failed :retry-exhausted
                        :outcome-unknown :outcome-unknown
                        :cancelled :cancelled
                        :retry-exhausted)
        error (or cause {:kind fallback-kind :reason reason})]
    (cond-> error
      cause (assoc :retry-outcome outcome :retry-reason reason)
      (= outcome :failed) (assoc :retry-exhausted? true
                                 :retry-reason reason)
      (nil? cause) (assoc :reason reason))))

(defn plan-retry-guard
  "P2: plan one bounded retry, or fix the call terminal.

  A timer dispatch claims `[:calls id :timer]`; the claim records the
  scheduler's attempt id even though a timer is not a Missionary attempt."
  []
  (scheduler/guard
   :plan-retry
   :p2-retry
   (fn [state _]
     (some (fn [candidate]
             (when (and (:last-error candidate)
                        (not (:retry-planned? candidate))
                        (contains? #{:running :dispatching}
                                   (:state candidate)))
               {:call-id (:call-id candidate)}))
           (vals (:calls state))))
   (fn [state ctx work _]
     (let [call-id (:call-id work)
           current (state/call state call-id)
           result (retry/plan-retry
                   {:state (:retry current)
                    :policy (:retry-policy ctx)
                    :failure (:last-error current)
                    :now (:now ctx)})
           state (update-in state [:calls call-id] assoc :retry-planned? true)]
       (if-not (:ok? result)
         {:state (-> state
                     (update-in [:calls call-id] assoc
                                :state :failed
                                :retry-error (:error result))
                     (state/accept-node call-id nil :failed nil (:error result)
                                  (:now ctx))
                     (state/append-diagnostic
                      (merge {:code :RETRY_PLAN_REJECTED
                              :call-id call-id
                              :at (:now ctx)}
                             (select-keys (:error result)
                                          [:code :message :details]))))}
         (let [decision (:decision result)
               current (-> current
                           (assoc :retry (:state result)
                                  :last-failure (:last-error current)
                                  :last-error nil))]
           (case decision
             :retry-wait
             (let [current (assoc current :state :retry-wait)
                   state (-> state
                             (state/put-call current)
                             (state/set-node-state call-id :retry-wait)
                             (assoc-in [:calls call-id :timer]
                                       {:state :dispatching}))]
               {:state state
                :claim {:path [:calls call-id :timer :state]
                        :work-id call-id
                        :attempt-path [:calls call-id :timer :attempt-id]
                        :from :ready
                        :to :dispatching}
                :dispatch [{:kind :timer
                            :call-id call-id
                            :retry-at (get-in result [:retry :retry-at])
                            :reason (:reason result)}]})

             :failed
             {:state (state/terminal-call state current :failed
                                    (:result current)
                                    (retry-terminal-error current result
                                                          :failed)
                                    (:now ctx))}

             :outcome-unknown
             {:state (state/terminal-call state current :outcome-unknown
                                    (:result current)
                                    (retry-terminal-error current result
                                                          :outcome-unknown)
                                    (:now ctx))}

             :cancelled
             {:state (state/terminal-call state current :cancelled
                                    (:result current)
                                    (retry-terminal-error current result
                                                          :cancelled)
                                    (:now ctx))}

             {:state (state/append-diagnostic state
                                        {:code :RETRY_DECISION_UNKNOWN
                                         :call-id call-id
                                         :decision decision
                                         :at (:now ctx)})})))))))

(defn claim-due-retry-guard
  "P2: make a due retry claimable again.

  The transition is state-only.  The P3 claim guard calls
  `retry/claim-due` with the scheduler's fresh attempt id, so the retry
  claim keeps the same four-step protocol as a first attempt."
  []
  (scheduler/guard
   :claim-due-retry
   :p2-retry
   (fn [state ctx]
     (some (fn [candidate]
             (let [retry-at (get-in candidate [:retry :retry-at])]
               (when (and (= :retry-wait (:state candidate))
                          (integer? retry-at)
                          (<= retry-at (:now ctx)))
                 {:call-id (:call-id candidate)})))
           (vals (:calls state))))
   (fn [state _ work _]
     (let [call-id (:call-id work)]
       {:state (-> state
                   (update-in [:calls call-id] assoc
                              :state :ready
                              :retry-due? true)
                   (assoc-in [:calls call-id :timer] {:state :ready}))}))))

;; ---------------------------------------------------------------------------
;; P3: actual swarm slots

(defn- actor-rebirth-needed?
  [mission actors tenure-id]
  (let [board* (mission/board-of mission tenure-id)]
    (boolean
     (some (fn [slot]
             (let [agent-id (:agent-id slot)]
               (or (nil? agent-id)
                   (not= :alive (get-in board* [:actors agent-id
                                                :life-state])))))
           (actors/slots actors tenure-id)))))

(declare refusal-limit-for)

(defn wake-actors-guard
  "P3: sync fixed slots and start one @task model turn per idle slot.

  This is the real actor dispatch path.  It creates normal Owner model calls
  with :actor-attempt-id, so the existing claim/dispatch/model-event path
  runs them.  Actor death/rebirth is reconciled from the board token slot;
  a dead actor's in-flight attempt is cancelled in the same transition."
  []
  (scheduler/guard
   :wake-actors
   :p3-progress
   (fn [state _]
     (when (state/admission-open? state)
       (some (fn [tenure]
               (let [tenure-id (:tenure-id tenure)
                     board* (mission/board-of (:mission state) tenure-id)
                     actor-state (:actors state)
                     slots (actors/slots actor-state tenure-id)
                     idle (set (actors/idle-slot-ids actor-state tenure-id))
                     idle-agents (->> slots
                                      (filter #(contains? idle (:slot-id %)))
                                      (keep :agent-id))
                     blocked (fn [agent-id]
                               (refusal-limit-for state tenure-id agent-id))
                     all-blocked? (and (seq idle-agents)
                                       (every? blocked idle-agents))
                     all-reported?
                     (and all-blocked?
                          (every? (fn [agent-id]
                                    (let [limit (blocked agent-id)]
                                      (contains? (:actor-refusal-reported state)
                                                 [tenure-id agent-id
                                                  (:member limit)])))
                                  idle-agents))]
                 (when (and board*
                            (seq (:slot-ids actor-state))
                            (or (actor-rebirth-needed?
                                 (:mission state) actor-state tenure-id)
                                (and (board/global-progressable? board*)
                                     (or (empty? slots)
                                         (and (seq idle-agents)
                                              (not (and all-blocked?
                                                        all-reported?)))))))
                   {:tenure-id tenure-id})))
             (state/active-execution-tenures state))))
   (fn [state ctx work _]
     (let [tenure-id (:tenure-id work)
           synced (actors/sync-tenure (:mission state) (:actors state)
                                      tenure-id)]
       (if-not (:ok? synced)
         {:state (state/append-diagnostic
                  state
                  (merge {:source :wake-actors :at (:now ctx)}
                         (:error synced)))}
         (let [blocked (->> (actors/slots (:actors synced) tenure-id)
                            (keep (fn [slot]
                                    (when-let [agent-id (:agent-id slot)]
                                      (when-let [limit
                                                 (refusal-limit-for
                                                  state tenure-id agent-id)]
                                        {:agent-id agent-id
                                         :role (:role slot)
                                         :limit limit}))))
                            vec)
               woken (if (seq blocked)
                       (actors/wake-all-skipping
                        (:mission synced) (:actors synced) tenure-id
                        (set (map :agent-id blocked)))
                       ;; Keep the three-argument entry point for hosts and
                       ;; tests that redef `wake-all`.
                       (actors/wake-all (:mission synced) (:actors synced)
                                        tenure-id))
               state (-> state
                         (state/with-mission (:mission woken))
                         (assoc :actors (:actors woken)))
               state (reduce
                      (fn [state effect]
                        (case (:type effect)
                          :model/start
                          (let [request (:request effect)
                                agent-id (:agent-id request)
                                limit (refusal-limit-for state tenure-id
                                                         agent-id)]
                            (if limit
                              ;; Defensive: wake-all already skipped it.
                              state
                              (let [[state _call]
                                    (state/create-call
                                     state
                                     {:kind :model
                                      :role (:role request)
                                      :lane :execution
                                      :tenure-id tenure-id
                                      :agent-id agent-id
                                      :actor-attempt-id (:attempt-id effect)
                                      :request
                                      {:snapshot
                                       {:messages (:messages request)
                                        :role (:model-role request)
                                        :mission-role (:mission-role request)
                                        :actor-role (:mission-role request)
                                        :agent-id agent-id
                                        :lane :execution
                                        :tenure-id tenure-id
                                        :session-id (:session-id state)
                                        :model-role (:model-role request)
                                        :model-id (:model-id request)
                                        :program-role (:program-role request)
                                        :holder? (:holder? request)
                                        :s1? (:s1? request)
                                        :write-unlocked? (:write-unlocked? request)
                                        :draft-path (:draft-path request)
                                        :workspace (:workspace request)
                                        :board-snapshot (:board-snapshot request)}
                                       :required-capabilities #{}}
                                      :created-at (:now ctx)})]
                                state)))

                          :model/cancel
                          (let [attempt-id (:attempt-id effect)
                                call (some #(when (= attempt-id
                                                      (:actor-attempt-id %))
                                              %)
                                           (vals (:calls state)))]
                            (if (and call
                                     (not (state/call-node-terminal?
                                           state (:call-id call))))
                              (cond-> (state/terminal-call
                                       state call :cancelled nil
                                       {:kind :actor-dead} (:now ctx))
                                (:attempt-id call)
                                (state/append-outbox
                                 [{:type :cancel/resource
                                   :kind (:kind call)
                                   :call-id (:call-id call)
                                   :attempt-id (:attempt-id call)
                                   :reason :actor-dead}]))
                              state))

                          state))
                      state
                      (:effects woken))
               state
               (reduce
                (fn [state {:keys [agent-id role limit]}]
                  (let [key [tenure-id agent-id (:member limit)]]
                    (if (contains? (:actor-refusal-reported state) key)
                      state
                      (let [diagnostic
                            {:code :TOOL_ADMISSION_REFUSED_LIMIT
                             :severity :error
                             :source :wake-actors
                             :message
                             (str "Actor " agent-id " tool " (:member limit)
                                  " was refused at admission "
                                  (:count limit) " times ("
                                  (:last-code limit) "). "
                                  (if (= :SCOPE_ANNOTATION_MISSING
                                         (:last-code limit))
                                    "Add the missing :scope annotation or stop calling it; "
                                    "Fix the request contract or stop calling it; ")
                                  "do not repeat the same request.")
                             :remedy :fix-request
                             :role (or role :worker)
                             :lane :execution
                             :tenure-id tenure-id
                             :agent-id agent-id
                             :member (:member limit)
                             :count (:count limit)
                             :last-code (:last-code limit)
                             :at (:now ctx)}]
                        (-> state
                            (update :actor-refusal-reported conj key)
                            (state/append-diagnostic diagnostic)
                            (state/append-outbox
                             [{:type :owner/blocked
                               :source :wake-actors
                               :diagnostic diagnostic
                               :at (:now ctx)}]))))))
                state
                blocked)]
           {:state state}))))))

;; ---------------------------------------------------------------------------
;; P3: new work claims

(defn- freeze-outbound-context
  "Build the outbound context once for a model call, before dispatch.

  The provider is injected by the app (`:outbound-context-fn`); compose only
  calls it with the committed state and the already-frozen model messages.
  The result is bound to the first attempt id and reused by every retry, so
  the UI and the model see the same bytes.  A missing/failed provider leaves
  the call without a context; it never blocks the request."
  [state ctx current attempt-id]
  (if (or (:context current) (not= :model (:kind current)))
    current
    (if-let [provider (:outbound-context-fn ctx)]
      (try
        (let [result
              (provider
               {:session-id (:session-id state)
                :call-id (:call-id current)
                :attempt-id attempt-id
                :role (:role current)
                :lane (:lane current)
                :tenure-id (:tenure-id current)
                :agent-id (:agent-id current)
                :turn-card-ids (vec (or (:turn-card-ids current) []))
                :instruction-values (or (:instruction-values current) {})
                :messages (get-in current [:request :snapshot :messages])
                :session (:session state)
                :conversation (:conversation state)})]
          (if (and (map? result)
                   (true? (:ok? result))
                   (map? (:context result)))
            (assoc current
                   :context (assoc (:context result) :attempt-id attempt-id)
                   :context-attempt-id attempt-id)
            current))
        (catch Throwable _ current))
      current)))

(defn- claim-call*
  [state ctx current attempt-id]
  (let [call-id (:call-id current)
        now (:now ctx)
        retry? (:retry-due? current)
        ;; The scheduler owns keyword attempt ids and claim validation checks
        ;; that exact value in state.  Keep it raw for the claim; only the
        ;; retry planner and derived run/message keys use the canonical
        ;; string spelling.
        retry-id (state/normalize-attempt-id attempt-id)
        result (if retry?
                 (retry/claim-due
                  {:state (:retry current)
                   :policy (:retry-policy ctx)
                   :now now
                   :attempt-id retry-id})
                 (retry/init-call
                  {:call-id call-id
                   :attempt-id retry-id
                   :now now
                   :policy (:retry-policy ctx)}))]
    (cond
      (not (:ok? result))
      {:state (-> state
                  (update-in [:calls call-id] assoc
                             :state :failed
                             :retry-error (:error result)
                             :retry-planned? true)
                  (state/accept-node call-id nil :failed nil (:error result) now)
                  (state/append-diagnostic
                   (merge {:code :CLAIM_REJECTED
                           :call-id call-id
                           :at now}
                          (select-keys (:error result)
                                       [:code :message :details]))))}

      (and (some? (:decision result))
           (not= :claimed (:decision result)))
      {:state (state/terminal-call
               state current
               (case (:decision result)
                 :failed :failed
                 :outcome-unknown :outcome-unknown
                 :cancelled :cancelled
                 :failed)
               (:result current)
               {:kind (or (:reason result) :claim-closed)}
               now)}

      :else
      (let [current (freeze-outbound-context state ctx current attempt-id)
            current (-> current
                        (assoc :state :dispatching
                               :attempt-id attempt-id
                               :retry (:state result)
                               :retry-due? false
                               :retry-planned? false
                               :started-at now)
                        (dissoc :last-error))
            state (-> state
                      (state/put-call current)
                      (state/add-attempt-node current attempt-id)
                      (state/set-node-state call-id :dispatching)
                      (state/start-run current attempt-id now))]
        {:state state
         :claim {:path [:calls call-id :state]
                 :work-id call-id
                 :attempt-path [:calls call-id :attempt-id]
                 :from :ready
                 :to :dispatching}
         :dispatch [{:kind (:kind current)
                     :call-id call-id
                     :request (:request current)}]}))))

(defn- claim-call
  [state ctx current attempt-id]
  (let [problem (when (= :model (:kind current))
                  (state/attachment-problem (:request current)
                                            (:attachment-contract ctx)))]
    (if problem
      {:state (-> state
                  (update-in [:calls (:call-id current)] assoc
                             :state :failed
                             :last-error problem)
                  (state/accept-node (:call-id current) nil :failed nil
                                     problem (:now ctx))
                  (state/append-diagnostic
                   (assoc problem :at (:now ctx))))}
      (claim-call* state ctx current attempt-id))))

(defn- claim-guard
  [guard-id kinds allowed?]
  (scheduler/guard
   guard-id
   :p3-progress
   (fn [state ctx]
     ;; A pending operator cancel blocks every new claim immediately, before
     ;; P0 finishes cancellation.  A closed admission blocks every call,
     ;; including Nudge and session-run regeneration: the session command
     ;; refuses to create a run under a closed admission.
     (when (and (empty? (:pending-cancels state))
                (state/admission-open? state))
       (some (fn [candidate]
               (when (and (contains? kinds (:kind candidate))
                          (= :ready (:state candidate))
                          (allowed? state ctx candidate))
                 {:call-id (:call-id candidate)}))
             (sort-by :created-at (vals (:calls state))))))
   (fn [state ctx work attempt-id]
     (claim-call state ctx (state/call state (:call-id work)) attempt-id))))

(defn- conversation-tail
  [state]
  (let [result (state/project-conversation state (:conversation state))]
    (if (:ok? result)
      (vec (take-last 32 (:messages result)))
      ;; Scope projection is a presentation filter, not a parser.  If it
      ;; refuses, rebuild model-valid roles with the pure runtime adapter
      ;; instead of handing `:role :tool-call` to the model adapter.
      (let [items (scope-runtime/conversation->items (:conversation state))]
        (vec (take-last 32 (scope-runtime/items->messages items)))))))

(defn- extension-scope
  [state role tenure-id]
  {:session-id (:session-id state)
   :role role
   :tenure-id tenure-id})

(defn- extension-tools
  [state ctx role tenure-id]
  (if-let [f (:extension-tools-fn ctx)]
    (try
      (vec (or (f (extension-scope state role tenure-id)) []))
      (catch Throwable _ []))
    []))

(defn- skill-context
  [state ctx role tenure-id]
  (if-let [f (:skill-context-fn ctx)]
    (try
      (vec (or (f (extension-scope state role tenure-id)) []))
      (catch Throwable _ []))
    []))

(defn- skill-messages
  [skills]
  (into []
        (keep (fn [{:keys [name text]}]
                (when (and (string? text) (not (str/blank? text)))
                  {:role :system
                   :text (if (and (string? name)
                                  (not (str/blank? name)))
                           (str "Skill: " name "\n\n" text)
                           text)})))
        skills))

(defn- central-messages
  [state ctx role tenure-id]
  (into (skill-messages (skill-context state ctx role tenure-id))
        (conversation-tail state)))

(defn- request-tools
  [state ctx role tenure-id]
  (vec (concat (when (= :plan role)
                 (get-in state [:config :plan-tools]))
               (extension-tools state ctx role tenure-id))))

(defn- program-context-for
  [state role tenure-id agent-id]
  (let [result (surface/program-context
                (:mission state)
                (cond-> {:role role}
                  tenure-id (assoc :tenure-id tenure-id)
                  agent-id (assoc :agent-id agent-id)))]
    (if (:ok? result) (:context result) {})))

(defn- session-model-identity
  "Return the request-snapshot identity for the session's selected model.

  The session binding is the operator's per-session choice; the global main
  role is only the default for a session without one.  The returned keys are
  the model port's `:connection`/`:model` aliases.  A missing binding
  contributes nothing, so the port keeps using the role target."
  [state]
  (let [binding (get-in state [:session :model-binding])]
    (cond-> {}
      (and (map? binding) (some? (:connection-id binding)))
      (assoc :connection (:connection-id binding))

      (and (map? binding) (some? (:model-entry-id binding)))
      (assoc :model (:model-entry-id binding)))))

(defn open-successor-run-guard
  "P3: open the successor run for a turn that arrived during cancellation.

  A user message accepted while the active run was `:cancelling` is committed
  at once, but no successor run may exist until that cancellation domain is
  quiescent: `cancel/finish-run` marks the run `:cancelled` only when no live
  Shell, workspace action, or `:applying` program remains.  Only the message
  that triggers the cancellation carries a reopen request, so without this
  guard the queued turn would wait for a second operator message that never
  comes.

  The guard is level-triggered and atomically opens the run in the same
  transition in which the old one is found cancelled.  It requires both the
  committed `:reopen-requested?` marker and a pending turn: a stopped session
  without new operator input stays stopped, and a turn that some run already
  serves never opens a second run."
  []
  (scheduler/guard
   :open-successor-run
   :p3-progress
   (fn [state _]
     (let [run-id (get-in state [:cancel :active-run-id])
           run (get-in state [:cancel :nodes run-id])]
       (when (and (state/successor-run-pending? state)
                  (seq (:pending-turns state))
                  (empty? (:pending-cancels state))
                  (= :cancelled (:state run))
                  ;; A closed admission alone is not quiescence.  The run may
                  ;; already be `:cancelled` while a late resource (for
                  ;; example a live Shell whose terminal has not arrived)
                  ;; still keeps `:resource-alive? true`.  Opening a
                  ;; successor here would create a second admission while
                  ;; the old resource can still act.
                  (cancel/cancellation-quiescent? (:cancel state) run-id))
         {:run-id run-id})))
   (fn [state _ _ _]
     {:state (state/open-successor-run state)})))

(defn- next-user-turn
  "The pending turn `open-user-turn` may open now, or nil.

  The call's snapshot freezes `(conversation-tail state)`, so no model run may
  be mid-stream anywhere in this conversation: a frozen partial text would be
  recorded in the prefix chain, and the next request that sees the final text
  would report `:PREFIX_DRIFT` for a change no reader made.
  `live-model-for-agent?` already covers the same agent; the stability check
  covers a different lane sharing the conversation.  Nudge calls are exempt
  because they are ephemeral and never enter the chain, and independent
  workers (swarm slots, plan workers, the devops worker) carry their own
  `:context`, so they keep running concurrently."
  [state]
  (when (and (state/admission-open? state)
             (empty? (:pending-cancels state))
             (state/conversation-stable? state))
    (some (fn [turn]
            (when-not (state/live-model-for-agent? state (:agent-id turn))
              turn))
          (:pending-turns state))))

(defn- remove-pending-turn
  "Remove exactly the pending turn WORK that `next-user-turn` selected.

  `next-user-turn` may skip a turn whose agent is still live, so WORK is not
  always the head of the queue.  Removing the head would drop a different
  message."
  [state work]
  (update state :pending-turns
          (fn [turns]
            (let [[before [removed & after]] (split-with #(not= % work) turns)]
              (vec (concat before (if removed after [])))))))

(defn- turn-tenure
  "Resolve the tenure a pending TURN was pinned to.

  A pinned id is authoritative.  If it no longer names the live lane tenure
  (Plan relayed, execution completed, or the id is unknown), return nil so
  the guard refuses the turn instead of rebinding it to a successor.  A
  legacy turn without an id keeps the old resolution: the current Plan
  tenure, or the latest active execution tenure."
  [state role work]
  (let [pinned (:tenure-id work)]
    (if (= :plan role)
      (let [current (relay/current-tenure (:relay state) :plan)]
        (cond
          (and pinned current (= pinned (:tenure-id current))) current
          pinned nil
          :else current))
      (let [tenure (when pinned (state/tenure-by-id state pinned))]
        (cond
          (and tenure
               (= :execution (:lane tenure))
               (= :active (:status tenure)))
          tenure

          pinned nil
          :else
          (state/latest-active-execution-tenure state))))))

(defn open-user-turn-guard
  "P3: turn one pending user input into a ready model call.

  The guard does not dispatch.  It creates the call; the normal model claim
  freezes the input snapshot and records the attempt id.

  A turn pinned to a tenure that has since relayed or completed is refused:
  the turn is removed, one `:TURN_TENURE_STALE` diagnostic is recorded, and
  one `:owner/blocked` event is emitted.  It never opens a model call under
  the successor tenure, because that would apply the old message to a
  different review window and write lock."
  []
  (scheduler/guard
   :open-user-turn
   :p3-progress
   next-user-turn
   (fn [state ctx work _]
     (let [role (:role work)
           lane (if (= :plan role) :plan :execution)
           tenure (turn-tenure state role work)]
       (if (nil? tenure)
         (let [now (:now ctx)
               diagnostic {:code :TURN_TENURE_STALE
                           :severity :error
                           :message (str "The queued turn was written for tenure "
                                         (or (:tenure-id work) "unknown")
                                         ", which is no longer live; it was not sent to the model. Send the message again.")
                           :remedy :resend
                           :role role
                           :lane lane
                           :tenure-id (:tenure-id work)
                           :command-id (:command-id work)
                           :at now}]
           {:state (-> state
                       (remove-pending-turn work)
                       (state/append-diagnostic diagnostic)
                       (state/append-outbox
                        [{:type :owner/blocked
                          :source :turn
                          :diagnostic diagnostic
                          :at now}]))})
         (let [;; A `:chat?` turn is a plain assistant-text answer: no
               ;; program tool, no Plan/Reviewer tool surface.  The role
               ;; identity stays on the snapshot for audit and Nudge
               ;; accounting; only the capability surface is removed.
               chat? (true? (:chat? work))
               tools (when-not chat?
                       (request-tools state ctx role (:tenure-id tenure)))
               turn-card-ids (vec (or (:turn-card-ids work) []))
               instruction-values (or (:instruction-values work) {})
               snapshot (cond-> (merge (program-context-for
                                        state role (:tenure-id tenure)
                                        (:agent-id work))
                                       ;; Freeze the session's selected model
                                       ;; at claim time.  A later selection
                                       ;; applies to the next user turn, not
                                       ;; this frozen request.
                                       (session-model-identity state)
                                       {:messages (central-messages
                                                   state ctx role
                                                   (:tenure-id tenure))
                                        :role :main
                                        :mission-role role
                                        :lane lane
                                        :user-turn? true
                                        :turn-id (:command-id work)
                                        :turn-command-id (:command-id work)
                                        :turn-card-ids turn-card-ids
                                        :instruction-values instruction-values
                                        :tenure-id (:tenure-id tenure)
                                        :session-id (:session-id state)})
                          chat? (assoc :chat? true)
                          (seq tools) (assoc :tools tools))
               state (remove-pending-turn state work)
               [state call]
               (state/create-call
                state
                {:kind :model
                 :role role
                 :lane lane
                 :tenure-id (:tenure-id tenure)
                 :agent-id (:agent-id work)
                 :request (cond-> {:snapshot snapshot
                                   :required-capabilities #{}
                                   :user-turn? true
                                   :turn-command-id (:command-id work)
                                   :turn-card-ids turn-card-ids
                                   :instruction-values instruction-values}
                            (true? (:temporary-read? work))
                            (assoc :temporary-read? true)
                            (contains? work :search)
                            (assoc :search (:search work)))
                 :created-at (:now ctx)})
               call (assoc call
                           :user-turn? true
                           :turn-command-id (:command-id work)
                           :turn-card-ids turn-card-ids
                           :instruction-values instruction-values)
               state (assoc-in state [:calls (:call-id call)] call)]
           {:state state}))))))

(defn claim-model-request-guard
  "P3: claim one ready model request.

  One model attempt per agent may run at a time.  The input snapshot was
  frozen when the call was created and is passed unchanged."
  []
  (claim-guard
   :claim-model-request
   #{:model}
   (fn [state _ candidate]
     (not (state/live-call-for-agent? state (:agent-id candidate)
                                      (:call-id candidate))))))

(defn claim-tool-call-guard
  "P3: claim one ready tool, shell, or workspace call.

  `:max-tool-concurrency` bounds live tool-kind calls per agent."
  []
  (claim-guard
   :claim-tool-call
   state/tool-kinds
   (fn [state ctx candidate]
     (< (state/live-tool-count state (:agent-id candidate))
        (get-in state [:config :max-tool-concurrency]
                (:max-tool-concurrency ctx 1))))))

;; ---------------------------------------------------------------------------
;; P3: unattended stop

(defn- turn-observation
  [state _ctx turn]
  (let [role (:role turn)
        agent-id (or (:agent-id turn) role)
        ;; The turn's terminal failure, or the facts the host classified
        ;; when the call has no raw error of its own (a start refusal P2
        ;; reported as an attempt failure).  The policy classifies permanent
        ;; and context failures from whatever it is given, so the host adds
        ;; only its own verdict for causes the policy cannot read.
        failure (or (:failure turn) (:failure-facts turn))
        facts (mission/nudge-facts
               (:mission state)
               {:role role
                :tenure-id (:tenure-id turn)
                :agent-id agent-id
                :in-flight? (or (state/pending-model-for-agent? state agent-id)
                                (state/live-call-for-agent? state agent-id))
                :turn-ended? true
                :failure failure
                :failure-outcome (:failure-outcome turn)
                :permanent-failure? (true? (:permanent-failure? turn))
                :turn-id (:call-id turn)
                :session-id (:session-id state)
                :nudge-seq (inc (:nudge-seq state 0))})]
    (nudge/observation (:relay state) facts)))

(defn- actor-slot-for
  [actors tenure-id agent-id]
  (some (fn [slot]
          (when (= agent-id (:agent-id slot)) slot))
        (actors/slots actors tenure-id)))

(defn- create-nudge-call
  [state ctx decision observation now]
  (let [role (:role decision)
        lane (:lane decision)
        tenure (:tenure observation)
        tenure-id (:tenure-id tenure)
        agent-id (or (:agent-id observation) role)
        central? (contains? #{:plan :reviewer} role)
        nudge (:nudge decision)
        actor-slot (when-not central?
                     (actor-slot-for (:actors state) tenure-id agent-id))
        ;; A worker/holder continues in its private actor context with the
        ;; task model; a central role continues the shared conversation and
        ;; the identity frozen into the ended turn.
        binding (when central?
                  (or (not-empty (:model-binding (:turn state)))
                      (session-model-identity state)))
        messages (if central?
                   (conj (central-messages state ctx role tenure-id)
                         {:role :system :text (:message decision)})
                   (conj (into (skill-messages
                                (skill-context state ctx role tenure-id))
                               (vec (:context actor-slot)))
                         {:role :system :text (:message decision)}))
        tools (request-tools state ctx role tenure-id)
        snapshot (merge (program-context-for state role tenure-id agent-id)
                        (when central? binding)
                        {:messages messages
                         :nudge-id (:nudge-id nudge)
                         :role (if central? :main :task)
                         :mission-role role
                         :actor-role (when-not central? role)
                         :lane lane
                         :agent-id agent-id
                         :tenure-id tenure-id
                         :session-id (:session-id state)}
                        (when-not central?
                          {:model-role :task
                           :program-role :clj-worker
                           :holder? (= role :holder)
                           :s1? false
                           :write-unlocked? false
                           :workspace (actors/workspace (:actors state)
                                                        tenure-id)
                           :board-snapshot (actors/public-board-snapshot
                                            (:mission state) tenure-id)})
                        (when (seq tools)
                          {:tools tools}))
        [state call] (state/create-call
                      state
                      {:kind :model
                       :role role
                       :lane lane
                       :tenure-id tenure-id
                       :agent-id agent-id
                       :request {:snapshot snapshot
                                 :required-capabilities #{}}
                       :nudge-id (:nudge-id nudge)
                       :created-at now})]
    (-> state
        (update :nudges
                (fn [nudges]
                  (let [all (assoc (or nudges {})
                                   (:nudge-id nudge)
                                   nudge)]
                    ;; A map has no insertion order.  Keep the newest 64 by
                    ;; their recorded time and deterministic id instead of
                    ;; dropping an arbitrary hash-order subset.
                    (into {}
                          (take-last 64
                                     (sort-by (fn [[id value]]
                                                [(or (:at value) 0) id])
                                              all))))))
        (assoc :nudge-seq (:nudge-seq observation))
        ;; The turn is live while the Nudge call runs.  Its terminal installs
        ;; the next ended turn through `set-model-turn`; leaving the old
        ;; ended turn here would let settle-turn re-decide it.
        (assoc :turn {:role role
                      :agent-id agent-id
                      :tenure-id tenure-id
                      :call-id (:call-id call)
                      :model-binding binding
                      :ended? false
                      :at now})
        (assoc-in [:calls (:call-id call) :nudge?] true)
        (cond-> (not central?)
          (assoc-in [:calls (:call-id call) :worker-nudge?] true)))))

(def tool-admission-refusal-limit
  "Consecutive admission refusals for one tenure/agent/member before the
  Owner stops Nudging and records an actionable blocked diagnostic.

  The model can fix a missing `:scope` after one refusal; three identical
  refusals mean it is not reading the tool result.  Continuing would only
  burn model calls, so the turn becomes a terminal diagnostic instead."
  3)

(defn- refusal-limit-for
  "Return the highest refusal entry at/above the limit for one actor, or nil."
  [state tenure-id agent-id]
  (let [entries (->> (:tool-refusals state)
                     (keep (fn [[[t a member] value]]
                             (let [count (long (or (:count value) 0))]
                               (when (and (= t tenure-id)
                                          (= a agent-id)
                                          (>= count tool-admission-refusal-limit))
                                 {:member (or (:member value) member)
                                  :count count
                                  :last-code (:last-code value)
                                  :at (:at value)}))))
                     (sort-by (juxt (comp - :count) (comp str :member))))]
    (first entries)))

(defn- tool-refusal-limit
  "Return the refusal entry at/above the limit for TURN, or nil."
  [state turn]
  (refusal-limit-for state
                     (:tenure-id turn)
                     (or (:agent-id turn) (:role turn))))

(defn- nudge-suppress-reason
  "Ask the host's `:nudge-suppress` hook whether this enabled Nudge may run.

  The hook is an Owner runtime option, never committed state, so it cannot
  be persisted or replayed across a restart.  It is consulted only after the
  policy enabled a Nudge and after every earlier stop gate (operator cancel,
  closed admission, refusal limit, permanent/context block) already answered;
  a legal exit or a deferral never reaches it.  The hook receives the
  committed state, the ended turn, and the policy observation, and returns
  a reason keyword (or any non-nil value) to suppress, nil/false to allow.
  A throwing hook suppresses nothing: the hook is a test seam, and a broken
  one must not silently change production continuation semantics."
  [ctx state turn observation]
  (when-let [f (:nudge-suppress ctx)]
    (try
      (let [reason (f state turn observation)]
        (when (and (some? reason) (not (false? reason)))
          reason))
      (catch Throwable _ nil))))

(defn settle-turn-guard
  "P3: settle one ended model turn.

  A permanent/context failure becomes an explicit blocked diagnostic before
  any Nudge.  Failure turns have a bounded per-tenure/agent budget; ordinary
  successful no-progress turns keep nudging per §2.5.

  The enabled predicate is a readiness gate: a turn that ended (or whose own
  call is terminal under a stopped run), whose agent owns no pending or live
  call, with no user turn and no uncommitted operator cancel waiting.  The
  stopped-run case closes the turn left behind by a cancelled Nudge attempt;
  without it the snapshot keeps reporting an unattended active turn after
  the run is already `:cancelled`.  The decision belongs to `:prepare`, the only place that
  commits an answer.  A committed answer clears the turn, so a settled stop
  is never re-answered; an enabled decision keeps the turn for the Nudge call
  it creates; a deferred one keeps it for the live work it waits on.  A
  cancelled or closed run never creates a Nudge call: the stop clears the
  turn and records why nothing was dispatched."
  []
  (scheduler/guard
   :settle-turn
   :p3-progress
   (fn [state _]
     (let [turn (:turn state)
           agent-id (or (:agent-id turn) (:role turn))
           call (when-let [call-id (:call-id turn)]
                  (state/call state call-id))
           terminal-call? (and call
                               (contains? #{:succeeded :failed :cancelled
                                            :outcome-unknown}
                                          (:state call)))
           stop-closed? (or (state/run-cancelling? state)
                            (not (state/admission-open? state)))]
       ;; §2.5 has three legal exits and nothing else.  "The last turn was a
       ;; Nudge" is not one of them: a Nudge turn that ends still stopped
       ;; naturally, so the policy decides, and the delivered Nudge advanced
       ;; `:nudge-seq`, so the next candidate is a distinct call rather than a
       ;; replay.  The decision is not spent in this predicate: the scheduler
       ;; re-evaluates it after every commit, so calling `nudge/decide` here
       ;; would re-answer a legal exit once per round.  `:prepare` clears the
       ;; turn when it commits an answer, which is what makes a present turn
       ;; owe a decision exactly once.
       (when (and (or (true? (:ended? turn))
                      (and terminal-call? stop-closed?))
                  (not (and (:call-id turn)
                            (state/actor-call? state (:call-id turn))))
                  (empty? (:pending-turns state))
                  (empty? (:pending-cancels state))
                  (not (state/pending-model-for-agent? state agent-id))
                  (not (state/live-call-for-agent? state agent-id)))
         {:turn turn})))
   (fn [state ctx work _]
     (let [turn (:turn work)
           observation (turn-observation state ctx turn)
           decision (nudge/decide observation)
           now (:now ctx)
          history (fn history*
                    ([state reason] (history* state reason nil))
                     ([state reason extra]
                      (let [blocked? (contains? #{:blocked
                                                  :nudge-budget-exhausted
                                                  :tool-admission-refused-limit}
                                                reason)]
                        (update state :turn-history
                                (fn [entries]
                                  (vec (take-last 16
                                                  (conj (vec entries)
                                                        (merge
                                                         {:role (:role turn)
                                                          :agent-id (:agent-id turn)
                                                          :enabled? (and (:enabled? decision)
                                                                         (not blocked?))
                                                          :blocked? blocked?
                                                          :reason reason
                                                          :exit (:exit decision)
                                                          :at now}
                                                         extra)))))))))
           blocked-state
           (fn [state diagnostic reason]
             (let [diagnostic (assoc diagnostic :at now
                                     :turn-id (:call-id turn))]
               (-> state
                   (assoc :turn nil)
                   (state/append-diagnostic diagnostic)
                   (state/append-outbox
                    [{:type :owner/blocked
                      :source :nudge
                      :diagnostic diagnostic
                      :at now}])
                   (history reason))))]
       (cond
         ;; A Phase B actor turn belongs to wake-actors: it owns the slot,
         ;; the private context, and the next turn.  settle-turn must not
         ;; create a second model call for the same worker.
         (and (:call-id turn)
              (state/actor-call? state (:call-id turn)))
         {:state (-> state
                     (assoc :turn nil)
                     (history :actor-owned-turn))}

         ;; An operator stop is not a natural stop, so §2.5 owes it no Nudge.
         ;; It is still recorded, so the turn history shows why nothing was
         ;; dispatched, and it never re-answers the same cleared turn.  A stop
         ;; must clear the turn even when the policy would suppress it for
         ;; some other reason, so this gate runs before the decision branches.
         (state/run-cancelling? state)
         {:state (-> state
                     (assoc :turn nil)
                     (history :operator-cancelled))}

         (not (state/admission-open? state))
         ;; A closed admission or an already terminal run is the same kind of
         ;; gate: no call, Nudge included, may dispatch.  Either way the turn
         ;; stops owing a decision here.
         {:state (-> state
                     (assoc :turn nil)
                     (history :admission-closed))}

         ;; A model that keeps calling a tool without the required `:scope`
         ;; must not loop through Nudge forever.  The tool result already
         ;; tells it what is missing; after the documented limit the turn
         ;; becomes an actionable diagnostic and stops.
         (tool-refusal-limit state turn)
         (let [refusal (tool-refusal-limit state turn)]
           {:state (blocked-state
                    state
                    {:code :TOOL_ADMISSION_REFUSED_LIMIT
                     :severity :error
                     :message (str "Tool " (:member refusal)
                                   " was refused at admission "
                                   (:count refusal) " times ("
                                   (:last-code refusal)
                                   "). "
                                   (if (= :SCOPE_ANNOTATION_MISSING
                                          (:last-code refusal))
                                     "Add the missing :scope annotation or stop calling it; "
                                     "Fix the request contract or stop calling it; ")
                                   "do not repeat the same request.")
                     :remedy :fix-request
                     :role (:role turn)
                     :lane (:lane observation)
                     :tenure-id (:tenure-id turn)
                     :agent-id (:agent-id turn)
                     :member (:member refusal)
                     :count (:count refusal)
                     :last-code (:last-code refusal)}
                    :tool-admission-refused-limit)})

         (:blocked? decision)
         {:state (blocked-state state (:diagnostic decision) :blocked)}

         :else
         (let [key [(:tenure-id turn) (:agent-id turn)]
               failure? (some? (:failure turn))
               budget (get-in state [:nudge-budget key]
                              {:count 0 :progress (:progress-seq state 0)})
               progressed? (> (:progress-seq state 0)
                              (:progress budget 0))
               count (if progressed? 0 (:count budget 0))]
           (cond
             (and failure? (>= count 3))
             {:state (blocked-state
                      state
                      {:code :NUDGE_BUDGET_EXHAUSTED
                       :severity :error
                       :message "Nudge budget exhausted without progress; fix the failure or request contract."
                       :remedy :fix-configuration
                       :role (:role turn)
                       :lane (:lane observation)
                       :tenure-id (:tenure-id turn)
                       :agent-id (:agent-id turn)
                       :failure (:failure turn)}
                      :nudge-budget-exhausted)}

             (:enabled? decision)
             (let [suppress-reason (nudge-suppress-reason ctx state turn
                                                          observation)]
               (if suppress-reason
                 ;; The host suppressed this Nudge (test hook).  The turn is
                 ;; cleared and the answer recorded once, so the stop is an
                 ;; observable idle boundary instead of an invisible live
                 ;; turn.  The next ended turn is judged again; suppression
                 ;; is level-triggered, never a one-shot.
                 {:state (-> state
                             (assoc :turn nil)
                             (history :nudge-suppressed
                                      {:enabled? false
                                       :suppress-reason suppress-reason}))}
                 (let [state' (create-nudge-call state ctx decision
                                                  observation now)]
                   {:state (if failure?
                             (assoc-in state' [:nudge-budget key]
                                       {:count (inc count)
                                        :progress (:progress-seq state 0)
                                        :last-failure (:failure turn)})
                             (update state' :nudge-budget dissoc key))})))

             (:deferred? decision)
             ;; In-flight work or a due retry keeps the turn eligible.  The
             ;; enabling test already excludes a turn whose agent owns a
             ;; pending or live call, so this branch normally cannot be
             ;; reached twice for the same wait; when it is (the call
             ;; settles between the gate and this commit), re-recording the
             ;; wait is correct and the following round decides the stop.
             ;; No disabling marker is written: a "settled" turn would never
             ;; be judged again, and the stop would stay unanswered forever
             ;; once the deferring work ended.  `:deferred-at` is the record.
             {:state (-> state
                         (assoc-in [:turn :deferred-at] now)
                         (history (:reason decision)))}

             :else
             ;; A legal exit, an ask answered mid-turn, a suspended lane, or a
             ;; turn the policy cannot place in a task cycle.  `:prepare` is
             ;; the only place that commits an answer, so the turn is cleared
             ;; here and its answer recorded once.
             {:state (-> state
                         (assoc :turn nil)
                         (history (:reason decision)))})))))))

;; ---------------------------------------------------------------------------
;; Catalog

(defn catalog
  "Return the P0–P4 guard catalog for one Owner."
  []
  (scheduler/catalog
   {:p0-control
    [(operator-cancel-guard)
     (cancel-late-nodes-guard)]

    :p1-closure
    [(accept-call-terminal-guard)
     (recover-lost-model-terminal-guard)
     (project-tool-result-guard)
     (finish-cancelled-run-guard)
     (ensure-report-claim-guard)
     (deliver-child-report-guard)
     (reclaim-child-report-guard)
     (schedule-report-retry-guard)
     (wake-report-retry-guard)
     (project-accepted-report-guard)
     (relay-barrier-guard)]

    :p2-retry
    [(plan-retry-guard)
     (claim-due-retry-guard)]

    :p3-progress
    [(wake-actors-guard)
     (open-successor-run-guard)
     (open-user-turn-guard)
     (claim-model-request-guard)
     (claim-tool-call-guard)
     (settle-turn-guard)]}))
