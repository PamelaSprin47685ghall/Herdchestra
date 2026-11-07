(ns hc.server.recovery.restart-smoke-test
  "Real restart smoke for recovery.

  Three separate JVMs share one store: seed, recover, verify.  The middle JVM
  opens through `recovery-entry` (integrity scan included), runs
  `recover-all!` across every partition and both session scopes, and the last
  JVM proves the phases survived a second process exit."

  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hc.test-jvm :as test-jvm]
            [hc.server.recovery :as recovery]
            [hc.server.storage :as storage]
            [hc.server.storage.engine.datalevin :as engine]
            [hc.server.storage.keys :as keys]
            [hc.server.storage.support :as support]))

(defn- run-child [mode dir]
  (test-jvm/sh "hc.server.recovery.restart-smoke" mode dir))

(defn- ok? [result]
  (and (zero? (:exit result))
       (str/includes? (:out result) "OK")))

(deftest restart-recovery-test
  (let [dir (support/temp-dir "hc-restart-smoke-")]
    (try
      (testing "seed process commits obligations in several scopes and exits"
        (is (ok? (run-child "seed" dir))))

      (testing "recover process opens, scans every partition and commits"
        (is (ok? (run-child "recover" dir))))

      (testing "verify process reopens after a second exit and sees final state"
        (is (ok? (run-child "verify" dir))))

      (testing "the parent process sees the same durable state"
        (let [store (storage/open-store {:path dir :verify-integrity? true})]
          (try
            (let [all (->> (storage/list-open-obligations
                            store {:all-partitions? true :open-only? false})
                           :obligations
                           (into {} (map (juxt :obligation/id identity))))]
              (is (= :outcome-unknown (:phase (get all "disp"))))
              (is (false? (:open? (get all "started"))))
              (is (= :report-accepted (:phase (get all "report-ok"))))
              (is (= :report-pending (:phase (get all "report-pending"))))
              (is (= :outcome-unknown (:phase (get all "master-run"))))
              (is (= :retry-wait (:phase (get all "retry-exhausted"))))
              (is (true? (:open? (get all "retry-exhausted"))))
              (is (= :retry-wait (:phase (get all "retry-future"))))
              (is (true? (:open? (get all "retry-future"))))
              (is (= "restart"
                     (get-in (storage/read-session-view
                              store {:partition "session/s1" :session-id "s1"})
                             [:session :title]))))
            (finally (storage/close-store! store)))))
      (finally (support/delete-tree! dir)))))

(deftest recovery-entry-blocks-a-corrupt-store-test
  (let [dir (support/temp-dir "hc-restart-corrupt-")]
    (try
      (let [store (storage/open-store {:path dir :create? true})]
        (storage/commit-migration! store {:migration-id "seed" :partition "p"
                                          :put [{:kind :task :id "t" :doc {}}]})
        ;; Corrupt one envelope through the engine so the integrity scan must
        ;; catch it.  The store is not replaced afterwards.
        (engine/transact!
         (:engine store)
         [[:put (keys/doc-key "p" nil :task "bad")
           {:storage/kind :wrong
            :storage/id "bad"
            :storage/partition "p"
            :storage/session-id nil
            :storage/version 1
            :storage/updated-at "x"
            :doc {}}]])
        (storage/close-store! store))
      (let [entry (recovery/recovery-entry {:path dir})]
        (is (= :blocked (:status entry)))
        (is (= :STORAGE_CORRUPT (get-in entry [:error :error]))))
      (finally (support/delete-tree! dir)))))
