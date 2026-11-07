(ns hc.client.preview.model
  "Pure state for one generated-HTML preview.

  A preview is a single sandboxed document plus the user's temporary
  permissions.  Network access starts closed.  An external link, download or
  form submission is only a request; the user must confirm the destination
  before the host performs anything.

  Streaming and version rules live here:

  - a new source never replaces a preview that the user has interacted with
    until the user chooses to switch;
  - a streaming preview rejects external consequences but keeps local
    filtering and reading available;
  - a failed load keeps the source and error visible without affecting other
    messages."
  (:require [clojure.string :as str]
            [hc.client.sandbox.model :as sandbox]))

(def max-html-bytes
  "Upper bound for one fetched HTML document.  A larger document is rejected
  before it is decoded into the page."
  (* 2 1024 1024))

(defn initial-state
  []
  {:status :idle
   :html nil
   :source-label "生成的 HTML"
   :error nil
   :version 0
   :allowed-origins []
   :runtime-error nil
   :external nil
   :dirty? false
   :ready? false
   :notice nil
   :show-source? false
   :pending-version nil
   :fullscreen? false
   :fetched-url nil})

(defn begin-load
  [state url]
  (assoc state
         :status :loading
         :error nil
         :runtime-error nil
         :fetched-url url))

(defn load-error
  [state error]
  (assoc state
         :status :error
         :error error
         :ready? false))

(defn content->state
  "Install a source as the active preview and reset permissions.

  Resetting `allowed-origins` on every new source is deliberate: a repaired
  version must not inherit a broader network grant from the version the user
  previously inspected."
  [state html]
  (let [html (str (or html ""))
        origins (sandbox/external-origins html)]
    (-> state
        (assoc :status :ready
               :html html
               :error nil
               :runtime-error nil
               :allowed-origins []
               :pending-origins origins
               :dirty? false
               :ready? false
               :notice nil
               :pending-version nil
               :version (inc (long (or (:version state) 0)))))))

(defn load-success
  [state html]
  (content->state state html))

(defn content-arrived
  "Handle a new `html` prop.

  If the user has typed in the preview or the source is still streaming, the
  new source waits behind an explicit switch.  Otherwise it replaces the
  current preview."
  [state html streaming?]
  (let [html (str (or html ""))]
    (cond
      (str/blank? html) state
      (= html (:html state)) state
      (nil? (:html state)) (load-success state html)
      ;; While tokens are still arriving, hold the latest document without
      ;; remounting the iframe on every chunk.  The effect runs again with
      ;; `streaming?` false when generation ends.
      (true? streaming?) (assoc state :pending-version html)
      (:dirty? state) (assoc state :pending-version html
                             :notice "预览有新版本；切换会重新加载并可能丢失已填内容。")
      :else (load-success state html))))

(defn accept-pending-version
  [state]
  (if-let [html (:pending-version state)]
    (load-success state html)
    state))

(defn dismiss-pending-version
  [state]
  (assoc state :pending-version nil :notice nil))

(defn mark-input
  [state]
  (assoc state :dirty? true))

(defn mark-ready
  [state]
  (assoc state :ready? true))

(defn runtime-error
  [state error]
  (assoc state :runtime-error error))

(defn allow-origins
  [state origins]
  (let [allowed (->> (concat (:allowed-origins state) origins)
                     (keep #(sandbox/normalize-origin %))
                     distinct
                     sort
                     vec)]
    (assoc state :allowed-origins allowed
           :notice "已允许所选域名联网；预览将重新加载。")))

(defn pending-origins
  [state]
  (let [allowed (set (:allowed-origins state))]
    (->> (sandbox/external-origins (:html state))
         (remove allowed)
         vec)))

(defn external-request
  "Normalize a bridge event into a user-confirmable request."
  [kind payload]
  (let [url (str (or (:url payload) ""))
        filename (str (or (:filename payload) ""))
        data (:data payload)
        supported? (sandbox/http-url? url)]
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
                         "表单目标不是 http/https；不会自动提交。")}
      :model {:kind :model
              :reason "预览或小应用不能直接调用模型；请在聊天输入区明确提出请求。"}
      {:kind (or kind :unknown)
       :reason "此动作需要用户确认。"})))

(defn request-external
  [state kind payload]
  (assoc state :external (external-request kind payload)))

(defn clear-external
  [state]
  (assoc state :external nil))

(defn toggle-source
  [state]
  (update state :show-source? not))

(defn toggle-fullscreen
  [state]
  (update state :fullscreen? not))

(defn close-fullscreen
  [state]
  (assoc state :fullscreen? false))

(defn notice
  [state tone text]
  (assoc state :notice {:tone tone :text text}))

(defn clear-notice
  [state]
  (assoc state :notice nil))

(defn error-summary
  [error]
  (cond
    (nil? error) nil
    (map? error) (str (or (:code (or (:error error) error)) "ERROR")
                      " · "
                      (or (:message (or (:error error) error))
                          (:message error)
                          "预览加载失败"))
    :else (str error)))

(defn source-bytes
  [state]
  (let [html (str (or (:html state) ""))]
    #?(:clj (alength (.getBytes html "UTF-8"))
       :cljs (let [encoder (when (exists? js/TextEncoder) (js/TextEncoder.))]
               (if encoder
                 (.-length (.encode encoder html))
                 (count html))))))

(defn source-size-label
  [state]
  (let [bytes (source-bytes state)]
    (cond
      (< bytes 1024) (str bytes " B")
      (< bytes (* 1024 1024))
      (let [tenths (quot (+ (* bytes 10) 512) 1024)]
        (str (quot tenths 10) "." (rem tenths 10) " KiB"))
      :else
      (let [tenths (quot (+ (* bytes 10) (* 512 1024)) (* 1024 1024))]
        (str (quot tenths 10) "." (rem tenths 10) " MiB")))))

(defn fetch-url
  "Resolve a preview source to an HTTP URL.

  `:url` wins.  A bare `:content-id` becomes the immutable blob route.  The
  client never fabricates a workspace path from a name."
  [{:keys [url content-id]}]
  (or (when (and (string? url) (not (str/blank? url))) url)
      (when (and (string? content-id) (not (str/blank? content-id)))
        (str "/blobs/" content-id))))
