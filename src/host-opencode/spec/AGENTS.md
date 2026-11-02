# host-opencode 规格：OpenCode v2 适配器

`src/host-opencode/` 把 core 与 compose 接到 OpenCode v2 上（根 AGENTS.md §7.1，Plan 见 §2.2）。
它只做三件事：注册一个插件、把宿主事件翻成 compose 与 core 的输入、把结果翻回宿主的形状。
它不改宿主源码，不导入 `@opencode/*`（宿主是 TypeScript workspace，本仓库是 pure mjs），
也不猜测宿主没有明说的行为——那些都进 `spec/gaps.mjs`。

## 0 依赖方向与装载

- 依赖：`host-opencode → compose → core`。core 不认识本目录，compose 也不导入它。
- **目录入口是 `src/host-opencode/index.js`。** 宿主把本地插件目录解析成 `index.js` 或
  `server.js`（`packages/plugin/src/host.ts` 的 `resolve`），**不看 `main`、不看 `exports`**；
  只有 `plugin.mjs` 的目录会被静默忽略（`gaps.mjs LOCAL_PLUGIN_ENTRY`，已闭合）。
- **模块形状是 `{ id, effect }`。** `packages/core/src/plugin/module.ts` 只认
  `{ id, effect }` 或 `{ id, setup }`；v2.1.4 只有 Effect 形状拿到 `session`、`tool`、`event`、
  `agent`。因此 `index.js` 的 `default` 是 `{ id: "herdchestra", effect }`，实现在
  `spec/effect-entry.mjs`。仓库层的 `plugin.mjs` **仅测试使用**：宿主从不导入它，
  Promise 形状的 `setup` 也只在那里可达。
- **`tool.transform` 的执行器必须是 Effect。** 宿主的 Effect 路径
  `packages/core/src/tool/runtime.ts` `execute` 在 `Effect.gen` 里调用
  `tool.execute(decoded, context)` 并对结果 `.pipe`；本适配器的执行器是 Promise，所以
  `effect-adapter.mjs` 在 `add`/`list`/`get` 上把它们包成 `Effect.tryPromise`（与宿主
  Promise 路径的 `executePromiseTool` 同形）：`signal` 取自 fiber，`progress` 桥回 Promise。
  漏了这层包装时真机报 `tool.execute(decoded, context).pipe is not a function`，整个工具调用失败；
  `spec/effect-tools.test.mjs` 用真实 effect 4.0.1 钉住这条。
- `spec/effect-adapter.mjs` 把 Effect 域桥成目录内其余模块用的 Promise 调用：
  `Effect.runPromiseWith(pluginContext)`、`transform`/`hook` 的 dispose 记在插件卸载时释放、
  事件 `Stream` 转成 `AsyncIterable`。除此之外不做任何产品判断。
- 装载配置：v2.1.4 写 `"plugins": ["<插件目录>"]`；已安装的 1.19.4 CLI 是 v1 插件宿主
  （要 `server()`，且不给 `session`/`tool`/`event`），无论哪个键都跑不了本适配器
  （`gaps.mjs PLUGIN_MODULE_SHAPE`、`CONFIG_KEY`）。
- **`effect` 是运行时依赖，版本跟宿主 catalog：`4.0.1`。** `effect-entry.mjs` 与
  `effect-adapter.mjs` 静态 `import { Effect, Stream } from "effect"`：`{ id, effect }` 是宿主
  装载后真正执行的入口，运行时必须有这个包。根 `package.json` 与
  `src/host-opencode/package.json` 的 `dependencies` 都写死 `"effect": "4.0.1"`；**不要**改成
  `devDependencies`、`peerDependencies` 或可选依赖，也不要用 `createRequire` 兜底——
  `effect` 是 ESM-only（`"type": "module"`，`exports["."] → dist/index.js`），没有可 require 的 CJS 入口。
- 包根是 `src/host-opencode/`（`package.json` 与 `index.js` 同级）。`files` 只能是包根下的路径，
  所以清单不能放在 `spec/` 里再去引用上一级的 `index.js`；`npm pack` 后 `index.js` 必须在包根。
- `options` 只承载万象术自己的配置项；本适配器读 `options.language`（显式语言），
  其余交给 core 的语言阶梯。

## 1 注册的东西

`setup(ctx)` 按顺序注册，每项只做 §7.1（以及 §2.2 的 Plan）允许它做的事：

| 宿主接口 | 本适配器 | 落在哪 |
|---|---|---|
| `ctx.agent.transform` | 建 `Manager`、`Plan`（primary）、`coder`、`devops`（subagent），其余 agent 置 `hidden` | `spec/agent-definitions.mjs` |
| `ctx.tool.transform` | 加 `js-manager`、`js-worker`、`js-review`、`plan`、`fork`、`join`；`editor.remove` 移除任何角色都不许用的宿主内置（`question`、`subagent`） | `spec/tool-registrations.mjs`、`spec/capabilities.mjs` |
| `ctx.session.hook("context")` | 请求副本：Scope 投影、Pair Hint 合成对、导演段、工具目录收窄、owner 视图记录；**评估轮在此开启** | `spec/context-hook.mjs` |
| `ctx.session.hook("compaction")` / `("generate")` | 同一个投影处理器；`generate` 在投影前标 `kind: "ephemeral"`（借用历史的一次性请求，不覆盖主链前缀） | `spec/context-hook.mjs`、`spec/plugin.mjs` |
| `ctx.session.hook("prompt")` | 只回答**真正的操作员** prompt（`delivery !== "queue"`、非子会话）的未决 `ask`，并重开已结案的工作 | `spec/prompt-hook.mjs` |
| `ctx.tool.hook("execute.before")` | 角色越权、`scope` 注解准入 | `spec/execute-hooks.mjs` |
| `ctx.tool.hook("execute.after")` | 一次模型回合的结算 | `spec/execute-hooks.mjs` |
| `ctx.event.subscribe` | `session.created` 绑定语言；`session.execution.*` 让 worker 退出在途，并对顶层代理的自然停止投递 Nudge（§8.2）；`session.execution.interrupted` 是操作员按停，**不判定、不投递**（`handleTurnEnd` 直接答 `hold: "operator-interrupt"`） | `spec/plugin.mjs`、`spec/turn-nudge.mjs` |

没有别的注册点。压缩与 generate 请求挂同一个 `context` 处理，但本适配器只改
`roleOf(event.agent)` 认得的四个 agent 的请求。

## 2 会话状态（`spec/session-state.mjs`）

一个宿主进程只有一份插件，但会话的事实必须跨工具调用存活。`SessionStates` 按 session id
给每个会话一个 `SessionState`，它拥有：

- 程序表（`ProgramRegistry`）、控制面（`ControlSurface`）与挂起停点（`ProgramCalls`）；
- 评审台账（`ledgerFor("plan" | "manager")`）；
- Scope 书（`scope()`，首次使用时从磁盘记录冷启）；
- 语言绑定（`bindLanguage`，子会话复制启动者）；
- 未结清的 park handles、fission 组记录、最近一次已交付的视图。（Pair Hint 的 epoch 不在会话状态里：它属于 `PairHint` 自己的日志 `{session}.hint.jsonl`，会话状态只提供 handles。曾有一份 `#epoch`/`newEpoch()` 副本，无人读取，已删除——两份代次一旦分叉，谁都不知道该信哪一份。）

持久化只有一件事：`.Herdchestra/{session}.jsonl`，追加式 JSONL，记录类型
`review`、`scope`、`language`、`fission`。最后一行没有换行是崩溃时的半条追加，丢弃；
其他无法解析的行是错误，不静默跳过。程序的续体是内存里的 promise，进程重启不续跑
（根 §3.3 采纳的语义），所以程序本身不持久化。

## 3 提示词

- 三段连续字符串与两种拼接的**唯一原文**在 `src/compose/prompt/`。本目录不抄一份，
  只回答“哪个宿主 agent 用哪种组合”（`spec/prompt-projection.mjs`）。
- `Agent.Info.system` 放 `common + manager` 或 `common + worker`；coder 与 devops 拿到的是
  逐字相同的 `common + worker`。
- **Plan 不进两段投影**（§2.2）：`systemPromptFor("plan")` 抛 `PLAN_OUTSIDE_PROJECTION`，
  Plan 的 `system` 是
  它自己的角色段 `compose/prompt/plan.mjs`（`PLAN_PROMPT`），既不拼 `common`，也不借用
  Manager 的组合。`prompt/plan.mjs` 只写 §2.2 给出的内容：三个打磨里程碑加待执行、唯一底稿、
  编程面、裸 `devops(verboseIntent)` 的同步性与单飞、空账 review 的交接。底稿在
  `plans/<name>.md`（工作区根），不在 `.Herdchestra`。
- 导演消息不进系统提示的组合（根 §5）。它在 `session.hook("context")` 里作为 system 的最后
  一段追加给 Manager，且幂等：已经有这一段就不再追加。

## 4 工具面与两层分离

- 第一层是可见性：`session.hook("context")` 的 `event.tools` 是本次请求的工具目录，
  本适配器按角色删名（`spec/narrowing.mjs`）。`js-review` 另受评估窗口约束（根 §2.3）：窗口是 `SessionState#windowFor`
  （core 的 `ReviewWindow`），**轮次在 `context` 钩子（请求边界）开启**，不是每次工具调用都开；
  `program-tool.mjs` 在建立程序前读它，关着即 `REVIEW_WINDOW_CLOSED`，`narrowTools` 在窗口关闭时
  把 `js-review` 从目录里删掉。同一回合里 review 被受理后再调 `js-review` 不会重开窗口。
  worker 无论如何都被角色拒。
  Manager 只留 `js-manager`、`js-review`、`fork`、`join`；Plan 只留 `plan`；worker 留 `js-worker`
  （外加 compose 面里的 `fork`/`join`）。**任何角色都不留 `question`**：§2.2 只允许 Plan 在 `S1` 用
  `this.ask`（park + interrupt），§2.3 的 Manager 无提问通道，§3.1 的子代理没有提问通道。

  第 0 条：`js-worker` 是 `coder` 与 `devops` **共用的同一个工具**（§2.1：两者系统提示词逐字相同，差别只在 `verboseIntent`）。
  注册必须写成 `workerProgram`（`workerRole(roleOf(context.agent))`），绝不能用 `programFor("coder")` 这类字面角色：
  `makeProgramExecutor` 会用 `actual !== role` 拒绝另一种身份，真机症状就是 `ROLE_DENIED tool:'coder' role:'devops'`。
  这个等式检查保留（第二层授权），错的是注册处写死角色。
  （§4.1 把 `fork`/`join` 给每个可派工的身份；`js-review` 仍只属 Manager）。
- 第二层是拒绝：目录收窄拦不住已捕获的执行器（宿主源码如此），所以 `execute.before` 对
  Manager 与 Plan 的 `read`/`grep`/`glob`/`write`/`edit`/`patch`/`bash`/`shell`/`subagent`/`question`
  直接拒收；每个适配器工具在 `execute` 里再核一次自己的角色（`spec/role-tools.mjs`）。
  `ctx.tool.transform` 同时移除任何角色都不许用的内置（`STATIC_REMOVED_TOOLS`：`question`、`subagent`）：
  宿主的工具表是全局的，所以静态移除只能放这五个角色都不能用的名字，其余按请求收窄。
- 工具输入与输出都是 JSON Schema（`spec/tool-inputs.mjs`、`spec/tool-outputs.mjs`）。
- **声明了 `output` 就必须回 `{ output }`。** 宿主的
  `packages/core/src/tool/runtime.ts` 在工具声明了输出 schema 时，结果缺 `output` 键就回
  `Tool.Error("Tool did not return its declared output")`，模型看到的是工具失败。所以
  `spec/tool-registrations.mjs` 的 `asHostExecutor` 统一把执行器的回执包成
  `{ output: 回执, content: [{ type: "text", text: toCue(回执) }] }`：`output` 由 schema
  把关、从不进模型视野；`content` 是模型唯一读到的文本，按根 §6.3 渲染成 CUE
  （`src/core/cue.mjs` 的 `toCue`），带行号的 `source`、命令 `stdout`、`payload` 一律是多行块，
  不再是一整行转义 JSON。拒绝回执（`ok: false`）按工具补齐 schema 要求的
  字段（`name`、`state: "failed"`、`reason: 拒绝码`、`steered`/`started`、`ok`），因此拒绝也是
  该 schema 的合法结果，不会被宿主当成形状错误。
- `spec/tool-result-contract.test.mjs` 用**真实**执行器与**真实** schema（宿主同款
  `SchemaRepresentation.fromJsonSchemaDocument` 编解码）跑宿主的检查顺序，而不是手写 `{ output }`
  夹具——上一轮 `{ output }` 夹具正是漏掉包装的原因。
- **live 边界测试**：`src/host-opencode/live/tool-boundary.live.ts` 跑在**宿主仓库里**，用宿主的
  真实 `Tool.Service`（真注册、真 `execute`、真 `encodeOutput`）执行本适配器注册的工具。上面那个
  contract 测试证明的是「包装形状对」，live 测试证明的是「宿主真的收下」。它不叫 `*.test.mjs`，
  因此 `node --test "src/host-opencode/**/*.test.mjs"` 不会把它算进依赖无关的适配器套件；运行方式
  写在文件头（复制进 `../opencode/packages/core/test/`，或从那里 `bun test <路径>`）。
  实测（v2.1.4，2026-11）：包装后四条用例全过；把 `asHostExecutor` 改回裸回执，三条立刻变红
  （宿主报 `Tool did not return its declared output`），说明这条测试咬的是真实边界。
- 工具描述里的成员表由 compose 的 `SURFACE_MEMBERS` 渲染（`spec/tool-descriptions.mjs`），
  不会描述面里没有的成员。

## 5 程序与回执

- 一次 `js-*` 调用是一个程序：`code` 字段就是程序正文，回执里的 `source` 就是这段正文加行号。
- 程序写法照 §3.4：`class Js extends JsProgram { async run() { ... } }`。`JsProgram` 基类复用
  `src/host-omp/program.mjs`（同一个类，两个宿主不各写一份）。一个能力只有一种写法（根 §3.1）：
  成员在 `run()` 里只以 `this.x` 存在，`fork`、`join`、`review` 只以裸函数名存在。不带
  `class ... extends JsProgram` 的源码被包进同一个类的 `run()`，`this` 同样是程序实例。
- 求值在 `spec/program-execution.mjs`；`await` 可用，成员 park 时程序体的 `await` 停在原地，
  包装后的成员返回一个永不自行结算的 promise。
- 成员 park 时，compose 的 `park` 已经把程序置为 `parked`；本次调用立即返回九字段回执，
  挂起点的 promise 记在 `spec/park-table.mjs`。
- **停点只由 core 消费一次**：`ControlSurface.unpark` 在控制者请求里已经 `takeSite`，
  `makeResumer` 不再第二次 `takeSite`（重复消费会在停点已不存在时抛错）。
- **能力集来自控制者本身**：`makeControlPort` 把控制者程序实际的 `capabilities` 交给 core，
  `targetCapabilities` 留 null（由 core 读目标自己的集合）；本宿主不给程序发能力，
  所以控制者的集合就是它启动时的集合，绝不以空集冒充。
- **恢复不重跑**（§3.3「不是重跑」）：`unpark` 只结算那一个 promise，并等待程序的下一个停点：
  返回（拿到注入值）、抛错，或再次 park。park 之前的语句已经执行过，不会第二次执行。
- 观察账本按请求打开：一次调用只认它自己与同一会话里仍在程序表中的名字
  （`ProgramCalls.liveNames`），因此同一会话的后续调用可以恢复先前挂起的程序，
  而别的会话看不到它。
- 回执的 `state` 在恢复后的执行结束前保持 `parked`；被挂起的那次调用在程序真正结束时才
  记录最终回执，恢复调用报告目标自己的结果。

## 6 子代理

- `fork(name, verboseIntent)`：根 §4.1 的两参数原语，身份由 `verboseIntent` 的显式声明给出（`role: coder` / `role: devops`）；
  无声明或声明两个一律拒绝，缺省值已删除；`role-tools` 因此把 `fork`/`join` 给 `manager`、`coder`、`devops`，Plan 不给。
  **模型可见的 `tool-inputs.mjs` 里 `fork` 只有 `name` 与 `verboseIntent`（外加 `scope`），`required` 也只列这两个**：
  曾经声明并必填的 `role` 是一个适配器根本不读的第三通道，且会让合规调用在 schema 层就失败。测试
  `scope-schema.test.mjs` 钉住这条形状（属性名、required、`verboseIntent` 描述里必须写明两种身份）。`devops`
  同一时刻只允许一个（`spec/session-creation.mjs`）。建子会话用
  `Session.create({ parentID, title, agent, model })`，**新建** worker 的首个任务描述用
  `Session.prompt({ sessionID, text, delivery: "queue" })` 投递——宿主缺省是 `"steer"`。
  **模型不能自己挑模型**：`fork` 输入没有 `model` 字段（根 §1.1 item 6）。
- 重名且在途即 steer，**steer 用 `delivery: "steer"`**（§7.1：`queue` 只用于新建；排队会让一直在干活的
  worker 永远读不到，而回执仍写 `steered: true`），回执显式给 `steered: true`；否则新建，回执 `steered: false`。
- **子代理跑 task 模型**（根 §1.1 item 6）：`model-inherit.mjs` 的 `resolveChildModel` 先读
  `HERDCHESTRA_TASK_PROVIDER_ID` + `HERDCHESTRA_TASK_MODEL_ID`（两个都设才算配置好，半配置是
  `TASK_MODEL_INCOMPLETE` 拒绝，不静默退回父模型）；未设时退回父会话模型。回执的
  `modelSource` 写明 `"task"` 或 `"parent"`。`forkWorker`、fission lane 与 Plan 的 devops 用它；
  relay 继任者与 Plan→Manager 交接仍用父/Plan 模型。
- **devops 的道路按会话记**：`activation.devopsInFlight` 是 `Map<sessionID, Set<name>>`
  （`devopsRoadOf`）。同名 fork 打到**本会话**已在跑的 devops 是 steer，**不**回 `DEVOPS_BUSY`；
  只有本会话还没有 devops 时才检查并**在任何 await 之前同步预留**名字，失败路径释放，
  `wait: true` 的 `finally` 只在 `receipt.started === true` 时释放。
  Plan 的 devops 用固定名 `plan-devops`、`reuse: true`、`keepRoad: true`：在途即 steer，
  已结束则**复用同一会话**跑下一次调查，不换新会话；单飞拒绝仍由 `activation.devopsRuns` 守。
- `join()` 不带参数，等第一个归来的 worker：对每个在途 worker 并发 `Session.wait`，
  取最先返回者，再读 `Session.get` 的 `outcome`；`outcome` 缺失即仍在忙，不冒充完成。
  返回记录形如 `{ name, role, sessionID, outcome, title }`。
- `fork`/`join` 既是程序面的成员（`js-manager` 与 `js-worker` 都经 compose 的 `ports.fork` 读，根 §3.1 把这两个原语
  同时给了两张面；worker 的 `fork` 落在它自己的 roster，根 §4.1），也是独立工具；
  两种写法走同一张 worker 表（`spec/worker-sessions.mjs`），因此不会出现两套账。
  **两种写法都用 `workerTableOf` 取表，绝不用 `activation.workers.get`。** 表是惰性建立的，
  而建立它正是继任者接管名册（§5.2）的时机；读原始 Map 的 `join` 对一个还没有表的会话回
  `PROGRAM_BUSY`（一条关于程序占用的拒绝，用在一个根本没有程序的会话上），继任者的第一次 `join`
  于是看不到记录替它保下来的结果。
- `session.execution.*` 事件把 worker 从在途名单里移出（`WorkerTable.settled`）。
- **Esc 传播到全部子孙**（根 §1.1 item 2）：`session.execution.interrupted` 在顶层 Plan/Manager
  上由 `cancel.mjs` 广度优先遍历 `activation.workers` 名册、relay 记录的 roster 与
  `activation.lanes`，对每个子孙调 `Session.interrupt`；不 dispose。`this.ask` 挂起、relay
  对退休者的静默、fission present replacement 三种中断**不传播**。被取消的会话记进
  `activation.cancelled`：之后的 `fork`/steer 回 `CANCELLED`，`join` 回带
  `cancelled: true`、`outcome: "cancelled"` 的记录。重复事件幂等。
- fork 成功的子会话登记进 `activation.fissionOrigins`：只有 `fork` 启动的 worker 才能 fission。
- **花名册是持久事实（`spec/worker-roster.test.mjs`）。** `fork` / `steer` / `settle` / `claim` 每次变化都经
  `SessionState.recordWorker` 追加进会话日志，`SessionState.workerRoster` 按序重放成
  `WorkerTable({ restored })` 的初始状态（`activation.mjs` 的 `workerTableOf` 负责建表）。
  理由是一次实测：四个 worker 已 fork、两个已 join，插件因被监视文件变化而热重载，旧表只活在
  activation 里，重载后每次 `join` 都在 **1 ms** 内回「没有可收集的结果」，而 devops 与 coder 仍在同一个
  服务进程里跑。`join` 因此**不得**把「表里没人」读成「无事可做」：只有记录说已结束且已被认领才算没有。
  两条不变量由 `fast-check` 在 `fork`/`steer`/`join`/宿主完成事件/重载的任意交错上证明——进度（有 worker
  在跑或持有未认领结果时 `join` 不得答「没有」）与记账（每个被 fork 的 worker 恰好被认领一次），
  另有一条属性断言重建的花名册与表自己写下的日志逐项相同。

## 7 Plan（根 §2.2）

- `Plan` 是第四个 agent（`mode: "primary"`），不由 `fork` 创建，没有 `fork`/`join`。
- 程序面是 compose 的 `plan`：成员 `cat`、`write`、`unpark`、`ask`；顶层原语 `devops`、`review`。
  没有 `this.devops`，也没有 glob、grep、exec。
- `ask` 只在 `S1` 可用；`Plan` 的 `S1` 初任空账 `review([])` 会代问「我需要干什么？」（§8.1）。
  `ask` 就是 `park({ question })` + `interrupt`，所以 `plan` 必须同时有 `unpark`：操作员的答复由
  AI 在新程序里 `this.unpark(name, answer)` 注入原挂起点（本节 §8）。
- `devops(verboseIntent)` 是**裸顶层原语**（join 语义），经 `makeDevopsPort` 使用固定的
  `plan-devops` worker：在途即 steer，已结束即复用同一会话；等它这一个答复，报告原样返回，
  不做二次摘要；同一任务同时至多一个未决 devops（`activation.devopsRuns`）。
- `review([])` 在「待执行」交给 `startManager(draft)`：创建独立的 `Manager` 会话并用
  `delivery: "queue"` 投递底稿字节。底稿为空时 core 在创建会话之前拒绝（`DRAFT_MISSING`）。
  review 本身不带文本：`source` 只来自 `draft` 端口，路径是工作区根的
  `plans/<safeName(session)>.md`（根 §1.1 item 3：产出物必须用户可见，不放进 `.Herdchestra`；
  见 `gaps.mjs DRAFT_PATH`），适配器不替模型挑路径。
- Plan 的 `write` 不做路径门禁：§2.2 把单文件读写叫作职责边界，不是运行时门禁。
- **底稿路径告诉 Plan 本人。** §2.2 说「系统会告知确切路径」，而系统提示词是按 agent 的、装不下按会话的值，
  所以 `plan` 的**每一份**回执都带 `draft: activation.draftPath(sessionID)`（与 `draft` 端口写的是同一个
  `planDraftPath`，用该会话自己的工作区）。字段在 `tool-outputs.mjs` 的 `PROGRAM_RESULT` 里声明，
  不是靠 `additionalProperties` 兜着。
- **底稿目录在端口建立时创建好。** `atomicWriteFile`（`core/fs.mjs`）把临时文件建在目标
  **同目录**里，所以 `plans/` 不存在时，Plan 的第一次 `write` 会以 `ENOENT`
  失败——而那个错误说的是一个 Plan 从未命名、也无法创建的临时文件（Plan 没有 `mkdir`，
  只有 `cat` 与 `write`）。因此 `makeDraftPort` 在建立时调 `ensureDraftDir`（与 `host-cline`
  `drafts.mjs` 同一契约），并把结果留在 `dirFailure()` 上。
- **准备失败在会话开始处如实报出，不留给写入。** Plan 面在 `makeSurface` 建立时若
  `dirFailure()` 非空，直接返回 `{ surface: null, members: {}, refusal: DRAFT_DIR_UNWRITABLE }`；
  `program-tool.mjs` 把它当回执返回并释放程序名，不启动任何程序体。这样失败原因仍是它自己的
  原因，而不是稍后一条关于临时文件的 `ENOENT`。
- `readDraftFile` 把 `ENOENT` 与 `ENOTDIR` 都当作「这里没有底稿」（返回 `""`）：路径中间某一级
  是文件时同样读不到，而台账需要的是「没有底稿」，好以 `DRAFT_MISSING` 拒绝交接。其他读取
  错误照抛。

## 8 `ask`（根 §3.3 例 7）

`ask` 成员在到达端口之前就 park 程序，所以答案只能是恢复值。宿主没有能在 park 之后继续
等待的提问原语，因此适配器：

1. 把问题记为该会话的未决 ask（`spec/ask-channel.mjs`），带上挂起程序的名称；
2. 用 `Session.synthetic({ resume: false })` 把问题展示给操作员；
3. 操作员的下一则 prompt 经 `session.hook("prompt")` 被当作答复：该钩子的载荷是宿主
   `SessionPrompt.prepare` 触发的 `{ sessionID, messageID, prompt: { text, files?, agents?, skills? },
   metadata?, delivery }`，适配器只读 `event.prompt.text`；答复写进挂起站点
   （`ask` 站点取自己的结果，不取注入值），再用 `unpark` 结算那个 promise，原程序从挂起点继续。
   载荷里没有 `prompt.text` 时视为宿主变更：问题保持未决，钩子回
   `{ answered: false, reason: "PROMPT_HOOK_PAYLOAD" }`，不拿空答复结束提问。
4. 工具回执带 `interrupt: true`，并调宿主的 `Session.interrupt({ sessionID })`
   （`capabilities.mjs` 已列出该能力）结束本回合，控制权回到操作员。

**`ask` 站点取注入值。** core 的 `resumeValue` 把 `unpark(name, answer)` 传入的值交给 `ask` 挂起点
（与显式 `park`、`where` 歧义一致）；只有 `call` 挂起点保留命令自己的结果。钩子把答复写进站点
（`site.value`）只是为了让恢复回执可读，答案本身走注入值。

宿主做不了第 2 步时**不拒绝**：问题仍被记录、回执仍是 asked（`{ ok: true, asked: true, question,
interrupt: true }`），只是操作员可能没有在会话里看到那段 synthetic 文本。`NO_ASK_CHANNEL` 已删除。

### 8.1 `ask` 只在 Plan 的 S1 可用

闸门在 compose 的 `ProgramSurface#handler("ask")`：它读台账自己的 `askAllowed`，Plan 的 `S2`、
`S3`、`待执行` 与 `Manager` 一律以 `ASK_FORBIDDEN_OUTSIDE_S1` 拒绝（程序不挂起、回合不中断、
系统不等待任何人）。`js-worker` 的面可以没有 `ask` 成员（compose 的成员表决定；有成员也只是给具名拒绝）。
适配器不再加第二道判断，只把
拒绝原样交给宿主（程序失败，`error.message` 以该码开头）。

`Plan` 的初任在 `S1` 提交空账 `review([])` 时，台账回 `askUser` + `我需要干什么？` + `interrupt`，
经 `askOperator` 走同一条 synthetic + 下一则 prompt 的通道；`Manager` 侧的初任空账不提问，
回执 `confirm`，里程碑留在「执行」（根 §2.4 第 1 步）——推进里程碑的只有继任者的第一次空账
`confirm`，下一次空账才触发接力。

### 8.2 自然停止的拦截与 Nudge（根 §2.2 唯一出口）

- 规则在 compose（`compose/nudge.mjs`）：顶层代理只有两个合法出口——`S1` 的 `ask`，以及
  「继任者独立验收上一任合格」的终局停止（Plan：继任者在 `S3` 验收、进入 `待执行` 并已提交交接
  `review([])`；Manager：`收尾` 清空台账结案为 `已完成`）。其余任何回合结束都带一条 Nudge：
  列出当前 review 账本全部未完成项（`{ key, value }`；空账则指出本阶段尚未完成评审/继任验收/交接），
  并附硬性口令「当前的 review 账本还有上述事项没有做，立刻去做！系统是无人值守的常授权模式，禁止等待
  用户决策或授权，禁止半途而废。」（`STANDING_ORDER`），CUE 排版，不出原始 JSON。
- 宿主侧在 `spec/turn-nudge.mjs` 的 `handleTurnEnd`，由 `spec/plugin.mjs` 的事件泵在
  `session.execution.succeeded` / `session.execution.failed` 上调用：
  - 身份取自会话（`activation.agents`，由 `context`/`prompt` 钩子与事件写入；事件缺 `agent` 时
    用 roster / lane 认出 worker）。worker 的回合结束是汇报，不拦。
  - 台账优先用本进程已打开的那本（`SessionState#hasLedger`/`ledgerFor`），否则读
    `.Herdchestra/{session}.jsonl` 最后一条 `review` 行（`rowOf`）。行里没有义务列表时按
    「义务未知」报告，绝不读成「没有义务」。
  - 该会话有未决 `ask` 时（`activation.asks.hasPending`）本回合不是自然停止：不 Nudge。
  - 一个回合只判一次：`activation.nudgedRounds` 记该会话已记账的回合（`event.data.id`，缺省
    `messageID`）；同一回合的重复事件（failed 之后重试、interrupt）不再重复投递。
  - 投递用宿主自己的队列：`sessions.prompt({ sessionID, text, delivery: "queue" })`。宿主拒绝时
    结果里带 `delivered: { ok: false, reason }`，不重试、不伪造成功。
- 证明在 `spec/nudge-ask.test.mjs`（`fast-check`）：除 `S1` 外任何阶段/角色调 `ask` 一律被拒；
  `S1` 的 `ask` 挂起并中断，操作员答复经 `unpark` 注入为挂起表达式的值且程序只跑一遍；
  未到出口的回合 100% 触发 Nudge 且列出全部未完成项，到达出口立即停止不再 Nudge，同一回合只
  Nudge 一次。

## 9 fission（根 §4.2）

- 准入、整组回滚、收敛与合并由 compose 的 `Fission`（`src/compose/fission/fission.mjs`）实现；
  适配器只提供端口（`spec/program-ports.mjs` 的 `makeFissionPorts`）、`origins` 与 `capacity`。
- 时序：owner 视图 → 逐条 `Session.create`（同一 agent、owner 的 model）→ 写一条组记录
  （`.Herdchestra/{owner}.jsonl`，类型 `fission`）→ 逐条 `Session.prompt({ delivery: "queue" })`
  投递 lane 文本 → `Session.interrupt(owner)` 静默旧 present。
- lane 文本里的总泳道数读组合层写在 lane 上的 `laneCount`（`lanesOf` 一次性定下，根 §4.2 第 3 步），
  `buildLane` 把它存进 lane 记录、`startLane` 原样用它；适配器不数 `activation.lanes` 表，也不从
  `lane.index` 推。三条 lane 的文本因此都印 `one of 3`，而不是 `1/2/3`。
- 准入之后任一步失败（抛出，或回执 `ok: false`）是**失败组**：已提交的组记录先撤回，再对整组调
  `abortLanes`（逐条 `Session.interrupt`），最后把真实错误原样抛出（程序失败，`error.message`
  是真实原因）；不留部分组，owner 可以重试。绝不改写成 `CapacityExceeded`。
- 组容量上限 32 条 lane（`FISSION_CAPACITY`），只在准入时、任何 lane 建立之前判定，超出即
  `CapacityExceeded`。
- `ConvergenceReady` 与接管 lane 的宣布沿用 compose 的 `LaneGroup`；适配器不自行宣布完成。

## 10 review 与接力（根 §2.3、§2.4）

- `review` 是 Manager 与 Plan 程序面的成员；台账由 compose 的 `ReviewMember` 加 core 的
  `ReviewLedger` 持有，本适配器按会话提供同一个 ledger（`surface-factory.mjs`）。
- 根 §2.4 第 3 步要求退休、屏障、继任是**一条**持久记录。宿主没有这样的原语，所以**适配器自己保存并强制执行**
  这条记录：

  | 文件 | 职责 |
  |---|---|
  | `spec/relay-record.mjs` | `.Herdchestra/{session}.relay.json` 的一次原子替换、读取与损坏分类、`recoverRelay`、`relayGate`、`applyBarrier`、确定性 `briefId` |
  | `spec/relay-facts.mjs` | 按会话读记录；按 mtime/size/inode 判失效，写入后下一次钩子自然读到新值 |
  | `spec/relay-host.mjs` | 执行交接五步，见下 |
  | `spec/execute-hooks.mjs` | `execute.before` 先过 `relayGate`：退休者 `RELAY_RETIRED`，记录损坏 `RELAY_CORRUPT` |
  | `spec/context-hook.mjs` | `applyRelay`：退休者抛 `ContextRefusal("RELAY_RETIRED")`；继任者按 `applyBarrier` 裁剪；损坏抛 `RELAY_CORRUPT` |
  | `spec/original-input.mjs` | 操作员原文的持久事实：`.Herdchestra/{session}.original.json` 的原子一次发布、旧会话 `unrecorded` 标记、`receiptVerdict` 的调用身份判定（根 §2.4 第 3 步） |

- **事件泵只吃真正的迭代对象**（`eventsOf`）：宿主的 `event.subscribe` 给的是 Effect `Stream`，Promise 桥
  （`effect-adapter.mjs`）把它转成 `AsyncIterable`，假宿主直接给可迭代对象。`for await` 对其它任何值都**静默
  产出空**，泵于是永远收不到 `session.created`，适配器就会把每个会话都当成「插件加载前就在跑」，逐个标记
  `unrecorded`（真机症状：每个会话都只有 `.original.unrecorded`、没有 `.original.json`，接力一律
  `ORIGINAL_INPUT_MISSING`）。所以形状在这里显式判定，不可用的形状回报 `UNSUPPORTED_EVENT_STREAM` /
  `NO_EVENT_SUBSCRIPTION`，而不是当空流吞掉。
- **交接顺序（每一步都是它字面的意思）：**
  0. **先读两件事，都还没有碰宿主**：操作员原文（`.original.json`，见下）与前任转录
     （`Session.context`，`GET /api/session/:id/context`，只用来取 `fenceThrough` = 已上屏的消息条数）。
     原文读不到 → 拒（`ORIGINAL_INPUT_MISSING` / `ORIGINAL_INPUT_UNREADABLE`）；转录读不到 → 拒
     （`RELAY_TRANSCRIPT_UNAVAILABLE`）。两种拒绝都**在 `create` 之前**，所以不会留下孤儿继任者。
     **这两件事都读前任自己的工作区**：`handOver` 用 `rootOfSession(predecessor)`（`activation.mjs` 把插件
     每会话解析出的根传进来，与 `prompt`/`context` 钩子写 `.original.json`、`.relay.json` 用的是同一个值），
     不读插件宿主的 `root`。记录写在**前任**工作区，两道闸门也从那里读；写入根与读取根不一致时，闸门会对着
     一个永远不会出现的文件判定，于是前任照常干活、继任者照常开工——正是 §5.3.2 要排除的那种半任期。
     答不出根时才退回插件根（记录与读者仍是同一个值）。
     原文**不**从转录里取：转录的形状是 `PublicSessionMessage`（`{type, text}`），且压缩后最早的用户消息
     只是摘要余项。
  1. `Session.create` 建**惰性**继任者（无 `parentID`、不 prompt），此时它没有任何回合与历史；
  2. 原子写记录（`incumbent`/`successor`/`tenure`/`barrier`/`briefId`/`kind`，`phase: "committed"`）。
     `kind` 是**被退休的台账侧**（`plan` / `manager`）：继任者用同一侧创建（`Plan` 或 `Manager`），
     记录的 `milestone` 也按该侧的里程碑表恢复；Plan 的接力还带 `draft`/`draftRoot`，继任者读写
     **前任的同一个底稿文件**。缺 `kind` 的旧记录按 `manager` 读。
     **写失败 → 删除第 1 步的会话并回 `ok: false`**：没有记录就没有退休；
     记录同时提交根 §2.4 第 4 步要求保留的三样，外加操作员的原始请求：
     - `operatorInput`：操作员原始请求（第 4 步保留项之一），取自 `.original.json` 这一持久事实；
     - `roster`：前任**全部** worker（`{name, role, sessionID, state}`），**不只是活着的**——
       空闲 worker 仍是名字指向的地方，已完成的可能握着没人领取的结果；
     - `unclaimed`：已结束但没人 `join` 过的结果；
     - `boundDevops`：道路绑定的 devops 名字，忙或闲都跨接力不变。
       `activation.devopsInFlight` 是 `Map<sessionID, Set<name>>`（`devopsRoadOf`），
       只取**该前任自己那条道路**上的名字；别的 Manager 启动的 devops 不会交给继任者。
     `roster` 里有条目缺 `sessionID` 时在 `create` 之前就拒（`RELAY_ROSTER_UNPRESERVABLE`）：
     保不住就不提交，不拿假成功换一条记录。
  3. 写 `phase: "sending"`，用 `record.briefId` 投递 `notice`（`delivery: "queue"`）；
  4. 写 `phase: "sent"`；
  5. `Session.interrupt(incumbent)`。失败**不回滚**：记录已经声明退休、闸门已经在挡，回滚只会让退休失效；
     失败写进回执的 `interrupted`。
- **为什么第 3 步可重放**：宿主的 `Session.prompt` 接受调用方给的 `id`
  （`PromptRequest = SessionPrompt.Input & { id?: SessionMessage.ID }`，
  `packages/core/src/session/session.ts:158`），且 `admission.reconcile`
  （`packages/core/src/session/inbox.ts:177`）查的是持久表（`SessionInboxTable` / `SessionMessageTable`），
  语义是「先入者胜、不重新准备载荷」。所以崩溃后同一 `briefId` 重投是**同一条输入**，不是第二条提示。
  OMP 没有 id 参数，那边记录里 `briefId: null`，`recoverRelay` 回 `undeliverable`——**不重投、不假装成功**。
- **恢复**：`handOver` 先 `recoverRelay(file)`。`done` → 直接报同一继任者；`resume` → 同 id 重投后报成功；
  `corrupt` → `RELAY_CORRUPT`，不动任何东西；`undeliverable` → `RELAY_UNDELIVERABLE`。
- **继任者怎么找到自己的记录**：记录仍写在**前任**的 `{incumbent}.relay.json` 下；交接提交时
  另外把**同一份记录**写到继任者自己的 `{successor}.relay.json`（`relay-host.mjs` 的
  `writePointer`，在投递之前、与 `sent` 相同步）。`RelayFacts.record()` 因此**只读自己那一个路径**：
  没有目录扫描，也没有未命中重扫的负缓存（根 §1.1 item 1：32k 条目的 `readdirSync` 曾在每次
  工具调用与每次请求上跑）。继任者后来自己成为退休者时，这份指针被它自己的接力记录覆盖，
  从那一刻起它就是 incumbent 的记录。自己的文件损坏 → `{corrupt: true}`，闸门拒绝；
  文件不存在 → 没有记录。指针写失败时交接不投递（继任者的首个请求会缺屏障），
  重放同一次交接会先修指针。
- **屏障口径**：`fenceThrough` 是接力发生时前任已上屏的消息条数，由 `handOver` 在写记录前取真实计数。
  `fenceThrough > 0` 时，先在副本里找**屏障自己写出的那一句 `notice`**（`firstAnchor`）：命中就从它开始保留，
  那是继任者自己回合的起点；命中不到才退回计数裁剪（`list.slice(fenceThrough)`，副本仍是宿主继承的前任历史时）。
  **锚点只认 `notice`，不认 `operatorInput`。** 前任历史的第一条用户消息通常就是操作员原文本身，用它作标记
  等于把屏障要裁掉的对话整段保留下来——真机实测正是这个后果（`relay-record.test.mjs` 的 `['ship the parser fix']`
  变成整份历史）。`operatorInput` 只作为**事实**写进 `opening`，不参与定位。
  `fenceThrough === 0` 表示**没取到计数**（宿主不报条数），此时**不保留任何前任消息**，只留 `notice`
  （operator 的工作在 `draft` 里随 `notice` 一起送）。不猜"哪条是原始输入"：猜第一条会把前任正在回答的那句话
  当成原始输入泄漏出去，猜最后一条会让退休对话跟着继任者。裁剪作用于**请求副本**，不是宿主已存下的历史
  ——这一点写在回执的 `notGuaranteed` 里。
  **`opening` 是一条消息，不是两条。** 它的正文依次是 `notice`、`# 操作员的原始请求\n\n<operatorInput>`（有则加）、
  `# 底稿\n\n<draft>`（有则加）。操作员原文**不单独成条**：独立的一条用户消息与前任第一条在文本上无法区分，
  任何依赖这种区分的逻辑都会成为泄漏通道。
  **屏障合成的消息必须带宿主的内容数组**：`content: [{type:"text", text}]`，绝不是裸字符串。宿主的
  `model-request.ts` `replaceMedia` 无条件对 `content` 调 `.map`，字符串会让整个模型请求崩掉（真机
  `TypeError: message.content.map is not a function`）。`textContent` / `textOfContent` 是这两端的唯一转换点。
- **继任者的前缀链在屏障处重新起头，且只起一次。** 根 §6.1 要求同一 epoch 的后一次请求以前一次为前缀，
  根 §2.4 第 4 步要求屏障把前任对话切掉——两者在继任者**第一条**请求处冲突：屏障换掉了副本的开头，而 hint 的锚
  仍是前任会话已呈现的字节。`opensPrefixChain(record, sessionID)` 识别继任者，`SessionState#anchorPrefix(record)`
  保证**每条接力记录只锚一次**（键为 `incumbent>successor@tenure`）；命中后 `PairHint#anchor(copy)` 只替换
  前缀锚，冻结文本、epoch、handles、`after` 全部不动，但**该锚点开启一条新链**：下一次 `project` 不与断点前
  那次请求比较（core `PairHint#anchor` 把 `#presentedEpoch` 置空）。不置空时这次锚定对前缀检查不可见——
  下一次请求走普通分支，是否通过取决于新旧副本有没有共同前缀，而不是取决于屏障这个事实。链断点之后的
  第一条请求从此成为链首，第二条起照旧严格。**只锚第一条**是必须的：后续请求若也重新锚定，
  锚点里没有 hint 那一对（宿主从不存它），下一次比较必然漂移——真机曾因此 300+ 次 `PREFIX_DRIFT`。
  **锚必须用 `toHintMessages` 归一后的副本**，与 `project` 比对时用的形态一致：原始宿主副本里的
  `{id, name, input}` / `{result}` 与 hint 视角的 `{toolCallId, toolName, input}` / `{output}` 不同形，
  同一个字都没改也会被判漂移（OMP 适配器实测过同型缺陷，`host-omp/AGENTS.md` 2026-11-01 下标 0）。
  普通会话（没有接力记录）**仍然严格**：改写过已呈现消息一律 `PREFIX_DRIFT`，`successor-prefix.test.mjs`
  用一条独立用例钉住这一点。
- **未结清 handles 变化时由请求钩子开新 epoch。** 冻结的呈现属于整个 epoch（根 §6.1），所以只调
  `hint.setHandles(...)` 不会改变任何已送出的字节：epoch 冻结时的 handles 列表会一直跟着会话，之后新出现
  的 park 永远列不进去，§6.1 的空闲提示就此失效。`context-hook.mjs` 在每次投影前比较「冻结的 handles」与
  `state.handles`，**不等才** `await hint.newEpoch()`；相等时不动前缀，也就不付全量重算。
- **宿主原样交回的副本不算漂移。** 若一次请求副本以上一次呈现为前缀（宿主把刚送出的请求连同那一对一起
  交回，或调用方把副本加长后再投影），core 接受它，并把对在副本里的实际位置记为新的 `after`；同一 epoch
  里改写过已呈现消息仍然 `PREFIX_DRIFT`。`pair-hint-journal.test.mjs` 的「a copy the host hands back as it
  sent it」钉住这一点。
- **继任者的第一行台账**（`SessionState#recordRelayStart` → `openLedger`）：行里写的是**台账自己的 tenure**
  （继任者从接力记录恢复出来时已经是 tenure 1），里程碑是接力记录点名的那一个，`successor: true`。
  同一 tenure 只写一次，判断按**本会话 + 本 kind + 本 tenure**（`openLedger` 与 `recordRelayStart` 两处都是）：
  只按 kind 判断时，同文件里前任的行会让继任者的行永远写不下去。写成「前任 tenure + 1」会让重启后重放出一个
  从未开启的任期；写成「`at` 的前一步」会让继任者的第一次空账 review 变成 `askUser` 而不是 `confirm`。
- **这一行是交接完成的一部分。** 写不下去时回执是 `ok: false` + `reason: "SUCCESSOR_LEDGER_NOT_DURABLE"`，
  同时如实带上 `retired: true` / `barrier: true`（闸门已经拒了前任，收不回）与 `successorRow` 的失败原因。
  恢复方式是重放同一次交接：记录已在 `sent` 相，`done` 与 `resume` 两条恢复分支都会再写一次这一行
  （`recordRelayStart` 按 tenure 幂等），不重发 brief、不新建继任者。
- **继任者的事实继承**（`SessionState#inherit`，`inheritFrom` 声明式）：继任者自己的 `.Herdchestra/{id}.jsonl`
  不存在或为空时**只复制一次**——
  `language` 复制（改绑到新 id，`source: "fork"`）；**Scope 记录一条都不复制**——持久化的 `resetScope`
  只证明旧事件存在，不证明预算当前有效（§6.2），带过去等于把前任的额度当成继任者的确证，所以继任者
  与任何冷启一样必须自己 `resetScope`；Pair Hint 不复制（按新 id 打开自己的 `hint-<digest>.jsonl`、按继承的 binding 绑定，新日志从 epoch 0 起，根 §6.1）；
  review 台账不复制（新任期从空台账开始，根 §2.4 第 5 步）。
- **名册接管**（`SessionState#adoptRoster`，由 `workerTableOf` 在继任者首次取表时调用）：
  根 §2.4 第 4 步要求保留已接收的工作、进程归属与未领取的结果，所以继任者**登记**前任的
  `roster` / `unclaimed` / `boundDevops`——不重发 brief、不重置 lane、不重新 fork。
  已完成的结果登记进 `unclaimed` 队列，仍可被 `join` 领取；`devops` 绑定点直接沿用。
  接管失败不回滚退休（与 `interrupt` 同理）：记录已经声明退休、闸门已经在挡。
  **`join` 也读同一份队列。** `WorkerTable#join` 先认领 `unclaimed` 里最早的一条，没有可认领的才等宿主
  （对每个在途 worker 并发 `Session.wait`）并等表自己的到达信号；`settled` / `adopt` / `adoptUnclaimed` /
  `markFinished` / `fork` 都会发出这个信号。因此「已经结束但没人领取的结果」——包括继任者刚接管进来的那条——
  能被 `join` 拿到，而不会因为「调用时在途名单为空」就回 `null`。`settled` 与 `join` 走同一个
  `#arrive`，事件驱动的结束和 `wait` 驱动的结束因此不可能对「一个已结束的 worker 意味着什么」给出两种答案。
  **接管是惰性的**：只在继任者第一次取 worker 表时发生（`workerTableOf`）。它从不 fork/join 就
  不会接管，这不算遗漏——没取表就没用到名册。记录里不写「已接管」标记：接管本身幂等
  （同名同 id 重复登记无副作用、`adoptUnclaimed` 跳过已有 sessionID），而一个可能撒谎的标记
  比重复调用更坏。
  `openLedger("manager", { at })` 把继任者的起点写成「`at` 的前一步、tenure + 1」，因为 core 的 `openSuccessor`
  才推进里程碑，继任者第一次空账 review 是把它推回 `at` 的 `confirm`。
- **仍不保证的事**（回执的 `notGuaranteed`）：退休者在记录落盘前可能产出最后一回合文本；退休者之后的请求
  被闸门拒绝时宿主记为一次**失败执行**（不是静默跳过）；屏障不改变宿主已存的继任者历史。
  另外**尚未在真机验证**：`adoptRoster` 走的是 `WorkerTable` 与 `SessionState` 的内部路径，不依赖
  宿主新能力，所以假宿主覆盖到的语义即真机语义；真机风险集中在「宿主会话已结束时 `join` 返回什么」。
- **操作员原始输入是独立持久事实（已实现）。** 转录不是原文：宿主会压缩，压缩后的请求副本里最早的用户消息
  是摘要余项。因此原文在 **`prompt` 钩子**里、在本次 receipt 的其它步骤之前**记录一次**，之后所有读者
  （接力记录、继任者 brief、重启后的进程）都读这个事实，绝不再从转录里「取第一条用户消息」。
  文件名是 `.Herdchestra/original-<id 前 16 字符>-<sha256(id) 前 16 位>.json`，标记文件同名前缀 `.unrecorded`。
  **工作区根取该会话自己的 `location`，不是插件上下文的**：真机实测两次——`location.directory` 是服务进程
  的启动目录（`--cwd packages/cli` 就报那个包目录），`location.project.directory` 是服务启动所在的检出目录；
  两者都不是操作员打开的工作区。`ctx.location` 描述的是**插件宿主**，不是会话。因此：
  1. `resolveSessionRoot(sid)` 先问宿主的 `Session.get`，取 `session.location.directory`
     （缺则 `session.location.project.directory`），解析成绝对路径；
  2. 答不出来才退回 `workspaceRootOf(ctx)`（`location.project.directory` → `location.directory` → cwd）。

  解析发生在该会话的 `SessionState` 建立**之前**（`prompt` 与 `context` 钩子入口各一次），因为状态本身持有
  这个根；同一会话只解析一次，写者与读者因此共用同一路径。
  3. **操作员声明的根压过一切宿主答案**：`HERDCHESTRA_ROOT` 或插件选项 `root`（`statedRoot`）。真机上
     `GET /api/session/<id>` 的 `location` 只有 `directory`，且等于服务的启动目录——宿主没有任何字段命名
     操作员打开的工作区，此时只有操作员自己说得清。`workspaceRootOf` 也先问它，否则这个函数名承诺「工作区」
     却答「宿主启动目录」。三者都取不到才退回插件根。

  **四个候选都没有时，写入落在 `process.cwd()`——这是已知的、不可消除的弱点。** `rootOf` 的最后一档是
  `process.cwd()`，因为插件拿不到「操作员打开的工作区」这一事实。后果是具体的：从仓库根启动的宿主会把
  `.Herdchestra/` 写进仓库根（2026-11-01 实测：仓库根出现 `original-ses_*.json`、`ses_*.jsonl`、
  6.3MB 的 `hint-*.jsonl`，内容是一次真实会话的原文与 Scope 记录）。不写入是更差的选择——那会把一次
  真实会话的原文丢掉。**要避免它，操作员必须设 `HERDCHESTRA_ROOT`**（或插件选项 `root`）；
  测试里则用 `mkdtemp` 的临时根，绝不依赖 cwd。

  trace 行同时打印 `root`（**该会话实际使用的根**，即 `rootOfSession`）与 `hostRoot`（插件宿主的根）：
  两者不一致时，日志本身就能说明是哪一个在生效，而不是把宿主根冒充成写入位置。
  键取自**精确的会话 id**（摘要），不是 id 的净化拼写：宿主 id 自由，两个不同 id 可能净化成同一个名字，
  那会让一个会话的原文回答另一个会话。

  - **谁是操作员，由调用的身份决定，不由文字决定**：`parentID` 为 `null`（本激活见过其
    `session.created` 且无父会话）、`delivery` 不是 `queue`、且不携带接力 `briefId`。操作员可以打出
    任何句子——包括看起来像我们自己的 succession notice 的句子——那仍然是操作员的话。反过来，
    fork 子会话的第一条 prompt 是 brief（§4.1），lane 的第一条是 lane 输入（§4.2），都不是原文。
  - **每个自建会话都跑父会话的模型**（`spec/model-inherit.mjs`）：宿主只对 `Session.fork` 继承模型
    （`session/projector.ts:167`），`Session.create` 直接把 `input.model` 存下（`core/src/session.ts:324`），
    所以不带 `model` 的子会话在 runner 里落到 agent 默认、再落到解析器默认（`session/runner/model.ts:79-88`），
    在 kw92 上就是回答 429 的 free 模型。适配器因此对四类自建会话（fork 子会话、fission lane、
    接力继任者、Plan 交接出的 Manager）都在 `create` 之前用 `Session.get` 读父会话的
    `{ providerID, id, variant? }` 并显式传入。父会话没有模型时是**具名拒绝**
    （`HOST_SESSION_HAS_NO_MODEL`；读不到父会话则 `HOST_HAS_NO_SESSION_GET` / `HOST_SESSION_UNREADABLE`；
    调用方根本没给父会话 id 则是 `NO_PARENT_SESSION`），且都在 `create` 之前返回——不会留下半个继任者，
    也不会悄悄退到默认模型。`fork` 的调用方显式给出 model 时以调用方为准。

    `variant` 原样透传：宿主存的是 `Model.Ref`，重建出来的模型会悄悄丢掉调用方选的变体。
    拒绝回执的 `detail` 带宿主自己的话（`HOST_SESSION_UNREADABLE` 时是 `Session.get` 抛出的消息），
    没有宿主消息时才回退成原因码——只报「读失败」而不报「为什么」，操作员无从下手。

    这四类路径都有行为测试（`relay-handover.test.mjs`、`features.test.mjs`）：断言的是**自建会话带着哪个
    model**、以及拒绝时**没有创建任何会话**，不断言调用次序——一次无害的重排不该让测试变红，
    而一条跑得太早的 `create` 也不该因为它仍排在某个下标之后就被放过。
  - **父会话可以问宿主**：事件泵是异步的，首条 prompt 可能先于该会话的 `session.created` 排空。此时表里查不到，
    `parentFactOf` 就用宿主的 `Session.get` 问一次 `parentID`（`null` = 顶层，字符串 = 子会话），并把答案记进表；
    宿主答不出来就仍然是「未知」，判定不记录。任何一次 `context` 请求也把该会话记为已观察。
  - **判定可观测**：`prompt` 钩子的回执带 `originalInput: { reason, parent }`（只在未捕获时出现）；
    `HERDCHESTRA_TRACE=1` 时另写一行 JSON 到 stderr（宿主不展示钩子返回值）。真机上「目录里没有原文文件」
    有多种成因，这一行把成因钉成一个字段。
  - **旧会话不会把后来的 prompt 当原文**：本激活启动时已在运行、却从未被 `session.created` 观察到的会话，
    它的下一条 prompt 不一定是第一条。这样的会话被**一次性**标记 `unrecorded`，此后永不记录；
    它的接力以 `ORIGINAL_INPUT_MISSING` 拒绝，而不是编造一个起点。
  - **发布是原子的**：先写同目录临时文件、`fsync`，再 `link(2)` 到正式名。`link` 在目标已存在时返回
    `EEXIST`，所以先写者胜、读者要么看不到事实、要么看到完整的一份，绝不会读到半截 JSON；
    崩溃留下的临时文件不承载事实，读者不认它。
  - **只写一次**：已有事实不被覆盖（后一条 prompt 是新消息，不是新起点）；读不出来的事实也**不覆盖**
    （操作员的话可能就在里面），接力以 `ORIGINAL_INPUT_UNREADABLE` 拒绝。
  - **接力只读事实**：`handOver` 先读 `.original.json`，读不到就拒（`ORIGINAL_INPUT_MISSING` /
    `ORIGINAL_INPUT_UNREADABLE`），**不退回转录**；转录此时只用来取 `fenceThrough`。
- **`atomic` 只是说明字段**：compose 的 `handOverCompleted` 只认 `ok`/`retired`/`barrier`/`successor`。
- `startManager(draft)`：Plan 的空账 review 用规范路径上的底稿启动独立 Manager 会话；建好但投递失败时
  同样删除该会话，不留半开的 Manager。
  **记录先于提示，且记录里写着这次交接的物理 callId。** 顺序是 `create` → 写**两份**
  `kind: "plan-handoff"` 记录（含 `from: <planSession>`、`handoffId: <callId>` 与 `draft`）：
  先写由 callId 派生的确定性去重路径 `.Herdchestra/handoff-<sha256(callId) 前 32 位>.relay.json`，
  再写 Manager 自己的 `{manager}.relay.json`（`RelayFacts` 读它做屏障与操作员原文）→ 注册身份 →
  投递底稿 → 写 Manager 自己的第一行台账。记录在提示之前，是因为「会话已经建出来」是唯一无法回头的一步：崩溃发生在它之后时，
  Plan 的台账仍停在「待执行」，同一次空账 review 会再来一次。
  **去重只认 callId，绝不认底稿内容。** 根 §2.2 说每次新的空账 review 都创建独立 Manager；
  根 §2.4 第 6 步说同一物理调用重放时返回原回执。因此 `planHandoffOf(root, handoffId)` 只读
  那一条确定性路径（不再扫描 `.Herdchestra`，根 §1.1 item 1）：同一 callId 再来一次 → 报原 Manager
  （`recovered: true`）、不建第二个、修复缺失的 Manager 副本、
  不重投底稿；**不同 callId 即使底稿逐字节相同，也照常新建**——按内容去重会把「两次评审各起一个
  Manager」这条规格悄悄取消。调用方没有 callId 时记录写 `handoffId: null`，它永不匹配：
  分不清重放与新评审时，新建是诚实的答案，猜一个匹配不是。
  callId 由 `program-tool.mjs` 的 `scope.id` 逐调用传入，`program-ports.mjs` 的
  `startManager` 原样传给 `relayHost`。重放不再读父会话模型、不再碰宿主，所以崩溃后的重试是修复而不是复制。
  Manager 自己的台账行同样在成功返回前写入（`recordSuccessorRow(..., "execute")`）：重启后的 Manager
  从「执行」判定，而不是从初任的提问开始。
- **程序层的重放同样按 callId，且载荷必须一致。** `makeProgramExecutor` 在 `calls.open` 之前查
  `ProgramCalls#retired(context.id)`：该 callId 已经结束过，就回它自己的回执（`replayed: true`），
  不建新程序、不再跑一遍程序体。这正是「同一 callId 重放」与「新的一次调用」在适配器里的唯一分界；
  `features.test.mjs` 的「two separate empty reviews start two Managers; a replayed call id starts one」
  把两半都钉住。
- **`review` 的 callId 一路是宿主的物理 call id，台账层也必须自己重放。** 链是
  `context.id` → `makeSurface({ name })`（`program-tool.mjs`）→ compose `surface.mjs` 的
  `relay.review(findings, source, { callId: this.name })` → `ReviewMember.review` → `ReviewLedger.review`。
  程序名**就是** call id，所以这条链上任何一环丢掉 callId，重放就会在台账里再记一次账（再问一次
  操作员、再接力、再起 Manager），而程序闸门（`ProgramCalls#retired`）会把那次错误挡住、让它看不出来。
  闸门与台账是两份记忆，只有台账那份才让规则无条件成立。因此 §2.4 第 6 步要在**台账层**单独钉：
  `features.test.mjs` 的「a review carries the host's physical call id into the ledger, which replays it
  alone」先用一个 callId 跑一次空账 review（`askUser`，操作员被问一次），再拿同一个 id 直接问会话的
  ledger——必须回 `replayed: true`、`kind` 不变、`reviewCount` 不增；换一个 id 则照常记账
  （证明传下去的是物理 call id，不是会话名）。
  **闸门先答是刻意的分工，不是顺序巧合。** 程序层拥有可持久的事实（`.Herdchestra/<session>.calls.jsonl`
  在准入时记 id 与载荷、结束时记回执），所以跨进程的重放只能由它答；台账只记得住本进程的
  `#calls`。两层按构造一致：各自能证明的重放都由自己答，都回 `replayed: true` 与原回执，
  谁先看到就谁答，且都不再产生第二次副作用。
- **同一 callId 换载荷立即拒绝（根 §2.4 第 6 步）。** 载荷是**整个工具输入**——`code`、`scope`，以及
  以后 schema 新增的任何字段；只比源码会放过一个改了注解的重放。比较用 core 的 `stableJson` 编码，
  所以键序不同不算不同载荷。三处各守一段，缺一不可：
  1. **记录**：`ProgramState#recordPayload(payload)` 在程序建立时记一次，再次记录不一致就抛
     `CALL_REPLAY_MISMATCH`（core 的同一个码）。程序就是一次物理调用，所以这份记录与它的 callId 同寿命；
  2. **闸门**：`execute.before`（`execute-hooks.mjs` 的 `replayRefusal`）在任何执行器之前判定——
     该 callId 已结束且 `matchesPayload` 为假，就回 `CALL_REPLAY_MISMATCH`，既不记账也不运行。
     放在这里而不是 `execute` 里，是因为这是宿主唯一「执行前」的拒绝通道，与 `scope` 准入同层；
  3. **回执**：`execute` 里同样的判定再兜一次，回执带 `rejection: CALL_REPLAY_MISMATCH` 与 `detail`，
     并把原回执一并带上（原回执是事实，不是可覆盖的答案）。
  载荷没记录过的程序一律判不等：无法证明相同就不是相同，猜「相同」等于放行一个改过的调用。
- **跨进程的重放要有持久事实（`spec/call-record.mjs`）。** 程序与回执都是内存，进程一换就没了；
  而 §2.4 第 6 步不因重启失效。适配器因此为每个会话追加写 `.Herdchestra/<session>.calls.jsonl`：
  - **准入时**写 `{ id, payloadDigest }`——调用还没跑就先记下 id，所以崩在半路也留下「这个 id 用过了」。
    载荷只留 `sha256(stableJson(input))`，**不再存整个工具输入**（根 §1.1 item 1：源码与注解被
    每条调用复制一遍，却没有增加任何重放需要的事实）；旧行里的 `payload` 读取时哈希，仍能比对。
  - **结束时**再写一条 `{ id, receipt }`，只存重放需要的事实：`state`、`source`、值
    （JSON 存 `json`，存不下存 `text` 并说明是文本）、`error`，以及非空的 `reason`/`payload`/`command`。
    `name` 就是 call id、`pendingOperations` 终局为 0、`delta`/`remainingWaitMs` 属于一次请求窗口，
    都不再落盘。
  `ProgramCalls#restoreFrom` 在闸门里做三件事：本进程没见过、磁盘上也没有 → 记下来、照常执行；
  磁盘上有且带回执 → 用 `ProgramState.restored` 把它放回已退休表，载荷用记录的 digest 比对，
  于是**与同进程完全一样**地判定
  （相同 → 原回执，不同 → `CALL_REPLAY_MISMATCH`）；磁盘上有却没有回执（崩在半路）→ `CALL_REPLAY_LOST`，
  既不放行也不重跑。记录读不出来同样回 `CALL_REPLAY_LOST`：不知道就等于用过，这是唯一不会重复执行的判断。
  记录写不进去时调用被拒（`CALL_REPLAY_LOST` 且 `detail` 说明原因）：记不住的调用不许跑。
  最后一行被崩溃截断就丢弃（与 `{session}.jsonl` 同规则），其他坏行是错误而不是跳过——
  跳过一行等于忘掉一个已用过的 id。
  `features.test.mjs` 的「a replayed call id with a different payload is refused, and every field counts」
  分别覆盖改 `code`、改 `scope`（源码比较会漏的那个字段）与仅键序不同三种情形。
- `askOperator(question)`：初任第一次空账 review 的代问；答复以操作员下一则 prompt 到来。

- **收尾要靠真实资源事实（根 §2.3）。** 「收尾」阶段的空账 review 只有在所有真实执行资源都已收束时才结案；
  core 的 `ReviewLedger` 默认 `resourcesSettled: () => true`，所以适配器必须注入真话：
  `SessionState` 接受一个 `resourcesSettled` 函数（`activation.mjs` 传入，`SessionStates` 按会话转发），
  它经 `workerTableOf` 读该会话名册的 `liveCount()`——有 worker 仍在运行就是未收束，回执是
  `nextReview` + `reason: "RESOURCES_UNSETTLED"`，里程碑停在「收尾」。走 `workerTableOf` 而不是原始 Map，
  是因为继任者接管进来的前任 worker 同样还在运行。默认值只给「从未有过名册的会话」，
  绝不给一个真有名册的会话——那正是「没人干活却结案」的假成功。
  测试：`features.test.mjs` 的「an empty review at close does not close the work while a worker is still running」。

## 11 Scope 与 Pair Hint

- `scope` 注解原样从工具输入里读，不改写（`spec/scope-bridge.mjs`）。会话首次见到时冷启一个
  Scope；模型回合以 assistant message id 计（`gaps.mjs PROMPT_TURN_ID`）。
- **工具输入里的 `scope` 由 `spec/scope-schema.mjs` 声明，不是自由对象。** 它按四种 kind 展开成
  `oneOf`，每个分支 `additionalProperties: false`，`resetScope` 分支强制 `newScopeGoal` 与
  `newScopeRetentionBudget`。这份 schema 从 core 的 `SCOPE_KIND_VALUES` 派生，不另抄一份枚举。
- **注解语言的说明只写一遍，在系统提示词里。** `SCOPE_DESCRIPTION` 只留指向系统提示词 Scope 一节
  的一句话加字段形状；`tool-descriptions.mjs` 的 `SCOPE_NOTE` 已删除，不再往六个工具描述里
  复制同一段（根 §1.1 item 5）。字段名仍由 schema 分支声明，模型照着写就能解码。
- 宿主的顺序是「先按输入 schema 解码，再触发 `execute.before`」，所以形状错误在宿主这一层就被挡下，
  而 `execute.before` 拿到的仍是**未解码的原始输入**——越权字段仍由 Scope 书判定。两层分工：
  schema 只描述注解语言，准入与记账仍归 core。
- **schema 里不能出现 `pattern`。** 宿主编译输入 schema 走
  `JsonSchema.fromSchemaDraft2020_12`（默认 `patterns: "error"`），遇到 `pattern` 直接抛；
  `packages/core/src/tool/runtime.ts` 用 `Effect.try(...).orElseSucceed(() => undefined)` 把它吞成
  `undefined`，于是**整个工具的输入校验静默失效**。因此这里只用 `minLength: 1`，空白串留给 Scope 书拒收。
  `host-cline` 能写 `pattern: "\\S"`，是因为它在自己的 `beforeTool` 里校验，不走这条 codec。
- `resetScope` 在 `execute.before` 准入，随后该调用本身按新额度执行；其余注解在
  `execute.after` 结算。额度耗尽后，非 `resetScope` 的调用在准入处被拒（`SCOPE_BUDGET_SPENT`）。
- 每个会话有两个互不解释的日志文件：`{session}.jsonl` 是适配器自己的事实（语言绑定、Scope 记录、
  评审账本、fission 组），`{session}.hint.jsonl` 是 core `PairHint` 的呈现日志。两套记录形状不同，
  共用同一文件会让双方把对方的记录读成 `FACT_CORRUPT` / 未知类型，所以必须分文件。
- Pair Hint 每次请求从存储事实重建：语言绑定 + 六本资源 + 未结清的 park handles，作为
  根 §6.1 的合成工具调用与结果对（空工具名）追加在请求副本末尾
  （`spec/pair-hint-request.mjs`）。epoch 由 core 的 `PairHint` 记在 `{session}.hint.jsonl` 里，
  会话状态只持有未结清的 handles；两者不一致时由 `context-hook.mjs` 开新 epoch（见 §11.4）。
- 资源对缺一本即失败：加载由 core 的 `ResourceLibrary` 完成，本适配器不换语种顶替；
  加载失败时不注入提示，而不是注入半份。

## 11.5 请求副本的形状（`spec/message-adapter.mjs`、`spec/context-hook.mjs`）

宿主 `session.hook("context")` 的 `event.messages` 是 `packages/ai/src/schema/messages.ts`
的 `Message`：`{ role, content: [ {type:"text", text} | {type:"tool-call", id, name, input}
| {type:"tool-result", id, name, result} | {type:"reasoning", text} | {type:"media", media} ] }`。
`result` 是带标签的值（`{type:"json"|"text"|"error"|"content", value}`），**不是**裸值；
宿主的 `model-request.ts` `replaceMedia` 直接读 `part.result.type`，缺 `result` 会抛
`TypeError` 并让整个请求失败。

core 的 Pair Hint 用的是另一套键：`{type:"tool-call", toolName, toolCallId, input}` 与
`{type:"tool-result", toolName, toolCallId, output}`。因此请求副本要跨两次边界：

1. `toHintMessages`：宿主形状 → hint 形状（在宿主键序里插入 `toolCallId`/`toolName`/`output`）；
2. `PairHint#project` 按持久事实放置或重放那一对；
3. `toHostMessages`：hint 形状 → 宿主形状（丢掉 hint 自己的键，`result` 一律带标签）。

三条由实测确定的规矩：

- **前缀不许被适配器改写。** hint 用 `JSON.stringify` 逐条比较已呈现的请求；任何键序变化
  都会被读成"已发出的消息变了"。因此 `toHostMessages(toHintMessages(m))` 必须逐键还原 `m`，
  适配器也不得把 hint 形状直接写进 `event.messages`。
- **投影不改消息边界。** 宿主一条 assistant 消息可带多个 call，但**每个结果一条 tool 消息**
  （`to-llm-message.ts` 末尾 `.map(Message.tool)`）。`messagesFromItems` 因此不合并相邻 `tool` 消息、
  不重排同一条消息内的部件、不重复发射 reasoning（整条消息已作为 `MESSAGE` 推入时，它的 reasoning
  不再单独作为 `THINKING` 发一次）。这四条都由属性测试钉住（`message-adapter.test.mjs` 的三条
  `property:` 用例）：它们全都曾经把「已发出的消息」改成另一种形状，下一次请求立刻被判 `PREFIX_DRIFT`
  （2026-11-01：`ses_e6afd9d23ffewVMIMyguNze0IJ` 五个 call 被合并成一条 tool 消息）。
- **只补出去的那一侧，不补进来的那一侧。** `result` 缺失只发生在宿主**没有结果可放**的时候
  （`session/runner/to-llm-message.ts`：未执行的本地调用只给 call；仍在跑的调用连 tool 消息都没有）。
  入站方向（`toHintPart`）必须原样照抄宿主的键：为这种 part 补一个
  `output: {type:"text", value:""}`，就等于在 hint 的副本里加了一个宿主副本没有的键，
  下一次请求会被读成"已发出的消息变了"（2026-11-01，`ses_e6b13bab3ffebkFehZo4iHmZzz`，
  差异只在一条失败的 `js-worker` 结果上多出的 `result`/`output`）。补标签是**出站**方向的事：
  `toHostPart` 里 `result` 一律带标签，因为宿主的 `replaceMedia` 会直接读 `part.result.type`。
- **那一对不随宿主历史回来。** 宿主不存合成对，所以下一次请求里没有它；hint 会在记录的
  位置重新放一次（`mode: "placed"`）。适配器先把请求里已有的那一对摘出来
  （`extractPair`），交 hint 处理后再放回原位；hint 若自己重放了（请求里本来就有），
  适配器不再补一份（`pairOf`）。
- **投影保留那一对。** 空名工具（`member: ""`）与 `js-review` 的成员作为 pinned 传给
  `projectRequest`（§6.2 保留 pinned 成员及其配对结果）；否则 Scope 投影会把提示对当成
  旧 Scope 的普通工具拍而删掉。

### 11.6 失败必须阻止请求（§6.1）

`session.hook("context")` 在宿主里**没有失败通道**：`packages/core/src/plugin/hooks.ts`
的 `trigger` 直接 `yield* entry.callback(event)`，回调抛出的异常进入 session drain 的
cause，`packages/core/src/session/execution.ts` 记为 `Failed to drain Session` 并结束本次
执行——**请求不会到达 provider**。因此适配器把每一种投影失败都做成抛出
（`spec/context-hook.mjs` 的 `ContextRefusal`），绝不退回宿主原消息：

| 失败 | code | 何时 |
|---|---|---|
| 提示无法打开（journal 读不了、资源缺一本） | 提示自身的 code 或 `PAIR_HINT_UNAVAILABLE` | `hintFor` |
| 宿主消息无法翻译 | `MESSAGE_COPY_FAILED` | 投影前 |
| Scope 投影拒收（如调用与其结果保留不一致） | 投影的 code | 投影后 |
| 提示放置失败（未绑定语言、资源缺一本、前缀漂移） | 提示自身的 code（`RESOURCE_PAIR_INCOMPLETE`、`PREFIX_DRIFT`…） | `PairHint#project` |
| 注入后副本里没有那一对 | `PAIR_HINT_MISSING` | 组装后 |

旧行为（吞掉失败、发送未注入提示的请求）已删除：§6.1 明说资源缺一本、语言非法、字节前缀
漂移都是失败，静默漏注入等于悄悄取消 §6.1。`context-refusal.test.mjs` 覆盖以上分支，并断言
失败时 `event.messages` 保持宿主给的样子。

## 12 缺口

`spec/gaps.mjs` 是唯一清单，每条写清：问题、宿主源码给出的证据、后果、它挡住了什么。
当前仍未闭合的：

| id | 挡住什么 | 现在的行为 |
|---|---|---|
| `WORKER_NESTING` | 嵌套 fork | 不经过内建 `subagent`，深度上限不生效；未实测 |
| `LOCAL_PLUGIN_ENTRY` | 按路径装载 | 已闭合：目录带 `index.js` |
| `PLUGIN_MODULE_SHAPE` | 模块形状 | 入口导出 `{ id, effect }`；Promise `setup` 仅供测试；v1 宿主不可用 |
| `CONFIG_KEY` | 配置键 | v2.1.4 用 `plugins`；1.19.4 读旧 `plugin` 且跑不了本适配器 |
| `PROMPT_TURN_ID` | 回合内多次结算 | 以 assistant message id 为回合 |
| `PROMPT_ANNOTATION_TRANSPORT` | 注解位置 | 只认工具输入里的 `scope` 字段 |
| `SYNTHETIC_TOOL_PAIR` | 空名工具对 | 按 §6.1 注入该对；provider 是否接受空名未实测 |
| `ASK_CHANNEL` | `ask` 的答复通道 | synthetic 展示问题，下一则 prompt 作为答复并 `unpark` |
| `PROMPT_HOOK_PAYLOAD` | prompt 钩子的载荷形状 | 已闭合：读 `event.prompt.text`（宿主 `SessionPrompt.prepare` 触发）；载荷不符时未决 `ask` 保持未决并回 `answered: false` |
| `FISSION_PRESENT` | 同一身份的多个 present | 建 lane 会话后静默 owner；宿主不保证被静默的会话能否再被 prompt |
| `FORK_SESSIONS` | 会话域可达性 | 插件持有 `ctx.session` 并交给 activation |
| `PLAN_ENTRY` | Plan 角色 | 已注册 `Plan`（根 §2.2 优先于 §7.1 的三 agent 列表）；系统提示是 Plan 专有段，不是 `common + manager` |
| `RELAY_SESSION_CONTROL` | 接力的一笔记录 | 适配器自有记录并强制执行：惰性继任者 → 原子写记录 → 同 id 投递 → `sent` → `interrupt`；两道闸门读记录；`briefId: null`（OMP）时 `undeliverable`、不重投。细节见 §6.1 |
| `DRAFT_PATH` | 底稿路径 | 无任务元数据时按会话派生工作区根的 `plans/<safeName(session)>.md`（根 §1.1 item 3：用户可见产出，不放 `.Herdchestra`）；目录由 `makeDraftPort` 建立，建不出来则 Plan 面以 `DRAFT_DIR_UNWRITABLE` 在开始处拒绝；盘上没有底稿则 `draft.read()` 为空，review 以 `DRAFT_MISSING` 拒绝，绝不拿别处文字顶替 |
| `CONTEXT_HOOK_FAILURE` | 请求钩子如何阻止一次请求 | 宿主无失败通道，钩子抛出即让本次执行失败、请求不发出（`hooks.trigger` → drain cause）；适配器据此抛出 `ContextRefusal` |
| `MESSAGE_SHAPE` | 请求副本的部件形状 | 已闭合（源码）：`{type:"tool-call", id, name, input}` / `{type:"tool-result", id, name, result}`，`result` 为带标签值；副本经 `message-adapter.mjs` 两次跨形状转换 |
| `HINT_PAIR_PLACEMENT` | 合成对是否随宿主历史回来 | 宿主不存合成对：每次请求由 hint 按持久位置重新放置；适配器先摘除已有的一对，避免两份 |
| `REFUSAL_DIAGNOSTICS` | 拒绝之后还能查到什么 | 已闭合：hint 在抛出前写一条 `refused` journal 记录（`code`/`epoch`/`index`/两侧形状与摘要），`context-hook` 把同一批诊断放进 `ContextRefusal.details` 交给宿主日志。两侧只以形状出现（`role`、部件类型、长度、SHA-256），**不含消息文本、工具 I/O 或凭据**。此前只剩一句话和一个下标（2026-11-01 的真机漂移只能靠翻宿主机库才说清） |

拒绝一律是回执（`spec/refusals.mjs`），不抛异常、不伪造结果。

## 13 测试

`node --test "src/host-opencode/**/*.test.mjs"`（计数以实际运行为准）。测试只用假宿主：`setup` 的每个注册点都是
普通函数，`spec/` 的模块除 `node:` 与 `src/{core,compose}` 外不依赖任何宿主包。
`plugin.test.mjs` 覆盖程序、park/恢复、Scope、提示词与收窄；`features.test.mjs` 覆盖
`review` 接力、Plan 的 `devops` 与交接、`ask`（含宿主 `prompt` 载荷与畸形载荷不消费提问）、
fission 的准入/建组/回滚、以及语言继承；接力覆盖顺序（create → 记录 → 带 `briefId` 的 prompt → interrupt）、
记录落盘内容、退休者在两道闸门被拒、继任者请求被屏障裁剪、损坏记录拦下所有人、以及继任者的一次性事实继承；
`relay-record.test.mjs` 覆盖记录层，`relay-replay.test.mjs` 覆盖台账重放；
`pair-hint-journal.test.mjs` 覆盖 journal 键与冷启；
`scope-schema.test.mjs` 用宿主的 JSON-Schema codec 覆盖 `scope` 注解的模型可见形状：解析器收的注解
必须能解码（属性测试）、裸 `resetScope` 被点名拒收、schema 与 `core/scope` 的 kind/必填字段一致、
以及 schema 不含宿主 codec 编不过的 `pattern`；
`entry.test.mjs` 覆盖入口形状与 Plan 的提示词边界；`effect-tools.test.mjs` 用真实
effect 运行时覆盖 `tool.transform` 的 Effect 执行器包装；`tool-result-contract.test.mjs` 覆盖
`{ output, content }` 结果契约与每种工具的拒绝回执；`message-adapter.test.mjs` 覆盖宿主
`Message` 形状的往返与合成对摘除/放回；`context-refusal.test.mjs` 覆盖 §6.2 的每个失败分支。
`cancel.test.mjs` 覆盖 Esc 的广度优先传播、`ask`/relay/fission 三种豁免与重复事件幂等；
`features.test.mjs` 另覆盖固定名 `plan-devops` 的复用、task 模型（含半配置拒绝）与
「继任者指针取代目录扫描」。
`nudge-ask.test.mjs` 用 `fast-check` 证明 §8.1–§8.2 的三条不变量：`ask` 除 `S1` 外一律
`ASK_FORBIDDEN_OUTSIDE_S1`；`S1` 的 `ask` 挂起 + 中断后经 `unpark` 注入答复并只续跑一遍；
未到出口的自然停止 100% 投递含全部未完成项与常授权口令的 Nudge，出口处立即停止，同一回合只 Nudge 一次。

## 13.1 真机证据（v2.1.4，隔离配置，`plugins` 指向 `src/host-opencode`）

已证实（宿主源码与真实服务器一致）：

- `/api/plugin` 报 `{"id":"herdchestra","state":{"status":"active"}}`，入口是 `index.js`；
- 四个 agent 注册成功（Manager/Plan `primary`，coder/devops `subagent`），系统提示长度
  7315 / 563 / 7335 / 7335（coder 与 devops 逐字相同）；
- 六个工具全部通过宿主的 schema codec（`Skipping invalid tool registration` 计数 0）；
- 请求流水线走完 `SessionModelRequest.prepare` → 本适配器的 `context` 钩子 →
  `replaceMedia` / `boundImages`，失败栈起点在 `packages/ai/src/route/executor.ts`（provider
  传输层），不在 `model-request.ts`；旧崩溃
  `TypeError: undefined is not an object (evaluating 'part.result.type')` 计数 0；
- 本次请求没有触发任何 `ContextRefusal` code。

**未证实**：模型真正作答。本机唯一有凭据的 provider 是 `opencode` 的 9 个免费模型，全部返回
`Free usage limit reached` 或 `Model is unavailable`（外部配额墙，与适配器无关）。因此
`js-worker` 的真实回执、第二次请求后 `/api/session/<id>/context` 中不含 `pair-hint:` 部件、
以及 `fork` 的真实回执都还没有真机数据；模型可用后按 §3.3 的步骤复测。

### 13.1.1 模型真的作答了：`scope` 注解的契约缺陷（live6，2026-11-01）

换到有凭据的 provider（`kw92/lazy/task`，variant `default`；`high`/`max` 报
`provider.no-route`）后，模型确实调用了工具，暴露出一个真契约缺陷：

| 会话 | agent | 模型发出的 `scope` | 结果 |
|---|---|---|---|
| `ses_e6c2a1408ffeJN429PIzIgPU27` | devops | `{"kind":"resetScope"}` | `executed: false`，`SCOPE_REFUSED` |
| `ses_e6c27de35ffeLqpZlJiRtP6vYf` | Manager | `{"kind":"resetScope"}` | `executed: false`，`SCOPE_REFUSED` |

两次都是 `resetScope` 但缺 `newScopeGoal` 与 `newScopeRetentionBudget`。拒收本身是对的——没有目标的
reset 无法开闭环——错的是**模型无从知道要写这两个字段**：`tool-inputs.mjs` 把 `scope` 声明成自由对象
`{type:"object"}`，模型能读到的全部信息就是「有个叫 scope 的字段」。`host-cline` 与 `host-omp`
早已按 kind 声明（`host-cline/scope-schema.mjs`、`host-omp/program-tools.mjs` 的 `SCOPE_PROPERTY`），
只有本宿主漏了。

修法有两半，缺一不可：

1. **补上声明**（`spec/scope-schema.mjs`）：按四种 kind 展开 `oneOf`，`resetScope` 分支强制两个字段。
   这决定了模型**看得到**什么——它是模型唯一能读到注解语言的通道。
2. **让拒收说出具体原因**（`spec/plugin.mjs` 的 `execute.before`）：宿主的顺序是
   `beforeExecute`（钩子）**先于** `executeTool` → `decodeInput`（`packages/core/src/tool.ts`），
   所以本适配器抛出的拒收**永远先于**输入 schema 的解码。schema 写得再准，模型也读不到它的报错。
   live7 实测：裸 `resetScope` 仍只回 `SCOPE_REFUSED: The scope annotation was refused; the call did not run.`
   而 `admit` 早已算出 `scope.resetScope: newScopeGoal must be a non-blank string`——那句话被 `plugin.mjs`
   的模板串丢掉了。现在 `detail` 一并抛出，模型拿到的是
   `SCOPE_REFUSED: … (scope.resetScope: newScopeGoal must be a non-blank string)`。

`execute.before` 收到的仍是原始输入，准入与记账仍归 core；schema 只负责让模型知道该写什么，
而**点明错在哪**由 core 的 `detail` 负责，因为它是唯一真正跑到的那一层。

`scope-schema.test.mjs` 钉住三条不变量：schema 永不比解析器更窄（属性测试：`parseAnnotation` 收的
注解必须能解码）、schema 的 kind 与必填字段与 `core/scope` 逐项一致、schema 不含宿主 codec 编不过的
`pattern`。

### 13.1.2 真机复测（live7，2026-11-01）：两处新事实

隔离配置、端口 47961、`kw92/lazy/task#default`。**`executed: false` 不是失败信号**：宿主对插件工具
一律写这个字段，成功调用也一样；判据是 `state.status` 与 `state.output`。

**有效注解确实能跑。** `devops` 会话发出
`{"code":"return 1 + 1;","scope":{"kind":"resetScope","newScopeGoal":"probe","newScopeRetentionBudget":5}}`，
回执 `status: "completed"`、`value: 2`。Scope 链路本身是通的。

**但跑到这一步之前，还有一个与 Scope 无关的真缺陷：`js-worker` 被写死成 `coder`。**
`plugin.mjs` 曾注册 `[TOOL_NAMES.worker]: programFor("coder")`，于是每次 `devops` 调用都回
`{"ok":false,"rejection":"ROLE_DENIED","tool":"coder","role":"devops"}`，与 `role-tools.mjs`
（`js-worker` 归两个 worker 身份）以及 `execute-hooks.mjs` 的第一层 owner 检查自相矛盾。
现在按调用会话的角色分派（`workerProgram`），执行器仍校验它拿到的角色，身份之外的人照样被拒。

**输入 schema 解码没有在 live7 生效。** 裸 `resetScope` 仍由 Scope 书拒收（`SCOPE_REFUSED`），
不是由宿主解码拒收（那会写 `Invalid arguments … scope.newScopeGoal: Missing key`）。
已在隔离环境证实 codec 本身没问题：宿主的 `jsonSchema()` 与 `Cache` 路径对同一份 schema 都正常编译、
正常拒收（六个工具全部 `COMPILES`，`Cache` 返回 codec 而非 `undefined`），
`Skipping invalid tool registration` 计数为 0。原因未定位，按上面的顺序事实，它也不影响结论：
**`execute.before` 先跑，所以 schema 的报错无论如何都到不了模型**——这正是第 2 半修法的理由。
这一点仍记为未闭合事实，不写成已解释。

## 13.2 真机复测步骤

1. 隔离配置目录（不碰用户状态）：`OPENCODE_CONFIG_DIR=/tmp/hc-oc`，其中 `opencode.json` 写
   `{"plugins":["<仓库>/src/host-opencode"]}`（目录，不是 `plugin.mjs`）。
2. `bun run --cwd packages/cli src/index.ts serve`（v2.1.4 源码 checkout；npm 上的 1.19.x 是
   v1 插件宿主，跑不了本适配器）。
3. `POST /api/session {"agent":"coder"}` → `POST /api/session/<id>/prompt`，要求模型调用一次
   `js-worker`，`code` 为 `return 1 + 1;`，`scope` 为
   `{"kind":"resetScope","newScopeGoal":"probe","newScopeRetentionBudget":5}`。
   看三件事：回合是否完成；日志里有没有 `part.result.type` 的 `TypeError`；`GET
   /api/session/<id>/context` 里的工具结果是否带标签值。
4. 同一会话再发一条普通消息，确认 `GET /api/session/<id>/context` 中**没有** id 以
   `pair-hint:` 开头的部件（合成对只在请求副本里）。
5. `POST /api/session {"agent":"Manager"}`，让模型调用 `fork`
   `{"name":"w1","verboseIntent":"role: coder. Say ok and stop."}`，看回执
   `{name, steered, started, modelSource}`
   与子会话是否收到首个 prompt。

任何一步失败时，日志里的 `ContextRefusal` code（`PAIR_HINT_UNAVAILABLE`、
`RESOURCE_PAIR_INCOMPLETE`、`PREFIX_DRIFT`、`MESSAGE_COPY_FAILED`、`PAIR_HINT_MISSING` 等）
直接指出是哪个分支，而不是一句笼统的“请求失败”。

## 14 2026-11-01 审计修订（本轮修复的事实）

这一节只记**行为改变**，详细理由在对应源码注释里。

- **接力的侧（`kind`）**：`handOver` 接收 `kind`（`plan` / `manager`），继任者用同一侧创建
  （`Plan` / `Manager`），记录持久化 `kind`；`recordRelayStart` / `openLedger` / `restoreLedger`
  按该侧恢复里程碑。缺 `kind` 的旧记录按 `manager` 读。Plan 接力的 `draft`/`draftRoot`
  让继任者读写前任的同一个底稿（`draftRootOf` / `draftNameOf`）；`draftFor` 与 `draftPath`
  现在都从会话自己的 root 派生（不再是插件根）。
- **Plan 交接的两个相**：`startManager` 先写 `phase: "sending"`（带确定性 `briefId`）→
  `Session.prompt({ id: briefId })` → `phase: "sent"`。prompt 抛错且会话确实被删除时记录一并删除；
  `recoverRelay` 对 `plan-handoff` 在非 `sent` 相回 `resume`（同 id 重投），`sent` 才是 `done`。
  `startManager` 检查 `recordSuccessorRow`，失败回 `SUCCESSOR_LEDGER_NOT_DURABLE`（与 `handOver` 一致），
  并在成功后把交接事实写进 Plan 自己的台账行（`noteHandoff`，`handoff: true`）。
- **steer 的投递**：同名在途 worker 用 `delivery: "steer"`；`queue` 只用于新建。
- **devops 道路按会话**：`activation.devopsInFlight` 是 `Map<sessionID, Set<name>>`；
  同名 fork 到本会话在跑的 devops 是 steer，不拒；检查与预留同步发生在第一个 await 之前；
  `wait: true` 的 `finally` 只在 `receipt.started === true` 时释放。
- **按调用的上下文**：`withCall` / `withWorkerRole` 与 activation 上的可变槽
  （`relayContext` / `askSession` / `devopsSession` / `askProgram` / `laneRole`）已删除；
  `portsFor({ call })` 把会话与物理 call id 传给 relay/devops 端口，lane 角色按**会话**记在
  `activation.laneRoles`。两个并发的工具调用不会再读错彼此的上下文。
- **第二次挂起会唤醒恢复者**：park 通道在 park 后调 `ProgramCalls#wakePark`，只有**正在等待**的
  resumer（`waitStop` 已标记 `awaited`）会被唤醒；首次 park 不会预结算未来的 resumer。
  被恢复的程序再次 `ask` 时，通道自己记录并展示问题（第一次仍由调用等待同一 promise）。
- **`ask` 只认真正的操作员 prompt**：`delivery === "queue"`（适配器自己的投递）与子会话都不消费
  未决问题。prompt 钩子已恢复过的 ask，之后模型再 `this.unpark(name, answer)` 回**已结算回执**
  （`alreadyResumed: true`），不再 `STALE_CONTROL`；resume 抛错也只写进 `answered.error`。
- **`question` 工具**：Manager / worker 权限、Plan 权限、目录 `KEEP` 全部移除；`STATIC_REMOVED_TOOLS`
  在 `ctx.tool.transform` 里 `editor.remove("question")` / `("subagent")`；`execute.before`
  对 Manager/Plan 的 `question` 直接拒。
- **评估窗口**：轮次只在 `context` 钩子（请求边界）`beginRound()`；`makeSurface` 传
  `reviewSurface({ openRound: false })`；窗口关闭时 `narrowTools` 删掉 `js-review`。
  同一回合的第二次 `js-review` 不会重开窗口。
- **重放优先**：`beforeCall` 与 `program-tool` 都先查 callId 重放（同载荷直接回原回执，
  不执行任何东西），再查 `js-review` 窗口与 `relayRefusal`。`program-calls.restoreFrom`
  保留整份存储回执（`error`/`reason`/`payload`/`command`/`delta`/`remainingWaitMs`）。
- **请求期限**：程序在准入时拿到 `Deadline`；`withDeadline` 到期时 `surface.expire()` 把程序挂起、
  为该挂起点登记 park 条目，调用回 park 回执。`built.refusal` 也经 `withDraft`。
- **Nudge 的交接事实**：`turnExit` 不再硬编码 `{ handedOff: true }`；`handleTurnEnd` 从
  `handoff: true` 的台账行（本 tenure）读出事实。`eventMilestone` 只接受该角色合法的里程碑名，
  `session.execution.failed` 的 `reason` 句子不再被当成里程碑。
- **压缩与 generate**：`compaction` 挂同一个投影处理器；`generate` 在投影前标 `kind: "ephemeral"`。
- **身份文件按摘要命名**：`identityFile` 用会话 id 的 SHA-256 前 32 位，不再用可碰撞的替换+截断。
- **无消息结果的 `tool` 消息保留**：`messagesFromItems` 只对**有 callId** 的孤立结果做半对丢弃。
- **测试临时目录**：`spec/test-temp.mjs` 统一登记并在进程退出时删除；property 场景在
  `runScenario` 结束即删。四个曾泄漏的测试文件（`worker-roster`、`context-refusal`、
  `successor-prefix`、`relay-record`）改用它。
- **死代码**：`session-state` 的重复 `get root()`、`program-tool` 的 `withSignal`、插件里未用的
  `fissionTableOf` 导入、`activation.disposed` / `fissionWorker` 已删除；`disposeActivation`
  清空 `fissionOrigins` / `identities` / `laneRoles`；`park-table` 头注释改为真实语义（恢复在原地继续，不重跑）。

## 15 2026-11-02 契约修订（根 §1.1 六项）

本节只记**行为改变**；理由在对应源码注释里。

- **Plan 的 `devops` 是裸原语**（`spec/primitives.mjs`、`program-execution.mjs`）：
  compose 的 `SURFACE_PRIMITIVES`、本宿主的 `HOST_PRIMITIVES`，以及一条真实的宿主事实
  ——`fork`/`join`/`review` 仍列在 compose 的 `SURFACE_MEMBERS` 里（处理器由 `surface()` 提供），
  但按 §3.1 由宿主绑定为顶层原语——三者合并；`compile` 把原语从 `this` 移进顶层参数。
  `this.devops` 不存在；`devops(verboseIntent)` 是 join 语义，等一个答复并原样返回报告。
- **dedicated devops**（`program-ports.mjs`、`plugin.mjs`、`worker-sessions.mjs`）：
  固定名 `plan-devops`；在途即 steer，已结束则 `reuse: true` 复用同一会话（`delivery: "queue"`），
  `keepRoad: true` 让名字留在 Plan 的道路上；`activation.devopsRuns` 的单飞拒绝不变。
- **`verboseIntent` 统一命名**：`fork` 输入、`forkWorker`、`WorkerTable.fork`、
  `makeForkJoinPorts`、`makeDevopsPort` 与测试全部改名；`briefId`（relay 记录）保持原名。
  没有 `brief` 别名。
- **task 模型**（`model-inherit.mjs`）：`resolveChildModel` 读
  `HERDCHESTRA_TASK_PROVIDER_ID` + `HERDCHESTRA_TASK_MODEL_ID`，两个都设才用；
  半配置是 `TASK_MODEL_INCOMPLETE` 拒绝；未设退回父模型。`forkWorker`、fission lane、
  Plan devops 用它，回执报 `modelSource`；relay 继任者与 Plan→Manager 仍用父/Plan 模型。
  `fork` 输入没有 `model`。
- **Esc 传播**（`cancel.mjs`、`plugin.mjs`）：顶层 Plan/Manager 的
  `session.execution.interrupted` 广度优先中断全部子孙（worker 名册、relay roster、fission lanes），
  不 dispose；`ask` 挂起、relay 静默退休者、fission present replacement 不传播。
  `activation.cancelled` 让后续 `fork`/steer 回 `CANCELLED`，`join` 报
  `cancelled: true` / `outcome: "cancelled"`；重复事件幂等。
- **接力指针**（`relay-facts.mjs`、`relay-host.mjs`）：继任者只读自己的
  `{successor}.relay.json`，交接提交时写入同一份记录；目录扫描与未命中重扫删除。
  指针写失败则不投递，重放先修指针；指针/`plan-handoff` 记录不会被误当成本会话自己的退休记录。
- **calls journal**（`call-record.mjs`、`program-calls.mjs`）：只存
  `sha256(stableJson(input))` 与重放所需回执（`state`/`source`/值/`error`，非空
  `reason`/`payload`/`command`）；旧 `payload` 行读取时哈希。`name`、`pendingOperations`、
  `delta`、`remainingWaitMs` 不再落盘。
- **花名册重放去二次方**（`worker-sessions.mjs`）：`restoreRoster` 与 `WorkerTable` 的未认领队列
  改为 `Map<sessionID, entry>`，插入/替换/删除 O(1)。
- **Scope 文案**（`scope-schema.mjs`、`tool-descriptions.mjs`）：`SCOPE_DESCRIPTION` 只留
  系统提示词指针与字段形状，`SCOPE_NOTE` 与六处追加删除；`oneOf`、必填字段与 schema 结构不变。
- **底稿路径**（`program-ports.mjs`）：`PLAN_DRAFT_DIR = "plans"`，`plans/<name>.md` 在工作区根；
  Plan 的模型可见文字写明产出位置、不得放进 `.Herdchestra`，除产出与操作员附件外只读。
- **Pair Hint 的 Chain 切换**（`context-hook.mjs`、测试）：`projectionBoundary` 收
  `presented: hint.chain`；冷启动测试与 successor 前缀测试改断言 `length`/`digests`/`root`/
  `pairIndex`，不再比较完整消息数组。拒绝诊断的链一侧只保留 digest 形状。
