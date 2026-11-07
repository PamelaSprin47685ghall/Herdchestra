(ns hc.server.commands
  "Public entry point for command-id and command-epoch deduplication.

  Authority: AGENTS.md §5.1 and §2.5. This package is a pure state machine.
  The session Owner serializes calls and persists the returned state. The
  package performs no I/O and keeps no process-local mutable state.

  One accepted command is one state transition:

    {:ok? true
     :state state'
     :receipt receipt
     :replayed? false
     :effects []}

  A refusal leaves the state untouched:

    {:ok? false
     :state state
     :error {:code ... :message ... :details ...}
     :effects []}"
  (:require [hc.server.commands.epoch :as epoch]
            [hc.server.commands.receipts :as receipts]))

(defn new-state
  "Create an empty command domain with no active epoch."
  []
  (merge epoch/empty-window
         {:receipts receipts/empty-registry}))

(defn active-epoch
  "Return the current command epoch, or nil when none is active."
  [state]
  (epoch/active-epoch state))

(defn retired-through
  "Return the highest retired epoch. Every epoch at or below it is retired."
  [state]
  (epoch/retired-through state))

(defn receipt-for
  "Return the receipt accepted for COMMAND-KEY in the current epoch, or nil.

  COMMAND-KEY is a command-id string, a `[scope command-id]` vector, or the
  OwnerPort query map `{:command-id .. :scope .. :session-id ..}`.  A query
  map tries the explicit `:command-key`, then the scoped key, then the bare
  command-id.  This keeps a receipt lookup working across the gateway and
  router layers without making the caller know which key shape was stored.
  Receipts from retired epochs are cleared, so a nil result means the client
  must re-read the current state instead of replaying an old command."
  [state command-key]
  (let [keys (if (map? command-key)
               (let [{:keys [command-key command-id scope session-id]}
                     command-key
                     scope (or scope session-id)]
                 (cond-> []
                   (some? command-key) (conj command-key)
                   (and (some? scope) (some? command-id))
                   (conj [scope command-id])
                   (some? command-id) (conj command-id)))
               [command-key])]
    (some #(get-in state [:receipts % :receipt]) keys)))

(defn- window
  [state]
  (select-keys state [:command-epoch :retired-through]))

(defn- apply-window
  [state window]
  (merge state window))

(defn- clear-receipts
  "Clear the receipt registry and its command-id index, when present.

  The index is a derived projection of the registry.  An epoch transition
  that cleared only `:receipts` would leave index entries pointing at
  commands that the new epoch no longer accepts."
  [state]
  (cond-> (assoc state :receipts receipts/empty-registry)
    (contains? state :receipt-index)
    (assoc :receipt-index {})))

(defn- remember-index
  "Keep the optional command-id index aligned with one receipt entry.

  A state without an index stays without one.  The durable gate always has
  an index; a pure host may omit it."
  [state command-id command-key entry]
  (if (and (map? (:receipt-index state)) (map? entry))
    (assoc-in state [:receipt-index command-id]
              (assoc entry
                     :command-id command-id
                     :command-key command-key))
    state))

(defn issue-epoch
  "Retire the current epoch, if any, and issue the next epoch.

  The new epoch starts with an empty receipt registry and command-id index.
  The returned state is the complete next state."
  [state]
  (let [{:keys [window epoch retired-epoch effects]}
        (epoch/issue (window state))]
    {:ok? true
     :state (clear-receipts (apply-window state window))
     :epoch epoch
     :retired-epoch retired-epoch
     :effects effects}))

(defn retire-epoch
  "Retire the current epoch and clear its receipts and command-id index.

  The transition is idempotent when no epoch is active. The returned state
  is the complete next state."
  [state]
  (let [{:keys [window retired-epoch effects]}
        (epoch/retire (window state))]
    {:ok? true
     :state (clear-receipts (apply-window state window))
     :retired-epoch retired-epoch
     :effects effects}))

(defn- accepted-receipt
  [request]
  {:status :accepted
   :command-id (:command-id request)
   :command-epoch (:command-epoch request)})

(defn- fail
  [state code message details]
  {:ok? false
   :state state
   :error (cond-> {:code code :message message}
            (some? details) (assoc :details details))
   :effects []})

(defn- accept*
  [state request receipt default-receipt?]
  (cond
    (not (map? request))
    (fail state :COMMAND_REQUEST_INVALID
          "command must be a map"
          {:request request})

    (not (receipts/valid-command-id? (:command-id request)))
    (fail state :COMMAND_ID_REQUIRED
          "command-id must be a non-blank string"
          {:command-id (:command-id request)})

    (not (epoch/valid-epoch? (:command-epoch request)))
    (fail state :COMMAND_EPOCH_INVALID
          "command-epoch must be a positive integer"
          {:command-epoch (:command-epoch request)})

    (not (map? (:receipts state)))
    (fail state :COMMAND_STATE_INVALID
          "command state has no receipt registry"
          {:receipts (:receipts state)})

    :else
    (let [command-id (:command-id request)
          command-key (or (:command-key request) command-id)
          command-epoch (:command-epoch request)
          payload (:payload request)
          redact? (boolean (:redact-payload? request))
          status (epoch/status (window state) command-epoch)]
      (if-not (receipts/valid-command-key? command-key)
        (fail state :COMMAND_KEY_INVALID
              "command-key must be a command-id string or [scope command-id]"
              {:command-key command-key})
        (case status
          :retired
        (fail state :COMMAND_EPOCH_RETIRED
              "command epoch is retired; re-read the current state before resubmitting"
              {:command-id command-id
               :command-epoch command-epoch
               :current-epoch (:command-epoch state)
               :action :resync-state})

        :unknown
        (fail state :COMMAND_EPOCH_UNKNOWN
              "command epoch was not issued by this server; re-read the current state"
              {:command-id command-id
               :command-epoch command-epoch
               :current-epoch (:command-epoch state)
               :action :resync-state})

        :active
        (let [stored-receipt (if default-receipt?
                               (accepted-receipt request)
                               receipt)
              result (receipts/remember (:receipts state)
                                        command-key
                                        command-epoch
                                        payload
                                        stored-receipt
                                        redact?)]
          (if (:ok? result)
            {:ok? true
             :state (remember-index
                     (assoc state :receipts (:registry result))
                     command-id command-key (:entry result))
             :receipt (:receipt (:entry result))
             :replayed? (boolean (:duplicate? result))
             :effects []}
            (case (:reason result)
              :payload-mismatch
              (fail state :COMMAND_ID_PAYLOAD_CONFLICT
                    "command-id already has an accepted receipt for another payload"
                    (cond-> {:command-id command-id
                             :command-epoch command-epoch
                             :command-key command-key
                             :reason :payload-mismatch}
                      (get-in result [:entry :payload-redacted?])
                      (assoc :payload-redacted? true
                             :accepted-payload-digest
                             (get-in result [:entry :payload-digest])
                             :received-payload-digest
                             (receipts/payload-digest payload))

                      (not (get-in result [:entry :payload-redacted?]))
                      (assoc :accepted-payload
                             (get-in result [:entry :payload])
                             :received-payload payload)))

              :epoch-mismatch
              (fail state :COMMAND_STATE_INVALID
                    "command receipt belongs to a different epoch"
                    {:command-id command-id
                     :command-epoch command-epoch
                     :reason :epoch-mismatch
                     :receipt-epoch (get-in result [:entry :command-epoch])}))))

        ;; Defensive: epoch validation above makes this branch unreachable.
        (fail state :COMMAND_EPOCH_INVALID
              "command-epoch must be a positive integer"
              {:command-epoch command-epoch}))))))

(defn accept
  "Accept one client command under the current epoch.

  REQUEST is {:command-id <non-blank string>
              :command-epoch <positive integer>
              :payload <any EDN value>}. The payload is compared with `=`.
  A request may set `:redact-payload? true`; only a canonical SHA-256
  digest is stored and mismatch details contain digests, never the payload.

  The optional RECEIPT is stored verbatim and returned on replay. Without
  it, the receipt is {:status :accepted :command-id ... :command-epoch ...}.

  Replay of the same command-id with the same payload returns the original
  receipt, sets :replayed? true, and does not change the state. A different
  payload returns :COMMAND_ID_PAYLOAD_CONFLICT. A retired epoch returns
  :COMMAND_EPOCH_RETIRED. An epoch this server never issued returns
  :COMMAND_EPOCH_UNKNOWN. All refusals leave the state untouched."
  ([state request]
   (accept* state request nil true))
  ([state request receipt]
   (accept* state request receipt false)))

(defn complete
  "Replace the stored receipt for an accepted COMMAND-KEY.

  COMMAND-KEY is a command-id string or a `[scope command-id]` vector.
  Use this after a durable gate accepted a placeholder receipt and the
  business transition produced the final receipt.  A replay then returns the
  final receipt.  An unknown key, an invalid key, or a receipt from a
  non-current epoch is refused and leaves the state unchanged."
  [state command-key receipt]
  (cond
    (not (receipts/valid-command-key? command-key))
    (fail state :COMMAND_KEY_INVALID
          "command-key must be a command-id string or [scope command-id]"
          {:command-key command-key})

    (not (map? (:receipts state)))
    (fail state :COMMAND_STATE_INVALID
          "command state has no receipt registry"
          {:receipts (:receipts state)})

    :else
    (let [entry (receipts/lookup (:receipts state) command-key)]
      (cond
        (nil? entry)
        (fail state :COMMAND_ID_UNKNOWN
              "command-key has not been accepted in the current epoch"
              {:command-key command-key
               :command-epoch (:command-epoch state)})

        (not= (:command-epoch state) (:command-epoch entry))
        (fail state :COMMAND_STATE_INVALID
              "command receipt belongs to a different epoch"
              {:command-key command-key
               :command-epoch (:command-epoch state)
               :receipt-epoch (:command-epoch entry)})

        :else
        (let [result (receipts/replace-receipt (:receipts state)
                                               command-key
                                               receipt)
              command-id (if (vector? command-key)
                           (second command-key)
                           command-key)]
          {:ok? true
           :state (remember-index
                   (assoc state :receipts (:registry result))
                   command-id command-key (:entry result))
           :receipt receipt
           :effects []})))))

(defn- receipt-index-violations
  [state]
  (let [index (:receipt-index state)
        registry (:receipts state)]
    (cond
      (nil? index) []
      (not (map? index)) [{:invariant :receipt-index-not-a-map}]
      :else
      (into []
            (mapcat
             (fn [[command-id entry]]
               (let [command-key (:command-key entry)
                     receipt-entry
                     (when (and (map? registry)
                                (receipts/valid-command-key? command-key))
                       (get registry command-key))]
                 (cond-> []
                   (not (receipts/valid-command-id? command-id))
                   (conj {:invariant :receipt-index-key-invalid
                          :command-id command-id})

                   (not (map? entry))
                   (conj {:invariant :receipt-index-entry-not-a-map
                          :command-id command-id})

                   (and (map? entry)
                        (not (receipts/valid-command-key? command-key)))
                   (conj {:invariant :receipt-index-key-invalid
                          :command-id command-id
                          :command-key command-key})

                   (and (map? entry)
                        (receipts/valid-command-key? command-key)
                        (nil? receipt-entry))
                   (conj {:invariant :receipt-index-orphan
                          :command-id command-id
                          :command-key command-key})

                   (and (map? entry)
                        (some? receipt-entry)
                        (not= (:receipt entry)
                              (:receipt receipt-entry)))
                   (conj {:invariant :receipt-index-receipt-mismatch
                          :command-id command-id
                          :command-key command-key})

                   (and (map? entry)
                        (contains? entry :command-id)
                        (not= command-id (:command-id entry)))
                   (conj {:invariant :receipt-index-command-id-mismatch
                          :command-id command-id
                          :entry-command-id (:command-id entry)}))))
             index)))))

(defn check-invariants
  "Return a vector of domain invariant violations. An empty vector is valid."
  [state]
  (if-not (map? state)
    [{:invariant :state-not-a-map}]
    (let [registry (:receipts state)
          current-epoch (:command-epoch state)
          registry-violations (receipts/check registry)
          index-violations (receipt-index-violations state)
          stale (if (map? registry)
                  (for [[command-key entry] registry
                        :when (and (map? entry)
                                   (not= current-epoch
                                         (:command-epoch entry)))]
                    {:invariant :receipt-epoch-mismatch
                     :command-key command-key
                     :command-epoch (:command-epoch entry)
                     :current-epoch current-epoch})
                  [])
          orphan (if (and (nil? current-epoch)
                          (map? registry)
                          (seq registry))
                   [{:invariant :receipts-without-active-epoch
                     :count (count registry)}]
                   [])]
      (into [] (concat (epoch/check (window state))
                       registry-violations
                       index-violations
                       stale
                       orphan)))))

(defn redact-state
  "Remove legacy plaintext secret payloads from receipts and the index."
  [state]
  (cond-> state
    (map? (:receipts state))
    (update :receipts receipts/redact-registry)

    (map? (:receipt-index state))
    (update :receipt-index receipts/redact-registry)))
