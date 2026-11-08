(ns hc.server.backup.http-common
  "Shared JDK HTTP and XML helpers for WebDAV and S3 targets.

  No third-party client is used.  Every request has a timeout, redirects are
  never followed (credentials must not leak to another host), and failures
  carry the HTTP status and a bounded response excerpt."
  (:require [clojure.string :as str]
            [hc.server.backup.codec :as codec]
            [hc.server.backup.error :as err]
            [hc.server.backup.package :as package])
  (:import (java.io ByteArrayInputStream)
           (java.net URI)
           (java.net.http HttpClient HttpClient$Redirect HttpHeaders
                          HttpRequest HttpRequest$BodyPublishers
                          HttpResponse HttpResponse$BodyHandlers)
           (java.nio.charset StandardCharsets)
           (java.time Duration)
           (java.util Base64)
           (javax.xml.parsers DocumentBuilderFactory)
           (org.w3c.dom Document Element Node NodeList)))

(def default-timeout-ms 30000)

(defn client
  [timeout-ms]
  (-> (HttpClient/newBuilder)
      (.connectTimeout (Duration/ofMillis (long (or timeout-ms default-timeout-ms))))
      (.followRedirects HttpClient$Redirect/NEVER)
      (.build)))

(defn basic-auth
  [username password]
  (str "Basic " (.encodeToString (Base64/getEncoder)
                                 (.getBytes (str username ":" password)
                                            StandardCharsets/UTF_8))))

(defn- bounded-body
  [^bytes body]
  (when body
    (let [text (String. ^bytes body StandardCharsets/UTF_8)]
      (subs text 0 (min 512 (count text))))))

(defn request!
  "Send one HTTP request.  Returns {:status int :headers map :body byte[]}.
  A network failure is :BACKUP_TARGET_IO; the caller classifies HTTP statuses."
  [^HttpClient client method ^URI uri headers body timeout-ms]
  (let [builder (-> (HttpRequest/newBuilder)
                    (.uri uri)
                    (.timeout (Duration/ofMillis (long (or timeout-ms default-timeout-ms)))))
        _ (doseq [[k v] headers]
            (when (and k v)
              (.header builder (str k) (str v))))
        publisher (if body
                    (HttpRequest$BodyPublishers/ofByteArray ^bytes body)
                    (HttpRequest$BodyPublishers/noBody))
        request (.build (.method builder (str/upper-case (name method)) publisher))]
    (try
      (let [^HttpResponse response (.send client request
                                                 (HttpResponse$BodyHandlers/ofByteArray))
            ^HttpHeaders response-headers (.headers response)]
        {:status (.statusCode response)
         :headers (into {} (map (fn [[k v]] [(str/lower-case k) v]))
                        (.map response-headers))
         :body (.body response)})
      (catch InterruptedException e
        (.interrupt (Thread/currentThread))
        (err/fail! :BACKUP_TARGET_IO
                   "HTTP target request was interrupted"
                   {:uri (str uri)}
                   e))
      (catch java.io.IOException e
        (err/fail! :BACKUP_TARGET_IO
                   "HTTP target request failed"
                   {:uri (str uri)
                    :message (.getMessage e)}
                   e)))))

(defn ensure-success!
  [response uri operation]
  (when-not (<= 200 (long (:status response)) 299)
    (err/fail! :BACKUP_TARGET_HTTP
               "HTTP target refused the request"
               {:operation operation
                :uri (str uri)
                :status (:status response)
                :body (bounded-body (:body response))}))
  response)

(defn ensure-one-of!
  [response uri operation statuses]
  (when-not (contains? (set statuses) (long (:status response)))
    (err/fail! :BACKUP_TARGET_HTTP
               "HTTP target returned an unexpected status"
               {:operation operation
                :uri (str uri)
                :status (:status response)
                :expected (vec statuses)
                :body (bounded-body (:body response))}))
  response)

(defn response-excerpt
  "A short, credential-free summary of an HTTP response body for probe
  details.  Never includes request headers or signatures."
  [^bytes body limit]
  (when body
    (let [text (String. ^bytes body StandardCharsets/UTF_8)]
      (subs text 0 (min (long (or limit 200)) (count text))))))

(defn- unreserved-byte?
  [b]
  (let [n (bit-and (int b) 0xff)]
    (or (<= (int \A) n (int \Z))
        (<= (int \a) n (int \z))
        (<= (int \0) n (int \9))
        (contains? #{(int \-) (int \.) (int \_) (int \~)} n))))

(defn encode-segment
  "Percent-encode one URI path segment.  Slashes are never part of a segment.
  This is the AWS/WebDAV unreserved-character encoder, not form encoding:
  space is %20, and * is %2A."
  [s]
  (let [bytes (.getBytes (str s) StandardCharsets/UTF_8)]
    (apply str
           (map (fn [b]
                  (if (unreserved-byte? b)
                    (String. (byte-array [(byte b)]) StandardCharsets/UTF_8)
                    (format "%%%02X" (bit-and (int b) 0xff))))
                bytes))))

(defn encode-path
  [path]
  (str/join "/" (map encode-segment (str/split (str path) #"/" -1))))

(defn encode-query-value
  [s]
  (encode-segment s))

(defn object-metadata
  "Verify a complete backup container and return its public metadata."
  [id ^bytes bytes]
  (let [{:keys [header]} (package/verify-container bytes)]
    (assoc (package/public-metadata header)
           :encrypted? (= :aes-gcm (:encryption header))
           :id id
           :size (alength bytes)
           :sha256 (codec/sha256-hex bytes)
           :integrity :verified)))

(defn invalid-metadata
  [id ^bytes bytes error]
  (cond-> {:id id
           :size (when bytes (alength ^bytes bytes))
           :integrity :invalid
           :error (or (err/code error) :BACKUP_FORMAT_INVALID)}
    error (assoc :message (.getMessage ^Throwable error))))

(defn parse-xml
  [^bytes body]
  (let [factory (doto (DocumentBuilderFactory/newInstance)
                  (.setNamespaceAware true)
                  (.setXIncludeAware false)
                  (.setExpandEntityReferences false)
                  (.setFeature "http://apache.org/xml/features/disallow-doctype-decl" true)
                  (.setFeature "http://xml.org/sax/features/external-general-entities" false)
                  (.setFeature "http://xml.org/sax/features/external-parameter-entities" false))
        builder (.newDocumentBuilder factory)]
    (try
      (.parse builder (ByteArrayInputStream. body))
      (catch Exception e
        (err/fail! :BACKUP_TARGET_HTTP
                   "HTTP target returned unreadable XML"
                   {:message (.getMessage e)}
                   e)))))

(defn elements
  [^Document document local-name]
  (let [^NodeList nodes (.getElementsByTagNameNS document "*" local-name)]
    (loop [index 0
           result []]
      (if (< index (.getLength nodes))
        (recur (inc index) (conj result (.item nodes index)))
        result))))

(defn element-text
  [^Element element local-name]
  (let [nodes (.getElementsByTagNameNS element "*" local-name)]
    (when (pos? (.getLength nodes))
      (some-> (.item nodes 0) (.getTextContent) str/trim))))

