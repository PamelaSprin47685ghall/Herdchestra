(ns hc.client.files.diff-test
  (:require [clojure.test :refer [deftest is testing]]
            [hc.client.files.diff :as diff]))

(deftest identical-text-has-no-changes
  (let [result (diff/diff-text "a\nb\n" "a\nb\n")]
    (is (false? (:changed? result)))
    (is (= {:added 0 :removed 0} (:summary result)))
    (is (empty? (:hunks result)))
    (is (false? (:too-large? result)))))

(deftest empty-and-missing-trailing-newline-are-visible
  (testing "empty text has no lines"
    (is (= [] (diff/split-lines ""))))
  (testing "a trailing newline is a real final empty line"
    (is (= ["a" ""] (diff/split-lines "a\n")))
    (let [result (diff/diff-text "a" "a\n")]
      (is (:changed? result))
      (is (= {:added 1 :removed 0} (:summary result))))))

(deftest replace-one-line-keeps-context-and-line-numbers
  (let [result (diff/diff-text "one\ntwo\nthree\n" "one\nTWO\nthree\n")
        lines (mapcat :lines (:hunks result))]
    (is (:changed? result))
    (is (= {:added 1 :removed 1} (:summary result)))
    (is (= [:equal :remove :add :equal :equal] (mapv :op lines)))
    (testing "the removed old line keeps its old number"
      (is (= 2 (:old-line (second lines))))
      (is (nil? (:new-line (second lines)))))
    (testing "the added new line keeps its new number"
      (is (= 2 (:new-line (nth lines 2))))
      (is (nil? (:old-line (nth lines 2)))))
    (testing "the hunk header names both ranges"
      (is (= {:old-start 1 :old-count 4 :new-start 1 :new-count 4}
             (:header (first (:hunks result))))))))

(deftest additions-and-removals-at-the-edges
  (testing "append"
    (let [result (diff/diff-text "a\n" "a\nb\n")
          lines (mapcat :lines (:hunks result))
          added (first (filter #(= :add (:op %)) lines))]
      (is (= {:added 1 :removed 0} (:summary result)))
      (is (= [:equal :add :equal] (mapv :op lines)))
      (is (= 2 (:new-line added)))))
  (testing "delete first line"
    (let [result (diff/diff-text "a\nb\n" "b\n")
          lines (mapcat :lines (:hunks result))]
      (is (= {:added 0 :removed 1} (:summary result)))
      (is (= :remove (:op (first lines))))
      (is (= 1 (:old-line (first lines)))))))

(deftest distant-changes-form-separate-hunks
  (let [old (apply str (map #(str "line-" % "\n") (range 1 31)))
        new (-> old
                (clojure.string/replace "line-2\n" "LINE-2\n")
                (clojure.string/replace "line-28\n" "LINE-28\n"))
        result (diff/diff-text old new)]
    (is (= 2 (count (:hunks result))))
    (is (= 2 (:removed (:summary result))))
    (is (= 2 (:added (:summary result))))))

(deftest large-middle-falls-back-to-coarse-replacement
  (let [old (apply str (map #(str "old-" % "\n") (range 1000)))
        new (apply str (map #(str "new-" % "\n") (range 1000)))
        result (diff/diff-text old new)]
    (is (:changed? result))
    (is (:coarse? result))
    (is (= {:added 1000 :removed 1000} (:summary result)))
    (is (false? (:too-large? result)))))

(deftest huge-diff-is-not-fabricated
  (let [old (apply str (map #(str "old-" % "\n") (range 3000)))
        new (apply str (map #(str "new-" % "\n") (range 3000)))
        result (diff/diff-text old new)]
    (is (:changed? result))
    (is (:too-large? result))
    (is (empty? (:hunks result)))
    (is (= {:added 3000 :removed 3000} (:summary result)))))
