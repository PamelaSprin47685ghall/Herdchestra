(ns hc.server.app.backup
  "Application-level backup commands.

  Backup and restore are maintenance operations, not session commands.  This
  adapter exposes list/create/preview/verify/recovery-point commands and
  refuses a live whole-store restore: the caller must stop Owners and other
  writers, then run restore through the offline entry point.  It never
  pretends a live restore succeeded."
  (:require [clojure.string :as str]
            [hc.server.sessions.ports :as ports]
            [hc.server.backup :as backup]
            [hc.server.backup.service :as backup-service]))

(defn service
  "Build the backup service from the open store and application config."
  [{:keys [store data-dir config maintenance persistence]}]
  (let [config-store (when persistence
                       {:read (fn [] (ports/read-config persistence))
                        :commit! (fn [expected-revision doc]
                                   (ports/commit-config!
                                    persistence expected-revision doc))})]
    (backup-service/service
     {:store store
      :data-dir data-dir
      :target-root (get-in config [:backup :dir])
      :recovery-root (get-in config [:backup :recovery-dir])
      :targets (get-in config [:backup :targets])
      :target (get-in config [:backup :target])
      :default-target-id (get-in config [:backup :default-target-id])
      :maintenance maintenance
      :config-store config-store
      :config config})))

(defn- ok
  [receipt]
  {:ok? true :receipt (assoc receipt :status (or (:status receipt) :accepted))})

(defn- failure
  [t]
  {:ok? false
   :error (if (backup/expected-error? t)
            (ex-data t)
            {:code :BACKUP_FAILED
             :message (or (ex-message t) (str t))})})

(defn- list-backups
  [service]
  (try
    (ok {:backups (backup/list-backups (:target service))})
    (catch Throwable t (failure t))))

(defn- create!
  [service payload]
  (try
    (let [result (backup/backup! (:domain service) (:target service)
                                 (select-keys payload
                                              [:password :categories :encrypt?
                                               :include-secrets?
                                               :confirm-plaintext-secrets?
                                               :sampled-at :purpose]))]
      (ok result))
    (catch Throwable t (failure t))))

(defn- preview!
  [service payload]
  (try
    (let [result (backup/preview (:domain service) (:target service)
                                 (:backup-id payload)
                                 (select-keys payload
                                              [:password :mode :categories
                                               :resolutions
                                               :confirm-memory-reactivation?]))]
      (if (:ok? result)
        (ok result)
        result))
    (catch Throwable t (failure t))))

(defn- verify!
  [service payload]
  (try
    (ok (backup/verify-backup (:target service) (:backup-id payload)
                              (select-keys payload [:password])))
    (catch Throwable t (failure t))))

(defn- recovery-points
  [service]
  (try
    (ok {:recovery-points (backup/list-recovery-points (:recovery service))})
    (catch Throwable t (failure t))))

(defn- maintenance-status
  [service]
  (if-let [status-fn (get-in service [:opts :maintenance :status])]
    (try
      (status-fn)
      (catch Throwable t
        {:available? false
         :mode :supervised
         :reason :MAINTENANCE_STATUS_FAILED
         :error {:code :BACKUP_MAINTENANCE_STATUS_FAILED
                 :message (or (ex-message t) (str t))}}))
    {:available? false
     :mode :offline-only
     :reason :NO_IN_PROCESS_QUIESCE
     :command "pixi run backup"}))

(defn- maintenance-command!
  [service payload]
  (if-let [handler (get-in service [:opts :maintenance :handle!])]
    (handler payload)
    {:ok? false
     :error {:code :BACKUP_MAINTENANCE_UNAVAILABLE
             :message
             "supervised maintenance is not installed in this process"
             :details {:kind (:kind payload)}}}))

(defn handle!
  "Handle one `:backup/*` payload.

  Live restore and restore-recovery-point are refused: they require stopping
  all Owners and writers.  Use `hc.server.backup.offline` for a quiescent
  restore.  All other kinds delegate to the real backup service."
  [service payload]
  (case (:kind payload)
    :backup/status
    (let [result (backup-service/handle! service payload)]
      (if (:receipt result)
        (update result :receipt assoc
                :maintenance (maintenance-status service))
        result))
    (:backup/maintenance-restore
     :backup/maintenance-status
     :backup/maintenance-restore-recovery-point)
    (maintenance-command! service payload)
    (:backup/restore :backup/restore-recovery-point)
    {:ok? false
     :error {:code :BACKUP_RESTORE_REQUIRES_QUIESCENCE
             :message
             "live restore is refused; stop Owners and writers, then restore offline"
             :details {:backup-id (:backup-id payload)
                       :recovery-point-id (:recovery-point-id payload)
                       :required :quiescent-whole-store}}}
    (backup-service/handle! service payload)))
