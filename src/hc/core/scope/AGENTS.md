# `src/hc/core/scope` 包级规格说明书

> 本文件展开根 `AGENTS.md` §6.2 的 Scope 部分。根规格是唯一语义来源；两者冲突时以根规格为准，并立即修正本文件与代码。

## 0 这个包做什么

一个长程委托跨越多轮工具调用。Scope 只决定两件事：一次调用能否准入，以及下一次出站请求副本里哪些旧拍子还能留下。它不碰宿主的持久历史，不做语言判断，不审查内容，也不替宿主压缩对话。

包内状态不可变。唯一的可变入口是 `hc.core.scope.scope/Scope`：它持有一个账本 atom 和一把结算锁。记录存储由调用方注入。

## 1 依赖边界

- 只依赖 Clojure 与包内命名空间。不依赖 `hc.core` 其他包，也不依赖 `hc.compose`、`hc.server` 或 `hc.client`。
- 不做 I/O。`store` 是一个函数：接收一批记录，失败时抛出异常。store 必须原子追加：失败时不得留下半批记录。`memory-store` 只用于测试与预演。`cold-start` 必须显式提供 store；冷启不能悄悄退化成内存存储。
- 模型输入不抛异常。拒收是值：`{:ok false :code ... :message ... :details ...}`。只有接线错误（缺少 `:session-id`、`store` 不是函数、记录损坏）才抛 `ex-info`。
- `:scope` 注解的 `:kind` 只接受四个关键字：`:reset-scope`、`:keep-last`、`:remove-last`、`:me-too`。不接受别名。
- 成员名按字符串比较。关键字与字符串都可用；`member-name` 统一归一化。

## 2 模块

| 文件 | 职责 |
|---|---|
| `errors.clj` | 拒收码、拒收值、参数错误、四种 kind、豁免与钉住成员 |
| `annotation.clj` | `parse-annotation`：原始 `:scope` 映射 → 规范化记录或拒收；逐 kind 校验必填字段与多余字段 |
| `ledger.clj` | `Retention` 与回合账本：`settle-turn`、`close-turn`、`restore-book`；纯函数，返回新 book 与记录 |
| `admission.clj` | 工具执行前的准入规则：缺席、未初始化、额度耗尽、豁免 |
| `records.clj` | 持久记录形状与 `read-cold-state`；冷启只恢复历史、空转计数与抹除清单 |
| `projection.clj` | `project`：按最新已结算 reset 边界构造出站副本；`removeLast` 改写上一拍 |
| `scope.clj` | 有状态 `Scope`、`cold-start`、会话表；先落盘再替换账本 |
| `runtime.clj` | 实际运行时形状适配：注解提取、Owner 会话 ↔ Scope items、模型消息重建、纯 book 准入/结算/投影 |
| `../scope.clj` | 包入口，重新导出公共函数与常量 |

## 3 语义

### 3.1 注解

| kind | 必填 | 不接受 | 效果 |
|---|---|---|---|
| `:reset-scope` | `:new-scope-goal` 非空白字符串；`:new-scope-retention-budget` 正整数（无上限） | 其他字段 | 整体替换目标与额度，开启新边界 |
| `:keep-last` | 无 | 其他字段 | 额度减 1 |
| `:remove-last` | `:replacement` 非空白字符串 | 其他字段 | 不扣额度；空转计数加 1；登记抹除上一拍 |
| `:me-too` | 无 | 其他字段 | 同回合附议；先到则暂存 |

缺失、非映射、`kind` 非法、字段非法、多余字段都返回拒收。多余字段先于必填字段校验。

### 3.2 准入与结算

准入发生在工具执行前，状态不变：

1. 成员名为空：`:SCOPE_ANNOTATION_MISSING`。
2. 豁免成员：直接通过，不解析注解。
3. 注解缺失：`:SCOPE_ANNOTATION_MISSING`。
4. 注解非法：`:SCOPE_KIND_INVALID`、`:SCOPE_FIELD_INVALID` 或 `:SCOPE_FIELD_UNEXPECTED`。
5. 预算未建立且 kind 不是 reset：`:SCOPE_UNINITIALIZED`。
6. 预算为 0 且 kind 不是 reset：`:SCOPE_BUDGET_SPENT`。

准入拒收带 `:phase :admission`；结算阶段的拒收带 `:phase :settle`。`parse-annotation` 的纯解析结果不带 phase。

结算发生在工具生效后：

- `turn-id` 缺失：`:SCOPE_TURN_MISSING`，不记账。
- 记录写入失败：`:SCOPE_RECORD_FAILED`，账本保持原样，可重试。
- 一个模型回合最多结算一次。第一个真注解结算；同回合后续真注解只返回 `:confirmed`。
- 先到的 `:me-too` 返回 `:staged`，不记账。真注解到达后按真注解结算一次。
- 回合关闭时只有 `:me-too`：默认按 `:keep-last` 结算，扣 1 点，记录带 `:defaulted? true`。
- 回合关闭时没有任何注解（也没有暂存 `:me-too`）：返回 `:outcome :closed`，但不把回合标记为已结算。这样模型 `:completed` 先到、工具终态后到时，同回合的迟到工具仍可结算。宿主不得在子工具仍存活时把“关闭”当作最终屏障。
- 带 `turn-id` 关闭时，若当前账本回合不是该 id，返回 `:ignored :turn-mismatch`，不关闭、不扣额度。迟到的旧模型终态不得默认结算新回合的 `:me-too`。
- `:remove-last` 不扣额度，但空转计数加 1。没有 `call-id` 时仍结算并计数，只是无法登记抹除。
- 结算不重跑预算准入：按成员判豁免、解析注解，然后交给账本。`turn-settled?` 为真时先返回 `:confirmed`，不会因为余额已被同回合前一调用花掉而再拒 `:SCOPE_BUDGET_SPENT`。非豁免调用若在结算时缺少或带非法注解，仍返回对应准入码但带 `:phase :settle`；这是宿主漏掉派发前准入的接线错误。

### 3.3 豁免与钉住

- 豁免成员：`negotiate`、`askPreviousScope`。它们不扣额度，额度为 0 或预算未建立时也可调用。`settle!` 对它们返回 `:outcome :exempt`，不写记录。
- 钉住成员：`assume`、`negotiate`、`askPreviousScope`。它们的调用与配对结果跨 reset 保留。
- `assume` 只豁免 history pruning，不豁免 retention。`assume` 搭 `:keep-last` 仍扣 1 点。
- 调用方可以追加钉住成员，但不能移除默认三项。
- 豁免成员的注解不解析、不结算。`askPreviousScope` 与 `:reset-scope` 同调用不会开新 Scope。
- `askPreviousScope` 的 receipt、持久化与答案 steer 属于 relay 组合层。本包只把它识别为豁免与钉住成员，并暴露 `previous-scopes`（全量 reset 历史，含最新）与 `preceding-scope`（紧邻前一个 reset，供 askPreviousScope 选择目标）。

### 3.4 出站投影

`project` 接收宿主本来要发的条目向量，返回新向量。输入不变。

- 边界是最新一次已结算 `:reset-scope` 的 `call-id`。`settled? true` 时该事实权威：`nil` 或请求里找不到该调用都表示“没有边界”，整份副本都算当前 Scope。
- 按 call-id 找边界时优先选择同时带 reset 成员名或 reset 注解的 tool-call；只有宿主没有标记该调用时，才退回“最新一个同 call-id 的 tool-call”。因此后续普通调用误复用 reset call-id 不会把边界向前移动，也不会吞掉真正位于边界之后的内容。
- 没有结算事实时（纯投影调用），才退回“请求里最新一个带 reset 注解的调用”。
- 边界之后：原样保留。
- 边界之前保留：操作员消息、assistant 正文、每次 reset 调用及配对结果、钉住成员的调用及配对结果、已观测 provider 原文。
- 边界之前删除：其他工具调用、工具结果、thinking、未知条目。
- 工具调用与结果按 `:call-id` 配对；同一 call-id 的调用和全部结果共享同一保留决定。没有 call-id 时才退回紧邻配对。这样多个结果、交错的调用/结果不会因为位置相邻而误删。有效转录中 call-id 唯一；若重复，后一个 tool-call 开启新 occurrence，结果只配最近的前一个同 id call。
- 仍按紧邻关系检查调用/结果声明冲突：相邻的 tool-call 与 tool-result 若 call-id 不兼容且保留决定不同，返回 `:SCOPE_FIELD_INVALID`。
- `removeLast` 记录把“上一个已完成的工具回合”替换成一条 assistant 消息：正文是 `replacement` 加一行 `Spin count: N`，并带 `:erased-call-id`。回合定义：eraser 之前最近的 tool-result（排除 eraser 自己 `:source-index` 回合），按 call-id 取其 call 与全部结果，并把该 call 的 `:source-index` 组内、位于 call 之前的 thinking 一并纳入；用 `:source-index` 排除 eraser 自己回合的正文、thinking 与兄弟调用；没有已完成工具回合时才退回最近的 assistant 正文。命中 pinned 或 reset 项时放弃本次改写。不跨 reset 边界。
- `:provider` 项在 `items->messages` 中重建为 `{:role :assistant :text ... :meta {:provider true}}`；standalone `:thinking` 项重建为 `{:role :assistant :text "" :thinking ...}`。`:observed` 只是结算 receipt 的诊断回显，不是 provider 原文，也不持久化。

### 3.5 冷启

- `cold-start` 读取记录，恢复 reset 历史、最新 reset 的 `call-id`、空转计数与抹除清单。
- 预算一律未建立。持久 reset 只证明旧事件，不证明本进程的预算。
- Owner 持久状态恢复必须调用 `runtime/cold-start-state`（`state/restore` 已接线）：它保留 `:records` 与 book 历史，但清空 `:retention` 与 `:turn`。直接把持久 book 原样装回会绕过冷启拒收。
- `make-scope` 的 `:book` 选项只恢复历史：`:retention` 清空、`:turn` 清空，因此不能靠它把持久预算装回。
- 预算未建立时，除 reset 外的非豁免调用都拒为 `:SCOPE_UNINITIALIZED`；`notice` 返回 `:scope-cold-start` 提示。
- 本进程第一次 reset 结算后，预算建立，提示消失。
- 旧的 `:budget` 与 `:me-too` 记录被接受为无操作；它们不能建立预算，也不记账。

### 3.6 持久记录

记录是 EDN 映射，带 `:v 1` 与 `:type`：

| type | 字段 |
|---|---|
| `:reset` | `:turn-id`、`:call-id`、`:goal`、`:budget`、`:remaining` |
| `:keep` | `:turn-id`、`:remaining`、可选 `:defaulted?` |
| `:remove` | `:turn-id`、`:call-id`、`:replacement`、`:spin` |

损坏的 reset、keep、remove 记录抛参数错误，不静默跳过。未知 type 同样抛错。

`:reset` 与 `:keep` 的 `:remaining` 必填非负整数；缺失或负数视为损坏记录。

## 4 接口

```clojure
(require '[hc.core.scope :as scope])

(def store (scope/memory-store))
(def sc (scope/make-scope {:session-id "s1" :store store}))

(scope/admit! sc {:member "cat"
                  :annotation {:kind :keep-last}})
;; => :SCOPE_UNINITIALIZED

(scope/settle! sc {:member "cat"
                   :annotation {:kind :reset-scope
                                :new-scope-goal "finish"
                                :new-scope-retention-budget 3}
                   :turn-id 1
                   :call-id "r1"})

(scope/settle! sc {:member "cat"
                   :annotation {:kind :keep-last}
                   :turn-id 2
                   :call-id "k1"})

(scope/close-turn! sc)
(scope/project-request sc items)
(scope/snapshot sc)
```

- `settle!` 返回 receipt 或拒收。receipt 带 `:outcome`、`:kind`、`:remaining`、`:records`、`:persisted`、`:retention`、`:notice`；若上一回合有补记，还带 `:prior-records` 与 `:prior-persisted`。
- `:persisted` 为 true 仅表示本次 receipt 的 `:records`（当前回合产生的记录）已写入；`:staged`、`:confirmed`、`:exempt` 恒为 false。上一回合补记的记录在 `:prior-records`，其写入状态用 `:prior-persisted`。
- `close-turn!` 返回 `:outcome :closed` 或 `:settled`（全 me-too 默认）；可带 `turn-id` 忽略迟到终态。
- `project-request` 返回向量或拒收。
- `snapshot` 给 `negotiate` 读取：目标、预算、余量、空转、reset 次数。
- `previous-scopes` 给 `askPreviousScope` 读取全量 reset 历史；`preceding-scope` 返回紧邻前一个。
- `table`、`create-scope!`、`cold-start-scope!`、`require-scope`、`drop-scope!` 提供每进程会话表。同一会话第二次创建返回 `:SCOPE_OWNER_TAKEN`；`require-scope` 对不存在的会话返回 `:SCOPE_NOT_FOUND`。

### 4.1 结算事务

准入在工具派发前完成；`settle!` 与 `close-turn!` 在同一把锁内执行，结算不再重跑预算准入。顺序固定：

1. 判豁免；非豁免解析注解；在活账本上计算下一份 book 与记录（`turn-settled?` 为真时直接 `:confirmed`）；
2. 调用 `store` 写入记录（上一回合补记在前，本次记录在后）；
3. 写入成功后才替换活账本 atom。

写入失败时内存与记录流不分叉。重试同一 `turn-id` 不会重复扣分，因为活账本没有前进。

### 4.2 先前回合的默认结算

如果宿主没有关闭上一回合就直接开始新回合，而上一回合只有 `:me-too`，账本会在新回合结算前补记一次默认 keep-last。若新调用随后被拒，补记记录仍会先落盘，返回的拒收带 `:prior-records` 与 `:prior-persisted true`。若新调用成功，补记记录放 `:prior-records`、本次记录放 `:records`；`ledger` 结果同时带 `:current-records` 供有状态包装计算 `:persisted`，两者在一次 store 批次中写入。这样额度不会被重复使用。

### 4.3 运行时适配（`hc.core.scope.runtime`）

实际 Owner 会话不是 Scope item 形状：用户消息用 `:role :user`，工具结果用 `:role :tool`，工具调用可能单独存为 `:role :tool-call`，模型消息用 `:text` 或 `:content`。`runtime.clj` 做纯适配：

| 函数 | 输入 | 输出 |
|---|---|---|
| `scope-annotation` | tool event/call/request | 原始 `:scope` 映射或 nil；接受 `:payload`、`:arguments`、`:request` |
| `tool-member` | 同上 | `:tool-name` / `:name` / `:member` / payload/request 名 |
| `tool-call-id` | 同上 | `:call-id` / `:tool-call-id` / `:id` / payload/request id |
| `message->items` | 单条运行时消息 + index | Scope item 向量，带 `:source-index` |
| `conversation->items` | Owner `:conversation` | Scope item 向量 |
| `items->messages` | 投影后的 items | 模型消息向量：`:user` / `:assistant` / `:system` / `:tool` |
| `project-conversation` | book + conversation | 投影后的模型消息或拒收 |
| `admission-check` | book + admission + tool | 准入结果 |
| `settle-tool` | book + admission + tool + turn-id + call-id | `{:book :result}`；结算不重跑预算准入 |
| `close-turn` | book | `{:book :result}` |
| `cold-start-book` | records | budget 未建立的 book |
| `cold-start-state` | Owner `{:book :records}` | 保留 records/book 历史、清空预算与回合的 Owner scope state |
| `latest-boundary` | Owner `{:book :records}` | 最新已结算 reset/remove 的原始事实或 nil |
| `pending-boundary` | Owner `{:book :records}` + 已消费事实 | 有变化时返回原始事实，否则 nil |
| `scope-admit!` / `scope-settle!` | 有状态 Scope + tool | 对应有状态包装 |

`items->messages` 把同一 `:source-index` 的 item 折回一条消息：文本/思考 + `:tool-calls` 成为一条 assistant 消息；工具结果成为 `:role :tool` 且带 `:tool-call-id`/`:name`/布尔 `:error?`。工具调用 item 的 `:arguments` 会保留并进入重建的 `:tool-calls`。`:provider` 项重建为 `{:role :assistant :text ... :meta {:provider true}}`；standalone `:thinking` 项重建为 `{:role :assistant :text "" :thinking ...}`。`removeLast` 生成的替换消息没有 source index，按 assistant 消息原地输出。这样模型适配器只看到合法角色与字段。

Owner 对 admission 拒收的调用会追加一对仅用于会话的 synthetic `:tool-call` + `:tool` 结果（显式带拒收文本与原始 arguments，但不创建 call、不结算 Scope）；严格 provider 不会收到孤立的 tool 结果。

### 4.4 Pair Hint 边界接线

Pair Hint 需要知道 Scope 是否刚刚裁剪了消息。不要在每次请求都发边界；只在“最新已结算 reset/remove 事实”与“Pair Hint 已消费事实”不同时发一次。

- `latest-boundary` 扫描 Owner `[:scope :records]`，取最后一条 `:reset` 或 `:remove`；`:keep` 不移动边界。记录窗口没有边界时退回 `[:scope :book :last-reset]`，再退回最新 erase。
- 返回原始事实：reset 为 `{:kind :reset-scope :call-id id :turn-id id?}`，remove 为 `{:kind :remove-last :call-id id}`。reset 记录带 `:turn-id` 时一并返回，供 Pair Hint 在 call-id 为 nil 时兜底；remove 的 call-id 必存，erase 事实不加入 `:turn-id` 元数据。
- `pending-boundary` 接收 `seen`（上次已消费的原始事实）；不同才返回最新事实。
- App 的 `:boundary` 函数应调用 `(hc.core.pair-hint/scope-boundary (runtime/pending-boundary scope-state seen))`，把原始事实翻译成 `:scope-reset` / `:scope-remove`。
- `seen` 必须是独立游标，不要拿 Pair Hint 的 `:chain-boundary` 比较：relay 或 compaction 边界会让旧 Scope 事实看起来像新的。新会话 `seen` 为 nil 时发出当前边界一次。
- 如果同一次请求窗口里先 reset 后 remove，最新记录 remove 胜出；一次新 epoch 已足够，因为出站副本已经同时应用了两者。

Owner 接线必须遵守：

1. 工具调用创建前用 `admission-check`；拒收时不要创建/执行调用，把拒收作为工具结果交给模型。
2. 工具终态用 `settle-tool`；同一模型回合共享 `turn-id`（通常模型 call-id）。`failed` 可以结算；`cancelled`/`outcome-unknown` 可以不结算，宿主必须明确选择。
3. 模型请求快照用 `project-conversation`，不要直接把 Owner 会话消息交给模型适配器。
4. 模型 `:completed`/`:failed`/`:cancelled` 不是 Scope 回合的最终屏障：只要该模型回合派生的工具调用仍存活，就不要调用 `close-turn`；等最后一个子工具终态后再关闭。否则 `:me-too` 可能被过早默认结算，后续真注解只能 `:confirmed`。
5. 冷启用 `cold-start-book`；预算未建立时只允许 reset。
6. Owner `state/restore` 调用 `cold-start-state`；持久 book 不能原样装回。
7. 新模型回合的领取守卫必须把 `:ready` 的旧工具调用也算作在途工作（忽略候选模型调用自身），否则旧工具会在新回合开始后才结算，`settle-turn` 会错误地终结新回合。

## 5 已知边界

- 本包不决定“第二次 reset 前必须先 askPreviousScope”。该顺序由提示词与 relay 组合层执行；本包只暴露 `previous-scopes`（全量历史）与 `preceding-scope`（紧邻前一个）。
- 本包不实现 `askPreviousScope` 的 receipt 重放与答案 steer。
- 本包不读宿主历史，不修改宿主消息，不做内容压缩。
- 投影按 `:call-id` 配对工具调用与结果，支持多结果与非相邻配对；只有无 call-id 时才退回紧邻配对。宿主插入额外条目可能影响无 id 配对。
- `snapshot` 与 `previous-scopes` 是给 negotiate / askPreviousScope 的读取 seam；本包不实现这两个工具的接线，当前也没有生产消费者。
- `:call-id` 是物理调用身份，不是回合身份。同一回合的多个调用共享 `turn-id`。

## 6 测试

包测试位于 `test/hc/core/scope_test.clj`，使用 `clojure.test` 与 `clojure.test.check`。在仓库根运行：

```bash
clojure -M:test -n hc.core.scope-test
```

也可以只跑本包的聚焦 runner：

```bash
clojure -M:dev -m hc.core.scope.run-tests
```

测试覆盖：注解全函数性与字段精确性、每回合一次结算、额度单调、全 me-too 默认、同回合第二调用 `:confirmed`、`:persisted`/`:prior-records` 拆分、投影边界与抹除（当前回合正文/兄弟调用/thinking/pinned/reset 反例）、call-id 多结果配对、准入全函数性与 `:phase`、记录失败回滚、记录读写与 `:remaining` 反例、冷启未初始化、`make-scope` 清预算、会话表所有权与 `:SCOPE_NOT_FOUND`、`preceding-scope`、provider 原文与 standalone thinking 重建、运行时形状适配（Owner `:tool-call`/`:tool`/`:content`、模型 `:tool-calls` 重建）。
