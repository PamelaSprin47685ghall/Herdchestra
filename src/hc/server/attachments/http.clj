(ns hc.server.attachments.http
  "Independent HTTP ingestion route for attachments.

  Bytes never travel over the Sente channel (§7.3).  The client sends one
  raw body to:

      POST /attachments?name=<display-name>&draft-count=<n>
      content-type: <declared media type>
      x-attachment-name: <display-name>        (alternative to ?name)

  The response is EDN with the attachment descriptor.  The route is owned by
  the application, not the gateway, so the app can mount it before static
  files while sharing the same `BlobStorePort` as `/blobs`."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [hc.server.attachments.ingest :as ingest])
  (:import (java.io ByteArrayInputStream InputStream)
           (java.net URLDecoder)
           (java.nio.charset StandardCharsets)))

(defn- edn-response
  ([status body] (edn-response status body {}))
  ([status body headers]
   {:status status
    :headers (merge {"content-type" "application/edn; charset=utf-8"
                     "cache-control" "no-store"}
                    headers)
    :body (pr-str body)}))

(defn- error-response
  ([status code message]
   (error-response status code message nil))
  ([status code message details]
   (edn-response status
                 (cond-> {:ok? false :error code :message message}
                   (some? details) (assoc :details details)))))

(defn- header
  [request header-name]
  (let [header-name (str/lower-case header-name)]
    (or (get-in request [:headers header-name])
        (some (fn [[key value]]
                (when (= header-name (str/lower-case (name key))) value))
              (:headers request)))))

(defn- url-decode
  [value]
  (try
    (URLDecoder/decode value "UTF-8")
    (catch Throwable _ value)))

(defn- parse-pairs
  [query]
  (if (str/blank? query)
    {}
    (into {}
          (keep (fn [pair]
                  (when-not (str/blank? pair)
                    (let [[key value] (str/split pair #"=" 2)]
                      [(keyword (url-decode (or key "")))
                       (url-decode (or value ""))]))))
          (str/split query #"&" -1))))

(defn- param
  [request key]
  (let [key (keyword key)]
    (or (get-in request [:params key])
        (get-in request [:query-params key])
        (get-in request [:form-params key])
        (get (parse-pairs (:query-string request)) key)
        (get-in request [:params (name key)])
        (get-in request [:query-params (name key)]))))

(defn- body-stream
  [body]
  (cond
    (instance? InputStream body) body
    (string? body) (ByteArrayInputStream. (.getBytes ^String body StandardCharsets/UTF_8))
    (bytes? body) (ByteArrayInputStream. ^bytes body)
    :else nil))

(defn- declared-size
  [request]
  (when-let [value (or (header request "content-length")
                       (param request :size))]
    (try
      (Long/parseLong (str value))
      (catch NumberFormatException _ nil))))

(defn- draft-count
  [request]
  (when-let [value (or (header request "x-draft-count")
                       (param request :draft-count)
                       (param request :draft_count))]
    (try
      (Long/parseLong (str value))
      (catch NumberFormatException _ nil))))

(defn- attachment-name
  [request]
  (let [value (or (header request "x-attachment-name")
                  (param request :name)
                  (param request :filename))]
    (when (and (string? value) (not (str/blank? value)))
      ;; Query parameters are already decoded by `parse-pairs`; the header
      ;; carries the raw display name.  Decoding here would corrupt names
      ;; containing a literal percent escape.
      value)))

(def ^:private max-extract-body-bytes (* 1024 1024))

(defn- read-text-body
  [body limit]
  (let [limit (long limit)]
    (cond
      (string? body)
      (if (<= (alength (.getBytes ^String body StandardCharsets/UTF_8)) limit)
        {:ok? true :text body}
        {:ok? false :error :ATTACHMENT_EXTRACT_BODY_TOO_LARGE})

      (bytes? body)
      (if (<= (alength ^bytes body) limit)
        {:ok? true :text (String. ^bytes body StandardCharsets/UTF_8)}
        {:ok? false :error :ATTACHMENT_EXTRACT_BODY_TOO_LARGE})

      (instance? InputStream body)
      (let [out (java.io.ByteArrayOutputStream.)
            buffer (byte-array 8192)]
        (loop [total 0]
          (let [n (.read ^InputStream body buffer)]
            (cond
              (neg? n)
              {:ok? true :text (String. (.toByteArray out) StandardCharsets/UTF_8)}

              (> (+ total n) limit)
              {:ok? false :error :ATTACHMENT_EXTRACT_BODY_TOO_LARGE}

              :else
              (do (.write out buffer 0 n)
                  (recur (+ total n)))))))

      :else
      {:ok? false :error :ATTACHMENT_EXTRACT_BODY_REQUIRED})))

(defn- extract-status
  [code]
  (case code
    (:EXTRACTOR_UNAVAILABLE :OCR_UNAVAILABLE :ROUTE_UNAVAILABLE) 503
    :ATTACHMENT_NOT_LOCAL 409
    :ATTACHMENT_STORE_UNAVAILABLE 503
    (:SELECTION_REQUIRED :INVALID_REQUEST :INVALID_EDN
     :ATTACHMENT_EXTRACT_BODY_REQUIRED :ATTACHMENT_EXTRACT_BODY_TOO_LARGE) 400
    :UNSUPPORTED_MIME 415
    (:NEEDS_PASSWORD :WRONG_PASSWORD :NO_TEXT :OCR_EMPTY :PAGE_RANGE
     :ENCODING_UNKNOWN :SOURCE_UNREADABLE) 422
    422))

(defn- extract-handler
  [service request]
  (cond
    (nil? service)
    (error-response 503 :ATTACHMENT_STORE_UNAVAILABLE
                    "attachment service is not configured")

    :else
    (let [content-type (some-> (header request "content-type")
                               str/lower-case
                               (str/split #";" 2)
                               first
                               str/trim)
          readable (read-text-body (:body request) max-extract-body-bytes)]
      (cond
        (and content-type
             (not (contains? #{"application/edn" "text/plain" "application/json"}
                             content-type)))
        (error-response 415 :UNSUPPORTED_MIME
                        "extraction request must be EDN"
                        {:content-type content-type})

        (not (:ok? readable))
        (error-response (if (= :ATTACHMENT_EXTRACT_BODY_TOO_LARGE (:error readable))
                          413 400)
                        (:error readable)
                        (case (:error readable)
                          :ATTACHMENT_EXTRACT_BODY_TOO_LARGE
                          "extraction request exceeds 1 MiB"
                          "extraction request body is required"))

        :else
        (let [parsed (try
                       (edn/read-string (:text readable))
                       (catch Throwable error
                         {:error {:code :INVALID_EDN
                                  :message (.getMessage ^Throwable error)}}))
              attachment (:attachment parsed)
              selection (:selection parsed)
              password (:password parsed)]
          (cond
            (not (map? parsed))
            (error-response 400 :INVALID_REQUEST
                            "extraction request must be an EDN map")

            (contains? parsed :error)
            (error-response 400 :INVALID_EDN
                            (get-in parsed [:error :message]))

            (not (map? attachment))
            (error-response 400 :INVALID_REQUEST
                            "extraction request needs an attachment map")

            (not (map? selection))
            (error-response 400 :SELECTION_REQUIRED
                            "extraction request needs an explicit selection map")

            (and (some? password) (not (string? password)))
            (error-response 400 :INVALID_REQUEST
                            "password must be a string when present")

            :else
            (let [result (ingest/extract-attachment! service attachment selection
                                                      {:password password})]
              (if (:ok? result)
                (edn-response 200 result)
                (let [code (get-in result [:error :code])]
                  (error-response (extract-status code)
                                  (or code :EXTRACTION_FAILED)
                                  (or (get-in result [:error :message])
                                      "attachment extraction failed")
                                  (select-keys result [:extraction :pages])))))))))))

(defn- status-for
  [code]
  (case code
    :ATTACHMENT_TOO_LARGE 413
    :BLOB_TOO_LARGE 413
    :ATTACHMENT_LIMIT_EXCEEDED 409
    :ATTACHMENT_NAME_INVALID 400
    :ATTACHMENT_BODY_REQUIRED 400
    :ATTACHMENT_SIZE_INVALID 400
    :ATTACHMENT_SOURCE_INVALID 400
    :ATTACHMENT_SOURCE_UNREADABLE 400
    :WORKSPACE_PATH_ABSOLUTE 400
    :WORKSPACE_PATH_ESCAPE 400
    :WORKSPACE_ROOT_INVALID 400
    :WORKSPACE_FILE_UNREADABLE 400
    :ATTACHMENT_STORE_UNAVAILABLE 503
    500))

(defn- ingest-handler
  [service request]
  (let [stream (body-stream (:body request))
        name (attachment-name request)
        declared-mime (header request "content-type")
        declared-size (declared-size request)
        count (draft-count request)
        max-bytes (get-in service [:limits :max-bytes])
        max-items (get-in service [:limits :max-items])]
    (cond
      (nil? service)
      (error-response 503 :ATTACHMENT_STORE_UNAVAILABLE
                      "attachment service is not configured")

      (nil? stream)
      (error-response 400 :ATTACHMENT_BODY_REQUIRED
                      "attachment ingestion needs a request body")

      (str/blank? name)
      (error-response 400 :ATTACHMENT_NAME_INVALID
                      "attachment needs a display name")

      (and (integer? declared-size) (neg? declared-size))
      (error-response 400 :ATTACHMENT_SIZE_INVALID
                      "attachment size cannot be negative")

      (and (integer? declared-size) (> declared-size max-bytes))
      (error-response 413 :ATTACHMENT_TOO_LARGE
                      "a single attachment may be at most 100 MiB"
                      {:size declared-size :limit max-bytes})

      (and (integer? count) (>= count max-items))
      (error-response 409 :ATTACHMENT_LIMIT_EXCEEDED
                      "a draft supports at most 20 attachments"
                      {:count count :limit max-items})

      :else
      (let [result (try
                     (ingest/ingest-stream! service
                                            {:name name
                                             :mime declared-mime
                                             :size declared-size
                                             :stream stream
                                             :source :local-import
                                             :draft-count count})
                     (finally
                       (when (instance? InputStream stream)
                         (try (.close ^InputStream stream)
                              (catch Throwable _ nil)))))]
        (if (:ok? result)
          (edn-response 201
                        (assoc result :url (:url (:attachment result)))
                        {"location" (:url (:attachment result))})
          (let [{:keys [code message details]} (:error result)]
            (error-response (status-for code) code message details)))))))

(defn- workspace-handler
  "Associate one relative workspace file without uploading its bytes.

  `:snapshot?` defaults to false here: the operator explicitly asked for a
  live reference, so the server never silently replaces it with a copy.  The
  response descriptor keeps `:local-copy? false` and the original stamp, and
  the draft policy requires a saved copy before an original file may travel."
  [service request]
  (if (nil? service)
    (error-response 503 :ATTACHMENT_STORE_UNAVAILABLE
                    "attachment service is not configured")
    (let [root (:workspace-root service)
          readable (read-text-body (:body request) max-extract-body-bytes)]
      (cond
        (not (:ok? readable))
        (error-response (if (= :ATTACHMENT_EXTRACT_BODY_TOO_LARGE (:error readable))
                          413 400)
                        (:error readable)
                        "workspace request body is required")

        (nil? root)
        (error-response 503 :WORKSPACE_UNAVAILABLE
                        "no workspace root is configured")

        :else
        (let [parsed (try
                       (edn/read-string (:text readable))
                       (catch Throwable error
                         {:error {:code :INVALID_EDN
                                  :message (.getMessage ^Throwable error)}}))
              relative (:relative-path parsed)]
          (cond
            (not (map? parsed))
            (error-response 400 :INVALID_REQUEST
                            "workspace request must be an EDN map")

            (contains? parsed :error)
            (error-response 400 :INVALID_EDN
                            "workspace request is not valid EDN")

            (not (and (string? relative)
                      (not (str/blank? relative))))
            (error-response 400 :ATTACHMENT_SOURCE_INVALID
                            "workspace association needs a relative-path")

            :else
            (let [result (ingest/workspace-attachment!
                          service
                          {:workspace-root root
                           :relative-path relative
                           :name (:name parsed)
                           :mime (:mime parsed)
                           :snapshot? (true? (:snapshot? parsed))})]
              (if (:ok? result)
                (edn-response 201
                              (assoc result :url (get-in result
                                                         [:attachment :url])))
                (let [{:keys [code message details]} (:error result)]
                  (error-response (status-for code) code message details))))))))))

(defn- route-uri
  "Normalize repeated leading slashes and one trailing slash.

  The application URL helper returns an origin with a trailing slash; callers
  that concatenate another leading slash must not turn `/attachments` into a
  route the static fallback answers with 405."
  [uri]
  (when (string? uri)
    (let [path (str/replace uri #"^/+" "/")]
      (if (and (> (count path) 1) (str/ends-with? path "/"))
        (subs path 0 (dec (count path)))
        path))))

(defn handle
  "Handle attachment HTTP routes:

      POST /attachments           raw byte ingestion
      POST /attachments/extract   explicit EDN extraction request
      POST /attachments/workspace associate a relative workspace file

  Returns a Ring response, or nil when the request is not an attachment
  route."
  [service request]
  (let [uri (route-uri (:uri request))
        method (:request-method request)]
    (cond
      (= "/attachments/extract" uri)
      (if (= :post method)
        (extract-handler service request)
        (error-response 405 :METHOD_NOT_ALLOWED
                        "attachment extraction supports POST only"
                        {:method method}))

      (= "/attachments/workspace" uri)
      (if (= :post method)
        (workspace-handler service request)
        (error-response 405 :METHOD_NOT_ALLOWED
                        "workspace association supports POST only"
                        {:method method}))

      (= "/attachments" uri)
      (if (= :post method)
        (ingest-handler service request)
        (error-response 405 :METHOD_NOT_ALLOWED
                        "attachment ingestion supports POST only"
                        {:method method}))

      :else nil)))
