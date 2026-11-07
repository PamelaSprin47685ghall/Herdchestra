(ns hc.server.extensions.safe-fetch
  "Pinned HTTP(S) GET for user-supplied skill links.

  Policy:

  - only absolute `http`/`https` URLs;
  - resolve every address before connecting and refuse loopback, private,
    link-local, multicast, unspecified and reserved ranges;
  - connect to the resolved address directly, so the name is not resolved a
    second time (no DNS-rebinding window);
  - never follow redirects; 3xx is a refusal;
  - keep the original host for the `Host` header, TLS SNI and certificate
    verification while the socket is pinned to the checked address;
  - bound the response body and use connect/read timeouts.

  `:allow-private?` exists for tests and explicitly configured local
  environments only.  It is never set by the browser client."
  (:require [clojure.string :as str]
            [hc.server.extensions.error :as error])
  (:import (java.io ByteArrayOutputStream InputStream)
           (java.net InetAddress InetSocketAddress Socket SocketTimeoutException
                     URI UnknownHostException)
           (java.nio.charset StandardCharsets)
           (javax.net.ssl SSLParameters SSLSocketFactory SNIHostName)))

(def default-timeout-ms 10000)
(def default-limit (* 20 1024 1024))

(defn- ipv4-private?
  [^bytes b]
  (let [a (bit-and (aget b 0) 0xff)
        c (bit-and (aget b 1) 0xff)
        d (bit-and (aget b 2) 0xff)]
    (or (= a 0)
        (= a 10)
        (and (= a 100) (<= 64 c 127))
        (= a 127)
        (and (= a 169) (= c 254))
        (and (= a 172) (<= 16 c 31))
        (and (= a 192) (= c 168))
        (and (= a 192) (= c 0) (= d 0))
        (and (= a 192) (= c 0) (= d 2))
        (and (= a 198) (<= 18 c 19))
        (and (= a 198) (= c 51) (= d 100))
        (and (= a 203) (= c 0) (= d 113))
        (>= a 224))))

(defn- ipv6-private?
  [^bytes b]
  (let [first (bit-and (aget b 0) 0xff)
        second (bit-and (aget b 1) 0xff)]
    (or (= first 0xff)
        (= first 0xfe)
        (= (bit-and first 0xfe) 0xfc)
        (and (every? zero? (take 10 b))
             (= (bit-and (aget b 10) 0xff) 0xff)
             (= (bit-and (aget b 11) 0xff) 0xff)
             (ipv4-private? (java.util.Arrays/copyOfRange b 12 16))))))

(defn private-address?
  [^InetAddress address]
  (let [bytes (.getAddress address)]
    (or (.isAnyLocalAddress address)
        (.isLoopbackAddress address)
        (.isLinkLocalAddress address)
        (.isSiteLocalAddress address)
        (.isMulticastAddress address)
        (case (alength bytes)
          4 (ipv4-private? bytes)
          16 (ipv6-private? bytes)
          false))))

(defn- failure
  [code message details]
  {:ok? false :error {:code code :message message :details details}})

(defn- parse-target
  [url]
  (try
    (let [uri (URI/create url)
          scheme (some-> (.getScheme uri) str/lower-case)
          host (some-> (.getHost uri) (str/replace #"^\[|\]$" ""))
          port (let [p (.getPort uri)]
                 (if (neg? p)
                   (case scheme "https" 443 "http" 80 nil)
                   p))]
      (cond
        (not (#{"http" "https"} scheme))
        (failure :SKILL_SOURCE_INVALID "URL scheme must be http or https"
                 {:url url :scheme scheme})

        (str/blank? (str host))
        (failure :SKILL_SOURCE_INVALID "URL must include a host" {:url url})

        (nil? port)
        (failure :SKILL_SOURCE_INVALID "URL has no usable port" {:url url})

        (some #(or (= % \newline) (= % \return) (= % \u0000)) (str host))
        (failure :SKILL_SOURCE_INVALID "URL host contains a control character"
                 {:url url})

        :else
        {:ok? true :uri uri :scheme scheme :host host :port port}))
    (catch Exception t
      (failure :SKILL_SOURCE_INVALID "URL is not a valid absolute URI"
               {:url url :reason (.getMessage ^Throwable t)}))))

(defn- resolved-address
  [host allow-private?]
  (try
    (let [addresses (vec (InetAddress/getAllByName host))]
      (cond
        (empty? addresses)
        (failure :SKILL_SOURCE_UNREACHABLE "host resolved to no address"
                 {:host host})

        (and (not allow-private?)
             (some private-address? addresses))
        (failure :SKILL_SOURCE_PRIVATE_ADDRESS
                 "URL resolves to a private, loopback or reserved address"
                 {:host host})

        :else
        {:ok? true :address (first addresses)}))
    (catch UnknownHostException _
      (failure :SKILL_SOURCE_UNREACHABLE "host could not be resolved"
               {:host host}))
    (catch Exception t
      (failure :SKILL_SOURCE_UNREACHABLE "host resolution failed"
               {:host host :reason (.getMessage ^Throwable t)}))))

(defn- request-path
  [^URI uri]
  (let [path (or (.getRawPath uri) "/")
        query (.getRawQuery uri)]
    (if (str/blank? query) path (str path "?" query))))

(defn- connect!
  [scheme address host port timeout-ms]
  (let [socket (Socket.)]
    (.connect socket (InetSocketAddress. address port) timeout-ms)
    (.setSoTimeout socket timeout-ms)
    (if (= "https" scheme)
      (let [ssl (.createSocket (SSLSocketFactory/getDefault)
                               socket host port true)
            params (.getSSLParameters ssl)]
        (.setEndpointIdentificationAlgorithm params "HTTPS")
        (.setServerNames params [(SNIHostName. host)])
        (.setSSLParameters ssl params)
        (.startHandshake ssl)
        ssl)
      socket)))

(defn- read-line!
  [^InputStream input limit]
  (let [out (ByteArrayOutputStream.)]
    (loop []
      (let [b (.read input)]
        (cond
          (neg? b) (str out)
          (= b 10) (str out)
          (= b 13) (recur)
          (> (.size out) limit)
          (error/fail! :SKILL_SOURCE_TOO_LARGE "response header line is too long"
                       {:limit limit})
          :else (do (.write out b) (recur)))))))

(defn- read-bounded
  [^InputStream input limit]
  (let [out (ByteArrayOutputStream.)]
    (loop [total 0]
      (let [b (.read input)]
        (cond
          (neg? b) (.toByteArray out)
          (> (inc total) limit)
          (error/fail! :SKILL_SOURCE_TOO_LARGE "response body exceeds the limit"
                       {:limit limit})
          :else (do (.write out b) (recur (inc total))))))))

(defn- read-exact
  [^InputStream input n]
  (let [out (ByteArrayOutputStream.)]
    (loop [remaining n]
      (when (pos? remaining)
        (let [b (.read input)]
          (when (neg? b)
            (error/fail! :SKILL_SOURCE_HTTP
                         "response ended before the declared body"
                         {:remaining remaining}))
          (.write out b)
          (recur (dec remaining)))))
    (.toByteArray out)))

(defn- read-chunked
  [^InputStream input limit]
  (let [out (ByteArrayOutputStream.)]
    (loop [total 0]
      (let [line (read-line! input 4096)
            size (try (Long/parseLong (first (str/split (str/trim line) #";" 2)) 16)
                      (catch Exception _ -1))]
        (cond
          (neg? size)
          (error/fail! :SKILL_SOURCE_HTTP "invalid chunk size" {:line line})

          (zero? size)
          (do (loop []
                (let [trailer (read-line! input 4096)]
                  (when-not (str/blank? trailer) (recur))))
              (.toByteArray out))

          (> (+ total size) limit)
          (error/fail! :SKILL_SOURCE_TOO_LARGE "response body exceeds the limit"
                       {:limit limit})

          :else
          (do (.write out (read-exact input size))
              (read-line! input 16)
              (recur (+ total size))))))))

(defn- read-body
  [^InputStream input headers limit]
  (let [transfer (some-> (get headers "transfer-encoding") str/lower-case)
        length (try
                 (some-> (get headers "content-length") str/trim Long/parseLong)
                 (catch Exception _ nil))]
    (cond
      (and transfer (str/includes? transfer "chunked"))
      (read-chunked input limit)

      (and length (pos? length))
      (do (when (> length limit)
            (error/fail! :SKILL_SOURCE_TOO_LARGE "response body exceeds the limit"
                         {:limit limit}))
          (read-exact input length))

      :else
      (read-bounded input limit))))

(defn- request-on-socket
  [^Socket socket {:keys [uri scheme host port]} limit]
  (let [host-header (if (or (and (= scheme "http") (= port 80))
                            (and (= scheme "https") (= port 443)))
                      host
                      (str host ":" port))
        request (str "GET " (request-path uri) " HTTP/1.1\r\n"
                     "Host: " host-header "\r\n"
                     "User-Agent: Herdchestra/0.1\r\n"
                     "Accept: text/plain, text/markdown, application/zip, application/octet-stream\r\n"
                     "Connection: close\r\n\r\n")
        out (.getOutputStream socket)
        input (.getInputStream socket)]
    (.write out (.getBytes request StandardCharsets/UTF_8))
    (.flush out)
    (let [status-line (read-line! input 8192)
          status (try
                   (Long/parseLong (second (str/split (str/trim status-line) #"\s+")))
                   (catch Exception _ nil))
          headers (loop [headers {}]
                    (let [line (read-line! input 8192)]
                      (if (str/blank? line)
                        headers
                        (let [idx (str/index-of line ":")]
                          (if idx
                            (recur (assoc headers
                                          (str/lower-case (str/trim (subs line 0 idx)))
                                          (str/trim (subs line (inc idx)))))
                            (recur headers))))))]
      (cond
        (nil? status)
        (failure :SKILL_SOURCE_HTTP "response has no status line" {})

        (<= 300 status 399)
        (failure :SKILL_SOURCE_REDIRECT_REFUSED
                 "redirects are not followed for skill links"
                 {:status status :location (get headers "location")})

        (not (<= 200 status 299))
        (failure :SKILL_SOURCE_HTTP (str "source returned HTTP " status)
                 {:status status})

        :else
        {:ok? true
         :status status
         :headers headers
         :content-type (get headers "content-type")
         :body (read-body input headers limit)}))))

(defn get!
  "Pinned GET.  Returns `{:ok? true :status :body :headers :content-type}` or
  a refusal.  A 3xx response is refused; the request is never replayed."
  ([url] (get! url {}))
  ([url {:keys [timeout-ms limit allow-private?]
         :or {timeout-ms default-timeout-ms limit default-limit}}]
   (let [target (parse-target url)]
     (if-not (:ok? target)
       target
       (let [resolved (resolved-address (:host target) allow-private?)]
         (if-not (:ok? resolved)
           resolved
           (let [socket (try
                          (connect! (:scheme target) (:address resolved)
                                    (:host target) (:port target) timeout-ms)
                          (catch SocketTimeoutException _ ::timeout)
                          (catch Exception t t))]
             (cond
               (= ::timeout socket)
               (failure :SKILL_SOURCE_TIMEOUT "connection timed out" {:url url})

               (instance? Throwable socket)
               (failure :SKILL_SOURCE_UNREACHABLE "connection failed"
                        {:url url :reason (.getMessage ^Throwable socket)})

               :else
               (try
                 (request-on-socket socket target limit)
                 (catch clojure.lang.ExceptionInfo t
                   (if (error/expected? t)
                     {:ok? false :error (error/error-map t)}
                     (throw t)))
                 (finally
                   (try (.close socket) (catch Exception _ nil))))))))))))
