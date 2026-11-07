# `hc.client.sync` — 纯客户端 AG-UI 状态同步适配器

> 权威顺序：根 `AGENTS.md` §7.3 > `src/AGENTS.md` > 本文件 > 代码注释。
> 本模块只负责服务器当前态的显示副本与同步边界；不新增服务端协议义务。

## 1 边界

`src/hc/client/sync` 是浏览器端的纯状态归约器：

- 无 I/O、无 atom、无定时器、无 Sente 依赖、无 Fulcro 依赖。
- 源码为 `.cljc`，同一份代码可由 Clojure/JVM 测试与 ClojureScript 客户端加载。
- 只持有服务器状态树 `:server`、其归一化视图 `:db`、同步元数据。
- 大状态对象只保存引用与待取队列；字节抓取、摘要校验与 EDN 解码属于
  `hc.client.workbench.sync-bridge`，适配器只接受已验证的 `:value`。
- 不持有 `:ui/draft`、展开状态、选区、IME 组合态、命令回执表；这些由调用方的 Fulcro 数据库与命令层管理。
- 不处理 `RUN_STARTED`、文本流、工具流或 `CUSTOM` 业务事件；它们属于消息/运行归约器。未知事件只记录 `:ignored`，不改变服务器副本。

## 2 状态形状

```clojure
{:phase        :disconnected
 :subscription 0
 :epoch        nil
 :seq          nil
 :synced-at    nil
 :reason       nil
 :last-outcome {:kind :initial :result :idle}
 :pending-object nil        ; 等待抓取/校验的大对象引用
 :deferred-events []        ; 等待该对象应用的后续状态事件（有界）
 :last-object  nil          ; 最近一次成功应用的对象诊断信息
 :server       {}
 :db           {}}
```

- `:subscription` 是本地订阅代号；每次 `connect` 加一。
- `:epoch` 是服务器同步纪元；`:seq` 是该纪元内最后应用的序号。
- `:synced-at` 是最近一次成功边界携带的 `:at`。失败与断线不覆盖它。
- `:server` 是协议状态树原文；`:db` 是它的 Fulcro 归一化视图。
- `:last-outcome` 只用于诊断与 UI 文案，不参与领域判断。
- `:pending-object` 是 `{:kind :state-snapshot|:state-delta :ref ref
  :event original-event}`；非空时 `synced?` 与 `command-allowed?` 均为
  false，`:seq` 保持最后确认值。
- `:deferred-events` 按到达顺序保存后续状态事件，上限 32 条；超限进入
  `:connected-awaiting-sync` 并要求新基线，绝不静默丢事件。

## 3 五阶段连接状态机

```text
:disconnected -> :connecting -> :connected-awaiting-sync -> :synced
                                                         -> :interrupted
```

| 入口 | 结果 |
|---|---|
| `connect` | 任意阶段可调用；进入 `:connecting`，订阅代号加一，清空 `:epoch`/`:seq`，保留 `:server`/`:db`/`:synced-at` |
| `channel-opened` | 仅从 `:connecting` 进入 `:connected-awaiting-sync`；其他阶段忽略 |
| `receive` 收到合法基线 | `:connected-awaiting-sync` 或 `:synced` -> `:synced` |
| `channel-interrupted` | `:connecting`/`:connected-awaiting-sync`/`:synced` -> `:interrupted`；保留最后确认副本与时间 |
| `request-resync` | 在通道仍开时退回 `:connected-awaiting-sync` |
| `disconnect` | 任意阶段 -> `:disconnected`；保留最后确认副本与时间 |

`command-allowed?` 仅在 `:synced` 且 `:epoch`、`:seq` 均非空时为真。不得用单一 `online?` 布尔值替代阶段判断。断线只改变连接说明；关闭标签页、卸载组件或断线绝不取消服务器任务。

### 3.1 Feed epoch 与 Owner command epoch 严格分离

适配器里的 `:epoch` 是 AG-UI 订阅/增量的同步纪元；它绝不作为业务命令的 `command-epoch`。
应用级命令使用服务器快照 `:sync :command-epoch` 中的 app 命令纪元，由
`(sync/command-epoch state)` 读取。两者可以不同，任何代码不得用 `:epoch` 回退冒充
`command-epoch`。

多会话时，每个会话的 Owner 有独立纪元。服务器在 `:session/by-id/<sid>` 上发布该会话的
`:command-epoch`；已删除会话不在该表中，只在 `:session/list` 回执的
`:ui/session-list` 行上带同一 Owner 纪元。桥必须先读 Fulcro 显示库（可见行或已删除行），
再回退到适配器的已确认视图；会话命令必须只使用按同一 `sid` 取得的这个值。全局
`:sync :command-epoch` 是 app 命令纪元，绝不给任何会话命令兜底。会话没有自己的纪元时，
客户端应阻止发送并显示原因，绝不把 app 或另一个会话的纪元发给该 Owner。

确认、重同步与回执恢复由工作台桥负责：适配器只提供 `:phase`、`:subscription`、`:seq`
与 `:last-outcome`。收到 `hc/resync-required` 或 Owner 纪元错误后，桥应请求新基线；
重连取得新基线后按原 `command-id` 查询未决回执，绝不自动重放命令。每个已应用的状态事件
可以用 `:seq` 发送 `:hc/ack`，让服务器在客户端落后时标记 stale 而不阻塞发布。

## 4 事件合同

事件类型接受字符串或关键字，大小写与下划线归一化：

- `"STATE_SNAPSHOT"` / `:STATE_SNAPSHOT` -> `:state-snapshot`
- `"STATE_DELTA"` -> `:state-delta`
- `"MESSAGES_SNAPSHOT"` -> `:messages-snapshot`

同步字段可平铺，也可放在 `:sync` 子映射中：

```clojure
{:type "STATE_SNAPSHOT"
 :snapshot {...}
 :epoch 7
 :seq 40
 :at "2026-11-03T10:00:00Z"
 :subscription-id 2}          ; 可选；也可由 receive 的第三参数显式传入

{:type "STATE_SNAPSHOT"
 :snapshot {...}
 :sync {:epoch 7 :seq 40}}
```

`:epoch` 必须是非负整数；`:seq` 必须是非负整数。`STATE_SNAPSHOT` 缺任一字段即拒绝。`STATE_DELTA` 与 `MESSAGES_SNAPSHOT` 缺任一字段即进入重同步。

订阅代号检查先于一切类型检查：旧连接、旧会话的迟到事件直接丢弃，返回 `:stale-subscription`。

### 4.1 大对象引用

超限载荷不进入 Sente。服务器发送同一事件类型，但把载荷换成引用：

```clojure
{:type "STATE_SNAPSHOT"
 :epoch 7 :seq 42
 :snapshot-ref {:token "32-hex"
                :digest "sha256-<64-hex>"
                :size 12345
                :encoding "edn"
                :kind :state-snapshot
                :epoch 7 :seq 42}}

{:type "STATE_DELTA"
 :epoch 7 :seq 43
 :delta-ref {... :kind :state-delta :epoch 7 :seq 43}}
```

- 引用中的 `:kind`、`:epoch`、`:seq` 必须与事件一致；token 必须是 32 位
  小写十六进制，digest 必须是规范 `sha256-` 值，size 不得超过客户端上限。
  size 必须是非负整数；超出 JavaScript 安全整数范围的 BigInt 直接判为
  无效引用，校验过程不抛异常。
- `STATE_SNAPSHOT` 引用按快照的纪元/序号规则检查；`STATE_DELTA` 引用按
  增量的规则检查（必须恰为 `seq + 1`）。检查通过后只设置
  `:pending-object`，不改变 `:server`、`:db`、`:epoch`、`:seq`。
- 引用事件与内联载荷互斥；同时出现视为协议错误，快照进入重同步，增量
  进入重同步。
- `:token` 是能力凭据；客户端只从当前事件取得，不从历史或外部输入拼接。

### 4.2 待取期间的事件顺序

`:pending-object` 非空时，除重复引用外的所有状态事件按到达顺序进入
`:deferred-events`，包括完整的 `STATE_SNAPSHOT`。这保证引用边界之前的
事件不会先被应用，也保证引用之后的快照不会吞掉更晚的事件。

`complete-object` 接受 `{:token :digest :size :value :verified?}`：

1. token 必须匹配当前待取对象；不匹配说明结果属于已退休的抓取，直接丢弃
   且不触碰当前对象。
2. `:digest`、`:size` 必须与引用一致，`:verified?` 必须为 true，`:value`
   必须是 map（快照）或序列（增量）；任一不符进入重同步，绝不应用未校验
   数据。
3. 通过后以原事件的 `:epoch`/`:seq` 应用载荷，再按顺序 drain
   `:deferred-events`。遇到新的引用就停止 drain，等待下一次抓取。

`:last-object` 记录最近一次成功应用的 `:kind`、`:token`、`:digest`、
`:size` 与事件时间。抓取失败调用 `object-failed`：清空待取对象与队列，
进入 `:connected-awaiting-sync`，由桥请求新基线。连续失败时桥仍持续请求
新基线，并按指数退避重试，不得永久停在等待同步阶段。

## 5 快照替换与防复活

`STATE_SNAPSHOT` 的 `:snapshot` 必须是一个 map。接受后：

1. `:server` 整体替换为 `:snapshot`；旧顶层键与旧实体不再存在。
2. `:db` 从新树重新归一化，绝不与旧表合并。
3. `:phase` 置 `:synced`，写入 `:epoch`、`:seq`、`:synced-at`。
4. 同一 epoch 的旧序号快照丢弃；更新 epoch 可重置序号；新 epoch 的完整快照就是角色清理的最终依据。

`merge-into-db` 先把 `default-server-keys`、调用方补充键、当前 `:server` 与 `:db` 的所有顶层键从 Fulcro 数据库中移除，再合并新视图。因此被折叠、被 Scope 修剪或接力屏障移除的实体不会因浅 merge 复活；`:ui/*` 本地状态保持不变。
服务器发布的每个顶层表都必须列入 `default-server-keys`（包括 `:slot/by-id`）；
遗漏会让该表在快照不再发布它时继续存活。测试必须断言代表性快照的顶层键集合
是 `default-server-keys` 的子集。

`normalize/to-db` 还会在 `:session/by-id` 存在时丢弃 `:session-id` 已不在可见会话表中的消息，并同步裁剪 `:message/order`。这是多客户端重连或服务器基线短暂携带已删除会话 Owner 对话时的客户端防线；服务器快照过滤仍是主权威。

## 6 纪元、序号与迟到增量

设当前已确认边界为 `(epoch E, seq S)`：

| 增量 | 处理 |
|---|---|
| `epoch < E` | 丢弃，`result :dropped`，`reason :stale-epoch` |
| `epoch > E` | 进入 `:connected-awaiting-sync`，`reason :epoch-changed`；不能把新纪元补丁应用到旧基线 |
| `epoch = E`, `seq <= S` | 丢弃，`reason :stale-sequence` |
| `epoch = E`, `seq = S + 1` | 应用 |
| `epoch = E`, `seq > S + 1` | 进入 `:connected-awaiting-sync`，`reason :delta-gap` |

补丁失败、补丁结果不是 map、归一化失败或事件缺少纪元/序号，均进入 `:connected-awaiting-sync`，保留最后确认的 `:server`、`:db`、`:epoch`、`:seq`、`:synced-at`。在重同步阶段，增量一律 `:dropped`/`:not-synced`，直到新的 `STATE_SNAPSHOT` 到达。
`STATE_SNAPSHOT` 的载荷不是 map 或归一化失败时同样进入
`:connected-awaiting-sync` 并返回 `:resync-required`；不得在旧纪元上继续
宣称 `:synced` 并放行命令。

传输层若主动丢弃积压事件或队列溢出，应调用 `request-resync`；适配器本身不缓冲增量，也不会反向阻塞服务器。

## 7 JSON Patch

`STATE_DELTA` 的 `:delta` 是 RFC 6902 操作序列。适配器支持：

`add`、`remove`、`replace`、`move`、`copy`、`test`。

- 路径是 JSON Pointer；失败返回 `{:ok? false :error {...}}`，不暴露部分应用结果。
- 操作键可为关键字或字符串；`op` 名称大小写不敏感。
- 数组索引拒绝前导零；`-` 只用于 `add` 的末段追加。
- `move` 禁止把值移入自己的后代。
- 新 map 键沿用现有键风格；空 map 默认字符串，以保持归一化表键的字符串实体 ID。查找先精确匹配字符串键，再匹配同名的关键字键。
- 归一化表键本身含 `/`，JSON Pointer 中必须转义为 `~1`：

```text
/session~1by-id/s1/status
/swarm~1by-id/w1/status
/message~1by-id/m1/content
```

不要写 `/session/by-id/s1/status`；那会变成两个路径段 `session` 与 `by-id`，并被拒绝为重同步。

补丁先应用到 `:server`，随后一次性调用 `to-db` 生成 `:db`；组件永远不消费原始 patch。

## 8 归一化

`to-db` 接受两种服务器表示：

- 已归一化表：`:session/by-id`、`:swarm/by-id`、`:task/by-id`、`:action/by-id`、`:message/by-id` 等。
- 紧凑集合：`:sessions`、`:swarms`、`:tasks`、`:actions`、`:messages` 等，按实体 `:id` 建表。

规则：

- 实体 ID 必须是非空字符串，符合跨语言稳定 ID 约定。
- 紧凑集合与已归一化表同时出现、重复 ID、非法表值或消息顺序不覆盖全部消息，均返回错误，调用方进入重同步。
- `:messages` 向量同时生成 `:message/order`；显式 `:message/order` 必须恰好列出 `:message/by-id` 的全部键一次。
- 标量字段原样通过；表键统一为关键字。
- 归一化是无状态的：每次边界都从当前 `:server` 重新计算，不与旧 `:db` 合并。

## 9 `MESSAGES_SNAPSHOT` 角色清理

普通角色（`user`、`assistant`、`tool` 等）总是由快照整体替换。

`activity` 与 `reasoning` 按标准 AG-UI 语义处理：若快照完全未出现某特殊角色，默认是“未声明”，保留原有该角色记录；若快照出现该角色，则该角色的全部旧记录被快照中的该角色记录替换。

项目扩展字段可显式覆盖：

| 字段 | 语义 |
|---|---|
| `:hc/roles` | 本快照完整声明的角色集合；未列入的特殊角色被清除，列入但无消息的角色也清空 |
| `:hc/clear-roles` | 无论是否出现在快照中都要清除的角色；`true` 表示清除全部特殊角色 |
| `:hc/epoch-rebuild?` | 本快照属于新纪元重建；未出现在快照中的特殊角色清除 |
| `:hc/order` | 应用后全部存活消息 ID 的完整顺序；必须恰好覆盖 `:message/by-id` 一次 |

未提供 `:hc/order` 时，快照顺序在前，保留的特殊角色记录按原顺序追加。保留记录保留实体数据；若需要精确穿插位置，服务器必须提供 `:hc/order`。

`MESSAGES_SNAPSHOT` 与 `STATE_DELTA` 一样消耗下一个序号；同一纪元内序号不连续即重同步。新纪元的 `STATE_SNAPSHOT` 会整体替换状态树，因此也是清除特殊角色记录的合法路径。

## 10 与 Fulcro 的装配

```clojure
(require '[hc.client.sync :as sync])

(defn on-agui-event [state event]
  (sync/receive state event))

(defn install-db [fulcro-db state]
  (sync/merge-into-db fulcro-db state))
```

- 发送、停止、接管等依赖最新服务器状态的按钮用 `(sync/command-allowed? state)` 禁用。
- 未决命令按 `command-id` 查询回执是命令层职责；本适配器只提供同步门槛。
- 观察订阅的建立与释放由传输层负责；释放订阅不等于取消服务器任务。

## 11 测试与验证

```bash
clojure -M:test -n hc.client.sync-test
clojure -M:test -n hc.client.sync-feed-test
clojure -M:test -n hc.client.sync-object-test
clojure -M -e "(require '[hc.client.sync :as sync]) (println (sync/initial-state))"

# ClojureScript compile + Node load smoke (output outside the repository).
clojure -M:cljs -co '{:target :nodejs
                      :output-to "/tmp/hc-sync-smoke.js"
                      :output-dir "/tmp/hc-sync-smoke-out"}' \
  -c hc.client.sync
env -C / node /tmp/hc-sync-smoke.js
```

`test/hc/client/sync_test.clj` 覆盖：

- 五阶段转换、重连、订阅代号与命令门槛。
- Feed epoch 与 Owner command epoch 分离；快照 `:sync :command-epoch` 是唯一命令纪元来源。
- 快照整体替换、实体清除、Fulcro 本地键保留。
- 服务器快照顶层键全部属于 `default-server-keys`（含 `:slot/by-id`），
  且快照不再发布槽位表时旧表被移除。
- 不可读快照（非 map 或归一化失败）进入等待同步并请求新基线，不再保持
  `:synced`。
- 纪元推进、迟到增量、重复序号、序号缺口、非法补丁的原子拒绝。
- JSON Patch 六种操作、转义、数组边界、根路径与失败原子性。
- `MESSAGES_SNAPSHOT` 未声明角色保留、显式声明、显式清除、纪元重建、显式顺序与原始消息向量。
- 归一化冲突、重复 ID、非法顺序，以及已删除会话的孤儿消息被裁剪、顺序同步收缩。
- `clojure.test.check` 属性：快照不复活旧实体、迟到增量不改确认状态、已应用增量严格加一、未声明特殊角色保留。

`test/hc/client/sync_feed_test.clj` 用真实 `hc.server.gateway.feed` 做跨层行为冒烟：
基线、增量、新纪元整体替换、重连新订阅、旧订阅迟到事件丢弃、旧订阅代号拒绝，以及
ack 窗口 stale 后 `feed/resync!` 发送新基线并清除 stale。

`test/hc/client/sync_object_test.clj` 覆盖大对象消费者行为：引用不推进
确认边界、校验通过后原子建立基线、token/digest/size/verified/shape
不匹配的重同步、增量引用的延迟队列与顺序 drain、连续引用链、重复引用、
队列溢出、旧 epoch 与序号缺口拒绝、抓取失败与重连清理，以及
`clojure.test.check` 属性：引用 + 校验载荷与内联载荷产生相同的确认状态。

服务端删除防复活由 `hc.server.app.snapshot-deletion-test` 覆盖：已删除会话的
Owner 对话不进入基线、已删除/折叠消息不复活、活动会话已删除时回退到可见会话，
同时保留未打墓碑的 Owner 额外消息。

## 12 非目标

- 不实现 Sente 通道、WebSocket 重连、`command-id` 回执查询或模型/工具事件归约。
- 不实现永久折叠、Scope 修剪或接力屏障本身；本模块只保证服务器推送的新集合不会在客户端复活旧实体。
- 不把大对象、日志或附件塞进实时状态树；它们走独立 HTTP/Blob 通道，状态树只携带内容 ID 与状态。本模块只保存引用与待取队列，不执行 HTTP、摘要或 EDN 解码。
