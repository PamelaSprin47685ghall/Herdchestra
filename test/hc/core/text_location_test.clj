(ns hc.core.text-location-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.core.text-location :as loc]
            [hc.core.text-location.nearest :as nearest]
            [hc.core.text-location.scan :as scan]))

;; ---------------------------------------------------------------------------
;; Helpers

(defn where-all
  [content needle]
  (loc/whereAll {:content content :needle needle}))

(defn where
  [content needle]
  (loc/where {:content content :needle needle}))

(defn brute-spans
  "All non-overlapping exact matches, independent of the implementation."
  [^String content ^String needle]
  (if (zero? (.length needle))
    []
    (let [n (.length needle)
          len (.length content)]
      (loop [b 0 out []]
        (if (> (+ (long b) n) len)
          out
          (if (.startsWith content needle (int b))
            (recur (+ b n) (conj out [b (+ b n)]))
            (recur (inc b) out)))))))

(defn brute-overlapping-spans
  "All exact matches, including overlapping occurrences."
  [^String content ^String needle]
  (if (zero? (.length needle))
    []
    (let [n (.length needle)
          len (.length content)]
      (loop [b 0 out []]
        (if (> (+ (long b) n) len)
          out
          (if (.startsWith content needle (int b))
            (recur (inc b) (conj out [b (+ b n)]))
            (recur (inc b) out)))))))

(defn levenshtein
  "Full Levenshtein distance over UTF-16 code units."
  [^String a ^String b]
  (let [n (.length a)
        m (.length b)
        prev (long-array (inc m))]
    (dotimes [j (inc m)]
      (aset prev j j))
    (dotimes [i n]
      (let [cur (long-array (inc m))]
        (aset cur 0 (inc i))
        (dotimes [j m]
          (let [cost (if (= (.charAt a (int i)) (.charAt b (int j))) 0 1)]
            (aset cur (inc j)
                  (min (inc (aget prev (inc j)))
                       (inc (aget cur j))
                       (+ (aget prev j) cost)))))
        (System/arraycopy cur 0 prev 0 (inc m))))
    (aget prev m)))

;; ---------------------------------------------------------------------------
;; Exact UTF-16 matching

(deftest exact-utf16-spans
  (testing "emoji spans count UTF-16 code units"
    (let [content "a😀b😀c"
          spans (where-all content "😀")]
      (is (= [[1 3] [4 6]] spans))
      (is (= "😀" (subs content 1 3)))
      (is (= "😀" (subs content 4 6)))))
  (testing "matching is literal, not normalized"
    (is (= [] (where-all "é" (str "e" (char 0x0301)))))
    (is (= [[0 1]] (where-all "é" "é"))))
  (testing "no implicit trim"
    (is (= [[1 2]] (where-all " x " "x")))
    (is (= [[0 2]] (where-all " x " " x")))
    (is (= [[1 3]] (where-all " x " "x "))))
  (testing "indentation is significant when the needle carries it"
    (is (= [[5 11]] (where-all "  foo\n  foo" "\n  foo")))
    (is (= [[2 5] [8 11]] (where-all "  foo\n  foo" "foo")))))

(deftest where-all-is-non-overlapping
  (is (= [[0 2]] (where-all "aaa" "aa")))
  (is (= [[0 2] [2 4]] (where-all "aaaa" "aa")))
  (is (= [] (where-all "abc" "")))
  (is (= [] (where-all "" "a")))
  (is (= [] (where-all "ab" "abc"))))

(deftest cjk-and-combining-text
  (testing "CJK code units and literal matching"
    (is (= [[0 3] [4 7]] (where-all "万象术 万象术" "万象术")))
    (is (= [0 3] (where "万象术" "万象术")))
    (let [result (where "万象术 万象术" "万象术")]
      (is (= :parked (:state result)))
      (is (= 2 (count (get-in result [:payload :candidates]))))))
  (testing "nearest keeps code-unit distance and does not normalize"
    (let [cjk (get-in (where "万象术\n" "万象书") [:payload :nearest])]
      (is (= [0 3] (:span cjk)))
      (is (= "万象术" (:text cjk)))
      (is (= 1 (:distance cjk))))
    (let [combining (get-in (where "e\u0301\n" "é") [:payload :nearest])]
      (is (= [0 2] (:span combining)))
      (is (= "e\u0301" (:text combining)))
      (is (= 2 (:distance combining))))))

(deftest where-unique
  (is (= [2 8] (where "xxneedlexx" "needle")))
  (is (= [0 1] (where "a" "a")))
  (is (= [1 3] (where "a😀b" "😀")))
  (is (false? (loc/park-signal? [2 8]))))

(deftest park-signal-shape
  (is (true? (loc/park-signal? (where "abc" "abd"))))
  (is (true? (loc/park-signal? (where "ab ab" "ab")))))

(deftest where-counts-overlapping-occurrences
  (testing "overlapping occurrences make where ambiguous"
    (let [result (where "aaa" "aa")
          candidates (get-in result [:payload :candidates])]
      (is (= :parked (:state result)))
      (is (= 2 (count candidates)))
      (is (= [[0 2] [1 3]] (mapv :span candidates)))))
  (testing "whereAll keeps the non-overlapping replacement law"
    (is (= [[0 2]] (where-all "aaa" "aa")))
    (is (= [[0 2] [2 4]] (where-all "aaaa" "aa"))))
  (testing "three overlapping occurrences"
    (let [candidates (get-in (where "aaaa" "aa") [:payload :candidates])]
      (is (= [[0 2] [1 3] [2 4]] (mapv :span candidates)))))
  (testing "non-overlapping repeats also park"
    (is (= :parked (:state (where "abab" "ab"))))
    (is (= [0 2] (where "abx" "ab")))))

(deftest where-zero-match-payload
  (let [result (where "hello world\n" "hello wurld")
        payload (:payload result)
        nearest (:nearest payload)]
    (is (= :parked (:state result)))
    (is (= :where-ambiguous (:reason result)))
    (is (= "hello wurld" (:needle payload)))
    (is (= [] (:candidates payload)))
    (is (= 64 (:max-distance payload)))
    (is (false? (:budget-exhausted payload)))
    (is (= [0 11] (:span nearest)))
    (is (= "hello world" (:text nearest)))
    (is (= 1 (:distance nearest)))
    (is (not (contains? nearest :truncated)))))

(deftest where-zero-match-without-near-line
  (let [needle (apply str (repeat 100 "z"))
        result (where "abc\ndef\n" needle)
        payload (:payload result)]
    (is (= :parked (:state result)))
    (is (nil? (:nearest payload)))
    (is (false? (:budget-exhausted payload)))))

(deftest where-multiple-match-payload
  (let [content (apply str (repeat 10 "xNEEDLEx"))
        result (where content "NEEDLE")
        payload (:payload result)
        candidates (:candidates payload)]
    (is (= :parked (:state result)))
    (is (= :where-ambiguous (:reason result)))
    (is (= "NEEDLE" (:needle payload)))
    (is (= 8 (count candidates)))
    (is (= (take 8 (where-all content "NEEDLE"))
           (map :span candidates)))
    (is (every? (fn [{:keys [span context]}]
                  (and (= "NEEDLE" (subs content (first span) (second span)))
                       (str/includes? context "NEEDLE")))
                candidates))
    (is (not (contains? payload :nearest)))))

(deftest ambiguous-context-is-eighty-units-per-side
  (let [left (apply str (repeat 100 "a"))
        right (apply str (repeat 100 "b"))
        content (str left "NEEDLE" right "NEEDLE" "tail")
        result (where content "NEEDLE")
        context (get-in result [:payload :candidates 0 :context])]
    (is (= (str (apply str (repeat 80 "a"))
                "NEEDLE"
                (apply str (repeat 80 "b")))
           context))
    (is (= 166 (count context)))))

(deftest ambiguous-context-does-not-split-surrogate-pairs
  (testing "start boundary moves inward"
    (let [content (str (apply str (repeat 80 "a"))
                       "😀"
                       (apply str (repeat 79 "a"))
                       "NEEDLE"
                       "xNEEDLE")
          result (where content "NEEDLE")
          context (get-in result [:payload :candidates 0 :context])]
      (is (= :parked (:state result)))
      (is (= 2 (count (get-in result [:payload :candidates]))))
      (is (not (Character/isLowSurrogate (.charAt context 0))))
      (is (= \a (.charAt context 0)))))
  (testing "end boundary moves inward"
    (let [content (str "NEEDLE"
                       (apply str (repeat 79 "a"))
                       "😀"
                       "xNEEDLE")
          result (where content "NEEDLE")
          context (get-in result [:payload :candidates 0 :context])]
      (is (= :parked (:state result)))
      (is (= 2 (count (get-in result [:payload :candidates]))))
      (is (not (Character/isHighSurrogate
                (.charAt context (dec (count context))))))
      (is (= \a (.charAt context (dec (count context))))))))

;; ---------------------------------------------------------------------------
;; Nearest diagnostic

(deftest nearest-keeps-the-earliest-tie
  (let [result (where "abcd\nabce\n" "abcf")
        nearest (get-in result [:payload :nearest])]
    (is (= [0 4] (:span nearest)))
    (is (= "abcd" (:text nearest)))
    (is (= 1 (:distance nearest)))))

(deftest nearest-handles-crlf-without-trimming
  (let [result (where "a\r\nfooo\r\nb" "food")
        nearest (get-in result [:payload :nearest])]
    (is (= [3 7] (:span nearest)))
    (is (= "fooo" (:text nearest)))
    (is (= 1 (:distance nearest)))
    (is (= "fooo" (subs "a\r\nfooo\r\nb" 3 7)))))

(deftest nearest-zero-distance-and-lone-cr
  (is (= {:nearest {:span [0 3] :text "abc" :distance 0}
          :budget-exhausted? false}
         (loc/nearest {:content "abc\n" :needle "abc"})))
  (let [nearest (get-in (where "a\rfoo\rb" "food") [:payload :nearest])]
    (is (= [2 5] (:span nearest)))
    (is (= "foo" (:text nearest)))
    (is (= 1 (:distance nearest)))))

(deftest nearest-distance-counts-utf16-code-units
  (let [result (where "😀\n" "😀😀")
        nearest (get-in result [:payload :nearest])]
    (is (= [0 2] (:span nearest)))
    (is (= 2 (:distance nearest)))))

(deftest nearest-truncates-text-but-not-span
  (let [line (str (apply str (repeat 249 "a")) "b")
        needle (str (apply str (repeat 249 "a")) "c")
        result (where (str line "\n") needle)
        nearest (get-in result [:payload :nearest])]
    (is (= [0 250] (:span nearest)))
    (is (= 200 (count (:text nearest))))
    (is (true? (:truncated nearest)))
    (is (= 1 (:distance nearest)))))

(deftest nearest-text-of-exactly-200-is-not-truncated
  (let [line (apply str (repeat 200 "a"))
        needle (str (apply str (repeat 199 "a")) "b")
        result (where line needle)
        nearest (get-in result [:payload :nearest])]
    (is (= [0 200] (:span nearest)))
    (is (= 200 (count (:text nearest))))
    (is (not (contains? nearest :truncated)))))

(deftest nearest-truncates-at-two-hundred-one-units
  (let [line (apply str (repeat 201 "a"))
        needle (str (apply str (repeat 200 "a")) "b")
        result (where (str line "\n") needle)
        nearest (get-in result [:payload :nearest])]
    (is (= [0 201] (:span nearest)))
    (is (= 200 (count (:text nearest))))
    (is (true? (:truncated nearest)))
    (is (= 1 (:distance nearest)))))

(deftest nearest-truncation-keeps-surrogate-pairs
  (let [line (str (apply str (repeat 199 "a")) "😀" "b")
        needle (str (apply str (repeat 199 "a")) "😀" "c")
        result (where (str line "\n") needle)
        nearest (get-in result [:payload :nearest])]
    (is (= [0 202] (:span nearest)))
    (is (= 199 (count (:text nearest))))
    (is (true? (:truncated nearest)))))

(deftest nearest-scans-only-the-first-two-thousand-non-blank-lines
  (let [far (apply str (repeat 200 "x"))
        near (str (str/join "\n" (repeat 2000 far)) "\ntargxt\n")
        miss (where near "target")]
    (is (nil? (get-in miss [:payload :nearest])))
    (is (false? (get-in miss [:payload :budget-exhausted]))))
  (let [far (apply str (repeat 200 "x"))
        content (str (str/join "\n" (repeat 1999 far)) "\ntargxt\n")
        result (where content "target")
        start (str/index-of content "targxt")]
    (is (= [start (+ start 6)] (get-in result [:payload :nearest :span])))
    (is (= "targxt" (get-in result [:payload :nearest :text])))
    (is (= 1 (get-in result [:payload :nearest :distance])))))

(deftest nearest-respects-the-dp-budget
  (let [result (loc/locate {:content "abcdef" :needle "abcxef"} {:dp-budget 1})
        payload (:payload result)]
    (is (= :none (:kind result)))
    (is (true? (:budget-exhausted payload)))
    (is (nil? (:nearest payload))))
  (let [result (loc/locate {:content "abcxef\nabcdeg" :needle "abcdef"}
                           {:dp-budget 4})
        payload (:payload result)]
    (is (true? (:budget-exhausted payload)))
    (is (= [0 6] (get-in payload [:nearest :span])))
    (is (= 1 (get-in payload [:nearest :distance]))))
  (let [result (loc/locate {:content "abcdef" :needle "abcxef"} {:dp-budget 1000})]
    (is (= 1 (get-in result [:payload :nearest :distance])))
    (is (false? (get-in result [:payload :budget-exhausted])))))

(deftest banded-distance-reports-real-cell-count
  (let [exhausted (nearest/banded-distance "aaaa" "bbbb" 64 1)]
    (is (true? (:exhausted? exhausted)))
    (is (nil? (:distance exhausted)))
    (is (= 0 (:cells exhausted))))
  (let [exhausted (nearest/banded-distance "aaaa" "bbbb" 64 5)]
    (is (true? (:exhausted? exhausted)))
    (is (= 5 (:cells exhausted))))
  (let [complete (nearest/banded-distance "abcxef" "abcdef" 64 1000)]
    (is (false? (:exhausted? complete)))
    (is (= 1 (:distance complete)))))

(deftest nearest-band-boundary
  (let [needle (apply str (repeat 64 "a"))
        line (apply str (repeat 64 "b"))
        result (where (str line "\n") needle)]
    (is (= 64 (get-in result [:payload :nearest :distance]))))
  (let [needle (apply str (repeat 65 "a"))
        line (apply str (repeat 65 "b"))
        result (where (str line "\n") needle)]
    (is (nil? (get-in result [:payload :nearest])))))

(deftest nearest-max-distance-option
  (let [miss (loc/locate {:content "abc" :needle "xyz"} {:max-distance 2})
        hit (loc/locate {:content "abc" :needle "xyz"} {:max-distance 3})]
    (is (= 2 (get-in miss [:payload :max-distance])))
    (is (nil? (get-in miss [:payload :nearest])))
    (is (= 3 (get-in hit [:payload :nearest :distance])))))

;; ---------------------------------------------------------------------------
;; Line spans and edge cases

(deftest line-spans-handle-line-terminators
  (is (= [{:start 0 :end 1} {:start 3 :end 6}]
         (scan/line-spans "a\r\nfoo\r\n" 10)))
  (is (= [{:start 0 :end 1} {:start 2 :end 5}]
         (scan/line-spans "a\rfoo" 10)))
  (is (= [{:start 2 :end 3}] (scan/line-spans "\n\na\n" 10)))
  (is (= [{:start 0 :end 1} {:start 2 :end 3}]
         (scan/line-spans "a\nb\nc" 2)))
  (is (= [] (scan/line-spans " \n" 10)))
  (is (= [{:start 4 :end 5} {:start 6 :end 7}]
         (scan/line-spans "\n \n\na\nb\n" 2)))
  (is (= [] (scan/line-spans "" 10))))

(deftest empty-needle-is-not-a-slice
  (let [result (where "abc" "")]
    (is (= :parked (:state result)))
    (is (= [] (get-in result [:payload :candidates])))
    (is (nil? (get-in result [:payload :nearest]))))
  (is (= [] (where-all "abc" ""))))

(deftest empty-needle-skips-line-scan
  (with-redefs [scan/line-spans (fn [& _]
                                  (throw (ex-info "line-spans must not run" {})))]
    (is (= {:nearest nil :budget-exhausted? false}
           (loc/nearest {:content "abc\ndef\n" :needle ""})))
    (is (nil? (get-in (where "abc\ndef\n" "") [:payload :nearest])))))

(defn- thrown-data
  [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo e
      (ex-data e))))

(deftest invalid-arguments-throw
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #":request"
                        (loc/where "not a map")))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #":content"
                        (loc/where {:content nil :needle "x"})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #":needle"
                        (loc/whereAll {:content "x"})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #":dp-budget"
                        (loc/locate {:content "x" :needle "y"} {:dp-budget -1})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #":options"
                        (loc/locate {:content "x" :needle "y"} :bad)))
  (is (= :INVALID_ARGUMENT
         (:status (thrown-data #(loc/where {:content nil :needle "x"})))))
  (is (= :INVALID_ARGUMENT
         (:status (thrown-data #(loc/locate {:content "x" :needle "y"}
                                            {:dp-budget -1}))))))

(deftest diagnostic-option-bounds
  (is (= :INVALID_ARGUMENT
         (:status (thrown-data #(loc/locate {:content "x" :needle "y"}
                                            {:max-distance 4097})))))
  (is (= :INVALID_ARGUMENT
         (:status (thrown-data #(loc/locate {:content "x" :needle "y"}
                                            {:max-distance -1})))))
  (is (= :INVALID_ARGUMENT
         (:status (thrown-data #(loc/nearest {:content "x" :needle "y"}
                                             {:max-lines -1})))))
  (is (= :INVALID_ARGUMENT
         (:status (thrown-data #(loc/nearest {:content "x" :needle "y"}
                                             {:dp-budget 1.5})))))
  (is (= :none (:kind (loc/locate {:content "x" :needle "y"}
                                  {:max-distance 4096}))))
  (is (false? (:budget-exhausted?
               (loc/nearest {:content "x" :needle "y"}
                            {:max-lines Long/MAX_VALUE
                             :dp-budget Long/MAX_VALUE})))))

;; ---------------------------------------------------------------------------
;; Properties

(defspec banded-distance-matches-full-dp 100
  (prop/for-all [a gen/string
                 b gen/string]
    (let [k 8
          expected (levenshtein a b)
          result (nearest/banded-distance a b k 1000000)]
      (and (false? (:exhausted? result))
           (if (<= expected k)
             (= expected (:distance result))
             (nil? (:distance result)))))))

(defspec where-all-matches-brute-force 100
  (prop/for-all [content gen/string-alphanumeric
                 needle gen/string-alphanumeric]
    (let [spans (where-all content needle)
          expected (brute-spans content needle)]
      (and (= expected spans)
           (every? (fn [[b e]] (= needle (subs content b e))) spans)
           (every? (fn [[[_ e1] [b2 _]]] (<= e1 b2))
                   (map vector spans (rest spans)))))))

(defspec where-is-unique-or-parks 100
  (prop/for-all [content gen/string-alphanumeric
                 needle (gen/such-that #(pos? (.length ^String %))
                                       gen/string-alphanumeric)]
    (let [spans (brute-overlapping-spans content needle)
          result (where content needle)]
      (cond
        (= 1 (count spans))
        (= (first spans) result)

        (empty? spans)
        (and (map? result)
             (= :parked (:state result))
             (= [] (get-in result [:payload :candidates])))

        :else
        (and (map? result)
             (= :parked (:state result))
             (= (min 8 (count spans))
                (count (get-in result [:payload :candidates])))
             (= (take 8 spans)
                (map :span (get-in result [:payload :candidates]))))))))
