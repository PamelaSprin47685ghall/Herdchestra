(ns hc.server.backup.crypto
  "Password policy and authenticated encryption for backup containers.

  A backup password is used exactly as typed.  Leading and trailing spaces are
  meaningful, Chinese characters count as one character each, and an
  all-whitespace password is refused.  The minimum is 12 characters.

  Encryption is PBKDF2-HMAC-SHA256 (64 derived bytes, first 32 for AES-256,
  last 32 for a key-check MAC) followed by AES-256-GCM.  The key-check lets a
  wrong password be reported as a wrong password instead of being confused
  with ciphertext corruption.  A header digest catches corruption before key
  derivation."
  (:require [clojure.string :as str]
            [hc.server.backup.codec :as codec]
            [hc.server.backup.error :as err])
  (:import (java.security SecureRandom)
           (javax.crypto AEADBadTagException Cipher SecretKeyFactory)
           (javax.crypto.spec GCMParameterSpec PBEKeySpec
                              SecretKeySpec)))

(def min-password-length
  12)

(def default-iterations
  210000)

(def min-iterations
  10000)

(def kdf-algorithm
  "PBKDF2WithHmacSHA256")

(def cipher-algorithm
  "AES/GCM/NoPadding")

(def key-length-bytes
  64)

(def salt-bytes
  16)

(def iv-bytes
  12)

(def tag-bits
  128)

(def crypto-version
  1)

(def default-settings
  "The encryption switch is remembered by default; the password itself is not."
  {:encrypt-by-default? true
   :remember-encryption? true
   :remember-password? false})

(defn password-length
  [password]
  (when (string? password)
    (.codePointCount ^String password 0 (.length ^String password))))

(defn- all-whitespace?
  [^String s]
  (every? (fn [c]
            (let [cp (int c)]
              (or (Character/isWhitespace cp)
                  (Character/isSpaceChar cp))))
          s))

(defn validate-password
  "Validate a password for a new backup and return it unchanged.

  The password is never trimmed.  A nil password is `:BACKUP_PASSWORD_REQUIRED`;
  a short one is `:BACKUP_PASSWORD_TOO_SHORT`; an all-whitespace one is
  `:BACKUP_PASSWORD_BLANK`."
  [password]
  (cond
    (nil? password)
    (err/fail! :BACKUP_PASSWORD_REQUIRED
               "Encrypted backups need a password"
               {:min-length min-password-length})

    (not (string? password))
    (err/fail! :BACKUP_PASSWORD_INVALID
               "Backup password must be a string"
               {:type (str (class password))})

    (str/blank? password)
    (err/fail! :BACKUP_PASSWORD_BLANK
               "Backup password must contain a non-whitespace character"
               {:length (password-length password)})

    (< (password-length password) min-password-length)
    (err/fail! :BACKUP_PASSWORD_TOO_SHORT
               "Backup password is shorter than the minimum"
               {:min-length min-password-length
                :length (password-length password)})

    (all-whitespace? password)
    (err/fail! :BACKUP_PASSWORD_BLANK
               "Backup password must contain a non-whitespace character"
               {:length (password-length password)})

    :else password))

(defn validate-password-confirmation
  [password confirmation]
  (validate-password password)
  (when (and (some? confirmation) (not= password confirmation))
    (err/fail! :BACKUP_PASSWORD_MISMATCH
               "The two backup passwords do not match"
               {:length (password-length password)}))
  password)

(defn- random-bytes
  ^bytes [n]
  (let [bytes (byte-array n)]
    (.nextBytes (SecureRandom.) bytes)
    bytes))

(defn- split-keys
  [^bytes derived]
  (let [enc (byte-array 32)
        mac (byte-array 32)]
    (System/arraycopy derived 0 enc 0 32)
    (System/arraycopy derived 32 mac 0 32)
    {:encryption-key enc :mac-key mac}))

(defn derive-keys
  "Derive the encryption and key-check keys.  The salt and iteration count are
  read from the backup header when decrypting, so old files keep working after
  defaults change."
  [password salt iterations]
  (when-not (and (string? password)
                 (bytes? salt)
                 (integer? iterations)
                 (<= min-iterations iterations))
    (err/fail! :BACKUP_ENCRYPTION_UNSUPPORTED
               "Unsupported password key-derivation parameters"
               {:iterations iterations
                :min-iterations min-iterations
                :salt-bytes (when (bytes? salt) (alength ^bytes salt))}))
  (let [spec (PBEKeySpec. ^chars (.toCharArray ^String password)
                          ^bytes salt
                          (int iterations)
                          (* 8 key-length-bytes))
        factory (SecretKeyFactory/getInstance kdf-algorithm)
        derived (.getEncoded (.generateSecret factory spec))]
    (split-keys derived)))

(defn- key-check-bytes
  [mac-key salt iterations]
  (codec/hmac-sha256
   mac-key
   (codec/utf8-bytes
    (str "hc.backup.key-check.v1\n"
         iterations "\n"
         (codec/base64-encode salt)))))

(defn- assert-crypto-header!
  [kdf cipher]
  (when-not (and (map? kdf)
                 (= kdf-algorithm (:algorithm kdf))
                 (integer? (:iterations kdf))
                 (<= min-iterations (:iterations kdf))
                 (string? (:salt kdf)))
    (err/fail! :BACKUP_ENCRYPTION_UNSUPPORTED
               "Backup uses an unsupported key-derivation algorithm"
               {:kdf (select-keys (or kdf {}) [:algorithm :iterations])}))
  (when-not (and (map? cipher)
                 (= cipher-algorithm (:algorithm cipher))
                 (string? (:iv cipher))
                 (= tag-bits (:tag-bits cipher)))
    (err/fail! :BACKUP_ENCRYPTION_UNSUPPORTED
               "Backup uses an unsupported cipher"
               {:cipher (select-keys (or cipher {}) [:algorithm :tag-bits])})))

(defn encrypt
  "Encrypt `plaintext` and return the header fields plus the ciphertext.

  `:aad` is associated data; it is not stored in the ciphertext.  The caller
  supplies the canonical header bytes that must not change."
  [^bytes plaintext password {:keys [iterations salt iv aad]
                              :or {iterations default-iterations}}]
  (validate-password password)
  (let [iterations (long (or iterations default-iterations))
        _ (when (< iterations min-iterations)
            (err/fail! :BACKUP_ENCRYPTION_UNSUPPORTED
                       "Key-derivation iteration count is too low"
                       {:iterations iterations :min-iterations min-iterations}))
        salt (or salt (random-bytes salt-bytes))
        iv (or iv (random-bytes iv-bytes))
        {:keys [encryption-key mac-key]} (derive-keys password salt iterations)
        check (key-check-bytes mac-key salt iterations)
        cipher (Cipher/getInstance cipher-algorithm)]
    (.init cipher
           Cipher/ENCRYPT_MODE
           (SecretKeySpec. ^bytes encryption-key "AES")
           (GCMParameterSpec. tag-bits ^bytes iv))
    (when aad
      (.updateAAD cipher ^bytes aad))
    (let [ciphertext (.doFinal cipher plaintext)]
      {:ciphertext ciphertext
       :kdf {:algorithm kdf-algorithm
             :iterations iterations
             :salt (codec/base64-encode salt)}
       :cipher {:algorithm cipher-algorithm
                :iv (codec/base64-encode iv)
                :tag-bits tag-bits}
       :key-check (codec/base64-encode check)
       :crypto-version crypto-version})))

(defn decrypt
  "Decrypt `ciphertext` with the password and header parameters.

  `:wrong-password?` is reported only when the key-check fails.  Once the
  key-check passes, an authentication-tag failure is corruption, not a
  password problem."
  [^bytes ciphertext password {:keys [kdf cipher key-check aad]}]
  (when-not (string? password)
    (err/fail! :BACKUP_PASSWORD_REQUIRED
               "This backup needs its password"
               {}))
  (assert-crypto-header! kdf cipher)
  (when-not (and (string? key-check) (not (str/blank? key-check)))
    (err/fail! :BACKUP_FORMAT_INVALID
               "Backup has no key-check value"
               {:reason :missing-key-check}))
  (let [salt (codec/base64-decode (:salt kdf))
        iterations (long (:iterations kdf))
        iv (codec/base64-decode (get-in cipher [:iv]))
        {:keys [encryption-key mac-key]} (derive-keys password salt iterations)
        expected (key-check-bytes mac-key salt iterations)
        actual (try
                 (codec/base64-decode key-check)
                 (catch clojure.lang.ExceptionInfo _
                   (err/fail! :BACKUP_FORMAT_INVALID
                              "Backup key-check value is not valid base64"
                              {})))]
    (when-not (codec/constant-time= expected actual)
      (err/fail! :BACKUP_WRONG_PASSWORD
                 "The password does not unlock this backup"
                 {:reason :key-check}))
    (let [c (Cipher/getInstance cipher-algorithm)]
      (.init c
             Cipher/DECRYPT_MODE
             (SecretKeySpec. ^bytes encryption-key "AES")
             (GCMParameterSpec. (int tag-bits) ^bytes iv))
      (when aad
        (.updateAAD c ^bytes aad))
      (try
        (.doFinal c ciphertext)
        (catch AEADBadTagException e
          (err/fail! :BACKUP_INTEGRITY
                     "Backup ciphertext failed authentication"
                     {:reason :gcm-tag}
                     e))
        (catch javax.crypto.BadPaddingException e
          (err/fail! :BACKUP_INTEGRITY
                     "Backup ciphertext could not be decrypted"
                     {:reason :padding}
                     e))))))
