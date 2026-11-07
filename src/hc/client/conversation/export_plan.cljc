(ns hc.client.conversation.export-plan
  "Pure export selection, cancellation, and long-image planning.

  The existing `hc.client.conversation.export` namespace owns Markdown
  rendering and credential redaction.  This namespace adds the pure layer
  the view can call before it touches the browser download adapter; it never
  reads the DOM and never writes a file.

  Selection defaults to the current selected version of each message.  Tool
  logs, hidden instructions, and secret fields are excluded unless the
  caller explicitly asks for the first two; secret values are always removed
  from the selected text.  A long image is planned as height-bounded
  segments; one message taller than the limit is an explicit error rather
  than a silently clipped image."
  (:require [clojure.string :as str]
            [hc.client.conversation.export :as export]
            [hc.client.copy.failures :as failures]))

;; ---------------------------------------------------------------------------
;; Selection

(defn- current-version
  [message]
  (let [versions (:versions message)
        selected (:selected-version message)]
    (if (seq versions)
      (or (some #(when (= selected (:id %)) %) versions)
          (last versions))
      message)))

(defn- version-entry
  [message version selected?]
  (-> (merge message version)
      (assoc :message-id (:id message)
             :version-id (:id version)
             :selected? selected?)))

(defn- expand-versions
  [message include-all?]
  (let [versions (:versions message)
        current (current-version message)]
    (if (and include-all? (seq versions))
      (mapv #(version-entry message % (= (:id %) (:id current))) versions)
      [(version-entry message current true)])))

(defn- safe-message
  [message]
  (let [text (or (:text message) "")]
    (-> (select-keys message
                     [:id :message-id :version-id :role :author :created-at
                      :source :kind :attachments :selected?])
        (assoc :text (export/redact-text text))
        (dissoc :secret :secrets :credentials :api-key :token))))

(defn- excluded-message?
  [message opts]
  (or (and (not (:include-tools? opts))
           (or (= :tool (:kind message)) (true? (:tool? message))))
      (and (not (:include-hidden-instructions? opts))
           (or (true? (:hidden? message))
               (true? (:hidden-instruction? message))))))

(defn select-messages
  "Select messages for one export.

  MODE is `:single`, `:multi`, or `:branch`.  Options:
  `:selected-id`, `:selected-ids`, `:branch-id`, `:include-all-versions?`,
  `:include-tools?`, `:include-hidden-instructions?`.

  Returns `{:ok? true :messages [...] :excluded {...} ...}` or an explicit
  error.  The current selected version is the default; all versions are only
  included when `:include-all-versions? true`."
  [mode messages opts]
  (let [opts (or opts {})
        messages (vec (or messages []))
        duplicates (->> messages
                        (map :id)
                        frequencies
                        (keep (fn [[id n]] (when (> n 1) id)))
                        vec)
        chosen (case mode
                 :single (when-let [id (:selected-id opts)]
                           (filterv #(= id (:id %)) messages))
                 :multi (let [ids (set (:selected-ids opts))]
                          (when (seq ids)
                            (filterv #(contains? ids (:id %)) messages)))
                 :branch (when-let [branch (:branch-id opts)]
                           (filterv #(= branch (:branch-id %)) messages))
                 nil)
        error (case mode
                :single (when (nil? (:selected-id opts)) :NO_SELECTION)
                :multi (when (empty? (:selected-ids opts)) :NO_SELECTION)
                :branch (when (nil? (:branch-id opts)) :NO_BRANCH)
                :UNKNOWN_MODE)]
    (cond
      (seq duplicates)
      {:ok? false
       :error {:code :DUPLICATE_MESSAGE_ID
               :message "导出消息 id 重复，无法稳定定位"
               :ids duplicates}}

      error {:ok? false :error {:code error :message "导出选择无效"}}

      (empty? chosen) {:ok? false :error {:code :EMPTY_SELECTION
                                          :message "没有可导出的消息"}}

      :else
      (let [excluded (atom {:tools 0 :hidden 0 :secrets 0})
            selected (vec
                      (mapcat (fn [message]
                                (cond
                                  (excluded-message? message opts)
                                  (do (swap! excluded update
                                             (if (or (= :tool (:kind message))
                                                     (true? (:tool? message)))
                                               :tools :hidden) inc)
                                      [])
                                  (true? (:secret? message))
                                  (do (swap! excluded update :secrets inc) [])
                                  :else
                                  (mapv safe-message
                                        (expand-versions
                                         message (:include-all-versions? opts)))))
                              chosen))]
        (if (empty? selected)
          {:ok? false :error {:code :EMPTY_SELECTION
                              :message "选择的消息都被排除"}}
          {:ok? true
           :mode mode
           :messages selected
           :excluded @excluded
           :include-all-versions? (boolean (:include-all-versions? opts))
           :selected-count (count selected)})))))

;; ---------------------------------------------------------------------------
;; Cancellation state machine

(def ^:private export-cancel-copy
  (delay (failures/failure-copy {:code :export-cancelled :context {}})))

(defn initial
  "Start an export in `:idle`, retaining the selection and preview settings."
  [selection preview]
  {:phase :idle
   :selection selection
   :preview preview
   :cancel-requested? false
   :error nil})

(defn begin
  [state]
  (if (= :idle (:phase state))
    {:ok? true :state (assoc state :phase :preparing)}
    {:ok? false :state state :error {:code :INVALID_EXPORT_PHASE
                                     :phase (:phase state)}}))

(defn start-writing
  [state]
  (if (= :preparing (:phase state))
    {:ok? true :state (assoc state :phase :writing)}
    {:ok? false :state state :error {:code :INVALID_EXPORT_PHASE
                                     :phase (:phase state)}}))

(defn cancel
  "Cancel an export.

  Cancellation takes effect only in `:preparing`.  In `:writing` it records
  the request and waits for the current write boundary; the state stays
  `:writing` until `complete` observes the request."
  [state]
  (case (:phase state)
    :preparing
    {:ok? true
     :state (assoc state :phase :cancelled
                   :cancel-requested? true
                   :copy @export-cancel-copy)
     :copy @export-cancel-copy}

    :writing
    {:ok? true
     :state (assoc state :cancel-requested? true
                   :copy @export-cancel-copy)
     :notice "已请求取消，等待当前写入边界。"
     :copy @export-cancel-copy}

    :idle
    {:ok? false :state state :error {:code :EXPORT_NOT_STARTED}}

    :cancelled
    {:ok? false :state state :error {:code :EXPORT_ALREADY_CANCELLED}}

    :done
    {:ok? false :state state :error {:code :EXPORT_ALREADY_DONE}}

    :failed
    {:ok? false :state state :error {:code :EXPORT_FAILED}}

    {:ok? false :state state :error {:code :INVALID_EXPORT_PHASE}}))

(defn complete
  "Finish a writing export.  A pending cancel wins at the write boundary."
  [state]
  (cond
    (not= :writing (:phase state))
    {:ok? false :state state :error {:code :INVALID_EXPORT_PHASE
                                     :phase (:phase state)}}

    (:cancel-requested? state)
    {:ok? true :state (assoc state :phase :cancelled
                             :copy @export-cancel-copy)
     :copy @export-cancel-copy}

    :else
    {:ok? true :state (assoc state :phase :done)}))

(defn fail
  "Mark the export failed while keeping selection and preview settings."
  [state error]
  {:ok? false
   :state (assoc state :phase :failed :error error)
   :error error})

;; ---------------------------------------------------------------------------
;; Long image planning

(defn estimate-message-height
  "Estimate one message's rendered height in logical pixels."
  [message opts]
  (if-let [height (:estimated-height message)]
    (long height)
    (let [opts (or opts {})
          text (str (or (:text message) ""))
          lines (max 1 (count (str/split-lines text)))
          attachments (count (or (:attachments message) []))]
      (+ 72
         (* 22 lines)
         (* 72 attachments)
         (if (:include-author? opts) 22 0)
         (if (:include-time? opts) 18 0)
         (if (:include-source? opts) 20 0)))))

(defn- segment
  [messages start-index max-height]
  (loop [remaining messages
         ids []
         height 0
         index start-index]
    (if-let [message (first remaining)]
      (let [h (long (:estimated-height message))]
        (if (and (seq ids) (> (+ height h) max-height))
          {:index index
           :message-ids ids
           :estimated-height height
           :next remaining
           :next-index (inc index)}
          (recur (rest remaining)
                 (conj ids (:id message))
                 (+ height h)
                 index)))
      {:index index
       :message-ids ids
       :estimated-height height
       :next nil
       :next-index (inc index)})))

(defn segment-plan
  "Plan a long image as height-bounded segments.

  Options: `:max-height` (default 4000), `:include-author?`, `:include-time?`,
  `:include-source?`, `:theme` (`:light`/`:dark`).

  Returns `{:ok? true :segments [...] :segment-count n :total-height h ...}`
  or `{:ok? false :error {:code :EMPTY_SELECTION|:SINGLE_MESSAGE_TOO_TALL}}`."
  [messages opts]
  (let [opts (or opts {})
        messages (vec (or messages []))
        max-height (long (or (:max-height opts) 4000))
        prepared (mapv #(assoc % :estimated-height
                                (estimate-message-height % opts))
                       messages)]
    (let [duplicates (->> messages
                          (map :id)
                          frequencies
                          (keep (fn [[id n]] (when (> n 1) id)))
                          vec)]
      (cond
        (seq duplicates)
        {:ok? false
         :error {:code :DUPLICATE_MESSAGE_ID
                 :message "消息 id 重复，无法稳定分段"
                 :ids duplicates}}

        (empty? messages)
        {:ok? false :error {:code :EMPTY_SELECTION
                            :message "没有可导出的消息"}}

        (some #(> (:estimated-height %) max-height) prepared)
      {:ok? false
       :error {:code :SINGLE_MESSAGE_TOO_TALL
               :message "单条消息超过长图最大高度；请降低字号或改用文本导出"
               :max-height max-height
               :message-id (some #(when (> (:estimated-height %) max-height)
                                    (:id %))
                                 prepared)}}

      :else
      (loop [remaining prepared
             index 0
             segments []]
        (if (empty? remaining)
          {:ok? true
           :segments segments
           :segment-count (count segments)
           :total-height (reduce + (map :estimated-height segments))
           :max-height max-height
           :options (select-keys opts [:theme :include-author? :include-time?
                                       :include-source?])}
          (let [s (segment remaining index max-height)]
            (recur (:next s) (:next-index s)
                   (conj segments (select-keys s
                                               [:index :message-ids
                                                :estimated-height]))))))))))

(defn preview-size
  "Estimate the long-image preview canvas size and approximate RGBA bytes."
  [plan opts]
  (let [opts (or opts {})
        width (long (or (:width opts) 800))
        height (long (or (:total-height plan) 0))
        segments (long (or (:segment-count plan) 0))]
    {:width width
     :height height
     :segments segments
     :pixels (* width height)
     :bytes (* width height 4)
     :theme (or (:theme opts) :light)}))
