(ns hc.client.artifacts
  "Artifact cards, provenance and open/download actions.

  Root `AGENTS.md` §8.5 requires a file card to state the name, type,
  workspace/project, full path, size, modified time, save status and source
  task.  §8.6 requires a delivered artifact to have passed completeness and
  openability checks before it is announced.

  This namespace is pure presentation.  It never invents a download URL for a
  path that has no content ID, never treats a suggested filename as a real
  file, and never marks a missing blob as openable.  The server artifact map
  is the only source of truth."
  (:require [clojure.string :as str]))

(defn field
  "Read a field by keyword or its string form.  Server maps may use either."
  [m k]
  (if (map? m)
    (if (contains? m k)
      (get m k)
      (get m (if (keyword? k)
               (subs (str k) 1)
               (keyword k))))
    nil))

(defn artifact-id
  [artifact]
  (or (field artifact :id)
      (field artifact :artifact-id)))

(defn content-id
  [artifact]
  (field artifact :content-id))

(defn display-name
  [artifact]
  (or (field artifact :name)
      (field artifact :filename)
      (artifact-id artifact)
      "未命名产物"))

(defn- safe-ascii-name
  [name]
  (when (string? name)
    (let [base (-> name
                   (str/replace #"[^A-Za-z0-9._-]+" "_")
                   (str/replace #"^\.+" "")
                   (str/replace #"_+" "_")
                   (str/replace #"_+$" ""))]
      (when (seq base)
        (subs base 0 (min 128 (count base)))))))

(def content-id-pattern
  "The only content-ID form the managed blob store admits."
  #"^sha256-[0-9a-f]{64}$")

(defn canonical-content-id?
  "True only for `sha256-` plus 64 lowercase hexadecimal characters."
  [id]
  (and (string? id) (boolean (re-matches content-id-pattern id))))

(defn- canonical-blob-url?
  "True for the exact immutable blob path, optionally with a query string.

  A server-provided URL is trusted only when it still names this content ID on
  the same origin.  This keeps a stale or malicious `:download-url` from
  turning a real artifact card into a link to unrelated bytes."
  [url id]
  (let [base (str "/blobs/" id)]
    (and (string? url)
         (or (= url base)
             (and (str/starts-with? url (str base "?filename="))
                  (not (str/includes? url "#")))))))

(defn download-url
  "The independent blob URL for ARTIFACT, or nil when no content ID exists.

  A URL is never fabricated from a workspace path: only a finalized content ID
  names delivered bytes.  A missing or corrupt artifact keeps its content ID
  for provenance but has no usable download link."
  [artifact]
  (let [status (field artifact :status)
        id (content-id artifact)]
    (when (and (or (nil? status) (= :available status))
               (not (false? (field artifact :blob-present?)))
               (canonical-content-id? id))
      (let [provided (field artifact :download-url)]
        (if (canonical-blob-url? provided id)
          provided
          (let [base (str "/blobs/" id)
                safe (or (field artifact :download-filename)
                         (safe-ascii-name (field artifact :filename))
                         (safe-ascii-name (display-name artifact)))]
            (if safe (str base "?filename=" safe) base)))))))

(defn open-url
  [artifact]
  (when (and (or (nil? (field artifact :status))
                 (= :available (field artifact :status)))
             (not (false? (field artifact :blob-present?)))
             (canonical-content-id? (content-id artifact)))
    (let [provided (field artifact :open-url)
          id (content-id artifact)]
      (if (and (string? provided)
               (= provided (str "/blobs/" id)))
        provided
        ;; The gateway turns `?filename=` into Content-Disposition: attachment.
        ;; An open action must use the bare immutable URL so the browser can
        ;; display the bytes instead of downloading them.
        (str "/blobs/" id)))))

(defn- browser-preview-type?
  [media-type]
  (let [media-type (str/lower-case (str media-type))]
    (or (str/starts-with? media-type "text/")
        (str/starts-with? media-type "image/")
        (str/starts-with? media-type "audio/")
        (str/starts-with? media-type "video/")
        (contains? #{"application/pdf"
                     "application/json"
                     "application/xml"
                     "application/edn"}
                   media-type))))

(defn previewable?
  "True when the artifact is available and a browser can display its type.

  The server's `:previewable?`/`:openable?` flag wins when present; otherwise
  the media type is checked locally.  Availability is always required."
  [artifact]
  (let [explicit (or (field artifact :previewable?)
                     (field artifact :openable?))]
    (and (= :available (field artifact :status))
         (not (false? (field artifact :blob-present?)))
         (canonical-content-id? (content-id artifact))
         (if (some? explicit)
           (boolean explicit)
           (browser-preview-type? (field artifact :media-type))))))

(defn size-label
  [size]
  (when (number? size)
    (let [units ["B" "KiB" "MiB" "GiB" "TiB"]
          [value unit] (loop [value (double size) idx 0]
                         (if (or (< value 1024.0) (>= idx (dec (count units))))
                           [value (nth units idx)]
                           (recur (/ value 1024.0) (inc idx))))]
      (if (= unit "B")
        (str (long value) " B")
        (let [tenths (long (+ 0.5 (* value 10.0)))
              whole (quot tenths 10)
              fraction (rem tenths 10)]
          (str whole
               (when (pos? fraction) (str "." fraction))
               " " unit))))))

(def ^:private kind-labels
  {:file "文件"
   :log "日志"
   :binary "二进制"
   :export "导出包"
   :diagnostic "诊断包"
   :report "报告"})

(defn kind-label
  [kind]
  (or (get kind-labels kind)
      (when (keyword? kind) (name kind))
      (when (string? kind) kind)
      "类型未知"))

(def ^:private status-labels
  {:available "可用"
   :missing "内容缺失"
   :corrupt "内容损坏"
   :incomplete "缺少内容 ID"
   :rejected "已拒绝"
   :pending "待检查"})

(defn status-label
  [status]
  (or (get status-labels status)
      (when (keyword? status) (name status))
      "状态未知"))

(defn status-tone
  [status]
  (case status
    :available :ok
    :missing :error
    :corrupt :error
    :incomplete :error
    :rejected :warn
    :warn))

(def ^:private environment-labels
  {:instance "实例工作区"
   :instance-browser "实例内浏览器"
   :host-browser "主机浏览器"
   :host-desktop "主机桌面"
   :client-browser "用户本地浏览器"})

(defn environment-label
  [environment]
  (or (get environment-labels environment)
      (when (keyword? environment) (name environment))
      "环境未知"))

(defn saved?
  [artifact]
  (and (= :available (field artifact :status))
       (not (false? (field artifact :blob-present?)))
       (canonical-content-id? (content-id artifact))))

(defn- stamp-label
  [stamp]
  (when (map? stamp)
    (let [sec (field stamp :mtime-sec)
          nsec (field stamp :mtime-nsec)
          size (field stamp :size)]
      (when (or sec size)
        (str (or sec "?") "." (or nsec 0)
             " · " (or (size-label size) "大小未知"))))))

(defn provenance
  "Ordered `[label value code?]` rows for the provenance disclosure.

  Missing fields are omitted.  The card must not show a guessed source task or
  a guessed save location."
  [artifact]
  (let [origin (field artifact :origin)
        inputs (field artifact :inputs)
        checks (field artifact :checks)
        truncated? (boolean (field artifact :truncated?))
        status (field artifact :status)
        passed (count (filter #(true? (field % :ok?)) checks))
        total (count checks)]
    (vec
     (remove
      nil?
      [["来源任务" (field artifact :source-task-id) true]
       ["来源调用" (field artifact :source-call-id) true]
       ["所属项目" (field artifact :project-id) true]
       ["工作区" (or (field artifact :workspace-root)
                    (field origin :workspace-root)) true]
       ["完整路径" (or (field artifact :source-path)
                     (field origin :path)) true]
       ["相对路径" (or (field artifact :relative-path)
                     (field origin :relative-path)) true]
       ["环境" (environment-label (field artifact :environment)) false]
       ["保存状态" (if (saved? artifact)
                   "已保存到受管 Blob 区"
                   "未保存或内容缺失") false]
       ["完整性" (cond
                  (not= :available status) (status-label status)
                  truncated? "已截断：仅交付部分字节"
                  :else "完整") false]
       ["保存位置" (let [location (field artifact :save-location)]
                    (when (map? location)
                      (or (field location :content-id)
                          (field location :path)))) true]
       ["内容 ID" (content-id artifact) true]
       ["内容版本" (field artifact :version) true]
       ["存储版本" (field artifact :storage/version) true]
       ["注册时间" (field artifact :registered-at) false]
       ["原始文件" (stamp-label (or (field artifact :source-stamp)
                                   (field origin :stamp))) false]
       ["输入引用"
        (when (seq inputs)
          (str/join " · "
                    (map (fn [input]
                           (or (field input :path)
                               (field input :id)
                               (field input :content-id)
                               "未命名引用"))
                         inputs)))
        true]
       ["检查" (when (pos? total)
                 (str passed "/" total " 项通过")) false]
       ["检查项" (when (pos? total)
                  (str/join " · "
                            (map (fn [check]
                                   (str (name (or (field check :check) :check))
                                        "：" (if (true? (field check :ok?))
                                               "通过" "失败")))
                                 checks)))
        false]]))))

(defn actions
  "Real actions only.  A missing artifact has no download or open action; a
  path without a content ID never becomes a download link."
  [artifact]
  (let [available? (= :available (field artifact :status))
        url (download-url artifact)
        name (or (field artifact :filename) (display-name artifact))]
    (vec
     (remove
      nil?
      [(when (and available? url (previewable? artifact))
         {:action :open :label "打开" :url (open-url artifact)
          :target :new-tab :rel "noopener"})
       (when (and available? url)
         {:action :download :label "下载" :url url
          :filename name
          :download-filename (or (field artifact :download-filename)
                                 (safe-ascii-name name))})
       (when (canonical-content-id? (content-id artifact))
         {:action :copy-content-id
          :label "复制内容 ID"
          :value (content-id artifact)})]))))

(defn card
  "Build one display card.  All values come from the server artifact map."
  [artifact]
  (let [id (artifact-id artifact)
        declared-status (field artifact :status)
        status (cond
                 (and (= :available declared-status)
                      (not (canonical-content-id? (content-id artifact))))
                 :incomplete

                 (and (= :available declared-status)
                      (false? (field artifact :blob-present?)))
                 :missing

                 :else declared-status)
        origin (field artifact :origin)
        media-type (field artifact :media-type)]
    {:id id
     :name (display-name artifact)
     :filename (or (field artifact :filename) (display-name artifact))
     :kind (field artifact :kind)
     :kind-label (kind-label (field artifact :kind))
     :media-type media-type
     :type-label (or media-type (kind-label (field artifact :kind)))
     :size (field artifact :size)
     :size-label (size-label (field artifact :size))
     :status status
     :status-label (status-label status)
     :status-tone (status-tone status)
     :available? (= :available status)
     :saved? (saved? artifact)
     :environment (field artifact :environment)
     :environment-label (environment-label (field artifact :environment))
     :device (field artifact :device)
     :retractable? (boolean (field artifact :retractable?))
     :project-id (field artifact :project-id)
     :workspace-root (or (field artifact :workspace-root)
                         (field origin :workspace-root))
     :path (or (field artifact :source-path) (field origin :path))
     :relative-path (or (field artifact :relative-path)
                        (field origin :relative-path))
     :stamp (or (field artifact :source-stamp) (field origin :stamp))
     :modified-label (or (field artifact :source-modified-at)
                         (stamp-label (or (field artifact :source-stamp)
                                          (field origin :stamp))))
     :content-id (content-id artifact)
     :content-version (field artifact :version)
     :storage-version (field artifact :storage/version)
     :source-task-id (field artifact :source-task-id)
     :source-call-id (field artifact :source-call-id)
     :registered-at (field artifact :registered-at)
     :truncated? (boolean (field artifact :truncated?))
     :complete? (and (= :available status)
                     (not (false? (field artifact :blob-present?)))
                     (not (boolean (field artifact :truncated?)))
                     (if (some? (field artifact :complete?))
                       (boolean (field artifact :complete?))
                       true))
     :truncated-reason (field artifact :truncated-reason)
     :checks (field artifact :checks)
     :inputs (field artifact :inputs)
     :download-url (download-url artifact)
     :open-url (open-url artifact)
     :openable? (previewable? artifact)
     :actions (actions artifact)
     :provenance (provenance artifact)
     :missing-reason (when (and (not= :available status) status)
                       (or (field artifact :missing-reason)
                           (status-label status)))}))

(defn- table
  [db]
  (or (get db :artifact/by-id)
      (get db "artifact/by-id")
      (when-let [artifacts (or (get db :artifacts) (get db "artifacts"))]
        (into {} (keep (fn [a] (when-let [id (artifact-id a)] [id a]))) artifacts))
      {}))

(defn from-state
  "Project a Fulcro database or protocol tree into display cards.

  Both normalized `:artifact/by-id` tables and compact `:artifacts`
  collections are accepted.  A removed artifact cannot survive because the
  caller replaces the table; this function does not merge."
  [db]
  (->> (vals (table db))
       (keep (fn [artifact]
               (when (artifact-id artifact)
                 (card artifact))))
       (sort-by (comp str :name))
       vec))

(defn announcement-artifact
  "Extract the artifact from a `CUSTOM hc/artifact-available` event."
  [event]
  (let [type (or (field event :type) (field event :event))
        name (field event :name)
        value (or (field event :value) (field event :artifact))]
    (when (and (map? value)
               (or (= "CUSTOM" (str type))
                   (= :custom type))
               (= "hc/artifact-available" (str name)))
      value)))
