(ns hc.client.sync.connection
  "The five-phase connection state machine from AGENTS.md §7.3.

  The phases are deliberately distinct:

    :disconnected -> :connecting -> :connected-awaiting-sync -> :synced
                                                             -> :interrupted

  `:connected-awaiting-sync` is also the resynchronization phase while the
  channel stays open (a sequence gap or an invalid delta).  `:interrupted`
  means the channel itself is gone.  Commands that depend on current server
  state are allowed only in `:synced`.")

(def phases
  #{:disconnected :connecting :connected-awaiting-sync :synced :interrupted})

(defn- outcome [state kind result details]
  (assoc state :last-outcome
         (merge {:kind kind :result result} details)))

(defn phase
  [state]
  (:phase state))

(defn initial-state
  "Return a disconnected adapter state.  `:subscription` starts at zero; the
  first `connect` creates subscription 1."
  []
  {:phase :disconnected
   :subscription 0
   :epoch nil
   :seq nil
   :synced-at nil
   :reason nil
   :last-outcome {:kind :initial :result :idle}
   :server {}
   :db {}})

(defn synced?
  "True only when the client holds a confirmed snapshot and has applied every
  sequence up to `:seq`."
  [state]
  (and (= :synced (:phase state))
       (some? (:epoch state))
       (some? (:seq state))))

(defn command-allowed?
  "Commands that depend on the latest server state are allowed only after sync."
  [state]
  (synced? state))

(defn channel-open?
  "True while the transport is connected, whether or not a baseline is held."
  [state]
  (contains? #{:connected-awaiting-sync :synced} (:phase state)))

(defn connect
  "Start a new subscription generation.

  The confirmed server copy and its timestamp stay visible while reconnecting.
  The epoch and sequence are cleared because the next baseline defines a new
  boundary; late events from the previous subscription are rejected by
  `:subscription`."
  [state]
  (let [subscription (inc (long (or (:subscription state) 0)))]
    (-> state
        (assoc :phase :connecting
               :subscription subscription
               :epoch nil
               :seq nil
               :reason nil)
        (outcome :connect :applied {:subscription subscription}))))

(defn channel-opened
  "Record that the transport is open and a baseline snapshot is expected."
  [state]
  (if (= :connecting (:phase state))
    (-> state
        (assoc :phase :connected-awaiting-sync
               :reason nil)
        (outcome :channel-opened :applied {}))
    (outcome state :channel-opened :ignored {:phase (:phase state)})))

(defn channel-interrupted
  "Record transport loss without discarding the last confirmed server copy."
  ([state]
   (channel-interrupted state :channel-closed))
  ([state reason]
   (if (= :disconnected (:phase state))
     (outcome state :channel-interrupted :ignored {:phase :disconnected})
     (-> state
         (assoc :phase :interrupted
                :reason reason)
         (outcome :channel-interrupted :applied {:reason reason})))))

(defn disconnect
  "Close the transport deliberately.  The last confirmed copy and timestamp
  remain readable, but no command may treat them as current."
  [state]
  (-> state
      (assoc :phase :disconnected
             :reason nil)
      (outcome :disconnect :applied {})))

(defn await-sync
  "Return to `:connected-awaiting-sync` while keeping the channel open.

  Used for a sequence gap, an invalid delta, or an epoch change.  A new
  baseline snapshot is required before deltas apply again."
  [state reason]
  (if (contains? #{:connected-awaiting-sync :synced} (:phase state))
    (-> state
        (assoc :phase :connected-awaiting-sync
               :reason reason)
        (outcome :await-sync :resync-required {:reason reason}))
    (outcome state :await-sync :ignored {:phase (:phase state)
                                         :reason reason})))

(defn connected-awaiting-sync?
  [state]
  (= :connected-awaiting-sync (:phase state)))

(defn interrupted?
  [state]
  (= :interrupted (:phase state)))
