(ns hc.server.documents.integration-test
  "End-to-end attachment -> documents -> HTTP extraction path.

  Uses real attachment ingestion, real PDFBox extraction and real Tesseract
  OCR.  No fake extractor and no hand-built result map."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [hc.server.app.config :as config]
            [hc.server.app.lifecycle-test-support :as lifecycle]
            [hc.server.app.system :as system]
            [hc.server.attachments :as attachments]
            [hc.server.attachments.http :as attachments-http]
            [hc.server.documents :as documents]
            [hc.server.documents.support :as support]
            [hc.server.gateway.ports :as ports])
  (:import [java.io File]
           [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-root
  []
  (str (Files/createTempDirectory "hc-documents-int-"
                                  (make-array FileAttribute 0))))

(defn- service
  [root]
  (let [counter (atom 0)]
    (attachments/service
     {:blob-store (ports/blob-store-port root)
      :extractor {:extract! documents/extract!}
      :now-fn (constantly 1700000000000)
      :id-fn #(str "att-" (swap! counter inc))})))

(defn- ingest
  [svc name mime ^bytes bytes]
  (attachments/ingest-bytes! svc {:name name :mime mime :draft-count 0} bytes))

(defn- extract-request
  [attachment selection]
  {:uri "/attachments/extract"
   :request-method :post
   :headers {"content-type" "application/edn"}
   :body (pr-str {:attachment attachment :selection selection})})

(defn- response-body
  [response]
  (edn/read-string (:body response)))

(deftest image-ingest-then-http-ocr
  (let [root (temp-root)
        svc (service root)
        image (support/text-image "Herdchestra HTTP OCR 2048" 1000 250)
        ingested (ingest svc "scan.png" "image/png" (support/png-bytes image))
        attachment (:attachment ingested)]
    (is (true? (:ok? ingested)))
    (is (= :not-attempted (get-in attachment [:extraction :status])))
    (is (= :ocr (get-in attachment [:extraction :suggested-route])))
    (let [response (attachments-http/handle
                    svc
                    (extract-request attachment
                                     {:route :ocr :ocr {:languages ["eng"]}}))
          body (response-body response)]
      (is (= 200 (:status response)))
      (is (true? (:ok? body)))
      (is (= "Herdchestra HTTP OCR 2048"
             (get-in body [:extraction :text])))
      (is (= 1 (get-in body [:extraction :page-count])))
      (is (= [1] (mapv :page (get-in body [:extraction :pages]))))
      (is (= (:blob-id attachment)
             (get-in body [:extraction :pages 0 :source :id]))))))

(deftest scanned-pdf-ingest-then-http-page-ocr
  (let [root (temp-root)
        svc (service root)
        file (support/scanned-pdf! (File. root "scanned.pdf"))
        ingested (ingest svc "scanned.pdf" "application/pdf" (support/bytes-of file))
        attachment (:attachment ingested)]
    (is (true? (:ok? ingested)))
    (is (= :failed (get-in attachment [:extraction :status])))
    (is (= :NO_TEXT (get-in attachment [:extraction :error :code])))
    (is (= :ocr (get-in attachment [:extraction :error :suggested-route])))
    (let [response (attachments-http/handle
                    svc
                    (extract-request attachment
                                     {:route :ocr :pages "1-2"
                                      :ocr {:languages ["eng"]}}))
          body (response-body response)]
      (is (= 200 (:status response)))
      (is (true? (:ok? body)))
      (is (= [1 2] (mapv :page (get-in body [:extraction :pages]))))
      (is (re-find #"Scanned alpha" (get-in body [:extraction :text])))
      (is (re-find #"Scanned beta" (get-in body [:extraction :text])))
      (is (= 2 (get-in body [:extraction :page-count]))))))

(deftest text-pdf-ingest-keeps-real-pages-without-http-ocr
  (let [root (temp-root)
        svc (service root)
        file (support/mixed-pdf! (File. root "mixed.pdf"))
        ingested (ingest svc "mixed.pdf" "application/pdf" (support/bytes-of file))
        attachment (:attachment ingested)
        extraction (:extraction attachment)]
    (is (true? (:ok? ingested)))
    (is (= :ready (:status extraction)))
    (is (= 3 (:page-count extraction)))
    (is (= [1 2 3] (mapv :page (:pages extraction))))
    (is (= [:text :no-text :text] (mapv :status (:pages extraction))))
    (is (re-find #"page one" (:text extraction)))
    (is (= [2] (get-in extraction [:warnings 0 :pages])))))

(deftest http-extraction-requires-explicit-selection
  (let [root (temp-root)
        svc (service root)
        image (support/text-image "selection required" 800 200)
        ingested (ingest svc "scan.png" "image/png" (support/png-bytes image))
        response (attachments-http/handle
                  svc
                  (extract-request (:attachment ingested) nil))
        body (response-body response)]
    (is (= 400 (:status response)))
    (is (false? (:ok? body)))
    (is (= :SELECTION_REQUIRED (:error body)))))

;; ---------------------------------------------------------------------------
;; Full application path

(defn- app-config
  []
  (let [root (temp-root)
        data (temp-root)
        static (temp-root)]
    (support/write-bytes! (File. static "index.html")
                          (.getBytes "<!doctype html><title>hc</title>" "UTF-8"))
    (config/normalize
     {:host "127.0.0.1"
      :port 0
      :data-dir data
      :workspace-root root
      :static-root static
      :blob-root (str data "/blobs")
      :worktree-root (str data "/worktrees")
      :artifact-root (str data "/artifacts")
      :storage {:path (str data "/db")}
      :log-level :warn
      :models {}})))

(defn- post-bytes!
  [url ^bytes body content-type]
  (let [client (HttpClient/newHttpClient)
        request (-> (HttpRequest/newBuilder (URI/create url))
                    (.header "content-type" content-type)
                    (.POST (HttpRequest$BodyPublishers/ofByteArray body))
                    (.build))]
    (.send client request (HttpResponse$BodyHandlers/ofString))))

(defn- post-edn!
  [url body]
  (post-bytes! url (.getBytes (pr-str body) "UTF-8") "application/edn"))

(defn- response-status
  [response]
  (.statusCode ^java.net.http.HttpResponse response))

(defn- response-text
  [response]
  (.body ^java.net.http.HttpResponse response))

(deftest full-app-image-ocr-over-http
  (let [config (app-config)
        started (system/start! config)]
    (try
      (is (false? (:blocked? started)))
      (is (string? (:url started)))
      (let [image (support/text-image "Herdchestra app OCR 2048" 1000 250)
            upload (post-bytes! (str (:url started)
                                     "/attachments?name=scan.png&draft-count=0")
                                (support/png-bytes image)
                                "image/png")
            upload-body (edn/read-string (response-text upload))
            attachment (:attachment upload-body)
            extract (post-edn! (str (:url started) "/attachments/extract")
                               {:attachment attachment
                                :selection {:route :ocr
                                            :ocr {:languages ["eng"]}}})
            body (edn/read-string (response-text extract))]
        (is (= 201 (response-status upload)))
        (is (true? (:ok? upload-body)))
        (is (= :not-attempted (get-in attachment [:extraction :status])))
        (is (= 200 (response-status extract)))
        (is (true? (:ok? body)))
        (is (= "Herdchestra app OCR 2048"
               (get-in body [:extraction :text])))
        (is (= (:blob-id attachment)
               (get-in body [:extraction :pages 0 :source :id]))))
      (finally
        (lifecycle/stop-system! started)))))
