(ns hc.server.browser.json
  "Minimal JSON bridge for the Chrome DevTools Protocol.

  CDP payloads are JSON objects.  The browser package keeps page data in
  ordinary Clojure maps with string keys so a page cannot inject keyword
  keys into the adapter's own control data.  This namespace owns only the
  transport conversion; it does not interpret CDP methods."
  (:import [com.fasterxml.jackson.databind ObjectMapper]
           [java.util List Map]))

(def ^:private mapper (ObjectMapper.))

(defn- out-value
  [value]
  (cond
    (or (nil? value) (string? value) (boolean? value)) value
    (integer? value) value
    (number? value) (double value)
    (keyword? value) (name value)
    (symbol? value) (name value)
    (map? value) (into (sorted-map)
                       (map (fn [[k v]] [(if (keyword? k) (name k) (str k))
                                         (out-value v)]))
                       value)
    (set? value) (mapv out-value (sort-by pr-str value))
    (sequential? value) (mapv out-value value)
    :else (throw (ex-info "value cannot be represented as JSON"
                          {:error/kind :json-value-invalid
                           :class (some-> value class .getName)}))))

(defn encode
  "Encode one Clojure value as a JSON string."
  [value]
  (.writeValueAsString mapper (out-value value)))

(defn- in-value
  [value]
  (cond
    (instance? Map value)
    (persistent!
     (reduce (fn [m [k v]] (assoc! m (str k) (in-value v)))
             (transient {})
             value))
    (instance? List value)
    (mapv in-value value)
    :else value))

(defn decode
  "Decode one JSON string into maps with string keys."
  [text]
  (in-value (.readValue mapper ^String text Object)))
