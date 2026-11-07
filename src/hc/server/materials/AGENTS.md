# `src/hc/server/materials` 包级规格说明书

> 权威顺序：根 `AGENTS.md` §5.3、§5.4、§5.5、§7.1、§7.4 旅程 5.6、6.1（记忆、世界书、搜索） > 本文件 > 代码注释。
> 本包实现长期记忆、世界书触发规则、指令卡与联网搜索来源引用。根规格冲突时以根规格为准，并立即修正本文件与代码。

## 0 这个包做什么

`hc.server.materials` 是材料与记忆领域的纯状态机与显式持久化边界：

- 长期记忆：四类记录、候选审阅、范围、替换历史、归档、永久删除墓碑、临时会话隔离、预算选入。
- 世界书：书、条目、绑定、关键词/始终/受控表达式触发、持续/冷却/延迟计数、预算不足的“命中但未提供”。
- 指令卡：库、占位符、仅本轮/本会话选择、互斥输出格式提示。
- 搜索来源：URL 去重、发布日期与抓取时间分离、引用编号、未引用结果分离、纯文本来源附录。
- 持久化：`Store` 协议与 CAS 提交；`durable_store.clj` 把同一协议落到 `hc.server.storage`，`session.clj` 提供应用装配与每用户轮次上下文。
- 纯领域层不选数据库、不打开文件、不读时钟、不发网络请求。

本包不做：真实搜索客户端、向量检索、语义相似度判断、UI 渲染、备份加密。纯领域状态机不调用模型；自动候选生成由 `memory_suggest.clj` 通过低层 `ModelPort` 完成一次显式请求，`session.clj` 负责装配。调用方必须提供消息、来源与端口；本包不会伪造它们。

## 1 依赖与边界

- 纯领域命名空间只依赖 Clojure 标准库与包内命名空间。
- `durable_store.clj` 额外依赖 `hc.server.storage`，只在 `:server` classpath 下编译；`hc.server.materials` 门面不 require 它。
- 纯领域层不做 I/O。所有时间、ID、来源片段与搜索结果均由调用方显式传入。`memory_suggest.clj` 是应用适配层：它读 `ModelPort`，但只做一次 `start-request!`，不启用 AiServices 自动循环，也不把模型散文当事实。
- 期望拒绝是值：`{:ok? false :state state :error {...} :effects []}`；拒绝不改变状态。
- 状态是不可变 EDN。每个成功转换把 `:revision` 加一；`store/commit!` 用预期修订做 CAS。
- `memory-store` 只用于测试与预演，不是持久适配器。`load-state` 不会在缺少状态时静默创建空库；创建必须走 `initialize!`。

## 2 状态

```clojure
{:v 1
 :revision 7
 :seq 12
 :settings {:memory-read? false
            :memory-suggest? false}
 :memories {"mem-1" {...}}
 :worldbook {:books {"wb-1" {...}}
             :entries {"entry-1" {...}}
             :sessions {"session-1"
                        {:turn 3
                         :runtime {"entry-1" {:last-trigger-turn 3
                                              :sticky-until 5
                                              :cooldown-until 6}}
                         :last-turn {:id "turn-3"
                                     :fingerprint "..."
                                     :result {...}}}}}
 :instruction-cards {"card-1" {...}}
 :instruction-sessions {"session-1" {:card-ids ["card-1"]}}}
```

- `:revision`：CAS 修订，成功转换恰好加一。
- `:seq`：稳定的创建顺序；不依赖墙钟，因此测试和恢复可复现。
- `:settings`：读取与自动建议是两个独立开关，默认都为 `false`。
- `:memories`：所有状态（候选、有效、归档、拒绝、删除墓碑）都在同一映射中；注入只取 `:active`。
- `:worldbook/:sessions`：按会话保存轮次数、每个条目的触发计数与最后一轮幂等记录。不同会话绝不共享持续/冷却/延迟计数。
- `:worldbook/:sessions/<id>/:turn`：只由该会话的 `run-turn` 增加；工具往返、流式片段、发送失败不得调用它。
- `:worldbook/:sessions/<id>/:runtime`：每个条目的触发计数；定义与计数分开，编辑定义不清空计数。
- `:instruction-cards` 是卡片库；`:instruction-sessions` 是每会话持久选择。
- 搜索引用不属于此状态：它属于使用来源的消息/轮次，见 `search.clj`。

## 3 持久化接口

```clojure
(require '[hc.server.materials.store :as store])

(defprotocol Store
  (read-snapshot [store])
  (commit-snapshot! [store expected-revision next-state]))
```

`read-snapshot` 返回：

```clojure
{:found? false}
;; 或
{:found? true :revision 7 :state state}
```

`commit-snapshot!` 返回：

```clojure
{:ok? true :revision 8}
;; 或
{:ok? false :reason :revision-conflict :current-revision 7}
```

契约：

1. 成功提交必须持久且原子：读者只能看到旧修订或新修订。
2. `expected-revision` 为 `nil` 仅表示创建；冲突时存储状态不得改变。
3. 存储打不开时必须抛出或返回显式失败；不得静默新建同名空库。
4. `load-state` 会校验全部不变量；损坏状态返回 `:MATERIALS_STATE_CORRUPT`，并附带全部违规项。
5. `commit!` 在触达存储前校验候选状态、修订形状与“恰好加一”规则。
6. `transact!` 加载、执行一个纯转换并提交；冲突返回 `:MATERIALS_REVISION_CONFLICT`，不自动重试。调用方重新读取并重新决策，因为转换可能已经选择了 ID 或来源片段。

调用方负责串行化（Owner 邮箱）。CAS 是防止陈旧写入的围栏，不是跨进程锁。

### 3.1 持久适配器

`hc.server.materials.durable-store/durable-store` 接收已经打开的 `hc.server.storage` 句柄，在 `"materials"` 分区持久化：

| kind | id | 内容 |
|---|---|---|
| `:materials-root` | `"root"` | `:v`、`:revision`、`:seq`、`:settings` |
| `:materials-memory` | memory id | 完整记忆记录 |
| `:materials-worldbook-book` | book id | 书 |
| `:materials-worldbook-entry` | entry id | 条目 |
| `:materials-worldbook-session` | session id | 轮次与运行时计数 |
| `:materials-instruction-card` | card id | 指令卡 |
| `:materials-instruction-session` | session id | 会话卡片选择 |

一次提交把根文档与全部变更/删除实体放进同一个 `commit-migration!`，所以 CAS 覆盖整个修订。读取时用一个 `read-session-view {:partition "materials"}` 重建；根缺失但实体存在按 `:MATERIALS_STORE_CORRUPT` 拒绝，不当作空库。存储句柄的生命周期属于应用；适配器不关闭它，也不在 Missionary 取消任务里提交。

## 4 长期记忆

### 4.1 记录与状态

| 字段 | 语义 |
|---|---|
| `:status` | `:candidate`、`:active`、`:archived`、`:rejected`、`:deleted` |
| `:category` | `:identity-background`、`:working-style`、`:expression-preference`、`:long-term-requirement` |
| `:scope` | `{:kind :global}`、`{:kind :assistant :assistant-id ...}`、`{:kind :project :project-id ...}` |
| `:epistemic` | `:fact`、`:preference`、`:requirement`、`:assumption` |
| `:body` | 去除首尾空白后的正文；最多 2,000 个 Unicode 码点 |
| `:versions` | 被替换版本的快照；替换不改写源聊天 |
| `:source` | 自动候选必填：`:session-id`、`:message-ids`、`:excerpt` |
| `:sensitive-kinds` | 自动扫描命中的敏感类别；只记录类别，不记录秘密值 |
| `:body-hash` | 去重与墓碑使用；不声称两段文字语义相同 |

### 4.2 审阅与生效

- `remember`：用户显式保存，直接 `:active`；不改变两个开关；临时会话拒绝写入。
- `propose`：自动候选，必须显式提供来源片段与 `:epistemic`；需要 `:memory-suggest? true`；默认拒绝疑似密码、令牌、私钥与通过 Luhn 检查的金融账号；相同内容曾被拒绝时返回 `:suppressed? true`，不重复建议；相同 `:id` 重试幂等。
- `approve`：候选或曾被拒绝的记录转为 `:active`；可同时修改正文、类别、范围、认识标签与“始终提供”；与现有有效记录同内容同范围时拒绝 `:MEMORY_DUPLICATE`。
- `reject`：候选转 `:rejected`，保留正文供审阅历史与后续抑制。
- `replace`：仅对有效记录；旧正文、类别、范围、标签与时间进入 `:versions`；不会因为文字相似自动合并。
- `archive`：立即退出未来注入；记录仍可在归档视图读取。
- `restore`：归档转有效；若会与现有有效记录重复则拒绝。
- `delete`：必须先归档并要求 `:confirm true`；清除正文、来源与版本，保留范围、类别、内容哈希与墓碑，防止旧备份复活。

### 4.2.1 自动候选生成

`:materials/memory/suggest`（`session/suggest-memories!`）从最近 N 轮消息生成
待审候选：

```clojure
{:messages [{:message-id ... :role ... :content ...} ...] ; 可选
 :source-range {:recent-rounds 10}        ; 或 {:message-ids [...]}
 :categories [...]
 :scope :global
 :model-binding {:role :main|:task|:utility} ; 可选
 :timeout-ms 60000}
```

`:messages` 缺省时从 `:session` 记录的 `:message-order` 与 `:messages` 读取：
跳过已删除、折叠与 nudge 消息，取每条消息当前版本；`:source-range` 再保留最近
N 个用户轮次（工具往返不计轮）。非空显式 `:messages` 覆盖会话读取；空向量
视为缺失，客户端没有消息投影时仍走服务端读取。
`:max-transcript-chars` 限制提示长度，回执用 `:dropped-message-count` 报告被
丢弃的旧消息数。

模型绑定按以下顺序解析：显式 `:model-binding` → 会话记录中的
`:model-binding`（`:connection-id`/`:model-entry-id`）→ 主模型。`:utility`
映射到 adapter 的 `:task` 角色。服务端口可以是 port、零参数 provider 或可
deref 的引用；应用装配传入动态 provider，使设置保存后重建的 port 立即生效。
没有可用 port 且没有可用绑定时返回
`:MEMORY_SUGGEST_MODEL_UNCONFIGURED`，提示“先配置主模型，或为记忆整理选择
模型”，details 带 `:action :configure-model`。

结果：`{:candidates [{:id :text :category :scope
{:status :pending-review
 :source {:message-id :span :excerpt}} :confidence :assumption? :truncated?}]
:skipped n :skipped-reasons {...} :included-message-ids [...]
:dropped-message-count n :attempts 1|2 :truncated? :dropped-candidates n}`。

- 需要 `:memory-suggest? true`；候选一律进入 `:candidate`，不直接影响未来请求。
- 模型调用只走 `hc.server.model-port.contract/ModelPort`：一次初始
  `start-request!`，解析失败时最多再发一次修复请求（带上次输出与错误位置）；
  模型尝试在服务锁外运行，评审与持久化在 store 事务内运行。
- 正文超过 2,000 个 Unicode 码点会截断并标 `:truncated? true`；假设保留
  `:assumption? true` 与 `:epistemic :assumption`。
- 敏感内容在持久化前过滤；归档、删除、已拒绝、已有效或已在待审的同内容记录
  分别抑制为 `:archived`、`:deleted`、`:already-rejected`、
  `:already-active`、`:already-candidate`。归档记录不会因新候选复活。
  “同内容”指精确哈希相同，或转小写并去掉空白与常见标点后相同；`+`、`#`、
  `%`、`@`、`&` 等符号保留区分，不做语义改写合并。
- 接受纯 JSON 数组、`{:candidates [...]}`、代码围栏包裹或前后带说明文字的
  上述两种形态。解析失败返回 `:MEMORY_SUGGEST_RESPONSE_INVALID`，details 带
  `:parse-error`（含位置）与最多 500 码点的 `:raw-excerpt`；片段含敏感值时
  省略并标 `:redacted? true`。修复仍失败时返回 `:status :parse-failed`、
  `:remedy :retry`、`:attempts 2`，不抛异常、不创建候选。
- 默认最多评审 20 条候选；超过 `:max-candidates` 的部分不静默丢弃，回执标
  `:truncated? true` 与 `:dropped-candidates n`。

材料库 readiness：`session/ensure-loaded` 在库缺失时用 CAS 初始化一次空库；
旧版本写入的 state 在加载时补齐新增顶层键（现有值优先），因此种子或恢复后的
首个快照即可 ready，首个 materials 命令成功。真正损坏的状态仍拒绝，不静默
覆盖。

### 4.3 范围、临时会话与注入

`effective` 的默认范围链：

```text
assistant (最具体) -> project -> global
```

调用方可传 `:scope-chain` 覆盖。链只决定顺序与可见性，不合并记录。

- `:memory-read? false`：不注入任何记忆，返回 `:reason :memory-read-disabled`。
- 临时会话：默认不读不写；`effective` 可接受本次只读覆盖 `:temporary-read? true`，但所有 memory 命令（remember/propose/approve/reject/replace/archive/restore/delete/search）仍拒绝，`:materials/snapshot` 不列出记忆正文或计数。`:temporary?` 只能来自会话记录，payload 不能覆盖。
- 不计算伪造的相关性分数。`effective` 按范围具体度、最近更新排序；`select-for-injection` 再把“始终提供”提前。
- `select-for-injection` 按预算整条选入；正文永不截断；放不下的条目进入 `:omitted`，附 `:reason :budget` 与字符数。
- `render-injection` 只生成确定性文本投影；它不声称模型已经遵守记忆。

### 4.4 备份合并

`merge-backup` 保留当前决定：

- 当前已归档或已删除：不复活；创建确定性 ID 的待审候选。
- 当前有效且内容相同：跳过。
- 当前有效但内容不同：不覆盖；创建待审候选。
- 备份中的 `:deleted` 记录：跳过。
- 同一 `:import-id` 重复导入：复用冲突候选 ID，幂等跳过。

### 4.5 只读出站视图（读 vs 推进）

`session/effective-memories-read`、`session/worldbook-selection-read`、
`session/instruction-cards-read` 是给 outbound context 的只读入口：

- 只调用 `store/load-state`，不 `transact!`、不 `initialize!`、不
  `state/prepare`；store revision 与 `:seq` 都不变。
- `worldbook-selection-read` 使用 `worldbook/preview-turn`：只读当前
  turn/runtime，不写 `:turn`、`:runtime`、`:last-turn`，不推进持续/冷却/
  延迟计数。真正消费用户轮次只能走 `context-for-turn!`（内部 `run-turn`）。
- 空库返回空集合（`[]`、0），不是 nil；只读入口不自动初始化缺失的库。
- 记忆只返回 bounded summary 与 version，正文不出站。
- 指令卡合并本轮 staged（`:scope :turn`）与会话级（`:scope :session`）；
  同一张卡以 `:turn` 出现一次。
- 工具面不在 materials：角色工具列表由 `history/context/tools-snapshot`
  投影；materials 不提供 `tools-read`。

## 5 世界书

### 5.1 计数单位

`run-turn` 必须且只能对一次有效用户提交调用一次，并携带 `:session-id`。工具往返、流式片段、发送失败、模型内部重试都不调用它，因此不消耗持续、冷却或延迟计数。计数按会话隔离：另一个会话不承接当前会话的轮次、持续或冷却。

`run-turn` 以 `:turn-id` 幂等：

- 相同 ID、相同输入：返回已存结果，`state` 不变，`:replayed? true`。
- 相同 ID、不同输入：拒绝 `:WORLDBOOK_TURN_CONFLICT`。
- 新 ID：轮次加一，更新运行时计数并提交一次修订。

`preview-turn` 评估下一轮但不增加轮次、不写计数，供发送前上下文检查。

### 5.2 触发

| 触发 | 语义 |
|---|---|
| `:keyword` | 关键词/短语；`:match :any` 或 `:all`；`:scan-depth` 默认 4；`:case-sensitive?` 默认 false |
| `:constant` | 满足延迟后始终提供 |
| `:expression` | 受控表达式 AST；无正则、无函数调用 |

表达式语法：

```clojure
[:contains "term"]
[:starts-with "prefix"]
[:ends-with "suffix"]
[:not expr]
[:all [expr ...]]
[:any [expr ...]]
```

限制：最多 16 层、128 个节点；未知操作符、空子表达式、非字符串参数都拒绝。无效表达式可以保存为停用草稿，但不能启用。

关键词匹配：纯 ASCII 单词使用字母/数字边界，避免 `cat` 命中 `concatenate`；短语与非 ASCII 关键词使用子串匹配，支持中文与含空格短语。

### 5.3 持续、冷却、延迟

设条目在用户轮次 $T$ 首次命中并被实际选入：

- `sticky = s`：$T+1$ 到 $T+s$ 继续提供；持续期间不重复插入同一条，也不重新触发计数。
- `cooldown = c`：$T+1$ 到 $T+c$ 不接受新的独立触发；持续提供不受冷却影响。
- `delay = d`：条目从第 $d+1$ 个有效用户轮次起参与判断；`0` 表示第一轮即可参与。

计数只在条目实际被选入时推进。命中但因预算未提供时，不消耗冷却；它留在 `:omitted` 中并说明原因。

### 5.4 预算与顺序

- 命中条目先按“关键条目”、书顺序、条目顺序排序，再整条装入预算。
- 正文永不截断；放不下的条目进入 `:omitted`，附 `:reason :budget`。
- 冷却中的命中进入 `:omitted`，附 `:reason :cooldown`。
- 同一轮同一条目最多出现一次。

### 5.5 书与条目

- 书默认未绑定任何助手，不全局生效；绑定与解绑是独立操作。
- 删除已绑定的书默认拒绝；必须显式解绑或 `:force? true`。
- 移动条目是整批原子操作；目标不存在时源条目不变；返回 `:undo` 所需的原书映射。
- 修改条目只影响未来判断；已发送轮次应保留调用方自己的正文快照。

## 6 搜索来源引用

引用集合是消息级值，不进入 `:materials` 状态。

### 6.1 URL 身份

`canonical-url` 只做保守规范化：小写 scheme/host、去默认端口、去 fragment、空路径补 `/`；查询参数保持原顺序；拒绝非 http(s)、无 host、带 user-info 的 URL。

同一 canonical URL 经多次查询命中时合并为一个来源：

- `:queries` 保留首次发现顺序。
- `:fetched-at` 保留首次抓取时间，`:last-fetched-at` 记录最近一次。
- `:published-at` 只来自服务/站点；没有就是 `nil`，绝不拿抓取时间冒充。
- 多个不同发布日期保留在 `:published-at-alternatives`。
- 内容状态取更强的一个：`:full` > `:partial` > `:summary` > `:blocked` > `:unknown`。

### 6.2 引用

- `cite` 只接受真实返回过的 URL；未知 URL 拒绝 `:SEARCH_SOURCE_UNKNOWN`。
- 首次引用分配递增编号；重复引用复用编号。
- 正文编号与来源列表严格对应；未引用结果进入 `:other`，不删除。
- `plain-text` 输出编号、标题、完整 URL、发布日期与抓取时间；发布日期未知时明确写 `published: unknown`。

### 6.3 全文读取

`record-read` 显式记录读取状态与读取时间；读取时间不是发布日期。状态合并规则同上。

### 6.5 指令卡

- 卡片有名称、说明、正文、可选占位符 `{:key :label :required?}` 与可选 `:output-format`。
- 卡片默认不生效；`set-instruction-session-cards` 持久化“本会话”选择，`select-instruction-cards` 解析“本轮”或会话选择。
- 禁用卡片时同时从所有会话选择与暂存轮次选择中移除，避免下一次 `context-for-turn!` 因选择中残留禁用卡而拒绝整轮。
- `stage-instruction-turn-cards` 暂存“下一有效用户轮次”的选择；`context-for-turn!` 在推进世界书轮次的同一事务里消费它。这样即使模型请求装配层只能传空 `:turn-card-ids`，已暂存的卡片仍会进入真实模型上下文；暂存不是长期设置。
- 暂存带 `:command-id` 时，消费后写入有界 `:instruction-turn-receipts`。同一 command-id 的重放不会再次暂存，避免重复发送把卡片泄漏到下一轮。
- 必填占位符缺失拒绝 `:INSTRUCTION_PLACEHOLDER_MISSING`；可选占位符保留原文并列入 `:unresolved`。
- 两张卡片声明不同非空 `:output-format` 时拒绝 `:INSTRUCTION_FORMAT_CONFLICT`，不自行选择胜者。
- 卡片正文不直接进入用户草稿，也不授予工具权限；实际工具调用仍由工具面板与执行器决定。

## 7 公共入口

```clojure
(require '[hc.server.materials :as materials])

;; 持久化
(materials/initialize! store)
(materials/load-state store)
(materials/transact! store f)
(materials/commit! store expected-revision state)

;; 记忆
(materials/remember state request)
(materials/propose-memory state request)
(materials/approve-memory state id changes)
(materials/reject-memory state id request)
(materials/replace-memory state id request)
(materials/archive-memory state id request)
(materials/restore-memory state id)
(materials/delete-memory state id {:confirm true})
(materials/search-memories state request)
(materials/effective-memories state context)
(materials/select-memories memories {:budget n})
(materials/render-memory-injection provided)
(materials/merge-memory-backup state request)

;; 世界书
(materials/create-worldbook state request)
(materials/update-worldbook state id request)
(materials/bind-worldbook state id assistant-id)
(materials/unbind-worldbook state id assistant-id)
(materials/delete-worldbook state id request)
(materials/create-worldbook-entry state request)
(materials/update-worldbook-entry state id request)
(materials/move-worldbook-entries state request)
(materials/delete-worldbook-entry state id)
(materials/run-worldbook-turn state {:session-id "s1" ...})
(materials/preview-worldbook-turn state {:session-id "s1" ...})
(materials/validate-worldbook-expression expr)

;; 指令卡
(materials/create-instruction-card state request)
(materials/update-instruction-card state id request)
(materials/delete-instruction-card state id)
(materials/set-instruction-session-cards state {:session-id ... :card-ids [...]})
(materials/clear-instruction-session-cards state session-id)
(materials/instruction-session-card-ids state session-id)
(materials/select-instruction-cards state {:card-ids [...] :values {...}})
(materials/stage-instruction-turn-cards state {:session-id ... :card-ids [...] :values {...}})
(materials/clear-instruction-turn-cards state session-id)
(materials/instruction-turn-card-selection state session-id)
(materials-session/stage-turn-from-send! service session payload {:command-id ...})
(materials-session/user-turn-request? request)

;; 搜索引用
(materials/new-reference-set)
(materials/add-search-results refs request)
(materials/cite-search-source refs url)
(materials/search-citation-marker refs url)
(materials/search-source-list refs)
(materials/record-search-read refs url request)
(materials/search-plain-text refs)
(materials/check-search-references refs)
```

### 7.1 应用装配

```clojure
(require '[hc.server.materials.durable-store :as durable]
         '[hc.server.materials.session :as materials-session])

(def service
  (materials-session/service (durable/durable-store storage)))
(materials-session/initialize! service)

;; one accepted user submission
(materials-session/context-for-turn!
 service
 {:session session
  :turn-id "turn-1"
  :text "user text"
  :user-texts ["previous" "user text"]
  :memory-budget 2000
  :worldbook-budget 4000
  :turn-card-ids [] ; direct user-turn fields are authoritative
  :instruction-values {}
  :use-staged? false}) ; staging requires explicit true and is legacy

;; client commands
(materials-session/handle-command!
 service
 {:session session
  :command-id "cmd-1"
  :command-epoch 1
  :payload {:kind :materials/memory/remember
            :body "..." :category :working-style
            :scope :global}})

;; baseline projection
(materials-session/snapshot service session)
```

`context-for-turn!` 返回 `:memory`、`:worldbook`、`:instruction` 与 `:injection`；其中 `:injection` 含 `:instructions`、`:memory`、`:worldbook` 文本和 `:worldbook-entries`。指令冲突与记忆范围错误在世界书轮次提交前拒绝，避免坏上下文消耗用户轮次。`:use-staged?` 默认 `false`；只有显式传 `true` 且 `:turn-card-ids` 为空、该会话存在暂存选择时，才使用暂存选择并在同一事务中清除它。显式非空 `:turn-card-ids` 覆盖暂存内容，但仍在同一事务中消费暂存，避免下一轮复用。不是用户轮次的模型调用（nudge/review 等）必须传 `:use-staged? false`，否则会错误消费暂存。

`context-for-turn!` 还接受 `:temporary-read? true` 作为临时会话的一次性只读覆盖；它只在本轮 `memory-context` 生效，不写回会话，普通会话忽略它。`app/system.clj` 的 `material-injection` 必须从请求或快照透传该字段；本包只提供服务端能力，接线由 server-app 负责。

`snapshot` 的 `:worldbook-session :last-turn :omitted` 最多返回 50 条审查字段（`:entry-id`、`:book-id`、`:title`、`:reason`、`:chars`），不返回正文或整轮结果；没有 `:last-turn` 时省略该键，客户端据此显示“服务器未报告”。

发送装配有一条当前真实路径和一条可选暂存路径：

1. 当前客户端在 `:send-message` payload 里直接带 `:turn-card-ids`/`:instruction-values`；Owner 把它们写入本轮请求，`app/system.clj` 的 `material-injection` 直接读取。`stage-turn-from-send!` 是给需要服务端暂存的调用方准备的 API，当前 app 路由不调用它；不得把“已提供 helper”说成“路由已接线”。
2. 需要暂存的调用方可以先发 `:materials/instruction/turn/stage` 命令，再发送普通消息；`context-for-turn!` 自动消费暂存。显式非空 `:turn-card-ids` 覆盖本轮暂存内容，并在同一事务中消费暂存，避免它泄漏到下一轮。

当前 `app/system.clj` 已读取最后一条用户消息上的 `:turn-card-ids`/`:instruction-values`，并用 `user-turn-request?` 门控。门控从请求信封或快照读取真实回合身份：显式 `:user-turn? false` 一律视为续轮；显式 `true` 在最后一条为 `:user` 时成立，最后一条为 `:tool` 时必须同时带非空且相等的 `:turn-command-id`/`:turn-id`，只有孤立 id、空 id 或不相等 id 都视为续轮。没有标记时退回“最后一条消息是 `:user` 且无 `:nudge-id`”。nudge/review/tool/regenerate 续轮调用不注入卡片、不注入记忆/世界书、不推进世界书计数、不消费暂存，并原样返回请求。

`handle-command!` 接受 `:materials/*` kind（含 `:materials/instruction/turn/stage` 与 `:clear`），返回 `:receipt {:status :accepted :command-id ... :command-epoch ... :result {...}}`；写入经服务锁与 Store CAS。它不返回完整状态。

`snapshot` 顶层键：`:materials/ready?`、`:materials/revision`、`:materials/settings`、`:materials/session`、`:memory/by-id`、`:memory/order`、`:memory/counts`、`:worldbook/by-id`、`:worldbook-entry/by-id`、`:worldbook-session`、`:instruction-card/by-id`、`:instruction-session`、`:instruction-turn`。

## 8 不变量与测试

`check-invariants` 汇总状态、记忆与世界书检查；`check-search-references` 检查引用集合。空向量表示有效。

测试：

```bash
clojure -M:test:server -r "hc.server.materials.*-test"
clojure -M:test -n hc.server.materials.memory-test
clojure -M:test -n hc.server.materials.worldbook-test
clojure -M:test -n hc.server.materials.search-test
clojure -M:test -n hc.server.materials.instruction-test
clojure -M:test -n hc.server.materials.session-test
clojure -M:test -n hc.server.materials.input-isolation-test
clojure -M:test -n hc.server.materials.store-test
clojure -M:test:server -n hc.server.materials.durable-store-test
clojure -M:test:server -n hc.server.materials.send-context-smoke-test
clojure -M:test -n hc.server.materials-smoke-test
```

覆盖：

- 两个记忆开关独立、范围顺序、临时会话只读覆盖与写入拒绝。
- 候选来源/认识标签必填、敏感内容拒绝、拒绝抑制、替换历史、归档/恢复/墓碑。
- 预算整条选入、始终提供优先、备份合并不复活已归档/已删除记录。
- 世界书持续/冷却/延迟按用户轮次、轮次幂等、预算“命中但未提供”、表达式草稿、批量移动原子性、按会话隔离。
- 指令卡 CRUD、占位符、会话选择、删除清理与输出格式冲突。
- 搜索 URL 合并、发布日期与抓取时间分离、引用稳定、未引用结果、全批校验。
- 持久化 CAS 冲突、显式初始化、损坏状态拒绝、`transact!` 单次提交。
- 输入隔离实际路径：assistant 作用域记忆不串助手；global 记忆可见；临时会话不读记忆但仍可使用世界书；世界书按助手绑定；轮次/持续计数按会话隔离。
- 附件路径：用户消息条目保存已校验附件，模型消息白名单保留 `:attachments`；会话提交/删除通过 `blob-refs/sessions-refs` 维护 blob 引用，临时会话丢弃释放引用，不声称立即擦除物理副本。
- 真实 Datalevin 适配器：创建、提交、关闭、重开、重载、陈旧 CAS 拒绝；session service 经持久适配器完成一轮上下文。
- 实际模型请求路径 smoke：直接调用 `app.system/material-injection`，验证空 `:turn-card-ids` 时暂存卡片仍进入 system message 并在同一轮被消费。
- 端到端 smoke：记忆审阅、世界书轮次、引用编号、重开存储。

## 9 已知边界

- `memory-store` 仅用于测试；生产路径使用 `durable-store`，它已持久化到 `hc.server.storage`。
- 没有真实联网搜索；`add-results` 只接收调用方已经取得的真实结果。
- 没有模型整理器；`propose` 只审阅调用方给出的候选，不推断用户事实。
- 没有语义相似度或向量召回；重复判断只基于内容哈希、类别与范围，绝不把相似度当事实。
- 没有 UI、导出包加密、备份恢复流程；这些属于其他包。材料文档已在主存储中，备份由 `export-state`/`restore-state!` 覆盖。
- 附件模块不属于本包；本包只核验了用户消息到模型请求的字段保留与会话 blob 引用路径。附件字节的实际擦除仍由 blob 清理窗口决定，不承诺即时销毁外部副本。
- 会话记录当前没有填充 `:project-id`；project 作用域记忆只有在调用方显式提供 project id 时才会匹配。不要把它误写成已按项目隔离。
- 指令卡已实现库、选择、占位符、格式冲突与每轮暂存；`app/system.clj` 当前从请求或最后一条用户消息读取显式 `:turn-card-ids`/`:instruction-values`，真实发送路径不再依赖 UI 预先发送 `:materials/instruction/turn/stage`。模型请求中的最终拼接位置仍由应用组合层决定。
- `app/system`、`app/commands`、`app/snapshot` 与 Owner 的每轮上下文钩子属于应用装配文件，不在本包所有权内。本包已提供 `session.clj` 的精确 API；在这些接线落地前，不得声称材料上下文已经注入真实模型请求。
