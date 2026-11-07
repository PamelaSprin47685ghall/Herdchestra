(ns hc.compose.reports.delivery
  "Child-side :report-pending delivery state machine.

  The child fixes its terminal state and registers one report.  The
  supervisor repeatedly reads `next-action`.  It delivers every pending
  report and removes the durable report record after an acknowledgement.

  Delivery is level-triggered.  Until `apply-ack` commits, the same report
  is returned again, so a crash at any gap loses nothing.  `apply-ack` moves
  a report to `:acknowledged`.  `mark-reclaimed` removes it.  The
  acknowledged record stays until reclamation closes the crash window
  between clearing :report-pending and removing the record.  Runtime
  resources are released by the child's terminal/cancel path before the
  report is registered; this protocol carries no live resource handle."
  (:require [hc.compose.reports.report :as report]))

(def empty-state
  "No report responsibility."
  {:reports {}
   :closed {:parent-call-ids #{}
            :report-ids #{}}})

(defn reports
  "The report-id -> report map."
  [state]
  (or (:reports state) {}))

(defn closed
  "Parent-call-ids and report-ids that were already reclaimed.

  A tombstone stops a late duplicate registration from creating a new
  pending report that the parent can only reject as a conflict."
  [state]
  (let [closed (or (:closed state) {})]
    {:parent-call-ids (or (:parent-call-ids closed) #{})
     :report-ids (or (:report-ids closed) #{})}))

(defn- sorted-by-report-id
  [entries]
  (sort-by :report-id entries))

(defn pending-reports
  "Reports whose parent has not acknowledged them yet, ordered by report-id."
  [state]
  (->> (vals (reports state))
       (filter #(= :pending (:phase %)))
       sorted-by-report-id
       vec))

(defn acknowledged-reports
  "Reports that wait only for resource reclamation, ordered by report-id."
  [state]
  (->> (vals (reports state))
       (filter #(= :acknowledged (:phase %)))
       sorted-by-report-id
       vec))

(defn report-entry
  "Return one report record, or nil."
  [state report-id]
  (get (reports state) report-id))

(defn register
  "Register a terminal report as pending delivery.

  Registration is idempotent for the exact same report.  A different report
  with the same `:report-id`, or a second report for the same
  `:parent-call-id`, is rejected.  The parent accepts at most one report per
  call, so a second report for that call could never close.

  A report-id or parent-call-id that was already acknowledged and reclaimed
  is a terminal refusal: the parent has already closed that responsibility."
  [state raw-report]
  (let [state (or state empty-state)
        validated (report/validate-report raw-report)]
    (if-not (:ok? validated)
      (assoc validated :state state)
      (let [entry (assoc (:report validated) :phase :pending :ack nil)
            report-id (:report-id entry)
            parent-call-id (:parent-call-id entry)
            closed* (closed state)
            existing (get (reports state) report-id)
            same-parent (some (fn [candidate]
                                (when (= parent-call-id
                                         (:parent-call-id candidate))
                                  candidate))
                              (vals (reports state)))]
        (cond
          (contains? (:report-ids closed*) report-id)
          {:ok? false
           :state state
           :error {:code :REPORT_ID_CLOSED
                   :terminal? true
                   :message "the report-id was already acknowledged and reclaimed"
                   :details {:report-id report-id
                             :terminal? true}}}

          (contains? (:parent-call-ids closed*) parent-call-id)
          {:ok? false
           :state state
           :error {:code :PARENT_CALL_CLOSED
                   :terminal? true
                   :message "the parent call already acknowledged and reclaimed a report"
                   :details {:parent-call-id parent-call-id
                             :terminal? true}}}

          existing
          (if (= (dissoc existing :phase :ack)
                 (dissoc entry :phase :ack))
            {:ok? true :outcome :existing :state state :report existing}
            {:ok? false
             :state state
             :error {:code :REPORT_ID_CONFLICT
                     :message "a different report already uses this :report-id"
                     :details {:report-id report-id
                               :existing-payload (:payload existing)
                               :incoming-payload (:payload entry)}}})

          same-parent
          {:ok? false
           :state state
           :error {:code :PARENT_CALL_CONFLICT
                   :message "the parent call already owns a report"
                   :details {:parent-call-id parent-call-id
                             :report-id (:report-id same-parent)}}}

          :else
          {:ok? true
           :outcome :registered
           :state (assoc state :reports (assoc (reports state) report-id entry))
           :report entry})))))

(defn next-action
  "Return the next supervisor action.

  Durable record reclamation comes first.  Otherwise deliver the lowest
  `:report-id` pending report.  `:none` means no live report responsibility
  remains."
  [state]
  (let [state (or state empty-state)]
    (if-let [entry (first (acknowledged-reports state))]
      {:action :reclaim
       :report-id (:report-id entry)
       :parent-call-id (:parent-call-id entry)}
      (if-let [entry (first (pending-reports state))]
        {:action :deliver
         :report entry}
        {:action :none}))))

(defn- ack-core
  [ack]
  (select-keys ack [:report-id :parent-call-id :status]))

(defn- merge-routing
  "Merge routing metadata without changing the idempotency key."
  [stored incoming]
  (if (and (contains? incoming :child-session-id)
           (not= (:child-session-id stored)
                 (:child-session-id incoming)))
    (assoc stored :child-session-id (:child-session-id incoming))
    stored))

(defn apply-ack
  "Apply a parent acknowledgement.

  A first valid acknowledgement moves the report to `:acknowledged` and
  returns a `:reclaim` action.  A repeated acknowledgement with the same
  `:report-id`, `:parent-call-id`, and `:status` is a no-op; routing metadata
  such as `:child-session-id` is merged separately and never changes the
  idempotency key.  An acknowledgement for a report this Owner no longer
  holds is also a no-op: the parent may redeliver after the child already
  reclaimed."
  [state raw-ack]
  (let [state (or state empty-state)
        validated (report/validate-ack raw-ack)]
    (if-not (:ok? validated)
      (assoc validated :state state)
      (let [ack (:ack validated)
            report-id (:report-id ack)
            entry (get (reports state) report-id)]
        (cond
          (nil? entry)
          {:ok? true :outcome :unknown-report :state state :ack ack}

          (not= (:parent-call-id entry) (:parent-call-id ack))
          {:ok? false
           :state state
           :error {:code :ACK_MISMATCH
                   :message "acknowledgement :parent-call-id does not match the report"
                   :details {:report-id report-id
                             :expected (:parent-call-id entry)
                             :actual (:parent-call-id ack)}}}

          (= :acknowledged (:phase entry))
          (if (= (ack-core (:ack entry)) (ack-core ack))
            (let [merged (merge-routing (:ack entry) ack)
                  state' (if (= merged (:ack entry))
                           state
                           (assoc-in state
                                     [:reports report-id :ack]
                                     merged))]
              {:ok? true :outcome :duplicate :state state' :ack merged})
            {:ok? false
             :state state
             :error {:code :ACK_MISMATCH
                     :message "the report already has a different acknowledgement"
                     :details {:report-id report-id
                               :existing (:ack entry)
                               :incoming ack}}})

          :else
          (let [entry' (assoc entry :phase :acknowledged :ack ack)
                state' (assoc state :reports (assoc (reports state) report-id entry'))]
            {:ok? true
             :outcome :acknowledged
             :state state'
             :ack ack
             :action {:action :reclaim
                      :report-id report-id
                      :parent-call-id (:parent-call-id entry')}}))))))

(defn mark-reclaimed
  "Record that the report's durable pending record is closed.

  Only an acknowledged report can be reclaimed.  An absent report is already
  reclaimed and therefore a no-op.  The report-id and parent-call-id become
  tombstones so a late duplicate cannot open a new pending report that the
  parent would only reject as a conflict."
  [state report-id]
  (let [state (or state empty-state)
        entry (get (reports state) report-id)]
    (cond
      (nil? entry)
      {:ok? true :outcome :already-reclaimed :state state}

      (= :acknowledged (:phase entry))
      (let [closed* (closed state)
            closed' (-> closed*
                        (update :parent-call-ids conj (:parent-call-id entry))
                        (update :report-ids conj report-id))]
        {:ok? true
         :outcome :reclaimed
         :state (-> state
                    (assoc :reports (dissoc (reports state) report-id))
                    (assoc :closed closed'))
         :report entry})

      :else
      {:ok? false
       :state state
       :error {:code :REPORT_NOT_ACKNOWLEDGED
               :message "resources cannot be reclaimed before the parent acknowledges"
               :details {:report-id report-id
                         :phase (:phase entry)}}})))

(defn closed?
  "True when no live report responsibility remains.

  Tombstones are not responsibilities; they only reject late duplicates."
  [state]
  (empty? (reports (or state empty-state))))
