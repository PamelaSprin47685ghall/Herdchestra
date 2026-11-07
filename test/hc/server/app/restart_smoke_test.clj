(ns hc.server.app.restart-smoke-test
  "Real application restart smoke.

  Three child JVMs share one data directory.  The first starts the full
  application, writes state through the app's store and stops.  The second
  starts the same application again; startup recovery must run before the
  gateway and must move stale :running obligations to :outcome-unknown while
  keeping a report-pending obligation open.  The third corrupts one envelope
  and proves the app returns a blocked result with no gateway or URL.  A
  fourth seeds a storage :retry-wait obligation and proves startup blocks
  without rewriting or dropping it.  A fifth injects a session-list read
  error and proves startup blocks without creating a default session.

  The app system test already proves an in-process start/health/stop cycle.
  This smoke proves the process boundary."

  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hc.test-jvm :as test-jvm]
            [hc.server.storage.support :as support]))

(defn- run-child [mode data]
  (test-jvm/sh "hc.server.app.restart-smoke" mode data))

(defn- ok? [result]
  (and (zero? (:exit result))
       (str/includes? (:out result) "OK")))

(deftest app-restart-recovery-and-blocked-entry-test
  (let [data (support/temp-dir "hc-app-restart-")]
    (try
      (testing "first app process seeds state and stops"
        (let [result (run-child "seed" data)]
          (is (ok? result) (str (:err result)))))
      (testing "second app process recovers before accepting work"
        (let [result (run-child "verify" data)]
          (is (ok? result) (str (:err result)))))
      (testing "third app process blocks on a corrupt store"
        (let [result (run-child "blocked" data)]
          (is (ok? result) (str (:err result)))))
      (finally (support/delete-tree! data)))))

(deftest app-start-blocks-on-unclaimed-storage-retry-test
  (let [data (support/temp-dir "hc-app-retry-blocked-")]
    (try
      (is (ok? (run-child "retry-blocked" data)))
      (finally
        (support/delete-tree! data)))))

(deftest app-start-blocks-on-session-list-failure-test
  (let [data (support/temp-dir "hc-app-session-list-blocked-")]
    (try
      (is (ok? (run-child "session-list-blocked" data)))
      (finally
        (support/delete-tree! data)))))
