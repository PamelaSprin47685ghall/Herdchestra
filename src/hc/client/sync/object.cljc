(ns hc.client.sync.object
  "Pure helpers for large AG-UI state objects.

  The server replaces an oversized `STATE_SNAPSHOT` or `STATE_DELTA` payload
  with a small reference.  The reference names an HTTP object, its EDN
  encoding, size and a domain-separated SHA-256 digest.  This namespace
  validates the reference and decodes the EDN text; it performs no I/O and
  never treats unverified bytes as a server baseline.  The transport layer
  (`hc.client.workbench.sync-bridge`) fetches the bytes, verifies the digest
  and only then calls `hc.client.sync/complete-object`."
  (:require [clojure.string :as str]
            #?(:clj [clojure.edn :as edn]
               :cljs [cljs.reader :as reader])))

(def object-tag
  "Domain separation between the wire digest and the blob-store content ID.
  The server hashes this tag followed by the UTF-8 payload bytes.  The client
  must use the same tag; changing it is a protocol change."
  "hc/state-object/v1\n")

(def encoding
  "The only wire encoding for a state object."
  "edn")

(def max-object-bytes
  "Client-side sanity ceiling for one fetched object.  The server enforces
  its own configured limit."
  (* 128 1024 1024))

(def ^:private token-pattern #"^[0-9a-f]{32}$")
(def ^:private digest-pattern #"^sha256-[0-9a-f]{64}$")

(defn- field
  [m k]
  (when (map? m)
    (if (contains? m k)
      (get m k)
      (get m (name k)))))

(defn- kind-of
  [value]
  (when (or (keyword? value) (string? value))
    (-> value
        name
        str/lower-case
        (str/replace "_" "-")
        keyword)))

(defn token?
  [value]
  (and (string? value)
       (boolean (re-matches token-pattern value))))

(defn digest?
  [value]
  (and (string? value)
       (boolean (re-matches digest-pattern value))))

(defn object-url
  "Return the gateway URL for TOKEN."
  [token]
  (str "/sync-objects/" token))

(defn valid-ref
  "Validate REF for KIND at EVENT-EPOCH and EVENT-SEQ.

  Returns `{:ok? true :ref normalized-ref}` or
  `{:ok? false :error {...}}`.  A reference is usable only at the exact
  boundary carried by its event; the client never applies it at a later
  sequence."
  [kind ref event-epoch event-seq]
  (cond
    (not (map? ref))
    {:ok? false
     :error {:code :STATE_OBJECT_REF_INVALID
             :message "object reference must be a map"}}

    (not= kind (kind-of (field ref :kind)))
    {:ok? false
     :error {:code :STATE_OBJECT_REF_INVALID
             :message "object kind does not match the event"
             :expected kind
             :actual (field ref :kind)}}

    (not= event-epoch (field ref :epoch))
    {:ok? false
     :error {:code :STATE_OBJECT_REF_INVALID
             :message "object epoch does not match the event"
             :expected event-epoch
             :actual (field ref :epoch)}}

    (not= event-seq (field ref :seq))
    {:ok? false
     :error {:code :STATE_OBJECT_REF_INVALID
             :message "object sequence does not match the event"
             :expected event-seq
             :actual (field ref :seq)}}

    (not (token? (field ref :token)))
    {:ok? false
     :error {:code :STATE_OBJECT_REF_INVALID
             :message "object token must be 32 lowercase hexadecimal characters"}}

    (not (digest? (field ref :digest)))
    {:ok? false
     :error {:code :STATE_OBJECT_REF_INVALID
             :message "object digest must be a canonical sha256 value"}}

    (not (and (integer? (field ref :size))
              ;; Compare without `long`: a malicious BigInt would throw
              ;; instead of failing validation.
              (<= 0 (field ref :size) max-object-bytes)))
    {:ok? false
     :error {:code :STATE_OBJECT_REF_INVALID
             :message "object size is missing or outside the client limit"
             :size (field ref :size)
             :limit max-object-bytes}}

    (not= encoding (field ref :encoding))
    {:ok? false
     :error {:code :STATE_OBJECT_REF_INVALID
             :message "object encoding is not supported"
             :expected encoding
             :actual (field ref :encoding)}}

    :else
    {:ok? true
     :ref {:token (field ref :token)
           :digest (field ref :digest)
           :size (long (field ref :size))
           :encoding (field ref :encoding)
           :kind kind
           :epoch (field ref :epoch)
           :seq (field ref :seq)}}))

(defn payload-shape?
  "True when VALUE has the shape KIND requires."
  [kind value]
  (case kind
    :state-snapshot (map? value)
    :state-delta (sequential? value)
    false))

(defn decode-text
  "Decode one EDN text into `{:ok? true :value v}` or
  `{:ok? false :error {...}}`.  Integrity is established by the caller
  against the reference digest before this function is used."
  [text]
  (try
    {:ok? true
     :value #?(:clj (edn/read-string text)
               :cljs (reader/read-string text))}
    (catch #?(:clj Throwable :cljs :default) t
      {:ok? false
       :error {:code :STATE_OBJECT_DECODE_FAILED
               :message (or #?(:clj (.getMessage ^Throwable t)
                               :cljs (.-message t))
                            (str t))}})))
