(ns hc.server.documents.image
  "Image preprocessing for sending and OCR.

  Two rules drive this namespace:

  1. Orientation is applied before anything else, so a rotated phone photo is
     not sent sideways and OCR sees the same pixels a person sees.
  2. Transparency is never silently discarded.  PNG keeps alpha; an explicit
     JPEG request must also say `:flatten-alpha? true`, and the result records
     that alpha was flattened onto a background.

  Image dimensions are inspected before the full decode, so a decompression
  bomb is refused before it can allocate an unbounded raster."
  (:require [clojure.string :as str]
            [hc.server.documents.error :as error])
  (:import [com.drew.imaging ImageMetadataReader]
           [com.drew.metadata.exif ExifDirectoryBase ExifIFD0Directory]
           [java.awt Color RenderingHints]
           [java.awt.geom AffineTransform]
           [java.awt.image BufferedImage]
           [java.io ByteArrayInputStream ByteArrayOutputStream]
           [javax.imageio ImageIO ImageWriteParam IIOImage]
           [javax.imageio.stream ImageOutputStream]))

(def default-max-edge 2048)
(def default-max-pixels 40000000)

(defn- format-key
  [format-name]
  (case (some-> format-name str/lower-case)
    ("jpeg" "jpg") :jpeg
    "png" :png
    "gif" :gif
    "bmp" :bmp
    ("tif" "tiff") :tiff
    "webp" :webp
    (keyword (some-> format-name str/lower-case))))

(defn- format-mime
  [format]
  (case format
    :png "image/png"
    :jpeg "image/jpeg"
    :gif "image/gif"
    :bmp "image/bmp"
    :tiff "image/tiff"
    :webp "image/webp"
    nil))

(defn- output-format
  [value]
  (case (some-> value name str/lower-case)
    nil :auto
    "auto" :auto
    "png" :png
    ("jpeg" "jpg") :jpeg
    :invalid))

(defn read-info
  "Read dimensions and format without decoding pixels.

  Refuses a raster above `:max-pixels` (default 40 million) before any image
  allocation.  Returns `{:ok? true :width ... :height ... :format ...}`."
  ([bytes]
   (read-info bytes {}))
  ([^bytes bytes {:keys [max-pixels] :or {max-pixels default-max-pixels}}]
   (let [max-pixels (or max-pixels default-max-pixels)]
     (try
     (with-open [in (ByteArrayInputStream. bytes)
                 iis (ImageIO/createImageInputStream in)]
       (let [readers (ImageIO/getImageReaders iis)]
         (if-not (.hasNext readers)
           (error/failure :IMAGE_UNREADABLE "no image decoder recognized the bytes")
           (let [reader (.next readers)]
             (try
               (.setInput reader iis true)
               (let [width (long (.getWidth reader 0))
                     height (long (.getHeight reader 0))
                     format (format-key (.getFormatName reader))
                     frames (try
                              (max 1 (.getNumImages reader true))
                              (catch Exception _ 1))]
                 (cond
                   (or (not (pos? width)) (not (pos? height)))
                   (error/failure :IMAGE_UNREADABLE "the image has no usable dimensions")

                   (> (* width height) max-pixels)
                   (error/failure :IMAGE_TOO_LARGE
                                  "the image raster exceeds the configured pixel limit"
                                  {:width width :height height
                                   :pixels (* width height) :max-pixels max-pixels})

                   :else
                   {:ok? true
                    :width width
                    :height height
                    :format format
                    :mime (format-mime format)
                    :frames frames}))
               (finally
                 (.dispose reader)))))))
     (catch java.io.IOException t
       (error/failure :IMAGE_UNREADABLE
                      "the image header could not be read"
                      {:reason (error/safe-message t)}))
       (catch RuntimeException t
         (error/failure :IMAGE_UNREADABLE
                        "the image header could not be read"
                        {:reason (error/safe-message t)}))))))

(defn exif-orientation
  "EXIF orientation 1-8 for JPEG/TIFF bytes, or 1 when absent/unknown."
  [^bytes bytes]
  (try
    (with-open [in (ByteArrayInputStream. bytes)]
      (let [metadata (ImageMetadataReader/readMetadata in)
            directory (.getFirstDirectoryOfType metadata ExifIFD0Directory)
            orientation (when directory
                          (.getInteger directory ExifDirectoryBase/TAG_ORIENTATION))]
        (if (and (integer? orientation) (<= 1 orientation 8))
          orientation
          1)))
    (catch Throwable _ 1)))

(defn- has-alpha?
  [^BufferedImage image]
  (.hasAlpha (.getColorModel image)))

(defn- raster-type
  [alpha?]
  (if alpha? BufferedImage/TYPE_INT_ARGB BufferedImage/TYPE_INT_RGB))

(defn- transform-for
  [orientation width height]
  (case orientation
    1 nil
    2 (AffineTransform. -1.0 0.0 0.0 1.0 (double width) 0.0)
    3 (AffineTransform. -1.0 0.0 0.0 -1.0 (double width) (double height))
    4 (AffineTransform. 1.0 0.0 0.0 -1.0 0.0 (double height))
    5 (AffineTransform. 0.0 1.0 1.0 0.0 0.0 0.0)
    6 (AffineTransform. 0.0 1.0 -1.0 0.0 (double height) 0.0)
    7 (AffineTransform. 0.0 -1.0 -1.0 0.0 (double height) (double width))
    8 (AffineTransform. 0.0 -1.0 1.0 0.0 0.0 (double width))
    nil))

(defn apply-orientation
  "Apply an EXIF orientation transform.  Orientation 5-8 swap the axes."
  [^BufferedImage image orientation]
  (if (or (nil? orientation) (= 1 orientation))
    image
    (let [width (.getWidth image)
          height (.getHeight image)
          swap? (<= 5 orientation 8)
          destination-width (if swap? height width)
          destination-height (if swap? width height)
          transform (transform-for orientation width height)
          destination (BufferedImage. destination-width destination-height
                                      (raster-type (has-alpha? image)))
          graphics (.createGraphics destination)]
      (try
        (.setRenderingHint graphics RenderingHints/KEY_INTERPOLATION
                           RenderingHints/VALUE_INTERPOLATION_BICUBIC)
        (.drawImage graphics image ^AffineTransform transform nil)
        destination
        (finally
          (.dispose graphics))))))

(defn- scale-image
  [^BufferedImage image max-edge]
  (let [width (.getWidth image)
        height (.getHeight image)
        long-edge (max width height)]
    (if (or (nil? max-edge) (not (pos? max-edge)) (<= long-edge max-edge))
      {:image image :scale 1.0}
      (let [scale (/ (double max-edge) (double long-edge))
            destination-width (max 1 (int (Math/round (* width scale))))
            destination-height (max 1 (int (Math/round (* height scale))))
            destination (BufferedImage. destination-width destination-height
                                        (raster-type (has-alpha? image)))
            graphics (.createGraphics destination)]
        (try
          (.setRenderingHint graphics RenderingHints/KEY_INTERPOLATION
                             RenderingHints/VALUE_INTERPOLATION_BICUBIC)
          (.setRenderingHint graphics RenderingHints/KEY_RENDERING
                             RenderingHints/VALUE_RENDER_QUALITY)
          (.drawImage graphics image 0 0 destination-width destination-height nil)
          {:image destination
           :scale scale}
          (finally
            (.dispose graphics)))))))

(defn- background-color
  [background]
  (case background
    :black Color/BLACK
    Color/WHITE))

(defn- flatten-alpha
  [^BufferedImage image background]
  (let [width (.getWidth image)
        height (.getHeight image)
        destination (BufferedImage. width height BufferedImage/TYPE_INT_RGB)
        graphics (.createGraphics destination)]
    (try
      (.setColor graphics (background-color background))
      (.fillRect graphics 0 0 width height)
      (.drawImage graphics image 0 0 nil)
      destination
      (finally
        (.dispose graphics)))))

(defn- resolve-format
  [requested alpha?]
  (case requested
    :auto (if alpha? :png :jpeg)
    (:png :jpeg) requested
    (error/failure :IMAGE_FORMAT_UNSUPPORTED
                   "only PNG and JPEG output are supported"
                   {:format requested})))

(defn prepare-image
  "Orient, scale and select an output format for a decoded image.

  Returns the prepared `:image` plus dimensions and warnings.  It does not
  encode bytes; `preprocess-image` does.  A JPEG request for an image with
  alpha is refused unless `:flatten-alpha? true` is explicit."
  [^BufferedImage image
   {:keys [max-edge orientation format quality flatten-alpha? background
           source-format source-width source-height frames]
    :or {max-edge default-max-edge
         orientation 1
         format :auto
         quality 0.9
         flatten-alpha? false
         background :white}}]
  (let [quality (or quality 0.9)
        orientation (or orientation 1)
        requested-format (output-format format)]
    (cond
      (= :invalid requested-format)
      (error/failure :IMAGE_FORMAT_UNSUPPORTED
                     "only PNG and JPEG output are supported"
                     {:format format})

      (not (<= 0.0 (double quality) 1.0))
      (error/failure :IMAGE_QUALITY
                     "JPEG quality must be between 0.0 and 1.0"
                     {:quality quality})

      :else
      (let [source-width (or source-width (.getWidth image))
            source-height (or source-height (.getHeight image))
            source-alpha? (has-alpha? image)
            oriented (apply-orientation image orientation)
            scaled-result (scale-image oriented max-edge)
            scaled (:image scaled-result)
            scale (:scale scaled-result)
            alpha? (has-alpha? scaled)
            output-format (resolve-format requested-format alpha?)
            flatten? (and alpha? (= :jpeg output-format) flatten-alpha?)
            final (if flatten? (flatten-alpha scaled background) scaled)
            warnings (cond-> []
                       (and source-alpha? flatten?)
                       (conj (error/warning :ALPHA_FLATTENED
                                            "transparency was flattened onto a solid background"
                                            {:background background}))

                       (and (integer? frames) (> frames 1))
                       (conj (error/warning :ANIMATED_FIRST_FRAME
                                            "only the first frame was processed"
                                            {:frames frames}))

                       (< scale 1.0)
                       (conj (error/warning :IMAGE_SCALED
                                            "the image was scaled to its long-edge limit"
                                            {:scale scale :max-edge max-edge})))]
        (cond
          (and alpha? (= :jpeg output-format) (not flatten-alpha?))
          (error/failure :ALPHA_UNSUPPORTED
                         "JPEG cannot preserve transparency; choose PNG or explicitly flatten"
                         {:format :jpeg :alpha? true})

          :else
          {:ok? true
           :image final
           :format output-format
           :mime (format-mime output-format)
           :width (.getWidth final)
           :height (.getHeight final)
           :source-width source-width
           :source-height source-height
           :source-format source-format
           :source-alpha? source-alpha?
           :alpha? (has-alpha? final)
           :orientation orientation
           :scale scale
           :quality quality
           :warnings warnings})))))

(defn- write-jpeg
  [^BufferedImage image quality]
  (let [writers (ImageIO/getImageWritersByFormatName "jpeg")]
    (if-not (.hasNext writers)
      (error/failure :IMAGE_FORMAT_UNSUPPORTED "no JPEG encoder is available")
      (let [writer (.next writers)
            output (ByteArrayOutputStream.)
            parameters (.getDefaultWriteParam writer)]
        (try
          (when (.canWriteCompressed parameters)
            (.setCompressionMode parameters ImageWriteParam/MODE_EXPLICIT)
            (.setCompressionQuality parameters (float quality)))
          (with-open [stream (ImageIO/createImageOutputStream output)]
            (.setOutput writer stream)
            (.write writer nil (IIOImage. image nil nil) parameters))
          {:ok? true :bytes (.toByteArray output) :mime "image/jpeg" :format :jpeg}
          (finally
            (.dispose writer)))))))

(defn encode-image
  "Encode a prepared image as PNG or JPEG."
  [^BufferedImage image format quality]
  (try
    (case format
      :png
      (let [output (ByteArrayOutputStream.)]
        (if (ImageIO/write image "png" output)
          {:ok? true :bytes (.toByteArray output) :mime "image/png" :format :png}
          (error/failure :IMAGE_ENCODE_FAILED "the PNG encoder produced no bytes")))

      :jpeg
      (write-jpeg image quality)

      (error/failure :IMAGE_FORMAT_UNSUPPORTED
                     "only PNG and JPEG output are supported"
                     {:format format}))
    (catch java.io.IOException t
      (error/failure :IMAGE_ENCODE_FAILED
                     "the image could not be encoded"
                     {:reason (error/safe-message t)}))
    (catch RuntimeException t
      (error/failure :IMAGE_ENCODE_FAILED
                     "the image could not be encoded"
                     {:reason (error/safe-message t)}))))

(defn preprocess-image
  "Prepare and encode a decoded image.

  Returns `{:ok? true :bytes ... :mime ... :width ... :height ...}`.  The
  `:image` BufferedImage is not part of the public result."
  [image options]
  (let [prepared (prepare-image image options)]
    (if-not (:ok? prepared)
      prepared
      (let [encoded (encode-image (:image prepared) (:format prepared)
                                  (:quality prepared))]
        (if-not (:ok? encoded)
          encoded
          (-> prepared
              (dissoc :image)
              (merge encoded)
              (assoc :byte-count (alength ^bytes (:bytes encoded)))))))))

(defn preprocess
  "Decode, orient, scale and re-encode image bytes.

  Options:
  - `:max-edge` default 2048; never upscales.
  - `:max-pixels` default 40 million; checked before decoding.
  - `:format` `:auto` (PNG when alpha, JPEG otherwise), `:png`, `:jpeg`.
  - `:quality` JPEG quality 0.0-1.0, default 0.9.
  - `:flatten-alpha?` required for JPEG output of an alpha image.
  - `:orientation` explicit EXIF orientation override; otherwise read from
    the bytes."
  ([bytes]
   (preprocess bytes {}))
  ([^bytes bytes options]
   (let [info (read-info bytes options)]
     (if-not (:ok? info)
       info
       (try
         (let [image (ImageIO/read (ByteArrayInputStream. bytes))]
           (if-not image
             (error/failure :IMAGE_UNREADABLE "the image could not be decoded")
             (preprocess-image image
                               (merge {:source-format (:format info)
                                       :source-width (:width info)
                                       :source-height (:height info)
                                       :frames (:frames info)
                                       :orientation (or (:orientation options)
                                                        (exif-orientation bytes))}
                                      options))))
         (catch java.io.IOException t
           (error/failure :IMAGE_UNREADABLE
                          "the image could not be decoded"
                          {:reason (error/safe-message t)}))
         (catch RuntimeException t
           (error/failure :IMAGE_UNREADABLE
                          "the image could not be decoded"
                          {:reason (error/safe-message t)})))))))
