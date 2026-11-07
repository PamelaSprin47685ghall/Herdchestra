(ns hc.server.speech.http
  "Outbound HTTP for speech providers.

  The adapter sends one request and reports what it observed.  It never
  retries, never follows a redirect, and never chooses a second provider.  A
  response stream that ends early is returned as partial bytes so a caller
  can preserve playable audio instead of discarding it."
  (:require [clojure.string :as str]
            [hc.server.speech.error :as error])
  (:import (java.io ByteArrayOutputStream IOException InputStream)
           (java.net URI)
           (java.net.http HttpClient HttpClient$Redirect HttpRequest
                          HttpRequest$Builder HttpRequest$BodyPublishers
                          HttpResponse
                          HttpResponse$BodyHandlers)
           (java.nio.charset StandardCharsets)
           (java.time Duration)
           (java.util.concurrent Executors ThreadFactory TimeUnit)))

(defn- client
  [connect-timeout-ms]
  (-> (HttpClient/newBuilder)
      (.connectTimeout (Duration/ofMillis (long (or connect-timeout-ms 5000))))
      (.followRedirects HttpClient$Redirect/NEVER)
      (.build)))

(defn header-map
  [^HttpResponse response]
  (persistent!
   (reduce (fn [result ^java.util.Map$Entry entry]
             (assoc! result
                     (str/lower-case (str (.getKey entry)))
                     (vec (.getValue entry))))
           (transient {})
           (.entrySet (.map (.headers response))))))

(defn ok-status?
  [status]
  (<= 200 (long status) 299))

(defn content-type
  [headers]
  (some-> (get headers "content-type")
          first
          (str/split #";" 2)
          first
          str/trim
          str/lower-case))

(defn read-limited
  "Read at most LIMIT bytes.  A read error after some bytes returns those
  bytes with `:error` and `:partial? true`."
  [^InputStream input limit]
  (let [limit (long (or limit (* 8 1024 1024)))
        output (ByteArrayOutputStream.)
        buffer (byte-array 8192)]
    (loop [total 0]
      (let [read (try
                   (.read input buffer)
                   (catch IOException e
                     {:error e}))]
        (cond
          (map? read)
          {:bytes (.toByteArray output)
           :error (:error read)
           :partial? (pos? total)}

          (neg? read)
          {:bytes (.toByteArray output)
           :partial? false}

          (> (+ total read) limit)
          {:bytes (.toByteArray output)
           :limit-exceeded? true
           :partial? true}

          :else
          (do
            (.write output buffer 0 read)
            (recur (+ total read))))))))

(defonce ^:private read-timeout-executor
  (Executors/newSingleThreadScheduledExecutor
   (reify ThreadFactory
     (newThread [_ runnable]
       (doto (Thread. ^Runnable runnable "hc-speech-read-timeout")
         (.setDaemon true))))))

(defn- read-limited-with-timeout
  "Close the response stream after TIMEOUT-MS so a provider that sends
  headers and then stalls cannot block the caller forever.  Bytes already
  received are returned as a partial read."
  [^InputStream input limit timeout-ms]
  (let [timed-out? (atom false)
        started (System/currentTimeMillis)
        task (.schedule read-timeout-executor
                        ^Runnable (fn []
                                    (reset! timed-out? true)
                                    (try (.close input) (catch Throwable _ nil)))
                        (long (max 1 (or timeout-ms 60000)))
                        TimeUnit/MILLISECONDS)]
    (try
      (let [read (read-limited input limit)
            elapsed (- (System/currentTimeMillis) started)
            timed-out? (or @timed-out?
                           (>= elapsed (max 1 (- (long (or timeout-ms 60000))
                                                 50))))]
        (cond-> (assoc read :elapsed-ms elapsed)
          timed-out?
          (assoc :timed-out? true
                 :error (or (:error read)
                            (ex-info "speech response read timed out"
                                     {:error/kind :speech-read-timeout})))))
      (finally
        (.cancel task false)))))

(defn- header-long
  [headers name]
  (when-let [value (some-> (get headers name) first)]
    (try
      (Long/parseLong (str value))
      (catch NumberFormatException _ nil))))

(defn- add-headers
  [^HttpRequest$Builder builder headers]
  (reduce-kv (fn [^HttpRequest$Builder b key value]
               (if (nil? value)
                 b
                 (.header b (str key) (str value))))
             builder
             (or headers {})))

(defn- transport-error
  [t]
  (cond
    (instance? java.net.http.HttpTimeoutException t)
    {:ok? false
     :error {:code :SPEECH_TIMEOUT
             :message "speech provider did not answer before the timeout"
             :retryable? true}}

    (instance? InterruptedException t)
    (do (.interrupt (Thread/currentThread))
        {:ok? false
         :error {:code :SPEECH_INTERRUPTED
                 :message "speech request was interrupted"
                 :retryable? false}})

    (instance? IOException t)
    {:ok? false
     :error {:code :SPEECH_UNREACHABLE
             :message (or (.getMessage ^Throwable t)
                          "speech provider is unreachable")
             :retryable? true}}

    (instance? IllegalArgumentException t)
    {:ok? false
     :error {:code :SPEECH_ENDPOINT_INVALID
             :message (or (.getMessage ^Throwable t)
                          "speech endpoint is invalid")
             :retryable? false}}

    :else
    {:ok? false
     :error {:code :SPEECH_HTTP_FAILED
             :message (or (.getMessage ^Throwable t)
                          "speech HTTP request failed")
             :details {:class (.getName (class t))
                       :cause (some-> t .getCause class .getName)
                       :cause-message (some-> t .getCause .getMessage)}
             :retryable? false}}))

(defn post-bytes
  "Send one POST and read at most `:limit` response bytes.

  Returns `{:ok? true :status :headers :body :truncated? :read-error}` or a
  transport error map."
  [url {:keys [headers body timeout-ms connect-timeout-ms limit]}]
  (try
    (let [builder (-> (HttpRequest/newBuilder (URI/create url))
                      (.timeout (Duration/ofMillis
                                 (long (or timeout-ms 60000)))))
          builder (if (nil? body)
                    (.POST builder (HttpRequest$BodyPublishers/noBody))
                    (.POST builder (HttpRequest$BodyPublishers/ofByteArray
                                    ^bytes body)))
          request (.build (add-headers builder headers))
          response (.send (client connect-timeout-ms)
                          request
                          (HttpResponse$BodyHandlers/ofInputStream))]
      (try
        (let [timeout (long (or timeout-ms 60000))
              response-headers (header-map response)
              read (read-limited-with-timeout (.body response) limit timeout)
              content-length (header-long response-headers "content-length")
              short? (and content-length
                          (< (alength ^bytes (:bytes read)) content-length))
              timed-out? (or (:timed-out? read)
                             (and short?
                                  (>= (:elapsed-ms read 0)
                                      (max 1 (- timeout 50)))))]
          {:ok? true
           :status (.statusCode response)
           :headers response-headers
           :body (:bytes read)
           :truncated? (boolean (:limit-exceeded? read))
           :timed-out? (boolean timed-out?)
           :read-error (or (:error read)
                           (when timed-out?
                             (ex-info "speech response read timed out"
                                      {:error/kind :speech-read-timeout})))})
        (finally
          (try (.close (.body response)) (catch Throwable _ nil)))))
    (catch Exception t
      (transport-error t))))

(defn post-audio
  "Send one POST and keep the response stream.

  The result always separates a provider status from a local stream failure.
  `:partial? true` means the stream ended before its advertised end or hit
  the local limit; the bytes received so far are still returned."
  [url {:keys [headers body timeout-ms connect-timeout-ms limit]}]
  (try
    (let [builder (-> (HttpRequest/newBuilder (URI/create url))
                      (.timeout (Duration/ofMillis
                                 (long (or timeout-ms 120000)))))
          builder (.POST builder (HttpRequest$BodyPublishers/ofByteArray
                                  ^bytes body))
          request (.build (add-headers builder headers))
          response (.send (client connect-timeout-ms)
                          request
                          (HttpResponse$BodyHandlers/ofInputStream))
          response-headers (header-map response)
          status (.statusCode response)]
      (try
        (let [timeout (long (or timeout-ms 120000))
              read (read-limited-with-timeout
                    (.body response)
                    (if (<= 200 status 299)
                      (or limit (* 50 1024 1024))
                      65536)
                    timeout)
              content-length (header-long response-headers "content-length")
              short? (and content-length
                          (< (alength ^bytes (:bytes read)) content-length))
              timed-out? (or (:timed-out? read)
                             (and short?
                                  (>= (:elapsed-ms read 0)
                                      (max 1 (- timeout 50)))))
              error? (or (:error read) (:limit-exceeded? read) timed-out?)]
          {:ok? true
           :status status
           :headers response-headers
           :content-type (content-type response-headers)
           :bytes (:bytes read)
           :partial? (boolean (and (<= 200 status 299)
                                   (or (:partial? read) error?)))
           :truncated? (boolean (:limit-exceeded? read))
           :timed-out? (boolean timed-out?)
           :read-error (or (:error read)
                           (when timed-out?
                             (ex-info "speech response read timed out"
                                      {:error/kind :speech-read-timeout})))})
        (finally
          (try (.close (.body response)) (catch Throwable _ nil)))))
    (catch Exception t
      (transport-error t))))

(defn- escape-param
  [value]
  (-> (str value)
      (str/replace "\\" "\\\\")
      (str/replace "\"" "\\\"")
      (str/replace #"[\r\n]" " ")))

(defn- byte-array?
  [value]
  (instance? (Class/forName "[B") value))

(defn multipart-body
  "Build one multipart/form-data body.

  Each part is `{:name .. :value ..}` with optional `:filename` and
  `:content-type`.  String values are UTF-8; byte arrays are written as is."
  [boundary parts]
  (let [output (ByteArrayOutputStream.)
        write! (fn [^String value]
                 (.write output (.getBytes value StandardCharsets/UTF_8)))]
    (doseq [{:keys [name value filename content-type]} parts]
      (when (or (str/blank? (str name))
                (re-find #"[\r\n\"]" (str name)))
        (error/fail! :SPEECH_MULTIPART_INVALID
                     "multipart field name is invalid"
                     {:name name}))
      (write! (str "--" boundary "\r\n"))
      (write! (str "Content-Disposition: form-data; name=\"" name "\""
                   (when filename
                     (str "; filename=\"" (escape-param filename) "\""))
                   "\r\n"))
      (when content-type
        (write! (str "Content-Type: "
                     (str/replace (str content-type) #"[\r\n]" "")
                     "\r\n")))
      (write! "\r\n")
      (if (byte-array? value)
        (.write output ^bytes value)
        (write! (str value)))
      (write! "\r\n"))
    (write! (str "--" boundary "--\r\n"))
    (.toByteArray output)))
