# Herdchestra 0.0.1 发布门禁基线

- 日期：2026-11-05 07:55–09:25 CST（基线 07:55–08:07；修复与复验 08:08–09:25）
- 工作目录：`/home/kunweiz/Desktop/vibe/Herdchestra`
- 基线 HEAD：`015229e28bd0fdddc14060a28f1348185c6e765b`（2026-11-03 17:40:58 +0800，`Implement code changes to enhance functionality and improve performance`）
- 模式：基线建立 + 修复阶段。修改了 `test/hc/compose/nudge/owner_integration_test.clj` 与 `.gitignore`；未 commit、未 push、未删除文件；未改 `src/`。
- 总体结论：**FAIL（仅剩发布提交与发布说明）**。G1–G7 已通过：最终全量 3406 tests / 22121 assertions，0 failures / 0 errors；`app-check` 09:25 退出 0，health `:ok true` 且 fleet/fleet-gc 已启动。阻塞项是 G8：仓库只跟踪 `AGENTS.md`，产品源码与 `.gitignore` 均未提交；条件 8 的发布说明缺失。E2E/性能/离线恢复仍 NOT RUN。

## 0. 门禁总览

| # | 门禁 | 命令 | 标签 |
|---|---|---|---|
| G1 | Pixi 锁定安装 | `/tmp/pixi install --locked` | **PASS**（仓库内为既有环境；另在临时干净目录复验） |
| G2 | 客户端依赖 | `/tmp/pixi run client-install` | **PASS** |
| G3 | 客户端 release 构建 | `/tmp/pixi run client-release` | **PASS** |
| G4 | 启动自检 | `/tmp/pixi run app-check` | **PASS**（基线 + 08:47/09:08/09:25 复验） |
| G5 | 全量测试 | `/tmp/pixi run test` | **PASS**（3406 tests / 22121 assertions，0 failures / 0 errors） |
| G6 | 真实启动冒烟 | `pixi run clojure -M:server ... --port 0` + curl + SIGTERM | **PASS** |
| G7 | 版本一致性 | `hc.version` / `pixi.toml` / `package.json` / `--help` / health | **PASS** |
| G8 | 发布提交干净 | `git status --porcelain` / `git ls-files` | **FAIL**（`.gitignore` 子项已修复；提交仍未建立） |

## 1. 环境与工具版本

### 1.1 宿主

| 项 | 实测 | 备注 |
|---|---|---|
| OS | Linux 7.3.0-rc6-1-MANJARO x86_64 | 12 核，62 GiB 内存 |
| Java | OpenJDK 27（`java -version`） | HACK.md 记录宿主为 26.0.2.1；已漂移。不是发布运行时 |
| Clojure CLI | 1.12.5.1664 | 宿主路径 |
| Node | v26.10.0 | HACK.md 记录 26.8.1；已漂移。不是发布运行时 |
| Pixi | `command -v pixi` 为空；`/tmp/pixi` 在本会话开始时不存在 | 见下 |

### 1.2 Pixi 获取与版本

`RUNNING.md` 与 `HACK.md` §1/§6 要求使用 `/tmp/pixi` 0.83.0，但该文件已不存在。本次从 GitHub Releases 重新取得 `v0.83.0` 的 `pixi-x86_64-unknown-linux-musl`：

- 路径：`/tmp/pixi`
- 版本：`pixi 0.83.0`
- 大小：71,427,392 bytes
- SHA-256：`50571451e96f4a82cf675cd83baaf66fff9b718f0aaf153900b9faf4c29bd401`

本报告所有 `pixi` 命令均使用该二进制。宿主 `PATH` 仍无 `pixi`。

### 1.3 Pixi 运行时（发布目标运行时）

| 项 | 实测 |
|---|---|
| JDK | `21.0.10-internal`（`.pixi/envs/default/lib/jvm`） |
| Clojure CLI | `1.12.6.1673` |
| Node | `v22.23.4` |
| Pixi 缓存 | `/home/kunweiz/.cache/rattler/cache` |

与 `RUNNING.md` 的 JDK 21 / Clojure 1.12.6 / Node 22 一致。

### 1.4 版本源一致性

| 位置 | 值 |
|---|---|
| `src/hc/version.cljc` | `"0.0.1"` |
| `pixi.toml` | `version = "0.0.1"` |
| `package.json` | `"version": "0.0.1"` |
| `--help` | `Herdchestra 0.0.1 server` |
| `GET /healthz` | `:version "0.0.1"` |

### 1.5 环境中的既有进程

基线开始前已有另一个 `hc.server.app` 进程：PID 746563，07:25:23 启动，监听 `127.0.0.1:20368`，配置为 `/tmp/hc-acceptance-final-20261105-0554/large-state-browser/config.edn`。它不是本次基线启动的，未触碰。本次冒烟使用临时端口与独立数据目录，无端口冲突。

## 2. 门禁明细

### G1 `pixi install --locked` — PASS

- 命令：`/tmp/pixi install --locked`
- 退出码：0
- 耗时：2 s
- 证据尾部：

```text
The default environment has been installed.
```

仓库内 `.pixi/envs/default` 已存在，因此这次是对既有环境的锁定校验/补齐，不是干净安装。为验证“干净环境”条件，另在 `/tmp/hc-baseline-clean` 复制 `pixi.toml` 与 `pixi.lock` 后执行同一命令：

- 退出码：0
- 耗时：1 s
- 运行时检查：

```text
21.0.10-internal
1.12.6
/tmp/hc-baseline-clean/.pixi/envs/default/lib/jvm
```

限制：rattler 包缓存已预热，干净安装主要验证锁文件与环境组装，没有真正走冷下载/断网路径。

### G2 `pixi run client-install` — PASS

- 命令：`/tmp/pixi run client-install`（即 `npm ci`）
- 退出码：0
- 耗时：2 s
- 结果：`added 134 packages in 1s`
- 警告：`core-js@2.6.12` 已停止维护；`whatwg-encoding@3.1.1` 建议改用 `@exodus/bytes`。均为传递依赖警告，不阻断构建。

### G3 `pixi run client-release` — PASS

- 命令：`/tmp/pixi run client-release`（`npm run release` → `build:css` + `npx shadow-cljs release :app`）
- 退出码：0
- 耗时：23 s
- 构建摘要：

```text
[:app] Build completed. (506 files, 61 compiled, 17 warnings, 18.25s)
```

- 产物：

| 文件 | 大小 |
|---|---|
| `resources/public/js/main.js` | 2,753,029 bytes |
| `resources/public/css/framework7-bundle.css` | 570,383 bytes |

- 日志中共 19 行 `WARNING`，构建摘要计 17 个 `:infer-warning`；典型内容为 `Cannot infer target type in expression (. props -versionId)`（`src/hc/client/speech/ui.cljs:755` 等）。不阻断 release，但属于发布前应清理的噪声。

### G4 `pixi run app-check` — PASS（基线 + 08:47/09:08/09:25 复验）

- 命令：`/tmp/pixi run app-check`（`clojure -M:server -m hc.server.app --check --port 0`）
- 退出码：0
- 耗时：16 s
- 健康输出（08:00 基线快照，当时写入默认目录；09:25 隔离复验见下）：

```clojure
{:store {:open? true, :path "/home/kunweiz/.local/share/herdchestra/db"},
 :config {:host "127.0.0.1", :port 0,
          :workspace-root "/home/kunweiz/Desktop/vibe/Herdchestra",
          :data-dir "/home/kunweiz/.local/share/herdchestra",
          :log-level :info},
 :gateway {:epoch 1, :seq 1, :max-pending 4096, :subscriptions 0, :stale 0},
 :models {:configured? false},
 :ok true,
 :sessions {:count 1},
 :version "0.0.1",
 :uptime-ms 3}
```

- 启动期警告：4 条命名空间遮蔽（`ensure`、`key`、`identity`、`send`）与 1 条反射警告（`hc/server/attachments/ingest.clj:834:30 - reference to field close can't be resolved`）。
- 历史副作用（08:47 及更早）：`app-check` 当时写入用户默认目录 `~/.local/share/herdchestra`；09:25 复验已由 `--check` 的临时根消除，见下。
- 中途回归与恢复：08:44 复跑曾报 `START_FAILED: class clojure.lang.Atom cannot be cast to class clojure.lang.IFn`；08:46 复跑曾报 `{:error :UNHEALTHY}`（`:fleet-gc {:started? false}`、`:fleet {:available? false, :reason :MASTER_REF_UNAVAILABLE}`）。server-app 修复后，08:47 复跑退出 0，健康含 `:ok true`、`:version "0.0.1"`、`:store {:open? true}`、`:gateway` 计数；`:fleet-gc` 仍为 `:started? false`、`:fleet` 仍 `:available? false`，但已不阻断 health。
- 最终复验（09:25，稳定树，`/tmp/final-app-check4.log`）：退出 0，14 s。自动隔离到 `/tmp/hc-check-3580104557462498968/{data,work}`（`src/hc/server/app.clj` 的 `check-options` 在未显式传路径时创建临时根），health 关键字段：`:ok true`、`:version "0.0.1"`、`:store {:open? true, :path "/tmp/hc-check-3580104557462498968/data/db"}`、`:gateway {:epoch 1, :seq 1, :max-pending 4096, :subscriptions 0, :stale 0}`、`:models {:configured? false}`、`:sessions {:count 1}`、`:fleet-gc {:configured? true, :started? true, :status :started}`、`:fleet {:configured? true, :available? true, :status :started}`。
- 默认目录副作用已消除：09:25 复验不再写 `~/.local/share/herdchestra`；`pixi.toml` 的 `app-check` 命令文本未变，隔离由 `hc.server.app` 的 `--check` 路径实现。

### G5 `pixi run test` — PASS（09:18–09:24 最终全量）

- 命令：`/tmp/pixi run test`（`clojure -X:test:compose:server:client`）
- 最终结果（09:18:30–09:24:14，343 s，`/tmp/final-full-test3.log`）：**退出 0，3406 tests / 22121 assertions，0 failures / 0 errors**。运行窗口内 `src/` 与 `test/` 无文件改动（`find -newermt '2026-11-05 09:18:30'` 计数 0），因此这是当前树的干净结果。本任务修复的 `hc.compose.nudge.owner-integration-test` 无失败记录。
- 前一版本（09:01:53–09:07:48，355 s）：退出 0，3394 tests / 22083 assertions，0 failures / 0 errors；但该次运行期间 `src/hc/server/app/system.clj`（09:08:24）、`src/hc/server/app.clj`（09:05:45）与 4 个 app 测试仍被 server-app 修改，故不作为最终证据。两次运行之间新增 12 个测试 / 38 个断言。
- 基线失败（08:00，作为修复前证据）：退出 1，313 s，汇总：

```text
Ran 2970 tests containing 19741 assertions.
4 failures, 0 errors.
Execution error (ExceptionInfo) at cognitect.test-runner.api/test (api.clj:30).
Test failures or errors occurred.
```

- test.check：106 次属性测试全部 `:result true`，0 次失败。seed 范围 `1793836837486`–`1793837126809`（每次运行随机，未固定 seed）。代表性输出：

```text
{:result true, :num-tests 100, :seed 1793837092793, :time-elapsed-ms 7,
 :test-var "saved-implies-matching-persisted-revision"}
```

- 失败集中在同一个测试的 4 个断言：

```text
FAIL in (pending-session-binding-applies-to-the-next-user-turn) (owner_integration_test.clj:239)
expected: (wait-for (fn* [] (= 1 (count (clojure.core/deref requests)))) 3000)
  actual: (not (wait-for ... 3000))

FAIL in (pending-session-binding-applies-to-the-next-user-turn) (owner_integration_test.clj:240)
expected: (= "beta" (get-in (first (clojure.core/deref requests)) [:snapshot :connection]))
  actual: (not (= "beta" nil))

FAIL in (pending-session-binding-applies-to-the-next-user-turn) (owner_integration_test.clj:242)
expected: (= "beta" (get-in (state handle) [:session :model-binding :connection-id]))
  actual: (not (= "beta" "alpha"))

FAIL in (pending-session-binding-applies-to-the-next-user-turn) (owner_integration_test.clj:244)
expected: (nil? (get-in (state handle) [:session :pending-model-binding]))
  actual: (not (nil? {:binding {:connection-id "beta", ...}, :mode :next-round}))
```

- 定点复跑（只跑该命名空间，使用与 `pixi run test` 相同的别名集）：

```text
/tmp/pixi run clojure -M:test:compose:server:client -n hc.compose.nudge.owner-integration-test
Ran 23 tests containing 150 assertions.
4 failures, 0 errors.
```

退出码 1，耗时 9 s。失败可稳定复现，不是全量并发下的偶发超时。

- 根因线索：用同一测试夹具提交 `:send-message` 时，回执是

```clojure
{:status :refused,
 :error {:code :SESSION_PERSISTENCE_UNAVAILABLE,
         :message "the Owner session port cannot persist a business change"}}
```

`src/hc/compose/owner/runtime.clj:270` 的 `commit-session-change!` 在 session 状态变化时要求配置 `[:session-port :commit-session!]`；`test/hc/compose/nudge/owner_integration_test.clj:222` 的夹具（`start-owner`，第 39–55 行）没有提供 session port。该测试的初始 `:pending-model-binding` 使发送动作产生 session 变更，于是在 Owner 提交前被拒绝，模型请求从未发出。

- 修复（2026-11-05 08:10）：确认这是测试夹具缺契约，不是 runtime 缺陷。根 AGENTS.md §5.1 与 `src/hc/compose/owner/AGENTS.md` 第 882 行明确要求“session 变更必须先经 `:commit-session!` 持久化”；`src/hc/compose/owner.clj` 的 `start-owner!` 文档也写明“A port without them refuses a session change”。因此保留 runtime 屏障，只改测试夹具。
- 修法：`test/hc/compose/nudge/owner_integration_test.clj` 增加 `hc.server.sessions`、`hc.server.sessions.ids`、`hc.server.sessions.ports` 依赖；用 `sessions/new-session` 建合法会话，补上 `:model-binding` 与 `:pending-model-binding`，先 `sessions/commit-session!` 到 `ports/memory-persistence`，再把 committed session 与 `(sessions/session-port {:persistence persistence})` 传给 Owner。与现有 `test/hc/compose/owner/session_run_bridge_test.clj` 的真实端口夹具惯例一致，没有伪造领域终态。
- 定点复验（PASS）：`/tmp/pixi run clojure -X:test:compose :nses '[hc.compose.nudge.owner-integration-test]'` 退出 0。08:14 为 **23 tests / 152 assertions**，08:45 为 **24 tests / 162 assertions**（其他工人给同一命名空间新增了测试），两次均 **0 failures / 0 errors**。修复本身新增 2 个断言，来自 `new-session` 与 `commit-session!` 前置校验。
- 中途阻塞（08:11–08:28）：六次重试全量入口，均被其他工人正在编辑的文件阻断：`hc.client.browser.view`（`view.cljc:487:79`，`Unmatched delimiter: )`）、`hc.client.settings.view`（`view.cljc:731:44`，同前）、`hc.server.model-auth.service`（`service.clj:1074:40`，`Unmatched delimiter: ]`）、`hc.compose.owner.guards`（`guards.clj:1077:42`，`Unable to resolve symbol: ctx`）、`hc.server.program.runtime`（`runtime.clj:130:36`，`Unable to resolve symbol: rt`）、`hc.compose.owner.runtime-test`（`runtime_test.clj:252:51`，`Unmatched delimiter: )`）。这些文件不在本任务可改范围，已报告 Main；最终 09:07 全量已通过。

- 全量修复前快照（08:34:48–08:42:13，715 s）：**3344 tests / 21716 assertions，79 failures / 19 errors**。失败集中在 app 启动 `:blocked? true`（底层 `ClassCastException: clojure.lang.Atom cannot be cast to clojure.lang.IFn`）、backup/restore、fleet fork NPE、browser CDP 类型转换等并发工作面；本任务修复的 `hc.compose.nudge.owner-integration-test` 无失败记录。
- 全量第二次（08:47:04–08:53:54，410 s，app 装配修复后）：**3378 tests / 22023 assertions，13 failures / 1 error**。剩余失败按命名空间：

| 命名空间 | 数量 | 代表首行错误 |
|---|---:|---|
| `hc.server.app.fleet-commands-test` | 7 | `FLEET_FORK_FAILED`，message `Cannot invoke "Object.getClass()" because "target" is null` |
| `hc.server.browser-cdp-smoke-test` | 5 | `:status :refused, :error {:code :takeover-active, :takeover-reason :wheel}`；另 1 个 `NullPointerException: Cannot invoke "Object.toString()" because "s" is null`（`clojure.string/includes?`） |
| `hc.server.app.system-test` | 1 | `launch-model-placeholders-are-projected-before-the-adapter`：`expected: (false? (model-adapter/model-configured? launch)) actual: (not (false? true))` |
| `hc.server.app.restore-supervisor-test` | 1 | `expected: (true? (:needs-attention? next)) actual: (not (true? false))` |

- 上述 4 个命名空间在 08:54 定点复跑：**55 tests / 383 assertions，0 failures / 0 errors**，退出 0；说明剩余 14 项与全量顺序/共享状态相关，或已被并行修复。最终 09:07 全量已 0 failures / 0 errors。
- 08:55 重试曾被 `src/hc/server/app/system.clj:2781:27` 的编译错误阻断（`Unable to resolve symbol: extension-component`）；server-app 修复后，09:01–09:07 的最终全量通过（见本节开头）。

### G6 真实启动冒烟 — PASS

- 命令：

```bash
/tmp/pixi run clojure -M:server -m hc.server.app \
  --host 127.0.0.1 --port 0 \
  --data-dir /tmp/hc-baseline-data \
  --workspace-root /tmp/hc-baseline-work \
  --static-root resources/public
```

- `--port 0` 会打印实际地址：`Herdchestra listening on http://127.0.0.1:39697/`
- `GET http://127.0.0.1:39697/healthz` → HTTP 200，关键字段：

```clojure
{:store {:open? true, :path "/tmp/hc-baseline-data/db"},
 :gateway {:epoch 1, :seq 1, :max-pending 4096, :subscriptions 0, :stale 0},
 :models {:configured? false},
 :ok true,
 :version "0.0.1"}
```

- `GET http://127.0.0.1:39697/` → HTTP 200，返回客户端 shell：`<title>Herdchestra 工作台</title>`、`/css/workbench.css`、`/js/main.js`。
- 停止：向 JVM 发送 SIGTERM，`pixi` 包装进程退出码 143（128+15）。日志没有显式 shutdown 行；`src/hc/server/app.clj:88-96` 注册了 shutdown hook。随后用同一 `--data-dir` 再次启动成功且 `:store {:open? true}`，说明上一进程已释放存储锁；这不能证明每个子系统都打印了关闭日志，但排除了崩溃残留锁。
- 注意：打印的 URL 末尾带 `/`。直接拼 `//healthz` 会被路由成 `/` 并返回 HTML；健康检查应使用去尾斜杠的基址。
- 首次冒烟曾因上述双斜杠误判一次，修正 URL 后得到上述结果；两次启动的进程均已停止，`pgrep -af hc.server.app` 只剩 1.5 节所述的外部既有进程。

### G7 版本一致性 — PASS

见 1.4。`hc.version`、`pixi.toml`、`package.json`、`--help`、`/healthz` 全部为 `0.0.1`。

### G8 发布提交干净 — FAIL

- `git ls-files | wc -l` = **1**，唯一被跟踪的文件是 `AGENTS.md`。
- `git ls-tree -r --name-only HEAD` 与 `origin/master` 都只有 `AGENTS.md`。
- 因此当前 HEAD 与远端都不包含 `src/`、`test/`、`resources/`、构建清单或运行文档。`0.0.1` 没有可发布的提交载体。
- `.gitignore` 覆盖已修复：新增 `out-*/`、`.pi/`、`hs_err_pid*.log`，保留 `target/`、`.cpcache/`、`node_modules/`、`.shadow-cljs/`、`.pixi/`、`*.log` 等；`git check-ignore` 复核 `.gitignore`、`src/`、`test/`、`resources/`、`plans/`、`deps.edn`、`shadow-cljs.edn`、`pixi.toml`、`pixi.lock`、`package.json`、`package-lock.json`、`HACK.md`、`HISTORY.md`、`RUNNING.md`、`AGENTS.md` 均为 trackable（未被忽略）。
- 忽略规则修复后，`git status --porcelain` 从 20 条顶层条目降为 15 条；剩余条目都是应提交的产品/文档文件。发布提交本身仍未建立，所以 G8 仍是 **FAIL**。

## 3. 失败与可疑项清单

1. **[已修复（定点与全量中均通过）] 基线 4 个测试失败。** `hc.compose.nudge.owner-integration-test/pending-session-binding-applies-to-the-next-user-turn`（`test/hc/compose/nudge/owner_integration_test.clj:222`，断言行 239/240/242/244）。基线回执为 `:SESSION_PERSISTENCE_UNAVAILABLE`；相关屏障在 `src/hc/compose/owner/runtime.clj:270`，测试夹具缺少 session port。已按 §5.1 用真实 `sessions/session-port` + memory persistence 修复夹具；定点复跑 0 failures / 0 errors（08:14 23/152；08:45 24/162）；08:34 全量中该命名空间无失败记录。
2. **[阻断] 发布提交不含产品源码。** `git ls-files` 只有 `AGENTS.md`；HEAD 和 `origin/master` 都只有该文件。发布条件 6 直接失败。
3. **[已修复] `.gitignore` 覆盖不足。** 原规则未覆盖 `out-artifacts-ui/`、`out-backup-password-dom/`、`out-doc/`、`out-workbench-draft-storage.NK4I6V/`、`.pi/`。现已增加 `out-*/`、`.pi/`、`hs_err_pid*.log`，并保留既有构建产物规则；`git status` 中这些条目已消失。`.gitignore` 自身仍未被 Git 跟踪，需随发布提交纳入版本库。
4. **[已修复] `app-check` 写入默认用户数据目录。** `src/hc/server/app.clj` 的 `check-options` 现在为 `--check` 自动创建临时根（`temp-check-root`），09:25 复验写入 `/tmp/hc-check-3580104557462498968/{data,work}`，health 的 `:data-dir` 指向该临时目录，不再触碰 `~/.local/share/herdchestra`。
5. **[中] 仓库根目录有 4 个 JVM 崩溃日志。** `hs_err_pid2905205.log`、`hs_err_pid3003787.log`、`hs_err_pid3597664.log`、`hs_err_pid455536.log`，时间 2026-11-04 01:33 至 2026-11-05 00:20，头部均为 `SIGSEGV`。被 `*.log` 忽略，未清理。本次基线运行没有产生新的 `hs_err`；崩溃来源未追查，但与 0.0.1 的稳定性声明相关，建议在发布前确认是否为 Datalevin/LMDB 或旧运行时的已知问题。
6. **[低] 编译警告。** 4 条命名空间遮蔽（`ensure`、`key`、`identity`、`send`）、1 条反射警告（`hc/server/attachments/ingest.clj:834`）、17 条 CLJS `:infer-warning`。不阻断，但发布构建不应长期保留。
7. **[低] npm 传递依赖弃用警告。** `core-js@2.6.12`、`whatwg-encoding@3.1.1`。`npm ci` 成功。
8. **[低] Pixi 引导脆弱。** 宿主无 `pixi`，`/tmp/pixi` 会在重启/清理后消失；HACK.md 记录了该绕行，但发布门禁依赖一个临时路径。建议固定二进制位置或校验和。
9. **[低] 干净安装依赖热缓存。** 临时目录安装 1 s 完成，未验证冷下载；锁文件本身通过 `--locked` 校验。
10. **[信息] `-M:test` 单独使用无法加载 compose 命名空间。** 直接跑 `clojure -M:test -n ...` 会报 `Could not locate missionary/core...`。`pixi run test` 使用 `-X:test:compose:server:client`，别名集正确；这是使用方式差异，不是产品缺陷。
11. **[信息] 存在外部既有服务。** PID 746563 监听 `127.0.0.1:20368`，配置在 `/tmp/hc-acceptance-final-20261105-0554/large-state-browser/config.edn`，非本次基线启动。
12. **[阻断，外部] 全量测试被并发编辑阻断。** 08:11、08:12、08:13、08:16、08:22、08:28 六次重试 `clojure -X:test:compose:server:client` 时，先后遇到：`src/hc/client/browser/view.cljc:487:79` 与 `src/hc/client/settings/view.cljc:731:44` 的 `Unmatched delimiter: )`；`src/hc/server/model_auth/service.clj:1074:40` 的 `Unmatched delimiter: ]`；`src/hc/compose/owner/guards.clj:1077:42` 的 `Unable to resolve symbol: ctx`；`src/hc/server/program/runtime.clj:130:36` 的 `Unable to resolve symbol: rt`；`test/hc/compose/owner/runtime_test.clj:252:51` 的 `Unmatched delimiter: )`。同一时段 `src/hc/compose/cancel.clj`、`src/hc/core/relay/core.clj` 也出现过瞬时读取错误。这些文件不在本任务可改范围，未修改；已报告 Main，等待 owner 收尾后重跑。08:12:55 后 `settings/view`、08:15:11 后 `model_auth/service`、08:17:24 后 `guards` 均可 `require`；08:29:58 后 `runtime_test.clj` 可完整读取（26 个顶层 form）。08:55 最终重试又遇到 `src/hc/server/app/system.clj:2781:27` 的 `Unable to resolve symbol: extension-component`，server-app 正在重构扩展组件。
13. **[已修复，其他工人] nudge worker 行为断言。** `hc.compose.nudge.owner-integration-test/worker-turn-end-dispatches-responsibility-nudge`（`owner_integration_test.clj:433`）在 08:13 的组合复跑中失败：expected `nudge-worker-tenure-2-:agent-2-1`，actual `nudge-worker-tenure-2-agent-2-1`。其他工人在 08:13:59 把 worker-id 归一化为 `(if (keyword? worker-id) (name worker-id) worker-id)`；08:14 复跑该命名空间 23 tests / 152 assertions 通过。此项不是本任务的 session-port 修复。
14. **[已清理，其他工人] 临时调试打印。** 08:23 复跑 `hc.compose.nudge.owner-integration-test`（23 tests / 152 assertions）时，stdout 出现 7 次 `:STOP-CLOSE-DEBUG`。完整源码行为 `src/hc/compose/owner/runtime.clj:1199`：`_ (println :STOP-CLOSE-DEBUG opts close? (contains? opts :close-store?))`。08:25 复核该行已移除；本任务未修改该文件。
15. **[已恢复，其他工人] `app-check` 中途回归。** 08:44 复跑报 `START_FAILED: class clojure.lang.Atom cannot be cast to class clojure.lang.IFn`；08:46 复跑报 `{:error :UNHEALTHY}`（`:fleet-gc {:started? false}`、`:fleet {:available? false, :reason :MASTER_REF_UNAVAILABLE}`）。server-app 修复后，08:47 复跑退出 0，`:ok true`；09:25 复验进一步显示 `:fleet-gc {:started? true}`、`:fleet {:available? true, :status :started}`。与全量中大量 app 启动失败同源，非本任务修改的测试或 `.gitignore` 引起。
16. **[已修复，并发中间态] 扩展组件重构导致 app-check 两次失败。** 09:00 左右 `src/hc/server/app/system.clj` 解构键为 `extensions-ref`，调用方仍传 `:extensions extensions-ref`，factory 内 `(deref nil)` 抛 `Cannot invoke "java.util.concurrent.Future.get()" because "fut" is null`，`--check` 报 `{:error {:code :OWNER_START_FAILED ... :error :DEFAULT_SESSION_FAILED}}`，退出 1；修好键名后又出现一次 `OWNER_START_FAILED: the state transaction failed`（`src/hc/server/storage/engine/datalevin.clj:119` 包装的底层事务错误），用全新 `/tmp/final-diag` 数据目录复现时启动成功，未再出现。server-app 把调用方改为 `:extensions-ref extensions-ref` 并定稿后，09:25 `app-check` 与 09:18 全量均通过。
17. **[已复验] 08:47 全量遗留的 4 个失败命名空间。** `hc.server.app.fleet-commands-test`（7）、`hc.server.browser-cdp-smoke-test`（5）、`hc.server.app.system-test`（1）、`hc.server.app.restore-supervisor-test`（1）在 09:25 稳定树上定点复跑：fleet-commands + system + restore-supervisor 合计 **58 tests / 330 assertions，0 failures / 0 errors**；browser-cdp-smoke **1 test / 68 assertions，0 failures / 0 errors**。这些失败属于并发中间态，不是 0.0.1 的稳定回归。

## 4. 工作树状态

基线时 `git status --porcelain` 有 20 条顶层未跟踪条目；`.gitignore` 修复后为 **15 条顶层条目、1405 个文件**（09:26 统计），无已修改的已跟踪文件。全部剩余条目都应提交：

| 类型 | 条目 | 文件数 | 分类 |
|---|---|---|---|
| 文件 | `.gitignore` | 1 | 构建配置（自身必须提交） |
| 文件 | `deps.edn`、`pixi.toml`、`pixi.lock`、`package.json`、`package-lock.json`、`shadow-cljs.edn` | 6 | 产品构建清单 |
| 目录 | `src/` | 542 | 产品源码 |
| 目录 | `test/` | 380 | 测试源码（含 `test/js/*` 渲染 shim） |
| 目录 | `resources/` | 6 | 非构建静态资源（`index.html`、`workbench.css`、pair-hint、tessdata） |
| 文件 | `RUNNING.md`、`HACK.md`、`HISTORY.md`、`PROMPT.md` | 4 | 运行/历史文档 |
| 目录 | `plans/` | 466 | 计划/证据文档（含本报告、E2E runbook、gap-scan、各工人产物） |

应忽略且已从状态中消失的条目：`.pixi/`、`node_modules/`、`.cpcache/`、`.shadow-cljs/`、`target/`、`out-*/`（含 `out-artifacts-ui/`、`out-backup-password-dom/`、`out-doc/`、`out-check*/`、`out-workbench-draft-storage.NK4I6V/` 等）、`.pi/`、`resources/public/js/`、`resources/public/css/framework7-bundle.css`、`resources/public/smoke/js/`、`*.log`（含 4 个 `hs_err_pid*.log`）。`hs_err` 日志按用户要求保留为证据，只忽略、不删除。

`git check-ignore` 复核：`.gitignore`、`src/`、`test/`、`resources/`、`plans/`、`deps.edn`、`shadow-cljs.edn`、`pixi.toml`、`pixi.lock`、`package.json`、`package-lock.json`、`HACK.md`、`HISTORY.md`、`RUNNING.md`、`AGENTS.md` 均未被忽略。

提交信息：

```text
015229e28bd0fdddc14060a28f1348185c6e765b
2026-11-03 17:40:58 +0800
Implement code changes to enhance functionality and improve performance
```

`git status -sb` 显示 `## master...origin/master`，无 ahead/behind；远端同样只有 `AGENTS.md`。

## 5. `RUNNING.md` 0.0.1 发布条件对照

| 条件 | 标签 | 说明 |
|---|---|---|
| 1. `pixi install --locked` 干净环境成功 | **PASS**（有限） | 仓库内退出 0；临时干净目录退出 0，JDK/Clojure 正确。热缓存，未验证冷下载 |
| 2. `client-install` + `client-release` 退出 0，产物存在 | **PASS** | 134 包；`main.js` 与 CSS 均存在 |
| 3. `app-check` 退出 0，health 含版本/存储/网关 | **PASS** | 09:25 稳定树复验退出 0：`:ok true`、`:version "0.0.1"`、`:store {:open? true}`、网关计数齐全，`:fleet-gc {:started? true}`、`:fleet {:available? true}`；自动隔离临时目录 |
| 4. 真实启动 `/healthz` 200，`/` 返回 shell | **PASS** | 临时端口 39697；两请求均 200 |
| 5. 全量测试通过并记录数量/seed | **PASS** | 最终全量 3406 tests / 22121 assertions，0 failures / 0 errors（09:18:30–09:24:14），运行窗口内无源码/测试改动；test.check 属性运行全部 `:result true`，seed 每次随机、已在日志中逐条记录 |
| 6. 发布提交干净、无未跟踪产品文件、版本一致 | **FAIL** | 版本一致；`.gitignore` 覆盖已修复；但仓库只跟踪 `AGENTS.md`，产品源码仍全部未跟踪 |
| 7. 无模型配置启动健康，聊天明确提示选择模型 | **PASS（服务端）/ NOT RUN（客户端）** | health `:models {:configured? false}` 且 `:ok true`；未实际打开浏览器验证聊天提示 |
| 8. 发布说明如实列出未覆盖项 | **FAIL** | 未找到独立发布说明。`RUNNING.md` 声明了 loopback、源码级发布、备份 CLI 等边界，但没有列出 E2E 01–22 的 `NOT RUN` 清单 |

## 6. NOT RUN 清单

以下项目本次基线**没有运行**，不得视为通过：

- E2E 01–22：**全部 NOT RUN**（22 条端到端剧本）。
- 性能/规模基准：1,000 条会话、单会话 2,000 条活动、两万字草稿：**NOT RUN**。
- 签名安装包：**NOT RUN**（`RUNNING.md` 明确为源码级本地发布，不产出安装包）。
- 离线恢复 / `hc.server.backup.cli` 实跑：**NOT RUN**。
- GPU 路径：**NOT RUN**（文档未声明 GPU 路径）。
- 客户端“未配置模型”提示与聊天行为：**NOT RUN**（仅验证服务端 health）。
- 远程部署、认证与 TLS：**NOT RUN**（当前默认 loopback-only）。
- Apple Silicon / macOS / Windows 运行时：**NOT RUN**（本机仅 linux-64）。

## 7. 复现命令

```bash
# 工具
command -v pixi; /tmp/pixi --version
java -version; clojure --version; node --version

# 门禁
/tmp/pixi install --locked
/tmp/pixi run client-install
/tmp/pixi run client-release
/tmp/pixi run app-check
/tmp/pixi run test

# 定点复跑失败测试（修复后）
/tmp/pixi run clojure -M:test:compose:server:client \
  -n hc.compose.nudge.owner-integration-test

# 真实启动冒烟
/tmp/pixi run clojure -M:server -m hc.server.app \
  --host 127.0.0.1 --port 0 \
  --data-dir /tmp/hc-baseline-data \
  --workspace-root /tmp/hc-baseline-work \
  --static-root resources/public
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:<实际端口>/healthz
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:<实际端口>/
```

## 8. 修复记录（2026-11-05 08:08–09:25）

### 8.1 测试夹具：`:SESSION_PERSISTENCE_UNAVAILABLE`

- **判定**：测试夹具缺契约，不是 runtime 缺陷。根 AGENTS.md §5.1、`src/hc/compose/owner/AGENTS.md` 第 882 行、`src/hc/server/sessions/AGENTS.md` 第 88–91 行与 `src/hc/compose/owner.clj` 的 `start-owner!` 文档都明确：session 变更必须先经 `:commit-session!` 持久化；没有该端口的 Owner 必须拒绝，错误码就是 `:SESSION_PERSISTENCE_UNAVAILABLE`。
- **变更文件**：`test/hc/compose/nudge/owner_integration_test.clj`。
- **变更内容**：引入 `hc.server.sessions`、`hc.server.sessions.ids`、`hc.server.sessions.ports`；用 `sessions/new-session` 建合法会话，补 `:model-binding`（alpha）与 `:pending-model-binding`（beta，`:next-round`）；先 `sessions/commit-session!` 到 `ports/memory-persistence`，再把 committed session 与 `(sessions/session-port {:persistence persistence})` 注入 Owner。与 `test/hc/compose/owner/session_run_bridge_test.clj` 的真实端口夹具惯例一致。
- **未改 `src/`**：runtime 的持久化屏障保留；没有为了让测试变绿而放宽产品契约或伪造领域终态。
- **已取得证据**：
  - 测试文件可完整读取：42 个顶层 form，读取退出 0。
  - `hc.server.sessions.ports-test`：7 tests / 28 assertions，0 failures / 0 errors，退出 0（验证 memory persistence 与 session-port 原语）。
  - 定点复验：`hc.compose.nudge.owner-integration-test` 0 failures / 0 errors（08:14 23/152；08:45 24/162），退出 0。
  - 最终全量（09:18，稳定树）：3406 tests / 22121 assertions，0 failures / 0 errors，退出 0；目标命名空间无失败记录。中途的 79/19 与 13/1 快照见 G5 归类。

### 8.2 `.gitignore`

- **变更**：新增 `out-*/`、`.pi/`、`hs_err_pid*.log`；把 `resources/public/js/`、`resources/public/css/framework7-bundle.css`、`resources/public/smoke/js/` 归入构建产物段；保留 `target/`、`.cpcache/`、`node_modules/`、`.shadow-cljs/`、`.pixi/`、`*.log` 等。`out-*` 的具体旧条目已由通配规则取代。
- **验证**：`git check-ignore` 显示 `.gitignore`、`src/`、`test/`、`resources/`、`plans/`、`deps.edn`、`shadow-cljs.edn`、`pixi.toml`、`pixi.lock`、`package.json`、`package-lock.json`、`HACK.md`、`HISTORY.md`、`RUNNING.md`、`AGENTS.md` 均未被忽略；`out-artifacts-ui`、`.pi`、`hs_err_pid2905205.log` 被正确忽略。
- **效果**：`git status --porcelain` 从 20 条顶层条目降到 15 条；09:26 统计的 1405 个文件全部是应提交的产品/文档文件。`hs_err` 日志保留未删除。

### 8.3 未修改的外部文件

`src/hc/compose/cancel.clj`、`src/hc/core/relay/core.clj`、`src/hc/client/browser/view.cljc`、`src/hc/client/settings/view.cljc`、`src/hc/server/model_auth/service.clj`、`src/hc/compose/owner/guards.clj` 在本轮由其他工人编辑，均不在本任务可改范围。已把每次读取/编译错误的命名空间、文件位置与首行错误报告 Main，等待 owner 收尾后重跑全量。

### 8.4 复验结果

- 全量入口 09:01:53–09:07:48 完整跑完：**3394 tests / 22083 assertions，0 failures / 0 errors，退出 0**；随后在稳定树上重跑得到 3406/22121（见 §8.5）。中途 08:34 的 79/19 与 08:47 的 13/1 是并发编辑期间的快照，已由后续修复消除。
- `app-check` 08:44 与 08:46 两次复跑均退出 1：先 `START_FAILED`（Atom→IFn），后 `:UNHEALTHY`（fleet 组件未启动）；server-app 修复后 08:47 与 09:01 均退出 0，G4 恢复 **PASS**。
- app 装配回归（Atom→IFn、fleet-gc、`MASTER_REF_UNAVAILABLE`、fleet fork NPE、duplicate tool-call）由 server-app 修复，CDP `Boolean→IAtom` 由 server-runtime-ext 修复；09:07 全量与 09:08 `app-check` 均通过。08:34/08:47 的失败快照保留为并发编辑期间的证据。
- 08:57:46 的一次全量恰好撞上 `src/hc/compose/owner.clj` 的实时编辑（mtime 08:57:47），得到 3394 tests / 21933 assertions、34 failures / 18 errors，app 启动报 `OWNER_START_FAILED ... "fut" is null`；该次结果不作为稳定基线。09:01 单独 `app-check` 已恢复退出 0（`:ok true`），随后再次重跑全量。
- 最终复跑命令（均已通过）：

```bash
/tmp/pixi run clojure -X:test:compose:server:client
/tmp/pixi run app-check
```

### 8.5 最终门禁复验（09:18–09:25，稳定树）

- **编译门禁**：`clojure -M:dev:server -e "(require 'hc.server.app.system) (println :ok)"` → `:ok`，退出 0，22 s。`-M:dev` 单独使用会报 `Could not locate missionary/core...`，编译检查必须带 `:server` 别名；这是别名集差异，不是源码缺陷。
- **app-check**：`/tmp/pixi run app-check` 退出 0，14 s；health `:ok true`、`:version "0.0.1"`、`:store {:open? true}`、`:gateway {:epoch 1, :seq 1, :max-pending 4096, :subscriptions 0, :stale 0}`、`:models {:configured? false}`、`:sessions {:count 1}`、`:fleet-gc`/`:fleet` 均 `:started`；自动隔离 `/tmp/hc-check-3580104557462498968`。日志 `/tmp/final-app-check4.log`。
- **全量**：`clojure -X:test:compose:server:client` 退出 0，343 s，**3406 tests / 22121 assertions，0 failures / 0 errors**；运行窗口（09:18:30 起）内 `src/`、`test/` 无文件改动。日志 `/tmp/final-full-test3.log`。
- **定点复跑**（08:47 遗留失败命名空间）：`hc.server.app.fleet-commands-test` + `hc.server.app.system-test` + `hc.server.app.restore-supervisor-test` = **58 tests / 330 assertions，0F/0E**；`hc.server.browser-cdp-smoke-test` = **1 test / 68 assertions，0F/0E**。
- **并发中间态记录**：09:00–09:08 期间 `system.clj` 的 `extensions-ref` 解构键与调用方 `:extensions` 不一致，factory 内 `(deref nil)` 导致 `OWNER_START_FAILED ... "fut" is null`；server-app 把调用方改为 `:extensions-ref` 并定稿后，编译、`app-check`、全量均在定稿树上通过。该失败不是 0.0.1 的稳定回归。
- **未变**：G8 仍 FAIL；E2E 01–22、性能/规模、离线恢复仍 NOT RUN。

最终全量与 `app-check` 均已退出 0，G4/G5 为 **PASS**。仍剩 G8（发布提交只跟踪 `AGENTS.md`，产品源码与 `.gitignore` 未提交）与条件 8（发布说明缺失）；E2E 01–22、性能/规模、离线恢复仍为 **NOT RUN**，不因测试修复而改变。
