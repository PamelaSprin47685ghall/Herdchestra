(ns hc.server.documents.ocr.tesseract
  "Local CPU Tesseract engine.

  Native libraries come from the JavaCPP presets on the server classpath, so
  there is no system `tesseract` install and no GPU requirement.  Language
  data is bundled under `resources/tessdata` and extracted to a temporary
  cache on first use; an explicit datapath can override it.

  The engine is honest about absence: a missing native library, a missing
  language file or a failed initialization is a refusal, never empty text."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [hc.server.documents.error :as error])
  (:import [java.awt.image BufferedImage]
           [java.io ByteArrayOutputStream File]
           [java.nio.file Files StandardCopyOption]
           [javax.imageio ImageIO]
           [org.bytedeco.javacpp BytePointer Loader]
           [org.bytedeco.leptonica PIX]
           [org.bytedeco.leptonica.global leptonica]
           [org.bytedeco.tesseract TessBaseAPI]
           [org.bytedeco.tesseract.global tesseract]))

(def version "5.5.3")
(def default-languages ["chi_sim" "eng"])
(def bundled-languages ["eng" "chi_sim"])
(def supported-psm #{0 1 2 3 4 5 6 7 8 9 10 11 12 13})

(defonce ^:private native-state (atom nil))

(defn- load-native!
  []
  (or @native-state
      (locking native-state
        (or @native-state
            (reset! native-state
                    (try
                      (Loader/load tesseract)
                      (Loader/load leptonica)
                      {:loaded? true}
                      (catch Throwable t
                        {:loaded? false
                         :reason (error/safe-message t)})))))))

(defn- cache-dir
  []
  (io/file (System/getProperty "java.io.tmpdir")
           (str "herdchestra-tessdata-" version)))

(defn- copy-resource!
  [language directory]
  (let [target (io/file directory (str language ".traineddata"))]
    (if (and (.isFile target) (pos? (.length target)))
      true
      (when-let [resource (io/resource (str "tessdata/" language ".traineddata"))]
        (try
          (let [temporary (File/createTempFile "hc-tess" ".part" directory)]
            (try
              (with-open [in (io/input-stream resource)]
                (io/copy in temporary))
              (Files/move (.toPath temporary) (.toPath target)
                          (into-array StandardCopyOption
                                      [StandardCopyOption/REPLACE_EXISTING
                                       StandardCopyOption/ATOMIC_MOVE]))
              true
              (catch Throwable _
                (.delete temporary)
                false)))
          (catch Throwable _
            false))))))

(defn- bundled-datapath
  []
  (let [directory (cache-dir)]
    (.mkdirs directory)
    (let [missing (->> bundled-languages
                       (remove #(copy-resource! % directory))
                       vec)]
      (if (seq missing)
        {:ok? false
         :reason :OCR_DATA_MISSING
         :message "bundled OCR language data is missing"
         :missing missing}
        {:ok? true :path directory}))))

(defn- explicit-datapath
  []
  (let [configured (or (System/getProperty "herdchestra.tessdata")
                       (System/getenv "HERDCHESTRA_TESSDATA"))]
    (when (and configured (not (str/blank? configured)))
      (let [directory (io/file configured)]
        (if (.isDirectory directory)
          {:ok? true :path directory}
          {:ok? false
           :reason :OCR_DATA_MISSING
           :message "the configured OCR data directory does not exist"
           :datapath configured})))))

(defn- resolve-datapath
  [datapath]
  (or (when (some? datapath)
        (let [directory (io/file datapath)]
          (if (.isDirectory directory)
            {:ok? true :path directory}
            {:ok? false
             :reason :OCR_DATA_MISSING
             :message "the OCR data directory does not exist"
             :datapath (str datapath)})))
      (explicit-datapath)
      (bundled-datapath)))

(defn available-languages
  [directory]
  (->> (or (.listFiles ^File directory) (make-array File 0))
       (filter #(.isFile ^File %))
       (keep #(second (re-matches #"(.+)\.traineddata" (.getName ^File %))))
       distinct
       sort
       vec))

(defn availability
  "Report whether native Tesseract and its language data can run."
  ([]
   (availability {}))
  ([{:keys [datapath]}]
   (let [native (load-native!)
         data (resolve-datapath datapath)]
     (cond
       (not (:loaded? native))
       {:available? false
        :engine :tesseract
        :engine-label "Tesseract (local CPU)"
        :location :device
        :reason :OCR_NATIVE_UNAVAILABLE
        :message (or (:reason native) "Tesseract native libraries did not load")}

       (not (:ok? data))
       {:available? false
        :engine :tesseract
        :engine-label "Tesseract (local CPU)"
        :location :device
        :reason (:reason data)
        :message (:message data)
        :missing (:missing data)}

       :else
       (let [languages (available-languages (:path data))]
         (if (empty? languages)
           {:available? false
            :engine :tesseract
            :engine-label "Tesseract (local CPU)"
            :location :device
            :reason :OCR_DATA_MISSING
            :message "no OCR language data was found"}
           {:available? true
            :engine :tesseract
            :engine-label "Tesseract (local CPU)"
            :location :device
            :version version
            :languages languages
            :datapath (str (:path data))}))))))

(defn- image->png
  [^BufferedImage image]
  (let [output (ByteArrayOutputStream.)]
    (if (ImageIO/write image "png" output)
      {:ok? true :bytes (.toByteArray output)}
      (error/failure :OCR_IMAGE_REQUIRED "the image could not be encoded as PNG"))))

(defn- recognize-once!
  [{:keys [datapath]} {:keys [png-bytes image languages psm]
                       :or {languages default-languages
                            psm 3}}]
  (let [data (resolve-datapath datapath)]
    (cond
      (not (:ok? data))
      (error/failure (:reason data) (:message data)
                     {:engine :tesseract :missing (:missing data)})

      (not (and (integer? psm) (contains? supported-psm psm)))
      (error/failure :OCR_INVALID_PSM
                     "the page segmentation mode is not supported"
                     {:engine :tesseract :psm psm})

      (and (#{0 1} psm)
           (not (some #{"osd"} (available-languages (:path data)))))
      (error/failure :OCR_UNSUPPORTED_PSM
                     "this page segmentation mode needs orientation data that is not bundled"
                     {:engine :tesseract :psm psm})

      :else
      (let [languages (->> (if (string? languages) [languages] languages)
                           (map str) distinct vec)
            available (set (available-languages (:path data)))
            missing (->> languages (remove available) vec)]
        (cond
          (empty? languages)
          (error/failure :OCR_LANGUAGE_UNAVAILABLE
                         "choose at least one OCR language"
                         {:engine :tesseract :available-languages (sort available)})

          (seq missing)
          (error/failure :OCR_LANGUAGE_UNAVAILABLE
                         "requested OCR language data is not available"
                         {:engine :tesseract
                          :languages languages
                          :missing missing
                          :available-languages (sort available)})

          :else
          (let [png (cond
                      (bytes? png-bytes) png-bytes
                      (instance? BufferedImage image)
                      (let [encoded (image->png image)]
                        (if (:ok? encoded)
                          (:bytes encoded)
                          nil))
                      :else nil)]
            (if-not png
              (error/failure :OCR_IMAGE_REQUIRED
                             "the OCR request needs :png-bytes or :image")
              (let [api (TessBaseAPI.)]
                (try
                  (let [initialized (.Init api (str (:path data))
                                           (str/join "+" languages)
                                           tesseract/OEM_DEFAULT)]
                    (if-not (zero? initialized)
                      (error/failure :OCR_INIT_FAILED
                                     "Tesseract did not initialize"
                                     {:engine :tesseract :languages languages})
                      (do
                        (.SetPageSegMode api (int psm))
                        (let [pix (leptonica/pixReadMem ^bytes png (alength ^bytes png))]
                          (if (nil? pix)
                            (error/failure :OCR_FAILED
                                           "Tesseract could not read the image")
                            (try
                              (leptonica/pixSetResolution ^PIX pix 300 300)
                              (.SetImage api ^PIX pix)
                              (let [pointer (.GetUTF8Text api)]
                                (try
                                  (let [text (str/trimr (.getString ^BytePointer pointer))
                                        text (if (str/blank? text) "" text)]
                                    {:ok? true
                                     :text text
                                     :empty? (empty? text)
                                     :engine :tesseract
                                     :languages languages
                                     :psm psm})
                                  (finally
                                    (.close ^BytePointer pointer))))
                              (finally
                                (leptonica/pixDestroy ^PIX pix))))))))
                  (finally
                    (try (.End api) (catch Throwable _ nil))
                    (try (.close api) (catch Throwable _ nil))))))))))))

(defn recognize!
  "Run Tesseract on one prepared PNG (or BufferedImage)."
  [options request]
  (try
    (recognize-once! options request)
    (catch Exception t
      (error/failure :OCR_FAILED
                     "Tesseract failed while recognizing the image"
                     {:engine :tesseract :reason (error/safe-message t)}))
    (catch LinkageError t
      (error/failure :OCR_NATIVE_UNAVAILABLE
                     "Tesseract native libraries could not be loaded"
                     {:engine :tesseract :reason (error/safe-message t)}))))

(defn engine
  "A local CPU OCR engine map for `hc.server.documents.ocr`."
  ([]
   (engine {}))
  ([options]
   {:id :tesseract
    :engine-label "Tesseract (local CPU)"
    :location :device
    :availability (fn [] (availability options))
    :recognize! (fn [request] (recognize! options request))}))
