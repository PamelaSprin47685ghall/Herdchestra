(ns hc.compose.reports.acceptance
  "Parent-side idempotent acceptance of cross-Owner reports.

  The parent accepts exactly one report per `:parent-call-id`.  The first
  acceptance stores the full payload and returns an acknowledgement.  Every
  redelivery returns the same acknowledgement and never appends again.

  A terminated parent still records and acknowledges the report, with
  `:parent-terminated`, so the child can reclaim its resources instead of
  waiting forever.  The caller also passes `:call-active? false` for a call
  that no longer exists.  A late report must not revive a closed call.

  The acceptance result must be committed with the parent's authoritative
  state before the acknowledgement leaves the Owner.  The conversation
  append is a separate projection.  `pending-projections` recovers that
  projection after a crash between acceptance and append."
  (:require [hc.compose.reports.report :as report]))

(def empty-state
  "No accepted report and no termination."
  {:terminated? false
   :accepted {}})

(defn accepted-entries
  "The parent-call-id -> acceptance map."
  [state]
  (or (:accepted state) {}))

(defn terminated?
  "True when the parent tenure is closed."
  [state]
  (true? (:terminated? (or state empty-state))))

(defn terminate
  "Close the parent tenure.

  Later reports are acknowledged as `:parent-terminated`.  Accepted reports
  that were not projected yet are skipped: a terminated parent must not
  append to its conversation."
  [state]
  (let [state (or state empty-state)]
    (-> state
        (assoc :terminated? true)
        (update :accepted
                (fn [entries]
                  (into {}
                        (map (fn [[parent-call-id entry]]
                               [parent-call-id
                                (if (= :pending (:projection entry))
                                  (assoc entry :projection :skipped)
                                  entry)]))
                        (or entries {})))))))

(defn accepted-report
  "Return the acceptance entry for one parent call, or nil."
  [state parent-call-id]
  (get (accepted-entries state) parent-call-id))

(defn- merge-routing
  "Merge a later child-session-id without changing accepted content."
  [entry incoming]
  (let [child (or (:child-session-id incoming)
                  (:child-session-id entry)
                  (get-in entry [:ack :child-session-id]))]
    (cond-> entry
      (and child (not= child (get-in entry [:ack :child-session-id])))
      (assoc-in [:ack :child-session-id] child)

      (and child (not= child (:child-session-id entry)))
      (assoc :child-session-id child))))

(defn pending-projections
  "Accepted reports whose full payload is not yet in the parent conversation.

  A terminated parent has none.  The caller projects each payload exactly
  once, then calls `mark-projected`."
  [state]
  (let [state (or state empty-state)]
    (if (terminated? state)
      []
      (->> (accepted-entries state)
           (keep (fn [[parent-call-id entry]]
                   (when (= :pending (:projection entry))
                     (assoc entry :parent-call-id parent-call-id))))
           (sort-by :parent-call-id)
           vec))))

(defn accept-report
  "Accept one report exactly once per `:parent-call-id`.

  The first acceptance stores the full payload and returns an
  acknowledgement.  A duplicate delivery returns the same acknowledgement
  and `:append? false`.  A terminated tenure or `:call-active? false`
  returns `:parent-terminated`, also with `:append? false`.

  A different report for the same parent call is a protocol conflict.  The
  state is unchanged and the child keeps its pending record, so the report
  is not lost.  A redelivery with the same `:report-id` but a changed
  payload or terminal is also a conflict; idempotency never hides changed
  content."
  ([state raw-report]
   (accept-report state raw-report {}))
  ([state raw-report {:keys [call-active?] :or {call-active? true}}]
   (let [state (or state empty-state)
         validated (report/validate-report raw-report)]
     (if-not (:ok? validated)
       (assoc validated :state state)
       (let [incoming (:report validated)
             parent-call-id (:parent-call-id incoming)
             report-id (:report-id incoming)
             entries (accepted-entries state)
             existing (get entries parent-call-id)
             same-id (some (fn [[accepted-parent-call-id accepted]]
                             (when (= report-id (:report-id accepted))
                               [accepted-parent-call-id accepted]))
                           entries)]
         (cond
           existing
           (if (and (= report-id (:report-id existing))
                    (= (:payload existing) (:payload incoming))
                    (= (:terminal existing) (:terminal incoming)))
             (let [entry' (merge-routing existing incoming)
                   state' (if (= entry' existing)
                            state
                            (assoc-in state
                                      [:accepted parent-call-id]
                                      entry'))]
               {:ok? true
                :outcome :duplicate
                :state state'
                :ack (:ack entry')
                :append? false})
             {:ok? false
              :state state
              :error {:code :REPORT_CONFLICT
                      :message (if (= report-id (:report-id existing))
                                 "the parent call already accepted different content for this report-id"
                                 "the parent call already accepted a different report")
                      :details {:parent-call-id parent-call-id
                                :accepted-report-id (:report-id existing)
                                :incoming-report-id report-id
                                :reason (if (= report-id (:report-id existing))
                                          :report-content-mismatch
                                          :different-report)}}})

           same-id
           {:ok? false
            :state state
            :error {:code :REPORT_ID_CONFLICT
                    :message "the :report-id was already accepted under another parent call"
                    :details {:report-id report-id
                              :accepted-parent-call-id (first same-id)
                              :incoming-parent-call-id parent-call-id}}}

           :else
           (let [status (if (or (terminated? state) (false? call-active?))
                          :parent-terminated
                          :accepted)
                 ack (cond-> (report/make-ack report-id parent-call-id status)
                       (contains? incoming :child-session-id)
                       (assoc :child-session-id (:child-session-id incoming)))
                 accepted (cond-> {:report-id report-id
                                   :parent-call-id parent-call-id
                                   :payload (:payload incoming)
                                   :ack ack
                                   :projection (if (= :accepted status)
                                                 :pending
                                                 :skipped)}
                            (contains? incoming :child-session-id)
                            (assoc :child-session-id
                                   (:child-session-id incoming))
                            (contains? incoming :terminal)
                            (assoc :terminal (:terminal incoming)))]
             {:ok? true
              :outcome status
              :state (assoc state :accepted
                            (assoc entries parent-call-id accepted))
              :ack ack
              :append? (= :accepted status)
              :entry accepted})))))))

(defn mark-projected
  "Record that the accepted payload is now in the parent conversation.

  The call is idempotent.  A skipped or terminated acceptance is
  `:not-applicable`."
  [state parent-call-id]
  (let [state (or state empty-state)
        entry (get (accepted-entries state) parent-call-id)]
    (cond
      (nil? entry)
      {:ok? false
       :state state
       :error {:code :UNKNOWN_ACCEPTANCE
               :message "no report was accepted for this parent call"
               :details {:parent-call-id parent-call-id}}}

      (= :projected (:projection entry))
      {:ok? true :outcome :already-projected :state state}

      (= :skipped (:projection entry))
      {:ok? true :outcome :not-applicable :state state}

      (terminated? state)
      {:ok? true
       :outcome :not-applicable
       :state (assoc-in state
                        [:accepted parent-call-id :projection]
                        :skipped)}

      :else
      {:ok? true
       :outcome :projected
       :state (assoc-in state
                        [:accepted parent-call-id :projection]
                        :projected)})))
