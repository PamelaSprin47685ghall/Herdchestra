(ns hc.client.settings.commands
  "Settings command envelopes, outbox, and receipt bookkeeping.

  The UI never reports a save or test as successful by itself.  A command is
  queued only while the five-phase sync state is `:synced` and a command
  epoch exists.  A receipt, not a click, changes the domain state."
  (:require [hc.client.settings.util :as util]))

(def pending-statuses
  #{:queued :submitting})

(defn- epoch
  "Return the Owner command epoch.  The AG-UI feed epoch is not a command
  epoch and must never authorize a command."
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
   :error {:code :settings/not-synced
           :message "连接尚未完成同步；草稿已保留，同步后再提交。"}})

(defn new-command-id
  []
  (str (random-uuid)))

(defn command-key
  "Return the logical key used to avoid duplicate in-flight commands."
  [kind payload]
  [kind
   (or (:draft-id payload) (:connection-id payload) (:role payload)
       (:session-id payload))])

(defn pending-command-for
  [state key]
  (some (fn [[_ command]]
          (when (and (= key (:key command))
                     (contains? pending-statuses (:status command)))
            command))
        (:commands state)))

(defn queue
  "Queue one command.

  `opts` may supply `:command-id`, `:now`, and `:command-epoch`.  A supplied
  epoch is used verbatim for an Owner-scoped command such as
  `:session/select-model`; without it the global settings epoch applies."
  ([state kind payload] (queue state kind payload {}))
  ([state kind payload opts]
   (let [command-epoch (if (contains? opts :command-epoch)
                         (:command-epoch opts)
                         (epoch state))]
     (cond
       (not (command-allowed? state))
       (not-synced state)

       (not (pos-int? command-epoch))
       {:ok? false
        :state state
        :error {:code :settings/command-epoch-missing
                :message "该目标的命令纪元尚未同步；等待快照后再提交，草稿已保留。"
                :details {:command-epoch command-epoch}}}

       :else
       (let [key (command-key kind payload)
             pending (pending-command-for state key)]
         (if pending
           {:ok? false
            :state state
            :error {:code :settings/command-pending
                    :message "同一目标已有待确认命令；请等待回执或先核对结果。"
                    :details {:command-id (:command-id pending)}}}
           (let [command-id (or (:command-id opts) (new-command-id))
                 envelope {:command-id command-id
                           :command-epoch command-epoch
                           :payload (assoc payload :kind kind)}
                 command {:command-id command-id
                          :command-epoch command-epoch
                          :kind kind
                          :key key
                          :payload payload
                          :status :queued
                          :queued-at (:now opts)
                          :draft-id (:draft-id payload)
                          :draft-version (:draft-version payload)
                          :config-digest (:config-digest payload)}]
             {:ok? true
              :state (-> state
                         (assoc-in [:commands command-id] command)
                         (update :outbox (fnil conj []) envelope))
              :command command
              :envelope envelope})))))))

(defn pending-outbox
  [state]
  (vec (:outbox state)))

(defn redact-secret-payload
  "Remove credential values from a command payload kept in client state."
  [payload]
  (cond-> payload
    (seq (:credentials payload))
    (update :credentials
            (fn [credentials]
              (mapv (fn [credential]
                      (cond-> credential
                        (contains? credential :secret)
                        (assoc :secret "***")))
                    credentials)))))

(defn mark-submitting
  [state command-id]
  (-> state
      (update-in [:commands command-id]
                 (fn [command]
                   (cond-> command
                     (:payload command)
                     (update :payload redact-secret-payload)
                     true
                     (assoc :status :submitting))))
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
                   :last-error (util/error-message error))
        (update :outbox
                (fn [outbox]
                  (into [] (remove #(= command-id (:command-id %))) outbox))))
    state))

(defn unwrap-receipt
  "Flatten a gateway receipt.

  The gateway sends `{:ok? ... :command-id ... :receipt {...}}`; the inner
  receipt carries the domain status (`:complete`, `:accepted`, `:refused`)
  and test layers.  Keep outer identity and error fields while letting the
  inner domain fields win."
  [receipt]
  (cond
    (not (map? receipt)) receipt

    (map? (:hc/receipt receipt))
    (unwrap-receipt (:hc/receipt receipt))

    (map? (:receipt receipt))
    (let [outer (select-keys receipt [:ok? :error :replayed? :effects :status
                                      :command-id :command-epoch])
          inner (:receipt receipt)
          merged (merge outer inner)]
      (cond-> merged
        (and (nil? (:command-id merged)) (:command-id receipt))
        (assoc :command-id (:command-id receipt))

        (and (nil? (:command-epoch merged)) (:command-epoch receipt))
        (assoc :command-epoch (:command-epoch receipt))))

    :else receipt))

(defn receipt-status
  [receipt]
  (let [receipt (unwrap-receipt receipt)]
    (cond
      (not (map? receipt)) :invalid
      (false? (:ok? receipt)) :refused
      (some? (:error receipt)) :refused
      :else (or (:status receipt) :accepted))))

(defn record-receipt
  "Attach one receipt to its command and remove it from the outbox.

  Returns `{:state state' :command command :receipt receipt}`.  A receipt for
  an unknown command is kept in `:orphan-receipts` for diagnosis; it never
  changes a draft."
  [state receipt]
  (let [receipt (unwrap-receipt receipt)
        command-id (:command-id receipt)
        command (get-in state [:commands command-id])]
    (if-not command
      {:state (update state :orphan-receipts (fnil conj []) receipt)
       :command nil
       :receipt receipt}
      (let [status (receipt-status receipt)
            command (cond-> (assoc command
                                   :status status
                                   :receipt receipt
                                   :completed-at (:at receipt))
                      (:payload command)
                      (update :payload redact-secret-payload))]
        {:state (-> state
                    (assoc-in [:commands command-id] command)
                    (update :outbox
                            (fn [outbox]
                              (into [] (remove #(= command-id (:command-id %))) outbox))))
         :command command
         :receipt receipt}))))

(defn command-status-label
  [command]
  (case (:status command)
    :queued "等待发送"
    :submitting "正在提交"
    :accepted "已接受"
    :replayed "已接受（重复回执）"
    :refused "已拒绝"
    :outcome-unknown "结果未知，先核对"
    :cancelled "已取消"
    "状态未知"))

(defn error-message
  [receipt]
  (let [receipt (unwrap-receipt receipt)]
    (or (get-in receipt [:error :message])
        (:message receipt)
        "服务器未接受该命令")))
