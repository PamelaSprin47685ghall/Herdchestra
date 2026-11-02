# `core/pair-hint` 包级规格

> 本文件是 `src/core/pair-hint/` 的规格说明书，体例参照根 `AGENTS.md`，由 AI 维护。
> 语义来源是根规格 §6.1（Pair Hint：语言解析、注入形态与前缀稳定）。
> 本包属于核心支持库：只依赖 Node 内置模块与 `src/core/{util,errors}.mjs`，不依赖任何宿主、任何组合层。

---

## 1 边界

**做什么。** 用注入的提示让模型始终用用户的语言思考与输出，并且这个呈现可以在冷启后逐字重建。

- 绑定会话语言（§6.1 解析阶梯，§6.1 步骤 1）。
- 按语言取成对资源、渲染一段提示（§6.1，§6.1 步骤 2）。
- 把提示作为一对合成的工具调用与结果（空工具名）放进请求副本（§6.1，§6.1 步骤 3）。
- 守住同一 epoch 内的字节前缀（§6.1，§6.1 步骤 4）。
- 列出本会话尚未结清的 park handles（§6.1，§6.1 步骤 5）。
- 把上面每一步的事实写进 `.Herdchestra/{sessionId}.jsonl`，冷启后重建同一呈现（§6.1，§6.1 步骤 6）。

**不做什么。**

- 不检测模型用哪种语言思考，也不因此拦截请求（§6.1）。语言是提示词约束。
- 不审查提示内容；防呆只有形式检查（§6.1）。§6.1 的「至少包含」是资源的编写义务，不是代码的检查项。
- 不改宿主持久历史、不改用户原文、不另开续传通道（§6.1）。所有改写只发生在请求副本上。
- 不动 retention、不参与 Scope 记账（§6.2）。两者只在「都作用于请求副本」这一点上相遇。
- 不预测 context 溢出、不代为压缩（§6.1）：窗口大小只作为事实写进资源。
- 不替模型恢复 park（§6.1）：只列出 handles 要求表态。

## 2 组成（mise en place）

每个文件一件完整的事，彼此不共享可变状态；组合只在 `pair-hint.mjs` 里显式发生。

| 文件 | 责任 | 对外导出 |
|---|---|---|
| `errors.mjs` | 唯一失败类型与失败码 | `PairHintError`、`PAIR_HINT_CODES`、`refuse`、`isPairHintError` |
| `language.mjs` | 语言枚举、解析阶梯、会话绑定、fork 继承 | `LANGUAGES`、`RESOURCE_NAMES`、`resolveLanguage`、`LanguageBinding`、`bindSession` |
| `resources.mjs` | 成对资源载入，缺一本即失败 | `RESOURCE_IDS`、`ResourcePair`、`ResourceLibrary`、`loadPair`、`resourcePath` |
| `presentation.mjs` | 呈现事实、渲染、解析、合成消息对 | `PresentationFact`、`renderPresentation`、`renderHandles`、`parseHint`、`factFor`、`syntheticMessages`、`digestOf`、`findInjection`、`findAllInjections`、`callIdParts` |
| `injection.mjs` | 请求副本上的放置、重放、前缀检查 | `PairHintInjector`、`INJECTION_MODES` |
| `canonical.mjs` | 前缀规则比较用的**语义身份**：正向白名单 + `stableJson` | `canonicalMessage`、`canonicalMessages`、`canonicalKey`、`CANONICAL_PART` |
| `chain.mjs` | 前缀链的最小状态：每消息摘要、根、对的下标 | `Chain`（`digestAt`、`pairFreeDigests`、`withoutPair`、`equals`、`toRecord`）、`messageDigest`、`rootOf`、`firstDivergence`、`startsWith`、`digestSide` |
| `facts.mjs` | 持久事实的折叠与日志（含旧形状迁移） | `PairHintState`、`JournalStore`、`foldRecords`、`RECORD_KINDS`、`journalPath` |
| `pair-hint.mjs` | 把以上组合成一个宿主入口 | `PairHint`、`MemoryJournal` |
| `resources/<id>/{en,zh-CN}.md` | 成对资源本体 | — |

## 3 语言规则

1. **两种语言。** `English` 与 `SimplifiedChinese`，字面量就是这两个字符串；`RESOURCE_NAMES` 给出 `en.md` / `zh-CN.md`。任何其他值都是 `INVALID_LANGUAGE`（）。
2. **解析阶梯。** `resolveLanguage({ explicit, host, locale })`：
   - `explicit`（显式环境变量）存在但不可用 → 抛 `INVALID_LANGUAGE`，**不**落到下一级。显式配置写错不能悄悄变成英文（§6.1 步骤 7）。
   - `host`（宿主配置偏好）是提示：不可用值被忽略。
   - `locale`（IDE / 系统 locale）按 BCP 47 语言子标签匹配；`en*` → English，`zh` 且脚本/地区不是繁体 → SimplifiedChinese；`zh-TW`、`zh-HK`、`zh-Hant` 不是已知值，整段忽略。列表（`zh-CN,en;q=0.8`）取第一个已知段。
   - 全部落空 → `English`，`source` 记 `default`（）。
3. **绑定一次。** `bindSession` 在会话创建时调用；结果写进日志的 `bind` 记录。已绑定的会话再次 `bind` 同语言是空操作，换语言或换会话 id 被拒。`LanguageBinding` 在构造后冻结，没有 setter：绑定之后没有任何路径能改语言（）。
4. **fork 继承。** `LanguageBinding#fork(childId)` 复制语言、`source: "fork"`、记下 owner；子会话此后独立（§6.1，§6.1 步骤 1）。
5. **不随全局变化。** 绑定之后本包不再读取阶梯；重试、失败、压缩、苏醒都不改语言（）。
6. **语言指令随语言渲染。** 中文提示的 `# language` 段即「思考过程与全部对外输出，一律用简体中文。」。这一段由渲染器按语言选取，是 §6.1 指名的那一句；本包**不**检查资源文件里是否也写了它（§6.1：不审查内容）。

## 4 资源规则

1. **成对。** 每个 id 在 `resources/<id>/` 下必须有 `en.md` 与 `zh-CN.md`。缺一本 → `RESOURCE_PAIR_INCOMPLETE`；读失败 → `RESOURCE_MISSING`；非 UTF-8 → `RESOURCE_MISSING`。**绝不**用另一本顶替（§6.1）。
2. **六个资源，按呈现顺序**（§6.1 的六项，一一对应）：

   | id | 内容 |
   |---|---|
   | `repo-aware-rules` | repo-aware 规则原文（英文资源是根规格 §5.1 `common` 那段 repo-aware 文本的独立资源副本；中文资源为对应译文） |
   | `pair-programming` | 结对编程准则；中文资源首句即「思考过程与全部对外输出，一律用简体中文」 |
   | `token-economy` | 缓存命中与重算 |
   | `wait-cost` | 等待成本校准 |
   | `context-limit` | provider 声明的窗口，只作事实 |
   | `budget` | 已花回合、估算剩余、已用时间，明确不是上限 |

3. **不翻译标识符。** 资源里出现的工具名、参数名、字段、枚举字面量、路径、命令保持原样（§6.1）。
4. **资源 id 先校验再触盘**：`^[a-z0-9]+(-[a-z0-9]+)*$`，防止 id 逃出资源目录。
5. **资源内容不审查。** §6.1 只允许形式合规检查（语言缺失即失败、资源成对、字节前缀不漂移）。本包**不**检查资源文本是否提到某个主题、是否保留某句：那属于内容审查，规格明确排除。§6.1 的六项内容是资源本身的编写义务，由资源文件承担；代码只负责按序渲染与成对校验。

## 5 呈现与注入

### 5.1 冻结与事实

**一次 epoch 一份冻结的呈现。** 一个 epoch 的第一次请求渲染一次文本，此后该 epoch 的每个请求都用这份文本；它作为一条 `presentation` 记录落盘，`presented` 记录只引用它的摘要。这样：

- 资源文件在 epoch 中途被改写，不会让同一 epoch 的字节漂移；
- 冷启只解析已存文本，不重读资源目录，重建结果与重启前逐字相同（§6.1 步骤 6）；
- handles 变化只对**下一个** epoch 生效（§6.1：同一 epoch 内不得改已呈现的字节）。

一次呈现的事实是 `PresentationFact = { epoch, digest, handles, after, inserted, text, entries }`。`text` 与 `entries` 在内存里随事实返回；落盘时它们只进 `presentation` 记录，`presented` 的事实只留 `{ epoch, digest, handles, after, inserted }`：

| 字段 | 含义 |
|---|---|
| `epoch` | 呈现代次。同一 epoch 内已经呈现的字节不得改变。 |
| `digest` | 冻结文本的 SHA-256。折叠时校验文本与摘要一致，不一致即 `FACT_CORRUPT`。 |
| `handles` | 本 epoch 冻结的未结清 park handles，去重后按字典序排序。 |
| `after` | **下一次请求**把对放在它自己的第几条消息之后。`null` 表示本 epoch 还没放置。 |
| `inserted` | 对在**上一次送出的**请求里的下标，仅供阅读与诊断。 |
| `text` | 冻结的提示全文。落盘时只写一次，见 `presentation` 记录。 |
| `entries` | 冻结的资源 id 顺序。随 `text` 一起只写一次。 |

**为什么是计数而不是下标。** 宿主的每个请求都由它自己的历史重建，历史里没有我们的对。用「上一次请求里的下标」定位，下一次请求里对会被后移两条，前缀立刻漂移；用「前 N 条」定位，对永远落在同样的两条消息之间，后一次请求自然以前一次为前缀（§6.1）。

### 5.2 合成消息对

消息形状沿用 OpenCode v2 的 `ModelMessage`：

- 调用：`{ role: "assistant", content: [{ type: "tool-call", toolName: "", toolCallId, input: { epoch, digest, handles, position, after } }] }`
- 结果：`{ role: "tool", content: [{ type: "tool-result", toolName: "", toolCallId, output: { type: "text", value: <提示全文> } }] }`
- `toolCallId = "pair-hint:<epoch>:<digest 前 16 位>"`：同一事实永远得到同一个 id，重放请求带同一对。
- 调用参数里的 `position` 写 `inserted`（它在本次请求里的下标），`after` 写下次放置用的计数；两者都由事实决定，不由调用方决定。

### 5.3 渲染与解析

`renderPresentation({ language, entries, blocks, handles })` 的布局固定：

```
# language
<该语言的语言指令>

<!-- pair-hint: resource=<id> -->

<资源正文>

…（按 entries 顺序重复）

# handles

<无 / 逐条 - handle>
```

- `# language` 段恒在最前：语言约束是 §6.1 的第一条内容，也保证「中文版首句」在整段文本里最先出现。
- 资源标记行让 `parseHint(text)` 能切回 `{ entries, blocks, handles }`；重复、未知或乱序的标记是失败，不是跳过。
- handles 段恒在最后，变化只影响尾部。
- `render(parse(x)) === x`：冷启不依赖资源目录，也不依赖任何宿主历史。

### 5.4 放置规则（`PairHintInjector#inject`）

按顺序判定，先命中者生效：

1. 请求里没有这一对 → 放在 `after` 条消息之后；`after` 为 `null` 时放末尾，并由本次放置记下 `after` 与 `inserted`（`placed`）。`after` 超出请求长度 → `PREFIX_DRIFT`，不静默夹取。
2. 已有这一对，且 `digest` 与 `epoch` 都相同 → **原地重放**（`replayed`），不新增（§6.1「同一位置重复处理只重放、不新增」）。返回值里的 `text` 是请求中**已呈现**的那份字节；重放还给出「这一对之前有几条消息」，下一次放置沿用它。
3. 已有这一对，`epoch` 相同但字节不同 → `PREFIX_DRIFT`。要改呈现只能开新 epoch（§6.1，§6.1 步骤 4）。
4. 已有这一对，`epoch` 不同 → 移除旧对，把新对放在新事实的位置（无 `after` 时放末尾，`placed`）。
5. 同一请求里出现两对或更多 → `PREFIX_DRIFT`（带 `positions`）。这是调用方的重复处理，无法在不猜的情况下重放，因此拒绝而不修补。

**开新 epoch 时对本身必须换、位置必须不换。** §6.1 的前缀铁律是「同一 epoch 内」的；开新 epoch 正是为了改已呈现的字节。所以跨 epoch 的边界只有两条可核对的事实：
1. 新 epoch 的第一次请求 `N` 把新对放在旧 epoch 最后一次请求 `P` 里旧对所在的**同一位置**（`PairHint#newEpoch` 把下一个 `after` 定为「上一次呈现里旧对之前的消息数」，由 `findInjection` 读出，而不是用 `length − 2` 推算）；
2. `N` 里新对之前的每一条消息与 `P` 里对应消息逐字相同。

不这样做的后果是具体的：新对落到副本末尾、旧对更靠前，一次 epoch 切换就改写了模型已经读过的大部分位置，所有从自身历史重建请求的宿主（三个都是）都会看到「对的位置漂移」而不是「对的内容换代」。跨 epoch 的字节前缀**不**成立，也**不**该成立——这正是 `assertPrefix` 只在同一 epoch 内调用的原因。

**第 2 条不要求对停在 `after` 处。** 一个本来就带着对的副本（宿主把刚送出的请求原样交回、调用方把副本加长后再投影），对可以停在任意条消息之后：它前面的消息一字未改，对本身仍是冻结字节，这就已经满足 §6.1 的字节前缀，而不是违背它。此时**副本里对的实际位置就是新的 `after`**，下一份不带对的副本会落回同样两条消息之间。旧实现把「对不在 `after` 处」一律判成漂移，等于拒绝唯一已经构成前缀的副本；现已删除。

**比较的是语义身份，不是宿主对象（`canonical.mjs`）。** 链状态是 `chain.mjs` 的 `Chain`：每条消息一个 `canonicalKey(message)` 的 SHA-256，外加一个根与对的下标。`assertPrefix` / `extendsPrefix` / `samePrefix` 对消息数组逐条比较 `canonicalKey(message)`（即 `canonicalMessage` 的 `stableJson`），对 `Chain` 则直接比较它的摘要；两条路径得到同一答案，因为摘要就是那串文本的哈希：

- **正向白名单**：只抽取模型真正读到的内容 —— `role`（`user`/`operator`/`human` → user，`assistant`/`model`/`ai` → assistant，`system`/`developer` → system，`tool`/`toolResult`/`function` → tool），以及按顺序的语义部件：
  - `text`：`{ type: "text", text }`；
  - `tool-call`（也接受 `toolCall` / `tool_call`）：`{ type, callId, toolName, input }`，id 读 `toolCallId`/`id`/`callId`，名字读 `toolName`/`name`，参数读 `input`/`args`/`arguments`（对象或 JSON 字符串），`input` 经 `stableJson` 规范化；
  - `tool-result`（也接受 `toolResult` / `tool_result`）：`{ type, callId, isError, output }`，`output`/`result`/`content` 统一成一种内容 —— `{type:"text"|"json", value: 字符串}` 与裸字符串都是 text，`{type:"error", value}` 是 text 且 `isError`，`{type:"content", value:[{text}]}` 拼成 text，其他非字符串走 `stableJson`；
  - `reasoning` / `thinking`：`{ type: "reasoning", text }`。
- **其余一律忽略，且是「忽略」而不是「黑名单」**：`timestamp`、`usage`、`duration`、`ttft`、`responseId`、`completedAt`、`contextSnapshot`、消息级 `id`、`providerExecuted`、`metadata`、`partialArgs`、`streamIndex`，以及任何将来新增的宿主字段。宿主新增字段**不可能**造成漂移，因为白名单根本不看它。
- **键序无关**：所有对象经 `stableJson` 排序，插入顺序不同不是变化。
- **不改写宿主消息**：`PairHint#project` 返回的仍是调用方自己的副本（含放进去的那一对），逐字不变；规范化只发生在比较与摘要。

这条规则取代了三个宿主各自的 `CHAIN_EXCLUDED` / `toHintPart` 黑名单：那是同一规则的第二份实现，两份必然分叉，而分叉正是 2026-11-01 反复漂移的根因。

### 5.6 请求的种类：主线、临时侧通道、分支

链（§6.1）是**会话的对话**，只有主线请求属于它。宿主还会发出借用这段上下文、随后被丢弃的请求：

| 宿主 | 请求 | 是否进 `context` 钩子 | 是否写进会话记录 |
|---|---|---|---|
| OMP | `runEphemeralTurn`：标题分叉、composer 预测、`/btw`、idle maintenance、completion probe、扩展 API | 是（`sdk.ts` `transformContext` → `emitContext`） | 否 |
| OMP | advisor、compaction、handoff、generate、title-model 路径 | 否（走 `buildSideRequestContext`） | 否 |
| OpenCode | `session.generate`（warming、一次性生成） | 否（走 `generate` 钩子） | 否 |
| OpenCode | title / compaction | 否（各自的钩子名） | 否 |
| Cline | `beforeModel` 的会话自身请求 | 是 | 是 |

OMP 的侧通道请求是这样拼出来的（`agent-session.ts:12929` `#buildEphemeralSnapshot`）：当前消息 + **正在流式输出的 assistant 消息被截成纯文本副本（tool-call 全部丢弃）** + `developer` 提醒 + `user` 的 `<title-request>`。它故意复用上下文以命中前缀缓存（提醒原文：`"reuses current conversation context ... to keep prompt cache warm"`）。

**`project({ messages, kind })`** 的 `kind` 只能取 `REQUEST_KINDS` 之一：

- **`main`**（默认）：主线。做前缀检查，写一条 `presented` 记录（`length`、每消息摘要、根、对的下标、事实），更新 `#chain` / `#after`。
- **`ephemeral`**：侧通道 / 分支。**照样在对的位置放对**（同一 epoch、同一冻结文本、同一 `after`、同一字节），宿主缓存前缀照样命中；然后**什么都不记、什么都不动** —— 不写 `presented`、不改 `#chain` / `#presentedEpoch` / `#after`、不消耗待写的 re-base。宿主会丢弃这次请求，把它记成链的一步就等于让下一次真正的请求去对照一段从未说过的话。
- **`branch`**：宿主自己重建、从不保存的视图（摘要、压缩提示），目前与 `ephemeral` 同义。

分支的容忍度是**精确**的：

- 它只能在**一处**与链不同，且必须是链**最后一条**已呈现消息 —— 那是宿主仍在写的那条。更早的消息模型已当作最终内容读过，改写它无论请求自称什么都算 `PREFIX_DRIFT`。
- 它不能比共同前缀更短（丢掉模型读过的消息）→ `PREFIX_DRIFT`。
- 其余部分是宿主追加的它自己的消息，不参与比较。

**判定「这是不是侧通道请求」属于适配器**：OMP 的标记是 Symbol，而 `emitContext` 先 `structuredClone`，Symbol 会被丢掉，本包根本看不到；靠文本或角色去猜，正是「黑名单追着宿主字段跑」的老毛病。适配器说**这是什么请求**，本包说**这对链意味着什么**。

**2026-11-01 真机（会话 `01a19547`）**：主线第一轮（1 条消息）呈现 3 条；4 ms 后标题分叉在主线四个工具调用结束前进来，副本是 `[user, assistant(76B 部分文本), developer(提醒), user(<title>)]`。它扩展了**去掉对之后**的前缀（下标 0 相同），链因此接受并把链状态覆盖成这次侧通道请求；4 ms 后主线真正的那轮是 `[user, assistant(text + 4 tool-calls, 3853B), 4×tool-result]`，在下标 1 与侧通道的 76B 部分文本不同 —— 此后该会话每次请求都以 `PREFIX_DRIFT` 拒绝。根因不是「比较太松」，而是「被丢弃的请求不该成为链的一步」。

`assertPrefix({ previous, next })` 检查「后一次请求以前一次请求为前缀」，返回 `true` 或抛 `PREFIX_DRIFT` 并给出第一个不同的消息下标。`previous` 可以是消息数组，也可以是 `Chain`；后者直接比较持久化的摘要，不需要一份存下来的副本。**没有豁免模式**：重放也是发出去的请求，宿主改写了已呈现消息同样要拒。`extendsPrefix({ previous, next })` 是同一判断的谓词形式（同样接受 `Chain`），`shownPlacement(messages)` 给出副本里对之前有几条消息（无对时 `null`）。

**接力屏障下的重新锚定（`PairHint#anchor`）。** 同一个 epoch 内唯一的合法链断点是宿主自己的事实替换了请求副本（根 §2.4 第 3 步的接力屏障）。`anchor(base)` 接收**放置之前**的新副本 `base`，把链的锚设成 `base` 本身，并把下一个放置计数 `after` 定成 `base.length`（即「对放在这份副本之后」）。
- **锚必须是 `base`，不能是 `base` + 对。** 宿主保存的是 `base` 加上它自己后来的消息，从不保存我们的对。锚在「`base` + 对」上，等于要求之后每一份副本都在同一个下标处带着对：第二份请求是 `base` + 宿主的回答 + 对，对已经不在原下标，`assertPrefix` 立刻判漂移——2026-11-01 真机继任者第二次请求就是这样报 `PREFIX_DRIFT` 的。
- **锚的形态必须与 `project` 比对时用的形态一致。** 调用方必须先做它自己的消息归一（OMP 适配器是 `toPairHintMessages`：字符串内容与单个 text part 同形，适配器标记与时间戳不算对话），再交给 `anchor` 和 `project`。两边形态不同时，同一句话也会在下标 0 处被判为漂移。
- **`anchor` 开启一条新链。** 锚点之后的第一条请求是链首，不与断点之前那次请求比较（`#presentedEpoch` 置空）；第二条起照旧严格，与锚点摘要逐条比对。只替换 `#chain` 而不置空时，这次调用对前缀检查不可见——下一次 `project` 走的是普通请求那条分支，锚点是否起作用取决于新旧副本恰好有没有共同前缀，而不是取决于调用方对宿主事实的声明。断点不是豁免：锚点里的字节就是调用方即将发出的字节，`anchor` 自己一个字节也不改写；调用方若把锚设成它刚刚呈现的那份副本，就让掉了唯一能抓到改写的那次比较，这个取舍属于调用方（三个宿主都只在屏障处锚）。
- `anchor` 只改链：冻结文本、epoch、handles 与放置都不变；锚点之后的请求仍必须扩展它，已经呈现的字节绝不改写。
- 本 epoch 还没有冻结文本时 `anchor` 什么也不做（没有对可锚），下一次 `project` 自己决定放置并开链。
- **`anchor(base, { reason })` 记下断链的原因**，`reason` 只能是 `REBASE_REASONS` 的三个值：`relay`（根 §2.4 第 3 步的屏障）、`scope-reset`（`resetScope` 边界裁掉了已读消息）、`scope-remove`（`removeLast` 就地改写了上一拍）。原因随**下一条** `presented` 记录写入 `rebased` 字段（可选、向后兼容：旧日志没有该字段照常折叠）。`PairHint#rebased` 读回它，冷启后仍可查。未知原因在写入任何东西之前抛 `INVALID_ARGUMENT`。
- **何时该 `anchor`：投影「确实改了副本」时。** 判定只看字节，不看记录：`src/compose/request-boundary.mjs` 的 `projectionBoundary` 比较「上一次呈现的副本（去掉对）」与「本次投影产出的副本（去掉对）」，只有**投影自己**把副本改短或就地改写才算替换（它还要比较投影的输入与输出，投影什么都没做就不算）。`resetScope` 只证明模型请求过，不证明这次副本被裁；在没被裁的请求上锚会丢掉唯一能抓到真实改写的那次比较。
- 一个继任任期只 `anchor` 一次（宿主用屏障记录的身份去重，见 `host-opencode/spec/session-state.mjs#anchorPrefix` 与 `host-cline/tenure.mjs#anchorOfFence`）：对每次请求都重新锚定会丢掉宿主没保存的那一对。
- **本 epoch 已经因 handles 变化开过新 epoch 时不再 `anchor`。** 开新 epoch 已经决定了链（`newEpoch` 把新对放在旧对的位置），再锚一次会把那个决定扔掉。OMP 适配器因此在同一次调用里只做其中一件（`host-omp/session-state.mjs#pairHintProject`）。

**epoch 的读法：** 只从 `toolCallId` 的 `pair-hint:<epoch>:<hex>` 解析，`<epoch>` 必须是十进制数字、`<hex>` 必须是十六进制；解析失败即视为「不是本包的对」，走第 4 条。

### 5.5 空闲提示

`# handles` 段：

- 没有未结清站点：一行「无」（两种语言各一句）。
- 有站点：逐条 `- <handle>`，随后一句说明「列出只要求表态，不强制恢复，系统也不替你恢复」。这一段是提示，不是恢复动作（§6.1）。

## 6 持久事实

**位置：** `.Herdchestra/{sessionId}.jsonl`（§1 允许的唯一持久化位置）。日志文件名必须是文件安全的 `sessionId`；宿主可以用摘要当文件名，把真实会话 id 交给 `bind`/`open`。

**记录（每行一条）：**

| `kind` | 字段 | 含义 |
|---|---|---|
| `bind` | `binding: { sessionId, language, source, ownerSessionId }` | 会话语言，创建时写一次 |
| `epoch` | `epoch` | 新呈现代次 |
| `presentation` | `epoch`、`digest`、`text`、`entries` | 一个 epoch/digest 的冻结提示文本，写一次；同一 epoch 的每个请求都引用它 |
| `presented` | `length`、`digests`（每消息摘要）、`digest`（根）、`pair`（对的下标）、`fact: { epoch, digest, handles, after, inserted }`、可选 `rebased` | 上一次送出的**链状态**，不含请求副本；`length` 是消息条数（含合成对），`digests` 是每条消息 `canonicalKey` 的 SHA-256 |
| `refused` | `code`、`message`、`epoch`、`index`、`previous`、`next` | 上一次请求为何被拒。**只是诊断**：拒绝不发请求，因此不折叠进状态，只随状态读回（`PairHintState#refusal`） |

**`presented` 记录是摘要，不是副本。** 每条 `presented` 只存 `length`、每消息摘要、根与对的下标；
提示全文只在 `presentation` 记录里出现一次。折叠时**只有最后一条带链的** `presented` 会被观察到
（`apply` 每条都整体替换 `#chain`），所以 `JournalStore` 在文件超过 4 MB 时压缩：原子重写
（写 `.compact` 再 `rename`），把旧形状的 `messages` 迁移成摘要、把每条事实里的 `text`/`entries`
提升为一条 `presentation` 记录。折叠结果完全不变；冷启从最新摘要续链。没有 `messages` 也没有
`digests` 的记录是已被取代的旧记录，折叠时沿用上一条链（不发明、不丢弃）。
`length` 非整数、`messages` 非数组、摘要数组与 `length` 不符、根与摘要不符，仍是 `FACT_CORRUPT`。
旧日志的 `messages` 在折叠时按 `canonicalKey` 计算同一组摘要；`foldRecords` 只对最后一条带链的记录
做这件事，因此旧日志的冷启不是 O(n²)。

**`refused` 记什么、不记什么。** 拒绝过去只在宿主日志里留下一句话和一个下标——知道"漂了"，
不知道"哪里、差多少"。2026-11-01 的真机漂移就是靠翻宿主自己的数据库才说清是哪把键不同。
所以现在 `PairHint#project` 在抛出前写一条 `refused`：

- `index` 是前缀规则第一条不一致的消息下标；`previous`/`next` 是它比较的两侧（链与本次请求，
  各去掉本 epoch 的对），各带 `count`（真实条数）与 `shapes`（前 `REFUSAL_SHAPE_LIMIT` = 64 条的形状）。
- 链侧的每条形状只有 `{ digest }`：全文已不在日志里，摘要仍足以核对下标。请求侧的形状是
  `{ role, parts, calls, results, length, digest }`：`role` 是规范化角色，`parts` 是语义部件类型名，
  `length` 是 `canonicalKey(message)` 的长度，`digest` 是对**同一串**的 SHA-256。前缀规则比的正是这串，
  所以摘要相等 ⇔ 语义内容相同；下标处摘要不同就是漂移，且可核对而不必拿到原文。
- **不记**消息文本、工具入参、工具出参、系统提示、凭据。摘要不能反推原文，长度也不足以重建一条短消息。
  记录回答"哪里、差多少"，永不回答"用户说了什么"。
- 写入失败被吞掉：诊断丢了可以接受，用诊断失败替换真正的拒绝不行。抛出的错误本身一个字节也不改。
- 冷启照常折叠：`refused` 不改 epoch、不改链、不改放置；字段非法则 `FACT_CORRUPT`，
  未知字段忽略（新版本写的日志旧版本要读得动）。

**折叠：** `PairHintState#apply` 纯函数式地折叠一条记录，返回新状态；`foldRecords` 折叠整份日志，并只对最后一条带链的 `presented` 计算旧形状的摘要。以下情形一律 `FACT_CORRUPT`：未知 `kind`、坏字段、日志里换语言、`epoch` 倒退、`presented` 的事实属于别的 epoch、事实文本与其摘要不符、`presented` 的摘要数组与 `length` 不符、根与摘要不符、`fact` 指向一个没有 `presentation` 的摘要。

**容错：** 只容忍「最后一行被截断」——那是 append 期间崩溃留下的半条记录，不承载事实。中间行坏 → `FACT_CORRUPT`。

**append：** `O_APPEND` 追加整行；跨进程并发追加不会在行内交错。**进程内**的 append 串行执行：前一条落盘并折叠完成后，后一条才读取缓存。记录在写入前先按当前状态试折叠一次，被拒的记录不会进入文件。

## 7 `PairHint`（宿主入口）

```js
const hint = await PairHint.open({ root, sessionId });   // 读日志
await hint.bind({ ladder: { explicit, host, locale } }); // 或 bind({ sessionId, forkOf })
hint.setHandles([...]);                                  // 表面提供的未结清 handles（下个 epoch 生效）
await hint.currentFact();                                // 下一次请求的呈现事实
const { messages, mode, fact } = await hint.project({ messages }); // 请求副本
hint.chain;                                             // 链状态（冷启可用），不是副本
await hint.newEpoch();                                   // 需要改呈现时
```

- `project` 依次做：取本 epoch 的冻结文本（没有则渲染并冻结）→ 首次使用时 append 一条 `presentation`（`epoch` + `digest` + 文本 + 资源表）→ 生成事实（带 `after`）→ 注入（`placed` 或 `replayed`）→ 同一 epoch 内与上一次呈现做摘要前缀检查 → append 一条 `presented`（`length` + 摘要 + 根 + 对的下标 + 事实）→ 返回新数组。输入数组从不被修改。
- **epoch 切换沿用旧对的尾部。** `newEpoch()` 把下一个 `after` 定在上一次呈现的请求里旧对之前的位置（见本节 §5.4），所以新 epoch 的第一次请求仍以旧 epoch 的最后一次请求为前缀；若本 epoch 还没有任何呈现，`after` 为 `null`（下一次投影放在末尾并自行决定）。
- **放置跟随已呈现的副本。** 当本次投影的副本以上一次的呈现为前缀（宿主交回原请求、或把副本加长）时，`after` 改记副本里对的实际位置，并写进 `presented` 记录；于是下一次不带对的请求仍落在同样两条消息之间，而不是被判漂移。前缀本身不改，只改「下一次放哪」。
- **会话内串行。** 同一 `PairHint` 的 `project`/`newEpoch` 串行执行，失败不打断队列。
- **前缀链以日志里的 `presented` 摘要为锚。** 冷启后锚来自 `presented` 记录的 `digests`/`digest`，因此重启后的第一次请求也受同一 epoch 的前缀约束，且不需要一份存下来的副本。新 epoch 的第一次请求另起一条链。
- 未绑定的会话 `render`/`project` → `INVALID_LANGUAGE`（「语言缺失即失败」，§6.1 步骤 7）。
- **`mode` 说的是「这一对发生了什么」，不是「哪个分支跑了」。** 宿主不存这一对，所以下一次请求
  到达时**不带**它，`inject` 只能走「副本里没有对」那条分支、只会答 `placed`——它知道放置已决定
  （`fact.after !== null`），却不知道本 epoch 是否已经呈现过。判断因此放在 `PairHint#project`：
  本 epoch 已呈现、副本不带对、放置未变、且字节相同 → `replayed`；其余（epoch 首请求、副本增长、
  放置移动）仍是 `placed`。「字节相同」用链自己的 `extendsPrefix` 双向比较，不另写一套
  `JSON.stringify` 相等，免得两个定义日后分叉。两种 mode 发出的字节完全一样，只有标签不同。
- `MemoryJournal` 提供与 `JournalStore` 相同的 `read`/`append` 形状，供测试与自带事实的宿主使用。

## 8 失败码

| 码 | 触发 |
|---|---|
| `INVALID_ARGUMENT` | 参数形状不对（非数组、空串、非对象、索引越界） |
| `INVALID_LANGUAGE` | 语言字面量非法；显式配置不可用；未绑定就渲染 |
| `RESOURCE_UNKNOWN` | 资源 id 不在库内或形状非法 |
| `RESOURCE_PAIR_INCOMPLETE` | 缺 `en.md` 或 `zh-CN.md` |
| `RESOURCE_MISSING` | 资源存在但读失败或非 UTF-8 |
| `PREFIX_DRIFT` | 同一 epoch 内字节要变；新请求不以旧请求为前缀；放置计数超出请求；同一请求两对 |
| `EPOCH_CONFLICT` | 保留给宿主层报告代次冲突（本包在 epoch 不同时按新 epoch 放置，不抛此码） |
| `FACT_CORRUPT` | 日志中间行损坏、记录字段非法、日志属于别的会话、文本与摘要不符、摘要数组与 `length` 不符、根与摘要不符、`fact` 指向没有 `presentation` 的摘要 |

## 9 与相邻层的关系

- **compose / 宿主适配器：** 宿主在请求级钩子里取 `messages` 副本交给 `hint.project`，把返回值当作本次 provider 请求的 `messages`；**不落持久历史**。需要判断「投影是否改写了上一次呈现」时读 `hint.chain`（`Chain`），不要读一份副本；`compose/request-boundary.mjs` 的 `projectionBoundary` 两种形状都接受。宿主提供 `root`、`sessionId`、阶梯输入、未结清 handles；`fork` 子会话以 `bind({ sessionId, forkOf: owner.binding })` 继承。
- **Scope（§6.2）：** 两包都只改请求副本；本包不读也不写 retention。
- **compose/prompt（§5）：** 那三文件投影是系统提示（§5 消息投影上），本包注入的是消息层的一对合成调用与结果（§6 消息投影下），两者不互相改写。

## 10 检验

- 语言：阶梯四级各一条；显式非法值抛错而不落英文；绑定对象不可变；fork 子会话复制后独立。
- 资源：六个 id 的两本都在且非空；缺一本报 `RESOURCE_PAIR_INCOMPLETE`。文本内容本身不检查（§6.1）。
- 渲染与解析：`render(parse(x)) === x`；handles 段在最后且按字典序。
- 注入：无对时按 `after` 放置（首次决定）；同一事实再次处理只重放；同 epoch 换字节报 `PREFIX_DRIFT`；开新 epoch 后旧对移除、新对按新位置放置；`after` 越界报错。
- epoch 与前缀：同一 epoch 内每次请求仍以它之前那次为前缀（属性测试覆盖任意增长序列）；任意次 `newEpoch()`（含 handles 变化触发的那种）之后，新对仍在旧对的位置、之前消息逐字不变；`anchor(base)` 之后的第一次请求以 `base` 为前缀、对落在 `base` 之后，第二次请求（`base` + 宿主自己的消息）同样通过；锚点之后的第一次请求不与断点前的请求比较（`anchor` 开新链），而锚点之后的**第二条**请求再改写已呈现消息仍报 `PREFIX_DRIFT`。
- 前缀：宿主每次重建自己的请求时，三次请求的对都在同一位置，逐次 `assertPrefix` 通过；重放但改写/删除已呈现消息被拒；逐字不变的重放通过；带对或加长后仍以旧呈现开头的副本一律通过（属性测试覆盖任意增长序列与任意消息数）。
- 链状态：新 `presented` 记录没有 `messages`，有 `length`、`digests`、根与对的下标；同一 epoch/digest 的 `presentation` 只有一条；任意请求序列的摘要链等于把同一序列消息逐个摘要的结果（`property: any request sequence...`）。
- 漂移下标：任意一条已呈现消息被改写，`PREFIX_DRIFT` 的 `details.index` 就是那条消息的下标（`property: any request sequence...`）。
- 旧形状：带 `messages` 与 `fact.text` 的旧记录折叠成同一 `Chain`；压缩把旧副本迁移成摘要并把文本提升为 `presentation`（`compaction migrates an old journal's copies...`）。
- 冷启：`foldRecords` 与新建 `PairHint` 从摘要重建同一 `Chain` 与同一冻结文本，并能继续校验前缀（`property: a cold fold...`）。
- 冷启：资源文件在 epoch 中途被改写，重启后仍还原旧字节，新 epoch 才读到新资源；`epoch` 记录重启后仍生效。
- 并发：同一会话两个交叠的 `project` 不误判漂移；同一日志两个交叠的 append 都不丢。
- 容错：末行截断被忽略；中间行坏、换语言、epoch 倒退、文本与摘要不符均报 `FACT_CORRUPT`。

## 11 已知缺口

1. **`EPOCH_CONFLICT` 未使用。** 本包按「epoch 不同即新呈现」处理；若宿主需要在同一 epoch 内并发写入两份不同呈现，冲突由宿主层报此码。
2. **旧日志的迁移发生在折叠与压缩时。** 新写入只产生摘要；一份旧日志在第一次折叠时计算最后一条链的摘要，在第一次超过 4 MB 的压缩时把旧副本与事实里的文本改写掉。迁移前它仍可读，只是文件更大。
3. **资源文本是产品文本，不是逐字规范。** 根 §5.1 的 `common` 由 `compose/prompt/files.mjs` 逐字投影；本包的 `repo-aware-rules` 是同一主题的独立资源，按会话语言渲染，不随 `common` 自动同步。
4. **locale 映射。** 只有 `zh`、`zh-Hans*` 与不标繁体习惯地区/脚本的 `zh-*` 映射到 SimplifiedChinese；`zh-TW` / `zh-HK` / `zh-MO` / `zh-Hant*` 不映射，阶梯继续。把繁体当简体是冒充，宁可落空也不换语种。
5. **跨进程无锁。** 同一会话若被两个进程同时驱动，前缀链只能靠日志串行（`O_APPEND` 保证行完整），后写者可能在下一个请求被判 `PREFIX_DRIFT`。本包不跨进程加锁；宿主须保证一个会话同时只有一个驱动者。
6. **前缀比较按消息粒度。** 对消息数组，`assertPrefix` 比较整条消息的规范化 JSON，不比较字节；对 `Chain`，它比较每条消息的摘要。消息内部若有语义差异（含键序被 `stableJson` 归一后仍不同的值）会被判为漂移（宁可误报，不放过真漂移）。
7. **epoch 中途的 handles 变化要等新 epoch。** 冻结是 §6.1 的直接后果；宿主若要立刻反映新的未结清站点，必须调用 `newEpoch()`，本包不会替它开。三个宿主都已在请求钩子里按「冻结的 handles ≠ 当前未结清 handles」判定并开新 epoch（`host-opencode/spec/context-hook.mjs`、`host-cline/session.mjs`、`host-omp/session-state.mjs#pairHintProject`）：列表不变时不动前缀，列表变化时付一次全量重算，否则 §6.1 的空闲提示在本会话里永远失效。
8. **`# language` 段恒在最前。** 这让语言约束成为最先读到的内容；代价是它占用了请求副本开头的位置，宿主的系统提示与对的关系由宿主自己决定。
