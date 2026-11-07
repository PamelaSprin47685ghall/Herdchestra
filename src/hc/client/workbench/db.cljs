(ns hc.client.workbench.db
  "Initial Fulcro database for the workbench.

  Server entities are absent until a confirmed `STATE_SNAPSHOT` arrives.  The
  shell never seeds fake sessions, tasks, or messages to make an empty screen
  look busy."
  (:require [hc.client.conversation.draft :as draft]
            [hc.client.conversation :as conversation]
            [hc.client.extensions :as extensions]
            [hc.client.backup.model :as backup-model]
            [hc.client.browser :as browser]
            [hc.client.diagnostics :as diagnostics]
            [hc.client.conversation.stream :as stream]
            [hc.client.files.browser :as files-browser]
            [hc.client.materials :as materials]
            [hc.client.search.model :as search-model]
            [hc.client.settings :as settings]
            [hc.client.workbench.model :as model]))

(defn initial-db
  []
  {:ui/route :sessions
   :ui/selected-session-id nil
   :ui/connection (model/connection-view
                   {:phase :disconnected
                    :subscription 0})
   :ui/theme :system
   :ui/font-scale 100
   :ui/contrast :normal
   :ui/reduced-motion false
   :ui/viewport-width 1280
   :ui/platform :desktop
   :ui/sidebar-open false
   ;; `main/init` opens the details column in the wide band and leaves the
   ;; narrow drawer closed; this default is the safe pre-layout state.
   :ui/aux-open false
   :ui/sidebar-return-id "wb-menu"
   :ui/aux-return-id "wb-aux-toggle"
   :ui/focus-target nil
   :ui/focus-seq 0
   :ui/server-revision 0
   :ui/notices []
   :ui/draft {}
   ;; `hc.client.conversation/initial-state` is a map, not a constructor.
   ;; Calling it as a function breaks the production bundle at boot.
   :ui/conversation conversation/initial-state
   :ui/draft-reset {}
   :ui/draft-storage :unknown
   :ui/stream {}
   :ui/command {}
   :ui/ask {}
   :ui/settings (settings/initial-state)
   :ui/materials (materials/initial-state)
   :ui/extensions (extensions/initial-state)
   :ui/board-expanded {}
   :ui/session-list []
   :ui/session-filter ""
   :ui/find-seq 0
   :ui/session-limit 200
   :ui/assistants-tab :roles
   :ui/materials-tab :files
   :ui/aux-tab :details
   :ui/files (files-browser/initial-state)
   :ui/diagnostics (diagnostics/initial-state)
   :ui/backup (backup-model/initial-state)
   :ui/browser (browser/initial-state)
   :ui/search ""
   :ui/search-state {}})

(defn merge-local!
  "Merge persisted local state into a fresh database without inventing server
  entities."
  [db {:keys [drafts settings status scroll]}]
  (let [scroll (into {}
                     (keep (fn [[session-id top]]
                             (when (number? top)
                               [(str session-id) {:scroll-top (max 0 (long top))}])))
                     (or scroll {}))]
    (cond-> (-> db
                (assoc :ui/draft (or drafts {}))
                (assoc :ui/draft-storage (or status :unknown))
                (cond->
                  (seq scroll)
                  (update :ui/conversation
                          (fn [conversation]
                            (update conversation :streams
                                    (fn [streams]
                                      (merge-with merge (or streams {}) scroll)))))
                  (seq scroll)
                  (update :ui/stream
                          (fn [streams]
                            (merge-with merge (or streams {}) scroll)))))
      (:theme settings) (assoc :ui/theme (:theme settings))
      (:font-scale settings) (assoc :ui/font-scale (:font-scale settings))
      (:contrast settings) (assoc :ui/contrast (:contrast settings))
      (some? (:reduced-motion settings)) (assoc :ui/reduced-motion (:reduced-motion settings)))))

(defn empty-draft-for
  [session-id]
  (draft/draft {} session-id))

(defn empty-stream-for
  [session-id]
  (stream/initial))
