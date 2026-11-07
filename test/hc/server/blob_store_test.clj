(ns hc.server.blob-store-test
  "Tests for the managed blob store.

  Every test owns a temporary root and never touches a user path.  Property
  tests generate arbitrary bytes; deterministic tests cover the crash-window
  and reference contracts that random generation cannot express."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.server.blob-store :as blob]
            [hc.server.blob-store.content :as content]
            [hc.server.blob-store.layout :as layout]
            [hc.server.blob-store.store :as store])
  (:import (java.io ByteArrayInputStream)
           (java.nio.file Files FileVisitOption LinkOption OpenOption Path StandardOpenOption)
           (java.nio.file.attribute FileAttribute FileTime)
           (java.util Arrays)))

(defn- temp-root
  []
  (str (Files/createTempDirectory "hc-blob-test-" (make-array FileAttribute 0))))

(defn- delete-tree
  [root]
  (let [path (layout/path-of root)]
    (when (Files/exists path (make-array LinkOption 0))
      (with-open [stream (Files/walk path (make-array FileVisitOption 0))]
        (doseq [entry (->> (iterator-seq (.iterator stream))
                           (sort-by #(.getNameCount ^Path %))
                           reverse)]
          (try
            (Files/deleteIfExists entry)
            (catch Exception _ nil)))))))

(defmacro with-root
  [[root] & body]
  `(let [~root (temp-root)]
     (try
       ~@body
       (finally
         (delete-tree ~root)))))

(defn- utf8
  ^bytes [text]
  (.getBytes ^String text "UTF-8"))

(defn- bytes=
  [^bytes a ^bytes b]
  (Arrays/equals a b))

(defn- thrown-code
  [f]
  (try
    (f)
    ::no-throw
    (catch clojure.lang.ExceptionInfo e
      (:error (ex-data e)))))

(defn- set-mtime!
  [path millis]
  (Files/setLastModifiedTime (layout/path-of path) (FileTime/fromMillis millis)))

(defn- old-object!
  [root data]
  (let [metadata (blob/put-bytes! root data)]
    (set-mtime! (layout/object-path root (:id metadata)) 1000000)
    metadata))

(deftest content-id-is-stable-and-canonical
  (let [data (utf8 "hello")
        id (content/id-of-bytes data)]
    (is (= id (content/id-of-bytes (utf8 "hello"))))
    (is (content/valid-id? id))
    (is (= 64 (count (content/id->hex id))))
    (is (not= id (content/id-of-bytes (utf8 "hello!"))))
    (is (= :BLOB_INVALID_ID (thrown-code #(content/assert-id! "sha256-ABC"))))
    (is (= :BLOB_INVALID_ID (thrown-code #(content/id->hex (str "SHA256-" (content/id->hex id))))))
    (is (= :BLOB_INVALID_ID (thrown-code #(content/assert-id! "sha256-../../escape"))))
    (is (= :BLOB_INVALID_DIGEST (thrown-code #(content/id-of-hex "abc"))))))

(deftest put-bytes-finalizes-atomically
  (with-root [root]
    (let [data (utf8 "managed blob")
          metadata (blob/put-bytes! root data)
          path (layout/object-path root (:id metadata))]
      (is (= (:id metadata) (content/id-of-bytes data)))
      (is (= (alength data) (:size metadata)))
      (is (Files/isRegularFile path (make-array LinkOption 0)))
      (is (bytes= data (blob/read-bytes root (:id metadata))))
      (is (empty? (:temp (blob/scan root))))
      (is (empty? (:unrecognized (blob/scan root))))
      (is (= :BLOB_LIMIT_EXCEEDED
             (thrown-code #(blob/read-bytes root (:id metadata) {:max-bytes 1}))))
      (is (= (:id metadata) (:id (blob/verify! root (:id metadata)))))
      (is (true? (:verified? (blob/verify! root (:id metadata)))))
      (is (= (str path) (:path metadata))))))

(deftest staging-writes-combine
  (with-root [root]
    (let [staging (store/stage root)]
      (store/write! staging (utf8 "hello "))
      (store/write-stream! staging (ByteArrayInputStream. (utf8 "world")))
      (let [metadata (store/commit! staging)]
        (is (= "hello world"
               (String. ^bytes (store/read-bytes root (:id metadata)) "UTF-8")))
        (is (= metadata (store/commit! staging)))
        (is (empty? (:temp (store/scan root))))))))

(deftest abort-removes-staging
  (with-root [root]
    (let [data (utf8 "abort me")
          staging (store/stage root)]
      (store/write! staging data)
      (is (= :aborted (store/abort! staging)))
      (is (empty? (:temp (store/scan root))))
      (is (false? (store/exists? root (content/id-of-bytes data))))
      (is (= :aborted (store/abort! staging))))))

(deftest identical-content-deduplicates
  (with-root [root]
    (let [data (utf8 "same bytes")
          first (blob/put-bytes! root data)
          second (blob/put-bytes! root data)]
      (is (= (:id first) (:id second)))
      (is (false? (:deduplicated? first)))
      (is (true? (:deduplicated? second)))
      (is (= 1 (count (:objects (blob/scan root)))))
      (is (empty? (:temp (blob/scan root)))))))

(deftest concurrent-identical-puts-deduplicate
  (with-root [root]
    (let [data (utf8 "racing writers")
          results (doall (pmap (fn [_] (blob/put-bytes! root data)) (range 8)))]
      (is (= 1 (count (set (map :id results)))))
      (is (= 1 (count (:objects (blob/scan root)))))
      (is (empty? (:temp (blob/scan root)))))))

(deftest integrity-failure-is-visible
  (with-root [root]
    (let [metadata (blob/put-bytes! root (utf8 "original"))
          path (layout/object-path root (:id metadata))]
      (Files/write path
                   (utf8 "tampered")
                   (into-array OpenOption [StandardOpenOption/TRUNCATE_EXISTING
                                           StandardOpenOption/WRITE]))
      (is (= :BLOB_INTEGRITY (thrown-code #(blob/verify! root (:id metadata)))))
      (is (= :BLOB_INTEGRITY
             (thrown-code #(blob/read-bytes root (:id metadata) {:verify? true}))))
      (is (bytes= (utf8 "tampered") (blob/read-bytes root (:id metadata)))))))

(deftest missing-and-invalid-objects
  (with-root [root]
    (let [missing (content/id-of-bytes (utf8 "missing"))]
      (is (nil? (blob/metadata root missing)))
      (is (false? (blob/exists? root missing)))
      (is (= :BLOB_NOT_FOUND (thrown-code #(blob/read-bytes root missing))))
      (is (= :BLOB_NOT_FOUND (thrown-code #(blob/open-stream root missing))))
      (is (= :BLOB_INVALID_ID
             (thrown-code #(blob/read-bytes root "sha256-../../escape"))))
      (is (= :BLOB_INVALID_ID (thrown-code #(blob/metadata root nil))))
      (is (= :BLOB_INVALID_ID (thrown-code #(blob/exists? root 42)))))))

(deftest expected-integrity-is-enforced
  (with-root [root]
    (let [data (utf8 "expected")]
      (is (= :BLOB_INTEGRITY
             (thrown-code #(blob/put-bytes! root data
                                            {:expected-id (content/id-of-bytes (utf8 "other"))}))))
      (is (empty? (:objects (blob/scan root))))
      (is (empty? (:temp (blob/scan root))))
      (is (= :BLOB_INTEGRITY
             (thrown-code #(blob/put-bytes! root data {:expected-size 1})))))))

(deftest streams-and-files-round-trip
  (with-root [root]
    (let [data (utf8 "streamed")
          metadata (blob/put-stream! root (ByteArrayInputStream. data))]
      (is (bytes= data (blob/read-bytes root (:id metadata)))))
    (let [data (byte-array (take 200000 (cycle (range 0 256))))
          metadata (blob/put-stream! root (ByteArrayInputStream. data))]
      (is (= 200000 (:size metadata)))
      (is (bytes= data (blob/read-bytes root (:id metadata)))))
    (let [source (Files/createTempFile "hc-blob-source-" ".bin" (make-array FileAttribute 0))]
      (try
        (Files/write source (utf8 "from a file") (make-array OpenOption 0))
        (let [metadata (blob/put-file! root (str source))]
          (is (= "from a file"
                 (String. ^bytes (blob/read-bytes root (:id metadata)) "UTF-8"))))
        (finally
          (Files/deleteIfExists source))))))

(deftest open-stream-reports-size-and-bytes
  (with-root [root]
    (let [metadata (blob/put-bytes! root (utf8 "opened"))
          {:keys [stream size]} (blob/open-stream root (:id metadata))]
      (try
        (is (= (:size metadata) size))
        (is (= "opened" (slurp stream :encoding "UTF-8")))
        (finally
          (.close stream))))))

(deftest cleanup-deletes-old-unreferenced-objects
  (with-root [root]
    (let [old (old-object! root (utf8 "old"))
          young (blob/put-bytes! root (utf8 "young"))]
      (set-mtime! (layout/object-path root (:id young)) 1999500)
      (let [result (blob/cleanup! root {:refs #{}
                                        :safety-window-ms 1000
                                        :now 2000000})]
        (is (= :completed (:outcome result)))
        (is (= [(:id old)] (:deleted result)))
        (is (false? (blob/exists? root (:id old))))
        (is (true? (blob/exists? root (:id young))))
        (is (some #(and (= (:id young) (:id %)) (= :young (:reason %)))
                  (:retained result)))))))

(deftest cleanup-retains-referenced-objects
  (with-root [root]
    (let [metadata (old-object! root (utf8 "referenced"))
          id (:id metadata)]
      (let [result (blob/cleanup! root {:refs #{id}
                                        :safety-window-ms 1000
                                        :now 2000000})]
        (is (= :completed (:outcome result)))
        (is (empty? (:deleted result)))
        (is (true? (blob/exists? root id)))
        (is (some #(and (= id (:id %)) (= :referenced (:reason %)))
                  (:retained result))))
      (let [result (blob/cleanup! root {:referenced? (fn [candidate] (= candidate id))
                                        :safety-window-ms 1000
                                        :now 2000000})]
        (is (empty? (:deleted result)))
        (is (true? (blob/exists? root id))))
      (let [result (blob/cleanup! root {:refs {id {:artifact 1}}
                                        :safety-window-ms 1000
                                        :now 2000000})]
        (is (empty? (:deleted result)))
        (is (true? (blob/exists? root id)))))))

(deftest cleanup-dry-run-does-not-delete
  (with-root [root]
    (let [metadata (old-object! root (utf8 "dry run"))
          id (:id metadata)
          result (blob/cleanup! root {:refs #{}
                                      :safety-window-ms 1000
                                      :now 2000000
                                      :dry-run? true})]
      (is (= :dry-run (:outcome result)))
      (is (= [id] (get-in result [:planned :objects])))
      (is (empty? (:deleted result)))
      (is (true? (blob/exists? root id))))))

(deftest cleanup-requires-a-reference-source
  (with-root [root]
    (let [metadata (old-object! root (utf8 "safe"))
          id (:id metadata)]
      (is (= :BLOB_REFERENCE_SOURCE_REQUIRED
             (thrown-code #(blob/cleanup! root {:safety-window-ms 1000
                                                :now 2000000}))))
      (is (true? (blob/exists? root id)))
      (is (= :BLOB_INVALID_ID
             (thrown-code #(blob/cleanup! root {:refs #{"not-an-id"}
                                                :safety-window-ms 1000
                                                :now 2000000}))))
      (is (true? (blob/exists? root id))))))

(deftest cleanup-reference-failure-deletes-nothing
  (with-root [root]
    (let [a (old-object! root (utf8 "a"))
          b (old-object! root (utf8 "b"))
          result (thrown-code
                  #(blob/cleanup! root {:referenced? (fn [_] (throw (ex-info "boom" {})))
                                        :safety-window-ms 1000
                                        :now 2000000}))]
      (is (= :BLOB_REFERENCE_ERROR result))
      (is (true? (blob/exists? root (:id a))))
      (is (true? (blob/exists? root (:id b)))))))

(deftest cleanup-recheck-failure-retains-the-object
  (with-root [root]
    (let [metadata (old-object! root (utf8 "recheck"))
          id (:id metadata)
          calls (atom 0)
          result (blob/cleanup! root
                                {:referenced? (fn [_]
                                                (if (= 1 (swap! calls inc))
                                                  false
                                                  (throw (ex-info "recheck failed" {}))))
                                 :safety-window-ms 1000
                                 :now 2000000})]
      (is (= :partial (:outcome result)))
      (is (true? (blob/exists? root id)))
      (is (some #(and (= id (:id %)) (= :reference-error (:reason %)))
                (:retained result)))
      (is (some #(= id (:id %)) (:errors result))))))

(deftest cleanup-reclaims-stale-temp-files
  (with-root [root]
    (layout/ensure-root! root)
    (let [stale (Files/createFile (.resolve (layout/temp-dir root) "stale.part")
                                  (make-array FileAttribute 0))
          fresh (Files/createFile (.resolve (layout/temp-dir root) "fresh.part")
                                  (make-array FileAttribute 0))]
      (set-mtime! stale 1000000)
      (set-mtime! fresh 1999500)
      (let [result (blob/cleanup! root {:refs #{}
                                        :safety-window-ms 1000
                                        :now 2000000})]
        (is (= [(str stale)] (:temp-deleted result)))
        (is (false? (Files/exists stale (make-array LinkOption 0))))
        (is (true? (Files/exists fresh (make-array LinkOption 0))))
        (is (some #(and (= (str fresh) (:path %)) (= :young (:reason %)))
                  (:temp-retained result)))))))

(deftest cleanup-can-disable-temp-reclamation
  (with-root [root]
    (layout/ensure-root! root)
    (let [stale (Files/createFile (.resolve (layout/temp-dir root) "stale.part")
                                  (make-array FileAttribute 0))]
      (set-mtime! stale 1000000)
      (let [result (blob/cleanup! root {:refs #{}
                                        :safety-window-ms 1000
                                        :now 2000000
                                        :include-temp? false})]
        (is (empty? (get-in result [:planned :temp])))
        (is (empty? (:temp-deleted result)))
        (is (true? (Files/exists stale (make-array LinkOption 0))))
        (is (some #(= :temp-cleanup-disabled (:reason %))
                  (:temp-retained result)))))))

(deftest cleanup-never-deletes-unrecognized-entries
  (with-root [root]
    (layout/ensure-root! root)
    (let [fanout (.resolve (layout/sha256-dir root) "zz")
          _ (Files/createDirectories fanout (make-array FileAttribute 0))
          path (.resolve fanout "not-a-blob")]
      (Files/write path (utf8 "do not delete") (make-array OpenOption 0))
      (set-mtime! path 1000000)
      (let [result (blob/cleanup! root {:refs #{}
                                        :safety-window-ms 1000
                                        :now 2000000})]
        (is (true? (Files/exists path (make-array LinkOption 0))))
        (is (some #(= (str path) (:path %)) (:unrecognized result)))
        (is (empty? (:deleted result)))))))

(deftest store-lock-returns-the-function-value
  (with-root [root]
    (is (= :locked
           (blob/with-store-lock root (fn [] :locked))))))

(deftest empty-blob-round-trips
  (with-root [root]
    (let [metadata (blob/put-bytes! root (byte-array 0))]
      (is (= 0 (:size metadata)))
      (is (= 0 (alength ^bytes (blob/read-bytes root (:id metadata)))))
      (is (true? (:verified? (blob/verify! root (:id metadata))))))))

(def ^:private byte-array-gen
  (gen/fmap byte-array (gen/vector (gen/choose 0 255) 0 512)))

(defspec content-address-round-trip 40
  (prop/for-all [data byte-array-gen]
    (with-root [root]
      (let [metadata (blob/put-bytes! root data)]
        (and (= (:id metadata) (content/id-of-bytes data))
             (= (alength ^bytes data) (:size metadata))
             (bytes= data (blob/read-bytes root (:id metadata)))
             (= (:id metadata) (:id (blob/verify! root (:id metadata)))))))))
