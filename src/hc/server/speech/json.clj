(ns hc.server.speech.json
  "Small JSON bridge for the speech HTTP adapters.

  Jackson is already on the server classpath.  This namespace owns the only
  conversion point so provider payloads never force a particular key shape
  into the rest of the component: maps leave with string keys and arrive with
  keyword keys."
  (:require [hc.server.speech.error :as error])
  (:import (com.fasterxml.jackson.databind ObjectMapper)
           (java.util Map Map$Entry)))

(def ^:private mapper (ObjectMapper.))

(defn- out-key
  [k]
  (cond
    (keyword? k) (name k)
    (symbol? k) (name k)
    (string? k) k
    :else (error/fail! :SPEECH_JSON_KEY_INVALID
                       "JSON object keys must be strings, keywords or symbols"
                       {:key k})))

(defn- normalize-out
  [value]
  (cond
    (or (nil? value) (string? value) (boolean? value)) value
    (integer? value) value
    (number? value) (double value)
    (keyword? value) (name value)
    (symbol? value) (name value)
    (map? value) (into {}
                       (map (fn [[k v]] [(out-key k) (normalize-out v)]))
                       value)
    (set? value) (mapv normalize-out (sort-by pr-str value))
    (sequential? value) (mapv normalize-out value)
    :else (error/fail! :SPEECH_JSON_VALUE_INVALID
                       "value cannot be represented as JSON"
                       {:class (some-> value class .getName)})))

(defn encode
  [value]
  (try
    (.writeValueAsString mapper (normalize-out value))
    (catch Throwable t
      (if (error/expected? t)
        (throw t)
        (error/fail! :SPEECH_JSON_ENCODE_FAILED
                     "could not encode JSON request"
                     {:message (.getMessage t)})))))

(defn- in-key
  [k]
  (let [k (str k)]
    (if (empty? k) k (keyword k))))

(defn- normalize-in
  [value]
  (cond
    (or (nil? value) (string? value) (boolean? value)) value
    (integer? value) value
    (number? value) value
    (instance? Map value)
    (persistent!
     (reduce (fn [m ^Map$Entry entry]
               (assoc! m
                       (in-key (.getKey entry))
                       (normalize-in (.getValue entry))))
             (transient {})
             (.entrySet ^Map value)))
    (instance? java.util.List value)
    (mapv normalize-in value)
    :else value))

(defn decode
  [text]
  (try
    (normalize-in (.readValue mapper ^String text Object))
    (catch Throwable t
      (error/fail! :SPEECH_JSON_DECODE_FAILED
                   "could not parse JSON response"
                   {:message (.getMessage t)}))))
