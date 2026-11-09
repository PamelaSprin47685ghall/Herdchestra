(ns hc.server.storage.lock
  "Single-writer lock for one store root.

  Datalevin rejects a second open inside one JVM, but LMDB itself allows
  several processes to open one environment.  Herdchestra has one Owner per
  domain, so cross-process writers are a configuration error.  An OS file lock
  makes that error explicit:

      (with-store-lock root (fn [] ...))

  The lock file lives inside the store directory.  The lock is acquired before
  the native environment opens and released after it closes.  `tryLock` is
  non-blocking: a second writer gets `:STORAGE_LOCKED`, not a hang."

  (:require [hc.server.storage.error :as err])
  (:import (java.nio.channels FileChannel FileLock OverlappingFileLockException)
           (java.nio.file Files LinkOption Path StandardOpenOption)))

(defn- lock-path ^Path [^Path root]
  (.resolve root "store.lock"))

(defn- rethrow-lock-failure
  "Rethrow E with the operator-facing cause.  An unwritable path must never
  be reported as another process holding the lock."
  [^Path f ^Throwable e lock-message]
  (if (err/unwritable? e)
    (throw (err/wrap :STORAGE_NOT_WRITABLE
                     (str "the store lock path is not writable "
                          "(permissions or a read-only mount): " f)
                     e
                     {:path (str f)}))
    (throw (err/wrap :STORAGE_LOCKED lock-message e {:path (str f)}))))

(defn acquire!
  "Acquire the exclusive lock for `root`.  Returns a handle for `release!`.
  Throws `:STORAGE_NOT_WRITABLE` when the lock path cannot be written and
  `:STORAGE_LOCKED` when another holder exists."
  [^Path root]
  (let [f  (lock-path root)
        ch (try
             (FileChannel/open f (into-array StandardOpenOption
                                             [StandardOpenOption/CREATE
                                              StandardOpenOption/WRITE]))
             (catch Throwable e
               (rethrow-lock-failure f e "cannot open the store lock file")))
        lk (try
             (.tryLock ch)
             (catch OverlappingFileLockException _ nil)
             (catch Throwable e
               (.close ch)
               (rethrow-lock-failure f e "cannot acquire the store lock")))]
    (if lk
      {:path (str f) :channel ch :lock lk}
      (do
        (.close ch)
        (err/raise :STORAGE_LOCKED
                   "another process or store holds this store lock"
                   {:path (str f)})))))

(defn release!
  "Release a handle from `acquire!`.  Idempotent."
  [{:keys [^FileChannel channel ^FileLock lock]}]
  (when lock
    (try (.release lock) (catch Throwable _ nil)))
  (when channel
    (try (.close channel) (catch Throwable _ nil)))
  nil)

(defn held?
  "True when some process holds the store lock (best-effort diagnostic)."
  [^Path root]
  (let [f (lock-path root)]
    (and (Files/exists f (make-array LinkOption 0))
         (try
           (with-open [ch (FileChannel/open f (into-array StandardOpenOption
                                                           [StandardOpenOption/WRITE]))]
             (if-let [l (.tryLock ch)]
               (do (.release l) false)
               true))
           (catch OverlappingFileLockException _ true)
           (catch Throwable _ false)))))
