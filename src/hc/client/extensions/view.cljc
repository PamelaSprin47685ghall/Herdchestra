(ns hc.client.extensions.view
  "Pure view tree for MCP and skill management.

  The tree is rendered by `hc.client.extensions.react`.  Every enabled control
  maps to a real server command; unavailable controls are disabled with the
  server-reported reason.  No switch is shown as enabled before a server
  snapshot or accepted receipt says so."
  (:require [clojure.string :as str]
            [hc.client.extensions.model :as model]))

(defn- el
  ([tag attrs] {:tag tag :attrs attrs :children []})
  ([tag attrs & children]
   {:tag tag :attrs attrs :children (vec children)}))

(defn- text [value] (str (or value "")))

(defn- action
  ([type] {:action {:type type}})
  ([type k v & kvs]
   {:action (if (nil? k)
              {:type type}
              (apply assoc {:type type} k v kvs))}))

(defn- on-click [type & args]
  {:on-click (apply action type args)})

(defn- on-input [type & args]
  {:on-change (assoc (apply action type args) :value :target-value)})

(defn- on-checked [type & args]
  {:on-change (assoc (apply action type args) :value :target-checked)})

(defn- on-file [type & args]
  {:on-change (assoc (apply action type args)
                     :value :target-file
                     :file-base64? true)})

(defn- badge [label tone]
  (el :span {:class "hc-ext-badge" :data-tone (name (or tone :neutral))}
      (text label)))

(defn- by-name
  "Sort key that tolerates a server entry without a name."
  [item]
  (str/lower-case (str (:name item))))

(defn- button
  ([label type] (button label type nil))
  ([label type {:keys [primary? danger? disabled? title args]
                :or {args []}}]
   (el :button {:type "button"
                :class (str "wb-button wb-button-small"
                            (when primary? " wb-button-primary")
                            (when danger? " wb-button-danger"))
                :disabled (boolean disabled?)
                :title title
                :events (apply on-click type args)}
       label)))

(defn- checkbox
  [label checked? type & args]
  (let [opts (when (map? (last args)) (last args))
        action-args (if opts (butlast args) args)]
    (el :label {:class "hc-ext-check"}
        (el :input {:type "checkbox"
                    :checked (boolean checked?)
                    :disabled (boolean (:disabled? opts))
                    :title (:title opts)
                    :class "hc-ext-checkbox"
                    :events (apply on-checked type action-args)})
        (el :span {} (text label)))))

(defn- field
  [label control & [hint]]
  (el :label {:class "hc-ext-field"}
      (el :span {:class "hc-ext-field-label"} (text label))
      control
      (when hint (el :small {:class "wb-muted"} (text hint)))))

(defn- input
  [value event & [attrs]]
  (el :input (merge {:type "text"
                     :value (text value)
                     :autocomplete "off"
                     :class "hc-ext-input"}
                    attrs
                    {:events event})))

(defn- textarea
  [value event & [attrs]]
  (el :textarea (merge {:rows 6
                        :value (text value)
                        :class "hc-ext-textarea"}
                       attrs
                       {:events event})))

(defn- select
  [value options event]
  (el :select {:class "hc-ext-select"
               :value (name (or value ""))
               :events event}
      (for [[label value] options]
        (el :option {:value (if (keyword? value) (name value) (str value))
                     :key (if (keyword? value) (name value) (str value))}
                    (text label)))))

(defn- error-line [message]
  (when message
    (el :p {:class "wb-error" :role "alert"} (text message))))

(defn- empty-state [title body]
  (el :div {:class "wb-empty"}
      (el :strong {} (text title))
      (el :div {} (text body))))

(defn- toolbar [state]
  (el :div {:class "hc-ext-toolbar"}
      (el :div {:class "wb-row"}
          (badge (if (:ready? state) "扩展状态已同步" "扩展状态未同步")
                 (if (:ready? state) :ok :warn))
          (when (and (:ready? state) (integer? (:version state)))
            (badge (str "catalog v" (:version state)) :neutral))
          (badge (str "MCP 连接 " (count (:connections state))) :neutral))
      (button "读取最新状态" :extensions/refresh
              {:disabled? (model/pending? state)
               :title (when (model/pending? state)
                        "已有扩展命令正在提交")})))

(defn- server-target [server]
  (let [t (:transport server)]
    (case (:type t)
      (:http :sse) (text (:url t))
      :stdio (str/join " " (cons (:command t) (:args t)))
      "")))

(defn- tool-badges [tool]
  (el :span {:class "hc-ext-tool-badges"}
      (when (:read-only? tool) (badge "只读" :ok))
      (when (and (not (:read-only? tool)) (not (:destructive? tool)))
        (badge "可写" :warn))
      (when (:destructive? tool) (badge "破坏性" :danger))
      (when (:description-changed? tool) (badge "说明已变化" :warn))
      (when (:new? tool) (badge "新工具" :neutral))
      (when-not (:available? tool) (badge "不可用" :neutral))))

(defn- tool-row [state server tool]
  (let [server-id (:id server)
        tool-name (:name tool)
        available? (boolean (:available? tool))
        enabled? (boolean (:enabled? tool))
        review? (boolean (:review-required? tool))
        pending? (model/pending? state)]
    (el :div {:class "hc-ext-tool" :key (str server-id "/" tool-name)}
        (el :div {:class "hc-ext-tool-main"}
            (el :strong {} (text tool-name))
            (el :span {:class "wb-muted"} (text (:description tool)))
            (tool-badges tool))
        (el :div {:class "hc-ext-tool-actions"}
            (if (and available? (not enabled?) review?)
              (button "审阅并启用" :extensions/mcp-tool-enable
                      {:primary? true
                       :disabled? pending?
                       :title (when pending? "已有扩展命令正在提交")
                       :args [:id server-id :tool tool-name
                              :enabled? true :approve-review? true]})
              (button (if enabled? "停用" "启用")
                      :extensions/mcp-tool-enable
                      {:disabled? (or pending? (not available?))
                       :title (cond pending? "已有扩展命令正在提交"
                                    (not available?) "服务器当前未提供该工具")
                       :args [:id server-id :tool tool-name
                              :enabled? (not enabled?)]}))))))

(defn- server-card [state server]
  (let [id (:id server)
        state-label (get model/server-state-labels (:state server) "未连接")
        tone (get model/server-state-tones (:state server) :neutral)
        session (model/session-availability server)
        connected? (= :connected (get-in server [:connection :status]))
        pending? (model/pending? state)]
    (el :div {:class "hc-ext-card" :key id}
        (el :div {:class "hc-ext-card-head"}
            (el :div {}
                (el :strong {} (text (:name server)))
                (el :span {:class "wb-muted"} (str " · " (get model/transport-labels
                                                              (get-in server [:transport :type])
                                                              "未知连接")))
                (el :div {:class "wb-muted hc-ext-target"} (text (server-target server))))
            (el :div {:class "hc-ext-card-actions"}
                (badge state-label tone)
                (badge (text (:label session))
                       (case (:state session)
                         :available :ok
                         :unavailable :danger
                         :warn))
                (checkbox "启用" (:enabled? server) :extensions/mcp-enable
                          :id id :enabled? (not (:enabled? server))
                          {:disabled? pending?
                           :title (when pending? "已有扩展命令正在提交")})
                (button "测试/打开" :extensions/mcp-open
                        {:disabled? pending?
                         :title (when pending? "已有扩展命令正在提交")
                         :args [:id id]})
                (button "刷新工具" :extensions/mcp-refresh
                        {:disabled? (or pending? (not connected?))
                         :title (when-not connected? "先连接服务器")
                         :args [:id id]})
                (button "断开" :extensions/mcp-close
                        {:disabled? (or pending? (not connected?))
                         :title (when-not connected? "服务器未连接")
                         :args [:id id]})
                (button "删除" :extensions/mcp-remove
                        {:danger? true
                         :disabled? pending?
                         :title (when pending? "已有扩展命令正在提交")
                         :args [:id id]})))
        (when-let [error (get-in server [:connection :error])]
          (el :p {:class "wb-error" :role "alert"}
              (str "连接失败：" (or (:message error) (pr-str error)))))
        (when-let [scope (:scope session)]
          (el :p {:class "wb-muted"}
              (str "会话范围："
                   (or (:assistant-id scope) "未声明助手")
                   " / "
                   (or (:session-id scope) "未声明会话"))))
        (if (seq (:tools server))
          (el :div {:class "hc-ext-tools"}
              (for [tool (:tools server)]
                (tool-row state server tool)))
          (el :p {:class "wb-muted"} "服务器尚未返回工具清单。")))))

(defn- mcp-import-section [state]
  (let [text (get-in state [:draft :mcp-import] "")
        imported (:mcp-import state)
        preview (:preview imported)
        candidates (:candidates preview)
        failed (:failed imported)
        pending? (model/pending? state)
        ready? (:ready? state)]
    (el :div {:class "hc-ext-import"}
        (el :div {:class "hc-ext-section-title"}
            (el :h3 {} "导入 MCP 配置")
            (el :p {:class "wb-muted"}
                "先预览候选；导入的服务器默认未启用。未知字段只出现在报告里，不执行。"))
        (textarea text
                  (on-input :extensions/mcp-import-draft)
                  {:rows 5
                   :placeholder "{\"mcpServers\": {...}}"})
        (el :div {:class "wb-row"}
            (button "预览导入" :extensions/mcp-import-preview
                    {:disabled? (or pending? (not ready?)
                                    (not (model/non-blank-string? text)))
                     :title (cond
                              (not ready?) "等待扩展状态同步"
                              pending? "已有扩展命令正在提交"
                              (not (model/non-blank-string? text)) "先粘贴 MCP 配置"
                              :else nil)
                     :args []})
            (button "导入并保持停用" :extensions/mcp-import-apply
                    {:primary? true
                     :disabled? (or pending? (not ready?) (nil? preview))
                     :title (cond
                              (not ready?) "等待扩展状态同步"
                              pending? "已有扩展命令正在提交"
                              (nil? preview) "先预览导入"
                              :else nil)
                     :args []}))
        (when (seq candidates)
          (el :div {:class "hc-ext-import-list"}
              (for [candidate candidates]
                (el :div {:class "hc-ext-import-row"
                          :key (str (:name candidate))}
                    (el :strong {} (text (:name candidate)))
                    (badge (get model/transport-labels
                                (:transport-type candidate) "未知")
                           :neutral)
                    (el :span {:class "hc-ext-target"}
                        (text (:target candidate)))
                    (when (:has-secrets? candidate)
                      (badge "含凭据" :warn))
                    (when (seq (:unknown-fields candidate))
                      (el :small {:class "wb-muted"}
                          (str "未知字段："
                               (str/join ", " (map name (:unknown-fields candidate)))))))))
        (when (seq failed)
          (el :div {:class "hc-ext-import-failed" :role "alert"}
              (for [failure failed]
                (el :div {:key (str (:name failure))}
                    (str (or (:name failure) "候选")
                         "：" (or (get-in failure [:error :message])
                                  "无法导入"))))))))))

(defn- mcp-form [state]
  (let [draft (:draft state)
        m (:mcp draft)
        type (model/transport-type (:type m))
        pending? (model/pending? state)
        disabled? (not (:ready? state))]
    (el :section {:class "hc-ext-section"}
        (el :div {:class "hc-ext-section-title"}
            (el :h2 {} "MCP 服务器")
            (el :p {:class "wb-muted"}
                "新增配置 → 测试连通 → 启用工具 → 会话可用，是四个独立状态。测试成功不会自动启用任何工具，也不会自动把服务器加入当前会话。"))
        (el :div {:class "hc-ext-form"}
            (field "名称" (input (:name m)
                                 (on-input :extensions/mcp-draft :field :name)
                                 {:maxlength 80}))
            (field "连接方式"
                   (select type [["HTTP 流式" :http] ["SSE" :sse] ["本地进程" :stdio]]
                           (on-input :extensions/mcp-draft :field :type)))
            (if (= :stdio type)
              (el :div {:class "hc-ext-form-row"}
                  (field "程序" (input (:command m)
                                       (on-input :extensions/mcp-draft :field :command))
                         "程序与参数分开，不经过 shell。")
                  (field "参数" (textarea (:args m)
                                          (on-input :extensions/mcp-draft :field :args)
                                          {:rows 3})
                         "每行一个参数。")
                  (field "环境变量" (textarea (:env m)
                                              (on-input :extensions/mcp-draft :field :env)
                                              {:rows 3})
                         "每行 KEY=VALUE；敏感值会保存在本机扩展目录。"))
              (field "地址" (input (:url m)
                                   (on-input :extensions/mcp-draft :field :url)
                                   {:placeholder "https://example.test/mcp"})))
            (el :div {:class "wb-row"}
                (button "新增服务器" :extensions/mcp-add
                        {:primary? true
                         :disabled? (or pending? disabled?)
                         :title (cond
                                  disabled? "等待扩展状态同步"
                                  pending? "已有扩展命令正在提交"
                                  :else nil)
                         :args []})
                (when disabled?
                  (el :small {:class "wb-muted"} "连接建立后可用。"))))
        (mcp-import-section state)
        (if (seq (:mcp state))
          (el :div {:class "hc-ext-list"}
              (for [server (sort-by by-name (:mcp state))]
                (server-card state server)))
          (empty-state "尚无 MCP 服务器"
                       "新增配置后先测试连接，再启用工具和服务器。")))))

(defn- panel-error-label
  "Only skill-source errors need the source-specific explanation.  An MCP
  failure must keep its own message instead of borrowing skill wording."
  [error]
  (cond
    (nil? error) nil
    (not (map? error)) (str error)
    (str/starts-with? (or (some-> (:code error) name) "") "SKILL_")
    (model/skill-source-error-label error)
    :else (or (:message error) (str error))))

(defn- diff-row [row]
  (let [status (:status row)
        label (or (get model/diff-status-labels status)
                  (some-> status name)
                  "未知")]
    (el :div {:class "hc-ext-diff-row" :key (:path row)}
        (el :span {:class "hc-ext-diff-path"} (text (:path row)))
        (badge label
               (get model/diff-status-tones status :neutral)))))

(defn- skill-update-section [state skill]
  (let [id (:id skill)
        pending? (model/pending? state)
        ready? (:ready? state)
        text (get-in state [:draft :skill-updates id] "")
        source (get-in state [:draft :skill-update-sources id] {})
        kind (model/source-kind (:kind source))
        ready-source? (case kind
                        :url (model/non-blank-string? (:url source))
                        :archive (model/non-blank-string? (:archive-base64 source))
                        (model/non-blank-string? text))
        diff (get-in state [:skill-updates id :diff])
        applied? (get-in state [:skill-updates id :applied?])
        conflicts (count (:conflicts diff))
        paths (:paths diff)]
    (el :div {:class "hc-ext-update"}
        (el :div {:class "hc-ext-section-title"}
            (el :h3 {} "上游更新与三方对比")
            (el :p {:class "wb-muted"}
                "选择文本、链接或压缩包后先比较。冲突默认保留本地版；只有明确选择才采用上游。"))
        (field "上游来源"
               (select kind [["文本" :text] ["链接" :url] ["压缩包" :archive]]
                       (on-input :extensions/skill-update-source :id id)))
        (case kind
          :url
          (field "上游链接"
                 (input (:url source)
                        (on-input :extensions/skill-update-url :id id)
                        {:placeholder "https://example.test/SKILL.md 或 .zip"}))

          :archive
          (el :div {:class "hc-ext-file-picker"}
              (el :input {:type "file"
                          :accept ".zip,application/zip"
                          :aria-label "选择上游技能压缩包"
                          :events (on-file :extensions/skill-update-archive :id id)})
              (when (model/non-blank-string? (:archive-name source))
                (el :small {:class "wb-muted"}
                    (str "已选择：" (:archive-name source)))))

          (textarea text
                    (on-input :extensions/skill-update-draft :id id)
                    {:rows 4
                     :placeholder "上游 SKILL.md 原文"}))
        (el :div {:class "wb-row"}
            (button "比较更新" :extensions/skill-compare
                    {:disabled? (or pending? (not ready?)
                                    (not ready-source?))
                     :title (cond
                              (not ready?) "等待扩展状态同步"
                              (not ready-source?) "先选择或填写上游来源"
                              :else nil)
                     :args [:id id]})
            (button "应用并保留本地" :extensions/skill-apply-update
                    {:primary? true
                     :disabled? (or pending? (not ready?) (nil? diff))
                     :title (cond
                              (not ready?) "等待扩展状态同步"
                              pending? "已有扩展命令正在提交"
                              (nil? diff) "先比较更新"
                              :else nil)
                     :args [:id id :strategy :keep-local]})
            (button "采用上游" :extensions/skill-apply-update
                    {:danger? true
                     :disabled? (or pending? (not ready?) (nil? diff))
                     :title (cond
                              (not ready?) "等待扩展状态同步"
                              pending? "已有扩展命令正在提交"
                              (nil? diff) "先比较更新"
                              :else nil)
                     :args [:id id :strategy :take-upstream]})
            (when applied? (badge "已应用，待复核" :warn))
            (when (pos? conflicts)
              (badge (str "冲突 " conflicts) :danger)))
        (when (seq paths)
          (el :div {:class "hc-ext-diff"}
              (for [row (sort-by :path paths)]
                (diff-row row)))))))

(defn- skill-source-label
  [source]
  (let [kind (:kind source)]
    (case kind
      :text "文本"
      :url (str "链接 " (or (:uri source) (:url source) "未报告"))
      :archive (str "压缩包 " (or (:filename source) (:name source) "未命名"))
      :archive-base64 (str "压缩包 " (or (:filename source) "未命名"))
      :directory (str "目录 " (or (:path source) "未报告"))
      (if kind (name kind) "未报告"))))

(defn- skill-card [state skill]
  (let [id (:id skill)
        pending? (model/pending? state)
        bindings (count (:bindings skill))
        assistant-id (get-in state [:draft :assistant-id])
        assistant-target? (model/non-blank-string? assistant-id)
        bind-reason (cond
                      pending? "已有扩展命令正在提交"
                      (not assistant-target?) "先在上方“绑定目标助手”中选择助手"
                      :else nil)
        unbind-reason (cond
                        pending? "已有扩展命令正在提交"
                        (zero? bindings) "该技能尚未绑定助手"
                        (not assistant-target?) "先填写要解绑的助手 ID"
                        :else nil)]
    (el :div {:class "hc-ext-card" :key id}
        (el :div {:class "hc-ext-card-head"}
            (el :div {}
                (el :strong {} (text (:name skill)))
                (el :span {:class "wb-muted"}
                    (str " · " (or (:version skill) "未标版本")
                         " · " (if (pos? bindings)
                                 (str "绑定 " bindings " 个")
                                 "未绑定")))
                (el :div {:class "wb-muted"}
                    (str "来源：" (skill-source-label (:source skill))))
                (el :p {:class "wb-muted"} (text (:description skill))))
            (el :div {:class "hc-ext-card-actions"}
                (badge (if (:enabled? skill) "已启用" "未启用")
                       (if (:enabled? skill) :ok :neutral))
                (when (:local-modified? skill) (badge "本地修改" :warn))
                (when (:review-required? skill) (badge "待复核" :warn))
                (checkbox "启用" (:enabled? skill) :extensions/skill-enable
                          :id id :enabled? (not (:enabled? skill))
                          {:disabled? pending?
                           :title (when pending? "已有扩展命令正在提交")})
                (button "绑定助手" :extensions/skill-bind
                        {:disabled? (boolean bind-reason)
                         :title (or bind-reason
                                    (str "绑定到 " assistant-id))
                         :args [:id id]})
                (button "解除绑定" :extensions/skill-unbind
                        {:disabled? (boolean unbind-reason)
                         :title (or unbind-reason
                                    (str "从 " assistant-id " 解除绑定"))
                         :args [:id id]})
                (button "删除" :extensions/skill-delete
                        {:danger? true
                         :disabled? pending?
                         :args [:id id]})))
        (el :div {:class "hc-ext-files"}
            (let [files (:files skill)
                  entries (cond
                            (map? files) (sort-by key files)
                            (sequential? files)
                            (map (fn [file]
                                   [(or (:path file) (:name file) "文件") file])
                                 files)
                            :else [])]
              (for [[path meta] entries]
                (el :div {:class "hc-ext-file" :key (text path)}
                    (el :span {} (text path))
                    (el :small {:class "wb-muted"}
                        (str (when (some? (:size meta))
                               (str (:size meta) " B · "))
                             (if (:text? meta) "文本" "二进制")))))))
        (skill-update-section state skill))))

(defn- resolved-entry
  [state id]
  (some (fn [entry] (when (= id (:id entry)) entry))
        (concat (get-in state [:resolved-skills :active])
                (get-in state [:resolved-skills :blocked])
                (get-in state [:resolved-skills :excluded]))))

(defn- blocker-label
  [entry]
  (when (seq (:blockers entry))
    (str/join "；" (map (fn [blocker]
                          (or (:message blocker)
                              (some-> (:code blocker) name)))
                        (:blockers entry)))))

(defn- session-skill-row
  [state skill]
  (let [id (:id skill)
        scope (:session-scope state)
        custom? (= :custom (:mode scope))
        selected? (contains? (set (:skills scope)) id)
        resolved (resolved-entry state id)
        blocked? (some #(= id (:id %))
                       (get-in state [:resolved-skills :blocked]))
        excluded (some #(when (= id (:id %)) %)
                       (get-in state [:resolved-skills :excluded]))
        pending? (model/pending? state)
        reason (cond
                 pending? "已有扩展命令正在提交"
                 (not custom?) "先切换到自定义"
                 (= :globally-disabled (:reason excluded)) "技能已全局停用"
                 (not (:enabled? skill)) "技能未启用"
                 blocked? (or (blocker-label resolved) "技能条件未满足"))]
    (el :div {:class "hc-ext-card" :key id}
        (el :div {:class "hc-ext-card-head"}
            (el :div {}
                (el :strong {} (text (:name skill)))
                (el :span {:class "wb-muted"}
                    (str " · " (or (:version skill) "未标版本")))
                (el :p {:class "wb-muted"} (text (:description skill))))
            (el :div {:class "hc-ext-card-actions"}
                (checkbox "本会话可用"
                          selected?
                          :extensions/session-skill-toggle
                          :id id
                          :selected? (not selected?)
                          {:disabled? (boolean reason)
                           :title reason})))
        (when reason
          (el :p {:class "wb-muted"} (text reason))))))

(defn- session-skill-section
  [state]
  (let [scope (:session-scope state)
        mode (or (:mode scope) :follow)
        active (vec (get-in state [:resolved-skills :active]))
        blocked (vec (get-in state [:resolved-skills :blocked]))
        excluded (vec (get-in state [:resolved-skills :excluded]))
        pending? (model/pending? state)
        session-id (:selected-session-id state)]
    (el :section {:class "hc-ext-section"}
        (el :div {:class "hc-ext-section-title"}
            (el :h2 {} "会话技能")
            (el :p {:class "wb-muted"}
                "技能按会话生效：跟随助手使用助手绑定，自定义只使用本会话勾选的技能。全局停用始终优先。"))
        (if-not (model/non-blank-string? session-id)
          (empty-state "未选择会话"
                       "在工作台左侧选择一个会话后，这里会显示技能勾选。")
          (el :div {}
              (el :div {:class "wb-row"}
                  (button "跟随助手" :extensions/session-skills-follow
                          {:primary? (= :follow mode)
                           :disabled? pending?
                           :title (when pending? "已有扩展命令正在提交")})
                  (button "自定义" :extensions/session-skills-custom
                          {:primary? (= :custom mode)
                           :disabled? pending?
                           :title (when pending? "已有扩展命令正在提交")})
                  (badge (if (= :follow mode) "当前：跟随助手" "当前：自定义")
                         (if (= :follow mode) :neutral :ok)))
              (when (= :custom mode)
                (if (seq (:skills state))
                  (el :div {:class "hc-ext-list"}
                      (for [skill (sort-by by-name (:skills state))]
                        (session-skill-row state skill)))
                  (empty-state "没有可选的技能"
                               "安装并启用技能后，这里会出现可勾选项。")))
              (el :p {:class "wb-muted"}
                  (str "当前会话实际可用："
                       (if (seq active)
                         (str/join "、" (map #(or (:name %) (:id %)) active))
                         "无")))
              (when (seq blocked)
                (el :p {:class "wb-muted"}
                    (str "条件未满足：" (count blocked) " 个")))
              (when (seq excluded)
                (el :p {:class "wb-muted"}
                    (str "未生效：" (count excluded) " 个"))))))))

(defn- skill-form [state]
  (let [draft (:draft state)
        s (:skill draft)
        kind (model/source-kind (:source s))
        ready-source? (case kind
                        :url (model/non-blank-string? (:url s))
                        :archive (model/non-blank-string? (:archive-base64 s))
                        (model/non-blank-string? (:text s)))
        pending? (model/pending? state)
        disabled? (not (:ready? state))]
    (el :section {:class "hc-ext-section"}
        (el :div {:class "hc-ext-section-title"}
            (el :h2 {} "技能")
            (el :p {:class "wb-muted"}
                "安装后默认未启用、未绑定。技能文件是说明与配套资料，不会被执行；启用后仍需在助手或会话中选择。"))
        (el :div {:class "hc-ext-form"}
            (field "名称（可选）" (input (:name s)
                                         (on-input :extensions/skill-draft :field :name)
                                         {:maxlength 80})
                   "留空时从 SKILL.md 读取名称。")
            (field "来源"
                   (select kind [["文本" :text] ["链接" :url] ["压缩包" :archive]]
                           (on-input :extensions/skill-source :field :source)))
            (case kind
              :url
              (field "技能链接"
                     (input (:url s)
                            (on-input :extensions/skill-source :field :url)
                            {:placeholder "https://example.test/SKILL.md 或 .zip"}))

              :archive
              (el :div {:class "hc-ext-file-picker"}
                  (el :input {:type "file"
                              :accept ".zip,application/zip"
                              :aria-label "选择技能压缩包"
                              :events (on-file :extensions/skill-archive)})
                  (when (model/non-blank-string? (:archive-name s))
                    (el :small {:class "wb-muted"}
                        (str "已选择：" (:archive-name s)))))

              (field "SKILL.md"
                     (textarea (:text s)
                               (on-input :extensions/skill-draft :field :text)
                               {:rows 8
                                :placeholder "---\nname: weekly-report\ndescription: ...\n---\n# ..."})))
            (field "绑定目标助手"
                   (if (seq (:assistants state))
                     (select (get-in state [:draft :assistant-id])
                             (into [["选择助手…" ""]]
                                   (mapv (fn [assistant]
                                           [(or (:name assistant) (:id assistant))
                                            (:id assistant)])
                                         (:assistants state)))
                             (on-input :extensions/assistant-draft))
                     (input (get-in state [:draft :assistant-id])
                            (on-input :extensions/assistant-draft)
                            {:placeholder "assistant-id"}))
                   "这是技能卡片上“绑定/解除绑定”的目标；安装不会自动绑定。")
            (el :div {:class "wb-row"}
                (button "安装技能" :extensions/skill-install
                        {:primary? true
                         :disabled? (or pending? disabled? (not ready-source?))
                         :title (cond
                                  disabled? "等待扩展状态同步"
                                  pending? "已有扩展命令正在提交"
                                  (not ready-source?) "先填写技能来源"
                                  :else nil)
                         :args []})))
        (if (seq (:skills state))
          (el :div {:class "hc-ext-list"}
              (for [skill (sort-by by-name (:skills state))]
                (skill-card state skill)))
          (empty-state "尚无技能"
                       "从文本、链接或压缩包安装；安装后默认未启用。")))))

(defn panel-tree
  "Whole extensions panel.  Both capability families are shown together so a
  user can compare what is installed and what is active."
  [state]
  (el :div {:class "hc-ext-panel"}
      (toolbar state)
      (error-line (panel-error-label (:last-error state)))
      (mcp-form state)
      (session-skill-section state)
      (skill-form state)))
