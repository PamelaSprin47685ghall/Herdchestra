(ns hc.server.program.workspace-conflict-stamp-test
  "E2E 09's conflict receipt must carry the original and current stamp.

  `hc.compose.workspace/session` returns the verifier verdict as `:detail`
  on `:FILE_CONFLICT`.  This test pins the verifier half: a real file changed
  between read and settle yields `:stamp-mismatch` with both stamps, and a
  fresh read passes."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [hc.core.workspace-gate :as wg]
            [hc.server.storage.support :as support]))

(deftest a-conflict-receipt-carries-both-stamps
  (let [root (support/temp-dir "hc-e2e09-stamp-")]
    (try
      (spit (io/file root "e2e09.txt") "original\n")
      (let [old (wg/current-stamp root "e2e09.txt")
            _ (Thread/sleep 5)
            _ (spit (io/file root "e2e09.txt") "external\n")
            fresh (wg/current-stamp root "e2e09.txt")
            verdict (wg/verify-read-set
                     root [{:path "e2e09.txt" :stamp old}])
            conflict (first (:conflicts verdict))]
        (testing "the old patch is refused before any write"
          (is (false? (:ok? verdict)))
          (is (= :FILE_CONFLICT (:reason verdict))))
        (testing "the receipt shows the original and current stamp"
          (is (= :stamp-mismatch (:reason conflict)))
          (is (= old (:expected conflict)))
          (is (= fresh (:actual conflict)))
          (is (not= old fresh))
          (is (= "external\n" (slurp (io/file root "e2e09.txt")))))
        (testing "a re-read of the current stamp passes"
          (is (:ok? (wg/verify-read-set
                     root [{:path "e2e09.txt" :stamp fresh}])))))
      (finally
        (support/delete-tree! root)))))
