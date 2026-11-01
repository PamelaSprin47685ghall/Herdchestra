# `src/core/scope` 包级规格说明书

> 本文件是根 `AGENTS.md` §6.2 在 Scope 包内的展开。根规格是唯一语义来源；两者冲突时以根规格为准，并立即修正本文件与代码。

## 0 这个包做什么

一个长程委托会跨越多轮工具调用。上下文窗口有限：不压缩会撑爆，乱压缩会把因果洗掉。Scope 只管一件事——**决定哪些已发生的拍子还能出现在下一次出站请求里**，并用 `retention` 额度把"还能保留几拍实质推进"变成模型自己负责的账。

Scope 不碰宿主的持久历史。它只产出请求副本（§6.2）。

## 1 依赖边界

- 包内模块**不 import 本目录之外的任何文件**：拒收类型 `ScopeRefusal`、参数错误 `ScopeArgumentError`、注解解析、UTF-8 规则全在包内。核心其他包的 `errors.mjs` / `util.mjs` 变化不影响本包，本包也不出现在 `src/core/index.mjs` 里；组合层显式 import `src/core/scope/index.mjs`。
- 不做 I/O：记录存储由调用方注入（`store.append(records) -> Promise`）。文件位置 `.Herdchestra/{session}.jsonl` 由组合层决定，本包只定义记录形状。
- 模型输入一律不抛异常：拒收是值（`ScopeRefusal`）。只有**接线错误**（事件缺 `sessionId`、`store` 没有 `append`）才抛 `ScopeArgumentError`。

## 2 模块

| 文件 | 职责 |
|---|---|
| `errors.mjs` | `SCOPE_CODES`、`ScopeRefusal` / `ScopeArgumentError`、四值 `SCOPE_KINDS`、`RESET_MEMBER`、豁免成员、钉住成员 |
| `annotation.mjs` | `parseAnnotation(raw)`：模型写的 `scope` 值 → 冻结记录或拒收；逐 kind 校验必填字段与"其余字段不接受" |
| `ledger.mjs` | `Retention`（额度）、`TurnLedger`（回合）、`ScopeBook`（一回合只结算一次；回合关闭时拒绝全 `meToo`） |
| `admission.mjs` | `Admission.check`：准入四规则 |
| `records.mjs` | 持久记录形状、`readColdState`、`coldStartNotice` |
| `projection.mjs` | `projectRequest`：出站请求副本 |
| `scope.mjs` | `Scope`（一会话的全部事实：admit → settle → persist → project）、`ScopeTable` |
| `hooks.mjs` | 宿主适配器唯一接口：`admit` / `settle` / `closeTurn` / `project` / `ask` |
| `index.mjs` | 包入口 |

## 3 语义

### 3.1 `scope.kind` 枚举与字段（§6.2、§6.2）

| kind | 必填 | 不接受 | 效果 |
|---|---|---|---|
| `resetScope` | `newScopeGoal` 非空白字符串；`newScopeRetentionBudget` 正整数（无上限） | 其他字段 | 结束当前闭环，整体替换目标与额度；宿主走 `ScopeReset.commit` |
| `keepLast` | 无 | 其他字段 | 额度 −1 |
| `removeLast` | `replacement` 非空白字符串 | 其他字段 | 不扣额度；记一次空转；登记被抹去的上一拍 |
| `meToo` | 无 | 其他字段 | 同回合确认；先到则暂存 |

缺席、非对象、`kind` 非法、字段非法、多余字段：拒收，状态不变。

**任何工具都可以带 `resetScope`**（§6.2：每次工具调用都带 `scope` 注解）。注解决定这一拍是什么，工具名不决定。是否真的开新 Scope 由「这次 reset 是否结算」决定：

- 结算成功（`outcome: "settled"`）→ 额度整体替换，这次调用的 `callId` 成为当前 Scope 的边界。
- 被拒收（校验失败、未初始化等）→ 不开边界，额度不变。
- 同一回合里第二次 `resetScope` → `outcome: "confirmed"`，不开边界（§6.2 一回合一次结算）。

边界是**结算事实**，不是注解出现。`Scope.project` 自动把最新结算的 reset 的 `callId` 传给投影；投影只按它找边界，找不到时才退回「请求里最新一个带 `resetScope` 注解的调用」。

### 3.2 retention（§6.2）

- 容量，不是回合数也不是调用次数，无上限。只有 `keepLast` 扣 1；`removeLast` / `meToo` 不扣。
- 额度为 0 后，非豁免工具必须 `resetScope`。
- 豁免：`negotiate`、`askPreviousScope`。它们同步返回最新快照，永不 park；既不扣额度，也不因额度为 0 被拒。
- **「最新」指最新已提交的事实。** 结算在 `ScopeBook` 的副本上进行，落盘成功后才 `adopt` 回活账本（见 §4「结算是一笔事务」）。因此当队列里还有在途结算时，账本描述的是即将被替换的状态：此刻作答，模型读到的是一个马上过期的额度，并据此规划下一拍。`Scope#negotiate` 与 `Scope#askPreviousScope` 在有在途调用时返回拒收 `SCOPE_SNAPSHOT_BUSY`（`details.inFlight` 为在途笔数），**不**用半本账回答；调用方稍后再问。这样两个成员仍是同步的，拒绝是值，状态不变。

### 3.3 一回合一次结算（§6.2、§6.2）

一回合最多扣 1，记账路径只有一条：**先到的真注解**（`resetScope` | `keepLast` | `removeLast`）决定本拍。

- 真注解之后同回合的注解（含 `meToo`）：`outcome: "confirmed"`，不再记账。
- `meToo` 先到：`outcome: "staged"`，不记账、不撤销、不落盘；真注解到达后按真注解一次结算。
- 回合关闭（`closeTurn`）时仍只有暂存：拒收 `SCOPE_METOO_ONLY`，状态不变。
- 未初始化时先到的 `meToo`：拒收 `SCOPE_UNINITIALIZED`；额度为 0 时：拒收 `SCOPE_BUDGET_SPENT`。
- 未初始化时的真注解（`resetScope` 除外）：拒收 `SCOPE_UNINITIALIZED`。

### 3.4 出站投影（§6.2、§6.2）

输入是宿主本来会发的条目数组；输出是新数组，输入不变。条目形状由组合层决定，本包只认 `kind` 与 `scope`：

| kind | 字段 | 保留规则 |
|---|---|---|
| `message` | `role`（`operator` / `assistant` / 其他）、`text` | 边界之后原样保留；之前保留操作员消息与带正文的 assistant 消息 |
| `toolCall` / `toolResult` | `member`、`callId`、`scope?` | 之后保留；之前只保留**历次** `resetScope` 调用及其配对结果、边界那一拍与钉住成员（`assume`、`negotiate`、`askPreviousScope`） |
| `thinking` | — | 之后保留；之前移除 |
| `provider` | — | 已观测 provider 原文，无论位置都保留 |
| 其他 | — | 不抛异常：边界之后保留，之前丢弃 |

- **边界**：`resetCallId`（最新结算的 reset）是权威事实——命中即边界；**给了它却在请求里找不到，就是没有边界**（整份副本都属于当前 Scope），不退回注解。只有调用方完全没有结算事实（例如尚未接上 `settle` 的冷启动）才退回「请求里最新一个带 `resetScope` 注解的调用」，结果条目上的注解会被归到它的配对调用。边界那一拍与其配对结果原样保留；边界**之前**历次 `resetScope` 调用及其配对结果也保留（§6.2 第 2 项）——模型要能看见自己走过的每一次闭环，不只是最新一次。
- 配对是位置性的：`toolCall` 后紧跟的 `toolResult` 即其配对结果；两者保留结论不一致时返回 `SCOPE_FIELD_INVALID` 拒收（宿主钩子不能因为一份坏条目丢掉整次请求）。
- 没有边界时全部按"之后"处理。
- **钉住名单可加，不可减。** `project(items, { pinned })` 的默认值是 `DEFAULT_PINNED_MEMBERS`（`assume`、`negotiate`、`askPreviousScope`）；宿主传入自己的名单时**整体替换**默认值，所以必须自己并上默认值再加自己的名字。两个适配器都加了同样的两项：空字符串 `""`（Pair Hint 的合成对，§6.1，工具名为空）与 `SURFACE_MEMBERS["js-review"]`（评估面，§2.3）。不加的后果是 `resetScope` 把这对或评估面的调用从副本里裁掉，下一次请求的呈现位置随之漂移——模型没有做错任何事，却要承担 `PREFIX_DRIFT`。名单按 `member` 精确匹配：`js-review` 这个工具名本身不是成员名，能钉住的是它可调用的成员（`cat`、`stat` 等）。
- **抹除由记录驱动，不由条目的注解驱动。** `erase` 列表是事实（只有 `removeLast` 结算成功才写），而被投影的条目并不携带 `scope` 注解 —— 三个适配器构造条目时都只给 `kind`/`member`/`callId`/`source`。因此「哪一次调用是抹除调用」只看 `callId` 是否在 `erase` 里，成员名与注解都不参与判断；旧实现要求条目自己也带注解，于是这条分支在所有真实路径上都是死代码，教训从未写进副本。
- 被 `removeLast` 抹去的一拍：抹除调用是 `removeLast` 那次调用（记录里的 `callId`），被抹的是**它紧邻的前一拍**（`thinking`、assistant 正文，或一个工具调用及其配对结果），不跨边界、不跨 `resetScope` 调用。抹除结果收成一条 assistant 消息：正文 = `replacement` + 一行空转计数。无 `callId` 的抹除不猜位置、不生效，也不落盘。

### 3.5 冷启（§6.2）

持久事实里已有的 `resetScope` 只证明旧事件存在，不证明当前预算。

- `Scope.coldStart` 读记录：`reset` 只进历史；只有 `budget` 记录能让本进程确证预算。
- 预算未知时 `notice()` 返回 `{ kind: "scopeColdStart", reason: "SCOPE_UNINITIALIZED", previousResets }`，组合层把这条提示放进每次请求，迫使模型 `resetScope`。
- 冷启同时从 `remove` 记录重建被抹拍的清单，因此重启前后的请求副本一致。
- **空转计数也随记录回来。** 每条 `remove` 记录都带着抹除当时的 `spin`，冷启把它交给账本（`ScopeBook#restoreSpin`），所以重启后 `spinCount` 与重启前相同，`removeLast` 的替换正文仍写同一个 `Spin count: N`。
- **余量取「最新一条预算事实」。** `budget` 记录只有 `resetScope` 结算时才写，因此单靠它会漏掉之后的 `keepLast` 扣减。`ColdState#remaining` 在「本 Scope 的 `budget` 记录」与「本 Scope 最新的 `keep` 记录里的 `remaining`」之间取**更晚出现的那条**（记录流里的先后，不是数值大小）。`keep` 的 spend 只改变余量，**不**确证预算：`budgetKnown` 仍只由 `budget` 记录或本进程里结算过的 reset 决定，预算未确证时照旧提示 `resetScope`、照旧拒收 `keep`/`remove`，绝不用一个内存默认值把会话锁死。

### 3.6 持久记录

每行一条 JSON：`{ v: 1, type, ... }`，类型 `reset` / `budget` / `keep` / `remove` / `meToo`。版本或字段不符即抛 `ScopeArgumentError`——丢一条记录会静默改变预算或副本，所以不静默跳过。

- `reset` 与 `budget` 在 `resetScope` 结算时同批写入；`reset.callId` 是开边界的物理调用（冷启靠它还原边界），`budget` 是本进程确证过的余量，`readColdState` 取最后一条。
- `remove` 必带 `callId`、`replacement`、`spin`（正整数）。
- `meToo` 只在真注解已结算后的确认时写入；暂存阶段不落盘。

## 4 接口

```js
import { ScopeHooks, Scope, ScopeTable } from "./scope/index.mjs";

const hooks = new ScopeHooks();
hooks.open({ sessionId, store });                        // 或 hooks.coldStart({ sessionId, records, store })

hooks.admit({ sessionId, member, annotation });           // 执行前：准入
await hooks.settle({ sessionId, member, annotation, turnId, callId, observed }); // 执行后：结算 + 落盘
hooks.closeTurn({ sessionId });                           // 回合结束：全 meToo 拒收
hooks.project({ sessionId, items, resetCallId? });        // 请求级上下文钩子
hooks.ask("negotiate", sessionId);                        // 同步快照
```

- `ask(member, sessionId)` → 核心快照（冻结对象）或拒收。`member` 只接受两个豁免成员名；其他名字返回 `SCOPE_MEMBER_UNKNOWN`（§6.2 只豁免这两个，别的名字是查询字段写错，不是新成员）。会话没有账本时返回 `SCOPE_UNINITIALIZED`；有在途结算时返回 `SCOPE_SNAPSHOT_BUSY`。**永远不是 Promise**：§6.2 要求这两个成员同步返回。

- `admit` → `{ ok: true, exempt, annotation, mustReset, notice }` 或 `ScopeRefusal`
- `settle` → `{ ok: true, outcome, kind, remaining, turnId, persisted, retention, notice }` 或 `ScopeRefusal`
- `closeTurn` → `null` 或 `ScopeRefusal`
- `project` → `{ ok: true, messages, notice }` 或 `ScopeRefusal`

`project` 省略 `resetCallId` 时用本进程最新结算的 reset；再没有才退回注解。`ScopeTable.create` / `coldStart` 对同一 `sessionId` 第二次调用返回 `SCOPE_OWNER_TAKEN`：一个进程里同一会话只能有一个 Scope，宿主必须把同一个对象交给所有钩子。

**`turnId` 必填。** 记账按模型回合结算（§6.2），`callId` 是物理调用身份，不是回合身份：同一回合的多个工具调用共享一个 `turnId`，第一次结算、其余确认。缺少 `turnId` 时 `settle` 拒收 `SCOPE_TURN_MISSING`，不记账、不落盘；**不得用 `callId` 或其他猜测补位**——那会让同一回合扣两次额度。宿主没有回合 id 时，适配器必须自己提供一个（如 assistant 消息 id），而不是交给本包去猜。

**结算是一笔事务。** `settle` 先在 `ScopeBook.clone()` 上结算并把记录写进 `store`，成功后才 `adopt` 回活的 book。任何一步失败都不留下半笔：内存与 `.jsonl` 记录永远描述同一组事实。`ScopeBook` 因此不对外暴露可变句柄；`Retention.clone/adopt`、`TurnLedger.clone`、`ScopeBook.clone/adopt/erases/noteErase` 是这套事务的全部机制。

**结算串行。** 每个 `Scope` 有一条先进先出的队列，`admit` → `clone` → `append` → `adopt` 整段在队列里执行，同一会话不会有两笔结算交错。没有这条队列，两次并发调用会各自从同一份 book 克隆、各扣一次额度，其中一次的记账与暂存的 `meToo` 一起丢失。队列只串行，不合并：不同 `turnId` 的调用依次结算，各扣各的。

**`closeTurn` 与在途结算。** 回合关闭的判定必须在一份静止的 book 上做：在途结算还没提交，此刻判"全 `meToo`"会误拒一个马上要结算的回合。因此 `closeTurn` 在有在途调用时排到队尾，返回 Promise；队列空时立即以同一判定兑现。宿主可以 `await`，也可以 `.then` 后通过自己的通道上报拒收。

**调用点必须解析这个 Promise。** 直接把 Promise 本身当返回值判空（`refusal === null`、`refusal.ok`）永远得到"没有拒收"：拒收不是丢了，而是被当成"无事发生"。这是「契约不一致」与「实际丢拒收」的唯一分界线，判据就是调用点与返回值之间有没有 `await`（或 `.then`）。

## 5 已知边界

- 宿主在工具执行前已把调用写进历史时，`admit` 的拒收不撤销那条历史；本包只保证自己的状态不变。宿主历史不动（§6.2）。
- **落盘失败即整笔回滚。** `settle` 在 `book.clone()` 上结算，`store.append` 成功后才 `book.adopt(clone)`。append 失败时：回合、额度、reset 边界、被抹拍清单一律保持原样，内存与持久事实不会分叉；回执为 `{ ok: false, code: "SCOPE_RECORD_FAILED", persisted: false, details: { sessionId, reason, turnId, retryable: true } }`。同一个 `turnId` + `callId` 重试会重新结算一次，不会重复扣分。适配器应把这条回执报给模型并重试整次调用。
- 事件顺序假定为"注解先到、执行后到"：`admit` 只校验，`settle` 才结算落盘。若宿主只能事后给注解，适配器必须在执行前调用 `admit`，否则 `settle` 会按已执行的事实结算。
- `removeLast` 需要被抹那一拍的 `callId`。缺失时该拍不登记、不落盘：没有名字的拍无法在之后的投影里被准确抹去。
- 本包不做语言判断、不审查内容、不做压缩；Pair Hint 与 retention 互不影响（§6.2）。
- **快照查询不是等待点。** 在途结算时 `negotiate`/`askPreviousScope` 返回 `SCOPE_SNAPSHOT_BUSY`，而不是排队等结算完成：排队会把同步查询变成一个隐式的 `await`，与 §6.2「严禁做成异步」冲突，也会让一次查询拖住程序。宿主把这条拒收交给模型，模型重问即可。
- **`ask` 的成员名不做模糊匹配。** `js-manager`、`cat`、空串都是 `SCOPE_MEMBER_UNKNOWN`：豁免名单只有两个名字，多认一个就多一条不花额度的通道。
