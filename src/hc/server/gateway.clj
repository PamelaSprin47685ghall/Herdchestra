(ns hc.server.gateway
  "Internal AG-UI gateway: Sente channel, atomic state feed, command entry and
  independent blob transport.

  Authority: root `AGENTS.md` §7.3.  The gateway is an adapter.  It does not
  decide business state, command validity or blob references; it gives those
  responsibilities to explicit ports:

    :owner  an `hc.server.gateway.ports/OwnerPort`
    :store  an `hc.server.gateway.ports/BlobStorePort`

  The gateway owns the synchronization epoch, the monotonic sequence and the
  per-subscription baseline.  A new subscription receives one atomic
  `STATE_SNAPSHOT` plus `{epoch, seq}`; every later state event consumes the
  next sequence.  A stale or superseded subscription never receives state
  events and never blocks publishing.

  Start does not bind a TCP port.  Callers own the web server and pass Sente's
  server-channel adapter.  `hc.server.gateway.http/ring-handler` serves
  `/chsk`, `/blobs` and `/healthz`; other paths go to `:fallback`."
  (:require [clojure.string :as str]
            [hc.server.gateway.feed :as feed]
            [hc.server.gateway.http :as http]
            [hc.server.gateway.ports :as ports]
            [hc.server.gateway.sente :as sente]
            [hc.server.gateway.sync-object :as sync-object])
  (:import [java.nio.charset StandardCharsets]))

(def default-max-message-bytes (* 1024 1024))

(def default-forbidden-browser-kinds
  "Commands that belong to an agent, Reviewer, scheduler, or Owner control
  plane.  A browser transport must not originate them unless the application
  explicitly allowlists the kind."
  #{:review/submit :board/command :worker/run-program
    :plan/write-draft :plan/claim-draft :plan/release-draft
    :owner/compact
    :report/ack :report/register
    :session/issue-epoch :app/issue-epoch
    :fleet/fork :fleet/join :fleet/complete :fleet/abandon
    :fleet/fail :fleet/gc :fleet/state})

(defn- kind-in?
  "True when KIND names a member of KINDS, accepting either the keyword or
  its string spelling.  The string form is checked without interning a new
  keyword."
  [kinds kind]
  (or (contains? kinds kind)
      (and (string? kind)
           (boolean
            (some #(and (keyword? %)
                        ;; `name` drops the namespace; `str` keeps it.
                        (= (subs (str %) 1) kind))
                  kinds)))))

(defn- estimate-bytes
  "Return a conservative UTF-8 size estimate for VALUE.

  The Sente packer encodes Transit/JSON, which escapes strings and adds
  per-entry punctuation.  Counting raw string bytes and two bytes per map
  entry underestimates quote-heavy strings and maps with many small keys,
  so an event can pass the pre-check and be rejected later by the packer
  after the feed has already advanced.  `pr-str` includes quotes, escapes
  and EDN punctuation and is therefore the estimate the protocol specifies.
  A value that cannot be printed is treated as over every boundary; the
  large-object path then reports the encoding failure instead of silently
  dropping the event."
  [value]
  (try
    (let [printed (pr-str value)]
      (max (alength (.getBytes ^String printed StandardCharsets/UTF_8))
           (if (bytes? value) (alength ^bytes value) 0)))
    (catch Throwable _
      Long/MAX_VALUE)))

(defn event-bytes
  "Estimate the encoded UTF-8 size of VALUE for the Sente boundary.

  This is the same estimate the gateway uses before publishing.  Callers that
  choose between a delta and a full snapshot may compare the two estimates;
  the gateway still performs the authoritative check."
  [value]
  (estimate-bytes value))

(defn- message-limit
  [gw key]
  (long (or (get (:opts gw) key) default-max-message-bytes)))

(defn- object-limit
  [gw]
  (long (or (get (:opts gw) :max-state-object-bytes)
            sync-object/default-max-object-bytes)))

(defn- object-count-limit
  [gw]
  (long (or (get (:opts gw) :max-state-objects)
            sync-object/default-max-objects)))

(defn- publish-lock
  "All state publications and baseline captures serialize on this object.

  The feed still owns epoch, sequence and delivery.  The extra lock closes the
  gap between assigning a boundary to an external object and publishing the
  reference for it.  Manually assembled gateways fall back to the feed lock."
  [gw]
  (or (:publish-lock gw) (:feed gw)))

(defn- store-ref!
  [gw kind epoch seq value]
  (sync-object/store! (:store gw) (:objects gw)
                      {:kind kind
                       :epoch epoch
                       :seq seq
                       :value value
                       :max-bytes (object-limit gw)
                       :max-objects (object-count-limit gw)}))

(defn- ref-event-size
  [kind ref]
  (estimate-bytes
   (merge {:type (if (= kind :state-snapshot)
                   "STATE_SNAPSHOT"
                   "STATE_DELTA")
           :epoch (:epoch ref)
           :seq (:seq ref)
           :subscription-id 0
           :at "2026-11-05T00:00:00.000000Z"}
          (if (= kind :state-snapshot)
            {:snapshot-ref ref}
            {:delta-ref ref}))))

(def ^:private state-event-envelope-bytes
  "Reserve for `:type`, `:epoch`, `:seq`, `:at` and `:subscription-id` added
  by the feed plus Transit type overhead.  The estimate must be conservative:
  a frame that the packer rejects is not a delivered event."
  256)

(def ^:private sente-limit-keys
  [:max-message-bytes :max-sente-body :max-sente-message-bytes])

(defn- normalize-start-opts
  "Validate the initial feed and clamp the Sente boundaries.

  The 1 MiB Sente limit is a protocol boundary, not an operator knob: a
  configuration may lower it, never raise it.  A non-map snapshot or a
  non-positive epoch would make the first baseline invalid, so start!
  refuses instead of publishing a broken boundary."
  [opts]
  (let [snapshot (or (:snapshot opts) {})
        epoch (or (:epoch opts) 1)]
    (when-not (map? snapshot)
      (throw (ex-info "gateway initial snapshot must be a map"
                      {:code :GATEWAY_CONFIG_INVALID
                       :field :snapshot
                       :value snapshot})))
    (when-not (and (integer? epoch) (pos? epoch))
      (throw (ex-info "gateway initial epoch must be a positive integer"
                      {:code :GATEWAY_CONFIG_INVALID
                       :field :epoch
                       :value epoch})))
    (reduce (fn [opts key]
              (if-let [value (get opts key)]
                (if (and (integer? value) (pos? value))
                  (assoc opts key (min (long value)
                                       (long default-max-message-bytes)))
                  (throw (ex-info "gateway size boundary must be a positive integer"
                                  {:code :GATEWAY_CONFIG_INVALID
                                   :field key
                                   :value value})))
                opts))
            (assoc opts :snapshot snapshot :epoch epoch)
            sente-limit-keys)))

;; ---------------------------------------------------------------------------
;; Event field helpers

(defn- field
  [m k]
  (when (map? m)
    (if (contains? m k)
      (get m k)
      (get m (name k)))))

(defn- event-type
  [event]
  (let [value (or (field event :type) (field event :event))]
    (when (or (keyword? value) (string? value))
      (-> value
          name
          str/lower-case
          (str/replace "_" "-")
          keyword))))

(defn- error
  ([code message] (error code message nil))
  ([code message details]
   (cond-> {:ok? false
            :error {:code code :message message}}
     details (assoc-in [:error :details] details))))

(defn- stopped-error
  []
  (error :GATEWAY_STOPPED "the gateway is stopping"))

(defn- store-error
  [result]
  (error (get-in result [:error :code])
         (get-in result [:error :message])
         (dissoc (:error result) :code :message)))

(defn- too-large
  ([field-name size limit]
   (too-large field-name size limit nil))
  ([field-name size limit details]
   (error :SENTE_MESSAGE_TOO_LARGE
          "event exceeds the Sente message boundary"
          (merge {:field field-name :size size :limit limit} details))))

;; ---------------------------------------------------------------------------
;; Replies and delivery

(defn- reply!
  [?reply-fn value]
  (when ?reply-fn
    (try
      (?reply-fn value)
      (catch Throwable _ nil)))
  value)

(defn- send-to!
  "Send EVENT to one live subscription.  The subscription ID is attached so
  the client adapter can reject a late event from an older generation."
  [gw uid sid event]
  (if-let [subscription (feed/subscription (:feed gw) uid sid)]
    (if (:stale? subscription)
      (error :STALE_SUBSCRIPTION
             "subscription is stale; request a snapshot"
             {:subscription-id sid :action :resync-state})
      (try
        ((:send-fn subscription) (assoc event :subscription-id sid))
        {:ok? true}
        (catch Throwable t
          (error :SUBSCRIPTION_DELIVERY_FAILED
                 "subscription send failed"
                 {:subscription-id sid :cause (str t)}))))
    (error :STALE_SUBSCRIPTION
           "subscription is not current"
           {:subscription-id sid :action :resubscribe})))

(defn- receipt-event
  [result]
  {:type "CUSTOM"
   :name "hc/command-receipt"
   :value result})

(defn- public-command-result
  [request result]
  (let [result (if (map? result)
                 (select-keys result [:ok? :receipt :replayed? :error :effects
                                      :status])
                 {:ok? false
                  :error {:code :COMMAND_RESULT_INVALID
                          :message "Owner returned a non-map result"}})]
    (cond-> result
      (field request :command-id) (assoc :command-id (field request :command-id))
      (field request :command-epoch)
      (assoc :command-epoch (field request :command-epoch)))))

(defn- deliver-command-result!
  [gw uid sid request result]
  (send-to! gw uid sid (receipt-event (public-command-result request result))))

;; ---------------------------------------------------------------------------
;; Large state objects

(defn- publish-state-event!
  "Publish a state snapshot or delta, externalizing an oversized payload.

  The caller holds the gateway publish lock.  EPOCH and SEQ are derived from
  the feed boundary and therefore match the values the feed will assign.
  The object is stored before the feed advances, so a subscriber can never
  receive a reference to bytes that were not finalized."
  [gw event size limit]
  (let [type (event-type event)
        snapshot? (= type :state-snapshot)
        new-epoch? (boolean (or (field event :new-epoch?)
                                (field event :reset-epoch?)))
        boundary (feed/snapshot (:feed gw))
        epoch (if (and snapshot? new-epoch?)
                (inc (:epoch boundary))
                (:epoch boundary))
        seq (if (and snapshot? new-epoch?)
              0
              (inc (:seq boundary)))
        payload (if snapshot?
                  (field event :snapshot)
                  (or (field event :delta) (field event :patch)))
        external? (> (+ size state-event-envelope-bytes) limit)]
    (cond
      (and snapshot? (not (map? payload)))
      (error :STATE_SNAPSHOT_INVALID
             "snapshot must be a map"
             {:snapshot payload})

      (and (not snapshot?) (not (sequential? payload)))
      (error :STATE_DELTA_INVALID
             "delta must be a sequence of JSON Patch operations"
             {:event-type "STATE_DELTA"})

      (not external?)
      (if snapshot?
        (feed/publish-snapshot! (:feed gw) payload
                                {:new-epoch? new-epoch?})
        (feed/publish-delta! (:feed gw) payload))

      (nil? (:store gw))
      (too-large :event size limit
                 {:hint (str "attach a blob store to publish large state "
                             "objects; the Sente boundary is unchanged")})

      :else
      (let [stored (store-ref! gw type epoch seq payload)]
        (if-not (:ok? stored)
          (store-error stored)
          (let [ref (:ref stored)
                ref-size (ref-event-size type ref)]
            (if (> ref-size limit)
              (too-large :event ref-size limit
                         {:hint (str "the state-object reference itself "
                                     "exceeds the Sente boundary")})
              (if snapshot?
                (feed/publish-snapshot! (:feed gw) payload
                                        {:new-epoch? new-epoch? :ref ref})
                (feed/publish-delta! (:feed gw) payload {:ref ref})))))))))

;; ---------------------------------------------------------------------------
;; Sente event handling

(defn- current-subscription
  [gw uid]
  (feed/subscription-for-uid (:feed gw) uid))

(defn- subscription-check
  [gw uid request]
  (let [subscription (current-subscription gw uid)
        requested (field request :subscription-id)]
    (cond
      (nil? subscription)
      (error :STALE_SUBSCRIPTION
             "no subscription is current; subscribe before sending commands"
             {:action :resubscribe})

      (and (some? requested)
           (not (and (integer? requested) (not (neg? requested)))))
      (error :SUBSCRIPTION_ID_REQUIRED
             "subscription-id must be a non-negative integer when supplied"
             {:subscription-id requested
              :action :resubscribe})

      (:stale? subscription)
      (error :STALE_SUBSCRIPTION
             "subscription is stale; request a snapshot"
             {:subscription-id (:subscription-id subscription)
              :action :resync-state})

      (and (some? requested)
           (not= requested (:subscription-id subscription)))
      (error :STALE_SUBSCRIPTION
             "event belongs to an older subscription generation"
             {:subscription-id requested
              :current-subscription (:subscription-id subscription)
              :action :resync-state})

      :else
      {:ok? true :subscription subscription})))

(defn- baseline-ref-for
  "Return `{:ok? true :ref ref-or-nil}` for one captured boundary.

  An oversized baseline is stored as a state object and replaced by its
  reference; a missing store or an oversized reference envelope is an honest
  error.  The caller holds the publish lock so the captured boundary cannot
  move between the decision and the subscription."
  [gw boundary]
  (let [raw-size (estimate-bytes {:type "STATE_SNAPSHOT"
                                  :snapshot (:snapshot boundary)})
        size (+ raw-size state-event-envelope-bytes)
        limit (message-limit gw :max-message-bytes)]
    (if (<= size limit)
      {:ok? true :ref nil}
      (let [stored (store-ref! gw :state-snapshot
                               (:epoch boundary) (:seq boundary)
                               (:snapshot boundary))]
        (if-not (:ok? stored)
          (store-error stored)
          (let [ref (:ref stored)
                ref-size (ref-event-size :state-snapshot ref)]
            (if (> ref-size limit)
              (too-large :snapshot ref-size limit
                         {:hint (str "the state-object reference itself "
                                     "exceeds the Sente boundary")})
              {:ok? true :ref ref})))))))

(defn- handle-subscribe!
  [gw {:keys [uid ?reply-fn]} data]
  (let [sid (field data :subscription-id)]
    (locking (publish-lock gw)
      (let [boundary (feed/snapshot (:feed gw))
            baseline (baseline-ref-for gw boundary)]
        (if-not (:ok? baseline)
          (reply! ?reply-fn baseline)
          (let [ref (:ref baseline)
                send-fn (fn [event]
                          ((get-in gw [:socket :send-fn])
                           uid [:hc/event event]))
                result (feed/subscribe! (:feed gw) uid sid send-fn
                                        {:baseline-ref ref})]
            (reply! ?reply-fn
                    (if (:ok? result)
                      (select-keys result
                                   [:ok? :subscription-id :epoch :seq])
                      result))))))))

(defn- handle-command!
  [gw {:keys [uid client-id ?reply-fn]} data]
  (let [request (if (map? data) data {})
        payload (field request :payload)
        kind (field payload :kind)
        command-id (field request :command-id)
        command-epoch (field request :command-epoch)
        forbidden (or (get (:opts gw) :forbidden-command-kinds)
                      default-forbidden-browser-kinds)
        allowed (get (:opts gw) :allowed-command-kinds #{})
        checked (subscription-check gw uid request)]
    (cond
      (not (:ok? checked))
      (reply! ?reply-fn checked)

      (not (and (string? command-id) (not (str/blank? command-id))))
      (reply! ?reply-fn
              (error :COMMAND_ID_REQUIRED
                     "command-id must be a non-blank string"
                     {:command-id command-id}))

      (and (kind-in? forbidden kind)
           (not (kind-in? allowed kind)))
      (reply! ?reply-fn
              (error :COMMAND_KIND_FORBIDDEN
                     "this command kind is not available to browser clients"
                     {:kind kind :action :operator-message-required}))

      (nil? (:owner gw))
      (reply! ?reply-fn
              (error :OWNER_UNAVAILABLE
                     "no Owner port is attached to this gateway"))

      :else
      (let [subscription (:subscription checked)
            sid (:subscription-id subscription)
            replied? (atom false)
            reply-fn (fn [result]
                       (when (compare-and-set! replied? false true)
                         (deliver-command-result! gw uid sid request result)))]
        ;; Transport-level acknowledgement only.  The domain receipt arrives
        ;; later as a CUSTOM event; the client must not fake a final state.
        (reply! ?reply-fn
                {:ok? true
                 :status :submitted
                 :command-id command-id
                 :command-epoch command-epoch
                 :subscription-id sid})
        (try
          (let [returned (ports/submit-command!
                          (:owner gw)
                          {:command-id command-id
                           :command-epoch command-epoch
                           :payload payload
                           :client-id client-id
                           :uid uid
                           :subscription-id sid}
                          reply-fn)]
            (when (and (map? returned)
                       (contains? returned :ok?)
                       (not @replied?))
              (reply-fn returned)))
          (catch Throwable t
            (reply-fn {:ok? false
                       :error {:code :COMMAND_SUBMIT_FAILED
                               :message "Owner failed while accepting the command"
                               :cause (str t)}})))))))

(defn- handle-receipt!
  [gw {:keys [uid ?reply-fn]} data]
  (let [command-id (field data :command-id)
        checked (subscription-check gw uid data)]
    (cond
      (not (:ok? checked))
      (reply! ?reply-fn checked)

      (nil? (:owner gw))
      (reply! ?reply-fn
              (error :OWNER_UNAVAILABLE
                     "no Owner port is attached to this gateway"))

      (not (and (string? command-id) (not (str/blank? command-id))))
      (reply! ?reply-fn
              (error :COMMAND_ID_REQUIRED
                     "command-id must be a non-blank string"))

      :else
      (try
        (let [query (cond-> {:command-id command-id}
                      (some? (field data :session-id))
                      (assoc :session-id (field data :session-id))
                      (some? (field data :scope))
                      (assoc :scope (field data :scope)))
              receipt (ports/command-receipt
                       (:owner gw)
                       (if (or (some? (field data :session-id))
                               (some? (field data :scope)))
                         query
                         command-id))
              missing? (or (nil? receipt)
                           (= :not-found (:status receipt)))
              result (if missing?
                       {:ok? true
                        :command-id command-id
                        :status :not-found
                        :receipt nil
                        :action :resync-state}
                       {:ok? true
                        :command-id command-id
                        :receipt receipt})
              subscription (:subscription checked)]
          (if ?reply-fn
            (reply! ?reply-fn result)
            (send-to! gw uid (:subscription-id subscription)
                      (receipt-event result))))
        (catch Throwable t
          (reply! ?reply-fn
                  (error :COMMAND_RECEIPT_FAILED
                         "Owner failed while reading the receipt"
                         {:cause (str t)})))))))

(defn- handle-ack!
  [gw {:keys [uid ?reply-fn]} data]
  (let [checked (subscription-check gw uid data)]
    (if-not (:ok? checked)
      (reply! ?reply-fn checked)
      (let [subscription (:subscription checked)
            result (feed/ack! (:feed gw)
                              uid
                              (:subscription-id subscription)
                              (field data :seq))]
        (reply! ?reply-fn
                (if (:ok? result)
                  (select-keys result [:ok? :subscription-id :acked-seq
                                       :epoch :seq])
                  result))))))

(defn- handle-resync!
  [gw {:keys [uid ?reply-fn]} data]
  (let [subscription (current-subscription gw uid)
        requested (field data :subscription-id)]
    (cond
      (nil? subscription)
      (reply! ?reply-fn
              (error :STALE_SUBSCRIPTION
                     "no subscription is current; subscribe before resyncing"
                     {:action :resubscribe}))

      (and (some? requested)
           (not (and (integer? requested) (not (neg? requested)))))
      (reply! ?reply-fn
              (error :SUBSCRIPTION_ID_REQUIRED
                     "subscription-id must be a non-negative integer when supplied"
                     {:subscription-id requested
                      :action :resubscribe}))

      (and (some? requested)
           (not= requested (:subscription-id subscription)))
      (reply! ?reply-fn
              (error :STALE_SUBSCRIPTION
                     "resync belongs to an older subscription generation"
                     {:subscription-id requested
                      :current-subscription (:subscription-id subscription)
                      :action :resubscribe}))

      :else
      (locking (publish-lock gw)
        (let [boundary (feed/snapshot (:feed gw))
              baseline (baseline-ref-for gw boundary)]
          (if-not (:ok? baseline)
            (reply! ?reply-fn baseline)
            (let [ref (:ref baseline)
                  result (feed/resync! (:feed gw) uid
                                       (:subscription-id subscription)
                                       {:baseline-ref ref})]
              (reply! ?reply-fn
                      (if (:ok? result)
                        (select-keys result
                                     [:ok? :subscription-id :epoch :seq])
                        result)))))))))

(defn- handle-uidport-close!
  [gw uid data]
  (feed/unsubscribe-uid! (:feed gw) (or data uid))
  nil)

(defn handle-event!
  "Handle one Sente event message.  Exposed for tests and narrow adapters."
  [gw {:keys [event uid ?reply-fn] :as event-msg}]
  (if (true? @(:stopped? gw))
    (reply! ?reply-fn (stopped-error))
    (let [size (estimate-bytes event)
          limit (message-limit gw :max-sente-message-bytes)]
      (if (> size limit)
        (reply! ?reply-fn (too-large :event size limit))
        (let [[ev-id data] event]
          (case ev-id
            :chsk/uidport-open nil
            :chsk/uidport-close (handle-uidport-close! gw uid data)
            :hc/subscribe (handle-subscribe! gw event-msg data)
            :hc/command (handle-command! gw event-msg data)
            :hc/receipt (handle-receipt! gw event-msg data)
            :hc/ack (handle-ack! gw event-msg data)
            :hc/resync (handle-resync! gw event-msg data)
            (reply! ?reply-fn
                    (error :EVENT_UNSUPPORTED
                           "gateway does not support this event"
                           {:event ev-id}))))))))

;; ---------------------------------------------------------------------------
;; Lifecycle

(defn start!
  "Start the gateway.

  Options:
    :sch-adapter       required Sente server-channel adapter
    :owner             optional OwnerPort
    :store             optional BlobStorePort
    :fallback          optional Ring handler for non-gateway routes
    :epoch             initial epoch, default 1
    :snapshot          initial state tree, default {}
    :now-fn            clock used in event timestamps
    :max-pending       acknowledged-event window, default 64
    :max-upload-bytes  blob upload limit, default 100 MiB
    :max-sente-body    Sente POST limit, default 1 MiB
    :max-message-bytes outbound Sente event limit, default 1 MiB
    :max-sente-message-bytes inbound event limit, default 1 MiB
    :max-state-object-bytes serialized large-object ceiling, default 64 MiB
    :max-state-objects  live large-object references kept, default 128
    :allowed-origins   explicit origin set for `auth/authorize`; :all means
                       same-origin only.  The Sente transport keeps its own
                       duplicate check disabled so a non-browser client with
                       no Origin/Referer is judged only by that policy.
    :trusted-hosts     extra literal Host names accepted as local origins
    :allow-remote?     declare a non-loopback deployment; requires
                       :authorized?-fn or :authorize-request.  Without a
                       hook `start!` rejects it; Host/Origin are not
                       authentication.
    :authorized?-fn    real authentication hook; replaces the default
                       loopback-only policy
    :authorize-request ordinary Ring request authentication predicate
    :forbidden-command-kinds browser command kinds to deny (default includes
                       :review/submit, :board/command, :app/issue-epoch and
                       every :fleet/* kind)
    :allowed-command-kinds explicit exceptions to the deny set
    :content-type-fn   `(fn [id metadata] content-type)` for downloads

  The caller owns the HTTP server.  Returns a gateway map; use `handler` for
  the Ring handler and `stop!` to retire subscriptions."
  [opts]
  (let [opts (normalize-start-opts opts)
        feed (feed/create (select-keys opts [:epoch :snapshot :now-fn
                                             :max-pending]))
        gw-ref (atom nil)
        sente-handle (sente/start!
                      (fn [event-msg]
                        (when-let [gw @gw-ref]
                          (handle-event! gw event-msg)))
                      opts)
        gw {:feed feed
            :owner (:owner opts)
            :store (:store opts)
            :socket (:socket sente-handle)
            :sente sente-handle
            :publish-lock (Object.)
            :objects (sync-object/create-registry)
            :opts opts
            :stopped? (atom false)}]
    (reset! gw-ref gw)
    (assoc gw :handler (http/ring-handler gw))))

(defn stop!
  "Retire every subscription and stop the Sente receive loop.

  Returns the Sente stop evidence.  `:stopped?` is true only when the
  receive loop drained; a later call retries the same stop and can observe
  a later drain."
  [gw]
  (when (compare-and-set! (:stopped? gw) false true)
    (feed/close! (:feed gw))
    (sync-object/clear! (:objects gw)))
  (if-let [stop-fn (get-in gw [:sente :stop!])]
    (let [result (stop-fn)]
      (if (map? result)
        (let [stopped? (and (not (false? (:drained? result)))
                            (not (false? (:ok? result))))]
          (assoc result
                 :ok? stopped?
                 :stopped? stopped?))
        {:ok? true :stopped? true}))
    {:ok? true :stopped? true}))

(defn handler
  "Return the Ring handler for GW."
  [gw]
  (:handler gw))

;; ---------------------------------------------------------------------------
;; Publishing

(defn publish!
  "Publish one ordered state event.

  Accepted events:
    {:type :state-snapshot  :snapshot tree :new-epoch? false}
    {:type :state-delta     :delta [json-patch ...]}
    {:type :messages-snapshot :messages [...] :hc/roles ...}

  Returns `{:ok? true :epoch :seq :delivered ...}` or an error.  A new epoch
  snapshot resets the sequence to zero and sends every live subscription a new
  baseline.  A delta or messages snapshot never heals a stale subscription.

  A state snapshot or delta larger than `:max-message-bytes` is stored as an
  EDN state object and replaced by a small reference event.  The Sente limit
  is unchanged.  When no blob store is attached the call fails with
  `:SENTE_MESSAGE_TOO_LARGE`; it never drops the event silently or advances
  the feed without delivering it.

  The optional KNOWN-SIZE is the `event-bytes` value the caller already
  computed for EVENT.  It avoids printing the same large event twice in one
  publisher tick; the value must come from `event-bytes`."
  ([gw event]
   (publish! gw event nil))
  ([gw event known-size]
   (cond
     (true? @(:stopped? gw))
     (stopped-error)

     (not (map? event))
     (error :EVENT_INVALID "event must be a map" {:event event})

     :else
     (let [size (long (or known-size (estimate-bytes event)))
           limit (message-limit gw :max-message-bytes)
           type (event-type event)]
       (locking (publish-lock gw)
         (cond
           (contains? #{:state-snapshot :state-delta} type)
           (publish-state-event! gw event size limit)

           (> size limit)
           (too-large :event size limit)

           :else
           (case type
             :messages-snapshot
             (feed/publish-messages! (:feed gw)
                                     (field event :messages)
                                     event)

             (error :EVENT_TYPE_UNSUPPORTED
                    (str "publish! accepts state-snapshot, state-delta or "
                         "messages-snapshot")
                    {:type type}))))))))

(defn publish-snapshot!
  "Convenience wrapper for a full `STATE_SNAPSHOT`."
  ([gw snapshot]
   (publish-snapshot! gw snapshot {}))
  ([gw snapshot opts]
   (publish! gw (merge {:type :state-snapshot :snapshot snapshot} opts))))

(defn broadcast!
  "Send a non-state AG-UI event (RUN_STARTED, text stream, tool stream,
  CUSTOM) to every live subscription.  Does not consume the sequence."
  [gw event]
  (cond
    (true? @(:stopped? gw))
    (stopped-error)

    (not (map? event))
    (error :EVENT_INVALID "event must be a map" {:event event})

    :else
    (let [size (estimate-bytes event)
          limit (message-limit gw :max-message-bytes)]
      (if (> size limit)
        (too-large :event size limit)
        (feed/broadcast! (:feed gw) event)))))

;; ---------------------------------------------------------------------------
;; Inspection and manual subscription (tests, embedded clients)

(defn snapshot
  "Return the current atomic `{:epoch :seq :snapshot}` boundary."
  [gw]
  (feed/snapshot (:feed gw)))

(defn stats
  "Return feed diagnostics."
  [gw]
  (feed/stats (:feed gw)))

(defn- manual-subscribe!
  [gw uid sid send-fn]
  (if (true? @(:stopped? gw))
    (stopped-error)
    (locking (publish-lock gw)
      (let [boundary (feed/snapshot (:feed gw))
            baseline (baseline-ref-for gw boundary)]
        (if-not (:ok? baseline)
          baseline
          (feed/subscribe! (:feed gw) uid sid send-fn
                           {:baseline-ref (:ref baseline)}))))))

(defn subscribe!
  "Manually create a subscription.

  `(subscribe! gw uid sid send-fn)` or
  `(subscribe! gw {:uid u :subscription-id s :send-fn f})`.  An oversized
  baseline uses the same state-object reference path as the Sente handler,
  and the capture serializes on the publish lock."
  ([gw opts]
   (manual-subscribe! gw (:uid opts) (:subscription-id opts)
                      (:send-fn opts)))
  ([gw uid sid send-fn]
   (manual-subscribe! gw uid sid send-fn))
  ([gw uid send-fn]
   (manual-subscribe! gw uid nil send-fn)))

(defn unsubscribe!
  "Remove one subscription, or every subscription for UID."
  ([gw uid sid]
   (feed/unsubscribe! (:feed gw) uid sid))
  ([gw uid]
   (feed/unsubscribe-uid! (:feed gw) uid)))

(defn ack!
  "Record the last sequence UID applied."
  [gw uid sid applied-seq]
  (feed/ack! (:feed gw) uid sid applied-seq))

(defn resync!
  "Send a fresh baseline to a live subscription.

  The baseline is captured under the publish lock and externalized when it
  exceeds the Sente boundary, exactly like the Sente `:hc/resync` handler."
  [gw uid sid]
  (if (true? @(:stopped? gw))
    (stopped-error)
    (locking (publish-lock gw)
      (let [boundary (feed/snapshot (:feed gw))
            baseline (baseline-ref-for gw boundary)]
        (if-not (:ok? baseline)
          baseline
          (feed/resync! (:feed gw) uid sid
                        {:baseline-ref (:ref baseline)}))))))
