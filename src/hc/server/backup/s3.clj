(ns hc.server.backup.s3
  "A real S3-compatible `TargetPort` with AWS Signature Version 4.

  The target supports path-style (default) and virtual-hosted addressing,
  arbitrary S3-compatible endpoints, a bucket, region, prefix and access
  credentials.  It performs real GET/PUT/DELETE/ListObjectsV2 requests through
  the JDK HTTP client; there is no local-file fallback.

  Credentials are used only to sign requests and are never logged.  Redirects
  are not followed."
  (:require [clojure.string :as str]
            [hc.server.backup.codec :as codec]
            [hc.server.backup.error :as err]
            [hc.server.backup.http-common :as http]
            [hc.server.backup.port :as port])
  (:import (java.net URI)
           (java.time ZonedDateTime ZoneOffset)
           (java.time.format DateTimeFormatter)
           (java.util.regex Pattern)))

(defrecord S3Target [endpoint bucket region access-key secret-key prefix
                     client timeout-ms path-style?])

(def ^:private id-pattern
  (Pattern/compile "^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$"))

(def ^:private amz-date-format
  (DateTimeFormatter/ofPattern "yyyyMMdd'T'HHmmss'Z'"))

(def ^:private date-format
  (DateTimeFormatter/ofPattern "yyyyMMdd"))

(defn- valid-id?
  [id]
  (and (string? id)
       (not (str/blank? id))
       (boolean (re-matches id-pattern id))))

(defn- assert-id!
  [id]
  (when-not (valid-id? id)
    (err/fail! :BACKUP_ID_INVALID
               "S3 object ID is not a safe file name"
               {:id id}))
  id)

(defn- normalize-prefix
  [prefix]
  (-> (str (or prefix ""))
      (str/replace #"^/+" "")
      (str/replace #"/+$" "")))

(defn target
  "Create an S3-compatible target.

  Options:
    :endpoint     required, e.g. \"https://s3.amazonaws.com\" or MinIO URL
    :bucket       required
    :region       required (SigV4 scope)
    :access-key   required
    :secret-key   required
    :prefix       optional object key prefix
    :path-style?  default true; false uses bucket.endpoint virtual hosting
    :timeout-ms   request timeout (default 30000)
    :client       optional preconfigured HttpClient"
  [{:keys [endpoint bucket region access-key secret-key prefix client
           timeout-ms path-style?]
    :or {timeout-ms http/default-timeout-ms path-style? true}}]
  (doseq [[k v] {:endpoint endpoint :bucket bucket :region region
                 :access-key access-key :secret-key secret-key}]
    (when (str/blank? (str v))
      (err/fail! :BACKUP_TARGET_INVALID
                 "S3 target is missing a required option"
                 {:option k})))
  (let [^URI uri (try (URI. (str endpoint))
                 (catch Exception e
                   (err/fail! :BACKUP_TARGET_INVALID
                              "S3 :endpoint is not a valid URI"
                              {:endpoint (str endpoint)}
                              e)))]
    (when-not (contains? #{"http" "https"} (.getScheme uri))
      (err/fail! :BACKUP_TARGET_INVALID
                 "S3 :endpoint must use http or https"
                 {:endpoint (str endpoint)}))
    (->S3Target (str endpoint)
                (str bucket)
                (str region)
                (str access-key)
                (str secret-key)
                (normalize-prefix prefix)
                (or client (http/client timeout-ms))
                (long timeout-ms)
                (boolean path-style?))))

(defn- endpoint-uri
  ^URI [target]
  (URI. (:endpoint target)))

(defn- path-segments
  [path]
  (remove str/blank? (str/split (str path) #"/")))

(defn- base-path
  [target]
  (or (.getRawPath ^URI (endpoint-uri target)) ""))

(defn- authority
  [target]
  (if (:path-style? target)
    (.getRawAuthority (endpoint-uri target))
    (let [^URI base (endpoint-uri target)
          host (.getHost base)
          port (.getPort base)]
      (str (:bucket target) "." host (when (pos? port) (str ":" port))))))

(defn- key-segments
  [target id]
  (concat (path-segments (:prefix target))
          [(str (assert-id! id) ".hcb")]))

(defn- object-path
  [target id]
  (let [segments (concat
                  (when (:path-style? target) [(:bucket target)])
                  (key-segments target id))]
    (str (if (str/ends-with? (base-path target) "/")
           (base-path target)
           (str (base-path target) "/"))
         (str/join "/" (map http/encode-segment segments)))))

(defn- object-uri
  ^URI [target id]
  (let [^URI base (endpoint-uri target)]
    (URI. (str (.getScheme base) "://" (authority target) (object-path target id)))))

(defn- bucket-path
  [target]
  (let [segments (when (:path-style? target) [(:bucket target)])]
    (str (if (str/ends-with? (base-path target) "/")
           (base-path target)
           (str (base-path target) "/"))
         (str/join "/" (map http/encode-segment segments)))))

(defn- bucket-uri
  ^URI [target]
  (let [^URI base (endpoint-uri target)]
    (URI. (str (.getScheme base) "://" (authority target) (bucket-path target)))))

(defn- canonical-uri
  [^URI uri]
  (let [path (.getRawPath uri)]
    (if (str/blank? path) "/" path)))

(defn- canonical-query
  [query]
  (->> query
       (map (fn [[k v]] [(http/encode-query-value k)
                         (http/encode-query-value v)]))
       (sort-by first)
       (map (fn [[k v]] (str k "=" v)))
       (str/join "&")))

(defn- hmac
  ^bytes [^bytes key ^bytes data]
  (codec/hmac-sha256 key data))

(defn- signing-key
  [target date]
  (let [k0 (codec/utf8-bytes (str "AWS4" (:secret-key target)))
        k1 (hmac k0 (codec/utf8-bytes date))
        k2 (hmac k1 (codec/utf8-bytes (:region target)))
        k3 (hmac k2 (codec/utf8-bytes "s3"))]
    (hmac k3 (codec/utf8-bytes "aws4_request"))))

(defn- signed-headers
  ([target method uri query body]
   (signed-headers target method uri query body nil))
  ([target method ^URI uri query ^bytes body now]
  (let [^ZonedDateTime now (or now (ZonedDateTime/now ZoneOffset/UTC))
        ^DateTimeFormatter amz-date-format amz-date-format
        ^DateTimeFormatter date-format date-format
        amz-date (.format amz-date-format now)
        date (.format date-format now)
        payload-hash (codec/sha256-hex (or body (byte-array 0)))
        host (.getRawAuthority uri)
        headers {"host" host
                 "x-amz-content-sha256" payload-hash
                 "x-amz-date" amz-date}
        header-names (sort (keys headers))
        canonical-headers (apply str
                                 (map (fn [k] (str k ":" (get headers k) "\n"))
                                      header-names))
        signed-header-names (str/join ";" header-names)
        canonical-request (str (str/upper-case (name method)) "\n"
                               (canonical-uri uri) "\n"
                               (canonical-query query) "\n"
                               canonical-headers "\n"
                               signed-header-names "\n"
                               payload-hash)
        scope (str date "/" (:region target) "/s3/aws4_request")
        string-to-sign (str "AWS4-HMAC-SHA256\n" amz-date "\n" scope "\n"
                            (codec/sha256-hex (codec/utf8-bytes canonical-request)))
        signature (codec/hex
                   (hmac (signing-key target date)
                         (codec/utf8-bytes string-to-sign)))
        authorization (str "AWS4-HMAC-SHA256 Credential="
                           (:access-key target) "/" scope
                           ", SignedHeaders=" signed-header-names
                           ", Signature=" signature)]
    {"x-amz-content-sha256" payload-hash
     "x-amz-date" amz-date
     "authorization" authorization})))

(defn- request
  [target method uri query body]
  (http/request! (:client target)
                 method
                 uri
                 (signed-headers target method uri query body)
                 body
                 (:timeout-ms target)))

(defn- query-uri
  [^URI uri query]
  (when (seq query)
    (URI. (str (.getScheme uri) "://" (.getRawAuthority uri)
               (.getRawPath uri) "?" (canonical-query query)))))

(defn- request-with-query
  [target method uri query body]
  (request target method (query-uri uri query) query body))

(defn- conflict!
  [id]
  (err/fail! :BACKUP_OBJECT_CONFLICT
             "S3 target already has different bytes for this ID"
             {:id id}))

(defn- get-bytes
  [target uri]
  (let [response (request target :get uri {} nil)]
    (case (long (:status response))
      200 (:body response)
      404 nil
      (do (http/ensure-success! response uri :get)
          (:body response)))))

(defn- listing-entry
  [target content]
  (let [key (http/element-text content "Key")
        id (when (and key (str/ends-with? (str/lower-case key) ".hcb"))
             (-> key
                 (str/split #"/")
                 last
                 (str/replace #"\.hcb$" "")))]
    (when id
      (let [uri (object-uri target id)]
        (try
          (let [bytes (get-bytes target uri)]
            (if bytes
              (http/object-metadata id bytes)
              (http/invalid-metadata id nil nil)))
          (catch clojure.lang.ExceptionInfo e
            (http/invalid-metadata id nil e)))))))

(extend-type S3Target
  port/TargetPort
  (put-object! [target object]
    (let [id (assert-id! (:id object))
          ^bytes bytes (:bytes object)
          _ (when-not (bytes? bytes)
              (err/fail! :BACKUP_FORMAT_INVALID
                         "S3 object bytes must be a byte array"
                         {:id id}))
          uri (object-uri target id)
          existing (get-bytes target uri)]
      (if existing
        (if (java.util.Arrays/equals ^bytes existing bytes)
          (assoc (http/object-metadata id bytes)
                 :deduplicated? true
                 :url (str uri))
          (conflict! id))
        (let [response (request target :put uri {} bytes)]
          (http/ensure-one-of! response uri :put [200 201 204])
          (assoc (http/object-metadata id bytes)
                 :deduplicated? false
                 :url (str uri))))))

  (get-object [target id]
    (get-bytes target (object-uri target id)))

  (delete-object! [target id]
    (let [uri (object-uri target id)
          response (request target :delete uri {} nil)]
      (when-not (contains? #{200 202 204 404} (long (:status response)))
        (http/ensure-success! response uri :delete))
      :deleted))

  (list-objects [target]
    (let [base (bucket-uri target)]
      (loop [token nil
             pages 0
             entries []]
        (when (> pages 1000)
          (err/fail! :BACKUP_TARGET_HTTP
                     "S3 listing exceeded the page limit"
                     {:bucket (:bucket target)}))
        (let [query (cond-> {"list-type" "2"
                             "prefix" (if (str/blank? (:prefix target))
                                        ""
                                        (str (:prefix target) "/"))}
                      token (assoc "continuation-token" token))
              response (request-with-query target :get base query nil)]
          (http/ensure-success! response base :list)
          (let [document (http/parse-xml (:body response))
                contents (http/elements document "Contents")
                entries (into entries
                              (keep #(listing-entry target %))
                              contents)
                truncated? (= "true"
                              (some-> ^org.w3c.dom.Node
                                      (first (http/elements document "IsTruncated"))
                                      (.getTextContent)
                                      str/trim
                                      str/lower-case))
                next-token (some-> ^org.w3c.dom.Node
                                   (first (http/elements document "NextContinuationToken"))
                                   (.getTextContent)
                                   str/trim)]
            (if (and truncated? (not (str/blank? next-token)))
              (recur next-token (inc pages) entries)
              (->> entries
                   (sort-by (fn [m] (or (:created-at m) "")))
                   reverse
                   vec)))))))

  (verify-object [target id expected-sha256]
    (let [bytes (get-bytes target (object-uri target id))]
      (when-not bytes
        (err/fail! :BACKUP_NOT_FOUND
                   "S3 object does not exist"
                   {:id id}))
      (let [actual (codec/sha256-hex bytes)]
        (when-not (= expected-sha256 actual)
          (err/fail! :BACKUP_INTEGRITY
                     "S3 object digest does not match"
                     {:id id :expected-sha256 expected-sha256
                      :actual-sha256 actual}))
        (http/object-metadata id bytes)))))
