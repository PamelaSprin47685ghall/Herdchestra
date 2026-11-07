(ns hc.server.gateway.sync-object
  "Controlled large-object transport for AG-UI state events.

  A `STATE_SNAPSHOT` or `STATE_DELTA` whose encoded event exceeds the Sente
  boundary is not squeezed through the channel and the boundary is not raised.
  The payload is serialized as EDN, stored through the gateway's
  `BlobStorePort`, and replaced by a small reference event:

      {:type \"STATE_SNAPSHOT\" :epoch e :seq s
       :snapshot-ref {:token ... :digest ... :size ... :encoding \"edn\"
                      :kind :state-snapshot :epoch e :seq s}}

  The reference is a capability: the token is random, is delivered only over
  the authenticated Sente channel, and is valid only while its epoch is the
  current feed epoch.  The digest is domain-separated from the blob-store
  content ID, so the wire reference does not name a `/blobs` URL.  The bytes
  themselves remain content-addressed and immutable in the store.

  The registry is process-local and bounded.  It holds no authority over the
  feed: the feed advances epoch and sequence before any reference is sent, and
  the client applies the fetched object at that exact boundary.  A missing,
  evicted or stale object is an honest failure that forces a fresh baseline;
  it is never replaced by an empty value or a partial tree."
  (:require [clojure.edn :as edn]
            [hc.server.blob-store :as blob]
            [hc.server.gateway.ports :as ports])
  (:import [java.io ByteArrayInputStream]
           [java.nio.charset StandardCharsets]
           [java.security MessageDigest SecureRandom]))

(def encoding
  "The only wire encoding for a state object."
  "edn")

(def object-tag
  "Domain separation between the wire digest and the blob content ID.  The
  server and client hash this tag followed by the UTF-8 payload bytes.
  Changing it is a protocol change."
  "hc/state-object/v1\n")

(def default-max-object-bytes
  "Default ceiling for one serialized state object."
  (* 64 1024 1024))

(def default-max-objects
  "Default number of live state-object references kept for slow clients."
  128)

(def ^:private token-bytes 16)
(def ^:private rng (SecureRandom.))

(defn- hex
  [^bytes bytes]
  (let [sb (StringBuilder.)]
    (doseq [b bytes]
      (.append sb (format "%02x" (bit-and (int b) 0xff))))
    (str sb)))

(defn new-token
  "Return a fresh 128-bit capability token as 32 lowercase hex characters."
  []
  (let [bytes (byte-array token-bytes)]
    (.nextBytes rng bytes)
    (hex bytes)))

(defn encode
  "Serialize VALUE to the wire bytes.  Throws when VALUE has no EDN
  representation."
  [value]
  (.getBytes ^String (pr-str value) StandardCharsets/UTF_8))

(defn decode
  "Read one EDN value from BYTES.  Used by tests and diagnostics; the client
  owns its own decoder."
  [^bytes bytes]
  (edn/read-string (String. bytes StandardCharsets/UTF_8)))

(defn digest-bytes
  "Return the domain-separated wire digest of BYTES."
  [^bytes bytes]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (.update digest (.getBytes ^String object-tag StandardCharsets/UTF_8))
    (.update digest bytes)
    (str "sha256-" (hex (.digest digest)))))

(defn create-registry
  "Create an empty process-local state-object registry."
  []
  {:state (atom {:by-token {}
                 :order []
                 :cache {}})})

(defn- prune
  "Keep the newest MAX-OBJECTS tokens and their cache entries."
  [state max-objects]
  (let [max-objects (max 0 (long max-objects))
        order (vec (:order state))
        overflow (- (count order) max-objects)]
    (if (pos? overflow)
      (let [dropped (subvec order 0 overflow)
            drop-set (set dropped)
            by-token (apply dissoc (:by-token state) dropped)
            cache (into {}
                        (remove (fn [[_ token]] (contains? drop-set token))
                                (:cache state)))]
        {:by-token by-token
         :order (subvec order overflow)
         :cache cache})
      state)))

(defn- register!
  "Register ENTRY and return the final entry, reusing an existing token when
  the same kind/epoch/sequence/content is already registered."
  [registry entry max-objects]
  (let [state-ref (:state registry)]
    (loop []
      (let [state @state-ref
            cache-key [(:kind entry) (:epoch entry) (:seq entry)
                       (:blob-id entry)]
            existing (get-in state [:cache cache-key])
            reuse? (and existing (contains? (:by-token state) existing))
            token (if reuse? existing (:token entry))
            entry' (assoc entry :token token :cache-key cache-key)
            order (if (some #{token} (:order state))
                    (:order state)
                    (conj (vec (:order state)) token))
            next-state (prune (-> state
                                  (assoc-in [:by-token token] entry')
                                  (assoc-in [:cache cache-key] token)
                                  (assoc :order order))
                              max-objects)]
        (if (compare-and-set! state-ref state next-state)
          (get-in next-state [:by-token token])
          (recur))))))

(defn lookup
  "Return the registry entry for TOKEN, or nil."
  [registry token]
  (when (and registry (string? token))
    (get-in @(:state registry) [:by-token token])))

(defn forget!
  "Remove TOKEN and its cache entry.  Used when the backing object vanished."
  [registry token]
  (when registry
    (swap! (:state registry)
           (fn [state]
             (let [entry (get-in state [:by-token token])
                   by-token (dissoc (:by-token state) token)
                   cache (if-let [cache-key (:cache-key entry)]
                           (dissoc (:cache state) cache-key)
                           (:cache state))]
               (assoc state
                      :by-token by-token
                      :cache cache
                      :order (filterv #(not= % token) (:order state))))))))

(defn clear!
  "Drop every reference.  The blob objects remain for the store's own
  reference-aware cleanup."
  [registry]
  (when registry
    (reset! (:state registry) {:by-token {} :order [] :cache {}})))

(def ^:private js-safe-integer
  "Largest integer a JavaScript number represents exactly.  The client is
  ClojureScript; a larger Clojure integer would silently lose precision."
  9007199254740991)

(defn- wire-value?
  "True when VALUE survives the client's EDN reader without a type or
  precision change.

  Clojure's reader accepts ratios, BigDecimal, BigInt, characters, regexes
  and large integers.  ClojureScript either coerces those to a JavaScript
  number/string or rejects them, so they are not valid wire values even when
  they round-trip on the JVM."
  [value]
  (cond
    (nil? value) true
    (or (boolean? value) (string? value) (keyword? value) (symbol? value)) true
    ;; `pr-str` writes a BigInt as `5N`.  The JVM reader round-trips it,
    ;; but a ClojureScript reader may reject the suffix or coerce it, so the
    ;; wire value would not preserve the server's type.  Reject it even when
    ;; the numeric value is inside the JavaScript safe range.
    (instance? clojure.lang.BigInt value) false
    (integer? value) (<= (- js-safe-integer) value js-safe-integer)
    (instance? Double value) true
    (instance? java.util.Date value) true
    (uuid? value) true
    (map? value) (every? (fn [[k v]] (and (wire-value? k) (wire-value? v)))
                         value)
    (set? value) (every? wire-value? value)
    (sequential? value) (every? wire-value? value)
    :else false))

(defn- round-trips?
  "True when VALUE survives the exact wire bytes and EDN decoding unchanged.

  The check runs on the UTF-8 bytes that are actually stored and digested,
  not on the pre-encoding String.  `String.getBytes` silently replaces an
  unpaired surrogate with `?`; checking the String would accept a value the
  client can never read back unchanged."
  [value ^bytes bytes]
  (try
    (= value (edn/read-string (String. bytes StandardCharsets/UTF_8)))
    (catch Throwable _ false)))

(defn store!
  "Serialize VALUE, store its bytes and register a state-object reference.

  Options:
    :kind        `:state-snapshot` or `:state-delta`
    :epoch       feed epoch assigned to the event
    :seq         feed sequence assigned to the event
    :value       payload (the complete tree or the JSON Patch operations)
    :max-bytes   serialized-size ceiling
    :max-objects live-reference ceiling

  Returns `{:ok? true :ref ref}` or `{:ok? false :error {...}}`.  A failure
  never advances the feed and never fabricates an empty object."
  [store registry {:keys [kind epoch seq value max-bytes max-objects]}]
  (cond
    (nil? store)
    {:ok? false
     :error {:code :STATE_OBJECT_STORE_UNAVAILABLE
             :message "no blob store is attached to this gateway"
             :hint "attach a BlobStorePort before publishing large state"}}

    (nil? registry)
    {:ok? false
     :error {:code :STATE_OBJECT_REGISTRY_UNAVAILABLE
             :message "the gateway has no state-object registry"}}

    :else
    (try
      (let [text (pr-str value)
            bytes (.getBytes ^String text StandardCharsets/UTF_8)
            size (alength ^bytes bytes)
            limit (long (or max-bytes default-max-object-bytes))
            object-limit (long (or max-objects default-max-objects))]
        (cond
          (not (pos? object-limit))
          {:ok? false
           :error {:code :STATE_OBJECT_TOO_LARGE
                   :message "the state-object registry limit must be positive"
                   :limit object-limit}}

          (not (wire-value? value))
          {:ok? false
           :error {:code :STATE_OBJECT_ENCODING_FAILED
                   :message (str "value contains a type or precision the "
                                 "client EDN reader cannot preserve")
                   :kind kind}}

          (not (round-trips? value bytes))
          {:ok? false
           :error {:code :STATE_OBJECT_ENCODING_FAILED
                   :message "value does not round-trip through EDN"
                   :kind kind}}

          (> size limit)
          {:ok? false
           :error {:code :STATE_OBJECT_TOO_LARGE
                   :message "state object exceeds the configured limit"
                   :size size
                   :limit limit}}

          :else
          (let [metadata (ports/put-blob! store
                                          (ByteArrayInputStream. bytes)
                                          {:kind :sync-object})
                blob-id (:id metadata)
                expected (blob/id-of-bytes bytes)]
            (if (or (not= blob-id expected)
                    (not= (long (or (:size metadata) -1)) (long size)))
              {:ok? false
               :error {:code :STATE_OBJECT_INTEGRITY
                       :message "blob store returned inconsistent metadata"
                       :expected-id expected
                       :id blob-id
                       :expected-size size
                       :size (:size metadata)}}
              (let [entry (register! registry
                                     {:token (new-token)
                                      :blob-id blob-id
                                      :digest (digest-bytes bytes)
                                      :size size
                                      :encoding encoding
                                      :kind kind
                                      :epoch epoch
                                      :seq seq}
                                     object-limit)]
                {:ok? true
                 :ref (select-keys entry
                                   [:token :digest :size :encoding :kind
                                    :epoch :seq])})))))
      (catch Throwable t
        {:ok? false
         :error (cond-> {:code :STATE_OBJECT_STORE_FAILED
                         :message (or (.getMessage ^Throwable t) (str t))}
                  (instance? clojure.lang.ExceptionInfo t)
                  (assoc :details (ex-data t)))}))))

(defn resolve-object
  "Resolve TOKEN for the current feed epoch.

  Returns `{:ok? true :entry entry}` or `{:ok? false :status n :error map}`.
  A reference from an older epoch is rejected even when its bytes still exist:
  the client must take a new baseline instead of applying a dead boundary."
  [registry current-epoch token]
  (let [entry (lookup registry token)]
    (cond
      (nil? entry)
      {:ok? false
       :status 404
       :error {:code :SYNC_OBJECT_NOT_FOUND
               :message "state object is not registered or has been evicted"}}

      (and (integer? current-epoch)
           (integer? (:epoch entry))
           (< (long (:epoch entry)) (long current-epoch)))
      {:ok? false
       :status 410
       :error {:code :SYNC_OBJECT_STALE_EPOCH
               :message "state object belongs to an older epoch"
               :epoch (:epoch entry)
               :current-epoch current-epoch
               :action :resync-state}}

      :else
      {:ok? true :entry entry})))
