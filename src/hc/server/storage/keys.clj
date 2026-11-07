(ns hc.server.storage.keys
  "Canonical key encoding for the single current-state table.

  One key grammar serves every record class, so one `transact-kv` call can
  commit documents, obligations, blob references, metadata and receipts in a
  single atomic transaction.

      d/<partition>/<session>/<kind>/<id>   domain document
      o/<partition>/<session>/<id>          open obligation
      b/<partition>/<id>                    blob reference
      m/...                                 store and partition metadata
      r/<version>/<migration-id>            commit receipt
      i/<migration-id>                      receipt index

  `<session>` is `_` for partition-scoped records.  A literal underscore in a
  segment is percent-encoded, so the sentinel cannot collide with a real name.

  Segments are UTF-8 percent-encoded.  Only `[A-Za-z0-9.:@-]` stays literal.
  The encoding is reversible and stable; `/` never appears inside a segment.
  Root AGENTS.md §7.1 keeps keys small and current-state only; this namespace
  never builds a key that embeds content or history."
  (:require [clojure.string :as str]))

(def ^:private safe-byte?
  (let [safe (set (map int "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789.:@-"))]
    (fn [^long b] (contains? safe b))))

(def ^:private hex "0123456789ABCDEF")

(defn encode-segment
  "Percent-encode one key segment.  `nil` is rejected; use `session-sentinel`
  for partition-scoped records."
  [segment]
  (when-not (string? segment)
    (throw (IllegalArgumentException. "key segment must be a string")))
  (let [bytes (.getBytes ^String segment java.nio.charset.StandardCharsets/UTF_8)
        out   (StringBuilder. (+ 8 (* 3 (alength bytes))))]
    (doseq [b bytes]
      (let [b (bit-and (long b) 0xFF)]
        (if (safe-byte? b)
          (.append out (char b))
          (do (.append out \%)
              (.append out (nth hex (bit-shift-right b 4)))
              (.append out (nth hex (bit-and b 0x0F)))))))
    (.toString out)))

(defn- hex-value [c]
  (cond
    (and (<= (int \0) (int c) (int \9)))
    (- (int c) (int \0))

    (and (<= (int \A) (int c) (int \F)))
    (+ 10 (- (int c) (int \A)))

    (and (<= (int \a) (int c) (int \f)))
    (+ 10 (- (int c) (int \a)))

    :else nil))

(defn decode-segment
  "Reverse `encode-segment`.  Returns nil on malformed input."
  [segment]
  (when (string? segment)
    (let [n   (count segment)
          out (java.io.ByteArrayOutputStream.)]
      (loop [i 0]
        (if (>= i n)
          (String. (.toByteArray out) java.nio.charset.StandardCharsets/UTF_8)
          (let [c (.charAt ^String segment i)]
            (if (= c \%)
              (if (<= (+ i 3) n)
                (let [hi (hex-value (.charAt ^String segment (inc i)))
                      lo (hex-value (.charAt ^String segment (+ i 2)))]
                  (if (and hi lo)
                    (do (.write out (bit-or (bit-shift-left (long hi) 4) (long lo)))
                        (recur (+ i 3)))
                    nil))
                nil)
              (do (.write out (int c))
                  (recur (inc i))))))))))

(def session-sentinel
  "Key segment for partition-scoped records."
  "_")

(defn session-segment [session-id]
  (if (nil? session-id) session-sentinel (encode-segment session-id)))

(def ^:private max-key-bytes 500)

(defn- key
  "Join already-encoded parts with `/` and enforce the LMDB key budget."
  [& parts]
  (let [k (str/join "/" parts)
        n (alength (.getBytes ^String k java.nio.charset.StandardCharsets/UTF_8))]
    (when (> n max-key-bytes)
      (throw (ex-info "storage key is too long"
                      {:error :STORAGE_INVALID_KEY
                       :storage/expected? true
                       :key-bytes n
                       :max-key-bytes max-key-bytes})))
    k))

(defn kind-name
  "Keyword name including its namespace, or the string itself."
  [kind]
  (if (keyword? kind)
    (subs (str kind) 1)
    (str kind)))

(defn doc-key
  "Key for one domain document."
  [partition session-id kind id]
  (key "d"
       (encode-segment partition)
       (session-segment session-id)
       (encode-segment (kind-name kind))
       (encode-segment id)))

(defn obligation-key
  "Key for one open or closed obligation."
  [partition session-id id]
  (key "o" (encode-segment partition) (session-segment session-id) (encode-segment id)))

(defn blob-ref-key
  "Key for one blob reference."
  [partition id]
  (key "b" (encode-segment partition) (encode-segment id)))

(defn partition-meta-key
  "Key for one partition metadata value.  `suffix` is a literal such as
  `\"version\"`; it is not encoded and must be one of the known suffixes."
  [partition suffix]
  (key "m" "p" (encode-segment partition) (name suffix)))

(defn receipt-key
  "Key for one commit receipt.  The zero-padded version makes the oldest
  receipt the first entry under the `r/` prefix."
  [version migration-id]
  (key "r" (format "%020d" (long version)) (encode-segment migration-id)))

(defn receipt-index-key
  "Index key from a migration ID to its receipt key."
  [migration-id]
  (key "i" (encode-segment migration-id)))

(defn prefix-range
  "LMDB closed range that contains exactly the keys starting with `prefix`."
  [prefix]
  [:closed prefix (str prefix "\uFFFF")])

(defn partition-doc-prefix [partition]
  (key "d" (encode-segment partition) ""))

(defn session-doc-prefix [partition session-id]
  (key "d" (encode-segment partition) (session-segment session-id) ""))

(defn partition-obligation-prefix [partition]
  (key "o" (encode-segment partition) ""))

(defn session-obligation-prefix [partition session-id]
  (key "o" (encode-segment partition) (session-segment session-id) ""))

(defn partition-blob-ref-prefix [partition]
  (key "b" (encode-segment partition) ""))

(defn partition-meta-prefix []
  (key "m" "p" ""))

(defn receipt-prefix []
  (key "r" ""))

(defn receipt-index-prefix []
  (key "i" ""))

(defn meta-key [suffix] (key "m" (name suffix)))

(defn partition-from-meta-key
  "Decode the partition segment of an `m/p/<partition>/<suffix>` key."
  [k]
  (let [parts (str/split k #"/")]
    (when (and (= 4 (count parts)) (= "m" (nth parts 0)) (= "p" (nth parts 1)))
      (decode-segment (nth parts 2)))))

(defn doc-key-parts
  "Decode a `d/...` key into `{:partition :session-id :kind :id}`."
  [k]
  (let [parts (str/split k #"/")]
    (when (and (= 5 (count parts)) (= "d" (nth parts 0)))
      {:partition (decode-segment (nth parts 1))
       :session-id (let [s (nth parts 2)] (when-not (= s session-sentinel) (decode-segment s)))
       :kind (keyword (decode-segment (nth parts 3)))
       :id (decode-segment (nth parts 4))})))

(defn obligation-key-parts
  "Decode an `o/...` key into `{:partition :session-id :id}`."
  [k]
  (let [parts (str/split k #"/")]
    (when (and (= 4 (count parts)) (= "o" (nth parts 0)))
      {:partition (decode-segment (nth parts 1))
       :session-id (let [s (nth parts 2)] (when-not (= s session-sentinel) (decode-segment s)))
       :id (decode-segment (nth parts 3))})))

(defn blob-ref-key-parts
  "Decode a `b/...` key into `{:partition :id}`."
  [k]
  (let [parts (str/split k #"/")]
    (when (and (= 3 (count parts)) (= "b" (nth parts 0)))
      {:partition (decode-segment (nth parts 1))
       :id (decode-segment (nth parts 2))})))
