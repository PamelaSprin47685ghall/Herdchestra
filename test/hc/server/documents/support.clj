(ns hc.server.documents.support
  "Real sample builders for document tests.

  Nothing here is a mock extractor: the helpers write actual PDF, JPEG and PNG
  bytes that the production adapters parse."
  (:import [java.awt Color Font RenderingHints]
           [java.awt.image BufferedImage]
           [java.io ByteArrayOutputStream File]
           [java.nio.file Files OpenOption]
           [javax.imageio ImageIO]
           [org.apache.pdfbox.pdmodel PDDocument PDPage PDPageContentStream]
           [org.apache.pdfbox.pdmodel.common PDRectangle]
           [org.apache.pdfbox.pdmodel.encryption AccessPermission
            StandardProtectionPolicy]
           [org.apache.pdfbox.pdmodel.font PDType1Font Standard14Fonts$FontName]
           [org.apache.pdfbox.pdmodel.graphics.image LosslessFactory
            PDImageXObject]))

(defn temp-dir
  []
  (.toFile (Files/createTempDirectory "hc-documents-test-"
                                      (make-array java.nio.file.attribute.FileAttribute 0))))

(defn write-bytes!
  [^File file ^bytes bytes]
  (Files/write (.toPath file) bytes (make-array OpenOption 0))
  file)

(defn bytes-of
  [^File file]
  (Files/readAllBytes (.toPath file)))

(defn text-image
  "A white image with one line of text.  The text is large enough for a real
  OCR engine to read without training data tricks."
  ([text]
   (text-image text 1200 300))
  ([^String text width height]
   (let [image (BufferedImage. (int width) (int height) BufferedImage/TYPE_INT_ARGB)
         graphics (.createGraphics image)]
     (try
       (.setRenderingHint graphics RenderingHints/KEY_TEXT_ANTIALIASING
                          RenderingHints/VALUE_TEXT_ANTIALIAS_ON)
       (.setColor graphics Color/WHITE)
       (.fillRect graphics 0 0 (int width) (int height))
       (.setColor graphics Color/BLACK)
       (.setFont graphics (Font. "SansSerif" Font/BOLD 64))
       (.drawString graphics text 30 (int (/ height 2)))
       image
       (finally
         (.dispose graphics))))))

(defn split-color-image
  "Left half red, right half blue.  Used to verify EXIF orientation."
  [width height]
  (let [image (BufferedImage. (int width) (int height) BufferedImage/TYPE_INT_RGB)
        graphics (.createGraphics image)]
    (try
      (.setColor graphics Color/RED)
      (.fillRect graphics 0 0 (quot (int width) 2) (int height))
      (.setColor graphics Color/BLUE)
      (.fillRect graphics (quot (int width) 2) 0 (int width) (int height))
      image
      (finally
        (.dispose graphics)))))

(defn png-bytes
  [^BufferedImage image]
  (let [output (ByteArrayOutputStream.)]
    (ImageIO/write image "png" output)
    (.toByteArray output)))

(defn jpeg-bytes
  [^BufferedImage image]
  (let [output (ByteArrayOutputStream.)]
    (ImageIO/write image "jpeg" output)
    (.toByteArray output)))

(defn- put-u16!
  [^bytes array index value]
  (aset array index (unchecked-byte (bit-shift-right value 8)))
  (aset array (inc index) (unchecked-byte value)))

(defn- put-u32!
  [^bytes array index value]
  (aset array index (unchecked-byte (bit-shift-right value 24)))
  (aset array (+ index 1) (unchecked-byte (bit-shift-right value 16)))
  (aset array (+ index 2) (unchecked-byte (bit-shift-right value 8)))
  (aset array (+ index 3) (unchecked-byte value)))

(defn jpeg-with-orientation
  "Insert a minimal EXIF APP1 segment with an orientation tag into a JPEG."
  [^BufferedImage image orientation]
  (let [jpeg (jpeg-bytes image)
        payload-length 32
        segment-length (+ payload-length 2)
        segment (byte-array (+ 2 2 payload-length))
        _ (do
            (aset segment 0 (unchecked-byte 0xFF))
            (aset segment 1 (unchecked-byte 0xE1))
            (put-u16! segment 2 segment-length)
            ;; Exif\0\0
            (aset segment 4 (unchecked-byte 0x45))
            (aset segment 5 (unchecked-byte 0x78))
            (aset segment 6 (unchecked-byte 0x69))
            (aset segment 7 (unchecked-byte 0x66))
            (aset segment 8 (unchecked-byte 0x00))
            (aset segment 9 (unchecked-byte 0x00))
            ;; big-endian TIFF header, IFD at offset 8
            (aset segment 10 (unchecked-byte 0x4D))
            (aset segment 11 (unchecked-byte 0x4D))
            (put-u16! segment 12 42)
            (put-u32! segment 14 8)
            ;; one IFD entry
            (put-u16! segment 18 1)
            (put-u16! segment 20 0x0112)
            (put-u16! segment 22 3)
            (put-u32! segment 24 1)
            (put-u16! segment 28 orientation)
            (put-u16! segment 30 0)
            (put-u32! segment 32 0))
        output (ByteArrayOutputStream.)]
    (.write output jpeg 0 2)
    (.write output segment)
    (.write output jpeg 2 (- (alength jpeg) 2))
    (.toByteArray output)))

(defn- add-text-page!
  [^PDDocument document ^String text]
  (let [page (PDPage. PDRectangle/LETTER)
        content (PDPageContentStream. document page)]
    (.beginText content)
    (.setFont content (PDType1Font. Standard14Fonts$FontName/HELVETICA) 24)
    (.newLineAtOffset content 72 700)
    (.showText content text)
    (.endText content)
    (.close content)
    (.addPage document page)))

(defn- add-image-page!
  [^PDDocument document ^BufferedImage image]
  (let [page (PDPage. PDRectangle/LETTER)
        image-object (LosslessFactory/createFromImage document image)
        content (PDPageContentStream. document page)]
    (.drawImage content ^PDImageXObject image-object
                (float 72) (float 600) (float 500) (float 125))
    (.close content)
    (.addPage document page)))

(defn mixed-pdf!
  "Three pages: text, a scanned image, text.  Page 2 has no text layer."
  [^File file]
  (with-open [document (PDDocument.)]
    (add-text-page! document "Herdchestra page one")
    (add-image-page! document (text-image "Scanned Herdchestra 2048" 1200 300))
    (add-text-page! document "Herdchestra page three")
    (.save document file))
  file)

(defn scanned-pdf!
  "Two image-only pages."
  [^File file]
  (with-open [document (PDDocument.)]
    (add-image-page! document (text-image "Scanned alpha 2048" 1200 300))
    (add-image-page! document (text-image "Scanned beta 2048" 1200 300))
    (.save document file))
  file)

(defn encrypted-pdf!
  "A one-page encrypted PDF with separate owner and user passwords."
  [^File file owner-password user-password]
  (with-open [document (PDDocument.)]
    (add-text-page! document "Herdchestra secret page")
    (let [permissions (doto (AccessPermission.)
                        (.setCanExtractContent true))
          policy (StandardProtectionPolicy. owner-password user-password permissions)]
      (.setEncryptionKeyLength policy 256)
      (.setPreferAES policy true)
      (.protect document policy)
      (.save document file)))
  file)

(defn restricted-pdf!
  "An encrypted PDF whose user permission forbids content extraction."
  [^File file owner-password user-password]
  (with-open [document (PDDocument.)]
    (add-text-page! document "Herdchestra restricted page")
    (let [permissions (doto (AccessPermission.)
                        (.setCanExtractContent false)
                        (.setCanModify false)
                        (.setCanPrint false)
                        (.setCanFillInForm false)
                        (.setCanModifyAnnotations false)
                        (.setCanAssembleDocument false))
          policy (StandardProtectionPolicy. owner-password user-password permissions)]
      (.setEncryptionKeyLength policy 256)
      (.setPreferAES policy true)
      (.protect document policy)
      (.save document file)))
  file)
