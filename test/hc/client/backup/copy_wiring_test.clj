(ns hc.client.backup.copy-wiring-test
  "Tests for backup errors mapped to the shared 24-class copy module."
  (:require [clojure.test :refer [deftest is testing]]
            [hc.client.backup.model :as model]))

(deftest standard-backup-errors-use-shared-copy
  (doseq [[code expected-title expected-command]
          [[:BACKUP_WRONG_PASSWORD "备份密码不正确" :backup/reenter-password]
           [:BACKUP_FORMAT_INVALID "导入格式不支持" :backup/supported-formats]
           [:BACKUP_CONTAINER_INVALID "导入格式不支持" :backup/supported-formats]
           [:BACKUP_OBJECT_CONFLICT "恢复发生冲突" :backup/review-conflicts]
           [:BACKUP_CONFLICT_RESOLUTION_INVALID "恢复发生冲突" :backup/review-conflicts]
           [:BACKUP_VERSION_UNSUPPORTED "版本过新" :app/update]]]
    (testing (name code)
      (let [view (model/error-view {:code code :message "server detail"})]
        (is (re-find (re-pattern (str expected-title "$")) (:title view)))
        (is (= expected-command (get-in view [:copy :primary-action :command])))
        (is (seq (:text view)))
        (is (seq (:action view)))))))

(deftest unknown-backup-code-stays-honest
  (let [view (model/error-view {:code :SOMETHING_NEW :message "unexpected"})]
    (is (= :SOMETHING_NEW (:code view)))
    (is (nil? (:copy view)))
    (is (re-find #"unexpected" (:text view)))))
