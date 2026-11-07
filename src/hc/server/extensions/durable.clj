(ns hc.server.extensions.durable
  "Durable extension catalog.

  The catalog is one EDN document written by a same-directory temporary file,
  fsynced, then atomically renamed.  A version counter is stored beside the
  catalog so a stale writer gets `:EXTENSION_CATALOG_CONFLICT` instead of
  silently replacing newer activation state.

  This file is self-contained so skill and MCP configuration can be durable
  before the main current-state store is selected or while it is unavailable.
  It contains credentials for MCP headers/environment; the file is created with
  owner-only permissions where the platform supports POSIX modes."
  (:require [clojure.edn :as edn]
            [hc.server.extensions.catalog :as catalog]
            [hc.server.extensions.error :as error])
  (:import (java.nio.charset StandardCharsets)
           (java.nio.file AtomicMoveNotSupportedException Files LinkOption
                          OpenOption Path StandardCopyOption StandardOpenOption)
           (java.nio.file.attribute FileAttribute PosixFilePermission
                                   PosixFilePermissions)))

(def format-version 1)
(def file-name "catalog.edn")

(defrecord CatalogStore [path lock])

(defn- fail
  [code message details]
  {:ok? false :error {:code code :message message :details details}})

(defn open
  "Open or create a catalog directory.  The directory is created lazily on the
  first save; opening does not write."
  [root]
  (let [path (Path/of (str root) (make-array String 0))]
    (->CatalogStore path (Object.))))

(defn catalog-file
  [repository]
  (.resolve ^Path (:path repository) file-name))

(defn- read-document
  [^Path file]
  (try
    (let [text (Files/readString file StandardCharsets/UTF_8)
          value (edn/read-string {:readers {}
                                  :default (fn [tag _]
                                             (error/fail! :EXTENSION_CATALOG_INVALID
                                                          "tagged value in catalog"
                                                          {:tag tag}))}
                                 text)]
      (when-not (map? value)
        (error/fail! :EXTENSION_CATALOG_INVALID
                     "catalog document must be a map" {}))
      {:ok? true :value value})
    (catch java.nio.file.NoSuchFileException _
      {:ok? true :value nil})
    (catch clojure.lang.ExceptionInfo t
      (if (error/expected? t)
        {:ok? false :error (error/error-map t)}
        {:ok? false :error {:code :EXTENSION_CATALOG_INVALID
                            :message (or (ex-message t) "catalog could not be read")}}))
    (catch Exception t
      {:ok? false :error {:code :EXTENSION_CATALOG_INVALID
                          :message (or (ex-message t) "catalog could not be read")}})))

(defn load!
  "Read the current catalog.  A missing file is an empty version-0 catalog."
  [repository]
  (let [result (read-document (catalog-file repository))]
    (if-not (:ok? result)
      result
      (let [doc (:value result)]
        (if (nil? doc)
          {:ok? true :catalog (catalog/empty-catalog) :version 0}
          (let [version (:version doc)]
            (if-not (and (integer? version) (not (neg? version)))
              (fail :EXTENSION_CATALOG_INVALID
                    "catalog version must be a non-negative integer"
                    {:version version})
              (try
                {:ok? true
                 :catalog (catalog/ensure (:catalog doc))
                 :version (long version)
                 :saved-at (:saved-at doc)}
                (catch clojure.lang.ExceptionInfo t
                  (if (error/expected? t)
                    {:ok? false :error (error/error-map t)}
                    (fail :EXTENSION_CATALOG_INVALID
                          "catalog document has an invalid shape"
                          {:reason (or (ex-message t) (str t))})))
                (catch Exception t
                  (fail :EXTENSION_CATALOG_INVALID
                        "catalog document has an invalid shape"
                        {:reason (or (ex-message t) (str t))}))))))))))

(defn- owner-only-permissions!
  [^Path path]
  (try
    (Files/setPosixFilePermissions
     path
     (PosixFilePermissions/fromString "rw-------"))
    (catch UnsupportedOperationException _ nil)
    (catch Exception _ nil)))

(defn- fsync-directory!
  [^Path dir]
  (try
    (with-open [channel (java.nio.channels.FileChannel/open
                         dir
                         (into-array OpenOption [StandardOpenOption/READ]))]
      (.force channel true))
    (catch Exception _ nil)))

(defn- write-document!
  [^Path file doc]
  (let [dir (.getParent file)
        _ (Files/createDirectories dir (make-array FileAttribute 0))
        temp (Files/createTempFile dir ".catalog-" ".tmp"
                                   (make-array FileAttribute 0))
        text (str (pr-str doc) "\n")]
    (try
      (owner-only-permissions! temp)
      (with-open [channel (java.nio.channels.FileChannel/open
                           temp
                           (into-array OpenOption [StandardOpenOption/WRITE
                                                   StandardOpenOption/TRUNCATE_EXISTING]))]
        (.write channel (java.nio.ByteBuffer/wrap (.getBytes text StandardCharsets/UTF_8)))
        (.force channel true))
      (try
        (Files/move temp file (into-array java.nio.file.CopyOption
                                          [StandardCopyOption/ATOMIC_MOVE
                                           StandardCopyOption/REPLACE_EXISTING]))
        (catch AtomicMoveNotSupportedException _
          (Files/move temp file (into-array java.nio.file.CopyOption
                                            [StandardCopyOption/REPLACE_EXISTING]))))
      (owner-only-permissions! file)
      (fsync-directory! dir)
      {:ok? true}
      (finally
        (try (Files/deleteIfExists temp) (catch Exception _ nil))))))

(defn save!
  "Write `catalog` if the on-disk version still equals `expected-version`."
  [repository catalog expected-version]
  (locking (:lock repository)
    (let [loaded (load! repository)]
      (if-not (:ok? loaded)
        loaded
        (if (not= (long (or expected-version 0)) (long (:version loaded)))
          (fail :EXTENSION_CATALOG_CONFLICT
                "catalog was changed by another writer"
                {:expected expected-version :actual (:version loaded)})
          (let [next-version (inc (long (:version loaded)))
                doc {:format-version format-version
                     :version next-version
                     :catalog catalog
                     :saved-at (str (java.time.Instant/now))}]
            (try
              (let [written (write-document! (catalog-file repository) doc)]
                (if (:ok? written)
                  {:ok? true :catalog catalog :version next-version}
                  written))
              (catch Exception t
                (fail :EXTENSION_CATALOG_IO
                      "catalog could not be written"
                      {:message (or (ex-message t) (str t))})))))))))

(defn update!
  "Apply pure function `f` to the current catalog and save it.

  `f` receives the catalog and returns `{:ok? true :catalog next}` or a
  refusal.  On a version conflict the read and `f` are retried; `f` must be
  pure because it may run more than once."
  ([repository f] (update! repository f {}))
  ([repository f {:keys [retries] :or {retries 4}}]
   (locking (:lock repository)
     (loop [attempt 0]
       (let [loaded (load! repository)]
         (if-not (:ok? loaded)
           loaded
           (let [applied (try
                           (f (:catalog loaded))
                           (catch clojure.lang.ExceptionInfo t
                             (if (error/expected? t)
                               {:ok? false :error (error/error-map t)}
                               (throw t))))]
             (if-not (:ok? applied)
               (assoc applied :catalog (:catalog loaded) :version (:version loaded))
               (let [saved (save! repository (:catalog applied) (:version loaded))]
                 (cond
                   (:ok? saved) saved
                   (and (= :EXTENSION_CATALOG_CONFLICT
                           (get-in saved [:error :code]))
                        (< attempt retries))
                   (recur (inc attempt))
                   :else
                   (assoc saved :catalog (:catalog loaded)
                          :version (:version loaded))))))))))))
