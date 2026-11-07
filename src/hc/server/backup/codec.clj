(ns hc.server.backup.codec
  "Deterministic EDN and the binary backup container.

  The container is deliberately tiny:

      magic \"HCBK\" | container version (1 byte) | header length (u32)
      header EDN (UTF-8) | payload bytes

  The header length is bounded, and the header is canonical EDN so the same
  logical header always produces the same bytes.  Canonical bytes are also
  used as AES-GCM associated data."
  (:require [clojure.edn :as edn]
            [hc.server.backup.error :as err])
  (:import (java.io ByteArrayInputStream ByteArrayOutputStream DataInputStream
                    DataOutputStream InputStream)
           (java.nio.charset StandardCharsets)
           (java.security MessageDigest)
           (java.util Base64)
           (javax.crypto Mac)
           (javax.crypto.spec SecretKeySpec)))

(def ^"[B" magic-bytes
  (.getBytes "HCBK" StandardCharsets/US_ASCII))

(def container-version
  1)

(def max-header-bytes
  (* 16 1024 1024))

(defn sha256-bytes
  ^bytes [^bytes data]
  (.digest (MessageDigest/getInstance "SHA-256") data))

(defn hex
  "Lower-case hexadecimal encoding of a byte array."
  [^bytes data]
  (let [sb (StringBuilder. (* 2 (alength data)))]
    (doseq [b data]
      (.append sb (format "%02x" (bit-and (int b) 0xff))))
    (.toString sb)))

(defn sha256-hex
  "Lower-case SHA-256 hex of a byte array."
  [^bytes data]
  (hex (sha256-bytes data)))

(defn sha256-string
  [^String s]
  (sha256-hex (.getBytes s StandardCharsets/UTF_8)))

(defn hmac-sha256
  ^bytes [^bytes key ^bytes data]
  (let [mac (Mac/getInstance "HmacSHA256")]
    (.init mac (SecretKeySpec. key "HmacSHA256"))
    (.doFinal mac data)))

(defn constant-time=
  [^bytes a ^bytes b]
  (MessageDigest/isEqual a b))

(defn base64-encode
  [^bytes data]
  (.encodeToString (Base64/getEncoder) data))

(defn base64-decode
  [^String s]
  (try
    (.decode (Base64/getDecoder) s)
    (catch IllegalArgumentException e
      (err/fail! :BACKUP_FORMAT_INVALID
                 "Backup contains invalid base64 data"
                 {:value (subs s 0 (min 32 (count s)))}
                 e))))

(defn utf8-bytes
  ^bytes [^String s]
  (.getBytes s StandardCharsets/UTF_8))

(defn utf8-string
  [^bytes data]
  (String. data StandardCharsets/UTF_8))

(defn- key-order
  [a b]
  (compare [(pr-str a) (str (class a))]
           [(pr-str b) (str (class b))]))

(defn canonicalize
  "Recursively sort map keys and set members.  The result prints identically
  on every JVM, which is required for header digests and GCM associated data."
  [value]
  (cond
    (map? value)
    (into (sorted-map-by key-order)
          (map (fn [[k v]] [k (canonicalize v)]))
          value)

    (set? value)
    (into (sorted-set-by key-order)
          (map canonicalize)
          value)

    (vector? value)
    (mapv canonicalize value)

    (seq? value)
    (apply list (map canonicalize value))

    (or (nil? value)
        (string? value)
        (keyword? value)
        (symbol? value)
        (number? value)
        (boolean? value)
        (char? value))
    value

    :else
    (err/fail! :BACKUP_FORMAT_INVALID
               "Value cannot be represented in the backup EDN payload"
               {:type (str (class value))})))

(defn canonical-string
  [value]
  (pr-str (canonicalize value)))

(defn canonical-bytes
  ^bytes [value]
  (utf8-bytes (canonical-string value)))

(defn edn-read
  "Read one EDN value.  Unknown tagged literals are rejected; the backup
  format never needs executable or host-specific readers."
  [x]
  (let [s (if (bytes? x) (utf8-string x) (str x))]
    (try
      (edn/read-string
       {:readers {}
        :default (fn [tag _]
                   (err/fail! :BACKUP_FORMAT_INVALID
                              "Backup contains an unsupported EDN tag"
                              {:tag tag}))}
       s)
      (catch clojure.lang.ExceptionInfo e
        (throw e))
      (catch Exception e
        (err/fail! :BACKUP_FORMAT_INVALID
                   "Backup header or payload is not readable EDN"
                   {:offset (ex-data e)}
                   e)))))

;; ---------------------------------------------------------------------------
;; Container

(defn- write-u32!
  [^DataOutputStream out n]
  (.writeInt out (int n)))

(defn- read-u32
  [^DataInputStream in]
  (.readInt in))

(defn- read-exact!
  ^bytes [^DataInputStream in n]
  (let [buf (byte-array n)]
    (.readFully in buf)
    buf))

(defn encode-container
  "Build one container from already-encoded header and payload bytes."
  ^bytes [^bytes header-bytes ^bytes payload]
  (let [out (ByteArrayOutputStream.
             (+ 9 (alength header-bytes) (alength payload)))
        data (DataOutputStream. out)]
    (.write data ^bytes magic-bytes)
    (.writeByte data container-version)
    (write-u32! data (alength header-bytes))
    (.write data header-bytes)
    (.write data ^bytes payload)
    (.flush data)
    (.toByteArray out)))

(defn read-container-header
  "Read the container prefix from an input stream and leave the stream at the
  first payload byte.  The stream is not closed."
  [^InputStream input]
  (try
    (let [data (DataInputStream. input)
          magic (read-exact! data 4)]
      (when-not (java.util.Arrays/equals ^bytes magic ^bytes magic-bytes)
        (err/fail! :BACKUP_FORMAT_INVALID
                   "Not a Herdchestra backup container"
                   {:magic (utf8-string magic)}))
      (let [version (.readUnsignedByte data)
            header-length (read-u32 data)]
        (when (or (neg? header-length)
                  (> header-length max-header-bytes))
          (err/fail! :BACKUP_FORMAT_INVALID
                     "Backup header length is outside the accepted range"
                     {:header-length header-length
                      :max-header-length max-header-bytes}))
        {:container-version version
         :header-length header-length
         :header-bytes (read-exact! data header-length)}))
    (catch java.io.EOFException e
      (err/fail! :BACKUP_FORMAT_INVALID
                 "Backup container is truncated"
                 {:reason :truncated}
                 e))
    (catch java.io.IOException e
      (err/fail! :BACKUP_IO
                 "Could not read the backup container"
                 {:reason :io}
                 e))))

(defn read-container
  "Parse a complete container.  Header EDN is not interpreted here."
  [^bytes bytes]
  (with-open [input (ByteArrayInputStream. bytes)]
    (let [{:keys [container-version header-bytes]}
          (read-container-header input)
          payload (.readAllBytes input)]
      {:container-version container-version
       :header-bytes header-bytes
       :payload payload})))

(defn container?
  "Cheap prefix check for listing and diagnostics."
  [^bytes bytes]
  (and (>= (alength bytes) (alength magic-bytes))
       (java.util.Arrays/equals (java.util.Arrays/copyOf
                                        bytes
                                        (int (alength ^bytes magic-bytes)))
                                ^bytes magic-bytes)))
