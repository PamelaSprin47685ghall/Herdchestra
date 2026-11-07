(ns hc.client.workbench.app
  "Fulcro application root for the workbench."
  (:require [com.fulcrologic.fulcro.application :as application]
            [com.fulcrologic.fulcro.components :as comp]
            [hc.client.files.browser :as files-browser]
            [hc.client.workbench.views.shell :as shell]))

(comp/defsc Root
  [this props]
  {:query [:ui/route
           :ui/selected-session-id
           :ui/connection
           :ui/theme
           :ui/font-scale
           :ui/contrast
           :ui/reduced-motion
           :ui/viewport-width
           :ui/platform
           :ui/sidebar-open
           :ui/aux-open
           :ui/sidebar-return-id
           :ui/aux-return-id
           :ui/focus-target
           :ui/focus-seq
           :ui/server-revision
           :ui/notices
           :ui/draft
           :ui/conversation
           :ui/draft-reset
           :ui/draft-storage
           :ui/stream
           :ui/command
           :ui/ask
           :ui/settings
           :ui/materials
           :ui/extensions
           :ui/board-expanded
           :ui/session-list
           :ui/session-filter
           :ui/find-seq
           :ui/session-limit
           :ui/assistants-tab
           :ui/materials-tab
           :ui/aux-tab
           {:ui/files (comp/get-query files-browser/FilesPane)}
           :ui/diagnostics
           :ui/backup
           :ui/browser
           :ui/search
           :ui/search-state]}
  (shell/workbench this props))

(defonce app-ref
  (atom nil))

(defn start!
  [initial-db]
  (let [app (application/fulcro-app {:initial-db initial-db})]
    (reset! app-ref app)
    (application/mount! app Root "app" {:initialize-state? false})
    app))

(defn current-app
  []
  @app-ref)

(defn remount!
  []
  (when-let [app @app-ref]
    (application/mount! app Root "app" {:initialize-state? false})))

(defn render!
  []
  (when-let [app @app-ref]
    (application/schedule-render! app)))
