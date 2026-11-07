(ns hc.server.blob-store.store
  "Content-addressed, atomically finalized blob objects.

  Writing has three phases:

  1. `stage` creates a uniquely named file under `<root>/tmp/`.
  2. `write!` / `write-stream!` append bytes while hashing and counting them.
  3. `commit!` flushes and fsyncs the file, derives the stable content ID,
     then atomically renames the staging file into its content-addressed path.

  A reader never observes a partial object: the final path appears only after
  the rename.  A crash before the rename leaves a staging file for the
  reference-aware cleaner; a crash after the rename leaves a complete,
  unreferenced object for the same cleaner.  Finalized objects are immutable,
  and writing identical bytes again deduplicates onto the existing object."
  (:require [hc.server.blob-store.content :as content]
            [hc.server.blob-store.error :as err]
            [hc.server.blob-store.layout :as layout])
  (:import (java.io FileDescriptor FileOutputStream InputStream)
           (java.nio.channels FileChannel)
           (java.nio.file AtomicMoveNotSupportedException
                          CopyOption
                          FileAlreadyExistsException
                          Files
                          LinkOption
                          NoSuchFileException
                          OpenOption
                          Path
                          StandardCopyOption
                          StandardOpenOption)
           (java.nio.file.attribute BasicFileAttributes FileAttribute)
           (java.security MessageDigest)))

(set! *warn-on-reflection* true)

(def ^:private no-file-attributes
  (make-array FileAttribute 0))

(def ^:private no-link-options
  (make-array LinkOption 0))

(defrecord Staging
    [root ^Path temp-path ^FileOutputStream out ^MessageDigest digest
     ^longs size state result])

(defn stage
  "Begin one staging write.

  The staging file is created under `<root>/tmp/`, on the same filesystem as
  the final objects.  The caller owns the returned staging value and must
  finish it with `commit!` or `abort!`."
  ([root]
   (stage root {}))
  ([root _opts]
   (layout/ensure-root! root)
   (let [temp-path (Files/createTempFile
                    (layout/temp-dir root)
                    "blob-"
                    ".part"
                    no-file-attributes)]
     (->Staging (layout/root-path root)
                temp-path
                (FileOutputStream. (.toFile temp-path))
                (content/new-digest)
                (long-array 1)
                (atom :open)
                (atom nil)))))

(defn- ensure-open!
  [^Staging staging]
  (when-not (= :open @(:state staging))
    (err/fail! :BLOB_STAGING_CLOSED
               "Blob staging is no longer open for writing"
               {:state @(:state staging)
                :temp-path (str (:temp-path staging))})))

(defn- write-range!
  "Append `len` bytes from `bytes` starting at `off`.

  One write updates the file, the digest and the size counter together; there
  is no state in which only some of the three advanced."
  [^Staging staging ^bytes bytes off len]
  (ensure-open! staging)
  (when (or (neg? off)
            (neg? len)
            (> (+ off len) (alength bytes)))
    (err/fail! :BLOB_INVALID_WRITE
               "Blob write range is outside the supplied buffer"
               {:offset off :length len :buffer-length (alength bytes)}))
  (when (pos? len)
    (.write ^FileOutputStream (:out staging) bytes (int off) (int len))
    (.update ^MessageDigest (:digest staging) bytes (int off) (int len))
    (aset ^longs (:size staging) 0
          (+ (aget ^longs (:size staging) 0) (long len))))
  staging)

(defn write!
  "Append all of `bytes` to an open staging value."
  [staging ^bytes bytes]
  (write-range! staging bytes 0 (alength bytes)))

(defn write-stream!
  "Append `in` to EOF.  The input stream is not closed."
  [staging ^InputStream in]
  (let [buffer (byte-array 65536)]
    (loop []
      (let [n (.read in buffer)]
        (when (pos? n)
          (write-range! staging buffer 0 n)
          (recur)))))
  staging)

(defn- fsync-directory!
  "Best-effort directory fsync after a rename.

  Some filesystems and operating systems cannot open a directory channel; the
  rename itself is already atomic, so a directory-fsync failure is not allowed
  to turn a completed publish into a reported failure."
  [^Path directory]
  (try
    (with-open [channel (FileChannel/open
                         directory
                         (into-array OpenOption [StandardOpenOption/READ]))]
      (.force channel true))
    (catch Exception _ nil)))

(defn- existing-metadata
  "Verify an object already present at `target` and return its metadata.

  A different digest or a non-regular entry means the object path is corrupt.
  The existing object is never overwritten in that case."
  [id ^Path target]
  (let [a (layout/attrs target)]
    (when-not (and a (.isRegularFile a))
      (err/fail! :BLOB_INTEGRITY
                 "Existing object path is not a regular file"
                 {:id id :path (str target)}))
    (let [actual (content/digest-file target)
          expected (content/id->hex id)]
      (when-not (= expected (:sha256 actual))
        (err/fail! :BLOB_INTEGRITY
                   "Existing object does not match its content ID"
                   {:id id
                    :path (str target)
                    :expected-sha256 expected
                    :actual-sha256 (:sha256 actual)}))
      (content/metadata id (:size actual) target
                        (.toMillis (.lastModifiedTime ^BasicFileAttributes a))))))

(defn- delete-quietly!
  [^Path path]
  (try
    (Files/deleteIfExists path)
    (catch Exception _ nil)))

(defn- publish!
  "Move a completed staging file to its content-addressed final path."
  [^Staging staging id]
  (let [target (layout/object-path (:root staging) id)
        parent (.getParent target)]
    (Files/createDirectories parent no-file-attributes)
    (if (layout/attrs target)
      (let [metadata (existing-metadata id target)]
        (delete-quietly! (:temp-path staging))
        (assoc metadata :deduplicated? true))
      (try
        (Files/move (:temp-path staging)
                    target
                    (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE]))
        (fsync-directory! parent)
        (let [a (layout/attrs target)]
          (when-not (and a (.isRegularFile a))
            (err/fail! :BLOB_INTEGRITY
                       "Published object is missing or not a regular file"
                       {:id id :path (str target)}))
          (assoc (content/metadata id
                                   (aget ^longs (:size staging) 0)
                                   target
                                   (.toMillis (.lastModifiedTime ^BasicFileAttributes a)))
                 :deduplicated? false))
        (catch FileAlreadyExistsException _
          (let [metadata (existing-metadata id target)]
            (delete-quietly! (:temp-path staging))
            (assoc metadata :deduplicated? true)))
        (catch AtomicMoveNotSupportedException e
          (err/fail! :BLOB_ATOMIC_MOVE_UNSUPPORTED
                     "Filesystem cannot publish a blob with one atomic rename"
                     {:id id :temp-path (str (:temp-path staging))}
                     e))
        (catch java.io.IOException e
          (err/io-failure! "Blob publish failed"
                           {:id id :temp-path (str (:temp-path staging))}
                           e))))))

(declare abort!)

(defn commit!
  "Finish a staging write and publish its object.

  Options:
  - `:expected-id`   fail with `:BLOB_INTEGRITY` unless the computed ID matches
  - `:expected-size` fail with `:BLOB_INTEGRITY` unless the byte count matches

  The operation is idempotent after success: calling `commit!` again returns
  the stored metadata.  After a failure the staging value is terminal; call
  `abort!` to remove its temporary file, then stage again."
  ([staging]
   (commit! staging {}))
  ([^Staging staging {:keys [expected-id expected-size]}]
   (case @(:state staging)
     :committed @(:result staging)
     :aborted (err/fail! :BLOB_STAGING_CLOSED
                         "Blob staging was aborted"
                         {:temp-path (str (:temp-path staging))})
     :failed (err/fail! :BLOB_STAGING_CLOSED
                        "Blob staging already failed"
                        {:temp-path (str (:temp-path staging))})
     :open
     (let [size (aget ^longs (:size staging) 0)
           id (content/id-of-digest (:digest staging))]
       (when (and (some? expected-size)
                  (not= (long expected-size) size))
         (abort! staging)
         (err/fail! :BLOB_INTEGRITY
                    "Blob size does not match the expected size"
                    {:expected-size (long expected-size) :actual-size size}))
       (when (and (some? expected-id)
                  (not= expected-id id))
         (abort! staging)
         (err/fail! :BLOB_INTEGRITY
                    "Blob digest does not match the expected content ID"
                    {:expected-id expected-id :actual-id id}))
       (try
         (let [^FileOutputStream out (:out staging)]
           (.flush out)
           (.sync ^FileDescriptor (.getFD out))
           (.close out))
         (let [metadata (publish! staging id)]
           (reset! (:result staging) metadata)
           (reset! (:state staging) :committed)
           metadata)
         (catch Throwable t
           (try
             (.close ^FileOutputStream (:out staging))
             (catch Throwable _ nil))
           (reset! (:state staging) :failed)
           (throw t)))))))

(defn abort!
  "Close and remove an unfinished staging file.

  Aborting a committed staging value is a no-op: a finalized object is never
  removed by this function.  Aborting twice is also a no-op."
  [^Staging staging]
  (case @(:state staging)
    :committed :committed
    :aborted :aborted
    (do
      (try
        (.close ^FileOutputStream (:out staging))
        (catch Throwable _ nil))
      (delete-quietly! (:temp-path staging))
      (reset! (:state staging) :aborted)
      :aborted)))

(defn put-bytes!
  "Stage, write and commit one byte array."
  ([root bytes]
   (put-bytes! root bytes {}))
  ([root ^bytes bytes opts]
   (let [staging (stage root)]
     (try
       (write! staging bytes)
       (commit! staging opts)
       (catch Throwable t
         (abort! staging)
         (throw t))))))

(defn put-stream!
  "Stage, consume `in` to EOF, and commit.  The input stream is not closed."
  ([root in]
   (put-stream! root in {}))
  ([root ^InputStream in opts]
   (let [staging (stage root)]
     (try
       (write-stream! staging in)
       (commit! staging opts)
       (catch Throwable t
         (abort! staging)
         (throw t))))))

(defn put-file!
  "Stage, consume a file, and commit.  The source file is never modified."
  ([root source]
   (put-file! root source {}))
  ([root source opts]
   (with-open [in ^InputStream (Files/newInputStream
                                (layout/path-of source)
                                (make-array OpenOption 0))]
     (put-stream! root in opts))))

(defn- object-attrs!
  "Read no-follow attributes for an object, failing when absent or unusual."
  [root id]
  (let [id (content/assert-id! id)
        path (layout/object-path root id)
        a (layout/attrs path)]
    (cond
      (nil? a)
      (err/fail! :BLOB_NOT_FOUND
                 "Blob object does not exist"
                 {:id id :path (str path)})

      (not (.isRegularFile ^BasicFileAttributes a))
      (err/fail! :BLOB_INTEGRITY
                 "Blob object path is not a regular file"
                 {:id id :path (str path)})

      :else
      [id path a])))

(defn metadata
  "Metadata for `id`, or nil when the object is absent.

  A present but non-regular entry is corruption and fails with
  `:BLOB_INTEGRITY`."
  [root id]
  (let [id (content/assert-id! id)
        path (layout/object-path root id)
        a (layout/attrs path)]
    (cond
      (nil? a) nil
      (not (.isRegularFile ^BasicFileAttributes a))
      (err/fail! :BLOB_INTEGRITY
                 "Blob object path is not a regular file"
                 {:id id :path (str path)})
      :else
      (content/metadata id
                        (.size ^BasicFileAttributes a)
                        path
                        (.toMillis (.lastModifiedTime ^BasicFileAttributes a))))))

(defn exists?
  "True when `id` names a regular object in this store."
  [root id]
  (let [id (content/assert-id! id)
        a (layout/attrs (layout/object-path root id))]
    (boolean (and a (.isRegularFile ^BasicFileAttributes a)))))

(defn open-stream
  "Open a finalized object for reading.

  Returns `{:id :size :stream}`.  The caller owns and must close `:stream`.
  A missing object fails with `:BLOB_NOT_FOUND`; a non-regular entry fails with
  `:BLOB_INTEGRITY`."
  [root id]
  (let [[id path ^BasicFileAttributes a] (object-attrs! root id)]
    {:id id
     :size (.size a)
     :stream (Files/newInputStream path (make-array OpenOption 0))}))

(defn read-bytes
  "Read a finalized object into memory.

  Options:
  - `:max-bytes` refuse objects larger than this before reading
  - `:verify?`  recompute the digest before returning the bytes"
  ([root id]
   (read-bytes root id {}))
  ([root id {:keys [max-bytes verify?]}]
   (let [[id path ^BasicFileAttributes a] (object-attrs! root id)
         size (.size a)]
     (when (and (some? max-bytes) (> size (long max-bytes)))
       (err/fail! :BLOB_LIMIT_EXCEEDED
                  "Blob object exceeds the read limit"
                  {:id id :size size :limit (long max-bytes)}))
     (let [bytes (try
                   (Files/readAllBytes path)
                   (catch NoSuchFileException _
                     (err/fail! :BLOB_NOT_FOUND
                                "Blob object disappeared while it was being read"
                                {:id id :path (str path)})))]
       (when (and (some? max-bytes) (> (alength ^bytes bytes) (long max-bytes)))
         (err/fail! :BLOB_LIMIT_EXCEEDED
                    "Blob object grew beyond the read limit while being read"
                    {:id id :size (alength ^bytes bytes) :limit (long max-bytes)}))
       (when verify?
         (content/verify-bytes id bytes))
       bytes))))

(defn verify!
  "Recompute the digest and size of an object.

  Returns its metadata with `:verified? true`, or fails with
  `:BLOB_INTEGRITY` when the bytes no longer match the content ID."
  [root id]
  (let [[id path ^BasicFileAttributes a] (object-attrs! root id)
        actual (try
                 (content/digest-file path)
                 (catch NoSuchFileException _
                   (err/fail! :BLOB_NOT_FOUND
                              "Blob object disappeared while it was being verified"
                              {:id id :path (str path)})))
        expected (content/id->hex id)]
    (when-not (= expected (:sha256 actual))
      (err/fail! :BLOB_INTEGRITY
                 "Blob object does not match its content ID"
                 {:id id
                  :path (str path)
                  :expected-sha256 expected
                  :actual-sha256 (:sha256 actual)}))
    (assoc (content/metadata id
                             (:size actual)
                             path
                             (.toMillis (.lastModifiedTime ^BasicFileAttributes a)))
           :verified? true)))

(defn delete!
  "Delete one object by ID.

  This is the low-level primitive used by the reference-aware cleaner.  A
  caller may use it directly only after it has durably removed the last
  reference; otherwise use `hc.server.blob-store.cleanup/cleanup!`."
  [root id]
  (let [id (content/assert-id! id)
        path (layout/object-path root id)
        a (layout/attrs path)]
    (cond
      (nil? a) false
      (not (.isRegularFile ^BasicFileAttributes a))
      (err/fail! :BLOB_INTEGRITY
                 "Blob object path is not a regular file"
                 {:id id :path (str path)})
      :else
      (Files/deleteIfExists path))))

(defn delete-temp!
  "Delete one regular file under `<root>/tmp/`.

  Paths outside the staging directory are refused.  A non-regular entry is
  left untouched and returns false."
  [root path]
  (let [path (layout/path-of path)
        temp (layout/temp-dir root)]
    (when-not (.startsWith path temp)
      (err/fail! :BLOB_INVALID_PATH
                 "Path is outside the blob staging directory"
                 {:path (str path) :temp-dir (str temp)}))
    (if (layout/regular-file? path)
      (Files/deleteIfExists path)
      false)))

(defn- object-entry
  [^Path fanout ^Path file]
  (let [hex (str (.getFileName file))
        fanout-name (str (.getFileName fanout))
        a (layout/attrs file)]
    (cond
      (not (content/valid-hex? hex))
      {:kind :unrecognized :path (str file) :reason :invalid-object-name}

      (not= fanout-name (subs hex 0 2))
      {:kind :unrecognized :path (str file) :reason :wrong-fanout}

      (nil? a)
      {:kind :unrecognized :path (str file) :reason :vanished}

      (not (.isRegularFile ^BasicFileAttributes a))
      {:kind :unrecognized :path (str file) :reason :not-a-regular-file}

      :else
      {:kind :object
       :id (content/id-of-hex hex)
       :size (.size ^BasicFileAttributes a)
       :path (str file)
       :mtime-ms (.toMillis (.lastModifiedTime ^BasicFileAttributes a))})))

(defn- temp-entry
  [^Path file]
  (let [a (layout/attrs file)]
    (cond
      (nil? a)
      {:kind :unrecognized :path (str file) :reason :vanished}

      (not (.isRegularFile ^BasicFileAttributes a))
      {:kind :unrecognized :path (str file) :reason :not-a-regular-file}

      :else
      {:kind :temp
       :path (str file)
       :size (.size ^BasicFileAttributes a)
       :mtime-ms (.toMillis (.lastModifiedTime ^BasicFileAttributes a))})))

(defn scan
  "Observe the store without changing it.

  Returns `{:objects [...] :temp [...] :unrecognized [...]}`.  Object entries
  carry `:id`, `:size`, `:path` and `:mtime-ms`; temp entries carry the last
  three.  `:unrecognized` entries are never deleted by cleanup."
  [root]
  (let [object-entries
        (mapcat
         (fn [^Path fanout]
           (if (layout/directory? fanout)
             (map #(object-entry fanout %) (layout/directory-entries fanout))
             [{:kind :unrecognized
               :path (str fanout)
               :reason :not-a-directory}]))
         (layout/directory-entries (layout/sha256-dir root)))
        temp-entries (map temp-entry (layout/directory-entries (layout/temp-dir root)))
        all (concat object-entries temp-entries)]
    {:objects (->> all
                   (filter #(= :object (:kind %)))
                   (map #(dissoc % :kind))
                   (sort-by :id)
                   vec)
     :temp (->> all
                (filter #(= :temp (:kind %)))
                (map #(dissoc % :kind))
                (sort-by :path)
                vec)
     :unrecognized (->> all
                        (filter #(= :unrecognized (:kind %)))
                        (sort-by :path)
                        vec)}))
