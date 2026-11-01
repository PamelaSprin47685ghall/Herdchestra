# compose 组合层规格

`src/compose/` 是黏合层：它把彼此独立的 core 库与宿主适配器拼成模型看到的那一面。
它自己不读文件、不跑命令、不创建会话；每一次真实动作都经 core 或经一个宿主端口。

## 0 位置与依赖方向

- 依赖只朝一个方向：`compose → core`，`host-* → compose`。compose 不导入任何 `host-*`，core 不导入 compose（根 §1、）。
- 宿主端口是普通对象，由适配器提供。缺少某个端口是接线错误（`MISSING_PORT`），在装配时报告，不在成员第一次被调用时才暴露。
- 组合层不持有程序计数器。程序状态只在 core 的 `ProgramState` 里；组合层只持实例与绑定。

## 1 包划分

| 目录 | 职责 |
|------|------|
| `prompt/` | 三个提示词文件 `common`/`manager`/`worker` 与两种拼接；导演消息全文（根 §5.4）；Plan 的角色段。 |
| `program/` | 一个程序对模型呈现的成员表与成员绑定。 |
| `fission/` | lane 组：准入、整组回滚、收敛条件、终局一次性。 |
| `relay/` | `review` 的台账判决与宿主副作用（接力、启动 Manager、代问用户）。 |
| `nudge.mjs` | 无人值守出口判定：`evaluateTurnExit` 与 `buildNudgeMessage`（根 §2.2）。纯函数，不读宿主、不读时钟。 |
| `request-boundary.mjs` | `projectionBoundary` / `shouldAnchor`：合法投影边界的重锚定（`relay`、`scope-reset`、`scope-remove`、`history-compacted`）。只比较 `canonicalKey`，判据是投影自己的输入与输出，加上宿主亲口报告的持久改写。 |
| `cue.mjs` | 组合层通往 CUE 的唯一门：`renderReceipt`、`renderNotice`、`refusalText`（根 §6.3）。 |
| `ports.mjs` | 宿主端口的形状与装配时校验。 |
| `errors.mjs` | 组合层自己的拒绝类型。 |
| `HOST-PORTS.md` | 交给宿主负责人的接口单：fission 步骤的回执形状、可选的 `deliver` 确认、relay 回执必须报告的三件事。 |

## 2 提示词投影（根 §5）

1. 根 §5 直接给出四份全文：§5.1 `common`、§5.2 `manager`、§5.3 `worker`、§5.4 导演消息。`prompt/files.mjs` 的 `COMMON`、`MANAGER`、`WORKER` 就是前三份全文，**逐字节相同**，不做句子取舍、不含标记、过滤或分支。
2. 拼接只有两种：`COMMON + MANAGER`（Manager），`COMMON + WORKER`（coder 与 devops）。coder 与 devops 拿到逐字相同的一串；身份差别只由 `<vibe-mode>` 与 `fork` 的任务描述承担。
3. 导演消息 `<vibe-mode>…</vibe-mode>` 是独立的角色段，不进这两个文件，也不由它们拼出。根 §5.4 给出它的全文；`prompt/director.mjs` 的 `DIRECTOR_MESSAGE` 就是这份全文，**逐字节相同**，抄录方式与 `files.mjs` 抄录 §5.1–§5.3 一致。§5.4 是根里唯一属于导演消息的编号：**不要用 §5.4 指别的段落**，也不要再声称根里没有这份正文。
4. 测试 `prompt/projection.test.mjs` **直接读根 `AGENTS.md`**，取出 §5.1–§5.3 的三个 ```text 块（扫描在 `### 5.4` 处停下），要求 `COMMON`/`MANAGER`/`WORKER` 与之一字不差。`prompt/dispatch.test.mjs` 同样直接读根 §5.4 的那个 ```text 块，要求 `DIRECTOR_MESSAGE` 与之一字不差。根改了而代码没跟，测试失败；不在测试里另抄一份句子清单，因为第二份抄本只会与代码一起漂移。
5. **§5 的四份全部逐字节核对，分居两个测试。** §5.1–§5.3 由 `projection.test.mjs` 核对；§5.4 由 `dispatch.test.mjs` 核对，方式相同。`projection.test.mjs` 的扫描止于 `### 5.4`，因为导演消息不是第四份文件、不参与两种拼接；把它计进「三个文件」会让计数与代码不符。
6. **审计者是测试，不是读者。** `files.mjs` 与根 §5 都只能经工具渲染来读，而渲染既截断、又会插入省略号，肉眼比对两边都不可靠。任何关于根 `AGENTS.md` 的字符级断言（逐字节、前缀稳定、行尾）一律由解析文件得出，绝不从渲染结果转写。同理，`prompt/dispatch.test.mjs` 经真实的 `SessionState.systemPrompt` 核对每个角色拿到的组合：角色决定提示词，不是「是否顶层」决定——顶层却被标成 `coder`/`devops` 的会话必须拿 `COMMON + WORKER`，只有 Manager 带导演消息。
7. **分派只认角色。** 宿主侧唯一的角色→组合映射点是 `SessionState.systemPrompt(role)`：`plan` 给 `PLAN_PROMPT`，`manager` 给 `COMMON + MANAGER` 加导演消息，其余交给 `systemPromptFor(role)`。`systemPromptFor` 只接受 coder 与 devops，别的一律拒绝（`UNKNOWN_ROLE`；`plan` 报 `PLAN_OUTSIDE_PROJECTION`）。**绝不把未知角色默认成 `coder`**：默认会让写错的角色串看起来像一个健康的 worker 会话。组合层不提供「按顶层与否挑提示词」的开关，宿主要这个语义就自己按角色分派，组合层只回答角色对应哪份文本。
8. **性质测试守住这几条不变量**（`prompt/dispatch.test.mjs`，用 fast-check）：角色唯一决定组合，与会话 id 无关；导演消息当且仅当角色是 Manager 时出现，且只出现一次；coder 与 devops 逐字节相同；未知角色被拒而不是降级成 worker。

## 3 程序面（根 §3、§4、§5、§8）

`program/surface.mjs` 的 `SURFACE_MEMBERS` 是唯一成员表：

- `js-manager`：`park`、`unpark`、`fork`、`join`、`review`、`ask`、`negotiate`、`askPreviousScope`。没有 `cat`/`stat`/`ls`/`write`/`mkdir`/`rm`/`mv`——Manager 连读都没有；读的唯一例外是独立的 `js-review`。也没有 `call`：Manager NEVER run（§2.1）。也没有 `fission`：裂变只属于 `fork` 启动的 worker（§4.2）。
- `js-worker`：`cat`、`stat`、`ls`、`write`、`mkdir`、`rm`、`mv`、`where`、`whereAll`、`park`、`unpark`、`call`、`fission`、`fork`、`join`、`negotiate`、`askPreviousScope`。coder 的 bash 禁令只在提示词里，`call` 照样在面里。没有 `ask`：根 §3.1 在 `js-worker` 一列明写「子代理无此通道」，提问只属于顶层程序（§3.3 例 7）；也没有 `review`。根 §3.1 把 `fork`/`join` 同时给了 `js-worker` 与 `js-manager`：worker 的 `fork` 落在**它自己的 roster**（§4.1），与 `fission` 是两件事，能否派工由 brief 说明。
- `negotiate`、`askPreviousScope`：§6.2 的两个豁免成员，放在**所有记账的面上**（`js-manager` 与 `js-worker`），因为报的就是这些面要花的额度。它们不进 `js-review`（只读评估不是任何 Scope 的一拍），也不进 `plan`（Plan 没有 Scope）。见 §3 的「同步快照查询」。
- `js-review`：`cat`、`stat`、`ls`、`where`、`whereAll`，只读；由 `ProgramSurface.reviewSurface()` 单独给出，不混进 `js-manager`。
  它只在评估窗口开着时存在（根 §2.3）：`reviewSurface()` 对关闭的窗口抛 `REVIEW_WINDOW_CLOSED`，对非 Manager 角色同样抛。
  窗口是会话的 `ReviewWindow`（core），经构造参数 `window` 传入；surface 只在**没有会话窗口**（自带窗口即本次程序自己的请求）或窗口的 `round === 0`（从未开过）时调 `beginRound()`。会话窗口的开轮属于宿主的请求边界：同一回合里受理过一次 review 之后，第二个 `js-manager` 程序不得把刚关上的评估窗口重新打开（§2.3）。
  受理一次 review 后 `noteReview(receipt)` 关闭窗口——被拒的 review 不关。适配器另有自己的闸门，但这一层独立成立：
  宿主漏掉闸门也读不到关着的窗口。
- `plan`：`cat`、`write`、`unpark`、`ask`、`devops`、`review`。没有单独的 `park`（根 §3.1 在 Plan 一列标 `-`）：`ask` 自己经 `parkChannel` 挂起程序并加上 `interrupt`（根 §2.2、§3.3 例 7），所以 `plan` 必须同时拥有 `unpark`：用户答复后由 AI 在新程序里 `this.unpark(name, answer)` 恢复原挂起点。`ask` 只在 `S1` 阶段可用，其余阶段调用抛 `ASK_FORBIDDEN_OUTSIDE_S1`。
- Plan 的 `write` 在本任第一次 review 之前被拒（`DRAFT_READ_ONLY`，根 §2.2）；判据是 core 的 `draftWritable(ledger)`，读的就是这个 surface 自己的台账。继任由 `openSuccessor()` 把 `reviewCount` 归零，于是新任期重新只读。`review` 必须先在本地台账记账再交端口执行副作用，否则 `write` 查的是另一份台账，解锁永远不会发生。

成员实现：

- 文件成员直接调 core 的 `FileMembers`；失败回执转成带同一 `code` 的 `CoreError`。`stat` 只答四个字段 `{ exists, size, mtime, isDir }`；缺失返回 `{ exists: false }`，不是错误（§3.1 与 §3.4 例 5 的 `if (!(await this.stat(p)).exists) continue` 依赖这一点）。
- `where`/`whereAll` 用 core 的 `TextMembers`，**第一个参数是内容、第二个是 needle**（`this.where(source, needle)`，§3.1、§3.4）；成员从不读文件。`where` 零匹配或多匹配时 park，payload 由 core 生成，`reason` 为 `explicit`。
- `call` 先 `beginOperation()` 拿到本次操作的 **id**，再 `await drainedPromise(id)`：core 按**身份**排空，只等 id 小于本次的操作，因此既不跳过任何先前操作，也不等自己（等自己即自我死锁）。排空期间 `program.draining` 为真、`acceptsExpressions` 为假，第二个表达式在**登记之前**就被拒绝（`INVALID_ARGUMENT`，消息含 `draining`）：`draining` 读的是等待者集合而不是操作计数，若先登记再判，第二个调用者会等第一个调用者才可能结清的前驱，成为永远等不到的幻影操作——§3.3 的「程序不再接受新的表达式推进」，否则它会越过排空要保护的那份工作。**不得**用 `pendingOperations - 1` 之类的计数补偿去猜「哪些是先前操作」：数量推不出身份，猜错要么跳过先前操作，要么永远等不到。命令期间程序在 `waiting`，输出进 core 的 `CommandView`。
- 一个程序同一时刻只跑一条命令：`call` 开始时若已有命令在跑，以 `INVALID_ARGUMENT` 拒绝。另一条命令不是本次的先前操作，它会在本次之后运行，任何排空都排不出次序；两条命令交错还会让 `waiting`/`running` 的回退互相覆盖。
- `call` 结束时把程序还给**它打断的那个状态**：命令开始时若程序已在 `waiting`（前一拍 SDK 操作未结清），结束后仍是 `waiting`，不冒充 `running`。命令进行中被 park 的程序保持 `parked`——恢复是离开 `parked` 的唯一通道（根 §3.3）。没有命令端口时 `call` 以 `INVALID_ARGUMENT` 拒绝，不抛 `TypeError`。
- **`call` 的拒绝次序照根 §3.3：先准入，后接线。** 已结束或已挂起的程序先以自身状态被拒（`is parked` / `is completed`），再是 `draining`、`running another command`，最后才是「没有命令端口」。理由是调用方要据此决定下一步：程序挂起时它必须去恢复，而不是重试或怀疑接线；先说「没有命令端口」会把真正可行动的事实藏起来。拒绝不改变任何状态，挂起站点仍在，恢复照旧可用。
- `write` 只保证单次调用的原子替换；多次 `write` 之间没有事务、没有锁、没有校验，同一路径写两次就是后一次获胜（§3.2）。「每路径只写一次」是程序自己的纪律（§3.4 的例子），组合层不拦。
- `park` 返回九字段回执；同一程序第二次 park 被拒。`ask` 用同一个通道 park，`reason` 为 `ask`、`payload` 为 `{ question }`，回执另带不可枚举的 `interrupt: true`：宿主必须结束本轮回合，把控制权交还用户。
- **`ask` 的阶段门禁。** 成员只在 `js-manager` 与 `plan` 两面上存在（`js-worker` 没有此通道，根 §3.1）；且只有角色是 Plan 且 `this.ledger` 的里程碑为 `S1` 时才允许（core `askAllowed(ledger)`）。门禁先于参数校验：`this.ask("")` 在越界角色或阶段也一律抛 `ComposeError("ASK_FORBIDDEN_OUTSIDE_S1", ...)`，程序**不挂起**。万象术无人值守，只有 Plan 的第一阶段可以向人提问。成员保留而不删，是为了让越界调用读到具名拒绝，而不是一个 `TypeError`。
- **`devops` 单飞。** 同一任务同时至多一个未决 DevOps（根 §2.2）。surface 自己持一个在途标记：第一个 `this.devops(intent)` 未结清时，第二个调用抛 `ComposeError("DEVOPS_IN_FLIGHT", ...)`；第一个调查记录原样返回，组合层不做二次摘要。
- 已结束或已挂起的程序不再接受新表达式（core 的 `acceptsExpressions` getter）。
- **回执的形状只有九个字段。** `receipt()` 与 `finalReceipt()` 用 core 的 `withExtra` 把 `value`、`result` 挂成不可枚举键，`Object.keys`、展开与深比较都只看到九个字段（§3.3）。可枚举的第十个键会让"形状"随便利字段漂移。
- 期限：`ProgramSurface` 构造时按 `intent` 一次性固定 `Deadline`（`intent` 缺省为 null，即无期限），
  `receipt()` 的 `remainingWaitMs` 报告程序自己剩余的期限，不再硬编码 0。挂起时 core 取消该期限，
  控制程序的 `unpark` 把自己的 `deadline` 一并交给端口，目标按新期限继续。
- 收尾：宿主在程序体返回或抛出后调用 `settle("completed"|"failed", result)`；它先 `finish`，
  再等本程序的操作排空，最后从注册表退休这个名字。名字在排空完成前不释放。
- `unpark` 把自己程序的 `capabilities` 与控制请求的 `deadline` 一并交给端口：能力集合是被恢复程序自己的事实（core `ProgramState#capabilities`），由端口补一个空集合就等于不设防。回执是目标程序恢复后的回执，`value` 是挂起表达式收到的值。
- `fission` 的 owner 在**调用时**向端口取会话名，不在构造 surface 时取：surface 活得比一次调用久，构造时取会把一次调用的答案冻进之后每一次。端口说不出会话名时 owner 为 `null`，由 fission 层以 `InvalidOrigin` 拒绝；绝不用程序名顶替——没有会话以工具调用 id 为名。
- `unpark`、`fork`、`join`、`fission`、`review`、`devops` 只转给端口，不自造结论。端口缺席时成员在**调用**处以 `MISSING_PORT` 拒绝，构建 surface 不因某个端口缺席而整体失败。
- **同步快照查询（`negotiate`、`askPreviousScope`）。** 两者只转给 `scope` 端口的 `ask(member)`，且**必须同步返回**：§6.2 明说这两个成员「必须同步返回结果，严禁做成异步返回结果」。宿主把 `ScopeHooks#ask(member, sessionId)` 原样接上即可；它返回核心快照或一个拒收值，**不返回 Promise**。
  - 拒收是值：`SCOPE_SNAPSHOT_BUSY`（有结算在途，账本即将改变）与 `SCOPE_UNINITIALIZED`（本进程还没有这个会话的账本）。成员把拒收转成带**同一 `code`** 的 `CoreError` 抛出，`details.refusal` 保留原值；程序因此停下并读到原因，而不是拿着一个没人确认过的额度继续规划。
  - 端口缺席时在**调用**处以 `MISSING_PORT` 拒绝（`details.port === "scope"`），构建 surface 不受影响。

## 4 fission（根 §4.2）

- 准入顺序：`InvalidOrigin` → `AlreadyActive`（仅当该 owner 还有一个**活动**组）→ `ConflictingGroup`（同一 owner 的建组尚未结束）→ `TooFewLanes` → `EmptyLanePrompt`（带 `index`）→ `CapacityExceeded`。任一项不满足都返回 `{ok:false, rejection}`，不创建任何 lane。`converged`/`failed` 是终局而不是活动：owner 在组收敛或失败后可以再次 `this.fission`，旧组记录保留到新组成功替换它为止。
- **建组步骤没有自己的时间上限。** 时间只有一个来源：准入时按 `intent` 字节数一次性定下的请求期限（根 §3.3）。组合层不发明第二只钟；端口不回答就是宿主不回答，组合层如实等待并如实报告，不把自定的超时当成端口的失败。
- 组 id 一经发出就不再复用（`#ids`）；`DuplicateOwnerActive` 在 `AlreadyActive` 之后、建组之前判定，绝不以 `ConflictingGroup` 冒充。
- 通过后依次：取 owner 视图 → 逐条 `createLane` → `persistGroup` → 逐条 `startLane` → `replacePresent`。任何一步失败都调 `abortLanes({groupId, laneIds})`，不登记组，不留部分组。步骤次序照根 §4.2 的 4→6：持久提交、启动、替换；组的内部状态仍是 `started`（全部 lane 启动）→ `replaced`（替换完成）。
- **组在 `startLane` 之前就对 `settle`/`deliver`/`#take` 可见。** 一步式宿主的 lane 就是它的第一回合，可能在 `createLane`/`startLane` 内同步结束；建组中的组放在 `#buildingGroups` 里，投递因此不会丢。成功后移入 `#groups`，回滚时删除。回滚还会把建组前捕获的完成事实去重后放回 `#pending` 队首：下一次建组继承它们，而不是静默地少算条件 B。
- **失败有两种形状，同样算失败**：端口抛出异常，或端口返回 `{ok:false, reason}`（`portRefused`）。后者被包成带 `step` 与 `receipt` 的错误，走同一条回滚。回滚自身再失败时，原错误带上 `abortFailure`，不被回滚错误顶替。
- `ownerView` 必须是字符串或 `null`（空视图合法）；其他类型在创建任何 lane 之前以 `INVALID_ARGUMENT` 失败，`abortLanes` 照样收到空 lane 表。
- **总泳道数 `lane_count` 是组合层的事实，随 lane 一起交给宿主（根 §4.2 第 3 步）。** `group.mjs` 的 `lanesOf(groupId, prompts)` 只调用一次：它按 `0..n−1` 建 lane，并把 `laneCount = prompts.length` 写进**每条** lane。`createLane` 与 `persistGroup` 收到的都是这些 lane，所以同一组的每条 lane 印出同一个数。`laneCount` 绝不是 `index` 或 `index + 1`：那会让同一段身份声明在每条 lane 里自相矛盾。宿主只读 `lane.laneCount`，不数 lane 表、不从下标推。
- 状态：`admitted → started → replaced → ready → converged | failed`。`ready` 只在 `ConvergenceReady` 成立时出现。
- `ConvergenceReady` = 每条 lane 都交出工作 ∧ 每条 lane 都收到全部广播完成。广播对每条 lane 单飞认领，重复投递不生效。交出就是 `deliver(index)` 这一件事；根 §4.2 没有要求宿主再确认合并，组合层不发明这一道确认。
- 只有接管 lane（序号 n−1）可以宣布收敛；`converged` 与 `failed` 一次性，同值重复是幂等保持，异值报 `TERMINAL_ALREADY_SET`。
- 接管 lane 的「交出」由宣布本身完成：它在宣布前可以尚未上报自己的交付，宣布仍是唯一的收束动作；其余每条 lane 的交付与每条广播的认领仍缺一不可。被拒绝的宣布不留下痕迹，宣布失败时该 lane 仍算未交出。
- 进入 `converged` / `failed` 之后不再接受 `deliver`、`broadcast`、`claimBroadcast`（`TERMINAL_ALREADY_SET`）：合并次序由终局写定，迟到的投递不得让它随到达次序变化。
- 合并次序恒为 `0..n−1`（`mergeOrder()` 不接受参数，结果是拓扑，不是调用方给的清单）。后继从当前 lane 顺环取第一个**其他**未交出者（`successorOf`）：已交出的 lane 与调用者自己都被跳过；没有其他未交出者时返回 `null`。越界下标一律 `INVALID_ARGUMENT`。
- 广播完成的键在建组时归一化一次：`key ?? name ?? id`。认领与 `ConvergenceReady` 读同一个键，所以宿主不带 `key`、只带 `name`/`id` 的完成也能被认领。

## 5 review 与接力（根 §2.3、§2.4）

`relay/review.mjs` 不重算台账：台账是 core 的 `ReviewLedger`。组合层只做三件宿主必须做的事，并把结果原样并入回执：

**一个 `ProgramSurface` 只建一个 `ReviewMember`。** 台账先判决、宿主后动手，两条并发的 review 会读到同一份空台账；复用同一个成员的队列，才让「同一 `ReviewMember` 的 review 串行执行」这句话有对象可指。队列空闲且本次 review 不需要宿主副作用时（`recorded`、`nextReview`、`confirm`、`closed`，或台账的同步拒绝），`ReviewMember.review` **直接返回回执**，于是 `const r = review(f)` 与 `await review(f)` 读到同一份字段；只有 `relay`、`handoff`、`askUser` 这三种要调端口或挂起程序的判决返回 Promise。

- `relay`：调 `port.handOver({retire, openSuccessor, barrier})`；只有 `handOverCompleted(done)` 为真才 `ledger.openSuccessor()`。
  该判定要求 `ok === true` **且** `retired === true`、`barrier === true`、`successor` 非空——
  只凭 `ok` 不够：`{ok:true, retired:false, successor:null}` 是半成品，不是接力。
  `{recorded:true}`、`{}`、`null` 同样算失败。失败时回执是
  `{ ok: false, kind: "relay", rejection: "RELAY_UNAVAILABLE", handedOver }`，台账原封不动
  （任期、里程碑、继任标记、台账内容都不变），现任可以继续 review 并在下一次空账时重试接力。
- **一次物理调用只记一次账（§2.4 第 6 步）。** `review` 从 surface 收到本程序的 `name`（即宿主 call id），交给 `ReviewMember.review(findings, source, { callId })`，再交给 `ReviewLedger.review`。重放命中时账本直接回原回执（`replayed: true`），**不执行任何副作用**：不二次接力、不二次启动 Manager、不二次落盘、不二次关评估窗口。同一 callId 换载荷立即 `REPLAY_MISMATCH`。**重放是无条件的**：同一 callId 无论账本后来怎样（别的调用加了义务、接力换了任期、里程碑推进），都只回它当初那份回执，并带 `replayed: true`；回执里的任期与里程碑就是那次调用的，不是对当前账本的描述。绝不在重放时重新判决、重新记账或重新执行副作用。
- **同一 `ReviewMember` 的 review 串行执行。** 台账先判决、宿主后动手，两条并发的 review 会读到同一份空台账：两条都 `relay`，第二条会退休掉第一条刚开启的继任。排队让判决与副作用保持一步。
- `handoff`：调 `port.startManager(draft)`；`draft` 只有一处来源——`draft` 端口在规范路径上读到的已保存底稿。review 不带任何文本（`review(findings, source = null)`，`source` 就是这份字节），没有 note、没有回退、没有替身。端口报告 `ok:false` 或没有回执时，本次 review 是 `{ok:false, kind:"handoff", rejection:"HANDOFF_UNAVAILABLE"}`：根 §2.2 要求「启动 Manager 会话」真的发生，Plan 停在「待执行」，下一次空账 review 重试。
- `askUser`：只在 Plan 的 `S1` 出现（初任首评为空）。调 `port.askOperator(question)`，回执带 `interrupt: true`，本轮回合结束、控制权交还用户；用户的答复在下一轮由 AI 以 `this.unpark(name, answer)` 注入。`Manager` 侧初任空账不是 `askUser`，而是 `confirm`（独立验收，里程碑留在「执行」）：无人值守，不等人。

退休、屏障、继任开启是同一笔记录（根 §2.4 第 3 步），三者不可分开出现：宿主只做完其中一部分也按失败处理，组合层不补上剩下的部分，也不留下半个任期。宿主做不了某件事时，该次 review 失败，不得假装完成。

## 6 端口

| 端口 | 方法 |
|------|------|
| `fork` | `fork(name, brief, role)`：`role` 是组合层从 brief 显式声明解析出的结果（`program/role-brief.mjs`），宿主不得再推导或补默认值 |
| `join` | `join()` |
| `ask` | `ask(question, { program, toolCallId })`：记录问题并返回 `{ ok: true, asked: true, question, program, interrupt: true }`；宿主有同步对话框时另带 `answer`。**任何宿主都不得以「没有通道」拒绝**——挂起与中断本身就是通道 |
| `fission` | `ownerView(owner)`、`buildLane(lane)` 或 `createLane(lane)`、`persistGroup(group)`、`startLane(laneId)`、`replacePresent(group)`、`abortLanes({groupId, laneIds})` |
| `relay` | `handOver(decision)`、`startManager(draft)`、`askOperator(question)` |
| `devops` | `investigate(intent)` |
| `draft` | `read()`：Plan 已保存的底稿字节，缺失时返回空串 |
| `command` | `run(command, {cwd, env, onData})` |
| `scope` | `ask(member)`：同步快照（`negotiate` / `askPreviousScope`），返回核心快照或拒收值，**不是 Promise** |
| `control` | `unpark({ controllerId, name, value, capabilities, deadline })`：`ProgramSurface.unpark` 调它；回执是目标程序恢复后的九字段回执，`value` 为挂起表达式收到的值（`HOST-PORTS.md`「control」） |

`fission` 端口的建组方法是**建组步骤**，由组合层（`Fission`）按顺序调用；宿主侧把它们
接成同一个对象的成员。`createLane(lane)` 收到 `{ index, laneId, prompt, laneCount, groupId, owner, view }`，
其中 `view` 是 owner 最近一次已交付的视图或 `null`，`laneCount` 是本组的总泳道数（每条 lane 相同）。

建组步骤的方法列在 `fission/port.mjs` 的 `FISSION_PORT_METHODS`（`ownerView` 与 `persistGroup`
也在其中，因为组合层按顺序调用它们）。每个步骤可以抛错，也可以返回 `{ok:false, reason}`；两种都
触发整组 `abortLanes`。`abortLanes` 的回执带 `orphaned`（宿主停不掉的 lane），组合层原样交给调用方，
不当作成功也不吞掉。

**Plan 不在投影内：** 根 §5 明说 Plan 不在此投影之内，因此
`systemPromptFor("plan")` 抛 `PLAN_OUTSIDE_PROJECTION`（不拼 `COMMON`，也不借用 `MANAGER`；
返回空串会让"没有提示词"看起来像合法组合）。Plan 的提示词是它自己的角色段
`prompt/plan.mjs`（`PLAN_PROMPT`），按根 §2.2 的四个里程碑、阶段判定铁律、台账规则、
底稿只读规则与工具边界逐条写定，由注册 Plan 的宿主适配器直接使用。
组合仍只有两种：`COMMON + MANAGER`、`COMMON + WORKER`。

`ports.mjs` 的 `PORT_METHODS` 是唯一来源；`requirePorts` 在装配时校验。

**park 回调的形状（唯一约定）：** core 的 `TextMembers` 与成员层用 `park(payload)` 请求挂起；`ProgramSurface` 把它接成 `park: (site) => this.park(site.payload, site.reason)`，即回调收到的是 core 给的站点 `{reason, payload}`，surface 再把 `payload` 与 `reason` 摊平交给 `ProgramSurface.park(payload, reason)`。**不得**写成 `this.park(site)` 或 `park({reason, payload})` 再包一层——那会让回执的 `payload` 恒为 `null`，`where` 歧义诊断（`needle`/`candidates`/`nearest`）随之丢失。回执的 `payload` 字段只放站点内容，不放站点对象本身。

## 6.1 面向大模型的结构化呈现（根 §6.3）

`cue.mjs` 是组合层唯一的 CUE 入口，core 的 `toCue` 是唯一实现：

- `renderReceipt(value)`：工具结果（park 回执、review 回执、`fork`/`join` 回执）——裸根渲染。
- `renderNotice(value)`：注入请求上下文的合成提示（Scope 冷启 notice、拒收详情）——同样的渲染；`null`/`undefined` 回 `null`，绝不让宿主把字符串 `"null"` 当上下文发出去。
- `refusalText(error)`：只对 `CoreError`/`ComposeError` 拼 `code: message` 加 CUE 详情；其他异常只报 message，不把宿主常量冒充成我们的 code。

组合层不发明字段，也不提升不可枚举键：core 的 `withExtra` 挂的 `value`/`result` 在 `toCue` 眼里与 `JSON.stringify` 眼里一样不可见，谁要呈现谁自己先摊平（宿主适配器的 `receiptObject` 就是这件事）。

## 6.2 无人值守出口与 Nudge（根 §2.2）

万象术是无人值守的长程工作。面向用户的顶层代理（`plan`、`manager`）只有两个合法出口，其余任何回合结束都必须被拦截：

| 出口 | 条件 | `evaluateTurnExit` 回答 |
|---|---|---|
| `S1` 的 `ask` | `role === "plan"` 且台账为 `plan`、里程碑 `S1`，且本回合确实调用了 `ask`（`askedInTurn`） | `{ stopAllowed: true, reason: "ask-s1", nudge: null }` |
| 验收上一任合格 | `Plan`：里程碑为「待执行」，**且判它的任期不是初任**（`tenure >= 1`），且已提交 `review([])` 交接（`handedOff`）；`Manager`：里程碑为 `done` | `{ stopAllowed: true, reason: "verified-completion", nudge: null }` |
| **操作员中断** | `interrupted` 为真：宿主记录的是「人按了停」（见下） | `{ stopAllowed: true, reason: "operator-interrupt", nudge: null }` |
| 其余一切 | 上述三者都不成立 | `{ stopAllowed: false, reason: "unattended-nudge", nudge }` |

- **人按停不是「代理自己想停」。** Nudge 只回答后者；对前者说「系统是无人值守的常授权模式，禁止等待用户决策」，等于推翻常授权口令唯一指向的那个人。三个宿主的说法各不相同，统一由 `operatorInterruptOf` 读：omp 是被中断那条 assistant 消息上的 `stopReason: "aborted"` + `errorMessage: "Interrupted by user"`（`agent-session.ts:11240` 的 `USER_INTERRUPT_LABEL` 分支；只认 `aborted` 不够——压缩、会话切换也是 `aborted`）；OpenCode 是 `session.execution.interrupted` 事件类型（`spec/turn-nudge.mjs` 的 `handleTurnEnd` 也据此直接拒绝判定）；Cline 是 run 的 `status` 为 cancelled/aborted/interrupted，或 `afterModel` 读到的 aborted 消息。**实测 2026-11-01（omp 18.14.2，会话 `01a1958e`）**：操作员在 05:22:27.950Z 中断首回合，adapter 在 05:22:27.974Z 就投递了 nudge，24 ms。

两个豁免不是出口：`hasInFlightToolCalls === true` 表示回合还没停（`in-flight`）；角色不是 `plan`/`manager`（worker 回合结束就是它的汇报，`not-user-facing`）。

- `buildNudgeMessage({ role, ledger })` 是纯函数：输出身份、任期、里程碑、继任标记，**当前台账的全部 `{ key, value }`**（台账为空时明说「账本当前为空：本阶段还没有通过 `review` 登记任何义务，也尚未完成继任验收、阶段晋级或交接」），并以固定口令 `STANDING_ORDER` 结尾。CUE 渲染（§6.3），绝不裸 JSON。
- **`Plan` 的验收判据是 `tenure`，不是 `successor`。** 根 §2.4 第 5 步说晋级就发生在继任者的第一次 `review`：那次评审正是清掉 `successor` 标记的那一次（core 只在首次评判之前保留它），所以「晋级成功」与「仍是 successor」不可能同时成立。真正成立的事实是「判它的任期不是初任」：初任在「待执行」之下只能接力，接力把里程碑原样交给下一任，因此「待执行」只可能由第 1 任及以后的任期判入。用 `successor === true` 当判据会让 `Plan` 的出口永远不可达。
- `Manager` 的 `execute` **不是**出口，即使台账为空：空账 `execute` review 触发接力，必须由继任者自己的 `review` 记录这次验收。
- **走到出口的回合必须清掉未送达的 nudge。** 上一回合被拒时记下的 nudge 会由 `requestNotice` 在下一个请求边界刷新；如果工作是在那个请求开始之后才完成的（提交交接、结案评审），记录的 nudge 就变成过期事实。`guard.mjs#turnExit` 在判定 `stopAllowed: true` 时调用 `session.clearNudge()`：合法停止之后不会再有下一个请求来清它。
- `nudge.test.mjs` 用 fast-check 证明五条定理（第 4、5 条覆盖中断：任意一种宿主写法的操作员中断都不产生 nudge；任意自然结束、且台账在出口之下，一定产生带全部未完项的 nudge）：`ask` 当且仅当在 `Plan` 的 `S1` 挂起并中断；未到出口的任意顶层回合结束都 `stopAllowed: false` 且 nudge 含全部未完项与常授权口令；到达出口即 `stopAllowed: true` 且 `nudge === null`。

## 7 测试

- `node --test "src/compose/**/*.test.mjs"`。
- 用 fast-check 覆盖 fission 的任意投递次序：不论到达顺序，`ConvergenceReady` 与合并次序都由拓扑决定。
- 用 fast-check 与例证覆盖同步快照查询：两个成员在 `js-manager`/`js-worker` 上存在、在 `js-review`/`plan` 上不存在；调用返回的是快照而不是 Promise，且程序状态不变；核心拒收（`SCOPE_SNAPSHOT_BUSY`）以同一 `code` 抛出；无 `scope` 端口时是 `MISSING_PORT` 而不是 `TypeError`（`program/surface.test.mjs`）。
- 用 fast-check 覆盖任意 lane 数的建组：每条 lane 的 `laneCount` 都等于组的总数，且每个宿主印出的身份声明里都是这个数（`orchestration.test.mjs`）。断言读的是宿主真收到的 lane 与真写出的起始文本，不是回显的 mock。
- 测试用 `node:test` + `fast-check`，不引入其他依赖；每个模块的测试与实现同目录。
- **测试自己发起的操作必须自己结清，并且按 id 结清。** 测试若替 SDK 先 `beginOperation()`，就把返回的 id 存下来，在 `try/finally` 里 `endOperation(id)`：断言失败时计数不会留给后面的用例，`settle` 与排空也不会永远等一个没人结清的操作。`endOperation` 没有无参形式——开放操作按任意顺序完成，"最新一个"只是猜测；猜错就会结清别人的操作，把先前操作永远留在开放集合里，`npm test` 随之挂到超时。
- **判死锁用 `unref()` 的计时器。** `call` 若真的排空失败，它等的是一个永远不会被兑现的 drain waiter，`Promise.race` 的超时分支就是唯一出路；计时器必须 `unref()`，否则断言先抛错时进程仍被它扣住。
