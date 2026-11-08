(ns hc.server.backup.file-target
  "A local-directory TargetPort for backup files.

  Objects live at `<root>/objects/<id>.hcb`.  A write goes to `<root>/tmp/`,
  is fsynced, and is published with one atomic rename.  An existing object is
  never overwritten: identical bytes deduplicate, different bytes conflict.

  Listing reads only the container header, so a remote-list style screen does
  not decrypt every backup or read its payload."
  (:require [clojure.string :as str]
            [hc.server.backup.codec :as codec]
            [hc.server.backup.error :as err]
            [hc.server.backup.package :as package]
            [hc.server.backup.port :as port])
  (:import (java.io FileOutputStream InputStream)
           (java.nio.channels FileChannel)
           (java.nio.file AtomicMoveNotSupportedException CopyOption Files
                          LinkOption OpenOption Path StandardCopyOption
                          StandardOpenOption)
           (java.nio.file.attribute BasicFileAttributes FileAttribute)
           (java.time Instant)
           (java.util.regex Pattern)))

(defrecord FileTarget [root])

(defn store
  "Create a local backup target rooted at `root`."
  [root]
  (->FileTarget (str root)))

(def ^:private id-pattern
  (Pattern/compile "^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$"))

(defn valid-id?
  [id]
  (and (string? id)
       (not (str/blank? id))
       (boolean (re-matches id-pattern id))))

(defn- assert-id!
  [id]
  (when-not (valid-id? id)
    (err/fail! :BACKUP_ID_INVALID
               "Backup ID is not a safe file name"
               {:id id}))
  id)

(defn root-path
  ^Path [root]
  (Path/of (str root) (make-array String 0)))

(defn objects-dir
  ^Path [root]
  (.resolve (root-path root) "objects"))

(defn temp-dir
  ^Path [root]
  (.resolve (root-path root) "tmp"))

(defn probe
  "Check one local-directory target without writing backup objects.

  `root` may be nil; the default is `<data-dir>/backups`.  Creates the
  directory chain and verifies it is writable.  Returns
  `{:ok? bool :status nil :detail string :url string}`; `:url` is a
  `file://` URL without credentials."
  [root data-dir]
  (let [root (or (not-empty (str root))
                 (str data-dir "/backups"))]
    (try
      (let [^Path dir (root-path root)]
        (Files/createDirectories dir (make-array FileAttribute 0))
        (if (.isWritable dir)
          {:ok? true :status nil
           :detail "local backup directory is writable"
           :url (str "file://" dir)}
          {:ok? false :status nil
           :detail "local backup directory is not writable"
           :url (str "file://" dir)}))
      (catch Throwable t
        {:ok? false :status nil
         :detail (or (ex-message t) (str t))
         :url nil}))))

(defn object-path
  ^Path [root id]
  (.resolve (objects-dir root) (str (assert-id! id) ".hcb")))

(defn- ensure-root!
  [root]
  (Files/createDirectories (objects-dir root) (make-array FileAttribute 0))
  (Files/createDirectories (temp-dir root) (make-array FileAttribute 0)))

(defn- ensure-space!
  "Fail before creating a temp file when the filesystem is obviously too
  small.  The message fallback in the write path remains for races."
  [^Path dir ^bytes bytes id]
  (try
    (let [usable (.getUsableSpace (Files/getFileStore dir))]
      (when (< (long usable) (+ (long (alength bytes)) (* 1024 1024)))
        (err/fail! :BACKUP_STORAGE_FULL
                   "Not enough space to write the backup"
                   {:id id :path (str dir)
                    :required (+ (alength bytes) (* 1024 1024))
                    :usable usable})))
    (catch clojure.lang.ExceptionInfo e (throw e))
    (catch Throwable _ nil)))

(defn- attrs
  ^BasicFileAttributes [^Path path]
  (Files/readAttributes path BasicFileAttributes
                        ^"[Ljava.nio.file.LinkOption;" (make-array LinkOption 0)))

(defn- fsync-directory!
  [^Path dir]
  (try
    (with-open [channel (FileChannel/open
                         dir
                         (into-array OpenOption [StandardOpenOption/READ]))]
      (.force channel true))
    (catch Exception _ nil)))

(defn- write-file!
  [^Path path ^bytes bytes]
  (with-open [out (FileOutputStream. (.toFile path))]
    (.write out bytes)
    (.flush out)
    (.sync (.getFD out))))

(defn- header-metadata
  [header file-size sha256 integrity]
  (merge {:id (:backup-id header)
          :size file-size
          :sha256 sha256
          :integrity integrity
          :encrypted? (= :aes-gcm (:encryption header))
          :format-version (:format-version header)
          :sampled-at (:sampled-at header)
          :created-at (:created-at header)
          :device (:device header)
          :app-version (:app-version header)}
         (package/public-metadata header)))

(defn- object-metadata
  [^Path path header sha256 integrity]
  (assoc (header-metadata header (.size (attrs path)) sha256 integrity)
         :path (str path)))

(defn- read-object-header
  [^Path path]
  (with-open [in (Files/newInputStream path (make-array OpenOption 0))]
    (let [{:keys [container-version header-bytes]}
          (codec/read-container-header in)]
      (when (> (long container-version) (long codec/container-version))
        (err/fail! :BACKUP_VERSION_UNSUPPORTED
                   "Backup container version is newer than this application"
                   {:path (str path)
                    :container-version container-version}))
      (package/read-header-bytes header-bytes))))

(defn- list-object-files
  [root]
  (let [dir (objects-dir root)]
    (if (Files/exists dir (make-array LinkOption 0))
      (with-open [stream (Files/list dir)]
        (->> (iterator-seq (.iterator stream))
             (filter #(str/ends-with? (str (.getFileName ^Path %)) ".hcb"))
             (sort-by str)
             vec))
      [])))

(defn- listing-metadata
  [^Path path]
  (try
    (let [result (read-object-header path)]
      (if (= :verified (:integrity result))
        (object-metadata path (:header result) nil :verified)
        {:id (str/replace (str (.getFileName path)) #"\.hcb$" "")
         :size (.size (attrs path))
         :integrity :invalid
         :error (:error result)
         :message (:message result)}))
    (catch clojure.lang.ExceptionInfo e
      {:id (str/replace (str (.getFileName path)) #"\.hcb$" "")
       :size (try (.size (attrs path)) (catch Exception _ nil))
       :integrity :invalid
       :error (err/code e)
       :message (.getMessage ^Throwable e)})
    (catch Exception e
      {:id (str/replace (str (.getFileName path)) #"\.hcb$" "")
       :size (try (.size (attrs path)) (catch Exception _ nil))
       :integrity :invalid
       :error :BACKUP_FORMAT_INVALID
       :message (.getMessage ^Throwable e)})))

(extend-type FileTarget
  port/TargetPort
  (put-object! [target object]
    (let [root (:root target)
          id (assert-id! (:id object))
          ^bytes bytes (:bytes object)
          _ (when-not (bytes? bytes)
              (err/fail! :BACKUP_FORMAT_INVALID
                         "Backup object bytes must be a byte array"
                         {:id id :type (str (class bytes))}))
          target-path (object-path root id)
          _ (ensure-root! root)
          _ (ensure-space! (objects-dir root) bytes id)
          existing (when (Files/exists target-path (make-array LinkOption 0))
                     (Files/readAllBytes target-path))]
      (if existing
        (if (java.util.Arrays/equals ^bytes existing bytes)
          (let [{:keys [header]} (package/verify-container existing)]
            (assoc (object-metadata target-path header
                                    (codec/sha256-hex existing) :verified)
                   :deduplicated? true))
          (err/fail! :BACKUP_OBJECT_CONFLICT
                     "A backup object with this ID already has different bytes"
                     {:id id :path (str target-path)}))
        (let [{:keys [header]} (package/verify-container bytes)]
          (let [temp (Files/createTempFile (temp-dir root)
                                           "backup-"
                                           ".part"
                                           (make-array FileAttribute 0))]
            (try
              (write-file! temp bytes)
              (try
                (Files/move temp target-path
                            (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE]))
                (catch AtomicMoveNotSupportedException e
                  (err/fail! :BACKUP_ATOMIC_MOVE_UNSUPPORTED
                             "Filesystem cannot publish a backup object atomically"
                             {:id id :path (str target-path)}
                             e)))
              (fsync-directory! (objects-dir root))
              (assoc (object-metadata target-path header
                                      (codec/sha256-hex bytes) :verified)
                     :deduplicated? false)
              (catch java.io.IOException e
                (try (Files/deleteIfExists temp) (catch Exception _ nil))
                (let [message (str/lower-case (str (.getMessage e)))]
                  (if (or (str/includes? message "no space")
                          (str/includes? message "space left"))
                    (err/fail! :BACKUP_STORAGE_FULL
                               "Not enough space to write the backup"
                               {:id id :path (str target-path)}
                               e)
                    (err/fail! :BACKUP_IO
                               "Could not write the backup object"
                               {:id id :path (str target-path)}
                               e))))))))))

  (get-object [target id]
    (let [path (object-path (:root target) id)]
      (when (Files/exists path (make-array LinkOption 0))
        (Files/readAllBytes path))))

  (delete-object! [target id]
    (Files/deleteIfExists (object-path (:root target) id))
    :deleted)

  (list-objects [target]
    (->> (list-object-files (:root target))
         (map listing-metadata)
         (sort-by (fn [p]
                    (or (when-let [s (:created-at p)]
                          (when (string? s)
                            (try (.toEpochMilli (Instant/parse s))
                                 (catch Exception _ nil))))
                        Long/MIN_VALUE))
                  >)
         vec))

  (verify-object [target id expected-sha256]
    (let [path (object-path (:root target) id)
          bytes (when (Files/exists path (make-array LinkOption 0))
                  (Files/readAllBytes path))]
      (when-not bytes
        (err/fail! :BACKUP_NOT_FOUND
                   "Backup object does not exist"
                   {:id id}))
      (let [actual (codec/sha256-hex bytes)]
        (when-not (= expected-sha256 actual)
          (err/fail! :BACKUP_INTEGRITY
                     "Backup object digest does not match"
                     {:id id
                      :expected-sha256 expected-sha256
                      :actual-sha256 actual}))
        (let [{:keys [header]} (package/verify-container bytes)]
          (object-metadata path header actual :verified))))))
