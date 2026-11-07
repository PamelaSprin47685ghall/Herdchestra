(ns hc.server.model-auth.store-test
  (:require [clojure.test :refer [deftest is testing]]
            [hc.server.model-auth.store :as store])
  (:import (java.nio.charset StandardCharsets)
           (java.nio.file Files LinkOption Path)
           (java.nio.file.attribute FileAttribute PosixFilePermissions)))

(defn- temp-dir
  []
  (Files/createTempDirectory "hc-model-auth-store-"
                             (make-array FileAttribute 0)))

(defn- delete-tree!
  [^Path path]
  (when (Files/exists path (make-array LinkOption 0))
    (with-open [stream (Files/walk path (make-array java.nio.file.FileVisitOption 0))]
      (doseq [p (reverse (vec (iterator-seq (.iterator stream))))]
        (try (Files/deleteIfExists p) (catch Exception _ nil))))))

(deftest encrypted-file-store-test
  (let [dir (temp-dir)
        credential {:connection-id "conn-1"
                    :provider-id :local
                    :issuer "http://127.0.0.1:1"
                    :subject "user-1"
                    :account-label "alice@example.test"
                    :account-claims {:email "alice@example.test"}
                    :scopes ["openid"]
                    :access-token "access-secret"
                    :refresh-token "refresh-secret"
                    :token-type :bearer
                    :expires-at-ms 123
                    :obtained-at-ms 100}]
    (try
      (let [store (store/file-store (str dir))]
        (store/write-credential! store "conn-1" credential)
        (is (= "access-secret"
               (:access-token (store/read-credential store "conn-1"))))
        (let [public (store/public-credential
                      (store/read-credential store "conn-1"))]
          (is (= "conn-1" (:connection-id public)))
          (is (= "a***@example.test" (:masked-account public)))
          (is (true? (:has-refresh-token? public)))
          (is (not (contains? public :access-token)))
          (is (not (contains? public :refresh-token))))
        (let [ciphertext (String. (Files/readAllBytes
                                   (.resolve dir "credentials.edn.enc"))
                                  StandardCharsets/ISO_8859_1)
              key-bytes (Files/readAllBytes (.resolve dir "key.bin"))]
          (is (not (clojure.string/includes? ciphertext "access-secret")))
          (is (not (clojure.string/includes? ciphertext "refresh-secret")))
          (is (= 32 (alength key-bytes)))
          (is (= "rw-------"
                 (PosixFilePermissions/toString
                  (Files/getPosixFilePermissions
                   (.resolve dir "credentials.edn.enc")
                   (make-array LinkOption 0)))))
          (is (= "rw-------"
                 (PosixFilePermissions/toString
                  (Files/getPosixFilePermissions
                   (.resolve dir "key.bin")
                   (make-array LinkOption 0))))))
        (store/close-store! store))
      (let [reopened (store/file-store (str dir))]
        (is (= "access-secret"
               (:access-token (store/read-credential reopened "conn-1"))))
        (is (= :deleted (store/delete-credential! reopened "conn-1")))
        (is (nil? (store/read-credential reopened "conn-1")))
        (store/close-store! reopened))
      (finally (delete-tree! dir)))))

(deftest missing-key-fails-closed-test
  (let [dir (temp-dir)]
    (try
      (let [store (store/file-store (str dir))]
        (store/write-credential! store "conn-1" {:access-token "secret"})
        (store/close-store! store))
      (Files/delete (.resolve dir "key.bin"))
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"credential ciphertext exists without its key"
           (store/file-store (str dir))))
      (finally (delete-tree! dir)))))
