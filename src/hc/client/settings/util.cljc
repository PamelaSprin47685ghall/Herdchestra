(ns hc.client.settings.util
  "Small pure helpers for the settings workspace.

  The helpers are platform neutral.  They do not read a clock, a random
  source, storage, or the network unless a caller passes the value in."
  (:require [clojure.string :as str]))

(defn present?
  "True for a non-nil, non-blank string."
  [value]
  (and (string? value) (not (str/blank? value))))

(defn keyword-name
  "Return the name of a keyword or string without a leading colon."
  [value]
  (cond
    (keyword? value) (if-let [ns (namespace value)]
                       (str ns "/" (name value))
                       (name value))
    (string? value) (if (str/starts-with? value ":") (subs value 1) value)
    :else (str value)))

(defn normalize-keyword
  "Return VALUE as a lower-case keyword, or nil."
  [value]
  (when (some? value)
    (let [s (str/lower-case (keyword-name value))]
      (when-not (str/blank? s)
        (keyword s)))))

(defn canonical
  "Return a deterministic EDN string for VALUE.

  Maps are ordered by the printed form of their keys.  Sets are ordered the
  same way.  Vectors keep their order.  The result is stable under
  Clojure and ClojureScript so a config digest is comparable across the
  boundary."
  [value]
  (cond
    (map? value)
    (str "{"
         (str/join ","
                   (map (fn [[k v]]
                          (str (canonical k) " " (canonical v)))
                        (sort-by (fn [[k _]] (pr-str k)) value)))
         "}")

    (set? value)
    (str "#{"
         (str/join "," (map canonical (sort-by pr-str value)))
         "}")

    (vector? value)
    (str "[" (str/join "," (map canonical value)) "]")

    (sequential? value)
    (str "(" (str/join "," (map canonical value)) ")")

    :else (pr-str value)))

(def ^:private max-hash 2147483647)

(defn- char-code
  [s i]
  #?(:clj (int (nth s i))
     :cljs (.charCodeAt s i)))

(defn- stable-hash
  "Return a stable 31-bit polynomial hash of S."
  [s]
  (loop [i 0
         h 0]
    (if (< i (count s))
      (let [c (char-code s i)]
        (recur (inc i) (mod (+ (* h 31) c) max-hash)))
      h)))

(defn- hex
  [n]
  (let [digits "0123456789abcdef"]
    (loop [n n
           out ""]
      (if (zero? n)
        (if (str/blank? out) "0" out)
        (recur (quot n 16)
               (str (nth digits (mod n 16)) out))))))

(defn- pad-hex
  [s width]
  (str (apply str (repeat (max 0 (- width (count s))) "0")) s))

(defn digest
  "Return a short deterministic digest of VALUE."
  [value]
  (str "v1-" (pad-hex (hex (stable-hash (canonical value))) 8)))

(defn short-digest
  "Return the display form of DIGEST."
  [digest]
  (if (and (string? digest) (> (count digest) 3))
    (subs digest 3)
    digest))

(defn format-integer
  "Return an integer string with comma grouping, or the original value."
  [value]
  (if (integer? value)
    (let [negative? (neg? value)
          digits (str (if negative? (- value) value))
          grouped (->> (range (count digits))
                       reverse
                       (partition-all 3)
                       (map (fn [chunk]
                              (apply str (map #(nth digits %) (reverse chunk)))))
                       reverse
                       (str/join ","))]
      (str (when negative? "-") grouped))
    (str value)))

(defn parse-int
  "Parse VALUE as an integer, or return FALLBACK."
  [value fallback]
  (cond
    (integer? value) value
    (number? value) (long value)
    (string? value)
    #?(:clj (try (Long/parseLong (str/trim value))
                 (catch NumberFormatException _ fallback))
       :cljs (let [n (js/parseInt value 10)]
               (if (js/isNaN n) fallback n)))
    :else fallback))

(defn parse-number
  "Parse VALUE as a finite number, or return nil."
  [value]
  (cond
    (number? value)
    #?(:clj (when (and (not (Double/isNaN (double value)))
                       (not (Double/isInfinite (double value))))
              value)
       :cljs (when (and (not (js/isNaN value)) (js/isFinite value))
               value))
    (string? value)
    (let [s (str/trim value)]
      (when-not (str/blank? s)
        #?(:clj (try
                  (let [n (Double/parseDouble s)]
                    (when (and (not (Double/isNaN n))
                               (not (Double/isInfinite n)))
                      n))
                  (catch NumberFormatException _ nil))
           :cljs (let [n (js/parseFloat s)]
                   (when (and (not (js/isNaN n))
                              (js/isFinite n))
                     n)))))
    :else nil))

(defn error
  "Build one refusal value.  A refusal never changes the domain state."
  [code message details]
  (cond-> {:ok? false
           :error {:code code
                   :message message}}
    (some? details) (assoc-in [:error :details] details)))

(defn error-message
  "Return a human-readable message for a thrown value."
  [error]
  #?(:clj (or (ex-message error) (str error))
     :cljs (or (some-> error .-message) (str error))))

(defn ok
  "Build one successful value."
  ([value] {:ok? true :value value})
  ([value extra] (merge {:ok? true :value value} extra)))
