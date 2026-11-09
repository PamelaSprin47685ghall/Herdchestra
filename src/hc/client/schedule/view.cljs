(ns hc.client.schedule.view
  "Pure hiccup-style tree for the schedule workspace.

  The tree carries `:events` descriptors instead of functions.  The React
  adapter resolves an action descriptor to a callback.  The tree never says a
  command succeeded; it renders the last server-confirmed schedule/run copy
  and the user's unsaved form.

  Copy rules from AGENTS.md §7.4 旅程 6.8（定时任务） and appendix §6.9:

  - every time is shown with its zone, never as a bare `08:00`;
  - a missed occurrence is “未运行” with a reason and is never caught up;
  - an overlapping occurrence is “已跳过：上次仍在运行”;
  - a paused schedule keeps a running item visible with a separate stop;
  - a restricted platform says “仅支持前台手动运行” and never promises a
    background wakeup."
  (:require [clojure.string :as str]
            [hc.client.schedule.model :as model]
            [hc.client.schedule.time :as time]
            [hc.client.sessions.labels :as session-labels]))

;; ---------------------------------------------------------------------------
;; Tree primitives

(defn- el
  ([tag] {:tag tag :attrs {} :children []})
  ([tag attrs] {:tag tag :attrs (or attrs {}) :children []})
  ([tag attrs & children]
   {:tag tag
    :attrs (or attrs {})
    :children (vec (remove nil? children))}))

(defn- act
  [type & kvs]
  (assoc (apply hash-map kvs) :type type))

(defn- ev
  [action]
  {:action action})

(defn- ev-value
  [action]
  {:action action :value :target-value})

(defn- ev-checked
  [action]
  {:action action :value :target-checked})

(defn- button
  ([label action]
   (button label action {}))
  ([label action attrs]
   (el :button
       (merge {:type "button"
               :class "hc-schedule-button"}
              attrs
              {:on-click (ev action)})
       label)))

(defn- link
  [label href attrs]
  (el :a (merge {:class "hc-schedule-link" :href href} attrs) label))

(defn- badge
  [text state & [attrs]]
  (el :span
      (merge {:class (str "hc-schedule-badge hc-schedule-badge--"
                          (name (or state :unknown)))}
             attrs)
      text))

(defn- field
  [label control & [hint error]]
  (el :label {:class "hc-schedule-field"}
      (el :span {:class "hc-schedule-field__label"} label)
      control
      (when hint (el :small {:class "hc-schedule-hint"} hint))
      (when error (el :small {:class "hc-schedule-error"} error))))

(defn- text-input
  [path value attrs]
  (el :input (merge {:class "hc-schedule-input"
                     :type "text"
                     :value (or value "")
                     :on-input (ev-value (act :schedule/change :path path))}
                    attrs)))

(defn- textarea
  [path value attrs]
  (el :textarea (merge {:class "hc-schedule-textarea"
                        :value (or value "")
                        :on-input (ev-value (act :schedule/change :path path))}
                       attrs)))

(defn- option-value
  [value]
  (cond
    (nil? value) ""
    (keyword? value) (name value)
    (string? value) value
    :else (str value)))

(defn- select
  ([path value options] (select path value options {}))
  ([path value options attrs]
   (el :select (merge {:class "hc-schedule-select"
                      :value (option-value value)
                      :on-change (ev-value (act :schedule/change :path path))}
                     attrs)
       (mapv (fn [[option-key option-label & [disabled?]]]
               (el :option {:value (option-value option-key)
                            :key (option-value option-key)
                            :disabled (when disabled? "true")}
                   option-label))
             options))))

(defn- checkbox
  ([action checked label] (checkbox action checked label {}))
  ([action checked label attrs]
   (el :label (merge {:class "hc-schedule-check"} attrs)
      (el :input {:type "checkbox"
                  :checked (when checked "true")
                  :on-change (ev-checked action)})
       (el :span {} label))))

(defn- radio
  [input-name action value checked label disabled? hint]
  (el :label {:class "hc-schedule-radio"}
      (el :input {:type "radio"
                  :name input-name
                  :value (clojure.core/name value)
                  :checked (when checked "true")
                  :disabled (when disabled? "true")
                  :on-change (ev-value action)})
      (el :span {}
          (el :strong {} label)
          (when hint (el :small {:class "hc-schedule-hint"} hint)))))

;; ---------------------------------------------------------------------------
;; Labels

(defn- timezone-label
  [timezone]
  (case (keyword (:mode timezone))
    :fixed (str "固定时区（" (:zone timezone) "）")
    :follow-device (str "跟随设备时区（" (:zone timezone) "）")
    (str "时区未知（" (:zone timezone) "）")))

(defn- next-run-label
  [state schedule]
  (let [platform (:platform state)
        zone (get-in schedule [:timezone :zone])
        browser (get-in state [:ui :browser-zone])
        next-run (:next-run schedule)]
    (cond
      (= :paused (:state schedule)) "已暂停，不会自动开始"
      (nil? next-run) "没有未来运行"
      :else
      (let [base (time/format-epoch next-run zone)
            device (when (and (not= browser zone)
                              (time/valid-zone? browser))
                     (time/local-view next-run zone browser))
            device-text (when device
                          (str "；设备时间 " (:device-display device)
                               "（" (:device-zone device) "）"))]
        (if (:manual-only? platform)
          (str "服务器计划 " base device-text "；此设备仅手动运行")
          (str base device-text))))))

(defn- recorded-time
  [run prefix]
  (let [local (get-in run [(keyword (str prefix "-local"))])
        zone (get-in run [(keyword (str prefix "-zone"))])
        at (get-in run [(keyword (str prefix "-at"))])]
    (cond
      (and local zone) (str (str/replace (str local) "T" " ")
                            "（" zone "）")
      (and at zone) (time/format-epoch at zone)
      local (str (str/replace (str local) "T" " "))
      at (str at)
      :else "时间未知")))

(defn- session-option-label
  [session]
  (let [assistant (:assistant-name session)
        title (session-labels/title-label (:title session))
        last-message (model/preview-text
                      (model/message-text (:last-message session)) 40)]
    (str title
         (when (not (str/blank? (str assistant)))
           (str " · 助手 " assistant))
         (when last-message
           (str " · 最后：" last-message)))))

(defn- message-role-label
  [role]
  (case (keyword role)
    :user "用户"
    :assistant "助手"
    :system "系统"
    "消息"))

(defn- message-option-label
  [message]
  (str (message-role-label (:role message))
       " · "
       (or (model/preview-text (model/message-text message) 48)
           "（无正文）")))

(defn- form-model-value
  [form]
  (let [model (:model form)
        mode (keyword (or (:model-mode form)
                          (when model :fixed)
                          :follow-assistant))]
    (when (and model (= :fixed mode))
      (str (:connection-id model) "|" (:model-entry-id model)))))

(declare preview-panel)

(defn- issue-list
  [issues]
  (when (seq issues)
    (el :ul {:class "hc-schedule-issues"}
        (mapv (fn [issue]
                (el :li {:key (str (:code issue) (hash (:message issue)))}
                    (or (:message issue) (str (:code issue)))
                    (when-let [cause (get-in issue [:details :cause])]
                      (el :small {:class "hc-schedule-hint"} (str "：" cause)))))
              issues))))

;; ---------------------------------------------------------------------------
;; Shared panels

(defn- platform-banner
  [state]
  (let [platform (:platform state)]
    (el :div {:class (str "hc-schedule-platform hc-schedule-platform--"
                          (name (:kind platform)))
              :role "status"}
        (el :strong {} (:label platform))
        (el :span {} (:notice platform)))))

(defn- sync-banner
  [state]
  (let [phase (get-in state [:sync :phase] :disconnected)
        can-submit? (and (= :synced phase)
                         (pos-int? (or (get-in state [:sync :command-epoch])
                                       0)))
        label (if (and (= :synced phase) (not can-submit?))
                "已同步，等待命令基线"
                (case phase
                  :synced "已同步"
                  :connecting "正在连接"
                :connected-awaiting-sync "已连接，等待同步"
                  :interrupted "连接中断；保留最后确认状态"
                  :disconnected "未连接"
                  "连接状态未知"))]
    (el :div {:class (str "hc-schedule-sync hc-schedule-sync--" (name phase))
              :role "status"}
        (el :strong {} label)
        (el :span {} (if can-submit?
                       "调度器命令通道可用；保存、暂停和立即运行都等待服务器回执。"
                       "命令基线尚未取得；保存、暂停与运行不会提交，表单与未保存标记会保留。")))))

(defn- notice-banner
  [state]
  (when-let [notice (get-in state [:ui :notice])]
    (el :div {:class (str "hc-schedule-notice hc-schedule-notice--"
                          (name (:tone notice :info)))
              :role (if (= :danger (:tone notice)) "alert" "status")}
        (el :span {} (:text notice))
        (button "关闭" (act :schedule/dismiss-notice)
                {:class "hc-schedule-button hc-schedule-button--quiet"}))))

(defn- load-error
  [state]
  (when-let [message (get-in state [:server :load-error])]
    (el :div {:class "hc-schedule-notice hc-schedule-notice--danger" :role "alert"}
        (el :strong {} "调度器状态不可用")
        (el :span {} message)
        (button "重新读取" (act :schedule/refresh :reason :load-error)
                {:class "hc-schedule-button"}))))

(defn- policy-note
  []
  (el :p {:class "hc-schedule-hint"}
      "错过的计划记为“未运行”，不会集中补跑。需要现在补做时使用“立即运行”；它是一条独立的手动记录，不改变原定下次时间。"))

;; ---------------------------------------------------------------------------
;; List

(defn- schedule-card
  [state card]
  (let [schedule (:schedule card)
        id (:id card)
        platform (:platform state)
        active-run (:active-run card)
        latest (:latest-run card)
        highlighted? (= id (get-in state [:ui :highlight-id]))
        state-key (:state card)]
    (el :article {:class (str "hc-schedule-card"
                              (when highlighted? " hc-schedule-card--highlight"))
                  :key id}
        (el :header {:class "hc-schedule-card__header"}
            (el :button {:type "button"
                         :class "hc-schedule-card__title"
                         :on-click (ev (act :schedule/select :id id))}
                (:name card))
            (badge (:state-label card) state-key)
            (when (:manual-only? platform)
              (badge "此设备仅手动" :manual-only))
            (when active-run
              (badge "当前运行" (keyword (:status active-run)))))
        (el :div {:class "hc-schedule-card__facts"}
            (el :div {}
                (el :span {:class "hc-schedule-hint"} "下次运行")
                (el :strong {} (next-run-label state schedule)))
            (el :div {}
                (el :span {:class "hc-schedule-hint"} "时区")
                (el :span {} (timezone-label (:timezone schedule))))
            (el :div {}
                (el :span {:class "hc-schedule-hint"} "助手")
                (el :span {} (model/assistant-label state schedule)))
            (el :div {}
                (el :span {:class "hc-schedule-hint"} "模型")
                (el :span {} (model/model-label state schedule)))
            (el :div {}
                (el :span {:class "hc-schedule-hint"} "最近结果")
                (el :span {} (or (:latest-label card) "尚无运行记录"))))
        (when (seq (:attention schedule))
          (el :div {:class "hc-schedule-attention"}
              (el :strong {} "需要处理")
              (issue-list (:attention schedule))))
        (el :div {:class "hc-schedule-card__actions"}
            (button "详情" (act :schedule/select :id id))
            (when (and (= :paused state-key)
                       (:automatic? platform))
              (button "恢复" (act :schedule/resume :id id)))
            (when (contains? #{:enabled :needs-attention} state-key)
              (button "暂停" (act :schedule/pause :id id)))
            (button "立即运行" (act :schedule/request-manual-run :id id)
                    {:class "hc-schedule-button hc-schedule-button--primary"
                     :title (when active-run
                              "上次运行仍在进行时，本次会记为已跳过，不会排队")})
            (when active-run
              (button "停止当前运行" (act :schedule/stop-request :run-id (:id active-run))
                      {:class "hc-schedule-button hc-schedule-button--danger"})))
        (when latest
          (el :p {:class "hc-schedule-hint"}
              (str "最近一次：" (model/run-status-label latest)
                   " · " (recorded-time latest "scheduled")))))))

(defn- empty-state
  [state]
  (el :div {:class "hc-schedule-empty"}
      (el :h2 {} "尚无定时任务")
      (el :p {:class "hc-schedule-hint"}
          "定时任务按保存的重复规则运行。先创建一条，检查预览后再决定是否启用。")
      (el :div {:class "hc-schedule-empty__example"}
          (el :strong {} "中性示例：每天 08:30 生成一份摘要")
          (el :p {:class "hc-schedule-hint"}
              "点击示例只会填入表单，不会替你开启任务。")
          (button "用示例填入表单"
                  (act :schedule/new-example)
                  {:class "hc-schedule-button hc-schedule-button--quiet"}))
      (button "新建任务" (act :schedule/new)
              {:class "hc-schedule-button hc-schedule-button--primary"})))

(defn- schedule-group
  [state title cards empty-text]
  (el :section {:class "hc-schedule-group"}
      (el :h2 {} title)
      (if (seq cards)
        (el :div {:class "hc-schedule-list"}
            (mapv #(schedule-card state %) cards))
        (el :p {:class "hc-schedule-hint"} empty-text))))

(defn- list-view
  [state]
  (let [{:keys [active paused]} (model/schedule-cards state)
        no-schedules? (and (empty? active) (empty? paused))]
    (el :div {:class "hc-schedule-body"}
        (el :div {:class "hc-schedule-toolbar"}
            (el :div {}
                (el :h2 {} "任务")
                (el :p {:class "hc-schedule-hint"}
                    "按下一次运行时间排序；已暂停任务单独分组。"))
            (el :div {:class "hc-schedule-toolbar__actions"}
                (button "刷新" (act :schedule/refresh :reason :manual))
                (when-not no-schedules?
                  (button "新建任务" (act :schedule/new)
                          {:class "hc-schedule-button hc-schedule-button--primary"}))))
        (policy-note)
        (if no-schedules?
          (empty-state state)
          (el :div {}
              (schedule-group state "活动任务" active "没有活动的定时任务。")
              (schedule-group state "已暂停" paused "没有已暂停的定时任务。"))))))

;; ---------------------------------------------------------------------------
;; Detail

(defn- run-row
  [state run]
  (let [status (keyword (:status run))
        chat-id (:chat-id run)
        files (:result-files run)
        active? (model/active-run? run)]
    (el :li {:class (str "hc-schedule-run hc-schedule-run--"
                         (name (or status :unknown)))
             :key (:id run)}
        (el :div {:class "hc-schedule-run__head"}
            (badge (model/run-status-label run) status)
            (el :strong {} (recorded-time run "scheduled"))
            (when (:trigger run)
              (el :span {:class "hc-schedule-hint"}
                  (if (= :manual (keyword (:trigger run))) "手动运行" "计划运行"))))
        (el :div {:class "hc-schedule-run__facts"}
            (el :span {} (str "开始：" (if (:started-at run)
                                        (recorded-time run "started")
                                        "尚未开始")))
            (el :span {} (str "结束：" (if (:ended-at run)
                                        (recorded-time run "ended")
                                        "尚未结束")))
            (when (seq files)
              (el :details {:class "hc-schedule-run__files"}
                  (el :summary {} (str "结果文件 " (count files) " 个"))
                  (el :ul {}
                      (mapv (fn [file]
                              (let [label (if (map? file)
                                            (or (:path file) (:name file)
                                                (pr-str file))
                                            (str file))]
                                (el :li {:key label} label)))
                            files))))
            (when-let [reason (model/reason-label run)]
              (el :span {} (str "原因：" reason)))
            (when (> (model/run-attempt run) 1)
              (el :span {} (str "第 " (model/run-attempt run) " 次尝试"))))
        (when (seq (:issues run))
          (issue-list (:issues run)))
        (when (or chat-id (= :failed status))
          (el :div {:class "hc-schedule-run__actions"}
              (when chat-id
                (link "打开结果对话" (str "#/sessions/" chat-id) {}))
              (when active?
                (button "停止当前运行"
                        (act :schedule/stop-request :run-id (:id run))
                        {:class "hc-schedule-button hc-schedule-button--danger"}))
              (when (= :failed status)
                (button "重试"
                        (act :schedule/request-manual-run
                             :id (:schedule-id run)
                             :retry? true
                             :run-id (:id run))
                        {:class "hc-schedule-button hc-schedule-button--primary"})))))))

(defn- runs-panel
  [state schedule]
  (let [runs (model/runs-for state (:id schedule))]
    (el :section {:class "hc-schedule-panel"}
        (el :h2 {} "运行记录")
        (el :p {:class "hc-schedule-hint"}
            "每条运行对应一条记录；错过与跳过只记录事实，不会补跑。同一任务不重叠：上次仍在运行时，后一次记为已跳过，不会排队。")
        (if (seq runs)
          (el :ol {:class "hc-schedule-runs"}
              (mapv #(run-row state %) runs))
          (el :p {:class "hc-schedule-hint"} "尚无运行记录。")))))

(defn- active-run-panel
  [state schedule]
  (when-let [run (model/active-run state (:id schedule))]
    (el :section {:class "hc-schedule-panel hc-schedule-panel--active"}
        (el :h2 {} "当前运行")
        (el :p {}
            (str (model/run-status-label run) " · "
                 (recorded-time run "scheduled")))
        (el :div {:class "hc-schedule-card__actions"}
            (when (:chat-id run)
              (link "打开结果对话" (str "#/sessions/" (:chat-id run)) {}))
            (button "停止当前运行"
                    (act :schedule/stop-request :run-id (:id run))
                    {:class "hc-schedule-button hc-schedule-button--danger"}))
        (el :p {:class "hc-schedule-hint"}
            "暂停只阻止未来开始，不会取消这次运行；停止是独立动作。"))))

(defn- delete-panel
  [state schedule]
  (let [confirm (get-in state [:ui :delete-confirm])
        active (model/active-run state (:id schedule))]
    (when (and confirm (= (:id confirm) (:id schedule)))
      (el :section {:class "hc-schedule-panel hc-schedule-panel--danger"
                    :role "alertdialog"
                    :aria-label "确认删除定时任务"}
          (el :h2 {} (str "删除任务“" (:name schedule) "”"))
          (el :p {} "未来计划将停止。已有对话与结果文件默认保留；运行记录也可选择保留。")
          (checkbox (act :schedule/delete-option :key :delete-runs?)
                    (:delete-runs? confirm)
                    "同时删除运行记录（默认保留）")
          (when active
            (checkbox (act :schedule/delete-option :key :force?)
                      (:force? confirm)
                      "停止当前运行并删除（记录为已停止）"))
          (el :div {:class "hc-schedule-card__actions"}
              (button "取消" (act :schedule/delete-cancel)
                      {:class "hc-schedule-button hc-schedule-button--quiet"})
              (button "确认删除" (act :schedule/delete-confirm)
                      {:class "hc-schedule-button hc-schedule-button--danger"}))))))

(defn- stop-panel
  [state]
  (when-let [confirm (get-in state [:ui :stop-confirm])]
    (let [run (get-in state [:server :runs (:run-id confirm)])]
      (el :section {:class "hc-schedule-panel hc-schedule-panel--danger"
                    :role "alertdialog"
                    :aria-label "确认停止当前运行"}
          (el :h2 {} "停止当前运行")
          (el :p {} "已产生的正文、文件和工具记录会保留；迟到结果不会自动续答。")
          (when run
            (el :p {:class "hc-schedule-hint"}
                (str (model/run-status-label run) " · "
                     (recorded-time run "scheduled"))))
          (el :div {:class "hc-schedule-card__actions"}
              (button "取消" (act :schedule/stop-cancel)
                      {:class "hc-schedule-button hc-schedule-button--quiet"})
              (button "确认停止" (act :schedule/stop-confirm)
                      {:class "hc-schedule-button hc-schedule-button--danger"}))))))

(defn- manual-confirm-panel
  [state]
  (when-let [confirm (get-in state [:ui :manual-confirm])]
    (let [summary (:summary confirm)
          next-run (:next-run summary)
          zone (:zone summary)]
      (el :section {:class "hc-schedule-panel hc-schedule-panel--manual"
                    :role "alertdialog"
                    :aria-label "确认立即运行"}
          (el :h2 {} (if (:retry? confirm)
                       (str "重试任务“" (:name summary) "”")
                       (str "立即运行任务“" (:name summary) "”")))
          (el :p {:class "hc-schedule-hint"}
              (if (:retry? confirm)
                (str "这会创建一条新的尝试（第 " (:attempt confirm)
                     " 次），不会自动重发可能已完成的外部写入。")
                "这会创建一条独立的手动运行记录；不会改变原定下次时间，也不会把暂停任务重新启用。"))
          (el :div {:class "hc-schedule-facts"}
              (el :div {} (el :span {:class "hc-schedule-hint"} "模型")
                  (el :span {} (or (:model summary) "跟随助手默认")))
              (el :div {} (el :span {:class "hc-schedule-hint"} "执行方式")
                  (el :span {} (or (:execution summary) "新建对话")))
              (el :div {} (el :span {:class "hc-schedule-hint"} "结果去向")
                  (el :span {} (or (:destination summary) "新对话")))
              (el :div {} (el :span {:class "hc-schedule-hint"} "下次时间")
                  (el :span {} (if (and (number? next-run)
                                        (time/valid-zone? zone))
                                 (time/format-epoch next-run zone)
                                 "没有未来运行"))))
          (el :div {:class "hc-schedule-card__actions"}
              (button "取消" (act :schedule/manual-run-cancel)
                      {:class "hc-schedule-button hc-schedule-button--quiet"})
              (button (if (:retry? confirm) "确认重试" "确认立即运行")
                      (act :schedule/confirm-manual-run)
                      {:class "hc-schedule-button hc-schedule-button--primary"}))))))

(defn- detail-view
  [state schedule]
  (if-not schedule
    (el :div {:class "hc-schedule-body"}
        (el :p {:class "hc-schedule-hint"} "找不到该任务；它可能已被删除。")
        (button "返回任务列表" (act :schedule/back)))
    (let [platform (:platform state)
          state-key (keyword (:state schedule))
          active (model/active-run state (:id schedule))
          latest (model/latest-run state (:id schedule))
          execution (or (:execution schedule)
                        (get-in schedule [:snapshot :execution]))
          redo-message (when (= :redo-question (keyword (:mode execution)))
                         (model/session-message
                          (model/catalog-session state (:chat-id execution))
                          (:message-id execution)))]
      (el :div {:class "hc-schedule-body"}
          (el :div {:class "hc-schedule-toolbar"}
              (button "返回" (act :schedule/back)
                      {:class "hc-schedule-button hc-schedule-button--quiet"})
              (el :div {:class "hc-schedule-toolbar__actions"}
                  (button "编辑" (act :schedule/edit :id (:id schedule)))
                  (when (and (= :paused state-key)
                             (:automatic? platform))
                    (button "恢复" (act :schedule/resume :id (:id schedule))))
                  (when (and (contains? #{:draft :paused} state-key)
                             (:automatic? platform))
                    (button "启用" (act :schedule/enable :id (:id schedule))
                            {:class "hc-schedule-button hc-schedule-button--primary"}))
                  (when (and (= :needs-attention state-key)
                             (:automatic? platform))
                    (button "修复并启用" (act :schedule/repair :id (:id schedule))
                            {:class "hc-schedule-button hc-schedule-button--primary"}))
                  (when (= :enabled state-key)
                    (button "暂停" (act :schedule/pause :id (:id schedule))))
                  (button "立即运行" (act :schedule/request-manual-run :id (:id schedule))
                          {:title (when active
                                    "上次运行仍在进行时，本次会记为已跳过，不会排队")})
                  (button "删除" (act :schedule/delete-request :id (:id schedule))
                          {:class "hc-schedule-button hc-schedule-button--danger"})))
          (el :header {:class "hc-schedule-detail__header"}
              (el :h2 {} (:name schedule))
              (badge (model/schedule-state-label schedule) state-key)
              (when (:manual-only? platform)
                (badge "此设备仅手动运行" :manual-only)))
          (when (seq (:attention schedule))
            (el :div {:class "hc-schedule-attention"}
                (el :strong {} "需要处理")
                (issue-list (:attention schedule))))
          (when (:manual-only? platform)
            (el :p {:class "hc-schedule-warning"}
                "此设备仅支持前台手动运行；自动启用与恢复只在其他平台提供。仍可立即运行、暂停或编辑为暂停。"))
          (el :div {:class "hc-schedule-facts"}
              (el :div {} (el :span {:class "hc-schedule-hint"} "下次运行")
                  (el :strong {} (next-run-label state schedule)))
              (el :div {} (el :span {:class "hc-schedule-hint"} "时区")
                  (el :span {} (timezone-label (:timezone schedule))))
              (el :div {} (el :span {:class "hc-schedule-hint"} "重复规则")
                  (el :span {} (time/describe-recurrence (:recurrence schedule))))
              (el :div {} (el :span {:class "hc-schedule-hint"} "执行方式")
                  (el :span {} (model/execution-label schedule)))
              (el :div {} (el :span {:class "hc-schedule-hint"} "助手")
                  (el :span {} (model/assistant-label state schedule)))
              (el :div {} (el :span {:class "hc-schedule-hint"} "模型")
                  (el :span {} (model/model-label state schedule)))
              (el :div {} (el :span {:class "hc-schedule-hint"} "最近结果")
                  (el :span {} (if latest (model/run-status-label latest)
                                   "尚无运行记录")))
              (el :div {} (el :span {:class "hc-schedule-hint"} "工具范围")
                  (el :span {} (model/tool-scope-label schedule)))
              (el :div {} (el :span {:class "hc-schedule-hint"} "结果去向")
                  (el :span {} (or (some-> (:output schedule) pr-str)
                                   (model/result-destination-label execution))))
              (when (= :redo-question (keyword (:mode execution)))
                (el :div {} (el :span {:class "hc-schedule-hint"} "原问题")
                    (el :span {}
                        (if redo-message
                          (or (model/message-text redo-message) "（无正文）")
                          (str "找不到所选消息（" (:message-id execution) "）"))))))
          (when-let [change (:last-zone-change schedule)]
            (let [next-run (or (:new-next-run change) (:next-run schedule))]
              (el :p {:class "hc-schedule-notice hc-schedule-notice--info"}
                  (str "时区已变更（" (:from change) " → " (:to change) "）：下次将于 "
                       (if (number? next-run)
                         (time/format-epoch next-run
                                            (get-in schedule [:timezone :zone]))
                         "服务器确认的时间")
                       " 运行。"))))
          (el :details {:class "hc-schedule-prompt"}
              (el :summary {} "提示词（点击展开）")
              (el :pre {:class "hc-schedule-prompt__text"} (:prompt schedule)))
          (preview-panel state {:recurrence (:recurrence schedule)
                                :timezone (:timezone schedule)})
          (policy-note)
          (active-run-panel state schedule)
          (stop-panel state)
          (delete-panel state schedule)
          (runs-panel state schedule)))))

;; ---------------------------------------------------------------------------
;; Form

(defn- model-options
  [state form]
  (let [models (:models (:catalog state))
        current (:model form)
        options (mapv (fn [model]
                        [(str (:connection-id model) "|" (:model-entry-id model))
                         (str (:connection-name model) " / " (:label model))])
                      models)
        options (if (and current
                         (not-any? (fn [[value _]]
                                     (= value (str (:connection-id current) "|"
                                                   (:model-entry-id current))))
                                   options))
                  (conj options
                        [(str (:connection-id current) "|" (:model-entry-id current))
                         (str (:connection-id current) " / " (:label current))])
                  options)]
    (into [["" "跟随助手默认"]] options)))

(defn- recurrence-kind-options
  []
  [[:once "单次"]
   [:daily "每天"]
   [:weekdays "工作日（周一至周五）"]
   [:weekly "每周自选日期"]])

(defn- weekday-options
  [recurrence]
  (mapv (fn [[day label]]
          [day label
           (contains? (set (map #(if (integer? %) % (js/parseInt (str %) 10))
                                (:days recurrence)))
                      day)])
        (map-indexed (fn [index label] [(inc index) label])
                     time/day-names)))

(defn- preview-panel
  [state form]
  (let [now (or (get-in state [:ui :now]) (.now js/Date))
        browser (get-in state [:ui :browser-zone])
        result (time/preview (:recurrence form)
                             (let [tz (:timezone form)]
                               (assoc tz :zone (if (= :fixed (keyword (:mode tz)))
                                                 (:zone tz)
                                                 browser)))
                             now
                             {:count 3 :device-zone browser})]
    (el :section {:class "hc-schedule-preview" :aria-label "运行时间预览"}
        (el :h3 {} "预览")
        (when (:execution form)
          (el :div {:class "hc-schedule-preview__summary"}
              (el :div {}
                  (el :span {:class "hc-schedule-hint"} "工具范围")
                  (el :span {} (model/tool-scope-label form)))
              (el :div {}
                  (el :span {:class "hc-schedule-hint"} "结果去向")
                  (el :span {} (model/result-destination-label
                                (:execution form))))))
        (if (:ok? result)
          (let [{:keys [times gaps readable exhausted? zone]} (:preview result)]
            (el :div {}
                (el :p {:class "hc-schedule-hint"} (str readable))
                (if (seq times)
                  (el :ol {:class "hc-schedule-preview__times"}
                      (mapv (fn [occurrence]
                              (el :li {:key (:epoch-ms occurrence)}
                                  (el :strong {}
                                      (str/replace (:local-date-time occurrence) "T" " "))
                                  (el :span {:class "hc-schedule-hint"}
                                      (str "（" (:zone occurrence) " "
                                           (:offset occurrence) "）"))
                                  (when-let [device (:device-display occurrence)]
                                    (el :span {:class "hc-schedule-hint"}
                                        (str "；设备时间 " device
                                             "（" (:device-zone occurrence) "）")))
                                  (when (:ambiguous? occurrence)
                                    (el :span {:class "hc-schedule-hint"}
                                        "；该时刻出现两次，只运行第一次"))))
                            times))
                  (el :p {:class "hc-schedule-hint"} "该规则没有未来运行。"))
                (when (seq gaps)
                  (el :div {:class "hc-schedule-gaps"}
                      (el :strong {} "夏令时空档")
                      (el :ul {}
                          (mapv (fn [gap]
                                  (el :li {:key (:local-date-time gap)}
                                      (str (:local-date-time gap)
                                           "（" (:zone gap) "）不存在；该次记为未运行，不会移到相邻时间。")))
                                gaps))))
                (when exhausted?
                  (el :p {:class "hc-schedule-warning"}
                      "预览达到扫描上限；未来运行可能更远，保存后以服务器计算为准。"))))
          (el :p {:class "hc-schedule-warning"}
              (get-in result [:error :message] "无法计算预览。"))))))

(defn- form-errors
  [state]
  (let [errors (get-in state [:ui :form-errors])]
    (when (seq errors)
      (el :div {:class "hc-schedule-notice hc-schedule-notice--danger" :role "alert"}
          (el :strong {} "请检查表单")
          (el :ul {}
              (mapv (fn [[field message]]
                      (el :li {:key (name field)} message))
                    errors))))))

(defn- assistant-field
  [state form]
  (let [assistants (get-in state [:catalog :assistants])
        current (:assistant-id form)]
    (if (seq assistants)
      (field "助手"
             (select [:assistant-id] current
                     (into [["" "未指定助手"]]
                           (mapv (fn [assistant]
                                   [(:id assistant) (:name assistant)])
                                 assistants)))
             "助手角色提示在任务开始时生效；不会改写目标会话以后手动发送的模型。"
             (get-in state [:ui :form-errors :assistant-id]))
      (el :label {:class "hc-schedule-field"}
          (el :span {:class "hc-schedule-field__label"} "助手")
          (select [:assistant-id] current
                  (cond-> [["" "未提供助手列表"]]
                    (not (str/blank? (str current)))
                    (conj [(str current) (str current)]))
                  {:disabled "true"})
          (el :small {:class "hc-schedule-hint"}
              "未提供助手列表；保存后由服务器按可用助手处理。")))))

(defn- execution-fields
  [state form]
  (let [execution (:execution form)
        mode (keyword (:mode execution))
        sessions (get-in state [:catalog :sessions])]
    (el :div {}
        (field "执行方式"
               (select [:execution :mode] mode
                       [[:new-chat "新建对话"]
                        [:continue-chat "继续指定对话"]
                        [:redo-question "重做指定问题"]])
               "继续指定对话需要目标会话仍有可用的 Owner；重做会在源会话中重新核对目标消息。")
        (when (= :continue-chat mode)
          (field "目标会话"
                 (select [:execution :chat-id] (:chat-id execution)
                         (into [["" "请选择会话"]]
                               (mapv (fn [session]
                                       [(:id session)
                                        (session-option-label session)])
                                     sessions)))
                 "不会把目标会话的未发送草稿带入任务上下文。"
                 (get-in state [:ui :form-errors :chat-id])))
        (when (= :redo-question mode)
          (let [redo-mode (keyword (or (:redo-mode execution) :branch))
                session (some #(when (= (str (:chat-id execution)) (:id %)) %)
                              sessions)
                target-role (if (= :append-version redo-mode)
                              :assistant :user)
                all-messages (:messages session)
                matching (filterv #(= target-role (:role %)) all-messages)
                messages (if (seq matching) matching all-messages)
                selected-message (some #(when (= (str (:message-id execution))
                                                 (:id %))
                                          %)
                                       all-messages)
                field-label (if (= :append-version redo-mode)
                              "原助手回复标识"
                              "原用户问题标识")]
            (el :div {}
                (field "重做方式"
                       (select [:execution :redo-mode] redo-mode
                               [[:branch "独立分支（目标为用户问题）"]
                                [:append-version "追加新版本（目标为助手回复）"]])
                       (if (= :append-version redo-mode)
                         "在源会话的原助手回复上追加新版本；原回复保留，需要源会话模型且当前没有运行中的生成。"
                         "从源会话的原用户问题创建独立分支；原会话与原回复保持不变。")
                       (get-in state [:ui :form-errors :redo-mode]))
                (field "源会话"
                       (select [:execution :chat-id] (:chat-id execution)
                               (into [["" "请选择源会话"]]
                                     (mapv (fn [session]
                                             [(:id session)
                                              (session-option-label session)])
                                           sessions)))
                       "源会话必须仍有可用的 Owner。"
                       (get-in state [:ui :form-errors :chat-id]))
                (if (seq messages)
                  (el :div {}
                      (field "原消息"
                             (select [:execution :message-id]
                                     (:message-id execution)
                                     (into [["" "请选择消息"]]
                                           (mapv (fn [message]
                                                   [(:id message)
                                                    (message-option-label message)])
                                                 messages)))
                             "选择源会话中的目标消息；服务端会再次核对角色与运行状态。"
                             (get-in state [:ui :form-errors :message-id]))
                      (when selected-message
                        (el :p {:class "hc-schedule-hint"}
                            (str "所选消息："
                                 (or (model/message-text selected-message)
                                     "（无正文）")))))
                  (field field-label
                         (text-input [:execution :message-id]
                                     (:message-id execution) {})
                         (if (= :append-version redo-mode)
                           "填写源会话中的助手回复标识；服务端会核对消息类型与运行状态。"
                           "填写源会话中的用户消息标识；服务端会核对消息类型。")
                         (get-in state [:ui :form-errors :message-id])))))))))

(defn- form-view
  [state]
  (let [form (get-in state [:ui :form])
        schedule (when (= :edit (:mode form))
                   (get-in state [:server :schedules (:id form)]))
        errors (get-in state [:ui :form-errors])
        platform (:platform state)
        submitting? (get-in state [:ui :form-submitting?])
        validation (model/validate-form state form)
        recurrence (:recurrence form)
        kind (keyword (:kind recurrence))
        timezone (:timezone form)
        save-mode (keyword (:save-mode form))
        enable-blocked? (:enable-blocked? validation)
        enable-reason (:enable-block-reason validation)
        title (if (= :edit (:mode form)) "编辑任务" "新建任务")]
    (el :div {:class "hc-schedule-body"}
        (el :div {:class "hc-schedule-toolbar"}
            (button "取消" (act :schedule/cancel-form)
                    {:class "hc-schedule-button hc-schedule-button--quiet"})
            (el :div {:class "hc-schedule-toolbar__actions"}
                (when (:dirty? form)
                  (badge "未保存的修改" :dirty))))
        (el :h2 {} title)
        (form-errors state)
        (when (= :failed (get-in state [:ui :form-storage-status]))
          (el :div {:class "hc-schedule-notice hc-schedule-notice--danger"
                    :role "status"}
              (el :strong {} "草稿暂未保存")
              (el :span {} "浏览器本地存储不可用；关闭页面后表单内容可能丢失。可先复制提示词。")))
        (el :form {:class "hc-schedule-form"
                   :on-submit {:action (act :schedule/save)
                               :prevent-default? true}}
            (field "名称"
                   (text-input [:name] (:name form)
                               {:maxLength "80" :autocomplete "off"})
                   "最多 80 个字符。"
                   (get errors :name))
            (field "提示词"
                   (textarea [:prompt] (:prompt form)
                             {:rows "5" :placeholder "任务开始时发送给模型的任务正文"})
                   "提示词来自任务自身；助手角色提示在开始时生效。"
                   (get errors :prompt))
            (assistant-field state form)
            (field "任务模型"
                   (select [:model] (form-model-value form)
                           (model-options state form))
                   (if (seq (get-in state [:catalog :models]))
                     "新建对话默认跟随助手默认模型；继续/重做继承目标会话模型。显式选择只覆盖本任务活动。"
                     "尚无已配置模型；可跟随助手默认模型，或让继续/重做继承目标会话模型。"))
            (execution-fields state form)
            (field "重复规则"
                   (select [:recurrence :kind] kind (recurrence-kind-options))
                   nil
                   (get errors :recurrence))
            (when (= :once kind)
              (field "单次日期"
                     (el :input {:class "hc-schedule-input"
                                 :type "date"
                                 :value (or (:local-date recurrence) "")
                                 :on-input (ev-value
                                            (act :schedule/change
                                                 :path [:recurrence :local-date]))})
                     nil
                     (get errors :recurrence-date)))
            (el :div {:class "hc-schedule-time-row"}
                (field "小时"
                       (el :input {:class "hc-schedule-input"
                                   :type "number"
                                   :min "0" :max "23"
                                   :value (str (or (get-in recurrence [:time :hour]) ""))
                                   :on-input (ev-value
                                              (act :schedule/change
                                                   :path [:recurrence :time :hour]))})
                       nil
                       (get errors :time))
                (field "分钟"
                       (el :input {:class "hc-schedule-input"
                                   :type "number"
                                   :min "0" :max "59"
                                   :value (str (or (get-in recurrence [:time :minute]) ""))
                                   :on-input (ev-value
                                              (act :schedule/change
                                                   :path [:recurrence :time :minute]))})
                       "24 小时制；显示时始终带时区。"))
            (when (= :weekly kind)
              (el :fieldset {:class "hc-schedule-days"}
                  (el :legend {} "星期")
                  (mapv (fn [[day label checked?]]
                          (checkbox (act :schedule/toggle-day :day day)
                                    checked? label
                                    {:key (str day)}))
                        (weekday-options recurrence))
                  (when (get errors :days)
                    (el :small {:class "hc-schedule-error"} (get errors :days)))))
            (field "时区"
                   (select [:timezone :mode] (keyword (:mode timezone))
                           [[:follow-device "跟随设备时区"]
                            [:fixed "固定时区"]])
                   (if (= :follow-device (keyword (:mode timezone)))
                     (str "当前设备时区：" (get-in state [:ui :browser-zone])
                          "。设备切换时区后未来运行按新时区计算。")
                     "固定时区不随旅行改变；历史记录保留发生时的当地时间和时区。")
                   (get errors :timezone))
            (when (= :fixed (keyword (:mode timezone)))
              (field "IANA 时区名称"
                     (text-input [:timezone :zone] (:zone timezone)
                                 {:placeholder "Asia/Shanghai"})
                     "例如 Asia/Shanghai、Europe/Paris、America/New_York。"
                     (get errors :timezone-zone)))
            (preview-panel state form)
            (if (= :edit (:mode form))
              (el :p {:class "hc-schedule-hint"}
                  "保存修改不会改变启停状态；暂停、恢复或启用请使用详情页按钮。")
              (el :fieldset {:class "hc-schedule-save-mode"}
                  (el :legend {} "保存方式")
                  (radio "schedule-save-mode"
                         (act :schedule/save-mode)
                         :enable
                         (= :enable save-mode)
                         "保存并启用"
                         (or enable-blocked? submitting?)
                         (when enable-blocked? enable-reason))
                  (radio "schedule-save-mode"
                         (act :schedule/save-mode)
                         :paused
                         (= :paused save-mode)
                         "保存为暂停"
                         submitting?
                         "暂停任务不会自动开始；可稍后启用或立即运行。")))
            (when (and (= :needs-attention (:state schedule))
                       (= :edit (:mode form)))
              (el :p {:class "hc-schedule-warning"}
                  "该任务当前需要处理。保存修改后，回到详情点击“修复并启用”以清除问题并重新启用。"))
            (el :div {:class "hc-schedule-form__actions"}
                (button "取消" (act :schedule/cancel-form)
                        {:class "hc-schedule-button hc-schedule-button--quiet"})
                (el :button {:type "submit"
                             :class "hc-schedule-button hc-schedule-button--primary"
                             :disabled (when submitting? "true")}
                    (cond
                      submitting? "正在提交…"
                      (= :edit (:mode form)) "保存修改"
                      (= :enable save-mode) "保存并启用"
                      :else "保存为暂停")))))))

;; ---------------------------------------------------------------------------
;; Root

(def schedule-css
  ".hc-schedule { --hc-fg:#111; --hc-muted:#4a4a4a; --hc-bg:#fff; --hc-surface:#f7f7f7; --hc-border:#b8b8b8; --hc-accent:#0b57d0; --hc-positive:#146c2e; --hc-warning:#7a3e00; --hc-danger:#a4000f; color:var(--hc-fg); background:var(--hc-bg); overflow-wrap:anywhere; }
.hc-schedule * { box-sizing:border-box; }
.hc-schedule h1,.hc-schedule h2,.hc-schedule h3 { margin:.2rem 0 .6rem; }
.hc-schedule__header p { color:var(--hc-muted); margin:0 0 1rem; }
.hc-schedule-platform,.hc-schedule-sync,.hc-schedule-notice { display:flex; flex-wrap:wrap; gap:.5rem 1rem; align-items:baseline; border:1px solid var(--hc-border); border-left-width:6px; padding:.7rem .9rem; border-radius:.4rem; margin:0 0 .8rem; background:var(--hc-surface); }
.hc-schedule-platform--ios { border-left-color:var(--hc-warning); }
.hc-schedule-sync--synced { border-left-color:var(--hc-positive); }
.hc-schedule-sync--interrupted,.hc-schedule-sync--disconnected { border-left-color:var(--hc-danger); }
.hc-schedule-notice--ok { border-left-color:var(--hc-positive); }
.hc-schedule-notice--warn,.hc-schedule-warning { border-left-color:var(--hc-warning); }
.hc-schedule-notice--danger { border-left-color:var(--hc-danger); }
.hc-schedule-warning { border:1px solid var(--hc-border); border-left-width:6px; padding:.6rem .8rem; border-radius:.4rem; }
.hc-schedule-toolbar { display:flex; flex-wrap:wrap; justify-content:space-between; gap:.7rem; align-items:flex-start; margin:1rem 0; }
.hc-schedule-toolbar__actions,.hc-schedule-card__actions,.hc-schedule-form__actions,.hc-schedule-run__actions { display:flex; flex-wrap:wrap; gap:.5rem; align-items:center; }
.hc-schedule-detail__header { display:flex; flex-wrap:wrap; align-items:center; gap:.5rem .9rem; margin:.4rem 0 .8rem; }
.hc-schedule-detail__header h2 { margin:0; }
.hc-schedule-button { min-height:44px; min-width:44px; padding:.5rem .9rem; border:1px solid var(--hc-border); border-radius:.4rem; background:var(--hc-surface); color:var(--hc-fg); cursor:pointer; font:inherit; }
.hc-schedule-button--primary { background:var(--hc-accent); border-color:var(--hc-accent); color:#fff; }
.hc-schedule-button--danger { border-color:var(--hc-danger); color:var(--hc-danger); }
.hc-schedule-button--quiet { background:transparent; }
.hc-schedule-button:focus-visible,.hc-schedule-input:focus-visible,.hc-schedule-select:focus-visible,.hc-schedule-textarea:focus-visible,.hc-schedule-card__title:focus-visible,.hc-schedule-prompt summary:focus-visible { outline:3px solid var(--hc-accent); outline-offset:2px; }
.hc-schedule-button[disabled] { opacity:.55; cursor:not-allowed; }
.hc-schedule-list { display:grid; gap:.8rem; }
.hc-schedule-card { border:1px solid var(--hc-border); border-radius:.5rem; padding:.9rem; background:var(--hc-surface); }
.hc-schedule-card--highlight { border-color:var(--hc-accent); box-shadow:0 0 0 2px var(--hc-accent) inset; }
.hc-schedule-card__header { display:flex; flex-wrap:wrap; gap:.5rem 1rem; align-items:center; margin-bottom:.6rem; }
.hc-schedule-card__title { background:none; border:0; color:var(--hc-accent); font:inherit; font-weight:700; font-size:1.05rem; text-align:left; cursor:pointer; padding:.2rem 0; min-height:44px; }
.hc-schedule-card__facts,.hc-schedule-facts { display:grid; grid-template-columns:repeat(auto-fit,minmax(12rem,1fr)); gap:.6rem 1rem; margin:.6rem 0; }
.hc-schedule-card__facts > div,.hc-schedule-facts > div { display:flex; flex-direction:column; gap:.15rem; }
.hc-schedule-hint { color:var(--hc-muted); font-size:.85rem; }
.hc-schedule-badge { display:inline-block; border:1px solid var(--hc-border); border-radius:999px; padding:.15rem .55rem; font-size:.8rem; background:var(--hc-bg); }
.hc-schedule-badge--enabled,.hc-schedule-badge--completed { border-color:var(--hc-positive); color:var(--hc-positive); }
.hc-schedule-badge--paused,.hc-schedule-badge--missed,.hc-schedule-badge--skipped { border-color:var(--hc-warning); color:var(--hc-warning); }
.hc-schedule-badge--needs-attention,.hc-schedule-badge--failed,.hc-schedule-badge--danger,.hc-schedule-badge--manual-only { border-color:var(--hc-danger); color:var(--hc-danger); }
.hc-schedule-badge--running,.hc-schedule-badge--preparing,.hc-schedule-badge--awaiting-user { border-color:var(--hc-accent); color:var(--hc-accent); }
.hc-schedule-attention { border:1px solid var(--hc-warning); border-radius:.4rem; padding:.6rem .8rem; margin:.6rem 0; }
.hc-schedule-issues { margin:.3rem 0 0 1.2rem; padding:0; }
.hc-schedule-empty { border:1px dashed var(--hc-border); border-radius:.5rem; padding:1.2rem; }
.hc-schedule-empty__example { border:1px solid var(--hc-border); border-radius:.4rem; padding:.8rem; margin:.8rem 0; }
.hc-schedule-group { margin-top:1rem; }
.hc-schedule-panel { border:1px solid var(--hc-border); border-radius:.5rem; padding:.9rem; margin:.8rem 0; }
.hc-schedule-panel--active { border-left:6px solid var(--hc-accent); }
.hc-schedule-panel--manual { border-left:6px solid var(--hc-accent); }
.hc-schedule-panel--danger { border-left:6px solid var(--hc-danger); }
.hc-schedule-runs { list-style:none; margin:0; padding:0; display:grid; gap:.7rem; }
.hc-schedule-run { border:1px solid var(--hc-border); border-radius:.4rem; padding:.7rem; background:var(--hc-bg); }
.hc-schedule-run__head { display:flex; flex-wrap:wrap; gap:.5rem 1rem; align-items:center; }
.hc-schedule-run__facts { display:flex; flex-wrap:wrap; gap:.3rem 1rem; color:var(--hc-muted); font-size:.85rem; margin:.4rem 0; }
.hc-schedule-run__files summary { min-height:44px; cursor:pointer; display:flex; align-items:center; }
.hc-schedule-run__files ul { margin:.3rem 0 0 1.2rem; padding:0; overflow-wrap:anywhere; }
.hc-schedule-prompt { margin:.8rem 0; }
.hc-schedule-prompt summary { min-height:44px; cursor:pointer; display:flex; align-items:center; }
.hc-schedule-prompt__text { white-space:pre-wrap; overflow-wrap:anywhere; background:var(--hc-surface); border:1px solid var(--hc-border); border-radius:.4rem; padding:.8rem; max-height:24rem; overflow:auto; }
.hc-schedule-form { display:grid; gap:.9rem; max-width:52rem; }
.hc-schedule-field { display:grid; gap:.3rem; }
.hc-schedule-field__label { font-weight:600; }
.hc-schedule-input,.hc-schedule-select,.hc-schedule-textarea { min-height:44px; padding:.5rem .6rem; border:1px solid var(--hc-border); border-radius:.4rem; background:var(--hc-bg); color:var(--hc-fg); font:inherit; width:100%; }
.hc-schedule-textarea { min-height:8rem; resize:vertical; }
.hc-schedule-time-row { display:grid; grid-template-columns:repeat(auto-fit,minmax(9rem,1fr)); gap:.8rem; }
.hc-schedule-days { border:1px solid var(--hc-border); border-radius:.4rem; padding:.7rem; display:flex; flex-wrap:wrap; gap:.6rem 1rem; }
.hc-schedule-check,.hc-schedule-radio { display:flex; gap:.5rem; align-items:flex-start; min-height:44px; padding:.35rem 0; cursor:pointer; }
.hc-schedule-check input,.hc-schedule-radio input { margin-top:.35rem; }
.hc-schedule-radio { flex-direction:row; }
.hc-schedule-radio span { display:flex; flex-direction:column; }
.hc-schedule-preview { border:1px solid var(--hc-border); border-radius:.5rem; padding:.8rem; background:var(--hc-surface); }
.hc-schedule-preview__summary { display:grid; grid-template-columns:repeat(auto-fit,minmax(12rem,1fr)); gap:.6rem 1rem; margin:.4rem 0 .6rem; }
.hc-schedule-preview__summary > div { display:flex; flex-direction:column; gap:.15rem; }
.hc-schedule-preview__times { margin:.4rem 0 .4rem 1.2rem; padding:0; }
.hc-schedule-preview__times li { margin:.3rem 0; }
.hc-schedule-error { color:var(--hc-danger); font-size:.85rem; }
.hc-schedule-save-mode { border:1px solid var(--hc-border); border-radius:.4rem; padding:.7rem; }
@media (max-width:480px) { .hc-schedule-card__facts,.hc-schedule-facts { grid-template-columns:1fr; } .hc-schedule-toolbar { flex-direction:column; } }
@media (prefers-color-scheme:dark) {
  html[data-theme=\"auto\"] .hc-schedule,
  html[data-theme=\"system\"] .hc-schedule { --hc-fg:#f2f2f2; --hc-muted:#c9c9c9; --hc-bg:#121212; --hc-surface:#1e1e1e; --hc-border:#5a5a5a; --hc-accent:#9ec1ff; --hc-positive:#7ddc9a; --hc-warning:#ffd479; --hc-danger:#ff8a8a; }
  html[data-theme=\"auto\"] .hc-schedule-button--primary,
  html[data-theme=\"system\"] .hc-schedule-button--primary { color:#08131b; }
}
html[data-theme=\"dark\"] .hc-schedule { --hc-fg:#f2f2f2; --hc-muted:#c9c9c9; --hc-bg:#121212; --hc-surface:#1e1e1e; --hc-border:#5a5a5a; --hc-accent:#9ec1ff; --hc-positive:#7ddc9a; --hc-warning:#ffd479; --hc-danger:#ff8a8a; }
html[data-theme=\"dark\"] .hc-schedule-button--primary { color:#08131b; }")

(defn schedule-tree
  "Return the complete schedule tree for STATE."
  [state]
  (let [state (or state (model/initial-state))
        view (get-in state [:ui :view] :list)
        selected (model/selected-schedule state)]
    (el :section {:class "hc-schedule" :aria-label "定时任务"}
        (el :style {} schedule-css)
        (el :header {:class "hc-schedule__header"}
            (el :h1 {} "定时任务")
            (el :p {}
                "按重复规则运行保存的任务。时间始终带时区；错过的计划记为“未运行”，不会集中补跑。"))
        (platform-banner state)
        (sync-banner state)
        (notice-banner state)
        (load-error state)
        (manual-confirm-panel state)
        (case view
          :detail (detail-view state selected)
          :form (form-view state)
          (list-view state)))))
