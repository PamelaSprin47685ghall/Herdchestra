(ns hc.client.miniapps.api
  "Read-only workspace HTTP calls used by the mini-app panel.

  These calls use the same GET-only `/api/workspace/*` surface as the file
  browser.  The mini-app host never scans the local file system and never
  writes a workspace file."
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
                               (catch :default _ nil))]
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
                    #js {:method "GET"
                         :cache "no-store"
                         :credentials "same-origin"
                         :headers #js {"accept" "application/edn"}})
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

(defn root!
  [on-result]
  (get! "/api/workspace" {} on-result))

(defn list!
  [path on-result]
  (get! "/api/workspace/list" {:path (str (or path ""))} on-result))

(defn stat!
  [path on-result]
  (get! "/api/workspace/stat" {:path (str (or path ""))} on-result))

(defn read!
  [path on-result]
  (get! "/api/workspace/read" {:path (str (or path ""))} on-result))

(defn generate!
  "Request one generated mini-app.  The server must perform the protected
  workspace write and register the app; the panel never writes a file.

  The request carries a client deadline so a stalled server or proxy cannot
  leave the panel in \"正在生成…\" forever.  A deadline is not a model error:
  it gets its own `:GENERATE_TIMEOUT` code, and the panel keeps the existing
  app list visible.  Returns the fetch promise (or a resolved promise for a
  missing fetch) so tests can await the same path."
  ([prompt filename on-result]
   (generate! prompt filename {} on-result))
  ([prompt filename opts on-result]
   (let [timeout-ms (or (:timeout-ms opts) 310000)
         controller (when (and (pos? (long timeout-ms))
                               (exists? js/AbortController))
                      (js/AbortController.))
         timer (when controller
                 (js/setTimeout
                  (fn []
                    (try (.abort controller) (catch :default _ nil)))
                  timeout-ms))
         finish! (fn [result]
                   (when timer (js/clearTimeout timer))
                   (when on-result (on-result result))
                   result)]
     (if (exists? js/fetch)
       (let [options #js {:method "POST"
                          :cache "no-store"
                          :credentials "same-origin"
                          :headers #js {"content-type" "application/json"
                                        "accept" "application/edn"}
                          :body (js/JSON.stringify
                                 #js {:prompt (str prompt)
                                      :filename (str filename)})}]
         (when controller
           (set! (.-signal options) (.-signal controller)))
         (-> (js/fetch "/api/workspace/miniapp-generate" options)
             (.then read-response!)
             (.then finish!)
             (.catch
              (fn [error]
                (finish!
                 (if (= "AbortError" (.-name error))
                   {:ok? false
                    :error {:code :GENERATE_TIMEOUT
                            :message (str "生成超时（" timeout-ms
                                          " ms）；已有文件仍可查看，请重试或换一个更快的模型")}}
                   {:ok? false
                    :error {:code :NETWORK_ERROR
                            :message (str error)}}))))))
       (js/Promise.resolve
        (finish! {:ok? false
                  :error {:code :FETCH_UNAVAILABLE
                          :message "当前浏览器不提供 fetch"}}))))))
