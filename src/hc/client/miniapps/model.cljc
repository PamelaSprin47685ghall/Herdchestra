(ns hc.client.miniapps.model
  "Pure model for workspace mini-apps.

  Identity is the app ID, not the file name.  A path finds an existing app;
  a content fingerprint only suggests a relink for an unlinked app and never
  merges automatically.  Data is keyed by app ID, so removing or replacing a
  file cannot erase it.

  The model also owns the upgrade decision.  A changed source is kept as a new
  version, the old source stays available, and a declared data-version change
  is reported as a compatibility warning rather than a silent migration."
  (:require [clojure.string :as str]
            [hc.client.sandbox.model :as sandbox]))

;; ---------------------------------------------------------------------------
;; Field and path helpers

(defn field
  [m k]
  (sandbox/field m k))

(defn now-string
  []
  (sandbox/now-string))

(defn html-path?
  [path]
  (boolean (re-find #"(?i)\.(?:html?|xhtml)$" (str path))))

(defn app-name-from-path
  [path]
  (let [segments (->> (str/split (str (or path "")) #"/")
                      (remove str/blank?))]
    (or (last segments) "小应用")))

(defn normalize-path
  [path]
  (-> (str (or path ""))
      (str/replace "\\" "/")
      (str/replace #"^\./" "")
      (str/replace #"^/+" "")
      (str/replace #"/+$" "")))

(defn safe-workspace-path?
  "A relative path with no parent traversal and no absolute/Windows root."
  [path]
  (let [path (str (or path ""))
        segments (str/split (normalize-path path) #"/")]
    (and (not (str/blank? path))
         (not (str/starts-with? path "/"))
         (not (re-find #"(?i)^[a-z]:" path))
         (not (str/includes? path "\\"))
         (not (str/includes? path "\u0000"))
         (not-any? #(= ".." %) segments))))

(defn path-under?
  [path prefix]
  (let [path (normalize-path path)
        prefix (normalize-path prefix)]
    (or (= path prefix)
        (str/starts-with? path (str prefix "/")))))

;; ---------------------------------------------------------------------------
;; Content identity and declared data version

(defn content-digest
  "Deterministic, non-cryptographic fingerprint for change detection.

  It is used to notice that a file changed and to suggest a relink for an
  unlinked app.  It is never treated as a security identity and never merges
  two apps without a user choice."
  [text]
  (let [text (str text)]
    (loop [index 0
           hash 2166136261]
      (if (< index (count text))
        (let [code #?(:clj (int (.charAt text index))
                      :cljs (.charCodeAt text index))
              hash (bit-xor hash code)
              hash #?(:clj (bit-and (unchecked-multiply hash 16777619) 0xffffffff)
                      :cljs (unsigned-bit-shift-right (js/Math.imul hash 16777619) 0))]
          (recur (inc index) hash))
        (let [value #?(:clj (long hash)
                       :cljs (unsigned-bit-shift-right hash 0))
              raw #?(:clj (format "%08x" value)
                     :cljs (.toString value 16))]
          (str (apply str (repeat (max 0 (- 8 (count raw))) "0")) raw))))))

(defn- tag-attribute
  [tag attribute]
  (some (fn [[_ key value]]
          (when (= (str/lower-case key) (str/lower-case attribute))
            value))
        (re-seq #"(?i)([a-zA-Z][a-zA-Z0-9_-]*)\s*=\s*[\"']([^\"']*)[\"']" tag)))

(defn declared-data-version
  "Read `<meta name=\"herdchestra-data-version\" content=\"N\">`.

  A missing or malformed declaration means version 1.  The value describes
  the app's data shape; it is not a trust signal."
  [html]
  (let [tags (re-seq #"(?is)<meta\b[^>]*>" (str (or html "")))
        tag (some (fn [tag]
                    (when (= "herdchestra-data-version"
                             (some-> (tag-attribute tag "name") str/lower-case))
                      tag))
                  tags)
        content (some-> (tag-attribute (or tag "") "content") str/trim)]
    (if (and content (re-matches #"\d{1,9}" content))
      #?(:clj (Long/parseLong content)
         :cljs (js/parseInt content 10))
      1)))

(defn upgrade-info
  "Compare a stored app with the current file source."
  [app html]
  (let [digest (content-digest html)
        previous-version (long (or (:data-version app) 1))
        declared-version (declared-data-version html)
        changed? (not= digest (:source-digest app))]
    {:changed? changed?
     :digest digest
     :declared-version declared-version
     :previous-version previous-version
     :compatibility (cond
                      (not changed?) :unchanged
                      (> declared-version previous-version) :unknown
                      (< declared-version previous-version) :downgrade
                      :else :compatible)}))

(defn compat-view
  [compat]
  (when (map? compat)
    (let [declared (:declared-version compat)
          previous (:previous-version compat)]
      (case (:kind compat)
        :unknown {:tone :warn
                  :text (str "当前源声明数据版本 " declared
                             "，已保存数据版本 " previous
                             "；无法确认能否读取旧数据。数据已保留，可恢复旧版或导出。")}
        :downgrade {:tone :warn
                    :text (str "当前源声明数据版本 " declared
                               "，低于已保存数据版本 " previous
                               "；数据已保留，请在当前源中检查记录。")}
        :compatible {:tone :info
                     :text "小应用文件已更新；数据已保留，旧版仍可查看。"}
        nil))))

;; ---------------------------------------------------------------------------
;; App records

(defn normalize-app
  [app]
  (when (map? app)
    (-> app
        (update :network-allowed #(vec (or % [])))
        (update :grants #(vec (or % [])))
        (update :data-version #(long (or % 1)))
        (update :source-version #(long (or % 1)))
        (update :status #(or % :available)))))

(defn new-app
  [workspace-root path html]
  (let [path (normalize-path path)
        declared (declared-data-version html)]
    {:app-id (str "app-" (random-uuid))
     :workspace-root (str workspace-root)
     :path path
     :name (app-name-from-path path)
     :created-at (now-string)
     :last-used-at (now-string)
     :last-updated-at (now-string)
     :data-version declared
     :source-data-version declared
     :source-digest (content-digest html)
     :source-version 1
     :data-bytes 0
     :status :available
     :network-allowed []
     :grants []}))

(defn app-status-label
  [status]
  (case status
    :available "文件可用"
    :unlinked "文件未关联"
    :missing "文件不存在"
    :error "读取失败"
    "状态未知"))

(defn status-tone
  [status]
  (case status
    :available :ok
    :unlinked :warn
    :missing :danger
    :error :danger
    :neutral))

;; ---------------------------------------------------------------------------
;; Data summary and export

(defn entries-seq
  [entries]
  (cond
    (map? entries) (vals entries)
    (sequential? entries) entries
    :else []))

(defn utf8-length
  [value]
  #?(:clj (alength (.getBytes (str (or value "")) "UTF-8"))
     :cljs (let [text (str (or value ""))
                 encoder (when (exists? js/TextEncoder) (js/TextEncoder.))]
             (if encoder
               (.-length (.encode encoder text))
               (count text)))))

(defn data-summary
  [entries]
  (let [entries (vec (entries-seq entries))
        bytes (reduce (fn [total entry]
                        (+ total
                           (utf8-length (:key entry))
                           (utf8-length (:value entry))))
                      0
                      entries)
        last-updated (->> entries (keep #(get % :updated-at)) sort last)]
    {:keys (count entries)
     :bytes bytes
     :last-updated-at last-updated}))

(defn format-bytes
  [bytes]
  (let [bytes (long (or bytes 0))]
    (cond
      (< bytes 1024) (str bytes " B")
      (< bytes (* 1024 1024))
      (let [tenths (quot (+ (* bytes 10) 512) 1024)]
        (str (quot tenths 10) "." (rem tenths 10) " KiB"))
      :else
      (let [tenths (quot (+ (* bytes 10) (* 512 1024)) (* 1024 1024))]
        (str (quot tenths 10) "." (rem tenths 10) " MiB")))))

(def sync-warning-bytes
  "Above this size the host warns that the synchronous bootstrap is large.
  The data is still injected; a warning is not permission to show an empty
  store."
  (* 512 1024))

(def sync-hard-limit
  "Hard cap for the synchronous `localStorage` bootstrap.  Above it the host
  refuses to open the app with an empty shim.  The user may explicitly choose
  an asynchronous-only open, export, or clear."
  (* 8 1024 1024))

(defn storage-snapshot
  "Data injected into the synchronous `localStorage` shim.

  Up to `sync-hard-limit` every key is injected, even above the old 512 KiB
  warning threshold.  A truncated or empty shim would look like lost user
  data, so above the hard cap the result is explicitly `:too-large?` and the
  host must not render the app as if the store were empty."
  ([entries] (storage-snapshot entries sync-hard-limit))
  ([entries limit]
   (let [entries (entries-seq entries)
         data (into {} (map (juxt :key :value) entries))
         bytes (utf8-length (sandbox/json data))]
     (if (> bytes limit)
       {:data {}
        :omitted? true
        :too-large? true
        :large? true
        :bytes bytes}
       {:data data
        :omitted? false
        :too-large? false
        :large? (> bytes sync-warning-bytes)
        :bytes bytes}))))

(defn export-document
  [app entries]
  {:format "herdchestra-miniapp-data"
   :format-version 1
   :exported-at (now-string)
   :app {:app-id (:app-id app)
         :name (:name app)
         :workspace-root (:workspace-root app)
         :path (:path app)
         :data-version (:data-version app)}
   :data (into {} (map (juxt :key :value) (entries-seq entries)))})

(defn export-filename
  [app]
  (let [base (-> (str (or (:name app) "miniapp"))
                 (str/replace #"(?i)\.(?:html?|xhtml)$" "")
                 (str/replace #"[^A-Za-z0-9._-]+" "_"))]
    (str (if (str/blank? base) "miniapp" base) "-data.json")))

(defn apps-in-workspace
  "Apps visible in ROOT.  A nil root means the workspace identity is not known
  yet, so no app is shown rather than every app from every workspace."
  [apps root]
  (if (some? root)
    (filterv #(= root (:workspace-root %)) (or apps []))
    []))

(defn grants-for-app
  "Session grants belonging to APP-ID only."
  [grants app-id]
  (filterv #(= app-id (:app-id %)) (or grants [])))

;; ---------------------------------------------------------------------------
;; Grants

(defn normalize-grant
  [grant]
  (when (map? grant)
    (let [path (normalize-path (:path grant))
          kind (or (:kind grant) :file)]
      (when (and (safe-workspace-path? path)
                 (contains? #{:file :directory} kind))
        (cond-> {:kind kind
                 :path path
                 :mode (or (:mode grant) :persistent)
                 :granted-at (or (:granted-at grant) (now-string))}
          (:app-id grant) (assoc :app-id (:app-id grant)))))))

(defn merge-grants
  [grants additions]
  (->> (concat (or grants []) additions)
       (keep #(normalize-grant %))
       (reduce (fn [acc grant]
                 (assoc acc [(:app-id grant) (:kind grant) (:path grant)] grant))
               {})
       vals
       (sort-by (juxt :kind :path))
       vec))

(defn revoke-grant
  [grants path kind]
  (->> (or grants [])
       (keep #(normalize-grant %))
       (remove #(and (= (normalize-path (:path %)) (normalize-path path))
                     (= (:kind %) kind)))
       vec))

(defn grant-allows?
  [grants path]
  (let [path (normalize-path path)]
    (boolean
     (some (fn [grant]
             (let [grant (normalize-grant grant)]
               (when grant
                 (case (:kind grant)
                   :directory (path-under? path (:path grant))
                   :file (= path (:path grant))
                   false))))
           (or grants [])))))

;; ---------------------------------------------------------------------------
;; Network

(defn online?
  []
  #?(:clj true
     :cljs (if (exists? js/navigator)
             (not (false? (.-onLine js/navigator)))
             true)))

(defn network-state
  ([app html] (network-state app html []))
  ([app html requested-origins]
   (let [allowed (set (map sandbox/normalize-origin (:network-allowed app)))
         discovered (sandbox/external-origins html)
         requested (keep sandbox/normalize-origin requested-origins)
         pending (->> (concat discovered requested)
                      (remove #(contains? allowed %))
                      distinct
                      sort
                      vec)]
     {:allowed-origins (vec (sort (remove nil? allowed)))
      :discovered-origins discovered
      :pending-origins pending
      :online? (online?)})))

(defn allow-origins
  [app origins]
  (update app :network-allowed
          (fn [current]
            (->> (concat (or current []) origins)
                 (keep #(sandbox/normalize-origin %))
                 distinct
                 sort
                 vec))))

(defn revoke-origin
  [app origin]
  (let [origin (sandbox/normalize-origin origin)]
    (update app :network-allowed
            (fn [current]
              (->> (or current [])
                   (keep #(sandbox/normalize-origin %))
                   (remove #(= % origin))
                   vec)))))

;; ---------------------------------------------------------------------------
;; External actions

(defn external-request
  [kind payload]
  (let [url (str (or (:url payload) ""))
        supported? (sandbox/http-url? url)
        filename (str (or (:filename payload) ""))
        data (:data payload)]
    (case kind
      :link {:kind :link
             :url url
             :supported? supported?
             :reason (when-not supported?
                       "只允许在浏览器中打开 http 或 https 地址。")}
      :download (let [data-url? (str/starts-with? (str/lower-case url) "data:")
                      data (or data (when data-url? url))]
                  {:kind :download
                   :url (when-not data-url? url)
                   :filename filename
                   :data data
                   :supported? supported?
                   :reason (when (and (not supported?) (not data))
                             "下载地址不是 http/https，且没有内联内容。")})
      :submit {:kind :submit
               :url url
               :method (str/upper-case (str (or (:method payload) "GET")))
               :fields (mapv (fn [field]
                               (let [value (or (:value field) (get field "value"))
                                     sensitive? (boolean (or (:sensitive? field)
                                                             (get field "sensitive")))]
                                 {:name (or (:name field) (get field "name") "字段")
                                  :value-length (count (str (or value "")))
                                  :sensitive? sensitive?}))
                             (or (:fields payload) []))
               :supported? supported?
               :reason (when-not supported?
                         "表单目标不是 http/https；沙箱不会自动提交。")}
      :model {:kind :model
              :reason "小应用不能直接调用模型；请在聊天输入区明确提出请求。"}
      {:kind (or kind :unknown)
       :reason "此动作需要用户确认。"})))

;; ---------------------------------------------------------------------------
;; UI state

(defn initial-state
  []
  {:apps []
   :loading? true
   :error nil
   :workspace nil
   :add-path ""
   :add-error nil
   :selected-id nil
   :app nil
   :source nil
   :status :idle
   :ready? false
   :runtime-error nil
   :fullscreen? false
   :compat nil
   :notice nil
   :data {:entries {} :summary (data-summary []) :snapshot {:data {} :omitted? false}}
   :versions []
   :network {:allowed-origins [] :pending-origins [] :online? (online?)}
   :requested-origins []
   :external nil
   :picker nil
   :relink nil
   :confirm-clear? false
   :show-data? false
   :using-old-version nil
   :data-too-large? false
   :force-open? false
   :restored-version? false
   :frame-epoch 0
   :session-grants []
   :checking? false})
