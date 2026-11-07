(ns hc.server.backup
  "Public entry point for encrypted backup, restore preview and recovery.

  Typical flow:

      (def target (hc.server.backup.file-target/store \"/var/lib/hc/backups\"))
      (def recovery (hc.server.backup.file-recovery/store \"/var/lib/hc/recovery\"))

      (backup! domain target {:password \"correct horse battery staple\"})
      (preview-restore domain target id {:password \"...\" :mode :merge})
      (restore! domain target recovery id {:password \"...\" :mode :merge})

  `domain` implements `hc.server.backup.port/DomainPort`.  The backup module
  never opens a database itself."
  (:require [hc.server.backup.core :as core]
            [hc.server.backup.crypto :as crypto]
            [hc.server.backup.error :as err]
            [hc.server.backup.model :as model]
            [hc.server.backup.recovery :as recovery]))

(def categories
  "Ordered backup category keywords."
  model/category-order)

(def category-info
  model/category-info)

(def default-options
  core/default-options)

(def default-settings
  crypto/default-settings)

(def min-password-length
  crypto/min-password-length)

(defn validate-password
  [password]
  (crypto/validate-password password))

(defn validate-password-confirmation
  [password confirmation]
  (crypto/validate-password-confirmation password confirmation))

(defn backup!
  [domain target opts]
  (core/backup! domain target opts))

(defn open-backup
  "Read and verify a backup.  `password-or-opts` may be the password string or
  a map containing `:password`."
  [target backup-id password-or-opts]
  (core/open-backup target backup-id
                    (if (map? password-or-opts)
                      (:password password-or-opts)
                      password-or-opts)))

(defn preview-restore
  [domain target backup-id opts]
  (core/preview-restore domain target backup-id opts))

(defn preview-fingerprint
  "Stable fingerprint of the restore plan a maintenance operator confirmed."
  [preview]
  (core/preview-fingerprint preview))

(defn preview
  "UI-friendly preview that returns {:ok? false :error ...} for expected
  failures instead of throwing."
  [domain target backup-id opts]
  (core/preview domain target backup-id opts))

(defn restore!
  [domain target recovery-store backup-id opts]
  (core/restore! domain target recovery-store backup-id opts))

(defn list-backups
  [target]
  (core/list-backups target))

(defn verify-backup
  [target backup-id opts]
  (core/verify-backup target backup-id opts))

(defn list-recovery-points
  [recovery-store]
  (core/list-recovery-points recovery-store))

(defn read-recovery-point
  [recovery-store id]
  (core/read-recovery-point recovery-store id))

(defn delete-recovery-point!
  [recovery-store id]
  (core/delete-recovery-point! recovery-store id))

(defn prune-recovery-points!
  [recovery-store opts]
  (core/prune-recovery-points! recovery-store opts))

(defn restore-recovery-point!
  [domain recovery-store id opts]
  (core/restore-recovery-point! domain recovery-store id opts))

(defn error-code
  [t]
  (err/code t))

(defn expected-error?
  [t]
  (err/expected? t))

(def retention-policy
  recovery/default-policy)

(defn plan-retention
  [points opts]
  (recovery/plan-retention points opts))
