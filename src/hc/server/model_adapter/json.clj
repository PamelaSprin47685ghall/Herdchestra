(ns hc.server.model-adapter.json
  "Deterministic JSON bridge for provider tool arguments and schemas.

  LangChain4j transports tool arguments as JSON strings.  The kernel stores
  EDN.  This namespace owns the only conversion point: maps get string keys on
  the way out and keyword keys on the way in.  It rejects values JSON cannot
  represent instead of letting Jackson invent a lossy shape."
  (:require [clojure.string :as str])
  (:import [com.fasterxml.jackson.databind ObjectMapper]
           [java.util Map Map$Entry]))

(def ^:private mapper (ObjectMapper.))

(defn- out-key
  [k]
  (cond
    (keyword? k) (name k)
    (symbol? k) (name k)
    (string? k) k
    :else (throw (ex-info "JSON object keys must be strings, keywords, or symbols"
                          {:error/kind :json-key-invalid
                           :key k}))))

(defn- normalize-out
  [value]
  (cond
    (or (nil? value) (string? value) (boolean? value)) value
    (integer? value) value
    (number? value) (double value)
    (keyword? value) (name value)
    (symbol? value) (name value)
    (map? value)
    (reduce-kv
     (fn [result key child]
       (let [key' (out-key key)]
         (when (contains? result key')
           (throw (ex-info "JSON object keys collide after normalization"
                           {:error/kind :json-key-collision
                            :key key'})))
         (assoc result key' (normalize-out child))))
     (sorted-map)
     value)
    (set? value) (mapv normalize-out (sort-by pr-str value))
    (sequential? value) (mapv normalize-out value)
    :else (throw (ex-info "value cannot be represented as JSON"
                          {:error/kind :json-value-invalid
                           :class (some-> value class .getName)}))))

(defn encode
  "Encode EDN data as a JSON string.  Object keys are sorted for stable tests."
  [value]
  (try
    (.writeValueAsString mapper (normalize-out value))
    (catch Throwable t
      (throw (ex-info "could not encode value as JSON"
                      {:error/kind :json-encode-failed
                       :message (.getMessage t)}
                      t)))))

(defn- in-key
  [key-mode k]
  (cond
    (= :string key-mode) k
    (= :keyword key-mode) (if (str/blank? k) k (keyword k))
    (fn? key-mode) (key-mode k)
    :else (throw (ex-info "JSON key mode must be :keyword, :string, or a function"
                          {:error/kind :json-key-mode-invalid
                           :key-mode key-mode}))))

(defn- normalize-in
  [value key-mode]
  (cond
    (or (nil? value) (string? value) (boolean? value)) value
    (integer? value) value
    (number? value) value
    (instance? Map value)
    (persistent!
     (reduce (fn [m ^Map$Entry entry]
               (assoc! m
                       (in-key key-mode (str (.getKey entry)))
                       (normalize-in (.getValue entry) key-mode)))
             (transient {})
             (.entrySet ^Map value)))
    (instance? java.util.List value)
    (mapv #(normalize-in % key-mode) value)
    :else value))

(defn decode
  "Decode one JSON string.  `key-mode` is `:keyword` (default), `:string`, or
  a function from string key to key."
  ([text] (decode text :keyword))
  ([text key-mode]
   (try
     (normalize-in (.readValue mapper text Object) key-mode)
     (catch Throwable t
       (throw (ex-info "could not parse JSON"
                       {:error/kind :json-decode-failed
                        :message (.getMessage t)}
                       t))))))

(defn normalize-keys
  "Copy a map into string-keyed JSON-shaped data without encoding it."
  [value]
  (normalize-out value))
