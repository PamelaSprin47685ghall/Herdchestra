(ns hc.core.ufss.selection-property-test
  "Property tests for the UFSS fence-selection ladder.

  Roundtripping alone cannot detect a printer that always picks a huge
  fence.  These properties assert the chosen hash count is the smallest one
  that clears the content, using only character-level facts about the input."
  (:refer-clojure :exclude [ensure])
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.string :as str]
            [hc.core.ufss :as ufss]))

(defn- ensure
  [pred message data]
  (when-not pred
    (throw (ex-info message data))))

(defn- hashes
  [n]
  (apply str (repeat n \#)))

(defn- leading-hash-count
  [^String rendered]
  (count (take-while #(= \# %) rendered)))

(defn- capped
  "Bound generated text to 120 characters without dropping its first two."
  [^String text]
  (subs text 0 (min 120 (.length text))))

(def ^:private dangerous-pieces
  ["\"" "\"\"" "\"\"\"" "#" "##" "#\"" "\"#" "\"\"\"#" "\\"])

(def ^:private gen-piece
  (gen/one-of [gen/char-alphanumeric (gen/elements dangerous-pieces)]))

(def ^:private gen-single-line
  ;; The `a` prefix keeps the value out of the documented `"` / `""` block
  ;; fallback; the selected danger piece guarantees a quote or backslash.
  (gen/fmap
   (fn [[danger pieces]]
     (capped (str "a" danger (apply str pieces))))
   (gen/tuple (gen/elements ["\"" "\\" "#\"" "\"\"\""])
              (gen/vector gen-piece 0 60))))

(def ^:private gen-multi-line
  (gen/fmap
   (fn [pieces]
     (capped (str "a\n" (apply str pieces))))
   (gen/vector gen-piece 0 60)))

(def ^:private gen-indented
  (gen/fmap
   (fn [[indent lines]]
     (capped
      (str/join "\n"
                (map (fn [line]
                       (if (empty? line)
                         ""
                         (str (apply str (repeat indent " ")) line)))
                     lines))))
   (gen/tuple (gen/choose 0 8)
              (gen/vector (gen/fmap #(apply str %)
                                    (gen/vector gen-piece 0 8))
                          2 8))))

(def ^:private control-pieces ["\r" "\u0000" "\u0001"])

(def ^:private gen-control
  (gen/fmap
   (fn [[control pieces]]
     (capped (str "a" control (apply str pieces))))
   (gen/tuple (gen/elements control-pieces)
              (gen/vector gen-piece 0 60))))

(defspec single-line-fence-is-minimal 150
  (prop/for-all [s gen-single-line]
    (let [out (ufss/write-str s)
          n (leading-hash-count out)]
      (ensure (= s (ufss/read-str out))
              "single-line roundtrip failed"
              {:s (pr-str s) :out (pr-str out) :n n})
      (ensure (>= n 1)
              "a quote/backslash string cannot use the plain form"
              {:s (pr-str s) :out (pr-str out) :n n})
      (ensure (str/starts-with? out (str (hashes n) "\""))
              "opening fence has the wrong hash count"
              {:s (pr-str s) :out (pr-str out) :n n})
      (ensure (str/ends-with? out (str "\"" (hashes n)))
              "closing fence has the wrong hash count"
              {:s (pr-str s) :out (pr-str out) :n n})
      (if (> n 1)
        (ensure (str/includes? s (str "\"" (hashes (dec n))))
                "n-1 hashes would already terminate the value"
                {:s (pr-str s) :out (pr-str out) :n n})
        (ensure (not (str/includes? s "\"#"))
                "a quote-hash run requires at least n=2"
                {:s (pr-str s) :out (pr-str out) :n n}))
      true)))

(defspec block-fence-is-minimal 150
  (prop/for-all [s gen-multi-line]
    (let [out (ufss/write-str s)
          n (leading-hash-count out)]
      (ensure (= s (ufss/read-str out))
              "block roundtrip failed"
              {:s (pr-str s) :out (pr-str out) :n n})
      (ensure (str/starts-with? out (str (hashes n) "\"\"\""))
              "opening block fence has the wrong hash count"
              {:s (pr-str s) :out (pr-str out) :n n})
      (ensure (str/ends-with? out (str "\"\"\"" (hashes n)))
              "closing block fence has the wrong hash count"
              {:s (pr-str s) :out (pr-str out) :n n})
      (if (zero? n)
        (do
          (ensure (not (str/includes? s "\"\"\""))
                  "n=0 is illegal when the content contains a triple quote"
                  {:s (pr-str s) :out (pr-str out) :n n})
          (ensure (not (str/includes? s "\\"))
                  "n=0 is illegal when the content contains a backslash"
                  {:s (pr-str s) :out (pr-str out) :n n})
          (ensure (not-any? #(str/starts-with? % "\"\"\"")
                            (str/split-lines s))
                  "n=0 is illegal when a line starts with a triple quote"
                  {:s (pr-str s) :out (pr-str out) :n n}))
        (if (str/includes? s "\"\"\"")
          (ensure (str/includes? s (str "\"\"\"" (hashes (dec n))))
                  "n-1 hashes would already close the block early"
                  {:s (pr-str s) :out (pr-str out) :n n})
          (ensure (= 1 n)
                  "a backslash-only block must use the smallest n=1"
                  {:s (pr-str s) :out (pr-str out) :n n})))
      (when (str/includes? s "\\")
        (ensure (>= n 1)
                "a block containing a backslash needs n>=1"
                {:s (pr-str s) :out (pr-str out) :n n}))
      true)))

(defspec indented-block-roundtrips 100
  (prop/for-all [s gen-indented]
    (let [out (ufss/write-str s)
          n (leading-hash-count out)
          closing (last (str/split-lines out))]
      (ensure (= s (ufss/read-str out))
              "indented block roundtrip failed"
              {:s (pr-str s) :out (pr-str out) :n n})
      ;; `write-str` has no indent parameter: the top-level fence has indent
      ;; "".  The closing line must therefore be exactly the bare fence.
      (ensure (= closing (str "\"\"\"" (hashes n)))
              "closing fence carries unexpected indentation"
              {:s (pr-str s) :closing (pr-str closing) :n n})
      (ensure (not (str/starts-with? closing " "))
              "closing fence must start at column zero"
              {:s (pr-str s) :closing (pr-str closing) :n n})
      true)))

(defspec control-text-always-uses-the-escaped-string 150
  (prop/for-all [s gen-control]
    (let [out (ufss/write-str s)]
      (ensure (= s (ufss/read-str out))
              "escaped control text failed to roundtrip"
              {:s (pr-str s) :out (pr-str out)})
      (ensure (str/starts-with? out "\"")
              "control text must use the standard escaped string form"
              {:s (pr-str s) :out (pr-str out)})
      (ensure (not (str/starts-with? out "#"))
              "control text must not use a raw fence"
              {:s (pr-str s) :out (pr-str out)})
      (doseq [[character escape] [["\r" "\\r"]
                                  ["\u0000" "\\u0000"]
                                  ["\u0001" "\\u0001"]]
              :when (str/includes? s character)]
        (ensure (str/includes? out escape)
                (str "missing escape " escape " for a present control")
                {:s (pr-str s) :out (pr-str out) :escape escape}))
      (doseq [raw ["\r" "\u0000" "\u0001"]]
        (ensure (not (str/includes? out raw))
                "raw control character leaked into the rendering"
                {:s (pr-str s) :out (pr-str out)}))
      true)))
