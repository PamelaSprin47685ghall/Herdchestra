(ns hc.server.documents.pages-test
  (:require [clojure.test :refer [deftest is testing]]
            [hc.server.documents.pages :as pages]))

(deftest parse-explicit-page-ranges
  (is (= {:ok? true :pages [2 3 4 5] :all? false}
         (pages/parse-range "2-5")))
  (is (= [2 3 4 5] (:pages (pages/parse-range "第 2–5 页"))))
  (is (= [1 3 5] (:pages (pages/parse-range "1,3,5"))))
  (is (= [1 3 5] (:pages (pages/parse-range "1 3 5"))))
  (is (= [2] (:pages (pages/parse-range "2-2"))))
  (is (= {:ok? true :pages nil :all? true} (pages/parse-range nil)))
  (is (= {:ok? true :pages nil :all? true} (pages/parse-range "   "))))

(deftest page-numbers-are-one-based
  (doseq [selection ["0" "0-2" "-1" "2-0"]]
    (testing selection
      (let [result (pages/parse-range selection)]
        (is (false? (:ok? result)))
        (is (= :PAGE_RANGE (get-in result [:error :code]))))))
  (let [result (pages/parse-range "5-2")]
    (is (false? (:ok? result)))
    (is (= :PAGE_RANGE (get-in result [:error :code])))))

(deftest duplicate-and-unordered-pages-normalize
  (is (= [1 2 3] (:pages (pages/parse-range [3 1 2 1 3]))))
  (is (= [1 2] (:pages (pages/parse-range [2 1])))))

(deftest validate-against-real-page-count
  (is (nil? (pages/validate nil 3)))
  (is (nil? (pages/validate [1 3] 3)))
  (let [result (pages/validate [1 4] 3)]
    (is (false? (:ok? result)))
    (is (= [4] (get-in result [:error :invalid])))
    (is (= 3 (get-in result [:error :page-count]))))
  (is (false? (:ok? (pages/validate nil -1)))))

(deftest selected-pages-keep-original-numbers
  (is (= [1 2 3] (pages/selected {:pages nil} 3)))
  (is (= [2 5] (pages/selected {:pages [2 5]} 10)))
  (is (= {:source {:id "doc-1" :name "a.pdf" :mime "application/pdf"}
          :page 4}
         (pages/page-ref {:id "doc-1" :name "a.pdf" :mime "application/pdf"}
                         4))))

(deftest max-page-expansion-is-bounded
  (let [result (pages/parse-range "1-100" {:max-pages 10})]
    (is (false? (:ok? result)))
    (is (= :PAGE_RANGE (get-in result [:error :code]))))
  (doseq [selection ["999999999999999999999"
                     "1-999999999999999999999"]]
    (testing selection
      (let [result (pages/parse-range selection)]
        (is (false? (:ok? result)))
        (is (= :PAGE_RANGE (get-in result [:error :code])))))))
