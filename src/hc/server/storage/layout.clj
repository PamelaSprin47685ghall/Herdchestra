(ns hc.server.storage.layout
  "Filesystem layout, store recognition and the non-destructive marker.

  A store directory contains the Datalevin environment plus one marker file:

      <root>/data.mdb, lock.mdb, VERSION     Datalevin LMDB environment
      <root>/store.edn                       Herdchestra store marker
      <root>/store.lock                      single-writer lock file

  `classify` decides what an existing path is BEFORE any native open.  This
  is the poka-yoke that keeps root AGENTS.md §7.1's rule: a failed open must
  never silently create a same-named empty database.  `datalevin.core/open-kv`
  happily writes into a missing or foreign directory, so this namespace is the
  only place allowed to decide that opening is safe.

  The marker is written last, after the first metadata transaction commits.
  A crash between environment creation and marker write therefore leaves a
  recognizable, empty environment that `:create? true` may finish initializing
  and `:create? false` refuses to adopt."

  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [hc.server.storage.error :as err])
  (:import (java.nio.channels FileChannel)
           (java.nio.file Files LinkOption Path StandardCopyOption StandardOpenOption)
           (java.nio.file.attribute FileAttribute)))

(def marker-name "store.edn")
(def lock-name "store.lock")
(def datalevin-data-name "data.mdb")
(def datalevin-lock-name "lock.mdb")
(def datalevin-version-name "VERSION")
(def marker-format-version 1)
(def engine-id :datalevin-kv)

(defn path
  "Absolute normalized `java.nio.file.Path` for `p`."
  ^Path [p]
  (-> (io/file (str p)) .toPath .toAbsolutePath .normalize))

(defn exists? [^Path p]
  (Files/exists p (make-array LinkOption 0)))

(defn directory? [^Path p]
  (Files/isDirectory p (make-array LinkOption 0)))

(defn- names-in [^Path dir]
  (with-open [stream (Files/list dir)]
    (into #{} (map #(.toString (.getFileName ^Path %))) (iterator-seq (.iterator stream)))))

(defn classify
  "Classify `p` without writing anything.

  Returns one of
    :missing           path does not exist
    :file              path is not a directory
    :empty-dir         existing empty directory
    :store             marker and Datalevin environment are both present
    :uninitialized-env Datalevin environment without marker (interrupted init)
    :marker-only       marker without environment
    :foreign-dir       non-empty directory that is not a store"
  [^Path p]
  (cond
    (not (exists? p)) :missing
    (not (directory? p)) :file
    :else
    (let [names   (names-in p)
          env?    (contains? names datalevin-data-name)
          marker? (contains? names marker-name)
          ;; Our own lock and temp files are not foreign data.  A crash after
          ;; the directory and lock are created but before the environment
          ;; exists must stay recoverable with :create? true.
          own?    #{lock-name (str marker-name ".tmp")}
          foreign (remove own? names)]
      (cond
        (and env? marker?) :store
        (and env? (not marker?)) :uninitialized-env
        (and marker? (not env?)) :marker-only
        (empty? foreign) :empty-dir
        :else :foreign-dir))))

(defn create-directories!
  "Create `p` and, when `parents?` is true, its missing parents."
  [^Path p parents?]
  (if parents?
    (Files/createDirectories p (make-array FileAttribute 0))
    (Files/createDirectory p (make-array FileAttribute 0)))
  p)

(defn read-marker
  "Read and validate the marker.  Returns the marker map, nil when absent.
  A malformed marker is `:STORAGE_CORRUPT`; it is never rewritten."
  [^Path root]
  (let [f (.resolve root marker-name)]
    (when (exists? f)
      (try
        (let [m (edn/read-string {:readers {} :default tagged-literal}
                                 (slurp (io/file (.toFile f))))]
          (when-not (map? m)
            (err/raise :STORAGE_CORRUPT "store marker is not a map"
                       {:path (str f)}))
          (when-not (= marker-format-version (:store/format-version m))
            (err/raise :STORAGE_FORMAT_MISMATCH
                       "store marker format version is not supported"
                       {:path (str f)
                        :found (:store/format-version m)
                        :expected marker-format-version}))
          m)
        (catch clojure.lang.ExceptionInfo e
          (throw e))
        (catch Throwable e
          (throw (err/wrap :STORAGE_CORRUPT
                           "cannot read the store marker"
                           e
                           {:path (str f)})))))))

(defn marker
  "Build a fresh marker map."
  [store-id created-at]
  {:store/format-version marker-format-version
   :store/engine engine-id
   :store/id store-id
   :store/created-at created-at
   :store/created-by "hc.server.storage"})

(defn- fsync-file! [^Path f]
  (with-open [ch (FileChannel/open f (into-array StandardOpenOption
                                                 [StandardOpenOption/WRITE]))]
    (.force ch true)))

(defn- fsync-dir!
  "Best-effort directory fsync.  Some filesystems and platforms reject it;
  the marker file itself is always fsynced before the rename."
  [^Path dir]
  (try
    (with-open [ch (FileChannel/open dir (into-array StandardOpenOption
                                                     [StandardOpenOption/READ]))]
      (.force ch true))
    (catch Throwable _ nil)))

(defn write-marker!
  "Atomically write `m` as `<root>/store.edn`: temp file, fsync, atomic rename,
  directory fsync.  A reader sees either the old marker or the new one."
  [^Path root m]
  (let [target (.resolve root marker-name)
        temp   (.resolve root (str marker-name ".tmp"))]
    (binding [*print-namespace-maps* false]
      (spit (io/file (.toFile temp)) (pr-str m)))
    (fsync-file! temp)
    (try
      (Files/move temp target
                  (into-array StandardCopyOption
                              [StandardCopyOption/ATOMIC_MOVE
                               StandardCopyOption/REPLACE_EXISTING]))
      (catch java.nio.file.AtomicMoveNotSupportedException _
        (Files/move temp target
                    (into-array StandardCopyOption
                                [StandardCopyOption/REPLACE_EXISTING]))))
    (fsync-dir! root)
    m))

(defn recovery-hint
  "A short, non-destructive operator hint for a blocked open."
  [code ^Path p]
  (case code
    :STORAGE_NOT_FOUND
    (str "No store exists at " p ". Pass :create? true only when a new store is intended.")
    :STORAGE_NOT_A_STORE
    (str "The directory " p " contains data that is not a Herdchestra store. "
         "Move it aside or choose another :path; nothing was changed.")
    :STORAGE_NOT_INITIALIZED
    (str "The directory " p " holds an uninitialized store environment. "
         "Open it with :create? true to finish initialization, or inspect it.")
    :STORAGE_CORRUPT
    (str "The store at " p " is incomplete or corrupt. "
         "Keep the directory for inspection and restore from a backup; it was not replaced.")
    :STORAGE_FORMAT_MISMATCH
    (str "The store at " p " uses a different data format. "
         "Use a matching build or an explicit migration; nothing was changed.")
    :STORAGE_OPEN_FAILED
    (str "The store at " p " could not be opened. "
         "Keep it for inspection and restore from a backup; it was not replaced.")
    :STORAGE_NOT_WRITABLE
    (str "The store path at " p " is not writable (permissions or a read-only mount). "
         "Change permissions or choose a writable directory; nothing was changed.")
    :STORAGE_LOCKED
    (str "Another process holds the store lock at " p ". "
         "Stop the other process; this process did not open the store.")
    (str "The store at " p " is unavailable. Nothing was changed.")))
