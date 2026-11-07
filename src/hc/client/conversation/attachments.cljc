(ns hc.client.conversation.attachments
  "Pure attachment queue for the conversation composer.

  Authority: root AGENTS.md §3.4, §6.7 and §7.4 旅程 6.6（多模态附件）.  The queue owns the
  cards before they become draft attachments:

  - at most 20 items, including failed and excluded items;
  - one locally imported file at most 100 MiB;
  - an explicit local-copy versus workspace-association distinction;
  - a visible preparation state (`:adding`, `:ready`, `:needs-choice`,
    `:unsupported`, `:failed`);
  - model compatibility that never treats an unknown capability as support;
  - a send summary that keeps failed items visible until the operator removes
    or excludes them.

  This namespace is pure CLJC.  It performs no upload, reads no file and never
  invents extracted text.  The workbench uploads bytes over the independent
  attachment HTTP route and feeds the resulting descriptor back through
  `mark-uploaded`; a real document extractor feeds `mark-extracted`."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Limits

(def max-attachments 20)
(def max-attachment-bytes (* 100 1024 1024))
(def default-limits {:max-items max-attachments
                     :max-bytes max-attachment-bytes
                     :max-pages nil})

(def statuses #{:adding :ready :needs-choice :unsupported :failed})

(def ^:private max-safe-int
  #?(:clj Long/MAX_VALUE
     :cljs js/Number.MAX_SAFE_INTEGER))

;; ---------------------------------------------------------------------------
;; Small helpers

(defn- keywordize
  [value]
  (cond
    (keyword? value) value
    (string? value) (keyword (str/lower-case value))
    :else value))

(defn normalize-name
  [value]
  (when (string? value)
    (let [base (last (str/split (str/replace value "\\" "/") #"/"))
          cleaned (-> (or base "")
                      (str/replace #"[\u0000-\u001F\u007F]" "")
                      str/trim)]
      (when-not (str/blank? cleaned)
        (subs cleaned 0 (min 255 (count cleaned)))))))

(def ^:private mime-pattern
  #"[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+")

(defn normalize-mime
  [value]
  (when (string? value)
    (let [value (-> value (str/split #";" 2) first str/trim str/lower-case)]
      (when (re-matches mime-pattern value) value))))

(defn mime->kind
  [mime]
  (let [mime (or (normalize-mime mime) "application/octet-stream")]
    (cond
      (str/starts-with? mime "image/") :image
      (= mime "application/pdf") :pdf
      (str/starts-with? mime "audio/") :audio
      (str/starts-with? mime "video/") :video
      (or (str/starts-with? mime "text/")
          (contains? #{"application/json" "application/ld+json"
                       "application/xml" "application/xhtml+xml"
                       "application/x-yaml" "application/yaml"
                       "application/toml" "application/x-sh"
                       "application/sql" "application/graphql"
                       "application/javascript" "application/ecmascript"
                       "application/x-ndjson" "application/csv"}
                     mime)
          (str/ends-with? mime "+json")
          (str/ends-with? mime "+xml"))
      :text
      (contains? #{"application/zip" "application/x-zip-compressed"
                   "application/x-rar-compressed" "application/x-7z-compressed"
                   "application/gzip" "application/x-tar"}
                 mime)
      :archive
      :else :file)))

(defn format-bytes
  [bytes]
  (when (and (number? bytes) (not (neg? bytes)))
    (let [units ["B" "KiB" "MiB" "GiB"]
          [value unit] (loop [value (double bytes) unit 0]
                         (if (or (< value 1024) (>= unit (dec (count units))))
                           [value (nth units unit)]
                           (recur (/ value 1024) (inc unit))))
          rounded #?(:clj (Math/round (* value 10.0))
                     :cljs (js/Math.round (* value 10.0)))]
      (str (if (zero? (mod rounded 10))
             (long (/ rounded 10))
             (/ rounded 10.0))
           " " unit))))

(defn canonical-blob-id?
  [id]
  (and (string? id)
       (boolean (re-matches #"^sha256-[0-9a-f]{64}$" id))))

(defn- local-copy?
  [item]
  (if (contains? item :local-copy?)
    (true? (:local-copy? item))
    (canonical-blob-id? (:blob-id item))))

(defn- valid-stamp?
  [stamp]
  (and (map? stamp)
       (integer? (:mtime-sec stamp))
       (integer? (:mtime-nsec stamp))
       (integer? (:size stamp))
       (not (neg? (:size stamp)))))

(defn valid-workspace?
  [workspace]
  (and (map? workspace)
       (string? (:root-id workspace))
       (not (str/blank? (:root-id workspace)))
       (string? (:relative-path workspace))
       (not (str/blank? (:relative-path workspace)))
       (valid-stamp? (:stamp workspace))))

;; ---------------------------------------------------------------------------
;; Model capabilities (display-side mirror of the server policy)

(def image-capabilities
  #{:vision :vision-input :image :images :image-input :image-upload
    :input-image :multimodal :multimodal-input :visual})

(def document-capabilities
  #{:document :documents :document-input :document-upload :pdf :pdf-input
    :file :files :file-input :file-upload :attachment})

(def audio-capabilities
  #{:audio :audio-input :audio-upload :speech :speech-input :transcription})

(def video-capabilities
  #{:video :video-input :video-upload :video-understanding})

(def text-capabilities
  #{:text :text-input})

(defn- capability-key
  [value]
  (cond
    (keyword? value) value
    (string? value) (keyword (str/lower-case (str/replace value #"^:" "")))
    (symbol? value) (keyword (name value))
    :else value))

(defn- truthy-capability?
  [value]
  (not (or (nil? value)
           (false? value)
           (contains? #{:unknown :unsupported :no :false :off :absent}
                      (capability-key value)))))

(defn model-capabilities
  [model]
  (let [model (or model {})
        nested (or (:model model) {})
        source (cond
                 (contains? model :capabilities) (:capabilities model)
                 (contains? nested :capabilities) (:capabilities nested)
                 (contains? model :input-types) (:input-types model)
                 (contains? nested :input-types) (:input-types nested)
                 (get-in model [:connection :capabilities])
                 (get-in model [:connection :capabilities])
                 :else nil)
        known? (some? source)
        values (cond
                 (nil? source) #{}
                 (set? source) source
                 (sequential? source) source
                 (map? source) (keep (fn [[k v]] (when (truthy-capability? v) k)) source)
                 :else #{})]
    {:known? known?
     :set (set (keep capability-key values))}))

(defn service-limits
  ([model] (service-limits model default-limits))
  ([model product]
   (let [product (merge default-limits (or product {}))
         declared (or (:attachment-limits model)
                      (:limits model)
                      (get-in model [:connection :attachment-limits])
                      (get-in model [:connection :limits])
                      {})
         positive (fn [value] (when (and (integer? value) (pos? value)) value))
         max-items (positive (:max-items declared))
         max-bytes (positive (:max-bytes declared))
         max-pages (positive (:max-pages declared))]
     {:max-items (min (:max-items product) (or max-items (:max-items product)))
      :max-bytes (min (:max-bytes product) (or max-bytes (:max-bytes product)))
      :max-pages (when (or max-pages (:max-pages product))
                   (min (or max-pages max-safe-int)
                        (or (:max-pages product) max-safe-int)))})))

(defn- extraction-ready?
  [item]
  (let [extraction (:extraction item)]
    (and (map? extraction)
         (contains? #{:ready :complete :completed} (keyword (:status extraction)))
         (string? (:text extraction))
         (not (str/blank? (:text extraction))))))

(defn- text-mime?
  [mime name]
  (let [mime (normalize-mime mime)
        kind (mime->kind mime)]
    (or (= :text kind)
        (and (contains? #{"application/octet-stream" nil} mime)
             (let [name (or name "")]
               (contains? #{"txt" "md" "json" "csv" "xml" "yaml" "yml"
                            "toml" "log" "clj" "cljs" "cljc" "edn" "js"
                            "ts" "py" "java" "c" "h" "rs" "go" "sql" "sh"}
                          (some-> (last (str/split name #"\.")) str/lower-case)))))))

(defn- native-capability
  [kind capabilities]
  (let [candidates (case kind
                     :image image-capabilities
                     :pdf document-capabilities
                     :audio audio-capabilities
                     :video video-capabilities
                     ;; Text travels as a text block, never as an opaque
                     ;; native attachment; the adapter rejects native text.
                     :text #{}
                     :archive document-capabilities
                     :file document-capabilities
                     #{})]
    (some #(when (contains? capabilities %) %) candidates)))

(defn- required-capability
  [kind]
  (case kind
    :image :vision
    :pdf :document
    :audio :audio
    :video :video
    :text :text
    :archive :document
    :file :document
    :file))

(defn compatibility
  "Display-side model compatibility.  The server re-checks this immediately
  before submission; this copy exists so the operator sees a repair path
  before uploading or sending.  An unknown capability is never a yes."
  [item model]
  (let [item (or item {})
        kind (or (get-in item [:media :kind]) (mime->kind (:mime item)))
        {:keys [known? set]} (model-capabilities model)
        limits (service-limits model)
        native (native-capability kind set)
        extracted? (extraction-ready? item)
        extractable? (or (text-mime? (:mime item) (:name item))
                         (boolean (:extractable? item)))
        text-ok? (or (empty? set)
                     (some #(contains? set %) text-capabilities))
        preferred (let [value (some-> (:preferred-route item) keyword)]
                    (when (contains? #{:native :text} value) value))
        extraction-failed? (contains? #{:failed :error :needs-service
                                        :needs-choice}
                                      (keyword (get-in item [:extraction :status])))
        base {:kind kind
              :capabilities-known? known?
              :native? (boolean native)
              :native-capability native
              :text-capable? (boolean text-ok?)
              :extractable? (boolean (or extractable? extracted?))
              :extraction-ready? (boolean extracted?)
              :preferred-route preferred
              :limits limits}]
    (cond
      (and (integer? (:size item)) (> (:size item) (:max-bytes limits)))
      (merge base {:route :unsupported
                   :send-route nil
                   :reason :service-size-limit
                   :detail "超过当前服务的附件大小限制"
                   :repairs [:select-model :remove]})

      (and (local-copy? item)
           (not (canonical-blob-id? (:blob-id item))))
      (merge base {:route :unsupported
                   :send-route nil
                   :reason :local-copy-missing
                   :detail "本地副本缺少可核对的内容 ID"
                   :repairs [:retry :remove]})

      (and (not (local-copy? item))
           (not (valid-workspace? (:workspace item))))
      (merge base {:route :needs-choice
                   :send-route nil
                   :reason :workspace-reference-unverified
                   :detail "外部关联文件尚未保存为本地副本"
                   :repairs [:snapshot :remove]})

      (and (not (local-copy? item))
           (valid-workspace? (:workspace item))
           (not (:workspace-verified? item)))
      (merge base {:route :needs-choice
                   :send-route nil
                   :reason :workspace-reference-unverified
                   :detail "外部关联文件尚未保存为本地副本"
                   :repairs [:snapshot :remove]})

      (and (= preferred :text)
           text-ok?
           (or extracted? (and (= kind :text) extractable?)))
      (merge base {:route (if extracted? :text :needs-choice)
                   :send-route (when extracted? :text)
                   :reason (if extracted? :preferred-text :extract-text)
                   :detail (if extracted?
                             "发送已确认的提取文本"
                             "已选择文本路线；请先提取文字")
                   :repairs [:extract-text :select-model :remove]})

      (and (= preferred :text)
           text-ok?
           extractable?
           (not extraction-failed?))
      (merge base {:route :needs-choice
                   :send-route nil
                   :reason :extract-text
                   :detail "已选择文本路线；请先提取文字"
                   :repairs [:extract-text :select-model :remove]})

      native
      (merge base {:route :native
                   :send-route :native
                   :reason :model-supports-native
                   :detail "将发送原文件"})

      (and (= kind :text) (or extracted? extractable?))
      (merge base {:route (if extracted? :text :needs-choice)
                   :send-route (when extracted? :text)
                   :reason (if extracted? :local-text :extract-text)
                   :detail (if extracted?
                             "发送已确认的提取文本"
                             "模型不接受原格式，可提取文字或换模型")
                   :repairs [:extract-text :select-model :remove]})

      (and extracted? text-ok?)
      (merge base {:route :text
                   :send-route :text
                   :reason :extracted-text
                   :detail "发送已确认的提取文本"
                   :repairs [:select-model :remove]})

      (not known?)
      (merge base {:route :unknown
                   :send-route nil
                   :reason :capability-unknown
                   :detail "尚未确认当前模型是否接受此类附件"
                   :repairs [:select-model :extract-text :remove]})

      (and extractable? text-ok?)
      (merge base {:route :needs-choice
                   :send-route nil
                   :reason :extract-text
                   :detail "模型不接受原格式，可提取文字或换模型"
                   :repairs [:extract-text :select-model :remove]})

      :else
      (merge base {:route :unsupported
                   :send-route nil
                   :reason :no-compatible-route
                   :detail "当前模型不接收该类附件，也没有可用的本地提取路线"
                   :repairs [:select-model :remove]}))))

;; ---------------------------------------------------------------------------
;; Queue state

(defn initial
  ([] (initial nil))
  ([model]
   {:items []
    :next-seq 0
    :limits default-limits
    :model model}))

(defn items
  [queue]
  (vec (or (:items queue) [])))

(defn total
  [queue]
  (count (items queue)))

(defn- limits
  [queue]
  (merge default-limits (:limits queue) (service-limits (:model queue))))

(defn- error
  [code message details]
  (cond-> {:code code :message message}
    (some? details) (assoc :details details)))

(defn- refused
  [queue code message details]
  {:ok? false :queue queue :error (error code message details)})

(defn- duplicate-of
  [items name size fingerprint last-modified]
  (some (fn [item]
          (when (and (= name (:name item))
                     (= size (:size item))
                     (or (and fingerprint (:fingerprint item)
                              (= fingerprint (:fingerprint item)))
                         (and (nil? fingerprint)
                              (nil? (:fingerprint item))
                              (some? last-modified)
                              (= last-modified (:last-modified item)))))
            (:id item)))
        items))

(defn add-file
  "Add one file card.  The item starts in `:adding`; it becomes `:ready`
  only after a real upload returns a descriptor.

  A duplicate is flagged but still added, because §3.4 requires an explicit
  choice rather than silently doubling or silently dropping the upload."
  [queue request]
  (let [queue (or queue (initial))
        name (normalize-name (:name request))
        size (:size request)
        mime (or (normalize-mime (:mime request)) "application/octet-stream")
        limits (limits queue)
        existing (items queue)]
    (cond
      (nil? name)
      (refused queue :ATTACHMENT_NAME_INVALID
               "附件需要可显示的的文件名" {:name (:name request)})

      (not (integer? size))
      (refused queue :ATTACHMENT_SIZE_INVALID
               "附件大小必须是字节数" {:size size})

      (neg? size)
      (refused queue :ATTACHMENT_SIZE_INVALID
               "附件大小不能为负数" {:size size})

      (> size (:max-bytes limits))
      (refused queue :ATTACHMENT_TOO_LARGE
               "单个附件最大 100 MiB"
               {:size size :limit (:max-bytes limits)})

      (>= (count existing) (:max-items limits))
      (refused queue :ATTACHMENT_LIMIT_EXCEEDED
               "单条草稿最多 20 个附件"
               {:count (count existing) :limit (:max-items limits)})

      :else
      (let [seq-number (long (or (:next-seq queue) 0))
            existing-ids (set (map :id existing))
            [id next-seq]
            (loop [n (inc seq-number)]
              (let [candidate (str "att-" n)]
                (if (contains? existing-ids candidate)
                  (recur (inc n))
                  [candidate n])))
            duplicate (duplicate-of existing name size
                                    (:fingerprint request) (:last-modified request))
            item {:id id
                  :attachment-id id
                  :name name
                  :mime mime
                  :size size
                  :source (or (:source request) :local-import)
                  :status :adding
                  :ready? false
                  :excluded? false
                  :local-copy? false
                  :blob-id nil
                  :fingerprint (:fingerprint request)
                  :last-modified (:last-modified request)
                  :created-at (:at request)
                  :detail "正在加入"}
            queue' (-> queue
                       (update :items (fnil conj []) item)
                       (assoc :next-seq next-seq))]
        {:ok? true
         :queue queue'
         :item item
         :duplicate-of duplicate
         :summary {:total (total queue')
                   :blocked 1
                   :message (str "已加入 " (total queue') " 个，1 个需要处理")}}))))

(defn add-files
  "Add several files in order, keeping every refusal visible."
  [queue requests]
  (loop [queue (or queue (initial))
         remaining (seq requests)
         added []
         refused []]
    (if-not remaining
      {:ok? (boolean (seq added))
       :queue queue
       :added added
       :refused refused
       :summary {:total (total queue)
                 :added (count added)
                 :refused (count refused)
                 :blocked (count (filter #(not (:ready? %)) (items queue)))
                 :message (let [blocked (+ (count refused)
                                           (count (filter #(not (:ready? %))
                                                          added)))]
                            (if (pos? blocked)
                              (str "已加入 " (count added) " 个，"
                                   blocked " 个需要处理")
                              (str "已加入 " (count added) " 个")))}}
      (let [result (add-file queue (first remaining))]
        (if (:ok? result)
          (recur (:queue result) (next remaining)
                 (conj added (:item result)) refused)
          (recur queue (next remaining) added
                 (conj refused (assoc (:error result)
                                      :request (first remaining)))))))))

(defn- map-item
  [queue item-id f]
  (update queue :items
          (fn [items]
            (mapv (fn [item]
                    (if (= item-id (:id item)) (f item) item))
                  items))))

(defn update-item
  [queue item-id changes]
  (map-item queue item-id #(merge % changes)))

(defn mark-uploaded
  "Adopt a real ingestion descriptor.  The descriptor must carry a canonical
  local-copy blob ID; a client-side placeholder is not ready."
  [queue item-id descriptor]
  (map-item queue item-id
            (fn [item]
              (let [descriptor (or descriptor {})
                    blob-id (:blob-id descriptor)
                    local? (and (true? (:local-copy? descriptor))
                                (canonical-blob-id? blob-id))
                    descriptor-status (keyword (:status descriptor))
                    failed? (contains? #{:failed :unsupported :needs-choice}
                                       descriptor-status)]
                (merge item
                       descriptor
                       {:id (:id item)
                        :attachment-id (:id item)
                        :name (or (normalize-name (:name descriptor)) (:name item))
                        :mime (or (normalize-mime (:mime descriptor)) (:mime item))
                        :size (or (:size descriptor) (:size item))
                        :local-copy? (boolean local?)
                        :status (cond failed? descriptor-status
                                      (and local? (= :ready descriptor-status)) :ready
                                      local? :needs-choice
                                      :else :failed)
                        :ready? (and local? (= :ready descriptor-status))
                        :excluded? (boolean (:excluded? item))
                        :detail (cond failed? "准备失败"
                                      local? "已保存应用内副本"
                                      :else "缺少应用内副本")})))))

(defn mark-failed
  [queue item-id reason]
  (map-item queue item-id
            #(assoc % :status :failed
                    :ready? false
                    :detail (or (:message reason) "准备失败")
                    :error reason)))

(defn mark-extracted
  "Attach a real extractor result.  Empty or failed extraction is not a
  success; the item stays blocked with a visible reason."
  [queue item-id extraction]
  (map-item queue item-id
            (fn [item]
              (let [status (keyword (:status extraction))
                    ready? (and (= :ready status)
                                (string? (:text extraction))
                                (not (str/blank? (:text extraction))))
                    local? (or (true? (:local-copy? item))
                               (canonical-blob-id? (:blob-id item)))]
                (assoc item
                       :extraction (assoc extraction
                                         :status (if ready? :ready :failed))
                       :extractable? true
                       :status (cond ready? :ready
                                     (= :needs-service status) :needs-choice
                                     :else :failed)
                       :ready? (and ready?
                                    local?
                                    (not (:excluded? item)))
                       :detail (cond ready? "已提取文字，待核对"
                                     (= :needs-service status)
                                     "需要连接真实的文字识别服务"
                                     :else "文字提取失败"))))))

(defn remove-item
  "Remove a card from the draft.  The operator's original file and any
  already-sent message attachment are untouched.  A tombstone prevents a
  stale draft snapshot from resurrecting the removed card."
  [queue item-id]
  (-> queue
      (update :items
              (fn [items]
                (filterv #(not= item-id (:id %)) items)))
      (update :tombstones (fnil conj #{}) item-id)))

(defn exclude
  "Explicitly exclude a blocked item so the remaining ready items may send."
  [queue item-id]
  (map-item queue item-id #(assoc % :excluded? true :ready? false)))

(defn include
  [queue item-id]
  (map-item queue item-id #(assoc % :excluded? false)))

(defn retry
  [queue item-id]
  (map-item queue item-id
            #(-> %
                 (assoc :status :adding
                        :ready? false
                        :detail "正在重试"
                        :error nil)
                 (dissoc :excluded?))))

(defn apply-model
  "Re-evaluate every card against the selected model.  Existing local copies
  and extraction results are preserved; only the route and readiness change."
  [queue model]
  (let [queue (assoc queue :model model)]
    (-> queue
        (update :items
                (fn [items]
                  (mapv (fn [item]
                          (if (contains? #{:adding :failed} (:status item))
                            item
                            (let [compatibility (compatibility item model)
                                  route (:route compatibility)
                                  excluded? (true? (:excluded? item))
                                  ready? (and (not excluded?)
                                              (contains? #{:native :text} route))
                                  status (cond
                                           excluded? :ready
                                           ready? :ready
                                           (= route :unsupported) :unsupported
                                           (= route :needs-choice) :needs-choice
                                           (= route :unknown) :needs-choice
                                           (= route :text) :ready
                                           :else :needs-choice)]
                              (assoc item
                                     :compatibility compatibility
                                     :route route
                                     :status status
                                     :ready? ready?
                                     :detail (:detail compatibility)
                                     :send-route (:send-route compatibility)))))
                        (or items []))))
        (assoc :model model))))

;; ---------------------------------------------------------------------------
;; Presentation

(defn status-label
  [item]
  (cond
    (:excluded? item) "已排除"
    (= :adding (keyword (:status item))) "准备中"
    (= :ready (keyword (:status item))) "可发送"
    (= :needs-choice (keyword (:status item))) "需要选择处理方式"
    (= :unsupported (keyword (:status item))) "不支持"
    (= :failed (keyword (:status item))) "准备失败"
    :else "准备中"))

(defn route-label
  [item]
  (case (keyword (or (:route item) (:send-route item)))
    :native "发送原文件"
    :text "发送提取文本"
    :needs-choice "等待选择处理方式"
    :unsupported "无法发送"
    :unknown "尚未确认"
    nil))

(defn- partial-pdf?
  "A PDF that already has some text but still has no-text/error pages.
  The operator must be able to OCR those pages without losing the text route."
  [item]
  (and (= :pdf (mime->kind (:mime item)))
       (or (some #(contains? #{:no-text :error} (keyword (:status %)))
                 (get-in item [:extraction :pages]))
           (some #(= :PAGES_WITHOUT_TEXT (:code %))
                 (get-in item [:extraction :warnings])))))

(defn extraction-pages
  "Per-page extraction results, if the extractor reported any."
  [attachment]
  (let [pages (get-in attachment [:extraction :pages])]
    (if (sequential? pages) (vec pages) [])))

(defn unreadable-pages
  "Original page numbers whose extraction produced no text or failed.

  Both the per-page results and the extractor warnings are read, because a
  failed page may appear only in one of them.  Page numbers are never
  rebased."
  [attachment]
  (let [from-pages (->> (extraction-pages attachment)
                        (filter #(contains? #{:no-text :error}
                                            (keyword (:status %))))
                        (keep :page))
        from-warnings (->> (get-in attachment [:extraction :warnings])
                           (filter #(contains? #{:PAGES_WITHOUT_TEXT
                                                 :PAGES_UNREADABLE}
                                               (keyword (:code %))))
                           (mapcat :pages))]
    (->> (concat from-pages from-warnings)
         (filter #(and (integer? %) (pos? %)))
         distinct
         sort
         vec)))

(defn selected-pages
  [attachment]
  (let [selected (get-in attachment [:extraction :selected-pages])]
    (when (and (sequential? selected) (seq selected))
      (vec selected))))

(defn page-label
  "Original page range for a PDF selection, e.g. `第 2–5 页，共 4 页`.

  Page numbers stay the document's own numbers; a four-page selection never
  becomes 1–4."
  [attachment]
  (when-let [pages (selected-pages attachment)]
    (let [first-page (first pages)
          last-page (last pages)
          contiguous? (= pages (vec (range first-page (inc last-page))))]
      (str "第 " (if contiguous?
                   (str first-page "–" last-page)
                   (str/join "," pages))
           " 页，共 " (count pages) " 页"))))

(defn unreadable-label
  [attachment]
  (let [pages (unreadable-pages attachment)]
    (when (seq pages)
      (str "不可读：" (str/join "、" (map #(str "第 " % " 页") pages))))))

(defn copy-label
  [attachment]
  (cond
    (local-copy? attachment) "已保存副本"
    (valid-workspace? (:workspace attachment)) "外部引用"
    :else nil))

(defn workspace-label
  "Relocation guidance for a workspace association.  A local copy never
  depends on the original file, so it gets no relocation prompt."
  [attachment]
  (let [workspace (:workspace attachment)]
    (when (and (not (local-copy? attachment))
               (valid-workspace? workspace))
      (str "外部引用 · " (:relative-path workspace)
           " · 打开前请确认原文件仍在该位置；找不到时请重新定位"))))

(defn attachment-facts
  "Readable page and provenance facts shared by draft cards and history
  attachment rows.  It reads only the attachment map; it never invents a
  page or a copy that the extractor did not report.

  Page facts are shown only after the operator chose an extraction route
  (`:preferred-route`) or after the attachment was sent.  An upload-time text
  probe of a scanned PDF is not a user choice: showing every page as
  unreadable before the operator picks OCR would claim a result that was
  never requested."
  ([attachment] (attachment-facts attachment true))
  ([attachment show-pages?]
   {:page-label (when show-pages? (page-label attachment))
    :unreadable-pages (if show-pages? (unreadable-pages attachment) [])
    :unreadable-label (when show-pages? (unreadable-label attachment))
    :copy-label (copy-label attachment)
    :workspace-label (workspace-label attachment)}))

(defn card
  [item]
  (let [kind (or (get-in item [:media :kind]) (mime->kind (:mime item)))
        image? (= kind :image)
        pdf? (= kind :pdf)
        ready? (boolean (:ready? item))
        status (keyword (:status item))
        excluded? (boolean (:excluded? item))
        blob-id (:blob-id item)
        preview-id (or (get-in item [:prepared :blob-id]) blob-id)
        suggested-route (keyword (or (get-in item [:extraction :suggested-route])
                                     (get-in item [:extraction :error :suggested-route])))
        text-route? (and (:extractable? item)
                         (text-mime? (:mime item) (:name item)))
        can-extract? (and (:local-copy? item)
                          (not (:excluded? item))
                          (or (not (extraction-ready? item))
                              (partial-pdf? item))
                          (or (contains? #{:ocr :text} suggested-route)
                              text-route? pdf?))
        extract-route (or suggested-route
                          (when text-route? :text)
                          :ocr)
        preferred (:preferred-route item)
        send-route (keyword (:send-route item))
        native? (boolean (get-in item [:compatibility :native?]))
        text-capable? (boolean (get-in item [:compatibility :text-capable?]))
        can-switch-text? (and (extraction-ready? item)
                              text-capable?
                              (not= :text send-route))
        can-switch-native? (and native? (not= :native send-route))
        name (or (:name item) "附件")]
    (merge
     {:id (:id item)
      :name name
     :mime (:mime item)
     :kind kind
     :size (:size item)
     :size-label (format-bytes (:size item))
     :status status
     :status-label (status-label item)
     :route (:route item)
     :route-label (route-label item)
     :preferred-route preferred
     :detail (or (:detail item)
                 (:message (:error item)))
     :ready? ready?
     :excluded? excluded?
     :local-copy? (boolean (:local-copy? item))
     :blob-id blob-id
     :workspace? (boolean (:workspace item))
     :thumbnail? image?
     :preview-url (when (and image? (canonical-blob-id? preview-id))
                    (str "/blobs/" preview-id))
     :can-retry? (contains? #{:failed :unsupported} status)
     :can-extract? can-extract?
     :extract-route extract-route
     :extract-label (str "提取附件 " name " 的文字")
     :can-switch-text? can-switch-text?
     :switch-text-label (str "改用提取文本 " name)
     :can-switch-native? can-switch-native?
     :switch-native-label (str "发送原文件 " name)
     :can-exclude? (and (not ready?) (not excluded?))
     :can-remove? true
     :retry-label (str "重试附件 " name)
     :exclude-label (str "排除附件 " name)
     :remove-label (str "移除附件 " name)
      :aria-label (str name "，" (status-label item)
                      (when-let [detail (:detail item)]
                        (str "，" detail)))}
     (attachment-facts item (boolean (or (:preferred-route item)
                                          (:user-extraction? item)))))))

(defn cards
  [queue]
  (mapv card (items queue)))

(defn active-items
  "Items that would travel with the next send.  Excluded items stay visible
  in the draft but do not travel."
  [queue]
  (filterv #(not (:excluded? %)) (items queue)))

(defn blocking-items
  [queue]
  (filterv #(and (not (:excluded? %)) (not (:ready? %)))
           (items queue)))

(defn send-summary
  "Summarize the queue for the send button and the batch-add notice."
  [queue]
  (let [all (items queue)
        active (active-items queue)
        blocked (blocking-items queue)
        ready (filterv :ready? active)
        total (count all)
        issue-count (count blocked)]
    {:total total
     :active (count active)
     :ready (count ready)
     :blocked issue-count
     :excluded (count (filter :excluded? all))
     :issue-count issue-count
     :has-ready? (pos? (count ready))
     :sendable? (zero? issue-count)
     :message (when (pos? issue-count)
                (str "已加入 " total " 个，" issue-count " 个需要处理"))
     :sendable-items (mapv (fn [item]
                             (cond-> (select-keys item [:id :name :mime :size
                                                        :blob-id :send-route
                                                        :local-copy? :workspace])
                               (= :text (keyword (:send-route item)))
                               (assoc :send-text (get-in item [:extraction :text]))))
                           ready)
     :blocked-items (mapv #(select-keys % [:id :name :status :detail :route])
                          blocked)}))

(defn snapshot
  "Draft attachments produced by the queue.  Only active items are included;
  the caller may still send text alone when the queue is empty."
  [queue]
  (mapv (fn [item]
          (cond-> (-> item
                      (select-keys [:id :attachment-id :name :mime :size :blob-id
                                    :local-copy? :workspace :extraction :media
                                    :prepared :preferred-route :send-route
                                    :send-text :excluded? :url])
                      (assoc :status (:status item)
                             :ready? (:ready? item)))
            (= :text (keyword (:send-route item)))
            (assoc :send-text (get-in item [:extraction :text]))))
        (active-items queue)))

(defn merge-items
  "Merge upload-pipeline status updates into the latest queue by item id.

  Existing items keep newer confirmed extraction results; incoming items that
  are not present yet are appended.  Items absent from INCOMING are never
  removed, so a concurrent removal or a newer local action is not resurrected
  or overwritten by a stale upload callback."
  [base incoming]
  (let [incoming-items (vec (or (:items incoming) []))
        incoming-by-id (into {} (map (juxt :id identity)) incoming-items)
        base-items (vec (or (:items base) []))
        base-ids (set (map :id base-items))
        merge-item (fn [item]
                     (if-let [next (get incoming-by-id (:id item))]
                       (let [base-status (keyword (:status item))
                             pending? (contains? #{:adding :uploading :preparing}
                                                 base-status)
                             old-extraction (:extraction item)
                             new-extraction (:extraction next)
                             keep-old-extraction?
                             (and (map? old-extraction)
                                  (or (nil? new-extraction)
                                      (and (= :ready (keyword (:status old-extraction)))
                                           (not= :ready (keyword (:status new-extraction))))))]
                         ;; Only an in-flight item may adopt the upload
                         ;; pipeline's status/route fields.  Once the card has
                         ;; a local decision (ready, excluded, model route,
                         ;; failed), a stale upload callback may add no field
                         ;; except a newer confirmed extraction.
                         (cond-> (if pending?
                                   (merge item (dissoc next :extraction))
                                   item)
                           keep-old-extraction? (assoc :extraction old-extraction)
                           (not keep-old-extraction?) (assoc :extraction new-extraction)))
                       item))
        updated (mapv merge-item base-items)
        tombstones (set (:tombstones base))
        added (filterv #(and (not (contains? base-ids (:id %)))
                             (not (contains? tombstones (:id %))))
                       incoming-items)]
    (assoc base
           :items (vec (concat updated added))
           :next-seq (max (long (or (:next-seq base) 0))
                          (long (or (:next-seq incoming) 0))))))

(defn clear-sent
  "Remove exactly the items captured by a successful send.

  Items added while the command was in flight stay in the queue.  This is
  called only after the server draft is observably cleared; a rejected send
  keeps every card so the operator can repair or resend it."
  [queue attachment-ids]
  (let [ids (set (or attachment-ids []))]
    (-> queue
        (update :items
                (fn [items]
                  (filterv #(not (contains? ids (:id %))) (or items []))))
        (update :tombstones (fnil into #{}) ids))))

(defn from-draft
  "Adopt existing draft attachments into the queue without inventing readiness.

  Workbench should call this once when opening a session whose draft already
  has attachments.  Unknown statuses stay blocked; a missing local copy is not
  promoted to ready."
  [draft]
  (let [items (vec (or (:attachments draft) []))
        normalized (mapv (fn [[index attachment]]
                           (let [id (or (:id attachment)
                                        (:attachment-id attachment)
                                        (str "att-" (inc index)))
                                 status (keyword (or (:status attachment)
                                                     (when (:ready? attachment) :ready)
                                                     :needs-choice))
                                 local? (and (local-copy? attachment)
                                             (canonical-blob-id? (:blob-id attachment)))
                                 ready? (and local?
                                             (= :ready status)
                                             (not (false? (:ready? attachment)))
                                             (not (:excluded? attachment)))]
                             (merge attachment
                                    {:id id
                                     :attachment-id id
                                     :name (or (normalize-name (:name attachment))
                                               (:name attachment)
                                               "附件")
                                     :mime (or (normalize-mime (:mime attachment))
                                               "application/octet-stream")
                                     :size (:size attachment)
                                     :status status
                                     :ready? ready?
                                     :excluded? (boolean (:excluded? attachment))
                                     :local-copy? (boolean local?)})))
                         (map-indexed vector items))]
    {:items normalized
     :next-seq (reduce (fn [highest item]
                         (let [suffix (second (re-matches #"att-(\d+)"
                                                          (str (:id item))))]
                           (max highest
                                (if suffix
                                  #?(:clj (Long/parseLong suffix)
                                     :cljs (js/parseInt suffix 10))
                                  0))))
                       0 normalized)
     :limits default-limits
     :tombstones #{}
     :model nil}))

(defn merge-draft
  "Adopt draft attachments that this queue has never seen and has not removed.

  Tombstones prevent a removed or already-sent attachment from returning when
  a stale draft snapshot or another client's draft still lists it."
  [queue draft]
  (let [draft-queue (from-draft draft)
        tombstones (set (:tombstones queue))
        existing (set (map :id (:items queue)))
        additions (->> (:items draft-queue)
                       (remove #(or (contains? existing (:id %))
                                    (contains? tombstones (:id %))))
                       vec)]
    (-> queue
        (update :items into additions)
        (assoc :next-seq (max (long (or (:next-seq queue) 0))
                              (long (or (:next-seq draft-queue) 0)))))))
