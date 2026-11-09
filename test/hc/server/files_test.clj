(ns hc.server.files-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [hc.core.workspace-gate :as wg]
            [hc.server.files :as files]
            [hc.server.files.http :as files-http])
  (:import (java.nio.charset StandardCharsets)
           (java.nio.file Files LinkOption Path Paths)
           (java.nio.file.attribute FileAttribute)))

(def ^:private root (atom nil))
(declare http)

(defn- temp-root []
  (-> (Files/createTempDirectory "hc-files-test"
                                 (make-array FileAttribute 0))
      (.toRealPath (make-array LinkOption 0))))

(defn- write-file! [relative content]
  (let [path (.resolve ^Path @root relative)]
    (Files/createDirectories (.getParent path)
                             (make-array FileAttribute 0))
    (Files/write path (.getBytes (str content) StandardCharsets/UTF_8)
                 (make-array java.nio.file.OpenOption 0))
    path))

(defn- write-bytes! [relative bytes]
  (let [path (.resolve ^Path @root relative)]
    (Files/createDirectories (.getParent path)
                             (make-array FileAttribute 0))
    (Files/write path ^bytes bytes
                 (make-array java.nio.file.OpenOption 0))
    path))

(defn- delete-tree! [^Path path]
  (when (Files/exists path (make-array LinkOption 0))
    (with-open [stream (Files/walk path (make-array java.nio.file.FileVisitOption 0))]
      (doseq [^Path child (reverse (vec (iterator-seq (.iterator stream))))]
        (Files/deleteIfExists child)))))

(use-fixtures :each
  (fn [f]
    (reset! root (temp-root))
    (try
      (write-file! "alpha.txt" "alpha\n")
      (write-file! "beta.md" "# beta\n")
      (write-file! "sub/gamma.clj" "(ns gamma)\n")
      (write-file! "sub/deeper/delta.txt" "delta\n")
      (write-bytes! "binary.bin"
                    (byte-array (map unchecked-byte [0 1 2 3 255])))
      (f)
      (finally
        (delete-tree! @root)))))

(defn- svc
  ([] (svc {}))
  ([opts] (files/service (merge {:root (str @root)} opts))))

(deftest list-root-shows-relative-paths-directories-first-and-stamps
  (let [result (files/list-entries (svc) "")]
    (is (:ok? result))
    (is (= "" (:path result)))
    (is (nil? (:parent result)))
    (let [entries (:entries result)
          names (mapv :name entries)]
      (testing "directories first, then names"
        (is (= ["sub" "alpha.txt" "beta.md" "binary.bin"] names)))
      (testing "paths are relative to the workspace root"
        (is (every? #(not (str/starts-with? (:path %) "/")) entries)))
      (testing "regular files carry the exact stamp; directories do not"
        (let [alpha (first (filter #(= "alpha.txt" (:name %)) entries))
              sub (first (filter #(= "sub" (:name %)) entries))
              expected (wg/stat-path (str @root) "alpha.txt")]
          (is (= {:mtime-sec (:mtime-sec expected)
                  :mtime-nsec (:mtime-nsec expected)
                  :size 6}
                 (:stamp alpha)))
          (is (:stamp-applicable? alpha))
          (is (nil? (:stamp sub)))
          (is (= :directory (:kind sub)))
          (is (false? (:stamp-applicable? sub))))))))

(deftest nested-list-and-parent
  (let [result (files/list-entries (svc) "sub")]
    (is (:ok? result))
    (is (= "sub" (:path result)))
    (is (= "" (:parent result)))
    (is (= ["deeper" "gamma.clj"] (mapv :name (:entries result))))
    (is (= "sub/deeper"
           (:path (files/list-entries (svc) "sub/deeper"))))))

(deftest listing-rejects-escapes
  (testing "parent traversal"
    (let [result (files/list-entries (svc) "../")]
      (is (false? (:ok? result)))
      (is (= :PATH_ESCAPE (get-in result [:error :code])))))
  (testing "absolute path"
    (let [result (files/list-entries (svc) "/etc")]
      (is (false? (:ok? result)))
      (is (= :ABSOLUTE_PATH (get-in result [:error :code])))))
  (testing "symlink escape"
    (let [outside (temp-root)]
      (try
        (Files/createSymbolicLink (.resolve ^Path @root "escape")
                                  outside
                                  (make-array FileAttribute 0))
        (let [result (files/list-entries (svc) "escape")]
          (is (false? (:ok? result)))
          (is (= :OUTSIDE_WORKSPACE (get-in result [:error :code]))))
        (finally
          (delete-tree! outside))))))

(deftest symlink-outside-is-labelled-without-target-metadata
  (let [outside (temp-root)]
    (try
      (Files/write (.resolve outside "outside-target.txt")
                   (.getBytes "secret" StandardCharsets/UTF_8)
                   (make-array java.nio.file.OpenOption 0))
      (Files/createSymbolicLink (.resolve ^Path @root "outside-link")
                                (.resolve outside "outside-target.txt")
                                (make-array FileAttribute 0))
      (let [result (files/list-entries (svc) "")
            entry (first (filter #(= "outside-link" (:name %))
                                 (:entries result)))]
        (is (= :symlink-outside (:kind entry)))
        (is (:outside? entry))
        (is (:exists? entry))
        (is (nil? (:stamp entry)))
        (is (nil? (:size entry))))
      (finally
        (delete-tree! outside)))))

(deftest read-follows-inside-symlink-and-rejects-outside
  (testing "a symlink inside the root is readable through its target"
    (Files/createSymbolicLink (.resolve ^Path @root "inside-link")
                              (.resolve ^Path @root "alpha.txt")
                              (make-array FileAttribute 0))
    (let [result (files/read-text (svc) "inside-link")]
      (is (:ok? result))
      (is (= "alpha\n" (:text result)))))
  (testing "a symlink outside the root is rejected before any content read"
    (let [outside (temp-root)]
      (try
        (Files/write (.resolve outside "secret.txt")
                     (.getBytes "secret" StandardCharsets/UTF_8)
                     (make-array java.nio.file.OpenOption 0))
        (Files/createSymbolicLink (.resolve ^Path @root "outside-read")
                                  (.resolve outside "secret.txt")
                                  (make-array FileAttribute 0))
        (let [result (files/read-text (svc) "outside-read")]
          (is (false? (:ok? result)))
          (is (= :OUTSIDE_WORKSPACE (get-in result [:error :code]))))
        (finally
          (delete-tree! outside))))))

(deftest read-text-returns-text-and-full-file-stamp
  (let [result (files/read-text (svc) "sub/gamma.clj")
        expected (wg/stat-path (str @root) "sub/gamma.clj")]
    (is (:ok? result))
    (is (= "(ns gamma)\n" (:text result)))
    (is (= (:mtime-sec expected) (get-in result [:stamp :mtime-sec])))
    (is (= (:mtime-nsec expected) (get-in result [:stamp :mtime-nsec])))
    (is (= 11 (get-in result [:stamp :size])))
    (is (false? (:changed-during-read? result)))))

(deftest read-text-rejects-directories-binary-and-large-files
  (testing "directory"
    (let [result (files/read-text (svc) "sub")]
      (is (false? (:ok? result)))
      (is (= :NOT_A_FILE (get-in result [:error :code])))))
  (testing "binary"
    (let [result (files/read-text (svc) "binary.bin")]
      (is (false? (:ok? result)))
      (is (= :BINARY_FILE (get-in result [:error :code])))))
  (testing "size limit"
    (let [result (files/read-text (svc {:max-file-bytes 4}) "alpha.txt")]
      (is (false? (:ok? result)))
      (is (= :FILE_TOO_LARGE (get-in result [:error :code])))
      (is (= 4 (get-in result [:error :details :limit])))))
  (testing "missing"
    (let [result (files/read-text (svc) "missing.txt")]
      (is (false? (:ok? result)))
      (is (= :NOT_FOUND (get-in result [:error :code]))))))

(deftest read-text-reports-stamp-conflict
  (let [stamp {:mtime-sec 1 :mtime-nsec 0 :size 3}
        result (with-redefs [wg/read-file (fn [_ _ _]
                                            {:status :FILE_CONFLICT
                                             :stamp stamp})
                             wg/current-stamp (fn [_ _] stamp)]
                 (files/read-text (svc) "alpha.txt"))]
    (is (false? (:ok? result)))
    (is (= :FILE_CONFLICT (get-in result [:error :code])))
    (is (nil? (:text result)))
    (is (= stamp (get-in result [:error :details :expected]))))
  (let [response (with-redefs [wg/read-file (fn [_ _ _]
                                              {:status :FILE_CONFLICT
                                               :stamp {:mtime-sec 1
                                                       :mtime-nsec 0
                                                       :size 3}})
                               wg/current-stamp (fn [_ _]
                                                  {:mtime-sec 1
                                                   :mtime-nsec 0
                                                   :size 3})]
                   (http {:uri "/api/workspace/read"
                          :query-string "path=alpha.txt"
                          :request-method :get}))]
    (is (= 409 (:status response)))
    (is (= :FILE_CONFLICT (get-in response [:body :error :code])))))

(deftest search-limit-must-be-positive-and-backslash-is-refused
  (doseq [limit ["0" "-1"]]
    (let [response (http {:uri "/api/workspace/search"
                          :query-string (str "q=a&limit=" limit)
                          :request-method :get})]
      (is (= 400 (:status response)))
      (is (= :INVALID_QUERY (get-in response [:body :error :code])))))
  (let [result (files/list-entries (svc) "sub\\gamma.clj")]
    (is (false? (:ok? result)))
    (is (= :INVALID_PATH (get-in result [:error :code])))))

(deftest stat-observes-missing-and-existing
  (let [present (files/stat-path (svc) "alpha.txt")
        missing (files/stat-path (svc) "nope.txt")]
    (is (:ok? present))
    (is (:exists? present))
    (is (= :file (:kind present)))
    (is (= 6 (get-in present [:stamp :size])))
    (is (:ok? missing))
    (is (false? (:exists? missing)))
    (is (= :missing (:kind missing)))
    (is (nil? (:stamp missing)))))

(deftest name-search-is-bounded-and-honest
  (let [result (files/search (svc) {:query "gamma"})]
    (is (:ok? result))
    (is (= :name (:mode result)))
    (is (= ["sub/gamma.clj"] (mapv :path (:results result))))
    (is (false? (:truncated? result))))
  (testing "truncation is reported"
    (let [result (files/search (svc {:max-search-results 1})
                               {:query "a"})]
      (is (:ok? result))
      (is (= 1 (count (:results result))))
      (is (:truncated? result)))))

(deftest content-search-reports-line-column-snippet-and-match-count
  (write-file! "content/notes.txt"
               "first line\nfind MEE here\nsecond MEE line\n")
  (let [result (files/search (svc) {:query "mee" :mode :content})
        hit (first (:results result))]
    (is (:ok? result))
    (is (= :content (:mode result)))
    (is (= ["content/notes.txt"]
           (mapv :path (:results result))))
    (is (= :file (:kind hit)))
    (is (= 2 (:line hit)))
    (is (= 6 (:column hit)))
    (is (= "find MEE here" (:snippet hit)))
    (is (= 2 (:match-count hit)))
    (is (map? (:stamp hit)))
    (is (= 41 (:size hit)))
    (is (= 1 (get-in result [:skipped :binary])))))

(deftest content-search-skips-binary-and-oversized-files
  (let [result (files/search (svc) {:query "alpha" :mode :content})]
    (is (:ok? result))
    (is (= ["alpha.txt"] (mapv :path (:results result))))
    (is (= 1 (get-in result [:skipped :binary])))
    (is (zero? (get-in result [:skipped :too-large]))))
  (let [result (files/search (svc {:max-search-content-file-bytes 2})
                             {:query "alpha" :mode :content})]
    (is (:ok? result))
    (is (empty? (:results result)))
    (is (= 5 (get-in result [:skipped :too-large])))
    (is (zero? (get-in result [:skipped :binary])))))

(deftest content-search-marks-budget-truncation
  (testing "file budget"
    (let [result (files/search (svc {:max-search-content-files 1})
                               {:query "alpha" :mode :content})]
      (is (:ok? result))
      (is (:truncated? result))
      (is (= 1 (:scanned-files result)))
      (is (= ["alpha.txt"] (mapv :path (:results result))))))
  (testing "byte budget stops before reading"
    (let [result (files/search (svc {:max-search-content-total-bytes 1})
                               {:query "alpha" :mode :content})]
      (is (:ok? result))
      (is (:truncated? result))
      (is (zero? (:scanned-bytes result)))
      (is (empty? (:results result))))))

(deftest content-search-records-unstable-reads
  (let [result (with-redefs [wg/read-file
                             (fn [_ _ _]
                               {:status :FILE_CONFLICT
                                :stamp {:mtime-sec 1
                                        :mtime-nsec 0
                                        :size 1}})]
                 (files/search (svc) {:query "alpha" :mode :content}))]
    (is (:ok? result))
    (is (empty? (:results result)))
    (is (= 5 (get-in result [:skipped :unstable])))
    (is (zero? (get-in result [:skipped :binary])))))

(deftest content-search-does-not-follow-outside-symlinks
  (let [outside (temp-root)]
    (try
      (Files/write (.resolve outside "secret.txt")
                   (.getBytes "secretneedle\n" StandardCharsets/UTF_8)
                   (make-array java.nio.file.OpenOption 0))
      (Files/createSymbolicLink (.resolve ^Path @root "outside-content-link")
                                (.resolve outside "secret.txt")
                                (make-array FileAttribute 0))
      (let [result (files/search (svc) {:query "secretneedle" :mode :content})]
        (is (:ok? result))
        (is (empty? (:results result)))
        (is (zero? (get-in result [:skipped :unstable])))
        (is (zero? (get-in result [:skipped :too-large])))
        (is (= 1 (get-in result [:skipped :binary])))
        (is (= 5 (:scanned-files result))))
      (finally
        (delete-tree! outside)))))

(deftest search-rejects-unknown-and-blank-modes
  (let [result (files/search (svc) {:query "alpha" :mode :fuzzy})]
    (is (false? (:ok? result)))
    (is (= :UNSUPPORTED_OPERATION (get-in result [:error :code])))
    (is (= :fuzzy (get-in result [:error :details :mode])))
    (is (= [:name :content] (get-in result [:error :details :allowed]))))
  (let [result (files/search (svc) {:query "   " :mode :content})]
    (is (false? (:ok? result)))
    (is (= :INVALID_QUERY (get-in result [:error :code])))))

(deftest http-content-search-passes-the-mode
  (let [response (http {:uri "/api/workspace/search"
                        :query-string "q=gamma&mode=content"
                        :request-method :get})]
    (is (= 200 (:status response)))
    (is (= :content (get-in response [:body :mode])))
    (is (= ["sub/gamma.clj"]
           (mapv :path (get-in response [:body :results]))))
    (is (= 1 (get-in response [:body :results 0 :match-count])))
    (is (= 1 (get-in response [:body :results 0 :line])))
    (is (= 5 (get-in response [:body :results 0 :column])))
    (is (= "(ns gamma)" (get-in response [:body :results 0 :snippet]))))
  (let [response (http {:uri "/api/workspace/search"
                        :query-string "q=alpha&mode=fuzzy"
                        :request-method :get})]
    (is (= 501 (:status response)))
    (is (= [:name :content]
           (get-in response [:body :error :details :allowed])))))

;; ---------------------------------------------------------------------------
;; HTTP

(defn- http
  ([request] (http request {}))
  ([request opts]
   (let [handler (files-http/handler (svc opts))
         response (handler request)]
     (when response
       (update response :body
               (fn [body]
                 (when body (edn/read-string body))))))))

(deftest http-list-and-read
  (let [list-response (http {:uri "/api/workspace/list"
                             :query-string "path=sub"
                             :request-method :get})]
    (is (= 200 (:status list-response)))
    (is (:ok? (:body list-response)))
    (is (= "sub" (get-in list-response [:body :path]))))
  (let [read-response (http {:uri "/api/workspace/read"
                             :query-string "path=alpha.txt"
                             :request-method :get})]
    (is (= 200 (:status read-response)))
    (is (= "alpha\n" (get-in read-response [:body :text]))))
  (let [search-response (http {:uri "/api/workspace/search"
                               :query-string "q=delta"
                               :request-method :get})]
    (is (= 200 (:status search-response)))
    (is (= ["sub/deeper/delta.txt"]
           (mapv :path (get-in search-response [:body :results]))))))

(deftest http-errors-are-stable
  (testing "escape is 400"
    (let [response (http {:uri "/api/workspace/list"
                          :query-string "path=../"
                          :request-method :get})]
      (is (= 400 (:status response)))
      (is (= :PATH_ESCAPE (get-in response [:body :error :code])))))
  (testing "binary is 415"
    (let [response (http {:uri "/api/workspace/read"
                          :query-string "path=binary.bin"
                          :request-method :get})]
      (is (= 415 (:status response)))
      (is (= :BINARY_FILE (get-in response [:body :error :code])))))
  (testing "write methods are refused"
    (let [response (http {:uri "/api/workspace/read"
                          :query-string "path=alpha.txt"
                          :request-method :post})]
      (is (= 405 (:status response)))
      (is (= "GET, HEAD" (get-in response [:headers "allow"])))))
  (testing "unknown api route is a 404, not the SPA fallback"
    (let [response (http {:uri "/api/workspace/nope"
                          :request-method :get})]
      (is (= 404 (:status response)))
      (is (= :NOT_FOUND (get-in response [:body :error :code])))))
  (testing "head has no body"
    (let [response (http {:uri "/api/workspace/read"
                          :query-string "path=alpha.txt"
                          :request-method :head})]
      (is (= 200 (:status response)))
      (is (nil? (:body response)))))
  (testing "non-api paths are not handled"
    (is (nil? (http {:uri "/chsk" :request-method :get}))))
  (testing "malformed percent encoding is a 400"
    (let [response (http {:uri "/api/workspace/list"
                          :query-string "path=%ZZ"
                          :request-method :get})]
      (is (= 400 (:status response)))
      (is (= :INVALID_QUERY (get-in response [:body :error :code]))))))
