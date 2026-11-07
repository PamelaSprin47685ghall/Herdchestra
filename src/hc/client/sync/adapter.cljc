(ns hc.client.sync.adapter
  "Pure reducer for the internal AG-UI state synchronization events.

  The adapter owns only the server display copy and its synchronization
  metadata.  It never touches local drafts, view state, command receipts, or
  the network.  Callers feed events in and merge the resulting `:db` into their
  Fulcro database.

  Accepted events:

    STATE_SNAPSHOT     replaces the protocol tree atomically
    STATE_DELTA        applies one RFC 6902 patch at the next sequence
    MESSAGES_SNAPSHOT  replaces messages with role-preservation rules

  Every state-mutating event carries an epoch and a sequence.  A delta from an
  older epoch or sequence is dropped.  A gap, an epoch advance, or an invalid
  patch returns to `:connected-awaiting-sync`; the last confirmed copy remains
  readable but commands are blocked until a new baseline arrives."
  (:require [clojure.string :as str]
            [hc.client.sync.connection :as connection]
            [hc.client.sync.json-patch :as json-patch]
            [hc.client.sync.messages :as messages]
            [hc.client.sync.normalize :as normalize]
            [hc.client.sync.object :as object]))

;; ---------------------------------------------------------------------------
;; Event field access

(defn- field
  [event k]
  (if (contains? event k)
    (get event k)
    (get event (normalize/keyword-string k))))

(defn- nested-sync
  [event]
  (let [sync (field event :sync)]
    (when (map? sync) sync)))

(defn event-type
  "Normalize an AG-UI event type to a keyword such as `:state-snapshot`."
  [event]
  (let [value (or (field event :type) (field event :event))]
    (when (or (keyword? value) (string? value))
      (-> value
          name
          str/lower-case
          (str/replace "_" "-")
          keyword))))

(defn- event-epoch
  [event]
  (let [value (or (field event :epoch)
                  (field event :sync-epoch)
                  (some-> (nested-sync event) (field :epoch)))]
    (when (and (integer? value) (not (neg? value))) value)))

(defn- event-seq
  [event]
  (let [value (or (field event :seq)
                  (field event :sequence)
                  (field event :sync-seq)
                  (some-> (nested-sync event) (field :seq))
                  (some-> (nested-sync event) (field :sequence)))]
    (when (and (integer? value) (not (neg? value))) value)))

(defn- event-at
  [event]
  (or (field event :at)
      (field event :synced-at)
      (field event :timestamp)))

(defn- event-subscription
  [event]
  (let [value (or (field event :subscription-id)
                  (field event :subscription)
                  (some-> (nested-sync event) (field :subscription-id)))]
    (when (integer? value) value)))

(defn- event-snapshot
  [event]
  (field event :snapshot))

(defn- event-delta
  [event]
  (or (field event :delta)
      (field event :patch)))

(defn- event-snapshot-ref
  [event]
  (field event :snapshot-ref))

(defn- event-delta-ref
  [event]
  (field event :delta-ref))

(def ^:private max-deferred-events
  "Upper bound on state events held while one large object is fetched.  The
  server's acknowledgement window is larger; exceeding this bound is treated
  as a backlog and forces a fresh baseline."
  32)

(defn- clear-object-state
  [state]
  (assoc state :pending-object nil :deferred-events []))

;; ---------------------------------------------------------------------------
;; Outcomes and resynchronization

(defn- outcome
  [state kind result details]
  (assoc state :last-outcome
         (merge {:kind kind :result result} details)))

(defn- resync
  [state kind reason details]
  (let [next-state (connection/await-sync (clear-object-state state) reason)
        result (get-in next-state [:last-outcome :result])]
    (outcome next-state kind result (merge {:reason reason} details))))

;; ---------------------------------------------------------------------------
;; State handlers

(defn receive-state-snapshot
  "Accept an atomic baseline snapshot and replace the protocol tree.

  A snapshot at the same epoch may only move the sequence forward.  A snapshot
  at an older epoch is stale.  A new epoch may reset the sequence; the whole
  tree is replaced, so removed entities cannot revive.

  A `:snapshot-ref` is accepted at the same boundary and leaves the confirmed
  copy untouched until `complete-object` supplies the verified payload.  A
  reference never advances the sequence by itself."
  [state event]
  (let [epoch (event-epoch event)
        seq (event-seq event)
        snapshot (event-snapshot event)
        ref (event-snapshot-ref event)]
    (cond
      (not (contains? #{:connected-awaiting-sync :synced} (:phase state)))
      (outcome state :state-snapshot :dropped
               {:reason :not-syncing :phase (:phase state)})

      (nil? epoch)
      (outcome state :state-snapshot :dropped
               {:reason :invalid-epoch
                :message "snapshot needs an integer epoch"})

      (nil? seq)
      (outcome state :state-snapshot :dropped
               {:reason :invalid-sequence
                :message "snapshot needs a non-negative integer sequence"})

      (and (integer? (:epoch state)) (< epoch (:epoch state)))
      (outcome state :state-snapshot :dropped
               {:reason :stale-epoch
                :epoch epoch
                :current-epoch (:epoch state)})

      (and (= epoch (:epoch state))
           (integer? (:seq state))
           (< seq (:seq state)))
      (outcome state :state-snapshot :dropped
               {:reason :stale-sequence
                :seq seq
                :current-seq (:seq state)})

      (and (some? ref) (some? snapshot))
      (resync state :state-snapshot :ambiguous-snapshot
              {:message "snapshot and snapshot-ref are mutually exclusive"})

      (some? ref)
      (let [checked (object/valid-ref :state-snapshot ref epoch seq)]
        (if-not (:ok? checked)
          (resync state :state-snapshot :invalid-object-ref (:error checked))
          (-> state
              (assoc :pending-object {:kind :state-snapshot
                                      :ref (:ref checked)
                                      :event event})
              (outcome :state-snapshot :awaiting-object
                       {:epoch epoch
                        :seq seq
                        :token (get-in checked [:ref :token])}))))

      (not (map? snapshot))
      ;; An unreadable baseline must not leave the client claiming `:synced`
      ;; on an older epoch.  Ask for a fresh boundary instead.
      (resync state :state-snapshot :invalid-snapshot
              {:message "snapshot must be a map"})

      :else
      (let [db-result (normalize/to-db snapshot)]
        (if (:ok? db-result)
          (-> state
              (assoc :phase :synced
                     :epoch epoch
                     :seq seq
                     :synced-at (event-at event)
                     :reason nil
                     :server snapshot
                     :db (:value db-result))
              (outcome :state-snapshot :applied
                       {:epoch epoch :seq seq}))
          (resync state :state-snapshot :invalid-snapshot
                  {:error (:error db-result)}))))))

(defn receive-state-delta
  "Apply one JSON Patch at the next sequence, or require a new baseline.

  A `:delta-ref` is accepted only at the exact next sequence.  It leaves the
  confirmed copy untouched until `complete-object` supplies the verified
  operations."
  [state event]
  (let [epoch (event-epoch event)
        seq (event-seq event)
        delta (event-delta event)
        ref (event-delta-ref event)]
    (cond
      (not= :synced (:phase state))
      (outcome state :state-delta :dropped
               {:reason :not-synced :phase (:phase state)})

      (nil? epoch)
      (resync state :state-delta :delta-invalid
              {:message "delta needs an integer epoch"})

      (nil? seq)
      (resync state :state-delta :delta-invalid
              {:message "delta needs a non-negative integer sequence"})

      (< epoch (:epoch state))
      (outcome state :state-delta :dropped
               {:reason :stale-epoch
                :epoch epoch
                :current-epoch (:epoch state)})

      (> epoch (:epoch state))
      (resync state :state-delta :epoch-changed
              {:epoch epoch :current-epoch (:epoch state)})

      (<= seq (:seq state))
      (outcome state :state-delta :dropped
               {:reason :stale-sequence
                :seq seq
                :current-seq (:seq state)})

      (> seq (inc (:seq state)))
      (resync state :state-delta :delta-gap
              {:expected-seq (inc (:seq state))
               :received-seq seq})

      (and (some? ref) (some? delta))
      (resync state :state-delta :delta-invalid
              {:message "delta and delta-ref are mutually exclusive"})

      (some? ref)
      (let [checked (object/valid-ref :state-delta ref epoch seq)]
        (if-not (:ok? checked)
          (resync state :state-delta :delta-invalid (:error checked))
          (-> state
              (assoc :pending-object {:kind :state-delta
                                      :ref (:ref checked)
                                      :event event})
              (outcome :state-delta :awaiting-object
                       {:epoch epoch
                        :seq seq
                        :token (get-in checked [:ref :token])}))))

      (not (sequential? delta))
      (resync state :state-delta :delta-invalid
              {:message "delta must be a sequence of JSON Patch operations"})

      :else
      (let [result (json-patch/apply-patch (:server state) delta)]
        (if-not (:ok? result)
          (resync state :state-delta :delta-invalid
                  {:error (:error result)})
          (let [server' (:value result)]
            (if-not (map? server')
              (resync state :state-delta :delta-invalid
                      {:message "patched state must remain a map"})
              (let [db-result (normalize/to-db server')]
                (if-not (:ok? db-result)
                  (resync state :state-delta :delta-invalid
                          {:error (:error db-result)})
                  (-> state
                      (assoc :server server'
                             :db (:value db-result)
                             :seq seq
                             :synced-at (or (event-at event)
                                            (:synced-at state))
                             :reason nil)
                      (outcome :state-delta :applied
                               {:epoch epoch :seq seq})))))))))))

(defn receive-messages-snapshot
  "Replace messages while preserving undeclared activity/reasoning records.

  The event consumes the next sequence exactly like a state delta.  The
  `MESSAGES_SNAPSHOT` role rules live in `hc.client.sync.messages`."
  [state event]
  (let [epoch (event-epoch event)
        seq (event-seq event)]
    (cond
      (not= :synced (:phase state))
      (outcome state :messages-snapshot :dropped
               {:reason :not-synced :phase (:phase state)})

      (nil? epoch)
      (resync state :messages-snapshot :messages-invalid
              {:message "messages snapshot needs an integer epoch"})

      (nil? seq)
      (resync state :messages-snapshot :messages-invalid
              {:message "messages snapshot needs a non-negative integer sequence"})

      (< epoch (:epoch state))
      (outcome state :messages-snapshot :dropped
               {:reason :stale-epoch
                :epoch epoch
                :current-epoch (:epoch state)})

      (> epoch (:epoch state))
      (resync state :messages-snapshot :epoch-changed
              {:epoch epoch :current-epoch (:epoch state)})

      (<= seq (:seq state))
      (outcome state :messages-snapshot :dropped
               {:reason :stale-sequence
                :seq seq
                :current-seq (:seq state)})

      (> seq (inc (:seq state)))
      (resync state :messages-snapshot :delta-gap
              {:expected-seq (inc (:seq state))
               :received-seq seq})

      :else
      (let [result (messages/apply-messages-snapshot (:server state) event)]
        (if-not (:ok? result)
          (resync state :messages-snapshot :messages-invalid
                  {:error (:error result)})
          (let [server' (:server (:value result))
                db-result (normalize/to-db server')]
            (if-not (:ok? db-result)
              (resync state :messages-snapshot :messages-invalid
                      {:error (:error db-result)})
              (-> state
                  (assoc :server server'
                         :db (:value db-result)
                         :seq seq
                         :synced-at (or (event-at event)
                                        (:synced-at state))
                         :reason nil)
                  (outcome :messages-snapshot :applied
                           {:epoch epoch :seq seq})))))))))

;; ---------------------------------------------------------------------------
;; Public reducer and connection delegation

(defn initial-state
  "Return a disconnected adapter state with no pending large object."
  []
  (-> (connection/initial-state)
      (assoc :pending-object nil
             :deferred-events [])))

(defn connect
  "Start a new subscription generation.  Any in-flight large object belongs
  to the previous generation and is discarded."
  [state]
  (-> state clear-object-state connection/connect))

(defn channel-opened
  "Record that the transport opened and a baseline is expected."
  [state]
  (connection/channel-opened state))

(defn channel-interrupted
  "Record transport loss while keeping the last confirmed copy."
  ([state]
   (-> state clear-object-state connection/channel-interrupted))
  ([state reason]
   (-> state clear-object-state
       (connection/channel-interrupted reason))))

(defn disconnect
  "Close the transport deliberately.  A pending large object is discarded."
  [state]
  (-> state clear-object-state connection/disconnect))

(defn request-resync
  "Force `:connected-awaiting-sync` when the transport dropped events or its
  backlog exceeded the client's capacity."
  [state reason]
  (resync state :resync reason {}))

(defn- receive-while-pending
  "Handle one state event while a large object is being fetched.

  A duplicate reference is ignored.  Every other state event, including a
  complete `STATE_SNAPSHOT`, is held in a bounded ordered queue so it can be
  applied after the object arrives.  Queuing the snapshot preserves arrival
  order: it may itself be followed by later events.  Overflow forces a fresh
  baseline instead of silently dropping a gap."
  [state kind event]
  (let [pending (:pending-object state)
        ref (case kind
              :state-snapshot (event-snapshot-ref event)
              :state-delta (event-delta-ref event)
              nil)]
    (cond
      (and (some? ref)
           (= (field ref :token) (get-in pending [:ref :token])))
      (outcome state kind :dropped
               {:reason :duplicate-pending-object
                :token (field ref :token)})

      :else
      (let [events (conj (vec (:deferred-events state)) event)]
        (if (> (count events) max-deferred-events)
          (resync state kind :object-backlog
                  {:limit max-deferred-events
                   :pending-token (get-in pending [:ref :token])})
          (assoc state :deferred-events events))))))

(defn receive
  "Feed one event to the adapter.

  The two-arity form uses the event's subscription ID when present, otherwise
  the current subscription.  The three-arity form is for transports that bind
  a handler to a subscription generation explicitly.

  While `:pending-object` is set, later state events are queued rather than
  applied against a stale tree.  The queue is drained by `complete-object`."
  ([state event]
   (if-not (map? event)
     (outcome state :invalid-event :dropped
              {:reason :invalid-event :event event})
     (receive state
              (or (event-subscription event) (:subscription state))
              event)))
  ([state subscription event]
   (if-not (map? event)
     (outcome state :invalid-event :dropped
              {:reason :invalid-event :event event})
     (let [kind (event-type event)]
       (if (not= subscription (:subscription state))
         (outcome state kind :dropped
                  {:reason :stale-subscription
                   :subscription subscription
                   :current-subscription (:subscription state)})
         (if (some? (:pending-object state))
           (receive-while-pending state kind event)
           (case kind
             :state-snapshot (receive-state-snapshot state event)
             :state-delta (receive-state-delta state event)
             :messages-snapshot (receive-messages-snapshot state event)
             (outcome state kind :ignored
                      {:reason :unsupported-event}))))))))

(defn- drain-deferred
  "Apply queued events in order until the queue is empty or a new object is
  pending.  Remaining events stay queued for the next completion."
  [state]
  (loop [state state]
    (let [events (:deferred-events state)]
      (if (or (empty? events) (some? (:pending-object state)))
        state
        (let [event (first events)
              state (assoc state
                           :deferred-events (subvec (vec events) 1))
              state (receive state event)]
          (recur state))))))

(defn complete-object
  "Apply the verified payload for the pending large state object.

  RESULT is `{:token :digest :size :value :verified?}`.  The caller must have
  fetched the exact bytes and verified both the domain-separated SHA-256
  digest and the byte count; `:verified?` must be true.  The payload is
  applied at the boundary carried by the original reference and then the
  deferred queue is drained in order.

  A token mismatch means the result belongs to a superseded fetch and is
  dropped without touching the current pending object.  A digest, size or
  shape mismatch with the matching token is corruption and forces a fresh
  baseline."
  [state result]
  (let [pending (:pending-object state)
        ref (:ref pending)
        kind (:kind pending)]
    (cond
      (nil? pending)
      (outcome state :state-object :dropped
               {:reason :no-pending-object})

      (not (map? result))
      (outcome state :state-object :dropped
               {:reason :invalid-object-result})

      (not= (:token result) (:token ref))
      (outcome state :state-object :dropped
               {:reason :token-mismatch
                :expected (:token ref)
                :actual (:token result)})

      (and (contains? result :kind)
           (not= (:kind result) kind))
      (resync state :state-object :object-kind-mismatch
              {:expected kind :actual (:kind result)})

      (not= (:digest result) (:digest ref))
      (resync state :state-object :object-digest-mismatch
              {:expected (:digest ref) :actual (:digest result)})

      (not= (:size result) (:size ref))
      (resync state :state-object :object-size-mismatch
              {:expected (:size ref) :actual (:size result)})

      (not (true? (:verified? result)))
      (resync state :state-object :object-unverified {})

      (not (object/payload-shape? kind (:value result)))
      (resync state :state-object :object-payload-invalid
              {:kind kind})

      :else
      (let [event (-> (:event pending)
                      (dissoc :snapshot-ref :delta-ref)
                      (assoc (if (= kind :state-snapshot) :snapshot :delta)
                             (:value result)))
            state' (-> state
                       (assoc :pending-object nil
                              :last-object {:kind kind
                                            :token (:token ref)
                                            :digest (:digest ref)
                                            :size (:size ref)
                                            :at (event-at (:event pending))}))
            state'' (if (= kind :state-snapshot)
                      (receive-state-snapshot state' event)
                      (receive-state-delta state' event))]
        (drain-deferred state'')))))

(defn object-failed
  "Abandon the pending object and require a fresh baseline."
  [state reason]
  (resync state :state-object reason
          {:pending-token (get-in state [:pending-object :ref :token])}))

(defn phase
  "Return the current connection phase."
  [state]
  (connection/phase state))

(defn synced?
  "True only after a confirmed baseline, with no outstanding gap and no
  pending large object."
  [state]
  (and (connection/synced? state)
       (nil? (:pending-object state))
       (empty? (:deferred-events state))))

(defn command-allowed?
  "True only when commands may rely on the latest server state."
  [state]
  (synced? state))

(defn command-epoch
  "Return the server-issued command epoch from the confirmed snapshot's
  `:sync` map, or nil.

  This is deliberately separate from the AG-UI feed epoch.  The feed epoch
  fences subscriptions and deltas; the command epoch fences Owner commands.
  They are independent numbers and must never be substituted for each other."
  [state]
  (let [server (:server state)
        sync (or (get server :sync) (get server "sync"))
        value (when (map? sync)
                (or (get sync :command-epoch)
                    (get sync "command-epoch")))]
    (when (and (integer? value) (pos? value))
      value)))

;; ---------------------------------------------------------------------------
;; Fulcro database integration

(def default-server-keys
  "Top-level keys owned by the server display copy.  Callers may add scalar
  keys through `merge-into-db`'s third argument."
  (into
   #{:session/by-id
     :session/current-id
     :session/controller-id
     :session/sync-epoch
     :session/observer-ids
     :session/active-id
     :session/history
     :session/history-active
     :swarm/by-id
     :task/by-id
     :action/by-id
     :agent/by-id
     :token-slot/by-id
     :message/by-id
     :message/order
     :run/by-id
     :call/by-id
     :attempt/by-id
     :artifact/by-id
     :project/by-id
     :instance/by-id
     :connection/by-id
     :schedule/by-id
     :goal/by-id
     :profile/by-id
     :credential/by-id
     :channel/by-id
     :backup/by-id
     :recovery-point/by-id
     :write-gate/by-id
     :slot/by-id
     :board/version
     :settings/by-id
     :settings/models
     :settings/roles
     :materials/ready?
     :materials/error
     :materials/revision
     :materials/settings
     :materials/session
     :memory/by-id
     :memory/order
     :memory/counts
     :worldbook/by-id
     :worldbook-entry/by-id
     :worldbook-session
     :instruction-card/by-id
     :instruction-session
     :instruction-turn
     :diagnostics
     :extensions
     :speech
     :search
     :documents
     :artifacts
     :scheduler
     :sync
     :run-error
     :server/now
     :instance/id
     :project/id
     :run/id
     :call/id
     :attempt/id}
   (keys normalize/raw-collections)))

(defn- display-text
  "The newest display text one message entity carries.

  A live publisher patches `:text` on every delta while `:content` stays at
  the version snapshot.  When one value is a prefix of the other, the longer
  value is the append-only stream and the shorter one is stale.  A non-prefix
  pair is an edit: `:content` names the selected version and wins."
  [message]
  (let [content (or (:content message) (:message/content message))
        text (or (:text message) (:message/text message))]
    (cond
      (str/blank? (str content)) text
      (str/blank? (str text)) content
      (and (string? content)
           (string? text)
           (str/starts-with? text content)) text
      (and (string? content)
           (string? text)
           (str/starts-with? content text)) content
      :else content)))

(defn- streaming-message?
  [message]
  (or (true? (:streaming? message))
      (= :streaming (keyword (:message/status message)))
      (= :streaming (keyword (:message/streaming? message)))))

(defn- reconcile-message
  "Keep append-only stream text from regressing at a state boundary.

  The server can patch `:text` to the full answer while the same entity's
  `:content` still names the first version.  The display projection prefers
  `:content`, so a stale boundary would hide every later delta.  During a live
  stream the locally appended text is authoritative when it extends the
  server text.  Once the stream ends, the server's newest text is
  authoritative, but its own stale `:content` is still brought forward to the
  longer `:text`."
  [server local]
  (let [server-text (display-text server)
        local-text (display-text local)
        server-text (when (string? server-text) server-text)
        local-text (when (string? local-text) local-text)
        live? (or (streaming-message? server) (streaming-message? local))
        server-content (when (string? (:content server)) (:content server))]
    (cond
      (and live?
           server-text
           local-text
           (> (count local-text) (count server-text))
           (str/starts-with? local-text server-text))
      (assoc server
             :content local-text
             :message/content local-text
             :message/content-version
             (or (:message/content-version local)
                 (:message/content-version server)))

      (and server-text
           server-content
           (> (count server-text) (count server-content))
           (str/starts-with? server-text server-content))
      (assoc server :content server-text)

      :else server)))

(defn merge-into-db
  "Replace the server-owned region of a Fulcro database with the adapter's
  current normalized view.

  Every key named by `default-server-keys`, `extra-server-keys`, or the current
  server/database view is removed from `db` before the new view is merged.  A
  removed entity therefore cannot survive as a stale table entry.  Local keys
  such as `:ui/draft` are untouched.

  Message entities are reconciled, not copied blindly: an append-only live
  text that is ahead of the server boundary survives the merge, and a stale
  `:content` is brought forward to the longer `:text`."
  ([db state]
   (merge-into-db db state #{}))
  ([db state extra-server-keys]
   (let [owned (into default-server-keys
                     (concat extra-server-keys
                             (keys (:server state))
                             (keys (:db state))))]
     (let [merged (merge (apply dissoc db owned) (:db state))
           local-messages (get db :message/by-id)
           server-messages (get merged :message/by-id)]
       (if (and (map? local-messages) (map? server-messages))
         (assoc merged :message/by-id
                (merge-with reconcile-message
                            server-messages local-messages))
         merged)))))
