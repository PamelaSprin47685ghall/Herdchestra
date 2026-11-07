(ns hc.server.model-auth.store
  "Encrypted, server-side credential storage for model account login.

  The store keeps access and refresh tokens out of configuration, snapshots,
  receipts, logs, and the browser.  `public-credential` is the only projection
  a caller may send to a client.

  The file store uses AES-GCM with a 256-bit key.  The key file and the
  ciphertext file are created with owner-only permissions.  A missing key is
  never replaced while ciphertext exists: that would silently destroy the
  only way to read the saved account."
  (:require [clojure.edn :as edn]
            [clojure.string :as str])
  (:import (java.nio ByteBuffer)
           (java.nio.channels FileChannel)
           (java.nio.charset StandardCharsets)
           (java.nio.file AtomicMoveNotSupportedException CopyOption Files
                          OpenOption Path StandardCopyOption StandardOpenOption)
           (java.nio.file.attribute FileAttribute PosixFilePermissions)
           (java.security SecureRandom)
           (java.util Base64 UUID)
           (javax.crypto Cipher)
           (javax.crypto.spec GCMParameterSpec SecretKeySpec)))

(defprotocol CredentialStore
  (read-credential [store connection-id]
    "Return the internal credential map, or nil.")
  (write-credential! [store connection-id credential]
    "Replace one credential.  Return {:status :written}.")
  (delete-credential! [store connection-id]
    "Delete one credential.  Return :deleted or :missing.")
  (list-credentials [store]
    "Return internal credential maps.  Callers must project them.")
  (close-store! [store]))

(declare mask-account)

(def secret-keys
  #{:access-token :refresh-token :id-token :client-secret :token :password})

(defn public-credential
  "Return the only credential shape that may leave the server."
  [credential]
  (when credential
    (-> (select-keys credential
                     [:connection-id :provider-id :issuer :subject
                      :account-label :account-claims :scopes :token-type
                      :obtained-at-ms :expires-at-ms :base-url])
        (assoc :has-refresh-token? (boolean (:refresh-token credential))
               :masked-account (mask-account (:account-label credential)
                                             (:subject credential))))))

(defn mask-account
  "Mask an account label for a public screen.  The full label is separate."
  [label fallback]
  (let [s (str (or label fallback ""))]
    (cond
      (str/blank? s) "***"
      (str/includes? s "@")
      (let [[local domain] (str/split s #"@" 2)
            local (if (str/blank? local) "?" local)]
        (str (subs local 0 (min 1 (count local))) "***@"
             (or domain "")))
      (> (count s) 4)
      (str (subs s 0 2) "***" (subs s (- (count s) 2)))
      :else "***")))

(defn- invalid!
  [code message data]
  (throw (ex-info message
                  (merge {:error/kind :model-auth/store-error
                          :code code}
                         data))))

(defn- set-perms!
  [^Path path perms]
  (try
    (Files/setPosixFilePermissions path (PosixFilePermissions/fromString perms))
    (catch UnsupportedOperationException _ nil)
    (catch java.io.IOException _ nil)))

(defn- read-bytes
  [^Path path]
  (Files/readAllBytes path))

(defn- write-atomic!
  [^Path dir ^Path target ^bytes bytes perms]
  (let [tmp (.resolve dir (str "." (.getFileName target) "."
                               (UUID/randomUUID) ".tmp"))
        opts (into-array OpenOption [StandardOpenOption/CREATE_NEW
                                     StandardOpenOption/WRITE])]
    (try
      (with-open [channel (FileChannel/open tmp opts)]
        (.write channel (ByteBuffer/wrap bytes))
        (.force channel true))
      (set-perms! tmp perms)
      (try
        (Files/move tmp target
                    (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE
                                            StandardCopyOption/REPLACE_EXISTING]))
        (catch AtomicMoveNotSupportedException _
          (Files/move tmp target
                      (into-array CopyOption
                                  [StandardCopyOption/REPLACE_EXISTING]))))
      (finally
        (when (Files/exists tmp (make-array java.nio.file.LinkOption 0))
          (try (Files/deleteIfExists tmp) (catch Exception _ nil)))))))

(defn- random-bytes
  [n]
  (let [bytes (byte-array n)]
    (.nextBytes (SecureRandom.) bytes)
    bytes))

(defn- new-key!
  [^Path path]
  (let [key (random-bytes 32)]
    (write-atomic! (.getParent path) path key "rw-------")
    key))

(defn- read-or-create-key!
  [^Path path]
  (if (Files/exists path (make-array java.nio.file.LinkOption 0))
    (let [key (read-bytes path)]
      (when-not (= 32 (alength ^bytes key))
        (invalid! :MODEL_AUTH_STORE_KEY_INVALID
                  "credential key has the wrong size" {:path (str path)}))
      key)
    (new-key! path)))

(defn- cipher
  [mode ^bytes key ^bytes iv]
  (let [cipher (Cipher/getInstance "AES/GCM/NoPadding")]
    (.init cipher mode (SecretKeySpec. key "AES")
           (GCMParameterSpec. 128 iv))
    cipher))

(def ^:private magic (.getBytes "HCMA1" StandardCharsets/UTF_8))

(defn- encrypt
  [^bytes key plaintext]
  (let [iv (random-bytes 12)
        cipher (cipher Cipher/ENCRYPT_MODE key iv)
        encrypted (.doFinal cipher plaintext)
        out (ByteBuffer/allocate (+ (alength magic) 12 (alength encrypted)))]
    (.put out magic)
    (.put out iv)
    (.put out encrypted)
    (.array out)))

(defn- decrypt
  [^bytes key ^bytes payload]
  (let [minimum (+ (alength magic) 12 16)]
    (when (or (< (alength payload) minimum)
              (not (java.util.Arrays/equals
                    ^bytes magic
                    ^bytes (java.util.Arrays/copyOf payload (alength magic)))))
      (invalid! :MODEL_AUTH_STORE_CORRUPT
                "credential file has an unknown format" {}))
    (let [iv (java.util.Arrays/copyOfRange payload (alength magic)
                                           (+ (alength magic) 12))
          body (java.util.Arrays/copyOfRange payload (+ (alength magic) 12)
                                             (alength payload))]
      (try
        (.doFinal (cipher Cipher/DECRYPT_MODE key iv) body)
        (catch Exception _
          (invalid! :MODEL_AUTH_STORE_CORRUPT
                    "credential file could not be decrypted" {}))))))

(defn- decode-store
  [^bytes bytes]
  (let [value (try
                (edn/read-string {:readers {} :default tagged-literal}
                                 (String. bytes StandardCharsets/UTF_8))
                (catch Exception _
                  (invalid! :MODEL_AUTH_STORE_CORRUPT
                            "credential file is not readable EDN" {})))]
    (when-not (and (map? value) (map? (:credentials value)))
      (invalid! :MODEL_AUTH_STORE_CORRUPT
                "credential file has no credentials map" {}))
    (:credentials value)))

(defn- encode-store
  [credentials]
  (.getBytes (pr-str {:version 1 :credentials credentials})
             StandardCharsets/UTF_8))

(defrecord MemoryStore [state]
  CredentialStore
  (read-credential [_ connection-id]
    (get-in @state [:credentials connection-id]))
  (write-credential! [_ connection-id credential]
    (swap! state assoc-in [:credentials connection-id] credential)
    {:status :written})
  (delete-credential! [_ connection-id]
    (let [present? (contains? (:credentials @state) connection-id)]
      (swap! state update :credentials dissoc connection-id)
      (if present? :deleted :missing)))
  (list-credentials [_]
    (vals (:credentials @state)))
  (close-store! [_] nil))

(defn memory-store
  ([] (memory-store {}))
  ([{:keys [credentials]}]
   (->MemoryStore (atom {:credentials (or credentials {})}))))

(defrecord FileStore [dir key key-path credentials-path state lock]
  CredentialStore
  (read-credential [_ connection-id]
    (get-in @state [:credentials connection-id]))
  (write-credential! [_ connection-id credential]
    (locking lock
      (let [next-credentials (assoc (:credentials @state)
                                    connection-id credential)]
        (write-atomic! dir credentials-path
                       (encrypt key (encode-store next-credentials))
                       "rw-------")
        (reset! state {:credentials next-credentials})
        {:status :written})))
  (delete-credential! [_ connection-id]
    (locking lock
      (if-not (contains? (:credentials @state) connection-id)
        :missing
        (let [next-credentials (dissoc (:credentials @state) connection-id)]
          (write-atomic! dir credentials-path
                         (encrypt key (encode-store next-credentials))
                         "rw-------")
          (reset! state {:credentials next-credentials})
          :deleted))))
  (list-credentials [_]
    (vals (:credentials @state)))
  (close-store! [_] nil))

(defn file-store
  "Open or create an encrypted file store under DIR.

  DIR is created with owner-only permissions.  The key and ciphertext are
  separate files so an operator can back up or remove credentials without
  changing the application configuration."
  [dir]
  (when (str/blank? (str dir))
    (invalid! :MODEL_AUTH_STORE_NO_DIRECTORY
              "credential store needs a data directory" {}))
  (let [dir (.toAbsolutePath (java.nio.file.Paths/get (str dir)
                                                      (make-array String 0)))
        key-path (.resolve dir "key.bin")
        credentials-path (.resolve dir "credentials.edn.enc")]
    (try
      (Files/createDirectories dir (make-array FileAttribute 0))
      (set-perms! dir "rwx------")
      (when (and (Files/exists credentials-path
                                (make-array java.nio.file.LinkOption 0))
                 (not (Files/exists key-path
                                    (make-array java.nio.file.LinkOption 0))))
        (invalid! :MODEL_AUTH_STORE_KEY_MISSING
                  "credential ciphertext exists without its key" {}))
      (let [key (read-or-create-key! key-path)
            credentials
            (if (Files/exists credentials-path
                              (make-array java.nio.file.LinkOption 0))
              (decode-store (decrypt key (read-bytes credentials-path)))
              {})]
        (->FileStore dir key key-path credentials-path
                     (atom {:credentials credentials})
                     (Object.)))
      (catch clojure.lang.ExceptionInfo e
        (throw e))
      (catch Exception e
        (invalid! :MODEL_AUTH_STORE_OPEN_FAILED
                  "credential store could not be opened"
                  {:message (or (.getMessage ^Exception e) (str e))})))))
