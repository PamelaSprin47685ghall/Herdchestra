(ns hc.server.backup.cli-test
  "Offline CLI option mapping and default policy.

  These tests pin the command-line contract that the release audit found
  broken: boolean options parse in both spellings, a new backup is encrypted
  by default, and an offline restore into a fresh target asserts the
  no-prior-commands fact that lets the storage fence build a legal window."
  (:require [clojure.test :refer [deftest is testing]]
            [hc.server.backup.cli :as cli]
            [hc.server.backup.core :as core]
            [hc.server.backup.offline :as offline])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- parse
  [args]
  (let [parsed (#'cli/parse-args args)]
    {:command (:command parsed)
     :options (#'cli/options (:options parsed))}))

(defn- password-file
  []
  (let [path (Files/createTempFile "hc-cli-password-" ".txt"
                                   (make-array FileAttribute 0))]
    (spit (str path) "correct horse battery staple")
    (str path)))

(deftest boolean-options-parse-in-both-spellings
  (testing "--encrypt accepts a separate value and an inline value"
    (doseq [args [["backup" "--encrypt" "true"]
                  ["backup" "--encrypt=true"]]]
      (is (= {:command :backup :options {:encrypt? true}}
             (parse args)))))
  (is (= {:command :backup :options {:encrypt? false}}
         (parse ["backup" "--encrypt" "false"])))
  (is (= {:command :backup :options {:include-secrets? true}}
         (parse ["backup" "--include-secrets" "true"])))
  (is (= {:command :backup :options {:include-deleted? true}}
         (parse ["backup" "--include-deleted" "true"])))
  (is (= {:command :restore :options {:no-prior-app-commands? true}}
         (parse ["restore" "--no-prior-app-commands" "true"])))
  (testing "a non-boolean value is a clean argument error"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"expected true or false"
                          (parse ["backup" "--encrypt" "yes"])))))

(deftest backup-run-requires-a-password-and-passes-encryption
  (let [captured (atom nil)
        file (password-file)]
    (try
      (with-redefs [offline/backup! (fn [opts]
                                      (reset! captured opts)
                                      {:status :complete})]
        (testing "the default is encrypted, so a password is required"
          (is (true? (:ok? (cli/run :backup {:db-path "/tmp/db"
                                             :data-dir "/tmp/data"
                                             :password-file file}))))
          (is (= "correct horse battery staple"
                 (:password @captured)))
          (is (not (contains? @captured :encrypt?))
              "an absent flag stays absent; the core policy defaults true"))
        (testing "an explicit true is passed through"
          (is (true? (:ok? (cli/run :backup {:db-path "/tmp/db"
                                             :data-dir "/tmp/data"
                                             :password-file file
                                             :encrypt true}))))
          (is (true? (:encrypt? @captured))))
        (testing "the deleted-session scope is a boolean option"
          (is (true? (:ok? (cli/run :backup {:db-path "/tmp/db"
                                             :data-dir "/tmp/data"
                                             :password-file file
                                             :include-deleted true}))))
          (is (true? (:include-deleted? @captured))))
        (testing "an explicit false is the only way to skip encryption"
          (reset! captured nil)
          (is (true? (:ok? (cli/run :backup {:db-path "/tmp/db"
                                             :data-dir "/tmp/data"
                                             :encrypt false}))))
          (is (false? (:encrypt? @captured)))
          (is (not (contains? @captured :password)))))
      (finally (Files/deleteIfExists (java.nio.file.Path/of file
                                                           (make-array String 0)))))))

(deftest a-new-backup-defaults-to-encrypted
  (let [normalized #'core/normalize-create-options
        categories [:conversations]]
    (is (true? (:encrypt? (normalized {:password "correct horse battery staple"
                                       :categories categories}))))
    (is (true? (:encrypt? (normalized {:password "correct horse battery staple"
                                       :categories categories
                                       :encrypt? nil}))))
    (is (false? (:encrypt? (normalized {:categories categories
                                        :encrypt? false}))))
    (testing "an encrypted backup still enforces the password policy"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"password"
                            (normalized {:categories categories}))))))

(deftest restore-establishes-the-application-window
  (let [captured (atom nil)
        file (password-file)]
    (try
      (with-redefs [offline/restore! (fn [opts]
                                       (reset! captured opts)
                                       {:status :complete})]
        (testing "a CLI restore asserts no prior app commands by default"
          (is (true? (:ok? (cli/run :restore {:db-path "/tmp/db"
                                              :data-dir "/tmp/data"
                                              :backup-id "b1"
                                              :password-file file}))))
          (is (true? (:no-prior-app-commands? @captured))))
        (testing "an operator can still require an existing gate"
          (is (true? (:ok? (cli/run :restore {:db-path "/tmp/db"
                                              :data-dir "/tmp/data"
                                              :backup-id "b1"
                                              :password-file file
                                              :no-prior-app-commands false}))))
          (is (false? (:no-prior-app-commands? @captured))))
        (testing "a programmatic caller keeps the offline key"
          (is (true? (:ok? (cli/run :restore {:db-path "/tmp/db"
                                              :data-dir "/tmp/data"
                                              :backup-id "b1"
                                              :password-file file
                                              :no-prior-app-commands? false}))))
          (is (false? (:no-prior-app-commands? @captured))))
        (testing "the password reaches the offline restore"
          (is (= "correct horse battery staple" (:password @captured)))))
      (finally (Files/deleteIfExists (java.nio.file.Path/of file
                                                           (make-array String 0)))))))
