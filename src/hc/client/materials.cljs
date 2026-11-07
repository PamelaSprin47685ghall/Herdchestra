(ns hc.client.materials
  "Public materials workspace.

  The component manages long-term memory, worldbooks and instruction cards
  from the confirmed `:materials/*` snapshot.  Commands leave through the
  workbench transport; a server receipt, followed by an authoritative
  `:materials/snapshot`, is the only path that changes server-derived state."
  (:require [hc.client.materials.model :as model]
            [hc.client.materials.react :as react-view]))

(def initial-state
  model/initial-state)

(def snapshot-from-db
  model/snapshot-from-db)

(defn apply-snapshot
  [state snapshot]
  (:state (model/reduce-action state {:type :materials/apply-snapshot
                                      :snapshot snapshot})))

(defn revision
  "The server materials revision carried by STATE, or nil before any snapshot."
  [state]
  (:revision state))

(defn begin-session
  "Drop server-derived materials state before loading a different session."
  [state session-id]
  (model/begin-session state session-id))

(defn reduce-action
  [state action]
  (model/reduce-action state action))

(defn apply-receipt
  [state receipt]
  (:state (model/reduce-action state {:type :materials/receive-receipt
                                      :receipt receipt})))

(defn drain-orphan-receipts
  "Re-apply receipts parked in `:orphan-receipts` whose command has since
  arrived in `:commands`."
  [state]
  (model/drain-orphan-receipts state))

(defn turn-selection
  "The per-turn cards and values to attach to the next user submission."
  [state]
  (model/turn-selection state))

(defn attach-turn-selection
  "Attach per-turn cards and values to a draft snapshot."
  [state snapshot]
  (model/attach-turn-selection state snapshot))

(defn clear-turn-selection
  "Drop the local per-turn staging after an accepted send."
  [state]
  (model/clear-turn-selection state))

(defn turn-selection-matches?
  "True when STATE still carries the submitted per-turn selection."
  [state submitted]
  (model/turn-selection-matches? state submitted))

(defn flush-outbox!
  "Hand every queued envelope to SUBMIT! exactly once.

  SUBMIT! returns true only when the transport accepted the envelope.  A
  false result marks the command as unsent; it is never reported as applied."
  [state submit!]
  (if-not submit!
    state
    (loop [state state]
      (if-let [envelope (first (model/pending-outbox state))]
        (let [command-id (:command-id envelope)
              state (model/mark-submitting state command-id)
              sent? (try (boolean (submit! envelope))
                         (catch :default _ false))
              state (if sent?
                      state
                      (model/mark-send-failed state command-id
                                              "实时通道未接受该命令。"))]
          (recur state))
        state))))

(defn materials-element
  "Create a React element for STATE, DISPATCH! and SECTION."
  [state dispatch! section]
  (react-view/materials-element state dispatch! section))

(def MaterialsView react-view/MaterialsView)
