(ns hc.server.backup.crypto-test
  (:require [clojure.test :refer [deftest is testing]]
            [hc.server.backup.codec :as codec]
            [hc.server.backup.crypto :as crypto]
            [hc.server.backup.error :as err]
            [hc.server.backup.package :as package]
            [hc.server.backup.support :as support]))

(defn- thrown-code
  [f]
  (support/thrown-code f))

(def ^:private header-fields
  {:backup-id "b-1"
   :sampled-at "2026-11-03T00:00:00Z"
   :created-at "2026-11-03T00:00:00Z"
   :device "test-device"
   :app-version "0.0.1"})

(def ^:private fast
  {:kdf-iterations 10000})

(deftest password-policy-accepts-chinese-and-spaces-without-trimming
  (testing "exactly twelve characters is accepted"
    (is (= "abcdefghijkl" (crypto/validate-password "abcdefghijkl"))))
  (testing "eleven characters is refused"
    (is (= :BACKUP_PASSWORD_TOO_SHORT
           (thrown-code #(crypto/validate-password "abcdefghijk")))))
  (testing "Chinese characters count one each"
    (is (= "密码密码密码密码密码密码"
           (crypto/validate-password "密码密码密码密码密码密码")))
    (is (= :BACKUP_PASSWORD_TOO_SHORT
           (thrown-code #(crypto/validate-password "密码密码密码密码密码")))))
  (testing "leading and trailing spaces are preserved"
    (is (= "  abcdefghij  "
           (crypto/validate-password "  abcdefghij  ")))
    (is (= 14 (crypto/password-length "  abcdefghij  "))))
  (testing "all-whitespace is refused"
    (is (= :BACKUP_PASSWORD_BLANK
           (thrown-code #(crypto/validate-password "            "))))
    (is (= :BACKUP_PASSWORD_BLANK
           (thrown-code #(crypto/validate-password "    　    　  ")))))
  (testing "nil and non-string are distinct failures"
    (is (= :BACKUP_PASSWORD_REQUIRED
           (thrown-code #(crypto/validate-password nil))))
    (is (= :BACKUP_PASSWORD_INVALID
           (thrown-code #(crypto/validate-password 123456789012))))))

(deftest password-confirmation-is-exact
  (is (= "correct horse battery staple"
         (crypto/validate-password-confirmation
          "correct horse battery staple"
          "correct horse battery staple")))
  (is (= :BACKUP_PASSWORD_MISMATCH
         (thrown-code #(crypto/validate-password-confirmation
                        "correct horse battery staple"
                        "correct horse battery stapl")))))

(deftest password-is-not-trimmed-when-decrypting
  (let [plaintext (codec/utf8-bytes "payload")
        aad (codec/utf8-bytes "aad")
        encrypted (crypto/encrypt plaintext "  abcdefghij  "
                                  (assoc fast :aad aad))]
    (is (= "payload"
           (String. ^bytes (crypto/decrypt (:ciphertext encrypted)
                                           "  abcdefghij  "
                                           (assoc encrypted :aad aad))
                    "UTF-8")))
    (is (= :BACKUP_WRONG_PASSWORD
           (thrown-code
            #(crypto/decrypt (:ciphertext encrypted) "abcdefghij"
                             (assoc encrypted :aad aad)))))))

(deftest wrong-password-is-not-corruption
  (let [sealed (package/seal-payload header-fields
                                     (codec/utf8-bytes "hello")
                                     (merge fast {:encrypt? true
                                                  :password "correct horse battery staple"}))]
    (is (= :BACKUP_WRONG_PASSWORD
           (thrown-code #(package/open-container (:bytes sealed)
                                                 "wrong horse battery staple"))))
    (is (= :BACKUP_PASSWORD_REQUIRED
           (thrown-code #(package/open-container (:bytes sealed) nil))))
    (let [tampered (aclone ^bytes (:bytes sealed))]
      (aset-byte tampered (dec (alength tampered)) (byte 0))
      (is (= :BACKUP_INTEGRITY
             (thrown-code #(package/open-container tampered
                                                   "correct horse battery staple")))))))

(deftest header-tampering-is-classified
  (let [sealed (package/seal-payload header-fields
                                     (codec/utf8-bytes "hello")
                                     (merge fast {:encrypt? true
                                                  :password "correct horse battery staple"}))
        {:keys [header-bytes payload]} (codec/read-container (:bytes sealed))
        header (codec/edn-read header-bytes)
        reencode (fn [h]
                   (codec/encode-container (codec/canonical-bytes h) payload))]
    (testing "newer format version"
      (is (= :BACKUP_VERSION_UNSUPPORTED
             (thrown-code #(package/open-container
                            (reencode (assoc header :format-version 99))
                            "correct horse battery staple")))))
    (testing "unknown encryption"
      (is (= :BACKUP_ENCRYPTION_UNSUPPORTED
             (thrown-code #(package/open-container
                            (reencode (assoc header :encryption :rot13))
                            "correct horse battery staple")))))
    (testing "header digest mismatch"
      (is (= :BACKUP_INTEGRITY
             (thrown-code #(package/open-container
                            (reencode (assoc header :device "tampered"))
                            "correct horse battery staple")))))
    (testing "payload digest mismatch"
      (let [bad (codec/encode-container header-bytes
                                        (codec/utf8-bytes "not the payload"))]
        (is (= :BACKUP_INTEGRITY
               (thrown-code #(package/open-container
                              bad "correct horse battery staple"))))))))

(deftest plaintext-backup-roundtrip
  (let [sealed (package/seal-payload header-fields
                                     (codec/canonical-bytes {:format :hc.backup/payload
                                      :format-version 1
                                      :backup-id "b-1"
                                      :text "plain"})
                                     {:encrypt? false})
        opened (package/open-container (:bytes sealed) nil)]
    (is (= "plain" (:text (:payload opened))))
    (is (false? (:encrypted? opened)))
    (is (= :none (get-in opened [:header :encryption])))))

(deftest old-iteration-count-decrypts-after-defaults-change
  (let [sealed (package/seal-payload header-fields
                                     (codec/canonical-bytes {:format :hc.backup/payload
                                      :format-version 1
                                      :backup-id "b-1"
                                      :text "old"})
                                     {:encrypt? true
                                      :password "correct horse battery staple"
                                      :kdf-iterations 10000})
        iterations (get-in sealed [:header :kdf :iterations])]
    (is (= 10000 iterations))
    (is (= "old"
           (:text (:payload
                   (package/open-container (:bytes sealed)
                                           "correct horse battery staple")))))))

(deftest chinese-password-roundtrip
  (let [password "密码密码密码密码密码密码"
        sealed (package/seal-payload header-fields
                                     (codec/canonical-bytes
                                      {:format :hc.backup/payload
                                       :format-version 1
                                       :backup-id "b-1"
                                       :text "你好"})
                                     {:encrypt? true
                                      :password password
                                      :kdf-iterations 10000})
        opened (package/open-container (:bytes sealed) password)]
    (is (= "你好" (:text (:payload opened))))
    (is (= :BACKUP_WRONG_PASSWORD
           (thrown-code #(package/open-container (:bytes sealed)
                                                 "密码密码密码密码密码密码 "))))))

(deftest truncated-and-newer-crypto-containers-are-classified
  (let [sealed (package/seal-payload header-fields
                                     (codec/canonical-bytes
                                      {:format :hc.backup/payload
                                       :format-version 1
                                       :backup-id "b-1"})
                                     {:encrypt? true
                                      :password "correct horse battery staple"
                                      :kdf-iterations 10000})
        bytes (:bytes sealed)
        truncated (java.util.Arrays/copyOf ^bytes bytes 7)]
    (is (= :BACKUP_FORMAT_INVALID
           (thrown-code #(package/open-container truncated
                                                 "correct horse battery staple"))))
    (let [{:keys [header-bytes payload]} (codec/read-container bytes)
          header (codec/edn-read header-bytes)
          changed (codec/encode-container
                   (codec/canonical-bytes (assoc header :crypto-version 2))
                   payload)]
      (is (= :BACKUP_ENCRYPTION_UNSUPPORTED
             (thrown-code #(package/open-container changed
                                                   "correct horse battery staple")))))))
