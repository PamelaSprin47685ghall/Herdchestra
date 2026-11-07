(ns hc.server.documents.text-test
  (:require [clojure.test :refer [deftest is testing]]
            [hc.server.documents :as documents]
            [hc.server.documents.text :as text]))

(defn- encoded
  [s encoding]
  (.getBytes ^String s ^String encoding))

(deftest strict-utf8
  (let [result (text/decode (encoded "第一行\n第二行" "UTF-8"))]
    (is (true? (:ok? result)))
    (is (= "第一行\n第二行" (:text result)))
    (is (= "UTF-8" (:encoding result)))
    (is (= 2 (:line-count result)))
    (is (= 7 (:codepoint-count result)))))

(deftest utf8-bom-is-removed
  (let [with-bom (byte-array (concat [0xEF 0xBB 0xBF] (seq (encoded "hello" "UTF-8"))))
        result (text/decode with-bom)]
    (is (true? (:ok? result)))
    (is (= "hello" (:text result)))
    (is (true? (:bom? result)))
    (is (= "UTF-8" (:encoding result)))))

(deftest utf16-bom-is-honored
  (let [le (byte-array (concat [0xFF 0xFE] (seq (encoded "hello" "UTF-16LE"))))
        be (byte-array (concat [0xFE 0xFF] (seq (encoded "hello" "UTF-16BE"))))]
    (doseq [input [le be]]
      (let [result (text/decode input)]
        (is (true? (:ok? result)))
        (is (= "hello" (:text result)))
        (is (re-find #"UTF-16" (:encoding result)))))))

(deftest explicit-encoding-wins
  (let [latin1 (byte-array [(unchecked-byte 0xE9)])
        result (text/decode latin1 {:encoding "ISO-8859-1"})]
    (is (true? (:ok? result)))
    (is (= "é" (:text result)))
    (is (= "ISO-8859-1" (:encoding result))))
  (let [utf16 (byte-array (seq (encoded "hello" "UTF-16LE")))
        result (documents/extract! {:source {:id "utf16" :name "a.txt"}
                                    :bytes utf16
                                    :selection {:route :text
                                                :encoding "UTF-16LE"}})]
    (is (true? (:ok? result)))
    (is (= "hello" (:text result)))))

(deftest invalid-utf8-is-refused
  (let [result (text/decode (byte-array [(unchecked-byte 0xC3)
                                         (unchecked-byte 0x28)]))]
    (is (false? (:ok? result)))
    (is (= :ENCODING_UNKNOWN (get-in result [:error :code])))
    (is (nil? (:text result)))))

(deftest nul-bytes-are-not-text
  (let [result (text/decode (byte-array [0x68 0x00 0x69]))]
    (is (false? (:ok? result)))
    (is (= :NOT_TEXT (get-in result [:error :code])))))

(deftest empty-text-is-empty-not-a-failure
  (let [result (text/decode (byte-array 0))]
    (is (true? (:ok? result)))
    (is (= "" (:text result)))
    (is (true? (:empty? result)))
    (is (= 0 (:line-count result)))))

(deftest text-source-identity-and-no-fake-pages
  (let [result (documents/extract! {:source {:id "text-1" :name "notes.txt"
                                             :mime "text/plain"}
                                    :bytes (encoded "alpha\nbeta" "UTF-8")
                                    :selection {:route :text}})]
    (is (true? (:ok? result)))
    (is (= "text-1" (get-in result [:source :id])))
    (is (re-matches #"[0-9a-f]{64}" (get-in result [:source :sha256])))
    (is (= [] (:pages result)))
    (is (nil? (:page-count result)))
    (is (= 2 (:line-count result)))))

(deftest text-source-rejects-page-selection
  (let [result (documents/extract! {:source {:id "text-1" :name "notes.txt"}
                                    :bytes (encoded "alpha" "UTF-8")
                                    :selection {:route :text :pages [1]}})]
    (is (false? (:ok? result)))
    (is (= :PAGE_RANGE (get-in result [:error :code])))))
