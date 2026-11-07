(ns hc.client.backup.bridge
  "Fulcro and Sente bridge for the backup workspace.

  The bridge owns no business state.  It projects the current connection into
  the pure backup state, transacts that state into Fulcro, and submits queued
  `:backup/*` envelopes through the same Sente command channel as settings.
  A receipt listener is the only path that marks a command complete."
  (:require [com.fulcrologic.fulcro.application :as application]
            [hc.client.backup.commands :as commands]
            [hc.client.backup.model :as model]
            [hc.client.workbench.sync-bridge :as sync-bridge]
            [hc.client.workbench.transport :as transport]
            [taoensso.timbre :as log]))

(defonce app-ref (atom nil))
(defonce installed? (atom false))
(defonce auto-refreshed? (atom false))

;; The app publisher may publish a new revision when unrelated state changes,
;; so polling maintenance status on every revision would feed itself.  Keep
;; the automatic check to one in-flight request and at most one every two
;; seconds.
(defonce last-maintenance-poll (atom nil))
(defonce last-maintenance-poll-at (atom 0))

(declare dispatch!)

;; Command IDs that belong to this workspace.  Updated before the Fulcro
;; transaction so a receipt that wins the race is still recognized.
(defonce command-index (atom #{}))

(defn- remember-commands!
  [state]
  (swap! command-index into (keys (:commands state)))
  nil)

(defn- forget-command!
  [command-id]
  (swap! command-index disj command-id)
  nil)


(def prefs-key "hc.backup.prefs.v1")

(defn- load-prefs
  []
  (try
    (when (exists? js/localStorage)
      (when-let [raw (.getItem js/localStorage prefs-key)]
        (js->clj (js/JSON.parse raw) :keywordize-keys true)))
    (catch :default error
      (log/warn "Backup preferences could not be read" error)
      nil)))

(defn- save-prefs!
  [state]
  (try
    (when (exists? js/localStorage)
      (.setItem js/localStorage prefs-key
                (js/JSON.stringify
                 (clj->js (select-keys (:ui state) model/preference-keys)))))
    (catch :default error
      (log/warn "Backup preferences could not be saved" error)
      nil)))

(defn- transact-state!
  "Commit the local backup UI slice directly to the Fulcro state atom.

  A receipt can arrive inside the sync bridge's own transaction/render pass.
  A nested `transact!` there is not a reliable way to update a local UI slice,
  so this writes the atom and asks Fulcro to render.  It touches only
  `:ui/backup`; server-owned tables and other local keys are untouched."
  [state]
  (when-let [app @app-ref]
    (let [state-atom (:com.fulcrologic.fulcro.application/state-atom app)]
      (swap! state-atom assoc :ui/backup state)
      (application/schedule-render! app))))

(defn state-from-db
  "Project the current Fulcro database into a backup state.  This function is
  pure with respect to the database and safe to call during render."
  [db]
  (-> (or (:ui/backup db) (model/initial-state))
      (model/set-sync (model/connection-projection (:ui/connection db)))
      (model/set-transport-available (transport/open?))))

(defn- reconnect-and-project!
  "Cycle the channel and transact the connection phase that now holds.

  `sync-bridge/reconnect!` takes no arguments; the workbench owns the
  transport.  Its positive result is also not proof that the channel is open
  again, so the phase is re-read from the Fulcro database and transacted with
  the adapter state that `reconnect!` just installed.  A Sente state change
  updates `:ui/connection` later; until then the panel must not claim
  `:synced`."
  []
  (let [result (sync-bridge/reconnect!)]
    (when-let [app @app-ref]
      (let [db (application/current-state app)
            connection (model/connection-projection
                        (cond-> (:ui/connection db)
                          (not (:ok? result))
                          (assoc :phase :interrupted
                                 :reason (or (:reason result)
                                             :reconnect-failed))))]
        (transact-state! (-> (state-from-db db)
                             (model/set-sync connection)))))
    result))

(defn- flush-outbox!
  [state]
  (reduce (fn [state envelope]
            (let [command-id (:command-id envelope)]
              (try
                (if (transport/command! envelope)
                  (commands/mark-submitting state command-id)
                  (model/mark-outcome-unknown
                   state command-id
                   {:message "实时通道未打开，命令没有发送"}))
                (catch :default error
                  (log/warn "Backup command send failed" error)
                  (model/mark-outcome-unknown state command-id error)))))
          state
          (commands/pending-outbox state)))

(defn- copy-text!
  "Write TEXT to the clipboard after an explicit operator click.  The text is
  never logged and never enters local storage.  A refused or unavailable
  clipboard is reported as a failure, not as a copy."
  [text]
  (try
    (let [clipboard (when (exists? js/navigator)
                      (aget js/navigator "clipboard"))
          write-text (when clipboard (aget clipboard "writeText"))]
      (if (fn? write-text)
        (-> (.call write-text clipboard text)
            (.then (fn [_] (dispatch! {:type :backup/copy-result :ok? true})))
            (.catch (fn [error]
                      (dispatch! {:type :backup/copy-result
                                  :ok? false
                                  :reason (or (when error (aget error "name"))
                                              "clipboard-rejected")}))))
        (dispatch! {:type :backup/copy-result
                    :ok? false
                    :reason "clipboard-unavailable"})))
    (catch :default error
      (dispatch! {:type :backup/copy-result
                  :ok? false
                  :reason (or (when error (aget error "message"))
                              "clipboard-error")}))))

(defn- run-effects!
  [effects]
  (doseq [effect effects]
    (case (:type effect)
      :backup/copy-text (copy-text! (:text effect))
      nil)))

(defn dispatch!
  "Apply one UI action, submit queued commands once, and transact the result."
  [action]
  (if (= :backup/reconnect (keyword (name (:type action))))
    (reconnect-and-project!)
    (if-let [app @app-ref]
      (let [db (application/current-state app)
            base (state-from-db db)
            {:keys [state effects]} (model/reduce-action base action)]
        (save-prefs! state)
        (remember-commands! state)
        ;; Record the command before sending it.  A fast server can otherwise
        ;; deliver the receipt before the command exists in the Fulcro
        ;; database, and the receipt listener would correctly ignore it as
        ;; unknown.
        (transact-state! state)
        (let [state (flush-outbox! state)]
          (transact-state! state)
          (run-effects! effects)
          state))
      (let [{:keys [state]} (model/reduce-action (model/initial-state) action)]
        state))))

(defn- receipt-event?
  [event]
  (let [type (or (:type event) (:event event))
        name (or (:name event) (:event event))]
    (or (contains? #{:hc/receipt :hc/command-receipt :backup/receipt} type)
        (contains? #{:hc/receipt :hc/command-receipt :backup/receipt} name)
        (= "hc/command-receipt" name))))

(defn- receipt-payload
  [event]
  (or (:value event) (:receipt event) event))

(defn- maintenance-event?
  [event]
  (let [name (or (:name event) (:event event))]
    (or (= "hc/backup-maintenance" name)
        (= :hc/backup-maintenance name))))

(defn- known-command?
  [db command-id]
  (or (contains? @command-index command-id)
      (contains? (get-in db [:ui/backup :commands] {}) command-id)))

(defn- defer!
  [f]
  (js/setTimeout f 0))

(defn- handle-receipt!
  [event]
  (when-let [app @app-ref]
    (let [payload (receipt-payload event)
          command-id (:command-id payload)]
      (when (and command-id
                 (known-command? (application/current-state app) command-id))
        ;; The listener runs inside the sync bridge's own transaction/render
        ;; pass, so defer the local state commit to the next task.
        (defer!
         (fn []
           (when-let [app @app-ref]
             (when (known-command? (application/current-state app) command-id)
               (dispatch! {:type :backup/apply-receipt :receipt payload})
               (forget-command! command-id)))))))))

(defn- handle-maintenance!
  [event]
  (let [payload (or (:value event) (:event event))]
    (defer!
     (fn []
       (dispatch! {:type :backup/apply-maintenance-event :event payload})))))

(defn handle-event!
  "Apply a backup command receipt or maintenance progress event."
  [event]
  (cond
    (receipt-event? event) (handle-receipt! event)
    (maintenance-event? event) (handle-maintenance! event)
    :else nil))

(defn- maybe-auto-refresh!
  [app]
  (let [db (application/current-state app)
        connection (:ui/connection db)
        backup (:ui/backup db)]
    (when (and (= :synced (:phase connection))
               (not @auto-refreshed?)
               (nil? (get-in backup [:server :status]))
               (empty? (:commands backup)))
      (reset! auto-refreshed? true)
      (dispatch! {:type :backup/refresh}))))

(defn install!
  "Install the receipt listener and an automatic first status read.

  Idempotent: repeated calls keep one listener.  The workbench calls this once
  after the Fulcro app starts."
  [app]
  (reset! app-ref app)
  (reset! last-maintenance-poll nil)
  (reset! last-maintenance-poll-at 0)
  (when-not @installed?
    (reset! installed? true)
    ;; Honest at the delivery point: the bridge registers once, and the sync
    ;; bridge also rejects a second delivery for the same key.
    (sync-bridge/ensure-event-listener! ::receipt handle-event!)
    (when-let [prefs (load-prefs)]
      (dispatch! {:type :backup/restore-prefs :prefs prefs}))
    (let [state-atom (:com.fulcrologic.fulcro.application/state-atom app)]
      (add-watch state-atom ::backup-auto-refresh
                 (fn [_ _ _ _]
                   (maybe-auto-refresh! app)))))
  (dispatch! {:type :backup/set-transport-available
              :available? (transport/open?)})
  app)

(defn sync!
  "Refresh the backup state from the current Fulcro database."
  [db]
  ;; A watch handler receives the state as it was when that swap ran.  A
  ;; nested dispatch inside an earlier watch can have queued a command since
  ;; then; projecting the stale argument would write the old `:ui/backup`
  ;; back and erase the new command.  Always project the current database
  ;; when the app is available.
  (let [db (if-let [app @app-ref]
             (application/current-state app)
             db)
        state (state-from-db db)
        maintenance (model/maintenance-view state)
        id (or (get-in maintenance [:active :id])
               (get-in maintenance [:active :maintenance-id])
               (get-in state [:ui :maintenance-request :id])
               (get-in maintenance [:last :id])
               (get-in maintenance [:last :maintenance-id]))
        poll-key (when id
                   [id (:status maintenance) (:phase maintenance)
                    (:needs-attention? maintenance)
                    (:acknowledged? maintenance)
                    (:evidence maintenance)])
        now (.now js/Date)
        poll? (and (model/maintenance-available? state)
                   (some? id)
                   (not (:terminal? maintenance))
                   (commands/command-allowed? state)
                   (nil? (commands/pending-command-for
                          state [:backup/maintenance-status :global :global]))
                   (or (not= poll-key @last-maintenance-poll)
                       (>= (- now (long @last-maintenance-poll-at)) 2000)))]
    (transact-state! state)
    (when (or (nil? id)
              (:terminal? maintenance)
              (not (model/maintenance-available? state)))
      (reset! last-maintenance-poll nil))
    (when poll?
      (reset! last-maintenance-poll poll-key)
      (reset! last-maintenance-poll-at now)
      (dispatch! {:type :backup/refresh-maintenance
                  :maintenance-id id}))
    state))
