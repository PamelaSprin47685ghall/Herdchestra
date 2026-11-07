# 旧规格残留扫描（0.0.1）

- 日期：2026-11-05 15:55 CST。
- 基准：用户当前 `AGENTS.md`（§7.4 已改为 8 段用户旅程；§8.5 已要求 22 条 E2E 全自动化 + mock 模型/OAuth/WebDAV/S3）。
- 范围：`src/`、`test/`、`scripts/`、`package.json`、`pixi.toml`、`plans/`。
- 约束：只读扫描；未改源码；未改根 `AGENTS.md`；只新增本报告。

## 1. 扫描计数

| 模式 | 原始命中 | 去重文件 | 有效文件（排除 evidence patch/本影响报告） | 说明 |
|---|---:|---:|---:|---|
| 旧 §7.4 编号：`§7.4-1..8`、`§7.4 第 N 组` | 163 | 75 | **76 处 / 72 文件** | 64 个 `src/` 文件（34 个 `AGENTS.md` + 30 个源码注释）+ 8 个 `plans/` 报告；**test/ 0 个** |
| 旧 24 类文案引用 | 48 | 14 | **24 处 / 10 文件** | 5 个 `src/` AGENTS + 5 个 `plans/` 报告；另有 `test/hc/client/copy/failures_test.clj` 直接断言 24 类（见 §2） |
| 旧手工/真实端点验收假设 | 46 | 9 | **44 处 / 7 文件** | RUNNING/HACK 已改为“手工只用于平台差异”，属新口径；e2e-master/runbook/regression 已标注历史；release-notes/release-prep 仍有旧统计 |

说明：原始命中里的 87 处来自 `plans/evidence/agents-md-unauthorized-diff*.patch` 和 `plans/agents-md-user-edit-impact-0.0.1.md` 自身的 diff/引用；它们不是待改产品文档，已从“有效文件”中排除。

## 2. 必须改测试断言的文件（4 个，只列不改）

| 文件 | 行号 | 断言/内容 | 为什么必须改 |
|---|---:|---|---|
| `test/hc/client/copy/failures_test.clj` | 66–68 | `(is (= 24 (count failures/failure-kinds)))`、逐项 24 类完整性 | 新 §7.4 第 8 段把 24 类重组为十类“未交付理由”；需改为十类或保留 24 类内部映射并增加“24→10”映射断言 |
| `test/hc/client/swarm/ui_test.cljs` | 264, 276 | 断言 DOM 含“结果待确认”与 `aria-label="结果待确认"` | “结果待确认”是旧并列状态词，已收敛为进行中/已交付/未交付（附理由） |
| `test/hc/client/conversation/selection_dom_test.cljs` | 469 | 断言状态文案“已停止” | “已停止”是旧状态词；应改为“未交付+理由”或明确它是操作反馈而非状态 |
| `test/hc/client/conversation_test.clj` | 740 | 断言 `(:status-label message)` 为“已停止” | 同上 |

建议复核但不必定改（属操作反馈/理由，不一定是用户侧状态）：

- `test/hc/client/generated_card/ui_test.cljs:145–147`、`test/hc/client/generated_card_test.cljc:237–238`：`正在生成`/`生成未完成`。
- `test/hc/client/diagnostics/model_test.clj:284`：`正在生成脱敏预览`。
- `test/hc/client/schedule/ui_test.cljs:82`：`目标模型未配置凭据`（属“未交付理由”）。
- `test/hc/client/backup/model_test.clj:591`：`恢复未完成`。
- `test/e2e/support/ui.mjs:110`：`/正在生成|生成中/` 辅助匹配。
- `test/hc/compose/nudge/owner_integration_test.clj:315`、`test/hc/core/relay_test.clj:909`：`尚未完成评审`（Nudge 文案仍在新 spec）。

## 3. 必须改文档

### 3.1 局部 `AGENTS.md`（34 个）

以下 34 个文件仍引用旧 §7.4 编号/组号，应改为新 8 段旅程或 Kelivo 参考手册编号：

`src/AGENTS.md`、`src/hc/client/AGENTS.md`、`src/hc/client/backup/AGENTS.md`、`src/hc/client/browser/AGENTS.md`、`src/hc/client/conversation/AGENTS.md`、`src/hc/client/conversation/attachments/AGENTS.md`、`src/hc/client/copy/AGENTS.md`、`src/hc/client/diagnostics/AGENTS.md`、`src/hc/client/extensions/AGENTS.md`、`src/hc/client/files/AGENTS.md`、`src/hc/client/materials/AGENTS.md`、`src/hc/client/model_auth/AGENTS.md`、`src/hc/client/schedule/AGENTS.md`、`src/hc/client/search/AGENTS.md`、`src/hc/client/sessions/AGENTS.md`、`src/hc/client/settings/AGENTS.md`、`src/hc/client/swarm/AGENTS.md`、`src/hc/client/workbench/AGENTS.md`、`src/hc/compose/scheduler/AGENTS.md`、`src/hc/server/AGENTS.md`、`src/hc/server/app/AGENTS.md`、`src/hc/server/attachments/AGENTS.md`、`src/hc/server/backup/AGENTS.md`、`src/hc/server/browser/AGENTS.md`、`src/hc/server/diagnostics/AGENTS.md`、`src/hc/server/documents/AGENTS.md`、`src/hc/server/extensions/AGENTS.md`、`src/hc/server/files/AGENTS.md`、`src/hc/server/history/AGENTS.md`、`src/hc/server/materials/AGENTS.md`、`src/hc/server/model_auth/AGENTS.md`、`src/hc/server/scheduler/AGENTS.md`、`src/hc/server/search/AGENTS.md`、`src/hc/server/sessions/AGENTS.md`。

其中 `src/AGENTS.md`、`src/hc/client/AGENTS.md`、`src/hc/client/copy/AGENTS.md`、`src/hc/client/conversation/AGENTS.md`、`src/hc/client/backup/AGENTS.md` 还引用“24 类”，需同时改成十类或说明 24 类内部映射。

### 3.2 发布/审计文档（6 个）

| 文件 | 必须改的条目 |
|---|---|
| `plans/release-notes-0.0.1.md` | 第 71、251 行仍按 24 类统计；第 15/19/90 行手工/真实端点统计需标明“补充证据，不是主验收”，并指向 W10 自动化结果 |
| `plans/release-gate-checklist-0.0.1.md` | 24 类文案条目；E2E 手工验收表述 |
| `plans/release-prep-0.0.1.md` | 6 处“真实模型+真实浏览器/手工”统计，需改为补充证据 |
| `plans/ui-contract-audit-0.0.1.md` | 7 处 24 类文案矩阵，需按十类未交付理由重排 |
| `plans/server-audit-0.0.1.md` | 1 处旧 §7.4 引用 + 1 处 24 类 |
| `plans/client-audit-0.0.1.md` | 1 处旧 §7.4 引用 + 1 处 24 类 |

### 3.3 已更新/无需再改

- `RUNNING.md` 第 121 行、`HACK.md` 第 680–681 行已经写明“22 条 E2E 全自动化 + mock 外部服务；手工只用于平台差异；真实端点是补充证据”，符合新口径，**无需再改**。
- `plans/e2e-master-status-0.0.1.md` 第 7–8 行已声明是“2026-11-05 手工/真实端点历史证据，不是新口径最终验收”；建议保留，并在顶部指向 W10 自动化结果，不逐条改 22 行。
- `plans/e2e-0.0.1-runbook.md`、`plans/e2e-regression-01-02-22.md` 是手工执行手册/回归记录，保留为历史证据。

## 4. 可保留为历史证据的真实端点报告

以下报告保留为“**补充证据，不是主验收**”，不删除；新 §8.5 的主验收以 `test/e2e` 自动化 + mock 结果为准：

- `plans/e2e-real-01-02.md`、`e2e-real-03.md`、`e2e-real-03-04.md`、`e2e-real-04-05-06-19.md`、`e2e-real-05-06.md`、`e2e-real-07-08.md`、`e2e-real-07-09.md`、`e2e-real-07-11-12-13.md`、`e2e-real-08-10-20.md`、`e2e-real-09-10.md`、`e2e-real-10-13.md`、`e2e-real-11-12.md`、`e2e-real-14-17-18-22.md`、`e2e-real-16-21.md`（14 份）
- `plans/e2e-baseline-0.0.1.md`（环境/基线）
- `plans/e2e-0.0.1-results.md`（首轮历史）
- `plans/e2e-regression-01-02-22.md`（手工回归）
- `plans/e2e-master-status-0.0.1.md`（历史状态表，需标注自动化结果）

合计 18 份保留为补充证据；其中 `e2e-master-status` 需要加自动化链接，不是逐条改。

## 5. 旧 §7.4 组号 → 新八段旅程映射

新 AGENTS.md / Kelivo 参考手册给出的换算：

| 旧组 | 新位置 |
|---:|---|
| 1 | 旅程 1（首次启动） |
| 2 | 旅程 1.2–1.3（引导/选模型） |
| 3 | 旅程 2（日常对话） |
| 4 | 旅程 2.6 / 3.5 / 7.1 |
| 5 | 旅程 5（历史与知识） |
| 6 | 旅程 5.6 / 6.1–6.3 |
| 7 | 旅程 6.4–6.8 |
| 8 | 旅程 7 / 8（导出备份迁移 / 设置诊断） |

改写 34 个局部 AGENTS 和 30 个源码注释时，按此表替换，不要继续引用不存在的 `§7.4-N`。

## 6. DEBT.md D-01–D-04 状态

| 条目 | 当前状态 | 证据 |
|---|---|---|
| D-01 `askPreviousScope`/`negotiate` 只有账本快照 | **完全开放** | `src/hc/core/scope/scope.clj` 仍只返回 snapshot；`src/hc/compose/workspace.clj:257` 仍把 `askPreviousScope` 列为其他层处理；无 receipt/答案 steer |
| D-02 写入结果缺“存储不可用”第四态 | **已关闭** | 最新 AGENTS.md §7.1 裁决“存储是单点关键组件，写入只有成功一个终态”，任何写入失败 fail-fast；不再设第四态。客户端草稿/附件的 `:storage-unavailable` 是本地保存状态，不属该条 |
| D-03 用户编辑底稿后无润色/回读 | **完全开放** | `src/hc/core/relay/core.clj:379-410` 的 `set-draft` 只存 `{:path :content}`；`start-execution` 仍用旧 content；无磁盘重读/润色 |
| D-04 22 条 E2E 未自动化、无 mock 外部服务 | **部分关闭** | `test/e2e` 已有 Playwright 配置 + 4 个 spec（01/03/09/mock-services）；`package.json` 有 `e2e`/`e2e:core`/`e2e:mock` 脚本与 `@playwright/test`；`plans/evidence/e2e-auto/mock-services/mock-services-result.json` 的 OAuth/WebDAV/S3 mock 检查全 true；但 22 条全自动化仍未完成，`DEBT.md` 的 D-04 文字已过时 |

结论：**D-01/D-03 完全开放；D-02 已关闭（fail-fast 裁决）；D-04 部分关闭（工装与 3 条核心 spec + mock 服务已落地，22 条未全）。**

## 7. 汇总

- 旧 §7.4 引用：**72 个有效文件**（64 src + 8 plans），**0 个 test**。
- 旧 24 类引用：**10 个有效文档 + 1 个测试文件**。
- 旧手工/真实端点假设：**7 个有效文件**，其中 RUNNING/HACK 已更新，release-notes/release-prep/e2e-master 仍有历史统计需标注。
- 必须改测试断言：**4 个文件**。
- 必须改文档：**40 个文件**（34 局部 AGENTS + release-notes + release-gate-checklist + release-prep + ui-contract-audit + server-audit + client-audit）。
- 可保留历史证据：**18 份真实端点/手工报告**（补充证据，不是主验收）。
- DEBT：D-01/D-03 完全开放；D-02 已关闭；D-04 部分关闭。
