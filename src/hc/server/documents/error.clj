(ns hc.server.documents.error
  "Expected refusals for document extraction.

  Extraction returns ordinary result maps.  A refusal is

      {:ok? false
       :error {:code :KEYWORD :message \"...\" ...}}

  and never carries a partial text claim.  Unexpected JVM failures are
  converted at the adapter boundary; callers do not parse exception text."
  (:require [clojure.string :as str]))

(defn failure
  "One expected refusal."
  ([code message]
   (failure code message nil))
  ([code message data]
   {:ok? false
    :error (merge {:code code :message message} data)}))

(defn warning
  "One non-fatal observation.  Warnings never make a refusal look successful."
  ([code message]
   (warning code message nil))
  ([code message data]
   (merge {:code code :message message} data)))

(defn expected?
  "True for a result map produced by `failure`."
  [value]
  (and (map? value)
       (false? (:ok? value))
       (keyword? (get-in value [:error :code]))))

(defn code
  "The refusal code, or nil for a success."
  [value]
  (when (expected? value)
    (get-in value [:error :code])))

(defn safe-message
  "A fixed, secret-free message for an unexpected exception.

  Exception text can contain a password, a path or a provider payload.  Only
  the exception class is reported; callers attach source identity separately
  and use a stable refusal code for user-facing repair."
  [^Throwable t]
  (str "operation failed (" (.getSimpleName (class t)) ")"))
