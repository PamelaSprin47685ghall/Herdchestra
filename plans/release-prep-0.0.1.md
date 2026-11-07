# Herdchestra 0.0.1 发布准备与证据核对

- 日期：2026-11-05 14:43 CST
- 基线：`HEAD 015229e28bd0fdddc14060a28f1348185c6e765b`（2026-11-03）
- 工作树：`git ls-files` 仅 1 个文件（`AGENTS.md`）；`git status --porcelain -uall` 在 14:43 快照为 1115 条，写本报告后为 1116 条，14:55 复查为 1133 条（并行施工新增 7 个测试文件、7 个计划文档、3 个证据文件、1 个疑似外部工具产物）；全部是未跟踪文件（`??`），没有已跟踪文件的修改。
- 约束：本报告不改源码，不执行 Git 写操作，不跑全量测试（W1 正在跑）；先盘点，随后按授权只改有证据支持的事实性错误，不改各 E2E 的最新结论；`HISTORY.md` 未修改。E2E 的最终状态由各修复工人的独立报告汇总。

## 0. 结论摘要

1. **版本五处一致**：`src/hc/version.cljc`、`pixi.toml`、`package.json`、`--help`、`app-check` 的 health 均为 `0.0.1`。这是当前工作树的结果，不是最终 tag 树的放行证据。
2. **E2E 仍未全部 PASS**：正确统计是 **7 条真实模型+真实浏览器 PASS（01/02/14/17/18/19/20）+ 1 条真实浏览器/store PASS（22）+ 10 条 PARTIAL + 4 条 BLOCKED + 0 条 NOT RUN**。本轮已把 master 与 release-notes 正文中的 6 修正为 7；E2E 结论不变。
3. **工作树没有临时构建产物混入未跟踪清单**：1115 个未跟踪文件（14:43 快照；写入后 1116；14:55 复查 1133）全部是产品源码、测试、构建清单、文档、发布证据，另有 1 个待确认的外部工具产物；`target/`、`.shadow-cljs/`、`out-*`、`node_modules/`、`.pixi/`、`resources/public/js/`、`chrome-profile/` 与 `hs_err_pid*.log` 均已被 `.gitignore` 忽略。发布阻断仍是“产品文件尚未提交”。
4. **本轮已修正主要事实性错误**：E2E 统计 6→7、01/02 真实 Ollama、19 的 NOT RUN 拆分、`hs_err` 4→9、`HACK.md` §8.4 旧统计、`RUNNING.md` 条件 8、`release-gate-checklist` 旧计数，以及备份恢复“完全空目录”定义（隔离复测：CLI 创建 store）。根 `AGENTS.md` §8.5 的 22 条自动化验收待 W10；剩余 E2E 复跑、最终树门禁与少量历史文档标注见 §7.3。

## 1. 版本一致性核对

测量时间：2026-11-05 14:39–14:42 CST。命令均在仓库根执行，`/tmp/pixi` 为 Pixi 0.83.0。

| # | 来源 | 命令 | 结果 | 判定 |
|---:|---|---|---|---|
| 1 | `src/hc/version.cljc` | `cat src/hc/version.cljc` | `(def version "0.0.1")` | PASS（唯一运行时源） |
| 2 | `pixi.toml` | `grep -n '^version' pixi.toml` | 第 3 行 `version = "0.0.1"` | PASS |
| 3 | `package.json` | `grep -n '"version"' package.json` | 第 3 行 `"version": "0.0.1",` | PASS |
| 4 | `--help` | `/tmp/pixi run clojure -M:server -m hc.server.app --help` | 退出 0；输出含 `Herdchestra 0.0.1 server`（前面有 Clojure WARNING） | PASS |
| 5 | `/healthz`（`app-check` 输出） | `/tmp/pixi run app-check` | 退出 0；`:ok true`、`:version "0.0.1"`、`:store {:open? true}`、`:gateway {:epoch 1 :seq 1 :max-pending 4096 :subscriptions 0 :stale 0}`、`:models {:configured? false}` | PASS |

证据日志：

- `/tmp/docs-version-help-0.0.1.log`（`--help`，退出 0）
- `/tmp/docs-version-app-check-0.0.1.log`（`app-check`，退出 0；本次临时隔离路径 `/tmp/hc-check-11713440995838864665`，每次运行不同）

说明：

- `app-check` 的 `:components :model {:configured? true}` 只表示模型端口组件存在，不代表用户已配置模型连接；顶层 `:models {:configured? false}` 才是“没有模型配置”。
- `--help` 与 `app-check` 都会打印若干 Clojure `WARNING`/`Reflection warning`，本次退出码仍为 0，不影响版本结论。
- 版本一致性只证明当前工作树；最终发布提交必须在合并树上重跑第 5 节列出的门禁。

## 2. 文档现状盘点

### 2.1 `RUNNING.md` 的八条发布条件

| 门禁 | 要求 | 本次可用证据 | 状态 |
|---:|---|---|---|
| 1 | `pixi install --locked` 在干净环境成功 | `plans/release-gate-checklist-0.0.1.md` §1 记录当前树 PASS（热缓存；冷下载未验证）；基线树另有 `/tmp/hc-baseline-clean` 复验；本次未重跑 | PASS（热缓存）/ 冷下载 NOT RUN |
| 2 | `pixi run client-install` 与 `pixi run client-release` 退出 0，静态产物存在 | `plans/release-gate-checklist-0.0.1.md` §1 记录 NOT RUN（并行构建中）；基线树记录见 `release-notes` §2 第 3、4 项；本次未重跑 | NOT RUN（并行构建中，终验重跑） |
| 3 | `pixi run app-check` 退出 0，health 含版本、存储打开与网关计数 | 本次实测退出 0，字段齐全 | PASS（本轮） |
| 4 | 真实启动后 `GET /healthz` 200、`GET /` 返回客户端 shell | `plans/release-gate-checklist-0.0.1.md` §1 记录当前树 PASS（端口 43497→43647，SIGTERM 后同 data-dir 重启）；基线树记录端口 39697；本次未重跑 | PASS（checklist 证据） |
| 5 | `pixi run test` 全量通过，记录测试/断言数、退出码与 seed | W1 正在跑；`plans/release-gate-checklist-0.0.1.md` §4.1 记录中间树 3611 tests / 23306 assertions、0F/0E、411 s；基线树 3406/22121、343 s（`HACK.md` §3.5）；最终树未跑 | 中间树 PASS / 最终树 NOT RUN |
| 6 | 发布提交干净；没有未提交/未跟踪的产品文件；版本五处一致 | 版本五处 PASS；`git ls-files` 仅 `AGENTS.md`，1133 个未跟踪文件（14:55 快照） | PARTIAL：版本 PASS，工作树 FAIL |
| 7 | 没有模型配置时启动仍健康，聊天明确提示需要选择模型；不得用假模型或静默回退 | `plans/release-gate-checklist-0.0.1.md` §1 记录当前树 PASS（`no-model-final` 证据：发送 disabled、草稿保留、`messageCount=0`）；基线树另有同类证据；本次未重跑 | PASS（checklist 证据） |
| 8 | `plans/release-notes-0.0.1.md` 如实列出已验证、`NOT RUN`/`BLOCKED` 与已知边界；E2E 尚未全部 PASS | 文件存在且总体结论正确；本轮已修正统计、01/02 端点、19 口径与 `hs_err` 计数；E2E 未全 PASS，最终树待跑 | 待复评（主要事实性错误已修） |

补充：`scripts/release-gate.sh` 自称运行“八条 RUNNING.md 门禁”，但实际步骤是 install、client-install/release、app-check、real-start、test、version、worktree inventory、release-notes 人工检查；它**没有执行 `RUNNING.md` 第 7 条的无模型探针**，第 6 条也只检查版本，不检查“发布提交干净”。该脚本与 `RUNNING.md` 的门禁编号/覆盖范围需要对齐（见第 3 节的 I-18）。`plans/release-gate-checklist-0.0.1.md` 已同步本轮计数与部分状态；引用它的 PASS 时仍要读它的日期与范围，并以 master 为准。

### 2.2 `HACK.md` 的本轮测量与复检命令

`HACK.md` 是实测账本，不是放行结论。与 0.0.1 发布直接相关的记录如下：

| 小节 | 内容 | 关键值 / 命令 | 发布含义 |
|---|---|---|---|
| §3.5 | 全量测试基线 | `/tmp/pixi run test`（`clojure -X:test:compose:server:client`）；基线树 `015229e`；退出 0；3406 tests / 22121 assertions，0 failures / 0 errors；343 s；日志 `/tmp/final-full-test3.log` | 只绑定基线树；最终树必须重跑 |
| §8.4 | 全量与真实应用冒烟 | 第 1 条基线全量 3406/22121；第 2 条真实启动端口 39697；第 3 条本轮已改为“8 PASS（01/02/14/17/18/19/20/22）、10 PARTIAL、4 BLOCKED” | 与 master 一致；见 §7.1 |
| §7 | 复检清单 | `clojure -P -M:dev:test:compose:server:client:cljs`；`test.check` quick-check；`clojure -M:test -n hc.core.pair-hint-test`；`clojure -X:test :nses '[hc.core.pair-hint-test]'`；`clojure -X:test`；`/tmp/pixi lock`；打印 `java.version`/`clojure-version` | 平台/JDK/依赖升级后重跑 |
| §13.4 / §14.4 | 备份 CLI 当前行为 | 默认备份 `:encrypted? true`/`:encryption :aes-gcm`；`--encrypt true` 退出 0；无密码且未显式 `--encrypt false` 退出 1、`:BACKUP_PASSWORD_REQUIRED`；完全空目录 restore 退出 0、CLI 创建 store（本次复测）；重复 `--mode replace` 返回 `:already-imported`；`:include-secrets` 键映射存在但未单独复检 | D1/D2/D3 已不再复现；“完全空目录”已由 §7.2 复测定稿；`--include-secrets` 仍待复检 |
| §13.5 | Pixi 校验和 | 71,427,392 bytes；SHA-256 `50571451e96f4a82cf675cd83baaf66fff9b718f0aaf153900b9faf4c29bd401`；`/tmp/pixi --version`、`sha256sum /tmp/pixi`、`stat -c '%s' /tmp/pixi` | `/tmp` 清理后需重新取得并核对 |
| §14.1 | 本地 Ollama 真实端点 | `http://127.0.0.1:11434/v1`；`qwen3.6:27b`；默认 4096 上下文对 Plan 请求返回 500，设 `OLLAMA_CONTEXT_LENGTH=16384` 后正常；`reasoning-effort "none"` | 开发/验证工装，不是发布依赖 |
| §14.2 | Chrome/Playwright | Chrome 153.0.8010.36；Playwright 1.64.1；`NODE_PATH=/tmp/hc-pw/node_modules`；`setsid` 启动 Chrome | E2E 工装，不是产品运行时依赖 |
| §14.3 | ENOSPC 注入（无需 root） | `unshare -Ur -m` + `tmpfs size=64M`；填满后附件上传 500 `ATTACHMENT_STORE_FAILED`、会话提交失败不冒充成功、释放空间后恢复 | 与 §13.3 的“未执行”冲突（见 I-08） |
| §14.5 | 属性测试规模 | 85/104 模块有属性测试引用；60 个属性测试文件；134 `defspec` + 36 `quick-check` = 170 次属性检查；组合验证 137 tests / 1566 assertions | 不宣称全部覆盖 |

复检命令（摘录，完整命令见 `HACK.md` §7、§13.4、§14.4）：

```bash
# 全量测试（最终树必须重跑）
/tmp/pixi run test

# 单命名空间 / 属性测试冒烟
clojure -M:test -n hc.core.pair-hint-test
clojure -X:test :nses '[hc.core.pair-hint-test]'

# 备份 CLI 默认加密
HC_BACKUP_PASSWORD='docs-verify-pass-123' \
  /tmp/pixi run clojure -M:server -m hc.server.backup.cli backup \
  --db-path /tmp/hc-docs-verify-final/target/db \
  --data-dir /tmp/hc-docs-verify-final/target \
  --target-root /tmp/hc-docs-verify-final/backups \
  --recovery-root /tmp/hc-docs-verify-final/target/recovery \
  --extensions-root /tmp/hc-docs-verify-final/target/extensions \
  --blob-root /tmp/hc-docs-verify-final/target/blobs \
  --workspace-store-root /tmp/hc-docs-verify-final/target/workspaces \
  --now 2026-11-05T00:00:00Z

# 重复 replace 应返回 :already-imported
# 无密码且未显式 --encrypt false 应退出 1、:BACKUP_PASSWORD_REQUIRED
```

### 2.3 `HISTORY.md` 的取舍记录

`HISTORY.md` 记录的是规则替换与代价，不是发布状态。与 0.0.1 发布证据相关的 2026-11-05 条目：

| 条目 | 裁决要点 | 对发布文档的影响 |
|---|---|---|
| 会话删除持久化与 purge 权威 | Owner 在 accepted 回执前通过 `commit-owner-session!` 提交 session；purge 只读 committed record；删除 `persist-live-deletion!` 后门 | 解释 E2E 20/17 中删除、discard、重启持久化的行为；本身不构成状态冲突 |
| Sente 出站批量与快照重发 | 发布器改为增量 delta；完整快照只用于首次/订阅基线/resync；1 MiB 边界不提高 | 解释 E2E02 的 `model-availability` 未确认与 `SENTE_MESSAGE_TOO_LARGE` 根因；不改变 E2E02 的 PASS |
| 备份 CLI 加密默认值 | 缺省 `:encrypt?` = 未指定，core 默认加密；只有显式 `--encrypt false` 才关闭；CLI 映射 `:encrypt?`/`:include-secrets?` | 与 `release-notes` §4.4 D1/D2 的“已修复”一致 |
| 停止命令 command-id | 改为 `cmd-<random-uuid>`；本地 `:sequence` 只负责 UI 排序；文末写“真实点击停止路径尚未在 0.0.1 重跑，发布说明仍把它标为未复验” | 与 master 的“01 PASS”及 `release-notes` 的 D7 描述存在覆盖范围歧义（见 I-16） |
| 离线恢复目标 store | 文末写“CLI `restore` 要求目标 store 已存在；对完全空目录报 `STORAGE_NOT_FOUND`”；需先用 `app-check`、已有数据目录或 `storage/open-store {:create? true}` 建 store | 与 `release-notes` “全新目录 restore 退出 0 / CLI 初始化”冲突（见 I-11） |

更早的 §9 设计决策（D01–D16）与 2026-11-04 fleet 装配记录不直接影响 0.0.1 的发布判定；本报告只列与发布证据相关的条目。

### 2.4 `plans/release-notes-0.0.1.md` 的已验证 / NOT RUN / BLOCKED 清单

现状结构：

- §2 “已验证项”：14 项，覆盖版本、Pixi 锁定安装、客户端依赖/构建、app-check、全量测试基线、真实启动、无模型门槛、离线备份、客户端规模、两万字草稿、网关安全、小应用生成与存储隔离。所有数字均标为基线树 `015229e` 的测量值；最终树必须重跑。
- §3 “E2E 状态”：22 行逐条表，结论为 7 真实模型+浏览器 PASS、1 浏览器/store PASS、10 PARTIAL、4 BLOCKED、0 NOT RUN；明确“E2E 尚未全部 PASS”。
- §4 “明确 NOT RUN / BLOCKED”：4.1 已 PASS 的 14/17/18/22 及已修 finding；4.2 性能/客户端规模（真实浏览器滚动、阅读位置、会话内查找等）；4.3 发布形态、平台、硬件、屏幕阅读器、真实设备；4.4 备份/恢复 D1–D4 与边界；4.5 UI findings 与稳定性风险；4.6 八条未挂载/未交付缺口。
- §5 已知边界；§6 发布前必须完成；§7 复现命令与证据索引。

总体结论“E2E 尚未全部 PASS”与 master 一致，必须保留。需要修正的是统计正文、01/02 端点说明、19 的 NOT RUN 拆分、`hs_err` 数量与备份恢复措辞，见第 3 节。

另：`plans/release-0.0.1-baseline.md` 是 2026-11-05 09:xx 的基线报告，其中“E2E 01–22 全部 NOT RUN”“G8 FAIL”是当时状态；不要把它当当前 E2E 清单。当前状态以 master 与本报告为准。

## 3. 文档不一致清单

> 严重度：**P0** = 影响发布说明真实性或放行判定；**P1** = 会造成读者误读，需在打标前修；**P2** = 措辞/重复，随手修。
>
> 本节保留 2026-11-05 14:43 的修正前快照。本轮已按证据修正其中一部分（见 §7）；未在 §7 标为“已修”的条目仍待处理。

### 3.1 E2E 统计与状态

| ID | 文件 / 位置 | 现表述 | 冲突或权威事实 | 建议动作 | 严重度 |
|---|---|---|---|---|---|
| I-01 | `plans/e2e-master-status-0.0.1.md` §3 正文（约第 75 行） | “只有 **6** 条是真实模型+真实浏览器 PASS，22 是真实浏览器/store 迁移 PASS；其余 14 条为 PARTIAL 或 BLOCKED。” | 同节表格（第 67 行）明确 **7** 条：01、02、14、17、18、19、20；§4 也写“01/02/14/17/18/19/22 已 PASS”。7+1+10+4=22。 | 正文 `6` 改为 `7`；保留“22 是浏览器/store PASS；其余 14 条 PARTIAL/BLOCKED”。 | P0 |
| I-02 | `plans/release-notes-0.0.1.md` §3 统计（约第 83 行） | “真实模型+真实浏览器 PASS **6**（01/02/14/17/18/19）” | 文件头醒目状态写 **7**；master 表格写 **7**（含 20）；§3 自己的表格第 20 行是 PASS。 | 改为 **7（01/02/14/17/18/19/20）**。 | P0 |
| I-03 | `plans/release-notes-0.0.1.md` §3 结论（约第 111–112 行） | “打标前必须补齐 03/04/05/06/07/08/09/10/11/12/13/15/16/**20**/21 的未跑子项。” | 20 在 master 与本文表格中均为 PASS，无剩余 E2E 子项。 | 从“补齐”清单删除 20；保留 03/04/05/06/07/08/09/10/11/12/13/15/16/21（14 条）。 | P0 |
| I-04 | `plans/release-notes-0.0.1.md` §6 第 3 项（约第 311 行） | “重跑 E2E 03/…/21，并复验 D7 的真实点击停止路径；**14/17/18/19/22 已 PASS**。” | 20 也是 PASS；01/02 也是 PASS。该句只列了 5 条 PASS，容易被读成 20 仍需重跑。 | 改为“01/02/14/17/18/19/20/22 已 PASS；重跑 03/04/05/06/07/08/09/10/11/12/13/15/16/21；D7 点击停止另列”。 | P1 |
| I-05 | `plans/release-notes-0.0.1.md` §4.3（约第 153 行） | “**屏幕阅读器、200% 缩放、对比度、真实输入法组合态：NOT RUN**。E2E 19 只覆盖自动化 DOM/模型层。” | 同文 §3 第 19 行与 master 第 19 行均写“IME/焦点/Escape/200% 字号 PASS；屏幕阅读器朗读顺序、真实触屏未跑”。200% 与 IME 不是 NOT RUN。 | 拆开写：200%/IME/焦点/Escape = PASS（自动化 DOM/模型层）；屏幕阅读器朗读顺序、对比度、真实触屏 = NOT RUN。 | P0 |
| I-06 | `plans/release-notes-0.0.1.md` §4.3（约第 161–162 行） | “E2E 01/02 使用本地 OpenAI-compatible stub（`http://127.0.0.1:18081/v1`，模型 `stub-model`），**不是真实模型**；本机 Ollama `127.0.0.1:11434` 不可达。” | 文件头与 §3 表格写 01/02 是真实模型 PASS；`/tmp/hc-e2e-real/artifacts/e2e01/e2e01-result.json` 显示 `Local Ollama (real)`、`Qwen3.6 27B (real)`、`replyNotStub: true`；`/tmp/hc-e2e-real/README.md` 明确“真实模型 + 真实浏览器 E2E 01–03”。stub 是更早批次（`HACK.md` §13.1）的端点。 | 改为“首轮 08:11 批次使用 stub；01/02 后续用真实本地 Ollama 复跑 PASS；真实云供应商质量/费用/错误行为仍未验证”。同时给 `HACK.md` §13.1 加“仅指首轮批次，已被后续真实端点复跑覆盖”。 | P0 |
| I-07 | `HACK.md` §13.1（约第 676–691 行） | “E2E 模型端点是本地 stub … 不是真实供应商”；“E2E 01/02 的模型质量、真实费用、真实供应商流式/错误行为未验证。不要把 stub 的固定回复当作真实模型验收。” | 该节描述的是 2026-11-05 08:11 首轮批次；后续 `/tmp/hc-e2e-real` 已用真实 Ollama 跑 01/02。当前文字没有时间/批次范围，读者会把它当成 01/02 的最终状态。 | 在 §13.1 顶部加“本节仅记录首轮 stub 批次；01/02 的真实端点复跑见 `/tmp/hc-e2e-real/README.md` 与 `plans/e2e-master-status-0.0.1.md`”；不要把“01/02 使用 stub”写成当前事实。 | P0 |
| I-08 | `HACK.md` §13.3（约第 714–731 行） | “ENOSPC 注入：未执行”；“真实 ENOSPC 需要文件系统配额或小 tmpfs；runbook 记录的备选机制是挂载 `tmpfs size=1M`，但需要 root/sudo，本轮未执行。” | 同文 §14.3 已记录“无需 root”的 `unshare -Ur -m` + `tmpfs size=64M` 实测；`release-notes` §4.1 与 master 第 18 行均写真实 ENOSPC + 恢复 PASS。 | 将 §13.3 标为“首轮未执行，后续 §14.3 已执行”；或合并到 §14.3，避免同一文档同时说“未执行”和“已实测”。 | P1 |
| I-09 | `plans/release-notes-0.0.1.md` §4.5（约第 213 行）与 §6 第 5 项 | “**4 个** JVM `SIGSEGV` 崩溃日志”，列 `hs_err_pid2905205.log`、`3003787`、`3597664`、`455536`；§6 说“确认 4 个”。 | 当前仓库根有 **7 个** `hs_err_pid*.log`：新增 `hs_err_pid1928617.log`（12:07）、`hs_err_pid3566148.log`（14:23）、`hs_err_pid3871452.log`（15:18）。 | 改为“截至本报告 7 个；早期 4 个 + 新增 3 个；来源均未追查”。不要只删除日志。 | P1 |
| I-10 | `plans/release-notes-0.0.1.md` §4.4（约第 182–184 行） | “备份恢复需要停机窗口；活服务拒绝在线整库恢复。维护恢复走受监督入口，客户端以 `:backup/maintenance-status` 终态为准。”出现两次。 | 重复段落。 | 删除重复的一条。 | P2 |
| I-11 | `HISTORY.md` 离线恢复目标 store vs `plans/release-notes-0.0.1.md` §2 第 10 项/§4.4/§5 vs `HACK.md` §13.4/§14.4 | HISTORY：“CLI `restore` 要求目标 store 已存在；对完全空目录报 `STORAGE_NOT_FOUND`。当前流程先用 `app-check`、已有数据目录或 `storage/open-store {:create? true}` 建 store，再执行恢复。”<br>release-notes：“全新目录 restore 退出 0、`:status :complete`（CLI 自带 `:no-prior-app-commands? true`）”；“全新目录由 CLI 初始化”。<br>HACK §13.4 的步骤明确“先用 `storage/open-store` 建 store”，§14.4 的“全新目录恢复”命令又没有显示建 store。 | “全新目录”与“完全空目录”没有定义，三处对 CLI 是否创建 store 的说法不能同时成立。 | 先统一术语：**完全空目录（无任何 store）** vs **已初始化 store 的新目录**。用一次真实 CLI 复测确认 `restore` 是否创建 store；然后把 HISTORY、release-notes、HACK 三处改成同一句。不要把“全新目录恢复失败”或“CLI 自动建库”写成未经验证的承诺。 | P0（发布说明真实性） |
| I-12 | `RUNNING.md` 发布条件 8（约第 118–120 行） | “01 PASS **待 binding 复跑确认**” | master 第 01 行：“binding 修复后 13/13 断言，无 workaround”；`/tmp/hc-e2e-real/README.md`：“binding 修复后，无默认绑定 workaround”。 | 改为“01 PASS（binding 修复后 13/13）”。 | P0 |
| I-13 | `RUNNING.md` 发布条件 8（约第 120–121 行） | “07/11/12/13 PARTIAL/BLOCKED（**扩展页崩溃已修，上下文面板与记忆候选待服务端交付**）” | master：07 BLOCKED（搜索选择传播未通）；11 BLOCKED（服务端 suggest 3 候选/159 s **PASS**，UI bundle `react is not defined`）；12 PARTIAL（上下文面板**已实现**，多轮计数/冷却未跑）；13 PARTIAL（小应用生成/存储隔离服务端 PASS，UI/IndexedDB 待复跑）。 | 改为逐条状态，删除“上下文面板与记忆候选待服务端交付”；11 的阻塞是 UI bundle，不是服务端 suggest。 | P0 |
| I-14 | `RUNNING.md` 发布条件 8（约第 120 行） | “扩展页崩溃**已修**” | `release-notes` §4.5 第 7 条明确：“源码已补 React require；当前 bundle 在 `/`、`/#/materials`、`/#/settings` 加载无错误；但小应用打开/生成后的页面尚未复验。**不得写‘已修复’**，只能写‘首页/材料/设置已加载，小应用页待复验’。” | 改为“扩展页 React keyword child 崩溃已修，面板可开；小应用页 `react is not defined` 仍待复验，不得写已修复”。 | P0 |
| I-15 | `RUNNING.md` 发布条件 8（约第 118–119 行） | “14/17/18/22 已 PASS（**含 finding**），19 PASS” | master：14/17/18/22 “无剩余 E2E 子项”；19 PASS 但“屏幕阅读器朗读顺序、真实触屏未跑”。 | 改为“14/17/18/22 PASS（相关 finding 已修，无剩余 E2E 子项）；19 PASS（屏幕阅读器顺序/真实触屏未跑）”。 | P1 |
| I-16 | `HISTORY.md` 停止命令条目（约第 243 行） vs master 01 / `release-notes` §4.5 D7 / §6 第 3 项 | HISTORY：“真实点击停止路径尚未在 0.0.1 重跑，发布说明仍把它标为未复验。”<br>master 01：“PASS（13/13）；无（停止路径的 D7 UI 首 chunk/标签仍单列）”。<br>release-notes D7：“服务端停止/迟到文本路径 PASS；UI 仍只渲染首个 chunk、不显示字面‘正在停止/已停止’”；§6 仍要求“复验 D7 的真实点击停止路径”。 | 01 的 13 项断言（`e2e01-result.json`）不包含停止路径；停止路径的 UI 渲染仍有 D7 缺口。三处不是同一层面的“停止”：服务端停止/迟到文本 PASS，UI 点击停止/首 chunk 渲染未完全通过。 | 在 master 01 行与 release-notes 01 行加覆盖范围注：“01 的 13/13 覆盖连接/回复/持久化，不含 D7 点击停止 UI；D7 另列待复验。”不要用“01 PASS”覆盖 D7。 | P1 |
| I-17 | `plans/docs-version-audit-0.0.1.md` §6/§7/§9 | 该报告仍写“18 PASS / 4 BLOCKED”“4 个 `hs_err`”“约 965 个未跟踪文件”“E2E 14/17/18/22 仍 BLOCKED”“D6/D9 仍无通过证据”。 | 当前 master 是 7+1+10+4+0；14/17/18/22 已 PASS；`hs_err` 9 个；未跟踪 1133 个（14:55 复查）；D6/D9 已在 release-notes 标为已修。 | 在报告顶部加“历史审计，已被 `plans/e2e-master-status-0.0.1.md` 与本报告取代”；或更新 §6/§7/§9 的过期数字。不要把该报告当当前发布清单。 | P1 |
| I-18 | `scripts/release-gate.sh` usage 与 `RUNNING.md` 八条 | 脚本说“Runs the eight RUNNING.md gates”，实际 gate 6 是 version consistency，gate 7 是 worktree inventory；没有执行 `RUNNING.md` 第 7 条的无模型探针；第 6 条的“发布提交干净”只记录不失败（脚本只警告 untracked）。 | `RUNNING.md` 第 6 条 = 干净工作树 + 版本一致；第 7 条 = 无模型门槛；第 8 条 = 发布说明。脚本的编号与覆盖范围不同。 | 二选一：给脚本补 no-model 步骤并把 gate 编号对齐 RUNNING；或在脚本 usage 与 RUNNING 中写明映射（例如“gate 7 = worktree inventory，无模型探针需手工执行”）。 | P1 |
| I-19 | `plans/release-notes-0.0.1.md` §4.1 标题 | “E2E 14、17、18、22 的 **findings**” | master 这四条均“无剩余 E2E 子项”；相关 D6/D9/ENOSPC 文案 finding 已修。标题容易被读成仍有未解决 finding。 | 改为“E2E 14、17、18、22 的已修 finding 记录”。 | P2 |
| I-20 | `plans/e2e-master-status-0.0.1.md` 第 03 行 vs `/tmp/hc-e2e-real/README.md` | master 03：“Plan 强制工具、无长文本流；流式渲染缺陷已修，**待重跑选区/滚动/停止**”。<br>README：“**停止路径 PASS**，多 chunk 渲染 FAIL”；停止后 15 s 内 0 次请求、内容保留、迟到不追加；选区/滚动是弱证据（UI 只渲染 1 chunk）。 | 03 仍应 BLOCKED（多 chunk/长文本未过），但“停止”已被实测通过；“待重跑停止”与 README 不一致。 | 待 E2E 03 修复工人确认后，把 master 03 改为“停止路径 PASS；多 chunk/长文本 FAIL；选区/滚动弱证据”；不要在本报告改判 E2E。 | P2（待确认） |
| I-21 | `plans/release-gate-checklist-0.0.1.md` §1/§4/§5 | 该清单仍写“14/17/18/22 PASS（含 finding）、19 PASS、**01 待复跑**、16 PARTIAL、21 BLOCKED、07/11/12/13 PARTIAL/BLOCKED”；gate 8 “PASS（文档）”；未跟踪 1050（§5 又写 1026）；5 个 `hs_err`、18 个 `out-*`；并称“当前未发现剩余的‘同一事实两种当前值’冲突”。 | master：01 PASS（binding 修复后 13/13）、07/11 BLOCKED、12/13 PARTIAL、20 PASS；当前 9 个 `hs_err`、1133 个未跟踪（14:55 复查）、20 个 `out-*`；release-notes 仍有第 3 节的统计/端点/NOT RUN 冲突，gate 8 不能算 PASS。清单自身 1050 与 1026 也不一致。 | 把该清单标为“2026-11-05 中间快照”，更新 E2E 状态与计数，或直接指向 master + 本报告；不要用它的 gate 8 PASS 作为放行依据。 | P0 |
| I-22 | `HACK.md` §8.4 第 3 条（约第 298 行） | “E2E 01–22：`[MEASURED]` 2026-11-05 已实跑/取证，结果 **18 PASS / 4 BLOCKED（14、17、18、22）**；多数 PASS 为契约/组件级，不等于完整 UI 通过。” | 该段是首轮批次的旧统计；master 最新是 7 真实模型+浏览器 PASS + 1 浏览器/store PASS + 10 PARTIAL + 4 BLOCKED，14/17/18/22 已 PASS。当前文字没有“首轮/已过时”标记，会被当成当前状态。 | 改为“首轮批次 18/4（已过时）；最新逐条状态见 `plans/e2e-master-status-0.0.1.md`”；或把 §8.4 第 3 条改成指向 master 的一句话。 | P0 |

### 3.2 “E2E 尚未全部 PASS”结论核对

- master、`release-notes` 文件头、`RUNNING.md` 条件 8 都明确“E2E 尚未全部 PASS”。这一结论正确，必须保留；不得改写成“全部通过”。
- 唯一需要修正的是**统计正文**：正确数量是 7 条真实模型+浏览器 PASS（01/02/14/17/18/19/20），不是 6；20 不应出现在“待补齐”清单。
- `release-notes` §3 提到的“早先批次 18 PASS / 4 BLOCKED”已明确标为过时；保留为历史说明即可，但 `plans/docs-version-audit-0.0.1.md` 仍把 18/4 当当前状态，需要标历史（I-17）。

### 3.3 文档内部冲突的优先级

1. **P0（发布说明真实性）**：I-01、I-02、I-03、I-05、I-06、I-07、I-11、I-12、I-13、I-14、I-21、I-22。
2. **P1（读者误读/门禁覆盖）**：I-04、I-08、I-09、I-15、I-16、I-17、I-18。
3. **P2（措辞/重复）**：I-10、I-19、I-20（待 E2E 工人确认）。

本轮已修正或复测定稿：I-01、I-02、I-03、I-04、I-05、I-06、I-07、I-08、I-09、I-11、I-12、I-13、I-14、I-15、I-21、I-22。仍待处理：I-10（重复段落）、I-16（D7 停止路径口径）、I-17（旧审计）、I-18（脚本门禁映射）、I-19（标题措辞）、I-20（E2E 03，待修复工人）。

## 4. 工作树分类

### 4.1 总览

| 项 | 数量 | 说明 |
|---|---:|---|
| 已跟踪文件 | 1 | 仅 `AGENTS.md` |
| 已跟踪文件的修改 | 0 | `git status --porcelain -uall` 全部是 `??`，无 ` M`/`A`/`D` |
| 未跟踪文件 | 1115（14:43）/ 1133（14:55） | 并行施工仍在增加；见下表 |
| 已忽略条目 | 33497（14:43 快照） | `git status --porcelain --ignored` 的 `!!` 条目；包括构建产物、依赖、浏览器 profile、崩溃日志 |
| 临时构建产物混入未跟踪清单 | 0 | 所有构建产物均被 `.gitignore` 命中 |

快照文件：`/tmp/hc-git-status-0.0.1.txt`（14:43，1115 条）、`/tmp/hc-git-status-now4.txt`（14:55，1133 条）。

按任务口径分类：

| 口径 | 数量 | 内容 |
|---|---:|---|
| 产品文件（含测试、资源、构建清单、运行文档、脚本） | 1007 | `src/` 550、`test/` 439（含新增 7 个测试）、`resources/` 6、构建清单 7、运行/历史文档 4、`scripts/` 1 |
| 发布证据 | 84 | `plans/evidence/` 的 PNG/JSON/TXT/LOG/HTML/EDN/CJS/MD/JS |
| 计划/报告 | 41 | 原 34 + 6 个 E2E 诊断/基线文档 + 本报告 `plans/release-prep-0.0.1.md` |
| 待确认外部产物 | 1 | `机制提炼/AGENTS.md`（222,246 bytes；omp/pi-goal-x 主题 shim，不是 Herdchestra 产品/证据；建议确认后移出仓库或单独忽略，不要直接提交） |
| 临时构建产物（未跟踪） | 0 | 全部命中 `.gitignore`；详见 4.3 |
| 应忽略（已在 `.gitignore`） | 33497 条 | `.pixi/`、`target/`、`node_modules/`、`.shadow-cljs/`、`out-*`、`.cpcache/`、`resources/public/js/`、`chrome-profile/`、`hs_err_pid*.log` |

### 4.2 未跟踪文件分类

| 类别 | 路径 / 数量 | 内容 | 处理建议 |
|---|---|---|---|
| 产品源码 | `src/` 550 个文件 | Clojure/ClojureScript 源码与局部 `AGENTS.md` | 必须提交 |
| 测试源码 | `test/` 439 个文件 | `.clj`/`.cljs`/`.cljc`、`test/js/*` 浏览器 harness、runner/support；含新增的 7 个测试 | 必须提交 |
| 产品资源 | `resources/` 6 个文件 | `public/index.html`、`public/css/workbench.css`、`hc/core/pair_hint/{en,zh-CN}.md`、`tessdata/{chi_sim,eng}.traineddata`（6.3 MB） | 必须提交；`traineddata` 是 OCR 产品数据，不要忽略 |
| 构建/仓库清单 | `.gitignore`、`deps.edn`、`package.json`、`package-lock.json`、`pixi.toml`、`pixi.lock`、`shadow-cljs.edn`（7 个） | 依赖、锁定、构建配置 | 必须提交；`.gitignore` 自身也必须提交 |
| 运行/历史文档 | `RUNNING.md`、`HACK.md`、`HISTORY.md`、`PROMPT.md`（4 个） | 运行入口、实测账本、历史取舍、提示词 | 必须提交（修正第 3 节的不一致后再提交） |
| 发布脚本 | `scripts/release-gate.sh`（1 个） | 八条门禁 runner | 必须提交；先对齐 I-18 |
| 计划/报告 | `plans/*.md` 41 个 | 发布说明、E2E 结果、审计、基线、路线图、6 个 E2E 诊断/基线文档与本报告 | 必须提交；其中 `docs-version-audit` 需标历史（I-17） |
| 发布证据 | `plans/evidence/` 84 个文件（25 MB） | 25 PNG、22 JSON、15 TXT、12 LOG、4 HTML、2 EDN、2 CJS、1 MD、1 JS | 作为发布证据提交；不要用 `.gitignore` 隐藏 |
| 待确认外部产物 | `机制提炼/AGENTS.md`（1 个，222 KB） | omp 18.16.0 + pi-goal-x 0.34.1 的 EditorTheme shim，与 Herdchestra 0.0.1 无关 | 不要直接提交；确认来源后移出仓库，或在 `.gitignore` 单独列出（本报告未改） |

计数校验（14:55）：550 + 439 + 6 + 7 + 4 + 1 + 41 + 84 + 1 = 1133。

### 4.3 已忽略的构建产物与运行残留

| 类别 | 代表路径 | 条目数 / 体积 | 说明 |
|---|---|---|---|
| Pixi 环境 | `.pixi/` | 8,470 / 656 MB | 不提交 |
| Clojure 编译 | `target/` | 6,676 / 258 MB | 不提交 |
| Node 依赖 | `node_modules/` | 6,519 / 69 MB | 不提交 |
| shadow-cljs | `.shadow-cljs/` | 2,197 / 250 MB | 不提交 |
| 测试 harness 输出 | `out-*` 约 20 个目录 | 273 MB | 不提交 |
| Clojure 缓存 | `.cpcache/` | 687 / 13 MB | 不提交 |
| 客户端构建 | `resources/public/js/` | 1,030 / 37 MB | 不提交；`main.js`、`manifest.edn`、`cljs-runtime/` 由 `client-release` 生成 |
| 浏览器 profile | `plans/evidence/*/chrome-profile/` | 552 个文件 | 不提交；E2E 缓存 |
| JVM 崩溃日志 | `hs_err_pid*.log` | 9 个文件 | 不提交；但发布前要调查来源（I-09），不要只删除 |

说明：`resources/public/css/framework7-bundle.css` 也被忽略；`resources/public/css/workbench.css` 是可提交的手写样式。`resources/public/index.html` 可提交。

### 4.4 `.gitignore` 建议（只建议，未改文件）

当前 `.gitignore` 已覆盖主要产物，建议保留现有规则，并考虑以下小补丁：

```gitignore
# JVM 诊断
*.hprof
# Clojure 编辑器/分析缓存（如团队使用）
.clj-kondo/.cache/
.calva/

# 如未来出现带后缀的 profile 目录
**/chrome-profile*/
```

注意事项：

1. **不要忽略** `plans/evidence/**`、`resources/tessdata/*.traineddata`、`package-lock.json`、`pixi.lock`；这些是发布证据、产品数据或可复现构建的一部分。
2. 现有 `*.log` + `!plans/evidence/**/*.log` 的否定规则有效：`plans/evidence/scale-dom/*.log` 可跟踪，`hs_err_pid*.log` 仍被忽略。
3. `plans/evidence` 25 MB、`resources/tessdata` 6.3 MB；如果仓库体积敏感，使用 Git LFS 或在提交说明中列明，不要用 `.gitignore` 隐藏证据。
4. `hs_err_pid*.log` 建议继续忽略但保留在工作树，直到来源确认；发布说明要更新为 6 个（I-09）。
5. `机制提炼/` 若确认不是 Herdchestra 仓库内容，可加入 `.gitignore`；先确认来源，不要直接删除。

## 5. 发布前必须补齐的文档动作

### P0：打标前必须完成

1. **修正 E2E 统计**：master §3 正文 6→7；release-notes §3 统计 6→7 并补 20；从“待补齐”清单删除 20（I-01/I-02/I-03）。
2. **修正 01/02 模型端点说明**：release-notes §4.3 删除“01/02 使用 stub、不是真实模型”；改为“首轮批次 stub；01/02 后续真实 Ollama 复跑 PASS；真实云供应商行为未验证”。给 HACK §13.1 加“仅首轮批次”的范围（I-06/I-07）。
3. **修正 RUNNING 条件 8**：01 改为“binding 修复后 13/13 PASS”；07/11/12/13 逐条改写，删除“上下文面板与记忆候选待服务端交付”；“扩展页崩溃已修”改为“keyword child 崩溃已修，小应用页待复验，不得写已修复”（I-12/I-13/I-14）。
4. **拆分 19 的 NOT RUN**：200%/IME/焦点/Escape = PASS；屏幕阅读器顺序、对比度、真实触屏 = NOT RUN（I-05）。
5. **统一备份恢复“全新目录”定义**：用一次 CLI 复测确认 `restore` 是否创建 store；统一 HISTORY、release-notes、HACK 的措辞；不要把未验证的“CLI 自动建库”写成承诺（I-11）。
6. **给停止路径加覆盖范围注**：01 的 13/13 不含 D7 点击停止 UI；D7 另列待复验（I-16）。
7. **更新 `hs_err` 数量**：4→6，列出新增两个文件；说明来源未追查（I-09）。
8. **标记历史审计**：`plans/docs-version-audit-0.0.1.md` 顶部加“已被 master 与本报告取代”（I-17）。
9. **对齐 release-gate.sh 与 RUNNING 八条**：补无模型探针或写明映射；不要把 worktree inventory 冒充第 6 条“干净工作树”（I-18）。
- **标记中间快照**：`plans/release-gate-checklist-0.0.1.md` 的 E2E 状态/gate 8/计数与 `HACK.md` §8.4 的 18 PASS/4 BLOCKED 都要标“首轮或中间快照，已过时”，并指向 master（I-21/I-22）。

### P1：发布提交前完成

10. **删除 release-notes §4.4 重复的停机窗口段落**（I-10）。
11. **修正 release-notes §6 第 3 项的 PASS 清单**：补 01/02/20（I-04）。
12. **HACK §13.3 标为“首轮未执行，后续 §14.3 已执行”**（I-08）。
13. **release-notes §4.1 标题改为“已修 finding 记录”**（I-19）。
14. **最终树重跑后写回数字**：`pixi run test` 的测试/断言数、seed、退出码；`app-check` 的耗时/health；`client-release` 的 `main.js`/CSS 大小与 hash；真实启动冒烟端口（每次不同）。当前 3406/22121 只是基线树。
15. **确认 E2E 03 的停止路径口径**：待 03 修复工人报告；本报告不把 03 改判为 PASS（I-20）。
16. **处理 `机制提炼/AGENTS.md`**：确认它是否属于本仓库；内容为 omp/pi-goal-x 的 EditorTheme shim，与 Herdchestra 0.0.1 无关。建议移出仓库或在 `.gitignore` 单独忽略；不要删除（本报告未改）。

### P2：提交前顺手完成

17. 确认 `plans/evidence` 的 25 MB 证据是否全部随发布提交；如使用 LFS，更新 `.gitattributes`（本报告未创建）。
18. 确认 `.gitignore` 自身已提交，且 `git status --porcelain -uall` 不再出现产品文件。
19. 确认 `resources/tessdata/*.traineddata` 作为产品数据提交，不被 `*.log`/`*.hprof` 等规则误伤。
20. 提交前重跑 `git status --porcelain -uall`，用最终计数替换本报告的 1133 快照；并行施工期间计数会继续变化。

## 6. 未执行与限制

- 未运行 `pixi run test`：W1 正在跑；本报告只引用基线树 3406/22121 与 HACK §3.5，不把它当最终树结果。
- 未运行 `pixi run client-install`、`pixi run client-release`、真实启动冒烟、无模型探针；这些门禁的最终树结果仍待跑。
- 未重跑 E2E 01–22；本报告只核对文档与既有证据，不重新裁决 E2E 状态。E2E 最终状态由各修复工人的独立报告汇总。
- 已执行一次隔离备份/恢复复测（见 §7.2）：完全空目录 restore 退出 0、CLI 创建 store；非空备份的 `:status :complete` 仍未复测。
- 未执行任何 Git 写操作；按授权修改了 `RUNNING.md`、`HACK.md`、`plans/release-notes-0.0.1.md`、`plans/e2e-master-status-0.0.1.md`、`plans/release-gate-checklist-0.0.1.md` 的事实性错误；未修改 `HISTORY.md`，未改各 E2E 的最新结论；未删除仓库内任何文件。
- 未修改 `.gitignore`；第 4.4 节只是建议。

## 7. 本轮事实修正记录（2026-11-05）

### 7.1 已修改文件与依据

| 文件 | 修正 | 依据 |
|---|---|---|
| `plans/e2e-master-status-0.0.1.md` | §3 正文“6 条”改为“7 条” | 同节表格与 §4 均为 7（01/02/14/17/18/19/20）；7+1+10+4=22 |
| `plans/release-notes-0.0.1.md` | §3 统计 6→7（补 20）；§3 结论从待补齐清单删除 20；§6 第 3 项 PASS 清单补 01/02/20 | master 表格与 §4；20 是 PASS，无剩余 E2E 子项 |
| `plans/release-notes-0.0.1.md` | §4.3 的 19 口径拆为“IME/焦点/Escape/200% 字号 PASS；屏幕阅读器顺序/对比度/真实触屏 NOT RUN” | master 第 19 行与同文 §3 第 19 行 |
| `plans/release-notes-0.0.1.md` | §4.3 删除“01/02 使用 stub、不是真实模型”，改为真实本地 Ollama 复跑；stub 只属首轮 08:11 批次 | `/tmp/hc-e2e-real/artifacts/e2e01/e2e01-result.json` 显示 `Local Ollama (real)`、`Qwen3.6 27B (real)`、`replyNotStub: true`；`/tmp/hc-e2e-real/README.md` |
| `plans/release-notes-0.0.1.md` | §4.5 与 §6 第 5 项 `hs_err` 4→9，补 `hs_err_pid1928617.log`、`hs_err_pid3566148.log`、`hs_err_pid3871452.log`、`hs_err_pid4054882.log`、`hs_err_pid4063480.log` | `ls hs_err_pid*.log` 当前 9 个 |
| `plans/release-notes-0.0.1.md` | §2 第 10 项、§4.4、§5、§6 第 4 项改写“完全空目录 restore” | 隔离复测：`/tmp/hc-prep-restore-0.0.1/restore-empty.log`，exit 0，CLI 创建 store，`:store-observed? true`、`:status :no-changes`（空备份无类别） |
| `HACK.md` | §8.4 第 3 条改为 8 PASS（01/02/14/17/18/19/20/22）、10 PARTIAL、4 BLOCKED，指向 master | master §3 |
| `HACK.md` | §13.1 标题与限制改为“首轮批次 stub；01/02 后续真实 Ollama 复跑” | `/tmp/hc-e2e-real` 的 01/02 结果 |
| `HACK.md` | §13.3 标题“未执行”→“已执行”；正文改为真实 ENOSPC 实测；§14.3 补证据路径 | `/tmp/hc-e2e-harness/enospc/evidence/e2e18-pass.json` |
| `HACK.md` | §13.4/§14.4 补“完全空目录 restore 由 CLI 创建 store”复测证据 | `/tmp/hc-prep-restore-0.0.1/restore-empty.log` |
| `RUNNING.md` | 条件 8 删除 01 待 binding、07/11/12/13 服务端待交付、扩展页已修等具体断言；保留“E2E 尚未全部 PASS”，改为指向 master | master 与 release-notes 当前状态；修复中条目不得预写 PASS |
| `plans/release-gate-checklist-0.0.1.md` | 结论改为 4 PASS/2 NOT RUN/2 FAIL；gate 6/8 更新；工作树 17/1133/37；`hs_err` 9；`out-*` 20；补 `机制提炼/`；冲突表与 §5 更新 | 本轮 `git status`、`ls` 与 master；restore 复测证据 |

### 7.2 备份恢复复测（新增证据）

命令摘要（隔离根 `/tmp/hc-prep-restore-0.0.1`，不碰用户数据）：

```bash
# 1. 建源 store
/tmp/pixi run clojure -M:server -e '(require (quote [hc.server.storage :as storage])) ...'
# 2. 默认加密备份，得到 backup-id 4796d4a1-c4b1-4965-b7c7-bee21164adc6
HC_BACKUP_PASSWORD='prep-pass-123' /tmp/pixi run clojure -M:server \
  -m hc.server.backup.cli backup ...
# 3. 恢复到完全空目录
HC_BACKUP_PASSWORD='prep-pass-123' /tmp/pixi run clojure -M:server \
  -m hc.server.backup.cli restore --db-path /tmp/hc-prep-restore-0.0.1/empty/db ...
```

结果：restore 退出 0；`empty/db` 下生成 `VERSION`、`data.mdb`、`lock.mdb`、
`store.edn`；回执 `:ok? true`、`:store-observed? true`、`:status :no-changes`
（空备份没有类别可导入）。这证明 CLI 可以在完全空目录创建 store，不需要预先
`storage/open-store`；HISTORY 的旧假设不成立，但按任务约束未修改 HISTORY。
非空备份的 `:status :complete` 仍引用旧证据，未在本次复测覆盖。

### 7.3 剩余待 E2E 结果或最终树才能定稿的条目

1. **E2E 03**：仍 BLOCKED；`/tmp/hc-e2e-real/README.md` 记录“停止路径 PASS、多 chunk 渲染 FAIL”，master 仍写“待重跑停止”；等 03 修复工人报告后统一（I-20）。
2. **E2E 07/11/12/13/15/16/21**：仍 BLOCKED/PARTIAL，需按 master §4 的剩余子项复跑；不得预写 PASS。
3. **D7 真实点击停止 UI**：01 的 13/13 不含停止路径；服务端停止/迟到文本 PASS，UI 首 chunk/标签仍待复验。
4. **最终树门禁**：`pixi run test`、`app-check`、`client-release`、真实启动冒烟、无模型探针必须在最终合并树重跑；当前 3406/22121 与 3611/23306 都是中间/基线。
5. **备份恢复非空备份**：完全空目录已复测；非空备份的 `:status :complete` 仍需在最终树复验。
6. **`hs_err` 来源**：9 个日志的来源仍未追查；发布前确认是否与 Datalevin/LMDB 或旧运行时有关。
7. **`机制提炼/AGENTS.md`**：仍是非产品外部 artifact，未忽略、未删除；建议确认后移出或加入 `.gitignore`。
8. **`plans/docs-version-audit-0.0.1.md`**：仍保留 18 PASS/4 BLOCKED、4 个 hs_err、965 个未跟踪等历史数字；未修改，建议标历史。
9. **release-notes §4.4 重复的停机窗口段落**：本轮未删除（不在事实修正清单）；如需清理可单独处理。
10. **HISTORY.md**：按任务约束未修改；其中“CLI restore 要求 store 已存在”已被本轮复测否定，保留为历史记录。

## 8. JVM 崩溃排查（hs_err，2026-11-05）

### 8.1 结论

- 9 个 `hs_err_pid*.log` 全部是 **SIGSEGV（0xb）崩溃**，不是 Java 异常，也不是 OOM：
  日志中没有 `Out of Memory` / `insufficient memory`；堆使用约 0.98–2.29 GB，
  最大堆 15.96 GB。
- **不是构建进程**：命令分别是 6 个 `hc.server.app`、1 个复现脚本、
  2 个测试 runner（`cognitect.test-runner` / `clojure.run.exec`）；没有
  `shadow-cljs` 或 `client-release` 进程。不能把崩溃归因于并行构建。
- **与 app 运行时相关**：7/9 崩溃线程是 `hc-publisher-1`（发布器读取/扫描
  store），1/9 是 `clojure-agent-send-off-pool-1`（artifacts 列表 →
  `session_view` → Datalevin scan），1/9 是 `hc-shutdown`（关闭时仍走
  publisher → baseline → `session_view` → Datalevin scan）。5/9 的原生帧
  直接落在 Datalevin 1.2.10 的 `libdtlv.so`（native 1.1.8）；2/9 落在
  `StubRoutines::jbyte_disjoint_arraycopy_avx3`，操作的是
  `java.nio.DirectByteBuffer`（其中一个错误报告阶段再次 SIGSEGV）；2/9 落在
  JIT 编译后的 `taoensso.nippy.io$read_typed`，Java 栈仍是
  `hc.server.artifacts.registry` → `read_session_view` → Datalevin scan。
  三类都指向存储读取/反序列化路径。
- JVM 版本：3 个宿主 JDK 26.0.2.1、4 个宿主 JDK 27、2 个 **Pixi JDK
  21.0.10**。Pixi 21 的崩溃是 `hs_err_pid3566148.log` 与
  `hs_err_pid4063480.log`，所以不能把它解释成“只在宿主 JDK 26/27 上的环境问题”。
- **阻塞级别：修复前 P0；D-06 已关闭，当前不阻塞**。根因是 Datalevin
  读/store 关闭竞争；W1/W11 已落地 `shutdownNow` + `awaitTermination` +
  publish-lock 排空。D-06 的 20 轮/180 assertions、SIGTERM 5/5、
  `hs_err` 9→9 已通过；若最终复验新增 `hs_err`，重开 D-06。
- **可复现性：当前不能稳定复现**。原始复现脚本
  `/tmp/hc-devops-20261104/repro_stream2.clj`、`/tmp/rt_only4.clj` 已不存在，
  也没有留下完整输入/时序；只能从命令、线程和原生帧推断触发面。
  15:18 的 `hs_err_pid3871452.log`、16:52 的 `hs_err_pid4054882.log`、
  16:53 的 `hs_err_pid4063480.log` 来自其他并行 app（e2e04、w6、w8），
  说明问题在当前树仍在发生。本轮 E2E 01/02/22 自己的 app 实例没有新增
  `hs_err`。

### 8.2 逐文件判读

| 日志 | 时间 / JVM | 进程 | 崩溃线程 | 原生帧 | 判读 |
|---|---|---|---|---|---|
| `hs_err_pid1928617.log` | 2026-11-05 12:07:43 / JDK 27 | `hc.server.app --config /tmp/hc-e2e-real/e2e15/config.edn` | `hc-shutdown` | `pthread_mutex_lock` ← `DTLV.mdb_txn_begin` ← `session_view` | 关闭路径 `system/stop!` → browser shutdown → publisher baseline 仍在读 Datalevin；关闭/读取竞态 |
| `hs_err_pid2905205.log` | 2026-11-04 01:33:00 / JDK 26.0.2.1 | `clojure.main -i /tmp/hc-devops-20261104/repro_stream2.clj` | `hc-publisher-1` | `pthread_mutex_lock` ← `DTLV.mdb_txn_begin`（read-only） | 发布器读取事务；复现脚本已缺失 |
| `hs_err_pid3003787.log` | 2026-11-04 02:33:49 / JDK 26.0.2.1 | `hc.server.app --port 17780 --data-dir /tmp/hc-devops-e2e-20261103/final5-data` | `hc-publisher-1` | `libdtlv.so mdb_page_search_root` | 发布器扫描 LMDB 页 |
| `hs_err_pid3566148.log` | 2026-11-05 14:23:28 / **Pixi JDK 21.0.10** | `hc.server.app --data-dir /tmp/hc-compose-e2e/data-e2e09chain3` | `hc-publisher-1` | `StubRoutines::jbyte_disjoint_arraycopy_avx3`；`DirectByteBuffer` address+position 越过映射页；错误报告再次 SIGSEGV | 发布器复制/序列化存储缓冲区；Pixi 运行时也会崩 |
| `hs_err_pid3597664.log` | 2026-11-04 13:09:38 / JDK 26.0.2.1 | `cognitect.test-runner /tmp/rt_only4.clj` | `hc-publisher-1` | `libdtlv.so mdb_cursor_get` | 测试 runner 中的发布器扫描 |
| `hs_err_pid455536.log` | 2026-11-05 00:20:15 / JDK 27 | `clojure.run.exec :nses [fleet…]` | `hc-publisher-1` | `pthread_mutex_lock` ← `DTLV.mdb_txn_begin`（read-only） | 测试 runner 中的发布器读取 |
| `hs_err_pid3871452.log` | 2026-11-05 15:18:36 / JDK 27 | `hc.server.app --config /tmp/hc-w6/run13/config.edn` | `clojure-agent-send-off-pool-1` | JIT `taoensso.nippy.io$read_typed`；Java 栈 `artifacts.registry/list-artifacts` → `read_session_view` → Datalevin scan | 当前树仍在发生；app 运行时读取/反序列化路径 |
| `hs_err_pid4054882.log` | 2026-11-05 16:52:46 / JDK 27 | `hc.server.app --config /tmp/hc-e2e04/config.edn` | `hc-publisher-1` | `StubRoutines::jbyte_disjoint_arraycopy_stub` | 发布器复制存储缓冲区；app 运行时 |
| `hs_err_pid4063480.log` | 2026-11-05 16:53:19 / **Pixi JDK 21.0.10** | `hc.server.app --data-dir /tmp/hc-w8/data8` | `hc-publisher-1` | JIT `taoensso.nippy.io$read_typed` | 发布器读取/反序列化；Pixi 运行时再次崩溃 |

### 8.3 最小复现候选（尚未验证）

1. **关闭竞态**：Pixi JDK 21 启动真实 app，等 publisher baseline 完成一次；
   在会话产生状态变化或轮次进行中发送 SIGTERM，让 `hc-shutdown` 与
   `hc-publisher-1` 同时进入 `session_view`/Datalevin scan；重复 20 次。
2. **发布器扫描压力**：用真实 store 灌入多会话/多状态变化，让
   `hc-publisher-1` 连续 `get_range`/`mdb_cursor_get`，同时在另一进程做
   start/stop 或备份读取；观察是否出现 `libdtlv.so` 帧。
3. **e2e09 链**：复跑 `/tmp/hc-e2e-harness/run/e2e09chain*` 的
   app + 多次状态增量 + stop 路径；`hs_err_pid3566148.log` 即来自该链的
   app 进程。
4. **artifacts 列表压力**：打开材料/artifacts 面板触发
   `list-artifacts` → `read_session_view` → Datalevin scan，同时让 publisher
   持续发布；对应 `hs_err_pid3871452.log` 的 nippy 反序列化崩溃。

以上都没有稳定触发条件；在最终树复现前，不应把任何一次“未崩溃”当作修复证据。

### 8.4 发布处理建议

- 保留 9 个 `hs_err_pid*.log`，不要删除；它们已被 `.gitignore` 忽略，不进入提交。
- 最终树至少做一轮“启动 → 发布器 baseline → 停止”循环，并检查是否新增
  `hs_err`；若新增，打 tag 前必须定位或明确规避。
- 若最终树无法复现，发布说明仍须列为已知原生崩溃风险，不能写“稳定”或
  “无崩溃”。不要用一次 E2E 通过替代根因分析。

### 8.5 E2E 01/02/22 回归观察（2026-11-05 15:22–15:38）

- 根 `AGENTS.md` §8.5 现要求 22 条 E2E 全部自动化，并配套 mock 模型服务与
  OAuth/WebDAV/S3 mock 外部服务；本节是手工回归/探索，不是新口径的最终验收；
  W10 自动化结果出来前不得据此判 PASS。
- **手工回归 E2E 01 PASS**：13/13 主断言 + 5/5 补充断言（无注册、草稿保留、
  单条用户消息、复制入口）；bundle `ec5ceea…`。
- **手工回归 E2E 02 PASS**：Alpha/Beta 可区分、旧会话保留原模型、新会话默认 Alpha、
  停用后提示明确且不删历史；bundle `ec5ceea…`。
- **E2E 22 PASS（手工回归；新 §8.5 自动化仍待 W10）**：原始
  `ClassCastException` 已由 W4 修复，W5 修了 `ReentrantLock` import；用
  备份恢复到独立目录后 B `app-check` **exit 0**（`:ok true`、`:models
  configured? true`、`:component-errors {}`、sessions=2；证据
  `e2e22-b-rerun-check3.log`）。默认 CLI 备份排除已删除会话；显式
  `--include-deleted true` 备份/恢复后，B2 UI 已删除区可见、`s-deleted` 的
  `deleted-at` 原样保留（未延期）。no-sync 以单 app 存储隔离验证：A 临时
  会话后 count 2，B2 仍 count 1，store ids 只有 `s-deleted`/`s1`。
  断言见 `e2e22-final.json`。诊断见
  `plans/e2e22-regression-diagnosis.md`。
- 该窗口另有 2 个其他 app 的原生崩溃（16:52 `hs_err_pid4054882.log`、
  16:53 `hs_err_pid4063480.log`），不是本 E2E 实例；本 E2E 的 app 没有新增
  `hs_err`。详见 §8.2。
- 01/02 结果绑定 `ec5ceea…`；E2E22 最终手工回归绑定
  `5addfe1dd8e567b5c8aa047b30911e992520452648a594d12a37257d701f75b7`
  （2026-11-06 10:21），早期失败绑定 `9c59f83…`。
- 逐项证据与命令：`plans/e2e-regression-01-02-22.md`、
  `plans/evidence/e2e-regression-01-02-22/`。

## 9. 根规格纪律（2026-11-06）

- 根 `AGENTS.md` 由用户直接维护；任何人不得回退、覆盖、合并、格式化或写入，
  也不得 `git restore` 或 chmod。
- 本报告未修改根 `AGENTS.md`；实现取舍只写入 `plans/`、`HISTORY.md` 或
  `src/**/AGENTS.md` 局部规格。发现实现缺口时写报告并通知 W1 汇总到
  `DEBT.md`（AI 维护台账，关闭即删）。
- 当前实现以用户最新版 `AGENTS.md` 为准；本报告引用的是 2026-11-06 的
  快照，若用户继续修改，需重新核对。

## 10. 内存与 OOM 风险（2026-11-06）

### 10.1 我持有的进程与 RSS

- E2E22 期间启动的 app JVM：
  - A 实例 java PID 89497，RSS 约 1,036,664 KB；
  - B2 实例 java PID 95262，RSS 约 1,117,352 KB。
  两者均带 `-Xmx1g -XX:MaxDirectMemorySize=512m`，已在 E2E 结束后停止。
- 当前我没有 app/test JVM 或 Chrome 进程；PID 86083 不存在、不属于我；
  PID 85370 是 W13 的全量测试，带 JVM caps，未杀。
- 我启动的 Playwright Chrome 均已关闭；当前 Chrome 属于 w2/e2e15，
  未杀。

### 10.2 热点与修复

| 热点 | 原因 | 修复 |
|---|---|---|
| E2E app JVM 默认堆上限 15.96 GB | 之前用裸 `clojure -M:server` 启动，多个实例叠加会 OOM | 后续 app 一律 `-Xmx1g -XX:MaxDirectMemorySize=512m`；同一时刻只 1 个 app JVM |
| 多个 app/JVM 并行 | 早期 E2E 同时起 A/B/Chrome | 改为单 app 串行；no-sync 用存储隔离验证，不并行起两个 app |
| Playwright Chrome 默认共享内存 | 容器/大页环境易占内存 | Chrome 加 `--disable-dev-shm-usage`；一次只 1 个 Chrome |
| `/tmp` E2E 数据 | 多份 data/tmp/backups | E2E 结束停止实例并清理；`/tmp/hc-w7-reg` 从 14 MB 降到 452 KB，证据已复制到 `plans/evidence/` |
| `hs_err` 中的 publisher/nippy/arraycopy | Datalevin 读/store 关闭竞争 | W1/W11 已修 `shutdownNow` + `awaitTermination` + publish-lock；D-06 的 20 轮 start/stop 复验未完成 |
| E2E app 堆 | 本轮 E2E22 使用 `-Xmx1g`；W15 标准为 E2E app 768m/512m | 后续 E2E 统一 768m/512m；本轮 1g 仍在安全上限内 |

### 10.3 结论

- 本轮没有改产品源码，因此没有新增无界缓存/日志/事件；E2E harness 是主要
  内存风险源，已通过 JVM caps、单实例、Chrome 参数与清理收敛。
- 9 个 `hs_err` 的原生崩溃与 OOM 是两类问题：OOM 是 JVM 默认堆过大与实例过多；
  SIGSEGV 是 Datalevin 存储读取/关闭竞争，W1/W11 已修，D-06 已关闭
  （20 轮/180 assertions、SIGTERM 5/5、`hs_err` 9→9）。两者都不能用
  “内存不值钱”解释。
