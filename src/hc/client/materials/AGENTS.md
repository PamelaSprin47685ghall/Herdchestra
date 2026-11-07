# hc.client.materials — 长期记忆、世界书与指令卡

> 权威顺序：根 `AGENTS.md` §7.4 旅程 5.6 / 6.1（记忆与搜索） > `src/AGENTS.md` > `src/hc/client/AGENTS.md` > 本文件。
> 本包只拥有材料工作区的纯模型、React 视图与命令封装；服务器仍是唯一权威。

## 1 责任

1. 投影服务器 `:materials/*` 快照：四类长期记忆、世界书与条目、指令卡、当前会话的指令卡选择与世界书运行计数。
2. 在发送前校验用户输入：记忆 2,000 字、四类与显式范围、世界书触发/计数/位置、指令卡占位符与输出格式。
3. 构造 `:materials/*` 命令信封，保留草稿直到服务器回执；点击本身从不宣布成功。
4. 接受 `:materials/snapshot` 的权威结果。材料写入当前不自动触发网关发布，因此每次接受的写入都追加一条快照命令。

## 2 文件

| 文件 | 责任 |
|---|---|
| `model.cljc` | 快照投影、修订守卫、草稿、校验、命令队列与回执归约 |
| `view.cljc` | 纯视图树；测试可在 JVM 检查文案与可用性 |
| `react.cljs` | 通用节点渲染器与 `MaterialsPanel` |
| `../materials.cljs` | 公共门面：`initial-state`、`apply-snapshot`、`revision`、`begin-session`、`clear-turn-selection`、`turn-selection-matches?`、`flush-outbox!`、`materials-element` |

工作台侧：

| 文件 | 责任 |
|---|---|
| `../workbench/materials_bridge.cljs` | `:ui/materials`、传输、回执、启动快照与陈旧事务守卫 |
| `../workbench/views/materials.cljs` | 八分区页签：文件、产物、记忆/世界书/指令卡、受管浏览器、扩展、小应用 |

相邻面板由各自模块拥有：`hc.client.files` 提供工作区文件浏览器，
`hc.client.artifacts` 提供产物卡，`hc.client.browser` 提供受管浏览器，
`hc.client.extensions` 提供 MCP/技能，`hc.client.miniapps` 提供独立存储的小应用。
材料页只负责页签与挂载，不复制这些模块的业务状态。

## 3 快照与修订

服务器快照顶层键：

```text
:materials/ready? :materials/error :materials/revision :materials/settings
:materials/session :memory/by-id :memory/order :memory/counts
:worldbook/by-id :worldbook-entry/by-id :worldbook-session
:instruction-card/by-id :instruction-session
```

`apply-snapshot` 只替换服务器派生字段，保留本地草稿、命令、页签、筛选与传输状态。若传入的
`:materials/revision` 小于当前修订，整个快照被忽略；工作台还会拒绝用较旧修订覆盖较新的
`:ui/materials`。这条守卫同时覆盖迟到的网关基线和命令快照的竞态。

网关启动基线可能早于材料写入。工作台在首次进入 `:synced` 后发送一次
`:materials/snapshot`，并在每次接受的材料写入后再次发送。命令发送在 Sente 接收回调之外
执行；回执也在后续任务中归约，避免 Fulcro 事务尚未记录命令时丢失回执。

## 4 命令合同

客户端只使用服务器已有的命令。服务器 kind 含多个 `/`，ClojureScript 不能写成关键字字面量，
因此在 `model.cljc` 用 `(keyword "materials" "…")` 构造。

| 动作 | 服务器 kind | 关键载荷 |
|---|---|---|
| 记忆开关 | `materials/settings/update` | `:memory-read?`、`:memory-suggest?` |
| 手动保存 | `materials/memory/remember` | `:body :category :scope :epistemic :always?` |
| 批准/拒绝 | `materials/memory/approve` / `reject` | `:memory-id`、`:changes` / `:reason` |
| 替换/归档/恢复/删除 | `materials/memory/{replace,archive,restore,delete}` | `:memory-id` 与内容字段 |
| 世界书 | `materials/worldbook/{create,update,bind,unbind,delete}` | `:book-id`、`:assistant-id` |
| 条目 | `materials/worldbook/entry/{create,update,move,delete}` | `:entry-id`、`:book-id`、`:entry-ids` |
| 指令卡 | `materials/instruction/{create,update,delete}` | `:card-id` 与卡片字段 |
| 会话选择 | `materials/instruction/session/{set,clear}` | `:card-ids` 向量，顺序即选择顺序 |
| 权威读取 | `materials/snapshot` | 无 |

同一逻辑目标存在未确认命令时，新的写入被拒绝，避免双击产生重复记录。
`materials/snapshot` 例外：可并发读取。

每个命令载荷都带当前材料的 `:session-id`，并使用该会话 Owner 的
`:command-epoch`；app epoch 绝不给材料命令兜底。服务器按
`[session-id command-id]` 持久去重，回执查询也必须带同一个 `:session-id`。
桥从同一份已确认客户端 DB 的 `:session/by-id/<sid>/:command-epoch` 读取
（兼容字符串键）；该值缺失时材料门保持关闭，不用全局 `:sync/:command-epoch`
冒充会话纪元。

发送路径同时把本轮选择附加到 `:send-message` 载荷：

```text
:turn-card-ids ["card-id" ...]
:instruction-values {"placeholder-key" "value"}
```

`workbench.views.session` 在提交前调用 `hc.client.materials/attach-turn-selection`。
当前路由不调用 `materials-session/stage-turn-from-send!`；显式
`:turn-card-ids`/`:instruction-values` 随 `:send-message` 载荷进入本轮请求，并由
`app/system` 的 `material-injection` 直接读取。客户端不再自动发送 `turn/stage`
命令；本轮选择只存在于本地状态和本次发送载荷中。发送回执被接受后，
`materials_bridge` 按已选会话比较提交时的 `:turn-selection-version`（旧命令回退
比较 card ids 与 instruction values）；只有当前选择仍是提交时的选择才清除，用户在
等待回执期间新选或改写的选择会保留。

## 5 诚实边界

1. **仅本轮指令卡默认生效**：选择只写入本地 `:ui/materials`，发送载荷携带显式字段。
   发送成功后自动清除；同一会话的下一次无卡发送不会复用上一轮。占位符值只随本轮提交，
   不进入会话级持久选择，也不写入服务端暂存。切换会话会清空本地本轮选择；服务端按
   `:session-id` 隔离会话卡选择与暂存选择。切换会话同时把应用方式复位为“仅本轮”。
   格式冲突只比较启用卡；发送路径必须在提交前检查冲突和必填占位符，不能只依赖材料页签
   的提示。
2. **世界书运行计数只显示快照内容**：快照提供 `:last-turn`/`:omitted` 时，材料页显示
   有界“命中但未提供”列表（条目、原因、字符数）；没有该键时明确写“服务器未报告”，
   不编造列表。
3. **临时会话只读是一次性的**：临时会话默认不读不写；材料页提供“仅本次读取已保存记忆”
   开关，显示会话/助手/项目范围，发送载荷携带 `:temporary-read? true`，发送成功或切换
   会话后复位。所有写入口仍由服务器拒绝。
4. **本地筛选不是服务器搜索**：记忆查询只过滤已确认快照，并标注“本地筛选”。
5. **不伪造能力**：技能、联网搜索、MCP 与设备连接由真实服务器快照与命令面驱动；未提供的
   连接或工具显示服务器返回的不可用原因。

## 6 测试

```bash
clojure -M:test -n hc.client.materials.model-test
clojure -M:test -n hc.client.workbench.materials-bridge-test
```

覆盖：快照投影与陈旧修订、四类/2,000 字/范围校验、世界书关键词与表达式/计数、指令卡占位符
与格式冲突、本轮选择附加到发送快照、提交版本匹配保护（新选择不被旧回执清除）、切换会话
模式时不发送本轮选择、命令队列去重、接受写入只追加一条快照、拒绝回执触发对账快照、
发送失败不宣布成功、临时会话一次性只读范围、世界书“命中但未提供”投影与无数据文案、
候选拒绝原因、位置深度非法值、切换会话复位应用方式、停用卡不参与格式冲突、纯视图树包含
真实分区与两种应用方式。`materials-bridge-test` 直接按
桥的调用方式检查公共门面：`begin-session` 接收状态与会话 ID，清空服务器派生表、递增本轮
选择版本，并保留命令队列。门面漏声明该函数会先在这里失败，而不是等到 release 构建。

### 2026-11-03 实测

真实 Sente 服务器（`127.0.0.1:7699`，临时数据目录）与真实 Chromium：

1. 从空材料库创建生效记忆、世界书、关键词条目并绑定助手、创建指令卡并持久化会话选择；
   服务端修订从 `0` 递增到 `18`，每次写入后的权威快照都回到界面。
2. 重新加载页面后，三条记忆、一本世界书、一条条目、两张指令卡与会话选择全部从
   `:materials/snapshot` 恢复；启动基线仍停留在旧修订时，修订守卫没有让旧值覆盖新值。
3. 同时选择 `text` 与 `json` 指令卡时显示格式冲突警告；390 px 宽视口无水平溢出。
4. 材料面板无控制台错误或 React 警告。
5. 空库复测：创建一条记忆后修订 `1`，切换读取开关后修订 `2`，创建并选择指令卡后修订
   `4`；开关与选择只在权威快照返回后变化，未出现乐观假状态。
6. 本轮指令卡真实发送：带必填占位符的卡片选择后随 `:send-message` 显式载荷发送；发送用户
   消息后本地选择清空，助手返回 `Per-turn OK`。本地 OpenAI 兼容 SSE 端点收到的首个模型
   请求包含 `system: Instructions:\n回答前说 你好。`，证明占位符值已进入本轮上下文。
7. 真实 Sente + Chromium 的八分区：文件浏览器、产物、记忆/世界书/指令卡、浏览器、扩展、
   小应用页签均可打开；浏览器页显示“受管浏览器”，扩展页显示 MCP/技能表单，无跨面板
   控制台错误。小应用面板曾停在“正在读取小应用列表…”；根因是 React setter 包装丢失
   额外参数，`mini-apps` 已修复并加入 6 秒超时。devops 复测确认列表已落定、八个页签与
   浏览器/扩展/小应用面板均渲染。

**未运行**：屏幕阅读器、真实供应商网络、多卡片格式冲突的端到端模型请求、小应用文件
打开与数据持久化的完整浏览器流程（等待 devops 最终冒烟）。
