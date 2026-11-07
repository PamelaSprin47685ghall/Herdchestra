# hc.compose.cancel — 取消树与独立分支收束

> 权威顺序：根 `AGENTS.md` > `src/AGENTS.md` > `src/hc/compose/AGENTS.md` > 本文件。
> 本文件只说明本包的接口、状态转换与验收边界；协议语义以根 §1.1-6、§3.2、§5.3 为准。

## 1 责任

本包是纯状态机，不启动线程、不写存储、不调用模型、不执行 I/O。它把操作员取消、父级终止、兄弟分支已接受、期限耗尽与执行器关闭表达为不可变数据转换；Owner 提交返回的 `:state`，执行层按 `:effects` 终止具体资源。

包内分工：

| 命名空间 | 责任 |
|---|---|
| `hc.compose.cancel.model` | 取消树、节点种类与状态、父子约束、广度优先遍历、树校验 |
| `hc.compose.cancel.reason` | 固定取消原因、操作员/程序化来源分类 |
| `hc.compose.cancel.transition` | 单节点取消规则、终态接受、未知结果核对、重试安全 |
| `hc.compose.cancel` | 公共装配：操作员取消、子树取消、运行收尾、三类独立分支组合器 |

## 2 取消树

树是普通 EDN：

```clojure
{:cancel-version 1
 :session-id "session-1"
 :active-run-id "run-1"
 :nodes {"run-1" {:node-id "run-1" :kind :run :parent-id nil
                  :state :running :admission :open :node-seq 1}
         "call-1" {:node-id "call-1" :kind :call :parent-id "run-1"
                   :state :running :side-effecting? true :node-seq 2}
         "attempt-1" {:node-id "attempt-1" :kind :attempt
                      :parent-id "call-1" :state :running :node-seq 3}}
 :receipts {}}
```

节点种类与状态：

| 种类 | 状态 |
|---|---|
| `:run` | `:preparing` `:running` `:cancelling` `:cancelled` `:succeeded` `:failed` |
| `:call` | `:ready` `:dispatching` `:running` `:retry-wait` `:succeeded` `:failed` `:cancelled` `:outcome-unknown` |
| `:attempt` | `:dispatching` `:running` `:succeeded` `:failed` `:cancelled` `:outcome-unknown` |
| `:action` | `:claimed` `:preparing` `:waiting-gate` `:applying` `:running` `:finished` `:uncertain` |
| `:process` | `:starting` `:running` `:stopping` `:exited` `:uncertain` |

父子约束由 `model/add-node` 在进入树之前校验：`attempt` 只能挂在 `call` 下，`action` 只能挂在 `run` 下，`process` 挂在 `action` 或 `run` 下；`run` 无父节点。`node-seq` 由 `add-node` 分配，只用于稳定广度优先顺序，不是独立权威。

`model/validate-state` 返回问题向量；空向量表示树合法。它检查节点身份、状态合法性、父子种类、可达性、`node-seq` 唯一性、`next-node-seq`、活动运行、`:cancelling` 运行的准入关闭，以及 call/attempt 的 `:outcome` 与状态一致、action/process/run 的 outcome 取值合法。

## 3 取消信号

固定原因来自根 §5.3：

```clojure
#{:user-cancel :parent-terminated :sibling-accepted
  :deadline-exceeded :executor-shutdown}
```

固定操作员来源：`:operator-esc`、`:operator-stop`。
程序化挂起来源：`:park`、`:ask`、`:relay-retirement`、`:slot-reincarnation`。

规则：

1. 只有真实操作员来源可以携带 `:user-cancel`；程序化挂起来源一律拒绝为 `:NOT_A_CANCELLATION`，状态不变。
2. 操作员来源不得携带内部原因；内部原因也不得冒用操作员来源。
3. 操作员取消先关闭运行准入（`:admission :closed`），记录 `:cancellation` 意图，再按广度优先传播。
4. 根节点保留调用者原因；后代节点携带 `:parent-terminated`。每个被触及节点同时保存 `:cancel-root-reason`，因此操作员停止不会被误读为上游故障。
5. `accept-operator-cancel` 对操作员命令按 `:command-id` 幂等：同载荷重放返回原回执且无效果；不同载荷拒绝 `:COMMAND_ID_CONFLICT`。内部组合器（deadline、executor-shutdown、first-accepted、required-step）的 `:command-id` 只作关联标记，不建立第二套回执；客户端命令去重由服务器 command gate 负责。
6. 运行已处于 `:cancelling` 时，新的操作员命令返回 `:already-cancelling` 且无效果；已 `:cancelled` 时返回 `:already-cancelled`；`:succeeded`/`:failed` 拒绝 `:RUN_TERMINAL`。
7. 取消根不得同时包含祖先与后代；重叠根拒绝为 `:OVERLAPPING_CANCEL_ROOTS`。
8. 操作员取消必须 rooted at run；非 run 根拒绝为 `:OPERATOR_CANCEL_REQUIRES_RUN_ROOT`。迟到节点路径显式豁免这一检查，因为它已处于关闭准入的 `:cancelling` run 内。
9. 迟到终态必须匹配 call 的 `:current-attempt-id` 或 attempt 自身的 `:attempt-id`；缺失或不匹配一律写入 `:late-results` 诊断，不覆盖当前结果。

## 4 单节点转换与未知结果

| 原状态 | 取消后 | 说明 |
|---|---|---|
| `call` `:ready`/`:retry-wait` | `:cancelled` | `:retry-wait` 附带 `:cancel-timer` 效果 |
| `call`/`attempt` `:dispatching`/`:running`，有副作用 | `:outcome-unknown` | 不冒充 `:cancelled`；附带 abort 效果 |
| 同上，无副作用 | `:cancelled` | 仍终止执行资源 |
| `action` `:claimed`/`:preparing` | `:finished` + `:outcome :cancelled` | 未触碰磁盘 |
| `action` `:waiting-gate` | 同上 | 附带 `:release-resource :write-gate` |
| `action` `:running`，有副作用 | `:uncertain` + `:outcome :outcome-unknown` | 保留未知结果 |
| `action` `:running`，无副作用 | `:finished` + `:outcome :cancelled` | 例如纯看板动作 |
| `action` `:applying` | 保持 `:applying`，记录 `:cancel-after-apply?` | 当前最小提交先完成，取消随后生效（F20-04） |
| `process` `:starting`/`:running` | `:stopping`，按副作用标记 `:outcome` | 附带 abort 效果；终止资源但不 dispose |
| `process` `:stopping` 且仍有活资源 | 保持 `:stopping` | 若此前未请求过，附带一次 abort；第二次取消幂等 |
| 任何 `:uncertain` / `:outcome-unknown` | 状态与结果不变 | 只记录取消意图；后续只能显式核对 |

`call` `:ready`/`:retry-wait` 与 `action` `:claimed`/`:preparing`/`:waiting-gate` 正常不拥有执行器；若恢复或手工状态显式带 `:resource-alive? true`，取消保留该标记并补发 abort，绝不在没有 settlement 的情况下把它抹成 false。

**终态但仍有活资源**：调用/尝试、动作或进程已固定 `:succeeded`/`:failed`/`:finished`/`:exited`，但 `:resource-alive? true` 时（例如程序已 `:PARKED` 而调用结果已固定），取消仍发出一次 `:abort-resource`，只终止资源，不改写状态、结果或证据。abort 效果携带 `:call-id`；call 的 `:call-id` 缺省为自身 `:node-id`，attempt 的缺省取自其父 call。第二次取消看到 `:cancel-requested? true` 后不再重复发出。在终态观察到达前，这些节点仍计入 `active-resources`，`finish-run` 拒绝 `:RESOURCES_NOT_QUIESCENT`；匹配的 `accept-terminal` 观察清除 `:resource-alive?`，把迟到 `payload`/`error` 存入 `:late-results`，不改写终态。

**只有结算观察能证明资源结束**：清 `:resource-alive?` 的唯一依据是到达的观察本身（`settlement-outcome?`）：`accept-terminal` 的 `:succeeded`、`:failed`、`:cancelled`，或带显式证据的 `reconcile-unknown`。`:outcome-unknown` 是“没有执行器结果”，不是“资源已停”，因此在任何分支都不清标记——直接写入、迟到、重放都一样。真实后续终态到达时清标记并保留理由：已固定的未知结果保留为 `:outcome-unknown`，真实结果进入 `:late-results`（`:TERMINAL_ALREADY_FIXED` / `:OUTCOME_UNKNOWN_PRESERVED`），`payload`/`error` 一并留存。被取消但从未回报的 Shell 因此保持存活，`finish-run` 继续拒绝。

`accept-terminal` 固定终态。重复终态幂等；终态之后的另一种结果、过期 `attempt-id`、以及已固定未知结果之后的任何结果都写入 `:late-results` 诊断，不覆盖已固定结果。只有结算观察（`:succeeded`/`:failed`/`:cancelled`）清 `:resource-alive?`；到达的 `:outcome-unknown` 在任何分支都保留该标记。`:applying` 动作接受提交终态并记录 `:cancel-applied-after-commit?`。

`reconcile-unknown` 只接受 `:outcome-unknown`/`:uncertain` 节点，要求显式证据（非空字符串或非空集合），把结果固定为 `:succeeded`、`:failed` 或 `:cancelled`。`retry-safety` 对未知结果一律返回 `:OUTCOME_UNKNOWN`；失败重试还必须满足错误 `:retryable? true`，有副作用的节点还要求 `:safe-to-retry? true`。

`record-late-report` 只把取消后的迟到报告存为未接受证据，绝不重启运行、队列或父任务。

## 5 独立分支组合器

| 函数 | 行为 |
|---|---|
| `accept-independent-failure` | 只改一个分支为 `:failed`，兄弟分支逐字不变，无取消效果；失败作为 `{:result/status :failed :result/error ...}` 数据值；`:applying` 动作拒绝为 `:ACTION_IN_FLIGHT`，先完成当前最小提交 |
| `accept-first-accepted` | 先固定胜者结果，再取消同组仍活动的非未知兄弟，原因 `:sibling-accepted`；未知/终态兄弟若 `:resource-alive? true` 仍发 abort，只保留状态/结果/证据；`:applying` 胜者拒绝为 `:ACTION_IN_FLIGHT`；多 worktree join 不使用本函数 |
| `accept-required-step-failure` | 先结算失败步骤自身的 timer、write-gate 与活执行器，再取消同组仍活动的非未知兄弟，原因 `:parent-terminated`；终态但仍有活资源的兄弟同样 abort |
| `cancel-due-deadline` | 从指定根传播 `:deadline-exceeded`，运行根会关闭准入 |
| `cancel-due-executor-shutdown` | 从运行根传播 `:executor-shutdown`，关闭准入 |

同组由 `:group-id` 标识。`accept-first-accepted` 拒绝未知结果作为胜者（`:WINNER_OUTCOME_UNKNOWN`）；`accept-required-step-failure` 拒绝把未知或非失败终态当作失败步骤。

`:partial` 是运行 `:outcome` 的投影，覆盖 call、attempt、action 与 process；只要既有成功工作又有未成功工作，`finish-run` 就投影为 `:partial`。

## 6 效果协议

效果只是建议，状态已经记录事实。每个效果都带：

```clojure
{:effect :abort-resource | :cancel-timer | :release-resource | :close-admission
 :node-id ... :scope ... :thread-interrupt? false :dispose? false}
```

执行层只 abort 该 `attempt-id` 关联的模型流、计算任务或受管子进程，随后回报最终观察状态。严禁 `Thread/interrupt` 穿透数据库提交或 `FileChannel` 写入线程；严禁 `dispose` 未领取证据。`finish-run` 只在没有活动执行资源后把运行标为 `:cancelled`；未知结果可以保留，`:partial` 只是运行 `:outcome` 投影。

**Owner 装配合同**：

* `:abort-resource` 效果带 `:node-id`、`:node-kind`（`:call`/`:attempt`/`:action`/`:process`）、`:resource-id`，以及可路由的 `:call-id` 或 `:resource-id`。call 的 `:call-id` 缺省为自身 `:node-id`，attempt 的缺省取自其父 call；action/process 没有 call 关联时至少带 `:resource-id`（缺省为自身 `:node-id`）。`:kind` 保留为节点种类以兼容现有 Owner `cancel-outbox`；执行器种类在节点声明时另带 `:executor-kind` 与 `:resource-kind`（`:shell`/`:tool`/`:workspace`/`:model`）。Owner 的 `cancel-outbox` 必须保留这些字段，并生成 `:cancel/resource`。
* `:cancel/resource` 由 `dispatch/cancel-resource!` 处理：先按规范化 `attempt-id` 查找并取消活 attempt，再调用 `:cancel-resource!` 钩子。钩子对 shell 调 `request-stop!`，对程序调 `program/cancel!`；两者必须幂等。
* 已 settled/parked 的资源没有 dispatcher task 可回报终态；当钩子返回 `:terminal` receipt 时，Owner runtime 合成一条 `:call/terminal`（`:source :cancel-resource`）走 P1 迟到分支，清除 `:resource-alive?`。信号已发送但无最终结果时不得合成。
* 钩子只在观察到真实结束时给 `:terminal`。以下都只是“停止已请求”，不得合成：`STOP_SIGNAL_SENT` / `STOP_REGISTERED` / `STOP_UNCONFIRMED`；`SESSION_ENDED` 但没有 result map，或内层状态不可识别（不可识别的状态是 `:outcome-unknown`，绝不是 `:cancelled`）；`program/cancel!` 的 `:cancel-after-apply?`（受保护 apply 仍在写）、`:CANCELLING`（在途 call 尚未返回）与 `:OUTCOME_UNKNOWN`（call 已结束但没有观察到真实终态）；workspace 协作停止（合同恒为 `:ended? false`）。
* 延迟取消由程序运行时自己的 `:on-terminal` 在真实 call 返回后提交 `:call/terminal`；`:OUTCOME_UNKNOWN` 走 P1 后保留 `:resource-alive?`，只有后续真实结算观察才能清除。
* 执行器可以声明为 `{:run fn :cancel fn}`。取消先调用执行器的 `:cancel`（shell `request-stop!`、程序 `cancel!`），再取消 Missionary task 作为兜底，避免协作式执行器不返回时泄漏任务。执行器若在兜底前返回真实结果，P1 的迟到终态分支把 `payload`/`error` 保留到调用记录与取消节点的 `:late-results`；否则 shell session 仍是权威证据。
* 迟到终态只进入调用记录的 `:late-results` 与取消节点的 `:late-results`，保留 `payload`/`error`，不追加对话、不重开调用；匹配的观察同时清除 `:resource-alive?`，使 `finish-run` 可以收尾。
* 取消不覆盖调用记录已有的 `:result`（已生成正文、工具输出、部分结果）。Owner 另记 `:outcome`，并把迟到结果放入 `:late-results`；不得用 `:outcome-unknown` 之类的状态关键字替换真实结果。
* 进程重启后，进程内执行句柄已经不存在；恢复流程必须清除终态与 `:uncertain` 节点的 `:resource-alive?`（保留 outcome/evidence），并把 `:stopping`/`:applying` 转为不确定结果，否则 `active-resources` 会永久阻塞 `finish-run`。

## 7 公共 API

```clojure
(require '[hc.compose.cancel :as cancel])

(cancel/new-state {:session-id "s" :run-id "r"})
(cancel/add-node state node)
(cancel/add-nodes state nodes)

(cancel/accept-operator-cancel
 state {:session-id "s" :run-id "r" :command-id "cmd-1"
        :origin :operator-esc :at 1000})

(cancel/cancel-subtree state {:root-id "call-1" :reason :deadline-exceeded
                              :origin :deadline :close-admission? true})
(cancel/cancel-subtrees state {:root-ids ["call-1" "call-2"]
                               :reason :sibling-accepted
                               :origin :first-accepted})

(cancel/accept-terminal state {:node-id "call-1" :outcome :succeeded})
(cancel/reconcile-unknown state {:node-id "call-1" :outcome :succeeded
                                 :evidence "receipt-42"})
(cancel/retry-safety state "call-1")

(cancel/accept-independent-failure state {:node-id "call-1"
                                          :error {:kind :timeout :retryable? true}})
(cancel/accept-first-accepted state {:group-id "g" :winner-id "call-1"
                                     :outcome :succeeded})
(cancel/accept-required-step-failure state {:group-id "g" :failed-id "call-1"
                                            :error {:kind :bad-input}})

(cancel/active-resources state "r")
(cancel/cancellation-quiescent? state "r")
(cancel/finish-run state {:run-id "r" :at 2000})
(cancel/record-late-report state {:run-id "r" :report "late" :at 2001})
(cancel/cancel-tree state "r")
```

所有转换返回：

```clojure
{:ok? true  :state state' :receipt {...} :effects [...]}
{:ok? false :state state  :error {:code ... :message ... :details ...} :effects []}
```

## 8 验收

`test/hc/compose/cancel_test.clj` 覆盖：

- 操作员取消关闭准入、BFS 传播、根原因保留；
- 程序化挂起拒绝、命令幂等、已取消/已停止运行的重复命令；
- `:applying` 延迟取消、`finish-run` 的活动资源闸门与 `:partial` 投影；
- 终态但仍有活资源的 sweep、`finish-run` 阻塞到迟到观察、迟到 payload/error 保留；
- 迟到结果诊断、未知结果核对、重试安全；
- 独立失败不连坐、first-accepted、required-step failure；
- deadline / executor-shutdown、跨运行隔离、非法树拒绝；
- 固定未知不被迟到真实终态覆盖、过期 attempt-id、终态/未知但仍有活资源的兄弟 sweep、required-step 失败分支资源结算、abort 效果路由字段、重叠根与操作员非 run 根拒绝、applying winner 拒绝、action-only `:partial` 投影、`validate-state` 新不变量；
- `clojure.test.check` 属性：固定结果保留、效果无 dispose/interrupt、独立失败只改一个节点、first-accepted 只收束活动兄弟、BFS 父先于子。

运行：

```bash
clojure -X:test:compose :nses '[hc.compose.cancel-test]'
clojure -M:test -n hc.compose.cancel-test
clojure -M:test:compose:server -n hc.compose.cancel-test -n hc.compose.cancel-integration-test
```
