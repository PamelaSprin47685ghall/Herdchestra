# AGENTS.md 用户编辑影响评估（0.0.1）

- 日期：2026-11-05 15:40 CST。
- 状态：**已稳定**。连续 4 分钟以上哈希不变；最终以用户当前版本为准。
- 最终文件：940,068 B，mtime `2026-11-05 15:32:26 +0800`，sha256 `938e12d9d27a2db5868a7525eb4a78421100107dd9af087e81c8f3b773704b42`。
- 监视窗口：15:27:17 → 15:39:17（每 2 分钟一次，共 6 次）。
- 约束：只读监视；未写 `AGENTS.md`、未回退、未 `chmod`、未执行 Git 写操作。

## 1. 监视结果

| 检查 | 时间 | 哈希 | 大小 | 判定 |
|---:|---|---|---:|---|
| start | 15:27:17 | `b74dd43d…` | 939,286 | 起始版本 |
| 1 | 15:29:17 | `b74dd43d…` | 939,286 | 不变 |
| 2 | 15:31:17 | `b74dd43d…` | 939,286 | 不变 |
| 3 | 15:33:17 | `938e12d9…` | 940,068 | **变化**（mtime 15:32:26） |
| 4 | 15:35:17 | `938e12d9…` | 940,068 | 不变 |
| 5 | 15:37:17 | `938e12d9…` | 940,068 | 不变 |
| 6 | 15:39:17 | `938e12d9…` | 940,068 | 不变 → **稳定** |

监视期间只有一次变化（15:32:26，+782 B）。变化内容（`diff-3.patch`，6,470 B）是引用编号清理，不是结构改写：

- 把旧 `§7.5` / `§7.7` / `§7.9` 的引用改标为 `Kelivo 参考手册 §7.5` / `§7.7` / `§7.9`。
- 在 Kelivo 参考手册开头新增“旧 §7.4-N 与新八段旅程”的编号对照说明。
- 在 Kelivo §7.7 的 24 类文案表前加注：新版 §7.4 第 8 段已重组为十类“未交付理由”。
- 在 `# Kelivo 体验基线` 前补空行，修正标题拼接。

## 2. 章节级差异（相对 HEAD）

最终相对 HEAD：**1691 insertions / 280 deletions，13 个 hunk**；文件从 763,917 B 增至 940,068 B（+176,151 B）。

### 2.1 修改的规范条款

| # | 位置 | 要点 | 影响面 |
|---:|---|---|---|
| 1 | 序言 | 根 `AGENTS.md` 只能由人类操作员修改；AI 同步局部 `AGENTS.md`、源码与 `DEBT.md` | 所有工人：停止改根文件，只改局部规格/源码 |
| 2 | §1.1-1 | 持久化写明 Datalevin（LMDB）、单库多 partition、`blobs/`、`artifacts/`、`worktrees/`、`fleet/state.edn` | 存储、备份、恢复 |
| 3 | §1.1-5 | 操作员 Esc 取消 run；取消后重开是唯一介入路径；**不提供暂停态** | Owner/取消/UI |
| 4 | §1.1 末 | 新增“终局可解释”：用户侧只有正确交付或未交付+理由与教训 | UI 文案/状态 |
| 5 | §2.3 | 新增“用户可编辑底稿”；Plan 读取用户改动并润色；stamp 冲突以用户版本为准 | Plan/relay/workspace gate |
| 6 | §2.5 | Nudge 只判断回合是否结束，不评估是否原地打转 | compose/nudge |
| 7 | §3.3 | 父项回 `:ready` 后公共可接续；不监控等待时长、不升级、不唤醒 | swarm STM |
| 8 | §3.10 | 删除“带精确匹配条件的文本修改”原语；改为 `where`+`subs`+`write` | 工具面/workspace gate |
| 9 | §4.3 | `park` 是 agent 私有状态，重启重置、不写看板 | 运行时/park |
| 10 | §5.4 | 模型并发不设本地排队；429/5xx 只做有界退避 | 模型适配 |
| 11 | §7.1 | `:report-pending` 扫描排除 `park`，不标 `:outcome-unknown` | 恢复/对账 |
| 12 | §8.1 | 新增根目录 `DEBT.md` 缺口台账 | 文档/发布 |
| 13 | §8.5 | 新增 E2E 全自动化、mock 外部服务/mock 模型、不消耗真实 token | E2E/发布门禁 |

### 2.2 替换：§7.4 由“八组 UI 规范”改为“八段用户旅程”

- 旧 §7.4 的八组编号清单（视口、IME、停止、历史、记忆、浏览器、备份、无障碍、24 类文案等）被删除。
- 新 §7.4 改为 8 个 `####` 段：`1. 首次启动`、`2. 日常对话`、`3. 长任务`、`4. 中断与恢复`、`5. 历史与知识`、`6. 扩展能力`、`7. 导出、备份与迁移`、`8. 设置与诊断`。
- 每段保留“旧条款 → 新位置”的映射表；24 类失败文案重组为十类“未交付理由”，完整映射在 §7.4 第 8 段。
- 旧 §7.4 的细节被压缩/迁移到新增的 `# Kelivo 体验基线` 参考手册（§7.5/§7.7/§7.9 等），不再直接作为 §7.4 的并列条款。

### 2.3 新增：参考手册作为 H1 章节并入 AGENTS.md

| 新增 H1 | 行号（最终版） | 位置 |
|---|---:|---|
| `# Scope` | 1108 | 插在 §6.1 内、§6.2 前 |
| `# Milestones (S1 -> S2 -> S3 -> 待执行)` | 1120 | 同上 |
| `# Surface (clj-plan) and Read/Write Boundary` | 1127 | 同上 |
| `# Swarm STM Protocol (clj-worker)` | 1152 | 同上 |
| `# Kelivo 体验基线（AGENTS.md 参考资料）` | 2975 | §9 之后 |
| `# 共享文件系统 Swarm 协作协议实施参考（AGENTS.md 参考资料）` | 4485 | Kelivo 之后 |
| `# a0lite 打包实测与平台教训（AGENTS.md 参考资料）` | 5460 | 之后 |
| `# AgentZero 运行与实例基线（AGENTS.md 参考资料）` | 5731 | 文件末尾 |

这些手册原本是独立参考资料；并入后，手册内的“AGENTS.md > 本文件”变成文件内部的自指，权威顺序需要在发布文档中重新说明。

### 2.4 删除/迁移

- 删除旧 §7.4 的八组编号规范与 24 类文案的“并列状态词”表；24 类映射改到十类“未交付理由”，旧表移到 Kelivo §7.7 并标注“旧版”。
- 没有删除任何 `##` 顶层章节；新增的 8 个 `####` 是 §7.4 的旅程段。
- 旧 §7.4 的细节不是全部消失：一部分进入八段旅程，一部分进入 Kelivo 参考手册。

## 3. 对实现与 E2E 断言的直接影响

### 3.1 UI/E2E 断言需要改引用

代码与包级规格仍有大量旧 §7.4 编号引用，至少包括（以下为清理前记录的**旧编号**对照；W12 已改写局部 AGENTS 与 plans，源码注释仍待各包 owner 处理）：

- `src/hc/client/backup/AGENTS.md`：`§7.1–§7.4-8`、`§7.7`、`§7.9`
- `src/hc/client/browser/AGENTS.md`：`§7.4-7`
- `src/hc/client/conversation/attachments/AGENTS.md`：`§7.4 第 7 组`
- `src/hc/client/copy/failures.cljc`：`§7.4-8 / §7.7`
- `src/hc/client/schedule/AGENTS.md`：`§7.4-7`
- `src/hc/client/sessions/AGENTS.md`：`§7.4-5`
- `src/hc/client/settings/AGENTS.md`：`§7.4-2` / `§7.4-3`
- `src/hc/client/search/AGENTS.md`：`§7.4 第 6 组`
- `src/AGENTS.md`：`hc.client.copy.*` 仍写“24 类失败文案单一事实来源”

这些引用要按 §7.4 的新八段或 Kelivo 手册的新编号重写；否则后续工人会按不存在的组号实现。

### 3.2 用户侧状态词与 24 类文案

- 新规范把用户侧状态收敛为三种：进行中、已交付、未交付（附理由与教训）。
- 代码/测试仍出现旧状态词：`test/hc/client/swarm/ui_test.cljs` 断言“结果待确认”，`src/hc/client/schedule/model.cljc` 使用“部分完成”，`src/hc/client/conversation/attachments.cljc` 使用“正在重试”等。
- `hc.client.copy.failures` 仍按 24 类组织；新 §7.4 第 8 段改为十类“未交付理由”。测试与 UI 需要决定：保留 24 类作为内部映射，还是按十类重组。

### 3.3 §8.5：E2E 改为 mock 模型与 mock 外部服务

- 新规范要求 22 条 E2E 全部自动化，使用 mock 模型、mock OAuth/WebDAV/S3，不依赖真实第三方账号或真实付费服务。
- 当前 E2E 主要用真实 Ollama + 真实 Chrome（见 `plans/e2e-baseline-0.0.1.md`、各 `e2e-real-*.md`），`package.json` 没有 Playwright/Cypress 依赖，mock 只在单元测试层。
- `DEBT.md` D-04 已记录：22 条 E2E 未自动化、无 mock 外部服务；建议先做 E2E 01/03/09 三条核心旅程，再补其余 19 条。
- 现有真实端点证据可以保留为“补充证据”，但不能再冒充新规范要求的主验收路径。

### 3.4 §3.10：文件操作集变化

- 删除“带精确匹配条件的文本修改”专用原语；精确修改由 `where` + `subs` + `write` 组合。
- 需要检查 workspace gate、Clojure 工具面、测试与包级 AGENTS 是否仍假设存在独立文本编辑原语；相关测试应按新组合方式改写或确认已兼容。

### 3.5 存储、模型、Owner、Swarm 的连锁影响

- §1.1-1：Datalevin/LMDB、partition、`blobs/`/`artifacts/`/`worktrees/`/`fleet/state.edn` 的布局需要与存储代码、备份/恢复测试对齐。
- §5.4：模型端口不再有本地队列/信号量；重试只做有界退避，测试不应断言排队容量。
- §1.1-5 / §2.5 / §4.3：Esc 取消、无暂停态、Nudge 只判回合结束、park 私有且重启重置，影响 Owner/runtime/nudge 测试。
- §2.3 / §3.3：用户可编辑底稿与父项 ready 语义影响 relay、workspace gate、STM 测试。

### 3.6 `DEBT.md` 已出现

- `DEBT.md` 已于 15:39:53 创建，4,373 B，记录 D-01–D-04；当前未跟踪。
- 它是 §8.1 的直接产物，提交清单应新增该文件（不再只是“建议创建”）。
- D-01（askPreviousScope）、D-02（存储不可用第四态）、D-03（用户编辑底稿无润色/回读）、D-04（E2E 未自动化）都是后续工人的工作队列。

## 4. 需要跟进的具体项（按 W2–W9 推断分工）

> W5/W6/W7/W8/W9 的具体归属按现有领域推断；主代理可调整。

| 工人 | 主要领域 | 具体跟进 |
|---|---|---|
| **W2** | E2E 07/08（搜索、ask/S1） | 按新 §7.4 第 6 段与 Kelivo 手册更新搜索/来源卡断言；E2E 08 的 `.hc-ask` 断言对齐旅程 3；接入 mock 模型。 |
| **W3** | E2E 03/04（流式、历史、分支） | 按旅程 2/3/5 更新流式/编辑/分支断言；核对 §3.3 父项 ready；§3.10 文件操作组合；mock 模型。 |
| **W4** | history/outbound | 修 `history_live_smoke_test.clj:145` 真实缺陷；按 §7.1 排除 park、旅程 3/5 更新 outbound 断言；mock E2E。 |
| **W5** | 客户端 UI/文案 | 按旅程 1/2/5/8 更新 UI 合同；用户侧状态收敛为三种；24 类→十类映射；IME/停止/视口断言；清理包级 AGENTS 的旧 §7.4 编号。 |
| **W6** | 服务端存储/模型 | 对齐 Datalevin/LMDB、partition、blobs/artifacts/worktrees/fleet；§5.4 取消本地队列；§7.1 恢复扫描；处理 DEBT D-01/D-02。 |
| **W7** | Swarm/STM | 对齐 §3.3 父项 ready/不监控等待；§4.3 park 私有；更新 Swarm STM 测试与 Swarm 参考手册引用。 |
| **W8** | compose/Owner/runtime | 对齐 §1.1-5 Esc/无暂停、§2.3 用户编辑底稿、§2.5 Nudge、§4.3 park、旅程 3 长任务；处理 DEBT D-03。 |
| **W9** | 发布/文档/E2E 工装 | 建 mock E2E 工装（D-04）；维护 `DEBT.md`；澄清并入的 H1 参考手册与根 spec 的权威顺序/编号；更新 release notes、gate checklist、提交清单。 |

## 5. 证据与约束

- 监视日志：`/tmp/hc-agents-watch/watch.log`
- 起始/最终快照：`/tmp/hc-agents-watch/snap-000.md`、`snap-3.md`
- HEAD 快照：`/tmp/hc-agents-watch/head.md`
- 监视期变化：`/tmp/hc-agents-watch/diff-3.patch`（6,470 B）、`headings-3.diff`
- 历史 diff 证据：`plans/evidence/agents-md-unauthorized-diff.patch`、`-2.patch`、`-3.patch`（文件名为历史命名；内容为用户编辑过程记录）。
- 本报告未修改 `AGENTS.md`，未回退、未 chmod、未执行 Git 写操作。最终内容以用户当前版本为准；不要合并、格式化或覆盖。
