(ns hc.client.render-test
  "Contract tests for the message render enrichment module.

  H-07: fenced Mermaid blocks and display math must surface as labelled,
  copyable degradation blocks instead of anonymous raw text."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [hc.client.render :as render]))

(deftest mermaid-classification
  (testing "a closed mermaid fence is :mermaid"
    (is (= :mermaid
           (render/block-kind {:type :code :lang "mermaid"
                               :content "graph TD; A-->B" :closed? true}))))
  (testing "language tag is case-insensitive and trimmed"
    (is (= :mermaid
           (render/block-kind {:type :code :lang "  Mermaid "
                               :content "x" :closed? true}))))
  (testing "other languages stay :code"
    (is (= :code
           (render/block-kind {:type :code :lang "clojure"
                               :content "(+ 1 2)" :closed? true})))
    (is (= :code
           (render/block-kind {:type :code :lang ""
                               :content "plain" :closed? true}))))
  (testing "an unclosed mermaid fence still renders as code, not diagram"
    (is (= :code
           (render/block-kind {:type :code :lang "mermaid"
                               :content "graph TD" :closed? false})))))

(deftest math-classification
  (testing "closed math segment is :math"
    (is (= :math
           (render/block-kind {:type :math :content "x^2" :closed? true}))))
  (testing "text/table stay their own kinds"
    (is (= :text (render/block-kind {:type :text :content "hi"})))
    (is (= :table (render/block-kind {:type :table
                                      :content "| a |" :closed? true}))))
  (testing "unknown segment shapes classify as nil"
    (is (nil? (render/block-kind {:type :weird})))
    (is (nil? (render/block-kind nil)))))

(deftest labels-and-copy-text
  (testing "enriched kinds carry reader-facing labels"
    (is (string? (render/block-label :mermaid)))
    (is (str/includes? (render/block-label :mermaid) "源码"))
    (is (str/includes? (render/block-label :math) "源码"))
    (is (nil? (render/block-label :code))))
  (testing "copy labels name the source"
    (is (str/includes? (render/copy-label :mermaid) "图表"))
    (is (str/includes? (render/copy-label :math) "公式"))))

(deftest inline-math-detection
  (testing "simple and multi-char formulas match"
    (is (render/inline-math? "see $x^2$ here"))
    (is (render/inline-math? "$\\frac{1}{2}$"))
    (is (= ["x^2" "\\alpha+1"]
           (render/inline-math-spans "a $x^2$ b $\\alpha+1$ c"))))
  (testing "currency-like pairs do not match"
    (is (not (render/inline-math? "costs $5 and $6 today")))
    (is (not (render/inline-math? "$ $")))
    (is (not (render/inline-math? "no math at all"))))
  (testing "blank and non-string input is safe"
    (is (not (render/inline-math? nil)))
    (is (not (render/inline-math? "")))))
