# AGENTS.md 最新变更与 DEBT 影响（0.0.1）

- 日期：2026-11-05 16:50 CST。
- 对比基准：上一稳定快照 `/tmp/hc-agents-watch/snap-3.md`（15:32:26，sha256 `938e12d9…`）。
- 最新 `AGENTS.md`：mtime 16:41:45，940,755 B，sha256 `026f4f89dba828e4357a63ba706cf318c4b44cdee83f0b12e61118c3055b2c08`。
- 差异：**8 insertions / 8 deletions，6 个 hunk**。
- 约束：未改根 `AGENTS.md`；未回退/覆盖；未执行 Git 写操作。

## 1. 最新变更摘要

| # | 章节 | 变更 | 性质 |
|---:|---|---|---|
| 1 | §5.2 `:accept-call-terminal` | 终态集合仍为 `:succeeded/:failed/:cancelled/:outcome-unknown`；新增“存储写入失败不进入本终态集合，按 §7.1 fail-fast 终止程序” | 语义收紧：存储失败不是业务终态 |
| 2 | §7.1 存储分层 | 明确“选定 Datalevin（LMDB 后端）”；Asami/Datomic Local/Datahike/XTDB 记录为不选理由；统一窄存储接口 | 选型定案 |
| 3 | §7.1 存储保护 | “存储损坏保护”改为“存储是单点关键组件，写入只有成功一个终态”；打开/提交/刷盘/只读/磁盘满/损坏任一失败一律 **fail-fast**：立即终止程序，不重试、不降级、不继续派发；读失败同样终止 | **重大语义变更** |
| 4 | Kelivo 参考手册 | “§7.4（八组前端合同）”改为“§7.4（八段用户旅程）”；旧八组编号只作对照 | 文档口径 |
| 5 | Kelivo 映射表 | 增加“§7.4 第 N 组按旧八组编号，换算见编号对照”；跳转表改为八段旅程 | 文档口径 |
| 6 | D-32 | “损坏/满盘资料库不得当新库清空”的落点从“§7.1 存储损坏保护”改为“§7.1 存储单点关键组件与 fail-fast” | 文档口径 |

### 1.1 特别检查的章节

| 章节 | 是否有新变更 | 说明 |
|---|---|---|
| §1.1 | **无** | 十条铁律、Esc/无暂停态、终局可解释等保持 15:32 版 |
| §3 | **无** | STM 看板/父项 ready/令牌生命周期保持 |
| §4 | **无** | 工具面、`where`/`subs`/`write`、park 保持 |
| §5 | **有（§5.2）** | 仅 `:accept-call-terminal` 增加存储失败 fail-fast 边界 |
| §7.1 | **有** | Datalevin 定案 + 存储单点/fail-fast |
| §7.4 | **无正文变更** | 八段旅程正文未变；只有 Kelivo 参考手册的引用措辞从“八组”改为“八段旅程” |
| §8 | **无（§8.1/§8.5 正文）** | DEBT 引用方式未变：§8.1 第 4 条仍是“根 DEBT.md 只记缺口，关闭即删，AI 以它为工作队列” |
| §8.5 | **无** | 22 条 E2E 全自动化 + mock 模型/OAuth/WebDAV/S3 的要求保持 |
| §9 | **无** | 参考索引未变 |

## 2. 对实现/测试/DEBT 的影响

### 2.1 存储 fail-fast

- 服务端不得把存储写入失败映射成普通可重试错误、`failed` 终态或“继续派发”；必须终止程序并保留已落盘文件事实。
- `:accept-call-terminal` 不再接纳 `:storage-unavailable` 第四态；D-02 因此**关闭**（裁决记录在 `HISTORY.md`，DEBT 中删除该条）。
- 需要复核的测试/文档：任何断言“存储失败后仍继续/重试/返回 failed 终态”的用例；`src/hc/server/storage`、`compose/guards`、`app/system` 的存储错误路径；`plans/release-notes` 的存储边界描述。
- 客户端草稿/附件的 `:storage-unavailable` 是本地保存状态，与服务端存储 fail-fast 不是同一层，保留。

### 2.2 Datalevin 定案

- 不再讨论 Asami/Datomic/Datahike/XTDB 切换；存储接口、备份/恢复、测试继续围绕 Datalevin。
- 与 P0 `hs_err` 的关系：W11 的 `publisher/stop!` awaitTermination + publish-lock 修复已落地；W11 自测压力 1 test / 180 assertions、0F/0E、hs_err 7→7，但 16:52/16:53 又新增两个 nippy/arraycopy 崩溃，仍需区分正常 stop/强杀并复验（DEBT D-06）。

### 2.3 文档口径

- Kelivo 手册已改“八段旅程”；文档侧 30 个局部 AGENTS、30 处旧编号已清理，剩余 29 个源码文件/29 处注释仍引用旧 `§7.4-N`；DEBT D-10 记录，需按 `plans/spec-delta-scan-0.0.1.md` 清理。
- 24 类文案仍按旧口径出现在 10 个文档 + 1 个测试；新 §7.4 第 8 段是十类“未交付理由”。

## 3. DEBT.md 更新

当前 `DEBT.md` 12 条：

| 条目 | 状态 |
|---|---|
| D-01 `askPreviousScope`/`negotiate` 只有账本快照 | 保留（完全开放） |
| D-02 存储不可用第四态 | **关闭并删除**（新 §7.1 裁决 fail-fast，不设第四态） |
| D-03 用户编辑底稿无润色/回读 | 保留（完全开放） |
| D-04 22 条 E2E 只覆盖 3 条 | 保留并改写（Playwright + mock 服务已落地，19 条未自动化） |
| D-05 E2E 22 恢复后启动健康仍待复验 | **新增**（W4 ClassCast、W5 ReentrantLock 已修；W4 又在 `clean-model-section` 剥离 `:created-at` 等应用元数据；B app-check exit 0 待 W7 复跑确认） |
| D-06 Datalevin use-after-close SIGSEGV 仍有新崩溃 | **新增**（W11 自测压力 1 test/180 assertions、0F/0E、hs_err 7→7；16:52/16:53 又新增两个 nippy/arraycopy 崩溃，需区分正常 stop/强杀） |
| D-07 E2E 04/11/12/13/15/21 未完成子项 | **新增**（合并记录，逐项见 master status） |
| D-08 会话级技能选择 UI 缺失 | **新增** |
| D-09 新分支 UI 切模型未生效 | **新增** |
| D-10 源码注释仍引用旧 §7.4 编号 | **新增**（文档侧 30 个局部 AGENTS 已清理；剩余 29 个源码文件/29 处注释） |
| D-11 Datalevin nippy/ByteBuffer 读取仍 SIGSEGV | **新增**（JDK21 `/tmp/hc-w8/data8` nippy；JDK27 `/tmp/hc-e2e04` arraycopy；均发生在 Datalevin 返回值反序列化） |
| D-12 新建本地模型连接手动模型 `:unknown`，启用/绑定后发送禁用 | **新增**（`draft.cljc:325` → `connection.cljc:539` → `composer.cljc:212`；E2E 01 用预配置停用连接规避，新建连接表单路径未修复） |

统计：**新增 8 条（D-05–D-12），关闭 1 条（D-02），保留/改写 3 条（D-01/D-03/D-04）。**

并行更新说明（16:55 后）：其他工人又新增 D-13（Plan 账本未清空时会话永不 idle）与 D-14（`take-or-die` 获胜者被立即退休）；D-10（源码注释旧 §7.4）被删除，但 `src/` 仍有 66 处旧 §7.4 引用，需恢复 D-10 或并入 D-11。D-06 仍开放：W11 已定位 Datalevin 自带 JVM shutdown hook 绕过 `hc-shutdown → system/stop!` 的窗口，新 `unregister-jvm-shutdown-hook!` 修复中，17:00 回归 4/5 启动失败。

## 4. 当前树完整性（16:45–16:48）

- 命名空间加载：PASS（`hc.server.app.system`、`hc.server.app`、`hc.server.storage`、`hc.compose.owner`、`hc.server.history`）。
- `app-check`：PASS，exit 0，31 s，health `:ok true`、`:version "0.0.1"`、store open、gateway/fleet 正常。
- `hc.server.app.settings-test`：PASS，36 tests / 170 assertions，0F/0E。
- `hc.server.history-live-smoke-test`：PASS，1 test / 31 assertions，0F/0E（此前 3/3 失败，当前树已修复；建议再跑 3 次确认后关闭 B6）。
- `hs_err`：完整性检查时 7 → 7；随后 16:52/16:53 并行 app 新增 2 个（`hs_err_pid4054882.log` JDK27 `/tmp/hc-e2e04`；`hs_err_pid4063480.log` JDK21 `/tmp/hc-w8/data8`），当前共 9 个；不是本轮 app-check 产生。
- 并行编辑期间 `storage.clj` 曾出现 Unmatched delimiter；16:45 加载检查已恢复（`hc.server.storage` require 通过），未改源码。
- 当前树仍有 1513 个未跟踪条目（并行施工），不是最终树全量结论。

## 5. 需要其他工人跟进

| 工人 | 跟进项 |
|---|---|
| **W4** | E2E 22 恢复投影剥离 `:created-at` 等应用元数据；B 启动健康后再验 UI 已删除区/CLI 恢复；history 定点已 PASS，补 3 次稳定性 |
| **W5** | E2E 22 UI 已删除区；会话级技能选择 UI；新分支 UI 切模型；24 类→十类文案与 4 个测试断言 |
| **W6** | 存储 fail-fast 路径（写失败终止、不重试/不降级）；更新相关测试与文档；Datalevin 定案后不再评估引擎切换；D-11 nippy/ByteBuffer 读取生命周期 |
| **W9** | 文档侧 30 个局部 AGENTS 已清理；剩余 29 个源码文件/29 处注释由各包 owner 在源码批次替换；W9 负责 release-notes/release-gate-checklist/release-prep/审计报告的 24 类与手工验收统计 |
| **W10** | 补其余 19 条 E2E 自动化，优先 04/07/08/10；把 mock 服务接入全部剧本；补 E2E 01 新建连接表单路径（D-12） |
| **W11** | 完成 20 轮 start/stop 压力复验（pixi JDK21、独立 data-dir、publisher 有负载），按本实例 PID/command line 判断新 `hs_err`；验证 shutdown 超时路径；与 W6 一起看 D-11 |

## 6. 证据

- 最新 diff：`/tmp/hc-latest-agents.diff`（8+/8-，6 hunks）。
- 上一快照：`/tmp/hc-agents-watch/snap-3.md`。
- 完整性日志：`/tmp/hc-integrity-load.log`、`/tmp/hc-integrity-app-check.log`、`/tmp/hc-integrity-settings-test.log`、`/tmp/hc-integrity-history-test.log`。
- E2E22 诊断：`plans/e2e22-regression-diagnosis.md`、`plans/evidence/e2e-regression-01-02-22/e2e22-diag-model-config.log`。
- 旧规格扫描：`plans/spec-delta-scan-0.0.1.md`。
- 崩溃调查：`plans/hs-err-datalevin-0.0.1.md`。
