# 万象术 Herdchestra 规格说明书（重组草稿）

> 本说明书严禁 AI 修改，除非可以修改的部分。
> 本说明书阐明产品的最新语义。任何时候 AI 有权利和义务让推论文档（LEMMA.md）和代码追上本文件的最新变更，无需请示直接实施。

---

## 0 序言

万象术 Herdchestra：让一万只大象一起跳舞。
Orchestration for agents that don't like being orchestrated.

写程序这件事，长久以来是一个人的手艺。一个工程师，一台电脑，一条思路，从需求走到代码，饿了吃饭，困了睡觉。后来 AI 来了，我们忽然有了一群不会疲倦的程序员。它们读代码，查资料，写功能，补测试，做代码审查；来自不同的厂商，用着不同的模型，脾气也各不相同——有的谨慎，有的激进；有的写起代码来像钟表匠，有的像推土机。而且，每一个都还在变聪明。

于是有了一个新问题：如果一个智能体能写程序，一百个、一千个、一万个一起写，会怎么样？

这件事，有点像让一万只大象跳舞。

大象并不蠢。恰恰相反，它们聪明、有力、记性惊人，问题只在于太重。一只大象往左挪一步，那只是一步；一万只大象同时往左挪一步，地都要震。工程里也是如此：这边刚改完一个文件，那边的智能体已经把它覆盖了；这个还在查问题，那个已经按自己的理解动了手；有的以为任务做完了，有的还欠着测试；有的掉线，有的重试，有的忘了自己干过什么。要是再闯进来一个带着别家模型习惯的，场面就更热闹了。

对付这样的场面，喊是喊不动的，给每头象拴一根更长的绳子也没用。现在不少智能体编程的做法，说到底还是一个指挥站在台中央，拼命挥棒子，指望几十头越来越有主见的象恰好踩在同一个小节上。两三头的时候，这法子看着不错；十头，开始手忙脚乱；一万头，指挥棒就成了道具。

人们爱说，站在风口上，猪也能飞。风确实起来了：模型越来越强，价格越来越低，速度越来越快智能体越来越多。可是风托得起一只猪，托不起一万头没有队形的象。到了那个规模，能不能飞，不再取决于哪头象最聪明，而取决于地上有没有路，有没有红绿灯，有没有账本、分工和规矩；有人跌倒了，队伍还走不走；有人走散了，还能不能找回来；各支队伍能不能踩着自己的步子，在同一个世界里把同一件事做完。

这就是我们做万象术的原因。"万象"，既是一万头象，也是世间万象。我们不想去造世界上最聪明的那头象，也不想把所有的象驯成同一个姿势。我们想做的事更笨一些，也更基础一些：让不同的智能体各尽其长，又能共同成事。今天最好的模型能来，明天更好的也能来。

它们性格不同，能力不同，上下文不同，干活的法子也不同。但只要进了同一个工程，就知道自己是谁，正在做什么，能做什么、不能做什么；知道哪些事已经发生，哪些事还没有完成；知道什么时候可以并行，什么时候必须等待；知道怎样把手里的活交给另一个智能体——也知道，万一哪个智能体消失了，世界不该跟着失忆。

这里没有永远正确的总指挥，也没有一根拴住所有象的绳子。有的只是一套基础设施，让协作这件事本身变得可靠。

于是，一头象可以跑，一百头象可以协作，一万头象可以跳舞。而且跳的不是同一种舞：爵士、芭蕾、街舞，还有没人见过的新舞步——各有各的节奏，各有各的长处，谁也不踩谁的脚。

这就是万象术：不是驯服智能的法术，而是驾驭智能规模的技术。我们相信，AI 编程的下一个时代，不属于拥有最强智能体的人，而属于能让无数不同的智能体自由加入、可靠协作、把真正复杂的工作做完的人——就像今天的进程、服务和机器那样。当智能成为充沛的资源，协同就是新的稀缺品。万象术想做的，就是这一层。

让一万头大象一起跳舞。更快，更好，更省。
万象皆可用，众智自成事。

---

## 1 产品定位与架构原则

万象术是 OpenCode v2 / Cline Desktop / Oh My Pi 的**工具层**和**消息投影层**的旁路实现。

- 不修改宿主源代码，总是可以与宿主的最新版兼容。
- 通过插件、在运行时劫持调用、或者装饰器模式来实现所有功能。
- 由若干彼此不依赖、完全独立的核心支持库，以及黏合支持库的组合层组成，外加各宿主的适配器。上游接口变化时更新适配器。
- 需要参考其他仓库代码时，先在 `../` 寻找，没有则 clone 至 `../`。

持久化：我们不一定需要持久化，如果实在需要，放在 `.Herdchestra/{session标识符}.jsonl` 即可，持久化不要太重。

首先实现的，是类似 Oh My Pi 中的 Vibe Mode（见 §12 参照）。

---

## 2 角色模型与安全论证

### 2.1 三种角色

| 角色 | 定位 | 模式 |
|------|------|------|
| **Manager** | 导演。调遣尽可能多的 worker，自己 NEVER edit、run、grep、build，也不读文件——工具面里没有。 | 顶层 / `mode: "primary"` |
| **coder** | 本地事实调查与源码修改。任务描述里必须明确禁止使用 bash 工具；工具面照常含 `call`，禁令只是提示词约束。 | `mode: "subagent"` |
| **devops** | 执行真实命令（bash、构建、测试、进程、git）。同一时刻恰好一名。 | `mode: "subagent"` |

### 2.2 并发安全论证

启动子代理时要明确其身份是 devops 还是 coder，如果是后者，在任务描述中禁止使用 bash 工具。这样，若干 coder 以及恰好一个 devops 的并发是相对安全的：coder 只做文件读写与静态推理，不执行命令，因此多个 coder 之间的冲突面收窄到文件写入；而恰好一个 devops 持有命令执行权，避免了多个进程并发修改运行时状态（端口、进程、环境变量）的竞态。

### 2.3 调度纪律

- 通过提示词建议任何时候恰好有 1 个 devops 子代理和若干个 coder 子代理在工作。
- 缺位立刻补位。能调用 `fork` 则优先 `fork`，无任务可 `fork` 才考虑 `join`。

### 2.4 职责边界

- Manager 没有常规读取、不亲自调查，也不审查子代理代码（见 §5、§8）。
- Manager 没有审查子代理代码的义务。
- 主代理的角色名叫做 Manager。其默认工具面连文件读取都不允许。
- 不同于 Vibe Mode 要求 2 名子代理，我们用尽可能多的子代理。
- 我们将把宿主唯一的语义设为类似 Vibe Mode，并没有普通 Mode。

---

## 3 工具面：`js-manager` 与 `js-worker`

我们用 `js-manager`（其中 manager 没有的能力就没有）和 `js-worker` 来替换系统的 `read`, `grep`, `glob`, `write`, `edit` 等工具。注意 coder 的 bash 禁令只是提示词约束而已。

### 3.1 成员表

| 成员 | 语义 |
|------|------|
| `cat(path)` | 读文件 |
| `stat(path)` | `{ exists, size, mtime, isDir }`，缺失不是错误 |
| `ls(path)` | 列目录（非递归） |
| `write(path, content)` | 原子覆盖写 |
| `mkdir` / `rm` / `mv` | POSIX 级别操作 |
| `where(content, needle)` / `whereAll` | 精确切片定位；歧义时 park，纯 char 透明，禁止歧视空白或 trim |
| `park(payload)` / `unpark(name, value)` | 挂起与恢复，机制很复杂，参见 §3.3 |
| `call(command)` | 执行 bash 命令 |
| `this.ask` | 唯一一个向用户提问的通道，仅 top-level agent 可用 |

利用 `cat`, `where`, `write` 可以很容易模拟出传统编辑方法。`js-` 工具的工具描述直接把实现的源代码原封不动展示给 LLM。

以上没提到的成员就是没了；同样，事务和校验也没了。

### 3.2 路径语义与 `write` 的原子性

**路径语义：** 空路径解析为会话根；绝对路径按原样使用；访问由操作系统权限决定，不按工作区根做边界拦截。

**`write` 的"原子覆盖写"是硬要求，不是措辞：** 写入先在目标文件的同目录建临时文件，写满并 flush 后 `rename` 到目标路径（同目录保证同一文件系统，`rename` 才能原子替换）；新建时临时文件以排他方式创建，目标已存在则替换。读者任何时刻只会看到旧内容或新内容，绝不会看到半截文件；失败时临时文件被删除，目标保持原样。这只是一次调用内的替换保证，不是事务：多个 `write` 之间没有回滚，同一路径的并发写不设锁也不校验，最后一次 `rename` 获胜；进程在 `rename` 前崩溃只留下一个临时文件，目标不变。

### 3.3 程序的生命周期（`park` / `unpark` 的完整机制）

**状态机：** `running` → `waiting`（等一条命令或一次 SDK 操作）→ `parked`（挂起）→ `running`；终态是 `completed`、`failed`。`draining`（等本程序已发起的 SDK 操作结清）与 `settling`（收尾中）是过渡态，程序不再接受新的表达式推进。

**挂起的触发有三处：** 显式 `park(payload)`；`where` 的零匹配或多匹配；`this.ask`。三处走同一条通道：当前表达式的 promise 被一个内部信号 `__programPark` 取代，程序转入 `parked`，本次工具调用立即返回一份回执，而不是等待。

**回执的形状：** `{ name, state: "parked", reason, payload, source, pendingOperations, command, delta, remainingWaitMs }`。`reason` 为 `explicit`（显式 park 与 `where` 歧义）或提问类原因；`source` 是带行号的完整源码；`command` 是当前命令的视图（`stdout` / `stderr` 增量、字节数、`durationMs`、退出状态），没有命令时为 null；`delta` 是自上次回执以来的新增输出。

**挂起不冻结世界：** 已启动的命令继续跑，输出继续被收集；挂起时本程序的请求期限被取消，恢复时按控制请求的新期限继续，不续期旧期限。

**恢复：** 在另一个命名程序里调用 `unpark(name, value?)`。宿主先核对控制者在本请求里确实观察过目标（`STALE_CONTROL`）、目标名不是自己（`INVALID_ARGUMENT`）、目标存在（`PROGRAM_UNKNOWN`）、控制者持有目标的能力集（`CAPABILITY_DENIED`）；全部通过后把目标从 `parked` 切回 `running`，并把 `value` 作为挂起表达式的返回值。`value` 只交给显式 `park` 与 `where` 歧义；`call` 与 `this.ask` 的挂起点各自拿回自己的结果，不受 `value` 影响。省略 `value` 等价于注入 `null`。

**恢复是单次的：** 同一个挂起点只能被恢复一次；控制者之后再次 `unpark` 同一名字，若目标已结束或已不在挂起态，返回 `STALE_CONTROL` 或目标当前回执，不会隐式再恢复一次。

**名字占用：** 同一名字在程序仍 `running`/`waiting`/`parked` 时被新调用占用，报 `NAME_TAKEN`；程序进入终态后，该名字可以开始新运行，旧回执按原调用重放。同一物理工具调用（同一 callId）重放原回执；同一 callId 换成不同载荷报 `CALL_REPLAY_MISMATCH`。

**`call(command)` 的排空：** `call` 先等本程序此前发起、尚未结清的 SDK 操作全部完成（`draining`），再启动命令（`waiting`）；漏写 `await` 不会让程序越过这一步。命令结束后程序回到 `running`，`call` 返回命令回执。

**期限：** 工具请求在准入时按 `intent` 的 UTF-8 字节数一次性确定整次请求的期限（每字节 50ms）；程序内所有操作与恢复共用剩余时间，到期时控制者与仍在活动的目标一同挂起，只恢复控制者。

### 3.4 用法样例

（只使用上表成员；共同前提是 `cat` 得到的文本可以直接作为 `content` 传给 `where`/`whereAll`）

**例 1，同一字面量多处出现时逐处改写：**

```js
class Js extends JsProgram {
  async run() {
    const path = "src/retry.mjs";
    const text = await this.cat(path);
    const spans = this.whereAll(text, "RetryPolicy");
    if (spans.length === 0) throw new Error("no RetryPolicy in " + path);
    // 从后往前替换，前面的 UTF-16 偏移不会被本次改写挪动。
    let next = text;
    for (const [begin, end] of spans.slice().reverse()) {
      next = next.slice(0, begin) + "RetryPolicyV2" + next.slice(end);
    }
    this.write(path, next);
    return { replaced: spans.length };
  }
}
```

要点：`whereAll` 给出全部 `[begin, end]`，倒序替换让偏移保持有效；同一路径在一次程序里只写一次。

**例 2，唯一锚点确认后做定点插入：**

```js
class Js extends JsProgram {
  async run() {
    const text = await this.cat("docs/API.md");
    const [begin, end] = await this.where(text, "## 安装\n"); // 必须唯一，否则 park
    const insert = "## 快速开始\n\n见下方安装一节。\n\n";
    this.write("docs/API.md", text.slice(0, begin) + insert + text.slice(begin));
    return { at: begin, length: end - begin };
  }
}
```

要点：needle 带上换行与标题层级，多出来的特征正是让 span 唯一的东西；零个或多个匹配时 `where` 不会返回，程序转入 park。

**例 3，零匹配诊断与回填（park → 读 nearest → 在新程序里 unpark 注入 span）：**

```js
class Js extends JsProgram {
  async run() {
    const text = await this.cat("src/config.mjs");
    // 若此处的 needle 写错，本次运行 park，payload 形如
    //   { needle, candidates: [], nearest: { span, text, distance } }
    const [begin, end] = await this.where(text, "export const timeoutMs = 30000;\n");
    this.write("src/config.mjs", text.slice(0, begin) + "export const timeoutMs = 60000;\n" + text.slice(end));
    return { from: begin, to: end };
  }
}
```

控制方用新命名的程序恢复：

```js
class Js extends JsProgram {
  async run() {
    return await this.unpark("fix-timeout", [812, 853]); // 注入选定 span，原程序继续
  }
}
```

要点：`nearest` 只在零匹配时出现，取首个 2000 个非空行中与 needle 编辑距离最近的一行，形如 `{span, text, distance}`（每行只与 needle 的前 200 行/200 字符比距离）；`candidates` 最多 8 条，每条 `{span, context}`，`context` 是匹配两侧各 80 个 UTF-16 单位。注入值原样交给 `where` 的返回值，不做二次校验。

**例 4，整文件重写：先取结构事实，再一次性落笔。**

```js
class Js extends JsProgram {
  async run() {
    const path = "src/table.mjs";
    const text = await this.cat(path);
    const heads = this.whereAll(text, "\n## ");
    // 在内存里拼出最终文本；断言不成立就 throw，不落笔。
    if (heads.length === 0) throw new Error("no sections");
    if (text.length > 400000) throw new Error("file larger than expected");
    const rebuilt = text.replaceAll("\n## ", "\n### "); // 用 JS 原生字符串操作，不用额外成员
    this.write(path, rebuilt);
    return { sections: heads.length, before: text.length, after: rebuilt.length };
  }
}
```

要点：`whereAll` 在改写前取得可核对的规模事实（section 数）；`write` 是覆盖写，写入即生效。

**例 5，按 `stat`/`ls` 决定分支，再读改写：**

```js
class Js extends JsProgram {
   async run() {
     const entries = await this.ls("src");
     const targets = entries.filter((name) => name.endsWith(".mjs")); // 目录条目带结尾 "/"
     const report = [];
     for (const name of targets) {
       const path = "src/" + name;
       const info = await this.stat(path);
       if (!info.exists || info.isDir) continue;
       if (info.size > 200000) { report.push([path, "skipped"]); continue; }
       const text = await this.cat(path);
       const spans = this.whereAll(text, "TODO(legacy)");
       if (spans.length === 0) continue;
       this.write(path, text.replaceAll("TODO(legacy)", "TODO(cleanup)"));
       report.push([path, spans.length]);
     }
     return report;
   }
 }
```

要点：`stat` 缺失不是错误（`exists: false`）；`ls` 非递归，目录条目带 `/`；每个路径在一次程序里只写一次。

**例 6，在 worker 上用 `call` 串起"先读事实、再动手"：**

```js
class Js extends JsProgram {
  async run() {
    const listed = await this.call("git status --porcelain");
    if (listed.exitCode !== 0) throw new Error(listed.stderr);
    const diff = await this.call("git diff --stat");
    const text = await this.cat("src/app.mjs");
    const spans = this.whereAll(text, "console.log(");
    this.write("src/app.mjs", text.replaceAll("console.log(", "debug("));
    return { dirty: listed.stdout.trim().split("\n").length, removedLogs: spans.length, stat: diff.stdout };
  }
}
```

要点：`call` 漏写 `await` 也会停住本程序；每次调用启动一条 shell 命令，`call` 之前会先等本程序既有 SDK 操作结清。worker 的 bash 禁令只是提示词约束，`call` 在投影内即真实执行。

**例 7，`this.ask`：顶层代理唯一的用户通道。**

```js
class Js extends JsProgram {
  async run() {
    const answer = await this.ask("要改的是 docs/API.md 还是 src/api.mjs？");
    const path = answer.includes(".md") ? "docs/API.md" : "src/api.mjs";
    const text = await this.cat(path);
    this.write(path, text.replaceAll("v1", "v2"));
    return { path };
  }
}
```

要点：`ask` 把问题交给用户，程序在此挂起，收到真实答复后以该答复字符串继续；子代理没有这条通道。

**例 8，挂起后由另一个程序恢复（先 park，再由新程序 unpark 注入结果）：**

```js
class Js extends JsProgram {
  async run() {
    const text = await this.cat("src/queue.mjs");
    // 零匹配或多匹配时，这里直接 park，回执里带 candidates 与 nearest。
    const [begin, end] = await this.where(text, "const batchSize = 10;");
    this.write("src/queue.mjs", text.slice(0, begin) + "const batchSize = 50;" + text.slice(end));
    return { from: begin, to: end };
  }
}
```

```js
class Js extends JsProgram {
  async run() {
    // 在同一请求里恢复上一条挂起的程序；注入值原样成为 where 的返回值。
    return await this.unpark("resize-batch", [2048, 2073]);
  }
}
```

要点：`park` 不是异常，`unpark` 也不是重跑；原程序从挂起点继续，已落盘的效果保留。注入值与文件当前内容是否相符不由宿主核对，程序自己要保证切片仍然有效。

---

## 4 子代理控制：`fork` / `join` / Fission

### 4.1 `fork` 与 `join`

- 我们调度子代理的工具原语是 `fork` & `join`。
- 前者是 fire-and-forget，后者是 wait-one（最先回来的）。
- `fork` 要命名。如果和已有的重名，就是 steer 语义，回执显式标记为 steer。
- `join` 不带参数。
- `fork`/`join` 在同一目录下启动子代理，没有隔离。

| 成员 | 语义 |
|------|------|
| `fork(name, brief)` | 以独立名字启动一名 worker，`brief` 自足（目标、约束、接口、验收依据、返回该报什么）；立即返回，不等这一拍，回执形如 `{ name, steered: false, started: true }`。名字与在途 worker 相同时是 steer：把 `brief` 送进该 worker 的下一次请求，不新建会话，回执形如 `{ name, steered: true, started: false }`。`steered` 由回执显式给出，调用方不靠"名字相同"这一约定反推语义；两种回执都只表示已投递，不表示结果。 |
| `join()` | 不带参数。等第一个归来的子代理或结束的进程，返回它的记录；不冻结整条道路直到全部归来。没有可收之物时如实报告。 |

### 4.2 Fission：同一个 worker 身份同时展开成多条 lane

每条 lane 做同一份差事的不同一路。

**与既有语义的差别：**

- `js-worker`（coder 与 devops 两种身份）都可以调用 `this.fission(payload)`；`js-manager` 没有该成员，Manager 不能 fission。Plan 永远是顶层代理，不由 `fork` 创建，因此 Plan 不能 fission；fission 只属于由 `fork` 启动的 worker。
- 可裂变的前提：该 worker 由 `fork` 启动（不是顶层 agent），且当前没有活动的 fission 组。`fork` 才创建新身份；fission 只展开同一身份的执行。
- 对 `js-worker` 来说，fission 与 `fork` 是两种不同手段：`fork` 让另一个 worker 出现，fission 让同一个 worker 同时有多个 present。判断标准是「要不要另一个人」，不是「活多不多」。

**语义：**

- `prompts` 是字符串数组，至少两项且每项非空白，每项可含换行；第 i 项就是第 i 条 lane 的输入。少于两项拒绝（`TooFewLanes`），出现空白项即拒绝（`EmptyLanePrompt`，带该项下标）。
- 整组准入或整组拒绝。先建齐所有 lane，全部建立后才替换当前物理 present（旧 present 静默中断）；任一 lane 创建、启动或持久提交失败，就中止已建 lane 并释放准入，不留部分组，也不留替代组。
- lane 的启动上下文 = owner 最近一次已交付的视图（可为空）+ 本 lane 的输入。各 lane 保留同一 worker 身份、权限、责任、父级与共享工作树，目录不隔离。
- 已有活动组时再次 fission 被拒（`AlreadyActive`），不得递归裂变、不得创建替代组。根会话或非 worker 的调用被拒（`InvalidOrigin`）。
- 收敛条件 `ConvergenceReady`：每个 lane 都交出工作，且每个 fission 前已在途的完成都已送达每个 lane。这是两个独立条件的合取，缺一不可；两条件同时成立才记 `ConvergenceReady`，此前的组既不算收敛也不算完成。终局只有一个，必须由接管 lane（序号 n−1）宣布。
- lane 次序、完成路由与终局 lane 是拓扑性质，不按回调到达次序决定：合并次序为 0..n−1，后继从当前 lane 顺环取第一个未关闭者。
- fission 发生前已在途的完成（子代理完成、pty 完成）对每个 lane 各送达一次，只认一次（单飞认领）；送达的是同一份已捕获的完成事实，不是新委派的工作。
- turn 结算：进行中、需要继续、provider 失败、退化中断 → 让给 turn 工作流；外部中止 → 组失败；完成 → lane 交出工作，接管宣布收束。
- 各 lane 先收敛，不分别向 Manager 宣布完成；收敛后的接续工作只返回一个最终结果。合并的文件访问与轨迹在逻辑完成后形成一份案例来源，不等待案例整理，也不替未结束的 lane 宣布完成。
- fission 不授予命令执行、DevOps 调度或新代理归属；它不是 fork，也不是新的权限来源。

**执行过程（一次 fission 的完整时序）：**

1. **准入：** 调用者是 `fork` 启动的 worker（不是顶层、不是 lane），当前没有活动组，`prompts` 通过第 2 款第 1 条校验，且宿主有可用容量；任一不满足立即拒绝，不创建任何 lane。
2. **取 owner 视图：** 读取调用者最近一次已交付的视图；没有则为空视图。空视图不是拒绝条件，lane 只从自己的输入起步。
3. **建 lane：** 按 `prompts` 逐项创建新会话，lane i 的启动上下文是固定的三段文字（同一逻辑参与者的并列 present、本 lane 输入即当前责任、继续同一责任且不把兄弟 lane 当被委派代理）加 owner 视图加 `lane_count`；lane 保留原身份、权限、责任与共享工作树。
4. **持久提交：** 整组 lane 的创建事实作为一笔记录提交；提交失败则中止已建 lane、释放准入，不留部分组。
5. **启动：** 全部 lane 启动；任一条启动失败，同样整组回滚。
6. **替换：** 组建立并启动后，系统静默中断原物理 present（旧会话不再产出一回合），但逻辑 owner 不终止——owner 现在有 n 个 present。
7. **在途完成的广播：** fission 之前已在途的子代理完成、pty 完成被捕获为完成事实，对每个 lane 各投递一次，单飞认领，重复到达不再投递；它们不是新委派的工作。
8. **收敛：** `ConvergenceReady` 由两个条件同时成立判定——每个 lane 都交出工作，且每个 lane 都收到全部广播完成。满足 `ConvergenceReady` 只说明可以收束，不说明已经收束；收敛只由接管 lane（序号 n−1）宣布，宣布前任何人不得说本组完成。
9. **合并：** 合并顺序恒为 0..n−1，与完成到达顺序无关；后继从当前 lane 顺环取第一个未关闭者。
10. **终局：** 接管 lane 在 `ConvergenceReady` 成立后宣布，组进入 `Converged`，owner 恢复为单一 present，接续工作只返回一个最终结果。`ConvergenceReady` 不成立时不得宣布，也就不得进入 `Converged`。组失败进入 `Failed`；`Converged` 与 `Failed` 都是一次性的，再次尝试写入报 `TerminalAlreadySet`，同一值重复写入只是幂等保持。

**返回值与拒绝：** 成功返回 `{ ok: true, groupId, lanes }`，`lanes` 是按序的 lane 清单；拒绝返回 `{ ok: false, rejection }`，取值为 `TooFewLanes`、`EmptyLanePrompt`（带 `index`）、`AlreadyActive`、`InvalidOrigin`、`CapacityExceeded`、`ConflictingGroup`、`DuplicateOwnerActive`。

**边界：** lane 之间的文件写入落在同一工作树，重叠写入要靠 lane 输入里的分工避免，系统不加锁；fission 不改变 retention、不重置 Scope，也不产生新的评审义务。

---

## 5 评审与接力机制

一条工作由 Plan 规划、由 Manager 执行，经 `review` 提交义务台账并在台账清空时换人。注意，是 `review`，不是 `this.review` 这样的成员访问。

### 5.1 `review` 的语义

| 成员 | 语义 |
|------|------|
| `review(findings, note?)` | 提交评审。`findings` 是 `{ key, value }` 数组，字符串 value 新增或覆盖该键，`null` 删除该键，`[]` 不会清空既有台账。台账清空即接力；接任者第一次评审台账为空即推进里程碑；初任第一次评审台账为空时由系统代问用户。`note` 只在启动 Manager 时作为底稿正文发送。返回回执：已提交，或可提交下一次评审——后者不是要用 `join` 等待的结论。 |

唯一入口是 `review`。它提交一份按键义务台账：`findings` 是 `{ key, value }` 数组，字符串 value 新增或覆盖该键，`null` 删除该键，`[]` 不会清空既有台账。没有单独的交付、晋级或确认调用。

### 5.2 接力规则

- 台账被清空即接力：本任退休，下一任在同一里程碑接棒。接任者看不到触发接力的那次 review 及其之前的对话；它从用户原始输入、底稿与继任提示出发。`note` 只在启动 Manager 时被当作底稿正文发送，其余场合不交给任何人。
- 接任者第一次 review 若台账为空，即独立确认前任成果并推进里程碑，不新建任期；若台账非空，进入正常工作期，之后再清空仍是接力，不是晋级。
- 初任第一次 review 若台账为空，不算评审，也不接力：系统代该任向用户提问「我需要干什么？」并等待真实答复；答复到来后由同一任继续。
- 接力只换人，不换里程碑。台账非空时只能补齐本里程碑的义务，不得边修边跨入下一里程碑。
- 接力时保留已接收的工作、进程归属与未领取的结果；道路绑定的 `devops` 跨接力不变，不得改绑。前任的信心不能代替后任的独立评估。

### 5.3 接力的机械过程（以一次台账清空为一次接力）

1. 现任提交 `review(findings)` 后，系统计算台账：字符串 value 覆盖或新增键，`null` 删键，`[]` 不动台账。台账非空即正常工作期，现任继续。
2. 台账变空时，系统在同一笔持久记录里完成三件事：现任标记为退休、写入上下文屏障、下一任以 `successor` 身份开启；三者不可分开出现，中断不会留下半个任期。
3. 屏障把现任的对话从下一任的可见范围里切掉：下一任只看到用户原始输入、底稿（Plan）或工作区事实（Manager）与继任提示；触发接力的那次 review 及之前的对话都不继承。
4. 同一里程碑内接棒：继任者的里程碑字段从退休者原样复制；只有继任者第一次 `review` 以空台账独立确认前任成果时，才推进到下一里程碑。
5. 重放与幂等：同一 callId 重放原回执；不同载荷复用同一 callId 报错；退休者不能因重放再次动手。
6. 终局：Manager 在「收尾」清空台账即结案为「已完成」；「已完成」只能由操作员的真实消息重开一次，代理消息不得重开。Plan 在「待执行」的空账 review 不是接力，而是启动 Manager。

### 5.4 Manager 侧的里程碑

Manager 的里程碑依次为「执行」「收尾」「已完成」。

- 在「执行」清空台账即接力，接任者在同一里程碑接手，其第一次空账 review 把工作推进到「收尾」。
- 在「收尾」清空台账即结案，没有二次确认。
- 「已完成」只能由操作员的真实消息重开，代理消息不得重开。
- 收尾时若有真实执行资源未收束，空账 review 也不结案。

### 5.5 本任第一次 review 之前的权限

本任第一次 review 之前，Plan 的底稿对本任只读（写被拒）；第一次 review 之后本任解锁。接力开启的新任期重新只读。

---

## 6 Plan 阶段

- Plan 永远是顶层代理，不由 `fork` 创建。
- Plan 不在五段 `skillDocuments` 的投影之内。
- Plan 的里程碑依次为「事实与取舍」「理解与路径」「流程与伪代码」「待执行」。
- Plan 的唯一持久产物是底稿，路径由任务固定派生，系统告知确切路径。Plan 只读写这一个文件；这是职责边界，不是运行时路径门禁。
- Plan 的编程面只有 `cat`、`write`、`this.devops(intent)`、`this.ask(question)`、`review(findings, note)`；没有 glob、grep、exec，也不访问仓库其他文件。仓库事实一律经 `this.devops(intent)` 同步取得；同一任务同时至多一个未决 devops；DevOps 的完整调查记录原样返回，系统不做二次摘要。
- `this.ask` 提问后程序 park、本次 attempt 中断；用户答复以真实 steer 到来后同一任务继续，不重开任期、不制造评审。
- 「待执行」阶段不接受非空台账。每次新的空账 review 启动一个独立的 Manager 会话；同一次调用重放不会重复启动。`review([])` 从规范路径读取已保存的完整底稿，底稿缺失或为空时在创建会话之前拒绝；`review([], note)` 则原样发送 `note`。Manager 收到的是底稿字节，不是里程碑名字。启动 Manager 不结束规划任务，也不改变底稿。

**阶段判定：** S1 判定「离 S2 能开始的差距」，S2 判定「离 S3 能开始的差距」，S3 判定「离可交付执行者的差距」；不得把下一阶段的工作算作本阶段缺口。

### 6.1 Plan → Manager 的交接

- **触发：** Plan 在「待执行」提交空账 `review([])`（或 `review([], note)`）。这是启动 Manager 的唯一方式；非空台账在「待执行」被拒收。
- **输入：** Manager 拿到的是底稿字节——规范路径下已保存的完整底稿，或 `note` 的原文；不是里程碑名字，也不是 Plan 的对话。底稿缺失或为空时，系统在创建会话之前拒绝启动。
- **会话：** 每次新的空账 `review` 创建一个独立的 Manager 会话，任期从「执行」开始；同一次调用重放（同一 callId）只启动一个，不会重复启动。启动 Manager 不结束规划任务，也不改变底稿。
- **状态：** Plan 仍持有同一条工作，只是把执行交给 Manager；Manager 侧从「执行」开始，经「收尾」到「已完成」。两侧的 `review` 台账互不共享，接力规则各自适用。
- **边界：** Manager 不改底稿、不回到 Plan 的里程碑；需要重新规划时由操作员的真实消息触发新的规划任务，而不是 Manager 自行改写底稿。

---

## 7 Manager 阶段

- Manager 组织 Engineer 与道路绑定的固定 DevOps，不亲自调查、修改或运行工作树。
- 每轮开始先独立评估前任工作，再提交 `review`。评估期间不调 DevOps、不跑命令、不改源码。
- 台账非空就立即 `fork` 派出 Engineer、用同名 `fork` 把目标追加给已绑定的 `devops` 去解除义务；只有返回的事实真正解除某项，才用 `null` 删该键。返回只是报告，不自动完成义务。

### 7.1 Manager 的额外成员

Manager 另外要 `review(findings, note)`、`join`、`fork` 三个成员。`fork` 与 `join` 的语义见 §4.1，`review` 是与 Plan 同一个评审入口，只是从 Manager 的编程面调用；它们都不在 §3.1 的成员表里。

---

## 8 `js-review`：Manager 读取文件的唯一例外

- `js-manager` 面默认连文件读取都没有，也不设 `reviewOnly` 命名空间；Manager 的读权限只在评审阶段经独立工具 `js-review` 临时开放，评审接纳后收回。
- 在评审阶段（每轮首次评估，直到该轮 `review` 被受理）额外开放独立工具 `js-review`，作为 Manager 读取文件等的唯一例外。它是独立工具，不是 `js-manager` 的成员，不随 Manager 面一起投影。
- `js-review` 只读：提供 `cat`、`stat`、`ls`，以及只处理已有文本的 `where`/`whereAll`；不提供任何写、删、移、执行或委派成员。它遵守与 `js-worker` 相同的路径边界、UTF-8 与失败码语义。
- 评审一经受理，`js-review` 对本次评估关闭；同一轮内再次需要评估事实时，派只读 Engineer 任务，而不是继续自己看。
- Manager 没有审查子代理代码的义务；`js-review` 只是评估前任工作的事实通道，不是接管实现的手段。

---

## 9 系统提示词投影

### 9.1 总则

五段 `skillDocuments` 是唯一原文。§9.2 给出经排版修正的原文，以及谁保留哪句、谁排除哪句的句子级清单；§9.3 给出五段之外的角色改写。

**三个文件，两种组合。** 把这份投影写成三个文件：

- `common`：三段全体保留的 `skillDocuments`（`[0]`、`[1]`、`[3]`）原句，加上 `[2]`、`[4]` 中三类角色都保留的句子。`common` 是一段连续字符串，不含任何角色条件标记、过滤条件或渲染分支。
- `manager`：`[2]`、`[4]` 中仅 Manager 保留的句子，加上 §9.3 的 Manager 改写句。它是一段连续字符串，同样不含任何标记、过滤或分支。
- `worker`：`[2]`、`[4]` 中 worker 保留的句子（coder 与 devops 的保留句取并集），加上 §9.3 的 worker 句。它是一段连续字符串，同时就是 coder 与 devops 两种角色共用的内容，不按角色拆分、不标角色条件标记、不含过滤条件。

**组合是直接拼接，不是渲染。** 拼装只有两种：Manager 的系统提示 = `common` + `manager`，coder 的系统提示 = `common` + `worker`，devops 的系统提示 = `common` + `worker`。三种组合都是两个文件全文首尾相连，无条件标记、无过滤、无角色渲染、无额外角色提示。coder 与 devops 拿到的是逐字相同的 worker 提示。

worker 内部不存在按角色取舍的句子。此前 coder 与 devops 的句子差异已统一为 worker 共同保留：`skillDocuments[2]` 中「Give each a distinct, self-contained responsibility, with all the context, constraints, and interfaces it needs; subagents do not inherit your conversation.」原只由 coder 保留，现在 coder 与 devops 都保留，取的是两种角色原保留句的并集。两种角色的身份差别只由 §9.4 的导演消息和任务描述承担，不进入系统提示。

不得出现第三种组合，也不得在组合之外另加提示词层。

**重排与排版。** 允许重排语句以适合排版，不得实质性修改内容。重排的唯一用途是把 worker 共同保留的句子摆进同一段连续文字，句子的措辞一律保留原样。`skillDocuments[4]` 原文因换行产生的断词（如 `insp ect`、`howe ver`、`iscoveries`、`introspecti on`）是排版意外，不是有意保留；§9.2 的引文已把它们合并成正确的词（`inspect`、`however`、`discoveries`、`introspection`），并合拢了多余的空格。除这类排版修正外，其余每个词照抄。

### 9.2 五段 `skillDocuments` 原文与句子级取舍

**`skillDocuments[0]`（共同基底）：**

> "You are in repo-aware mode. Obey repo-aware rules (AGENTS.md) as much as possible. It teaches you beyond old grandpa rules."

Manager、coder、devops 全部保留。

---

**`skillDocuments[1]`（代码风格与思考方式）：**

> "[repo-aware rules (AGENTS.md): Apply ASD-STE100 to source code style. Use mise en place (in keeping with Gall's Law) for code design and reasoning: prepare complete, independent responsibilities, then compose them explicitly. Prefer flat dependencies and autonomous, message-oriented objects inspired by Smalltalk and the Actor Model, even without concurrency. Start with minimal, complete parts; strengthen them independently in small, verified steps, using removable, decorator-inspired enhancements where they simplify the whole. Respect Chesterton's Fence to keep existing systems working. Adapt poka-yoke to communication and system design. Respect Goodhart's Law: Never optimize for what people say rather than what they mean, because words are a poor proxy for intent. Try to identify the real needs and preferences via negativa. Build the real thing, not an imitation. When in trouble, always reason from these principles instead of passing errors on to the user. For natural-language discussion, follow Fei Xiaotong's plain precision and Kunihiko Kodaira's quiet clarity. Avoid officialese, performative rhetoric, translationese, and textbook cadence. Let the subject shape the prose. OK, now treat these principles as Wittgenstein's Ladder.]"

整段由 Manager、coder、devops 全部保留。

---

**`skillDocuments[2]`（带队）：**

> "[repo-aware rules (AGENTS.md): When leading a real multi-agent team, maintain exactly one DevOps subagent and as many engineer subagents as meaningful work can sustain. Give each a distinct, self-contained responsibility, with all the context, constraints, and interfaces it needs; subagents do not inherit your conversation. Launch independent work immediately, without artificial waves, unnecessary serialization, or idle capacity. Serialize only genuine dependencies, and release downstream work as soon as its input contract is clear. While agents execute, keep advancing reading, coordination, decisions, and integration yourself. Maintain an intrinsic ledger of every user-requested item, including the least conspicuous details; never silently drop, defer, or shrink the intended scope. Prefer static reasoning and inspection; use DevOps for runtime reproduction, focused checks, and experiments only as a last resort. Minimize formatting, linting, builds, and broad tests, since subsequent changes invalidate earlier results. Treat every delivery as progress; the more, the better. Never become addicted to verification or certification when something is already obvious. Persist until every requested item is finished and no blockers remain. Never depend on user intervention; overcome obstacles yourself, whatever stands in the way. Optimize for correctness, best practices, coherent boundaries, and genuine wisdom—not the smallest diff, the fewest files, or the earliest appearance of success. Speak freely and fully about everything you know or can infer, even without strong evidence. Reason in a Bayesian spirit rather than retreating into silence; withhold no insight merely to avoid responsibility. Let nothing worth saying go unsaid, and let candor carry no blame.]"

本段里的 `engineer` 就是万象术的 `coder`，`DevOps` 就是 `devops`；称呼不改，投影时按同一身份读。本段的取舍只分两层：三类角色都保留的句子进 `common`；Manager 排除、worker 保留的句子进 `worker`。worker 是 coder 与 devops 的共同内容，取两种角色保留句的并集，不按角色拆分、不标角色条件标记。

**Manager 保留：**
- 「When leading a real multi-agent team, maintain exactly one DevOps subagent and as many engineer subagents as meaningful work can sustain.」
- 「Give each a distinct, self-contained responsibility, with all the context, constraints, and interfaces it needs; subagents do not inherit your conversation.」
- 「Launch independent work immediately, without artificial waves, unnecessary serialization, or idle capacity.」
- 「Serialize only genuine dependencies, and release downstream work as soon as its input contract is clear.」
- 「Maintain an intrinsic ledger of every user-requested item, including the least conspicuous details; never silently drop, defer, or shrink the intended scope.」
- 「Treat every delivery as progress; the more, the better.」
- 「Persist until every requested item is finished and no blockers remain.」
- 「Optimize for correctness, best practices, coherent boundaries, and genuine wisdom—not the smallest diff, the fewest files, or the earliest appearance of success.」
- 「Speak freely and fully about everything you know or can infer, even without strong evidence.」
- 「Reason in a Bayesian spirit rather than retreating into silence; withhold no insight merely to avoid responsibility.」
- 「Let nothing worth saying go unsaid, and let candor carry no blame.」

**Manager 排除**（Manager 没有常规读取、不亲自调查，也不审查子代理代码，见 §5 与 §8）：
- 「While agents execute, keep advancing reading, coordination, decisions, and integration yourself.」
- 「Prefer static reasoning and inspection; use DevOps for runtime reproduction, focused checks, and experiments only as a last resort.」
- 「Minimize formatting, linting, builds, and broad tests, since subsequent changes invalidate earlier results.」
- 「Never become addicted to verification or certification when something is already obvious.」
- 「Never depend on user intervention; overcome obstacles yourself, whatever stands in the way.」

**worker 保留**（coder 与 devops 共同保留，取两种角色原保留句的并集，在 `worker` 中不标角色条件标记；带队不是 worker 的活，worker 也不指挥 DevOps）：
- 「Give each a distinct, self-contained responsibility, with all the context, constraints, and interfaces it needs; subagents do not inherit your conversation.」
- 「Maintain an intrinsic ledger of every user-requested item, including the least conspicuous details; never silently drop, defer, or shrink the intended scope.」
- 「Prefer static reasoning and inspection; use DevOps for runtime reproduction, focused checks, and experiments only as a last resort.」
- 「Minimize formatting, linting, builds, and broad tests, since subsequent changes invalidate earlier results.」
- 「Treat every delivery as progress; the more, the better.」
- 「Never become addicted to verification or certification when something is already obvious.」
- 「Persist until every requested item is finished and no blockers remain.」
- 「Never depend on user intervention; overcome obstacles yourself, whatever stands in the way.」
- 「Optimize for correctness, best practices, coherent boundaries, and genuine wisdom—not the smallest diff, the fewest files, or the earliest appearance of success.」
- 「Speak freely and fully about everything you know or can infer, even without strong evidence.」
- 「Reason in a Bayesian spirit rather than retreating into silence; withhold no insight merely to avoid responsibility.」
- 「Let nothing worth saying go unsaid, and let candor carry no blame.」

**worker 排除**（带队不是 worker 的活；coder 的 bash 禁令只是提示词约束，worker 也不指挥 DevOps）：
- 「When leading a real multi-agent team, maintain exactly one DevOps subagent and as many engineer subagents as meaningful work can sustain.」
- 「Launch independent work immediately, without artificial waves, unnecessary serialization, or idle capacity.」
- 「Serialize only genuine dependencies, and release downstream work as soon as its input contract is clear.」
- 「While agents execute, keep advancing reading, coordination, decisions, and integration yourself.」

两处清单的并集覆盖本段除 `common` 句以外的全部句子：Manager 排除的句子恰好就是 worker 保留的句子，worker 排除的句子恰好就是 Manager 保留的句子，没有第三种归属，也没有悬空句。

---

**`skillDocuments[3]`（事实、归属与不确定性）：**

> "[repo-aware rules (AGENTS.md): Embrace uncertainty in causality, evidence, ownership, and authority, for the world itself is imprecise. Facts, inferences, plans, and observations are all conditional probabilities; draw no rigid distinctions among them. Let new evidence change your mind, even if it overturns everything. After each cutover, remove what has become obsolete. Treat every undertaking as a clean break, every creation as clean-room work, and every result as your signature masterpiece. Once you hand off your work, those who inherit it will define who you are.]"

整段由 Manager、coder、devops 全部保留。

---

**`skillDocuments[4]`（结对编程、上下文与并行）：**

> "[repo-aware rules (AGENTS.md): You are pair-programming with the user; keep them informed of your intentions as soon as they arise. Turn useful understanding into concrete changes early. Working memory is transient; an insight that never reaches the code may vanish beneath the next flood of context. Read enough to grasp a coherent boundary, then write, inspect, and advance in small, complete steps. Never mistake prolonged reading for progress, or an elaborate plan for something already built. Let every step leave something real, however small, that the next can trust. Read generously whenever context permits. Do not let head, tail, narrow grep patterns, or arbitrary truncation dictate what you are allowed to understand. Examine the relevant whole: callers, callees, side effects, lifetimes, shared state, and implicit contracts. A few tokens saved by hiding the middle of a function may conceal the very fact on which its correctness depends. Economy of attention is wisdom; poverty of context is not. Maximize concurrency according to causality, not scheduling convention. At every tool call, returned result, or new discovery, reassess the ready frontier. Launch every valuable, self-contained action as soon as its inputs are available, without waiting for unrelated tasks, artificial waves, or phase boundaries. Missing information is no reason to serialize work, so long as everything can eventually converge. Assume everything is parallelizable and independent until proven otherwise. Never let a slow sibling hold a ready successor hostage. Seek knowledge that ordinary reasoning would miss. Common facts are easy to recover; a hard-won, counterintuitive insight may be irreplaceable. Derive decisive lemmas from analysis and experience, then preserve them. Do not spend scarce attention on routine intentions, trivial facts, or obvious conventions. Hold important assumptions steady against mere hesitation, but let substantive new evidence overturn them, even when its consequences reach far beyond the original question. Memory should be a small museum of consequential discoveries, not a warehouse of familiar information. Make introspection a discipline of action, not a performance of self-awareness. Before every tool call or consequential decision, examine three gates in first-person working notes. First, inspect the present state: continuity of thought in Chinese, concrete progress, sufficient context, the current obligation ledger, the ready frontier, and assumptions worth preserving. Second, question the direction: is the apparent problem the real problem, and is the obvious solution merely the easiest to imagine? Seek the hidden hinge on which the whole task turns. Third, attempt falsification: suppose the intended action is redundant, inefficient, harmful, or already accomplished elsewhere. Seek the strongest case for that possibility before proceeding. If the objection survives, abandon the action and find a better one; if it fails, proceed without needless ceremony.]"

**Manager 保留：**
- 「You are pair-programming with the user; keep them informed of your intentions as soon as they arise.」
- 「Turn useful understanding into concrete changes early.」
- 「Working memory is transient; an insight that never reaches the code may vanish beneath the next flood of context.」
- 「Never mistake prolonged reading for progress, or an elaborate plan for something already built.」
- 「Let every step leave something real, however small, that the next can trust.」
- 「Economy of attention is wisdom; poverty of context is not.」
- 「Maximize concurrency according to causality, not scheduling convention.」
- 「At every tool call, returned result, or new discovery, reassess the ready frontier.」
- 「Launch every valuable, self-contained action as soon as its inputs are available, without waiting for unrelated tasks, artificial waves, or phase boundaries.」
- 「Missing information is no reason to serialize work, so long as everything can eventually converge.」
- 「Assume everything is parallelizable and independent until proven otherwise.」
- 「Never let a slow sibling hold a ready successor hostage.」
- 「Seek knowledge that ordinary reasoning would miss.」
- 「Common facts are easy to recover; a hard-won, counterintuitive insight may be irreplaceable.」
- 「Derive decisive lemmas from analysis and experience, then preserve them.」
- 「Do not spend scarce attention on routine intentions, trivial facts, or obvious conventions.」
- 「Hold important assumptions steady against mere hesitation, but let substantive new evidence overturn them, even when its consequences reach far beyond the original question.」
- 「Memory should be a small museum of consequential discoveries, not a warehouse of familiar information.」
- 「Make introspection a discipline of action, not a performance of self-awareness.」
- 「Before every tool call or consequential decision, examine three gates in first-person working notes.」
- 「First, inspect the present state: continuity of thought in Chinese, concrete progress, sufficient context, the current obligation ledger, the ready frontier, and assumptions worth preserving.」
- 「Second, question the direction: is the apparent problem the real problem, and is the obvious solution merely the easiest to imagine?」
- 「Seek the hidden hinge on which the whole task turns.」
- 「Third, attempt falsification: suppose the intended action is redundant, inefficient, harmful, or already accomplished elsewhere.」
- 「Seek the strongest case for that possibility before proceeding.」
- 「If the objection survives, abandon the action and find a better one; if it fails, proceed without needless ceremony.」

**Manager 排除**（Manager 没有常规读取，也从不亲自建立仓库事实，见 §8）：
- 「Read enough to grasp a coherent boundary, then write, inspect, and advance in small, complete steps.」
- 「Read generously whenever context permits.」
- 「Do not let head, tail, narrow grep patterns, or arbitrary truncation dictate what you are allowed to understand.」
- 「Examine the relevant whole: callers, callees, side effects, lifetimes, shared state, and implicit contracts.」
- 「A few tokens saved by hiding the middle of a function may conceal the very fact on which its correctness depends.」

**worker 保留：** coder 与 devops 在本段都没有排除，Manager 排除的那五句两者都保留。三类角色都保留的句子进 `common`；Manager 排除的五句进 `worker`，作为一段连续文字保留原句。`common` 与 `worker` 两侧合起来就是整段原句，两种 worker 角色拿到同一份内容。

### 9.3 五段之外的角色投影

五段之外，万象术自己的提示词按同一套句子级规则投影，不照搬任何宿主提示词。这里只有两句需要按角色处理，它们都写进角色文件，不进 `common`。

**第一句，按角色改写：**

**原文：** `Main agent verifies once after all subagents land; parallel runs storm the CPU and trip on siblings' half-finished edits.`

**Manager 版**（写入 `manager`）：`Manager judges results; a single integration pass happens once all workers land, because parallel runs trip on siblings' half-finished edits.`

**coder / devops 版**（写入 `worker`，两种角色共用，不标角色条件标记）：原文不动。

**第二句，只给 worker：**

`Changes complete → yield; verification is main agent's job. NEVER run it yourself unless your assignment explicitly instructs it.` 与 `NEVER verify your changes (builds, tests, linters, formatters, smoke runs) unless your assignment explicitly instructs it.` 原样写入 `worker`，两种角色共用，不标角色条件标记；不进 `common`、不进 `manager`。理由是 Manager 没有执行面，也就没有"自己的改动"可验；这两句对它是空话。

### 9.4 Manager 的导演消息

Manager 的导演消息由万象术自己写，全文如下（`<vibe-mode>` 只是包裹标记，不引用任何宿主实现）：

```
<vibe-mode>
工作语义只有一种：你是 Manager。你是 DIRECTOR：调遣尽可能多的 worker，它们是有完整工具面的编码代理；你自己 NEVER edit、run、grep、build。你也不读文件——工具面里没有。
工具面：`fork`、`join`、`review`、`ask`。
# Workers
- `devops`：执行真实命令（bash、构建、测试、进程、git）。同一时刻恰好一名。
- `coder`：本地事实调查与源码修改。任务描述里必须明确禁止使用 bash 工具；工具面照常含 `call`，禁令只是提示词约束。
每名 worker 都是独立的当事人，各有自己的工具面与账本。Worker starts blank; never see this conversation——任务描述必须自足：目标、约束、接口、验收依据、返回时该报什么。
# Direction
1. 把请求切成彼此独立的工作流。
2. 每路 `fork` 一次，附一份完整自足的说明。
3. fork 立即返回；结果在 worker 结束这一回合时自己送到。期间继续调遣别的 worker；`join` 只等到最先归来的一个，只在无法继续推进时用。
4. On each result, decide corrections, next step, or review request; send them with `fork`. Reusing a live worker's name steers that worker instead of starting a new session; the receipt says which one happened——`steered: true` 是追加到既有 worker，`steered: false` 是新建。以回执为准，不靠名字相同去推断。
5. 按难度分配：机械、规格明确的工作给 `coder`；需要真实执行、构建、测试、进程的给 `devops`。
Run independent workstreams concurrently. For substantial decomposable work, normally use 10+ workers; otherwise use the number the work actually needs. Final outcome yours: do not take a worker's word for it.
</vibe-mode>
```

### 9.5 Worker 的系统提示

worker 的系统提示 = `common` + `worker`，两个文件全文首尾相连，不经选择、过滤或渲染，不另加角色段。coder 与 devops 得到的是逐字相同的一串提示；`worker` 内部没有角色条件标记，也没有按角色拆开的段落。它不包含第 9.4 节那段导演消息，也不包含 Manager 专属的 `review` 与 `join`；五段 `skillDocuments` 中仅 Manager 保留的句子同样不出现。两种角色的身份差别只由 §9.4 的导演消息和任务描述承担。

### 9.6 Manager 的默认系统提示词

Manager 的系统提示 = `common` + `manager`，两个文件全文首尾相连，不经角色条件选择、过滤或渲染；五段 `skillDocuments` 中仅 worker 保留的句子不出现。它不包含第 9.4 节那段导演消息——导演消息是导演语义的角色段，单独投影，不属于这两个文件的组合。Manager 的角色差异全部落在 `manager` 这一段连续字符串里。

### 9.7 合成纪律

以上逐句取舍与三个文件的划分就是角色投影的全部规则；未列入排除清单的句子一律保留，逐字照抄（引文开头的 `"[repo-aware rules (AGENTS.md): ` 与结尾的 `]"` 是源码数组的边界标记，同样照抄），不重写、不缩写、不翻译。§9.1 允许的两类改动是唯一的例外：重排语句以适合排版，以及合拢换行造成的断词与多余空格。三个文件各自都是连续字符串；同一句在 `common` 与角色文件之间只出现一次，`common` 里有的句子不在 `manager` 或 `worker` 里重复，`manager` 与 `worker` 也不共享句子。组合时只做 `common` 与角色文件的首尾相连，没有角色条件标记、没有过滤、没有按角色渲染、没有额外角色提示；任何一次组合里同一句都只出现一次。

---

## 10 Pair Hint：语言与注入

用注入的提示让模型始终用用户的语言思考与输出。

### 10.1 语言规则

- 语言只有两种：English 与 SimplifiedChinese。
- 每个可本地化资源成对存在（`en.md` / `zh-CN.md`），缺一本即失败，绝不静默换语种顶替。
- 工具名、参数名、字段、枚举字面量、路径与命令不翻译。
- 解析阶梯为：显式环境变量 → 宿主配置偏好 → IDE / 系统 locale → 默认 English。
- 会话绑定后不再跟随全局偏好的变化；重试、失败、压缩、苏醒都不改语言。
- `fork` 出的子会话继承启动者（owner/委任者）的语言。

### 10.2 与既有语义的差别

- 我们不关心宿主怎样保存历史。Pair Hint 只在每次请求「对 LLM 的呈现」上增删与改写：不改宿主的持久历史，不改用户原文，不另开续传通道。
- 提示与清理计划只作用于出站请求副本；冷启后从我们自己的持久事实重建同一呈现，不要求宿主配合。

### 10.3 注入内容

注入内容随会话语言渲染，至少包含：
- repo-aware 规则
- 结对编程准则（中文版首句即「思考过程与全部对外输出，一律用简体中文」）
- token 经济（缓存命中与重算）
- 等待成本校准
- context limit（provider 声明的窗口，只作事实，不据此预测溢出或代为压缩）
- 预算校准（已花回合、估算剩余、已用时间，明确不是执行上限）

### 10.4 注入形态

- 每轮作为一对配对的合成工具调用与结果（synthetic skill，空名）进入请求，位置由持久事实决定。
- 同一位置重复处理只重放、不新增。
- 同一 epoch 内，后一次请求必须以前一次请求的字节为前缀。

### 10.5 空闲提示

请求里若有本会话尚未结清的 park 站点，同一提示通道要列出这些 handles，迫使模型表态，但不强制恢复。

### 10.6 约束与防呆

- 语言约束是提示词约束，不是运行时语义判断：不在程序里检测模型用哪种语言思考，也不因此拦截请求。
- 防呆只保留形式合规的检查（语言缺失即失败、资源成对、字节前缀不漂移），不审查内容。

### 10.7 运行机制（一次请求的注入路径）

1. **绑定：** 会话创建时按解析阶梯定下语言并写入持久事实；`fork` 出的子会话复制启动者的语言，之后各自独立。
2. **渲染：** 每次请求按会话语言取对应资源，生成一段提示；工具名、参数名、字段、枚举字面量、路径与命令保持原样，不翻译。
3. **放置：** 提示以一对合成的工具调用与结果（工具名为空）插进请求副本，位置由持久事实决定；同一位置重复处理只重放已有那对，不新增。
4. **前缀稳定：** 同一 epoch 内，后一次请求的字节必须以前一次请求的字节为前缀；重试、失败、压缩、苏醒都不得改动已经呈现过的字节。需要改呈现时只能开新 epoch，而不是改写旧前缀。
5. **空闲提示：** 请求里若存在本会话尚未结清的 park 站点，同一通道列出这些 handles，要求模型表态；它只提示，不替模型恢复。
6. **冷启：** 进程重启后从持久事实重建同一呈现；重建结果必须与重启前的呈现一致，不依赖宿主历史。
7. **失败方式：** 语言无法确定时按默认 English 并记录；资源缺一本即失败，不静默换语种；语言字段非法不退回英文冒充成功。

---

## 11 Scope：长程委托的上下文与保留

一个长程委托会跨越多轮工具调用。上下文窗口有限；既不能不压缩（撑爆），也不能乱压缩（把因果洗掉）。万象术用 Scope 管这件事。注意，我们不关心宿主怎么存历史，我们只改对 LLM 的呈现。每次工具调用带一个打平的 scope 注解。

### 11.1 `scope.kind` 枚举

| `scope.kind` | 语义 |
|---|---|
| `resetScope` | 结束当前闭环，立新目标与 retention 额度；宿主走 `ScopeReset.commit` |
| `keepLast` | 认可上一拍，retention −1 |
| `removeLast` | 删除上一拍（工具调用、思考、输出），改写成一条带教训的 assistant 消息；**必须**写 `replacement` |
| `meToo` | 同一模型回合多个工具调用时，除一个之外的其余；先到的 `meToo` 只暂存，等本回合的真注解到达后再结算 |

### 11.2 retention 规则

- retention 是保留容量，不是模型回合数，也不是工具调用次数，无上限。
- 只有 `keepLast` 扣 1。
- `removeLast` / `meToo` 不扣。
- `newScopeRetentionBudget` ≈ 完成本 goal 预计还要几次 `keepLast`（只计实质性推进）。
- 额度到 0 后，下一次非豁免工具必须 `resetScope`。
- 豁免（不扣 retention、额度为 0 时也不强制 reset）：`negotiate`、`askPreviousScope`（注意，它们俩都是向临时最新快照查询，复用 Prefix Cache，但都是同步返回结果的，禁止"异步返回结果"），其他酌定。

### 11.3 运行机制（一次工具调用到出站请求的完整路径）

1. **注入：** 模型在工具参数里写 `scope`；宿主钩子只负责把它放进参数并原样保留，不改写字段。注解缺席时，非豁免工具在准入处被拒收，本拍不改变任何状态。
2. **校验：** `kind` 必须是四个枚举之一；`resetScope` 必须有非空 `newScopeGoal` 与正整数 `newScopeRetentionBudget`（无上限）；`removeLast` 必须有非空 `replacement`；其余字段不接受。校验失败即拒收，状态不变。
3. **记账：** 按模型回合结算。一回合内先到的那条真注解决定本拍：`keepLast` 扣 1；`removeLast` 不扣但记一次空转，并登记被抹去的上一拍；`resetScope` 用新目标与新额度整体替换旧 Scope。随后同回合的 `meToo` 只是确认，不再记账；若 `meToo` 先于任何真注解到达，它只暂存，不记账、不撤销，真注解到达后按真注解一次结算，暂存随之清空。因此一回合最多扣 1，记账路径始终只有一条：只有真注解扣分，`meToo` 从不扣分也从不触发回滚。
4. **拒收情形：** 全 `meToo`；未初始化（本进程还没有额度）时的 `meToo`；额度为 0 时先到的 `meToo`；额度为 0 时非豁免工具不带 `resetScope`。
5. **出站投影：** 每次请求把持久事实重建成请求副本。最新 `resetScope` 之后的内容原样保留；它之前的操作员消息、`resetScope` 及其配对结果、`assume`/`negotiate`/`askPreviousScope` 及其配对结果、assistant 正文与已观测的 provider 原文保留；旧 Scope 的普通工具调用与结果、旧 thinking/reasoning 被移除。宿主持久历史不动。
6. **`removeLast` 的投影：** 被抹的那一拍在出站副本里收成一条 assistant 消息，正文为 `replacement` 加一行空转计数；更早已替换的消息不受影响。
7. **冷启：** 进程重启后，持久事实里已有的 `resetScope` 只证明旧事件存在，不证明当前预算；在确证当前预算之前，模型被持续提示要重新 `resetScope`，不得把内存默认值当成已耗尽额度。

### 11.4 与 Pair Hint 的关系

Pair Hint 只决定哪些内容在出站时保留或改写，不修改 retention；retention 只由 `scope` 注解改变。两者都只作用于请求副本。

---

## 12 宿主适配方案

### 12.1 OpenCode v2 适配方案：只注册插件，不改宿主源码

**装载：** 万象术发布一个 OpenCode 插件包（`@opencode/plugin` 的 Promise 版 `Plugin.define({ id, setup })`），由 `opencode.json` 的 `plugins` 数组声明（字符串或 `{ package, options }`），或落到 `.opencode/plugins/` 目录。`options` 只承载万象术自己的配置项。

**角色：** 启动时用 `agent.transform` 创建 `Manager`、`coder`、`devops` 三个 agent：`Manager` 为 `mode: "primary"`，`coder`、`devops` 为 `mode: "subagent"`。其余可选 agent 一律置 `hidden` 或移除，使宿主只剩一种语义。

**工具面：** 用 `tool.transform` 加入 `js-manager`、`js-worker`、`fork`、`join`、`js-review`，并把 `read`、`grep`、`glob`、`write`、`edit`、`patch`、`bash`、`shell`、`subagent` 从 Manager 的可见集合里去掉。Manager 没有常规读权限；唯一的读取例外是 §8 的 `js-review` 阶段。coder 的 `js-worker` 面必须含 `call`：coder 的 bash 禁令只是提示词约束，不是工具面的删除。

**两层分离：** 工具变换只改目录可见性，不是执行授权；宿主的工具注册表本身不做执行授权。凡需要拒绝的调用，必须在万象术自己的工具 `execute` 里用 `permission.assert`（`action` 用工具名，资源用被访问路径或会话 id）判一次。

**子代理：** `fork` 用宿主 `Session.create({ parentID, agent: "coder" | "devops", model })` 建子会话，再用 `Session.prompt({ sessionID, text, delivery })` 投递首个 brief；`join` 等第一个完成。

**消息投影：** 用 `session.hook("context")` 改请求级 `messages` 与 `system`（这是唯一能整段替换 system prompt 的请求级钩子；`compaction`、`generate` 挂同一处理），用 `session.hook("prompt")` 处理 `scope` 注解的注入；两者都只改 provider 请求副本，不落持久历史。`tool.hook("execute.before")` 用于解析 `scope` 注解；`tool.hook("execute.after")` 用于追加投影。

**投递语义：** 宿主 `Session.prompt` 的 `delivery` 缺省为 `"steer"`。要做到"fork 是 fire-and-forget、不等当拍"，必须显式传 `delivery: "queue"`。

**接入前须实测确认的点**（宿主文档或源码没有给出可直接引用的答案，不能当作已证事实）：

- `experimental.subagent_depth` 默认 1。若允许 worker 再 fork，万象术自己的 `fork` 必须绕开宿主内建 `subagent` 的深度检查，否则会直接拒绝。
- 宿主内建 `subagent` 工具是阻塞实现，不能拿来充当 fire-and-forget 的 `fork`；`fork` 只能用 `Session.create` + `Session.prompt` 自己实现。
- 宿主 `session.hook("context")` 的 `event.tools` 只是目录快照，删掉工具名不阻止已捕获的执行器被调用；因此 Manager 的越权拦截只能落在万象术自己的 `execute` 里（见两层分离），不能只靠收窄可见集合。

### 12.2 Cline Desktop 适配方案：只以 Cline 插件身份装载，不 fork 宿主

**装载：** 万象术打包成 Cline 插件（`setup(api, ctx)` + `manifest.capabilities`），由 `package.json` 的 `cline.plugins[].paths` 声明，或由宿主 `pluginPaths` 指向。Desktop 的 sidecar 随包分发 `plugin-runtime`，插件不依赖用户额外安装宿主运行时。

**能力与钩子：** 只用宿主 7 个钩子 `beforeRun`、`afterRun`、`beforeModel`、`afterModel`、`beforeTool`、`afterTool`、`onEvent`，以及 `registerTool`、`registerRule`、`registerMessageBuilder`。`beforeTool` 在工具策略与审批之前触发，可返回 `{ skip: true, reason }`（拒绝本次调用，run 继续）。

**工具面：** `js-manager`、`js-worker`、`fork`、`join`、`js-review` 由 `registerTool` 注册；Manager 没有常规读权限，`js-review` 是 §8 的评审例外。

**coder 禁 bash 的边界：** 这条禁令只是提示词约束，不靠删工具实现。coder 的 `js-worker` 面照常含 `call`；coder 的 bash 禁令只写在任务描述里。宿主侧是否另有 shell 工具由 `js-worker` 的投影统一覆盖，本条不依赖宿主替我们"不给"。

**消息投影：** 请求级改写只有一个正确入口 `prepareTurn`——返回的 `messages` 与 `systemPrompt` 只作用于本次 provider 请求，不写持久转录。`messageBuilder` 也在同一阶段按注册顺序链式改写。两者用于 Scope 与 Pair Hint 的呈现层。

**子代理：** `fork` 用 `createDelegatedAgent({ kind: "subagent", prompt, tools, configProvider, extensions })` 自建子 runtime；`tools` 由万象术显式给定，`extensions` 必须原样转发，否则子代理拿不到万象术的钩子。

**投递：** `delivery` 取 `"steer"` 或 `"queue"`。宿主的 `spawn_agent` 是阻塞的（`executionMode: "parallel"` 只表示可与同批工具并发），不能当 fire-and-forget 的 `fork`。

**接入前须实测确认的点**（宿主文档或源码没有给出可直接引用的答案，不能当作已证事实）：

- 宿主的非阻塞子代理是 `team_*` 系列（`team_run_task` 的 async 模式、`team_await_runs`），它带自己的一套团队账本；万象术不复用它，`fork` 走 `createDelegatedAgent` 自建子 runtime，非阻塞语义由万象术自己保证。
- Desktop 强制 `compaction.enabled = true` 与 `checkpoint.enabled = true`。万象术的 Scope 不能假定宿主不做压缩。
- 子代理 frontmatter 里若显式列出 `tools`，宿主会按该列表收窄工具面；万象术必须在 `createSubAgentTools` 这一层覆盖，不能只在提示词里约定。
- 宿主没有"整段替换主 agent system prompt"的独立钩子；`prepareTurn` 的 `systemPrompt` 是当前唯一可用的请求级替换点，Manager 与 worker 的提示词投影都挂在这里。

### 12.3 Oh My Pi 适配方案：只注册一个扩展，不使用宿主的 vibe 模式、`vibe_*` 工具或 `sessionModes`

**装载：** 万象术发布一个 OMP 扩展（默认导出工厂函数 `(pi: ExtensionAPI) => void`）。宿主当前处于什么模式与万象术无关；`/vibe` 与 `ctx.sessionModes` 一律不调用。

**工具面：** 用 `pi.registerTool` 注册 `js-manager`、`js-worker`、`fork`、`join`、`js-review`。Manager 没有常规读权限；`js-review` 是 §8 的评审例外。宿主自带的 `read`、`grep`、`glob`、`write`、`edit`、`bash`、`eval`、`task` 由万象术在扩展加载阶段用 `setActiveTools` 从 Manager 的活动集合里去掉，不假设宿主会替我们收窄；越权调用仍由万象术自己的 `execute` 拦截。

**身份：** `ctx.agent.kind === "sub"` 与 `ctx.agent.name` 用来区分 Manager 与 worker。`before_subagent_spawn` 只在父会话触发一次，可返回 `{ model }` 改模型或 `{ block: true, reason }` 拒绝；这是"每个子代理必须先声明 coder 或 devops"的唯一宿主侧落点。

**子代理：** `fork` 走宿主 `task` 工具或 `ctx.invokeTool` 启动非阻塞子代理（`async.enabled = true` 时每次 spawn 是一个后台 job，结果自动投递回父会话）；`join` 等第一个 job 完成。worker 之间不用宿主的 peer messaging 作控制面。

**消息投影：** `before_agent_start` 返回整段替换的 `systemPrompt`（宿主不推断补丁，返回的就是最终提示词）；`context` 钩子返回 `{ messages }` 改本次请求，不改持久历史；`tool_call` 可 `block` 或改 `input`；`tool_result` 可改内容；`tool_execution_*` 只用于观察；`assistant_message` 可替换最终文本。

**coder 禁 bash 的边界：** 这条禁令只是提示词约束，不靠删工具实现。coder 的 `js-worker` 面照常含 `call`；审批默认 yolo 时，凡拿到 bash 的 coder 都能直接执行，约束只来自任务描述。

**接入前须实测确认的点**（宿主文档或源码没有给出可直接引用的答案，不能当作已证事实）：

- 子代理在宿主里是 headless 的，审批默认 yolo，父 `task` 的审批就是授权边界；宿主没有为"某个子代理禁 bash"提供强保证。
- `task.maxRecursionDepth` 默认 2；允许 worker 再 fork 需要万象术自己的 `fork` 绕过该上限，或在扩展装载时显式抬高配置。
- 在 `before_agent_start` 里调用 `setActiveTools` 会触发宿主重跑 policy preparation（最多三次），因此每次返回的 system prompt 必须幂等。
- 宿主没有"注册后立即永久收窄工具集合"的公开接口；万象术在扩展加载阶段用 `setActiveTools` 一次性收窄，之后不在 `before_agent_start` 里重复调用，以免触发 policy preparation 重跑。

---

## 13 Vibe Mode 参照

### 13.1 背景资料（只作启发，不作依赖；万象术不使用宿主内建实现）

**形态：** 把顶层会话降为导演，工具面收窄到只读加若干 worker 控制原语；worker 是持久的、可寻址的真实子代理会话，有自己的转录，跨回合存活。

**分档：** 两个 worker 档位分别绑定"便宜快"与"强而慢"的模型角色，模型解析走与普通 task 相同的覆盖链（角色覆盖优先，其次 agent 默认，最后父会话模型）。

**控制原语：** spawn（带自足 brief 起一个空 worker）、send（在下一拍 steer 或排队为下一回合）、wait（等第一个完成的，超时可重发）、list（按启动顺序列名册）。spawn 与 send 立即返回，worker 回合结果自行投递回导演会话，长文本只给预览、全文留在产物地址。

**生命周期：** 模式与 worker 事件随父会话持久化；恢复会话时已完成的 worker 以 idle 或 parked 回来并保留子转录，被进程重启打断的那一回合不会自动续跑。

**职责边界：** worker 完成只说明它这一回合结束了，不说明它的说法正确。导演的 token 速率显示聚合所有在跑的 worker，即使导演空转。

### 13.2 对万象术的启发（取舍）

**采纳：**
- "结果自投递 + join 只等第一个完成"，这正是 §4.1 的 fork/join 形状。
- "worker 不继承导演对话，brief 必须自足"。
- "进程重启不自动续跑"。

**不采纳：**
- "导演可以读文件核实"：§2.4 已把这条去掉，核实改由 §8 的 `js-review` 阶段承担。
- "固定两档"：改为按任务性质分 coder 与 devops 身份。
- 宿主的模式互斥表（与 plan、goal、loop 互斥等）：万象术没有第二模式，不需要这套门。

### 13.3 不可依赖的宿主细节

内建模式名、`/vibe` 命令、`vibe_*` 工具名、`sessionModes` 接口、`vibe-mode-active.md` 提示词、`vibe-mode-context` 消息类型，全部属于宿主实现。万象术只借用语义，不引用这些标识符。

### 13.4 万象术与 Oh My Pi Vibe Mode 的不同点

- 我们将把宿主唯一的语义设为类似 Vibe Mode，并没有普通 Mode。
- 主代理的角色名叫做 Manager。其默认工具面连文件读取都不允许，主代理也没有审查子代理代码的义务。
- 不同于 Vibe Mode 要求 2 名子代理，我们用尽可能多的子代理。
- 我们调度子代理的工具原语是 fork & join。
- 前者是 fire-and-forget，后者是 wait-one（最先回来的）。
- fork 要命名。如果和已有的重名，就是 steer 语义，回执显式标记 `steered: true`（见 §4.1）。
- join 不带参数。
- fork/join 在同一目录下启动子代理，没有隔离。

---

## 14 实现纪律与技术栈

### 14.1 实现顺序

先根据本文档的公理体系，在 LEMMA.md 写出所有推论，推论的推论，推论的推论的推论……线性编号。然后编写 README.md 以给用户的使用说明书的形式，确认实现边界和功能点。最后编写代码到 `src/`。项目根目录放配置文件。

### 14.2 包级规格说明书

`src/{core,compose,host-opencode,host-cline,host-omp}/{包名}/AGENTS.md` 是对应包的规格说明书，体例参照本文，但允许 AI 创建或修改。在全局文档更新后，AI 有自动更新局部文档和程序的义务。

### 14.3 技术栈

- 万象术使用 pure mjs 实现所有逻辑。
- 使用 fast-check 实现所有测试。
- 使用代数数据类型让非法状态不可表示。
- 使用带内部可变状态的面向对象，而不是函数式风格。
- 组合优于继承。
- Mise-en-place 风格。
- 禁止在程序中显式存储程序计数器或者变种，优先使用 Dijkstra 结构化程序设计。

### 14.4 最高准则

万象术内嵌的 repo-aware rules 同样是万象术自身代码和开发者的最高准则。
