(ns hc.client.preview.view
  "Sandboxed generated-HTML preview.

  The component is self-contained: it can load inline HTML or an immutable
  blob/URL, renders the sandboxed iframe, keeps a source view, and asks the
  user before every external action.  It never writes workspace files and
  never calls the model."
  (:require ["react" :as react]
            [clojure.string :as str]
            [com.fulcrologic.fulcro.dom :as dom]
            [hc.client.preview.model :as model]
            [hc.client.sandbox.frame :as frame]
            [hc.client.sandbox.model :as sandbox]
            [hc.client.sandbox.ui :as ui]))

(defn- copy-text!
  [text]
  (when (and text (pos? (count (str text))))
    (try
      (if-let [clipboard (.-clipboard js/navigator)]
        (.writeText clipboard (str text))
        (let [node (.createElement js/document "textarea")]
          (set! (.-value node) (str text))
          (set! (.-position (.-style node)) "fixed")
          (set! (.-opacity (.-style node)) "0")
          (.appendChild js/document.body node)
          (.select node)
          (.execCommand js/document "copy")
          (.removeChild js/document.body node)))
      (catch :default _ nil))))

(defn- open-external!
  [url]
  (when (sandbox/http-url? url)
    (try
      (js/window.open url "_blank" "noopener,noreferrer")
      (catch :default _ nil))))

(defn- download-text!
  [filename text mime]
  (try
    (let [blob (js/Blob. #js [(str text)] #js {:type (or mime "text/plain;charset=utf-8")})
          url (.createObjectURL js/URL blob)
          anchor (.createElement js/document "a")]
      (set! (.-href anchor) url)
      (set! (.-download anchor) (or filename "download.txt"))
      (set! (.-rel anchor) "noopener")
      (.appendChild js/document.body anchor)
      (.click anchor)
      (.removeChild js/document.body anchor)
      (js/setTimeout #(.revokeObjectURL js/URL url) 1000))
    (catch :default _ nil)))

(defn- download-request!
  [{:keys [data filename url]}]
  (cond
    (and data (str/starts-with? (str data) "data:"))
    (try
      (let [anchor (.createElement js/document "a")]
        (set! (.-href anchor) data)
        (set! (.-download anchor) (or filename "download"))
        (set! (.-rel anchor) "noopener")
        (.appendChild js/document.body anchor)
        (.click anchor)
        (.removeChild js/document.body anchor))
      (catch :default _ nil))

    data
    (download-text! filename data "application/octet-stream")

    url
    (open-external! url)

    :else nil))

(defn- timeout-promise
  [ms message]
  (js/Promise.
   (fn [_ reject]
     (js/setTimeout #(reject (js/Error. message)) ms))))

(defn- fetch-text!
  [url on-result]
  (if (exists? js/fetch)
    (-> (js/Promise.race
         #js [(js/fetch url
                        #js {:method "GET"
                             :cache "no-store"
                             :credentials "same-origin"
                             :headers #js {"accept" "text/html,text/plain;q=0.9,*/*;q=0.1"}})
              (timeout-promise 15000 "读取预览内容超时")])
        (.then
         (fn [response]
           (let [content-length (js/parseInt (or (.get (.-headers response) "content-length") "") 10)]
             (if (and (number? content-length)
                      (> content-length model/max-html-bytes))
               {:ok? false
                :status (.-status response)
                :error {:code :FILE_TOO_LARGE
                        :message (str "预览内容超过 " model/max-html-bytes " 字节上限")}}
               (-> (.text response)
                   (.then
                    (fn [text]
                      (cond
                        (not (.-ok response))
                        {:ok? false
                         :status (.-status response)
                         :error {:code :HTTP_ERROR
                                 :message (str "读取预览内容失败：HTTP " (.-status response))}}

                        (> (count text) model/max-html-bytes)
                        {:ok? false
                         :error {:code :FILE_TOO_LARGE
                                 :message "预览内容超过 2 MiB 上限"}}

                        :else
                        {:ok? true :text text :status (.-status response)}))))))))
        (.then on-result)
        (.catch (fn [error]
                  (on-result {:ok? false
                              :error {:code :NETWORK_ERROR
                                      :message (str "读取预览内容失败：" error)}}))))
    (on-result {:ok? false
                :error {:code :FETCH_UNAVAILABLE
                        :message "当前浏览器不提供 fetch，无法读取预览内容"}})))

(defn- online?
  []
  (if (exists? js/navigator)
    (not (false? (.-onLine js/navigator)))
    true))

(defn generated-html-preview
  "Render one generated-HTML preview.

  Props:

    :html            inline HTML (wins over :url)
    :url             same-origin URL or blob URL
    :content-id      immutable blob content ID
    :source-label    visible origin label
    :streaming?      true while the source is incomplete
    :error           host-supplied load/generation error
    :on-fix!         optional (fn [{:keys [source error]}]) to start a new turn
  "
  [{:keys [html url content-id source-label streaming? error on-fix!]
    :as props}]
  (let [token (react/useMemo #(str "hc-preview-" (random-uuid)) #js [])
        [state raw-set-state!] (react/useState (model/initial-state))
        set-state! (fn [f & args]
                     (raw-set-state!
                      (if (seq args)
                        (fn [current] (apply f current args))
                        f)))
        [online-state set-online-state!] (react/useState (online?))
        state-atom-ref (react/useRef (atom state))
        state-ref (.-current state-atom-ref)
        _ (reset! state-ref state)
        load-id-ref (react/useRef 0)
        load!
        (fn []
          (let [target (model/fetch-url {:url url :content-id content-id})
                load-id (inc (.-current load-id-ref))]
            (set! (.-current load-id-ref) load-id)
            (when target
              (set-state! #(model/begin-load % target))
              (fetch-text! target
                           (fn [result]
                             (when (= load-id (.-current load-id-ref))
                               (set-state! (fn [current]
                                             (if (:ok? result)
                                               (model/load-success current (:text result))
                                               (model/load-error current
                                                                 (or (:error result)
                                                                     {:code :UNKNOWN
                                                                      :message "预览加载失败"})))))))))))]
    (react/useEffect
     (fn []
       (when (some? html)
         (set-state! #(model/content-arrived % html streaming?)))
       js/undefined)
     #js [html streaming?])
    (react/useEffect
     (fn []
       (when (and (nil? html) (or url content-id))
         (load!))
       js/undefined)
     #js [html url content-id])
    (react/useEffect
     (fn []
       (let [update! (fn [] (set-online-state! (online?)))]
         (when (exists? js/window)
           (.addEventListener js/window "online" update!)
           (.addEventListener js/window "offline" update!))
         (fn []
           (when (exists? js/window)
             (.removeEventListener js/window "online" update!)
             (.removeEventListener js/window "offline" update!)))))
     #js [])
    (react/useEffect
     (fn []
       (let [handler (fn [event]
                       (when (and (exists? js/document)
                                  (= "Escape" (.-key event))
                                  (:fullscreen? @state-ref))
                         (set-state! model/close-fullscreen)))]
         (when (exists? js/document)
           (.addEventListener js/document "keydown" handler))
         (fn []
           (when (exists? js/document)
             (.removeEventListener js/document "keydown" handler)))))
     #js [])
    (let [source (:html state)
          pending-origins (model/pending-origins state)
          notice (:notice state)
          notice (if (map? notice) notice (when notice {:tone :info :text notice}))
          error-view (or error (:error state))
          external (:external state)
          frame-config {:mode "preview"
                        :baseUrl "https://preview.invalid/"
                        :online online-state
                        :storage {}
                        :allowedOrigins (:allowed-origins state)
                        :app {:name (or source-label "生成的 HTML")}
                        :dataVersion 1}
          handle-event
          (fn [{:keys [type payload reply!]}]
            (case type
              :ready (set-state! model/mark-ready)
              :input (set-state! model/mark-input)
              :runtime-error (set-state! #(model/runtime-error % payload))
              :external-link
              (if streaming?
                (set-state! #(model/notice % :warn "正在生成；外部动作暂不可用。"))
                (set-state! #(model/request-external % :link payload)))
              :download
              (if streaming?
                (set-state! #(model/notice % :warn "正在生成；下载暂不可用。"))
                (set-state! #(model/request-external % :download payload)))
              :external-submit
              (if streaming?
                (set-state! #(model/notice % :warn "正在生成；外部提交暂不可用。"))
                (set-state! #(model/request-external % :submit payload)))
              :network-request
              (when-let [origin (sandbox/normalize-origin (:url payload))]
                (set-state! #(model/notice % :warn
                                           (str "预览请求访问 " origin
                                                "；当前未允许联网，请求已被策略阻止。"))))
              (when reply!
                (reply! {:ok? false :error "预览不支持此请求"}))))]
      (dom/div {:className (str "hc-sandbox hc-preview "
                                (when (:fullscreen? state) "hc-sandbox-fullscreen"))
                :data-hc-preview "generated-html"}
               (dom/style {:dangerouslySetInnerHTML #js {:__html ui/css}})
               (dom/div {:className "hc-sandbox-header"}
                        (dom/div {:className "hc-sandbox-title"}
                                 (dom/strong {} (or source-label "生成的 HTML"))
                                 (dom/div {:className "hc-sandbox-meta"}
                                          (dom/span {:className "hc-sandbox-badge"} "生成的交互内容")
                                          (dom/span {:className "hc-sandbox-badge"} "受限预览")
                                          (when source
                                            (dom/span {} (str "约 " (model/source-size-label state))))
                                          (when-not online-state
                                            (dom/span {:className "hc-sandbox-badge" :data-tone "warn"} "离线"))))
                        (dom/div {:className "hc-sandbox-toolbar"}
                                 (when source
                                   (dom/button {:type "button"
                                                :className "hc-sandbox-button"
                                                :onClick (fn [_] (set-state! model/toggle-source))}
                                               (if (:show-source? state) "收起原文" "查看原文")))
                                 (dom/button {:type "button"
                                              :className "hc-sandbox-button"
                                              :onClick (fn [_] (set-state! model/toggle-fullscreen))}
                                             (if (:fullscreen? state) "退出全屏" "全屏"))))
               (when streaming?
                 (ui/banner {:tone :warn
                             :text "正在生成；只读部分可查看，会产生外部后果的操作暂不可用。"}))
               (when error-view
                 (ui/banner {:tone :danger
                             :text (str "预览未完成：" (model/error-summary error-view))
                             :actions (cond-> []
                                        (and (nil? html) (or url content-id))
                                        (conj {:label "重试" :on-click load!})
                                        source
                                        (conj {:label "查看原文"
                                               :on-click #(set-state! assoc :show-source? true)})
                                        (and on-fix! source)
                                        (conj {:label "要求助手修复"
                                               :on-click #(on-fix! {:source source
                                                                    :error error-view})}))}))
               (when-let [runtime (:runtime-error state)]
                 (ui/banner {:tone :danger
                             :text (str "预览脚本报错：" (or (:message runtime) "未知错误"))
                             :actions [{:label "查看原文"
                                        :on-click #(set-state! assoc :show-source? true)}]}))
               (when (seq pending-origins)
                 (ui/banner {:tone :warn
                             :text (str "外部资源默认已阻止："
                                        (str/join "、" (take 4 pending-origins))
                                        (when (> (count pending-origins) 4)
                                          (str " 等 " (count pending-origins) " 个域名")))
                             :actions [{:label "允许这些域名（本次预览）"
                                        :on-click #(set-state! (fn [current]
                                                                 (model/allow-origins current pending-origins)))}]}))
               (when notice
                 (ui/banner {:tone (:tone notice) :text (:text notice)}))
               (when-let [pending (:pending-version state)]
                 (ui/banner {:tone :info
                             :text "预览有新版本。切换会重新加载并可能丢失已填内容。"
                             :actions [{:label "切换到新版本"
                                        :on-click #(set-state! model/accept-pending-version)}
                                       {:label "保留当前"
                                        :on-click #(set-state! model/dismiss-pending-version)}]}))
               (cond
                 source
                 (frame/sandbox-frame-element
                  {:key (str token "-" (:version state))
                   :html source
                   :token token
                   :mode :preview
                   :config frame-config
                   :allowed-origins (:allowed-origins state)
                   :title (str "受限预览：" (or source-label "生成的 HTML"))
                   :streaming? (boolean streaming?)
                   :on-event handle-event})

                 (= :loading (:status state))
                 (dom/div {:className "hc-sandbox-muted" :role "status"}
                          "正在读取预览内容…")

                 :else
                 (dom/div {:className "hc-sandbox-muted"}
                          "尚无预览内容。"))
               (when external
                 (ui/external-panel
                  {:request external
                   :callbacks {:open-url (fn [target]
                                           (open-external! target)
                                           (set-state! model/clear-external))
                               :copy-url (fn [target]
                                           (copy-text! target)
                                           (set-state! #(model/notice % :info "地址已复制。")))
                               :download-data (fn [request]
                                                (download-request! request)
                                                (set-state! model/clear-external))
                               :cancel (fn [_]
                                         (set-state! model/clear-external))}}))
               (when (:show-source? state)
                 (ui/source-panel {:source source
                                   :label "查看生成的 HTML 原文"
                                   :on-copy (fn [text]
                                              (copy-text! text)
                                              (set-state! #(model/notice % :info "源内容已复制。")))}))
               (dom/div {:className "hc-sandbox-muted"}
                        "此预览没有普通浏览器登录态、文件访问或设备权限；链接、下载和外部表单都需要你确认。")))))
