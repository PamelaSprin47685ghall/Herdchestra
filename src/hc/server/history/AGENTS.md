# hc.server.history — 消息版本、分支、上下文边界与可撤销压缩

> 权威顺序：根 `AGENTS.md` §7.4 旅程 2.6、5（消息、历史与知识）、§5.1 > `src/AGENTS.md` > `src/hc/server/AGENTS.md` > 本文件。
> 本文件只说明本包边界、数据形状与拒绝码；根规格冲突时以根规格为准。

## 1 边界

本包是纯领域层：

- 不写存储、不启动线程、不调用模型、不删除 Blob；
- 除用户显式触发的 `:history/regenerate` 外不启动 run；不重放历史工具
  调用。编辑命中当前 run 的输入消息时，只按 `:input-changed` 取消该 run
  并返回取消效果；
- 只接收 Owner 已通过命令闸门的 `:history/...` 命令；
- 返回完整的新 session 与 `:effects []`，由 Owner 统一提交一个 revision。

`hc.server.sessions` 仍拥有 `:session/edit-message` 等既有命令。本包提供
`:history/edit-message` 供需要版本级附件/引用快照的调用方使用；两者都只追加
版本，不发送内容。

全局历史全文索引是独立的可重建派生层，见
`src/hc/server/history/index/AGENTS.md`；本包的阅读投影与发送投影不读取索引
命中的词项，命中必须回主库核对。

## 2 消息与版本

```clojure
{:message-id "m-1"
 :role :user
 :versions [{:version-id "m-1-v1"
             :content "..."
             :created-at 1000
             :edited? false
             :source :model          ; :model | :manual | :regenerated | :branch | :imported
             :parent-version-id nil
             :attachments [{:attachment-id "a-1" ...}]
             :citations [...]
             :quotes [...]
             :tool-records [...]
             :usage {...}}]
 :current-version 0
 :attachments [...]              ; 当前版本镜像，供旧消息回退
 :citations [...]
 :deleted? false
 :folded? false}
```

规则：

1. `:versions` 只追加。编辑旧消息生成新版本并选中；原版本正文、附件引用、
   引用来源、工具记录与用量保持原样。
2. 版本快照各自携带 `:attachments` / `:citations` / `:quotes` / `:tool-records` / `:usage`。
   切换版本时这些字段随版本一起切换。
3. 附件身份是 `:attachment-id` 字符串。本包不复制、不改写、不删除引用；
   编辑时移除某附件只影响新版本的引用向量，原版本与 Blob 不受影响。
   客户端只带 `:id`/`:blob-id` 的引用会补上同一值的 `:attachment-id`，
   不改变原字段或附件身份。
4. 手工编辑助手回复的版本标记 `:manual-edit? true`，不冒充模型原始输出；
   未显式提供 `:usage` 时继承当前版本用量并标 `:usage-inherited? true`。
5. 重新生成必须由用户显式点击。`:history/regenerate` 为目标助手消息启动
   一个 run，run 快照固定 `:target-message-id`/`:target-version-id`/
   `:target-mode :append-version`，并冻结目标之前的 `:messages`（含按位置
   保留的压缩摘要）；成功终态把新版本追加到同一条消息并选中，不新增
   第二条助手消息，也不重放历史工具。`append-version` 只追加调用方已经
   生成的正文、引用、工具记录与用量，不启动 run。
6. `select-version` 只改变 `:current-version` 与消息级 `:attachments`/`:citations`
   镜像，不改变 `:activity-at`，不发送请求。持久化和重启恢复必须同时保留
   `:versions`、`:current-version` 与该镜像；读取附件时以选中的版本快照为准，
   空向量是“该版本没有附件”，不得回退到其他版本。

## 3 上下文边界

session 的 `:context` 形状：

```clojure
{:boundary {:message-id "m-3" :set-at 1000}
 :recent-rounds 8
 :compressions {"cmp-1" {...}}
 :compression-order ["cmp-1"]
 :pinned-attachment-ids ["a-1"]}
```

- `:boundary` 是“从此处开始”的第一条候选消息；清除边界恢复完整候选范围。
- `:recent-rounds` 只保留最近 N 个用户轮次；工具往返不增加轮次。
- `:pinned-attachment-ids` 是历史图片的优先保留身份；被边界或压缩排除时，
  仍在 `:pinned-attachments` 中显式列出。身份缺失时报告
  `:PINNED_ATTACHMENT_MISSING`，绝不静默取消固定；传入
  `:attachment-limit` 且固定数超过上限时报告
  `:PINNED_ATTACHMENT_LIMIT_EXCEEDED`，同样不自动取消固定。
- `reading-context` 永远返回完整可见历史；`outbound-context` 才应用边界、
  最近轮次与压缩。两者不可混用。

`outbound-context` 返回：

```clojure
{:messages [message-view | summary-entry ...]
 :message-ids [...]
 :version-ids {"m-1" "m-1-v2" ...}
 :summary-ids [...]
 :excluded [{:message-id "m-1" :reason ...} ...]
 :pinned-attachments [...]
 :warnings [...]
 :history {:range {...} :compressed? bool
           :visible-count n :excluded-count n}
 :system {:prompt-bytes n|:unknown :modules [...]}
 :assistant {:name ... :revision ...}
 :instructions {:session-override? bool :text-preview ...}
 :memory {:enabled? bool :items [...] :archived-excluded n|:unknown}
 :worldbook {:enabled-books [...] :provided [...] :hit-not-provided [...]}
 :instruction-cards [...]
 :tools [...]
 :attachments [...]
 :estimates {:tokens n|:unknown :kind :estimated|:unknown}
 :boundary {...}}
```

`summary-entry` 是 `{:kind :context-summary :role :system :compression-id ...
:source-message-ids [...] :source-version-ids {...}}`，只存在于出站投影，
不是 `:message/by-id` 实体。

`context-snapshot` 把这些上下文段投影成客户端可读的纯数据。调用方提供
`:system`、`:assistant`、`:instructions`、`:memory`、`:worldbook`、
`:instruction`、`:tools`、`:estimates`，也可以把它们放在 `:materials` 下。
缺少数据时输出 `[]` 或 `:unknown`，不伪造 0：

- 记忆只发布 `:summary` 与 `:version`，正文永不出站；`:archived-excluded`
  由 `:memories` 计数或显式传入，否则为 `:unknown`。
- `:system` 由 `prompt-modules-snapshot` 计算：显式 `:prompt-bytes` 优先，
  否则用 `:text` 或模块文本拼接的 UTF-8 字节；`modules` 只列非空文本模块或
  name-only 模块。没有文本/字节数时 `:unknown`。
- `:assistant` 由 `assistant-snapshot` 从助手实体取 `:name` 与
  `:revision`/`:v`/`:updated-seq`；无实体或名称时 `:unknown`。
- 世界书 `:hit-not-provided` 保留条目未入选的确切 `:reason`（如 `:budget`、
  `:cooldown`）。
- `:enabled-books` 显式值优先；否则从 `:materials :books` 按当前
  `:assistant-id`（顶层或 `:materials` 内）的绑定推断；再否则取本轮
  `:selected` 与 `:omitted` 中实际参与的书；都没有时为 `[]`。显式空向量
  表示没有启用书，不被推断覆盖。
- 指令卡 `:scope` 区分 `:turn` 与 `:session`；本轮卡不会出现在下一轮快照。
- 工具由 `tools-snapshot` 生成 `{:name :source :read-write :enabled?}`；
  读写性质依次取显式 `:read-write`、`:read-only?`、annotations、capability
  set，最后按名称保守回退；未知保持 `:unknown`。`source` 取
  `:source`/`:origin`/`:kind`。
- `:estimates` 由 `estimates-snapshot` 按 4 字符/token 估算；显式 tokens
  优先，正字符数至少 1 token，空/缺失为 `:unknown`，不写 0。
- `:instructions :text-preview` 截断 200 码点；命中
  `memory/sensitive-kinds` 时置 nil 并标 `:redacted? true`。
- 附件 `:pages` 是选中版本的显式页号（升序去重）；只有页数时发布
  `:page-count`，不编造页号。
- `:history :range` 是本轮发送范围，`:visible-count` 是完整可见历史；两者
  不可互相替代。

## 4 可撤销压缩

压缩记录：

```clojure
{:compression-id "cmp-1"
 :state :draft | :applied | :reverted | :cancelled
 :message-ids ["m-2" "m-3"]
 :range {:from-message-id "m-2" :to-message-id "m-3"}
 :summary "用户审阅后的摘要"
 :source-version-ids {"m-2" "m-2-v1" "m-3" "m-3-v1"}
 :created-at 1000 :applied-at 2000 :reverted-at nil}
```

生命周期：

1. `prepare` 选择连续可见范围并保存调用方提供的摘要审阅稿；不替换发送范围。
2. `update-draft` 只改草稿摘要；`apply` 确认后才进入 `:applied` 与
   `:compression-order`。
3. `outbound-context` 只替换完整落在当前选择范围内的 applied 范围；范围被
   边界/最近轮次切开时报告 `:COMPRESSION_OUTSIDE_CONTEXT`，不改写历史。
4. `undo` 把状态改为 `:reverted` 并移出顺序；原消息从未删除，后续新消息
   仍在。压缩源版本后来被编辑时，摘要条目标 `:stale? true`，不自动重生成。
5. 草稿、已应用与已撤销记录都保留在 session 中；`cancel` 只取消草稿。

## 4.0 重新生成运行桥

`hc.server.history.run-port` 是历史 run 与 Owner 模型循环之间的窄桥：

- `start`：在 Owner 接受 `:run-started` 效果后，调用 `runs/claim-call`
  认领 session call，从 run 输入快照的 `:context :messages` 构造模型消息，
  按 `:version-ids` 取冻结版本，保留用户消息的 attachments/quotes/citations，
  压缩摘要按 outbound 位置保留；返回 `:request` 与 `:session-run` 身份；
  不直接调用 provider。旧快照没有 `:messages` 时回退到当前投影，但仍按
  位置保留摘要。
- Owner 为请求创建普通 `:kind :model` 调用并标记 `:session-run`，由现有
  模型守卫 claim/dispatch。
- `accept`：Owner 模型终态回到 session run，`sessions/accept-model-event`
  追加/拒绝版本；成功终态才追加 `:regenerated` 版本。
- 只有目标模式为 `:append-version` 的重新生成请求带
  `:user-turn? false`/`:regenerate? true`；普通发送带 `:user-turn? true`/
  `:regenerate? false`，材料注入不得把普通轮次误判为 continuation。
- 没有该桥时 run 会停在 `:running`；app/system 注入
  `:session-run-port`，Owner runtime 把它放入 ctx 并允许 session-run 模型
  调用在 mission admission 关闭时仍被 claim。
- 若 `:session-run-port` 缺失、`:start` 缺失或 start 失败，Owner 必须调用
  `:fail`（或 `:history/fail-run`）关闭 run/call/attempt，记录
  `:SESSION_RUN_DISPATCH_FAILED` 诊断，并在 run 上保留 `:failure`；绝不
  留下永久 `:running` 的已接受 run。

## 4.1 客户端快照

`hc.server.history.snapshot` 把 session 记录投影成工作台已有的
`:message/by-id` / `:message/order` 表：

- 每条可见消息带 `:id`/`:message-id`、`:session-id`、`:role`、当前
  `:content`、`:attachments`、`:citations`/`:sources`、`:tool-records`、
  `:usage`、完整 `:versions`、`:current-version`、`:version-id`、
  `:manual-edit?`；
- 每个版本快照都带自己的 `:citations`/`:sources` 与 `:attachments`；
  客户端切版本时来源卡、附件与工具记录随版本切换，不沿用旧版本镜像。
- `history-durable-smoke-test` 覆盖真实存储关闭/重开后的选版顺序：v2（空附件）
  → v1（有图）→ 再切回 v2，当前版本和附件镜像都必须原样恢复；
  `history-test` 与客户端 `conversation-test` 覆盖同一记录的客户端投影。
- 客户端内联编辑保存时必须显式携带 `:attachments`；省略该键表示继承当前
  版本附件，空向量表示新版本不引用任何附件。两种写法都只追加版本，不删除
  原版本引用或 Blob。
- 会话摘要合并 `:message-ids`、`:context` 与 `:branch-of`
  （来自 `:origin-session-id`）；
- `:session/history` 保存每个会话的 context/outbound 预览；
- `:message/order` 覆盖 `:message/by-id` 的全部实体，满足客户端
  `to-db` 的完整性校验。
- 多个会话的 owner conversation 合并时按会话收集后拼接 order，不把
  order 向量当 map 合并；每个会话的 `:message-ids` 只含本会话消息，
  不得用全局 by-id 覆盖另一个会话的同名条目。
- 客户端消息的 `:streaming?` 由 active Owner `:runs` 的 `:message-id`
  与终态状态（含 `:finished`）对齐；不得只按 call-id 查找，否则
  `assistant-<call>-<attempt>` 消息会永久停在“正在生成”。

组装层从 registry 读取完整 session 记录（不是只有摘要）后传入
`snapshot/baseline` 的 `:session-records`。折叠/删除消息不进入显示表。

## 4.2 Owner conversation 镜像

Owner 的 `:conversation` 是实时显示源，session history 是可编辑/版本化
副本。新用户提交与成功的助手终态通过 `:history/append-message` 镜像，使用
同一稳定 message-id；附件、引用、quotes、usage、`:run-id` 与
`:nudge?`/`:nudge-id` 原样保留。重复镜像同一 id 幂等；只有助手原始未编辑
版本的空白正文，或显式带 `:partial? true` 的部分正文，可原地更新为最终
正文；用户消息与人工编辑版本不被覆盖。

旧会话在历史命令命中目标 message-id 时懒补该条消息，不扫描/复制整段会话。
镜像的 Nudge 助手消息保留 provenance，但 `session-history`、outbound
context 与客户端基线只过滤显式 `:nudge?`/`:nudge-id`，正常回答不受影响。

## 4.3 删除与恢复

- 会话处于 `:deleted` 时，任何 `:history/*` 命令都返回
  `:SESSION_DELETED`，不得继续编辑、选版、压缩或重新生成；先恢复会话。
- 恢复保留消息、全部版本、当前版本、附件引用、边界、最近轮次与压缩
  记录；已删除/折叠消息仍按原标记排除。
- 分支在创建时复制版本与附件身份；来源会话之后删除或恢复不改变分支
  正文、版本、附件或继续聊天能力。`origin-session-id` 只作跳转提示。
- `:session/delete-message`/`:session/fold-message` 与 `:history/*` 一样经
  Owner 命令面路由；被删除/折叠消息不进入 reading/outbound 投影。

## 5 分支

分支由 `hc.server.sessions/branch-session` 创建。本包要求并测试：

- 复制起点之前的消息，消息 ID 与版本 ID 重新分配；
- 版本正文、选中版本、`:attachments`/`:citations`/`:tool-records`/`:usage`
  原样复制，附件 ID 不重写；
- 版本 `:parent-version-id` 与 `:regenerated-from-version-id` 按新旧版本
  映射重写；源 run 身份移到 `:origin-run-id`，分支不保留可被误用的
  `:run-id`；
- `:origin-session-id`/`:origin-message-id` 保留来源跳转；
- `:runs`/`:calls`/`:attempts`/`:drafts` 为空，`:context` 为空；
- 原会话删除后分支仍完整可读；分支不继承完全访问或一次性授权。

## 5.1 客户端控件

会话视图只渲染服务器快照中的状态，不自行改版本或伪造终态。工作台回调：

- `:edit-message!` / `:history/edit-message!` → `:history/edit-message`
- `:regenerate!` → `:history/regenerate`（真实模型 run，目标版本追加）
- `:branch!` / `:history/branch!` → `:history/branch`
- `:history/select-version!` → `:history/select-version`
- `:history/set-boundary!` / `:history/clear-boundary!`
- `:history/prepare-compression!` / `:history/apply-compression!` /
  `:history/undo-compression!`
- `:history/pin-attachment!` / `:history/unpin-attachment!`

没有回调的控件不得显示为可用按钮。编辑/分支/压缩成功后不自动发送。
分支回执携带新 `:session-id`，工作台据此选中新会话。上下文面板可导出
当前分支：只使用当前选中版本的正文，不夹带过程区、隐藏指令或密钥。

## 6 命令

```clojure
(history/apply-command session {:kind :history/edit-message ...} opts)
(history/apply-command session {:kind :history/append-message ...} opts) ; owner mirror
(history/apply-command session {:kind :history/select-version ...} opts)
(history/apply-command session {:kind :history/append-version ...} opts)
(history/apply-command session {:kind :history/regenerate ...} opts)
(history/apply-command session {:kind :history/fail-run ...} opts) ; internal closure
(history/apply-command session {:kind :history/set-context-boundary ...} opts)
(history/apply-command session {:kind :history/clear-context-boundary} opts)
(history/apply-command session {:kind :history/set-recent-rounds ...} opts)
(history/apply-command session {:kind :history/pin-attachment ...} opts)
(history/apply-command session {:kind :history/unpin-attachment ...} opts)
(history/apply-command session {:kind :history/prepare-compression ...} opts)
(history/apply-command session {:kind :history/update-compression-draft ...} opts)
(history/apply-command session {:kind :history/apply-compression ...} opts)
(history/apply-command session {:kind :history/undo-compression ...} opts)
(history/apply-command session {:kind :history/cancel-compression ...} opts)
(history/apply-command session {:kind :history/context-preview ...} opts)
(history/apply-command session {:kind :history/message-versions ...} opts)
```

组装：`(sessions/session-port {:delegate history/apply-command
:outbound-context history/outbound-context})`；Owner 闸门先接受命令，
`session-command` 再调用 session port。`compose.owner.handlers` 按
`(namespace kind) = "history"` 放行，未知 `:history/*` 由本包拒绝。
`:session/send-message` 用 `outbound-context` 的 `:message-ids` 作为本次
run 的发送范围，并把投影冻结到 run 输入快照的 `:context`。

拒绝码（节选）：`:MESSAGE_NOT_FOUND`、`:MESSAGE_DELETED`、`:MESSAGE_FOLDED`、
`:MESSAGE_CONTENT_INVALID`、`:VERSION_ID_TAKEN`、`:MESSAGE_VERSION_CONFLICT`、
`:CONTEXT_BOUNDARY_INVALID`、`:CONTEXT_BOUNDARY_MESSAGE_NOT_FOUND`、
`:CONTEXT_RECENT_ROUNDS_INVALID`、`:ATTACHMENT_ID_INVALID`、
`:ATTACHMENT_NOT_FOUND`、`:COMPRESSION_RANGE_REQUIRED`、
`:COMPRESSION_RANGE_NOT_CONTIGUOUS`、`:COMPRESSION_RANGE_OVERLAP`、
`:COMPRESSION_SUMMARY_REQUIRED`、`:COMPRESSION_NOT_DRAFT`、
`:COMPRESSION_ID_TAKEN`、
`:COMPRESSION_NOT_APPLIED`。

## 7 不变量

`history/check-invariants` 组合：

- 消息角色合法；版本非空、版本 ID 唯一、当前版本在范围内；正文为字符串；
- 每个版本附件引用合法且同一版本内不重复；
- 同一 `:attachment-id` 在所有版本中的描述符一致；
- 边界消息存在；最近轮次为正整数；
- 压缩范围非空、连续、引用存在；applied 范围互不重叠；
- `:compression-order` 只包含 applied 压缩；
- 固定附件身份仍可解析。

## 8 测试

- `test/hc/server/history_test.clj`：版本编辑、版本切换、重新生成、边界、
  最近轮次、固定附件、压缩审阅/应用/撤销/重叠拒绝、压缩 id 冲突、
  普通发送与 regenerate 的 flags、冻结压缩摘要与用户结构化字段、
  已完成回答不被迟到镜像覆盖、分支与附件身份。
- `test/hc/server/history_durable_smoke_test.clj`：真实 Datalevin 临时库，
  经 `SessionPersistence` 提交、关闭、重开，验证版本、附件身份、边界与
  applied 压缩仍在，且没有 run/call/attempt 被创建。
- `test/hc/server/history_live_smoke_test.clj`：真实应用启动、完整 session
  记录、Owner/gateway 命令、发布基线、停止并重启；验证编辑、压缩、分支和
  版本在 live Owner 状态与快照中可见。
- `test/hc/compose/owner/session_run_bridge_test.clj`：真实 Owner 守卫、
  模型调用与终态；验证重新生成只追加目标消息的新版本，run 完成，不新增
  第二条助手消息。
- `test/hc/server/history_regenerate_live_test.clj`：真实 app + 真实
  HTTP OpenAI 兼容 stub；经 gateway router 提交 `:history/regenerate`，
  等真实模型终态，验证目标消息新增 `:regenerated` 版本、run completed、
  消息数不变。缺失/失败 dispatch 由 `:history/fail-run` 或强制纯状态
  闭合，测试覆盖 run/call 不得停在 `:running`。
- `test/hc/server/history_snapshot_test.clj`：session 记录 → 客户端
  `:message/by-id`/`:message/order` → `hc.client.sync.normalize/to-db`，
  并验证 `:session/by-id` 的 `:message-ids`/`:context`/`:branch-of` 与
  workbench 消息解析。
