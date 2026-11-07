# Datalevin use-after-close 修复（0.0.1）

- 日期：2026-11-05 17:45 CST。
- 范围：`system/stop!`、publisher 生命周期、shutdown hook 路径、storage 关闭屏障、Datalevin 自带 JVM shutdown hook。
- 关联：`plans/hs-err-datalevin-0.0.1.md`（W1 根因调查）、DEBT D-06。
- 结论：两个关闭窗口都已修复；20 轮 start/stop、5 轮 SIGTERM、3 次 `app-check` 均通过，未产生新 `hs_err`。

## 1. 根因

### 1.1 publisher 读与 store 关闭竞争

`system/stop!` 先 `(.shutdownNow publisher-executor)`，但 `shutdownNow` 只取消排队任务并中断当前任务，不等待 Datalevin 原生读返回；随后 `close-store!` 关闭 LMDB env。Datalevin 的 `close-kv` 会 `close-reader-rtxs!` 并释放 DBI/env，正在 `mdb_txn_begin` / `mdb_get` / `mdb_cursor_get` 的线程继续访问已释放内存，产生 SIGSEGV。

第二个入口是关闭期的直接发布：`browser/shutdown!` 会 `emit!`，Owner 订阅回调同步调用 `publish-now!`；旧实现没有停止标志，`hc-shutdown` 线程因此重新进入 `baseline`/`session_view` 读 store。`hs_err_pid1928617.log` 的栈即 `browser/shutdown! → publish-now! → baseline → session_view → scan`。

`d/get-range` 是 eager 读取（Datalevin 1.2.10 文档明确与 lazy `range-seq` 区分），nippy/ByteBuffer 反序列化在 `get-range` 调用内完成，不会作为 lazy seq 逃逸到 txn 关闭之后；#4/#6 的 nippy 崩溃是“读与 close 并发”，不是独立的反序列化缺陷。因此不需要改 engine 读取路径。

### 1.2 Datalevin 自带的 JVM shutdown hook（追加根因）

只修 `system/stop!` 仍不够。正常 SIGTERM 下 JVM **并发**运行所有 shutdown hook；Datalevin 1.2.10 为每个 LMDB env 注册自己的 hook，直接 `close-kv`，绕过 `hc-shutdown → system/stop! → publisher awaitTermination`。

证据：

- `hs_err_pid4054882.log`（JDK 27，W9 E2E04，16:52:46）与 `hs_err_pid4063480.log`（Pixi JDK 21，W8，16:53:20）都是正常 SIGTERM stop（存在 `hc-shutdown`、`SIGTERM handler`，没有 kill -9）。
- `hs_err_pid4063480.log` 的 publisher 栈已包含新代码 `start_publisher$publish_body_BANG_` / `publish_now_BANG_`，但仍在 `session_view → scan → nippy read_typed` 崩溃。
- 说明 `system/stop!` 的 await 顺序没有生效：库 hook 与 `hc-shutdown` 并发，库 hook 先关了 env。

## 2. 源码改动

### 2.1 `src/hc/server/app/system.clj`

1. `start-publisher` 新增 `stopped?` 原子标志与 `ReentrantLock publish-lock`。
2. `publish-body!` 保留原有快照/基线读取；`publish-now!` 在 `publish-lock` 内先检查 `stopped?`，停止后不再发起新读。直接调用者（Owner 通知、browser `:publish!`）与 500 ms 定时 tick 共用同一把锁。
3. `schedule!` 在停止后拒绝再调度，并吞掉 executor 已关闭时的 `RejectedExecutionException`。
4. `:stop!`：
   - 先 `compare-and-set! stopped? false true`；
   - `remove-watch` + 取消 Owner 订阅；
   - `shutdownNow`，记录被取消的排队任务数；
   - `awaitTermination`，再 `tryLock publish-lock` 等待直接调用者；
   - 全部在 `:maintenance :publisher-stop-timeout-ms`（默认 10 s）内完成；超时返回 `{:ok? false :stopped? false :terminated? false :timeout? true :timeout-ms n :cancelled-tasks n}`，`system/stop!` 因此不关闭 store。
   - 若在 publisher 自己的读线程上调用 stop，返回 `:reentrant? true` 失败，不自我中断、不关 store。
5. `system/stop!` 顺序改为：drain → http → **publisher（等待读结束）** → gateway → fleet-gc → scheduler → owners → browser/extensions/speech/model-auth/fleet/model ports → store。publisher 在 gateway 之前停止，避免发布线程向已停网关广播。
6. `start-internal!` 在 store 打开后立即调用 `storage/expect-ordered-close!`；`stop!` 入口再次幂等标记。这样即使 SIGTERM 在启动早期到达，Datalevin hook 也会等待应用关闭，而不是直接关 env。

### 2.2 `src/hc/server/storage.clj`

新增 `ReentrantReadWriteLock` 读屏障：

- `Store` 增加 `:read-lock`；
- `read-session-view`、`list-open-obligations`、`store-info`、`verify-integrity`、`export-state`、`manage-blob-refs!` 的 `:list` 分支在 read lock 内执行，并在锁内重新 `ensure-open!`；
- `close-store!` 先取 commit lock（等待在途提交），再取 write lock（等待所有已进入的读），然后置 `:closed? true` 并 `engine/close!`；
- 关闭后到达的读在 write lock 之后重新检查 `:closed?`，得到 `:STORAGE_CLOSED`，不会触碰已释放的 native env；
- 读路径不取 commit lock，提交路径不取 read lock，锁序固定为 commit → write，避免环。

### 2.3 `src/hc/server/storage/engine/datalevin.clj`

不再注销 Datalevin 的 shutdown hook（`removeShutdownHook` 在 JVM shutdown 进行中会抛 `IllegalStateException`，且启动期注销会引入额外失败面）。改为：

- `open!` 成功后调用 `datalevin.binding.cpp.lifecycle/register-shutdown-close!`，注册更高层 close action；
- close action 读取 `ordered?` 原子：
  - `false`（直接打开的测试/CLI store）：保持 Datalevin 原行为，立即 `close-kv`；
  - `true`（应用 store）：等待 `closed` promise，最多 30 s；应用 `close-store!` 完成后释放，库 hook 不再并发关 env；
- `expect-ordered-close!` 由 `system/start!`/`stop!` 调用；
- `close!` 在 `finally` 中 deliver `closed`，即使 `close-kv` 抛错也不会让 hook 等满 30 s；
- `register-shutdown-close!` 只 swap atom，不调用 `removeShutdownHook`，启动期与 shutdown 期都安全。

### 2.4 publisher 内存热点与 OOM 防护

机器 OOM 的直接原因是 15–16 个 java 进程、每个默认 `MaxHeapSize` 约 16.7 GiB；不是 `hs_err` SIGSEGV。W15 已在 `deps.edn` 各 alias 统一 `-Xmx1g -XX:MaxDirectMemorySize=512m -XX:+ExitOnOutOfMemoryError`。本修复在此之上处理 publisher 的**无界保留**：

1. `previous-messages` 原先为每个 assistant 消息保存完整文本引用。文本对象本身与 session 状态共享，但删除消息/会话后 publisher 仍钉住这些字符串，无法 GC。改为 `previous-message-lengths`，只保存 `message-id -> length`；新增 `text-delta` 只按长度取追加后缀。未增长/缩短/同长度替换都不发 delta，与原 append-only 行为一致。
2. `previous-calls` 原先保存上一 tick 的整个 `calls` map；大结果/错误载荷会随旧 map 多保留一个 tick。改为 `call-id -> :state`，只保留状态迁移检测所需的关键字。
3. 两个 map 原先从不为已删除会话清理。`publish-run-events!` 现在收集 live session id，并在 tick 末用 `prune-to-live` 剪枝；live 集合未变时返回原 map，不复制。
4. 压力测试的 `load-future` 在 `system/stop!` 抛错时可能永久自旋；现在在 `finally` 中置停止标志并 join，避免测试 JVM 泄漏线程。
5. 脚本资源上限：stress 用 `-J-Xmx1g -J-XX:MaxDirectMemorySize=512m`，SIGTERM app 用显式 `-Xmx1g -XX:MaxDirectMemorySize=512m`；不启动 Chrome。全量测试与 E2E 串行。

前后数据（按保留引用估算）：

- 删除一个含 1000 条 10 KiB assistant 文本的会话：旧 publisher 在进程剩余生命周期内钉住约 10 MiB 文本 + 最后一份 calls map；新实现下一次 tick 剪掉该 session，保留 0。
- 活跃会话内删除单条 assistant 消息：旧实现继续钉住该消息文本；新实现只保留一个 long，文本可被 GC。
- calls：旧实现每个 session 保留上一份完整 calls map（含 `:result`/`:error`）；新实现每个 call 一个状态关键字。

回归：`hc.server.app.publisher-test` 8 tests / 49 assertions（含 `text-delta`、`prune-to-live`、无变化零打印、小 delta 单次打印、大 delta 复用 size）；20 轮压力 180 assertions / 0F/0E。

### 2.5 序列化与 scheduler 关闭优化

1. `publish-state!` 先用排除 `:server/now` 的 `not=` 判断状态是否变化；无变化直接 `:noop`，不再跑 `gateway-diff/diff`、不打印任何事件。
2. 小 delta（≤16 KiB）不再为了和完整 snapshot 比大小而打印 snapshot；delta 是合法状态事件，直接发布。
3. `gateway/publish!` 增加可选 `known-size`；publisher 复用 `gateway/event-bytes` 已算出的值，选中事件不再二次 `pr-str`。
4. 大 delta 仍比较 delta/snapshot 一次，并用已知 size 发布选中事件；delta 发布失败时回退完整 snapshot。
5. `scheduler/stop!` 在 `shutdownNow` 后补最终 `awaitTermination`（5 s + 5 s）；超时返回 `:stopped? false`、`:terminated? false`、`:timeout? true`，`system/stop!` 因此保持 store 打开。
6. publisher 增加 `baseline-cache`：key 由 `[session-id revision]` 列表、owner version 映射、active session、command epoch、`:models` 值、`:materials/revision` 组成；key 未变直接复用上一份 baseline map。
7. key 变化才重新构造 baseline；每 tick 仍取 `component-snapshot` 并与缓存 baseline 浅合并，组件状态变化不会丢。
8. session 删除、消息折叠、revision/epoch/materials 变化都会改变 key；重建后的 snapshot 不包含旧实体，不会复活已删除数据。
9. `snapshot/baseline` 内部按 session 缓存子块：`conversation-view` 按 `[session-id owner-version]`，非 active `session-history` 按 `revision`，`summary-row` 按 `[revision epoch]`；每 tick 剪掉不可见 session。一个 session 变化时只重建该 session 的子块，其余复用。
10. `component-snapshot` 按组件 revision 缓存：materials `:materials/revision`、diagnostics 分区 version、extensions `:version`、speech config revision、search config/tests；scheduler 本身只是 deref；无 revision 的组件保持每 tick 构造。nil revision 不缓存。

基准（2,027,843-byte snapshot，20 次平均，`ThreadMXBean` 当前线程分配）：

| 路径 | 耗时/tick | 分配/tick |
|---|---:|---:|
| 旧：delta + snapshot + publish 再打印 | 28.92 ms | 12.13 MB |
| 新：小 delta，仅 1 次打印 | 0.64 ms | 2.40 MB |

baseline 基准（每 session 10 条消息 × 200 字符，单 JVM `-Xmx1g`）：

| 规模 | 全量 baseline | 缓存命中 |
|---|---:|---:|
| 100 sessions | 31.99 ms / 48.61 MB per build | 0.00 ms / 0.00 MB |
| 1000 sessions | 195.78 ms / 487.56 MB per build | 0.00 ms / 0.00 MB |

单 session 变化（per-session 子块缓存）：

| 规模 | 全部缓存命中（全局合并） | 一个 session 变化 | 无缓存全量 |
|---|---:|---:|---:|
| 100 sessions | 12.43 ms / 12.69 MB | 11.71 ms / 22.88 MB | 32.32 ms / 57.89 MB |
| 1000 sessions | 56.00 ms / 125.85 MB | 82.74 ms / 224.55 MB | 211.96 ms / 580.56 MB |

component-snapshot（空应用，单 JVM `-Xmx1g`）：

| 路径 | 耗时/次 | 分配/次 |
|---|---:|---:|
| 无缓存 | 3.69 ms | 0.30 MB |
| 缓存命中 | 2.04 ms | 0.26 MB |

压力 JVM `jcmd`（baseline 缓存后）：`MaxHeapSize=1073741824`、`MaxDirectMemorySize=536870912`；`GC.heap_info` mid `used 39985K`、late `used 47370K`；`ps` RSS 123,344K（启动早期样本）。

## 3. 关闭顺序

修复前：

```text
drain → http → gateway → publisher(shutdownNow，不等待) → fleet-gc → scheduler
      → owners → browser → ... → model ports → close-store!
```

修复后：

```text
drain → http
      → publisher: stopped?=true → shutdownNow → awaitTermination → publish-lock
      → gateway → fleet-gc → scheduler → owners → browser → ...
      → model ports → close-store!（commit lock → write lock → engine close）
```

JVM shutdown 路径：

```text
SIGTERM
  ├─ hc-shutdown: system/stop! → publisher drain → ... → close-store! → deliver closed
  └─ Datalevin hook: ordered? true → wait(closed, 30 s) → return
```

任何一步报告 `:stopped? false`（包括 publisher 超时）都不关闭 store；`system/stop!` 返回失败证据，维护流程不会在活跃读之上恢复。

## 4. 测试

### 4.1 20 轮 start/stop 压力

`test/hc/server/app/store_shutdown_stress_test.clj`：

- 真实 `system/start!` / `system/stop!` 20 轮；
- 每轮独立临时 data-dir / workspace / static root；
- 每轮启动一个直接 `publish-now!` 读循环，并在 stop 前让它与 500 ms 定时 tick 竞争；
- 断言：stop 返回 `:stopped? true`、`:store-closed? true`、publisher 证据 `:stopped? true`、publisher 标志为 true、stop 后 `publish-now!` 返回 nil、关闭后的 `read-session-view` 返回 `:STORAGE_CLOSED`；
- 20 轮 × 9 断言 = 180 assertions。

结果（Pixi OpenJDK 21.0.10）：

```text
Ran 1 tests containing 180 assertions.
0 failures, 0 errors.
test exit: 0
hs_err before: 9
hs_err after:  9
new hs_err:    none
```

日志：`/tmp/hc-store-shutdown-stress.log`。脚本：`scripts/store-shutdown-stress.sh`。

### 4.2 SIGTERM 5 轮

`scripts/store-shutdown-sigterm.sh`：

- 直接启动 app JVM（独立 `-Djava.io.tmpdir`、独立 data-dir）；
- 等待 `Herdchestra listening on`（冷启动最长 90 s），再等 1.2 s 让 publisher 完成多次 tick；
- `kill -TERM`，等待退出；
- 断言 5/5 启动成功、退出 0/143、无新 `hs_err`。

结果：

```text
round 1: pid=167665 exit=143
round 2: pid=168290 exit=143
round 3: pid=172429 exit=143
round 4: pid=178194 exit=143
round 5: pid=182030 exit=143
hs_err before: 9
hs_err after:  9
new hs_err:    none
PASS: 5 SIGTERM stops, no new hs_err
```

日志：`/tmp/hc-store-shutdown-sigterm.log`。

> W12 早期 5 轮里有 4 轮“app 未启动”，根因是脚本/ harness 的 20 s 启动等待在 CPU 争用下超时；失败 app 日志只有第一条 namespace warning，没有 `IllegalStateException`、`removeShutdownHook` 或编译错误。脚本等待已改为 90 s，当前 5/5 成功。

### 4.3 单实例自检

`pixi run app-check` 在最终树上连续 3 次（Pixi JDK 21），每次退出 0；日志 `/tmp/hc-p0-postrestart-app-check-{1,2,3}.log`。3 次结束后 `hs_err` 仍为 9。冷启动在 CPU 争用下可能超过 20 s；测试/harness 必须留足等待。

### 4.4 单元/集成测试

| 命名空间 | 结果 |
|---|---|
| `hc.server.app.system-test`（component 缓存后复跑） | 17 / 105 / 0 / 0 |
| `hc.server.app.publisher-test` | 12 / 72 / 0 / 0（含无变化零打印、小 delta 单次打印、大 delta 复用 size、baseline/component 缓存命中、key 失效、只重建变化 session） |
| `hc.server.gateway-test` | 9 / 41 / 0 / 0 |
| `hc.server.scheduler-test` | 20 / 241 / 0 / 0（含 stop 终止等待） |
| `hc.server.storage-test` | 27 / 102 / 0 / 0 |
| `hc.server.storage.engine-lifecycle-test` | 10 / 41 / 0 / 0（含 shutdown close action 注册测试） |
| `hc.server.storage.session-erasure-test` | 1 / 14 / 0 / 0 |
| `hc.server.storage.owner-state-size-test` | 4 / 20 / 0 / 0 |
| `hc.server.storage.disk-smoke-test` | 1 / 5 / 0 / 0 |

日志：`/tmp/hc-p0-final2-*.log`、`/tmp/hc-p0-mem-system-test.log`、`/tmp/hc-publisher-memory-test.log`。

### 4.5 hs_err 计数

- 调查基线：7（W1 的 7 个历史日志）。
- 修复过程中 W9/W8 的 E2E SIGTERM 又产生 2 个：`hs_err_pid4054882.log`、`hs_err_pid4063480.log`，用于定位 §1.2 的库 hook 根因。
- 最终计数：9；本修复的 20 轮、最终 SIGTERM 5 轮、3 次 app-check、publisher/system/storage 测试之后仍为 9，无新增。

## 5. 为什么不是单次 app-check

单次 start/stop 时 publisher 的 500 ms tick 往往已经完成，`shutdownNow` 之后没有在途原生读；压力测试让直接读循环与定时 tick 在 stop 窗口内持续运行，才覆盖“读与 close 并发”的真实条件。SIGTERM 测试则覆盖 JVM 并发 shutdown hook 这一独立窗口。

## 6. 复检命令

```bash
cd /home/kunweiz/Desktop/vibe/Herdchestra
export PATH="$PWD/.pixi/envs/default/bin:$PATH"
export JAVA_TOOL_OPTIONS="-Xmx1g -XX:MaxDirectMemorySize=512m"

# 20 轮压力（Pixi JDK 21），并检查新 hs_err
./scripts/store-shutdown-stress.sh

# SIGTERM 5 轮
ROUNDS=5 ./scripts/store-shutdown-sigterm.sh

# 3 次单实例自检
clojure -M:server -m hc.server.app --check --port 0
clojure -M:server -m hc.server.app --check --port 0
clojure -M:server -m hc.server.app --check --port 0

# 应用与存储测试
clojure -M:test:compose:server:client -n hc.server.app.system-test
clojure -M:test:compose:server:client \
  -n hc.server.storage-test \
  -n hc.server.storage.engine-lifecycle-test \
  -n hc.server.storage.session-erasure-test \
  -n hc.server.storage.owner-state-size-test \
  -n hc.server.storage.disk-smoke-test

# 崩溃检查
ls hs_err_pid*.log | wc -l
```

## 7. 剩余风险

1. **publisher 超时**：若原生读超过 10 s，stop 失败且 store 保持打开；进程退出时 Datalevin hook 在 30 s 兜底后关闭 env。宁可留下未关闭 env，也不在活跃读上关闭。
2. **30 s 兜底**：应用 hook 未在 30 s 内完成关闭时，Datalevin hook 仍会直接 `close-kv`。这是最后安全阀；如果 publisher 原生读卡死超过 30 s，仍可能 use-after-close。可把兜底时间调大，但会延长异常退出的等待。
3. **publisher 自身线程调用 stop**：返回 `:reentrant? true` 失败，store 不关闭；这是防御性分支，正常关闭路径不触发。
4. **host JDK 26/27**：native-access 警告与更高崩溃率仍在；发布/测试固定 Pixi JDK 21。host JVM 只用于不碰 store 的静态检查。
5. **直接 `close-store!` 的调用者**：read barrier 只保证“已进入的读”在 close 前完成；调用者仍须先停止自己的读者。应用路径已由 `system/stop!` 保证。
6. **scheduler 停止**：`scheduler/stop!` 现在在 `shutdownNow` 后补最终 `awaitTermination`（5 s + 5 s），超时返回 `:stopped? false`，`system/stop!` 保持 store 打开；storage read barrier 仍是第二道保护。若 tick 永久卡死，关闭会失败而不是 use-after-close。
7. **启动超时**：冷启动 + CPU 争用可超过 20 s；测试/harness 必须使用足够长的启动等待（本脚本 90 s），否则会把慢启动误判为失败。
8. **publisher 每 tick 的瞬时分配**：序列化已从“delta + snapshot + 再打印选中事件”降到小 delta 只打印一次（28.92 ms/12.13 MB → 0.64 ms/2.40 MB）；baseline 已按 session/owner/materials version 缓存，未变时复用（100/1000 session 全量 31.99/195.78 ms、48.61/487.56 MB，命中 0），并按 session 缓存子块（一个 session 变化：100/1000 session 11.71/82.74 ms、22.88/224.55 MB，无缓存 32.32/211.96 ms、57.89/580.56 MB）；component-snapshot 已按 materials/diagnostics/extensions/speech/search revision 缓存（空应用 3.69→2.04 ms、0.30→0.26 MB）。剩余热点是单 session 变化时的全局 `base-by-id`/`by-id`/`order` 合并，以及无 revision 的 model-auth/documents/artifacts/fleet 组件；下一步可把全局消息表改为增量更新。堆上限 `-Xmx1g` 使其有界。
9. **OOM 防护**：机器 OOM 的根因是 15–16 个 JVM 使用默认约 16.7 GiB 堆；W15 已在 `deps.edn` 各 alias 统一 `-Xmx1g -XX:MaxDirectMemorySize=512m -XX:+ExitOnOutOfMemoryError`。本修复的脚本同样带 caps，且同一时刻只跑一个 app JVM；全量测试与 E2E 必须串行，结束立即停实例。
