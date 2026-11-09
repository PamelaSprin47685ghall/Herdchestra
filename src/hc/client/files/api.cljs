(ns hc.client.files.api
  "HTTP reads for the workspace file browser.

  These calls are GET-only.  They return the server's EDN result unchanged;
  the caller stores it through `hc.client.files.model`.  No client-side
  fallback scans the local file system."
  (:require [cljs.reader :as reader]
            [clojure.string :as str]))

(defn- encode
  [value]
  (js/encodeURIComponent (str value)))

(defn- query-string
  [params]
  (->> params
       (keep (fn [[key value]]
               (when (some? value)
                 (str (name key) "=" (encode value)))))
       (str/join "&")))

(defn- read-response!
  [response]
  (let [status (.-status response)]
    (-> (.text response)
        (.then (fn [text]
                 (let [value (try
                               (reader/read-string text)
                               (catch :default _
                                 nil))]
                   (if (map? value)
                     (assoc value :http-status status)
                     {:ok? false
                      :http-status status
                      :error {:code :BAD_RESPONSE
                              :message "服务器返回了无法解析的工作区响应"}})))))))

(defn- get!
  [path params on-result]
  (if (exists? js/fetch)
    (let [query (query-string params)
          url (str path (when-not (str/blank? query) (str "?" query)))]
      (-> (js/fetch url
                    (clj->js {:method "GET"
                              :cache "no-store"
                              :credentials "same-origin"
                              :headers {"accept" "application/edn"}}))
          (.then read-response!)
          (.then (fn [result]
                   (when on-result (on-result result))))
          (.catch (fn [error]
                    (when on-result
                      (on-result {:ok? false
                                  :error {:code :NETWORK_ERROR
                                          :message (str error)}}))))))
    (when on-result
      (on-result {:ok? false
                  :error {:code :FETCH_UNAVAILABLE
                          :message "当前浏览器不提供 fetch"}}))))

(defn list!
  [path on-result]
  (get! "/api/workspace/list" {:path (str (or path ""))} on-result))

(defn read!
  [path on-result]
  (get! "/api/workspace/read" {:path (str (or path ""))} on-result))

(defn- search-mode-param
  [mode]
  (if (= "content" (some-> mode name))
    "content"
    "name"))

(defn search!
  "Search the workspace.  MODE is `:name` or `:content`; the two-arity form
  keeps the name search as the default."
  ([query on-result]
   (search! query :name on-result))
  ([query mode on-result]
   (get! "/api/workspace/search" {:q (str (or query ""))
                                  :mode (search-mode-param mode)}
         on-result)))
