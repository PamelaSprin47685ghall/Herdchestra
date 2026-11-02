# `src/host-omp` 包级规格说明书

> 本文件是根 `AGENTS.md` §7.3 在本包的展开。根规格是唯一语义来源；两者冲突时以根规格为准，并立即修正本文件与代码。
> 核对基准：`../oh-my-pi`，`packages/coding-agent` 版本 18.14.1（2026-10 读取源码，逐条列在 `host-facts.mjs` 与 `host-binding.mjs` 顶部）。

## 0 这个包做什么

把万象术接到 Oh My Pi（OMP）上，只注册一个扩展，不使用宿主的 vibe 模式、`vibe_*` 工具或 `sessionModes`。

装载：包内 `package.json` 声明 `omp.extensions: ["./index.mjs"]`，扩展默认导出 `herdchestra(api)`（宿主只传一个参数，见下），在 `pi.on("session_start", ...)` 里按会话建立适配器。宿主发现该包时，同目录的 `agents/` 也一并被发现（`task/discovery.ts:119` 把 `<package root>/agents` 加入搜索目录）。

**实测装载方式（omp 18.14.1）：自动发现未生效，只有 `--extension` 可靠。** 在真实宿主里分别放置普通 `.mjs` 探针到 `.omp/extensions/`、用 `.omp/settings.json` 的 `{"extensions": [...]}`、以及放到 `~/.omp/agent/extensions/`，三者都没有触发 `session_start`；显式 `omp --extension <path>`（或 `-e`）每次都可靠加载。`package.json` 的 `omp.extensions` 清单是否会被已安装的包发现，未实测。因此上面两处「随包发现」的表述只在包被显式加载时成立。

**默认加载通道：作为已安装 plugin。** OMP 发现扩展的默认路径是 `getAllPluginExtensionPaths`（`loader.ts:700`）→ `getEnabledPlugins`：枚举两个根——用户根 `getPluginsDir(home)`，以及存在 `.omp/`/`.git/` 锚点时的项目根 `<anchor>/.omp/plugins`——每个根读 `node_modules/<pkg>/package.json`，取 `omp`（或 `pi`）字段作为清单；清单的 `extensions` 数组按相对包根解析成入口文件（`loader.ts` `resolveManifestEntryFiles`，文件项直接加载）。因此本包的 `package.json` 写 `"omp": { "extensions": ["./index.mjs"] }` 即构成默认加载入口，无需 `--extension`。同一个包根下的 `agents/*.md` 也进入 agent 搜索路径（`task/discovery.ts:113-120` 对每个 `listOmpExtensionRoots` 根追加 `<root>/agents`），三份 agent 定义随之可用。包必须真的出现在某个根的 `node_modules` 里，并在该根 `package.json#dependencies`（或 `omp-plugins.lock.json#plugins`）中声明；否则不会被枚举。

**真实宿主没有工具调度器（实测 + 源码核对）。** `loader.ts:454` 是 `await factory(api)`，工厂只收一个参数，`options.dispatch` 永不由宿主提供；`ExtensionAPI` 无总调度方法；`ctx.invokeTool` 只在工具执行上下文里存在、且只委托给**同名**内置工具（`extensions/types.ts:877`），真实 `session_start` 的 ctx 上它并不存在（实测 `ctx.invokeTool is not a function`）；`ExtensionContext` 的 `sessionManager` 只读，没有 `branch`/`append`/`newSession`。因此适配器无法替模型跑 `task`、`write`、`ask`。**但 `fork`/`join` 与 Plan 的 `devops` 不依赖调度器**：worker 和 DevOps 都是本适配器经宿主 SDK（`api.pi.createAgentSession`）自建的会话（`lane.mjs`/`ports.mjs`），`HostBinding` 只保留宿主状态。测试里注入的 `dispatch` 不再有消费方，只是构造参数的遗留；它不代表真实宿主能力。

## 1 文件与职责

| 文件 | 职责 |
|---|---|
| `index.mjs` | 扩展入口、`HostAdapter`、可见工具集计算 |
| `host-facts.mjs` | 核对过的宿主事实表（含源码位置）、角色判定、路径 |
| `host-binding.mjs` | 本会话的宿主状态：`pi`、SDK 命名空间、当前事件的 `ctx`、`ui`。**没有**调度器、`runAgent`、`spawn`、`steer`、`progressOf`：worker 与 Plan 的 DevOps 都是本适配器自建的会话（`lane.mjs`），没有第二条路 |
| `hooks.mjs` | 六个钩子：`tool_call` 越权与 scope 准入、`tool_result` scope 结算、`before_agent_start` 提示词、`turn_start`/`turn_end` 回合边界、`context` 请求副本 |
| `fission-fanout.mjs` | §4.2 第 7–10 步的宿主侧路由：把一条 lane 的回合结束翻成 `Fission.settle(owner, index, result)`。判定权全在 compose（交付、ConvergenceReady、合并序、接管宣布），本文件只回答「这条 lane 属于哪个组、第几号」 |
| `spawn-gate.mjs` | §2.1/§7.3 的父侧身份闸：`before_subagent_spawn` 只放行定义名为 `coder`/`devops` 的子代理，其余返回 `{ block: true, reason }`；合法子代理回 `{ model: "@task" }`（根 §1.1 item 6） |
| `program-tools.mjs` | `js-manager` / `js-worker` / `js-review` 三个工具 |
| `program.mjs` | `JsProgram` 基类、源码编译、成员绑定 |
| `worker-port.mjs` | `fork` / `join` 工具与 `TaskPort` |
| `lane.mjs` | worker lane：真实会话的创建、运行时接线、工具安装、握手授权、首回合启动、报告收取、subagent 身份 |
| `ports.mjs` | 组合层端口：`command`、`ask`、`relay`、`devops`、`fork`、`fission`、`control`（注意 compose 读的是 `ports.fork.fork` / `ports.fork.join`，键名是 `fork` 不是 `worker`） |
| `session-control.mjs` | 本会话自己的控制面：`reason()`（为何现在不能接管）、`newConversation()`（`newSession`，接力的屏障）、`silence()`（`abort`+`clearQueue`，fission 的 present 替换）、`waitSettled()` |
| `surface.mjs` | `RoleSession`（一会话的全部状态与端口）与 `buildSurface` |
| `session-state.mjs` | 程序注册表、控制面、评审台账（持久化）、Scope 与 Pair Hint 书、语言绑定 |
| `request.mjs` | OMP 消息 ↔ Scope 条目、Pair Hint 消息 |
| `drift-log.mjs` | 请求诊断：`.Herdchestra/<session>.requests.jsonl`，**只记失败的请求与判据变化**（根 §1.1 item 1），一行 `{length, root, firstDivergence, failure}`；分段轮转，不整文件重写 |
| `tool-schema.mjs` | 参数 JSON Schema、工具结果构造；`present`（CUE 呈现）、`presentToolText`（宿主工具结果的 JSON 文本呈现） |
| `files.mjs` | 状态文件：原子写 JSON、追加式 JSONL |
| `agents/*.md` | `herd-manager`、`devops`、`coder`、`plan` 四个 agent 定义。前三份 `blocking: true`；`plan` 是 `blocking: false`——规划是持续的顶层工作，不该把调用方按在一次答复上 |

依赖方向：本包 import `src/core` 与 `src/compose`，不反向依赖；不 import 任何宿主包（纯 `.mjs`，无构建）。

## 2 已核对的宿主接口

全部结论来自 `../oh-my-pi/packages/coding-agent/src`，逐条列在 `host-facts.mjs` 顶部表格里。要点：

- **一会话一个 runner**：`sdk.ts:3560` 与 `task/executor.ts` 各自 `new ExtensionRunner(...)`，扩展工厂按会话重绑；`ctx.agent` 是本会话身份（`kind`、`id`、`name`、`depth`、`parentId`）。
- **身份判定只看 `ctx.agent.kind`**：`kind === "sub"` 才是子代理，`/tan` 克隆是 depth 0 的子代理。
- **系统提示**：`before_agent_start` 返回 `systemPrompt: string[]` 即整段替换。
- **请求消息**：`context` 返回 `{ messages }` 只改本次请求副本。
- **工具门禁**：`tool_call` 在审批之前触发，可返回 `{ block, reason }`；`tool_result` 在调用结束后触发，可返回 `{ content, details, isError, additionalContext }`。
- **子代理**：`task` 工具；`async.enabled` 默认 `true`，每次 spawn 是一个后台 job。`blocking: true` 的 agent 定义（frontmatter）改为内联执行，`task` 等到它结束才返回（`task/index.ts:1138`、`executor.ts:4339`）。
- **投递**：`pi.sendMessage` / `pi.sendUserMessage` 只投向**调用方自己的会话**；跨会话投递只有 `write agent://<id>`（`internal-urls/agent-protocol.ts#write` → `irc/bus.ts#send`）。
- **shell**：`pi.exec(command, args)`，参数是 argv，返回 `{ stdout, stderr, code }`（`exec/exec.ts:21`）。
- **工具名**：本版本有 `bash`，没有 `shell`；`task` 工具名是 `task`。

## 3 角色与工具面

| 角色 | 判定 | 可见工具（收窄后） | 编程面 |
|---|---|---|---|
| Manager | 顶层（`kind !== "sub"`）且名字不是 `plan` | `js-manager`、`fork`、`join` + 其余非替换工具 | `js-manager`（评估窗口开着时加 `js-review`） |
| Plan | 顶层（`kind !== "sub"`）且名字是 `plan` | `plan` + 其余非替换工具，但**没有 `fork`/`join`**（§2.2 只给五个能力，唯一代理通道是同步的裸原语 `devops(verboseIntent)`） | `plan` |
| coder | 子代理（`kind === "sub"`）且名字不是 `devops` | `js-worker` + 其余非替换工具 | `js-worker` |
| devops | 子代理（`kind === "sub"`）且名字是 `devops` | 同上 | `js-worker` |

判定顺序是 **kind 先于名字**（`host-facts.mjs` 的 `roleOfAgent`）：`kind === "sub"` 一律是 worker，名字只用来分 coder 与 devops；
只有顶层会话才看名字（`plan` → Plan，其余 → Manager）。**顺序不能反**：先看名字会让一个恰好叫 `plan` 的子代理变成 Plan，
拿到 Plan 的台账与单文件读写面。子代理永远不是 Plan（根 §2.2：Plan 不由 `fork` 创建）。

**没有 `agent` 事实不是 Manager**：`ctx.agent` 由宿主自己填（`sdk.ts:3571`），会话在它出现之前被绑定时，
我们什么都没读到。`roleOfAgent(undefined | null)` 因此回 `ROLE.UNPROVEN`，而 `UNPROVEN` 不是第五种角色，
是**读失败**：`laneFaceFor` 与 `visibleToolsFor` 都回空面，`surfaceAllowed` 对任何面都回 `false`，
`tool_call` 以 `SESSION_ROLE_UNKNOWN` 拦下本适配器的每个工具。此前这里回 `MANAGER`，
于是一个还没拿到 agent 的会话会静默获得 Manager 的面与它的评审台账——那是一个猜测，不是读取。
显式给出角色的路径（`workerSessionTools.install` 的 `role`、`bindSession` 的 `role`）不受影响。

收窄在 `session_start` 做一次，并在**阶段真的变化**时再做一次：`getActiveTools()` → 去掉 `REPLACED_TOOLS`（`read`、`grep`、`glob`、`write`、`edit`、`bash`、`eval`、`task`）→ Manager 再并上 `js-manager`、`fork`、`join`；worker 去掉 `js-manager` 与 `js-review`。`js-review` 已注册但不在任何常驻可见集合里（根 §2.3：只在评估窗口开着时开放）。**绝不在 `before_agent_start` 里调用** `setActiveTools`（根 §7.3：会让宿主重跑 policy preparation）；评估轮在 `turn_start` 开、在本轮第一次 review 被接受时关。

收窄只改模型看见的目录；**执行拦截在 `tool_call`**：Manager 调 `MANAGER_FORBIDDEN` 任一名字即 `block`；调不属于本角色的编程面（如 worker 调 `js-manager`）也 `block`。

## 4 工具

| 工具 | 参数 | 语义 |
|---|---|---|
| `js-manager` | `{ source, options? }` | 编译并运行 Manager 编程面程序 |
| `js-worker` | 同上 | worker 编程面程序 |
| `js-review` | 同上 | 只读评审面程序 |
| `fork` | `{ name, verboseIntent }`（身份由 verboseIntent 显式声明 `role: coder` / `role: devops`；无声明或声明两个一律拒绝，绝不按名字或默认值推断） | 立即返回 `{ name, steered, started }` |
| `join` | `{}` | 等第一个 worker 结束，返回其记录；被取消的子树回 `SESSION_CANCELLED` |

`source` 是 `class Js extends JsProgram { async run() {...} }`。编译用 `AsyncFunction`（`Object.getPrototypeOf(async () => {}).constructor`），模型自己写的循环、条件、模板字符串、原生字符串方法都按原样执行。成员取自 `src/compose/program/surface.mjs` 的 `SURFACE_MEMBERS`：角色没有的成员**不存在**，调用即 `TypeError`。

工具结果：文本内容是**程序回执**（core 的 `programView`）加 `value` / `error`，按 §7.2 呈现为 CUE。`run()` 返回 → `{...回执, value, error: null}`；`run()` 抛错 → `isError` 结果，`value: null`，`error: {name, message}`。一次 park 不结束程序体，所以工具**不**等程序体：它用 `Promise.race(程序体结果, park 信号)`，park 先到时以 park 回执作答，程序体继续挂起。**调用方读 `details.result`，不解析文本**：文本是给人（模型）看的 CUE，结构在 `details` 里（§7.2）。

审批：三个编程面都声明 `approval: "exec"`，由宿主的审批模式（`always-ask` / `deny` / `yolo` …）决定是否放行。适配器**不**在 `tool_call` 里替它们再判一次：那样会把操作员明确拒绝的调用放行。

## 5 程序生命周期

`buildSurface` 用 `toolCallId` 作为程序名：同一物理调用重放得到同一个名字。程序停在 park 时，回执即工具结果；`unpark` 由同一会话的另一个程序发起。观察台账按请求开合，且**每次调用先观察本会话所有存活程序**（含停在 park 的那个），否则 `unpark` 必然报 `STALE_CONTROL`。

**`ask` 就是 `park` + `interrupt`（根 §2.2、§3.3 例 7）。** `this.ask(question)` 在 Plan 的 S1 阶段把程序 park（`reason: "ask"`、`payload: { question }`），`ports.ask` 记录问题并回 `askedReceipt`（`{ ok: true, asked: true, question, interrupt: true }`），随后 `SessionControl.interruptTurn` 用**本事件自己的 ctx** 调 `ctx.abort({ reason })` 结束本回合（`extensions/types.ts` `ExtensionContext.abort`；每次 emit 新建一个 ctx，不得用 `session_start` 那个）。控制权回到操作员，操作员在常规对话中答复后，同一会话的新程序用 `this.unpark(name, answer)` 恢复原续体，答复成为 `await this.ask(...)` 的值。宿主的 `ui.input` 对话框（`extensions/types.ts:334`）只在**真的注入**时才用：它能在同一次调用里给答复，回执另带 `answer`；没有对话框也照样提问，**绝不回 `NO_ASK_CHANNEL`**。`S1` 之外的 `this.ask` 由组合层以 `ASK_FORBIDDEN_OUTSIDE_S1` 拒绝，程序不挂起。

`Manager` 面保留 `ask` 成员只为让越界调用读到具名拒绝；Manager 初任空账 review 是 `confirm`（独立验收，不推进里程碑），不是提问——无人值守，不等人。

生命周期：每次 `buildSurface` 按 `intent`（缺省为程序源码）一次性固定 core 的 `Deadline`，回执的 `remainingWaitMs` 由此报告；`unpark` 把控制请求的期限交给目标。程序体返回或抛出后，工具调 `ProgramSurface.settle` 结束程序并在其操作排空后退休名字；停在 park 的程序保持存活、不结束，等待 `unpark`。终止回执经 `ProgramSurface.finalReceipt()` 读取，因为 `settle` 已把名字从注册表退休。

续跑：park 的续体是内存里的 promise（根规格 §3.3「恢复不重跑」），进程重启不续跑（§3.3）。挂起点的 promise 按程序名存放，`unpark` 只结算那一个 promise，程序体的 async 栈因此原样保留，从挂起点继续；park 之前的语句不重跑。不得用抛异常表示挂起——那会销毁续体。

**能力集契约（core §3.3，2026-11 改动后）。** core 的 `unpark` 只问一件事：控制者是否持有**目标程序**携带的全部能力？目标的能力集是程序自身的事实（`ProgramState#capabilities`），不是 `unpark` 的参数；`targetCapabilities` 只是给「在别处镜像了目标集合」的宿主用的覆盖项，core 会拿它与目标的真实集合比对，不一致即 `CAPABILITY_DENIED`。

- `buildSurface` 用 `registry.begin(name, { capabilities: [roleForSurface(surfaceName, role)] })` 注册程序，所以一个会话里所有程序携带同一角色集（`manager` / `coder` / `devops`）。不传这一项，集合就是空的，任何控制者都「持有」它，检查失去意义。
- `SessionState.unpark` 传 `capabilities: [this.#role]`（控制者自己的集合），**不传** `targetCapabilities`：后者曾是「控制者集合」的镜像，在 core 改动后会被判为「声称的集合不是目标的」，即使两边完全相等也会被拒。
- 回执里的 `value` 由 core 以**不可枚举**属性挂上（`core/receipt.mjs` `withExtra`），`JSON.stringify` 会把它丢掉。`controlPort.unpark` 因此把它重新定义为可枚举自有属性——控制者的工具结果必须看得见被恢复表达式真正收到的值。

## 6 worker

worker 是**本适配器自己创建并驱动的真实会话**（`lane.mjs`，经 `api.pi.createAgentSession`），不经宿主 `task` 工具——扩展拿不到工具调度器（见 §0），所以 `fork` 走宿主注入的 SDK：`api.pi.createAgentSession`（`sdk.ts:1771`，`loader.ts` 的 `get pi()` 返回整个 coding-agent 模块）。

- **worker 的扩展运行时不会被宿主初始化，lane 必须按宿主自己的接线补上。** `runner.initialize` 只在启动路径被调用（`modes/runtime-init.ts:74` 的 `initializeExtensions`，供 print/RPC；`acp/acp-agent.ts:2980`），`createAgentSession` 不调用；`initializeExtensions` 也不在包的公开导出里（`index.ts` 的 `export * from "./modes"` 不含 `runtime-init`），`api.pi` 拿不到它。所以 `lane.mjs` 的 `wireWorkerRuntime` 用**同一套 action** 调 `runner.initialize(actions, contextActions, commandActions)`：每一项都转发到会话自己的 API（`sendCustomMessage`/`sendUserMessage`、`sessionManager.appendCustomEntry`/`appendLabelChange`/`get|setSessionName`、`getEnabledToolNames`/`getAllToolInfos`/`setActiveToolsByName`、`modelRegistry.getApiKey`+`setModel`、`thinkingLevel`、`serviceTierByFamily`、`model`/`isStreaming`/`abort({reason:"Interrupted by user"})`/`queuedMessageCount`/`getContextUsage`/`systemPrompt`/`runEphemeralTurn`/`compact`、`waitForIdle`/`newSession`/`branch`/`navigateTree`/`switchSession`/`reload`），`getCommands` 按 `get-commands-handler.ts` 汇总 runner 已注册命令、prompt 命令与技能命令。**不得填占位实现**：会话缺少任一被转发成员时回 `RUNTIME_INIT_FAILED` 并 dispose，绝不让 action 替不存在的操作答“已完成”。
- **fork 仍然“验证后启动”。** lane 建好会话、接好 action 后补发 `session_start`，只有本适配器带着 token 的回答（`bound` 且 `narrowed === true`）才放行；没有 runner 回 `EXTENSION_RUNTIME_UNAVAILABLE`，没答或没收窄回 `WORKER_NOT_AUTHORIZED`，都 dispose 并拒绝 fork。**绝不启动未授权 worker**——那会带着错误工具面且没有闸门。`hasHandlers()` 不是证据（别的扩展也能满足它）。
- **`restrictToolNames` 不能用。** 它会丢弃全部扩展工具（`sdk.ts:3618`），worker 连 `js-worker` 都没有。改用 `toolNames` 指定名字集合；工具**定义**由 lane 在会话建好后安装到该会话（`workerBuild.install(session)` → `registerTool`），因为 SDK 建的会话拿不到扩展运行时，事后 `setActiveTools` 收窄不了，而 `customTools` 会在创建时快照创建方的闭包。
- **worker 的工具必须为它自己构建，绝不借用 Manager 的定义。** 每个工具闭包读的是**拥有它的会话**；把 Manager 的工具对象交给 worker，worker 的程序就会跑在 Manager 的注册表、台账和 park 表上（真机 2026-11-01 实测：worker 的 `js-worker` 抛 `undefined is not an object (evaluating 'session.buildSurface')`）。所以 `HostAdapter` 的 `laneTools.workerToolsFor(workerPi, lanes)` 在**每个** worker 启动时新建一个 `HostAdapter`（`workerSessionTools`）；`LaneTable.start` 在建会话前调用它，建会话后调 `workerBuild.install(session)`：绑定 worker 会话、把五个工具（`js-worker`/`js-review`/`plan`/`fork`/`join`）写进**该会话自己的工具表**（`session.tools`；宿主没有这张表时才退回 `registerTool`），随后核对 `boundSession() === session`，不一致回 `WORKER_TOOLS_FAILED`。**不能走 `customTools`**：宿主在建会话时就把它们快照下来，带走的仍是建它的那个会话的闭包。**也不能用父级的 `registerTool`**：那会把 worker 的工具写进 Manager 的表，worker 永远看不到。
- **`fork`/`join` 的 port 必须按调用会话解析，不能在建定义时固定。** 五个工具定义在工厂期就注册了，那时还没有会话；工具里的 `forkTool({ port: (ctx) => ownerOf(ctx).session.worker })` 用调用方 `ctx` 找**调用所在会话**的适配器，再取它的 `worker` 端口。固定成 `this.#session.worker` 会让 worker 的 `fork` 与 `join` 读到别的会话（或读到一个尚未打开的表）——真机症状就是 `fork` 回 `started:true`、随后每次 `join` 都回 `NOTHING_TO_JOIN`。
- **worker 自己的 `fork` 是真的，但落在它自己的 roster 上（§4.1 给每个 worker `fork`）。** worker 的 `api` 由 `workerExtensionApi(parentApi, lanes)` 给出：`registerTool` 转发父 API，`pi` 是 `workerSdk(parentApi.pi, lanes)` —— 同一个真实 `createAgentSession`，但每次创建都走**发起 fork 的会话自己的** `LaneTable.start`（建面、初始化运行时、握手授权、启动首回合），返回已启动会话或 `undefined`。**roster 不共享**：`workerSessionTools` 不传 `lanes`，每个会话在 `RoleSession#open` 里开自己的 `LaneTable`。所以 worker 的 `fork` 把子 worker 放进 worker 的 roster，worker 的 `join` 从同一张表取，Manager 的 `join` 永远看不到它没启动的 worker；子 worker 的 `parentAgentId` 是发起 fork 的那个 worker（真机 2026-11-01：`w1` 起 `w1-child`，三段收窄 `Main`/`w1`/`w1-child` 各自独立，Manager 第二次 `join` 的 `roster` 只有 `w1`）。
- **实测对照**：先 `runner.initialize(真实 actions)` 再 emit `session_start`，worker 收窄成功（`narrowed:true`，角色 `coder`）；不初始化则 action 抛 `Extension runtime not initialized`、握手回 `narrowed:false`。actions 也不能传空对象——空 actions 会在收窄时报 `this.runtime.getActiveTools is not a function`。
- **`session_start` 必须自己补发。** 宿主只在 CLI/ACP 启动路径发它（`modes/runtime-init.ts:212`、`acp-agent.ts:3060`），`createAgentSession` 不发；而 worker 的钩子与工具收窄都挂在 `session_start` 上。因此 `lane.mjs` 在建好会话后、首回合之前调用 `session.extensionRunner.emit({type:"session_start"})`（`emit` 是公开方法）。缺了它，worker 会带着 Manager 面加全部宿主工具运行——真机就是这么失败的。
- **替换清单用宿主自己的工具名。** `REPLACED_TOOLS` 取自 `tools/builtin-names.ts` 的 `BUILTIN_TOOL_NAMES`：除了 `read/write/edit/bash/glob/grep/eval/task`，还有 `apply_patch`、`ast_grep`、`ast_edit`、`debug`、`lsp`、`wait`、`todo`、`web_search`。漏一个名字，worker 就会多留一个工具。worker **保留** `fork`/`join`（§4.1 把它们给每个 worker；worker 的 fork 落在自己的 roster 上，见上）。
- **`HERDCHESTRA_DEBUG=1`** 打印 `session_start` 收到的 `kind/name/id/depth`，以及收窄前后的活动工具集合。默认静默。
- **`fork` 的“已提交”信号是 `agent_start`，不是 `prompt()`。** `prompt()` 只在整回合结束时 resolve（`agent-session.ts:9032 await run`），等它会让 fork 阻塞 worker 的整个运行（真机实测 4.9 秒）。
- `fork(name, verboseIntent)`：先看同名 lane 是否仍在跑。在跑 → 把 verboseIntent 投进该 lane 当前回合（`session.steer`）或它的下一回合（空闲时 `session.prompt`），回执 `{ name, steered: true, started: false }`；不在跑 → 新建一个会话并**只启动第一回合就返回**，回执 `{ name, steered: false, started: true }`。fire-and-forget 由「不 await 该回合」保证，不依赖任何宿主 detached 开关。
- 会话创建与首回合启动是两次可失败的动作：创建失败报 `SESSION_CREATE_FAILED`，首回合启动失败报 `TURN_START_FAILED`（并 `dispose` 掉半成品会话，`join` 不会等一个不会发生的回合）。宿主没暴露 `createAgentSession` 时报 `NO_SESSION_FACTORY`，不起任何 lane。
- **`LaneTable.start` 的 `register` 参数决定这条 lane 属不属于调用者的 roster。** `fork` 用默认 `true`：它起的 worker 归调用者，`join` 收它。fission 用 `false`：lane 归**组**（`fissionPort.lanes`），从不进 roster，`join` 因此永远收不到一条尚未收敛的 lane。lane 本身照样完整构建、接运行时、握手授权、起首回合；差别只在表项。
- **relay 的继任者不是一条 lane，而是本会话自己的下一段对话**（§8.1）。它沿用同一个 `sessionId`、`agentId`、台账与角色，所以 `join` 的 roster 不受影响：现任 `fork` 过的 worker 在接力后仍由同一张表收，`DevOps` 绑定天然不变（§2.4 第 4 步）。`LaneTable.start` 的 `parentSessionFile` / `agentId` 参数只服务于显式的 SDK 会话创建，不参与 relay。
- `join()`：取**第一个交出报告的 lane**。回合以宿主的 `agent_end` 判定，且只在 `isTerminal !== false` 时算结束——非终局结束是续跑（重试、排队输入、后台任务），不算完成。报告就是该 lane 最后一条 assistant 文本，适配器不加摘要。已结束但未取走的报告会被 `join` 立即取走；每个回合只交一次，被 steer 后的新回合会再次交出。
- **steer**：见上；`steered: true` 只在 verboseIntent 真的进了在途 lane 时给出，lane 已结束则 `{ steered: false, started: false, reason: "LANE_ENDED" }`。
- lane 的 agent 身份按名字决定：`devops` 名字用 devops 定义，其余用 coder（`agentForRole`），与 §3 的角色表一致。

### 6.1 任务模型、取消传播与参数命名（根 §1.1 item 2/4/6）

**worker 跑用户配置的 task 模型。** 宿主 18.14.1 的 `createAgentSession` 接受 `modelPattern`（`sdk.ts:607`），`config/model-resolver.ts` 会把 `@task` 这类角色别名展开成 `settings.getModelRole("task")`（未配置时走该角色的内置优先级链）；宿主自己的 task agent 就声明 `model: "@task"`（`task/agents.ts:56`）。因此 `lane.mjs` 的 `LaneTable.start` 对 coder/devops 传 `modelPattern: "@task"`，Manager lane 不传（主模型）。`Lane` 的 `report()`/`start` 回执带 `modelPattern`/`modelSource`，所以回退到宿主默认时可见。`spawn-gate.mjs` 的 `modelFor` 对两个已声明 worker 角色回 `{ model: "@task" }`；`agents/coder.md`/`agents/devops.md` 的 frontmatter 也声明 `model: "@task"`（宿主支持的方言）。

**一次真实操作员中断取消整棵子树。** `RoleSession.operatorInterrupt(message)` 只在用户可见会话（Plan/Manager）上、`operatorInterruptOf(message)` 为真、且不是 S1 `ask`、不是 fission present 替换、不是 relay 意图时回 `{operator:true}`。`turn_end` 与终局 `agent_end` 都走 `index.mjs` 的 `cancelSubtree`：广度优先遍历根会话的 `LaneTable`、每条 lane 的 `AgentSession`、该会话自己的适配器与它自己的 `LaneTable`。每条 lane 调 `Lane.abort(reason)`，直接 `AgentSession.abort({reason})`（会话是本适配器创建的，不需要 `ctx.abort`）；**不 dispose**，转录与未取走的结果仍可读。`LaneTable.cancelAll` 标记 roster，之后 `fork`/`steer` 回 `SESSION_CANCELLED`，`join` 先交出已有结果再如实报取消；同一子树重复取消是幂等的。

**所有子代理任务描述参数统一叫 `verboseIntent`。** `fork(name, verboseIntent)`、`devops(verboseIntent)`、`LaneTable.start/steer`、`Lane.start/steer`、`workerExtensionApi/workerSdk` 的选项、`TaskPort.fork` 与 `host-binding` 的旧方法（已删除）都用这一个名字；deadline 的 `intent` 与 relay 的 `briefId` 不在改名范围。

### 6.2 花名册与无人值守出口

**`LaneTable` 是花名册，不是进程缓存。** `fork`、`steer`、`settle`、`claim` 都是持久事件，随会话状态落盘；
表建好时按记录重放，所以插件热重载或冷启动之后，在途 lane 仍在表里，`join` 不会对着一个活着的 worker 答
「没有可收集的结果」。三个宿主同一条规则，`host-opencode/spec/worker-roster.test.mjs` 用 `fast-check`
在任意交错上证明它（`src/AGENTS.md` §3.1a）。

**结果按回合计，日志是唯一来源。** 一个 worker 是名字，它的工作是**一串回合**：每次 `start` 或
`steer` 回执真的起了工作，就多一个回合，每个回合恰好欠一个结果（§4.1：`join` 交回的是「先回来的那个」，
被继续的 worker 会再回来一次）。
- `#settle` 每次都**追加**自己的结果（按结束顺序），绝不按名字替换；`steer` 与续跑**不撤销**任何结果——
  没人 `join` 过的回合是 worker 做过、Manager 还没看到的工作，撤掉就是静默丢数据。
- 回合一结束，`SETTLE` 记录就同步写进日志（`Lane#finish` → `#settle` → `#record`）。所以**日志是唯一
  结果来源**：重放出来的 `unclaimed` 就是「没人取走的回合」，`#settleRestored` 只照单入队，**绝不**再从
  lane 状态为一个已经结束的回合造第二份结果（`#settleTail`、`adopt()` 因此都不为已结束的 lane 结算）。
- `owed()` = 未结束的 lane + 队列里未取走的结果 + 日志里仍 `running` 的条目；`pending()` 只看未结束的
  lane，因为已结束 lane 的报告已经在队列里。
- `Lane.steer` 按 **lane 自己的** `#settled` 分岔，不按宿主的 `isStreaming`（那个标记在整个 in-flight
  括号内为真，跨过回合终局）：未结束 → 投进在途回合；已结束 → `start()` 开下一个回合。
- **花名册重放用 Map，不再 `findIndex`/`splice`**（根 §1.1 item 1）：`replayRoster` 按名字维护未取走结果的队列，
  一次 claim 只 `shift` 该名字的队列，最后按 settle 序号排序一次；旧实现每次 claim 都线性扫描整个结果数组，
  长会话因此是 O(n²)。
- **被 claim 的回答文本不再持久**：`WorkerRoster` 在 claim 时把对应 SETTLE 记录的 `text` 抹掉并原子重写日志。
  小日志（≤64 条记录）每次 claim 都压实，大日志只在陈旧文本达到 64 KiB 时压实，所以代价是摊销的，
  不会变成每次 `join` 重写整份文件。未 claim 的文本照旧保留；重放永远不为已 claim 的结果交回文本。
- 重建表时**只有一条接管路径**：新表从日志认识 worker，仍在跑的会话经 `runningSession`
  （`surface.mjs` `#laneSessions`）交给 `adopt()`，并带 `observeFrom` 不重放旧事件。旧表由
  `LaneTable.detach()` 摘下监听（不 dispose 会话），等着它的 `join` 以 `SESSION_DISPOSED` 结束。
  `#laneSessions` 在重建前**不清空**：先填好仍在跑的会话，再建新表，接管查找才看得见它们。日志里仍
  `running`、但本进程没有任何活会话的条目属于**上一个进程**，新表把它 `settle` 成 `WORKER_LOST`——
  否则 `join` 会对着一个永远不会到来的回合空转（每个宏任务醒来一次，直到进程被杀）。
- `LaneTable.start` 的**同步** `prompt` 抛出也会被捕获：删表项、写 `RELEASE`、清掉本次回合可能已入队的
  错误结果并 dispose，回 `TURN_START_FAILED`。首回合被宿主拒绝（`submitted.ok !== true`）走同一条清理；
  `join` 不会收到一个 `fork` 已报 `started:false` 的幽灵结果。`#settleTail` 对**已经结束**的回合
  （同步结束、或接管时结束先到）只入队一次，靠 `Lane#turnId` 区分，不会因为 sink 永不触发而丢结果。

**无人值守出口在 `nudge.mjs`。** `nudgeFor({ role, ledger, askedInS1, handOverComplete })` 是纯函数：
只有 `ask`（Plan 的 `S1`）与终局（Manager 到 `done`；Plan 到「待执行」且交接已完成）两个出口；
其余一律 `{ stop: false, nudge }`，文本列出账本里尚未完成的全部事项并附上 `NUDGE_MANDATE` 的常授权口令。
规则写成**出口的正向清单**：没人想到的新台账状态会落到「nudge」（继续干活），而不是「stop」（丢活）。
送达方式（`index.mjs` 的宿主调用）与台账是否诚实（`core/review.mjs`）都不在这里决定。
`SessionState.markAskedInS1` / `askedInS1` 是 S1 提问的持久标记。`askOperator` 在 `interruptTurn`
**之前**置位，`RoleSession#turnExit` 在**每一次终局回合评估**里清掉（不论判定是 `ask-s1`、人按停还是
nudge），并同时把该回合记进 `NudgeBook`。只在 `ask-s1` 时清会漏掉「提问自己的 abort 被宿主写成
`Interrupted by user`」的那次：标记留着，下一回合的停止被误判成合法的 S1 等待，会话从此不再被 nudge。
`Manager` 从不提问，所以这个标记不会替它掩盖未完成的工作。

**nudge 的送达回执要检查。** `deliverNudge` 回 `{ok, reason}`；投递失败时 `index.mjs` 往
`.Herdchestra/<session>.requests.jsonl` 写一条 `phase:"nudge"`、`code:"NUDGE_UNDELIVERED"` 的
`gateStateRecord`。不重试（第二次投递会被读成系统反复催促），也不假装送达。

**人按停不是「代理自己想停」（2026-11-01 实测修复）。** `turn_end` 钩子把本回合的 assistant 消息交给
`RoleSession#noteTurnEnd`，`turnExit` 用 `compose/nudge.mjs` 的 `operatorInterruptOf` 读它：omp 对操作员中断写的是
`stopReason: "aborted"` + `errorMessage: "Interrupted by user"`（`agent-session.ts:11240` 的 `USER_INTERRUPT_LABEL`
分支），于是判 `operator-interrupt`、不投递 nudge、并清掉这一回合的记录；压缩/切换导致的内部 `aborted`（`errorMessage`
不含 interrupt/abort/cancel）照常判定。实测会话 `01a1958e`：操作员在 `05:22:27.950Z` 中断首回合，adapter 在
`05:22:27.974Z` 投了 nudge，24 ms。

**`this.ask` 的宿主侧（`surface.mjs` 的 `ask(question, { program })`）。** 成员在 `surface.mjs` 里先 park
（`reason: "ask"`、`payload: { question }`），再经 `ports.ask` 走 `askPort`：把问题记成该会话的未决提问并
答一份带 `interrupt: true` 的回执，然后 `SessionControl.interruptTurn` 调 `ctx.abort()` 结束本回合。
`Manager` 与 worker 调用它读到的是 `ASK_FORBIDDEN_OUTSIDE_S1`（core `askAllowed`），**程序不挂起**。
首任 Plan 的第一次空账 review 也走同一形状（`askOperator`，根 §2.4 第 1 步）。

**Pair Hint 的链只存摘要（根 §1.1 item 1 的 core 切换）。** `PairHint#presented` 已删除；`PairHint#chain` 返回冻结的
`Chain`（`length`、逐消息 `digests`、`root`、`pairIndex`、`digestAt(i)`、`pairFreeDigests()`、`withoutPair()`、`equals()`），
冷启动可直接重建。`session-state.mjs` 的 `projectionBoundary` 传 `presented: hint.chain`；`freshCompaction` 不再比较全文，
而是用 `core/pair-hint/chain.mjs` 的 `messageDigest(projected[summaryIndex])` 与 `chain.digests[index]` 比较：相等即同一段
压缩历史，否则是一次新的压缩。旧日志里的 `messages` 由 core 折叠成新形状，适配器不读全文副本。

## 7 提示词、Scope、Pair Hint

- 系统提示 = `common + manager`（Manager，再接导演消息）或 `common + worker`（coder 与 devops 逐字相同）。
- `context` 顺序：**接力屏障 → Scope 投影 → Pair Hint 合成对**。三者都只改请求副本；宿主的持久历史从不被改写。
- **投影失败不会变成「没人检查的请求」（§8.1 a 的直接后果）。** OMP 不给 `context` 钩子拒绝请求的通道：处理器抛错只会被 `emitError` 报出来，宿主照旧发送它自己构建的那份副本。所以 `createContextHandler` 自己决定一份能守住的副本，并写下一个持久判据：
  - 屏障失败 → 副本 = 继任提示 + fence 已经算作继任者的那条操作员消息；Scope 不运行（第一份请求没有本任期的边界）。fence 连提示都没有时，副本只有那条操作员消息（不编造提示，也不退回未屏障的请求）。
  - Scope 抛错或拒收 → 副本 = **只有**操作员最新那条消息（未投影的整份历史绝不发出，那正是 retention 要删的东西）；请求里没有操作员消息时，副本只有失败说明本身。
  - Pair Hint 抛错或拒收 → **有边界才保留，没边界就退到最小副本。** 屏障在（`fenced` 或 `barriered`）时，副本 = 已经屏障 + Scope 投影过的那份，原样保留（屏障与 retention 都不放弃），只少一份语言提示。**没有 fence 时，Scope 并不是边界**：没有 boundary 的 Scope 会整份保留，所以「原样保留」在这里等于把宿主手上的整段历史发给 provider——实测（2026-11-01，omp 18.14.2，DevOps probe A）：unfenced 会话的 Pair Hint 日志损坏（`FACT_CORRUPT`）时闸门确实结算为 `allowed:false`，但请求仍带出前任的 15 条消息。现在无 fence 的 Pair Hint 失败回退到与 Scope 失败相同的副本：**只有操作员最新那条消息**；只有 Scope 真的减少过请求（`kept` 更短）时才沿用 Scope 的答案。
  - 请求闸门写不进去（`open`/`settle` 回 `false` 或抛错）→ 请求同样**未投影**，所以副本与「屏障失败」同形：带 fence 时是继任提示 + fence 已经算作继任者的那条操作员消息；无 fence 时只有操作员最新那条消息。此时 Scope 与 Pair Hint 都不运行——它们的答案会决定一份「闸门无法保证模型停手」的副本。请求里没有操作员消息时，副本只有失败说明本身。
  每种失败都在副本里点名（阶段、失败码、原因），并**关闭请求闸门**：`tool_call` 在闸门关闭时拒绝**每一次**工具调用（`CONTEXT_PROJECTION_FAILED`）。模型可以报告失败，不能拿一份没投影过的请求去干活。
- **请求闸门是持久事实**（`.Herdchestra/<session>.request.json`），但只在**判据改变**时写，外加回合/代理结束时刷新请求号（根 §1.1 item 1）：文件承载最后一次 SETTLED 的判据，`open` 一个请求不写（没有文件本身就是拒绝），判据不变也不写。崩溃安全性不变：无文件 = 拒绝，旧判据属于最后一个真正完成的请求，而能存活的工具调用只可能由它的回答发出。回合边界由 `hooks.mjs` 的 `closeRequestTurn` 调 `SessionState.flushContextGate()`。
- **闸门的形状也是安全事实。** `requestGate` 只接受 `{v:3, settled:boolean, allowed:boolean, request:非空字符串}`（`previous` 同形但无 `settled`）；手工改过或外来 schema 的文件（例如 `allowed:"no"`）读作 `REQUEST_GATE_UNREADABLE`，绝不按「已投影」读。`requestRefusal` 只有 `allowed === true` 才放行，其他任何值都拒绝。
- **闸门的 I/O 失败一律 fail closed，而且只在内存里也能拦住。** 读：`readJson` 读不出的已有文件按「拒绝」读（`REQUEST_GATE_UNREADABLE`），不按「无判据」读；解析不了的文件、目录占位、读时抛错同样按「拒绝」。写：`openContextRequest`/`settleContextRequest` 回 `false` 时，`createContextHandler` 把拒绝**存在自己身上**（`gateHeld`），由同一个钩子对象的 `requestRefusal()` 交给 `tool_call`，直到某次请求 settle 出一个文件真的写下的判据才清掉。`tool_call` 对闸门的每一种回答都 fail closed：只有明确的 `null` 算「已投影」；抛错、空对象、缺 `message` 的答案一律按 `CONTEXT_PROJECTION_FAILED` 拒绝。**带 fence 的请求在闸门写不进去时不再抛出**（旧实现的 `REQUEST_REFUSED` 已删除）：宿主会吞掉抛出并照发它自己那份副本，对继任者就是被替换的整段对话；现在发的是上面那条最小副本，停手靠内存闸门。
- **`context` 里任何情况都不抛出。** 抛出不是停止请求的手段（见上）：它只会让宿主把未投影的副本发出去。闸门才是停止手段。
- **回退副本绝不取前任的提问。** 回退路径里的「操作员最新消息」从 `fence.fenceThrough` 开始找，之前的 user 消息属于被替换的任期，交给继任者就是屏障要防的泄漏；全都早于 fence 时，副本只剩失败说明。
- Scope：`tool_call` 做准入（注解从参数 `scope` 读，原样交给 `admit`，拒收即 `block`）；`tool_result` 结算并持久化；`before_agent_start` 每请求推进一次回合号。记录写 `.Herdchestra/{session}.scope.jsonl`，冷启按 §6.2 从记录重建，`resetScope` 只证明旧事件，预算待模型重新确证。
- Pair Hint：会话首次请求时按阶梯（`HERDCHESTRA_LANGUAGE` → `HERDCHESTRA_HOST_LANGUAGE` → `LC_ALL`/`LC_MESSAGES`/`LANG` → English）绑定语言；handles 取本会话尚未结清的 park 名（`state.noteParked` 在程序 park 时加入，`noteFinished` 在它结束或关闭时移除；parked 程序恢复后由 `run.outcome` 的回调移除）。
- **side-channel 事实由调用方显式传入 `pairHintProject`。** `context` 处理器从宿主交来的原始消息算出 `side`，再作为 `ephemeral` 传进去；**不得**让 `pairHintProject` 从它收到的副本重新推导——继任任期的副本经过屏障，宿主那条 `role:"developer"` 提醒已被裁掉，重新推导会把标题请求当成主回合，开 epoch、改 handles、锚定前缀链，随后真实请求报 `PREFIX_DRIFT`。
- **投影只有一处入口：`SessionState#pairHintProject`**（`ProgramSurface#pairHintProject` 只是转调）。它先比较冻结的 handles 与当前未结清的 park 名：**不等才** `await hint.newEpoch()`，再 `setHandles` 并 `project`。冻结的呈现属于整个 epoch（根 §6.1），只调 `setHandles` 的话，epoch 冻结时没有的 park 永远列不进空闲提示；相等时不动 epoch，也就不付全量重算。
- **继任者的前缀链在屏障处重锚，而且只在那时**（`barriered` 参数）。屏障切掉了被替换的对话，那份副本不可能是之前任何呈现的延长，所以链从它开始（根 §2.4 第 3 步，唯一合法的断点）。同一次调用里若因 handles 变化已经开过新 epoch，就不再重锚——开新 epoch 已经决定了链。
- **重锚与投影必须用同一种消息形态。** `anchor` 收到的是 `toPairHintMessages(messages)`，不是原始宿主副本。原始副本里同一条用户消息可能是裸字符串，而投影里是单个 text part；两边形态不同时，`assertPrefix` 会在下标 0 判漂移（真机 2026-11-01：继任者第二次请求报 `PREFIX_DRIFT`，`index: 0`，消息一个字都没变）。
- **链的形态归一在 `toPairHintMessages` 里**：用户消息的 `content` 是字符串还是单个 text part 视为同一句；适配器自己的 `herdchestraSuccession` 标记与宿主的 `timestamp` 不算对话。屏障写的提示固定为「单个 text part + 标记」，宿主回放的副本即使形状不同也归一到同一形态。

## 7.1 面向模型的 CUE 呈现（根 §6.3 在本包的落点）

根 §6.3 规定：凡是可能进入模型视野的结构化数据，一律不得投喂裸 JSON，必须转成 CUE Pretty Print。本包的实现只有一条规则，其余都是它的推论：

**`content[].text` 是呈现，`details` 是结构。** OMP 把 `toolResult` 消息折进请求时只取 `content`（`session/messages.ts` 的 `case "toolResult"` 只转发 `content`，`details` 留在宿主侧），所以 `content[].text` 就是模型读到的那份字节，而 `details` 是给代码读的那份对象。于是：

- **模型可见的一律 `toCue`**（`src/core/cue.mjs`，根 §6.3 的唯一实现）。本包不自己实现格式化器：`tool-schema.mjs` 的 `present(value)` 只是 `toCue` 加一层 try/catch。
- **`details` 保持原样的对象**，不复制、不改写、不转字符串。任何消费方（测试、宿主以外的调用方）读结构一律读 `details`，**绝不 `JSON.parse(content[0].text)`**：CUE 不是 JSON，这条路已经被 §6.3 关掉。

落点（每个都在 `src/host-omp` 内）：

| 出口 | 呈现什么 | 结构在哪 |
|---|---|---|
| `js-manager` / `js-worker` / `js-review` / `plan`（`program-tools.mjs`） | 程序回执 + `value` / `error` | `details.result` |
| `fork` / `join`（`worker-port.mjs`） | 回执 / 报告 / `NO_SESSION` 拒绝 | `details` 本身 |
| `negotiate` / `askPreviousScope`（`worker-port.mjs` 的 `receiptResult`） | Scope 快照回执 | `details` 本身 |
| `tool_result` 的 `additionalContext`（`hooks.mjs`） | Scope 冷启通知 `{kind, reason, previousResets, message}` | —（只有文本） |
| `tool_result` 的宿主工具结果（`hooks.mjs`） | 宿主 `read`/`bash`/`grep`/`task` 的 JSON 文本 | 宿主自己的 `details`，本适配器不碰 |

几条必须守住的细节：

- **先展开再呈现。** core 把回执的 `value` / `result` 挂在**不可枚举**属性上（`core/receipt.mjs` 的 `withExtra`），`toCue` 与 `JSON.stringify` 一样看不见它们。所以 `program-tools.mjs` 一律 `{...receipt}` 之后再呈现——这就是以前 `JSON.stringify` 也要先展开的原因，规则没变，只是输出换了格式。
- **park 回执也要看得见 `value`。** 一次 park 已经收到被恢复表达式的值，模型必须在挂起那一刻就看见它，且字段名与完成时一致（都叫 `value`）。
- **宿主工具的结果也归 §6.3。** 宿主自己的 `read`/`bash`/`grep` 在拿到结构化结果时答的是 JSON，那段文本会原样进请求。`tool_result` 在宿主把结果折进消息**之前**运行，所以 `presentHostResult` 在那里把**整块就是一个 JSON 值**的 text block 换成 CUE。三条边界：①只有整块恰好是一个对象或数组才改（裸数字、带引号的字符串、散文、diff、带行号的文件一律原样）；②图片与其他 block 类型原样按序穿过；③**本适配器自己的工具结果不改**（`ADAPTER_TOOLS`）：它们在自己的定义里已经呈现过，而宿主把任何返回的 `content` 都当成替换——一次多余的替换等于谎报「是谁写的」。什么都没改时返回 `undefined`，不是返回一份与原物相同的副本。
- **渲染不出来不许变成工具失败。** 环、函数、bigint 会让 `toCue` 抛 `TypeError`；`present` 接住它，答一份 `{presentable: false, detail: "..."}` 的 CUE，调用照旧成功。把一份好回执变成工具失败，比少一个字段糟得多。
- **`# Relay` 段落保持散文，不转 CUE。** 它是给模型的一条**指令**（「接力没发生，别声称它发生了」），不是一条数据。§6.3 管的是结构化数据；把指令塞进 `{...}` 只会让它读起来像事实。
- **落盘与比较不进这条规则。** `.Herdchestra/*.jsonl`、`*.session.json`、`*.request.json`、Scope 记录、Pair Hint 记录仍是 JSON（`files.mjs`），`stableJson` 一类的前缀/相等比较也不变：那些字节机器自己读，模型永远看不到。§6.3 明文把内部持久存储排除在外。

**这是呈现层的改动，不是语义层的改动。** 回执的字段、拒绝的码、闸门的判据一个都没变；变的只是模型读到的那份排版。因此任何依赖「文本可 `JSON.parse`」的旧断言都是错的，必须改成读 `details`。

## 7.2 Scope 注解面

§6.2：每次工具调用都带一个打平的 `scope` 注解，非豁免工具缺席即拒收。§6.2 只列了两个豁免成员——`negotiate`、`askPreviousScope`（都是同步查询最新快照）。其余「酌定」不等于可以按类豁免：把整类宿主工具豁免掉，等于让调用完全不碰预算，而预算正是 Scope 存在的理由。所以本适配器只豁免这两个，其他一律需要注解。

- 本适配器自己注册的四个编程面与 `fork`/`join` 都在参数 schema 里声明了 `scope`（`program-tools.mjs` 的 `SCOPE_PROPERTY`），模型写得出，宿主闸门在 `execute` 之前读得到。**参数描述只写一句指针**（机制在 System Prompt 的 Scope 段）与各 kind 的必填字段对：`keepLast`/`meToo` 只有 `kind`，`removeLast` 另需非空 `replacement`，`resetScope` 另需非空 `newScopeGoal` 与正整数 `newScopeRetentionBudget`。每个工具复制一份完整解释是浪费，所以 `SCOPE_HINT` 已删除，七处追加也一并删除。
- 适配器不拥有的宿主工具（`read`/`write`/`bash`/MCP 工具）无法在此处补 schema，因此它们**不带注解仍会被拒收**。这是规格的结果，不是本适配器可以绕过的缺口。
- 两个豁免工具 `negotiate`/`askPreviousScope` 的 schema **没有** `scope` 属性，描述里明说它们豁免：它们不花 retention，也不需要注解。

## 7.3 一个进程里多个会话：适配器与会话一一对应

`omp` 的一个进程里可以同时活着多个会话（Manager、worker、`/new` 之后的继任者）。2026-11-01 13:22–13:24 的实测（`01a1958b`/`01a1958c`×2/`01a1958e`/`01a1958f` 五个会话同一进程）暴露了两件事：

- **工厂只建一个适配器，等于让第一个绑定的会话拥有所有会话。** 工具定义、钩子、闸门、花名册都挂在那一个对象上；`adapterForCall` 解析不到会话 id 时回退到「建这个工具定义的适配器」，于是 `01a1958f`（自身没有任何文件）的每次 `tool_call` 都被同进程兄弟会话的 `PREFIX_DRIFT` 判据拒绝，`js-manager` 甚至在兄弟会话上建了 surface。现在：工厂只注册工具定义与钩子，**每个会话 id 一个适配器**（`sessionIdOf(ctx)`，也是所有持久文件的名字）；`/new`、`/switch` 换了 id 就是换了适配器，旧适配器的状态留在原地。
- **解析不到就拒绝，绝不借用。** `adapterForCall` 只查注册表，没有回退。带 `ctx` 却指向本进程没绑定过的会话 → `SESSION_UNBOUND`（工具层抛错，`guard` 变成错误结果；`tool_call` 钩子同一条）；完全没有 `ctx` 的直接 `execute` 没有会话可判，交回工具自己的端口答 `NO_SESSION`/`NOTHING_TO_JOIN`。`adapterForTool` 只多一条豁免：某个适配器**绑定的 ctx 就是这次调用的 ctx**，它才拥有这次调用。
- **钩子对象每适配器只建一次。** 之前 `hooks()` 每次调用都重建，`context` 钩子对象里的内存闸门（`gateHeld`）随之丢失，闸门写不进去时下一次 `tool_call` 什么都拦不住。
- **绑定时刻有一行诊断。** `HERDCHESTRA_DEBUG=1` 时 `bind()` 打 `[herd] bound {sessionId, role, root, host}`，所以「会话从没绑定过」与「绑定过但没写文件」在宿主 stderr 里就能分开——`01a1958f` 那次只能靠手读宿主会话库。
- **离线覆盖**：`a refusal in one session never blocks another session in the same process`（A 的 `PREFIX_DRIFT` 不再拦住 B，B 只写自己的记录）、`a call whose session never bound is refused by name, never judged by another session's gate`（程序面不建 surface，`fork` 不借花名册）。

## 8 缺口（核对后的结论，不是猜测）

1. **relay（接力）在 OMP 上已实现：一笔 `appendEntriesAtomically` 提交 + 宿主自己的 `newSession`。** 根 §2.4 第 3 步要求退休、上下文屏障、继任者开启「在同一笔持久记录里完成……进程中断也不会留下半个任期」。

   **原语找到了，就是宿主自己的日志事务。** `SessionManager.appendEntriesAtomically(append)`（`session-manager.ts` `#appendEntriesAtomicallyLocked`）把回调里追加的条目暂存，然后用 `#rewriteAtomically` → `session-storage.ts` `#publishAtomic` 把**整份日志**写成临时文件、再 `renameSync` 一次替换。因此：

   - 记录是一个 `custom` 条目（`customType: "herdchestra.relay"`，`relay-record.mjs`），退休、屏障、继任者三个事实是**同一个条目的三个字段**，读者只能看到「有」或「没有」，看不到其中一个而没有另外两个；
   - 发布失败时 `#rollbackAtomicEntryBatch` 撤掉暂存条目并抛出，所以一次被拒的提交**不留记录**；
   - `custom` 条目不参与 LLM 上下文（`session/messages.ts` 只转换 `message` / `custom_message`），所以记录本身不会漏进继任者的请求——屏障仍是 `context` 钩子的职责。

   **继任者是本会话自己的下一段对话**（`AgentSession.newSession({ parentSession })` → `SessionManager.newSession` → `ensureOnDisk`），它的头部在返回前已落盘，`parentSession` 记下被替换的转录。用 `newSession` 而不是另起一个 SDK 会话，是因为 §2.4 要换的是**任**而不是**人**：会话身份、`agentId`、台账、角色都不变，`DevOps` 绑定自然跨接力不变（§2.4 第 4 步）。这同时解掉了上一版论证里的死结——「另起会话 = 两笔提交」不再成立，因为继任者不是一个新对象。

   **提交顺序与崩溃点**（`relay-record.mjs` 顶部有完整表）：

   | 崩溃点 | 留下的状态 | 是否半个任期 |
   |---|---|---|
   | 发布前 | 无记录、无切换 | 否 |
   | 发布后、切换前 | 有记录（fence 生效、任期 +1），旧转录仍在 | 否：继任者已在记录中，旧对话被屏障挡住 |
   | 切换中 | 旧转录（`newSession` 先 abort/重置内存，再 `ensureOnDisk` 写继任头部） | 否：记录已在，屏障仍挡旧对话；下一次启动读到记录即继任者 |
   | 切换后 | 继任转录，记录已重写进新转录 | 否 |

   顺序**不能反过来**：先切换再记录，中间被杀就得到「已切换、无记录」——没有屏障、没有任期的继任者，正是 §2.4 第 3 步禁止的那个窗口。

   **回执与拒绝**：`handOverPort` 只有在 `appendEntriesAtomically` 存在时才提交；宿主没有这个方法时回 `{ok:false, reason:"NO_ATOMIC_RECORD"}` 且**三步一件都不做**（三次分开的写，崩溃就会劈开任期）。现任忙（streaming/transitioning/排队）时回 `RELAY_BUSY` 并给出 `busy`，不提交；提交抛错回 `RELAY_COMMIT_FAILED`。这三种拒绝都不动台账。`ReviewMember` 只在 `handOverCompleted` 通过后 `openSuccessor()`，所以任期**只前进一次**——`SessionState.handOver` 因此只安装 fence、绝不自己再开继任者。

   **接力必须延期，不能当场执行（真机实测，18.14.2）。** `AgentSession.isStreaming` 是 `agent.state.isStreaming || #promptInFlightCount > 0`（`agent-session.ts:6825`），而**任何工具调用都发生在自己这一回合的 prompt 仍在飞行时**。因此 `review()` 触发接力那一刻，会话**永远**是 streaming——宿主自己的活跃源就证明了这一点。旧实现据此拒绝（`RELAY_BUSY`），于是真机接力永远不可能成功；那不是「偶发忙」，是**正常路径**。

   现在的语义：

   - **当场提交记录，只把「切换」延后**。`handOverPort` 遇到 `SESSION_STREAMING` 时不再拒绝，而是**在回合内**用与同步路径同一个 `commitVerified` 提交 `herdchestra.relay` 记录本身（`appendEntriesAtomically` + `getEntries` 读回验证），记录带 `published: true`、`retiredTranscript: true`、真实 `fenceThrough`。随后把**这条记录**（不是草稿）写进 `herdchestra.relay.pending` 意图。回执 `{ok:false, kind:"relay", rejection:"RELAY_DEFERRED", handedOver:{deferred:true, retired:false, barrier:false, successor:null, pendingId, recordPublished:true, recordId, recordPublishFailure:null, busy:"SESSION_STREAMING"}}`。

     **这不是成功**：转录没切换、没有继任转录。但**记录是真的**——§5.3.2 要的那份原子事实已经在盘上，所以台账在记录落地的同一刻按记录推进（`RoleSession.handOver` 见 `recordPublished === true` 时 `adoptLedgerRecord` + `saveLedger`）。回执逐项说明哪一半做了、哪一半没做。

     **fence 不在这一刻装（真机实测，18.14.2）。** 台账与屏障是两件事，必须分开：`adoptLedgerRecord` 只说「某个任期已有记录」，`armFence` 才决定一条请求能看见什么。早先把两者一起做，于是屏障立刻作用于**发布这条记录的那个回合**剩下的请求——模型只看到操作员那句话和继任提示，丢掉了自己的对话，于是重新 `fork`，一次回合里 116 次 `fork`、18 个 worker 会话、2.2MB 转录，直到 600s 上限被杀。屏障属于**继任者的**请求（§2.4 第 4 步），所以它在**切换转录时**才装上（`performDeferredRelay` 里 `armFence` **先于** `saveLedger`，否则落盘的继任账本没有 fence，重启后重建出一个什么都不挡的继任者）。「记录已提交、切换未发生」这段期间，`relayOwed` 为真，由 `before_agent_start` 的提示告诉模型「继任已定、切换待办」，而不是靠屏障把它自己的历史剪掉。

     **回执必须告诉调用方停手。** 延期回执带 `next: "STOP"`，`detail` 写明「本轮就此结束、不要 fork、不要重试」；回执里的 `tenure`/`milestone` 报的是**采纳之后**的状态，不是采纳之前的——早先报 `tenure: 0` 而 `recordPublished: true`，模型据此以为还有活要干，于是不停地干。

     为什么必须在回合内提交：终局 `agent_end` 时管理器已封（`dispose()` → `seal()` 置 `#released` 并 bump `#diskEpoch`，每个 commit guard 都是 `!released && diskEpoch === epoch`），**任何**发布——原子或普通——都落不了盘（`/tmp/hc-devops-final/harness` 三个探针）。回合是唯一的可写窗口。

     顺序是「先记录、后意图」：崩溃在两者之间留下「有记录、无意图」= 切换尚未发生但 fence 已生效，这是安全的一侧；反过来会留下指向空处的意图。
   - **在宿主的空闲边界只做切换**。`agent_end` 且 `willContinue` 缺席（终局回合）时，`performDeferredRelay()` 不再重建、也不再发布切换前记录：它用 `#carriedRecordEntryId` 在日志里查这条记录是否真的存在（按 tenure/milestone/notice/fenceThrough/successorSessionId/previousSessionFile 比对）。查到就**按原样使用**，只跑 `newConversation()` 与继任转录里的那份副本（`successorCopy`）；查不到才回退到「自己发布一份」，而那在本宿主上会失败并被如实报告。`turn_end` 不行——它在 prompt 仍在飞行时发出（`lifecycle-mirror.ts` 只把终局 `agent_end` 映射成 `willContinue: undefined`）。`willContinue: true` 是续跑（重试、排队输入、后台任务），不是空闲，继续等。
   - **`preparedRecord` 不是权威事实。** 意图里的 `record` 字段只有 `published: true` 才表示宿主接受了这次提交；`published` 是唯一的判据，`#carriedRecordEntryId` 是唯一的证据（日志里真的有这一条）。一份「准备好了但没发布」的草稿**不得**被当成任期，也不得被当成已提交——那正是旧实现把 `ok:true` 写在一条哪里都不存在的记录上的方式。
   - **`agent_end` 本身仍在 in-flight 括号内（真机实测，18.14.2）。** 该事件由 `#endInFlight` → `#flushPendingAgentEnd` → `runner.emit` 发出，此刻 `#promptInFlightCount` 还是 1，而 `ctx.isIdle()` 就是 `!session.isStreaming`。所以在处理器里当场切换**永远**读到 `SESSION_STREAMING`——宿主说的是真话，只是说的是「这个括号还没结束」。等 `waitForIdle()` 是自锁（它要等这个处理器返回），宿主还会在 30s 处理器超时处砍掉。因此 `performDeferredRelay()` 先 `waitOutsideOwnTurn()`：把控制权交还事件循环，让宿主自己释放括号，再切换。等待有界（默认 1s，`maxPauseMs` 可调），等不到就**什么都不改**并回 `OWN_TURN_NOT_RELEASED`，意图与记录都留着——记录本来就是真的，缺的只是切换。
   - **拒绝一律保留意图。** `NO_ATOMIC_RECORD` / `NO_SESSION_CONTROL` 曾被视为「永久、丢弃」，于是接力被静默丢掉。现在它们只被**报告**（`RELAY_DEFERRED_UNREACHABLE` + `cause`，并经 `before_agent_start` 的 `# Relay` 段落送到模型），意图留在盘上等下一个空闲边界或下一次进程。清除只发生在**真的落地**之后，且只清掉被这次执行兑现的那一条（`clearRelayPending({ throughTenure })`），不误删之后记下的新义务。

     回合内连记录都发布不出去时（`NO_ATOMIC_RECORD`），回执是 `NO_ATOMIC_RECORD`（宿主没有这个原语，永久）或 `RELAY_DEFER_FAILED` 且带 `cause`/`recordPublishFailure`，**意图也不写**——没有记录就没有可兑现的义务，写一条指向空处的意图只会让下一个边界去追一个不存在的事实。
   - **语义不变**。`performDeferredRelay` 除了跑端口，还要补上台账那一半：`adoptRelayRecord(done.record)` 一次同时装 fence、按**绝对值**把任期与里程碑设成记录里写的那个、并清空义务（这正是 `openSuccessor()` 会得到的状态）；然后 `saveLedger()` 落盘、`clearRelayPending({throughTenure})`。**只调 `adoptRelayRecord`、不再调 `openSuccessor()`**：后者会把任期推进第二次（曾经因此到 tenure 2）。它同时也是崩溃恢复那一步——重启走同一个函数，于是「现在执行」与「崩前已执行」收敛到同一个状态。用例 `a deferred relay reaches exactly the state a synchronous one would` 逐字段比对两条路径的落盘状态（任期、里程碑、`successor`、义务、fence、记录 tenure），二者必须相同。**延期只改变「何时」，不改变「什么」。**
   - **崩溃恢复**。记录与意图都在回合结束**之前**就已落盘，所以进程死在回合里时：①记录已经生效——`RoleSession#open` 读到它即 `adoptRelayRecord`，重启后**第一条请求**就被 fence 挡住；②意图还在，第一个空闲边界补上那次切换。判据是**记录属于哪份转录**，不是任期大小：标为 `retiredTranscript: true` 的记录坐在它要退休的转录里，说明切换**没发生**，义务**照旧成立**（这正是这条意图存在的崩溃点）；只有继任者自己那份副本（`retiredTranscript: false`）或更高任期的记录才表示切换已跑完，意图才丢弃。**不得**用 `record.tenure > pending.tenure` 判定——切换前记录写的永远是「本任期 + 1」，那样判会把刚发布的记录当成「已切换」，在它最该起作用的那一刻丢掉义务。记录归属最终按 `successorSessionId` 判定（`recordGoverns`）。
   - 只有 `SESSION_TRANSITIONING` / `SESSION_QUEUED_WORK` 仍是 `RELAY_BUSY`：它们不会随本回合结束自行消失，延期的意图会与它们竞争。

   **不得**把 `RELAY_DEFERRED` 当成 `ok:true`：那会把「稍后做」谎报成「已做完」。

   **每个事件都要换用该事件自己的 `ctx`（真机实测，18.14.2）。** 宿主为**每一次 emit** 新建一个 `ExtensionContext`（`runner.ts` `createContext`）。早先 `bind()` 只认第一个（`session_start` 的），此后整场会话都拿着那个对象：真机从 `js-manager` 内部探测，`ctx.sessionManager` / `agent` / `isIdle` / `abort` 全是 `undefined`。后果有两条，同源：① Scope 准入被静默降级（每次都回 `SCOPE_ANNOTATION_MISSING` 的 `additionalContext`）；② `agent_end` 里 `performDeferredRelay` 拿不到 `appendEntriesAtomically`，**延期接力静默不执行**——回执早已说过 `deferred`，之后再没有任何东西反驳它。

   现在 `HostAdapter.bind` 对已绑定的会话**只替换 ctx**（`RoleSession.refreshContext` + `HostBinding.refresh`，并重建控制面），**不动** state / roster / ledger / ports：那是「换上下文」，不是「重新绑定会话」。身份（role、forked、sessionId）仍只在第一次确定。

   **延期失败必须报出来，不能吞掉。** 端口区分两种提交失败：`NO_ATOMIC_RECORD`（宿主没有这个原语，永久）与 `RELAY_COMMIT_FAILED`（写了但失败，可能重试）。永久失败时 `performDeferredRelay` 回 `{ok:false, reason:"RELAY_DEFERRED_UNREACHABLE", cause, detail}`，**保留**意图，并把失败写进会话状态；下一次 `before_agent_start` 追加一段 `# Relay` 告诉模型「接力没能执行，别声称它发生了」。早先的实现会清掉意图且什么都不说——真机就是这么把一个坏掉的接力伪装成安静的成功的。

   **控制面必须绑到 `ctx.sessionManager`，不是 `ctx`（真机实测，18.14.2）。** 同理，`ctx` 没有 `isStreaming`，活跃判定要走 `ctx.isIdle()`；两者都答不出的面回 `NO_SESSION_LIVENESS`，**绝不把「不知道」当成「空闲」**。 扩展在 `session_start` 拿到的 `ctx` **没有** `newSession` / `sessionFile` / `isStreaming`：`typeof ctx.newSession === "undefined"`。切换原语在 `ctx.sessionManager` 上（`newSession`、`getSessionFile`），而 `ExtensionContext.sessionManager` 虽被类型标成 `ReadonlySessionManager`，宿主在运行时传的是活的 `SessionManager`（`runner.ts` `sessionManager: this.sessionManager`）。`session-control.mjs` 的 `switchTarget()` 因此按「会话自己 → `sessionManager`」的顺序找 `newSession`，`sessionFile()` 走 `getSessionFile()`。

   **这条曾让每一次真机接力都被拒。** 早先 `SessionControl` 直接向 `ctx` 要 `newSession`，`busyReason` 因此回 `NO_NEW_SESSION`，端口把它包装成 `RELAY_BUSY`——于是拒绝看起来像「现任正忙、稍后重试」，实际永远不会成功。现在 `NO_NEW_SESSION` / `NO_SESSION` 一律回 `NO_SESSION_CONTROL`（面缺陷），只有 streaming / transitioning / 排队才回 `RELAY_BUSY`（可重试）。**离线测试曾全绿，是因为夹具把 `AgentSession` 当成 `ctx` 传进去**；`host-omp.test.mjs` 现在同时保留「lane 形状」与「真机 ctx 形状」两种夹具。

   **`fenceThrough` 属于某一份转录，不是某个任期。** 记录写进去的那份文件决定了这个数：
   - 切换成功后，继任转录是空的，所以写进继任转录的那份记录 `fenceThrough: 0`；进程内安装的 fence 也用 0（与落盘记录一致，重启前后看到的相同）。
   - 切换没发生（忙、或 `session_before_switch` 取消）时，现任的转录仍是活的，写进去的数就是真实边界，进程内 fence 也用这个数。

   早先 `SessionState.handOver` 读 `done.record?.fenceThrough ?? 0` 而端口从不回传 `record`，于是**永远装 0**：切换没发生时屏障等于不存在。现在端口把写下去的记录原样回传（`record: {tenure, milestone, fenceThrough, notice, …, transcriptReplaced}`），`handOver` 只从它重建 fence；端口回不出记录时回 `RELAY_RECORD_MISSING`，不装一个无法核对的屏障。

   **`transcriptReplaced` 是事实，不是成功声明。** 记录已提交、任期已前进，但 `session_before_switch` 取消或切换失败时，回执给 `transcriptReplaced: false` 与 `switchFailure`：继任者仍在旧转录上作答，由 fence 挡住前任对话。把这种情况报成「已替换」才是 §2.4 第 3 步禁止的假账。

   **账本文件跟着会话 id 走（真机实测，18.14.2）。** `SessionState.#file` 早先在构造时算一次，之后不再跟随：接力完成后 `saveLedger()` 把继任者的任期与 fence 写进了**现任**的 `.session.json`，而 `.Herdchestra/<successor>.session.json` 从未被创建。现在 `adoptSessionId` 同时更新 `#file`，延期路径在 `saveLedger()` 之前先用记录里的 `successorSessionId` 调它；端口的回执也改成回**切换后**的实时 id（`present.sessionId()`），否则回执和落盘记录都还写着现任的 id。

   **继任提示只出现一次。** 宿主会把继任提示当作普通消息重放一遍，而 `barrierFor` 又无条件前置同一段文本，于是继任者的首个请求里出现两份（真机实测）。`barrierFor` 现在比较首条 user 消息（字符串或 text parts）与 `fence.notice`，相同就不再前置；措辞不同的消息照常保留。

   **记录有两份，都是真的：宿主发布的那份 + 适配器自己的那份（根 §1.3）。** 宿主在终局 `agent_end` 已经封印管理器，既写不进继任转录，也不创建继任转录；只靠宿主那份，接力就永远停在「记录已提交、切换已完成、继任副本写不进去」，回 `RELAY_RECORD_NOT_DURABLE` 直到天荒地老。根 §1.3 明确允许把「上下文边界事实」写进 `.Herdchestra/{session}.jsonl`，所以：

   - **宿主那份仍然先写**（回合内 `commitVerified`），仍是 `recordGoverns` 从转录里读到的记录，仍是「一次 rename 发布」的那份；适配器那份只是**同一载荷的第二视图**（`RELAY_LEDGER_TYPE`），用 `writeJson`（temp + fsync + rename）整份替换，读者要么看到完整记录，要么看到上一份完整记录。
   - **两份不一致时以转录为准**（宿主发布的那份）；转录里一条都没有时——封印边界——才由适配器那份回答。
   - **`relayFile()` 按血缘命名，不按实时会话 id。** 接力会 `adoptSessionId` 换成继任 id，按实时 id 命名会让切换前写、切换后读落在两个文件上：记录写了却永远读不到（已修，`#relayLineage` 记下第一个 id）。
   - **继任副本写不进宿主时**，端口把它写进适配器那份并回 `successor.recordInAdapterJournal`；两份都写不进才回 `RELAY_RECORD_NOT_DURABLE`。**绝不**因为「宿主没接」就声称成功，也**绝不**因为「宿主没接」就把已经成立的任期报成失败。
   - **冷启动**：`#open` 先读转录（`relayRecordIn`），转录没有才读适配器那份（`latestRelayRecord`），于是封印边界之后的继任者在**第一条请求**上就被 fence 挡住，`relayOwed` 为假。

   **终局 `agent_end` 已封印，记录必须在回合内写（真机实测，18.14.2）。** `dispose()` 先 `emitSessionShutdownEvent`，随后 `sessionManager.seal()` 置 `#released` 并推进 `#diskEpoch`；每个 commit guard 都是 `!released && diskEpoch === epoch`，所以封印之后**任何**写入都不会落地——原子发布与普通 `appendCustomEntry` 都一样（DevOps 三个探针：agent_end 上两者都返回 id、既不在 `getEntries()` 也不在盘上；回合内两者都落地）。`waitOutsideOwnTurn()` 成功只说明本次事件的 in-flight 括号已清空，与 `#released` 无关。因此**记录在决定接力的那个回合内就写好**：回合内先用同一个 `commitVerified` 把它发布出去（发布前先写记录、再写意图——崩在两者之间时，记录单独存在就是「任期已开始、转录尚未切换」，fence 已经挡住前任对话，这是安全方向），并把记录作为 `record` 字段随 `herdchestra.relay.pending` 意图一起落盘（`relay-record.mjs` 的 `RECORD_FIELD`/`preparedRecord`、`PUBLISHED_FIELD`：只有宿主真的接受过的那份才带 `published: true`，草稿不算任期）。空闲边界只做两件事——把**已经发布的**记录按原样当作这次提交（`carriedDurable` 读回日志确认条目还在），再切换转录（`carriedDurable` 就是 `RoleSession#carriedRecordEntryId`，按字段比对日志里的那一条），最后把**继任者自己那一份**重新发布（`successorCopy`：`retiredTranscript:false`、`fenceThrough:0`、`successorSessionId` 跟随新 id；`previousSessionFile` **不改**，它是血缘、也是 `recordGoverns` 判断「退休记录是否已不归本文件」的依据）。**不得**在空闲边界重建记录：那里重建的 `fenceThrough` 描述的是一个已经无法记录的瞬间，而重建本身也写不进去。意图不带记录（旧意图）时回退到现场重建，这是该次尝试能做到的上限。

   **原子发布必须读回验证，id 不是证据（真机实测，18.14.2）。** 宿主可以在 `appendEntriesAtomically` 里丢弃暂存的 temp（commit guard 失效时 `session-storage.ts` 正常返回）再抛 `superseded`，并把条目回滚出内存日志；而 `appendCustomEntry` 早已返回了 id。于是「信任 id」的适配器会对一份哪里都不存在的记录回 `ok:true`——真机现象就是任期前进到 1、继任账本写成 tenure 1，而转录里 `herdchestra.relay` 一条也没有。现在 `commitCustomEntry` 以宿主自己的 `getEntries()` 读回这次写入的条目 id 为准：读不回来就不算提交。两种情况必须分开——①条目被回滚（`recovered:false`）：不重试，如实报 `RELAY_COMMIT_FAILED`/`RELAY_RECORD_NOT_DURABLE`；②条目仍在日志里、只是这次发布被 superseded（`recovered:true`）：宿主会把它带进下一次发布，因此**重试一次**（同一任期写两条记录描述的是同一事实，幂等）。提交前（写进被替换的转录）与提交后（重写进继任转录）两条路径都走同一个 `commitVerified`；**继任转录里读不回记录时 handOver 必须回 `ok:false`（`RELAY_RECORD_NOT_DURABLE`）**，不得把「试过了」当成功——任期与屏障照实报告为 `retired:true, barrier:true`，`successor:null`。

   **冷启动**：`SessionState` 先读 `.Herdchestra/<session>.session.json` 的 `fence` 字段，再读会话转录里最新的 `herdchestra.relay` 条目（`getEntries`，只认自己写的 `customType`，载荷不过 `isRelayPayload` 就整条忽略）。**两者冲突时以转录为准**，因为那是宿主用一次 `rename` 发布的那份；记录归属由写入者自己声明：`retiredTranscript: true` 的那份写进它要退休的转录，重置后写进继任转录的那份是 `false`。`recordGoverns` 的判据因此有两条——继任 id，以及「标为退休的记录在宿主报告了**另一份**live 转录时不予采纳」；**不得**用 `previousSessionFile !== getSessionFile()` 代替它：真机两份记录都写着同一条路径（切换未替换文件时更是如此），按路径比对会把继任者自己那份也判掉，重启即失去 fence。宿主报告不出转录、或报告的就是同一份（切换未替换文件 / 崩在记录与切换之间）时，标为退休的记录**照常采纳**——那是 §2.4 第 3 步要的崩溃恢复；`adoptRelayRecord` 用 `ReviewLedger.adoptSuccessor` 按**绝对值**重建任期（幂等，不会再开一个继任者），于是重启后的继任者从第一条请求起就被屏障过滤。

   **不得**把补偿式实现或适配器私有记录包装成成功：记录必须由宿主的原子发布写，继任者必须由宿主的 `newSession` 开。**已排除且不再重走**的两条：`newSession({ parentSession, setup })` 的 `setup` 在切换**之后**才跑（两步之间被杀 = 已切换无记录，所以本适配器不传 `setup`，记录自己先写）；另起 SDK 会话再切换是两笔提交。

   **原「适配器闸门方案」的排除理由已作废。** 当时排它的两条前提现在都不成立：① 「前任与继任者共用同一个 `sessionId`，闸门没有第二个身份可判」——不需要第二个身份，**任期**就是身份，`fence.successor` 与台账 `tenure` 一起前进，闸门判的是任期而不是会话；② 「继任者是另一个会话对象 → 两笔提交」——现在的继任者不是另一个对象。当时那三条理由里唯一仍然成立的是：`context` 钩子不能拒绝请求（下面 a）。但这不影响本方案：屏障只裁**请求副本**（§2.4 第 4 步要的正是「前任对话不进继任者视野」），退休由台账任期与 fence 保证，不依赖拒绝请求。

   **实测的宿主事实**（omp 18.14.1，2026-11-01；单扩展 + shim provider，不加载万象术，避免混淆）：

   a. **`context` 钩子抛错不会拒绝请求——错误被吞掉，请求照常发出。**
      探针：一个只注册 `context` 就 `throw` 的独立扩展。stdout 原样：
      `Extension error (…): HERD_PROBE_CONTEXT_THROW` 之后模型**照常作答**（`把眼前的事做扎实。`），`EXIT=0`；同一会话第二条消息同样作答。宿主源码佐证：`runner.ts` 的 `#runHandlerWithTimeout` 捕获 handler 失败后 `emitError` 并返回 `undefined`，`emitContext` 保持 `currentMessages` 不变；`transformContext` 从不抛。

      **结论**：OMP 上不存在「请求闸门」。任何依赖「退休者发不出请求」的接力设计都不成立。

      **由此得到的两条规矩（2026-11-01 真机缺陷之后）：**
      1. **`context` 里的失败一律不抛出。** 抛出只会让宿主发出未投影的副本——对继任者就是被替换的整段对话。所以处理器自己决定一份守得住的副本，并把判据写进持久闸门（§7）。**闸门自己写不进去时也一样**：那时没有任何副本能被判为已投影，处理器发的是它最小的一份（继任提示 + 操作员自己的话，或无 fence 时的操作员最新那条），并把拒绝留在内存里。
      2. **闸门落在 `tool_call` 上，且对闸门的每一种回答 fail closed。** `createToolCallHandler` 在闸门关闭时拒绝每一次调用；闸门抛错、答非所问时同样拒绝——只有明确的 `null` 算「已投影」。它不阻止模型读到那份副本（宿主已经发了），但它阻止模型基于一份没投影过的请求动手；模型还能把失败报出来。
      实测出处：`runner.ts` `#runHandlerWithTimeout` 捕获 handler 失败后 `emitError` 并返回 `undefined`，`emitContext` 保持 `currentMessages` 不变；`agent-loop.ts:1385` 的 `prepareProviderCall` 抛出时会结束整轮，**但那条路只有 `transformContext` 自己抛才走得到，而它被 `emitContext` 包住**，所以 handler 的错永远走「吞掉 + 原样发送」。

   b. **我们自己建的会话可以读到自己的历史。**
      `session.getMessages` / `session.getHistory` 都是 `undefined`，但 `sessionManager.getEntries` 是函数（一轮之后返回 6 条，含 user/assistant 的 `message` 条目），且 `session.messages` 是**活的数组**（prompt 前 len 0，之后 len 2）。投递调用是 `session.prompt`（`sendMessage`/`runTurn` 不存在）。

      **结论**：继任者的首次投递**可以**被观测，「恰好一次」这一环在 OMP 上可解——但它救不了方案，因为第 1 条已经堵死了请求闸门。

      **这条结论不随第 1 条变化，不要重测。** 若将来宿主补上请求闸门，或规格 owner 把「退休」放宽到只需工具闸门，那么「继任者首次投递是否恰好一次」这一环**已经解决**（`sessionManager.getEntries` 的 message 条目，或 `session.messages` 的活数组），不需要再探一次。重测只会在宿主升级后、这两者行为改变时才需要。

2. **`js-review` 按评估窗口开关（根 §2.3）。** 该面注册但**不**常驻：只有 Manager 在窗口开着时能看到它。**没有窗口就是关着的窗口**：`surfaceAllowed` 现在要求 `window !== null && window.open === true`，会话没有窗口时 `js-review` 一律被拒（此前 `window === null` 被当作开着，等于把只读面交给了一个从未评估过的 Manager）。窗口的记账只发生一次：程序内的 `review` 由 compose 的 `ProgramSurface.noteReview` 记账，会话成员自己的 `review` 由 `RoleSession.review` 记账，`tool_call` 闸门里对 `js-review` 只判一次（带窗口），不再被后面的通用面检查用「无窗口」二次否决。

   窗口本身是 core 的 `ReviewWindow`（`SessionState#window`），适配器只驱动它，不另存计数器：
   - **开**：`turn_start`（宿主每个模型回合一次）调 `HostAdapter.openEvaluation()` → `window.beginRound()`，阶段真的变化时重新收窄，`visibleToolsFor(..., { evaluating: true })` 才把 `js-review` 加进可见集。**不在 `before_agent_start` 开**：根 §7.3 禁止在那里调 `setActiveTools`。
   - **关**：本轮**第一次被接受**的 review（`receipt.ok === true`）关闭窗口。两条路径都通知适配器：会话成员自己的 `review` 经 `RoleSession.review`；**程序里的 `review(...)`** 由 compose 的 `ProgramSurface.noteReview` 记账，`RoleSession.buildSurface` 包装的同一方法在窗口由开转关时调用 `onReviewAccepted`（compose 的 `ReviewMember` 不经过 `ports.relay.review`，只接这条包装就会漏）。`onReviewAccepted` 把 `#evaluating` 置 `false` 并重新收窄。**被拒的 review 不关**——Manager 还没判定。
   - **执行拦截**：`tool_call` 与 `buildSurface` 都读同一个窗口；关着的窗口一律 `REVIEW_WINDOW_CLOSED`，worker 无论如何都被角色拒。`visibleToolsFor` 只改目录，不是授权。

   两条边界都要说清：
   - §7.3 禁止在 `before_agent_start` 里调 `setActiveTools`。本适配器把开轮放在 `turn_start`，且**只在阶段真的变化时**才收窄（一轮一次，不是一请求一次）；`before_agent_start` 只返回角色的纯函数 prompt。
   - 收窄失败（宿主拒绝该 action）不让请求失败：面保持原状，只记 `narrowFailure`，与收窄本身失败时的既有行为一致。

   `js-review` 仍只属于 Manager（`ownFace`），worker 即使 `evaluating: true` 也拿不到。
3. **`forked` 是「谁创建了这个会话」的事实，只能由创建者传入，不得从 `session.agent` 反推。** 宿主的 `agent` 对象是宿主自己拼的（`sdk.ts:3571`），`parentId` 缺失只说明宿主没填，不说明这个会话不是 `fork` 起的。`bindSession(session, { forked })` 显式接收该标志，`workerSessionTools.install` 传 `forked: true`。真机教训：曾从 `agent.parentId` 反推，worker 的 `fission` 被拒为 `InvalidOrigin`。`HERDCHESTRA_DEBUG=1` 的 narrow 行现在打印 `parentId`、`forked` 与 `program`（即 `name()`），一眼可辨。
4. **裂变组的第 7–10 步由 lane 的回合结束喂给 compose。** 每条 lane 建好即在本会话的 `fissionPort.buildLane` 里挂上 `onSettled`，其回合结束经 `fission-fanout.mjs` 翻成 `Fission.settle(owner, index, result)`；交付、`ConvergenceReady`、合并序（恒 `0..n−1`）与终局宣布全部由 compose 判定，适配器不写任何组状态。
   **接管 lane 先于兄弟结束时的收束由 compose 负责**：`Fission.settle` 交付后调 `LaneGroup.announcement()`，因此**任何一次**使合取成立的交付都会宣布，且携带的永远是 lane `n−1` 自己已存下的报告，绝不是最后到达那条 lane 的报告。适配器不存任何 lane 结果、也不重投（此前的适配器侧重投分支已删除）。性质测试见 `fission-fanout.test.mjs`（任意结束顺序 / 任意事实到达顺序 → 恰好一次宣布、只由 `n−1` 宣布、合并序 `0..n−1`）。

4. **fission（裂变）的每一步都真的做了，包括替换 present。** `Fission` 对**每个**会话都建（不只是 Manager）。归属判定走的是**会话身份**，不是名字：`ProgramSurface` 传给 `fission` 的 owner 是**程序名**（tool call id，`call_96a5…`），与 `RoleSession.name()`（`w1`）永远不相等 —— 曾用 `name === this.name()` 判定，真机 worker 被拒为 `InvalidOrigin`。现在 `origins` 只读会话自己的 `#forked` 事实（由 `bindSession` 显式传入），参数不参与判定；共享的 `Fission.origins(owner)` 契约不变，其他宿主行为不受影响。`name()` 仍是会话身份，作为组标签与 lane 名前缀；容量看的是真实 SDK 工厂（`sessionFactoryOf`），不看宿主 action。

   **lane**：每条都是真实 worker 会话（同身份、同一工作树，经 `LaneTable.start` 创建、接运行时、握手授权、起首回合）。lane 以 `register: false` 启动，**不进 owner 的 roster**——组自己持有它们（`ports.mjs` `fissionPort.lanes`），否则 `join` 会收走一条尚未收敛的 lane。`ownerView` 读**属主会话自己**的最后一条已交付 assistant 文本（调用方就是属主自己的会话，不是别人的转录）；会话暴露不出转录时才回 `null`（空视图）。

   **lane 不能再裂变（根 §4.2 `InvalidOrigin`）。** `register: false` 的事实随 `workerToolsFor` 的第四个参数传到 `workerSessionTools.install` → `bindSession`，`origins` 要求 `#forked === true && #fissionLane !== true`。仅看 `#forked` 会把 fission lane 当成 `fork` 起的 worker，允许它再开一组。

   **第 4 步的组记录是持久的。** `persistGroup` 往 `.Herdchestra/<lineage>.fission.jsonl` 追加一行 `{groupId, owner, lanes}`；写不进去就拒绝启动整组（`FISSION_RECORD_FAILED` / `FISSION_RECORD_UNAVAILABLE`）。`discardGroup` 追加 `fission-withdrawn`，回滚不会在重放里复活一个组。

   **第 5 步等宿主接受首回合。** `startLane` 在 `lane.start(verboseIntent)` 之后 `await lane.submitted`；`ok !== true` 抛 `TURN_START_FAILED`，让 compose 回滚整组，而不是在一根从未启动的 lane 上收敛。

   **第 7 步的 pre-fission completion 由本适配器交付并 claim。** roster 的 `onArrive` 把每个 worker 结果交给 `Fission.settled`（组未开时进入 compose 的 pending），本会话另存一份；`buildLane` 建第一根 lane 时取走同一刀口的结果，`replacePresent` 成功后一个宏任务再对每根 lane 调 `Fission.claim(owner, index, key)` 并把完成文本 `steer` 进仍在跑的 lane。没有这一步，`ConvergenceReady` 的合取是空的。

   **第 10 步收敛后属主恢复为唯一 present。** `Fission.settle` 报 `converged` 时，`#noteFission` 调 `SessionControl.restore()` 清掉 `SESSION_REPLACED` 标记，并把合并结果 `LaneTable.deliver(owner, …)` 进属主自己的 roster，属主会话的 `join()` 能收到它；不恢复则属主此后每次都被 `SESSION_REPLACED` 拒绝，永远读不到自己的合并结果。

   **lane 的起始文本读 `lane.laneCount`（根 §4.2 第 3 步）。** 总泳道数是组合层在 `lanesOf` 里一次性定下的 lane 事实，随 `createLane(lane)` 的 lane 对象到达；本适配器只读它，不数、也不从 `index` 推。曾用 `lane.index + 1` 顶替，结果每条 lane 印出的「one of N」各不相同，与同一段身份声明自相矛盾。lane 起始文本 = 三段身份声明（含 `laneCount`）+ owner 已交付视图（有则加，无则省略）+ 本条 prompt。

   **替换 present 是「标记 + 停队 + 回合末 abort」。** `fissionPort.replacePresent` 调本会话自己的控制面：先 `replace()` 打上 `SESSION_REPLACED`（此后每个新回合都被拒），再 `stop()` 清空排队工作并重读 `isStreaming`；宿主的 abort 不能在本回合内 await（`AgentSession.abort` 会等 `waitForIdle`，而死锁在调用它的回合上），所以 `atTurnEnd()` 把 `silence()` 排到 `agent_end`，由 `index.mjs` 的 `drainTurnEnd()` 执行。会话对象**不 dispose**，转录与已交付视图仍可读。

   **回执只在真的停住或真的排上 abort 时成功。** 要求 `settled.ok === true || deferred.ok === true`；两者都失败，或 `atTurnEnd` 当场执行而 `silence()` 失败，一律回 `PRESENT_NOT_REPLACED` 并让 compose 回滚。旧实现只要两个字段非空就回 `ok:true`，组可能在旧 present 仍有排队/在途回合时「收敛」。`silence()` 的拒绝不再被吞掉，回执的 `silence` 字段如实携带。

   **回执形状**：成功 `{ok:true, groupId, lanes}`；`replacePresent` 成功时 receipt 带 `{ok:true, owner, replaced, settled, deferredAbort, silence, stoppedNow}`。

   **回滚结果必须到达模型，否则「没有残留」不可观测。** 拒绝错误上挂 `rollback: { ok, groupId, released, failed }`，`groupId`/lane 名以会话身份开头（`w1:2:2`）。这个字段曾在两个边界被丢掉两次：`thrownResult` 只保留 `code`/`message`/`details`，`program.mjs` 又把失败记录重拼成 `{name, message, code}`。现在两处共用 `extraFactsOf(error)`——带走除 `name`/`message`/`stack`/`code`/`details` 外的全部 enumerable 自有属性，所以不是给 `rollback` 开特例，而是「抛出时附带的事实」一律保留。`abortFailure`（回滚自身失败）走同一条通道，与 `rollback` 并列，不替代原错误。

   **已作废的观测（2026-11-01，替换 present 实现之前）**：worker `w1` 曾收到
   `{"ok":false,"name":"Error","code":"PRESENT_NOT_REPLACED","step":"replacePresent",…}`。
   那是旧实现的拒绝码；旧结论「OMP 没有静默另一个会话的原语」也已被推翻：**另一个**会话确实停不了，但 owner 停自己的 present 可以（标记 + 停队 + 回合末 abort），而 fission 只需要后者。`PRESENT_NOT_REPLACED` 现在只在标记没落地、`stop()` 与 `atTurnEnd()` 都没成功、或当场 `silence()` 失败时出现；`PRESENT_BUSY` 不再是本适配器的拒绝码。

   **`released` 的证明边界**：`released` 只证明两件事——组合层删除了该 lane 记录，且对该 lane 的 `dispose()` 调用没有抛异常（抛了进 `failed`）。它**不**证明宿主侧会话真的已销毁：宿主没有向扩展回报会话终止的事实，本适配器拿不到。因此文档与回执都不得把 `released` 读成「宿主会话已终止」；「没有残留」只到组合层为止。

   **`dispose()` 的另一半同样没有宿主证据**：lane 的 `dispose()` 调用会话自己的 `dispose()`（`agent-session.ts`），返回值不携带终止确认。宿主是否真正释放了该会话，只能由宿主自己的日志/进程事实说明，适配器不作断言。

   **不要声称「每次都会重试」**：`#sequence` 每次**调用**自增（成功或失败都自增），所以 `groupId` 的序号（如 `w1:2:2`）只表示「本会话的第几次调用」。适配器本身不重试、不排程、不记忆失败；是否再试由调用方（模型或上层）决定。每次尝试都是独立整组回滚：不登记组、不留 lane 记录，因此再次调用是安全的——但「安全」不等于「会自动发生」。
5. **worker 走 SDK，不走 `task`（真机 2026-11-01 实测）**：`fork` 通过宿主注入的 `api.pi.createAgentSession` 自建会话。宿主 `task` 路径够不到——扩展工厂只收到 `api`（`loader.ts:454`），`ExtensionAPI` 无调度器，`ctx.invokeTool` 只委托同名内置工具，真机回执为 `{"started":false,"reason":"NO_HOST_DISPATCH","detail":"task"}`。SDK 路径的约束与处理：① 会话的扩展运行时不会被宿主初始化，lane 用宿主的 action 集（`runtime-init.ts` / `acp-agent.ts`）补调 `runner.initialize`，全部转发到会话自己的 API，缺成员即 `RUNTIME_INIT_FAILED` 拒绝（见 §6）；② 工具名用 `toolNames` 给定，定义由 lane 在建会话后安装到该会话（`customTools` 会在创建时快照创建方闭包，故不用；`restrictToolNames` 会丢弃全部扩展工具，`sdk.ts:3618`，也不用）；③ `session_start` 需自行补发，并用一次性 token 握手确认**本适配器实例**确实绑定且收窄成功，否则 `WORKER_NOT_AUTHORIZED` 拒绝启动（`hasHandlers` 会被其他扩展满足，不可作为证据）。

   **Plan 身份本身此前不存在，现已补上（根 §2.1/§2.2）。** 缺口是：`ROLE` 里没有 `PLAN`，`roleOfAgent` 把所有顶层会话一律读成 `MANAGER`，`tools()` 也只注册了三个面。于是 Plan 的台账、`draft` 端口、`startManager` 全都在，**却没有任何会话能真正以 Plan 身份跑起来**——它们都是够不着的代码。现在：
   - `ROLE.PLAN = "plan"`，`roleOfAgent` 按 §3 的 kind 先于名字判定；`plan` 面与 Plan 台账从此有会话能真正拿到。
   - `agentForRole` 只接受 worker 角色，`plan`/未知角色抛错。早先是 `role === DEVOPS ? "devops" : "coder"`，任何未知角色都会静默变成 coder——`fork` 的 verboseIntent 若写 `role: plan` 会起一个 coder 而不是被拒。`TaskPort.fork` 的既有守卫会把它报成 `UNDECLARED_ROLE`。
   - **面不越界**：`install` 装哪个面，**由会话自己的角色决定**，不由调用方记得传参决定。它先取 `laneFaceFor(adapter.role)` 作为该会话拥有的面，`only` 只能在这个面**内部收窄**（`only.filter(name => owned.includes(name))`）。此前它无条件写 `adapter.tools()` 全部六个，于是 `install(session)` 不传 `only` 时 worker 会话会拿到 `plan`——把安全性押在「调用方别忘参数」上。`laneFaceFor(role)` 统一给出 Manager/Plan/Worker 三种 face。
   - `tools()` 注册第四个面 `plan`（`PLAN_DESCRIPTION`），`primary` 按角色选：Plan 会话默认跑 `plan`。
   - `ownFace` / `surfaceAllowed` / `roleForSurface` 认 `plan`：**Plan 只拥有 `plan`**。早先 `js-worker` 的判据是 `role !== MANAGER`，会把 worker 面（含 `call`/`rm`/`mv`）漏给 Plan；现在收紧为 `role === CODER || role === DEVOPS`。
   - `PLAN_HIDDEN` 额外挡掉 `fork`/`join`：§2.2 只给 Plan 五个能力，唯一能触及的代理是同步、单路的裸原语 `devops(verboseIntent)`。
   - **`devops` 是裸原语，不是成员（根 §1.1 item 3）。** `program.mjs` 的 `PRIMITIVES` 收 `devops`，并按角色限定为 Plan；`MEMBERS` 里没有它，`this.devops` 在任何面上都不存在（声明同名方法会被 `JsProgram` 构造器拒绝）。`program-tools.mjs` 的 Plan 描述与 `agents/plan.md` 都按 `devops(verboseIntent)` 写。
   - **DevOps 是恰好一条专用 lane。** `ports.mjs` 的 `devopsPort` 在会话自己的 `LaneTable` 里用固定名 `plan-devops`（role `devops`）建一次；后续调查 `steer` 同一条 lane（在途回合并入，空闲则开下一回合）并等 `lane.ended`，报告原样返回。不再每次调用起 `plan-devops-<timestamp>`；同一任务同时至多一个未决调查（`DEVOPS_IN_FLIGHT`）。`HostBinding.runAgent/spawn/steer/progressOf` 已删除，没有第二条路。
   - 新增 `agents/plan.md`：没有这份定义，宿主不会以 `plan` 名字开会话，角色仍然够不着。

   **Plan → Manager 交接走 SDK，不走 `task`（根 §2.2）。** `startManager(draft)` 不再调 `task`（那条路在真实宿主上必然 `NO_HOST_DISPATCH`），而是用同一张 roster 的 `LaneTable.start` 起一个**真实 Manager 会话**：`role: ROLE.MANAGER`、`verboseIntent` 就是底稿字节，且**不带 `modelPattern`**（Manager 用主模型）。两处随之修正：
   - **面按角色定，不按「是否由 lane 创建」定。** 早先 `workerToolsFor` 无条件给 `WORKER_LANE_TOOLS`，Manager 会话会拿到 `js-worker`。现在 `LaneTable` 收 `toolNamesFor(role)`，`start` 用 `#faceFor(role)` 取名集，并把 `role` 作为第三个参数传给 `workerToolsFor`，所以 Manager lane 得到 `MANAGER_TOOLS`（`js-manager`/`fork`/`join`）。
   - **回执等的是 Manager 的一个回合，不是它的整份工作。** 根 §2.2 只要求交接有答案：`startManager` 等 `lane.ended`（与 `join` 读同一事件，即一个 `agent_end` 且 `isTerminal !== false`），拿到 Manager 自己的首份文本就返回；Manager 后续继续在同一会话里工作，Plan 不被拖到任务结束，交接也不承诺 Plan 已可依赖 Manager 的最终结论。`ended.error` 存在时回 `AGENT_FAILED`。Manager 会话留在 roster 里（`register: true`），所以它的后续回合仍可寻址、它自己的 `fork`/`join` 照常工作。
   - **底稿来源必须透传。** review 不带任何文本：组合层在 Plan 的 `review` 里经 `draft` 端口读规范路径（`plans/<session>.md`）上已保存的底稿，并作为 `review` 的**第二**参数交给台账；宿主侧 `review(findings, source = null)` 必须把它继续传给 `ReviewMember`，否则 `review([])` 在「待执行」会被误判为 `DRAFT_MISSING`，而底稿其实在盘上。没有底稿就没有交接，绝不用别处的文字顶替。
   - **底稿目录先建，再公布路径（根 §1.1 item 3）。** 端口公布**工作区根**的 `plans/<session>.md`，**绝不放在 `.Herdchestra`**：底稿是用户可见的交付物，`.Herdchestra` 只放适配器私有状态。`SessionState` 在构造时就为 Plan 建好该目录（`PLAN_DRAFT_DIR = "plans"`）。**建不出来是如实报告，不是崩溃**：失败记为 `draftDirFailure = {code, detail}`，会话照常存在并能说出来。
   - **「系统给你路径」现在是真的给了。** 工具描述与 `PLAN_PROMPT` 都写「the path the system gives you」，但此前没有任何东西给出路径——模型只能自己猜一个，于是又落回同一个 `ENOENT`。`before_agent_start` 每回合为 Plan 追加一段 `# Draft`，写出端口读的那个真实路径；目录准备失败时这一段改为陈述失败（`could not be prepared (<code>)`）并叫停，不再公布一个写不进去的路径。
   - **读不到 ≠ 空底稿。** 目录准备失败时 `ports().draft.read()` 回 `null`（核心按 `DRAFT_MISSING` 拒绝交接），**不回 `""`**：空串的意思是「Plan 什么都没写」，那会用错误的理由拒绝交接，并把真实原因（`ENOTDIR`、只读根）藏起来。
   - **根由创建者说，不从进程环境读。** lane 起的会话（worker、Plan 交接出的 Manager）在构造时没有 `ctx`，`bind` 时才建 `SessionState`；它此前取 `projectRoot()`——**一个进程级变量**。于是两个会话在同一时刻绑定，写进哪个工作树取决于谁当时设了 `HERDCHESTRA_PROJECT_ROOT`：整套测试因此时通时不通（首次 656/654，随后 656/656 两次）。现在 `bind`/`bindSession` 收 `root`，`workerSessionTools({ root })` 把它一路传到 `SessionState`，`workerToolsFor` 传的是**属主会话自己的** `state.root`。一个会话写进哪棵树，是它自己的事实，不是环境的状态。
   - 无 SDK 时如实回 `NO_SESSION_FACTORY`，不编造一个 Manager。
6. **worker 的 bash 禁令**：审批默认 yolo，父会话的审批就是授权边界；宿主没有“给某个子代理禁 bash”的强保证，禁令只在任务描述与 `agents/coder.md` 里。
7. **agent 定义的位置**：`herd-manager`、`devops`、`coder`、`plan` 四份定义在包内 `agents/`，作为已安装 plugin 时随包发现（`<plugin>/agents`，`task/discovery.ts:113-120`）。不走插件装载时，宿主只扫描 `.omp/agents/`（`task/discovery.ts:100`）与 `~/.omp/agent/agents/`，需复制过去。

## 9 测试

（用例数随并行编辑变动；当前条数与分层耗时见 `src/AGENTS.md` §4.1 的 2026-11-01 快照。）relay 一族的用例覆盖：一笔 `appendEntriesAtomically` 提交、发布失败不留痕、延期意图在回合内落盘、`agent_end` 括号内让出后提交、冷启动从转录重建 fence、继任者自己的账本文件、提示只出现一次、以及「延期与同步两条路径的落盘状态逐字段相同」。

`node --test "src/host-omp/**/*.test.mjs"`（条数与分层耗时见 `src/AGENTS.md` §4.1 的 2026-11-01 快照）。测试只断言消费者可见的行为，不扫描源码结构、字段声明或语法布线。覆盖：角色判定（**kind 先于名字：顶层 `plan` 为 `ROLE.PLAN`，子代理叫 `plan` 仍是 `CODER`**）、可见工具集（**Plan 只见 `plan`，无 `fork`/`join`/`call`/`glob`/`grep`**）、**面归属与执行闸门：只有 Plan 能跑 `plan`，Plan 也跑不了 worker/Manager 的面**、**`install(session, { only })` 只写该 lane 自己的 face（worker 会话拿不到 `plan`）**、**Plan 拿到自己的提示词段（不走三文件投影，那里会按根 §5 拒绝）**、程序编译与成员缺失、**park 挂起与 `unpark` 就地续跑（含真实工具端到端）**、**Plan 的 `this.ask` 端到端：park 回执带 `interrupt:true`、`ctx.abort` 恰好一次、`unpark` 恢复同一函数体且不换任期**、**程序按角色注册能力集，且控制者回执带得见被恢复的值（含 JSON 往返）**、`HostBinding` 的 `runAgent`/`steer`/`progressOf`/无调度器、lane 上的 `fork`/`join`/steer 与 subagent 身份、**worker 运行时 action 逐个落到会话自身（含缺成员拒绝）**、工具注册时机、**worker 工具装在 worker 自己的会话表上（成员数随 `SURFACE_MEMBERS` 变；worker 的 `js-worker` 现已含 `fork`/`join`，与根 §3.1 一致）**、**worker 的 `fork` 落在 worker 自己的 roster（子会话 parent 是 `w1`）**、**Plan 交接经宿主自己的 SDK 工厂起真实 Manager 会话（底稿即首回合 brief、答案取自回合结束、无 SDK 时回 `NO_SESSION_FACTORY`）**、**`js-review` 只在评审阶段可见（Manager 开/关、worker 永不可见）**、**评审阶段由 `turn_start` 打开、review 被接受后关闭（空账 review 也算接受：§5.1 说 `[]` 不拒、只 `added:0`）**、**fission 真实替换 present（现任 `abort` 被调用、组已登记、lane 不进 owner roster）**、**fission 替换失败时整组回滚且 `rollback.released` 等于 lane 数、`groupId` 以会话名开头**、**跨进程重载的 `RUNNING` worker 报 `WORKER_LOST`，`join` 不再空转**、`join` 的拒绝文案区分「表空」与「报告已取走」、Scope 回合边界、fission 的归属（worker 准入 / Manager `InvalidOrigin`、fission lane 也 `InvalidOrigin`）、**relay 一笔原子提交（提交→`newSession`→重新提交的顺序、崩溃点、fence 冷启动重建、屏障裁剪、`parentSession` 记被替换转录的路径、`agent_end` 括号释放前后的延期执行、拒绝时意图不丢、以及 fast-check 性质「任何交错都不留半个任期」）**、**屏障只留操作员输入与继任提示（有 fence 时 Scope 不再二次裁剪）**、`ask` 与 devops 端口、会话级 `startManager` 与冷启重建/scope/语言绑定、四个钩子、工具结果形状、拒收的 code 与 details 透传、回执的真实 `remainingWaitMs`、状态目录。

**上面那句里的 `HostBinding` 一条已经过时**：`runAgent`/`steer`/`progressOf` 已删除，`HostBinding` 只保留宿主状态；Plan 的 DevOps 走专用 lane，worker 走 SDK。

新增契约的离线覆盖：**Plan 的 `devops` 是裸原语且 `this.devops` 不存在**、**专用 `plan-devops` lane 复用、单飞与 task 模型**、**coder/devops 得到 `@task`、Manager 不得**、**取消传播**（`cancelAll` 直接 abort 子会话、未取走结果仍可读、S1 ask / 替换 present / worker 不算操作员中断、重复取消幂等）、**只记失败请求**、**闸门只在判据改变时写**、**Pair Hint 断言 chain 而不是全文副本**。

**真机已验证（2026-11-01，18.14.2，`--resume <successor.jsonl>`）**：

**读回执一律读 `details`（§7.1）。** `host-omp.test.mjs` 的 `receiptOf(result)` 是这条规则的唯一入口：三个编程面答 `{result}`，`fork`/`join` 与 Scope 快照答回执本身；失败、挂起、被延期的回执同样展开——它不看 `ok`，由调用方断言。任何 `JSON.parse(result.content[0].text)` 都是错的，文本现在是 CUE。`cue.test.mjs` 单独覆盖呈现契约：`present` 与 `toCue` 同值、多行字符串是块并按字段缩进对齐、需要时升级为 `#"""` 原始块、不可呈现的值不变成工具失败、宿主工具的 JSON 文本被呈现而散文逐字保留、本适配器自己的结果不被二次替换、Scope 通知是 CUE 而拒绝仍是一行。

1. **跨进程屏障通过。** 继任转录被恢复后，首个请求的角色序列是 `[user, user, assistant, tool]`，`hasIncumbentText=false`（看不到「I'll run」或程序 JSON），`hasNotice=true`；同一会话里再 `review` 回 `tenure: 1` 且 `kind: "recorded"`（继任者的正常工作期，不是又一次接力）。此前「`omp -p` 不会恢复继任转录」的结论是**命令没带 `--resume`**，不是宿主限制。
2. **`parentSession` 通过。** 继任头部里就是被替换转录的路径，宿主原样保留。
3. **`recordGoverns` 通过。** 继任转录里恰好一条 `herdchestra.relay`，其 `successorSessionId` 是**自己的真实 id**（不是 `<id>#tenure-1`），所以恢复它时被采纳、恢复旧转录时被挡。

**仍未在真机上验证**：fission 收敛后 `SessionControl.restore()` 与 `LaneTable.deliver(owner, …)` 能否让真实 Manager 的 `join` 收到合并结果（离线只覆盖端口与花名册）；`.fission.jsonl` 的组记录目前**只写不重放**，真机崩溃恢复需要一次实测才能确认重放策略。`js-review` 的阶段开关（`turn_start` 开、review 接受后关）只有离线用例，真机需确认收窄真的落到了宿主的 active set。**Scope 闸门读到的是活 ctx** 未隔离实测：模型自己写了 `scope` 的调用通过，但不带 `scope` 的调用没有被单独跑过。

**真机缺陷与修复（2026-11-01，`--resume` 继任者，18.14.2）：**

- **症状。** 恢复继任转录后每次运行出现 2–3 条 `Extension error (…/index.mjs): a message already shown would change`，来源是 `PREFIX_DRIFT` 从 `createContextHandler` 抛出、被宿主吞掉；那一轮请求因此**整份未投影**——屏障与 Scope 投影一起丢失，继任者照常作答。
- **根因两处，缺一不成立。** ① `context` 抛出没有停止请求，只丢掉了投影结果（§8.1 a）。② Pair Hint 的链锚与投影用了两种消息形态：`anchor(messages)` 拿到原始宿主副本（提示是裸字符串），`project` 拿到 `toPairHintMessages` 归一后的副本（提示是单个 text part），于是 `assertPrefix` 在下标 0 判漂移，而对话一个字都没变。
- **修复。** ① `context` 不再抛出：失败决定一份守得住的副本并关闭持久请求闸门（§7）。② `anchor` 只改链、且只在本 epoch 没有因 handles 变化开新 epoch 时调用；它收到的就是 `toPairHintMessages(messages)`。③ `anchor` 的锚是「副本 + 本 epoch 的对」（`after = base.length`）：前缀比较在去掉对之后进行（`injector.withoutInjection`），所以对的下标自由、对前后的消息必须逐字不变；这正是继任者第二次请求（宿主历史 + 对）能延续第一次的原因。④ 用户消息的字符串/单 text part 两种形态在 `toPairHintMessages` 里归一到同一形态。
- **离线覆盖。** `a resumed successor's second request extends the first instead of drifting`、`a successor's chain survives a restart in the middle of its tenure`、`any successor history the host can grow never drifts from the anchored copy`（fast-check）、`a failed Pair Hint projection keeps the barrier and the Scope projection`、`a failed Scope projection sends only the operator's message, never the unpruned history`、`the request gate is durable, closed before a request and opened by a clean one`。
- **侧信道回合曾成为链锚，真实会话再次不可用（2026-11-01，omp 18.14.2，会话 `01a19547`）。**
  症状：`omp --extension …/index.mjs --no-extensions` 的第一次 `fork` 就 `PREFIX_DRIFT`，闸门 `allowed:false`，此后每次工具调用被拒。
  **证据**（`.Herdchestra/01a19547-….requests.jsonl`）：`04:04:29.869Z` 的请求 `incoming` 是「操作员消息 + 流式 assistant 片段 + developer 提醒 + `<title-request>`」，`presented` 被写成 6 条；`04:04:29.873Z`（4 ms 后）真实请求带 4 个 tool-call 与 4 个结果，在下标 1 判漂移。
  **机制**（读 `../oh-my-pi` 源码）：`sdk.ts:4454` 的 `transformContext` 是 `context` 钩子的唯一入口，同时装在主 agent（:4656）与会话 agent（:4958）上；`agent-session.ts:12718 runEphemeralTurn` → `convertMessagesToLlm` → `transformContext`，所以**每个 ephemeral 回合都会经过我们的钩子**。`#buildEphemeralSnapshot`（:12929）拼出的副本是「当前消息 + 流式 assistant 片段 + sideChannelNoToolsReminder（role developer、attribution agent）+ 侧提示（role user、attribution agent）」。调用方：标题 fork、composer prediction、omfg、btw、idle maintenance、completion probe、扩展 API。**不经钩子**的侧请求：advisors、compaction、handoff、generate、title-model（走 `convertToLlmForSideRequest` / `buildSideRequestContext`）。
  **为什么不能靠宿主标记**：宿主用 Symbol `agent.message.perCallContext` 标记这些消息，但 `runner.ts:2242` 先 `structuredClone`，Symbol 被丢弃（实测），所以钩子里读不到；识别只能靠上面那两段宿主自己写入的形状（developer + attribution agent，且 user + attribution agent）。
  **修复**：`request.mjs` 的 `isSideChannelRequest`；`SessionState#pairHintProject` 把它作为 **ephemeral 请求**交给 core（`REQUEST_KINDS.EPHEMERAL`：提示仍按链的既有位置注入，让宿主的前缀缓存命中，但**不记录、不移动**链），不开新 epoch、不改 handles、不 anchor；`createContextHandler` 的 `sideTurn` 选项让它**不打开也不结算闸门**、不跑 Scope 记账、失败时保留宿主原样副本。对话请求的字节前缀检查一点没放松。
  **离线覆盖**：`a side-channel turn never becomes the chain's anchor`（重放 4 ms 序列）、`a failed side-channel turn neither cuts its copy nor closes the request gate`、`any side-channel copy the host can build leaves the chain untouched`（fast-check）。
- **宿主改写的同一条回复曾被判成 `PREFIX_DRIFT`（2026-11-01，omp 18.14.2，真实会话 `01a194f5`）。**
  症状：`omp --extension …/index.mjs --no-extensions` 起来后，首个请求的 `fork` 就回 `CONTEXT_PROJECTION_FAILED: … PREFIX_DRIFT: a message already shown would change`，闸门关闭后每一次工具调用都被拒，会话完全不可用；宿主日志里**一个字都没有**（`context` 抛错被吞），唯一证据是 `.Herdchestra/<session>.jsonl` 的 `refused` 记录。
  **首差异可得，且在下标 1**：`previous` 是 `assistant` / `parts:["text"]` / 359 字节，`next` 是 `assistant` / `parts:["text","toolCall"]` / 1181 字节——同一条回复的两种形态。
  **根因**：OMP 在流式期间就构造 `{role:"assistant", content:[], usage: 全 0, stopReason: "stop"}` 的占位消息，随 `text_delta`/`toolcall_end` 原地改写它；`context` 钩子看到的是流式副本，流结束后同一消息变成 `stopReason:"toolUse"` 且带 `toolCall`（`partialArgs`/`streamIndex` 也被删掉）。`normalizedForChain` 只归一了 `timestamp`、`herdchestraSuccession` 与裸字符串 content，于是把宿主自己的改写当成对话被改。**不是** OpenCode 那个「凭空发明 `output` 键」的缺陷：本适配器对 `tool-result` 原样拷贝 `output`。
  **修复**：`request.mjs` 的链归一化额外排除 `usage`、`responseId`、`duration`、`ttft`、`completedAt`、`contextSnapshot`，并在 `toolCall` 分片上去掉 `partialArgs`/`streamIndex`；**不**归一 `stopReason`（它描述这条回复是文本还是调用，属于对话事实，改成忽略会掩盖真正的形态变化）。
  **持久诊断**：`drift-log.mjs` **只为失败的请求**在 `.Herdchestra/<session>.requests.jsonl` 追加一行——`{length, root, firstDivergence:{index,previousDigest,nextDigest}, failure:{stage,code,detail}}`，外加至多 64 条 `dropped` 形状与 pair 位置；干净的请求不写。判据记录（`gateStateRecord`）只在判据改变、写不进去、工具被拒或 nudge 未送达时写。不记正文、工具输入输出、系统提示与密钥。2 MiB 上限用**分段轮转**（当前段改名成 `<file>.1`，再开新段），每次追加只加字节计数，不再整文件重写；写失败不影响请求判定。
  **写失败本身也要留痕（2026-11-01，会话 `01a1958f`）**：那一次请求被拒，`.Herdchestra/` 里却一行都没有，只能去读宿主的会话库才解释得清。现在 `recordRequest` 返回落点（`{ok, file, failure}`）：会话自己的文件写不进去时，记录写到**最近的、能写的祖先目录**里的 `.<文件名>.<pid>.fallback`，并带 `writeFailure{code, detail, target}`；`createContextHandler` 只在写入器**抛错**时重试一次（带 `writeFailure`），闸门开不了/结算不了也照样写这一行。写入器抛错两次才算丢失（`HERDCHESTRA_DEBUG=1` 时打一行 stderr）。离线覆盖：`a request record that cannot be written leaves a named fallback record`、`a refused request whose journal and state directory cannot be written still leaves a named record`。
  **离线覆盖**：`a message OMP finalises after streaming is the same message for the chain`（重放真实首差异形态，旧代码在上面报 `PREFIX_DRIFT index 1`）。
- **Pair Hint 失败曾经泄漏未投影历史（2026-11-01，omp 18.14.2，DevOps probe A）。** 实测：unfenced 会话把 `.Herdchestra/<session>.jsonl` 置为不可读（`chmod 000`），闸门结算为 `allowed:false stage:"pair hint" code:"FACT_CORRUPT"`，**但 provider 收到的请求仍带前任的 15 条消息**。原因是「Pair Hint 失败 → 原样保留」这条规则只对**有边界**的副本成立：有 fence 时边界是屏障，有真实 Scope 投影时边界是 retention；而 unbound 的 Scope 一份都不删，「原样保留」就等于把宿主手上的整段历史发出去。现在 `pairHintFailureCopy`：`fenced || barriered` → 保留；否则只有 Scope 真的缩短过请求才沿用，其余回退到「操作员最新那条」。同时 Pair Hint 不再跑在 Scope 失败后的副本上（那里 `current` 已是处理器自己的最小副本，再按长度判断会把「没发生过的减少」读成 retention）。离线覆盖：`a failed Pair Hint without a fence does not send the unprojected history`、`a failed Pair Hint keeps the barriered copy, which a fence already bounded`、`a Scope failure does not let the Pair Hint redefine the copy as retained`。
- **闸门的 I/O 失败：抛出不是拒绝（2026-11-01）。** 旧实现在「带 fence 的请求 + 闸门写不进去」时抛 `REQUEST_REFUSED`，理由是「宿主会吞掉，但此后每次 `tool_call` 都已被拒」。理由不成立：宿主吞掉抛出后照发它自己那份副本——对继任者就是被替换的整段对话，屏障结果一起丢掉。抛出没换来任何拒绝，只换来一次泄漏。现在：写不进去 → 发最小副本（§7），拒绝存在 `createContextHandler` 的 `gateHeld` 里，由同一钩子对象的 `requestRefusal()` 交给 `tool_call`（`index.mjs` 的 `tool_call` 问的是这个对象，不再直接读 `SessionState`）；`settle` 失败、`open`/`settle`/读闸门抛错、闸门答非所问，一律按同一方向 fail closed。离线覆盖：`a successor's request is sent as the smallest copy when the gate cannot record it`、`a gate that throws instead of answering fails closed, never open`、`the tool gate refuses when the request gate cannot answer at all`、`a held refusal outranks a stale on-disk allowed verdict`、`a real verdict clears the held refusal, so a recovered disk is not refused forever`、`the request gate is durable, closed before a request and opened by a clean one`（末段：撕裂文件与目录占位）。

## 10 交给 devops 的验证指令

本包的代码与离线测试由 coder 交付；以下命令与真机场景**必须**由唯一的 release-devops 执行。凡结果与本文不符，以实际输出为准并回报，不要改断言去迎合。

### 10.1 离线（先跑，成本最低）

```sh
cd /home/kunweiz/Desktop/vibe/Herdchestra
node --test "src/host-omp/**/*.test.mjs"          # 期望：全绿
node --test "src/core/**/*.test.mjs" "src/compose/**/*.test.mjs"   # 确认 core 改动没有连带失败
```

重点看这几条：`a later event's fresh context is adopted, so the deferred relay can commit`、`a deferred relay that cannot run is reported, not silently dropped`、`a relay on a live turn is DEFERRED, not refused, and nothing changes yet`、`a deferred relay reaches exactly the state a synchronous one would`、`a pending intent survives a restart and is performed at the next idle boundary`、`the agent_end hook performs a deferred relay only on a terminal end`、`a relay works on the REAL session_start ctx shape (sessionManager owns the switch)`、`a crash before the reset recovers as the successor, with the same barrier`、`the fence installed in-process is the fence the durable record carries`、`a relay commits retirement, the barrier and the successor as ONE durable record`、`a failed publish performs none of the three parts`、`a crash between the record and the reset leaves a successor, not half a tenure`、`a cold start rebuilds the fence from the transcript, and the barrier hides the old conversation`、`the relay barrier keeps the operator's input and the succession notice only`、`fission starts real worker sessions and replaces the owner's present`、`a top-level session named plan is the planner, not the Manager`、`a worker session is never handed a face its role does not own`。

### 10.2 真机：unpark 能力集（最高优先，core 契约刚改）

```sh
omp -p --yolo --extension /home/kunweiz/Desktop/vibe/Herdchestra/src/host-omp/index.mjs --model <model> \
  '用 js-manager 跑一个程序：先 this.park({why:"probe"}) 并原样返回回执；再用第二个 js-manager 调用 this.unpark("<第一个 callId>", [1,2]) 并返回结果。'
```

判定：第二次调用必须**不**出现 `CAPABILITY_DENIED`；其 `value` 必须是 `[1,2]`（不是 `undefined`）。若出现 `CAPABILITY_DENIED ... is not the target's own`，说明 `targetCapabilities` 仍被传入或 `capabilities` 未按角色注册，回报原文。

### 10.3 真机：fission 的 present 替换

在 `w1`（由 `fork` 起的 worker）里调用 `this.fission(["lane one","lane two"])`。

- 期望：`{"ok":true,"groupId":"w1:2:<n>","lanes":[…]}`；`HERDCHESTRA_DEBUG=1` 的日志里 `w1` 之后不再产出新回合。
- 成功回执带 `{ok:true, owner, replaced, settled, deferredAbort, silence, stoppedNow}`。`settled.ok` 可能为 `false`（调用方正在自己的回合里，宿主还没结算），但 `deferredAbort.ok` 必须为 `true`——回合末 `agent_end` 的 `drainTurnEnd()` 会执行那次 `silence()`。若得到 `PRESENT_NOT_REPLACED`：记录 `settled`/`deferredAbort`/`silence` 原样；**不要**用环境变量或加预算去「调通」它。
- 回滚必须完整：任何拒绝下 `rollback.ok === true` 且 `released` 等于 lane 数。

### 10.4a 真机结论：omp 18.14.2 上**在带接力无法完成**（2026-11-01，隔离树 `/tmp/hc-devops-final`）

以下每条都由真机探针测出，证据在 `/tmp/hc-devops-final/{relay-turn1,relay-turn2,relay-file1,relay-file2,relay-move1}`：

1. **回合内发布成立。** 记录在 `review` 所在回合内经 `appendEntriesAtomically` 落盘，条目带 `published: true`、`retiredTranscript: true`、真实的 `fenceThrough`（实测 4/8/28/46 各一次）。回执 `recordPublished: true` 带 `recordId`。
2. **终局 `agent_end` 已封印，任何写入都不落地。** 原子发布、普通 `appendCustomEntry`、以及 `newSession` 的 `ensureOnDisk` 头部发布，三种都返回 id 或路径而不落盘；`moveTo` 更糟——在封印态下 `assertSourceIsOurs`/`claimDestination` 的 await 永不结算，调用**不返回也不抛错**。原因同源：commit guard 是 `!released && diskEpoch === epoch`，`seal()` 之后永不为真，存储层丢弃暂存 temp 并正常返回。
3. **因此继任转录在边界上无法产生**，继任者自己那份记录也就无处可写。适配器的终态因此是：`RELAY_RECORD_NOT_DURABLE`（`retired:true, barrier:true, successor:null`）、fence 不安装、任期与台账一致、意图保留。**这是拒绝，不是损坏**；**不得**为了让它看起来成功而改成 `ok:true`、把 fence 提前装上、或在回合内调用 `newSession`（那会在运行中的回合下重置转录，并以同一种 epoch 手段丢弃它自己的头部写入——比诚实拒绝更糟）。
4. **`retiredTranscript` 的 fence 只在切换时武装**（`armFence`），因为武装在发布处会让发布它的那个回合之后的请求都套上继任屏障：实测 116 次 `fork`、18 个 worker 会话、进程不结束。

### 10.4 relay：真机已跑出一次成功接力（2026-11-01，omp 18.14.2）

**真机结果（隔离根 `/tmp/hc-devops-relay5`）**：第 1–4 条通过，第 3 条当时带一处偏差（`parentSession: null`），本轮已修，见下。`agent_end` 之后台账 `tenure` 到 1、`milestone` 仍 `execute`、`fence.successor` 为 1；继任转录存在且含 `data.tenure === 1`、`data.fenceThrough === 0` 的 `herdchestra.relay` 条目；顺序实测为「提交 → `newSession` → 重新提交」。

**本轮修掉的两处真机缺陷（都由真机实测发现，离线用例此前全绿）：**

1. **`refreshContext` 未落到适配器真正持有的对象。** 真机 stderr 每个事件后都有
   `this.#session.refreshContext is not a function`。`runner` 隔离抛错，于是适配器此后一直拿着 `session_start` 的 ctx，延期接力读不到活的管理器。现在 `bind()` 先确认对象上真有这个方法再调用，缺失时在 `HERDCHESTRA_DEBUG=1` 下明说，而不是抛给宿主吞掉。
2. **`agent_end` 在宿主自己的 in-flight 括号内发出。** `#endInFlight` → `#flushPendingAgentEnd` → `emit`，此时 `#promptInFlightCount` 仍为 1，而 `ctx.isIdle()` 就是 `!session.isStreaming`。所以处理器里当场提交**永远**读到 `SESSION_STREAMING`；等 `waitForIdle()` 又是自锁（它要等这个处理器返回），宿主还会在 30s 处理器超时处砍掉。现在 `performDeferredRelay` 先 `waitOutsideOwnTurn()` 让出事件循环，等宿主自己释放括号再提交；有界（默认 1s），等不到就原样保留意图并回报 `OWN_TURN_NOT_RELEASED`。

**继任者血缘由 `newSession` 的 `parentSession` 记录。** 宿主把该字段**原样**写进继任头部（`session-manager.ts:2045`），而它自己的调用方并不一致：`fork`/`branchFrom` 写会话 **id**，`resume` 写被替换的**文件路径**（`parentSession: this.#persist ? sourceSessionFile : undefined`）。消费者站在路径这边：`session-listing.ts:495` 把它暴露为 `parentSessionPath`（"Path to the parent session"），`gc-cli.ts:1339` 会跳过既非绝对路径又不以 `.jsonl` 结尾的值。接力是**转录替换**，属 `resume` 形状，所以 `newConversation()` 传**被替换转录的路径**；此前传 `{}`，真机头部因此是 `parentSession: null`，§10.4 第 3 条无法成立。测试夹具现在**建模**这个字段（`realCtxShape().header()`），而不是把参数丢掉——夹具丢掉参数正是这条缺陷在 134 项离线用例下隐身的原因。

**第二轮真机（`--resume`，同一宿主）修掉并验证的两处缺陷：**

3. **继任者的台账写进了前任的文件。** `SessionState.#file` 在构造时由 `sessionIdOf(ctx)` 定下，而接力进程里 `getSessionId()` 仍是**前任**的 id；`adoptSessionId()` 更新了 id 却没被 `handOver()` 在 `saveLedger()` 之前调用，于是 `.Herdchestra/<successor>.session.json` **从未被创建**（真机：接力后再 `--resume` 三次，目录里只有前任的三份文件）。现在 `handOver()` 在 `saveLedger()` **之前**用记录里的 `successorSessionId` 调 `adoptSessionId()`；`#file` 随 id 重新推导，继任任期有自己的台账文件。延期路径早已如此，两条路径现在一致。
4. **继任提示去重按「身份」，绝不按「内容」。** 早期版本为了让提示只出现一次，丢弃了**内容整段等于提示**的消息。那是错的：操作员可以打出任何字，包括恰好与提示相同的整段，而屏障存在的理由正是保留操作员自己的输入（§5.3.2 第 3 步）。两段相同的文字仍是两个事实——一段是操作员写的，一段是适配器写的——只有适配器写的那段可以丢。**内容不是身份。** 现在 `barrierFor` 只丢弃带 `herdchestraSuccession` 标记（消息自身或宿主 `custom_message` 的 `details`）的副本，其余一律逐字保留；判断有歧义时**保留**操作员的消息。**不得**再引入任何基于文本比对的兜底。

**真机已确认（`--resume <successor.jsonl>`）：** 跨进程屏障通过（首个请求 `hasIncumbentText=false`、`hasNotice=true`，再 `review` 回 `tenure: 1` / `kind: "recorded"`）；`parentSession` 是被替换转录的路径；继任转录里恰好一条记录且 `successorSessionId` 是自己的真实 id。此前「`omp -p` 不恢复继任转录」的结论是**没带 `--resume`**。

**仍未在真机上验证：** Scope 闸门是否读到活 ctx——只观察到「模型自己写了 `scope` 的调用通过」，没有隔离跑过不带 `scope` 的调用。

离线已覆盖（`src/host-omp/host-omp.test.mjs`，含 fast-check 性质用例「任何提交/重置/拒绝的交错都不留半个任期」）：一笔提交、发布失败不留痕、崩溃在「已记录未切换」时的冷启动、fence 冷启动重建与屏障裁剪、忙/无原语的拒绝、继任者首评晋级、括号释放前后的延期执行、拒绝时意图不丢。

真机命令：

```sh
HERDCHESTRA_DEBUG=1 omp -p --yolo --extension /home/kunweiz/Desktop/vibe/Herdchestra/src/host-omp/index.mjs --model <model> \
  '用 js-manager 跑一个程序：先 review([{key:"a",value:"x"}])，再 review([{key:"a",value:null}])，把两次回执原样返回。'
```

判定（缺一不可）：

1. 同一个回合里第二次 `review` 的回执应是 `{"ok":false,"kind":"relay","rejection":"RELAY_DEFERRED","handedOver":{"deferred":true,"retired":false,"barrier":false,"successor":null,…}}`——**这是预期**，因为工具调用永远在 prompt 飞行中（§8.1）。真正要判的是第 3 步：回合结束后接力是否真的落地。
2. `.Herdchestra/<session>.session.json` 里 `tenure` 变成 `1`，`milestone` **仍是** `execute`，`obligations` 为空，且有 `fence` 字段（`successor: 1`）。
3. **继任记录落在 `.Herdchestra/<血缘 id>.relay.jsonl`，不是宿主新转录里（真机实测，18.14.2）。** 宿主 `newSession` 会返回一个继任路径，但它**从不写出那个文件**（封印之后 header 发布被丢弃）；因此判定改为：

   - `.Herdchestra/` 下存在 `<血缘 id>.relay.jsonl`，其中一条 `type: "herdchestra.relay.ledger"`，`data.tenure === 1`、`data.fenceThrough === 0`、`data.retiredTranscript === false`、`data.successorSessionId` 是**新**的会话 id；
   - 回执的 `successor.recordInSuccessorTranscript.ok` 为 `false`、`successor.recordInAdapterJournal.ok` 为 `true`——两个事实分开报告，**不得**把前者写成 `true`；
   - 宿主会话目录里**不一定**多出转录；没有它**不是**失败（已实测），但若确实多出一份，其头部 `parentSession` 必须是被替换转录的路径。

   旧判据（「宿主目录多出一份带记录的转录」）在本宿主上不可达，保留它会让人去追一个宿主永远不会写的文件。
4. 后续请求里看不到接力前的对话：`context` 投影结果以继任提示开头，其前只有操作员自己输入。若还能看到前任的 assistant 正文，就是屏障没生效。
5. **单回合内的回执应当是 `RELAY_DEFERRED`，不是 `ok:true`，也不是 `RELAY_BUSY`。** 见 §8.1：工具调用永远在 prompt 飞行中，所以当场执行不可能。`busy: "NO_NEW_SESSION"` 是缺陷（控制面没绑到 `ctx.sessionManager`）；`SESSION_TRANSITIONING` / `SESSION_QUEUED_WORK` 才是真正该重试的忙。**不要**用环境变量或加预算把它「调通」。
   然后**让回合结束**（模型返回），观察 `agent_end` 之后：`.Herdchestra/<session>.session.json` 的 `tenure` 变成 `1`、`milestone` 仍 `execute`、有 `fence`；会话目录多出一份转录，其 `parentSession` 指向旧转录，且含 `customType: "herdchestra.relay"` 的条目。若 `agent_end` 之后什么都没变，是 deferral 没跑（`HERDCHESTRA_DEBUG=1` 下没有记录时属缺陷），回报原文。
6. `NO_ATOMIC_RECORD` 只应在宿主没有 `appendEntriesAtomically` 时出现；`NO_SESSION_CONTROL` 只在任何可达对象都没有 `newSession` 时出现。真机若出现它们，说明宿主版本变了：按 `HACK.md` 第 1、3 条重新核对源码。
7. 回执的 `handedOver.record.fenceThrough` 在成功切换后必须是 `0`（继任转录是空的），`handedOver.transcriptReplaced` 必须是 `true`。若 `transcriptReplaced: false`，读 `switchFailure`，**不要**把它当成成功。

8. **继任任期有自己的台账文件。** `.Herdchestra/` 下必须出现 `.Herdchestra/<successor id>.session.json`（`tenure: 1`、`milestone: "execute"`、`obligations: []`、有 `fence`）。只有前任那份 `.session.json` 就是缺陷（曾经如此：`#file` 在构造时定死，`handOver` 没在 `saveLedger` 前采纳继任 id）。

### 10.4b 真机：跨进程屏障（`--resume`，不是 `-p`）

**`-p` 不会恢复上一段转录**——它总是新开一个会话。要走到继任者的 `context` 投影，必须显式 `--resume` 继任文件：

```sh
# 先按 §10.4 跑一次接力，拿到继任转录路径（头部 parentSession 指向被替换的那份）
SUCCESSOR=<上面多出来的那份 .jsonl>
HERDCHESTRA_DEBUG=1 omp -p --yolo --resume "$SUCCESSOR" \
  --extension /home/kunweiz/Desktop/vibe/Herdchestra/src/host-omp/index.mjs --model <model> \
  '用 js-manager 跑一个程序，调用 review([{key:"b",value:"y"}])，把回执原样返回。'
```

判定（缺一不可）：

1. 首个请求里**没有**接力前的 assistant 正文或程序 JSON（`hasIncumbentText=false`），且**有**继任提示（`hasNotice=true`）。
2. 屏障自己的那份继任提示必须在前，且**操作员的输入一条不少、逐字保留**。若操作员恰好发了与提示同文的消息，请求里出现两段相同文字是**正确**的（一段是操作员的，一段是屏障的）；缺了操作员那段才是缺陷。适配器自己写下的副本按 `herdchestraSuccession` 标记识别并去重，不得按文字去重。
3. 回执是 `{"ok":true,"kind":"recorded","tenure":1,…}`——恢复后是继任者的正常工作期，不是又一次接力；`tenure` 必须是 `1`（不是 `0`）。

### 10.4c 真机：Plan 身份确实存在（从未在真机跑过）

此前**没有任何会话能以 Plan 身份运行**（`ROLE` 无 `PLAN`，顶层一律读成 Manager），所以 Plan 的台账、`draft` 端口、`startManager` 全是够不着的代码。本轮补了 `ROLE.PLAN`、`plan` 面与 `agents/plan.md`，必须实测这条**身份链**，而不只是交接结果。

**先要解决「怎么让顶层会话叫 `plan`」——这一步本适配器定不了，也不该猜。** 本仓库里没有证据表明 OMP 能用某个命令行开关把顶层会话命名为某个 agent 定义（`--agent` 是我未验证的猜测，**不要照抄**）。已确认的只有两点：包内 `agents/*.md` 会进入 agent 搜索路径（`task/discovery.ts:113-120`），以及不走插件装载时需把定义复制到 `.omp/agents/` 或 `~/.omp/agent/agents/`。因此先让 devops 回答一个问题：**怎样启动一个 `agent.name === "plan"` 的顶层会话？** 拿到答案再跑下面的判定。

```sh
# 把 <启动方式> 换成 devops 实测得出的写法；不要用未验证的 --agent。
HERDCHESTRA_DEBUG=1 omp -p --yolo \
  --extension /home/kunweiz/Desktop/vibe/Herdchestra/src/host-omp/index.mjs \
  <启动方式> --model <model> '读一下底稿，告诉我它的第一行。'
```

判定（缺一不可）：

1. `[herd] narrow` 那行的 `role` 是 **`plan`**（不是 `manager`）。若仍是 `manager`，说明 `roleOfAgent` 没认到名字。
2. `active after` **含 `plan`**，且**不含** `js-worker`、`js-manager`、`fork`、`join`。出现任何一个都说明面归属仍按「非 Manager 即 worker」在漏。
3. 模型能用 `this.cat` / `this.write` / `this.ask`，`review` 与 `devops(verboseIntent)` 以裸函数名存在；`this.review` / `this.devops` 应是 `TypeError`（§3.1 一个能力只有一种写法），调用 `this.call` 应报「未知成员」。
4. 首次 `review` 之前的 `this.write` 被拒（`DRAFT_READ_ONLY` 或等价拒绝），首次 `review` 之后可写。

若 1 或 2 不成立，那是本轮的缺陷，回报原文并停下。

### 10.5 真机：Plan → Manager 交接（本轮新增，从未在真机跑过）

本轮有两处**改变了真机行为**：这一条（此前 `startManager` 走 `task`，在真实宿主上必然 `NO_HOST_DISPATCH`）与根 §2.3（`js-review` 的阶段开关）。两者都必须实测，不能只靠离线用例。

```sh
omp -p --yolo --extension /home/kunweiz/Desktop/vibe/Herdchestra/src/host-omp/index.mjs --model <model> \
  '用 js-manager 跑一个程序，调用 review([])，把回执原样返回。'
```

判定（缺一不可）：

1. 回执里**没有** `NO_HOST_DISPATCH`。出现它说明 `startManager` 仍走了 `task` 路径。
2. 出现 `started` 字段且 `started.ok === true`，`started.report` 是 Manager 的**真实答复文本**（不是空串、不是摘要）。
3. `HERDCHESTRA_DEBUG=1` 的日志里，Manager 会话那行 `narrow` 的 `role` 是 `manager`，`active after` 含 `js-manager` 且**不含** `js-worker`。若含 `js-worker`，说明面仍按「lane 创建」而非按角色决定，回报原文。
4. Manager 会话是**独立**的：它的 `narrow` 行里 `id` 不是 Plan 的会话 id，且它的提示词里没有 Plan 的对话内容。

若 3 或 4 不成立，那是本轮的缺陷，回报原文并停下，不要改断言。

### 10.6 真机：`js-review` 只在评审阶段可见

```sh
omp -p --yolo --extension /home/kunweiz/Desktop/vibe/Herdchestra/src/host-omp/index.mjs --model <model> \
  '用 js-manager 跑一个程序，调用 review([{key:"a",value:"x"}])，把回执原样返回。'
```

判定：

1. **第一次回合**（尚未 review）时，Manager 的 `active after` **含** `js-review` —— `turn_start` 打开了评审阶段（`before_agent_start` 只出提示词）。
2. review 被接受后，**下一次请求**的 `active after` **不含** `js-review` —— 阶段已关闭。
3. 收窄失败不得让请求失败：若日志里出现 `narrowFailure`，回报原文。
4. worker 会话的 `active after` **任何时刻都不含** `js-review`（`ownFace` 只给 Manager）。

`HERDCHESTRA_DEBUG=1` 的 `[herd] narrow` 行就是判据；把两次请求的 `active before/after` 原样贴回。

### 10.7 回报格式

每条给出：命令、原始回执（JSON 原样）、以及「与本文一致 / 不一致（差异在哪）」。不要汇总成「大致可用」。

## 程序体内的 `this`（根 §3.1、§3.4）

一个能力只有一种写法（根 §3.1）。三个宿主一致：

- 成员（`cat`、`stat`、`ls`、`write`、`mkdir`、`rm`、`mv`、`where`、`whereAll`、`call`、`devops`、`fission`、`park`、`unpark`、`ask`）只以 `this.xxx` 存在。`bindMembers` 把它们绑到实例上，作用域里没有同名裸函数。
- 顶层原语（`fork`、`join`、`review`）只以裸函数名存在。`compileProgram(source, primitives)` 把它们作为工厂函数的形参传入，`bindMembers` 与 `isPrimitive` 一起保证它们不出现在实例上，所以 `this.fork` 是 `TypeError`。
- 不声明 `class … extends JsProgram` 的程序体被包进同一个类（`host-cline/execution.mjs` 的 `programBody`、`host-opencode/spec/program-execution.mjs` 的 `compile`），`this` 同样是程序实例，两种写法不会因包装与否而分叉。

## 请求副本里待结果的工具调用（根 §6.1）

Scope 的投影按条目取舍，宿主必须把它还原成自己的消息。还原时有一条硬规则：**助手消息保留它原本拥有的每一个 `tool-call` 部分，一个都不少**。

原因不是风格：宿主把本回合的助手消息连同「结果尚未到达」的工具调用一起交给我们。若还原时丢掉那个调用，模型刚读过的一条消息就被改写，而下一次请求（调用已带结果、于是被保留）会把同一条消息改回原样——两次请求之间同一位置的字节变化，正是 §6.1 禁止的前缀漂移。实测样例见 `host-opencode/spec/message-adapter.mjs` 的注释（`ses_e6b5fa739ffef04j5obpcnk3Ze`：第 4 次请求在 index 3 被拒）。

两个方向都不许走偏：
- **不删**：模型读过的调用不能从副本里消失；
- **不加**：模型没读过的调用不能凭空出现。

只有「结果的对调用已经不在副本里」的那一半仍然丢弃——provider 会拒绝一个它没请求过的结果。一条消息的所有条目都被丢掉时，整条消息照旧离开副本（§6.2 的旧 Scope 修剪不受影响）。`selectKept` 是这条规则的唯一落点（`src/host-omp/request.mjs`、`src/host-cline/request.mjs`）。
