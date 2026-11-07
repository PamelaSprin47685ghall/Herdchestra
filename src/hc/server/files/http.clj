(ns hc.server.files.http
  "Ring surface for the read-only workspace browser.

  Routes (all EDN, same origin, `cache-control: no-store`):

    GET /api/workspace          root identity
    GET /api/workspace/list     ?path=<relative>
    GET /api/workspace/stat     ?path=<relative>
    GET /api/workspace/read     ?path=<relative>
    GET /api/workspace/search   ?q=<text>&limit=<n>

  The handler returns nil for every path outside `/api/workspace`, so the
  application can compose it with the gateway and static fallback without a
  second router.  It never accepts a write method and never creates a file."
  (:require [clojure.string :as str]
            [hc.server.files :as files])
  (:import (java.net URLDecoder)))

(def ^:private prefix "/api/workspace")

(defn- url-decode
  [value]
  (try
    (URLDecoder/decode (str value) "UTF-8")
    (catch IllegalArgumentException _ nil)))

(defn- parse-query
  [value]
  (if (str/blank? value)
    {}
    (reduce
     (fn [acc pair]
       (if (str/blank? pair)
         acc
         (let [[k v] (str/split pair #"=" 2)
               k (url-decode (or k ""))
               v (url-decode (or v ""))]
           (if (or (nil? k) (nil? v))
             (reduced ::invalid)
             (assoc acc (keyword k) v)))))
     {}
     (str/split value #"&" -1))))

(defn- params-of
  [request]
  (let [parsed (parse-query (:query-string request))]
    (if (= ::invalid parsed)
      ::invalid
      (merge parsed
             (when (map? (:query-params request)) (:query-params request))))))

(defn- edn-response
  ([status body] (edn-response status body nil))
  ([status body extra-headers]
   {:status status
    :headers (merge {"content-type" "application/edn; charset=utf-8"
                     "cache-control" "no-store"
                     "x-content-type-options" "nosniff"
                     "referrer-policy" "no-referrer"}
                    extra-headers)
    :body (pr-str body)}))

(defn- error-status
  [code]
  (case code
    (:INVALID_PATH :ABSOLUTE_PATH :PATH_ESCAPE :NOT_A_DIRECTORY :NOT_A_FILE
     :INVALID_QUERY) 400
    :OUTSIDE_WORKSPACE 403
    :NOT_FOUND 404
    :FILE_CONFLICT 409
    (:FILE_TOO_LARGE :LIMIT_EXCEEDED) 413
    :BINARY_FILE 415
    :UNSUPPORTED_OPERATION 501
    500))

(defn- response-for
  [result]
  (if (:ok? result)
    (edn-response 200 result)
    (edn-response (error-status (get-in result [:error :code])) result)))

(defn- limit-param
  [params]
  (when-let [value (:limit params)]
    (try
      (let [parsed (Long/parseLong (str value))]
        (if (pos? parsed) parsed ::invalid))
      (catch NumberFormatException _
        ::invalid))))

(defn- method-not-allowed
  [request]
  (edn-response 405
                {:ok? false
                 :error {:code :METHOD_NOT_ALLOWED
                         :message "工作区读取 API 只接受 GET 和 HEAD"}}
                {"allow" "GET, HEAD"}))

(defn- not-found
  [request]
  (edn-response 404
                {:ok? false
                 :error {:code :NOT_FOUND
                         :message "没有匹配的工作区读取路由"
                         :uri (:uri request)}}))

(defn- route
  [svc request]
  (let [uri (:uri request)
        method (:request-method request)
        params (params-of request)]
    (cond
      (= ::invalid params)
      (edn-response 400
                    {:ok? false
                     :error {:code :INVALID_QUERY
                             :message "查询字符串不是合法的 URL 编码"}})

      (not (#{:get :head} method))
      (method-not-allowed request)

      :else
      (let [limit (limit-param params)
            result
            (cond
              (contains? #{prefix (str prefix "/")} uri)
              (files/root-info svc)

              (= (str prefix "/list") uri)
              (files/list-entries svc (:path params))

              (= (str prefix "/stat") uri)
              (files/stat-path svc (:path params))

              (= (str prefix "/read") uri)
              (files/read-text svc (:path params))

              (= (str prefix "/search") uri)
              (if (= ::invalid limit)
                {:ok? false
                 :error {:code :INVALID_QUERY
                         :message "limit 必须是正整数"}}
                (files/search svc {:query (:q params)
                                   :mode (some-> (:mode params)
                                                 keyword)
                                   :limit limit}))

              :else
              nil)]
        (if (nil? result)
          (not-found request)
          (let [response (response-for result)]
            (if (= :head method)
              (assoc response :body nil)
              response)))))))

(defn handler
  "Build a Ring handler for the read-only workspace browser.

  Returns nil outside `/api/workspace`, so callers compose with
  `(or (files-handler request) (gateway request))`."
  [svc]
  (fn [request]
    (let [uri (str (:uri request))]
      (when (or (= uri prefix) (str/starts-with? uri (str prefix "/")))
        (route svc request)))))
