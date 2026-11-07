(ns hc.server.storage.integrity
  "Prove that the current state is internally complete before work is dispatched.

  Root AGENTS.md §7.1: if the database cannot be opened, or cannot be proven
  complete, the system must stop side-effect dispatch and offer a recovery
  entry.  LMDB already checks its meta pages on open, and `open-store` checks
  the marker and the store metadata.  This namespace adds the remaining proof:
  every stored key and envelope must agree, every receipt must have its index,
  and every index entry must point to a receipt.

  The scan is linear in the current state.  It is opt-in for direct opens and
  on by default for `hc.server.recovery/recovery-entry`, which is the startup
  path.  A failure raises `:STORAGE_CORRUPT` with at most 20 examples; nothing
  is repaired or deleted."

  (:require [clojure.string :as str]
            [hc.server.storage.engine.datalevin :as engine]
            [hc.server.storage.error :as err]
            [hc.server.storage.keys :as keys]))

(def ^:private max-reported-errors 20)

(defn- problem! [errors k message data]
  (when (< (count @errors) max-reported-errors)
    (swap! errors conj (merge {:key k :problem message} data))))

(defn- valid-version? [x]
  (and (integer? x) (not (neg? x))))

(defn- check-envelope
  "Check the common storage envelope against the decoded key parts."
  [errors k v {:keys [kind id partition session-id] :as parts}]
  (when-not (map? v)
    (problem! errors k :not-an-envelope {:value-type (some-> v class str)}))
  (when (map? v)
    (when-not (= kind (:storage/kind v))
      (problem! errors k :kind-mismatch
                {:key-kind kind :envelope-kind (:storage/kind v)}))
    (when-not (= id (:storage/id v))
      (problem! errors k :id-mismatch
                {:key-id id :envelope-id (:storage/id v)}))
    (when-not (= partition (:storage/partition v))
      (problem! errors k :partition-mismatch
                {:key-partition partition :envelope-partition (:storage/partition v)}))
    (when-not (= session-id (:storage/session-id v))
      (problem! errors k :session-mismatch
                {:key-session session-id :envelope-session (:storage/session-id v)}))
    (when-not (valid-version? (:storage/version v))
      (problem! errors k :invalid-version {:version (:storage/version v)}))
    (when-not (map? (:doc v))
      (problem! errors k :document-not-a-map
                {:doc-type (some-> (:doc v) class str)}))))

(defn- check-document [errors [k v]]
  (let [parts (keys/doc-key-parts k)]
    (if-not parts
      (problem! errors k :malformed-document-key {})
      (check-envelope errors k v parts))))

(defn- check-obligation [errors [k v]]
  (let [parts (keys/obligation-key-parts k)]
    (if-not parts
      (problem! errors k :malformed-obligation-key {})
      (do
        (check-envelope errors k v (assoc parts :kind :obligation))
        (let [doc (:doc v)]
          (when (map? doc)
            (when-not (= (:id parts) (:obligation/id doc))
              (problem! errors k :obligation-id-mismatch
                        {:key-id (:id parts) :doc-id (:obligation/id doc)}))
            (when (and (contains? doc :open?)
                       (not (boolean? (:open? doc))))
              (problem! errors k :invalid-open-flag {:open? (:open? doc)}))))))))

(defn- check-blob-ref [errors [k v]]
  (let [parts (keys/blob-ref-key-parts k)]
    (if-not parts
      (problem! errors k :malformed-blob-ref-key {})
      (do
        (check-envelope errors k v (assoc parts :kind :blob-ref :session-id nil))
        (let [doc (:doc v)]
          (when (and (map? doc) (not= (:id parts) (:id doc)))
            (problem! errors k :blob-id-mismatch
                      {:key-id (:id parts) :doc-id (:id doc)})))))))

(def ^:private simple-meta-keys
  #{"m/format-version" "m/store-id" "m/created-at"
    "m/store-version" "m/last-commit"})

(defn- check-meta [errors [k v]]
  (cond
    (contains? simple-meta-keys k)
    (case k
      "m/format-version" (when-not (= 1 v)
                           (problem! errors k :unsupported-format {:value v}))
      "m/store-id" (when-not (and (string? v) (seq v))
                     (problem! errors k :invalid-store-id {:value v}))
      "m/created-at" (when-not (string? v)
                       (problem! errors k :invalid-created-at {:value v}))
      "m/store-version" (when-not (valid-version? v)
                          (problem! errors k :invalid-store-version {:value v}))
      "m/last-commit" (when-not (map? v)
                        (problem! errors k :invalid-last-commit {:value v}))
      nil)

    (str/starts-with? k "m/p/")
    (let [parts (str/split k #"/")]
      (if (not= 4 (count parts))
        (problem! errors k :malformed-partition-meta-key {})
        (let [partition (keys/decode-segment (nth parts 2))
              suffix (nth parts 3)]
          (when-not partition
            (problem! errors k :undecodable-partition {}))
          (case suffix
            "version" (when-not (valid-version? v)
                        (problem! errors k :invalid-partition-version {:value v}))
            "last-commit" (when-not (map? v)
                            (problem! errors k :invalid-partition-commit {:value v}))
            "meta" (when-not (map? v)
                     (problem! errors k :invalid-partition-meta {:value v}))
            (problem! errors k :unknown-partition-meta {:suffix suffix})))))

    :else
    (problem! errors k :unknown-meta-key {})))

(defn- check-receipt [errors [k v]]
  (if-not (map? v)
    (problem! errors k :receipt-not-a-map {})
    (let [{:keys [migration-id digest version]} v]
      (when-not (and (string? migration-id) (seq migration-id))
        (problem! errors k :receipt-without-migration-id {}))
      (when-not (and (string? digest) (= 64 (count digest)))
        (problem! errors k :receipt-without-digest {:digest digest}))
      (when-not (valid-version? version)
        (problem! errors k :receipt-without-version {:version version}))
      (when (and (string? migration-id) (valid-version? version))
        (let [expected (keys/receipt-key version migration-id)]
          (when-not (= expected k)
            (problem! errors k :receipt-key-mismatch {:expected expected})))))))

(defn- check-receipt-index [errors receipt-keys [k v]]
  (let [migration-id (when (str/starts-with? k "i/")
                       (keys/decode-segment (subs k 2)))]
    (cond
      (nil? migration-id)
      (problem! errors k :malformed-index-key {})

      (not (string? v))
      (problem! errors k :index-without-target {:value v})

      (not (contains? receipt-keys v))
      (problem! errors k :index-target-missing {:target v})

      :else
      (let [expected (keys/receipt-index-key migration-id)]
        (when-not (= expected k)
          (problem! errors k :index-key-mismatch {:expected expected}))))))

(defn verify
  "Scan every state record and raise `:STORAGE_CORRUPT` on the first class of
  inconsistency found.  Returns counts on success."
  [engine]
  (let [errors        (atom [])
        documents     (engine/scan engine "d/")
        obligations   (engine/scan engine "o/")
        blob-refs     (engine/scan engine "b/")
        meta          (engine/scan engine "m/")
        receipts      (engine/scan engine "r/")
        receipt-index (engine/scan engine "i/")
        receipt-keys  (into #{} (map first) receipts)
        index-targets (into #{} (map second) receipt-index)]
    (doseq [entry documents] (check-document errors entry))
    (doseq [entry obligations] (check-obligation errors entry))
    (doseq [entry blob-refs] (check-blob-ref errors entry))
    (doseq [entry meta] (check-meta errors entry))
    (doseq [entry receipts] (check-receipt errors entry))
    (doseq [entry receipt-index] (check-receipt-index errors receipt-keys entry))
    (doseq [[k _] receipts]
      (when-not (contains? index-targets k)
        (problem! errors k :receipt-without-index {})))
    (when-not (some (fn [[k _]] (= "m/format-version" k)) meta)
      (problem! errors "m/format-version" :missing-format-version {}))
    (when-not (some (fn [[k _]] (= "m/store-id" k)) meta)
      (problem! errors "m/store-id" :missing-store-id {}))
    (when-not (some (fn [[k _]] (= "m/store-version" k)) meta)
      (problem! errors "m/store-version" :missing-store-version {}))
    (let [record-partitions
          (into #{}
                (concat (keep (comp :partition keys/doc-key-parts) (map first documents))
                        (keep (comp :partition keys/obligation-key-parts) (map first obligations))
                        (keep (comp :partition keys/blob-ref-key-parts) (map first blob-refs))))
          meta-partitions
          (into #{}
                (keep (fn [[k _]]
                        (when (and (str/starts-with? k "m/p/")
                                   (str/ends-with? k "/version"))
                          (keys/partition-from-meta-key k))))
                meta)
          missing (remove meta-partitions record-partitions)]
      (doseq [p missing]
        (problem! errors (str "m/p/" (keys/encode-segment p) "/version")
                  :partition-without-version {:partition p}))
      (let [global (some (fn [[k v]] (when (= "m/store-version" k) v)) meta)
            ahead  (when (valid-version? global)
                     (keep (fn [[k v]]
                             (when (and (str/starts-with? k "m/p/")
                                        (str/ends-with? k "/version")
                                        (valid-version? v)
                                        (> v global))
                               {:key k :partition-version v :store-version global}))
                           meta))]
        (doseq [a ahead]
          (problem! errors (:key a) :partition-version-ahead a))))
    (when (seq @errors)
      (err/raise :STORAGE_CORRUPT
                 "the store failed its integrity check"
                 {:errors @errors
                  :error-count (count @errors)
                  :hint "Keep the directory for inspection and restore from a backup; nothing was repaired."}))
    {:ok? true
     :documents (count documents)
     :obligations (count obligations)
     :blob-refs (count blob-refs)
     :meta-keys (count meta)
     :receipts (count receipts)
     :receipt-index (count receipt-index)
     :entries (+ (count documents) (count obligations) (count blob-refs)
                 (count meta) (count receipts) (count receipt-index))}))
