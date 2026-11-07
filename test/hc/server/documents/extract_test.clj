(ns hc.server.documents.extract-test
  (:require [clojure.test :refer [deftest is testing]]
            [hc.server.documents :as documents]
            [hc.server.documents.support :as support])
  (:import [java.io ByteArrayInputStream File]))

(deftest source-identity-is-required
  (let [result (documents/extract! {:bytes (.getBytes "hello" "UTF-8")
                                    :selection {:route :text}})]
    (is (false? (:ok? result)))
    (is (= :SOURCE_REQUIRED (get-in result [:error :code]))))
  (let [result (documents/extract! {:source {:name "no-id.txt"}
                                    :bytes (.getBytes "hello" "UTF-8")
                                    :selection {:route :text}})]
    (is (false? (:ok? result)))
    (is (= :SOURCE_ID_REQUIRED (get-in result [:error :code])))))

(deftest native-does-not-read-the-source
  (let [result (documents/extract! {:source {:id "native-1" :name "missing.pdf"
                                             :mime "application/pdf"}
                                    :path "/definitely/not/here.pdf"
                                    :selection {:route :native}})]
    (is (true? (:ok? result)))
    (is (= :native (:route result)))
    (is (nil? (:text result)))
    (is (= [] (:pages result)))
    (is (true? (get-in result [:processing :sends-original?])))))

(deftest extension-does-not-decide-content
  (let [result (documents/extract! {:source {:id "mismatch-1"
                                             :name "actually-text.pdf"
                                             :mime "application/pdf"}
                                    :bytes (.getBytes "plain text, not a PDF" "UTF-8")
                                    :selection {:route :text}})]
    (is (true? (:ok? result)))
    (is (= :text (get-in result [:detected :kind])))
    (is (= "text/plain" (get-in result [:detected :mime])))
    (is (= :MIME_MISMATCH (-> result :warnings first :code)))
    (is (= "plain text, not a PDF" (:text result)))))

(deftest pdf-bytes-declared-as-text-still-use-pdfbox
  (let [directory (support/temp-dir)
        file (support/mixed-pdf! (File. directory "mixed.pdf"))
        result (documents/extract! {:source {:id "pdf-2" :name "mixed.txt"
                                             :mime "text/plain"}
                                    :path (.getPath file)
                                    :selection {:route :text}})]
    (is (true? (:ok? result)))
    (is (= :pdf (get-in result [:detected :kind])))
    (is (= :MIME_MISMATCH (-> result :warnings first :code)))
    (is (re-find #"page one" (:text result)))))

(deftest pdf-header-may-be-within-the-first-kilobyte
  (let [directory (support/temp-dir)
        file (support/mixed-pdf! (File. directory "mixed.pdf"))
        prefix (.getBytes "leading bytes\n" "US-ASCII")
        original (support/bytes-of file)
        shifted (byte-array (concat (seq prefix) (seq original)))
        result (documents/extract! {:source {:id "pdf-shifted" :name "shifted.pdf"
                                             :mime "application/pdf"}
                                    :bytes shifted
                                    :selection {:route :text :pages [1]}})]
    (is (true? (:ok? result)))
    (is (= :pdf (get-in result [:detected :kind])))
    (is (re-find #"page one" (:text result)))))

(deftest declared-size-change-is-reported
  (let [result (documents/extract! {:source {:id "size-1" :name "a.txt"
                                             :mime "text/plain" :size 1}
                                    :bytes (.getBytes "longer than one" "UTF-8")
                                    :selection {:route :text}})]
    (is (true? (:ok? result)))
    (is (= :SOURCE_SIZE_CHANGED (-> result :warnings first :code)))
    (is (= 15 (get-in result [:source :size])))))

(deftest unsupported-bytes-have-no-route
  (let [result (documents/extract! {:source {:id "bin-1" :name "a.bin"}
                                    :bytes (byte-array [0x00 0x01 0x02 0x03])
                                    :selection {:route :text}})]
    (is (false? (:ok? result)))
    (is (= :UNSUPPORTED_MIME (get-in result [:error :code])))
    (is (= :unsupported (get-in result [:detected :kind])))))

(deftest rtf-is-not-treated-as-plain-text
  (let [result (documents/extract! {:source {:id "rtf-1" :name "a.rtf"
                                             :mime "application/rtf"}
                                    :bytes (.getBytes
                                            "{\\rtf1\\ansi Hello RTF}"
                                            "US-ASCII")
                                    :selection {:route :text}})]
    (is (false? (:ok? result)))
    (is (= :UNSUPPORTED_MIME (get-in result [:error :code])))
    (is (= :unsupported (get-in result [:detected :kind])))
    (is (nil? (:text result)))))

(deftest missing-route-is-refused-before-reading
  (let [result (documents/extract! {:source {:id "no-route" :name "a.txt"}
                                    :bytes (.getBytes "hello" "UTF-8")})]
    (is (false? (:ok? result)))
    (is (= :ROUTE_REQUIRED (get-in result [:error :code])))))

(deftest unknown-route-is-refused
  (let [result (documents/extract! {:source {:id "bad-route" :name "a.txt"}
                                    :bytes (.getBytes "hello" "UTF-8")
                                    :selection {:route :magic}})]
    (is (false? (:ok? result)))
    (is (= :ROUTE_UNKNOWN (get-in result [:error :code])))))

(deftest route-refusals-carry-the-route
  (let [result (documents/extract! {:source {:id "r1" :name "a.txt"}
                                    :bytes (.getBytes "hello" "UTF-8")
                                    :selection {:route :ocr}})]
    (is (false? (:ok? result)))
    (is (= :ocr (:route result)))
    (is (= :ROUTE_UNAVAILABLE (get-in result [:error :code])))))

(deftest bounded-read-refuses-large-source
  (let [result (documents/extract! {:source {:id "large" :name "a.txt"}
                                    :bytes (byte-array 100)
                                    :max-bytes 10
                                    :selection {:route :text}})]
    (is (false? (:ok? result)))
    (is (= :SOURCE_TOO_LARGE (get-in result [:error :code])))))

(deftest missing-path-is-unreadable-not-empty
  (let [result (documents/extract! {:source {:id "missing" :name "a.txt"}
                                    :path "/definitely/not/here.txt"
                                    :selection {:route :text}})]
    (is (false? (:ok? result)))
    (is (= :SOURCE_UNREADABLE (get-in result [:error :code])))
    (is (nil? (:text result)))))

(deftest ambiguous-source-is-refused
  (let [result (documents/extract! {:source {:id "ambiguous" :name "a.txt"}
                                    :bytes (.getBytes "hello" "UTF-8")
                                    :path "/tmp/also-here.txt"
                                    :selection {:route :text}})]
    (is (false? (:ok? result)))
    (is (= :SOURCE_AMBIGUOUS (get-in result [:error :code])))))

(deftest invalid-path-is-unreadable
  (let [result (documents/extract! {:source {:id "bad-path" :name "a.txt"}
                                    :path (str "/tmp/" (char 0) "bad")
                                    :selection {:route :text}})]
    (is (false? (:ok? result)))
    (is (= :SOURCE_UNREADABLE (get-in result [:error :code])))))

(deftest stream-source-is-read
  (let [stream (ByteArrayInputStream. (.getBytes "stream text" "UTF-8"))
        result (documents/extract! {:source {:id "stream-1" :name "a.txt"
                                             :mime "text/plain"}
                                    :stream stream
                                    :selection {:route :text}})]
    (is (true? (:ok? result)))
    (is (= "stream text" (:text result)))
    (is (= "stream-1" (get-in result [:source :id])))))
