(ns hc.client.backup.commands
  "Backup command envelopes, outbox and receipt bookkeeping.

  A command is queued only while the five-phase sync state is `:synced` and a
  positive command epoch exists.  The click never claims success: a receipt
  changes the domain state, and a missing receipt leaves the command
  `:outcome-unknown` so the user checks the target instead of replaying it."
  (:require [clojure.string :as str]))

(def pending-statuses
  #{:queued :submitting})

(defn epoch
  "Only the snapshot command epoch authorizes a backup command.  A feed
  `:epoch` describes the event stream and is not a command authority."
  [state]
  (get-in state [:sync :command-epoch]))

(defn command-allowed?
  "True only when the latest server state is confirmed."
  [state]
  (and (= :synced (get-in state [:sync :phase]))
       (pos-int? (or (epoch state) 0))))

(defn new-command-id
  []
  (str (random-uuid)))

(defn command-key
  "Logical key used to refuse duplicate in-flight commands.  Different backup
  targets do not block each other; a target-less command uses `:global`."
  [kind payload]
  [kind
   (or (:target-id payload) :global)
   (or (:backup-id payload)
       (:recovery-point-id payload)
       (:command-key payload)
       :global)])

(defn pending-command-for
  [state key]
  (some (fn [[_ command]]
          (when (and (= key (:key command))
                     (contains? pending-statuses (:status command)))
            command))
        (:commands state)))

(defn- not-synced
  [state]
  {:ok? false
   :state state
   :error {:code :backup/not-synced
           :message "连接尚未完成同步；表单内容保留，同步后再提交。"}})

(defn queue
  "Queue one command.  Returns `{:ok? true :state ... :command ... :envelope ...}`
  or a refusal that leaves the state unchanged."
  ([state kind payload] (queue state kind payload {}))
  ([state kind payload opts]
   (cond
     (not (command-allowed? state))
     (not-synced state)

     (str/blank? (str (epoch state)))
     (not-synced state)

     :else
     (let [key (command-key kind payload)
           pending (pending-command-for state key)]
       (if pending
         {:ok? false
          :state state
          :error {:code :backup/command-pending
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
      (assoc-in [:commands command-id :status] :submitting)
      (update :outbox
              (fn [outbox]
                (vec (remove #(= command-id (:command-id %)) outbox))))))

(defn record-receipt
  "Record one receipt.  Returns the next state plus the matching command.

  A receipt with `:ok? false` is `:rejected`; it never becomes a success.
  Unknown command IDs are returned with `:command nil` and do not change the
  outbox."
  [state receipt]
  (let [command-id (or (:command-id receipt)
                       (get-in receipt [:value :command-id])
                       (get-in receipt [:receipt :command-id]))
        command (when command-id (get-in state [:commands command-id]))
        ok? (if (contains? receipt :ok?)
              (true? (:ok? receipt))
              (not (contains? receipt :error)))
        status (cond
                 (not ok?) :rejected
                 (contains? #{:failed :rejected} (:status receipt)) :rejected
                 (contains? #{:queued :submitting} (:status receipt)) :submitted
                 :else :applied)]
    {:state (if command
              (-> state
                  (assoc-in [:commands command-id :status] status)
                  (assoc-in [:commands command-id :receipt] receipt)
                  (assoc-in [:commands command-id :error] (:error receipt))
                  (update :outbox
                          (fn [outbox]
                            (vec (remove #(= command-id (:command-id %))
                                         outbox)))))
              state)
     :command command
     :receipt receipt
     :ok? ok?
     :error (:error receipt)}))

(defn mark-outcome-unknown
  "The transport threw after handoff.  Keep the command pending and require
  the user to check the target before retrying."
  [state command-id error]
  (-> state
      (assoc-in [:commands command-id :status] :outcome-unknown)
      (assoc-in [:commands command-id :error]
                {:code :backup/outcome-unknown
                 :message (or (some-> error :message)
                              (str error)
                              "提交结果未知")
                 :details {:command-id command-id}})
      (update :outbox
              (fn [outbox]
                (vec (remove #(= command-id (:command-id %)) outbox))))))
