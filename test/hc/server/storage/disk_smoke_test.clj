(ns hc.server.storage.disk-smoke-test
  "Real-disk, cross-process durability smoke.

  Each step starts a separate JVM.  The first writes, the second reopens and
  writes, the parent verifies both values, and a final child proves that the
  single-writer lock is visible across processes.  This is the evidence that
  a commit survives a process exit, not just a `close`/`open` in one JVM."

  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hc.test-jvm :as test-jvm]
            [hc.server.storage :as storage]
            [hc.server.storage.support :as support]))

(defn- run-child [& args]
  (apply test-jvm/sh "hc.server.storage.disk-smoke" args))

(defn- ok? [result]
  (and (zero? (:exit result))
       (str/includes? (:out result) "OK")))

(deftest cross-process-durability-test
  (let [dir (support/temp-dir "hc-disk-smoke-")]
    (try
      (testing "first process creates the store and commits"
        (is (ok? (run-child "write" dir "alpha" "one"))))

      (testing "second process reopens, verifies and commits again"
        (is (ok? (run-child "read" dir "alpha" "one"))))

      (testing "parent process verifies both commits"
        (let [store (storage/open-store {:path dir})]
          (try
            (let [docs (get-in (storage/read-session-view store {:partition "smoke"})
                               [:records :smoke])]
              (is (= {"alpha" "one" "alpha-seen" "one"}
                     (into {} (map (juxt :storage/id :value) docs)))))
            (finally (storage/close-store! store)))))

      (testing "another process cannot open while the parent holds the lock"
        (let [store (storage/open-store {:path dir})]
          (try
            (let [result (run-child "lock-check" dir)]
              (is (zero? (:exit result)))
              (is (str/includes? (:out result) "LOCKED")))
            (finally (storage/close-store! store)))))

      (finally (support/delete-tree! dir)))))
