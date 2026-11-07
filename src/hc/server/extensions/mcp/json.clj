(ns hc.server.extensions.mcp.json
  "A small strict JSON codec for the MCP wire.

  No JSON library is on the base classpath, and a transitive dependency of the
  server alias would make the extension tests depend on a different runtime
  than the product.  This codec is intentionally limited to JSON values:
  objects become maps with string keys, arrays become vectors, and numbers
  become Long or Double.  Duplicate object keys are refused because they make
  a configuration import ambiguous.

  The writer emits compact JSON with no insignificant whitespace.  It never
  writes a map key that is not a string, keyword or symbol."
  (:require [hc.server.extensions.error :as error])
  (:import (java.util LinkedHashMap)))

(def ^:private max-depth 100)

(defn- fail! [message position]
  (error/fail! :MCP_JSON_PARSE message {:position position}))

(defn- whitespace?
  [c]
  (or (= c \space) (= c \tab) (= c \newline) (= c \return)))

(defn- skip-whitespace
  [^String s i]
  (loop [i i]
    (if (and (< i (.length s)) (whitespace? (.charAt s i)))
      (recur (inc i))
      i)))

(defn- expect-char
  [^String s i expected]
  (when (or (>= i (.length s)) (not= (.charAt s i) expected))
    (fail! (str "expected '" expected "'") i))
  (inc i))

(defn- hex-value
  [c]
  (let [n (int c)]
    (cond
      (and (>= n (int \0)) (<= n (int \9))) (- n (int \0))
      (and (>= n (int \a)) (<= n (int \f))) (+ 10 (- n (int \a)))
      (and (>= n (int \A)) (<= n (int \F))) (+ 10 (- n (int \A)))
      :else nil)))

(defn- read-unicode-escape
  [^String s i]
  (when (> (+ i 4) (.length s))
    (fail! "incomplete unicode escape" i))
  (let [value (loop [n 0 k 0]
                (if (= k 4)
                  n
                  (let [h (hex-value (.charAt s (+ i k)))]
                    (if (nil? h)
                      (fail! "invalid unicode escape" (+ i k))
                      (recur (+ (* n 16) h) (inc k))))))]
    [(char value) (+ i 4)]))

(defn- read-string-literal
  [^String s i]
  (let [start (inc i)
        sb (StringBuilder.)]
    (loop [i start]
      (when (>= i (.length s))
        (fail! "unterminated string" start))
      (let [c (.charAt s i)]
        (cond
          (= c \")
          [(str sb) (inc i)]

          (= c \\)
          (let [j (inc i)]
            (when (>= j (.length s))
              (fail! "unterminated escape" j))
            (let [esc (.charAt s j)]
              (case esc
                \" (do (.append sb \") (recur (inc j)))
                \\ (do (.append sb \\) (recur (inc j)))
                \/ (do (.append sb \/) (recur (inc j)))
                \b (do (.append sb \backspace) (recur (inc j)))
                \f (do (.append sb \formfeed) (recur (inc j)))
                \n (do (.append sb \newline) (recur (inc j)))
                \r (do (.append sb \return) (recur (inc j)))
                \t (do (.append sb \tab) (recur (inc j)))
                \u (let [[ch next] (read-unicode-escape s (inc j))]
                     (.append sb ch)
                     (recur next))
                (fail! (str "invalid escape '\\" esc "'") j))))

          (< (int c) 0x20)
          (fail! "control character in string" i)

          :else
          (do (.append sb c) (recur (inc i))))))))

(defn- digit?
  [c]
  (let [n (int c)]
    (and (>= n (int \0)) (<= n (int \9)))))

(defn- read-number
  [^String s i]
  (let [len (.length s)
        start i
        i (if (and (< i len) (= (.charAt s i) \-)) (inc i) i)]
    (when (>= i len)
      (fail! "incomplete number" start))
    (let [i (if (= (.charAt s i) \0)
              (inc i)
              (if (digit? (.charAt s i))
                (loop [i i]
                  (if (and (< i len) (digit? (.charAt s i))) (recur (inc i)) i))
                (fail! "invalid number" i)))]
      (let [i (if (and (< i len) (= (.charAt s i) \.))
                (let [j (inc i)]
                  (when (or (>= j len) (not (digit? (.charAt s j))))
                    (fail! "invalid fraction" j))
                  (loop [j j]
                    (if (and (< j len) (digit? (.charAt s j))) (recur (inc j)) j)))
                i)
            i (if (and (< i len) (or (= (.charAt s i) \e) (= (.charAt s i) \E)))
                (let [j (inc i)
                      j (if (and (< j len) (or (= (.charAt s j) \+) (= (.charAt s j) \-)))
                          (inc j)
                          j)]
                  (when (or (>= j len) (not (digit? (.charAt s j))))
                    (fail! "invalid exponent" j))
                  (loop [j j]
                    (if (and (< j len) (digit? (.charAt s j))) (recur (inc j)) j)))
                i)
            token (subs s start i)
            value (if (re-find #"[.eE]" token)
                    (Double/parseDouble token)
                    (Long/parseLong token))]
        [value i]))))

(declare read-value)

(defn- read-object
  [^String s i depth]
  (when (>= depth max-depth)
    (fail! "maximum nesting depth exceeded" i))
  (let [i (skip-whitespace s (inc i))]
    (if (and (< i (.length s)) (= (.charAt s i) \}))
      [{} (inc i)]
      (loop [i i
             result (LinkedHashMap.)]
        (let [i (skip-whitespace s i)]
          (when (or (>= i (.length s)) (not= (.charAt s i) \"))
            (fail! "object key must be a string" i))
          (let [[k i] (read-string-literal s i)
                i (skip-whitespace s i)
                i (expect-char s i \:)
                i (skip-whitespace s i)
                [v i] (read-value s i (inc depth))
                i (skip-whitespace s i)]
            (when (.containsKey result k)
              (fail! (str "duplicate object key " (pr-str k)) i))
            (.put result k v)
            (cond
              (and (< i (.length s)) (= (.charAt s i) \,))
              (recur (inc i) result)

              (and (< i (.length s)) (= (.charAt s i) \}))
              [(into {} result) (inc i)]

              :else
              (fail! "expected ',' or '}'" i))))))))

(defn- read-array
  [^String s i depth]
  (when (>= depth max-depth)
    (fail! "maximum nesting depth exceeded" i))
  (let [i (skip-whitespace s (inc i))]
    (if (and (< i (.length s)) (= (.charAt s i) \]))
      [[] (inc i)]
      (loop [i i
             result (transient [])]
        (let [[v i] (read-value s i (inc depth))
              i (skip-whitespace s i)]
          (cond
            (and (< i (.length s)) (= (.charAt s i) \,))
            (recur (inc i) (conj! result v))

            (and (< i (.length s)) (= (.charAt s i) \]))
            [(persistent! (conj! result v)) (inc i)]

            :else
            (fail! "expected ',' or ']'" i)))))))

(defn- literal
  [^String s i token value]
  (let [end (+ i (count token))]
    (when (or (> end (.length s))
              (not= token (subs s i end)))
      (fail! (str "expected " token) i))
  (when (and (< end (.length s))
             (let [c (.charAt s end)]
               (or (Character/isLetterOrDigit c) (= c \_))))
    (fail! (str "invalid literal after " token) end))
    [value end]))

(defn- read-value
  [^String s i depth]
  (let [i (skip-whitespace s i)]
    (when (>= i (.length s))
      (fail! "unexpected end of input" i))
    (let [c (.charAt s i)]
      (case c
        \" (read-string-literal s i)
        \{ (read-object s i depth)
        \[ (read-array s i depth)
        \t (literal s i "true" true)
        \f (literal s i "false" false)
        \n (literal s i "null" nil)
        (if (or (= c \-) (digit? c))
          (read-number s i)
          (fail! (str "unexpected character '" c "'") i))))))

(defn read-str
  "Parse exactly one JSON value.  Throws an expected `:MCP_JSON_PARSE` error."
  [^String text]
  (when-not (string? text)
    (error/fail! :MCP_JSON_PARSE "JSON input must be a string"
                 {:class (some-> text class .getName)}))
  (let [[value i] (read-value text 0 0)
        i (skip-whitespace text i)]
    (when (< i (.length text))
      (fail! "trailing data after JSON value" i))
    value))

(defn field
  "Read a JSON object member by keyword or string key."
  [m k]
  (or (get m (name k)) (get m k)))

(defn parse
  "Parse one JSON value, returning `{:ok? true :value v}` or a refusal."
  [text]
  (try
    {:ok? true :value (read-str text)}
    (catch clojure.lang.ExceptionInfo t
      (if (error/expected? t)
        {:ok? false :error (error/error-map t)}
        (throw t)))))

(defn- write-string
  [^StringBuilder sb ^String s]
  (.append sb \")
  (doseq [c s]
    (let [n (int c)]
      (cond
        (= c \") (.append sb "\\\"")
        (= c \\) (.append sb "\\\\")
        (= c \newline) (.append sb "\\n")
        (= c \return) (.append sb "\\r")
        (= c \tab) (.append sb "\\t")
        (= c \backspace) (.append sb "\\b")
        (= c \formfeed) (.append sb "\\f")
        (< n 0x20) (.append sb (format "\\u%04x" n))
        :else (.append sb c))))
  (.append sb \"))

(defn- key->string
  [k]
  (cond
    (string? k) k
    (keyword? k) (if (namespace k)
                   (str (namespace k) "/" (name k))
                   (name k))
    (symbol? k) (str k)
    (char? k) (str k)
    :else
    (error/fail! :MCP_JSON_WRITE
                 "JSON object keys must be strings, keywords or symbols"
                 {:key k :class (some-> k class .getName)})))

(declare write-value)

(defn- write-map
  [^StringBuilder sb m depth]
  (when (>= depth max-depth)
    (error/fail! :MCP_JSON_WRITE "maximum nesting depth exceeded" {}))
  (.append sb \{)
  (loop [entries (seq m) first? true]
    (when entries
      (let [[k v] (first entries)]
        (when-not first? (.append sb \,))
        (write-string sb (key->string k))
        (.append sb \:)
        (write-value sb v (inc depth))
        (recur (next entries) false))))
  (.append sb \}))

(defn- write-array
  [^StringBuilder sb xs depth]
  (when (>= depth max-depth)
    (error/fail! :MCP_JSON_WRITE "maximum nesting depth exceeded" {}))
  (.append sb \[)
  (loop [xs (seq xs) first? true]
    (when xs
      (when-not first? (.append sb \,))
      (write-value sb (first xs) (inc depth))
      (recur (next xs) false)))
  (.append sb \]))

(defn- write-value
  [^StringBuilder sb value depth]
  (cond
    (nil? value) (.append sb "null")
    (true? value) (.append sb "true")
    (false? value) (.append sb "false")
    (string? value) (write-string sb value)
    (integer? value) (.append sb (str value))
    (number? value)
    (let [d (double value)]
      (when (or (Double/isNaN d) (Double/isInfinite d))
        (error/fail! :MCP_JSON_WRITE "NaN and infinity are not JSON values"
                     {:value value}))
      (.append sb (str d)))
    (map? value) (write-map sb value depth)
    (or (vector? value) (sequential? value) (set? value))
    (write-array sb (if (set? value) (sort-by str value) value) depth)
    :else
    (error/fail! :MCP_JSON_WRITE
                 "value is not JSON-serializable"
                 {:value value :class (some-> value class .getName)})))

(defn write-str
  "Serialize a Clojure value as compact JSON."
  [value]
  (let [sb (StringBuilder.)]
    (write-value sb value 0)
    (str sb)))
