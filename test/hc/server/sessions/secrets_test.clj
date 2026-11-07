(ns hc.server.sessions.secrets-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hc.server.sessions.secrets :as secrets])
  (:import (java.nio.charset StandardCharsets)
           (java.nio.file Files LinkOption)
           (java.nio.file.attribute PosixFilePermission PosixFilePermissions)
           (java.util Base64)))

(defn- temp-dir []
  (str (Files/createTempDirectory "hc-secrets-"
                                  (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- read-text [path]
  (String. (Files/readAllBytes (.toPath (io/file path)))
           StandardCharsets/UTF_8))

(deftest memory-store-round-trip
  (let [store (secrets/memory-secret-store)
        stored (secrets/put-secret! store "cred-1" "sk-secret-value")
        ref (:secret-ref stored)]
    (is (:ok? stored))
    (is (= "secret://cred-1" ref))
    (is (= {:ok? true :secret "sk-secret-value"}
           (secrets/get-secret store ref)))
    (is (secrets/secret-exists? store ref))
    (is (:ok? (secrets/delete-secret! store ref)))
    (is (not (secrets/secret-exists? store ref)))
    (is (= :SECRET_NOT_FOUND (get-in (secrets/get-secret store ref)
                                     [:error :code])))))

(deftest invalid-secret-identifiers-are-refused
  (let [store (secrets/memory-secret-store)]
    (doseq [id ["" "../x" "a/b" nil :x]]
      (let [result (secrets/put-secret! store id "secret")]
        (is (not (:ok? result)) (pr-str id))
        (is (= :SECRET_ID_INVALID (get-in result [:error :code])))))
    (is (= :SECRET_REF_INVALID
           (get-in (secrets/get-secret store "nope") [:error :code])))))

(deftest file-store-encrypts-at-rest-and-survives-reopen
  (let [dir (temp-dir)
        store (secrets/file-secret-store {:directory dir})
        stored (secrets/put-secret! store "cred-1" "sk-super-secret-value")
        ref (:secret-ref stored)
        data-file (io/file dir "secrets.edn")
        key-file (io/file dir "master.key")
        data-text (read-text data-file)
        reopened (secrets/file-secret-store {:directory dir})]
    (is (:ok? stored))
    (is (not (str/includes? data-text "sk-super-secret-value")))
    (is (str/includes? data-text ":ciphertext"))
    (is (= {:ok? true :secret "sk-super-secret-value"}
           (secrets/get-secret reopened ref)))
    (is (pos? (.length data-file)))
    (is (pos? (.length key-file)))
    (testing "POSIX permissions are owner-only"
      (try
        (is (= #{PosixFilePermission/OWNER_READ
                 PosixFilePermission/OWNER_WRITE}
               (Files/getPosixFilePermissions (.toPath data-file)
                                              (make-array LinkOption 0))))
        (catch UnsupportedOperationException _ nil)))
    (is (:ok? (secrets/delete-secret! reopened ref)))
    (is (not (secrets/secret-exists? reopened ref)))))

(deftest file-store-rotates-the-same-reference
  (let [dir (temp-dir)
        store (secrets/file-secret-store {:directory dir})
        first (secrets/put-secret! store "cred-1" "first-secret")
        second (secrets/put-secret! store "cred-1" "second-secret")]
    (is (= (:secret-ref first) (:secret-ref second)))
    (is (= {:ok? true :secret "second-secret"}
           (secrets/get-secret store (:secret-ref second))))))

(deftest a-wrong-master-key-does-not-return-plaintext
  (let [dir (temp-dir)
        store (secrets/file-secret-store {:directory dir})
        ref (:secret-ref (secrets/put-secret! store "cred-1" "hidden-value"))
        wrong (.encodeToString (Base64/getEncoder) (byte-array 32))
        reopened (secrets/file-secret-store {:directory dir :key wrong})]
    (let [result (secrets/get-secret reopened ref)]
      (is (not (:ok? result)))
      (is (= :SECRET_DECRYPT_FAILED (get-in result [:error :code])))
      (is (nil? (:secret result))))))
