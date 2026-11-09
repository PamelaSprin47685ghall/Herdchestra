(ns hc.server.storage
  "Public facade for the embedded current-state store.

  Root AGENTS.md §7.1 defines five responsibilities, and this namespace owns
  exactly those:

    read-session-view        consistent session/board/relay view
    commit-migration!        one atomic domain migration, then a read barrier
    list-open-obligations    open call and report obligations
    manage-blob-refs!        blob reference add/release/list
    open-close-store!        stop intake, drain or hand off, then close

  plus the store lifecycle (`open-store`, `close-store!`, `store-info`) and the
  format-versioned export/restore pair used by backup.

  Design choices, all forced by the spec or by measurement:

  - Engine: Datalevin 1.2.10 KV (durable LMDB).  One sub-database, one
    `transact-kv` call per commit, so documents, obligations, blob references,
    metadata and the commit receipt share one atomic write transaction.
  - Read barrier: a commit returns only after `sync`; a view is returned only
    when the global store version is unchanged across its reads.
  - No destructive initialization: `layout/classify` decides what the path is
    before the native open.  A foreign directory, a marker without an
    environment, a format mismatch, a corrupt environment and a lock conflict
    all fail without writing, deleting or replacing anything.
  - Single writer: an OS file lock plus a non-interruptible commit lock.  The
    commit lock is never acquired interruptibly, so §5.3 cancellation cannot
    abandon a half-finished write transaction.

  Every expected failure is an `ExceptionInfo` with an `:error` keyword.
  `expected-error?`, `error-code` and `error-describe` classify it."

  (:require [clojure.string :as str]
            [hc.server.storage.engine.datalevin :as engine]
            [hc.server.storage.error :as err]
            [hc.server.storage.export :as export]
            [hc.server.storage.integrity :as integrity]
            [hc.server.storage.keys :as keys]
            [hc.server.storage.layout :as layout]
            [hc.server.storage.lock :as lock]
            [hc.server.storage.migration :as migration]
            [hc.server.storage.view :as view])
  (:import (java.util UUID)
           (java.util.concurrent.locks ReentrantLock
                                       ReentrantReadWriteLock)))

(def defaults
  {:create? false
   :create-parents? true
   :durability :sync
   :format-version 1
   :mapsize-mib 128
   :max-readers 256
   :lock? true
   :read-only? false
   :receipt-window 1024
   :default-partition "master"
   ;; Current-state documents are working state, not a content store.  The
   ;; Owner keeps the whole session state in one document, so the guard must
   ;; admit a large program source or a burst of call records while still
   ;; refusing unbounded growth.  Eight million characters admits the observed
   ;; 2-4 MB Owner states with headroom; compaction moves long sources and
   ;; receipts to blob references and keeps the steady state far below this.
   :max-doc-chars 8000000
   :verify-integrity? false
   :now-fn (fn [] (str (java.time.Instant/now)))})

(def ^:private allowed-options
  (into #{:path :now-fn} (keys defaults)))

(defrecord Store [engine path opts commit-lock read-lock state lock])

;; ---------------------------------------------------------------------------
;; Options and lifecycle helpers

(defn- invalid-options [message data]
  (err/raise :STORAGE_INVALID_OPTIONS message data))

(defn- check-options [opts]
  (when-not (map? opts)
    (invalid-options "open-store needs an option map" {:opts opts}))
  (let [unknown (seq (remove allowed-options (keys opts)))]
    (when unknown
      (invalid-options "unknown open-store options" {:unknown (vec unknown)})))
  (when-not (and (string? (:path opts)) (not (str/blank? (:path opts))))
    (invalid-options "open-store needs a non-empty :path" {:path (:path opts)}))
  (let [m (merge defaults opts)]
    (when-not (= 1 (:format-version m))
      (invalid-options "this build supports data format version 1 only"
                       {:format-version (:format-version m)}))
    (when-not (#{:sync :nosync} (:durability m))
      (invalid-options ":durability must be :sync or :nosync"
                       {:durability (:durability m)}))
    (doseq [k [:mapsize-mib :max-readers :receipt-window :max-doc-chars]]
      (when-not (and (integer? (get m k)) (pos? (long (get m k))))
        (invalid-options (str k " must be a positive integer") {k (get m k)})))
    (when-not (ifn? (:now-fn m))
      (invalid-options ":now-fn must be callable" {}))
    (when-not (and (string? (:default-partition m))
                   (not (str/blank? (:default-partition m))))
      (invalid-options ":default-partition must be a non-empty string" {}))
    m))

(defn- now-string [store-or-opts]
  (let [f (:now-fn (if (instance? Store store-or-opts) (:opts store-or-opts) store-or-opts))
        v (f)]
    (if (string? v) v (str v))))

(defn- closed? [store]
  (:closed? @(:state store)))

(defn store-open?
  "True when the store is open for use."
  [store]
  (and (instance? Store store) (not (closed? store))))

(defn store-closed? [store] (closed? store))

(defn expect-ordered-close!
  "Mark STORE as application-managed for JVM shutdown.

  Datalevin's own shutdown hook then waits for `close-store!` instead of
  closing the native environment under a live reader.  A direct store keeps
  the library's immediate close behavior."
  [store]
  (when (instance? Store store)
    (engine/expect-ordered-close! (:engine store)))
  nil)

(defn- ensure-open! [store]
  (when-not (instance? Store store)
    (invalid-options "not a store" {:value store}))
  (when (closed? store)
    (err/raise :STORAGE_CLOSED "the store is closed" {:path (str (:path store))})))

(defmacro ^:private with-commit-lock [store & body]
  `(let [^ReentrantLock lock# (:commit-lock ~store)]
     ;; Never interruptible: §5.3 forbids cancellation from tearing a commit.
     (.lock lock#)
     (try
       ~@body
       (finally (.unlock lock#)))))

(defmacro ^:private with-read-lock [store & body]
  `(let [store# ~store]
     (if-not (instance? Store store#)
       ;; Let `ensure-open!` report the documented invalid-store error.
       (do ~@body)
       (let [^ReentrantReadWriteLock lock# (:read-lock store#)
             read-lock# (.readLock lock#)]
         ;; A read that passed `ensure-open!` must not outlive the engine.
         ;; The close path takes the write lock after draining commits, so no
         ;; reader can still be inside a native call when the environment
         ;; closes.
         (.lock read-lock#)
         (try
           ~@body
           (finally (.unlock read-lock#)))))))

(defn- marker-for [store-id created-at]
  (layout/marker store-id created-at))

(defn- init-store!
  "Write the first metadata transaction and then the marker file."
  [engine path opts]
  (let [store-id (str (UUID/randomUUID))
        at       (now-string opts)
        fmt      (:format-version opts)
        allowed  #{"m/format-version" "m/store-id" "m/created-at"
                   "m/store-version" "m/last-commit"}
        foreign-dbis (remove #{"state"} (engine/dbi-names engine))
        user?    (some pos? [(count (engine/scan engine "d/"))
                             (count (engine/scan engine "o/"))
                             (count (engine/scan engine "b/"))
                             (count (engine/scan engine "r/"))
                             (count (engine/scan engine "i/"))])
        unknown  (seq (remove allowed (map first (engine/scan engine "m/"))))
        non-meta (seq (remove #(str/starts-with? (str (first %)) "m/")
                              (engine/scan engine "")))]
    ;; A crash between the first metadata transaction and the marker write
    ;; leaves only our own init keys.  Anything else is an unknown environment
    ;; and is never adopted.
    (when (or user? unknown (seq foreign-dbis) (seq non-meta))
      (err/raise :STORAGE_NOT_A_STORE
                 "the environment has entries but no store marker"
                 {:path (str path)
                  :unknown-meta (vec unknown)
                  :foreign-databases (vec foreign-dbis)
                  :foreign-keys (vec (take 5 (map first non-meta)))
                  :hint "refusing to adopt an unknown environment"}))
    (engine/transact! engine
                      [[:put (keys/meta-key "format-version") fmt]
                       [:put (keys/meta-key "store-id") store-id]
                       [:put (keys/meta-key "created-at") at]
                       [:put (keys/meta-key "store-version") 0]])
    (when (= :sync (:durability opts))
      (engine/sync! engine))
    (layout/write-marker! path (marker-for store-id at))
    {:store-id store-id :created-at at :format-version fmt}))

(defn- verify-store!
  "Prove that the marker and the metadata agree.  Any disagreement is
  corruption: nothing is rewritten."
  [engine path opts]
  (let [m        (layout/read-marker path)
        fmt      (engine/get-value engine (keys/meta-key "format-version"))
        store-id (engine/get-value engine (keys/meta-key "store-id"))
        version  (engine/get-value engine (keys/meta-key "store-version"))
        expected (:format-version opts)]
    (when-not m
      (err/raise :STORAGE_CORRUPT "store marker is missing" {:path (str path)}))
    (when-not (= fmt expected)
      (err/raise :STORAGE_FORMAT_MISMATCH
                 "the environment format version does not match the marker"
                 {:path (str path) :found fmt :expected expected}))
    (when-not (and (string? store-id) (= store-id (:store/id m)))
      (err/raise :STORAGE_CORRUPT
                 "the store ID in the marker and the environment differ"
                 {:path (str path)
                  :marker-id (:store/id m)
                  :environment-id store-id}))
    (when-not (and (integer? version) (not (neg? version)))
      (err/raise :STORAGE_CORRUPT
                 "the store version is missing or invalid"
                 {:path (str path) :version version}))
    {:store-id store-id
     :created-at (:store/created-at m)
     :format-version fmt}))

(defn- release-open-resources [engine-handle lock-handle]
  (when engine-handle (engine/close! engine-handle))
  (when lock-handle (lock/release! lock-handle)))

(defn open-store
  "Open or create a current-state store.

  `opts` keys:
    :path             required, the store directory
    :create?          permit initialization (default false)
    :create-parents?  create missing parent directories (default true)
    :durability       :sync (default) or :nosync
    :format-version   must be 1
    :mapsize-mib      initial LMDB map size (default 128)
    :max-readers      LMDB reader slots (default 256)
    :lock?            take the single-writer lock (default true)
    :read-only?       refuse writes through this handle (the directory must
                      still be writable; Datalevin needs a writable env)
    :receipt-window   idempotency receipts retained (default 1024)
    :default-partition default \"master\"
    :max-doc-chars    coarse document size guard (default 8000000).  A
                      document above it is refused with the hint to compact
                      to blob references; the store never silently truncates.
    :now-fn           () -> timestamp string

  Returns a `Store`.  Throws an expected `ExceptionInfo` when the path is not
  a store, is corrupt, uses another format or is locked.  It never creates or
  replaces a store after a failed open."
  [opts]
  (let [opts (check-options opts)
        p    (layout/path (:path opts))]
    (when (and (:read-only? opts) (:create? opts))
      (invalid-options ":read-only? and :create? are mutually exclusive" {}))
    (let [kind (layout/classify p)]
      (case kind
        :missing
        (if (:create? opts)
          (try
            (layout/create-directories! p (:create-parents? opts))
            (when-not (= :empty-dir (layout/classify p))
              (err/raise :STORAGE_OPEN_FAILED
                         "the store directory was created but is not empty"
                         {:path (str p)}))
            (catch clojure.lang.ExceptionInfo e
              (throw e))
            (catch Throwable e
              (throw
               (if (err/unwritable? e)
                 (err/wrap :STORAGE_NOT_WRITABLE
                           (str "the store path is not writable "
                                "(permissions or a read-only mount): " p)
                           e
                           {:path (str p)})
                 (err/wrap :STORAGE_OPEN_FAILED
                           "cannot create the store directory"
                           e
                           {:path (str p)})))))
          (err/raise :STORAGE_NOT_FOUND "no store exists at this path"
                     {:path (str p) :hint (layout/recovery-hint :STORAGE_NOT_FOUND p)}))

        :empty-dir
        (when-not (:create? opts)
          (err/raise :STORAGE_NOT_INITIALIZED
                     "the directory exists but holds no store"
                     {:path (str p) :hint (layout/recovery-hint :STORAGE_NOT_INITIALIZED p)}))

        :file
        (err/raise :STORAGE_NOT_A_STORE "the store path is not a directory"
                   {:path (str p) :hint (layout/recovery-hint :STORAGE_NOT_A_STORE p)})

        :foreign-dir
        (err/raise :STORAGE_NOT_A_STORE
                   "the directory contains data that is not a store"
                   {:path (str p) :hint (layout/recovery-hint :STORAGE_NOT_A_STORE p)})

        :marker-only
        (err/raise :STORAGE_CORRUPT
                   "the store marker exists but the environment is missing"
                   {:path (str p) :hint (layout/recovery-hint :STORAGE_CORRUPT p)})

        :uninitialized-env
        (when-not (:create? opts)
          (err/raise :STORAGE_NOT_INITIALIZED
                     "the environment was never initialized"
                     {:path (str p) :hint (layout/recovery-hint :STORAGE_NOT_INITIALIZED p)}))

        :store nil

        (err/raise :STORAGE_OPEN_FAILED "unrecognized store path state"
                   {:path (str p) :state kind}))
      (let [lock-handle (when (:lock? opts) (lock/acquire! p))
            engine-h    (try
                          (engine/open! p opts)
                          (catch Throwable e
                            (release-open-resources nil lock-handle)
                            (throw e)))
            initialized (if (contains? #{:missing :empty-dir :uninitialized-env} kind)
                          (try
                            (init-store! engine-h p opts)
                            (catch Throwable e
                              (release-open-resources engine-h lock-handle)
                              (throw e)))
                          (try
                            (verify-store! engine-h p opts)
                            (catch Throwable e
                              (release-open-resources engine-h lock-handle)
                              (throw e))))
            _           (when (:verify-integrity? opts)
                          (try
                            (integrity/verify engine-h)
                            (catch Throwable e
                              (release-open-resources engine-h lock-handle)
                              (throw e))))]
        (->Store engine-h
                 p
                 (assoc opts
                        :store-id (:store-id initialized)
                        :created-at (:created-at initialized))
                 (ReentrantLock.)
                 (ReentrantReadWriteLock.)
                 (atom {:accepting? true :closing? false :closed? false})
                 lock-handle)))))

(defn close-store!
  "Close the store.  Idempotent.  Returns `{:closed? true :path ...}`."
  [store]
  (when-not (instance? Store store)
    (invalid-options "not a store" {:value store}))
  (let [{:keys [closed?]} @(:state store)]
    (if closed?
      {:closed? true :path (str (:path store))}
      (do
        (swap! (:state store) assoc :accepting? false)
        ;; Wait for an in-flight commit, then for every reader that passed
        ;; `ensure-open!` before the close began.  Readers that arrive after
        ;; the write lock is held observe `:closed? true` and fail without
        ;; touching the native environment.
        (with-commit-lock store
          (let [^ReentrantReadWriteLock lock (:read-lock store)
                write-lock (.writeLock lock)]
            (.lock write-lock)
            (try
              (swap! (:state store) assoc :closed? true)
              (engine/close! (:engine store))
              (finally
                (.unlock write-lock)))))
        (lock/release! (:lock store))
        {:closed? true :path (str (:path store))}))))

;; ---------------------------------------------------------------------------
;; Reads

(defn- read-opts [store opts]
  (cond
    (string? opts)
    {:partition (:default-partition (:opts store)) :session-id opts
     :include-partition-scope? true
     :include-unknown-kinds? false
     :open-only? nil}

    (map? opts)
    {:partition (if (:all-partitions? opts)
                  (:partition opts)
                  (or (:partition opts) (:default-partition (:opts store))))
     :session-id (:session-id opts)
     :all-partitions? (:all-partitions? opts)
     :include-partition-scope? (if (false? (:include-partition-scope? opts))
                                 false
                                 true)
     :include-unknown-kinds? (boolean (:include-unknown-kinds? opts))
     :now-ms (:now-ms opts)
     :due-only? (:due-only? opts)
     :open-only? (:open-only? opts)}

    (nil? opts)
    {:partition (:default-partition (:opts store)) :session-id nil
     :include-partition-scope? true
     :include-unknown-kinds? false
     :open-only? nil}

    :else
    (invalid-options "read options must be a map or a session ID string"
                     {:opts opts})))

(defn read-session-view
  "Read a consistent session view after the read barrier.

  Accepts a session ID string (partition defaults to `:default-partition`) or
  `{:partition .. :session-id ..}`.  The scan options are passed through:

    :include-partition-scope?  default true; false skips the partition scope
    :include-unknown-kinds?    default false; true scans every kind, not only
                               `view/known-document-kinds`"
  [store opts]
  (with-read-lock store
    (ensure-open! store)
    (let [{:keys [partition session-id include-partition-scope?
                  include-unknown-kinds?]}
          (read-opts store opts)]
      (view/session-view (:engine store)
                         {:partition partition
                          :session-id session-id
                          :include-partition-scope? include-partition-scope?
                          :include-unknown-kinds? include-unknown-kinds?}))))

(defn read-document
  "Read one stored document by exact key after the read barrier.

  `opts`: `{:partition .. :session-id .. :kind .. :id ..}`.  Returns the
  unwrapped document (with `:storage/*` identity keys) or nil when absent.
  This is the point read for callers that need one document and must not pay
  the partition scan `read-session-view` performs."
  [store {:keys [partition session-id kind id] :as opts}]
  (with-read-lock store
    (ensure-open! store)
    (when-not (and (string? partition) (seq partition)
                   (keyword? kind)
                   (or (string? id) (keyword? id)))
      (invalid-options "read-document needs :partition :kind and :id"
                       {:opts opts}))
    (view/read-doc (:engine store)
                   {:partition partition :session-id session-id
                    :kind kind :id id})))

(defn read-documents
  "Read every document of one KIND in one session scope after the read
  barrier.

  `opts`: `{:partition .. :session-id .. :kind ..}`.  The kind is part of the
  key prefix, so this is the narrow scan for compact, index-like documents;
  session records and message bodies must still use `read-session-view`."
  [store {:keys [partition session-id kind] :as opts}]
  (with-read-lock store
    (ensure-open! store)
    (when-not (and (string? partition) (seq partition)
                   (keyword? kind))
      (invalid-options "read-documents needs :partition and :kind"
                       {:opts opts}))
    (view/read-documents (:engine store)
                         {:partition partition :session-id session-id
                          :kind kind})))

(defn- strip-storage-identity
  "Return DOC without the reserved `:storage/*` envelope keys.

  A point read returns the caller document with its identity attached.  A
  read-modify-write that stores the document again must not freeze this
  read's identity into the next envelope."
  [doc]
  (into {}
        (remove (fn [[k _]]
                  (and (keyword? k) (= "storage" (namespace k)))))
        doc))

(defn read-partition-documents
  "Read every document of one KIND in PARTITION, across all scopes.

  `opts`: `{:partition .. :kind ..}`.  This is a full partition scan and
  exists only for one-time derived-index rebuilds; steady-state callers use
  `read-document`, `read-documents` or `read-session-view`."
  [store {:keys [partition kind] :as opts}]
  (with-read-lock store
    (ensure-open! store)
    (when-not (and (string? partition) (seq partition)
                   (keyword? kind))
      (invalid-options "read-partition-documents needs :partition and :kind"
                       {:opts opts}))
    (view/read-partition-documents (:engine store)
                                   {:partition partition :kind kind})))

(defn read-partition-version
  "Return the committed version of PARTITION, or 0 when it has none."
  [store partition]
  (with-read-lock store
    (ensure-open! store)
    (or (engine/get-value (:engine store)
                          (keys/partition-meta-key partition "version"))
        0)))

(defn read-partition-head
  "Read `{:version n :state doc}` for one session's owner partition.

  `opts`: `{:partition .. :session-id ..}`.  This is the point read behind
  the owner-state corruption check; it does not scan the partition's
  obligations or board documents."
  [store {:keys [partition session-id] :as opts}]
  (with-read-lock store
    (ensure-open! store)
    (when-not (and (string? partition) (seq partition))
      (invalid-options "read-partition-head needs :partition" {:opts opts}))
    (view/partition-head (:engine store)
                         {:partition partition :session-id session-id})))

(defn list-open-obligations
  "List open obligations for a partition or session.

  `opts`: `{:partition .. :session-id .. :now-ms .. :due-only? ..}`.
  `:due-only?` keeps obligations whose `:due-at` is at or before `:now-ms`."
  [store opts]
  (with-read-lock store
    (ensure-open! store)
    (let [{:keys [partition session-id all-partitions? include-partition-scope?
                  now-ms due-only? open-only?]}
          (read-opts store opts)]
      (view/open-obligations (:engine store)
                             {:partition partition
                              :session-id session-id
                              :all-partitions? (boolean all-partitions?)
                              :include-partition-scope? (if (nil? include-partition-scope?)
                                                         true
                                                         include-partition-scope?)
                              :now-ms now-ms
                              :due-only? due-only?
                              :open-only? (if (nil? open-only?) true open-only?)}))))

;; ---------------------------------------------------------------------------
;; Writes

(defn- partition-version [engine partition]
  (or (engine/get-value engine (keys/partition-meta-key partition "version")) 0))

(defn- read-receipt [engine migration-id]
  (when-let [k (engine/get-value engine (keys/receipt-index-key migration-id))]
    (engine/get-value engine k)))

(defn- receipt-prune
  "Return the oldest `[key receipt]` when the receipt window is full."
  [engine receipt-window]
  (when (>= (engine/count-entries engine (keys/receipt-prefix)) receipt-window)
    (engine/first-entry engine (keys/receipt-prefix))))

(defn commit-migration!
  "Commit one domain migration atomically and return the post-commit view.

  Returns
    {:ok? true :version partition-version :store-version global-version
     :commit receipt :view view}

  The same `:migration-id` with the same payload replays the original receipt
  and returns the current view.  A different payload is
  `:STORAGE_MIGRATION_CONFLICT`.  `:expected-version` compares the partition
  version and fails with `:STORAGE_VERSION_CONFLICT`.

  Three control keys are not part of the migration payload or its digest:
  `:lightweight-return? true` and `:include-partition-scope? false` both skip
  the post-commit partition view; the result then has no `:view`, because a
  caller that needs no partition view must not pay a partition scan.
  `:include-unknown-kinds?` is passed to the view that is built, so a caller
  can see a kind outside `view/known-document-kinds`.  The default keeps the
  old result shape (partition scope scanned, unknown kinds hidden)."
  [store m]
  (ensure-open! store)
  (when-not (:accepting? @(:state store))
    (err/raise :STORAGE_CLOSING "the store is closing; new work is refused"
               {:path (str (:path store))}))
  (let [m? (map? m)
        lightweight? (and m?
                          (or (true? (:lightweight-return? m))
                              (false? (:include-partition-scope? m))))
        include-partition-scope? (if (and m?
                                          (false? (:include-partition-scope? m)))
                                   false
                                   true)
        include-unknown-kinds? (boolean (when m? (:include-unknown-kinds? m)))
        m (if m?
            (dissoc m :lightweight-return? :include-partition-scope?
                    :include-unknown-kinds?)
            m)
        m (migration/normalize
           (cond-> m
             (and (map? m) (nil? (:partition m)))
             (assoc :partition (:default-partition (:opts store)))
             (and (map? m) (nil? (:at m)))
             (assoc :at (now-string store)))
           (:opts store))]
    (with-commit-lock store
      (when-not (:accepting? @(:state store))
        (err/raise :STORAGE_CLOSING "the store is closing; new work is refused"
                   {:path (str (:path store))}))
      (let [eng         (:engine store)
            partition   (:partition m)
            session-id  (:session-id m)
            existing    (read-receipt eng (:migration-id m))]
        (if existing
          (if (= (:digest existing) (:digest m))
            (if lightweight?
              {:ok? true
               :replayed? true
               :version (partition-version eng partition)
               :store-version (or (engine/get-value eng
                                                    (keys/meta-key "store-version"))
                                  0)
               :commit existing}
              (let [v (view/session-view eng {:partition partition
                                              :session-id session-id
                                              :include-partition-scope?
                                              include-partition-scope?
                                              :include-unknown-kinds?
                                              include-unknown-kinds?})]
                {:ok? true
                 :replayed? true
                 :version (:version v)
                 :store-version (:store-version v)
                 :commit existing
                 :view v}))
            (err/raise :STORAGE_MIGRATION_CONFLICT
                       "the migration ID was used with a different payload"
                       {:migration-id (:migration-id m)
                        :committed-digest (:digest existing)
                        :incoming-digest (:digest m)}))
          (let [pv      (partition-version eng partition)
                expected (:expected-version m)]
            (when (and (some? expected) (not= expected pv))
              (err/raise :STORAGE_VERSION_CONFLICT
                         "the partition version does not match :expected-version"
                         {:partition partition
                          :expected expected
                          :actual pv}))
            (let [gv     (or (engine/get-value eng (keys/meta-key "store-version")) 0)
                  tx     (inc (long gv))
                  meta   (engine/get-value eng (keys/partition-meta-key partition "meta"))
                  {:keys [ops receipt]
                   plan-version :partition-version}
                  (migration/plan m {:tx-version tx
                                     :partition-version pv
                                     :partition-meta meta
                                     :prune-receipt (receipt-prune eng
                                                                   (:receipt-window (:opts store)))})]
              (engine/transact! eng ops)
              (when (= :sync (:durability (:opts store)))
                (engine/sync! eng))
              (if lightweight?
                {:ok? true
                 :version plan-version
                 :store-version tx
                 :commit receipt}
                (let [v (view/session-view eng {:partition partition
                                                :session-id session-id
                                                :include-partition-scope?
                                                include-partition-scope?
                                                :include-unknown-kinds?
                                                include-unknown-kinds?})]
                  {:ok? true
                   :version (:version v)
                   :store-version tx
                   :commit receipt
                   :view v})))))))))

(defn manage-blob-refs!
  "Add, release or list blob references.

  `opts`: `{:partition .. :op :add|:release|:list :refs [..] :migration-id ..}`.
  Add and release commit through `commit-migration!`, so a reference can be
  established in the same transaction as the document that uses it.  Callers
  that coordinate with the blob store hold `blob-store/with-store-lock` around
  the whole operation."
  [store {:keys [partition op refs migration-id] :as opts}]
  (ensure-open! store)
  (let [partition (or partition (:default-partition (:opts store)))
        mid       (or migration-id (str "blob-refs-" (UUID/randomUUID)))]
    (case op
      :list
      (with-read-lock store
        (ensure-open! store)
        (let [result (view/read-stable
                      (:engine store)
                      (fn []
                        {:refs (->> (engine/scan (:engine store)
                                                 (keys/partition-blob-ref-prefix partition))
                                    (map view/unwrap)
                                    (sort-by :storage/id)
                                    vec)
                         :version (partition-version (:engine store) partition)}))]
          {:ok? true
           :version (:version result)
           :refs (:refs result)}))

      :add
      (let [refs (vec refs)]
        (when (empty? refs)
          (invalid-options ":op :add needs at least one reference" {:opts opts}))
        (commit-migration! store
                           {:migration-id mid
                            :partition partition
                            :reason :manage-blob-refs
                            :blob-refs {:add refs}}))

      :release
      (let [refs (mapv (fn [r] (if (map? r) (:id r) r)) refs)]
        (when (empty? refs)
          (invalid-options ":op :release needs at least one reference" {:opts opts}))
        (commit-migration! store
                           {:migration-id mid
                            :partition partition
                            :reason :manage-blob-refs
                            :blob-refs {:release refs}}))

      (invalid-options "unknown blob reference operation"
                       {:op op :allowed #{:add :release :list}}))))

;; ---------------------------------------------------------------------------
;; Ordered shutdown

(defn open-close-store!
  "Stop intake, drain or hand off current obligations, then close.

  `opts`:
    :drain-fn    optional blocking thunk; returns truthy when drained
    :handoff-fn  optional thunk called with the store; returns handoff data
    :timeout-ms  drain deadline (default 30000)

  If the drain times out or throws, intake is re-enabled and the store stays
  open; the caller can retry or escalate.  The store closes only after the
  drain and handoff succeed."
  [store {:keys [drain-fn handoff-fn timeout-ms] :or {timeout-ms 30000}}]
  (ensure-open! store)
  (swap! (:state store) assoc :accepting? false :closing? true)
  (let [drained
        (if drain-fn
          (let [f (future (drain-fn))
                r (try
                    (deref f timeout-ms ::timeout)
                    (catch Throwable e
                      (swap! (:state store) assoc :accepting? true :closing? false)
                      (throw e)))]
            (if (= r ::timeout)
              (do (swap! (:state store) assoc :accepting? true :closing? false)
                  (future-cancel f)
                  (err/raise :STORAGE_DRAIN_TIMEOUT
                             "the store drain did not finish before the deadline"
                             {:timeout-ms timeout-ms :path (str (:path store))}))
              (if (or (nil? r) (false? r))
                (do (swap! (:state store) assoc :accepting? true :closing? false)
                    (err/raise :STORAGE_DRAIN_INCOMPLETE
                               "the store drain reported that obligations remain"
                               {:timeout-ms timeout-ms :path (str (:path store))}))
                r)))
          true)
        handed-off
        (if handoff-fn
          (try
            (handoff-fn store)
            (catch Throwable e
              (swap! (:state store) assoc :accepting? true :closing? false)
              (throw e)))
          nil)]
    (close-store! store)
    {:closed? true
     :drained? (boolean drained)
     :handed-off handed-off
     :path (str (:path store))}))

;; ---------------------------------------------------------------------------
;; Info and export/restore

(defn store-info
  "Return a plain description of the store.  Safe before and after close."
  [store]
  (when-not (instance? Store store)
    (invalid-options "not a store" {:value store}))
  (let [{:keys [path opts state]} store
        eng (:engine store)]
    (with-read-lock store
      (let [open? (not (:closed? @state))]
        (cond-> {:path (str path)
                 :engine layout/engine-id
                 :format-version (:format-version opts)
                 :store-id (:store-id opts)
                 :created-at (:created-at opts)
                 :read-only? (boolean (:read-only? opts))
                 :durability (:durability opts)
                 :default-partition (:default-partition opts)
                 :lock? (:lock? opts)}
          open? (assoc :version (or (engine/get-value eng (keys/meta-key "store-version")) 0)
                       :last-commit (engine/get-value eng (keys/meta-key "last-commit"))
                       :entries (engine/total-entries eng))
          (not open?) (assoc :version nil :closed? true))))))

(defn store-path [store] (str (:path store)))

(defn verify-integrity
  "Scan every current-state record and prove that keys, envelopes, receipts
  and indexes agree.  Returns counts; raises `:STORAGE_CORRUPT` otherwise.
  Nothing is repaired."
  [store]
  (with-read-lock store
    (ensure-open! store)
    (integrity/verify (:engine store))))

(defn export-state
  "Return a complete, format-versioned export package.

  `opts`: `{:partition ..}` to export one partition (marked incomplete), or
  nil/`{}` to export every partition (restorable).  `:sections` optionally
  selects `:docs`, `:obligations` and/or `:blob-refs`; a reference-only caller
  should pass `#{:blob-refs}` to avoid copying every document.

  A whole-store export excludes rebuildable derived partitions by default
  (`export/derived-partitions`, currently `\"search\"`).  Pass
  `:include-derived? true` to copy them for diagnostics or an integrity check.
  An explicit `:partition` always exports that partition, including a derived
  one, so a derived-index reader keeps working; the default exclusion applies
  to the whole-store package that backup and migration consume."
  [store opts]
  (with-read-lock store
    (ensure-open! store)
    (let [partition (:partition opts)
          exclude-partition? (when (and (nil? partition)
                                        (not (:include-derived? opts)))
                               export/derived-partition?)]
      (view/export-state
       (:engine store)
       {:partition partition
        :sections (:sections opts)
        :at (or (:at opts) (now-string store))
        :exclude-partition? exclude-partition?}))))

(def ^:private rebuilt-session-document-kinds
  "Documents derived from one session record.

  A backup can replace a session record while keeping a same-named derived
  document from the target store; the derived copy would then describe the
  previous session.  They are recomputed from the restored record, so a
  restore never carries them."
  #{:session-summary :session-ref-index})

(defn- drop-rebuilt-session-documents
  [export]
  (update export :partitions
          (fn [partitions]
            (into (empty partitions)
                  (map (fn [[p data]]
                         [p (update data :docs
                                    (fn [docs]
                                      (into []
                                            (remove #(contains?
                                                      rebuilt-session-document-kinds
                                                      (:kind %)))
                                            docs)))]))
                  partitions))))

(defn restore-state!
  "Replace the entire current state with a validated export package.

  Requires `:quiescent? true` from the caller, which must have stopped the
  Owner and every writer.  The adapter validates the whole package first, then
  replaces all documents, obligations, blob references, partition metadata and
  receipts in one atomic transaction.  A validation failure changes nothing.

  Session-derived documents (`:session-summary`, `:session-ref-index`) are
  never carried across the restore: a backup can replace a session record
  while keeping a same-named derived document, and the stale copy would
  disagree with the restored record.  The session adapter rebuilds them from
  the restored records.

  A package that still carries a rebuildable derived partition (an older
  whole-store export, `\"search\"` today) restores every current-state
  partition and drops the derived one.  The result's `:warnings` names each
  dropped partition: the startup/index owner rebuilds it from the restored
  current state."
  [store export {:keys [migration-id at quiescent?]}]
  (ensure-open! store)
  (when-not (:accepting? @(:state store))
    (err/raise :STORAGE_CLOSING "the store is closing; restore is refused"
               {:path (str (:path store))}))
  (when-not (true? quiescent?)
    (err/raise :STORAGE_RESTORE_REQUIRES_QUIESCENCE
               "restore requires :quiescent? true from the caller"
               {:path (str (:path store))}))
  (let [dropped-derived (when (map? (:partitions export))
                          (->> (keys (:partitions export))
                               (filter export/derived-partition?)
                               sort
                               vec))
        validated (-> (export/validate export)
                      drop-rebuilt-session-documents)]
    (with-commit-lock store
      (when-not (:accepting? @(:state store))
        (err/raise :STORAGE_CLOSING "the store is closing; restore is refused"
                   {:path (str (:path store))}))
      (let [eng      (:engine store)
            prefixes ["d/" "o/" "b/" "r/" "i/" "m/p/"]
            existing (mapcat (fn [prefix]
                               (map first (engine/scan eng prefix)))
                             prefixes)
            gv       (or (engine/get-value eng (keys/meta-key "store-version")) 0)
            ;; The restored partition versions come from the export and may be
            ;; higher than this store's counter.  The global version must stay
            ;; at least as high as every partition version.
            tx       (max (inc (long gv))
                          (reduce max 0 (map :version (vals (:partitions validated)))))
            mid      (or migration-id (str "restore-" (UUID/randomUUID)))
            {:keys [ops receipt counts]}
            (export/restore-ops validated (vec existing)
                                {:tx-version tx
                                 :at (or at (now-string store))
                                 :migration-id mid})]
        (engine/transact! eng ops)
        (when (= :sync (:durability (:opts store)))
          (engine/sync! eng))
        {:ok? true
         :version tx
         :commit receipt
         :restored counts
         :warnings
         (mapv (fn [partition]
                 {:code :STORAGE_DERIVED_PARTITION_DROPPED
                  :partition partition
                  :message
                  (str "dropped rebuildable derived partition " partition
                       "; it is rebuilt from the restored current state")})
               dropped-derived)}))))

;; ---------------------------------------------------------------------------
;; Temporary-session erasure

(defn- partition-export
  [store partition]
  (get-in (export-state store {:partition partition})
          [:partitions partition]))

(defn- session-scoped-doc?
  "True when one derived-partition document belongs to SESSION-ID.

  The rules are deliberately explicit.  A generic scan for a `:session-id`
  field would delete another session's data when a producer reused the key for
  a different meaning."
  [partition session-id {:keys [kind id doc]}]
  (cond
    (= partition "diagnostics")
    (= session-id (:session-id doc))

    (= partition "materials")
    (or (= session-id (get-in doc [:source :session-id]))
        (= session-id (:session-id doc))
        (and (contains? #{:materials-worldbook-session
                          :materials-instruction-session} kind)
             (= session-id id)))

    (= partition "search")
    (= session-id (:session-id doc))

    :else false))

(defn- erase-partition!
  [store partition session-id all?]
  (let [data (partition-export store partition)
        docs (if all?
               (:docs data)
               (filterv #(session-scoped-doc? partition session-id %) (:docs data)))
        obligations (if all? (:obligations data) [])
        blob-refs (if all? (:blob-refs data) [])
        commit! (fn [scope ds obs refs]
                  (when (or (seq ds) (seq obs) (seq refs))
                    (commit-migration!
                     store
                     (cond-> {:migration-id (str "session-erase-" (UUID/randomUUID))
                              :partition partition
                              :session-id scope
                              :reason :session-discard
                              :lightweight-return? true
                              :delete (mapv #(select-keys % [:kind :id]) ds)}
                       (seq obs)
                       (assoc :obligations {:remove (mapv :id obs)})

                       (seq refs)
                       (assoc :blob-refs {:release (mapv :id refs)})))))]
    (if all?
      ;; The owner partition is stored under the session scope.
      (commit! session-id docs obligations blob-refs)
      ;; Derived partitions can mix partition-scoped and session-scoped
      ;; records.  A migration has one scope, so group before deleting.
      (do
        (doseq [[scope ds] (group-by :session-id docs)]
          (commit! scope ds [] []))
        (doseq [[scope obs] (group-by :session-id obligations)]
          (commit! scope [] obs []))
        (commit! nil [] [] blob-refs)))
    {:partition partition
     :documents (count docs)
     :obligations (count obligations)
     :blob-refs (count blob-refs)}))

(defn- remove-session-index-entry!
  [store session-id]
  ;; Point-read the index.  Exporting the whole `sessions` partition here
  ;; would deserialize every session record and its messages just to remove
  ;; one id.
  (let [index (or (read-document store {:partition "sessions"
                                        :session-id nil
                                        :kind :session-index
                                        :id "sessions"})
                  (first (read-documents store {:partition "sessions"
                                                :session-id nil
                                                :kind :session-index})))
        ids (vec (or (:ids index) []))
        next-ids (vec (remove #{session-id} ids))]
    (when (and index (not= ids next-ids))
      (commit-migration!
       store
       {:migration-id (str "session-index-erase-" (UUID/randomUUID))
        :partition "sessions"
        :reason :session-discard
        :lightweight-return? true
        :put [{:kind :session-index
               :id (or (:id index) "sessions")
               :doc (assoc (strip-storage-identity index) :ids next-ids)}]}))
    {:updated? (and (some? index) (not= ids next-ids))
     :removed (count (remove #{session-id} ids))}))

(defn erase-session-derived!
  "Erase one session's owner partition and derived session-scoped records.

  Call this after `SessionPersistence.delete-session!` removed the session
  record, its command records, its index entry and its blob references.  The
  session record is the deletion authority; keeping that order lets the
  persistence compute the blob-reference diff from the stored session.

  This function removes:

    - every document, obligation and blob reference in `session/<id>`;
    - diagnostics records carrying the session ID;
    - materials memory candidates whose `:source :session-id` is the session,
      plus instruction/worldbook session-scoped records;
    - a defensive `search` partition when one exists.

  This build has no persisted full-text or vector index; global search is
  derived from session records, so removing the record and the owner
  partition is sufficient.  A future index must add its partition here.

  Temporary sessions never enter the deleted area.  If a tombstone exists,
  `delete-session!` removes it before this call.

  External services already contacted (model provider, web search, speech)
  cannot be revoked by a local discard.  The result states that explicitly."
  [store session-id]
  (ensure-open! store)
  (when-not (and (string? session-id) (not (str/blank? session-id)))
    (invalid-options "erase-session-derived! needs a non-blank session ID"
                     {:session-id session-id}))
  (let [owner-partition (str "session/" session-id)
        partitions (mapv (fn [partition]
                           (erase-partition! store partition session-id
                                             (= partition owner-partition)))
                         [owner-partition "diagnostics" "materials" "search"])
        index (remove-session-index-entry! store session-id)]
    {:ok? true
     :session-id session-id
     :partitions partitions
     :session-index index
     :external-effects
     [{:scope :external-services
       :revocable? false
       :note (str "Content already sent to model, search or speech services "
                  "is not revoked by this local discard.")}]}))

;; ---------------------------------------------------------------------------
;; Failure classification

(defn expected-error? [e] (err/expected? e))
(defn error-code [e] (err/code e))
(defn error-describe [e] (err/describe e))

(defn run-uninterrupted
  "Run F with the interrupt flag cleared, then restore it.

  A maintenance restore uses this around the commit and the close that reads
  the store version afterwards: a cancellation that arrives there must not
  tear the commit and must not skip the observation."
  [f]
  (let [was (Thread/interrupted)]
    (try
      (f)
      (finally
        (when was
          (.interrupt (Thread/currentThread)))))))
