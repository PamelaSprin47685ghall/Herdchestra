(ns hc.server.backup.file-recovery
  "Local recovery-point files.

  Each point is one canonical EDN file under `<root>/points/`.  Writes go to a
  temporary file, are fsynced, and are published with one atomic rename.  The
  file is readable only by the owner when the platform supports POSIX
  permissions.  A recovery point is device-local protection, not an external
  backup."
  (:require [clojure.string :as str]
            [hc.server.backup.codec :as codec]
            [hc.server.backup.error :as err]
            [hc.server.backup.port :as port])
  (:import (java.io FileOutputStream)
           (java.nio.channels FileChannel)
           (java.nio.file AtomicMoveNotSupportedException CopyOption Files
                          LinkOption OpenOption Path StandardCopyOption
                          StandardOpenOption)
           (java.nio.file.attribute BasicFileAttributes FileAttribute
                                    PosixFilePermissions)
           (java.time Instant)
           (java.util.regex Pattern)))

(def point-format
  :hc.backup/recovery-point)

(def point-format-version
  1)

(def ^:private id-pattern
  (Pattern/compile "^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$"))

(defrecord FileRecoveryStore [root])

(defn store
  "Create a local recovery-point store rooted at `root`."
  [root]
  (->FileRecoveryStore (str root)))

(defn valid-id?
  [id]
  (and (string? id)
       (not (str/blank? id))
       (boolean (re-matches id-pattern id))))

(defn- assert-id!
  [id]
  (when-not (valid-id? id)
    (err/fail! :BACKUP_ID_INVALID
               "Recovery point ID is not a safe file name"
               {:id id}))
  id)

(defn root-path
  ^Path [root]
  (Path/of (str root) (make-array String 0)))

(defn points-dir
  ^Path [root]
  (.resolve (root-path root) "points"))

(defn temp-dir
  ^Path [root]
  (.resolve (root-path root) "tmp"))

(defn point-path
  ^Path [root id]
  (.resolve (points-dir root) (str (assert-id! id) ".edn")))

(defn- ensure-root!
  [root]
  (Files/createDirectories (points-dir root) (make-array FileAttribute 0))
  (Files/createDirectories (temp-dir root) (make-array FileAttribute 0)))

(defn- ensure-space!
  [^Path dir ^bytes bytes id]
  (try
    (let [usable (.getUsableSpace (Files/getFileStore dir))]
      (when (< (long usable) (+ (long (alength bytes)) (* 1024 1024)))
        (err/fail! :BACKUP_STORAGE_FULL
                   "Not enough space to create a local recovery point"
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
    (.sync (.getFD out)))
  (try
    (Files/setPosixFilePermissions
     path
     (PosixFilePermissions/fromString "rw-------"))
    (catch UnsupportedOperationException _ nil)
    (catch Exception _ nil)))

(defn- state-sha
  [state]
  (codec/sha256-hex (codec/canonical-bytes state)))

(defn- encode-point
  [point]
  (let [state (:state point)
        _ (when-not (map? state)
            (err/fail! :BACKUP_RECOVERY_POINT_INVALID
                       "Recovery point state must be a map"
                       {:id (:id point)}))
        sha (state-sha state)
        body0 (-> point
                  (dissoc :bytes :sha256 :integrity :body-sha256)
                  (assoc :format point-format
                         :format-version point-format-version
                         :state-sha256 sha
                         :state-bytes (alength (codec/canonical-bytes state))))
        body (assoc body0 :body-sha256
                    (codec/sha256-hex (codec/canonical-bytes body0)))]
    {:body body
     :bytes (codec/canonical-bytes body)
     :state-bytes (:state-bytes body)
     :sha256 sha}))

(defn- verify-body
  [body]
  (when-not (and (map? body)
                 (= point-format (:format body)))
    (err/fail! :BACKUP_RECOVERY_POINT_INVALID
               "Recovery point has an unknown format"
               {:format (:format body)}))
  (when (> (long (or (:format-version body) 0)) point-format-version)
    (err/fail! :BACKUP_VERSION_UNSUPPORTED
               "Recovery point was written by a newer format"
               {:format-version (:format-version body)
                :supported-version point-format-version}))
  (let [body-actual (codec/sha256-hex
                     (codec/canonical-bytes (dissoc body :body-sha256)))]
    (when-not (= body-actual (:body-sha256 body))
      (err/fail! :BACKUP_INTEGRITY
                 "Recovery point body digest does not match"
                 {:expected (:body-sha256 body) :actual body-actual})))
  (let [actual (state-sha (:state body))]
    (when-not (= actual (:state-sha256 body))
      (err/fail! :BACKUP_INTEGRITY
                 "Recovery point state digest does not match"
                 {:expected (:state-sha256 body) :actual actual})))
  body)

(defn- read-point-file
  [^Path path]
  (let [bytes (Files/readAllBytes path)
        body (codec/edn-read bytes)]
    (verify-body body)))

(defn- point-metadata
  [body file-size integrity]
  {:id (:id body)
   :created-at (:created-at body)
   :reason (:reason body)
   :scope (:scope body)
   :kind :local
   :status :success
   :bytes file-size
   :state-bytes (:state-bytes body)
   :integrity integrity})

(defn- metadata-from-file
  [^Path path]
  (try
    (let [body (read-point-file path)]
      (point-metadata body (.size (attrs path)) :verified))
    (catch clojure.lang.ExceptionInfo e
      {:id (str/replace (str (.getFileName path)) #"\.edn$" "")
       :integrity :invalid
       :error (err/code e)
       :message (.getMessage ^Throwable e)})
    (catch Exception e
      {:id (str/replace (str (.getFileName path)) #"\.edn$" "")
       :integrity :invalid
       :error :BACKUP_FORMAT_INVALID
       :message (.getMessage ^Throwable e)})))

(defn- list-point-files
  [root]
  (let [dir (points-dir root)]
    (if (Files/exists dir (make-array LinkOption 0))
      (with-open [stream (Files/list dir)]
        (->> (iterator-seq (.iterator stream))
             (filter #(str/ends-with? (str (.getFileName ^Path %)) ".edn"))
             (sort-by str)
             vec))
      [])))

(defn- free-bytes
  [^Path path]
  (try
    (.getUsableSpace (Files/getFileStore path))
    (catch Exception _ nil)))

(defn- created-millis
  [point]
  (try
    (if-let [s (:created-at point)]
      (if (string? s)
        (.toEpochMilli (Instant/parse s))
        Long/MIN_VALUE)
      Long/MIN_VALUE)
    (catch Exception _ Long/MIN_VALUE)))

(extend-type FileRecoveryStore
  port/RecoveryPointPort
  (write-recovery-point! [store point]
    (let [root (:root store)
          id (assert-id! (:id point))
          target (point-path root id)
          {:keys [body bytes state-bytes sha256]} (encode-point point)
          existing (when (Files/exists target (make-array LinkOption 0))
                     (Files/readAllBytes target))]
      (ensure-root! root)
      (ensure-space! (points-dir root) bytes id)
      (if (and existing (java.util.Arrays/equals ^bytes existing ^bytes bytes))
        (assoc (point-metadata body (.size (attrs target)) :verified)
               :deduplicated? true)
        (if existing
          (err/fail! :BACKUP_RECOVERY_POINT_CONFLICT
                     "A recovery point with this ID already has different content"
                     {:id id})
          (let [temp (Files/createTempFile (temp-dir root)
                                           "recovery-"
                                           ".part"
                                           (make-array FileAttribute 0))]
            (try
              (write-file! temp bytes)
              (try
                (Files/move temp target
                            (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE]))
                (catch AtomicMoveNotSupportedException e
                  (err/fail! :BACKUP_ATOMIC_MOVE_UNSUPPORTED
                             "Filesystem cannot publish a recovery point atomically"
                             {:id id :path (str target)}
                             e)))
              (fsync-directory! (points-dir root))
              (assoc (point-metadata body (.size (attrs target)) :verified)
                     :deduplicated? false
                     :sha256 sha256
                     :state-bytes state-bytes)
              (catch java.io.IOException e
                (try (Files/deleteIfExists temp) (catch Exception _ nil))
                (let [message (str/lower-case (str (.getMessage e)))]
                  (if (or (str/includes? message "no space")
                          (str/includes? message "space left"))
                    (err/fail! :BACKUP_STORAGE_FULL
                               "Not enough space to create a local recovery point"
                               {:id id :path (str target)}
                               e)
                    (err/fail! :BACKUP_RECOVERY_POINT_FAILED
                               "Could not create a local recovery point"
                               {:id id :path (str target)}
                               e))))))))))

  (list-recovery-points [store]
    (->> (list-point-files (:root store))
         (map metadata-from-file)
         (sort-by created-millis >)
         vec))

  (read-recovery-point [store id]
    (let [path (point-path (:root store) id)]
      (when (Files/exists path (make-array LinkOption 0))
        (read-point-file path))))

  (delete-recovery-point! [store id]
    (let [path (point-path (:root store) id)]
      (Files/deleteIfExists path)
      :deleted))

  (recovery-storage-info [store]
    (let [root (:root store)
          dir (points-dir root)
          points (list-point-files root)
          used (reduce + 0 (map #(try (.size (attrs ^Path %))
                                      (catch Exception _ 0))
                                points))]
      {:path (str (root-path root))
       :used-bytes used
       :free-bytes (free-bytes dir)
       :count (count points)})))
