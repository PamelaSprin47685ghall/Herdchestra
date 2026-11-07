(ns hc.client.sandbox.ui
  "Shared presentation for sandboxed previews and workspace mini-apps.

  The renderer never performs an external action by itself.  It shows the
  destination and the exact consequence, then calls the supplied callback only
  after the user chooses."
  (:require [clojure.string :as str]
            [com.fulcrologic.fulcro.dom :as dom]
            [hc.client.sandbox.model :as model]))

(def css
  ".hc-sandbox{display:flex;flex-direction:column;gap:10px;border:1px solid var(--wb-line,#d8d8dc);border-radius:12px;padding:10px;background:var(--wb-surface,#fff);min-width:0}
.hc-sandbox-header{display:flex;flex-wrap:wrap;align-items:center;gap:8px;justify-content:space-between}
.hc-sandbox-title{display:flex;flex-direction:column;gap:2px;min-width:0}
.hc-sandbox-title strong{overflow-wrap:anywhere}
.hc-sandbox-meta{display:flex;flex-wrap:wrap;gap:6px;font-size:.8rem;color:var(--wb-ink-muted,#555)}
.hc-sandbox-badge{display:inline-flex;align-items:center;padding:2px 8px;border-radius:999px;border:1px solid var(--wb-line,#d8d8dc);font-size:.76rem}
.hc-sandbox-badge[data-tone=warn]{color:var(--wb-warn,#8a5a00);border-color:var(--wb-warn,#8a5a00)}
.hc-sandbox-badge[data-tone=danger]{color:var(--wb-danger,#a40000);border-color:var(--wb-danger,#a40000)}
.hc-sandbox-badge[data-tone=ok]{color:var(--wb-ok,#1b6b2a);border-color:var(--wb-ok,#1b6b2a)}
.hc-sandbox-toolbar{display:flex;flex-wrap:wrap;gap:6px;align-items:center}
.hc-sandbox-frame-wrap{position:relative;min-height:220px;background:#fff;border-radius:8px;overflow:hidden}
.hc-sandbox-frame{display:block;width:100%;height:min(68vh,520px);min-height:320px;border:0;background:#fff}
.hc-sandbox-loading{position:absolute;inset:0;display:flex;align-items:center;justify-content:center;padding:16px;background:rgba(255,255,255,.92);color:#333;font-size:.9rem;text-align:center}
.hc-sandbox-streaming .hc-sandbox-frame{opacity:.86}
.hc-sandbox-banner{display:flex;flex-wrap:wrap;align-items:center;gap:8px;padding:8px 10px;border-radius:8px;border:1px solid var(--wb-line,#d8d8dc);font-size:.86rem}
.hc-sandbox-banner[data-tone=warn]{border-color:var(--wb-warn,#8a5a00);background:#fff8e6;color:#5c3b00}
.hc-sandbox-banner[data-tone=danger]{border-color:var(--wb-danger,#a40000);background:#fff0f0;color:#6b0000}
.hc-sandbox-banner[data-tone=info]{border-color:var(--wb-accent,#1565c0);background:#eef6ff;color:#0d3c6e}
.hc-sandbox-button{min-height:36px;min-width:36px;padding:6px 12px;border-radius:8px;border:1px solid var(--wb-line,#c8c8cc);background:var(--wb-surface,#fff);color:inherit;font:inherit;cursor:pointer}
.hc-sandbox-button:hover,.hc-sandbox-button:focus-visible{outline:2px solid var(--wb-accent,#1565c0);outline-offset:-2px}
.hc-sandbox-button[disabled]{opacity:.55;cursor:not-allowed}
.hc-sandbox-button-primary{background:var(--wb-accent,#1565c0);border-color:var(--wb-accent,#1565c0);color:#fff}
.hc-sandbox-external{border:1px solid var(--wb-warn,#8a5a00);border-radius:10px;padding:10px;display:flex;flex-direction:column;gap:8px;background:#fffdf5}
.hc-sandbox-external h4{margin:0;font-size:.95rem}
.hc-sandbox-url{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:.8rem;word-break:break-all;background:var(--wb-surface-2,#f3f3f5);border-radius:6px;padding:6px 8px}
.hc-sandbox-fields{max-height:160px;overflow:auto;font-size:.82rem}
.hc-sandbox-source{margin:0;max-height:42vh;overflow:auto;padding:10px;border-radius:8px;background:var(--wb-surface-2,#f3f3f5);font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:.8rem;white-space:pre-wrap;word-break:break-word}
.hc-sandbox-list{display:flex;flex-direction:column;gap:4px;margin:0;padding:0;list-style:none}
.hc-sandbox-row{display:flex;flex-wrap:wrap;gap:8px;align-items:center;justify-content:space-between;padding:6px 8px;border:1px solid var(--wb-line,#e0e0e4);border-radius:8px;font-size:.85rem}
.hc-sandbox-muted{color:var(--wb-ink-muted,#555);font-size:.82rem}
.hc-sandbox-fullscreen{position:fixed;inset:0;z-index:1200;border-radius:0;overflow:auto;padding:12px;background:var(--wb-surface,#fff)}
.hc-sandbox-fullscreen .hc-sandbox-frame{height:calc(100vh - 170px);min-height:0}
.hc-sandbox-sr{position:absolute!important;width:1px;height:1px;padding:0;margin:-1px;overflow:hidden;clip:rect(0,0,0,0);white-space:nowrap;border:0}
@media(max-width:599px){.hc-sandbox-frame{height:56vh;min-height:260px}.hc-sandbox{padding:8px}}")

(defn- tone-class
  [tone]
  (case tone
    :warn "hc-sandbox-banner"
    :danger "hc-sandbox-banner"
    :info "hc-sandbox-banner"
    "hc-sandbox-banner"))

(defn banner
  [{:keys [tone text actions]}]
  (dom/div {:className (tone-class tone)
            :data-tone (name (or tone :info))
            :role (if (= tone :danger) "alert" "status")}
           (dom/span {} (str text))
           (for [action actions]
             (dom/button {:key (:label action)
                          :type "button"
                          :className "hc-sandbox-button"
                          :disabled (:disabled? action)
                          :title (:reason action)
                          :onClick (fn [_] (when-let [f (:on-click action)] (f)))}
                         (:label action)))))

(defn notice
  [tone text]
  (when (seq (str text))
    (banner {:tone tone :text text})))

(defn source-panel
  "Collapsible source view with a copy action."
  [{:keys [source label on-copy]}]
  (dom/details {:className "hc-sandbox-source-panel"}
               (dom/summary {} (or label "查看源内容"))
               (dom/div {:className "hc-sandbox-toolbar"}
                        (dom/button {:type "button"
                                     :className "hc-sandbox-button"
                                     :onClick (fn [_] (when on-copy (on-copy source)))}
                                    "复制源内容"))
               (dom/pre {:className "hc-sandbox-source"} (str source))))

(defn- field-name
  [field]
  (or (:name field) (get field "name") "字段"))

(defn- field-display
  [field]
  (cond
    (:sensitive? field) "敏感值已隐藏"
    (some? (:value-length field)) (str "已填写（" (:value-length field) " 字符）")
    :else "已填写"))

(defn external-panel
  "Show one pending external action.

  `request` is produced by `hc.client.preview.model/external-request` or the
  mini-app equivalent.  `callbacks`:

    :open-url      (fn [url])
    :copy-url      (fn [url])
    :download-data (fn [request])
    :cancel        (fn [])
  "
  [{:keys [request callbacks]}]
  (when request
    (let [{:keys [kind url filename method fields reason supported? data]} request
          callbacks (or callbacks {})
          open! #(when-let [f (:open-url callbacks)] (f url))
          copy! #(when-let [f (:copy-url callbacks)] (f url))
          cancel! #(when-let [f (:cancel callbacks)] (f))
          download! #(when-let [f (:download-data callbacks)] (f request))]
      (dom/div {:className "hc-sandbox-external"
                :role "dialog"
                :aria-label "待确认的外部动作"}
               (case kind
                 :link
                 (dom/div {}
                          (dom/h4 {} "请求打开外部网址")
                          (dom/div {:className "hc-sandbox-url"} (str url))
                          (when-let [origin (model/normalize-origin url)]
                            (dom/div {:className "hc-sandbox-muted"}
                                     (str "目标站点：" origin)))
                          (dom/div {:className "hc-sandbox-toolbar"}
                                   (dom/button {:type "button"
                                                :className "hc-sandbox-button hc-sandbox-button-primary"
                                                :disabled (not supported?)
                                                :title (when-not supported? "只允许在浏览器中打开 http 或 https 地址")
                                                :onClick open!}
                                               "在浏览器打开")
                                   (dom/button {:type "button"
                                                :className "hc-sandbox-button"
                                                :onClick copy!}
                                               "复制地址")
                                   (dom/button {:type "button"
                                                :className "hc-sandbox-button"
                                                :onClick cancel!}
                                               "取消"))
                          (when-not supported?
                            (dom/div {:className "hc-sandbox-muted"}
                                     "此协议不能从预览直接打开；可复制地址后自行判断。")))

                 :download
                 (dom/div {}
                          (dom/h4 {} "请求下载文件")
                          (dom/div {:className "hc-sandbox-muted"}
                                   (str "文件名：" (or filename "未命名")))
                          (when url
                            (dom/div {:className "hc-sandbox-url"} (str url)))
                          (when data
                            (dom/div {:className "hc-sandbox-muted"}
                                     (str "内容大小约 " (count (str data)) " 字符；下载前请确认来源。")))
                          (dom/div {:className "hc-sandbox-toolbar"}
                                   (when (and data (not url))
                                     (dom/button {:type "button"
                                                  :className "hc-sandbox-button hc-sandbox-button-primary"
                                                  :onClick download!}
                                                 "下载此内容"))
                                   (when url
                                     (dom/button {:type "button"
                                                  :className "hc-sandbox-button hc-sandbox-button-primary"
                                                  :disabled (not supported?)
                                                  :onClick open!}
                                                 "在浏览器打开下载地址"))
                                   (when url
                                     (dom/button {:type "button"
                                                  :className "hc-sandbox-button"
                                                  :onClick copy!}
                                                 "复制地址"))
                                   (dom/button {:type "button"
                                                :className "hc-sandbox-button"
                                                :onClick cancel!}
                                               "取消")))

                 :submit
                 (dom/div {}
                          (dom/h4 {} "请求提交外部表单")
                          (dom/div {:className "hc-sandbox-muted"}
                                   (str "方法：" (or method "GET") " · 预览不会自动提交"))
                          (when url
                            (dom/div {:className "hc-sandbox-url"} (str url)))
                          (when (seq fields)
                            (dom/div {:className "hc-sandbox-fields"}
                                     (dom/strong {} "将发送的字段")
                                     (dom/ul {:className "hc-sandbox-list"}
                                             (for [[index field] (map-indexed vector fields)]
                                               (dom/li {:key (str index "-" (field-name field))
                                                        :className "hc-sandbox-row"}
                                                       (dom/span {} (field-name field))
                                                       (dom/span {:className "hc-sandbox-muted"}
                                                                 (field-display field)))))))
                          (dom/div {:className "hc-sandbox-toolbar"}
                                   (when url
                                     (dom/button {:type "button"
                                                  :className "hc-sandbox-button hc-sandbox-button-primary"
                                                  :disabled (not supported?)
                                                  :onClick open!}
                                                 "在浏览器打开页面"))
                                   (when url
                                     (dom/button {:type "button"
                                                  :className "hc-sandbox-button"
                                                  :onClick copy!}
                                                 "复制地址"))
                                   (dom/button {:type "button"
                                                :className "hc-sandbox-button"
                                                :onClick cancel!}
                                               "取消")))

                 :model
                 (dom/div {}
                          (dom/h4 {} "请求调用模型")
                          (dom/div {:className "hc-sandbox-muted"}
                                   (or reason "小应用不能直接调用模型；请在聊天输入区明确提出请求。"))
                          (dom/div {:className "hc-sandbox-toolbar"}
                                   (dom/button {:type "button"
                                                :className "hc-sandbox-button"
                                                :onClick cancel!}
                                               "知道了")))

                 (dom/div {}
                          (dom/h4 {} "待确认的请求")
                          (dom/div {:className "hc-sandbox-muted"}
                                   (or reason "此动作需要用户确认。"))
                          (dom/div {:className "hc-sandbox-toolbar"}
                                   (dom/button {:type "button"
                                                :className "hc-sandbox-button"
                                                :onClick cancel!}
                                               "取消"))))))))
