(ns hc.server.documents.source
  "Source identity, bounded reads and content sniffing.

  The declared file name and MIME type are hints, never proof.  The detector
  looks at bytes; a mismatch is reported as a warning and the detected type is
  the one extraction acts on.  This is why a `.pdf` file containing text is
  never sent to a PDF parser, and a text file named `.pdf` is not silently
  treated as a broken PDF."
  (:require [clojure.string :as str]
            [hc.server.documents.error :as error]
            [hc.server.documents.text :as text])
  (:import [java.io ByteArrayOutputStream IOException InputStream]
           [java.nio.file Files LinkOption OpenOption Paths]
           [java.security MessageDigest]
           [java.util HexFormat]))

(def default-max-bytes (* 100 1024 1024))

(defn- pick
  [m k]
  (or (get m k) (get m (keyword "source" (name k)))))

(defn normalize
  "Normalize a caller-supplied source descriptor.

  `:id` is required: without a stable source identity a page reference cannot
  point back to the material it came from.  `:name`, `:mime` and `:size` are
  optional hints."
  [m]
  (cond
    (not (map? m))
    (error/failure :SOURCE_REQUIRED "a source descriptor is required")

    :else
    (let [id (pick m :id)
          name (pick m :name)
          mime (some-> (pick m :mime) str/lower-case (str/split #";") first str/trim)
          size (pick m :size)]
      (cond
        (not (and (string? id) (not (str/blank? id))))
        (error/failure :SOURCE_ID_REQUIRED
                       "the source needs a stable :id before extraction"
                       {:source (select-keys m [:name :mime :size])})

        (and (some? size) (not (and (integer? size) (not (neg? size)))))
        (error/failure :SOURCE_REQUIRED
                       "source :size must be a non-negative integer"
                       {:source/id id :size size})

        :else
        {:ok? true
         :source (cond-> {:id id}
                   (and (string? name) (not (str/blank? name))) (assoc :name name)
                   (and (string? mime) (not (str/blank? mime))) (assoc :mime mime)
                   (some? size) (assoc :size size))}))))

(defn- sha256
  [^bytes bytes]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (.formatHex (HexFormat/of) (.digest digest bytes))))

(declare read-bounded)

(defn- read-path
  [path max-bytes]
  (try
    (let [p (Paths/get path (make-array String 0))]
      (cond
        (not (Files/isRegularFile p (make-array LinkOption 0)))
        (error/failure :SOURCE_UNREADABLE
                       "the source is not a regular readable file"
                       {:path path})

        (> (Files/size p) max-bytes)
        (error/failure :SOURCE_TOO_LARGE
                       "the source exceeds the configured byte limit"
                       {:size (Files/size p) :max-bytes max-bytes})

        :else
        (with-open [in (Files/newInputStream p (make-array OpenOption 0))]
          (read-bounded in max-bytes (Files/size p)))))
      (catch IOException t
        (error/failure :SOURCE_UNREADABLE
                       "the source could not be read"
                       {:path path :reason (error/safe-message t)}))
      (catch SecurityException t
        (error/failure :SOURCE_UNREADABLE
                       "the source could not be read"
                       {:path path :reason (error/safe-message t)}))
      (catch java.nio.file.InvalidPathException t
        (error/failure :SOURCE_UNREADABLE
                       "the source path is not valid"
                       {:path path :reason (error/safe-message t)}))))

(defn- read-bounded
  [^InputStream in max-bytes initial-capacity]
  (let [out (ByteArrayOutputStream. (int (min initial-capacity (* 1024 1024))))
        buffer (byte-array 65536)]
    (loop [total 0]
      (let [n (.read in buffer)]
        (cond
          (neg? n)
          {:ok? true :bytes (.toByteArray out) :size total}

          (> (+ total n) max-bytes)
          (error/failure :SOURCE_TOO_LARGE
                         "the source grew beyond the configured byte limit while reading"
                         {:size (+ total n) :max-bytes max-bytes})

          :else
          (do
            (.write out buffer 0 n)
            (recur (+ total n))))))))

(defn read-bytes
  "Read a source into one bounded byte array.

  Exactly one of `:bytes` or `:path` is expected.  The returned size is the
  bytes actually read; a changed file cannot smuggle an unbounded read past
  the limit."
  ([request]
   (read-bytes request {}))
  ([{:keys [bytes path stream]} {:keys [max-bytes] :or {max-bytes default-max-bytes}}]
   (let [max-bytes (or max-bytes default-max-bytes)]
     (cond
       (> (count (filter some? [bytes path stream])) 1)
       (error/failure :SOURCE_AMBIGUOUS
                      "provide only one of :bytes, :path or :stream")

       (some? bytes)
       (if (bytes? bytes)
         (if (> (alength ^bytes bytes) max-bytes)
           (error/failure :SOURCE_TOO_LARGE
                          "the source exceeds the configured byte limit"
                          {:size (alength ^bytes bytes) :max-bytes max-bytes})
           {:ok? true :bytes bytes :size (alength ^bytes bytes) :sha256 (sha256 bytes)})
         (error/failure :SOURCE_UNREADABLE ":bytes must be a byte array"))

       (string? path)
       (let [result (read-path path max-bytes)]
         (if (:ok? result)
           (assoc result :sha256 (sha256 (:bytes result)))
           result))

       (some? stream)
       (if (instance? InputStream stream)
         (try
           (let [result (read-bounded stream max-bytes 65536)]
             (if (:ok? result)
               (assoc result :sha256 (sha256 (:bytes result)))
               result))
           (catch IOException t
             (error/failure :SOURCE_UNREADABLE
                            "the source stream could not be read"
                            {:reason (error/safe-message t)})))
         (error/failure :SOURCE_UNREADABLE ":stream must be an InputStream"))

       :else
       (error/failure :SOURCE_UNREADABLE
                      "a :path, :bytes or :stream source is required"
                      {:has-path? (some? path)
                       :has-bytes? (some? bytes)
                       :has-stream? (some? stream)})))))

(defn- starts-with?
  [^bytes bytes prefix]
  (and (>= (alength bytes) (count prefix))
       (loop [i 0]
         (cond
           (= i (count prefix)) true
           (= (aget bytes i) (unchecked-byte (nth prefix i))) (recur (inc i))
           :else false))))

(defn- ascii-at?
  [^bytes bytes offset value]
  (let [needle (.getBytes ^String value "US-ASCII")]
    (and (>= (alength bytes) (+ offset (alength needle)))
         (loop [i 0]
           (cond
             (= i (alength needle)) true
             (= (aget bytes (+ offset i)) (aget needle i)) (recur (inc i))
             :else false)))))

(defn- image-format
  [^bytes bytes]
  (cond
    (starts-with? bytes [0x89 0x50 0x4E 0x47 0x0D 0x0A 0x1A 0x0A]) :png
    (starts-with? bytes [0xFF 0xD8 0xFF]) :jpeg
    (or (ascii-at? bytes 0 "GIF87a") (ascii-at? bytes 0 "GIF89a")) :gif
    (ascii-at? bytes 0 "BM") :bmp
    (or (starts-with? bytes [0x49 0x49 0x2A 0x00])
        (starts-with? bytes [0x4D 0x4D 0x00 0x2A])) :tiff
    (and (ascii-at? bytes 0 "RIFF") (ascii-at? bytes 8 "WEBP")) :webp
    :else nil))

(defn- image-mime
  [format]
  (case format
    :png "image/png"
    :jpeg "image/jpeg"
    :gif "image/gif"
    :bmp "image/bmp"
    :tiff "image/tiff"
    :webp "image/webp"
    nil))

(defn- pdf-header?
  [^bytes bytes]
  (let [limit (min (alength bytes) 1024)]
    (loop [offset 0]
      (cond
        (> (+ offset 5) limit) false
        (ascii-at? bytes offset "%PDF-") true
        :else (recur (inc offset))))))

(defn- text-mime?
  [mime]
  (or (str/starts-with? (or mime "") "text/")
      (#{"application/json" "application/xml" "application/xhtml+xml"
         "application/javascript" "application/ecmascript" "application/yaml"
         "application/x-yaml" "application/toml" "application/x-sh"
         "application/x-httpd-php" "application/sql" "application/graphql"
         "application/csv"}
       mime)
      (boolean (some #(str/ends-with? (or mime "") %)
                     ["+json" "+xml" "+yaml"]))))

(defn- declared-family
  [mime]
  (cond
    (nil? mime) :unknown
    (text-mime? mime) :text
    (= mime "application/pdf") :pdf
    (str/starts-with? mime "image/") :image
    :else :unknown))

(defn detect
  "Detect the actual content family from bytes.

  Returns `{:ok? true :kind :text|:image|:pdf|:unsupported ...}`.  A refused
  decode is still a successful detection: `:kind :unsupported` with a reason
  lets the caller explain why no route is available."
  [^bytes bytes {:keys [declared-mime name encoding] :as _options}]
  (let [declared (some-> declared-mime str/lower-case (str/split #";") first str/trim)
        family (declared-family declared)
        image-format (image-format bytes)
        pdf? (pdf-header? bytes)
        rtf? (ascii-at? bytes 0 "{\\rtf")
        zip? (or (starts-with? bytes [0x50 0x4B 0x03 0x04])
                 (starts-with? bytes [0x50 0x4B 0x05 0x06]))
        ole? (starts-with? bytes [0xD0 0xCF 0x11 0xE0])
        text-result (when-not (or image-format pdf? rtf? zip? ole?)
                      (text/decode bytes {:encoding encoding}))
        detected
        (cond
          pdf? {:kind :pdf :mime "application/pdf" :format :pdf}
          image-format {:kind :image :mime (image-mime image-format) :format image-format}
          rtf? {:kind :unsupported :mime "application/rtf" :format :rtf
                :reason :UNSUPPORTED_MIME}
          zip? {:kind :unsupported :mime "application/zip" :format :zip
                :reason :UNSUPPORTED_CONTAINER}
          ole? {:kind :unsupported :mime "application/x-ole-storage" :format :ole
                :reason :UNSUPPORTED_CONTAINER}
          (:ok? text-result) {:kind :text
                              :mime (if (text-mime? declared) declared "text/plain")
                              :format :text}
          :else {:kind :unsupported
                 :mime "application/octet-stream"
                 :format :unknown
                 :reason (:code (:error text-result))})
        detected-family (cond
                          (= :text (:kind detected)) :text
                          (= :pdf (:kind detected)) :pdf
                          (= :image (:kind detected)) :image
                          :else :unknown)
        mismatch? (and (not= family :unknown)
                       (not= family detected-family))
        warnings (cond-> []
                   mismatch?
                   (conj (error/warning :MIME_MISMATCH
                                        "declared MIME does not match the detected bytes"
                                        {:declared-mime declared
                                         :detected-mime (:mime detected)})))]
    {:ok? true
     :kind (:kind detected)
     :mime (:mime detected)
     :format (:format detected)
     :reason (:reason detected)
     :declared-mime declared
     :declared-family family
     :name name
     :warnings warnings}))
