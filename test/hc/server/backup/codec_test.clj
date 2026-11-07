(ns hc.server.backup.codec-test
  (:require [clojure.test :refer [deftest is testing]]
            [hc.server.backup.codec :as codec])
  (:import (java.io ByteArrayInputStream)))

(deftest canonical-edn-is-order-independent
  (let [a {:b 2 :a 1 :c [{:z 1 :y 2}]}
        b {:c [{:y 2 :z 1}] :a 1 :b 2}]
    (is (= (codec/canonical-string a)
           (codec/canonical-string b)))
    (is (= (seq (codec/canonical-bytes a))
           (seq (codec/canonical-bytes b))))))

(deftest canonical-edn-rejects-unprintable-values
  (is (= :BACKUP_FORMAT_INVALID
         (try
           (codec/canonical-bytes (Object.))
           ::no-throw
           (catch clojure.lang.ExceptionInfo e
             (:error (ex-data e)))))))

(deftest container-roundtrip
  (let [header (codec/canonical-bytes {:format :test :version 1})
        payload (codec/utf8-bytes "payload")
        bytes (codec/encode-container header payload)
        parsed (codec/read-container bytes)]
    (is (codec/container? bytes))
    (is (= codec/container-version (:container-version parsed)))
    (is (= (seq header) (seq (:header-bytes parsed))))
    (is (= (seq payload) (seq (:payload parsed))))))

(deftest header-reader-stops-before-payload
  (let [header (codec/canonical-bytes {:format :test})
        payload (codec/utf8-bytes "payload")
        bytes (codec/encode-container header payload)]
    (with-open [in (ByteArrayInputStream. bytes)]
      (let [parsed (codec/read-container-header in)]
        (is (= (seq header) (seq (:header-bytes parsed))))
        (is (= "payload" (String. (.readAllBytes in) "UTF-8")))))))

(deftest bad-magic-is-refused
  (is (= :BACKUP_FORMAT_INVALID
         (try
           (codec/read-container (codec/utf8-bytes "NOPE1234"))
           ::no-throw
           (catch clojure.lang.ExceptionInfo e
             (:error (ex-data e)))))))

(deftest base64-roundtrip
  (let [bytes (byte-array [0 1 2 127 -1])]
    (is (= (seq bytes)
           (seq (codec/base64-decode (codec/base64-encode bytes)))))))
