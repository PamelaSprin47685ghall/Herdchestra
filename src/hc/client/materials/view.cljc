(ns hc.client.materials.view
  "Pure view tree for memory, worldbook and instruction-card management.

  The tree uses the same node shape as the settings view: a map with `:tag`,
  `:attrs`, `:events` and `:children`.  `hc.client.materials.react` renders
  it.  Every action in the tree is backed by a materials command or a local
  projection; disabled or unavailable capabilities are written out instead of
  offered as dead controls."
  (:require [clojure.string :as str]
            [hc.client.materials.model :as model]))

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

(defn- on-change [type & args]
  {:on-change (assoc (apply action type args) :value :target-value)})

(defn- on-checked [type & args]
  {:on-change (assoc (apply action type args) :value :target-checked)})

(defn- action-button
  [label type & args]
  (el :button {:type "button"
               :class "wb-button wb-button-small"
               :events (apply on-click type args)}
      label))

(defn- primary-button
  [label type & args]
  (el :button {:type "button"
               :class "wb-button wb-button-primary wb-button-small"
               :events (apply on-click type args)}
      label))

(defn- danger-button
  [label type & args]
  (el :button {:type "button"
               :class "wb-button wb-button-danger wb-button-small"
               :events (apply on-click type args)}
      label))

(defn- badge [label tone]
  (el :span {:class "hc-mat-badge" :data-tone (name (or tone :neutral))}
      label))

(defn- field
  [label control & [hint]]
  (el :label {:class "hc-mat-field"}
      (el :span {:class "hc-mat-field-label"} label)
      control
      (when hint (el :small {:class "wb-muted"} hint))))

(defn- text-input
  [value event & [attrs]]
  (el :input (merge {:type "text"
                     :default-value (text value)
                     :autocomplete "off"
                     :class "hc-mat-input"}
                    attrs
                    {:events event})))

(defn- textarea
  [value event & [attrs]]
  (el :textarea (merge {:rows 5
                        :default-value (text value)
                        :class "hc-mat-textarea"}
                       attrs
                       {:events event})))

(defn- draft-key
  [draft suffix]
  (str (or (:nonce draft) "draft") "-" (name suffix)))

(defn- assistant-label
  "Display name for an assistant id.  `assistant-options` already carries
  the server-published label (name plus the id suffix when needed); fall
  back to the bare id so an unknown assistant still shows something
  stable instead of disappearing."
  [assistant-id state]
  (let [option (some #(when (= assistant-id (:id %)) %)
                     (model/assistant-options state))]
    (if option
      (let [label (text (:label option))
            suffix (str "（" assistant-id "）")]
        ;; The option label already embeds the id; strip the suffix so
        ;; lists show "主助手" rather than "主助手（a-1）（a-1）".
        (if (and (> (count label) (count suffix))
                 (str/ends-with? label suffix))
          (subs label 0 (- (count label) (count suffix)))
          label))
      (text assistant-id))))

(defn- select
  ([value options event]
   (select value options event nil))
  ([value options event attrs]
   (apply el :select
          (merge {:class "hc-mat-select" :value (name (or value ""))}
                 attrs
                 {:events event})
          (mapv (fn [[option-label option-value]]
                  (el :option {:value (name option-value)
                               :key (name option-value)}
                      (text option-label)))
                options))))

(defn- checkbox
  ([label checked? event]
   (checkbox label checked? event nil))
  ([label checked? event disabled?]
   (el :label {:class "hc-mat-check"}
       (el :input {:type "checkbox"
                   :checked (boolean checked?)
                   :disabled (boolean disabled?)
                   :title (when disabled? "选择命令待确认，请稍候")
                   :class "hc-mat-checkbox"
                   :events event})
       (el :span {} label))))

(defn- error-line [message]
  (when message
    (el :p {:class "wb-error" :role "alert"} (text message))))

(defn- global-error [state]
  (when-let [error (:last-error state)]
    (el :div {:class "hc-mat-warning hc-mat-error" :role "alert"}
        (el :span {} (text (model/error-label error)))
        (el :button {:type "button"
                     :class "wb-button wb-button-small"
                     :events (on-click :materials/dismiss-error)}
            "关闭"))))

(defn- section-title [title description]
  (el :div {:class "hc-mat-section-title"}
      (el :h2 {} title)
      (el :p {:class "wb-muted"} description)))

(defn- empty-state [title body]
  (el :div {:class "wb-empty"}
      (el :strong {} title)
      (el :div {} body)))

(defn- status-tone [status]
  (case status
    :active :ok
    :candidate :warn
    :archived :neutral
    :rejected :danger
    :neutral))

(defn- memory-excerpt [body]
  (let [body (text body)]
    (if (> (count body) 160)
      (str (subs body 0 157) "…")
      body)))

(defn- memory-scope-label
  "Scope label for a memory row; resolves the assistant id to its display
  name when the model's `:assistants` projection knows one."
  [scope state]
  (if (= :assistant (get scope :kind))
    (str "助手 "
         (assistant-label (get scope :assistant-id) state))
    (model/scope-label scope)))

(defn- session-note [state]
  (let [session (:session state)
        temporary? (:temporary? session)]
    (el :p {:class "wb-muted hc-mat-note"}
        (cond
          (nil? session)
          "服务器尚未提供当前会话；记忆写入与注入范围按全局默认处理。"

          temporary?
          "当前是临时会话：服务器默认不读不写长期记忆。"

          :else
          (str "当前会话：" (or (:session-id session) "未知")
               "；助手范围 " (if (model/non-blank-string? (:assistant-id session))
                               (assistant-label (:assistant-id session) state)
                               "未声明")
               "；项目范围 " (or (:project-id session) "未声明") ".")))))

(defn- temporary-read-control [state]
  (when (model/temporary-session? state)
    (let [scope (model/temporary-read-scope state)]
      (el :div {:class "hc-mat-warning" :role "note"}
          (checkbox "仅本次读取已保存记忆"
                    (:temporary-read? state)
                    (on-checked :materials/temporary-read))
          (el :small {:class "wb-muted"}
              (str "范围：会话 " (or (:session-id scope) "未知")
                   "；助手 " (or (:assistant-id scope) "未声明")
                   "；项目 " (or (:project-id scope) "未声明")
                   "。发送成功后自动关闭；切换会话不保留。"))))))

(defn- memory-toolbar [state]
  (let [settings (:settings state)]
    (el :div {:class "hc-mat-toolbar"}
        (el :div {:class "hc-mat-switches"}
            (checkbox "读取已保存记忆"
                      (:memory-read? settings)
                      (on-checked :materials/memory-settings :field :memory-read?))
            (checkbox "自动建议新记忆"
                      (:memory-suggest? settings)
                      (on-checked :materials/memory-settings :field :memory-suggest?))
            (el :small {:class "wb-muted"}
                "两个开关独立；默认关闭。自动建议只进入待审区。"))
        (el :div {:class "wb-row"}
            (badge (if (:ready? state) "材料库已就绪" "材料库未就绪")
                   (if (:ready? state) :ok :warn))
            (when (integer? (:revision state))
              (badge (str "revision " (:revision state)) :neutral))
            (action-button "读取最新材料" :materials/refresh :reason :operator)
            (el :button {:type "button"
                         :class "wb-button wb-button-small"
                         :disabled (not (:memory-suggest? settings))
                         :title (when-not (:memory-suggest? settings)
                                  "先开启“自动建议新记忆”")
                         :events (on-click :materials/memory-suggest)}
                        "整理记忆")
            (when-let [error (:last-error state)]
              (el :small {:class "hc-mat-error" :role "alert"}
                  (model/error-label error)))))))

(defn- memory-stats [state]
  (let [counts (:memory-counts state)]
    (el :div {:class "hc-mat-stats"}
        (for [status [:candidate :active :archived :rejected]]
          (el :div {:class "hc-mat-stat" :key (name status)}
              (el :strong {} (str (get counts status 0)))
              (el :span {} (model/status-label status)))))))

(defn- memory-filters [state]
  (el :div {:class "hc-mat-filters"}
      (for [status [:all :candidate :active :archived :rejected]]
        (el :button {:type "button"
                     :class "wb-button wb-button-small"
                     :aria-pressed (if (= status (:memory-filter state))
                                     "true" "false")
                     :data-active (if (= status (:memory-filter state))
                                    "true" "false")
                     :key (name status)
                     :events (on-click :materials/memory-filter :filter status)}
            (if (= status :all) "全部" (model/status-label status))))))

(defn- skip-reason-label [reason]
  (case reason
    :empty "空候选"
    :category-filtered "类别不匹配"
    (name (or reason :unknown))))

(defn- memory-suggest-summary [state]
  (when-let [result (:memory-suggest-result state)]
    (el :div {:class "hc-mat-note" :role "status"}
        (el :strong {} (str "本次整理：候选 " (count (:candidates result))
                            " · 跳过 " (or (:skipped result) 0)))
        (when (seq (:skipped-reasons result))
          (el :small {}
              (str "跳过原因："
                   (str/join "、" (map (fn [[k v]]
                                         (str (skip-reason-label k) " " v))
                                       (:skipped-reasons result))))))
        (when (pos? (long (or (:dropped-message-count result) 0)))
          (el :small {} (str " · 超出字符预算丢弃 " (:dropped-message-count result) " 条"))))))

(defn- memory-list [state]
  (let [items (model/memory-items state)]
    (el :div {:class "hc-mat-pane"}
        (memory-filters state)
        (el :div {:class "hc-mat-search"}
            (text-input (:memory-query state)
                        (on-input :materials/memory-query :query nil)
                        {:key "memory-query"
                         :placeholder "本地筛选：正文、类别、范围或来源"}))
        (if (seq items)
          (el :ul {:class "wb-list hc-mat-list"}
              (for [memory items]
                (el :li {:key (:id memory)
                         :class (str "hc-memory-row hc-memory-"
                                     (name (:status memory)))
                         :data-memory-id (:id memory)}
                    (el :button {:type "button"
                                 :class "wb-list-button"
                                 :aria-current (if (= (:id memory)
                                                     (:selected-memory-id state))
                                                 "true" "false")
                                 :events (on-click :materials/memory-select
                                                   :memory-id (:id memory))}
                        (el :div {:class "wb-list-main"}
                            (el :div {:class "hc-mat-row"}
                                (el :strong {} (model/category-label
                                                (:category memory)))
                                (badge (model/status-label (:status memory))
                                       (status-tone (:status memory)))
                                (when (:sensitive? memory)
                                  (badge "含敏感内容" :danger)))
                            (el :small {:class "hc-memory-body"}
                                (memory-excerpt (:body memory)))
                            (when (:assumption? memory)
                              (badge "假设，待确认" :warn))
                            (when (:truncated? memory)
                              (badge "已截断至 2000 字" :warn))
                            (when-let [excerpt (get-in memory [:source :excerpt])]
                              (el :small {:class "hc-mat-source hc-memory-source"}
                                  (str "来源片段：" (memory-excerpt excerpt))))
                            (el :small {:class "wb-muted hc-memory-meta"}
                                (str "范围 " (memory-scope-label (:scope memory) state)
                                     " · "
                                     (model/epistemic-label (:epistemic memory))
                                     (when (:always? memory) " · 总是提供")
                                     " · 更新序号 "
                                     (or (:updated-seq memory) "未知"))))))))
          (empty-state "没有符合条件的记忆"
                       "待审、生效、归档与已拒绝记录会按服务器快照显示。")))))

(defn- memory-editor [state]
  (let [draft (:memory-draft state)
        selected (model/selected-memory state)
        mode (:mode draft)
        edit? (= :edit mode)
        status (:status selected)]
    (el :div {:class "hc-mat-pane hc-mat-editor"}
        (el :div {:class "hc-mat-editor-head"}
            (el :h3 {} (if edit? "编辑记忆" "新建记忆"))
            (action-button "新建" :materials/memory-new))
        (when selected
          (el :div {:class "hc-mat-source"}
              (el :div {:class "hc-mat-row"}
                  (badge (model/status-label status) (status-tone status))
                  (badge (model/category-label (:category selected)) :neutral)
                  (when (seq (:versions selected))
                    (badge (str (count (:versions selected)) " 个旧版本")
                           :neutral)))
              (when-let [source (:source selected)]
                (el :div {}
                    (el :strong {} "来源对话片段")
                    (el :blockquote {:class "hc-mat-quote"}
                        (text (:excerpt source)))
                    (el :small {:class "wb-muted"}
                        (str "会话 " (or (:session-id source) "未知")
                             " · 消息 "
                             (str/join ", " (:message-ids source))))))))
        (field "正文（最多 2000 字）"
               (textarea (:body draft)
                         (on-input :materials/memory-edit-field :field :body)
                         {:key (draft-key draft :body)
                          :rows 8
                          :placeholder "只写用户明确保存的事实、偏好、要求或假设。"})
               (str (model/unicode-length (:body draft)) " / "
                    model/memory-max-chars " 字"))
        (field "类别"
               (select (:category draft)
                       (for [category model/memory-categories]
                         [(model/category-label category) category])
                       (on-change :materials/memory-edit-field
                                  :field :category)))
        (field "范围"
               (select (:scope-kind draft)
                       (for [kind [:global :assistant :project]]
                         [(model/scope-kind-label kind) kind])
                       (on-change :materials/memory-edit-field
                                  :field :scope-kind)))
        (case (:scope-kind draft)
          :assistant
          (field "助手 ID"
                 (text-input (:assistant-id draft)
                             (on-input :materials/memory-edit-field
                                       :field :assistant-id)
                             {:key (draft-key draft :assistant-id)})
                 "只对指定助手生效。")
          :project
          (field "项目 ID"
                 (text-input (:project-id draft)
                             (on-input :materials/memory-edit-field
                                       :field :project-id)
                             {:key (draft-key draft :project-id)})
                 "只对指定项目生效。")
          nil)
        (field "认识标签"
               (select (:epistemic draft)
                       (for [epistemic [:fact :preference :requirement
                                        :assumption]]
                         [(model/epistemic-label epistemic) epistemic])
                       (on-change :materials/memory-edit-field
                                  :field :epistemic))
               "假设不会自动升级为事实。")
        (checkbox "总是提供（仍受注入预算限制）"
                  (:always? draft)
                  (on-checked :materials/memory-edit-field :field :always?))
        (when (= :candidate status)
          (el :p {:class "wb-muted hc-memory-candidate-hint"}
              "改范围：在上方“范围”选择新的助手或项目，再点“批准候选”，批准命令会同时保存新范围。"))
        (when (contains? #{:candidate :rejected} status)
          (field "拒绝原因（可选）"
                 (text-input (:reject-reason draft)
                             (on-input :materials/memory-edit-field
                                       :field :reject-reason)
                             {:key (draft-key draft :reject-reason)})
                 "随拒绝命令发送；不会写入记忆正文。"))
        (error-line (:error draft))
        (el :div {:class "hc-mat-actions hc-mat-save-row"}
            (if edit?
              (case status
                (:candidate :rejected)
                (primary-button "批准候选（按当前范围）"
                                :materials/memory-approve)

                :active
                (primary-button "保存替换" :materials/memory-save)

                :archived
                (primary-button "恢复" :materials/memory-restore)

                (primary-button "保存替换" :materials/memory-save))
              (primary-button "保存记忆" :materials/memory-save)))
        (when (and edit? (contains? #{:candidate :rejected} status))
          (el :div {:class "hc-mat-actions hc-mat-danger-row"
                    :data-danger "true"}
              (danger-button "拒绝" :materials/memory-reject)))
        (when (and edit? (= :active status))
          (el :div {:class "hc-mat-actions hc-mat-danger-row"
                    :data-danger "true"}
              (action-button "归档" :materials/memory-archive)))
        (when (and edit? (= :archived status))
          (el :div {:class "hc-mat-actions hc-mat-danger-row"
                    :data-danger "true"}
              (if (:confirm-delete? draft)
                (danger-button "确认永久删除"
                               :materials/memory-delete)
                (danger-button "永久删除"
                               :materials/memory-request-delete))
              (when (:confirm-delete? draft)
                (action-button "取消"
                               :materials/memory-cancel-delete))))
        (el :p {:class "wb-muted hc-mat-note"}
            "修改生效记忆会保留旧版本；归档立即退出未来注入。临时会话不写入。"))))

(defn memory-tree [state]
  (el :div {:class "hc-mat-section"}
      (section-title "长期记忆"
                     "四类长期记忆、候选审阅、范围与归档。默认关闭；自动整理只生成待审候选。")
      (session-note state)
      (temporary-read-control state)
      (memory-toolbar state)
      (memory-stats state)
      (memory-suggest-summary state)
      (el :div {:class "hc-mat-split"}
          (memory-list state)
          (memory-editor state))))

(defn- book-list [state]
  (let [books (model/book-items state)]
    (el :div {:class "hc-mat-pane"}
        (if (seq books)
          (el :ul {:class "wb-list hc-mat-list"}
              (for [book books]
                (el :li {:key (:id book)}
                    (el :button {:type "button"
                                 :class "wb-list-button"
                                 :data-book-id (:id book)
                                 :aria-current (if (= (:id book)
                                                     (:selected-book-id state))
                                                 "true" "false")
                                 :events (on-click :materials/book-select
                                                   :book-id (:id book))}
                        (el :div {:class "wb-list-main"}
                            (el :div {:class "hc-mat-row"}
                                (el :strong {} (text (:name book)))
                                (badge (if (:enabled? book) "启用" "停用")
                                       (if (:enabled? book) :ok :warn))
                                (badge (str (:entry-count book) " 条") :neutral))
                            (el :small {}
                                (str (or (:description book) "无说明")
                                     " · 绑定 "
                                     (if (seq (:assistant-ids book))
                                       (str/join ", "
                                                 (map #(assistant-label % state)
                                                      (sort (:assistant-ids book))))
                                       "无"))))))))
          (empty-state "没有世界书"
                       "创建一本书后再添加条目。未绑定的世界书不会进入任何助手。")))))

(defn- book-editor [state]
  (let [book (model/selected-book state)
        draft (:book-draft state)
        edit? (= :edit (:mode draft))
        assistant-options (model/assistant-options state)
        assistant-id (model/selected-book-assistant-id state)
        ;; The bind select acts on a selectable assistant; unbind falls back
        ;; to an already-bound id so a book can still be detached when the
        ;; assistant list is empty.
        bind-assistant-id (if (seq assistant-options)
                            assistant-id
                            (->> (:assistant-ids book)
                                 (filter model/non-blank-string?)
                                 sort
                                 first))
        bound? (boolean (seq (:assistant-ids book)))]
    (el :div {:class "hc-mat-pane hc-mat-editor"}
        (el :div {:class "hc-mat-editor-head"}
            (el :h3 {} (if (and edit? book)
                         (str "编辑：" (text (:name book)))
                         "新建世界书"))
            (action-button "新建" :materials/book-new))
        (field "名称"
               (text-input (:name draft)
                           (on-input :materials/book-edit-field :field :name)
                           {:key (draft-key draft :name)}))
        (field "说明"
               (textarea (:description draft)
                         (on-input :materials/book-edit-field :field :description)
                         {:key (draft-key draft :description)
                          :rows 3}))
        (checkbox "启用"
                  (:enabled? draft)
                  (on-checked :materials/book-edit-field :field :enabled?))
        (error-line (:error draft))
        (when edit?
          (el :div {:class "wb-row hc-mat-bind"}
              (select assistant-id
                      (if (seq assistant-options)
                        (for [option assistant-options]
                          [(:label option) (:id option)])
                        [["先创建/选择助手" ""]])
                      (on-change :materials/book-edit-field
                                 :field :assistant-id)
                      {:aria-label "绑定助手"
                       :disabled (not (seq assistant-options))
                       :title (when-not (seq assistant-options)
                                "当前没有可选择的助手；先创建助手，或在会话中选择助手。")})
              (if (seq assistant-options)
                (action-button "绑定" :materials/book-bind
                               :assistant-id assistant-id)
                (el :button {:type "button"
                             :class "wb-button wb-button-small"
                             :disabled true}
                    "绑定"))
              (if bound?
                (action-button "解绑" :materials/book-unbind
                               :assistant-id (or bind-assistant-id assistant-id))
                (el :button {:type "button"
                             :class "wb-button wb-button-small"
                             :disabled true}
                    "解绑"))
              (el :small {:class "wb-muted"}
                  (str "已绑定："
                       (if bound?
                         (str/join ", "
                                   (map #(assistant-label % state)
                                        (sort (:assistant-ids book))))
                         "无")
                       "。绑定/解绑是独立操作。"))))
          (when (and edit? (empty? assistant-options))
            (el :p {:class "wb-muted hc-mat-note"}
                "当前没有可绑定的助手：先创建助手，或在会话中选择助手后再绑定世界书。"))
        (el :div {:class "hc-mat-actions hc-mat-save-row"}
            (primary-button (if edit? "保存世界书" "创建世界书")
                            :materials/book-save))
        (when edit?
          (el :div {:class "hc-mat-actions hc-mat-danger-row"
                    :data-danger "true"}
              (if (:confirm-delete? draft)
                (danger-button "确认删除世界书"
                               :materials/book-delete)
                (danger-button "删除世界书"
                               :materials/book-request-delete))
              (when (:confirm-delete? draft)
                (action-button "取消" :materials/book-cancel-delete))))
        (when edit?
          (el :p {:class "wb-muted hc-mat-note"}
              "已绑定的世界书默认拒绝删除；先解绑所有助手。"))
        (when edit?
          (el :div {:class "hc-mat-runtime"}
              (el :h4 {} "当前会话计数")
              (el :p {:class "wb-muted"}
                  (str "有效用户轮次："
                       (or (get-in state [:worldbook-session :turn]) "未知")
                       "。工具往返、流式片段与发送失败不消耗计数。"))
              (let [rows (model/worldbook-runtime-rows state)]
                (if (seq rows)
                  (el :ul {:class "hc-mat-runtime-list"}
                      (for [row rows]
                        (el :li {:key (:entry-id row)}
                            (el :strong {} (:title row))
                            (el :span {:class "wb-muted"}
                                (str " · 最近命中轮次 "
                                     (or (:last-trigger-turn row) "—")
                                     " · 持续至 "
                                     (or (:sticky-until row) "—")
                                     " · 冷却至 "
                                     (or (:cooldown-until row) "—"))))))
                  (el :p {:class "wb-muted"}
                      "服务器尚未提供本会话的世界书运行计数。")))
              (let [{:keys [reported? rows]} (model/worldbook-omitted-rows state)]
                (el :div {:class "hc-mat-runtime-sub"}
                    (el :h4 {} "命中但未提供")
                    (if reported?
                      (if (seq rows)
                        (el :ul {:class "hc-mat-runtime-list"}
                            (for [row rows]
                              (el :li {:key (str "omitted-" (:entry-id row))}
                                  (el :strong {} (:title row))
                                  (el :span {:class "wb-muted"}
                                      (str " · " (:reason-label row)
                                           (when (:chars row)
                                             (str " · " (:chars row) " 字")))))))
                        (el :p {:class "wb-muted"}
                            "本轮没有命中但未提供的条目。"))
                      (el :p {:class "wb-muted"}
                          "服务器未报告本轮命中但未提供列表。")))))))))

(defn- entry-list [state]
  (let [book (model/selected-book state)
        entries (if book (model/entry-items state (:id book)) [])]
    (el :div {:class "hc-mat-pane"}
        (if (seq entries)
          (el :ul {:class "wb-list hc-mat-list"}
              (for [entry entries]
                (el :li {:key (:id entry)}
                    (el :button {:type "button"
                                 :class "wb-list-button"
                                 :data-entry-id (:id entry)
                                 :aria-current (if (= (:id entry)
                                                     (get-in state [:entry-draft :entry-id]))
                                                 "true" "false")
                                 :events (on-click :materials/entry-select
                                                   :entry-id (:id entry))}
                        (el :div {:class "wb-list-main"}
                            (el :div {:class "hc-mat-row"}
                                (el :strong {} (text (:title entry)))
                                (badge (if (:enabled? entry) "启用" "停用")
                                       (if (:enabled? entry) :ok :warn))
                                (when (:key? entry) (badge "关键条目" :neutral)))
                            (el :small {} (model/trigger-summary entry))
                            (el :small {}
                                (memory-excerpt (:body entry)))
                            (el :small {:class "wb-muted"}
                                (str "持续 " (or (:sticky entry) 0)
                                     " · 冷却 " (or (:cooldown entry) 0)
                                     " · 延迟 " (or (:delay entry) 0)
                                     " · 顺序 " (or (:order entry) "—"))))))))
          (empty-state "没有条目"
                       "条目必须属于一本书；启用且绑定到助手的书才会参与检索。")))))

(defn- entry-editor [state]
  (let [draft (:entry-draft state)
        edit? (= :edit (:mode draft))
        books (model/book-items state)
        move-books (if edit?
                     (vec (remove #(= (:id %) (:book-id draft)) books))
                     books)]
    (el :div {:class "hc-mat-pane hc-mat-editor"}
        (el :div {:class "hc-mat-editor-head"}
            (el :h3 {} (if edit? "编辑条目" "新建条目"))
            (action-button "新建" :materials/entry-new))
        (field "标题"
               (text-input (:title draft)
                           (on-input :materials/entry-edit-field :field :title)
                           {:key (draft-key draft :title)}))
        (field "正文"
               (textarea (:body draft)
                         (on-input :materials/entry-edit-field :field :body)
                         {:key (draft-key draft :body)
                          :rows 6})
               "服务器不会截断条目末尾的否定词或例外条件。")
        (field "触发方式"
               (select (:trigger-kind draft)
                       (for [kind [:keyword :constant :expression]]
                         [(model/trigger-kind-label kind) kind])
                       (on-change :materials/entry-edit-field
                                  :field :trigger-kind)))
        (case (:trigger-kind draft)
          :keyword
          (el :div {}
              (field "关键词（每行一个，可含空格）"
                     (textarea (:keywords draft)
                               (on-input :materials/entry-edit-field
                                         :field :keywords)
                               {:key (draft-key draft :keywords)
                                :rows 3}))
              (field "命中规则"
                     (select (:match draft)
                             (for [match [:any :all]]
                               [(model/match-kind-label match) match])
                             (on-change :materials/entry-edit-field
                                        :field :match))))
          :expression
          (field "受控表达式（EDN）"
                 (textarea (:expression draft)
                           (on-input :materials/entry-edit-field
                                     :field :expression)
                           {:key (draft-key draft :expression)
                            :rows 3})
                 "支持 contains / starts-with / ends-with / not / all / any。")
          nil)
        (el :div {:class "hc-mat-grid-3"}
            (field "持续次数"
                   (text-input (:sticky draft)
                               (on-input :materials/entry-edit-field
                                         :field :sticky)
                               {:key (draft-key draft :sticky)}))
            (field "冷却次数"
                   (text-input (:cooldown draft)
                               (on-input :materials/entry-edit-field
                                         :field :cooldown)
                               {:key (draft-key draft :cooldown)}))
            (field "延迟次数"
                   (text-input (:delay draft)
                               (on-input :materials/entry-edit-field
                                         :field :delay)
                               {:key (draft-key draft :delay)})))
        (el :div {:class "hc-mat-grid-3"}
            (field "顺序"
                   (text-input (:order draft)
                               (on-input :materials/entry-edit-field
                                         :field :order)
                               {:key (draft-key draft :order)}))
            (field "位置"
                   (select (:position-kind draft)
                           (for [kind [:base-background :before-current
                                       :between-recent]]
                             [(model/position-kind-label kind) kind])
                           (on-change :materials/entry-edit-field
                                      :field :position-kind)))
            (when (= :between-recent (:position-kind draft))
              (field "位置深度"
                     (text-input (:position-depth draft)
                                 (on-input :materials/entry-edit-field
                                           :field :position-depth)
                                 {:key (draft-key draft :position-depth)}))))
        (checkbox "关键条目（上下文不足时优先）"
                  (:key? draft)
                  (on-checked :materials/entry-edit-field :field :key?))
        (checkbox "启用条目"
                  (:enabled? draft)
                  (on-checked :materials/entry-edit-field :field :enabled?))
        (error-line (:error draft))
        (el :div {:class "hc-mat-actions hc-mat-save-row"}
            (primary-button (if edit? "保存条目" "创建条目")
                            :materials/entry-save))
        (when edit?
          (el :div {:class "hc-mat-actions"}
              (field "移动到"
                     (select (:move-book-id draft)
                             (cons ["选择目标世界书" ""]
                                   (for [book move-books]
                                     [(text (:name book)) (:id book)]))
                             (on-change :materials/entry-edit-field
                                        :field :move-book-id))
                     "移动是独立操作，计数器随条目移动；当前书不在列表中。")
              (action-button "移动" :materials/entry-move)
              (when (:last-move state)
                (action-button "撤销移动" :materials/entry-undo-move))))
        (when edit?
          (el :div {:class "hc-mat-actions hc-mat-danger-row"
                    :data-danger "true"}
              (if (:confirm-delete? draft)
                (danger-button "确认删除条目"
                               :materials/entry-delete)
                (danger-button "删除条目"
                               :materials/entry-request-delete))
              (when (:confirm-delete? draft)
                (action-button "取消" :materials/entry-cancel-delete)))))))

(defn worldbook-tree [state]
  (el :div {:class "hc-mat-section"}
      (section-title "世界书"
                     "关键词、始终提供与受控表达式触发；持续、冷却与延迟按有效用户提交轮次计数。")
      (session-note state)
      (if (seq (:books state))
        ;; Two rows keep each editor wide enough for its form.  The previous
        ;; three-column split gave the book editor and entry editor only a
        ;; fraction of the already narrow main column when the details rail
        ;; was open.
        (el :div {:class "hc-mat-stack"}
            (el :div {:class "hc-mat-split"}
                (book-list state)
                (book-editor state))
            (el :div {:class "hc-mat-split"}
                (entry-list state)
                (entry-editor state)))
        (el :div {}
            (book-list state)
            (book-editor state)))))

(defn- card-list [state]
  (let [cards (model/card-items state)
        session-selected (set (model/session-card-ids state))
        turn-selected (set (model/turn-card-ids state))]
    (el :div {:class "hc-mat-pane"}
        (if (seq cards)
          (el :ul {:class "wb-list hc-mat-list"}
              (for [card cards]
                (el :li {:key (:id card)}
                    (el :button {:type "button"
                                 :class "wb-list-button"
                                 :data-card-id (:id card)
                                 :aria-current (if (= (:id card)
                                                     (:selected-card-id state))
                                                 "true" "false")
                                 :events (on-click :materials/card-select
                                                   :card-id (:id card))}
                        (el :div {:class "wb-list-main"}
                            (el :div {:class "hc-mat-row"}
                                (el :strong {} (text (:name card)))
                                (badge (if (:enabled? card) "启用" "停用")
                                       (if (:enabled? card) :ok :warn))
                                (when (contains? session-selected (:id card))
                                  (badge "本会话已选" :ok))
                                (when (contains? turn-selected (:id card))
                                  (badge "本轮已选" :warn))
                                (when-let [format (:output-format card)]
                                  (badge (model/format-label format) :neutral)))
                            (el :small {}
                                (or (:description card)
                                    (memory-excerpt (:body card)))))))))
          (empty-state "没有指令卡"
                       "指令卡是显式选择的库对象；它不会自行获得工具权限。")))))

(defn- mode-button [mode current label description & [disabled?]]
  (el :button {:type "button"
               :class "wb-button wb-button-small"
               :disabled (boolean disabled?)
               :title (when disabled? "选择命令待确认，请稍候")
               :data-active (if (= mode current) "true" "false")
               :aria-pressed (if (= mode current) "true" "false")
               :events (on-click :materials/instruction-mode :mode mode)}
      label
      (el :small {:class "wb-muted"} (str " · " description))))

(defn- card-checklist [state ids toggle-type & [disabled?]]
  (let [cards (filter :enabled? (model/card-items state))
        selected (set ids)]
    (if (seq cards)
      (el :ul {:class "hc-mat-checklist"}
          (for [card cards]
            (el :li {:key (:id card)}
                (checkbox (str (:name card)
                               (when-let [format (:output-format card)]
                                 (str " · " (model/format-label format)))
                               (when (seq (model/required-placeholder-keys card))
                                 " · 含必填占位符"))
                          (contains? selected (:id card))
                          (on-checked toggle-type :card-id (:id card))
                          disabled?))))
      (el :p {:class "wb-muted"} "没有启用的指令卡。"))))

(defn- placeholder-inputs [state pending?]
  (let [cards (model/effective-cards state)
        rows (for [card cards
                   placeholder (:placeholders card)
                   :let [key (:key placeholder)
                         value (get (:instruction-values state) key)]
                   :when key]
               (field (str (:name card) " · "
                           (or (:label placeholder) key)
                           (when (:required? placeholder) "（必填）"))
                      (text-input value
                                  (on-input :materials/instruction-value
                                            :key key)
                                  (cond-> {:key (str (:id card) "-" key)}
                                    pending? (assoc :disabled true)))
                      (when (:required? placeholder)
                        "缺值时服务器拒绝本轮；值只随本轮提交。")))]
    (when (seq rows)
      (el :div {:class "hc-mat-values"}
          (el :h4 {} "占位符值")
          rows))))

(defn- turn-selection-panel [state pending?]
  (el :div {:class "hc-mat-turn-selection"}
      (el :p {:class "wb-muted"}
          "仅本轮：随下一条用户消息一次性发送；发送成功后清除。占位符值只随本轮提交，不写入会话选择。")
      (el :div {:class "hc-mat-actions"}
          (let [button (action-button "清空本轮" :materials/turn-card-clear)]
            (if pending?
              (assoc-in button [:attrs :disabled] true)
              button)))
      (card-checklist state (model/turn-card-ids state)
                      :materials/turn-card-toggle pending?)
      (placeholder-inputs state pending?)))

(defn- session-selection-panel [state pending?]
  (el :div {:class "hc-mat-session-selection"}
      (el :p {:class "wb-muted"}
          "本会话持续应用：选择会持久化到当前会话；不携带占位符值。")
      (el :div {:class "hc-mat-actions"}
          (let [button (action-button "清空选择" :materials/card-clear-selection)]
            (if pending?
              (assoc-in button [:attrs :disabled] true)
              button)))
      (card-checklist state (model/session-card-ids state)
                      :materials/card-toggle-selection pending?)))

(defn- card-selection [state]
  (let [mode (or (:instruction-mode state) :turn)
        turn-ids (model/turn-card-ids state)
        session-ids (model/session-card-ids state)
        turn-count (count turn-ids)
        session-count (count session-ids)
        other-label (if (= :session mode) "仅本轮" "本会话持续")
        other-count (if (= :session mode) turn-count session-count)
        conflict (model/selection-format-conflict state)
        missing (model/missing-required-placeholders state)
        pending? (model/instruction-selection-pending? state)]
    (el :div {:class "hc-mat-pane hc-mat-selection"}
        (el :div {:class "hc-mat-editor-head"}
            (el :h3 {} "指令卡应用方式"))
        (el :div {:class "hc-mat-mode" :role "group"
                  :aria-label "指令卡应用方式"}
            (mode-button :turn mode (str "仅本轮（" turn-count "）") "默认" pending?)
            (mode-button :session mode (str "本会话持续（" session-count "）")
                         "持久选择" pending?))
        (el :p {:class "wb-muted hc-mat-note hc-mat-mode-hint"}
            (str "另一集合：" other-label " " other-count " 条。"))
        (if (= :session mode)
          (session-selection-panel state pending?)
          (turn-selection-panel state pending?))
        (when conflict
          (el :div {:class "hc-mat-warning" :role "alert"}
              (str "格式冲突：已选卡片声明了不同输出格式（"
                   (str/join ", " (map model/format-label conflict))
                   "）。发送前必须只保留一种格式。")))
        (when (seq missing)
          (el :div {:class "hc-mat-warning" :role "alert"}
              (str "必填占位符尚未提供值："
                   (str/join "；"
                             (map (fn [{:keys [card-name key]}]
                                    (str card-name " / " key))
                                  missing))
                   (if (= :session mode)
                     "。本会话持续模式不携带占位符值；请切换到仅本轮并填写。"
                     "。服务器在上下文组装时会拒绝本轮。")))))))

(defn- card-editor [state pending?]
  (let [draft (:card-draft state)
        edit? (= :edit (:mode draft))]
    (el :div {:class "hc-mat-pane hc-mat-editor"}
        (el :div {:class "hc-mat-editor-head"}
            (el :h3 {} (if edit? "编辑指令卡" "新建指令卡"))
            (let [button (action-button "新建" :materials/card-new)]
              (if pending?
                (assoc-in button [:attrs :disabled] true)
                button)))
        (field "名称"
               (text-input (:name draft)
                           (on-input :materials/card-edit-field :field :name)
                           {:key (draft-key draft :name)}))
        (field "说明"
               (textarea (:description draft)
                         (on-input :materials/card-edit-field :field :description)
                         {:key (draft-key draft :description)
                          :rows 2}))
        (field "正文"
               (textarea (:body draft)
                         (on-input :materials/card-edit-field :field :body)
                         {:key (draft-key draft :body)
                          :rows 8})
               (str (model/unicode-length (:body draft)) " / "
                    model/card-body-max-chars " 字"))
        (field "占位符（每行 key | 标签 | required）"
               (textarea (:placeholders-text draft)
                         (on-input :materials/card-edit-field
                                   :field :placeholders-text)
                         {:key (draft-key draft :placeholders-text)
                          :rows 3})
               "正文使用 {{key}}；必填占位符缺值时服务器拒绝应用。")
        (field "输出格式"
               (select (:output-format draft)
                       (model/instruction-output-options)
                       (on-change :materials/card-edit-field
                                  :field :output-format))
               "留空表示未声明；仅允许文本 / Markdown / JSON。")
        (checkbox "启用"
                  (:enabled? draft)
                  (on-checked :materials/card-edit-field :field :enabled?)
                  pending?)
        (error-line (:error draft))
        (el :div {:class "hc-mat-actions hc-mat-save-row"}
            (primary-button (if edit? "保存指令卡" "创建指令卡")
                            :materials/card-save))
        (when edit?
          (el :div {:class "hc-mat-actions hc-mat-danger-row"
                    :data-danger "true"}
              (if (:confirm-delete? draft)
                (danger-button "确认删除指令卡"
                               :materials/card-delete)
                (danger-button "删除指令卡"
                               :materials/card-request-delete))
              (when (:confirm-delete? draft)
                (action-button "取消" :materials/card-cancel-delete)))))))

(defn cards-tree [state]
  (el :div {:class "hc-mat-section"}
      (section-title "指令卡"
                     "显式选择的复用指令。卡片不会改变工具权限，也不会进入用户草稿。")
      (session-note state)
      (el :div {:class "hc-mat-split"}
          (card-list state)
          (el :div {:class "hc-mat-stack"}
              (card-selection state)
              (card-editor state
                           (model/instruction-selection-pending? state))))))

(defn panel-tree
  [state section]
  (el :div {:class "hc-mat-panel"}
      (global-error state)
      (case section
        :memory (memory-tree state)
        :worldbook (worldbook-tree state)
        :instructions (cards-tree state)
        (el :div {:class "wb-empty"} "未知材料分区"))))
