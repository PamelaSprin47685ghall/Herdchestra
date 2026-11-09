(ns hc.client.miniapps.view
  "Workspace mini-app panel.

  The panel is self-contained: it owns its IndexedDB-backed app registry, its
  workspace HTTP reads, and the sandboxed runtime.  It does not write Fulcro
  state and does not need a workbench query change.

  Security boundaries:

  - every app runs in `allow-scripts` only, with no same-origin, forms,
    popups, downloads, modals or top navigation;
  - network origins are blocked until the user allows them for this app;
  - workspace files are readable only after the user selects a file or
    directory and chooses once/session/persistent access;
  - external links, downloads, form submissions and model calls are visible
    requests, never silent actions.
  "
  (:require ["react" :as react]
            [clojure.string :as str]
            [com.fulcrologic.fulcro.dom :as dom]
            [hc.client.miniapps.api :as api]
            [hc.client.miniapps.model :as model]
            [hc.client.miniapps.store :as store]
            [hc.client.sandbox.frame :as frame]
            [hc.client.sandbox.model :as sandbox]
            [hc.client.sandbox.ui :as ui]))

;; ---------------------------------------------------------------------------
;; Small helpers

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

(defn- open-external!
  [url]
  (when (sandbox/http-url? url)
    (try
      (js/window.open url "_blank" "noopener,noreferrer")
      (catch :default _ nil))))

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

(defn- error-text
  [result]
  (let [error (:error result)
        code (or (:code error) (:code result))
        message (or (:message error) (:message result))]
    (str (when code (str (name code) " · "))
         (or message "工作区读取失败"))))

(def settings-route-hash
  "The workbench reads this exact hash form back into `:ui/route`.
  `hc.client.workbench.routing` writes `#/settings` for the same page."
  "#/settings")

(defn- open-settings!
  "Navigate to the settings page through the workbench's single hash router.

  The panel receives no navigation props, so it writes the same hash the
  sidebar and the connection badge use.  The workbench's `hashchange`
  listener turns it into `:ui/route :settings`."
  []
  (when (and (exists? js/window)
             (not= settings-route-hash (.-hash js/window.location)))
    (set! (.-hash js/window.location) settings-route-hash)))

(defn- response-code
  [result]
  (or (get-in result [:error :code]) (:code result)))

(defn- missing-resource?
  "A 404 means the resource is gone, not that the server is unhealthy."
  [result]
  (or (= 404 (:http-status result))
      (= :NOT_FOUND (response-code result))))

(defn- server-unavailable?
  "Any 5xx means the request never reached a usable workspace service."
  [result]
  (let [status (:http-status result)]
    (or (and (number? status) (<= 500 status 599))
        (= :SERVICE_UNAVAILABLE (response-code result)))))

(defn request-error-text
  "User-facing Chinese text for one failed workspace response.

  Missing resources and server outages get stable Chinese placeholders; the
  panel never prints a response code or an English error token as the whole
  message."
  [result]
  (cond
    (server-unavailable? result)
    "服务器暂不可用；小应用与本地数据没有改变。请稍后重试，或打开设置检查连接。"

    (missing-resource? result)
    "找不到这个工作区资源；它可能已被移动或删除。请重新选择路径，或刷新后重试。"

    :else
    (error-text result)))

(defn clear-disabled?
  "True when the selected app has no local records to clear.

  The clear control stays disabled and a clear request leaves the
  confirmation dialog closed; an empty store never produces a fake success."
  [state]
  (zero? (long (or (get-in state [:data :summary :keys]) 0))))

(def clear-disabled-reason
  "The disabled clear control names the missing precondition."
  "没有可清空的本地记录")

(defn- focus-relink!
  []
  (when (exists? js/document)
    (when-let [node (.getElementById js/document "hc-miniapp-relink-path")]
      (.focus node))))

(defn- time-label
  [value]
  (let [value (str (or value ""))]
    (if (>= (count value) 19)
      (subs value 0 19)
      (if (str/blank? value) "—" value))))

(defn- status-badge
  [status]
  (dom/span {:className "hc-sandbox-badge"
             :data-tone (name (model/status-tone status))}
            (model/app-status-label status)))

(defn- data-state
  [entries]
  (let [entries (vec (model/entries-seq entries))
        snapshot (model/storage-snapshot entries)
        summary (model/data-summary entries)]
    {:entries (into {} (map (juxt :key identity) entries))
     :summary summary
     :snapshot snapshot}))

(defn- assoc-data-entry
  [state key value]
  (let [entry {:key (str key)
               :value (str value)
               :updated-at (model/now-string)}
        entries (assoc (get-in state [:data :entries] {}) (:key entry) entry)
        summary (model/data-summary entries)
        snapshot (model/storage-snapshot entries)
        app (assoc (:app state) :data-bytes (:bytes summary))]
    (-> state
        (assoc-in [:data :entries] entries)
        (assoc-in [:data :summary] summary)
        (assoc-in [:data :snapshot] snapshot)
        (assoc :app app))))

(defn- remove-data-entry
  [state key]
  (let [entries (dissoc (get-in state [:data :entries] {}) (str key))
        summary (model/data-summary entries)
        snapshot (model/storage-snapshot entries)
        app (assoc (:app state) :data-bytes (:bytes summary))]
    (-> state
        (assoc-in [:data :entries] entries)
        (assoc-in [:data :summary] summary)
        (assoc-in [:data :snapshot] snapshot)
        (assoc :app app))))

(defn- upsert-app
  [apps app]
  (let [apps (vec (or apps []))]
    (if (some #(= (:app-id %) (:app-id app)) apps)
      (mapv #(if (= (:app-id %) (:app-id app)) app %) apps)
      (conj apps app))))

(defn- with-timeout
  "Reject a stalled IndexedDB or HTTP promise instead of leaving the panel in
  an infinite loading state."
  [promise ms message]
  (js/Promise.race
   #js [promise
        (js/Promise.
         (fn [_ reject]
           (js/setTimeout #(reject (js/Error. message)) ms)))]))

;; ---------------------------------------------------------------------------
;; App loading

(defn- apply-ready-state!
  [set-state! app text result info versions entries]
  (let [{:keys [entries summary snapshot]} (data-state entries)
        app (assoc app :data-bytes (:bytes summary))
        changed? (:changed? info)]
    (store/put-app! app)
    (set-state! assoc
                :app app
                :selected-id (:app-id app)
                :source {:text text
                         :stamp (:stamp result)
                         :size (:size result)
                         :digest (:digest info)
                         :declared-data-version (:declared-version info)
                         :version (long (or (:source-version app) 1))}
                :status :ready
                :ready? false
                :runtime-error nil
                :compat (when changed?
                          {:kind (:compatibility info)
                           :declared (:declared-version info)
                           :previous (:previous-version info)})
                :data {:entries entries
                       :summary summary
                       :snapshot snapshot}
                :versions (vec (sort-by :version versions))
                :network (model/network-state app text)
                :using-old-version nil
                :restored-version? false
                :data-too-large? (boolean (:too-large? snapshot))
                :force-open? false
                :error nil
                :notice (cond
                          (:too-large? snapshot)
                          (str "数据约 " (model/format-bytes (:bytes snapshot))
                               "，超过同步 localStorage 安全上限；未自动打开，避免以空列表误导。可导出、清空，或明确选择不同步打开。")
                          (:large? snapshot)
                          (str "数据约 " (model/format-bytes (:bytes snapshot))
                               "，已完整注入同步 localStorage。")))
    (set-state! update :apps upsert-app app)))

(defn- finish-open!
  [set-state! app text result info versions]
  (-> (store/kv-all (:app-id app))
      (.then (fn [entries]
               (apply-ready-state! set-state! app text result
                                   info versions entries)))))

(defn- apply-restored-state!
  [set-state! app version versions entries]
  (let [{:keys [entries summary snapshot]} (data-state entries)
        app (assoc app :data-bytes (:bytes summary))
        text (str (:text version))
        data-version (long (or (:data-version app) 1))
        source-version (long (or (:data-version version) 1))
        compat (cond
                 (> source-version data-version)
                 {:kind :unknown :declared source-version :previous data-version}
                 (< source-version data-version)
                 {:kind :downgrade :declared source-version :previous data-version}
                 :else nil)]
    (store/put-app! app)
    (set-state! assoc
                :app app
                :selected-id (:app-id app)
                :source {:text text
                         :version (:version version)
                         :digest (:digest version)
                         :declared-data-version (:data-version version)}
                :status :ready
                :ready? false
                :runtime-error nil
                :compat compat
                :data {:entries entries
                       :summary summary
                       :snapshot snapshot}
                :versions (vec (sort-by :version versions))
                :network (model/network-state app text)
                :using-old-version version
                :restored-version? true
                :data-too-large? (boolean (:too-large? snapshot))
                :force-open? false
                :error nil
                :notice (str "已恢复旧版 v" (:version version)
                             "（本地）；工作区文件未改写，数据保留。"
                             (when (:too-large? snapshot)
                               " 数据超过同步 localStorage 安全上限；未自动打开。")))
    (set-state! update :apps upsert-app app)))

(defn- open-source!
  [set-state! app result]
  (let [text (str (:text result))
        info (model/upgrade-info app text)
        changed? (:changed? info)]
    (-> (store/versions-list (:app-id app))
        (.then
         (fn [versions]
           (let [versions (vec (or versions []))
                 current-version (long (or (:source-version app) 1))
                 new-version (if changed?
                               (inc (max current-version
                                         (reduce max 0 (map :version versions))))
                               current-version)
                 version-record {:app-id (:app-id app)
                                 :version new-version
                                 :text text
                                 :digest (:digest info)
                                 :data-version (:declared-version info)
                                 :saved-at (model/now-string)}
                 versions (if changed?
                            (conj versions version-record)
                            versions)
                 app (cond-> (assoc app
                                    :status :available
                                    :path (:path result)
                                    :name (or (:name app)
                                              (model/app-name-from-path (:path result)))
                                    :last-used-at (model/now-string)
                                    :source-digest (:digest info)
                                    :source-data-version (:declared-version info)
                                    :source-version new-version)
                       changed? (assoc :last-updated-at (model/now-string)))]
             (when changed?
               (store/version-put! version-record)
               (store/versions-prune! (:app-id app) store/max-versions))
             (-> (store/put-app! app)
                 (.then (fn [_]
                          (finish-open! set-state! app text result info versions)))))))
        (.catch (fn [error]
                  (set-state! assoc :status :error
                              :error (str "读取小应用版本失败：" error)))))))

(defn- apply-unlinked-state!
  [set-state! app result versions entries]
  (let [{:keys [entries summary snapshot]} (data-state entries)]
    (set-state! assoc
                :app app
                :selected-id (:app-id app)
                :source nil
                :status :unlinked
                :ready? false
                :runtime-error nil
                :compat nil
                :data {:entries entries
                       :summary summary
                       :snapshot snapshot}
                :versions (vec (sort-by :version versions))
                :network (model/network-state app "")
                :using-old-version nil
                :restored-version? false
                :data-too-large? (boolean (:too-large? snapshot))
                :force-open? false
                :error nil
                :notice (str "小应用文件当前不可找到"
                             (when-let [message (get-in result [:error :message])]
                               (str "：" message))
                             "。数据仍保留；可重新关联路径、导出或清空。"))
    (set-state! update :apps upsert-app app)))

(defn- unlinked!
  [set-state! app result]
  (let [app (assoc app
                   :status :unlinked
                   :last-checked-at (model/now-string))]
    (store/put-app! app)
    (-> (store/versions-list (:app-id app))
        (.then
         (fn [versions]
           (-> (store/kv-all (:app-id app))
               (.then (fn [entries]
                        (apply-unlinked-state! set-state! app result versions entries))))))
        (.catch (fn [error]
                  (set-state! assoc :status :error
                              :error (str "读取小应用数据失败：" error)))))))

(defn- load-current!
  [set-state! app]
  (api/read! (:path app)
             (fn [result]
               (if (:ok? result)
                 (open-source! set-state! app result)
                 (if (missing-resource? result)
                   (unlinked! set-state! app result)
                   (set-state! assoc
                               :app app
                               :selected-id (:app-id app)
                               :status :error
                               :error (request-error-text result)
                               :notice nil))))))

(defn- open-restored!
  [set-state! app version]
  (-> (store/versions-list (:app-id app))
      (.then
       (fn [versions]
         (-> (store/kv-all (:app-id app))
             (.then (fn [entries]
                      (apply-restored-state! set-state! app version versions entries))))))
      (.catch (fn [error]
                (set-state! assoc :status :error
                            :error (str "读取恢复版本失败：" error))))))

(defn- load-app!
  [set-state! app-id]
  (set-state! assoc
              :status :loading
              :selected-id app-id
              :error nil
              :notice nil
              :using-old-version nil
              :restored-version? false
              :data-too-large? false
              :force-open? false
              :compat nil)
  (-> (with-timeout (store/get-app app-id)
                    6000
                    "读取小应用记录超时；请检查浏览器是否允许 IndexedDB。")
      (.then
       (fn [app]
         (if app
           (if-let [restored (:restored-version app)]
             (-> (store/version-get (:app-id app) restored)
                 (.then
                  (fn [version]
                    (if version
                      (open-restored! set-state! app version)
                      (let [app (dissoc app :restored-version :restored-at)]
                        (store/put-app! app)
                        (set-state! assoc :app app :restored-version? false)
                        (load-current! set-state! app)))))
                 (.catch (fn [error]
                           (set-state! assoc :status :error
                                       :error (str "读取恢复版本失败：" error)))))
             (load-current! set-state! app))
           (set-state! assoc :status :error :error "小应用记录不存在"))))
      (.catch (fn [error]
                (set-state! assoc :status :error
                            :error (str "读取小应用失败：" error))))))

(defn- create-app!
  [set-state! workspace-root result]
  (let [text (str (:text result))
        app (model/new-app workspace-root (:path result) text)
        version {:app-id (:app-id app)
                 :version 1
                 :text text
                 :digest (:source-digest app)
                 :data-version (:data-version app)
                 :saved-at (model/now-string)}]
    (-> (store/put-app! app)
        (.then (fn [_] (store/version-put! version)))
        (.then (fn [_]
                 (set-state! update :apps upsert-app app)
                 (load-app! set-state! (:app-id app))))
        (.catch (fn [error]
                  (set-state! assoc :add-error (str "创建小应用失败：" error)))))))

(defn- add-app!
  [state-ref set-state! path]
  (let [path (model/normalize-path path)
        workspace-root (get-in @state-ref [:workspace :root])]
    (cond
      (not (model/safe-workspace-path? path))
      (set-state! assoc :add-error "请输入工作区内的相对路径（不能以 / 开头，也不能包含 ..）")

      (not (model/html-path? path))
      (set-state! assoc :add-error "小应用文件需要 .html、.htm 或 .xhtml 后缀")

      (nil? workspace-root)
      (set-state! assoc :add-error "工作区身份尚未读取；请稍后重试")

      :else
      (api/read! path
                 (fn [result]
                   (if (:ok? result)
                     (let [text (str (:text result))
                           digest (model/content-digest text)
                           apps (:apps @state-ref)
                           exact (some #(when (and (= (:workspace-root %) workspace-root)
                                                   (= (model/normalize-path (:path %)) path))
                                          %)
                                       apps)
                           unlinked (filter #(and (= :unlinked (:status %))
                                                  (= workspace-root (:workspace-root %))
                                                  (= (:source-digest %) digest))
                                            apps)]
                       (cond
                         exact (load-app! set-state! (:app-id exact))
                         (= 1 (count unlinked))
                         (set-state! assoc
                                     :relink {:candidate (first unlinked)
                                              :result result}
                                     :add-error nil)
                         :else (create-app! set-state! workspace-root result)))
                     (set-state! assoc :add-error
                                 (request-error-text result))))))))

(defn- relink-confirm!
  [state-ref set-state!]
  (let [{:keys [candidate result]} (:relink @state-ref)]
    (when (and candidate result)
      (set-state! assoc :relink nil)
      (open-source! set-state! (assoc candidate
                                      :workspace-root (get-in @state-ref [:workspace :root])
                                      :path (:path result)
                                      :status :available)
                    result))))

(defn- check-file!
  [state-ref set-state! app]
  (api/stat!
   (:path app)
   (fn [result]
     (let [status (if (:ok? result)
                    :available
                    (if (= :NOT_FOUND (get-in result [:error :code]))
                      :unlinked
                      :error))
           checked (assoc app
                          :status status
                          :last-checked-at (model/now-string))]
       (store/put-app! checked)
       (set-state!
        (fn [state]
          (cond-> state
            (= (:app-id (:app state)) (:app-id checked))
            (assoc :app checked)
            true
            (update :apps
                    (fn [apps]
                      (mapv #(if (= (:app-id %) (:app-id checked))
                               checked
                               %)
                            apps))))))))))

;; ---------------------------------------------------------------------------
;; Data actions

(defn- clear-data!
  [state-ref set-state!]
  (let [app (:app @state-ref)]
    (when (and app
               (not (clear-disabled? @state-ref)))
      (-> (store/kv-clear! (:app-id app))
          (.then
           (fn [_]
             (let [app (assoc app :data-bytes 0)]
               (store/put-app! app)
               (set-state! assoc
                           :app app
                           :data {:entries {}
                                  :summary (model/data-summary [])
                                  :snapshot {:data {} :omitted? false}}
                           :data-too-large? false
                           :force-open? false
                           :frame-epoch (inc (long (or (:frame-epoch @state-ref) 0)))
                           :confirm-clear? false
                           :notice "已清空此小应用的数据；其他小应用与普通浏览器数据未受影响。")
               (set-state! update :apps upsert-app app))))
          (.catch (fn [error]
                    (set-state! assoc :notice (str "清空失败：" error))))))))

(defn- export-data!
  [state-ref]
  (let [app (:app @state-ref)
        entries (get-in @state-ref [:data :entries])
        doc (model/export-document app entries)]
    (download-text! (model/export-filename app)
                    (sandbox/json doc)
                    "application/json;charset=utf-8")))

(defn- export-version!
  [app version]
  (download-text! (str (-> (:name app)
                           (str/replace #"(?i)\.(?:html?|xhtml)$" "")
                           (str/replace #"[^A-Za-z0-9._-]+" "_"))
                       "-v" (:version version) ".html")
                  (:text version)
                  "text/html;charset=utf-8"))

(defn- restore-version!
  [state-ref set-state! version]
  (let [app (:app @state-ref)]
    (set-state! assoc
                :using-old-version version
                :restored-version? (= (:version version) (:restored-version app))
                :ready? false
                :network (model/network-state app (:text version) (:requested-origins @state-ref))
                :notice (str "正在查看旧版 v" (:version version)
                             "；当前工作区文件未改变。"))))

(defn- restore-version-local!
  [state-ref set-state! version]
  (let [app (:app @state-ref)
        app (assoc app
                   :restored-version (:version version)
                   :restored-at (model/now-string))]
    (-> (store/put-app! app)
        (.then (fn [_]
                 (set-state! assoc :app app :force-open? false)
                 (open-restored! set-state! app version)))
        (.catch (fn [error]
                  (set-state! assoc :notice (str "恢复旧版失败：" error)))))))

(defn- back-to-current!
  [set-state! app]
  (let [app (dissoc app :restored-version :restored-at)]
    (store/put-app! app)
    (set-state! assoc
                :app app
                :using-old-version nil
                :restored-version? false
                :notice nil)
    (load-app! set-state! (:app-id app))))

;; ---------------------------------------------------------------------------
;; Network permissions

(defn- allow-origins!
  [state-ref set-state! origins]
  (let [app (:app @state-ref)
        text (or (get-in @state-ref [:using-old-version :text])
                 (get-in @state-ref [:source :text])
                 "")]
    (when app
      (let [app (model/allow-origins app origins)]
        (-> (store/put-app! app)
            (.then (fn [_]
                     (set-state! assoc
                                 :app app
                                 :requested-origins []
                                 :network (model/network-state app text)
                                 :notice "已允许所选域名；小应用会重新加载，未保存的页面内状态可能丢失。"))))))))

(defn- revoke-origin!
  [state-ref set-state! origin]
  (let [app (:app @state-ref)
        text (or (get-in @state-ref [:using-old-version :text])
                 (get-in @state-ref [:source :text])
                 "")]
    (when app
      (let [app (model/revoke-origin app origin)]
        (-> (store/put-app! app)
            (.then (fn [_]
                     (set-state! assoc
                                 :app app
                                 :network (model/network-state app text (:requested-origins @state-ref))
                                 :notice "已撤销该域名的联网许可；小应用会重新加载。"))))))))

;; ---------------------------------------------------------------------------
;; Workspace file picker

(defn- read-or-list-and-reply!
  [selected kind reply]
  (if (= kind :directory)
    (api/list! (:path selected)
               (fn [result]
                 (if (:ok? result)
                   (when reply
                     (reply {:ok? true
                             :value {:kind :directory
                                     :path (:path result)
                                     :entries (:entries result)}}))
                   (when reply
                     (reply {:ok? false :error (error-text result)})))))
    (api/read! (:path selected)
               (fn [result]
                 (if (:ok? result)
                   (when reply
                     (reply {:ok? true
                             :value {:kind :file
                                     :name (:name selected)
                                     :path (:path result)
                                     :text (:text result)
                                     :stamp (:stamp result)
                                     :size (:size result)}}))
                   (when reply
                     (reply {:ok? false :error (error-text result)})))))))

(defn- picker-load!
  [set-state! path]
  (set-state! update :picker assoc :loading? true :error nil)
  (api/list! path
             (fn [result]
               (if (:ok? result)
                 (set-state! update :picker assoc
                             :loading? false
                             :path (:path result)
                             :entries (vec (:entries result))
                             :selected nil)
                 (set-state! update :picker assoc
                             :loading? false
                             :error (request-error-text result))))))

(defn- open-picker!
  [set-state! payload reply!]
  (set-state! assoc
              :picker {:request payload
                       :path ""
                       :entries []
                       :selected nil
                       :loading? true
                       :error nil
                       :grant-mode (if (contains? #{:session :persistent} (:mode payload))
                                     (:mode payload)
                                     :once)
                       :reply reply!})
  (picker-load! set-state! ""))

(defn- cancel-picker!
  [state-ref set-state!]
  (let [picker (:picker @state-ref)]
    (when-let [reply (:reply picker)]
      (reply {:ok? false :error "用户取消了工作区文件访问请求"}))
    (set-state! assoc :picker nil)))

(defn- picker-confirm!
  [state-ref set-state!]
  (let [picker (:picker @state-ref)
        selected (:selected picker)
        reply (:reply picker)
        mode (:grant-mode picker)
        app (:app @state-ref)
        request-kind (keyword (or (get-in picker [:request :kind]) :file))
        kind (if (= :directory (:kind selected)) :directory :file)]
    (cond
      (nil? selected)
      (set-state! update :picker assoc :error "请选择一个文件或目录")

      (not= request-kind kind)
      (set-state! update :picker assoc
                  :error (str "此请求需要选择"
                              (if (= :directory request-kind) "目录" "单个文件")
                              "，当前选择类型不匹配。"))

      :else
      (do
        (when-not (= mode :once)
          (let [grant (model/normalize-grant {:kind kind
                                              :path (:path selected)
                                              :mode mode
                                              :app-id (:app-id app)})
                app (if (= mode :persistent)
                      (update app :grants model/merge-grants [grant])
                      app)]
            (when (= mode :persistent)
              (store/put-app! app))
            (set-state! assoc
                        :app app
                        :session-grants (if (= mode :session)
                                          (model/merge-grants (:session-grants @state-ref) [grant])
                                          (:session-grants @state-ref))
                        :notice (str "已授权" (if (= kind :directory) "目录" "文件")
                                     "：" (:path grant)
                                     (case mode
                                       :session "（本次打开期间）"
                                       :persistent "（长期关联）"
                                       "（仅本次）")))))
        (read-or-list-and-reply! selected kind reply)
        (set-state! assoc :picker nil)))))

;; ---------------------------------------------------------------------------
;; Rendering pieces

(defn- app-card
  [{:keys [app active? on-open on-data on-check]}]
  (dom/li {:key (:app-id app)}
          (dom/div {:className (str "hc-sandbox-row hc-miniapp-row"
                                    (when active? " hc-miniapp-row-active"))}
                   (dom/div {:className "hc-miniapp-main"}
                            (dom/strong {} (:name app))
                            (dom/div {:className "hc-sandbox-meta"}
                                     (status-badge (:status app))
                                     (dom/span {} (str "数据 " (model/format-bytes (:data-bytes app))))
                                     (dom/span {} (str "最近使用 " (time-label (:last-used-at app))))
                                     (when (:restored-version app)
                                       (dom/span {} (str "已恢复 v" (:restored-version app) "（本地）"))))
                            (dom/div {:className "hc-sandbox-url"} (:path app)))
                   (dom/div {:className "hc-sandbox-toolbar"}
                            (dom/button {:type "button"
                                         :className "hc-sandbox-button hc-sandbox-button-primary"
                                         :aria-label (str "打开 " (:name app))
                                         :onClick (fn [_] (on-open app))}
                                        "打开")
                            (dom/button {:type "button"
                                         :className "hc-sandbox-button"
                                         :aria-label (str "查看数据 " (:name app))
                                         :onClick (fn [_] (on-data app))}
                                        "数据")
                            (dom/button {:type "button"
                                         :className "hc-sandbox-button"
                                         :aria-label (str "检查文件 " (:name app))
                                         :onClick (fn [_] (on-check app))}
                                        "检查文件")))))

(defn- key-row
  [[key entry]]
  (dom/li {:key key :className "hc-sandbox-row"}
          (dom/span {:className "hc-sandbox-url"} (str key))
          (dom/span {:className "hc-sandbox-muted"}
                    (str (model/format-bytes (model/utf8-length (:value entry)))
                         " · " (time-label (:updated-at entry))))))

(defn- grant-row
  [grant on-revoke]
  (dom/li {:key (str (:kind grant) "-" (:path grant)) :className "hc-sandbox-row"}
          (dom/div {:className "hc-miniapp-main"}
                   (dom/span {} (str (if (= :directory (:kind grant)) "目录" "文件")
                                     "：" (:path grant)))
                   (dom/span {:className "hc-sandbox-muted"}
                             (str "权限 " (if (= :persistent (:mode grant))
                                            "长期关联"
                                            "本次打开期间"))))
          (dom/button {:type "button"
                       :className "hc-sandbox-button"
                       :onClick (fn [_] (on-revoke grant))}
                      "撤销")))

(defn- version-row
  [version current-version restored-version on-view on-export on-restore-local]
  (dom/li {:key (:version version) :className "hc-sandbox-row"}
          (dom/div {:className "hc-miniapp-main"}
                   (dom/span {} (str "版本 v" (:version version)
                                     (when (= (:version version) current-version)
                                       " · 当前")
                                     (when (= (:version version) restored-version)
                                       " · 已恢复")))
                   (dom/span {:className "hc-sandbox-muted"}
                             (str "保存于 " (time-label (:saved-at version))
                                  " · 声明数据版本 " (:data-version version))))
          (dom/div {:className "hc-sandbox-toolbar"}
                   (dom/button {:type "button"
                                :className "hc-sandbox-button"
                                :onClick (fn [_] (on-view version))}
                               "查看旧版")
                   (dom/button {:type "button"
                                :className "hc-sandbox-button"
                                :onClick (fn [_] (on-restore-local version))}
                               "恢复此旧版（本地）")
                   (dom/button {:type "button"
                                :className "hc-sandbox-button"
                                :onClick (fn [_] (on-export version))}
                               "导出 HTML"))))

(defn- data-panel
  [{:keys [app data versions session-grants on-export on-export-version
           on-restore on-restore-local on-clear on-revoke-grant
           on-revoke-session]}]
  (let [summary (:summary data)
        entries (:entries data)
        grants (vec (:grants app))
        keys (sort (keys entries))
        clearable? (pos? (long (or (:keys summary) 0)))]
    (dom/section {:className "hc-miniapp-data" :aria-label "小应用数据"}
                 (dom/div {:className "hc-sandbox-header"}
                          (dom/h3 {} "小应用数据"))
                 (dom/div {:className "hc-sandbox-meta"}
                          (dom/span {} (str (:keys summary) " 条记录"))
                          (dom/span {} (str "约 " (model/format-bytes (:bytes summary))))
                          (dom/span {} (str "最近写入 " (time-label (:last-updated-at summary)))))
                 (dom/div {:className "hc-sandbox-muted"}
                          "数据只属于此小应用；复制工作区文件不会复制这里的数据，其他小应用页面不共享此存储。")
                 (dom/div {:className "hc-sandbox-toolbar"}
                          (dom/button {:type "button"
                                       :className "hc-sandbox-button hc-sandbox-button-primary"
                                       :onClick (fn [_] (on-export))}
                                      "导出数据")
                          (dom/button {:type "button"
                                       :className "hc-sandbox-button hc-sandbox-button-danger"
                                       :disabled (not clearable?)
                                       :title (when-not clearable?
                                                clear-disabled-reason)
                                       :onClick (fn [_] (on-clear))}
                                      "清空数据"))
                 (when (seq keys)
                   (dom/div {:className "hc-miniapp-keys"}
                            (dom/strong {} "本地记录键")
                            (dom/div {:className "hc-sandbox-muted"}
                                     "这里只列出键名；值不会在数据页展开。")
                            (dom/ul {:className "hc-sandbox-list"}
                                    (for [key (take 50 keys)]
                                      (key-row [key (get entries key)])))
                            (when (> (count keys) 50)
                              (dom/div {:className "hc-sandbox-muted"}
                                       (str "另有 " (- (count keys) 50) " 个键未列出。")))))
                 (dom/div {:className "hc-miniapp-subsection"}
                          (dom/strong {} "已授权的文件范围")
                          (if (seq (concat grants session-grants))
                            (dom/ul {:className "hc-sandbox-list"}
                                    (for [grant grants]
                                      (grant-row grant
                                                 (fn [grant]
                                                   (on-revoke-grant grant))))
                                    (for [grant session-grants]
                                      (grant-row grant
                                                 (fn [grant]
                                                   (on-revoke-session grant)))))
                            (dom/div {:className "hc-sandbox-muted"}
                                     "尚未授权任何工作区文件；小应用请求时会让你选择具体文件或目录。")))
                 (dom/div {:className "hc-miniapp-subsection"}
                          (dom/strong {} "源版本")
                          (dom/div {:className "hc-sandbox-muted"}
                                   "文件更新会保留旧版；查看或本地恢复旧版都不会改写工作区文件。恢复只改变本应用读取的源，数据保留。")
                          (if (seq versions)
                            (dom/ul {:className "hc-sandbox-list"}
                                    (for [version (reverse versions)]
                                      (version-row version
                                                   (:source-version app)
                                                   (:restored-version app)
                                                   (fn [version] (on-restore version))
                                                   (fn [version] (on-export-version version))
                                                   (fn [version] (on-restore-local version)))))
                            (dom/div {:className "hc-sandbox-muted"} "尚无保存的源版本。"))))))

(defn- picker-panel
  [{:keys [picker on-load on-select on-mode on-confirm on-cancel]}]
  (let [selected (:selected picker)]
    (dom/section {:className "hc-miniapp-picker" :aria-label "工作区文件选择"}
                 (dom/div {:className "hc-sandbox-header"}
                          (dom/h3 {} "小应用请求访问工作区文件")
                          (dom/button {:type "button"
                                       :className "hc-sandbox-button"
                                       :onClick (fn [_] (on-cancel))}
                                      "取消"))
                 (dom/div {:className "hc-sandbox-muted"}
                          (str "请求：" (or (get-in picker [:request :path]) "未指定路径")
                               " · "
                               (if (= :directory (get-in picker [:request :kind]))
                                 "目录范围"
                                 "单个文件")))
                 (dom/div {:className "hc-sandbox-muted"}
                          (str "本次请求必须选择"
                               (if (= :directory (keyword (get-in picker [:request :kind])))
                                 "目录" "单个文件")
                               "；类型不匹配时不能确认。"))
                 (dom/div {:className "hc-sandbox-muted"}
                          "你选择的是工作区内的具体文件或目录；小应用不会获得整个设备目录。")
                 (dom/div {:className "hc-sandbox-toolbar"}
                          (dom/button {:type "button"
                                       :className "hc-sandbox-button"
                                       :disabled (str/blank? (:path picker))
                                       :onClick (fn [_] (on-load ""))}
                                      "工作区根")
                          (dom/span {:className "hc-sandbox-url"}
                                    (if (str/blank? (:path picker))
                                      "工作区根目录"
                                      (:path picker))))
                 (when (:error picker)
                   (ui/banner {:tone :danger
                               :text (:error picker)
                               :actions [{:label "刷新目录"
                                          :on-click #(on-load (:path picker))}]}))
                 (when (:loading? picker)
                   (dom/div {:className "hc-sandbox-muted" :role "status"} "正在读取目录…"))
                 (when (seq (:entries picker))
                   (dom/ul {:className "hc-sandbox-list"}
                           (for [entry (:entries picker)]
                             (dom/li {:key (:path entry) :className "hc-sandbox-row"}
                                     (dom/button {:type "button"
                                                  :className "hc-sandbox-button"
                                                  :onClick (fn [_]
                                                             (if (= :directory (:kind entry))
                                                               (on-load (:path entry))
                                                               (on-select entry)))}
                                                 (str (if (= :directory (:kind entry)) "▸ " "· ")
                                                      (:name entry)))
                                     (dom/span {:className "hc-sandbox-muted"}
                                               (str (if (= :directory (:kind entry))
                                                      "目录"
                                                      (model/format-bytes (:size entry)))
                                                    " · " (:path entry)))))))
                 (when selected
                   (dom/div {:className "hc-miniapp-selected"}
                            (dom/strong {} (str "已选择：" (:path selected)))
                            (dom/label {:className "hc-miniapp-field"}
                                       (dom/span {} "授权方式")
                                       (dom/select {:value (name (:grant-mode picker))
                                                    :onChange (fn [event]
                                                                (on-mode (keyword (.. event -target -value))))}
                                                   (dom/option {:value "once"} "仅本次读取")
                                                   (dom/option {:value "session"} "本次打开期间")
                                                   (dom/option {:value "persistent"} "长期关联（保存在本应用）")))))
                 (dom/div {:className "hc-sandbox-toolbar"}
                          (dom/button {:type "button"
                                       :className "hc-sandbox-button hc-sandbox-button-primary"
                                       :disabled (nil? selected)
                                       :onClick (fn [_] (on-confirm))}
                                      (str "授权并读取"
                                           (if (= :directory (:kind selected)) "目录" "文件")))))))

(defn- relink-panel
  [{:keys [relink on-confirm on-new on-cancel]}]
  (let [candidate (:candidate relink)
        result (:result relink)
        digest (model/content-digest (:text result))
        same? (= digest (:source-digest candidate))]
    (dom/section {:className "hc-miniapp-relink" :aria-label "关联未关联的小应用"}
                 (dom/h3 {} "发现内容相同的小应用")
                 (dom/div {:className "hc-sandbox-muted"}
                          (str "此文件内容指纹与未关联的小应用「" (:name candidate)
                               "」" (if same? "相同" "不同")
                               "。关联会保留它已有的数据；不会按文件名自动合并。"))
                 (dom/div {:className "hc-sandbox-url"} (:path result))
                 (dom/div {:className "hc-sandbox-toolbar"}
                          (dom/button {:type "button"
                                       :className "hc-sandbox-button hc-sandbox-button-primary"
                                       :onClick (fn [_] (on-confirm))}
                                      "关联并保留数据")
                          (dom/button {:type "button"
                                       :className "hc-sandbox-button"
                                       :onClick (fn [_] (on-new))}
                                      "作为新应用打开")
                          (dom/button {:type "button"
                                       :className "hc-sandbox-button"
                                       :onClick (fn [_] (on-cancel))}
                                      "取消")))))

(defn- clear-confirm-panel
  [{:keys [data on-confirm on-cancel]}]
  (let [summary (:summary data)
        keys (sort (keys (:entries data)))]
    (dom/div {:className "hc-miniapp-confirm-backdrop"
              :onClick (fn [event]
                         (when (= (.-target event) (.-currentTarget event))
                           (on-cancel)))}
             (dom/section {:className "hc-miniapp-confirm"
                           :role "dialog"
                           :aria-modal "true"
                           :aria-label "确认清空小应用数据"
                           :onKeyDown (fn [event]
                                        (when (= "Escape" (.-key event))
                                          (on-cancel)))}
                          (dom/h3 {} "清空此小应用的数据？")
                          (dom/div {:className "hc-sandbox-muted"}
                                   (str "将失去 " (:keys summary) " 条本地记录（约 "
                                        (model/format-bytes (:bytes summary)) "）："))
                          (when (seq keys)
                            (dom/ul {:className "hc-sandbox-list"}
                                    (for [key (take 30 keys)]
                                      (dom/li {:key key :className "hc-sandbox-row"}
                                              (dom/span {:className "hc-sandbox-url"} (str key))))))
                          (dom/div {:className "hc-sandbox-muted"}
                                   "只清空此小应用；其他小应用、普通浏览器数据和已导出的文件不受影响。")
                          (dom/div {:className "hc-sandbox-toolbar"}
                                   (dom/button {:type "button"
                                                :className "hc-sandbox-button hc-sandbox-button-danger"
                                                :onClick (fn [_] (on-confirm))}
                                               "确认清空")
                                   (dom/button {:type "button"
                                                :className "hc-sandbox-button"
                                                :autoFocus true
                                                :onClick (fn [_] (on-cancel))}
                                               "取消"))))))

;; ---------------------------------------------------------------------------
;; Main panel

(def miniapp-css
  ".hc-miniapps{display:flex;flex-direction:column;gap:12px;min-width:0}
.hc-miniapps h2,.hc-miniapps h3{margin:0}
.hc-miniapps-grid{display:grid;grid-template-columns:minmax(240px,34%) minmax(0,1fr);gap:12px;align-items:start}
@media(max-width:900px){.hc-miniapps-grid{grid-template-columns:1fr}}
.hc-miniapps-panel{display:flex;flex-direction:column;gap:10px;min-width:0}
.hc-miniapp-add{display:flex;flex-wrap:wrap;gap:6px;align-items:center}
.hc-miniapp-add input{flex:1 1 220px;min-height:40px;padding:6px 10px;border:1px solid var(--wb-line,#c8c8cc);border-radius:8px;background:var(--wb-surface,#fff);color:inherit;font:inherit}
.hc-miniapp-list{display:flex;flex-direction:column;gap:6px;margin:0;padding:0;list-style:none}
.hc-miniapp-row{display:flex;flex-wrap:wrap;gap:8px;align-items:center;justify-content:space-between;padding:8px;border:1px solid var(--wb-line,#d8d8dc);border-radius:10px}
.hc-miniapp-row-active{border-color:var(--wb-accent,#1565c0);box-shadow:0 0 0 1px var(--wb-accent,#1565c0) inset}
.hc-miniapp-main{display:flex;flex-direction:column;gap:3px;min-width:0;flex:1 1 220px}
.hc-miniapp-main strong{overflow-wrap:anywhere}
.hc-miniapp-data,.hc-miniapp-picker,.hc-miniapp-relink,.hc-miniapp-confirm{border:1px solid var(--wb-line,#d8d8dc);border-radius:12px;padding:10px;display:flex;flex-direction:column;gap:8px;background:var(--wb-surface,#fff)}
.hc-miniapp-subsection{border-top:1px solid var(--wb-line,#e4e4e8);padding-top:8px;display:flex;flex-direction:column;gap:6px}
.hc-miniapp-keys{display:flex;flex-direction:column;gap:4px}
.hc-miniapp-field{display:flex;flex-wrap:wrap;gap:6px;align-items:center;font-size:.86rem}
.hc-miniapp-field select{min-height:36px;padding:4px 8px;border:1px solid var(--wb-line,#c8c8cc);border-radius:8px;background:var(--wb-surface,#fff);color:inherit;font:inherit}
.hc-miniapp-selected{display:flex;flex-direction:column;gap:6px;padding:8px;border:1px dashed var(--wb-line,#c8c8cc);border-radius:8px}
.hc-miniapp-app-header{display:flex;flex-wrap:wrap;gap:8px;align-items:center;justify-content:space-between}
.hc-miniapp-app-header h2{font-size:1.08em}
.hc-miniapp-fullscreen{position:fixed;inset:0;z-index:1200;overflow:auto;padding:12px;background:var(--wb-surface,#fff);border-radius:0}
.hc-miniapp-fullscreen .hc-sandbox-frame{height:calc(100vh - 210px)}
.hc-miniapp-generate{border:1px solid var(--wb-line,#d8d8dc);border-radius:10px;padding:6px 10px;background:var(--wb-surface-2,#f3f3f5);margin:0}
.hc-miniapp-generate summary{cursor:pointer;min-height:36px;display:flex;align-items:center;font-weight:600}
.hc-miniapp-generate[open] summary{margin-bottom:6px}
.hc-miniapp-generate textarea,.hc-miniapp-generate input{display:block;box-sizing:border-box;width:100%;min-height:40px;margin:0 0 8px;padding:6px 10px;border:1px solid var(--wb-line,#c8c8cc);border-radius:8px;background:var(--wb-surface,#fff);color:inherit;font:inherit}
.hc-miniapp-generate textarea{min-height:76px;resize:vertical}
.hc-miniapp-generate .hc-sandbox-button{margin-top:2px}
.hc-miniapp-generate .hc-sandbox-muted{margin-top:6px}
.hc-miniapp-generate .hc-sandbox-banner{margin-top:6px}
.hc-miniapp-app-title{min-width:0}
.hc-miniapp-app-header h2{overflow-wrap:anywhere}
.hc-sandbox-button-danger{border-color:var(--wb-danger,#a40000);color:var(--wb-danger,#a40000)}
.hc-sandbox-button-danger:hover,.hc-sandbox-button-danger:focus-visible{outline-color:var(--wb-danger,#a40000)}
.hc-miniapp-confirm-backdrop{position:fixed;inset:0;z-index:1300;display:flex;align-items:center;justify-content:center;padding:16px;background:rgba(10,16,24,.5)}
.hc-miniapp-confirm{width:min(560px,100%);max-height:calc(100vh - 32px);overflow:auto;background:var(--wb-surface,#fff);border:1px solid var(--wb-line,#d8d8dc);border-radius:12px;padding:12px;display:flex;flex-direction:column;gap:8px}
@media(max-width:599px){.hc-miniapps .hc-sandbox-button,.hc-miniapp-generate summary{min-height:44px;min-width:44px}.hc-miniapp-add input{min-height:44px}}")

(defn miniapps-panel
  "Self-contained workspace mini-app panel.  Mount it as a React element or
  call it from another component's render."
  []
  (let [[state raw-set-state!] (react/useState (model/initial-state))
        set-state! (fn [f & args]
                     (raw-set-state!
                      (if (seq args)
                        (fn [current] (apply f current args))
                        f)))
        state-atom-ref (react/useRef (atom state))
        state-ref (.-current state-atom-ref)
        _ (reset! state-ref state)
        token (react/useMemo #(str "hc-app-" (random-uuid))
                             #js [(get-in state [:app :app-id])])
        refresh! (fn []
                   (-> (with-timeout (store/list-apps)
                                     6000
                                     "读取小应用列表超时；请检查浏览器是否允许 IndexedDB。")
                       (.then
                        (fn [apps]
                          (let [root (get-in @state-ref [:workspace :root])
                                apps (mapv model/normalize-app apps)
                                apps (model/apps-in-workspace apps root)]
                            (set-state! assoc
                                        :apps apps
                                        :loading? false
                                        :list-error nil)
                            (doseq [app apps]
                              (check-file! state-ref set-state! app)))))
                       (.catch (fn [error]
                                 (set-state! assoc
                                             :loading? false
                                             :list-error
                                             (str "读取小应用列表失败：" error))))))
        reload! (fn []
                  (api/root!
                   (fn [result]
                     (if (:ok? result)
                       (do (swap! state-ref assoc :workspace (:workspace result))
                           (set-state! assoc
                                       :workspace (:workspace result)
                                       :server-error nil)
                           (refresh!))
                       (set-state! assoc
                                   :server-error (request-error-text result))))))]
    (react/useEffect
     (fn []
       (refresh!)
       (reload!)
       (let [online! (fn [] (set-state! update :network assoc :online? (model/online?)))
             escape! (fn [event]
                       (when (and (= "Escape" (.-key event))
                                  (:fullscreen? @state-ref))
                         (set-state! assoc :fullscreen? false)))]
         (when (exists? js/window)
           (.addEventListener js/window "online" online!)
           (.addEventListener js/window "offline" online!)
           (.addEventListener js/document "keydown" escape!))
         (fn []
           (when (exists? js/window)
             (.removeEventListener js/window "online" online!)
             (.removeEventListener js/window "offline" online!)
             (.removeEventListener js/document "keydown" escape!)))))
     #js [])
    (let [app (:app state)
          source-text (or (get-in state [:using-old-version :text])
                          (get-in state [:source :text]))
          app-grants (:grants app)
          session-grants (model/grants-for-app (:session-grants state)
                                               (:app-id app))
          allowed-origins (get-in state [:network :allowed-origins])
          snapshot (get-in state [:data :snapshot])
          too-large? (boolean (:too-large? snapshot))
          force-open? (boolean (:force-open? state))
          storage-blocked? (and too-large? (not force-open?))
          clearable? (not (clear-disabled? state))
          request-clear! (fn []
                           (when clearable?
                             (set-state! assoc :confirm-clear? true)))
          frame-config {:mode "app"
                        :baseUrl (str "https://mini-app.invalid/"
                                      (js/encodeURIComponent (str (:path app))))
                        :online (get-in state [:network :online?])
                        :storage (if storage-blocked? {} (:data snapshot))
                        :storageOmitted (or storage-blocked? (:omitted? snapshot))
                        :storageTooLarge too-large?
                        :app {:id (:app-id app)
                              :name (:name app)
                              :path (:path app)
                              :workspaceRoot (:workspace-root app)
                              :dataVersion (or (get-in state [:using-old-version :data-version])
                                               (:data-version app))}
                        :allowedOrigins allowed-origins
                        :dataVersion (or (get-in state [:using-old-version :data-version])
                                         (:data-version app))}
          handle-event
          (fn [{:keys [type payload reply!]}]
            (let [current @state-ref
                  app (:app current)
                  app-grants (:grants app)
                  session-grants (model/grants-for-app (:session-grants current)
                                                       (:app-id app))
                  allowed-origins (get-in current [:network :allowed-origins])]
              (case type
                :ready (set-state! assoc :ready? true)
              :input nil
              :runtime-error
              (set-state! assoc :runtime-error payload
                          :notice (str "小应用脚本报错：" (or (:message payload) "未知错误")))

              :external-link
              (set-state! assoc :external (model/external-request :link payload))
              :download
              (set-state! assoc :external (model/external-request :download payload))
              :external-submit
              (set-state! assoc :external (model/external-request :submit payload))
              :network-request
              (let [origin (sandbox/normalize-origin (:url payload))]
                (when (and origin (not (contains? (set allowed-origins) origin)))
                  (set-state! (fn [current]
                                (let [requested (->> (conj (:requested-origins current) origin)
                                                     distinct
                                                     vec)
                                      text (or (get-in current [:using-old-version :text])
                                               (get-in current [:source :text])
                                               "")]
                                  (assoc current
                                         :requested-origins requested
                                         :network (model/network-state (:app current) text requested)
                                         :notice (str "小应用请求访问 " origin
                                                      "；当前未允许联网，请求已被策略阻止。")))))))

              :storage-get
              (-> (store/kv-get (:app-id app) (:key payload))
                  (.then (fn [entry]
                           (when reply!
                             (reply! {:ok? true :value (some-> entry :value)}))))
                  (.catch (fn [error]
                            (when reply!
                              (reply! {:ok? false :error (str error)})))))

              :storage-set
              (let [key (str (:key payload))
                    value (str (:value payload))]
                (-> (store/kv-put! (:app-id app) key value)
                    (.then (fn [_]
                             (set-state! assoc-data-entry key value)
                             (when reply! (reply! {:ok? true :value true}))))
                    (.catch (fn [error]
                              (set-state! assoc :notice (str "小应用保存失败：" error))
                              (when reply!
                                (reply! {:ok? false :error (str error)}))))))

              :storage-remove
              (let [key (str (:key payload))]
                (-> (store/kv-delete! (:app-id app) key)
                    (.then (fn [_]
                             (set-state! remove-data-entry key)
                             (when reply! (reply! {:ok? true :value true}))))
                    (.catch (fn [error]
                              (when reply!
                                (reply! {:ok? false :error (str error)}))))))

              :storage-keys
              (-> (store/kv-all (:app-id app))
                  (.then (fn [entries]
                           (when reply!
                             (reply! {:ok? true :value (mapv :key entries)}))))
                  (.catch (fn [error]
                            (when reply!
                              (reply! {:ok? false :error (str error)})))))

              :storage-clear
              (-> (store/kv-clear! (:app-id app))
                  (.then (fn [_]
                           (set-state! assoc
                                       :app (assoc app :data-bytes 0)
                                       :data {:entries {}
                                              :summary (model/data-summary [])
                                              :snapshot {:data {} :omitted? false}})
                           (when reply! (reply! {:ok? true :value true}))))
                  (.catch (fn [error]
                            (when reply!
                              (reply! {:ok? false :error (str error)})))))

              :workspace-request
              (open-picker! set-state! payload reply!)

              :workspace-read
              (let [path (:path payload)
                    grants (concat app-grants session-grants)]
                (if (model/grant-allows? grants path)
                  (api/read! path
                             (fn [result]
                               (if (:ok? result)
                                 (when reply!
                                   (reply! {:ok? true
                                            :value {:kind :file
                                                    :path (:path result)
                                                    :text (:text result)
                                                    :stamp (:stamp result)
                                                    :size (:size result)}}))
                                 (when reply!
                                   (reply! {:ok? false :error (error-text result)})))))
                  (open-picker! set-state! {:path path :kind :file} reply!)))

              :workspace-list
              (let [path (:path payload)
                    grants (concat app-grants session-grants)]
                (if (model/grant-allows? grants path)
                  (api/list! path
                             (fn [result]
                               (if (:ok? result)
                                 (when reply!
                                   (reply! {:ok? true
                                            :value {:kind :directory
                                                    :path (:path result)
                                                    :entries (:entries result)}}))
                                 (when reply!
                                   (reply! {:ok? false :error (error-text result)})))))
                  (open-picker! set-state! {:path path :kind :directory} reply!)))

                (when reply!
                  (reply! {:ok? false :error "小应用请求了不支持的操作"})))))]
      (dom/div {:className (str "hc-miniapps "
                                (when (:fullscreen? state) "hc-miniapp-fullscreen"))}
               (dom/style {:dangerouslySetInnerHTML #js {:__html (str ui/css miniapp-css)}})
               (dom/div {:className "hc-sandbox-header"}
                        (dom/div {:className "hc-sandbox-title"}
                                 (dom/h2 {} "工作区小应用")
                                 (dom/div {:className "hc-sandbox-meta"}
                                          (dom/span {:className "hc-sandbox-badge"} "独立存储")
                                          (dom/span {:className "hc-sandbox-badge"} "沙箱运行")
                                          (when-let [workspace (:workspace state)]
                                            (dom/span {} (str "工作区：" (:name workspace)
                                                              " · " (:root workspace))))
                                          (dom/span {} (if (get-in state [:network :online?])
                                                         "在线"
                                                         "离线"))))
                        (dom/div {:className "hc-sandbox-toolbar"}
                                 (dom/button {:type "button"
                                              :className "hc-sandbox-button"
                                              :onClick (fn [_] (reload!))}
                                             "刷新")
                                 (dom/button {:type "button"
                                              :className "hc-sandbox-button"
                                              :onClick (fn [_] (set-state! update :fullscreen? not))}
                                             (if (:fullscreen? state) "退出全屏" "全屏"))))
               (when (:list-error state)
                 (ui/banner {:tone :danger
                             :text (:list-error state)
                             :actions [{:label "重试"
                                        :on-click #(refresh!)}]}))
               (when (:server-error state)
                 (ui/banner {:tone :danger
                             :text (:server-error state)
                             :actions [{:label "刷新"
                                        :on-click reload!}
                                       {:label "打开设置"
                                        :on-click open-settings!}]}))
               (dom/div {:className "hc-miniapps-grid"}
                        (dom/section {:className "hc-miniapps-panel" :aria-label "小应用列表"}
                                     (dom/div {:className "hc-miniapp-add"}
                                              (dom/input {:type "text"
                                                          :value (:add-path state)
                                                          :placeholder "例如 apps/reading-log.html"
                                                          :aria-label "工作区 HTML 路径"
                                                          :onChange (fn [event]
                                                                      (set-state! assoc :add-path (.. event -target -value)
                                                                                  :add-error nil))
                                                          :onKeyDown (fn [event]
                                                                       (when (= "Enter" (.-key event))
                                                                         (add-app! state-ref set-state! (:add-path state))))})
                                              (dom/button {:type "button"
                                                           :className "hc-sandbox-button hc-sandbox-button-primary"
                                                           :onClick (fn [_]
                                                                      (add-app! state-ref set-state! (:add-path state)))}
                                                          "打开为小应用"))
                                     (dom/details {:className "hc-miniapp-generate"}
                                                  (dom/summary {} "生成小应用")
                                                  (dom/textarea
                                                   {:value (or (:generate-prompt state) "")
                                                    :aria-label "小应用生成提示词"
                                                    :placeholder "描述要生成的小应用"
                                                    :onChange (fn [event]
                                                                (set-state! assoc :generate-prompt
                                                                            (.. event -target -value)))}
                                                   )
                                                  (dom/input
                                                   {:type "text"
                                                    :value (or (:generate-name state) "")
                                                    :placeholder "例如 reading-log.html（固定写入 miniapps/）"
                                                    :aria-label "小应用文件名"
                                                    :onChange (fn [event]
                                                                (set-state! assoc :generate-name
                                                                            (.. event -target -value)))})
                                                  (dom/button
                                                   {:type "button"
                                                    :className "hc-sandbox-button hc-sandbox-button-primary"
                                                    :disabled (boolean (:generating? state))
                                                    :onClick
                                                    (fn [_]
                                                      (set-state! assoc :generating? true :generate-error nil)
                                                      (api/generate!
                                                       (:generate-prompt state)
                                                       (:generate-name state)
                                                       (fn [result]
                                                         (set-state! assoc
                                                                     :generating? false
                                                                     :generate-error
                                                                     (when-not (:ok? result)
                                                                       (request-error-text result)))
                                                         (when (:ok? result)
                                                           (let [app (model/normalize-app (:app result))]
                                                             (when app
                                                               (-> (store/put-app! app)
                                                                   (.then
                                                                    (fn [_]
                                                                      (set-state!
                                                                       update :apps
                                                                       (fn [apps]
                                                                         (vec (cons app
                                                                                    (remove #(= (:app-id %)
                                                                                                (:app-id app))
                                                                                            apps)))))
                                                                      (load-app! set-state! (:app-id app))))
                                                                   (.catch
                                                                    (fn [error]
                                                                      (set-state! assoc
                                                                                  :generate-error
                                                                                  (str "登记小应用失败：" error)))))))))))}
                                                   (if (:generating? state) "正在生成…" "生成小应用"))
                                                  (when (:generating? state)
                                                    (dom/div {:className "hc-sandbox-muted"
                                                              :role "status"}
                                                             "正在生成，最长等待 5 分钟；失败时下方已有文件仍可查看。"))
                                                  (when (:generate-error state)
                                                    (ui/banner {:tone :danger
                                                                :text (:generate-error state)
                                                                :actions
                                                                [{:label "刷新已有文件"
                                                                  :on-click reload!}
                                                                 {:label "打开设置"
                                                                  :on-click open-settings!}]})))
                                     (when (:add-error state)
                                       (ui/banner {:tone :danger :text (:add-error state)}))
                                     (if (:loading? state)
                                       (dom/div {:className "hc-sandbox-muted" :role "status"} "正在读取小应用列表…")
                                       (if (seq (:apps state))
                                         (dom/ul {:className "hc-miniapp-list"}
                                                 (for [app (:apps state)]
                                                   (app-card {:app app
                                                              :active? (= (:app-id app) (:selected-id state))
                                                              :on-open (fn [app] (load-app! set-state! (:app-id app)))
                                                              :on-data (fn [app]
                                                                         (load-app! set-state! (:app-id app))
                                                                         (set-state! assoc :show-data? true))
                                                              :on-check (fn [app] (check-file! state-ref set-state! app))})))
                                         (dom/div {:className "hc-sandbox-muted"}
                                                  "还没有工作区小应用。输入一个工作区内的 HTML 文件路径，或先在文件页找到它。")))
                                     (dom/div {:className "hc-sandbox-muted"}
                                              "小应用是工作区中的 HTML 文件。复制文件默认创建独立实例，不复制数据；删除文件只会让它暂时未关联。"))
                        (dom/section {:className "hc-miniapps-panel" :aria-label "小应用详情"}
                                     (when (:notice state)
                                       (ui/banner {:tone :info :text (:notice state)}))
                                     (when (:runtime-error state)
                                       (ui/banner {:tone :danger
                                                   :text (str "脚本错误：" (or (:message (:runtime-error state)) "未知错误"))}))
                                     (cond
                                       (= :error (:status state))
                                       (ui/banner
                                        {:tone :danger
                                         :text (or (:error state) "读取失败")
                                         :actions
                                         (cond-> [{:label "打开设置"
                                                   :on-click open-settings!}]
                                           (:app-id app)
                                           (conj {:label "重新读取"
                                                  :on-click #(load-app! set-state!
                                                                        (:app-id app))}))})

                                       (nil? app)
                                       (dom/div {:className "hc-sandbox-muted"}
                                                "选择左侧的小应用，或输入路径打开一个 HTML 文件。")

                                       (= :loading (:status state))
                                       (dom/div {:className "hc-sandbox-muted" :role "status"} "正在读取小应用…")

                                       :else
                                       (dom/div {:className "hc-miniapps-panel"}
                                                (dom/div {:className "hc-miniapp-app-header"}
                                                         (dom/div {:className "hc-miniapp-app-title"}
                                                                  (dom/h2 {} (:name app))
                                                                  (dom/div {:className "hc-sandbox-meta"}
                                                                           (status-badge (:status app))
                                                                           (dom/span {:className "hc-sandbox-url"} (:path app))
                                                                           (dom/span {} (str "数据 " (model/format-bytes (get-in state [:data :summary :bytes]))))))
                                                         (dom/div {:className "hc-sandbox-toolbar"}
                                                                  (dom/button {:type "button"
                                                                               :className "hc-sandbox-button"
                                                                               :onClick (fn [_] (set-state! update :show-data? not))}
                                                                              (if (:show-data? state) "收起数据" "数据"))
                                                                  (dom/button {:type "button"
                                                                               :className "hc-sandbox-button"
                                                                               :onClick (fn [_] (check-file! state-ref set-state! app))}
                                                                              "检查文件")
                                                                  (dom/button {:type "button"
                                                                               :className "hc-sandbox-button"
                                                                               :onClick (fn [_] (set-state! assoc :selected-id nil :app nil :status :idle))}
                                                                              "关闭")))
                                                (when-let [compat (model/compat-view (:compat state))]
                                                  (ui/banner {:tone (:tone compat) :text (:text compat)
                                                              :actions [{:label "查看数据"
                                                                         :on-click #(set-state! assoc :show-data? true)}
                                                                        {:label "导出数据"
                                                                         :on-click #(export-data! state-ref)}]}))
                                                (when (:using-old-version state)
                                                  (ui/banner {:tone :warn
                                                              :text (if (:restored-version? state)
                                                                      (str "已恢复旧版 v" (get-in state [:using-old-version :version])
                                                                           "（本地）；工作区文件未改写，数据保留。")
                                                                      (str "正在查看旧版 v" (get-in state [:using-old-version :version])
                                                                           "；当前工作区文件未改变。"))
                                                              :actions [{:label "回到当前版本"
                                                                         :on-click #(back-to-current! set-state! app)}]}))
                                                (when (= :unlinked (:status state))
                                                  (ui/banner {:tone :warn
                                                              :text "文件当前不可找到；数据仍保留。可以输入新路径重新关联，或导出/清空数据。"
                                                              :actions [{:label "重新关联"
                                                                         :on-click focus-relink!}
                                                                        {:label "导出数据"
                                                                         :on-click #(export-data! state-ref)}
                                                                        {:label "清空数据"
                                                                         :disabled? (not clearable?)
                                                                         :reason clear-disabled-reason
                                                                         :on-click request-clear!}]})
                                                  (dom/div {:className "hc-miniapp-add"}
                                                           (dom/input {:id "hc-miniapp-relink-path"
                                                                       :type "text"
                                                                       :value (or (:relink-path state) (:path app))
                                                                       :aria-label "重新关联路径"
                                                                       :onChange (fn [event]
                                                                                   (set-state! assoc :relink-path (.. event -target -value)))
                                                                       :onKeyDown (fn [event]
                                                                                    (when (= "Enter" (.-key event))
                                                                                      (add-app! state-ref set-state! (:relink-path state))))})
                                                           (dom/button {:type "button"
                                                                        :className "hc-sandbox-button"
                                                                        :onClick (fn [_]
                                                                                   (add-app! state-ref set-state! (:relink-path state)))}
                                                                       "检查并关联")))
                                                (when (seq (get-in state [:network :allowed-origins]))
                                                  (dom/div {:className "hc-sandbox-muted"}
                                                           (str "已允许联网：" (str/join "、" (get-in state [:network :allowed-origins])))))
                                                (when (seq (get-in state [:network :pending-origins]))
                                                  (dom/div {:className "hc-miniapp-subsection"}
                                                           (dom/strong {} "外部域名")
                                                           (dom/div {:className "hc-sandbox-muted"}
                                                                    "默认阻止联网；只有你允许的域名会进入 Content-Security-Policy。")
                                                           (dom/ul {:className "hc-sandbox-list"}
                                                                   (for [origin (get-in state [:network :pending-origins])]
                                                                     (dom/li {:key origin :className "hc-sandbox-row"}
                                                                             (dom/span {:className "hc-sandbox-url"} origin)
                                                                             (dom/div {:className "hc-sandbox-toolbar"}
                                                                                      (dom/button {:type "button"
                                                                                                   :className "hc-sandbox-button"
                                                                                                   :onClick (fn [_] (allow-origins! state-ref set-state! [origin]))}
                                                                                                  "允许")
                                                                                      (dom/button {:type "button"
                                                                                                   :className "hc-sandbox-button"
                                                                                                   :onClick (fn [_]
                                                                                                              (copy-text! origin)
                                                                                                              (set-state! assoc :notice "域名已复制。"))}
                                                                                                  "复制")))))))
                                                (when (and (not (get-in state [:network :online?]))
                                                           (seq (get-in state [:data :entries])))
                                                  (ui/banner {:tone :info
                                                              :text (str "离线：外部服务不可达；已保存的 "
                                                                         (get-in state [:data :summary :keys])
                                                                         " 条记录仍可读取，最近写入 "
                                                                         (time-label (get-in state [:data :summary :last-updated-at]))
                                                                         "。")}))
                                                (when too-large?
                                                  (ui/banner
                                                   {:tone :warn
                                                    :text (str "数据约 "
                                                               (model/format-bytes (get-in state [:data :summary :bytes]))
                                                               "，超过同步 localStorage 安全上限。"
                                                               (if storage-blocked?
                                                                 "未打开小应用，避免以空列表误导；可导出、清空，或明确选择不同步打开。"
                                                                 "已明确选择不同步打开；应用必须使用 Herdchestra.storage 读取数据，普通 localStorage 初始为空。"))
                                                    :actions (cond-> [{:label "导出数据"
                                                                       :on-click #(export-data! state-ref)}
                                                                      {:label "清空数据"
                                                                       :disabled? (not clearable?)
                                                                       :reason clear-disabled-reason
                                                                       :on-click request-clear!}]
                                                               storage-blocked?
                                                               (conj {:label "仍然打开（不同步 localStorage）"
                                                                      :on-click #(set-state! assoc
                                                                                             :force-open? true
                                                                                             :notice "已选择不同步 localStorage；应用需使用 Herdchestra.storage 读取完整数据。")}))}))
                                                (cond
                                                  storage-blocked?
                                                  (dom/div {:className "hc-sandbox-muted"}
                                                           "小应用未打开：同步存储超过安全上限，不能用空列表冒充没有记录。")

                                                  source-text
                                                  (frame/sandbox-frame-element
                                                   {:key (str token "-" (:app-id app) "-"
                                                              (get-in state [:source :version]) "-"
                                                              (get-in state [:using-old-version :version]) "-"
                                                              (pr-str allowed-origins)
                                                              "-" force-open?)
                                                    :html source-text
                                                    :token token
                                                    :mode :app
                                                    :config frame-config
                                                    :allowed-origins allowed-origins
                                                    :revision (str (get-in state [:source :version]) "-"
                                                                   (get-in state [:using-old-version :version]) "-"
                                                                   force-open? "-"
                                                                   (:frame-epoch state))
                                                    :title (str "工作区小应用：" (:name app))
                                                    :on-event handle-event})

                                                  :else
                                                  (dom/div {:className "hc-sandbox-muted"}
                                                           "当前没有可渲染的源内容。"))
                                                (when (:show-data? state)
                                                  (data-panel {:app app
                                                               :data (:data state)
                                                               :versions (:versions state)
                                                               :session-grants session-grants
                                                               :on-export #(export-data! state-ref)
                                                               :on-export-version #(export-version! app %)
                                                               :on-restore #(restore-version! state-ref set-state! %)
                                                               :on-restore-local #(restore-version-local! state-ref set-state! %)
                                                               :on-clear request-clear!
                                                               :on-revoke-grant (fn [grant]
                                                                                  (let [app (update app :grants
                                                                                                    (fn [grants]
                                                                                                      (model/revoke-grant grants (:path grant) (:kind grant))))]
                                                                                    (store/put-app! app)
                                                                                    (set-state! assoc :app app
                                                                                                :notice "已撤销该文件范围。")))
                                                               :on-revoke-session (fn [grant]
                                                                                    (set-state! update :session-grants
                                                                                                #(vec (remove (fn [g]
                                                                                                                (and (= (:app-id g) (:app-id app))
                                                                                                                     (= (:path g) (:path grant))
                                                                                                                     (= (:kind g) (:kind grant))))
                                                                                                              %))))}))))))
               (when (:picker state)
                 (picker-panel {:picker (:picker state)
                                :on-load #(picker-load! set-state! %)
                                :on-select #(set-state! update :picker assoc :selected %)
                                :on-mode #(set-state! update :picker assoc :grant-mode %)
                                :on-confirm #(picker-confirm! state-ref set-state!)
                                :on-cancel #(cancel-picker! state-ref set-state!)}))
               (when (:relink state)
                 (relink-panel {:relink (:relink state)
                                :on-confirm #(relink-confirm! state-ref set-state!)
                                :on-new #(let [{:keys [result]} (:relink state)]
                                           (set-state! assoc :relink nil)
                                           (create-app! set-state! (get-in state [:workspace :root]) result))
                                :on-cancel #(set-state! assoc :relink nil)}))
               (when (and (:confirm-clear? state) clearable?)
                 (clear-confirm-panel {:data (:data state)
                                       :on-confirm #(clear-data! state-ref set-state!)
                                       :on-cancel #(set-state! assoc :confirm-clear? false)}))
               (when (:external state)
                 (ui/external-panel
                  {:request (:external state)
                   :callbacks {:open-url (fn [target]
                                           (open-external! target)
                                           (set-state! assoc :external nil))
                               :copy-url (fn [target]
                                           (copy-text! target)
                                           (set-state! assoc :external nil :notice "地址已复制。"))
                               :download-data (fn [request]
                                                (download-request! request)
                                                (set-state! assoc :external nil))
                               :cancel (fn [_]
                                         (set-state! assoc :external nil))}}))
               (dom/div {:className "hc-sandbox-muted"}
                        "小应用与普通浏览器、其他小应用、其他工作区互不共享登录态或本地存储；模型不会自动读取小应用数据。")))))
