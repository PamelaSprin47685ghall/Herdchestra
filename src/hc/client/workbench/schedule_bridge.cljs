(ns hc.client.workbench.schedule-bridge
  "Bridge between the schedule workspace, the Sente transport, and Fulcro.

  The schedule page keeps its own external store instead of adding a second
  Fulcro business key.  The bridge:

  - projects catalog/sync/platform from the current Fulcro database;
  - submits scheduler commands through the same `command-id`/`command-epoch`
    envelope as every other workbench command;
  - applies only receipts for commands this workspace queued;
  - refreshes the scheduler snapshot on notifications and manual runs;
  - reports the browser zone for follow-device schedules.

  No click marks a schedule enabled, paused, or run; only a server receipt or
  a refreshed snapshot does."
  (:require [clojure.string :as str]
            [com.fulcrologic.fulcro.application :as application]
            [hc.client.schedule.model :as model]
            [hc.client.schedule.platform :as platform]
            [hc.client.schedule.react :as react-view]
            [hc.client.schedule.time :as time]
            [hc.client.workbench.model :as wb-model]
            [hc.client.workbench.sync-bridge :as sync-bridge]
            [hc.client.workbench.transport :as transport]
            [taoensso.timbre :as log]))

(def storage-key "hc.schedule.v1")

(defonce state-atom
  (atom (model/initial-state {:platform :desktop
                              :device-zone (time/device-zone)})))

(defonce listeners
  (atom #{}))

(defonce app-ref
  (atom nil))

(defonce installed?
  (atom false))

(declare dispatch!)

(defn- notify!
  []
  (doseq [listener @listeners]
    (try
      (listener)
      (catch :default error
        (log/error error "Schedule store listener failed")))))

(defn- set-state!
  [state]
  (reset! state-atom state)
  (notify!)
  state)

(defn- subscribe!
  [listener]
  (swap! listeners conj listener)
  (fn []
    (swap! listeners disj listener)))

(defonce store
  #js {:subscribe subscribe!
       :getSnapshot (fn [] @state-atom)
       :dispatch (fn [action] (dispatch! action))})

;; ---------------------------------------------------------------------------
;; Fulcro projection

(defn- entry-list
  "Normalize a model collection that may arrive as a vector or a map keyed by
  model id.  A map entry is never mistaken for a model map."
  [entries]
  (cond
    (nil? entries) []
    (map? entries) (vals entries)
    (sequential? entries) entries
    :else []))

(defn- model-option
  [connection-id connection-name entry]
  (let [entry (if (map? entry) entry {:id (str entry)})
        model-id (or (:id entry)
                     (:model-id entry)
                     (:model-entry-id entry))]
    (when (and model-id (not (str/blank? (str model-id))))
      {:connection-id (str connection-id)
       :connection-name connection-name
       :model-entry-id (str model-id)
       :label (or (:display-name entry)
                  (:name entry)
                  (:id entry)
                  (:model-id entry)
                  (str model-id))})))

(defn- settings-models
  [db]
  (let [models (or (wb-model/field db :settings/models) {})
        connections (or (wb-model/field db :settings/by-id)
                        (wb-model/field db :connection/by-id)
                        {})]
    (if (seq models)
      (vec
       (mapcat (fn [[connection-id entries]]
                 (let [connection (get connections connection-id)
                       connection-name (or (:name connection)
                                           (:display-name connection)
                                           (str connection-id))]
                   (keep #(model-option connection-id connection-name %)
                         (entry-list entries))))
               models))
      (vec
       (mapcat (fn [[connection-id connection]]
                 (let [connection-name (or (:name connection)
                                           (:display-name connection)
                                           (str connection-id))]
                   (keep #(model-option connection-id connection-name %)
                         (entry-list (:models connection)))))
               connections)))))

(defn- catalog-from-db
  [db]
  {:models (settings-models db)
   :roles (or (wb-model/field db :settings/roles)
              (wb-model/field db :role/by-id)
              (wb-model/field db :profile/by-id)
              {})
   :sessions (->> (or (wb-model/field db :session/by-id) {})
                  vals
                  (map (fn [session]
                         {:id (or (:id session) (:session-id session))
                          :title (or (:title session)
                                     (:id session)
                                     (:session-id session))}))
                  vec)
   :connections (or (wb-model/field db :settings/by-id)
                    (wb-model/field db :connection/by-id)
                    {})})

(defn- connection-from-db
  [db]
  (:ui/connection db))

(defn sync-from-db!
  "Project catalog, sync phase, platform, and browser zone into the store."
  [db]
  (when (map? db)
    (dispatch! {:type :schedule/set-catalog
                :catalog (catalog-from-db db)})
    (let [connection (connection-from-db db)]
      (dispatch! {:type :schedule/set-sync
                  :phase (:phase connection)
                  :command-epoch (:command-epoch connection)
                  :epoch (:epoch connection)}))
    (dispatch! {:type :schedule/set-browser-zone
                :zone (time/device-zone)})
    (dispatch! {:type :schedule/set-platform
                :platform (platform/detect)})))

;; ---------------------------------------------------------------------------
;; Persistence

(defn- persist-form!
  [state]
  (let [payload (model/export-form state)]
    (try
      (if payload
        (.setItem js/localStorage storage-key
                  (js/JSON.stringify (clj->js payload)))
        (.removeItem js/localStorage storage-key))
      (set-state! (assoc-in state [:ui :form-storage-status] :saved))
      (catch :default error
        (log/warn "Schedule form persistence failed" error)
        (set-state! (assoc-in state [:ui :form-storage-status] :failed))))))

(defn- restore-form!
  []
  (try
    (when-let [raw (.getItem js/localStorage storage-key)]
      (let [payload (js->clj (js/JSON.parse raw) :keywordize-keys true)
            state (model/import-form @state-atom payload)]
        (when (get-in state [:ui :form])
          (set-state! (-> state
                          (assoc-in [:ui :view] :form)
                          (assoc-in [:ui :form-storage-status] :saved))))))
    (catch :default error
      (log/warn "Schedule form restore failed" error)
      (set-state! (assoc-in @state-atom [:ui :form-storage-status] :failed)))))

;; ---------------------------------------------------------------------------
;; Effects and dispatch

(defn- send-failed!
  [command-id]
  (set-state!
   (-> @state-atom
       (update-in [:commands command-id] assoc :status :failed)
       (assoc-in [:ui :zone-sync-pending?] false)
       (assoc-in [:ui :form-submitting?] false)
       (model/notice :danger "命令未发送：实时通道未打开或已中断。"))))

(defn- handle-effect!
  [{:keys [effect] :as effect-map}]
  (case effect
    :submit-command
    (let [command-id (:command-id effect-map)
          envelope (:envelope effect-map)]
      (set-state! (update-in @state-atom [:commands command-id]
                             assoc :status :submitting))
      (if (transport/command! envelope)
        true
        (send-failed! command-id)))

    :refresh
    (dispatch! {:type :schedule/refresh
                :reason (or (:reason effect-map) :effect-refresh)})

    :notify
    (set-state! (model/notice @state-atom
                              (or (:tone effect-map) :info)
                              (or (:text effect-map) "")))

    nil))

(defn- form-action?
  [type]
  (contains? #{:schedule/new
               :schedule/new-example
               :schedule/edit
               :schedule/change
               :schedule/toggle-day
               :schedule/save-mode
               :schedule/save
               :schedule/cancel-form
               :schedule/back
               :schedule/apply-receipt
               :schedule/restore-form}
             type))

(defn dispatch!
  "Apply one action, run its effects, and persist a dirty form."
  [action]
  (if (= :schedule/sync-from-db (:type action))
    (do (sync-from-db! (:db action)) @state-atom)
    (let [{:keys [state effects]} (model/reduce-action @state-atom action)]
      (set-state! state)
      (doseq [effect effects]
        (handle-effect! effect))
      (when (form-action? (:type action))
        (persist-form! @state-atom))
      @state-atom)))

;; ---------------------------------------------------------------------------
;; Events

(defn- event-name
  [event]
  (some-> (or (:name event) (:event event) (:type event))
          name
          (str/replace "_" "-")
          keyword))

(defn- event-value
  [event]
  (or (:value event) (:data event) event))

(defn handle-event!
  "Handle a receipt for a schedule command, or a scheduler notification.

  Receipts for conversation/settings commands are ignored here."
  [event]
  (let [name (event-name event)
        value (event-value event)]
    (cond
      (contains? #{:hc/receipt :hc/command-receipt} name)
      (when (contains? (:commands @state-atom) (:command-id value))
        (dispatch! {:type :schedule/apply-receipt :receipt value}))

      (= :hc/schedule-notification name)
      (dispatch! {:type :schedule/notification :notification value})

      :else nil)))

;; ---------------------------------------------------------------------------
;; Public entry points

(defn schedule-element
  "React element for the settings workspace's 定时任务 tab."
  [db]
  (react-view/schedule-store-element store db))

(defn install!
  "Install the receipt listener and project the current database.

  Idempotent.  Workbench main calls this once after the sync bridge starts."
  [app]
  (reset! app-ref app)
  (when (compare-and-set! installed? false true)
    (restore-form!)
    (sync-bridge/ensure-event-listener! ::receipt handle-event!)
    (when (exists? js/window)
      (.addEventListener
       js/window "focus"
       (fn [_]
         (dispatch! {:type :schedule/set-browser-zone
                     :zone (time/device-zone)})))))
  (when-let [db (and app (application/current-state app))]
    (sync-from-db! db))
  store)

(defn state
  []
  @state-atom)
