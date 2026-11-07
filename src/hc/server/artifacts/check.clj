(ns hc.server.artifacts.check
  "Completeness and openability checks for delivered artifacts.

  Root `AGENTS.md` §7.2 and §8.6 require four steps before an artifact is
  announced: the task workspace produced it, completeness and openability were
  checked, durable metadata was registered, and only then may the user see it.
  This namespace performs the second step without touching the domain store.

  A check is a plain map with `:check` and `:ok?`.  A failure carries the same
  stable `:error` keyword that registration raises, so a caller can show the
  exact reason without parsing prose."
  (:require [clojure.string :as str]
            [hc.core.workspace-gate.stamp :as stamp]
            [hc.server.artifacts.error :as err]
            [hc.server.blob-store :as blob])
  (:import (java.io InputStream)
           (java.nio.charset CharacterCodingException CodingErrorAction StandardCharsets)
           (java.nio.file Files LinkOption NoSuchFileException Path Paths)
           (java.nio.file.attribute BasicFileAttributes)
           (java.time Instant)))

(set! *warn-on-reflection* true)

(def default-max-bytes
  "Root §7.2: one artifact may not exceed 64 MiB by default."
  (* 64 1024 1024))

(def default-text-check-bytes
  "Bytes inspected when proving that a textual artifact decodes as UTF-8."
  (* 1024 1024))

(def ^:private no-link-options
  (make-array LinkOption 0))

(def ^:private no-follow-options
  (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))

(declare valid-utf8?)

;; ---------------------------------------------------------------------------
;; Names and media types

(defn path-of
  "Resolve `value` to a normalized absolute Path."
  ^Path [value]
  (-> (Paths/get (str value) (make-array String 0))
      (.toAbsolutePath)
      (.normalize)))

(defn- extension
  [name]
  (let [name (str name)
        dot (.lastIndexOf ^String name ".")]
    (when (and (pos? dot) (< dot (dec (count name))))
      (str/lower-case (subs name (inc dot))))))

(def ^:private media-types
  {"txt" "text/plain"
   "text" "text/plain"
   "log" "text/plain"
   "md" "text/markdown"
   "markdown" "text/markdown"
   "csv" "text/csv"
   "tsv" "text/tab-separated-values"
   "json" "application/json"
   "jsonl" "application/x-ndjson"
   "ndjson" "application/x-ndjson"
   "edn" "application/edn"
   "xml" "application/xml"
   "yaml" "application/yaml"
   "yml" "application/yaml"
   "toml" "application/toml"
   "html" "text/html"
   "htm" "text/html"
   "css" "text/css"
   "js" "text/javascript"
   "mjs" "text/javascript"
   "clj" "text/x-clojure"
   "cljc" "text/x-clojure"
   "cljs" "text/x-clojure"
   "py" "text/x-python"
   "rb" "text/x-ruby"
   "sh" "text/x-shellscript"
   "bash" "text/x-shellscript"
   "sql" "text/plain"
   "diff" "text/x-diff"
   "patch" "text/x-diff"
   "pdf" "application/pdf"
   "png" "image/png"
   "jpg" "image/jpeg"
   "jpeg" "image/jpeg"
   "gif" "image/gif"
   "webp" "image/webp"
   "svg" "image/svg+xml"
   "ico" "image/x-icon"
   "mp3" "audio/mpeg"
   "wav" "audio/wav"
   "ogg" "audio/ogg"
   "mp4" "video/mp4"
   "webm" "video/webm"
   "zip" "application/zip"
   "gz" "application/gzip"
   "tar" "application/x-tar"
   "7z" "application/x-7z-compressed"})

(defn media-type-for
  "Best-effort media type from a name.  Unknown names return nil."
  [name]
  (get media-types (extension name)))

(defn textual-media-type?
  [media-type]
  (let [media-type (str/lower-case (str media-type))]
    (or (str/starts-with? media-type "text/")
        (contains? #{"application/json"
                     "application/edn"
                     "application/xml"
                     "application/x-ndjson"
                     "application/yaml"
                     "application/toml"
                     "image/svg+xml"}
                   media-type))))

(defn browser-openable?
  "True for types a browser can display without downloading first."
  [media-type]
  (let [media-type (str/lower-case (str media-type))]
    (or (str/starts-with? media-type "text/")
        (str/starts-with? media-type "image/")
        (str/starts-with? media-type "audio/")
        (str/starts-with? media-type "video/")
        (contains? #{"application/pdf"
                     "application/json"
                     "application/xml"
                     "application/edn"}
                   media-type))))

(defn safe-download-filename
  "Return a filename accepted by the gateway's `[A-Za-z0-9._-]{1,128}` rule,
  or nil when no useful ASCII name survives.

  The original name stays in metadata.  This value is only the HTTP
  `Content-Disposition` fallback."
  [name]
  (when (string? name)
    (let [base (-> name
                   (str/replace #"[^A-Za-z0-9._-]+" "_")
                   (str/replace #"^\.+" "")
                   (str/replace #"_+" "_")
                   (str/replace #"_+$" ""))]
      (when (seq base)
        (subs base 0 (min 128 (count base)))))))

(defn- valid-media-type?
  [value]
  (and (string? value)
       (boolean (re-matches #"[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+(?:[ \t]*;.*)?" value))))

(defn normalize-media-type
  "Return an explicit media type, or detect one from NAME and PREFIX."
  [value name prefix]
  (cond
    (some? value)
    (if (valid-media-type? value)
      value
      (err/fail! :ARTIFACT_INVALID_MEDIA_TYPE
                 "media type is not a valid type/subtype"
                 {:media-type value}))

    (media-type-for name) (media-type-for name)

    :else
    (let [prefix (or prefix (byte-array 0))]
      (if (and (pos? (alength ^bytes prefix))
               (valid-utf8? prefix))
        "text/plain"
        "application/octet-stream"))))

;; ---------------------------------------------------------------------------
;; Source observations

(defn attrs
  "Read attributes for PATH.  Follows symlinks when `follow-links?` is true.
  Missing paths return nil; other I/O failures propagate."
  ^BasicFileAttributes [^Path path follow-links?]
  (try
    (let [^"[Ljava.nio.file.LinkOption;" options
          (if follow-links? no-link-options no-follow-options)]
      (Files/readAttributes path BasicFileAttributes options))
    (catch NoSuchFileException _ nil)
    (catch java.io.FileNotFoundException _ nil)
    (catch java.nio.file.NotDirectoryException _ nil)))

(defn source-snapshot
  "Observe one artifact source without reading its bytes.

  Returns `{:ok? true :path :real-path :stamp :attrs}` or a rejection map with
  `:error` and `:check`.  Directories, devices and symlinks are refused by
  default: a delivered artifact is a regular file, not a path alias."
  [value {:keys [follow-links?] :or {follow-links? false}}]
  (let [path (path-of value)
        a (attrs path follow-links?)]
    (cond
      (nil? a)
      {:ok? false
       :check {:check :source-exists :ok? false}
       :error {:code :ARTIFACT_SOURCE_MISSING
               :message "artifact source does not exist"
               :path (str path)}}

      (and (not follow-links?) (.isSymbolicLink a))
      {:ok? false
       :check {:check :source-regular :ok? false}
       :error {:code :ARTIFACT_SOURCE_NOT_REGULAR
               :message "artifact source is a symbolic link; pass :follow-links? true to deliver its target"
               :path (str path)}}

      (not (.isRegularFile a))
      {:ok? false
       :check {:check :source-regular :ok? false}
       :error {:code :ARTIFACT_SOURCE_NOT_REGULAR
               :message "artifact source is not a regular file"
               :path (str path)}}

      :else
      {:ok? true
       :path path
       :real-path (try (.toRealPath path no-link-options)
                       (catch Exception _ path))
       :stamp (stamp/stamp-of-attrs a)
       :modified-at (str (.toInstant (.lastModifiedTime a)))
       :attrs a})))

(defn source-stable?
  "Compare a fresh observation with the snapshot taken before copying."
  [snapshot]
  (let [path (:path snapshot)
        a (attrs path true)
        current (when (and a (.isRegularFile a))
                  (stamp/stamp-of-attrs a))]
    (if (and current (stamp/same-stamp? (:stamp snapshot) current))
      {:check :source-stable :ok? true :stamp current}
      {:check :source-stable
       :ok? false
       :before (:stamp snapshot)
       :after current})))

;; ---------------------------------------------------------------------------
;; Text decoding

(defn valid-utf8?
  "True when BYTES decode as UTF-8 with no replacement characters."
  [^bytes bytes]
  (try
    (let [decoder (doto (.newDecoder StandardCharsets/UTF_8)
                    (.onMalformedInput CodingErrorAction/REPORT)
                    (.onUnmappableCharacter CodingErrorAction/REPORT))]
      (.decode decoder (java.nio.ByteBuffer/wrap bytes))
      true)
    (catch CharacterCodingException _ false)))

(defn read-prefix
  "Read at most LIMIT bytes from IN without closing it."
  [^InputStream in limit]
  (let [limit (int (max 0 (long limit)))
        out (java.io.ByteArrayOutputStream. (min limit 8192))
        buffer (byte-array (max 1 (min 8192 limit)))]
    (loop [remaining limit]
      (when (pos? remaining)
        (let [n (.read in buffer 0 (min remaining (alength buffer)))]
          (when (pos? n)
            (.write out buffer 0 n)
            (recur (- remaining n))))))
    (.toByteArray out)))

(defn encoding-check
  "Build the openability check for PREFIX under MEDIA-TYPE.

  Textual types must decode as UTF-8; binary types are accepted as-is.  A
  caller that detected the media type after reading the prefix should replace
  the provisional `:encoding` check with this one."
  [media-type ^bytes prefix]
  (let [textual? (textual-media-type? media-type)
        utf8? (valid-utf8? prefix)]
    {:check :encoding
     :ok? (or (not textual?) utf8?)
     :textual? textual?
     :utf8? utf8?}))

;; ---------------------------------------------------------------------------
;; Blob checks

(defn check-blob!
  "Prove that a finalized blob is present, matches its content ID and size,
  and can be opened.  Returns `{:ok? true :checks [...] :metadata ... :prefix
  ...}`; throws on an expected blob failure after recording the check."
  [root id {:keys [expected-size expected-sha256 media-type text-check-bytes]
            :or {text-check-bytes default-text-check-bytes}}]
  (let [text-check-bytes (long (or text-check-bytes default-text-check-bytes))
        checks (atom [])
        record! (fn [check] (swap! checks conj check))
        snapshot! (fn [] @checks)]
    (try
      (let [verified (try
                       (blob/verify! root id)
                       (catch clojure.lang.ExceptionInfo t
                         (if (= :BLOB_NOT_FOUND (blob/error-code t))
                           (do
                             (record! {:check :blob-exists :ok? false})
                             (err/fail! :ARTIFACT_BLOB_MISSING
                                        "finalized blob does not exist"
                                        {:content-id id :checks (snapshot!)}))
                           (throw t))))
            metadata verified]
        (do
            (record! {:check :blob-exists :ok? true})
            (when (and (some? expected-size)
                       (not= (long expected-size) (long (:size metadata))))
              (record! {:check :size-matches :ok? false
                        :expected expected-size :actual (:size metadata)})
              (err/fail! :ARTIFACT_SIZE_MISMATCH
                         "blob size does not match the observed source size"
                         {:content-id id
                          :expected expected-size
                          :actual (:size metadata)
                          :checks (snapshot!)}))
            (record! {:check :size-matches :ok? true
                      :size (:size metadata)})
            (when (and (some? expected-sha256)
                       (not= (str/lower-case (str expected-sha256))
                             (str/lower-case (str (:sha256 metadata)))))
              (record! {:check :digest-matches :ok? false
                        :expected expected-sha256
                        :actual (:sha256 metadata)})
              (err/fail! :ARTIFACT_INTEGRITY
                         "blob digest does not match the observed source digest"
                         {:content-id id
                          :expected-sha256 expected-sha256
                          :actual-sha256 (:sha256 metadata)
                          :checks (snapshot!)}))
            (when-not (:verified? verified)
              (record! {:check :content-addressable :ok? false})
              (err/fail! :ARTIFACT_INTEGRITY
                         "blob verifier did not confirm the object"
                         {:content-id id
                          :verified verified
                          :checks (snapshot!)}))
            (record! {:check :content-addressable :ok? true
                      :sha256 (:sha256 verified)
                      :verified? true})
            (let [{:keys [stream size]} (blob/open-stream root id)]
              (try
                (let [prefix (read-prefix stream text-check-bytes)]
                  (record! {:check :blob-readable :ok? true
                            :bytes (alength ^bytes prefix)})
                  (let [text? (textual-media-type? media-type)
                        utf8? (valid-utf8? prefix)]
                    (when (and text? (not utf8?))
                      (record! {:check :encoding :ok? false
                                :expected "UTF-8"
                                :observed :invalid})
                      (err/fail! :ARTIFACT_ENCODING
                                 "text artifact does not decode as UTF-8"
                                 {:content-id id
                                  :media-type media-type
                                  :checks (snapshot!)}))
                    (record! {:check :encoding :ok? true
                              :textual? text?
                              :utf8? utf8?})
                    {:ok? true
                     :checks (snapshot!)
                     :metadata metadata
                     :prefix prefix
                     :size size}))
                (finally
                  (.close ^InputStream stream))))))
      (catch clojure.lang.ExceptionInfo t
        (if (err/expected? t)
          (throw (ex-info (.getMessage t)
                          (assoc (ex-data t)
                                 :checks (or (:checks (ex-data t))
                                             (snapshot!)))
                          t))
          (throw t))))))

(defn checked-at
  []
  (str (Instant/now)))
