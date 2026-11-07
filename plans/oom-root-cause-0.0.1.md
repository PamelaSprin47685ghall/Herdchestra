# 0.0.1 OOM 根因、处置与内存限制

- 日期：2026-11-06 10:19–10:40 CST。
- 范围：整机 OOM 事件（用户报告）、PID 86083、`hc.server.app.restart-smoke-test` 子 JVM、全量测试内存控制。
- 约束：未改根 `AGENTS.md`；未做 Git 写操作；未杀任何其他工人明确持有的进程。
- 协调：`restart_smoke_test.clj` 的显式子 JVM 限制由 W15 落地（`hc.test-jvm`）；`/tmp/pixi` 默认 caps 由 W1 落地。本文件由 W13 汇总，避免重复写。

## 1. 事件时间线

| 时间（CST） | 事件 |
|---|---|
| 10:11 左右 | 第一次全量测试启动（无 caps）；10:19:23 因会话中断被终止，日志停在 `hc.server.app.restart-smoke-test`（1388 行）。不是 OOM 证据。 |
| 10:19:42 | 第二次全量测试启动（**无 caps**，`JAVA_TOOL_OPTIONS` 未设置）；10:21:16 日志停止，进程消失，无退出文件、无异常栈。用户报告此时整机 OOM 全杀。 |
| 10:21:16 | `/tmp/hc-full-test-current.log` 最后写入时间；`hs_err` 9→9（SIGKILL 不产生 hs_err），与“OOM killer”一致。 |
| 10:28:00 | 第三次全量测试启动，显式 `JAVA_TOOL_OPTIONS='-Xmx1g -XX:MaxDirectMemorySize=512m'`；10:33:13 正常结束，exit 1（测试失败，不是 OOM），313 s。 |
| 10:28:0x | 观测到无主 JVM **PID 86083**：test classpath、无 `JAVA_TOOL_OPTIONS`、RSS 2,792,264 KB、elapsed 8 s。 |
| 10:29:2x | 复查 `/proc/86083` 已不存在；**W13 未对其执行任何 kill**。W1、W15 也确认不是自己的进程。 |

## 2. PID 86083 处置

- 归属：`PWD=/home/kunweiz/Desktop/vibe/Herdchestra`，classpath 为 `test:src:resources`，无 caps，父进程链已不可考。
- 最后一次观测：RSS 2,792,264 KB（约 2.79 GB），启动 8 秒；增长速度快于正常单测 JVM。
- 结果：在授权处置前自行退出（10:29:2x 确认不存在）。**未 kill、未 TERM、未 KILL**。
- 记录：`/tmp/hc-full-test-rss-timeline.txt` 只跟踪我的 85370；86083 数据来自 10:28 的一次性 `ps` 采样。

## 3. 根因

1. **内核 OOM，不是 SIGSEGV**：W15 的内核证据是 **15–16 个 java 进程合计 46–47 GiB**；每个 JVM 的默认 `MaxHeapSize` 为物理内存的 1/4（62 GiB 机器上约 **16.7 GiB**）。`hs_err` 9→9、日志无 `OutOfMemoryError`，与 OOM killer 的 SIGKILL 一致。
2. **默认堆按物理内存计算**：没有 `-Xmx` 的 JVM 不按实际需要起算，而是按 1/4 物理内存设定上限；并发 worker 的 test/app JVM 越多，承诺内存越大。
3. **子 JVM 不继承父 JVM 的堆限制**（W15 的 `test/hc/test_jvm.clj` docstring 原话）：`restart_smoke_test` 的 5 个场景各 spawn 一个完整 app JVM；未显式传 `-Xmx` 时，每个子 JVM 都按 16.7 GiB 上限起算。
4. **并发叠加**：事件窗口内同时存在多个工人的 test/app JVM（我观测到 84014 1.09 GB、88043 0.9 GB、89497 545 MB、89634 1.4 GB 等）与 E2E 浏览器进程；单看每个都不致命，叠加后触发整机 OOM。
5. **触发点**：两次死亡都在 `restart-smoke-test` 的子 JVM 阶段；该用例是全套里唯一“一个测试内再起 5 个完整 app JVM”的用例，因此最先撞上内存墙。不是产品代码泄漏。

## 4. 已落地的修复

| 项 | 内容 | 证据 |
|---|---|---|
| W13 全量测试 | `JAVA_TOOL_OPTIONS='-Xmx1g -XX:MaxDirectMemorySize=512m'`；`jcmd 85370 VM.flags` 实测 `MaxHeapSize=1073741824`、`MaxDirectMemorySize=536870912` | `/tmp/hc-full-test-current.log` 首行；`/proc/85370/environ` |
| W15 依赖别名 caps | `deps.edn` 给 `:dev`/`:browser-test`/`:test`/`:server`/`:cljs`/`:shadow` 统一加 `:jvm-opts ["-Xmx1g" "-XX:MaxDirectMemorySize=512m" "-XX:+ExitOnOutOfMemoryError"]`（10:31:14 落地） | `deps.edn:9-18,27-28,34-35,85-86,98-99,107-108` |
| W15 子 JVM 显式限制 | 新增 `test/hc/test_jvm.clj`：`-Xmx512m -XX:MaxDirectMemorySize=256m -XX:+ExitOnOutOfMemoryError -XX:TieredStopAtLevel=1`；`restart_smoke_test.clj` 改用 `hc.test-jvm/sh`；同时被 `extensions/support.clj`、`recovery/restart_smoke_test.clj`、`storage/disk_smoke_test.clj` 使用 | `test/hc/test_jvm.clj`；`restart_smoke_test.clj` 已无内联 `java-exe` |
| W15 有界化 | owner mailbox 上限 4096；diagnostics 日志上限 2000 条；Chrome `--disable-dev-shm-usage` + V8 堆 512m | W15 报告 |
| W15 孤儿与挂起防护 | `test/e2e/support/app.mjs` 增加 detached watchdog：父 Node 消失时自动 TERM/KILL 遗留 app；`test/hc/test_jvm.clj` 子 JVM 180 s 超时（destroyForcibly，exit 124） | W15 报告；`test/hc/test_jvm.clj` |
| W1 全局默认 | `/tmp/pixi` 包装默认导出 `JAVA_TOOL_OPTIONS=-Xmx1g -XX:MaxDirectMemorySize=512m`（已验证 max heap 1024 MB）；Chrome 走 `/tmp/hc-chrome`（自动 `--disable-dev-shm-usage`）；`/tmp/hc-app-single.lock` 限制单 app | W1 报告 |
| 既有 | `test/e2e/support/app.mjs:14` 已用 `-J-Xmx1g -J-XX:MaxDirectMemorySize=512m`；`scripts/store-shutdown-*.sh` 已用 1g/512m | 源码 |

串行性：`run-child` 通过 `clojure.java.shell/sh` 阻塞执行，5 个场景**串行**，不是并发 5 个；W15 保留 5 个场景（seed/verify/blocked/retry-blocked/session-list-blocked 各自覆盖不同启动路径）。10:39 W13 发现同一 ns 有 3 个并发运行（W15 两个 + 我一个），已停止自己的 PID 165009/子 PID 169207，把重资源测试降到 1–2 个；W15 负责保留最终一份验证。

## 5. 前后内存数据

| 对象 | 限制 | 峰值 / 观测 |
|---|---|---|
| 86083（无主、无 caps） | 默认（≈16.7 GiB 上限） | **2.79 GB RSS / 8 s** |
| 全量测试父 JVM 85370（有 caps） | 1 GiB 堆 + 512 MiB direct | **2,066,852 KB ≈ 1.97 GiB VmHWM**；常态 1.80–1.85 GiB；313 s 完成 |
| 全量测试（最终冻结树 04f859ac） | 1 GiB 堆 + 512 MiB direct | **2,149,072 KB ≈ 2.05 GiB VmHWM**；370 s；3741 tests / 0F / 0E；exit 0 |
| 全量测试（最终冻结 8b210896） | 1 GiB 堆 + 512 MiB direct | **1,988,920 KB ≈ 1.90 GiB VmHWM**；365 s；3746 tests / 0F / 0E；exit 0 |
| 全量测试（最终冻结 ea9e5732） | 1 GiB 堆 + 512 MiB direct | **≈ 1.94 GiB VmHWM**；365 s；3748 tests / 24306 assertions / 0F / 0E；exit 0（W35，13:04–13:10） |
| 全量测试（a4518891 / ea9e5732，final4） | 1 GiB 堆 + 512 MiB direct | **2,128,952 KB ≈ 2.03 GiB VmHWM**；365 s；3749 tests / 1F（scheduler 日界属性）/ 0E；exit 1（W35，13:15–13:21） |
| 全量测试（final6：7667323a / 0c05b7d1） | 1 GiB 堆 + 512 MiB direct | **2,022,024 KB ≈ 1.93 GiB VmHWM**；365 s；3749 tests / 24310 assertions / 0F / 0E；exit 0（W35，13:38–13:44，单树） |
| 全量测试（final7：a97c68f7 / 072587a4，前冻结） | 1 GiB 堆 + 512 MiB direct | **2,065,732 KB ≈ 1.97 GiB VmHWM**；380 s；3749 tests / 24310 assertions / 0F / 0E；exit 0（W35，13:53–14:00，单树） |
| 全量测试（final8：d1d8d08d / 072587a4，前冻结） | 1 GiB 堆 + 512 MiB direct | **2,095,668 KB ≈ 2.00 GiB VmHWM**；445 s；3749 tests / 24310 assertions / 0F / 0E；exit 0（W35，14:11–14:18，单树） |
| 全量测试（final9：e465f626 / 072587a4，作废） | 1 GiB 堆 + 512 MiB direct | **1,959,308 KB ≈ 1.87 GiB VmHWM**；376 s；3757 tests / 24347 assertions / 0F / 0E；exit 0（W35，15:24–15:30，单树；树含 DBG 打印，作废） |
| 全量测试（final10：6aa694f1 / 072587a4，前冻结） | 1 GiB 堆 + 512 MiB direct | **2,104,740 KB ≈ 2.01 GiB VmHWM**；365 s；3758 tests / 24351 assertions / 0F / 0E；exit 0（W35，15:51–15:57，单树） |
| 全量测试（final11：ab801450 / 72de4f9a，前冻结） | 1 GiB 堆 + 512 MiB direct | **2,120,904 KB ≈ 2.02 GiB VmHWM**；360 s；3792 tests / 24578 assertions / 0F / 0E；exit 0（W35，17:50–17:56，单树） |
| 全量测试（final12：7dfcf0d7 / 72de4f9a） | 1 GiB 堆 + 512 MiB direct | **2,057,324 KB ≈ 1.96 GiB VmHWM**；356 s；3793 tests / 24585 assertions / 0F / 0E；exit 0（W35，01:29–01:35，单树） |
| restart-smoke（有 caps，干净单跑） | 父 1 GiB + 子 512m/256m | **整棵进程树峰值 747.1 MiB**；子 JVM 约 498 MB（堆 used 345 MB / committed 527 MB）；3 tests / 5 assertions / 0F / 0E，236.5 s，exit 0；free available 49,661→52,464 MiB |
| app-check（有 caps） | 1 GiB/512 MiB | 21 s（W15 复测 19.1 s），exit 0；W15 进程树峰值 1,101 MiB |
| E2E 01（app 768m/512m + Chrome V8 512m） | 单 app + 单 Chrome | UI 点击超时 240 s（非 OOM）；进程树峰值 2,371 MiB（app 947 MiB + Chrome≈1.0 GiB + node≈0.5 GiB）；无 OOM、无 hs_err |
| 整机空闲内存（OOM 后） | — | 可用 45–50 GiB，used 4.8–17 GB |

> **当前最终冻结为 final12：`7dfcf0d7` / `72de4f9a`**（3793/24585/0F/0E，356 s，峰值 1.96 GiB）。
> final11（`ab801450` / `72de4f9a`）、final10（`6aa694f1` / `072587a4`）、final9（`e465f626`，含 DBG）、
> final8（`d1d8d08d`）、final7（`a97c68f7`）及更早各行均为**前冻结/作废历史**，仅供内存对照。

## 6. 剩余风险与建议

1. **`pixi.toml` 的 task 本身仍无 `-J` 参数**，但 `pixi run test`/`app-check` 使用的别名已带 `:jvm-opts`，所以经 Clojure CLI 启动的 JVM 已被 caps 覆盖；`/tmp/pixi` 包装另有 `JAVA_TOOL_OPTIONS` 兜底。剩余缺口只有**绕过 deps.edn 的裸 `java`/无别名 `clojure -M`**。规则：不得用无 caps 的裸 clojure 绕过 deps.edn，优先 `/tmp/pixi` 或仓库 `.pixi`。
2. **restart-smoke 仍是最重用例**：5 次完整 app 启动，每次重读源码；并发编辑会直接击穿（本次 3 个伪失败即 `system.clj`/`history.clj` 半成品）。建议最终门禁在冻结树上跑，并保留“子 JVM 语法错误 = 混合树”的判读。
3. **store-shutdown-stress-test** 在单 JVM 内 20 次起停完整 app；每次 `finally` 关闭 store 并删临时目录，未见泄漏证据，但属内存高水位用例，应与 E2E 串行。
4. **测试侧大对象均有界**：`owner_state_size_test` 最大 3 MB 字符串、`security_test` 600 k 引号串、`devops_test`/`compact_test` 200 k/20 k 字符串，均为一次性字面量，不是无界增长。
5. **`hc.test-runner` 的泄漏检测目前只告警**：`report-leaked-stores!` 在套件结束时打印仍打开的 Datalevin 目录，只有 `-Dhc.test.assert-no-leaked-stores=true` 才失败。建议全量门禁显式打开该属性，避免泄漏 store 在后续用例中累积内存。

## 7. 本领域的无界增长审查

- W13 本次**未改任何产品代码**，只写报告；因此“最近改过的代码”不包含产品路径。
- 对全量测试基础设施逐项检查：`hc.test-runner`（无缓存/atom 累积，只读诊断）、`restart_smoke_test`（子 JVM 逐个退出，已加 caps）、`store_shutdown_stress_test`（固定 20 次，逐次清理）、`support/temp-dir` + `delete-tree!`（有清理）。未发现无界缓存、无界日志、无界事件队列或重复全量拷贝。
- 原先的系统性缺口是“仓库任务入口没有默认堆上限”；W15 已把 caps 写进 `deps.edn` 各别名，`/tmp/pixi` 另有环境兜底。当前缺口只剩绕过 deps.edn 的裸 JVM（见 6.1）。

## 8. 结论

整机 OOM 的直接原因是 15–16 个 java 进程合计 46–47 GiB、每个默认 16.7 GiB 堆；触发点是 `restart-smoke-test` 的子 JVM；不是 Datalevin 原生崩溃，也不是产品代码泄漏。caps 落地后，同一全量套件在 1 GiB 堆下 313 s 完成、峰值 1.97 GiB、无新 `hs_err`。剩余待办：遵守“同一时刻最多 1 个重资源 app JVM + 1 个 Chrome、全量测试与 E2E 串行、结束立即停实例、不用裸 clojure 绕过 deps.edn”，并在冻结树上重跑全量与 `app-check`。

## 9. publisher 侧内存优化（W11，2026-11-06）

### 9.1 热点

`src/hc/server/app/system.clj` 的 publisher 每 500 ms 执行一次：

- `baseline` 构造完整 snapshot；
- `publish-state!` 对 delta 和 snapshot 各做一次 `gateway/event-bytes`（内部 `pr-str` 全量字符串 + UTF-8 字节数组）；
- `gateway/publish!` 再对选中事件做一次 `estimate-bytes`。

一个 2 MiB snapshot 的 tick 会打印同一状态约 3 次，产生约 12 MiB 瞬时分配。

### 9.2 修复

1. `publish-state!` 先用排除 `:server/now` 的 `not=` 判断是否变化；无变化直接 `:noop`，不跑 `gateway-diff/diff`、不打印事件。
2. 小 delta（≤16 KiB）不再序列化完整 snapshot 做大小比较；delta 是合法状态事件，直接发布。
3. `gateway/publish!` 新增可选 `known-size`，复用 `event-bytes` 已算出的值，避免选中事件二次打印。
4. `previous-calls` 改存 `call-id -> :state`；`previous-messages` 改存 `message-id -> length`（`text-delta`）；每 tick `prune-to-live` 剪掉已删除会话。
5. 压力测试 reader future 在 stop 抛错时也置停止标志并 join。
6. `scheduler/stop!` 在 `shutdownNow` 后补最终 `awaitTermination`（5 s + 5 s），超时返回 `:stopped? false`，`system/stop!` 因此不关 store。

### 9.3 前后数据

基准：1000 条消息、每条 2000 字符，snapshot 估算 2,027,843 bytes；单线程 `ThreadMXBean/getCurrentThreadAllocatedBytes`，20 次平均。

| 路径 | 耗时/tick | 分配/tick |
|---|---:|---:|
| 旧：delta + snapshot + publish 再打印 | 28.92 ms | 12.13 MB |
| 新：小 delta，仅 1 次打印 | 0.64 ms | 2.40 MB |

压力 JVM（`jcmd`）：

- `MaxHeapSize=1073741824`、`MaxDirectMemorySize=536870912`；
- `GC.heap_info` mid `used 26680K`，late `used 243584K`；
- `ps` RSS 205,688K（启动早期样本）。

### 9.4 测试

- `hc.server.app.publisher-test`：10 tests / 58 assertions，0F/0E（含无变化零打印、小 delta 单次打印、大 delta 复用 size、baseline 缓存命中与 key 失效）。
- `hc.server.scheduler-test`：20 / 241，0F/0E。
- `hc.server.app.system-test`：17 / 105，0F/0E。
- 20 轮 start/stop 压力：180 assertions，0F/0E，exit 0；`hs_err` 9 → 9。

### 9.5 baseline 全量快照缓存（本轮已实现）

先测再改。基准：每个 session 10 条消息、每条 200 字符；单 JVM、`-Xmx1g`，`ThreadMXBean` 当前线程分配。

| 规模 | 全量 `snapshot/baseline` | 缓存命中 |
|---|---:|---:|
| 100 sessions | 31.99 ms / 48.61 MB per build | 0.00 ms / 0.00 MB |
| 1000 sessions | 195.78 ms / 487.56 MB per build | 0.00 ms / 0.00 MB |

同进程 heap/RSS：100 sessions 116.0→112.8 MB heap、831→850 MB RSS；1000 sessions 139.2→112.8 MB heap、968→1054 MB RSS（RSS 含 JVM 固定开销；分配是瞬时峰值，GC 后回落）。

实现：

1. publisher 增加 `baseline-cache`；`baseline-cache-key` 由 `[session-id revision]` 列表、`owner-version` 映射、active session、command epoch、`:models` 值、`:materials/revision` 组成。
2. `cached-baseline` 在 key 相同时直接返回上一份 baseline map；只有 key 变化才重新调用 `baseline`。
3. `publish-body!` 每 tick 仍取 `component-snapshot`（materials/diagnostics/scheduler 等），并与缓存的 baseline 浅合并；因此组件状态变化仍会发布。
4. 无变化时 `publish-state!` 仍是 `:noop`；session 删除/消息折叠/revision 变化都会改变 key，重建后不会复活旧实体。
5. `snapshot/baseline` 内部再按 session 缓存子块：`conversation-view` 按 `[session-id owner-version]`，非 active `session-history` 按 `revision`，`summary-row` 按 `[revision epoch]`；每 tick 剪掉不可见 session。

单 session 变化时（per-session 子块缓存，单 JVM `-Xmx1g`）：

| 规模 | 全部缓存命中（仍做全局合并） | 一个 session 变化 | 无缓存全量 |
|---|---:|---:|---:|
| 100 sessions | 12.43 ms / 12.69 MB | 11.71 ms / 22.88 MB | 32.32 ms / 57.89 MB |
| 1000 sessions | 56.00 ms / 125.85 MB | 82.74 ms / 224.55 MB | 211.96 ms / 580.56 MB |

回归：`publisher-test` 11 tests / 65 assertions（缓存命中、key 失效、只重建变化 session）；`system-test` 17/105；`gateway-test` 9/41；20 轮压力 180 assertions / 0F/0E；`hs_err` 9→9。压力 JVM `GC.heap_info` mid used 39,985K、late used 47,370K；RSS 123,344K（启动早期样本）。

下一热点：`component-snapshot` 仍每 500 ms 构造 materials/diagnostics/scheduler 等完整视图；baseline 的全局合并（`base-by-id`/`by-id`/`order`）在单 session 变化时仍遍历全部消息（1000 sessions 约 82.74 ms / 224.55 MB）。

## 10. component-snapshot 缓存（W11，2026-11-06）

### 10.1 热点与实现

`component-snapshot` 每 500 ms 重新构造 materials/diagnostics/extensions/speech/search 等视图。按各组件 revision/version 缓存：

- materials：`materials-session/revision` 读取 state revision，快照按 `[session-id revision]` 缓存（active session 变化会换 key）。
- diagnostics：`diagnostics-service/revision` 读取分区 version，baseline 按 version 缓存。
- extensions：按 `@(:version component)` 缓存。
- speech：按 `(:revision @(:config component))` 缓存。
- search：按 `[(:config service) @(:tests service)]` 缓存。
- scheduler：`service/snapshot` 只是 `@(:state service)`，本身无构造开销，不额外缓存。
- model-auth/documents/artifacts/fleet 暂无廉价 revision 访问器，保持每 tick 构造。

`cached-component` 在 version 为 nil 时总是重建，避免缓存无法验证的值；组件缓存只有固定几个 key，天然有界。

### 10.2 前后数据

空应用实测 `component-snapshot`（单 JVM `-Xmx1g`，20/50 次平均）：

| 路径 | 耗时/次 | 分配/次 |
|---|---:|---:|
| 无缓存 | 3.69 ms | 0.30 MB |
| 缓存命中 | 2.04 ms | 0.26 MB |

heap 163→185 MB、RSS 848→850 MB（启动/基准噪声；空应用 materials/diagnostics 数据少，真实数据下缓存收益更大）。

### 10.3 回归

`publisher-test` 12 tests / 72 assertions（新增 component 缓存命中/nil revision 重建）；`system-test` 17/105；`gateway-test` 9/41；`scheduler-test` 20/241；20 轮压力 180 assertions / 0F/0E；`hs_err` 9→9。

### 10.4 P0 括号修复

`component-snapshot` 第二 arity 在拆分时缺少一个关闭括号，导致 `system.clj` EOF。已在 line 2461 补齐；`(require 'hc.server.app.system)` exit 0，`app-check` exit 0 / `:ok true`。未回退其他工人改动。
