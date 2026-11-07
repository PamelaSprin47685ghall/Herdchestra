(ns hc.server.documents.selection-test
  (:require [clojure.test :refer [deftest is testing]]
            [hc.server.documents.selection :as selection]))

(deftest route-must-be-explicit
  (let [result (selection/select-route {:requested nil :kind :text})]
    (is (false? (:ok? result)))
    (is (= :ROUTE_REQUIRED (get-in result [:error :code]))))
  (let [result (selection/select-route {:requested :guess :kind :text})]
    (is (false? (:ok? result)))
    (is (= :ROUTE_UNKNOWN (get-in result [:error :code])))))

(deftest native-is-always-available
  (let [result (selection/select-route {:requested :native :kind :unsupported})]
    (is (true? (:ok? result)))
    (is (= :native (:route result)))
    (is (true? (get-in result [:processing :sends-original?])))))

(deftest text-and-ocr-routes-are-kind-specific
  (let [image-text (selection/select-route {:requested :text :kind :image})
        text-ocr (selection/select-route {:requested :ocr :kind :text})]
    (is (false? (:ok? image-text)))
    (is (= :ROUTE_UNAVAILABLE (get-in image-text [:error :code])))
    (is (= :ocr (get-in image-text [:error :suggested-route])))
    (is (false? (:ok? text-ocr)))
    (is (= :text (get-in text-ocr [:error :suggested-route])))))

(deftest ocr-availability-is-required
  (let [result (selection/select-route
                {:requested :ocr
                 :kind :image
                 :ocr {:available? false :engine :tesseract :reason :NO_DATA}})]
    (is (false? (:ok? result)))
    (is (= :OCR_UNAVAILABLE (get-in result [:error :code])))
    (is (= :NO_DATA (get-in result [:error :reason])))
    (is (= :native (get-in result [:error :suggested-route])))))

(deftest processing-describes-privacy-boundary
  (let [text (selection/processing :text)
        ocr (selection/processing :ocr {:engine :tesseract :location :device})]
    (is (false? (:sends-original? text)))
    (is (true? (:sends-text? text)))
    (is (= :device (:location ocr)))
    (is (= :tesseract (:engine ocr)))))
