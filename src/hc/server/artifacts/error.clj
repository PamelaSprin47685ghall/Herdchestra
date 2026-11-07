(ns hc.server.artifacts.error
  "Expected artifact-registration failures.

  An artifact is a delivered object, not a path that happened to appear in
  tool output.  Every refusal therefore carries a stable `:error` keyword and
  the checks that were performed, so a caller can show why the object was not
  announced without inventing a success state."
  (:require [clojure.string :as str]))

(defn fail!
  "Throw one expected artifact failure."
  ([code message]
   (fail! code message nil nil))
  ([code message data]
   (fail! code message data nil))
  ([code message data cause]
   (throw (ex-info message
                   (merge {:error code
                           :artifacts/expected? true}
                          data)
                   cause))))

(defn expected?
  "True for failures produced by `fail!`."
  [t]
  (and (instance? clojure.lang.ExceptionInfo t)
       (true? (:artifacts/expected? (ex-data t)))))

(defn code
  "The stable error keyword, or nil."
  [t]
  (when (expected? t)
    (:error (ex-data t))))

(defn describe
  "One-line description for logs.  Never used as a result code."
  [t]
  (if (expected? t)
    (str/join " " (remove nil? [(:error (ex-data t))
                                (:path (ex-data t))
                                (.getMessage ^Throwable t)]))
    (str t)))
