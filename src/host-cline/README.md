# host-cline：Cline 宿主适配器

把万象术接到 Cline（Desktop / CLI / SDK）的插件接口上。不修改宿主源码，只以插件身份装载。

## 装载

本目录是独立包根。插件要**被加载**，路径必须落在 `localRuntime.pluginPaths`（`AGENTS.md` §2.1：
`local-runtime-bootstrap.ts:463` 只把 `localConfig?.pluginPaths` 交给 `resolveAndLoadAgentPlugins`）；
工具要**可见**，还要由同一会话请求的 `toolPolicies` 打开。`config.agentPluginPaths` 只喂 settings 里的
**包**加载器，写在 `config.pluginPaths` 里**不加载任何插件**（真机实测）。本适配器自己的会话请求
（`plugin-session.mjs` 的 `pluginSessionConfig`）已经按这条规则写好，下面是等价的 `package.json` 形态：

```json
{
  "cline": {
    "plugins": [
      {
        "paths": ["./index.js"],
        "capabilities": ["tools", "hooks", "rules"]
      }
    ]
  }
}
```

```js
await cline.start({
  prompt: "...",
  config: {
    providerId: "cline",
    modelId: "<宿主当前的模型 id>",
    cwd: process.cwd(),
    // 宿主 schema 要求这三项与 systemPrompt，缺一项就是 ZodError：
    // `SessionManifestSchema` 的 `enable_tools` / `enable_spawn` / `enable_teams`
    // 是 `z.boolean()`，而宿主用 `config.enableTools` / `enableSpawnAgent` /
    // `enableAgentTeams` 填它们。字段名是 camelCase，写 snake_case 同样失败。
    enableTools: true,
    enableSpawnAgent: false,
    enableAgentTeams: false,
    systemPrompt: "<会话的系统提示>",
    // 工具可见性：先全部关闭，再逐个打开本会话要用的面（AGENTS.md §2.4）。
    toolPolicies: { "*": { enabled: false }, "js-manager": { enabled: true } },
  },
  localRuntime: {
    // 插件加载路径的唯一落点（AGENTS.md §2.1）。
    pluginPaths: ["/abs/path/to/herdchestra/src/host-cline/index.js"],
    // 必须是**数组**：宿主类型是 `RuntimeConfigExtensionKind[]`
    // （`@cline/shared` `hub.d.ts:369`），读法是 `new Set(extensions).has(kind)`。
    // 对象形式（`{ rules: true, ... }`）在宿主内部抛 `object is not iterable`
    // ——`@cline/core` 0.2.0 真机实测。合法值只有 `rules`/`skills`/`workflows`/`plugins`。
    configExtensions: ["rules", "skills", "plugins"],
  },
});
```

插件默认导出（也以 `plugin` 具名导出）一个 `{ name, manifest, hooks, setup }` 对象，
`manifest.capabilities` 恰好是 `tools`、`hooks`、`rules` 三项（请求改写的入口是 `hooks` 里的
`beforeModel`，见下；`messageBuilders` 在本宿主上永远不跑，所以不申请）。入口必须是 `.js`：宿主在
导入前就拒绝 `.mjs`。

### 装进 Cline Desktop（`cline plugin install`）——实测结论

`cline plugin install` 走的是宿主的**包加载器**，与 `localRuntime.pluginPaths` 无关：
它扫描三处 `<workspace>/.cline/plugins`、`<cline-home>/plugins`（即 `~/.cline/plugins`）、
`~/Documents/Cline/Plugins`，`config.agentPluginPaths` 只是这一加载器在 settings 侧的同名入口，
**不是**会话加载插件的路径。会话要加载本插件，路径必须写在 `localRuntime.pluginPaths`
（`local-runtime-bootstrap.ts:463` 读 `localConfig?.pluginPaths`）；写在 `config.pluginPaths`
或只写 `config.agentPluginPaths` 都不加载任何插件。

**安装命令与落点**（`cline-cli` 3.0.74 实测）：

```sh
cline-cli plugin install /abs/path/to/Herdchestra --json
# -> {"installPath":"~/.cline/plugins/_installed/local/Herdchestra-<hash>",
#     "entryPaths":["…/package/src/host-cline/index.js"],"mcpSyncFailures":[]}
```

安装器只复制根 `package.json` `files` 列出的东西（根是 `["src"]`），并在安装目录写一份
带 `cline.plugins[].paths` 的 `package.json`。**根 `package.json` 必须声明 `cline.plugins[].paths`
且只列本插件**，否则安装器把 `src` 下每个 `.js` 都当入口，`src/host-opencode/index.js`
被判为非法插件：

```json
"cline": {
  "plugins": [{ "paths": ["./src/host-cline/index.js"], "capabilities": ["tools", "hooks", "rules"] }]
}
```

**为什么不能只装 `src/host-cline`：** 它的依赖闭包有 17 条相对导入落在目录之外
（`../core/*`、`../compose/*`），装完在第一条 `../compose/program/surface.mjs` 上
`Cannot find module`。装根包则整棵 `src` 一起搬走，闭包完整。

**三条实测的发现规则**（`@cline/core` 0.2.0 的 `discoverPluginModulePaths`/`loadAgentPluginsFromPaths`）：

1. 传入**入口文件**（`.js`/`.ts`）——可用。安装器回报的 `entryPaths` 正是这种形态。
2. 传入**目录**：只有当该目录的 `package.json` 里有 `cline.plugins[].paths` 时才解析成插件；
   解析时以 `package.json` 为准，**不需要 `plugin.json`**。
3. 目录里放了 `plugin.json` 反而会让「按目录发现」失效：`Fu()` 见到 `plugin.json` 就让
   目录遍历返回空。**不要为本插件创建 `plugin.json`。**

**插件装好之后，Desktop 会话仍要靠快照证明身份。** 包加载只决定「插件有没有被装载」；
工具可见性仍是 `toolPolicies`，角色仍是会话事实（§2.3）。操作员自己开的 Desktop 会话没有
角色事实，`setup` 又早于会话注册（拿不到 `getSession`），所以角色改由**每次调用都带的宿主快照**
决定：顶层调用（`snapshot` 存在且没有 `parentAgentId`）即操作员自己的会话，按 Manager 受理
（调用 `js-plan` 时为 Plan），并把 `{ v: 1, sessionId, role }` 写成
`.Herdchestra/<sessionId>.role.json`，供下一次加载读回。没有快照又没有角色事实时仍按
`SESSION_ROLE_UNKNOWN` 拒绝——**fail closed 的那一半没有变**。

**在用户机器上的实际启用状态**（2026-11-01，Cline Desktop 0.0.49 / core 0.2.0）：

```
~/.cline/plugins/_installed/local/Herdchestra-889aca242dc5/
  package.json                       (name Herdchestra, cline.plugins[].paths -> ./package/src/host-cline/index.js)
  package/src/{host-cline,core,compose}/…
```

该入口经宿主自己的 `loadAgentPluginsFromPaths` 实测：1 个插件、0 个失败、6 个工具
（`js-manager`/`js-worker`/`js-plan`/`js-review`/`fork`/`join`）、2 条规则；一条模拟的操作员
顶层 `js-manager` 调用被受理，角色事实按上表写出。用户既有会话（23 条）、
`~/.cline/data/settings/global-settings.json` 与宿主 `/usr/lib/Cline` 均未改动。

**限制**：`plugin.json` 的完整清单校验、Marketplace 安装与 Desktop 设置界面里的插件面板
本次没有实测；Desktop 的侧边栏也不会把插件工具画出来——它们只进会话的请求。

## 它注册什么

| 产物 | 内容 |
|---|---|
| 工具 | `js-manager`、`js-worker`、`js-plan`、`js-review`、`fork`、`join` |
| 规则 | 两条：`content: () => systemPromptFor("manager")`（即 `compose` 的 `COMMON + MANAGER`，逐字来自同一份原文，不是副本）；Plan 底稿路径与「目录已备好」的说明（`whenToolAvailable: "js-plan"`） |
| 钩子 | `beforeRun`、`beforeModel`、`beforeTool`、`afterTool`、`afterModel`、`afterRun`、`onEvent` |

不注册命令、provider、MCP，也不使用宿主的 `spawn_agent` / `team_*`。

每个工具名只注册一次。角色不是插件模块的常量，而是**会话的事实**：`setup` 只收到 `sessionId`
与 `cwd`；本适配器启动的会话把角色写进 session metadata（`herdchestra.role`），插件用
`getSession` / `get` 读回（`identity.mjs`）。三级读法都失败时角色是 `unproven`，`beforeTool` 拒绝**任何**
面工具（`SESSION_ROLE_UNKNOWN`），不会因为缺省值看起来像 Manager 就把 Manager 的面发出去。
**每个钩子都先 `await session.resolveRole()`**，`beforeTool` 也不例外：Plan 会话的第一次工具调用就是
`js-plan`，少了这一步它会被当成 Manager 面拒掉。

## 进入 Plan（真实入口）

Plan 是独立顶层会话。它只有一处入口：

```js
import { ClineCore } from "@cline/core";
const { startPlan } = await import("/abs/path/to/herdchestra/src/host-cline/index.js");

const cline = await ClineCore.create({ backendMode: "local" });
const started = await startPlan({ host: cline, text: "把重试改成指数退避", root: "/repo/demo" });
// started = { ok: true, sessionId, draft: "/repo/demo/plans/....md" }
```

`startPlan` 起一个会话：系统提示是 `PLAN_PROMPT`（Plan 自己的角色段，不含 `COMMON`/`MANAGER`），
`localRuntime.pluginPaths` 指向本插件，`config.sessionId` 是适配器在启动**之前**生成、
并已写好角色事实（`.Herdchestra/<sessionId>.role.json`）的那个 id，首个提示就是操作员原文。
会话自己的插件在 `setup` 里按这个 id 读回角色，于是它拿 `js-plan`、Plan 台账与 Plan 门禁。
台账、Scope 日志与草稿都在**会话自己的工作区根**下（`workspaceInfo.rootPath`），不在适配器的仓库里。
**底稿是用户可见产物**：`plans/<name>.md` 在工作区根，绝不在 `.Herdchestra/` 下（那里是适配器自己的状态）。
Plan 除这份产出物与操作员附件之外全程只读；`js-plan` 的规则把这三件事一起告诉模型。
`startPlan` 在创建会话**之前**就建好底稿目录（`plans/`）：`write` 是目录内临时文件的
原子替换，不会自己建目录。建不出来时它回 `{ ok: false, reason: "DRAFT_DIR_UNWRITABLE", message }`
并且不启动会话；否则一次「启动成功」的 Plan 会写不下自己唯一的产物，交接也永远读不到底稿。

已经在插件会话里的调用者用 `plugin.openPlan(text, { root, name, model })`，走同一个 `startPlan`。
`model` 可以由调用者给；不给时走 `SessionState.resolveModel()` 的同一条梯子（最近请求 → setup 上下文 →
环境变量 → 宿主会话记录）。梯子全空时入口回 `MODEL_UNKNOWN` 并列出试过的来源，
**不会用 `"cline"/""` 起一个必然失败的会话**。
`root`/`name` 缺省时取本会话的 `root` 与 `sessionId`；无论走调用者自带的入口还是适配器自己的
工厂，两条分支拿到的是同一组派生值，所以同一会话的 Plan 底稿只有一份。

`modelId` 必须给出（宿主 `CoreModelConfig` 要求非空模型）。缺省时 `startPlan` 与交接
在调用宿主之前回 `{ ok: false, reason: "MODEL_MISSING" }`，而不是让宿主 schema 的 zod
消息代替拒绝理由。

交接与 `plugin.openPlan` 的连接取自 `SessionState.resolveModel()`：`beforeModel` 把每次请求的
provider/model 记进 `noteModel`（最优先的读数）；还没有请求时依次退到 setup 上下文、
`CLINE_PROVIDER_ID`/`CLINE_MODEL_ID`、宿主会话记录（`getSession` 的行与 metadata 都带
`provider`/`model`）。全都没有时回 `{ ok: false, reason: "MODEL_UNKNOWN", detail: { tried } }`，
不会拿 `cline` 或空模型顶上。回执里的 `modelSource` 说明用的是哪一级。
**子代理不走这条梯子**：`fork`、fission lane 与 Plan 的 `devops` 用
`resolveTaskModel()`/`resolveTaskConnection()`——先读 `HERDCHESTRA_TASK_PROVIDER_ID` +
`HERDCHESTRA_TASK_MODEL_ID`（两半齐全才算），命中报 `source: "task"`，未设时回落主梯子并报
`source: "main"`。顶层 Manager/Plan 会话仍是主模型。

## 交接：Plan → Manager

Plan 在「待执行」提交空账 `review([])` 时，`relay.startManager(draft)` 经 `handoff.mjs` 调用
`ManagerSessions.startManager`：在同一主机句柄上开一个独立会话，`config.sessionId` 与
`herdchestra.role = "manager"` 的角色事实在启动前写好（同 §2.3），首个提示就是底稿字节。
会话起好但没投递时它会被停掉，回执带 `discarded`，Plan 台账不动。

## 提示词

- Manager 的系统提示 = `compose` 的 `COMMON + MANAGER`，导演消息另起一段；只投递一次。
- coder 与 devops 的系统提示 = `COMMON + WORKER`，逐字相同。
- Plan = `PLAN_PROMPT`。
- 本目录不存提示词副本；原文只有 `src/compose/prompt/` 一份。
- 投递通道：会话的完整提示是 `registerRule` 的一条规则（`systemPromptRule`，按请求读当前角色）；
  由本适配器启动的会话在 `start` 时也把 `systemPrompt` 交给宿主；worker 的提示是
  `createDelegatedAgent({ prompt })`。`beforeModel` 返回的 `systemPrompt` 会被宿主丢弃。

## 程序执行

`js-*` 的工具参数是 `code`。这段代码是**真实 JavaScript**：它被包进一个 async 函数执行，
所以 `await`、`for`、`if`、`try`、箭头函数、`Promise.all` 都能用。作用域里只有
`compose` 为该角色决定的成员，只以 `this.xxx` 一种写法存在；顶层原语只以裸函数名存在（根 §3.1）：
`fork`、`join`、`review`，以及 Plan 的 `devops(verboseIntent)`。`this.devops` 不存在
（根 §1.1 item 3：它是 join 语义，不是成员）。

**Plan 的 devops 是一个专属 worker**（`devops.mjs`）：一个会话只建一个运行时句柄，固定名
`plan-devops`，第一次调查时才懒建；之后每次调查都是同一个句柄的 `handle.run(verboseIntent)`，
所以 DevOps 记得自己查过什么，也不再为每次调用付启动成本。句柄被操作员中断 abort 后是 `dead`，
下一次调查回 `PLAN_DEVOPS_DEAD`，绝不悄悄起第二个。同一时刻至多一个调查在飞
（`DEVOPS_IN_FLIGHT` / `DEVOPS_ALREADY_IN_FLIGHT`），报告原样返回，不做二次摘要。

§3.4 的 `class Js extends JsProgram { async run() {...} }` 同样可执行。

### `scope` 参数（根 §6.2）

每次非豁免工具调用都要带一个 `scope` 注解，六个工具（`js-manager`、`js-worker`、`js-plan`、
`js-review`、`fork`、`join`）都不是豁免工具。注解由 `scope-schema.mjs` 的 `SCOPE_SCHEMA` 描述，
并写进每个工具的 `inputSchema`——工具对象保持闭合，但 `scope` 是它声明并 `required` 的字段，
所以合规调用写得出来，宿主的前置校验不会先把注解拒掉。

```jsonc
// 一次会话的第一次调用
{"code": "return 1", "scope": {"kind": "resetScope", "newScopeGoal": "把重试改成指数退避", "newScopeRetentionBudget": 6}}
// 之后每个模型回合：一条真注解，同回合其余调用 meToo
{"code": "return 2", "scope": {"kind": "keepLast"}}
{"code": "return 3", "scope": {"kind": "meToo"}}
```

四种 `kind` 的分支各自闭合：`resetScope` 只收 `newScopeGoal` 与 `newScopeRetentionBudget`，
`removeLast` 只收 `replacement`（非空白），`keepLast`、`meToo` 只收 `kind`。
`SCOPE_SCHEMA.description` 只留一句「见系统提示词的 Scope 一节」加字段形状，字段描述也只说字段本身：
机制在系统提示词里写一遍就够，每个工具再复制一份长说明只是浪费上下文（根 §1.1 item 5）。
模式只是注解的描述；预算是否已建立、是否已耗尽、本拍谁是真注解，仍由 `core/scope` 的书在
`beforeTool`/`afterTool` 判定。

一次调用就是一台程序：成员 `park` 时调用立即返回回执，程序保持挂起，
由另一台程序 `unpark` 恢复。回执形状来自 `core/receipt.mjs`。

## 工具结果进请求时一律是 CUE（§6.3）

宿主的 provider 层把工具结果转成模型可读文本时，对结构化值走
`JSON.stringify`：`@cline/shared` 的 `Dh(output, isError)` 在 `output` 是对象时
回 `{ type: "json", value }`，再序列化成 JSON。于是被 `beforeTool` 拒绝的调用
到模型眼里是 `{"error":"js-manager is not available to this session"}`——转义噪声、
多行回执里全是 `\n`，正是 §6.3 禁止的形状。宿主源码不改，所以在**请求投影**里换掉它：

`request.mjs` 的 `toCueToolResults(messages)` 在 `SessionState.projectMessages`
的第一步运行（早于接力栅栏、Scope 投影与 Pair Hint），把每个 `tool-result` 部件里
**结构化**的 `output` 渲染成 CUE：

| 部件形状 | 模型读到的文本 |
|---|---|
| `{ type: "json", value: {…} }`（拒绝回执、程序回执） | `toCue(value)`，如 `{error: "…"}`，多行值是 `"""` 块 |
| `{ type: "text", value: "…" }` | 原样字节，不加引号、不重排 |
| `output` 是字符串，或部件没有 `output` | 原样，或留空 |
| `toCue` 拒绝的值（循环引用、函数、bigint、symbol、抛错的 getter） | `{error: "UNRENDERABLE_TOOL_RESULT: …"}`：内容扣留，调用照常应答 |
| `{ type: "text", value: <非字符串> }` | `toCue(value)`；渲染不了时同上 |

四条不变量：

- **只动 `tool-result` 部件的 `output` 字段。** `toolCallId`、`toolName` 以及宿主放在
  旁边的键都不变，因为 Pair Hint 用 `JSON.stringify` 逐条比较已呈现的消息
  （`injection.mjs` `assertPrefix`），键序或键集变化会被读成「消息被改写」。
- **操作员的原文一字不改。** 投影只重写工具结果；`user` 消息的文本（含缩进、换行）
  进入请求时与到达时逐字节相同。
- **幂等（按对象同一性）。** 已经可读的部件（字符串 `output`、`{ type: "text", value: <字符串> }`、
  没有 `output`）原样返回同一个部件对象；渲染过的部件第二次投影读到字符串 value 也原样返回。
  同一请求里的 CUE 字节与对象因此都稳定，Pair Hint 的前缀链不会因二次投影断裂。
- **渲染不了就失败关闭，不退回裸 JSON（根 §6.3）。** `toCue` 拒绝的值换成 CUE 拒绝回执，
  不是 `JSON.stringify`；只有连这条回执都渲染不了时才直接发纯文本。`text` 部件的 value
  不是字符串时按结构化值渲染成 CUE，渲染不了再走同一条拒绝。

## 已核对的宿主接口

全部来自 `../cline` 的源码（2026-10）：

- 插件对象：`AgentExtension`，`setup(api, ctx)` 第二参数是 `PluginSetupContext`
  （`session`、`client`、`user`、`workspaceInfo`、`logger`、`telemetry`，全部可选）。
- 入口扩展名：`PLUGIN_FILE_EXTENSIONS = [".js", ".ts"]`（`@cline/shared`）。
- 工具：`AgentTool { name, description, inputSchema, execute(input, context) }`。
- 钩子：`beforeRun` / `afterRun` / `beforeModel` / `afterModel` / `beforeTool` / `afterTool` / `onEvent`；
  `beforeTool` 返回 `{ skip, reason }` 时拒绝本次调用且 run 继续。
- 规则与请求副本：`registerRule({ id, content })` 的文本由宿主追加到每次请求的 system prompt 末尾；
  请求副本（relay barrier、Scope、Pair Hint、CUE 渲染）是 `beforeModel` 答案里的 `messages`。
  **这两项就是本适配器的请求改写入口**（根 §7.2）。`beforeModel` 的返回值只读
  `stop`/`messages`/`tools`/`options`：`messages` 换进本次请求（`agent-runtime.ts:1700-1711`），
  不进转录；返回 `systemPrompt` 会被丢弃——所以角色提示词走规则，不走钩子。
  `registerMessageBuilder` 虽然存在，但它的 `build` 只被宿主自己的 `prepareTurn` 调用，
  而该字段总有值，所以插件注册的 builder 在本宿主上永远不跑（`HACK.md` 有实测）。
- 子代理：`createDelegatedAgent` 与 `createDelegatedAgentConfigProvider`（`@cline/core`）。
- 会话工厂：`ClineCore.create` / `createRuntimeHost`，`start` / `startSession` 接受
  `config`、`prompt`、`sessionMetadata`、`localRuntime`；`getSession` 回带 `metadata`；
  `stop` / `stopSession` 停会话。
- 新会话的插件由 `localRuntime.pluginPaths` 加载（宿主解析路径，不接收模块对象）；
  `config.agentPluginPaths` 只喂 settings 侧的包加载器，不加载会话插件。
- 插件运行在 worker 或子进程，跨线程只传纯数据。

## 缺口

以下是核对后确认的**宿主限制**，不是待办：

1. **Manager 的工具面收不窄。** 宿主没有按角色禁用工具的接口：`disabledTools` 是全局设置，
   `toolPolicies` 是会话级。Manager 的「无读权限」只能靠 `beforeTool` 返回 `{ skip: true }`。
2. **`fork` 在 worker 沙箱里自己找到宿主 SDK，不再需要环境变量。** 插件跑在 `worker_thread` 里，
   `require.resolve("@cline/core")` 在那里是 `MODULE_NOT_FOUND`（`argv[1]` 是沙箱 bootstrap，
   `cwd` 是会话工作区）；但**按绝对路径 import 宿主的 `dist/index.js` 在 worker 里可以工作**
   （实测 612 ms，两个工厂都在）。`host-modules.mjs` 因此按「证据强度」爬梯子：
   `CLINE_CORE_MODULE` → 裸模块名 → 从 `argv[1]`/`argv[0]`/`execPath`/`cwd`/插件目录向上找
   `node_modules/@cline/core` → 已知安装布局（`/usr/lib/Cline/bin/desktop-backend`、`/opt/Cline/...`、
   macOS bundle、Windows 安装）→ `../cline` 源码树。候选路径先 `stat` 再 import，只接受导出本适配器
   所需条目的模块；结果按进程缓存，`hostModuleSource()` 说明是哪一级命中的。
   **连接（provider/model）走同一条「有证据才用」的梯子**：最近一次请求 → `setup` 上下文 →
   宿主自己的会话记录（`getSession`，行与 metadata 都带 `provider`/`model`）→
   `CLINE_PROVIDER_ID`/`CLINE_MODEL_ID`。两半必须同时具备，半条连接不算连接；
   `SessionState.resolveModel()` 是带 await 的形式，`startManager`/`openPlan` 用它（顶层会话）；
   `fork`、lane 与 `devops` 用 `resolveTaskModel()`（`HERDCHESTRA_TASK_*` 优先，否则回落主梯子），
   回执里的 `modelSource` 说明用了哪一级（`task` / `main`）。全都没有时仍按名字拒绝
   （`HostCapabilityMissing` / `MODEL_UNKNOWN`），不假装启动、不用缺省值顶替。
3. **`fission` 的 lane 由子运行时承载。** 每条 lane 是一次 `createDelegatedAgent`；
   宿主拿不到该工厂时，lane 创建失败，组合层按 §4.2 整组回滚，不留部分组。
   组操作本身在 `compose/fission` 里，`fissionPort` 只提供 `fission(owner, prompts)` 与五个步骤。
   **组记录是持久的**：`persistGroup` 把整组的创建事实作为**一行**追加到
   `.Herdchestra/<session>.fission.jsonl`（§4.2 第 4 步的「原子持久提交」）。一行而不是每条 lane 一行，
   因为写了一半的 lane 集合不是一个组；`appendJsonLine` 的单行追加让读者要么看到整条记录，要么看不到。
   写成功才回 `durable: true`，失败回 `{ ok: false, durable: false, reason }`，不谎报已提交。
4. **子代理的内置工具由宿主决定。** `createSubAgentTools` 决定子代理拿哪些宿主工具，本适配器无法收紧。
5. **回滚回执要如实。** `abortLanes` 回 `{ ok, released, orphaned }`：停不掉的 lane 记为 orphaned
   并留在表里，不会被算进 `released`；`control.unpark` 必须转发控制器的 `capabilities` 与 `deadline`。
6. **中途 steer 走子运行时自己的待输入通道。** `steer_message` 按 `sessions.get(sessionId)` 解析目标，
   而 `createDelegatedAgent` 建出的子运行时**不进入** `sessions` 表，所以那条事件通道到不了它。
   可到达的是运行时自己的 `consumePendingUserMessage`：agent 循环在每个请求边界读一次
   （`agent-runtime.ts:1689`），`runtime.mjs` 在 config provider 上装的实现**先排本适配器的队列**，
   再把宿主的消费者链在后面。同名 `fork` 因此把 `verboseIntent` 送进**正在运行的那一回合**，
   回执是 `{ name, steered: true, started: false }`；运行时在回合结束时报告它实际读了几条
   （`record.steered`），worker 表只结算这几条，没读到的那条留在运行时队列里由下一回合取走。
   宿主连这个成员都没有时，它进表的 `pending` 队列，等该 worker 的下一回合。
7. **`ask` 就是 `park` + `interrupt`，所以不需要宿主的问答接口。** 宿主确实没有「问一句话」的 API；
   审批往返（`capabilities.requestToolApproval`）在类型里有，但 `setup` 的 ctx 不带它。这不再是问题：
   `this.ask(question)` 让程序挂起（`ParkTable`），工具回 `{ ok: true, asked: true, question, interrupt: true }`，
   本回合结束，控制权回到操作员；操作员在常规对话里答复后，`beforeModel` 的 `resumeAsked` 把这句话
   注入挂起点（`unpark(name, answer)`），原程序从 `await this.ask(...)` 继续。
   `operator.mjs` 仍然接受嵌入方注入的 `ask` / `requestApproval` / `request`，那只是「能更快拿到答复」，
   任何一条缺席都不会变成拒绝——**适配器里不存在「无通道」这种拒绝码**。宿主的 `ask_question`
   工具只承载 2–5 选一，本适配器不用它。

   **恢复回答的是续体的真实结局，不是一次视图。** 一次 park 不结束 body：`ProgramCall#run` 记住
   body 的 promise（`#body`），工具调用当场回挂起回执，而 body 仍悬在 `await this.ask(...)` 上。
   `ParkTable` 的条目同时持有唤醒点与 `bodyPromise`；`resume(name, value)` 先装好「下一次挂起」的
   信号，再唤醒，然后与续体赛跑——body 返回就是 `completed`/`failed`，body 再次挂起就是新的
   `parked` 回执（新问题照旧登记进 `asked`）。所以 `await this.unpark(name, answer)` 的值是
   **原程序的结局**，`value` 是挂起表达式收到的那个答复。`ASK_FORBIDDEN_OUTSIDE_S1` 也随
   `error.code` 原样到达模型，不只是一个 message。
   **唯一的真实边界是挂起不跨进程**：`#asked` 只在内存里，宿主重启或插件重新加载后程序体接不回去，
   用合成的 user 消息重述问题不是原 promise 的 continuation，所以本适配器不做，由操作员重新发起。
8. **角色只能从 session metadata 读回。** `PluginSetupContext` 不带 metadata，插件必须自己
   `getSession`；宿主若改这条读法，`roleOf` 的 `source` 会变，测试先失败。
   **一次调用是顶层还是子代理，看 `snapshot.parentAgentId` 的值，不看键在不在。** 该字段在
   `@cline/shared` 的 `AgentRuntimeStateSnapshot` 里是可选的（`parentAgentId?: string | null`），
   `@cline/core` 0.2.0 的顶层 agent 实测**整个键都不出现**
   （键集：`agentId, conversationId, runId, status, iteration, messages, pendingToolCalls, usage`）。
   用 `"parentAgentId" in snapshot` 判断，会把顶层 Manager 读成 coder，于是它自己的面被拒
   （真机 trace：`{tool: "js-manager", role: "coder", declaredRole: "manager"}`）。
   `SessionState.roleFor` 因此只判值：`null`/`undefined`/缺键三者同义——没有父代理。
9. **加载路径的实测范围，按观察点分开写。** 贡献是在 `setup` 内惰性注册的，所以
   `resolveAndLoadAgentPlugins` 返回空工具列表**不是缺陷**，只是看错了观察点。
   - 真实加载器 + **sandbox 传输**：`collectPluginContributions` 收到 1 个 plugin、6 个工具、2 条规则。
   - 进程内加载器（`loadAgentPluginFromPath`，`../cline` 源码直跑）：能装载 `index.js`、
     注册工具、执行程序与 `call`。
   - 模型驱动的端到端会话已跑通到工具闸门：`PLAN_PROMPT` 进入 `system_prompt`，`js-plan` 通过角色闸门与
     Scope 准入；provider 凭据失效（`unauthorized`）前没有拿到一次成功的 `tool_use` 结果。

10. **会话结束时 worker 由 `onDispose` 停掉。** `PluginSetupContext.onDispose`（`contribution-registry.ts:219`）
    是宿主给插件的唯一收尾钩子。`setup` 已注册它，`SessionState.release()` → `WorkerTable.abortAll()`
    中止本会话启动的每个 runtime，回执 `{ ok, released, orphaned, roster }` 与 `abortLanes` 同形
    （`roster` 是日志路径 `{ ok, path }`）：中止抛错的 runtime 留在表里、如实列为 `orphaned`。
    宿主不给 `onDispose` 时本适配器没有别的收尾手段。

11. **花名册按回合记账并落盘。** `WorkerTable` 的 `join` 判据是「在途回合 + 未认领的完成记录」，
    不是「还有没有活着的名字」：同名 `fork` 排队（steer）时该回合在排队那一刻就已计入在途，
    所以「上一回合刚结束、下一回合正要开始」的空隙不会让 `join` 提前回 `null`。
    每次变化写一条记录到 `.Herdchestra/<session>.workers.jsonl`；**这份日志是唯一来源**，
    `WorkerTable` 建表时就回放它（崩溃留下的半行被忽略），没有第二份快照可与之矛盾。
    `restoreRoster` 的重放用 Map 记账，不再对完成记录做线性查找；`settle` 记 `carrierText`，
    `steer` 记 `delivery`，所以重载后的表与活表交给 `join` 的记录逐条一致。
    `claim` 记录意味着该答案已交付：重放时完成记录（连同文本）被删除，下一次加载的
    `snapshot()` 里没有已认领的答案。仍被记为在途的 worker 继续被 `join` 等待；对这类没有本进程句柄的名字，同名 `fork` 回
    `WORKER_RUNTIME_GONE`，不把它收进没人排空的队列。`worker-roster.test.mjs` 用 fast-check
    状态机固定这三条不变量的证明。

12. **操作员按 Esc，取消传播到全部子孙（根 §1.1 item 2）。** `guard.mjs` 在 `afterModel` 的
    aborted assistant 消息与 `afterRun` 的 cancelled/aborted/interrupted 状态上判
    `operator-interrupt`，只对面向用户的角色（Plan、Manager）调
    `SessionState.cancelDescendants()` → `WorkerTable.cancelAll()`：对每个仍在运行或空闲的句柄调
    `abort("OPERATOR_CANCELLED")`（不 dispose），把子树标记为 `cancelled`（之后同名 `fork`/steer 回
    `WORKER_CANCELLED`，重载后仍拒绝），并给每个欠着的回合补一条 `finishReason: "cancelled"` 的完成
    记录，使 `join` 如实报告而不是永远等待；已经到达的未认领结果原样保留。`abortAll()` 仍是会话结束
    的收尾，会清空队列，二者不复用。`this.ask` 的 park 停机（`parkStopOwed`/`parkStopped`）不是
    操作员对工作的中断，因此不传播——否则提问会把被问的那件工作一起杀掉。
    Plan 的专属 `plan-devops` 不在 worker 表里：`cancelDescendants()` 直接调 runner 的
    `cancel()`（abort 那个句柄），`release()` 收尾时也中止它；被中止的句柄之后回
    `PLAN_DEVOPS_DEAD`，不会悄悄起第二个。

13. **无人值守：Nudge 走宿主真正会读的通道。** 根 §2.2 说顶层 `Plan`/`Manager` 只有两个出口
    （`S1` 的 `ask`、验收上一任合格后的终局停止），其余回合结束都要被拦截。核对宿主源码后：
    `completionPolicy.completionGuard` 只有宿主自己的 runtime builder 能写，`afterRun` 的返回值被
    丢弃，`afterModel` 只被当作「停」的控制——三条都不能续跑。真正可用的是 `beforeModel` 的
    `appendContext`（下一次迭代刷成一条 user 消息）与 `emitEvent("steer_message")`（下一次请求消费）。
    于是 `guard.mjs` 三处分工：`afterModel` 记录这次自然停止尝试，`beforeModel` 把 Nudge 送进下一次
    请求（并先重新判定，出口已到达就丢弃），`afterRun` 用 `steer_message` 真续跑并如实回报
    `continued`/`delivery`。worker 的回合结束、run 失败/取消、带工具调用的回合都不是停止尝试。
    `nudge-ask.test.mjs` 用 fast-check 证明三条定理（ask 门禁、续体往返、永不自然停止）。

## 测试

```sh
npm run test:host-cline
```

- `host-cline.test.mjs`：工具、规则、钩子、程序执行、park/unpark、面门禁、worker 表、接力、fission 步骤。
- `scope-schema.test.mjs`：六个工具的 `scope` 模式；fast-check 固定「模式放行的注解 = 书接受的注解」。
- `plan-entry.test.mjs`：`startPlan` 的请求形状与拒绝、角色读回、`planHandoff` 的会话与回收。
- `worker-roster.test.mjs`：`join` 的回合会计、并发 `join`、花名册日志的回放与中途重载；fast-check
  状态机固定「有在途或未认领就不回 `null`」「每条完成记录恰好被认领一次」「重载后的花名册与保存的一致」。
