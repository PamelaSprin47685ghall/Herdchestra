(ns hc.server.artifacts-test
  "Artifact registration against real temporary stores and files.

  Each test uses its own store and blob root.  No test touches a user
  workspace, and every artifact assertion checks the durable document, the
  blob reference, or the bytes themselves."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hc.client.artifacts :as client-artifacts]
            [hc.server.artifacts :as artifacts]
            [hc.server.artifacts.check :as check]
            [hc.server.artifacts.integration :as integration]
            [hc.server.blob-store :as blob]
            [hc.server.storage :as storage]
            [hc.server.storage.support :as support])
  (:import (java.nio.charset StandardCharsets)
           (java.nio.file Files LinkOption Paths)
           (java.nio.file.attribute FileAttribute)))

(defn- temp-dir [prefix]
  (str (Files/createTempDirectory prefix (make-array FileAttribute 0))))

(defn- write-file! [dir name ^String content]
  (let [file (io/file dir name)]
    (io/make-parents file)
    (spit file content)
    (str file)))

(defn- write-bytes! [dir name ^bytes bytes]
  (let [file (io/file dir name)]
    (io/make-parents file)
    (with-open [out (io/output-stream file)]
      (.write out bytes))
    (str file)))

(defn- error-code [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo e
      (artifacts/error-code e))))

(defn- broken-artifact
  [artifact]
  (assoc artifact :content-id (str "sha256-" (apply str (repeat 64 "0")))))

(defn- blob-error-code [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo e
      (blob/error-code e))))

(defmacro with-artifact-env
  [[store-sym blob-sym svc-sym dir-sym] & body]
  `(let [~dir-sym (temp-dir "hc-artifacts-")
         ~store-sym (storage/open-store {:path (str ~dir-sym "/db")
                                        :create? true})
         ~blob-sym (str ~dir-sym "/blobs")
         ~svc-sym (artifacts/service {:store ~store-sym
                                      :blob-root ~blob-sym})]
     (try
       ~@body
       (finally
         (try (storage/close-store! ~store-sym) (catch Throwable ~'_ nil))
         (support/delete-tree! ~dir-sym)))))

(deftest register-file-and-reopen-test
  (with-artifact-env [store blob-root svc dir]
    (let [file (write-file! dir "报告.txt" "第一行\n第二行\n")
          result (artifacts/register!
                  svc
                  {:session-id "s1"
                   :file file
                   :name "报告.txt"
                   :kind :file
                   :source-task-id "task-1"
                   :source-call-id "call-1"
                   :project-id "project-1"
                   :workspace-root dir
                   :relative-path "报告.txt"
                   :inputs [{:kind :file
                             :path (str dir "/input.csv")
                             :stamp {:mtime-sec 1 :mtime-nsec 2 :size 3}}]})
          artifact (:artifact result)
          content-id (:content-id artifact)]
      (testing "registration returns a complete artifact and announcement event"
        (is (:ok? result))
        (is (= :available (:status artifact)))
        (is (= "报告.txt" (:name artifact)))
        (is (= "text/plain" (:media-type artifact)))
        (is (= 20 (:size artifact)))
        (is (string? (:id artifact)))
        (is (= "task-1" (:source-task-id artifact)))
        (is (= "call-1" (:source-call-id artifact)))
        (is (string? (:source-modified-at artifact)))
        (is (= content-id (get-in artifact [:save-location :content-id])))
        (let [card (client-artifacts/card artifact)]
          (is (= (:id artifact) (:id card)))
          (is (= "报告.txt" (:name card)))
          (is (= content-id (:content-id card)))
          (is (= "/blobs/" (subs (:download-url card) 0 7)))
          (is (some #(= :download (:action %)) (:actions card)))
          (is (some (fn [[label _ _]] (= "来源任务" label))
                    (:provenance card))))
        (is (= "hc/artifact-available" (:name (:announcement result))))
        (is (= content-id (get-in result [:blob :id])))
        (is (blob/exists? blob-root content-id))
        (is (false? (:announced? artifact))
            "registration builds the announcement but does not claim delivery"))
      (testing "the announcement transition is explicit and idempotent"
        (let [first-mark (artifacts/mark-announced!
                          svc {:session-id "s1"
                               :artifact-id (:id artifact)})
              second-mark (artifacts/mark-announced!
                           svc {:session-id "s1"
                                :artifact-id (:id artifact)})]
          (is (true? (:changed? first-mark)))
          (is (true? (:announced? (:artifact first-mark))))
          (is (false? (:changed? second-mark)))
          (is (true? (:announced? (:artifact second-mark))))
          (is (true? (:announced?
                      (artifacts/get-artifact
                       svc {:session-id "s1"
                            :artifact-id (:id artifact)}))))))
      (testing "the document and blob reference are durable"
        (let [view (storage/read-session-view store
                                              {:partition "session/s1"
                                               :session-id "s1"})
              stored (first (get-in view [:records :artifact]))]
          (is (= (:id artifact) (:id stored)))
          (is (= content-id (:content-id stored)))
          (is (= "task-1" (:source-task-id stored)))
          (is (= "报告.txt" (get-in stored [:origin :relative-path])))
          (is (seq (:checks stored))))
        (let [refs (storage/manage-blob-refs! store
                                              {:partition "session/s1"
                                               :op :list})]
          (is (= [content-id] (mapv :id (:refs refs))))
          (is (= [(:id artifact)] (mapv :artifact-id (:refs refs))))))
      (testing "open and download use the immutable blob URL"
        (let [download (artifacts/download svc artifact)]
          (is (:ok? download))
          (is (re-matches
               (re-pattern (str "/blobs/" content-id
                                "\\?filename=[A-Za-z0-9._-]+"))
               (:url download))
              "non-ASCII names fall back to an ASCII-safe filename")
          (is (= "报告.txt" (:filename download)))
          (is (= "text/plain" (:media-type download))))
        (is (= (str "/blobs/" content-id) (:open-url artifact)))
        (is (not (str/includes? (:open-url artifact) "filename="))
            "the open URL must stay inline; the download URL carries the filename")
        (with-open [in (:stream (artifacts/open! svc artifact))]
          (is (= "第一行\n第二行\n"
                 (String. (.readAllBytes in) StandardCharsets/UTF_8))))
        (is (= "第一行\n第二行\n"
               (String. ^bytes (:bytes (artifacts/read-bytes! svc artifact))
                        StandardCharsets/UTF_8))))
      (testing "reopening the store preserves the artifact"
        (storage/close-store! store)
        (let [reopened (storage/open-store {:path (str dir "/db")})]
          (try
            (let [svc2 (artifacts/service {:store reopened
                                           :blob-root blob-root})
                  listed (artifacts/list-artifacts svc2 {:session-id "s1"})]
              (is (:ok? listed))
              (is (= 1 (count (:artifacts listed))))
              (is (= content-id (:content-id (first (:artifacts listed)))))
              (is (= "task-1" (:source-task-id (first (:artifacts listed))))))
            (finally
              (storage/close-store! reopened))))))))

(deftest registration-is-idempotent-test
  (with-artifact-env [store blob-root svc dir]
    (let [file (write-file! dir "a.txt" "alpha")
          request {:session-id "s1"
                   :file file
                   :artifact-id "artifact-a"
                   :migration-id "migration-a"
                   :source-task-id "t1"}
          first-result (artifacts/register! svc request)
          second-result (artifacts/register! svc request)]
      (is (false? (:replayed? first-result)))
      (is (true? (:replayed? second-result)))
      (is (= (:id (:artifact first-result)) (:id (:artifact second-result))))
      (is (= 1 (count (:artifacts (artifacts/list-artifacts
                                   svc {:session-id "s1"})))))
      (testing "the same migration ID with different content is a conflict"
        (let [other (write-file! dir "b.txt" "beta")
              code (error-code
                    #(artifacts/register!
                      svc (assoc request :file other :name "b.txt")))]
          (is (= :ARTIFACT_REGISTRATION_CONFLICT code))))
      (testing "truncation metadata is part of the registration identity"
        (let [code (error-code
                    #(artifacts/register!
                      svc (assoc request :truncated? true)))]
          (is (= :ARTIFACT_REGISTRATION_CONFLICT code)))))))

(deftest truncated-artifact-is-marked-test
  (with-artifact-env [store blob-root svc dir]
    (let [file (write-file! dir "partial.log" "partial bytes")
          result (artifacts/register!
                  svc {:session-id "s1"
                       :file file
                       :name "partial.log"
                       :kind :log
                       :truncated? true
                       :truncated-reason "artifact limit"
                       :source-total-bytes 4096})
          artifact (:artifact result)
          card (client-artifacts/card artifact)]
      (is (:ok? result))
      (is (true? (:truncated? artifact)))
      (is (false? (:complete? artifact)))
      (is (= "artifact limit" (:truncated-reason artifact)))
      (is (= 4096 (:source-total-bytes artifact)))
      (is (true? (:truncated? card)))
      (is (false? (:complete? card)))
      (is (some (fn [[label value _]]
                  (and (= "完整性" label)
                       (= "已截断：仅交付部分字节" value)))
                (:provenance card)))
      (is (= :available (:status (first (:artifacts
                                         (artifacts/list-artifacts
                                          svc {:session-id "s1"})))))))))

(deftest shell-output-helper-preserves-truncation-test
  (with-artifact-env [store blob-root svc dir]
    (let [file (write-file! dir "shell.log" "captured output\n")
          result (artifacts/register-shell-output!
                  svc {:session-id "s1"
                       :source-task-id "t1"
                       :source-call-id "c1"}
                  {:output {:artifact {:path file
                                       :bytes 16
                                       :limit 16
                                       :truncated? true}}})
          artifact (:artifact result)]
      (is (:ok? result))
      (is (= :log (:kind artifact)))
      (is (true? (:truncated? artifact)))
      (is (false? (:complete? artifact)))
      (is (re-find #"shell artifact limit 16 bytes"
                   (str (:truncated-reason artifact))))
      (is (= :ARTIFACT_OUTPUT_UNAVAILABLE
             (error-code
              #(artifacts/register-shell-output!
                svc {:session-id "s1"}
                {:output {:artifact {:error "write failed"}}}))))
      (is (= :ARTIFACT_OUTPUT_UNAVAILABLE
             (error-code
              #(artifacts/register-shell-output!
                svc {:session-id "s1"}
                {:output {:artifact {}}})))))))

(deftest source-refusals-test
  (with-artifact-env [store blob-root svc dir]
    (testing "missing, directory and symlink sources are refused"
      (is (= :ARTIFACT_SOURCE_MISSING
             (error-code #(artifacts/register!
                           svc {:session-id "s1"
                                :file (str dir "/missing.txt")}))))
      (is (= :ARTIFACT_SOURCE_NOT_REGULAR
             (error-code #(artifacts/register!
                           svc {:session-id "s1" :file dir}))))
      (let [target (write-file! dir "target.txt" "target")
            link (str dir "/link.txt")]
        (Files/createSymbolicLink (Paths/get link (make-array String 0))
                                  (Paths/get target (make-array String 0))
                                  (make-array FileAttribute 0))
        (is (= :ARTIFACT_SOURCE_NOT_REGULAR
               (error-code #(artifacts/register!
                             svc {:session-id "s1" :file link}))))
        (is (:ok? (artifacts/register!
                   svc {:session-id "s1" :file link :follow-links? true
                        :name "link.txt"})))))
    (testing "empty and oversized files are refused"
      (let [empty (write-file! dir "empty.txt" "")]
        (is (= :ARTIFACT_EMPTY
               (error-code #(artifacts/register!
                             svc {:session-id "s1" :file empty}))))
        (is (:ok? (artifacts/register!
                   svc {:session-id "s1" :file empty :allow-empty? true
                        :name "empty.txt"}))))
      (let [large (write-file! dir "large.bin" (apply str (repeat 100 "x")))]
        (is (= :ARTIFACT_LIMIT_EXCEEDED
               (error-code #(artifacts/register!
                             svc {:session-id "s1" :file large
                                  :max-bytes 10}))))))
    (testing "declared text that is not UTF-8 is refused"
      (let [bad (write-bytes! dir "bad.txt"
                              (byte-array [0xFF 0xFE 0x00 0x41]))]
        (is (= :ARTIFACT_ENCODING
               (error-code #(artifacts/register!
                             svc {:session-id "s1" :file bad
                                  :media-type "text/plain"}))))))
    (testing "a source changed after observation fails the stability check"
      (let [file (write-file! dir "changing.txt" "one")
            snapshot (check/source-snapshot file {})]
        (spit file "two")
        (let [stable (check/source-stable? snapshot)]
          (is (false? (:ok? stable)))
          (is (= (:stamp snapshot) (:before stable))))))))

(deftest existing-content-id-test
  (with-artifact-env [store blob-root svc dir]
    (let [bytes (.getBytes "log line\n" StandardCharsets/UTF_8)
          metadata (blob/put-bytes! blob-root bytes)
          result (artifacts/register!
                  svc {:session-id "s1"
                       :content-id (:id metadata)
                       :name "run.log"
                       :kind :log
                       :source-task-id "task-2"})
          artifact (:artifact result)]
      (is (:ok? result))
      (is (= (:id metadata) (:content-id artifact)))
      (is (= "text/plain" (:media-type artifact)))
      (is (= :available (:status artifact)))
      (is (= "log line\n"
             (String. ^bytes (:bytes (artifacts/read-bytes! svc artifact))
                      StandardCharsets/UTF_8)))
      (is (= :ARTIFACT_BLOB_MISSING
             (error-code
              #(artifacts/register!
                svc {:session-id "s1"
                     :content-id (str "sha256-" (apply str (repeat 64 "a")))
                     :name "missing.bin"}))))
      (testing "available metadata without a canonical ID is not presented as delivered"
        (let [public (artifacts/public-artifact
                      {:id "broken" :name "broken.bin"
                       :status :available :content-id "not-a-content-id"})]
          (is (= :incomplete (:status public)))
          (is (nil? (:download-url public)))
          (is (false? (:downloadable? public)))
          (is (= "缺少规范内容 ID" (:missing-reason public))))))))

(deftest verification-and-status-test
  (with-artifact-env [store blob-root svc dir]
    (let [file (write-file! dir "gone.txt" "gone")
          artifact (:artifact (artifacts/register!
                               svc {:session-id "s1" :file file
                                    :name "gone.txt"}))
          content-id (:content-id artifact)]
      (is (:ok? (artifacts/verify! svc artifact)))
      (is (= blob-root (:blob-root svc)))
      (is (true? (blob/exists? blob-root content-id)))
      (blob/delete! blob-root content-id)
      (is (false? (blob/exists? blob-root content-id)))
      (is (nil? (blob/metadata blob-root content-id)))
      (is (nil? (blob/metadata (:blob-root svc) content-id)))
      (is (= :BLOB_NOT_FOUND
             (blob-error-code #(blob/verify! (:blob-root svc) content-id))))
      (is (= content-id
             (:content-id (artifacts/resolve-artifact svc artifact)))
          (pr-str {:input (:content-id artifact)
                   :resolved (:content-id (artifacts/resolve-artifact
                                           svc artifact))}))
      (is (= :missing
             (:status (artifacts/annotate-artifact svc artifact))))
      (is (= :ARTIFACT_BLOB_MISSING
             (error-code #(artifacts/download svc artifact))))
      (is (= :ARTIFACT_BLOB_MISSING
             (error-code
              #(artifacts/check-blob (:blob-root svc)
                                     content-id
                                     {}))))
      (is (false? (:ok? (artifacts/verify! svc (broken-artifact artifact)))))
      (testing "a snapshot does not advertise bytes that are gone"
        (let [listed (first (:artifacts (artifacts/list-artifacts
                                        svc {:session-id "s1"})))]
          (is (= content-id (:content-id listed)))
          (is (= :missing (:status listed)))
          (is (false? (:blob-present? listed)))
          (is (nil? (:download-url listed)))
          (is (false? (:downloadable? listed)))))
      (testing "re-registering the same metadata cannot claim success"
        (is (= :ARTIFACT_NOT_AVAILABLE
               (error-code
                #(artifacts/register!
                  svc {:session-id "s1"
                       :artifact-id (:id artifact)
                       :file file
                       :name "gone.txt"}))))
        (is (= :ARTIFACT_BLOB_MISSING
               (error-code
                #(artifacts/mark-announced!
                  svc {:session-id "s1" :artifact-id (:id artifact)})))))
      (testing "verification reports the missing object"
        (let [calls (atom [])
              original blob/verify!]
          (with-redefs [blob/verify! (fn [root id]
                                       (swap! calls conj [root id])
                                       (original root id))]
            (let [verified (artifacts/verify! svc artifact)]
              (is (= [[(:blob-root svc) content-id]] @calls))
              (is (false? (:ok? verified)))
              (is (= :missing (:status verified)))))))
      (testing "status persistence keeps the missing state"
        (let [refreshed (artifacts/refresh-status!
                         svc {:session-id "s1" :artifact-id (:id artifact)}
                         {:persist? true})]
        (is (= :missing (:status refreshed)))
        (is (true? (:changed? refreshed)))
        (let [listed (artifacts/get-artifact svc {:session-id "s1"
                                                  :artifact-id (:id artifact)})]
          (is (= :missing (:status listed)))
            (is (= content-id (:content-id listed)))))))
      (testing "tampered bytes are reported as corrupt, not available"
      (let [file (write-file! dir "tampered.txt" "original")
            artifact (:artifact (artifacts/register!
                                 svc {:session-id "s1" :file file
                                      :name "tampered.txt"}))
            object (blob/object-path blob-root (:content-id artifact))]
        (spit (str object) "tampered")
        (let [verified (artifacts/verify! svc artifact)]
          (is (false? (:ok? verified)))
          (is (= :corrupt (:status verified))))
        (let [refreshed (artifacts/refresh-status!
                         svc {:session-id "s1" :artifact-id (:id artifact)}
                         {:persist? true})]
          (is (= :corrupt (:status refreshed)))
          (is (= :corrupt
                 (:status (artifacts/get-artifact
                           svc {:session-id "s1"
                                :artifact-id (:id artifact)})))))))))

(deftest delivery-refuses-stale-artifact-metadata
  (with-artifact-env [store blob-root svc dir]
    (let [file (write-file! dir "stale.txt" "stale metadata\n")
          artifact (:artifact (artifacts/register!
                               svc {:session-id "s1" :file file
                                    :name "stale.txt"}))
          wrong-size (assoc artifact :size (inc (long (:size artifact))))
          wrong-digest (assoc artifact :sha256 (apply str (repeat 64 "0")))]
      (testing "a stale size cannot pass the delivery preflight"
        (is (= :ARTIFACT_SIZE_MISMATCH
               (error-code #(artifacts/download svc wrong-size))))
        (is (= :ARTIFACT_INTEGRITY
               (error-code #(artifacts/read-bytes! svc wrong-size)))))
      (testing "a stale digest cannot pass the delivery preflight"
        (is (= :ARTIFACT_INTEGRITY
               (error-code #(artifacts/download svc wrong-digest))))
        (is (= :ARTIFACT_INTEGRITY
               (error-code #(artifacts/open! svc wrong-digest)))))
      (testing "a non-persisted status check exposes the corrected status"
        (let [checked (artifacts/refresh-status!
                       svc {:session-id "s1"
                            :artifact-id (:id artifact)})]
          (is (= :available (:status checked)))
          (is (= (str "/blobs/" (:content-id artifact))
                 (:open-url (:artifact checked)))))
        (blob/delete! blob-root (:content-id artifact))
        (let [checked (artifacts/refresh-status!
                       svc {:session-id "s1"
                            :artifact-id (:id artifact)})]
          (is (= :missing (:status checked)))
          (is (false? (:changed? checked)))
          (is (= :missing (:status (:artifact checked))))
          (is (nil? (:open-url (:artifact checked))))
          (is (nil? (:download-url (:artifact checked)))))))))

(deftest reference-aware-cleanup-test
  (with-artifact-env [store blob-root svc dir]
    (let [file (write-file! dir "kept.txt" "kept")
          artifact (:artifact (artifacts/register!
                               svc {:session-id "s1" :file file
                                    :name "kept.txt"}))
          content-id (:content-id artifact)
          refs (->> (storage/manage-blob-refs! store
                                               {:partition "session/s1"
                                                :op :list})
                    :refs
                    (mapv :id))]
      (testing "a referenced blob survives cleanup"
        (let [result (blob/cleanup! blob-root
                                    {:refs refs
                                     :safety-window-ms 0
                                     :include-temp? true})]
          (is (blob/exists? blob-root content-id))
          (is (= 0 (count (:deleted result))))))
      (testing "the blob is reclaimed only after the reference is released"
        (storage/manage-blob-refs! store
                                   {:partition "session/s1"
                                    :op :release
                                    :refs [content-id]})
        (let [refs (-> (storage/manage-blob-refs! store
                                                  {:partition "session/s1"
                                                   :op :list})
                       :refs)]
          (is (empty? refs))
          (let [result (blob/cleanup! blob-root
                                      {:refs refs
                                       :safety-window-ms 0
                                       :include-temp? true})]
            (is (false? (blob/exists? blob-root content-id)))
            (is (= 1 (count (:deleted result))))))))))

(deftest application-integration-test
  (with-artifact-env [store blob-root _svc dir]
    (let [app (integration/attach {:store store
                                   :blob-root blob-root
                                   :config {:limits {:artifact-bytes (* 1024 1024)}}})
          file (write-file! dir "delivered.log" "line\n")
          result ((:register! app) {:session-id "s1"
                                    :file file
                                    :name "delivered.log"
                                    :kind :log})
          artifact (:artifact result)
          content-id (:content-id artifact)]
      (is (:ok? result))
      (is (= "text/plain"
             ((:content-type-fn app) content-id {:id content-id}))
          "the gateway can resolve the artifact's media type by content ID")
      (let [snapshot ((:snapshot app) "s1")]
        (is (= 1 (count (:artifacts snapshot))))
        (is (= content-id (:content-id (first (:artifacts snapshot)))))
        (is (= (:version snapshot)
               (:version ((:list app) {:session-id "s1"})))))
      (is (= (:id artifact)
             (:id ((:get app) {:session-id "s1" :artifact-id (:id artifact)})))))))

(deftest retraction-releases-only-the-last-reference-test
  (with-artifact-env [store blob-root svc dir]
    (let [file (write-file! dir "shared.txt" "same bytes")
          first-artifact (:artifact
                          (artifacts/register!
                           svc {:session-id "s1" :file file
                                :artifact-id "shared-a"
                                :name "shared-a.txt"}))
          second-artifact (:artifact
                           (artifacts/register!
                            svc {:session-id "s1" :file file
                                 :artifact-id "shared-b"
                                 :name "shared-b.txt"}))
          content-id (:content-id first-artifact)]
      (is (= content-id (:content-id second-artifact)))
      (is (= 1 (count (-> (storage/manage-blob-refs!
                           store {:partition "session/s1" :op :list})
                          :refs))))
      (let [first-retract (artifacts/retract!
                           svc {:session-id "s1"
                                :artifact-id (:id first-artifact)})]
        (is (true? (:shared? first-retract)))
        (is (false? (:released? first-retract)))
        (is (= 1 (count (-> (storage/manage-blob-refs!
                             store {:partition "session/s1" :op :list})
                            :refs))))
        (is (nil? (artifacts/get-artifact
                   svc {:session-id "s1"
                        :artifact-id (:id first-artifact)}))))
      (let [second-retract (artifacts/retract!
                            svc {:session-id "s1"
                                 :artifact-id (:id second-artifact)})]
        (is (false? (:shared? second-retract)))
        (is (true? (:released? second-retract)))
        (is (empty? (-> (storage/manage-blob-refs!
                         store {:partition "session/s1" :op :list})
                        :refs)))
        (is (blob/exists? blob-root content-id)
            "retraction releases the reference; bytes wait for the cleaner")))))

(deftest executor-result-registers-announces-and-marks
  (with-artifact-env [store blob-root _svc dir]
    (let [file (write-file! dir "out.log" "line one\nline two\n")
          component (integration/attach {:store store
                                         :blob-root blob-root
                                         :config {}})
          published (atom [])
          result (integration/register-executor-result!
                  component
                  {:session-id "s1"
                   :task-id "t1"
                   :call-id "c1"
                   :executor :shell
                   :result {:output {:artifact {:path file
                                                :truncated? true
                                                :limit 1024}}}
                   :publish! #(swap! published conj %)})]
      (is (:ok? result))
      (is (= 1 (count @published)))
      (is (= "hc/artifact-available" (:name (first @published))))
      (is (true? (get-in result [:artifact :announced?])))
      (is (true? (get-in result [:artifact :truncated?])))
      (is (false? (get-in result [:artifact :complete?])))
      (is (str/includes? (get-in result [:artifact :truncated-reason])
                         "shell artifact limit")))))

(deftest default-artifact-id-is-derived-and-replayable
  (with-artifact-env [store blob-root svc dir]
    (let [file (write-file! dir "stable.txt" "stable\n")
          request {:session-id "s1" :file file :name "stable.txt"
                   :kind :file :source-task-id "t1"}
          first-result (artifacts/register! svc request)
          second-result (artifacts/register! svc request)]
      (is (= (get-in first-result [:artifact :id])
             (get-in second-result [:artifact :id])))
      (is (true? (:replayed? second-result)))
      (is (= 1 (count (:artifacts (artifacts/list-artifacts
                                   svc {:session-id "s1"}))))))))

(deftest produced-artifact-requires-source-task
  (with-artifact-env [_store _blob-root svc dir]
    (let [file (write-file! dir "produced.txt" "produced\n")]
      (is (= :ARTIFACT_INVALID_REQUEST
             (error-code
              (fn []
                (artifacts/register!
                 svc {:session-id "s1" :file file
                      :name "produced.txt"
                      :producer {:kind :tool :name "shell"}}))))))))
