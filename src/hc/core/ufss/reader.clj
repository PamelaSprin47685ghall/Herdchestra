(ns hc.core.ufss.reader
  "EDN + UFSS reader.

  Supports standard EDN values, standard Clojure escaped strings, and the
  Universal Fence-Scaled String forms from AGENTS.md §6.3:

    #^n\"...\"#^n      single-line raw fence, n >= 1
    #^n\"\"\"...\"\"\"#^n  multi-line raw block, n >= 0, with indent stripping

  `read-str` is strict EDN+UFSS. `read-input` is the liberal parser for model
  submissions: it also accepts JSON conveniences (null, \"key\": value, \\/).

  All expected failures throw ExceptionInfo with :type :ufss/read-error and
  {:error :UFSS_* :offset :line :column :expected :found}."
  (:require [clojure.string :as str])
  (:import (java.math BigDecimal BigInteger)
           (java.time Instant)
           (java.util Date UUID)))

;;; ---------------------------------------------------------------------------
;;; Cursor and diagnostics
;;; ---------------------------------------------------------------------------

(def ^:private eof-sentinel ::eof)
(def ^:private discard-sentinel ::discard)

(defn- cursor ^longs [] (long-array 1))

(defn- pos ^long [^longs cur] (aget cur 0))

(defn- set-pos! [^longs cur ^long p] (aset cur 0 p))

(defn- bump! [^longs cur] (aset cur 0 (inc (aget cur 0))))

(defn- char-at ^long [^String s ^long i]
  (if (< i (.length s)) (int (.charAt s i)) -1))

(defn- line-column
  "Return [line column] for a UTF-16 offset. Columns count code points."
  [^String s ^long offset]
  (let [limit (min offset (.length s))]
    (loop [i 0 line 1 col 1]
      (if (>= i limit)
        [line col]
        (let [c (int (.charAt s i))]
          (cond
            (== c 10) (recur (inc i) (inc line) 1)
            (and (<= 0xD800 c 0xDBFF)
                 (< (inc i) (.length s))
                 (<= 0xDC00 (int (.charAt s (inc i))) 0xDFFF))
            (recur (+ i 2) line (inc col))
            :else (recur (inc i) line (inc col))))))))

(defn- preview [^String s ^long offset]
  (if (>= offset (.length s))
    ""
    (subs s offset (min (.length s) (+ offset 24)))))

(defn- fail!
  ([s offset code expected]
   (fail! s offset code expected nil))
  ([s offset code expected extra]
   (let [[line column] (line-column s offset)
         found (preview s offset)
         data (merge {:type :ufss/read-error
                      :error code
                      :offset offset
                      :line line
                      :column column
                      :expected expected
                      :found found}
                     extra)]
     (throw (ex-info (str "UFSS read error at line " line ", column " column
                          " [" (name code) "]: expected " expected
                          (if (empty? found)
                            ", found end of input"
                            (str ", found " (pr-str found))))
                     data)))))

;;; ---------------------------------------------------------------------------
;;; Character classes
;;; ---------------------------------------------------------------------------

(defn- ascii-digit? [^long c] (<= 48 c 57))

(defn- hex-digit? [^long c]
  (or (<= 48 c 57) (<= 65 c 70) (<= 97 c 102)))

(defn- octal-digit? [^long c] (<= 48 c 55))

(defn- token-delimiter? [^long c]
  (or (== c 32) (== c 9) (== c 10) (== c 13) (== c 44) ; whitespace, comma
      (== c 40) (== c 41) (== c 91) (== c 93) (== c 123) (== c 125)
      (== c 34) (== c 59) (== c 92))) ; " ; \

(defn- skip-line
  "Return the offset of the next newline at or after i, or the string length."
  [^String s ^long i]
  (let [n (.length s)]
    (loop [j i]
      (if (or (>= j n) (== (int (.charAt s j)) 10)) j (recur (inc j))))))

(defn- skip-ws! [^String s ^longs cur]
  (let [n (.length s)]
    (loop []
      (let [i (pos cur)]
        (when (< i n)
          (let [c (int (.charAt s i))]
            (cond
              (or (== c 32) (== c 9) (== c 10) (== c 13) (== c 44))
              (do (set-pos! cur (inc i)) (recur))

              (== c 59) ; ;
              (do (set-pos! cur (skip-line s (inc i))) (recur))

              (and (== c 35) (< (inc i) n)
                   (== (int (.charAt s (inc i))) 33)) ; #!
              (do (set-pos! cur (skip-line s (inc i))) (recur))

              :else nil)))))))

;;; ---------------------------------------------------------------------------
;;; Tokens, numbers, keywords, characters
;;; ---------------------------------------------------------------------------

(defn- read-token ^String [^String s ^longs cur]
  (let [n (.length s)
        start (pos cur)]
    (loop [i start]
      (if (or (>= i n) (token-delimiter? (int (.charAt s i))))
        (do (set-pos! cur i) (subs s start i))
        (recur (inc i))))))

(defn- number-start? [^String token]
  (let [n (.length token)
        c0 (int (.charAt token 0))]
    (or (ascii-digit? c0)
        (and (or (== c0 43) (== c0 45))
             (> n 1)
             (ascii-digit? (int (.charAt token 1)))))))

(defn- parse-number [^String s ^long offset ^String token]
  (let [n (.length token)
        last-ch (.charAt token (dec n))
        suffix (case last-ch \N :bigint \M :bigdec nil)
        body (if suffix (subs token 0 (dec n)) token)
        bad! (fn [expected] (fail! s offset :UFSS_INVALID_NUMBER expected))]
    (when (empty? body)
      (bad! "a number"))
    (cond
      ;; Ratio: integer/integer.  N/M suffixes are for integers and
      ;; decimals; silently dropping them here would turn "1/2M" into 1/2.
      (str/includes? body "/")
      (let [parts (str/split body #"/" -1)]
        (when suffix
          (bad! "a ratio without an N or M suffix"))
        (when (or (not= 2 (count parts)) (some empty? parts))
          (bad! "a ratio like 1/2"))
        (let [^String ns (nth parts 0)
              ^String ds (nth parts 1)]
          (when (or (str/includes? ns ".") (str/includes? ns "e") (str/includes? ns "E")
                    (str/includes? ds ".") (str/includes? ds "e") (str/includes? ds "E"))
            (bad! "an integer numerator and denominator"))
          (try
            (let [nv (try (Long/parseLong ns) (catch NumberFormatException _ nil))
                  dv (try (Long/parseLong ds) (catch NumberFormatException _ nil))]
              (if (and nv dv)
                (do (when (zero? dv) (bad! "a non-zero denominator"))
                    (/ nv dv))
                (let [nv (BigInteger. ns)
                      dv (BigInteger. ds)]
                  (when (zero? (.signum dv)) (bad! "a non-zero denominator"))
                  (/ (bigint nv) (bigint dv)))))
            (catch NumberFormatException _
              (bad! "a ratio like 1/2"))
            (catch ArithmeticException _
              (bad! "a non-zero denominator")))))

      ;; Decimal / exponent
      (or (str/includes? body ".") (str/includes? body "e") (str/includes? body "E"))
      (cond
        (= suffix :bigint) (bad! "an integer, not a decimal")
        (= suffix :bigdec) (try (BigDecimal. body)
                                (catch NumberFormatException _ (bad! "a decimal number")))
        :else (try (Double/parseDouble body)
                   (catch NumberFormatException _ (bad! "a number"))))

      ;; Integer
      :else
      (cond
        (= suffix :bigdec) (try (BigDecimal. body)
                                (catch NumberFormatException _ (bad! "a decimal number")))
        (= suffix :bigint) (try (bigint (BigInteger. body))
                                (catch NumberFormatException _ (bad! "an integer")))
        :else (try (Long/parseLong body)
                   (catch NumberFormatException _
                     (try (bigint (BigInteger. body))
                          (catch NumberFormatException _ (bad! "an integer")))))))))

(defn- read-token-form [^String s ^longs cur opts]
  (let [start (pos cur)
        token (read-token s cur)]
    (when (empty? token)
      (fail! s start :UFSS_INVALID_TOKEN "a token"))
    (cond
      (= token "nil") nil
      (= token "true") true
      (= token "false") false
      (and (:json opts) (= token "null")) nil
      (number-start? token) (parse-number s start token)
      :else (symbol token))))

(defn- tag-token-valid?
  "An EDN/Clojure tag is a symbol, not a literal, keyword, or reader macro.
  `read-token` already excludes whitespace and delimiters; this rejects the
  remaining tokens that would make `TaggedLiteral` unprintable."
  [^String tag]
  (and (not (empty? tag))
       (not (str/starts-with? tag ":"))
       (not (str/starts-with? tag "'"))
       (not (str/starts-with? tag "@"))
       (not (str/starts-with? tag "^"))
       (not (str/starts-with? tag "`"))
       (not (str/starts-with? tag "~"))
       (not (str/starts-with? tag "#"))
       (not (#{"nil" "true" "false"} tag))
       (not (number-start? tag))))

(defn- read-keyword [^String s ^longs cur _opts]
  (let [start (pos cur)]
    (bump! cur) ; :
    (let [token (read-token s cur)]
      (when (empty? token)
        (fail! s start :UFSS_INVALID_KEYWORD "a keyword name after :"))
      (when (str/starts-with? token ":")
        (fail! s start :UFSS_INVALID_KEYWORD "a single-colon keyword"))
      (keyword token))))

(defn- read-char-literal [^String s ^longs cur]
  (let [start (pos cur)
        n (.length s)]
    (bump! cur) ; backslash
    (let [i (pos cur)]
      (when (>= i n)
        (fail! s start :UFSS_INVALID_CHAR "a character after \\"))
      (let [c (.charAt s i)]
        (if (Character/isLetter c)
          (let [token (read-token s cur)]
            (case token
              "newline" \newline
              "space" \space
              "tab" \tab
              "return" \return
              "backspace" \backspace
              "formfeed" \formfeed
              (cond
                (and (== (int (.charAt token 0)) 117) ; u
                     (= 5 (.length token))
                     (every? #(hex-digit? (int %)) (subs token 1)))
                (char (Integer/parseInt (subs token 1) 16))

                (and (== (int (.charAt token 0)) 111) ; o
                     (<= 2 (.length token) 4)
                     (every? #(octal-digit? (int %)) (subs token 1)))
                (let [v (Integer/parseInt (subs token 1) 8)]
                  (if (<= v 255)
                    (char v)
                    (fail! s start :UFSS_INVALID_CHAR
                           "an octal escape in the range \\0 to \\377")))

                (= 1 (.length token)) (.charAt token 0)

                :else (fail! s start :UFSS_INVALID_CHAR
                             "a named character, \\uXXXX, or \\oNNN"))))
          (do
            (bump! cur)
            (let [j (pos cur)]
              (when (and (< j n) (not (token-delimiter? (int (.charAt s j)))))
                (fail! s start :UFSS_INVALID_CHAR
                       "a delimiter after a single-character literal")))
            c))))))

;;; ---------------------------------------------------------------------------
;;; Strings and fences
;;; ---------------------------------------------------------------------------

(defn- read-standard-string [^String s ^longs cur opts]
  ;; The opening quote has been consumed.
  (let [n (.length s)
        sb (StringBuilder.)]
    (loop []
      (let [i (pos cur)]
        (if (>= i n)
          (fail! s i :UFSS_UNTERMINATED_STRING "a closing double quote")
          (let [ch (.charAt s i)
                c (int ch)]
            (cond
              (== c 34)
              (do (bump! cur) (.toString sb))

              (== c 92) ; backslash
              (let [j (inc i)]
                (if (>= j n)
                  (fail! s j :UFSS_UNTERMINATED_STRING "an escape sequence")
                  (let [e (.charAt s j)]
                    (case e
                      \t (do (.append sb \tab) (set-pos! cur (+ j 1)) (recur))
                      \r (do (.append sb \return) (set-pos! cur (+ j 1)) (recur))
                      \n (do (.append sb \newline) (set-pos! cur (+ j 1)) (recur))
                      \\ (do (.append sb \\) (set-pos! cur (+ j 1)) (recur))
                      \" (do (.append sb \") (set-pos! cur (+ j 1)) (recur))
                      \b (do (.append sb \backspace) (set-pos! cur (+ j 1)) (recur))
                      \f (do (.append sb \formfeed) (set-pos! cur (+ j 1)) (recur))
                      \/ (if (:json opts)
                           (do (.append sb \/) (set-pos! cur (+ j 1)) (recur))
                           (fail! s j :UFSS_INVALID_ESCAPE
                                  "one of \\t \\r \\n \\\\ \\\" \\b \\f \\uXXXX"))
                      \u (let [k (+ j 1)
                               end (+ k 4)]
                           (if (> end n)
                             (fail! s j :UFSS_INVALID_ESCAPE
                                    "four hexadecimal digits after \\u")
                             (let [hex (subs s k end)
                                   v (try (Integer/parseInt hex 16)
                                          (catch NumberFormatException _ -1))]
                               (if (neg? v)
                                 (fail! s j :UFSS_INVALID_ESCAPE
                                        "four hexadecimal digits after \\u")
                                 (do (.append sb (char v))
                                     (set-pos! cur end)
                                     (recur))))))
                      (if (octal-digit? (int e))
                        (let [end (loop [p j]
                                    (if (and (< p n)
                                             (< (- p j) 3)
                                             (octal-digit? (int (.charAt s p))))
                                      (recur (inc p))
                                      p))
                              v (Integer/parseInt (subs s j end) 8)]
                          (if (<= v 255)
                            (do (.append sb (char v))
                                (set-pos! cur end)
                                (recur))
                            (fail! s j :UFSS_INVALID_ESCAPE
                                   "an octal escape in the range \\0 to \\377")))
                        (fail! s j :UFSS_INVALID_ESCAPE
                               "one of \\t \\r \\n \\\\ \\\" \\b \\f \\uXXXX, or octal \\NNN"))))))

              :else
              (do (.append sb ch) (bump! cur) (recur)))))))))

(defn- fence-at? [^String s ^long i ^long n]
  (and (== (int (.charAt s i)) 34)
       (<= (+ i 1 n) (.length s))
       (loop [k 0]
         (if (== k n)
           true
           (if (== (int (.charAt s (+ i 1 k))) 35)
             (recur (inc k))
             false)))))

(defn- read-single-line-fence [^String s ^longs cur ^long n]
  ;; The opening quote has been consumed; n >= 1.
  (let [start (pos cur)
        len (.length s)]
    (loop [i start]
      (if (>= i len)
        (fail! s start :UFSS_UNTERMINATED_FENCE
               (str "a closing " (pr-str (str \" (apply str (repeat n \#))))))
        (if (fence-at? s i n)
          (let [content (subs s start i)]
            (set-pos! cur (+ i 1 n))
            content)
          (recur (inc i)))))))

(defn- split-lines
  "Split body on \\n, preserving empty lines. Returns [[relative-offset line] ...]."
  [^String body]
  (let [n (.length body)]
    (loop [start 0 acc (transient [])]
      (let [idx (.indexOf body "\n" (int start))]
        (if (neg? idx)
          (persistent! (conj! acc [start (subs body start)]))
          (recur (inc idx) (conj! acc [start (subs body start idx)])))))))

(defn- read-multiline-fence [^String s ^longs cur ^long n]
  ;; The opening \"\"\" has been consumed; n >= 0.
  (let [len (.length s)
        after-open (pos cur)]
    (when (or (>= after-open len)
              (not (== (int (.charAt s after-open)) 10)))
      (fail! s after-open :UFSS_MULTILINE_NEEDS_NEWLINE
             "a newline immediately after the opening fence"))
    (let [body-start (inc after-open)]
      (loop [line-start body-start]
        (if (>= line-start len)
          (fail! s body-start :UFSS_UNTERMINATED_FENCE
                 (str "a closing line " (pr-str (str "\"\"\"" (apply str (repeat n \#))))))
          (let [spaces-end (loop [i line-start]
                             (if (and (< i len) (== (int (.charAt s i)) 32))
                               (recur (inc i))
                               i))
                indent-len (- spaces-end line-start)
                close-end (+ spaces-end 3 n)]
            (if (and (<= close-end len)
                     (== (int (.charAt s spaces-end)) 34)
                     (== (int (.charAt s (+ spaces-end 1))) 34)
                     (== (int (.charAt s (+ spaces-end 2))) 34)
                     (loop [k 0]
                       (if (== k n)
                         true
                         (if (== (int (.charAt s (+ spaces-end 3 k))) 35)
                           (recur (inc k))
                           false)))
                     (or (pos? n)
                         (>= close-end len)
                         (not (== (int (.charAt s close-end)) 34))))
              (let [indent (subs s line-start spaces-end)
                    body-end (if (== line-start body-start) body-start (dec line-start))
                    body (subs s body-start body-end)
                    sb (StringBuilder.)]
                (loop [pairs (seq (split-lines body)) first? true]
                  (if-let [[rel line] (first pairs)]
                    (do
                      (when-not first? (.append sb \newline))
                      (when-not (empty? line)
                        (if (str/starts-with? line indent)
                          (.append sb (subs line indent-len))
                          (fail! s (+ body-start rel) :UFSS_INDENT_VIOLATION
                                 (str "a non-empty line indented by at least "
                                      indent-len " spaces, or an empty line"))))
                      (recur (next pairs) false))
                    (do
                      (set-pos! cur close-end)
                      (.toString sb)))))
              (let [nl (.indexOf s "\n" (int line-start))]
                (if (neg? nl)
                  (fail! s body-start :UFSS_UNTERMINATED_FENCE
                         (str "a closing line " (pr-str (str "\"\"\"" (apply str (repeat n \#))))))
                  (recur (inc nl)))))))))))

(defn- read-fence-after-quote [^String s ^longs cur ^long n]
  ;; The first opening quote has been consumed; n >= 1.
  (let [i (pos cur)
        len (.length s)]
    (if (and (< (inc i) len)
             (== (int (.charAt s i)) 34)
             (== (int (.charAt s (inc i))) 34))
      (do (set-pos! cur (+ i 2))
          (read-multiline-fence s cur n))
      (read-single-line-fence s cur n))))

;;; ---------------------------------------------------------------------------
;;; Collections and dispatch
;;; ---------------------------------------------------------------------------

(declare read-form)

(defn- read-coll [s cur opts close-ch kind]
  (let [^String s s
        ^longs cur cur
        ^long close-ch close-ch]
    (loop [acc (transient [])]
      (skip-ws! s cur)
      (let [i (pos cur)]
        (cond
          (>= i (.length s))
          (fail! s i :UFSS_UNEXPECTED_EOF (str "a value or " (char close-ch)))

          (== (int (.charAt s i)) close-ch)
          (do (bump! cur)
              (let [v (persistent! acc)]
                (case kind
                  :vector v
                  :list (apply list v))))

          :else
          (let [v (read-form s cur opts)]
            (if (= v eof-sentinel)
              (fail! s (pos cur) :UFSS_UNEXPECTED_EOF (str "a value or " (char close-ch)))
              (recur (conj! acc v)))))))))

(defn- json-map-separator?
  "True when the colon at `colon` is a JSON key/value separator rather than
  the start of an EDN keyword value. `key-end` is the offset just after the
  string key. An adjacent colon is always JSON. A spaced colon is JSON when
  what follows cannot begin an EDN keyword token."
  [^String s ^long key-end ^long colon]
  (let [c2 (char-at s (inc colon))]
    (or (== key-end colon)
        (== c2 -1)
        (or (== c2 32) (== c2 9) (== c2 10) (== c2 13) (== c2 44)
            (== c2 123) (== c2 91) (== c2 34) (== c2 125) (== c2 93)))))

(defn- read-map [^String s ^longs cur opts]
  (loop [acc (transient {})]
    (skip-ws! s cur)
    (let [i (pos cur)]
      (cond
        (>= i (.length s))
        (fail! s i :UFSS_UNEXPECTED_EOF "a map key or }")

        (== (int (.charAt s i)) 125)
        (do (bump! cur) (persistent! acc))

        :else
        (let [k (read-form s cur opts)]
          (if (= k eof-sentinel)
            (fail! s (pos cur) :UFSS_UNEXPECTED_EOF "a map key")
            (do
              (when (and (:json opts) (string? k))
                ;; JSON convenience: "key": value, including a spaced colon
                ;; when the following character cannot start an EDN keyword.
                (let [key-end (pos cur)]
                  (skip-ws! s cur)
                  (let [colon (pos cur)]
                    (when (and (< colon (.length s))
                               (== (int (.charAt s colon)) 58)
                               (json-map-separator? s key-end colon))
                      (set-pos! cur (inc colon))))))
              (skip-ws! s cur)
              (let [j (pos cur)]
                (if (or (>= j (.length s)) (== (int (.charAt s j)) 125))
                  (fail! s j :UFSS_ODD_MAP "a value after the map key")
                  (let [v (read-form s cur opts)]
                    (if (= v eof-sentinel)
                      (fail! s (pos cur) :UFSS_UNEXPECTED_EOF "a map value")
                      (if (contains? acc k)
                        (fail! s i :UFSS_DUPLICATE_KEY "distinct map keys")
                        (recur (assoc! acc k v))))))))))))))

(defn- read-set [^String s ^longs cur opts]
  (loop [acc (transient #{})]
    (skip-ws! s cur)
    (let [i (pos cur)]
      (cond
        (>= i (.length s))
        (fail! s i :UFSS_UNEXPECTED_EOF "a set element or }")

        (== (int (.charAt s i)) 125)
        (do (bump! cur) (persistent! acc))

        :else
        (let [v (read-form s cur opts)]
          (if (= v eof-sentinel)
            (fail! s (pos cur) :UFSS_UNEXPECTED_EOF "a set element or }")
            (if (contains? acc v)
              (fail! s i :UFSS_DUPLICATE_SET_ELEMENT "distinct set elements")
              (recur (conj! acc v)))))))))

(defn- read-tag [s cur opts tag-sym tag-offset]
  (let [^String s s
        ^longs cur cur]
    (skip-ws! s cur)
    (let [i (pos cur)]
      (when (>= i (.length s))
        (fail! s i :UFSS_UNEXPECTED_EOF (str "a value after #" tag-sym)))
      (let [v (read-form s cur opts)]
        (when (= v eof-sentinel)
          (fail! s (pos cur) :UFSS_UNEXPECTED_EOF (str "a value after #" tag-sym)))
        (case (str tag-sym)
          "uuid"
          (if (string? v)
            (try (UUID/fromString v)
                 (catch IllegalArgumentException _
                   (fail! s tag-offset :UFSS_INVALID_TAGGED_VALUE "a UUID string")))
            (fail! s tag-offset :UFSS_INVALID_TAGGED_VALUE "a UUID string"))

          "inst"
          (if (string? v)
            (try (Date/from (Instant/parse v))
                 (catch Exception _
                   (fail! s tag-offset :UFSS_INVALID_TAGGED_VALUE
                          "an ISO-8601 instant string")))
            (fail! s tag-offset :UFSS_INVALID_TAGGED_VALUE "an ISO-8601 instant string"))

          (tagged-literal tag-sym v))))))

(defn- read-dispatch [^String s ^longs cur opts]
  (let [len (.length s)
        start (pos cur)]
    (bump! cur) ; #
    (let [c (char-at s (pos cur))]
      (cond
        (== c -1)
        (fail! s (pos cur) :UFSS_UNEXPECTED_EOF "a dispatch form after #")

        (== c 35) ; #: a fence or ##Inf-style special float
        (let [run-end (loop [i (pos cur)]
                        (if (and (< i len) (== (int (.charAt s i)) 35))
                          (recur (inc i))
                          i))
              n (- run-end start)
              c2 (char-at s run-end)]
          (cond
            (== c2 34)
            (do (set-pos! cur (inc run-end))
                (read-fence-after-quote s cur n))

            (and (== n 2) (or (== c2 73) (== c2 45) (== c2 78))) ; I, -, N
            (let [_ (set-pos! cur run-end)
                  token (read-token s cur)]
              (case token
                "Inf" Double/POSITIVE_INFINITY
                "-Inf" Double/NEGATIVE_INFINITY
                "NaN" Double/NaN
                (fail! s run-end :UFSS_INVALID_TOKEN "##Inf, ##-Inf, or ##NaN")))

            :else
            (fail! s run-end :UFSS_INVALID_FENCE
                   "a double quote after the fence hashes")))

        (== c 34) ; #"
        (do (bump! cur) (read-fence-after-quote s cur 1))

        (== c 95) ; #_
        (do (bump! cur)
            (let [v (read-form s cur opts)]
              (when (= v eof-sentinel)
                (fail! s (pos cur) :UFSS_UNEXPECTED_EOF "a form after #_"))
              discard-sentinel))

        (== c 123) ; #{
        (do (bump! cur) (read-set s cur opts))

        (== c 58) ; #:
        (fail! s (pos cur) :UFSS_UNSUPPORTED_SYNTAX
               "an EDN value (namespaced maps are not supported)")

        (== c 63) ; #?
        (fail! s (pos cur) :UFSS_UNSUPPORTED_SYNTAX
               "an EDN value (reader conditionals are not supported)")

        (or (== c 39) (== c 61) (== c 94) (== c 40) (== c 60)) ; ' = ^ ( <
        (fail! s (pos cur) :UFSS_UNSUPPORTED_SYNTAX "an EDN value")

        :else
        (let [tag-offset (pos cur)
              tag (read-token s cur)]
          (when (empty? tag)
            (fail! s tag-offset :UFSS_INVALID_TAG "a tag symbol after #"))
          (when-not (tag-token-valid? tag)
            (fail! s tag-offset :UFSS_INVALID_TAG
                   "a valid tag symbol after #"))
          (read-tag s cur opts (symbol tag) tag-offset))))))

(defn- read-form* [^String s ^longs cur opts]
  (let [i (pos cur)
        len (.length s)]
    (if (>= i len)
      eof-sentinel
      (let [c (int (.charAt s i))]
        (cond
          (== c 40) (do (bump! cur) (read-coll s cur opts 41 :list))
          (== c 91) (do (bump! cur) (read-coll s cur opts 93 :vector))
          (== c 123) (do (bump! cur) (read-map s cur opts))

          (== c 41) (fail! s i :UFSS_UNMATCHED_DELIMITER "a value, not )")
          (== c 93) (fail! s i :UFSS_UNMATCHED_DELIMITER "a value, not ]")
          (== c 125) (fail! s i :UFSS_UNMATCHED_DELIMITER "a value, not }")

          (== c 34) ; "
          (if (and (<= (+ i 3) len)
                   (== (int (.charAt s (+ i 1))) 34)
                   (== (int (.charAt s (+ i 2))) 34))
            (do (set-pos! cur (+ i 3))
                (read-multiline-fence s cur 0))
            (do (bump! cur)
                (read-standard-string s cur opts)))

          (== c 92) (read-char-literal s cur)
          (== c 58) (read-keyword s cur opts)
          (== c 35) (read-dispatch s cur opts)

          (== c 39)
          (fail! s i :UFSS_UNSUPPORTED_SYNTAX "an EDN value, not a quote form")

          (or (== c 64) (== c 94) (== c 96) (== c 126)) ; @ ^ ` ~
          (fail! s i :UFSS_UNSUPPORTED_SYNTAX "an EDN value, not a reader macro")

          :else (read-token-form s cur opts))))))

(defn- read-form [^String s ^longs cur opts]
  (loop []
    (skip-ws! s cur)
    (let [v (read-form* s cur opts)]
      (if (= v discard-sentinel)
        (recur)
        v))))

;;; ---------------------------------------------------------------------------
;;; Public reader API
;;; ---------------------------------------------------------------------------

(defn read-str
  "Read exactly one EDN+UFSS value from s. Trailing non-whitespace is an error."
  ([s] (read-str s nil))
  ([s opts]
   (when-not (string? s)
     (throw (IllegalArgumentException. "read-str expects a string")))
   (let [^String s s
         cur (cursor)
         v (read-form s cur opts)]
     (when (= v eof-sentinel)
       (fail! s (pos cur) :UFSS_UNEXPECTED_EOF "an EDN value"))
     (skip-ws! s cur)
     (let [i (pos cur)]
       (when (< i (.length s))
         (fail! s i :UFSS_TRAILING_DATA "end of input")))
     v)))

(defn read-input
  "Read one model-submitted value. Accepts EDN+UFSS plus JSON conveniences:
  null, \"key\": value (adjacent colon), and the \\/ escape."
  ([s] (read-str s {:json true}))
  ([s opts] (read-str s (assoc opts :json true))))

(defn read-all-str
  "Read every EDN+UFSS form in s. Returns a vector of values."
  ([s] (read-all-str s nil))
  ([s opts]
   (when-not (string? s)
     (throw (IllegalArgumentException. "read-all-str expects a string")))
   (let [^String s s
         cur (cursor)]
     (loop [acc (transient [])]
       (skip-ws! s cur)
       (let [v (read-form* s cur opts)]
         (cond
           (= v eof-sentinel) (persistent! acc)
           (= v discard-sentinel) (recur acc)
           :else (recur (conj! acc v))))))))

(defn read-str-safe
  "Like read-str, but return {:ok value} or {:error ex-data} for expected errors."
  ([s] (read-str-safe s nil))
  ([s opts]
   (try
     {:ok (read-str s opts)}
     (catch clojure.lang.ExceptionInfo e
       (if (= :ufss/read-error (:type (ex-data e)))
         {:error (ex-data e)}
         (throw e))))))
