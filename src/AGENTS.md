# `src/` 全局架构师笔记

> 本文件承担传统项目里 `ARCHITECTURE.md` 的职责（根 `AGENTS.md` §8.1 第 2 条）。
> 根 `AGENTS.md` 是唯一语义来源；本文件只描述**代码怎么分层、怎么装配、边界在哪**。
> 两者冲突时以根规格为准，并立即修正本文件与代码。适配宿主的绕行手法不写在这里，写在根 `HACK.md`。
>
> 核对基准：`src/` 当前树（`find src -type f` 实测 224 个文件），根规格 §1–§8。
>
> 版本基准：**Herdchestra 1.0.0**。根 `package.json` 与三个子包 `package.json` 同为 `1.0.0`；
> 许可证状态见根 `LICENSE`（开源项目，具体许可证尚未注明），四个清单统一用
> `SEE LICENSE IN …` 指向该文件，不写 `UNLICENSED`。

---

## 0 一句话结构

三个彼此独立的层，依赖只朝一个方向：

```
src/core     纯 Node、不认识任何宿主，也不认识 compose
   ↑
src/compose  把 core 的部件与宿主端口拼成“模型看到的那一面”；不导入任何 host-*
   ↑
src/host-*   三个适配器，只把宿主接口映射成 compose 的端口
```

- `src/core/*` 只 import `node:` 与同目录模块。
- `src/compose/*` import core，绝不 import `host-*`。
- `src/host-*` import core 与 compose，绝不反向。
- 适配器之间原则上互不依赖。当前唯一的例外是 `host-opencode/spec/program-execution.mjs` 直接 import
  `../../host-omp/program.mjs` 的 `JsProgram` 基类（同一份类不写两遍）。这是**刻意的复用**，不是新的依赖方向：
  该类不读宿主、不读会话状态，只做“把成员绑到实例上、把 park 变成挂起”。若将来它长出宿主知识，必须搬回 `core`。

没有任何构建步骤：全部 pure `.mjs`（唯一的 `.js` 是三个宿主入口，见 §3），无转译链（根 §8.2 第 1 条）。

### 0.1 发布形态：根包是唯一可安装单元

1.0.0 只发布**根包 `herdchestra`**。三个子包 `@herdchestra/host-{omp,opencode,cline}` 一律 `private: true`，
随根包一起分发，不单独上架。原因不是偏好，是实测：三个适配器的模块都 import `../core` 与 `../compose`
（OpenCode 是 `../../`），把任一子包目录单独 `npm pack` 后 `import` 入口会得到 `ERR_MODULE_NOT_FOUND`——
兄弟目录不在包里。根包把整棵 `src/` 与 `LICENSE` 带出去，入口才是 `package/src/host-cline/index.js`
这种“兄弟目录俱在”的形状。

**发行面（实测 `npm pack --dry-run --json`，1.0.0 为 176 个文件）。** 根包 `files` 是
`["src", "LICENSE", "!src/**/*.test.mjs", "!src/**/AGENTS.md", "!src/**/live/**", "!src/compose/HOST-PORTS.md"]`：

- 进的：全部运行期 `.mjs`/`.js`、四个 `package.json`（根 + 三个子包，npm 强制，工作区清单也在其中）、
  `src/core/pair-hint/resources/{六个 id}/{en,zh-CN}.md`（Pair Hint 运行期按路径读，不是 import，
  见 `resources.mjs` 的 `RESOURCE_DIR`）、`src/host-omp/agents/*.md`（OMP 的 agent 搜索目录）、
  `src/host-cline/README.md`、`LICENSE`。
- 不进的：任何 `*.test.mjs`（含 `src/host-opencode/spec/*.test.mjs`）、任何 `AGENTS.md`（包级与单模块规格
  属仓库内部）、`src/host-opencode/live/**`（在宿主仓库里跑的边界测试）、`src/compose/HOST-PORTS.md`、
  根 `HACK.md` 与 `scripts/`。`!src/**/live/**` 必须显式写：负向条目只有在命中路径上恰有正向条目时才生效，
  否则被默认的 `*` 拒绝，反而成了“包含”。
- 判据是可机检的：打完包后，每个随包 `.mjs`/`.js` 的每条相对 import 都必须落到随包文件上；
  零 `*.test.mjs`、零 `AGENTS.md`。改 `files` 后跑一次这条检查，不要只数文件数。
- 任何运行期模块 import 测试文件都是错误：先 grep，命中就改代码，不能靠 `files` 把测试排除掉来掩盖。
  1.0.0 实测：没有运行期模块 import `*.test.mjs`。

三个子包私有，`files` 与根包同一口径：`host-omp` 是
`["index.mjs", "*.mjs", "agents/", "!*.test.mjs"]`，`host-opencode` 是
`["index.js", "plugin.mjs", "spec", "!spec/**/*.test.mjs", "!spec/AGENTS.md", "!live/**"]`，
`host-cline` 是 `["**/*", "!**/*.test.mjs", "!AGENTS.md"]`（手写清单漂移过，见该文件 `//files`）。

- **入口**：三个子包各自 `main`/`exports` 都指向自己的入口文件——`host-omp` 是 `./index.mjs`，
  `host-opencode` 是 `./index.js`，`host-cline` 是 `./index.js`；OMP 的加载清单 `omp.extensions` 与
  `exports` 并存，前者给宿主发现，后者给 import。根包没有“名字即入口”：仓库里没有任何模块 `import
  "herdchestra"`，根入口是三个宿主，所以根 `exports` 只给子路径——
  `./host-omp` → `./src/host-omp/index.mjs`、`./host-opencode` → `./src/host-opencode/index.js`、
  `./host-cline` → `./src/host-cline/index.js`，外加 `./package.json`（只暴露清单本身，不把
  `package.json` 当模块入口，否则 `import "herdchestra"` 会得到 `ERR_IMPORT_ATTRIBUTE_MISSING`）。

- 根 `package.json` 的 `cline.plugins[].paths` 指向 `./src/host-cline/index.js`，**这是发行用的那一份**；
  `src/host-cline/package.json` 里同名块相对自身目录写 `./index.js`，只对“单独装这个目录”成立，
  单装会因上述 import 失败，故两处形状保持一致但不等价。
- 根包 `dependencies` 只有 `effect`（`4.0.1`，宿主 catalog 版本）。OpenCode 入口静态 import 它，
  所以它必须是运行期依赖，不能降为 dev/peer/可选。

---

## 1 `src/core`：纯支持库

宿主无关、可单测、无副作用的零件。`index.mjs` 是唯一的公共出口，重新导出以下模块：

| 模块 | 事实它拥有 |
|---|---|
| `util.mjs` | 参数校验、`normalizePath`/`isInsideRoot`、稳定 JSON、字节长度 |
| `errors.mjs` | `CoreError`（`.code`、`.details`）、`CODES`、`REJECTIONS` |
| `clock.mjs` | `deadlineFor(intent)`（每 UTF-8 字节 50ms）、`Deadline`、真实钟与假钟 |
| `command.mjs` | `nodeCommandRunner`（`/bin/sh`，`onData` 逐块上报）、假 runner |
| `text.mjs` | `findSpans`、`whereParkPayload`、`nearestLine`、`decodeUtf8` |
| `fs.mjs` | `FileMembers`（`cat`/`stat`/`ls`/`write`/`mkdir`/`rm`/`mv`）、`atomicWriteFile` |
| `where.mjs` | `TextMembers`（`where`/`whereAll`） |
| `program.mjs` | `ProgramState`、`ProgramRegistry`、`STATES`、`TERMINAL_STATES`、`isTerminal` |
| `receipt.mjs` | `buildReceipt`、`programView`、`CommandView`、`PARK_REASONS`、`RECEIPT_FIELDS`、`withExtra`、`sourceWithLineNumbers`、`isTerminalReceipt` |
| `control.mjs` | `ControlSurface`、`ObservationLedger`、`resumeValue` |
| `review.mjs` | `ReviewLedger`、`ReviewWindow`、`PLAN_MILESTONES`（`S1`/`S2`/`S3`/`待执行`）、`MANAGER_MILESTONES`（`execute`/`close`/`done`）、`REVIEW_KINDS`、`REVIEW_REJECTIONS`、`WINDOW_STATES`、`draftWritable`、`ASK_MILESTONE`、`askAllowed`、`INITIAL_EMPTY_QUESTION` |
| `cue.mjs` | `toCue`：模型可见结构化数据的唯一渲染器（根 §6.3），宿主无关 |

`src/core/scope/` 与 `src/core/pair-hint/` 是**独立子包**，有各自的 `AGENTS.md`，**故意不从 `index.mjs` 重新导出**：
它们各有一份内部状态与资源目录，调用方按路径 import（`core/scope/index.mjs`、`core/pair-hint/index.mjs`），
这样任何一处都不会因为 `export *` 而意外拿到另一套语义。

`src/core/{command,fs,park,review,where}/AGENTS.md` 是上述单模块的局部规格；`park/` 覆盖
`program`+`receipt`+`control`+`clock`+`errors` 这一组生命周期事实。

**`ask` 的准入是 core 的一条事实，不是各适配器的各自判断。** `askAllowed(ledger)` 只对 `kind === "plan"`
且 `milestone === "S1"` 的台账回 `{ ok: true }`，其余回具名原因（`NO_LEDGER` / `NOT_PLAN` / `OUTSIDE_S1`）；
compose 的 `ask` 成员把拒绝包装成 `ASK_FORBIDDEN_OUTSIDE_S1`。这条判定不读宿主、不读时钟，所以三个宿主
不可能对「谁能提问」给出不同答案。

**core 不做的事**（`core/AGENTS.md` 的 §Not in this package）：宿主适配、`JsProgram` 类、按角色的成员投影、
fission lane、提示词拼接、持久化、Scope/Pair Hint 渲染。

### 1.1 Pair Hint：`PREFIX_DRIFT` 的根治、合法边界与 4 MiB 压缩

`core/pair-hint/` 的完整规格在 `src/core/pair-hint/AGENTS.md`；这里只记 1.0.0 消费者必须知道的三件事。

**「同一条消息」由正向语义白名单定义（`canonical.mjs`）。** 前缀规则只比较 `canonicalKey(message)`：
`canonicalMessage` 只抽取模型真正读到的内容——`role` 与按序的语义部件（`text`、`tool-call`、`tool-result`、
`reasoning`）——并把每个对象的键排序。**白名单里没有的键一律不参与比较**，所以宿主新增或改写任何簿记字段
（`timestamp`、`usage`、`duration`、`responseId`、`partialArgs`、`streamIndex`、`completedAt`、
`contextSnapshot`、将来才有的字段）**都不可能**造成漂移。这不是黑名单的加强版：旧实现里三个宿主各有一份
`CHAIN_EXCLUDED` / `toHintPart` 黑名单，那正是全仓 `PREFIX_DRIFT` 反复发作的根因——一份要跟着宿主字段跑的
名单永远落后一个字段。宿主消息对象本身从不被改写：`PairHint#project` 返回的仍是调用方的副本，逐字节不变。

**合法的链断点只有 `REBASE_REASONS` 里的四个，且必须由 `compose/request-boundary.mjs` 证明。**

| reason | 谁决定 | 判据 |
|---|---|---|
| `relay` | 根 §2.4 第 3 步的接力屏障 | 屏障切掉了被替换的对话，副本不可能延长任何先前呈现 |
| `scope-reset` | 一次已结算的 `resetScope` 修剪了已上屏的节拍 | 投影自己的输入与输出之间丢了消息（`isSubsequence` 成立） |
| `scope-remove` | 一次已结算的 `removeLast` 就地改写了节拍 | 投影的答案里有它从未收到的项 |
| `history-compacted` | **宿主**把转录持久替换成摘要加尾巴（Cline 的压缩，并已保存） | 宿主亲口报告了这次改写，且副本确实变了 |

`projectionBoundary` 只回答「投影是不是这次改动的作者」，**不看记录**：一条 `resetScope` 记录只证明模型
请求过，不证明这份副本变了。比较一律走 `canonicalKey`，所以「投影改动了副本」与「链会拒绝」不可能互相矛盾。
适配器自己丢消息（半个对）不得授权重锚（`reason: "filtered"`）。`history-compacted` 是唯一由宿主报告、
而不是由投影决定的原因，且调用方必须**证明**它（宿主产出的压缩结果），猜不出来的适配器只能传 `null`，
于是链拒绝——安全方向。

**OMP 的并发旁路请求只注入前缀，绝不覆盖 `#presented`。** OMP 自己的 `<title-request>` 与
`Ephemeral side-channel turn` 走同一个 `context` 钩子，却**不是**这条对话的一步（宿主不把它们写进转录）。
`core/pair-hint/pair-hint.mjs` 的 `REQUEST_KINDS.EPHEMERAL` 让它们按主链的位置放置合成对（命中 provider 的
前缀缓存），然后**什么都不记录**：epoch 与 `#presented` 都不动，所以主线下一份请求仍与主链比较。适配器负责
判定「这是什么请求」（OMP 的标记是 Symbol，`structuredClone` 会丢掉它，只能按宿主写下的两种形状判定），
本包负责「这对链意味着什么」。隔离一旦失效，症状是侧通道的截断副本被当成链的一步，随后每个真实请求都在
下标 1 判 `PREFIX_DRIFT`。

**拒绝一律带可诊断的 code，不静默修补**（`errors.mjs` 的 `PAIR_HINT_CODES`）：

| code | 何时 |
|---|---|
| `PREFIX_DRIFT` | 同一 epoch 内已呈现消息的字节要变；新请求不以旧请求为前缀；`after` 计数超出请求长度；同一请求里出现两对 |
| `EPOCH_CONFLICT` | epoch 已推进，调用方仍按旧 epoch 提交 |
| `RESOURCE_PAIR_INCOMPLETE` / `RESOURCE_MISSING` / `RESOURCE_UNKNOWN` | 资源对缺书、整份缺失、或指向未知资源 |
| `INVALID_LANGUAGE` / `INVALID_ARGUMENT` | 语言未绑定或参数不合法 |
| `FACT_CORRUPT` | 日志里的 fact 无法折叠 |

`PREFIX_DRIFT` 的 `details.index` 是第一个不同的消息下标——诊断先看它。**没有豁免模式**：重放也是发出去的
请求，宿主改写了已呈现消息同样要拒。锚必须是 `base`，不能是 `base` + 对（见 `pair-hint/AGENTS.md` §153 那条
真机记录）；锚错位会让继任者第二次请求必然漂移。

**日志压缩阈值 4 MiB**（`facts.mjs` 的 `JournalStore.COMPACT_BYTES = 4 * 1024 * 1024`）。一份长对话的请求副本
就是数 MB，而每次请求都重写整份副本——实测 33 次请求 / 67 条消息写出 34 MB，仓库日志曾达 133 MB。折叠只保留
最后一条 `presented`（每条都整份替换 `#presented`），更早的副本是读者永远到不了的死重。压缩是原子重写
（写在旁边再 rename），发生在 append 链内部，因此本 store 的 append 不会与它交错；它**不**防另一个进程同时
append——那是日志本来就有的限制（一个会话一个驱动）。

---

## 2 `src/compose`：组合层

它自己不读文件、不跑命令、不建会话。每一次真实动作都经 core 或经一个宿主端口。

| 目录 / 文件 | 职责 |
|---|---|
| `prompt/files.mjs` | `common`/`manager`/`worker` 三段连续字符串（唯一原文） |
| `prompt/projection.mjs` | 只有两种拼接：`COMMON+MANAGER`、`COMMON+WORKER`；`systemPromptFor("plan")` 抛 `PLAN_OUTSIDE_PROJECTION` |
| `prompt/plan.mjs` | `PLAN_PROMPT`：Plan 自己的角色段，与上面两种组合互斥 |
| `prompt/director.mjs` | `<vibe-mode>` 导演消息：独立角色段，不进系统提示组合 |
| `program/surface.mjs` | `SURFACE_MEMBERS`（唯一成员表）、`ProgramSurface`、`ROLES` |
| `program/members.mjs` | 成员实现：`coreMembers`（文件、文本、`call`）、`reviewMembers`（`park`、`unpark`、`review`、`ask`、`fork`、`join`、`fission`、`devops` 的端口转发） |
| `program/role-brief.mjs` | `roleOfBrief`：从 `brief` 的显式声明解析 worker 身份（`coder`/`devops`），无声明或声明两者一律拒绝 |
| `fission/` | lane 组：准入顺序、整组回滚、`ConvergenceReady`、拓扑合并、一次性终局 |
| `relay/review.mjs` | `ReviewMember`：台账判决 + 宿主副作用（接力、启动 Manager、代问用户） |
| `nudge.mjs` | 无人值守出口判定（根 §2.2）：`evaluateTurnExit`、`buildNudgeMessage`、`verifiedCompletion`、`STOP_REASONS`、`STANDING_ORDER`、`USER_FACING`。纯函数，不读宿主、不读时钟 |
| `request-boundary.mjs` | 合法投影边界的重锚定：`projectionBoundary`（回 `replaced` / `shared` / `reason`）、`shouldAnchor`。判据是「投影自己的输入与输出」加上宿主亲口报告的持久改写，比较一律走 `canonicalKey` |
| `ports.mjs` | `PORT_METHODS`：端口形状与装配时校验（`MISSING_PORT`） |
| `errors.mjs` | 组合层自己的拒绝类型（`COMPOSE_CODES`、`ComposeError`） |
| `HOST-PORTS.md` | 交给宿主负责人的接口单：每一步必须回报什么 |
| `cue.mjs` | 组合层通往 CUE 的唯一门：`renderReceipt`、`renderNotice`、`refusalText`（根 §6.3） |

#### §6.3 CUE 呈现的三层分工

任何可能进入 LLM 视野的结构化数据一律渲染成 CUE Pretty Print，绝不投喂裸 JSON（根 §6.3；机器自用的
`.Herdchestra/*.jsonl` 不在其列）。渲染器只有一份，分工固定：

| 层 | 文件 | 职责 |
|---|---|---|
| core | `core/cue.mjs` | 纯渲染器：`toCue`、`cueScalar`、`isCueIdentifier`、`isCueObject`、`DEFAULT_INDENT`、`DEFAULT_WIDTH`；不认识任何宿主 |
| compose | `compose/cue.mjs` | 三种 LLM-facing 形状：工具结果（`renderReceipt`）、注入上下文（`renderNotice`，无内容回 `null` 而不是字面 `"null"`）、拒绝（`refusalText`：一行散文 + 细节用 CUE 跟在后面） |
| host-* | 各自 `tool-schema.mjs` / `tool-registrations.mjs` / `request.mjs` | 只调用 compose 的门，不自己拼字符串，不 `JSON.stringify` |

渲染器**不发明字段**：对象没有的可枚举键不出现；core 把 `value`/`result` 挂成非枚举，所以 §3.3 的九字段回执
渲染出来仍是九字段。

### 2.1 成员表是唯一事实

`SURFACE_MEMBERS` 决定“哪个角色拿到哪些成员”，四个面：

- `js-manager`：`park`、`unpark`、`fork`、`join`、`review`、`ask`、`negotiate`、`askPreviousScope`。无读、无写、无 `call`、无 `fission`。
- `js-worker`：`cat`、`stat`、`ls`、`write`、`mkdir`、`rm`、`mv`、`where`、`whereAll`、`park`、`unpark`、`call`、`fission`、`fork`、`join`、`negotiate`、`askPreviousScope`。无 `ask`、无 `review`。
  根 §3.1 把 `fork`/`join` 同时给了 `js-worker` 与 `js-manager`：worker 的 `fork` 落在**它自己的 roster**（根 §4.1），与 `fission` 是两件事。
- `js-review`：`cat`、`stat`、`ls`、`where`、`whereAll`。只读，由 `ProgramSurface.reviewSurface()` 单独给出，不混进 `js-manager`。
- `plan`：`cat`、`write`、`park`、`unpark`、`ask`、`devops`、`review`。
  `ask` 就是 `park` + `interrupt`，所以 `plan` 必须同时拥有 `unpark`：答复由 AI 在新程序里
  `this.unpark(name, answer)` 注入原挂起点，否则这个面提出的问题永远没人能接。
- 两个豁免查询 `negotiate`、`askPreviousScope`（根 §6.2）同时挂在 `js-manager` 与 `js-worker` 上：
  它们报的是这两个面要花的额度。`js-review` 与 `plan` 没有它们——只读评估不是任何 Scope 的一拍，Plan 也没有 Scope。

角色缺的成员**不在作用域里**：调用即 `TypeError`，不是静默 no-op。三个适配器的工具描述都由这张表渲染，
所以文档与执行面不会各说一套。

**一个能力只有一种写法**（根 §3.1）。表里的成员（`cat`、`stat`、`ls`、`write`、`mkdir`、`rm`、`mv`、
`where`、`whereAll`、`call`、`ask`、`fission`、`park`、`unpark`、`devops`、`negotiate`、
`askPreviousScope`）在程序体里只写 `this.xxx`，作用域里没有同名裸函数；`fork`、`join`、`review`
三个顶层原语只写裸函数名，程序实例上没有 `this.fork`/`this.join`/`this.review`。成员表本身仍按名字列，
写法由三个执行面各自落实：`host-omp/program.mjs` 的 `bindMembers`（`isPrimitive` 排除原语）与
`compileProgram`（原语作工厂形参）、`host-cline/execution.mjs` 的 `compile`、
`host-opencode/spec/program-execution.mjs` 的 `compile`。

### 2.1a `this.ask(question)` = `this.park({ question })` + `interrupt`

这是全仓唯一的提问通道，且只属于 **`Plan` 的 `S1` 阶段**（根 §2.2、§3.1）：

- **机制。** 成员先在同一个 park 通道上挂起当前程序（`reason: "ask"`、`payload: { question }`），
  回执带不可枚举的 `interrupt: true`；宿主据此**中断本轮模型回合**，控制权交还人类用户。答复不是这次
  工具调用的返回值——用户输入后控制权回到 AI，AI 在**新程序**里调用 `await this.unpark(name, answer)`
  恢复原挂起程序并注入答复，同一任务从 `ask` 处继续，不重开任期、不制造评审。
- **不再依赖任何宿主的阻塞式弹窗或专用 operator channel。** 三个适配器都只做两件事：把问题记成该会话的
  未决提问（并尽量展示给操作员），以及结束本轮回合；用户的下一句真实输入由宿主自己的输入路径送达，
  适配器把它当作 `unpark` 的值。宿主没有展示接口时问题仍然被记录，`ask` 不回退成拒绝。
- **阶段门禁。** 成员在 `js-manager` 与 `plan` 两面上都存在，但只有 `this.ledger` 是 `plan` 且里程碑为
  `S1` 时才允许（core `askAllowed`）。`S2`、`S3`、`待执行`、`Manager` 与所有 worker 调用一律抛
  `ComposeError("ASK_FORBIDDEN_OUTSIDE_S1", ...)`，程序**不挂起**。成员保留而不删，是为了让越界调用读到
  具名拒绝，而不是一个 `TypeError`。

### 2.1b 无人值守与永不自然停止的 Nudge

面向用户的顶层 LLM（`Plan` 与 `Manager`）**除非在 `S1` 调用 `this.ask`，否则永远不可能自然停止**。
唯一的合法停止出口是**继任者独立验收上一任合格之后的停止**：

- `Manager`：进入「已完成」（`done`，即 `close` 里程碑上的空账）；
- `Plan`：在「待执行」完成向 `Manager` 的交接。判据是**判它的任期不是初任**（`tenure >= 1`）且已提交
  `review([])`：初任在「待执行」之下只能接力，而接力把里程碑原样交给下一任，所以「待执行」只可能由
  `S3` 继任者的第一次空账 `review` 晋级而来。**不要用 `successor === true` 当判据**——晋级正是那次
  清掉 `successor` 标记的评审，两者不可能同时成立。

任何半途试图自然停止的回合都被拦截并注入 **Nudge**：`compose/nudge.mjs` 的 `evaluateTurnExit` 判定
`stopAllowed: false`，`buildNudgeMessage` 列出当前 `review` 账本里未完成的事项（用台账自己的措辞，
经 CUE 呈现），并附上固定口令 `STANDING_ORDER`——「系统是无人值守的常授权模式，禁止等待用户决策或授权，
禁止半途而废」——要求模型立刻去做。空账不等于无事可做：那意味着本阶段的 `review` 验收或交接还没做。
判定是纯函数（只读 `role`、台账、`handedOff`、`askedInTurn`、`hasInFlightToolCalls`），
由 `nudge.test.mjs` 的 `fast-check` 属性证明三条定理：`ask` 当且仅当在 `Plan` 的 `S1`；未到出口的任意
顶层回合结束都 `stopAllowed: false` 且 nudge 含全部未完项与常授权口令；到达出口即 `stopAllowed: true`
且 `nudge === null`。三个适配器各自负责把这份文本送进下一次请求（`host-omp/nudge.mjs`、
`host-opencode/spec/turn-nudge.mjs`、`host-cline` 的回合边界），compose 只决定「该不该停」。

### 2.2 程序生命周期归 core，compose 只持有实例

程序状态只存在于 core 的 `ProgramState`；compose 持有的是“实例 + 端口绑定”。
两条容易写错的规则（详细理由见 `compose/AGENTS.md`）：

- `call` 用 `beginOperation()` 拿到的 **id** 排空（`drainedPromise(id)`），只等 id 更小的操作；
  绝不用 `pendingOperations - 1` 这类计数补偿——数量推不出身份。
- 回执恒为九字段；`value`/`result` 由 `withExtra` 挂成**不可枚举**键，`Object.keys` 只看到九个。

### 2.3 park 回调的形状

core 的 `TextMembers` 与成员层调用 `park(payload)` 请求挂起；`ProgramSurface` 接成
`park: (site) => this.park(site.payload, site.reason)`。写成 `this.park(site)` 会让回执的 `payload` 恒为 `null`，
`where` 歧义诊断随之丢失。

### 2.4 fission 与 relay 的边界

- fission：组合层按 `ownerView → createLane* → persistGroup → startLane* → replacePresent` 顺序调用端口；
  任一步抛错或回 `{ok:false}` 都触发整组 `abortLanes`，不登记组、不留部分组。组内不发明第二只钟。
- 总泳道数 `lane_count` 是组合层的事实：`compose/fission/group.mjs` 的 `lanesOf` 一次性把它写进每条 lane，
  宿主从 `lane.laneCount` 读它并写进 lane 起始文本。宿主不得数自己的 lane 表，也不得用 `index`/`index + 1` 顶替。
- relay：`handOverCompleted(done)` 要求 `ok===true` **且** `retired===true`、`barrier===true`、`successor` 非空。
  只凭 `ok` 不够：`{ok:true, retired:false}` 是半成品，按失败处理，台账原封不动。
  同一 `ReviewMember` 的 review 串行执行，判决与副作用保持一步。

---

## 3 三个适配器

每个适配器只做一件事：把宿主的真实接口映射成 compose 的端口。宿主没有的能力**如实拒绝**，不编造结果。
“绕行手法、未文档化行为依赖、临时补丁”一律记录在根 `HACK.md`；本文件只记结构与边界。

| 包 | 装载形态 | 入口 | 端口来源 |
|---|---|---|---|
| `src/host-opencode` | OpenCode v2 插件目录，导出 `{ id, effect }` | `index.js` → `spec/effect-entry.mjs` | `ctx.session` / `ctx.tool` / `ctx.event` / `ctx.agent`（Effect 域，经 `spec/effect-adapter.mjs` 桥成 Promise） |
| `src/host-cline` | Cline 插件，导出 `{ name, manifest, hooks, setup }` | `index.js` → `index.mjs` | `setup(api, ctx)` 的七个钩子 + `registerTool`；`fork` 走运行时解析的 `@cline/core` `createDelegatedAgent` |
| `src/host-omp` | OMP 扩展，默认导出 `herdchestra(api)` | `index.mjs`（`package.json` 的 `omp.extensions`） | `pi.registerTool`、六个钩子（`tool_call`/`tool_result`/`before_agent_start`/`context`/`turn_start`/`turn_end`）、`api.pi.createAgentSession` |

工具名清单（一个宿主注册几个面）：

- OpenCode：`js-manager`、`js-worker`、`js-review`、`plan`、`fork`、`join`；
- Cline：`js-manager`、`js-worker`、`js-plan`、`js-review`、`fork`、`join`（Plan 的面在本宿主叫 `js-plan`，
  是宿主侧的工具名；`SURFACE_MEMBERS` 里它仍是 `plan`）；
- OMP：`js-manager`、`js-worker`、`js-review`、`plan`、`fork`、`join`（`host-omp/index.mjs` 的 `TOOL_NAMES`），
  另有 `negotiate`、`askPreviousScope` 两个豁免查询工具（根 §6.2）。

共同约定：

- **一个工具名只注册一次**，角色由会话身份决定，不由模块常量决定。
- **两层分离**：收窄可见集合只改“模型看见的目录”，拦不住已被捕获的执行器；真正的拒绝在各自的
  `execute` / `execute.before` / `tool_call` / `beforeTool` 里做。
- **提示词不存副本**：三个适配器都调 `compose/prompt` 的同一份原文。
- **程序执行面**：`host-cline/execution.mjs` 与 `host-opencode/spec/program-execution.mjs` 用 `AsyncFunction`
  绑定成员；`host-omp/program.mjs` 的 `JsProgram` 基类被 OMP 与 OpenCode 共用（见 §0 的例外说明）。
  三处都按 §2.1 的单一写法绑定：成员进 `this`，`fork`/`join`/`review` 进形参。
- 适配器自有状态只落 `.Herdchestra/`（根 §1 第 3 条），文件名按宿主各有事实：
  - OpenCode：`{session}.jsonl`（追加式记录：review / scope / language / fission）、
    `hint-<sha256(session)>.jsonl`（Pair Hint 日志）、`{session}.relay.json`、
    `{session}.calls.jsonl`（程序调用的持久事实）、
    `original-<id 前 16 字符>-<sha256(id) 前 16 位>.json`（操作员原文，原子发布）；
  - OMP：`{session}.scope.jsonl`、`{session}.jsonl`（Pair Hint 日志）、`{session}.session.json`（台账 + 栅栏）、
    `{session}.request.json`（请求闸门）、`{session}.requests.jsonl`（请求形状诊断）、
    `{lineage}.relay.jsonl`（接力记录的适配器副本，与宿主转录条目互为独立视图）；
  - Cline：`{session}.{plan|manager}.json`（评审台账 + 栅栏）、`{session}.scope.jsonl`、
    `{session}.jsonl`（Pair Hint 日志）、`{session}.role.json`（启动前写定的会话身份，根 §2.1）、
    `{session}.workers.jsonl` 与 `{session}.workers.restored.json`（§3.1a 的花名册）、
    `{session}.fission.jsonl`（泳道组）。

  底稿一律在 `drafts/<name>.md`，目录由适配器在会话构造或启动前建好（`drafts.mjs` / `program-ports.mjs`）。

  宿主的历史格式一概不读、不改。

### 3.1 子代理调度：三宿主各不相同，但都绕过内建 subagent

- OpenCode：`Session.create({ parentID, agent })` + `Session.prompt({ delivery: "queue" })`（缺省是 `"steer"`）。
- Cline：`createDelegatedAgent({ kind: "subagent", ... })`，`extensions` 必须原样转发，否则子代理拿不到本插件的钩子。
- OMP：`api.pi.createAgentSession`，建好后由 lane 补 `runner.initialize`、补发 `session_start`、做 token 握手。

三处都必须**不等待首个回合**（fire-and-forget），`join()` 只等第一个归来者。

### 3.1a `fork`/`join` 花名册必须持久：跨热重载与冷启动都不丢在途 worker

**花名册是持久事实，不是进程内存。** 三个宿主的 `WorkerTable`（OpenCode、Cline）与 `LaneTable`（OMP）
都把 `fork`、`steer`、`settle`、`claim` 写进 `.Herdchestra/`，并在表建好时按记录重放。理由是实测过的
一次故障：四个 worker 被 `fork`，两个已被 `join`，随后插件因被监视文件变化而热重载——旧实现在 activation
里持有表，重载后的表是空的，此后每次 `join` 都在 **1 ms** 内回「没有可收集的结果」，而 devops 与 coder
仍在同一个服务进程里跑。`join()` 因此绝不能把「表里没人」读成「无事可做」：只有记录说已结束且已被认领，
才算没有。

| 宿主 | 表 | 持久形态 |
|---|---|---|
| OpenCode | `spec/worker-sessions.mjs` `WorkerTable` | `SessionState.recordWorker` 把 `worker-fork` / settle / steer / claim / release 追加进会话日志（`stateFile`）；`workerRoster` 按序重放成表，`activation.mjs` 用 `restored` 建表 |
| Cline | `workers.mjs` `WorkerTable` | 追加式日志 `.Herdchestra/<session>.workers.jsonl` 记每次变化，`.Herdchestra/<session>.workers.restored.json` 存上一轮结束时的快照（`save()` / `restoredRoster()`）；`release()` 随收尾回执一并保存 |
| OMP | `lane.mjs` `LaneTable` | 每条 lane 的归属、开始、结算与认领都随会话状态落盘 |

证明是状态机而不是例子：OpenCode 的 `worker-roster.test.mjs` 用 `fast-check` 生成 `fork` / `steer` /
`join` / 宿主完成事件 / 插件重载的任意交错，证明两条不变量——**进度**（任何 worker 仍在跑、或持有未被
`join` 认领的结果时，`join` 不得回答「没有可收集的」）与**记账**（每个被 `fork` 的 worker 恰好被认领一次，
且日志重建出的花名册与宿主对「哪些会话还活着」的答案一致）。另有一条单独的属性：重建出的花名册与表
自己写下的日志逐项相同。**读这些数字的纪律同样适用**：一次 `node --test` 只描述它跑的那一刻。

### 3.1b Cline 的运行时自动桥接（`host-modules.mjs`）

Cline Desktop 把插件装进 `worker_thread` 沙箱，**从不向插件显式注入 `createDelegatedAgent`**：worker 的
`argv[1]` 是沙箱引导脚本，`cwd` 是会话工作区，插件文件的 `module.paths` 只指向插件自己。裸
`createRequire(import.meta.url)` 因此看不到 SDK，早期实现里每一次真实 `fork` 都回 `HostCapabilityMissing`。

`src/host-cline/host-modules.mjs` 的阶梯按「每一步能证明什么」排序，全部是宿主的真实痕迹，不是猜测：

1. `@cline/core` / `@cline/sdk` 裸说明符——进程内宿主（测试、从源码跑的 `../cline`）直接解析；
2. `CLINE_CORE_MODULE`——嵌入方显式给出的路径或说明符；
3. **宿主自己的模块树**：`process.argv[1]` / `process.execPath` / `process.cwd()` 的祖先目录、
   插件自身祖先、以及 Cline Desktop 的已知安装布局（Linux 发行包
   `/usr/lib/Cline/bin/desktop-backend`、macOS 应用包、Windows 安装目录，以及从源码跑的 `../cline`）。
   每个候选都同时试 `<base>/node_modules/@cline/core` 与 `<base>/plugin-runtime/node_modules/@cline/core`
   ——后者正是真机上的位置
   （`/usr/lib/Cline/bin/desktop-backend/plugin-runtime/node_modules/@cline/core`）。

候选解析成功后按进程缓存（SDK 在宿主运行期间不会移动，第二个答案会让适配器的两部分互相矛盾），
`hostModuleSource()` 报告**是哪一级答的**，回执因此能解释自己而不是只说「缺能力」。

### 3.2 接力（relay）：三个宿主都写成一笔，能力不足时如实拒绝

- OpenCode：宿主没有“一笔提交”原语，适配器自持记录并**强制执行**（`spec/relay-record.mjs`、`relay-host.mjs`）：
  惰性继任者 → 原子写记录 → 用确定性 `briefId` 投递 → `sent` → `interrupt`；两道闸门（`execute.before`、
  `context` 钩子）读记录。可重放依赖宿主 `admission.reconcile` 的持久表。
- OMP: relay succeeds only through `SessionManager.appendEntriesAtomically` — retirement, the context barrier and the successor start are three fields of ONE `custom` entry (`herdchestra.relay`) published by a single rename; a failed publish leaves no record, and a cold start rebuilds the fence and the successor tenure from that entry.
  宿主不提供该原语时（`NO_ATOMIC_RECORD`）本次 review 以 `RELAY_UNAVAILABLE` 失败，台账不动。
- Cline：`TenureState.handOver` 一次写入退休、栅栏、继任三件事；文件没落盘就是三件事都没发生。

判断“接力是否成立”的唯一依据是 `handOverCompleted`，不是适配器自己的说法。

#### OMP 的双视图日志（`relay-record.mjs`）

OMP 上同一个接力事实有两份**各自独立、各自原子**的持久视图，这是适配器刻意的冗余，不是重复实现：

| 视图 | 类型标记 | 写入方式 | 何时读它 |
|---|---|---|---|
| 宿主 transcript 条目 | `herdchestra.relay` | `SessionManager.appendEntriesAtomically`（宿主整体 temp-write + rename） | 活回合内、以及冷启动读 transcript 时 |
| 适配器自有日志 | `herdchestra.relay.ledger`（`.Herdchestra/<血缘 id>.relay.jsonl`） | `files.mjs` 的 `writeJson`（temp + fsync + rename），绝不裸 append | transcript 已封口、读不到时 |

两条铁律：

1. **载荷逐字节相同**，因此两者可比较、任一份都能重建栅栏与继任 tenure。
2. **冲突时以 transcript 为准**（那是宿主发布的那份）；transcript 没有（`dispose()` → `seal()` 早于终结
   `agent_end`，边界处读不到条目）时才由适配器日志作答。

另有 `herdchestra.relay.pending`：它是**意图**，不是 tenure。收据返回前写下，崩溃后重启能找到并补完接力；
但它本身不使退休、栅栏或继任任何一项为真——读者**不得**从 pending 推导栅栏、tenure 或继任者。

---

## 4 测试

- 全部用 `node:test` + `fast-check`（属性测试优先，根 §8.2 第 2 条）。测试与实现同目录，文件名 `*.test.mjs`。
- **四份跨切面的属性证明**，各自守住一条全仓不变量；它们比用例条数重要，改代码时先看它们是否仍成立：

  | 文件 | 证明什么 |
  |---|---|
  | `src/compose/prefix-state-machine.test.mjs` | 对三个宿主的投影接口跑同一台状态机：任意合法回合序列里**不出现任何前缀原因的拒绝**，且**任何没有投影在背后的语义改写都被 `PREFIX_DRIFT` 拒绝**。生成器包含侧通道请求、压缩、重启、宿主簿记字段变更（键名随机生成，不是一份名单） |
  | `src/compose/nudge.test.mjs` | 无人值守出口的三条定理（§2.1b）：`ask` 当且仅当 `Plan` 的 `S1`；未到出口必被 nudge 且列出全部未完项与常授权口令；到达出口必 `stopAllowed: true` 且无 nudge |
  | `src/core/pair-hint/canonical.test.mjs` | 语义身份是正向白名单：任意宿主字段的新增、改写与键序变化都不改变 `canonicalKey`；真正的内容变化一定改变它 |
  | `src/host-opencode/spec/worker-roster.test.mjs` | 花名册跨插件重载的进度与记账（§3.1a），含任意交错的 `fork`/`steer`/`join`/重载 |

- 用例数随并行编辑变动，本文档**不复制瞬时数字**：需要条数时跑一次 `node --test`，引用时必须连同
  “测的哪棵树、什么时候”一起引用。静态 `test(` 计数永远比实际少，因为部分用例在 `for` 循环里生成。
- **读这些数字的纪律**：本仓库长期多写者并行编辑，一次 `node --test` 只描述它跑的那一刻。引用数字必须
  连同“测的哪棵树”一起引用；编辑中的红数不是回归，先看源文件 mtime 再下结论。
- 根 `package.json` 的脚本按层切分：`test:core`、`test:compose`、`test:host-cline`、`test:host-opencode`、
  `test:host-omp`；`npm test` 跑全部。

### 4.1 快回路与真机 smoke

单元测试证明的是形状，真机 smoke 证明的是三个宿主**装得上、跑得动、写得下记录**。两条回路各答一个问题，
不要用一条回路代替另一条。

| 命令 | 回答什么 | 用例数 | 参考耗时（16 核，2026-11-01 审计后快照） |
|---|---|---|---|
| `npm test` | 全量属性测试，每个用例按自己声明的次数跑 | 1127 | 约 3.7–3.9 秒 |
| `npm run test:quick` | 同一组用例，属性测试每例至多 20 次（`HC_FC_RUNS=20`），并行度 `$(nproc)` | 1127 | 约 3.4–3.5 秒 |
| `npm run smoke:omp` | 真机 OMP：3 个回合，中间一回合调 `js-manager` | —（3 个真机回合） | 约 5 分钟（324 秒；区间 2–8 分钟） |
| `npm run smoke:opencode` | 真机 OpenCode v2（`opencode run --standalone`，隔离 `OPENCODE_CONFIG_DIR`） | —（3 个真机回合） | 约 12 秒 |
| `npm run smoke:cline` | 真机 Cline（`@cline/core` SDK，隔离 `CLINE_DIR`） | —（3 个真机回合 + 1 个 park 回合） | 约 15 秒 |
| `npm run smoke` | 上面三个并行，逐宿主一行 | — | 约 2–8 分钟（取决于最慢的宿主） |

- **这是一份 2026-11-01 的快照**（1.0.0 审计修复后，同一棵树、16 核），不是长期契约：条数与耗时都随并行
  编辑和网络变动，引用时连同“哪棵树、哪台机器、什么时候”一起引用。
- 分层实测（同一次快照）：`core` 264 例 1.2 秒、`compose` 176 例 1.5 秒、
  `host-omp` 271 例 2.4 秒、`host-opencode` 219 例 1.8 秒、`host-cline` 197 例 2.5 秒；合计 1127 例。
  `host-cline` 的 devops 连接用例注入工厂，不启动真机运行时；此前它会让该层多等一次约 46 秒的网络超时。
- 时间只作量级参考：本仓库长期多写者并行编辑，引用时连同“哪台机器、什么时候”一起引用。
- 每个 smoke 建一个 `/tmp/hc-smoke-<host>-<随机>/` 根，跑完即删；`--keep` 保留，`--verbose` 打出宿主输出。
  Cline 另建 `/tmp/hc-smoke-cline-capture-<随机>/` 读回 provider 请求，跑完同样删除；`--capture <dir>` 指定
  目录时归操作员所有，脚本不删。
  凭据只从操作员自己的配置**读入**交给子进程，从不打印、不写入仓库。
- 三个 smoke 共用同一组检查，逐行给出，任何一条失败都以非零退出码结束，**失败即结果**：`宿主退出 0`、`无
  PREFIX_DRIFT / CONTEXT_PROJECTION_FAILED / REQUEST_GATE_UNWRITABLE`（日志、`.Herdchestra/` 的 `.jsonl`
  与 `.json` 记录都查）、`js-* 工具输出是 CUE`（能 `JSON.parse` 的一律判失败；OMP 的 `{\n  ok: true\n}` 这种
  以 `{` 开头却不可解析的形态是合格 CUE）、`js-* 输出不含程序拒绝码`
  （`SCOPE_REFUSED` / `INVALID_ARGUMENT` / `SYNTAX_ERROR` / `PROGRAM_FAILED`）、
  `.Herdchestra/<session>.*` 记录存在。
- Cline 另有两组实测：provider capture 逐请求检查角色规则与 Pair Hint 对（每个会话的第一笔冷启动请求允许
  没有，之后每一笔必须各出现一次，且 Pair Hint 只出现一次）；`js-plan` 的 park 检查要求 `ask` 之后运行结束、
  不再有模型步或 nudge。
- CUE 判定只有一份，在 `scripts/smoke/lib.mjs` 的 `isCue`，三个脚本共用；不要在各脚本里另写一份。
- 改了宿主适配器的装载路径、工具注册或投影时先跑对应 smoke，再跑 `test:quick`；全量 `npm test` 留给合入前。
- smoke 里的模型默认 `lazy/task`，用 `HC_SMOKE_MODEL`、`HC_SMOKE_OMP_MODEL`、`HC_SMOKE_OPENCODE_MODEL`、
  `HC_SMOKE_CLINE_MODEL` 覆盖；Cline 另认 `HC_SMOKE_CLINE_PROVIDER`。
- `src/host-opencode/live/tool-boundary.live.ts` 是**在宿主仓库里**跑的边界测试，不叫 `*.test.mjs`，
  不进依赖无关的适配器套件；运行方式写在文件头。
- 测试只断言消费者可见的行为，不扫描源码结构；不得为了“跑通”去改断言——与实测不符时以实际输出为准并回报。

---

## 5 文档分工

| 文件 | 内容 |
|---|---|
| 根 `AGENTS.md` | 唯一语义来源（产品行为、阶段、角色、工具面、投影） |
| `src/AGENTS.md`（本文件） | 分层、装配关系、跨包边界 |
| `src/{core,compose,host-*}/AGENTS.md` | 包级规格 |
| `src/{core/command,core/fs,core/park,core/review,core/where,core/scope,core/pair-hint}/AGENTS.md` | 单模块 / 子包规格 |
| `src/host-opencode/spec/AGENTS.md` | OpenCode 适配器规格；`spec/gaps.mjs` 是缺口唯一清单 |
| `src/compose/HOST-PORTS.md` | 宿主端口接口单 |
| 根 `HACK.md` | 适配绕行、未文档化依赖、临时补丁 |
| 根 `LICENSE` | 许可证状态的权威表述：开源项目，具体许可证尚未注明（1.0.0） |

根规格更新后，以上文件与源码一并追赶（根 §8.1 第 4 条），无需请示。

根目录**不设** `README.md` 与 `LEMMA.md`（根 §8.1 首段）。发行说明写在根 `package.json` 的 `description`
与本文件；1.0.0 的版本、许可证指针、发布形态见 §0.1。四个清单（根 + 三个子包）的 `version` 必须同步为
`1.0.0`，`license` 必须是 `SEE LICENSE IN …` 形式且目标文件存在——这两条是可机检的，改动后跑一次
`node -e` 逐项核对，不要只改一个。
