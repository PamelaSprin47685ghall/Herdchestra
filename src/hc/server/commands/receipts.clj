(ns hc.server.commands.receipts
  "Accepted command receipts for one epoch window.

  A registry maps command-id to
  `{:command-epoch n :payload p :receipt r}` or, for a sensitive payload,
  `{:command-epoch n :payload-digest sha256 :payload-redacted? true
    :receipt r}`.  It is deliberately small and ephemeral: the current epoch
  owns it, and advancing or retiring the epoch clears it.  There is no
  history archive.

  A sensitive payload is never stored in plaintext.  The digest is compared
  for replay and payload mismatch, so idempotence survives without keeping
  the secret."
  (:require [clojure.string :as str])
  (:import (java.nio.charset StandardCharsets)
           (java.security MessageDigest)))

(def empty-registry
  "An empty registry.

  A plain map is required: command keys may be strings or
  `[scope command-id]` vectors in the same epoch.  A sorted map cannot
  compare those key types."
  {})

(defn valid-command-id?
  [command-id]
  (and (string? command-id)
       (not (str/blank? command-id))))

(defn valid-command-key?
  "A key is a command-id string or a [scope command-id] vector."
  [key]
  (or (valid-command-id? key)
      (and (vector? key)
           (= 2 (count key))
           (every? valid-command-id? key))))

(def ^:private secret-key-exact
  #{"password" "passwd" "secret" "api-key" "apikey" "token" "tokens"
    "authorization" "private-key" "credential" "credentials" "cookie"
    "bearer"})

(def ^:private secret-key-suffix
  #"(?i)(password|passwd|secret|api[-_]?key|access[-_]?token|refresh[-_]?token|auth[-_]?token|id[-_]?token|session[-_]?token|authorization|private[-_]?key|credential|cookie|bearer)")

(defn secret-key?
  [key]
  (and (or (keyword? key) (string? key))
       (let [value (str/lower-case (name key))]
         (or (contains? secret-key-exact value)
             (boolean (re-find secret-key-suffix value))))))

(defn contains-secret?
  "True when VALUE contains a map entry whose key names a secret."
  [value]
  (cond
    (map? value)
    (boolean
     (some (fn [[k v]]
             (or (secret-key? k) (contains-secret? v)))
           value))

    (coll? value)
    (boolean (some contains-secret? value))

    :else false))

(defn redact-secrets
  "Strip every secret-keyed field, recursively."
  [value]
  (cond
    (map? value)
    (into (empty value)
          (keep (fn [[k v]]
                  (when-not (secret-key? k)
                    [k (redact-secrets v)])))
          value)

    (vector? value) (mapv redact-secrets value)
    (set? value) (into #{} (map redact-secrets) value)
    (list? value) (apply list (map redact-secrets value))
    :else value))

(defn- canonical
  [value]
  (cond
    (map? value)
    (str "{"
         (str/join ","
                   (map (fn [[k v]]
                          (str (canonical k) " " (canonical v)))
                        (sort-by (comp pr-str key) value)))
         "}")

    (vector? value)
    (str "[" (str/join "," (map canonical value)) "]")

    (set? value)
    (str "#{" (str/join "," (map canonical (sort-by pr-str value))) "}")

    (list? value)
    (str "(" (str/join "," (map canonical value)) ")")

    (nil? value) "nil"
    :else (pr-str value)))

(defn payload-digest
  "Return the SHA-256 hex digest of a canonical EDN rendering of PAYLOAD."
  [payload]
  (let [digest (MessageDigest/getInstance "SHA-256")
        bytes (.digest digest
                       (.getBytes ^String (canonical payload)
                                  StandardCharsets/UTF_8))]
    (apply str (map #(format "%02x" (bit-and (int %) 0xFF)) bytes))))

(defn redact-registry
  "Replace legacy plaintext secret payloads with their canonical digest."
  [registry]
  (if-not (map? registry)
    registry
    (into (empty registry)
          (map (fn [[key entry]]
                 (if (and (map? entry)
                          (contains? entry :payload)
                          (contains-secret? (:payload entry)))
                   [key (-> entry
                            (dissoc :payload)
                            (assoc :payload-digest
                                   (payload-digest (:payload entry))
                                   :payload-redacted? true))]
                   [key entry])))
          registry)))

(defn lookup
  [registry command-id]
  (get registry command-id))

(defn replace-receipt
  "Replace the receipt of an existing command, preserving its payload or
  digest and epoch.  Returns {:ok? true :registry reg' :entry entry} or
  {:ok? false :reason :unknown :entry nil}."
  [registry command-id receipt]
  (if-let [existing (get registry command-id)]
    (let [entry (assoc existing :receipt receipt)]
      {:ok? true
       :registry (assoc registry command-id entry)
       :entry entry})
    {:ok? false
     :reason :unknown
     :entry nil}))

(defn- payload-matches?
  [entry payload]
  (cond
    (and (:payload-redacted? entry) (:payload-digest entry))
    (= (payload-digest payload) (:payload-digest entry))

    (contains? entry :payload)
    (= payload (:payload entry))

    :else false))

(defn remember
  "Record a command, or report a replay or conflict.

  When REDACT? is true the payload is stored only as a SHA-256 digest.
  Replay compares the digest; a mismatch never echoes either payload.
  Returns
  {:ok? true :registry reg' :entry entry :duplicate? bool}
  or
  {:ok? false :reason :payload-mismatch|:epoch-mismatch :entry existing}.

  An existing receipt from a different epoch is a state error. The epoch
  transition MUST clear receipts, so this branch protects the fence."
  ([registry command-id epoch payload receipt]
   (remember registry command-id epoch payload receipt false))
  ([registry command-id epoch payload receipt redact?]
   (let [registry (or registry empty-registry)]
     (if-let [existing (get registry command-id)]
       (cond
         (not= epoch (:command-epoch existing))
         {:ok? false
          :reason :epoch-mismatch
          :entry existing}

         (payload-matches? existing payload)
         {:ok? true
          :registry registry
          :entry existing
          :duplicate? true}

         :else
         {:ok? false
          :reason :payload-mismatch
          :entry existing})
       (let [entry (cond-> {:command-epoch epoch
                            :receipt receipt}
                     redact? (assoc :payload-digest (payload-digest payload)
                                    :payload-redacted? true)
                     (not redact?) (assoc :payload payload))]
         {:ok? true
          :registry (assoc registry command-id entry)
          :entry entry
          :duplicate? false})))))

(defn check
  "Return a vector of registry invariant violations."
  [registry]
  (if-not (map? registry)
    [{:invariant :receipts-not-a-map}]
    (into []
          (mapcat (fn [[command-key entry]]
                    (cond-> []
                      (not (valid-command-key? command-key))
                      (conj {:invariant :command-key-invalid
                             :command-key command-key})

                      (not (map? entry))
                      (conj {:invariant :receipt-entry-not-a-map
                             :command-key command-key})

                      (and (map? entry)
                           (not (or (contains? entry :payload)
                                    (and (contains? entry :payload-digest)
                                         (string? (:payload-digest entry))
                                         (not (str/blank? (:payload-digest entry)))))))
                      (conj {:invariant :receipt-payload-missing
                             :command-key command-key})

                      (and (map? entry)
                           (:payload-redacted? entry)
                           (not (string? (:payload-digest entry))))
                      (conj {:invariant :receipt-digest-missing
                             :command-key command-key})

                      (and (map? entry)
                           (not (contains? entry :receipt)))
                      (conj {:invariant :receipt-missing
                             :command-key command-key})

                      (and (map? entry)
                           (not (and (integer? (:command-epoch entry))
                                     (pos? (:command-epoch entry)))))
                      (conj {:invariant :receipt-epoch-invalid
                             :command-key command-key
                             :command-epoch (:command-epoch entry)}))))
          registry)))
