(ns hc.client.sync.messages
  "Apply an AG-UI `MESSAGES_SNAPSHOT` to the protocol state tree.

  Standard AG-UI treats a snapshot that mentions neither `activity` nor
  `reasoning` as not declaring those roles absent.  This namespace therefore
  preserves existing records for undeclared special roles.  A server that
  really wants them gone must say so with the project extensions:

    :hc/roles         complete role set represented by this snapshot
    :hc/clear-roles   roles to clear even when present in the snapshot
    :hc/epoch-rebuild? true when the snapshot is part of a new epoch
    :hc/order         complete display order of the surviving message IDs

  Ordinary roles are always replaced by the snapshot.  Preserved special-role
  records keep their entity data; without `:hc/order` they are appended after
  the snapshot's order."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [hc.client.sync.normalize :as normalize]))

;; ---------------------------------------------------------------------------
;; Results and event fields

(defn- ok [value]
  {:ok? true :value value})

(defn- fail [code message details]
  {:ok? false :error (merge {:code code :message message} details)})

(defn- field-present?
  [event k]
  (or (contains? event k)
      (contains? event (normalize/keyword-string k))))

(defn- field
  [event k]
  (if (contains? event k)
    (get event k)
    (get event (normalize/keyword-string k))))

(defn- field-any
  [event ks]
  (some (fn [k] (when (field-present? event k) (field event k))) ks))

(defn- message-id [message]
  (normalize/entity-id message))

(defn- message-role [message]
  (normalize/role-key (or (get message :role) (get message "role"))))

;; ---------------------------------------------------------------------------
;; Input parsing

(defn- validate-messages
  [messages]
  (if-not (sequential? messages)
    (fail :invalid-messages "messages must be a sequence" {:messages messages})
    (loop [remaining (seq messages)
           index 0
           ids #{}
           by-id {}
           order []]
      (if (seq remaining)
        (let [message (first remaining)
              id (message-id message)
              role (message-role message)]
          (cond
            (not (map? message))
            (fail :invalid-message "message must be a map"
                  {:index index :message message})

            (nil? id)
            (fail :invalid-message "message needs a non-blank string :id"
                  {:index index :message message})

            (contains? ids id)
            (fail :duplicate-message-id
                  (str "duplicate message id " (pr-str id))
                  {:index index :id id})

            (nil? role)
            (fail :invalid-message "message needs a non-blank :role"
                  {:index index :id id})

            :else
            (recur (next remaining)
                   (inc index)
                   (conj ids id)
                   (assoc by-id id message)
                   (conj order id))))
        (ok {:by-id by-id :order order})))))

(defn- parse-role-set
  [value]
  (cond
    (nil? value)
    (ok nil)

    (or (sequential? value) (set? value))
    (let [roles (map normalize/role-key value)]
      (if (every? some? roles)
        (ok (set roles))
        (fail :invalid-roles "roles must be non-blank strings or keywords"
              {:roles value})))

    :else
    (fail :invalid-roles "roles must be a sequence or set"
          {:roles value})))

(defn- parse-clear-roles
  [value]
  (cond
    (or (nil? value) (false? value))
    (ok #{})

    (true? value)
    (ok normalize/special-roles)

    (or (sequential? value) (set? value))
    (parse-role-set value)

    :else
    (fail :invalid-roles
          "clear roles must be true, a sequence, or a set"
          {:clear-roles value})))

(defn- parse-order
  [value]
  (if (nil? value)
    (ok nil)
    (if (and (sequential? value)
             (every? #(and (string? %) (not (str/blank? %))) value)
             (= (count value) (count (distinct value))))
      (ok (vec value))
      (fail :invalid-message-order
            "explicit order must be a sequence of distinct message IDs"
            {:order value}))))

;; ---------------------------------------------------------------------------
;; Current message representation

(defn- validate-current-order
  [order by-id]
  (if (normalize/valid-order? order by-id)
    (ok order)
    (fail :invalid-message-order
          "current message order must list every message ID exactly once"
          {:order order :ids (set (keys by-id))})))

(defn- current-view
  [server]
  (let [by-id-keys (normalize/find-keys server :message/by-id)
        order-keys (normalize/find-keys server :message/order)
        messages-keys (normalize/find-keys server :messages)]
    (cond
      (> (count by-id-keys) 1)
      (fail :conflicting-representation
            "message/by-id has both keyword and string keys"
            {:keys by-id-keys})

      (> (count order-keys) 1)
      (fail :conflicting-representation
            "message/order has both keyword and string keys"
            {:keys order-keys})

      (> (count messages-keys) 1)
      (fail :conflicting-representation
            "messages has both keyword and string keys"
            {:keys messages-keys})

      (and (seq by-id-keys) (seq messages-keys))
      (fail :conflicting-representation
            "both normalized and compact message collections exist"
            {:by-id-key (first by-id-keys)
             :messages-key (first messages-keys)})

      (and (seq order-keys)
           (empty? by-id-keys)
           (empty? messages-keys))
      (fail :invalid-message-order
            "message order exists without any message collection"
            {:order-key (first order-keys)})

      (seq by-id-keys)
      (let [by-id (get server (first by-id-keys))]
        (cond
          (not (map? by-id))
          (fail :invalid-messages "message/by-id must be a map"
                {:value by-id})

          (not (every? #(and (string? %) (not (str/blank? %)))
                       (keys by-id)))
          (fail :invalid-messages
                "message/by-id keys must be non-blank strings"
                {:keys (keys by-id)})

          :else
          (let [order-result
                (if (seq order-keys)
                  (validate-current-order (get server (first order-keys)) by-id)
                  (ok (vec (sort (keys by-id)))))]
            (if (:ok? order-result)
              (ok {:kind :normalized
                   :by-id by-id
                   :order (:value order-result)
                   :by-id-key (first by-id-keys)
                   :order-key (first order-keys)})
              order-result))))

      (seq messages-keys)
      (let [messages (get server (first messages-keys))
            result (validate-messages messages)]
        (if-not (:ok? result)
          result
          (let [by-id (:by-id (:value result))
                derived-order (:order (:value result))
                order-result (if (seq order-keys)
                               (validate-current-order
                                (get server (first order-keys))
                                by-id)
                               (ok derived-order))]
            (if (:ok? order-result)
              (ok {:kind :raw
                   :by-id by-id
                   :order (:value order-result)
                   :messages-key (first messages-keys)
                   :order-key (first order-keys)
                   :messages messages})
              order-result))))

      :else
      (ok {:kind :empty
           :by-id {}
           :order []
           :by-id-key :message/by-id
           :order-key :message/order}))))

;; ---------------------------------------------------------------------------
;; Preservation and replacement

(defn- preserved-messages
  [current preserve-roles snapshot-by-id]
  (let [by-id (:by-id current)
        order (:order current)
        ordered-ids (set order)
        in-order (->> order
                      (keep (fn [id]
                              (let [message (get by-id id)]
                                (when (and (contains? preserve-roles
                                                      (message-role message))
                                           (not (contains? snapshot-by-id id)))
                                  message))))
                      vec)
        extra (->> by-id
                   (remove (fn [[id _]] (contains? ordered-ids id)))
                   (filter (fn [[_ message]]
                             (contains? preserve-roles (message-role message))))
                   (sort-by (fn [[id _]] id))
                   (map second)
                   vec)]
    (into in-order extra)))

(defn- snapshot-kind
  "Classify a validated message-snapshot shape.  The default is explicit so a
  future shape cannot fall through a case."
  [current]
  (case (:kind current)
    :raw :raw
    :normalized :normalized
    :empty :empty
    :unknown))

(defn- update-server
  [current server by-id order]
  (case (snapshot-kind current)
    :raw
    (cond-> (assoc server (:messages-key current) (mapv by-id order))
      (:order-key current) (assoc (:order-key current) order))

    :normalized
    (-> server
        (assoc (or (:by-id-key current) :message/by-id) by-id)
        (assoc (or (:order-key current) :message/order) order))

    :empty
    (-> server
        (assoc :message/by-id by-id)
        (assoc :message/order order))

    ;; Keep the server tree and publish an explicit marker instead of
    ;; silently claiming the snapshot was applied.
    (assoc server :message/snapshot-kind-error
           {:code :UNKNOWN_SNAPSHOT_KIND
            :kind (:kind current)})))

(defn apply-messages-snapshot
  "Apply `event` to `server`, returning `{:ok? true :server server' :order [...]}`
  or an error.  The event must be an ordered synchronization event; the adapter
  checks its epoch and sequence before calling this function."
  [server event]
  (if-not (map? server)
    (fail :invalid-server-state "protocol state must be a map" {:state server})
    (let [messages (field event :messages)
          validated (validate-messages messages)
          current-result (current-view server)]
      (cond
        (not (:ok? validated))
        validated

        (not (:ok? current-result))
        current-result

        :else
        (let [declared-result (parse-role-set (field-any event [:hc/roles :roles]))
              clear-result (parse-clear-roles
                            (field-any event
                                       [:hc/clear-roles
                                        :hc/clear-special-roles
                                        :clear-roles]))
              order-result (parse-order (field-any event [:hc/order :order]))
              epoch-rebuild? (true? (field-any event
                                               [:hc/epoch-rebuild?
                                                :epoch-rebuild?]))]
          (cond
            (not (:ok? declared-result))
            declared-result

            (not (:ok? clear-result))
            clear-result

            (not (:ok? order-result))
            order-result

            :else
            (let [snapshot (:value validated)
                  current (:value current-result)
                  declared (:value declared-result)
                  explicit-clear (:value clear-result)
                  explicit-order (:value order-result)
                  snapshot-roles (set (map message-role
                                           (vals (:by-id snapshot))))]
              (if (and declared (not (every? declared snapshot-roles)))
                (fail :undeclared-role
                      "snapshot contains a role outside the declared role set"
                      {:declared declared :roles snapshot-roles})
                (let [declared-clears (if declared
                                        (set/difference normalize/special-roles
                                                        declared)
                                        #{})
                      epoch-clears (if epoch-rebuild?
                                     (set/difference normalize/special-roles
                                                     snapshot-roles)
                                     #{})
                      effective-clear (set/union
                                       (set/intersection explicit-clear
                                                         normalize/special-roles)
                                       declared-clears
                                       epoch-clears)
                      replace-roles (set
                                     (filter
                                      (fn [role]
                                        (and (not (contains? effective-clear role))
                                             (or (contains? snapshot-roles role)
                                                 (and declared
                                                      (contains? declared role)))))
                                      normalize/special-roles))
                      preserve-roles (set/difference normalize/special-roles
                                                     effective-clear
                                                     replace-roles)
                      kept-snapshot (filterv
                                     #(not (contains? effective-clear
                                                      (message-role %)))
                                     (mapv #(get (:by-id snapshot) %)
                                           (:order snapshot)))
                      kept-snapshot-by-id (into {}
                                                (map (juxt message-id identity))
                                                kept-snapshot)
                      kept-snapshot-order (mapv message-id kept-snapshot)
                      preserved (preserved-messages current
                                                    preserve-roles
                                                    kept-snapshot-by-id)
                      new-by-id (merge kept-snapshot-by-id
                                       (into {} (map (juxt message-id identity))
                                             preserved))
                      default-order (into kept-snapshot-order
                                          (map message-id)
                                          preserved)
                      final-order (or explicit-order default-order)]
                  (if (and explicit-order
                           (not (normalize/valid-order? explicit-order
                                                        new-by-id)))
                    (fail :invalid-message-order
                          "explicit order must list every surviving message ID exactly once"
                          {:order explicit-order
                           :ids (set (keys new-by-id))})
                    (ok {:server (update-server current
                                                server
                                                new-by-id
                                                final-order)
                         :order final-order})))))))))))
