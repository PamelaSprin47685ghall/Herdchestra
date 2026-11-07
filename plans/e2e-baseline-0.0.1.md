# Herdchestra 0.0.1 E2E 环境与基线（2026-11-05）

- 探测窗口：2026-11-05 14:39–14:48 CST。
- 工作目录：`/home/kunweiz/Desktop/vibe/Herdchestra`。
- 模式：环境探测与基线为只读；随后按门禁要求修复 `hc.server.app.settings` 的 revision CAS 回归，仅修改 `src/hc/server/app/settings.clj` 与 `test/hc/server/app/settings_test.clj`；未执行 Git 写操作；未杀任何既有 app 进程。
- 重要前提：探测与测试期间其他工人正在持续改写 `src/`、`test/`，本报告的测试数字**绑定混合树**，不是最终合并树的发布结论。
- 权威规格：根 `AGENTS.md`、`RUNNING.md`、`plans/e2e-master-status-0.0.1.md`。

## 1. 结论摘要

| 项 | 结论 |
|---|---|
| 工具链 | 可用。Pixi 0.83.0、JDK 21.0.10、Clojure 1.12.6、Node 22.23.4、Chrome 153、Playwright 1.64.1 |
| 本地模型 | 可用。`qwen3.6:27b`，Ollama `127.0.0.1:11434`，`context_length=16384`，`NUM_PARALLEL=1` |
| 云端模型 | 可用。`deepseek-v4.1-flash:1017`，`OLLAMA_API_KEY` 存在（值未读取、未打印） |
| 18082 兼容端点 | 探测开始时**未监听**；已从既有脚本恢复，现 `/v1/models` 200 |
| 并发上限 | 端点接受 3 路并发且 3/3 完成、无丢弃；但 Ollama 只有 1 个并行槽，实际串行。模型调用必须 flock 串行 |
| 全量测试 | 修复前 **FAIL**：3646 tests / 23547 assertions，14 failures（全部 settings）；修复后 **FAIL（非 settings）**：3682 tests / 23810 assertions，3 failures / 0 errors，退出 1，444 s |
| 失败归属 | settings 的 6 个测试、14 个断言已修（§4.5）；修复后 3 个失败：model 失败 3/3 通过（瞬态），history 失败 3/3 复现（真实缺陷，§4.6） |
| app-check | PASS：退出 0，18 s，health `:ok true`、`:version "0.0.1"`、store 打开、gateway/fleet 计数正常 |
| Git | **阻塞**：HEAD 只跟踪 `AGENTS.md`；15:14 有 1171 个产品文件未跟踪（`??`），无法用 git 区分改动 |
| Bundle | 持续被其他工人重建；当前 `main.js` `9c59f834…`（15:14:29）；两次连续构建位级一致（§4.7） |
| 锁文件 | `/tmp/hc-client-release.lock`、`/tmp/hc-e2e-model.lock` 已创建（0 字节），flock 冒烟通过 |

## 2. 环境表

| 项 | 实测值 | 备注 |
|---|---|---|
| OS / CPU / 内存 | Linux 7.3.0-rc6-1-MANJARO x86_64；12 核；62 GiB | `/tmp` 为 32 GiB tmpfs，可用 17 GiB；根盘可用 159 GiB |
| Pixi | `pixi 0.83.0`，`/tmp/pixi`（68.1 MB） | 宿主 `PATH` 无 `pixi`，统一用 `/tmp/pixi` |
| Pixi JDK | `21.0.10-internal` | `.pixi/envs/default/bin/java` |
| Pixi Clojure | `1.12.6` | `clojure -M -e '(clojure-version)'` |
| Pixi Node / npm | `v22.23.4` / `10.9.9` | 发布任务实际运行时 |
| 宿主 Node / npm | `v26.10.0` / `12.2.0` | 不是发布运行时；直接跑 driver 时用的是它 |
| Chrome | `153.0.8010.36` | `/usr/bin/google-chrome-stable`、`/usr/bin/google-chrome`、`/opt/google/chrome/chrome` |
| Playwright | `1.64.1`，`/tmp/hc-pw/node_modules` | `NODE_PATH` 下 `require('playwright')` 成功；自带 `chromium-1248` |
| Playwright + 系统 Chrome | 实测启动成功 | `chromium.launch({executablePath:'/usr/bin/google-chrome'})`，返回版本 153.0.8010.36 |
| 工装脚本 | 可用 | `run_app.sh`、`stop_app.sh`、`e2e_driver.cjs` 均 `bash -n`/`node --check` 通过，`--help` 正常 |
| 锁文件 | 已创建 | `/tmp/hc-client-release.lock`、`/tmp/hc-e2e-model.lock`；`flock -n` 冒烟通过 |

`e2e_driver.cjs` 的可用边界：

- 必须设 `NODE_PATH=/tmp/hc-pw/node_modules`；否则 `require('playwright')` 失败。
- 默认自行启动系统 Chrome（headless=new、`--remote-debugging-port=0`），并读取 `DevToolsActivePort` 后 `connectOverCDP`。
- 未传 `--profile` 时用 `mkdtemp` 建独立 profile，退出时删除；`--keep-profile` 保留现场。
- 默认等待 `.wb-badge` 出现“已同步”；页面未同步则结果 `ok:false`、退出 1。

`run_app.sh` 的可用边界：

- 必须同时给 `--name`、`--data-dir`、`--workspace-root`；端口默认 0（临时端口）。
- 每个实例使用 `run/<name>/tmp` 作为 `java.io.tmpdir`，规避应用的单例锁；并发实例必须用不同 `--name` 与目录。
- `--model-config` 从 `/tmp/hc-e2e-harness/endpoint.env` 生成连接，指向 `HC_E2E_BASE_URL`（当前 18082）。
- 启动前会检查/拉起 Ollama；不会改仓库。

## 3. 模型端点

### 3.1 本地 Ollama

探测开始时 11434 未监听、无 `ollama` 进程；已按 `RUNNING.md` 要求启动：

```bash
OLLAMA_HOST=127.0.0.1:11434 \
OLLAMA_CONTEXT_LENGTH=16384 \
OLLAMA_NUM_PARALLEL=1 \
setsid /usr/local/bin/ollama serve >/tmp/ollama-serve.log 2>&1 &
```

当前 PID `3647839`。`/api/ps` 显示 `qwen3.6:27b` 已加载，`context_length=16384`。

| 请求 | 结果 |
|---|---|
| `GET /api/tags` | 200；`qwen3.6:27b`（27.8B Q4_K_M，`vision,completion,tools,thinking`）、`nomic-embed-text-v2-moe:latest`（embedding） |
| `GET /v1/models` | 200；同上两个 id |
| `POST /v1/chat/completions`（`qwen3.6:27b`，`reasoning_effort:"none"`，非流式） | 200，内容 `ok`；冷启动 21.87 s（首次加载），热后 7.39 s 一次（重载后），稳定热态见下 |
| 流式首 token（热态，直连 11434） | HTTP 200；TTFB **0.833 s**，总 0.941 s |
| 流式首 token（经 18082 代理） | HTTP 200；TTFB **2.296 s**，总 2.385 s（代理逐请求记录日志，额外开销明显） |

### 3.2 云端 Ollama

`OLLAMA_API_KEY`：**存在**（本次只判断存在性，未读取、未打印值）。

| 请求 | 结果 |
|---|---|
| `GET https://ollama.com/v1/models` | 200，0.93–1.03 s，26 个模型 |
| 模型清单含 `deepseek-v4.1-flash:1017` | 是 |
| `POST /v1/chat/completions`（`deepseek-v4.1-flash:1017`，默认思考，流式） | 200；TTFB **1.059 s**，总 1.142 s；`max_tokens=8` 时只输出 reasoning，`finish_reason:"length"` |
| 同上，`reasoning_effort:"none"`，流式 | 200；TTFB 5.363 s，总 5.441 s（首次请求，网络/冷启动波动） |
| 同上，`reasoning_effort:"none"`，非流式，32 token | 200，2.451 s，内容 `ok` |

结论：本地与云端都能完成真实生成；云端首 token 受网络影响在 1–5 s 间波动，不能当作稳定 SLA。

### 3.3 18082 兼容端点

- `endpoint.env` 的 `HC_E2E_BASE_URL=http://127.0.0.1:18082/v1` 在探测开始时**不可用**（connection refused）。
- 恢复方式：`/tmp/hc-compose-e2e/model_proxy.cjs` 是到 `127.0.0.1:11434` 的透明转发代理，监听 18082，并把请求/响应写入 `/tmp/hc-compose-e2e/model-proxy.log`。
- 已用 `setsid node model_proxy.cjs` 恢复，当前 PID `3646828`；`GET /v1/models` 200，`qwen3.6:27b` 可见。
- 该代理不是受管服务；若它退出，所有 `--model-config` 启动的 E2E 实例会连接失败。重启命令见 §6。

### 3.4 并发 3 小测

同一 `qwen3.6:27b`，`reasoning_effort:"none"`，3 个线程同时 `POST /v1/chat/completions`（`max_tokens=16`，非流式）：

| 轮次 | 各请求耗时 | 墙钟 | 结果 |
|---|---|---|---|
| 重启前（默认并行） | 0.877 / 1.470 / 2.041 s | 2.041 s | 3/3 HTTP 200，无丢弃 |
| 当前（`NUM_PARALLEL=1`） | 1.638 / 2.319 / 3.007 s | 3.007 s | 3/3 HTTP 200，无丢弃 |

解读：

- 端点**接受**至少 3 路并发，队列不拒绝、不丢请求。
- `NUM_PARALLEL=1` 下实际只有 1 个生成槽，墙钟约等于各请求之和，**有效并发上限是 1**。
- 云端并发未测；在拿到证据前同样按串行处理。

## 4. 基线测试

### 4.1 全量测试

```bash
/tmp/pixi run test    # clojure -X:test:compose:server:client
```

| 项 | 值 |
|---|---|
| 日志 | `/tmp/hc-baseline-full-test.log` |
| 窗口 | 2026-11-05 14:41:22 → 14:46:58 CST |
| 耗时 | **336 s** |
| 退出码 | **1** |
| 测试 / 断言 | **3646 tests / 23547 assertions** |
| 失败 / 错误 | **14 failures / 0 errors** |
| test.check | 171 次属性测试，0 次 `:result false`；seed 范围 `1793860907537`–`1793861218033`（每次随机，无固定 seed） |
| 参考 | 09:18 的旧基线为 3406 tests / 22121 assertions、0 failures；当前树多 240 tests |

失败全部集中在 `test/hc/server/app/settings_test.clj`：

| 测试 | 失败断言 | 现象 |
|---|---:|---|
| `disabling-a-connection-marks-its-session-binding-unavailable` | 3 | `save-connection!` 返回 `:ok? false`；连接未禁用；`model-availability` 仍 `:available` |
| `deleting-a-launch-connection-survives-restart` | 3 | 删除返回 `:ok? false`；重启后连接仍在；可用性为 `:unconfirmed` 而非 `:connection-removed` |
| `a-settings-created-connection-survives-a-launch-restart` | 2 | 新增返回 `:ok? false`；重启后 `ui-conn` 不存在 |
| `a-saved-connection-is-not-recorded-as-launch-provenance` | 2 | 保存返回 `:ok? false`；连接集合仍只有 `alpha` |
| `a-launch-removal-hides-without-erasing-saved-fields` | 3 | 保存返回 `:ok? false`；名称/显示名仍是旧值 |
| `removing-a-launch-connection-revokes-the-session-binding` | 1 | 删除返回 `:ok? false` |

### 4.2 失败原因（已定位，未修）

定点探针（`/tmp/hc-settings-probe.clj`，加载 `settings-test` 的私有 fixture）复现：

```clojure
:save-result
{:ok? false,
 :error {:code :SETTINGS_CONFLICT,
         :message "settings changed in another writer; re-read before saving",
         :details {:revision 1}}}
```

代码路径：`hc.server.app.settings/service` 在 launch 连接多于持久化连接时先做一次 bootstrap 提交，把持久化 revision 从 0 推到 1；但 `config` 在 bootstrap **之前**就已绑定并附加 revision 0，bootstrap 之后没有重新附加。保存时 `config-revision` 仍为 0，`commit-config!` 以 0 去比持久化的 1，返回 `:conflict`。

- 受影响文件：`src/hc/server/app/settings.clj`（mtime 14:25:29，早于全量运行）。
- 定点复跑（当前树）：`clojure -M:test:compose:server:client -n hc.server.app.settings-test` → 34 tests / 155 assertions / 14 failures / 0 errors，退出 1，10 s；日志 `/tmp/hc-baseline-settings-focused.log`。
- 该复跑期间 `src/`、`test/` 仍被其他工人改写（树哈希变化），因此只能证明“当前混合树可复现”，不能替代最终树结论。
- **未修复**。这是真实回归，不能把本次全量结果当作绿色基线。

### 4.3 树是否在测试期间变化

| 证据 | 测试前（14:41:10） | 测试后（14:46:58） |
|---|---|---|
| `src/`+`test/` 内容哈希（路径+内容聚合） | `4fcc7cef47868010529f81647221fd15d9b357136d37cd0bb8f396de03075c19` | `10d617350ec0448d1330ff73e48c7ffd5278dc0407de790f8f8dde5a991b9b46` |
| `git status --porcelain -uall \| wc -l` | 1115 | 1124 |
| `src/test` 最新 mtime | 14:31:03 `src/hc/client/schedule/model.cljc` | 14:46:57 `src/hc/server/materials/session.clj` |
| 运行窗口内被改写文件数 | — | 28 |

运行窗口内被改写的文件包括 `src/hc/server/app/commands.clj`（14:45）、`src/hc/server/app/snapshot.clj`（14:45）、`src/hc/compose/owner/handlers.clj`（14:45）等。**因此全量结果绑定混合树。**

### 4.4 app-check

```bash
/tmp/pixi run app-check
```

| 项 | 值 |
|---|---|
| 日志 | `/tmp/hc-baseline-app-check.log` |
| 耗时 | 18 s |
| 退出码 | **0** |
| 隔离根 | `/tmp/hc-check-14247475807040033772/{data,work}`（未写用户默认数据目录） |

health 关键字段摘录：

```clojure
{:ok true
 :version "0.0.1"
 :store {:open? true, :path "/tmp/hc-check-14247475807040033772/data/db"}
 :config {:host "127.0.0.1", :port 0,
          :workspace-root "/tmp/hc-check-14247475807040033772/work",
          :data-dir "/tmp/hc-check-14247475807040033772/data"}
 :gateway {:epoch 1, :seq 1, :max-pending 4096, :subscriptions 0, :stale 0}
 :models {:configured? false}
 :sessions {:count 1}
 :fleet-gc {:configured? true, :started? true, :status :started}
 :fleet {:configured? true, :available? true, :status :started}
 :uptime-ms 4}
```

启动期仍有 4 条命名空间遮蔽警告（`ensure`、`key`、`identity`、`send`）与 1 条反射警告（`hc/server/attachments/ingest.clj:838:30`），与旧基线一致，不阻断。

### 4.5 修复后复跑（settings revision CAS）

修复点（`src/hc/server/app/settings.clj` 的 `service`）：

- 编辑前 `git diff` 为空（这两个文件本来就不被 Git 跟踪），并以 mtime + 即时重读确认没有覆盖并行改动；编辑后只有本次写入。
- 根因：launch 连接触发 bootstrap 提交（revision 0→1）后，`config` 仍是 bootstrap 前绑定的 revision 0；save/delete 以 0 做 CAS，返回 `:SETTINGS_CONFLICT`。
- 修复：bootstrap 后以实际提交的 revision 重新给 `document` 与 `config` 附加 revision；`config` 由最终 document 重新计算，不再复用 bootstrap 前的绑定。
- 并发保护：若 bootstrap 自身 CAS 冲突，先重新读取持久化 document，再取 revision；不把冲突 revision 与陈旧内容配对，避免静默覆盖。
- 新增回归测试（`test/hc/server/app/settings_test.clj`）：
  - `bootstrap-then-save-save-delete-advance-one-revision`：bootstrap 1 → save 2 → save 3 → delete 4，全部 `:ok? true`。
  - `stale-settings-revision-cannot-overwrite-a-newer-save`：两个 service 同起点；第一个 save 到 2，第二个 save 返回 `:SETTINGS_CONFLICT`（details revision 2），且未写入 `other-conn`。

| 运行 | 命令 | tests | assertions | failures | errors | 退出 | 耗时 | 日志 |
|---|---|---:|---:|---:|---:|---:|---:|---|
| settings 定点 | `-n hc.server.app.settings-test` | 36 | 170 | 0 | 0 | 0 | 13 s | `/tmp/hc-settings-fix-test.log` |
| `hc.server.app.*` | `-r 'hc\.server\.app\..*'` | 249 | 1472 | 8 | 0 | 1 | 242 s | `/tmp/hc-settings-fix-app-ns.log` |
| 全量 | `/tmp/pixi run test` | 3682 | 23810 | 3 | 0 | 1 | 444 s | `/tmp/hc-baseline-full-test-after-fix.log` |

`hc.server.app.*` 的 8 个失败全部在 `outbound_context_test.clj`（memory / worldbook / instruction-cards 段缺失）。该轮运行期间 `src/hc/server/app/system.clj` 于 14:55:01 被其他工人改写；修复后全量运行不再出现这些失败，判定为混合树瞬态。

全量运行的 3 个剩余失败（均非 settings）：

| 测试 | 断言 | 现象 | 判断 |
|---|---:|---|---|
| `hc.client.materials.model-test/entry-move-receipt-remembers-undo` | 2 | payload 为 `{}`，缺 `:entry-ids` / `:book-id` / `:undo?` | 3/3 通过，混合树瞬态（§4.6） |
| `hc.server.history-live-smoke-test/live-history-edit-compress-branch-survives-restart` | 1 | outbound `message-ids` 为 `["m3"]`，期望 `["m3" "m4"]` | 3/3 复现，真实缺陷（§4.6） |

稳定性判定见 §4.6：`hc.client.materials.model-test` 的失败 3/3 通过（瞬态）；`hc.server.history-live-smoke-test` 的失败 3/3 复现（真实缺陷）。

测试窗口：14:55:30 → 15:02:54；`src/`+`test/` 聚合哈希 `8450d969…` → `65d81e0b…`；窗口内 9 个文件被改写；`git status` 1133 → 1141。**结果仍绑定混合树。**

### 4.6 失败稳定性判定（15:04–15:14）

方法：每个目标单独跑 3 次，间隔 10 s；每次记录 `git diff --stat` 与相关文件 mtime。`git diff --stat` 在窗口内只显示 `AGENTS.md`（其他工人改规范文件），产品源码仍全部未跟踪；判定依据是 mtime 与源码哈希。

逐次 `git diff --stat`：model 与 history 的三次均为空；outbound 的三次显示 `AGENTS.md`（第 1/2 次 8 行变更，第 3 次 24 行变更），没有产品源码进入 diff。

| 目标 | 次数 | 结果 | 相关 mtime | 判定 |
|---|---:|---|---|---|
| `hc.client.materials.model-test/entry-move-receipt-remembers-undo` | 3 | 3/3 通过；每次 1 test / 3 assertions，0 failures / 0 errors，退出 0；耗时 39/31/34 s | `model_test.clj` 14:56:11、`model.cljc` 14:53:38、`view.cljc` 15:00:51，三次不变 | **混合树瞬态，非产品缺陷** |
| `hc.server.history-live-smoke-test/live-history-edit-compress-branch-survives-restart` | 3 | 3/3 失败；每次 1 test / 31 assertions，1 failure / 0 errors，退出 1；耗时 30/38/39 s | `history_live_smoke_test.clj` 2026-11-03 21:18:41、`system.clj` 14:55:01、`context.clj` 14:52:49；`history.clj` 15:09:01 被改写后第 2/3 次仍失败 | **真实缺陷** |
| `hc.server.app.outbound-context-test` | 3 | 3/3 通过；每次 1 test / 27 assertions，0 failures / 0 errors，退出 0；耗时 22/17/19 s | `outbound_context_test.clj` 14:02:11、`system.clj` 14:55:01、`context.clj` 14:52:49，三次不变 | **W4 outbound 套件稳定** |

真实缺陷的具体断言（`history_live_smoke_test.clj:145`）：

```text
FAIL in (live-history-edit-compress-branch-survives-restart) (history_live_smoke_test.clj:145)
the published baseline carries versions and context
expected: (= ["m3" "m4"] (get-in summary [:context :outbound :message-ids]))
  actual: (not (= ["m3" "m4"] ["m3"]))
```

同一条 summary 的 `:message-ids`（上一行断言）为 `["m1" "m2" "m3" "m4"]`，说明 m4 已持久化；缺的是 `:context :outbound :message-ids` 中的 m4。缺陷位于 outbound 选择/投影（regenerate 后把目标 m4 排除），不在历史持久化。相关源码哈希：

| 文件 | sha256 |
|---|---|
| `src/hc/server/app/system.clj` | `b72898528124b7572578ffac5e8b3d15ffb36780572fbfe4e89aecaee1789833` |
| `src/hc/server/history/context.clj` | `a20d0556a9282d32d78ba3bad59681f848a42715aa0dd8116bcb30e891733e3f` |
| `src/hc/server/history.clj` | `da6f42694a8b1bc7ff3a052dee0a866647a92bbed3e0dc7cad9560293ce9ffe3` |
| `src/hc/server/sessions.clj` | `48cce4df335a36c35e210805046c90b12a0d9968c1e9698c1fbb32368fa328d7` |
| `test/hc/server/history_live_smoke_test.clj` | `7581c9772b7ac9052148032c96ec1852c5a74b65b0380dcd9ac18b4887f76cf2` |

**交给 history/outbound 对应工人（W4）处理。**

### 4.7 Bundle 构建确定性

在源码不变的前提下连续构建两次（每次持 `/tmp/hc-client-release.lock`）：

| 项 | build 1 | build 2 |
|---|---|---|
| 命令 | `/tmp/pixi run client-release` | 同左 |
| 退出码 | 0 | 0 |
| 构建摘要 | 513 files / 4 compiled / 16 warnings / 22.88 s | 513 files / 4 compiled / 16 warnings / 20.83 s |
| `main.js` 大小 | 2,953,410 B | 2,953,410 B |
| `main.js` sha256 | `9c59f834a1dd49fb3fd085a711eac683f681a52f6e757ebcba5ecb5dd1aa19b6` | 同左 |
| 字节比较 | — | `cmp` 完全一致 |

源码哈希 `bf338abd7b277374497f9e4e4ad5350add6363eecdac38b6847b3c66fe947dfd` 在 build 1 前、两次构建之间、build 2 后均不变。两次产物位级一致，未观察到时间戳或构建哈希嵌入。发布只要求产物存在且可运行，本项**不影响发布**；但 E2E 仍必须记录每次实际使用的 `main.js` 哈希。

### 4.8 进程重启后完整性（16:45–16:48）

进程重启后（W5/W11 中断前有 `snapshot.clj`、`app.clj`、`system.clj`、`storage.clj` 改动），按只读方式检查当前树：

| 检查 | 命令 | 结果 |
|---|---|---|
| 命名空间加载 | `/tmp/pixi run clojure -M:server -e "(require 'hc.server.app.system 'hc.server.app 'hc.server.storage 'hc.compose.owner 'hc.server.history) (println :ok)"` | **PASS**，退出 0，23 s；仅 4 条命名空间遮蔽 + 1 条反射警告，无半截语法 |
| 启动自检 | `/tmp/pixi run app-check` | **PASS**，退出 0，31 s；health `:ok true`、`:version "0.0.1"`、store `{:open? true, :path "/tmp/hc-check-5151507353525539067/data/db"}`、gateway `{:epoch 1, :seq 1, :max-pending 4096, :subscriptions 0, :stale 0}`、`:models {:configured? false}`、`:sessions {:count 1}`、`:fleet-gc {:started? true}`、`:fleet {:available? true}` |
| hs_err | `ls hs_err_pid*.log \| wc -l` | **7 → 7**，无新增；最新仍是 15:18 的 `hs_err_pid3871452.log` |
| settings 定点 | `-n hc.server.app.settings-test` | **PASS**：36 tests / 170 assertions，0 failures / 0 errors，36 s |
| history 定点 | `-n hc.server.history-live-smoke-test` | **PASS**：1 test / 31 assertions，0 failures / 0 errors，38 s；此前同一测试 3/3 失败，当前树已修复（建议再跑 3 次确认稳定性后关闭 B6） |

关键文件 mtime：`snapshot.clj` 15:51:42、`app.clj` 15:54:09、`system.clj` 15:55:15、`storage.clj` 15:54:53；加载与启动均通过，未发现半截修改。当前 `git status` 仍有 1513 个未跟踪条目（并行施工），本检查只证明“当前树可加载/可启动/定点测试通过”，不是最终树全量结论。

## 5. 当前 Bundle

`resources/public/js/` 与 `resources/public/css/framework7-bundle.css` 被 `.gitignore` 忽略，不在版本控制内。

| 文件 | 14:47（修复前） | 15:14（当前） |
|---|---|---|
| `resources/public/js/main.js` | 2,939,270 B；`0038c945b1aa729461075f910805bcc6d43b379c0d29a0deceb6b8a5b5a6893f` | **2,953,410 B**；`9c59f834a1dd49fb3fd085a711eac683f681a52f6e757ebcba5ecb5dd1aa19b6` |
| `resources/public/css/framework7-bundle.css` | 570,383 B；`77da0dd42f787c7f9b8012df796006d19353b6c22af717018ab6a65d6fff2a57` | 同左（15:14:04 重建，字节未变） |
| `resources/public/css/workbench.css` | 39,916 B；`a2653dc9a1d8e9aad7e69f8864568de2861497b89677391e7105ba3098c3b181` | 同左（08:32:59，未变） |

- `main.js` 在 14:30–15:14 间被重建 5 次：14:30 `fa0c4d38…`（2,934,956 B）→ 14:47 `0038c945…`（2,939,270 B）→ 14:51 `d37b13a7…`（2,950,407 B）→ 15:02 `6466aede…`（2,953,410 B）→ 15:14 `9c59f834…`（2,953,410 B）。15:02–15:14 间源码继续变化（含 15:09 的 `history.clj`），15:14 重建得到新哈希。
- 当前 E2E 基线 bundle：**`main.js` 2,953,410 B，mtime 15:14:29，sha256 `9c59f834a1dd49fb3fd085a711eac683f681a52f6e757ebcba5ecb5dd1aa19b6`**。
- 两次连续构建（源码哈希 `bf338abd…` 不变）产物位级一致，见 §4.7。
- 任何 E2E 证据必须记录所用 `main.js` 的 sha256；重建 bundle 后必须重启 app，旧标签页仍持有旧 bundle。

## 6. Git 状态（只读）

| 项 | 值 |
|---|---|
| HEAD | `015229e28bd0fdddc14060a28f1348185c6e765b`，2026-11-03 17:40:58 +0800，`Implement code changes to enhance functionality and improve performance` |
| HEAD 跟踪文件 | **1 个：`AGENTS.md`** |
| `HEAD~1` / `HEAD~2` | 1 个文件 / 260 个文件（产品树在两次提交前已被移出版本控制） |
| `git ls-files` | 1 |
| `git status --porcelain -uall` | 14:41 为 **1115**；14:47 为 **1124**；15:02 为 **1141**；15:14 为 **1171**。产品文件全部是 `??`；唯一已跟踪修改是其他工人正在编辑的 `AGENTS.md` |
| 分布 | `src/hc` 549、`test/hc` 407、`plans/evidence` 81、`test/js` 30、其余资源与计划文件 |

结论：`RUNNING.md` 发布条件 6“发布提交干净；没有未提交/未跟踪的产品文件”**不满足**。在当前状态下无法用 `git diff` 判断工人改了什么，只能用文件哈希/mtime。

## 7. 给其他工人的运行约束

### 7.1 模型调用：必须串行

本地 Ollama 只有 1 个并行槽。所有真实模型调用（含 E2E、探针、生成）用同一把锁：

```bash
flock -w 900 /tmp/hc-e2e-model.lock -c 'your-model-command'
```

- 不要为了“加速”并发发模型请求；第 2、3 路只会排队。
- 云端也按串行处理，除非另有并发证据。
- 若需重启 Ollama，保持 `OLLAMA_CONTEXT_LENGTH=16384` 与 `OLLAMA_NUM_PARALLEL=1`；否则 Plan 系统提示会触发 HTTP 500 或空回复。

### 7.2 Bundle 构建：必须串行

```bash
flock -w 900 /tmp/hc-client-release.lock -c '/tmp/pixi run client-release'
```

- 构建后记录 `main.js` 的 mtime/size/sha256，并重启 app。
- 不要让两个工人同时跑 `client-release`；也不要与全量测试同时跑，二者都会抢 CPU 且会改变 E2E 的 bundle 快照。

### 7.3 实例隔离

- 每个实例独立：`--name`、`--data-dir`、`--workspace-root`、端口（默认 0）。
- `run_app.sh` 已为每个实例设置独立 `java.io.tmpdir`；不要复用别的实例的 `run/<name>`。
- Chrome 用独立 profile；`e2e_driver.cjs` 默认 `mkdtemp`，不要指向共享 profile。
- 不要共用 data-dir；不要删除 `/tmp` 下他人目录；不要杀别人的 app 进程。

### 7.4 18082 代理

当前已恢复。若 `/v1/models` 再次 connection refused：

```bash
cd /tmp/hc-compose-e2e && setsid node model_proxy.cjs \
  >>/tmp/hc-compose-e2e/model-proxy.out 2>&1 </dev/null &
```

代理是透明转发并记录完整请求体，日志会增长；它不是受管服务，E2E 前先 `curl -fsS http://127.0.0.1:18082/v1/models`。

### 7.5 其他

- `app-check` 使用临时 data-dir/workspace，可与其他只读工作并行。
- 全量测试没有专用锁；建议在最终合并树上单独跑，避免与 bundle 构建、E2E 浏览器同时进行。
- 记录 E2E 结果时必须带：app 端口、data-dir、`main.js` sha256、模型端点、是否经 18082。

## 8. 阻塞项

| # | 阻塞 | 证据 | 影响 |
|---|---|---|---|
| B1 | settings revision CAS 回归 | **已修复**（§4.5）：bootstrap 后 config revision 未更新 | 14 个 settings 失败消失；发布条件 5 仍待剩余失败清零 |
| B2 | Git 只跟踪 `AGENTS.md`，1171 个产品文件未跟踪 | §6 | 发布条件 6 不满足；无法审计改动 |
| B3 | 混合树 | 修复后全量窗口内 9 个文件被改写；model 失败 3/3 通过，history 失败 3/3 复现 | 测试数字不是最终树结论；仍需在最终合并树重跑全量 |
| B4 | 18082 代理无人监管 | 探测开始时端口关闭；已手工恢复 | 代理退出会让所有 `--model-config` E2E 连接失败 |
| B5 | Bundle 在窗口内被重建 5 次 | `main.js` 14:30 → 14:47 → 14:51 → 15:02 → 15:14，当前 `9c59f834…`；两次连续构建位级一致（§4.7） | E2E 证据必须绑定 bundle 哈希，否则不可复现 |
| B6 | **history outbound 真实缺陷** | §4.6：`history_live_smoke_test.clj:145`，3/3 复现；summary `:message-ids` 含 m4，但 `:context :outbound :message-ids` 只有 `["m3"]` | 全量测试不绿；交给 history/outbound 工人（W4）修复后重跑 |

未做/未覆盖：

- 云端并发 >1 未测。
- Playwright 仅验证“可 require + 可启动系统 Chrome”，未对真实 app 跑完整 `e2e_driver.cjs` 流程。
- 未启动真实 app 做浏览器冒烟（避免与工人实例争资源）；`app-check` 已覆盖服务端健康。
- 未在最终合并树上取得全绿全量结果；model 失败为瞬态，history 失败为真实缺陷且未修。
