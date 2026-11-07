(ns hc.client.backup.ui
  "Public backup workspace surface for the thin workbench.

  `backup-element` accepts the current Fulcro database and returns a React
  element.  `install!` wires receipts and the first status read.  The panel is
  deliberately self-contained: settings/workbench only mounts it and does not
  edit backup state."
  (:require [hc.client.backup.bridge :as bridge]
            [hc.client.backup.react :as react-view]
            [hc.client.workbench.transport :as transport]))

(def BackupPage
  "React function component for a pre-projected backup state."
  react-view/BackupPage)

(defn backup-element
  "Create the backup panel element from the Fulcro DB."
  [db]
  (let [state (bridge/state-from-db db)]
    (react-view/backup-element state bridge/dispatch!
                               (transport/open?))))

(defn install!
  "Install the receipt listener.  Call once after the Fulcro app starts."
  [app]
  (bridge/install! app))

(defn sync!
  "Project the current Fulcro database into backup state."
  [db]
  (bridge/sync! db))
