// `src/host-cline/` 包级规格说明书（AGENTS.md §8.1）。
// 体例参照根 AGENTS.md，本文件允许 AI 创建或修改。根规格更新后，这里与代码必须同步。

# Cline 宿主适配器（host-cline）

## 1 职责

把根规格的语义接到 Cline Desktop / CLI / SDK 的插件接口上。不修改宿主源码，只以插件身份装载。

- 只注册：`js-manager`、`js-worker`、`js-plan`、`js-review`、`fork`、`join` 六个工具；两条规则；七个钩子。
- `js-review` 只属 Manager，且只在评估窗口开着时可用（根 §2.3）。窗口是 tenure 的 `ReviewWindow`：
  `beforeModel` 每个请求 `beginRound()` 开一轮，`reviewFor` 把受理的回执交给 `noteReview`，本轮**第一次被接受**的 review 关窗。
  `beforeTool`、`js-review` 工具的 `execute`、`session.reviewSurface()` 三处都读同一个窗口；关着即 `REVIEW_WINDOW_CLOSED`，
  worker 不论窗口状态都按角色被拒。
- 不注册命令、provider、MCP；不使用宿主的 `team_*`、`spawn_agent` 作为 `fork`。
- 程序面、成员表、fission、review 台账、提示词全部取自 `src/compose` 与 `src/core`，本目录不复制一份。
- 本目录只做一件事：把宿主的真实接口映射成 compose 的端口。

**每个工具名只注册一次。** 宿主按 `tool.name` 去重（`session-runtime-orchestrator.ts`
把贡献工具放进以名字为键的 Map，后注册者覆盖先注册者）。`js-worker` 不按角色各注册一份。
角色的判定顺序：

1. 会话的角色是**会话的事实**，不是插件模块的常量：`setup` 收到的是 `sessionId` 与 `cwd`，
   没有角色。本适配器启动的会话把角色写进 session metadata（`herdchestra.role`），插件用自己的
   `getSession` 读回（`identity.mjs`）；读不到时按 Manager（操作员自己的会话）。
2. 单次调用里，顶层调用者是 Manager，除非本会话的角色是 Plan（`roles.mjs` 的 `callRole`）；
   子代理调用查 `fork` 记下的身份表（角色来自 brief 的显式声明，绝不按名字推断）。

顶层会话即便调用 `js-worker` 也拿到 worker 面：拒绝只能由 `hooks.beforeTool` 做，
因为宿主没有按角色收窄工具面的接口（根 §7.2「两层分离」）。

## 2 目录

| 路径 | 职责 |
|---|---|
| `index.js` | 宿主唯一接受的入口扩展名；只从 `index.mjs` 重新导出 |
| `index.mjs` | 插件对象：`name`、`manifest`、`hooks`、`setup(api, ctx)`（注册 `onDispose` 收尾）、`openPlan(text, {root, name, model})` |
| `adapter.mjs` | 工具与规则的注册、一次调用到一台程序的转换、会话角色解析 |
| `scope-schema.mjs` | 每个非豁免工具的 `scope` 参数模式（§6.2 注解的 JSON Schema 形态） |
| `session.mjs` | 一个会话的全部状态：程序表、park 表、worker 表、任期、端口、角色采纳、`release()` 收尾 |
| `hooks.mjs` | 七个钩子、面门禁、`review` 的会话封装 |
| `roles.mjs` | 角色名、面门禁表、`callRole`（唯一一份角色常量） |
| `identity.mjs` | 会话角色：`ROLE_METADATA_KEY`、`roleOf`（metadata → 记录；无答案即 `unproven`） |
| `role-fact.mjs` | 启动前的会话身份：`sessionIdFor`、`writeRoleFact`、`readRoleFact`（§2.3） |
| `plan-entry.mjs` | **Plan 的唯一入口** `startPlan({ host, text })` |
| `plan.mjs` | 底稿读取与 Plan 的两个端口（`draft`、`devops` 由 `devops.mjs` 给） |
| `plugin-session.mjs` | 会话请求的 `pluginPaths` / `toolPolicies` / `plugins`（§2.1） |
| `rewrite.mjs` | 唯一的请求改写入口（根 §7.2）：`requestRewrite` 就是 `beforeModel` 钩子答案里的 `messages`（`hooks.mjs`），`systemPromptRule`/`rolePrompt` 把角色提示词做成规则；文件头逐行记录了宿主为何如此（`HACK.md` 有实测） |
| `drafts.mjs` | 底稿路径的唯一定义 |
| `devops.mjs` | Plan 的单次 devops 运行时（`createDevopsRunner`） |
| `handoff.mjs` | §2.2 交接步骤 `planHandoff`（半开 Manager 的回收） |
| `sessions.mjs` | 本适配器启动会话的工厂：`ManagerSessions`（`openPlan`、`startManager`、`stopManager`） |
| `entry-path.mjs` | 新会话要加载的插件入口路径（`index.js`） |
| `ports.mjs` | `command`、`relay`、`fission`（含组操作）三个端口 |
| `execution.mjs` | 真实 JS 执行（`AsyncFunction`）、park 表、一次调用的结局 |
| `workers.mjs` | `fork` / `join` / steer / fission lane 的 worker 表；按回合记账的 `join`；持久花名册（`.workers.jsonl` + `.workers.restored.json`，`restoreRoster` / `restoredRoster`）；`abortAll()` 是会话结束时的收尾 |
| `runtime.mjs` | 一个 `createDelegatedAgent` 运行时的句柄（首个 brief、steer、abort） |
| `tenure.mjs` | Manager / Plan 任期：台账、接力栅栏、Scope 与 Pair Hint 书 |
| `request.mjs` | Cline 消息 → Scope item 与 Pair Hint 消息的映射 |
| `facts.mjs` / `files.mjs` | 会话 id、工作区、语言阶梯；`.Herdchestra/` 的读写 |
| `host-modules.mjs` | 运行时解析 `@cline/core`：`CLINE_CORE_MODULE` → 裸模块名 → `argv`/`execPath`/`cwd`/插件目录向上 → 已知安装布局 → `../cline` 源码树；异步、带证据来源（`hostModuleSource()`），拿不到就如实报告 |
| `operator.mjs` | `ask` 的提问半边：park + `interrupt: true` 的回执，以及**能力可注入**的 seam（直答通道 / 审批往返 / 只给嵌入方的 `request`）。**没有「无通道」拒绝** |
| `execution.mjs` | 程序执行：`ParkTable` 持有挂起点的 promise、**续体**（`bodyPromise`）与下一次挂起信号；`ProgramCall#run` 记住 body 的 promise，`ParkTable.resume` 唤醒后与下一次挂起赛跑，所以 `this.unpark` 回答的是续体的真实结局 |
| `guard.mjs` | 无人值守出口判定在本宿主的落地（根 §2.2）：`turnExit`（`afterModel` 记录自然停止尝试）、`requestNotice`（`beforeModel` 的 `appendContext` 把 Nudge 送进下一次请求）、`runExit`（`afterRun` 用 `steer_message` 真续跑）。操作员按停（`status` 为 cancelled/aborted/interrupted，或 aborted 的 assistant 消息）判 `operator-interrupt`，绝不 nudge |

## 2.1 会话请求：加载与可见性（`plugin-session.mjs`）

插件能不能到达模型，由宿主的两件独立事实决定，源码各一处：

| 事实 | 宿主字段 | 依据 |
|---|---|---|
| 插件要**被加载** | **`localRuntime.pluginPaths`** | `services/local-runtime-bootstrap.ts:463` 交给 `resolveAndLoadAgentPlugins` 的是 `localConfig?.pluginPaths`，而 `localConfig` 由 `localRuntime` 展开而来（`:356-360`）。写在 `config.pluginPaths` 里**不加载任何插件**（真机实测：Plan 会话跑起来了，但系统提示里没有面、也没有 herdchestra 规则）；`config.agentPluginPaths` 只喂 settings 里的**包**加载器（`:517`） |
| 工具要**可见** | `toolPolicies` | 加载后的插件工具进入扩展列表（`:552`），再由 `runtime/orchestration/runtime-builder.ts:85-99` 按 `toolPolicies` 过滤；`enabled: false` 即从请求里消失 |

因此 `pluginSessionConfig({ entry, role })` 给出三项：`pluginPaths: [entry]`、`toolPolicies`
（`"*": { enabled: false }` 加每个要用的面 `{ enabled: true }`）、以及 `plugins`（宿主
`StartSessionInput.plugins` 的选择表）。**三者的落点不同**：`pluginPaths` 进 `localRuntime`，
`toolPolicies` 进 `config`，`plugins` 进请求顶层；`localRuntime.configExtensions` 还要含
`rules`/`skills`/`plugins`，否则插件机制根本不会被走到。`startPlan` 与 Plan 交接出的 Manager 都遵守这条；
两处都不再设置 `agentPluginPaths`，也不在 `config` 里放 `pluginPaths`。

**`configExtensions` 必须是数组，不能是对象。** 宿主类型是 `RuntimeConfigExtensionKind[]`
（`@cline/shared` `hub.d.ts:369`，`session/runtime-config.d.ts:3`：
`"rules" | "skills" | "workflows" | "plugins"`），读法是 `new Set(extensions).has(kind)`
（`runtime-config` 的 `hasRuntimeConfigExtension`）。对象形式（`{ rules: true, … }`）在宿主内部抛
`object is not iterable`——`@cline/core` 0.2.0 真机实测。唯一的来源是
`plugin-session.mjs` 的 `CONFIG_EXTENSIONS`，`startPlan` 与 `startManager` 都从
`pluginSessionConfig(...)` 取，不在别处重写。

可见集合按角色收窄：Plan 只有 `js-plan`（§3.1：Plan 没有 `js-review`——评审是 Manager 的阶段）；
Manager 只有 `js-manager`、`js-review`、`fork`、`join`。`role` 只认 `plan` 与 `manager`，没有缺省值、
没有回落：未知角色回 `UNKNOWN_SESSION_ROLE`，而不是悄悄拿到 Manager 的面。
这不是执行授权——`hooks.beforeTool` 仍是唯一闸门（根 §7.2「可见集合不是执行授权」）。

### 2.2 为什么一个会话可以 `ok: true` 却没有回合

`startPlan` 的回执只说明**会话记录建好了**。宿主的 `startResolvedSession` 在写完会话记录之后才执行首个
prompt 的回合（`local-runtime-host.ts:1055` `executeTurn(active, { prompt })`），所以下面任一步都能让
「会话存在、`systemPrompt` 与 `messages` 都为空」：

- 插件加载/设置阶段的钩子抛错——`beforeRun` 是最早的钩子，它的拒绝会中止整次 start，而
  `ClineCore.start` 的返回值里没有 `pluginIssues`（宿主不传），回执因此看不到原因；
- provider 配置解析失败（隔离 `CLINE_DIR` 里没有该 provider 的凭据时）；
- 工作区解析失败。

回执**不**声称回合发生过：`startPlan` 返回 `{ ok, sessionId, draft }`，只有会话 id。要判断「回合真的跑了」，
看磁盘：`<CLINE_DIR>/data/sessions/<id>/<id>.messages.json` 里出现 `system_prompt` 与 `messages`。
文档在此明确区分这两件事，避免把「会话已建」读成「插件已到达模型」。

宿主的失败也可能以**回答**而不是抛错出现（`{ ok: false, name: "ZodError", issues: [...] }`，例如 manifest
schema 拒绝）。`start-result.mjs` 的 `refusalFromStart` 按宿主的三种形状读取：`ok: false` → `START_REFUSED`
（带 `issues`）、无 `sessionId` → `START_RETURNED_NO_SESSION`、没有回答 → `START_RETURNED_NOTHING`；
两者都把回执变成 `PLAN_SESSION_FAILED` / `MANAGER_SESSION_FAILED`，不再把「没建成」报成成功；
宿主更细的原因放在回执的 `detail`（`START_REFUSED` / `START_RETURNED_NO_SESSION` / `START_RETURNED_NOTHING`），
`issues` 原样保留。

### 2.3 会话身份的原子确定（`role-fact.mjs`）

会话角色是**本适配器在启动前就确定的事实**，不是插件加载时的常量，也不是事后补写的文件：

- **身份两半都在 `start` 之前定**：`startPlan` / `startManager` 先用 `sessionIdFor(role, name)`
  生成会话 id（`<role>-<name>_<毫秒>_<5 位 base36>`，与宿主 `createSessionId` 同形，
  见 `@cline/shared` 的 `ex`），再用 `writeRoleFact` 把
  `{ v: 1, sessionId, role, draft? }` 写到 `.Herdchestra/<sessionId>.role.json`，最后才把
  `config.sessionId` 放进启动请求。宿主对调用方给的 id 原样使用
  （`startSession`：`e.config.sessionId?.trim() || createSessionId()`；hub `session.create`：
  `sessionConfig.sessionId || createSessionId()`），并在 `kO` 里把 `session.sessionId` 交给插件的
  `setup`。插件在**会话注册之前**就被 setup（`kO` 早于 `startResolvedSession` 的 `sessions.set`），
  所以「事后按返回的 id 再写一份」在第一次钩子之前根本来不及——这就是必须原子确定的原因；
- **为什么不能问宿主**：插件加载器从不转交 `sessionMetadata`（`Us` 的 `setupContext` 与沙箱的
  `Ls({...})` 都不带），`sessionOrigin` 只进遥测，`readSessionMetadata` 只给 checkpoint 钩子；
  而沙箱模式（`CLINE_PLUGIN_MODE=sandbox`）下插件是独立子进程，插件→宿主的回调无法往返。
  文件是唯一同时满足「setup 时已存在」与「沙箱可读」的通道；
- **判定顺序**（`SessionState#resolveRole`）：`ctx.sessionMetadata` → **磁盘角色事实**（按
  `sessionIdOf(ctx)`，即宿主交给 setup 的那个 id）→ 宿主会话记录（`getSession` / `get`，两种调用形状
  都接受）→ 默认 Manager。真实宿主从不把 metadata 交给 `setup`，所以线上走第二、三、四条；
- **读不到角色不等于 Manager**：`roleOf` 的 `source` 区分「宿主回答了、只是没标记」（`unmarked` /
  `sessionRecord`）与「问了但没有答案」（`unproven`）。`unproven` 时 `hooks.beforeTool` 拒绝一切面
  （`SESSION_ROLE_UNKNOWN`），绝不按默认发权限；
- **写不成就不启动**：角色事实写失败时 `startPlan` / `startManager` 回具名拒绝
  （`ROLE_FACT_UNWRITABLE` / `SESSION_ID_MISSING` / `UNKNOWN_ROLE`），不启动会话；宿主若没用给定的 id
  启动（`SESSION_ID_CHANGED`），回执如实报告并停掉半开会话，不补写第二份文件；
- 文件只接受本适配器可标记的四个角色；缺失、损坏、版本不符、`sessionId` 不符或未知角色一律当没有，
  交回后一级判定。`adoptRole` 对未知名抛 `UNKNOWN_SESSION_ROLE`，不会在猜出来的角色上开台账；
- 读记录的两个方法名是同一个事实：`RuntimeHost.getSession(id)` 与 `ClineCore.get(id)`（别名，
  部分构建只暴露后者）；**只认 `getSession` 会让真实 Plan 会话落到无答案并按 Manager 处理**；
- **每个钩子都必须先 `await session.resolveRole()`**，`beforeTool` 也不例外：少这一步时 Plan 会话
  在第一次工具调用时仍是未解析状态，`callRole` 把它读成 Manager，`js-plan` 就被 Manager 面拒掉。
- **顶层还是子代理，看 `snapshot.parentAgentId` 的值，不看键在不在。** 该字段在
  `@cline/shared` 的 `AgentRuntimeStateSnapshot` 里是可选的（`parentAgentId?: string | null`），
  `@cline/core` 0.2.0 的顶层 agent 实测**整个键都不出现**（键集只有
  `agentId, conversationId, runId, status, iteration, messages, pendingToolCalls, usage`）。
  `"parentAgentId" in snapshot` 会把顶层 Manager 读成 coder，它自己的 `js-manager` 因此被拒
  （真机 trace：`{tool: "js-manager", role: "coder", declaredRole: "manager"}`）。
  `roleFor` 只判值：缺键、`null`、`undefined` 三者同义——没有父代理。没有 `snapshot` 时仍是 worker 读法。

### 2.3.0 操作员自开的会话：用快照证明身份（`hooks.mjs` `operatorRoleFor`）

Cline Desktop 里操作员自己新建的会话既没有角色事实，`setup` 又早于会话注册（`getSession` 答不出来），
于是四个来源全空、`identity.source === "unproven"`。旧实现此时拒绝一切面（`SESSION_ROLE_UNKNOWN`），
表现成「插件装了但没生效」。能证明身份的**唯一**证据是宿主每次调用都带的快照：

- **顶层判据**：`snapshot` 存在且 `parentAgentId` 的值是 `null`/`undefined`/缺键（`topLevelProven`）。
  与 `roleFor` 同一条读法，判值不判键。
- **角色**：顶层调用按 Manager 受理；调用的是 `js-plan` 时按 Plan 受理（`js-plan` 是 Plan 自己的入口，
  `callRole` 会给顶层 `js-plan` 发 Plan 的角色）。其它面随后仍由 §2.4 的面表与窗口决定。
- **落盘**：受理时 `SessionState.recordOperatorRole(role)` 写 `.Herdchestra/<sessionId>.role.json`
  （`{ v: 1, sessionId, role }`），并把 `identity.source` 改成 `sessionSnapshot`。下一次加载读文件，
  没有快照的钩子（如 `beforeModel`）也拿到同一角色。写失败不影响本次判定，原因进 trace 的
  `roleFactFailure`。
- **仍然 fail closed**：没有快照、或快照带 `parentAgentId`（子代理）时，会话保持 `unproven`，
  `beforeTool` 照旧拒绝一切面，也不写角色事实。**子代理永远不能用这条路拿到会话角色。**
- **`resolveRole` 不写 `unproven` 的角色事实。** 那里只有缺省值（`#role`），不是读数；
  提前写会把「默认」固化成「事实」，还会把本该是 Plan 的会话钉成 Manager（它的第一次调用就是 `js-plan`）。

### 2.3.1 会话的根是它自己的工作区

`projectRoot`（`facts.mjs`）依次取 `HERDCHESTRA_PROJECT_ROOT` → **`workspaceInfo.rootPath`** →
`workspaceInfo.cwd` → `cwd` → `process.cwd()`。适配器自己的仓库**不是**候选：沙箱里
`workspaceInfo.rootPath` 是会话目录、`cwd` 是插件目录，旧实现拿 `cwd` 与 `process.cwd()` 比较，
两者相等就一路落到 `repoRootFromAdapter()`——真机上 Plan 的台账与 Scope 日志因此写进了适配器的仓库，
而不是操作员的工作区。`.Herdchestra/` 下的每一个文件（角色事实、台账、Scope 日志、草稿）都由这一个根派生。

**`workspaceOf` 与 `workspaceRootOf` 的候选顺序一致**：`workspaceInfo.rootPath` →
`workspaceInfo.cwd` → `ctx.cwd`。`@cline/core` 0.2.0 的 setup `cwd` 是**插件目录**，
把它排在 `rootPath` 之前就等于把 `.Herdchestra/` 写进适配器仓库——那正是线上出过的那个 bug。
`process.cwd()` 只留在 `workspaceOf` 的最后一位（Hub 进程共享，没有任何会话含义）。

**受理的 review 必须落盘**：`ProgramSurface` 的 `saveLedger` 只在拿到 `ledgerStore` 时写台账，
`SessionState.surface()` 因此把 tenure 本体（`save()`）作为 `ledgerStore` 传进去。少了它，
程序里 `review(...)` 的受理只活在内存里——真机上两次受理 review 之后磁盘上没有 `.plan.json`，
底稿的只读锁也只在那一次程序里解开。`js-plan` 之外的受理路径（`reviewFor`）本来就调 `tenure.save()`。

**草稿目录在会话构造时就建好**（`ensureDraftDir`）：`write` 是目标同目录内的原子替换，
建不了目录；目录缺失时第一次写是 `ENOENT`，模型随后会把 `drafts.md` 写成普通文件，
之后每次写都是 `ENOTDIR`。目录建不成时 `planDraftDirFailure` 记下原因，`js-plan` 的规则
**原样告诉模型**（`could NOT be prepared` + 具名原因 + 目录），而不是让写入在看不出原因的地方失败。
`startPlan` 在建会话之前也做同一件事，失败即回 `DRAFT_DIR_UNWRITABLE`，不启动会话。

文件名的前缀有两套，这是有意的，不是缺陷：**角色事实、Pair Hint 日志、草稿**按会话 id
（`<sessionId>.role.json` / `<sessionId>.jsonl` / `drafts/<name>.md`），**台账与 Scope 日志**按
`#stateName`（`<stateName>.plan.json`、`<stateName>.scope.jsonl`）。`#stateName` 在会话有 id 时就是
`safeName(sessionId)`，只有宿主连 id 都没给时才会多一个序号——那种会话不存在第二个。
按会话 id 找不到台账时，先看 `#stateName` 是否退回了序号。

### 2.4 工具面收窄的唯一入口

`toolPolicies` 是宿主**唯一**能收窄工具面的开关，它的合并语义是
`{...policies["*"], ...policies[name]}.enabled !== false`
（`runtime-builder.ts:71-83`、`session-runtime-orchestrator.ts:153-164`）：

- `plugin-session.mjs` 因此写 `"*": { enabled: false }`，再给角色面逐项 `{ enabled: true }`；
  没被点名的工具（全部 builtin、其他角色面）都继承全局的 `false`；
- Plan 的请求里只剩 `js-plan`，Manager 的请求里只剩 `js-manager`/`js-review`/`fork`/`join`；
- 该字段放在 `config.toolPolicies`（`local-runtime-bootstrap.ts:602` 从 `input.config` 读），
  与插件路径放在 `localRuntime.pluginPaths` 是两处不同的位置，不能互换。

工具名**不会**出现在 `system_prompt` 里：宿主把工具面放在请求的 tools 字段
（`session-runtime-orchestrator.ts:988`），所以「工具面是否收窄」只能从
`toolPolicies` 的合并结果或真实 `tool_use` 判断。

## 3 提示词投影

不存副本，只用 `compose/prompt/projection.mjs`：

- Manager = `COMMON + MANAGER`，导演消息（根 §5 的 `<vibe-mode>` 段）另起一段；两者只出现一次。
- coder 与 devops = `COMMON + WORKER`，逐字相同。
- Plan = `compose/prompt/plan.mjs` 的 `PLAN_PROMPT`，与上面两种组合互斥。

**提示词由角色决定，不由「是否顶层」决定。** `SessionState.systemPrompt(role)` 是唯一分派点：
`plan` → `PLAN_PROMPT`，`manager` → `COMMON + MANAGER` 加导演消息，其余一律交给
`systemPromptFor(role)`。`systemPromptFor` 只认 coder 与 devops，别的一律拒绝
（`UNKNOWN_ROLE`；`plan` 另报 `PLAN_OUTSIDE_PROJECTION`）。**不得**把未知角色默认成 `coder`：
那样一个写错角色的会话会拿到 worker 提示与 worker 权限，看上去却一切正常。

这条规则曾经破过：`systemPrompt` 在 Plan 分支之后无条件返回
`directorPromptFor(systemPromptFor(MANAGER))`，于是被标成 `coder` / `devops` 的**顶层**会话
（`identity.mjs` 的 `ROLES_THAT_MAY_BE_MARKED` 允许 `manager`/`plan`/`coder`/`devops` 四个角色被标记）
拿到 Manager 提示加导演消息，直接违反根 §5。现在只有 `manager` 分支带导演消息，其余交给
`systemPromptFor(role)`，它只认 coder 与 devops。`fork` 启动的 worker 走另一条运行时，不受影响，所以破坏面只在顶层会话。
守卫它的性质测试在 `compose/prompt/dispatch.test.mjs`：角色唯一决定组合，导演消息只在 Manager
出现且只出现一次，coder 与 devops 逐字节相同，未知角色被拒。

投递走宿主真正支持的通道：

- Manager 的规则用 `registerRule`：`composeSystemPrompt` 把规则接在系统提示之后，
  这是宿主会保留的通道。
- 会话的完整提示（Manager 含导演消息，Plan 是 `PLAN_PROMPT`）也是 `registerRule` 的一条规则
  （`rewrite.mjs` 的 `systemPromptRule`），内容按请求读当前角色，所以**操作员自己启动的会话**
  从第一次请求起就带 Manager 提示，而不只是本适配器启动的会话。
  **不走 `beforeModel`**：该钩子的返回值只读 `stop`/`messages`/`tools`/`options`
  （`agent-runtime.ts:1700-1719`，0.2.0 同），返回的 `systemPrompt` 会被丢弃——这正是旧实现的缺口。
- 请求副本（relay barrier、Scope、Pair Hint、CUE 渲染）只有一处：`beforeModel` 返回的 `messages`
  （`rewrite.mjs` 的 `requestRewrite`，`hooks.mjs` 的 `beforeModel`）。宿主把这份 `messages` 换进
  **本次请求**的局部 `request`（`agent-runtime.ts:1700-1711`），随后 `model.stream(request)` 读的就是它
  （`:2099`）；转录只由 `state.messages.push` 增长，永不取自钩子的答案。`beforeModel` 看到的形状是
  运行时自己的 `AgentMessage`（`@cline/shared` `agent.ts:122`）：`tool-call` 部件在 assistant 消息里，
  `tool-result` 部件在 `role:"tool"` 消息里；provider 形状的转换在钩子之后
  （`agent-message-codec.ts:237-306`）。
  **`registerMessageBuilder` 在本宿主上永远不跑**：它的 `build` 只被宿主的 `prepareTurn`
  调用（`session-runtime-orchestrator.ts:1258-1267`），而 `local-runtime-host.ts:754` 总会填上那个字段。
  实测：builder 是入口时，Pair Hint 合成对到达 provider 的请求数是 0/24（`HACK.md`）。
- 由本适配器启动的会话（Plan、交接出的 Manager）在 `start` 时就把 `systemPrompt` 交给宿主，
  宿主 `resolveBootstrapSystemPrompt` 对非空 `systemPrompt` 原样保留。
- coder / devops 的提示是 `createDelegatedAgent({ prompt })` 的 `prompt`：
  `buildSubAgentSystemPrompt` 用它作 `overridePrompt`，不另加角色文字。

## 4 程序执行

`js-*` 的参数是 `code`，按真实 JavaScript 执行：

- 包进 async 函数，`await`、控制流、箭头函数、`Promise.all` 都可用；
- 一个能力只有一种写法（根 §3.1）：成员只以 `this.xxx` 存在，`fork`、`join`、`review` 三个顶层原语只以裸函数名存在；
- 角色缺的成员不在作用域里，调用即 `TypeError`，不是静默 no-op；
- 成员 park 时调用立即返回 `core` 的回执，程序保持挂起，由另一台程序 `unpark` 恢复；
- `class Js extends JsProgram { async run() {...} }`（§3.4 的形状）是标准写法；不声明类的正文会被包进同一个类，`this` 同样是程序实例。

结局判定以程序自身状态为准：`run` 返回后程序仍为 `parked`，本次调用就是挂起回执。
程序准入时按 `code` 的 UTF-8 字节数固定 core 的 `Deadline`（§3.3）。

### 4.1 工具参数模式必须能承载 `scope`（根 §6.2）

宿主先用 `inputSchema` 校验模型参数，再调 `beforeTool`。因此
`additionalProperties: false` 却不声明 `scope` 的工具，会让**合规调用根本无法写出**：
唯一被规格要求的字段先被模式拒掉，准入那一步永远看不到它。

六个工具（`js-manager`、`js-worker`、`js-plan`、`js-review`、`fork`、`join`）全部非豁免
（豁免只有 `negotiate`、`askPreviousScope`，它们不是本适配器的工具），所以六个都声明 `scope`，
且都保持闭合。`scope-schema.mjs` 是唯一定义：

- `SCOPE_SCHEMA` 用 `oneOf` 列四种 `kind`，每个分支各自闭合，因此「该 kind 不收的字段」
  在模型视野里就写不出来（与 `annotation.mjs` 的 `SCOPE_FIELD_UNEXPECTED` 同一语义）；
- `newScopeGoal`、`replacement` 同时带 `minLength: 1` 与 `pattern: "\\S"`：
  后者对应 `annotation.mjs` 的「非空白」，否则模式会放过一个 `"   "` 而让书去拒；
- `withScope(properties, required)` 追加 `scope` 并把它写进 `required`；
  `required` 只列工具真正必需的字段，`fork` 只有 `name` 与 `brief`（根 §4.1 的两参数原语）。

模式只是注解的**描述**，不是第二条准入规则：预算是否已建立、是否已耗尽、本拍谁是真注解，
仍只由 `core/scope` 的书决定。`scope-schema.test.mjs` 用 fast-check 固定两者的语言一致：
书接受的注解一定写得出来，书拒收的注解一定写不出来。

## 5 端口

| 角色 | 端口 |
|---|---|
| Manager | `fork`、`join`、`relay`、`command`、`control` |
| coder / devops | `fork`、`join`、`fission`、`command`、`control`（§3.1、§4.1：凡可派工的身份都有 `fork`/`join`；Plan 没有） |
| Plan | `devops`、`draft`、`relay`、`command`、`control` |

所有角色另有 `scope` 端口（`ask(member)`：豁免快照查询，同步返回，根 §6.2）。

- `command.run` 用 core 的 `nodeCommandRunner`（`/bin/sh`，`onData` 逐块上报）。
- `relay.handOver` 由 `TenureState.handOver` 一次写入退休、栅栏、继任三件事；
  文件没落盘就是三件事都没发生，台账原地不动。
- `relay.startManager` 走 `handoff.mjs` 的 `planHandoff`，真正的会话由
  `ManagerSessions.startManager` 在 `ClineCore` / `createRuntimeHost` 上开：新会话的
  `localRuntime.pluginPaths` 指向本插件入口，`config.sessionId` 与角色事实在启动前写好（§2.3），
  首个提示就是底稿字节。§2.2 的交接只**新开**一个 Manager 会话：Plan 不因此结束，也不被替换。
  半开的 Manager（起了会话但没投递）由 `stopManager` 停掉，回执里 `discarded` 如实报告。
- `relay.askOperator(question)` 经 `operator.mjs` 的 seam：它**永远**回
  `{ ok: true, asked: true, question, interrupt: true }`（`askedReceipt`），不再有「没有通道」这种拒绝。
  `this.ask` 就是 `this.park({ question })` + `interrupt`：挂起与中断本身就是通道。初任空账 review
  仅在 Plan 的 S1 出现；Manager 侧的初任空账是 `confirm`，不提问。
- `fork` 对在途 worker 是**真中途 steer**：brief 进运行时自己的待输入通道
  （`consumePendingUserMessage`，`agent-runtime.ts:1689` 每个请求边界读一次），由**正在运行的那个回合**
  在下一个请求边界取走，见 §7 第 3 条与 HACK.md「a steered brief rides the turn that is running」。
  回执恒为 `{ name, steered, started }`，成功里没有 rejection。一个 brief 被 admitted 时同时记成
  「被某回合承载」（`steered`）：承载它的那一个上报同时结算承载回合与被承载的 brief，两者各是一条
  `join` 记录（`#settle`）。宿主 handle 没有这条通道时，brief 排到该 worker 的下一回合（`pending`）。
- **`join` 的会计按「回合」记账，不按「名字」**（`workers.mjs`）。一个 worker 可以有好几个回合：
  排在运行中回合后面的 steer，或者在它已经结算过一次之后又收到的 brief。每个回合在被接受时
  `admitted += 1`，被结算时 `settled += 1`，只有「已结算且无人认领」的回合才是可交付的完成记录：

  ```
  owed = (admitted − settled − steered) + 未认领的完成记录
  ```

  `steered` 是「已被某个运行中回合承载的 brief」：它被 admitted，但它的上报就是承载回合的那一次，
  所以既不算第二个在途回合，也不能在承载回合结算后继续让 `join` 等一个不会来的上报。
  **被承载的 brief 各交一条结果，文本取自结算它的那次上报**（`#settle`）：一次上报先交承载回合，
  再按回合号升序交它承载的每一条 brief。承载回合的记录用这个回合被交给的文本（`carrierText`，
  没有 steer 时就是 `brief`）；被承载回合的记录用**上报的文本**——它们都是被这一份上报结束的，
  这份上报就是它们各自的答复。结果是按回合算的，每个回合恰好一条；两次 steer 落在同一回合时，
  队列里是「承载回合 1 条 + 被承载 2 条」，后两条同名同文，各自独立认领。重载后承载回合的 brief
  不在快照里，这条记录退回用上报的文本（`carrierText || brief || record.text`）。
  因此「第一个回合刚结束、紧接着启动队列中的 steered 回合」这一刻，新回合在排队时就已 admitted，
  `owed` 不为零，`join` 绝不回 `null`。运行时对同一个回合重复上报（宿主重试、重复事件）不会被记成
  第二个完成记录；认领按回合 id（`<name>#<turn>`）记账，同一条结果不会被交付两次。
  并发 `join` 各自登记一个可唤醒的等待者，每次唤醒后重查表；没有可认领的记录就重新挂起，
  不会空转。`owed()` 是这条不变量的公开读数，`worker-roster.test.mjs` 用 fast-check 状态机
  （任意交错的 `fork` / steer / 乱序完成 / `join` / 并发 `join` / 重载）固定它。
- **花名册是持久的。** 每次变化写一条记录到 `.Herdchestra/<session>.workers.jsonl`
  （`fork` / `steer` / `settle` / `claim` / `release`），会话结束时把整份快照写到
  `.Herdchestra/<session>.workers.restored.json`；`WorkerTable({ root, sessionId, restored })`
  从这份快照重建（`restoredRoster`），`restoreRoster(records)` 能把日志本身重放成同一份花名册。
  重载后的表对「本进程没有句柄、但仍被快照记为在途」的 worker 保留其回合，`join` 照旧等它；
  同名 `fork` 对这种名字回 `WORKER_RUNTIME_GONE`，而不是把 brief 收进一个没人会排空的队列。
  快照的每个条目带 `steered`（正在运行的回合承载了几条 brief）：少了它，重载后的表会把每条被承载的
  brief 都当成第二个在途回合，于是一个上报只结算承载回合，`join` 永远等一份不会来的结果。
  `settle` 记录同样带 `steered`，重放时按 `turn − steered … turn` 逐回合补完成记录。
  回合号是**名字的**，不是运行时的：同名 `fork` 重建运行时后，计数器从该名字已结算的回合数续上，
  否则新的 `name#1` 会撞上已认领的同名回合，`#complete` 会把这条结果当成「已交付」丢掉。
- 会话结束时 `SessionState.release()` 调 `WorkerTable.abortAll()`（§7 第 12 条），随后 `save()`
  把花名册落盘；回执因此是 `{ ok, released, orphaned, roster }`。被中止的回合永远不会上报，
  所以 `release` 把它们记为已结算：否则下一次加载会一直等一个不会再来的结果。
- `fission` 的端口是 `compose/fission` 的 `Fission`：成员 `fission(owner, prompts)` 是组操作，
  另外五个是建组步骤（`fissionSteps`）。`origins(owner)` 只认 `fork` 启动、且不是 lane 的 worker；
  会话本身不在 worker 表里，所以顶层调用者得到 `InvalidOrigin`。`ownerName()` 是会话 id。
  lane 起始文本的总泳道数读 `lane.laneCount`（组合层在 `lanesOf` 里一次性写进每条 lane，根 §4.2 第 3 步）；
  `createLane` 拿不到整数 `laneCount` 时以 `LANE_COUNT_MISSING` 抛错，让整组回滚，绝不印出 `undefined`。

### 5.1 操作员通道（`operator.mjs`）

宿主没有「问操作员一句话」的接口。**唯一确定可达的通道是操作员自己的下一条消息**；
审批往返在类型上有，但插件拿不到。

| 通道 | 宿主事实（源码） | 能承载什么 |
|---|---|---|
| 操作员的下一条消息 | 会话的下一次模型请求就是它，`hooks.beforeModel` 已经在读（`SessionState.resumeAsked`） | **操作员的原话**；唯一能穿过 sandbox 与挂起的通道 |
| `capabilities.requestToolApproval(request)` | `StartSessionInput.capabilities`（`local-runtime-host.ts:553`）里有这个字段（签名 `extensions/tools/team/delegated-agent.d.ts:44`，答复 `{ approved, reason }`），但 `setup` 的 ctx **不带**它，启动请求又要序列化、函数过不去 | 嵌入方在进程内接线时可用；**真实宿主会话拿不到** |
| `capabilities.toolExecutors.askQuestion(question, options, context)` | `extensions/tools/types.d.ts:95`；`ask_question` 的 schema 是 `{question, options: string[]}`（`extensions/tools/schemas.d.ts:317`） | **只有选择**；而且这些会话里 `ask_question` 根本不可见（`toolPolicies` 关掉全部 builtin，宿主的 `hJ()` 也默认禁用） |
| `ctx.emitEvent("steer_message", { sessionId, prompt })` | `agent-event-bridge.ts` 把 prompt 排进会话的待发队列，**下一次模型请求**才被消费（`consumePendingUserMessage`） | 给会话的下一轮，不能回答本轮正在等的问题 |

因此 `operator.mjs` 是**能力可注入**的 seam，但它**不靠**这些通道成立：

1. 通道本身（`this.ask` = park + interrupt）：程序挂起、工具回执带 `interrupt: true`、本回合结束，
   操作员拿到控制权并**在常规对话里答复**。这是**每一台** Cline 会话都有的通道，也是唯一必需的通道。
   答复由 `SessionState.resumeAsked` 在下一个 `beforeModel` 取到，注入挂起点（`unpark`），原程序继续。
2. `ask(question)`：嵌入方注入的同步通道，能在同一次调用里直接给答复；seam 把它原样返回。
3. `requestApproval(request)`：嵌入方在进程内注入审批回调时可用（`toolName: "herdchestra:ask"`，
   正文写明「用文字回答」），操作员的文字从 `reason` 取回；没有文字就回 asked 回执，**不报错**。
4. `request(question)`：嵌入方注入的投递通道，只用来把问题显示给操作员；投递失败也只是记录，
   回执照旧是 asked。

**没有第 5 条「拒绝」，也不存在「无通道」这种拒绝。** 把「无法证明人看到了问题」当成拒绝理由，
是把「无法证明送达」和「无法提问」混为一谈：挂起 + 中断本身就是提问，操作员的答复不需要插件去证明，
它会作为下一条真实消息到达。seam 因此**永远**回答 asked 回执（唯一的例外是空问题
`INVALID_QUESTION`，那不是拒绝通道，是拒绝一个不成立的问题）。
`index.mjs` 的 `setup` 把 `ctx.requestToolApproval`（通常不存在）与 `ctx.emitEvent` 交给 seam，
但那只是「能更快地拿到答复」，不是「能否提问」。`steer` 只用于**续跑**（见 §5.2），不用于回答问题。

**ask 就是 `park` + `interrupt`**（`compose/program/surface.mjs` 的 `ask` 成员 → `parkChannel`）：程序带着
`{ reason: "ask", payload: { question } }` 停住，工具立刻回挂起回执，回执带 `interrupt: true`，本回合
随即结束——这就是「中断」在本宿主上的样子：工具批次没有后续动作，run 收尾，控制权回到操作员。
`adapter.mjs` 把这次调用留在 park 表里（`session.noteAsk`），**操作员的下一句真实消息**在 `beforeModel`
被 `session.resumeAsked` 取到，它消费挂起点（`ProgramState#takeSite`）并把答复交给 park 表，挂起的
表达式以那句话为返回值继续（根 §2.2：不重开任期、不制造评审）。同一个答复也可以由 AI 在新程序里
用 `this.unpark(name, answer)` 注入，两条路走的是同一套 core 语义。

`ask` 只在 Plan 的 S1 阶段可用；`S2`/`S3`/`待执行` 与 Manager 上调用会被组合层以
`ASK_FORBIDDEN_OUTSIDE_S1` 拒绝，程序不挂起。**该拒绝码必须到达模型**：`ProgramCall#run` 把
`error.code` 复制到失败对象上，`adapter.mjs` 原样返回它，所以工具结果的 `error.code` 就是那个码，
而不是一个只有 message 的壳。

### 5.1.1 续体（`execution.mjs`）

一次 park 不是 body 的结束，而是它的暂停。要让「恢复」可被观察，三件事必须同时成立：

1. `ProgramCall#run` 在调用 body 之前就把它的 promise 存进 `#body`；body 挂起后，`run` 立刻返回
   `{ status: "parked" }`，工具调用当场回答挂起回执，而 **body 的 promise 仍然悬着**。
2. `ParkTable` 的条目同时持有 `promise`/`resolve`（唤醒点）与 `bodyPromise`（续体），
   以及下一次 park 的信号 `wakeNextPark`。
3. `ParkTable.resume(name, value)` 先装好「下一次 park」的信号，再唤醒挂起点，然后
   `Promise.race([bodyPromise, nextPark])`：
   - body 返回 → `{ status: "completed" | "failed", … }`，控制器拿到续体的终局回执；
   - body 再次挂起 → `{ status: "parked", site }`，控制器拿到同一个程序在新挂起点的回执；
     若新站点是 `ask`，`session` 同时把新问题登记进 `asked`，下一条操作员消息照旧恢复它。

因此 `await this.unpark(name, answer)` 的值是**续体的真实结局**，不是 core 在 `running` 时刻的视图；
`value` 仍是挂起表达式收到的那个值（core 的 `resumeValue` 决定）。

能力集（`adapter.mjs#CAPABILITIES_FOR`，根 §3.3 的「控制者须持有目标的能力集」）：

| 程序身份 | 注册的能力集 | 因此可以恢复 |
|---|---|---|
| `plan` | `["plan"]` | 自己的 `ask` 挂起 |
| `manager` | `["manager", "coder", "devops", "js-review"]` | 自己的程序，以及 worker 的程序 |
| `coder` / `devops` | `[role, "coder", "devops"]` | 自己的程序，以及兄弟 worker 的程序 |

### 5.1.2 无人值守与 Nudge（`guard.mjs`）

根 §2.2 的两个出口（Plan 的 `S1` `ask`、验收上一任合格后的终局停止）由组合层的
`compose/nudge.mjs` 判定；本适配器只负责**判定之后做什么**，而「做什么」由宿主的真实通道决定
（每条都已按源码核对）：

| 通道 | 宿主事实 | 能做什么 |
|---|---|---|
| `completionPolicy.completionGuard` | 只有宿主的 `runtime-builder.ts:855` 会写它（来自 team 状态与 `submit_and_exit`），插件设不了 | **不可用** |
| `afterRun` 的返回值 | `callAfterRunHooks` 丢弃 | **不可用** |
| `afterModel` 的返回值 | 只当 `AgentStopControl` 读，只能停、不能续 | **不可用** |
| `beforeModel` 的 `appendContext` | 收进 `pendingHookContexts`，下一次迭代作为一条 user 消息刷出 | **在 run 内续跑** |
| `emitEvent("steer_message", …)` | 进会话的待发队列，下一次请求消费 | **run 已结束后续跑** |

所以职责拆成三处，各自只做自己通道能做的事：

- `afterModel` → `turnExit`：模型这一轮没有发出任何工具调用 = 一次自然停止尝试。判定拒绝时把
  Nudge 记进会话（`noteNudge`），**不**在 `afterModel` 里说话（宿主不听）。
- `beforeModel` → `requestNotice`：本次请求带上记下的 Nudge。**先重新判定**：若出口在这期间已经
  到达（台账清空、交接完成），就丢掉这条 Nudge 并清空记录，绝不对已完成的工作继续催促。
- `afterRun` → `runExit`：run 还是结束了（宿主没注册守卫、同一 run 里守卫被更早一轮消费）。
  此时用 `steer_message` 投一条真实提示续跑，回执如实报告 `continued` 与 `delivery`；
  通道缺失时是 `NO_CONTINUATION_CHANNEL`，不是假的成功。

Nudge 文本由 `compose/nudge.mjs#buildNudgeMessage` 生成：当前台账的全部 `{ key, value }`
（空账则明说本阶段尚未完成评审/继任验收/交接），加上根 §2.2 的硬口令原文。

不是出口的三种情况（都不发 Nudge）：worker 的回合结束是它的汇报（`not-user-facing`）；
run 失败或被取消是宿主的报告（`run-failed` / `run-cancelled`）；带工具调用的回合还没有停。

挂起期间的三条真实边界（`hooks.mjs`、`session.mjs`、`agent-runtime.ts`）：

- **闭回合**：`beforeModel` 在 `resumeAsked` 之前就 `closeTurn()` 了。这是对的——挂起那一回合的全部工具
  都已结算，`closeTurn` 只拒「整回合只有 `meToo`」；恢复本身不产生 Scope 记录。
- **`resumeAsked` 只取最后一条 user 消息**（`request.mjs#lastOperatorText`）。宿主会把钩子上下文
  （`source="RunStart"` / `PreToolUse` / `PostToolUse`）作为一条 `role: "user"` 的消息追加在末尾
  （`agent-runtime.ts:1100-1124`，`displayRole: "system"` 只影响展示，不改 role），所以若那条上下文
  排在操作员消息之后，恢复用的就是**上下文而不是原话**。今天 `RunStart` 的上下文在同一次 run 的开头
  注入，因此真实顺序是先上下文、后原话；**这个先后是宿主的顺序，不是本适配器的保证**。
- **没有跨进程恢复**：`#asked`（park 表里的那个 promise）只活在内存里。宿主进程重启、会话被 `stop`
  后重新 `start`、或插件被重新加载，挂起的调用与它的 park 表条目一起消失，`messages.json` 里只剩一句
  没有结果的工具调用。**程序体接不回来，就没有续写**：用一条合成的 user 消息把「上一轮问过什么」重述给
  模型，不是原 promise 的 continuation，也不是操作员的真实答复送达路径——它会在模型上下文里伪造一段
  没人说过的话，并且要靠读一次就删掉的问题记录来维持，所以本适配器**不做**这件事。唯一合法的继续是
  操作员重新发起，或宿主侧真的把那个 promise 接回来（见 §7 第 11 条的宿主面证据）。

  宿主为什么接不回来：`ClineCore.send` 就是 `host.runTurn`，`runTurn` 先 `getSessionOrThrow`
  （`local-runtime-host.ts:1143`、`:2630-2648`），只认 `LocalRuntimeHost.sessions` 里的活动会话，
  所以 `core.start` 建好、`dispose` 或重启后已经不在表里的会话一律 `SessionNotFoundError`；
  `ClineCore.restore({sessionId, checkpointRunCount, start})` 能带着历史重新 `startSession`
  （`local-runtime-host.ts:1106-1130`），但那是**按 checkpoint 重新开始**，不是把挂起的 promise 接回来；
  宿主的 `pendingPrompts`（`steer_message` / `queue_message` / `pending_prompt` 事件）能把文字排进会话的
  待发队列，由 `consumePendingUserMessage` 在**下一次模型请求**取走（`local-runtime-host.ts:862-870`），
  但同样要求会话在 `sessions` 表里，且它是「给下一轮的输入」，不是「对本次挂起的答复」。

### 5.2 Plan 的真实入口（`plan-entry.mjs`、`index.mjs`）

Plan 是独立顶层会话，不由 `fork` 创建，不在 `COMMON + MANAGER` / `COMMON + WORKER` 这两种组合内（根 §5）。入口只有一处：

```js
const cline = await ClineCore.create();              // 或 createRuntimeHost()
const { startPlan } = await import("<repo>/src/host-cline/index.js");
const started = await startPlan({ host: cline, text: "把重试改成指数退避", root: "/repo/demo" });
```

- `startPlan` 起一个会话：`systemPrompt = PLAN_PROMPT`、`localRuntime.pluginPaths = [index.js]`、
  `config.sessionId` = 适配器自己生成并已写好角色事实的那个 id（§2.3），
  首个提示就是操作员原文。宿主拒绝 `.mjs` 插件路径（`PLUGIN_FILE_EXTENSIONS`），所以入口是 `index.js`。
- 已在本插件会话里的调用者用 `plugin.openPlan(text, { root, name, model })`，走同一个 `startPlan`，
  主机句柄由 `ManagerSessions` 打开并复用。`model` 可以由调用者给；不给时走
  `SessionState.resolveModel()`（见 §7 第 2 条的同一条梯子）。梯子全空时入口回
  `MODEL_UNKNOWN` 并列出试过的来源，不会用 `"cline"/""` 起一个必然失败的会话。
- `plugin.openPlan` 的两条分支（调用者自带的 `openPlan`、适配器自己的 `ManagerSessions#openPlan`）
  收到**同一组**派生后的选项：`root` 取调用者的，缺省用 `adapter.session.root`；`name` 取调用者的，
  缺省用 `adapter.session.sessionId`。`startPlan` 只从这两个值派生底稿路径与会话 id，所以同一会话的
  多次 `openPlan` 落在同一份底稿上——工厂分支若拿原始选项重新缺省，就会按 `cwd` 与临时名派生第二份。
- **连接按证据顺序取，setup 只是其中一级**：`PluginSetupContext` 本身既没有 `providerId` 也没有
  `modelId`（实测 `ctxKeys` 只有 `session`/`client`/`user`/`workspaceInfo`/`cwd`/`emitEvent`/`onDispose`）。
  顺序是：`beforeModel` 记下的最近一次请求（`SessionState.noteModel`）→ setup 上下文里嵌入方给的
  `ctx.model`/`ctx.config`/`ctx.session` → `CLINE_PROVIDER_ID`/`CLINE_MODEL_ID` → 宿主会话记录
  （`getSession` 的行与 metadata 都带 `provider`/`model`，Cline 0.2.0 实测）。
  两半必须齐全，`SessionState.resolveModel()` 是带 await 的形式，`fork`/`startManager`/`openPlan`/
  `this.devops` 共用，回执的 `modelSource` 说明命中哪一级。全空时回 `MODEL_UNKNOWN`，**不猜 `cline`**。
- `modelId` 为空时在调用宿主之前就回 `MODEL_MISSING`：宿主的 `CoreModelConfig` 要求非空模型，
  否则失败会埋在宿主 schema 的 zod 消息里。

- **宿主 config 的三个必填布尔**：`ClineCoreStartConfig`（= `CoreSessionConfig`）继承
  `CoreRuntimeFeatures`，`enableTools` / `enableSpawnAgent` / `enableAgentTeams` 都是必填 `boolean`，
  `systemPrompt` 也是必填。宿主把它们翻译成 `SessionManifestSchema` 的 `enable_tools` /
  `enable_spawn` / `enable_teams`（`local-runtime-host.ts:506-508`、`hub-runtime-host.ts:731-733`），
  那三个是 `z.boolean()`：**请求里缺一个就是 ZodError**，而字段名是 camelCase——写 snake_case
  同样缺。本适配器的两条自建会话路径（`plan-entry.mjs`、`sessions.mjs` 的 `config()`）都显式给出
  这三项；手写 `cline.start` 的调用者（README 的装载示例）也必须给，否则报错来自宿主 schema，
  而不是来自本适配器。
- Plan 会话的角色由它自己的插件读回：`roleOf` 先看 `setup` 是否给了 metadata，再问宿主记录
  （`getSession` 或 `get`）。
  读回之前 `beforeModel` 会先 `resolveRole()`，所以 Plan 不会先带一次 Manager 提示；
  角色变化同时换掉台账文件（`.Herdchestra/<会话>.plan.json` 对 `.manager.json`）。
- 底稿路径由会话自己决定：`startPlan` 派生的路径写进 metadata，Plan 会话读回它；
  没有 metadata 的会话按 `sessionId` 派生（`drafts.mjs` 是唯一定义）。
- **底稿目录在会话创建之前就备好**：`write` 是经目标目录内临时文件的原子替换，自己不会建目录，
  所以 `startPlan` 在调宿主之前先 `ensureDraftDir`；建不出来就回
  `{ ok: false, reason: "DRAFT_DIR_UNWRITABLE", message }`，**不启动**会话——一个永远写不下
  自己唯一产物的 Plan 没有意义。会话侧 `resolveRole` 在 `js-plan` 规则报出路径之前再备一次
  （`startPlan` 之外的会话也走这条路）；失败原因由 `planDraftDirFailure` 交给规则，规则如实说明
  目录没备好，而不是只给路径、让模型在 `write` 上撞 ENOENT。
- 工具是 `js-plan`，成员只有 `cat`、`write`（读写的唯一对象是底稿）；`ask`、`devops`、
  `review` 由端口提供，`park`/`unpark` 由 `ProgramSurface` 的处理器补齐。
- `registerRule` 用 `whenToolAvailable: "js-plan"` 把底稿路径告诉 Plan，模型不能自选路径。
- `devops(intent)` 用与 `fork` 同一个 `createDelegatedAgent` 起一个 devops 运行时并等它的
  单次结果（`devops.mjs`）；同一时刻至多一个在飞，第二次回 `DEVOPS_ALREADY_IN_FLIGHT`。
  工厂不可达时回 `HOST_HAS_NO_DELEGATED_AGENT`，**不**编造调查结论。
- 底稿在本任第一次 `review` 之前只读（§2.2）：`js-plan` 的 `write` 在 `reviewCount === 0` 时抛
  `DRAFT_READ_ONLY`，第一次 review 之后才可写。
- 门禁：`PLAN_FORBIDDEN` 拒绝宿主的读/跑/编辑/子代理工具以及 `js-worker`/`fork`/`join`。

### 5.3 交接（`handoff.mjs`、`sessions.mjs`）

- Plan 在「待执行」提交空账 `review([])`，`ReviewMember` 调 `relay.startManager(draft)`。
- review 不带任何文本：`reviewFor(findings, role?)` 的第二参数是角色，不是 note；底稿只从 `draft`
  端口读（`.Herdchestra/drafts/<session>.md`），读不到就是 `DRAFT_MISSING`，绝不拿别的文字顶替。
- 底稿为空时在开会话之前拒绝（`DRAFT_MISSING`），Plan 留在「待执行」，下一次空账 review 重试。
- 会话起好但没投递时，`planHandoff` 调 `stopManager`，回执带 `discarded`；台账不动。
- 新 Manager 会话与 Plan 会话互不共享台账：`sessionId` 不同，文件名不同。

## 6 钩子

| 钩子 | 行为 |
|---|---|
| `beforeRun` | 只开 run（`#run += 1`），**不**开模型回合；把 Scope 冷启通知作为钩子上下文交给宿主 |
| `beforeModel` | **先关上一回合**（`session.closeTurn()`，全 `meToo` 的拒收写进钩子上下文），再开新回合（`session.openTurn()`），再 `resolveRole()`；随后做请求副本：接力栅栏 → Scope 投影 → Pair Hint；第一次请求整段替换本角色的提示。Scope 投影传 `pinned: PINNED_MEMBERS`（`request.mjs`：core 默认值 + 空字符串 + `SURFACE_MEMBERS["js-review"]`），与 opencode 同集；不传时 Pair Hint 的合成对会在 `resetScope` 之后被裁掉，下一次呈现随之漂移。Pair Hint 三件事都在 `session.mjs#projectMessages`：栅栏切过副本时由 `tenure.anchorOfFence()` 对**本条栅栏**锚一次（冻结文本、epoch、handles 不动），冻结的 handles 与当前未结清 park 名不等时开新 epoch，相等时不动前缀 |
| `beforeTool` | 角色面门禁（`callRole`）、Scope 准入 |
| `afterTool` | Scope 结算；拒收写进钩子上下文，不抛异常 |
| `afterModel` | **不关回合**：宿主在这一刻还没跑本回合的工具 |
| `afterRun` / `onEvent` | 收尾与观察；都不改 turn id |

**回合边界是模型请求，不是整个 run，也不是 `afterModel`。** 宿主一次迭代的顺序是
`beforeModel` → `afterModel`（`agent-runtime.ts:2079`）→ `beforeTool`/`afterTool`（`executeToolCalls`，:974）。

`beforeTool` 的 Scope 准入读 `input.scope`（`session.admit`），所以工具的 `inputSchema`
必须先放行这个字段（§4.1）；模式与准入是两件事，模式放开字段不等于准入放行调用。

**钩子上下文是模型可见文本，按根 §6.3 用 CUE。** `appendContext` 会进入当次请求副本，
所以结构化载荷（Scope 冷启通知、结算后的通知）由 `hooks.mjs` 的 `contextText` 调
`core/cue.mjs` 的 `toCue` 渲染，不再 `JSON.stringify`：字段是 `kind: "…"` 这样一行一个，
多行 `message` 是 `"""` 块，模型不会读到 `\"`、`\n` 这类转义。`toCue` 拒绝的值（本适配器不产）
退回 `String`，钩子绝不因渲染失败而崩。`.Herdchestra/` 下的落盘仍是 JSON（§1），不在 §6.3 范围。

**工具结果进请求时同样按 §6.3 用 CUE，而且必须在适配器里做。** 宿主的 provider 层
（`@cline/shared` 的 `Dh(output, isError)`）对结构化 `output` 回 `{ type: "json", value }`，
再 `JSON.stringify` 成模型可读文本；被 `beforeTool` 拒绝的调用因此到模型眼里是
`{"error":"js-manager is not available to this session"}`。宿主源码不改，所以
`request.mjs` 的 `toCueToolResults(messages)` 在 `SessionState.projectMessages` 的**第一步**
（早于接力栅栏、Scope 投影、Pair Hint）把每个 `tool-result` 部件的结构化 `output` 渲染成
`{ type: "text", value: toCue(value) }`。四条不变量：

- **只改 `tool-result` 部件的 `output`。** `toolCallId`、`toolName` 与宿主放在旁边的键都不动，
  因为 Pair Hint 用 `JSON.stringify` 逐条比较已呈现的消息（`injection.mjs` `assertPrefix`），
  键序或键集变化会被读成「消息被改写」。
- **文本结果与操作员原文一字不改。** `{ type: "text", value }` 的 value 就是模型该读的字节，
  再套一层 CUE 会给它加引号；`user`/`assistant` 消息完全不动。
- **幂等（按对象同一性，不只看字节）。** 已经可读的部件（字符串 `output`、`{ type: "text",
  value: <字符串> }`、没有 `output`）由 `toolResultText` 回 `null`，`toCueToolResults` 原样返回
  同一个部件对象；渲染过的部件第二次投影也读到字符串 value 原样返回。同一请求的 CUE 字节与
  对象因此都稳定，Pair Hint 的前缀链不会因二次投影断裂。判定只看 `toolResultText` 的返回值，
  不再先问 `structuredValueOf`——否则 `{ type: "text", value: <非字符串> }` 会被跳过，
  把 JSON 对象原样送进请求。
- **渲染不了就失败关闭，绝不退回裸 JSON。** `toCue` 拒绝的值（循环引用、函数、symbol、
  抛错的 getter）过去退回 `JSON.stringify`，那正是根 §6.3 禁止的形状。现在该部件的
  `output` 换成 CUE 拒绝回执 `{error: "UNRENDERABLE_TOOL_RESULT: …"}`（常量
  `UNRENDERABLE_TOOL_RESULT`），内容扣留、调用照常应答、回合继续；只有连这条回执都渲染不了
  时才直接发纯文本。`{ type: "text", value }` 的 value 不是字符串时按结构化值渲染成 CUE，
  渲染不了再走同一条拒绝，绝不 stringify。

工具**返回值**本身仍是结构化对象：宿主把它序列化给模型之前就被这里换掉了，
`host-omp`/`host-opencode` 的「结构化输出」契约与它不冲突。

## 7 缺口（不得假装已解决）

1. 宿主没有按角色禁用工具的接口，Manager 的「无读权限」只能由 `beforeTool` 返回 `{ skip: true }`。
2. `createDelegatedAgent` 只能在运行时解析，且**在 worker 沙箱里也必须能解析到**。
   `host-modules.mjs` 按证据强度爬梯子：`CLINE_CORE_MODULE` → 裸 `@cline/core`/`@cline/sdk` →
   从 `argv[1]`/`argv[0]`/`execPath`/`cwd`/插件目录向上找 `node_modules/@cline/core` →
   已知安装布局（`/usr/lib/Cline/bin/desktop-backend/plugin-runtime/node_modules/@cline/core`、
   `/opt/Cline/...`、macOS bundle、Windows 安装）→ `../cline` 源码树（`packages/core`、
   `sdk/packages/core`）。候选先 `stat` 再 import，只接受导出本适配器所需条目的模块，结果按进程缓存，
   `hostModuleSource()` 报出命中哪一级。
   **实测依据**：worker 里 `require.resolve("@cline/core")` 是 `MODULE_NOT_FOUND`，但按绝对路径
   import 宿主的 `dist/index.js` 可用（612 ms，两个工厂都在）；worker 拿到的是独立模块实例，
   而这两个工厂是纯函数式的连接封装，不需要跨线程共享状态。
   连接（provider/model）走同一条「有证据才用」的梯子：最近请求 → `setup` 上下文 → 宿主会话记录
   （`getSession`，行与 metadata 都带 `provider`/`model`）→ `CLINE_PROVIDER_ID`/`CLINE_MODEL_ID`；
   两半齐全才算读数，`SessionState.resolveModel()` 是带 await 的形式，`fork`/`startManager`/
   `openPlan`/`this.devops` 共用，回执的 `modelSource` 说明用了哪一级。
   全都拿不到时 `fork` 仍回 `{ started: false, rejection: "HostCapabilityMissing" }`，
   连接全空时回 `MODEL_UNKNOWN` 并列出试过的来源——不假装启动，也不用缺省值顶替。
3. `SessionRuntime.run()` 在运行中直接拒绝（`session-runtime-orchestrator.ts` 的
   `getRunAdmissionError`），hub 的 `send_input` 队列也拿不到：`ctx.emitEvent("steer_message", …)`
   只在 `PendingPromptService.enqueue` 找得到会话时生效（`pending-prompt-service.ts:266`），而
   `getSession` 只认 `LocalRuntimeHost.sessions` 里登记的会话；`createDelegatedAgent` 的 worker
   不在那张表里，`enqueue` 静默返回。
   **可达的中途输入通道是运行时自己的**：agent loop 在每个请求边界读
   `consumePendingUserMessage()`（`agent-runtime.ts:1689`，由 `agent-runtime-config-builder.ts:117`
   从 config 转发）。`runtime.mjs` 在宿主没装时装上它，`handle.steer(brief)` 把文本入队，
   运行中的回合在下一次请求取走；`notifyPendingUserMessage()` 只打断模型请求
   （`agent-runtime.ts:633`），在跑的工具会跑完。
4. 子代理的工具表由调用方给定，而**配置型子代理的 `tools:` 会先被宿主的 `createSubAgentTools`
   收窄**（`configured-agent-tool.ts:160`，`runtime-builder.ts:662` 接线），插件看不到那个函数。
   本适配器因此在**自己交出去的工厂**上兜底：`guardedCreateDelegatedAgent` 与 `runtimeHandle`
   都用运行时自己的 `addTools` 把 worker face 补回去（`enforceFace`），回执里 `face` 如实报告；
   没有工具面的运行时回 `NO_TOOL_SURFACE`，不假装守住。
5. **不是缺口：** 本适配器不需要宿主的问答通道。宿主的 `ask_question` 只承载 2–5 选一，
   `steer_message` 只能投给根会话，但 `this.ask` 走的是 `park` + `interrupt`：程序挂起、回合结束、
   操作员在常规对话中答复、`resumeAsked` 把答复注入挂起点。因此 `this.ask`（Plan 的 S1）与
   Plan 初任空账提问都不回拒绝，回 `{ ok: true, asked: true, question, interrupt: true }`。
   真实的边界只有一条：挂起不跨进程（第 11 条）。
6. 插件的入口必须是 `.js`/`.ts`：宿主在导入前就拒绝 `.mjs`（`PLUGIN_FILE_EXTENSIONS`）。
7. 宿主用 jiti 加载插件（babel 变换）；`#name()` 形式的私有方法在变换后无法解析，
   所以本目录的类只用私有字段，不用私有方法。
8. 会话角色来自 session metadata（`herdchestra.role`），而 `PluginSetupContext` 今天不带
   metadata（`AgentExtensionSessionContext` 只有 `sessionId`）：插件必须自己读，两个方法名
   （`getSession` / `get`）都要认；沙箱里连宿主模块都解析不到，所以还必须读入口写下的
   `.Herdchestra/<session>.role.json`。`HERDCHESTRA_TRACE_FILE=<path>` 会把每次 `beforeTool`
   的判定写进该文件（沙箱里 stderr 不被转发，这是唯一可用的诊断通道），行内 `identitySource`
   直接说明角色是从哪一级读到的。
9. 插件 setup 的预算约 4 s（`setupTimeoutMs`，两种模式相同；实测 3.8 s 内完成、4.2 s 超时）。
   角色事实是同步读的一个小文件，远在预算内；将来若要在 setup 里做更重的事，必须先量。
10. `fork` 出来的 worker 的 `fission` 只在假端口上验证过：真 lane 需要
    `createDelegatedAgent` 可达（第 2 条）。
11. **ask 的挂起不跨进程，也没有续写**：`SessionState.#asked` 只在内存里，`messages.json` 里那条工具
    调用没有结果。宿主重启、会话 `stop` 后重新 `start`、或插件重新加载之后，**程序体接不回来**：
    `compose` 的 `restore` 不读挂起回执，`ClineCore.send` 走 `runTurn → getSessionOrThrow`
    （`local-runtime-host.ts:1143`、`:2630-2648`）只认活动会话，不在 `sessions` 表里的会话一律
    `SessionNotFoundError`；`ClineCore.restore` 是按 checkpoint 重开（`local-runtime-host.ts:1106-1130`），
    不是接回挂起的 promise；`pendingPrompts`（`steer_message` / `queue_message` / `pending_prompt`）
    只能把文字排给**下一次**模型请求（`local-runtime-host.ts:862-870`），同样要求会话在 `sessions` 表里。
    **用合成的 user 消息重述问题不是替代品**：那不是原 promise 的 continuation，也不是操作员的真实答复，
    而是在上下文里伪造一段没人说过的话；本适配器不做，恢复只能等宿主提供接回 promise 的通道，
    否则由操作员重新发起。
12. **会话结束时 worker 必须被停**：`PluginSetupContext.onDispose` 是宿主给插件的唯一收尾钩子
    （`contribution-registry.ts:219`）。`index.mjs` 的 `setup` 已注册它，`SessionState.release()`
    调 `WorkerTable.abortAll()` 并随后 `save()`，回执是 `{ ok, released, orphaned, roster }`
    （中止那一半与 `abortLanes` 同形）：中止抛错的 runtime 仍在表里、被如实列为 `orphaned`；
    `roster` 是落盘结果，写不进去时如实报 `{ ok: false, reason: "NO_ROSTER_FILE" }`。宿主若不给 `onDispose`（旧版本），
    本适配器**没有别的收尾手段**，worker 会随宿主进程存活到宿主自己结束它。

## 请求副本里待结果的工具调用（根 §6.1）

Scope 的投影按条目取舍，宿主必须把它还原成自己的消息。还原时有一条硬规则：**助手消息保留它原本拥有的每一个 `tool-call` 部分，一个都不少**。

原因不是风格：宿主把本回合的助手消息连同「结果尚未到达」的工具调用一起交给我们。若还原时丢掉那个调用，模型刚读过的一条消息就被改写，而下一次请求（调用已带结果、于是被保留）会把同一条消息改回原样——两次请求之间同一位置的字节变化，正是 §6.1 禁止的前缀漂移。实测样例见 `host-opencode/spec/message-adapter.mjs` 的注释（`ses_e6b5fa739ffef04j5obpcnk3Ze`：第 4 次请求在 index 3 被拒）。

两个方向都不许走偏：
- **不删**：模型读过的调用不能从副本里消失；
- **不加**：模型没读过的调用不能凭空出现。

只有「结果的对调用已经不在副本里」的那一半仍然丢弃——provider 会拒绝一个它没请求过的结果。一条消息的所有条目都被丢掉时，整条消息照旧离开副本（§6.2 的旧 Scope 修剪不受影响）。`selectKept` 是这条规则的唯一落点（`src/host-omp/request.mjs`、`src/host-cline/request.mjs`）。
