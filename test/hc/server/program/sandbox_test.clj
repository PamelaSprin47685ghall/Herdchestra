(ns hc.server.program.sandbox-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hc.server.program.sandbox :as sandbox]))

(defn- compile-ok?
  [source]
  (sandbox/compile-program source))

(deftest parses-defprogram
  (let [result (compile-ok? "(defprogram add [] (+ 1 2))")]
    (is (true? (:ok? result)))
    (is (= "add" (:name result)))
    (is (= "(defprogram add [] (+ 1 2))" (:source result)))
    (is (= "1 | (defprogram add [] (+ 1 2))" (:numbered-source result))))
  (testing "multiple body forms and a raw UFSS fence"
    (let [result (compile-ok? "(defprogram raw []\n  #\"a\"b\\c\"#\n  (+ 1 2))")]
      (is (true? (:ok? result)))
      (is (= "raw" (:name result)))
      (is (str/includes? (:numbered-source result) "2 | ")))))

(deftest rejects-malformed-defprogram
  (doseq [source ["(+ 1 2)"
                  "(defprogram)"
                  "(defprogram x [a] a)"
                  "(defprogram \"x\" [] 1)"
                  "(defprogram foo/x [] 1)"
                  "not a form"]]
    (let [result (compile-ok? source)]
      (is (false? (:ok? result)) source)
      (is (contains? #{:INVALID_ARGUMENT :UFSS_READ_ERROR}
                     (get-in result [:error :status]))
          source))))

(deftest reports-ufss-position
  (let [result (compile-ok? "(defprogram x [] \"unterminated)")]
    (is (false? (:ok? result)))
    (is (= :UFSS_READ_ERROR (get-in result [:error :status])))
    (is (number? (get-in result [:error :line])))
    (is (number? (get-in result [:error :column])))))

(deftest macro-expansion-surface
  (doseq [source ["(defprogram x [] (let [a 1] (+ a 2)))"
                  "(defprogram x [] (for [i (range 3)] (* i i)))"
                  "(defprogram x [] (doseq [i (range 3)] i))"
                  "(defprogram x [] (case 1 1 :a :b))"
                  "(defprogram x [] (condp = 1 1 :a :b))"
                  "(defprogram x [] (try (throw (ex-info \"x\" {})) (catch Exception e (ex-data e)) (finally nil)))"
                  "(defprogram x [] (letfn [(f [n] (if (zero? n) 0 (f (dec n))))] (f 3)))"
                  "(defprogram x [] (lazy-seq (list 1 2)))"
                  "(defprogram x [] (assert true))"]]
    (is (true? (:ok? (compile-ok? source))) source)))

(deftest safe-qualified-core-and-string
  (is (true? (:ok? (compile-ok? "(defprogram x [] (clojure.string/replace \"abc\" \"b\" \"x\"))"))))
  (is (true? (:ok? (compile-ok? "(defprogram x [] (clojure.core/update {:a 1} :a inc))"))))
  (is (false? (:ok? (compile-ok? "(defprogram x [] (clojure.core/eval 1))")))))

(deftest refuses-escape-hatches
  (doseq [source ["(defprogram x [] (System/exit 0))"
                  "(defprogram x [] (eval 1))"
                  "(defprogram x [] (read-string \"1\"))"
                  "(defprogram x [] (slurp \"x\"))"
                  "(defprogram x [] (spit \"x\" \"y\"))"
                  "(defprogram x [] (future 1))"
                  "(defprogram x [] (atom 1))"
                  "(defprogram x [] (binding [*out* *out*] 1))"
                  "(defprogram x [] (with-redefs [x 1] x))"
                  "(defprogram x [] (instance? String \"x\"))"
                  "(defprogram x [] (class 1))"
                  "(defprogram x [] (new java.lang.ProcessBuilder [\"x\"]))"
                  "(defprogram x [] (. System exit 0))"
                  "(defprogram x [] (Thread. (fn [] nil)))"
                  "(defprogram x [] (clojure.lang.RT/loadResourceScript \"x\"))"
                  "(defprogram x [] (hc.compose.mission.proof/token))"
                  "(defprogram x [] (clojure.core/requiring-resolve (symbol \"hc.compose.mission.proof/token\")))"
                  "(defprogram x [] (token))"
                  "(defprogram x [] (re-find (re-pattern \"a\") \"a\"))"]]
    (let [result (compile-ok? source)]
      (is (false? (:ok? result)) source)
      (is (= :CAPABILITY_DENIED (get-in result [:error :status])) source))))

(deftest case-and-condp-constructors-are-bounded
  (testing "case without a default may throw IllegalArgumentException"
    (is (true? (:ok? (compile-ok? "(defprogram x [] (case 1 1 :a))")))))
  (testing "a user constructor outside the safe set is refused"
    (is (= :CAPABILITY_DENIED
           (get-in (compile-ok? "(defprogram x [] (new java.lang.StringBuilder \"x\"))")
                   [:error :status])))))

(deftest checkpoints-are-injected
  (let [result (compile-ok? "(defprogram x [] (loop [i 0] (if (< i 3) (recur (inc i)) i)))")
        rendered (pr-str (:fn-form result))]
    (is (true? (:ok? result)))
    (is (str/includes? rendered "checkpoint!"))))

(deftest source-limit
  (let [result (sandbox/compile-program "(defprogram x [] 1)" {:max-source-bytes 4})]
    (is (false? (:ok? result)))
    (is (= :LIMIT_EXCEEDED (get-in result [:error :status])))))
