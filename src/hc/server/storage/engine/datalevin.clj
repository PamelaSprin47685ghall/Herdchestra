(ns hc.server.storage.engine.datalevin
  "Datalevin KV engine for the current-state store.

  One LMDB environment, one sub-database named `state`, one call per commit.
  A single `transact-kv` call is one LMDB write transaction, so documents,
  obligations, blob references, metadata and receipts land together or not at
  all.  The commit is followed by an explicit `sync` in `:sync` durability
  mode; only then does the adapter publish the new version.

  Root AGENTS.md §5.3: a Missionary cancellation must not interrupt a commit.
  Callers commit from the Owner thread.  As a second guard, the native call
  runs with the thread interrupt flag cleared and restores it afterwards, so
  a cancellation arriving mid-commit cannot tear the write transaction.

  The facade in `hc.server.storage` owns path recognition, locking, markers
  and versioning.  This namespace owns only the engine call surface.

  Datalevin 1.2.10 owns its process-global non-daemon scheduler and async
  executor.  `datalevin.binding.cpp/close-kv` removes the environment from
  `datalevin.lmdb/lmdb-dirs` and, when that set is empty, calls
  `shutdown-last-lmdb-executors!`.  This engine must therefore close every
  KV after `open-kv` returns a handle: on success, on a later open failure
  and on ordered shutdown.  The last close releases the global runtime.
  Another open non-spill LMDB keeps `lmdb-dirs` non-empty, so this close is
  not the last close.  A host that relies on the global pools without an open
  LMDB still loses them at this product's last close; that sharing is
  unsupported."

  (:require [datalevin.core :as d]
            [hc.server.storage.error :as err]
            [hc.server.storage.keys :as keys]))

(def dbi-name "state")

(def ^:private shutdown-close-grace-ms
  "How long Datalevin's JVM hook waits for the application's ordered close.

  The application stop timeout is ten seconds for the publisher plus the
  other components; thirty seconds leaves room for a slow gateway drain.  A
  direct store (no `expect-ordered-close!`) keeps Datalevin's immediate close
  behavior, so test and CLI JVMs do not wait on a leaked handle."
  30000)

(defn- shutdown-close-action
  "Return the close action Datalevin's JVM shutdown hook runs.

  JVM runs all shutdown hooks concurrently.  If the application has marked
  the store for an ordered close, this action waits for `closed` instead of
  closing the LMDB environment while the publisher is still reading.  The
  grace period is a last-resort fallback: if the application hook never
  closes, the library still closes the environment before the JVM exits."
  [kv closed ordered?]
  (fn []
    (if @ordered?
      (when-not (deref closed shutdown-close-grace-ms false)
        (try (d/close-kv kv) (catch Throwable _ nil)))
      (try (d/close-kv kv) (catch Throwable _ nil)))))

(defn- register-jvm-shutdown-close!
  "Install `shutdown-close-action` as Datalevin's higher-level close action.

  Returns true when the internal API is available.  The API only swaps an
  atom; it never calls `removeShutdownHook`, so it is safe during startup and
  during JVM shutdown.  A missing API leaves Datalevin's default hook in
  place; the pinned 1.2.10 version provides it, and the lifecycle test pins
  the registration."
  [kv closed ordered?]
  (if-let [register (try
                      (requiring-resolve
                       'datalevin.binding.cpp.lifecycle/register-shutdown-close!)
                      (catch Throwable _ nil))]
    (do (register kv (shutdown-close-action kv closed ordered?))
        true)
    false))

(defn- run-uninterrupted
  "Run `f` with the interrupt flag cleared, then restore it.  The native
  write transaction must finish even when a cancellation arrived."
  [f]
  (let [was (Thread/interrupted)]
    (try
      (f)
      (finally
        (when was
          (.interrupt (Thread/currentThread)))))))

(defn- open-failure
  "Classify one environment-open failure.  A permission problem and lock
  contention keep their own codes instead of collapsing into CORRUPT/OPEN."
  [path ^Throwable e code message]
  (cond
    (err/unwritable? e)
    (err/wrap :STORAGE_NOT_WRITABLE
              (str "the store path is not writable "
                   "(permissions or a read-only mount): " path)
              e
              {:path (str path)})

    (err/locked? e)
    (err/wrap :STORAGE_LOCKED
              "another process holds the store environment"
              e
              {:path (str path)})

    :else
    (err/wrap code message e {:path (str path)})))

(defn open!
  "Open the Datalevin environment at `path` and the `state` DBI.

  `opts`:
    :mapsize-mib  initial LMDB map size, default 128
    :max-readers  default 256
    :read-only?   refuse writes through this handle

  Datalevin KV needs a writable environment even to open its metadata DBI, so
  read-only is enforced by the adapter, not by LMDB's `:rdonly-env` flag.  The
  directory must still be writable by the process; the flag means `this handle
  never writes`, not `the filesystem is mounted read-only`.

  The caller has already proved that `path` is a store or may be initialized."
  [path {:keys [mapsize-mib max-readers read-only?]
         :or   {mapsize-mib 128 max-readers 256}}]
  (let [opts {:mapsize mapsize-mib :max-readers max-readers}
        closed (promise)
        ordered? (atom false)
        kv   (try
               (d/open-kv (str path) opts)
               (catch Throwable e
                 (throw (open-failure path e
                                      :STORAGE_OPEN_FAILED
                                      "cannot open the Datalevin environment"))))]
    (try
      (d/open-dbi kv dbi-name)
      ;; Touch the DBI: a corrupt or unreadable environment fails here, before
      ;; any caller can dispatch side effects.  `stat` reads B-tree metadata
      ;; (`:entries` is a page count), so opening a store with many large
      ;; documents never deserializes a value.
      (d/stat kv dbi-name)
      (let [registered? (register-jvm-shutdown-close! kv closed ordered?)]
        {:kv kv
         :dbi dbi-name
         :path (str path)
         :read-only? (boolean read-only?)
         :closed? (atom false)
         :closed-signal closed
         :ordered-close? ordered?
         :shutdown-close-registered? registered?})
      (catch clojure.lang.ExceptionInfo e
        (try (d/close-kv kv) (catch Throwable _ nil))
        (if (err/expected? e)
          (throw e)
          (throw (open-failure
                  path e
                  :STORAGE_CORRUPT
                  "the store environment opened but its state table is unreadable"))))
      (catch Throwable e
        (try (d/close-kv kv) (catch Throwable _ nil))
        (throw (open-failure
                path e
                :STORAGE_CORRUPT
                "the store environment opened but its state table is unreadable"))))))

(defn close! [engine]
  (when-let [closed? (:closed? engine)]
    (when (compare-and-set! closed? false true)
      (try
        (when-let [^datalevin.kv.KVLMDB kv (:kv engine)]
          (try
            (d/close-kv kv)
            (catch Throwable _ nil)))
        (finally
          ;; Release Datalevin's JVM shutdown hook from its wait.  Deliver even
          ;; when `close-kv` throws so the hook cannot wait for the full grace
          ;; period on an already-failed close.
          (when-let [signal (:closed-signal engine)]
            (deliver signal true))))))
  nil)

(defn expect-ordered-close!
  "Mark that the application is stopping in order.

  Datalevin's JVM shutdown hook then waits for `close!` instead of closing
  the native environment while the publisher is still reading.  A store
  opened directly (a test or CLI handle) stays unmarked and keeps
  Datalevin's immediate close behavior."
  [engine]
  (when-let [ordered? (:ordered-close? engine)]
    (reset! ordered? true))
  nil)

(defn closed? [engine]
  (or (nil? (:kv engine))
      (try (d/closed-kv? (:kv engine)) (catch Throwable _ true))))

(defn transact!
  "Apply `ops` in one atomic LMDB write transaction.

  `ops` is a vector of `[:put key value]` and `[:del key]`.  Values are
  arbitrary EDN data serialized by Datalevin's `:data` codec."
  [engine ops]
  (when (empty? ops)
    (err/raise :STORAGE_TX_FAILED "empty transaction" {}))
  (when (:read-only? engine)
    (err/raise :STORAGE_INVALID_OPTIONS "store is read-only" {:path (:path engine)}))
  (try
    (run-uninterrupted
     (fn []
       (d/transact-kv (:kv engine) dbi-name ops :string :data)))
    (catch clojure.lang.ExceptionInfo e
      (if (err/expected? e)
        (throw e)
        (throw (err/wrap :STORAGE_TX_FAILED
                         "the state transaction failed"
                         e
                         {:path (:path engine) :op-count (count ops)}))))
    (catch Throwable e
      (throw (err/wrap :STORAGE_TX_FAILED
                       "the state transaction failed"
                       e
                       {:path (:path engine) :op-count (count ops)})))))

(defn sync!
  "Force the environment to disk.  Returns nil."
  [engine]
  (try
    (run-uninterrupted
     (fn [] (d/sync (:kv engine) 1)))
    (catch Throwable e
      (throw (err/wrap :STORAGE_TX_FAILED
                       "cannot flush the store to disk"
                       e
                       {:path (:path engine)})))))

(defn get-value [engine k]
  (try
    (d/get-value (:kv engine) dbi-name k :string :data)
    (catch Throwable e
      (throw (err/wrap :STORAGE_READ_FAILED
                       "cannot read a state value"
                       e
                       {:path (:path engine) :key k})))))

(defn scan
  "Return `[[key value] ...]` for the closed key prefix `prefix`."
  [engine prefix]
  (try
    (d/get-range (:kv engine) dbi-name (keys/prefix-range prefix) :string :data)
    (catch Throwable e
      (throw (err/wrap :STORAGE_READ_FAILED
                       "cannot scan a state range"
                       e
                       {:path (:path engine) :prefix prefix})))))

(defn first-entry
  "Return the first `[key value]` under `prefix`, or nil."
  [engine prefix]
  (try
    (d/get-first (:kv engine) dbi-name (keys/prefix-range prefix) :string :data)
    (catch Throwable e
      (throw (err/wrap :STORAGE_READ_FAILED
                       "cannot read the first state entry"
                       e
                       {:path (:path engine) :prefix prefix})))))

(defn count-entries
  "Number of entries under `prefix`."
  [engine prefix]
  (try
    (d/range-count (:kv engine) dbi-name (keys/prefix-range prefix) :string)
    (catch Throwable e
      (throw (err/wrap :STORAGE_READ_FAILED
                       "cannot count a state range"
                       e
                       {:path (:path engine) :prefix prefix})))))

(defn total-entries [engine]
  (try
    (d/entries (:kv engine) dbi-name)
    (catch Throwable e
      (throw (err/wrap :STORAGE_READ_FAILED
                       "cannot count state entries"
                       e
                       {:path (:path engine)})))))

(defn dbi-names
  "Every sub-database name in the environment.  Used before initialization to
  refuse a foreign LMDB environment that merely happens to contain a
  `data.mdb`."
  [engine]
  (try
    (set (d/list-dbis (:kv engine)))
    (catch Throwable e
      (throw (err/wrap :STORAGE_READ_FAILED
                       "cannot list store sub-databases"
                       e
                       {:path (:path engine)})))))
