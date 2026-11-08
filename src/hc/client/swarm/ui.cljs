(ns hc.client.swarm.ui
  "Swarm fleet and board dashboard for the thin browser workbench.

  Public entries:

  - `SwarmDashboard` — the documented plain React component.  It accepts a
    normalized `:db` prop or an already projected `:swarm/dashboard` map, and
    renders without a Fulcro app context (including server rendering).
  - `SwarmDashboardFromDb` — descriptive alias for `SwarmDashboard`.
  - `SwarmDashboardFulcro` — optional Fulcro query wrapper for hosts that keep
    the projected dashboard at `:swarm/dashboard`.
  - `dashboard-element` — pure renderer for an already projected dashboard.

  The view is deliberately read-only.  Fleet rows and task nodes expand with
  native `<details>`/`<summary>`, so keyboard users get the same disclosure
  behavior without a custom tree widget.  Status is always written as text;
  color is supplemental.  A join report is rendered in full inside a focusable
  scroll region, never truncated or summarized.  A recovery write barrier is a
  labelled text section with its reasons, unresolved action IDs, previous W
  evidence, and persisted acknowledgement."
  (:require [com.fulcrologic.fulcro.components :refer [defsc]]
            [com.fulcrologic.fulcro.dom :as dom]
            [hc.client.swarm.model :as model]
            [hc.client.copy.failures :as failures]
            ["react" :as react]
            ["framework7-react" :as f7]))
(def ^:private BlockTitle (.-BlockTitle f7))
(def ^:private Card (.-Card f7))
(def ^:private CardContent (.-CardContent f7))
(def ^:private F7List (.-List f7))
(def ^:private F7ListItem (.-ListItem f7))

;; ---------------------------------------------------------------------------
;; Self-contained, scoped styles.  A host can override the custom properties
;; without replacing the component's layout.

(def dashboard-css
  ".hc-swarm-dashboard {
     --hc-fg: #111111;
     --hc-muted: #4a4a4a;
     --hc-bg: #ffffff;
     --hc-surface: #f7f7f7;
     --hc-border: #b8b8b8;
     --hc-accent: #0b57d0;
     --hc-positive: #146c2e;
     --hc-pending: #6b4d00;
     --hc-warning: #7a3e00;
     --hc-negative: #a4000f;
     color: var(--hc-fg);
     background: var(--hc-bg);
     overflow-wrap: anywhere;
   }
   @media (prefers-color-scheme: dark) {
     .hc-swarm-dashboard {
       --hc-fg: #f2f2f2;
       --hc-muted: #c9c9c9;
       --hc-bg: #121212;
       --hc-surface: #1e1e1e;
       --hc-border: #5a5a5a;
       --hc-accent: #9ec1ff;
       --hc-positive: #7ddc9a;
       --hc-pending: #ffd479;
       --hc-warning: #ffb86b;
       --hc-negative: #ff8a8a;
     }
   }
   /* The workbench theme is an explicit document attribute.  Keep the
      dashboard on the same light/dark surface even when the OS preference
      disagrees, and let the media query above serve `data-theme=\"auto\"`. */
   html[data-theme=\"light\"] .hc-swarm-dashboard {
     --hc-fg: #111111;
     --hc-muted: #4a4a4a;
     --hc-bg: #ffffff;
     --hc-surface: #f7f7f7;
     --hc-border: #b8b8b8;
     --hc-accent: #0b57d0;
     --hc-positive: #146c2e;
     --hc-pending: #6b4d00;
     --hc-warning: #7a3e00;
     --hc-negative: #a4000f;
   }
   html[data-theme=\"dark\"] .hc-swarm-dashboard {
     --hc-fg: #f2f2f2;
     --hc-muted: #c9c9c9;
     --hc-bg: #121212;
     --hc-surface: #1e1e1e;
     --hc-border: #5a5a5a;
     --hc-accent: #9ec1ff;
     --hc-positive: #7ddc9a;
     --hc-pending: #ffd479;
     --hc-warning: #ffb86b;
     --hc-negative: #ff8a8a;
   }
   html[data-contrast=\"high\"] .hc-swarm-dashboard {
     --hc-fg: #000000;
     --hc-muted: #1a1a1a;
     --hc-bg: #ffffff;
     --hc-surface: #ffffff;
     --hc-border: #000000;
     --hc-accent: #003f66;
     --hc-positive: #0b4d24;
     --hc-pending: #4d2e00;
     --hc-warning: #4d2e00;
     --hc-negative: #7a0000;
   }
   .hc-swarm-dashboard__summary {
     color: var(--hc-muted);
     margin: 0 0 1rem;
   }
   .hc-swarm-fleet {
     display: grid;
     gap: 1rem;
   }
   .hc-swarm-row {
     border: 1px solid var(--hc-border);
     border-radius: .5rem;
     background: var(--hc-surface);
     color: var(--hc-fg);
     margin: 0;
   }
   /* Framework7 list/card colors follow the Framework7 theme, which may not
      match the workbench's explicit data-theme.  Keep the slot list on the
      same dashboard palette. */
   .hc-swarm-dashboard .list,
   .hc-swarm-dashboard .list ul {
     background: transparent;
   }
   .hc-swarm-dashboard .item-content {
     background: var(--hc-bg);
     color: var(--hc-fg);
   }
   .hc-swarm-dashboard .item-title {
     color: var(--hc-fg);
   }
   .hc-swarm-dashboard .item-text,
   .hc-swarm-dashboard .item-footer,
   .hc-swarm-dashboard .item-after {
     color: var(--hc-muted);
   }
   .hc-swarm-row[data-selected=\"true\"],
   .hc-swarm-row__details[data-selected=\"true\"] {
     border-color: var(--hc-accent);
     box-shadow: 0 0 0 2px var(--hc-accent);
   }
   .hc-swarm-row__summary {
     min-height: 44px;
     padding: .75rem 1rem;
     cursor: pointer;
     display: grid;
     gap: .5rem;
   }
   .hc-swarm-row__summary:focus-visible,
   .hc-task__summary:focus-visible,
   .hc-join-report summary:focus-visible {
     outline: 3px solid var(--hc-accent);
     outline-offset: 2px;
   }
   .hc-swarm-row__title {
     display: flex;
     flex-wrap: wrap;
     align-items: center;
     gap: .5rem 1rem;
   }
   .hc-swarm-row__title h3 {
     margin: 0;
     font-size: 1.05rem;
   }
   .hc-swarm-facts {
     display: grid;
     grid-template-columns: repeat(auto-fit, minmax(11rem, 1fr));
     gap: .5rem 1rem;
     margin: 0;
   }
   .hc-swarm-facts dt {
     font-weight: 700;
     color: var(--hc-muted);
     font-size: .8rem;
   }
   .hc-swarm-facts dd {
     margin: 0;
   }
   .hc-swarm-row__body {
     padding: 0 1rem 1rem;
   }
   .hc-swarm-row__body h4 {
     margin: 1rem 0 .4rem;
     font-size: .95rem;
   }
   .hc-status {
     display: inline-block;
     border: 1px solid currentColor;
     border-radius: 999px;
     padding: .1rem .5rem;
     font-size: .8rem;
     font-weight: 700;
     white-space: nowrap;
   }
   .hc-status--positive { color: var(--hc-positive); }
   .hc-status--pending { color: var(--hc-pending); }
   .hc-status--warning { color: var(--hc-warning); }
   .hc-status--negative { color: var(--hc-negative); }
   .hc-status--neutral { color: var(--hc-muted); }
   .hc-task-tree,
   .hc-wait-tree,
   .hc-responsibility-list,
   .hc-queue-list {
     list-style: none;
     margin: .25rem 0;
     padding-left: 1rem;
   }
   .hc-task-tree .hc-task-tree,
   .hc-wait-tree .hc-wait-tree {
     border-left: 2px solid var(--hc-border);
   }
   .hc-task {
     margin: .4rem 0;
   }
   .hc-task__summary {
     min-height: 44px;
     padding: .4rem .5rem;
     cursor: pointer;
     display: flex;
     flex-wrap: wrap;
     align-items: center;
     gap: .35rem .75rem;
   }
   .hc-task__goal { font-weight: 700; }
   .hc-task__meta { color: var(--hc-muted); font-size: .85rem; }
   .hc-task__body { padding: .25rem .5rem .5rem; }
   .hc-task__facts,
   .hc-holder-facts,
   .hc-write-gate-facts,
   .hc-recovery-facts,
   .hc-report-facts {
     display: grid;
     grid-template-columns: repeat(auto-fit, minmax(9rem, 1fr));
     gap: .25rem .75rem;
     margin: .25rem 0 .5rem;
   }
   .hc-task__facts dt,
   .hc-holder-facts dt,
   .hc-write-gate-facts dt,
   .hc-recovery-facts dt,
   .hc-report-facts dt {
     font-weight: 700;
     color: var(--hc-muted);
     font-size: .8rem;
   }
   .hc-task__facts dd,
   .hc-holder-facts dd,
   .hc-write-gate-facts dd,
   .hc-recovery-facts dd,
   .hc-report-facts dd {
     margin: 0;
   }
   .hc-task__remaining,
   .hc-task__wait,
   .hc-task__detail {
     margin: .35rem 0;
   }
   .hc-attention {
     color: var(--hc-warning);
     font-weight: 700;
   }
   .hc-unresolved {
     margin: 0 0 1rem;
     padding: .75rem 1rem;
     border: 2px solid var(--hc-warning);
     border-radius: .5rem;
     background: var(--hc-surface);
   }
   .hc-unresolved h4 {
     margin: 0 0 .35rem;
     color: var(--hc-warning);
   }
   .hc-unresolved-call {
     margin: .5rem 0;
     padding: .5rem .65rem;
     border: 1px solid var(--hc-border);
     border-radius: .35rem;
     background: var(--hc-bg);
   }
   .hc-unresolved-call__title {
     font-weight: 700;
     overflow-wrap: anywhere;
   }
   .hc-unresolved-call__meta {
     color: var(--hc-muted);
     font-size: .85rem;
     overflow-wrap: anywhere;
   }
   .hc-unresolved-call__actions {
     display: flex;
     flex-wrap: wrap;
     gap: .5rem;
     margin-top: .35rem;
   }
   .hc-unresolved-call__actions button {
     min-height: 44px;
     min-width: 44px;
     padding: .35rem .75rem;
   }
   .hc-unresolved-call__ack {
     color: var(--hc-positive);
     font-weight: 700;
   }
   .hc-protected {
     margin: 0 0 1rem;
     padding: .75rem 1rem;
     border: 2px solid var(--hc-accent);
     border-radius: .5rem;
     background: var(--hc-surface);
   }
   .hc-protected h4 {
     margin: 0 0 .35rem;
   }
   .hc-protected-program {
     margin: .5rem 0;
     padding: .5rem .65rem;
     border: 1px solid var(--hc-border);
     border-radius: .35rem;
     background: var(--hc-bg);
     overflow-wrap: anywhere;
   }
   .hc-protected-program__title {
     font-weight: 700;
   }
   .hc-protected-program__conflict {
     color: var(--hc-negative);
     font-weight: 700;
   }
   .hc-protected-program__stamps {
     font-family: ui-monospace, monospace;
     font-size: .82rem;
     white-space: pre-wrap;
     overflow-wrap: anywhere;
   }
   .hc-protected-program__actions {
     display: flex;
     flex-wrap: wrap;
     gap: .5rem;
     margin-top: .35rem;
   }
   .hc-protected-program__actions button {
     min-height: 44px;
     min-width: 44px;
     padding: .35rem .75rem;
   }
   .hc-recovery {
     margin: .75rem 0 .5rem;
     padding: .65rem .75rem;
     border: 2px solid var(--hc-warning);
     border-radius: .4rem;
     background: var(--hc-bg);
   }
   .hc-recovery h5 {
     margin: 0 0 .35rem;
     font-size: .95rem;
     color: var(--hc-warning);
   }
   .hc-recovery-reasons,
   .hc-recovery-actions {
     margin: .25rem 0 .5rem;
     padding-left: 1.25rem;
   }
   .hc-recovery-reasons li,
   .hc-recovery-actions li {
     margin: .15rem 0;
     overflow-wrap: anywhere;
   }
   .hc-join-report {
     margin-top: .75rem;
     border-top: 1px solid var(--hc-border);
     padding-top: .5rem;
   }
   .hc-join-report summary {
     min-height: 44px;
     cursor: pointer;
     font-weight: 700;
     padding: .35rem 0;
   }
   .hc-join-report__text {
     white-space: pre-wrap;
     overflow-wrap: anywhere;
     max-height: 28rem;
     overflow: auto;
     background: var(--hc-bg);
     border: 1px solid var(--hc-border);
     border-radius: .35rem;
     padding: .75rem;
     margin: .5rem 0 0;
   }
   .hc-slot-list .item-title,
   .hc-slot-list .item-text,
   .hc-slot-list .item-footer {
     overflow-wrap: anywhere;
   }
   .hc-slot-list .item-title,
   .hc-slot-list .item-after {
     white-space: normal;
   }
   .hc-board-columns {
     display: grid;
     gap: 1rem;
   }
   @media (min-width: 820px) {
     .hc-board-columns {
       grid-template-columns: minmax(0, 1.45fr) minmax(0, 1fr);
       align-items: start;
     }
   }
   @media (max-width: 480px) {
     .hc-swarm-facts,
     .hc-task__facts,
     .hc-holder-facts,
     .hc-write-gate-facts,
     .hc-recovery-facts,
     .hc-report-facts {
       grid-template-columns: 1fr;
     }
     .hc-task-tree,
     .hc-wait-tree {
       padding-left: .35rem;
     }
     .hc-swarm-row__summary {
       padding: .6rem;
     }
   }")

(defn- f7
  "Create a Framework7 React element without calling the component function
  outside React's render cycle."
  [component props & children]
  (apply react/createElement component (clj->js props) children))

(defn- status-element
  [status label tone]
  (dom/span #js {:className (str "hc-status hc-status--" (name (or tone :neutral)))
                 :data-status (name (or status :unknown))}
            label))

(defn- attention-element
  [text]
  (dom/span #js {:className "hc-attention"} text))

(defn- fact
  [label value]
  (dom/div #js {:className "hc-fact"}
           (dom/dt nil label)
           (dom/dd nil (or value "—"))))

(defn- wait-node-element
  [node]
  (dom/li #js {:className "hc-wait-node"
               :key (str "wait-" (:task-id node))
               :data-task-id (:task-id node)}
          (dom/span #js {:className "hc-task__goal"}
                    (or (:goal node) (str "任务 " (:task-id node))))
          (dom/span #js {:className "hc-task__meta"}
                    (cond
                      (:truncated? node) " — 等待链已截断；请检查看板"
                      (:missing? node) " — 任务缺失"
                      (:cycle? node) " — 检测到循环；不再继续跟随"
                      :else (str " — " (model/task-state-label (:state node)))))
          (when (seq (:children node))
            (dom/ul #js {:className "hc-wait-tree"}
                    (mapv wait-node-element (:children node))))))

(defn- task-node-element
  [node]
  (let [status (:state node)
        status-label (model/task-state-label status)
        tone (model/task-state-tone status)
        missing? (:missing? node)
        title (if missing?
                (str "缺失任务 " (:task-id node))
                (:goal node))
        meta (cond
               missing? "该任务被引用但不在快照中。"
               (:cycle? node) "检测到循环；子任务不再继续展开。"
               (:cycle-root? node) "不可达的循环根；请检查看板。"
               (:orphan? node) "父任务缺失；作为根任务显示。"
               (:truncated? node) "已达深度上限；子任务不再继续展开。"
               :else nil)]
    (dom/li #js {:className "hc-task"
                 :key (str "task-" (:task-id node))
                 :data-task-id (:task-id node)
                 :data-state (when status (name status))}
            (dom/details #js {:className "hc-task__details"}
                         (dom/summary #js {:className "hc-task__summary"
                                           :aria-label (str title ", " status-label
                                                           (when (:needs-token node) ", token task")
                                                           (when meta (str ". " meta)))}
                                      (dom/span #js {:className "hc-task__goal"} title)
                                      (when status
                                        (status-element status status-label tone))
                                      (dom/span #js {:className "hc-task__meta"}
                                                (str (if (:needs-token node)
                                                       "令牌任务"
                                                       "普通任务")
                                                     (when-let [owner (:owner-id node)]
                                                       (str " · 持有者 " owner))
                                                     (when-let [claim (:claim-id node)]
                                                       (str " · 认领 " claim))
                                                     (when-let [ledger (:ledger-key node)]
                                                       (str " · 评审台账 " ledger))))
                                      (when meta
                                        (attention-element meta)))
                         (dom/div #js {:className "hc-task__body"}
                                  (dom/dl #js {:className "hc-task__facts"}
                                          (fact "剩余工作" (:remaining-work node))
                                          (fact "完成条件" (:done-when node))
                                          (fact "状态" status-label)
                                          (fact "等待依赖" (if (seq (:wait-for node))
                                                             (str (count (:wait-for node)) " 个已登记")
                                                             "无")))
                                  (dom/p #js {:className "hc-task__detail"}
                                         (:state-detail node))
                                  (when (seq (:wait-chain node))
                                    (dom/div nil
                                             (dom/strong nil "等待链")
                                             (dom/ul #js {:className "hc-wait-tree"}
                                                     (mapv wait-node-element (:wait-chain node)))))
                                  (when (seq (:children node))
                                    (dom/ul #js {:className "hc-task-tree"}
                                            (mapv task-node-element (:children node)))))))))

(defn- task-tree-element
  [tasks]
  (if (seq tasks)
    (dom/ul #js {:className "hc-task-tree"
                 :aria-label "根任务与子任务层级"}
            (mapv task-node-element tasks))
    (dom/p #js {:className "hc-empty"} "该 worktree 尚未上报任何看板任务。")))

(defn- slot-element
  [slot]
  (let [title (str "槽位 " (or (:slot-id slot) "未知"))
        after (str (:role-label slot) " · " (:life-state-label slot))
        lifecycle (:lifecycle slot)
        footer (str (:description lifecycle)
                    (when-let [note (:handoff-note slot)]
                      (str " 交接：" note)))]
    (f7 F7ListItem
        {:key (str "slot-" (or (:slot-id slot) (:agent-id slot)))
         :title title
         :after after
         :text (:label lifecycle)
         :footer footer
         :aria-label (str title ", " after ". " (:label lifecycle) ". " footer)
         :data-slot-id (or (:slot-id slot) "unknown")}
        (when (:attention? slot)
          (attention-element "需要关注")))))

(defn- slots-element
  [slots]
  (if (seq slots)
    (f7 F7List {:className "hc-slot-list"
              :aria-label "固定运行槽"}
        (mapv slot-element slots))
    (dom/p #js {:className "hc-empty"} "该 worktree 尚未上报槽位状态。")))

(defn- responsibility-element
  [row]
  (dom/li #js {:className "hc-responsibility"
               :key (str "responsibility-" (:id row))}
          (case (:kind row)
            :task
            (str "任务 " (:task-id row)
                 " — " (model/task-state-label (:state row))
                 (when (:needs-token row) "（令牌）")
                 (when-let [goal (:goal row)] (str ": " goal)))

            :action
            (str "动作 " (:action-id row)
                 " — " (or (some-> (:phase row) name) "未知")
                 (when-let [kind (:action-kind row)] (str " " (name kind)))
                 (when-let [task (:task-id row)] (str "（任务 " task "）")))

            (str "未知责任 " (:id row)))))

(defn- holder-element
  [holder]
  (let [reported? (:reported? holder)]
    (dom/div #js {:className "hc-holder"}
             (dom/dl #js {:className "hc-holder-facts"}
                     (fact "持有者" (if reported?
                                      (or (:holder-id holder) "无")
                                      "未上报"))
                     (fact "Term" (if reported? (:term-id holder) "未上报"))
                     (fact "令牌状态" (if reported?
                                           (or (some-> (:state holder) name) "未知")
                                           "未上报"))
                     (fact "R(holder)" (if reported?
                                         (str (:responsibility-count holder)
                                              " 项"
                                              (when (:computed? holder) "（由看板推算）"))
                                         "未上报")))
             (dom/p #js {:className (when (:inconsistent? holder) "hc-attention")}
                    (:explanation holder))
             (cond
               (not reported?)
               (dom/p #js {:className "hc-empty"}
                      "令牌槽位未由服务器上报。")

               (seq (:responsibility-rows holder))
               (dom/ul #js {:className "hc-responsibility-list"
                            :aria-label "持有者完整责任集合"}
                       (mapv responsibility-element (:responsibility-rows holder)))

               :else
               (dom/p #js {:className "hc-empty"} "R(holder) = ∅")))))

(defn- queue-element
  [item]
  (dom/li #js {:className "hc-queue-item"
               :key (str "queue-" (or (:action-id item) "unknown"))}
          (if (:missing? item)
            (str "未知排队动作 " (:action-id item))
            (str "动作 " (:action-id item)
                 " — " (or (some-> (:phase item) name) "未知")
                 (when-let [agent (:agent-id item)] (str " · 个体 " agent))
                 (when-let [kind (:kind item)] (str " · " (name kind)))))))

(defn- recovery-reason-element
  [reason]
  (dom/li #js {:className "hc-recovery-reason"
               :key (str "recovery-reason-" (name (or reason :unknown)))}
          (model/recovery-reason-label reason)))

(defn- recovery-action-element
  [action-id]
  (dom/li #js {:className "hc-recovery-action"
               :key (str "recovery-action-" action-id)}
          (str action-id)))

(defn- recovery-element
  [write-gate]
  (let [blocked? (:recovery-blocked? write-gate)
        acknowledgement (:recovery-acknowledgement write-gate)
        acknowledged? (true? (:acknowledged? acknowledgement))
        reasons (:recovery-reasons write-gate)
        actions (:recovery-actions write-gate)
        old-gate (:recovery-write-gate write-gate)]
    (when (or blocked? acknowledgement (seq reasons) (seq actions) old-gate)
      (dom/section #js {:className "hc-recovery"
                        :aria-label "恢复写屏障"
                        :data-recovery-state
                        (name (or (:recovery-state write-gate) :unknown))}
                   (dom/h5 nil
                           (cond
                             blocked?
                             "恢复屏障生效中 — 新的受保护写入被拒绝"

                             acknowledged?
                             "恢复已确认 — 屏障已解除"

                             :else
                             "已上报恢复证据"))
                   (dom/dl #js {:className "hc-recovery-facts"}
                           (fact "状态"
                                 (case (:recovery-state write-gate)
                                   :blocked "已阻塞"
                                   :acknowledged "已确认"
                                   "已上报"))
                           (fact "未结清动作"
                                 (if (seq actions)
                                   (str (count actions) " 个已上报")
                                   "无"))
                           (when old-gate
                             (dom/div #js {:className "hc-recovery-old-gate"}
                                      (fact "旧 W"
                                            (str (if (:held? old-gate) "持有中" "空闲")
                                                 (when-let [owner (:owner old-gate)]
                                                   (str "，持有者 " owner))
                                                 (when (number? (:queue old-gate))
                                                   (str "，队列 " (:queue old-gate) " 个"))))
                                      (fact "旧结局"
                                            (if (:outcome-unknown? old-gate)
                                              "未知；未证实已写或未写"
                                              "未记录为未知"))
                                      (fact "旧队列"
                                            (if (:queue-abandoned? old-gate)
                                              "已放弃；未重放"
                                              "无已放弃队列"))
                                      (fact "旧证据"
                                            (if-let [reason (:reason old-gate)]
                                              (model/recovery-reason-label reason)
                                              "未上报"))
                                      (fact "恢复于"
                                            (if-let [recovered-at (:recovered-at old-gate)]
                                              (str recovered-at " ms（自纪元起）")
                                              "未上报")))))
                   (when (seq reasons)
                     (dom/div nil
                              (dom/strong nil "W 被阻塞的原因")
                              (dom/ul #js {:className "hc-recovery-reasons"
                                           :aria-label "恢复屏障原因"}
                                      (mapv recovery-reason-element reasons))))
                   (when (and blocked? (empty? reasons))
                     (dom/p #js {:className "hc-empty"}
                            "服务器上报了恢复屏障但未给出原因。"))
                   (when (seq actions)
                     (dom/div nil
                              (dom/strong nil "未结清动作 ID")
                              (dom/ul #js {:className "hc-recovery-actions"
                                           :aria-label "未结清恢复动作"}
                                      (mapv recovery-action-element actions))))
                   (when acknowledgement
                     (dom/div nil
                              (dom/strong nil "恢复确认")
                              (dom/dl #js {:className "hc-recovery-facts"}
                                      (fact "已确认"
                                            (if (:acknowledged? acknowledgement)
                                              "是"
                                              "未上报"))
                                      (fact "证据"
                                            (or (:evidence acknowledgement)
                                                "未上报"))
                                      (fact "操作员"
                                            (or (:actor-id acknowledgement)
                                                "未知"))
                                      (fact "看板版本"
                                            (or (:at-version acknowledgement)
                                                "未上报"))
                                      (fact "确认时 W 状态"
                                            (case (:write-gate-held? acknowledgement)
                                              true "持有中"
                                              false "空闲"
                                              "未上报")))))))))

(defn- write-gate-element
  [write-gate]
  (dom/div #js {:className "hc-write-gate"
                :data-w-state (name (:mode write-gate))}
           (dom/dl #js {:className "hc-write-gate-facts"}
                   (fact "W" (:mode-label write-gate))
                   (fact "持有者" (if (:reported? write-gate)
                                   (or (:owner write-gate) "无")
                                   "未上报"))
                   (fact "当前动作" (if (:reported? write-gate)
                                            (or (:current-action-id write-gate) "无")
                                            "未上报"))
                   (fact "队列" (if (:reported? write-gate)
                                   (str (:queued-count write-gate) " 个等待")
                                   "未上报")))
           (dom/p nil (:explanation write-gate))
           (recovery-element write-gate)
           (when (:holder-not-writing? write-gate)
             (dom/p #js {:className "hc-task__meta"}
                    (str "令牌持有者 " (:holder-id write-gate)
                         " 不是当前 W 持有者。持有令牌不占用 W；这是合法的。")))
           (when (seq (:queue write-gate))
             (dom/ul #js {:className "hc-queue-list"
                          :aria-label "等待工作区写入口的动作"}
                     (mapv queue-element (:queue write-gate))))))

(defn- report-element
  [report]
  (when report
    (dom/details #js {:className "hc-join-report"}
                 (dom/summary nil "合入报告 — 完整文本")
                 (dom/dl #js {:className "hc-report-facts"}
                         (fact "Swarm" (:swarm-id report))
                         (fact "切片" (:slice-id report))
                         (fact "快进提交" (or (:ff-commit report) "未上报"))
                         (fact "Rebase 轮次" (:rebase-rounds report)))
                 (if (:report-present? report)
                   (dom/pre #js {:className "hc-join-report__text"
                                 :tabIndex 0
                                 :role "region"
                                 :aria-label "完整合入报告，可滚动"}
                            (:report report))
                   (dom/p #js {:className "hc-empty"}
                          "服务器未为该已合入 swarm 提供报告文本。")))))

(defn- swarm-row-element
  [swarm selected-id]
  (let [status (:status swarm)
        status-label (:status-label swarm)
        selected? (and selected-id (= selected-id (:swarm-id swarm)))
        counts (or (:root-counts-displayed swarm)
                   (:root-counts swarm)
                   {:ready 0 :waiting 0 :done 0})
        declared? (= :declared (:root-counts-source swarm))]
    (f7 Card
        {:key (:swarm-id swarm)
         :className (str "hc-swarm-row hc-swarm-row--" (name (or status :unknown)))
         :data-swarm-id (:swarm-id swarm)
         :data-selected (if selected? "true" "false")
         :data-status (name (or status :unknown))}
        (f7 CardContent
            {:className "hc-swarm-row__content"}
            (dom/details #js {:className "hc-swarm-row__details"
                              :data-selected (if selected? "true" "false")
                              :open selected?}
                         (dom/summary #js {:className "hc-swarm-row__summary"
                                           :autoFocus selected?
                                           :aria-label (str "Swarm " (:slice-id swarm)
                                                           ", " status-label
                                                           ". " (:status-detail swarm))}
                                      (dom/div #js {:className "hc-swarm-row__title"}
                                               (dom/h3 nil (:slice-id swarm))
                                               (status-element status status-label
                                                               (:status-tone swarm))
                                               (when (:attention? swarm)
                                                 (attention-element "需要关注")))
                                      (dom/dl #js {:className "hc-swarm-facts"}
                                              (fact "工作区" (:worktree swarm))
                                              (fact (if declared?
                                                      "根任务（声明值）"
                                                      "根任务")
                                                    (str (:ready counts) " 个就绪 · "
                                                         (:waiting counts) " 个等待 · "
                                                         (:done counts) " 个完成"))
                                              (fact (if (:fleet-reported? swarm)
                                                      "Master 版本"
                                                      "看板版本")
                                                    (if (:fleet-reported? swarm)
                                                      (:master-version swarm)
                                                      (:board-version swarm)))
                                              (fact "合入队列" (:join-queue-text swarm))))
                         (dom/div #js {:className "hc-swarm-row__body"}
                                  (dom/p nil (:status-detail swarm))
                                  (when (false? (:fleet-reported? swarm))
                                    (dom/p #js {:className "hc-task__meta"}
                                           "未上报 fleet 行；正在显示该工作区当前的看板记录。合入队列、报告与 worktree 身份均未知。"))
                                  (when (:root-count-mismatch? swarm)
                                    (dom/p #js {:className "hc-attention"}
                                           (str "声明的根任务计数与看板树不一致：声明 "
                                                (pr-str (:declared-root-counts swarm))
                                                "，计算值 " (pr-str (:root-counts swarm)) "。")))
                                  (dom/div #js {:className "hc-board-columns"}
                                           (dom/div #js {:className "hc-board-main"}
                                                    (dom/h4 nil "根任务与子任务层级")
                                                    (task-tree-element (:tasks swarm)))
                                           (dom/div #js {:className "hc-board-side"}
                                                    (dom/h4 nil "固定运行槽")
                                                    (slots-element (:slots swarm))
                                                    (dom/h4 nil "令牌持有者")
                                                    (holder-element (:holder swarm))
                                                    (dom/h4 nil "工作区写入口 W")
                                                    (write-gate-element (:write-gate swarm))))
                                  (when-let [report (:join-report swarm)]
                                    (report-element report))
                                  (when-let [reason (:abandon-reason swarm)]
                                    (dom/details #js {:className "hc-join-report"}
                                                 (dom/summary nil "放弃原因")
                                                 (dom/pre #js {:className "hc-join-report__text"
                                                               :tabIndex 0
                                                               :role "region"
                                                               :aria-label "放弃原因"}
                                                          reason)))
                                  (when-let [error (:error swarm)]
                                    (dom/details #js {:className "hc-join-report"}
                                                 (dom/summary nil "失败详情")
                                                 (dom/pre #js {:className "hc-join-report__text"
                                                               :tabIndex 0
                                                               :role "region"
                                                               :aria-label "失败详情"}
                                                          (if (string? error) error (pr-str error)))))))))))

(defn- unresolved-call-element
  [call on-ack]
  (dom/div #js {:className "hc-unresolved-call"
                :key (:call-id call)
                :data-call-id (:call-id call)
                :data-outcome-unknown (if (:ack call) "false" "true")}
           (dom/div #js {:className "hc-unresolved-call__title"}
                    (str (or (:call-id call) "未知调用")
                         " · " (or (:kind call) "未知")
                         (when-let [role (:role call)]
                           (str " · " role))))
           (dom/div #js {:className "hc-unresolved-call__meta"}
                    (str "阶段 " (or (:phase call) "未知")
                         " · 最后已知 " (or (:last-known-stage call) "未知")
                         (when-let [task (:task-id call)]
                           (str " · 任务 " task))
                         (when-let [agent (:agent-id call)]
                           (str " · 个体 " agent))))
           (let [reason-label (failures/reason-label :receipt-lost {})]
             (when reason-label
               (dom/p #js {:className "hc-reason"} reason-label)))
           (if-let [label (:ack-label call)]
             (dom/p #js {:className "hc-unresolved-call__ack"}
                    (str label
                         (when-let [by (:ack-outcome call)]
                           (str "（" (name by) "）"))
                         (when-let [note (:ack-note call)]
                           (str " · " note))))
             (dom/div nil
                      (dom/p nil
                             "重启或中断后，这个动作的真实结果未知。先核对目标状态；确认只结清记录，绝不自动重放。")
                      (dom/div #js {:className "hc-unresolved-call__actions"}
                               (dom/button #js {:type "button"
                                                :className "hc-button"
                                                :data-ack-outcome "succeeded"
                                                :onClick (fn [_]
                                                           (when (fn? on-ack)
                                                             (on-ack {:call-id (:call-id call)
                                                                      :outcome :succeeded})))}
                                           "确认已成功")
                               (dom/button #js {:type "button"
                                                :className "hc-button"
                                                :data-ack-outcome "not-occurred"
                                                :onClick (fn [_]
                                                           (when (fn? on-ack)
                                                             (on-ack {:call-id (:call-id call)
                                                                      :outcome :not-occurred})))}
                                           "确认未发生"))))))

(defn- unresolved-calls-element
  [dashboard]
  (let [calls (:unresolved-calls dashboard)
        summary (:unresolved-summary dashboard)
        on-ack (:on-ack-call dashboard)]
    (when (seq calls)
      (dom/section #js {:className "hc-unresolved"
                        :aria-label "结果待确认"}
                   (dom/h4 nil
                           (str "结果待确认 · " (:open summary 0)
                                " 个未结 · " (:acknowledged summary 0)
                                " 个已核对"))
                   (dom/p nil
                          "外部动作在结果返回前中断。核对目标后选择“已成功”或“未发生”；服务端只结清该轮，不重放动作。")
                   (mapv #(unresolved-call-element % on-ack) calls)))))

(defn- stamp-text
  [stamp]
  (if (map? stamp)
    (str "mtime " (:mtime-sec stamp) "." (:mtime-nsec stamp)
         " · size " (:size stamp))
    (pr-str stamp)))

(defn- worker-program-element
  [program on-retry]
  (dom/div #js {:className "hc-protected-program"
                :key (:job-id program)
                :data-job-id (:job-id program)
                :data-status (or (:program-status program) "unknown")}
           (dom/div #js {:className "hc-protected-program__title"}
                    (str (or (:task-id program) "任务")
                         " · " (or (:claim-id program) "认领")
                         " · " (or (:label program) "结果未报告")))
           (when-let [path (:file-path program)]
             (dom/div #js {:className "hc-protected-program__meta"}
                      (str "文件 " path
                           (when (some? (:file-changed? program))
                             (str " · 文件已改变 " (:file-changed? program))))))
           (when (seq (:conflicts program))
             (dom/div nil
                      (dom/div #js {:className "hc-protected-program__conflict"}
                               "冲突：旧补丁未写入，外部内容保留")
                      (for [conflict (:conflicts program)]
                        (dom/div #js {:className "hc-protected-program__stamps"
                                      :key (str (:path conflict))}
                                 (str (:path conflict)
                                      " · " (or (:reason conflict) "stamp-mismatch")
                                      "\n原 stamp: " (stamp-text (:expected conflict))
                                      "\n当前 stamp: " (stamp-text (:actual conflict)))))))
           (when-let [error (:error program)]
             (dom/div #js {:className "hc-protected-program__conflict"}
                      (str "错误 " (or (:code error) (:reason error) "unknown")
                           (when-let [message (:message error)]
                             (str " · " message)))))
           (when (and (:conflict? program) (fn? on-retry))
             (dom/div #js {:className "hc-protected-program__actions"}
                      (dom/button #js {:type "button"
                                       :className "hc-button"
                                       :data-retry-program (:job-id program)
                                       :onClick (fn [_]
                                                  (on-retry {:job-id (:job-id program)
                                                             :task-id (:task-id program)
                                                             :claim-id (:claim-id program)
                                                             :path (:file-path program)}))}
                                  "重读并重试")))))

(defn- worker-programs-element
  [dashboard]
  (let [programs (:worker-programs dashboard)
        summary (:worker-program-summary dashboard)
        on-retry (:on-retry-program dashboard)]
    (when (seq programs)
      (dom/section #js {:className "hc-protected"
                        :aria-label "受保护提交结果"}
                   (dom/h4 nil
                           (str "受保护提交 · " (:total summary 0)
                                " 次 · " (:conflicts summary 0)
                                " 次冲突 · " (:applied summary 0)
                                " 次已应用"))
                   (dom/p nil
                          "冲突表示旧补丁在写入口复核失败，真实文件保留外部修改；重读后可再次提交。")
                   (mapv #(worker-program-element % on-retry) programs)))))

(defn dashboard-element
  "Render a projected dashboard map.  This is the pure React entry used by
  tests and by hosts that already computed the projection."
  [dashboard]
  (let [dashboard (or dashboard (model/dashboard {}))
        summary (:summary dashboard)
        selected-id (:selected-swarm-id dashboard)]
    (dom/section #js {:className "hc-swarm-dashboard"
                      :aria-label "Swarm fleet 看板"}
        (dom/style #js {:key "hc-swarm-dashboard-style"} dashboard-css)
        (dom/header nil
                    (f7 BlockTitle {:className "hc-swarm-dashboard__title"}
                        "Swarm 总览")
                    (when-not (:empty? dashboard)
                      [(dom/p #js {:className "hc-swarm-dashboard__summary"
                                   :key "swarm-dashboard-summary"
                                   :role "status"
                                   :aria-live "polite"}
                              (:text summary))
                       (dom/p #js {:className "hc-swarm-dashboard__summary"
                                    :key "swarm-dashboard-master"}
                              (str "Fleet master 版本："
                                   (or (:master-version dashboard) "未报告")
                                   "。选择 swarm 行查看看板。父任务不会因子任务完成而自动标记完成。"))]))
        (worker-programs-element dashboard)
        (unresolved-calls-element dashboard)
        (if (:empty? dashboard)
          (dom/p #js {:className "hc-empty"}
                 "尚未准入任何 swarm。服务器发布快照后，fleet 会显示在这里。")
          (dom/div #js {:className "hc-swarm-fleet"
                        :aria-label "Fleet swarm 列表"}
                   (mapv #(swarm-row-element % selected-id)
                         (:swarms dashboard)))))))

(defn SwarmDashboard
  "The documented swarm/fleet dashboard component.

  This is a plain React function so it renders in a Fulcro tree, in a server
  render, or in a focused Node test without an app context.  It accepts either:

    #js {:db normalized-fulcro-database}
    #js {:swarm/dashboard projected-dashboard
         :swarm/selected-id selected-swarm-id}

  The database is projected on every render.  The component never submits a
  command and never changes a domain state.  A host that wants Fulcro query
  composition can use `SwarmDashboardFulcro`."
  [props]
  (let [db (aget props "db")
        projected (aget props "swarm/dashboard")
        selected (aget props "swarm/selected-id")
        on-ack (aget props "on-ack-call")
        on-retry (aget props "on-retry-program")
        dashboard (or projected
                      (when db (model/dashboard-from-db db))
                      (model/dashboard {}))
        dashboard (cond-> dashboard
                    selected (assoc :selected-swarm-id selected)
                    (fn? on-ack) (assoc :on-ack-call on-ack)
                    (fn? on-retry) (assoc :on-retry-program on-retry))]
    (dashboard-element dashboard)))

(defn SwarmDashboardFromDb
  "Alias for the plain React entry; kept as a descriptive name for hosts that
  already hold the normalized Fulcro database."
  [props]
  (SwarmDashboard props))

(defsc SwarmDashboardFulcro
  "Fulcro query wrapper for hosts that keep the projected dashboard in their
  database.  Query: `[:swarm/dashboard :swarm/selected-id]`.

  It requires a Fulcro app context.  Plain React and Node render tests use
  `SwarmDashboard` instead."
  [this {:swarm/keys [dashboard selected-id]}]
  {:query [:swarm/dashboard :swarm/selected-id]
   :componentName "SwarmDashboardFulcro"}
  (let [dashboard (cond-> (or dashboard (model/dashboard {}))
                    selected-id (assoc :selected-swarm-id selected-id))]
    (dashboard-element dashboard)))
