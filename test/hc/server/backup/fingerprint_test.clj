(ns hc.server.backup.fingerprint-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [hc.server.backup :as backup]
            [hc.server.backup.file-recovery :as file-recovery]
            [hc.server.backup.support :as support])
  (:import (clojure.lang ExceptionInfo)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(def ^:private password "correct horse battery staple")

(defn- temp-dir
  []
  (str (Files/createTempDirectory "hc-fingerprint-" (make-array FileAttribute 0))))

(defn- delete-tree!
  [path]
  (doseq [file (reverse (file-seq (io/file path)))]
    (try
      (.delete ^java.io.File file)
      (catch Exception _ nil))))

(defn- error-code
  [f]
  (try
    (f)
    nil
    (catch ExceptionInfo e
      (:error (ex-data e)))))

(deftest preview-fingerprint-is-stable-and-present
  (let [domain (support/domain (support/local-state))
        target (support/target)
        created (backup/backup! domain target
                                {:password password
                                 :categories [:conversations]})
        backup-id (:id created)
        first-preview (backup/preview-restore
                       domain target backup-id
                       {:password password :mode :merge
                        :categories [:conversations]})
        second-preview (backup/preview-restore
                        domain target backup-id
                        {:password password :mode :merge
                         :categories [:conversations]})]
    (is (string? (:fingerprint first-preview)))
    (is (= (:fingerprint first-preview) (:fingerprint second-preview)))
    (is (= (:fingerprint first-preview)
           (backup/preview-fingerprint first-preview)))))

(deftest changed-preview-fingerprint-writes-nothing
  (let [state (support/local-state)
        domain (support/domain state)
        target (support/target)
        created (backup/backup! domain target
                                {:password password
                                 :categories [:conversations]})
        backup-id (:id created)
        preview (backup/preview-restore
                 domain target backup-id
                 {:password password :mode :replace
                  :categories [:conversations]})
        changed (assoc preview :summary
                       (update (:summary preview) :added inc))
        expected (backup/preview-fingerprint changed)
        before @(:state domain)
        result (error-code
                #(backup/restore! domain target
                                  (support/failing-recovery-store)
                                  backup-id
                                  {:password password :mode :replace
                                   :categories [:conversations]
                                   :expected-fingerprint expected
                                   :quiescent? true}))]
    (is (= :BACKUP_PREVIEW_STALE result))
    (is (= before @(:state domain))
        "a stale confirmation must not touch domain state or create a recovery point")))

(deftest matching-fingerprint-restores-and-creates-recovery-point
  (let [root (temp-dir)
        domain (support/domain (support/local-state))
        target (support/target)
        created (backup/backup! domain target
                                {:password password
                                 :categories [:conversations]})
        backup-id (:id created)
        preview (backup/preview-restore
                 domain target backup-id
                 {:password password :mode :replace
                  :categories [:conversations]})
        result (backup/restore! domain target (file-recovery/store root)
                                backup-id
                                {:password password :mode :replace
                                 :categories [:conversations]
                                 :expected-fingerprint (:fingerprint preview)
                                 :quiescent? true})]
    (try
      (is (= :complete (:status result)))
      (is (string? (get-in result [:recovery-point :id])))
      (is (true? (:verified?
                  (some #(when (= (get-in result [:recovery-point :id])
                                  (:id %))
                           {:verified? (= :verified (:integrity %))})
                        (backup/list-recovery-points
                         (file-recovery/store root))))))
      (finally
        (delete-tree! root)))))
