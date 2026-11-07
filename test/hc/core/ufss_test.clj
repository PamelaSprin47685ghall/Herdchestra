(ns hc.core.ufss-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.core.ufss :as ufss])
  (:import (java.math BigInteger)
           (java.time Instant)
           (java.util Date UUID)))

;;; ---------------------------------------------------------------------------
;;; Helpers
;;; ---------------------------------------------------------------------------

(defn- round-trip? [s]
  (= s (ufss/read-str (ufss/write-str s))))

(defn- error-of [s]
  (let [result (ufss/read-str-safe s)]
    (when-not (:error result)
      (throw (ex-info (str "expected a read error for " (pr-str s)) {:result result})))
    (:error result)))

;;; ---------------------------------------------------------------------------
;;; Property tests (AGENTS.md §8.2 UFSS bijection invariant)
;;; ---------------------------------------------------------------------------

(def ^:private gen-utf16-char
  "Any UTF-16 code unit, including lone and paired surrogates."
  (gen/fmap char (gen/choose 0 0xFFFF)))

(def ^:private gen-utf16-string
  (gen/fmap str/join (gen/vector gen-utf16-char 0 40)))

(def ^:private unicode-samples
  ["中文测试" "日本語" "한국어" "مرحبا"
   (str "e" (char 0x0301))
   (str "a" (char 0xD83D) (char 0xDE00) "b")
   (str (char 0xD83D) (char 0xDE00))
   (str "a" (char 0xD800) "b")
   (str "a" (char 0xDC00) "b")
   (str (char 0xD800) (char 0xD800))
   (str (char 0xDC00) (char 0xD800))
   (str (char 0xD83D) (char 0xDE00) "\n" "x")])

(def ^:private fence-tokens
  ["\"" "\\" "#" "\"\"\"" "\n" "\r" "\t" " " "abc" "##"
   "\"\"\"#" "\"\"\"##"
   (str (char 0)) (str (char 0x7F)) (str (char 0x85)) (str (char 0x2028))])

(def ^:private gen-fence-string
  (gen/fmap str/join (gen/vector (gen/elements fence-tokens) 0 12)))

(def ^:private gen-ufss-string
  (gen/one-of [gen-utf16-string
               gen-fence-string
               (gen/elements unicode-samples)]))

(deftest string-bijection-property
  (testing "arbitrary UTF-16 strings, including fences, surrogates, controls, newlines"
    (let [result (tc/quick-check
                  500
                  (prop/for-all [s gen-ufss-string]
                    (= s (ufss/read-str (ufss/write-str s)))))]
      (is (:pass? result) (pr-str result)))))

(deftest ascii-string-bijection-property
  (let [result (tc/quick-check
                300
                (prop/for-all [s gen/string-ascii]
                  (= s (ufss/read-str (ufss/write-str s)))))]
    (is (:pass? result) (pr-str result))))

(def ^:private gen-keyword
  (gen/fmap #(keyword (str "k" %)) gen/nat))

(def ^:private gen-namespaced-keyword
  (gen/fmap (fn [n] (keyword "demo" (str "k" n))) gen/nat))

(def ^:private gen-symbol
  (gen/fmap #(symbol (str "s" %)) gen/nat))

(def ^:private gen-namespaced-symbol
  (gen/fmap (fn [n] (symbol "demo" (str "s" n))) gen/nat))

(def ^:private gen-ratio
  (gen/fmap (fn [[a b]]
              (let [b (if (zero? b) 1 b)]
                (/ a b)))
            (gen/tuple gen/small-integer gen/small-integer)))

(def ^:private gen-decimal
  (gen/fmap #(BigDecimal. (str %)) gen/small-integer))

(def ^:private gen-date
  (gen/fmap #(Date. (long %)) gen/large-integer))

(def ^:private gen-uuid
  (gen/fmap (fn [[a b]] (UUID. (long a) (long b)))
            (gen/tuple gen/large-integer gen/large-integer)))

(def ^:private gen-tagged
  (gen/fmap (fn [s] (tagged-literal 'demo s)) gen-ufss-string))

(defn- gen-edn [depth]
  (if (zero? depth)
    (gen/one-of [(gen/return nil)
                 gen/boolean
                 gen/small-integer
                 (gen/fmap bigint gen/large-integer)
                 gen-decimal
                 gen-ratio
                 (gen/double* {:NaN? false :infinite? false})
                 gen-utf16-char
                 gen-ufss-string
                 gen-keyword
                 gen-namespaced-keyword
                 gen-symbol
                 gen-namespaced-symbol
                 gen-date
                 gen-uuid
                 gen-tagged])
    (gen/one-of [(gen-edn 0)
                 (gen/vector (gen-edn (dec depth)) 0 4)
                 (gen/fmap #(apply list %) (gen/vector (gen-edn (dec depth)) 0 4))
                 (gen/set (gen-edn (dec depth)))
                 (gen/map (gen-edn (dec depth)) (gen-edn (dec depth)))])))

(deftest edn-value-bijection-property
  (testing "arbitrary nested EDN values survive write-str/read-str"
    (let [result (tc/quick-check
                  300
                  (prop/for-all [v (gen-edn 2)]
                    (= v (ufss/read-str (ufss/write-str v)))))]
      (is (:pass? result) (pr-str result)))))

;;; ---------------------------------------------------------------------------
;;; Deterministic string ladder
;;; ---------------------------------------------------------------------------

(deftest plain-string-ladder
  (is (= "\"\"" (ufss/write-str "")))
  (is (= "\"a b\"" (ufss/write-str "a b")))
  (is (= "\"foo\"" (ufss/write-str "foo"))))

(deftest single-line-fence-ladder
  (testing "smallest n such that \"#^n does not occur"
    (is (= "#\"foo\"\"#" (ufss/write-str "foo\"")))
    (is (= "##\"foo\"#\"##" (ufss/write-str "foo\"#")))
    (is (= "###\"foo\"##\"###" (ufss/write-str "foo\"##")))
    (is (= "####\"foo\"###\"####" (ufss/write-str "foo\"###")))
    (is (= "#####\"foo\"####\"#####" (ufss/write-str "foo\"####")))
    (is (= "#\"C:\\Users\\dev\\app.clj\"#" (ufss/write-str "C:\\Users\\dev\\app.clj")))
    (is (= "#\"(defn foo [x] \"bar\")\"#" (ufss/write-str "(defn foo [x] \"bar\")")))
    (is (= "#\"a\\b\"#" (ufss/write-str "a\\b")))
    (is (= (str "#\"a" "\t" "b\"#") (ufss/write-str "a\tb")))))

(deftest multiline-block-ladder
  (is (= "\"\"\"\na\nb\n\"\"\"" (ufss/write-str "a\nb")))
  (is (= "\"\"\"\na\n\n\"\"\"" (ufss/write-str "a\n")))
  (is (= "\"\"\"\n\n\n\"\"\"" (ufss/write-str "\n")))
  (is (= "\"\"\"\n\"\"\n\"\"\"" (ufss/write-str "\"\"")))
  (is (= "\"\"\"\n\"\n\"\"\"" (ufss/write-str "\"")))
  (is (= "\"\"\"\n\"\"foo\n\"\"\"" (ufss/write-str "\"\"foo")))
  (testing "triple quotes force n >= 1"
    (is (= "#\"\"\"\n\"\"\"foo\n\"\"\"#" (ufss/write-str "\"\"\"foo")))
    (is (= "##\"\"\"\n\"\"\"#foo\n\"\"\"##" (ufss/write-str "\"\"\"#foo")))
    (is (= "###\"\"\"\n\"\"\"##x\n\"\"\"###" (ufss/write-str "\"\"\"##x")))
    (is (= "####\"\"\"\n\"\"\"###x\n\"\"\"####" (ufss/write-str "\"\"\"###x"))))
  (testing "backslash forces n >= 1 in a block"
    (is (= "#\"\"\"\na\\b\nc\n\"\"\"#" (ufss/write-str "a\\b\nc")))))

(deftest control-character-fallback
  (is (= "\"a\\rb\"" (ufss/write-str "a\rb")))
  (is (= "\"a\\u0001b\"" (ufss/write-str "a\u0001b")))
  (is (= "\"a\\u0000b\"" (ufss/write-str "a\u0000b")))
  (is (= "\"a\\u007Fb\"" (ufss/write-str "a\u007Fb")))
  (is (= "\"a\\u0085b\"" (ufss/write-str "a\u0085b")))
  (testing "CRLF uses the escaped form, never a block"
    (is (= "\"a\\r\\nb\"" (ufss/write-str "a\r\nb")))))

(deftest surrogate-fallback
  (testing "unpaired surrogates escape; paired surrogates stay raw"
    (is (= "\"a\\uD800b\"" (ufss/write-str (str "a" (char 0xD800) "b"))))
    (is (= "\"a\\uDC00b\"" (ufss/write-str (str "a" (char 0xDC00) "b"))))
    (is (= (str "\"a" (char 0xD83D) (char 0xDE00) "b\"")
           (ufss/write-str (str "a" (char 0xD83D) (char 0xDE00) "b"))))
    (is (= "\"\\uD800\\uD800\""
           (ufss/write-str (str (char 0xD800) (char 0xD800))))))
  (doseq [s unicode-samples]
    (is (round-trip? s) (pr-str s))))

(deftest string-fence-round-trips
  (doseq [s ["foo"
             "foo\""
             "foo\"#"
             "foo\"##"
             "C:\\Users\\dev\\app.clj"
             "(defn foo [x] \"bar\")"
             "a\\b"
             "a\tb"
             "a\rb"
             "a\u0001b"
             "\""
             "\"\""
             "\"\"foo"
             "\"\"\""
             "\"\"\"foo"
             "\"\"\"#foo"
             "a\nb"
             "a\n"
             "\n"
             "\n\n"
             "  a\n    b"
             "a\n  \"\"\"\nb"
             "a\n  \"\"\"#\nb"]]
    (is (round-trip? s) (pr-str s))))

(deftest block-strings-in-nested-collections
  (doseq [v [{"a\nb" {:x "c\nd"}}
             ["a\nb" {:k "c\nd"}]
             {"k" ["a\nb" "c\nd"]}
             (list "a\nb" "c\nd")
             #{"a\nb" "c\nd"}]]
    (is (= v (ufss/read-str (ufss/write-str v))) (pr-str v))))

;;; ---------------------------------------------------------------------------
;;; Reader: fences
;;; ---------------------------------------------------------------------------

(deftest multiline-fence-reading
  (is (= "a\n  b" (ufss/read-str "#\"\"\"\n    a\n      b\n    \"\"\"#")))
  (is (= "a\n" (ufss/read-str "#\"\"\"\na\n\n\"\"\"#")))
  (is (= "" (ufss/read-str "#\"\"\"\n\"\"\"#")))
  (is (= "" (ufss/read-str "\"\"\"\n\"\"\"")))
  (is (= "\"\"foo" (ufss/read-str "\"\"\"\n\"\"foo\n\"\"\"")))
  (is (= "a\nb" (ufss/read-str "\"\"\"\na\nb\n\"\"\"")))
  (testing "empty lines are exempt from indentation"
    (is (= "a\n\nb" (ufss/read-str "#\"\"\"\n  a\n\n  b\n  \"\"\"#"))))
  (testing "trailing empty line restores a trailing newline"
    (is (= "a\n" (ufss/read-str "#\"\"\"\n  a\n\n  \"\"\"#")))))

(deftest fence-read-errors
  (is (= :UFSS_MULTILINE_NEEDS_NEWLINE (:error (error-of "#\"\"\"x"))))
  (is (= :UFSS_UNTERMINATED_FENCE (:error (error-of "#\"abc"))))
  (is (= :UFSS_UNTERMINATED_FENCE (:error (error-of "#\"\"\"\na"))))
  (let [e (error-of "#\"\"\"\n    a\n  b\n    \"\"\"#")]
    (is (= :UFSS_INDENT_VIOLATION (:error e)))
    (is (= 3 (:line e)))
    (is (= 1 (:column e)))
    (is (= 11 (:offset e)))
    (is (string? (:expected e))))
  (let [e (error-of "#\"\"\"x")]
    (is (= 1 (:line e)))
    (is (= 5 (:column e)))
    (is (= 4 (:offset e)))
    (is (string? (:expected e)))))

;;; ---------------------------------------------------------------------------
;;; Reader: EDN
;;; ---------------------------------------------------------------------------

(deftest edn-reader
  (is (= {:a 1 :b [2 3]} (ufss/read-str "{:a 1 :b [2 3]}")))
  (is (= [1 2 3] (ufss/read-str "[1, 2, 3]")))
  (is (= '(1 2) (ufss/read-str "(1 2)")))
  (is (= #{1 2} (ufss/read-str "#{1 2}")))
  (is (= 2 (ufss/read-str "#_ 1 2")))
  (is (= 42 (ufss/read-str "; comment\n42")))
  (is (= 42 (ufss/read-str "#! shebang\n42")))
  (is (= nil (ufss/read-str "nil")))
  (is (= true (ufss/read-str "true")))
  (is (= 1M (ufss/read-str "1M")))
  (is (= 1N (ufss/read-str "1N")))
  (is (= 1/2 (ufss/read-str "1/2")))
  (is (= 2 (ufss/read-str "2/1")))
  (is (= \newline (ufss/read-str "\\newline")))
  (is (= \A (ufss/read-str "\\u0041")))
  (is (= \A (ufss/read-str "\\o101")))
  (is (= (char 255) (ufss/read-str "\\o377")))
  (is (= :UFSS_INVALID_CHAR (:error (error-of "\\o400"))))
  (is (= "A" (ufss/read-str "\"\\101\"")))
  (is (= (str (char 255)) (ufss/read-str "\"\\377\"")))
  (is (= :UFSS_INVALID_ESCAPE (:error (error-of "\"\\400\""))))
  (is (= (str (char 0)) (ufss/read-str "\"\\0\"")))
  (is (= \) (ufss/read-str "\\)")))
  (is (= Double/POSITIVE_INFINITY (ufss/read-str "##Inf")))
  (is (= Double/NEGATIVE_INFINITY (ufss/read-str "##-Inf")))
  (is (Double/isNaN (ufss/read-str "##NaN")))
  (is (= "x y" (ufss/read-str "#\"x y\"#")))
  (is (= "C:\\Users" (ufss/read-str "#\"C:\\Users\"#")))
  (is (= (Date. 0) (ufss/read-str "#inst \"1970-01-01T00:00:00Z\"")))
  (is (= (UUID/fromString "123e4567-e89b-12d3-a456-426614174000")
         (ufss/read-str "#uuid \"123e4567-e89b-12d3-a456-426614174000\"")))
  (is (= (tagged-literal 'demo {:x 1}) (ufss/read-str "#demo {:x 1}")))
  (is (= (tagged-literal 'foo/bar 1) (ufss/read-str "#foo/bar 1")))
  (is (= (tagged-literal '-foo 1) (ufss/read-str "#-foo 1"))))

(deftest json-input-compatibility
  (is (= {"a" 1 "b" [true nil]}
         (ufss/read-input "{\"a\": 1, \"b\": [true, null]}")))
  (is (= {"a" "x/y"} (ufss/read-input "{\"a\":\"x\\/y\"}")))
  (testing "adjacent colon is JSON; spaced colon remains an EDN keyword value"
    (is (= {"a" :b} (ufss/read-input "{\"a\" :b}")))
    (is (= {"a" :1} (ufss/read-str "{\"a\":1}")))
    (is (= {"a" 1} (ufss/read-input "{\"a\":1}")))
    (is (= {"a" 1} (ufss/read-input "{\"a\" : 1}")))
    (is (= {"a" "x"} (ufss/read-input "{\"a\" :\"x\"}")))
    (is (= {"a" [1]} (ufss/read-input "{\"a\" :[1]}")))
    (is (= {"a" 1} (ufss/read-input "{\"a\" :,1}")))
    (is (= {"a" {"b" 2}} (ufss/read-input "{\"a\" :{\"b\":2}}"))))
  (is (= nil (ufss/read-input "null")))
  (is (= 'null (ufss/read-str "null"))))

(deftest reader-errors
  (doseq [[s code] [["[1 2" :UFSS_UNEXPECTED_EOF]
                    ["{:a}" :UFSS_ODD_MAP]
                    ["{:a 1 :a 2}" :UFSS_DUPLICATE_KEY]
                    ["#{1 1}" :UFSS_DUPLICATE_SET_ELEMENT]
                    [")" :UFSS_UNMATCHED_DELIMITER]
                    ["\"abc" :UFSS_UNTERMINATED_STRING]
                    ["\"\\q\"" :UFSS_INVALID_ESCAPE]
                    ["::foo" :UFSS_INVALID_KEYWORD]
                    ["1abc" :UFSS_INVALID_NUMBER]
                    ["1/0" :UFSS_INVALID_NUMBER]
                    ["1/2N" :UFSS_INVALID_NUMBER]
                    ["1/2M" :UFSS_INVALID_NUMBER]
                    ["#1 2" :UFSS_INVALID_TAG]
                    ["#nil 1" :UFSS_INVALID_TAG]
                    ["#true 1" :UFSS_INVALID_TAG]
                    ["#false 1" :UFSS_INVALID_TAG]
                    ["#@foo 1" :UFSS_INVALID_TAG]
                    ["#~foo 1" :UFSS_INVALID_TAG]
                    ["##foo" :UFSS_INVALID_FENCE]
                    ["[1] 2" :UFSS_TRAILING_DATA]
                    ["#:foo{:a 1}" :UFSS_UNSUPPORTED_SYNTAX]
                    ["#?(:clj 1)" :UFSS_UNSUPPORTED_SYNTAX]]]
    (let [e (error-of s)]
      (is (= code (:error e)) (pr-str s))
      (is (integer? (:line e)))
      (is (integer? (:column e)))
      (is (string? (:expected e)))
      (is (integer? (:offset e))))))

(deftest read-str-safe-and-read-all
  (is (= {:ok [1 2]} (ufss/read-str-safe "[1 2]")))
  (is (= :UFSS_UNEXPECTED_EOF (get-in (ufss/read-str-safe "[1 2") [:error :error])))
  (is (= [1 2 [4]] (ufss/read-all-str "; c\n1 2 #_ 3 [4] ")))
  (is (= [] (ufss/read-all-str "   "))))

(deftest trailing-comments
  (is (= 1 (ufss/read-str "1 ; trailing")))
  (is (= 1 (ufss/read-str "1 #! trailing")))
  (is (= 1 (ufss/read-str "1 #! trailing\n")))
  (is (= [1 2] (ufss/read-all-str "1 #! trailing\n2"))))

;;; ---------------------------------------------------------------------------
;;; Printer: nested values
;;; ---------------------------------------------------------------------------

(deftest nested-value-printing
  (let [v {:name "fix-timeout"
           :state :parked
           :source "1 | (defprogram fix-timeout []\n2 |   (let [x 1]\n3 |     x))"
           :payload {:needle "(def timeout-ms 30000)"
                     :candidates []
                     :nearest {:span [812 835]
                               :text "(def timeout-ms 30000) ; default"
                               :distance 10}}
           :remaining-wait-ms 4200}
        text (ufss/write-str v)]
    (is (string? text))
    (is (re-find #":source \"\"\"\n" text))
    (is (= v (ufss/read-str text))))
  (testing "single-line fences inside collections"
    (let [v {:path "C:\\tmp\\a.clj"
             :needle "(defn f [] \"x\")"
             :items ["a\"b" "c\\d"]}]
      (is (= v (ufss/read-str (ufss/write-str v)))))))

(deftest write-str-rejects-identity-changing-symbols
  (is (thrown? clojure.lang.ExceptionInfo (ufss/write-str (symbol "1abc"))))
  (is (thrown? clojure.lang.ExceptionInfo (ufss/write-str (symbol "nil"))))
  (is (thrown? clojure.lang.ExceptionInfo (ufss/write-str (symbol "a b"))))
  (is (thrown? clojure.lang.ExceptionInfo (ufss/write-str (keyword "a b"))))
  (is (thrown? clojure.lang.ExceptionInfo (ufss/write-str (keyword ":foo"))))
  (is (thrown? clojure.lang.ExceptionInfo (ufss/write-str (keyword ":"))))
  (is (thrown? clojure.lang.ExceptionInfo
               (ufss/write-str (tagged-literal 'uuid "123e4567-e89b-12d3-a456-426614174000")))))

(deftest write-str-rejects-non-edn-numerics
  (testing "Float would read back as Double"
    (let [e (try (ufss/write-str (float 0.1))
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (= :ufss/write-error (:type (ex-data e))))
      (is (instance? Float (:value (ex-data e))))))
  (testing "BigInteger prints as N and reads back as BigInt"
    (is (= "123N" (ufss/write-str (BigInteger. "123"))))
    (is (= 123N (ufss/read-str (ufss/write-str (BigInteger. "123")))))))

(deftest write-str-rejects-out-of-date-range-instant
  (is (= "#inst \"1970-01-01T00:00:00Z\""
         (ufss/write-str (Instant/ofEpochMilli 0))))
  (let [v (Instant/ofEpochSecond 10000000000000000)
        e (try (ufss/write-str v)
               (catch clojure.lang.ExceptionInfo e e))]
    (is (= :ufss/write-error (:type (ex-data e))))
    (is (= v (:value (ex-data e))))))
