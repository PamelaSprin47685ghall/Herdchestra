(ns hc.server.backup.error
  "Expected failures of the backup and restore pipeline.

  A backup failure is an exception, not a boolean result: a wrong password, a
  corrupt container and an unsupported format must stay distinguishable so the
  caller can tell the user what to do.  Every expected failure carries an
  `:error` keyword and a human-readable message."
  (:require [clojure.string :as str]))

(def codes
  "Every expected failure code this package and its adapters raise.  Keeping
  the set closed lets callers rely on the documented vocabulary."
  #{:BACKUP_ATOMIC_MOVE_UNSUPPORTED
    :BACKUP_ATTACHMENTS_TOO_LARGE
    :BACKUP_ATTACHMENT_TOO_LARGE
    :BACKUP_BLOBS_UNAVAILABLE
    :BACKUP_CANCELLED
    :BACKUP_CATEGORY_INVALID
    :BACKUP_CATEGORY_MISSING
    :BACKUP_CATEGORY_REQUIRED
    :BACKUP_CATEGORY_UNKNOWN
    :BACKUP_COMMAND_EPOCH_WINDOW_INVALID
    :BACKUP_COMMAND_EPOCH_WINDOW_MISSING
    :BACKUP_COMMAND_INVALID
    :BACKUP_COMMAND_UNKNOWN
    :BACKUP_CONFLICT_RESOLUTION_INVALID
    :BACKUP_DELETE_UNCONFIRMED
    :BACKUP_ENCRYPTION_UNSUPPORTED
    :BACKUP_EXTENSIONS_INCOMPLETE
    :BACKUP_EXTENSIONS_RESTORE_FAILED
    :BACKUP_EXTENSIONS_UNAVAILABLE
    :BACKUP_FAILED
    :BACKUP_FORMAT_INVALID
    :BACKUP_ID_INVALID
    :BACKUP_INTEGRITY
    :BACKUP_IO
    :BACKUP_MAINTENANCE_ACK_INVALID
    :BACKUP_MAINTENANCE_ACTIVE
    :BACKUP_MAINTENANCE_FAILED
    :BACKUP_MAINTENANCE_GATE_REOPENED
    :BACKUP_MAINTENANCE_ID_CONFLICT
    :BACKUP_MAINTENANCE_ID_INVALID
    :BACKUP_MAINTENANCE_INTERRUPTED
    :BACKUP_MAINTENANCE_INVALID
    :BACKUP_MAINTENANCE_JOURNAL_FAILED
    :BACKUP_MAINTENANCE_JOURNAL_INVALID
    :BACKUP_MAINTENANCE_LOCKED
    :BACKUP_MAINTENANCE_NOT_FOUND
    :BACKUP_MAINTENANCE_OUTCOME_UNKNOWN
    :BACKUP_MAINTENANCE_PREVIEW_INVALID
    :BACKUP_MAINTENANCE_RECORD_REPLACED
    :BACKUP_MAINTENANCE_RESTART_FAILED
    :BACKUP_MAINTENANCE_RESTORE_FAILED
    :BACKUP_MAINTENANCE_STATE_CHANGED
    :BACKUP_MAINTENANCE_STATUS_FAILED
    :BACKUP_MAINTENANCE_STORE_UNCHANGED
    :BACKUP_MAINTENANCE_UNAVAILABLE
    :BACKUP_MODE_INVALID
    :BACKUP_NOT_FOUND
    :BACKUP_OBJECT_CONFLICT
    :BACKUP_OFFLINE_RESTORE_INCOMPLETE
    :BACKUP_PASSWORD_BLANK
    :BACKUP_PASSWORD_INVALID
    :BACKUP_PASSWORD_MISMATCH
    :BACKUP_PASSWORD_REQUIRED
    :BACKUP_PASSWORD_TOO_SHORT
    :BACKUP_PLAINTEXT_SECRETS_UNCONFIRMED
    :BACKUP_PREVIEW_STALE
    :BACKUP_QUIESCE_NOT_CONFIRMED
    :BACKUP_QUIESCE_REQUIRED
    :BACKUP_QUIESCE_STOP_FAILED
    :BACKUP_RECOVERY_POINT_CONFLICT
    :BACKUP_RECOVERY_POINT_FAILED
    :BACKUP_RECOVERY_POINT_INVALID
    :BACKUP_RECOVERY_POINT_MISSING
    :BACKUP_RESTORE_FAILED
    :BACKUP_RESTORE_REQUIRES_QUIESCENCE
    :BACKUP_RESTORE_UNCONFIRMED
    :BACKUP_SNAPSHOT_INCOHERENT
    :BACKUP_STATE_INVALID
    :BACKUP_STORAGE_CLOSED
    :BACKUP_STORAGE_DUPLICATE_ID
    :BACKUP_STORAGE_FULL
    :BACKUP_TARGET_HTTP
    :BACKUP_TARGET_INVALID
    :BACKUP_TARGET_IO
    :BACKUP_TARGET_UNAVAILABLE
    :BACKUP_TARGET_UNKNOWN
    :BACKUP_VERSION_UNSUPPORTED
    :BACKUP_WORKSPACE_INCOMPLETE
    :BACKUP_WORKSPACE_INVALID
    :BACKUP_WORKSPACE_IO
    :BACKUP_WORKSPACE_RESTORE_FAILED
    :BACKUP_WORKSPACES_INCOMPLETE
    :BACKUP_WORKSPACES_UNAVAILABLE
    :BACKUP_WORKSPACE_TOO_LARGE
    :BACKUP_WRONG_PASSWORD
    :COMMAND_EPOCH_GATE_MISSING
    :COMMAND_EPOCH_ISSUE_FAILED
    :COMMAND_EPOCH_ROTATE_FAILED
    :COMMAND_EPOCH_STORE_MISSING
    :COMMAND_EPOCH_SYNC_FAILED
    :COMMAND_EPOCH_WINDOW_INVALID
    :COMMAND_EPOCH_WINDOW_MISSING
    :COMMAND_EPOCH_WINDOW_UNAVAILABLE})

(defn fail!
  "Throw one expected backup failure."
  ([code message]
   (fail! code message nil nil))
  ([code message data]
   (fail! code message data nil))
  ([code message data cause]
   (throw (ex-info message (merge {:error code} data) cause))))

(defn expected?
  "True for an ex-info value produced by `fail!`."
  [t]
  (and (instance? clojure.lang.ExceptionInfo t)
       (keyword? (:error (ex-data t)))))

(defn code
  "The `:error` keyword of an expected failure, or nil."
  [t]
  (when (expected? t)
    (:error (ex-data t))))

(defn io-failure!
  "Wrap an unexpected I/O failure without losing its cause."
  [message data ^Throwable cause]
  (fail! :BACKUP_IO message data cause))

(defn describe
  "Short text for logs.  Never used as a result code."
  [t]
  (if (expected? t)
    (str/join " " (remove nil? [(:error (ex-data t))
                                (:id (ex-data t))
                                (.getMessage ^Throwable t)]))
    (str t)))
