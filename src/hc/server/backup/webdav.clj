(ns hc.server.backup.webdav
  "A real WebDAV `TargetPort`.

  Objects are stored as `<base-url>/<directory>/<id>.hcb`.  The target uses
  HTTP PUT/GET/DELETE and WebDAV PROPFIND/MKCOL.  It does not fabricate a local
  path: every operation is an HTTP request to the configured endpoint.

  Basic authentication is sent only to the configured origin.  Redirects are
  never followed, so credentials cannot leak to another host."
  (:require [clojure.string :as str]
            [hc.server.backup.codec :as codec]
            [hc.server.backup.error :as err]
            [hc.server.backup.http-common :as http]
            [hc.server.backup.port :as port])
  (:import (java.net URI)
           (java.nio.charset StandardCharsets)
           (java.util.regex Pattern)))

(defrecord WebDavTarget [base-url directory username password client timeout-ms])

(def ^:private id-pattern
  (Pattern/compile "^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$"))

(defn- valid-id?
  [id]
  (and (string? id)
       (not (str/blank? id))
       (boolean (re-matches id-pattern id))))

(defn- assert-id!
  [id]
  (when-not (valid-id? id)
    (err/fail! :BACKUP_ID_INVALID
               "WebDAV object ID is not a safe file name"
               {:id id}))
  id)

(defn target
  "Create a WebDAV target.

  Options:
    :base-url     required, http(s) endpoint
    :directory    target collection below the endpoint (default \"\")
    :username     optional Basic auth user
    :password     optional Basic auth password
    :timeout-ms   request timeout (default 30000)
    :client       optional preconfigured HttpClient"
  [{:keys [base-url directory username password client timeout-ms]
    :or {directory "" timeout-ms http/default-timeout-ms}}]
  (when (str/blank? (str base-url))
    (err/fail! :BACKUP_TARGET_INVALID
               "WebDAV target needs :base-url"
               {}))
  (let [^URI uri (try (URI. (str base-url))
                 (catch Exception e
                   (err/fail! :BACKUP_TARGET_INVALID
                              "WebDAV :base-url is not a valid URI"
                              {:base-url (str base-url)}
                              e)))]
    (when-not (contains? #{"http" "https"} (.getScheme uri))
      (err/fail! :BACKUP_TARGET_INVALID
                 "WebDAV :base-url must use http or https"
                 {:base-url (str base-url)}))
    (->WebDavTarget (str base-url)
                    (str/replace (str directory) #"^/+|/+$" "")
                    username
                    password
                    (or client (http/client timeout-ms))
                    (long timeout-ms))))

(defn- base-uri
  ^URI [target]
  (URI. (:base-url target)))

(defn- path-segments
  [path]
  (remove str/blank? (str/split (str path) #"/")))

(defn- append-path
  [^URI base segments]
  (let [base-path (or (.getRawPath base) "")
        prefix (if (str/ends-with? base-path "/") base-path (str base-path "/"))
        path (str prefix (str/join "/" (map http/encode-segment segments)))]
    (URI. (str (.getScheme base) "://" (.getRawAuthority base) path))))

(defn- directory-uri
  ^URI [target]
  (append-path (base-uri target) (path-segments (:directory target))))

(defn- object-uri
  ^URI [target id]
  (append-path (base-uri target)
               (concat (path-segments (:directory target))
                       [(str (assert-id! id) ".hcb")])))

(defn- auth-headers
  [target]
  (if (some? (:username target))
    {"authorization" (http/basic-auth (:username target) (or (:password target) ""))}
    {}))

(defn- request
  [target method uri headers body]
  (http/request! (:client target) method uri
                 (merge (auth-headers target) headers)
                 body (:timeout-ms target)))

(defn- ensure-collections!
  [target]
  (let [^URI base (base-uri target)
        base-path (or (.getRawPath base) "")
        segments (path-segments (:directory target))]
    (loop [current base-path
           remaining segments]
      (if-let [segment (first remaining)]
        (let [next-path (str (if (str/ends-with? current "/") current (str current "/"))
                             (http/encode-segment segment))
              uri (URI. (str (.getScheme base) "://" (.getRawAuthority base) next-path))
              response (request target :mkcol uri {} nil)]
          (when-not (contains? #{200 201 204 301 302 405} (long (:status response)))
            (err/fail! :BACKUP_TARGET_HTTP
                       "WebDAV could not create the target collection"
                       {:uri (str uri)
                        :status (:status response)
                        :body (String. ^bytes (:body response)
                                       StandardCharsets/UTF_8)}))
          (recur next-path (rest remaining)))
        true))))

(defn- safe-url-fn
  "Local URL redaction for probe results: scheme, host, port and path only."
  [^URI uri]
  (try
    (str (.getScheme uri) "://" (.getRawAuthority uri)
         (or (.getRawPath uri) ""))
    (catch Exception _ nil)))

(defn- propfind-body
  []
  (.getBytes
   (str "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
        "<d:propfind xmlns:d=\"DAV:\"><d:prop>"
        "<d:getcontentlength/><d:getlastmodified/>"
        "</d:prop></d:propfind>")
   StandardCharsets/UTF_8))

(defn probe
  "Check one WebDAV target without writing backup objects.

  Accepts a target config map or an existing `WebDavTarget`.  Creates the
  configured collection chain (MKCOL, tolerant of servers that answer 405)
  and issues a Depth:0 PROPFIND against the directory URI.  Returns
  `{:ok? bool :status int|nil :detail string :url string}`; credentials are
  never included in the detail."
  [target-or-config]
  (try
    (let [target (if (instance? WebDavTarget target-or-config)
                   target-or-config
                   (target target-or-config))
          uri (directory-uri target)
          safe (safe-url-fn uri)]
      (try
        (ensure-collections! target)
        (catch Throwable _ nil))
      (let [response (request target :propfind uri
                              {"depth" "0"
                               "content-type" "application/xml; charset=utf-8"}
                              (propfind-body))
            status (long (:status response))
            excerpt (http/response-excerpt (:body response) 200)]
        (if (<= 200 status 299)
          {:ok? true :status status
           :detail (str "WebDAV directory reachable (HTTP " status ")")
           :url safe}
          {:ok? false :status status
           :detail (str "WebDAV PROPFIND failed (HTTP " status ")"
                        (when excerpt (str ": " excerpt)))
           :url safe})))
    (catch Throwable t
      {:ok? false :status nil
       :detail (or (ex-message t) (str t))
       :url nil})))

(defn- conflict!
  [id]
  (err/fail! :BACKUP_OBJECT_CONFLICT
             "WebDAV target already has different bytes for this ID"
             {:id id}))

(extend-type WebDavTarget
  port/TargetPort
  (put-object! [target object]
    (let [id (assert-id! (:id object))
          ^bytes bytes (:bytes object)
          _ (when-not (bytes? bytes)
              (err/fail! :BACKUP_FORMAT_INVALID
                         "WebDAV object bytes must be a byte array"
                         {:id id}))
          existing (port/get-object target id)]
      (if existing
        (if (java.util.Arrays/equals ^bytes existing bytes)
          (assoc (http/object-metadata id bytes)
                 :deduplicated? true
                 :url (str (object-uri target id)))
          (conflict! id))
        (let [uri (object-uri target id)]
          (ensure-collections! target)
          (let [response (request target :put uri
                                  {"content-type" "application/octet-stream"}
                                  bytes)]
            (if (contains? #{200 201 204} (long (:status response)))
              (assoc (http/object-metadata id bytes)
                     :deduplicated? false
                     :url (str uri))
              (do
                (ensure-collections! target)
                (let [retry (request target :put uri
                                     {"content-type" "application/octet-stream"}
                                     bytes)]
                  (http/ensure-success! retry uri :put)
                  (assoc (http/object-metadata id bytes)
                         :deduplicated? false
                         :url (str uri))))))))))

  (get-object [target id]
    (let [uri (object-uri target id)
          response (request target :get uri {} nil)]
      (case (long (:status response))
        200 (:body response)
        404 nil
        (do (http/ensure-success! response uri :get)
            (:body response)))))

  (delete-object! [target id]
    (let [uri (object-uri target id)
          response (request target :delete uri {} nil)]
      (when-not (contains? #{200 202 204 404} (long (:status response)))
        (http/ensure-success! response uri :delete))
      :deleted))

  (list-objects [target]
    (let [uri (directory-uri target)
          body (.getBytes
                (str "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                     "<d:propfind xmlns:d=\"DAV:\"><d:prop>"
                     "<d:getcontentlength/><d:getlastmodified/>"
                     "</d:prop></d:propfind>")
                StandardCharsets/UTF_8)
          response (request target :propfind uri
                            {"depth" "1"
                             "content-type" "application/xml; charset=utf-8"}
                            body)]
      (when-not (= 207 (long (:status response)))
        (err/fail! :BACKUP_TARGET_HTTP
                   "WebDAV listing failed"
                   {:uri (str uri)
                    :status (:status response)
                    :body (String. ^bytes (:body response)
                                   StandardCharsets/UTF_8)}))
      (let [document (http/parse-xml (:body response))
            hrefs (keep (fn [element]
                          (when (str/ends-with? (str/lower-case (or (http/element-text element "href") ""))
                                                ".hcb")
                            (http/element-text element "href")))
                        (http/elements document "response"))]
        (->> hrefs
             (map (fn [href]
                    (let [^URI resolved (.resolve ^URI uri (URI. href))
                          id (-> (str resolved)
                                 (str/split #"/")
                                 last
                                 (str/replace #"\.hcb$" ""))
                          response (request target :get resolved {} nil)]
                      (if (= 200 (long (:status response)))
                        (try
                          (http/object-metadata id (:body response))
                          (catch clojure.lang.ExceptionInfo e
                            (http/invalid-metadata id (:body response) e)))
                        (http/invalid-metadata id nil nil)))))
             (sort-by (fn [m] (or (:created-at m) "")))
             reverse
             vec))))

  (verify-object [target id expected-sha256]
    (let [bytes (port/get-object target id)]
      (when-not bytes
        (err/fail! :BACKUP_NOT_FOUND
                   "WebDAV object does not exist"
                   {:id id}))
      (let [actual (codec/sha256-hex bytes)]
        (when-not (= expected-sha256 actual)
          (err/fail! :BACKUP_INTEGRITY
                     "WebDAV object digest does not match"
                     {:id id :expected-sha256 expected-sha256
                      :actual-sha256 actual}))
        (http/object-metadata id bytes)))))
