(ns hc.client.conversation.export
  "Markdown export for one visible conversation path.

  The caller owns selection: a branch export passes every message currently
  visible for that branch, while a multi-select export passes only the checked
  messages.  This namespace resolves each message's current version, keeps
  only the fields that belong in a readable transcript, and redacts known
  credential shapes.

  By default it omits tool records, process summaries, hidden prompts, blob
  IDs, workspace paths, download URLs and arbitrary maps.  Those fields can
  contain operational detail or credentials that the operator did not select
  for export.  Attachment metadata is rendered as a readable note; the bytes
  themselves stay in the product's local store.

  By default only visible user and assistant bodies pass the filter.  Tool,
  activity, reasoning, and system messages stay out, as does any message whose
  body is a serialized tool payload (`:raw-source`, `(defprogram ...)`, tool
  results).  The operator can opt in to tool and process detail with
  `:include-tools?`; system messages remain excluded because they are hidden
  instructions rather than conversation.

  This namespace is pure.  The view owns the browser download adapter; it
  calls this builder only after the operator explicitly chooses an export."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Credential exclusion

(def ^:private secret-fields
  ;; Keep this list aligned with hc.server.diagnostics.redact/secret-fields.
  #{"api-key" "apikey" "authorization" "auth" "bearer" "cookie" "set-cookie"
    "password" "passwd" "pwd" "secret" "client-secret" "private-key"
    "access-key" "secret-key" "signing-key" "encryption-key" "credential"
    "credentials" "access-token" "refresh-token" "id-token" "auth-token"
    "api-token" "session-token" "csrf-token" "token" "key"})

(def ^:private secret-suffixes
  ["-api-key" "-apikey" "-secret" "-password" "-passwd" "-token"
   "-credential" "-credentials" "-private-key" "-access-key" "-secret-key"
   "-signing-key" "-encryption-key" "-authorization" "-cookie"])

(defn secret-key?
  "True when KEY names a credential field.

  Accepts keywords, strings, and namespaced keys.  The predicate is used by
  callers that build an export envelope; the Markdown builder never serializes
  arbitrary maps, so this is a second line of defence rather than a filter
  applied after the fact."
  [key]
  (let [normalized (-> (cond
                         (keyword? key) (name key)
                         (string? key) key
                         (symbol? key) (name key)
                         :else (str key))
                       str/lower-case
                       (str/replace #"[^a-z0-9]+" "-")
                       (str/replace #"^-+|-+$" ""))]
    (boolean
     (or (contains? secret-fields normalized)
         (some #(str/ends-with? normalized %) secret-suffixes)))))

(def ^:private private-key-block
  #"-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----[\s\S]*?-----END [A-Z0-9 ]*PRIVATE KEY-----")

(def ^:private authorization-header
  #"(?i)\b((?:proxy-)?authorization)\b(\s*[:=]\s*)([^\r\n]+)")

(def ^:private url-credentials
  #"(?i)\b([a-z][a-z0-9+.-]*://)([^/\s:@]+):([^/\s@]+)@")

(def ^:private basic-token
  #"(?i)\bbasic\s+[A-Za-z0-9+/=]{8,}")

(def ^:private secret-assignment
  #"(?i)\b(api[_-]?key|apikey|secret|password|passwd|token)\s*[:=]\s*([\"']?)([A-Za-z0-9._~+/=-]{8,})([\"']?)")

(def ^:private aws-secret-assignment
  #"(?i)\b(aws[_-]?secret[_-]?access[_-]?key)\s*[:=]\s*([\"']?)([A-Za-z0-9/+=]{40})([\"']?)")

(def ^:private known-token
  #"\b(?:sk-[A-Za-z0-9_-]{16,}|gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|xox[baprs]-[A-Za-z0-9-]{10,}|AKIA[0-9A-Z]{16}|AIza[0-9A-Za-z_-]{35}|eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,})\b")

(def ^:private bearer-token
  #"(?i)\bbearer\s+[A-Za-z0-9._~+/=-]{12,}")

(defn redact-text
  "Replace known credential shapes in TEXT with a visible placeholder.

  This covers private-key blocks, authorization headers, secret assignments,
  and common provider token prefixes.  It is not a guarantee that arbitrary
  prose contains no personal information; the operator still reviews the
  content they choose to export."
  [text]
  (let [text (str (or text ""))]
    (-> text
        (str/replace private-key-block "[已隐藏]")
        (str/replace url-credentials
                     (fn [[_ scheme _ _]]
                       (str scheme "[已隐藏]@")))
        (str/replace authorization-header
                     (fn [[_ name separator _]]
                       (str name separator "[已隐藏]")))
        (str/replace aws-secret-assignment
                     (fn [[_ name quote _ close]]
                       (str name ": " quote "[已隐藏]" close)))
        (str/replace secret-assignment
                     (fn [[_ name quote _ close]]
                       (str name ": " quote "[已隐藏]" close)))
        (str/replace basic-token "Basic [已隐藏]")
        (str/replace known-token "[已隐藏]")
        (str/replace bearer-token "Bearer [已隐藏]"))))

;; ---------------------------------------------------------------------------
;; Visible-message filtering

(def ^:private visible-roles
  "Roles that read as conversation in an export.  A legacy message without a
  role is treated as a visible assistant turn, matching the timeline default."
  #{:user :assistant :human})

(def ^:private tool-roles
  "Roles that carry operational detail rather than conversation."
  #{:tool :tool-call :tool-result :function :function-call :call
    :activity :reasoning :system})

(def ^:private tool-payload-patterns
  "Shapes that identify a serialized tool payload inside a message body.

  A server snapshot can store a tool result as an ordinary message body.  The
  role alone is not enough then, so the default export also rejects bodies
  that carry a raw program, its numbered source, or a tool result map."
  [#":raw-source\b"
   #":numbered-source\b"
   #":tool-call-id\b"
   #":tool-results?\b"
   #":control-results\b"
   #"\(defprogram\b"])

(defn- message-role
  [message]
  (let [role (or (:role message) (:kind message) (:type message))]
    (cond
      (keyword? role) role
      (string? role) (keyword role)
      (symbol? role) (keyword (name role))
      :else nil)))

(defn- message-id
  [message]
  (or (:id message) (:message-id message) (:message/id message)))

(defn- tool-payload?
  [message]
  (let [text (str (or (:body message) (:content message) (:text message) ""))]
    (boolean (some #(re-find % text) tool-payload-patterns))))

(defn exportable-message?
  "True when MESSAGE belongs in a readable transcript.

  Options: `:include-tools?` (default false).  With it false, only visible
  user and assistant messages pass; tool, activity, reasoning, and system
  messages, and any body shaped like a serialized tool payload, are dropped.
  With it true, tool and process messages pass, but system messages never do:
  they are hidden instructions, not conversation."
  [message {:keys [include-tools?]}]
  (let [role (message-role message)]
    (cond
      (= :system role) false
      include-tools? true
      :else (and (or (nil? role) (contains? visible-roles role))
                 (not (tool-payload? message))))))

;; ---------------------------------------------------------------------------
;; Current-version projection

(defn- selected-version
  "Return MESSAGE's current version map, or nil for a legacy flat message."
  [message]
  (let [versions (vec (or (:versions message) []))
        index (:current-version message)]
    (when (and (integer? index)
               (<= 0 index)
               (< index (count versions)))
      (nth versions index))))

(defn- message-content
  "Return the selected version's text with credential shapes redacted.

  A version with an explicitly empty `:content` stays empty; it never falls
  back to a superseded version.  Legacy messages without versions use their
  display body.  A live or stopped message uses its display body because the
  stream may already contain text that the last version snapshot does not."
  [message]
  (let [version (selected-version message)
        live? (boolean (or (:streaming? message) (:stopped? message)))
        text (if (and (not live?)
                      version
                      (contains? version :content)
                      (string? (:content version)))
               (:content version)
               (or (:body message) (:content message) ""))]
    (redact-text text)))

(defn- message-attachments
  "Return the selected version's attachment references.

  An explicit empty vector on the selected version is preserved, so removing
  every attachment in a new version also removes it from that version's
  export.  Legacy messages fall back to the display attachment list."
  [message]
  (let [version (selected-version message)
        live? (boolean (or (:streaming? message) (:stopped? message)))]
    (vec (if (and (not live?) version (contains? version :attachments))
           (:attachments version)
           (or (:attachment-refs message) (:attachments message) [])))))

(defn- role-label
  [role]
  (case (keyword role)
    :user "你"
    :assistant "助手"
    :tool "工具"
    :activity "活动"
    :system "系统"
    (if (some? role)
      (if (or (keyword? role) (symbol? role)) (name role) (str role))
      "消息")))

(defn- author-label
  [message]
  (let [author (:author message)]
    (if (and (string? author) (not (str/blank? author)))
      author
      (role-label (:role message)))))

(defn- size-label
  [size]
  (when (number? size)
    (cond
      (< size 1024) (str size " B")
      (< size (* 1024 1024)) (str (quot size 1024) " KiB")
      :else (str (quot size (* 1024 1024)) " MiB"))))

(defn- attachment-line
  [attachment]
  (let [name (or (:name attachment) (:filename attachment) "附件")
        mime (or (:mime attachment) (:media-type attachment))
        size (size-label (:size attachment))
        source (cond
                 (:local-copy? attachment) "本地副本"
                 (:workspace attachment) "工作区引用"
                 :else nil)
        details (->> [mime size source]
                     (remove str/blank?)
                     vec)]
    (str "- " (redact-text name)
         (when (seq details)
           (str "（" (str/join "，" details) "）")))))

(defn- render-source-lines
  "Render SOURCES only when the operator asked to include them.

  Only a title, URL, site, and publication date are emitted.  Snippets and
  arbitrary source maps stay out: they may contain fetched page content the
  operator did not select, and the export must not grow a second copy of it."
  [sources]
  (let [sources (vec (or sources []))]
    (when (seq sources)
      (str "\n\n来源：\n"
           (str/join
            "\n"
            (map-indexed
             (fn [index source]
               (let [source (if (map? source) source {})
                     label (or (:title source) (:url source)
                               (str "来源 " (inc index)))
                     url (:url source)
                     site (:site source)
                     published (or (:published-at source) "未知发布日期")]
                 (str "- " (redact-text label)
                      (when (and (string? url) (not (str/blank? url)))
                        (str " — " (redact-text url)))
                      (str "（"
                           (when (and (string? site) (not (str/blank? site)))
                             (str (redact-text site) "，"))
                           (redact-text published) "）"))))
             sources))))))

(defn- source-lines
  "Render one message's own sources, or the run fallback when it has none.

  A search answer can carry `[S1]` labels while the message has no citation
  map (the terminal text was empty).  The caller may pass the run's public
  source cards as `:fallback-sources`; only the last assistant message gets
  them, so a transcript does not repeat the same list after every reply."
  [message fallback-sources]
  (or (render-source-lines (or (:sources message) (:citations message)))
      (when (seq fallback-sources)
        (render-source-lines fallback-sources))))

(defn- process-line
  "Render one tool/process record as a readable line.

  Only called when the operator explicitly asked for tool detail.  Values are
  still passed through the credential redactor before they reach the text."
  [item]
  (let [title (or (:title item) (:name item) (:label item)
                  (get-in item [:function :name]) "过程")
        status (or (:status-label item) (:status item))
        summary (:summary item)
        result (:result item)
        result-text (cond
                      (nil? result) nil
                      (string? result) result
                      :else (pr-str result))]
    (str "- " (redact-text (str title))
         (when (some? status)
           (str "（" (redact-text (str status)) "）"))
         (when-not (str/blank? (str summary))
           (str "：" (redact-text (str summary))))
         (when-not (str/blank? (str result-text))
           (str "\n  " (redact-text (str result-text)))))))

(defn- process-section
  "Render a message's tool/process records when tool detail is included."
  [message]
  (let [items (vec (or (not-empty (:process message))
                       (not-empty (:process-items message))
                       (:tool-records message)
                       (:tool-calls message)
                       []))]
    (when (seq items)
      (str "\n\n工具与过程：\n"
           (str/join "\n" (map process-line items))))))

(defn message-markdown
  "Render one message as Markdown using only its selected version.

  The heading names the author; the body is the current version's text.
  Attachments are listed by name, media type, size and source kind.  By
  default tool records, process summaries, blob IDs and URLs are not emitted.
  Sources are included only with `:include-sources?`; process records are
  included only with `:include-tools?`."
  ([message] (message-markdown message {}))
  ([message {:keys [include-sources? include-tools? fallback-sources]}]
   (let [body (message-content message)
         attachments (message-attachments message)
         body (cond
                (not (str/blank? body)) body
                (seq attachments) "（无文字正文）"
                :else "（无正文）")]
     (str "## " (author-label message) "\n\n" body
          (when (seq attachments)
            (str "\n\n附件：\n"
                 (str/join "\n" (map attachment-line attachments))))
          (when include-sources?
            (source-lines message fallback-sources))
          (when include-tools?
            (process-section message))))))

(defn- render-messages
  "Render MESSAGES with the run fallback applied only to the last assistant
  reply, so one search answer's source list never repeats after every turn."
  [messages opts render]
  (let [opts (or opts {})
        messages (vec (or messages []))
        fallback (:fallback-sources opts)
        last-assistant (some->> messages
                                (filter #(= :assistant (message-role %)))
                                last
                                message-id)]
    (str/join "\n\n"
              (map (fn [message]
                     (render message
                             (cond-> (dissoc opts :fallback-sources)
                               (and last-assistant
                                    (= last-assistant (message-id message)))
                               (assoc :fallback-sources fallback))))
                   (filter #(exportable-message? % opts) messages)))))

(defn markdown
  "Render MESSAGES as Markdown in caller-supplied order.

  MESSAGES is the already-selected path: the current branch's visible
  messages, or only the checked messages.  This function does not fetch,
  reorder, or merge another session.

  Options: `:include-sources?` (default false), `:include-tools?` (default
  false).  The role and payload filter runs here, before any message is
  rendered."
  ([messages] (markdown messages {}))
  ([messages opts]
   (render-messages messages opts message-markdown)))

(defn message-plain-text
  "Render one message as plain text with the same selection rules as Markdown."
  ([message] (message-plain-text message {}))
  ([message {:keys [include-sources? include-tools? fallback-sources]}]
   (let [body (message-content message)
         attachments (message-attachments message)
         body (cond
                (not (str/blank? body)) body
                (seq attachments) "（无文字正文）"
                :else "（无正文）")]
     (str (author-label message) "：\n" body
          (when (seq attachments)
            (str "\n\n附件：\n"
                 (str/join "\n" (map attachment-line attachments))))
          (when include-sources?
            (source-lines message fallback-sources))
          (when include-tools?
            (process-section message))))))

(defn plain-text
  "Render MESSAGES as plain text in caller-supplied order.

  Options: `:include-sources?` (default false), `:include-tools?` (default
  false).  The role and payload filter runs here, before any message is
  rendered."
  ([messages] (plain-text messages {}))
  ([messages opts]
   (render-messages messages opts message-plain-text)))
