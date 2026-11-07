(ns hc.server.speech.error
  "Expected failures of the speech adapters.

  An expected failure is an ExceptionInfo with `:error/kind :speech`,
  `:error/code`, and optional `:error/details`.  Callers turn it into a
  receipt.  They never expose a stack trace or a credential."
  (:require [clojure.string :as str]))

(defn fail!
  [code message details]
  (throw (ex-info message
                  (cond-> {:error/kind :speech
                           :error/code code}
                    (some? details) (assoc :error/details details)))))

(defn expected?
  [t]
  (and (instance? clojure.lang.ExceptionInfo t)
       (= :speech (:error/kind (ex-data t)))))

(defn code
  [t]
  (:error/code (ex-data t)))

(defn error-map
  "Return a plain error map.  Unexpected failures do not leak their message
  because a provider or filesystem message may contain a path or a secret."
  [t]
  (if (expected? t)
    (let [data (ex-data t)]
      (cond-> {:code (:error/code data)
               :message (or (ex-message t) "speech request failed")}
        (some? (:error/details data)) (assoc :details (:error/details data))))
    {:code :SPEECH_INTERNAL_ERROR
     :message "speech component failed"
     :details {:class (some-> t class .getName)}}))

(defn redact-message
  "Keep a provider message readable without allowing an unbounded body."
  [value]
  (let [text (cond
               (nil? value) nil
               (string? value) value
               :else (str value))]
    (when-not (str/blank? text)
      (subs text 0 (min 1000 (count text))))))
