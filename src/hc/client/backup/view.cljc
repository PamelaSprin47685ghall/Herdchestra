(ns hc.client.backup.view
  "Pure hiccup-style tree for the encrypted backup and recovery workspace.

  The tree carries `:events` descriptors instead of functions.  The React
  adapter resolves them, so JVM tests can inspect every label and warning and
  the same tree renders in the browser."
  (:require [clojure.string :as str]
            [hc.client.backup.model :as model]))


(def backup-css
  ".hc-backup {
     --hc-backup-fg: #161616;
     --hc-backup-muted: #4a4a4a;
     --hc-backup-bg: #ffffff;
     --hc-backup-surface: #f7f7f7;
     --hc-backup-border: #b5b5b5;
     --hc-backup-accent: #0b57d0;
     --hc-backup-positive: #146c2e;
     --hc-backup-warning: #7a3e00;
     --hc-backup-danger: #a4000f;
     color: var(--hc-backup-fg);
     background: var(--hc-backup-bg);
     overflow-wrap: anywhere;
   }
   @media (prefers-color-scheme: dark) {
     html[data-theme=\"auto\"] .hc-backup,
     html[data-theme=\"system\"] .hc-backup {
       --hc-backup-fg: #f2f2f2;
       --hc-backup-muted: #c9c9c9;
       --hc-backup-bg: #121212;
       --hc-backup-surface: #1e1e1e;
       --hc-backup-border: #5a5a5a;
       --hc-backup-accent: #9ec1ff;
       --hc-backup-positive: #7ddc9a;
       --hc-backup-warning: #ffd479;
       --hc-backup-danger: #ff8a8a;
     }
     html[data-theme=\"auto\"] .hc-backup-button--primary,
     html[data-theme=\"system\"] .hc-backup-button--primary { color: #0b1b33; }
   }
   html[data-theme=\"dark\"] .hc-backup {
     --hc-backup-fg: #f2f2f2;
     --hc-backup-muted: #c9c9c9;
     --hc-backup-bg: #121212;
     --hc-backup-surface: #1e1e1e;
     --hc-backup-border: #5a5a5a;
     --hc-backup-accent: #9ec1ff;
     --hc-backup-positive: #7ddc9a;
     --hc-backup-warning: #ffd479;
     --hc-backup-danger: #ff8a8a;
   }
   html[data-theme=\"dark\"] .hc-backup-button--primary { color: #0b1b33; }
   .hc-backup h2, .hc-backup h3, .hc-backup h4 { margin: .6rem 0 .4rem; }
   .hc-backup p { margin: .35rem 0 .6rem; }
   .hc-backup__header { margin-bottom: .8rem; }
   .hc-backup-muted, .hc-backup-note, .hc-backup-field__hint {
     color: var(--hc-backup-muted);
     font-size: .88rem;
   }
   .hc-backup-sync {
     display: flex; flex-wrap: wrap; gap: .4rem .8rem;
     border: 1px solid var(--hc-backup-border); border-radius: .5rem;
     background: var(--hc-backup-surface); padding: .6rem .75rem;
   }
   .hc-backup-tabs {
     display: flex; flex-wrap: wrap; gap: .35rem;
     border-bottom: 1px solid var(--hc-backup-border);
     margin: .8rem 0 1rem;
   }
   .hc-backup-tab, .hc-backup-button, .hc-backup-choice {
     min-height: 44px; min-width: 44px;
     border: 1px solid var(--hc-backup-border); border-radius: .45rem;
     background: var(--hc-backup-surface); color: var(--hc-backup-fg);
     padding: .5rem .8rem; font: inherit; cursor: pointer;
   }
   .hc-backup-tab--active { border-color: var(--hc-backup-accent);
     box-shadow: inset 0 -3px 0 var(--hc-backup-accent); font-weight: 700; }
   .hc-backup-button--primary { background: var(--hc-backup-accent); color: #fff; }
   .hc-backup-button--danger { border-color: var(--hc-backup-danger); color: var(--hc-backup-danger); }
   .hc-backup-button--quiet { background: transparent; }
   .hc-backup-tab:disabled, .hc-backup-button:disabled, .hc-backup-choice:disabled {
     opacity: .62; cursor: not-allowed;
   }
   .hc-backup-tab:focus-visible, .hc-backup-button:focus-visible,
   .hc-backup-choice:focus-visible, .hc-backup input:focus-visible {
     outline: 3px solid var(--hc-backup-accent); outline-offset: 2px;
   }
   .hc-backup-panel, .hc-backup-target {
     border: 1px solid var(--hc-backup-border); border-radius: .55rem;
     background: var(--hc-backup-surface); padding: .75rem 1rem;
     margin: .8rem 0;
   }
   .hc-backup-facts { display: grid;
     grid-template-columns: repeat(auto-fit, minmax(12rem, 1fr));
     gap: .5rem 1rem; margin: .5rem 0; }
   .hc-backup-fact dt { font-weight: 700; color: var(--hc-backup-muted); font-size: .8rem; }
   .hc-backup-fact dd { margin: 0; }
   .hc-backup-field { display: grid; gap: .3rem; margin: .6rem 0; }
   .hc-backup-field__label { font-weight: 700; }
   .hc-backup-input {
     min-height: 44px; width: 100%; box-sizing: border-box;
     border: 1px solid var(--hc-backup-border); border-radius: .4rem;
     background: var(--hc-backup-bg); color: var(--hc-backup-fg);
     padding: .45rem .6rem; font: inherit;
   }
   .hc-backup-check, .hc-backup-radio, .hc-backup-target-choice {
     display: flex; align-items: flex-start; gap: .55rem;
     min-height: 44px; padding: .35rem .2rem;
   }
   .hc-backup-check input, .hc-backup-radio input,
   .hc-backup-target-choice input, .hc-backup-category input {
     width: 1.25rem; height: 1.25rem; margin-top: .15rem;
   }
   .hc-backup-categories { border: 1px solid var(--hc-backup-border);
     border-radius: .55rem; padding: .6rem .8rem; margin: .8rem 0; }
   .hc-backup-category-grid { display: grid;
     grid-template-columns: repeat(auto-fit, minmax(15rem, 1fr)); gap: .3rem .9rem; }
   .hc-backup-category { display: flex; align-items: flex-start; gap: .55rem;
     min-height: 44px; padding: .35rem .2rem; }
   .hc-backup-category small, .hc-backup-radio small, .hc-backup-target-choice small {
     display: block; color: var(--hc-backup-muted); font-size: .82rem;
   }
   .hc-backup-actions, .hc-backup-submit, .hc-backup-category-actions {
     display: flex; flex-wrap: wrap; gap: .5rem; align-items: center; margin: .6rem 0;
   }
   .hc-backup-warning { border-left: 4px solid var(--hc-backup-warning);
     background: color-mix(in srgb, var(--hc-backup-warning) 12%, transparent);
     padding: .45rem .65rem; }
   .hc-backup-warning--error { border-left-color: var(--hc-backup-danger);
     background: color-mix(in srgb, var(--hc-backup-danger) 12%, transparent); }
   .hc-backup-message { border: 1px solid var(--hc-backup-border);
     border-left-width: 5px; border-radius: .45rem; padding: .55rem .7rem;
     margin: .6rem 0; display: flex; flex-wrap: wrap; gap: .4rem .8rem;
     align-items: center; }
   .hc-backup-message--error { border-left-color: var(--hc-backup-danger); }
   .hc-backup-message--warning { border-left-color: var(--hc-backup-warning); }
   .hc-backup-message--success { border-left-color: var(--hc-backup-positive); }
   .hc-backup-message__action { color: var(--hc-backup-muted); }
   .hc-backup-badge { display: inline-block; border: 1px solid currentColor;
     border-radius: 999px; padding: .08rem .45rem; font-size: .78rem;
     font-weight: 700; white-space: nowrap; }
   .hc-backup-badge--positive { color: var(--hc-backup-positive); }
   .hc-backup-badge--warning { color: var(--hc-backup-warning); }
   .hc-backup-badge--unknown { color: var(--hc-backup-muted); }
   .hc-backup-row { border: 1px solid var(--hc-backup-border);
     border-radius: .5rem; padding: .6rem .8rem; margin: .6rem 0;
     background: var(--hc-backup-bg); }
   .hc-backup-row__header { display: flex; flex-wrap: wrap;
     justify-content: space-between; align-items: baseline; gap: .3rem .8rem; }
   .hc-backup-row__header h4 { margin: 0; }
   .hc-backup-row--selected { border-color: var(--hc-backup-accent);
     box-shadow: 0 0 0 2px color-mix(in srgb, var(--hc-backup-accent) 35%, transparent); }
   .hc-backup-section-heading { display: flex; flex-wrap: wrap;
     justify-content: space-between; align-items: center; gap: .5rem .9rem; }
   .hc-backup-section-heading h3 { margin: .2rem 0; }
   .hc-backup-result__status { display: flex; align-items: center;
     gap: .5rem; margin: .4rem 0 .6rem; }
   .hc-backup-verify { border-left: 4px solid var(--hc-backup-positive);
     background: color-mix(in srgb, var(--hc-backup-positive) 10%, transparent);
     border-radius: .35rem; padding: .5rem .7rem; margin: .6rem 0; }
   .hc-backup-table { width: 100%; border-collapse: collapse; margin: .5rem 0;
     display: block; overflow-x: auto; }
   .hc-backup-table th, .hc-backup-table td {
     border-bottom: 1px solid var(--hc-backup-border);
     padding: .35rem .5rem; text-align: left; }
   .hc-backup-code { overflow-x: auto; max-height: 16rem; padding: .5rem;
     background: var(--hc-backup-bg); border: 1px solid var(--hc-backup-border); }
   .hc-backup-conflict { border-top: 1px solid var(--hc-backup-border);
     padding-top: .6rem; margin-top: .6rem; }
   .hc-backup-choice-row { display: flex; flex-wrap: wrap; gap: .4rem; }
   .hc-backup-choice--selected { border-color: var(--hc-backup-accent);
     box-shadow: inset 0 0 0 2px var(--hc-backup-accent); font-weight: 700; }
   .hc-backup-maintenance { border: 2px solid var(--hc-backup-danger);
     border-radius: .5rem; padding: .7rem .9rem; margin: .8rem 0;
     background: color-mix(in srgb, var(--hc-backup-danger) 9%, transparent); }
   .hc-backup-acknowledge { border: 2px solid var(--hc-backup-warning);
     border-radius: .5rem; padding: .7rem .9rem; margin: .8rem 0;
     background: color-mix(in srgb, var(--hc-backup-warning) 10%, transparent); }
   .hc-backup-confirm { border: 1px dashed var(--hc-backup-danger);
     border-radius: .45rem; padding: .6rem .8rem; margin: .5rem 0; }
   .hc-backup-target-list { list-style: none; padding: 0; margin: .3rem 0; }
   .hc-backup-target-list > li { margin: .3rem 0; }
   .hc-backup-empty { padding: .8rem; border: 1px dashed var(--hc-backup-border);
     border-radius: .5rem; color: var(--hc-backup-muted); }
   .hc-backup-list-block ul { padding-left: 1.2rem; }
   .hc-backup__footer { margin-top: 1rem; border-top: 1px solid var(--hc-backup-border); }
   @media (max-width: 480px) {
     .hc-backup-panel, .hc-backup-target { padding: .6rem .7rem; }
     .hc-backup-facts { grid-template-columns: 1fr; }
   }
   @media (prefers-reduced-motion: reduce) {
     .hc-backup * { animation: none !important; transition: none !important; }
   }")

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
       (merge {:type "button" :class "hc-backup-button"}
              attrs
              {:on-click (ev action)})
       label)))

(defn- checkbox
  [label checked? action & [attrs]]
  (el :label (merge {:class "hc-backup-check"} attrs)
      (el :input (merge {:type "checkbox"
                         :checked (boolean checked?)
                         :on-change (ev action)}))
      (el :span {} label)))

(defn- badge
  [text state]
  (el :span {:class (str "hc-backup-badge hc-backup-badge--"
                         (name (or state :unknown)))}
      text))

(defn- field
  [label control & [hint]]
  (el :label {:class "hc-backup-field"}
      (el :span {:class "hc-backup-field__label"} label)
      control
      (when hint (el :small {:class "hc-backup-field__hint"} hint))))

(defn- input
  [attrs]
  (el :input (merge {:class "hc-backup-input"} attrs)))

(defn- draft-input
  "A text input whose live DOM value is the draft authority.

  The React adapter mounts it uncontrolled.  `:value` seeds the field and is
  used only when the app deliberately resets the draft; `:reset-key` changes
  on that reset.  Typing, IME composition, caret, and selection therefore do
  not depend on a Fulcro render arriving between two keystrokes."
  [attrs]
  (input (merge {:draft-input true} attrs)))

(defn- password-input
  "One backup password field.

  Both create fields and the restore/verify field share the same in-memory
  `:ui` password.  They are draft inputs because a controlled password field
  loses characters when React restores the last committed value between fast
  keystrokes.  The reset key clears the DOM only after an explicit clear or a
  terminal receipt, never because a server snapshot re-rendered the panel."
  [ui field aria-label placeholder]
  (draft-input
   (cond-> {:type (if (true? (:password-visible? ui)) "text" "password")
            :value (str (get ui field))
            :reset-key (get ui :password-reset 0)
            :autocomplete "new-password"
            :aria-label aria-label
            :on-input (ev-value
                       (act :backup/set-field :field field))
            ;; Some browsers publish compositionend before the final input
            ;; event.  Dispatch the settled DOM text as well so the state
            ;; cannot lag behind an IME-confirmed final character.
            :on-composition-end (ev-value
                                 (act :backup/set-field :field field))}
     placeholder (assoc :placeholder placeholder))))

(defn- warning
  [text & [tone]]
  (el :p {:class (str "hc-backup-warning hc-backup-warning--"
                      (name (or tone :warning)))
          :role (when (= :error tone) "alert")}
      text))

(defn- id-label
  [id]
  (cond
    (keyword? id) (name id)
    (string? id) id
    (nil? id) nil
    :else (str id)))

(defn format-bytes
  "Render a byte count without locale-dependent formatting."
  [n]
  (when (and (some? n) (number? n))
    (let [n (long n)]
      (cond
        (< n 1024) (str n " B")
        (< n (* 1024 1024)) (str (quot (+ n 512) 1024) " KiB")
        (< n (* 1024 1024 1024)) (str (quot (+ n (* 512 1024))
                                             (* 1024 1024)) " MiB")
        :else (str (quot (+ n (* 512 1024 1024))
                         (* 1024 1024 1024)) " GiB")))))

(def permission-labels
  {:schedules "计划任务"
   :auto-backup "自动备份"
   :device-control "设备控制"
   :full-access "完全访问"
   :external-write "外部写入"
   :background-run "后台运行"
   :browser-login "浏览器登录"
   :system-authorization "系统授权"
   :skills "技能"
   :tools "工具"
   :connections "模型连接"
   :external-workspaces "外部工作区"
   :memories "记忆"
   :restored-from-backup? "来自备份"
   :implicit-authority? "隐式授权"})

(def permission-value-labels
  {:paused "暂停"
   :disabled "停用"
   :relogin-required "需要重新登录"
   :regrant-required "需要重新授权"
   :installed-needs-review "已安装，待审核"
   :disabled-needs-review "已停用，待审核"
   :needs-verification "需要重新验证"
   :relink-required "需要重新定位"
   :pending-review "待审核"})

(defn- permission-value-label
  [value]
  (cond
    (keyword? value) (get permission-value-labels value (name value))
    (true? value) "是"
    (false? value) "否"
    (nil? value) "未提供"
    (string? value) value
    (number? value) (str value)
    :else (pr-str value)))

(defn- permission-facts
  "Render the actual permission entries returned by the server.  `:source` is
  the raw snapshot, not a restored default, so it stays out of the summary."
  [permissions]
  (->> (or permissions {})
       (remove (fn [[key _]] (= :source key)))
       (sort-by (comp name first))
       (mapv (fn [[key value]]
               [(get permission-labels key (name key))
                (permission-value-label value)]))))

(defn- facts
  [pairs]
  (el :dl {:class "hc-backup-facts"}
      (mapv (fn [[label value]]
              (el :div {:class "hc-backup-fact" :key (str label)}
                  (el :dt {} label)
                  (el :dd {} (if (or (nil? value) (and (string? value)
                                                       (str/blank? value)))
                               "未提供"
                               (str value)))))
            pairs)))

(defn- list-block
  [title items empty-text]
  (el :section {:class "hc-backup-list-block"}
      (el :h3 {} title)
      (if (seq items)
        (el :ul {} (mapv (fn [item] (el :li {:key (pr-str item)} (str item)))
                         items))
        (el :p {:class "hc-backup-muted"} empty-text))))

(defn- offline-command
  [state command & [id]]
  (let [target (model/target-view state)
        selected (model/selected-target state)
        data-dir (or (:data-dir target) "<data-dir>")
        target-root (or (:local-root target) "<target-root>")
        mode (name (or (get-in state [:ui :mode]) :merge))
        backup-id (or (get-in state [:ui :selected-backup-id]) "<backup-id>")
        point-id (or id (get-in state [:ui :recovery-confirm-id])
                     "<recovery-point-id>")]
    (str "clojure -M:server -m hc.server.backup.cli " command " \\\n"
         "  --db-path <db-path> --data-dir " data-dir " \\\n"
         "  --target-root " target-root " \\\n"
         (case command
           "restore" (str "  --backup-id " backup-id " --mode " mode)
           "restore-recovery-point" (str "  --recovery-point-id " point-id)
           "")
         "\n# 密码通过 HC_BACKUP_PASSWORD 或 --password-file 提供；不要放进 argv。"
         (when (and selected (not= :file (:kind selected)))
           (str "\n# 所选目标为 " (name (:kind selected))
                "；离线 CLI 使用本地 target-root，不能直接读取该远程目标。")))))

(defn- sync-banner
  [state]
  (let [phase (get-in state [:sync :phase] :disconnected)
        reason (model/submit-reason state)
        label (case phase
                :synced "已同步"
                :connecting "正在连接"
                :connected-awaiting-sync "已连接，等待同步"
                :interrupted "连接中断；保留最后确认状态"
                :disconnected "未连接"
                "连接状态未知")]
    (el :div {:class (str "hc-backup-sync hc-backup-sync--" (name phase))
              :role "status"}
        (el :strong {} label)
        (el :span {} (if reason
                       reason
                       "命令通道可用；点击只表示提交，回执才表示完成。")))))

(defn- message-banner
  [state]
  (when-let [message (get-in state [:ui :message])]
    (let [kind (or (:kind message) :info)]
      (el :div {:class (str "hc-backup-message hc-backup-message--" (name kind))
                :role (if (= :error kind) "alert" "status")}
          (el :div {}
              (when-let [title (:title message)]
                (el :strong {} (str title "：")))
              (el :span {} (:text message)))
          (when-let [action (:action message)]
            (el :span {:class "hc-backup-message__action"} (str "建议：" action)))
          (button "关闭" (act :backup/dismiss-message)
                  {:class "hc-backup-button hc-backup-button--quiet"})))))

(defn- tabs
  [state]
  (let [section (get-in state [:ui :section] :backup)
        tabs [[:backup "创建备份"]
              [:restore "恢复与预览"]
              [:recovery "本地恢复点"]
              [:status "目标与状态"]]]
    (el :nav {:class "hc-backup-tabs" :aria-label "备份分区"}
        (mapv (fn [[id label]]
                (button label
                        (act :backup/select-section :section id)
                        {:key (name id)
                         :class (str "hc-backup-tab"
                                     (when (= section id) " hc-backup-tab--active"))
                         :aria-current (when (= section id) "page")}))
              tabs))))

(defn- target-summary
  [state]
  (let [target (model/target-view state)
        last-backup (:last-backup target)
        storage (:storage target)
        recovery (first (model/recovery-point-rows state))]
    (el :section {:class "hc-backup-panel hc-backup-target"}
        (el :h3 {} "备份目标")
        (if (:configured? target)
          (el :div {}
              (el :div {:class "hc-backup-target-list"
                        :role "radiogroup"
                        :aria-label "备份目标"}
                  (mapv (fn [item]
                          (el :label {:class "hc-backup-target-choice"
                                      :key (str (:id item))}
                              (el :input {:type "radio"
                                          :name "hc-backup-target"
                                          :checked (boolean (:selected? item))
                                          :on-change (ev (act :backup/select-target
                                                              :target-id (:id item)))})
                              (el :span {}
                                  (el :strong {} (str (:kind-label item)
                                                      " · " (id-label (:id item))))
                                  " "
                                  (badge (cond
                                           (not (:configured? item)) "未配置"
                                           (:available? item) "可用"
                                           :else "不可用")
                                         (if (:available? item) :positive :warning))
                                  (when (:reason item)
                                    (el :small {} (:reason item)))
                                  (when (:root item)
                                    (el :small {} (str "目录：" (:root item))))
                                  (when (:base-url item)
                                    (el :small {} (str "地址：" (:base-url item)
                                                       (when (:directory item)
                                                         (str " / " (:directory item))))))
                                  (when (:endpoint item)
                                    (el :small {} (str "端点：" (:endpoint item)
                                                       " · 桶：" (:bucket item)
                                                       (when (:prefix item)
                                                         (str " · 前缀：" (:prefix item)))))))))
                        (:targets target)))
              (facts [["已选目标" (some-> target :selected :id id-label)]
                      ["备份目录" (:local-root target)]
                      ["数据目录" (:data-dir target)]
                      ["恢复点目录" (:recovery-root target)]
                      ["最近成功备份" (or (:sampled-at last-backup)
                                          (:created-at last-backup)
                                          "尚无成功备份")]
                      ["本机恢复点" (if recovery
                                      (str (:created-at recovery)
                                           (when (number? (:bytes recovery))
                                             (str " · "
                                                  (format-bytes (:bytes recovery)))))
                                      "尚无恢复点")]
                      ["数据占用" (if (number? (:used-bytes storage))
                                    (format-bytes (:used-bytes storage))
                                    "未提供")]
                      ["可用空间" (if (number? (:free-bytes storage))
                                    (format-bytes (:free-bytes storage))
                                    "未提供")]]))
          (el :p {:class "hc-backup-muted"}
              "尚未读取到已配置的备份目标；刷新状态后再操作。"))
        (el :p {:class "hc-backup-note"}
            "目标由服务器配置；界面只展示服务器实际提供的本地文件、WebDAV 或 S3 目标，不会伪造远程写入。")
        (el :div {:class "hc-backup-actions"}
            (button "刷新状态"
                    (act :backup/refresh)
                    {:disabled (not (model/can-submit? state))})))))

(defn- category-grid
  [state scope]
  (el :fieldset {:class "hc-backup-categories"}
      (el :legend {} (if (= :restore scope)
                       "要恢复的类别"
                       "要备份的类别"))
      (el :div {:class "hc-backup-category-actions"}
          (button "全选"
                  (act :backup/set-categories :scope scope :value true)
                  {:class "hc-backup-button hc-backup-button--quiet"})
          (button "全不选"
                  (act :backup/set-categories :scope scope :value false)
                  {:class "hc-backup-button hc-backup-button--quiet"}))
      (el :div {:class "hc-backup-category-grid"}
          (mapv (fn [{:keys [category label note selected?]}]
                  (el :label {:class "hc-backup-category"
                              :key (name category)}
                      (el :input {:type "checkbox"
                                  :checked (boolean selected?)
                                  :on-change (ev-checked
                                              (act :backup/toggle-category
                                                   :scope scope
                                                   :category category))})
                      (el :span {}
                          (el :strong {} label)
                          (el :small {} note))))
                (model/category-view state scope)))))

(defn- create-options
  [state]
  (let [ui (:ui state)]
    (el :section {:class "hc-backup-panel"}
        (el :h3 {} "内容与隐私")
        (el :div {:class "hc-backup-options"}
            (checkbox "包含模型密钥与认证信息（默认不勾选）"
                      (:include-secrets? ui)
                      (act :backup/toggle-field :field :include-secrets?))
            (checkbox "包含已删除的会话（进入已删除区）"
                      (:include-deleted? ui)
                      (act :backup/toggle-field :field :include-deleted?))
            (checkbox "包含临时会话"
                      (:include-temp? ui)
                      (act :backup/toggle-field :field :include-temp?))
            (checkbox "包含可重建缓存"
                      (:include-cache? ui)
                      (act :backup/toggle-field :field :include-cache?))
            (checkbox "包含外部工作区文件（只恢复配置时不要勾选）"
                      (:include-external-files? ui)
                      (act :backup/toggle-field :field :include-external-files?)))
        (when (:include-secrets? ui)
          (warning "密钥默认不勾选。勾选后密钥会进入备份；如果同时关闭加密，需要再次确认风险。"))
        (el :p {:class "hc-backup-note"}
            "登录会话与系统授权永远不会导出；它们必须在新设备重新建立。"))))

(defn- encryption-panel
  [state]
  (let [ui (:ui state)
        encrypt? (not (false? (:encrypt? ui)))
        check (model/password-check (:password ui) (:password-confirm ui))
        n (model/code-point-count (:password ui))]
    (el :section {:class "hc-backup-panel"}
        (el :h3 {} "密码与加密")
        (checkbox "默认开启密码加密"
                  encrypt?
                  (act :backup/toggle-field :field :encrypt?))
        (when encrypt?
          (el :div {:class "hc-backup-password"}
              (field "备份密码"
                     (password-input
                      ui :password "备份密码"
                      (str "至少 " model/min-password-length " 个字符"))
                     (str "当前 " n " 个字符；支持中文与空格，首尾空格不会被去掉。"))
              (field "再次输入密码"
                     (password-input
                      ui :password-confirm "再次输入备份密码" nil))
              (when (and (pos? n) (not (:ok? check)))
                (warning (:message check) :error))
              (el :p {:class "hc-backup-note"}
                  "密码只保留在本次页面内存中，不写入本地存储，也不会长期记住。")))
        (when-not encrypt?
          (warning "关闭加密会把内容写成未加密文件；包含密钥时还需要额外确认。"
                   :error))
        (when (and (not encrypt?) (:include-secrets? ui))
          (checkbox "我确认这份未加密备份包含密钥"
                    (:confirm-plaintext-secrets? ui)
                    (act :backup/toggle-field
                         :field :confirm-plaintext-secrets?)))
        (el :div {:class "hc-backup-actions"}
            (button (if (:password-visible? ui) "隐藏密码" "显示密码")
                    (act :backup/toggle-field :field :password-visible?)
                    {:class "hc-backup-button hc-backup-button--quiet"})
            (button "复制密码"
                    (act :backup/copy-password)
                    {:class "hc-backup-button hc-backup-button--quiet"
                     :disabled (empty? (str (:password ui)))})
            (button "清除密码"
                    (act :backup/clear-password)
                    {:class "hc-backup-button hc-backup-button--quiet"
                     :disabled (and (empty? (str (:password ui)))
                                    (empty? (str (:password-confirm ui))))})))))

(defn- create-result-panel
  [state]
  (when-let [result (model/create-result-view state)]
    (el :section {:class "hc-backup-panel hc-backup-result"}
        (el :h3 {} "最近一次备份")
        (el :p {:class (str "hc-backup-result__status hc-backup-result__status--"
                            (name (or (:status result) :unknown)))}
            (badge (:status-label result) (if (= :complete (:status result))
                                            :positive
                                            :warning)))
        (facts [["备份 ID" (:id result)]
                ["取样时间" (:sampled-at result)]
                ["创建时间" (:created-at result)]
                ["设备" (:device result)]
                ["加密" (if (:encrypted? result) "是" "否")]
                ["大小" (format-bytes (:size result))]
                ["位置" (:path result)]
                ["目标" (or (:target result) "未提供")]])
        (when-let [verification-error (get-in result [:verification :error])]
          (warning (str "备份校验错误："
                        (or (:message verification-error) "未提供原因"))
                   :error))
        (when-let [categories (:categories result)]
          (el :table {:class "hc-backup-table"}
              (el :thead {} (el :tr {} (el :th {} "类别") (el :th {} "记录数")))
              (el :tbody {}
                  (mapv (fn [category]
                          (el :tr {:key (name category)}
                              (el :td {} (get model/category-labels category
                                              (name category)))
                              (el :td {} (str (get-in categories [category :count]
                                                      "未提供")))))
                        model/category-order))))
        (when-let [attachments (:attachments result)]
          (el :p {:class "hc-backup-muted"}
              (str "附件：" (or (:count attachments) "未提供")
                   " 个；" (or (:bytes attachments) "未提供") " 字节。")))
        (when (seq (:incomplete-attachments result))
          (warning (str "有 " (count (:incomplete-attachments result))
                        " 个附件没有打包；备份中已移除这些悬空引用。")
                   :error)
          (list-block "未打包附件" (:incomplete-attachments result) "无"))
        (when (seq (:missing-references result))
          (list-block "缺失引用" (:missing-references result) "无"))
        (when (seq (:exclusions result))
          (list-block "按选项排除的内容" (:exclusions result) "无"))
        (when-let [secrets (:secrets result)]
          (el :p {:class "hc-backup-muted"}
              (str "密钥处理："
                   (if (:included? secrets) "已按选择包含" "未包含")
                   (when-let [removed (get-in secrets [:removed :secret])]
                     (str "；已移除 " removed " 个密钥字段"))))))))

(defn- create-section
  [state]
  (let [errors (model/create-validation state)
        encrypt? (not (false? (get-in state [:ui :encrypt?])))
        reason (or (model/submit-reason state)
                   (some-> errors first :message))]
    (el :div {:class "hc-backup-section"}
        (el :p {:class "hc-backup-lead"}
            "备份按一次取样时间生成一致快照；模型密钥默认不勾选，加密默认开启。")
        (target-summary state)
        (category-grid state :create)
        (create-options state)
        (encryption-panel state)
        (el :div {:class "hc-backup-submit"}
            (when reason
              (el :p {:class "hc-backup-warning" :role "status"} reason))
            (button (if encrypt? "生成加密备份" "生成未加密备份")
                    (act :backup/create)
                    {:class "hc-backup-button hc-backup-button--primary"
                     :disabled (boolean reason)}))
        (create-result-panel state))))

(defn- backup-row
  [state row]
  (let [selected? (= (:id row) (get-in state [:ui :selected-backup-id]))
        confirm-delete? (= (:id row) (get-in state [:ui :delete-confirm-id]))]
    (el :article {:class (str "hc-backup-row"
                              (when selected? " hc-backup-row--selected"))
                  :key (:id row)
                  :aria-current (when selected? "true")}
        (el :header {:class "hc-backup-row__header"}
            (el :div {}
                (el :h4 {} (str "备份 " (:id row)))
                (el :p {:class "hc-backup-muted"} (:label row))))
        (facts [["取样时间" (:sampled-at row)]
                ["创建时间" (:created-at row)]
                ["设备" (:device row)]
                ["应用版本" (:app-version row)]
                ["格式版本" (:format-version row)]
                ["加密" (if (:encrypted? row) "是" "否")]
                ["完整性" (or (:integrity row) "未提供")]
                ["大小" (cond
                          (number? (:size row)) (format-bytes (:size row))
                          (some? (:size row)) (str (:size row))
                          :else "未提供")]])
        (el :div {:class "hc-backup-actions"}
            (button (if selected? "已选择" "选择这份备份")
                    (act :backup/select-backup :backup-id (:id row))
                    {:aria-pressed (when selected? "true")})
            (button "删除"
                    (act :backup/request-delete :backup-id (:id row))
                    {:class "hc-backup-button hc-backup-button--danger hc-backup-button--quiet"
                     :disabled (not (model/target-ready? state))}))
        (when confirm-delete?
          (el :div {:class "hc-backup-confirm" :role "alert"}
              (el :p {} "再次确认删除这份备份文件？当前资料与本地恢复点不受影响；删除后无法从该目标恢复。")
              (button "确认删除备份"
                      (act :backup/confirm-delete :backup-id (:id row))
                      {:class "hc-backup-button hc-backup-button--danger"})
              (button "取消"
                      (act :backup/cancel-delete)
                      {:class "hc-backup-button hc-backup-button--quiet"}))))))

(defn- backup-list
  [state]
  (let [rows (model/backup-rows state)
        listing-error (get-in state [:server :listing-error])
        status (get-in state [:server :status])
        target (model/selected-target state)]
    (el :section {:class "hc-backup-panel"}
        (el :div {:class "hc-backup-section-heading"}
            (el :h3 {} "选择要恢复的备份")
            (button "刷新列表"
                    (act :backup/refresh)
                    {:class "hc-backup-button hc-backup-button--quiet"
                     :disabled (not (model/can-submit? state))}))
        (cond
          (and target (false? (:available? target)))
          (warning (str "所选备份目标不可用："
                        (or (:reason target) "服务器未提供原因")
                        "。没有读取备份列表，也不会把不可用当作空列表。")
                   :error)

          listing-error
          (warning (str "备份列表读取失败：" listing-error
                        "。界面不会把读取失败当作空列表。")
                   :error)

          (nil? status)
          (el :p {:class "hc-backup-muted"}
              "尚未读取备份状态；先刷新。")

          (empty? rows)
          (el :p {:class "hc-backup-empty"}
              "尚无设备外备份。新建一份备份后，这里会显示取样时间、设备与加密状态。")

          :else
          (el :div {:class "hc-backup-list"}
              (mapv #(backup-row state %) rows))))))

(defn- password-panel
  [state]
  (let [ui (:ui state)
        backup (model/selected-backup state)
        encrypted? (model/encrypted-backup? backup)]
    (el :section {:class "hc-backup-panel"}
        (el :h3 {} "备份密码")
        (el :p {:class "hc-backup-muted"}
            (cond
              (nil? backup) "选择备份后输入密码。"
              encrypted? "这份备份已加密；密码只用于本次校验、预览与恢复，不写入本地存储。"
              :else "这份备份未加密；密码可以留空。"))
        (field "备份密码"
               (password-input
                ui :password "恢复用备份密码" "输入备份密码"))
        (el :div {:class "hc-backup-actions"}
            (button "校验备份"
                    (act :backup/verify)
                    {:disabled (or (not (model/can-submit? state))
                                   (nil? backup)
                                   (not (model/target-ready? state)))})
            (button (if (:password-visible? ui) "隐藏密码" "显示密码")
                    (act :backup/toggle-field :field :password-visible?)
                    {:class "hc-backup-button hc-backup-button--quiet"})
            (button "复制密码"
                    (act :backup/copy-password)
                    {:class "hc-backup-button hc-backup-button--quiet"
                     :disabled (empty? (str (:password ui)))})
            (button "清除密码"
                    (act :backup/clear-password)
                    {:class "hc-backup-button hc-backup-button--quiet"
                     :disabled (empty? (str (:password ui)))}))
        (when-let [verify (get-in state [:ui :verify-result])]
          (el :div {:class "hc-backup-verify"}
              (el :p {} "校验通过。")
              (facts [["备份 ID" (get-in verify [:backup :id])]
                      ["取样时间" (get-in verify [:backup :sampled-at])]
                      ["设备" (get-in verify [:backup :device])]]))))))

(defn- mode-panel
  [state]
  (let [ui (:ui state)
        mode (or (:mode ui) :merge)]
    (el :fieldset {:class "hc-backup-panel hc-backup-mode"}
        (el :legend {} "恢复方式")
        (el :label {:class "hc-backup-radio"}
            (el :input {:type "radio"
                        :name "hc-backup-mode"
                        :checked (= :merge mode)
                        :on-change (ev (act :backup/set-mode :mode :merge))})
            (el :span {}
                (el :strong {} "合并选中类别")
                (el :small {} "保留本地内容；同名或同 ID 差异默认两份都保留。")))
        (el :label {:class "hc-backup-radio"}
            (el :input {:type "radio"
                        :name "hc-backup-mode"
                        :checked (= :replace mode)
                        :on-change (ev (act :backup/set-mode :mode :replace))})
            (el :span {}
                (el :strong {} "替换选中类别")
                (el :small {} "移除本地所选类别的版本，改用备份版本；未选类别不变。")))
        (when (= :replace mode)
          (warning "替换会移除所选类别中不在备份里的本地内容。恢复前服务器会先建立本地恢复点；若无法建立恢复点，恢复不会开始。"
                   :error)))))

(defn- conflict-panel
  [state]
  (let [preview (model/preview-view state)
        conflicts (:conflicts preview)]
    (when (seq conflicts)
      (el :section {:class "hc-backup-panel"}
          (el :h3 {} "合并冲突")
          (el :p {:class "hc-backup-muted"}
              "同名不同内容的助手与世界书默认保留两份并加备份日期后缀。逐项选择后需要重新预览。")
          (when (:stale? preview)
            (warning "冲突选择已改变；下面的恢复按钮会保持禁用，直到重新预览。"))
          (el :div {:class "hc-backup-conflicts"}
              (mapv (fn [conflict]
                      (el :article {:class "hc-backup-conflict"
                                    :key (:key conflict)}
                          (el :header {}
                              (el :h4 {} (:name conflict))
                              (el :p {:class "hc-backup-muted"}
                                  (str (:category-label conflict)
                                       " · " (:reason-label conflict))))
                          (facts [["本地 ID" (:id conflict)]
                                  ["备份 ID" (:imported-id conflict)]
                                  ["当前选择" (:resolution-label conflict)]])
                          (el :div {:class "hc-backup-choice-row"
                                    :role "group"
                                    :aria-label (str "冲突处理：" (:name conflict))}
                              (mapv (fn [choice]
                                      (button (:label choice)
                                              (act :backup/resolve-conflict
                                                   :category (:category conflict)
                                                   :id (:id conflict)
                                                   :resolution (:resolution choice))
                                              {:key (name (:resolution choice))
                                               :class (str "hc-backup-choice"
                                                           (when (:selected? choice)
                                                             " hc-backup-choice--selected"))
                                               :aria-pressed (when (:selected? choice)
                                                               "true")}))
                                    (:choices conflict)))))
                    conflicts))))))

(defn- preview-lists
  [preview]
  (el :div {:class "hc-backup-preview-lists"}
      (when (seq (:placeholders preview))
        (list-block "缺失的助手（已建立待配置占位）" (:placeholders preview) "无"))
      (when (seq (:missing-relations preview))
        (list-block "缺失的关联" (:missing-relations preview) "无"))
      (when (seq (:relink-required preview))
        (list-block "需要重新定位的工作区" (:relink-required preview) "无"))
      (when (seq (:needs-review preview))
        (list-block "需要重新审核的项" (:needs-review preview) "无"))
      (when (seq (:needs-reconfiguration preview))
        (list-block "需要重新配置的项" (:needs-reconfiguration preview) "无"))
      (when (seq (:incomplete-attachments preview))
        (list-block "备份中缺失的附件" (:incomplete-attachments preview) "无"))
      (when (seq (:expired-skipped preview))
        (list-block "已超过删除保留期而跳过" (:expired-skipped preview) "无"))
      (when (seq (:memory-reactivation preview))
        (list-block "会被重新启用的记忆" (:memory-reactivation preview) "无"))
      (when (seq (:warnings preview))
        (list-block "预览警告" (:warnings preview) "无"))))

(defn- acknowledge-command
  [state maintenance]
  (let [target (model/target-view state)
        data-dir (or (:data-dir target) "<data-dir>")
        id (or (get-in maintenance [:last :maintenance-id])
               (get-in maintenance [:last :id])
               "<maintenance-id>")
        code (or (:acknowledge-code maintenance) "<acknowledge-code>")]
    (str "clojure -M:server -m hc.server.backup.cli status \\\n"
         "  --data-dir " data-dir " --db-path <db-path>\n"
         "clojure -M:server -m hc.server.backup.cli acknowledge \\\n"
         "  --data-dir " data-dir " \\\n"
         "  --db-path <db-path> \\\n"
         "  --maintenance-id " id " --operator <name> \\\n"
         "  --acknowledge " code " \\\n"
         "  --state-digest <state-digest> --restore-digest <restore-digest> \\\n"
         "  --store-changed true \\\n"
         "  --note \"<比较结果>\"")))

(defn- acknowledge-panel
  [state maintenance]
  (let [status (:status maintenance)]
    (cond
      (:acknowledge-required? maintenance)
      (el :section {:class "hc-backup-acknowledge" :role "alert"}
          (el :h4 {} "操作员确认：不会把失败改成成功")
          (warning
           (str "上一次恢复失败，"
                (case (:evidence maintenance)
                  :committed "存储可能已写入恢复数据"
                  :unknown "无法证明存储是否已提交"
                  :not-committed "已确认没有提交"
                  :not-started "恢复没有开始"
                  "没有足够的提交证据")
                "。该记录会保持启动阻塞，直到你核对当前存储与恢复点并记录确认。")
           :error)
          (facts [["维护 ID" (or (get-in maintenance [:last :maintenance-id])
                                 (get-in maintenance [:last :id]))]
                  ["提交证据" (:evidence-label maintenance)]
                  ["确认代码" (:acknowledge-code maintenance)]
                  ["启动门槛" (:start-gate-label maintenance)]])
          (el :p {:class "hc-backup-muted"}
              "先运行 status 读取 state-digest 与 restore-digest，并比较当前存储与恢复点。")
          (el :pre {:class "hc-backup-code"}
              (acknowledge-command state maintenance))
          (el :p {:class "hc-backup-note"}
              "如果已恢复另一份备份或完成手工修复，把 --acknowledge 改为 restore-another-backup。存储没有变化时服务器会拒绝确认。不要手改 state.edn，也不要自动重跑恢复。")
          (el :div {:class "hc-backup-actions"}
              (button "重新读取维护状态"
                      (act :backup/refresh-maintenance)
                      {:class "hc-backup-button hc-backup-button--quiet"
                       :disabled (not (model/can-submit? state))})))

      (= :failed-before-commit status)
      (el :p {:class "hc-backup-note"}
          "已确认未提交：当前资料保持原样；不需要操作员确认，下次启动可以继续。失败记录仍保留。")

      (and (= :failed status)
           (false? (get-in maintenance [:last :restore-attempted?])))
      (el :p {:class "hc-backup-note"}
          "恢复没有开始；该记录不阻塞下次启动。")

      (:acknowledged? maintenance)
      (el :p {:class "hc-backup-note"}
          "操作员已记录确认；失败状态与证据保留，不表示恢复成功。")

      :else nil)))

(defn- maintenance-panel
  [state]
  (let [maintenance (model/maintenance-view state)
        active-or-last (or (:active maintenance) (:last maintenance))
        status (:status maintenance)
        no-write? (:no-write? maintenance)
        status-label (if no-write? "没有写入" (:status-label maintenance))]
    (el :section {:class "hc-backup-panel hc-backup-maintenance-panel"}
        (el :h3 {} "停机恢复与重连")
        (if (:available? maintenance)
          (el :div {}
              (warning "工作台恢复会停止写入、恢复备份并重启应用。连接可能短暂中断；服务器确认前不会宣布成功。" :error)
              (checkbox "我已查看预览，理解停机/合并/替换范围，并确认执行"
                        (get-in state [:ui :restore-confirm?])
                        (act :backup/toggle-restore-confirm))
              (let [errors (model/maintenance-validation state)
                    reason (or (model/submit-reason state)
                               (some-> errors first :message))]
                (when reason
                  (el :p {:class "hc-backup-warning" :role "status"} reason))
                (button "停机并恢复"
                        (act :backup/request-maintenance-restore)
                        {:class "hc-backup-button hc-backup-button--danger"
                         :disabled (boolean reason)})))
          (el :div {}
              (warning
               (str "工作台内恢复不可用："
                    (or (:reason-label maintenance)
                        "服务器没有提供进程内停机/恢复/重连协调。")
                    " 当前资料没有改变；请按下面的离线步骤操作。")
               :error)
              (when-let [command (:command maintenance)]
                (el :p {:class "hc-backup-muted"}
                    (str "服务器建议入口：" command)))
              (el :p {:class "hc-backup-muted"}
                  "在应用停止后运行离线恢复；密码只从环境变量或文件读取：")
              (el :pre {:class "hc-backup-code"}
                  (offline-command state "restore"))))
        (when active-or-last
          (el :div {:class "hc-backup-maintenance-progress"}
              (el :p {:class "hc-backup-maintenance-progress__status"}
                  (badge status-label
                         (cond
                           no-write? :neutral
                           (= :complete status) :positive
                           (contains? #{:failed :stop-failed :restart-failed
                                        :failed-needs-attention
                                        :failed-needs-recovery
                                        :failed-commit-unproven
                                        :failed-before-commit} status)
                           :warning
                           :else :neutral)))
              (facts [["阶段" (:phase-label maintenance)]
                      ["状态" status-label]
                      ["提交证据" (:evidence-label maintenance)]
                      ["备份 ID" (or (:backup-id active-or-last) "未提供")]
                      ["操作员确认" (cond
                                      (:acknowledged? maintenance) "已记录"
                                      (:acknowledge-required? maintenance) "未记录"
                                      :else "不需要")]
                      ["启动门槛" (:start-gate-label maintenance)]])
              (when-let [message (:message maintenance)]
                (el :p {:class "hc-backup-muted"} message))
              (when (:reconnect? maintenance)
                (warning "连接已中断；维护可能仍在进行。请重新连接并核对结果，不要重复提交。" :error)
                (el :div {:class "hc-backup-actions"}
                    (button "重新连接并核对"
                            (act :backup/reconnect)
                            {:class "hc-backup-button hc-backup-button--primary"})))
              (el :div {:class "hc-backup-actions"}
                  (button "核对维护状态"
                          (act :backup/refresh-maintenance)
                          {:class "hc-backup-button hc-backup-button--quiet"
                           :disabled (or (not (model/can-submit? state))
                                         (nil? (or (get-in state [:ui :maintenance-request :id])
                                                   (get-in state [:ui :maintenance-request :maintenance-id])
                                                   (get-in maintenance [:active :id])
                                                   (get-in maintenance [:active :maintenance-id])
                                                   (get-in maintenance [:last :id])
                                                   (get-in maintenance [:last :maintenance-id]))))}))
              (acknowledge-panel state maintenance)
              (when (= :complete status)
                (el :p {:class "hc-backup-note"}
                    (if no-write?
                      "没有写入；资料保持原样，请核对预览摘要。"
                      "恢复完成；请检查待重新配置项、计划任务暂停状态与外部权限。"))))))))

(defn- preview-panel
  [state]
  (when-let [preview (model/preview-view state)]
    (el :section {:class "hc-backup-panel hc-backup-preview"}
        (el :h3 {} "恢复预览")
        (el :p {:class "hc-backup-preview__mode"}
            (badge (:mode-label preview)
                   (if (= :replace (:mode preview)) :warning :positive)))
        (when (:replace-all? preview)
          (warning "这是全部类别的替换；本地未包含在备份中的内容会被移除。" :error))
        (when (:no-changes? preview)
          (warning "预览显示没有需要写入的变化；恢复会跳过。"))
        (when (:already-imported? preview)
          (warning "此备份此前已导入；再次恢复会幂等跳过。"))
        (when (:recovery-required? preview)
          (el :p {:class "hc-backup-note"}
              "执行替换前会自动建立本地恢复点；若无法建立，恢复不会开始。"))
        (el :table {:class "hc-backup-table"}
            (el :thead {} (el :tr {} (el :th {} "项目") (el :th {} "数量")))
            (el :tbody {}
                (mapv (fn [[label value]]
                        (el :tr {:key label}
                            (el :td {} label)
                            (el :td {} (if (nil? value) "未提供" (str value)))))
                      (:summary preview))))
        (conflict-panel state)
        (when (:memory-reactivation-required? preview)
          (warning "替换会重新启用已归档或删除的记忆；需要显式确认，恢复后仍可再次停用。" :error)
          (checkbox "我确认要重新启用这些记忆"
                    (get-in state [:ui :confirm-memory-reactivation?])
                    (act :backup/toggle-memory-reactivation)))
        (preview-lists preview)
        (when (seq (:permissions preview))
          (el :div {:class "hc-backup-preview-permissions"}
              (el :h4 {} "恢复后的权限默认值")
              (facts (permission-facts (:permissions preview)))))
        (when-let [point (:recovery-point preview)]
          (facts [["恢复点要求" (if (:required? point) "执行前建立" "无需建立")]
                  ["恢复点状态" (or (:status point) "未提供")]
                  ["保留策略" "最近 7 个成功点 + 4 周 + 3 月代表副本"]]))
        (when-let [maintenance (get-in state [:ui :maintenance])]
          (el :section {:class "hc-backup-maintenance" :role "alert"}
              (el :h3 {} (:title maintenance))
              (el :p {} (:text maintenance))
              (el :ol {}
                  (mapv (fn [step] (el :li {:key step} step))
                        (:steps maintenance))))))))

(defn- restore-result-panel
  [state]
  (when-let [result (model/restore-result-view state)]
    (el :section {:class "hc-backup-panel hc-backup-result"}
        (el :h3 {} "最近一次恢复")
        (el :p {:class (str "hc-backup-result__status hc-backup-result__status--"
                            (name (or (:status result) :unknown)))}
            (badge (:status-label result)
                   (cond
                     (and (= :complete (:status result))
                          (or (:no-changes? result)
                              (:already-imported? result)))
                     :neutral
                     (= :complete (:status result)) :positive
                     :else :warning)))
        (when (:summary result)
          (el :table {:class "hc-backup-table"}
              (el :thead {} (el :tr {} (el :th {} "项目") (el :th {} "数量")))
              (el :tbody {}
                  (mapv (fn [[label value]]
                          (el :tr {:key label}
                              (el :td {} label)
                              (el :td {} (if (nil? value) "未提供" (str value)))))
                        (:summary result)))))
        (when-let [point (:recovery-point result)]
          (facts [["恢复点 ID" (:id point)]
                  ["恢复点时间" (:created-at point)]]))
        (when (some? (:rolled-back? result))
          (el :p {:class "hc-backup-muted"}
              (str "回退状态："
                   (case (:rolled-back? result)
                     true "已回退到操作前"
                     false "未回退"
                     :unknown "未知，需要核对"))))
        (when (:needs-recovery? result)
          (warning "存储可能已写入恢复数据；请使用本地恢复点核对，不要把部分结果当作成功。"
                   :error))
        (when (:commit-unproven? result)
          (warning "无法证明存储是否已提交；请先核对当前资料与恢复点，不要自动重试。"
                   :error))
        (when (:store-unchanged? result)
          (el :p {:class "hc-backup-muted"}
              "已确认存储没有提交；当前资料保持原样。"))
        (when (seq (:needs-reconfiguration result))
          (list-block "需要重新配置" (:needs-reconfiguration result) "无"))
        (when (seq (:relink-required result))
          (list-block "需要重新定位" (:relink-required result) "无"))
        (when (seq (:needs-review result))
          (list-block "需要审核" (:needs-review result) "无"))
        (when (seq (:incomplete-attachments result))
          (list-block "未恢复的附件" (:incomplete-attachments result) "无"))
        (when (seq (:warnings result))
          (list-block "恢复警告" (:warnings result) "无"))
        (when (seq (:permissions result))
          (el :div {:class "hc-backup-result-permissions"}
              (el :h4 {} "恢复后的权限状态")
              (facts (permission-facts (:permissions result)))
              (el :p {:class "hc-backup-note"}
                  "恢复后的计划任务保持暂停，外部权限与设备控制没有自动恢复。"))))))

(defn- restore-section
  [state]
  (el :div {:class "hc-backup-section"}
      (el :p {:class "hc-backup-lead"}
          "恢复前先校验并预览。预览区分合并与替换，列出冲突、缺失关联与未打包附件；预览阶段不写入任何资料。")
      (target-summary state)
      (backup-list state)
      (password-panel state)
      (mode-panel state)
      (category-grid state :restore)
      (let [errors (model/preview-validation state)
            reason (or (model/submit-reason state)
                       (some-> errors first :message))]
        (el :div {:class "hc-backup-submit"}
            (when reason
              (el :p {:class "hc-backup-warning" :role "status"} reason))
            (button "校验并生成预览"
                    (act :backup/preview)
                    {:class "hc-backup-button hc-backup-button--primary"
                     :disabled (boolean reason)})))
      (preview-panel state)
      (maintenance-panel state)
      (restore-result-panel state)))

(defn- recovery-point-row
  [state point]
  (let [confirm? (= (:id point) (get-in state [:ui :recovery-confirm-id]))]
    (el :article {:class "hc-backup-row" :key (:id point)}
        (el :header {:class "hc-backup-row__header"}
            (el :div {}
                (el :h4 {} (str "恢复点 " (:id point)))
                (el :p {:class "hc-backup-muted"}
                    (str (:reason-label point) " · " (:created-at point)
                         (when (some? (:bytes point))
                           (str " · " (format-bytes (:bytes point))))))))
        (when (seq (:scope point))
          (el :p {:class "hc-backup-muted"}
              (str "覆盖类别：" (str/join "、" (:scope point)))))
        (el :div {:class "hc-backup-actions"}
            (cond
              (model/maintenance-available? state)
              (el :div {}
                  (el :p {:class "hc-backup-muted"}
                      "工作台停机回退会停止写入并重启；服务器确认前不会宣布成功。")
                  (button "停机回退"
                          (act :backup/request-recovery-restore
                               :recovery-point-id (:id point))
                          {:class "hc-backup-button hc-backup-button--danger hc-backup-button--quiet"}))

              :else
              (el :div {}
                  (el :p {:class "hc-backup-muted"}
                      "在线回退不可用；请在离线维护窗口运行恢复点回退入口。")
                  (el :pre {:class "hc-backup-code"}
                      (offline-command state "restore-recovery-point" (:id point))))))
        (when confirm?
          (el :div {:class "hc-backup-confirm" :role "alert"}
              (el :p {} "回退会用该恢复点覆盖当前资料。服务器仍会先建立新的恢复点；若无法建立，回退不会开始。")
              (button (if (model/maintenance-available? state)
                        "确认停机回退"
                        "确认回退")
                      (act (if (model/maintenance-available? state)
                             :backup/request-maintenance-restore-point
                             :backup/confirm-recovery-restore)
                           :recovery-point-id (:id point))
                      {:class "hc-backup-button hc-backup-button--danger"})
              (button "取消"
                      (act :backup/cancel-recovery-restore)
                      {:class "hc-backup-button hc-backup-button--quiet"}))))))

(defn- recovery-section
  [state]
  (let [points (model/recovery-point-rows state)
        error (get-in state [:server :recovery-error])
        prune-confirm? (get-in state [:ui :prune-confirm?])
        restore-active? (model/restore-in-progress? state)]
    (el :div {:class "hc-backup-section"}
        (el :p {:class "hc-backup-lead"}
            "恢复点只保存在本机数据目录，用于替换前的保护，不是设备外备份。保留最近 7 个成功点、4 周与 3 月代表副本。")
        (el :div {:class "hc-backup-actions"}
            (button "刷新恢复点"
                    (act :backup/refresh-recovery)
                    {:class "hc-backup-button hc-backup-button--quiet"
                     :disabled (not (model/can-submit? state))})
            (button "按 7+4+3 清理"
                    (act :backup/request-prune)
                    {:class "hc-backup-button hc-backup-button--quiet"
                     :disabled (or (not (model/can-submit? state))
                                   (empty? points)
                                   restore-active?)}))
        (when prune-confirm?
          (el :div {:class "hc-backup-confirm" :role "alert"}
              (el :p {} "确认删除保留策略之外的恢复点？当前恢复点与资料不会被删除。")
              (button "确认清理"
                      (act :backup/confirm-prune)
                      {:class "hc-backup-button hc-backup-button--danger"})
              (button "取消"
                      (act :backup/cancel-prune)
                      {:class "hc-backup-button hc-backup-button--quiet"})))
        (cond
          error
          (warning (str "恢复点列表读取失败：" error
                        "。界面不会把读取失败当作没有恢复点。")
                   :error)

          (empty? points)
          (el :p {:class "hc-backup-empty"}
              "尚无本地恢复点。执行替换恢复前，服务器会尝试建立一个恢复点。")

          :else
          (el :div {:class "hc-backup-list"}
              (mapv #(recovery-point-row state %) points)))
        (restore-result-panel state))))

(defn- status-section
  [state]
  (let [target (model/target-view state)
        status (get-in state [:server :status])
        create-result (model/create-result-view state)]
    (el :div {:class "hc-backup-section"}
        (el :div {:class "hc-backup-actions"}
            (button "刷新状态"
                    (act :backup/refresh)
                    {:class "hc-backup-button hc-backup-button--primary"
                     :disabled (not (model/can-submit? state))}))
        (el :section {:class "hc-backup-panel"}
            (el :h3 {} "当前目标")
            (if status
              (facts [["数据目录" (:data-dir target)]
                      ["备份目录" (:local-root target)]
                      ["恢复点目录" (:recovery-root target)]
                      ["最近成功备份" (or (some-> target :last-backup :sampled-at)
                                          (some-> target :last-backup :created-at)
                                          "尚无成功备份")]
                      ["已列出的备份" (count (model/backup-rows state))]
                      ["本地恢复点" (count (model/recovery-point-rows state))]])
              (el :p {:class "hc-backup-muted"} "尚未读取备份状态。")))
        (el :section {:class "hc-backup-panel"}
            (el :h3 {} "已配置目标")
            (el :p {:class "hc-backup-muted"}
                "本地文件、WebDAV 与 S3 是三种目标能力；当前服务器只暴露其已配置的目标。")
            (el :ul {:class "hc-backup-target-list"}
                (mapv (fn [item]
                        (el :li {:key (str (:id item))}
                            (el :strong {} (str (:kind-label item) " · " (id-label (:id item))))
                            " "
                            (badge (cond
                                     (not (:configured? item)) "未配置"
                                     (:available? item) "可用"
                                     :else "不可用")
                                   (if (:available? item) :positive :warning))
                            (when (:reason item)
                              (el :p {:class "hc-backup-muted"} (:reason item)))
                            (when (:root item)
                              (el :p {:class "hc-backup-muted"}
                                  (str "目录：" (:root item))))
                            (when (:base-url item)
                              (el :p {:class "hc-backup-muted"}
                                  (str "地址：" (:base-url item))))
                            (when (:endpoint item)
                              (el :p {:class "hc-backup-muted"}
                                  (str "端点：" (:endpoint item)
                                       " · 桶：" (:bucket item))))))
                      (:targets target))))
        (when (seq (:errors target))
          (el :section {:class "hc-backup-panel"}
              (el :h3 {} "目标读取错误")
              (list-block "错误" (mapv pr-str (:errors target)) "无")))
        (when-let [storage (:storage target)]
          (el :section {:class "hc-backup-panel"}
              (el :h3 {} "存储状态")
              (facts [["路径" (:path storage)]
                      ["已用" (if (number? (:used-bytes storage))
                                (format-bytes (:used-bytes storage))
                                "未提供")]
                      ["可用" (if (number? (:free-bytes storage))
                                (format-bytes (:free-bytes storage))
                                "未提供")]])))
        (when create-result
          (el :section {:class "hc-backup-panel"}
              (el :h3 {} "最近备份")
              (facts [["备份 ID" (:id create-result)]
                      ["取样时间" (:sampled-at create-result)]
                      ["创建时间" (:created-at create-result)]
                      ["大小" (format-bytes (:size create-result))]
                      ["位置" (:path create-result)]
                      ["状态" (:status-label create-result)]])
              (when-let [verification-error
                         (get-in create-result [:verification :error])]
                (warning (str "备份校验错误："
                              (or (:message verification-error)
                                  "未提供原因"))
                         :error)))))))

(defn backup-tree
  "Return the complete backup workspace tree for STATE."
  [state]
  (let [state (or state (model/initial-state))
        section (get-in state [:ui :section] :backup)]
    (el :div {:class "hc-backup" :role "region"
              :aria-label "加密备份与恢复"}
        (el :style {} backup-css)
        (el :header {:class "hc-backup__header"}
            (el :h2 {} "加密备份与恢复")
            (el :p {:class "hc-backup-muted"}
                "本地文件、WebDAV 与 S3 目标由服务器配置；界面只展示服务器实际提供的能力。")
            (sync-banner state))
        (message-banner state)
        (tabs state)
        (case section
          :backup (create-section state)
          :restore (restore-section state)
          :recovery (recovery-section state)
          :status (status-section state)
          (el :p {:class "hc-backup-warning"} "未知备份分区。"))
        (el :footer {:class "hc-backup__footer"}
            (el :p {:class "hc-backup-note"}
                "备份文件不会在未选择目标时产生外部活动；密码不会写入本地存储；恢复失败不会伪装成功。")))))
