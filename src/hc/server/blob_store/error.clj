(ns hc.server.blob-store.error
  "Failures of the managed blob store.

  Blob-store failures are exceptions, not ordinary result maps: a missing or
  corrupt object is a storage inconsistency that callers must not confuse with
  a business refusal.  Every expected failure carries an `:error` keyword so
  callers can branch without parsing messages."
  (:require [clojure.string :as str]))

(defn fail!
  "Throw one expected blob-store failure."
  ([code message]
   (fail! code message nil nil))
  ([code message data]
   (fail! code message data nil))
  ([code message data cause]
   (throw (ex-info message (merge {:error code} data) cause))))

(defn expected?
  "True for the ex-info values produced by `fail!`."
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
  (fail! :BLOB_IO message data cause))

(defn describe
  "Short text for logs.  Never used as a result code."
  [t]
  (if (expected? t)
    (str/join " " (remove nil? [(:error (ex-data t))
                                (:id (ex-data t))
                                (:path (ex-data t))
                                (.getMessage ^Throwable t)]))
    (str t)))
