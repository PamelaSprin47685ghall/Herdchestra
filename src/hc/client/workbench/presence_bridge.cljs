(ns hc.client.workbench.presence-bridge
  "One-shot client presence attachment.

  A reloaded tab used to reach the Owner as a brand-new client and then had no
  command that an observer may send.  The stable handshake identity in
  `hc.client.workbench.transport` fixes the usual reload; this bridge closes
  the remaining case where the confirmed baseline reports no controller at
  all (a fresh session or an Owner restart).

  The bridge subscribes to the installed server revision and sends one
  `:session/attach-client` per `(session-id, subscription-id)`.  The pair is
  recorded before the command leaves, so a receipt, a render, or a repeated
  baseline can never turn attachment into a loop.  A controller already
  reported by the server is never challenged here; taking control from another
  client stays an explicit operator action."
  (:require [clojure.string :as str]
            [com.fulcrologic.fulcro.application :as application]
            [hc.client.workbench.commands :as commands]
            [hc.client.workbench.model :as model]
            [hc.client.workbench.sync-bridge :as bridge]
            [hc.client.workbench.transport :as transport]
            [taoensso.timbre :as log]))

(defonce app-ref
  (atom nil))

;; `(session-id, subscription-id)` pairs already asked to attach.  The atom
;; survives a hot reload, so re-installing the bridge cannot repeat the send.
(defonce attempted
  (atom #{}))

(defonce ^:private watch-key
  ::presence)

(defn- non-blank-id
  [value]
  (let [value (cond
                (string? value) value
                (keyword? value) (name value)
                (some? value) (str value)
                :else nil)]
    (when (and value (not (str/blank? value)))
      value)))

(defn local-client-id
  "The stable handshake identity the gateway stamps on this client's commands."
  []
  (or (non-blank-id (:client-id @transport/channel))
      ;; The shell may render before `transport/start!` stores the id.  The
      ;; storage-backed identity is the same value the handshake will use.
      (transport/stable-client-id)))

(defn attach-decision
  "Pure attachment decision.

  Returns `{:attach? bool :reason keyword}`; a true decision also carries the
  `:session-id` and the `:key` that must be remembered before sending.  The
  client only attaches when the confirmed baseline names no controller: a
  named controller is never silently displaced."
  [{:keys [session-id synced? controller-id local-client-id subscription-id
           attempted]}]
  (let [key (when (and session-id (some? subscription-id))
              [session-id subscription-id])]
    (cond
      (nil? session-id)
      {:attach? false :reason :no-session}

      (not synced?)
      {:attach? false :reason :not-synced}

      (nil? subscription-id)
      {:attach? false :reason :no-subscription}

      (nil? local-client-id)
      {:attach? false :reason :no-client-id}

      (some? controller-id)
      {:attach? false :reason :controlled}

      (contains? attempted key)
      {:attach? false :reason :already-attempted}

      :else
      {:attach? true :reason :free :session-id session-id :key key})))

(defn attempt!
  "Send at most one attach for the current baseline.  Safe from a watch: an
  already-attempted pair returns without touching the transport."
  []
  (when-let [app @app-ref]
    (try
      (let [db (application/current-state app)
            decision (attach-decision
                      {:session-id (model/selected-session-id db)
                       :synced? (bridge/synced?)
                       :controller-id (non-blank-id
                                       (model/field db :session/controller-id))
                       :local-client-id (local-client-id)
                       :subscription-id (bridge/subscription)
                       :attempted @attempted})]
        (if (and (:attach? decision)
                 (nil? (bridge/command-epoch-for-session
                        (:session-id decision))))
          ;; No Owner epoch is published yet (the session Owner may still be
          ;; starting).  Wait for the next revision instead of sending a
          ;; command that the gate would refuse.
          (assoc decision :attach? false :reason :no-epoch)
          (do
            (when (:attach? decision)
              (swap! attempted conj (:key decision))
              (commands/attach-client! app (:session-id decision)))
            decision)))
      (catch :default e
        (log/error e "Unable to attach this client to the session")
        {:attach? false :reason :error}))))

(defn install!
  "Subscribe to the confirmed server revision and try the initial attach.

  `main` must call this after `conversation-bridge/install!`; the adapter may
  still be unsynchronized here, and the revision watch will run again once the
  first baseline lands."
  [app]
  (reset! app-ref app)
  (when-let [state-atom (:com.fulcrologic.fulcro.application/state-atom app)]
    (remove-watch state-atom watch-key)
    (add-watch
     state-atom
     watch-key
     (fn [_ _ old new]
       (when (or (not= (:ui/server-revision old)
                       (:ui/server-revision new))
                 (not= (:ui/connection old) (:ui/connection new))
                 (not= (:session/controller-id old)
                       (:session/controller-id new))
                 (not= (:ui/selected-session-id old)
                       (:ui/selected-session-id new)))
         (attempt!))))
    (attempt!)))
