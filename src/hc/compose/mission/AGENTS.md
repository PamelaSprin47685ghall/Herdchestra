# `hc.compose.mission` 局部规格

> 权威顺序：根 `AGENTS.md` > `src/AGENTS.md` > `src/hc/compose/AGENTS.md` > 本文件。
> 本文件只记录本包的装配边界与实现选择；协议语义以根 §1.1 #8、§2–§3、§4.1、§5.2、§5.4 为准。

## 1 职责

本包是 Mission 的宿主边界，把三个独立状态机装配成一个可提交的当前态：

- `hc.core.relay`：Plan/执行层任期、`review` 台账、里程碑、上下文屏障；
- `hc.core.swarm-stm`：每个执行任期自己的看板、根任务、个体、认领与令牌槽；
- `hc.compose.nudge.policy`：中心化阶段与 Phase B 的 Nudge 判断；
- `hc.compose.mission.worker`：每个 Plan tenure 唯一的 dedicated worker 合同；
- `hc.compose.mission.model`：主模型与任务模型的归属规则。

本包负责：

- 把 `review` 返回的台账键变成真实看板根任务，并把根任务完成、取消、替代映射回台账；
- 在同一个 host 提交中完成“退休旧任期 + 写上下文屏障 + 开启继任者 + 替换角色绑定”；
- 为每个执行任期维护自己的看板、Reviewer 绑定、swarm actor 绑定与根任务映射；
- 在注册、认领、`take-or-die`、`finish-root-task` 时同步模型归属与生命周期；
- 为 Nudge policy 提供带具体 tenure 与 board 的 observation facts；
- 对 Plan dedicated worker 执行复用/steer、死亡重建、报告原样返回；
- 拒绝迟到任期请求，拒绝模型归属漂移，拒绝未审阅 tenure 写底稿。

本包不负责：

- 看板命令本身的语义、拆分、父项恢复、责任公式（`hc.core.swarm-stm`）；
- 台账、里程碑、屏障与 quiescence 判定（`hc.core.relay`）；
- 文件读取、`stamp`、`$W$`、dry-run（`hc.core.workspace-gate`）；
- 模型 HTTP 流、工具沙箱、进程管理、持久化（`hc.server.*`）；
- 多 worktree fork/join（`hc.compose.fleet`）；
- 操作员 Esc 取消（`hc.compose.cancel`）。

## 2 状态模型

`MissionHost` 持有一个不可变 mission state 的 atom。状态是普通 EDN：

```clojure
{:relay <relay state>
 :boards {execution-tenure-id <swarm board>}
 :bindings {:plan <plan binding>
            :plan-worker {:worker <worker-or-nil> :next-seq n}
            :execution {tenure-id {:reviewer <binding>
                                   :control-agent-id <board actor id>
                                   :swarm {agent-id <binding>}
                                   :root-tasks {board-task-id
                                                {:ledger-key "..."
                                                 :relay-task-id :root-task-1
                                                 :state :ready|:done
                                                 :claim-id ...}}}}}
 :models {:main "..." :task "..."}
 :retired {retired-tenure-id {:lane ... :barrier-id ... :successor-id ...}}
 :requests {request-id {:op ... :payload ... :receipt ...}}
 :reopens [{:from-tenure-id ... :to-tenure-id ... :reason ... :user-input ...}]
 :extensions {:pair-hint <opaque session>}}
```

角色绑定至少包含：

```clojure
{:role :plan | :reviewer | :worker | :holder
 :model-role :main | :task
 :model-id "<host-selected concrete id>"
 :tenure-id "..."
 :agent-id ...        ; swarm roles
 :status :active | :completed | :dead
 :life-state :alive | :dead
 :phase :review | :swarm | :handoff | :completed
 :milestone ...}
```

Plan 绑定额外保存 `:reviewed-in-tenure?`。它只在 Plan tenure 换任时复位；同一 tenure 内里程碑推进不复位。底稿写锁以它为准，而不是以会随里程碑重置的 `:first-review?` 为准。

## 3 提交协议与原子屏障

所有命令遵循：

1. 读取当前 committed state；
2. 用纯函数计算候选 relay state、候选 board、候选绑定与 effect；
3. 任一步失败：返回原 state，不触碰 atom；
4. 用一次 `compare-and-set!` 发布候选 state；
5. 只有 CAS 成功后才调用 `:dispatch!` 派发 effect；
6. effect 抛错只记录为 `:dispatch-errors`，不回滚已提交状态。

因此：

- `review` 的台账、蜂群根任务、执行看板与 Reviewer 绑定一起出现；
- 接力屏障的旧任期退休、上下文屏障、继任者 tenure、继任者模型绑定、旧看板/旧绑定移除一起出现；
- 不存在“屏障已写、台账未推进”或“继任者已建、旧绑定仍活”的中间态；
- CAS 失败只重试纯 transition，不重放外部动作；
- 重放 `review` 的 call-id 由 relay 返回原回执且无 effect；host 不重复建根任务。

`finish-root-task!` 在同一个候选里先算 board finish，再用该候选的 board snapshot 调 `relay/finish-root-task`。relay 拒绝时整个候选丢弃，board 的完成也不会提交。这是本包对“原子接力屏障在宿主边界”的具体含义。

## 4 角色生命周期

### 4.1 Plan

- `create` 建立一个 `:S1` Plan tenure，绑定主模型，`reviewed-in-tenure? false`。
- 初任 S1 空账返回 `:ask`（“我需要干什么？”），绑定保持 main，Nudge 抑制。
- `resume!` 只清除 `:awaiting-user?`，不产生 review。
- Plan 非空台账只更新 Plan 台账，不建蜂群。
- Plan 自己用 `nil` 值销账；最后一个键解除时 relay 退休当前 tenure，host 同时清除 Plan dedicated worker 并绑定新的 Plan tenure。
- 同一 Plan tenure 内 `:S1 -> :S2 -> :S3 -> :pending` 由继任者空首评推进；绑定里程碑随 relay 同步。
- `:pending` 空账创建执行 tenure；host 同时建该 tenure 的看板与 Reviewer 绑定。Plan tenure 保持活跃，底稿冻结。
- `set-draft!` 的写锁是“本 tenure 第一次被接受的 review 之前只读”。Plan 阶段的非空台账、空账推进、销账都算 review；S1 的初始 `ask` 不算。
- Plan review 必须点名它所属的 tenure。Plan 换任后，旧 tenure 的迟到 review 由 host 返回 `:TENURE_RETIRED`，不会写入继任者的台账，也不会解开继任者的底稿写锁。
- 首稿路径由 Owner 事务化 `:claim-draft` 预留：`claim-draft-state` 在
  `:draft-claim` 记录 path/request-id；并发不同名返回
  `:DRAFT_NAME_TAKEN`，同 request-id 同 path 重放，同 path 不同 request
  允许但只保留首个 claim。`set-draft` 成功后清除 claim；失败程序用
  `:release-draft` 按 request-id 清除。workspace 桥在 `$W$` 内先 claim，
  再检查目标缺失，失败时释放；目标在真实写前出现返回 `:DRAFT_NAME_TAKEN`。
- `:check-draft`、`:claim-draft` 与 `:set-draft` 都必须点名当前 Plan
  tenure（`:tenure-id`）。pinned tenure 已退休返回 `:TENURE_RETIRED`，
  其他不匹配返回 `:UNKNOWN_TENURE`，nil 也拒绝；旧 tenure 的迟到写因此
  不会借用继任者的写锁、底稿路径或 claim。`:check-draft` 是只读检查，
  不创建 claim；workspace commit 在 dry-run 前用它，dry-run 后再用
  `:claim-draft` 在 Owner 事务内固定资格。
- claim 记录 `:owner-instance-id`。`restore` 收到新的 Owner 实例身份时，只有
  当 claim 带有可比较的旧实例且二者不同，才证明旧 continuation 已死并清除；
  同实例、缺失实例或无法比较时保留 claim（不确定责任），不猜成安全。
- 底稿写入桥：`mission/set-draft!` 与 Owner `:plan/write-draft` 已实现。`hc.compose.mission.control/draft-command` 与 `applied-draft-paths` 负责把 Plan 工具写出的 `plans/<name>.md` 转成 Owner 命令；app adapter 必须在 Plan 程序成功后读取已应用内容并提交该命令，同时带上工具调用的 pinned `:tenure-id`。否则 handoff 会以 `:DRAFT_MISSING_OR_EMPTY` 拒绝，或迟到内容被 `:TENURE_RETIRED` 拒绝。
- `:pending` 拒绝非空 findings（relay 错误码原样返回）。
- Plan 处于 `:awaiting-user?` 时 `program-context` 的 `:write-unlocked?`
  为 false；`set-draft`/`claim-draft` 也会以 `:AWAITING_USER` 拒绝。

### 4.2 Reviewer

- 每个执行 tenure 一个 Reviewer 绑定，模型必须为主模型。
- Phase A：`review-window :open`。Reviewer 用只读工具核验；host 不向它暴露旧 tenure 的台账、findings 或 board。
- Phase B（`:review-window :closed` 或 `:swarm-phase :active`）：
  `program-context` 返回 `:REVIEW_WINDOW_CLOSED`，不再生成 `clj-review`
  只读上下文；relay 也会拒绝迟到的 review。
- `review` 未点名 `:tenure-id` 且只有一个执行 tenure 时，host 自动解析它；
  多个执行 tenure 时返回 `:AMBIGUOUS_TENURE`，不猜。
- 执行初任首次空账返回 `:ask`（“底稿已满足，接下来做什么？”），绑定保持 main，Nudge 抑制；同一初任只 ask 一次。
- 非空首评/继任者非空首评：host 在同一提交里关闭窗口、进入 Phase B、创建根任务并返回 `:wake :all`。
- 继任者空首评：`review` 返回 `:promoted`，同一 tenure 进入下一里程碑，绑定同步。
- `:closing` 空账：relay 要求单任期 quiescence；host 从该 tenure 自己的看板计算精确计数（含 `:unhandled-inputs`），并把 `:ff-ready?`、`:call-children`、`:report-pending`、`:pending-rebase`、`:holds-write-lock?` 等宿主事实传入。拒绝码原样返回。
- `:completed` 后任何 review 返回 `:MISSION_COMPLETED`。
- 工具路径：Reviewer 的 `clj-review` `(review {:findings ...})` 必须由 app control-port 转为 Owner `:review/submit`。`hc.compose.mission.control/review-command`/`review-message` 给出载荷；`review-command` 以程序 control context 的 `:tenure-id`/`:lane`/`:call-id` 为准，模型请求只提供 findings。未接线时 `:review` 只返回 `:UNSUPPORTED_OPERATION`，中心化评审不会实际开蜂群。

### 4.3 Swarm Worker / Holder

- `register-swarm-actor!` 只在 Phase B 的活跃执行 tenure 上注册固定槽位；board actor 角色是 `:normal`，mission 绑定角色是 `:worker`，模型必须是 task。
- `claim-task!` 走 board 的 `:claim`，保留 `:atomicity-reason` 要求。
- `take-or-die!` 走 board 的不可逆竞争；赢家绑定升为 `:holder`，输家绑定标 `:dead`。若同一 board 提交内 holder 已无责退休，绑定也标 `:dead`。
- `finish-root-task!` 只允许根任务；它同时完成 board finish 与 relay 台账释放。最后一个根任务且 board quiescent 时触发接力屏障。
- `cancel-root-task!` 只允许叶子根任务；它同时移除 board task、board ledger 项、relay 根任务镜像与 relay 台账键。最后一个根任务被取消时同样尝试接力屏障；board 未 quiescent 时留下 `:relay-pending` 供 `maybe-relay!`。
- `replace-root-task!` 只允许叶子根任务；board 保留同一 `:ledger-key` 并创建替代任务，relay 根任务镜像更新 goal/remaining-work/done-when，台账义务保持开放，因此不触发屏障。
- `reopen-root-task!`（或 `:board-command` 的根 `:reopen`）只恢复一个已 `:done` 的根任务：board task 回 `:ready`、board ledger 重新登记 `:ledger-key`、relay 根任务镜像回 `:ready`、relay 台账恢复镜像中的原始义务文本、host 根绑定回 `:ready`，并清除 `:relay-pending?`。这四者在同一候选状态中提交；任务之后必须再次走正常根 `finish` 才能解除义务并触发屏障。非根任务仍走纯 board `:reopen`。
- `abandon-swarm!` 按 `:swarm-id`（可选 `:slice-id` 定位根任务）找到执行 tenure：`fleet-<swarm-id>` 直接命中，否则按唯一 `:ledger-key` 匹配。它在同一转换中把该 tenure 标 `:abandoned`（记录 reason/at）、清空 relay 台账与根任务镜像、移除该 tenure 看板的全部 task/claim/action/waiter、释放令牌、把 holder 降为普通角色并记录 `:swarm-abandoned` 看板事件；其它执行 tenure 不受影响。Plan tenure 永不参与；重复调用幂等。看板不变量在提交前复核，非法候选整体拒绝。
- relay 根任务镜像保存 `:original-obligation`（首次由 Reviewer 台账写入，
  `replace-root-task` 不覆盖它）；`reopen` 优先恢复该文本，因此替代后的
  重开不会把替代目标误当原义务。
- 旧任期的 board、swarm 绑定与 Reviewer 绑定在屏障提交中删除；迟到请求由 host 返回 `:TENURE_RETIRED`。
- 普通 worker 不直接宣布里程碑晋级；晋级只能由中心化 Reviewer 的空首评发生。

### 4.4 完成后的操作员重开

`reopen-state`（`apply-command` 的 `:op :reopen`）只接受真实操作员消息：

- `:reason` 必须是 `:operator-message`，否则 `:REOPEN_NOT_OPERATOR`；
- `:user-input` 必须非空，否则 `:INVALID_OPERATOR_INPUT`；
- `:user-attachments` 可选，只保留附件引用与显示元数据，内容/推理字段被丢弃；
- `:request-id` 必须非空，用于幂等，否则 `:REQUEST_ID_REQUIRED`；
- 没有任何执行 tenure 同时为 `:status :completed` 与 `:milestone :completed` 时拒绝 `:MISSION_NOT_COMPLETED`。

成功后：

- 已完成 tenure 原样保留在 relay `:lanes :execution` 中，作为终局证据；不原地改状态；
- 新建执行 tenure（`:milestone :execution`、`:initial? false`、`:first-review? false`）与全新 board、主模型 Reviewer 绑定；
- relay `:user-input` 更新为操作员文本并追加到 `:user-inputs` 原文历史；`:user-attachments` 以引用元数据形式追加，内容与推理字段被丢弃；`barriers`、`retired-tenure-ids`、`:receipts`、底稿、session-id 与已完成 tenure 全部保留；
- 新 tenure 的空首评走独立继任接力（不是再次 `ask`，也不直接晋级）；非空首评进入 Phase B；
- `:reopens` 追加 `{:from-tenure-id :to-tenure-id :reason :operator-message :user-input}` 审计记录；
- effect 是 `:execution/start`（带 `:reopen? true`、新 tenure、Reviewer 绑定、操作员文本）。

`reopen` 的协议检查只证明调用载荷声明了操作员来源；真正的“只有操作员命令分支可调用”由 Owner 的入口保证。agent/internal 消息不得路由到该 op。

## 5 Plan dedicated worker 合同

`hc.compose.mission.worker/contract`：

```clojure
{:role :plan-worker
 :model-role :task
 :board? false
 :token? false
 :write? false
 :one-per-tenure? true
 :reuse :steer
 :report :verbatim
 :capabilities #{:cat :stat :ls :where :whereAll :park :unpark :call}
 :forbidden-capabilities #{:write :mkdir :rm :mv :split :claim :finish
                           :cancel :replace :release :reopen :comment
                           :update :take-or-die :review :board :token
                           :wake-all :ask}}
```

- 每个 Plan tenure 至多一个 live worker；`devops-request!` 优先复用并 steer 同一个 `:worker-id`，没有 `:DEVOPS_IN_FLIGHT` 拒绝。
- worker 死亡或 Plan tenure 换任后，下一次请求创建一个全新 `:worker-id` 与递增 `:generation`；不存在 `:PLAN_DEVOPS_DEAD`。
- `devops-complete!` 只接受当前 worker 的 pending call；空闲 worker 的
  迟到报告返回 `:STALE_WORKER_CALL`。报告按原字符串保存并返回，不摘要、
  不截断、不转 artifact。
- `devops!` 是宿主侧同步便利：先提交 request，再在提交之外调用注入的 `:run`，最后提交 report。不得在 Owner 事务内调用。
- `devops-request!` 以 call-id 幂等；同一 call-id 不同载荷返回
  `:REQUEST_ID_CONFLICT`。call-id 缓存在独立的 `:devops-requests` 命名
  空间，不与根任务的 `:request-id` 混用。

## 6 模型归属

| 角色 | 模型 |
|---|---|
| Steward / Plan / Reviewer | 主模型（`:main`） |
| Plan dedicated worker | 任务模型（`:task`） |
| Swarm Worker / Holder | 任务模型（`:task`） |

`model/bind-role` 同时记录 `:model-role` 与具体 `:model-id`；`check-invariants` 同时核对角色类别与具体 id。配置缺失、空字符串、角色未知或绑定漂移都是硬错误，不在运行时猜测默认模型。

## 7 根任务镜像与看板快照

- relay 的 `:swarm/start` root task 是镜像描述；host 用该执行 tenure 的 board 控制 actor 创建真实根任务，并建立 `board-task-id -> ledger-key` 绑定。
- 根任务 `:owner-id nil`、`:needs-token false`，由 swarm 个体按公共可接续集合认领。
- `finish-root-task!` 用 board task 的 `:ledger-key` 调 relay，因此不依赖 relay 镜像 task-id 与 board task-id 相同。
- `board-snapshot` 的 board 计数精确；宿主事实由调用者传入。`ff-ready?` 默认 false，收尾必须显式 true。`:unhandled-inputs` 直接数该 tenure 看板的公开输入；relay 屏障与收尾都要求它为 0，否则用户输入会在退休/结案时失去唯一当前副本。`:user-inputs` 只导出已处理输入的原文、`:message-id` 与附件引用元数据（`{:text ... :message-id ... :attachments [...]}`），不包含附件内容、resolution、摘要、评论或动作；relay 据此把用户原文与附件引用带入继任上下文。
- 每个执行 tenure 有自己的 board；多个执行 tenure 可以同时存活，各自的根任务、台账、Reviewer、屏障互不影响。

## 8 Nudge

`hc.compose.mission/decide-nudge` 把 committed host 状态转成 `hc.compose.nudge.policy/observation`：

- Plan 选择当前 Plan tenure；
- Reviewer/worker/holder 必须给 `:tenure-id`，否则在多个执行 tenure 时由 policy 返回 `:ambiguous-tenure`；
- board 来自该 tenure 自己的 `:boards`；
- 中心化文本仍由 `hc.core.relay/central-nudge-message` 生成；
- Phase B worker 文本仍由 `hc.compose.nudge.policy/worker-message` 生成，不使用 review 台账；
- `:awaiting-user?`、Phase B 的 Reviewer、已完成 tenure 与合法出口继续抑制 Nudge。

## 8.1 实际 actor 运行时（`hc.compose.mission.actors`）

纯 mission 状态机之上还有一层真实槽位生命周期：

```clojure
(actors/new-state {:models {:main .. :task ..}
                   :slot-ids [:slot-a :slot-b]
                   :workspace-root "..."          ; Plan/master worktree
                   :workspaces {tenure-id "..."}}) ; per-swarm worktree
(actors/sync-tenure mission actors tenure-id)
(actors/wake-all mission actors tenure-id)
(actors/accept-model-event mission actors attempt-id event)
(actors/plan-worker-request mission actors {:call-id .. :verbose-intent ..})
(actors/public-board-snapshot mission tenure-id)
```

- `sync-tenure` 将固定槽位与 board actor 对账。缺失/死亡 actor 在同一转换中换新 `agent-id`、递增 generation、清空私有 context，并为旧在途 attempt 产生 `:model/cancel`。
- `wake-all` 只启动 idle 且有身份的槽位。`wake-all-skipping` 接受 `skip-agent-ids`，让被准入拒绝上限封禁的 actor 保持 `:idle`，不把槽位标成 `:running` 去等待一个调用方不会派发的 effect；成功准入后同一 actor 仍可被唤醒。每个 `:model/start` 请求的 provider 角色是 `:role :task`，mission 角色单独放在 `:mission-role :worker|:holder`；另带 `:model-role :task`、`:model-id`、`:program-role :clj-worker`、`:holder?`、`:workspace`、`:board-snapshot` 与仅该 agent 的 context。不会携带 Reviewer ledger、findings 或其他 agent 的消息。模型适配器只接受 `:main`/`:task`；mission 角色不得直接作为 provider role。
- `accept-model-event` 把 `:chunk`/`:completed`/`:failed`/`:cancelled` 结算回该槽位；完成只追加该 agent 的 assistant 消息。
- `accept-tool-result` 把一次工具结果追加到调用它的 agent 私有 context：
  `{:role :tool :tool-call-id ... :name ... :text ... :error? ...}`，与
  `hc.compose.fleet.actors/continue-after-tool!` 的消息形状一致。它按
  `:actor-attempt-id` 找到父 model call 的槽位，因此结果始终关联到正确的
  call/agent；槽位转世后旧身份的结果只留证据、不写入新 context；同一
  `:tool-call-id` 重复投影不追加第二次。
- `plan-worker-request` 复用/steer 同一 Plan dedicated worker；provider 角色为 `:role :task`，`:mission-role :plan-worker`，另带 `:model-role :task`、`:program-role :dedicated-worker`、Plan workspace，context 仅属于该 worker。
- `public-board-snapshot` 是实际公开看板视图（tasks/actors/token/inputs/derived），不含台账与私有对话。
- `board-work-prompt` 的 EDN+UFSS 块对 `:goal`、`:remaining-work`、`:done-when`、`:handoff-note` 各按 UTF-8 截断到 4096 字节（按码点推进，不拆代理对）；被截断的任务 map 带 `:truncated? true` 与 `:full-bytes`（该任务被截断字段的最大原始字节数），未截断时二者不出现。整块上限 32768 字节；任何超限裁剪都带 `:board-truncated? true` 与 `:omitted-task-count`/最多 20 个 `:omitted-task-ids`（任务未省略时计数为 0），裁剪顺序为“尾部优先、根任务与可推进任务最后”；极端情况下责任列表按尾部省略并带 `:responsibility-truncated? true`/`:omitted-responsibility-count`，仍超限抛 `:PROMPT_TOO_LARGE`。未超限时输出保持既有逐字形状。
- `public-board-snapshot` 对同一组字段执行同样的逐字段上限；仅超限时附加 `:truncated?`/`:full-bytes`。

Owner 集成：

- Owner state 在 `:mission` 旁持有 `:actors`，二者在同一 store CAS 提交。
- P3 `:wake-actors` guard 调用 `sync-tenure` + `wake-all`，把 `:model/start` 变成普通 `:kind :model` Owner call，并写入 `:actor-attempt-id`。现有 claim/dispatch/model-event 路径负责真实模型调用。
- Owner model `:completed`/`:failed`/`:cancelled` 对带 `:actor-attempt-id` 的 call 调用 `accept-model-event`，与 call terminal 同一状态提交。`:call/terminal` 与 `:dispatch/failed` 走同一路由；gateway `:start!` 返回 nil 或抛异常时，dispatch 产生的 failed terminal 会把槽位从 `:running` 释放回 `:idle`，随后 `wake-actors` 可启动新 turn。旧身份迟到 terminal 由 `finish-swarm-call` 的 stale-agent guard 丢弃。
- Owner 工具调用从当前 model call 的 program context 继承 `:program-role`、`:holder?`、`:s1?`、`:write-unlocked?`、`:draft-path`、`:workspace`、`:board-snapshot`，避免工具退化成默认 `:clj-worker`。
- Owner 的 `:project-tool-result` 按父 model call 分流：父 call 带
  `:actor-attempt-id` 时结果只进该 agent 的私有 context，绝不写入中央
  `:conversation`；普通工具结果仍投影到 conversation。父 attempt 记录缺失时
  用 event 的 `:tenure-id`/`:agent-id` 回退到活槽位，责任仍可恢复；找不到
  活 agent 时在 call 上记 `:result-delivery {:status :undeliverable
  :code ...}`、记 `:TOOL_RESULT_UNDELIVERABLE` 诊断并发一次
  `:owner/blocked`，call 仍标记已投影，因此不泄漏、不空转。成功投递记
  `:result-delivery {:status :delivered}`；槽位转世记 `:stale-agent` 并把
  `:actor/stale-tool-result` 放进 outbox。
- `:workspace` 必须是该 swarm 的真实 worktree；fleet 把 `fork` 结果的 `:workspace` 填入 `:workspaces[tenure-id]`。不得让 swarm 工具跑在 master 工作区。
- `hc.compose.mission.worktree/execute-program!` 是 app/fleet launcher 的工具执行适配器：按 `:tenure-id` 或精确 `:workspace` 选择 runtime（支持 fleet 的 `:workspace-runtime` 形状），拒绝 `:WORKSPACE_NOT_FOUND`/`:WORKSPACE_MISMATCH`，绝不回退到 master。
- app/fleet 的 launcher 契约：`fleet.service/runtime` 或 `runtime-for-workspace` 取 per-worktree runtime；`fleet.service/actor-state` 取 actor state；`:model/start` 交真实模型 gateway；tool call 交 `mission.worktree/execute-program!`；事件回 `fleet.service/accept-actor-event!`。不需要第二个 workspace 工厂。
- Holder 的 `(call ...)` 是黑盒 Shell，不走 control-port；app 必须为每个 worktree program runtime 注入 `:call` 端口（worktree 的 `:root`/`:gate`/`:kernel` + `shell-board/swarm-board` + `program/shell-call-port`）。未接线时 holder 在 swarm worktree 无法运行 Shell；这是 app 装配缺口，不是 `mission.worktree` 的 board 路径。
- Holder 的程序角色必须是 `:clj-worker` + `:holder? true`，绝不能是 `:dedicated-worker`。`:dedicated-worker` 没有 write/split/claim/finish/take-or-die，且会跳过静态 holder Shell 门。`mission.worktree/execute-program!` 对 `:holder? true` 的请求强制归一为 `:clj-worker`，作为对 app 映射错误的防线；动态 token/claim/W 校验仍由 shell board 与 workspace gate 承担。

Plan dedicated worker 的外部 runner 在 `hc.compose.mission.devops`；Owner/app 通过 `mission/devops!` 的 `:run` 或 app adapter 调用。`devops-complete-state` 回执保留：
`:worker-result-status`（`:ok`/`:failed`/`:interrupted`）、`:worker-status`（同一结果状态）、`:worker-lifecycle-status`（`:idle`）与逐字 `:report`。
worker 的 `call` 是 best-effort 只读；工具结果 map 以
`:workspace-may-have-changed true` 与 `:note`（`"workspace may have changed;
re-read the facts you rely on"`）合入，整体由 `hc.core.ufss/write-str`
渲染，提示 Plan 在依赖调查结论前重新核对现场。

提示词边界：actor 请求只包含 board-derived work prompt；COMMON/角色系统提示词、Pair Hint 与 Scope 投影由 app/model gateway 在出站副本上注入一次，不得在每个 actor prompt 重复 Scope 规则。当前 `actors` 尚未直接调用 `hc.core.pair-hint`/`hc.core.scope`；这是 app gateway 的接线责任。

## 8.2 程序控制适配（`hc.compose.mission.control`）

```clojure
(control/review-command ctx request) ; Owner :review/submit payload
(control/review-message ctx request) ; internal Owner message
(control/draft-command path content tenure-id) ; Owner :plan/write-draft payload
(control/applied-draft-paths result) ; plans/<name>.md changed paths
(control/draft-path? path)
```

app control-port 必须把 Reviewer 的 `:review` 转成 `:review/submit`，并在 Plan 程序成功后把 `:files/applied` 中的草稿路径读回并提交带 pinned `:tenure-id` 的 `:plan/write-draft`。未接线时中心化评审与 handoff 都不会真实工作。

## 9 公开 API

### 9.1 纯状态入口（Owner 集成）

Owner 必须把 mission state 放进自己的 committed state，并在同一个 store
CAS 中提交 mission state 与 call/conversation/report/cancel 状态。纯入口
不创建 atom，不派发 effect：

```clojure
(mission/initial-state opts) -> mission-state
(mission/restore state) -> {:ok? true :state mission-state}
                        | {:ok? false :state state :error {...}}
(mission/apply-command state command)
  -> {:ok? true  :state state' :receipt r :effects [e ...]}
  |  {:ok? false :state state  :error {:code ... :message ... :details ...}
      :effects []}
```

`initial-state` 选项：`:session-id`、`:user-input`、`:user-attachments`、`:draft`、
`:successor-hint`、`:plan-milestone`、`:models {:main <id> :task <id>}`。
`restore` 接受 mission state 或带 `:mission` 的 Owner state；它不会把旧的
顶层 `:relay`/`:board` 自动猜成 per-tenure boards 与角色绑定。旧状态需要
一次性迁移，或从 `initial-state` 重新开始。

`apply-command` 的 `:op`：

```text
:review :resume :set-draft :maybe-relay
:register-swarm-actor :claim-task :take-or-die :finish-root-task
:cancel-root-task :replace-root-task :reopen-root-task :abandon-swarm
:reopen
:extensions-put
:board-command :devops-request :devops-complete
```

`:board-command` 形状：

```clojure
{:op :board-command
 :tenure-id "tenure-3"          ; 省略时仅接受唯一执行任期
 :command {:op :split ...}      ; 原始 swarm board 命令
 :env {:write-gate-proof <opaque> ; 可选；不透明能力，EDN 值无效
       :verify-read-set f}}
```

`:register-actor`、`:claim`、`:take-or-die` 在 `:board-command` 内会委托给
对应的类型化转换。`:board-command` 的 `:finish` 若目标是根任务，会自动
委托给 `:finish-root-task`；普通子任务仍走纯 board finish。根任务的
`:cancel`/`:replace`/`:reopen` 会自动委托给
`:cancel-root-task`/`:replace-root-task`/`:reopen-root-task`，
在同一个状态转换中同步 board ledger、relay 根任务镜像与 relay 台账。
`:create-task` 也被拒绝；根任务只由 Reviewer 台账生成，worker 用 `:split`
创建子项。
`:finish-root-task` 的 `:env` 与顶层 `:write-gate-proof`/`:verify-read-set`
两种写法都接受。`:write-gate-proof` 是 `hc.compose.mission.proof` 的不透明
单例，只有真正持有 W 的 workspace bridge 才附加；布尔值、字符串、关键字、
映射等 EDN 值一律被忽略。非空 `:read-set` 必须在写入口内由 verifier 校验；
调用者已在 W 内核对过时可传 `:verified-read-set`（仍需该能力），board 直接
把它记为 `result-ref` 的 `:read-set` 与 `:verified-read-set` 依据。

`:extensions-put` 用于持久化不归 mission 解释的扩展值：

```clojure
{:op :extensions-put :key :pair-hint :value pair-hint-session}
```

Pair Hint 使用 `[:extensions :pair-hint]`。Owner 必须从自己的单写者入口调用
并把返回的 `:mission` 与 conversation/calls 在同一 store CAS 提交；gateway
atom 只能作为缓存，重启后从该键恢复。

纯访问器：

```clojure
(mission/relay-of state)
(mission/board-of state tenure-id)
(mission/current-board state)          ; 唯一执行任期的 board，否则 nil
(mission/tenure state tenure-id)
(mission/board-summary state tenure-id facts)
(mission/nudge-facts state facts)
(mission/decide-nudge-state state facts)
(mission/check-invariants state)
```

`board-summary` 返回 relay 需要的扁平快照，包含 `:unresolved-tasks`、
`:unhandled-inputs`、`:user-inputs`、`:inflight-actions`、`:applying`、`:running`、`:uncertain`、
`:holder-retired?`、`:all-root-tasks-done?`、`:ff-ready?`、
`:call-children`、`:report-pending`、`:pending-rebase`、
`:holds-write-lock?`、`:unresolved-token-tasks`。宿主事实由 `facts` 提供；
board 计数由该 tenure 自己的 board 计算。

### 9.2 便利 host（测试/工具）

`create` 与 `restore-host` 是 atom 包装，仅供测试、工具或单进程宿主使用；
Owner 不应使用它们作为第二权威。

```clojure
(require '[hc.compose.mission :as mission])

(mission/create opts)
(mission/restore-host state opts)
(mission/apply-command! host command)
(mission/snapshot host)
(mission/relay-state host)
(mission/models host)
(mission/model-for-role host role)
(mission/role-binding state {:role ... :tenure-id ... :agent-id ...})
(mission/review! host request)
(mission/resume! host request)
(mission/set-draft! host request)
(mission/maybe-relay! host request)
(mission/register-swarm-actor! host request)
(mission/claim-task! host request)
(mission/take-or-die! host request)
(mission/finish-root-task! host request)
(mission/cancel-root-task! host request)
(mission/replace-root-task! host request)
(mission/reopen-root-task! host request)
(mission/reopen! host request)
(mission/devops-request! host request)
(mission/devops-complete! host request)
(mission/devops! host request)
(mission/board-snapshot board facts)
(mission/nudge-facts state facts)
(mission/decide-nudge host facts)
(mission/decide-nudge-state state facts)
(mission/check-invariants state)
```

`create` 必填 `:models {:main <id> :task <id>}`。可选 `:session-id`、
`:user-input`、`:user-attachments`、`:draft`、`:successor-hint`、`:plan-milestone`、
`:dispatch!`。

命令返回：

```clojure
{:ok? true  :state state' :receipt receipt :effects [effect ...]}
{:ok? false :state state  :error {:code ... :message ... :details ...} :effects []}
```

成功提交后可能带 `:dispatch-errors`。回执表示已受理并已提交，不表示异步任务完成。

## 10 错误码

本包新增或转发的宿主边界错误：

`:INVALID_MODELS`、`:UNKNOWN_ROLE`、`:MODEL_ROLE_MISMATCH`、`:MODEL_ID_MISMATCH`、
`:WRITE_LOCKED_BEFORE_FIRST_REVIEW`、`:DRAFT_PATH_LOCKED`、`:AWAITING_USER`、
`:TENURE_RETIRED`、`:UNKNOWN_TENURE`、`:TENURE_LANE_MISMATCH`、`:NOT_IN_SWARM`、
`:MISSION_BOARD_MISSING`、`:UNKNOWN_ACTOR_BINDING`、`:UNKNOWN_TASK`、`:NOT_A_ROOT_TASK`、
`:RELAY_ROOT_TASK_MISSING`、`:ROOT_TASK_CANCEL_REQUIRES_REVIEW`、
`:MISSION_NOT_COMPLETED`、`:REOPEN_NOT_OPERATOR`、`:INVALID_OPERATOR_INPUT`、
`:REQUEST_ID_CONFLICT`、`:CALL_ID_REQUIRED`、`:VERBOSE_INTENT_REQUIRED`、
`:INVALID_WORKER_STATUS`、`:INVALID_WORKER_REPORT`、`:STALE_WORKER_CALL`、
`:WORKER_PENDING_STATE_INVALID`、`:UNKNOWN_WORKER`、`:WORKER_DEAD`、
`:AMBIGUOUS_TENURE`、`:MISSION_TRANSITION_FAILED`。

relay 与 board 的既有拒绝码原样返回，包括 `:CALL_ID_PAYLOAD_CONFLICT`、`:REVIEW_WINDOW_CLOSED`、
`:NONEMPTY_FINDINGS_AT_PENDING`、`:DRAFT_MISSING_OR_EMPTY`、`:BOARD_SNAPSHOT_REQUIRED`、
`:RESOURCES_NOT_QUIESCENT`、`:FF_NOT_READY`、`:TENURE_RETIRED`、`:CLAIM_CHANGED`、
`:WRITE_GATE_REQUIRED`、`:FILE_CONFLICT`、`:LEDGER_KEY_IN_USE`、
`:RELAY_LEDGER_CONFLICT`、`:INVALID_ATTACHMENT` 等。

## 11 不变量

`check-invariants` 检查：

- relay 自身不变量；
- 模型配置完整，所有中心化/任务绑定类别与具体 id 正确；
- Plan 绑定与当前 Plan tenure、里程碑一致；
- Plan worker 属于当前 Plan tenure，能力面只读，模型为 task；
- Plan worker 的 `:status :running` 必须有 pending call；其他状态必须没有
  pending call；
- 每个 live 执行 tenure 恰好有一个 board、一个 Reviewer 绑定；board 与绑定集合相等；
- board 控制 actor 存活；除控制 actor 外每个 board actor 都有 swarm 绑定；
- swarm 绑定角色与 board actor 角色对应（`:worker` ↔ `:normal`，`:holder` ↔ `:holder`）；
- token holder 有 live holder 绑定；
- Phase B 的 relay 根任务键集合与 board 根任务键集合一致；
- 根任务映射只指向真实 board task；
- relay `:user-inputs` 只含用户原文、可选 `:message-id` 与附件引用元数据；
- 屏障继任者有对应角色绑定；
- 已退休 tenure 不再拥有 board 或绑定。

## 12 测试

```bash
clojure -M:test -n hc.compose.mission.model-test
clojure -M:test -n hc.compose.mission.worker-test
clojure -M:test -n hc.compose.mission.host-test
clojure -M:test -n hc.compose.mission.smoke-test
clojure -M:test -n hc.compose.mission.owner-seam-test
clojure -M:test -n hc.compose.mission.surface-test
clojure -M:test -n hc.compose.mission.actor-smoke-test
clojure -M:dev:compose -e "(require 'hc.compose.mission.owner-actor-runtime-test) (clojure.test/run-tests 'hc.compose.mission.owner-actor-runtime-test)"
clojure -M:test -n hc.compose.mission.devops-test
clojure -M:dev:compose -e "(require 'hc.compose.mission.worktree-executor-test) (clojure.test/run-tests 'hc.compose.mission.worktree-executor-test)"
```

- `model_test.clj`：主/任务模型归属、配置校验、绑定漂移。
- `worker_test.clj`：合同、复用/steer、死亡重建、报告原样、过期报告、空闲
  worker 拒绝迟到报告、pending/status 不变量，以及随机生命周期的
  `test.check` 属性。
- `host_test.clj`：底稿写锁、review 幂等、根任务镜像、拒绝不半提交、原子屏障、多执行任期、dedicated worker、Nudge、收尾 quiescence；未处理看板输入阻止屏障并在处理后才退休；已处理输入原文与附件引用跨屏障且内容/resolution 不泄漏；根任务 reopen 在同一候选状态恢复 board/relay 台账、镜像与绑定；`finish-root-task` 的不透明 W 能力、`:verified-read-set` 依据投影，以及布尔/字符串/关键字/映射/普通对象伪造能力的拒绝；单执行 tenure 的 review 自动解析；devops call-id 与根任务 request-id 不冲突。
- `smoke_test.clj`：从 Plan S1 到 `:completed` 的完整确定性状态迁移，以及根任务 cancel/replace 释放/保留台账并触发接力屏障的完整场景。
- `owner_seam_test.clj`：`initial-state`/`restore`/`apply-command` 的纯状态入口、`board-command` 委托、根任务 finish/cancel/replace 的 relay 语义、操作员 reopen（拒绝码、幂等、空首评继任、非空首评进蜂群）、扁平 `board-summary`、devops 纯转换。
- `surface_test.clj`：mission role → program role、Plan S1/write-unlock、Reviewer/worker/holder、dedicated worker、ambiguous tenure 的纯 tool/model context。
- `actor_smoke_test.clj`：stub model 驱动的 `sync-tenure`/`wake-all`/`accept-model-event`，验证 @task、per-agent context、workspace/board snapshot、holder death/rebirth、Plan worker 复用。
- `owner_actor_runtime_test.clj`：真实 Owner runtime + stub model gateway；`:mission`/`:actors` 同 CAS，wake-actors guard 创建真实 model calls，model terminal 回写 actor，holder 死亡后新身份；gateway `:start!` 返回 nil/抛异常时 failed terminal 释放槽位并允许新 turn。
- `devops_test.clj`：Plan dedicated worker runner 的复用/steer、task 模型、只读工具面、报告逐字返回。
- `worktree_executor_test.clj`：真实临时 worktree + protected workspace bridge + fleet runtime 形状；`execute-program!` 实际写文件，另一个 root 不变，workspace 不匹配被拒。
- `control_test.clj`：Reviewer `:review` 载荷、Plan 草稿命令、workspace `:files/applied` 草稿路径提取。
- `actor_smoke_test.clj` 的 `late-old-terminal-cannot-overwrite-reborn-slot`：旧身份迟到终态不能覆盖转世槽位；`failed-model-turn-uses-a-system-note-not-a-bare-tool-message`：失败注记是 system message，不是缺 name 的 tool message。

## 13 明确决策

1. **每个执行 tenure 一个 board**：多个 handoff 并发时，relay 的屏障快照本来就属于具体 tenure；共享一个 board 会让一个 tenure 的未结任务阻塞另一个。代价是 board 数量随 live tenure 增长，屏障后旧 board 从当前态移除。
2. **host 使用纯 `commands/execute` 而不是 swarm kernel atom**：kernel 的 CAS 只覆盖 board，无法与 relay 同一提交。Owner 集成使用 `apply-command` 的 state-in/state-out；`create`/`restore-host` 的 atom 只是测试与工具便利。board 命令保持纯函数；代价是 host 必须自己处理 board `:request-id` 幂等，本包用 `:requests` 缓存覆盖 `take-or-die`、`finish-root-task` 与 `devops-request`。
3. **Plan 写锁按 tenure 记录**：relay 的 `:first-review?` 会在里程碑推进时重置，那是评估窗口语义；底稿写锁只应在换任时复位。host 单独记录 `:reviewed-in-tenure?`。
4. **Plan worker 在换任时清除，不在里程碑推进时清除**：一个 Plan tenure 一个 worker；S1→S2→S3 复用同一 worker，Plan 屏障后下一次 devops 创建新 worker。
5. **effect 只作派发建议**：状态已记录 successor、board、binding 与根任务；重放只返回回执，不重复派发。
6. **不复制 swarm 转换**：本包只调用 `commands/execute` 与 relay API，不重新实现拆分、父项恢复、责任公式或令牌竞争。
7. **根任务 cancel/replace/reopen 由 mission 同步 relay 镜像**：`hc.core.relay` 没有公开的 root-release API，因此 mission 在同一个候选状态中删除/更新/恢复 relay 根任务镜像与台账键。cancel 在最后一个根任务时调用 `relay/maybe-relay` 复用既有屏障逻辑；replace 保留台账键并更新镜像目标；reopen 恢复镜像状态、原义务文本与 host 绑定，并清除 pending 屏障。board 命令仍是纯函数，失败时候选整体丢弃。
8. **reopen 新建 tenure 而不是复活旧 tenure**：已完成 tenure 原样保留为终局证据；新 tenure 设 `:initial? false` 且 `:first-review? false`，因此空首评直接走独立继任接力，不会再次 `ask` 或直接晋级。操作员原文同时写入 relay `:user-input` 与 `:user-inputs`，`:reopens` 保存审计。Owner 若选择 Plan-pending handoff 路径，仍可不用此 op；两条路径都不得复活已完成 tenure。
