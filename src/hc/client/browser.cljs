(ns hc.client.browser
  "Public managed-browser workspace.

  The component shows server-reported tabs, observations, takeover state and
  page cards.  Commands leave through the workbench transport; a server
  receipt followed by an authoritative snapshot is the only path that changes
  server-derived state."
  (:require [hc.client.browser.model :as model]
            [hc.client.browser.react :as react-view]))

(def initial-state
  model/initial-state)

(defn apply-snapshot
  [state snapshot]
  (model/apply-snapshot state snapshot))

(defn apply-cards
  [state cards]
  (model/apply-cards state cards))

(defn apply-observation
  [state observation]
  (model/apply-observation state observation))

(defn apply-receipt
  [state receipt]
  (model/apply-receipt state receipt))

(defn apply-connection
  [state phase epoch session-id]
  (model/apply-connection state phase epoch session-id))

(defn reduce-action
  [state action]
  (model/reduce-action state action))

(defn queue-command
  ([state kind payload] (model/queue-command state kind payload))
  ([state kind payload opts] (model/queue-command state kind payload opts)))

(defn pending-outbox
  [state]
  (model/pending-outbox state))

(defn mark-submitting
  [state command-id]
  (model/mark-submitting state command-id))

(defn mark-send-failed
  [state command-id message]
  (model/mark-send-failed state command-id message))

(defn command-allowed?
  [state]
  (model/command-allowed? state))

(defn browser-element
  "Create a React element for STATE and DISPATCH!."
  [state dispatch!]
  (react-view/browser-element state dispatch!))

(def BrowserView react-view/BrowserView)
