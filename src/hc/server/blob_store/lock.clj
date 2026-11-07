(ns hc.server.blob-store.lock
  "One serialization point for reference commits and cleanup.

  Cleanup must observe a stable reference set while it decides what to delete.
  The database transaction that adds or removes a reference therefore shares
  this lock with cleanup.  The lock has two layers:

  - a per-root JVM `ReentrantLock`, so threads in one process block instead of
    colliding on an overlapping file lock;
  - an OS file lock on `<root>/.blob-store.lock`, so another process using the
    same root also serializes.

  Callers that only stage or finalize a blob do not need the lock: content
  addressing and atomic rename already make those operations independent.
  The lock is for the reference decision, not for the bytes."
  (:require [hc.server.blob-store.error :as err]
            [hc.server.blob-store.layout :as layout])
  (:import (java.nio.channels FileChannel FileLock OverlappingFileLockException)
           (java.nio.file OpenOption StandardOpenOption)
           (java.util.concurrent.locks ReentrantLock)))

(set! *warn-on-reflection* true)

(defonce ^:private locks
  (atom {}))

(defn- jvm-lock
  ^ReentrantLock [root]
  (let [key (str (layout/root-path root))]
    (or (get @locks key)
        (get (swap! locks update key #(or % (ReentrantLock.))) key))))

(defn with-store-lock
  "Run `f` while holding the root's reference/cleanup lock.

  Returns `f`'s value.  `f` must not call `with-store-lock` again for the same
  root from the same thread: the file-lock layer is not reentrant.  Reference
  lookups run inside the lock, so they must not acquire it."
  [root f]
  (layout/ensure-root! root)
  (let [lock (jvm-lock root)]
    (.lock lock)
    (try
      (with-open [channel (FileChannel/open
                           (layout/lock-file root)
                           (into-array OpenOption [StandardOpenOption/CREATE
                                                   StandardOpenOption/WRITE]))]
        (let [file-lock (try
                          (.lock channel)
                          (catch OverlappingFileLockException e
                            (err/fail! :BLOB_STORE_BUSY
                                       "Blob-store lock is already held by this process"
                                       {:root (str (layout/root-path root))}
                                       e)))]
          (try
            (f)
            (finally
              (when (.isValid ^FileLock file-lock)
                (.release ^FileLock file-lock))))))
      (finally
        (.unlock lock)))))
