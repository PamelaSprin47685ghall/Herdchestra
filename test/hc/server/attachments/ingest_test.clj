(ns hc.server.attachments.ingest-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hc.server.attachments :as att]
            [hc.server.attachments.media :as media]
            [hc.server.blob-store :as blob]
            [hc.server.gateway.ports :as ports]
            [hc.server.sessions.drafts :as drafts])
  (:import (java.awt.image BufferedImage)
           (java.io ByteArrayInputStream ByteArrayOutputStream InputStream)
           (java.nio.charset StandardCharsets)
           (java.nio.file Files FileVisitOption LinkOption OpenOption Path
                          StandardOpenOption)
           (java.nio.file.attribute FileAttribute)
           (java.util Arrays)
           (javax.imageio ImageIO)))

(defn- temp-root
  []
  (str (Files/createTempDirectory "hc-attachments-test-" (make-array FileAttribute 0))))

(defn- delete-tree
  [root]
  (let [path (Path/of root (make-array String 0))]
    (when (Files/exists path (make-array LinkOption 0))
      (with-open [stream (Files/walk path (make-array FileVisitOption 0))]
        (doseq [entry (->> (iterator-seq (.iterator stream))
                           (sort-by #(.getNameCount ^Path %))
                           reverse)]
          (try
            (Files/deleteIfExists entry)
            (catch Exception _ nil)))))))

(defmacro with-root
  [[root] & body]
  `(let [~root (temp-root)]
     (try
       ~@body
       (finally
         (delete-tree ~root)))))

(defn- service
  [root]
  (let [counter (atom 0)]
    (att/service {:blob-store (ports/blob-store-port root)
                  :now-fn (constantly 1700000000000)
                  :id-fn #(str "att-" (swap! counter inc))})))

(defn- ingest
  [svc name mime ^bytes bytes]
  (att/ingest-bytes! svc {:name name :mime mime :draft-count 0} bytes))

(defn- png-bytes
  [width height]
  (let [image (BufferedImage. width height BufferedImage/TYPE_INT_ARGB)
        out (ByteArrayOutputStream.)]
    (ImageIO/write image "png" out)
    (.toByteArray out)))

(deftest text-ingestion-stores-an-exact-local-copy
  (with-root [root]
    (let [svc (service root)
          bytes (.getBytes "hello 世界\n" StandardCharsets/UTF_8)
          result (ingest svc "notes.txt" "text/plain" bytes)]
      (is (:ok? result) (pr-str result))
      (let [attachment (:attachment result)]
        (is (= "text/plain" (:mime attachment)))
        (is (= (alength bytes) (:size attachment)))
        (is (:local-copy? attachment))
        (is (= :ready (get-in attachment [:extraction :status])))
        (is (= "hello 世界\n" (get-in attachment [:extraction :text])))
        (is (nil? (get-in attachment [:extraction :page-count])))
        (is (= [] (get-in attachment [:extraction :pages])))
        (is (Arrays/equals bytes (blob/read-bytes root (:blob-id attachment))))
        (is (:ok? (att/verify! svc attachment)))
        (is (:ok? (att/open-attachment svc attachment)))))))

(deftest verify-detects-same-size-byte-corruption
  (let [objects (atom {})
        svc (att/service {:blob-store (ports/memory-blob-port objects)})
        bytes (.getBytes "hello" StandardCharsets/UTF_8)
        result (att/ingest-bytes! svc {:name "a.txt" :mime "text/plain"} bytes)
        attachment (:attachment result)
        id (:blob-id attachment)
        original (get @objects id)
        replacement (byte-array (alength ^bytes original) (byte 0x78))]
    (swap! objects assoc id replacement)
    (let [verified (att/verify! svc attachment)]
      (is (false? (:ok? verified)))
      (is (= :ATTACHMENT_INTEGRITY (get-in verified [:error :code])))
      (is (= (subs id 7)
             (get-in verified [:error :details :expected-sha256]))))))

(deftest extraction-failure-keeps-suggested-route-and-selected-pages
  (with-root [root]
    (let [extractor (fn [_]
                      {:ok? false
                       :error {:code :NO_TEXT
                               :message "no text layer"
                               :suggested-route :ocr}
                       :pages [{:page 1 :status :no-text}]
                       :selected-pages [1]})
          svc (att/service {:blob-store (ports/blob-store-port root)
                            :extractor extractor})
          result (att/ingest-bytes! svc {:name "scan.pdf"
                                         :mime "application/pdf"}
                                    (.getBytes "%PDF-1.7\n" StandardCharsets/UTF_8))
          attachment (:attachment result)]
      (is (true? (:ok? result)))
      (is (= :failed (get-in attachment [:extraction :status])))
      (is (= :ocr (get-in attachment [:extraction :suggested-route])))
      (is (= [1] (get-in attachment [:extraction :selected-pages])))
      (is (true? (:extractable? attachment))))))

(deftest image-ingestion-keeps-the-original-and-prepares-a-2048-copy
  (with-root [root]
    (let [svc (service root)
          bytes (png-bytes 3000 1000)
          result (ingest svc "wide.png" "image/png" bytes)]
      (is (:ok? result) (pr-str result))
      (let [attachment (:attachment result)
            prepared (:prepared attachment)]
        (is (= 3000 (get-in attachment [:media :width])))
        (is (= 1000 (get-in attachment [:media :height])))
        (is (= :prepared (:status (:prepared-status attachment))))
        (is (= 2048 (:width prepared)))
        (is (= 683 (:height prepared)))
        (is (= "image/png" (:mime prepared)))
        (is (< (:size prepared) (:size attachment)))
        (is (blob/exists? root (:blob-id attachment)))
        (is (blob/exists? root (:blob-id prepared)))
        (let [sent (ImageIO/read (ByteArrayInputStream.
                                  (blob/read-bytes root (:blob-id prepared))))]
          (is (= 2048 (.getWidth sent)))
          (is (= 683 (.getHeight sent)))
          (is (.hasAlpha (.getColorModel sent))))))))

(defn- jpeg-bytes
  [width height]
  (let [image (BufferedImage. width height BufferedImage/TYPE_INT_RGB)
        out (ByteArrayOutputStream.)]
    (ImageIO/write image "jpeg" out)
    (.toByteArray out)))

(defn- exif-app1
  [orientation]
  (let [tiff (byte-array (concat
                          [0x49 0x49 0x2A 0x00 0x08 0x00 0x00 0x00]
                          [0x01 0x00]
                          [0x12 0x01 0x03 0x00 0x01 0x00 0x00 0x00
                           (bit-and 0xff orientation) 0x00 0x00 0x00]
                          [0x00 0x00 0x00 0x00]))
        exif (byte-array (concat [0x45 0x78 0x69 0x66 0x00 0x00] tiff))
        length (+ 2 (alength exif))]
    (byte-array (concat
                 [0xFF 0xE1
                  (bit-and 0xff (bit-shift-right length 8))
                  (bit-and 0xff length)]
                 exif))))

(deftest exif-orientation-is-applied-to-the-prepared-copy
  (with-root [root]
    (let [svc (service root)
          jpeg (jpeg-bytes 100 50)
          oriented (byte-array (concat [(aget jpeg 0) (aget jpeg 1)]
                                       (exif-app1 6)
                                       (drop 2 jpeg)))
          result (ingest svc "phone.jpg" "image/jpeg" oriented)
          prepared (get-in result [:attachment :prepared])]
      (is (:ok? result) (pr-str result))
      (is (= 6 (media/jpeg-orientation oriented)))
      (is (= :prepared (get-in result [:attachment :prepared-status :status])))
      (is (= 50 (:width prepared)))
      (is (= 100 (:height prepared)))
      (let [sent (ImageIO/read (ByteArrayInputStream.
                                (blob/read-bytes root (:blob-id prepared))))]
        (is (= 50 (.getWidth sent)))
        (is (= 100 (.getHeight sent)))))))

(deftest declared-mime-cannot-hide-the-real-bytes
  (with-root [root]
    (let [svc (service root)
          result (ingest svc "not-an-image.png" "image/png"
                         (.getBytes "plain text" StandardCharsets/UTF_8))]
      (is (:ok? result))
      (is (= "text/plain" (get-in result [:attachment :mime])))
      (is (true? (get-in result [:attachment :mime-mismatch?])))
      (is (= "image/png" (get-in result [:attachment :declared-mime]))))))

(deftest pdf-facts-are-honest-about-unknown-encryption
  (with-root [root]
    (let [svc (service root)
          result (ingest svc "scan.pdf" "application/pdf"
                         (.getBytes "%PDF-1.7\n1 0 obj\n<< /Type /Catalog >>\nendobj\n%%EOF\n"
                                    StandardCharsets/UTF_8))]
      (is (:ok? result))
      (is (= :pdf (get-in result [:attachment :media :kind])))
      (is (= :unknown (get-in result [:attachment :media :encrypted?])))
      (is (nil? (get-in result [:attachment :media :page-count]))))))

(deftest invalid-utf8-is-a-visible-extraction-failure
  (with-root [root]
    (let [svc (service root)
          bytes (byte-array [(unchecked-byte 0xC3) 0x28])
          result (ingest svc "broken.txt" "text/plain" bytes)]
      (is (:ok? result))
      (is (= :failed (get-in result [:attachment :extraction :status])))
      (is (= :ENCODING_UNKNOWN (get-in result [:attachment :extraction :error :code])))
      (is (nil? (get-in result [:attachment :extraction :text]))))))

(deftest a-stream-one-byte-over-100-mib-is-refused
  (with-root [root]
    (let [svc (service root)
          limit att/max-attachment-bytes
          in (proxy [InputStream] []
               (read
                 ([] 65)
                 ([buffer] (Arrays/fill ^bytes buffer (byte 65)) (alength ^bytes buffer))
                 ([buffer offset length]
                  (Arrays/fill ^bytes buffer (int offset) (int (+ offset length)) (byte 65))
                  length)))
          result (att/ingest-stream! svc {:name "huge.bin"
                                          :mime "application/octet-stream"
                                          :size (inc limit)
                                          :stream in})]
      (is (false? (:ok? result)))
      (is (= :ATTACHMENT_TOO_LARGE (get-in result [:error :code])))
      (is (= limit (get-in result [:error :details :limit]))))))

(deftest a-stricter-limit-bounds-the-sniff-read
  (with-root [root]
    (let [consumed (atom 0)
          in (proxy [InputStream] []
               (read
                 ([] (swap! consumed inc) 65)
                 ([buffer]
                  (swap! consumed + (alength ^bytes buffer))
                  (Arrays/fill ^bytes buffer (byte 65))
                  (alength ^bytes buffer))
                 ([buffer offset length]
                  (swap! consumed + length)
                  (Arrays/fill ^bytes buffer (int offset) (int (+ offset length))
                               (byte 65))
                  length)))
          svc (att/service {:blob-store (ports/blob-store-port root)
                            :limits {:max-bytes 10}
                            :now-fn (constantly 1)
                            :id-fn (constantly "att-small")})
          result (att/ingest-stream! svc {:name "small.bin"
                                          :mime "application/octet-stream"
                                          :stream in})]
      (is (false? (:ok? result)))
      (is (= :ATTACHMENT_TOO_LARGE (get-in result [:error :code])))
      (is (<= @consumed 11)
          "the sniff read must not consume a full 128 KiB before the limit")
      (is (empty? (:objects (blob/scan root)))))))

(deftest the-twenty-first-draft-item-is-refused-before-reading
  (with-root [root]
    (let [svc (service root)
          reads (atom 0)
          in (proxy [InputStream] []
               (read
                 ([] (swap! reads inc) 65)
                 ([buffer] (swap! reads inc) (alength ^bytes buffer))
                 ([buffer offset length] (swap! reads inc) length)))
          result (att/ingest-stream! svc {:name "a.txt" :mime "text/plain"
                                          :stream in :draft-count 20})]
      (is (false? (:ok? result)))
      (is (= :ATTACHMENT_LIMIT_EXCEEDED (get-in result [:error :code])))
      (is (zero? @reads)))))

(deftest a-real-extractor-can-supply-text-and-a-refusal-stays-visible
  (with-root [root]
    (let [calls (atom 0)
          seen (atom nil)
          extractor (fn [request]
                      (swap! calls inc)
                      (reset! seen request)
                      {:ok? true :route :text :text "page one"
                       :page-count 1
                       :pages [{:page 1 :status :text :text "page one"}]})
          svc (att/service {:blob-store (ports/blob-store-port root)
                            :extractor extractor
                            :now-fn (constantly 1)
                            :id-fn (constantly "att-x")})
          pdf (ingest svc "doc.pdf" "application/pdf" (.getBytes "%PDF-1.7\n" "UTF-8"))]
      (is (:ok? pdf))
      (is (= 1 @calls))
      (is (= {:route :text} (:selection @seen)))
      (is (= att/max-attachment-bytes (:max-bytes @seen)))
      (is (= "page one" (get-in pdf [:attachment :extraction :text])))
      (is (= 1 (get-in pdf [:attachment :extraction :page-count])))
      (let [refusing (att/service {:blob-store (ports/blob-store-port root)
                                   :extractor (fn [_] {:ok? false
                                                       :error {:code :OCR_UNAVAILABLE
                                                               :message "no OCR"}})
                                   :now-fn (constantly 1)
                                   :id-fn (constantly "att-y")})
            image (ingest refusing "scan.png" "image/png" (png-bytes 10 10))]
        (is (:ok? image))
        (is (= :not-attempted (get-in image [:attachment :extraction :status])))
        (is (= :needs-operator-choice (get-in image [:attachment :extraction :reason])))
        (is (= :ocr (get-in image [:attachment :extraction :suggested-route])))
        (is (nil? (get-in image [:attachment :extraction :text])))
        (is (= :needs-choice (:route (att/compatibility (:attachment image)
                                                        {:capabilities #{:text}}))))
        (is (true? (get-in image [:attachment :extractable?])))
        (let [explicit (att/extract-attachment! refusing (:attachment image)
                                               {:route :ocr
                                                :ocr {:languages ["eng"]}})]
          (is (false? (:ok? explicit)))
          (is (= :needs-service (get-in explicit [:extraction :status])))
          (is (nil? (get-in explicit [:extraction :text]))))))))

(deftest workspace-association-detects-change-and-snapshot-preserves-the-version
  (with-root [root]
    (let [svc (service root)
          workspace (str (Files/createTempDirectory "hc-att-ws-" (make-array FileAttribute 0)))
          path (.resolve (Path/of workspace (make-array String 0)) "notes.txt")]
      (try
        (Files/writeString path "version one" StandardCharsets/UTF_8
                           (make-array OpenOption 0))
        (let [association (att/workspace-attachment!
                           svc {:workspace-root workspace
                                :relative-path "notes.txt"
                                :snapshot? false
                                :name "notes.txt"})
              attachment (:attachment association)]
          (is (:ok? association))
          (is (false? (:local-copy? attachment)))
          (is (nil? (:blob-id attachment)))
          (is (map? (get-in attachment [:workspace :stamp])))
          (is (:ok? (att/refresh-workspace! attachment)))
          (Files/writeString path "version two" StandardCharsets/UTF_8
                             (make-array OpenOption 0))
          (let [changed (att/refresh-workspace! attachment)]
            (is (false? (:ok? changed)))
            (is (true? (:changed? changed)))
            (is (= :needs-choice (get-in changed [:attachment :status]))))
          (let [current-association
                (att/workspace-attachment!
                 svc {:workspace-root workspace
                      :relative-path "notes.txt"
                      :snapshot? false
                      :name "notes.txt"})
                restored (att/refresh-workspace!
                          (assoc (:attachment current-association)
                                 :status :needs-choice))]
            (is (:ok? restored))
            (is (= :ready (get-in restored [:attachment :status])))
            (is (true? (get-in restored [:attachment :workspace-verified?]))))
          (let [stale-snapshot (att/snapshot-workspace! svc attachment)]
            (is (false? (:ok? stale-snapshot)))
            (is (= :WORKSPACE_FILE_CHANGED (get-in stale-snapshot [:error :code]))))
          (let [reassociated (att/workspace-attachment!
                              svc {:workspace-root workspace
                                   :relative-path "notes.txt"
                                   :snapshot? false
                                   :name "notes.txt"})
                snapshot (att/snapshot-workspace! svc (:attachment reassociated))]
            (is (:ok? snapshot))
            (is (true? (get-in snapshot [:attachment :local-copy?])))
            (is (string? (get-in snapshot [:attachment :blob-id])))
            (is (= "version two" (get-in snapshot [:attachment :extraction :text])))))
        (finally
          (delete-tree workspace))))))

(deftest missing-file-is-a-value-not-an-exception
  (with-root [root]
    (let [svc (service root)
          result (att/ingest-file! svc {:path (str root "/missing.txt")})]
      (is (false? (:ok? result)))
      (is (= :ATTACHMENT_SOURCE_UNREADABLE (get-in result [:error :code]))))))

(deftest workspace-path-traversal-is-refused
  (with-root [root]
    (let [svc (service root)
          workspace (str (Files/createTempDirectory "hc-att-ws-" (make-array FileAttribute 0)))]
      (try
        (let [result (att/workspace-attachment!
                      svc {:workspace-root workspace
                           :relative-path "../escape.txt"
                           :snapshot? false
                           :name "escape.txt"})]
          (is (false? (:ok? result)))
          (is (= :WORKSPACE_PATH_ESCAPE (get-in result [:error :code]))))
        (finally
          (delete-tree workspace))))))

(deftest http-route-returns-a-descriptor-and-refuses-limits
  (with-root [root]
    (let [svc (service root)
          ok (att/handle-http svc {:request-method :post
                                   :uri "/attachments"
                                   :query-string "name=http.txt&draft-count=0"
                                   :headers {"content-type" "text/plain"
                                             "content-length" "9"}
                                   :body (ByteArrayInputStream.
                                          (.getBytes "http text" StandardCharsets/UTF_8))})
          too-many (att/handle-http svc {:request-method :post
                                         :uri "/attachments"
                                         :query-string "name=next.txt&draft-count=20"
                                         :headers {"content-type" "text/plain"}
                                         :body (ByteArrayInputStream. (byte-array 1))})
          too-large (att/handle-http svc {:request-method :post
                                          :uri "/attachments"
                                          :query-string "name=huge.bin"
                                          :headers {"content-type" "application/octet-stream"
                                                    "content-length" (str (inc att/max-attachment-bytes))}
                                          :body (ByteArrayInputStream. (byte-array 1))})]
      (is (= 201 (:status ok)))
      (is (true? (-> (:body ok) edn/read-string :ok?)))
      (is (= 409 (:status too-many)))
      (is (= :ATTACHMENT_LIMIT_EXCEEDED (-> (:body too-many) edn/read-string :error)))
      (is (= 413 (:status too-large)))
      (is (= :ATTACHMENT_TOO_LARGE (-> (:body too-large) edn/read-string :error))))))

(deftest descriptors-fit-the-session-draft-contract
  (with-root [root]
    (let [svc (service root)
          descriptors (mapv (fn [index]
                              (:attachment
                               (ingest svc (str "f" index ".txt") "text/plain"
                                       (.getBytes (str "file " index) StandardCharsets/UTF_8))))
                            (range 20))
          draft (drafts/new-draft "window-1" 1)
          accepted (drafts/update-draft draft {:attachments descriptors} 2)
          too-many (drafts/update-draft draft
                                        {:attachments
                                         (conj descriptors
                                               (assoc (first descriptors) :attachment-id "att-extra"))}
                                        3)]
      (is (:ok? accepted) (pr-str accepted))
      (is (= 20 (count (get-in accepted [:draft :attachments]))))
      (is (false? (:ok? too-many)))
      (is (= :ATTACHMENT_LIMIT_EXCEEDED (get-in too-many [:error :code]))))))

(deftest http-extraction-route-is-explicit-and-password-safe
  (with-root [root]
    (let [seen (atom nil)
          extractor (fn [request]
                      (reset! seen request)
                      {:ok? true :route :ocr :text "ocr text"
                       :page-count 1
                       :pages [{:page 1 :status :text :text "ocr text"}]})
          svc (att/service {:blob-store (ports/blob-store-port root)
                            :extractor extractor
                            :now-fn (constantly 1)
                            :id-fn (constantly "att-ocr")})
          image (ingest svc "scan.png" "image/png" (png-bytes 10 10))
          attachment (:attachment image)
          body (pr-str {:attachment (select-keys attachment
                                                 [:attachment-id :blob-id :name :mime
                                                  :size :local-copy?])
                        :selection {:route :ocr :ocr {:languages ["eng"]}}
                        :password "secret-never-echoed"})
          response (att/handle-http svc {:request-method :post
                                         :uri "/attachments/extract"
                                         :headers {"content-type" "application/edn"}
                                         :body body})
          parsed (edn/read-string (:body response))]
      (is (= 200 (:status response)) (pr-str parsed))
      (is (true? (:ok? parsed)))
      (is (= "ocr text" (get-in parsed [:extraction :text])))
      (is (= {:route :ocr :ocr {:languages ["eng"]}}
             (get-in @seen [:selection])))
      (is (= "secret-never-echoed" (get-in @seen [:password])))
      (is (not (str/includes? (:body response) "secret-never-echoed")))
      (is (not (str/includes? (:body response) "password")))
      (let [missing (att/handle-http svc {:request-method :post
                                          :uri "/attachments/extract"
                                          :headers {"content-type" "application/edn"}
                                          :body (pr-str {:attachment attachment})})]
        (is (= 400 (:status missing)))
        (is (= :SELECTION_REQUIRED (-> missing :body edn/read-string :error)))))))

(deftest extractor-exceptions-do-not-echo-the-password
  (with-root [root]
    (let [svc (att/service {:blob-store (ports/blob-store-port root)
                            :extractor (fn [_]
                                         (throw (ex-info "password: secret-value" {})))
                            :now-fn (constantly 1)
                            :id-fn (constantly "att-throw")})
          result (att/ingest-bytes! svc {:name "a.txt"
                                         :mime "text/plain"}
                                    (.getBytes "text" StandardCharsets/UTF_8))]
        (is (true? (:ok? result)))
        (is (= :failed (get-in result [:attachment :extraction :status])))
        (is (not (str/includes? (pr-str result) "secret-value")))
        (is (= "the extractor failed"
             (get-in result [:attachment :extraction :error :message]))))))

(deftest image-pixel-limit-is-checked-before-decode
  (let [bytes (png-bytes 10 10)
        result (media/prepare-image
                (ByteArrayInputStream. bytes)
                {:head-bytes bytes
                 :max-pixels 1})]
    (is (false? (:ok? result)))
    (is (= :image-too-large (:reason result)))))

(deftest http-extraction-refusal-keeps-text-empty
  (with-root [root]
    (let [svc (att/service {:blob-store (ports/blob-store-port root)
                            :extractor (fn [_] {:ok? false
                                                :error {:code :OCR_UNAVAILABLE
                                                        :message "no OCR"}})
                            :now-fn (constantly 1)
                            :id-fn (constantly "att-refuse")})
          image (ingest svc "scan.png" "image/png" (png-bytes 10 10))
          attachment (:attachment image)
          response (att/handle-http svc {:request-method :post
                                         :uri "/attachments/extract"
                                         :headers {"content-type" "application/edn"}
                                         :body (pr-str {:attachment (select-keys attachment
                                                                                   [:attachment-id :blob-id
                                                                                    :name :mime :size
                                                                                    :local-copy?])
                                                        :selection {:route :ocr}})})
          parsed (edn/read-string (:body response))]
      (is (= 503 (:status response)) (pr-str parsed))
      (is (false? (:ok? parsed)))
      (is (nil? (get-in parsed [:extraction :text])))
      (is (= :OCR_UNAVAILABLE (get-in parsed [:error]))))))

(deftest http-route-tolerates-double-leading-slash-from-origin-url
  (with-root [root]
    (let [svc (att/service {:blob-store (ports/blob-store-port root)
                            :extractor (fn [_] {:ok? true :route :text :text "double"})
                            :now-fn (constantly 1)
                            :id-fn (constantly "att-double")})
          response (att/handle-http svc {:request-method :post
                                         :uri "//attachments"
                                         :query-string "name=double.txt"
                                         :headers {"content-type" "text/plain"}
                                         :body (ByteArrayInputStream.
                                                (.getBytes "double" StandardCharsets/UTF_8))})
          extract (att/handle-http svc {:request-method :post
                                        :uri "//attachments/extract"
                                        :headers {"content-type" "application/edn"}
                                        :body (pr-str {:attachment (:attachment (edn/read-string (:body response)))
                                                       :selection {:route :text}})})]
      (is (= 201 (:status response)))
      (is (= 200 (:status extract))))))

(deftest http-name-is-decoded-only-once
  (with-root [root]
    (let [svc (service root)
          response (att/handle-http svc {:request-method :post
                                         :uri "/attachments"
                                         :query-string "name=a%2520b.txt"
                                         :headers {"content-type" "text/plain"}
                                         :body (ByteArrayInputStream.
                                                (.getBytes "name" StandardCharsets/UTF_8))})
          attachment (:attachment (edn/read-string (:body response)))]
      (is (= 201 (:status response)))
      (is (= "a%20b.txt" (:name attachment))))))

(deftest exif-orientation-is-read-from-a-real-jpeg-app1-segment
  (let [tiff (byte-array (concat
                          [0x49 0x49 0x2A 0x00 0x08 0x00 0x00 0x00]
                          [0x01 0x00]
                          [0x12 0x01 0x03 0x00 0x01 0x00 0x00 0x00 0x06 0x00 0x00 0x00]
                          [0x00 0x00 0x00 0x00]))
        exif (byte-array (concat [0x45 0x78 0x69 0x66 0x00 0x00] tiff))
        app1-length (+ 2 (alength exif))
        head (byte-array (concat [0xFF 0xD8 0xFF 0xE1
                                  (bit-and 0xff (bit-shift-right app1-length 8))
                                  (bit-and 0xff app1-length)]
                                 exif))]
    (is (= 6 (media/jpeg-orientation head)))
    (is (= 1 (media/jpeg-orientation (byte-array [0xFF 0xD8]))))))
