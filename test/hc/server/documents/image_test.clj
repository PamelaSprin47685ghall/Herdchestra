(ns hc.server.documents.image-test
  (:require [clojure.test :refer [deftest is testing]]
            [hc.server.documents :as documents]
            [hc.server.documents.image :as image]
            [hc.server.documents.support :as support])
  (:import [java.awt.image BufferedImage]
           [javax.imageio ImageIO]))

(deftest scales-long-edge-and-preserves-alpha
  (let [source (BufferedImage. 3000 1000 BufferedImage/TYPE_INT_ARGB)
        result (documents/preprocess-image (support/png-bytes source)
                                           {:max-edge 2048})]
    (is (true? (:ok? result)))
    (is (= :png (:format result)))
    (is (= 2048 (:width result)))
    (is (= 683 (:height result)))
    (is (= 3000 (:source-width result)))
    (is (= 1000 (:source-height result)))
    (is (true? (:alpha? result)))
    (is (true? (:source-alpha? result)))
    (is (< (:scale result) 1.0))
    (is (= :IMAGE_SCALED (get-in (first (:warnings result)) [:code])))
    (is (pos? (alength ^bytes (:bytes result))))))

(deftest never-upscales
  (let [source (BufferedImage. 900 220 BufferedImage/TYPE_INT_RGB)
        result (documents/preprocess-image (support/png-bytes source)
                                           {:max-edge 2048})]
    (is (true? (:ok? result)))
    (is (= 900 (:width result)))
    (is (= 220 (:height result)))
    (is (= 1.0 (:scale result)))
    (is (= :jpeg (:format result)))))

(deftest exif-orientation-is-applied-before-scaling
  (let [source (support/split-color-image 3000 1000)
        encoded (support/jpeg-with-orientation source 6)
        result (documents/preprocess-image encoded {:max-edge 2048})]
    (is (true? (:ok? result)))
    (is (= 6 (:orientation result)))
    (is (= 3000 (:source-width result)))
    (is (= 1000 (:source-height result)))
    (is (= 683 (:width result)))
    (is (= 2048 (:height result)))
    (is (= 6 (image/exif-orientation encoded)))))

(deftest orientation-moves-pixels-not-just-dimensions
  (let [source (support/split-color-image 100 50)
        encoded (support/jpeg-with-orientation source 6)
        result (documents/preprocess-image encoded {:max-edge 2048
                                                    :format :png})
        decoded (ImageIO/read (java.io.ByteArrayInputStream. (:bytes result)))
        top-right (.getRGB decoded (dec (.getWidth decoded)) 0)]
    (is (= 50 (.getWidth decoded)))
    (is (= 100 (.getHeight decoded)))
    (is (> (bit-and (bit-shift-right top-right 16) 0xFF)
           (bit-and top-right 0xFF)))))

(deftest jpeg-cannot-silently-drop-alpha
  (let [source (BufferedImage. 100 100 BufferedImage/TYPE_INT_ARGB)
        bytes (support/png-bytes source)
        refused (documents/preprocess-image bytes {:format :jpeg})]
    (is (false? (:ok? refused)))
    (is (= :ALPHA_UNSUPPORTED (get-in refused [:error :code])))
    (let [flattened (documents/preprocess-image bytes {:format :jpeg
                                                       :flatten-alpha? true})]
      (is (true? (:ok? flattened)))
      (is (= :jpeg (:format flattened)))
      (is (false? (:alpha? flattened)))
      (is (= :ALPHA_FLATTENED (get-in (first (:warnings flattened)) [:code]))))))

(deftest pixel-limit-is-checked-before-decode
  (let [source (BufferedImage. 200 200 BufferedImage/TYPE_INT_RGB)
        result (documents/preprocess-image (support/png-bytes source)
                                           {:max-pixels 10000})]
    (is (false? (:ok? result)))
    (is (= :IMAGE_TOO_LARGE (get-in result [:error :code])))
    (is (= 40000 (get-in result [:error :pixels])))))

(deftest unreadable-image-is-refused
  (let [result (documents/preprocess-image (.getBytes "not an image" "UTF-8"))]
    (is (false? (:ok? result)))
    (is (= :IMAGE_UNREADABLE (get-in result [:error :code])))))

(deftest invalid-format-is-refused
  (let [source (BufferedImage. 10 10 BufferedImage/TYPE_INT_RGB)
        result (documents/preprocess-image (support/png-bytes source)
                                           {:format :gif})]
    (is (false? (:ok? result)))
    (is (= :IMAGE_FORMAT_UNSUPPORTED (get-in result [:error :code])))))
