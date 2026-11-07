(ns hc.core.workspace-gate.board
  "Board adapter boundary for the protected commit.

  The file contract never reads the board's source code and never selects a
  task.  It only asks the adapter to check eligibility, fix the applying
  qualification, and publish the result.  A production adapter must perform
  `begin-apply!` and `publish-applied!` inside its own STM transaction; the
  in-memory adapter here is a reference implementation for tests and
  composition."
  (:require [hc.core.workspace-gate.error :as err]))

(defprotocol BoardAdapter
  (check-eligibility!
    [board request]
    "Early non-mutating check of actor liveness and claim validity.  Throw an
    ordinary rejection (`:ACTOR_INACTIVE`, `:CLAIM_CHANGED`) or return nil.")
  (begin-apply!
    [board request summary]
    "Atomically re-check eligibility and fix the action in `:applying`.
    Return a permit that `publish-*` can use.  Must not touch files.")
  (publish-applied!
    [board permit result]
    "Publish an applied result as a pure control update.  May throw an STM
    conflict; the orchestrator retries only this call, never the file apply.")
  (publish-failure!
    [board permit failure]
    "Publish a hard-failure or uncertain result as a pure control update."))

(defn- check-eligibility-state!
  [state request]
  (let [{:keys [actors claims]} state
        actor-id (:actor-id request)
        claim-id (:claim-id request)]
    (when-not (and actor-id
                   (contains? actors actor-id)
                   (get-in actors [actor-id :alive?]))
      (err/fail! :ACTOR_INACTIVE :actor-inactive
                 "Actor is not alive"
                 {:actor-id actor-id}))
    (when (nil? claim-id)
      (err/fail! :CLAIM_CHANGED :missing-claim
                 "A protected edit requires a claim"
                 {:actor-id actor-id}))
    (when-not (true? (get claims claim-id))
      (err/fail! :CLAIM_CHANGED :claim-changed
                 "Claim is no longer valid"
                 {:claim-id claim-id}))
    nil))

(defn- action-permit
  [state permit]
  (when-not (map? permit)
    (err/fail! :CLAIM_CHANGED :invalid-permit
               "A board permit must be a map"
               {:permit permit}))
  (let [request-id (:request-id permit)
        action (get-in state [:actions request-id])]
    (when-not request-id
      (err/fail! :CLAIM_CHANGED :missing-permit-request-id
                 "A board permit must carry :request-id"
                 {:permit permit}))
    (when-not action
      (err/fail! :CLAIM_CHANGED :action-missing
                 "No action exists for this permit"
                 {:request-id request-id}))
    (when-not (= request-id (:request-id action))
      (err/fail! :CLAIM_CHANGED :action-request-mismatch
                 "The action does not belong to this request"
                 {:request-id request-id
                  :action-request-id (:request-id action)}))
    (when-not (= :applying (:phase action))
      (err/fail! :CLAIM_CHANGED :action-not-applying
                 "The action is not in the applying phase"
                 {:request-id request-id :phase (:phase action)}))
    action))

(defrecord MemoryBoard [state]
  BoardAdapter
  (check-eligibility! [_ request]
    (check-eligibility-state! @state request))
  (begin-apply! [board request summary]
    (swap! state
           (fn [s]
             (check-eligibility-state! s request)
             (assoc-in s [:actions (:request-id request)]
                       {:request-id (:request-id request)
                        :phase :applying
                        :actor-id (:actor-id request)
                        :task-id (:task-id request)
                        :claim-id (:claim-id request)
                        :summary summary})))
    {:board board
     :action-id (:request-id request)
     :request-id (:request-id request)})
  (publish-applied! [board permit result]
    (let [failures (get @state :fail-publish 0)]
      (when (pos? failures)
        (swap! state update :fail-publish dec)
        (throw (ex-info "Simulated STM publication conflict"
                        {:code :STM_CONFLICT}))))
    (swap! state
           (fn [s]
             (action-permit s permit)
             (assoc-in s [:actions (:request-id permit)]
                       {:request-id (:request-id permit)
                        :phase :finished
                        :result result})))
    {:published true})
  (publish-failure! [board permit failure]
    (let [failures (get @state :fail-failure-publish 0)]
      (when (pos? failures)
        (swap! state update :fail-failure-publish dec)
        (throw (ex-info "Simulated STM failure publication conflict"
                        {:code :STM_CONFLICT}))))
    (swap! state
           (fn [s]
             (action-permit s permit)
             (assoc-in s [:actions (:request-id permit)]
                       {:request-id (:request-id permit)
                        :phase :uncertain
                        :result failure})))
    {:published true}))

(defn memory-board
  "Reference board adapter.  Production composes the real STM board instead."
  []
  (->MemoryBoard (atom {:actors {}
                        :claims {}
                        :actions {}
                        :fail-publish 0
                        :fail-failure-publish 0})))

(defn set-actor!
  [board actor-id alive?]
  (swap! (:state board) assoc-in [:actors actor-id :alive?] alive?))

(defn set-claim!
  [board claim-id valid?]
  (swap! (:state board) assoc-in [:claims claim-id] valid?))

(defn fail-publishes!
  [board n]
  (swap! (:state board) assoc :fail-publish n))

(defn fail-failure-publishes!
  "Simulate N STM conflicts while publishing a hard-failure result."
  [board n]
  (swap! (:state board) assoc :fail-failure-publish n))

(defn board-action
  [board request-id]
  (get-in @(:state board) [:actions request-id]))
