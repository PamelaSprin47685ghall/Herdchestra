(ns hc.core.ufss.printer
  "Deterministic EDN + UFSS pretty printer.

  The string ladder is the one in AGENTS.md §6.3:

    1. control characters -> standard escaped \"...\"
    2. no newline -> plain \"...\" when safe, otherwise #^n\"...\"#^n with the
       smallest n >= 1 for which \"#^n does not occur
    3. newline -> #^n\"\"\"...\"\"\"#^n block with common-indent stripping;
       n = 0 when safe, otherwise the smallest n >= 1 for which \"\"\"#^n
       does not occur

  Two edge cases are made explicit here because §6.3 leaves them implicit:

    * A single-line string that is exactly \" or starts with \"\" cannot use
      #\"...\"#: the opening quote plus the content would look like the
      multi-line opener \"\"\". Such strings use the block form.
    * DEL and C1 controls are escaped together with ASCII < 32 (except \\n and
      \\t). This keeps terminal output free of invisible control characters.

  Blocks use the current line indentation for every non-empty content line and
  for the closing fence line, so the reader's five-step indent stripping is
  lossless."
  (:require [clojure.string :as str])
  (:import (java.math BigDecimal BigInteger)
           (java.time Instant)
           (java.util Date UUID)))

;;; ---------------------------------------------------------------------------
;;; Character classes
;;; ---------------------------------------------------------------------------

(defn- control-char? [^long c]
  (or (and (< c 32) (not (== c 10)) (not (== c 9)))
      (<= 127 c 159)))

(defn- high-surrogate? [^long c] (<= 0xD800 c 0xDBFF))

(defn- low-surrogate? [^long c] (<= 0xDC00 c 0xDFFF))

(defn- paired-surrogate? [^String s ^long i]
  (and (high-surrogate? (int (.charAt s i)))
       (< (inc i) (.length s))
       (low-surrogate? (int (.charAt s (inc i))))))

;;; ---------------------------------------------------------------------------
;;; String rendering
;;; ---------------------------------------------------------------------------

(defn- needs-escape? [^String s]
  (let [n (.length s)]
    (loop [i 0]
      (if (>= i n)
        false
        (let [c (int (.charAt s i))]
          (cond
            (control-char? c) true
            (high-surrogate? c)
            (if (paired-surrogate? s i)
              (recur (+ i 2))
              true)
            (low-surrogate? c) true
            :else (recur (inc i))))))))

(defn- plain-safe? [^String s]
  (let [n (.length s)]
    (loop [i 0]
      (if (>= i n)
        true
        (let [c (int (.charAt s i))]
          (cond
            (or (== c 34) (== c 92) (== c 10) (== c 13) (== c 9)) false
            (control-char? c) false
            (high-surrogate? c)
            (if (paired-surrogate? s i)
              (recur (+ i 2))
              false)
            (low-surrogate? c) false
            :else (recur (inc i))))))))

(defn- max-quote-hash-run [^String s]
  (let [n (.length s)]
    (loop [i 0 max-run 0]
      (if (>= i n)
        max-run
        (if (== (int (.charAt s i)) 34)
          (let [j (loop [k (inc i)]
                          (if (and (< k n) (== (int (.charAt s k)) 35))
                            (recur (inc k))
                            k))
                run (- j (inc i))]
            (recur (inc i) (if (> run max-run) run max-run)))
          (recur (inc i) max-run))))))

(defn- max-triple-hash-run [^String s]
  (let [n (.length s)]
    (loop [i 0 max-run 0]
      (if (>= i n)
        max-run
        (if (and (<= (+ i 3) n)
                 (== (int (.charAt s i)) 34)
                 (== (int (.charAt s (+ i 1))) 34)
                 (== (int (.charAt s (+ i 2))) 34))
          (let [j (loop [k (+ i 3)]
                          (if (and (< k n) (== (int (.charAt s k)) 35))
                            (recur (inc k))
                            k))
                run (- j (+ i 3))]
            (recur (inc i) (if (> run max-run) run max-run)))
          (recur (inc i) max-run))))))

(defn- single-line-ambiguous? [^String s]
  (or (= s "\"")
      (str/starts-with? s "\"\"")))

(defn- render-escaped [^String s]
  (let [sb (StringBuilder.)
        n (.length s)]
    (.append sb \")
    (loop [i 0]
      (if (>= i n)
        (do (.append sb \") (.toString sb))
        (let [c (.charAt s i)
              code (int c)]
          (cond
            (== code 34) (do (.append sb "\\\"") (recur (inc i)))
            (== code 92) (do (.append sb "\\\\") (recur (inc i)))
            (== code 10) (do (.append sb "\\n") (recur (inc i)))
            (== code 13) (do (.append sb "\\r") (recur (inc i)))
            (== code 9) (do (.append sb "\\t") (recur (inc i)))
            (== code 8) (do (.append sb "\\b") (recur (inc i)))
            (== code 12) (do (.append sb "\\f") (recur (inc i)))
            (paired-surrogate? s i)
            (do (.append sb c)
                (.append sb (.charAt s (inc i)))
                (recur (+ i 2)))
            (or (control-char? code) (high-surrogate? code) (low-surrogate? code))
            (do (.append sb (format "\\u%04X" (int c))) (recur (inc i)))
            :else (do (.append sb c) (recur (inc i)))))))))

(defn- render-single-line-fence [^String s]
  (let [n (inc (max-quote-hash-run s))
        hashes (apply str (repeat n \#))]
    (str hashes "\"" s "\"" hashes)))

(defn- render-block [^String s ^String indent]
  (let [n (if (and (not (str/includes? s "\"\"\""))
                   (not (str/includes? s "\\")))
            0
            (inc (max-triple-hash-run s)))
        hashes (apply str (repeat n \#))
        lines (str/split s #"\n" -1)
        sb (StringBuilder.)]
    (.append sb hashes)
    (.append sb "\"\"\"\n")
    (loop [ls (seq lines) first? true]
      (if-let [line (first ls)]
        (do
          (when-not first? (.append sb \newline))
          (when-not (empty? line)
            (.append sb indent)
            (.append sb line))
          (recur (next ls) false))
        (do
          (.append sb \newline)
          (.append sb indent)
          (.append sb "\"\"\"")
          (.append sb hashes)
          (.toString sb))))))

(defn- render-string [^String s ^String indent]
  (cond
    (needs-escape? s) (render-escaped s)

    (not (str/includes? s "\n"))
    (cond
      (plain-safe? s) (str \" s \")
      (single-line-ambiguous? s) (render-block s indent)
      :else (render-single-line-fence s))

    :else (render-block s indent)))

;;; ---------------------------------------------------------------------------
;;; Scalar rendering
;;; ---------------------------------------------------------------------------

(defn- token-safe? [^String t]
  (and (not (empty? t))
       (not (re-find #"[\s,;()\[\]{}\"\\]" t))))

(defn- number-start? [^String t]
  (let [n (.length t)
        c0 (int (.charAt t 0))]
    (or (<= 48 c0 57)
        (and (or (== c0 43) (== c0 45))
             (> n 1)
             (<= 48 (int (.charAt t 1)) 57)))))

(defn- render-keyword [^clojure.lang.Keyword k]
  (let [ns (namespace k)
        nm (name k)
        t (if ns (str ns "/" nm) nm)]
    (when-not (and (token-safe? t)
                   (not (str/starts-with? t ":"))
                   (or ns (not (str/includes? nm "/")))
                   (= k (keyword t)))
      (throw (ex-info (str "UFSS cannot print keyword " (pr-str k)
                           " without changing its identity")
                      {:type :ufss/write-error :value k})))
    (str ":" t)))

(defn- render-symbol [^clojure.lang.Symbol sym]
  (let [ns (namespace sym)
        nm (name sym)
        t (if ns (str ns "/" nm) nm)]
    (when-not (and (token-safe? t)
                   (or ns (not (str/includes? nm "/")))
                   (not (str/starts-with? t "#"))
                   (not (str/starts-with? t ":"))
                   (not (str/starts-with? t "'"))
                   (not (str/starts-with? t "@"))
                   (not (str/starts-with? t "^"))
                   (not (str/starts-with? t "`"))
                   (not (str/starts-with? t "~"))
                   (not (#{"nil" "true" "false"} t))
                   (not (number-start? t))
                   (= sym (symbol t)))
      (throw (ex-info (str "UFSS cannot print symbol " (pr-str sym)
                           " without changing its identity")
                      {:type :ufss/write-error :value sym})))
    t))

(defn- render-float [v]
  (let [d (double v)]
    (cond
      (Double/isNaN d) "##NaN"
      (== d Double/POSITIVE_INFINITY) "##Inf"
      (== d Double/NEGATIVE_INFINITY) "##-Inf"
      :else (str v))))

(defn- render-char [c]
  (let [i (int c)]
    (cond
      (== i 10) "\\newline"
      (== i 32) "\\space"
      (== i 9) "\\tab"
      (== i 13) "\\return"
      (== i 8) "\\backspace"
      (== i 12) "\\formfeed"
      (or (< i 32) (<= 127 i 159) (<= 0xD800 i 0xDFFF))
      (format "\\u%04X" i)
      :else (str "\\" c))))

(defn- render-instant [^Instant v ^String indent]
  ;; #inst reads back through java.util.Date, whose range is narrower than
  ;; java.time.Instant's. Refuse to emit text that the reader cannot consume.
  (try
    (Date/from v)
    (str "#inst " (render-string (str v) indent))
    (catch IllegalArgumentException _
      (throw (ex-info "UFSS cannot print an Instant outside java.util.Date's range"
                      {:type :ufss/write-error :value v})))))

;;; ---------------------------------------------------------------------------
;;; Collection rendering
;;; ---------------------------------------------------------------------------

(declare render)

(defn- render-map [m ^String indent]
  (if (empty? m)
    "{}"
    (let [child (str indent "  ")
          pairs (mapv (fn [[k v]] (str (render k child) " " (render v child))) m)
          multiline? (boolean (some #(str/includes? % "\n") pairs))]
      (if multiline?
        (str "{" (str/join (str "\n" child) pairs) "\n" indent "}")
        (str "{" (str/join " " pairs) "}")))))

(defn- render-seq-like [v ^String indent ^String open ^String close]
  (if (empty? v)
    (str open close)
    (let [child (str indent "  ")
          items (mapv #(render % child) v)
          multiline? (boolean (some #(str/includes? % "\n") items))]
      (if multiline?
        (str open (str/join (str "\n" child) items) "\n" indent close)
        (str open (str/join " " items) close)))))

(defn- render-tagged [t ^String indent]
  (let [tag (:tag t)
        tag-name (str tag)]
    (when (#{"uuid" "inst"} tag-name)
      (throw (ex-info (str "UFSS reserves #" tag-name
                           " for UUID/Date values; print the typed value instead")
                      {:type :ufss/write-error :value t})))
    (str "#" (render-symbol tag) " " (render (:form t) indent))))

(defn- render [v ^String indent]
  (cond
    (nil? v) "nil"
    (true? v) "true"
    (false? v) "false"
    (string? v) (render-string v indent)
    (char? v) (render-char v)
    (keyword? v) (render-keyword v)
    (symbol? v) (render-symbol v)
    (instance? clojure.lang.BigInt v) (str v "N")
    (instance? BigInteger v) (str v "N")
    (integer? v) (str v)
    (ratio? v) (str v)
    (instance? BigDecimal v) (str v "M")
    (instance? Float v)
    (throw (ex-info "UFSS cannot print Float without changing its numeric identity; use Double"
                    {:type :ufss/write-error :value v}))
    (instance? Double v) (render-float v)
    (instance? UUID v) (str "#uuid " (render-string (str v) indent))
    (instance? Date v)
    (str "#inst " (render-string (str (Instant/ofEpochMilli (.getTime ^Date v))) indent))
    (instance? Instant v) (render-instant v indent)
    (tagged-literal? v) (render-tagged v indent)
    (map? v) (render-map v indent)
    (vector? v) (render-seq-like v indent "[" "]")
    (set? v) (render-seq-like v indent "#{" "}")
    (list? v) (render-seq-like v indent "(" ")")
    (seq? v) (render-seq-like (apply list v) indent "(" ")")
    :else (throw (ex-info (str "UFSS cannot print value of type " (class v))
                          {:type :ufss/write-error :value v}))))

;;; ---------------------------------------------------------------------------
;;; Public printer API
;;; ---------------------------------------------------------------------------

(defn write-str
  "Render v as EDN+UFSS text. A string is rendered as a string literal, so
  (= s (read-str (write-str s))) holds for every Unicode string s."
  [v]
  (render v ""))
