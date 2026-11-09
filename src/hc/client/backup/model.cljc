(ns hc.client.backup.model
  "Pure state, validation and receipt handling for encrypted backup/restore.

  The UI owns a form and a command outbox.  It never invents a successful
  backup or restore: only a server receipt changes `:server` or `:last-*`.
  Passwords stay in memory; no function here writes them to local storage.
  Failure text follows the 24-class contract in root AGENTS.md §7.7, and
  errors that cannot be classified stay honest instead of becoming `网络错误`."
  (:require [clojure.string :as str]
            [hc.client.backup.commands :as commands]
            [hc.client.copy.failures :as failures]))

;; ---------------------------------------------------------------------------
;; Category vocabulary.  Mirrors hc.server.backup.model/category-info.

(def category-order
  [:conversations :assistants :connections :tools :skills :workspaces
   :worldbooks :memories :appearance :schedules])

(def category-labels
  {:conversations "对话与附件"
   :assistants "助手配置"
   :connections "模型服务配置"
   :tools "工具与 MCP 配置"
   :skills "技能"
   :workspaces "工作区"
   :worldbooks "世界书"
   :memories "记忆与画像"
   :appearance "外观偏好"
   :schedules "任务配置"})

(def category-notes
  {:conversations "包含消息与已打包的附件；未打包附件会单独列出。"
   :assistants "同名不同内容默认保留两份，并加备份日期后缀。"
   :connections "密钥默认不包含；恢复后连接需要重新验证。"
   :tools "恢复后默认停用待审，不自动获得执行权限。"
   :skills "恢复后默认停用待审。"
   :workspaces "外部目录只恢复配置；恢复后需要重新定位，不自动授权。"
   :worldbooks "同名不同内容默认保留两份，并加备份日期后缀。"
   :memories "已归档或删除的记忆保持停用待审，不因旧备份复活。"
   :appearance "单例偏好；替换会覆盖当前外观设置。"
   :schedules "恢复后默认暂停，下一次运行时间清空。"})

(def default-category-selection
  (zipmap category-order (repeat true)))

(def default-restore-selection
  (zipmap category-order (repeat true)))

(def min-password-length 12)

(def target-kind-options
  "Ordered backup target kinds for the editor select."
  [[:webdav "WebDAV"] [:s3 "S3"] [:file "本地文件"]])

(def target-field-defs
  "Editor fields in display order.  Each entry lists the kinds that show
  it; `:kinds` stays on the row so the view can filter without a second
  table.  `:secret` is the S3 secret and `:password` the WebDAV password;
  both always leave the editor empty on edit."
  [{:key :remote-url :label "服务地址" :input :text
    :required? true :kinds #{:webdav}}
   {:key :endpoint :label "端点" :input :text
    :required? true :kinds #{:s3}}
   {:key :bucket :label "存储桶" :input :text
    :required? true :kinds #{:s3}}
   {:key :region :label "区域" :input :text
    :required? true :kinds #{:s3}}
   {:key :access-key :label "访问密钥" :input :text
    :required? true :kinds #{:s3}}
   {:key :secret :label "私有密钥" :input :password
    :required? true :kinds #{:s3}}
   {:key :prefix :label "路径前缀" :input :text
    :required? false :kinds #{:s3}}
   {:key :username :label "用户名" :input :text
    :required? false :kinds #{:webdav}}
   {:key :password :label "密码" :input :password
    :required? false :kinds #{:webdav}}
   {:key :directory :label "远端目录" :input :text
    :required? false :kinds #{:webdav :file}}
   {:key :set-default? :label "设为默认目标" :input :checkbox
    :required? false :kinds #{:webdav :s3 :file}}])

(def target-editor-fields
  "All editable target-editor keys, including `:kind`."
  #{:kind :endpoint :bucket :region :access-key :secret :prefix
    :remote-url :username :password :directory :set-default?})

(def default-target-editor
  "Blank backup-target editor form.  `:secret`/`:password` stay empty on
  edit: an empty value tells the server to keep the stored secret."
  {:open? false
   :target-id nil
   :kind :webdav
   :endpoint ""
   :bucket ""
   :region ""
   :access-key ""
   :secret ""
   :prefix ""
   :remote-url ""
   :username ""
   :password ""
   :directory ""
   :set-default? false})

(def default-form
  {:password ""
   :password-confirm ""
   ;; Monotonic token for the uncontrolled password fields.  Only an explicit
   ;; clear or a terminal receipt may write the empty value back into the
   ;; focused DOM input; ordinary renders must leave the operator's text,
   ;; caret, selection, and IME composition alone.
   :password-reset 0
   :password-visible? false
   :target-id nil
   :encrypt? true
   :include-secrets? false
   :confirm-plaintext-secrets? false
   :include-deleted? false
   :include-temp? false
   :include-cache? false
   :include-external-files? false
   :categories default-category-selection
   :restore-categories default-restore-selection
   :mode :merge
   :selected-backup-id nil
   :preview nil
   :preview-stale? false
   :resolutions {}
   :confirm-memory-reactivation? false
   :restore-confirm? false
   :delete-confirm-id nil
   :recovery-confirm-id nil
   :prune-confirm? false
   :maintenance nil
   :maintenance-request nil
   :message nil
   :copy-feedback nil
   :last-create nil
   :last-restore nil
   :verify-result nil
   :transport-available? false
   :target-editor default-target-editor
   :target-delete-id nil
   :target-test {:status :idle :detail nil :kind nil :target-id nil}
   :section :backup})

(defn initial-state
  "A disconnected backup workspace.  Passwords are empty and never persisted."
  []
  {:ui default-form
   :sync {:phase :disconnected :command-epoch nil}
   :server {:status nil
            :targets []
            :default-target-id nil
            :restore {:online? false :offline? true
                      :requires-quiescence? true}
            :maintenance nil
            :backups []
            :recovery-points []
            :listing-error nil
            :recovery-error nil}
   :commands {}
   :outbox []})

;; ---------------------------------------------------------------------------
;; Small helpers

(defn- action-type
  [x]
  (cond
    (keyword? x) x
    (string? x) (keyword x)
    :else x))

(defn- normalize-keyword
  [x]
  (cond
    (keyword? x) x
    (string? x) (keyword x)
    (symbol? x) (keyword (name x))
    :else x))

(defn- result
  ([state] {:state state :effects []})
  ([state effects] {:state state :effects (vec effects)}))

(defn- message
  ([state kind text] (message state kind text {}))
  ([state kind text details]
   (assoc-in state [:ui :message]
             (merge {:kind kind
                     :text text
                     :at (str (random-uuid))}
                    details))))

(defn dismiss-message
  [state]
  (assoc-in state [:ui :message] nil))

(defn- copy-feedback
  "Store clipboard progress next to the password controls instead of in the
  page-top message banner."
  [state status text code]
  (assoc-in state [:ui :copy-feedback]
            {:status status :message text :code code}))

(defn code-point-count
  "Count Unicode code points.  A surrogate pair is one user-visible
  character, matching the server's password length rule."
  [s]
  (count (re-seq #"(?s)[\uD800-\uDBFF][\uDC00-\uDFFF]|." (str s))))

(defn- whitespace-only?
  [s]
  (str/blank? (str s)))

(defn password-check
  "Validate a NEW encrypted-backup password.

  Leading and trailing spaces are meaningful and are never trimmed.  The
  check counts code points, so Chinese characters and emoji count once."
  [password confirmation]
  (let [password (str password)
        confirmation (str confirmation)
        n (code-point-count password)]
    (cond
      (empty? password)
      {:ok? false :length n :reason :required
       :message "请输入备份密码；密码不会被长期记住。"}

      (whitespace-only? password)
      {:ok? false :length n :reason :blank
       :message "密码不能只包含空格。"}

      (< n min-password-length)
      {:ok? false :length n :reason :too-short
       :message (str "密码至少需要 " min-password-length
                     " 个字符；支持中文与空格，首尾空格不会被去掉。")}

      (not= password confirmation)
      {:ok? false :length n :reason :mismatch
       :message "两次输入的密码不一致。"}

      :else
      {:ok? true :length n :reason nil})))

(defn encrypted-backup?
  [backup]
  (boolean (:encrypted? backup)))

(defn selected-categories
  [selection]
  (vec (filter #(get selection % false) category-order)))

(defn- toggle
  [m k]
  (assoc m k (not (get m k false))))

(defn- set-all
  [selection value]
  (zipmap category-order (repeat (boolean value))))

(defn- command-pending?
  [state]
  (boolean (seq (filter #(contains? #{:queued :submitting} (:status %))
                        (vals (:commands state))))))

(defn- progress-code
  [kind]
  (case kind
    :backup/status :backup/status-pending
    :backup/list :backup/list-pending
    :backup/create :backup/create-pending
    :backup/preview :backup/preview-pending
    :backup/verify :backup/verify-pending
    :backup/restore :backup/restore-pending
    :backup/delete :backup/delete-pending
    :backup/recovery-points :backup/recovery-pending
    :backup/restore-recovery-point :backup/recovery-restore-pending
    :backup/prune :backup/prune-pending
    :backup/maintenance-restore :backup/maintenance-pending
    :backup/maintenance-status :backup/maintenance-status-pending
    :backup/maintenance-restore-recovery-point :backup/maintenance-pending
    :backup/save-target :backup/save-target-pending
    :backup/test-target :backup/test-target-pending
    :backup/delete-target :backup/delete-target-pending
    :backup/pending))

(defn- submit
  "Queue KIND/PAYLOAD and attach a neutral progress message unless OPTS has
  `:silent? true`.  A refusal keeps the form and reports the exact reason."
  ([state kind payload]
   (submit state kind payload {}))
  ([state kind payload opts]
   (let [queued (commands/queue state kind payload)]
     (if (:ok? queued)
       (if (:silent? opts)
         (:state queued)
         (message (:state queued) :info
                  (case kind
                    :backup/status "正在读取备份状态…"
                    :backup/list "正在读取备份列表…"
                    :backup/create "正在取样并写入备份；完成前不会宣称已保存。"
                    :backup/preview "正在校验备份并生成预览；尚未写入任何资料。"
                    :backup/verify "正在校验备份完整性…"
                    :backup/restore "正在请求恢复；服务器会先建立本地恢复点。"
                    :backup/delete "正在删除所选备份…"
                    :backup/recovery-points "正在读取本地恢复点…"
                    :backup/restore-recovery-point "正在请求从恢复点回退…"
                    :backup/prune "正在按 7+4+3 清理恢复点…"
                    :backup/maintenance-restore "正在请求停机恢复；服务器确认停止写入前不会宣布成功。"
                    :backup/maintenance-status "正在核对维护状态…"
                    :backup/maintenance-restore-recovery-point "正在请求停机回退；服务器确认停止写入前不会宣布成功。"
                    :backup/save-target "正在保存备份目标…"
                    :backup/test-target "正在测试连接…"
                    :backup/delete-target "正在删除备份目标…"
                    "正在提交…")
                  {:code (progress-code kind)}))
       (message state :warning
                (or (get-in queued [:error :message])
                    "命令未提交；表单仍保留。")
                {:code (get-in queued [:error :code])})))))

(defn- refresh-status
  [state]
  (let [target-id (or (get-in state [:ui :target-id])
                      (get-in state [:server :default-target-id]))
        queued (commands/queue state :backup/status
                               (cond-> {}
                                 target-id (assoc :target-id target-id)))]
    (if (:ok? queued) (:state queued) state)))

(defn- redact-command-passwords
  "Remove the operator's password from command records that remain in the
  outbox bookkeeping.  The envelope is already removed when it is sent, but
  the command record survives until its receipt arrives and must not keep the
  secret.  The rest of the payload is untouched so the receipt still matches."
  [state]
  (update state :commands
          (fn [commands]
            (reduce-kv
             (fn [result command-id command]
               (let [payload (:payload command)]
                 (assoc result command-id
                        (if (and (map? payload)
                                 (or (contains? payload :password)
                                     (contains? payload :password-confirm)))
                          (-> command
                              (assoc :payload (dissoc payload
                                                      :password
                                                      :password-confirm))
                              (assoc :password-redacted? true))
                          command))))
             {}
             (or commands {})))))

(defn- clear-password
  [state]
  (-> state
      redact-command-passwords
      (assoc-in [:ui :password] "")
      (assoc-in [:ui :password-confirm] "")
      (assoc-in [:ui :copy-feedback] nil)
      (update-in [:ui :password-reset] (fnil inc 0))))

;; ---------------------------------------------------------------------------
;; Error contract

(def error-contract
  {:BACKUP_WRONG_PASSWORD
   {:title "备份密码不正确"
    :text "无法解锁此备份，当前资料没有改变。请重新输入密码。"
    :action "重新输入密码"}

   :BACKUP_FORMAT_INVALID
   {:title "导入格式不支持"
    :text "无法识别这份资料的格式。当前资料没有改变。"
    :action "查看支持范围"}

   :BACKUP_CONTAINER_INVALID
   {:title "导入格式不支持"
    :text "无法识别这份资料的容器。当前资料没有改变。"
    :action "查看支持范围"}

   :BACKUP_VERSION_UNSUPPORTED
   {:title "版本过新"
    :text "此资料由较新的格式保存。请更新应用后再打开；原文件没有被改写。"
    :action "更新应用或保留原文件"}

   :BACKUP_INTEGRITY
   {:title "备份完整性校验失败"
    :text "备份文件没有通过完整性校验。没有读取或覆盖任何资料。"
    :action "换一份备份或重新导出"}

   :BACKUP_OBJECT_CONFLICT
   {:title "同名备份内容不同"
    :text "同一备份 ID 已存在且内容不同；原文件没有被覆盖。"
    :action "重新取样或换一个目标"}

   :BACKUP_NOT_FOUND
   {:title "找不到该备份"
    :text "备份列表可能已经变化。当前资料没有改变。"
    :action "刷新列表"}

   :BACKUP_STORAGE_FULL
   {:title "本地空间不足"
    :text "空间不足，无法建立恢复点或写入备份。当前资料没有改变。"
    :action "释放空间或导出到其他位置"}

   :BACKUP_RECOVERY_POINT_FAILED
   {:title "无法建立本地恢复点"
    :text "恢复没有开始；当前资料没有改变。请释放空间或检查数据目录权限后重试。"
    :action "检查空间与权限"}

   :BACKUP_RESTORE_REQUIRES_QUIESCENCE
   {:title "需要离线维护窗口"
    :text "在线恢复被服务器拒绝：需要先停止所有 Owner 与写入者，在离线维护窗口使用恢复入口。当前资料没有改变。"
    :action "查看维护步骤"}

   :BACKUP_RESTORE_FAILED
   {:title "恢复未完成"
    :text "恢复没有完成。请查看恢复点与已应用范围；不要把部分结果当作成功。"
    :action "检查恢复点与目标状态"}

   :BACKUP_MAINTENANCE_UNAVAILABLE
   {:title "工作台恢复不可用"
    :text "当前服务器没有可用的进程内停机/恢复/重连协调。没有写入任何资料；请按离线步骤停止应用、运行恢复并重启。"
    :action "查看离线恢复步骤"}

   :BACKUP_MAINTENANCE_ACTIVE
   {:title "维护操作进行中"
    :text "已有维护操作正在进行。连接可能短暂中断；请等待状态或重新连接后核对，不要重复提交。"
    :action "等待或重新连接"}

   :BACKUP_MAINTENANCE_NOT_FOUND
   {:title "找不到维护操作"
    :text "服务器没有该维护 ID 的状态；不能判断恢复成功。请重新读取备份状态并核对。"
    :action "重新读取状态"}

   :BACKUP_MAINTENANCE_RESTORE_FAILED
   {:title "停机恢复未完成"
    :text "停机恢复没有完成。不要把部分结果当作成功；请查看维护状态与本地恢复点，必要时使用离线入口。"
    :action "查看维护状态与恢复点"}

   :BACKUP_MAINTENANCE_ACK_REQUIRED
   {:title "上一次恢复尚未确认"
    :text "上一次恢复失败且尚未由操作员确认。请先核对当前存储与恢复点，记录确认后再开始新的恢复。"
    :action "核对并记录确认"}

   :BACKUP_MAINTENANCE_STORE_UNCHANGED
   {:title "存储仍未变化"
    :text "服务器拒绝确认：当前存储仍与失败证据一致。请先恢复另一份备份或完成手工修复，再记录比较结果。"
    :action "先改变存储或恢复另一份备份"}

   :BACKUP_MAINTENANCE_STATE_CHANGED
   {:title "维护记录已变化"
    :text "维护记录、恢复计划或存储身份已变化。请重新读取维护状态并核对，不要沿用旧确认。"
    :action "重新读取维护状态"}

   :BACKUP_MAINTENANCE_ACK_INVALID
   {:title "无法记录确认"
    :text "服务器拒绝了操作员确认；失败状态与证据保持不变。请重新读取状态并核对确认代码。"
    :action "重新读取并核对"}

   :BACKUP_MAINTENANCE_JOURNAL_INVALID
   {:title "维护日志无法读取"
    :text "无法读取维护日志；不能判断恢复结果。请保留 state.edn 并检查数据目录权限。"
    :action "检查维护日志与数据目录"}

   :APP_NOT_QUIESCENT
   {:title "应用尚未完全停止"
    :text "服务器没有确认写入者已停止，恢复没有开始。当前资料没有改变。"
    :action "重新停止应用后重试"}

   :APP_RESTART_FAILED
   {:title "应用重启失败"
    :text "恢复可能已经完成，但应用没有成功重启。需要操作员处理；不要显示为成功。"
    :action "查看进程与日志，重新启动应用"}

   :BACKUP_CANCELLED
   {:title "操作已取消"
    :text "没有生成最终文件；当前资料没有改变。"
    :action "返回或重新开始"}

   :BACKUP_TARGET_INVALID
   {:title "备份目标不可用"
    :text "备份目标配置无效；没有产生外部写入。"
    :action "检查目标配置"}

   :BACKUP_TARGET_UNAVAILABLE
   {:title "备份目标不可用"
    :text "无法访问备份目标；没有产生外部写入。"
    :action "检查网络与目标配置"}

   :BACKUP_UNAVAILABLE
   {:title "备份服务尚未就绪"
    :text "备份服务不可用；没有执行任何操作。"
    :action "查看诊断"}

   :BACKUP_DELETE_UNCONFIRMED
   {:title "删除尚未确认"
    :text "删除备份需要显式确认；文件仍然保留。"
    :action "再次确认删除"}

   :BACKUP_PLAINTEXT_SECRETS_UNCONFIRMED
   {:title "未加密备份包含密钥"
    :text "包含密钥的未加密备份需要显式确认。当前没有写入任何文件。"
    :action "确认风险或改回加密"}

   :BACKUP_CONFLICT_RESOLUTION_INVALID
   {:title "恢复发生冲突"
    :text "冲突选项无效；没有写入任何资料。请重新预览并选择。"
    :action "查看冲突并决定"}

   :BACKUP_CATEGORY_MISSING
   {:title "备份不包含所选类别"
    :text "所选恢复类别并不都在备份中。没有写入任何资料；请只选择备份实际包含的类别后重新预览。"
    :action "只选择备份中存在的类别"}

   :BACKUP_CATEGORY_REQUIRED
   {:title "尚未选择类别"
    :text "至少选择一个类别；当前没有执行任何操作。"
    :action "选择类别"}

   :BACKUP_CATEGORY_UNKNOWN
   {:title "类别未知"
    :text "请求包含当前版本不认识的类别；没有执行任何操作。"
    :action "刷新后重选"}

   :BACKUP_SNAPSHOT_INCOHERENT
   {:title "取样快照不一致"
    :text "备份没有在一致取样点完成；没有宣称保存成功。"
    :action "重新取样"}

   :backup/not-synced
   {:title "连接尚未同步"
    :text "连接尚未完成同步；表单已保留，同步后再提交。"
    :action "等待同步或重新连接"}

   :backup/command-pending
   {:title "已有待确认命令"
    :text "同一目标已有待确认命令；请等待回执或先核对结果。"
    :action "等待回执"}

   :backup/outcome-unknown
   {:title "提交结果未知"
    :text "操作可能已经提交；请先核对备份列表或恢复点，不要自动重发。"
    :action "检查目标状态"}})

(defn- standard-error-copy
  "Map the backup codes that are part of the 24-class contract to the shared
  copy module.  The returned map keeps the existing `:title/:text/:action`
  shape and adds the full `:copy` for callers that need command keywords."
  [code]
  (let [kind (case code
               :BACKUP_WRONG_PASSWORD :backup-password-invalid
               (:BACKUP_FORMAT_INVALID :BACKUP_CONTAINER_INVALID)
               :import-format-unsupported
               (:BACKUP_OBJECT_CONFLICT :BACKUP_CONFLICT_RESOLUTION_INVALID)
               :restore-conflict
               :BACKUP_VERSION_UNSUPPORTED :format-too-new
               nil)
        copy (when kind
               (failures/failure-copy {:code kind :context {}}))]
    (when copy
      {:title (:title copy)
       :text (:body copy)
       :action (get-in copy [:primary-action :label])
       :copy copy})))

(defn error-view
  "Turn one server error into a stable user-facing card.  Unknown codes keep
  the server message instead of pretending to know a cause."
  [error]
  (let [code (normalize-keyword (or (:code error) :BACKUP_FAILED))
        known (get error-contract code)
        standard (standard-error-copy code)
        server-message (or (:message error) (get-in error [:details :message]))]
    (let [view (merge {:kind :error
            :code code
            :title "操作未完成"
            :text (if server-message
                    (str "操作未完成：" server-message
                         "。请先核对目标状态，不要自动重试。")
                    "操作未完成，且没有可用的原因说明。请先核对目标状态，不要自动重试。")
            :action "检查目标状态"}
           (or known {})
           (or standard {}))]
    (cond-> view
      (:copy view)
      (update :title (fn [title]
                       (str (or (failures/reason-label
                                 (get-in view [:copy :reason-category]))
                                "")
                            title)))))))

;; ---------------------------------------------------------------------------
;; Derived view

(defn backup-row
  [backup]
  (let [id (str (or (:id backup) (:backup-id backup) ""))]
    {:id id
     :sampled-at (:sampled-at backup)
     :created-at (:created-at backup)
     :device (:device backup)
     :app-version (:app-version backup)
     :encrypted? (:encrypted? backup)
     :format-version (:format-version backup)
     :size (:size backup)
     :integrity (:integrity backup)
     :path (:path backup)
     :label (str (or (:device backup) "未知设备") " · "
                 (or (:sampled-at backup) (:created-at backup) id))}))

(defn backup-rows
  [state]
  (->> (get-in state [:server :backups])
       (map backup-row)
       (sort-by (fn [row] (str (or (:created-at row)
                                   (:sampled-at row)
                                   (:id row)))))
       reverse
       vec))

(defn selected-backup
  [state]
  (let [id (get-in state [:ui :selected-backup-id])]
    (when id
      (some #(when (= id (:id %)) %) (backup-rows state)))))

(defn target-view
  [state]
  (let [status (get-in state [:server :status])
        targets (vec (or (get-in state [:server :targets])
                         (:targets status)
                         (when-let [target (:target status)] [target])))
        selected-id (or (get-in state [:ui :target-id])
                        (get-in state [:server :default-target-id])
                        (:default-target-id status)
                        (:id (:target status)))
        selected (or (some #(when (= selected-id (:id %)) %) targets)
                     (:target status)
                     (first targets))
        recovery (:recovery status)
        errors (:errors status)]
    {:data-dir (:data-dir status)
     :selected-id (or (:id selected) selected-id)
     :selected selected
     :targets (mapv (fn [target]
                      {:id (:id target)
                       :kind (normalize-keyword (:kind target))
                       :kind-label (case (normalize-keyword (:kind target))
                                     :file "本地文件"
                                     :webdav "WebDAV"
                                     :s3 "S3"
                                     (name (or (normalize-keyword (:kind target))
                                               :unknown)))
                       :configured? (not (false? (:configured? target)))
                       :available? (not (false? (:available? target)))
                       :reason (:reason target)
                       :root (:root target)
                       :base-url (:base-url target)
                       :directory (:directory target)
                       :endpoint (:endpoint target)
                       :bucket (:bucket target)
                       :prefix (:prefix target)
                       :region (:region target)
                       :username (:username target)
                       :path-style? (:path-style? target)
                       :selected? (= (:id target) (or (:id selected)
                                                      selected-id))})
                    targets)
     :local-root (or (:root selected) (:root (:target status))
                     (:target-root status))
     :base-url (:base-url selected)
     :endpoint (:endpoint selected)
     :bucket (:bucket selected)
     :last-backup (:last-backup status)
     :storage (:storage status)
     :recovery-root (or (:root recovery) (:recovery-root status))
     :errors (vec (or errors []))
     :configured? (boolean (seq targets))
     :restore (merge {:online? false :offline? true
                      :requires-quiescence? true}
                     (:restore status))
     :online-restore? false}))

(defn selected-target-id
  [state]
  (let [target (target-view state)
        id (or (get-in state [:ui :target-id])
               (get-in state [:server :default-target-id])
               (:selected-id target))]
    (if (some #(= id (:id %)) (:targets target))
      id
      (:selected-id target))))

(defn selected-target
  [state]
  (some #(when (:selected? %) %) (:targets (target-view state))))

(defn target-ready?
  [state]
  (let [target (selected-target state)]
    (and (some? (get-in state [:server :status]))
         (some? target)
         (not (false? (:available? target))))))

(defn online-restore?
  "The browser never runs an online restore.  The server rejects every live
  `:backup/restore`; only the offline maintenance path may write.  A server
  snapshot that claims `:online? true` does not reopen that path."
  [_state]
  false)

(defn- target-kind-label
  [kind]
  (case (normalize-keyword kind)
    :webdav "WebDAV"
    :s3 "S3"
    :file "本地文件"
    (name (or (normalize-keyword kind) :unknown))))

(defn- non-blank?
  [value]
  (not (str/blank? (str (or value "")))))

(defn- target-config-payload
  "Map the editor form to the server `:config` map for KIND.  Secrets are
  forwarded verbatim, including empty strings: the server treats a blank
  secret on an existing target as \"keep the stored value\"."
  [editor]
  (let [kind (normalize-keyword (:kind editor))
        remote-url (str (or (:remote-url editor) ""))
        endpoint (str (or (:endpoint editor) ""))
        directory (str (or (:directory editor) ""))]
    (case kind
      :webdav
      (cond-> {:kind :webdav
               :remote-url (if (non-blank? remote-url)
                             remote-url
                             endpoint)
               :username (str (or (:username editor) ""))
               :password (str (or (:password editor) ""))}
        (non-blank? directory) (assoc :directory directory))

      :s3
      {:kind :s3
       :endpoint (if (non-blank? endpoint) endpoint remote-url)
       :bucket (str (or (:bucket editor) ""))
       :region (str (or (:region editor) ""))
       :access-key (str (or (:access-key editor) ""))
       :secret (str (or (:secret editor) ""))
       :prefix (str (or (:prefix editor) ""))
       :path-style? true}

      ;; :file (and any unknown kind) uses the local-directory form.
      {:kind :file
       :root (if (non-blank? directory) directory remote-url)})))

(defn- target-test-command-key
  "Command key that keeps in-flight tests for different drafts/targets from
  blocking each other.  `:backup/test-target` carries no `:target-id`, so the
  `command-key` payload slot distinguishes the drafts."
  [editor]
  (str "test-" (name (normalize-keyword (:kind editor)))
       "-" (or (:target-id editor) "new")))

(defn- target-busy?
  [state]
  (boolean
   (some (fn [[_ command]]
           (and (contains? #{:backup/save-target :backup/test-target
                             :backup/delete-target}
                           (:kind command))
                (contains? #{:queued :submitting :outcome-unknown}
                           (:status command))))
         (:commands state))))

(defn- target-required-fields
  "Field keys that must be non-blank for KIND before save/test.  The file
  kind accepts either `:directory` or `:remote-url`, and WebDAV accepts
  `:remote-url` or `:endpoint`; neither has a single required key, so
  `target-config-ready?` checks those pairs."
  [kind]
  (case (normalize-keyword kind)
    :webdav #{}
    :s3 #{:endpoint :bucket :region :access-key :secret}
    :file #{}
    #{}))

(defn- target-config-ready?
  [editor]
  (let [kind (normalize-keyword (:kind editor))
        editor (assoc editor
                      :remote-url (str (or (:remote-url editor) ""))
                      :endpoint (str (or (:endpoint editor) ""))
                      :directory (str (or (:directory editor) "")))
        required (target-required-fields kind)]
    (and (or (seq required)
             ;; :file needs a directory or a remote URL.
             (or (non-blank? (:directory editor))
                 (non-blank? (:remote-url editor))))
         (every? #(non-blank? (get editor %)) required)
         ;; WebDAV needs a remote URL or an endpoint; :file needs a
         ;; directory or a remote URL.
         (or (not (contains? #{:webdav :file} kind))
             (case kind
               :webdav (or (non-blank? (:remote-url editor))
                           (non-blank? (:endpoint editor)))
               :file (or (non-blank? (:directory editor))
                         (non-blank? (:remote-url editor)))
               true)))))

(defn target-config-view
  "View model for the backup-target editor.  `:fields` lists every editor
  input in display order with `:kinds`; the view filters by `:kind`.
  `:can-save?`/`:can-test?` require the kind's mandatory fields and no
  in-flight target command."
  [state]
  (let [ui (:ui state)
        editor (or (:target-editor ui) default-target-editor)
        kind (normalize-keyword (:kind editor))
        busy? (target-busy? state)
        ready? (and (target-config-ready? editor) (not busy?))]
    {:editor editor
     :open? (true? (:open? editor))
     :kind kind
     :kind-label (target-kind-label kind)
     :fields (vec target-field-defs)
     :test (:target-test ui)
     :targets (:targets (target-view state))
     :delete-id (:target-delete-id ui)
     :busy? busy?
     :can-save? ready?
     :can-test? ready?}))

(def maintenance-terminal-statuses
  "Terminal maintenance statuses.  The three evidence statuses must stop the
  bridge from treating a finished failure as an operation still in progress."
  #{:complete :failed :stop-failed :restart-failed :failed-needs-attention
    :failed-needs-recovery :failed-commit-unproven :failed-before-commit
    :cancelled})

(def maintenance-blocking-statuses
  "Terminal failures that keep the next start blocked until an operator
  records an acknowledgement.  Mirrors
  hc.server.backup.maintenance-journal/blocking-terminal-statuses."
  #{:failed :failed-needs-attention :failed-needs-recovery
    :failed-commit-unproven})

(def maintenance-status-labels
  {:accepted "已接受"
   :validating "正在校验备份"
   :stopping "正在停止写入"
   :restoring "正在恢复"
   :restarting "正在重启"
   :reconnecting "等待重新连接"
   :complete "恢复完成"
   :failed "恢复失败"
   :stop-failed "停止失败，恢复未开始"
   :restart-failed "恢复完成，但应用重启失败"
   :failed-needs-attention "恢复结果未知，需要人工处理"
   :failed-needs-recovery "恢复失败，存储可能已提交"
   :failed-commit-unproven "恢复失败，提交结果未知"
   :failed-before-commit "恢复失败，已确认未提交"
   :cancelled "维护已取消"})

(def maintenance-evidence-labels
  {:committed "已提交，需核对"
   :unknown "结果未知"
   :not-committed "已确认未提交"
   :no-evidence "没有提交证据"
   :not-started "恢复未开始"
   :success "恢复完成"
   :restart-failed "恢复可能完成，应用未重启"
   :cancelled "已取消"})

(def maintenance-status-evidence
  {:complete :success
   :failed :no-evidence
   :stop-failed :not-started
   :restart-failed :restart-failed
   :failed-needs-attention :unknown
   :failed-needs-recovery :committed
   :failed-commit-unproven :unknown
   :failed-before-commit :not-committed
   :cancelled :cancelled})

(def maintenance-phase-labels
  {:validating "正在校验备份"
   :stopping "正在停止写入"
   :restoring "正在恢复数据"
   :restarting "正在重启应用"
   :reconnecting "正在重新连接"
   :failed "维护失败"
   :done "维护结束"})

(defn- maintenance-evidence
  [last]
  (let [status (normalize-keyword (:status last))]
    (cond
      (= :failed-needs-recovery status) :committed
      (= :failed-commit-unproven status) :unknown
      (= :failed-before-commit status) :not-committed
      (= :failed-needs-attention status) :unknown
      (= :failed status)
      (cond
        (false? (:restore-attempted? last)) :not-started
        (or (true? (:needs-recovery? last))
            (true? (:storage-touched? last))
            (true? (:store-committed? last))) :committed
        (true? (:commit-unproven? last)) :unknown
        (true? (:store-unchanged? last)) :not-committed
        :else :no-evidence)
      :else (get maintenance-status-evidence status))))

(defn- maintenance-blocking-failure?
  "True when LAST is a terminal failure that blocks the next start.

  A `:failed` record that explicitly says the restore was not attempted is
  pre-restore history, not a storage failure.  An acknowledged record keeps
  its failure status but no longer blocks."
  [last]
  (let [status (normalize-keyword (:status last))]
    (and (contains? maintenance-blocking-statuses status)
         (not (true? (:acknowledged? last)))
         (not (and (= :failed status)
                   (false? (:restore-attempted? last))
                   (not (true? (:needs-attention? last)))
                   (not (true? (:needs-recovery? last)))
                   (not (true? (:commit-unproven? last)))
                   (not (true? (:storage-touched? last))))))))

(defn- maintenance-acknowledge-code
  "The acknowledgement code the backend accepts for LAST.

  The journal refuses `inspect-accept-unknown-commit` for a proven commit and
  `inspect-and-accept-partial` for an unproven one.  `restore-another-backup`
  remains an alternative after the operator restored a different backup."
  [last]
  (let [status (normalize-keyword (:status last))]
    (cond
      (= :failed-needs-recovery status) "inspect-and-accept-partial"
      (= :failed-commit-unproven status) "inspect-accept-unknown-commit"
      (contains? #{:failed :failed-needs-attention} status)
      (if (or (true? (:needs-recovery? last))
              (true? (:storage-touched? last))
              (true? (:store-committed? last)))
        "inspect-and-accept-partial"
        "inspect-accept-unknown-commit")
      :else nil)))

(defn- maintenance-start-gate-label
  [status last blocking?]
  (cond
    (true? (:acknowledged? last))
    "已记录确认；失败状态保留，启动不再阻塞"

    blocking?
    "保持阻塞，直到操作员确认"

    (= :failed-before-commit status)
    "不阻塞；已确认未提交"

    (= :stop-failed status)
    "不阻塞；恢复未开始"

    (= :restart-failed status)
    "不阻塞；启动应用即恢复"

    (contains? #{:complete :cancelled} status)
    "不阻塞"

    (nil? status)
    "尚未开始"

    (contains? maintenance-terminal-statuses status)
    "不阻塞"

    :else
    "维护进行中"))

(defn maintenance-view
  [state]
  (let [server (or (get-in state [:server :maintenance])
                   (:maintenance (get-in state [:server :status])))
        request (get-in state [:ui :maintenance-request])
        mode (or (normalize-keyword (:mode server)) :offline-only)
        available? (and (true? (:available? server))
                        (= :supervised mode))
        server-last (:last server)
        request-id (:id request)
        last-id (or (:id server-last) (:maintenance-id server-last))
        active (or (:active server)
                   (when (and request
                              (or (nil? last-id) (not= request-id last-id))
                              (not (contains? maintenance-terminal-statuses
                                              (normalize-keyword (:status request)))))
                     request))
        last (or server-last request)
        current (or active last)
        status (normalize-keyword (or (:status active) (:status last)))
        phase (normalize-keyword (or (:phase active) (:phase last)
                                     (when (contains? maintenance-terminal-statuses
                                                      status)
                                       :done)))
        connected? (= :synced (get-in state [:sync :phase]))
        blocking? (maintenance-blocking-failure? current)
        acknowledged? (true? (:acknowledged? current))
        evidence (maintenance-evidence current)
        reconnect? (boolean (and active
                                 (not (contains? maintenance-terminal-statuses status))
                                 (not connected?)))
        no-write? (boolean
                   (and (= :complete status)
                        (or (true? (:no-changes? current))
                            (true? (get-in current [:result :no-changes?]))
                            (true? (:already-imported? current))
                            (true? (get-in current [:result :already-imported?])))))
        evidence-label (if no-write?
                         "没有写入"
                         (get maintenance-evidence-labels evidence
                              (if evidence (name evidence) "未提供")))]
    {:available? available?
     :mode mode
     :reason (or (:reason server) :NO_IN_PROCESS_QUIESCE)
     :reason-label (case (normalize-keyword (:reason server))
                     :NO_IN_PROCESS_QUIESCE
                     "服务器没有进程内停机/恢复/重连协调"
                     (:reason server))
     :command (or (:command server) "pixi run backup")
     :active active
     :last last
     :status status
     :status-label (get maintenance-status-labels status
                         (if status (name status) "尚未开始"))
     :phase phase
     :phase-label (get maintenance-phase-labels phase
                        (if phase (name phase) "尚未开始"))
     :needs-attention? (boolean (:needs-attention? current))
     :needs-recovery? (boolean (:needs-recovery? current))
     :commit-unproven? (boolean (:commit-unproven? current))
     :store-unchanged? (boolean (:store-unchanged? current))
     :storage-touched? (boolean (:storage-touched? current))
     :restore-attempted? (boolean (:restore-attempted? current))
     :acknowledged? acknowledged?
     :blocking? blocking?
     :acknowledge-required? blocking?
     :acknowledge-code (maintenance-acknowledge-code current)
     :evidence evidence
     :evidence-label evidence-label
     :start-gate-label (maintenance-start-gate-label status current blocking?)
     :operator-action? (boolean (or blocking?
                                    reconnect?
                                    (= :restart-failed status)))
     :terminal? (contains? maintenance-terminal-statuses status)
     :no-write? no-write?
     :reconnect? reconnect?
     :message (:message current)
     :result (:result current)}))

(defn maintenance-available?
  [state]
  (true? (:available? (maintenance-view state))))

(def restore-command-kinds
  "Commands that stop or replace the running data.  The 7+4+3 prune button is
  disabled while one of them is queued or the maintenance operation is active."
  #{:backup/restore :backup/restore-recovery-point
    :backup/maintenance-restore
    :backup/maintenance-restore-recovery-point})

(defn restore-in-progress?
  [state]
  (or (let [maintenance (maintenance-view state)]
        (boolean (and (:active maintenance)
                      (not (:terminal? maintenance)))))
      (boolean (some (fn [command]
                       (and (contains? #{:queued :submitting} (:status command))
                            (contains? restore-command-kinds (:kind command))))
                     (vals (:commands state))))))

(defn category-view
  [state scope]
  ;; The view receives a form scope (:create/:restore), but the selection is
  ;; stored under :categories/:restore-categories.  Reading [:ui scope] made
  ;; every checkbox render unchecked even after "select all", so clicks looked
  ;; like no-ops and the create button stayed disabled.
  (let [selection (get-in state [:ui (if (= :restore (normalize-keyword scope))
                                       :restore-categories
                                       :categories)])]
    (mapv (fn [category]
            {:category category
             :label (get category-labels category)
             :note (get category-notes category)
             :selected? (boolean (get selection category))})
          category-order)))

(defn create-validation
  [state]
  (let [ui (:ui state)
        categories (selected-categories (:categories ui))
        encrypt? (not (false? (:encrypt? ui)))
        include-secrets? (true? (:include-secrets? ui))
        target (selected-target state)
        check (when encrypt?
                (password-check (:password ui) (:password-confirm ui)))]
    (cond-> []
      (nil? (get-in state [:server :status]))
      (conj {:code :BACKUP_TARGET_UNAVAILABLE
             :message "先读取备份状态并选择目标。"})

      (and (get-in state [:server :status]) (nil? target))
      (conj {:code :BACKUP_TARGET_INVALID
             :message "尚未选择备份目标。"})

      (and target (not (:available? target)))
      (conj {:code :BACKUP_TARGET_UNAVAILABLE
             :message (str "所选备份目标不可用："
                           (or (:reason target) "服务器未提供原因"))})

      (empty? categories)
      (conj {:code :BACKUP_CATEGORY_REQUIRED
             :message "至少选择一个类别。"})

      (and encrypt? (not (:ok? check)))
      (conj {:code :BACKUP_PASSWORD_INVALID
             :message (:message check)})

      (and include-secrets? (not encrypt?)
           (not (true? (:confirm-plaintext-secrets? ui))))
      (conj {:code :BACKUP_PLAINTEXT_SECRETS_UNCONFIRMED
             :message "未加密备份包含密钥需要显式确认。"}))))

(defn restore-password-check
  [state]
  (let [backup (selected-backup state)
        password (str (get-in state [:ui :password]))
        encrypted? (encrypted-backup? backup)]
    (cond
      (and encrypted? (empty? password))
      {:ok? false
       :reason :required
       :message "这份备份已加密；请输入备份密码。"}

      (and (nil? backup) (empty? password))
      {:ok? false
       :reason :no-backup
       :message "先选择一份备份。"}

      :else {:ok? true})))

(defn preview-validation
  [state]
  (let [ui (:ui state)
        categories (selected-categories (:restore-categories ui))
        target (selected-target state)
        check (restore-password-check state)]
    (cond-> []
      (nil? (get-in state [:server :status]))
      (conj {:code :BACKUP_TARGET_UNAVAILABLE
             :message "先读取备份状态并选择目标。"})

      (and target (not (:available? target)))
      (conj {:code :BACKUP_TARGET_UNAVAILABLE
             :message (str "所选备份目标不可用："
                           (or (:reason target) "服务器未提供原因"))})

      (nil? (selected-backup state))
      (conj {:code :BACKUP_NOT_FOUND
             :message "先选择一份备份。"})

      (empty? categories)
      (conj {:code :BACKUP_CATEGORY_REQUIRED
             :message "至少选择一个要恢复的类别。"})

      (not (:ok? check))
      (conj {:code :BACKUP_WRONG_PASSWORD
             :message (:message check)}))))

(defn- preview-category-set
  "The server's preview `:categories` may be a category-summary map or a
  sequence of category ids.  Return the selected set, or nil when the field
  has no comparable shape."
  [preview]
  (let [categories (:categories preview)]
    (cond
      (map? categories) (set (map normalize-keyword (keys categories)))
      (sequential? categories) (set (map normalize-keyword categories))
      :else nil)))

(defn- restore-base-validation
  [state]
  (let [ui (:ui state)
        preview (:preview ui)
        categories (selected-categories (:restore-categories ui))
        check (restore-password-check state)]
    (cond-> (preview-validation state)
      (nil? preview)
      (conj {:code :BACKUP_PREVIEW_REQUIRED
             :message "恢复前需要先生成预览。"})

      (true? (:preview-stale? ui))
      (conj {:code :BACKUP_PREVIEW_STALE
             :message "冲突选择已改变；请重新预览后再恢复。"})

      (and preview (true? (:no-changes? preview)))
      (conj {:code :BACKUP_NO_CHANGES
             :message "预览显示没有需要写入的变化；恢复会跳过。"})

      (and preview (true? (:already-imported? preview)))
      (conj {:code :BACKUP_ALREADY_IMPORTED
             :message "此备份此前已导入；再次恢复会幂等跳过。"})

      (and preview (true? (:memory-reactivation-required? preview))
           (not (true? (:confirm-memory-reactivation? ui))))
      (conj {:code :BACKUP_MEMORY_REACTIVATION_UNCONFIRMED
             :message "备份会重新启用已归档或删除的记忆；需要显式确认。"})

      (not (true? (:restore-confirm? ui)))
      (conj {:code :BACKUP_RESTORE_UNCONFIRMED
             :message "需要勾选确认后再执行恢复。"})

      (and preview
           (contains? preview :categories)
           (let [preview-categories (preview-category-set preview)]
             (and (some? preview-categories)
                  (not= (set categories) preview-categories))))
      (conj {:code :BACKUP_PREVIEW_STALE
             :message "恢复类别已改变；请重新预览后再恢复。"})

      (not (:ok? check))
      (conj {:code :BACKUP_WRONG_PASSWORD
             :message (:message check)}))))

(defn restore-validation
  [state]
  (conj (restore-base-validation state)
        {:code :BACKUP_RESTORE_REQUIRES_QUIESCENCE
         :message "在线恢复已被服务器禁用；需要停止所有 Owner 与写入者后使用离线恢复入口。"}))

(defn maintenance-validation
  [state]
  (let [maintenance (maintenance-view state)]
    (cond-> (restore-base-validation state)
      (not (:available? maintenance))
      (conj {:code :BACKUP_MAINTENANCE_UNAVAILABLE
             :message (or (:reason maintenance)
                          "当前服务器没有可用的工作台维护入口；请使用离线恢复步骤。")})

      (:active maintenance)
      (conj {:code :BACKUP_MAINTENANCE_ACTIVE
             :message "已有维护操作正在进行；请等待状态或重新连接后核对。"})

      (:acknowledge-required? maintenance)
      (conj {:code :BACKUP_MAINTENANCE_ACK_REQUIRED
             :message "上一次恢复失败且尚未确认；请先核对当前存储与恢复点并记录确认，或恢复另一份备份。"}))))

(defn maintenance-recovery-point-validation
  [state recovery-point-id]
  (let [maintenance (maintenance-view state)]
    (cond-> []
      (not (:available? maintenance))
      (conj {:code :BACKUP_MAINTENANCE_UNAVAILABLE
             :message (or (:reason maintenance)
                          "当前服务器没有可用的工作台维护入口；请使用离线回退步骤。")})

      (:active maintenance)
      (conj {:code :BACKUP_MAINTENANCE_ACTIVE
             :message "已有维护操作正在进行；请等待状态或重新连接后核对。"})

      (:acknowledge-required? maintenance)
      (conj {:code :BACKUP_MAINTENANCE_ACK_REQUIRED
             :message "上一次恢复失败且尚未确认；请先核对当前存储与恢复点并记录确认，或恢复另一份备份。"})

      (str/blank? (str recovery-point-id))
      (conj {:code :BACKUP_NOT_FOUND
             :message "先选择一个本地恢复点。"}))))

(defn preview-summary-rows
  [preview]
  (let [summary (:summary preview)]
    [["本地记录" (:local summary)]
     ["备份记录" (:imported summary)]
     ["新增" (:added summary)]
     ["替换" (:replaced summary)]
     ["移除" (:removed summary)]
     ["跳过（相同或已导入）" (:skipped summary)]
     ["冲突" (:conflicts summary)]
     ["过期删除项跳过" (:expired-skipped summary)]
     ["记忆待审" (:memory-pending summary)]]))

(def reason-labels
  {:same-id-different-content "相同 ID、内容不同"
   :same-name-different-content "同名、内容不同"
   :local-disabled-memory "本地已停用或删除的记忆，备份中仍启用"})

(def resolution-labels
  {:keep-local "保留本地版本"
   :use-backup "使用备份版本"
   :keep-both "两份都保留（默认）"})

(defn- conflict-key
  [conflict]
  [(normalize-keyword (:category conflict)) (str (:id conflict))])

(defn conflict-views
  [preview resolutions]
  (mapv (fn [conflict]
          (let [k (conflict-key conflict)
                resolution (or (get resolutions k)
                               (normalize-keyword (:resolution conflict))
                               :keep-both)]
            {:key (str (some-> (first k) name) "/" (second k))
             :category (first k)
             :category-label (get category-labels (first k) (name (first k)))
             :id (second k)
             :imported-id (:imported-id conflict)
             :name (or (:name conflict) (:id conflict) (:imported-id conflict))
             :reason (normalize-keyword (:reason conflict))
             :reason-label (get reason-labels
                                (normalize-keyword (:reason conflict))
                                (name (or (normalize-keyword (:reason conflict))
                                          :unknown)))
             :resolution resolution
             :resolution-label (get resolution-labels resolution)
             :kept-id (:kept-id conflict)
             :choices (mapv (fn [choice]
                              {:resolution choice
                               :label (get resolution-labels choice (name choice))
                               :selected? (= choice resolution)})
                            (or (:choices conflict)
                                [:keep-local :use-backup :keep-both]))}))
        (or (:conflicts preview) [])))

(defn- describe-item
  [item]
  (cond
    (map? item)
    (or (:label item) (:name item) (:title item)
        (when-let [id (:id item)] (str id))
        (pr-str item))

    (keyword? item) (get category-labels item (name item))
    (string? item) item
    (nil? item) "未知项"
    :else (pr-str item)))

(defn- describe-list
  [items]
  (mapv describe-item (or items [])))

(defn preview-view
  [state]
  (let [preview (get-in state [:ui :preview])
        resolutions (get-in state [:ui :resolutions])]
    (when preview
      {:mode (normalize-keyword (:mode preview))
       :mode-label (if (= :replace (:mode preview))
                     "替换选中类别"
                     "合并选中类别")
       :summary (preview-summary-rows preview)
       :conflicts (conflict-views preview resolutions)
       :conflict-count (count (or (:conflicts preview) []))
       :no-changes? (boolean (:no-changes? preview))
       :already-imported? (boolean (:already-imported? preview))
       :replace-all? (boolean (:replace-all? preview))
       :memory-reactivation-required? (boolean (:memory-reactivation-required? preview))
       :memory-reactivation (describe-list (:memory-reactivation preview))
       :expired-skipped (describe-list (:expired-skipped preview))
       :placeholders (describe-list (:placeholders preview))
       :missing-relations (describe-list (:missing-relations preview))
       :relink-required (describe-list (:relink-required preview))
       :needs-review (describe-list (:needs-review preview))
       :needs-reconfiguration (describe-list (:needs-reconfiguration preview))
       :incomplete-attachments (describe-list (:incomplete-attachments preview))
       :permissions (:permissions preview)
       :manifest (:manifest preview)
       :recovery-point (:recovery-point preview)
       :recovery-required? (boolean (get-in preview [:recovery-point :required?]))
       :recovery-status (normalize-keyword (get-in preview [:recovery-point :status]))
       :warnings (describe-list (:warnings preview))
       :stale? (boolean (get-in state [:ui :preview-stale?]))})))

(defn create-result-view
  [state]
  (let [result (get-in state [:ui :last-create])]
    (when result
      {:status (normalize-keyword (:status result))
       :status-label (case (normalize-keyword (:status result))
                       :complete "备份已写入并通过校验"
                       :needs-verification "备份已写入，但写入后校验未完成"
                       :cancelled "已取消，没有生成最终文件"
                       :accepted "服务器已接受备份请求"
                       "结果待确认")
       :id (get-in result [:backup :id])
       :path (or (get-in result [:target :path])
                 (get-in result [:backup :path]))
       :size (or (get-in result [:target :size])
                 (get-in result [:backup :size])
                 (get-in result [:backup :payload-size]))
       :created-at (or (get-in result [:backup :created-at])
                       (get-in result [:target :created-at]))
       :sampled-at (get-in result [:backup :sampled-at])
       :device (get-in result [:backup :device])
       :encrypted? (get-in result [:backup :encrypted?])
       :categories (:categories result)
       :attachments (:attachments result)
       :exclusions (describe-list (:exclusions result))
       :incomplete-attachments (describe-list (:incomplete-attachments result))
       :missing-references (describe-list (:missing-references result))
       :secrets (:secrets result)
       :verification (:verification result)
       :target (:target result)})))

(defn restore-result-view
  [state]
  (let [result (get-in state [:ui :last-restore])]
    (when result
      (let [status (normalize-keyword (:status result))
            needs-recovery? (:needs-recovery? result)
            commit-unproven? (:commit-unproven? result)
            store-unchanged? (:store-unchanged? result)
            status-label (cond
                           (and (= :complete status)
                                (or (true? (:no-changes? result))
                                    (true? (:already-imported? result))))
                           "没有写入"

                           (and (= :failed status)
                                (true? needs-recovery?))
                           "恢复失败，存储可能已提交，需要核对"

                           (and (= :failed status)
                                (true? commit-unproven?))
                           "恢复失败，提交结果未知"

                           (and (= :failed status)
                                (true? store-unchanged?))
                           "恢复失败，已确认未提交"

                           :else
                           (case status
                             :complete "恢复完成"
                             :no-changes "没有需要写入的变化"
                             :already-imported "此前已导入，本次跳过"
                             :cancelled "恢复已取消"
                             :failed "恢复未完成"
                             :failed-needs-attention "恢复结果未知，需要人工处理"
                             :failed-needs-recovery "恢复失败，存储可能已提交，需要核对"
                             :failed-commit-unproven "恢复失败，提交结果未知"
                             :failed-before-commit "恢复失败，已确认未提交"
                             "结果待确认"))]
        {:status status
         :status-label status-label
         :mode (normalize-keyword (:mode result))
         :summary (when (:summary result) (preview-summary-rows result))
         :recovery-point (:recovery-point result)
         :rolled-back? (:rolled-back? result)
         :no-changes? (boolean (:no-changes? result))
         :already-imported? (boolean (:already-imported? result))
         :needs-recovery? needs-recovery?
         :commit-unproven? commit-unproven?
         :store-unchanged? store-unchanged?
         :needs-reconfiguration (describe-list (:needs-reconfiguration result))
         :relink-required (describe-list (:relink-required result))
         :needs-review (describe-list (:needs-review result))
         :incomplete-attachments (describe-list (:incomplete-attachments result))
         :permissions (:permissions result)
         :warnings (describe-list (:warnings result))}))))

(defn recovery-point-rows
  [state]
  (->> (get-in state [:server :recovery-points])
       (mapv (fn [point]
               {:id (str (or (:id point) (:recovery-point-id point) ""))
                :created-at (:created-at point)
                :bytes (:bytes point)
                :reason (normalize-keyword (:reason point))
                :reason-label (case (normalize-keyword (:reason point))
                                :before-restore "恢复前保护"
                                :before-recovery-point-restore "恢复点回退前保护"
                                (name (or (normalize-keyword (:reason point))
                                          :unknown)))
                :scope (describe-list (:scope point))}))
       (sort-by (fn [row] (str (:created-at row))))
       reverse
       vec))

(defn pending?
  [state]
  (command-pending? state))

(defn can-submit?
  [state]
  (and (commands/command-allowed? state)
       (true? (get-in state [:ui :transport-available?]))))

(defn submit-reason
  [state]
  (cond
    (not= :synced (get-in state [:sync :phase]))
    "连接尚未完成同步；表单内容会保留。"
    (not (pos-int? (or (get-in state [:sync :command-epoch]) 0)))
    "服务器尚未提供命令 epoch；不能提交。"
    (not (true? (get-in state [:ui :transport-available?])))
    "命令传输尚未接入；按钮不会伪造成功。"
    (pending? state)
    "已有命令等待回执；请先核对结果。"
    :else nil))

;; ---------------------------------------------------------------------------
;; Receipt application

(defn- receipt-result
  [receipt]
  (or (:receipt receipt) (:result receipt)))

(defn- manual-status-request?
  "True only for an explicit operator refresh.  Mount, post-restore, and
  post-mutation refreshes carry no manual origin and stay silent."
  [command]
  (let [payload (or (:payload command) {})]
    (and (not (true? (:silent? payload)))
         (= :manual (:origin payload)))))

(defn- apply-status
  [state command result]
  (let [result (or result {})
        errors (vec (or (:errors result) []))
        state (-> state
                  (assoc-in [:server :status] result)
                  (cond->
                    (contains? result :targets)
                    (assoc-in [:server :targets] (vec (or (:targets result) [])))

                    (contains? result :default-target-id)
                    (assoc-in [:server :default-target-id]
                              (:default-target-id result))

                    (contains? result :restore)
                    (assoc-in [:server :restore]
                              (merge {:online? false :offline? true
                                      :requires-quiescence? true}
                                     (:restore result)))

                    (contains? result :maintenance)
                    (assoc-in [:server :maintenance] (:maintenance result))

                    (and (nil? (get-in state [:ui :target-id]))
                         (some? (or (get-in result [:target :id])
                                    (:default-target-id result))))
                    (assoc-in [:ui :target-id]
                              (or (get-in result [:target :id])
                                  (:default-target-id result)))

                    true
                    (assoc-in [:server :listing-error]
                              (some #(when (:target %) (:target %)) errors))

                    true
                    (assoc-in [:server :recovery-error]
                              (some #(when (:recovery %) (:recovery %)) errors))))
        state (if (contains? result :backups)
                (assoc-in state [:server :backups] (vec (:backups result)))
                state)
        state (if (contains? result :recovery-points)
                (assoc-in state [:server :recovery-points]
                          (vec (:recovery-points result)))
                state)]
    (if (seq errors)
      (message state :warning
               "备份状态部分读取失败；列表可能不完整。界面不会把读取失败当作空列表。"
               {:details errors})
      (let [current (get-in state [:ui :message])
            manual? (manual-status-request? command)
            replace? (or manual?
                         (nil? current)
                         (= :backup/status-pending (:code current)))
            maintenance (get-in state [:server :maintenance])
            last (:last maintenance)
            status (normalize-keyword (:status last))
            attention? (and last
                            (contains? #{:failed :failed-needs-attention
                                         :failed-needs-recovery
                                         :failed-commit-unproven
                                         :failed-before-commit}
                                       status))]
        (cond
          (and attention? replace?)
          (message state :warning
                   (str "最近一次维护恢复未完成："
                        (get maintenance-status-labels status (name status))
                        "。请在“恢复与预览”分区查看提交证据与启动门槛。")
                   {:code :BACKUP_MAINTENANCE_ATTENTION
                    :details last})

          attention?
          state

          manual?
          (message state :info "备份状态已更新。"
                   {:code :backup/status-loaded})

          (= :backup/status-pending (:code current))
          (assoc-in state [:ui :message] nil)

          :else
          state)))))

(defn- apply-create
  [state result]
  (let [result (or result {})
        status (normalize-keyword (:status result))
        incomplete (vec (or (:incomplete-attachments result) []))
        verification-error (get-in result [:verification :error])
        state (-> state
                  (assoc-in [:ui :last-create] result)
                  (assoc-in [:ui :last-restore] nil)
                  clear-password
                  (assoc-in [:ui :message] nil))
        state (cond
                (= :cancelled status)
                (message state :info
                         "备份已取消；没有生成最终文件，当前资料没有改变。"
                         {:code :BACKUP_CANCELLED})

                (= :needs-verification status)
                (message state :warning
                         "备份已写入，但写入后的完整性校验没有完成；请使用校验按钮确认，不要当作已验证。"
                         {:code :BACKUP_NEEDS_VERIFICATION})

                verification-error
                (message state :warning
                         (str "备份已写入，但校验报告错误："
                              (or (:message verification-error) "未提供原因"))
                         {:details verification-error})

                :else
                (message state :success
                         (str "备份已生成"
                              (when-let [at (get-in result [:backup :sampled-at])]
                                (str "；取样时间 " at "（UTC）"))
                              "。")
                         {:code :BACKUP_CREATED}))]
    (cond-> (refresh-status state)
      (seq incomplete)
      (message :warning
               (str "有 " (count incomplete)
                    " 个附件没有打包；备份中已移除这些悬空引用，并单独列出。")
               {:details incomplete :code :BACKUP_INCOMPLETE_ATTACHMENTS})

      (and (get-in result [:secrets :included?])
           (false? (get-in result [:backup :encrypted?])))
      (message :warning
               "这份备份未加密且包含密钥；请妥善保管文件并尽快删除不需要的副本。"
               {:code :BACKUP_PLAINTEXT_SECRETS}))))

(defn- apply-preview
  [state result]
  (let [result (or result {})
        state (-> state
                  (assoc-in [:ui :preview] result)
                  (assoc-in [:ui :preview-stale?] false)
                  (assoc-in [:ui :restore-confirm?] false)
                  (assoc-in [:ui :maintenance] nil)
                  (assoc-in [:ui :message] nil))
        conflicts (count (or (:conflicts result) []))]
    (cond
      (:already-imported? result)
      (message state :info
               "此备份此前已导入；再次恢复会幂等跳过，不会新增重复记录。"
               {:code :BACKUP_ALREADY_IMPORTED})

      (:no-changes? result)
      (message state :info
               "预览显示没有需要写入的变化；恢复会跳过。"
               {:code :BACKUP_NO_CHANGES})

      (:memory-reactivation-required? result)
      (message state :warning
               "备份会重新启用已归档或删除的记忆；需要逐项确认后才能替换。"
               {:code :BACKUP_MEMORY_REACTIVATION})

      (:replace-all? result)
      (message state :warning
               "这是全部类别的替换；本地未包含在备份中的内容会被移除。请确认预览摘要后再继续。"
               {:code :BACKUP_REPLACE_ALL})

      (pos? conflicts)
      (message state :info
               (str "预览已生成；发现 " conflicts
                    " 处冲突。默认两份都保留，可逐项选择后重新预览。尚未写入任何资料。")
               {:code :BACKUP_PREVIEW_READY})

      :else
      (message state :info
               "预览已生成；尚未写入任何资料。请确认摘要后再执行恢复。"
               {:code :BACKUP_PREVIEW_READY}))))

(defn- apply-verify
  [state result]
  (let [result (or result {})]
    (-> state
        clear-password
        (assoc-in [:ui :verify-result] result)
        (message :success
                 (str "备份校验通过"
                      (when-let [at (get-in result [:backup :sampled-at])]
                        (str "；取样时间 " at "（UTC）"))
                      "。")
                 {:code :BACKUP_VERIFIED}))))

(defn- apply-restore
  [state result]
  (let [result (or result {})
        status (normalize-keyword (:status result))
        state (-> state
                  (assoc-in [:ui :last-restore] result)
                  (assoc-in [:ui :restore-confirm?] false)
                  (assoc-in [:ui :maintenance] nil)
                  (assoc-in [:ui :message] nil))
        state (if (contains? #{:complete :no-changes :already-imported} status)
                (clear-password state)
                state)]
    (case status
      :complete
      (-> (refresh-status state)
          (message :success
                   "恢复完成。计划任务保持暂停、外部权限没有自动恢复；请检查待重新配置项。"
                   {:code :BACKUP_RESTORED}))

      :no-changes
      (-> (refresh-status state)
          (message :info
                   "没有需要写入的变化；恢复已跳过，资料保持原样。"
                   {:code :BACKUP_NO_CHANGES}))

      :already-imported
      (-> (refresh-status state)
          (message :info
                   "此备份此前已导入；本次幂等跳过，没有新增记录。"
                   {:code :BACKUP_ALREADY_IMPORTED}))

      :cancelled
      (message state :info
               "恢复已取消；资料没有改变。"
               {:code :BACKUP_CANCELLED})

      :failed-needs-recovery
      (-> (refresh-status state)
          (message :error
                   "恢复失败，但存储可能已写入恢复数据；请对照恢复点与当前资料核对，不要把部分结果当作成功。"
                   {:code (or (get-in result [:error :code])
                              :BACKUP_RESTORE_FAILED)
                    :details result}))

      :failed-commit-unproven
      (-> (refresh-status state)
          (message :error
                   "恢复失败，且无法证明存储是否已提交；请先核对当前资料与恢复点，不要自动重试。"
                   {:code (or (get-in result [:error :code])
                              :BACKUP_RESTORE_FAILED)
                    :details result}))

      :failed-before-commit
      (-> (refresh-status state)
          (message :warning
                   "恢复失败，但已确认没有提交到存储；当前资料保持原样。"
                   {:code (or (get-in result [:error :code])
                              :BACKUP_RESTORE_FAILED)
                    :details result}))

      :failed-needs-attention
      (-> (refresh-status state)
          (message :error
                   "恢复结果未知，需要人工处理；请查看维护状态与本地恢复点。"
                   {:code (or (get-in result [:error :code])
                              :BACKUP_RESTORE_FAILED)
                    :details result}))

      :failed
      (let [rolled-back? (:rolled-back? result)
            needs-recovery? (:needs-recovery? result)
            commit-unproven? (:commit-unproven? result)
            store-unchanged? (:store-unchanged? result)
            text (cond
                   (true? rolled-back?)
                   "恢复失败，已回退到操作前状态；请查看恢复点与错误原因。"

                   (= :unknown rolled-back?)
                   "恢复失败，回退结果未知；请先核对当前资料与恢复点，不要自动重试。"

                   (true? needs-recovery?)
                   "恢复失败，但存储可能已写入恢复数据；请使用本地恢复点核对，不要把部分结果当作成功。"

                   (true? commit-unproven?)
                   "恢复失败，且无法证明存储是否已提交；请先核对当前资料与恢复点，不要自动重试。"

                   (true? store-unchanged?)
                   "恢复失败，但已确认存储没有提交；当前资料保持原样。"

                   :else
                   "恢复失败；当前资料可能部分改变，请先核对恢复点与摘要。")]
        (-> (refresh-status state)
            (message :error text
                     {:code (or (get-in result [:error :code])
                                :BACKUP_RESTORE_FAILED)
                      :details result})))

      (-> (refresh-status state)
          (message :warning
                   (str "恢复结果状态未知："
                        (or (:status result) "服务器未提供"))
                   {:code :BACKUP_RESTORE_STATUS_UNKNOWN
                    :details result})))))

(defn- apply-delete
  [state result]
  (let [id (or (:backup-id result)
               (get-in state [:ui :delete-confirm-id]))
        state (-> state
                  (assoc-in [:ui :delete-confirm-id] nil)
                  (update-in [:server :backups]
                             (fn [backups]
                               (vec (remove #(= id (:id %)) backups))))
                  (refresh-status))]
    (message state :success
             "备份已删除；本地恢复点与当前资料没有改变。"
             {:code :BACKUP_DELETED})))

(defn- apply-prune
  [state result]
  (let [deleted (vec (or (:deleted result) []))]
    (-> (refresh-status state)
        (message :success
                 (str "已清理 " (count deleted)
                      " 个恢复点；保留最近 7 个成功点、4 周与 3 月代表副本。")
                 {:code :BACKUP_PRUNED :details result}))))

(defn- apply-recovery-restore
  [state result]
  (apply-restore state result))

(defn- maintenance-failure-text
  [status result]
  (case status
    :failed-needs-recovery
    "恢复失败，但存储可能已写入恢复数据；应用会保持阻塞。请对照维护状态、恢复点与当前资料核对，再用 acknowledge 记录比较结果。不要把部分结果当作成功。"

    :failed-commit-unproven
    "恢复失败，且无法证明存储是否已提交；应用会保持阻塞。没有自动重跑；请核对当前资料与恢复点，再用 acknowledge 记录比较结果。"

    :failed-before-commit
    "恢复失败，但已确认没有提交到存储；当前资料保持原样。应用可以继续启动，不需要确认。"

    :failed-needs-attention
    "恢复结果未知，需要人工处理；请查看维护状态与本地恢复点，不要把未知结果当作成功。"

    :failed
    (cond
      (false? (:restore-attempted? result))
      (if (and (not (true? (:needs-attention? result)))
               (not (true? (:needs-recovery? result)))
               (not (true? (:commit-unproven? result)))
               (not (true? (:storage-touched? result))))
        "恢复没有开始；当前资料没有改变。该记录不阻塞下次启动。"
        "恢复没有开始；当前资料没有改变。该记录仍会保持启动阻塞，直到操作员确认。")

      (true? (:needs-recovery? result))
      "恢复失败，但存储可能已写入恢复数据；请使用本地恢复点核对，不要把部分结果当作成功。"

      (true? (:commit-unproven? result))
      "恢复失败，且无法证明存储是否已提交；请先核对当前资料与恢复点，不要自动重试。"

      (true? (:store-unchanged? result))
      "恢复失败，但已确认没有提交到存储；当前资料保持原样。"

      :else
      "停机恢复未完成，且没有足够的提交证据；请查看维护状态与恢复点，不要把部分结果当作成功。")

    "维护失败；请查看维护状态与恢复点。"))

(defn- apply-maintenance-record
  "Store one maintenance record in both the request slice and the
  server-visible maintenance slice, so a terminal receipt is not hidden by an
  older `:backup/status` snapshot."
  [state record]
  (let [existing (get-in state [:ui :maintenance-request])
        request (merge existing record)
        request (cond
                  (:id existing) (assoc request :id (:id existing))
                  (:maintenance-id record) (assoc request :id (:maintenance-id record))
                  :else request)
        status (normalize-keyword (:status record))
        terminal? (contains? maintenance-terminal-statuses status)]
    (-> state
        (assoc-in [:ui :maintenance-request] request)
        (update-in [:server :maintenance]
                   (fn [server]
                     (-> (or server {:available? true :mode :supervised})
                         (assoc :last record)
                         (assoc :active (when-not terminal? record))))))))

(defn- maintenance-error-record
  [error]
  (let [details (or (:details error) (:data error) {})
        status (or (normalize-keyword (:status details))
                   (case (:code error)
                     :APP_RESTART_FAILED :restart-failed
                     :APP_NOT_QUIESCENT :stop-failed
                     :BACKUP_QUIESCE_STOP_FAILED :stop-failed
                     :failed))
        needs-attention? (if (contains? details :needs-attention?)
                           (:needs-attention? details)
                           (not (contains? #{:failed-before-commit :stop-failed
                                             :restart-failed :complete
                                             :cancelled}
                                           status)))]
    (merge details
           {:status status
            :needs-attention? (boolean needs-attention?)
            :error error})))

(defn- apply-maintenance-error
  [state error]
  (apply-maintenance-record state (maintenance-error-record error)))

(defn- apply-maintenance-result
  [state result]
  (let [result (or (:maintenance result) result)
        status (normalize-keyword (:status result))
        phase (normalize-keyword (:phase result))
        request (merge (get-in state [:ui :maintenance-request])
                       result
                       {:status status}
                       (when phase {:phase phase}))
        state (apply-maintenance-record state request)
        state (if (= :complete status)
                (-> state
                    clear-password
                    (assoc-in [:ui :restore-confirm?] false))
                state)
        state (if-let [restored (:result result)]
                (assoc-in state [:ui :last-restore]
                          (merge restored
                                 (select-keys result
                                              [:needs-recovery? :commit-unproven?
                                               :store-unchanged?
                                               :acknowledged?])))
                state)]
    (cond
      (and (= :complete status)
           (or (true? (:no-changes? result))
               (true? (get-in result [:result :no-changes?]))))
      (message state :info
               "维护结束：预览显示没有需要写入的变化；资料保持原样。"
               {:code :BACKUP_NO_CHANGES :details result})

      (and (= :complete status)
           (or (true? (:already-imported? result))
               (true? (get-in result [:result :already-imported?]))))
      (message state :info
               "维护结束：此备份此前已导入；本次幂等跳过，没有新增记录。"
               {:code :BACKUP_ALREADY_IMPORTED :details result})

      (= :complete status)
      (message state :success
               "停机恢复完成；应用已重启。请核对待重新配置项与权限状态。"
               {:code :BACKUP_MAINTENANCE_COMPLETE :details result})

      (= :restart-failed status)
      (message state :error
               "恢复可能已完成，但应用没有成功重启；需要操作员处理，不能视为成功。"
               {:code :APP_RESTART_FAILED :details result})

      (= :stop-failed status)
      (message state :error
               "停止写入失败，恢复没有开始；当前资料没有改变。"
               {:code :BACKUP_QUIESCE_STOP_FAILED :details result})

      (and (true? (:acknowledged? result))
           (contains? #{:failed :failed-needs-attention
                        :failed-needs-recovery :failed-commit-unproven}
                      status))
      (message state :warning
               "操作员已记录确认；失败状态与证据保留，不表示恢复成功。该记录不再阻塞启动。"
               {:code :BACKUP_MAINTENANCE_ACKNOWLEDGED :details result})

      (contains? #{:failed :failed-needs-attention :failed-needs-recovery
                   :failed-commit-unproven :failed-before-commit}
                 status)
      (message state (if (or (= :failed-before-commit status)
                             (and (= :failed status)
                                  (not (true? (:needs-recovery? result)))
                                  (not (true? (:commit-unproven? result)))
                                  (or (false? (:restore-attempted? result))
                                      (true? (:store-unchanged? result)))))
                       :warning
                       :error)
               (maintenance-failure-text status result)
               {:code (if (= :failed-before-commit status)
                        :BACKUP_MAINTENANCE_BEFORE_COMMIT
                        :BACKUP_MAINTENANCE_RESTORE_FAILED)
                :details result})

      (= :cancelled status)
      (message state :info
               "维护已取消；请重新读取状态核对当前资料。"
               {:code :BACKUP_CANCELLED :details result})

      (nil? status)
      (message state :warning
               "维护状态未提供；不能判断恢复成功。请重新连接后核对。"
               {:code :BACKUP_MAINTENANCE_STATUS_UNKNOWN :details result})

      :else
      (message state :info
               (str "维护进行中："
                    (get maintenance-status-labels status (name status))
                    "。连接可能短暂中断，重连后继续核对。")
               {:code :BACKUP_MAINTENANCE_PROGRESS :details result}))))

(defn- apply-receipt-success
  [state command result]
  (case (:kind command)
    :backup/status (apply-status state command result)
    :backup/list
    (let [state (assoc-in state [:server :backups]
                          (vec (or (:backups result) [])))
          current (get-in state [:ui :message])]
      (if (or (nil? current) (= :backup/list-pending (:code current)))
        (message state :info "备份列表已更新。"
                 {:code :backup/list-loaded})
        state))
    :backup/create (apply-create state result)
    :backup/preview (apply-preview state result)
    :backup/verify (apply-verify state result)
    :backup/restore (apply-restore state result)
    :backup/delete (apply-delete state result)
    :backup/recovery-points
    (-> state
        (assoc-in [:server :recovery-points]
                  (vec (or (:recovery-points result) [])))
        (message :info "本地恢复点列表已更新。"
                 {:code :BACKUP_RECOVERY_POINTS}))
    :backup/prune (apply-prune state result)
    :backup/restore-recovery-point (apply-recovery-restore state result)
    :backup/maintenance-restore (apply-maintenance-result state result)
    :backup/maintenance-status (apply-maintenance-result state result)
    :backup/maintenance-restore-recovery-point
    (apply-maintenance-result state result)
    :backup/save-target
    (-> (refresh-status
         (cond-> (-> state
                      (assoc-in [:ui :target-editor]
                                (assoc default-target-editor :open? false))
                      (assoc-in [:ui :target-delete-id] nil))
           (contains? result :targets)
           (assoc-in [:server :targets] (vec (:targets result)))
           (contains? result :default-target-id)
           (assoc-in [:server :default-target-id]
                     (:default-target-id result))))
        (message :info "备份目标已保存。"
                 {:code :BACKUP_TARGET_SAVED}))
    :backup/test-target
    (let [probe (or (:probe result) result)
          ok? (true? (:ok? probe))
          detail (or (:detail probe) (:message probe))
          target-id (or (:target-id probe) (:target-id result))
          kind (or (:kind probe) (:kind result)
                   (get-in state [:ui :target-editor :kind]))]
      (-> state
          (assoc-in [:ui :target-test]
                    {:status (if ok? :ok :failed)
                     :detail detail
                     :kind kind
                     :target-id target-id})
          (message (if ok? :info :warning)
                   (if ok?
                     (str "连接测试通过"
                          (when detail (str "：" detail))
                          "。")
                     (str "连接测试失败"
                          (when detail (str "：" detail))
                          "。表单仍保留，可修改后重试。"))
                   {:code (if ok? :BACKUP_TARGET_TEST_OK
                              :BACKUP_TARGET_TEST_FAILED)
                    :details probe})))
    :backup/delete-target
    (message
     (refresh-status
      (cond-> (-> state
                   (assoc-in [:ui :target-delete-id] nil)
                   (assoc-in [:ui :target-editor]
                             (cond-> (:target-editor (:ui state))
                               (= (:target-id (:target-editor (:ui state)))
                                  (:target-id result))
                               (assoc :open? false))))
        (contains? result :targets)
        (assoc-in [:server :targets] (vec (:targets result)))
        (contains? result :default-target-id)
        (assoc-in [:server :default-target-id]
                  (:default-target-id result))))
     :info "备份目标已删除。"
     {:code :BACKUP_TARGET_DELETED})
    (message state :info "服务器已处理该备份命令。")))

(defn- error-available-categories
  [error]
  (let [details (or (:details error) (:data error))
        available (some-> details :available)]
    (when (sequential? available)
      (vec (filter #(contains? (set category-order) %)
                   (map normalize-keyword available))))))

(defn- apply-receipt-error
  [state command error]
  (let [view (error-view error)
        code (:code view)
        available (error-available-categories error)
        state (-> state
                  (assoc-in [:ui :message] nil)
                  (cond->
                    (contains? #{:BACKUP_WRONG_PASSWORD :BACKUP_INTEGRITY
                                 :BACKUP_FORMAT_INVALID :BACKUP_CONTAINER_INVALID
                                 :BACKUP_VERSION_UNSUPPORTED}
                               code)
                    (assoc-in [:ui :preview] nil)

                    (= :BACKUP_WRONG_PASSWORD code)
                    (assoc-in [:ui :preview-stale?] true)

                    (= :BACKUP_RESTORE_REQUIRES_QUIESCENCE code)
                    (assoc-in [:ui :maintenance]
                              {:code code
                               :title (:title view)
                               :text (:text view)
                               :steps ["停止所有 Owner、后台任务与写入者。"
                                       "确认没有正在应用的恢复或备份。"
                                       "在离线维护窗口运行恢复入口；浏览器不会自行设置 quiescent。"
                                       "恢复后重新启动，并检查待重新配置项。"]})

                    (contains? #{:BACKUP_STORAGE_FULL
                                 :BACKUP_RECOVERY_POINT_FAILED} code)
                    (assoc-in [:ui :recovery-state]
                              {:code code
                               :text (:text view)})

                    (contains? #{:APP_RESTART_FAILED
                                 :APP_NOT_QUIESCENT
                                 :BACKUP_MAINTENANCE_RESTORE_FAILED
                                 :BACKUP_QUIESCE_STOP_FAILED} code)
                    (apply-maintenance-error error)

                    (and (= :BACKUP_CATEGORY_MISSING code)
                         (seq available))
                    (assoc-in [:ui :restore-categories]
                              (zipmap category-order
                                      (map #(contains? (set available) %) category-order)))
                    (and (= :BACKUP_CATEGORY_MISSING code)
                         (seq available))
                    (assoc-in [:ui :preview-stale?] true)))
        state (cond
                (= :BACKUP_CANCELLED code)
                (message state :info (:text view) {:code code})

                (contains? #{:backup/save-target :backup/delete-target
                            :backup/test-target}
                           (:kind command))
                (message state :warning (:text view)
                         {:code code
                          :title (:title view)
                          :action (:action view)
                          :details (:details error)})

                (and (= :BACKUP_CATEGORY_MISSING code)
                     (seq available))
                (message state :warning
                         (str "备份只包含以下类别："
                              (str/join "、" (map #(get category-labels % (name %))
                                                  available))
                              "。已改为只选择这些类别，请重新预览。")
                         {:code code
                          :details error
                          :available available})

                :else
                (message state :error (:text view)
                         {:code code
                          :title (:title view)
                          :action (:action view)
                          :details (:details error)}))]
    (case (:kind command)
      (:backup/preview :backup/restore :backup/restore-recovery-point)
      (assoc-in state [:ui :restore-confirm?] false)
      :backup/test-target
      (assoc-in state [:ui :target-test]
                {:status :failed
                 :detail (str (or (:title view) "")
                              (when (:title view) "：")
                              (:text view))
                 :kind (get-in state [:ui :target-editor :kind])
                 :target-id (get-in state [:ui :target-editor :target-id])})
      state)))

(defn apply-receipt
  "Apply one receipt.  Only a matching command changes backup state."
  [state receipt]
  (let [{:keys [state command ok? error]} (commands/record-receipt state receipt)
        result (receipt-result receipt)]
    (redact-command-passwords
     (cond
       (nil? command)
       (message state :warning
                "收到未知命令的回执；未改变任何表单或备份状态。")

       (not ok?)
       (apply-receipt-error state command
                            (or error
                                {:code :BACKUP_FAILED
                                 :message "服务器拒绝了该命令"}))

       :else
       (apply-receipt-success state command result)))))

(defn mark-outcome-unknown
  [state command-id error]
  (let [state (commands/mark-outcome-unknown state command-id error)]
    (message state :warning
             "提交结果未知；请先核对备份列表或恢复点，不要自动重发。"
             {:code :backup/outcome-unknown
              :details {:command-id command-id
                        :error (or (:message error) (str error))}})))

(defn set-transport-available
  [state available?]
  (assoc-in state [:ui :transport-available?] (boolean available?)))

(defn set-sync
  [state connection]
  (let [connection (or connection {})
        phase (normalize-keyword (:phase connection))
        epoch (:command-epoch connection)]
    (assoc state :sync {:phase (or phase :disconnected)
                        :command-epoch epoch
                        :reason (:reason connection)
                        :epoch (:epoch connection)})))

(defn connection-projection
  "Project one Fulcro connection view into the backup sync slice.

  A database without `:ui/connection` is disconnected.  A reconnect attempt
  that failed is interrupted and carries no command epoch: an unconfirmed
  connection must not authorize backup commands."
  [connection]
  (let [connection (or connection {})
        phase (or (normalize-keyword (:phase connection)) :disconnected)]
    (if (= :interrupted phase)
      {:phase :interrupted
       :reason (or (:reason connection) :reconnect-failed)
       :command-allowed? false}
      (cond-> connection
        (contains? connection :phase) (assoc :phase phase)))))

;; ---------------------------------------------------------------------------
;; Actions

(defn- handle-set-field
  [state action]
  (let [field (normalize-keyword (:field action))
        value (:value action)]
    (cond
      (contains? #{:include-secrets? :include-deleted? :include-temp?
                   :include-cache? :include-external-files? :encrypt?
                   :confirm-plaintext-secrets? :confirm-memory-reactivation?
                   :restore-confirm?} field)
      (assoc-in state [:ui field] (boolean value))

      (contains? #{:password :password-confirm} field)
      (-> state
          (assoc-in [:ui :copy-feedback] nil)
          (assoc-in [:ui field] (str (or value ""))))

      :else state)))

(defn- handle-toggle-field
  [state action]
  (let [field (normalize-keyword (:field action))]
    (update-in state [:ui field] not)))

(defn- handle-toggle-category
  [state action]
  (let [restore? (= :restore (normalize-keyword (:scope action)))
        scope (if restore?
                :restore-categories
                :categories)
        category (normalize-keyword (:category action))]
    (if (contains? (set category-order) category)
      (cond-> (update-in state [:ui scope] toggle category)
        restore? (assoc-in [:ui :preview-stale?] true))
      state)))

(defn- handle-set-categories
  [state action]
  (let [restore? (= :restore (normalize-keyword (:scope action)))
        scope (if restore?
                :restore-categories
                :categories)
        value (true? (:value action))]
    (cond-> (assoc-in state [:ui scope] (set-all category-order value))
      restore? (assoc-in [:ui :preview-stale?] true))))

(defn- handle-select-backup
  [state action]
  (-> state
      (assoc-in [:ui :selected-backup-id] (:backup-id action))
      (assoc-in [:ui :preview] nil)
      (assoc-in [:ui :preview-stale?] false)
      (assoc-in [:ui :verify-result] nil)
      (assoc-in [:ui :resolutions] {})
      (assoc-in [:ui :restore-confirm?] false)
      (assoc-in [:ui :confirm-memory-reactivation?] false)
      (assoc-in [:ui :message] nil)))

(defn- handle-resolve-conflict
  [state action]
  (let [category (normalize-keyword (:category action))
        id (str (:id action))
        resolution (normalize-keyword (:resolution action))]
    (if (contains? #{:keep-local :use-backup :keep-both} resolution)
      (-> state
          (assoc-in [:ui :resolutions [category id]] resolution)
          (assoc-in [:ui :preview-stale?] true)
          (message :info "冲突选择已改变；请重新预览以应用选择。尚未写入任何资料。"
                   {:code :BACKUP_PREVIEW_STALE}))
      state)))

(defn- handle-create
  [state]
  (let [errors (create-validation state)]
    (if (seq errors)
      (message state :warning (:message (first errors))
               {:code (:code (first errors)) :details errors})
      (let [ui (:ui state)
            encrypt? (not (false? (:encrypt? ui)))
            payload (cond-> {:target-id (selected-target-id state)
                             :categories (selected-categories (:categories ui))
                             :encrypt? encrypt?
                             :include-secrets? (true? (:include-secrets? ui))
                             :confirm-plaintext-secrets?
                             (true? (:confirm-plaintext-secrets? ui))
                             :include-deleted? (true? (:include-deleted? ui))
                             :include-temp? (true? (:include-temp? ui))
                             :include-cache? (true? (:include-cache? ui))
                             :include-external-files?
                             (true? (:include-external-files? ui))}
                      encrypt? (assoc :password (str (:password ui))
                                      :password-confirm (str (:password-confirm ui))))]
        (submit state :backup/create payload)))))

(defn- handle-preview
  [state]
  (let [errors (preview-validation state)]
    (if (seq errors)
      (message state :warning (:message (first errors))
               {:code (:code (first errors)) :details errors})
      (let [ui (:ui state)
            backup (selected-backup state)
            payload (cond-> {:target-id (selected-target-id state)
                             :backup-id (:id backup)
                             :mode (or (normalize-keyword (:mode ui)) :merge)
                             :categories (selected-categories (:restore-categories ui))
                             :resolutions (get ui :resolutions {})
                             :confirm-memory-reactivation?
                             (true? (:confirm-memory-reactivation? ui))}
                      (not (empty? (str (:password ui))))
                      (assoc :password (str (:password ui))))]
        (submit state :backup/preview payload)))))

(defn- handle-verify
  [state]
  (let [backup (selected-backup state)
        check (restore-password-check state)]
    (cond
      (nil? backup)
      (message state :warning "先选择一份备份。" {:code :BACKUP_NOT_FOUND})

      (not (:ok? check))
      (message state :warning (:message check) {:code :BACKUP_WRONG_PASSWORD})

      :else
      (let [payload (cond-> {:target-id (selected-target-id state)
                             :backup-id (:id backup)}
                      (not (empty? (str (get-in state [:ui :password]))))
                      (assoc :password (str (get-in state [:ui :password]))))]
        (submit state :backup/verify payload)))))

(defn- handle-restore
  [state]
  (let [errors (restore-validation state)]
    (if (seq errors)
      (let [offline? (some #(= :BACKUP_RESTORE_REQUIRES_QUIESCENCE (:code %))
                           errors)
            state (if offline?
                    (assoc-in state [:ui :maintenance]
                              {:code :BACKUP_RESTORE_REQUIRES_QUIESCENCE
                               :title "需要离线维护窗口"
                               :text "在线恢复被服务器禁用。当前资料没有改变；浏览器不会把 quiescent 当作可由页面自行声明的开关。"
                               :steps ["停止所有 Owner、后台任务与写入者。"
                                       "确认没有正在应用的恢复或备份。"
                                       "在应用停止时运行 hc.server.backup.cli restore；密码从 HC_BACKUP_PASSWORD 或 --password-file 读取，不放进 argv。"
                                       "恢复后重新启动，并检查待重新配置项。"]})
                    state)]
        (message state :warning (:message (first errors))
                 {:code (:code (first errors)) :details errors}))
      (let [ui (:ui state)
            backup (selected-backup state)
            payload (cond-> {:target-id (selected-target-id state)
                             :backup-id (:id backup)
                             :mode (or (normalize-keyword (:mode ui)) :merge)
                             :categories (selected-categories (:restore-categories ui))
                             :resolutions (get ui :resolutions {})
                             :confirm-memory-reactivation?
                             (true? (:confirm-memory-reactivation? ui))}
                      (not (empty? (str (:password ui))))
                      (assoc :password (str (:password ui))))]
        (submit state :backup/restore payload)))))

(defn- handle-maintenance-restore
  [state]
  (let [errors (maintenance-validation state)]
    (if (seq errors)
      (message state :warning (:message (first errors))
               {:code (:code (first errors)) :details errors})
      (let [ui (:ui state)
            backup (selected-backup state)
            maintenance-id (str (random-uuid))
            payload (cond-> {:target-id (selected-target-id state)
                             :backup-id (:id backup)
                             :mode (or (normalize-keyword (:mode ui)) :merge)
                             :categories (selected-categories (:restore-categories ui))
                             :resolutions (get ui :resolutions {})
                             :confirm-memory-reactivation?
                             (true? (:confirm-memory-reactivation? ui))
                             :maintenance-id maintenance-id
                             :confirm? true}
                      (not (empty? (str (:password ui))))
                      (assoc :password (str (:password ui)))

                      (get-in state [:ui :preview :fingerprint])
                      (assoc :preview-fingerprint
                             (get-in state [:ui :preview :fingerprint])))
            queued (commands/queue state :backup/maintenance-restore payload)]
        (if (:ok? queued)
          (-> (:state queued)
              (assoc-in [:ui :maintenance-request]
                        {:id maintenance-id
                         :maintenance-id maintenance-id
                         :status :accepted
                         :phase :stopping
                         :backup-id (:id backup)
                         :needs-attention? false})
              (message :info
                       "维护已请求；服务器确认停止写入前不会宣布成功。连接可能短暂中断。"
                       {:code :BACKUP_MAINTENANCE_REQUESTED}))
          (message state :warning
                   (or (get-in queued [:error :message])
                       "维护命令未提交；表单仍保留。")
                   {:code (get-in queued [:error :code])}))))))

(defn- handle-maintenance-status
  [state action]
  (let [id (or (:maintenance-id action)
               (get-in (maintenance-view state) [:active :id])
               (get-in (maintenance-view state) [:active :maintenance-id])
               (get-in state [:ui :maintenance-request :id])
               (get-in state [:ui :maintenance-request :maintenance-id])
               (get-in (maintenance-view state) [:last :id])
               (get-in (maintenance-view state) [:last :maintenance-id]))]
    (if id
      (submit state :backup/maintenance-status {:maintenance-id id})
      (message state :warning
               "没有可核对的维护操作。"
               {:code :BACKUP_MAINTENANCE_STATUS_UNKNOWN}))))

(defn- handle-maintenance-restore-point
  [state action]
  (let [recovery-point-id (or (:recovery-point-id action)
                              (get-in state [:ui :recovery-confirm-id]))
        errors (maintenance-recovery-point-validation state recovery-point-id)]
    (if (seq errors)
      (message state :warning (:message (first errors))
               {:code (:code (first errors)) :details errors})
      (let [maintenance-id (str (random-uuid))
            payload {:recovery-point-id recovery-point-id
                     :maintenance-id maintenance-id
                     :confirm? true}
            queued (commands/queue state
                                   :backup/maintenance-restore-recovery-point
                                   payload)]
        (if (:ok? queued)
          (-> (:state queued)
              (assoc-in [:ui :recovery-confirm-id] nil)
              (assoc-in [:ui :maintenance-request]
                        {:id maintenance-id
                         :maintenance-id maintenance-id
                         :status :accepted
                         :phase :stopping
                         :recovery-point-id recovery-point-id
                         :needs-attention? false})
              (message :info
                       "停机回退已请求；服务器确认停止写入前不会宣布成功。连接可能短暂中断。"
                       {:code :BACKUP_MAINTENANCE_REQUESTED}))
          (message state :warning
                   (or (get-in queued [:error :message])
                       "维护命令未提交；表单仍保留。")
                   {:code (get-in queued [:error :code])}))))))

(defn- handle-delete
  [state action]
  (let [id (or (:backup-id action)
               (get-in state [:ui :delete-confirm-id]))]
    (if id
      (submit state :backup/delete {:target-id (selected-target-id state)
                                    :backup-id id :confirm? true})
      (message state :warning "先选择要删除的备份。" {:code :BACKUP_NOT_FOUND}))))

(defn- target-editor-row
  "Find one `:server :targets` row by id for editor backfill.  Rows keep the
  projected fields plus any non-secret extras the status projection sent."
  [state target-id]
  (some #(when (= target-id (:id %)) %) (:targets (target-view state))))

(defn- target-editor-from-row
  [row set-default?]
  (let [kind (normalize-keyword (:kind row))]
    (-> default-target-editor
        (assoc :open? true
               :target-id (:id row)
               :kind (or kind :webdav)
               :endpoint (str (or (:endpoint row) ""))
               :bucket (str (or (:bucket row) ""))
               :region (str (or (:region row) ""))
               :access-key (str (or (:access-key row) ""))
               :prefix (str (or (:prefix row) ""))
               :remote-url (str (or (:base-url row) (:remote-url row) ""))
               :username (str (or (:username row) ""))
               :directory (str (or (:directory row) (:root row) ""))
               :set-default? (boolean set-default?)))))

(defn- handle-toggle-target-editor
  [state]
  (let [editor (get-in state [:ui :target-editor] default-target-editor)
        opening? (not (true? (:open? editor)))
        editor (if (and opening? (nil? (:target-id editor)))
                 default-target-editor
                 editor)]
    (assoc-in state [:ui :target-editor]
              (assoc editor :open? opening?))))

(defn- handle-new-target
  [state]
  (-> state
      (assoc-in [:ui :target-editor] (assoc default-target-editor
                                            :open? true))
      (assoc-in [:ui :target-delete-id] nil)
      (assoc-in [:ui :target-test]
                {:status :idle :detail nil :kind nil :target-id nil})))

(defn- handle-edit-target
  [state action]
  (let [target-id (or (:target-id action)
                      (get-in state [:ui :target-delete-id]))]
    (if-let [row (target-editor-row state target-id)]
      (let [set-default? (or (= target-id
                                 (get-in state [:server :default-target-id]))
                            (= target-id (:target-id (:ui state))))]
        (-> state
            (assoc-in [:ui :target-editor]
                      (target-editor-from-row row set-default?))
            (assoc-in [:ui :target-test]
                      {:status :idle :detail nil
                       :kind (:kind row) :target-id target-id})))
      (message state :warning "找不到该备份目标；请刷新状态后重试。"
               {:code :BACKUP_TARGET_UNKNOWN}))))

(defn- handle-set-target-field
  [state action]
  (let [field (normalize-keyword (:field action))
        value (:value action)]
    (if (contains? target-editor-fields field)
      (update-in state [:ui :target-editor]
                 (fn [editor]
                   (cond-> (assoc editor field value)
                     (= :kind field)
                     (update :kind normalize-keyword))))
      state)))

(defn- handle-save-target
  [state]
  (let [editor (get-in state [:ui :target-editor] default-target-editor)]
    (if-not (target-config-ready? editor)
      (message state :warning
               "请填写当前类型的必填字段后再保存。"
               {:code :BACKUP_TARGET_INVALID})
      (submit state :backup/save-target
              {:target-id (:target-id editor)
               :config (target-config-payload editor)
               :default? (boolean (:set-default? editor))}))))

(defn- handle-test-target
  [state]
  (let [editor (get-in state [:ui :target-editor] default-target-editor)]
    (if-not (target-config-ready? editor)
      (message state :warning
               "请填写当前类型的必填字段后再测试连接。"
               {:code :BACKUP_TARGET_INVALID})
      (submit state :backup/test-target
              {:config (target-config-payload editor)
               :command-key (target-test-command-key editor)}))))

(defn- handle-request-delete-target
  [state action]
  (assoc-in state [:ui :target-delete-id]
            (or (:target-id action)
                (get-in state [:ui :target-delete-id]))))

(defn- handle-delete-target
  [state]
  (let [target-id (get-in state [:ui :target-delete-id])]
    (if target-id
      ;; The contract clears the confirmation only once the command is
      ;; accepted; a refused queue keeps the pending id for a retry.
      (let [before (count (:outbox state))
            queued (submit state :backup/delete-target
                            {:target-id target-id})]
        (if (> (count (:outbox queued)) before)
          (assoc-in queued [:ui :target-delete-id] nil)
          queued))
      (message state :warning "先选择要删除的备份目标。"
               {:code :BACKUP_TARGET_UNKNOWN}))))

(defn- handle-recovery-restore
  [state action]
  (let [id (or (:recovery-point-id action)
               (get-in state [:ui :recovery-confirm-id]))]
    (if (nil? id)
      (message state :warning "先选择一个本地恢复点。" {:code :BACKUP_NOT_FOUND})
      (-> state
          (assoc-in [:ui :recovery-confirm-id] nil)
          (assoc-in [:ui :maintenance]
                    {:code :BACKUP_RESTORE_REQUIRES_QUIESCENCE
                     :title "需要离线维护窗口"
                     :text "在线回退被服务器禁用。当前资料没有改变。"
                     :steps ["停止所有 Owner、后台任务与写入者。"
                             "在应用停止时运行 hc.server.backup.cli restore-recovery-point；密码从 HC_BACKUP_PASSWORD 或 --password-file 读取。"
                             "恢复后重新启动，并核对恢复结果。"]})
          (message :warning
                   "在线恢复点回退被服务器禁用；请使用离线维护窗口。当前资料没有改变。"
                   {:code :BACKUP_RESTORE_REQUIRES_QUIESCENCE})))))

(defn- handle-select-target
  [state action]
  (let [target-id (or (:target-id action)
                      (get-in state [:ui :target-id]))
        state (-> state
                  (assoc-in [:ui :target-id] target-id)
                  (assoc-in [:ui :selected-backup-id] nil)
                  (assoc-in [:ui :preview] nil)
                  (assoc-in [:ui :preview-stale?] false)
                  (assoc-in [:ui :verify-result] nil)
                  (assoc-in [:ui :resolutions] {})
                  (assoc-in [:ui :delete-confirm-id] nil)
                  (assoc-in [:server :backups] [])
                  (assoc-in [:ui :message] nil))]
    (submit state :backup/status {:target-id target-id})))

(def preference-keys
  "Non-secret options that may survive a page reload.  Passwords and the two
  danger confirmations are deliberately absent."
  #{:encrypt? :include-secrets? :include-deleted? :include-temp? :include-cache?
    :include-external-files? :categories :restore-categories :mode :section})

(defn apply-preferences
  "Merge persisted non-secret preferences into STATE.  Unknown keys, password
  fields and confirmations are ignored."
  [state prefs]
  (let [prefs (select-keys (or prefs {}) preference-keys)
        prefs (cond-> prefs
                (:categories prefs)
                (update :categories
                        #(zipmap category-order
                                 (map (fn [category]
                                        (boolean (get % category false)))
                                      category-order)))

                (:restore-categories prefs)
                (update :restore-categories
                        #(zipmap category-order
                                 (map (fn [category]
                                        (boolean (get % category false)))
                                      category-order)))

                (:mode prefs)
                (update :mode #(if (contains? #{:merge :replace}
                                              (normalize-keyword %))
                                 (normalize-keyword %)
                                 :merge))

                (:section prefs)
                (update :section #(if (contains? #{:backup :restore
                                                   :recovery :status}
                                                 (normalize-keyword %))
                                    (normalize-keyword %)
                                    :backup)))]
    (update state :ui merge prefs)))

(defn reduce-action
  "Apply one UI action.  Returns `{:state state' :effects effects}`.

  Actions never call a transport.  Queued commands wait in `:outbox` for the
  bridge to submit once."
  [state action]
  (let [type (action-type (:type action))]
    (case type
      :backup/refresh
      (let [manual? (= :manual (:origin action))]
        (result (submit state :backup/status
                        (cond-> {:target-id (selected-target-id state)}
                          (:origin action)
                          (assoc :origin (:origin action))

                          (true? (:silent? action))
                          (assoc :silent? true))
                        {:silent? (or (not manual?)
                                      (true? (:silent? action)))})))
      :backup/refresh-recovery
      (result (submit state :backup/recovery-points {}))

      :backup/select-target
      (result (handle-select-target state action))

      :backup/toggle-target-editor
      (result (handle-toggle-target-editor state))

      :backup/new-target
      (result (handle-new-target state))

      :backup/edit-target
      (result (handle-edit-target state action))

      :backup/set-target-field
      (result (handle-set-target-field state action))

      :backup/save-target
      (result (handle-save-target state))

      :backup/test-target
      (result (handle-test-target state))

      :backup/request-delete-target
      (result (handle-request-delete-target state action))

      :backup/cancel-delete-target
      (result (assoc-in state [:ui :target-delete-id] nil))

      :backup/delete-target
      (result (handle-delete-target state))

      :backup/select-section
      (result (assoc-in state [:ui :section]
                        (normalize-keyword (:section action))))

      :backup/set-field
      (result (handle-set-field state action))

      :backup/toggle-field
      (result (handle-toggle-field state action))

      :backup/toggle-category
      (result (handle-toggle-category state action))

      :backup/set-categories
      (result (handle-set-categories state action))

      :backup/select-backup
      (result (handle-select-backup state action))

      :backup/set-mode
      (result (-> state
                  (assoc-in [:ui :mode]
                            (normalize-keyword (:mode action)))
                  (assoc-in [:ui :preview-stale?] true)
                  (assoc-in [:ui :restore-confirm?] false)))

      :backup/resolve-conflict
      (result (handle-resolve-conflict state action))

      :backup/toggle-restore-confirm
      (result (handle-toggle-field state {:field :restore-confirm?}))

      :backup/toggle-memory-reactivation
      (result (handle-toggle-field state
                                   {:field :confirm-memory-reactivation?}))

      :backup/create
      (result (handle-create state))

      :backup/preview
      (result (handle-preview state))

      :backup/verify
      (result (handle-verify state))

      :backup/request-restore
      (result (handle-restore state))

      :backup/request-maintenance-restore
      (result (handle-maintenance-restore state))

      :backup/refresh-maintenance
      (result (handle-maintenance-status state action))

      :backup/request-maintenance-restore-point
      (result (handle-maintenance-restore-point state action))

      :backup/apply-maintenance-event
      (result (apply-maintenance-result state (:event action)))

      :backup/request-delete
      (result (assoc-in state [:ui :delete-confirm-id] (:backup-id action)))

      :backup/cancel-delete
      (result (assoc-in state [:ui :delete-confirm-id] nil))

      :backup/confirm-delete
      (result (handle-delete state action))

      :backup/request-recovery-restore
      (result (assoc-in state [:ui :recovery-confirm-id]
                        (:recovery-point-id action)))

      :backup/cancel-recovery-restore
      (result (assoc-in state [:ui :recovery-confirm-id] nil))

      :backup/confirm-recovery-restore
      (result (handle-recovery-restore state action))

      :backup/request-prune
      (result (assoc-in state [:ui :prune-confirm?] true))

      :backup/cancel-prune
      (result (assoc-in state [:ui :prune-confirm?] false))

      :backup/confirm-prune
      (result (-> state
                  (assoc-in [:ui :prune-confirm?] false)
                  (submit :backup/prune {})))

      :backup/clear-password
      (result (clear-password state))

      :backup/copy-password
      (let [password (str (get-in state [:ui :password]))]
        (if (empty? password)
          (result (copy-feedback state :failed
                                 "没有可复制的密码。"
                                 :BACKUP_PASSWORD_EMPTY))
          {:state (copy-feedback state :copying
                                 "正在复制密码…"
                                 :BACKUP_PASSWORD_COPYING)
           :effects [{:type :backup/copy-text :text password}]}))

      :backup/copy-result
      (result
       (if (true? (:ok? action))
         (copy-feedback state :copied
                        "已复制到剪贴板；不会写入本地存储。"
                        :BACKUP_PASSWORD_COPIED)
         (copy-feedback state :failed
                        "复制失败：浏览器拒绝或没有剪贴板权限；请手动选择并复制。"
                        :BACKUP_PASSWORD_COPY_FAILED)))

      :backup/apply-receipt
      (result (apply-receipt state (:receipt action)))

      :backup/mark-outcome-unknown
      (result (mark-outcome-unknown state
                                    (:command-id action)
                                    (:error action)))

      :backup/set-transport-available
      (result (set-transport-available state (:available? action)))

      :backup/set-sync
      (result (set-sync state (:connection action)))

      :backup/dismiss-message
      (result (dismiss-message state))

      :backup/restore-prefs
      (result (apply-preferences state (:prefs action)))

      (result state))))
