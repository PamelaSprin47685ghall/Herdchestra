(ns hc.server.extensions.common
  "Small shared primitives for extension installation.

  The functions here are deliberately dull: stable digests, safe relative
  paths, bounded byte reads, name rules and secret classification.  They do
  not touch a catalog and they never decide whether a capability is enabled."
  (:require [clojure.string :as str]
            [hc.server.extensions.error :as error])
  (:import (java.nio ByteBuffer)
           (java.nio.charset CharacterCodingException CodingErrorAction
                             StandardCharsets)
           (java.nio.file Files LinkOption Path)
           (java.security MessageDigest)
           (java.util UUID)))

(def ^:private hex-digits "0123456789abcdef")

(defn bytes->hex
  ^String [^bytes bytes]
  (let [sb (StringBuilder. (* 2 (alength bytes)))]
    (doseq [b bytes]
      (let [v (bit-and (int b) 0xff)]
        (.append sb (.charAt hex-digits (bit-shift-right v 4)))
        (.append sb (.charAt hex-digits (bit-and v 0x0f)))))
    (str sb)))

(defn sha256
  "Lowercase SHA-256 hex of a byte array."
  ^String [^bytes bytes]
  (bytes->hex (.digest (MessageDigest/getInstance "SHA-256") bytes)))

(defn digest-id
  "Stable content ID used by skill file metadata."
  [^bytes bytes]
  (str "sha256-" (sha256 bytes)))

(defn utf8-bytes
  ^bytes [^String text]
  (.getBytes text StandardCharsets/UTF_8))

(defn bytes->utf8
  "Decode UTF-8 strictly.  Returns nil for malformed input."
  [^bytes bytes]
  (try
    (let [decoder (doto (.newDecoder StandardCharsets/UTF_8)
                    (.onMalformedInput CodingErrorAction/REPORT)
                    (.onUnmappableCharacter CodingErrorAction/REPORT))]
      (str (.decode decoder (ByteBuffer/wrap bytes))))
    (catch CharacterCodingException _ nil)))

(defn valid-utf8?
  [^bytes bytes]
  (some? (bytes->utf8 bytes)))

(defn now-ms
  []
  (System/currentTimeMillis))

(defn new-id
  [prefix]
  (str (name prefix) "-" (UUID/randomUUID)))

(defn blank->nil
  [value]
  (when (and (string? value) (not (str/blank? value)))
    (str/trim value)))

(defn control-char?
  [c]
  (let [n (int c)]
    (or (< n 0x20) (= n 0x7f))))

(defn safe-name
  "A user-visible capability name: trimmed, 1..80 characters, no controls."
  [value]
  (let [name (some-> value str str/trim)]
    (cond
      (str/blank? name)
      (error/fail! :EXTENSION_NAME_REQUIRED "name must not be blank" {})

      (> (count name) 80)
      (error/fail! :EXTENSION_NAME_TOO_LONG
                   "name must be at most 80 characters"
                   {:name name :length (count name)})

      (some control-char? name)
      (error/fail! :EXTENSION_NAME_INVALID
                   "name must not contain control characters"
                   {:name name})

      :else name)))

(defn unique-name
  "Return `base`, or `base-2`, `base-3`, ... not present in `names`."
  [names base]
  (let [names (set names)]
    (if-not (contains? names base)
      base
      (loop [n 2]
        (let [candidate (str base "-" n)]
          (if (contains? names candidate)
            (recur (inc n))
            candidate))))))

(defn normalize-relative-path
  "Normalize one POSIX-style relative path or refuse it.

  Backslashes are rejected rather than translated: a Windows-authored archive
  must be repacked with `/`, because silently rewriting separators makes a
  traversal check depend on the platform.  Absolute paths, drive letters,
  empty segments and `..` are refused."
  [value]
  (when-not (string? value)
    (error/fail! :EXTENSION_PATH_INVALID "path must be a string"
                 {:path value :class (some-> value class .getName)}))
  (let [path value]
    (cond
      (str/blank? path)
      (error/fail! :EXTENSION_PATH_INVALID "path must not be blank" {:path path})

      (some #(= \u0000 %) path)
      (error/fail! :EXTENSION_PATH_INVALID "path must not contain NUL" {:path path})

      (str/includes? path "\\")
      (error/fail! :EXTENSION_PATH_INVALID
                   "path must use '/' separators, not backslashes"
                   {:path path})

      (str/starts-with? path "/")
      (error/fail! :EXTENSION_PATH_ABSOLUTE "absolute paths are not allowed"
                   {:path path})

      (re-find #"^[A-Za-z]:" path)
      (error/fail! :EXTENSION_PATH_ABSOLUTE "drive-letter paths are not allowed"
                   {:path path})

      (str/starts-with? path "//")
      (error/fail! :EXTENSION_PATH_ABSOLUTE "UNC paths are not allowed"
                   {:path path})

      :else
      (let [segments (str/split path #"/" -1)]
        (when (some #(= ".." %) segments)
          (error/fail! :EXTENSION_PATH_TRAVERSAL
                       "path must not contain '..'"
                       {:path path}))
        (let [segments (remove #(= "." %) segments)]
          (when (empty? segments)
            (error/fail! :EXTENSION_PATH_INVALID
                         "path must name a file"
                         {:path path}))
          (when (some str/blank? segments)
            (error/fail! :EXTENSION_PATH_INVALID
                         "path must not contain empty segments"
                         {:path path}))
          (str/join "/" segments))))))

(defn safe-relative-path?
  [value]
  (try
    (some? (normalize-relative-path value))
    (catch clojure.lang.ExceptionInfo t
      (if (error/expected? t) false (throw t)))))

(defn under-root?
  "True when `path` is the same as, or inside, `root` after normalization.
  Both arguments may be Paths or strings."
  [root path]
  (let [^Path root (.normalize (.toAbsolutePath (if (instance? Path root) root (Path/of (str root) (make-array String 0)))))
        ^Path p (.normalize (.toAbsolutePath (if (instance? Path path) path (Path/of (str path) (make-array String 0)))))]
    (.startsWith p root)))

(defn read-limited
  "Read at most `limit` bytes from an InputStream.  Refuse a longer stream
  instead of truncating it: an installation must not silently drop bytes."
  ^bytes [in limit]
  (with-open [input ^java.io.InputStream in]
    (let [buffer (byte-array 8192)
          out (java.io.ByteArrayOutputStream.)]
      (loop []
        (let [n (.read input buffer)]
          (if (neg? n)
            (.toByteArray out)
            (do
              (when (> (+ (.size out) n) limit)
                (error/fail! :EXTENSION_TOO_LARGE
                             "content exceeds the size limit"
                             {:limit limit}))
              (.write out buffer 0 n)
              (recur))))))))

(defn read-file-limited
  ^bytes [path limit]
  (read-limited (Files/newInputStream (Path/of (str path) (make-array String 0))
                                      (make-array java.nio.file.OpenOption 0))
                limit))

(def ^:private secret-key-pattern
  #"(?i)(authorization|api[-_]?key|access[-_]?token|refresh[-_]?token|token|secret|password|passwd|cookie|session[-_]?id|private[-_]?key)")

(defn secret-key?
  "Conservative classification used for masking and import warnings.  A false
  positive only hides a value; a false negative could leak a credential, so
  the pattern is intentionally broad."
  [key]
  (boolean (re-find secret-key-pattern (str key))))

(defn redact-value
  [value]
  (if (or (nil? value) (and (string? value) (str/blank? value)))
    value
    "***"))

(defn redact-map
  "Mask values whose keys look secret.  Returns [redacted has-secrets?]."
  [m]
  (reduce-kv (fn [[out has?] k v]
               [(assoc out k (if (secret-key? k) (redact-value v) v))
                (or has? (secret-key? k))])
             [{} false]
             (or m {})))

(defn redact-text
  "Replace known secret values in a diagnostic string."
  [text values]
  (reduce (fn [s v]
            (if (and (string? v) (not (str/blank? v)))
              (str/replace s v "***")
              s))
          (or text "")
          values))

(defn truncate
  [s n]
  (let [s (str s)]
    (if (> (count s) n)
      (str (subs s 0 n) "…")
      s)))
