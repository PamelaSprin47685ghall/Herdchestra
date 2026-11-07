(ns hc.server.storage.error
  "Expected failures for the current-state store.

  Every expected failure is an `ExceptionInfo` with an `:error` keyword.
  Callers classify it with `expected?`, `code` and `describe`; they must not
  parse the message text.  An unexpected failure is a real bug or a hard
  storage failure and is allowed to escape.

  Root AGENTS.md §7.1: an open failure must stop side-effect dispatch and
  offer a recovery entry.  It must never create or replace an empty database.")

(def codes
  "Every expected error code this package raises."
  #{:STORAGE_ALREADY_OPEN
    :STORAGE_CLOSED
    :STORAGE_CLOSING
    :STORAGE_CORRUPT
    :STORAGE_DRAIN_INCOMPLETE
    :STORAGE_DRAIN_TIMEOUT
    :STORAGE_FORMAT_MISMATCH
    :STORAGE_INVALID_EXPORT
    :STORAGE_INVALID_KEY
    :STORAGE_INVALID_MIGRATION
    :STORAGE_INVALID_OPTIONS
    :STORAGE_LOCKED
    :STORAGE_MIGRATION_CONFLICT
    :STORAGE_NOT_A_STORE
    :STORAGE_NOT_FOUND
    :STORAGE_NOT_INITIALIZED
    :STORAGE_OPEN_FAILED
    :STORAGE_READ_FAILED
    :STORAGE_READ_UNSTABLE
    :STORAGE_RESTORE_REQUIRES_QUIESCENCE
    :STORAGE_TX_FAILED
    :STORAGE_VERSION_CONFLICT})

(defn raise
  "Throw an expected storage failure."
  ([code message] (raise code message {}))
  ([code message data]
   (throw (ex-info message (assoc data :error code :storage/expected? true)))))

(defn expected?
  "True when `e` is an expected storage failure."
  [e]
  (and (instance? clojure.lang.ExceptionInfo e)
       (true? (:storage/expected? (ex-data e)))
       (contains? codes (:error (ex-data e)))))

(defn code
  "Return the error keyword, or nil when `e` is not an expected failure."
  [e]
  (when (expected? e) (:error (ex-data e))))

(defn describe
  "Return a plain data description suitable for logs and recovery entries."
  [e]
  (if (expected? e)
    (let [data (ex-data e)]
      {:error (:error data)
       :message (ex-message e)
       :data (dissoc data :error :storage/expected?)
       :cause (some-> (ex-cause e) ex-message)})
    {:error :UNEXPECTED
     :message (ex-message e)
     :class (some-> e class str)
     :cause (some-> (ex-cause e) ex-message)}))

(defn wrap
  "Attach `code` to an unexpected throwable without hiding its cause."
  [code message e data]
  (ex-info message
           (assoc data :error code :storage/expected? true)
           e))
