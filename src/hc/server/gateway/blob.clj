(ns hc.server.gateway.blob
  "Independent HTTP transport for managed blobs.

  The realtime Sente channel carries IDs, status and readiness only.  Bytes
  travel here, over ordinary HTTP, with content-addressed immutable URLs:

      GET|HEAD /blobs/<sha256-id>   download, optional single byte range
      PUT      /blobs               upload, returns {:id ... :size ...}

  A blob ID is the SHA-256 of its bytes.  Responses are therefore immutable
  and can be cached for a year.  The gateway never deletes a blob: reference
  accounting belongs to the domain Owner and `hc.server.blob-store`."
  (:require [clojure.string :as str]
            [hc.server.blob-store :as blob]
            [hc.server.gateway.ports :as ports])
  (:import [java.io ByteArrayInputStream FilterInputStream InputStream]
           [java.nio.charset StandardCharsets]))

(def default-max-upload-bytes (* 100 1024 1024))

;; ---------------------------------------------------------------------------
;; Responses

(defn- edn-response
  ([status body] (edn-response status body {}))
  ([status body headers]
   {:status status
    :headers (merge {"content-type" "application/edn; charset=utf-8"
                     "cache-control" "no-store"}
                    headers)
    :body (pr-str body)}))

(defn- error-response
  ([status code message] (error-response status code message nil))
  ([status code message details]
   (edn-response status
                 (cond-> {:error code :message message}
                   details (assoc :details details)))))

(defn- header
  [request header-name]
  (let [headers (:headers request)
        header-name (str/lower-case header-name)]
    (or (get headers header-name)
        (some (fn [[k v]]
                (when (= header-name (str/lower-case (name k))) v))
              headers))))

(defn- etag
  [id]
  (str "\"" id "\""))

(defn- if-none-match?
  [request id]
  (let [value (header request "if-none-match")]
    (or (= "*" (some-> value str/trim))
        (some (fn [candidate]
                (= (str/trim candidate) (etag id)))
              (some-> value (str/split #","))))))

;; ---------------------------------------------------------------------------
;; Streams

(defn- skip-fully
  [^InputStream in n]
  (loop [remaining (long n)]
    (when (pos? remaining)
      (let [skipped (.skip in remaining)]
        (if (pos? skipped)
          (recur (- remaining skipped))
          ;; `skip` may return zero before EOF on some streams; read one byte.
          (when-not (neg? (.read in))
            (recur (dec remaining))))))))

(defn- limited-input-stream
  [^InputStream in n]
  (let [remaining (volatile! (long n))]
    (proxy [FilterInputStream] [in]
      (read
        ([]
         (if (pos? @remaining)
           (let [b (.read in)]
             (when-not (neg? b) (vswap! remaining dec))
             b)
           -1))
        ([buffer]
         (let [allowed (min (alength ^bytes buffer) @remaining)]
           (if (pos? allowed)
             (let [read (.read in ^bytes buffer 0 (int allowed))]
               (when (pos? read) (vswap! remaining - read))
               read)
             -1)))
        ([buffer offset length]
         (let [allowed (min (long length) @remaining)]
           (if (pos? allowed)
             (let [read (.read in ^bytes buffer (int offset) (int allowed))]
               (when (pos? read) (vswap! remaining - read))
               read)
             -1)))))))

(defn- bounded-input-stream
  "Reject an upload once it exceeds LIMIT bytes."
  [^InputStream in limit]
  (let [total (volatile! 0)]
    (proxy [FilterInputStream] [in]
      (read
        ([]
         (let [b (.read in)]
           (when-not (neg? b)
             (when (> (vswap! total inc) limit)
               (throw (ex-info "blob upload exceeds the configured limit"
                               {:code :BLOB_TOO_LARGE
                                :limit limit}))))
           b))
        ([buffer]
         (let [read (.read in ^bytes buffer 0 (alength ^bytes buffer))]
           (when (pos? read)
             (when (> (vswap! total + read) limit)
               (throw (ex-info "blob upload exceeds the configured limit"
                               {:code :BLOB_TOO_LARGE
                                :limit limit}))))
           read))
        ([buffer offset length]
         (let [read (.read in ^bytes buffer (int offset) (int length))]
           (when (pos? read)
             (when (> (vswap! total + read) limit)
               (throw (ex-info "blob upload exceeds the configured limit"
                               {:code :BLOB_TOO_LARGE
                                :limit limit}))))
           read))))))

(defn- input-stream
  [body]
  (cond
    (instance? InputStream body) body
    (string? body) (ByteArrayInputStream. (.getBytes ^String body
                                                     StandardCharsets/UTF_8))
    (bytes? body) (ByteArrayInputStream. ^bytes body)
    :else nil))

;; ---------------------------------------------------------------------------
;; Range requests

(defn- parse-long*
  [value]
  (try
    (Long/parseLong value)
    (catch NumberFormatException _ nil)))

(defn parse-range
  "Parse one HTTP byte range against SIZE.

  Returns nil for no range, `:invalid` for an unsatisfiable or unsupported
  range, or `{:start n :end n}` with inclusive indexes."
  [value size]
  (when value
    (let [value (str/lower-case (str/trim value))]
      (if-let [[_ first second] (re-matches #"bytes=(\d*)-(\d*)" value)]
        (let [size (long size)]
          (cond
            (and (str/blank? first) (str/blank? second)) :invalid
            (zero? size) :invalid
            (str/blank? first)
            (let [suffix (parse-long* second)]
              (if (and suffix (pos? suffix))
                {:start (max 0 (- size suffix)) :end (dec size)}
                :invalid))
            :else
            (let [start (parse-long* first)
                  end (if (str/blank? second) (dec size) (parse-long* second))]
              (if (and start end (<= 0 start) (< start size) (<= start end))
                {:start start :end (min end (dec size))}
                :invalid))))
        :invalid))))

;; ---------------------------------------------------------------------------
;; Download and upload

(defn- blob-path-id
  [uri]
  (when (and (string? uri) (str/starts-with? uri "/blobs/"))
    (let [id (subs uri (count "/blobs/"))]
      (when (and (seq id) (not (str/includes? id "/")))
        id))))

(defn- safe-filename
  [request]
  (let [value (or (get-in request [:params :filename])
                  (get-in request [:params "filename"]))]
    (when (and (string? value)
               (re-matches #"[A-Za-z0-9._-]{1,128}" value))
      value)))

(defn- content-type
  [opts id metadata]
  (let [value (or (when-let [f (:content-type-fn opts)]
                    (f id metadata))
                  "application/octet-stream")]
    (if (and (string? value)
             (not (re-find #"[\r\n]" value))
             (re-matches #"[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+(?:[ \t]*;.*)?"
                         value))
      value
      "application/octet-stream")))

(def ^:private active-content-types
  "Types a browser can execute or render as an active document.  A blob is
  content-addressed bytes, not a trusted application document, so these
  responses carry a sandbox policy even when opened directly without the
  `?filename=` attachment disposition."
  #{"text/html" "application/xhtml+xml" "image/svg+xml"
    "application/xml" "text/xml"})

(defn- active-content-type?
  [media-type]
  (let [base (-> (str media-type)
                 str/lower-case
                 (str/split #";" 2)
                 first
                 str/trim)]
    (contains? active-content-types base)))

(defn- download
  [store opts request id]
  (cond
    (not (blob/valid-id? id))
    (error-response 400 :BLOB_INVALID_ID
                    "blob ID must be a canonical sha256 content ID"
                    {:id id})

    (nil? store)
    (error-response 503 :BLOB_STORE_UNAVAILABLE
                    "no blob store is attached to this gateway")

    :else
    (let [metadata (ports/blob-metadata store id)]
      (if (nil? metadata)
        (error-response 404 :BLOB_NOT_FOUND "blob does not exist" {:id id})
        (let [size (long (:size metadata))
              media-type (content-type opts id metadata)
              base {"etag" (etag id)
                    "cache-control" "public, max-age=31536000, immutable"
                    "accept-ranges" "bytes"
                    "x-content-type-options" "nosniff"
                    "content-type" media-type}
              base (cond-> base
                     (active-content-type? media-type)
                     (assoc "content-security-policy" "sandbox"))
              base (if-let [filename (safe-filename request)]
                     (assoc base "content-disposition"
                            (str "attachment; filename=\"" filename "\""))
                     base)]
          (cond
            (if-none-match? request id)
            {:status 304 :headers base :body nil}

            :else
            (let [range (parse-range (header request "range") size)
                  head? (= :head (:request-method request))]
              (cond
                (= range :invalid)
                (update (error-response 416 :BLOB_RANGE_INVALID
                                        "requested byte range is not satisfiable"
                                        {:id id :size size})
                        :headers merge
                        base
                        {"content-range" (str "bytes */" size)})

                (nil? range)
                (if head?
                  {:status 200
                   :headers (assoc base "content-length" (str size))
                   :body nil}
                  (let [stream (ports/open-blob store id)]
                    (if (nil? stream)
                      (error-response 404 :BLOB_NOT_FOUND
                                      "blob disappeared before it could be opened"
                                      {:id id})
                      {:status 200
                       :headers (assoc base "content-length" (str size))
                       :body stream})))

                :else
                (let [{:keys [start end]} range
                      length (inc (- end start))]
                  (if head?
                    {:status 206
                     :headers (assoc base
                                     "content-length" (str length)
                                     "content-range" (str "bytes " start "-" end "/" size))
                     :body nil}
                    (let [stream (ports/open-blob store id)]
                      (if (nil? stream)
                        (error-response 404 :BLOB_NOT_FOUND
                                        "blob disappeared before it could be opened"
                                        {:id id})
                        (do
                          (skip-fully stream start)
                          {:status 206
                           :headers (assoc base
                                           "content-length" (str length)
                                           "content-range" (str "bytes " start "-" end "/" size))
                           :body (limited-input-stream stream length)})))))))))))))

(defn- upload
  [store opts request]
  (cond
    (nil? store)
    (error-response 503 :BLOB_STORE_UNAVAILABLE
                    "no blob store is attached to this gateway")

    :else
    (let [limit (long (or (:max-upload-bytes opts) default-max-upload-bytes))
          body (input-stream (:body request))]
      (if (nil? body)
        (error-response 400 :BLOB_BODY_REQUIRED
                        "upload requires a request body")
        (try
          (let [metadata (ports/put-blob! store
                                          (bounded-input-stream body limit)
                                          {})]
            (edn-response 201
                          (select-keys metadata [:id :size :sha256])
                          {"location" (str "/blobs/" (:id metadata))}))
          (catch clojure.lang.ExceptionInfo t
            (let [{:keys [code]} (ex-data t)]
              (case code
                :BLOB_TOO_LARGE
                (error-response 413 :BLOB_TOO_LARGE
                                "upload exceeds the configured size limit"
                                {:limit limit})

                (:BLOB_INVALID_ID :BLOB_INVALID_DIGEST :BLOB_INTEGRITY)
                (error-response 400 code (.getMessage t))

                (error-response 500 (or code :BLOB_UPLOAD_FAILED)
                                (or (.getMessage t) "blob upload failed")))))
          (catch Throwable t
            (error-response 500 :BLOB_UPLOAD_FAILED
                            (or (.getMessage t) "blob upload failed"))))))))

(defn handle
  "Handle a blob request.  Returns a Ring response, or nil when the request is
  not a blob route."
  [store opts request]
  (let [method (:request-method request)
        uri (:uri request)]
    (cond
      (and (= "/blobs" uri) (contains? #{:put :post} method))
      (upload store opts request)

      (blob-path-id uri)
      (if (contains? #{:get :head} method)
        (download store opts request (blob-path-id uri))
        (error-response 405 :METHOD_NOT_ALLOWED
                        "blob downloads support GET and HEAD only"))

      :else nil)))
