(ns hc.client.search.history
  "Pure state and projection for global history search.

  The server owns the rebuildable full-text index and validates every hit
  against the authoritative session record.  This namespace only projects
  confirmed command results into display state; it never synthesizes a hit,
  a session title, or a truncation flag.

  Scope is the whole visible history.  A hit keeps the server's
  `:session-id`, `:message-id`, `:version-id`, `:snippet` and
  `:historical-version?` because the click path must name the exact version it
  will open.  A deleted or missing session is reported as unavailable instead
  of being replaced by a same-titled session."
  (:require [clojure.string :as str]))

(def initial-state
  "The panel starts closed and idle.  `:filters` is an extra key so one patch
  can remove exactly one condition without hiding another."
  {:open? false
   :query ""
   :mode :history
   :status :idle
   :hits []
   :count 0
   :truncated? false
   :error nil
   :selected-index 0
   :filters {}
   :index nil})

(def filter-keys
  [:assistant-id :status :from-ms :to-ms :include-versions? :has-attachments?])

(def error-labels
  "User-facing Chinese reasons for the codes the command surface can return.
  Unknown codes keep the server message; neither is replaced by a claim of
  success."
  {:SEARCH_QUERY_INVALID "搜索词不能为空"
   :SEARCH_LIMIT_INVALID "搜索条数上限无效"
   :SEARCH_SESSION_INVALID "会话标识无效"
   :HISTORY_SEARCH_FAILED "历史索引查询失败"
   :HISTORY_SEARCH_STATUS_FAILED "索引状态读取失败"
   :REINDEX_REQUIRES_TRUSTED_CALLER "索引重建只允许服务器内部调用"
   :connection-not-ready "实时连接尚未就绪，无法搜索"
   :command-epoch-unknown "命令纪元尚未发布，请等待同步后重试"
   :transport-not-open "实时通道未打开，搜索未发送"
   :query-invalid "搜索词不能为空"})

(defn open
  [state]
  (assoc state :open? true))

(defn close
  [state]
  (assoc state :open? false))

(defn open?
  [state]
  (boolean (:open? state)))

(defn set-query
  [state query]
  (let [query (if (string? query) query "")
        stale? (and (contains? state :result-query)
                    (not= (str/trim query) (:result-query state)))]
    (cond-> (assoc state :query query)
      ;; Hits belong to the query that produced them.  Editing the words must
      ;; not leave old hits looking like an answer to the new words.
      stale? (assoc :status :idle
                    :hits []
                    :count 0
                    :truncated? false
                    :error nil
                    :selected-index 0
                    :result-query nil))))

;; ---------------------------------------------------------------------------
;; Filters

(defn- non-blank-string
  [value]
  (when (and (string? value) (not (str/blank? value)))
    (str/trim value)))

(defn- normalize-status
  [value]
  (cond
    (keyword? value) value
    (string? value) (when-let [value (non-blank-string value)]
                      (keyword value))
    :else nil))

(defn- normalize-filter
  [k value]
  (case k
    :assistant-id (non-blank-string value)
    :status (normalize-status value)
    :from-ms (when (number? value) (long value))
    :to-ms (when (number? value) (long value))
    :include-versions? (when (some? value) (boolean value))
    :has-attachments? (when (some? value) (boolean value))
    nil))

(defn set-filters
  "Merge FILTERS into the current conditions.

  A nil value removes that condition, so no hidden predicate survives a
  cleared chip.  The query is never touched here: changing a filter must not
  erase the words the operator is about to search."
  [state filters]
  (let [filters (if (map? filters) filters {})
        next (reduce-kv
              (fn [acc k value]
                (if-not (contains? (set filter-keys) k)
                  acc
                  (let [value (normalize-filter k value)]
                    (if (nil? value)
                      (dissoc acc k)
                      (assoc acc k value)))))
              (or (:filters state) {})
              filters)]
    (if (= next (or (:filters state) {}))
      (assoc state :filters next)
      ;; A different condition set cannot keep showing hits produced under the
      ;; previous conditions.  The query itself stays.
      (assoc state
             :filters next
             :status :idle
             :hits []
             :count 0
             :truncated? false
             :error nil
             :selected-index 0
             :result-query nil))))

(defn clear-filters
  "Remove every condition while keeping the query."
  [state]
  (set-filters state (zipmap filter-keys (repeat nil))))

;; ---------------------------------------------------------------------------
;; Results

(defn- normalize-hit
  [hit]
  (when (map? hit)
    (let [session-id (non-blank-string (:session-id hit))
          message-id (non-blank-string (:message-id hit))]
      (when (and session-id message-id)
        (assoc hit
               :session-id session-id
               :message-id message-id
               :snippet (or (:snippet hit) "")
               :historical-version? (true? (:historical-version? hit)))))))

(defn begin-search
  "Enter the loading phase and drop the previous result set.  A stale hit
  must not look like an answer to the new query."
  [state]
  (assoc state
         :status :loading
         :hits []
         :count 0
         :truncated? false
         :error nil
         :selected-index 0
         :result-query nil))

(defn apply-result
  "Project one successful `:history/search` payload into display state.

  Hits without a session and message identity are dropped rather than
  invented.  `:count` and `:truncated?` come from the server unchanged;
  the client never hides a truncated result set."
  [state payload]
  (let [payload (if (map? payload) payload {})
        hits (vec (keep normalize-hit (:hits payload)))
        query (or (non-blank-string (:query payload)) (:query state ""))
        current-query (str/trim (or (:query state) ""))
        count (if (and (integer? (:count payload))
                       (not (neg? (:count payload))))
                (:count payload)
                (count hits))]
    (if (and (not (str/blank? current-query))
             (not= current-query query))
      ;; A late answer for words the operator already replaced must not become
      ;; the result of the new query.
      (assoc state :status :idle :result-query nil)
      (assoc state
             :query query
             :result-query query
             :status :loaded
             :hits hits
             :count count
             :truncated? (true? (:truncated? payload))
             :error nil
             :selected-index 0))))

(defn normalize-error
  [error]
  (cond
    (map? error) error
    (string? error) {:message error}
    (keyword? error) {:code error}
    :else {:message "历史搜索失败"}))

(defn apply-error
  "Enter the error phase.  Confirmed hits are cleared because they no longer
  describe the failed query; the reason stays available for the view and the
  retry control."
  [state error]
  (assoc state
         :status :error
         :hits []
         :count 0
         :truncated? false
         :error (normalize-error error)
         :selected-index 0
         :result-query nil))

(defn error-label
  [error]
  (let [error (normalize-error error)
        code (:code error)
        code (cond
               (keyword? code) code
               (string? code) (keyword code)
               :else nil)]
    (or (get error-labels code)
        (get error-labels (some-> code name str/lower-case keyword))
        (:message error)
        "历史搜索失败")))

(defn apply-index-status
  "Keep only the status facts the panel displays.  This never triggers a
  rebuild: `:history/search-status` is read-only."
  [state payload]
  (let [payload (if (map? payload) payload {})]
    (assoc state :index
           (select-keys payload [:state :generation :session-count
                                 :message-count :built-at :last-error]))))

(defn apply-index-error
  [state error]
  (assoc state :index {:state :error :error (normalize-error error)}))

(defn move-selection
  "Move the selected hit by DELTA without wrapping.  An empty list stays at
  index 0, and the first/last item is a hard boundary."
  [state delta]
  (let [total (count (:hits state))
        index (long (or (:selected-index state) 0))
        delta (long (or delta 0))]
    (if (zero? total)
      (assoc state :selected-index 0)
      (assoc state :selected-index
             (max 0 (min (dec total) (+ index delta)))))))

;; ---------------------------------------------------------------------------
;; Receipt envelopes

(defn- receipt-map
  [value]
  (if (and (map? value) (map? (:receipt value)))
    (:receipt value)
    value))

(defn- result-map
  [value]
  (let [receipt (receipt-map value)]
    (or (when (map? (:result receipt)) (:result receipt))
        receipt)))

(defn receipt-command-id
  [value]
  (when (map? value)
    (or (:command-id value)
        (:commandId value)
        (get-in value [:receipt :command-id]))))

(defn receipt-status
  "Return the normalized receipt status, whether it sits on the envelope, the
  receipt, or a nested handler result."
  [value]
  (when (map? value)
    (let [receipt (receipt-map value)
          result (result-map value)
          status (or (:status receipt) (:status result) (:status value))]
      (cond
        (keyword? status) status
        (string? status) (keyword (str/lower-case status))
        :else nil))))

(defn receipt-payload
  "Extract the history handler payload from one command receipt.

  The gateway may wrap a handler result as
  `{:receipt {:status :accepted :result {:status :ok :payload {..}}}}`, may
  keep the payload on the handler result, or may deliver the payload directly.
  A receipt envelope itself is never returned as a payload."
  [value]
  (when (map? value)
    (let [receipt (receipt-map value)
          result (result-map value)
          candidates [(when (map? result) (:payload result))
                      (when (map? receipt) (:payload receipt))
                      (when (and (map? result)
                                 (or (contains? result :hits)
                                     (contains? result :state)))
                        result)
                      (when (and (map? receipt)
                                 (or (contains? receipt :hits)
                                     (contains? receipt :state)))
                        receipt)]]
      (first (filter map? candidates)))))

(defn receipt-error
  "Return the command error carried by a receipt, including a handler-level
  `{:status :error}` nested inside an accepted command receipt."
  [value]
  (when (map? value)
    (let [receipt (receipt-map value)
          result (result-map value)]
      (or (when (false? (:ok? value)) (:error value))
          (when (= :error (:status result)) (:error result))
          (when (= :error (:status receipt)) (:error receipt))
          (:error value)
          (:error receipt)
          (when (false? (:ok? receipt)) (:error receipt))))))

;; ---------------------------------------------------------------------------
;; Context labels

(defn- now-ms
  []
  #?(:clj (System/currentTimeMillis)
     :cljs (.now js/Date)))

(defn- relative-time
  [at now]
  (if (and (number? at) (pos? at) (number? now))
    (let [delta (max 0 (- now (long at)))]
      (cond
        (< delta 60000) "刚刚"
        (< delta 3600000) (str (quot delta 60000) " 分钟前")
        (< delta 86400000) (str (quot delta 3600000) " 小时前")
        (< delta 2592000000) (str (quot delta 86400000) " 天前")
        :else (str (quot delta 2592000000) " 个月前")))
    "时间未知"))

(defn format-hit-context
  "Display context for one hit: assistant, relative time, and version
  identity.

  A historical version and the currently selected version carry different
  `:version-label` values; only the historical one exposes a
  `:version-badge`, so the view can mark it without implying the current
  version is historical."
  ([hit]
   (format-hit-context hit (now-ms)))
  ([hit now]
   (let [historical? (true? (:historical-version? hit))
         assistant (or (non-blank-string (:assistant-name hit))
                       (non-blank-string (:assistant-id hit))
                       "未知助手")]
     {:assistant assistant
      :time (relative-time (:created-at hit) now)
      :session-title (or (non-blank-string (:session-title hit)) "未命名会话")
      :version-id (:version-id hit)
      :version-label (if historical? "历史版本" "当前版本")
      :version-badge (when historical? "历史版本")})))

(defn index-status-label
  "Read-only index status text.  `:missing` is a fresh store, not an error."
  [index]
  (when index
    (let [state (:state index)
          state-label (case state
                        :ready "就绪"
                        :building "正在重建"
                        :missing "未建立"
                        :error "状态不可用"
                        "未知")]
      (str "索引：" state-label
           (when (some? (:generation index))
             (str " · generation " (:generation index)))
           (when (some? (:session-count index))
             (str " · 会话 " (:session-count index)))))))
