(ns hc.core.relay.ledger
  "Pure obligation-ledger operations for the relay state machine.

  A ledger is a map of string key -> non-blank string value.  `nil` values
  never live in a ledger; they are the release instruction used by a review."
  (:require [clojure.string :as str]))

(def empty-ledger
  "An empty obligation ledger.  Sorted maps keep receipts deterministic."
  (sorted-map))

(defn ledger-empty?
  [ledger]
  (empty? ledger))

(defn valid-key?
  [k]
  (and (string? k) (not (str/blank? k))))

(defn valid-value?
  [v]
  (or (nil? v)
      (and (string? v) (not (str/blank? v)))))

(defn valid-ledger?
  [ledger]
  (and (map? ledger)
       (every? (fn [[k v]]
                 (and (valid-key? k)
                      (string? v)
                      (not (str/blank? v))))
               ledger)))

(defn- invalid
  [message details]
  {:ok? false
   :error {:code :INVALID_FINDINGS
           :message message
           :details details}})

(defn normalize-findings
  "Validate a findings vector and strip unrelated keys.

  The protocol is a vector of `{:key <string> :value <string-or-nil>}`.
  A nil value releases the key.  Duplicate keys are applied in order by
  `apply-findings`, so the last occurrence wins."
  [findings]
  (if-not (sequential? findings)
    (invalid "findings must be a sequential collection"
             {:findings findings})
    (loop [remaining (seq findings)
           index 0
           acc []]
      (if-let [entry (first remaining)]
        (let [details {:index index :entry entry}]
          (cond
            (not (map? entry))
            (invalid "each finding must be a map" details)

            (not (contains? entry :key))
            (invalid "each finding must contain :key" details)

            (not (contains? entry :value))
            (invalid "each finding must contain :value (use nil to release)"
                     details)

            (not (valid-key? (:key entry)))
            (invalid ":key must be a non-blank string" details)

            (not (valid-value? (:value entry)))
            (invalid ":value must be nil or a non-blank string" details)

            :else
            (recur (next remaining)
                   (inc index)
                   (conj acc (select-keys entry [:key :value])))))
        {:ok? true :findings acc}))))

(defn apply-findings
  "Apply normalized findings to a ledger.

  `[]` leaves the ledger unchanged.  A nil value removes its key.  A
  non-blank string inserts or replaces its key."
  [ledger findings]
  (reduce (fn [l {:keys [key value]}]
            (if (nil? value)
              (dissoc l key)
              (assoc l key value)))
          (or ledger empty-ledger)
          findings))

(defn changes
  "Describe the difference between two ledgers for receipts and diagnostics."
  [before after]
  {:added (into (sorted-map)
                (remove (fn [[k v]] (= v (get before k))) after))
   :removed (into (sorted-map)
                  (remove (fn [[k _]] (contains? after k)) before))})

(defn entries
  "Stable key-ordered entries."
  [ledger]
  (sort-by key ledger))
