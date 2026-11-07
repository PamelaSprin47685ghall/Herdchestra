(ns hc.server.documents.text
  "Strict text decoding.

  Text is read only when the bytes really decode.  There is no replacement
  character fallback and no extension-based guess: an invalid byte sequence is
  a refusal, not a document with a few changed characters."
  (:require [clojure.string :as str]
            [hc.server.documents.error :as error])
  (:import [java.nio ByteBuffer]
           [java.nio.charset CharacterCodingException Charset CodingErrorAction]
           [java.util Arrays]))

(def default-max-bytes (* 100 1024 1024))

(defn- starts-with-bytes?
  [^bytes bytes prefix]
  (and (>= (alength bytes) (count prefix))
       (loop [i 0]
         (cond
           (= i (count prefix)) true
           (= (aget bytes i) (unchecked-byte (nth prefix i))) (recur (inc i))
           :else false))))

(defn- bom-info
  [^bytes bytes]
  (cond
    (starts-with-bytes? bytes [0xEF 0xBB 0xBF]) {:encoding "UTF-8" :length 3}
    (starts-with-bytes? bytes [0xFF 0xFE 0x00 0x00]) {:encoding "UTF-32LE" :length 4}
    (starts-with-bytes? bytes [0x00 0x00 0xFE 0xFF]) {:encoding "UTF-32BE" :length 4}
    (starts-with-bytes? bytes [0xFF 0xFE]) {:encoding "UTF-16LE" :length 2}
    (starts-with-bytes? bytes [0xFE 0xFF]) {:encoding "UTF-16BE" :length 2}
    :else nil))

(defn- has-nul?
  [^bytes bytes]
  (loop [i 0]
    (cond
      (= i (alength bytes)) false
      (zero? (aget bytes i)) true
      :else (recur (inc i)))))

(defn line-count
  "Visible line count: newline count plus one when the text does not end with
  a newline.  An empty string has zero lines."
  [^String text]
  (if (empty? text)
    0
    (let [breaks (count (re-seq #"\r\n|\r|\n" text))]
      (if (str/ends-with? text "\n")
        breaks
        (inc breaks)))))

(defn codepoint-count
  [^String text]
  (.codePointCount text 0 (.length text)))

(defn decode
  "Decode bytes into `{:ok? true :text ... :encoding ... ...}`.

  Options:
  - `:encoding` explicit charset name.  It wins over a BOM; the BOM is
    reported as ignored.
  - `:max-bytes` default 100 MiB.
  - `:allow-nul?` only for callers that already know the source is text.

  Refusals: `:SOURCE_TOO_LARGE`, `:ENCODING_UNKNOWN`, `:NOT_TEXT`.
  `:NOT_TEXT` is used for NUL-bearing bytes without a UTF-16/32 BOM, because
  those are binary far more often than they are text."
  ([bytes]
   (decode bytes {}))
  ([^bytes bytes {:keys [encoding max-bytes allow-nul?]
                  :or {max-bytes default-max-bytes}}]
   (let [max-bytes (or max-bytes default-max-bytes)]
     (cond
     (nil? bytes)
     (error/failure :SOURCE_UNREADABLE "text bytes are missing")

     (> (alength bytes) max-bytes)
     (error/failure :SOURCE_TOO_LARGE
                    "text exceeds the configured byte limit"
                    {:size (alength bytes) :max-bytes max-bytes})

     :else
     (let [bom (bom-info bytes)
           explicit? (some? encoding)
           encoding (or encoding (:encoding bom) "UTF-8")
           bom? (some? bom)
           bom-ignored? (and explicit? bom?
                             (not= (str/upper-case encoding)
                                   (str/upper-case (:encoding bom))))
           skip (if (and bom? (not bom-ignored?)) (:length bom) 0)
           body (if (pos? skip) (Arrays/copyOfRange bytes skip (alength bytes)) bytes)]
       (cond
         (and (not explicit?) (nil? bom) (has-nul? body) (not allow-nul?))
         (error/failure :NOT_TEXT
                        "bytes contain NUL and no text encoding marker"
                        {:size (alength bytes)})

           :else
           (try
           (let [charset (Charset/forName encoding)
                 decoder (-> (.newDecoder charset)
                             (.onMalformedInput CodingErrorAction/REPORT)
                             (.onUnmappableCharacter CodingErrorAction/REPORT))
                 text (str (.decode decoder (ByteBuffer/wrap body)))]
             (let [text (if (and (pos? (count text))
                                 (= \uFEFF (.charAt ^String text 0)))
                          (subs text 1)
                          text)]
               {:ok? true
                :text text
                :encoding (.name charset)
                :bom? bom?
                :bom-ignored? bom-ignored?
                :byte-count (alength bytes)
                :char-count (.length ^String text)
                :codepoint-count (codepoint-count text)
                :line-count (line-count text)
                :empty? (empty? text)
                :warnings (cond-> []
                            bom-ignored?
                            (conj (error/warning :BOM_IGNORED
                                                 "explicit encoding overrides the byte-order mark"
                                                 {:bom-encoding (:encoding bom)
                                                  :encoding (.name charset)})))}))
           (catch CharacterCodingException _
             (error/failure :ENCODING_UNKNOWN
                            "bytes are not valid in the selected text encoding"
                            {:encoding encoding}))
           (catch java.nio.charset.UnsupportedCharsetException _
             (error/failure :ENCODING_UNKNOWN
                            "the selected text encoding is not available"
                            {:encoding encoding}))
           (catch IllegalArgumentException _
               (error/failure :ENCODING_UNKNOWN
                              "the selected text encoding is not available"
                              {:encoding encoding})))))))))
