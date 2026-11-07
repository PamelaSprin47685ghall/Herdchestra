(ns hc.client.extensions
  "Public client surface for MCP and skill management.

  The panel reads one server snapshot, sends real `extensions/*` commands
  through the workbench Sente transport, and changes server-derived state only
  from an accepted receipt plus the next authoritative view."
  (:require [hc.client.extensions.model :as model]
            [hc.client.extensions.react :as react]))

(def initial-state model/initial-state)
(def snapshot-from-db model/snapshot-from-db)
(def reduce-action model/reduce-action)
(def apply-receipt model/apply-receipt)
(def flush-outbox! model/flush-outbox!)

(defn extensions-element
  [state dispatch!]
  (react/extensions-element state dispatch!))

(def ExtensionsView react/ExtensionsView)
