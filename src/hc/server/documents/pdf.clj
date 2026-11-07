(ns hc.server.documents.pdf
  "PDF text and page access through Apache PDFBox.

  Page numbers are 1-based and always refer to the original document.  A page
  with no extractable text is reported as `:no-text`; it is never counted as
  read.  Encrypted documents are opened only with an explicitly supplied
  password, and extraction permissions are checked before text is read."
  (:require [clojure.string :as str]
            [hc.server.documents.error :as error]
            [hc.server.documents.pages :as pages])
  (:import [java.io IOException]
           [org.apache.pdfbox Loader]
           [org.apache.pdfbox.pdmodel PDDocument]
           [org.apache.pdfbox.pdmodel.encryption InvalidPasswordException]
           [org.apache.pdfbox.rendering ImageType PDFRenderer]
           [org.apache.pdfbox.text PDFTextStripper]))

(def default-max-pages 5000)

(defn- load-document
  [^bytes bytes password max-pages]
  (let [password (when (and (string? password) (not (str/blank? password)))
                   password)]
    (try
      (let [document (if password
                       (Loader/loadPDF bytes password)
                       (Loader/loadPDF bytes))
            page-count (.getNumberOfPages document)]
        (cond
          (zero? page-count)
          (do
            (.close document)
            (error/failure :PDF_UNREADABLE "the PDF has no pages"))

          (> page-count max-pages)
          (do
            (.close document)
            (error/failure :PDF_TOO_MANY_PAGES
                           "the PDF has more pages than one extraction allows"
                           {:page-count page-count :max-pages max-pages}))

          :else
          {:ok? true
           :document document
           :page-count page-count
           :encrypted? (.isEncrypted document)
           :password-supplied? (some? password)}))
      (catch InvalidPasswordException _
        (if password
          (error/failure :WRONG_PASSWORD
                         "the PDF password was not accepted"
                         {:encrypted? true})
          (error/failure :NEEDS_PASSWORD
                         "the PDF is encrypted and needs a password"
                         {:encrypted? true})))
      (catch IOException t
        (error/failure :PDF_UNREADABLE
                       "the PDF could not be opened"
                       {:reason (error/safe-message t)}))
      (catch Exception t
        (error/failure :PDF_UNREADABLE
                       "the PDF could not be opened"
                       {:reason (error/safe-message t)})))))

(defn with-document
  "Open a PDF, call `(f document facts)`, and close it in every case.

  `facts` contains `:page-count`, `:encrypted?` and `:password-supplied?`.
  The document is never returned to the caller."
  ([bytes password f]
   (with-document bytes password {} f))
  ([bytes password {:keys [max-pages] :or {max-pages default-max-pages}} f]
   (let [max-pages (or max-pages default-max-pages)
         opened (load-document bytes password max-pages)]
     (if-not (:ok? opened)
       opened
       (let [document (:document opened)]
         (try
           (f document (dissoc opened :document))
           (finally
             (.close document))))))))

(defn extraction-allowed?
  [^PDDocument document]
  (or (not (.isEncrypted document))
      (.canExtractContent (.getCurrentAccessPermission document))))

(defn- permission-failure
  []
  (error/failure :PDF_EXTRACTION_FORBIDDEN
                 "the PDF permissions do not allow content extraction"
                 {:encrypted? true}))

(defn- extract-page
  [^PDDocument document page]
  (try
    (let [stripper (PDFTextStripper.)]
      (.setStartPage stripper (int page))
      (.setEndPage stripper (int page))
      (let [text (.getText stripper document)]
        {:page page
         :status (if (str/blank? text) :no-text :text)
         :text (or text "")}))
    (catch Exception t
      {:page page
       :status :error
       :text ""
       :error {:code :PAGE_EXTRACTION_FAILED
               :message (error/safe-message t)}})))

(defn- page-warnings
  [results]
  (let [no-text (->> results (filter #(= :no-text (:status %))) (mapv :page))
        failed (->> results (filter #(= :error (:status %))) (mapv :page))]
    (cond-> []
      (seq no-text)
      (conj (error/warning :PAGES_WITHOUT_TEXT
                           "some pages have no extractable text"
                           {:pages no-text}))

      (seq failed)
      (conj (error/warning :PAGES_UNREADABLE
                           "some pages could not be extracted"
                           {:pages failed})))))

(defn extract-text
  "Extract text from selected pages.

  Arguments:
  - `:bytes` PDF bytes.
  - `:password` optional explicit password; never returned.
  - `:selected` the `:pages` value from `hc.server.documents.pages/parse-range`
    (nil means every page).
  - `:max-pages` default 5000.

  Returns `{:ok? true :page-count n :pages [...] :text ...}` or a refusal.
  If every selected page is empty, the refusal is `:NO_TEXT` and suggests
  `:ocr`; it does not pretend the document was read."
  [{:keys [bytes password selected max-pages]
    :or {max-pages default-max-pages}}]
  (let [max-pages (or max-pages default-max-pages)]
    (with-document bytes password {:max-pages max-pages}
    (fn [^PDDocument document facts]
      (if-not (extraction-allowed? document)
        (permission-failure)
        (let [page-count (:page-count facts)
              invalid (pages/validate selected page-count {:max-pages max-pages})]
          (if invalid
            invalid
            (let [selected-pages (pages/selected {:pages selected} page-count)
                  results (mapv #(extract-page document %) selected-pages)
                  text (->> results
                            (filter #(= :text (:status %)))
                            (map :text)
                            (str/join "\n\n"))
                  warnings (cond-> (page-warnings results)
                             (:encrypted? facts)
                             (conj (error/warning :PDF_ENCRYPTED_OPENED
                                                  (if (:password-supplied? facts)
                                                    "the PDF was opened with the supplied password"
                                                    "the PDF was encrypted and opened without a password")
                                                  {:password-supplied? (:password-supplied? facts)})))]
              (if (str/blank? text)
                (merge (error/failure :NO_TEXT
                                      "the selected pages contain no extractable text"
                                      {:suggested-route :ocr})
                       {:page-count page-count
                        :pages results
                        :selected-pages selected-pages
                        :warnings warnings})
                {:ok? true
                 :route :text
                 :page-count page-count
                 :pages results
                 :selected-pages selected-pages
                 :text text
                 :encrypted? (:encrypted? facts)
                 :warnings warnings})))))))))

(defn renderer
  [^PDDocument document]
  (PDFRenderer. document))

(defn page-pixels
  "Approximate raster size for a page at `dpi`, or nil when the box is unreadable."
  [^PDDocument document page dpi]
  (try
    (let [pdf-page (.getPage (.getPages document) (dec (int page)))
          box (.getMediaBox pdf-page)
          width (* (/ (.getWidth box) 72.0) (double dpi))
          height (* (/ (.getHeight box) 72.0) (double dpi))]
      (long (* width height)))
    (catch Exception _ nil)))

(defn render-page
  "Render one 1-based page to an RGB BufferedImage."
  [^PDFRenderer renderer page dpi]
  (.renderImageWithDPI renderer (dec (int page)) (float dpi) ImageType/RGB))
