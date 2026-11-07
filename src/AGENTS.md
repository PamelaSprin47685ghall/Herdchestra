# src/AGENTS.md — 全局架构师笔记

> 权威顺序：根 `AGENTS.md` > 本文件 > 各包 `AGENTS.md` > 代码注释。
> 本文件承担传统 `ARCHITECTURE.md` 职责，只解释模块边界与装配方式，不新增协议义务。
> 根规格更新后，先同步本文件，再同步相关包文档与 `HACK.md`。最后同步：2026-11-05（根 §3、§4、§5、§7.3、§7.4、§8.1）。

## 1 依赖方向

系统采用扁平、职责完整的模块划分。底层支持库互不依赖，组合层显式装配。

```text
hc.core.*       独立支持库；彼此不 require，不依赖 compose/server/client
hc.compose.*    组合层；可以 require hc.core.*，不 require server/client
hc.server.*     服务端适配；可以 require hc.core.* 与 hc.compose.*
hc.client.*     浏览器端；只依赖共享协议数据与 CLJC，不 require JVM server
```

强制规则：

1. `hc.core/*` 模块之间不得互相 require。共享关键字与数据结构用普通 map、vector、set、keyword 表达；不新增“公共 core 依赖”来绕过独立性。
2. `hc.compose` 是唯一显式装配层。它构造 system map，启动与停止组件，并持有 Owner 邮箱、监督树与取消域。业务模块不建立全局单例。
3. `hc.server` 只做适配：存储、模型、执行器、网关、配置、恢复。它不替看板决定任务状态，也不在 Owner 内阻塞等待 I/O。
4. `hc.client` 只持有服务器状态的显示副本、本地 UI 状态、未提交草稿与命令跟踪表。它不伪造领域终态。
5. 任何包不得从 `src/hc/client` require JVM 专属命名空间，也不得从 `src/hc/server` require 浏览器专属命名空间。

## 2 状态与副作用边界

1. **控制状态**：任务、动作、个体、令牌槽、任期、台账。短 STM 事务提交；派生索引与权威事实同事务更新。
2. **文件现场**：只以 `(mtime-sec, mtime-nsec, size)` 的 `stamp` 作为快速判据；不使用 `atime`，不增加隐藏内容哈希或文件代数。普通受保护提交与 Shell 共用写入口 `$W$`。
3. **个体上下文**：可以随逻辑死亡完全丢弃。公共接续材料必须已写入看板或产物区，不依赖前世私有记忆。
4. **单 Owner**：每个会话与每个看板协调域只有一个串行状态入口。模型流、工具结果、计时器与取消信号都作为消息投回 Owner；Owner 内不做长耗时 I/O。
5. **禁止副作用重放**：文件写入、Shell、外部调用不得放在可重试的 STM 闭包内。记账冲突只重试纯控制状态更新。
6. **父项实质**：子项全部完成只把父项从 `:waiting` 恢复为 `:ready`；绝不自动改为 `:done`，也不沿祖先链级联完成。
7. **结果分类**：普通拒绝、已完成应用、仍在等待的动作、硬失败后的不确定现场必须分开表达；不用单一布尔值 `success` 混淆。

## 3 模块清单

### 3.1 `hc/core`

| 目录 | 当前/计划命名空间 | 责任 | 根规格 |
|---|---|---|---|
| `ufss/` | `hc.core.ufss.*` | EDN + 通用变长围栏字符串读取器与 Pretty Printer；双射不变量 | §6.3、§8.2 |
| `scope/` | `hc.core.scope.*` | Scope 准入、回合结算、出站副本裁剪 | §6.2 |
| `pair_hint/` | `hc.core.pair-hint.*` | 双语资源、前缀链摘要、合成提示注入 | §6.2、§8.2 |
| `relay/` | `hc.core.relay.*` | 义务台账、中心化评估窗口、原子接力屏障 | §2.4、§3.3、§8.2 |
| `swarm_stm/` | `hc.core.swarm-stm.*` | 看板 STM、拆分/认领/父项恢复、take-or-die 内核 | §3.1–§3.7 |
| `workspace_gate/` | `hc.core.workspace-gate.*` | `stamp` 凭据、批内内存视图、dry-run、写入口 `$W$` | §4.2 |
| `steward_sphinx/` | `hc.core.steward-sphinx.*` | Standing Sphinx 认识状态与 Steward 决策前沿触发器 | §2、§8.2 |
| `text_location/` | `hc.core.text-location.*` | UTF-16 精确 `where` / `whereAll` 与歧义诊断 | §4.3 |

每个模块拥有自己的 `AGENTS.md`，说明局部接口、状态转换、拒绝码与属性测试。模块文档可以细化实现选择，但不得改变根规格的协议语义。

### 3.2 `hc/compose`

当前命名空间：`hc.compose.guards.*`、`hc.compose.cancel.*`、`hc.compose.nudge.*`、`hc.compose.reports.*`、`hc.compose.retry.*`、`hc.compose.fleet.*`、`hc.compose.mission.*`、`hc.compose.owner.*`。计划补充 `hc.compose.supervisor`。职责与禁区见 `src/hc/compose/AGENTS.md`，Owner 细节见 `src/hc/compose/owner/AGENTS.md`。

### 3.3 `hc/server`

当前命名空间：`hc.server.artifacts.*`、`hc.server.blob-store.*`、`hc.server.browser.*`、`hc.server.backup.*`、`hc.server.commands.*`、`hc.server.documents.*`、`hc.server.gateway.*`、`hc.server.files.*`（只读工作区文件 API 与浏览器读取面）、`hc.server.history.*`（含 `history.context` 的阅读/发送双投影）、`hc.server.model-port.*`、`hc.server.program.*`、`hc.server.scheduler.*`、`hc.server.search.*`、`hc.server.speech.*`、`hc.server.storage.*`、`hc.server.recovery.*`、`hc.server.materials.memory-suggest`。计划补充 `hc.server.config`、`hc.server.adapters`。职责与禁区见 `src/hc/server/AGENTS.md`；搜索与读取边界见 `src/hc/server/search/AGENTS.md`；产物提交边界见 `src/hc/server/artifacts/AGENTS.md`；文档提取与 OCR 边界见 `src/hc/server/documents/AGENTS.md`。

### 3.4 `hc/client`

当前命名空间：`hc.client.sync.*`（`adapter`、`connection`、`json-patch`、`messages`、`normalize`、`object`）、`hc.client.artifacts.*`（产物卡片、来源披露与打开/下载动作）、`hc.client.copy.*`（24 类失败文案单一事实来源）、`hc.client.conversation.*`（含 `export-plan` 的导出选择/取消/长图计划与 `minimap` 话题概览）、`hc.client.files.*`（工作区文件浏览器、stamp 展示与差异）、`hc.client.materials.*`（长期记忆、世界书、指令卡模型与视图）、`hc.client.search.*`（联网搜索三态控件、服务选择与来源卡）、`hc.client.swarm.*`（`model`、`ui`）与 `hc.client.sessions.*`（会话元数据控件、真实命令回执与已删除列表）。职责与禁区见 `src/hc/client/AGENTS.md`。

## 4 装配与启动顺序

1. 解析配置并打开存储；存储打不开时停止派发副作用工作，提供恢复入口，绝不静默新建空库。
2. 构造 Owner 邮箱、看板、写入口、模型端口与执行器；先完成恢复扫描，再开放新工作。
3. 启动监督树与 Sente/AG-UI 网关；客户端连接后先取基线快照与同步序号，再查询未决命令回执。
4. 关闭时先停接新工作，结清或交接未结责任，排空 `:applying` 与活动 Shell 会话，再关闭存储。
5. 外部服务（Ollama、浏览器、桌面应用）只连接，不拥有其生命周期；任务退出不得擅自关闭用户共享服务。

## 5 测试与验证

1. 每个 `hc.core/*` 与 `hc.compose/*` 模块必须有 `clojure.test.check` 属性测试。随机操作序列与故障注入都要覆盖；发现反例后用 shrinker 沉淀为固定确定性场景。
2. 测试分三层：控制状态转换、文件入口、适配与消息。文件测试只用测试自建临时工作区，完成后清理，绝不触碰用户项目。
3. 验证标签必须诚实：`V01–V08`、`F01–F15`、`T01–T13`、`E2E 01–22` 未运行就标 `NOT RUN`，不得用“设计上成立”替代运行证据。
4. `deps.edn` 的 `:test` 别名已包含 `org.clojure/test.check` 与 test-runner。属性测试写成普通 `clojure.test` 命名空间即可被发现。`:test` 同时提供 `-M` 与 `-X` 入口：

```bash
clojure -X:test
clojure -M:test -n hc.core.pair-hint-test
```

常用命令：

```bash
clojure -P -M:dev:test:compose:server:client:cljs
clojure -X:test
clojure -M:test -n hc.core.pair-hint-test
clojure -X:test:compose
clojure -X:test:server
clojure -M:dev -e "(require 'clojure.test.check)"
pixi run test
```

`-X:test` runs through `hc.test-runner/test`: after the suite it stops
Datalevin's process-global scheduler/async/worker pools and `shutdown-agents`,
so a leaked or failed store open cannot keep a non-daemon thread alive after
the summary.  `-M:test` keeps the standard Cognitect runner, which exits
explicitly.

`resources/` 已在 `:paths` 中，双语提示资源随 classpath 加载。Pixi 环境实际供应 JDK 21.0.10 与 Clojure 1.12.6.1673；宿主 JDK 26 只用于快速验证，交付验证以 Pixi 为准。

## 6 文档与变更纪律

1. 根目录不设 `LEMMA.md` 或 `README.md`。外部库、平台与运行时限制、源码行号、测量数据与复检步骤写入根 `HACK.md`。
2. 新增模块时同时新增该模块的 `AGENTS.md`；修改公共接口、状态关键字、拒绝码或锁范围时，同步更新相关包文档与根规格允许的落点。
3. 变更前先读 `AGENTS.md` §8.4 的维护者检查表。新增文件操作、调整锁粒度、修改任务结构、令牌接口、异常处理、唤醒机制或运行时身份，都要重新核对对应不变量。
4. 不删除非本回合自己创建并逐一具名的共享路径。清理操作独立执行，不在复合 Shell 命令中夹带 `rm -rf`。

## 7 本轮新增模块的同步检查

| 模块 | 契约 | 失败模式 | 复检 |
|---|---|---|---|
| `hc.client.copy.failures` | 24 类失败文案单一来源；认证/权限/模型三口径分开；`已保留` 仅在 `:saved? true`；未知用量不写 0 | 三类混用、未保存却称已保留、未知显示 0、动作只有含糊重试 | `clojure -X:test:client :nses '[hc.client.copy.failures-test]'` |
| `hc.client.conversation.export-plan` | 选择单条/多选/分支、默认当前版本、可选全部版本；取消状态机；长图分段；重复 id 显式拒绝 | 导出未选版本/工具日志、`:writing` 取消写成已取消、单条超高静默截断、重复 id 产生重复段 | `clojure -X:test:client :nses '[hc.client.conversation.export-test]'` |
| `hc.client.conversation.minimap` | 话题概览、类型密度、稳定锚点、截断仍可 locate；重复 id 保留第一条 | 空列表崩溃、锚点随追加漂移、重复 id 覆盖定位 | `clojure -X:test:client :nses '[hc.client.conversation.minimap-test]'` |
| `hc.server` 默认会话/恢复投影 | 默认会话传 `:config`；恢复连接重新投影；调度器冻结模型；merge restart 回执不伪造 | 首屏未绑定、恢复连接被旧 launch 标记隐藏、既有 run 被新设置替换、restart 失败报 complete | `clojure -X:test:server :nses '[hc.server.app.system-test hc.server.backup.maintenance-test hc.server.scheduler-test]'` |
