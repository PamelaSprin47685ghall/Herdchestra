(ns hc.core.steward-sphinx.evidence-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.core.steward-sphinx.evidence :as evidence]))

(def gen-path
  (gen/elements ["src" "src/hc" "src/hc/core" "docs" "test"
                 "README.md" "." "src/hc/core/file.clj"]))

(deftest git-evidence-identity
  (let [result (evidence/normalize-evidence
                {:kind :git-commit
                 :commit "abc123"
                 :parents ["parent"]
                 :changed-paths ["src/b.clj" "src/a.clj" "src/a.clj"]
                 :subject "change"})]
    (is (:ok? result))
    (is (= "git:abc123" (get-in result [:value :evidence-id])))
    (is (= ["src/a.clj" "src/b.clj"]
           (get-in result [:value :changed-paths])))
    (is (= :git-commit (get-in result [:value :kind])))))

(deftest git-evidence-rejects-mismatched-id
  (let [result (evidence/normalize-evidence
                {:kind :git-commit
                 :commit "abc"
                 :evidence-id "git:other"
                 :changed-paths []})]
    (is (not (:ok? result)))
    (is (= :INVALID_EVIDENCE (get-in result [:error :code])))))

(deftest non-git-evidence-needs-identity-and-summary
  (is (not (:ok? (evidence/normalize-evidence
                  {:kind :observation :summary "saw something"}))))
  (is (not (:ok? (evidence/normalize-evidence
                  {:kind :observation :evidence-id "obs-1"}))))
  (let [result (evidence/normalize-evidence
                {:kind :observation
                 :evidence-id "obs-1"
                 :text "saw something"
                 :changed-paths ["docs/a.md"]})]
    (is (:ok? result))
    (is (= "saw something" (get-in result [:value :summary])))))

(deftest path-normalization
  (is (= "src/a.clj" (evidence/normalize-path "./src/a.clj")))
  (is (= "src/a.clj" (evidence/normalize-path "/src/a.clj")))
  (is (= "src/a.clj" (evidence/normalize-path "src\\a.clj")))
  (is (= ["a" "b"] (evidence/normalize-paths ["b" "a" "b"])))
  (is (evidence/repo-wide-path? "."))
  (is (evidence/repo-wide-path? ""))
  (is (evidence/path-prefix? "src" "src/hc/core.clj"))
  (is (not (evidence/path-prefix? "src" "srcs/core.clj")))
  (is (evidence/path-prefix? "src/a.clj" "src/a.clj")))

(deftest assumption-impact
  (is (evidence/affects-assumption?
       {:watch-paths ["src"]}
       {:changed-paths ["src/hc/core.clj"]}))
  (is (not (evidence/affects-assumption?
            {:watch-paths ["src"]}
            {:changed-paths ["docs/readme.md"]})))
  (is (evidence/affects-assumption?
       {:watch-paths []}
       {:changed-paths ["anything.clj"]}))
  (is (not (evidence/affects-assumption?
            {:watch-paths ["."]}
            {:changed-paths []}))))

(deftest path-prefix-property
  (is (:result
       (tc/quick-check
        100
        (prop/for-all [base gen-path
                       suffix (gen/not-empty gen/string-alphanumeric)]
          (evidence/path-prefix? base (str base "/" suffix)))))))

(deftest assumption-impact-property
  (is (:result
       (tc/quick-check
        100
        (prop/for-all [watch gen-path
                       changed gen-path]
          (= (or (evidence/repo-wide-path? watch)
                 (evidence/path-prefix? watch changed))
             (evidence/affects-assumption?
              {:watch-paths [watch]}
              {:changed-paths [changed]})))))))
