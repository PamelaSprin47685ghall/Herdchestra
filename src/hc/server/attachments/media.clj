(ns hc.server.attachments.media
  "Byte inspection and real local media preparation.

  This namespace answers only questions it can answer from the bytes:

  - the effective media type, using magic numbers rather than trusting a
    filename or a client-declared type;
  - image dimensions, frame count and readability through ImageIO;
  - a real downscaled image copy that preserves alpha and applies EXIF
    orientation before the operator sees or sends it;
  - strict UTF-8 text, with invalid bytes refused rather than silently
    replaced by U+FFFD.

  It does not perform OCR, PDF text extraction, audio transcription or video
  frame extraction.  Those need a real document/media service; claiming them
  here would turn an unknown into a false success."
  (:require [clojure.string :as str]
            [hc.server.attachments.model :as model])
  (:import (java.awt Color RenderingHints)
           (java.awt.image BufferedImage)
           (java.io ByteArrayInputStream ByteArrayOutputStream FilterInputStream
                    InputStream PushbackInputStream)
           (java.nio ByteBuffer)
           (java.nio.charset CharacterCodingException CodingErrorAction
                             StandardCharsets)
           (java.nio.file Files OpenOption Path)
           (java.util Arrays)
           (javax.imageio ImageIO ImageWriteParam)
           (javax.imageio.stream ImageInputStream MemoryCacheImageInputStream)))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------------------
;; Bounded reads

(defn pushback-head
  "Read up to N bytes from IN and return `[pushback-stream head-bytes]`.

  The bytes are pushed back, so the caller can pass the returned stream to
  the blob store and still store the complete object."
  ^PushbackInputStream [^InputStream in n]
  (let [n (int (max 1 n))
        pb (PushbackInputStream. in n)
        buffer (byte-array n)
        read (loop [offset 0]
               (if (>= offset n)
                 offset
                 (let [value (.read pb buffer offset (- n offset))]
                   (if (neg? value)
                     offset
                     (recur (+ offset value))))))]
    (.unread pb buffer 0 read)
    [pb (Arrays/copyOf buffer (int read))]))

(defn read-up-to
  "Read at most N bytes from IN.  The stream is not closed."
  ^bytes [^InputStream in n]
  (let [n (int (max 0 n))
        out (ByteArrayOutputStream. (min n 65536))
        buffer (byte-array 8192)]
    (loop [remaining n]
      (when (pos? remaining)
        (let [value (.read in buffer 0 (min remaining (alength buffer)))]
          (when (pos? value)
            (.write out buffer 0 value)
            (recur (- remaining value))))))
    (.toByteArray out)))

;; ---------------------------------------------------------------------------
;; Media type sniffing

(defn- u8
  ^long [^bytes bytes index]
  (if (< index (alength bytes))
    (bit-and 0xff (aget bytes index))
    0))

(defn- ascii?
  [^bytes bytes offset text]
  (let [n (count text)]
    (and (<= (+ offset n) (alength bytes))
         (every? true?
                 (map-indexed (fn [i ch]
                                (= (u8 bytes (+ offset i))
                                   (bit-and 0xff (int ch))))
                              text)))))

(defn- printable-text?
  [^bytes bytes]
  (and (pos? (alength bytes))
       (let [sample (min (alength bytes) 4096)
             counts (reduce (fn [{:keys [printable zero] :as acc} i]
                              (let [b (u8 bytes i)]
                                (cond
                                  (zero? b) (update acc :zero inc)
                                  (or (and (>= b 0x20) (< b 0x7f))
                                      (= b 0x09) (= b 0x0a) (= b 0x0d)
                                      (>= b 0xc2)) (update acc :printable inc)
                                  :else acc)))
                            {:printable 0 :zero 0}
                            (range sample))]
         (and (zero? (:zero counts))
              (>= (:printable counts) (* 0.9 sample))))))

(defn sniff-mime
  "Infer a media type from the first bytes.  Returns nil when the bytes carry
  no recognizable signature."
  [^bytes head]
  (cond
    (ascii? head 0 "%PDF-") "application/pdf"
    (and (= 0x89 (u8 head 0)) (= 0x50 (u8 head 1))
         (= 0x4e (u8 head 2)) (= 0x47 (u8 head 3))
         (= 0x0d (u8 head 4)) (= 0x0a (u8 head 5))
         (= 0x1a (u8 head 6)) (= 0x0a (u8 head 7))) "image/png"
    (and (= 0xff (u8 head 0)) (= 0xd8 (u8 head 1))
         (= 0xff (u8 head 2))) "image/jpeg"
    (or (ascii? head 0 "GIF87a") (ascii? head 0 "GIF89a")) "image/gif"
    (and (ascii? head 0 "RIFF") (ascii? head 8 "WEBP")) "image/webp"
    (and (ascii? head 0 "RIFF") (ascii? head 8 "WAVE")) "audio/wav"
    (and (ascii? head 0 "RIFF") (ascii? head 8 "AVI ")) "video/x-msvideo"
    (ascii? head 0 "OggS") "audio/ogg"
    (ascii? head 0 "fLaC") "audio/flac"
    (and (ascii? head 0 "ID3")) "audio/mpeg"
    (and (= 0xff (u8 head 0))
         (contains? #{0xfb 0xf3 0xf2} (u8 head 1))) "audio/mpeg"
    (and (ascii? head 0 "PK") (= 0x03 (u8 head 2)) (= 0x04 (u8 head 3)))
    "application/zip"
    (ascii? head 0 "Rar!") "application/x-rar-compressed"
    (and (= 0x37 (u8 head 0)) (= 0x7a (u8 head 1))
         (= 0xbc (u8 head 2)) (= 0xaf (u8 head 3))) "application/x-7z-compressed"
    (and (= 0x1f (u8 head 0)) (= 0x8b (u8 head 1))) "application/gzip"
    (and (ascii? head 4 "ftyp")) "video/mp4"
    (and (= 0x1a (u8 head 0)) (= 0x45 (u8 head 1))
         (= 0xdf (u8 head 2)) (= 0xa3 (u8 head 3))) "video/webm"
    (ascii? head 0 "{\\rtf") "application/rtf"
    (or (ascii? head 0 "<svg") (and (ascii? head 0 "<?xml")
                                    (str/includes? (String. head StandardCharsets/UTF_8)
                                                   "<svg"))) "image/svg+xml"
    (or (ascii? head 0 "<!doctype html") (ascii? head 0 "<!DOCTYPE html")
        (ascii? head 0 "<html")) "text/html"
    (printable-text? head) "text/plain"
    :else nil))

(defn- family
  [mime]
  (some-> mime (str/split #"/" 2) first))

(defn- compatible-mime?
  [declared sniffed]
  (let [declared (model/normalize-mime declared)
        sniffed (model/normalize-mime sniffed)]
    (or (nil? declared)
        (= declared sniffed)
        (and (or (= "application/octet-stream" declared)
                 (= "binary/octet-stream" declared))
             (some? sniffed))
        (and (= (family declared) (family sniffed))
             (contains? #{"image" "audio" "video"} (family declared)))
        (and (= "text/plain" sniffed)
             (model/text-mime? declared nil))
        (and (= "application/zip" sniffed)
             (contains? #{"application/zip" "application/x-zip-compressed"}
                        declared)))))

(defn resolve-mime
  "Choose the effective media type.

  A specific declared type wins only when the magic bytes are compatible.
  A generic or missing declaration is replaced by the sniffed type.  A clear
  mismatch is reported and the sniffed type wins, because routing a ZIP as an
  image would make the model integration lie."
  [declared sniffed name]
  (let [declared (model/normalize-mime declared)
        sniffed (model/normalize-mime sniffed)
        declared (if (and (nil? declared)
                          (model/text-mime? nil name))
                   "text/plain"
                   declared)]
    (cond
      (nil? sniffed) {:mime (or declared "application/octet-stream")
                      :declared-mime declared
                      :sniffed-mime nil
                      :mismatch? false}
      (compatible-mime? declared sniffed)
      {:mime (or declared sniffed)
       :declared-mime declared
       :sniffed-mime sniffed
       :mismatch? false}
      :else
      {:mime sniffed
       :declared-mime declared
       :sniffed-mime sniffed
       :mismatch? true})))

;; ---------------------------------------------------------------------------
;; Images

(def ^:private orientation-1 1)

(defn- exif-orientation
  "Read the EXIF orientation tag from a JPEG APP1 segment.

  Returns 1 when the tag is absent or unreadable.  Only the first 128 KiB are
  examined, which is where camera and phone JPEG writers put EXIF."
  [^bytes head]
  (try
    (when (and (> (alength head) 4)
               (= 0xff (u8 head 0)) (= 0xd8 (u8 head 1)))
      (loop [offset 2]
        (when (< (+ offset 4) (alength head))
          (let [prefix (u8 head offset)
                marker (u8 head (inc offset))
                length (bit-or (bit-shift-left (u8 head (+ offset 2)) 8)
                               (u8 head (+ offset 3)))]
            (cond
              (not= 0xff prefix) nil
              (<= length 2) nil
              (= marker 0xe1)
              (let [start (+ offset 4)
                    end (min (alength head) (+ offset 2 length))]
                (when (and (>= (- end start) 14)
                           (ascii? head start "Exif")
                           (zero? (u8 head (+ start 4)))
                           (zero? (u8 head (+ start 5))))
                  (let [tiff (+ start 6)
                        little? (= 0x49 (u8 head tiff))
                        u16 (fn [i] (if little?
                                      (bit-or (u8 head i)
                                              (bit-shift-left (u8 head (inc i)) 8))
                                      (bit-or (bit-shift-left (u8 head i) 8)
                                              (u8 head (inc i)))))
                        u32 (fn [i] (if little?
                                      (bit-or (u8 head i)
                                              (bit-shift-left (u8 head (+ i 1)) 8)
                                              (bit-shift-left (u8 head (+ i 2)) 16)
                                              (bit-shift-left (u8 head (+ i 3)) 24))
                                      (bit-or (bit-shift-left (u8 head i) 24)
                                              (bit-shift-left (u8 head (+ i 1)) 16)
                                              (bit-shift-left (u8 head (+ i 2)) 8)
                                              (u8 head (+ i 3)))))
                        ifd (+ tiff (u32 (+ tiff 4)))
                        entries (u16 ifd)]
                    (loop [index 0]
                      (when (< index entries)
                        (let [entry (+ ifd 2 (* index 12))
                              tag (u16 entry)]
                          (if (= tag 0x0112)
                            (let [value (u16 (+ entry 8))]
                              (if (<= 1 value 8) value orientation-1))
                            (recur (inc index)))))))))
              (= marker 0xda) nil
              :else (recur (+ offset 2 length)))))))
    (catch Throwable _ orientation-1)))

(defn jpeg-orientation
  "Public EXIF orientation reader for JPEG head bytes.  Returns 1 when the
  tag is absent or the bytes are not a JPEG."
  [^bytes head]
  (or (exif-orientation head) 1))

(defn image-info
  "Inspect an image without decoding every pixel.  The stream is consumed."
  [^InputStream in]
  (try
    (with-open [^ImageInputStream iis (ImageIO/createImageInputStream in)]
      (let [readers (ImageIO/getImageReaders iis)]
        (if (.hasNext readers)
          (let [^javax.imageio.ImageReader reader (.next readers)]
            (try
              (.setInput reader iis true true)
              (let [width (try (.getWidth reader 0) (catch Throwable _ nil))
                    height (try (.getHeight reader 0) (catch Throwable _ nil))
                    frames (try (.getNumImages reader true) (catch Throwable _ 1))]
                {:readable? (boolean (and width height))
                 :width width
                 :height height
                 :format (some-> (.getFormatName reader) str/lower-case)
                 :frames frames
                 :animated? (and (integer? frames) (> frames 1))})
              (finally
                (try (.dispose reader) (catch Throwable _ nil)))))
          {:readable? false
           :reason :unsupported-image-format})))
    (catch Throwable error
      {:readable? false
       :reason :unreadable-image
       :message (.getMessage ^Throwable error)})))

(defn- alpha?
  [^BufferedImage image]
  (.hasAlpha (.getColorModel image)))

(defn- affine
  [m00 m01 m10 m11 tx ty]
  (java.awt.geom.AffineTransform. (double m00) (double m01)
                                  (double m10) (double m11)
                                  (double tx) (double ty)))

(defn- orient
  "Apply EXIF orientation so the pixels match the camera preview."
  [^BufferedImage image orientation]
  (let [w (.getWidth image)
        h (.getHeight image)
        [transform out-w out-h]
        (case (int orientation)
          2 [(affine -1 0 0 1 w 0) w h]
          3 [(affine -1 0 0 -1 w h) w h]
          4 [(affine 1 0 0 -1 0 h) w h]
          5 [(affine 0 1 1 0 0 0) h w]
          6 [(affine 0 1 -1 0 h 0) h w]
          7 [(affine 0 -1 -1 0 h w) h w]
          8 [(affine 0 -1 1 0 0 w) h w]
          [nil w h])]
    (if transform
      (let [type (if (alpha? image) BufferedImage/TYPE_INT_ARGB
                     BufferedImage/TYPE_INT_RGB)
            out (BufferedImage. out-w out-h type)
            graphics (.createGraphics out)]
        (try
          (.drawImage graphics image ^java.awt.geom.AffineTransform transform nil)
          out
          (finally (.dispose graphics))))
      image)))

(defn- scaled
  [^BufferedImage image max-edge]
  (let [w (.getWidth image)
        h (.getHeight image)
        longest (max w h)]
    (if (<= longest max-edge)
      image
      (let [scale (/ (double max-edge) (double longest))
            out-w (max 1 (int (Math/round (* scale w))))
            out-h (max 1 (int (Math/round (* scale h))))
            type (if (alpha? image) BufferedImage/TYPE_INT_ARGB
                     BufferedImage/TYPE_INT_RGB)
            out (BufferedImage. out-w out-h type)
            graphics (.createGraphics out)]
        (try
          (.setRenderingHint graphics RenderingHints/KEY_INTERPOLATION
                             RenderingHints/VALUE_INTERPOLATION_BILINEAR)
          (.setRenderingHint graphics RenderingHints/KEY_RENDERING
                             RenderingHints/VALUE_RENDER_QUALITY)
          (.drawImage graphics image 0 0 out-w out-h nil)
          out
          (finally (.dispose graphics)))))))

(defn- write-image
  ^bytes [^BufferedImage image format quality]
  (let [out (ByteArrayOutputStream.)
        writers (ImageIO/getImageWritersByFormatName format)
        ^javax.imageio.ImageWriter writer (when (.hasNext writers) (.next writers))]
    (when-not writer
      (throw (ex-info "No image writer for format" {:format format})))
    (try
      (let [^ImageWriteParam params (.getDefaultWriteParam writer)]
        (when (and (= format "jpeg") quality)
          (.setCompressionMode params ImageWriteParam/MODE_EXPLICIT)
          (.setCompressionQuality params (float quality)))
        (let [target-type (if (= format "jpeg")
                            BufferedImage/TYPE_INT_RGB
                            (if (alpha? image)
                              BufferedImage/TYPE_INT_ARGB
                              BufferedImage/TYPE_INT_RGB))
              ^BufferedImage output (if (= target-type (.getType image))
                                      image
                                      (let [copy (BufferedImage. (.getWidth image)
                                                                 (.getHeight image)
                                                                 target-type)
                                            graphics (.createGraphics copy)]
                                        (try
                                          (.drawImage graphics image 0 0 nil)
                                          copy
                                          (finally (.dispose graphics)))))
              ^javax.imageio.IIOImage iio (javax.imageio.IIOImage. output nil nil)]
        (with-open [ios (ImageIO/createImageOutputStream out)]
          (.setOutput writer ios)
          (.write writer nil iio params))))
      (finally
        (try (.dispose writer) (catch Throwable _ nil))))
    (.toByteArray out)))

(defn prepare-image
  "Return a real send copy for an image, or an honest refusal.

  Options:
  - `:max-edge` default 2048;
  - `:quality` JPEG quality, default 0.85;
  - `:orientation` EXIF orientation read from the original head;
  - `:head-bytes` the bytes used to read EXIF.
  - `:max-pixels` decoded raster ceiling, default 40 million.

  The original blob is never modified.  A transparent image is written as
  PNG so alpha does not become a black background.  An animated image is
  sent as its first frame and says so; it is not presented as the animation."
  [^InputStream in {:keys [max-edge quality orientation head-bytes]
                    :or {max-edge 2048 quality 0.85 orientation 1}
                    :as options}]
  (try
    (let [max-pixels (or (:max-pixels options) 40000000)
          head-info (when (and head-bytes (pos? (alength ^bytes head-bytes)))
                      (image-info (ByteArrayInputStream. ^bytes head-bytes)))
          pixels (when (and (integer? (:width head-info))
                            (integer? (:height head-info)))
                   (* (long (:width head-info)) (long (:height head-info))))]
      (if (and pixels (> pixels max-pixels))
        {:ok? false
         :reason :image-too-large
         :message "the image raster exceeds the configured pixel limit"
         :details {:pixels pixels :max-pixels max-pixels}}
        (let [^BufferedImage original (ImageIO/read in)]
          (if (nil? original)
            {:ok? false :reason :unreadable-image}
            (let [info head-info
                  oriented (orient original orientation)
                  prepared (scaled oriented max-edge)
                  resized? (not (identical? oriented prepared))
                  animated? (boolean (:animated? info))
                  has-alpha? (alpha? prepared)
                  format (if has-alpha? "png" "jpeg")
                  bytes (if (and (not resized?) (not animated?)
                                 (= (int orientation) 1))
                          nil
                          (write-image prepared format quality))]
              {:ok? true
               :bytes bytes
               :unchanged? (nil? bytes)
               :mime (if has-alpha? "image/png" "image/jpeg")
               :format format
               :width (.getWidth ^BufferedImage prepared)
               :height (.getHeight ^BufferedImage prepared)
               :original-width (.getWidth original)
               :original-height (.getHeight original)
               :orientation orientation
               :resized? resized?
               :animated? animated?
               :first-frame? animated?
               :alpha? has-alpha?})))))
    (catch Throwable error
      {:ok? false
       :reason :image-prepare-failed
       :message (.getMessage ^Throwable error)})))

;; ---------------------------------------------------------------------------
;; Text

(defn decode-utf8
  "Strictly decode UTF-8.  Invalid bytes are refused; they are never replaced
  by U+FFFD because that would silently alter the operator's file."
  [^bytes bytes]
  (try
    (let [decoder (.newDecoder StandardCharsets/UTF_8)]
      (.onMalformedInput decoder CodingErrorAction/REPORT)
      (.onUnmappableCharacter decoder CodingErrorAction/REPORT)
      (let [text (.toString (.decode decoder (ByteBuffer/wrap bytes)))
            text (if (str/starts-with? text "\uFEFF") (subs text 1) text)]
        {:ok? true :text text :chars (count text)}))
    (catch CharacterCodingException error
      {:ok? false :reason :invalid-utf8 :message (.getMessage error)})))

(defn read-text
  "Read at most LIMIT bytes and decode strict UTF-8.

  `:truncated?` is true when the source had more bytes.  A truncated
  multi-byte tail is trimmed to the last complete character rather than
  replaced; an invalid byte anywhere else refuses the whole extraction."
  [^InputStream in limit]
  (let [bytes (read-up-to in (inc (long limit)))
        truncated? (> (alength bytes) limit)
        bytes (if truncated?
                (Arrays/copyOf bytes (int limit))
                bytes)]
    (if truncated?
      (loop [trim 0]
        (if (> trim 4)
          {:ok? false :reason :invalid-utf8 :truncated? true}
          (let [candidate (Arrays/copyOf bytes (- (alength bytes) trim))
                decoded (decode-utf8 candidate)]
            (if (:ok? decoded)
              (assoc decoded :truncated? true :bytes (alength candidate))
              (recur (inc trim))))))
      (assoc (decode-utf8 bytes) :truncated? false :bytes (alength bytes)))))

(defn text-preview
  "Produce a real local preview of a text-like file.  This is a preview, not
  a claim that a document extractor read the file.  Sending uses the
  extraction route, which must be supplied by a real extractor."
  [^InputStream in limit]
  (let [result (read-text in limit)]
    (if (:ok? result)
      {:status (if (str/blank? (:text result)) :empty :ready)
       :text (:text result)
       :chars (:chars result)
       :bytes (:bytes result)
       :truncated? (boolean (:truncated? result))
       :source :local-utf8-preview
       :editable? true}
      {:status :failed
       :reason (:reason result)
       :message (:message result)
       :source :local-utf8-preview})))

;; ---------------------------------------------------------------------------
;; PDF and media facts

(defn pdf-facts
  "Read only facts that are actually present in the header/trailer sample.

  Page count is not guessed.  Encryption is reported as `true` only when the
  `/Encrypt` marker is visible; otherwise it is `:unknown`, not `false`."
  [^bytes sample]
  (let [text (String. sample StandardCharsets/ISO_8859_1)]
    {:kind :pdf
     :encrypted? (if (str/includes? text "/Encrypt") true :unknown)
     :page-count nil
     :readable? true}))

(defn wav-duration-ms
  "Duration for a canonical PCM WAV header.  Other containers return nil."
  [^bytes head]
  (try
    (when (and (ascii? head 0 "RIFF") (ascii? head 8 "WAVE"))
      (let [byte-rate (bit-or (u8 head 28)
                              (bit-shift-left (u8 head 29) 8)
                              (bit-shift-left (u8 head 30) 16)
                              (bit-shift-left (u8 head 31) 24))
            data-size (loop [offset 12]
                        (when (< (+ offset 8) (alength head))
                          (let [chunk-size (bit-or (u8 head (+ offset 4))
                                                   (bit-shift-left (u8 head (+ offset 5)) 8)
                                                   (bit-shift-left (u8 head (+ offset 6)) 16)
                                                   (bit-shift-left (u8 head (+ offset 7)) 24))]
                            (cond
                              (and (ascii? head offset "data")
                                   (< (+ offset 8 4) (alength head)))
                              chunk-size
                              (pos? chunk-size)
                              (recur (+ offset 8 chunk-size))
                              :else nil))))]
        (when (and (pos? byte-rate) data-size)
          (long (Math/round (* 1000.0 (/ (double data-size) byte-rate)))))))
    (catch Throwable _ nil)))

(defn basic-facts
  [mime head]
  (let [kind (model/mime->kind mime)]
    (cond-> {:kind kind
             :readable? true}
      (= kind :image) (merge (image-info (ByteArrayInputStream. ^bytes head)))
      (= kind :pdf) (merge (pdf-facts head))
      (= kind :audio) (assoc :duration-ms (wav-duration-ms head)))))
