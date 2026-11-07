# Datalevin / JVM SIGSEGV 崩溃调查（0.0.1）

- 日期：2026-11-05 15:50 CST。
- 范围：仓库根目录 7 个 `hs_err_pid*.log`；只读分析 + 单实例复现尝试。
- 约束：未修改源码；未删除 `hs_err` 日志；未修改根 `AGENTS.md`。
- 结论先行：**7/7 都是 JVM 崩溃（SIGSEGV），其中 5 个落在 Datalevin 原生 LMDB（`libdtlv.so`），2 个落在 Datalevin 读取路径的 nippy/ByteBuffer 反序列化。崩溃线程集中在 `hc-publisher-1`（5 个）、`hc-shutdown`（1 个）、`clojure-agent-send-off-pool-1`（1 个），共同点是“读 Datalevin 时 store 生命周期/并发关闭”。判定为**发布阻断**，先修复 publisher/shutdown 与 store 关闭的时序，再做压力复检。**

## 1. 7 个 hs_err 逐条

| # | 日志 / 时间 | JVM | 命令类型 | 崩溃线程 | 顶部帧 | 关键栈 | 数据目录 |
|---:|---|---|---|---|---|---|---|
| 1 | `hs_err_pid1928617.log`<br>11-05 12:07:43 | 27.0 | app `--config /tmp/hc-e2e-real/e2e15/config.edn` | `hc-shutdown` | `libc pthread_mutex_lock+0x4` | `mdb_txn_begin` → `get_dbi` → `scan` → `session_view` → `baseline` | `/tmp/hc-e2e-real/e2e15/data` |
| 2 | `hs_err_pid2905205.log`<br>11-04 01:33:00 | 26.0.2.1 | dev repro `repro_stream2.clj` | `hc-publisher-1` | `libc pthread_mutex_lock+0x4` | `mdb_txn_begin` → `fresh_reader` → `borrow_reader!` | 无（脚本自建） |
| 3 | `hs_err_pid3003787.log`<br>11-04 02:33:49 | 26.0.2.1 | app `--data-dir /tmp/hc-devops-e2e-20261103/final5-data --port 17780` | `hc-publisher-1` | `libdtlv.so mdb_page_search_root+0x11f` | `mdb_get` → `get_value` → `scan` | `/tmp/hc-devops-e2e-20261103/final5-data` |
| 4 | `hs_err_pid3566148.log`<br>11-05 14:23:28 | 21.0.10（pixi） | app `--data-dir /tmp/hc-compose-e2e/data-e2e09chain3` | `hc-publisher-1` | `StubRoutines::jbyte_disjoint_arraycopy_avx3` | nippy `read_str` / `ByteBufferReader` | `/tmp/hc-compose-e2e/data-e2e09chain3` |
| 5 | `hs_err_pid3597664.log`<br>11-04 13:09:39 | 26.0.2.1 | test runner `cognitect.test-runner /tmp/rt_only4.clj` | `hc-publisher-1` | `libdtlv.so mdb_cursor_get+0x3d` | `dtlv_key_iter_has_next` → `get_range` | 测试临时 store |
| 6 | `hs_err_pid3871452.log`<br>11-05 15:18:37 | 27.0 | app `--config /tmp/hc-w6/run13/config.edn` | `clojure-agent-send-off-pool-1` | nippy `read_typed`（Java 帧） | `datalevin.bits/read_buffer` → `scan` → `storage.view/session_view` | `/tmp/hc-w6/data` |
| 7 | `hs_err_pid455536.log`<br>11-05 00:20:15 | 27.0 | test runner `clojure.run.exec :nses [fleet/app-system tests]` | `hc-publisher-1` | `libc pthread_mutex_lock+0x4` | `mdb_txn_begin` → `fresh_reader` → `borrow_reader!` | 测试临时 store |

补充：

- 全部 `SIGSEGV`（`si_signo: 11`），不是 OOM、不是 Java 异常、不是 `SIGBUS` 主因。
- 5 个原生帧在 `libdtlv.so` / `libc pthread_mutex_lock`；2 个在 nippy/ByteBuffer 读取。
- 6/7 是 host JVM 26/27（`/usr/bin/java`、host `clojure`）；只有 #4 是 pixi JDK 21。
- #1 线程 `hc-shutdown` 在关闭期读 `baseline`/`session_view`；#2/#3/#5/#7 都是 `hc-publisher-1` 读 store；#6 是异步 send-off 池反序列化 Datalevin 值；#4 是 publisher 读 nippy ByteBuffer。

## 2. 根因判断

### 2.1 主因（高置信）：publisher/shutdown 读 store 与 store 关闭时序竞争

证据：

- `system/stop!` 的停止顺序是 gateway → **publisher** → fleet-gc → scheduler → owners → … → **最后关闭 store**。
- `publisher` 的 `:stop!` 只执行 `(.shutdownNow executor)`，**没有 `awaitTermination`/join**；`shutdownNow` 立即返回，已进入 Datalevin 原生读的任务不会响应中断。
- store 随后被 `close-store!` 关闭；正在执行的 `mdb_txn_begin`/`mdb_get`/`mdb_cursor_get` 会在已释放/已关闭的 LMDB env 上继续访问，表现为 `pthread_mutex_lock` 空指针/失效地址、`mdb_page_search_root`、`mdb_cursor_get` 崩溃。
- 5/7 崩溃线程是 `hc-publisher-1`，1/7 是 `hc-shutdown` 在关闭期读 baseline；这与“publisher 读 store 时 store 被关闭”完全吻合。
- #6 是异步线程在 `datalevin.bits/read_buffer` 里用 nippy 读 Datalevin 返回的 ByteBuffer；如果 buffer 所属的 read txn/env 已关闭，或 buffer 被复用/释放，就会在 nippy 读取时 SIGSEGV。属于同一生命周期家族。
- #4 是 publisher 读 nippy `ByteBufferReader` 时在 `jbyte_disjoint_arraycopy_avx3` 崩溃；同样是“Datalevin 返回的 buffer 在读取期间失效/长度异常”，不是单纯 GC。

### 2.2 加重因素：host JVM 26/27 + Datalevin native 1.1.8

- 5 个 `libdtlv.so` 崩溃全部出现在 host JVM 26/27；pixi JDK 21 只出现 1 个 nippy/arraycopy 崩溃。
- 当前 `datalevin/datalevin 1.2.10` 使用 JavaCPP native `dtlvnative-linux-x86_64-1.1.8`；host JVM 26/27 启动时已提示：
  `WARNING: Restricted methods will be blocked in a future release unless native access is enabled`
  （host `clojure` 未加 `--enable-native-access=ALL-UNNAMED`）。
- 这不能解释全部崩溃（JDK 21 也有 1 个），但说明 **host JVM 26/27 不是 Datalevin 的稳妥运行环境**；发布运行时是 pixi JDK 21，host JVM 26/27 主要用于工人手工跑 app/test。

### 2.3 多 JVM 打开同一 Datalevin 目录

- 7 个日志中未发现两个当前 JVM 同时打开同一 `data-dir` 的硬证据；崩溃目录分别是 e2e15、final5-data、e2e09chain3、w6/data，当前进程也使用不同 data-dir。
- 但 `run_app.sh` 允许不同 `--name`/不同 `java.io.tmpdir` 的实例指向同一 `--data-dir`；如果旧 app 未完全退出就重启，仍可能两个 JVM 同时打开同一 LMDB。**这是需要继续排除的加重因素，不是本次已证主因。**

### 2.4 不是 DirectByteBuffer/GC 单独问题

- #4/#6 的 nippy `ByteBufferReader` 指向 Datalevin 返回值；崩溃地址是失效/越界 buffer，不是 GC 移动对象（DirectByteBuffer 不在 Java 堆内移动）。
- 没有 `OutOfMemory`/GC overhead 证据；`DirectByteBuffer` 计数只是栈上对象，不是根因。

## 3. 最小复现

已尝试（均无新 `hs_err`）：

| 尝试 | 环境 | 结果 |
|---|---|---|
| 单实例 `pixi run app-check` | pixi JDK 21 | **PASS**，退出 0，21 s，health `:ok true` |
| 单测试 ns `hc.server.app.system-test` | pixi JDK 21 | **PASS**，16 tests / 99 assertions，0 failures，26 s |
| 单实例 host `clojure -M:server -m hc.server.app --check --port 0` | host JDK 27 | **PASS**，退出 0，27 s；仅 native-access 警告 |

判断：

- **单次 start/stop 不足以复现**；崩溃需要 publisher 任务在 stop 时仍在 Datalevin 读（500 ms 周期、负载/会话活动、或测试连续 start/stop）。
- **不是只在并行 `pixi run test` 出现**：日志 #1/#3/#4/#6 是单 app 实例；#5/#7 是测试 runner；#2 是 dev repro 脚本。
- 下一步最小压力复现：同一 JVM 内反复 `start!`/`stop!`（或反复 `app-check` + 有活动会话），在 stop 时让 publisher 保持读；观察 `hs_err`。这需要新增测试/脚本，当前未改源码。

## 4. 是否发布阻断

**是，发布阻断（P0）。**

理由：

- 新日志 #6 发生在 15:18 的当前树；不是历史遗留。
- 崩溃是 JVM 级 SIGSEGV，可能直接终止 app、丢失未落盘状态；如果发生在写事务附近，存在 LMDB 数据损坏风险。
- 单次 `app-check`/`system-test` 通过不能证明 stop-under-load 安全；发布门禁的全量测试和 E2E 都会反复 start/stop app，风险未清零。
- 修复前不应打 0.0.1 tag；全量测试即使偶发通过，也不能把“无 hs_err”当作稳定证据。

## 5. 缓解建议（不改源码）

### 5.1 立即执行（发布前）

1. **统一用 pixi JDK 21 跑所有 app/test**：`/tmp/pixi run ...`；不要让 host `clojure`（JDK 26/27）跑 Datalevin 相关工作。host JVM 26/27 只用于不碰 store 的静态检查。
2. **串行化 store 生命周期**：同一 data-dir 同一时刻只允许一个 JVM；`stop_app.sh` 后确认进程退出，再启动下一个；不要复用同一 data-dir 并发跑 app。
3. **测试串行**：`hc.server.app.*`、storage、history、backup 等会打开 store 的测试不要与 app/E2E 并行；全量测试期间停止其他 app 实例。
4. **每次门禁前后检查 `hs_err_pid*.log`**：任何新文件都按阻断处理，记录 mtime/命令/线程。
5. 如果必须在 host JDK 26/27 跑，设置 `--enable-native-access=ALL-UNNAMED`（只是消除警告/未来限制，不替代生命周期修复）。

### 5.2 需要源码修复（交给对应工人）

1. `publisher/stop!`：`shutdownNow` 后 `awaitTermination`（或 join 正在执行的 publish future），确认 publisher 不再读 store 后才允许 `close-store!`。
2. `hc-shutdown` 路径：shutdown hook 不要读 baseline/session；先停 publisher/owner，再关 store；确保 `system/stop!` 被调用并等待。
3. Datalevin 读取路径：确保 nippy 反序列化在 read txn 内完成；不要把 Datalevin 返回的 native buffer 逃逸到 txn/env 关闭之后；必要时复制字节到 Java heap。
4. 增加压力测试：同一 JVM 反复 start/stop app，publisher 保持读；同一进程反复 open/close store；测试 JDK 21 与 host JDK 27 各跑一轮。

### 5.3 Datalevin / JVM 取舍

- **首选**：发布与测试固定 pixi JDK 21，Datalevin 1.2.10 保持；先修 publisher/store 生命周期。
- **次选**：升级 Datalevin（需查兼容性并跑 storage/app 压力测试）；不要只升级 JVM。
- **不建议**：继续用 host JDK 26/27 跑 app/test 并忽略 native-access 警告。

## 6. 复检命令

```bash
cd /home/kunweiz/Desktop/vibe/Herdchestra

# 1) 单实例自检（pixi JDK 21）
/tmp/pixi run app-check

# 2) 单测试 ns（pixi JDK 21）
/tmp/pixi run clojure -M:test:compose:server:client -n hc.server.app.system-test

# 3) 压力复现（修复后新增/现有脚本）：反复 start/stop，publisher 有读
#    至少 20 轮；每轮后检查是否有新 hs_err

# 4) 全量（最终树，串行，不与 app/E2E 并行）
/tmp/pixi run test

# 5) 崩溃检查
ls -lt hs_err_pid*.log | head
```

复检通过标准：

- 压力 20 轮 + 全量测试后没有新的 `hs_err_pid*.log`；
- `app-check` 退出 0，health `:ok true`；
- 全量 tests/assertions/failures/errors 记录完整；
- 若仍有 host JDK 26/27 的崩溃，发布运行时必须明确只支持 pixi JDK 21，并在 release notes 标注。

## 7. 证据清单

- `hs_err_pid1928617.log`（JVM 27，`hc-shutdown`，libc mutex）
- `hs_err_pid2905205.log`（JVM 26，`hc-publisher-1`，libc mutex）
- `hs_err_pid3003787.log`（JVM 26，`hc-publisher-1`，`mdb_page_search_root`）
- `hs_err_pid3566148.log`（JVM 21，`hc-publisher-1`，arraycopy/nippy）
- `hs_err_pid3597664.log`（JVM 26，`hc-publisher-1`，`mdb_cursor_get`）
- `hs_err_pid3871452.log`（JVM 27，`clojure-agent-send-off-pool-1`，nippy `read_typed`）
- `hs_err_pid455536.log`（JVM 27，`hc-publisher-1`，libc mutex）
- 复现日志：`/tmp/hc-p0-app-check.log`、`/tmp/hc-p0-system-test.log`、`/tmp/hc-p0-host-app-check.log`
- 源码位置：`src/hc/server/app/system.clj`（`start-publisher`/`stop!`）、`src/hc/server/app.clj`（`hc-shutdown`）、`src/hc/server/storage/engine/datalevin.clj`（`close!`/`scan`）

## 8. 修复状态（2026-11-05 16:50 CST）

**已修复源码，压力复检通过；尚未打 tag。**

- `system/stop!` 现在先停 publisher：设置 `stopped?` → `shutdownNow` → `awaitTermination` → 等待直接 `publish-now!` 调用者的 `publish-lock`；超时返回失败且**不关闭 store**。之后才停 gateway/owner/scheduler，最后 `close-store!`。
- shutdown hook 仍经 `restore-supervisor/stop-current!` 调用同一 `system/stop!`；关闭期 `browser/shutdown!` 触发的 `publish-now!` 被停止标志挡下，不再读 baseline。
- `storage/close-store!` 增加 read-write barrier：等待已进入的读完成后才关闭 engine；关闭后到达的读返回 `:STORAGE_CLOSED`。
- 压力回归 `test/hc/server/app/store_shutdown_stress_test.clj`：20 轮真实 start/stop、每轮 publisher 有直接读负载，180 assertions / 0 failures；`hs_err` 7 → 7，无新日志（`/tmp/hc-store-shutdown-stress.log`）。
- `pixi run app-check` 连续 3 次退出 0；`hc.server.app.system-test` 与 storage 相关 ns 结果见 `plans/store-shutdown-fix-0.0.1.md` §6。
- 剩余风险：publisher 超时会保持 store 打开；host JDK 26/27 仍非稳妥运行环境，发布固定 Pixi JDK 21。

### 8.1 追加根因：Datalevin 自己的 JVM shutdown hook（2026-11-05 17:30）

只修 `system/stop!` 仍不够。正常 SIGTERM 下 JVM 会**并发**运行所有 shutdown hook；Datalevin 1.2.10 为每个 LMDB env 注册自己的 hook，直接 `close-kv`，绕过 `hc-shutdown → system/stop! → publisher awaitTermination`。`hs_err_pid4063480.log`（Pixi JDK 21，W8 正常 `stop_app.sh` SIGTERM）栈里已有新的 `start_publisher$publish_body_BANG_` / `publish_now_BANG_`，但 publisher 仍在 `session_view → scan → nippy read_typed` 崩溃，证明是库 hook 与 `hc-shutdown` 并发关 env。

修复：store 打开成功后用 Datalevin 的 `register-shutdown-close!` 注册更高层 close action；应用 `system/start!` 把 store 标记为 ordered close。Datalevin hook 在 ordered 状态下等待 `close-store!` 的完成信号（30 s 兜底），不再直接关 env。没有调用 `removeShutdownHook`，因此不会在 JVM shutdown 进行中抛 `IllegalStateException`，也不影响直接打开 store 的测试/CLI。SIGTERM 5 轮与 20 轮 start/stop 复验见 `plans/store-shutdown-fix-0.0.1.md`。

### 8.2 内存效率与最终复验（2026-11-06 10:50）

- publisher 不再为每个 assistant 消息保留完整文本引用，改为 `message-id -> length`；`text-delta` 按长度取追加后缀，行为与原 append-only 路径一致。
- `previous-calls` 从“上一份完整 calls map”改为 `call-id -> :state`，不再让旧 `:result`/`:error` 载荷多留一个 tick。
- `publish-run-events!` 每 tick 用 `prune-to-live` 剪掉已删除会话；live 集合未变时不复制。
- 压力测试的 reader future 在 stop 抛错时也会停止并 join，避免测试 JVM 泄漏线程。
- 最终：20 轮 180 assertions / 0F/0E、exit 0；SIGTERM 5/5 exit 143、无新 hs_err；`hs_err` 9 → 9；publisher 5/38、system 17/105、storage 各 ns 0F/0E。机器 OOM 根因是多个 JVM 默认 16.7 GiB 堆，W15 已统一 `deps.edn` 的 `-Xmx1g -XX:MaxDirectMemorySize=512m`。
