(ns hc.server.storage.engine.datalevin-test
  "Store startup must distinguish an unwritable path from a held store lock.

  The operator action differs: a permission problem needs a permission or
  path change, while lock contention needs the other process to stop.  Both
  paths use real `mkdtemp` directories and `chmod`."
  (:require [clojure.test :refer [are deftest is testing]]
            [hc.server.storage :as storage]
            [hc.server.storage.layout :as layout])
  (:import (clojure.lang ExceptionInfo)
           (java.nio.channels FileChannel FileLock)
           (java.nio.file Files Path StandardOpenOption)
           (java.nio.file.attribute FileAttribute PosixFilePermissions)))

(defn- temp-store-dir
  "Create a fresh temp root with an empty `db` child and return [ROOT DB]."
  ^Path []
  (let [root (Files/createTempDirectory "hc-store-perm-"
                                        (make-array FileAttribute 0))
        dir (.resolve root "db")]
    (Files/createDirectories dir (make-array FileAttribute 0))
    [root dir]))

(defn- chmod!
  [^Path path value]
  (Files/setPosixFilePermissions
   path (PosixFilePermissions/fromString value)))

(defn- delete-tree!
  [^Path root]
  (doseq [^java.io.File file (reverse (file-seq (.toFile root)))]
    (try (chmod! (.toPath file) "rwxr-xr-x") (catch Exception _ nil))
    (try (.delete file) (catch Exception _ nil))))

(defn- open-result
  "Open DIR as a new store.  Return `{:store store}` or `{:error e}`."
  [^Path dir]
  (try
    {:store (storage/open-store {:path (str dir) :create? true})}
    (catch ExceptionInfo e
      {:error e})))

(defn- once!
  [result]
  (when-let [store (:store result)]
    (storage/close-store! store))
  (:error result))

(defn- read-only-error
  []
  (let [[root dir] (temp-store-dir)]
    (try
      (chmod! dir "r-xr-xr-x")
      (once! (open-result dir))
      (finally
        (try (chmod! dir "rwxr-xr-x") (catch Exception _ nil))
        (delete-tree! root)))))

(defn- held-lock-error
  []
  (let [[root dir] (temp-store-dir)
        lock-path (.resolve dir "store.lock")
        channel (FileChannel/open
                 lock-path
                 (into-array StandardOpenOption
                             [StandardOpenOption/CREATE
                              StandardOpenOption/WRITE]))
        held (.lock channel)]
    (try
      (once! (open-result dir))
      (finally
        (try (.release ^FileLock held) (catch Exception _ nil))
        (try (.close ^FileChannel channel) (catch Exception _ nil))
        (delete-tree! root)))))

(defn- read-only-data-file-error
  "Build a real store, then make its data file read-only.  The lock succeeds;
  the native environment open must still report an unwritable path."
  []
  (let [[root dir] (temp-store-dir)]
    (try
      (let [store (storage/open-store {:path (str dir) :create? true})]
        (storage/close-store! store))
      (chmod! (.resolve dir "data.mdb") "r--r--r--")
      (once! (open-result dir))
      (finally
        (try (chmod! (.resolve dir "data.mdb") "rw-r--r--")
             (catch Exception _ nil))
        (delete-tree! root)))))

(deftest read-only-store-path-is-reported-as-not-writable
  (let [error (read-only-error)
        code (storage/error-code error)
        path (get (ex-data error) :path)
        message (ex-message error)
        hint (layout/recovery-hint code path)]
    (is (some? error) "opening a store on a read-only path must fail")
    (is (true? (storage/expected-error? error)))
    (is (= :STORAGE_NOT_WRITABLE code))
    (is (string? path))
    (is (re-find (re-pattern (java.util.regex.Pattern/quote path)) message)
        "the message names the exact path")
    (is (re-find #"(?i)not writable|permissions|read-only" message))
    (is (re-find #"(?i)not writable|permissions|read-only" hint))
    (is (not (re-find #"(?i)another process" hint))
        "a permission failure must not mention another process")))

(deftest held-store-lock-is-reported-as-lock-contention
  (let [error (held-lock-error)
        code (storage/error-code error)
        path (get (ex-data error) :path)
        message (ex-message error)
        hint (layout/recovery-hint code path)]
    (is (some? error) "opening a held store lock must fail")
    (is (true? (storage/expected-error? error)))
    (is (= :STORAGE_LOCKED code))
    (is (re-find #"(?i)another process|store lock" message))
    (is (re-find #"(?i)another process" hint))
    (is (not (re-find #"(?i)not writable" hint))
        "lock contention must not be reported as a permission failure")))

(deftest read-only-data-file-is-not-opened-as-a-new-store
  (let [error (read-only-data-file-error)
        code (storage/error-code error)
        path (get (ex-data error) :path)
        message (ex-message error)
        hint (layout/recovery-hint code path)]
    (is (some? error))
    (is (= :STORAGE_NOT_WRITABLE code))
    (is (re-find (re-pattern (java.util.regex.Pattern/quote path)) message))
    (is (re-find #"(?i)not writable|permissions|read-only" hint))
    (is (not (re-find #"(?i)another process" hint)))))

(deftest permission-and-lock-contention-have-distinct-codes
  (let [read-only (storage/error-code (read-only-error))
        locked (storage/error-code (held-lock-error))]
    (are [actual expected] (= expected actual)
      read-only :STORAGE_NOT_WRITABLE
      locked :STORAGE_LOCKED)
    (are [left right] (not= left right)
      read-only locked
      locked read-only)))
