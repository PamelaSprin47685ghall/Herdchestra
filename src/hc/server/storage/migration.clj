(ns hc.server.storage.migration
  "Validation, canonical digest and pure transaction planning.

  A migration is the only way state changes.  It is a data value:

      {:migration-id \"uuid\"        required, idempotency key
       :partition \"session/abc\"     required
       :session-id \"abc\"            optional
       :expected-version 41          optional per-partition compare-and-swap
       :at \"2026-11-03T...\"         optional; excluded from the digest
       :reason :accept-user-message  optional
       :put [{:kind :task :id \"t1\" :doc {..}}]
       :delete [{:kind :task :id \"t1\"}]
       :obligations {:put [..] :remove [\"id\" ..]}
       :blob-refs {:add [..] :release [\"sha256-..\" ..]}
       :meta {..}}

  The digest covers the semantic payload but not `:at`, so a retry that does
  not reproduce the timestamp still replays the original receipt.  A different
  payload with the same migration ID is a conflict, never a second commit.

  Planning is pure.  It returns the exact `[:put k v]` / `[:del k]` vector for
  one atomic `transact-kv` call.  The facade adds the receipt-prune delete and
  performs the commit."

  (:require [clojure.string :as str]
            [hc.server.storage.error :as err]
            [hc.server.storage.keys :as keys]))

(def allowed-keys
  #{:migration-id :partition :session-id :expected-version :at :reason
    :put :delete :obligations :blob-refs :meta})

(def ^:private reserved-doc-kinds
  "Kinds with a dedicated channel.  Writing them as ordinary documents would
  silently bypass obligation indexing or blob reference management."
  #{:obligation :blob-ref})

(defn- invalid [message data]
  (err/raise :STORAGE_INVALID_MIGRATION message data))

(defn- non-empty-string? [x]
  (and (string? x) (not (str/blank? x))))

(defn- check-segment [label x max-len]
  (when-not (non-empty-string? x)
    (invalid (str label " must be a non-empty string") {label x}))
  (when (> (count x) max-len)
    (invalid (str label " is too long") {label x :max max-len}))
  x)

(defn- kind-of [x]
  (cond
    (keyword? x) x
    (and (string? x) (not (str/blank? x))) (keyword x)
    :else (invalid "document :kind must be a keyword or a non-empty string"
                   {:kind x})))

(defn- normalize-doc-entry [entry]
  (when-not (map? entry)
    (invalid "each :put entry must be a map" {:entry entry}))
  (let [unknown (seq (remove #{:kind :id :doc} (keys entry)))]
    (when unknown
      (invalid "unknown :put entry keys" {:unknown (vec unknown)})))
  (let [kind (kind-of (:kind entry))]
    (when (contains? reserved-doc-kinds kind)
      (invalid "this kind has a dedicated migration channel"
               {:kind kind
                :hint "use :obligations or :blob-refs"}))
    {:kind kind
     :id (check-segment :id (:id entry) 200)
     :doc (let [doc (:doc entry)]
            (when-not (map? doc)
              (invalid "document :doc must be a map" {:kind kind :id (:id entry)}))
            doc)}))

(defn- normalize-delete-entry [entry]
  (when-not (map? entry)
    (invalid "each :delete entry must be a map" {:entry entry}))
  (let [unknown (seq (remove #{:kind :id} (keys entry)))]
    (when unknown
      (invalid "unknown :delete entry keys" {:unknown (vec unknown)})))
  {:kind (kind-of (:kind entry))
   :id (check-segment :id (:id entry) 200)})

(defn- normalize-obligations [x]
  (when-not (or (nil? x) (map? x))
    (invalid ":obligations must be a map" {:obligations x}))
  (let [{:keys [put] remove-ids :remove :as m} (or x {})
        unknown (seq (clojure.core/remove #{:put :remove} (keys m)))]
    (when unknown
      (invalid "unknown :obligations keys" {:unknown (vec unknown)}))
    {:put (mapv (fn [doc]
                  (when-not (map? doc)
                    (invalid "each obligation must be a map" {:obligation doc}))
                  (check-segment :obligation/id (:obligation/id doc) 200)
                  (when (contains? doc :open?)
                    (when-not (boolean? (:open? doc))
                      (invalid "obligation :open? must be a boolean"
                               {:obligation-id (:obligation/id doc)})))
                  doc)
                (or put []))
     :remove (mapv (fn [id] (check-segment :obligation/id id 200)) (or remove-ids []))}))

(defn- normalize-blob-refs [x]
  (when-not (or (nil? x) (map? x))
    (invalid ":blob-refs must be a map" {:blob-refs x}))
  (let [{:keys [add release] :as m} (or x {})
        unknown (seq (remove #{:add :release} (keys m)))]
    (when unknown
      (invalid "unknown :blob-refs keys" {:unknown (vec unknown)}))
    {:add (mapv (fn [ref]
                  (when-not (map? ref)
                    (invalid "each blob reference must be a map" {:ref ref}))
                  (check-segment :id (:id ref) 200)
                  ref)
                (or add []))
     :release (mapv (fn [ref]
                      (let [id (if (map? ref) (:id ref) ref)]
                        (check-segment :id id 200)))
                    (or release []))}))

(defn- canonical-form [x]
  (cond
    (map? x)
    (into (sorted-map-by (fn [a b] (compare (pr-str a) (pr-str b))))
          (map (fn [[k v]] [k (canonical-form v)]))
          x)

    (set? x)
    (mapv canonical-form (sort-by pr-str (map canonical-form x)))

    (or (vector? x) (seq? x))
    (mapv canonical-form x)

    :else x))

(defn- sha256-hex [^String s]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")
        bytes  (.digest digest (.getBytes s java.nio.charset.StandardCharsets/UTF_8))]
    (apply str (map #(format "%02x" (bit-and (long %) 0xFF)) bytes))))

(defn digest
  "Stable SHA-256 of the semantic payload.  `:at` is excluded."
  [m]
  (sha256-hex (pr-str (canonical-form (dissoc m :at :digest)))))

(defn normalize
  "Validate and normalize a migration map.  Throws `:STORAGE_INVALID_MIGRATION`
  for any shape error.  Returns the map with `:digest` added."
  [m {:keys [max-doc-chars] :or {max-doc-chars 8000000}}]
  (when-not (map? m)
    (invalid "a migration must be a map" {:migration m}))
  (let [unknown (seq (remove allowed-keys (keys m)))]
    (when unknown
      (invalid "unknown migration keys" {:unknown (vec unknown)})))
  (let [migration-id (check-segment :migration-id (:migration-id m) 200)
        partition    (check-segment :partition (:partition m) 200)
        session-id   (when-some [s (:session-id m)]
                       (check-segment :session-id s 200))
        expected     (:expected-version m)
        _            (when (and (some? expected)
                                (not (and (integer? expected) (not (neg? expected)))))
                       (invalid ":expected-version must be a non-negative integer"
                                {:expected-version expected}))
        at           (or (:at m) (str (java.time.Instant/now)))
        _            (when-not (non-empty-string? at)
                       (invalid ":at must be a non-empty string" {:at at}))
        put          (mapv normalize-doc-entry (:put m))
        delete       (mapv normalize-delete-entry (:delete m))
        _            (let [dups (->> (concat put delete)
                                     (map (juxt :kind :id))
                                     frequencies
                                     (filter (fn [[_ n]] (> n 1)))
                                     (map first))]
                       (when (seq dups)
                         (invalid "a document appears more than once in one migration"
                                  {:duplicates (vec dups)})))
        _            (doseq [{:keys [kind id doc]} put]
                       (let [n (count (pr-str doc))]
                         (when (> n max-doc-chars)
                           (invalid "document is too large for the current-state store"
                                    {:kind kind :id id
                                     :chars n :max-doc-chars max-doc-chars
                                     :hint "store large content in the blob store and keep a reference"}))))
        obligations  (normalize-obligations (:obligations m))
        blob-refs    (normalize-blob-refs (:blob-refs m))
        meta         (let [x (or (:meta m) {})]
                       (when-not (map? x)
                         (invalid ":meta must be a map" {:meta x}))
                       x)
        normalized   {:migration-id migration-id
                      :partition partition
                      :session-id session-id
                      :expected-version expected
                      :at at
                      :reason (:reason m)
                      :put put
                      :delete delete
                      :obligations obligations
                      :blob-refs blob-refs
                      :meta meta}]
    (assoc normalized :digest (digest normalized))))

(defn- doc-value [kind id partition session-id tx-version at doc]
  {:storage/kind kind
   :storage/id id
   :storage/partition partition
   :storage/session-id session-id
   :storage/version tx-version
   :storage/updated-at at
   :doc doc})

(defn plan
  "Build the atomic op vector and receipt for a normalized migration.

  `ctx` supplies values read by the facade under the commit lock:
    :tx-version        new global version
    :partition-version current partition version
    :partition-meta    current partition metadata map
    :prune-receipt     optional `[key value]` of the oldest receipt to drop"
  [m {:keys [tx-version partition-version partition-meta prune-receipt]}]
  (let [{:keys [migration-id partition session-id at reason digest
                put delete obligations blob-refs meta]} m
        receipt {:migration-id migration-id
                 :digest digest
                 :version tx-version
                 :partition-version (inc (long (or partition-version 0)))
                 :partition partition
                 :session-id session-id
                 :at at
                 :reason reason}
        doc-ops (mapv (fn [{:keys [kind id doc]}]
                        [:put (keys/doc-key partition session-id kind id)
                         (doc-value kind id partition session-id tx-version at doc)])
                      put)
        del-ops (mapv (fn [{:keys [kind id]}]
                        [:del (keys/doc-key partition session-id kind id)])
                      delete)
        ob-put-ops (mapv (fn [doc]
                           [:put (keys/obligation-key partition session-id (:obligation/id doc))
                            (doc-value :obligation (:obligation/id doc)
                                       partition session-id tx-version at doc)])
                         (:put obligations))
        ob-del-ops (mapv (fn [id]
                           [:del (keys/obligation-key partition session-id id)])
                         (:remove obligations))
        blob-add-ops (mapv (fn [ref]
                             [:put (keys/blob-ref-key partition (:id ref))
                              (doc-value :blob-ref (:id ref)
                                         partition nil tx-version at ref)])
                           (:add blob-refs))
        blob-del-ops (mapv (fn [id]
                             [:del (keys/blob-ref-key partition id)])
                           (:release blob-refs))
        merged-meta (merge (or partition-meta {}) meta)
        meta-ops (cond-> [[:put (keys/partition-meta-key partition "version")
                           (inc (long (or partition-version 0)))]
                          [:put (keys/partition-meta-key partition "last-commit") receipt]
                          [:put (keys/meta-key "store-version") tx-version]
                          [:put (keys/meta-key "last-commit") receipt]]
                   (seq meta)
                   (conj [:put (keys/partition-meta-key partition "meta") merged-meta]))
        receipt-ops [[:put (keys/receipt-key tx-version migration-id) receipt]
                     [:put (keys/receipt-index-key migration-id)
                      (keys/receipt-key tx-version migration-id)]]
        prune-ops (when prune-receipt
                    (let [[k v] prune-receipt]
                      [[:del k]
                       [:del (keys/receipt-index-key (:migration-id v))]]))]
    {:ops (vec (concat doc-ops del-ops ob-put-ops ob-del-ops
                       blob-add-ops blob-del-ops meta-ops receipt-ops prune-ops))
     :receipt receipt
     :partition-version (inc (long (or partition-version 0)))}))
