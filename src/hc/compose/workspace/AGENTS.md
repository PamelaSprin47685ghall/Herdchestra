# `hc.compose.workspace` 局部规格（受保护文件程序桥）

> 权威顺序：根 `AGENTS.md` > `src/AGENTS.md` > `src/hc/compose/AGENTS.md` > 本文件。
> 本文件只记录本包的实现选择；协议语义以根 §4.2、§4.3、§4.4 与 §3.3 为准。

## 1 责任

把两个独立核心模块装配成程序执行器可调用的受保护文件桥：

- `hc.core.workspace-gate` 决定一次批量读写是否合法：无锁读取、`stamp`
  凭据、批内内存视图、完整 dry-run、全局写入口 `$W$`、确定性应用与结果
  分类。
- `hc.core.swarm-stm` 决定身份、认领与动作生命周期：活身份、有效
  `:claim-id`、`begin-action`、`:applying` 资格固定、`:finished` 结果发布、
  硬失败后的 `:uncertain` 责任。

本包不实现程序语言、`defprogram` 求值、`where`/`park`/`unpark`、Shell
会话、Relay 台账、模型调用或持久化。程序求值器由应用装配注入
（`hc.server.program/evaluate-program!`），因此 `hc.compose.*` 不依赖
`hc.server.*`。

受保护 `finish` 只从 `hc.compose.mission.proof` 取一个不透明的 W 能力。
该命名空间是叶子，不包含 mission 状态机；本包不依赖 `hc.compose.mission`
的其他部分。

## 2 公开面

门面命名空间：`hc.compose.workspace`。

| 面 | 函数 |
|---|---|
| 运行时 | `make-runtime`, `request-stop!`, `clear-stop!`, `runtime-snapshot` |
| 程序执行 | `run-program!` |
| 单操作 | `execute!` |
| 求值器端口 | `workspace-port`, `control-port` |

`make-runtime` 接收 `:root`、`:kernel`（Owner 路由时可为 nil）、
`:write-gate`（缺省新建）、`:clock`、可选的 `:evaluate`（或
`:program-runtime`）、Owner 句柄 `:execute-board!` / `:board-snapshot`、
批限制 `:limits` 与测试注入 `:verify-read-set` / `:apply-fn`。它把内核的
`:verify-read-set` 设为 `workspace-gate/read-set-verifier`（Owner 路由时
只保存在 runtime 中），并把 `workspace-gate` 的看板适配器接到同一内核或
Owner 邮箱。`$W$` 与看板由整个 swarm 共享；本包绝不创建第二个 `$W$`。

Owner 路由要求 `:execute-board!` 与 `:board-snapshot` 成对提供。此时
所有程序看板命令与 `resolve-action` 都走 `:execute-board!`，没有直接
内核写入。`finish` 的非空 `read-set` 由桥自己取得 `$W$`、调用
`workspace-gate/read-set-verifier`，再以 `:read-set []` +
`:verified-read-set` 调用 Owner 的三参阻塞形式
`(execute-board! command timeout-ms {:write-gate-proof <opaque>})`。
`:write-gate-proof` 是 `hc.compose.mission.proof` 的不透明单例，只在桥
已持有 `$W$` 时附加；EDN 布尔值、字符串、关键字或映射一律不被信任。
Owner 必须把该能力转成内核的 under-W 执行入口，否则内核返回
`:WRITE_GATE_REQUIRED`。桥同时在结果与 `:result-ref` 上带回已核验集合，
证明本身不进入回执、状态或程序结果。
校验失败返回 `:FILE_CONFLICT`，看板不变，`$W$` 在 `finally` 释放。

### 2.1 `run-program!`

```clojure
(run-program! runtime
  {:agent-id "..." :claim-id "..." :task-id "..."
   :attempt-id "..." :call-id "..."
   :program "(defprogram ...)"      ; 或 :source
   :intent "..." :role :clj-worker
   :tenure-id "tenure-1" :lane :plan
   :holder? false :s1? false :phase :executing
   :capabilities #{...} :context {...}})
;; => {:status :succeeded | :failed | :cancelled | :outcome-unknown
;;     :value <last value or park value>
;;     :receipts [ ... ] :effects [ ... ] :error {...} | nil}
```

- 阻塞调用；Owner 在 Missionary task 上派发。
- `:holder? true` 时 `run-program!` 强制把 `:role` 归一为 `:clj-worker`；
  holder 是带 holder 标志的 worker，绝不继承 `:dedicated-worker` 的能力
  面，也不跳过静态 Shell 门。`mission.worktree` 保留同一防线。
- Owner 工具载荷顶层的 `:draft-path`、`:write-unlocked?` 与
  `:readable-paths` 会合并进程序的 `:context` 并保留在请求顶层（因此进入
  call-id 指纹）；Plan 的草稿写权与附件读取依赖这三个字段，桥接层不得
  丢弃。`:context` 中同名键由顶层载荷覆盖。
- Owner 工具载荷顶层的 `:tenure-id` 与 `:lane` 原样进入求值器请求（因此
  也进入 call-id 指纹与程序 control 上下文）。程序控制面（`:review`、草稿
  命令、看板命令）必须看到与产生该工具调用的模型调用相同的任期；桥接层
  不得在 relay 后把迟到调用重新解析到继任任期。缺了这两个字段时
  `(review ...)` 会拿到 nil 任期并以 `:INVALID_REQUEST` 拒绝，台账和写锁
  都不会前进。
- Plan 首稿在 `settle!` 内走 under-`$W$` 的 `:pre-apply!`：先在 Owner
  `:claim-draft` 预留相对路径，再在真实 dry-run/apply 前检查目标缺失；
  已有目标返回 `:DRAFT_NAME_TAKEN`，claim 在失败提交或失败终态释放。
  `:files/applied` 的 `:changed-paths` 统一为工作区相对路径，供 app
  `applied-draft-paths` 读取。
- Plan 没有 swarm actor、claim 或 task。`settle!` 对 `:clj-plan` 的
  写批次带 `:plan-draft`（pinned `:tenure-id`、`:call-id`、草稿路径、
  `:request-id`）；Owner board adapter 据此走 mission 的
  `:check-draft`（dry-run 前）与首稿 `:claim-draft`（under-`$W$` 的
  pre-apply 内），而不是 swarm eligibility。首稿在 dry-run 后只做一次
  只读 `:check-draft` 复验；固定路径的修订没有 pre-apply claim，所以在
  dry-run 后、apply 前用 `:claim-draft` 固定资格；该调用在固定路径上也
  会创建短期 claim，两个并发修订不能同时认为自己是唯一写者，成功
  `set-draft` 或失败 `release-draft` 后清除。两种路径都重验 tenure 与
  路径，所以 relay 后的迟到写会在 `:final-check` 以
  `:TENURE_RETIRED`/`:UNKNOWN_TENURE` 拒绝，且不会触碰文件。路径权限
  仍由程序 `check-plan-write`（只允许 `plans/<name>.md` 且写锁已开）
  与 mission 的写锁/claim 决定；这不是绕过 `$W$` 或 dry-run。
- 期望拒绝、文件冲突、认领变化与程序错误都返回数据，不抛异常。
- 未注入求值器时返回 `:failed` / `:PROGRAM_RUNTIME_UNAVAILABLE`，不猜测
  执行环境。
- 求值器返回 `:PARKED` 时映射为 `:succeeded` 并带 `:parked? true`；
  `:value` 保留完整 park 回执（`:reason`、`:payload` 的 candidates/nearest
  等），不得只投影 `:parked`；后续 `unpark` 仍由程序运行时负责。
- 停止是协作式的：`request-stop!` 只登记 attempt-id，能力函数在下一次
  调用前观察；不中断线程，不回滚已应用的提交。
- `request-stop!` 返回 `{:status :stop-registered | :unknown
  :attempt-id ... :call-id ... :ended? false}`。停止请求不是“动作已结束”
  的证据，因此 `:ended?` 恒为 false，调用方不得据此合成 call terminal；
  terminal 只来自求值器自己的结果。`:call-id` 只在映射仍存活时给出，
  `:unknown` 表示 id 为空或运行时已关闭。
- 成功或失败终止后清理该次会话；`PARKED` 保留会话供恢复。程序名不是
  会话别名；合成 `unpark-<12 hex>` 包装不登记会话（真实程序名
  `unpark-foo` 不受影响）。目标经 `unpark` 终止时由
  运行时 `:before-terminal` 发布屏障（在终态 segment 交付前同步执行）
  清理 call-id/attempt-id，并把
  receipts/effects 放入有界 terminal-evidence 缓存，保证 call-id 重放
  仍有回执；包装的 `:before-terminal`/`:on-terminal` 被运行时丢弃，
  不会误删目标。该屏障必须非阻塞且不得重入运行时。

`execute!` 接受同一身份字段与一个 `:op`：

- `:cat` / `:stat` / `:ls`：无锁观察，返回 `:value` 与 receipt；
- `:write` / `:mkdir` / `:rm` / `:mv`：一次性批视图 + 完整 settle；
- `:split` / `:claim` / `:finish` / `:cancel` / `:replace` / `:release` /
  `:reopen` / `:comment` / `:update` / `:take-or-die` / `:park-slot`：
  看板命令；`finish` 的非空 `read-set` 自动走 `$W$` + verifier；
- `:resolve-action`：仅把调用者自己的 `:uncertain` 动作推进到
  `:finished`，用于硬失败后的现场核对；恢复来源的动作必须带显式
  `:result-ref`，不能只靠默认 `:RESOLVED` 收束；
- `:acknowledge-recovery`：在恢复后的工作区里，所有未完成动作都显式
  resolve 之后，用非空 `:evidence` 记录恢复确认；它不改变动作阶段；
- `:retry-publish`：只按 `:request-id` 调用 `workspace-gate/retry-publish!`
  重试纯看板发布，绝不重跑程序或文件批。`run-program!` 也接受
  `{:op :retry-publish :request-id ...}` 并直接转交。

`begin-action`、`advance-action`、`batch` 不属于程序能力面，`execute!` 与
`control-port` 都拒绝。

### 2.2 求值器端口

`workspace-port` 返回 `hc.server.program/runtime` 期望的 `:workspace` 映射：

```clojure
{:open-session (fn [ctx] session)
 :cat   (fn [session path] text)
 :stat  (fn [session path] map)
 :ls    (fn [session path] vector)
 :write (fn [session path content] staged)
 :mkdir (fn [session path] staged)
 :rm    (fn [session path] staged)
 :mv    (fn [session source target opts] staged)
 :facts (fn [session] {:read-set .. :checks .. :ops ..})
 :settle! (fn [session request]
            {:status :APPLIED|... :session next-session :result commit-result})}
```

`control-port` 返回 `{:execute! (fn [ctx {:primitive kw :request map}])}`，
只处理 worker 看板原语；其他原语交给可选的 `fallback`，否则返回
`:UNSUPPORTED_OPERATION`。程序可见的文件函数全部是“单个 EDN map 参数”；
`where`/`whereAll`/`park`/`unpark`/`call` 由程序运行时拥有。

`settle!` 的请求至少带 `:request-id`；`request-id` 在同一个程序内按
drain 单调递增，`workspace-gate` 以它做幂等。成功应用后返回**全新的空
会话**，不携带旧 `read-set`（读依据属于单次批；跨 settle 保留会重复校验
旧读取）。非 `:APPLIED` 时返回原会话与分类结果，程序运行时必须立即中止。

看板适配器有两个实现：

- `board/adapter`：直接读写进程内 `hc.core.swarm-stm` 内核；
- `board/owner-adapter`：把 `:begin-action` / `:advance-action` 通过
  `(fn [command] receipt)` 发给唯一看板写者（Owner 邮箱），并用
  `(fn [] board-map)` 读取当前不可变看板。receipt 既可以是内核命令结果，
  也可以是 `{:ok? ... :board ... :result ...}`；两者都会归一化。
  `make-runtime` 的默认路径使用内核适配器；Owner 作为唯一写者的装配
  可以直接用 `owner-adapter` 构造 `workspace-gate`。

## 3 提交流程（与根 §4.2 七阶段对齐）

1. **读取**：`cat` / `stat` 进入批内 `read-set` 与结构检查；`ls` 是纯探查。
   读取前后 `stamp` 变化由 `workspace-gate` 标记 `:FILE_CONFLICT`。
2. **批内视图**：`write` / `mkdir` / `rm` / `mv` 只改内存；异常、`park`
   与预演拒绝都不触碰真实磁盘。
3. **settle**：取得 `$W$` → 看板适配器早期核对活身份/认领/任务 → 完整
   dry-run 复核全部 `stamp` 与结构条件 → 短 STM 固定 `:edit` 动作的
   `:applying` 资格 → 确定性安装 → 纯控制发布 `:finished`。
4. **无变更结论**：`read-set`/`checks` 非空但 `ops` 为空时同样走 settle，
   产生一次“空变更提交”，复核结论所依赖的文件事实。
   若会话已无 claim（例如 `finish` 已释放认领），`facts` 不把 read-set
   伪装成空观察，而是返回 `:read-only-conclusion? true` 与
   `:discarded-read-set-count`：这些读取不能发布新的看板结论，桥也不为
   它们创建动作。
5. **拒绝**：普通拒绝在触碰磁盘前发生，真实文件零修改，不创建动作。
6. **硬失败**：文件可能已部分改变；动作留在 `:uncertain`，`R(a)` 保留
   责任，由 `:resolve-action` 显式收束。

`finish` 的非空 `read-set` 由 `execute-under-write-gate!` 在 `$W$` 内调用
verifier，随后在同一段 `$W$` 内完成看板 CAS 与事件发布。`finish` 本身
不 drain、也不延迟批内写入。程序先 `write` 再 `finish` 时，`finish`
只做两件事：在 `$W$` 内用真实工作区复核它的 `read-set`（因此看到的是
写入前的旧内容），然后立即提交看板 `finish`。暂存写入仍留在批内；
`finish` 释放认领后，程序结束时的 settle 可能因认领失效返回
`:CLAIM_CHANGED`，真实文件保持旧内容——这是规格接受的顺序语义，桥不会
为了让 settle 成功而提前落盘或改写 `finish` 的时点。Owner 路由下同样先
复核、立即 finish，再以 `:verified-read-set` 记录已核验集合。

## 4 身份与动作

- `:actor-id` 永远来自会话（`run-program!` 的 `:agent-id`），程序请求里的
  `:agent-id` 被丢弃；程序不能用别人的身份提交。
- 受保护编辑必须有当前 `:claim-id`。认领由程序自己通过 `(claim ...)` 获得；
  会话在成功的 `claim` / `release` / `finish` / `split` / `cancel` /
  `replace` 后更新当前认领，settle 使用会话中的认领，不信任请求里的旧值。
- 看板 id 在内核中是关键字。模型与传输层常用字符串，因此会话在
  `open` 与每条命令上把 `:agent-id` / `:claim-id` / `:task-id` /
  `:action-id` / `:parent-id` / `:owner-id` / `:wait-for` / `:children` /
  `:replacement` / `:expected-cond` 中的字符串 id 归一化为关键字；已经是
  关键字的 id 保持不变。
- 动作创建只在 `$W$` 内、dry-run 成功之后发生。`begin-action` 与
  `:applying` 是两个 STM 转换；第二个失败时桥尝试把动作收束为
  `:uncertain`，绝不静默遗忘。
- 事件持久化 hook 返回 `{:ok? false ...}` 时，`begin-action` /
  `:applying` 返回 `:BOARD_EVENT_PERSIST_FAILED` 且带 `:persisted? false`；
  桥不进入 apply。把内存动作收束为 `:uncertain` 只是最佳努力，服务仍保留
  `:persist-error`。恢复后的工作区若还有 `:uncertain` 动作或旧 gate 证据，
  新 `$W$` 以 `:fleet-recovery-hold` 持有并拒绝写入，直到显式确认。
  重复的 `begin-apply!` 若看到内存动作已是 `:applying`，先发一次
  `:checkpoint` 事件再返回 permit，避免重放绕过 write-ahead。
- 每个 `request-id` 最多产生一个编辑动作；已 `:finished` 的 request-id
  重放返回 `:CALL_REPLAY_MISMATCH`，不重复落盘。
- 首稿 claim 的释放以终态为准：`:COMPLETED`、`:completed`、`:succeeded`
  与 `:ok` 都保留 claim 供 app 的最终 `:plan/write-draft`；其他终态释放。
- 动作发布只重试纯控制状态，绝不重放文件写入。
- `split`、`claim`、`release`、`cancel`、`replace` 与 `take-or-die`
  在批内仍有待写文件时拒收 `:PREPARE_REJECTED :pending-file-changes`：
  这些命令可能释放或替换当前认领，而暂存的写入在认领消失后无法提交。
  程序应先 settle/`call` 排空或拆成两个程序。`finish` 例外：它不 drain、
  不延迟，只在 `$W$` 内复核旧内容并立即完成看板；暂存写入保持 pending，
  之后的 settle 可能因认领已释放而 `:CLAIM_CHANGED`，桥不掩盖该结果。

## 5 结果码

本包沿用 `hc.core.workspace-gate` 的分类，并把桥生成的具体错误放在
`:code`：

| 情形 | `:status` | 典型 `:code` |
|---|---|---|
| 应用完成 | `:APPLIED` | — |
| 文件依据冲突 | `:FILE_CONFLICT` | `:stamp-mismatch` / `:changed-during-read` |
| 结构条件不符 | `:STRUCTURE_CONFLICT` | `:missing` / `:not-a-file` |
| 批内预演拒绝 | `:PREPARE_REJECTED` | `:deleted-in-batch` / `:target-exists` |
| 身份或认领失效 | `:ACTOR_INACTIVE` / `:CLAIM_CHANGED` | `:claim-task-mismatch` |
| Plan draft 任期失效 | `:TENURE_RETIRED` / `:UNKNOWN_TENURE` | `:TENURE_RETIRED`（details 带 `:current-plan-tenure-id`） |
| 等待写入口被停止 | `:WAITING_FOR_WRITE_GATE` | `:cancelled-while-waiting` |
| 恢复证据未解决 | `:RECOVERY_UNRESOLVED` | `:recovery-unresolved` |
| 看板事件未持久化 | `:BOARD_EVENT_PERSIST_FAILED` | `:BOARD_EVENT_PERSIST_FAILED`（`:persist-error` 保留底层） |
| 硬失败 | `:HARD_FAILURE` | `:outcome :unknown` |
| 未注入求值器 | `:failed` | `:PROGRAM_RUNTIME_UNAVAILABLE` |
| 程序被停止 | `:cancelled` | `:STOP_REQUESTED` |

Owner 看到的错误形状为 `{:code :message :details :retryable?}`。普通拒绝
默认不自动重试；`STALE_CURSOR` 与未被停止的 `WAITING_FOR_WRITE_GATE`
标记为可重试，硬失败永远不自动重试。

## 6 不得

1. 不在程序能力面暴露 `workspace-gate` 的原始 `BatchSession` 或
   `commit!`；所有写入必须经过本桥。
2. 不让 `finish` 的非空 `read-set` 走普通 `swarm/execute!`；必须持有
   `$W$` 并由 verifier 复核。
3. 不在 `$W$` 内等待模型、Shell 或另一把非重入锁。
4. 不把 `:applying` 动作当成已完成；不把硬失败翻译成普通拒绝。
5. 不在 `run-program!` 里自行解释 `defprogram`；求值器由应用装配注入。
6. 不在组合层重新实现 `stamp` 比较、dry-run 或看板转换。
7. 不把内存中的 `:finished` / `:published?` 当成已持久化；发布重试必须
   通过 `:checkpoint` 事件再走一次持久化 hook。

## 7 测试

`test/hc/compose/workspace_test.clj`：

- 真实临时文件：批内 `cat`/`write`、`settle!` 落盘、`$W$` 释放、
  动作 `:finished`、任务保持 `:ready`；
- 读取后外部修改 → `:FILE_CONFLICT`，真实文件与看板均不变；
- 只读依赖改变、写目标未变 → `:FILE_CONFLICT`，写目标不变；
- 持有 `$W$` 时读取不排队，立即返回当前内容；
- 发布首次冲突后只重试纯控制发布，文件 apply 次数为 1；
- 应用期间登记停止仍完成固定批，并返回 `:cancel-after-apply?`；
- 无认领写入 → `:CLAIM_CHANGED`，文件不变；
- `read-set` 非空、`ops` 为空的空变更 settle；
- `finish` 的非空 `read-set` 在 `$W$` 内复核，成功完成任务，冲突时看板不变；
- `write` 后 `finish`：`finish` 返回时真实文件仍是旧内容、看板任务已
  done，暂存写入仍在批内；随后 settle 返回 `:CLAIM_CHANGED`，文件不变；
- 程序能力白名单拒绝 `begin-action`/`batch`；
- `run-program!` 成功路径、receipts/effects、无求值器失败、协作停止；
- `run-program!` 对 `:holder? true` 强制 `:role :clj-worker`，即使调用方
  误传 `:dedicated-worker`；
- EDN 程序表单的 source 回退使用 `hc.core.ufss/write-str`，可被 UFSS 读回；
- `where` 歧义 park 的完整诊断（candidates/nearest）保留在 `run-program!`
  的 `:value`；
- 真实多段 `park`/`unpark`：目标完成后 call-id/attempt-id 别名被清理，
  合成 `unpark-*` 包装不留下会话键；
- 在 `$W$` 队列中的 settle 收到协作停止后离队，不应用文件；
- `execute!` 一次性写入、未知操作不产生副作用；
- 硬失败留下 `:uncertain` 动作并可显式 resolve；
- begin 事件持久化失败不进入 apply、同 request-id 不重放；发布持久化
  失败经 checkpoint 重试（`workspace_write_ahead_test`）；
- Owner 邮箱 receipt 形态的 `owner-adapter` 发布；
- 随机文本的受保护写入往返属性。

### 8.3 UFSS 程序来源

`run-program!` 接受字符串 `:source` 或已读 EDN `:program`。非字符串值用
`hc.core.ufss/write-str` 序列化为模型可读的 UFSS 源码；单个
`(defprogram ...)` 表单整体序列化，表单序列取第一个。不得使用 `pr-str`，
否则引号、反斜杠与换行会变成转义噪音。

`test/hc/compose/workspace_owner_integration_test.clj` 启动真实
`hc.compose.owner`（`-M:test:compose`），用 Owner 权威看板与三参
`execute-board!` 证明跑真实文件系统：写入后 finish 保持旧文件、任务
done、后续 settle `:CLAIM_CHANGED`；无待写 finish 成功并返回
`:verified-read-set`；根任务 finish 在真实 `$W$` 内复核并把同一依据写进
任务 `result-ref`，随后触发接力屏障；程序自带的伪造 `:write-gate-proof`
以及线缆层布尔/EDN 伪造都被 Owner 以 `:WRITE_GATE_REQUIRED` 拒绝；
verifier 运行时断言 `$W$` 已持有，Owner 提交时也断言 `$W$` 仍被持有；
finish 返回后断言已释放，verifier 抛错时也释放并返回 `:FILE_CONFLICT`；
程序结果、提交状态与 `:owner/committed`
通知都不含证明对象。故障注入下
首次提交 `:APPLIED :published? false`，
`:retry-publish` 后 `:published? true` 且文件 apply 次数仍为 1；已完全
发布的请求再 retry 返回 `:UNSUPPORTED_OPERATION`。真实场景实测：
`FIRST {:APPLIED :published? false :retry :publish-only}`、文件 `new`、
apply=1；同一 request-id retry 后 `{:APPLIED :published? true}`、文件仍
`new`、apply 仍 1；再次 retry 返回 `:UNSUPPORTED_OPERATION`。

`test/hc/compose/workspace_integration_test.clj` 用真实
`hc.server.program/evaluate-program!` 跑端到端路径：程序读取并写入
真实临时文件、程序用 `stat` 的 `stamp` 构造 `read-set` 后 `finish`、
程序 `write` 后 `finish`（空 `read-set`，finish 时文件仍旧、任务已
done、后续 settle 因认领失效失败）、程序 `stat` 旧 stamp 后 `write` 再
`finish`（同一断言，finish receipt 带旧 read-set）、Owner 路由下的写入与
finish、以及 Plan 顶层载荷的 `:draft-path` / `:write-unlocked?` /
`:readable-paths` 经 `run-program!` 进入程序上下文后完成附件读取与草稿
写入。`workspace-test` 另用真实程序运行时验证 Plan 顶层
`:tenure-id`/`:lane` 进入 control 上下文，`:review` 原语拿到固定任期而
不是 nil；并验证 `:actor-id :plan` 的首稿与固定底稿修订在 board 没有
Plan actor/claim 时仍能通过 mission `:check-draft`/`:claim-draft`、
写锁与 `$W$` 落盘，且 relay 后的旧 tenure 写被 `:TENURE_RETIRED`
拒绝、不产生文件。`workspace-owner-integration-test` 用真实 Owner
mailbox 验证同一条路径和 app 侧 `:plan/write-draft` 的 tenure 固定。
server
命名空间只由测试 require，compose 源码不依赖 server。

运行：

```bash
clojure -M:test -n hc.compose.workspace-test
clojure -M:test -n hc.compose.workspace-integration-test
clojure -M:test:compose -n hc.compose.workspace-owner-integration-test
```

真实文件测试只使用测试自建临时目录，结束后删除。

## 8 F01–F15 审计（本包负责范围）

共同前提：读取不加锁；普通提交与受管 Shell 共用同一个 `$W$`；文件判据只用
`mtime` + `size`；普通拒绝在真实修改前发生，硬失败保留不确定现场。

| 编号 | 本包证据 | 结论 / 缺口 |
|---|---|---|
| F01 | 读取后外部修改 → `settle!` 返回 `:FILE_CONFLICT`，文件维持外部结果，会话/身份仍可重读 | 覆盖 |
| F02 | 只读依赖 `api.txt` 改变、写目标未变 → `:FILE_CONFLICT`，写目标不变 | 覆盖（`read-only-dependency-conflict-rejects-before-write`） |
| F03 | 批内视图与 dry-run 由 `workspace-gate` 负责；桥只提交完整批 | gate 测试覆盖；桥无部分落盘路径 |
| F04 | `mv` 后 `cat`/`write` 使用同一批内视图；删除后未重建写入由 gate 拒绝 | gate 测试覆盖；桥使用 session 而非真实路径探查 |
| F05 | `apply-fn` 内登记停止后仍返回 `:APPLIED` 与 `:cancel-after-apply? true`，文件完整落盘；排队中的 settle 收到停止会离队且不应用 | 桥覆盖“已固定批次完成”与排队离队；取消入板/wake-all 属 Owner/Relay |
| F06 | Owner 邮箱发布首次抛 `:STM_CONFLICT`，重试后 `:APPLIED`，文件 apply 次数严格为 1 | 覆盖（`publish-conflict-retries-without-reapplying-files`） |
| F07 | 持有 `$W$` 时 `cat` 仍立即返回 | 覆盖（`reads-do-not-wait-for-w`） |
| F08 | 继承 gate 的 `(mtime,size)` 快速判据；不添加哈希或隐藏版本，不宣称内容完全相等 | 覆盖边界，由 gate 文档声明 |
| F09 | 不比较 `atime`；其他读者只改变访问时间不影响提交 | 继承 gate；无桥侧 atime 逻辑 |
| F10 | `read-set` 非空、`ops` 为空的 settle 仍取得 `$W$` 复核并发布空变更结论 | 覆盖（`read-set-only-settle-verifies-without-writing`） |
| F11 | Shell 持锁等待与普通写等待链属 Shell 适配层 | 不在本包；未实现 Shell |
| F12 | 会话子活动边界与 `$W$` 保留属 Shell 适配层 | 不在本包；未实现 Shell |
| F13 | 注入 apply 硬失败 → `:HARD_FAILURE`、动作 `:uncertain`、可 `:resolve-action` | 覆盖（`hard-failure-keeps-an-uncertain-action-and-can-be-resolved`） |
| F14 | 锁内校验后的外部自由写入是 best-effort 边界；桥不把它误判为两个受保护提交互斥失效 | 设计边界；无确定性外部写注入测试 |
| F15 | `park-slot` 与输入唤醒属 swarm/Owner；本包不把等 `$W$` 当主动休眠 | 不在本包 |

### 8.1 `finish` 顺序结论

`finish` 不 drain、不延迟暂存写入：

1. 非空 `read-set`：在 `$W$` 内按真实工作区复核旧内容；Owner 路由下以
   空 `read-set` + `:verified-read-set` 调用三参 Owner 句柄并传
   `{:write-gate-proof <opaque>}` 能力，Owner 需把能力转成内核的 under-W
   入口；内核路由下直接把原 `read-set` 交给
   `execute-under-write-gate!`。
2. 看板 `finish` 立即提交并释放认领。
3. 暂存 `ops` 与批视图原样保留；程序结束时 settle 可能返回
   `:CLAIM_CHANGED`，真实文件保持旧内容。这是规格接受的顺序语义。
4. 桥不在 finish 内 settle、不重置批视图、不延迟 finish 到写入之后。

### 8.2 边界与已闭合项

- `request-stop!` 已把 attempt/call id 对应的 pending settle request-id
  转给 `workspace-gate`，排队中的 settle 会离队；已进入 `:applying` 的
  批次仍完成。取消入板与 wake-all 由 Owner/Relay 负责。
- 真实 Owner 三参能力已由 `hc.compose.owner` 的 `submit-board-command!`
  接收 `:write-gate-proof` 并传入 mission env；真实 Owner/FS 测试通过。
- 发布持续失败时 `commit!` 返回 `:APPLIED :published? false`，动作保持
  `:applying`；桥返回 `:files/publish-pending` effect，并提供
  `:retry-publish` 操作/`retry-publish!` 函数，只重试发布、不重放文件。
- Owner 路由的 `finish` 以三参句柄把 `:read-set []` +
  `:verified-read-set` 和不透明能力交给 Owner；mission 只在能力有效时
  接受该形式，并把它写进任务 `result-ref` 的 `:read-set` 与
  `:verified-read-set`。根任务路径也在 `:finish-root-task` 中保留同一
  依据，接力屏障前的看板提交与 relay 释放仍是一个原子转换。程序沙箱
  不提供 `hc.compose.mission.proof` 命名空间，模型/工具载荷无法构造该
  能力。
- F11/F12/F14/F15 需要 Shell 适配层、外部写注入与 Owner 调度测试，
  本包不实现，也不声称已运行。
