(ns hc.compose.owner.commands
  "Command-id and command-epoch gate for one Owner.

  The gate is a pure state machine.  The Owner serializes calls and commits
  the returned state.  A command carries a server-issued epoch; an epoch
  that is not current is never treated as new work.  A replay of the same
  command id with the same payload returns the stored receipt; a different
  payload is a conflict.

  The registry is injectable.  `local-registry` is the default.  A host that
  already owns a command protocol may pass an equivalent implementation;
  this namespace does not require hc.server.*."
  (:require [clojure.string :as str]))

(defprotocol Registry
  (new-state [registry]
    "Return an empty gate state with epoch 1 active.")
  (accept [registry state request]
    "Accept REQUEST = {:command-id :command-epoch :payload}.")
  (accept-with-receipt [registry state request receipt]
    "Accept REQUEST and store RECEIPT for a replay.")
  (issue-epoch [registry state]
    "Retire the current epoch and open the next one.")
  (receipt-for [registry state command-id]
    "Return the accepted receipt, or nil."))

(defn- valid-command-id?
  [command-id]
  (and (string? command-id) (not (str/blank? command-id))))

(defn- valid-epoch?
  [epoch]
  (and (integer? epoch) (pos? epoch)))

(defn- fail
  [state code message details]
  {:ok? false
   :state state
   :error (cond-> {:code code :message message}
            (some? details) (assoc :details details))})

(defn- status
  [{:keys [command-epoch retired-through]} epoch]
  (cond
    (not (valid-epoch? epoch)) :invalid
    (= epoch command-epoch) :active
    (<= epoch (or retired-through 0)) :retired
    :else :unknown))

(defn- accept*
  [state request receipt]
  (let [{:keys [command-id command-epoch payload]} request]
    (cond
      (not (map? request))
      (fail state :COMMAND_REQUEST_INVALID "command must be a map"
            {:request request})

      (not (valid-command-id? command-id))
      (fail state :COMMAND_ID_REQUIRED
            "command-id must be a non-blank string"
            {:command-id command-id})

      (not (valid-epoch? command-epoch))
      (fail state :COMMAND_EPOCH_INVALID
            "command-epoch must be a positive integer"
            {:command-epoch command-epoch})

      :else
      (case (status state command-epoch)
        :retired
        (fail state :COMMAND_EPOCH_RETIRED
              "command epoch is retired; re-read the current state"
              {:command-id command-id
               :command-epoch command-epoch
               :current-epoch (:command-epoch state)
               :action :resync-state})

        :unknown
        (fail state :COMMAND_EPOCH_UNKNOWN
              "command epoch was not issued by this Owner"
              {:command-id command-id
               :command-epoch command-epoch
               :current-epoch (:command-epoch state)
               :action :resync-state})

        :invalid
        (fail state :COMMAND_EPOCH_INVALID
              "command-epoch must be a positive integer"
              {:command-epoch command-epoch})

        :active
        (let [existing (get-in state [:receipts command-id])
              stored (or receipt
                         {:status :accepted
                          :command-id command-id
                          :command-epoch command-epoch})]
          (cond
            (and existing (= payload (:payload existing)))
            {:ok? true
             :state state
             :receipt (:receipt existing)
             :replayed? true}

            existing
            (fail state :COMMAND_ID_PAYLOAD_CONFLICT
                  "command-id was already accepted with a different payload"
                  {:command-id command-id
                   :accepted-payload (:payload existing)
                   :received-payload payload})

            :else
            {:ok? true
             :state (assoc-in state [:receipts command-id]
                              {:command-epoch command-epoch
                               :payload payload
                               :receipt stored})
             :receipt stored
             :replayed? false}))))))

(defn local-registry
  "The default registry.  Epoch 1 is active from the first state."
  []
  (reify Registry
    (new-state [_]
      {:command-epoch 1
       :retired-through 0
       :receipts {}})

    (accept [_ state request]
      (accept* state request nil))

    (accept-with-receipt [_ state request receipt]
      (accept* state request receipt))

    (issue-epoch [_ state]
      (let [current (:command-epoch state)
            high-water (max (or (:retired-through state) 0)
                            (or current 0))
            next-epoch (inc high-water)]
        {:ok? true
         :state {:command-epoch next-epoch
                 :retired-through high-water
                 :receipts {}}
         :epoch next-epoch
         :retired-epoch current}))

    (receipt-for [_ state command-id]
      (get-in state [:receipts command-id :receipt]))))
