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
    :backup/recovery-points :backup/restore-recovery-point :backup/prune
    :backup/save-target :backup/test-target :backup/delete-target})

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
  "Merge configured targets with the settings-document targets.

  `settings-targets` is the `[:backup :targets]` map read through
  `:config-store`; on a shared id its fields win over the config file, and
  the id set is the union of both sources."
  [{:keys [targets target config data-dir] :as opts}
   settings-targets]
  (let [from-config (or (get-in config [:backup :targets])
                        (when-let [t (get-in config [:backup :target])]
                          {:local t}))
        file-map (or (when (seq targets) targets)
                     (when target {:local target})
                     (when (seq from-config) from-config))
        merged (merge-with merge file-map settings-targets)
        raw (if (seq merged)
              merged
              {:local {:kind :file
                       :root (or (:target-root opts)
                                 (str data-dir "/backups"))}})]
    (into {}
          (map (fn [[id config]]
                 [(normalize-target-id id)
                  (if (map? config) config {:kind config})]))
          raw)))

(defn- target-state
  "The live `{:configs :ports :default-target-id}` map behind a service."
  [service]
  (some-> (:target-state (:opts service)) deref))

(defn- config-store-read
  "Read the settings document through `:config-store`.  Returns
  `{:revision n :document map}`; a missing document reads as revision 0
  and an empty map."
  [config-store]
  (let [result ((:read config-store))]
    (if (= :found (:status result))
      {:revision (long (or (:revision (:config result)) 0))
       :document (:config result)}
      {:revision 0 :document {}})))

(defn- update-target-state!
  [service f & args]
  (swap! (:target-state (:opts service))
         (fn [state] (apply f state args))))

(defn- settings-targets
  "Read `[:backup :targets]` and `[:backup :default-target-id]` from the
  settings document exposed by `:config-store`."
  [config-store]
  (when config-store
    (let [doc (:config (config-store-read config-store))]
      {:targets (get-in doc [:backup :targets])
       :default-target-id (get-in doc [:backup :default-target-id])})))

(defn- default-target-for
  [configs explicit]
  (normalize-target-id
   (or explicit
       (when (contains? configs :local) :local)
       (first (keys configs)))))

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
    :config-store optional `{:read fn :commit! fn}` settings-document port;
                  targets saved through :backup/save-target persist under
                  [:backup :targets] and the default under
                  [:backup :default-target-id]
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
        settings (settings-targets (:config-store opts))
        configs (target-configs opts (:targets settings))
        ports (into {}
                    (map (fn [[id config]]
                           [id (make-target id config data-dir)]))
                    configs)
        resolved-default (default-target-for
                          configs
                          (or default-target-id
                              (:default-target-id settings)))
        target-state (atom {:configs configs
                            :ports ports
                            :default-target-id resolved-default})]
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
                     nil
                     nil
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
                            :app-version app-version
                            :config-store (:config-store opts)
                            :target-state target-state))))

(defn- target-for
  [service payload]
  (let [state (target-state service)
        id (normalize-target-id
            (or (:target-id payload) (:default-target-id state)))]
    (if-let [target (get (:ports state) id)]
      (if-let [reason (:error target)]
        (err/fail! :BACKUP_TARGET_UNAVAILABLE
                   "The selected backup target is not available"
                   {:target-id id :reason reason})
        (:port target))
      (err/fail! :BACKUP_TARGET_UNKNOWN
                 "The selected backup target does not exist"
                 {:target-id id
                  :available (vec (keys (:ports state)))}))))

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
                     (or (:target-id payload)
                         (:default-target-id (target-state service))))
        state (target-state service)
        selected (get (:ports state) selected-id)
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
               :targets (mapv target-description (vals (:ports state)))
               :default-target-id (:default-target-id state)
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
                   (or (:target-id payload)
                       (:default-target-id (target-state service))))
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

(defn- blank?
  [v]
  (or (nil? v) (and (string? v) (str/blank? v))))

(defn- target-kind
  [config]
  (let [k (:kind config :file)]
    (cond
      (keyword? k) k
      (string? k) (keyword k)
      :else :file)))

(defn- normalize-target-config
  "Map the client form onto the stored target config.

  Field aliases: webdav accepts `:remote-url`/`:endpoint` for `:base-url`,
  s3 accepts `:remote-url`/`:base-url` for `:endpoint` and `:secret` for
  `:secret-key`, and file accepts `:directory`/`:remote-url` as `:root`.
  When `existing` is given, blank secret fields keep their stored value."
  ([config] (normalize-target-config config nil))
  ([config existing]
   (let [raw (if (map? config) config {})
         kind (target-kind raw)
         merged (case kind
                  :webdav
                  (-> raw
                      (dissoc :remote-url :endpoint)
                      (assoc :base-url (or (:remote-url raw)
                                           (:base-url raw)
                                           (:endpoint raw))))
                  :s3
                  (-> raw
                      (dissoc :remote-url :base-url :secret)
                      (assoc :endpoint (or (:endpoint raw)
                                           (:remote-url raw)
                                           (:base-url raw))
                             :secret-key (or (:secret-key raw)
                                             (:secret raw))))
                  (:file :local)
                  (-> raw
                      (dissoc :directory :remote-url)
                      (assoc :root (or (:root raw)
                                       (:directory raw)
                                       (:remote-url raw))))
                  raw)
         merged (cond-> merged
                  (and (= kind :s3)
                       (not (true? (:path-style? merged)))
                       (not (false? (:path-style? merged))))
                  (assoc :path-style? true))
         secret-fields (case kind
                         :webdav [:password]
                         :s3 [:secret-key]
                         [])]
     (reduce (fn [cfg field]
               (if (and existing (blank? (get cfg field)))
                 (if-let [old (get existing field)]
                   (assoc cfg field old)
                   cfg)
                 cfg))
             (assoc merged :kind kind)
             secret-fields))))

(defn- fresh-target-id
  "Pick an unused target id: the kind name, then `-2`, `-3`, ..."
  [kind taken]
  (let [base (name kind)]
    (loop [candidate base
           n 2]
      (let [id (keyword candidate)]
        (if (contains? taken id)
          (recur (str base "-" n) (inc n))
          id)))))

(defn- targets-receipt
  [state]
  {:targets (mapv target-description (vals (:ports state)))
   :default-target-id (:default-target-id state)})

(defn- commit-settings!
  "CAS-commit the whole settings document through `:config-store`,
  retrying on revision conflicts.  Returns `{:persisted? bool}`."
  [service mutate-doc!]
  (let [config-store (:config-store (:opts service))]
    (if-not config-store
      {:persisted? false :committed? false}
      (loop [attempt 0]
        (let [{:keys [revision document]} (config-store-read config-store)
              next-doc (mutate-doc! document)
              result ((:commit! config-store)
                      revision
                      (dissoc next-doc :revision))]
          (case (:status result)
            :committed {:persisted? true :committed? true}
            :conflict
            (if (<= attempt 2)
              (recur (inc attempt))
              (err/fail! :BACKUP_TARGET_INVALID
                         "The settings document kept changing; retry the save"
                         {:attempts (inc attempt)}))
            (err/fail! :BACKUP_FAILED
                       "The settings document could not be committed"
                       {:status (:status result)
                        :error (:error result)})))))))

(defn- persist-targets!
  "Write the current target set into the settings document."
  [service configs default-target-id]
  (commit-settings!
   service
   (fn [document]
     (let [doc (if (seq configs)
                 (assoc-in document [:backup :targets] configs)
                 (update document :backup dissoc :targets))]
       (cond-> doc
         (some? default-target-id)
         (assoc-in [:backup :default-target-id] default-target-id)
         (nil? default-target-id)
         (update :backup dissoc :default-target-id))))))

(defn- replace-port!
  "Rebuild one target's port from its stored config and swap it into the
  live state."
  [service id]
  (update-target-state!
   service
   (fn [state]
     (if-let [config (get (:configs state) id)]
       (assoc state :ports
              (assoc (:ports state)
                     id (make-target id config (:data-dir service))))
       state))))

(defn save-target!
  "Validate, normalize, persist and register one backup target.

  Payload: `{:target-id id|nil :config {...} :default? bool}`.  A missing
  id derives from the target kind (`webdav`, `webdav-2`, ...).  Secrets
  left blank keep the stored value of an existing target."
  [service payload]
  (let [state (target-state service)
        raw-config (:config payload)
        kind (target-kind raw-config)
        supplied-id (normalize-target-id (:target-id payload))
        taken (set (keys (:ports state)))
        target-id (if supplied-id
                    supplied-id
                    (fresh-target-id kind taken))
        existing (get (:configs state) target-id)
        config (normalize-target-config raw-config existing)
        built (make-target target-id config (:data-dir service))]
    (when-let [reason (:error built)]
      (err/fail! :BACKUP_TARGET_INVALID
                 "The backup target configuration is not usable"
                 {:target-id target-id :kind kind :reason reason}))
    (let [configs (assoc (:configs state) target-id config)
          default-target-id (if (true? (:default? payload))
                              target-id
                              (:default-target-id state))
          persisted (persist-targets! service configs default-target-id)
          _ (update-target-state!
             service
             (fn [state]
               (-> state
                   (assoc :configs configs
                          :default-target-id default-target-id)
                   (assoc :ports (assoc (:ports state) target-id built)))))]
      {:ok? true
       :receipt (assoc (targets-receipt (target-state service))
                       :status :accepted
                       :target-id target-id
                       :kind (:kind built)
                       :persisted? (:persisted? persisted))})))

(defn delete-target!
  "Remove one configured backup target.  When the deleted target was the
  default, the first remaining target becomes the default."
  [service payload]
  (let [state (target-state service)
        target-id (normalize-target-id (:target-id payload))]
    (when-not (contains? (:ports state) target-id)
      (err/fail! :BACKUP_TARGET_UNKNOWN
                 "The selected backup target does not exist"
                 {:target-id target-id
                  :available (vec (keys (:ports state)))}))
    (let [configs (dissoc (:configs state) target-id)
          default-target-id (if (= target-id (:default-target-id state))
                              (when (seq configs)
                                (->> (keys configs)
                                     (sort-by str)
                                     first))
                              (:default-target-id state))
          _ (persist-targets! service configs default-target-id)
          _ (update-target-state!
             service
             (fn [state]
               (-> state
                   (assoc :configs configs
                          :default-target-id default-target-id)
                   (assoc :ports (dissoc (:ports state) target-id)))))]
      {:ok? true
       :receipt (assoc (targets-receipt (target-state service))
                       :status :accepted
                       :target-id target-id
                       :deleted? true)})))

(defn- default-prober
  "Dispatch a connectivity probe by target kind."
  [kind config data-dir]
  (case kind
    (:file :local) (file-target/probe (:root config) data-dir)
    :webdav (webdav/probe config)
    :s3 (s3/probe config)
    {:ok? false :status nil
     :detail (str "unsupported backup target kind: " kind)
     :url nil}))

(defn- probe-target!
  [service kind config target-id]
  (let [prober (or (:target-prober (:opts service)) default-prober)
        probe (try
                (prober kind config (:data-dir service))
                (catch Throwable t
                  {:ok? false :status nil
                   :detail (or (ex-message t) (str t))
                   :url nil}))]
    {:ok? (true? (:ok? probe))
     :kind kind
     :target-id target-id
     :status (:status probe)
     :detail (:detail probe)
     :url (:url probe)}))

(defn test-target!
  "Probe one target without persisting anything.

  `{:target-id id}` probes the stored config (secrets included);
  `{:config {...}}` normalizes a form payload first.  A failed probe is
  still a successful command: the receipt carries `:probe {:ok? false}`."
  [service payload]
  (let [state (target-state service)
        supplied-id (normalize-target-id (:target-id payload))
        {:keys [kind config target-id]}
        (if supplied-id
          (if-let [stored (get (:configs state) supplied-id)]
            {:kind (target-kind stored)
             :config stored
             :target-id supplied-id}
            (err/fail! :BACKUP_TARGET_UNKNOWN
                       "The selected backup target does not exist"
                       {:target-id supplied-id
                        :available (vec (keys (:ports state)))}))
          (let [raw (:config payload)
                kind (target-kind raw)
                config (normalize-target-config raw nil)]
            {:kind kind :config config :target-id nil}))
        ;; Validate the same way save-target does; an unbuildable config is
        ;; a probe failure, not a command failure.
        built (try (make-target (or target-id :probe) config (:data-dir service))
                   (catch Throwable t {:error (or (ex-message t) (str t))}))
        probe (if-let [reason (:error built)]
                {:ok? false :kind kind :target-id target-id
                 :status nil
                 :detail (str "target configuration is not usable: " reason)
                 :url nil}
                (probe-target! service kind config target-id))]
    {:ok? true
     :receipt {:status :complete
               :command-id (:command-id payload)
               :probe probe}}))

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
        :backup/save-target (save-target! service payload)
        :backup/test-target (test-target! service payload)
        :backup/delete-target (delete-target! service payload)
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
