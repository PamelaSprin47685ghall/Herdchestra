(ns hc.client.workbench.mutations
  "Fulcro mutations for the workbench shell.

  Mutations only change the Fulcro database.  Transport calls and command
  receipts live in `hc.client.workbench.commands` and
  `hc.client.workbench.sync-bridge`; this keeps a rejected command from being
  mistaken for a state transition."
  (:require [com.fulcrologic.fulcro.mutations :as m]
            [hc.client.conversation.ask :as ask]
            [hc.client.conversation.a11y :as a11y]
            [hc.client.conversation.draft :as draft]
            [hc.client.conversation.stream :as stream]
            [hc.client.materials.model :as materials-model]
            [hc.client.sync :as sync]
            [hc.client.workbench.events :as events]
            [hc.client.workbench.model :as model]))

(defn- bump-revision [db]
  (update db :ui/server-revision (fnil inc 0)))

(defn- request-focus
  "Record a focus intent for the shell's post-commit effect.

  The mutation cannot focus a drawer close control before React commits the
  new `data-open` value; a target plus a monotonically increasing sequence
  lets the effect run after every request, including a repeated target."
  [db target]
  (cond-> db
    target (assoc :ui/focus-target target
                  :ui/focus-seq (inc (long (or (:ui/focus-seq db) 0))))))

(m/defmutation install-sync
  "Replace the server-owned region of the Fulcro database with the adapter's
  current confirmed view.  Local `:ui/*` keys survive."
  [{:keys [adapter]}]
  (action [env]
    (let [{:keys [state]} env]
      (swap! state
             (fn [db]
               (-> (sync/merge-into-db db adapter)
                   (assoc :ui/connection (model/connection-view adapter))
                   bump-revision))))))

(m/defmutation apply-run-event
  "Apply one non-state AG-UI event."
  [{:keys [event]}]
  (action [env]
    (let [{:keys [state]} env
          result (events/apply-event @state event)]
      (swap! state
             (fn [db]
               (-> (if (:handled? result) (:db result) db)
                   bump-revision))))))

(m/defmutation recover-snapshot-error
  [{:keys [session-id error]}]
  (action [env]
    (let [{:keys [state]} env]
      (swap! state events/recover-snapshot-error session-id error))))

(m/defmutation track-command
  [{:keys [command]}]
  (action [env]
    (let [{:keys [state]} env]
      (swap! state assoc-in [:ui/command (:command-id command)] command))))

(m/defmutation update-command
  [{:keys [command-id changes]}]
  (action [env]
    (let [{:keys [state]} env]
      (swap! state update-in [:ui/command command-id] merge changes))))

(m/defmutation set-connection
  [{:keys [connection]}]
  (action [env]
    (let [{:keys [state]} env]
      (swap! state assoc :ui/connection connection))))

(m/defmutation set-route
  [{:keys [route session-id]}]
  (action [env]
    (let [{:keys [state]} env]
      (swap! state
             (fn [db]
               (cond-> (assoc db
                              :ui/route route
                              :ui/sidebar-open false)
                 (a11y/overlay-layout? (:ui/viewport-width db))
                 (assoc :ui/aux-open false)

                 session-id (-> (assoc :ui/selected-session-id session-id)
                                (update :ui/selection-seq (fnil inc 0)))))))))

(m/defmutation select-session
  [{:keys [session-id]}]
  (action [env]
    (let [{:keys [state]} env]
      (swap! state
             (fn [db]
               (let [drawer? (:ui/sidebar-open db)]
                 (-> db
                     (assoc :ui/selected-session-id session-id
                            :ui/route :sessions
                            :ui/sidebar-open false)
                     (cond-> (a11y/overlay-layout? (:ui/viewport-width db))
                       (assoc :ui/aux-open false))
                     (update :ui/selection-seq (fnil inc 0))
                     (update :ui/draft #(assoc (or % {}) session-id
                                               (draft/draft (or % {}) session-id)))
                     (update :ui/stream #(assoc (or % {}) session-id
                                                (or (get % session-id)
                                                    (stream/initial))))
                     ;; A session chosen from the navigation drawer closes
                     ;; that drawer.  Focus must return to the real opener,
                     ;; not vanish with the hidden list.
                     (cond-> drawer?
                       (request-focus (or (:ui/sidebar-return-id db)
                                          "wb-menu"))))))))))

(m/defmutation toggle-sidebar
  "Toggle the navigation drawer.  When opening, remember which control did it
  so closing the drawer can return focus there."
  [{:keys [trigger-id]}]
  (action [env]
    (let [{:keys [state]} env]
      (swap! state
             (fn [db]
               (let [open? (not (:ui/sidebar-open db))
                     db' (cond-> (assoc db :ui/sidebar-open open?)
                           (and open?
                                (a11y/overlay-layout?
                                 (:ui/viewport-width db)))
                           (assoc :ui/aux-open false)
                           (and open? trigger-id)
                           (assoc :ui/sidebar-return-id trigger-id))]
                 (request-focus
                  db'
                  (if open?
                    "wb-nav-drawer"
                    (or (:ui/sidebar-return-id db') "wb-menu")))))))))

(m/defmutation toggle-aux
  "Toggle or explicitly open/close the details panel.

  `:open? true` makes the conversation header's \"open\" action idempotent;
  `:open? false` is the panel's close control.  `:trigger-id` is recorded
  only when the panel opens, so the close path knows where to return focus."
  [{:keys [trigger-id open?]}]
  (action [env]
    (let [{:keys [state]} env]
      (swap! state
             (fn [db]
               (let [was-open? (:ui/aux-open db)
                     next-open? (if (some? open?)
                                  (boolean open?)
                                  (not was-open?))
                     db' (cond-> (assoc db :ui/aux-open next-open?)
                           (and next-open?
                                (a11y/overlay-layout?
                                 (:ui/viewport-width db)))
                           (assoc :ui/sidebar-open false)
                           (and next-open? trigger-id)
                           (assoc :ui/aux-return-id trigger-id))]
                 (request-focus
                  db'
                  (if next-open?
                    "wb-aux-close"
                    (when was-open?
                      (or (:ui/aux-return-id db') "wb-aux-toggle"))))))))))

(m/defmutation close-overlay
  "Close one overlay layer by id and return focus to the control that opened
  it.  The caller passes the topmost entry from `model/overlay-stack`."
  [{:keys [id]}]
  (action [env]
    (let [{:keys [state]} env]
      (swap! state
             (fn [db]
               (if-let [overlay (some #(when (= id (:id %)) %)
                                      (model/overlay-stack db))]
                 (-> (cond-> db
                       (= :nav id) (assoc :ui/sidebar-open false)
                       (= :aux id) (assoc :ui/aux-open false))
                     (request-focus (:trigger-id overlay)))
                 db))))))

(m/defmutation edit-draft
  [{:keys [session-id text cursor selection composing? at]}]
  (action [env]
    (let [{:keys [state]} env]
      (swap! state update-in [:ui/draft session-id]
             (fn [d]
               (draft/edit (draft/draft {session-id d} session-id)
                           {:text text
                            :cursor cursor
                            :selection selection
                            :composing? composing?
                            :at at}))))))

(m/defmutation set-draft-save-state
  [{:keys [session-id save-state error saved-version]}]
  (action [env]
    (let [{:keys [state]} env]
      (swap! state update-in [:ui/draft session-id]
             (fn [d]
               (draft/set-save-state
                (draft/draft {session-id d} session-id)
                save-state
                {:error error :saved-version saved-version}))))))

(m/defmutation edit-ask
  [{:keys [ask-id text selected]}]
  (action [env]
    (let [{:keys [state]} env]
      (swap! state update-in [:ui/ask ask-id]
             (fn [a]
               (when a
                 (ask/edit a {:text text :selected selected})))))))

(m/defmutation set-stream
  [{:keys [session-id stream]}]
  (action [env]
    (let [{:keys [state]} env]
      (swap! state assoc-in [:ui/stream session-id] stream))))

(m/defmutation ask-submitted
  [{:keys [ask]}]
  (action [env]
    (let [{:keys [state]} env]
      (swap! state assoc-in [:ui/ask (:ask-id ask)] ask))))

(m/defmutation clear-drafts
  [_]
  (action [env]
    (let [{:keys [state]} env]
      (swap! state assoc :ui/draft {} :ui/draft-reset {}))))

(m/defmutation set-diagnostics-state
  "Replace the diagnostics display state.  A server receipt is the only
  source of domain values; this mutation never marks a command complete."
  [{:keys [new-state]}]
  (action [env]
    (let [{:keys [state]} env]
      (swap! state assoc :ui/diagnostics new-state))))

(m/defmutation set-materials-state
  [{:keys [new-state]}]
  (action [env]
    (let [{:keys [state]} env]
      (swap! state
             (fn [db]
               (let [current (:ui/materials db)
                     stale? (and (integer? (:revision new-state))
                                 (integer? (:revision current))
                                 (< (:revision new-state)
                                    (:revision current)))]
                 ;; A stale write is stale only in its server-derived
                 ;; revision: the database already holds a newer snapshot.
                 ;; Dropping it whole would also drop the local delta the
                 ;; write carried — a receipt's `:last-move`, a command
                 ;; status, drafts and selections — and leave the pending
                 ;; lock held forever.  Merge those local fields onto the
                 ;; current projection instead, atomically at mutation
                 ;; time, so a snapshot landing between the caller's read
                 ;; and this write cannot lose the receipt.
                 (assoc db :ui/materials
                        (if stale?
                          (materials-model/merge-local-state current new-state)
                          new-state))))))))

(m/defmutation set-extensions-state
  "Replace the extensions display state.  Only a server receipt or the next
  authoritative view may change server-derived fields."
  [{:keys [new-state]}]
  (action [env]
    (let [{:keys [state]} env]
      (swap! state
             (fn [db]
               (let [current (:ui/extensions db)
                     stale? (and (integer? (:version new-state))
                                 (integer? (:version current))
                                 (< (:version new-state)
                                    (:version current)))]
                 (if stale?
                   db
                   (assoc db :ui/extensions new-state))))))))

(m/defmutation set-browser-state
  [{:keys [new-state]}]
  (action [env]
    (let [{:keys [state]} env]
      (swap! state assoc :ui/browser new-state))))

(m/defmutation set-theme
  [{:keys [theme]}]
  (action [env]
    (let [{:keys [state]} env]
      (swap! state assoc :ui/theme theme))))

(m/defmutation set-font-scale
  [{:keys [scale]}]
  (action [env]
    (let [{:keys [state]} env]
      (swap! state assoc :ui/font-scale scale))))

(m/defmutation set-appearance
  [{:keys [theme font-scale contrast reduced-motion?]}]
  (action [env]
    (let [{:keys [state]} env]
      (swap! state assoc
             :ui/theme theme
             :ui/font-scale font-scale
             :ui/contrast contrast
             :ui/reduced-motion reduced-motion?))))

(m/defmutation set-tab
  [{:keys [group tab]}]
  (action [env]
    (let [{:keys [state]} env]
      (swap! state assoc group tab))))

(m/defmutation request-find
  "Open the conversation's in-page find bar.  The sequence is local UI state;
  it never creates a model request."
  [{:keys [seq]}]
  (action [env]
    (let [{:keys [state]} env]
      (swap! state assoc :ui/find-seq seq))))

(m/defmutation set-session-limit
  "Grow the rendered session slice.  The server list is unchanged."
  [{:keys [limit]}]
  (action [env]
    (let [{:keys [state]} env]
      (swap! state assoc :ui/session-limit (max 200 (long (or limit 200)))))))

(m/defmutation set-viewport-width
  [{:keys [width]}]
  (action [env]
    (let [{:keys [state]} env]
      (swap! state
             (fn [db]
               (let [was-overlay? (a11y/overlay-layout?
                                   (:ui/viewport-width db))
                     overlay? (a11y/overlay-layout? width)]
                 (cond-> (assoc db :ui/viewport-width width)
                   (and (not was-overlay?) overlay?)
                   (assoc :ui/sidebar-open false :ui/aux-open false)

                   (and was-overlay? (not overlay?))
                   (assoc :ui/sidebar-open false
                          :ui/sidebar-return-id "wb-menu"
                          :ui/aux-open true
                          :ui/aux-return-id "wb-aux-toggle"))))))))

(m/defmutation set-draft-storage
  [{:keys [status error]}]
  (action [env]
    (let [{:keys [state]} env]
      (swap! state assoc :ui/draft-storage status
             :ui/draft-storage-error error))))

(m/defmutation update-search-state
  "Merge the local search selection.  The server remains the authority for
  which services exist and whether a run succeeded."
  [{:keys [session-id changes]}]
  (action [env]
    (let [{:keys [state]} env]
      (swap! state update-in [:ui/search-state session-id]
             (fn [current] (merge (or current {}) (or changes {})))))))

(m/defmutation set-search
  [{:keys [value]}]
  (action [env]
    (let [{:keys [state]} env]
      (swap! state assoc :ui/search value))))

(m/defmutation set-session-filter
  "Filter the visible session list locally.  It never removes a server entity
  or changes the selected session."
  [{:keys [value]}]
  (action [env]
    (let [{:keys [state]} env]
      (swap! state assoc :ui/session-filter (or value "")))))

(m/defmutation toggle-board-row
  [{:keys [swarm-id]}]
  (action [env]
    (let [{:keys [state]} env]
      (swap! state update-in [:ui/board-expanded swarm-id] not))))

(m/defmutation notify
  [{:keys [notice]}]
  (action [env]
    (let [{:keys [state]} env]
      (swap! state update :ui/notices
             (fn [notices]
               (vec (take-last 20 (conj (vec (or notices [])) notice))))))))

(m/defmutation dismiss-notice
  [{:keys [id]}]
  (action [env]
    (let [{:keys [state]} env]
      (swap! state update :ui/notices
             (fn [notices] (filterv #(not= id (:id %)) (or notices [])))))))
