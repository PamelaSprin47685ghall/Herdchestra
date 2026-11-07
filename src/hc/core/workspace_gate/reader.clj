(ns hc.core.workspace-gate.reader
  "Lock-free observation of the real workspace.

  Reading never acquires the write gate.  `cat` and `stat` produce stamps and
  structure observations; `ls` is a pure probe that produces neither.  A read
  whose stamp changes between the two metadata samples is returned with
  `:changed-during-read true` and cannot become a valid commit basis."
  (:require [hc.core.workspace-gate.error :as err]
            [hc.core.workspace-gate.stamp :as stamp])
  (:import (java.io ByteArrayOutputStream IOException)
           (java.nio ByteBuffer)
           (java.nio.charset CharacterCodingException CodingErrorAction StandardCharsets)
           (java.nio.file Files NoSuchFileException NotDirectoryException OpenOption)
           (java.util Arrays)))

(set! *warn-on-reflection* true)

(def default-max-file-bytes (* 2 1024 1024))

(defn decode-text
  "Decode bytes as canonical UTF-8.

  Invalid UTF-8 and byte sequences that do not round-trip are rejected as
  `:UNSUPPORTED_OPERATION`; the file is never silently rewritten with
  replacement characters."
  [^bytes bytes path]
  (let [decoder (doto (.newDecoder StandardCharsets/UTF_8)
                  (.onMalformedInput CodingErrorAction/REPORT)
                  (.onUnmappableCharacter CodingErrorAction/REPORT))]
    (try
      (let [text (str (.decode decoder (ByteBuffer/wrap bytes)))]
        (when-not (Arrays/equals bytes (.getBytes text StandardCharsets/UTF_8))
          (err/fail! :UNSUPPORTED_OPERATION :non-canonical-utf8
                     "File bytes do not round-trip through UTF-8 text"
                     {:path path}))
        text)
      (catch CharacterCodingException _
        (err/fail! :UNSUPPORTED_OPERATION :binary-file
                   "File is not valid UTF-8 text"
                   {:path path})))))

(defn- read-bounded
  "Read at most `limit + 1` bytes from `p`.

  The extra byte is enough to reject growth without allocating the whole
  file first.  A limit breach is an ordinary `:LIMIT_EXCEEDED`, not a
  hard failure."
  [^java.nio.file.Path p limit]
  (with-open [in (Files/newInputStream p (make-array OpenOption 0))]
    (let [out (ByteArrayOutputStream.)
          buffer (byte-array 8192)]
      (loop [total 0]
        (let [n (.read in buffer)]
          (if (neg? n)
            (.toByteArray out)
            (let [total (+ total n)]
              (when (> total limit)
                (err/fail! :LIMIT_EXCEEDED :file-too-large
                           "File grew beyond the configured single-file limit"
                           {:path (str p) :size total :limit limit}))
              (.write out buffer 0 n)
              (recur total))))))))

(defn read-bytes
  "Read a regular file without the write gate.

  Returns `{:path :exists :bytes :stamp :size :changed-during-read?}`.
  A missing file returns `{:path p :exists false}`; callers that require a
  file turn that into `:STRUCTURE_CONFLICT`."
  [root path {:keys [max-file-bytes] :or {max-file-bytes default-max-file-bytes}}]
  (let [p (stamp/path-of root path)
        ps (str p)
        a (stamp/attrs p)]
    (cond
      (nil? a)
      {:path ps :exists false :changed-during-read? false}

      (.isDirectory a)
      (err/fail! :STRUCTURE_CONFLICT :not-a-file
                 "Path is a directory, not a file"
                 {:path ps :kind :directory})

      (not (.isRegularFile a))
      (err/fail! :UNSUPPORTED_OPERATION :not-a-regular-file
                 "Only regular files support text reads"
                 {:path ps})

      (> (.size a) max-file-bytes)
      (err/fail! :LIMIT_EXCEEDED :file-too-large
                 "File exceeds the configured single-file limit"
                 {:path ps :size (.size a) :limit max-file-bytes})

      :else
      (let [before (stamp/stamp-of-attrs a)]
        (try
          (let [bytes (read-bounded p max-file-bytes)
                after (stamp/current-stamp p)
                changed? (not (stamp/same-stamp? before after))
                size (alength ^bytes bytes)]
            (when (> size max-file-bytes)
              (err/fail! :LIMIT_EXCEEDED :file-too-large
                         "File grew beyond the configured single-file limit"
                         {:path ps :size size :limit max-file-bytes}))
            {:path ps
             :exists true
             :bytes bytes
             :size size
             :stamp before
             :changed-during-read? changed?})
          (catch NoSuchFileException _
            (err/fail! :FILE_CONFLICT :changed-during-read
                       "File disappeared while it was being read"
                       {:path ps :expected before :actual nil}))
          (catch IOException e
            (let [after-kind (stamp/kind-of p)
                  after (when (= :file after-kind)
                          (stamp/current-stamp p))]
              (err/fail! (if (= :file after-kind)
                           :FILE_CONFLICT
                           :STRUCTURE_CONFLICT)
                         :changed-during-read
                         "File changed while it was being read"
                         {:path ps
                          :expected before
                          :actual after
                          :kind after-kind
                          :exception (err/throwable->data e)}))))))))

(defn read-text
  "Read a UTF-8 text file.  The result includes the full-file stamp even for
  a partial view; this implementation has no line-level versions."
  [root path opts]
  (let [read (read-bytes root path opts)]
    (if (:exists read)
      (-> read
          (dissoc :bytes)
          (assoc :text (decode-text (:bytes read) (:path read))
                 :observation-basis :mtime+size
                 :status (if (:changed-during-read? read)
                           :FILE_CONFLICT
                           :OBSERVED)))
      (err/fail! :STRUCTURE_CONFLICT :missing
                 "Path does not exist"
                 {:path (:path read)}))))

(defn observe
  "Collect the structure condition and stamp for one exact path.

  `:kind` is `:file`, `:directory`, `:missing` or `:other`.  Only regular
  files carry a content stamp.  A directory listing time is never treated as
  a subtree version."
  [root path]
  (let [p (stamp/path-of root path)
        ps (str p)
        a (stamp/attrs p)]
    (cond
      (nil? a)
      {:path ps :kind :missing}

      (.isDirectory a)
      {:path ps :kind :directory}

      (.isRegularFile a)
      {:path ps :kind :file :stamp (stamp/stamp-of-attrs a)}

      :else
      {:path ps :kind :other})))

(defn stat-path
  "The public `stat` shape.  Missing paths are ordinary observations."
  [root path]
  (let [p (stamp/path-of root path)]
    (stamp/stat-map p)))

(defn list-dir
  "Non-recursive directory listing.  Directory entries end with `/`.

  This is a pure probe: it never enters a read-set and never creates a
  structure check."
  [root path]
  (let [p (stamp/path-of root path)
        a (stamp/attrs p)]
    (cond
      (nil? a)
      (err/fail! :STRUCTURE_CONFLICT :missing
                 "Directory does not exist"
                 {:path (str p)})

      (not (.isDirectory a))
      (err/fail! :STRUCTURE_CONFLICT :not-a-directory
                 "Path is not a directory"
                 {:path (str p)})

      :else
      (try
        (with-open [stream (Files/list p)]
          (->> (iterator-seq (.iterator stream))
               (map (fn [^java.nio.file.Path child]
                      (let [name (str (.getFileName child))]
                        (if (Files/isDirectory child (make-array java.nio.file.LinkOption 0))
                          (str name "/")
                          name))))
               sort
               vec))
        (catch NoSuchFileException _
          (err/fail! :STRUCTURE_CONFLICT :missing
                     "Directory disappeared while it was being listed"
                     {:path (str p)}))
        (catch NotDirectoryException _
          (err/fail! :STRUCTURE_CONFLICT :not-a-directory
                     "Path became a non-directory while it was being listed"
                     {:path (str p)}))))))
