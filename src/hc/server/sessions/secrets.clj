(ns hc.server.sessions.secrets
  "Secure storage for provider credentials.

  Authority: AGENTS.md §7.4 旅程 1.2–1.3（引导与选模型） (multi-key connections, masked display,
  no secret in logs) and §7.1 (configuration is an adapter boundary).

  A connection stores only a `:secret-ref` and a masked display value.  This
  namespace owns the raw secret behind that pointer.  It never logs a secret,
  never returns one in a listing, and never stores one in the session or
  connection state.

  Two implementations are provided:

    - `memory-secret-store` for tests and ephemeral processes;
    - `file-secret-store` with AES-GCM encryption, a 0600 master key, and a
      0600 data file.

  The file store is a local control-plane store, not a KMS.  It protects
  against accidental plaintext disclosure and casual file reads; it does not
  claim protection from a process running as the same OS user."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.nio ByteBuffer)
           (java.nio.charset StandardCharsets)
           (java.nio.file Files LinkOption OpenOption Path StandardCopyOption
                          StandardOpenOption)
           (java.nio.file.attribute FileAttribute PosixFilePermissions)
           (java.security SecureRandom)
           (java.util Base64)
           (javax.crypto Cipher)
           (javax.crypto.spec GCMParameterSpec SecretKeySpec)))

(defprotocol SecretStore
  (put-secret!
    [store secret-id secret]
    "Store SECRET under a stable SECRET-ID and return
    `{:ok? true :secret-ref \"secret://...\"}`.  The same ID overwrites the
    previous value.  The raw secret never appears in the result.")
  (get-secret
    [store secret-ref]
    "Return `{:ok? true :secret s}` or a structured error.")
  (delete-secret!
    [store secret-ref]
    "Remove one secret.  Deleting a missing secret is idempotent.")
  (secret-exists?
    [store secret-ref]
    "True when the store can currently resolve SECRET-REF."))

(def ^:private ref-prefix "secret://")
(def ^:private id-pattern #"[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
(def ^:private nonce-bytes 12)
(def ^:private key-bytes 32)
(def ^:private gcm-tag-bits 128)

(defn- error
  [code message details]
  {:ok? false
   :error (cond-> {:code code :message message}
            (some? details) (assoc :details details))})

(defn- valid-secret-id?
  [value]
  (and (string? value)
       (boolean (re-matches id-pattern value))))

(defn secret-ref
  [secret-id]
  (str ref-prefix secret-id))

(defn- parse-secret-ref
  [value]
  (when (and (string? value) (str/starts-with? value ref-prefix))
    (let [id (subs value (count ref-prefix))]
      (when (valid-secret-id? id) id))))

(defn- b64
  [^bytes value]
  (.encodeToString (Base64/getEncoder) value))

(defn- unb64
  ^bytes [value]
  (.decode (Base64/getDecoder) ^String value))

(defn- random-bytes
  ^bytes [n]
  (let [bytes (byte-array n)]
    (.nextBytes (SecureRandom.) bytes)
    bytes))

;; ---------------------------------------------------------------------------
;; Memory adapter

(defrecord MemorySecretStore [state lock]
  SecretStore
  (put-secret! [_ secret-id secret]
    (cond
      (not (valid-secret-id? secret-id))
      (error :SECRET_ID_INVALID "secret-id must match [A-Za-z0-9._-]{1,128}"
             {:secret-id secret-id})

      (not (and (string? secret) (not (str/blank? secret))))
      (error :SECRET_INVALID "secret must be a non-blank string" {})

      :else
      (locking lock
        (swap! state assoc secret-id secret)
        {:ok? true :secret-ref (secret-ref secret-id)})))

  (get-secret [_ ref]
    (if-let [id (parse-secret-ref ref)]
      (locking lock
        (if-let [secret (get @state id)]
          {:ok? true :secret secret}
          (error :SECRET_NOT_FOUND "secret does not exist" {:secret-ref ref})))
      (error :SECRET_REF_INVALID "secret-ref is invalid" {:secret-ref ref})))

  (delete-secret! [_ ref]
    (if-let [id (parse-secret-ref ref)]
      (locking lock
        (swap! state dissoc id)
        {:ok? true :deleted? true})
      (error :SECRET_REF_INVALID "secret-ref is invalid" {:secret-ref ref})))

  (secret-exists? [_ ref]
    (if-let [id (parse-secret-ref ref)]
      (locking lock (contains? @state id))
      false)))

(defn memory-secret-store
  "An in-memory store for tests and ephemeral processes."
  []
  (->MemorySecretStore (atom {}) (Object.)))

;; ---------------------------------------------------------------------------
;; File adapter

(defn- posix!
  [path permissions]
  (try
    (Files/setPosixFilePermissions path permissions)
    (catch UnsupportedOperationException _ nil)
    (catch java.nio.file.FileSystemException _ nil)))

(defn- write-private!
  [^Path path ^bytes content]
  (let [tmp (Files/createTempFile (.getParent path)
                                  ".hc-secret-"
                                  ".tmp"
                                  (make-array FileAttribute 0))]
    (try
      (posix! tmp (PosixFilePermissions/fromString "rw-------"))
      (Files/write tmp content
                   (into-array OpenOption
                               [StandardOpenOption/TRUNCATE_EXISTING
                                StandardOpenOption/WRITE]))
      (posix! tmp (PosixFilePermissions/fromString "rw-------"))
      (try
        (Files/move tmp path
                    (into-array java.nio.file.CopyOption
                                [StandardCopyOption/ATOMIC_MOVE
                                 StandardCopyOption/REPLACE_EXISTING]))
        (catch java.nio.file.AtomicMoveNotSupportedException _
          (Files/move tmp path
                      (into-array java.nio.file.CopyOption
                                  [StandardCopyOption/REPLACE_EXISTING]))))
      (finally
        (Files/deleteIfExists tmp)))))

(defn- read-key-file
  ^bytes [^Path path]
  (when (Files/exists path (make-array LinkOption 0))
    (let [value (str/trim (String. (Files/readAllBytes path)
                                   StandardCharsets/UTF_8))]
      (when-not (str/blank? value)
        (unb64 value)))))

(defn- load-or-create-key
  ^bytes [^Path directory {:keys [key key-file]}]
  (cond
    key
    (let [value (if (string? key) (unb64 key) key)]
      (when-not (= key-bytes (alength ^bytes value))
        (throw (ex-info "master key must be 32 bytes"
                        {:code :SECRET_KEY_INVALID})))
      value)

    (System/getenv "HC_SECRET_KEY")
    (let [value (unb64 (System/getenv "HC_SECRET_KEY"))]
      (when-not (= key-bytes (alength ^bytes value))
        (throw (ex-info "HC_SECRET_KEY must decode to 32 bytes"
                        {:code :SECRET_KEY_INVALID})))
      value)

    :else
    (let [path (or (some-> key-file io/file .toPath)
                   (.resolve directory "master.key"))]
      (if-let [existing (read-key-file path)]
        (do
          (when-not (= key-bytes (alength ^bytes existing))
            (throw (ex-info "secret master key has the wrong length"
                            {:code :SECRET_KEY_INVALID})))
          existing)
        (let [value (random-bytes key-bytes)]
          (write-private! path (.getBytes (b64 value) StandardCharsets/UTF_8))
          value)))))

(defn- read-secrets
  [^Path path]
  (if (Files/exists path (make-array LinkOption 0))
    (let [value (edn/read-string
                 {:readers {} :default (fn [_] nil)}
                 (String. (Files/readAllBytes path)
                          StandardCharsets/UTF_8))]
      (if (map? value)
        value
        (throw (ex-info "secret store is not a map"
                        {:code :SECRET_STORE_CORRUPT}))))
    {}))

(defn- encrypt
  [^bytes key ^bytes plaintext]
  (let [nonce (random-bytes nonce-bytes)
        cipher (Cipher/getInstance "AES/GCM/NoPadding")
        spec (GCMParameterSpec. gcm-tag-bits nonce)]
    (.init cipher Cipher/ENCRYPT_MODE (SecretKeySpec. key "AES") spec)
    {:version 1
     :nonce (b64 nonce)
     :ciphertext (b64 (.doFinal cipher plaintext))}))

(defn- decrypt
  [^bytes key {:keys [version nonce ciphertext]}]
  (when-not (= 1 version)
    (throw (ex-info "unsupported secret record version"
                    {:code :SECRET_RECORD_INVALID})))
  (let [cipher (Cipher/getInstance "AES/GCM/NoPadding")
        spec (GCMParameterSpec. gcm-tag-bits (unb64 nonce))]
    (.init cipher Cipher/DECRYPT_MODE (SecretKeySpec. key "AES") spec)
    (String. (.doFinal cipher (unb64 ciphertext)) StandardCharsets/UTF_8)))

(defrecord FileSecretStore [directory key state lock]
  SecretStore
  (put-secret! [_ secret-id secret]
    (cond
      (not (valid-secret-id? secret-id))
      (error :SECRET_ID_INVALID "secret-id must match [A-Za-z0-9._-]{1,128}"
             {:secret-id secret-id})

      (not (and (string? secret) (not (str/blank? secret))))
      (error :SECRET_INVALID "secret must be a non-blank string" {})

      :else
      (locking lock
        (try
          (let [path (.resolve directory "secrets.edn")
                current (read-secrets path)
                record (encrypt key (.getBytes secret StandardCharsets/UTF_8))
                next-value (assoc current secret-id record)]
            (write-private! path
                            (.getBytes (pr-str next-value)
                                       StandardCharsets/UTF_8))
            (reset! state next-value)
            {:ok? true :secret-ref (secret-ref secret-id)})
          (catch Exception exception
            (error :SECRET_STORE_WRITE_FAILED
                   (str "secret could not be stored: "
                        (.getMessage exception))
                   {:secret-id secret-id}))))))

  (get-secret [_ ref]
    (if-let [id (parse-secret-ref ref)]
      (locking lock
        (try
          (let [current (if (some? @state)
                          @state
                          (read-secrets (.resolve directory "secrets.edn")))]
            (if-let [record (get current id)]
              {:ok? true :secret (decrypt key record)}
              (error :SECRET_NOT_FOUND "secret does not exist"
                     {:secret-ref ref})))
          (catch Exception exception
            (error :SECRET_DECRYPT_FAILED
                   (str "secret could not be read: " (.getMessage exception))
                   {:secret-ref ref}))))
      (error :SECRET_REF_INVALID "secret-ref is invalid" {:secret-ref ref})))

  (delete-secret! [_ ref]
    (if-let [id (parse-secret-ref ref)]
      (locking lock
        (try
          (let [path (.resolve directory "secrets.edn")
                current (read-secrets path)]
            (when (contains? current id)
              (let [next-value (dissoc current id)]
                (write-private! path
                                (.getBytes (pr-str next-value)
                                           StandardCharsets/UTF_8))
                (reset! state next-value)))
            {:ok? true :deleted? true})
          (catch Exception exception
            (error :SECRET_STORE_WRITE_FAILED
                   (str "secret could not be deleted: " (.getMessage exception))
                   {:secret-ref ref}))))
      (error :SECRET_REF_INVALID "secret-ref is invalid" {:secret-ref ref})))

  (secret-exists? [_ ref]
    (if-let [id (parse-secret-ref ref)]
      (locking lock
        (try
          (contains? (or @state
                          (read-secrets (.resolve directory "secrets.edn")))
                     id)
          (catch Exception _ false)))
      false)))

(defn file-secret-store
  "Create an encrypted local secret store.

  Options:

    :directory  required directory for `master.key` and `secrets.edn`
    :key        optional 32-byte key or base64 string; otherwise
                `HC_SECRET_KEY` or `master.key` is used
    :key-file   optional explicit master-key path

  The directory is created with 0700 and files with 0600 where the platform
  supports POSIX permissions.  The store never writes a raw secret."
  [{:keys [directory] :as options}]
  (when-not (string? directory)
    (throw (ex-info "file-secret-store needs :directory"
                    {:code :SECRET_DIRECTORY_REQUIRED})))
  (let [directory (-> (io/file directory) .toPath .toAbsolutePath .normalize)
        _ (Files/createDirectories directory (make-array FileAttribute 0))
        _ (posix! directory (PosixFilePermissions/fromString "rwx------"))
        key (load-or-create-key directory options)]
    (->FileSecretStore directory key (atom nil) (Object.))))
