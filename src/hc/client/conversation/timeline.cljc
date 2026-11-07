(ns hc.client.conversation.timeline
  "Conversation timeline projection.

  The server owns messages.  This namespace turns the normalized display copy
  into ordered view items and keeps three presentation rules explicit:

  - a tool whose arguments are complete is not finished; only a terminal
    domain status closes the waiting display;
  - a stopped message keeps its text and tool records, and late tool results
    are labelled rather than hidden or folded into the answer;
  - an unclosed code fence, table, or display-math block is rendered as stable
    plain text until its closing delimiter arrives.

  No reasoning text is invented.  If the server did not supply a summary, the
  process item has no summary."
  (:require [clojure.string :as str]
            [hc.client.conversation.stream :as stream]))

;; ---------------------------------------------------------------------------
;; Field access

(defn- field
  "Read a plain key, its `:message/*` namespaced form, or a bare keyword.
  Workbench-created messages use `:message/role`, `:message/content`, and
  friends; server snapshots may use plain keys.  Both are display copies."
  [m k]
  (when (map? m)
    (let [n (name k)]
      (or (get m k)
          (get m (keyword "message" n))
          (get m (keyword n))))))

;; ---------------------------------------------------------------------------
;; Message ordering

(defn- message-id
  [message]
  (or (field message :id) (field message :message-id)))

(defn- sort-key
  [message]
  [(or (field message :created-at) (field message :at) (field message :seq) "")
   (str (message-id message))])

(defn ordered-messages
  "Return messages in display order.

  Accepts either a normalized database map with `:message/by-id` and
  `:message/order`, or an already ordered vector.  An order that does not
  cover every message exactly once falls back to a stable timestamp/id sort
  instead of dropping records."
  [messages]
  (cond
    (vector? messages) messages
    (sequential? messages) (vec messages)
    (map? messages)
    (let [by-id (:message/by-id messages)
          order (:message/order messages)
          order (when (sequential? order) (vec order))]
      (if (and (map? by-id)
               (seq order)
               (= (count order) (count by-id))
               (every? #(contains? by-id %) order)
               (= (count (set order)) (count order)))
        (mapv #(get by-id %) order)
        (->> (vals by-id)
             (sort-by sort-key)
             vec)))
    :else []))

;; ---------------------------------------------------------------------------
;; Text segments

(defn- fence-open
  [line]
  (when-let [[_ indent marker info]
             (re-matches #"^(\s*)(`{3,}|~{3,})\s*(.*)$" line)]
    {:indent (count indent)
     :char (subs marker 0 1)
     :length (count marker)
     :lang (str/trim info)}))

(defn- fence-close?
  [line {:keys [char length]}]
  (boolean
   (re-matches
    (re-pattern (str "^\\s*" (if (= char "`") "`" "~") "{" length ",}\\s*$"))
    line)))

(defn- math-open?
  [line]
  (let [trimmed (str/triml line)]
    (and (str/starts-with? trimmed "$$")
         (not (and (> (count trimmed) 4)
                   (str/ends-with? trimmed "$$"))))))

(defn- single-line-math
  [line]
  (let [trimmed (str/trim line)]
    (when (and (> (count trimmed) 4)
               (str/starts-with? trimmed "$$")
               (str/ends-with? trimmed "$$"))
      (subs trimmed 2 (- (count trimmed) 2)))))

(defn- math-close?
  [line]
  (= "$$" (str/trim line)))

(defn- table-line?
  [line]
  (str/starts-with? (str/triml line) "|"))

(defn- next-special
  [lines start]
  (loop [i start]
    (if (>= i (count lines))
      (count lines)
      (let [line (nth lines i)]
        (if (or (fence-open line)
                (math-open? line)
                (single-line-math line)
                (table-line? line))
          i
          (recur (inc i)))))))

(defn- join-lines
  [lines]
  (str/join "\n" lines))

(defn segment-markdown
  "Split message text into stable display segments.

  Segment types are `:text`, `:code`, `:math`, and `:table`.  A segment that
  has not seen its closing delimiter has `:closed? false`; the view should use
  a plain, copyable fallback for that segment instead of re-laying it out on
  every token."
  ([text] (segment-markdown text {}))
  ([text {:keys [streaming?]}]
  (let [text (or text "")]
    (if (str/blank? text)
      []
      (let [lines (str/split-lines text)
            n (count lines)
            trailing-newline? (str/ends-with? text "\n")]
        (loop [i 0 segments []]
          (if (>= i n)
            (if (and trailing-newline? (seq segments))
              (update-in segments [(dec (count segments)) :content] str "\n")
              segments)
            (let [line (nth lines i)]
              (cond
                (fence-open line)
                (let [fence (fence-open line)
                      end (loop [j (inc i)]
                            (if (>= j n)
                              nil
                              (if (fence-close? (nth lines j) fence)
                                j
                                (recur (inc j)))))
                      content (join-lines (subvec lines (inc i) (or end n)))
                      segment {:type :code
                               :lang (:lang fence)
                               :content content
                               :closed? (some? end)}]
                  (recur (if end (inc end) n) (conj segments segment)))

                (single-line-math line)
                (recur (inc i)
                       (conj segments {:type :math
                                       :content (single-line-math line)
                                       :closed? true}))

                (math-open? line)
                (let [end (loop [j (inc i)]
                            (if (>= j n)
                              nil
                              (if (math-close? (nth lines j))
                                j
                                (recur (inc j)))))
                      content (join-lines (subvec lines (inc i) (or end n)))]
                  (recur (if end (inc end) n)
                         (conj segments {:type :math
                                         :content content
                                         :closed? (some? end)})))

                (table-line? line)
                (let [end (loop [j (inc i)]
                            (if (or (>= j n) (not (table-line? (nth lines j))))
                              j
                              (recur (inc j))))
                      content (join-lines (subvec lines i end))]
                  (recur end
                         (conj segments {:type :table
                                         :content content
                                         :closed? (or (not streaming?)
                                                      (< end n))})))

                :else
                (let [end (next-special lines (inc i))
                      content (join-lines (subvec lines i end))]
                  (recur end
                         (conj segments {:type :text
                                         :content content
                                         :closed? true}))))))))))))

(defn copyable-code
  "Code text without fences, language labels, or line numbers."
  [segment]
  (:content segment))

;; ---------------------------------------------------------------------------
;; Process items

(defn- keywordize
  [value]
  (cond
    (keyword? value) value
    (string? value) (keyword (str/lower-case value))
    :else value))

(defn- tool-status-label
  [tool]
  (let [status (keywordize (field tool :status))]
    (cond
      (stream/tool-waiting? tool) "参数已完整，等待执行结果"
      (contains? #{:succeeded :success :completed :complete :done} status) "已完成"
      (contains? #{:failed :error :errored} status) "失败"
      (contains? #{:cancelled :canceled} status) "已取消"
      (contains? #{:outcome-unknown :unknown} status) "结果未确认"
      (contains? #{:running :pending :waiting :claimed :applying} status) "正在执行"
      :else "等待执行")))

(defn- process-kind
  [item]
  (keywordize (or (field item :kind) (field item :type) (field item :role))))

(defn- process-view
  [index item]
  (let [kind (process-kind item)
        tool? (contains? #{:tool :tool-call :function :call} kind)
        status (keywordize (field item :status))
        terminal? (if tool? (stream/tool-terminal? status) true)]
    {:id (or (field item :id) (field item :tool-call-id) (str "process-" index))
     :kind (or kind :activity)
     :title (or (field item :title) (field item :name) (field item :label)
                (case kind
                  :reasoning "推理摘要"
                  :search "搜索进展"
                  :tool "工具调用"
                  "过程"))
     :summary (field item :summary)
     :status status
     :status-label (if tool? (tool-status-label item) (field item :status-label))
     :arguments-complete? (boolean (field item :arguments-complete?))
     :waiting-for-result? (boolean (and tool? (stream/tool-waiting? item)))
     :terminal? terminal?
     :collapsed? (if (or (contains? item :collapsed?)
                         (contains? item :message/collapsed?))
                   (boolean (field item :collapsed?))
                   true)
     :result (field item :result)}))

(defn- process-items
  [message version-entry]
  (let [explicit (or (field version-entry :process)
                     (field version-entry :process-items)
                     (field message :process)
                     (field message :process-items)
                     [])
        ;; Server snapshots publish `:tool-records`; live workbench events
        ;; publish `:tool-calls`.  Both are display copies of the same thing.
        ;; The selected version is authoritative when it carries a vector,
        ;; including an empty one.
        tool-records (or (field version-entry :tool-records)
                         (field message :tool-records)
                         (field message :tool-calls))
        tool-calls (mapv #(assoc % :kind :tool) tool-records)
        reasoning (when-let [summary (or (field version-entry :reasoning-summary)
                                         (field version-entry :reasoning)
                                         (field message :reasoning-summary)
                                         (field message :reasoning))]
                    [{:kind :reasoning
                      :summary (when (string? summary) summary)
                      :title "推理摘要"}])
        search (when-let [progress (or (field version-entry :search-progress)
                                       (field message :search-progress))]
                 [{:kind :search
                   :summary (when (string? progress) progress)
                   :title "搜索进展"}])]
    (->> (concat explicit tool-calls reasoning search)
         (vec)
         (map-indexed process-view)
         vec)))

;; ---------------------------------------------------------------------------
;; Message projection

(defn- prefix-extension?
  [short long]
  (and (string? short)
       (string? long)
       (str/starts-with? long short)))

(defn- newest-text
  "Return the newest string among the message's display copies.

  The live publisher patches `:text` while `:content` can remain at the first
  version.  When one value extends the other by appending, the longer value is
  the current text.  A non-prefix pair is an edit, so the first value (the
  selected version) wins."
  [values]
  (let [strings (filter #(and (string? %) (not (str/blank? %))) values)]
    (reduce (fn [best candidate]
              (if (and (prefix-extension? best candidate)
                       (> (count candidate) (count best)))
                candidate
                best))
            (first strings)
            (rest strings))))

(defn- body-text
  [message]
  (let [values [(field message :content) (field message :text)
                (field message :body)
                (get message :message/content) (get message :message/text)]
        body (or (newest-text values)
                 (some #(when (some? %) %) values))]
    (cond
      (string? body) body
      (vector? body) (str/join
                      ""
                      (map (fn [part]
                             (cond
                               (string? part) part
                               (map? part) (or (:text part) (:content part) "")
                               :else (str part)))
                           body))
      (nil? body) ""
      :else (str body))))

(defn- role
  [message]
  (keywordize (or (field message :role) (field message :author-role) :assistant)))

(defn- role-label
  [role]
  (case role
    :user "你"
    :assistant "助手"
    :tool "工具"
    :activity "活动"
    :reasoning "推理"
    :system "系统"
    (name role)))

(defn- message-status-label
  [status]
  (case (keywordize status)
    :streaming "正在生成"
    :stopped "已停止"
    :cancelled "已停止"
    :canceled "已停止"
    :interrupted "已中断"
    :completed "已完成"
    :complete "已完成"
    :error "未完成"
    :failed "未完成"
    nil))

(defn- attachment-view
  [attachment]
  {:id (or (field attachment :id)
           (field attachment :attachment-id)
           (field attachment :blob-id))
   :name (or (field attachment :name) (field attachment :filename) "附件")
   :mime (field attachment :mime)
   :size (field attachment :size)
   :url (field attachment :url)
   :status (field attachment :status)
   :preview? (boolean (or (field attachment :preview?)
                          (str/starts-with? (str (field attachment :mime)) "image/")))})

(defn message-view
  "Project one message for the conversation surface.

  Options:

    :streaming?        the run currently producing this message
    :late-tool-results results that arrived after the stop
  "
  ([message] (message-view message {}))
  ([message {:keys [streaming? late-tool-results]}]
   (let [id (message-id message)
         role (role message)
         status (keywordize (field message :status))
         body (body-text message)
         stopped? (contains? #{:stopped :cancelled :canceled :interrupted} status)
         author (or (field message :author) (field message :author-name)
                    (field message :assistant-name))
         author (if (string? author) author (role-label role))
         versions (vec (field message :versions))
         current-version (field message :current-version)
         version-entry (when (and (integer? current-version)
                                  (< -1 current-version (count versions)))
                         (get versions current-version))
         sources (or (field message :sources)
                     (:sources version-entry)
                     [])
         citations (or (field message :citations)
                       (:citations version-entry)
                       [])
         ;; A version snapshot is the authority for the selected version.
         ;; The message-level mirror can lag behind it (for example when a
         ;; snapshot changes :current-version without rewriting the old
         ;; display copy), and an empty vector is a real empty attachment
         ;; set, not a missing value.
         attachment-refs (vec (or (field version-entry :attachments)
                                  (field message :attachment-refs)
                                  (field message :attachments)
                                  []))]
     {:id id
      :role role
      :author author
      :author-label (role-label role)
      :body body
      :segments (segment-markdown body {:streaming? (or streaming?
                                                        (= :streaming status))})
      :status status
      :status-label (or (message-status-label status)
                        (when streaming? "正在生成"))
      :stopped? stopped?
      :streaming? (boolean (or streaming? (= :streaming status)))
      :process (process-items message version-entry)
      :attachments (mapv attachment-view attachment-refs)
      ;; The display projection drops identity fields such as :blob-id and
      ;; :local-copy?.  The inline editor needs the raw references so it can
      ;; save a new version without rewriting or losing the ones it keeps.
      :attachment-refs attachment-refs
      :sources (vec sources)
      :citations (vec citations)
      :versions versions
      :current-version current-version
      :manual-edit? (boolean (or (field version-entry :manual-edit?)
                                 (field message :manual-edit?)))
      :usage (or (field version-entry :usage) (field message :usage))
      :generated-card (field message :generated-card)
      :late-tool-results (vec (or late-tool-results []))
      :error (field message :error)
      :copy-text body
      :operations (cond-> [:copy]
                    (= :assistant role) (conj :edit :regenerate :branch)
                    (= :user role) (conj :edit :branch))
      :aria-label (str author "消息"
                       (when-let [label (message-status-label status)]
                         (str "，" label)))})))
