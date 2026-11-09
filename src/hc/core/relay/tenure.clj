(ns hc.core.relay.tenure
  "Tenure records, milestone ladders, and promotion.

  A tenure is the current incarnation of one lane.  Plan and execution are
  separate lanes; each has its own ledger, review window, and milestone
  ladder.  Retiring a tenure never mutates the other lane."
  (:require [hc.core.relay.ledger :as ledger]))

(def plan-milestones
  [:S1 :S2 :S3 :pending])

(def execution-milestones
  [:execution :closing :completed])

(defn milestones
  [lane]
  (case lane
    :plan plan-milestones
    :execution execution-milestones
    nil))

(defn role-for-lane
  [lane]
  (case lane
    :plan :plan
    :execution :reviewer
    nil))

(defn valid-milestone?
  [lane milestone]
  (boolean (some #{milestone} (milestones lane))))

(def statuses
  "Live tenure statuses.

  `:abandoned` is a terminal outcome like `:completed`; `:retired` is an
  archival value produced by `retire-tenure` and never stored in a live
  lane."
  #{:active :completed :abandoned})

(defn next-milestone
  [lane milestone]
  (let [ladder (milestones lane)
        index (first (keep-indexed (fn [i m] (when (= m milestone) i))
                                   ladder))]
    (when (and index (< (inc index) (count ladder)))
      (nth ladder (inc index)))))

(defn create-tenure
  "Create an active tenure with an empty private ledger and an open window."
  [{:keys [tenure-id epoch lane milestone initial?]}]
  (when-not (valid-milestone? lane milestone)
    (throw (ex-info "invalid tenure milestone"
                    {:lane lane :milestone milestone})))
  {:tenure-id tenure-id
   :epoch epoch
   :lane lane
   :role (role-for-lane lane)
   :milestone milestone
   :status :active
   :initial? (boolean initial?)
   :first-review? true
   :asked? false
   :awaiting-user? false
   :review-window :open
   :swarm-phase :none
   :relay-pending? false
   :ledger ledger/empty-ledger
   :root-tasks []})

(defn active?
  [tenure]
  (and tenure (= :active (:status tenure))))

(defn completed?
  [tenure]
  (and tenure (= :completed (:status tenure))))

(defn abandoned?
  [tenure]
  (and tenure (= :abandoned (:status tenure))))

(defn terminal?
  "True when the tenure cannot run another review or board command."
  [tenure]
  (or (completed? tenure) (abandoned? tenure)))

(defn retire-tenure
  "Retire a tenure.  The returned record is archival only; the live lane
  receives a fresh successor in the same atomic transition."
  [tenure]
  (assoc tenure
         :status :retired
         :review-window :closed
         :swarm-phase :none
         :relay-pending? false))

(defn abandon-tenure
  "Mark TENURE abandoned after REASON.

  The record stays in its lane as terminal evidence.  Its ledger, root-task
  mirror, review window, and swarm phase are cleared so the relay and board
  invariants remain true; the mission host removes the board tasks in the
  same transition.  AT is optional; a map REASON may carry it as `:at`."
  ([tenure reason]
   (abandon-tenure tenure reason nil))
  ([tenure reason at]
   (when tenure
     (assoc tenure
            :status :abandoned
            :abandoned-reason reason
            :retired-at (or at (when (map? reason) (:at reason)))
            :review-window :closed
            :swarm-phase :none
            :relay-pending? false
            :awaiting-user? false
            :ledger ledger/empty-ledger
            :root-tasks []))))

(defn promote-tenure
  "Advance one milestone in the tenure's lane.

  A successor's empty first review calls this.  The same tenure keeps its
  identity and receives a fresh first-review window for the next milestone.
  Promotion to `:completed` closes the lane."
  [tenure]
  (let [to (next-milestone (:lane tenure) (:milestone tenure))]
    (if-not to
      {:ok? false
       :error {:code :TERMINAL_MILESTONE
               :message "milestone has no successor"
               :details {:lane (:lane tenure)
                         :milestone (:milestone tenure)}}}
      (let [completed? (= :completed to)
            tenure' (assoc tenure
                           :milestone to
                           :first-review? (not completed?)
                           :asked? false
                           :awaiting-user? false
                           :review-window (if completed? :closed :open)
                           :swarm-phase :none
                           :relay-pending? false
                           :ledger ledger/empty-ledger
                           :root-tasks [])]
        {:ok? true
         :from (:milestone tenure)
         :to to
         :tenure (if completed?
                   (assoc tenure' :status :completed)
                   tenure')}))))
