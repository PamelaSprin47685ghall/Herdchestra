(ns hc.client.workbench.settings-bridge
  "Adapter between the server display copy and the settings-ui component.

  The settings component owns its own pure state and command outbox.  This
  bridge supplies a server snapshot, persists settings drafts locally, and
  submits queued settings commands through the same Sente transport as the
  conversation surface.  A receipt, not the click, changes the domain state."
  (:require [clojure.string :as str]
            [com.fulcrologic.fulcro.application :as application]
            [hc.client.appearance :as appearance]
            [hc.client.appearance.model :as appearance-model]
            [com.fulcrologic.fulcro.components :as comp]
            [com.fulcrologic.fulcro.mutations :as m]
            [hc.client.settings :as settings-api]
            [hc.client.settings.draft :as draft]
            [hc.client.workbench.commands :as commands]
            [hc.client.workbench.model :as model]
            [hc.client.workbench.mutations :as mutations]
            [hc.client.workbench.sync-bridge :as bridge]
            [hc.client.workbench.transport :as transport]
            [taoensso.timbre :as log]))

(defonce app-ref
  (atom nil))

(defonce auth-timers
  (atom {}))

(defonce auth-tick-timers
  (atom {}))

(def storage-key "hc.settings.v1")

(declare dispatch!)

(defn set-app!
  [app]
  (reset! app-ref app))

(m/defmutation set-settings-state
  "Replace the settings display copy without discarding a newer confirmed
  server region.

  The bridge computes a next state from the database it can see.  A later
  projection may have already written a newer `:server` region before this
  transaction runs; a delayed local action or boot projection must not put
  the older connections, models, or sync phase back.  Local `:ui`, drafts,
  and commands from NEW-STATE still apply."
  [{:keys [new-state]}]
  (action [env]
    (let [{:keys [state]} env
          db @state
          current (:ui/settings db)
          current-revision (get-in current [:sync :server-revision])
          new-revision (get-in new-state [:sync :server-revision])
          stale-server? (and (integer? current-revision)
                             (or (nil? new-revision)
                                 (< new-revision current-revision)))
          next-state (if stale-server?
                       (-> new-state
                           (assoc :server (:server current))
                           (assoc :sync (merge (:sync new-state)
                                               (:sync current))))
                       new-state)]
      (swap! state assoc :ui/settings next-state))))

(m/defmutation apply-settings-projection
  "Apply one server projection against the settings state that is current
  when the transaction runs.

  A projection captured before a click must not replace the section, role,
  draft, or command the operator selected in the meantime.  The pure
  `apply-snapshot` preserves local `:ui`; the server-revision guard drops a
  projection older than the confirmed copy."
  [{:keys [snapshot]}]
  (action [env]
    (let [{:keys [state]} env
          db @state
          current (or (:ui/settings db) (settings-api/initial-state))
          next-state (settings-api/apply-snapshot current snapshot)]
      (swap! state assoc :ui/settings next-state))))

(defn- transact-state!
  [state]
  (when-let [app @app-ref]
    (comp/transact! app [(set-settings-state {:new-state state})])))

(defn- transact-projection!
  [snapshot]
  (when-let [app @app-ref]
    (comp/transact! app [(apply-settings-projection {:snapshot snapshot})])))

(defn- notify!
  [tone text]
  (when-let [app @app-ref]
    (comp/transact! app
                    [(mutations/notify
                      {:notice {:id (str "settings-" (random-uuid))
                                :tone tone
                                :text text
                                :at (str (js/Date.))}})])))

(defn- persist-drafts!
  [drafts]
  (try
    (let [payload (draft/export-drafts drafts)]
      (when (draft/secret-leak? payload)
        (throw (ex-info "draft export still contains a secret"
                        {:reason :secret-leak})))
      (.setItem js/localStorage storage-key
                (js/JSON.stringify (clj->js payload))))
    (dispatch! {:type :settings/set-draft-storage-status :status :saved})
    (notify! :info "设置草稿已保存到本浏览器。")
    (catch :default e
      (log/warn "Settings draft persistence failed" e)
      (dispatch! {:type :settings/set-draft-storage-status
                  :status :error
                  :error (str e)})
      (notify! :warn "设置草稿暂未保存：浏览器本地存储不可用。"))))

(defn- copy-secret!
  [secret label]
  (try
    (if (and (exists? js/navigator.clipboard)
             (fn? (.-writeText js/navigator.clipboard)))
      (-> (.writeText js/navigator.clipboard secret)
          (.then (fn [] (notify! :info (str label "已复制到剪贴板"))))
          (.catch (fn [_] (notify! :warn "剪贴板不可用，未复制密钥。"))))
      (notify! :warn "剪贴板不可用，未复制密钥。"))
    (catch :default _
      (notify! :warn "剪贴板不可用，未复制密钥。"))))

(defn- open-url!
  [url]
  (try
    (when (and url (exists? js/window))
      (.open js/window url "_blank" "noopener,noreferrer"))
    (catch :default e
      (log/warn "Could not open the authorization URL" e))))

(defn- cancel-auth-poll!
  [flow-id]
  (when-let [timer (get @auth-timers flow-id)]
    (js/clearTimeout timer)
    (swap! auth-timers dissoc flow-id)))

(defn- schedule-auth-poll!
  [flow-id after-ms]
  (when flow-id
    (cancel-auth-poll! flow-id)
    (let [timer (js/setTimeout
                 (fn []
                   (swap! auth-timers dissoc flow-id)
                   (dispatch! {:type :model-auth/poll}))
                 (max 1000 (long (or after-ms 2000))))]
      (swap! auth-timers assoc flow-id timer))))

(defn- cancel-auth-tick!
  [flow-id]
  (when-let [timer (get @auth-tick-timers flow-id)]
    (js/clearInterval timer)
    (swap! auth-tick-timers dissoc flow-id)))

(defn- schedule-auth-tick!
  [flow-id]
  (when flow-id
    (cancel-auth-tick! flow-id)
    (let [timer (js/setInterval
                 (fn []
                   (dispatch! {:type :model-auth/tick
                               :now-ms (.now js/Date)}))
                 1000)]
      (swap! auth-tick-timers assoc flow-id timer))))

(defn- handle-effect!
  [{:keys [effect] :as effect-map}]
  (case effect
    :persist-drafts
    (persist-drafts! (:drafts effect-map))

    :copy-secret
    (copy-secret! (:secret effect-map) (or (:label effect-map) "密钥"))

    :request-stop-run
    (when-let [app @app-ref]
      (commands/stop-run! app (:session-id effect-map)
                          (or (:reason effect-map) :settings-stop)))

    :open-url
    (open-url! (:url effect-map))

    :schedule-poll
    (do
      (schedule-auth-poll! (:flow-id effect-map) (:after-ms effect-map))
      (schedule-auth-tick! (:flow-id effect-map)))

    :cancel-poll
    (do
      (cancel-auth-poll! (:flow-id effect-map))
      (cancel-auth-tick! (:flow-id effect-map)))

    (log/debug "Ignoring settings effect" effect)))

(defn- flush-outbox!
  [state]
  (let [holder (atom state)]
    (settings-api/flush-outbox!
     holder
     (fn [envelope]
       (let [sent? (transport/command! envelope)]
         (when-not sent?
           (notify! :warn "设置命令未发送：实时通道未打开。"))
         (if sent? nil false))))
    @holder))

(defn- dispatch-appearance!
  [action]
  (let [db (when-let [app @app-ref] (application/current-state app))
        current (appearance-model/from-db db)
        {:keys [state]} (appearance-model/reduce-action current action)]
    (when-let [app @app-ref]
      (comp/transact! app
                      [(mutations/set-appearance
                        (appearance-model/to-ui state))]))
    (appearance/apply! state)
    state))

(defn dispatch!
  "Apply one settings action, handle its effects, and submit queued commands.

  Appearance actions are local browser preferences, not server settings.
  They update the workbench database and apply immediately; the workbench
  storage watch persists them with the other local UI settings."
  [action]
  (if (appearance-model/action? action)
    (dispatch-appearance! action)
    (let [db (when-let [app @app-ref] (application/current-state app))
          current (or (:ui/settings db) (settings-api/initial-state))
          {:keys [state effects]} (settings-api/reduce-action current action)]
      (doseq [effect effects]
        (handle-effect! effect))
      (let [state (flush-outbox! state)]
        (transact-state! state)))))

(defn- event-keyword
  [value]
  (when (or (keyword? value) (string? value))
    (-> value
        name
        str/lower-case
        (str/replace "_" "-")
        keyword)))

(defn- receipt-event?
  [event]
  (let [type (event-keyword (or (:type event) (:event event)))
        name (event-keyword (or (:name event) (:event event)))]
    (or (contains? #{:hc/receipt :hc/command-receipt :settings/receipt}
                   type)
        (contains? #{:hc/receipt :hc/command-receipt :settings/receipt}
                   name))))

(defn handle-event!
  "Feed a server receipt to the settings state.  Non-receipt events are
  ignored here; the conversation surface owns run events."
  [event]
  (when (receipt-event? event)
    (let [db (when-let [app @app-ref] (application/current-state app))
          current (or (:ui/settings db) (settings-api/initial-state))
          receipt (or (:value event) (:receipt event) event)
          command-id (or (:command-id receipt) (:commandId receipt))]
      ;; Receipts for backup/materials/diagnostics commands share this Sente
      ;; channel.  Only apply a receipt that belongs to a settings command.
      (when (contains? (:commands current) command-id)
        (let [state (settings-api/apply-receipt current receipt)]
          (when (or (:restart-required receipt)
                    (:restart-required? receipt))
            (notify! :warn "配置已保存；需要重启应用后新连接与模型才会生效。"))
          (transact-state! state))))))

(defn install!
  [app]
  (set-app! app)
  (bridge/ensure-event-listener! ::receipt handle-event!))

(defn sync-from-db!
  "Project the latest server display copy and local session selection into the
  settings state.  Settings commands that target the current session use the
  operator's selection, never the server's shared presentation pointer."
  [db]
  (let [connection (:ui/connection db)
        selected-session-id (model/selected-session-id db)
        server-active-session-id (model/field db :session/active-id)
        settings-by-id (model/field db :settings/by-id)
        legacy-connections (model/field db :connection/by-id)
        model-auth-providers (model/field db :model-auth/providers)
        model-auth-accounts (model/field db :model-auth/accounts)
        session-by-id (model/field db :session/by-id)
        session-ids (set (concat (keys (or session-by-id {}))
                                 (keep (fn [row]
                                         (or (model/field row :session-id)
                                             (model/field row :id)))
                                       (or (model/field db :ui/session-list) []))))
        session-epochs (into {}
                             (keep (fn [session-id]
                                     (when-let [epoch (model/session-command-epoch
                                                       db (str session-id))]
                                       [(str session-id) epoch])))
                             session-ids)
        snapshot (cond-> {:sync {:phase (:phase connection)
                                 :command-epoch (:command-epoch connection)
                                 :epoch (:epoch connection)}
                          :server-revision (:ui/server-revision db)
                          ;; The settings panel's "apply to current session"
                          ;; must target the session the operator sees, not
                          ;; the server's shared presentation pointer.
                          :session/selected-id selected-session-id
                          :session/running?
                          (boolean (and selected-session-id
                                        (model/run-active?
                                         db selected-session-id)))
                          :session/command-epochs session-epochs}
                   (map? settings-by-id)
                   (assoc :settings/by-id settings-by-id)

                   (map? (model/field db :settings/models))
                   (assoc :settings/models (model/field db :settings/models))

                   (map? (model/field db :settings/roles))
                   (assoc :settings/roles (model/field db :settings/roles))

                   (map? (model/field db :session/by-id))
                   (assoc :session/by-id (model/field db :session/by-id))

                   (some? server-active-session-id)
                   (assoc :session/active-id server-active-session-id)

                   (sequential? model-auth-providers)
                   (assoc :model-auth/providers model-auth-providers)

                   (sequential? model-auth-accounts)
                   (assoc :model-auth/accounts model-auth-accounts)

                   ;; A missing settings projection is not an authoritative
                   ;; empty connection list.  Only the legacy connection
                   ;; table may stand in for it; otherwise keep the
                   ;; confirmed connections already in settings state.
                   (and (nil? settings-by-id)
                        (map? legacy-connections))
                   (assoc :server {:connections legacy-connections}))]
    (transact-projection! snapshot)))

(defn settings-element
  [db]
  (let [state (or (:ui/settings db) (settings-api/initial-state))
        state (assoc state :appearance (appearance-model/from-db db))
        connection (:ui/connection db)]
    (settings-api/settings-element state dispatch! (:synced? connection))))
