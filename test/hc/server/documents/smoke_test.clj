(ns hc.server.documents.smoke-test
  "Real-sample smoke: actual files on disk, actual PDFBox parse, actual
  Tesseract recognition.  No fake extractor is used here."
  (:require [clojure.test :refer [deftest is testing]]
            [hc.server.documents :as documents]
            [hc.server.documents.support :as support])
  (:import [java.io File]))

(deftest real-text-pdf-and-ocr-samples
  (let [directory (support/temp-dir)
        text-file (support/write-bytes!
                   (File. directory "notes.txt")
                   (.getBytes "第一行\nsecond line\n" "UTF-8"))
        pdf-file (support/mixed-pdf! (File. directory "mixed.pdf"))
        image-file (support/write-bytes!
                    (File. directory "scan.png")
                    (support/png-bytes (support/text-image "Herdchestra OCR 2048" 900 220)))
        text-result (documents/extract!
                     {:source {:id "smoke-text" :name "notes.txt" :mime "text/plain"}
                      :path (.getPath text-file)
                      :selection {:route :text}})
        pdf-result (documents/extract!
                    {:source {:id "smoke-pdf" :name "mixed.pdf" :mime "application/pdf"}
                     :path (.getPath pdf-file)
                     :selection {:route :text :pages "1-3"}})
        pdf-ocr-result (documents/extract!
                        {:source {:id "smoke-pdf" :name "mixed.pdf" :mime "application/pdf"}
                         :path (.getPath pdf-file)
                         :selection {:route :ocr :pages [2]
                                     :ocr {:languages ["eng"] :dpi 200}}})
        image-result (documents/extract!
                      {:source {:id "smoke-image" :name "scan.png" :mime "image/png"}
                       :path (.getPath image-file)
                       :selection {:route :ocr
                                   :ocr {:languages ["eng"]}}})]
    (testing "real UTF-8 text"
      (is (true? (:ok? text-result)))
      (is (= "第一行\nsecond line\n" (:text text-result)))
      (is (= 2 (:line-count text-result))))
    (testing "real PDF text keeps page identity and exposes the scanned page"
      (is (true? (:ok? pdf-result)))
      (is (= 3 (:page-count pdf-result)))
      (is (= [1 2 3] (mapv :page (:pages pdf-result))))
      (is (= [:text :no-text :text] (mapv :status (:pages pdf-result))))
      (is (re-find #"page one" (:text pdf-result)))
      (is (re-find #"page three" (:text pdf-result))))
    (testing "real OCR reads the scanned PDF page without losing page number"
      (is (true? (:ok? pdf-ocr-result)))
      (is (= [2] (mapv :page (:pages pdf-ocr-result))))
      (is (= "Scanned Herdchestra 2048" (:text pdf-ocr-result))))
    (testing "real OCR reads a standalone image"
      (is (true? (:ok? image-result)))
      (is (= 1 (:page-count image-result)))
      (is (= "Herdchestra OCR 2048" (:text image-result))))
    (testing "every result points back to its source"
      (is (= "smoke-pdf" (get-in pdf-result [:source :id])))
      (is (= "smoke-pdf" (get-in pdf-ocr-result [:pages 0 :source :id])))
      (is (= "smoke-image" (get-in image-result [:pages 0 :source :id]))))))
