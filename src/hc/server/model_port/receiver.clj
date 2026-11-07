(ns hc.server.model-port.receiver
  "Bounded delivery from transport callbacks to the Owner.

  A provider callback thread calls `deliver!` with one event.  The receiver
  only appends to a bounded vector and returns; it never blocks on I/O.  When
  the bound is reached, the event is refused with `:overflow` and counted.
  Silent loss is not allowed: the adapter must surface the refusal as a
  failed attempt or a diagnostic.

  `merge-chunks` is a pure helper for the consumer.  It coalesces adjacent
  text chunks for the same attempt and output so the Owner can process a
  bounded batch without changing event order."
  (:require [hc.server.model-port.contract :as contract]))

(defprotocol BoundedReceiver
  (drain! [receiver]
    "Remove and return all pending events with the counters.")
  (close! [receiver]
    "Stop accepting events and return the remaining events.")
  (stats [receiver]
    "Capacity and counters; never includes event payloads."))

(defn bounded-receiver
  "Create a receiver holding at most `capacity` pending events."
  [capacity]
  (when-not (and (integer? capacity) (pos? capacity))
    (throw (ex-info "receiver capacity must be a positive integer"
                    {:error/kind :invalid-receiver
                     :capacity capacity})))
  (let [state (atom {:capacity capacity
                     :events []
                     :accepted 0
                     :dropped 0
                     :closed? false})]
    (reify
      contract/Receiver
      (deliver! [_ event]
        (let [[before after]
              (swap-vals! state
                          (fn [{:keys [capacity events closed?] :as s}]
                            (cond
                              closed? s
                              (>= (count events) capacity)
                              (update s :dropped inc)
                              :else
                              (-> s
                                  (update :events conj event)
                                  (update :accepted inc)))))]
          (cond
            (:closed? before)
            {:ok false :reason :closed}

            (>= (count (:events before)) capacity)
            {:ok false
             :reason :overflow
             :dropped (:dropped after)
             :capacity capacity}

            :else
            {:ok true :size (count (:events after))})))

      BoundedReceiver
      (drain! [_]
        (let [[before after] (swap-vals! state assoc :events [])]
          {:events (:events before)
           :dropped (:dropped after)
           :accepted (:accepted after)
           :closed? (:closed? after)}))

      (close! [_]
        (let [[before after] (swap-vals! state
                                         (fn [s]
                                           (assoc s :closed? true :events [])))]
          {:events (:events before)
           :dropped (:dropped after)
           :accepted (:accepted after)
           :closed? true}))

      (stats [_]
        (select-keys @state [:capacity :accepted :dropped :closed?])))))

(defn merge-chunks
  "Coalesce consecutive text chunks with the same identity.

  A chunk is `{:event :chunk :attempt-id ... :call-id ... :output-index ...
  :text \"...\"}`.  Only adjacent chunks merge; a tool call, completion, or a
  different identity ends the run.  Non-text chunks pass through unchanged."
  [events]
  (reduce (fn [merged event]
            (let [last-event (peek merged)
                  mergeable? (and (= :chunk (:event event))
                                  (= :chunk (:event last-event))
                                  (= (:attempt-id event) (:attempt-id last-event))
                                  (= (:call-id event) (:call-id last-event))
                                  (= (:output-index event) (:output-index last-event))
                                  (string? (:text event))
                                  (string? (:text last-event)))]
              (if mergeable?
                (conj (pop merged)
                      (update last-event :text str (:text event)))
                (conj merged event))))
          []
          events))
