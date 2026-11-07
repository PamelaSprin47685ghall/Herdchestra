(ns hc.core.workspace-gate.test-support
  "Small filesystem helpers shared by the workspace-gate tests.  Every test
  owns its temporary root and removes it afterwards; user projects are never
  touched."
  (:require [clojure.test :refer [is]])
  (:import (java.nio.charset StandardCharsets)
           (java.nio.file Files FileVisitOption LinkOption Path Paths)
           (java.nio.file.attribute FileAttribute)))

(defn temp-root []
  (str (Files/createTempDirectory "hc-wg-" (make-array FileAttribute 0))))

(defn delete-tree [root]
  (let [p (Paths/get (str root) (make-array String 0))]
    (when (Files/exists p (make-array LinkOption 0))
      (with-open [stream (Files/walk p (make-array FileVisitOption 0))]
        (doseq [^Path child (reverse (vec (iterator-seq (.iterator stream))))]
          (Files/deleteIfExists child))))))

(defmacro with-root [[binding] & body]
  `(let [~binding (temp-root)]
     (try
       ~@body
       (finally
         (delete-tree ~binding)))))

(defn file [root name]
  (str (Paths/get (str root) (make-array String 0))
       java.io.File/separator
       name))

(defn write! [root name content]
  (Files/write (Paths/get (file root name) (make-array String 0))
               (.getBytes ^String content StandardCharsets/UTF_8)
               (make-array java.nio.file.OpenOption 0))
  content)

(defn write-bytes! [root name ^bytes bytes]
  (Files/write (Paths/get (file root name) (make-array String 0))
               bytes
               (make-array java.nio.file.OpenOption 0))
  bytes)

(defn read! [root name]
  (String. (Files/readAllBytes (Paths/get (file root name) (make-array String 0)))
           StandardCharsets/UTF_8))

(defn exists? [root name]
  (Files/exists (Paths/get (file root name) (make-array String 0))
                (make-array LinkOption 0)))

(defn ex-data-of [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo e
      (ex-data e))))

(defn thrown-data [f]
  (let [data (ex-data-of f)]
    (is (some? data) "expected an ordinary rejection")
    data))
