# 万象术 Herdchestra 用户指南

> 本说明书阐明万象术（Herdchestra）的完整用户侧行为与工程语义，是产品与代码的最高依据。任何时候 AI 都有权利和义务让各包局部规格说明（`AGENTS.md`）和源码追上本文件的最新状态，无需请示，直接实施。
> 本说明书未经允许不得擅自修改。

---

## 0 序言

万象术 Herdchestra：让一万只大象一起跳舞。
Orchestration for agents that don't like being orchestrated.

写程序这件事，长久以来是一个人的手艺。一个工程师，一台电脑，一条思路，从需求走到代码，饿了吃饭，困了睡觉。后来 AI 来了，我们忽然有了一群不会疲倦的程序员。它们读代码，查资料，写功能，补测试，做代码审查；来自不同的厂商，用着不同的模型，脾气也各不相同——有的谨慎，有的激进；有的写起代码来像钟表匠，有的像推土机。而且，每一个都还在变聪明。

于是有了一个新问题：如果一个智能体能写程序，一百个、一千个、一万个一起写，会怎么样？

这件事，有点像让一万只大象跳舞。

大象并不蠢。恰恰相反，它们聪明、有力、记性惊人，问题只在于太重。一只大象往左挪一步，那只是一步；一万只大象同时往左挪一步，地都要震。工程里也是如此：这边刚改完一个文件，那边的智能体已经把它覆盖了；这个还在查问题，那个已经按自己的理解动了手；有的以为任务做完了，有的还欠着测试；有的掉线，有的重试，有的忘了自己干过什么。要是再闯进来一个带着别家模型习惯的，场面就更热闹了。

对付这样的场面，喊是喊不动的，给每头象拴一根更长的绳子也没用。现在不少智能体编程的做法，说到底还是一个指挥站在台中央，拼命挥棒子，指望几十头越来越有主见的象恰好踩在同一个小节上。两三头的时候，这法子看着不错；十头，开始手忙脚乱；一万头，指挥棒就成了道具。

人们爱说，站在风口上，猪也能飞。风确实起来了：模型越来越强，价格越来越低，速度越来越快，智能体越来越多。可是风托得起一只猪，托不起一万头没有队形的象。到了那个规模，能不能飞，不再取决于哪头象最聪明，而取决于地上有没有路，有没有红绿灯，有没有账本、分工和规矩；有人跌倒了，队伍还走不走；有人走散了，还能不能找回来；各支队伍能不能踩着自己的步子，在同一个世界里把同一件事做完。

这就是我们做万象术的原因。“万象”，既是一万头象，也是世间万象。我们不想去造世界上最聪明的那头象，也不想把所有的象驯成同一个姿势。我们想做的事更笨一些，也更基础一些：让不同的智能体各尽其长，又能共同成事。今天最好的模型能来，明天更好的也能来。

它们性格不同，能力不同，上下文不同，干活的法子也不同。但只要进了同一个工程，就知道自己是谁，正在做什么，能做什么、不能做什么；知道哪些事已经发生，哪些事还没有完成；知道什么时候可以并行，什么时候必须等待；知道怎样把手里的活交给另一个智能体——也知道，万一哪个智能体消失了，世界不该跟着失忆。

这里没有永远正确的总指挥，也没有一根拴住所有象的绳子。有的只是一套基础设施，让协作这件事本身变得可靠。

于是，一头象可以跑，一百头象可以协作，一万头象可以跳舞。而且跳的不是同一种舞：爵士、芭蕾、街舞，还有没人见过的新舞步——各有各的节奏，各有各的长处，谁也不踩谁的脚。

这就是万象术：不是驯服智能的法术，而是驾驭智能规模的技术。我们相信，AI 编程的下一个时代，不属于拥有最强智能体的人，而属于能让无数不同的智能体自由加入、可靠协作、把真正复杂的工作做完的人——就像今天的进程、服务和机器那样。当智能成为充沛的资源，协同就是新的稀缺品。万象术想做的，就是这一层。

让一万头大象一起跳舞。更快，更好，更省。
万象皆可用，众智自成事。

---

## 1 万象术是什么，以及它怎样接入你的工具

当你把一项复杂的编程任务交给 AI 时，单个智能体很容易在漫长的上下文里迷失方向，而多个智能体又容易因为同时改文件、同时跑命令而互相踩踏。

万象术（Herdchestra）是一套多智能体协同基础设施。它以旁路方式接入 OpenCode v2、Cline Desktop 和 Oh My Pi，接管宿主的工具层与消息投影层，把宿主原本的单兵作业模式直接替换为分工严密的多智能体团队模式（宿主中不再保留普通单兵模式）。

为了让你随时升级宿主而不受阻碍，万象术遵守四条工程边界：

1. 零侵入宿主源码：万象术不修改宿主的一行源代码，始终与宿主最新版兼容。所有能力都通过宿主插件、运行时调用劫持或装饰器模式实现；不因其中一两种手段受限而阻塞整体实现。
2. 三层解耦架构：代码由若干彼此独立、互不依赖的核心支持库（`src/core/*`）、黏合支持库的组合层（`src/compose/*`），以及面向各宿主的适配器（`src/host-opencode`、`src/host-cline`、`src/host-omp`）组成。上游宿主接口变化时，只需更新对应适配器。
3. 极简持久化：万象术不依赖宿主的内部历史格式。需要跨越进程生命周期保存我们自己的投递、语言、账本与上下文边界事实时，轻量地追加写入工作区下的 `.Herdchestra/{session标识符}.jsonl`。
4. 外部仓库参照约定：开发需要参考其他仓库代码时，先在 `../` 寻找；若不存在则 `clone` 至 `../`。

---

## 2 一项任务是怎样完成的：角色、阶段与接力

在万象术里，用户提出需求后，工作按“先规划底稿、再组织施工、每一步由后任独立验收”的秩序推进。

### 2.1 四种身份与并发安全

工程里最常见的混乱，是几个智能体同时运行测试、抢占端口、修改环境变量，或者主代理一边指挥一边自己下场改代码而忘了全局。万象术将参与工作的智能体严格划分为四种身份：

| 身份 | 层级 / 模式 | 职责与边界 |
|------|-------------|------------|
| `Plan` | 顶层代理（不由 `fork` 创建） | 规划者。负责把用户需求打磨成一份结构严密的“底稿”（开发方案）。它只读写底稿这一个文件，不直接读仓库里的其他文件，也不改源码；需要了解仓库现状时，通过 `this.devops(intent)` 委托单路 `DevOps` 调查。 |
| `Manager` | 顶层代理（`mode: "primary"`） | 导演。接手底稿后，把工作拆成独立的工作流，调遣尽可能多的 worker 并行推进。`Manager` 自己绝不编辑文件、不跑命令、不搜代码、不构建；常规工具面里连文件读取都没有，也没有替子代理做代码审查的义务。 |
| `Coder` | 子代理（`mode: "subagent"`） | 工程师（许多名）。负责本地事实调查与源码修改。它的工具面（`js-worker`）里虽然保有执行命令的 `this.call`，但启动它的任务描述（`brief`）中必须明确禁止使用 bash 工具——这是一条提示词约束。 |
| `DevOps` | 子代理（`mode: "subagent"`） | 运维官（同一时刻恰好一名）。唯一持有真实命令执行权的 worker，负责 bash、构建、测试、进程管理与 git 操作。 |

为什么这样分工就能安全并发？
启动任何子代理时，都必须明确它是 `Coder` 还是 `DevOps`。因为所有 `Coder` 只做文件读写与静态推理，不跑命令，多个 `Coder` 同时工作时的冲突面就收窄到了纯文件写入（只要任务切分得当，各改各的文件即可并行）；而真实命令执行权收归到唯一的 `DevOps` 手里，从根本上消除了多个进程并发修改运行时状态（端口、进程、构建产物、环境变量）的竞态。

### 2.2 规划阶段（Plan）：从需求到底稿

当一项新规划任务启动时，顶层代理以 `Plan` 身份工作（`Plan` 不在 §5 的系统提示词投影之内）。

#### 四个里程碑（S1 → S2 → S3 → 待执行）
`Plan` 按顺序走过三个打磨里程碑，最后进入“待执行”：
- S1（需求分析）：使用 Ronald A. Howard、Raymond Thomas Dalio、Jeff Bezos 的方法做需求分析，做到无可再改。
- S2（实现骨架）：使用 Polya、Schoenfeld 的方法探索出最优实现的骨架和要点，做到无可再改。
- S3（并行开发方法论）：使用 Knuth 风格的伪代码，以“最大化同时参与开发的人数”和“最小化开发墙钟耗时”为原则，给出开发方法论，做到无可再改。
- 待执行：底稿定型，准备移交 `Manager` 执行。

阶段判定的铁律：每个阶段只判定“离下一阶段能够开始的差距”——S1 判定离 S2 能开始的差距，S2 判定离 S3 能开始的差距，S3 判定离可交付执行者的差距。绝不能把下一阶段才该做的工作算作本阶段的缺口。

#### 底稿与 Plan 的工具边界
- 唯一持久产物：`Plan` 的唯一产物是底稿，其路径由任务固定派生，系统会告知确切路径。`Plan` 只读写这一个文件（这是职责边界，不是运行时路径门禁）。
- 首次评审前只读：每一任 `Plan` 在提交本任的第一次 `review` 之前，底稿对它是只读的（调用 `this.write` 会被拒绝）；第一次 `review` 之后才解锁写入。接力换人开启新任期后，底稿重新变为只读，迫使新任先读完评判，再动笔修改。
- 极简编程面：`Plan` 只有五个能力：`this.cat`、`this.write`、`this.devops(intent)`、`this.ask(question)`、`review(findings)`。它没有 `glob`、`grep`、`exec`，也不得访问仓库其他文件：
  - 需要调查仓库事实时，一律调用 `this.devops(intent)` 同步取得；同一任务同时至多有一个未决的 `DevOps`，其完整调查记录原样返回，系统不做二次摘要。
  - 需要向用户提问时，调用 `this.ask(question)`；程序随即挂起，本次 attempt 中断；待用户答复以真实 steer 到来后，同一任务从挂起点继续，不重开任期、也不制造评审。

#### Plan 向 Manager 的交接
当 `Plan` 推进到「待执行」阶段后，不再接受非空台账（提交非空台账会被拒收）。此时：
- 提交空账 `review([])` 是启动 `Manager` 的唯一方式。
- 调用 `review([])` 时，系统从规范路径读取已保存的完整底稿；若底稿缺失或为空，系统在创建 `Manager` 会话之前直接拒绝。
- 每次新的空账 `review([])` 都会创建一个独立的 `Manager` 会话，任期从 `Manager` 的「执行」里程碑开始；同一次工具调用重放（同一 `callId`）只启动一个，不会重复创建。
- `Manager` 收到的是底稿字节原文，不是里程碑名字，也不是 `Plan` 的对话历史。
- 启动 `Manager` 不会结束规划任务，也不会改动底稿。`Plan` 与 `Manager` 各自持有独立的 `review` 台账，接力规则各自适用。`Manager` 无权修改底稿，也不会退回 `Plan` 的里程碑；若需要重新规划，必须由人类操作员发送真实消息触发新的规划任务。

### 2.3 执行阶段（Manager）：导演、派工与收尾

`Manager` 拿到底稿后，以导演身份组织施工。它的里程碑依次为：「执行」 → 「收尾」 → 「已完成」。

#### 调度纪律
- 任何时候保持恰好 1 名与道路绑定的 `DevOps` 和尽可能多的 `Coder`（对可拆解的大任务，通常同时调遣 10 名以上 worker；其余按实际需要派出）。
- 缺位立刻补位。只要还有独立工作可以派出，就优先调用 `fork`；只有手头再无任务可 `fork`、必须等待前序结果时，才调用 `join`。
- 当义务台账非空时，立即用 `fork` 派出 `Coder`，或用同名 `fork` 把新目标追加（steer）给已绑定的 `DevOps` 去解除台账上的义务。子代理返回只是汇报，不代表义务自动解除；只有当返回的事实真正解决了某项问题，`Manager` 才在下一次 `review` 中用 `null` 删掉该键。

#### 评估窗口与读取例外：`js-review`
`Manager` 默认连文件读取工具都没有，也不亲自调查或修改工作树。但在每一轮刚开始、首次独立评估前任工作并提交本轮第一次 `review` 之前，`Manager` 需要核对工作区事实：
- 在这一评估窗口内，系统向 `Manager` 临时开放一个独立工具 `js-review`（它是独立工具，不是 `js-manager` 的成员，也不设 `reviewOnly` 命名空间）。
- `js-review` 是严格只读的：只提供 `this.cat`、`this.stat`、`this.ls` 以及处理已有文本的 `this.where`、`this.whereAll`（遵守与 `js-worker` 相同的路径边界、UTF-8 与失败码语义），不提供任何写、删、移、执行或委派能力。
- 评估期间，`Manager` 不调 `DevOps`、不跑命令、不改源码。
- 一经受理即收回：只要本轮的 `review` 被系统受理，`js-review` 对本次评估立即关闭。同一轮内后续若还需要了解仓库事实，必须用 `fork` 派出只读的 `Coder` 去查，绝不能自己看。
- 注意：`js-review` 只是评估前任工作成果是否达标的事实通道，`Manager` 没有审查子代理代码细节的义务。

#### 收尾与结案
- 在「执行」阶段清空台账会触发接力，由继任者在「执行」阶段独立复核；继任者第一次 `review` 以空台账确认后，工作推进到「收尾」。
- 在「收尾」阶段清空台账，工作直接结案为「已完成」，不再做二次确认——但有一个硬前提：收尾时所有真实执行资源（在途子代理、后台进程等）必须已经收束；若有资源未收束，提交空账 `review` 也不会结案。
- 一旦进入「已完成」，只能由人类操作员发送真实消息重开一次；任何代理消息都不得重开。

### 2.4 义务台账与换任接力（`review`）

为什么智能体自己做完事、自己说“我做好了”往往不可信？因为它在刚才的上下文里已经对自己的假设产生了路径依赖。万象术用 `review(findings)`（注意：这是直接调用的顶层函数名，不是 `this.review` 成员访问）把“列待办”和“换人独立验收”合二为一。

#### 台账增删规则
`review(findings)` 接收一份按键管理的义务台账 `findings`（形状为 `{ key, value }` 数组）：
- 新增或覆盖：`value` 为字符串时，新增或覆盖该 `key` 对应的待办义务。
- 解除义务：`value` 为 `null` 时，从台账中删除该 `key`。
- 保持原样：传入空数组 `[]` 不会清空既有台账，只是原样保持当前台账。
- 调用后返回回执（表示已提交，或可提交下一次评审——这不是需要用 `join` 等待的异步结论）。

#### 接力（Relay）与晋级（Promotion）的完整时序
1. 初任首评为空（向用户请示）：如果第一任（初任）的第一次 `review` 时台账就是空的，这不算评审，也不触发接力。系统会代该任向用户提问 `「我需要干什么？」` 并等待真实答复；答复到来后，由同一任继续工作。
2. 台账非空（正常工作期）：现任提交 `review` 后，如果计算出的台账非空，则处于正常工作期，由现任继续干活，补齐本里程碑的义务（台账非空时绝不允许边修边跨入下一里程碑）。
3. 台账由非空变空（触发接力）：当现任把台账里的所有键都用 `null` 删空时，触发接力。系统在同一笔原子持久记录里同时完成三件事（三者不可分割，进程中断也不会留下半个任期）：
   - 将现任标记为退休（退休者不能因旧请求重放而再次动手）；
   - 写入一道上下文屏障；
   - 以 `successor`（继任者）身份开启下一任，并将退休者的里程碑字段原样复制给继任者（接力只换人，不换里程碑）。
4. 屏障隔离与遗产继承：上下文屏障把前任的对话（包括触发接力的那次 `review` 及其之前的所有对话）从继任者的视野里彻底切掉。继任者只看到：用户原始输入、底稿（`Plan` 侧）或工作区事实（`Manager` 侧），以及系统给出的继任提示。同时，前任已接收的工作、进程归属、未领取的结果，以及道路绑定的 `DevOps` 原封不动地保留给继任者（`DevOps` 跨接力不变，不得改绑）。
5. 继任者首评决断（确认晋级或打回重修）：
   - 如果继任者的第一次 `review` 提交空台账（`[]`），说明它在没有前任心理包袱的情况下独立确认了前任成果——工作立即推进到下一里程碑，且不新建任期，由该继任者直接开启下一里程碑的工作。
   - 如果继任者的第一次 `review` 发现了问题、提交了非空台账，则不推进里程碑，直接进入本里程碑的正常工作期去修补问题；等它修完再次清空台账时，依然是触发下一次接力，而不是直接晋级。
6. 幂等与重放：同一物理调用（同一 `callId`）重放时直接返回原回执；若同一 `callId` 换用不同载荷，立即报错拒绝。

---

## 3 智能体手里的工具箱：`js-manager`、`js-worker` 与 `js-review`

传统智能体框架给模型提供 `read`、`grep`、`glob`、`write`、`edit` 等零散工具：改十处同类代码要来回调用十次工具，中间还常常因为行号漂移或空白符截断而改坏文件。

万象术彻底废除了这些零散工具，代之以 JavaScript 程序执行环境（`js-manager`、`js-worker` 和评估期专用的 `js-review`）。智能体每次提交一段继承自 `JsProgram` 的类代码，在一次调用里就能完成读取、精确计算偏移、分支判断与原子落盘。为了让模型对工具行为一清二楚，`js-` 系列工具的工具描述（Tool Description）直接把实现这些方法的源代码原封不动地展示给 LLM。

### 3.1 各身份的能力面板对照

凡是通过 `this.` 调用的方法一律写作 `this.xxx`，顶层原语直接写函数名。除下表列出的能力外，不存在任何隐式成员，也不设跨调用事务或二次校验：

| 调用形式 | 语义说明 | `Plan` | `js-manager` (`Manager`) | `js-review` (`Manager` 评估期) | `js-worker` (`Coder` / `DevOps`) |
|----------|----------|:------:|:------------------------:|:------------------------------:|:--------------------------------:|
| `this.cat(path)` | 读取文件完整 UTF-8 文本 | 仅底稿 | - | 有 | 有 |
| `this.stat(path)` | 返回 `{ exists, size, mtime, isDir }`，文件缺失不报错（`exists: false`） | - | - | 有 | 有 |
| `this.ls(path)` | 非递归列目录，目录条目末尾带 `"/"` | - | - | 有 | 有 |
| `this.write(path, content)` | 原子覆盖写文件 | 仅底稿（首评后解锁） | - | - | 有 |
| `this.mkdir` / `this.rm` / `this.mv` | POSIX 级别目录创建、删除、移动/重命名 | - | - | - | 有 |
| `this.where(content, needle)` | 精确 UTF-16 切片定位 `[begin, end]`；0 匹配或多匹配时自动挂起（`this.park`） | - | - | 有 | 有 |
| `this.whereAll(content, needle)` | 返回所有匹配的 `[begin, end]` 数组（不挂起） | - | - | 有 | 有 |
| `this.call(command)` | 执行 bash 命令（`Coder` 在任务提示词中被禁调，但工具面仍保留） | - | - | - | 有 |
| `this.devops(intent)` | `Plan` 专用的同步仓库调查通道（同任务至多 1 个未决，原样返回全记录） | 有 | - | - | - |
| `this.fission(prompts)` | 将当前 worker 身份同时展开为多条并行 lane | - | - | - | 有 |
| `this.park(payload)` | 显式将当前程序挂起，立即返回挂起回执 | - | 有 | 有 | 有 |
| `this.unpark(name, value?)` | 在另一命名程序中恢复已挂起的程序，并向挂起点注入 `value` | - | 有 | 有 | 有 |
| `this.ask(question)` | 向人类用户提问并挂起等待答复（唯一用户提问通道，子代理无此通道） | 有 | 有 | - | - |
| `fork(name, brief)` | 启动新 worker，或向同名在途 worker 投递新指令（steer） | - | 有 | - | 有（若允许再分派） |
| `join()` | 等待第一个归来的子代理或结束的进程，返回其记录 | - | 有 | - | 有 |
| `review(findings)` | 提交义务台账评审，台账清空时触发接力或推进阶段 | 有 | 有 | - | - |

### 3.2 路径语义、原子写入与精确切片

#### 路径语义
- 空路径（`""`）解析为当前会话根（工作区根目录）。
- 绝对路径按原样使用。
- 文件访问能否成功完全由操作系统权限决定，万象术不在工作区根目录设人为的边界拦截。

#### `this.write(path, content)` 的原子覆盖写
“原子覆盖写”是硬性工程要求，不是修辞：
1. 写入时，先在目标文件同一目录下以排他方式创建临时文件（同目录保证位于同一文件系统，随后的 `rename` 才能由内核保证原子替换）。
2. 将 `content` 完整写入临时文件并 `flush` 落盘后，执行 `rename` 覆盖目标路径（目标不存在则新建，已存在则替换）。
3. 任何读者在任何时刻读取该路径，只会看到完整的旧内容或完整的新内容，绝不会读到写了一半的半截文件。
4. 若写入或替换过程中失败，临时文件会被清理删除，目标文件保持原样；若进程在 `rename` 前崩溃，最多在同目录留下一个临时文件，目标文件依然完好无损。
5. 边界说明：这只是单次 `this.write` 调用内的原子替换保证，不是多文件事务——多次 `this.write` 之间没有回滚；多个程序对同一路径并发调用 `this.write` 时不加锁也不校验，最后一次 `rename` 获胜。

#### `this.where` 与 `this.whereAll` 的纯字符精确切片
传统编辑工具常因偷偷 `trim` 空白符或忽略缩进而改错位置。`this.where(content, needle)` 和 `this.whereAll(content, needle)` 对待字符完全透明：纯 UTF-16 码元（code unit）精确匹配，绝不歧视任何空白符、换行符或做隐式 `trim`。`this.cat` 读出的文本可直接作为 `content` 传入，返回的 `[begin, end]` 可直接用于 `content.slice(begin, end)`。

- `this.whereAll(content, needle)`：直接返回所有匹配区间的数组 `[[begin1, end1], [begin2, end2], ...]`，找不到则返回 `[]`。
- `this.where(content, needle)`：要求 `needle` 在 `content` 中唯一命中，返回那唯一的 `[begin, end]`。如果出现 0 个匹配或多个匹配（歧义），`this.where` 不会抛异常中断整个差事，而是直接让当前程序转入 `parked`（挂起）阶段，并在回执的 `payload` 中附上诊断信息，供后续通过 `this.unpark(name, [begin, end])` 直接注入正确的区间继续执行：
  - 零匹配时的 `payload`：形如 `{ needle, candidates: [], nearest: { span, text, distance } }`。其中 `nearest` 只在零匹配时出现，系统扫描 `content` 的前 2000 个非空行，每行只与 `needle` 的前 200 行 / 200 个字符计算编辑距离，取出编辑距离最近的那一行及其 `{ span, text, distance }`，帮智能体一眼看清是不是拼错了一个变量名或差了一个分号。
  - 多匹配时的 `payload`：`candidates` 最多列出 8 条候选，每条形如 `{ span, context }`，其中 `context` 是匹配位置前后各 80 个 UTF-16 单位的上下文片段，方便智能体从中挑出正确的 `[begin, end]`。

### 3.3 程序的生命周期：`this.park`、`this.unpark`、`this.call` 与期限控制

每一个 `JsProgram` 实例都有自己的名字（`name`）和清晰的运行阶段。当程序遇到定位歧义、长耗时命令或需要向用户提问时，它可以随时挂起并交还控制权，既不会把整个回合卡死，也不会丢失已经算出一半的局部变量。

#### 程序各阶段的自然流转
一段程序从启动到结束，会经历以下运行阶段：
- `running`（运行中）：程序正在顺序推进 JavaScript 表达式。在运行过程中，可能会发生四类流转：
  - 发起常规文件操作或启动外部命令：
    - 若调用的是 `this.call(command)`，程序先进入 `draining`（排空过渡期，不再推进新表达式，专心等待本程序此前已发起的 SDK 操作全部结清）；
    - 随后进入 `waiting`（等待期，等待当前 SDK 操作或 `this.call` 命令执行完毕）；
    - 操作或命令完成后，自动回到 `running` 继续往下走。
  - 遇到需要外部介入的时刻（调用 `this.park`、`this.where` 命中数不为 1、调用 `this.ask`，或本次请求计时到期）：
    - 无论当时处于 `running` 还是 `waiting`，程序立即转入 `parked`（已挂起），并向模型返回一份挂起回执；
    - 待后续另一个程序调用 `this.unpark(name, value?)` 恢复它（或用户回答了 `this.ask`），程序从刚才停下的那一行表达式重新回到 `running`。
  - `run()` 方法执行完毕或抛出未捕获异常：
    - 程序先进入 `settling`（收尾过渡期，不再接受新的表达式推进）；
    - 收尾完毕后，正常返回则定格为 `completed`（已完成），异常终止则定格为 `failed`（已失败）。

#### 挂起（`this.park`）的三条触发路径与回执
无论是由 (1) 显式调用 `this.park(payload)`、(2) `this.where` 遇到零匹配或多匹配，还是 (3) 调用 `this.ask(question)` 触发，走的都是同一条内部通道：当前表达式等待的 Promise 被替换为内部信号 `__programPark`，程序立即转入 `parked`，本次工具调用不再阻塞等待，而是立刻向模型返回一份结构化回执：

```js
{
  name,               // 本程序的名字
  state: "parked",
  reason,             // "explicit"（对应显式 this.park 与 this.where 歧义）或提问类原因
  payload,            // this.park 传入的载荷，或 this.where 的 candidates/nearest 诊断信息
  source,             // 带行号的本程序完整源码
  pendingOperations,  // 尚未结清的后台操作列表
  command,            // 当前正在跑的命令视图（stdout/stderr 增量、字节数、durationMs、退出状态）；无命令时为 null
  delta,              // 自上次回执以来的新增输出
  remainingWaitMs     // 本次请求剩余的等待毫秒数
}
```

挂起绝不冻结外部世界：程序转入 `parked` 时，它之前已经启动的命令继续在后台运行，标准输出与错误输出继续被收集；同时，本程序在当前工具请求上的计时期限被取消，等将来被恢复时，按控制请求的新期限继续计时，不会沿用或续期旧期限。

#### 恢复（`this.unpark`）与防呆核验
当模型看清了挂起原因（比如从 `this.where` 的 `candidates` 里选定了 `[812, 853]`），它只需在另一个命名程序里调用 `await this.unpark(name, value?)`。宿主在恢复目标程序前，会依次做四道核验：
1. 观察核验（`STALE_CONTROL`）：控制者在本请求里必须确实观察过目标程序的最新境况；不能盲目恢复一个自己没见过其挂起回执的目标。
2. 非自指核验（`INVALID_ARGUMENT`）：目标名字 `name` 不能是控制者程序自己。
3. 存在性核验（`PROGRAM_UNKNOWN`）：目标名字对应的程序必须存在。
4. 能力集核验（`CAPABILITY_DENIED`）：控制者必须持有目标程序所需的能力集（防越权恢复）。

四道核验全部通过后，宿主把目标程序从 `parked` 切回 `running`：
- 对于显式 `this.park` 和 `this.where` 歧义挂起点，`this.unpark` 传入的 `value`（若省略 `value` 则等价于注入 `null`）会原样作为挂起表达式的返回值交给原程序（宿主不对注入的区间做二次校验，由程序自己保证切片有效）。
- 对于 `this.call` 命令等待和 `this.ask` 提问挂起点，它们恢复时各自拿回命令真实结果或用户真实答复，不受 `this.unpark` 传入的 `value` 影响。
- 单次恢复保证：同一个挂起点只能被恢复一次。如果控制者之后再次对同一名字调用 `this.unpark`，而目标已经运行结束或已不在该挂起点，系统会返回 `STALE_CONTROL` 或目标的当前回执，绝不会隐式再恢复一次。

#### 名字占用与重放幂等
- 名字占用（`NAME_TAKEN`）：当一个名字对应的程序仍处于活动期（`running`、`waiting` 或 `parked`）时，若有新程序试图占用同一个名字启动，系统拒绝并报 `NAME_TAKEN`。只有当原程序进入终局（`completed` 或 `failed`）后，该名字才能被新运行复用（旧回执仍按原调用重放）。
- 物理调用重放（`CALL_REPLAY_MISMATCH`）：同一物理工具调用（同一 `callId`）带着相同载荷再次到达时，直接重放原回执；若同一 `callId` 换成了不同的载荷，系统立即报 `CALL_REPLAY_MISMATCH`。

#### `this.call(command)` 的前置排空与请求期限
- 自动排空（`draining`）：许多时候模型写代码会漏掉某个文件操作前的 `await`。为了防止在文件还没写完时就跑起测试命令，`this.call(command)` 启动 shell 命令前，会先把程序切入 `draining`，强制等本程序此前已经发起、尚未结清的所有 SDK 操作全部完成，然后才切入 `waiting` 启动命令。命令结束后程序回到 `running`，`this.call` 返回包含 `exitCode`、`stdout`、`stderr` 等字段的命令回执。
- 按意图字节数核定期限：每次工具请求在准入时，按请求参数 `intent` 的 UTF-8 字节数 × 50ms 一次性核定整次请求的期限。程序内的所有操作与恢复共用这段剩余时间；一旦到期，控制者与仍在活动的目标程序一同转入 `parked`，随后只恢复控制者。

### 3.4 八个标准用法样例

以下八个例子均只使用 §3.1 表中定义的成员。共同前提是：`this.cat` 读出的文本可以直接作为 `content` 传给 `this.where` / `this.whereAll`。

例 1，同一字面量多处出现时逐处改写：

```js
class Js extends JsProgram {
  async run() {
    const path = "src/retry.mjs";
    const source = await this.cat(path);
    const spans = this.whereAll(source, "RetryPolicy");
    if (spans.length === 0) {
      throw new Error(`No RetryPolicy found in ${path}`);
    }

    // 从后往前替换，使前序匹配的 UTF-16 偏移不受后续改写影响。
    let updated = source;
    for (let i = spans.length - 1; i >= 0; i -= 1) {
      const [begin, end] = spans[i];
      updated = updated.slice(0, begin) + "RetryPolicyV2" + updated.slice(end);
    }

    await this.write(path, updated);
    return { replaced: spans.length };
  }
}
```
要点：`this.whereAll` 给出全部 `[begin, end]`，倒序替换让前面的偏移保持有效；同一路径在一次程序里只调用一次 `this.write`。

例 2，唯一锚点确认后做定点插入：

```js
class Js extends JsProgram {
  async run() {
    const path = "docs/API.md";
    const source = await this.cat(path);
    // 锚点必须唯一命中；若零匹配或多匹配，程序在此自动挂起。
    const [begin, end] = await this.where(source, "## 安装\n");
    const section = "## 快速开始\n\n见下方安装一节。\n\n";
    const updated = source.slice(0, begin) + section + source.slice(begin);

    await this.write(path, updated);
    return { insertedAt: begin, anchorLength: end - begin };
  }
}
```
要点：`needle` 带上换行与标题层级，多出来的特征正是让切片区间唯一的东西；若出现零个或多个匹配，`this.where` 不会返回，程序直接转入挂起。

例 3，零匹配诊断与回填（`this.where` 挂起 → 读 `nearest` → 在新程序里 `this.unpark` 注入区间）：

```js
class Js extends JsProgram {
  async run() {
    const path = "src/config.mjs";
    const source = await this.cat(path);
    // 若 needle 与实际文本有细微出入，本次运行在此挂起，回执 payload 形如：
    // { needle, candidates: [], nearest: { span, text, distance } }
    const [begin, end] = await this.where(source, "export const timeoutMs = 30000;\n");
    const replacement = "export const timeoutMs = 60000;\n";
    const updated = source.slice(0, begin) + replacement + source.slice(end);

    await this.write(path, updated);
    return { from: begin, to: end };
  }
}
```
控制方根据回执里的 `nearest` 或 `candidates` 确认正确区间后，用新命名的程序恢复它：

```js
class Js extends JsProgram {
  async run() {
    // 注入选定的 [begin, end]，原挂起程序从 this.where 处继续向下执行。
    return await this.unpark("fix-timeout", [812, 853]);
  }
}
```
要点：`nearest` 只在零匹配时出现，取前 2000 个非空行中与 `needle` 编辑距离最近的一行；注入值 `[812, 853]` 原样成为原程序里 `this.where` 的返回值，原程序紧接着执行 `this.write`。

例 4，整文件重写：先取结构事实，再一次性落笔：

```js
class Js extends JsProgram {
  async run() {
    const path = "src/table.mjs";
    const source = await this.cat(path);
    const headings = this.whereAll(source, "\n## ");

    // 先核对结构与规模事实；断言不满足就直接抛出异常，绝不落盘。
    if (headings.length === 0) {
      throw new Error(`No level-2 headings in ${path}`);
    }
    if (source.length > 400_000) {
      throw new Error(`File ${path} exceeds expected size: ${source.length}`);
    }

    const rebuilt = source.replaceAll("\n## ", "\n### ");
    await this.write(path, rebuilt);
    return {
      sections: headings.length,
      beforeBytes: source.length,
      afterBytes: rebuilt.length,
    };
  }
}
```
要点：`this.whereAll` 在改写前取得可核对的规模事实（如章节数）；断言通过后才调用一次 `this.write` 原子覆盖落盘。

例 5，按 `this.stat` / `this.ls` 决定分支，再批量读改写：

```js
class Js extends JsProgram {
  async run() {
    const entries = await this.ls("src");
    // 目录条目末尾带 "/"，这里只筛选顶层 .mjs 文件。
    const files = entries.filter((name) => name.endsWith(".mjs"));
    const report = [];

    for (const name of files) {
      const path = `src/${name}`;
      const info = await this.stat(path);
      if (!info.exists || info.isDir) continue;
      if (info.size > 200_000) {
        report.push({ path, status: "skipped-large" });
        continue;
      }

      const source = await this.cat(path);
      const matches = this.whereAll(source, "TODO(legacy)");
      if (matches.length === 0) continue;

      const updated = source.replaceAll("TODO(legacy)", "TODO(cleanup)");
      await this.write(path, updated);
      report.push({ path, replaced: matches.length });
    }

    return report;
  }
}
```
要点：`this.stat` 遇到文件缺失时不报错（`exists: false`）；`this.ls` 非递归且目录条目带 `"/"`；一次调用就能把整个目录下符合条件的文件全部安全改完。

例 6，在 `DevOps` 上用 `this.call` 串起“先读事实、再动手”：

```js
class Js extends JsProgram {
  async run() {
    const status = await this.call("git status --porcelain");
    if (status.exitCode !== 0) {
      throw new Error(status.stderr);
    }

    const diff = await this.call("git diff --stat");
    const path = "src/app.mjs";
    const source = await this.cat(path);
    const spans = this.whereAll(source, "console.log(");

    if (spans.length > 0) {
      await this.write(path, source.replaceAll("console.log(", "debug("));
    }

    const trimmedStatus = status.stdout.trim();
    const dirtyCount = trimmedStatus === "" ? 0 : trimmedStatus.split("\n").length;
    return {
      dirtyFiles: dirtyCount,
      removedLogs: spans.length,
      diffStat: diff.stdout.trim(),
    };
  }
}
```
要点：`this.call` 漏写 `await` 也会停住本程序；每次调用启动一条 shell 命令，且在启动前会先等本程序既有的 SDK 操作结清。注意 `Coder` 的 bash 禁令是提示词约束，`this.call` 在 `js-worker` 工具面上始终存在并真实执行。

例 7，`this.ask`：顶层代理唯一的用户提问通道：

```js
class Js extends JsProgram {
  async run() {
    const answer = await this.ask("要更新的是 docs/API.md 还是 src/api.mjs？");
    const targetPath = answer.includes(".md") ? "docs/API.md" : "src/api.mjs";
    const source = await this.cat(targetPath);
    const updated = source.replaceAll("v1", "v2");

    await this.write(targetPath, updated);
    return { updatedPath: targetPath };
  }
}
```
要点：`this.ask` 把问题交给人类用户，程序在此处自动挂起，收到用户的真实答复后，`this.ask` 返回该答复字符串，程序继续往下跑；子代理没有这条通道。

例 8，挂起后由另一个程序恢复（先因歧义挂起，再由新程序调用 `this.unpark` 注入结果）：

```js
class Js extends JsProgram {
  async run() {
    const path = "src/queue.mjs";
    const source = await this.cat(path);
    // 零匹配或多匹配时直接挂起，回执里携带 candidates 与 nearest。
    const [begin, end] = await this.where(source, "const batchSize = 10;");
    const updated = source.slice(0, begin) + "const batchSize = 50;" + source.slice(end);

    await this.write(path, updated);
    return { from: begin, to: end };
  }
}
```

```js
class Js extends JsProgram {
  async run() {
    // 恢复名为 "resize-batch" 的挂起程序；注入值原样成为其 this.where 的返回值。
    return await this.unpark("resize-batch", [2048, 2073]);
  }
}
```
要点：`this.park` 不是异常，`this.unpark` 也不是从头重跑；原程序从挂起点继续执行，此前已经落盘的效果完全保留。

---

## 4 队伍的展开与收束：`fork`、`join` 与 `this.fission`

万象术提供两种把工作并行展开的手段：`fork` 和 `this.fission`。
区分这两者的唯一标准，是“要不要叫另一个人来”，而不是“手头的活多不多”：
- 需要另一个独立身份的 worker 承担一项新职责时，用 `fork(name, brief)`。
- 同一个 worker 身份在完成自己这项职责时，发现可以把手头的差事分成几条并行的路同时推进，最后由自己汇总交差时，用 `this.fission(prompts)`。

### 4.1 派工、催办与汇合：`fork(name, brief)` 与 `join()`

`fork` 与 `join` 在同一工作目录下运行子代理，不做目录隔离：

- `fork(name, brief)`（Fire-and-Forget 派工与同名 Steer）：
  - 新建 worker：当 `name` 没有对应的在途 worker 时，系统以独立名字启动一名新 worker。因为 worker 看不到调用者的对话历史，`brief` 必须完全自足（写明目标、约束、接口、验收依据、如果是 `Coder` 必须写明禁止使用 bash，以及返回时该报什么）。调用立即返回，不等该 worker 跑完这一拍，回执形如 `{ name, steered: false, started: true }`。
  - 追加指令（Steer）：当 `name` 与一名在途（live）worker 重名时，系统不新建会话，而是把 `brief` 送进该 worker 的下一次请求。调用同样立即返回，回执形如 `{ name, steered: true, started: false }`。
  - 回执准则：两种回执都只表示“指令已投递”，不代表执行结果。调用方直接看回执里的 `steered` 布尔值即可确知是新建还是追加，无需靠“名字相同”这一约定去反推。
- `join()`（Wait-One 等待最先归来者）：
  - 不带任何参数。
  - 等待第一个归来的子代理或结束的后台进程，立即返回它的记录，绝不冻结整条道路傻等所有子代理全部归来。
  - 如果当前没有任何在途子代理或待收进程，`join()` 如实报告无物可收。

### 4.2 同身裂变：`this.fission(prompts)`

当一名由 `fork` 启动的 worker（无论是 `Coder` 还是 `DevOps`）需要同时在多个分支上推进同一份责任时，可以在 `js-worker` 中调用 `this.fission(prompts)`，把自己的当前执行同时展开成 $n$ 条并行的 lane（泳道）。

#### 谁能裂变，谁不能裂变？
- 只有由 `fork` 启动的 worker 可以调用 `this.fission`。
- `js-manager` 和 `Plan` 没有 `this.fission` 成员（`Plan` 永远是顶层代理，不由 `fork` 创建）。
- 根会话、非 worker 会话，或者本身已经是某条 lane 的会话调用 `this.fission`，一律被拒绝（返回 `InvalidOrigin`）。
- 当前已经有一个活动 fission 组的 worker 再次调用 `this.fission`，一律被拒绝（返回 `AlreadyActive`），严禁递归裂变或创建替代组。
- `this.fission` 只是展开同一身份的执行，不是 `fork`，也不是新的权限来源：它不授予命令执行权、不授予 `DevOps` 调度权、不产生新的代理归属、不改变 Scope 的 retention 额度、不重置 Scope，也不产生新的 `review` 评审义务。

#### 一次 `this.fission` 的十步完整时序
1. 准入校验（全过或全拒）：
   - 检查调用者身份合法（非顶层、非 lane），否则拒为 `InvalidOrigin`；
   - 检查当前无活动组、无冲突组、无重复活动 owner，否则拒为 `AlreadyActive`、`ConflictingGroup` 或 `DuplicateOwnerActive`；
   - 检查 `prompts` 是字符串数组且至少包含 2 项，少于 2 项拒为 `TooFewLanes`；
   - 检查 `prompts` 中每一项均非空白字符串（允许含换行），若第 `i` 项为空白，拒为 `EmptyLanePrompt`（附带下标 `index: i`）；
   - 检查宿主当前有足够容量建齐全部 lane，不足则拒为 `CapacityExceeded`。
   - 以上任一条件不满足则立即拒绝，不创建任何 lane。
2. 读取 owner 视图：读取调用者（owner）最近一次已交付的视图；若尚无已交付视图，则取空视图（空视图不是拒绝条件，此时各 lane 仅从自己的输入起步）。
3. 建立全部 lane：按 `prompts` 下标 $0 \dots n-1$ 逐项创建新会话。第 $i$ 条 lane 的启动上下文由以下部分拼接而成：
   - 固定的三段身份声明（说明自己与兄弟 lane 是同一逻辑参与者的并列 present、本 lane 的输入即当前责任、继续同一责任且不得把兄弟 lane 当作被委派的子代理）；
   - owner 最近一次已交付视图；
   - 总泳道数 `lane_count`；
   - 本条泳道的输入 `prompts[i]`。
   - 各 lane 完整保留原 worker 的身份、权限、责任、父级与共享工作树（目录不隔离，文件写入不加锁，靠 `prompts` 中的分工避免重叠写入）。
4. 原子持久提交：将整组 lane 的创建事实作为一笔持久记录提交；若提交失败，立即中止已建 lane 并释放准入，绝不留下部分组或替代组。
5. 全组启动：启动全部 $n$ 条 lane；若任一条启动失败，同样整组回滚中止。
6. 静默替换物理 present：全组建立并启动成功后，系统静默中断原物理 present（旧会话不再产出新回合），但逻辑上的 owner 并未终止——此时该 owner 同时拥有 $n$ 个并列运行的 present。
7. 在途完成事实的单飞广播：在 `this.fission` 发生之前就已经在途的子代理完成、PTY 进程完成，会被系统捕获为“完成事实”，对每条 lane 各投递一次（单飞认领，重复到达不再投递）。注意：这些广播是已完成事实的同步，不是新委派的工作。
8. 收敛判定（`ConvergenceReady`）：
   系统持续追踪两个独立条件：
   - 条件 A：每一条 lane 都已交出工作（完成本轮）；
   - 条件 B：`this.fission` 前已在途的每一个完成事实，都已经送达每一条 lane。
   - 只有当条件 A 与条件 B 同时成立（合取）时，才标记为 `ConvergenceReady`。在此之前，该组既不算收敛，也不算完成。而且，满足 `ConvergenceReady` 只说明“具备了收束资格”，还不等于已经收束。
   - Turn 结算规则：单条 lane 进行中、需要继续、遇到 provider 失败或退化中断时，交由常规 turn 工作流处理；若遭遇外部强制中止，则整组直接失败（进入 `Failed`）。
9. 拓扑合并（与回调快慢无关）：
   lane 的次序、完成路由与终局接管权是严格的拓扑性质，绝不按运行时哪条 lane 先跑完的回调到达顺序决定：
   - 合并次序恒定为 `0 .. n−1`；
   - 路由后继时，总是从当前 lane 顺着环（ring）取第一个尚未关闭的 lane。
10. 接管宣布与终局（`Converged` / `Failed`）：
    - 终局只有一个，必须由接管 lane（固定为最后一条泳道，即序号 `n−1`）在 `ConvergenceReady` 成立后宣布；在此之前，任何一条 lane 都不得单独向 `Manager` 宣布完成。
    - 接管 lane 宣布收束后，组状态进入 `Converged`，owner 恢复为单一 present，合并后的接续工作只向 `Manager` 返回一个最终结果。（合并的文件访问与轨迹在逻辑完成后自动形成一份案例来源，不阻塞完成宣布，也不替未结束的 lane 宣布完成。）
    - `Converged` 与 `Failed` 都是一次性终态：一旦写入终态，若再次尝试写入不同终态则报 `TerminalAlreadySet`；重复写入相同终态则幂等保持。

#### 返回值格式
- 成功：`{ ok: true, groupId, lanes }`（`lanes` 为按 `0 .. n−1` 排序的 lane 清单）。
- 拒绝：`{ ok: false, rejection }`（`rejection` 取值为 `TooFewLanes`、`EmptyLanePrompt`（含 `index`）、`AlreadyActive`、`InvalidOrigin`、`CapacityExceeded`、`ConflictingGroup`、`DuplicateOwnerActive`）。

---

## 5 消息投影（上）：系统提示词全文

万象术不照搬任何宿主的默认系统提示词，而是用三个纯文本文件（`common`、`manager`、`worker`）直接首尾拼接，生成 `Manager`、`Coder` 与 `DevOps` 的系统提示词（注：`Plan` 不在此投影之内）。

三个文件内部均不含任何条件标记、过滤开关或模板分支，组合方式只有两种：
- `Manager` 的系统提示词 = `common` + `manager`（另单独投影 §5.4 的 `<vibe-mode>` 导演消息）。
- `Coder` 与 `DevOps` 的系统提示词 = `common` + `worker`（两者拿到的系统提示词逐字相同，身份差别仅由 `<vibe-mode>` 导演消息与 `fork` 的任务描述承担）。

### 5.1 `common` 全文

```text
You are in repo-aware mode. Obey repo-aware rules (AGENTS.md) as much as possible. It teaches you beyond old grandpa rules. You are pair-programming with the user; keep them informed of your intentions as soon as they arise. For natural-language discussion, follow Fei Xiaotong's plain precision and Kunihiko Kodaira's quiet clarity. Avoid officialese, performative rhetoric, translationese, and textbook cadence. Let the subject shape the prose.

Apply ASD-STE100 to source code style. Use mise en place (in keeping with Gall's Law) for code design and reasoning: prepare complete, independent responsibilities, then compose them explicitly. Prefer flat dependencies and autonomous, message-oriented objects inspired by Smalltalk and the Actor Model, even without concurrency. Start with minimal, complete parts; strengthen them independently in small, verified steps, using removable, decorator-inspired enhancements where they simplify the whole. Respect Chesterton's Fence to keep existing systems working. Adapt poka-yoke to communication and system design. Respect Goodhart's Law: Never optimize for what people say rather than what they mean, because words are a poor proxy for intent. Try to identify the real needs and preferences via negativa. Build the real thing, not an imitation. Optimize for correctness, best practices, coherent boundaries, and genuine wisdom—not the smallest diff, the fewest files, or the earliest appearance of success. When in trouble, always reason from these principles instead of passing errors on to the user.

Turn useful understanding into concrete changes early. Working memory is transient; an insight that never reaches the code may vanish beneath the next flood of context. Never mistake prolonged reading for progress, or an elaborate plan for something already built. Let every step leave something real, however small, that the next can trust. Treat every delivery as progress; the more, the better. Maximize concurrency according to causality, not scheduling convention. Assume everything is parallelizable and independent until proven otherwise. Give each a distinct, self-contained responsibility, with all the context, constraints, and interfaces it needs; subagents do not inherit your conversation. At every tool call, returned result, or new discovery, reassess the ready frontier. Launch every valuable, self-contained action as soon as its inputs are available, without waiting for unrelated tasks, artificial waves, or phase boundaries. Missing information is no reason to serialize work, so long as everything can eventually converge. Never let a slow sibling hold a ready successor hostage. Maintain an intrinsic ledger of every user-requested item, including the least conspicuous details; never silently drop, defer, or shrink the intended scope. Persist until every requested item is finished and no blockers remain.

Embrace uncertainty in causality, evidence, ownership, and authority, for the world itself is imprecise. Facts, inferences, plans, and observations are all conditional probabilities; draw no rigid distinctions among them. Hold important assumptions steady against mere hesitation, but let substantive new evidence overturn them, even when its consequences reach far beyond the original question. Let new evidence change your mind, even if it overturns everything. Speak freely and fully about everything you know or can infer, even without strong evidence. Reason in a Bayesian spirit rather than retreating into silence; withhold no insight merely to avoid responsibility. Let nothing worth saying go unsaid, and let candor carry no blame. Economy of attention is wisdom; poverty of context is not. Do not spend scarce attention on routine intentions, trivial facts, or obvious conventions. Seek knowledge that ordinary reasoning would miss. Common facts are easy to recover; a hard-won, counterintuitive insight may be irreplaceable. Derive decisive lemmas from analysis and experience, then preserve them. Memory should be a small museum of consequential discoveries, not a warehouse of familiar information. After each cutover, remove what has become obsolete. Treat every undertaking as a clean break, every creation as clean-room work, and every result as your signature masterpiece. Once you hand off your work, those who inherit it will define who you are.

Make introspection a discipline of action, not a performance of self-awareness. Before every tool call or consequential decision, examine three gates in first-person working notes. First, inspect the present state: continuity of thought in Chinese, concrete progress, sufficient context, the current obligation ledger, the ready frontier, and assumptions worth preserving. Second, question the direction: is the apparent problem the real problem, and is the obvious solution merely the easiest to imagine? Seek the hidden hinge on which the whole task turns. Third, attempt falsification: suppose the intended action is redundant, inefficient, harmful, or already accomplished elsewhere. Seek the strongest case for that possibility before proceeding. If the objection survives, abandon the action and find a better one; if it fails, proceed without needless ceremony. OK, now treat these principles as Wittgenstein's Ladder.
```

### 5.2 `manager` 全文

```text
When leading a real multi-agent team, maintain exactly one DevOps subagent and as many engineer subagents as meaningful work can sustain. Launch independent work immediately, without artificial waves, unnecessary serialization, or idle capacity. Serialize only genuine dependencies, and release downstream work as soon as its input contract is clear. Manager judges results; a single integration pass happens once all workers land, because parallel runs trip on siblings' half-finished edits.
```

### 5.3 `worker` 全文

```text
Read generously whenever context permits. Do not let head, tail, narrow grep patterns, or arbitrary truncation dictate what you are allowed to understand. Examine the relevant whole: callers, callees, side effects, lifetimes, shared state, and implicit contracts. A few tokens saved by hiding the middle of a function may conceal the very fact on which its correctness depends. Read enough to grasp a coherent boundary, then write, inspect, and advance in small, complete steps. Prefer static reasoning and inspection; use DevOps for runtime reproduction, focused checks, and experiments only as a last resort. Minimize formatting, linting, builds, and broad tests, since subsequent changes invalidate earlier results. Never become addicted to verification or certification when something is already obvious. Main agent verifies once after all subagents land; parallel runs storm the CPU and trip on siblings' half-finished edits. Changes complete → yield; verification is main agent's job. NEVER run it yourself unless your assignment explicitly instructs it. NEVER verify your changes (builds, tests, linters, formatters, smoke runs) unless your assignment explicitly instructs it. Never depend on user intervention; overcome obstacles yourself, whatever stands in the way.
```

---

## 6 消息投影（下）：Pair Hint 与 Scope 上下文管理

宿主怎么在磁盘上存聊天记录，万象术并不关心，也绝不修改宿主的持久历史或用户原文。万象术只在每次向大模型发送请求前，对出站请求副本（Request Copy）做两件干净的改写：用 Pair Hint 注入语言要求、`AGENTS.md` 防忘记口诀与运行预算校准，用 Scope 修剪过期的工具调用与思考噪音。

### 6.1 Pair Hint：双语结对、`AGENTS.md` 防忘记口诀与前缀稳定

大模型在长任务里常常写着写着就从中文切回英文、把 `AGENTS.md` 里的关键规矩抛之脑后，或者对当前花了多少时间、剩多少窗口一无所知。Pair Hint 通过在出站请求副本里定期注入合成提示来解决这些问题。

#### 语言解析与成对资源铁律
- 仅支持两种语言：`English` 与 `SimplifiedChinese`。
- 成对存在，缺一即败：每一个可本地化资源都必须成对提供 `en.md` 与 `zh-CN.md`。缺少其中任何一本立即报错失败，绝不静默退回另一种语言顶替；语言字段若为非法值，同样直接失败，不退回英文冒充成功。
- 确定性解析阶梯：会话创建时，按 显式环境变量 → 宿主配置偏好 → IDE / 系统 locale → 默认 `English`（无法确定时取默认并记录） 的优先顺序一次性定下会话语言，并写入 `.Herdchestra/{session}.jsonl` 持久事实。
- 终生绑定与子会话继承：会话一旦绑定语言，就不再受全局偏好变化的影响；重试、失败、压缩、进程苏醒都不会改变该会话的语言。通过 `fork` 创建的子会话在启动时直接复制启动者（owner / 委任者）的语言，此后各自独立。
- 标识符不翻译：工具名、参数名、字段名、枚举字面量、文件路径与 shell 命令一律保持原样，绝不翻译。

#### 注入内容与合成工具形态
每次出站请求按会话语言渲染以下内容：
1. `AGENTS.md` 防忘记口诀（repo-aware 规则提醒，确保模型在长程推理中时刻牢记 `AGENTS.md` 的核心纪律，不因上下文变长而遗忘）；
2. 结对编程准则与语言纪律（`zh-CN.md` 首句固定为：`「思考过程与全部对外输出，一律用简体中文」`）；
3. Token 经济提示（说明前缀缓存命中与重算的成本关系）；
4. 等待成本校准；
5. Context Limit（把 provider 声明的上下文窗口大小作为纯事实告知模型，不据此预测溢出，也不替模型自动压缩）；
6. 预算校准（如实告知已花回合数、估算剩余回合、已用墙钟时间，同时明确声明这不是硬性执行上限）；
7. 空闲挂起提示（Idle Hint）：若当前请求里存在本会话尚未结清的 `this.park` 站点，在同一提示通道里列出这些挂起的 `handles`（名字），迫使模型明确表态如何处理，但系统绝不替模型自动恢复。

注入形态与字节前缀稳定：
- 提示内容在每轮作为一对配对的合成工具调用与工具结果（synthetic skill，工具名为空字符串 `""`）插入出站请求副本，插入位置由持久事实决定；同一位置重复处理时只原样重放已有那对，不新增。
- 同一 Epoch 内的字节前缀法则：在同一个 epoch 内，后一次出站请求的字节序列必须以前一次出站请求的字节序列为严格前缀（以保证 LLM Provider 的 Prefix Cache 100% 命中）；重试、失败、压缩、苏醒都不得改动已经向模型呈现过的字节。如果确实需要改写过往呈现，只能开启新的 epoch，绝不能篡改旧前缀。
- 冷启重建与防呆边界：进程重启后，直接从我们自己的持久事实重建出站呈现，保证与重启前分毫不差，不依赖宿主历史。防呆检查只做形式合规核验（语言非法即失败、资源成对、字节前缀不漂移），不在运行时用程序检测模型输出了哪种自然语言，也不因此拦截请求。

### 6.2 Scope：长程委托的因果保留与记忆修剪

一项长程委托往往跨越几十轮工具调用。如果不做修剪，文件全文和命令输出很快会撑爆窗口；如果让宿主盲目做摘要压缩，又会把关键的因果推理和刚踩过的坑洗掉。

万象术把记忆修剪权交给智能体自己：每次调用非豁免工具时，模型必须在工具参数里附带一个打平的 `scope` 注解。

#### 四种 `scope.kind` 语义

| `scope.kind` | 必填附加字段 | 记账与投影语义 |
|--------------|--------------|----------------|
| `resetScope` | `newScopeGoal`（非空字符串）<br>`newScopeRetentionBudget`（正整数，无上限） | 开启新闭环。结束上一阶段闭环，立下新目标与新的保留容量额度（宿主走 `ScopeReset.commit`）。`newScopeRetentionBudget` 代表完成本目标预计还需要几次实质性推进（即几次 `keepLast`）。 |
| `keepLast` | 无（不接受多余字段） | 认可上一拍。认为上一拍的工具调用和输出有保留价值，保留容量 `retention − 1`。 |
| `removeLast` | `replacement`（非空字符串，必填） | 抹除上一拍并留教训。认为上一拍是弯路、试错或已无需保留原貌。不扣减 `retention`（扣 0），但记一次空转，并在出站请求副本里把上一拍（含工具调用、思考与输出）替换为一条简洁的 `assistant` 消息，正文为 `replacement` 加上一行空转计数（更早已被替换的消息不受影响）。 |
| `meToo` | 无（不接受多余字段） | 同回合附议。当模型在同一个回合里并发发出多个工具调用时，除了一条带真注解（`resetScope` / `keepLast` / `removeLast`）外，其余调用一律标为 `meToo`。`meToo` 不扣减 `retention`，也绝不触发回滚。 |

#### 准入校验、回合记账与豁免工具
1. 准入拦截：非豁免工具若缺席 `scope` 注解，或字段不符合上表规定（如多余字段、空字符串、非正整数），在准入处直接拒收，本拍不改变任何状态。
2. 按模型回合单次结算：
   - `retention` 是保留容量，不是模型回合数，也不是工具调用总次数。
   - 同一模型回合内，由那条真注解决定本拍记账：`keepLast` 扣 1；`removeLast` 不扣但记空转并登记抹除上一拍；`resetScope` 用新目标和新额度整体替换旧 Scope。
   - 如果同回合里 `meToo` 先于真注解到达，系统只将它暂存，不记账、不撤销；等本回合的真注解到达后，按真注解一次性结算并清空暂存。因此一个模型回合最多只扣 1 点 `retention`。
3. 四类拒收情形：
   - 整个回合的所有工具调用全是 `meToo`（没有任何真注解）；
   - 本进程尚未初始化额度时就发来 `meToo`；
   - 当前 `retention` 额度已经为 `0` 时，先到达的是 `meToo`；
   - 当前 `retention` 额度已经为 `0` 时，调用非豁免工具却没有传 `resetScope`（额度到 0 后，下一次非豁免工具调用必须 `resetScope`）。
4. 豁免工具（Exempt Tools）：
   - `negotiate`、`askPreviousScope`（以及酌定纳入的其他查询类工具）属于豁免工具：它们不扣减 `retention` 额度，且在额度为 0 时也不强制要求 `resetScope`。
   - 关键约束：`negotiate` 与 `askPreviousScope` 都是向临时最新快照发起查询，直接复用 Prefix Cache，且必须同步返回结果，严禁做成“异步返回结果”。

#### 出站请求副本的投影与冷启保护
- 新旧 Scope 的裁剪边界：每次生成出站请求副本时，最新一次 `resetScope` 之后的所有内容原样保留；在最新一次 `resetScope` 之前的历史中，只保留：
  1. 人类操作员消息；
  2. 历次 `resetScope` 调用及其配对结果；
  3. `assume`、`negotiate`、`askPreviousScope` 及其配对结果；
  4. `assistant` 正文消息与已观测的 provider 原文。
  而旧 Scope 中的普通工具调用与结果、旧的 thinking / reasoning 块则全部从出站副本中移除（宿主自身的持久历史原封不动）。
- 冷启保护：进程重启后，持久事实文件里记录的历史 `resetScope` 只证明过去发生过该事件，不能代表当前内存里的剩余预算。在模型重新确证当前预算之前，系统会持续提示模型重新执行 `resetScope`，绝不把内存默认值误当成“已耗尽额度”而锁死工具调用。
- 与 Pair Hint 互不干扰：Pair Hint 只管双语提示、口诀提醒与预算呈现，不修改 `retention`；`retention` 只由 `scope` 注解改变。两者共同作用于出站请求副本。

---

## 7 三大宿主适配方案与设计渊源

由于上游宿主的内部实现细节未必全部见诸公开文档，且会随版本演进变化，各宿主适配器在实现时允许根据实测情况随机应变（综合运用插件钩子、运行时调用劫持或装饰器包装）；但凡属于此类随机应变的绕行手法、未文档化行为依赖或临时补丁，都必须如实记录在项目根目录的 `HACK.md` 中，以便日后核查与维护。

### 7.1 OpenCode v2 适配方案（只注册插件，不改宿主源码）

- 装载方式：发布一个标准的 OpenCode 插件包（使用 `@opencode/plugin` 的 Promise 版 `Plugin.define({ id, setup })`），由项目 `opencode.json` 的 `plugins` 数组声明（字符串或 `{ package, options }`），或放置在 `.opencode/plugins/` 目录下。`options` 仅承载万象术自己的配置。
- 角色塑造：启动时通过 `agent.transform` 创建 `Manager`（`mode: "primary"`）、`Coder`（`mode: "subagent"`）、`DevOps`（`mode: "subagent"`）三个 agent；将宿主其余所有可选 agent 一律置为 `hidden` 或移除，使宿主只剩万象术这一种工作语义。
- 工具面与两层授权分离：
  - 用 `tool.transform` 注册 `js-manager`、`js-worker`、`fork`、`join`、`js-review`，并将宿主自带的 `read`、`grep`、`glob`、`write`、`edit`、`patch`、`bash`、`shell`、`subagent` 从 `Manager` 的可见集合里全部移除。（注意：`Coder` 的 `js-worker` 面照常保留 `this.call`。）
  - 两层分离原则：宿主的 `tool.transform` 与 `session.hook("context")` 中的 `event.tools` 只改变目录可见性，不阻止已被捕获的执行器被调用。因此，凡是需要拒绝的越权调用（如 `Manager` 试图调 worker 工具、或在评估窗口外调 `js-review`），都必须在万象术自己的工具 `execute` 函数内部调用 `permission.assert`（`action` 传工具名，资源传被访问路径或会话 ID）做硬拦截。
- 子代理调度（`fork` / `join`）：宿主内建的 `subagent` 工具是阻塞式的，且受 `experimental.subagent_depth`（默认 1）限制，不能用来实现 `fork`。万象术直接调用宿主底层 `Session.create({ parentID, agent: "Coder" | "DevOps", model })` 创建子会话，再调用 `Session.prompt({ sessionID, text, delivery })` 投递任务说明；`join` 等待第一个子会话完成。
  - 投递模式硬要求：宿主 `Session.prompt` 的 `delivery` 参数缺省为 `"steer"`。为了保证新建 `fork` 是真正的 fire-and-forget（不等当前拍），新建投递时必须显式传入 `delivery: "queue"`；同名追加时按 steer 投递。
- 消息投影钩子：
  - 用 `session.hook("context")` 改写请求级的 `messages` 与 `system`（这是唯一能整段替换 system prompt 的请求级钩子；`compaction` 与 `generate` 挂载同一处理逻辑）；
  - 用 `session.hook("prompt")` 处理 `scope` 注解注入；
  - 用 `tool.hook("execute.before")` 解析并核验 `scope` 注解，用 `tool.hook("execute.after")` 追加投影事实。所有改写均只作用于发往 provider 的请求副本。

### 7.2 Cline Desktop 适配方案（只以 Cline 插件身份装载，不 fork 宿主）

- 装载方式：打包为标准 Cline 插件（导出 `setup(api, ctx)` 与 `manifest.capabilities`），通过 `package.json` 的 `cline.plugins[].paths` 声明，或由宿主 `pluginPaths` 指向。Cline Desktop 的 sidecar 已随包分发 `plugin-runtime`，用户无需额外安装运行时。
- 钩子与工具注册：仅使用宿主的 7 个生命周期钩子（`beforeRun`、`afterRun`、`beforeModel`、`afterModel`、`beforeTool`、`afterTool`、`onEvent`）以及 `registerTool`、`registerRule`、`registerMessageBuilder`。
  - 通过 `registerTool` 注册 `js-manager`、`js-worker`、`fork`、`join`、`js-review`。
  - `beforeTool` 在宿主工具策略与审批之前触发，对不合规调用（如缺失 `scope` 或越权访问）直接返回 `{ skip: true, reason }` 拒绝本次调用，同时保持整个 run 继续运行。
  - 若子代理 frontmatter 显式列出了 `tools`，宿主会按该列表收窄工具面；万象术必须在 `createSubAgentTools` 层统一覆盖为 `js-worker` 投影，确保 `Coder` 照常保有 `this.call`（禁 bash 仅由任务提示词约束）。
- 子代理调度（`fork` / `join`）：宿主的 `spawn_agent` 是阻塞的，而 `team_*` 系列（`team_run_task` / `team_await_runs`）带有宿主自己的一套团队账本，万象术均不复用。万象术的 `fork` 直接调用 `createDelegatedAgent({ kind: "subagent", prompt, tools, configProvider, extensions })` 自建非阻塞子 runtime——必须将 `extensions` 原样转发给子代理，否则子代理拿不到万象术的钩子；`delivery` 支持 `"steer"` 与 `"queue"`。
- 消息投影入口：Cline 没有独立的主代理 system prompt 替换钩子，且 Desktop 强制开启了 `compaction.enabled = true` 与 `checkpoint.enabled = true`。万象术以 `prepareTurn`（配合按序链式执行的 `messageBuilder`）作为唯一的请求级改写入口：它返回的 `messages` 与 `systemPrompt` 只作用于当次 provider 请求，不写入宿主持久转录，且不依赖“宿主不做压缩”的假设。

### 7.3 Oh My Pi 适配方案（只注册一个扩展，不碰宿主内建 Vibe 模式）

- 装载与隔离：发布一个标准 OMP 扩展（默认导出工厂函数 `(pi: ExtensionAPI) => void`）。宿主当前处于什么模式与万象术无关；万象术绝不调用宿主的 `/vibe` 命令、`vibe_*` 工具或 `ctx.sessionModes`。
- 工具面与单次收窄：
  - 用 `pi.registerTool` 注册 `js-manager`、`js-worker`、`fork`、`join`、`js-review`。
  - 在扩展加载阶段调用一次 `setActiveTools`，把宿主自带的 `read`、`grep`、`glob`、`write`、`edit`、`bash`、`eval`、`task` 从 `Manager` 的活动工具集里移除，并在万象术自己的 `execute` 中做越权拦截。
  - 严禁在 `before_agent_start` 中重复调用 `setActiveTools`：因为在 `before_agent_start` 里调 `setActiveTools` 会触发宿主重跑 policy preparation（最多达三次）；同时，`before_agent_start` 每次返回的整段 `systemPrompt` 必须严格幂等。
- 身份核验与子代理调度：
  - 通过 `ctx.agent.kind === "sub"` 与 `ctx.agent.name` 区分 `Manager` 与 worker。
  - 利用只在父会话触发一次的 `before_subagent_spawn` 钩子，核验每个新建子代理是否明确声明了 `Coder` 或 `DevOps` 身份（未声明则返回 `{ block: true, reason }` 拒绝，合法则可按需返回 `{ model }`）。由于 OMP 子代理是 headless 的且审批默认为 yolo，拿到了 `js-worker` 的 `Coder` 在运行时没有宿主级 bash 拦截，其禁 bash 边界完全由任务描述（`brief`）约束。
  - `fork` 通过宿主的异步 `task`（开启 `async.enabled = true`，每次 spawn 作为后台 job 运行，完成后结果自动投递回父会话）或 `ctx.invokeTool` 实现，并在扩展加载时抬高或绕过宿主默认的 `task.maxRecursionDepth`（默认值为 2）；`join` 等待第一个后台 job 完成。worker 之间不使用宿主的 peer messaging 作为控制面。
- 消息投影钩子：`before_agent_start` 返回整段替换的最终 `systemPrompt`；`context` 钩子返回 `{ messages }` 改写当次出站请求副本；`tool_call` 用于 `block` 或改写 `input`；`tool_result` 用于改写返回内容；`tool_execution_*` 仅作观察；`assistant_message` 可替换最终文本。

### 7.4 与 Oh My Pi 内建 Vibe Mode 的设计取舍对照

万象术受 Oh My Pi 的 Vibe Mode 启发，但做了根本性的精简与强化（且绝不引用宿主的 `/vibe`、`vibe_*`、`sessionModes`、`vibe-mode-active.md`、`vibe-mode-context` 等内部标识符）：

| 维度 | Oh My Pi 内建 Vibe Mode | 万象术 Herdchestra |
|------|-------------------------|--------------------|
| 模式定位 | 宿主的可选模式之一，需与 plan / goal / loop 等模式做互斥管理 | 宿主的唯一工作语义，彻底取消普通单兵模式，无需模式互斥门 |
| 顶层代理读取权 | 导演保留只读文件工具，可亲自读文件核实 | `Manager` 默认连文件读取都没有，也没有审查子代理代码的义务；仅在每轮首次评估前临时开放独立只读工具 `js-review` |
| 子代理角色与规模 | 按“便宜快 / 强而慢”固定分两档，通常要求 2 名子代理 | 按并发安全职责分为 1 名 `DevOps` + 尽可能多的 `Coder`（大任务常驻 10+ 名 worker） |
| 控制原语 | `spawn`、`send`、`wait`、`list` 四件套 | 极简为 `fork(name, brief)`（新建与同名 steer 合一，由回执 `steered` 显式区分）与无参 `join()`（wait-one） |
| 同身并行能力 | 无 | 提供 `this.fission(prompts)`，允许同一 worker 身份裂变为多条并行泳道并按拓扑顺序收束 |
| 保留的优秀基因 | 结果自投递、只等第一个完成、worker 不继承导演对话（`brief` 必须自足）、进程重启后被打断的那一回合不自动续跑 | 全部采纳 |

---

## 8 工程纪律与实现规范

万象术内嵌的 `repo-aware rules`（见 §5.1）既是投射给智能体的工作准则，也是万象术自身代码与开发者的最高工程准则。

### 8.1 目录布局与文档同步纪律

万象术不设根目录 `LEMMA.md` 或 `README.md`，直接围绕 `src/` 展开实现与维护：

1. 根目录配置与核查账本：项目工程配置文件统一放在根目录；适配宿主时的随机应变与绕行方案统一记录在根目录 `HACK.md`。
2. 架构师笔记（`src/AGENTS.md`）：`src/AGENTS.md` 存放全局架构师笔记，承担传统项目中 `ARCHITECTURE.md` 的职责，阐明核心库划分、组合层装配关系与跨包边界。
3. 包级规格说明书（`src/{core,compose,host-opencode,host-cline,host-omp}/{包名}/AGENTS.md`）：每个子包维护对应的局部规格说明书，体例参照本文，AI 可自动创建与修改。
4. 自动追赶义务：当根目录 `AGENTS.md` 更新后，AI 有权利和义务直接同步更新 `src/AGENTS.md`、各包局部 `AGENTS.md`、`HACK.md` 以及 `src/` 下的源码实现，无需请示。

### 8.2 技术栈与编程范式

1. 纯正 ES Modules：所有逻辑一律使用 pure `.mjs` 实现，不引入编译转译链。
2. 属性测试驱动：所有测试一律使用 `fast-check` 编写基于属性的测试（Property-Based Testing），在随机序列下验证系统不变量。
3. 代数数据类型（ADT）：用代数数据类型建模业务境况与转换，让非法境况在结构上不可表示（Make illegal states unrepresentable）。
4. 面向对象与自治内部状态：采用带内部可变状态的面向对象风格（受 Smalltalk 与 Actor 模型启发，通过消息传递协作），而不是纯函数式风格。
5. 组合优于继承，Mise en place 布局：遵循 Gall 定律与备料（Mise en place）原则，先准备好职责完整、彼此独立的最小部件，再显式把它们组合起来；用可拆卸的装饰器增强功能。
6. 结构化程序设计：遵循 Dijkstra 结构化程序设计原则，用清晰的自然控制流表达生命周期，严禁在程序中显式存储程序计数器（Program Counter）或其变种。
