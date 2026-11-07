(ns hc.server.backup.service
  "Application-facing backup/restore service.

  The application constructs one service around the already-open storage
  handle and a data directory.  The router calls `handle!` with the same
  command envelope used for other application commands.

  Restore is a whole-store operation.  The application must stop every Owner
  and writer, call `:backup/restore` with `:quiescent? true`, then restart the
  runtime.  This namespace never pretends that a live restore is safe."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [hc.server.backup :as backup]
            [hc.server.backup.error :as err]
            [hc.server.backup.file-recovery :as file-recovery]
            [hc.server.backup.file-target :as file-target]
            [hc.server.backup.port :as port]
            [hc.server.backup.s3 :as s3]
            [hc.server.backup.storage-adapter :as storage-adapter]
            [hc.server.backup.webdav :as webdav]
            [hc.server.backup.workspace :as workspace]
            [hc.server.gateway.ports :as blob-port])
  (:import (java.nio.file Files LinkOption Path StandardCopyOption
                          StandardOpenOption)))

(defrecord BackupService [store domain targets default-target-id recovery data-dir opts])

(def command-kinds
  #{:backup/status :backup/create :backup/list :backup/preview
    :backup/restore :backup/verify :backup/delete
    :backup/recovery-points :backup/restore-recovery-point :backup/prune})

(defn- basename
  [path]
  (some-> (java.nio.file.Path/of (str path) (make-array String 0))
          .getFileName
          str))

(defn- normalize-target-id
  [id]
  (cond
    (keyword? id) id
    (string? id) (keyword id)
    (symbol? id) (keyword (name id))
    :else id))

(defn- make-target
  [id config data-dir]
  (let [kind (let [k (:kind config :file)]
               (cond
                 (keyword? k) k
                 (string? k) (keyword k)
                 :else k))
        config (assoc config :kind kind)]
    (try
      (case kind
        (:file :local)
        {:id id :kind :file
         :config (assoc config :root (or (:root config)
                                         (str data-dir "/backups")))
         :port (file-target/store (or (:root config)
                                      (str data-dir "/backups")))}

        :webdav
        {:id id :kind :webdav
         :config config
         :port (webdav/target config)}

        :s3
        {:id id :kind :s3
         :config config
         :port (s3/target config)}

        {:id id :kind kind :config config
         :error (str "unsupported backup target kind: " kind)})
      (catch Throwable t
        {:id id :kind kind :config config
         :error (or (ex-message t) (str t))}))))

(defn- target-configs
  [{:keys [targets target config data-dir] :as opts}]
  (let [from-config (or (get-in config [:backup :targets])
                        (when-let [t (get-in config [:backup :target])]
                          {:local t}))
        raw (or (when (seq targets) targets)
                (when target {:local target})
                (when (seq from-config) from-config)
                {:local {:kind :file
                         :root (or (:target-root opts)
                                   (str data-dir "/backups"))}})]
    (into {}
          (map (fn [[id config]]
                 [(normalize-target-id id)
                  (if (map? config) config {:kind config})]))
          raw)))

(defn service
  "Create a backup service.

  Options:
    :store        required open `hc.server.storage` handle
    :data-dir     required directory containing backups and recovery points
    :target       default target config, e.g. {:kind :file :root ...}
    :targets      map id -> target config, e.g. {:local {...} :remote {...}}
    :target-root  local target root when no target map is supplied
    :config       optional application config; reads [:backup :target] and
                  [:backup :targets] when :target/:targets are absent
    :default-target-id  target selected when a command omits :target-id
    :recovery-root default `<data-dir>/recovery`
    :extensions-root default `<data-dir>/extensions`
    :blob-root    default `<data-dir>/blobs`; a `:blob-store` port wins
    :device       optional display name
    :app-version  optional application format label

  Target configs:
    file:   {:kind :file :root <directory>}
    webdav: {:kind :webdav :base-url <url> :directory <name>
             :username <user> :password <secret>}
    s3:     {:kind :s3 :endpoint <url> :bucket <name>
             :region <region> :access-key <key> :secret-key <secret>
             :prefix <prefix> :path-style? true}"
  [{:keys [store data-dir recovery-root device app-version default-target-id]
    :as opts}]
  (when-not (and (string? data-dir) (not (str/blank? (str data-dir))))
    (err/fail! :BACKUP_TARGET_INVALID
               "Backup service needs a non-blank :data-dir"
               {:data-dir data-dir}))
  (let [recovery-root (or recovery-root (str data-dir "/recovery"))
        extensions-root (or (:extensions-root opts)
                            (get-in opts [:config :extensions-root])
                            (str data-dir "/extensions"))
        blob-root (or (:blob-root opts)
                      (get-in opts [:config :blob-root])
                      (str data-dir "/blobs"))
        blob-store (or (:blob-store opts)
                       (blob-port/blob-store-port blob-root))
        workspace-root (or (:workspace-store-root opts)
                           (get-in opts [:config :workspace-store-root])
                           (str data-dir "/workspaces"))
        workspace-extras (or (:workspaces opts)
                             (get-in opts [:config :backup :workspaces])
                             (when-let [root (get-in opts [:config :workspace-root])]
                               [{:id "app-workspace"
                                 :name (or (basename root) "app-workspace")
                                 :kind :external
                                 :path (str root)}]))
        workspace-store (or (:workspace-store opts)
                            (workspace/store workspace-root
                                             {:workspaces workspace-extras}))
        configs (target-configs opts)
        targets (into {}
                      (map (fn [[id config]]
                             [id (make-target id config data-dir)]))
                      configs)
        default-target-id (normalize-target-id
                           (or default-target-id
                               (when (contains? targets :local) :local)
                               (first (keys targets))))]
    (->BackupService store
                     (storage-adapter/domain
                      store
                      {:quiescent? false
                       :extensions-root extensions-root
                       :blob-store blob-store
                       :workspace-store workspace-store
                       :max-attachment-bytes (:max-attachment-bytes opts)
                       :max-attachment-total-bytes
                       (:max-attachment-total-bytes opts)})
                     targets
                     default-target-id
                     (file-recovery/store recovery-root)
                     data-dir
                     (assoc opts
                            :recovery-root recovery-root
                            :extensions-root extensions-root
                            :blob-root blob-root
                            :blob-store blob-store
                            :workspace-store-root workspace-root
                            :workspace-store workspace-store
                            :device device
                            :app-version app-version))))

(defn- target-for
  [service payload]
  (let [id (normalize-target-id
            (or (:target-id payload) (:default-target-id service)))]
    (if-let [target (get (:targets service) id)]
      (if-let [reason (:error target)]
        (err/fail! :BACKUP_TARGET_UNAVAILABLE
                   "The selected backup target is not available"
                   {:target-id id :reason reason})
        (:port target))
      (err/fail! :BACKUP_TARGET_UNKNOWN
                 "The selected backup target does not exist"
                 {:target-id id
                  :available (vec (keys (:targets service)))}))))

(defn target-port
  "Return the target port for PAYLOAD's `:target-id` (or the default).

  Maintenance uses this to stage a verified local copy before the application
  stops, so the offline restore does not need a live network target.  Expected
  failures are `ExceptionInfo` values with `:error`."
  [service payload]
  (target-for service payload))

(defn- safe-url
  [value]
  (try
    (let [uri (java.net.URI. (str value))
          port (.getPort uri)]
      (str (.getScheme uri) "://" (.getHost uri)
           (when (pos? port) (str ":" port))
           (or (.getRawPath uri) "")))
    (catch Exception _ (str value))))

(defn- target-description
  [target]
  (let [config (:config target)]
    (cond-> {:id (:id target)
             :kind (:kind target)
             :configured? true
             :available? (nil? (:error target))}
      (:error target) (assoc :reason (:error target))
      (:root config) (assoc :root (:root config))
      (:base-url config) (assoc :base-url (safe-url (:base-url config))
                                :directory (:directory config))
      (:endpoint config) (assoc :endpoint (safe-url (:endpoint config))
                                :bucket (:bucket config)
                                :prefix (:prefix config)
                                :path-style? (:path-style? config)))))

(defn- options
  [service payload]
  (cond-> (select-keys payload
                       [:password :password-confirm :encrypt?
                        :categories :selection :include-secrets?
                        :confirm-plaintext-secrets? :include-deleted?
                        :include-temp? :include-cache? :include-external-files?
                        :mode :resolutions :confirm-memory-reactivation?
                        :include-expired? :expected-fingerprint
                        :quiescent? :retention-days
                        :device :app-version
                        :max-attachment-bytes :max-attachment-total-bytes])
    (:device (:opts service)) (assoc :device (:device (:opts service)))
    (:app-version (:opts service)) (assoc :app-version (:app-version (:opts service)))))

(defn- require-quiescent!
  [payload]
  (when-not (true? (:quiescent? payload))
    (err/fail! :BACKUP_RESTORE_REQUIRES_QUIESCENCE
               "Stop the Owner and all writers before restoring"
               {})))

(defn- error-result
  [t]
  {:ok? false
   :error (cond-> {:code (or (err/code t) :BACKUP_FAILED)
                   :message (or (ex-message t) "backup operation failed")}
            (ex-data t) (assoc :details (dissoc (ex-data t) :error)))})

(defn- last-success-path
  ^Path [service]
  (Path/of (str (:data-dir service))
           (into-array String ["last-success.edn"])))

(defn- read-last-success
  [service]
  (try
    (let [path (last-success-path service)]
      (when (Files/exists path (make-array LinkOption 0))
        (edn/read-string {:readers {}}
                         (slurp (io/file (.toFile path))))))
    (catch Throwable _ nil)))

(defn- write-last-success!
  [service target-id result]
  (try
    (let [path (last-success-path service)
          parent (.getParent path)
          _ (when parent
              (Files/createDirectories parent (make-array java.nio.file.attribute.FileAttribute 0)))
          marker {:backup-id (:id result)
                  :target-id target-id
                  :sampled-at (get-in result [:backup :sampled-at])
                  :created-at (get-in result [:backup :created-at])
                  :verified-at (str (java.time.Instant/now))}
          temp (Files/createTempFile parent "last-success-" ".edn"
                                     (make-array java.nio.file.attribute.FileAttribute 0))]
      (spit (io/file (.toFile temp)) (pr-str marker))
      (Files/move temp path
                  (into-array java.nio.file.CopyOption
                              [StandardCopyOption/ATOMIC_MOVE
                               StandardCopyOption/REPLACE_EXISTING]))
      marker)
    (catch Throwable _ nil)))

(defn status
  ([service] (status service {}))
  ([service payload]
  (let [selected-id (normalize-target-id
                     (or (:target-id payload) (:default-target-id service)))
        selected (get (:targets service) selected-id)
        target-error (atom nil)
        backups (if (and selected (nil? (:error selected)))
                  (try (port/list-objects (:port selected))
                       (catch Throwable t
                         (reset! target-error (or (ex-message t) (str t)))
                         nil))
                  nil)
        recovery-error (atom nil)
        recovery (try (port/list-recovery-points (:recovery service))
                      (catch Throwable t
                        (reset! recovery-error (or (ex-message t) (str t)))
                        nil))
        marker (read-last-success service)
        last-backup (or (some #(when (= (:backup-id marker) (:id %)) %)
                              (or backups []))
                        (first (filter #(= :verified (:integrity %))
                                       (or backups []))))
        errors (cond-> []
                 @target-error (conj {:target @target-error})
                 @recovery-error (conj {:recovery @recovery-error}))
        error (when (seq errors)
                {:code :BACKUP_TARGET_IO
                 :message "A backup or recovery-point listing failed"
                 :details {:errors errors}})]
    (cond-> {:ok? (nil? error)
     :receipt {:data-dir (:data-dir service)
               :target-id selected-id
               :target (when selected (target-description selected))
               :targets (mapv target-description (vals (:targets service)))
               :default-target-id (:default-target-id service)
               :backups backups
               :recovery-points recovery
               :last-backup last-backup
               :storage (port/storage-info (:domain service))
               :extensions {:root (get-in service [:opts :extensions-root])
                            :configured? (some? (get-in service [:opts :extensions-root]))}
               :blobs {:root (get-in service [:opts :blob-root])
                       :configured? (some? (get-in service [:opts :blob-store]))}
               :workspaces {:root (get-in service [:opts :workspace-store-root])
                            :configured? (some? (get-in service [:opts :workspace-store]))}
               :recovery {:root (get-in service [:opts :recovery-root])}
               :restore {:online? false
                         :offline? true
                         :requires-quiescence? true}
               :errors errors}}
      error (assoc :error error)))))

(defn create!
  [service payload]
  (let [target-id (normalize-target-id
                   (or (:target-id payload) (:default-target-id service)))
        result (backup/backup! (:domain service) (target-for service payload)
                               (options service payload))]
    (when (= :complete (:status result))
      (write-last-success! service target-id result))
    {:ok? true :receipt result}))

(defn list!
  ([service] (list! service {}))
  ([service payload]
   {:ok? true
    :receipt {:backups (port/list-objects (target-for service payload))}}))

(defn preview!
  [service payload]
  (let [id (:backup-id payload)]
    (when (str/blank? (str id))
      (err/fail! :BACKUP_ID_INVALID "A backup ID is required" {}))
    (let [result (backup/preview (:domain service) (target-for service payload)
                                 id (options service payload))]
      (if (:ok? result)
        {:ok? true :receipt result}
        result))))

(defn restore!
  [service payload]
  (require-quiescent! payload)
  (let [id (:backup-id payload)]
    (when (str/blank? (str id))
      (err/fail! :BACKUP_ID_INVALID "A backup ID is required" {}))
    {:ok? true
     :receipt (backup/restore! (:domain service) (target-for service payload)
                               (:recovery service) id
                               (options service payload))}))

(defn verify!
  [service payload]
  (let [id (:backup-id payload)]
    (when (str/blank? (str id))
      (err/fail! :BACKUP_ID_INVALID "A backup ID is required" {}))
    {:ok? true
     :receipt (backup/verify-backup (target-for service payload) id
                                    {:password (:password payload)})}))

(defn delete!
  [service payload]
  (let [id (:backup-id payload)]
    (when (str/blank? (str id))
      (err/fail! :BACKUP_ID_INVALID "A backup ID is required" {}))
    (when-not (true? (:confirm? payload))
      (err/fail! :BACKUP_DELETE_UNCONFIRMED
                 "Deleting a backup needs explicit confirmation"
                 {:backup-id id}))
    (port/delete-object! (target-for service payload) id)
    {:ok? true :receipt {:backup-id id :deleted? true}}))

(defn recovery-points
  [service]
  {:ok? true :receipt {:recovery-points (port/list-recovery-points (:recovery service))}})

(defn restore-recovery-point!
  [service payload]
  (require-quiescent! payload)
  (let [id (:recovery-point-id payload)]
    (when (str/blank? (str id))
      (err/fail! :BACKUP_ID_INVALID "A recovery-point ID is required" {}))
    {:ok? true
     :receipt (backup/restore-recovery-point! (:domain service)
                                              (:recovery service) id
                                              (options service payload))}))

(defn prune!
  [service payload]
  {:ok? true
   :receipt (backup/prune-recovery-points! (:recovery service)
                                           (select-keys payload
                                                        [:keep :recent-limit
                                                         :weeks :months
                                                         :current-id]))})

(defn handle!
  "Handle one backup command envelope.  Returns `{:ok? bool :receipt ...}` or
  `{:ok? false :error ...}`."
  [service payload]
  (try
    (let [kind (cond
                 (keyword? (:kind payload)) (:kind payload)
                 (string? (:kind payload)) (keyword (:kind payload))
                 :else nil)]
      (case kind
        :backup/status (status service payload)
        :backup/create (create! service payload)
        :backup/list (list! service payload)
        :backup/preview (preview! service payload)
        :backup/restore (restore! service payload)
        :backup/verify (verify! service payload)
        :backup/delete (delete! service payload)
        :backup/recovery-points (recovery-points service)
        :backup/restore-recovery-point (restore-recovery-point! service payload)
        :backup/prune (prune! service payload)
        {:ok? false
         :error {:code :BACKUP_COMMAND_UNKNOWN
                 :message "unknown backup command"
                 :details {:kind kind}}}))
    (catch clojure.lang.ExceptionInfo e
      (error-result e))
    (catch Throwable e
      {:ok? false
       :error {:code :BACKUP_FAILED
               :message (or (ex-message e) "backup operation failed")}})))
