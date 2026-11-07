(ns hc.server.extensions.error
  "Expected failures of skill and MCP configuration.

  Public operations return `{:ok? false :error {...}}` so a caller can keep
  the previous catalog and show a precise refusal.  Low-level functions use
  `fail!` for the same shape; `->result` converts an exception at the public
  boundary.  A refusal is never a boolean: it names a code and keeps the
  details that let the user fix the input."
  (:require [clojure.string :as str]))

(defn fail!
  "Throw one expected failure."
  ([code message]
   (fail! code message nil))
  ([code message details]
   (throw (ex-info message (merge {:error code} details)))))

(defn expected?
  "True for an ex-info produced by `fail!`."
  [t]
  (and (instance? clojure.lang.ExceptionInfo t)
       (keyword? (:error (ex-data t)))))

(defn code
  "The `:error` keyword of an expected failure, or nil."
  [t]
  (when (expected? t)
    (:error (ex-data t))))

(defn error-map
  "Convert an expected exception into the public error map."
  [t]
  (if (expected? t)
    (let [data (ex-data t)]
      {:code (:error data)
       :message (.getMessage ^Throwable t)
       :details (dissoc data :error)})
    {:code :EXTENSION_INTERNAL
     :message (or (.getMessage ^Throwable t) (str t))
     :details {:class (some-> t class .getName)}}))

(defn ->result
  "Run `f`, returning either `{:ok? true :value v}` or a refusal."
  [f]
  (try
    {:ok? true :value (f)}
    (catch clojure.lang.ExceptionInfo t
      (if (expected? t)
        {:ok? false :error (error-map t)}
        (throw t)))))

(defn describe
  "One-line diagnostic text.  Never use it as a result code."
  [t]
  (if (expected? t)
    (str/join " " (remove nil? [(:error (ex-data t))
                                (:path (ex-data t))
                                (:name (ex-data t))
                                (.getMessage ^Throwable t)]))
    (str t)))
