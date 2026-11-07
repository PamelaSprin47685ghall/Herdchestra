(ns hc.client.copy.failures
  "Single source of truth for the 24 user-facing failure copies in
  AGENTS.md §7.4 旅程 8（设置与诊断） / §7.7.

  This namespace is pure.  Callers pass an error code and a small context;
  the result is `{:title :body :primary-action :secondary-action :severity}`.
  Actions carry an executable command keyword, not an ambiguous \"retry\".

  Two rules are enforced here, not in each view:
  - authentication failure, permission revocation, and a missing model are
    separate kinds and never share a title/body;
  - the words \"已保留\" and \"已保存\" appear only when `:saved? true`.
  Unknown usage is described as unknown, never as zero."
  (:require [clojure.string :as str]))

(def failure-kinds
  "The 24 contract kinds.  The keyword is stable; callers should prefer it."
  [:no-model :connection-disabled :model-removed :invalid-credentials
   :authorization-expired :quota-exhausted :rate-limited :network-unreachable
   :response-timeout :input-too-large :attachment-unsupported
   :partial-materials :local-storage-full :local-data-unreadable
   :external-file-moved :permission-revoked :tool-failed :receipt-lost
   :schedule-missed :backup-password-invalid :import-format-unsupported
   :restore-conflict :format-too-new :export-cancelled])

(declare normalize-code)

(def failure-reason-categories
  "The ten non-delivery reason categories from AGENTS.md §7.4 第 8 段.

  These are ten different reasons a step was not delivered, not ten
  parallel status words.  A view shows the category first, then the
  concrete kind's user explanation (:title/:body) and primary action.
  `:kinds` lists every copy key that belongs to the category;
  `:transport-disconnected` rides along in category four even though it
  is not one of the 24 contract kinds."
  [{:id :no-usable-model
    :number "一"
    :label "还没有一个能用的模型"
    :explanation "还没有选好，或者原来选中的对象已经不在。草稿和历史留在原处，不因为缺一个模型就清空。"
    :kinds [:no-model :connection-disabled :model-removed]}
   {:id :credentials-or-authorization
    :number "二"
    :label "服务没有接受这组凭据或这次授权"
    :explanation "服务端拒绝的是身份，不是内容；不要把这类理由写成网络问题，也不要把凭据送到另一个服务去试。"
    :kinds [:invalid-credentials :authorization-expired]}
   {:id :quota-or-rate-limited
    :number "三"
    :label "服务按额度或频率拒绝了这次请求"
    :explanation "这次请求没有继续，但本轮已经生成或已经落盘的内容还在；额度与限流是服务侧的决定，不是用户的输入有错。"
    :kinds [:quota-exhausted :rate-limited]}
   {:id :connection-or-response
    :number "四"
    :label "连接没有建立，或回应没有到达"
    :explanation "要分开说：是连不上所选服务，还是连上了但服务还没有返回内容。前者可以换网络或检查地址，后者可以继续等或停止。"
    :kinds [:network-unreachable :response-timeout :transport-disconnected]}
   {:id :content-out-of-range
    :number "五"
    :label "内容超出了所选模型能接收的范围"
    :explanation "对象是这次要发送的内容或附件，不是整个账号；说明要给出可调整的范围，或给出替代的处理方式。"
    :kinds [:input-too-large :attachment-unsupported]}
   {:id :partial-materials
    :number "六"
    :label "材料只读进来一部分"
    :explanation "已读入的内容可以用，没读进来的部分要按对象点名；不能把一次部分读入写成全部读完。"
    :kinds [:partial-materials]}
   {:id :local-storage-or-file
    :number "七"
    :label "本地没有空间，或打不开这份材料"
    :explanation "问题在本机的存储或文件，不在远端服务；先保住已经确认的内容，再给释放空间、导出或重新定位的入口。"
    :kinds [:local-storage-full :local-data-unreadable :external-file-moved]}
   {:id :permission-or-outcome-unknown
    :number "八"
    :label "权限被关闭，或执行结果无法确认"
    :explanation "权限被撤销时给恢复路径；回执丢失时先核对目标状态，绝不第二次提交。"
    :kinds [:permission-revoked :tool-failed :receipt-lost]}
   {:id :schedule-missed
    :number "九"
    :label "计划的时间已经过去"
    :explanation "这是时间条件没有满足，不是任务内容有错；错过就是错过，不集中补跑，也不假装已经跑过。"
    :kinds [:schedule-missed]}
   {:id :backup-export-undeliverable
    :number "十"
    :label "恢复或导出的材料不能交付"
    :explanation "密码错、格式不识别、内容冲突、版本过新、用户取消是五种不同理由，不能合并成一句操作失败；取消是普通结果，不显示红色错误。"
    :kinds [:backup-password-invalid :import-format-unsupported
            :restore-conflict :format-too-new :export-cancelled]}])

(def ^:private indeterminate-reason
  "Fallback category for codes that match no contract kind.  It is not
  one of the ten; the diagnostics page uses it to say the concrete cause
  cannot be determined and lists actionable checks instead of guessing."
  {:id :indeterminate
   :number nil
   :label "无法确定具体原因"
   :explanation "诊断页优先指出可确认的类别；不能确定时列出可操作的检查顺序，不编造看似专业的根因。"
   :kinds []})

(def ^:private kind->category
  (into {}
        (mapcat (fn [{:keys [id kinds]}]
                  (map (fn [k] [k id]) kinds)))
        failure-reason-categories))

(defn failure-category
  "Return the reason-category id for a failure CODE, or `:indeterminate`
  when the code matches no contract kind.  Accepts the same keyword,
  underscored keyword, or string forms as `failure-copy`."
  [code]
  (let [kind (normalize-code code)]
    (get kind->category kind :indeterminate)))

(defn failure-reason
  "Return the full reason-category descriptor for a failure CODE.
  Unknown codes get `indeterminate-reason`; they never borrow another
  category's cause."
  [code]
  (let [id (failure-category code)]
    (or (some #(when (= (:id %) id) %) failure-reason-categories)
        indeterminate-reason)))

(def ^:private copies
  {:no-model
   {:title "尚未选择模型"
    :body "先选择一个可用模型；输入内容仍在输入框。"
    :primary-action {:label "选择模型" :command :settings/select-model}
    :severity :warning
    :draft? true}

   :connection-disabled
   {:title "供应商未启用"
    :body "当前模型所属的连接已停用；请启用连接或选择其他模型。"
    :primary-action {:label "打开连接设置" :command :settings/open-connection}
    :severity :warning
    :draft? true}

   :model-removed
   {:title "模型被移除"
    :body "原模型已不可用，历史回答仍可阅读；请选择替代模型。"
    :primary-action {:label "选择替代模型" :command :settings/select-model}
    :severity :warning
    :draft? true}

   :invalid-credentials
   {:title "凭据无效"
    :body "服务没有接受这组凭据；请更新凭据或改选连接。"
    :primary-action {:label "更新凭据" :command :settings/update-credentials}
    :secondary-action {:label "改选连接" :command :settings/open-connection}
    :severity :error}

   :authorization-expired
   {:title "账号授权过期"
    :body "需要重新连接这个账号；当前资料没有被改写。"
    :primary-action {:label "重新登录" :command :model-auth/restart}
    :severity :warning}

   :quota-exhausted
   {:title "额度不足"
    :body "这家服务报告额度不足，本次未继续；请查看服务或换模型。"
    :primary-action {:label "查看服务或换模型" :command :settings/open-connection}
    :secondary-action {:label "停止" :command :command/stop-run}
    :severity :error}

   :rate-limited
   {:title "服务限流"
    :body "服务暂时限制请求；请等待后重试或取消。"
    :primary-action {:label "等待后重试" :command :command/retry-after}
    :secondary-action {:label "取消本轮" :command :command/stop-run}
    :severity :warning
    :draft? false}

   :network-unreachable
   {:title "网络不可达"
    :body "无法连接到所选服务；请检查连接或稍后重试。"
    :primary-action {:label "检查连接" :command :settings/test-connection}
    :severity :error}

   :transport-disconnected
   {:title "连接已断开"
    :body "与服务器的实时连接已断开；请重新连接后重试。"
    :primary-action {:label "重新连接" :command :app/reconnect}
    :severity :error}

   :response-timeout
   {:title "响应迟迟未到"
    :body "服务尚未返回内容；可以继续等待或停止当前生成。"
    :primary-action {:label "停止当前生成" :command :command/stop-run}
    :secondary-action {:label "继续等待" :command :command/wait}
    :severity :warning}

   :input-too-large
   {:title "输入超出容量"
    :body "当前内容超过所选模型可接受的范围；发送前会阻止，历史仍可阅读。"
    :primary-action {:label "调整内容范围" :command :composer/adjust-content}
    :secondary-action {:label "换模型" :command :settings/select-model}
    :severity :warning
    :draft? true}

   :attachment-unsupported
   {:title "附件类型不支持"
    :body "当前模型不能接收这类附件；请换模型或改变处理方式。"
    :primary-action {:label "换模型" :command :settings/select-model}
    :secondary-action {:label "改用提取文本" :command :attachments/use-text}
    :severity :warning
    :draft? true}

   :partial-materials
   {:title "部分资料不可读"
    :body "已读入部分内容，仍有资料未成功处理；请查看缺失部分。"
    :primary-action {:label "查看缺失部分" :command :attachments/inspect-missing}
    :severity :warning
    :draft? true}

   :local-storage-full
   {:title "本地空间不足"
    :body "空间不足，暂时无法保存更多内容；请释放空间或导出当前内容。"
    :primary-action {:label "释放空间" :command :app/free-space}
    :secondary-action {:label "导出当前内容" :command :app/export-draft}
    :severity :error
    :draft? true}

   :local-data-unreadable
   {:title "本地资料不可读"
    :body "无法打开这份资料，原文件未被替换；请查看恢复选项。"
    :primary-action {:label "查看恢复选项" :command :app/open-recovery}
    :severity :error}

   :external-file-moved
   {:title "外部文件已移动"
    :body "找不到原位置的文件；请重新定位或移除链接。"
    :primary-action {:label "重新定位" :command :workspace/relocate}
    :secondary-action {:label "移除链接" :command :workspace/remove-link}
    :severity :warning}

   :permission-revoked
   {:title "权限被撤销"
    :body "这项操作需要的权限已关闭；请恢复权限或选择其他方式。"
    :primary-action {:label "恢复权限" :command :permissions/restore}
    :secondary-action {:label "选择其他方式" :command :app/choose-alternative}
    :severity :warning}

   :tool-failed
   {:title "工具执行失败"
    :body "这一步没有完成，已完成的步骤仍保留；请查看原因和后续选择。"
    :primary-action {:label "查看原因" :command :conversation/open-process}
    :severity :error}

   :receipt-lost
   {:title "结果回执丢失"
    :body "操作可能已执行，需要先核对结果；不要自动重发。"
    :primary-action {:label "检查目标状态" :command :command/check-target}
    :severity :warning}

   :schedule-missed
   {:title "任务错过时间"
    :body "本次执行时间已错过，没有自动补跑；请手动运行或查看下次时间。"
    :primary-action {:label "手动运行" :command :schedule/run-now}
    :secondary-action {:label "查看下次时间" :command :schedule/open}
    :severity :info}

   :backup-password-invalid
   {:title "备份密码不正确"
    :body "无法解锁此备份，当前资料没有改变；请重新输入密码。"
    :primary-action {:label "重新输入密码" :command :backup/reenter-password}
    :severity :error}

   :import-format-unsupported
   {:title "导入格式不支持"
    :body "无法识别这份资料的格式；当前资料没有改变。"
    :primary-action {:label "查看支持范围" :command :backup/supported-formats}
    :severity :error}

   :restore-conflict
   {:title "恢复发生冲突"
    :body "新旧资料包含需要你选择的差异；没有写入任何资料。"
    :primary-action {:label "查看冲突并决定" :command :backup/review-conflicts}
    :severity :warning}

   :format-too-new
   {:title "版本过新"
    :body "此资料由较新的格式保存；请更新应用或保留原文件。"
    :primary-action {:label "更新应用" :command :app/update}
    :secondary-action {:label "保留原文件" :command :app/keep-file}
    :severity :warning}

   :export-cancelled
   {:title "导出被取消"
    :body "没有生成最终导出文件；请返回或重新导出。"
    :primary-action {:label "重新导出" :command :export/restart}
    :secondary-action {:label "返回" :command :app/back}
    :severity :info}})

(def ^:private aliases
  {:no_model :no-model
   :connection_disabled :connection-disabled
   :model_removed :model-removed
   :model_missing :model-removed
   :invalid_credentials :invalid-credentials
   :auth_invalid :invalid-credentials
   :permission_denied :permission-revoked
   :authorization_expired :authorization-expired
   :quota_exhausted :quota-exhausted
   :rate_limited :rate-limited
   :network_unreachable :network-unreachable
   :transport_disconnected :transport-disconnected
   :response_timeout :response-timeout
   :input_too_large :input-too-large
   :attachment_unsupported :attachment-unsupported
   :partial_materials :partial-materials
   :local_storage_full :local-storage-full
   :local_data_unreadable :local-data-unreadable
   :external_file_moved :external-file-moved
   :permission_revoked :permission-revoked
   :tool_failed :tool-failed
   :receipt_lost :receipt-lost
   :schedule_missed :schedule-missed
   :backup_password_invalid :backup-password-invalid
   :backup_wrong_password :backup-password-invalid
   :import_format_unsupported :import-format-unsupported
   :backup_format_invalid :import-format-unsupported
   :restore_conflict :restore-conflict
   :backup_object_conflict :restore-conflict
   :format_too_new :format-too-new
   :backup_version_unsupported :format-too-new
   :export_cancelled :export-cancelled
   :search_quota_exceeded :quota-exhausted
   :search_timeout :response-timeout
   :search_credential_invalid :invalid-credentials
   :command_store_failed :local-storage-full})

(defn- normalize-code
  [code]
  (let [k (cond
            (keyword? code) (keyword (str/lower-case (name code)))
            (string? code) (keyword (str/lower-case (str/replace code #"^:" "")))
            :else nil)
        underscored (when k (keyword (str/replace (name k) "-" "_")))]
    (or (get aliases underscored) (get aliases k) k)))

(defn- saved-suffix
  [context draft?]
  (when draft?
    (if (true? (:saved? context))
      "草稿已保留。"
      "草稿仍在输入框，尚未确认本地保存。")))

(defn- usage-suffix
  [context]
  (when (true? (:unknown-usage? context))
    "用量未知，不按零计算。"))

(defn failure-copy
  "Return the stable copy for one failure CODE.

  CONTEXT keys used here: `:saved?`, `:unknown-usage?`.  Unknown codes fall
  back to a deliberately non-specific copy; they never borrow another
  category's cause."
  [{:keys [code context]}]
  (let [kind (normalize-code code)
        context (or context {})
        base (get copies kind)
        reason-id (failure-category kind)]
    (if-not base
      {:title "无法确定具体原因"
       :body "请查看诊断信息并按可执行检查顺序处理；不要猜测根因。"
       :primary-action {:label "查看诊断" :command :diagnostics/open-bundle}
       :secondary-action nil
       :severity :error
       :reason-category reason-id}
      (let [body (cond-> (:body base)
                   (and (= kind :rate-limited) (true? (:saved? context)))
                   (str " 已保留本轮内容。")

                   (and (= kind :rate-limited) (not (true? (:saved? context))))
                   (str " 本轮内容仍在输入框，尚未确认本地保存。")

                   (and (not= kind :rate-limited)
                        (saved-suffix context (:draft? base)))
                   (str " " (saved-suffix context (:draft? base)))

                   (usage-suffix context)
                   (str " " (usage-suffix context)))]
        (-> base
            (dissoc :draft?)
            (assoc :body body
                   :reason-category reason-id))))))
