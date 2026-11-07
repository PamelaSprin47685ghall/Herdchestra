(ns hc.client.sessions.controls
  "Pure action and receipt model for session metadata controls.

  The module owns no domain state.  It maps a normalized session summary and
  the newest tracked command to real `:session/*` command payloads, disabled
  reasons, and receipt labels.  The view calls the workbench command surface;
  a control is never a local toggle."
  (:require [clojure.string :as str]))

(def action-order
  [:rename :pin :archive :restore :delete :purge])

(def metadata-kinds
  {:rename :session/rename
   :pin :session/pin
   :archive :session/archive
   :restore :session/restore
   :delete :session/delete
   :purge :session/purge})

(def metadata-kind-set
  (set (vals metadata-kinds)))

(defn metadata-command
  "Return COMMAND only when it is one of the metadata kinds this module owns.
  A send/stop/history command must not disable or label these controls."
  [command]
  (when (contains? metadata-kind-set (:kind command))
    command))

(def pending-statuses
  #{:created :submitting :sent})

(def terminal-statuses
  #{:accepted :applied :rejected :failed :unconfirmed})

(defn latest-command
  "Return the newest tracked metadata command for SESSION-ID from the raw
  `:ui/command` table.  Unlike the shell status helper, this includes
  `:rejected` and `:failed` receipts so the controls can show them."
  [commands session-id]
  (->> (vals (or commands {}))
       (filter #(= session-id (:session-id %)))
       (filter #(contains? metadata-kind-set (:kind %)))
       (sort-by #(str (or (:created-at %) "")))
       last))

(defn pending?
  [command]
  (contains? pending-statuses (:status command)))

(defn accepted?
  [command]
  (contains? #{:accepted :applied} (:status command)))

(defn rejected?
  [command]
  (contains? #{:rejected :failed :unconfirmed} (:status command)))

(defn synced?
  [connection]
  (true? (:synced? connection)))

(defn- status-key
  [value]
  (cond
    (keyword? value) value
    (string? value) (keyword (str/lower-case value))
    :else value))

(defn archived?
  [session]
  (= :archived (status-key (:status session))))

(defn deleted?
  [session]
  (= :deleted (status-key (:status session))))

(defn summary
  "Normalize a raw session record or an existing summary for these controls.
  Raw `:session/list` receipts use `:session-id`; the workbench summary uses
  `:id`."
  [session]
  (when (map? session)
    {:id (or (:session-id session) (:id session))
     :title (or (:title session) (:name session) "未命名会话")
     :status (or (status-key (:status session)) :active)
     :pinned? (boolean (:pinned? session))
     :updated-at (or (:updated-at session) (:activity-at session))
     :created-at (:created-at session)
     :deleted-at (:deleted-at session)
     :model-label (or (:model-label session)
                      (some-> (:model-binding session) :display-name)
                      (some-> (:model session) :display-name))}))

(defn title-valid?
  [title]
  (let [title (str/trim (str (or title "")))]
    (and (not (str/blank? title))
         (<= (count title) 200))))

(defn action-label
  [action session]
  (case action
    :rename "重命名"
    :pin (if (:pinned? session) "取消置顶" "置顶")
    :archive "归档"
    :restore "恢复"
    :delete "删除"
    :purge "永久删除"
    "会话操作"))

(defn action-description
  [action session]
  (case action
    :rename "修改会话标题；服务器接受后当前快照才会更新。"
    :pin (if (:pinned? session)
           "取消置顶；列表顺序由服务器快照决定。"
           "置顶会话；列表顺序由服务器快照决定。")
    :archive "归档会话；归档后可恢复。"
    :restore "恢复已归档或已删除会话。"
    :delete "删除会话；普通删除可在 30 天内恢复，临时会话不进入已删除区。"
    :purge "永久删除已删除会话记录；不可恢复，且不会删除工作区文件。"
    ""))

(defn- base-action
  [action session]
  {:action action
   :kind (get metadata-kinds action)
   :label (action-label action session)
   :description (action-description action session)
   :danger? (contains? #{:delete :purge} action)
   :confirm? (contains? #{:delete :purge} action)})

(defn action-reason
  "Return a non-nil reason when ACTION must be disabled."
  [action session connection command]
  (let [status (:status session)]
    (cond
      (not (synced? connection))
      "尚未与服务器同步，命令不可提交"

      (nil? (:id session))
      "缺少会话标识"

      (pending? command)
      "上一条会话命令仍在等待服务器回执"

      (and (= action :restore)
           (not (contains? #{:archived :deleted} status)))
      "当前会话不需要恢复"

      (and (= action :archive) (contains? #{:archived :deleted} status))
      "已归档或已删除的会话不能再次归档"

      (and (contains? #{:pin :delete :rename} action)
           (= :deleted status))
      "已删除会话只能恢复或永久删除"

      (and (= action :purge) (not (deleted? session)))
      "只有已删除会话可以永久删除"

      :else nil)))

(defn action-enabled?
  [action session connection command]
  (nil? (action-reason action session connection command)))

(defn actions
  "Return the real actions for SESSION in display order.

  Archived sessions show restore instead of archive.  Deleted sessions show
  restore and permanent purge; the 30-day window is a default retention
  period, not a prerequisite for an explicit permanent deletion."
  [session connection command]
  (let [kinds (cond
                (deleted? session) [:restore :purge]
                (archived? session) [:rename :pin :restore :delete]
                :else [:rename :pin :archive :delete])]
    (mapv (fn [action]
            (let [reason (action-reason action session connection command)]
              (assoc (base-action action session)
                     :disabled? (some? reason)
                     :reason reason)))
          kinds)))

(defn command-payload
  "Build the server payload for ACTION, or nil when the input is invalid.

  `:rename` requires a non-blank title; the other actions carry only their
  canonical flags."
  [action session opts]
  (case action
    :rename (let [title (str/trim (str (or (:title opts) "")))]
              (when (title-valid? title) {:title title}))
    :pin {:pinned? (not (boolean (:pinned? session)))}
    :archive {}
    :restore {}
    :delete {}
    :purge {:confirm? true}
    nil))

(defn command-request
  "Build the request handed to the workbench command surface, or nil when
  the action has no valid payload.

  The exact session id travels with the action.  A deleted row is not the
  selected session, and its restore or purge must never retarget whatever
  session happens to be selected."
  [action session opts]
  (when-let [payload (command-payload action session opts)]
    {:session-id (:id session)
     :kind (get metadata-kinds action)
     :action action
     :payload payload}))

(defn command-status-label
  [command]
  (when command
    (or (:status-label command)
        (case (:status command)
          :created "已创建"
          :submitting "正在提交"
          :sent "已发送，等待服务器回执"
          :accepted "服务器已接受"
          :applied "服务器已应用"
          :rejected "服务器已拒绝"
          :failed "发送失败"
          :unconfirmed "回执未确认"
          "状态未知"))))

(defn command-status-tone
  [command]
  (cond
    (contains? #{:accepted :applied} (:status command)) :positive
    (contains? #{:rejected :failed :unconfirmed} (:status command)) :negative
    (contains? #{:submitting :sent :created} (:status command)) :pending
    :else :neutral))

(defn command-status-text
  "Human-readable receipt state.  A rejection includes the server message when
  one was supplied."
  [command]
  (when command
    (let [label (command-status-label command)]
      (if (rejected? command)
        (str label
             (when-let [message (:message command)]
               (str "：" message)))
        label))))

(defn list-command-payload
  [include-deleted?]
  {:kind :session/list
   :include-deleted? (boolean include-deleted?)})

(defn deleted-sessions
  "Return deleted sessions from a `:session/list` receipt, newest first."
  [sessions]
  (->> (or sessions [])
       (keep summary)
       (filter deleted?)
       (sort-by (fn [session]
                  (str (or (:updated-at session)
                           (:deleted-at session)
                           (:created-at session)
                           ""))))
       reverse
       vec))
