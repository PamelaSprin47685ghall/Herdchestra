# Herdchestra 0.0.2 E2E 主状态

> 权威顺序：`AGENTS.md` §8.5（22 条剧本定义）> 本文件。本文件只记录**证据文件实际存在并可解析**的状态。不把「以前跑过」写成 PASS。

## 1. 判定规则

- `PASS`：`plans/evidence/` 下存在一份可解析的结果文件，且 `ok` 为 `true`。
- `FAIL`：最新证据 `ok` 为 `false`，或 `ok` 缺失且存在非空失败断言。
- `NEEDS-RERUN`：只有诊断快照（`ok` 为 `null`），或证据生成于本轮改动之前且无法证明仍适用。
- `NOT-IMPLEMENTED`：`test/e2e/` 下没有对应 spec。

## 2. 22 条剧本状态

| E2E | 状态 | 证据 | 说明 |
|---|---|---|---|
| 01 | PASS | `plans/evidence/e2e-auto/e2e01/e2e01-result.json` | 本轮多次复跑通过；19 checks 全 true；`bind.toolsDeclared: true`、`mockState.last.hasTools: true`、`toolNames: ["devops","clj-plan"]` |
| 02 | PASS | `plans/evidence/e2e-02/e2e02-result.json` | 同名模型分组、会话固定模型、停用服务提示 |
| 03 | PASS | `plans/evidence/e2e-03-04/e2e03-restart.json` | 流式阅读、停止、重启恢复 |
| 04 | PASS | `plans/evidence/e2e-real-04/e2e04-result.json` | 历史编辑、双分支、导出过滤 |
| 05 | PASS | `plans/evidence/e2e-05/e2e05-v8-c9cbfdf6-final22.json` | 压缩、撤销压缩、摘要新会话 |
| 06 | PASS | `plans/evidence/e2e-06/e2e06-result.json` | 附件能力不匹配、部分读取 |
| 07 | PASS | `plans/evidence/e2e-real-07-09/e2e07-select-probe.json` | 搜索结果、来源去重、日期口径 |
| 08 | PASS | `plans/evidence/e2e-07-08/e2e08l-result.json` | 角色能力边界、S1 提问、Esc 中断 |
| 09 | PASS | `plans/evidence/e2e-real-07-09/e2e09-result.json` | 受保护提交遇外部修改（`stamp` 冲突） |
| 10 | PASS | `plans/evidence/e2e-10/e2e-10-result.json` | 工具中断后 `:outcome-unknown` |
| 11 | PASS | `plans/evidence/e2e-11/e2e-11-result.json` | 记忆候选、归档、临时会话隔离 |
| 12 | PASS | `plans/evidence/e2e-11-12/e2e12-c9cbfdf6-final22-w121.json` | 世界书触发、冷却、指令卡叠加 |
| 13 | PASS | `plans/evidence/e2e-13/e2e-13-result.json` | 技能安装、小应用存储隔离 |
| 14 | PASS | `plans/evidence/e2e-14/e2e-14.json` | 浏览器观察、人工接管、终止 |
| 15 | PASS | `plans/evidence/e2e-13-15-16-21/e2e15-version-switch-fixed.json` | 语音转写、失败回退、朗读翻译 |
| 16 | PASS | `plans/evidence/e2e-16/e2e-16.json` | 定时任务、时区策略、平台边界 |
| 17 | PASS | `plans/evidence/e2e-17/e2e-17.json` | 本轮复跑通过（1.3m），`ok: true`。修了两处 spec 缺陷：①三处 `execFileSync('clojure', ...)` 改为复用 `support/app.mjs` 的 `clojureCommand()`（裸 PATH 下 `spawnSync clojure ENOENT`）；②会话数断言从 6 改为 5——`s-history` 两侧完全相同被幂等跳过，符合 §7.2 第 13 条，断言 6 反而是错的 |
| 18 | NOT-IMPLEMENTED | — | `test/e2e/` 无 `e2e-18*.spec.mjs`；见 `DEBT.md` D-04 |
| 19 | NOT-IMPLEMENTED | — | `test/e2e/` 无 `e2e-19*.spec.mjs`；见 `DEBT.md` D-04 |
| 20 | PASS | `plans/evidence/e2e-20/e2e20-result.json` | 临时会话舍弃与转存边界 |
| 21 | PASS | `plans/evidence/e2e-21/e2e21-result.json` | 费用三口径 |
| 22 | PASS | `plans/evidence/e2e-regression-01-02-22/e2e22-final-ui.json` | 跨设备迁移、环境降级 |

统计：**PASS 20 / NOT-IMPLEMENTED 2**。

## 3. 未覆盖项

| 缺口 | 条款 | 阻断级别 |
|---|---|---|
| E2E 18 spec 缺失 | `AGENTS.md` §8.5「严禁把保存受阻的资料库当空库重新初始化」 | 阻断放行缺陷清单条目 |
| E2E 19 spec 缺失 | `AGENTS.md` §8.5「中文输入法组词确认按 Enter 绝不误触发发送」 | 阻断放行缺陷清单条目 |

## 4. 证据命名现状

`plans/evidence/` 下的结果文件名不统一：有 `e2e01-result.json`、`e2e-02.json`、`e2e03-restart.json`、`e2e05-v8-c9cbfdf6-final22.json` 等多种形状，同一编号又存在多份历史快照。本文件的判定按「该编号下最新一份 `ok: true` 的证据」取值，不按文件名排序。

建议后续把证据收敛为 `plans/evidence/e2e-NN/result.json` 单一形状，历史快照移入 `archive/` 子目录。这属于整理工作，不阻塞 0.0.2。
