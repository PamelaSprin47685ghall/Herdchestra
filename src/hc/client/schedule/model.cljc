(ns hc.client.schedule.model
  "Pure schedule workspace state, form validation, and receipt application.

  This namespace never talks to Sente and never marks a server operation as
  complete by itself.  It returns `{:state state' :effects effects}`.  An
  effect is data for the bridge: submit one command, refresh the scheduler
  snapshot, persist a draft, or navigate to a result chat.

  The server owns recurrence execution and every run fact.  The client only
  projects the last confirmed copy and the user's unsaved form."
  (:require [clojure.string :as str]
            [hc.client.schedule.commands :as commands]))

;; ---------------------------------------------------------------------------
;; Vocabulary

(def schedule-state-labels
  {:draft "草稿"
   :paused "已暂停"
   :enabled "已启用"
   :needs-attention "需要处理"})

(def run-status-labels
  {:preparing "准备中"
   :running "正在执行"
   :awaiting-user "等待你回答"
   :outcome-unknown "结果未知"
   :completed "已完成"
   :partial "部分完成"
   :failed "失败"
   :stopped "已停止"
   :timed-out "已超时"
   :missed "未运行"
   :skipped "已跳过"
   :needs-attention "需要处理"
   :interrupted "已中断"})

(def active-run-statuses
  #{:preparing :running :awaiting-user :outcome-unknown})

(def reason-labels
  {:paused "暂停期间错过"
   :process-restart "应用未运行"
   :nonexistent-local-time "本地时间不存在（夏令时）"
   :previous-run-active "上次仍在运行"
   :previous-still-running "上次仍在运行"
   :timeout "超过运行时限"
   :preflight-failed "运行前检查未通过"
   :needs-attention "需要处理"
   :schedule-deleted "任务已删除"
   :user-cancel "操作员停止"
   :application-exit "应用退出"
   :timezone-change "时区变更前错过"})

(def platform-policies
  {:ios
   {:kind :ios
    :label "仅支持前台手动运行"
    :automatic? false
    :background? false
    :notice "此设备仅支持前台手动运行：不会提前生成，也不承诺定点唤醒；立即运行需要应用位于前台。计划规则会保留，可在其他设备启用。"
    :manual-only? true}

   :android
   {:kind :android
    :label "受系统与厂商后台限制"
    :automatic? true
    :background? :conditional
    :notice "后台执行受系统与厂商限制，可能延迟或错过。错过的计划记为“未运行”，不会集中补跑。"
    :manual-only? false}

   :desktop
   {:kind :desktop
    :label "应用运行时执行"
    :automatic? true
    :background? true
    :notice "任务由应用运行时执行；应用关闭期间的计划不会运行。错过的计划记为“未运行”，不会集中补跑。"
    :manual-only? false}

   :unknown
   {:kind :unknown
    :label "后台能力未确认"
    :automatic? true
    :background? :unknown
    :notice "无法确认此平台的后台执行能力；不会承诺定点唤醒。错过的计划记为“未运行”，不会集中补跑。"
    :manual-only? false}})

(defn platform-policy
  [kind]
  (let [kind (cond
               (keyword? kind) kind
               (string? kind) (keyword (str/replace kind #"^:" ""))
               :else kind)]
    (get platform-policies kind (get platform-policies :unknown))))

;; ---------------------------------------------------------------------------
;; Small helpers

(defn- now-ms
  [state]
  (or (get-in state [:ui :now])
      (get-in state [:catalog :now])
      #?(:clj (System/currentTimeMillis)
         :cljs (.now js/Date))))

(defn code-point-length
  [value]
  (if (string? value)
    #?(:clj (.codePointCount ^String value 0 (.length ^String value))
       :cljs (.-length (js/Array.from value)))
    0))

(defn- non-blank-string?
  [value]
  (and (string? value) (not (str/blank? value))))

(defn- valid-local-date?
  [value]
  (and (string? value)
       (re-matches #"\d{4}-\d{2}-\d{2}" value)
       #?(:clj (try
                 (java.time.LocalDate/parse value)
                 true
                 (catch Exception _ false))
          :cljs (let [[y m d] (mapv #(js/parseInt % 10)
                                    (str/split value #"-"))
                      dt (js/Date. (js/Date.UTC y (dec m) d))]
                  (and (= y (.getUTCFullYear dt))
                       (= m (inc (.getUTCMonth dt)))
                       (= d (.getUTCDate dt)))))))

(defn- parse-int
  [value]
  (cond
    (integer? value) value
    (number? value) (long value)
    (string? value) #?(:clj (try (Long/parseLong (str/trim value))
                                (catch NumberFormatException _ nil))
                       :cljs (let [n (js/parseInt value 10)]
                               (when-not (js/isNaN n) n)))
    :else nil))

(defn- keywordize
  [value]
  (cond
    (keyword? value) value
    (string? value) (keyword (str/replace value #"^:" ""))
    :else value))

(defn notice
  ([state tone text]
   (notice state tone text nil))
  ([state tone text details]
   (assoc-in state [:ui :notice]
             {:id (str "schedule-" (random-uuid))
           :tone tone
           :text text
           :details details
           :at (now-ms state)})))

(defn- receipt-status
  [result]
  (some-> (:status result) keywordize))

(defn- result-ok?
  [result]
  (let [status (receipt-status result)]
    (and (not (false? (:ok? result)))
         (not (contains? #{:rejected :refused :unknown :outcome-unknown
                           :failed :error}
                         status)))))

(defn- result-unknown?
  [result]
  (contains? #{:unknown :outcome-unknown} (receipt-status result)))

(defn error-message
  [result]
  (or (get-in result [:error :message])
      (get-in result [:receipt :error :message])
      (:message result)
      (get-in result [:error :code])
      "服务器未说明原因"))

(defn error-code
  [result]
  (or (get-in result [:error :code])
      (get-in result [:receipt :error :code])))

;; ---------------------------------------------------------------------------
;; Catalog and initial state

(defn- normalize-role
  [binding]
  (when (map? binding)
    {:connection-id (or (:connection-id binding)
                        (:connectionId binding))
     :model-id (or (:model-id binding)
                   (:modelId binding)
                   (:model-entry-id binding)
                   (:modelEntryId binding))
     :mode (:mode binding)}))

(defn- normalize-model-option
  [model]
  (when (map? model)
    (let [connection-id (or (:connection-id model)
                            (:connectionId model))
          model-entry-id (or (:model-entry-id model)
                             (:modelId model)
                             (:model-id model)
                             (:id model))]
      (when (and (non-blank-string? (str connection-id))
                 (non-blank-string? (str model-entry-id)))
        {:connection-id (str connection-id)
         :model-entry-id (str model-entry-id)
         :connection-name (or (:connection-name model)
                              (:connectionName model)
                              (str connection-id))
         :label (or (:label model)
                    (:display-name model)
                    (:name model)
                    (str model-entry-id))
         :source (or (:source model) :catalog)}))))

(defn message-text
  "Return the visible text of a catalog message, if it has one."
  [message]
  (cond
    (string? message) message
    (map? message) (or (:text message)
                       (:content message)
                       (let [versions (:versions message)
                             index (or (:current-version message) 0)]
                         (get-in versions [index :content])))
    :else nil))

(defn preview-text
  "Collapse whitespace and truncate TEXT for a one-line preview."
  ([text] (preview-text text 48))
  ([text limit]
   (when (string? text)
     (let [clean (str/trim (str/replace text #"\s+" " "))]
       (when-not (str/blank? clean)
         (if (> (code-point-length clean) limit)
           (str (apply str (take limit clean)) "…")
           clean))))))

(defn- normalize-message
  [message]
  (when (map? message)
    (let [id (or (:id message) (:message-id message))
          role (keywordize (:role message))
          text (message-text message)]
      (when (non-blank-string? (str id))
        (cond-> {:id (str id)}
          role (assoc :role role)
          (non-blank-string? (str text)) (assoc :text (str text)))))))

(defn- normalize-messages
  [messages]
  (->> (cond
         (map? messages) (vals messages)
         (sequential? messages) messages
         :else [])
       (keep normalize-message)
       vec))

(defn- normalize-assistant
  [assistant]
  (when (map? assistant)
    (let [id (or (:id assistant)
                 (:assistant-id assistant)
                 (:assistantId assistant))
          name (or (:name assistant)
                   (:title assistant)
                   (:display-name assistant))]
      (when (non-blank-string? (str id))
        {:id (str id)
         :name (if (non-blank-string? (str name)) (str name) (str id))}))))

(defn- normalize-assistants
  [assistants]
  (->> (cond
         (sequential? assistants) assistants
         (map? assistants) (if (sequential? (:items assistants))
                             (:items assistants)
                             (map (fn [[id assistant]]
                                    (if (map? assistant)
                                      (assoc assistant :id (or (:id assistant) id))
                                      {:id id :name assistant}))
                                  assistants))
         :else [])
       (keep normalize-assistant)
       distinct
       vec))

(defn- normalize-session
  [session]
  (when-let [id (or (:id session) (:session-id session))]
    (let [messages (normalize-messages (:messages session))
          assistant-name (or (:assistant-name session)
                             (:assistantName session)
                             (get-in session [:assistant :name]))
          last-message (:last-message session)]
      (cond-> {:id (str id)
               :title (or (:title session) (str id))}
        (non-blank-string? (str assistant-name))
        (assoc :assistant-name (str assistant-name))

        (some? last-message)
        (assoc :last-message last-message)

        (seq messages)
        (assoc :messages messages)))))

(defn normalize-catalog
  [catalog]
  (let [catalog (or catalog {})
        models (->> (:models catalog)
                    (keep normalize-model-option)
                    distinct
                    vec)
        roles (into {}
                    (keep (fn [[role binding]]
                            (when-let [normalized (normalize-role binding)]
                              [(keywordize role) normalized])))
                    (or (:roles catalog) {}))
        sessions (->> (:sessions catalog)
                      (keep normalize-session)
                      vec)
        assistants (normalize-assistants (:assistants catalog))]
    {:models models
     :roles roles
     :sessions sessions
     :assistants assistants
     :connections (or (:connections catalog) {})}))

(defn catalog-assistant
  [state assistant-id]
  (when (non-blank-string? (str assistant-id))
    (some #(when (= (str assistant-id) (:id %)) %)
          (get-in state [:catalog :assistants]))))

(defn catalog-session
  [state chat-id]
  (when (non-blank-string? (str chat-id))
    (some #(when (= (str chat-id) (:id %)) %)
          (get-in state [:catalog :sessions]))))

(defn session-message
  [session message-id]
  (when (and session (non-blank-string? (str message-id)))
    (some #(when (= (str message-id) (:id %)) %)
          (:messages session))))

(defn assistant-label
  "Show the assistant name when the catalog has it, else its id, else the
  honest absence of a chosen assistant."
  [state schedule]
  (let [assistant-id (or (:assistant-id schedule)
                         (get-in schedule [:snapshot :assistant-id]))
        assistant (catalog-assistant state assistant-id)]
    (cond
      (non-blank-string? (str (:name assistant))) (str (:name assistant))
      (non-blank-string? (str assistant-id)) (str assistant-id)
      :else "未指定助手")))

(defn execution-label
  [schedule]
  (let [execution (or (:execution schedule)
                      (get-in schedule [:snapshot :execution]))]
    (case (keyword (:mode execution))
      :continue-chat (str "继续指定对话（" (:chat-id execution) "）")
      :redo-question (str "重做指定问题（源会话 " (:chat-id execution)
                          " / 消息 " (:message-id execution)
                          "；" (if (= :append-version
                                     (keyword (:redo-mode execution)))
                                "追加新版本" "独立分支") "）")
      "新建对话")))

(defn result-destination-label
  [execution]
  (let [execution (or execution {})]
    (case (keyword (:mode execution))
      :continue-chat (str "目标会话"
                          (when-let [chat-id (:chat-id execution)]
                            (str " " chat-id)))
      :redo-question (if (= :append-version
                           (keyword (:redo-mode execution)))
                       "源会话的新版本"
                       "源会话的独立分支")
      "新对话")))

(defn tool-scope-label
  [value]
  (let [scope (or (:tool-scope value)
                  (get-in value [:snapshot :tool-scope])
                  (:capability-scope value)
                  (get-in value [:snapshot :capability-scope]))]
    (cond
      (nil? scope) "未声明"
      (and (string? scope) (str/blank? scope)) "未声明"
      (string? scope) scope
      :else (pr-str scope))))

(defn model-label
  [state schedule]
  (let [binding (get-in schedule [:snapshot :model])
        connection-id (:connection-id binding)
        model-id (or (:model-entry-id binding) (:model-id binding))
        option (some #(when (and (= (str connection-id) (:connection-id %))
                                 (= (str model-id) (:model-entry-id %)))
                        %)
                     (get-in state [:catalog :models]))
        execution (or (:execution schedule)
                      (get-in schedule [:snapshot :execution]))
        mode (keyword (:mode execution))]
    (cond
      (and connection-id model-id)
      (if option
        (str (:connection-name option) " / " (:label option))
        (str connection-id " / " model-id))

      (= :continue-chat mode) "继承目标会话模型"
      (= :redo-question mode) "继承源会话模型"
      :else "跟随助手默认")))

(defn device-zone
  [state]
  (or (get-in state [:ui :browser-zone])
      (get-in state [:server :device-zone])
      "UTC"))

(defn initial-state
  ([] (initial-state {}))
  ([{:keys [platform device-zone sync catalog now]
     :or {platform :desktop}}]
   {:schedule/version 1
    :sync (merge {:phase :disconnected :command-epoch nil} sync)
    :server {:schedules {}
             :runs {}
             :run-order []
             :device-zone device-zone
             :revision nil
             :loaded? false
             :load-error nil
             :health nil
             :last-sync-at nil}
    :catalog (normalize-catalog catalog)
    :platform (platform-policy platform)
    :ui {:view :list
         :selected-id nil
         :form nil
         :form-errors {}
         :form-submitting? false
         :highlight-id nil
         :notice nil
         :delete-confirm nil
         :stop-confirm nil
         :manual-confirm nil
         :now now
         :browser-zone device-zone
         :zone-sync-pending? false
         :refresh-pending? false
         :expanded #{}
         :server-preview nil
         :form-storage-status :unknown}
    :commands {}
    :outbox []}))

;; ---------------------------------------------------------------------------
;; Form construction and validation

(defn new-form
  [state]
  (let [automatic? (:automatic? (:platform state))]
    {:id nil
     :mode :create
     :name ""
     :prompt ""
     :assistant-id nil
     :execution {:mode :new-chat}
     :recurrence {:kind :daily
                  :time {:hour 8 :minute 30}
                  :days [1 3]}
     :timezone {:mode :follow-device :zone (device-zone state)}
     ;; The server freezes this key even when nil.  Carrying it from the form
     ;; makes "no explicit ceiling" visible in the create payload instead of
     ;; letting the server add a hidden key later.
     :capability-scope nil
     :model-mode :follow-assistant
     :model nil
     :save-mode (if automatic? :enable :paused)
     :dirty? false}))

(defn form-from-schedule
  [state schedule]
  (let [snapshot (:snapshot schedule)
        binding (:model snapshot)
        model (when (and (map? binding)
                         (non-blank-string? (str (:connection-id binding)))
                         (non-blank-string? (str (or (:model-entry-id binding)
                                                     (:model-id binding)))))
                {:connection-id (str (:connection-id binding))
                 :model-entry-id (str (or (:model-entry-id binding)
                                          (:model-id binding)))
                 :label (str (or (:model-entry-id binding)
                                 (:model-id binding)))
                 :source :schedule})
        assistant-id (or (:assistant-id schedule)
                         (get-in snapshot [:assistant-id]))]
    {:id (:id schedule)
     :mode :edit
     :name (or (:name schedule) "")
     :prompt (or (:prompt schedule) "")
     :assistant-id (some-> assistant-id str)
     :execution (or (:execution schedule)
                    (get-in schedule [:snapshot :execution])
                    {:mode :new-chat})
     :recurrence (or (:recurrence schedule)
                     {:kind :daily :time {:hour 8 :minute 30} :days [1 3]})
     :timezone (or (:timezone schedule)
                   {:mode :follow-device :zone (device-zone state)})
     :capability-scope (or (:capability-scope schedule)
                           (get-in snapshot [:capability-scope]))
     :model-mode (if model :fixed :follow-assistant)
     :model model
     :snapshot snapshot
     :save-mode (if (= :enabled (:state schedule)) :enable :paused)
     :dirty? false}))

(defn- normalize-recurrence
  [recurrence]
  (let [kind (keywordize (:kind recurrence))
        time (:time recurrence)
        hour (parse-int (:hour time))
        minute (parse-int (:minute time))
        days (->> (:days recurrence)
                  (map parse-int)
                  (filter #(and (integer? %) (<= 1 % 7)))
                  distinct
                  sort
                  vec)]
    (cond-> {:kind kind
             :time {:hour hour :minute minute}}
      (= :once kind) (assoc :local-date (:local-date recurrence))
      (= :weekly kind) (assoc :days days)
      (:starts-on recurrence) (assoc :starts-on (:starts-on recurrence)))))

(defn- normalize-timezone
  [timezone state]
  (let [mode (keywordize (or (:mode timezone) :follow-device))
        zone (or (:zone timezone)
                 (device-zone state))]
    {:mode mode
     :zone (if (= :fixed mode) zone (device-zone state))}))

(defn- normalize-execution
  [execution]
  (let [mode (keywordize (or (:mode execution) :new-chat))]
    (case mode
      :continue-chat {:mode :continue-chat
                      :chat-id (:chat-id execution)}
      :redo-question (let [redo-mode (keywordize (or (:redo-mode execution)
                                                     :branch))
                           redo-mode (if (contains? #{:branch :append-version}
                                                    redo-mode)
                                       redo-mode
                                       :branch)]
                       (cond-> {:mode :redo-question
                                :redo-mode redo-mode
                                :message-id (:message-id execution)}
                         (:chat-id execution) (assoc :chat-id (:chat-id execution))))
      {:mode :new-chat})))

(defn- form-snapshot
  [form]
  (let [snapshot (or (:snapshot form) {})
        snapshot (merge snapshot
                        {:prompt (:prompt form)
                         :execution (normalize-execution (:execution form))
                         :capability-scope (:capability-scope form)})
        assistant-id (:assistant-id form)
        snapshot (if (non-blank-string? (str assistant-id))
                   (assoc snapshot :assistant-id (str assistant-id))
                   (dissoc snapshot :assistant-id))
        model-mode (keywordize (or (:model-mode form)
                                   (when (:model form) :fixed)
                                   :follow-assistant))
        model (:model form)]
    (if (and (= :fixed model-mode) model)
      (assoc snapshot :model {:connection-id (:connection-id model)
                              :model-entry-id (:model-entry-id model)})
      (dissoc snapshot :model))))

(defn form->create-spec
  [state form]
  (let [enable? (and (= :enable (:save-mode form))
                     (:automatic? (:platform state)))
        assistant-id (:assistant-id form)]
    (cond-> {:id (or (:id form) (str (random-uuid)))
             :name (str/trim (or (:name form) ""))
             :prompt (:prompt form)
             :snapshot (form-snapshot form)
             :execution (normalize-execution (:execution form))
             :recurrence (normalize-recurrence (:recurrence form))
             :timezone (normalize-timezone (:timezone form) state)
             :state (if enable? :draft :paused)
             :enable? enable?}
      (non-blank-string? (str assistant-id))
      (assoc :assistant-id (str assistant-id)))))

(defn form->changes
  [state form]
  (let [assistant-id (:assistant-id form)]
    (cond-> {:name (str/trim (or (:name form) ""))
             :prompt (:prompt form)
             :snapshot (form-snapshot form)
             :execution (normalize-execution (:execution form))
             :recurrence (normalize-recurrence (:recurrence form))
             :timezone (normalize-timezone (:timezone form) state)}
      (non-blank-string? (str assistant-id))
      (assoc :assistant-id (str assistant-id)))))

(defn validate-form
  "Return `{:ok? bool :errors {path message} :enable-blocked? bool
  :enable-block-reason string}`."
  [state form]
  (let [form (or form {})
        recurrence (:recurrence form)
        time (:time recurrence)
        hour (parse-int (:hour time))
        minute (parse-int (:minute time))
        execution (:execution form)
        timezone (:timezone form)
        policy (:platform state)
        name (or (:name form) "")
        prompt (or (:prompt form) "")
        errors
        (cond-> {}
          (str/blank? name)
          (assoc :name "名称为必填项。")

          (> (code-point-length (str/trim name)) 80)
          (assoc :name "名称不能超过 80 个字符。")

          (str/blank? prompt)
          (assoc :prompt "提示词为必填项，不能只包含空白。")

          (not (contains? #{:once :daily :weekdays :weekly}
                          (keywordize (:kind recurrence))))
          (assoc :recurrence "请选择单次、每天、工作日或每周。")

          (or (nil? hour) (not (<= 0 hour 23)))
          (assoc :time "小时必须在 0–23 之间。")

          (or (nil? minute) (not (<= 0 minute 59)))
          (assoc :time "分钟必须在 0–59 之间。")

          (and (= :once (keywordize (:kind recurrence)))
               (not (valid-local-date? (:local-date recurrence))))
          (assoc :recurrence-date "单次任务需要一个有效日期（YYYY-MM-DD）。")

          (and (= :weekly (keywordize (:kind recurrence)))
               (empty? (->> (:days recurrence)
                            (map parse-int)
                            (filter #(and (integer? %) (<= 1 % 7))))))
          (assoc :days "每周任务至少选择一个星期。")

          (not (contains? #{:follow-device :fixed} (keywordize (:mode timezone))))
          (assoc :timezone "请选择跟随设备时区或固定时区。")

          (and (= :fixed (keywordize (:mode timezone)))
               (not (non-blank-string? (str (:zone timezone)))))
          (assoc :timezone-zone "固定时区需要一个 IANA 时区名称。")

          (and (= :continue-chat (keywordize (:mode execution)))
               (not (non-blank-string? (str (:chat-id execution)))))
          (assoc :chat-id "继续指定对话需要选择一个目标会话。")

          (and (= :redo-question (keywordize (:mode execution)))
               (not (contains? #{:branch :append-version}
                               (keywordize (or (:redo-mode execution) :branch)))))
          (assoc :redo-mode "重做方式必须是独立分支或追加新版本。")

          (and (= :redo-question (keywordize (:mode execution)))
               (not (non-blank-string? (str (:chat-id execution)))))
          (assoc :chat-id "重做指定问题需要选择源会话。")

          (and (= :redo-question (keywordize (:mode execution)))
               (not (non-blank-string? (str (:message-id execution)))))
          (assoc :message-id "重做指定问题需要填写原问题标识。"))
        enable-blocked? (not (:automatic? policy))
        enable-block-reason (cond
                              (not (:automatic? policy))
                              (:notice policy)
                              :else nil)]
    {:ok? (empty? errors)
     :errors errors
     :enable-blocked? enable-blocked?
     :enable-block-reason enable-block-reason}))

;; ---------------------------------------------------------------------------
;; Server projections

(defn normalize-schedules
  [schedules]
  (cond
    (map? schedules) schedules
    (sequential? schedules)
    (into {} (keep (fn [schedule]
                     (when-let [id (or (:id schedule) (:schedule-id schedule))]
                       [(str id) schedule])))
          schedules)
    :else {}))

(defn normalize-runs
  [runs]
  (cond
    (map? runs) runs
    (sequential? runs)
    (into {} (keep (fn [run]
                     (when-let [id (or (:id run) (:run-id run))]
                       [(str id) run])))
          runs)
    :else {}))

(defn- run-sort-key
  [run]
  [(or (:scheduled-at run) 0)
   (or (:started-at run) 0)
   (or (:id run) "")])

(defn run-order
  [runs]
  (->> runs
       vals
       (sort-by run-sort-key)
       (mapv :id)))

(defn- normalize-snapshot
  [snapshot]
  (let [snapshot (or snapshot {})
        schedules (or (:schedules snapshot)
                      (:schedule/by-id snapshot)
                      {})
        runs (or (:runs snapshot)
                 (:run/by-id snapshot)
                 {})
        schedules (normalize-schedules schedules)
        runs (normalize-runs runs)
        order (or (:run-order snapshot)
                  (run-order runs))]
    {:schedules schedules
     :runs runs
     :run-order (vec order)
     :device-zone (:device-zone snapshot)
     :revision (:revision snapshot)
     :seq (:seq snapshot)
     :health (:health snapshot)}))

(defn apply-snapshot
  "Replace the server display copy with one scheduler snapshot.

  A full snapshot replaces both collections; a partial read keeps the
  collection it did not carry.  Unsaved form drafts are never discarded."
  [state snapshot]
  (let [{:keys [schedules runs run-order device-zone revision seq health]}
        (normalize-snapshot snapshot)
        state (cond-> state
                (contains? snapshot :schedules)
                (assoc-in [:server :schedules] schedules)

                (contains? snapshot :runs)
                (assoc-in [:server :runs] runs)

                (or (contains? snapshot :run-order)
                    (contains? snapshot :runs))
                (assoc-in [:server :run-order] run-order)

                (some? device-zone)
                (assoc-in [:server :device-zone] device-zone)

                (some? revision)
                (assoc-in [:server :revision] revision)

                (some? seq)
                (assoc-in [:server :seq] seq)

                (some? health)
                (assoc-in [:server :health] health))]
    (-> state
        (assoc-in [:server :loaded?] true)
        (assoc-in [:server :load-error] nil)
        (assoc-in [:server :last-sync-at] (now-ms state)))))

(defn apply-schedules
  [state schedules]
  (-> state
      (assoc-in [:server :schedules] (normalize-schedules schedules))
      (assoc-in [:server :loaded?] true)
      (assoc-in [:server :load-error] nil)))

(defn apply-runs
  [state runs]
  (let [runs (normalize-runs runs)]
    (-> state
        (assoc-in [:server :runs] runs)
        (assoc-in [:server :run-order] (run-order runs)))))

(defn mark-load-error
  [state message]
  (-> state
      (assoc-in [:server :loaded?] true)
      (assoc-in [:server :load-error] message)
      (notice :danger message)))

;; ---------------------------------------------------------------------------
;; Commands and effects

(defn- submit-effect
  [command]
  {:effect :submit-command
   :command-id (:command-id command)
   :envelope {:command-id (:command-id command)
              :command-epoch (:command-epoch command)
              :payload (assoc (:payload command) :kind (:kind command))}})

(defn- queue-command
  ([state kind payload]
   (queue-command state kind payload {}))
  ([state kind payload opts]
   (let [result (commands/queue state kind payload
                                (assoc opts :now (now-ms state)))]
     (if (:ok? result)
       {:state (:state result)
        :effects [(submit-effect (:command result))]}
       {:state (notice (:state result) :warn
                       (get-in result [:error :message]))
        :effects []}))))

(defn- queue-refresh
  [state reason]
  (if (commands/pending-command-for state [:scheduler/snapshot :global])
    {:state (assoc-in state [:ui :refresh-pending?] true)
     :effects []}
    (queue-command state :scheduler/snapshot {} {:reason reason})))

(defn- browser-zone
  [state]
  (get-in state [:ui :browser-zone]))

(defn- maybe-sync-zone
  "Report the browser zone once per confirmed snapshot when it differs from
  the server's device zone.  Fixed schedules are unaffected by the server."
  [state]
  (let [browser (browser-zone state)
        server (get-in state [:server :device-zone])]
    (if (and (commands/command-allowed? state)
             (non-blank-string? (str browser))
             (not= browser server)
             (not (get-in state [:ui :zone-sync-pending?])))
      (let [{:keys [state effects]}
            (queue-command (assoc-in state [:ui :zone-sync-pending?] true)
                           :scheduler/set-device-zone
                           {:zone browser}
                           {:reason :device-zone-changed})]
        {:state state :effects effects})
      {:state state :effects []})))

;; ---------------------------------------------------------------------------
;; Run projections

(defn active-run?
  [run]
  (contains? active-run-statuses (keywordize (:status run))))

(defn run-attempt
  [run]
  (or (parse-int (:attempt run)) 1))

(defn runs-for
  [state schedule-id]
  (->> (:runs (:server state))
       vals
       (filter #(= schedule-id (:schedule-id %)))
       (sort-by run-sort-key)
       reverse
       vec))

(defn active-run
  [state schedule-id]
  (some #(when (active-run? %) %) (runs-for state schedule-id)))

(defn latest-run
  [state schedule-id]
  (first (runs-for state schedule-id)))

(defn- localized-label
  "Return the localized label for `value`, else its name, else `fallback`.
  Non-nameable values never throw; they use `fallback`."
  [labels value fallback]
  (or (get labels value)
      (when (or (keyword? value) (symbol? value) (string? value))
        (not-empty (name value)))
      fallback))

(defn reason-label
  [run]
  (when-let [reason (keywordize (:reason run))]
    (get reason-labels reason (name reason))))

(defn run-status-label
  [run]
  (let [status (keywordize (:status run))
        reason (keywordize (:reason run))]
    (case status
      :missed (str "未运行" (when reason
                              (str "：" (get reason-labels reason (name reason)))))
      :skipped (str "已跳过" (when reason
                               (str "：" (get reason-labels reason (name reason)))))
      :needs-attention "需要处理"
      (get run-status-labels status (name (or status :unknown))))))

(defn schedule-state-label
  [schedule]
  (get schedule-state-labels (keywordize (:state schedule))
       (name (or (:state schedule) :unknown))))

(defn- schedule-sort-key
  [schedule]
  [(or (:next-run schedule) ##Inf)
   (or (:name schedule) "")])

(defn schedule-groups
  "Active schedules sorted by the next real time; paused schedules separate."
  [state]
  (let [schedules (vals (get-in state [:server :schedules]))
        paused? #(= :paused (keywordize (:state %)))
        active (->> schedules (remove paused?) (sort-by schedule-sort-key) vec)
        paused (->> schedules (filter paused?) (sort-by schedule-sort-key) vec)]
    {:active active
     :paused paused}))

(defn schedule-cards
  [state]
  (let [{:keys [active paused]} (schedule-groups state)
        card (fn [schedule]
               (let [id (:id schedule)
                     active-run (active-run state id)
                     latest (latest-run state id)]
                 {:schedule schedule
                  :id id
                  :name (:name schedule)
                  :state-label (schedule-state-label schedule)
                  :state (keywordize (:state schedule))
                  :next-run (:next-run schedule)
                  :zone (get-in schedule [:timezone :zone])
                  :timezone-mode (keywordize (get-in schedule [:timezone :mode]))
                  :active-run active-run
                  :latest-run latest
                  :latest-label (when latest (run-status-label latest))}))]
    {:active (mapv card active)
     :paused (mapv card paused)}))

(defn selected-schedule
  [state]
  (get-in state [:server :schedules (get-in state [:ui :selected-id])]))

;; ---------------------------------------------------------------------------
;; Receipts

(defn- upsert-schedule
  [state schedule]
  (if-let [id (or (:id schedule) (:schedule-id schedule))]
    (assoc-in state [:server :schedules (str id)] schedule)
    state))

(defn- upsert-run
  [state run]
  (if-let [id (or (:id run) (:run-id run))]
    (let [id (str id)
          state (assoc-in state [:server :runs id] run)
          order (->> (conj (vec (get-in state [:server :run-order] [])) id)
                     distinct
                     vec)]
      (assoc-in state [:server :run-order] order))
    state))

(defn- remove-schedule
  [state schedule-id delete-runs?]
  (let [run-ids (if delete-runs?
                  (->> (get-in state [:server :runs])
                       vals
                       (filter #(= schedule-id (:schedule-id %)))
                       (map :id)
                       set)
                  #{})]
    (-> state
        (update-in [:server :schedules] dissoc schedule-id)
        (update-in [:server :runs]
                   (fn [runs]
                     (if delete-runs?
                       (apply dissoc runs run-ids)
                       runs)))
        (update-in [:server :run-order]
                   (fn [order]
                     (if delete-runs?
                       (into [] (remove run-ids) order)
                       order))))))

(defn- result-domain
  [result]
  (let [inner (:receipt result)]
    (cond
      (map? (:result inner)) (:result inner)
      (and (map? inner)
           (some #(contains? inner %)
                 [:snapshot :schedules :runs :health :schedule :run
                  :device-zone :changes :missed]))
      inner
      :else result)))

(defn- close-form
  [state]
  (-> state
      (assoc-in [:ui :form] nil)
      (assoc-in [:ui :form-errors] {})
      (assoc-in [:ui :form-submitting?] false)))

(defn- apply-read-receipt
  [state kind domain result]
  (case kind
    :scheduler/snapshot
    (let [snapshot (or (:snapshot domain)
                       (:snapshot result)
                       (when (contains? domain :schedules) domain))]
      (if (map? snapshot)
        (let [{:keys [state effects]}
              (maybe-sync-zone (apply-snapshot state snapshot))
              refresh-pending? (get-in state [:ui :refresh-pending?])
              state (assoc-in state [:ui :refresh-pending?] false)]
          (if refresh-pending?
            (let [queued (queue-refresh state :queued-refresh)]
              {:state (:state queued)
               :effects (into (vec effects) (:effects queued))})
            {:state state :effects effects}))
        (let [{:keys [state effects]}
              (queue-refresh state :snapshot-missing)]
          {:state (notice state :warn
                          "服务器回执没有携带调度器状态；未把它当作空列表。")
           :effects effects})))

    :scheduler/schedules
    (let [schedules (or (:schedules domain) (:schedules result))]
      (if (map? schedules)
        {:state (apply-schedules state schedules) :effects []}
        {:state (notice state :warn "服务器回执没有携带任务列表。") :effects []}))

    :scheduler/runs
    (let [runs (or (:runs domain) (:runs result))]
      (if (sequential? runs)
        {:state (apply-runs state runs) :effects []}
        {:state (notice state :warn "服务器回执没有携带运行记录。") :effects []}))

    :scheduler/health
    (let [health (or (:health domain) (:health result))]
      {:state (assoc-in state [:server :health] health) :effects []})

    {:state state :effects []}))

(defn- apply-success
  [state command result]
  (let [kind (:kind command)
        domain (result-domain result)
        payload (:payload command)]
    (case kind
      (:scheduler/snapshot :scheduler/schedules :scheduler/runs :scheduler/health)
      (apply-read-receipt state kind domain result)

      :scheduler/create
      (if-let [schedule (:schedule domain)]
        (let [id (:id schedule)
              enabled? (:enabled? domain)
              enable-error (:enable-error domain)
              state (-> state
                        (upsert-schedule schedule)
                        close-form
                        (assoc-in [:ui :view] :list)
                        (assoc-in [:ui :selected-id] nil)
                        (assoc-in [:ui :highlight-id] id))
              state (cond
                      (and enabled? (not enable-error))
                      (notice state :ok "任务已创建并启用。")

                      enable-error
                      (notice state :warn
                              (str "任务已保存为暂停：" (error-message
                                                         {:error enable-error})))

                      (false? enabled?)
                      (notice state :info "任务已保存为暂停。")

                      :else
                      (notice state :warn
                              "服务器未确认启用状态；请核对任务详情，不会把未知当作已启用。"))]
          {:state state :effects []})
        (let [state (assoc-in state [:ui :form-submitting?] false)
              {:keys [state effects]} (queue-refresh state :create-missing-schedule)]
          {:state (notice state :warn
                          "服务器未返回新任务；已请求重新读取调度器状态。")
           :effects effects}))

      :scheduler/update
      (if-let [schedule (:schedule domain)]
        {:state (-> state
                    (upsert-schedule schedule)
                    close-form
                    (assoc-in [:ui :view] :detail)
                    (assoc-in [:ui :selected-id] (:id schedule))
                    (notice :ok "任务已更新。"))
         :effects []}
        (let [state (assoc-in state [:ui :form-submitting?] false)
              {:keys [state effects]} (queue-refresh state :update-missing-schedule)]
          {:state (notice state :warn
                          "服务器未返回更新后的任务；已请求重新读取。")
           :effects effects}))

      (:scheduler/enable :scheduler/pause :scheduler/resume :scheduler/repair)
      (let [schedule (:schedule domain)
            state (if schedule (upsert-schedule state schedule) state)
            text (case kind
                   :scheduler/enable "任务已启用。"
                   :scheduler/pause "任务已暂停；正在运行的任务不会被取消，可在运行记录中单独停止。"
                   :scheduler/resume "任务已恢复；暂停期间错过的计划记为未运行，不会补跑。"
                   :scheduler/repair "任务已修复并重新启用。")]
        (if schedule
          {:state (notice state :ok text) :effects []}
          (let [{:keys [state effects]}
                (queue-refresh state :mutation-missing-schedule)]
            {:state (notice state :warn
                            "服务器未返回任务状态；已请求重新读取，不把未知当作成功。")
             :effects effects})))

      :scheduler/delete
      (let [schedule-id (:schedule-id payload)
            opts (:opts payload)
            delete-runs? (boolean (:delete-runs? opts))
            stopped-run (:stopped-run domain)
            state (-> state
                      (cond-> stopped-run (upsert-run stopped-run))
                      (remove-schedule schedule-id delete-runs?)
                      (assoc-in [:ui :view] :list)
                      (assoc-in [:ui :selected-id] nil)
                      (assoc-in [:ui :delete-confirm] nil)
                      (notice :ok (if stopped-run
                                    "任务已删除；正在进行的运行记录为已停止。"
                                    "任务已删除。")))]
        {:state state :effects []})

      :scheduler/manual-run
      (let [run (:run domain)
            schedule (:schedule domain)
            outcome (keywordize (:outcome domain))
            reason (keywordize (:reason domain))
            issues (:issues domain)
            state (cond-> state
                    run (upsert-run run)
                    schedule (upsert-schedule schedule))
            state (case outcome
                    :started
                    (if run
                      (notice state :ok
                              "已开始手动运行；它是一条独立记录，不改变原定下次时间。")
                      (notice state :warn
                              "服务器未返回运行记录；已请求重新读取，不当作已开始。"))

                    :skipped
                    (notice state :warn
                            (str "已跳过："
                                 (get reason-labels reason
                                      "上次运行仍在进行")))

                    :needs-attention
                    (notice state :warn
                            (str "需要处理："
                                 (or (some-> issues first :message)
                                     "运行前检查未通过")
                                 (when-let [cause (get-in issues [0 :details :cause])]
                                   (str "（" cause "）"))))

                    (notice state :info "服务器已受理手动运行请求。"))]
            {:state state :effects [{:effect :refresh
                                     :reason :manual-run}]})

      :scheduler/set-device-zone
      (let [zone (or (:device-zone domain) (:zone payload))
            changes (:changes domain)
            missed (:missed domain)
            state (-> state
                      (assoc-in [:server :device-zone] zone)
                      (assoc-in [:ui :zone-sync-pending?] false))
            state (if (= :follow-device
                        (get-in state [:ui :form :timezone :mode]))
                    (assoc-in state [:ui :form :timezone :zone] zone)
                    state)
            state (reduce
                   (fn [s change]
                     (if-let [schedule (get-in s [:server :schedules
                                                  (:schedule-id change)])]
                       (assoc-in s [:server :schedules (:schedule-id change)]
                                 (cond-> schedule
                                   (:new-zone change)
                                   (assoc-in [:timezone :zone] (:new-zone change))
                                   (contains? change :new-next-run)
                                   (assoc :next-run (:new-next-run change))))
                       s))
                   state
                   changes)
            text (cond
                   (seq changes)
                   (str "时区已变更为 " zone "；" (count changes)
                        " 个跟随设备任务已重新计算下次运行。")
                   (seq missed)
                   (str "时区已变更为 " zone "；错过的计划记为未运行，不会补跑。")
                   :else
                   (str "设备时区已更新为 " zone "；固定时区任务保持不变。"))]
        {:state (notice state :info text)
         :effects [{:effect :refresh :reason :device-zone}]})

      :scheduler/preview
      {:state (assoc-in state [:ui :server-preview] (:preview domain))
       :effects []}

      :scheduler/tick
      {:state (notice state :info "已请求调度器立即检查到期任务。")
       :effects [{:effect :refresh :reason :tick}]}

      ;; A conversation :stop-run receipt is handled by the conversation
      ;; surface.  The scheduler run closes when the Owner reports a terminal
      ;; state; do not fake a terminal run here.
      :stop-run
      {:state (notice state :info "已请求停止当前运行；等待服务器确认终态。")
       :effects [{:effect :refresh :reason :stop-requested}]}

      (let [{:keys [state effects]} (queue-refresh state :unknown-receipt)]
        {:state (notice state :warn "收到未知命令的回执；已请求重新读取。")
         :effects effects}))))

(defn apply-receipt
  "Apply one command receipt.  A receipt never clears an unsaved form."
  [state receipt]
  (let [{:keys [state command receipt]} (commands/record-receipt state receipt)]
    (if (nil? command)
      {:state (notice state :warn
                      "收到未知命令的回执；未改变任何任务或草稿。")
       :effects []}
      (if (result-ok? receipt)
        (apply-success state command receipt)
        (if (result-unknown? receipt)
          (let [state (-> state
                          (assoc-in [:ui :form-submitting?] false)
                          (assoc-in [:ui :delete-confirm] nil)
                          (assoc-in [:ui :stop-confirm] nil)
                          (notice :warn
                                  "提交结果未知；表单与已确认状态保留。请先核对目标状态，不要自动重发。"))]
            {:state state
             :effects [{:effect :refresh :reason :outcome-unknown}]})
        (let [state (if (= :scheduler/snapshot (:kind command))
                      (-> state
                          (assoc-in [:server :loaded?] true)
                          (assoc-in [:server :load-error]
                                    (str "调度器状态读取失败："
                                         (error-message receipt))))
                      state)
              state (-> state
                        (assoc-in [:ui :form-submitting?] false)
                        (assoc-in [:ui :delete-confirm] nil)
                        (assoc-in [:ui :stop-confirm] nil)
                        (assoc-in [:ui :zone-sync-pending?] false)
                        (notice :danger
                                (str "命令未完成：" (error-message receipt))))]
          {:state state :effects []}))))))

;; ---------------------------------------------------------------------------
;; Actions

(defn- update-form
  [state path value]
  (-> state
      (update-in (into [:ui :form] path) (constantly value))
      (assoc-in [:ui :form :dirty?] true)
      (update-in [:ui :form-errors] dissoc (first path))))

(defn- parse-days
  [value]
  (->> (if (sequential? value) value [value])
       (map parse-int)
       (filter #(and (integer? %) (<= 1 % 7)))
       distinct
       sort
       vec))

(defn- parse-model-value
  [value]
  (when (and (string? value) (not (str/blank? value)))
    (let [[connection-id model-entry-id] (str/split value #"\|" 2)]
      (when (and (non-blank-string? connection-id)
                 (non-blank-string? model-entry-id))
        {:connection-id connection-id
         :model-entry-id model-entry-id
         :label model-entry-id
         :source :form}))))

(defn- normalize-change
  [path value]
  (let [[root & more] path]
    (cond
      (and (= root :recurrence) (= more [:time :hour])) (parse-int value)
      (and (= root :recurrence) (= more [:time :minute])) (parse-int value)
      (and (= root :recurrence) (= more [:days])) (parse-days value)
      (and (= root :recurrence) (= more [:kind])) (keywordize value)
      (and (= root :timezone) (= more [:mode])) (keywordize value)
      (and (= root :execution) (= more [:mode])) (keywordize value)
      (and (= root :execution) (= more [:redo-mode])) (keywordize value)
      (= root :model) (parse-model-value value)
      (= root :save-mode) (keywordize value)
      :else value)))

(defn- start-command
  [state kind payload notice-text]
  (let [{:keys [state effects]} (queue-command state kind payload)]
    {:state (cond-> state
              (and (seq effects)
                   (contains? #{:scheduler/create :scheduler/update} kind))
              (assoc-in [:ui :form-submitting?] true)
              (and notice-text (seq effects))
              (notice :info notice-text))
     :effects effects}))

(defn- manual-run-summary
  [state schedule]
  {:name (:name schedule)
   :model (model-label state schedule)
   :execution (execution-label schedule)
   :destination (or (some-> (:output schedule) pr-str)
                    (result-destination-label
                     (or (:execution schedule)
                         (get-in schedule [:snapshot :execution]))))
   :next-run (:next-run schedule)
   :zone (get-in schedule [:timezone :zone])})

(defn reduce-action
  "Apply one UI action.  Returns `{:state state' :effects effects}`."
  [state action]
  (let [type (keywordize (:type action))
        state (assoc-in state [:ui :now] (or (:now action) (now-ms state)))]
    (case type
      :schedule/set-sync
      (let [sync {:phase (keywordize (:phase action))
                  :command-epoch (:command-epoch action)
                  :epoch (:epoch action)}
            state (assoc state :sync sync)
            offline? (contains? #{:disconnected :interrupted} (:phase sync))
            pending? (some (fn [[_ command]]
                             (contains? #{:queued :submitting}
                                        (:status command)))
                           (:commands state))
            state (if offline?
                    (-> state
                        (assoc-in [:ui :form-submitting?] false)
                        (assoc-in [:ui :zone-sync-pending?] false)
                        (assoc-in [:ui :refresh-pending?] false)
                        (update :commands
                                (fn [commands]
                                  (into {}
                                        (map (fn [[id command]]
                                               [id (if (contains? #{:queued
                                                                    :submitting}
                                                                  (:status command))
                                                     (assoc command
                                                            :status :outcome-unknown)
                                                     command)]))
                                        commands))))
                    state)
            state (if (and offline? pending?)
                    (notice state :warn
                            "连接中断；待确认命令的结果未知，表单已保留。请先核对再重试。")
                    state)]
        (if (and (= :synced (:phase sync))
                 (not (get-in state [:server :loaded?]))
                 (not (commands/pending-command-for
                       state [:scheduler/snapshot :global])))
          (queue-refresh state :initial-sync)
          {:state state :effects []}))

      :schedule/set-platform
      {:state (assoc state :platform (platform-policy
                                      (keywordize (:platform action))))
       :effects []}

      :schedule/set-browser-zone
      (let [zone (:zone action)
            state (assoc-in state [:ui :browser-zone] zone)
            state (if (= :follow-device
                        (get-in state [:ui :form :timezone :mode]))
                    (assoc-in state [:ui :form :timezone :zone] zone)
                    state)]
        (if (get-in state [:server :loaded?])
          (maybe-sync-zone state)
          {:state state :effects []}))

      :schedule/set-catalog
      {:state (assoc state :catalog (normalize-catalog (:catalog action)))
       :effects []}

      :schedule/open
      (let [state (assoc-in state [:ui :view] :list)]
        (if (or (:refresh? action)
                (not (get-in state [:server :loaded?])))
          (queue-refresh state :open)
          {:state state :effects []}))

      :schedule/refresh
      (queue-refresh state (or (:reason action) :manual))

      :schedule/new
      {:state (-> state
                  (assoc-in [:ui :form] (new-form state))
                  (assoc-in [:ui :form-errors] {})
                  (assoc-in [:ui :view] :form)
                  (assoc-in [:ui :selected-id] nil))
       :effects []}

      :schedule/edit
      (if-let [schedule (get-in state [:server :schedules
                                      (or (:id action)
                                          (get-in state [:ui :selected-id]))])]
        {:state (-> state
                    (assoc-in [:ui :form] (form-from-schedule state schedule))
                    (assoc-in [:ui :form-errors] {})
                    (assoc-in [:ui :view] :form))
         :effects []}
        {:state (notice state :warn "找不到要编辑的任务。") :effects []})

      :schedule/select
      {:state (-> state
                  (assoc-in [:ui :selected-id] (:id action))
                  (assoc-in [:ui :view] :detail)
                  (assoc-in [:ui :form] nil)
                  (assoc-in [:ui :form-errors] {}))
       :effects []}

      :schedule/back
      {:state (-> state
                  (assoc-in [:ui :view] :list)
                  (assoc-in [:ui :selected-id] nil)
                  (assoc-in [:ui :form] nil)
                  (assoc-in [:ui :form-errors] {}))
       :effects []}

      :schedule/cancel-form
      (let [form (get-in state [:ui :form])
            state (close-form state)]
        {:state (if (and form (= :edit (:mode form)))
                  (assoc-in state [:ui :view] :detail)
                  (assoc-in state [:ui :view] :list))
         :effects []})

      :schedule/change
      (if (get-in state [:ui :form])
        (let [path (:path action)
              value (normalize-change path (:value action))]
          {:state (if (= [:model] path)
                    (-> state
                        (assoc-in [:ui :form :model] value)
                        (assoc-in [:ui :form :model-mode]
                                  (if value :fixed :follow-assistant))
                        (assoc-in [:ui :form :dirty?] true)
                        (update-in [:ui :form-errors] dissoc :model))
                    (update-form state path value))
           :effects []})
        {:state state :effects []})

      :schedule/save-mode
      {:state (assoc-in state [:ui :form :save-mode]
                        (keywordize (or (:value action) (:mode action))))
       :effects []}

      :schedule/toggle-day
      (if (get-in state [:ui :form])
        (let [day (parse-int (:day action))
              days (set (map parse-int
                             (get-in state [:ui :form :recurrence :days])))
              days' (if (contains? days day) (disj days day) (conj days day))]
          {:state (-> state
                      (assoc-in [:ui :form :recurrence :days]
                                (vec (sort days')))
                      (assoc-in [:ui :form :dirty?] true))
           :effects []})
        {:state state :effects []})

      :schedule/new-example
      {:state (-> state
                  (assoc-in [:ui :form]
                            (assoc (new-form state)
                                   :name "每日摘要"
                                   :prompt "总结今天的会话与待办，并列出明天最重要的一件事。"
                                   :recurrence {:kind :daily
                                                :time {:hour 8 :minute 30}
                                                :days [1 3]}
                                   :save-mode :paused
                                   :dirty? true))
                  (assoc-in [:ui :form-errors] {})
                  (assoc-in [:ui :view] :form)
                  (assoc-in [:ui :selected-id] nil))
       :effects []}

      :schedule/save
      (let [form (get-in state [:ui :form])
            validation (validate-form state form)]
        (if-not (:ok? validation)
          {:state (-> state
                      (assoc-in [:ui :form-errors] (:errors validation))
                      (notice :warn "请先修正表单中的必填项。"))
           :effects []}
          (if (and (= :enable (:save-mode form))
                   (:enable-blocked? validation))
            {:state (-> state
                        (assoc-in [:ui :form-errors]
                                  {:save-mode (:enable-block-reason validation)})
                        (notice :warn (:enable-block-reason validation)))
             :effects []}
            (if (= :edit (:mode form))
              (start-command state
                             :scheduler/update
                             {:schedule-id (:id form)
                              :changes (form->changes state form)}
                             "正在保存任务修改…")
              (start-command state
                             :scheduler/create
                             {:schedule (form->create-spec state form)}
                             "正在保存任务…")))))

      :schedule/pause
      (start-command state :scheduler/pause
                     {:schedule-id (:id action)}
                     "正在暂停任务…")

      :schedule/resume
      (start-command state :scheduler/resume
                     {:schedule-id (:id action)}
                     "正在恢复任务…")

      :schedule/enable
      (start-command state :scheduler/enable
                     {:schedule-id (:id action)}
                     "正在启用任务…")

      :schedule/repair
      (start-command state :scheduler/repair
                     {:schedule-id (:id action)}
                     "正在修复任务…")

      :schedule/manual-run
      (start-command state :scheduler/manual-run
                     {:schedule-id (:id action)
                      :opts {}}
                     "正在请求立即运行…")

      :schedule/request-manual-run
      (let [id (or (:id action)
                   (:schedule-id action)
                   (get-in state [:ui :selected-id]))
            schedule (get-in state [:server :schedules id])
            retry? (boolean (:retry? action))
            run (when (:run-id action)
                  (get-in state [:server :runs (:run-id action)]))
            attempt (when retry?
                      (inc (run-attempt run)))]
        (if schedule
          {:state (assoc-in state [:ui :manual-confirm]
                            (cond-> {:schedule-id id
                                     :retry? retry?
                                     :summary (manual-run-summary state schedule)}
                              retry? (assoc :attempt attempt)))
           :effects []}
          {:state (notice state :warn "找不到要立即运行的任务。")
           :effects []}))

      :schedule/manual-run-cancel
      {:state (assoc-in state [:ui :manual-confirm] nil)
       :effects []}

      :schedule/confirm-manual-run
      (let [confirm (get-in state [:ui :manual-confirm])]
        (if (nil? confirm)
          {:state state :effects []}
          (let [opts (cond-> {}
                       (:retry? confirm) (assoc :attempt (:attempt confirm)))
                result (start-command
                        state
                        :scheduler/manual-run
                        {:schedule-id (:schedule-id confirm)
                         :opts opts}
                        (if (:retry? confirm)
                          "正在请求重试…"
                          "正在请求立即运行…"))]
            (if (seq (:effects result))
              (update result :state assoc-in [:ui :manual-confirm] nil)
              result))))

      :schedule/delete-request
      {:state (assoc-in state [:ui :delete-confirm]
                        {:id (:id action)
                         :delete-runs? false
                         :force? false})
       :effects []}

      :schedule/delete-option
      {:state (update-in state [:ui :delete-confirm (:key action)]
                         (constantly (:value action)))
       :effects []}

      :schedule/delete-cancel
      {:state (assoc-in state [:ui :delete-confirm] nil) :effects []}

      :schedule/delete-confirm
      (let [confirm (get-in state [:ui :delete-confirm])
            schedule (get-in state [:server :schedules (:id confirm)])
            active (active-run state (:id confirm))]
        (cond
          (nil? confirm)
          {:state state :effects []}

          (and active (not (:force? confirm)))
          {:state (notice state :warn
                          "任务仍在运行；先停止当前运行，或勾选“停止并删除”。")
           :effects []}

          :else
          (start-command state
                         :scheduler/delete
                         {:schedule-id (:id confirm)
                          :opts {:delete-runs? (boolean (:delete-runs? confirm))
                                 :force? (boolean (:force? confirm))}}
                         (str "正在删除任务“" (:name schedule) "”…"))))

      :schedule/stop-request
      {:state (assoc-in state [:ui :stop-confirm] {:run-id (:run-id action)})
       :effects []}

      :schedule/stop-cancel
      {:state (assoc-in state [:ui :stop-confirm] nil) :effects []}

      :schedule/stop-confirm
      (let [run-id (get-in state [:ui :stop-confirm :run-id])
            run (get-in state [:server :runs run-id])
            chat-id (:chat-id run)]
        (if-not (non-blank-string? (str chat-id))
          {:state (-> state
                      (assoc-in [:ui :stop-confirm] nil)
                      (notice :warn
                              "该运行还没有可停止的对话；请等待服务器创建结果会话。"))
           :effects []}
          (let [{:keys [state effects]}
                (queue-command state
                               :stop-run
                               {:session-id chat-id
                                :reason :user-cancel}
                               {:reason :schedule-stop})]
            {:state (-> state
                        (assoc-in [:ui :stop-confirm] nil)
                        (notice :info "已请求停止当前运行；等待服务器确认终态。"))
             :effects effects})))

      :schedule/dismiss-notice
      {:state (assoc-in state [:ui :notice] nil) :effects []}

      :schedule/apply-snapshot
      (let [state (apply-snapshot state (:snapshot action))]
        (maybe-sync-zone state))

      :schedule/apply-receipt
      (apply-receipt state (:receipt action))

      :schedule/notification
      (let [notification (:notification action)
            schedule (get-in state [:server :schedules
                                    (:schedule-id notification)])
            name (or (:schedule-name notification)
                     (:name schedule))
            status (keywordize (:status notification))
            reason (keywordize (:reason notification))
            label (let [status-label (localized-label run-status-labels
                                                      status
                                                      "状态更新")]
                    (if reason
                      (str status-label
                           "："
                           (localized-label reason-labels
                                            reason
                                            "未知原因"))
                      status-label))
            text (str "定时任务"
                      (when name (str "“" name "”"))
                      "："
                      label)]
        {:state (notice state
                        (if (contains? #{:failed :needs-attention
                                         :outcome-unknown :missed :skipped
                                         :timed-out :interrupted}
                                       status)
                          :warn :info)
                        text)
         :effects [{:effect :refresh :reason :notification}]})

      :schedule/restore-form
      (let [form (:form action)]
        (if (map? form)
          {:state (-> state
                      (assoc-in [:ui :form] form)
                      (assoc-in [:ui :view] :form))
           :effects []}
          {:state state :effects []}))

      :schedule/mark-load-error
      {:state (mark-load-error state (:message action)) :effects []}

      {:state state :effects []})))


;; ---------------------------------------------------------------------------
;; Local form draft persistence

(defn export-form
  "Return a serializable form draft, or nil when the form is clean."
  [state]
  (let [form (get-in state [:ui :form])]
    (when (and form (:dirty? form))
      {:version 1
       :form form
       :saved-at (now-ms state)})))

(defn import-form
  "Restore a previously exported form draft.  A malformed payload is ignored."
  [state payload]
  (let [form (:form payload)]
    (if (and (= 1 (:version payload))
             (map? form)
             (contains? #{:create :edit} (:mode form))
             (string? (:name form))
             (string? (:prompt form))
             (map? (:execution form))
             (map? (:recurrence form))
             (map? (:timezone form)))
      (assoc-in state [:ui :form] form)
      state)))
