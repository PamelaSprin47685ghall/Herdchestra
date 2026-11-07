(ns hc.client.workbench.views.browser
  "Workbench mount for the managed-browser pane.

  The browser module owns its state and view tree; this namespace only reads
  `:ui/browser` and hands the bridge dispatcher to it."
  (:require [hc.client.browser :as browser]
            [hc.client.workbench.browser-bridge :as bridge]))

(defn browser-pane
  [db]
  (browser/browser-element
   (or (:ui/browser db) (browser/initial-state))
   bridge/dispatch!))
