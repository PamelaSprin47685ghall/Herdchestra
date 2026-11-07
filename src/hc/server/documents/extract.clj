(ns hc.server.documents.extract
  "Document extraction orchestration.

  One call reads the source at most once, detects its real content family,
  validates the explicitly selected route, and returns page-identified
  results.  The order is deliberate:

  1. source identity is required;
  2. `:native` returns without reading bytes;
  3. otherwise bytes are read under a bound, sniffed, and only then parsed;
  4. a missing route, an unavailable engine, or an empty OCR result is a
     refusal with evidence, never a fallback to another route."
  (:require [clojure.string :as str]
            [hc.server.documents.error :as error]
            [hc.server.documents.image :as image]
            [hc.server.documents.ocr :as ocr]
            [hc.server.documents.ocr.tesseract :as tesseract]
            [hc.server.documents.pages :as pages]
            [hc.server.documents.pdf :as pdf]
            [hc.server.documents.selection :as selection]
            [hc.server.documents.source :as source]
            [hc.server.documents.text :as text]))

(def default-max-bytes source/default-max-bytes)
(def default-max-pages pdf/default-max-pages)
(def default-dpi 200)
(def max-dpi 300)

(defn- requested-route
  [request]
  (let [route (or (get-in request [:selection :route])
                  (:route request))]
    (cond
      (nil? route) nil
      (keyword? route) route
      (string? route) (keyword route)
      :else route)))

(defn- selection-options
  [request]
  (merge (:ocr request)
         (get-in request [:selection :ocr])))

(defn- image-options
  [request]
  (merge (:image request)
         (get-in request [:selection :image])))

(defn- ocr-engine
  [request]
  (let [candidate (or (:ocr-engine request)
                      (get-in request [:selection :ocr :engine]))]
    (cond
      (ocr/engine? candidate)
      {:ok? true :engine candidate}

      (or (nil? candidate) (= :tesseract candidate))
      {:ok? true :engine (tesseract/engine
                          (select-keys (selection-options request) [:datapath]))}

      :else
      (error/failure :OCR_ENGINE_INVALID
                     "the selected OCR engine is not a valid engine map"
                     {:engine candidate}))))

(defn- max-bytes
  [request]
  (or (:max-bytes request) default-max-bytes))

(defn- max-pages
  [request]
  (or (:max-pages request) default-max-pages))

(defn- enrich-source
  [src read-result]
  (let [actual (:size read-result)
        declared (:size src)
        changed? (and (some? declared) (not= declared actual))]
    {:source (cond-> (assoc src :size actual :sha256 (:sha256 read-result))
               changed? (assoc :declared-size declared :changed? true))
     :warnings (if changed?
                 [(error/warning :SOURCE_SIZE_CHANGED
                                 "the source size differs from its descriptor"
                                 {:declared-size declared :actual-size actual})]
                 [])}))

(defn- attach-context
  [src detected processing result extra-warnings]
  (-> result
      (assoc :source src)
      (cond-> detected
        (assoc :detected (select-keys detected [:kind :mime :format
                                                :declared-mime :reason])))
      (cond-> processing
        (assoc :processing processing))
      (assoc :warnings (vec (concat (or (:warnings detected) [])
                                    extra-warnings
                                    (:warnings result))))))

(defn- attach-page-source
  [result src]
  (if (seq (:pages result))
    (update result :pages
            (fn [pages]
              (mapv #(assoc % :source (select-keys src [:id :name :mime]))
                    pages)))
    result))

(defn- parsed-selection
  [request]
  (pages/parse-range (get-in request [:selection :pages])
                     {:max-pages (max-pages request)}))

(defn- text-source
  [src bytes request]
  (let [parsed (parsed-selection request)]
    (if-not (:ok? parsed)
      parsed
      (if (and (not (:all? parsed)) (seq (:pages parsed)))
        (error/failure :PAGE_RANGE
                       "a text source has no page selection"
                       {:selected-pages (:pages parsed)})
        (let [encoding (or (:encoding request)
                           (get-in request [:selection :encoding]))
              decoded (text/decode bytes {:encoding encoding
                                          :max-bytes (max-bytes request)})]
          (if-not (:ok? decoded)
            decoded
            (-> decoded
                (assoc :route :text
                       :page-count nil
                       :pages [])
                (update :warnings (fnil into [])
                        (when (:empty? decoded)
                          [(error/warning :EMPTY_TEXT
                                          "the text source contains no characters")])))))))))

(defn- pdf-text
  [src bytes request]
  (let [parsed (parsed-selection request)]
    (if-not (:ok? parsed)
      parsed
      (-> (pdf/extract-text {:bytes bytes
                             :password (:password request)
                             :selected (:pages parsed)
                             :max-pages (max-pages request)})
          (attach-page-source src)))))

(defn- ocr-page
  [engine prepared options]
  (let [recognized (ocr/recognize! engine
                                   {:png-bytes (:bytes prepared)
                                    :languages (:languages options)
                                    :psm (:psm options)
                                    :timeout-ms (:timeout-ms options)})]
    (cond
      (not (:ok? recognized))
      {:status :error
       :text ""
       :error (:error recognized)}

      (:empty? recognized)
      {:status :no-text
       :text ""}

      :else
      {:status :text
       :text (:text recognized)})))

(defn- ocr-options
  [request]
  (let [options (selection-options request)]
    {:languages (let [languages (or (:languages options) tesseract/default-languages)]
                  (if (string? languages) [languages] (vec languages)))
     :psm (or (:psm options) 3)
     :timeout-ms (or (:timeout-ms options) ocr/default-timeout-ms)
     :dpi (min max-dpi (max 72 (or (:dpi options) default-dpi)))
     :max-edge (or (:max-edge options) image/default-max-edge)}))

(defn- ocr-image-options
  [request options]
  (merge {:format :png
          :flatten-alpha? true
          :quality 0.9
          :max-edge (:max-edge options)
          :max-pixels image/default-max-pixels}
         (image-options request)
         {:format :png
          :flatten-alpha? true
          :max-edge (:max-edge options)}))

(defn- ocr-pages
  [engine pages options page->image]
  (mapv (fn [page]
          (try
            (let [prepared (page->image page)]
              (if-not (:ok? prepared)
                {:page page
                 :status :error
                 :text ""
                 :error (:error prepared)}
                (merge {:page page}
                       (ocr-page engine prepared options))))
            (catch Exception t
              {:page page
               :status :error
               :text ""
               :error {:code :PAGE_RENDER_FAILED
                       :message (error/safe-message t)}})))
        pages))

(defn- ocr-warnings
  [results]
  (let [empty-pages (->> results (filter #(= :no-text (:status %))) (mapv :page))
        failed-pages (->> results (filter #(= :error (:status %))) (mapv :page))]
    (cond-> []
      (seq empty-pages)
      (conj (error/warning :PAGES_WITHOUT_TEXT
                           "OCR found no text on some pages"
                           {:pages empty-pages}))

      (seq failed-pages)
      (conj (error/warning :PAGES_UNREADABLE
                           "some pages could not be recognized"
                           {:pages failed-pages})))))

(defn- finish-ocr
  [src route page-count pages options warnings]
  (let [text (->> pages
                  (filter #(= :text (:status %)))
                  (map :text)
                  (str/join "\n\n"))
        warnings (into (vec warnings) (ocr-warnings pages))
        first-error (some :error pages)]
    (if (str/blank? text)
      (-> (merge (if first-error
                   (error/failure (or (:code first-error) :OCR_FAILED)
                                  (or (:message first-error)
                                      "OCR failed for the selected pages"))
                   (error/failure :OCR_EMPTY
                                  "OCR produced no text for the selected pages"
                                  {:suggested-route :native}))
                 {:page-count page-count
                  :pages pages
                  :warnings warnings}
                 (when-not first-error
                   {:ocr {:engine (:id options)
                          :languages (:languages options)
                          :psm (:psm options)}}))
          (assoc :route route)
          (attach-page-source src))
      (-> {:ok? true
           :route route
           :page-count page-count
           :pages pages
           :text text
           :ocr {:engine (:id options)
                 :languages (:languages options)
                 :psm (:psm options)}
           :warnings warnings}
          (attach-page-source src)))))

(defn- image-ocr
  [src bytes request engine options]
  (let [prepared (image/preprocess bytes (ocr-image-options request options))]
    (if-not (:ok? prepared)
      prepared
      (let [page (merge {:page 1}
                        (ocr-page engine prepared options))
            warnings (cond-> (or (:warnings prepared) [])
                       (= :no-text (:status page))
                       (conj (error/warning :PAGES_WITHOUT_TEXT
                                            "OCR found no text in the image"
                                            {:pages [1]}))
                       (= :error (:status page))
                       (conj (error/warning :PAGES_UNREADABLE
                                            "the image could not be recognized"
                                            {:pages [1]})))
            image-facts (dissoc prepared :bytes)]
        (-> (finish-ocr src :ocr 1 [page]
                        {:id (:id engine)
                         :languages (:languages options)
                         :psm (:psm options)}
                        warnings)
            (assoc :image image-facts))))))

(defn- pdf-ocr
  [src bytes request engine options]
  (let [parsed (parsed-selection request)]
    (if-not (:ok? parsed)
      parsed
      (pdf/with-document bytes (:password request) {:max-pages (max-pages request)}
        (fn [document facts]
          (if-not (pdf/extraction-allowed? document)
            (error/failure :PDF_EXTRACTION_FORBIDDEN
                           "the PDF permissions do not allow content extraction"
                           {:encrypted? true})
            (let [invalid (pages/validate (:pages parsed) (:page-count facts)
                                          {:max-pages (max-pages request)})]
              (if invalid
                invalid
                (let [selected (pages/selected parsed (:page-count facts))
                      renderer (pdf/renderer document)
                      max-pixels (or (:max-pixels (ocr-image-options request options))
                                     image/default-max-pixels)
                      page->image
                      (fn [page]
                        (let [pixels (pdf/page-pixels document page (:dpi options))]
                          (if (and pixels (> pixels max-pixels))
                            (error/failure :PAGE_TOO_LARGE
                                           "the rendered page exceeds the pixel limit"
                                           {:page page :pixels pixels
                                            :max-pixels max-pixels})
                            (let [rendered (pdf/render-page renderer page (:dpi options))]
                              (if (> (* (.getWidth ^java.awt.image.BufferedImage rendered)
                                        (.getHeight ^java.awt.image.BufferedImage rendered))
                                     max-pixels)
                                (error/failure :PAGE_TOO_LARGE
                                               "the rendered page exceeds the pixel limit"
                                               {:page page :max-pixels max-pixels})
                                (image/preprocess-image
                                 rendered
                                 (merge (ocr-image-options request options)
                                        {:orientation 1
                                         :source-width (.getWidth ^java.awt.image.BufferedImage rendered)
                                         :source-height (.getHeight ^java.awt.image.BufferedImage rendered)})))))))
                      results (ocr-pages engine selected options page->image)
                      warnings (cond-> []
                                 (:encrypted? facts)
                                 (conj (error/warning :PDF_ENCRYPTED_OPENED
                                                      (if (:password-supplied? facts)
                                                        "the PDF was opened with the supplied password"
                                                        "the PDF was encrypted and opened without a password")
                                                      {:password-supplied? (:password-supplied? facts)})))]
                  (-> (finish-ocr src :ocr (:page-count facts) results
                                  {:id (:id engine)
                                   :languages (:languages options)
                                   :psm (:psm options)}
                                  warnings)
                      (assoc :selected-pages selected
                             :encrypted? (:encrypted? facts))))))))))))

(defn- dispatch
  [src bytes request detected route processing engine]
  (let [kind (:kind detected)]
    (case route
      :text
      (case kind
        :text (text-source src bytes request)
        :pdf (pdf-text src bytes request)
        (error/failure :ROUTE_UNAVAILABLE
                       "text extraction is not available for this content"
                       {:kind kind :suggested-route :ocr}))

      :ocr
      (let [options (assoc (ocr-options request)
                           :id (:id engine))]
        (case kind
          :image (image-ocr src bytes request engine options)
          :pdf (pdf-ocr src bytes request engine options)
          (error/failure :ROUTE_UNAVAILABLE
                         "OCR is not available for this content"
                         {:kind kind :suggested-route :text})))

      (error/failure :ROUTE_UNKNOWN
                     "the selected processing route is not recognized"
                     {:route route}))))

(defn extract!
  "Extract one document under an explicit processing selection.

  See `src/hc/server/documents/AGENTS.md` for the full request and result
  contract.  Expected refusals are result maps with `:ok? false`."
  [request]
  (let [source-result (source/normalize (:source request))]
    (if-not (:ok? source-result)
      source-result
      (let [src (:source source-result)
            route (requested-route request)]
        (cond
          (nil? route)
          (attach-context src nil nil
                          (error/failure :ROUTE_REQUIRED
                                         "choose how this material should be processed"
                                         {:routes (sort selection/routes)})
                          [])

          (not (contains? selection/routes route))
          (attach-context src nil nil
                          (error/failure :ROUTE_UNKNOWN
                                         "the selected processing route is not recognized"
                                         {:route route :routes (sort selection/routes)})
                          [])

          (= :native route)
          (attach-context src nil (selection/processing :native)
                          {:ok? true
                           :route :native
                           :text nil
                           :pages []
                           :page-count nil}
                          [])

          :else
          (let [read-result (source/read-bytes request {:max-bytes (max-bytes request)})]
            (if-not (:ok? read-result)
              (attach-context src nil nil (assoc read-result :route route) [])
              (let [{:keys [source warnings]} (enrich-source src read-result)
                    detected (source/detect (:bytes read-result)
                                            {:declared-mime (:mime source)
                                             :name (:name source)
                                             :encoding (or (:encoding request)
                                                           (get-in request [:selection :encoding]))})
                    engine-result (when (= :ocr route) (ocr-engine request))]
                (if (and engine-result (not (:ok? engine-result)))
                  (attach-context source detected nil
                                  (assoc engine-result :route route) warnings)
                  (let [engine (:engine engine-result)
                        ocr-info (when engine (ocr/availability engine))
                        selected (selection/select-route
                                  {:requested route
                                   :kind (:kind detected)
                                   :ocr ocr-info})]
                    (if-not (:ok? selected)
                      (attach-context source detected nil
                                      (assoc selected :route route) warnings)
                      (attach-context source detected (:processing selected)
                                      (assoc (dispatch source (:bytes read-result) request
                                                detected route (:processing selected) engine)
                                             :route route)
                                      warnings))))))))))))
