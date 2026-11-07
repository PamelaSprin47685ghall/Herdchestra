(ns hc.server.blob-store.content
  "Stable content identity for managed blobs.

  A content ID is `sha256-<64 lowercase hex characters>`.  The algorithm is
  part of the ID so a later digest change cannot silently reinterpret old
  objects.  The digest is a function of the bytes alone; size is recorded as
  verified metadata rather than folded into the ID, because the digest already
  determines the bytes and therefore the size.

  Identity is deliberately canonical: uppercase hex, alternate prefixes and
  whitespace are rejected.  Accepting them would create two names for one
  object and let cleanup mistake the second name for a separate blob."
  (:require [hc.server.blob-store.error :as err])
  (:import (java.io InputStream)
           (java.nio.file Files OpenOption Path)
           (java.security MessageDigest)
           (java.util HexFormat)))

(set! *warn-on-reflection* true)

(def algorithm
  "The only digest algorithm this store admits."
  :sha256)

(def algorithm-name "sha256")

(def id-prefix "sha256-")

(def hex-length 64)

(def id-pattern
  #"^sha256-[0-9a-f]{64}$")

(def hex-pattern
  #"^[0-9a-f]{64}$")

(defn new-digest
  "A fresh SHA-256 MessageDigest."
  ^MessageDigest []
  (MessageDigest/getInstance "SHA-256"))

(defn hexify
  "Lowercase hexadecimal text for `bytes`."
  [^bytes bytes]
  (.formatHex (HexFormat/of) bytes))

(defn valid-id?
  "True only for the canonical content-ID form."
  [id]
  (and (string? id)
       (boolean (re-matches id-pattern id))))

(defn assert-id!
  "Return `id` when canonical, otherwise fail with `:BLOB_INVALID_ID`."
  [id]
  (if (valid-id? id)
    id
    (err/fail! :BLOB_INVALID_ID
               "Blob content ID is not canonical"
               {:id id :pattern (str id-pattern)})))

(defn valid-hex?
  "True for exactly 64 lowercase hexadecimal characters."
  [hex]
  (and (string? hex)
       (boolean (re-matches hex-pattern hex))))

(defn id-of-hex
  "Build a canonical content ID from a SHA-256 hex digest."
  [hex]
  (if (valid-hex? hex)
    (str id-prefix hex)
    (err/fail! :BLOB_INVALID_DIGEST
               "SHA-256 digest is not 64 lowercase hexadecimal characters"
               {:sha256 hex})))

(defn id->hex
  "Extract the SHA-256 hex digest from a canonical content ID."
  [id]
  (assert-id! id)
  (subs id (count id-prefix)))

(defn id-of-digest
  "Finish `digest` and build its content ID."
  [^MessageDigest digest]
  (id-of-hex (hexify (.digest digest))))

(defn id-of-bytes
  "The content ID of `bytes`."
  [^bytes bytes]
  (let [digest (new-digest)]
    (.update digest bytes)
    (id-of-digest digest)))

(defn id-of-stream
  "Read `in` to EOF and return `{:id :sha256 :size}`.

  The stream is not closed.  Reading consumes it; callers that need the bytes
  afterwards must buffer or reopen."
  [^InputStream in]
  (let [digest (new-digest)
        buffer (byte-array 65536)]
    (loop [size 0]
      (let [n (.read in buffer)]
        (if (neg? n)
          (let [hex (hexify (.digest digest))]
            {:id (id-of-hex hex) :sha256 hex :size size})
          (do
            (.update digest buffer 0 n)
            (recur (+ size n))))))))

(defn digest-file
  "Stream `path` through SHA-256.

  Returns `{:sha256 <hex> :size <bytes>}`.  The file is opened read-only and
  closed before return."
  [^Path path]
  (with-open [in ^InputStream (Files/newInputStream path (make-array OpenOption 0))]
    (let [digest (new-digest)
          buffer (byte-array 65536)]
      (loop [size 0]
        (let [n (.read in buffer)]
          (if (neg? n)
            {:sha256 (hexify (.digest digest)) :size size}
            (do
              (.update digest buffer 0 n)
              (recur (+ size n)))))))))

(defn verify-bytes
  "Fail with `:BLOB_INTEGRITY` unless `bytes` match `id`."
  [id ^bytes bytes]
  (let [id (assert-id! id)
        actual (id-of-bytes bytes)
        expected-size (alength bytes)]
    (when-not (= id actual)
      (err/fail! :BLOB_INTEGRITY
                 "Blob bytes do not match their content ID"
                 {:id id :actual-id actual :size expected-size}))
    {:id id :algorithm algorithm :sha256 (id->hex id) :size expected-size}))

(defn verify-file
  "Fail with `:BLOB_INTEGRITY` unless `path` matches `id`.

  Returns `{:id :algorithm :sha256 :size}` without reading the file into
  memory."
  [id ^Path path]
  (let [id (assert-id! id)
        actual (digest-file path)
        expected-hex (id->hex id)]
    (when-not (= expected-hex (:sha256 actual))
      (err/fail! :BLOB_INTEGRITY
                 "Blob file does not match its content ID"
                 {:id id
                  :path (str path)
                  :expected-sha256 expected-hex
                  :actual-sha256 (:sha256 actual)}))
    {:id id :algorithm algorithm :sha256 expected-hex :size (:size actual)}))

(defn metadata
  "Build the public metadata map for a finalized object."
  [id size path mtime-ms]
  {:id (assert-id! id)
   :algorithm algorithm
   :sha256 (id->hex id)
   :size size
   :path (str path)
   :mtime-ms mtime-ms})
