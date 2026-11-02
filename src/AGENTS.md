# `src/` 全局架构师笔记

> 本文件承担传统项目里 `ARCHITECTURE.md` 的职责（根 `AGENTS.md` §8.1 第 2 条）。
> 根 `AGENTS.md` 是唯一语义来源；本文件只描述**代码怎么分层、怎么装配、边界在哪**。
> 两者冲突时以根规格为准，并立即修正本文件与代码。适配宿主的绕行手法不写在这里，写在根 `HACK.md`。
>
> 核对基准：`src/` 当前树与根规格 §1–§8。文件数随并行编辑变动，需要时跑
> `find src -type f | wc -l`，不把某一次的数字写成本文的事实。
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

### 1.1 Pair Hint：`PREFIX_DRIFT` 的根治、合法边界与摘要链

`core/pair-hint/` 的完整规格在 `src/core/pair-hint/AGENTS.md`；这里只记消费者必须知道的几条事实。

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

**OMP 的并发旁路请求只注入前缀，绝不推进链。** OMP 自己的 `<title-request>` 与
`Ephemeral side-channel turn` 走同一个 `context` 钩子，却**不是**这条对话的一步（宿主不把它们写进转录）。
`core/pair-hint/pair-hint.mjs` 的 `REQUEST_KINDS.EPHEMERAL` 让它们按主链的位置放置合成对（命中 provider 的
前缀缓存），然后**什么都不记录**：`#chain`、`#presentedEpoch`、`#after` 与待写的 re-base 都不动，所以主线
下一份请求仍与主链比较。适配器负责判定「这是什么请求」（OMP 的标记是 Symbol，`structuredClone` 会丢掉它，
只能按宿主写下的两种形状判定），本包负责「这对链意味着什么」。隔离一旦失效，症状是侧通道的截断副本被当成
链的一步，随后每个真实请求都在下标 1 判 `PREFIX_DRIFT`。

**拒绝一律带可诊断的 code，不静默修补**（`errors.mjs` 的 `PAIR_HINT_CODES`）：

| code | 何时 |
|---|---|
| `PREFIX_DRIFT` | 同一 epoch 内已呈现消息的字节要变；新请求不以旧请求为前缀；`after` 计数超出请求长度；同一请求里出现两对 |
| `EPOCH_CONFLICT` | epoch 已推进，调用方仍按旧 epoch 提交 |
| `RESOURCE_PAIR_INCOMPLETE` / `RESOURCE_MISSING` / `RESOURCE_UNKNOWN` | 资源对缺书、整份缺失、或指向未知资源 |
| `INVALID_LANGUAGE` / `INVALID_ARGUMENT` | 语言未绑定或参数不合法 |
| `FACT_CORRUPT` | 日志里的 fact 无法折叠 |

`PREFIX_DRIFT` 的 `details.index` 是第一个不同的消息下标——诊断先看它。**没有豁免模式**：重放也是发出去的
请求，宿主改写了已呈现消息同样要拒。锚的有效形态必须是 `base`：`Chain` 里可以带着本 epoch 的对
（`pairIndex = base.length`），但前缀比较取 `pairFreeDigests()`，所以被比较的锚就是 `base`；锚错位会让
继任者第二次请求必然漂移。

**链是摘要，不是请求副本（`chain.mjs`、`facts.mjs`）。** `PairHint#chain` 返回一个不可变的 `Chain`：
`length`、`digests`（每条消息 `canonicalKey` 的 SHA-256，数组冻结）、`root`（带长度前像的摘要根）、
`pairIndex`（本 epoch 合成对的下标），以及 `digestAt(index)`、`pairFreeDigests()`、`withoutPair()`、
`equals(other)`、`toRecord()`。`presented` 记录存的就是这份状态：`length` + 每消息摘要 + 根 + 对的下标 +
事实（`{ epoch, digest, handles, after, inserted }`），可选 `rebased`；**从不存完整消息**。前缀规则比较的是
去掉对之后的两组摘要，`details.index` 仍是第一个不同的下标，所以「链拒绝」与「拒绝记录里的下标」共用同一套
坐标系。`PairHint#presented` 已不存在：链就是唯一锚点。

**提示全文一个 epoch/digest 只写一次。** 第一条引用它的 `presented` 之前，`project` 先写一条
`presentation` 记录（`{ epoch, digest, text, entries }`）；此后同一 epoch 的每条 `presented` 只引用它的
`digest`。资源文件在 epoch 中途被改写不会改变已呈现的字节，冷启也只解析已存文本，不重读资源目录。

**旧日志照常折叠。** 旧形状的 `presented` 带 `messages`；折叠时只对**最后一条带链的记录**计算同一组摘要
（`foldRecords` 的 `computeChain`），更早的副本已被后一条取代，读者永远到不了，所以旧日志的冷启不是
O(n²)。压缩把旧副本迁移成摘要、把每条事实里的 `text`/`entries` 提升为 `presentation` 记录，折叠结果完全
不变。`length` 非整数、摘要数组与 `length` 不符、根与摘要不符，仍是 `FACT_CORRUPT`。

**4 MiB 只是压缩阈值，不再是每请求的写入量。** `JournalStore.COMPACT_BYTES = 4 * 1024 * 1024`：超过才
原子重写（写 `.compact` 再 `rename`），且发生在 append 链内部，所以本 store 的 append 不会与它交错。
旧实现每次请求重写整份副本——实测 33 次请求 / 67 条消息写出 34 MB，仓库日志曾达 133 MB；现在每次请求只
追加一行摘要。压缩**不**防另一个进程同时 append——那是日志本来就有的限制（一个会话一个驱动）。

**冷启只靠日志。** `PairHint.open` 从日志得到 `Chain` 与冻结文本，于是重启后的第一份请求仍受同一 epoch 的
前缀约束，且不需要任何存下来的副本、也不读宿主历史。`refused` 记录是诊断：`index` + 两侧形状（链侧只有
`{ digest }`，请求侧是 `{ role, parts, calls, results, length, digest }`），每侧最多 64 条；不记文本、
入参、出参、系统提示或凭据。

---

## 2 `src/compose`：组合层

它自己不读文件、不跑命令、不建会话。每一次真实动作都经 core 或经一个宿主端口。

| 目录 / 文件 | 职责 |
|---|---|
| `prompt/files.mjs` | `common`/`manager`/`worker` 三段连续字符串（唯一原文） |
| `prompt/projection.mjs` | 只有两种拼接：`COMMON+MANAGER`、`COMMON+WORKER`；`systemPromptFor("plan")` 抛 `PLAN_OUTSIDE_PROJECTION` |
| `prompt/plan.mjs` | `PLAN_PROMPT`：Plan 自己的角色段，与上面两种组合互斥 |
| `prompt/director.mjs` | `<vibe-mode>` 导演消息：独立角色段（根 §5.4 全文的逐字节抄本），不进系统提示组合 |
| `program/surface.mjs` | `SURFACE_MEMBERS`（唯一成员表）、`SURFACE_PRIMITIVES`（`{ plan: ["devops"] }`，裸顶层原语）、`ProgramSurface`、`ROLES` |
| `program/members.mjs` | `coreMembers`（文件、文本、`call`）与 `reviewMembers`（`js-review` 的只读成员）；`park`/`unpark`/`review`/`ask`/`fork`/`join`/`fission`/`devops` 的端口转发在 `surface.mjs` 的 `handler` |
| `program/role-brief.mjs` | `roleOfVerboseIntent`：从 `verboseIntent` 的显式声明解析 worker 身份（`coder`/`devops`）；`forkRequest` 校验 `fork(name, verboseIntent)` 的两个参数，无声明或声明两者一律拒绝 |
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
- `plan`：`cat`、`write`、`unpark`、`ask`、`review`；另有裸顶层原语 `devops(verboseIntent)`
  （`SURFACE_PRIMITIVES = { plan: ["devops"] }`，不在成员表里，也没有 `this.devops`）。Plan 没有
  `this.park`：`ask` 自己走 park 通道（`park` + `interrupt`），所以 `plan` 必须拥有 `unpark`——答复由 AI
  在新程序里 `this.unpark(name, answer)` 注入原挂起点，否则这个面提出的问题永远没人能接。
- 两个豁免查询 `negotiate`、`askPreviousScope`（根 §6.2）同时挂在 `js-manager` 与 `js-worker` 上：
  它们报的是这两个面要花的额度。`js-review` 与 `plan` 没有它们——只读评估不是任何 Scope 的一拍，Plan 也没有 Scope。

角色缺的成员**不在作用域里**：调用即 `TypeError`，不是静默 no-op。三个适配器的工具描述都由这张表渲染，
所以文档与执行面不会各说一套。

**一个能力只有一种写法**（根 §3.1）。表里的成员（`cat`、`stat`、`ls`、`write`、`mkdir`、`rm`、`mv`、
`where`、`whereAll`、`call`、`ask`、`fission`、`park`、`unpark`、`negotiate`、
`askPreviousScope`）在程序体里只写 `this.xxx`，作用域里没有同名裸函数；顶层原语 `fork`、`join`、`review`
与 Plan 的 `devops` 只写裸函数名，程序实例上没有 `this.fork`/`this.join`/`this.review`/`this.devops`。
成员表本身仍按名字列，写法由三个执行面各自落实：`host-omp/program.mjs` 的 `bindMembers`（`isPrimitive`
排除原语）与 `compileProgram`（原语作工厂形参，`devops` 只给 `plan`）、`host-cline/execution.mjs` 的
`compile`（`PRIMITIVES` 含 `devops`）、`host-opencode/spec/program-execution.mjs` 的 `compile`
（`spec/primitives.mjs` 的 `HOST_PRIMITIVES` 补上 `devops`）。

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
  三处都按 §2.1 的单一写法绑定：成员进 `this`，`fork`/`join`/`review` 与 Plan 的 `devops` 进形参。
- 适配器自有状态只落 `.Herdchestra/`（根 §1 第 3 条），文件名按宿主各有事实：
  - OpenCode：`{session}.jsonl`（追加式记录：review / scope / language / fission）、
    `hint-<sha256(session)>.jsonl`（Pair Hint 日志）、`{session}.relay.json`（本会话的接力记录）、
    `{successor}.relay.json`（交接时写给继任者的同一份记录副本，继任者只读这一个路径，不扫描目录）、
    `{session}.calls.jsonl`（程序调用的持久事实：`payloadDigest = sha256(stableJson(input))` + 回执，
    不存完整入参）、
    `original-<id 前 16 字符>-<sha256(id) 前 16 位>.json`（操作员原文，原子发布）；
  - OMP：`{session}.scope.jsonl`、`{session}.jsonl`（Pair Hint 日志）、`{session}.session.json`（台账 + 栅栏）、
    `{session}.request.json`（请求闸门，只在判决变化时写）、`{session}.requests.jsonl`（只记失败请求的
    形状诊断：丢掉的形状上限 64，超过 2 MiB 轮转成 `.1`）、
    `{session}.workers.jsonl`（花名册：fork / steer / settle / claim / release / restore）、
    `{lineage}.relay.jsonl`（接力记录的适配器副本，与宿主转录条目互为独立视图）；
  - Cline：`{session}.{plan|manager}.json`（评审台账 + 栅栏）、`{session}.scope.jsonl`、
    `{session}.jsonl`（Pair Hint 日志）、`{session}.role.json`（启动前写定的会话身份，根 §2.1）、
    `{session}.workers.jsonl`（§3.1a 的花名册，唯一来源）、
    `{session}.fission.jsonl`（泳道组）。

  Plan 底稿一律在工作区根目录 `plans/<name>.md`（`PLAN_DRAFT_DIR = "plans"`），目录由适配器在会话构造或
  启动前建好（`drafts.mjs` / `program-ports.mjs` / `host-facts.mjs`）；它绝不放 `.Herdchestra/`。

  宿主的历史格式一概不读、不改。

### 3.1 子代理调度：三宿主各不相同，但都绕过内建 subagent

- OpenCode：`Session.create({ parentID, agent })` + `Session.prompt({ delivery: "queue" })`（缺省是 `"steer"`）。
- Cline：`createDelegatedAgent({ kind: "subagent", ... })`，`extensions` 必须原样转发，否则子代理拿不到本插件的钩子。
- OMP：`api.pi.createAgentSession`，建好后由 lane 补 `runner.initialize`、补发 `session_start`、做 token 握手。

三处都必须**不等待首个回合**（fire-and-forget），`join()` 只等第一个归来者。

**模型归属（根 §1.1 第 6 条）：worker、fission lane 与 Plan 的 `plan-devops` 用 task 模型；`Manager` 与
Plan 顶层保留主模型 / Plan 模型。** 三个宿主的取法不同，回执都带 `modelSource`：

- OpenCode：`spec/model-inherit.mjs` 的 `taskModelOf` 要求 `HERDCHESTRA_TASK_PROVIDER_ID` 与
  `HERDCHESTRA_TASK_MODEL_ID` 两半齐全，返回 `Model.Ref`；两半都未设置才退回父模型并标
  `modelSource: "parent"`，只设一半是具名拒绝（`TASK_MODEL_INCOMPLETE`），不猜默认值。`forkWorker`、
  fission 的 `buildLane` 与 Plan 的 devops 都走它。
- Cline：`resolveTaskModel` / `resolveTaskConnection` 读同一对 `HERDCHESTRA_TASK_*`（两半齐全才算一条连接），
  runtime handle 的 `modelSource` 是 `"task"` 或 `"main"`；`Manager` 与 Plan 顶层走 `resolveModel`。
- OMP：`createAgentSession` 接受 `modelPattern: "@task"`，由宿主经 `settings.getModelRole("task")` 展开
  （即宿主的 `modelRoles.task`；`host-facts.mjs` 的 `TASK_MODEL_PATTERN`，源码位置 `sdk.ts:607`、
  `config/model-resolver.ts`、`task/agents.ts:56`）；lane 回执的 `modelSource` 是 `"task-role"`。
  解析不了就如实报告，不冒充 task 模型。

**Plan 的 devops 是 dedicated 的单例。** 三个宿主都固定只开一条 `plan-devops`：OpenCode 的
`PLAN_DEVOPS_NAME` 复用或 steer 同一个 worker；Cline 每个会话惰性建一个 runtime handle，后续调查都是
`handle.run(verboseIntent)`；OMP 的 `PLAN_DEVOPS_LANE` 是同一会话花名册里的一条 lane。绝不开第二路。

### 3.1a `fork`/`join` 花名册必须持久：跨热重载与冷启动都不丢在途 worker

**花名册是持久事实，不是进程内存。** 三个宿主的 `WorkerTable`（OpenCode、Cline）与 `LaneTable`（OMP）
都把 `fork`、`steer`、`settle`、`claim` 写进 `.Herdchestra/`，并在表建好时按记录重放。三张表都以 `Map`
索引 worker 与未领取的结果（OpenCode 按 session id，Cline 与 OMP 按 turn id），认领是 O(1)，重放不扫描
队列。理由是实测过的一次故障：四个 worker 被 `fork`，两个已被 `join`，随后插件因被监视文件变化而热重载
——旧实现在 activation 里持有表，重载后的表是空的，此后每次 `join` 都在 **1 ms** 内回「没有可收集的结果」，
而 devops 与 coder
仍在同一个服务进程里跑。`join()` 因此绝不能把「表里没人」读成「无事可做」：只有记录说已结束且已被认领，
才算没有。

| 宿主 | 表 | 持久形态 |
|---|---|---|
| OpenCode | `spec/worker-sessions.mjs` `WorkerTable` | `SessionState.recordWorker` 把 `worker-fork` / settle / steer / claim / release 追加进会话日志（`stateFile`）；`SessionState.workerRoster` 交出按序折出的记录，`activation.mjs` 以 `restored` 建表 |
| Cline | `workers.mjs` `WorkerTable` | `.Herdchestra/<session>.workers.jsonl` 是**唯一来源**：每次 `fork` / steer / settle / claim / release / cancel 追加一行，建表时 `restoredRoster()` 按序重放。快照文件已删除——两个能互相矛盾的答案不如一个；`claim` 记录让已认领的答案在重放时不再出现 |
| OMP | `lane.mjs` `LaneTable` + `worker-roster.mjs` | `.Herdchestra/<session>.workers.jsonl` 记 `worker-fork` / steer / settle / claim / release / restore；重放按名字与 turn id 折叠，已认领的答案文本在 `claim` 时从日志剥离（小日志每次认领都重写，大日志按 64 条 / 64 KiB 阈值摊还） |

证明是状态机而不是例子：OpenCode、Cline 与 OMP 各有一份 `worker-roster.test.mjs`。它们用 `fast-check`
生成 `fork` / `steer` / `join` / 宿主完成事件 / 插件重载的任意交错，证明两条不变量——**进度**（任何
worker 仍在跑、或持有未被 `join` 认领的结果时，`join` 不得回答「没有可收集的」）与**记账**（每个被
`fork` 的 worker 恰好被认领一次，且日志重建出的花名册与表自己写下的日志逐项相同）。OpenCode 另有一条
属性：重建出的花名册与宿主对「哪些会话还活着」的答案一致。**读这些数字的纪律同样适用**：一次
`node --test` 只描述它跑的那一刻。

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

### 3.1c 操作员 Esc：递归取消到每一名后代

根 §1.1 第 2 条要求取消递归传播。三个宿主都不会替适配器级联（OpenCode 只通知被中断的会话，Cline 与 OMP
同理），所以每个适配器遍历自己已经持有的花名册，逐个中断，**从不 dispose**：dispose 会抹掉证据，也会让
`join` 失去可报告的终局。三类程序化中断不传播：`this.ask` 的 park、fission 的 present 替换、接力的退休。

- OpenCode：`spec/cancel.mjs` 从 `activation.workers`、`activation.lanes` 以及继任者接力记录里的花名册
  做广度优先遍历，对每个会话调 `activation.sessions.interrupt({ sessionID })`。被取消的标记写进
  `activation.cancelled` 与 worker table：后续 `fork`/steer 拒绝，`join` 如实报告 cancelled worker。
- Cline：`guard.mjs` 的 `operatorInterruptOf` 从 run status 或 `stopReason: "aborted"` 的 assistant 消息
  识别操作员中断；`this.ask` 的 park（`parkStopOwed` / `parkStopped`）先被排除。`SessionState.cancelDescendants`
  调 `WorkerTable.cancelAll` 与 Plan devops 的 `cancel`：live runtime 只 `abort`、不 dispose，未领取的完成
  仍可 `join`，欠的回合以 `finishReason: "cancelled"` 结算，取消标记随日志跨重载保留；下一次 Plan devops
  调用以 `PLAN_DEVOPS_DEAD` 拒绝，而不是悄悄再开一个。
- OMP：`index.mjs` 的 `cancelSubtree` 沿 adapters 与 lanes 广度优先遍历，对每个子会话调
  `AgentSession.abort({ reason })`（不是 `dispose`）。遍历幂等；每个经过的花名册都被标记 cancelled，后续
  `fork`/steer 具名拒绝，`join` 在取消后仍报告未领取的结果。

### 3.2 接力（relay）：三个宿主都写成一笔，能力不足时如实拒绝

- OpenCode：宿主没有“一笔提交”原语，适配器自持记录并**强制执行**（`spec/relay-record.mjs`、`relay-host.mjs`）：
  惰性继任者 → 原子写记录 → 用确定性 `briefId` 投递 → `sent` → `interrupt`；两道闸门（`execute.before`、
  `context` 钩子）读记录。可重放依赖宿主 `admission.reconcile` 的持久表。交接在同一步里把同一份记录写成
  继任者自己的 `{successor}.relay.json`，所以继任者只读一个路径，**不再 `readdirSync` 整个 `.Herdchestra/`**；
  文件缺失即无记录，损坏即对所有人关门（fail closed），没有回退扫描。Plan 的交接去重同样只读一个确定路径：
  `planHandoffOf` 读 `.Herdchestra/handoff-<sha256(handoffId)[0:32]>.relay.json`（`relay-record.mjs` 的
  `handoffFile`），该记录先写，再写 Manager 自己的 `{manager}.relay.json`。
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
- **五份跨切面的属性证明**，各自守住一条全仓不变量；它们比用例条数重要，改代码时先看它们是否仍成立：

  | 文件 | 证明什么 |
  |---|---|
  | `src/compose/prefix-state-machine.test.mjs` | 对三个宿主的投影接口跑同一台状态机：任意合法回合序列里**不出现任何前缀原因的拒绝**，且**任何没有投影在背后的语义改写都被 `PREFIX_DRIFT` 拒绝**。生成器包含侧通道请求、压缩、重启、宿主簿记字段变更（键名随机生成，不是一份名单） |
  | `src/compose/nudge.test.mjs` | 无人值守出口的三条定理（§2.1b）：`ask` 当且仅当 `Plan` 的 `S1`；未到出口必被 nudge 且列出全部未完项与常授权口令；到达出口必 `stopAllowed: true` 且无 nudge |
  | `src/core/pair-hint/canonical.test.mjs` | 语义身份是正向白名单：任意宿主字段的新增、改写与键序变化都不改变 `canonicalKey`；真正的内容变化一定改变它 |
  | `src/core/pair-hint/pair-hint.test.mjs` | 摘要链（§1.1）：任意请求序列留下每消息摘要 + 根 + 对的下标，链的无对视图等于同一序列的逐条摘要；任意改写都在第一个不同的下标报 `PREFIX_DRIFT`；冷启只凭日志折出同一条链与同一份冻结文本，并能继续校验前缀 |
  | `src/host-opencode/spec/worker-roster.test.mjs`、`src/host-cline/worker-roster.test.mjs`、`src/host-omp/worker-roster.test.mjs` | 花名册跨插件重载的进度与记账（§3.1a），含任意交错的 `fork`/`steer`/`join`/重载 |

- 用例数随并行编辑变动，本文档**不复制瞬时数字**：需要条数时跑一次 `node --test`，引用时必须连同
  “测的哪棵树、什么时候”一起引用。静态 `test(` 计数永远比实际少，因为部分用例在 `for` 循环里生成。
- **读这些数字的纪律**：本仓库长期多写者并行编辑，一次 `node --test` 只描述它跑的那一刻。引用数字必须
  连同“测的哪棵树”一起引用；编辑中的红数不是回归，先看源文件 mtime 再下结论。
- 根 `package.json` 的脚本按层切分：`test:core`、`test:compose`、`test:host-cline`、`test:host-opencode`、
  `test:host-omp`；`npm test` 跑全部。

### 4.1 快回路与真机 smoke

单元测试证明的是形状，真机 smoke 证明的是三个宿主**装得上、跑得动、写得下记录**。两条回路各答一个问题，
不要用一条回路代替另一条。

| 命令 | 回答什么 | 参考耗时（量级，随机器与并行编辑变动） |
|---|---|---|
| `npm test` | 全量属性测试，每个用例按自己声明的次数跑 | 数秒 |
| `npm run test:quick` | 同一组用例，属性测试每例至多 20 次（`HC_FC_RUNS=20`），并行度 `$(nproc)` | 数秒 |
| `npm run smoke:omp` | 真机 OMP：3 个回合，中间一回合调 `js-manager` | 约 2–8 分钟 |
| `npm run smoke:opencode` | 真机 OpenCode v2（`opencode run --standalone`，隔离 `OPENCODE_CONFIG_DIR`） | 约十几秒 |
| `npm run smoke:cline` | 真机 Cline（`@cline/core` SDK，隔离 `CLINE_DIR`） | 约十几秒 |
| `npm run smoke` | 上面三个并行，逐宿主一行 | 约 2–8 分钟（取决于最慢的宿主） |

- **用例条数不写进本文**：条数随并行编辑变动，引用时必须连同“哪棵树、哪台机器、什么时候”一起引用。
  分层跑法：`npm run test:core`、`test:compose`、`test:host-cline`、`test:host-opencode`、`test:host-omp`。
  `host-cline` 的 devops 连接用例注入工厂，不启动真机运行时。
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
