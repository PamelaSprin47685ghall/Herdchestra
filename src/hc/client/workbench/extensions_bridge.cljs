(ns hc.client.workbench.extensions-bridge
  "Bridge between the extensions panel, Fulcro and Sente.

  The panel is useful only with a real server: after the first synchronized
  connection it asks for `:extensions/view`.  Every accepted mutation queues
  one authoritative view refresh.  Receipts are applied outside Sente's
  receive callback so a Fulcro transaction cannot race the command table."
  (:require [com.fulcrologic.fulcro.application :as application]
            [com.fulcrologic.fulcro.components :as comp]
            [hc.client.extensions :as extensions]
            [hc.client.extensions.model :as model]
            [hc.client.workbench.mutations :as mutations]
            [hc.client.workbench.model :as workbench-model]
            [hc.client.workbench.sync-bridge :as bridge]
            [hc.client.workbench.transport :as transport]
            [taoensso.timbre :as log]))

(defonce app-ref (atom nil))
(defonce last-state (atom nil))
(defonce fetched? (atom false))
(defonce last-subscription (atom nil))
(defonce seen-receipts (atom #{}))
(defonce queried-receipts (atom #{}))

(defn set-app!
  [app]
  (reset! app-ref app))

(defn- state-from-db
  [db]
  (let [assistants (or (get-in db [:ui/settings :assistants])
                       (get-in db [:ui/settings :server :assistants])
                       (:settings/assistants db)
                       [])
        selected (workbench-model/selected-session-id db)]
    (assoc (or (:ui/extensions db) (extensions/initial-state))
           :selected-session-id selected
           :assistants (vec (if (map? assistants)
                              (mapv (fn [[id a]] (assoc a :id (or (:id a) id)))
                                    assistants)
                              assistants)))))

(defn- transact-state!
  [state]
  (let [db (when-let [app @app-ref] (application/current-state app))
        current (or @last-state (:ui/extensions db))
        stale? (and (integer? (:version state))
                    (integer? (:version current))
                    (< (:version state) (:version current)))]
    (when-not stale?
      (reset! last-state state)
      (when-let [app @app-ref]
        (comp/transact! app
                        [(mutations/set-extensions-state
                          {:new-state state})])))))

(defn- notify!
  [tone text]
  (when-let [app @app-ref]
    (comp/transact! app
                    [(mutations/notify
                      {:notice {:id (str "ext-" (random-uuid))
                                :tone tone
                                :text text
                                :at (str (js/Date.))}})])))

(defn- submit!
  [command]
  (let [payload (:payload command)
        session-id (:session-id payload)
        session-command? (and (string? session-id)
                              (= "session" (namespace (:kind payload))))
        allowed? (bridge/command-allowed?)
        ;; A session command is fenced by that session Owner's epoch.  The
        ;; app epoch is not accepted by the Owner gate and must never be
        ;; substituted for it.
        epoch (if session-command?
                (bridge/command-epoch-for-session session-id)
                (bridge/command-epoch))
        sent? (and allowed?
                   (some? epoch)
                   (try
                     (transport/command!
                      {:command-id (:command-id command)
                       :command-epoch epoch
                       :subscription-id (bridge/subscription)
                       :payload payload})
                     (catch :default error
                       (log/warn "Extensions command transport failed" error)
                       false)))]
    (when-not sent?
      (notify! :warn
               (if (and session-command? (nil? epoch))
                 "扩展命令未发送：该会话的命令 epoch 尚未同步。"
                 "扩展命令未发送：实时通道未同步或已中断。")))
    (boolean sent?)))

(defn dispatch!
  "Apply one pure panel action, then submit every queued command."
  [action]
  (let [db (when-let [app @app-ref] (application/current-state app))
        current (or @last-state (state-from-db db))
        {:keys [state error]} (extensions/reduce-action current action)
        state (if error (assoc state :last-error error) state)
        state (extensions/flush-outbox! state submit!)]
    (transact-state! state)
    state))

(defn- receipt-event?
  [event]
  (let [type (or (:type event) (:event event))
        name (or (:name event) (:event event))]
    (or (contains? #{:hc/receipt :hc/command-receipt
                     "hc/receipt" "hc/command-receipt"}
                   type)
        (contains? #{:hc/receipt :hc/command-receipt
                     "hc/receipt" "hc/command-receipt"}
                   name))))

(defn- apply-receipt-event!
  [event]
  (let [receipt (or (:value event) (:receipt event) event)
        command-id (or (:command-id receipt) (:commandId receipt))
        status (get-in receipt [:receipt :status])
        receipt-key [command-id status]
        db (when-let [app @app-ref] (application/current-state app))
        current (or @last-state (state-from-db db))
        state (extensions/apply-receipt current event)
        state (extensions/flush-outbox! state submit!)
        command-status (get-in state [:commands command-id :status])]
    (when-not (contains? @seen-receipts receipt-key)
      (swap! seen-receipts conj receipt-key)
      (transact-state! state)
      (when (= :unknown command-status)
        ;; A missing receipt is an unknown outcome.  Ask the gateway for the
        ;; same command-id once; never resend the command or start a loop.
        (when (and command-id (not (contains? @queried-receipts command-id)))
          (swap! queried-receipts conj command-id)
          (transport/receipt! {:command-id command-id
                               :subscription-id (bridge/subscription)})))
      (when-let [error (:last-error state)]
        (notify! :danger (or (:message error) "扩展命令失败"))))))

(defn handle-event!
  "Apply a command receipt.  Unrelated receipts are ignored."
  [event]
  (when (receipt-event? event)
    (js/setTimeout #(apply-receipt-event! event) 0)))

(defn sync-from-db!
  "After the connection is synchronized, request one authoritative view."
  [db]
  (let [current (or @last-state (state-from-db db))
        selected (workbench-model/selected-session-id db)
        selection-changed? (not= selected (:selected-session-id current))
        allowed? (boolean (get-in db [:ui/connection :command-allowed?]))
        subscription (get-in db [:ui/connection :subscription])
        new-subscription? (not= subscription @last-subscription)
        state (if selection-changed?
                (model/switch-session current selected)
                (assoc current :selected-session-id selected))
        state (assoc state
                     :sync {:phase (get-in db [:ui/connection :phase])
                            :command-epoch (get-in db [:ui/connection :command-epoch])}
                     :transport-available? allowed?)]
    (when new-subscription?
      (reset! last-subscription subscription)
      (reset! fetched? false)
      (reset! seen-receipts #{})
      (reset! queried-receipts #{}))
    (when selection-changed?
      (reset! fetched? false))
    (transact-state! state)
    (cond
      (not allowed?)
      (reset! fetched? false)

      (and (not @fetched?) (or new-subscription? selection-changed?))
      (do
        (reset! fetched? true)
      (js/setTimeout
       (fn []
         (let [db (when-let [app @app-ref] (application/current-state app))]
           (if (get-in db [:ui/connection :command-allowed?])
             ;; One automatic authoritative read per subscription.  A server
             ;; refusal stays visible and is retried only by an explicit user
             ;; action or a new subscription; it never becomes a retry loop.
             (dispatch! {:type :extensions/refresh})
             (reset! fetched? false))))
       0))

      :else nil)))

(defn install!
  [app]
  (reset! fetched? false)
  (reset! last-subscription nil)
  (reset! seen-receipts #{})
  (reset! queried-receipts #{})
  (reset! last-state nil)
  (set-app! app)
  (bridge/ensure-event-listener! ::receipt handle-event!))
