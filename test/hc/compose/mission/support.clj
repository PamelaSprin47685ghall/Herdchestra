(ns hc.compose.mission.support
  "Deterministic drivers for the mission host tests."
  (:require [hc.compose.mission :as mission]))

(def ^:private call-counter (atom 0))

(defn call-id
  []
  (str "call-" (swap! call-counter inc)))

(defn models
  []
  {:main "main-model"
   :task "task-model"})

(defn host
  ([] (host {}))
  ([opts]
   (mission/create
    (merge {:session-id "mission-test"
            :user-input "Build the requested feature."
            :successor-hint "Inspect the current workspace independently."
            :models (models)}
           opts))))

(defn plan-tenure-id
  [h]
  (-> (mission/snapshot h) :relay :lanes :plan :tenure-id))

(defn execution-tenure-ids
  [h]
  (keys (get-in (mission/snapshot h) [:relay :lanes :execution])))

(defn sole-execution-id
  [h]
  (let [ids (execution-tenure-ids h)]
    (when-not (= 1 (count ids))
      (throw (ex-info "expected one execution tenure"
                      {:ids (vec ids)})))
    (first ids)))

(defn review!
  ([h lane tenure-id findings]
   (review! h lane tenure-id findings nil))
  ([h lane tenure-id findings board-facts]
   (mission/review! h
                    (cond-> {:lane lane
                             :tenure-id tenure-id
                             :call-id (call-id)
                             :findings findings}
                      board-facts (assoc :board-facts board-facts)))))

(defn drive-plan-to-pending!
  "Drive an initial Plan from S1 to :pending.

  The first empty review asks the operator; the host then resumes.  The next
  empty review relays to a successor Plan tenure.  That successor promotes
  S1 -> S2, then writes the draft, then promotes S2 -> S3 -> :pending.
  Returns H."
  [h]
  (let [tenure-id (plan-tenure-id h)
        asked (review! h :plan tenure-id [])]
    (when-not (= :ask (get-in asked [:receipt :outcome]))
      (throw (ex-info "expected initial Plan ask"
                      {:result asked})))
    (mission/resume! h {:lane :plan :tenure-id tenure-id})
    (review! h :plan (plan-tenure-id h) [])
    (review! h :plan (plan-tenure-id h) [])
    (let [draft (mission/set-draft! h {:path "plans/mission-smoke.md"
                                       :content "Draft body for the smoke test."
                                       :tenure-id (plan-tenure-id h)})]
      (when-not (:ok? draft)
        (throw (ex-info "draft write failed" {:result draft}))))
    (review! h :plan (plan-tenure-id h) [])
    (review! h :plan (plan-tenure-id h) [])
    h))

(defn start-execution!
  [h]
  (let [result (review! h :plan (plan-tenure-id h) [])]
    (when-not (= :execution-started (get-in result [:receipt :outcome]))
      (throw (ex-info "expected execution handoff" {:result result})))
    result))

(defn start-swarm!
  [h tenure-id findings]
  (let [result (review! h :execution tenure-id findings)]
    (when-not (= :swarm-started (get-in result [:receipt :outcome]))
      (throw (ex-info "expected swarm start" {:result result})))
    result))

(defn register-worker!
  [h tenure-id slot-id]
  (let [result (mission/register-swarm-actor! h {:tenure-id tenure-id
                                                 :slot-id slot-id})]
    (when-not (:ok? result)
      (throw (ex-info "actor registration failed" {:result result})))
    (get-in result [:receipt :agent-id])))

(defn board
  [h tenure-id]
  (get-in (mission/snapshot h) [:boards tenure-id]))

(defn board-tasks
  [h tenure-id]
  (vals (get-in (board h tenure-id) [:tasks])))

(defn root-task-ids
  [h tenure-id]
  (->> (board-tasks h tenure-id)
       (filter #(nil? (:parent-id %)))
       (map :task-id)
       vec))

(defn finish-task!
  [h tenure-id actor-id task-id claim-id summary]
  (mission/finish-root-task! h
                             {:tenure-id tenure-id
                              :actor-id actor-id
                              :task-id task-id
                              :claim-id claim-id
                              :result-summary summary
                              :board-facts {:ff-ready? true}}))

(defn claim-and-finish-root!
  "Claim and finish one ready root task.  Returns the finish result."
  [h tenure-id actor-id task-id]
  (let [claimed (mission/claim-task! h {:tenure-id tenure-id
                                        :actor-id actor-id
                                        :task-id task-id
                                        :atomicity-reason
                                        "one root task is one atomic result"})]
    (when-not (:ok? claimed)
      (throw (ex-info "claim failed" {:result claimed})))
    (finish-task! h tenure-id actor-id task-id
                  (get-in claimed [:receipt :claim-id])
                  "Root task completed.")))

(defn quiescent-facts
  []
  {:ff-ready? true
   :call-children 0
   :report-pending 0
   :pending-rebase 0
   :holds-write-lock? false})
