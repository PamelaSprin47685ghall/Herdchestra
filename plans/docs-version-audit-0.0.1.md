# 文档与版本一致性审计（0.0.1）

- 日期：2026-11-05 10:58 CST
- 仓库：`/home/kunweiz/Desktop/vibe/Herdchestra`
- 范围：发布文档、版本一致性、`HACK.md`/`HISTORY.md` 增补、Git 状态清单。
- 约束：未修改 `src/`；未运行全量测试；未执行任何 Git 写操作（无
  add/commit/tag/push）；未删除仓库内文件（只清理了自建 `/tmp` 测试目录）。
- 版本源：`src/hc/version.cljc` 保持 `"0.0.1"`，未改代码。

## 1. 改动文件

| 文件 | 动作 | 说明 |
|---|---|---|
| `.gitignore` | 更新 | 新增 `chrome-profile/`、`**/chrome-profile/`；在 `*.log` 后新增 `!plans/evidence/**/*.log`，使 E2E/规模证据可跟踪，`hs_err_pid*.log` 仍忽略 |
| `plans/release-notes-0.0.1.md` | 新建/更新 | 0.0.1 发布说明草案；易漂移数字改为“基线树/该次测量，以最终门禁日志为准” |
| `RUNNING.md` | 更新 | 条件 8 指向发布说明；修正 shadow-cljs aliases、fleet preflight 措辞、默认数据目录限定、全量测试口径 |
| `HACK.md` | 更新 | 刷新当前环境；更新 §3.5/§8.4 全量测试与真实启动状态；新增发布验证节；把重复的第二个 §8 改为 §12，把发布验证节改为 §13 |
| `HISTORY.md` | 更新 | 新增 2026-11-05 规则替换：备份 CLI 加密默认值、停止 command-id、离线恢复目标 store |
| `plans/offline-restore-0.0.1.md` | 更新 | 顶部加“后续状态”：D1/D2/D3 已复检，本文保留早期失败证据 |
| `plans/e2e-0.0.1-results.md` | 更新 | 顶部加“后续状态”：D7 与备份 CLI 已改，相关 E2E 未重跑 |
| `plans/client-scale-0.0.1.md` | 更新 | 顶部加 E2E 交叉引用；本文只保留客户端规模与无模型结论 |
| `plans/docs-version-audit-0.0.1.md` | 新建 | 本报告 |

未修改：`src/`、`test/`、`pixi.toml`、`package.json`、`deps.edn`、`shadow-cljs.edn`、`resources/`。

## 2. 版本一致性核对

### 2.1 三个来源

```text
src/hc/version.cljc:  (def version "0.0.1")
pixi.toml:            version = "0.0.1"      （第 3 行）
package.json:         "version": "0.0.1"     （第 3 行）
```

### 2.2 `--help`

命令：

```bash
/tmp/pixi run clojure -M:server -m hc.server.app --help
```

结果：退出 0，18.46 s；首行：

```text
Herdchestra 0.0.1 server
```

日志：`/tmp/docs-version-help.log`。

### 2.3 `app-check` health

命令：

```bash
/tmp/pixi run app-check
```

结果：退出 0，18.79 s。health 关键字段：

```clojure
{:ok true,
 :version "0.0.1",
 :store {:open? true,
         :path "/tmp/hc-check-7252957530632265791/data/db"},
 :gateway {:epoch 1, :seq 1, :max-pending 4096,
           :subscriptions 0, :stale 0},
 :models {:configured? false},
 :components {:fleet-gc {:configured? true, :started? true, :status :started},
              :fleet {:configured? true, :available? true, :status :started},
              :model {:configured? true}},
 :publish-error nil,
 :component-errors {},
 :sessions {:count 1}}
```

日志：`/tmp/docs-version-app-check.log`。

说明：`:components :model {:configured? true}` 在 `src/hc/server/app/system.clj:2380`
表示模型端口组件存在（`(or (:model-port system) (:model-port-ref system))`），
不是“用户已配置模型连接”；实际模型配置看顶层 `:models {:configured? false}`。
文档若只写一个 `configured?` 容易误读，发布说明已注明。

### 2.4 五处一致性结论

| 位置 | 值 | 结论 |
|---|---|---|
| `src/hc/version.cljc` | `0.0.1` | 基准 |
| `pixi.toml` | `0.0.1` | 一致 |
| `package.json` | `0.0.1` | 一致 |
| `--help` | `Herdchestra 0.0.1 server` | 一致 |
| `app-check` health | `:version "0.0.1"` | 一致 |

**结论：五处一致，无需修改清单版本。** 版本源仍是 `src/hc/version.cljc`。

## 3. `RUNNING.md` 修正与理由

### 3.1 条件 8 与发布说明指针

- 原第 8 条写“E2E 01–22 未跑就标 `NOT RUN`”。这与当前事实不符：E2E 已实跑，
  结果 18 PASS / 4 BLOCKED，且多数 PASS 是契约/组件级。
- 改为：发布说明必须列出已验证项、`NOT RUN`/`BLOCKED` 项与边界；E2E 22 条中
  18 条 PASS（多数组件级，不等于完整 UI 通过）、4 条 BLOCKED（14/17/18/22）。
- 在第 8 条后新增一句：逐项证据与未覆盖清单以
  `plans/release-notes-0.0.1.md` 为准；本文件只保留运行入口、发布条件与
  操作边界。满足任务要求的“第 8 条后补指向”。

### 3.2 shadow-cljs aliases

- 原文：`:deps {:aliases [:shadow :client]}`。
- 实际：`shadow-cljs.edn` 为 `:deps {:aliases [:shadow :client :browser-test]}`；
  `deps.edn` 的 `:browser-test` 只加 `test` 路径，供聚焦浏览器 harness 使用。
- 已改为三别名，并说明生产 `:app` 只编译入口引用到的命名空间。
- 理由：按实际构建图修正，避免读者以为 `test` classpath 不存在或需要把
  `:browser-test` 加进 `:cljs`。

### 3.3 fleet preflight 措辞

- 原文：“隔离 workspace 不是 Git 仓库，因此 check 模式下 fleet 以
  `:preflight? false` 启动”。
- 实际 `src/hc/server/app.clj` 的 `check-options`：只有创建了临时 workspace
  （即未显式传 `--workspace-root`/`HC_WORKSPACE_ROOT`）时，才设置
  `[:fleet :preflight?] false` 与 `[:fleet :gc-run-immediately?] false`。
  显式传 workspace 时保持真实预检。
- 已改为“默认隔离 workspace 时……；显式传入 workspace 时保持真实预检”。
- 理由：原句把默认行为说成所有 `--check` 行为，范围过宽。

### 3.4 默认数据目录限定

- 原文：“使用默认数据目录 `~/.local/share/herdchestra`”。
- 实际 `hc.server.app.config/default-data-dir` 按 OS 分支：Linux 为
  `~/.local/share/herdchestra`，macOS/Windows 不同。
- 已加“Linux 上为……”限定，并指向 `default-data-dir`。
- 理由：避免把 Linux 路径写成跨平台承诺。

### 3.5 全量测试口径

- 表格新增“以最终合并树重跑为准”。
- 理由：3406/22121 是基线树结果；当前 `src/`/`test/` 仍在并行修改，不能把
  旧结果直接当最终放行证据。

### 3.6 未改但需注意

- `RUNNING.md` 的“健康 EDN 应包含……`:gateway` 计数”仍准确。
- “停止应用使用 Ctrl-C 或 SIGTERM”仍准确；真实冒烟只验证了 SIGTERM。
- “默认只监听 127.0.0.1”仍准确。
- 条件 6 仍是 FAIL：发布提交未建立，产品源码全部未跟踪。这是事实，不是文档
  错误。

## 4. `HACK.md` 增补

### 4.1 当前环境刷新

`HACK.md` §1 原记录宿主 JDK 26.0.2.1、Node v26.8.1、OS rc1-2、基线
`c48d50e`；2026-11-05 实测为：

| 项 | 实测 |
|---|---|
| 主机 | `Linux 7.3.0-rc6-1-MANJARO` x86_64 |
| 宿主 JDK | OpenJDK 27（2026-09-15） |
| 宿主 Clojure CLI | 1.12.5.1664 |
| 宿主 Node | v26.10.0 |
| 仓库基线 | `015229e` |
| Pixi 运行时 | JDK 21.0.10、Clojure 1.12.6、Node 22.23.4（未变） |

已更新 §1、§3.2、§4.3、§6 的当前值；§8.1/§10/§11 的 2026-11-03 快照保留为
历史测量，不回写成当前值。

### 4.2 全量与真实启动状态

- §3.5：从“全量 NOT RUN”改为基线树实测 3406 tests / 22121 assertions，
  0 failures / 0 errors；并注明最终树必须重跑。
- §8.4：真实应用冒烟从 NOT RUN 改为 2026-11-05 实测（端口 39697、
  `/healthz` 200、`/` 200、SIGTERM 后可重开）；E2E 01–22 改为已实跑
  18 PASS / 4 BLOCKED；F/V/T 场景仍 NOT RUN。

### 4.3 新增 §13

| 小节 | 实测内容 |
|---|---|
| §13.1 | E2E 模型端点是本地 stub `127.0.0.1:18081/v1`，模型 `stub-model`；Ollama 11434 不可达；无真实供应商凭据。stub 不是真实模型 |
| §13.2 | Chrome `/usr/bin/google-chrome` 153.0.8010.36；仓库内 `require('playwright')` 不可解析；E2E 用 `/tmp/hc-pw/node_modules` + `NODE_PATH` 加载 Playwright 1.64.1；`verify_browser_ui.cjs` 硬编码 Chrome 路径 |
| §13.3 | E2E 18 无真实 ENOSPC 注入；只有保存失败/损坏库/维护失败相邻取证；tmpfs `size=1M` 需 sudo，未执行 |
| §13.4 | 备份 CLI 早期 D1/D2/D3 原文与当前树复检：默认加密、`--encrypt true`、无 app gate 的新 store 恢复均通过；完全空目录仍 `STORAGE_NOT_FOUND`；重复 replace 非 no-op |
| §13.5 | Pixi 0.83.0：71,427,392 bytes，SHA-256 `50571451e96f4a82cf675cd83baaf66fff9b718f0aaf153900b9faf4c29bd401` |

### 4.4 交叉引用修正

- §8.4 真实冒烟证据指向 `plans/release-0.0.1-baseline.md` §2 G6 与
  `plans/release-notes-0.0.1.md` §2。
- §8.4 E2E 证据指向 `plans/e2e-0.0.1-results.md`；模型端点边界指向 §13.1。

## 5. `HISTORY.md` 增补

新增 `## 2026-11-05 0.0.1 发布前的规则替换`，记录三项：

1. **备份 CLI 加密默认值**
   - 旧实现：`offline/backup!` 无条件传 `:encrypt? nil`，`core` 把键存在当成
     显式 false；CLI `select-keys` 缺 `:encrypt`/`:include-secrets`，导致
     默认不加密、显式开关解析失败。
   - 当前规则：缺省表示未指定，core 默认加密；CLI 映射为
     `:encrypt?`/`:include-secrets?`；显式 `--encrypt false` 才关闭。
   - 代价：依赖旧默认的脚本必须显式关闭；默认路径强制密码策略。
2. **停止命令 command-id**
   - 旧实现：本地递增 `cmd-N` 同时当 UI 排序键和 Owner 幂等键；重载/第二
     客户端从 1 重新计数，触发
     `command-id was already accepted with a different payload`。
   - 当前规则：`cmd-<random-uuid>` 作为幂等键，`:sequence` 只负责 UI 排序；
     Owner 仍拒绝同 id 不同 payload。
   - 代价：回执 ID 不再可读为序号；真实点击停止路径未重跑。
3. **离线恢复的目标 store**
   - 旧假设：CLI restore 可在完全空目录创建 store 并恢复。
   - 当前事实：要求目标 store 已存在；空目录报 `STORAGE_NOT_FOUND`；需先用
     `app-check` 或已有数据目录初始化。`--no-prior-app-commands` 已映射为
     `:no-prior-app-commands?`。
   - 代价：离线恢复多一步初始化。

写法遵循 `HISTORY.md` 的定位：记录旧规则/旧实现与代价，不把历史编号写进
当前规格；`AGENTS.md` 未改。

## 6. Git 状态清单（2026-11-05 10:58 CST）

### 6.1 已跟踪

```text
AGENTS.md
```

`git ls-files | wc -l` = **1**。HEAD 与 `origin/master` 都只有该文件。

### 6.2 未跟踪但应提交的产品/文档文件

`git status --porcelain` 为 **15 条顶层条目、约 965 个文件**（`-uall`；
10:57 快照为 965，10:58 已增至 966，并行施工期间会继续变动）。

| 类型 | 条目 | 文件数 | 分类 |
|---|---|---:|---|
| 文件 | `.gitignore` | 1 | 构建配置；自身必须提交 |
| 文件 | `deps.edn`、`pixi.toml`、`pixi.lock`、`package.json`、`package-lock.json`、`shadow-cljs.edn` | 6 | 产品构建清单 |
| 目录 | `src/` | 542 | 产品源码 |
| 目录 | `test/` | 382 | 测试源码（含 `test/js/*` 渲染 shim） |
| 目录 | `resources/` | 6 | `index.html`、`workbench.css`、pair-hint、tessdata |
| 文件 | `RUNNING.md`、`HACK.md`、`HISTORY.md`、`PROMPT.md` | 4 | 运行/历史文档 |
| 目录 | `plans/` | 约 24 | 计划/证据文档（16 个 `.md` + 5 个截图/JSON + 3 个规模日志；10:58 已增至 25） |

按当前文件计数，上述均为应提交内容；没有已修改的已跟踪文件。

### 6.3 构建产物与崩溃日志（仍存在，已忽略）

`git status --porcelain --ignored` 有 **33 条顶层忽略条目**。主要类别：

| 类别 | 条目 |
|---|---|
| Pixi/Clojure/Node 环境 | `.pixi/`、`.cpcache/`、`.shadow-cljs/`、`node_modules/`、`target/` |
| 测试 harness 输出 | `out-artifacts-ui/`、`out-backup-password-dom/`、`out-check/`、`out-check2/`、`out-check3/`、`out-conversation-selection-dom.IqSImE/`、`out-conversation-selection-dom.ikEAGk/`、`out-doc/`、`out-files-ui/`、`out-generated-card-dom/`、`out-generated-card-ui/`、`out-schedule-ui/`、`out-swarm-ui/`、`out-workbench-draft-storage.NK4I6V/`、`out-workbench-scale-dom.cmV2Ks/`、`out-workbench-scale-dom.xNNXBw/`、`out-workbench-session-commands.sPVn6N/`、`out-workbench-shell-dom.A0z1qK/`、`out-workbench-shell-dom.coG8l8/` |
| 客户端构建 | `resources/public/js/main.js`（本稿生成时 2,890,249 bytes，10:52；并行重建中，尺寸会变）、`resources/public/js/manifest.edn`（20,785 bytes）、`resources/public/css/framework7-bundle.css`（570,383 bytes） |
| 本地工具 | `.pi/` |
| 浏览器 profile | `plans/evidence/no-model/chrome-profile/`、`plans/evidence/scale-draft/chrome-profile/` |
| JVM 崩溃日志 | `hs_err_pid2905205.log`、`hs_err_pid3003787.log`、`hs_err_pid3597664.log`、`hs_err_pid455536.log` |

`hs_err` 四个文件均为 `SIGSEGV`，时间 2026-11-04 01:33 至 2026-11-05 00:20；
按 `*.log` 规则忽略，未删除。本次基线运行没有新增 `hs_err`。

### 6.4 未跟踪且未被忽略的测试产物

`plans/evidence/` 下有 **8 个未跟踪且未被忽略的文件**：

| 路径 | 未跟踪文件数 | 性质 |
|---|---:|---|
| `plans/evidence/no-model/` | 3 | `no-model.png`、`no-model.json`、`no-model-dom.html`；发布证据，可提交 |
| `plans/evidence/scale-draft/` | 2 | `scale-draft.png`、`scale-draft.json`；发布证据，可提交 |
| `plans/evidence/scale-dom/` | 3 | `list.log`、`messages.log`、`pure-bench.log`；已通过否定规则可跟踪 |

`plans/evidence/*/chrome-profile/` 的 449 个浏览器 profile 文件已被
`.gitignore` 忽略，不再出现在 `git status`。`plans/evidence/scale-dom/*.log`
不再被 `*.log` 整体忽略，可随普通提交进入仓库。

### 6.5 `git check-ignore` 复核

以下均**未被忽略**（trackable）：

```text
.gitignore  src  test  resources  plans  deps.edn  shadow-cljs.edn
pixi.toml  pixi.lock  package.json  package-lock.json
HACK.md  HISTORY.md  RUNNING.md  PROMPT.md  AGENTS.md
plans/evidence/scale-dom/list.log
plans/evidence/scale-dom/messages.log
plans/evidence/scale-dom/pure-bench.log
```

以下**被忽略**：

```text
out-check  hs_err_pid2905205.log  .pi
plans/evidence/no-model/chrome-profile/
plans/evidence/scale-draft/chrome-profile/
```

## 7. 残留不一致与风险

1. **发布提交仍不存在（阻断）**：`git ls-files` 只有 `AGENTS.md`；产品源码、
   测试、构建清单与文档全部未跟踪。发布条件 6 / G8 仍 FAIL。
2. **发布说明是草案**：它已如实列出 18 PASS/4 BLOCKED 与 NOT RUN，但 0.0.1
   尚未打标；条件 8 只能算“已有草案”，不能算“已发布”。
3. **E2E 结果早于当前树的部分修复**：E2E 结果记录 D7 停止冲突；当前
   `src/hc/client/conversation.cljc` 已改为 `cmd-<random-uuid>` + `:sequence`，
   但 E2E 01/03/08 没有重跑。备份 CLI D1/D2/D3 的主要路径已在当前树复检
   通过（`--include-secrets` 未单独复检），而
   `plans/offline-restore-0.0.1.md` 仍保留早期失败记录。两者不矛盾，但读者
   必须按“早期证据 + 当前复检”理解，不能只看旧报告。
4. **D6/D9 仍无通过证据**：E2E 17 备份面板 no-op、E2E 14 浏览器启用流程仍
   BLOCKED；D8 提示仍是通用 `no model port`。本次未复验修复。
5. **全量测试与 app-check 未在最终树重跑**：3406/22121 与 09:25 app-check
   属于基线树；当前树仍在并行修改，发布前必须重跑。
6. **`HACK.md` 章节编号重复（本轮已修复）**：原第二个
   `## 8 应用启动与维护入口` 与 `### 8.1 受监督恢复入口` 已改为
   `## 12`/`### 12.1`；原发布验证节 `## 12` 改为 `## 13`，子节改为
   `13.1`–`13.5`。详见 §9.2。
7. **`HACK.md` 历史测量与当前环境并存**：§8.1/§10/§11 的 JDK 26/Node 26.8.1
   是 2026-11-03 快照，§1 已更新为当前 JDK 27/Node 26.10.0。读者应按日期
   区分，不要把历史快照当当前环境。
8. **`plans/evidence/*/chrome-profile/` 未被忽略（本轮已修复）**：
   `.gitignore` 新增 `chrome-profile/` 与 `**/chrome-profile/`；449 个
   profile 文件已不再出现在 `git status`。详见 §9.1。
9. **`plans/evidence/scale-dom/*.log` 被忽略（本轮已修复）**：
   `.gitignore` 在 `*.log` 后新增 `!plans/evidence/**/*.log`；3 个规模证据
   日志现在可跟踪。详见 §9.1。
10. **4 个 `hs_err` 崩溃日志未追查**：均为 `SIGSEGV`，来源不明；发布前应
    确认是否与 Datalevin/LMDB 或旧运行时有关。日志被忽略但仍在工作树。
11. **当前客户端产物与基线尺寸不同**：本稿生成时
    `resources/public/js/main.js` 为 2,890,249 bytes（10:52），基线 G3 记录
    2,753,029 bytes。这是并行重建的结果；发布说明已把该数字标为基线值，
    最终发布前以最终 `client-release` 日志重新记录。
12. **`RUNNING.md` 条件 5 的测试数未写死**：这是有意的，避免并行施工时数字
    过期；最终发布说明/报告中必须写最终树的实际数字。
13. **`HACK.md` §13.4 的“已修复”只覆盖当前树复检**：没有覆盖全部 CLI 组合
    （如 `--include-secrets`、错误密码、merge 幂等、replace 重复）。发布说明
    已把未覆盖项列出；不要把当前复检扩大成“备份 CLI 全绿”。

## 8. 未执行项

- 未运行 `pixi run test`（按任务约束，避免与并行 `src/` 编辑冲突）。
- 未运行 `pixi run client-release`（当前产物由其他工人生成；尺寸已变化）。
- 未重跑 E2E 01–22；只引用 `plans/e2e-0.0.1-results.md` 的既有结论。
- 未重跑 E2E 14/17/18/22；未验证 D6/D9 修复。
- 未执行真实 ENOSPC 注入（需要 root/tmpfs）。
- 未执行 Git 写操作；未删除仓库内文件（只清理了自建 `/tmp` 测试目录）。

## 9. 收口记录（2026-11-05 10:54–10:58）

本轮处理上一版报告的三项收口：忽略规则、`HACK.md` 编号、发布说明易漂移数字。
仍未执行 Git 写操作。

### 9.1 `.gitignore` 与 `git check-ignore` 验证

变更：

```gitignore
# Node / client
node_modules/
dist/
chrome-profile/
**/chrome-profile/

# Local tooling and runtime artifacts
.pi/
.lsp/
*.log
hs_err_pid*.log
!plans/evidence/**/*.log
```

`git check-ignore -v` 逐项输出（2026-11-05 10:52）：

```text
$ git check-ignore -v plans/evidence/scale-dom/list.log
.gitignore:31:!plans/evidence/**/*.log	plans/evidence/scale-dom/list.log

$ git check-ignore plans/evidence/scale-dom/list.log; echo $?
1

$ git check-ignore -v hs_err_pid2905205.log
.gitignore:30:hs_err_pid*.log	hs_err_pid2905205.log

$ git check-ignore -v plans/evidence/no-model/chrome-profile/x
.gitignore:24:**/chrome-profile/	plans/evidence/no-model/chrome-profile/x
```

解释：`-v` 对未被忽略的路径会显示命中的否定规则（这里是
`!plans/evidence/**/*.log`）；不带 `-v` 时退出码为 1，表示未被忽略。
`git ls-files --others --exclude-standard plans/evidence/scale-dom` 列出
`list.log`、`messages.log`、`pure-bench.log`，证明它们可随提交进入仓库。
`git status --porcelain -uall` 同样给出 `?? plans/evidence/scale-dom/list.log`
等三行，而不是把它们藏在 ignored 列表里。
`hs_err_pid2905205.log` 仍命中 `hs_err_pid*.log`，`chrome-profile` 目录命中
`**/chrome-profile/`。

效果（10:58 统计）：

| 项 | 变更前 | 变更后 |
|---|---:|---:|
| `git status --porcelain -uall` 文件数 | 1410 | 约 965 |
| `plans/` 未跟踪文件数 | 469 | 约 24 |
| `plans/evidence/` 未跟踪文件数 | 454 | 8 |
| 未跟踪且未忽略的 chrome-profile 文件 | 449 | 0 |
| `git status --ignored` 顶层忽略条目 | 55 | 33 |

`plans/evidence/` 现在未跟踪且未忽略的 8 个文件是：`no-model` 3 个、
`scale-draft` 2 个、`scale-dom` 3 个日志。449 个 chrome-profile 文件已被
忽略；未删除任何文件。

### 9.2 `HACK.md` 编号修正

改动标题：

| 原标题 | 新标题 |
|---|---|
| `## 8 应用启动与维护入口` | `## 12 应用启动与维护入口` |
| `### 8.1 受监督恢复入口（2026-11-04，已复跑）` | `### 12.1 受监督恢复入口（2026-11-04，已复跑）` |
| `## 12 0.0.1 发布验证实测（2026-11-05）` | `## 13 0.0.1 发布验证实测（2026-11-05）` |
| `### 12.1`–`### 12.5` | `### 13.1`–`### 13.5` |

保留不动：第一个 `## 8 聚焦集成命令与运行时基线` 及其 `### 8.1 运行时快照`、
`### 8.4`。因此 `HACK.md §8.4` 仍指向原全量/真实应用冒烟小节，没有歧义。

已更新的引用：

- `HACK.md` 内部：§8.4 的“见 §3.5 与 §12”改为 §13；模型端点边界 §12.1 改为
  §13.1。
- `HISTORY.md`：两处 `HACK.md §12.4` 改为 §13.4。
- `plans/offline-restore-0.0.1.md`：`HACK.md §12.4` 改为 §13.4。
- `plans/release-notes-0.0.1.md`：证据索引 `HACK.md §9–§12` 改为 §9–§13。
- 本报告：§4.3/§4.4/§7 的 §12.x 引用改为 §13.x。

未发现仓库内其他 `HACK.md §8.1` 交叉引用；`AGENTS.md` 的 §8.1 指它自己的
文档纪律小节，不是 HACK 小节。编号顺序现在为 §1–§13，第二个 §8 不再存在。

### 9.3 发布说明易漂移数字

`plans/release-notes-0.0.1.md` 的处理位置：

| 位置 | 处理 |
|---|---|
| §2 表前说明 | 新增“表中耗时、文件大小、包数、测试/断言数与性能数字都是该次测量的基线值，不是最终树当前值；以最终门禁日志为准” |
| §2 第 2 项 | `2 s` 改为“该次约 2 s（以最终日志为准）” |
| §2 第 3 项 | `134 packages` 改为“该次 …（以最终 `npm ci` 日志为准）” |
| §2 第 4 项 | `main.js` 2,753,029 bytes 与 CSS 570,383 bytes 标为“基线树”，并注明以最终 `client-release` 日志为准 |
| §2 第 5 项 | 14 s/18.79 s 标为基线/该次复检，并注明以最终 `app-check` 日志为准；临时隔离路径标为“该次” |
| §2 第 6 项 | 3406 tests / 22121 assertions、343 s 标为基线树，并注明当前树未重跑、以最终 `pixi run test` 日志为准 |
| §2 第 7 项 | 端口 39697 标为“该次”，并注明 `--port 0` 每次不同 |
| §2 第 11/12 项 | jsdom 与两万字草稿数字标为“该次测量”“不是最终树基准” |
| §3 标题与表前说明 | 18 PASS / 4 BLOCKED 标为“2026-11-05 该次执行”；定向测试/断言数标明“不是最终树门禁” |
| §6 第 1 项 | `git ls-files` 只有 `AGENTS.md` 标为“本稿生成时” |
| §6 第 6 项 | 原文“chrome-profile 未忽略”改为“已由 .gitignore 忽略，提交前确认” |

未改的固定值：版本 `0.0.1`、Pixi 运行时版本、Pixi 二进制大小与 SHA-256、
备份密码最小长度；这些是契约或固定输入，不是易漂移的测试计数。

BLOCKED 复核：E2E 14/17/18/22 在 §3 表中仍全部标为 **BLOCKED**，§4.1 逐条
给出阻塞原因；全文没有把它们写成通过，也没有出现“E2E 完整通过”的肯定句
（只有“不能写成 E2E 完整通过”的否定说明）。
