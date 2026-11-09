# `hc.core.swarm_stm` 局部规格（阶段一控制内核）

> 权威顺序：根 `AGENTS.md` > `src/AGENTS.md` > `src/hc/core/AGENTS.md` > 本文件。
> 本文件只记录本包实现选择；协议语义以根 `AGENTS.md` §3、§8.4 为准。

## 1. 职责边界

本包实现阶段一的 Swarm 看板控制内核：

- 任务、个体、认领、动作、依赖等待、责任集合与令牌槽的不可变状态；
- `split` / `claim` / `release` / `finish` / `cancel` / `replace` / `reopen` /
  `comment` / `update` 等短事务；
- 父项 `:waiting -> :ready` 恢复，**绝不自动 `:done`**；
- `take-or-die` 的赢/输两种不可逆生命结论，输者责任公开移交；
- 持令者 `R(a) = ∅` 时同一提交内释放令牌并逻辑死亡；
- `park-slot` 条件核对与等待登记同事务，以及 `wake-all` 不丢失；
- `request-id` 幂等、批量命令同事务、事件日志与结构不变量检查；
- 无副作用文件动作替身（`:claimed` / `:preparing` / `:waiting-gate` /
  `:applying` / `:running` / `:finished` / `:uncertain`）。

本包不负责：

- 真实文件读取、`stamp` 复核、整批 dry-run、写入口 `$W$`（阶段二
  `workspace_gate`）；
- Shell 子进程、会话边界与输出保留（阶段四）；
- 模型调用、槽位拉起、持久化、Sente/AG-UI（`hc.server` / `hc.compose`）；
- Relay 台账与接力屏障状态机（`hc.core.relay`）。本包只维护一个阶段一的
  根任务 `:ledger-key` 镜像，并在 `finish` / `cancel` 结果中报告
  `:ledger-released`；组合层应在同一外层事务中调用 relay 的
  `finish-root-task`，不能把本包的镜像当成 relay 的权威台账。

本包只依赖基础 Clojure，不 require 任何其他 `hc.core.*` 模块。

## 2. 状态模型

看板是一个不可变 EDN map，由 `hc.core.swarm-stm.board/empty-board` 构造：

```clojure
{:board-version 0
 :counters {:task 0 :actor 0 :claim 0 :action 0 :input 0}
 :tasks {task-id Task}
 :actors {agent-id Actor}
 :claims {claim-id Claim}
 :actions {action-id Action}
 :token-slot {:holder-id nil :term-id 0 :state :free}
 :waiters {agent-id {:cursor 0 :registered-version 0}}
 :inputs {input-id Input}
 :ledger {ledger-key {:task-id task-id :state :open}}
 :comments {task-id [{:agent-id ... :text ... :at-version ...}]}
 :requests {request-id {:command ... :result ...}}
 :events [Event ...]
 :event-seq 0
 :last-wake nil
 :derived Derived}
```

`Task`：

```clojure
{:task-id :task-1
 :ledger-key nil | key
 :goal "..." :remaining-work "..." :done-when "..."
 :state :ready | :waiting | :done
 :wait-for #{task-id ...}
 :needs-token false
 :owner-id agent-id | nil
 :claim-id claim-id | nil
 :parent-id task-id | nil
 :child-ids [task-id ...]
 :result-refs [{:summary "..." :read-set [...] :at-version n}]
 :handoff-note "..." | nil
 :rev 0
 :created-version n
 :done-at-version nil | n}
```

`Actor`：

```clojure
{:agent-id :agent-1 :slot-id :slot-a
 :role :normal | :holder
 :life-state :alive | :dead | :terminated
 :active-action-id action-id | nil
 :created-version n :dead-at-version nil | n
 :generation n}
```

`Claim` 保存 `:claim-id :task-id :agent-id :atomicity-reason :phase :created-version`。
`Action` 保存 `:action-id :task-id :agent-id :claim-id :kind :board|:edit|:shell`
`:request-id :phase` 与 `:result-ref`。`Input` 保存用户原文、`:state :unhandled|:handled` 与
解释结果；可选的 `:message-id` 是宿主消息标识，只用于跨 tenure 去重，不改变看板
语义；可选的 `:attachments` 只保存用户附件引用与显示元数据，绝不保存附件内容或
代理解释。`Derived` 只含可重建索引：`:ready-tasks`、`:waiting-tasks`、
`:public-ready-tasks`、`:token-tasks`、`:owned-tasks`、`:responsibility`、
`:holder-responsibility`、`:global-progressable?`、`:progressable-reasons`、
`:parked-agents`、`:root-tasks`、`:all-roots-done?`、`:ledger-clear?`。

派生索引与权威记录在同一个不可变 board 值中重算；任何命令提交后都由
`board/derive` 重建。非权威缓存不能代替认领、休眠与退休判断。

## 3. 事务入口

纯函数入口：

```clojure
(hc.core.swarm-stm.commands/execute board command env)
;; => {:ok? true :board board' :events [...] :result {...}}
;; 或 {:ok? false :result {:ok? false :error code :detail {...}}}
```

原子内核：

```clojure
(require '[hc.core.swarm-stm :as swarm])

(def k (swarm/create))
(swarm/execute! k {:op :register-actor :slot-id :slot-a})
(swarm/execute-under-write-gate!
 k {:acquire (fn [owner] ...) :release (fn [permit] ...)}
 :caller-id
 {:op :finish ... :read-set [{:path "..." :stamp ...}]})
```

也可直接使用实现命名空间：

```clojure
(require '[hc.core.swarm-stm.kernel :as kernel])

(def k (kernel/create))
(kernel/execute! k {:op :register-actor :slot-id :slot-a})
(kernel/execute! k {:op :create-task :actor-id :agent-1
                    :goal "..." :remaining-work "..." :done-when "..."})
(kernel/execute-batch! k [cmd1 cmd2 ...])
(kernel/execute-under-write-gate! k gate-port :caller-id finish-cmd)
(kernel/snapshot k)
(kernel/check-invariants k)
```

`kernel/create` 选项：

- `:verify-read-set`：`(fn [read-set] {:ok? true|false ...})`，阶段二的
  `workspace_gate` 注入点。**非空 `read-set` 必须经过
  `execute-under-write-gate!`（W 已持有）并提供该 verifier；非空
  `verified-read-set` 只要求 W 已持有，不再调用 verifier**。否则 `finish`
  在真实状态变更前拒绝 `:WRITE_GATE_REQUIRED` 或
  `:READ_SET_VERIFICATION_REQUIRED`，绝不静默接受。空 `read-set` 不需要
  W 或 verifier。返回 `false` / 非 `{:ok? true}` 时拒绝 `:FILE_CONFLICT`；
  非函数值拒绝 `:INVALID_VERIFIER`。
- `:on-events`：提交后收到已提交事件向量，在 CAS 之后、命令返回之前调用。
  返回值被检查：`{:ok? false ...}` 表示事件无法持久化。board 变更不回滚，
  但命令返回 `:BOARD_EVENT_PERSIST_FAILED` 与 `:persisted? false`；
  `:persist-error` 保留底层错误。调用方不得在失败后继续文件或进程副作用，
  也不得把内存中的终态当成已持久化。该 hook 不得重入内核。
- `:initial-board`：确定性测试/恢复注入。

`execute!` 使用 `compare-and-set!` 发布不可变 board，成功命令线性化；CAS
失败只重试纯函数，不重放副作用。`request-id` 命中已提交命令时返回原结果，
不改 board；同一 id 不同载荷返回 `:REQUEST_ID_CONFLICT`。`:requests` 条目
还保存 `:persisted?`：有事件的命令先记为 `false`，内核在事件 hook 成功后
才标记 `true`。未持久化的同 `request-id` 重放返回
`:BOARD_EVENT_PERSIST_FAILED`、`:persisted? false` 与
`:detail :requires-checkpoint? true`，不得返回成功。成功的 `:checkpoint`
会把其已提交 board 中已存在的请求标记为持久化；checkpoint 之后到达的新请求
保持自己的标记，不会被提前宣称已发布。

`begin-action` 与 `advance-action` 的 `:applying` 记录是文件批的
write-ahead 证据。事件持久化失败时命令失败，文件桥不得进入 apply；恢复时
未持久化的 `:applying` 不会被当成已完成。发布重试使用 `:checkpoint` 事件
把已提交的 board 值再次交给持久化 hook，而不是只凭内存中的 `:finished`
宣称已发布。纯 `commands/execute` 入口无法发布事件，因此有事件的请求在纯
结果中保持 `:persisted? false`；确定性驱动器若把它当成已提交，必须自行用
内核发布或显式标记，否则重放会按未持久化拒绝。

`replace-board!` 是组合层“读 board → 纯计算替换 → 发布”的原子发布口：
它只在当前 board 仍是读取时的同一个值时 CAS 成功，否则返回 false，调用方
必须用新 board 重跑纯计算。它不重放命令，也不合并值。`reset-board!` 仍仅
用于确定性测试/恢复注入；协议参与者不得用它覆盖并发命令。

`execute-batch!` 把多个子命令放在一次 board 转换中：所有子命令都成功后
才统一派生索引、检查退休、唤醒并递增版本；任一失败则整批不提交。子命令
不得携带自己的 `:request-id`，批级 `:request-id` 用于幂等。

`execute-under-write-gate!` 接收 W 端口
`{:acquire (fn [owner] permit) :release (fn [permit])}`。对文件依赖的
`finish`，顺序严格为：取得 W → 在 W 内调用 verifier → 在 W 内完成 board
CAS/提交与事件发布 → `finally` 释放 W。它不会在 verifier 之后、CAS 之前
释放 W；若 W 端口不可用则直接拒绝，不退回无锁校验。调用方不得在已持有同一
非重入 W 时再次调用。W 端口缺失、取得失败或未取得时分别返回
`:WRITE_GATE_PORT_REQUIRED`、`:WRITE_GATE_ACQUIRE_FAILED`、
`:WRITE_GATE_NOT_ACQUIRED`，均不执行命令。

`:under-write-gate?` 是内核内部证明，只由 `execute-under-write-gate!` 在
取得 W 后设置。直接调用纯 `commands/execute` 的确定性驱动器若自行设置该
字段，必须真的持有 W；该纯入口不替调用方证明锁。`hc.compose.mission.host`
只在 `hc.compose.mission.proof` 的不透明 W 能力有效时设置该字段；EDN 载荷
无法构造该能力。

## 4. 命令与前置条件

所有 mutating 命令都要求 `:actor-id` 对应活身份（注册、用户输入、显式
`wake-all` 除外）。拒绝不改变 board。关键拒绝码：

| 命令 | 前置条件 | 主要拒绝码 |
|---|---|---|
| `:register-actor` | `:slot-id` 未被活身份占用 | `:SLOT_OCCUPIED` |
| `:create-task` | 目标/剩余工作/完成含义非空；`wait-for` 存在且未完成；`ledger-key` 未被任何当前或历史任务占用 | `:INVALID_TASK`、`:UNKNOWN_WAIT_TASK`、`:WAIT_TASK_DONE`、`:LEDGER_KEY_IN_USE` |
| `:split` | 父项存在且非 `:done`；`expected-cond` 成立（缺省为 `{:rev (:rev parent)}`）；有子项；无 `:applying/:running/:uncertain` 动作；父项若被他人认领则拒绝；子项不等待祖先 | `:TASK_DONE`、`:EXPECTED_CONDITION_FAILED`、`:ALREADY_CLAIMED`、`:ACTION_IN_FLIGHT`、`:DEPENDENCY_CYCLE`、`:NO_CHILDREN`、`:UNKNOWN_WAIT_TASK`、`:WAIT_TASK_DONE`、`:WAIT_NOT_CHILD` |
| `:claim` | 任务 `:ready`、无现有认领、普通归属允许、令牌任务由 holder 认领、原子性理由非空 | `:TASK_NOT_READY`、`:ALREADY_CLAIMED`、`:NOT_OWNER`、`:TOKEN_REQUIRED`、`:ATOMICITY_REASON_REQUIRED` |
| `:release` | 认领匹配调用者；未进入 `:applying/:running/:uncertain` | `:CLAIM_CHANGED`、`:ACTION_IN_FLIGHT` |
| `:finish` | 任务非 `:done`；有认领时 `:claim-id` 与认领者匹配；无认领时调用者是责任者；无在途动作；非空 `read-set` 必须在 W 内并由显式 verifier 校验，非空 `verified-read-set` 也必须在 W 内；结果摘要非空；`read-set` 与 `verified-read-set` 不得同时非空 | `:CLAIM_CHANGED`、`:NOT_RESPONSIBLE`、`:ACTION_IN_FLIGHT`、`:WRITE_GATE_REQUIRED`、`:READ_SET_VERIFICATION_REQUIRED`、`:INVALID_VERIFIER`、`:FILE_CONFLICT`、`:RESULT_SUMMARY_REQUIRED`、`:INVALID_READ_SET` |
| `:cancel` | 叶子（无未完成子项）、非 `:done`、无在途动作、理由非空 | `:HAS_UNFINISHED_CHILDREN`、`:TASK_DONE`、`:ACTION_IN_FLIGHT` |
| `:replace` | 同 `cancel`；替代描述合法；`replacement` 可带 `:wait-for`（不得等待旧 id、传递等待旧 id 或等待仍存活的祖先）；原子移除旧叶子并创建替代项，父项/外部等待者中的旧 id 换成新 id；未显式给 `:owner-id` 时继承仍存活的旧 owner，显式 `:owner-id nil` 表示清空；已 `:done` 的父项只移除旧引用，不挂新子项 | 同 `cancel` + `:INVALID_REPLACEMENT`、`:UNKNOWN_WAIT_TASK`、`:WAIT_TASK_DONE`、`:DEPENDENCY_CYCLE` |
| `:reopen` | 任务 `:done`；理由非空；根任务重新登记 `:ledger-key`，该键不得已被占用 | `:TASK_NOT_DONE`、`:REASON_REQUIRED`、`:LEDGER_KEY_IN_USE` |
| `:comment` | 任务存在；文本非空；append-only，不改任务 `:rev` | `:COMMENT_REQUIRED` |
| `:update` | 任务非 `:done`；只允许 `:remaining-work :wait-for :done-when :needs-token :owner-id :handoff-note`；有认领时禁止改 `:wait-for` / `:needs-token`；依赖无环；移除仍有未完成子项的依赖时必须已有去向；改 `:needs-token true` 同事务清空 `:owner-id`，改回 `false` 时清空已死 owner | `:IMMUTABLE_FIELD`、`:NOT_RESPONSIBLE`、`:CLAIMED`、`:DEPENDENCY_CYCLE`、`:ORPHAN_DEPENDENCY` |
| `:take-or-die` | 活身份；`:confirm :i-hereby-confirm-i-die-without-regret`；无 `:applying/:running/:uncertain` 动作 | `:CONFIRMATION_REQUIRED`、`:ALREADY_HOLDER`、`:ACTION_IN_FLIGHT` |
| `:park-slot` | 活身份；`:observed-cursor` 是整数且等于当前 `:board-version`；无活动动作；全局无非阻塞可推进工作；同一身份不得重复登记 | `:CURSOR_REQUIRED`、`:STALE_CURSOR`、`:ACTION_IN_FLIGHT`、`:PROGRESS_AVAILABLE`、`:ALREADY_PARKED` |
| `:wake-all` | 无 | — |
| `:user-input` | 原文非空；可带 `:message-id` 与附件引用元数据；总是产生 `wake-all` | `:INPUT_REQUIRED`、`:INVALID_ATTACHMENT` |
| `:resolve-input` | 活身份；输入存在；未处理时必须给出任务或具体摘要 | `:ACTOR_REQUIRED`、`:UNKNOWN_ACTOR`、`:ACTOR_INACTIVE`、`:UNKNOWN_INPUT`、`:INTERPRETATION_REQUIRED` |
| `:begin-action` | 活身份；任务有匹配认领；无其他活动动作；`:shell` 需要 holder 且任务 `:needs-token true` | `:CLAIM_CHANGED`、`:ACTION_IN_FLIGHT`、`:TOKEN_REQUIRED` |
| `:advance-action` | 动作属于调用者；合法阶段迁移 | `:INVALID_ACTION_TRANSITION`、`:NOT_ACTION_OWNER` |
| `:batch` | 非空；子命令合法；子命令无 `:request-id` | `:EMPTY_BATCH`、`:NESTED_BATCH`、`:REQUEST_ID_NOT_ALLOWED_IN_BATCH` |
| `:acknowledge-recovery` | 活身份；`:evidence` 非空字符串；没有任何未完成动作 | `:RECOVERY_EVIDENCE_REQUIRED`、`:RECOVERY_ACTIONS_PENDING` |
| `:checkpoint` | 活身份 | `:ACTOR_INACTIVE`、`:UNKNOWN_ACTOR` |

## 5. 拆分、认领、完成与父项恢复

`split` 在一个事务中：

1. 校验父项结构可变更、`expected-cond` 成立、无在途动作；
2. 创建子项（普通子项默认公开；令牌子项动态归当前 holder；拆分者不能把
   子项强派给无关身份；显式 `:owner-id` 只允许父项的当前普通 owner 自己，
   令牌父项不授予这种归属权）；
3. 把父项 `:remaining-work` 改为调用者给出的内容；
4. 父项 `:wait-for` 并入本轮新子项与调用者指定的未完成子项；
5. 父项置 `:waiting`，`:child-ids` 追加新 id；
6. 若父项有原主认领且原主就是调用者，释放旧认领；若被他人认领则拒绝。
   同时清理父项上尚未进入副作用的动作（`:claimed` / `:preparing` /
   `:waiting-gate`），避免结构变化后留下孤儿动作。

未提供 `:expected-cond` 时，`split` 以父项当前 `{:rev (:rev parent)}` 作为
最小读条件；并发拆分因此只有一个能提交，后提交者收到
`:EXPECTED_CONDITION_FAILED` 并重读结构。同一次 `split` 的新子项 id 在事务
内才分配，子项 `:wait-for` 不能引用同批其他新子项；需要子项间依赖时先拆分
再用 `:update` 建立，避免用未分配 id 猜测关系。

内核在 `execute!` 入口把当时的 board 作为 `:observed-board` 放进环境；
CAS 重试保留这个入口观察，所以缺省 `:rev` 不会随重试悄悄更新。直接调用纯
`commands/execute` 的驱动器若需要同样的并发保护，必须传入
`:observed-board`（或显式 `:expected-cond`）；否则缺省只能取当前 board 的
rev，仅作为单次调用的基线。`:batch` 内的 `:split` 子命令不自动注入入口
观察（它可能依赖同批前序子命令的结果）；需要跨事务并发保护时显式提供
`:expected-cond`。

`claim` 只接受 `:ready` 任务。普通任务若 `:owner-id` 为空，认领同时确立归属；
令牌任务只允许当前 holder 认领，`:owner-id` 不是令牌责任的权威字段。

`finish`：

- 有认领时校验 `:claim-id` 与认领者；无认领时校验普通归属或 holder 资格；
- 非空 `:read-set` 或 `:verified-read-set` 必须由 `execute-under-write-gate!`
  在持有 W 期间执行：
  未持有 W 时直接拒绝 `:WRITE_GATE_REQUIRED`；持有 W 但未注入 verifier
  时拒绝 `:READ_SET_VERIFICATION_REQUIRED`。verifier 必须在 W 内运行，
  board CAS/提交与事件发布也在 W 内完成，之后才释放 W；拒绝时任务、认领、
  动作均不变。空 `read-set` 可直接完成；
- 任务置 `:done`、清认领、清空当前轮 `:wait-for`、追加 `:result-refs`；
- 事件顺序为 `:task-finished` 先、父项/等待者恢复事件后，最后是
  `:ledger-released`；状态计算仍先完成父项恢复，再在事务末尾检查退休；
- 同一事务移除该任务上尚未进入副作用的动作
  （`:claimed` / `:preparing` / `:waiting-gate`），并在结果与
  `:task-finished` 事件中报告 `:removed-action-ids`；
  `:applying` / `:running` / `:uncertain` 仍视为在途并拒绝完成；
- 对每个直接等待该任务的父项：从 `:wait-for` 移除该 id；**仅当等待集为空且
  父项为 `:waiting` 时置 `:ready`**；保留父项目标、`:remaining-work`、
  `:owner-id`、`:needs-token`；绝不把父项置 `:done`，也不沿祖先链级联；
- 根任务释放 `:ledger-key`；结果报告 `:ledger-released`；
- 事务末尾按最终状态重算 `R(holder)`，为空则同一提交内退休。

父项可以在仍有未完成子项时被明确完成（根规格 B12-06 允许保留运行中子动作
的责任）；此后子项完成不会再激活已 `:done` 的父项。取消/替代子项会从已
`:done` 父项的 `:child-ids` 中移除旧引用，但不复活父项。

`replace` 的补充语义：

- 未显式提供 `:owner-id` 时继承旧任务的 `:owner-id`，前提是该 owner 仍存活；
  显式 `:owner-id nil` 才表示清空归属。令牌替代项不保留 `:owner-id`。
- `replacement` 可带 `:wait-for`。它必须引用存在且未完成的任务，不能包含旧
  任务 id，也不能引用传递等待旧任务的任务（否则替换后会形成环）。替代项按
  等待集是否为空置 `:waiting` 或 `:ready`。
- 对已 `:done` 的父项/等待者，只从 `:child-ids` / `:wait-for` 移除旧 id，
  不把新 id 挂到已完成的父项；新任务的 `:parent-id` 也只在旧父项未完成时
  保留。

`update` 改 `:wait-for` 时，若移除的依赖是当前任务的未完成子项，必须先在
同一事务之外完成该子项的去向（取消、替代或转移）；否则拒绝
`:ORPHAN_DEPENDENCY`。外部依赖的移除不受此限制。改 `:needs-token` 为
`true` 时同一事务清空 `:owner-id`；改回 `false` 时若旧 owner 已死则清空。

`cancel` / `replace` 遇到 `:applying` / `:running` / `:uncertain` 动作时仍
拒绝立即移除（根 §3.3）。运行中取消意图由用户输入事件或 `:comment` 记录，
动作结束并结清责任后再提交 `cancel` / `replace`；内核不把“拒绝”当成已取消。

`:cancelled` 与 `:replaced` 是事件日志的专用事件（`:type :task-cancelled` /
`:task-replaced`，携带 `:event :cancelled` / `:replaced`），不是 `Task.state`
值，也不进入看板状态投影；事件保留原因与替代前后的任务 id。

## 6. 责任集合与持令者退休

严格按根规格 §3.2：

```text
U = {t | t.state ∈ {:ready, :waiting}}
R(a) = {t ∈ U | ¬t.needs-token ∧ t.owner-id = a}
     ∪ (holder = a ? {t ∈ U | t.needs-token} : ∅)
     ∪ {e ∈ Actions | e.agent-id = a ∧ e.phase ∈ {:applying, :running, :uncertain}}
```

- `:waiting` 任务计入责任；不能因为“暂时没有可执行步骤”把 holder 判成无责。
- `:finished` 动作不计入 `R(a)`，但结果处理仍由看板可推进集合覆盖。
- 每个可能改变 holder 责任的事务（finish/cancel/replace/update/take-or-die/
  advance-action）提交前都检查退休；不是轮询，也不暴露 `release-token`。
- 退休与释放令牌、撤销身份、清空该 holder 的未完成动作与全部认领、移出等待
  登记在同一 board 转换中；`:term-id` 递增，下一次获胜生成新任期。事件报告
  `:removed-action-ids` 与 `:released-claim-ids`。
- 普通个体没有“无责退休”规则；只有 holder 自动退休。

## 7. 令牌竞争

`take-or-die` 在确认令牌与身份后只有两种已决定结果：

- `:attempt-won`：当前 `holder-id` 为空；同一转换设置 holder、递增
  `:term-id`、把调用者 `:role` 改为 `:holder`；若最终 `R(a)=∅`，同一转换
  紧接着退休。
- `:attempt-lost`：已有其他 holder；同一转换解除调用者全部认领、清空其
  未完成任务（含 `:waiting` 父项）的 `:owner-id`、删除未完成动作、把身份
  标为 `:dead`，并发布 `:attempt-lost` 事件。绝无“输后退回普通身份”。

同一 `request-id` 重放返回原结果，不二次参选；不同载荷复用 id 返回
`:REQUEST_ID_CONFLICT`。失败分支不提供 `handoff-updates` 或 `fission`。
`:published-task-ids` 只列普通任务；输者拥有的令牌子项同样清空 `:owner-id`
以保持结构不变量，但不冒充可接续的普通责任。

## 8. 主动休眠与唤醒

- `park-slot` 必须在同一事务中核对 `:observed-cursor` 与登记 `:waiters`。
  游标缺失或非整数返回 `:CURSOR_REQUIRED`，不等于当前版本返回
  `:STALE_CURSOR`；全局可推进集合非空返回 `:PROGRESS_AVAILABLE` 并附原因
  摘要；调用者有活动动作返回 `:ACTION_IN_FLIGHT`；同一身份重复登记返回
  `:ALREADY_PARKED`。
- 全局可推进集合按任务/动作阶段计算，不按“任务状态是 `:ready`”简单求和：
  `:waiting-gate`、`:applying`、`:running` 不新增全局步骤；`:claimed`、
  `:preparing`、`:finished`、`:uncertain` 与未阻塞的 ready 任务计入。
  未处理用户输入本身计入。
- 任一事务使全局可推进集合从空变非空，或任何 `user-input`，都在同一提交
  产生 `:wake-all` 事件并清空 `:waiters`。事件携带 `:woken` 集合与
  `:reasons`。通知可以合并传输，但“需要重新观察”的事实不能丢失。
- 显式 `:wake-all` 用于操作员/运行时；它不改变任务状态。

## 9. 不变量与属性测试

`board/check-invariants` 在每次提交后可由测试调用，覆盖：

- **V01**：结构上至多一个 `:holder`，且 `:token-slot` 与角色一致。
- **V02**：`:dead` 身份没有认领、活动动作、未结普通归属或令牌责任；任何
  命令入口先拒绝非活身份。
- **V03**：`take-or-die` 结果只有 `:attempt-won` / `:attempt-lost`；同一
  `request-id` 结果稳定。
- **V04**：失败竞争后死者 `R(a)=∅`，普通任务归属清空，认领释放。
- **V05**：holder 存在时 `R(holder) ≠ ∅`；责任清空的提交同时退休。
- **V06**：子项完成只使等待集为空的父项 `:waiting -> :ready`；不自动
  `:done`，不级联。
- **V07**：普通任务归属不因他人持令而改变；`take-or-die` 赢者不接管普通
  父项。
- **V08**：可推进集合非空时没有等待者；park 与登记同事务；唤醒不丢。

另外检查：任务状态封闭、`:wait-for` 引用存在且未完成、无自等待/显式环、
父/子双向一致、认领与任务双向一致、活动动作与 actor 唯一活动动作一致、
`:done` 无认领/等待、根任务 `:ledger-key` 与镜像台账一致、派生索引与原始
记录一致。`:wait-for` 图有独立的传递环检测，报告 `:wait-for-cycle` 与路径；
派生索引检查逐一比较 `:ready-tasks`、`:waiting-tasks`、`:done-tasks`、
`:public-ready-tasks`、`:token-tasks`、`:owned-tasks`、`:responsibility`、
`:holder-responsibility`、`:global-progressable?`、`:progressable-reasons`、
`:parked-agents`、`:root-tasks`、`:all-roots-done?`、`:ledger-clear?`，
不一致时报告 `:derived-index-mismatch` 与具体索引。

未进入副作用的动作（`:claimed` / `:preparing` / `:waiting-gate`）还必须满足：
所属任务存在、认领存在且与任务当前认领一致、身份存活、且该动作就是
`actor.active-action-id`。`finish` 与 holder 退休会清除这些动作，避免留下
孤儿动作阻塞后续 `begin-action`。

测试文件（普通 `clojure.test`，`clojure -X:test` 可发现）：

- `test/hc/core/swarm_stm_test.clj`：确定性单元场景与结构不变量；
- `test/hc/core/swarm_stm_property_test.clj`：`clojure.test.check` 属性；
- `test/hc/core/swarm_stm_smoke_test.clj`：三个脚本化个体、并发拆分/认领、
  父项恢复、park/wake 竞态、批量同事务；
- `test/hc/core/swarm_stm_workspace_test.clj`：用 `workspace_gate` 的真实
  `read-set-verifier` 与真实 `WriteGate` 验证：verifier 与 board CAS/事件
  发布都在 W 内，匹配 stamp 接受、文件变化拒绝且看板不变；另测普通
  `execute!` 与 batch 都不能绕过 W；
- `test/hc/core/swarm_stm_test_util.clj`：脚本化个体辅助。

运行：

```bash
clojure -M:test -n hc.core.swarm-stm-test
clojure -M:test -n hc.core.swarm-stm-property-test
clojure -M:test -n hc.core.swarm-stm-smoke-test
clojure -M:test -n hc.core.swarm-stm-workspace-test
clojure -X:test :nses '[hc.core.swarm-stm-test hc.core.swarm-stm-property-test hc.core.swarm-stm-smoke-test hc.core.swarm-stm-workspace-test]'
clojure -X:test
```

属性覆盖：父项永不自动完成、并发 `take-or-die` 单赢家、并发认领单赢家、
死身份不可提交、`request-id` 幂等/冲突、holder 责任空集退休、park/input
竞态无漏唤醒，以及带种子的随机命令序列逐步检查不变量。随机反例必须缩成
确定性场景后再修复，不能只保留种子。

### 9.1 T01–T13 验收矩阵

根 §3.4/§8.3 的令牌竞争推演；“先/后”指 STM 提交顺序。每行必须同时检查
状态、责任归属与旧资格是否仍可用。

| # | 场景 | 必须 | 禁止 | 本地覆盖入口 |
|---|---|---|---|---|
| T01 | A、B 同时看到令牌空闲并用不同 request-id 竞争 | 同一任期恰好一赢一输；输者移交剩余责任并逻辑死亡 | 两个 holder；输者继续普通工作 | `boundary-driver-test/token-competition-has-only-win-or-lose-outcomes`；property |
| T02 | 赢家事务已提交但响应丢失 | 原 request-id 重放仍是 `:attempt-won`；责任不重复转移 | 再竞争；把未收到响应当输；第二 holder | `boundary-driver-test/dropped-response-replays-the-same-conclusion-without-board-advance` |
| T03 | 输家事务已提交但响应丢失，重发请求或旧提交 | 重发仍是 `:attempt-lost`；旧提交因身份已死被拒 | 先回输再异步移交；旧资格仍可用 | 同上；`swarm-stm-test` 死亡/迟到提交 |
| T04 | 竞争失败者拥有等待子项的普通父项 P | P 进入公共可接续，子项完成后回 `:ready` | P 消失、仍归死者或自动完成 | `swarm-stm-matrix-test`（显式 T04） |
| T05 | holder 责任清空 | 退休与 `R(a)=∅` 同一提交 | 先退休后移交或反向 | property / `swarm-stm-test` retire |
| T06 | 同槽新个体与旧任期 | 新个体不继承旧资格/私有上下文 | 旧资格复活或追溯旧任期 | `swarm-stm-matrix-test`（显式 T06） |
| T07 | 子项完成 | 只把等待集为空的直接父项置 `:ready` | 自动 `:done` 或沿祖先级联 | `swarm-stm-test` / property |
| T08 | 普通任务归属与 holder | 他人持令不改变普通归属；holder 不吸收普通父项 | 归属转移或父项自动完成 | `swarm-stm-matrix-test`（显式 T08） |
| T09 | holder 无责退休与令牌槽 | 退休同事务释放身份/令牌 | 留下可提交旧身份 | property / `swarm-stm-test` |
| T10 | holder 与普通个体并发 | 普通任务不被 holder 抢占；holder 不被误杀 | 自动完成父项或杀死 holder | `swarm-stm-matrix-test`（显式 T10） |
| T11 | 失去资格个体的迟到提交 | 入口按 `:life-state` 拒绝 | 旧 attempt 写入新任期 | `swarm-stm-test` 迟到请求/死身份 |
| T12 | 新个体看到 holder 已存在且自己无工作 | 不发起必败竞争；继续找公共工作 | 预留令牌/内核选人 | `swarm-stm-matrix-test`（显式 T12） |
| T13 | 令牌空闲但无令牌责任 | 未发出时不抢空令牌；已发出的合法竞争仍得赢/输，赢且 `R(a)=∅` 同事务退休 | 给已受理竞争增加退回出口 | `boundary-driver-test/token-competition-has-only-win-or-lose-outcomes` |

以上是验收目标，不等于每条都已有独立断言；逐条缺口以根 §3.4 与测试注释
为准，不能把随机调度偶尔通过当作边界证据。

### 9.2 七边界确定性驱动器（测试侧契约）

`test/hc/boundary_driver.clj` 用于第一层控制状态与第二层文件入口的确定
交错，不覆盖第三层传输适配。

**(a) 公开 API**

```clojure
(new-driver world)
;; => driver；第二个未关闭 driver 抛 :DRIVER_ALREADY_OPEN
(step! driver participant command)
;; command: {:op :read|:commit|:finish|:claim|:split|:take-token|...}
;; => {:status :parked|:completed|:waiting|:error :boundary .. :observation ..}
(resume! driver participant) ; => 同上
(run-until! driver participant boundary)
(run-to-done! driver participant command)
(environment! driver action) ; :shell-write/:external-write/:drop-response/:hard-failure...
(replay driver recorded-log) ; => {:final-observation .. :log ..}
(close! driver)             ; 幂等，恢复全部 alter-var-root
```

七个边界：`:read-complete`、`:queued-for-w`、`:in-gate-checked`、
`:dry-run-complete`、`:applying`、`:applied`、`:board-published`。

**(b) 失败/拒绝**

| code/结果 | 触发 |
|---|---|
| `:DRIVER_ALREADY_OPEN` | 同一 JVM 已有未关闭 driver |
| `:waiting` | 参与者停在等 `$W$`/外部条件，不能用 `run-to-done!` 静默通过 |
| `:error` + `:boundary` | 参与者命令失败；记录所在边界与观察 |
| `:UNKNOWN_COMMAND`/`:INVALID_COMMAND` | `step!` 收到未知/非法 command（代码事实） |

**(c) 不变量**

保证：一次只开一个 driver；每个边界可精确暂停/恢复；`close!` 取消剩余
线程并逐 var `identical?` 恢复；`replay` 重放每个边界观察与最终 world；
`record-failure!` 输出可粘贴的 `deftest`。

不保证：不保证生产入口本身有暂停钩子（驱动用 `alter-var-root` 包装）；
不保证并发/多 driver；不保证第三层传输；不保证 `:waiting` 会自行完成；
不保证外部自由写入受 `$W$` 保护。

**(d) 验证**

```bash
clojure -M:test:compose -n hc.boundary-driver-test
```

代码事实，未复测（2026-11-09 当前文件）：19 deftest + 2 defspec
（21 个测试入口）/ 196 个 `is`。

## 10. 阶段边界与装配点

- **阶段一（本包当前）**：文件动作是无副作用替身；`finish` 的非空
  `:read-set` / `:verified-read-set` 不能通过普通 `execute!` 完成：普通路径**拒绝**
  `:WRITE_GATE_REQUIRED`；即使注入 verifier，也必须经
  `execute-under-write-gate!` 持有 W。没有真实 `stamp` 读取、没有 Shell；
  W 端口由调用方注入，内核本身不 require `workspace_gate`。
- **阶段二**：`workspace_gate` 已公开 `verify-read-set` /
  `read-set-verifier` 与 `write-gate` 的 acquire/release 端口；组合层把
  二者分别注入 `:verify-read-set` 与 `execute-under-write-gate!` 的 gate
  port，使 verifier、board CAS/提交与事件发布都在同一段 W 内完成，拒绝时
  看板不变。本包的 `swarm_stm_workspace_test` 已用真实临时工作区与真实
  `WriteGate` 验证：verifier 执行时 W 已持有，CAS/事件发布后 W 才释放，
  文件变化时 `:FILE_CONFLICT` 且看板零变化。
- **阶段三**：本包已实现 `take-or-die`、请求去重、逻辑死亡与 `:term-id`；
  运行时按 `agent-id` 停止旧会话、槽位补新由 `hc.compose` / `hc.server`
  负责，不在本包启动线程。
- **阶段四**：`begin-action` / `advance-action` 的 `:shell` 替身由真实
  Shell 适配器替换；`$W$` 串行、会话边界与输出保留仍属 `workspace_gate` /
  服务端。
- **Relay**：本包的 `:ledger` 只是根任务镜像。组合层在 `finish` 返回
  `:ledger-released` 时，应在同一外层事务中调用 `hc.core.relay` 的
  `finish-root-task`，并传入该看板所属执行 tenure 的 `:tenure-id`；
  不能只改本包镜像。多个并发执行 tenure 各有自己的看板与台账。
- **持久化**：本包不写存储。组合层负责把 `kernel/snapshot` 的当前态作为
  一次事务提交；恢复时用 `:initial-board` 注入，并先跑
  `check-invariants`。组合层把纯计算得到的整块 board 写回时，必须使用
  `replace-board!`（期望值 = 计算前读取的 board）；CAS 失败说明有并发命令
  先提交，必须用新 board 重算，不能覆盖。

## 11. 明确决策与未实现项

- 文件依赖 `finish`（`:read-set` 或 `:verified-read-set`）的 W 顺序是硬约束：
  取得 W → verifier（仅 `:read-set`）→ board
  CAS/提交与事件发布 → `finally` 释放 W。普通 `execute!` 一律拒绝
  `:WRITE_GATE_REQUIRED`；没有 W 端口时不得降级为“先校验、后无锁 CAS”。
- 认领允许一个个体同时持有多个任务认领，但同一时刻最多一个有副作用动作
  （`Actor.active-action-id` 与动作阶段共同约束）。
- 同一 claim 下动作可按序推进：`:finished` 历史动作不阻塞新的
  `begin-action`；`:claimed`/`:preparing`/`:waiting-gate`/`:applying`/
  `:running`/`:uncertain` 仍视为在途并拒绝并行动作。
- 动作阶段是证据标签，不是必须逐级经过的路径：`begin-action` 已确认认领
  与身份后，`:claimed` 可以直接进入 `:applying` / `:running`；需要
  write-ahead 证据的文件批仍应在进入 `:applying` 前记录 `:preparing` /
  `:waiting-gate`。这不改变“同一时刻最多一个活动动作”的约束。
- `finish` 在任务置 `:done` 前移除同任务的 `:claimed`/`:preparing`/
  `:waiting-gate` 动作并报告 `:removed-action-ids`；`:applying`/
  `:running`/`:uncertain` 仍拒绝完成。holder 退休同样清除其全部未完成
  动作与认领，避免死身份留下孤儿动作。
- `split` 允许非父项 owner 拆分，但不改变父项归属；新普通子项默认公开。
  这避免内核替个体派活。
- `split` 在父项被他人认领时拒绝；原主拆分则在同一事务释放旧认领，符合
  “下一提交边界接受结构变化”。
- `cancel` 叶子定义按“无未完成子项”；取消不自动满足依赖。外部等待者
  的 `:wait-for` 会移除被取消项，等待集为空时回到 `:ready` 并发布
  `:dependency-cancelled`，由个体重新决定结构，不伪造完成。
- `replace` 在父项/外部等待者中原子换 id；根任务 `:ledger-key` 转移到替代项。
- `:uncertain` 与 `:applying` / `:running` 同属在途动作集合：`split`、
  `cancel`、`replace`、`finish`、`take-or-die`、`release` 与 `begin-action`
  都拒绝在 `:uncertain` 上继续改写结构。根 §3.3 的最小拒绝集只列
  `:applying` / `:running`；本实现更严，因为 `:uncertain` 表示外部效果
  未知，此时改写任务结构会丢失对账依据。这是本地实现选择，不改变根规格。
- `reopen` 只恢复 `:ready` 与根台账，不把已恢复的父项重新阻塞。
- 根任务 `:ledger-key` 在任务完成后仍保留在任务记录上，因此 `create-task` 把历史任务也视为键占用者；新任务不得复用旧键。`reopen` 只重新登记同一任务的原键，若键已被任何当前 ledger 条目占用则拒绝 `:LEDGER_KEY_IN_USE`。
- `reopen` 会清除已死 `:owner-id`，避免重开的任务落到无法提交的旧身份；
  原 owner 仍存活时保留其普通责任。
- `abandon`、多 worktree `fork`/`join`、master 集成锁与真实 Git 由组合层
  实现：`hc.compose.fleet` 负责 worktree/fork/join，`hc.compose.fleet.git`
  负责真实 Git，`abandon` 由 holder 程序原语经 fleet 转发；内核只维护单个
  worktree 的看板控制记录。持久化恢复扫描与模型提示注入属于 server/组合层
  适配，不在内核内实现。
