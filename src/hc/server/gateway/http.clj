(ns hc.server.gateway.http
  "Ring surface for the gateway.

  Routes:

    GET|POST /chsk      Sente handshake, long-poll and Ajax POST
    GET|HEAD /blobs/:id independent blob download
    PUT      /blobs     independent blob upload
    GET      /healthz   feed diagnostics

  Any other request is delegated to the caller's `:fallback` handler, so the
  application keeps ownership of static assets and the root UI.  The gateway
  does not mount a catch-all page."
  (:require [clojure.string :as str]
            [hc.server.gateway.auth :as auth]
            [hc.server.gateway.blob :as blob]
            [hc.server.gateway.feed :as feed]
            [hc.server.gateway.ports :as ports]
            [hc.server.gateway.sync-object :as sync-object])
  (:import [java.io ByteArrayInputStream InputStream]
           [java.net URLDecoder]
           [java.nio.charset StandardCharsets]))

(def default-max-sente-body (* 1024 1024))

(def ^:private sente-param-names
  "The only Ring params Sente reads as keywords.  Every other name stays a
  string so an arbitrary query or form field cannot intern unbounded
  keywords."
  #{"client-id" "csrf-token" "handshake?" "ppstr" "binary?" "udt"})

(def ^:private max-unknown-params
  "Upper bound on non-Sente params retained from one request."
  64)

(defn- url-decode
  [value]
  (try
    (URLDecoder/decode value "UTF-8")
    (catch IllegalArgumentException _
      value)))

(defn- parse-pairs
  [value]
  (if (str/blank? value)
    {}
    (loop [pairs (str/split value #"&" -1)
           params {}
           unknown 0]
      (if (empty? pairs)
        params
        (let [pair (first pairs)]
          (if (str/blank? pair)
            (recur (rest pairs) params unknown)
            (let [[k v] (str/split pair #"=" 2)
                  k (url-decode (or k ""))
                  v (url-decode (or v ""))]
              (cond
                (contains? sente-param-names k)
                (recur (rest pairs) (assoc params (keyword k) v) unknown)

                (< unknown max-unknown-params)
                (recur (rest pairs) (assoc params k v) (inc unknown))

                :else
                (recur (rest pairs) params unknown)))))))))

(defn- form-content-type?
  [request]
  (let [content-type (some-> (get-in request [:headers "content-type"])
                             str/lower-case)]
    (and content-type
         (str/starts-with? content-type
                           "application/x-www-form-urlencoded"))))

(defn- read-limited
  [^InputStream in limit]
  (let [buffer (byte-array 8192)
        out (java.io.ByteArrayOutputStream.)]
    (loop [total 0]
      (let [n (.read in buffer)]
        (if (neg? n)
          (.toByteArray out)
          (let [total' (+ total n)]
            (when (> total' limit)
              (throw (ex-info "Sente message exceeds the 1 MiB boundary"
                              {:code :SENTE_BODY_TOO_LARGE
                               :limit limit})))
            (.write out buffer 0 n)
            (recur total')))))))

(defn- add-params
  "Parse query and form parameters and install Ring-compatible :params.

  Sente requires keyword params.  The body is replaced by a fresh stream so
  Sente can still inspect it for binary posts.  Every POST/PUT body is read
  through the 1 MiB limiter, not only form-encoded bodies: a binary Sente
  post is read wholesale by the transport, so leaving it unbounded would
  bypass the Sente body boundary."
  [request opts]
  (let [query (parse-pairs (:query-string request))
        body-result
        (if (and (contains? #{:post :put} (:request-method request))
                 (instance? InputStream (:body request)))
          (try
            (let [bytes (read-limited (:body request)
                                      (long (or (:max-sente-body opts)
                                                default-max-sente-body)))]
              {:ok? true
               :params (if (form-content-type? request)
                         (parse-pairs (String. ^bytes bytes
                                               StandardCharsets/UTF_8))
                         {})
               :body (ByteArrayInputStream. bytes)})
            (catch clojure.lang.ExceptionInfo t
              {:ok? false :error t}))
          {:ok? true :params {} :body (:body request)})]
    (if-not (:ok? body-result)
      {:ok? false
       :response {:status 413
                  :headers {"content-type" "application/edn; charset=utf-8"
                            "cache-control" "no-store"}
                  :body (pr-str {:error :SENTE_BODY_TOO_LARGE
                                 :message "Sente message exceeds the 1 MiB boundary"})}}
      (let [request (assoc request
                           :body (:body body-result)
                           :query-params query
                           :form-params (:params body-result)
                           :params (merge query (:params body-result)))]
        {:ok? true :request request}))))

(defn- health
  [gw]
  {:status 200
   :headers {"content-type" "application/edn; charset=utf-8"
             "cache-control" "no-store"}
   :body (pr-str (assoc (feed/stats (:feed gw)) :ok true))})

(defn- sync-object-token
  [uri]
  (when (and (string? uri) (str/starts-with? uri "/sync-objects/"))
    (let [token (subs uri (count "/sync-objects/"))]
      (when (and (seq token) (not (str/includes? token "/")))
        token))))

(defn- sync-object-headers
  [entry]
  {"content-type" "application/edn; charset=utf-8"
   "cache-control" "no-store, private"
   "x-content-type-options" "nosniff"
   "etag" (str "\"" (:digest entry) "\"")
   "content-length" (str (:size entry))})

(defn- edn-response
  "Return one EDN error response for the sync-object route."
  ([status body] (edn-response status body {}))
  ([status body headers]
   {:status status
    :headers (merge {"content-type" "application/edn; charset=utf-8"
                     "cache-control" "no-store"
                     "x-content-type-options" "nosniff"}
                    headers)
    :body (pr-str body)}))

(defn- sync-object-error
  "Convert a resolver error into the gateway's HTTP error envelope.

  `resolve-object` returns `{:code ... :message ...}`; every other gateway
  route returns `{:error <code> ...}`.  Keeping one envelope lets a client
  read the same field for every failure without guessing which route answered."
  [error]
  (let [{:keys [code message] :as details} error
        details (dissoc details :code :message)]
    (cond-> {:error code :message message}
      (seq details) (assoc :details details))))

(declare not-found method-not-allowed)

(defn- sync-object-handler
  "Serve one registered large state object.

  The token is a capability delivered only over the authenticated Sente
  channel.  Responses are never cacheable, never support ranges, and are
  rejected once the feed has moved to a newer epoch."
  [gw request]
  (let [method (:request-method request)
        token (sync-object-token (:uri request))
        current-epoch (:epoch (feed/snapshot (:feed gw)))]
    (cond
      (not (contains? #{:get :head} method))
      (method-not-allowed request "GET, HEAD")

      (nil? token)
      (not-found request)

      :else
      (let [resolved (sync-object/resolve-object (:objects gw)
                                                 current-epoch
                                                 token)]
        (if-not (:ok? resolved)
          (edn-response (:status resolved)
                        (sync-object-error (:error resolved))
                        {"cache-control" "no-store, private"})
          (let [{:keys [blob-id size] :as entry} (:entry resolved)
                store (:store gw)]
            (cond
              (nil? store)
              (edn-response 503
                            {:error :SYNC_OBJECT_STORE_UNAVAILABLE
                             :message "no blob store is attached to this gateway"}
                            {"cache-control" "no-store, private"})

              :else
              (let [metadata (ports/blob-metadata store blob-id)]
                (cond
                  (nil? metadata)
                  (do
                    (sync-object/forget! (:objects gw) token)
                    (edn-response
                     410
                     {:error :SYNC_OBJECT_UNAVAILABLE
                      :message "state object bytes are no longer available"
                      :action :resync-state}
                     {"cache-control" "no-store, private"}))

                  (not= (long (:size metadata)) (long size))
                  (do
                    (sync-object/forget! (:objects gw) token)
                    (edn-response
                     500
                     {:error :SYNC_OBJECT_INTEGRITY
                      :message "state object size does not match its registration"
                      :expected size
                      :actual (:size metadata)}
                     {"cache-control" "no-store, private"}))

                  (= :head method)
                  {:status 200
                   :headers (sync-object-headers entry)
                   :body nil}

                  :else
                  (if-let [stream (ports/open-blob store blob-id)]
                    {:status 200
                     :headers (sync-object-headers entry)
                     :body stream}
                    (do
                      (sync-object/forget! (:objects gw) token)
                      (edn-response
                       410
                       {:error :SYNC_OBJECT_UNAVAILABLE
                        :message "state object disappeared before it could be opened"
                        :action :resync-state}
                       {"cache-control" "no-store, private"}))))))))))))

(defn- not-found
  [request]
  {:status 404
   :headers {"content-type" "application/edn; charset=utf-8"
             "cache-control" "no-store"}
   :body (pr-str {:error :NOT_FOUND
                  :message "no gateway route matches this request"
                  :uri (:uri request)})})

(defn- method-not-allowed
  [request allow]
  {:status 405
   :headers {"content-type" "application/edn; charset=utf-8"
             "cache-control" "no-store"
             "allow" allow}
   :body (pr-str {:error :METHOD_NOT_ALLOWED
                  :message "request method is not supported for this route"
                  :method (:request-method request)})})

(defn- chsk-handler
  [gw request]
  (let [method (:request-method request)
        socket (:socket gw)]
    (cond
      (nil? socket)
      {:status 503
       :headers {"content-type" "application/edn; charset=utf-8"
                 "cache-control" "no-store"}
       :body (pr-str {:error :GATEWAY_STOPPED
                      :message "the realtime channel is not running"})}

      (= :get method)
      ((:ajax-get-or-ws-handshake-fn socket) request)

      (= :post method)
      ((:ajax-post-fn socket) request)

      :else
      (method-not-allowed request "GET, POST"))))

(defn ring-handler
  "Build the Ring handler for GW.

  GW is the map returned by `hc.server.gateway/start!`; `:opts` may carry
  `:fallback`, `:max-upload-bytes`, `:max-sente-body`, `:content-type-fn`
  and the `auth/authorize` policy keys (`:allowed-origins`,
  `:trusted-hosts`).  `:allow-remote?` is only a startup declaration; remote
  peers require an authorization hook."
  [gw]
  (fn [request]
    (let [opts (:opts gw)
          uri (:uri request)
          method (:request-method request)]
      (cond
        (true? @(:stopped? gw))
        {:status 503
         :headers {"content-type" "application/edn; charset=utf-8"
                   "cache-control" "no-store"}
         :body (pr-str {:error :GATEWAY_STOPPED
                        :message "the gateway is stopping"})}

        (= "/chsk" uri)
        (if-not (auth/authorize opts request)
          (auth/forbidden request)
          (let [parsed (add-params request opts)]
            (if (:ok? parsed)
              (chsk-handler gw (:request parsed))
              (:response parsed))))

        (or (= "/blobs" uri) (str/starts-with? uri "/blobs/"))
        (if-not (auth/authorize opts request)
          (auth/forbidden request)
          (or (blob/handle (:store gw)
                           opts
                           (update request :params
                                   #(or % (parse-pairs (:query-string request)))))
              (not-found request)))

        (or (= "/sync-objects" uri)
            (str/starts-with? uri "/sync-objects/"))
        (if-not (auth/authorize opts request)
          (auth/forbidden request)
          (sync-object-handler gw request))

        (= "/healthz" uri)
        (cond
          (not (auth/authorize opts request)) (auth/forbidden request)
          (= :get method) (health gw)
          :else (method-not-allowed request "GET"))

        :else
        (if-let [fallback (:fallback opts)]
          (fallback request)
          (not-found request))))))
