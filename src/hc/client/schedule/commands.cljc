(ns hc.client.schedule.commands
  "Scheduler command envelopes, outbox, and receipt bookkeeping.

  A command is queued only while the five-phase sync state is `:synced` and a
  command epoch exists.  A receipt, not a click, changes a schedule or run.
  The namespace is pure; the bridge owns the Sente handoff."
  (:require [clojure.string :as str]))

(def pending-statuses
  #{:queued :submitting})

(defn epoch
  "The Owner command epoch only.  The AG-UI feed epoch is not a substitute:
  the server rejects a command whose epoch does not match the Owner's."
  [state]
  (get-in state [:sync :command-epoch]))

(defn command-allowed?
  "True only when the latest server state is confirmed."
  [state]
  (and (= :synced (get-in state [:sync :phase]))
       (pos-int? (epoch state))))

(defn not-synced
  [state]
  {:ok? false
   :state state
   :error {:code :schedule/not-synced
           :message "连接尚未完成同步；草稿已保留，同步后再提交。"}})

(defn new-command-id
  []
  (str (random-uuid)))

(defn command-key
  "Logical key used to avoid duplicate in-flight commands."
  [kind payload]
  [kind
   (or (:schedule-id payload)
       (:run-id payload)
       (:session-id payload)
       (get-in payload [:schedule :id])
       (get-in payload [:opts :schedule-id])
       :global)])

(defn pending-command-for
  [state key]
  (some (fn [[_ command]]
          (when (and (= key (:key command))
                     (contains? pending-statuses (:status command)))
            command))
        (:commands state)))

(defn queue
  "Queue one command.  `opts` may supply `:command-id` and `:now`."
  ([state kind payload] (queue state kind payload {}))
  ([state kind payload opts]
   (cond
     (not (command-allowed? state))
     (not-synced state)

     (nil? (epoch state))
     (not-synced state)

     :else
     (let [key (command-key kind payload)
           pending (pending-command-for state key)]
       (if pending
         {:ok? false
          :state state
          :error {:code :schedule/command-pending
                  :message "同一目标已有待确认命令；请等待回执或先核对结果。"
                  :details {:command-id (:command-id pending)}}}
         (let [command-id (or (:command-id opts) (new-command-id))
               envelope {:command-id command-id
                         :command-epoch (epoch state)
                         :payload (assoc payload :kind kind)}
               command {:command-id command-id
                        :command-epoch (epoch state)
                        :kind kind
                        :key key
                        :payload payload
                        :status :queued
                        :queued-at (:now opts)}]
           {:ok? true
            :state (-> state
                       (assoc-in [:commands command-id] command)
                       (update :outbox (fnil conj []) envelope))
            :command command
            :envelope envelope}))))))

(defn pending-outbox
  [state]
  (vec (:outbox state)))

(defn mark-submitting
  [state command-id]
  (-> state
      (update-in [:commands command-id] assoc :status :submitting)
      (update :outbox
              (fn [outbox]
                (into [] (remove #(= command-id (:command-id %))) outbox)))))

(defn mark-outcome-unknown
  "A thrown transport error may have happened after the server accepted the
  command.  Keep the draft and ask the user to check the target state."
  [state command-id error]
  (if (contains? (:commands state) command-id)
    (-> state
        (update-in [:commands command-id]
                   assoc
                   :status :outcome-unknown
                   :last-error (or (some-> error :message)
                                   (str error)))
        (update :outbox
                (fn [outbox]
                  (into [] (remove #(= command-id (:command-id %)) outbox)))))
    state))

(defn receipt-command-id
  [receipt]
  (or (:command-id receipt)
      (get-in receipt [:value :command-id])
      (get-in receipt [:receipt :command-id])))

(defn receipt-value
  "Return the domain value carried by a receipt event or command result."
  [receipt]
  (let [value (or (:value receipt)
                  (:data receipt)
                  receipt)
        inner (:receipt value)]
    (cond
      (map? (:result inner)) (:result inner)
      (map? inner) inner
      :else value)))

(defn record-receipt
  "Record one receipt.  Returns `{:state state' :command command :receipt value}`.
  An unknown command id leaves the state unchanged."
  [state receipt]
  (let [command-id (receipt-command-id receipt)
        command (get-in state [:commands command-id])
        value (receipt-value receipt)]
    (if (nil? command)
      {:state state :command nil :receipt value}
      {:state (-> state
                  (update-in [:commands command-id]
                             merge
                             {:status (let [status (some-> (:status value) keyword)]
                                        (cond
                                          (or (false? (:ok? value))
                                              (contains? #{:rejected :refused
                                                           :failed}
                                                         status))
                                          :failed

                                          (contains? #{:unknown :outcome-unknown}
                                                     status)
                                          :outcome-unknown

                                          :else
                                          :applied))
                              :receipt value
                              :updated-at (or (:now receipt)
                                              (get-in state [:ui :now]))})
                  (update :outbox
                          (fn [outbox]
                            (into [] (remove #(= command-id (:command-id %)))
                                  outbox))))
       :command command
       :receipt value})))

(defn refused
  [state error]
  {:state state
   :effects []
   :error error})
