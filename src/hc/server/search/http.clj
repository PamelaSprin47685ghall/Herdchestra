(ns hc.server.search.http
  "Bounded HTTP for search and retrieval.

  Redirects are never followed: a search credential must not be replayed to
  another host, and a retrieved page must not move the adapter to an address
  the user did not select.  Error values never contain request headers,
  request bodies, response bodies or exception messages; those are the places
  a credential or a private query would otherwise escape."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [hc.server.search.errors :as errors])
  (:import (java.io ByteArrayOutputStream InputStream)
           (java.net ConnectException URI UnknownHostException)
           (java.net.http HttpClient HttpClient$Redirect HttpRequest
                          HttpRequest$BodyPublishers HttpResponse$BodyHandlers)
           (java.nio.charset StandardCharsets)
           (java.time Duration)
           (java.util.concurrent CancellationException ExecutionException)))

(def default-timeout-ms 8000)
(def default-max-bytes (* 4 1024 1024))
(def hard-max-bytes (* 8 1024 1024))
(def default-connect-timeout-ms 10000)

(defn default-client
  "One shared client.  Redirects are disabled at the client and request."
  []
  (-> (HttpClient/newBuilder)
      (.connectTimeout (Duration/ofMillis default-connect-timeout-ms))
      (.followRedirects HttpClient$Redirect/NEVER)
      (.build)))

(defn- cancelled?
  [value]
  (cond
    (nil? value) false
    (fn? value) (boolean (value))
    (instance? clojure.lang.IDeref value) (boolean @value)
    :else (boolean value)))

(defn- header-map
  [response]
  (into {}
        (map (fn [[name values]]
               [(str/lower-case (str name)) (vec values)]))
        (.map (.headers response))))

(defn- read-bounded-now
  "Read at most LIMIT bytes on the calling thread."
  [^InputStream input limit cancelled]
  (with-open [input input]
    (let [buffer (byte-array 8192)
          output (ByteArrayOutputStream.)]
      (loop [total 0]
        (cond
          (cancelled? cancelled)
          {:cancelled? true
           :bytes (.toByteArray output)
           :truncated? false}

          :else
          (let [read (.read input buffer)]
            (cond
              (neg? read)
              {:cancelled? false
               :bytes (.toByteArray output)
               :truncated? false}

              (> (+ total read) limit)
              (do
                (let [keep (max 0 (- limit total))]
                  (when (pos? keep)
                    (.write output buffer 0 keep)))
                {:cancelled? false
                 :bytes (.toByteArray output)
                 :truncated? true})

              :else
              (do
                (.write output buffer 0 read)
                (recur (+ total read))))))))))

(defn- read-bounded
  "Read at most LIMIT bytes and stop at DEADLINE.

  The byte loop runs on a helper thread because an InputStream read can block
  after the response headers have arrived.  Closing the stream on timeout
  releases the socket; the caller receives an explicit timeout instead of a
  worker stuck on a slow body."
  [^InputStream input limit cancelled deadline]
  (if (nil? deadline)
    (read-bounded-now input limit cancelled)
    (let [remaining-ms (max 0 (quot (- (long deadline) (System/nanoTime))
                                    1000000))]
      (if (zero? remaining-ms)
        (do
          (try (.close input) (catch Throwable _ nil))
          {:timeout? true})
        (let [reader (clojure.core/future
                       (try
                         (read-bounded-now input limit cancelled)
                         (catch Throwable error
                           {:error error})))
              value (deref reader remaining-ms ::timeout)]
          (if (= ::timeout value)
            (do
              (try (.close input) (catch Throwable _ nil))
              (clojure.core/future-cancel reader)
              {:timeout? true})
            value))))))

(defn- exception-error
  [^Throwable throwable service-id]
  (let [cause (loop [t throwable]
                (if-let [next (.getCause t)]
                  (recur next)
                  t))
        code (cond
               (instance? java.net.http.HttpTimeoutException cause)
               :SEARCH_TIMEOUT

               (or (instance? ConnectException cause)
                   (instance? UnknownHostException cause)
                   (instance? javax.net.ssl.SSLException cause))
               :SEARCH_SERVICE_UNREACHABLE

               (instance? java.io.IOException cause)
               :SEARCH_SERVICE_UNREACHABLE

               :else
               :SEARCH_SERVICE_UNREACHABLE)
        message (case code
                  :SEARCH_TIMEOUT
                  "the search service did not answer before the timeout"
                  "the search service could not be reached")]
    (errors/refusal code message
                    {:service-id service-id
                     :exception (.getSimpleName (class cause))})))

(defn- await-response
  [response-future deadline cancelled service-id]
  (loop []
    (cond
        (cancelled? cancelled)
        (do (.cancel ^java.util.concurrent.CompletableFuture response-future true)
            (errors/refusal :SEARCH_CANCELLED
                            "the search request was cancelled"
                            {:service-id service-id}))

        (.isDone ^java.util.concurrent.CompletableFuture response-future)
        (try
          {:ok? true :response (.get ^java.util.concurrent.CompletableFuture
                                     response-future)}
          (catch ExecutionException e
            (exception-error (or (.getCause e) e) service-id))
          (catch CancellationException _
            (errors/refusal :SEARCH_CANCELLED
                            "the search request was cancelled"
                            {:service-id service-id}))
          (catch InterruptedException _
            (.interrupt (Thread/currentThread))
            (errors/refusal :SEARCH_CANCELLED
                            "the search request was cancelled"
                            {:service-id service-id})))

        (>= (System/nanoTime) deadline)
        (do (.cancel ^java.util.concurrent.CompletableFuture response-future true)
            (errors/refusal :SEARCH_TIMEOUT
                            "the search service did not answer before the timeout"
                            {:service-id service-id}))

        :else
        (do
          (try
            (Thread/sleep 20)
            (catch InterruptedException _
              (.interrupt (Thread/currentThread))
              (throw (InterruptedException.))))
          (recur)))))

(defn send!
  "Perform one HTTP request and return bounded bytes.

  Options:

    :client       java.net.http.HttpClient
    :method       \"GET\" or \"POST\"
    :uri          java.net.URI or string
    :headers      map of string -> string
    :body         byte array or nil
    :timeout-ms   total request deadline
    :max-bytes    response body bound
    :allow-truncated? return the bounded prefix instead of a refusal
    :cancelled?   atom, function or nil
    :service-id   identifier used in error details

  Returns `{:ok? true :status n :headers {...} :body bytes :truncated? bool}`
  or a refusal."
  [{:keys [client method uri headers body timeout-ms max-bytes cancelled?
           service-id allow-truncated?]
    :or {method "GET"
         timeout-ms default-timeout-ms
         max-bytes default-max-bytes}}]
  (let [limit (min hard-max-bytes (max 1 (long max-bytes)))
        timeout-ms (min 120000 (max 1 (long timeout-ms)))
        deadline (+ (System/nanoTime) (* 1000000 timeout-ms))
        uri (try
              (if (instance? URI uri) uri (URI. (str uri)))
              (catch Exception _
                nil))]
    (cond
      (nil? uri)
      (errors/refusal :SEARCH_SERVICE_URL_INVALID
                      "the search endpoint is not a valid URI"
                      {:service-id service-id})

      (not (contains? #{"http" "https"}
                      (some-> (.getScheme uri) str/lower-case)))
      (errors/refusal :SEARCH_SERVICE_URL_INVALID
                      "the search endpoint must use http or https"
                      {:service-id service-id})

      :else
      (try
        (let [publisher (if (some? body)
                          (HttpRequest$BodyPublishers/ofByteArray
                           ^bytes body)
                          (HttpRequest$BodyPublishers/noBody))
              builder (-> (HttpRequest/newBuilder uri)
                          (.timeout (Duration/ofMillis timeout-ms))
                          (.method (str/upper-case (str method)) publisher))]
          (doseq [[name value] headers]
            (.header builder (str name) (str value)))
          (let [future (.sendAsync ^HttpClient client
                                   (.build builder)
                                   (HttpResponse$BodyHandlers/ofInputStream))
                awaited (await-response future deadline cancelled? service-id)]
            (if-not (:ok? awaited)
              awaited
              (let [response (:response awaited)
                    status (.statusCode response)
                    headers (header-map response)
                    read (read-bounded (.body response) limit cancelled?
                                       deadline)]
                (cond
                  (:timeout? read)
                  (errors/refusal :SEARCH_TIMEOUT
                                  "the search service did not finish before the timeout"
                                  {:service-id service-id})

                  (:error read)
                  (exception-error (:error read) service-id)

                  (:cancelled? read)
                  (errors/refusal :SEARCH_CANCELLED
                                  "the search request was cancelled"
                                  {:service-id service-id})

                  (and (:truncated? read) (not allow-truncated?))
                  (errors/refusal :SEARCH_RESPONSE_TOO_LARGE
                                  "the service response exceeded the byte limit"
                                  {:service-id service-id
                                   :limit-bytes limit})

                  :else
                  {:ok? true
                   :status status
                   :headers headers
                   :body (:bytes read)
                   :truncated? (:truncated? read)})))))
        (catch InterruptedException _
          (.interrupt (Thread/currentThread))
          (errors/refusal :SEARCH_CANCELLED
                          "the search request was cancelled"
                          {:service-id service-id}))
        (catch Exception e
          (exception-error e service-id))))))

(defn content-type
  [headers]
  (some-> (get headers "content-type") first
          (str/split #";" 2)
          first
          str/trim
          str/lower-case))

(defn header
  [headers name]
  (some-> (get headers (str/lower-case name)) first))

(defn decode-json
  "Parse one JSON value.  The response body is never included in an error."
  [bytes service-id]
  (try
    {:ok? true
     :value (json/read-str (String. ^bytes bytes StandardCharsets/UTF_8)
                           :key-fn keyword)}
    (catch Exception _
      (errors/refusal :SEARCH_RESPONSE_INVALID
                      "the search service returned invalid JSON"
                      {:service-id service-id}))))

(defn status-error
  "Map a non-success HTTP status to a distinct, actionable refusal."
  [status service-id]
  (let [status (long status)
        [code message]
        (cond
          (= status 401)
          [:SEARCH_CREDENTIAL_INVALID
           "the search service rejected the credential"]

          (contains? #{403 451} status)
          [:SEARCH_ACCESS_DENIED
           "the search service denied access to this request"]

          (= status 407)
          [:SEARCH_CREDENTIAL_INVALID
           "the proxy requires authentication"]

          (= status 429)
          [:SEARCH_QUOTA_EXCEEDED
           "the search service rate limit or quota was reached"]

          (<= 300 status 399)
          [:SEARCH_REDIRECT_REFUSED
           "the search service redirected the request; redirects are not followed"]

          (<= 500 status 599)
          [:SEARCH_SERVICE_ERROR
           "the search service reported an internal error"]

          :else
          [:SEARCH_SERVICE_ERROR
           "the search service returned an unsuccessful status"])]
    (errors/refusal code message
                    {:service-id service-id
                     :http-status status})))

(defn request-json!
  "Send one request and parse a successful JSON response.

  A non-2xx status is a refusal before any JSON parsing.  A 2xx response with
  invalid JSON is a separate refusal."
  [options]
  (let [result (send! options)]
    (if-not (:ok? result)
      result
      (if (<= 200 (:status result) 299)
        (let [decoded (decode-json (:body result) (:service-id options))]
          (if (:ok? decoded)
            (assoc decoded :status (:status result))
            decoded))
        (status-error (:status result) (:service-id options))))))
