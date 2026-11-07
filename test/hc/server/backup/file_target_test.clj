(ns hc.server.backup.file-target-test
  (:require [clojure.test :refer [deftest is testing]]
            [hc.server.backup.codec :as codec]
            [hc.server.backup.file-target :as file-target]
            [hc.server.backup.package :as package]
            [hc.server.backup.port :as port]
            [hc.server.backup.support :as support])
  (:import (java.nio.file Files LinkOption Path)
           (java.nio.file.attribute FileAttribute)))

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
  `(let [~sym (str (Files/createTempDirectory "hc-file-target-test-"
                                               (make-array FileAttribute 0)))]
     (try
       ~@body
       (finally
         (delete-tree ~sym)))))

(defn- sealed
  [id & [suffix]]
  (package/seal-payload
   {:backup-id id
    :sampled-at "2026-11-03T00:00:00Z"
    :created-at "2026-11-03T00:00:00Z"
    :device "test"
    :app-version "0.0.1"}
   (codec/canonical-bytes {:format :hc.backup/payload
                                      :format-version 1
                                      :backup-id id
                                      :text (str "payload-" id (or suffix ""))})
   {:encrypt? false}))

(deftest file-target-roundtrip-and-listing
  (with-temp-dir [root]
    (let [target (file-target/store root)
          object (sealed "b-1")
          written (port/put-object! target {:id "b-1" :bytes (:bytes object)})
          listed (port/list-objects target)
          fetched (port/get-object target "b-1")
          verified (port/verify-object target "b-1"
                                       (codec/sha256-hex (:bytes object)))]
      (is (= :verified (:integrity written)))
      (is (false? (:deduplicated? written)))
      (is (= 1 (count listed)))
      (is (= "b-1" (:id (first listed))))
      (is (= :verified (:integrity (first listed))))
      (is (= (seq (:bytes object)) (seq fetched)))
      (is (= :verified (:integrity verified)))
      (testing "identical bytes deduplicate"
        (let [again (port/put-object! target {:id "b-1" :bytes (:bytes object)})]
          (is (true? (:deduplicated? again)))))
      (testing "different bytes under the same ID conflict"
        (let [other (sealed "b-1" "-different")]
          (is (= :BACKUP_OBJECT_CONFLICT
                 (support/thrown-code
                  #(port/put-object! target {:id "b-1"
                                             :bytes (:bytes other)}))))))
      (testing "verify detects corruption"
        (is (= :BACKUP_INTEGRITY
               (support/thrown-code
                #(port/verify-object target "b-1" (str (apply str (repeat 64 "0"))))))))
      (testing "delete is idempotent"
        (port/delete-object! target "b-1")
        (is (nil? (port/get-object target "b-1")))
        (is (= :deleted (port/delete-object! target "b-1")))))))

(deftest file-target-lists-corruption-instead-of-hiding-it
  (with-temp-dir [root]
    (let [target (file-target/store root)
          object (sealed "b-2")]
      (port/put-object! target {:id "b-2" :bytes (:bytes object)})
      (let [path (file-target/object-path root "b-2")
            bytes (Files/readAllBytes path)]
        (aset-byte bytes 20 (byte 0))
        (Files/write path bytes (make-array java.nio.file.OpenOption 0)))
      (let [listed (port/list-objects target)]
        (is (= 1 (count listed)))
        (is (= :invalid (:integrity (first listed))))
        (is (some? (:error (first listed))))))))

(deftest file-target-refuses-unsafe-ids
  (with-temp-dir [root]
    (let [target (file-target/store root)]
      (is (= :BACKUP_ID_INVALID
             (support/thrown-code
              #(port/put-object! target {:id "../escape"
                                         :bytes (codec/utf8-bytes "x")})))))))
