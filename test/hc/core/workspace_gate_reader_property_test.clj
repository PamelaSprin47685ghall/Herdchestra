(ns hc.core.workspace-gate-reader-property-test
  "Property tests for lock-free reading: a stamp change between the two
  metadata samples is marked :changed-during-read and cannot become a
  stable commit basis."
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.core.workspace-gate.reader :as reader]
            [hc.core.workspace-gate.stamp :as stamp]
            [hc.core.workspace-gate.test-support :as ts]))

(defspec changed-stamp-is-not-a-stable-basis 50
  (prop/for-all [content (gen/such-that seq gen/string-alphanumeric 100)]
    (ts/with-root [root]
      (ts/write! root "f" content)
      (with-redefs [stamp/current-stamp
                    (fn [_] {:mtime-sec 0 :mtime-nsec 0 :size 0})]
        (let [read (reader/read-bytes root "f" {})
              text (reader/read-text root "f" {})]
          (and (true? (:changed-during-read? read))
               (= :FILE_CONFLICT (:status text))
               (not (contains? read :text))))))))
