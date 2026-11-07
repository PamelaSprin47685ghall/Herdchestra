(ns hc.server.backup.recovery-test
  (:require [clojure.test :refer [deftest is testing]]
            [hc.server.backup.file-recovery :as file-recovery]
            [hc.server.backup.port :as port]
            [hc.server.backup.recovery :as recovery])
  (:import (java.nio.file Files LinkOption Path)
           (java.nio.file.attribute FileAttribute)))

(defn- point
  [id created-at & [extra]]
  (merge {:id id
          :created-at created-at
          :kind :local
          :status :success
          :bytes 100}
         extra))

(defn- delete-tree
  [root]
  (let [path (Path/of (str root) (make-array String 0))]
    (when (Files/exists path (make-array LinkOption 0))
      (with-open [stream (Files/walk path (make-array java.nio.file.FileVisitOption 0))]
        (doseq [entry (->> (iterator-seq (.iterator stream))
                           (sort-by #(.getNameCount ^Path %))
                           reverse)]
          (try (Files/deleteIfExists entry) (catch Exception _ nil)))))))

(defmacro with-temp-dir
  [[sym] & body]
  `(let [~sym (str (Files/createTempDirectory "hc-recovery-test-"
                                               (make-array FileAttribute 0)))]
     (try
       ~@body
       (finally
         (delete-tree ~sym)))))

(deftest retention-keeps-recent-weekly-monthly-and-protected
  (let [points (for [day (range 1 32)]
                 (point (str "rp-" day)
                        (format "2026-10-%02dT00:00:00Z" day)))
        points (conj (vec points)
                     (point "rp-sep" "2026-09-15T00:00:00Z")
                     (point "rp-current" "2026-11-01T00:00:00Z")
                     (point "rp-pinned" "2026-09-01T00:00:00Z" {:pinned? true})
                     (point "rp-failed" "2026-09-02T00:00:00Z" {:status :failed})
                     (point "rp-other" "2026-09-03T00:00:00Z" {:kind :external}))
        plan (recovery/plan-retention points
                                      {:now "2026-11-03T00:00:00Z"
                                       :current-id "rp-current"})]
    (is (contains? (set (:keep-ids plan)) "rp-current"))
    (is (contains? (set (:keep-ids plan)) "rp-pinned"))
    (is (contains? (set (:keep-ids plan)) "rp-failed"))
    (is (contains? (set (:keep-ids plan)) "rp-other"))
    (is (not (contains? (set (:delete-ids plan)) "rp-current")))
    (is (not (contains? (set (:delete-ids plan)) "rp-pinned")))
    (is (not (contains? (set (:delete-ids plan)) "rp-failed")))
    (is (not (contains? (set (:delete-ids plan)) "rp-other")))
    (testing "seven most recent local successful points are kept"
      (is (contains? (set (:keep-ids plan)) "rp-31"))
      (is (contains? (set (:keep-ids plan)) "rp-25")))
    (testing "an old point can still be kept as a monthly representative"
      (is (contains? (set (:keep-ids plan)) "rp-sep")))
    (testing "unknown timestamps are never deleted"
      (let [plan (recovery/plan-retention
                  [(point "unknown" nil)
                   (point "old" "2020-01-01T00:00:00Z")]
                  {:now "2026-11-03T00:00:00Z"})]
        (is (contains? (set (:keep-ids plan)) "unknown"))
        (is (not (contains? (set (:delete-ids plan)) "unknown")))))))

(deftest retention-deletes-only-old-unprotected-successes
  (let [points [(point "new" "2026-11-03T00:00:00Z")
                (point "old-1" "2020-01-01T00:00:00Z")
                (point "old-2" "2020-01-02T00:00:00Z")
                (point "old-3" "2020-01-03T00:00:00Z")]
        plan (recovery/plan-retention points
                                      {:now "2026-11-03T00:00:00Z"
                                       :recent-limit 1 :weeks 0 :months 0})]
    (is (= #{"old-1" "old-2" "old-3"} (set (:delete-ids plan))))
    (is (= #{"new"} (set (:keep-ids plan))))))

(deftest file-recovery-roundtrip-and-corruption
  (with-temp-dir [root]
    (let [store (file-recovery/store root)
          state {:categories {:conversations [{:id "c1"}]}
                 :attachments {}}
          point {:id "rp-1"
                 :created-at "2026-11-03T00:00:00Z"
                 :reason :before-restore
                 :scope [:conversations]
                 :state state}
          written (port/write-recovery-point! store point)
          listed (port/list-recovery-points store)
          read (port/read-recovery-point store "rp-1")]
      (is (= :verified (:integrity written)))
      (is (= 1 (count listed)))
      (is (= "rp-1" (:id (first listed))))
      (is (= state (:state read)))
      (testing "writing the same point is idempotent"
        (let [again (port/write-recovery-point! store point)]
          (is (true? (:deduplicated? again)))))
      (testing "a different point with the same ID conflicts"
        (is (= :BACKUP_RECOVERY_POINT_CONFLICT
               (try
                 (port/write-recovery-point!
                  store (assoc point :state {:categories {}}))
                 ::no-throw
                 (catch clojure.lang.ExceptionInfo e
                   (:error (ex-data e)))))))
      (testing "corruption is listed as invalid, not hidden"
        (let [path (file-recovery/point-path root "rp-1")
              bytes (Files/readAllBytes path)]
          (aset-byte bytes (dec (alength bytes)) (byte 0))
          (Files/write path bytes (make-array java.nio.file.OpenOption 0))
          (let [listed (port/list-recovery-points store)]
            (is (= :invalid (:integrity (first listed)))))))
      (testing "delete is idempotent"
        (port/delete-recovery-point! store "rp-1")
        (is (nil? (port/read-recovery-point store "rp-1")))
        (is (= :deleted (port/delete-recovery-point! store "rp-1")))))))

(deftest retention-failed-points-do-not-consume-success-slots
  (let [success (for [day (range 1 9)]
                  (point (str "s-" day)
                         (format "2026-11-%02dT00:00:00Z" day)))
        failed [(point "f-1" "2026-11-10T00:00:00Z" {:status :failed})
                (point "f-2" "2026-11-09T00:00:00Z" {:status :failed})]
        plan (recovery/plan-retention (vec (concat success failed))
                                      {:now "2026-11-11T00:00:00Z"
                                       :recent-limit 7 :weeks 0 :months 0})]
    (is (contains? (set (:keep-ids plan)) "s-2")
        "the seventh successful point is kept even when failed points are newer")
    (is (not (contains? (set (:keep-ids plan)) "s-1")))
    (is (contains? (set (:delete-ids plan)) "s-1"))
    (is (contains? (set (:keep-ids plan)) "f-1"))
    (is (contains? (set (:keep-ids plan)) "f-2"))))
