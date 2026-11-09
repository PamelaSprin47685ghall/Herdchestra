(ns hc.client.workbench.main
  "Browser entry point for the Herdchestra workbench.

  One mount owns the Fulcro root and the Framework7 App.  Initialization loads
  browser-local drafts and appearance settings, installs the single hash
  router, starts the Sente transport, and adds the global Escape hierarchy."
  (:require [com.fulcrologic.fulcro.application :as application]
            [com.fulcrologic.fulcro.components :as comp]
            [hc.client.appearance :as appearance]
            [hc.client.appearance.model :as appearance-model]
            [hc.client.conversation.a11y :as a11y]
            [hc.client.conversation.draft :as draft]
            [hc.client.backup.ui :as backup-ui]
            [hc.client.conversation.stream :as stream]
            [hc.client.diagnostics :as diagnostics]
            [hc.client.search.history-view :as history]
            [hc.client.workbench.app :as app]
            [hc.client.workbench.browser-bridge :as browser-bridge]
            [hc.client.workbench.commands :as commands]
            [hc.client.workbench.conversation-bridge :as conversation-bridge]
            [hc.client.workbench.db :as db]
            [hc.client.workbench.extensions-bridge :as extensions-bridge]
            [hc.client.workbench.materials-bridge :as materials-bridge]
            [hc.client.workbench.model :as model]
            [hc.client.workbench.mutations :as mutations]
            [hc.client.workbench.presence-bridge :as presence-bridge]
            [hc.client.workbench.routing :as routing]
            [hc.client.workbench.schedule-bridge :as schedule-bridge]
            [hc.client.workbench.settings-bridge :as settings-bridge]
            [hc.client.workbench.storage :as storage]
            [hc.client.workbench.sync-bridge :as bridge]
            [taoensso.timbre :as log]))

(defonce initialized?
  (atom false))

(defonce listeners
  (atom {}))

(defonce save-timer
  (atom nil))

(defonce last-persisted
  (atom nil))

(defn- current-db []
  (when-let [app (app/current-app)]
    (application/current-state app)))

(defn- apply-appearance!
  [db]
  (appearance/apply! (appearance-model/from-db db)))

(defn- scroll-map
  "The persisted reading offset per session.  Only measured offsets are kept;
  a nil offset means the session has never been scrolled."
  [db]
  (into {}
        (keep (fn [[session-id stream]]
                (when (number? (:scroll-top stream))
                  [session-id (:scroll-top stream)])))
        (:ui/stream db)))

(defn- persist-signature
  [db]
  [(:ui/theme db)
   (:ui/font-scale db)
   (:ui/contrast db)
   (:ui/reduced-motion db)
   (get-in db [:ui/settings :language])
   (get-in db [:ui/settings :background])
   (get-in db [:ui/settings :bubble])
   (storage/draft-signature (:ui/draft db))
   (scroll-map db)])

(defn- mark-saved!
  [app versions]
  (let [db (application/current-state app)]
    (doseq [[session-id version] versions]
      (let [d (get-in db [:ui/draft session-id])]
        (when (and d
                   (= version (:version d))
                   (not= :saved (:save-state d)))
          (comp/transact! app
                          [(mutations/set-draft-save-state
                            {:session-id session-id
                             :save-state :saved
                             :saved-version version})]))))))

(defn- persist!
  [app]
  (let [db (application/current-state app)
        versions (into {}
                       (map (fn [[session-id d]]
                              [session-id (:version d)]))
                       (:ui/draft db))
        signature (persist-signature db)
        result (storage/save! (:ui/draft db)
                              (appearance-model/to-storage db)
                              (scroll-map db))]
    (if (:ok? result)
      (do
        (reset! last-persisted signature)
        (comp/transact! app [(mutations/set-draft-storage {:status :available})])
        (mark-saved! app versions))
      (do
        (comp/transact! app
                        [(mutations/set-draft-storage
                          {:status :unavailable
                           :error (:error result)})])
        ;; A global status is not enough: the composer reads each draft's
        ;; `:save-state`.  Mark every draft unavailable so the visible label
        ;; keeps saying "草稿暂未保存" instead of a stale "已保存".
        (doseq [[session-id _] versions]
          (comp/transact! app
                          [(mutations/set-draft-save-state
                            {:session-id session-id
                             :save-state :storage-unavailable
                             :error (:error result)})]))
        (comp/transact! app
                        [(mutations/notify
                          {:notice {:id (str "storage-" (random-uuid))
                                    :tone :warn
                                    :text "草稿暂未保存：浏览器本地存储不可用。请复制或另存当前草稿。"
                                    :at (str (js/Date.))}})])))))

(defn- schedule-persist!
  [app]
  (when-let [timer @save-timer]
    (js/clearTimeout timer))
  (reset! save-timer
          (js/setTimeout #(persist! app) 350)))

(defn- install-watch!
  [app]
  (let [state-atom (:com.fulcrologic.fulcro.application/state-atom app)]
    (add-watch state-atom ::workbench
               (fn [_ _ old new]
                 (apply-appearance! new)
                 (when (or (not= (:ui/server-revision old) (:ui/server-revision new))
                           (not= (:ui/connection old) (:ui/connection new)))
                   (settings-bridge/sync-from-db! new)
                   (materials-bridge/sync-from-db! new)
                   (extensions-bridge/sync-from-db! new)
                   (diagnostics/sync! new)
                   (backup-ui/sync! new)
                   (browser-bridge/sync-from-db! new))
                 (when (not= (:ui/selected-session-id old)
                             (:ui/selected-session-id new))
                   (settings-bridge/sync-from-db! new)
                   (browser-bridge/sync-from-db! new)
                   (materials-bridge/sync-from-db! new)
                   (extensions-bridge/sync-from-db! new))
                 (let [old-session-id (model/selected-session-id old)
                       new-session-id (model/selected-session-id new)]
                   ;; The Owner is created lazily.  Its `:session/ensure`
                   ;; receipt publishes the per-session command epoch on the
                   ;; session row, but no server-revision/connection change
                   ;; accompanies it.  Re-project the session-scoped bridges
                   ;; when that epoch arrives so the materials snapshot (and
                   ;; the other session-scoped reads) are not left waiting
                   ;; forever on a nil epoch.
                   (when (and (some? new-session-id)
                              (= old-session-id new-session-id)
                              (not= (model/session-command-epoch old old-session-id)
                                    (model/session-command-epoch new new-session-id)))
                     (settings-bridge/sync-from-db! new)
                     (browser-bridge/sync-from-db! new)
                     (materials-bridge/sync-from-db! new)
                     (extensions-bridge/sync-from-db! new)))
                 (when (or (not= (:ui/theme old) (:ui/theme new))
                           (not= (:ui/font-scale old) (:ui/font-scale new))
                           (not= (:ui/contrast old) (:ui/contrast new))
                           (not= (:ui/reduced-motion old) (:ui/reduced-motion new))
                           (not= (get-in old [:ui/settings :language])
                                 (get-in new [:ui/settings :language]))
                           (not= (get-in old [:ui/settings :background])
                                 (get-in new [:ui/settings :background]))
                           (not= (get-in old [:ui/settings :bubble])
                                 (get-in new [:ui/settings :bubble]))
                           (not= (:ui/draft old) (:ui/draft new))
                           (not= (scroll-map old) (scroll-map new)))
                   (let [signature (persist-signature new)]
                     (when (not= signature @last-persisted)
                       (schedule-persist! app))))))))

(defn- text-selection?
  []
  (when (exists? js/window)
    (let [selection (.getSelection js/window)]
      (and selection (not (.-isCollapsed selection))))))

(defn- control-selection?
  "True when the focused input or textarea holds a non-collapsed selection.

  `window.getSelection()` reports a collapsed selection for text controls, so
  Escape must consult the control itself before it may stop a run."
  []
  (when (exists? js/document)
    (let [el (.-activeElement js/document)
          tag (some-> el .-tagName)
          start (some-> el .-selectionStart)
          end (some-> el .-selectionEnd)]
      (and (contains? #{"INPUT" "TEXTAREA"} tag)
           (number? start)
           (number? end)
           (not= start end)))))

(defn- clear-control-selection!
  []
  (when (exists? js/document)
    (let [el (.-activeElement js/document)
          tag (some-> el .-tagName)]
      (when (and (contains? #{"INPUT" "TEXTAREA"} tag)
                 (fn? (.-setSelectionRange el)))
        (let [pos (or (some-> el .-selectionEnd) 0)]
          (.setSelectionRange el pos pos))))))

(defn- focus-session-search!
  "Focus the session filter.  The drawer layout has its own input; open the
  drawer first so the operator can type immediately."
  [app]
  (let [db (application/current-state app)
        overlay? (a11y/overlay-layout? (:ui/viewport-width db))
        target (if overlay? "wb-nav-session-search" "wb-session-search")]
    (js/setTimeout
     (fn []
       ;; Read the drawer state after the navigation transaction lands; a
       ;; stale `:ui/sidebar-open` would otherwise focus a hidden input.
       (let [db (application/current-state app)]
         (when (and overlay? (not (:ui/sidebar-open db)))
           (comp/transact! app
                           [(mutations/toggle-sidebar
                             {:trigger-id "wb-menu"})])))
       (js/setTimeout
        (fn []
          (some-> (.getElementById js/document target) (.focus)))
        0))
     0)))

(defn- keydown!
  [app event]
  (let [key (.-key event)
        lower (when (string? key) (.toLowerCase key))
        meta? (or (.-metaKey event) (.-ctrlKey event))
        shift? (.-shiftKey event)]
    (cond
      (and meta? (= key ","))
      (do (.preventDefault event)
          (routing/navigate! app :settings nil))

      (and meta? shift? (= "f" lower))
      (do (.preventDefault event)
          (history/open!))

      (and meta? (= "f" lower))
      (do (.preventDefault event)
          (if (model/selected-session-id (application/current-state app))
            (let [db (application/current-state app)]
              (when (not= :sessions (:ui/route db))
                (routing/navigate! app :sessions nil))
              (comp/transact! app
                              [(mutations/request-find
                                {:seq (inc (long (or (:ui/find-seq db) 0)))})]))
            (do (routing/navigate! app :sessions nil)
                (focus-session-search! app))))

      (and meta? (not shift?) (= "n" lower))
      (do (.preventDefault event)
          (routing/navigate! app :sessions nil)
          (commands/create-session! app {:title "新会话"}))

      (and (= key "Escape") (history/open?))
      (do (.preventDefault event)
          (.stopPropagation event)
          (history/close!))

      (= key "Escape")
      (let [db (application/current-state app)
            selected-id (model/selected-session-id db)
            stream' (or (get-in db [:ui/stream selected-id]) (stream/initial))
            decision (a11y/escape-decision
                      {:overlays (model/overlay-stack db)
                       :composing? (.-isComposing event)
                       :text-selection? (boolean (or (text-selection?)
                                                     (control-selection?)))
                       :stream-selection nil
                       ;; The stop button already treats a server-reported run
                       ;; as active even when the visible stream is idle
                       ;; (unattended Nudge).  Escape must use the same fact.
                       :generating? (or (stream/streaming? stream')
                                        (boolean (model/run-active? db selected-id)))
                       :stopping? (stream/stopping? stream')})
            ;; Escape only stops a generation while the operator is looking at
            ;; the conversation workspace; a settings or board form keeps its
            ;; own Escape semantics.
            action (if (and (= :stop (:action decision))
                            (not (model/escape-stop-allowed? db)))
                     :ignore
                     (:action decision))]
        (case action
          :close-overlay
          (do
            (.preventDefault event)
            ;; Close exactly the topmost overlay; one Escape press must not
            ;; discard two layers of the operator's context.
            (comp/transact! app
                            [(mutations/close-overlay
                              {:id (:id (:overlay decision))})]))

          :clear-selection
          (do
            (when-let [selection (.getSelection js/window)]
              (.removeAllRanges selection))
            (clear-control-selection!))

          :stop
          (do
            (.preventDefault event)
            (commands/stop-run! app selected-id :escape))

          nil))

      :else nil)))

(defn- install-listeners!
  [app]
  (let [resize (fn [_]
                 (comp/transact! app
                                [(mutations/set-viewport-width
                                  {:width (.-innerWidth js/window)})]))
        keydown (fn [event] (keydown! app event))
        before-unload (fn [_] (persist! app))]
    (swap! listeners assoc :resize resize :keydown keydown :before-unload before-unload)
    (.addEventListener js/window "resize" resize)
    (.addEventListener js/window "keydown" keydown)
    (.addEventListener js/window "beforeunload" before-unload)))

(defn- remove-listeners!
  []
  (when-let [{:keys [resize keydown before-unload]} @listeners]
    (.removeEventListener js/window "resize" resize)
    (.removeEventListener js/window "keydown" keydown)
    (.removeEventListener js/window "beforeunload" before-unload))
  (reset! listeners {}))

(defn- mark-storage-unavailable
  [initial-db]
  (update initial-db :ui/draft
          (fn [drafts]
            (into {}
                  (map (fn [[session-id d]]
                         [session-id (draft/set-save-state
                                      d :storage-unavailable
                                      {:error "localStorage unavailable"})]))
                  (or drafts {})))))

(defn init
  "Mount the workbench once.  Safe to call again from a hot-reload hook."
  []
  (when-not @initialized?
    (reset! initialized? true)
    (let [loaded (storage/load!)
          width (if (exists? js/window)
                  (.-innerWidth js/window)
                  1280)
          initial (-> (db/initial-db)
                      (db/merge-local! loaded)
                      (assoc :ui/viewport-width width
                             ;; Wide layouts keep the details column visible;
                             ;; narrow layouts start with the drawer closed.
                             :ui/aux-open (not (a11y/overlay-layout? width))
                             :ui/platform (if (and (exists? js/window)
                                                   (fn? (.-matchMedia js/window))
                                                   (.-matches (.matchMedia js/window "(pointer: coarse)"))
                                                   (not (.-matches (.matchMedia js/window "(any-pointer: fine)"))))
                                            :mobile
                                            :desktop)))
          initial (cond-> initial
                    (= :unavailable (:status loaded)) mark-storage-unavailable)
          app (app/start! initial)]
      ;; Read-only E2E probe: the real Board projection depends on the
      ;; Fulcro database shape, which is not observable from the DOM.  The
      ;; function returns only identity and counts, never drafts or content.
      (when (exists? js/window)
        (set! (.-__HC_DB__ js/window)
              (fn []
                (let [db (application/current-state app)
                      sessions (get db :session/by-id)]
                  (clj->js
                   {:keys (mapv name (keys db))
                    :sessionIds (mapv name (keys sessions))
                    :activeId (get db :session/active-id)
                    :dashboard (contains? db :swarm/dashboard)
                    ;; E2E diagnostics: which session each message entity
                    ;; claims and the per-session visible id lists.  Ids and
                    ;; ownership only; no message text or draft content.
                    :selectedSessionId (get db :ui/selected-session-id)
                    :sessionMessageIds
                    (into {}
                          (map (fn [[id summary]]
                                 [id (or (get summary :message-ids)
                                         (get summary :messages))]))
                          sessions)
                    :messageSessions
                    (into {}
                          (map (fn [[id message]]
                                 [id (or (get message :session-id)
                                         (get message :message/session-id)
                                         (get message :session))]))
                          (get db :message/by-id))
                    :workerPrograms
                    (into {}
                          (map (fn [[id summary]]
                                 [id (count (or (:worker-programs summary) []))]))
                          sessions)})))))
      (reset! last-persisted (persist-signature initial))
      (apply-appearance! initial)
      (bridge/set-app! app)
      (conversation-bridge/install! app)
      (presence-bridge/install! app)
      (history/install!)
      (settings-bridge/install! app)
      (settings-bridge/sync-from-db! initial)
      (diagnostics/install! app)
      (diagnostics/sync! initial)
      (backup-ui/install! app)
      (schedule-bridge/install! app)
      (browser-bridge/install! app)
      (browser-bridge/sync-from-db! initial)
      (materials-bridge/install! app)
      (materials-bridge/sync-from-db! initial)
      (extensions-bridge/install! app)
      (extensions-bridge/sync-from-db! initial)
      (install-watch! app)
      (routing/start! app)
      (install-listeners! app)
      (bridge/start!))
    nil))

(defn after-load
  "Shadow-cljs hot-reload hook.  Re-render the existing root; do not restart
  the transport or discard drafts."
  []
  (app/remount!)
  (when-let [db (current-db)]
    (apply-appearance! db)))

(defn -main
  [& _]
  (init))

(defn ^:export main
  []
  (init))
