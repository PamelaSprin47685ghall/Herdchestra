(ns hc.client.sync.normalize
  "One-way conversion from the AG-UI protocol state tree to Fulcro-style
  entity tables.

  The server may send already-normalized tables (`:session/by-id`) or compact
  wire collections (`:sessions`).  Conversion happens once per accepted state
  boundary.  Nothing here merges an old table into a new one; a snapshot
  replaces the whole protocol tree, and `to-db` derives a fresh database view
  from it.

  Entity IDs cross the language boundary as non-blank strings, per AGENTS.md
  §7.3."
  (:require [clojure.string :as str]))

(def special-roles
  "Roles that a MESSAGES_SNAPSHOT may leave undeclared."
  #{:activity :reasoning})

(def raw-collections
  "Compact wire collection -> normalized Fulcro table."
  {:sessions :session/by-id
   :swarms :swarm/by-id
   :tasks :task/by-id
   :actions :action/by-id
   :messages :message/by-id
   :runs :run/by-id
   :calls :call/by-id
   :attempts :attempt/by-id
   :artifacts :artifact/by-id
   :projects :project/by-id
   :instances :instance/by-id
   :connections :connection/by-id
   :schedules :schedule/by-id
   :goals :goal/by-id
   :profiles :profile/by-id
   :credentials :credential/by-id
   :channels :channel/by-id
   :backups :backup/by-id
   :recoveries :recovery-point/by-id})

(defn- fail [code message details]
  {:ok? false :error (merge {:code code :message message} details)})

(defn- ok [value]
  {:ok? true :value value})

(defn keyword-string
  "Return the wire name of a keyword, or the string itself."
  [k]
  (if (keyword? k)
    (subs (str k) 1)
    (str k)))

(defn find-keys
  "Return every key in `m` that names keyword `k`.

  A map may use the keyword itself or its full string form.  Both are returned
  so callers can reject an ambiguous double representation."
  [m k]
  (let [s (keyword-string k)]
    (cond-> []
      (contains? m k) (conj k)
      (and (not= k s) (contains? m s)) (conj s))))

(defn find-key
  "Return the first key in `m` that names keyword `k`, or nil."
  [m k]
  (first (find-keys m k)))

(defn table-key?
  "True for a normalized table key such as `:session/by-id` or
  `\"session/by-id\"`."
  [k]
  (cond
    (keyword? k)
    (and (some? (namespace k)) (= "by-id" (name k)))

    (string? k)
    (and (str/includes? k "/")
         (str/ends-with? k "/by-id"))

    :else false))

(defn table-key
  "Return the keyword form of a normalized table key."
  [k]
  (if (keyword? k) k (keyword k)))

(defn- raw-table-key
  [k]
  (let [n (if (keyword? k) (name k) (str k))]
    (get raw-collections (keyword n))))

(defn entity-id
  "Return a non-blank string entity ID, or nil."
  [entity]
  (when (map? entity)
    (some (fn [id]
            (when (and (string? id) (not (str/blank? id)))
              id))
          [(get entity :id)
           (get entity "id")
           (get entity :message-id)
           (get entity "message-id")])))

(defn role-key
  "Normalize a message role to a lower-case keyword, or nil."
  [role]
  (when (or (keyword? role) (string? role) (symbol? role))
    (let [s (str/lower-case (name role))]
      (when-not (str/blank? s)
        (keyword s)))))

(defn- normalize-table
  [normalized-key value]
  (cond
    (nil? value)
    (ok {})

    (map? value)
    (if (and (every? #(and (string? %) (not (str/blank? %))) (keys value))
             (every? map? (vals value)))
      (ok value)
      (fail :invalid-entity
            "table keys must be non-blank string IDs and values must be entity maps"
            {:table normalized-key
             :keys (keys value)
             :invalid-values (remove map? (vals value))}))

    (sequential? value)
    (loop [entities (seq value)
           index 0
           out {}]
      (if (seq entities)
        (let [entity (first entities)
              id (entity-id entity)]
          (cond
            (nil? id)
            (fail :invalid-entity
                  "entity needs a non-blank string :id"
                  {:table normalized-key :index index})

            (contains? out id)
            (fail :duplicate-entity-id
                  (str "duplicate entity id " (pr-str id))
                  {:table normalized-key :index index :id id})

            :else
            (recur (next entities) (inc index) (assoc out id entity))))
        (ok out)))

    :else
    (fail :invalid-table
          "table value must be a map or a sequence of entities"
          {:table normalized-key :value value})))

(defn valid-order?
  "True when `order` lists every key of `by-id` exactly once as a string."
  [order by-id]
  (and (sequential? order)
       (every? #(and (string? %) (not (str/blank? %))) order)
       (= (count order) (count (distinct order)))
       (= (set order) (set (keys by-id)))))

(defn- order-error [message details]
  (fail :invalid-message-order message details))

(defn- message-session-id
  [message]
  (let [value (or (get message :session-id)
                  (get message "session-id")
                  (get message :session)
                  (get message "session"))]
    (when (and (string? value) (not (str/blank? value)))
      value)))

(defn- prune-orphan-messages
  "Drop messages whose session is no longer in the visible session table.

  A permanently deleted session can remain in an Owner conversation snapshot
  while its summary has already left `:session/by-id`.  The client must not
  retain those message entities; otherwise a later projection can resurrect
  deleted content even though the session is gone."
  [db]
  (let [sessions (get db :session/by-id)
        by-id (get db :message/by-id)]
    (if (and (map? sessions) (map? by-id))
      (let [visible (set (keys sessions))
            kept (into {}
                       (remove (fn [[_ message]]
                                 (when-let [session-id (message-session-id message)]
                                   (not (contains? visible session-id)))))
                       by-id)]
        (if (= (count kept) (count by-id))
          db
          (let [db' (assoc db :message/by-id kept)
                order (get db :message/order)]
            (if (sequential? order)
              (assoc db' :message/order
                     (filterv #(contains? kept %) order))
              db'))))
      db)))

(defn- validate-message-order
  [db]
  (let [order-keys (find-keys db :message/order)
        by-id-keys (find-keys db :message/by-id)]
    (cond
      (> (count order-keys) 1)
      (order-error "message order has both keyword and string keys"
                   {:keys order-keys})

      (and (seq order-keys) (empty? by-id-keys))
      (order-error "message order exists without message/by-id"
                   {:order (get db (first order-keys))})

      (seq order-keys)
      (let [order (get db (first order-keys))
            by-id (get db (first by-id-keys))]
        (if (valid-order? order by-id)
          (ok db)
          (order-error "message order must list every message ID exactly once"
                       {:order order :ids (set (keys by-id))})))

      :else
      (ok db))))

(defn to-db
  "Convert a protocol state tree into a Fulcro-style database map.

  Normalized tables are kept, compact collections are indexed by entity ID, and
  scalar protocol fields pass through.  A raw `:messages` collection also
  produces `:message/order` when the event did not provide one.  The result is
  a fresh map; callers replace the server-owned region rather than merging the
  old database into it."
  [server]
  (if-not (map? server)
    (fail :invalid-server-state
          "protocol state must be a map"
          {:state server})
    (let [entries (seq server)
          sources (reduce
                   (fn [acc [k _]]
                     (let [normalized (cond
                                        (table-key? k) (table-key k)
                                        (raw-table-key k) (raw-table-key k)
                                        :else nil)]
                       (if normalized
                         (update acc normalized (fnil conj []) k)
                         acc)))
                   {}
                   entries)
          conflict (first (filter (fn [[_ keys]]
                                    (> (count keys) 1))
                                  sources))]
      (if conflict
        (fail :conflicting-representation
              "both normalized and compact representations were supplied"
              {:table (first conflict) :keys (second conflict)})
        (let [order-keys (find-keys server :message/order)]
          (if (> (count order-keys) 1)
            (fail :conflicting-representation
                  "message order has both keyword and string keys"
                  {:keys order-keys})
            (loop [remaining entries
                   db {}]
              (if (seq remaining)
                (let [[k value] (first remaining)
                      normalized (cond
                                   (table-key? k) (table-key k)
                                   (raw-table-key k) (raw-table-key k)
                                   :else nil)]
                  (if normalized
                    (let [result (normalize-table normalized value)]
                      (if (:ok? result)
                        (let [db' (assoc db normalized (:value result))
                              db'' (if (and (= normalized :message/by-id)
                                            (empty? order-keys)
                                            (sequential? value))
                                     (assoc db' :message/order
                                            (mapv entity-id value))
                                     db')]
                          (recur (next remaining) db''))
                        result))
                    (let [scalar-key (if (= (keyword-string k) "message/order")
                                       :message/order
                                       k)]
                      (recur (next remaining) (assoc db scalar-key value)))))
                (validate-message-order (prune-orphan-messages db))))))))))
