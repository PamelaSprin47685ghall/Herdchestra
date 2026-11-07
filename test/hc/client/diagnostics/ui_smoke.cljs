(ns hc.client.diagnostics.ui-smoke
  "Node smoke runner for the diagnostics workspace.

  This is not a test namespace.  It renders the real React element tree with
  ReactDOMServer and prints FOUND/MISSING checks plus the complete HTML for
  inspection.  The CLJS test namespace exercises the same view with cljs.test."
  (:require ["react-dom/server" :as rds]
            [clojure.string :as str]
            [hc.client.diagnostics.fixture :as fixture]
            [hc.client.diagnostics.model :as model]
            [hc.client.diagnostics.view :as view]))

(defn- render
  [state]
  (rds/renderToStaticMarkup
   (view/diagnostics-element state (fn [_] nil))))

(defn- check
  [label present?]
  (println (if present? "FOUND" "MISSING") label))

(defn -main [& _]
  (let [stats-html (render (fixture/state))
        bundle-state (-> (fixture/state)
                         (model/apply-receipt {:ok? true
                                               :receipt {:status :complete
                                                         :bundle fixture/bundle}})
                         (assoc :tab :bundle))
        bundle-html (render bundle-state)
        logs-html (render (assoc (fixture/state) :tab :logs))]
    (println "STATS-HTML-LENGTH" (count stats-html))
    (doseq [needle ["用户发送次数"
                    "模型请求次数"
                    "工具调用次数"
                    "服务返回"
                    "估算"
                    "未提供"
                    "币种 USD"
                    "币种 EUR"
                    "未估算"
                    "缓存命中"
                    "模型分布"
                    "活跃热力图"
                    "按轮次查看"
                    "时间未知的记录"
                    "刷新统计"
                    "aria-label=\"每日活动\""
                    "@media (prefers-color-scheme: dark)"
                    "@media (max-width: 720px)"
                    "min-height: 44px"
                    ":focus-visible"
                    "overflow-wrap: anywhere"]]
      (check needle (str/includes? stats-html needle)))
    (println "BUNDLE-HTML-LENGTH" (count bundle-html))
    (doseq [needle ["仅本地：没有上传"
                    "自动上传：否"
                    "网络：否"
                    "需要你选择位置：是"
                    "脱敏预览"
                    "占位符"
                    "删去"
                    "隐藏域名"
                    "隐藏路径"
                    "应用昵称替换"
                    "每项默认不包含"
                    "导出诊断文件"
                    "不会替你提交公开 issue"]]
      (check needle (str/includes? bundle-html needle)))
    (println "LOGS-HTML-LENGTH" (count logs-html))
    (doseq [needle ["详细日志默认关闭"
                    "最多保留 7 天"
                    "仅下一次请求"
                    "清空详细日志"
                    "密钥、登录令牌、认证头和密码始终遮蔽"]]
      (check needle (str/includes? logs-html needle)))
    (println "HTML-BEGIN")
    (println stats-html)
    (println "HTML-END")))

(set! *main-cli-fn* -main)
