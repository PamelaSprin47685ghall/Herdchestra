(ns hc.client.workbench.browser-bridge
  "Bridge between the managed-browser UI and the Sente command channel.

  The bridge projects confirmed `:browser/snapshot` / `:browser/cards`
  results into `:ui/browser`, submits queued commands through the existing
  transport, and feeds receipts back to the pure browser model.  A command is
  never marked applied from a local click."
  (:require [com.fulcrologic.fulcro.application :as application]
            [com.fulcrologic.fulcro.components :as comp]
            [clojure.string :as str]
            [hc.client.browser :as browser]
            [hc.client.browser.model :as model]
            [hc.client.workbench.model :as workbench-model]
            [hc.client.workbench.mutations :as mutations]
            [hc.client.workbench.sync-bridge :as bridge]
            [hc.client.workbench.transport :as transport]
            [taoensso.timbre :as log]))

(defonce app-ref
  (atom nil))

(defonce last-state
  (atom nil))

(defonce last-session
  (atom nil))

(defonce last-fetch-revision
  (atom nil))

(defonce fetch-blocked?
  (atom false))

(defn set-app!
  [app]
  (reset! app-ref app))

(defn- state-from-db
  [db]
  (let [session-id (workbench-model/selected-session-id db)]
    ;; The first baseline may set a session id after the pane mounted.  That
    ;; is not a session switch and must not discard a local permission form
    ;; or a selected observation.  A real switch (A -> B) still resets.
    (when (and (some? @last-session)
               (not= session-id @last-session))
      (reset! last-session session-id)
      (reset! last-state (browser/initial-state))
      (reset! last-fetch-revision nil)
      (reset! fetch-blocked? false))
    (when (and (nil? @last-session) (some? session-id))
      (reset! last-session session-id))
    (let [state (or @last-state (:ui/browser db) (browser/initial-state))
          phase (get-in db [:ui/connection :phase])
          epoch (bridge/command-epoch-for-session session-id)]
      (browser/apply-connection state phase epoch session-id))))

(defn- transact-state!
  [state]
  (reset! last-state state)
  (when-let [app @app-ref]
    (comp/transact! app
                    [(mutations/set-browser-state {:new-state state})])))

(defn- notify!
  [tone text]
  (when-let [app @app-ref]
    (comp/transact! app
                    [(mutations/notify
                      {:notice {:id (str "browser-" (random-uuid))
                                :tone tone
                                :text text
                                :at (str (js/Date.))}})])))

(defn- submit!
  [envelope]
  (let [sent? (try
                (transport/command! envelope)
                (catch :default error
                  (log/warn "Browser command transport failed" error)
                  false))]
    (when-not sent?
      (notify! :warn "浏览器命令未发送：实时通道未打开或已中断。"))
    (boolean sent?)))

(defn- flush-outbox!
  [state]
  (if-not (browser/command-allowed? state)
    state
    (loop [state state]
      (if-let [envelope (first (browser/pending-outbox state))]
        (let [command-id (:command-id envelope)
              state (browser/mark-submitting state command-id)
              sent? (submit! envelope)
              state (if sent?
                      state
                      (browser/mark-send-failed
                       state command-id
                       "实时通道未接受该浏览器命令。"))]
          (recur state))
        state))))

(defn dispatch!
  "Apply one browser UI action and submit every queued command."
  [action]
  (when (= :browser/refresh (:type action))
    ;; An explicit operator refresh is the only path that clears a failed
    ;; snapshot backoff for the current revision.
    (reset! fetch-blocked? false)
    (reset! last-fetch-revision nil))
  (let [db (when-let [app @app-ref] (application/current-state app))
        current (state-from-db db)
        {:keys [state error]} (browser/reduce-action current action)
        state (flush-outbox! state)]
    (transact-state! state)
    (when error (notify! :warn error))
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

(defn- accepted-receipt?
  [receipt]
  (not (contains? #{:refused :failed}
                  (model/receipt-status receipt))))

(defn- snapshot-pending?
  [state]
  (boolean
   (some (fn [[_ command]]
           (and (= :browser/snapshot (:kind command))
                (contains? #{:queued :submitting} (:status command))))
         (:commands state))))

(defn- apply-receipt-event!
  [event]
  (let [receipt (or (:value event) (:receipt event) event)
        db (when-let [app @app-ref] (application/current-state app))
        current (state-from-db db)
        command-id (model/receipt-command-id receipt)
        command (get-in current [:commands command-id])
        state (browser/apply-receipt current receipt)]
    (when (= :browser/snapshot (:kind command))
      (if (accepted-receipt? receipt)
        (reset! fetch-blocked? false)
        ;; A failed snapshot is terminal for automatic polling.  Only an
        ;; explicit operator refresh or a new session/connection may retry.
        (reset! fetch-blocked? true)))
    (transact-state! state)
    (when (and command
               (accepted-receipt? receipt)
               (not= :browser/snapshot (:kind command))
               (not (snapshot-pending? state)))
      ;; A browser command does not schedule a gateway publish.  Ask for one
      ;; authoritative snapshot after the receipt, outside Sente's callback.
      (js/setTimeout
       (fn []
         (let [db (when-let [app @app-ref] (application/current-state app))
               current (state-from-db db)
               {:keys [state]} (browser/reduce-action current {:type :browser/refresh})]
           (reset! last-fetch-revision (:ui/server-revision db))
           (transact-state! (flush-outbox! state))))
       0))))

(defn- browser-live-event?
  "Server-pushed browser state (takeover, operator input, permission) must
  refresh the pane without waiting for a manual button."
  [event]
  (let [value (or (:value event) (:data event) event)
        kind (or (:kind value) (:type value) (:event value) (:name value)
                 (:kind event) (:type event) (:name event))
        kind (when (or (keyword? kind) (string? kind))
               (-> kind name str/lower-case
                   (str/replace "_" "-") keyword))
        short (when kind
                (keyword (last (str/split (name kind) #"/"))))]
    (contains? #{:operator-input :takeover :resume :permission-changed
                 :browser/event :browser-event :hc/browser-event
                 :browser/operator-input :browser/takeover
                 :browser/resume :browser/permission-changed}
               (or short kind))))

(defn handle-event!
  "Apply a browser command receipt or refresh on live browser state."
  [event]
  (cond
    (receipt-event? event)
    (js/setTimeout #(apply-receipt-event! event) 0)

    (browser-live-event? event)
    (js/setTimeout
     (fn []
       (let [db (when-let [app @app-ref] (application/current-state app))
             current (state-from-db db)
             {:keys [state]} (browser/reduce-action current {:type :browser/refresh})]
         (transact-state! (flush-outbox! state))))
     0)))

(defn sync-from-db!
  "Project the current connection and session into `:ui/browser`.

  Ask for one authoritative browser snapshot when the server revision moves
  and no snapshot request is already in flight.  The server does not publish
  a revision for the snapshot command itself, so this cannot loop."
  [db]
  (let [state (state-from-db db)
        allowed? (browser/command-allowed? state)
        revision (:ui/server-revision db)
        fetch? (and allowed?
                    (not (snapshot-pending? state))
                    (not @fetch-blocked?)
                    (not= revision @last-fetch-revision))]
    (when-not allowed?
      (reset! last-fetch-revision nil)
      (reset! fetch-blocked? false))
    (let [state (if fetch?
                  (do
                    (reset! last-fetch-revision revision)
                    (let [{:keys [state]} (browser/reduce-action
                                           state {:type :browser/refresh})]
                      (flush-outbox! state)))
                  state)]
      (transact-state! state))))

(defn install!
  [app]
  (reset! last-fetch-revision nil)
  (reset! fetch-blocked? false)
  (reset! last-state nil)
  (reset! last-session nil)
  (set-app! app)
  (bridge/ensure-event-listener! ::receipt handle-event!))
