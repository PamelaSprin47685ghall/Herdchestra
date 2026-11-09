(ns hc.compose.workspace.board
  "Swarm STM adapter for the protected file contract.

  `hc.core.workspace-gate` owns the file decision; the swarm board owns the
  claim, action and responsibility lifecycle.  This namespace is the only
  place that joins them:

  * an edit is bound to a live actor and its current claim;
  * the `:applying` qualification is fixed in one board transition;
  * an applied batch finishes the edit action with a result reference;
  * a hard failure leaves the action `:uncertain`, so the responsibility
    remains visible instead of disappearing.

  A Plan draft is not a swarm edit.  The session marks it with `:plan-draft`;
  the Owner adapter then validates the pinned Plan tenure, the first-review
  write lock and the draft claim in the mission, and never fabricates a
  swarm actor.  The kernel adapter has no mission authority and refuses such
  a request.

  Two adapters are provided:

  * `adapter` reads and writes an in-process `hc.core.swarm-stm` kernel;
  * `owner-adapter` issues the same commands through an Owner mailbox, for
    compositions where the Owner is the only board writer.

  Nothing here touches the file system or acquires W.  `workspace-gate`
  calls these functions while it already holds W."
  (:require [clojure.string :as str]
            [hc.core.swarm-stm :as swarm]
            [hc.core.workspace-gate.board :as gate-board]
            [hc.core.workspace-gate.error :as err]))

;; ---------------------------------------------------------------------------
;; Board reads

(defn- id
  "Accept the keyword ids used by the STM and the string ids used by models
  and transport envelopes.  A leading colon is a common transport artifact."
  [x]
  (if (string? x)
    (keyword (if (str/starts-with? x ":") (subs x 1) x))
    x))

(defn- normalize-request
  [request]
  (cond-> request
    (contains? request :actor-id) (update :actor-id id)
    (contains? request :claim-id) (update :claim-id id)
    (contains? request :task-id) (update :task-id id)))

(defn check-eligibility-board!
  "Early non-mutating check used by workspace-gate before the dry-run.

  Throws an ordinary rejection when the actor is dead, the claim is gone, the
  claim belongs to someone else, or the claim is no longer bound to TASK-ID.
  A nil claim is rejected: a protected edit must be authorized by a claim."
  [b request]
  (let [{:keys [actor-id claim-id task-id]} (normalize-request request)]
    (let [actor (get-in b [:actors actor-id])
          claim (get-in b [:claims claim-id])
          task (get-in b [:tasks task-id])]
      (when-not (and actor (= :alive (:life-state actor)))
        (err/fail! :ACTOR_INACTIVE :actor-inactive
                   "The actor is no longer alive"
                   {:actor-id actor-id
                    :life-state (:life-state actor)}))
      (when-not claim-id
        (err/fail! :CLAIM_CHANGED :missing-claim
                   "A protected edit requires a claim"
                   {:actor-id actor-id :task-id task-id}))
      (when-not (and claim (= actor-id (:agent-id claim)))
        (err/fail! :CLAIM_CHANGED :claim-changed
                   "The claim is missing or belongs to another actor"
                   {:claim-id claim-id :actor-id actor-id
                    :claim-agent-id (:agent-id claim)}))
      (when-not (and task (= task-id (:task-id claim))
                     (= claim-id (:claim-id task)))
        (err/fail! :CLAIM_CHANGED :claim-task-mismatch
                   "The claim is not bound to the requested task"
                   {:claim-id claim-id :task-id task-id
                    :claim-task-id (:task-id claim)
                    :current-claim-id (:claim-id task)}))
      (when (= :done (:state task))
        (err/fail! :CLAIM_CHANGED :task-done
                   "The claimed task is already done"
                   {:task-id task-id}))
      nil)))

(defn- action-for-request
  [b request-id]
  (some (fn [action]
          (when (= request-id (:request-id action)) action))
        (vals (:actions b))))

(defn- permit
  [request action summary]
  {:action-id (:action-id action)
   :request-id (:request-id action)
   :actor-id (:actor-id request)
   :claim-id (:claim-id request)
   :task-id (:task-id request)
   :summary summary})

(defn- abandon-action!
  "Best-effort closure of an action whose begin or applying qualification
  could not be made durable.  It never authorizes an apply: the caller still
  returns the original failure.  If persistence is still down, the in-memory
  action stays visible and the service keeps its `:persist-error`; no file is
  written."
  [execute! request action-id reason]
  (when action-id
    (execute! {:op :advance-action
               :actor-id (:actor-id request)
               :action-id action-id
               :phase :applying})
    (execute! {:op :advance-action
               :actor-id (:actor-id request)
               :action-id action-id
               :phase :uncertain
               :result-ref {:request-id (:request-id request)
                            :status :NOT_APPLIED
                            :reason reason}})))

;; ---------------------------------------------------------------------------
;; Command execution through either a kernel or an Owner mailbox

(defn command-result
  "Normalize the two accepted board-command receipts.

  The kernel returns the command result directly.  An Owner mailbox returns
  `{:ok? ... :board ... :result ...}`.  Merging the inner result keeps the
  command-specific fields (`:action-id`, `:claim-id`, `:error`, ...)."
  [result]
  (if-not (map? result)
    {:ok? false
     :error :INVALID_BOARD_RECEIPT
     :detail {:actual (some-> result class str)}}
    (let [inner (:result result)
          merged (if (map? inner) (merge result inner) result)]
      (if (contains? merged :ok?)
        merged
        (assoc merged :ok? true)))))

(defn- fail-command!
  [result]
  (let [detail (cond-> (or (:detail result) {})
                 (contains? result :persisted?)
                 (assoc :persisted? (:persisted? result))

                 (:persist-error result)
                 (assoc :persist-error (:persist-error result)))]
    (err/fail! (or (:error result) :INTERNAL)
               (or (:code result) :swarm-rejected)
               (or (:message result)
                   (get-in result [:persist-error :message])
                   (str "Swarm command rejected: " (:error result)))
               detail)))

;; ---------------------------------------------------------------------------
;; Edit action lifecycle

(defn- begin-edit-on!
  [snapshot execute! request summary]
  (let [request (normalize-request request)]
    (check-eligibility-board! (snapshot) request)
    (if-let [existing (action-for-request (snapshot) (:request-id request))]
      (case (:phase existing)
        :applying
        ;; A repeated begin-apply may be the only call before an apply.  Force
        ;; the existing :applying value through the durable hook; otherwise a
        ;; prior event-persist failure could leave the write-ahead record only
        ;; in memory.
        (let [checked (command-result
                       (execute! {:op :checkpoint
                                  :actor-id (:actor-id request)
                                  :reason :begin-apply-replay}))]
          (if (:ok? checked)
            (permit request existing summary)
            (fail-command! checked)))
        :finished
        (err/fail! :CALL_REPLAY_MISMATCH :action-already-finished
                   "This request-id already completed an edit action"
                   {:request-id (:request-id request)
                    :action-id (:action-id existing)})
        (err/fail! :CLAIM_CHANGED :action-not-applying
                   "An action for this request exists but is not applying"
                   {:request-id (:request-id request)
                    :action-id (:action-id existing)
                    :phase (:phase existing)}))
      (let [begun (command-result
                   (execute! {:op :begin-action
                              :actor-id (:actor-id request)
                              :task-id (:task-id request)
                              :claim-id (:claim-id request)
                              :kind :edit
                              :request-id (:request-id request)}))
            action-id (:action-id begun)]
        (if-not (:ok? begun)
          (do
            ;; A created action whose event persistence failed is left in
            ;; memory.  Close it best-effort so it cannot authorize an apply
            ;; that has no durable write-ahead record.  A rejection that
            ;; created nothing carries no action id and is left untouched.
            (when action-id
              (abandon-action! execute! request action-id (:error begun)))
            (fail-command! begun))
          (let [advanced (command-result
                          (execute! {:op :advance-action
                                     :actor-id (:actor-id request)
                                     :action-id action-id
                                     :phase :applying}))]
            (if (:ok? advanced)
              (permit request {:action-id action-id
                               :request-id (:request-id request)}
                      summary)
              (do
                ;; Do not leave a claimed action that blocks the actor forever.
                ;; `:claimed -> :applying -> :uncertain` is the valid path.
                (abandon-action! execute! request action-id (:error advanced))
                (fail-command! advanced)))))))))

(defn- checkpoint-published!
  "Force the already-committed action value through the durable event hook.
  Returning `:already? true` from memory alone would claim a publication the
  previous write never made durable.  A mailbox adapter has its own commit
  boundary and supplies no checkpoint function, so it keeps the old
  already-published answer."
  [checkpoint! permit]
  (if-not checkpoint!
    {:published true :already? true}
    (let [result (command-result (checkpoint! permit))]
      (if (:ok? result)
        {:published true :already? true}
        (throw (ex-info "Board publication is still not durable"
                        {:code :STM_CONFLICT
                         :action-id (:action-id permit)
                         :persisted? (:persisted? result)
                         :board-error (:error result)
                         :detail (:detail result)}))))))

(defn- publish-applied-on!
  [snapshot execute! checkpoint! permit applied]
  (let [aid (:action-id permit)
        action (get-in (snapshot) [:actions aid])]
    (case (:phase action)
      :finished
      ;; The in-memory action may be finished while its event persistence
      ;; failed.  Force one checkpoint through the same durable hook instead
      ;; of claiming publication from memory alone.
      (checkpoint-published! checkpoint! permit)

      :applying
      (let [result (command-result
                    (execute! {:op :advance-action
                               :actor-id (:actor-id permit)
                               :action-id aid
                               :phase :finished
                               :result-ref {:request-id (:request-id permit)
                                            :status :APPLIED
                                            :summary (:summary permit)
                                            :op-count (:op-count applied)
                                            :applied-at-version
                                            (:board-version (snapshot))}}))]
        (if (:ok? result)
          {:published true}
          (throw (ex-info "Applied files but the STM publication conflicted"
                          {:code :STM_CONFLICT
                           :action-id aid
                           :swarm (:error result)
                           :detail (:detail result)}))))
      (throw (ex-info "Applied files but the action is not in :applying"
                      {:code :STM_CONFLICT
                       :action-id aid
                       :phase (:phase action)})))))

(defn- publish-failure-on!
  [snapshot execute! checkpoint! permit failure]
  (let [aid (:action-id permit)
        action (get-in (snapshot) [:actions aid])
        stage (let [raw (or (:last-known-stage failure) (:phase failure))]
                (case raw
                  :board :publish-pending
                  :commit :applying
                  (:accepted :waiting-gate :final-check :preparing :applying
                   :publish-pending :uncertain :finished) raw
                  :applying))
        expected-action (let [value (:expected-action failure)
                              text (cond
                                     (string? value) value
                                     (nil? value) "none"
                                     :else (pr-str (err/sanitize value)))]
                          (subs text 0 (min 300 (count text))))
        failed-at (let [location (:failed-at failure)
                        location (cond
                                   (map? location) location
                                   (keyword? location) {:stage location}
                                   :else {:stage stage})]
                    (into {}
                          (keep (fn [[k v]]
                                  (when (and (contains? #{:op-index :stage} k)
                                             (some? v))
                                    [k (err/sanitize v)])))
                          location))
        result-ref (into {}
                         {:request-id (:request-id permit)
                          :status (:status failure)
                          :message (:message failure)
                          :phase (:phase failure)
                          :last-known-stage stage
                          :expected-action expected-action
                          :failed-at failed-at})]
    (case (:phase action)
      :uncertain (checkpoint-published! checkpoint! permit)
      :finished (checkpoint-published! checkpoint! permit)
      :applying
      (let [result (command-result
                    (execute! {:op :advance-action
                               :actor-id (:actor-id permit)
                               :action-id aid
                               :phase :uncertain
                               :result-ref result-ref}))]
        (if (:ok? result)
          {:published true}
          (throw (ex-info "Hard failure could not be published to the STM"
                          {:code :STM_CONFLICT
                           :action-id aid
                           :swarm (:error result)
                           :detail (:detail result)}))))
      (throw (ex-info "Hard failure has no action in :applying"
                      {:code :STM_CONFLICT
                       :action-id aid
                       :phase (:phase action)})))))

;; ---------------------------------------------------------------------------
;; Public adapters

(defn- plan-command!
  "Submit one Plan draft command through the Owner and throw its refusal.

  A Plan draft is not a swarm action: its authority is the mission's current
  Plan tenure and first-write draft claim.  The Owner remains the only
  writer of that authority."
  [execute! command]
  (let [result (command-result (execute! command))]
    (if (:ok? result)
      result
      (let [error (:error result)
            code (if (map? error)
                   (or (:code error) :INTERNAL)
                   (or error (:code result) :INTERNAL))
            message (or (when (map? error) (:message error))
                        (:message result)
                        (str "Plan draft command rejected: " code))
            details (if (map? error) (:details error) (:detail result))]
        (err/fail! code code message details)))))

(defn- require-plan-owner!
  [execute! request]
  (when-not execute!
    (err/fail! :UNSUPPORTED_OPERATION :plan-draft-requires-owner
               "A Plan draft write requires the Owner mission authority"
               {:actor-id (:actor-id request)})))

(defn- check-plan-draft!
  "Read-only eligibility check for a Plan draft write.

  The mission validates the pinned Plan tenure, the first-review write lock,
  the draft path and the handoff freeze.  It does not create a claim; the
  claim is reserved under W by `draft-pre-apply!`."
  [execute! request]
  (require-plan-owner! execute! request)
  (let [{:keys [tenure-id path request-id call-id]} (:plan-draft request)]
    (plan-command! execute! {:op :check-draft
                             :tenure-id tenure-id
                             :path path
                             :request-id request-id
                             :call-id call-id})
    nil))

(defn- begin-plan-draft!
  "Fix the Plan draft qualification in one Owner transaction.

  A first draft already reserved its claim in the under-W pre-apply, so this
  revalidates the same tenure, unlock, path and handoff with a read-only
  `:check-draft` after the dry-run instead of claiming twice.  A fixed-path
  revision has no pre-apply claim, so it claims here; that claim is its only
  mission qualification before the apply.  Either way a retired Plan tenure
  is refused before any file is touched."
  [execute! request summary]
  (require-plan-owner! execute! request)
  (let [{:keys [tenure-id path request-id call-id]} (:plan-draft request)
        ;; `:pre-apply!` is present only for the first-write path, where
        ;; `draft-pre-apply!` already claimed the path under W.
        op (if (:pre-apply! request) :check-draft :claim-draft)
        result (plan-command! execute! {:op op
                                        :tenure-id tenure-id
                                        :path path
                                        :request-id request-id
                                        :call-id call-id})]
    {:board :plan
     :request-id request-id
     :actor-id (:actor-id request)
     :tenure-id tenure-id
     :path path
     :summary summary
     :outcome (or (:outcome result)
                  (get-in result [:receipt :outcome]))}))

(defn- publish-plan-applied!
  "Acknowledge an applied Plan draft without inventing a swarm action.

  The mission records the draft bytes from the applied file after the
  program terminal (`sync-plan-drafts!`).  The commit itself only needed the
  tenure and claim qualification fixed by the pre-apply or `begin-plan-draft!`
  before the apply."
  [_execute! permit _applied]
  {:published true
   :already? true
   :board :plan
   :tenure-id (:tenure-id permit)
   :path (:path permit)})

(defn- publish-plan-failure!
  "Release the first-write claim after a hard Plan draft failure."
  [execute! permit failure]
  (when execute!
    (plan-command! execute! {:op :release-draft
                             :path (:path permit)
                             :request-id (:request-id permit)}))
  {:published true
   :board :plan
   :phase :uncertain
   :status (:status failure)})

(defn- reify-adapter
  [snapshot execute! checkpoint! plan-execute!]
  (reify gate-board/BoardAdapter
    (check-eligibility! [_ request]
      (if (:plan-draft request)
        (check-plan-draft! plan-execute! request)
        (check-eligibility-board! (snapshot) request)))
    (begin-apply! [_ request summary]
      (if (:plan-draft request)
        (begin-plan-draft! plan-execute! request summary)
        (begin-edit-on! snapshot execute! request summary)))
    (publish-applied! [_ permit applied]
      (if (= :plan (:board permit))
        (publish-plan-applied! plan-execute! permit applied)
        (publish-applied-on! snapshot execute! checkpoint! permit applied)))
    (publish-failure! [_ permit failure]
      (if (= :plan (:board permit))
        (publish-plan-failure! plan-execute! permit failure)
        (publish-failure-on! snapshot execute! checkpoint! permit failure)))))

(defn adapter
  "`BoardAdapter` over an in-process swarm kernel."
  [kernel]
  (reify-adapter #(swarm/snapshot kernel)
                 #(swarm/execute! kernel %)
                 (fn [permit]
                   (swarm/execute! kernel
                                   {:op :checkpoint
                                    :actor-id (:actor-id permit)
                                    :reason :publication-retry}))
                 nil))

(defn owner-adapter
  "`BoardAdapter` over an Owner mailbox.

  `execute-board-fn` is `(fn [command] receipt)` where receipt is either the
  kernel-style command result or `{:ok? ... :board ... :result ...}`.
  `board-snapshot-fn` returns the current immutable board map.  Both calls
  are synchronous and serialized by the Owner; this adapter never writes the
  board itself."
  [execute-board-fn board-snapshot-fn]
  (reify-adapter board-snapshot-fn execute-board-fn nil execute-board-fn))
