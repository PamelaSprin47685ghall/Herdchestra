(ns hc.server.miniapps
  "Generate one workspace mini-app through the low-level model port.

  The HTTP surface is deliberately small:

    POST /api/workspace/miniapp-generate   {:prompt :filename :workspace-root?}
    GET  /api/workspace/miniapps           registered apps for this workspace

  Generation is a real model request (never an `AiServices` loop).  The
  model output is validated as one complete HTML document with no external
  script source and no external form target.  The accepted document is
  written through `hc.core.workspace-gate`: the batch records the target as
  missing, runs a dry-run, then commits under the shared write gate.  A
  pre-existing file is never replaced.

  Registration is metadata, not a second source of truth: the workspace file
  stays the app.  Records are keyed by `app-id` and persisted under the data
  directory when one is configured.  Each record names the independent
  browser storage namespace `miniapp:<app-id>`."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [hc.core.workspace-gate :as wg]
            [hc.server.model-port.contract :as model-contract])
  (:import (java.io ByteArrayOutputStream InputStream)
           (java.nio.charset StandardCharsets)
           (java.nio.file AtomicMoveNotSupportedException Files LinkOption
                          OpenOption Path Paths StandardCopyOption)
           (java.nio.file.attribute FileAttribute)
           (java.time Instant)
           (java.util UUID)))

(set! *warn-on-reflection* true)

(def generate-uri "/api/workspace/miniapp-generate")
(def list-uri "/api/workspace/miniapps")

(def ^:private miniapps-dir "miniapps")
(def ^:private max-output-bytes (* 2 1024 1024))
(def ^:private max-request-bytes (* 64 1024))
(def ^:private max-prompt-chars 20000)
(def default-timeout-ms 300000)
(def max-timeout-ms 600000)
(def ^:private actor-id "miniapp-generator")
(def ^:private claim-id "miniapp-generate")

(def system-prompt
  (str "You generate one complete, self-contained HTML document for a "
       "workspace mini-app.\n"
       "Rules:\n"
       "- Output only the HTML document. No Markdown fences and no commentary.\n"
       "- Start with <!DOCTYPE html>, include <html>, <head>, and <body>, "
       "and end with </html>.\n"
       "- Use inline CSS and inline JavaScript only. Do not use external "
       "resources, <script src>, external stylesheets, or external images.\n"
       "- Do not use a form action that points to another origin.\n"
       "- Persist data with localStorage; do not make network requests.\n"
       "- Be minimal: at most 8 CSS rules, no CSS variables, gradients, "
       "shadows, or animations. The complete document must be under 2600 "
       "characters so it finishes within the 1024-token response budget."))

;; ---------------------------------------------------------------------------
;; Results

(defn- failure
  ([code message] (failure code message nil))
  ([code message details]
   (cond-> {:ok? false
            :status :error
            :error {:code code :message message}}
     (some? details) (assoc-in [:error :details] details))))

(defn- success
  [app]
  {:ok? true :status :ok :app app})

(defn resolve-timeout-ms
  "Resolve one attempt deadline.

  A positive integer in the request overrides the service deadline.  Both are
  capped at `max-timeout-ms` (600 s).  A non-positive or non-integer request
  value is `:invalid`; it is never silently replaced by the default."
  ([requested] (resolve-timeout-ms requested default-timeout-ms))
  ([requested service-default]
   (let [service-default (if (and (integer? service-default)
                                  (pos? service-default))
                           (long service-default)
                           (long default-timeout-ms))
         requested (cond
                     (nil? requested) nil
                     (and (integer? requested) (pos? requested))
                     (long requested)
                     :else :invalid)]
     (if (= :invalid requested)
       :invalid
       (min (long max-timeout-ms)
            (or requested service-default))))))

(defn- configured-connection-timeout
  "Read the task/main connection timeout from the launch configuration.

  Settings can change at runtime, so this is a startup hint only; the service
  uses `max(default, configured)` and still allows a request override."
  [config]
  (try
    (let [models (:models config)
          connections (:connections models)
          roles (:roles models)
          role (or (:task roles) (:main roles))
          conn-id (cond
                    (map? role) (:connection role)
                    (or (string? role) (keyword? role)) role
                    :else nil)
          conns (cond
                  (map? connections) (vals connections)
                  (sequential? connections) connections
                  :else [])
          conn (or (some #(when (= conn-id (:id %)) %) conns)
                   (when (= 1 (count conns)) (first conns)))
          timeout (:timeout-ms conn)]
      (when (and (integer? timeout) (pos? timeout))
        timeout))
    (catch Throwable _ nil)))

(defn- error-kind
  [result kind]
  (-> result
      (assoc-in [:error :kind] kind)
      (assoc-in [:error :remedy] :retry)))

;; ---------------------------------------------------------------------------
;; Filename and HTML validation

(defn safe-filename
  "Normalize one requested filename to a safe `miniapps/` leaf.

  A path separator, a parent segment, a blank name, or a hidden name is
  rejected instead of being silently reinterpreted.  Control characters and
  the Windows-reserved punctuation become `-`.  The result always ends in
  `.html`."
  [value]
  (let [raw (some-> value str str/trim)]
    (cond
      (str/blank? raw)
      (failure :INVALID_FILENAME "请提供小应用文件名")

      (str/includes? raw "/")
      (failure :INVALID_FILENAME "文件名不能包含 /；小应用固定写入 miniapps/ 目录"
               {:filename raw})

      (str/includes? raw "\\")
      (failure :INVALID_FILENAME "文件名不能包含反斜杠" {:filename raw})

      (str/includes? raw "..")
      (failure :INVALID_FILENAME "文件名不能包含 .." {:filename raw})

      (str/includes? raw "\u0000")
      (failure :INVALID_FILENAME "文件名包含 NUL 字符" {:filename raw})

      :else
      (let [base (-> raw
                     (str/replace #"[\u0000-\u001f\u007f]" "-")
                     (str/replace #"[\\/:*?\"<>|]" "-")
                     str/trim)
            stem (str/replace base #"(?i)\.(?:html?|xhtml)$" "")]
        (cond
          (str/blank? stem)
          (failure :INVALID_FILENAME "文件名净化后为空" {:filename raw})

          (str/starts-with? stem ".")
          (failure :INVALID_FILENAME "文件名不能以点开头" {:filename raw})

          (> (alength (.getBytes ^String base StandardCharsets/UTF_8)) 180)
          (failure :INVALID_FILENAME "文件名过长（UTF-8 最多 180 字节）"
                   {:filename raw})

          :else
          {:ok? true :filename (str stem ".html")})))))

(defn- strip-fences
  [text]
  (let [text (str/trim text)]
    (if (str/starts-with? text "```")
      (let [newline (str/index-of text "\n")
            body (if newline (subs text (inc newline)) text)
            end (str/last-index-of body "```")]
        (str/trim (if end (subs body 0 end) body)))
      text)))

(defn- html-candidate
  "Slice the first complete `<html>...</html>` document out of TEXT.

  Leading prose or a Markdown fence is not part of the document.  A missing
  start or end marker is an explicit rejection, never a partial file."
  [text]
  (let [lower (str/lower-case text)
        doctype (str/index-of lower "<!doctype")
        html (str/index-of lower "<html")
        start (cond
                (and doctype (or (nil? html) (< doctype html))) doctype
                html html
                :else nil)
        end (str/last-index-of lower "</html>")]
    (when (and start end (>= end start))
      (subs text start (+ end (count "</html>"))))))

(defn- attribute-value
  [tag attribute]
  (let [pattern (re-pattern
                 (str "(?i)(?:^|\\s)" (java.util.regex.Pattern/quote attribute)
                      "\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))"))]
    (when-let [match (re-find pattern tag)]
      (or (nth match 1 nil) (nth match 2 nil) (nth match 3 nil)))))

(defn- external-url?
  [value]
  (let [value (str/trim (str value))
        lower (str/lower-case value)]
    (boolean
     (or (str/starts-with? lower "http://")
         (str/starts-with? lower "https://")
         (str/starts-with? value "//")
         (re-find #"(?i)^[a-z][a-z0-9+.-]*:" value)))))

(defn- external-script
  [html]
  (some (fn [tag]
          (let [src (attribute-value tag "src")]
            (when (and src (not (str/blank? src))) src)))
        (re-seq #"(?is)<script\b[^>]*>" html)))

(defn- external-form
  [html]
  (or (some (fn [tag]
              (let [action (attribute-value tag "action")]
                (when (and action (external-url? action)) action)))
            (re-seq #"(?is)<form\b[^>]*>" html))
      (some (fn [tag]
              (let [action (attribute-value tag "formaction")]
                (when (and action (external-url? action)) action)))
            (re-seq #"(?is)<(?:button|input)\b[^>]*>" html))))

(defn validate-html
  "Validate one model answer as a complete, self-contained HTML document.

  Returns `{:ok? true :html ... :bytes n}` or a structured failure.  A
  Markdown fence is removed, but prose, a partial document, an external
  script source, and an external form target are rejected."
  [text]
  (let [text (strip-fences (str (or text "")))]
    (if (str/blank? text)
      (failure :INVALID_HTML "模型没有返回任何内容")
      (if-let [candidate (html-candidate text)]
        (let [html (if (str/starts-with? (str/lower-case candidate) "<!doctype")
                     candidate
                     (str "<!DOCTYPE html>\n" candidate))
              bytes (alength (.getBytes ^String html StandardCharsets/UTF_8))]
          (cond
            (> bytes max-output-bytes)
            (failure :OUTPUT_TOO_LARGE
                     "生成结果超过 2 MiB 上限"
                     {:bytes bytes :limit max-output-bytes})

            (not (re-find #"(?i)<html\b" html))
            (failure :INVALID_HTML "生成结果不是 HTML 文档")

            (not (re-find #"(?i)</html>" html))
            (failure :INVALID_HTML "生成结果缺少 </html> 结束标记")

            (external-script html)
            (failure :EXTERNAL_RESOURCE
                     "小应用不得引用外部脚本；请使用内联脚本"
                     {:attribute :script-src})

            (external-form html)
            (failure :EXTERNAL_RESOURCE
                     "小应用的表单不得提交到外部地址"
                     {:attribute :form-action})

            :else
            {:ok? true :html html :bytes bytes}))
        (failure :INVALID_HTML
                 "模型输出不是完整的 HTML 文档（需要 <html> 和 </html>）"
                 {:chars (count text)
                  :preview (subs text 0 (min 200 (count text)))})))))

;; ---------------------------------------------------------------------------
;; Storage isolation injection

(def storage-marker
  "HTML marker for the injected per-app storage bootstrap.  Its presence makes
  injection idempotent."
  "data-hc-miniapp-storage")

(defn- html-escape
  [value]
  (-> (str value)
      (str/replace "&" "&amp;")
      (str/replace "\"" "&quot;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- json-embed
  [value]
  (-> (json/write-str (str value))
      (str/replace "<" "\\u003c")))

(defn- storage-script
  [app-id]
  (let [prefix (str "miniapp:" app-id ":")]
    (str "<script " storage-marker "=\"" (html-escape app-id) "\">\n"
         "(function(){\n"
         "  var APP_ID = " (json-embed app-id) ";\n"
         "  var PREFIX = " (json-embed prefix) ";\n"
         "  if (window.__hcMiniappStorage && window.__hcMiniappStorage.appId === APP_ID) return;\n"
         "  var nativeStorage = null;\n"
         "  try { nativeStorage = window.localStorage; } catch (e) { nativeStorage = null; }\n"
         "  if (!nativeStorage) {\n"
         "    var memory = Object.create(null);\n"
         "    nativeStorage = {\n"
         "      getItem: function(k){ return Object.prototype.hasOwnProperty.call(memory, k) ? memory[k] : null; },\n"
         "      setItem: function(k, v){ memory[String(k)] = String(v); },\n"
         "      removeItem: function(k){ delete memory[String(k)]; },\n"
         "      clear: function(){ memory = Object.create(null); },\n"
         "      key: function(i){ var keys = Object.keys(memory); return i >= 0 && i < keys.length ? keys[i] : null; }\n"
         "    };\n"
         "    Object.defineProperty(nativeStorage, 'length', { get: function(){ return Object.keys(memory).length; } });\n"
         "  }\n"
         "  function keys(){\n"
         "    var out = [];\n"
         "    try {\n"
         "      for (var i = 0; i < nativeStorage.length; i++) {\n"
         "        var key = nativeStorage.key(i);\n"
         "        if (typeof key === 'string' && key.indexOf(PREFIX) === 0) out.push(key.slice(PREFIX.length));\n"
         "      }\n"
         "    } catch (e) {}\n"
         "    return out;\n"
         "  }\n"
         "  var storage = {\n"
         "    getItem: function(key){ try { return nativeStorage.getItem(PREFIX + String(key)); } catch (e) { return null; } },\n"
         "    setItem: function(key, value){ try { nativeStorage.setItem(PREFIX + String(key), String(value)); } catch (e) {} },\n"
         "    removeItem: function(key){ try { nativeStorage.removeItem(PREFIX + String(key)); } catch (e) {} },\n"
         "    clear: function(){\n"
         "      keys().forEach(function(key){\n"
         "        try { nativeStorage.removeItem(PREFIX + key); } catch (e) {}\n"
         "      });\n"
         "    },\n"
         "    key: function(index){ var ks = keys(); return index >= 0 && index < ks.length ? ks[index] : null; }\n"
         "  };\n"
         "  Object.defineProperty(storage, 'length', { get: function(){ return keys().length; } });\n"
         "  try { Object.defineProperty(window, 'localStorage', { configurable: true, value: storage }); }\n"
         "  catch (e) { try { window.localStorage = storage; } catch (e2) {} }\n"
         "  window.miniappStorage = {\n"
         "    get: function(key){ return storage.getItem(key); },\n"
         "    set: function(key, value){ storage.setItem(key, value); return value; },\n"
         "    remove: function(key){ storage.removeItem(key); },\n"
         "    clear: function(){ storage.clear(); }\n"
         "  };\n"
         "  window.miniappAppId = APP_ID;\n"
         "  window.__hcMiniappStorage = { appId: APP_ID, prefix: PREFIX, rawKeys: keys,\n"
         "    prefixedKeys: function(){\n"
         "      var out = [];\n"
         "      try { for (var i = 0; i < nativeStorage.length; i++) { var k = nativeStorage.key(i); if (typeof k === 'string' && k.indexOf(PREFIX) === 0) out.push(k); } } catch (e) {}\n"
         "      return out;\n"
         "    } };\n"
         "})();\n"
         "</script>")))

(defn inject-storage
  "Inject the per-app storage bootstrap into one complete HTML document.

  The script exposes `window.miniappAppId`, `window.miniappStorage`, and a
  namespaced `window.localStorage` whose keys start with
  `miniapp:<app-id>:`.  `clear` removes only keys under that prefix.  The
  function is idempotent: a document that already carries the marker is
  returned unchanged.  It is called only for generated mini-app documents,
  never for ordinary workspace files."
  [html app-id]
  (let [html (str (or html ""))
        app-id (str app-id)]
    (if (or (str/includes? html storage-marker)
            (str/includes? html "window.__hcMiniappStorage"))
      html
      (let [script (storage-script app-id)
            head (re-find #"(?is)<head\b[^>]*>" html)]
        (cond
          head
          (str/replace-first html head (str head script))

          (re-find #"(?is)<html\b[^>]*>" html)
          (let [tag (re-find #"(?is)<html\b[^>]*>" html)]
            (str/replace-first html tag
                               (str tag "<head>" script "</head>")))

          :else
          (str "<!DOCTYPE html><html><head>" script
               "</head><body>" html "</body></html>"))))))

;; ---------------------------------------------------------------------------
;; Paths and files

(defn- root-paths
  [root]
  (when (str/blank? (str root))
    (throw (ex-info "miniapp workspace root is blank"
                    {:error/kind :miniapp-invalid :field :root})))
  (let [path (-> (Paths/get (str root) (make-array String 0))
                 .toAbsolutePath
                 .normalize)]
    (when-not (Files/isDirectory path (make-array LinkOption 0))
      (throw (ex-info "miniapp workspace root is not a directory"
                      {:error/kind :miniapp-invalid :field :root
                       :root (str root)})))
    {:display (str path)
     :real (.toRealPath path (make-array LinkOption 0))}))

(defn- under?
  [^Path child ^Path parent]
  (and child parent (.startsWith child parent)))

(defn- nofollow-exists?
  [^Path path]
  (Files/exists path (into-array LinkOption [LinkOption/NOFOLLOW_LINKS])))

(defn- canonical-root
  [root]
  (try
    (str (.toRealPath (Paths/get (str root) (make-array String 0))
                      (make-array LinkOption 0)))
    (catch Throwable _ nil)))

(defn- safe-directory
  "Check `miniapps/` before generation.  A symlink that resolves outside the
  workspace is rejected; an ordinary directory or a missing directory is
  accepted."
  [ctx]
  (let [^Path root-path (:root-path ctx)
        ^Path dir (.resolve root-path ^String miniapps-dir)
        stat (wg/stat-path (:root ctx) miniapps-dir)]
    (cond
      (Files/isSymbolicLink dir)
      (try
        (let [real (.toRealPath dir (make-array LinkOption 0))]
          (when-not (under? real (:root-real ctx))
            (failure :PATH_ESCAPE "miniapps 目录指向工作区外"
                     {:path miniapps-dir :target (str real)})))
        (catch Throwable _
          (failure :PATH_ESCAPE "miniapps 目录的符号链接无法解析"
                   {:path miniapps-dir})))

      (not (:exists stat)) nil
      (not (:is-dir stat))
      (failure :NOT_A_DIRECTORY "工作区中的 miniapps 不是目录"
               {:path miniapps-dir})
      :else nil)))

(defn- workspace-check
  [ctx requested-root rel ^Path absolute]
  (let [^Path root-path (:root-path ctx)
        requested (some-> requested-root str str/trim)]
    (cond
      (and (some? requested) (not (str/blank? requested)))
      (if (= (canonical-root requested) (:root-real ctx))
        nil
        (failure :WORKSPACE_MISMATCH
                 "请求的工作区与服务器工作区不一致"
                 {:requested (str requested) :workspace-root (:root ctx)}))

      (not (under? absolute root-path))
      (failure :PATH_ESCAPE "目标路径不在工作区内" {:path rel})

      (not (under? absolute (.resolve root-path ^String miniapps-dir)))
      (failure :PATH_ESCAPE "目标路径不在 miniapps/ 目录内" {:path rel})

      :else
      (safe-directory ctx))))

;; ---------------------------------------------------------------------------
;; Model request

(defn- model-failure
  "Map one provider failure.  A provider timeout is a timeout receipt, not a
  generic model failure: the two have different status codes and remedies."
  [event]
  (let [error (:error event)
        timeout? (or (= :timeout (:kind error))
                     (= :timeout (:category error)))
        details {:model-kind (:kind error)
                 :category (:category error)
                 :retryable? (:retryable? error)}]
    (if timeout?
      (error-kind
       (failure :MODEL_TIMEOUT
                (str "模型请求超时：" (or (:message error) "无详细信息")
                     "；请重试")
                (assoc details :provider-timeout? true))
       :timeout)
      (error-kind
       (failure :MODEL_FAILED
                (str "模型生成失败：" (or (:message error) "无详细信息"))
                details)
       :model-failed))))

(defn- generate-with-port
  [ctx port prompt timeout-ms]
  (let [attempt-id (str "miniapp-attempt-" ((:uuid ctx)))
        call-id (str "miniapp-call-" ((:uuid ctx)))
        request (model-contract/request
                 {:attempt-id attempt-id
                  :call-id call-id
                  :snapshot {:role :task
                             :messages [{:role :system :text system-prompt}
                                        {:role :user :text prompt}]
                             :max-tokens 1024
                             :stream? false}})
        result (promise)
        receiver (reify model-contract/Receiver
                   (deliver! [_ event]
                     (when (contains? #{:completed :failed :cancelled}
                                      (:event event))
                       (deliver result event))))]
    (try
      (let [handle (model-contract/start-request! port request receiver)
            event (deref result timeout-ms ::timeout)]
        (if (= ::timeout event)
          (do
            (try
              (model-contract/cancel-request! port handle)
              (catch Throwable _ nil))
            (error-kind
             (failure :MODEL_TIMEOUT
                      "模型生成超时；请重试或选择更快的模型"
                      {:timeout-ms timeout-ms})
             :timeout))
          (case (:event event)
            :completed
            (let [text (some-> (:text event) str str/trim)
                  finish (:finish-reason event)]
              (cond
                (str/blank? text)
                (failure :MODEL_EMPTY "模型返回了空内容")

                (= :length finish)
                (failure :OUTPUT_TRUNCATED
                         "模型输出在 token 上限处被截断；请缩小生成范围后重试"
                         {:finish-reason finish :chars (count text)})

                :else
                {:ok? true :text text :finish-reason finish}))

            :failed
            (model-failure event)

            :cancelled
            (error-kind
             (failure :MODEL_FAILED "模型生成已取消")
             :model-failed)

            (error-kind
             (failure :MODEL_FAILED "模型返回了未知事件"
                      {:event (:event event)})
             :model-failed))))
      (catch Throwable t
        (error-kind
         (failure :MODEL_FAILED
                  (or (.getMessage ^Throwable t) (str t))
                  {:class (.getName (class t))})
         :model-failed)))))

(defn- generate-text
  [ctx prompt timeout-ms]
  (if-let [generate-fn (:generate-fn ctx)]
    (generate-fn {:prompt prompt :timeout-ms timeout-ms})
    (let [port (some-> (:model-port-ref ctx) deref)]
      (if-not port
        (failure :MODEL_NOT_CONFIGURED
                 "尚未配置模型连接；请先在设置中选择模型")
        (generate-with-port ctx port prompt timeout-ms)))))

;; ---------------------------------------------------------------------------
;; Protected write

(defn- commit-session!
  [ctx session]
  (wg/commit! (:workspace-gate ctx)
              {:request-id (str "miniapp-write-" ((:uuid ctx)))
               :actor-id actor-id
               :claim-id claim-id
               :batch (wg/snapshot session)}))

(defn- commit-result
  "Run the dry-run and protected commit for one prepared session."
  [ctx session]
  (let [preview (wg/preview (wg/snapshot session))]
    (if (wg/preview? preview)
      {:commit (commit-session! ctx session)}
      {:rejection preview})))

(defn- write-once!
  "Stage one create-only write and commit it.

  The target is observed as missing in this batch, so a file created after
  the observation is a conflict rather than a replacement.  A missing
  `miniapps/` directory is created in the same batch."
  [ctx rel html]
  (let [session (wg/session (:root ctx))
        dir-stat (wg/stat-path (:root ctx) miniapps-dir)]
    (cond
      (and (:exists dir-stat) (not (:is-dir dir-stat)))
      {:rejection (failure :NOT_A_DIRECTORY "工作区中的 miniapps 不是目录"
                           {:path miniapps-dir})}

      :else
      (do
        (when-not (:exists dir-stat)
          (wg/mkdir session miniapps-dir))
        (let [target-stat (wg/stat session rel)]
          (if (:exists target-stat)
            {:rejection (failure :APP_EXISTS
                                 "同名文件已存在；请换一个文件名"
                                 {:workspace-path rel})}
            (do
              (wg/write session rel html)
              (commit-result ctx session))))))))

(defn- target-exists?
  [ctx ^String rel]
  (let [^Path root-path (:root-path ctx)]
    (nofollow-exists? (.resolve root-path rel))))

(defn- write-failure
  [ctx rel result]
  (let [status (:status result)
        code (:code result)]
    (cond
      (= :HARD_FAILURE status)
      (failure :WRITE_UNCERTAIN
               "受保护写入可能只完成了一部分；请检查工作区文件"
               {:workspace-status status :workspace-code code})

      (and (contains? #{:STRUCTURE_CONFLICT :FILE_CONFLICT
                        :PREPARE_REJECTED}
                      status)
           (target-exists? ctx rel))
      (failure :APP_EXISTS "同名文件已存在；请换一个文件名"
               {:workspace-path rel :workspace-status status})

      (= :LIMIT_EXCEEDED status)
      (failure :OUTPUT_TOO_LARGE
               "生成结果超过工作区写入上限"
               {:workspace-status status :workspace-code code})

      :else
      (failure :WRITE_FAILED
               (or (:message result) "受保护写入失败")
               {:workspace-status status :workspace-code code}))))

(defn- directory-race?
  [ctx result]
  (let [^Path root-path (:root-path ctx)
        path (str (:path result))
        dir (str (.resolve root-path ^String miniapps-dir))]
    (and (= :PREPARE_REJECTED (:status result))
         (= :already-exists (:code result))
         (= path dir))))

(defn- write-html!
  "Write HTML through the gate.  Retry once when another writer created the
  `miniapps/` directory between the observation and the commit."
  [ctx rel html]
  (let [attempt (fn []
                  (let [{:keys [commit rejection]} (write-once! ctx rel html)]
                    (or rejection
                        (if (= :APPLIED (:status commit))
                          {:ok? true :commit commit}
                          commit))))]
    (let [first-result (attempt)
          result (if (and (map? first-result)
                          (directory-race? ctx first-result))
                   (attempt)
                   first-result)]
      (cond
        (:ok? result) result
        (false? (:ok? result)) result
        :else (write-failure ctx rel result)))))

;; ---------------------------------------------------------------------------
;; Registration

(defn- registry-state
  []
  {:version 1 :apps {}})

(defn- read-registry
  [path]
  (if (and path (Files/isRegularFile ^Path path
                                      (make-array LinkOption 0)))
    (try
      (let [value (edn/read-string
                   (Files/readString ^Path path StandardCharsets/UTF_8))]
        (if (and (map? value) (map? (:apps value)))
          (merge (registry-state) value)
          (registry-state)))
      (catch Throwable _ (registry-state)))
    (registry-state)))

(defn- write-registry!
  [^Path path value]
  (let [parent (.getParent path)]
    (when parent
      (Files/createDirectories parent (make-array FileAttribute 0)))
    (let [tmp (Files/createTempFile parent "registry" ".edn"
                                    (make-array FileAttribute 0))]
      (try
        (let [^bytes bytes (.getBytes ^String (pr-str value)
                                     StandardCharsets/UTF_8)]
          (Files/write tmp bytes ^"[Ljava.nio.file.OpenOption;" (make-array OpenOption 0)))
        (try
          (Files/move tmp path
                      (into-array java.nio.file.CopyOption
                                  [StandardCopyOption/ATOMIC_MOVE
                                   StandardCopyOption/REPLACE_EXISTING]))
          (catch AtomicMoveNotSupportedException _
            (Files/move tmp path
                        (into-array java.nio.file.CopyOption
                                    [StandardCopyOption/REPLACE_EXISTING]))))
        (finally
          (Files/deleteIfExists tmp))))))

(defn- register!
  "Persist one app record.  Returns nil on success or a failure map."
  [ctx record]
  (try
    (locking (:registry-lock ctx)
      (let [current @(:registry ctx)
            next-state (assoc-in current [:apps (:app-id record)] record)]
        (when-let [path (:registry-path ctx)]
          (write-registry! path next-state))
        (reset! (:registry ctx) next-state)))
    nil
    (catch Throwable t
      (failure :REGISTRY_FAILED
               "小应用文件已写入，但登记元数据失败"
               {:class (.getName (class t))
                :message (.getMessage ^Throwable t)}))))

(defn- rollback-file!
  "Best-effort removal of the just-created file when registration fails."
  [ctx rel]
  (try
    (let [session (wg/session (:root ctx))
          stat (wg/stat session rel)]
      (when (:exists stat)
        (wg/rm session rel)
        (let [result (commit-session! ctx session)]
          (= :APPLIED (:status result)))))
    (catch Throwable _ false)))

;; ---------------------------------------------------------------------------
;; Generation orchestration

(defn- content-digest
  "The client's non-security FNV-1a fingerprint, mirrored so a freshly
  generated app is not reported as an unexplained source change on first
  open.  It is change detection only, never an identity or trust signal."
  [text]
  (let [text (str text)
        hash (loop [index 0
                    hash 2166136261]
               (if (< index (count text))
                 (let [code (int (.charAt ^String text index))
                       hash (bit-xor hash code)
                       hash (bit-and (unchecked-multiply hash 16777619)
                                     0xffffffff)]
                   (recur (inc index) hash))
                 hash))]
    (format "%08x" (long hash))))

(defn- tag-attribute
  [tag attribute]
  (some (fn [[_ key value]]
          (when (= (str/lower-case key) (str/lower-case attribute))
            value))
        (re-seq #"(?i)([a-zA-Z][a-zA-Z0-9_-]*)\s*=\s*[\"']([^\"']*)[\"']" tag)))

(defn- declared-data-version
  [html]
  (let [tag (some (fn [tag]
                    (when (= "herdchestra-data-version"
                             (some-> (tag-attribute tag "name")
                                     str/lower-case))
                      tag))
                  (re-seq #"(?is)<meta\b[^>]*>" (str html)))
        content (some-> (tag-attribute (or tag "") "content") str/trim)]
    (if (and content (re-matches #"\d{1,9}" content))
      (Long/parseLong content)
      1)))

(defn- app-record
  [ctx filename rel html app-id]
  (let [created-at ((:now ctx))
        data-version (declared-data-version html)]
    {:app-id app-id
     :name filename
     :path rel
     :created-at created-at
     :last-used-at created-at
     :last-updated-at created-at
     :storage-namespace (str "miniapp:" app-id)
     :workspace-root (:root ctx)
     :status :available
     :data-version data-version
     :source-data-version data-version
     :source-digest (content-digest html)
     :source-version 1
     :data-bytes 0
     :network-allowed []
     :grants []}))

(defn- generate-app!
  [ctx {:keys [prompt filename workspace-root timeout-ms]}]
  (let [raw-prompt prompt
        prompt (some-> prompt str str/trim)
        timeout-ms (resolve-timeout-ms timeout-ms (:timeout-ms ctx))]
    (cond
      (not (string? raw-prompt))
      (failure :INVALID_PROMPT "prompt 必须是字符串")

      (str/blank? prompt)
      (failure :INVALID_PROMPT "请描述要生成的小应用")

      (> (count prompt) max-prompt-chars)
      (failure :INVALID_PROMPT
               (str "prompt 过长（最多 " max-prompt-chars " 字符）"))

      (= :invalid timeout-ms)
      (failure :INVALID_TIMEOUT
               "timeout-ms 必须是 1..600000 之间的整数"
               {:max-timeout-ms max-timeout-ms})

      :else
      (let [file-result (safe-filename filename)]
        (if-not (:ok? file-result)
          file-result
          (let [filename (:filename file-result)
                rel (str miniapps-dir "/" filename)
                absolute (.resolve ^Path (:root-path ctx) rel)
                checked (workspace-check ctx workspace-root rel absolute)]
            (if (some? checked)
              checked
              (if (target-exists? ctx rel)
                (failure :APP_EXISTS "同名文件已存在；请换一个文件名"
                         {:workspace-path rel})
                (let [generated (generate-text ctx prompt timeout-ms)]
                  (if-not (:ok? generated)
                    generated
                    (let [validated (validate-html (:text generated))]
                      (if-not (:ok? validated)
                        validated
                        (let [app-id (str "app-" ((:uuid ctx)))
                              html (inject-storage (:html validated) app-id)
                              written (write-html! ctx rel html)]
                          (if-not (:ok? written)
                            written
                            (let [record (app-record ctx filename rel html app-id)
                                  registration (register! ctx record)]
                              (if registration
                                (assoc registration
                                       :error
                                       (assoc (:error registration)
                                              :details
                                              (assoc (get-in registration
                                                             [:error :details])
                                                     :file-removed?
                                                     (rollback-file! ctx rel))))
                                (success record)))))))))))))))))

;; ---------------------------------------------------------------------------
;; Service

(defn service
  "Build one mini-app generation service.

  Options: `:root` (workspace, required), `:data-dir` (durable registry),
  `:gate` (shared write gate), `:model-port-ref` (atom holding the live
  port), `:generate-fn` (test seam), `:timeout-ms`, `:now`, `:uuid`,
  `:registry-file`."
  [{:keys [root data-dir gate model-port-ref generate-fn timeout-ms
           provider-timeout-ms now uuid registry-file]
    :or {timeout-ms default-timeout-ms}}]
  (let [{:keys [display real]} (root-paths root)
        timeout-ms (resolve-timeout-ms
                    nil
                    (max (long (or timeout-ms default-timeout-ms))
                         (long (or provider-timeout-ms 0))))
        shared-gate (or gate (wg/write-gate))
        registry-path (or registry-file
                          (when data-dir
                            (.resolve (Paths/get (str data-dir)
                                                 (make-array String 0))
                                      (str miniapps-dir "/registry.edn"))))
        board (wg/memory-board)
        _ (wg/set-actor! board actor-id true)
        _ (wg/set-claim! board claim-id true)
        ctx {:root display
             :root-path (Paths/get display (make-array String 0))
             :root-real real
             :registry-path registry-path
             :registry (atom (read-registry registry-path))
             :registry-lock (Object.)
             :gate shared-gate
             :workspace-gate (wg/workspace-gate
                              {:root display
                               :gate shared-gate
                               :board board})
             :model-port-ref model-port-ref
             :generate-fn generate-fn
             :timeout-ms timeout-ms
             :now (or now #(str (Instant/now)))
             :uuid (or uuid #(str (UUID/randomUUID)))}]
    {:root (:root ctx)
     :root-real (:root-real ctx)
     :timeout-ms (:timeout-ms ctx)
     :registry (:registry ctx)
     :generate! (fn [request] (generate-app! ctx request))
     :list-apps (fn []
                  {:ok? true
                   :status :ok
                   :apps (->> @(:registry ctx)
                              :apps
                              vals
                              (filter #(= (:root ctx) (:workspace-root %)))
                              (sort-by :created-at)
                              vec)})}))

;; ---------------------------------------------------------------------------
;; HTTP

(defn- edn-response
  ([status body] (edn-response status body nil))
  ([status body extra-headers]
   {:status status
    :headers (merge {"content-type" "application/edn; charset=utf-8"
                     "cache-control" "no-store"
                     "x-content-type-options" "nosniff"
                     "referrer-policy" "no-referrer"}
                    extra-headers)
    :body (pr-str body)}))

(defn- error-status
  [code]
  (case code
    (:INVALID_REQUEST :INVALID_PROMPT :INVALID_FILENAME :INVALID_JSON
     :INVALID_BODY :INVALID_TIMEOUT) 400
    (:WORKSPACE_MISMATCH :PATH_ESCAPE) 403
    :NOT_FOUND 404
    (:APP_EXISTS :WRITE_CONFLICT :NOT_A_DIRECTORY) 409
    (:OUTPUT_TOO_LARGE :BODY_TOO_LARGE) 413
    :UNSUPPORTED_MEDIA_TYPE 415
    (:INVALID_HTML :EXTERNAL_RESOURCE :OUTPUT_TRUNCATED) 422
    :MODEL_NOT_CONFIGURED 503
    :MODEL_TIMEOUT 504
    (:MODEL_FAILED :MODEL_EMPTY) 502
    (:WRITE_FAILED :WRITE_UNCERTAIN :REGISTRY_FAILED :INTERNAL_ERROR) 500
    500))

(defn- error-response
  [result]
  (edn-response (error-status (get-in result [:error :code])) result))

(defn- header
  [request name]
  (let [name (str/lower-case name)]
    (or (get-in request [:headers name])
        (some (fn [[key value]]
                (when (= name (str/lower-case (name key))) value))
              (:headers request)))))

(defn- body-text
  [body]
  (let [limit max-request-bytes]
    (cond
      (nil? body) {:ok? true :text ""}
      (string? body)
      (if (<= (alength (.getBytes ^String body StandardCharsets/UTF_8)) limit)
        {:ok? true :text body}
        {:ok? false :error :BODY_TOO_LARGE})
      (instance? (Class/forName "[B") body)
      (if (<= (alength ^bytes body) limit)
        {:ok? true :text (String. ^bytes body StandardCharsets/UTF_8)}
        {:ok? false :error :BODY_TOO_LARGE})
      (instance? InputStream body)
      (try
        (with-open [^InputStream stream body]
          (let [out (ByteArrayOutputStream.)
                buffer (byte-array 8192)]
            (loop [total 0]
              (let [n (.read stream buffer)]
                (cond
                  (neg? n)
                  {:ok? true
                   :text (String. (.toByteArray out) StandardCharsets/UTF_8)}

                  (> (+ total n) limit)
                  {:ok? false :error :BODY_TOO_LARGE}

                  :else
                  (do (.write out buffer 0 n)
                      (recur (+ total n))))))))
        (catch Throwable _
          {:ok? false :error :INVALID_BODY}))
      :else {:ok? false :error :INVALID_BODY})))

(defn- parse-json
  [text]
  (try
    (let [value (json/read-str text :key-fn keyword)]
      (if (map? value) {:ok? true :value value} {:ok? false}))
    (catch Throwable _ {:ok? false})))

(defn- json-request?
  [request]
  (let [content-type (some-> (header request "content-type")
                             str/lower-case
                             (str/split #";" 2)
                             first
                             str/trim)]
    (or (nil? content-type)
        (= content-type "application/json")
        (= content-type "text/json"))))

(defn- generate-handler
  [svc request]
  (let [body (body-text (:body request))]
    (cond
      (not (:ok? body))
      (edn-response 413 (failure :BODY_TOO_LARGE "请求体超过 64 KiB"))

      (not (json-request? request))
      (edn-response 415
                    (failure :UNSUPPORTED_MEDIA_TYPE
                             "请求体必须是 application/json"))

      :else
      (let [parsed (parse-json (:text body))]
        (if-not (:ok? parsed)
          (edn-response 400 (failure :INVALID_JSON "请求体不是 JSON 对象"))
          (let [value (:value parsed)
                value (cond-> value
                        (and (contains? value :workspaceRoot)
                             (not (contains? value :workspace-root)))
                        (assoc :workspace-root (:workspaceRoot value))
                        (and (contains? value :timeoutMs)
                             (not (contains? value :timeout-ms)))
                        (assoc :timeout-ms (:timeoutMs value)))]
            (let [result ((:generate! svc) value)]
              (if (:ok? result)
                (edn-response 200 result)
                (error-response result)))))))))

(defn- list-handler
  [svc request]
  (let [requested (or (get-in request [:query-params :workspace-root])
                      (get-in request [:query-params "workspace-root"]))]
    (if (and (some? requested)
             (not= (canonical-root requested)
                   (canonical-root (:root svc))))
      (edn-response 403
                    (failure :WORKSPACE_MISMATCH
                             "请求的工作区与服务器工作区不一致"))
      (edn-response 200 ((:list-apps svc))))))

(defn- method-not-allowed
  [allow]
  (edn-response 405
                (failure :METHOD_NOT_ALLOWED "请求方法不被支持")
                {"allow" allow}))

(defn- not-found
  []
  (edn-response 404 (failure :NOT_FOUND "没有匹配的小应用路由")))

(defn- miniapp-uri?
  [uri]
  (contains? #{generate-uri list-uri (str generate-uri "/")
               (str list-uri "/")}
             (str uri)))

(defn- route
  [svc request]
  (let [uri (str (:uri request))
        method (:request-method request)]
    (cond
      (contains? #{generate-uri (str generate-uri "/")} uri)
      (if (= :post method)
        (generate-handler svc request)
        (method-not-allowed "POST"))

      (contains? #{list-uri (str list-uri "/")} uri)
      (if (contains? #{:get :head} method)
        (let [response (list-handler svc request)]
          (if (= :head method) (assoc response :body nil) response))
        (method-not-allowed "GET, HEAD"))

      :else nil)))

(defn ring-handler
  "Build a Ring handler over one service.  Returns nil outside the two
  mini-app URIs so the application can compose it with other routers."
  [svc]
  (fn [request]
    (when (miniapp-uri? (:uri request))
      (try
        (or (route svc request) (not-found))
        (catch Throwable t
          (edn-response 500
                        (failure :INTERNAL_ERROR
                                 "小应用服务发生内部错误"
                                 {:class (.getName (class t))
                                  :message (.getMessage ^Throwable t)})))))))

(defonce ^:private service-cache (atom {}))

(defn- cached-service
  "One service per workspace/data-dir/gate identity.

  A per-request service would give two concurrent generations separate
  registry locks and could lose one registration.  The cache also keeps the
  live `model-port-ref` atom: a settings save replaces its value, not the
  atom, so the next request still sees the new port."
  [config model-port-ref gate]
  (let [key [(str (:workspace-root config))
             (str (:data-dir config))
             model-port-ref
             gate]]
    (or (get @service-cache key)
        (let [svc (service {:root (:workspace-root config)
                            :data-dir (:data-dir config)
                            :model-port-ref model-port-ref
                            :gate gate
                            :provider-timeout-ms
                            (configured-connection-timeout config)})]
          (get (swap! service-cache
                      (fn [cache]
                        (if (contains? cache key)
                          cache
                          (assoc cache key svc))))
               key)))))

(defn handler
  "Build the application route without a second router.

  `config` supplies `:workspace-root` and `:data-dir`; `model-port-ref` is
  the live atom so a settings save reaches the next generation request.
  The service is built only for a mini-app URI and is shared by all requests
  for the same workspace."
  [config model-port-ref gate]
  (fn [request]
    (when (miniapp-uri? (:uri request))
      (let [built (try
                    {:service (cached-service config model-port-ref gate)}
                    (catch Throwable t
                      {:response
                       (edn-response
                        500
                        (failure :INTERNAL_ERROR
                                 "无法初始化小应用服务"
                                 {:class (.getName (class t))
                                  :message (.getMessage ^Throwable t)}))}))]
        (if-let [svc (:service built)]
          ((ring-handler svc) request)
          (:response built))))))
