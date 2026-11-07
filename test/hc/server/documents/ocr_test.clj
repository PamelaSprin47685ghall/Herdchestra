(ns hc.server.documents.ocr-test
  (:require [clojure.test :refer [deftest is testing]]
            [hc.server.documents :as documents]
            [hc.server.documents.ocr :as ocr]
            [hc.server.documents.support :as support])
  (:import [java.io File]))

(deftest local-engine-is-available-with-bundled-languages
  (let [availability (documents/ocr-availability (documents/tesseract-engine))]
    (is (true? (:available? availability)))
    (is (= :device (:location availability)))
    (is (some #{"eng"} (:languages availability)))
    (is (some #{"chi_sim"} (:languages availability)))))

(deftest real-ocr-reads-a-generated-image
  (let [directory (support/temp-dir)
        file (support/write-bytes! (File. directory "text.png")
                                   (support/png-bytes (support/text-image "Herdchestra OCR 2048" 900 220)))
        result (documents/extract! {:source {:id "image-1" :name "text.png"
                                             :mime "image/png"}
                                    :path (.getPath file)
                                    :selection {:route :ocr
                                                :ocr {:languages ["eng"]}}})]
    (is (true? (:ok? result)))
    (is (= "Herdchestra OCR 2048" (:text result)))
    (is (= 1 (:page-count result)))
    (is (= [1] (mapv :page (:pages result))))
    (is (= "image-1" (get-in result [:pages 0 :source :id])))))

(deftest blank-image-is-not-successful-text
  (let [directory (support/temp-dir)
        file (support/write-bytes! (File. directory "blank.png")
                                   (support/png-bytes (java.awt.image.BufferedImage.
                                                       400 200
                                                       java.awt.image.BufferedImage/TYPE_INT_RGB)))
        result (documents/extract! {:source {:id "blank" :name "blank.png"
                                             :mime "image/png"}
                                    :path (.getPath file)
                                    :selection {:route :ocr
                                                :ocr {:languages ["eng"]}}})]
    (is (false? (:ok? result)))
    (is (= :OCR_EMPTY (get-in result [:error :code])))
    (is (= [:no-text] (mapv :status (:pages result))))
    (is (nil? (:text result)))))

(deftest missing-language-is-a-refusal
  (let [engine (documents/tesseract-engine)
        result (ocr/recognize! engine {:png-bytes (support/png-bytes
                                                   (support/text-image "hello"))
                                       :languages ["klingon"]})]
    (is (false? (:ok? result)))
    (is (= :OCR_LANGUAGE_UNAVAILABLE (get-in result [:error :code])))
    (is (some #{"eng"} (get-in result [:error :available-languages])))))

(deftest invalid-psm-is-a-refusal
  (let [engine (documents/tesseract-engine)
        result (ocr/recognize! engine {:png-bytes (support/png-bytes
                                                   (support/text-image "hello"))
                                       :languages ["eng"]
                                       :psm 99})]
    (is (false? (:ok? result)))
    (is (= :OCR_INVALID_PSM (get-in result [:error :code])))))

(deftest unavailable-engine-does-not-invent-text
  (let [engine {:id :fake :recognize! (fn [_] {:ok? true :text "invented"})
                :availability (fn [] {:available? false :reason :NO_SERVICE})}
        result (ocr/recognize! engine {:png-bytes (byte-array 0)})]
    (is (false? (:ok? result)))
    (is (= :OCR_UNAVAILABLE (get-in result [:error :code])))
    (is (nil? (:text result)))))

(deftest slow-engine-hits-the-deadline
  (let [engine {:id :slow
                :availability (fn [] {:available? true})
                :recognize! (fn [_] (Thread/sleep 5000) {:ok? true :text "late"})}
        started (System/nanoTime)
        result (ocr/recognize! engine {:png-bytes (byte-array 0)
                                       :timeout-ms 30})
        elapsed-ms (/ (- (System/nanoTime) started) 1000000.0)]
    (is (false? (:ok? result)))
    (is (= :OCR_TIMEOUT (get-in result [:error :code])))
    (is (< elapsed-ms 1000.0))))
