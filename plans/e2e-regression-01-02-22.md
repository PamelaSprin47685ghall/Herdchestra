# E2E 01 / 02 / 22 回归复跑（0.0.1）

- 日期：2026-11-05 15:22 – 2026-11-06 10:30 CST
- 范围：已 PASS 条目的回归复跑；不修改源码，不执行 Git 写操作，不删除文件。
- 运行方式：独立 app 实例，data/workspace 位于 `/tmp/hc-w7-reg/data-*`、`/tmp/hc-w7-reg/work-*`；模型调用串行，使用 `flock /tmp/hc-e2e-model.lock`。
- 证据目录：`plans/evidence/e2e-regression-01-02-22/`
- 验收口径：根 `AGENTS.md` §8.5 现要求 22 条 E2E 全部自动化，并配套 mock
  模型服务与 OAuth/WebDAV/S3 mock 外部服务；手工验收只用于平台差异。本报告是
  手工回归/探索，不是新口径的最终验收；W10 自动化结果出来前不得据此判 PASS。
- 结论：**手工回归 E2E 01 PASS；E2E 02 PASS；E2E 22 PASS（手工回归；非 §8.5 自动化）**。E2E 22 的原始 `ClassCastException` 与
  `MODEL_PORT_FAILED`（`:created-at`、`:model-entry-id`）已由 W4 修复，
  B `app-check` 已 exit 0；已删除区、include-deleted 备份/恢复与 no-sync 隔离已复跑通过。
  E2E 仍未全部 PASS，不得据此打 tag。

## 1. Bundle 绑定

每次运行前记录 `resources/public/js/main.js` 的 SHA-256、mtime、size；运行后再次记录。

| 运行 | bundle SHA-256 | mtime | size | 证据 |
|---|---|---|---:|---|
| E2E 22 | `9c59f834a1dd49fb3fd085a711eac683f681a52f6e757ebcba5ecb5dd1aa19b6` | 2026-11-05 15:14:29 | 2,953,410 | `e2e22-bundle.txt` |
| E2E 01 | `ec5ceea29bb3697e8da2b80edea7caa2659c44b46204c4464cd8b4e3d97fc908` | 2026-11-05 15:29:38 | 2,953,574 | `e2e01-bundle.txt` |
| E2E 01 补充 | `ec5ceea29bb3697e8da2b80edea7caa2659c44b46204c4464cd8b4e3d97fc908` | 2026-11-05 15:29:38 | 2,953,574 | `e2e01-supplement-bundle.txt` |
| E2E 02 | `ec5ceea29bb3697e8da2b80edea7caa2659c44b46204c4464cd8b4e3d97fc908` | 2026-11-05 15:29:38 | 2,953,574 | `e2e02-bundle.txt` |

说明：E2E 22 跑在 15:14 的 bundle 上；E2E 01/02 跑在 15:29 重建后的 bundle 上。E2E 22 的失败不能直接归因于 E2E 01/02 所用的 bundle，但当前工作树仍在并行修改。

运行结束后（15:41）`resources/public/js/main.js` 又变为
`ac652de1edfde9590b5d84ebebec6ea1e3edec48ed3e4fe97ba1c3e97dedbb93`
（2,956,626 bytes）。本报告结论只绑定上表哈希；若用新 bundle 复验，必须重跑并重新记录哈希。

## 2. E2E 01 — PASS

命令与实例：

```bash
flock -w 1800 /tmp/hc-e2e-model.lock bash -c '/tmp/hc-w7-reg/run_e2e01.sh'
# app: /tmp/hc-w7-reg/data-e2e01, /tmp/hc-w7-reg/work-e2e01
# config: /tmp/hc-w7-reg/e2e01-config.edn (single, real Ollama qwen3.6:27b)
```

主脚本 `e2e01.cjs` 的 13 项断言全部为 true：

| 断言 | 结果 |
|---|---|
| `connectionVisible` / `connectionHost` | true / true（`Local Ollama (real)`、`127.0.0.1:11434`） |
| `modelBound` | true（新会话默认绑定 `qwen3.6:27b (real)`） |
| `replyNonEmpty` / `replyNotStub` / `replySemantic` | true / true / true（回复“是的，本地模型正在工作。”，35.3 s） |
| `userMessagePersisted` | true |
| `reloadKeepsUser` / `reloadKeepsReply` / `reloadKeepsModel` | true / true / true |
| `reopenKeepsUser` / `reopenKeepsReply` / `reopenKeepsModel` | true / true / true |

补充检查（同一 data-dir 重启，`e2e01_supplement.cjs`）5/5：

| 补充断言 | 结果 |
|---|---|
| 引导不要求额外注册（页面无注册/创建账户/sign up） | true |
| 工作台可见 | true |
| 草稿跨设置页往返完整保留 | true（`E2E01 草稿完整-…` 原样恢复） |
| 会话只有一条用户消息 | true（`userMessages.length = 1`） |
| 回复复制入口可见 | true（`copyButtons = 3`） |

证据：`e2e01-result.json`、`e2e01-supplement.json`、`e2e01-settings.png`、`e2e01-reply.png`、`e2e01-reload.png`、`e2e01-reopen.png`、`e2e01-supplement.png`、`e2e01-run.log`、`e2e01-supplement-run.log`。

## 3. E2E 02 — PASS

命令与实例：

```bash
flock -w 1800 /tmp/hc-e2e-model.lock bash -c '/tmp/hc-w7-reg/run_e2e02.sh'
# app: /tmp/hc-w7-reg/data-e2e02, /tmp/hc-w7-reg/work-e2e02
# config: /tmp/hc-w7-reg/e2e02-config.edn (dual: Alpha/Beta, real Ollama)
```

断言：

| 断言 | 结果 |
|---|---|
| 同名模型可区分连接来源 | true（`模型：Alpha 模型` / `模型：Beta 模型`） |
| 旧会话保留原模型 | true（切回 A 后仍 `Alpha 模型`） |
| 新会话采用新默认 | true（新会话 A 直接显示 `Alpha 模型`，无手工绑定） |
| 两会话不串投 | true（`bHasB`、`bLacksA`、`aHasA`、`aLacksB` 全 true） |
| 两回复不同 | true（Alpha 回复含 `ALPHA-…`，Beta 回复含 `BETA-…`） |
| 停用 Alpha 后提示明确 | true（“所选连接已停用；请启用连接或选择其他模型，草稿已保留”） |
| 停用后发送被禁用 | true（`sendEnabledAfterDisable = false`） |
| 停用不删历史 | true（`afterDisableText` 仍含 `ALPHA-…` 与 Alpha 回复） |

证据：`e2e02-result.json`、`e2e02-two-sessions.png`、`e2e02-after-disable.png`、`e2e02-run.log`。

## 4. E2E 22 — PASS（手工回归）

命令与实例：

```bash
bash /tmp/hc-w7-reg/run_e2e22.sh
# A: /tmp/hc-w7-reg/data-e2e22-a / work-e2e22-a
# B: /tmp/hc-w7-reg/data-e2e22-b / work-e2e22-b
# B2: /tmp/hc-w7-reg/data-e2e22-b2 / work-e2e22-b2
```

已完成：

- A 种子写入成功：`{:ok? true :sessions ["s1" "s-deleted"] ...}`。
- CLI 加密备份成功；备份回执 `:ok? true`，并记录 `FINDING: CLI backup excluded the deleted session`。
- CLI 恢复成功：`:ok? true`、`:status :complete`、`:store-observed? true`；目标 store 已创建。

失败点：

- B 实例启动尝试 1/2 均失败，错误码 `:START_FAILED`：

```text
class java.lang.Character cannot be cast to class java.util.Map$Entry
(java.lang.Character and java.util.Map$Entry are in module java.base of loader 'bootstrap')
```

- 启动失败后 cleanup 显示 store 已关闭；因此 B 的 UI 验证、`verify_store.clj`、no-sync、include-deleted 备份/恢复均未执行。
- 这是当前树的回归失败，不是环境缺少模型：B 启动前 A 已成功备份/恢复；错误发生在恢复后 app 启动的配置/状态加载阶段。
- 15:40 用当前源码对同一 B 数据目录执行
  `app-check --data-dir /tmp/hc-w7-reg/data-e2e22-b`，仍以同一
  `ClassCastException` 退出 1；证据 `/tmp/hc-w7-reg/e2e22-b-check-current.log`。
  说明该失败不是 15:14 bundle 的偶发问题，当前树仍可复现。

证据：`e2e22-seed-a.log`、`e2e22-backup-cli.log`、`e2e22-restore-cli.log`、
`e2e22-start-b.log`、`e2e22-b-check-current.log`、`e2e22-findings.txt`、
`e2e22-a-health.edn`。

说明：该条在 11:19 的旧证据中曾 PASS；本次在 15:14 bundle 上失败。不要把它写成 PASS，也不要在本轮修源码；把 `START_FAILED` 交给对应修复工人。

### 4.1 W4 修复后的复跑（2026-11-05 16:4x）

W4 已修复 `clean-model-section` / `without-nil-values` 的 `Character → Map$Entry`；
W5 已修复 `ReentrantLock` 的错误 import（`java.util.concurrent` →
`java.util.concurrent.locks`），并停止了自己的 repro 实例：

- 复跑时原 B 数据目录曾被 W5 的 repro 进程持锁（PID 3994191），未杀进程；改用
  `/tmp/hc-w7-reg/data-e2e22-b-rerun`，从同一备份重新恢复。
- CLI restore 成功：`:ok? true`、`:status :complete`、`:store-observed? true`。
- B 启动不再抛 `ClassCastException`；store 能打开，`sessions {:count 2}`。
- 但 `app-check` 仍 exit 1，剩余错误是模型端口：
  `MODEL_PORT_FAILED "configuration contains a field the adapter does not understand"`，
  第一轮完整堆栈的 `:unknown` 是连接元数据 **`:created-at`**
  （`model_adapter.config/check-keys!` → `normalize-connection`）。
- W4 第一轮剥离 `:created-at` 后第二次复跑仍失败，`path [:models 0]`、
  `:unknown [:model-entry-id]`；第二轮把 `:model-entry-id` 也剥离后，
  `app-check` 在 B 恢复目录上 **exit 0**（`e2e22-b-rerun-check3.log`）。
- B 启动已恢复健康。默认 CLI 备份排除已删除会话（`deleted-included 0`），
  所以 B 的已删除区没有可验证对象；这是备份策略，不是产品缺陷。
- 用显式 `--include-deleted true` 重新备份（`:deleted-included 1`）并恢复到
  B2：restore `:status :complete`、`:store-observed? true`；B2 UI 展开
  “已删除会话”后 `deletedVisible = true`、`deletedNotInMain = true`，
  store 中 `s-deleted` 的 `:deleted-at = 2026-11-05T02:06:49.548Z` 原样保留，
  未被静默延期。
- no-sync：A 初始 `:sessions {:count 1}`，新建临时会话后 `:count 2`；
  随后启动 B2 仍为 `:count 1`，B2 store ids 只有 `["s-deleted" "s1"]`，
  临时会话未进入 B2。受“一次一个 app JVM”限制，未做 A+B 同时在线测试；
  这是启动/存储隔离证据，不是实时双向同步测试。
- restore receipt 的权限默认值为 `:schedules :paused`、`:background-run
  :disabled`、`:auto-backup :disabled`、`:tools :disabled-needs-review`、
  `:external-write :disabled`、`:memories :pending-review`、
  `:skills :installed-needs-review`、`:connections :needs-verification`。
- 因此 E2E 22 手工回归为 **PASS**；诊断：`plans/e2e22-regression-diagnosis.md`；
  D-05 的模型投影子项已修。最终断言见 `e2e22-final.json`。

证据：`e2e22-b-rerun-check.log`、`e2e22-diag-model-config.log`、
`e2e22-diag-model-config2.log`、`e2e22-diag-read-config.log`、
`e2e22-restore-rerun.log`、`e2e22-b-rerun-check3.log`、`e2e22-b-ui.json`、
`e2e22-verify-b.log`、`e2e22-final.json`、`e2e22-final-ui.json`、
`e2e22-final-deleted.png`、`e2e22-final-read-deleted.log`、
`e2e22-final-add-temp.json`、`e2e22-final-a-before.edn`、
`e2e22-final-a-after.edn`、`e2e22-final-b2-health.edn`。

- 16:56 曾有一次隔离复跑在 restore 阶段被并行源码的临时语法错误阻断
  （`datalevin.clj: Unable to resolve symbol: rowable`）；该次不是产品结论。
  源码恢复编译后已用最终路径重跑，结果见本节。

下一步：新 §8.5 的自动化 + mock 验收仍待 W10；本节的 PASS 是手工回归。

## 5. hs_err 观察

- 运行窗口 15:22–15:38 没有新增 `hs_err`；当前仓库根为 **9** 个
  `hs_err_pid*.log`。
- 15:18 的 `hs_err_pid3871452.log`、16:52 的 `hs_err_pid4054882.log`、
  16:53 的 `hs_err_pid4063480.log` 来自其他并行 app；详见
  `plans/release-prep-0.0.1.md` §8。
- 本轮 E2E 01/02 的 app 启动/停止未产生新的 `hs_err`，但这不构成对原生崩溃的修复证据。

## 6. 复现命令摘要

```bash
# E2E 01（主 + 补充）
flock -w 1800 /tmp/hc-e2e-model.lock bash -c '/tmp/hc-w7-reg/run_e2e01.sh'
bash /tmp/hc-w7-reg/run_e2e01_supplement.sh

# E2E 02
flock -w 1800 /tmp/hc-e2e-model.lock bash -c '/tmp/hc-w7-reg/run_e2e02.sh'

# E2E 22
bash /tmp/hc-w7-reg/run_e2e22.sh
```

所有 app data/workspace 均在 `/tmp/hc-w7-reg/`；未修改源码，未执行 Git 写操作，未删除仓库文件。

## 7. W5 复验（2026-11-05 下午）

### 7.1 B 启动/恢复

- `system.clj` 的 `ReentrantLock` import 修复后，B 原 data-dir
  `/tmp/hc-w7-reg/data-e2e22-b` 用当前源码跑 `--check`：
  **EXIT 0、`:ok true`、`:component-errors {}`、`:models {:configured? true}`、
  2 个 session**。Character→MapEntry 与 MODEL_PORT_FAILED 均不再复现。
- `model-configuration` 对带 `:created-at` 的连接/模型输入已验证会 strip
  该字段（输出 `{:connections [{:id "x" :models {"m" {:id "m"}}}]}`）。
- 证据：`plans/evidence/e2e-regression-01-02-22/e2e22-check.json`。

### 7.2 UI 已删除区

- 真实 app + Chrome：会话导航抽屉的“已删除会话” details 可见，
  “加载已删除会话”按钮可点击；B 数据只有 1 个可见会话、没有已删除
  会话，因此 UI 显示空态，**“已删除会话保留在已删除区”断言未证**。
- 证据：`e2e22-deleted.json`、`e2e22-deleted.png`。

### 7.3 剩余

- E2E 22 的完整 UI 已删除区保留断言需要带已删除会话的备份；W7 复跑时
  请用该数据。
- E2E 09 真实 UI 受保护写入仍受 board `take-or-die` 行为阻断：token
  free 时发起者仍被退休（回执 `:result :attempt-won` 但
  `:retired? true :retired-agent-id` 为发起者），随后 claim 返回
  `ACTOR_INACTIVE`。这是 compose/mission 的 board 缺陷，不在 W5
  storage/compact/call 所有权内，建议登记 DEBT。
