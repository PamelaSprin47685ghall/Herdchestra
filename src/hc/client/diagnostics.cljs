(ns hc.client.diagnostics
  "Public facade for the statistics and diagnostics workspace.

  Integration seam for the workbench:

    (install! app)          register the receipt listener and app reference
    (sync! db)              project server diagnostics and connection state
    (diagnostics-element db) React element for the settings data section
    (ensure-stats! db)      optional load-once helper

  State lives at `:ui/diagnostics`; if the key is absent the facade uses the
  package's initial state.  All domain values come from server receipts."
  (:require [hc.client.diagnostics.bridge :as bridge]
            [hc.client.diagnostics.model :as model]
            [hc.client.diagnostics.view :as view]))

(def initial-state
  "Return a fresh diagnostics state."
  model/initial-state)

(defn install!
  [app]
  (bridge/install! app))

(defn sync!
  [db]
  (bridge/sync! db))

(defn ensure-stats!
  [db]
  (bridge/ensure-stats! db))

(defn dispatch!
  [action]
  (bridge/dispatch! action))

(defn diagnostics-element
  [db]
  (bridge/diagnostics-element db))

(def reduce-action
  "Pure reducer for tests and embedding hosts."
  model/reduce-action)

(def diagnostics-css
  "Scoped stylesheet string used by the view."
  view/diagnostics-css)
