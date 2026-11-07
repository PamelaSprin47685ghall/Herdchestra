(ns hc.server.backup.package
  "The backup container, its public header and its authenticated payload.

  The header is intentionally small: it exposes only what a remote list can
  show without the password (backup ID, sampling time, device, format version,
  encryption state and integrity).  Category counts, conflicts and secrets
  live inside the payload, which is encrypted by default."
  (:require [hc.server.backup.codec :as codec]
            [hc.server.backup.crypto :as crypto]
            [hc.server.backup.error :as err]))

(def format-version
  1)

(def header-format
  :hc.backup/header)

(def payload-format
  :hc.backup/payload)

(defn- header-base
  "The header fields that are not crypto parameters, payload descriptors or the
  header digest.  This is the AES-GCM associated data; the header digest
  separately covers the whole header."
  [header]
  (dissoc header
          :payload-sha256 :payload-size :header-sha256
          :crypto-version :kdf :cipher :key-check))

(defn- header-digest
  [header-without-digest]
  (codec/sha256-hex (codec/canonical-bytes header-without-digest)))

(defn- finalize-header
  [header-without-digest]
  (assoc header-without-digest
         :header-sha256 (header-digest header-without-digest)))

(defn seal-payload
  "Build one container from a header field map and plaintext payload bytes.

  `header-fields` must contain `:backup-id`, `:sampled-at`, `:created-at`,
  `:device` and `:app-version`.  `:encrypt?` defaults to true; when true,
  `:password` is required.  `:kdf-iterations` is accepted for tests and old
  files; the default is the production value."
  [header-fields ^bytes plaintext {:keys [encrypt? password kdf-iterations]
                                   :or {encrypt? true}}]
  (let [plain-sha (codec/sha256-hex plaintext)
        plain-size (alength plaintext)
        base (merge {:format header-format
                     :format-version format-version
                     :encryption (if encrypt? :aes-gcm :none)
                     :plaintext-sha256 plain-sha
                     :plaintext-size plain-size}
                    header-fields)]
    (if encrypt?
      (do
        (crypto/validate-password password)
        (let [aad (codec/canonical-bytes base)
              encrypted (crypto/encrypt plaintext password
                                        {:iterations (or kdf-iterations
                                                         crypto/default-iterations)
                                         :aad aad})
              header-without-digest
              (merge base
                     (select-keys encrypted [:crypto-version :kdf :cipher
                                             :key-check])
                     {:payload-sha256 (codec/sha256-hex (:ciphertext encrypted))
                      :payload-size (alength ^bytes (:ciphertext encrypted))})
              header (finalize-header header-without-digest)
              container (codec/encode-container
                         (codec/canonical-bytes header)
                         (:ciphertext encrypted))]
          {:bytes container
           :header header
           :encrypted? true}))
      (let [header-without-digest
            (assoc base
                   :payload-sha256 plain-sha
                   :payload-size plain-size)
            header (finalize-header header-without-digest)
            container (codec/encode-container
                       (codec/canonical-bytes header)
                       plaintext)]
        {:bytes container
         :header header
         :encrypted? false}))))

(defn- assert-header-shape!
  [header]
  (when-not (map? header)
    (err/fail! :BACKUP_FORMAT_INVALID
               "Backup header is not a map"
               {:header header}))
  (when-not (= header-format (:format header))
    (err/fail! :BACKUP_FORMAT_INVALID
               "Backup header has an unknown format"
               {:format (:format header)}))
  (let [version (:format-version header)]
    (when-not (integer? version)
      (err/fail! :BACKUP_FORMAT_INVALID
                 "Backup header has no integer format version"
                 {:format-version version}))
    (when (> version format-version)
      (err/fail! :BACKUP_VERSION_UNSUPPORTED
                 "This backup was written by a newer application format"
                 {:format-version version
                  :supported-version format-version})))
  (when-not (and (string? (:backup-id header))
                 (not (empty? (:backup-id header))))
    (err/fail! :BACKUP_FORMAT_INVALID
               "Backup header has no backup ID"
               {:backup-id (:backup-id header)}))
  (when-not (contains? #{:none :aes-gcm} (:encryption header))
    (err/fail! :BACKUP_ENCRYPTION_UNSUPPORTED
               "Backup uses an unsupported encryption mode"
               {:encryption (:encryption header)}))
  (when (and (= :aes-gcm (:encryption header))
             (not= (:crypto-version header) crypto/crypto-version))
    (err/fail! :BACKUP_ENCRYPTION_UNSUPPORTED
               "Backup uses an unsupported encryption format version"
               {:crypto-version (:crypto-version header)
                :supported-version crypto/crypto-version}))
  header)

(defn parse-header
  "Parse and validate header EDN without touching the payload."
  [^bytes header-bytes]
  (assert-header-shape! (codec/edn-read header-bytes)))

(defn- verify-header-digest!
  [header]
  (let [claimed (:header-sha256 header)
        actual (header-digest (dissoc header :header-sha256))]
    (when-not (and (string? claimed)
                   (= claimed actual))
      (err/fail! :BACKUP_INTEGRITY
                 "Backup header digest does not match its contents"
                 {:expected claimed :actual actual}))))

(declare read-header-bytes)

(defn read-header
  "Read the public header of a container.  Returns
  {:integrity :verified :header h} or {:integrity :invalid :error code ...}.
  This never needs a password and never decrypts the payload."
  [^bytes container-bytes]
  (try
    (let [{:keys [container-version header-bytes]}
          (codec/read-container container-bytes)]
      (when (> (long container-version) (long codec/container-version))
        (err/fail! :BACKUP_VERSION_UNSUPPORTED
                   "Backup container version is newer than this application"
                   {:container-version container-version
                    :supported-version codec/container-version}))
      (when-not (= container-version codec/container-version)
        (err/fail! :BACKUP_FORMAT_INVALID
                   "Backup container version is not recognized"
                   {:container-version container-version}))
      (read-header-bytes header-bytes))
    (catch clojure.lang.ExceptionInfo e
      (if (err/expected? e)
        {:integrity :invalid
         :error (err/code e)
         :message (.getMessage ^Throwable e)
         :data (ex-data e)}
        (throw e)))
    (catch Exception e
      {:integrity :invalid
       :error :BACKUP_FORMAT_INVALID
       :message (.getMessage ^Throwable e)})))

(defn read-header-bytes
  "Parse and verify a header that was already isolated from its container.
  File targets use this to list objects without reading payload bytes."
  [^bytes header-bytes]
  (try
    (let [header (parse-header header-bytes)]
      (verify-header-digest! header)
      {:integrity :verified :header header})
    (catch clojure.lang.ExceptionInfo e
      (if (err/expected? e)
        {:integrity :invalid
         :error (err/code e)
         :message (.getMessage ^Throwable e)
         :data (ex-data e)}
        (throw e)))
    (catch Exception e
      {:integrity :invalid
       :error :BACKUP_FORMAT_INVALID
       :message (.getMessage ^Throwable e)})))

(defn public-metadata
  "Fields a remote backup list may display before unlocking the file."
  [header]
  (assoc (select-keys header
                      [:format-version :backup-id :sampled-at :created-at :device
                       :app-version :encryption :crypto-version :payload-size
                       :plaintext-size])
         :id (:backup-id header)))

(defn- verify-payload-descriptor!
  [header ^bytes payload]
  (let [expected-size (:payload-size header)
        expected-sha (:payload-sha256 header)]
    (when-not (and (integer? expected-size)
                   (= (long expected-size) (alength payload)))
      (err/fail! :BACKUP_INTEGRITY
                 "Backup payload length does not match its header"
                 {:expected-size expected-size
                  :actual-size (alength payload)}))
    (let [actual (codec/sha256-hex payload)]
      (when-not (= expected-sha actual)
        (err/fail! :BACKUP_INTEGRITY
                   "Backup payload digest does not match its header"
                   {:expected-sha256 expected-sha
                    :actual-sha256 actual})))))

(defn- verify-plaintext!
  [header ^bytes plaintext]
  (let [expected-size (:plaintext-size header)
        expected-sha (:plaintext-sha256 header)]
    (when-not (and (integer? expected-size)
                   (= (long expected-size) (alength plaintext)))
      (err/fail! :BACKUP_INTEGRITY
                 "Backup plaintext length does not match its header"
                 {:expected-size expected-size
                  :actual-size (alength plaintext)}))
    (let [actual (codec/sha256-hex plaintext)]
      (when-not (= expected-sha actual)
        (err/fail! :BACKUP_INTEGRITY
                   "Backup plaintext digest does not match its header"
                   {:expected-sha256 expected-sha
                    :actual-sha256 actual})))))

(defn- assert-payload-shape!
  [payload header]
  (when-not (map? payload)
    (err/fail! :BACKUP_FORMAT_INVALID
               "Backup payload is not a map"
               {:type (str (class payload))}))
  (when-not (= payload-format (:format payload))
    (err/fail! :BACKUP_FORMAT_INVALID
               "Backup payload has an unknown format"
               {:format (:format payload)}))
  (let [version (:format-version payload)]
    (when-not (integer? version)
      (err/fail! :BACKUP_FORMAT_INVALID
                 "Backup payload has no integer format version"
                 {:format-version version}))
    (when (> version format-version)
      (err/fail! :BACKUP_VERSION_UNSUPPORTED
                 "This backup payload was written by a newer format"
                 {:format-version version
                  :supported-version format-version})))
  (when-not (= (:backup-id payload) (:backup-id header))
    (err/fail! :BACKUP_INTEGRITY
               "Backup payload and header disagree about the backup ID"
               {:header-id (:backup-id header)
                :payload-id (:backup-id payload)}))
  payload)

(defn verify-container
  "Verify a container's framing, header digest and payload descriptor without
  decrypting or decoding the payload.  File targets use this before publishing
  an object and again when verifying it."
  [^bytes container-bytes]
  (let [{:keys [container-version header-bytes payload]}
        (codec/read-container container-bytes)]
    (when (> (long container-version) (long codec/container-version))
      (err/fail! :BACKUP_VERSION_UNSUPPORTED
                 "Backup container version is newer than this application"
                 {:container-version container-version
                  :supported-version codec/container-version}))
    (when-not (= container-version codec/container-version)
      (err/fail! :BACKUP_FORMAT_INVALID
                 "Backup container version is not recognized"
                 {:container-version container-version}))
    (let [header (parse-header header-bytes)]
      (verify-header-digest! header)
      (verify-payload-descriptor! header payload)
      {:header header :payload payload})))

(defn open-container
  "Verify and open a container.

  Order matters: container shape, header digest, payload digest, password
  key-check, GCM tag, plaintext digest, payload EDN.  A wrong password is
  reported before any plaintext is decoded."
  [^bytes container-bytes password]
  (let [{:keys [container-version header-bytes payload]}
        (codec/read-container container-bytes)]
    (when (> (long container-version) (long codec/container-version))
      (err/fail! :BACKUP_VERSION_UNSUPPORTED
                 "Backup container version is newer than this application"
                 {:container-version container-version
                  :supported-version codec/container-version}))
    (when-not (= container-version codec/container-version)
      (err/fail! :BACKUP_FORMAT_INVALID
                 "Backup container version is not recognized"
                 {:container-version container-version}))
    (let [header (parse-header header-bytes)]
      (verify-header-digest! header)
      (verify-payload-descriptor! header payload)
      (let [plaintext
            (case (:encryption header)
              :none
              payload

              :aes-gcm
              (do
                (when-not (string? password)
                  (err/fail! :BACKUP_PASSWORD_REQUIRED
                             "This backup needs its password"
                             {:backup-id (:backup-id header)}))
                (crypto/decrypt payload password
                                {:kdf (:kdf header)
                                 :cipher (:cipher header)
                                 :key-check (:key-check header)
                                 :aad (codec/canonical-bytes
                                       (header-base header))}))
              (err/fail! :BACKUP_ENCRYPTION_UNSUPPORTED
                         "Backup uses an unsupported encryption mode"
                         {:encryption (:encryption header)}))]
        (verify-plaintext! header plaintext)
        (let [decoded (codec/edn-read plaintext)]
          (assert-payload-shape! decoded header)
          {:header header
           :payload decoded
           :encrypted? (= :aes-gcm (:encryption header))
           :container-bytes container-bytes
           :plaintext-bytes plaintext})))))
