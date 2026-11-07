(ns hc.server.attachments.model
  "Pure attachment policy: limits, descriptors, model compatibility, and
  send preparation.

  Authority: root AGENTS.md §3.4, §6.7 and §7.4 旅程 6.6（多模态附件）.  A draft holds at
  most 20 attachments and one imported file is at most 100 MiB.  Those are
  product limits, not storage-engine limits; the managed blob store accepts
  larger objects, so every boundary that accepts operator input must check
  them explicitly.

  This namespace performs no I/O and reads no clock.  Ingestion supplies the
  bytes and identity; this namespace decides whether a descriptor is honest,
  whether the selected model can accept it natively, and what the operator
  must choose when it cannot.

  Compatibility is deliberately conservative.  An unknown capability is not
  a yes.  A model that does not declare image input cannot receive an image
  just because a file extension looks familiar.  Text extraction is only a
  route when a real extractor produced text; this namespace never invents
  OCR output."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Product limits

(def max-attachments
  "A single draft supports at most this many attachments, including failed
  and excluded items.  Failed items keep their place until removed."
  20)

(def max-attachment-bytes
  "A single locally imported file may be at most 100 MiB."
  (* 100 1024 1024))

(def default-limits
  {:max-items max-attachments
   :max-bytes max-attachment-bytes
   :max-pages nil})

(def statuses
  "Card states from §3.4.  `:adding` is an in-flight local copy, not yet a
  claim that the file is ready."
  #{:adding :ready :needs-choice :unsupported :failed})

(def terminal-statuses #{:ready :needs-choice :unsupported :failed})

;; ---------------------------------------------------------------------------
;; Names and MIME types

(defn normalize-name
  "Return a safe display name, or nil when nothing usable remains.

  Path separators are removed because an imported attachment is identified by
  its display name, not by a client-supplied path.  Control characters are
  removed; the result is capped at 255 characters."
  [value]
  (when (string? value)
    (let [base (last (str/split (str/replace value "\\" "/") #"/"))
          cleaned (-> (or base "")
                      (str/replace #"[\u0000-\u001F\u007F]" "")
                      (str/trim))]
      (when-not (str/blank? cleaned)
        (subs cleaned 0 (min 255 (count cleaned)))))))

(def ^:private mime-pattern
  #"[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+")

(defn normalize-mime
  "Lowercase a media type and drop parameters.  Invalid values become nil."
  [value]
  (when (string? value)
    (let [value (-> value
                    (str/split #";" 2)
                    first
                    str/trim
                    str/lower-case)]
      (when (re-matches mime-pattern value)
        value))))

(defn mime->kind
  "Classify a media type into the route family used by the interface."
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

(def ^:private text-extensions
  #{"txt" "md" "markdown" "json" "jsonl" "ndjson" "xml" "csv" "tsv"
    "log" "yaml" "yml" "toml" "ini" "cfg" "conf" "sql" "graphql"
    "clj" "cljs" "cljc" "edn" "js" "mjs" "cjs" "ts" "tsx" "jsx"
    "py" "rb" "java" "kt" "c" "h" "cc" "cpp" "hpp" "rs" "go" "php"
    "sh" "bash" "zsh" "fish" "html" "htm" "css" "scss" "less" "svg"})

(defn extension
  [name]
  (when-let [name (normalize-name name)]
    (let [index (str/last-index-of name ".")]
      (when (and index (< index (dec (count name))))
        (str/lower-case (subs name (inc index)))))))

(defn text-mime?
  "True for media types whose bytes are genuinely text.  RTF, Word, PDF and
  images are not text: extracting them needs a real document extractor."
  [mime name]
  (let [mime (normalize-mime mime)
        kind (mime->kind mime)]
    (or (= :text kind)
        (and (contains? #{"application/octet-stream" nil} mime)
             (contains? text-extensions (extension name))))))

(defn text-extractable?
  "True when a local UTF-8 read is an honest extraction route.  This says
  nothing about PDFs, scans, images, audio or video; those need a real
  extractor or OCR service."
  [mime name]
  (text-mime? mime name))

;; ---------------------------------------------------------------------------
;; Descriptor validation

(def ^:private blob-id-pattern #"^sha256-[0-9a-f]{64}$")

(defn canonical-blob-id?
  [id]
  (and (string? id) (boolean (re-matches blob-id-pattern id))))

(defn local-copy?
  "True when the attachment is backed by an application-owned blob.

  A descriptor that predates the explicit `:local-copy?` flag is accepted when
  it already names a canonical blob ID; a descriptor that explicitly says
  `false` stays an association."
  [attachment]
  (if (contains? attachment :local-copy?)
    (true? (:local-copy? attachment))
    (canonical-blob-id? (:blob-id attachment))))

(defn- non-blank-string?
  [value]
  (and (string? value) (not (str/blank? value))))

(defn valid-stamp?
  [stamp]
  (and (map? stamp)
       (integer? (:mtime-sec stamp))
       (integer? (:mtime-nsec stamp))
       (integer? (:size stamp))
       (not (neg? (:size stamp)))))

(defn valid-workspace?
  [workspace]
  (and (map? workspace)
       (non-blank-string? (:root-id workspace))
       (non-blank-string? (:relative-path workspace))
       (valid-stamp? (:stamp workspace))))

(defn descriptor-issues
  "Return structural problems with one attachment descriptor.

  A local copy must name a canonical blob ID.  A workspace association must
  name a root, a relative path and a captured stamp; it must not pretend to be
  a local copy."
  [attachment]
  (let [attachment (or attachment {})
        id (or (:attachment-id attachment) (:id attachment))
        size (:size attachment)
        local? (local-copy? attachment)
        workspace (:workspace attachment)]
    (cond-> []
      (not (non-blank-string? id))
      (conj {:code :ATTACHMENT_ID_INVALID
             :message "attachment needs a non-blank attachment-id"})

      (nil? (normalize-name (:name attachment)))
      (conj {:code :ATTACHMENT_NAME_INVALID
             :message "attachment needs a usable display name"})

      (not (integer? size))
      (conj {:code :ATTACHMENT_SIZE_INVALID
             :message "attachment size must be an integer byte count"})

      (and (integer? size) (neg? size))
      (conj {:code :ATTACHMENT_SIZE_INVALID
             :message "attachment size cannot be negative"})

      (and (integer? size) (> size max-attachment-bytes))
      (conj {:code :ATTACHMENT_TOO_LARGE
             :message "a single attachment may be at most 100 MiB"
             :details {:size size :limit max-attachment-bytes}})

      (and local? (not (canonical-blob-id? (:blob-id attachment))))
      (conj {:code :ATTACHMENT_BLOB_ID_INVALID
             :message "a local copy needs a canonical sha256 blob ID"})

      (and (not local?) (not (valid-workspace? workspace)))
      (conj {:code :ATTACHMENT_SOURCE_INVALID
             :message "an associated file needs a workspace root, relative path and stamp"})

      (and (contains? attachment :status)
           (not (contains? statuses (keyword (:status attachment)))))
      (conj {:code :ATTACHMENT_STATUS_INVALID
             :message "attachment status is not one of the supported card states"})

      (and (contains? attachment :extraction)
           (some? (:extraction attachment))
           (not (map? (:extraction attachment))))
      (conj {:code :ATTACHMENT_EXTRACTION_INVALID
             :message "extraction must be a map when present"})

      (and (map? (:extraction attachment))
           (= :ready (keyword (get-in attachment [:extraction :status])))
           (let [source-id (get-in attachment [:extraction :source :id])]
             (and (string? source-id)
                  (not= source-id (:blob-id attachment)))))
      (conj {:code :ATTACHMENT_EXTRACTION_SOURCE_MISMATCH
             :message "extraction source does not match the attachment blob"})

      (and (contains? attachment :preferred-route)
           (some? (:preferred-route attachment))
           (not (contains? #{:native :text} (:preferred-route attachment))))
      (conj {:code :ATTACHMENT_ROUTE_INVALID
             :message "preferred-route must be :native or :text"}))))

;; ---------------------------------------------------------------------------
;; Service limits

(defn- positive-int
  [value]
  (when (and (integer? value) (pos? value)) value))

(defn service-limits
  "Resolve effective attachment limits for one model or connection.

  The product limits are ceilings.  A service may be stricter, never looser.
  A missing value inherits the product default.  Values are read from
  `:attachment-limits` or `:limits` on the model or its connection."
  ([model] (service-limits model default-limits))
  ([model product]
   (let [product (merge default-limits (or product {}))
         declared (or (:attachment-limits model)
                      (:limits model)
                      (get-in model [:connection :attachment-limits])
                      (get-in model [:connection :limits])
                      {})
         max-items (positive-int (:max-items declared))
         max-bytes (positive-int (:max-bytes declared))
         max-pages (positive-int (:max-pages declared))]
     {:max-items (min (:max-items product) (or max-items (:max-items product)))
      :max-bytes (min (:max-bytes product) (or max-bytes (:max-bytes product)))
      :max-pages (when (or max-pages (:max-pages product))
                   (min (or max-pages Long/MAX_VALUE)
                        (or (:max-pages product) Long/MAX_VALUE)))})))

(defn limit-issues
  "Check one attachment against effective limits."
  [attachment limits]
  (let [limits (merge default-limits (or limits {}))
        size (:size attachment)
        selected-pages (get-in attachment [:extraction :selected-pages])
        pages (if (sequential? selected-pages)
                (count selected-pages)
                (or (get-in attachment [:extraction :page-count])
                    (get-in attachment [:media :page-count])))]
    (cond-> []
      (and (integer? size) (> size (:max-bytes limits)))
      (conj {:code :ATTACHMENT_SERVICE_SIZE_LIMIT
             :message "this service accepts a smaller file"
             :details {:size size
                       :limit (:max-bytes limits)
                       :limit-source :service}})

      (and (integer? pages)
           (:max-pages limits)
           (> pages (:max-pages limits)))
      (conj {:code :ATTACHMENT_SERVICE_PAGE_LIMIT
             :message "this service accepts fewer pages"
             :details {:pages pages
                       :limit (:max-pages limits)
                       :limit-source :service}}))))

;; ---------------------------------------------------------------------------
;; Model capabilities and compatibility

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
  "Return `{:known? bool :set #{...}}` for a model or connection map.

  `:known?` is false when the map has no capability declaration at all.
  An explicitly empty set is known and means no declared input capabilities.
  Nested `:model`, `:input-types` and `:capabilities` maps are accepted so
  the same policy works for the server configuration and a client snapshot."
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

(defn- declared-capability
  [capabilities candidates]
  (some #(when (contains? capabilities %) %) candidates))

(defn- extraction-ready?
  [attachment]
  (let [extraction (:extraction attachment)]
    (and (map? extraction)
         (contains? #{:ready :complete :completed} (keyword (:status extraction)))
         (string? (:text extraction))
         (not (str/blank? (:text extraction))))))

(defn- extraction-failed?
  [attachment]
  (contains? #{:failed :error :needs-service :needs-choice}
             (keyword (get-in attachment [:extraction :status]))))

(defn- local-text?
  "Only a real extractor result is a sendable text route.  A local preview is
  not confirmed text and must not travel as if it were."
  [attachment]
  (extraction-ready? attachment))

(defn- text-capable?
  [capabilities]
  (or (empty? capabilities)
      (some #(contains? capabilities %) text-capabilities)))

(defn- native-capability
  [kind capabilities]
  (case kind
    :image (declared-capability capabilities image-capabilities)
    :pdf (declared-capability capabilities document-capabilities)
    :audio (declared-capability capabilities audio-capabilities)
    :video (declared-capability capabilities video-capabilities)
    ;; A text file is not an opaque native attachment: the adapter sends it
    ;; as a text block, and a native route would be rejected there.  Returning
    ;; nil here keeps the text route (extracted or inline) as the only path.
    :text nil
    :archive (declared-capability capabilities document-capabilities)
    :file (declared-capability capabilities document-capabilities)
    nil))

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

(defn- route
  [attachment kind capabilities known?]
  (let [native (native-capability kind capabilities)
        extracted? (extraction-ready? attachment)
        extractable? (or (text-extractable? (:mime attachment) (:name attachment))
                         (boolean (:extractable? attachment)))
        text-ok? (text-capable? capabilities)
        local-text? (local-text? attachment)
        preferred (let [value (some-> (:preferred-route attachment) keyword)]
                    (when (contains? #{:native :text} value) value))
        base {:kind kind
              :required-capability (required-capability kind)
              :capabilities-known? known?
              :native? (boolean native)
              :native-capability native
              :text-capable? (boolean text-ok?)
              :extractable? (boolean (or extractable? extracted?))
              :extraction-ready? (boolean extracted?)
              :preferred-route preferred}]
    (cond
      ;; An explicit text preference must be honored even when the model also
      ;; accepts the original bytes.  This is the operator's OCR/text route,
      ;; not a capability upgrade.
      (and (= preferred :text)
           text-ok?
           (or extracted? local-text?))
      (merge base
             {:route :text
              :send-route :text
              :reason :preferred-text
              :detail "发送已确认的提取文本"
              :repairs [:select-model :remove]})

      (and (= preferred :text)
           text-ok?
           extractable?
           (not (extraction-failed? attachment)))
      (merge base
             {:route :needs-choice
              :send-route nil
              :reason :extract-text
              :detail "已选择文本路线；请先提取文字"
              :repairs [:extract-text :select-model :remove]})

      ;; An explicit text choice never silently becomes a native send.  If
      ;; extraction failed or is not possible, the operator must retry,
      ;; choose another model, or remove the item.
      (and (= preferred :text) text-ok?)
      (merge base
             {:route :needs-choice
              :send-route nil
              :reason :extract-text
              :detail (if (extraction-failed? attachment)
                        "已选择文本路线，但提取失败；请重试或移除"
                        "已选择文本路线；请先提取文字")
              :repairs [:extract-text :select-model :remove]})

      (= preferred :text)
      (merge base
             {:route :unsupported
              :send-route nil
              :reason :no-compatible-route
              :detail "当前模型不接收文本，也没有可用的原文件路线"
              :repairs [:select-model :remove]})

      ;; A declared native capability wins; extraction remains an alternative.
      native
      (merge base
             {:route :native
              :send-route :native
              :reason :model-supports-native
              :detail "将发送原文件"
              :alternatives (when (or extracted? extractable?)
                              [{:route :text :label "改用提取文本"}])})

      ;; Text files are safe to read locally and send as text.
      (and (= kind :text) (or local-text? extractable?))
      (merge base
             {:route (if local-text? :text :needs-choice)
              :send-route (when local-text? :text)
              :reason (if local-text? :local-text :extract-text)
              :detail (if local-text?
                        "在设备上提取文本后发送"
                        "模型不接受原格式，可在设备上提取文本")
              :repairs [:extract-text :select-model :remove]})

      ;; A real extractor already produced text.
      (and extracted? text-ok?)
      (merge base
             {:route :text
              :send-route :text
              :reason :extracted-text
              :detail "发送已确认的提取文本"
              :repairs [:select-model :remove]})

      ;; A real extractor can be asked, but the operator must choose it.
      (and (not known?) (not extracted?))
      (merge base
             {:route :unknown
              :send-route nil
              :reason :capability-unknown
              :detail "尚未确认当前模型是否接受此类附件"
              :repairs [:select-model :extract-text :remove]})

      (and extractable? text-ok? (not (extraction-failed? attachment)))
      (merge base
             {:route :needs-choice
              :send-route nil
              :reason :extract-text
              :detail "模型不接受原格式，可提取文字或换模型"
              :repairs [:extract-text :select-model :remove]})

      :else
      (merge base
             {:route :unsupported
              :send-route nil
              :reason :no-compatible-route
              :detail "当前模型不接收该类附件，也没有可用的本地提取路线"
              :repairs [:select-model :remove]}))))

(defn compatibility
  "Return the send route for one attachment and one model.

  `:route` is one of `:native`, `:text`, `:needs-choice`, `:unsupported` or
  `:unknown`.  `:send-route` is non-nil only when the item may travel now.
  `:repairs` names the operator actions that can unblock it."
  [attachment model]
  (let [attachment (or attachment {})
        kind (or (get-in attachment [:media :kind])
                 (mime->kind (:mime attachment)))
        {:keys [known? set]} (model-capabilities model)
        limits (service-limits model)
        size-issues (limit-issues attachment limits)
        base (route attachment kind set known?)]
    (assoc
     (cond
      (seq size-issues)
      (merge base
             {:route :unsupported
              :send-route nil
              :reason :service-limit
              :detail "超过当前服务的附件限制"
              :issues size-issues
              :repairs [:select-model :remove]})

      (and (local-copy? attachment)
           (not (canonical-blob-id? (:blob-id attachment))))
      (merge base
             {:route :unsupported
              :send-route nil
              :reason :local-copy-missing
              :detail "本地副本缺少可核对的内容 ID"
              :repairs [:retry :remove]})

      (and (not (local-copy? attachment))
           (not (valid-workspace? (:workspace attachment))))
      (merge base
             {:route :needs-choice
              :send-route nil
              :reason :workspace-reference-unverified
              :detail "外部关联文件尚未保存为本地副本"
              :repairs [:snapshot :remove]})

      (and (not (local-copy? attachment))
           (valid-workspace? (:workspace attachment))
           (not (:workspace-verified? attachment)))
      (merge base
             {:route :needs-choice
              :send-route nil
              :reason :workspace-reference-unverified
              :detail "外部关联文件尚未保存为本地副本"
              :repairs [:snapshot :remove]})

      (and (not (local-copy? attachment))
           (= :native (:route base)))
      (merge base
             {:route :needs-choice
              :send-route nil
              :reason :workspace-reference-unverified
              :detail "外部关联文件需先保存为本地副本才能发送原文件"
              :repairs [:snapshot :select-model :remove]})

      :else
      base)
     :limits limits)))

(defn compatible?
  [attachment model]
  (contains? #{:native :text}
             (:route (compatibility attachment model))))

;; ---------------------------------------------------------------------------
;; Draft validation and send preparation

(defn- issue
  [attachment code message details]
  (cond-> {:code code
           :message message
           :attachment-id (or (:attachment-id attachment) (:id attachment))}
    (some? details) (assoc :details details)))

(defn- normalize-attachment
  [attachment]
  (let [id (or (:attachment-id attachment) (:id attachment))]
    (-> attachment
        (assoc :attachment-id id
               :id id
               :name (or (normalize-name (:name attachment)) (:name attachment))
               :mime (or (normalize-mime (:mime attachment)) "application/octet-stream")
               :status (keyword (or (:status attachment) :ready))
               :local-copy? (local-copy? attachment)
               :preferred-route (when (some? (:preferred-route attachment))
                                  (keyword (:preferred-route attachment)))))))

(defn validate-draft
  "Validate the attachments of one draft against product limits, structural
  integrity, the selected model and service limits.

  The result always contains the enriched attachments, even when refused, so
  the caller can show every card with its reason.  Failed and excluded items
  still count toward the 20-item limit; they are not silently dropped."
  ([attachments model] (validate-draft attachments model {}))
  ([attachments model opts]
   (let [attachments (mapv normalize-attachment (or attachments []))
         limits (merge default-limits
                       (:limits opts)
                       (service-limits model (:limits opts)))
         total (count attachments)
         declared-total (or (:declared-total opts) total)
         count-over (max total declared-total)
         count-issues (when (> count-over (:max-items limits))
                        [(issue nil
                                :ATTACHMENT_LIMIT_EXCEEDED
                                "a draft supports at most 20 attachments"
                                {:count count-over :limit (:max-items limits)})])
         enriched (mapv (fn [attachment]
                          (let [structural (descriptor-issues attachment)
                                compatibility (compatibility attachment model)
                                limit-issues (limit-issues attachment limits)
                                excluded? (true? (:excluded? attachment))
                                status-issues (when (and (not excluded?)
                                                         (not= :ready (:status attachment)))
                                                [(issue attachment
                                                        :ATTACHMENT_NOT_READY
                                                        "attachment is not ready to send"
                                                        {:status (:status attachment)})])
                                compatibility-issues
                                (when (and (not excluded?)
                                           (not (compatible? attachment model)))
                                  [(issue attachment
                                          :ATTACHMENT_INCOMPATIBLE
                                          (:detail compatibility)
                                          (select-keys compatibility
                                                       [:route :reason :required-capability
                                                        :repairs :limits]))])
                                issues (into (vec structural)
                                             (concat limit-issues
                                                     status-issues
                                                     compatibility-issues))
                                ready? (and (empty? issues)
                                            (not excluded?)
                                            (contains? #{:ready} (:status attachment))
                                            (contains? #{:native :text}
                                                       (:route compatibility)))]
                            (assoc attachment
                                   :compatibility compatibility
                                   :send-route (:send-route compatibility)
                                   :issues issues
                                   :ready? ready?
                                   :excluded? excluded?)))
                        attachments)
         all-issues (vec (concat count-issues
                                 (mapcat :issues (remove :excluded? enriched))))
         sendable (filterv #(and (:ready? %) (not (:excluded? %))) enriched)
         blocked (filterv #(and (not (:excluded? %)) (not (:ready? %))) enriched)
         excluded (filterv :excluded? enriched)
         ready-count (count sendable)
         blocked-count (count blocked)]
     {:ok? (empty? all-issues)
      :attachments enriched
      :issues all-issues
      :sendable sendable
      :blocked blocked
      :excluded excluded
      :limits limits
      :summary {:total total
                :ready ready-count
                :blocked blocked-count
                :excluded (count excluded)
                :issue-count (count all-issues)
                :sendable? (and (empty? all-issues)
                                (pos? ready-count)
                                (zero? blocked-count))
                :message (when (pos? blocked-count)
                           (str "已加入 " total " 个，"
                                blocked-count " 个需要处理"))}})))

(def sendable-attachment-keys
  "Stable descriptor fields that travel with a sent attachment.

  Draft-only choices stay in the composer: `:preferred-route` is resolved
  into `:send-route`, and presentation labels such as `:status-label` or
  `:remove-label` describe a card, not an attachment.  The message history and
  the model request carry only this descriptor.  `:url` is the immutable blob
  link the history view displays; the model adapter accepts it as reference
  metadata and loads bytes through its injected loader, never from this URL."
  #{:attachment-id :id :name :type :kind :mime :send-mime :blob-id
    :send-blob-id :size :send-route :send-text :media :status :excluded?
    :ready? :local-copy? :prepared :prepared-status :extraction :workspace
    :compatibility :issues :send-source :send-original? :sha256
    :declared-mime :sniffed-mime :mime-mismatch? :source :created-at
    :limits :url})

(defn- page-marked-text
  "Keep the document's own page numbers in text that travels to the model.

  A PDF selection of pages 2–5 stays 2–5; the model sees `第 2 页：...`
  instead of a rebased 1–4.  Pages without recognized text are omitted from
  the text route (the card lists them as unreadable); they are never silently
  presented as read.  A source without per-page results keeps its text."
  [extraction]
  (let [pages (vec (:pages extraction))]
    (if (seq pages)
      ;; Append directly into one builder.  A `map` + `str/join` pipeline
      ;; allocates one intermediate string per page and a lazy sequence; a
      ;; 10k-page selection would hold both copies at once.
      (let [builder (StringBuilder.)]
        (doseq [page pages
                :when (= :text (:status page))]
          (when (pos? (.length builder))
            (.append builder "\n\n"))
          (.append builder "第 ")
          (.append builder (str (:page page)))
          (.append builder " 页：\n")
          (.append builder (str (:text page))))
        (.toString builder))
      (:text extraction))))

(defn prepare-send
  "Validate a draft immediately before submission and return only the items
  that may travel.

  A `:text` route carries the confirmed extracted text, not a promise that
  the original file was read.  A native route uses the prepared image copy
  when one exists, because §7.4 asks for the default 2048-pixel send copy
  while keeping the original local copy intact.

  `:ok?` means the attachment policy has no unresolved issue.  It can be true
  with an empty `:sendable` vector when every failed card was explicitly
  excluded; the caller must then refuse a message with no text."
  ([attachments model] (prepare-send attachments model {}))
  ([attachments model opts]
   (let [validation (validate-draft attachments model opts)
         sendable (mapv (fn [attachment]
                          (let [compatibility (:compatibility attachment)
                                prepared (:prepared attachment)
                                route (:send-route compatibility)
                                send-blob-id (cond
                                               (and (= route :native)
                                                    prepared
                                                    (not (:send-original? attachment))
                                                    (canonical-blob-id? (:blob-id prepared)))
                                               (:blob-id prepared)
                                               :else
                                               (:blob-id attachment))]
                            (select-keys
                             (assoc attachment
                                    :send-route route
                                    :send-source (if (local-copy? attachment)
                                                   :blob
                                                   :workspace)
                                    :send-blob-id send-blob-id
                                    :send-mime (if (= route :text)
                                                 "text/plain; charset=utf-8"
                                                 (or (:mime prepared)
                                                     (:mime attachment)))
                                    :send-text (when (= route :text)
                                                 (page-marked-text
                                                  (:extraction attachment))))
                             sendable-attachment-keys)))
                        (:sendable validation))]
     (assoc validation
            :sendable sendable
            ;; `:ok?` means the attachment policy has no unresolved issue.
            ;; A text-only message may legitimately travel with zero sendable
            ;; attachments after every failed card was explicitly excluded.
            ;; The caller must still refuse a completely empty message.
            :ok? (:ok? validation)))))

(defn summary-message
  "The batch-add message required by §3.4 item 2.  Failed items keep a place
  in the count; the message never implies that every file succeeded."
  [added blocked]
  (let [added (long (or added 0))
        blocked (long (or blocked 0))]
    (if (pos? blocked)
      (str "已加入 " added " 个，" blocked " 个需要处理")
      (str "已加入 " added " 个"))))

