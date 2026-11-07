(ns hc.client.conversation.attachments.http
  "Browser upload pipeline for the attachment queue.

  Bytes go to the independent `POST /attachments` route, never through Sente.
  The pipeline only updates the queue: a failed upload becomes a visible
  `:failed` card, and the user's text draft is never cleared or replaced.
  Local limits are checked before any network call; the server re-checks them."
  (:require [cljs.reader :as reader]
            [clojure.string :as str]
            [hc.client.conversation.attachments :as attachments]))

(defn file-info
  "Project a browser File/Blob into the queue's add request."
  [file]
  {:file file
   :name (or (.-name file) "attachment")
   :mime (let [mime (.-type file)]
           (when (and (string? mime) (not (str/blank? mime))) mime))
   :size (.-size file)
   :last-modified (.-lastModified file)
   :source :local-import})

(defn file-list->vector
  [file-list]
  (vec (array-seq file-list)))

(defn- parse-body
  [text]
  (try
    (reader/read-string text)
    (catch :default _ nil)))

(defn- status-error
  [status body text]
  (cond
    (and (map? body) (:error body))
    {:code (:error body)
     :message (or (:message body) "附件上传失败")
     :details (:details body)
     :status status}

    (= 413 status)
    {:code :ATTACHMENT_TOO_LARGE
     :message (or (not-empty text) "单个附件最大 100 MiB")
     :status status}

    (= 409 status)
    {:code :ATTACHMENT_LIMIT_EXCEEDED
     :message (or (not-empty text) "单条草稿最多 20 个附件")
     :status status}

    (= 503 status)
    {:code :ATTACHMENT_STORE_UNAVAILABLE
     :message (or (not-empty text) "附件存储暂不可用")
     :status status}

    :else
    {:code :ATTACHMENT_UPLOAD_FAILED
     :message (or (not-empty text) (str "附件上传失败（HTTP " status "）"))
     :status status}))

(defn upload-file!
  "Upload one File/Blob for ITEM.  Returns a Promise of
  `{:ok? true :attachment descriptor}` or `{:ok? false :error {...}}`.

  `:base-url` is optional; the browser normally uses the same origin and a
  relative URL.  Tests and non-browser harnesses can supply an absolute one."
  ([queue item file] (upload-file! queue item file {}))
  ([queue item file {:keys [base-url]}]
  (if-not (exists? js/fetch)
    (js/Promise.resolve
     {:ok? false
      :error {:code :NETWORK_UNAVAILABLE
              :message "当前环境没有可用的网络请求能力"}})
    (let [name (js/encodeURIComponent (or (:name item) "attachment"))
          url (str (or base-url "") "/attachments?name=" name
                   "&draft-count=" (attachments/total queue))
          mime (or (:mime item) "application/octet-stream")
          options #js {:method "POST"
                       :headers #js {"content-type" mime}
                       :body file}]
      (-> (js/fetch url options)
          (.then (fn [response]
                   (-> (.text response)
                       (.then (fn [text]
                                (let [body (parse-body text)]
                                  (if (and (.-ok response)
                                           (map? body)
                                           (true? (:ok? body))
                                           (map? (:attachment body)))
                                    {:ok? true
                                     :attachment (:attachment body)
                                     :status (.-status response)}
                                    {:ok? false
                                     :error (status-error (.-status response)
                                                          body text)})))))))
          (.catch (fn [error]
                    {:ok? false
                     :error {:code :NETWORK_FAILED
                             :message (or (.-message error)
                                          "附件上传网络错误")}})))))))

(defn- notify!
  [opts tone text]
  (when-let [f (:on-notice! opts)]
    (f {:tone tone :text text})))

(defn- apply-upload-result
  [queue item result]
  (let [next-queue (if (:ok? result)
                     (attachments/mark-uploaded queue (:id item) (:attachment result))
                     (attachments/mark-failed queue (:id item) (:error result)))]
    ;; Unknown model capability is not a yes.  The upload pipeline applies the
    ;; queue's current model so an image/PDF with no confirmed capability stays
    ;; visibly blocked instead of being sent and rejected after draft clearing.
    (attachments/apply-model next-queue (:model queue))))

(defn upload-item!
  "Upload one already-added queue item and return a Promise of the next queue."
  [queue item file opts]
  (-> (upload-file! queue item file opts)
      (.then (fn [result]
               (let [next-queue (apply-upload-result queue item result)]
                 (when-let [f (:on-queue-change! opts)]
                   (f next-queue))
                 (when-not (:ok? result)
                   (notify! opts :danger
                            (str (:name item) "：" (get-in result [:error :message]))))
                 next-queue)))))

(defn add-files-and-upload!
  "Add FILES to QUEUE, enforce local limits, then upload each accepted file.

  Returns a Promise of `{:queue final-queue :added n :refused [errors]}`.
  `on-queue-change!` receives the queue immediately after the local add and
  again after every upload settles, so cards show `:adding` then `:ready` or
  `:failed`.  No draft text or draft version is touched."
  [queue files {:keys [on-queue-change!] :as opts}]
  (let [files (vec (or files []))
        {:keys [queue accepted refused]}
        (reduce (fn [{:keys [queue accepted refused]} file]
                  (let [info (file-info file)
                        result (attachments/add-file queue info)]
                    (if (:ok? result)
                      {:queue (:queue result)
                       :accepted (conj accepted {:item (:item result)
                                                 :file file
                                                 :duplicate-of (:duplicate-of result)})
                       :refused refused}
                      {:queue queue
                       :accepted accepted
                       :refused (conj refused
                                      (assoc (:error result)
                                             :file (:name info)))})))
                {:queue queue :accepted [] :refused []}
                files)]
    (when-let [f on-queue-change!]
      (f queue))
    (when (seq refused)
      (notify! opts :warn
               (str "已加入 " (count accepted) " 个，"
                    (count refused) " 个需要处理")))
    (when-let [duplicate (some :duplicate-of accepted)]
      (notify! opts :info (str "此草稿中已有同名附件（" duplicate "）；仍保留本次副本。")))
    (-> (reduce (fn [promise {:keys [item file]}]
                  (.then promise
                         (fn [current]
                           (upload-item! current item file opts))))
                (js/Promise.resolve queue)
                accepted)
        (.then (fn [final-queue]
                 {:queue final-queue
                  :added (count accepted)
                  :refused (vec refused)})))))

(defn retry-item!
  "Reset one failed card and upload it again.  Returns a Promise of the queue."
  [queue item-id file opts]
  (let [queue (attachments/retry queue item-id)
        item (some #(when (= item-id (:id %)) %) (attachments/items queue))]
    (when-let [f (:on-queue-change! opts)]
      (f queue))
    (if (and item file)
      (upload-item! queue item file opts)
      (js/Promise.resolve queue))))

(defn- extraction-attachment
  [card]
  (select-keys card [:id :blob-id :name :mime :size :local-copy? :workspace]))

(defn- extraction-failure
  [body]
  (or (get-in body [:details :extraction])
      {:status :failed
       :error {:code (or (:error body) :EXTRACTION_FAILED)
               :message (or (:message body) "文字提取失败")}
       :pages (get-in body [:details :pages])}))

(defn extract!
  "Run an explicit operator-chosen extraction on an uploaded attachment.

  Sends metadata only to `POST /attachments/extract`; the server reads the
  local copy.  Returns a Promise of
  `{:ok? bool :queue next-queue :extraction ... :error ...}`.  A failed or
  empty extraction is recorded as a failed/needs-choice card, never as text."
  ([queue card selection] (extract! queue card selection {}))
  ([queue card selection {:keys [base-url password]}]
   (if-not (exists? js/fetch)
     (js/Promise.resolve
      {:ok? false
       :queue (attachments/mark-extracted
               queue (:id card)
               {:status :failed
                :error {:code :NETWORK_UNAVAILABLE
                        :message "当前环境没有可用的网络请求能力"}})
       :error {:code :NETWORK_UNAVAILABLE
               :message "当前环境没有可用的网络请求能力"}})
     (let [url (str (or base-url "") "/attachments/extract")
           body (pr-str (cond-> {:attachment (extraction-attachment card)
                                 :selection selection}
                          (string? password) (assoc :password password)))
           options #js {:method "POST"
                        :headers #js {"content-type" "application/edn"}
                        :body body}]
       (-> (js/fetch url options)
           (.then (fn [response]
                    (-> (.text response)
                        (.then (fn [text]
                                 (let [parsed (parse-body text)]
                                   (if (and (map? parsed) (true? (:ok? parsed)))
                                     (let [extraction (:extraction parsed)
                                           queue' (-> (attachments/mark-extracted
                                                       queue (:id card) extraction)
                                                      (attachments/update-item
                                                       (:id card)
                                                       {:preferred-route :text})
                                                      (attachments/apply-model
                                                       (:model queue)))]
                                       {:ok? true
                                        :queue queue'
                                        :extraction extraction
                                        :attachment (:attachment parsed)
                                        :status (.-status response)})
                                     (let [extraction (extraction-failure parsed)
                                           queue' (-> (attachments/mark-extracted
                                                       queue (:id card) extraction)
                                                      (attachments/apply-model
                                                       (:model queue)))]
                                       {:ok? false
                                        :queue queue'
                                        :extraction extraction
                                        :error (or (:error extraction)
                                                   {:code :EXTRACTION_FAILED
                                                    :message "文字提取失败"})
                                        :status (.-status response)}))))))))
           (.catch (fn [error]
                     (let [failure {:code :NETWORK_FAILED
                                    :message (or (.-message error)
                                                 "文字提取网络错误")}]
                       {:ok? false
                        :queue (attachments/mark-extracted
                                queue (:id card)
                                {:status :failed :error failure})
                        :error failure}))))))))

(defn extract-item!
  "Callback adapter for explicit extraction.

  Same request as `extract!`, but accepts a queue item id, calls
  `:on-queue-change!` with the next queue and reports failures through
  `:on-notice!`.  Returns a Promise of the next queue."
  [queue item-id selection {:keys [base-url password on-queue-change!]
                            :as opts}]
  (let [item (some #(when (= item-id (:id %)) %) (attachments/items queue))]
    (cond
      (nil? item)
      (js/Promise.resolve queue)

      (not (map? selection))
      (do
        (notify! opts :warn "请选择文字提取方式")
        (js/Promise.resolve queue))

      (not (exists? js/fetch))
      (do
        (notify! opts :danger "当前环境没有可用的网络请求能力")
        (js/Promise.resolve queue))

      :else
      (let [attachment (select-keys item
                                    [:attachment-id :id :name :mime :size
                                     :blob-id :send-blob-id :send-mime
                                     :local-copy? :send-route :send-text
                                     :media :workspace])
            body (pr-str (cond-> {:attachment attachment
                                  :selection selection}
                           (string? password) (assoc :password password)))
            url (str (or base-url "") "/attachments/extract")
            options #js {:method "POST"
                         :headers #js {"content-type" "application/edn"}
                         :body body}]
        (-> (js/fetch url options)
            (.then (fn [response]
                     (-> (.text response)
                         (.then (fn [text]
                                  (let [parsed (parse-body text)
                                        ok? (and (.-ok response)
                                                 (map? parsed)
                                                 (true? (:ok? parsed)))
                                        extraction (when ok? (:extraction parsed))
                                        error (when-not ok?
                                                (status-error (.-status response)
                                                              parsed text))
                                        extraction (or extraction
                                                       {:status :failed
                                                        :error error})
                                        next-queue (-> (attachments/mark-extracted
                                                        queue item-id extraction)
                                                       (cond-> ok?
                                                         (attachments/update-item
                                                          item-id
                                                          {:preferred-route :text}))
                                                       (attachments/apply-model
                                                        (:model queue)))]
                                    (when-let [f on-queue-change!]
                                      (f next-queue))
                                    (when-not (= :ready (keyword (:status extraction)))
                                      (notify! opts :warn
                                               (or (get-in extraction [:error :message])
                                                   (:message error)
                                                   "文字提取未完成")))
                                    next-queue))))))
            (.catch (fn [error]
                      (notify! opts :danger
                               (or (.-message error) "文字提取网络错误"))
                      queue)))))))

(defn associate-workspace!
  "Associate one relative workspace file as a live reference.

  Sends only the relative path.  The server stamps the real file and returns
  a descriptor with `:local-copy? false`; the draft policy still requires a
  saved copy before an original file may travel.  Returns a Promise of
  `{:ok? true :attachment descriptor :queue next-queue}` or
  `{:ok? false :error {...} :queue queue}`."
  ([queue relative-path] (associate-workspace! queue relative-path {}))
  ([queue relative-path {:keys [base-url on-queue-change!]}]
   (if-not (exists? js/fetch)
     (js/Promise.resolve
      {:ok? false
       :queue queue
       :error {:code :NETWORK_UNAVAILABLE
               :message "当前环境没有可用的网络请求能力"}})
     (let [body (pr-str {:relative-path relative-path
                         :snapshot? false})
           url (str (or base-url "") "/attachments/workspace")
           options #js {:method "POST"
                        :headers #js {"content-type" "application/edn"}
                        :body body}]
       (-> (js/fetch url options)
           (.then (fn [response]
                    (-> (.text response)
                        (.then (fn [text]
                                 (let [parsed (parse-body text)
                                       ok? (and (.-ok response)
                                                (map? parsed)
                                                (true? (:ok? parsed)))
                                       attachment (:attachment parsed)
                                       error (when-not ok?
                                               (status-error (.-status response)
                                                             parsed text))
                                       next-queue (if (and ok? (map? attachment))
                                                    (attachments/merge-draft
                                                     queue {:attachments [attachment]})
                                                    queue)]
                                   (when (fn? on-queue-change!)
                                     (on-queue-change! next-queue))
                                   (if ok?
                                     {:ok? true
                                      :attachment attachment
                                      :queue next-queue}
                                     {:ok? false
                                      :error error
                                      :queue queue})))))))
           (.catch (fn [error]
                     {:ok? false
                      :queue queue
                      :error {:code :NETWORK_FAILED
                              :message (or (.-message error)
                                           "工作区关联网络错误")}})))))))
