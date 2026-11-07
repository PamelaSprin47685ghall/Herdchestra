# `hc.core.relay` 局部规格

本包实现根 `AGENTS.md` §2.4 的义务台账、中心化评估窗口与原子接力屏障。它是纯状态机，不依赖 `swarm_stm`、`workspace_gate` 或任何存储适配器；组合层负责把返回的 `:state` 与 `:effects` 放进同一笔持久事务并派发外部动作。

## 1. 职责边界

本包负责：

- `review` 台账的增、删、覆盖与 `[]` 保持语义；
- Plan 与执行层两条独立 tenure 车道；执行层每个 tenure 持有自己的台账；
- 首次评估、`ask` 挂起/恢复、继任者首评晋级；
- 执行阶段 Phase A（只读评估窗口）与 Phase B（蜂群施工）的切换；
- 根任务完成后的台账释放与原子接力屏障；
- `收尾` 阶段的单任期 quiescence 检查与 `:FF_NOT_READY` 拒绝；
- 上下文屏障：只向继任者暴露用户原始输入、底稿、当前工作区与继任提示；
- 中心化阶段的 Nudge 文本与 `:awaiting-user` 抑制。

本包不负责：

- Swarm STM 看板的完整实现、认领、拆分、`take-or-die`；
- 文件读取/写入、`stamp` 复核、`$W$`；
- 模型调用、会话存储、进程管理；
- Plan 底稿的真实写工具；`set-draft` 只记录宿主已经接受的底稿字节，并在首次写入后锁定路径。

## 2. 状态模型

状态是普通 EDN map，可整体作为一次存储事务提交：

```clojure
{:session-id "..."
 :user-input "..."                 ; 初始/兼容的单条用户输入
 :user-inputs [{:text "..."        ; 全部原始用户输入的只读历史
                :message-id "..."   ; message-id 可选，用于多 tenure 去重
                :attachments [...]}] ; 用户附件引用/元数据，非内容
 :draft {:path "plans/x.md"        ; 固定路径
         :content "..."}           ; 底稿字节
 :successor-hint "..."             ; 系统继任提示，可为 nil
 :lanes {:plan <tenure>
         :execution {tenure-id <tenure> ...}} ; 可同时多个独立任期
 :retired-tenure-ids #{...}
 :receipts {call-id {:payload ... :receipt ...}}
 :barriers [...]                   ; 可修剪的审计记录；barrier-seq 保持单调
 :next-tenure-seq 2
 :next-task-seq 1
 :barrier-seq 0}
```

Plan 车道同一时刻只有一个 live tenure，并在启动执行层后仍保持 `:active`（§2.3：启动执行不会结束规划任务）。执行车道是 `tenure-id -> tenure` 的 map：每一次不同的空账 handoff 都创建一个独立执行任期，多个执行任期可以同时存活；每个任期有自己的台账、Reviewer 窗口、根任务与接力屏障。

`:user-inputs` 只保存用户原文、可选消息标识与用户附件引用元数据；不得写入 resolution、摘要、评论、推理、附件内容或任何看板派生解释。附件只允许稳定引用与显示元数据（`:attachment-id`/`:blob-id`/`:send-blob-id`/`:name`/`:filename`/`:mime`/`:size`/`:local-copy?`/`:send-route`/`:url`）。接力时把该任期看板中已处理的输入原文与附件引用并入此历史，屏障 `:context :user-input` 由原文拼接、`:context :user-attachments` 提供附件引用；未处理输入先阻止屏障。

Tenure 关键字段：

```clojure
{:tenure-id "tenure-2"
 :epoch 2
 :lane :plan | :execution
 :role :plan | :reviewer
 :milestone :S1 | :S2 | :S3 | :pending
            | :execution | :closing | :completed
 :status :active | :completed
 :initial? true | false
 :first-review? true | false       ; 本里程碑是否尚未提交会改变台账或进入 Phase B 的 review
 :asked? true | false              ; 初任空账 ask 只发生一次
 :awaiting-user? true | false      ; 抑制 Nudge，拒绝新 review
 :review-window :open | :closed
 :swarm-phase :none | :active
 :relay-pending? true | false
 :ledger {key value}
 :root-tasks [...]}
```

`retired` tenure 从 `:lanes :execution` 中移除；旧 `:tenure-id` 进入 `:retired-tenure-ids`，用于把迟到请求稳定拒绝为 `:TENURE_RETIRED`。同一 `call-id` 的成功回执在 `:receipts` 中跨接力保留，重放优先于退休检查。

## 3. 台账与 review 语义

`review` 请求：

```clojure
{:call-id "..."          ; 必填，非空字符串
 :lane :plan | :execution
 :tenure-id "..."
 :findings [{:key "k" :value "v"} ...]
 :board {...}}           ; 执行层接力/收尾时需要
```

`findings` 规则：

- `:value` 为非空字符串：新增或覆盖 `:key`；
- `:value` 为 `nil`：解除 `:key`；
- `[]`：保持当前台账原样，不清空；
- 同一向量内重复 `:key` 按顺序应用，后者胜出；
- `:key` 必须是非空字符串；`:value` 必须是 `nil` 或非空字符串；否则 `:INVALID_FINDINGS`。

执行层只有在应用后的台账非空时才进入 Phase B；全部为 `nil` 且原台账为空时是 `:noop`，不会创建空蜂群。Plan 车道同理。

`:reviewed?` 表示这次调用被受理并参与评审判定；`:noop` 与 `:ledger-unchanged` 也返回 true。`:first-review?` 只表示“本里程碑尚未提交会改变台账或进入 Phase B 的 review”，因此这两类调用不会清掉它。宿主判断“本任是否已 review 过”应使用回执的 `:reviewed?`，不要把 `:first-review?` 当成同一含义。

返回：

```clojure
{:ok? true
 :state state'
 :receipt {:status :accepted
           :call-id "..."
           :lane ...
           :outcome :ask | :promoted | :completed | :ledger-updated
                    | :ledger-unchanged | :noop | :swarm-started
                    | :relayed | :relay-pending | :execution-started
           :reviewed? true | false
           ...}
 :effects [...]}

{:ok? false
 :state state
 :error {:code :TENURE_RETIRED :message "..." :details {...}}
 :effects []}
```

同一 `call-id` 同一载荷重放时返回原回执并加 `:replayed true`，`:effects` 为空。同一 `call-id` 不同载荷立即 `:CALL_ID_PAYLOAD_CONFLICT`。只有成功回执会入库；失败调用不占用 `call-id`。

## 4. Plan 车道

- 初始 Plan 在 `:S1`；`S1 -> :S2 -> :S3 -> :pending` 只能由继任者的空首评推进。
- 初任在 `:S1` 的第一次空 review 不记为 review：返回 `:ask`，问题为 `"我需要干什么？"`，`first-review?` 保持 true，`awaiting-user?` 置 true。恢复后再次空 review 触发 Plan 接力；继任者空首评再推进里程碑。
- Plan 的非空 review 只更新台账，不开蜂群，窗口保持 `:open`。
- Plan 自己用 `nil` 值销账；最后一个键解除时在同一状态转换中退休、写屏障、开启同里程碑的新 Plan tenure。
- Plan 到 `:pending` 后拒绝任何非空 findings（`:NONEMPTY_FINDINGS_AT_PENDING`）。
- `:pending` 的空 review 是启动执行层的唯一方式；要求底稿路径合法且内容非空，否则 `:DRAFT_MISSING_OR_EMPTY`。
- 每一个不同 `call-id` 的空账 review 都创建一个独立执行任期（从 `:execution` 开始），即使已有活动或已完成的执行任期；它们共享底稿字节，但台账、Reviewer、根任务、屏障与生命状态彼此独立。同一 `call-id` 重放只返回原回执，不重复创建。
- 启动执行层不结束 Plan tenure，不改底稿，不复制 Plan 台账。

## 5. 执行车道

- 新执行任期从 `:execution` 开始，`role :reviewer`，`initial? true`，台账为空，窗口打开。多个执行任期可以并发存在；`review` / `finish-root-task` / `maybe-relay` / `resume` 必须用 `:tenure-id` 定位具体任期。
- 初任第一次非空 review：台账生效，同一转换关闭 `clj-review`、挂起 Reviewer、为每个台账键生成一个 `:ready` 根任务镜像，并返回 `:swarm/start` 效果（`:wake :all`）。进入 Phase B。
- 初任第一次空 review：返回 `:ask`，问题为 `"底稿已满足，接下来做什么？"`；不记 review，`first-review?` 保持 true，`asked?` 置 true。恢复后的再次空 review 触发接力，交由继任者复核。
- 继任者空首评：
  - `:execution -> :closing`：推进里程碑，同一 tenure 重置首评窗口；
  - `:closing -> :completed`：先做 quiescence 与 ff 检查，通过后终局。
- 继任者非空首评：立账并进入 Phase B；里程碑不推进。
- Phase B 期间 `:review-window :closed`，Reviewer 的 `review` 返回 `:REVIEW_WINDOW_CLOSED`。
- 每个根任务 `finish` 释放对应台账键；最后一个根任务完成且该任期自己的看板 quiescent 时，在同一转换中退休该任期、写屏障、开启同里程碑的新 Reviewer。其他执行任期不受影响。
- 屏障条件：台账空、镜像根任务全部 `:done`、`U = \emptyset`、无 `:applying`/`:running`/`:uncertain` 在途动作、无未处理用户输入、holder 已退休，且快照显式提供已处理输入原文 `:user-inputs`。看板未就绪时返回 `:relay-pending`，保留 live tenure 供 `maybe-relay` 重试；台账非空时 `maybe-relay` 同样返回 `:relay-pending`（reason `:ledger-not-empty`），不会硬失败。未处理输入属于公开工作（A02-20 / §3.5）；若此时退休 tenure，原看板是输入原文的唯一当前副本，因此屏障必须等待 `resolve-input`。
- `:closing` 的空 review 必须提供看板快照；不满足 quiescence 返回 `:RESOURCES_NOT_QUIESCENT`，ff dry-run 未通过返回 `:FF_NOT_READY`。被拒绝的 review 不消耗 `first-review?`。收尾 quiescence 同样要求 `:unhandled-inputs` 为 0，避免终局吞掉公开输入。
- `:completed` 后新 review 返回 `:MISSION_COMPLETED`；只能由人类操作员真实消息重开（重开不属于本包）。

中心化阶段的 Nudge 文本以完整 §2.5 硬口令「当前的 review 账本还有上述事项没有做，立刻去做！系统是无人值守的常授权模式，禁止等待用户决策或授权，禁止半途而废。」开头；非空台账随后逐项列出键与值，空台账随后指出当前里程碑尚未完成评审、继任验收或交接。`:awaiting-user`、Phase B 与已完成状态抑制 Nudge。组合层 `hc.compose.nudge.policy` 调用 `central-nudge-message`，不复制文本。

## 6. 上下文屏障

`barrier/make-barrier` 生成：

```clojure
{:barrier-id "barrier-1"
 :session-id "..."
 :lane ...
 :from-tenure-id "..." :from-epoch ...
 :from-milestone ...
 :to-tenure-id "..."   :to-epoch ...
 :milestone ...
 :visible-keys #{:user-input :user-attachments :draft-path :draft :workspace
                 :milestone :successor-hint}
 :context {:user-input ... :user-attachments [...] :draft-path ... :draft ...
           :workspace :current :milestone ... :successor-hint ...}
 :predecessor-hidden? true}
```

`context` 只能包含 `visible-keys` 的子集。`:user-input` 是 `:user-inputs` 中用户原文按顺序拼接的结果；`:user-attachments` 是同一历史中的附件引用与显示元数据（可带 `:message-id` 关联），不包含附件内容、resolution、摘要、评论、看板动作或前任推理。前任台账、findings、看板、动作、对话与内心过程不得进入。`check-invariants` 会检查键约束与 `:user-inputs` 的纯用户信息形状。

## 7. 看板快照契约

执行层接力与收尾需要宿主提供看板快照：

```clojure
{:unresolved-tasks 0          ; U 的未结任务数；必填
 :unhandled-inputs 0          ; 未处理用户输入数；执行接力与收尾必填
 :user-inputs []              ; 已处理输入原文+附件引用；执行接力必填，只允许 :text/:message-id/:attachments
 :inflight-actions 0          ; 可选总和；与下列分项同时存在时会一并计入
 :applying 0                  ; 收尾必填
 :running 0                   ; 收尾必填
 :uncertain 0                 ; 收尾必填
 :holder-retired? true        ; 必填且必须显式为 true
 :all-root-tasks-done? true   ; 必填且必须为 true
 :ff-ready? true              ; 收尾必填
 :call-children 0             ; 收尾必填
 :report-pending 0            ; 收尾必填
 :pending-rebase 0            ; 收尾必填
 :holds-write-lock? false     ; 收尾必填
 :unresolved-token-tasks 0}   ; 收尾必填
```

执行层接力要求 `:unresolved-tasks`、`:unhandled-inputs`、`:user-inputs`、`:holder-retired?`、`:all-root-tasks-done?`，以及 `:inflight-actions` 或 `:applying`/`:running`/`:uncertain` 之一；缺失字段产生 `:*-missing` 原因并保持 `:relay-pending`。字段存在但不是非负整数（计数）或布尔（`:holder-retired?`/`:all-root-tasks-done?`/`:holds-write-lock?`）时产生 `:*-invalid` 原因，同样保持未就绪；`:holds-write-lock?` 只有显式 `false` 才放行。未处理输入非零产生 `:unhandled-inputs` 原因；这不是可忽略的提示，而是防止原看板被退休后输入原文丢失的硬条件。`:user-inputs` 由宿主从已处理输入生成，只携带原文、可选 `:message-id` 与用户附件引用元数据；relay 按非空白 `:message-id` 去重后并入历史，空白 id 视为无 id，并再次丢弃附件内容/推理字段。收尾要求上表标注“收尾必填”的字段显式存在；缺失不会被当作 0/false，收尾的最小集按 §5 执行。执行层接力缺少整个快照时返回 `:BOARD_SNAPSHOT_REQUIRED`。`:effects` 是建议性的：状态里已经记录 successor、barrier、根任务镜像与窗口变化，宿主应从状态对账，而不是依赖重放回执再次派发。

快照属于具体执行 tenure 自己的 swarm 看板，不是所有执行任期的全局并集。多个并发 handoff 时，每次 `review` / `finish-root-task` / `maybe-relay` 都传入该 tenure 的看板快照。

## 8. 与 `swarm_stm` 的咬合

- 组合层在同一个 STM 事务中调用 `review` / `finish-root-task` / `maybe-relay`，把返回状态与看板状态一起提交。
- `:swarm/start` 效果中的 `:root-tasks` 是镜像描述；每项含 `:ledger-key`、`:goal`、`:remaining-work`、`:done-when`，宿主必须在看板创建同 `:ledger-key` 的根任务，并执行 `wake-all`。
- 根任务 `finish` 必须调用 `finish-root-task`，而不是只改看板。可用镜像 `:task-id` 或看板直接对应的 `:ledger-key` 定位；该函数释放台账键。只有全部根任务完成且快照 quiescent 才触发屏障。
- 看板上的 `:unhandled` 用户输入必须先由个体 `resolve-input` 处理；relay 只读取快照中的 `:unhandled-inputs`，不替看板解释输入。屏障提交时把 `:user-inputs` 中的原文与附件引用并入 relay 历史，绝不读取 `:resolution`、附件内容、评论或动作说明。
- 持令者退休由 swarm 层在同一事务中完成；relay 只读取 `:holder-retired?`，不自行释放令牌。
- 父项恢复、子项完成、`take-or-die` 死亡等语义属于 `swarm_stm`，本包不复制。

## 9. 公开 API

公开入口是 `hc.core.relay`；实现位于 `hc.core.relay.core`，各子职责在 `ledger`、`tenure`、`barrier` 命名空间。

```clojure
(require '[hc.core.relay :as relay])

(relay/new-state opts)
(relay/set-draft state {:path "plans/x.md" :content "..."})
(relay/review state request)
(relay/resume state {:lane ... :tenure-id ...})
(relay/finish-root-task state {:lane :execution :tenure-id ... :task-id ... :board ...})
;; or, when the host board is keyed by the obligation key:
(relay/finish-root-task state {:lane :execution :tenure-id ... :ledger-key "k" :board ...})
(relay/maybe-relay state {:lane :execution :tenure-id ... :board ...})
(relay/prune-barriers state 16)
(relay/central-nudge-message ledger)
(relay/nudge-message state lane)
(relay/nudge-message state :execution tenure-id)
(relay/check-invariants state)
(relay/normalize-user-attachments attachments)
(relay/current-tenure state lane)
(relay/current-tenure-id state lane)
(relay/execution-tenures state)
(relay/tenure-by-id state tenure-id)
(relay/ledger-of state lane)
(relay/ledger-for state tenure-id)
(relay/plan-ledger state)
(relay/execution-ledger state)
(relay/execution-ledgers state)
```

`current-tenure` / `current-tenure-id` / `ledger-of` / `execution-ledger` 是单车道的便利访问器：多个执行任期存在时返回 nil，调用者应改用 `tenure-by-id` / `execution-tenures` / `ledger-for` / `execution-ledgers`。`nudge-message` 的 `[state :execution]` 二参数形式同样只在恰好一个执行任期时可用；多任期时传第三参数 `tenure-id`。

`new-state` 默认 Plan 从 `:S1` 开始；测试/恢复可传 `:plan-milestone`，并可传 `:message-id` 标记初始输入，便于后续看板副本按消息去重。`set-draft` 首次路径锁定，之后只能改内容；第一个执行 tenure 一旦存在，底稿冻结为 `:DRAFT_LOCKED_AFTER_HANDOFF`，避免执行层启动后底稿漂移。底稿写工具的 `:WRITE_LOCKED_BEFORE_FIRST_REVIEW` 由 Plan 工具层执行，本包只记录字节。

## 10. 错误码

`:CALL_ID_REQUIRED`、`:CALL_ID_PAYLOAD_CONFLICT`、`:INVALID_LANE`、`:INVALID_REQUEST`、`:INVALID_FINDINGS`、`:UNKNOWN_TENURE`、`:TENURE_RETIRED`、`:TENURE_LANE_MISMATCH`、`:ROLE_FORBIDDEN`、`:AWAITING_USER`、`:REVIEW_WINDOW_CLOSED`、`:MISSION_COMPLETED`、`:NONEMPTY_FINDINGS_AT_PENDING`、`:NONEMPTY_LEDGER_AT_PENDING`、`:DRAFT_MISSING_OR_EMPTY`、`:BOARD_SNAPSHOT_REQUIRED`、`:RESOURCES_NOT_QUIESCENT`、`:FF_NOT_READY`、`:RELAY_NOT_READY`、`:NOT_IN_SWARM`、`:UNKNOWN_ROOT_TASK`、`:NOT_AWAITING_USER`、`:INVALID_DRAFT_PATH`、`:INVALID_DRAFT_CONTENT`、`:DRAFT_PATH_LOCKED`、`:DRAFT_LOCKED_AFTER_HANDOFF`、`:INVALID_PRUNE_COUNT`、`:TERMINAL_MILESTONE`。

`:ROLE_FORBIDDEN` 与 `:TERMINAL_MILESTONE` 是防御性/内部错误码：公开转换会先拒绝已完成 tenure、错误车道或已完成里程碑，正常路径不可达。

## 11. 不变量与测试

`check-invariants` 检查：Plan tenure 活跃且窗口常开；每个执行 tenure 的里程碑/角色/状态合法；执行 map 的键等于 tenure-id；Phase B 与关闭窗口互锁；非空执行台账必须处于 Phase B；`:relay-pending?` 的台账必须为空；开放根任务与台账一一对应；根任务 ID 唯一且状态合法；完成态为空且关闭；任一执行 tenure 存在时 Plan 已到 `:pending` 且台账已清；`:user-inputs` 只含非空 `:text`、可选非空白 `:message-id` 与附件引用元数据（无内容/推理字段）；退休 ID 不与 live tenure 相交；屏障上下文无泄漏；屏障 successor 为 live 或已退休 tenure，predecessor 必须已退休；屏障的 `:from-milestone` 必须等于 `:milestone`（里程碑复制）；回执结构合法。

测试位于 `test/hc/core/relay_test.clj`，包含确定性场景与 `clojure.test.check` 属性测试：

```bash
clojure -X:test :nses '[hc.core.relay-test hc.compose.nudge.policy-test]'
```

属性覆盖：台账模型、`review` 幂等、随机 review 序列的不变量、多 handoff 独立任期、根任务完成顺序与屏障唯一性、收尾 quiescence 判定。确定性场景覆盖 Plan 自我销账、两阶段继任、`ask` 单次、Phase B、延迟屏障、未处理输入阻止屏障、已处理原文与附件引用跨屏障且拒绝内容/非推理字段、`:closing` 与终局、Plan/执行台账隔离、并发 handoff 的独立 swarm/屏障、Nudge 文本与抑制。

## 12. 明确决策

- 执行车道可以同时有多个独立 tenure；每个不同 call-id 的空账 handoff 创建一个，同一 call-id 只重放原回执。各 tenure 的台账、Reviewer、根任务、屏障与生命状态互不影响。
- Plan 接力不要求看板快照；Plan 没有蜂群动作。执行接力与收尾必须有快照，缺失不视为 quiescent。
- `:barriers` 是可修剪审计后缀；`prune-barriers` 只从头部删除并保留连续尾部，`barrier-seq` 只增不减。修剪后 `check-invariants` 仍成立。
- 屏障上下文只携带用户信息：`:user-input` 由 `:user-inputs` 按到达顺序拼接，`:user-attachments` 提供同一历史中的附件引用与显示元数据；不携带附件内容、resolution、摘要、评论或任何看板/代理推理。多个执行 tenure 收到同一 `:message-id` 时只保留一份。
- `:effects` 不重复派发；重放只返回回执。
- 本包不自动完成父项、不自动推进里程碑、不代替 `swarm_stm` 判断责任集合。
