(ns hc.server.storage.export
  "Validation and atomic restore planning for format-versioned export packages.

  Root AGENTS.md §7.1: manual migration and cross-database upgrade use an
  explicit data-format version, never a package version guess.  This namespace
  accepts only format version 1, validates the whole package before the disk
  is touched, and plans one transaction that replaces the entire current state.

  Restore is a whole-store operation.  A partial export is rejected, because
  deleting the partitions it omits would be data loss.  The caller must state
  that writers are quiescent; the adapter takes the commit lock, but it cannot
  prove that an Owner elsewhere has stopped."

  (:require [clojure.string :as str]
            [hc.server.storage.error :as err]
            [hc.server.storage.keys :as keys]
            [hc.server.storage.migration :as migration]))

(def format-version 1)

(def ^:private reserved-doc-kinds #{:obligation :blob-ref})

(defn- invalid [message data]
  (err/raise :STORAGE_INVALID_EXPORT message data))

(defn- non-empty-string? [x]
  (and (string? x) (not (str/blank? x))))

(defn- check-partition [p]
  (when-not (non-empty-string? p)
    (invalid "export partition names must be non-empty strings" {:partition p}))
  p)

(defn- check-kind [kind]
  (let [k (cond
            (keyword? kind) kind
            (and (string? kind) (not (str/blank? kind))) (keyword kind)
            :else (invalid "document kind must be a keyword or a non-empty string"
                           {:kind kind}))]
    (when (contains? reserved-doc-kinds k)
      (invalid "export must use the dedicated obligation and blob-ref sections"
               {:kind k}))
    k))

(defn- validate-doc [entry]
  (when-not (map? entry)
    (invalid "export document entries must be maps" {:entry entry}))
  (let [kind (check-kind (:kind entry))
        id   (:id entry)
        doc  (:doc entry)]
    (when-not (non-empty-string? id)
      (invalid "export document IDs must be non-empty strings" {:entry entry}))
    (when-not (map? doc)
      (invalid "export document :doc must be a map" {:kind kind :id id}))
    (when-some [s (:session-id entry)]
      (when-not (non-empty-string? s)
        (invalid "export document :session-id must be a string or nil"
                 {:kind kind :id id :session-id s})))
    {:kind kind :session-id (:session-id entry) :id id :doc doc}))

(defn- validate-obligation [entry]
  (when-not (map? entry)
    (invalid "export obligation entries must be maps" {:entry entry}))
  (let [id  (:id entry)
        doc (:doc entry)]
    (when-not (non-empty-string? id)
      (invalid "export obligation IDs must be non-empty strings" {:entry entry}))
    (when-not (map? doc)
      (invalid "export obligation :doc must be a map" {:id id}))
    (when-not (= id (:obligation/id doc))
      (invalid "export obligation :id and :obligation/id must agree"
               {:id id :obligation-id (:obligation/id doc)}))
    (when-some [s (:session-id entry)]
      (when-not (non-empty-string? s)
        (invalid "export obligation :session-id must be a string or nil"
                 {:id id :session-id s})))
    {:session-id (:session-id entry) :id id :doc doc}))

(defn- validate-blob-ref [entry]
  (when-not (map? entry)
    (invalid "export blob reference entries must be maps" {:entry entry}))
  (let [id  (:id entry)
        doc (:doc entry)]
    (when-not (non-empty-string? id)
      (invalid "export blob reference IDs must be non-empty strings" {:entry entry}))
    (when-not (map? doc)
      (invalid "export blob reference :doc must be a map" {:id id}))
    {:id id :doc doc}))

(defn- duplicate-keys [entries key-fn]
  (->> entries (map key-fn) frequencies (filter (fn [[_ n]] (> n 1))) (map first) vec))

(defn validate
  "Validate a complete export package and return a normalized copy.

  Throws `:STORAGE_INVALID_EXPORT` on any shape error.  Nothing is written."
  [export]
  (when-not (map? export)
    (invalid "an export package must be a map" {:export export}))
  (when-not (= format-version (:format-version export))
    (invalid "unsupported export format version"
             {:found (:format-version export) :expected format-version}))
  (when-not (true? (:complete? export))
    (invalid "restore requires a complete export (export every partition)"
             {:complete? (:complete? export)}))
  (when-not (non-empty-string? (:store-id export))
    (invalid "export :store-id must be a non-empty string"
             {:store-id (:store-id export)}))
  (when-not (map? (:partitions export))
    (invalid "export :partitions must be a map" {:partitions (:partitions export)}))
  (when (and (some? (:store-format-version export))
             (not= format-version (:store-format-version export)))
    (invalid "export store format version is not supported"
             {:found (:store-format-version export) :expected format-version}))
  (when (and (some? (:engine export))
             (not= :datalevin-kv (:engine export)))
    (invalid "export comes from another storage engine"
             {:found (:engine export) :expected :datalevin-kv}))
  (let [partitions (into (sorted-map)
                         (map (fn [[p data]]
                                (check-partition p)
                                (when-not (map? data)
                                  (invalid "export partition data must be a map"
                                           {:partition p}))
                                (let [version     (:version data)
                                      _           (when-not (and (integer? version) (not (neg? version)))
                                                    (invalid "export partition :version must be a non-negative integer"
                                                             {:partition p :version version}))
                                      docs        (mapv validate-doc (:docs data))
                                      obligations (mapv validate-obligation (:obligations data))
                                      blob-refs   (mapv validate-blob-ref (:blob-refs data))
                                      dup-docs    (duplicate-keys docs (juxt :kind :session-id :id))
                                      dup-obs     (duplicate-keys obligations :id)
                                      dup-blobs   (duplicate-keys blob-refs :id)]
                                  (when (seq dup-docs)
                                    (invalid "duplicate export documents"
                                             {:partition p :duplicates dup-docs}))
                                  (when (seq dup-obs)
                                    (invalid "duplicate export obligations"
                                             {:partition p :duplicates dup-obs}))
                                  (when (seq dup-blobs)
                                    (invalid "duplicate export blob references"
                                             {:partition p :duplicates dup-blobs}))
                                  [p {:version version
                                      :meta (or (:meta data) {})
                                      :last-commit (:last-commit data)
                                      :docs docs
                                      :obligations obligations
                                      :blob-refs blob-refs}])))
                         (:partitions export))]
    {:format-version format-version
     :complete? true
     :store-id (:store-id export)
     :store-format-version (:store-format-version export)
     :exported-at (:exported-at export)
     :partitions partitions}))

(defn restore-ops
  "Build one atomic op vector that replaces all current state with `export`.

  `existing-keys` is every key currently under `d/`, `o/`, `b/`, `r/`, `i/`
  and `m/p/`.  `ctx` supplies `:tx-version`, `:at`, `:migration-id`."
  [export existing-keys {:keys [tx-version at migration-id]}]
  (let [{:keys [store-id partitions]} export
        _        (when-not (non-empty-string? migration-id)
                   (invalid "restore needs a migration-id" {:migration-id migration-id}))
        receipt  {:migration-id migration-id
                  :digest (migration/digest {:restore (dissoc export :exported-at)})
                  :version tx-version
                  :partition nil
                  :at at
                  :reason :restore
                  :store-id store-id}
        del-ops  (mapv (fn [k] [:del k]) existing-keys)
        doc-ops  (mapcat (fn [[p {:keys [docs]}]]
                           (map (fn [{:keys [kind session-id id doc]}]
                                  [:put (keys/doc-key p session-id kind id)
                                   {:storage/kind kind
                                    :storage/id id
                                    :storage/partition p
                                    :storage/session-id session-id
                                    :storage/version tx-version
                                    :storage/updated-at at
                                    :doc doc}])
                                docs))
                         partitions)
        ob-ops   (mapcat (fn [[p {:keys [obligations]}]]
                           (map (fn [{:keys [session-id id doc]}]
                                  [:put (keys/obligation-key p session-id id)
                                   {:storage/kind :obligation
                                    :storage/id id
                                    :storage/partition p
                                    :storage/session-id session-id
                                    :storage/version tx-version
                                    :storage/updated-at at
                                    :doc doc}])
                                obligations))
                         partitions)
        blob-ops (mapcat (fn [[p {:keys [blob-refs]}]]
                           (map (fn [{:keys [id doc]}]
                                  [:put (keys/blob-ref-key p id)
                                   {:storage/kind :blob-ref
                                    :storage/id id
                                    :storage/partition p
                                    :storage/session-id nil
                                    :storage/version tx-version
                                    :storage/updated-at at
                                    :doc doc}])
                                blob-refs))
                         partitions)
        meta-ops (mapcat (fn [[p {:keys [version meta]}]]
                           ;; The restored partition's current commit is the
                           ;; restore itself; the exported receipt is history
                           ;; and is not resurrected.
                           (cond-> [[:put (keys/partition-meta-key p "version") version]
                                    [:put (keys/partition-meta-key p "last-commit") receipt]]
                             (seq meta)
                             (conj [:put (keys/partition-meta-key p "meta") meta])))
                         partitions)
        global-ops [[:put (keys/meta-key "store-version") tx-version]
                    [:put (keys/meta-key "last-commit") receipt]
                    [:put (keys/receipt-key tx-version migration-id) receipt]
                    [:put (keys/receipt-index-key migration-id)
                     (keys/receipt-key tx-version migration-id)]]]
    {:ops (vec (concat del-ops doc-ops ob-ops blob-ops meta-ops global-ops))
     :receipt receipt
     :counts {:partitions (count partitions)
              :documents (reduce + 0 (map (comp count :docs) (vals partitions)))
              :obligations (reduce + 0 (map (comp count :obligations) (vals partitions)))
              :blob-refs (reduce + 0 (map (comp count :blob-refs) (vals partitions)))
              :deleted-keys (count existing-keys)}}))
