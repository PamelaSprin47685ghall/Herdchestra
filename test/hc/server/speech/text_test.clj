(ns hc.server.speech.text-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hc.server.speech.text :as text]))

(deftest skips-code-urls-and-large-tables
  (let [prepared (text/prepare
                  (str "Read this.\n\n"
                       "```clojure\n(+ 1 2)\n```\n\n"
                       "See https://example.com/private for details.\n\n"
                       "| a | b |\n|---|---|\n"
                       "| 1 | 2 |\n| 3 | 4 |\n| 5 | 6 |\n| 7 | 8 |\n"))]
    (is (str/includes? (:text prepared) "Read this."))
    (is (not (str/includes? (:text prepared) "(+ 1 2)")))
    (is (not (str/includes? (:text prepared) "https://")))
    (is (not (str/includes? (:text prepared) "| 7 |")))
    (is (str/includes? (:text prepared) "There is a code block."))
    (is (str/includes? (:text prepared) "There is a link."))
    (is (str/includes? (:text prepared) "There is a table."))
    (is (= #{:code-block :url :table}
           (set (map :kind (:skipped prepared)))))))

(deftest small-tables-and-links-stay-readable
  (let [prepared (text/prepare
                  (str "Small table:\n\n"
                       "| name | value |\n|---|---|\n| alpha | 1 |\n\n"
                       "Read [the docs](https://example.com/docs) and "
                       "![a cat](cat.png)."))]
    (is (str/includes? (:text prepared) "name, value"))
    (is (str/includes? (:text prepared) "alpha, 1"))
    (is (str/includes? (:text prepared) "the docs"))
    (is (str/includes? (:text prepared) "a cat"))
    (is (not (str/includes? (:text prepared) "https://")))
    (is (empty? (filter #(= :table (:kind %)) (:skipped prepared))))))

(deftest formulas-do-not-eat-currency
  (let [prepared (text/prepare "It costs $5 and $10, and the identity is $x + y$.")]
    (is (str/includes? (:text prepared) "$5"))
    (is (str/includes? (:text prepared) "$10"))
    (is (str/includes? (:text prepared) "There is a formula."))))

(deftest all-code-text-still-has-a-notice
  (let [prepared (text/prepare "```\nsecret code\n```")]
    (is (= "There is a code block." (:text prepared)))
    (is (= 1 (get-in prepared [:skipped 0 :count])))))

(deftest long-text-is-truncated-with-a-flag
  (let [prepared (text/prepare (str/join " " (repeat 100 "word"))
                               {:max-chars 30})]
    (is (:truncated? prepared))
    (is (<= (count (:text prepared)) 30))))

(deftest skip-flags-can-keep-content
  (let [prepared (text/prepare "```\n(+ 1 2)\n```"
                               {:skip-code? false})]
    (is (str/includes? (:text prepared) "(+ 1 2)"))
    (is (empty? (:skipped prepared)))))
