(ns hc.client.sync
  "Pure client-side AG-UI state synchronization.

  This namespace is the stable facade for the browser client.  It has no I/O,
  no atoms, and no Fulcro dependency; the same code runs under Clojure and
  ClojureScript.  Feed transport events to `receive`, then merge `(:db state)`
  into the Fulcro database with `merge-into-db`.

  The five phases are `:disconnected`, `:connecting`,
  `:connected-awaiting-sync`, `:synced`, and `:interrupted`.  Only `:synced`
  allows commands that depend on the latest server state."
  (:require [hc.client.sync.adapter :as adapter]
            [hc.client.sync.connection :as connection]
            [hc.client.sync.json-patch :as json-patch]
            [hc.client.sync.messages :as messages]
            [hc.client.sync.normalize :as normalize]
            [hc.client.sync.object :as object]))

;; ---------------------------------------------------------------------------
;; Connection lifecycle

(defn initial-state
  "Return a disconnected adapter state."
  []
  (adapter/initial-state))

(defn connect
  "Start a new subscription generation.  The last confirmed copy and timestamp
  stay visible while reconnecting."
  [state]
  (adapter/connect state))

(defn channel-opened
  "Record that the transport opened and a baseline snapshot is expected."
  [state]
  (adapter/channel-opened state))

(defn channel-interrupted
  "Record transport loss without discarding the last confirmed copy."
  ([state]
   (adapter/channel-interrupted state))
  ([state reason]
   (adapter/channel-interrupted state reason)))

(defn disconnect
  "Close the transport deliberately."
  [state]
  (adapter/disconnect state))

(defn request-resync
  "Return to `:connected-awaiting-sync` when the transport dropped events or
  its backlog exceeded client capacity."
  [state reason]
  (adapter/request-resync state reason))

(defn phase
  "Return the current connection phase."
  [state]
  (adapter/phase state))

(defn synced?
  "True only after a confirmed baseline, with no outstanding gap and no
  pending large state object."
  [state]
  (adapter/synced? state))

(defn command-allowed?
  "True only when commands may rely on the latest server state: synced, no
  pending large object and no deferred state event."
  [state]
  (adapter/command-allowed? state))

(defn command-epoch
  "Return the server-issued Owner command epoch from the confirmed snapshot,
  or nil.  This is not the AG-UI feed epoch."
  [state]
  (adapter/command-epoch state))

;; ---------------------------------------------------------------------------
;; Event reduction

(defn receive
  "Feed one AG-UI event to the adapter.

  The two-arity form uses the event's subscription ID when present, otherwise
  the current subscription.  The three-arity form binds the event to an
  explicit subscription generation; events from an older generation are
  dropped."
  ([state event]
   (adapter/receive state event))
  ([state subscription event]
   (adapter/receive state subscription event)))

(defn receive-state-snapshot
  "Apply a `STATE_SNAPSHOT` event."
  [state event]
  (adapter/receive-state-snapshot state event))

(defn receive-state-delta
  "Apply a `STATE_DELTA` event."
  [state event]
  (adapter/receive-state-delta state event))

(defn receive-messages-snapshot
  "Apply a `MESSAGES_SNAPSHOT` event."
  [state event]
  (adapter/receive-messages-snapshot state event))

(defn complete-object
  "Apply the verified payload for the pending large state object.

  RESULT is `{:token :digest :size :value :verified?}`.  The transport must
  fetch the exact bytes and verify the domain-separated SHA-256 digest and
  size before calling this function."
  [state result]
  (adapter/complete-object state result))

(defn object-failed
  "Abandon the pending large state object and require a fresh baseline."
  [state reason]
  (adapter/object-failed state reason))

(defn decode-object-text
  "Decode one EDN state-object text into `{:ok? true :value v}` or
  `{:ok? false :error {...}}`."
  [text]
  (object/decode-text text))

(defn object-url
  "Return the gateway URL for a state-object token."
  [token]
  (object/object-url token))

;; ---------------------------------------------------------------------------
;; Normalization and Fulcro integration

(defn to-db
  "Convert a protocol state tree to a fresh Fulcro-style database map."
  [server]
  (normalize/to-db server))

(defn merge-into-db
  "Replace the server-owned region of a Fulcro database with the adapter's
  current normalized view.  Local `:ui/*` keys are untouched."
  ([db state]
   (adapter/merge-into-db db state))
  ([db state extra-server-keys]
   (adapter/merge-into-db db state extra-server-keys)))

(def default-server-keys
  "Top-level keys owned by the server display copy."
  adapter/default-server-keys)

;; ---------------------------------------------------------------------------
;; Pure building blocks, exposed for tests and narrow adapters

(defn apply-state-patch
  "Apply an RFC 6902 patch to a protocol state tree."
  [document ops]
  (json-patch/apply-patch document ops))

(defn apply-messages-snapshot
  "Apply message replacement rules to a protocol state tree."
  [server event]
  (messages/apply-messages-snapshot server event))

(def phases
  "The five connection phases."
  connection/phases)
