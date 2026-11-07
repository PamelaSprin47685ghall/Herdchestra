(ns hc.server.speech.support
  "Loopback HTTP providers for speech adapter tests.

  These are real HTTP servers, not provider stubs on the adapter boundary.
  They exercise the same multipart, JSON, auth, status and stream paths as a
  configured provider."
  (:require [clojure.string :as str]
            [hc.server.speech.json :as json])
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
           (java.io ByteArrayOutputStream InputStream)
           (java.net InetSocketAddress)
           (java.nio.charset StandardCharsets)
           (java.util.concurrent Executors ThreadFactory TimeUnit)
           (java.util.concurrent.atomic AtomicLong)))

(defn- daemon-thread-factory
  [prefix]
  (let [counter (AtomicLong.)]
    (reify ThreadFactory
      (newThread [_ runnable]
        (doto (Thread. ^Runnable runnable
                       (str prefix "-" (.incrementAndGet counter)))
          (.setDaemon true))))))

(defn temp-dir
  [prefix]
  (str (java.nio.file.Files/createTempDirectory
        prefix (make-array java.nio.file.attribute.FileAttribute 0))))

(defn read-all
  [^InputStream input]
  (let [output (ByteArrayOutputStream.) buffer (byte-array 8192)]
    (loop []
      (let [n (.read input buffer)]
        (when (pos? n)
          (.write output buffer 0 n)
          (recur))))
    (.toByteArray output)))

(defn read-body
  [body]
  (cond
    (nil? body) (byte-array 0)
    (string? body) (.getBytes ^String body StandardCharsets/UTF_8)
    :else (read-all body)))

(defn write-bytes!
  ([^HttpExchange exchange status ^bytes body content-type]
   (write-bytes! exchange status body content-type {}))
  ([^HttpExchange exchange status ^bytes body content-type headers]
  (doseq [[key value] headers]
    (.set (.getResponseHeaders exchange) (str key) (str value)))
  (.sendResponseHeaders exchange status (alength body))
  (with-open [out (.getResponseBody exchange)]
    (.write out body))))

(defn write-json!
  [^HttpExchange exchange status value]
  (write-bytes! exchange status
                (.getBytes (json/encode value) StandardCharsets/UTF_8)
                "application/json"))

(defn- split-bytes
  [^bytes haystack ^bytes needle]
  (let [limit (- (alength haystack) (alength needle))]
    (loop [index 0]
      (if (> index limit)
        -1
        (if (zero? (java.util.Arrays/compare
                    needle 0 (alength needle)
                    haystack index (+ index (alength needle))))
          index
          (recur (inc index)))))))

(defn parse-multipart
  "Parse a test multipart body into `{field-name value}`.  File values stay
  byte arrays.  This parser is intentionally small and only used with the
  test audio."
  [content-type ^bytes body]
  (let [boundary (or (second (re-find #"boundary=([^;]+)" (str content-type)))
                     (throw (ex-info "missing multipart boundary" {})))
        marker (.getBytes (str "--" boundary) StandardCharsets/UTF_8)
        body-end (.getBytes (str "--" boundary "--") StandardCharsets/UTF_8)
        body-limit (split-bytes body body-end)
        limit (if (neg? body-limit) (alength body) body-limit)
        strip-crlf (fn [^bytes value]
                     (let [n (alength value)]
                       (if (and (>= n 2)
                                (= (aget value (- n 2)) (byte 13))
                                (= (aget value (dec n)) (byte 10)))
                         (java.util.Arrays/copyOfRange value 0 (- n 2))
                         value)))
        first-marker (split-bytes body marker)
        initial-offset (if (neg? first-marker)
                         0
                         (+ first-marker (alength marker)))
        initial-offset (if (and (< (+ initial-offset 1) limit)
                                (= (aget body initial-offset) (byte 13))
                                (= (aget body (inc initial-offset)) (byte 10)))
                         (+ initial-offset 2)
                         initial-offset)]
    (loop [offset initial-offset result {}]
      (if (>= offset limit)
        result
        (let [offset (if (and (< (+ offset 1) limit)
                              (= (aget body offset) (byte 13))
                              (= (aget body (inc offset)) (byte 10)))
                       (+ offset 2)
                       offset)
              next-marker (split-bytes
                           (java.util.Arrays/copyOfRange body offset limit)
                           marker)
              next-marker (if (neg? next-marker)
                            (- limit offset)
                            next-marker)]
          (if (zero? next-marker)
            result
            (let [end (+ offset next-marker)
                  part (java.util.Arrays/copyOfRange body offset end)
                  header-end (split-bytes part (.getBytes "\r\n\r\n"
                                                          StandardCharsets/UTF_8))
                  header-text (String. ^bytes (java.util.Arrays/copyOfRange
                                               part 0 (max 0 header-end))
                                       StandardCharsets/UTF_8)
                  value (if (neg? header-end)
                          (byte-array 0)
                          (strip-crlf
                           (java.util.Arrays/copyOfRange
                            part (+ header-end 4) (alength part))))
                  name (or (second (re-find #"name=\"([^\"]+)\"" header-text))
                           (str "part-" offset))
                  file? (str/includes? header-text "filename=")
                  value (if file?
                          value
                          (str/trim (String. ^bytes value StandardCharsets/UTF_8)))]
              (recur (+ end (alength marker))
                     (assoc result name value)))))))))

(defn- request
  [^HttpExchange exchange]
  {:method (.getRequestMethod exchange)
   :path (.getPath (.getRequestURI exchange))
   :headers (persistent!
             (reduce (fn [result ^java.util.Map$Entry entry]
                       (assoc! result
                               (str/lower-case (str (.getKey entry)))
                               (first (.getValue entry))))
                     (transient {})
                     (.entrySet (.getRequestHeaders exchange))))
   :body (read-all (.getRequestBody exchange))})

(defn start-provider!
  "Start one loopback provider.  HANDLER receives the request map and the
  exchange; it must write the response."
  [handler]
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
        requests (atom [])
        executor (Executors/newCachedThreadPool
                  (daemon-thread-factory "hc-speech-provider"))]
    (try
      (.createContext
       server "/"
       (reify HttpHandler
         (handle [_ exchange]
           (let [req (request exchange)]
             (swap! requests conj req)
             (try
               (handler req exchange)
               (catch Throwable _
                 (try (write-json! exchange 500 {:error "provider failed"})
                      (catch Throwable _ nil))))))))
      (.setExecutor server executor)
      (.start server)
      {:server server
       :executor executor
       :requests requests
       :url (str "http://127.0.0.1:" (.getPort (.getAddress server)))}
      (catch Throwable t
        (.shutdownNow executor)
        (try (.stop server 0) (catch Throwable _ nil))
        (throw t)))))

(defn stop-provider!
  [{:keys [server executor]}]
  (when server
    (try (.stop server 0) (catch Throwable _ nil)))
  (when executor
    (.shutdownNow executor)
    (try (.awaitTermination executor 2 TimeUnit/SECONDS)
         (catch InterruptedException _ (.interrupt (Thread/currentThread)))))
  {:stopped? true})

(defn wav-bytes
  "Return a valid 8-bit mono PCM WAV with SILENCE-SAMPLES samples."
  ([silence-samples] (wav-bytes 8000 silence-samples))
  ([sample-rate silence-samples]
   (let [data-length (int silence-samples)
         header (byte-array 44)
           put-str (fn [offset value]
                     (System/arraycopy (.getBytes ^String value StandardCharsets/UTF_8)
                                       0 header offset (count value)))
           put-int (fn [offset value]
                     (aset-byte header offset (unchecked-byte (bit-and value 0xff)))
                     (aset-byte header (inc offset)
                                (unchecked-byte (bit-and (bit-shift-right value 8) 0xff)))
                     (aset-byte header (+ offset 2)
                                (unchecked-byte (bit-and (bit-shift-right value 16) 0xff)))
                     (aset-byte header (+ offset 3)
                                (unchecked-byte (bit-and (bit-shift-right value 24) 0xff))))]
       (put-str 0 "RIFF")
       (put-int 4 (+ 36 data-length))
       (put-str 8 "WAVE")
       (put-str 12 "fmt ")
       (put-int 16 16)
       (aset-byte header 20 (byte 1))
       (aset-byte header 21 (byte 0))
       (aset-byte header 22 (byte 1))
       (aset-byte header 23 (byte 0))
       (put-int 24 sample-rate)
       (put-int 28 sample-rate)
       (aset-byte header 32 (byte 1))
       (aset-byte header 33 (byte 0))
       (aset-byte header 34 (byte 8))
       (aset-byte header 35 (byte 0))
       (put-str 36 "data")
       (put-int 40 data-length)
       (let [data (byte-array data-length)]
         (java.util.Arrays/fill data (unchecked-byte 0x80))
         (let [all (byte-array (+ 44 data-length))]
           (System/arraycopy header 0 all 0 44)
           (System/arraycopy data 0 all 44 data-length)
           all)))))
