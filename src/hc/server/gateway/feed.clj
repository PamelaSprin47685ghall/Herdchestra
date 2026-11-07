(ns hc.server.gateway.feed
  "Atomic AG-UI state feed: one epoch, one monotonic sequence, many
  subscriptions.

  A subscription is created and its baseline snapshot captured while holding
  the feed lock, so no delta can slip between the baseline and the first
  increment.  Publishing also holds the lock, assigns the next sequence, and
  hands every subscriber the event in the same order.

  A subscription is stale when its transport was superseded, its sender threw,
  or it acknowledged too slowly.  Stale subscriptions receive no further state
  events.  A full `STATE_SNAPSHOT` heals a stale subscription; a delta never
  does, because the missing history cannot be reconstructed.

  `send-fn` is called while the feed lock is held.  It must be non-blocking and
  must not call back into the feed.  Sente's `send-fn` meets that contract."
  (:require [hc.server.gateway.patch :as patch]))

;; ---------------------------------------------------------------------------
;; Events

(defn- now
  [feed]
  ((:now-fn feed)))

(defn- event-at
  [feed]
  {:at (now feed)})

(defn- state-event
  [feed type fields]
  (merge {:type type} (event-at feed) fields))

(defn- resync-event
  [feed state reason]
  (state-event feed "CUSTOM"
               {:name "hc/resync-required"
                :value {:reason reason
                        :epoch (:epoch state)
                        :seq (:seq state)}
                :epoch (:epoch state)
                :seq (:seq state)}))

(defn- ref-matches?
  "True when REF names the state object for KIND at EPOCH/SEQ.

  A reference is only usable for the exact boundary it was created for.
  Anything else falls back to the inline payload (for a baseline) or is
  rejected (for a publish)."
  [ref kind epoch seq]
  (and (map? ref)
       (= kind (:kind ref))
       (= epoch (:epoch ref))
       (= seq (:seq ref))
       (string? (:token ref))
       (string? (:digest ref))
       (integer? (:size ref))
       (string? (:encoding ref))))

(defn- delivery-failed
  [subscription error]
  (assoc subscription :stale? true :last-error error))

(defn- send-one!
  "Send EVENT to SUBSCRIPTION.  Returns [subscription delivered?]."
  [subscription event]
  (try
    ((:send-fn subscription) event)
    [subscription true]
    (catch Throwable t
      [(delivery-failed subscription t) false])))

;; ---------------------------------------------------------------------------
;; State access

(defn- state
  [feed]
  @(:state_ feed))

(defn- current-subscription
  [state uid]
  (when-let [sid (get-in state [:uid->sid uid])]
    (get-in state [:subscriptions [uid sid]])))

(defn subscription-for-uid
  "Return the current subscription map for UID, or nil."
  [feed uid]
  (locking (:lock feed)
    (current-subscription (state feed) uid)))

(defn subscription
  "Return the subscription map for UID and SID, or nil."
  [feed uid sid]
  (locking (:lock feed)
    (get-in (state feed) [:subscriptions [uid sid]])))

;; ---------------------------------------------------------------------------
;; Creation

(defn create
  "Create an empty feed.

  Options:
    :epoch        initial epoch, default 1
    :snapshot     initial state tree, default {}
    :now-fn       zero-arg clock; its value is written verbatim to `:at`
    :max-pending  acknowledged-event window before a subscription is stale"
  [{:keys [epoch snapshot now-fn max-pending]
    :or {epoch 1 snapshot {} max-pending 64}}]
  {:lock (Object.)
   :now-fn (or now-fn #(str (java.time.Instant/now)))
   :state_ (atom {:epoch (or epoch 1)
                  :seq 0
                  :snapshot (or snapshot {})
                  :subscriptions {}
                  :uid->sid {}
                  :max-pending (or max-pending 64)})})

(defn snapshot
  "Return the atomic `{:epoch :seq :snapshot}` boundary."
  [feed]
  (locking (:lock feed)
    (let [st (state feed)]
      (select-keys st [:epoch :seq :snapshot]))))

(defn stats
  "Return diagnostic counts for the feed."
  [feed]
  (locking (:lock feed)
    (let [st (state feed)
          subscriptions (vals (:subscriptions st))]
      {:epoch (:epoch st)
       :seq (:seq st)
       :max-pending (:max-pending st)
       :subscriptions (count subscriptions)
       :stale (count (filter :stale? subscriptions))})))

;; ---------------------------------------------------------------------------
;; Subscribe and unsubscribe

(defn subscribe!
  "Create or replace the subscription for UID.

  SID is the client's local subscription generation.  Re-subscribing with the
  same SID resets its baseline; re-subscribing with a new SID retires the old
  entry.  Returns `{:ok? true :subscription-id :epoch :seq :event}` or an
  error when the baseline cannot be delivered.

  `:baseline-ref`, when supplied and matching the captured boundary, is sent
  instead of the inline snapshot.  A mismatched reference is ignored and the
  inline snapshot is used: the baseline must always describe the tree the
  feed actually holds."
  ([feed uid send-fn]
   (subscribe! feed uid nil send-fn {}))
  ([feed uid sid send-fn]
   (subscribe! feed uid sid send-fn {}))
  ([feed uid sid send-fn {:keys [baseline-ref]}]
   (locking (:lock feed)
     (let [st (state feed)
           sid (or sid 1)
           old-sid (get-in st [:uid->sid uid])]
       (cond
         (not (and (integer? sid) (not (neg? sid))))
         {:ok? false
          :error {:code :SUBSCRIPTION_INVALID
                  :message "subscription-id must be a non-negative integer"
                  :subscription-id sid}}

         (not (ifn? send-fn))
         {:ok? false
          :error {:code :SUBSCRIPTION_INVALID
                  :message "send-fn must be callable"}}

         (and old-sid (< sid old-sid))
         {:ok? false
          :error {:code :STALE_SUBSCRIPTION
                  :message "subscription-id is older than the current generation"
                  :subscription-id sid
                  :current-subscription old-sid
                  :action :resubscribe}}

         :else
         (let [base {:uid uid
                     :subscription-id sid
                     :send-fn send-fn
                     :seq (:seq st)
                     :acked-seq (:seq st)
                     :ack-mode? false
                     :stale? false}
               subscriptions (-> (:subscriptions st)
                                 (cond-> (and old-sid (not= old-sid sid))
                                   (dissoc [uid old-sid]))
                                 (assoc [uid sid] base))
               st' (assoc st
                          :subscriptions subscriptions
                          :uid->sid (assoc (:uid->sid st) uid sid))
               use-ref? (ref-matches? baseline-ref :state-snapshot
                                      (:epoch st') (:seq st'))
               event (state-event feed "STATE_SNAPSHOT"
                                  (cond-> {:epoch (:epoch st')
                                           :seq (:seq st')
                                           :subscription-id sid}
                                    use-ref? (assoc :snapshot-ref baseline-ref)
                                    (not use-ref?)
                                    (assoc :snapshot (:snapshot st'))))
               [subscription delivered?] (send-one! base event)]
           (reset! (:state_ feed)
                   (assoc-in st' [:subscriptions [uid sid]] subscription))
           (if delivered?
             {:ok? true
              :subscription-id sid
              :epoch (:epoch st')
              :seq (:seq st')
              :event event}
             {:ok? false
              :subscription-id sid
              :error {:code :SUBSCRIPTION_DELIVERY_FAILED
                      :message "baseline snapshot could not be delivered"
                      :cause (str (:last-error subscription))}})))))))

(defn unsubscribe!
  "Remove one subscription."
  [feed uid sid]
  (locking (:lock feed)
    (let [st (state feed)
          existed? (contains? (:subscriptions st) [uid sid])
          uid-sid (get-in st [:uid->sid uid])]
      (reset! (:state_ feed)
              (cond-> (update st :subscriptions dissoc [uid sid])
                (= uid-sid sid) (update :uid->sid dissoc uid)))
      {:ok? true :removed? existed?})))

(defn unsubscribe-uid!
  "Remove every subscription owned by UID."
  [feed uid]
  (locking (:lock feed)
    (let [st (state feed)
          sid (get-in st [:uid->sid uid])]
      (if (nil? sid)
        {:ok? true :removed? false}
        (unsubscribe! feed uid sid)))))

;; ---------------------------------------------------------------------------
;; Delivery

(defn- ack-window-exceeded?
  [state subscription next-seq]
  (and (:ack-mode? subscription)
       (> (- next-seq (:acked-seq subscription))
          (:max-pending state))))

(defn- deliver-sequential
  "Deliver one event that consumes NEXT-SEQ.

  Subscribers exactly at the previous sequence receive it.  A subscriber that
  fell behind is marked stale and asked to resynchronize; a full snapshot is
  the only event that can heal it."
  [feed st next-seq event-for]
  (let [previous-seq (dec next-seq)
        result
        (reduce-kv
         (fn [acc [uid sid :as key] subscription]
           (let [state (:state acc)]
             (cond
               (:stale? subscription)
               acc

               (ack-window-exceeded? state subscription next-seq)
               (let [stale (assoc subscription :stale? true
                                  :last-error :ack-window-exceeded)
                     _ (send-one! stale (resync-event feed state :ack-window))]
                 (-> acc
                     (assoc-in [:state :subscriptions key] stale)
                     (update :stale inc)))

               (= (:seq subscription) previous-seq)
               (let [event (event-for sid)
                     [subscription delivered?] (send-one! subscription event)]
                 (-> acc
                     (assoc-in [:state :subscriptions key]
                               (cond-> subscription
                                 delivered? (assoc :seq next-seq)
                                 (and delivered? (not (:ack-mode? subscription)))
                                 (assoc :acked-seq next-seq)
                                 (not delivered?) (assoc :stale? true
                                                          :last-error :send-failed)))
                     (cond-> delivered? (update :delivered inc)
                       (not delivered?) (update :stale inc))))

               :else
               (let [stale (assoc subscription :stale? true
                                  :last-error :sequence-gap)
                     _ (send-one! stale (resync-event feed state :sequence-gap))]
                 (-> acc
                     (assoc-in [:state :subscriptions key] stale)
                     (update :stale inc))))))
         {:state st :delivered 0 :stale 0}
         (:subscriptions st))]
    (reset! (:state_ feed) (:state result))
    result))

(defn- deliver-to-all
  "Deliver a full baseline event to every subscriber.  A snapshot is a valid
  starting point at any sequence, so it heals stale subscriptions."
  [feed st next-seq event-for]
  (let [result
        (reduce-kv
         (fn [acc [uid sid :as key] subscription]
           (let [event (event-for sid)
                 [subscription delivered?] (send-one! subscription event)]
             (-> acc
                 (assoc-in [:state :subscriptions key]
                           (assoc subscription
                                  :seq next-seq
                                  :acked-seq next-seq
                                  :stale? (not delivered?)
                                  :last-error (when-not delivered? :send-failed)))
                 (cond-> delivered? (update :delivered inc)))))
         {:state st :delivered 0}
         (:subscriptions st))]
    (reset! (:state_ feed) (:state result))
    result))

;; ---------------------------------------------------------------------------
;; Publishing

(defn publish-snapshot!
  "Publish a full state snapshot.

  With `:new-epoch? true`, the epoch advances and the sequence resets to zero;
  every live subscription receives a new baseline.  Without it, the snapshot
  consumes the next sequence but is still a complete baseline, so it heals
  subscribers that fell behind."
  [feed new-snapshot {:keys [new-epoch? ref]}]
  (locking (:lock feed)
    (let [st (state feed)]
      (if-not (map? new-snapshot)
        {:ok? false
         :error {:code :STATE_SNAPSHOT_INVALID
                 :message "snapshot must be a map"
                 :snapshot new-snapshot}}
        (let [epoch (if new-epoch? (inc (:epoch st)) (:epoch st))
              next-seq (if new-epoch? 0 (inc (:seq st)))
              usable-ref? (ref-matches? ref :state-snapshot epoch next-seq)]
          (if (and ref (not usable-ref?))
            {:ok? false
             :error {:code :STATE_OBJECT_BOUNDARY_MISMATCH
                     :message "snapshot reference does not match the assigned boundary"
                     :event-type "STATE_SNAPSHOT"
                     :ref-epoch (:epoch ref)
                     :ref-seq (:seq ref)
                     :epoch epoch
                     :seq next-seq}}
            (let [st' (assoc st
                             :epoch epoch
                             :seq next-seq
                             :snapshot new-snapshot)
                  result (deliver-to-all
                          feed st' next-seq
                          (fn [sid]
                            (state-event feed "STATE_SNAPSHOT"
                                         (cond-> {:epoch epoch
                                                  :seq next-seq
                                                  :subscription-id sid}
                                           usable-ref?
                                           (assoc :snapshot-ref ref)
                                           (not usable-ref?)
                                           (assoc :snapshot new-snapshot)))))]
              {:ok? true
               :epoch epoch
               :seq next-seq
               :new-epoch? (boolean new-epoch?)
               :delivered (:delivered result)})))))))

(defn publish-delta!
  "Apply one RFC 6902 patch, advance the sequence and publish `STATE_DELTA`.

  A failed patch leaves the epoch, sequence and tree untouched.  A supplied
  `:ref` must name the exact assigned boundary; a mismatch is rejected before
  any state changes."
  ([feed delta]
   (publish-delta! feed delta {}))
  ([feed delta {:keys [ref]}]
   (locking (:lock feed)
     (let [st (state feed)
           result (patch/apply-patch (:snapshot st) delta)]
       (if-not (:ok? result)
         {:ok? false
          :error (assoc (:error result) :event-type "STATE_DELTA")}
         (let [tree (:value result)]
           (if-not (map? tree)
             {:ok? false
              :error {:code :STATE_DELTA_INVALID
                      :message "patched state must remain a map"
                      :event-type "STATE_DELTA"}}
             (let [next-seq (inc (:seq st))
                   usable-ref? (ref-matches? ref :state-delta (:epoch st)
                                             next-seq)]
               (if (and ref (not usable-ref?))
                 {:ok? false
                  :error {:code :STATE_OBJECT_BOUNDARY_MISMATCH
                          :message "delta reference does not match the assigned boundary"
                          :event-type "STATE_DELTA"
                          :ref-epoch (:epoch ref)
                          :ref-seq (:seq ref)
                          :epoch (:epoch st)
                          :seq next-seq}}
                 (let [st' (assoc st :seq next-seq :snapshot tree)
                       delivered (deliver-sequential
                                  feed st' next-seq
                                  (fn [sid]
                                    (state-event
                                     feed "STATE_DELTA"
                                     (cond-> {:epoch (:epoch st')
                                              :seq next-seq
                                              :subscription-id sid}
                                       usable-ref?
                                       (assoc :delta-ref ref)
                                       (not usable-ref?)
                                       (assoc :delta delta)))))]
                   {:ok? true
                    :epoch (:epoch st')
                    :seq next-seq
                    :delivered (:delivered delivered)
                    :stale (:stale delivered)}))))))))))

(def ^:private message-extension-keys
  [:hc/roles :hc/clear-roles :hc/epoch-rebuild? :hc/order
   :roles :clear-roles :epoch-rebuild? :order])

(defn publish-messages!
  "Publish `MESSAGES_SNAPSHOT` as the complete server-authoritative message
  list.  Extension keys for role clearing and order pass through unchanged.

  The gateway stores the list in canonical `:messages` form and removes any
  older normalized message tables from the baseline tree."
  [feed messages extensions]
  (locking (:lock feed)
    (let [st (state feed)]
      (if-not (sequential? messages)
        {:ok? false
         :error {:code :MESSAGES_SNAPSHOT_INVALID
                 :message "messages must be a sequence"
                 :messages messages}}
        (let [next-seq (inc (:seq st))
              tree (-> (:snapshot st)
                       (dissoc :message/by-id :message/order)
                       (assoc :messages (vec messages)))
              st' (assoc st :seq next-seq :snapshot tree)
              extra (select-keys extensions message-extension-keys)
              delivered (deliver-sequential
                         feed st' next-seq
                         (fn [sid]
                           (state-event feed "MESSAGES_SNAPSHOT"
                                        (merge {:messages (vec messages)
                                                :epoch (:epoch st')
                                                :seq next-seq
                                                :subscription-id sid}
                                               extra))))]
          {:ok? true
           :epoch (:epoch st')
           :seq next-seq
           :delivered (:delivered delivered)
           :stale (:stale delivered)})))))

(defn broadcast!
  "Send a non-state AG-UI event to every live subscription.

  The event is copied per subscription so it can carry the subscriber's own
  `:subscription-id`.  It does not consume the sequence."
  [feed event]
  (locking (:lock feed)
    (if-not (map? event)
      {:ok? false
       :error {:code :EVENT_INVALID
               :message "event must be a map"
               :event event}}
      (let [st (state feed)
            result
            (reduce-kv
             (fn [acc [uid sid :as key] subscription]
               (if (:stale? subscription)
                 acc
                 (let [event' (assoc event :subscription-id sid)
                       [subscription delivered?] (send-one! subscription event')]
                   (-> acc
                       (assoc-in [:state :subscriptions key]
                                 (if delivered?
                                   subscription
                                   (assoc subscription :stale? true
                                          :last-error :send-failed)))
                       (cond-> delivered? (update :delivered inc))))))
             {:state st :delivered 0}
             (:subscriptions st))]
        (reset! (:state_ feed) (:state result))
        {:ok? true :delivered (:delivered result)}))))

;; ---------------------------------------------------------------------------
;; Client acknowledgements and resynchronization

(defn ack!
  "Record that UID applied every event through SEQ.

  The first acknowledgement switches the subscription into ack mode.  A
  subscriber more than `:max-pending` events ahead becomes stale on the next
  publish.  Acknowledgements never change the server sequence."
  [feed uid sid applied-seq]
  (locking (:lock feed)
    (let [st (state feed)
          subscription (get-in st [:subscriptions [uid sid]])]
      (cond
        (nil? subscription)
        {:ok? false
         :error {:code :STALE_SUBSCRIPTION
                 :message "subscription is not current"
                 :subscription-id sid
                 :action :resubscribe}}

        (:stale? subscription)
        {:ok? false
         :error {:code :STALE_SUBSCRIPTION
                 :message "subscription is stale; request a snapshot"
                 :subscription-id sid
                 :action :resync-state}}

        (not (and (integer? applied-seq) (<= 0 applied-seq (:seq st))))
        {:ok? false
         :error {:code :ACK_INVALID
                 :message "ack sequence must be between zero and the current sequence"
                 :subscription-id sid
                 :seq applied-seq
                 :current-seq (:seq st)}}

        :else
        (let [subscription' (assoc subscription
                                   :ack-mode? true
                                   :acked-seq applied-seq)]
          (reset! (:state_ feed)
                  (assoc-in st [:subscriptions [uid sid]] subscription'))
          {:ok? true
           :subscription-id sid
           :acked-seq applied-seq
           :epoch (:epoch st)
           :seq (:seq st)})))))

(defn resync!
  "Send a fresh baseline to the current subscription and clear its stale flag.

  The subscription keeps its client generation; no new SID is required.
  `:baseline-ref`, when supplied and matching the current boundary, replaces
  the inline snapshot."
  ([feed uid sid]
   (resync! feed uid sid {}))
  ([feed uid sid {:keys [baseline-ref]}]
   (locking (:lock feed)
     (let [st (state feed)
           subscription (get-in st [:subscriptions [uid sid]])]
       (if-not subscription
         {:ok? false
          :error {:code :STALE_SUBSCRIPTION
                  :message "subscription is not current"
                  :subscription-id sid
                  :action :resubscribe}}
         (let [use-ref? (ref-matches? baseline-ref :state-snapshot
                                      (:epoch st) (:seq st))
               event (state-event feed "STATE_SNAPSHOT"
                                  (cond-> {:epoch (:epoch st)
                                           :seq (:seq st)
                                           :subscription-id sid}
                                    use-ref? (assoc :snapshot-ref baseline-ref)
                                    (not use-ref?)
                                    (assoc :snapshot (:snapshot st))))
               [subscription' delivered?] (send-one! subscription event)]
           (reset! (:state_ feed)
                   (assoc-in st [:subscriptions [uid sid]]
                             (assoc subscription'
                                    :seq (:seq st)
                                    :acked-seq (:seq st)
                                    :stale? (not delivered?))))
           (if delivered?
             {:ok? true
              :subscription-id sid
              :epoch (:epoch st)
              :seq (:seq st)
              :event event}
             {:ok? false
              :subscription-id sid
              :error {:code :SUBSCRIPTION_DELIVERY_FAILED
                      :message "baseline snapshot could not be delivered"}})))))))

(defn close!
  "Retire every subscription.  The feed stops delivering until a new
  subscription arrives."
  [feed]
  (locking (:lock feed)
    (let [st (state feed)
          count' (count (:subscriptions st))]
      (reset! (:state_ feed)
              (-> st
                  (assoc :subscriptions {})
                  (assoc :uid->sid {})))
      {:ok? true :removed count'})))
