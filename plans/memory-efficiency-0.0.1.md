# 内存效率审计与 OOM 根因（0.0.1）

- 日期：2026-11-06（CST）。
- 范围：系统 OOM 证据、JVM 堆上限、测试子进程、Chrome、无界容器与全量拷贝热点。
- 约束：未改根 `AGENTS.md`；未做 Git 写操作；未杀其他工人的进程。
- 结论先行：10:13/10:21/10:22 的内核 OOM 不是 `hs_err` 里的 SIGSEGV。当时有 15–16 个 Java 进程，合计约 46–47 GiB RSS/anon；每个 JVM 的默认 `MaxHeapSize` 是 16.7 GiB。旧版 `memfuckilld` 在 anon 达到 51,059 MiB 时执行 `sysrq` 全局 OOM，内核选中了 Chrome。9 个 `hs_err` 全部是 Datalevin use-after-close 的 SIGSEGV，没有 `OutOfMemoryError`、`Native memory allocation` 或 `mmap failed`；该问题已由 W1/W11 修复，本次没有重复改。

## 1. 系统证据

### 1.1 内核 OOM 时间线

`journalctl -k` 中三次 OOM 都是 `sysrq: Manual OOM execution`，由旧版 `memfuckilld` 触发：

| 时间 | memfuckilld 日志 | Java 进程 | Java anon 合计 | Java RSS 合计 | Chrome | node | llama-server |
|---|---|---:|---:|---:|---:|---:|---:|
| 10:13:14 | `no victim, sysrq` | 15 | 47,371 MiB | 47,843 MiB | 36 个 / 4,199 MiB RSS | 10 个 / 1,004 MiB | 827 MiB |
| 10:21:47 | `no victim, sysrq` | 16 | 45,980 MiB | 46,472 MiB | 52 个 / 7,212 MiB RSS | 10 个 / 1,219 MiB | 642 MiB |
| 10:22:05 | `no victim, sysrq` | 16 | 46,059 MiB | 46,537 MiB RSS | 58 个 / 7,056 MiB RSS | 12 个 / 1,345 MiB | 942 MiB |

旧服务描述是 `Hard fallback OOM trigger (kernel sysrq via memory.available)`，启动日志 `anon_cap=53539349299 bytes (51059 MiB)`。三次 OOM 的 Java anon 加 Chrome/node/llama 已接近或超过该 cap；`memfuckilld` 找不到单个“够大”的 victim 时，直接写 `/proc/sysrq-trigger`，内核按 `oom_score_adj` 选中 Chrome（`oom_score_adj=300`）。因此“机器全杀”的直接机制是：**多 JVM 默认堆叠加 → 触发外部 OOM 守门 → 全局 OOM 杀进程**，不是 Java 堆内 OOM。

10:27:13 新服务已换成 `Precise OOM guardian for user.slice (RssAnon, no sysrq)`，参数 `anon_cap=41485MiB memavail_min=7658MiB min_victim=512MiB`，改用信号而非 sysrq。它仍是外部约束：任何单个大 JVM 仍可能被杀，所以应用侧必须自己封顶。

### 1.2 hs_err 不是 OOM

仓库根目录 9 个 `hs_err_pid*.log`：

- 全部是 JVM 级 SIGSEGV；顶部帧为 `pthread_mutex_lock`、`mdb_page_search_root`、`mdb_cursor_get`、nippy `read_typed`、`jbyte_disjoint_arraycopy`。
- 没有任何 `OutOfMemoryError`、`Native memory allocation`、`mmap failed`、`unable to create`。
- 9/9 的 `MaxHeapSize` 都是 `16,735,272,960` 字节（约 15.6 GiB，62 GiB 物理内存的 1/4，JVM 人体工学默认值），证明崩溃时也没有显式 `-Xmx`。
- 这些 SIGSEGV 属于 Datalevin 读与 store 关闭的竞争，已由 `plans/hs-err-datalevin-0.0.1.md`、`plans/store-shutdown-fix-0.0.1.md` 修复；本次复检 `hs_err` 仍为 9，无新增。

### 1.3 修复后的当前观测

- 全量测试（W13，PID 85370）在 `JAVA_TOOL_OPTIONS=-Xmx1g -XX:MaxDirectMemorySize=512m` 下运行：采样 RSS 约 2.06 GiB；`jcmd GC.heap_info` 显示 committed 812 MiB、used 686 MiB（1 GiB 上限内）。
- 同期三个 app JVM 各约 1.1–1.4 GiB RSS，合计约 3.7 GiB；可用内存约 45–49 GiB。
- `jcmd GC.class_histogram`（全量测试 JVM）前五类：`Object[]` 29.6 MiB、`byte[]` 28.1 MiB、`PersistentHashMap$BitmapIndexedNode` 12.7 MiB、`Class` 12.6 MiB、`String` 10.1 MiB；没有单一类占据绝对多数，说明该 JVM 是“多命名空间/多测试”的正常工作集，不是某个集合泄漏。
- `free -h` 修复后（10:39）：total 62 GiB，used 17 GiB，free 16 GiB，available 45 GiB；swap 仅 39 MiB。修复前 OOM 时刻 Java 单独就 46–47 GiB。

### 1.4 修复后分场景峰值

| 场景 | 结果 | 耗时 | 进程树峰值 RSS | 可用内存前后 |
|---|---|---|---:|---:|---|
| 全量测试（1 GiB/512 MiB） | 3707 tests / 24072 assertions；26F/10E 来自 D-14 前提与并发编辑，不是 OOM | 313 s | **1.97 GiB VmHWM**（采样 2.06 GiB） | 45–50 GiB 可用 |
| `app-check`（1 GiB/512 MiB cap） | exit 0，`:ok true` | 19.1 s | **1,101 MiB** | 44,653 → 47,354 MiB |
| `hc.server.app.restart-smoke-test`（父 1 GiB，子 512m/256m） | 3 tests / 5 assertions / 0F / 0E | 236.5 s | **747 MiB** | 49,661 → 52,464 MiB |
| E2E 01 短流程（app 768m/512m，Chrome V8 512m） | 跑到 UI 点击步骤超时；内存无异常 | 240 s 测试超时 | **2,371 MiB**（app 947 MiB + Chrome 约 1.0 GiB + node 约 0.5 GiB） | 约 46 GiB 可用 |

E2E 01 的超时是现有 UI 断言/点击问题，不是 OOM；期间 app 与 Chrome 都保持在上限内，没有触发 `memfuckilld`。

现场还发现并处置了两个 E2E 超时后遗留的 app JVM：PID 193377（`/tmp/hc-e2e-vtVgJR`）与 PID 195551（`/tmp/hc-e2e-bPFmbe`），均为 PPID 1、约 950 MiB RSS。`kill -TERM` 后立即退出；随后在 `test/e2e/support/app.mjs` 增加 watchdog，父 Node 进程消失时自动 TERM/KILL 遗留 app。

### 1.5 无 `-Xmx` 进程审计

| 时间 | PID | 情况 | 处置 |
|---|---|---|---|
| 10:28:0x | 86083 | test classpath，命令行无 `-Xmx`，无 `JAVA_TOOL_OPTIONS`，8 s 时 RSS 2,792,264 KB | 10:29:2x 复查已自行退出；未 kill、未 TERM |
| 10:29 | 85370 | 命令行无 `-Xmx`，但 `/proc/85370/environ` 有 `JAVA_TOOL_OPTIONS=-Xmx1g -XX:MaxDirectMemorySize=512m`；`jcmd VM.flags` 实测 `MaxHeapSize=1,073,741,824` | 有 cap，保留到测试结束 |
| 10:29 | 92183 | 85370 的 restart-smoke 子 JVM，同样继承 `JAVA_TOOL_OPTIONS` | 有 cap |
| 10:55 终检 | 当前 6 个 `java` | 6/6 命令行都有显式 `-Xmx`；无 `-Xmx` 进程为 0 | — |

### 1.6 视觉工人 W16–W21 合规审计（10:53，只读）

目录：`/tmp/hc-visual-{startup,conversation,materials,board,ops,responsive}`。

| 项 | 结果 |
|---|---|
| 单实例 | 六个目录都通过 `flock /tmp/hc-app-single.lock` 串行；当前锁由 PID 206833（materials tour）持有，board/startup/conversation/ops/responsive 在队列中；没有第二个视觉 app 并发 |
| JVM caps | board/startup/ops/responsive 用 768m/512m；conversation/materials 用 1g/512m；所有 app 日志都有 `Picked up JAVA_TOOL_OPTIONS` |
| Chrome | 六个目录的启动脚本都带 `--disable-dev-shm-usage --no-sandbox`；当前没有视觉 Chrome 在跑（都在等锁） |
| 清理 | materials `run.sh` 的 `pkill -f hc.server.app/google-chrome` 已改为 PID-scoped cleanup；当前 grep 无 `pkill` |
| 缺口 | 全局锁只覆盖视觉工人；W2/W3/E2E01 在锁外并发，当前 3 个 headless Chrome（203455/221213/223033）+ 1 个用户 Chrome。需要把同一把锁推广到所有 E2E/全量测试 |

11:04 复查：六个视觉目录仍全部使用 `flock /tmp/hc-app-single.lock`；当前 holder 是 visual-conversation 的有限 audit（228036，已自行退出），没有视觉 app/Chrome 误持锁；materials `run.sh` 仍无 `pkill`。全局锁的队列问题见 §1.10。

### 1.7 当前 Java 进程清单（10:53，6 个）

| PID | owner / 来源 | 显式堆 cap | RSS | 备注 |
|---:|---|---:|---:|---|
| 160864 | W4 / `/tmp/hc-w4/config.edn` | 1 GiB | 1,180 MiB | PPID=1 孤儿；已通知 W4，未杀 |
| 201682 | W2 / `/tmp/hc-w2/config-07.edn` | 1 GiB | 1,130 MiB | 活跃 app |
| 220415 | W3 / `/tmp/hc-w3e5r2/config.edn` | 1 GiB | 1,089 MiB | 活跃 app |
| 230525 | W9 / `/tmp/hc-d08/config.edn` | 768 MiB | 909 MiB | D08 探针 |
| 221727 | E2E01 / `/tmp/hc-e2e-5Flcgd/config.edn` | 768 MiB | 900 MiB | Playwright 子进程 |
| 230994 | scheduler-stop test / `/tmp/hc-scheduler-stop-test.log` | 1 GiB | 645 MiB | 测试 JVM |

合计约 5.8 GiB；`free` used 18 GiB / available 44 GiB；`hs_err` 仍为 9。所有 6 个都有显式 `-Xmx`，没有无限制堆。

### 1.8 W11 publisher 优化后复测（11:00）

W11 已确认 stable：20 轮 store-shutdown 压力 180 assertions / 0F/0E；`hc.server.app.system-test` 17/105、publisher-test 8/49、scheduler-test 20/241 全过；stress JVM `MaxHeapSize=1073741824`、`MaxDirectMemorySize=536870912`，RSS 约 201 MiB（启动早期），heap used 24–244 MB。

W15 复测：

| 场景 | 结果 | 峰值 RSS | heap |
|---|---|---:|---|
| `app-check`（W11 stable 后） | exit 0，19.0 s | 1,028 MiB | used 171 MB / committed 270 MB / max 1 GiB |
| 真实 app + 6 s publisher（约 12 tick） | SIGTERM exit 143 | 1,066 MiB | used 271 MB / committed 468 MB / max 1 GiB；direct max 512 MiB |

真实 app 的 `GC.class_histogram` 前几类：`byte[]` 18.9 MB、`Object[]` 16.5 MB、`PersistentHashMap$BitmapIndexedNode` 7.3 MB、`String` 6.8 MB、`Class` 6.2 MB、`ConcurrentHashMap` 5.2 MB；没有单一类占据绝对多数。W11 的 2 MiB snapshot 基准：旧 28.92 ms / 12.13 MB per tick → 新 0.64 ms / 2.40 MB per tick；后续 baseline-cache 命中为 0.00 ms/0.00 MB。

W11 后续 baseline-cache 第一层（每 session 10 条消息 × 200 字符，单 JVM 1g）：100 sessions 全量 31.99 ms / 48.61 MB、1000 sessions 195.78 ms / 487.56 MB；缓存命中 0.00 ms / 0.00 MB。

W11 第二层 per-session 子块缓存：conversation-view 按 `[session-id owner-version]`、非 active session-history 按 revision、summary-row 按 `[revision epoch]` 缓存，每 tick 剪掉不可见 session。单 session 变化：1000 sessions **82.74 ms / 224.55 MB**（无缓存 211.96 ms / 580.56 MB）；100 sessions **11.71 ms / 22.88 MB**（无缓存 32.32 ms / 57.89 MB）。全部缓存命中：100/1000 sessions **12.43/56.00 ms、12.69/125.85 MB**（仍有全局 base-by-id/by-id/order 合并）。下一热点：`component-snapshot` 每 500 ms 全量，以及单 session 变化时的全局消息表合并。

### 1.9 四项热点修复与基准（11:05）

| 热点 | 修复 | 修复前 | 修复后 |
|---|---|---|---|
| `export-state` 全量拷贝 | `:sections` 可选；blob 清理只读 `#{:blob-refs}` | 1000 sessions 全量 **22.2 ms / 17.7 MiB alloc** | 仅引用 **9.55 ms / 5.39 MiB alloc**；`docs`/`obligations` 为 nil |
| diagnostics bundle 全量 `pr-str` | `write-local-artifact!` 按顶层键/每个 section 分块写 `OutputStream`；writer 路径不再先生成整串 | 2.38 MB bundle：**78.3 ms / 30.9 MiB alloc**，临时整串 2.38 MB | **58.5 ms / 38.5 MiB alloc**；临时最大字符串降到一个 section（约 1 KB），峰值下降，总 alloc 略高 |
| CDP `chrome.log` 全量 `slurp` | `read-log-tail` 只读尾部 64 KiB | 10 MB 日志：**33.3 ms / 42.3 MiB alloc** | **0.29 ms / 0.126 MiB alloc**，最多 64 KiB |
| 客户端附件 `memory-cache` 无界 | LRU：最多 20 项 / 64 MiB，按最近使用淘汰 | 25 会话保留 25 项；2×40 MiB 保留 80 MiB | 25 会话保留 **20 项 / 20.5 KB**；2×40 MiB 保留 **1 项 / 41.9 MiB**（≤64 MiB） |

测试：`hc.server.storage.export-sections-test`、`hc.server.diagnostics.bundle-test`、`hc.server.browser.cdp-test`、`hc.client.conversation.attachments-storage-test` 合计 **25 tests / 112 assertions / 0F / 0E**。

W23 E2E09 首段（真实 app + Chrome，bundle dd968793）：2m17s，app RSS **1,096,176 → 1,372,032 KB**（约 1.05 → 1.31 GiB），无 OOM；Plan 首评/底稿/清账、S1→S2→S3→pending→execution、swarm-started、register-actor、split token task、take-or-die、claim 这些断言 PASS。缺口：Board worker-program 卡未渲染、重试未写入 worker,7；归 W23 诊断。

W35 全量（bundle ea9e5732，tree `52547df2`）：**3748 tests / 24306 assertions / 0F / 0E / exit 0 / 365s**；PRE=POST hash `52547df2`，hs_err 9→9，`PEAK_VmHWM=2,029,192 KB`（约 1.93 GiB）；app-check exit 0/15s/:ok true，版本五处 0.0.1。**该 52547df2 基线已被 D-17 修复作废**：src/test 于 13:14:32 变为 `a45188910084da832a2a2cf2108906ac296baf02c309d02a9b0b82dce9c3eca9`（`run_port.clj` + `run_port_test.clj`），bundle 仍 `ea9e5732`。W35 在 13:16–13:30 窗口串行重跑全量 + app-check；窗口内不写 src/test、不重建、不跑 E2E。新数字出来前，不能宣称全量门禁条件 5 已满足。

**E2E09 状态更正（W33）**：E2E09 已在最终树 `ea9e5732` 跑过，**仍 FAIL（UI 初始快照未送达，D-16）**，整体不是 PASS。此前 `plans/evidence/e2e-09-10/e2e09-final.json`（12:10:27）绑 `55e4e8ea`，`ok=false`、`conflictCard=false`、`originalStampUnchangedInCard=false`；这些均为前冻结/失败证据。上一条“这些断言 PASS”只指首段列出的断言，不能作为 E2E09 PASS 声明。

**hash 冻结更新（W33，13:02）**：最终 `main.js = ea9e5732656e79e738883ba384b1a62b4fe9461d396b83612ad4624dd81a39c2`（3,010,646 B，CSS `332f9fd3…`）；`8b210896`、`55e4e8ea`、`dd968793` 全部作废。W35 在 13:04–13:15 全量窗口内不重建/不写 src-test/不跑 E2E；证据必须重绑 `ea9e5732`。E2E09 首段、W20/W25 视觉证据均为“前冻结”，不能作为最终 hash 证据。

**最终冻结（W33，15:58）**：bundle `072587a4`（3,011,162 B，13:51:04；CSS `332f9fd3`）+ 源码树 `6aa694f1`（final10）。`e465f626` 是 final9，已作废；`final8` 的 `d1d8d08d` 及更早 hash 全部前冻结。E2E 证据必须同时记录 bundle `072587a4` + 树 `6aa694f1`；不再写 src/test、不再重建。

**W35 final10 权威结果（15:57）**：bundle `072587a4` + 树 `6aa694f1`，**3758 tests / 24351 assertions / 0F / 0E / exit 0 / 365s**（15:51:13–15:57:18，PRE=POST 单树）；hs_err 9→9，bundle 未变。全量门禁条件 5 在最终冻结树满足；后续 E2E/视觉证据须双记 `072587a4` + `6aa694f1`。W36 E2E05 运行于 16:15–16:33，绑定 `6aa694f1` 正确。

### 1.10 全局锁审计（11:04）

- 持有者：PID 228036 `flock /tmp/hc-app-single.lock bash -c /tmp/hc-visual-conversation/run-audit.sh`，子脚本 257327、app 257332（1g cap，heap used 143 MB）。总等锁+运行 11:44，当前脚本运行 1:33；这是**有限 visual audit**，不是长驻 app。11:04:29 已自行退出，锁交给下一个等待者 234846（W2），**未杀任何进程**。
- 等待者（按 `/proc/locks` 顺序）：234846 W2、235013 E2E01、253082 visual-board、242573 W3、259020 visual-startup、264922 materials-quick。当前没有 W5/E2E09 的 flock 进程；已通知 W5 确认入队。
- 规则：`flock` 只包一个有限 app+Chrome 段；holder 必须等待子进程并在 trap 中清理；禁止把长驻 app 包在锁内；等待者用有界 `-w`；非必要 visual audit 不要排队。注意 `flock` 的文件描述符会被子进程继承，wrapper 异常退出而子进程未清理时锁可能继续被持有；holder 必须验证 app/Chrome 已退出。
- 11:07 追加处置：W4 报告锁由 W5 app 265283/267822 持有。复核确认 265283 是 `flock -w 1800` 包装（PPID=1），267822 是其 app 子进程（`/tmp/hc-w5e/data`，1g/768m cap，健康但无 driver/Chrome 子进程；W5 session 不可用，`/tmp/hc-w5e/evidence` 为空）。按用户授权于 **11:07:01** `kill -TERM` 两个 PID，均立即退出；锁释放后交给 268104（E2E01），等待者 268132（W2）。W5 的 E2E09 未完成，未产生证据。`hs_err` 仍为 9。
- 11:10 E2E01 复查：holder 268104（`flock -w 840`，11:06 起，已 4:18），Playwright 270810、Chrome 270825、app 270955（`/tmp/hc-e2e-rDb9UL`，768m/512m cap）；截图最后停在 11:07 的 `e2e01-06-draft-restored`，约 3.5 分钟无新输出；Playwright test timeout 240s 约 11:11 到期。W2/W23 在排队。已通知 W10；等待 watcher 结果，未提前 kill。
- 11:11:04 E2E01 自行退出（未杀）：Playwright test timeout 触发，结果 `ok=false`、`error="page.waitForTimeout: Target page, context or browser has been closed"`、`finishedAt 11:11:03`。app 270955/Chrome 270825 已清理，无遗留；锁交给 268132（W2），等待者 271726、273310（W23）、276423（W22）。`hs_err` 仍 9，free available 约 51 GiB。
- 11:16:20 视觉优先处置：W5 E2E09 的 flock 304761（`flock -w 3600 ... /tmp/hc-e2e09/run.sh`）仍在排队，但已启动子脚本 306402（`bash /tmp/hc-e2e09/run.sh`，无 app/Chrome）。按用户授权 `kill -TERM` 304761 与 306402，两者均退出；未杀其他进程。锁随即交给 305550（W17 visual-conversation，`run-audit2.sh`，app 307203，1g cap），等待者 306164（W18 materials-quick）。W17 已成为 holder，可跑 ≤5 分钟视觉段；W5 E2E09 未完成，需在视觉序列后重排。
- 11:17:20 W17 释放锁：app 307203/Chrome 已清理，无遗留；锁交给 306164（W18 materials-quick，node 309179/app 309190）。W19 已收到通知，按 W18→W19→W20→W21 顺序入队。
- 11:18 W18 释放锁：post-fix 段因 CDP viewport 800×600 隐藏会话列表而失败，未新增截图；材料收口转 W25。锁交给 309443（W19 visual-board `run-all.sh`，app 312760/Chrome 313133），W20 313744 为下一 waiter；W21 排 W20 之后。最终 bundle hash 仍待 W17 重建，当前仅临时 4652ef26。
- 11:19:17 W19 释放锁（无 app/Chrome 遗留）；锁交给 313744（W20 visual-ops `segment-ops-final.js`），waiter 314306（W25/materials-w25.cjs）。W25 晚于 W19/W20 入队，实际顺序为 W19→W20→W25→W21；W21 已通知排在 314306 之后。W15 不杀进程、不重建。
- 用户接受实际顺序 W19→W20→W25→W21；每段 ≤5 分钟，跑完立即释放。W20 当前 holder（app 314453/Chrome 315141），W25 314306、W21 315315 等待；W15 只监控，不杀进程。
- 11:21:56 W20 自行退出（未杀）：段内最后截图 `f-09-schedule-form-1280.png` 11:20:38；W20 报告 node 在 360 诊断定位处卡住约 2:40，主动停止让路，保留 11 张 1280 截图并绑定当时 bundle dd968793（现为前冻结，最终 hash 见 §1.9 的 ea9e5732）。持锁约 3:09，在 5 分钟内；app 314453/Chrome 315141 已清理。锁交给 314306（W25 materials-w25.cjs），W21 315315 为下一 waiter，其后 W16 318107、W19 318254、W21b 321687。
- 11:23 W5 误报 318254 为 holder；复核 `/proc/locks` 第一行是 314306（W25）持有，318254 只是第 4 个 waiter。`fuser -v` 会列出所有打开锁文件的 flock（含等待者），不能据此判断 holder；以 `/proc/locks` 第一条无 `->` 的记录为准。当前队列 W25→W21→W16→W19→W21b，W25 约 2:49，未超 5 分钟，无 kill。
- 11:23:46 再次复核：318254 已不在进程表（无 http.server/test-fixture/Chrome 子进程），无需终止；当前 holder 仍是 314306（W25），队列 W21→W16→W19→W21b。W5 已确认以 `/proc/locks` 为准，E2E09 暂不入队。
- 12:39 E2E05 holder 422999 已 35:23、app 455494 23:36/CPU 19:08/RSS 1.54GB；`/tmp/hc-e2e05/proxy.jsonl` 12:39:30 仍在更新、Chrome 活跃，属“长但仍有进展”，不是卡死，未 kill。W10 的 E2E01 flock 已超时退出；队列 8 个（422999→W2→W22→E2E09→W21b→…）。
- 12:49:01 E2E05 自行释放（结果 `e2e05.json` 为 TypeError：digest 读取 undefined.length，不是 PASS）。随后 W2 448031 短暂取得后退出，W25 524180 取得并创建 `chrome-w25b`（12:49）后退出；12:50 当前 holder 为 W10 531484（E2E01，已起 app/Chrome），W22/E2E09 不在队列。W25 段不足 5 分钟；已通知 W25/Main，W15 未杀进程。
- 13:00 W25 已撤下阻塞 waiter，等“锁完全空闲且无阻塞 waiter”；当前 holder 639420（W3 E2E05，6:39，java 641960 活跃），队列 E2E09 644293→646518→W10 647936→W22 655463，队列持续非空。已建议 Main 给 W25 保留槽，否则材料段标“跳过/前冻结”；W15 只监控。
- 13:02:32 W10 释放后 W25 663796 拿到锁并启动 node 666189；13:04:22 释放，持续约 **1m50s**，`/tmp/hc-visual-materials` 13:02 后无新证据/日志，未达 5 分钟。按裁决记“跳过/前冻结（无证据）”。当前 holder 为 E2E09 679599；W35 全量测试 674293 运行中（1:56）。W15 未杀进程、未写 src/test。
- 14:46 W3 E2E05 holder 990667 已 33:01，app 990671 CPU 1:45/RSS 1.29GB；`/tmp/hc-w3e5r4/proxy.jsonl` 14:45 仍更新、Chrome 1183929 活跃，属“长但仍有进展”，未 kill。队列 6 个：W2 1077717→E2E09 1077839→W22 1087356→W5 1092149→1092754；已通知 W3 确认时长/收尾。
- 14:51:03 W3/W36 E2E05 holder 990667/app 990671/chrome05 由 W36 主动停止并释放（未杀）；W27 认领并主动撤下 1077717（E2E08 run08r，未拿锁、无 app/Chrome）。E2E09 1077839 接锁并启动 run.sh/app 1273389；W22 1087356、W5 1092149 仍等待。1077717 未按无主终止。
- 14:56 W22 已释放、W5 已撤 1092149；但 fuser 仍显示 E2E09 run.sh/app/driver（1279994/1280002/1280443，probe2 1279126 子树）在跑，W36 config06 1274321 仍等待，前有 1092754（E2E05 脚本）。W36 10 分钟窗口尚未满足；已请 Main 明确清场，W15 不杀进程。
- 14:57:19–34 按用户授权终止 1092754（W34 遗留 waiter，无 owner/子进程）与 W5 E2E09 组 1279126/1279994/1279997/1280002/1280443 + driver Chrome；W36 也终止了 1092754，W5 也终止了自己的组（重复操作，均无残留）。锁现由 W36 1274321 持有，config06 app 1282060 已启动；10 分钟窗口 14:57:34 起算。W22/W5 暂停，W36 结束释放后通知 W22。

## 2. 根因分类

| 类别 | 证据 | 判定 |
|---|---|---|
| 系统 | 旧 `memfuckilld` sysrq + 51 GiB anon cap；内核全局 OOM | 直接触发器，不是根因 |
| JVM | 15–16 个 JVM 无 `-Xmx`；hs_err 9/9 `MaxHeapSize=16.7 GiB`；合计 46–47 GiB | **主因** |
| 测试并行 | 全量测试 + 多个 app-check/E2E + 各工人手工 JVM 同时运行；`restart-smoke-test` 串行 spawn 5 个子 JVM，每个默认堆 | **主因/放大器** |
| Datalevin | LMDB `mapsize` 默认 128 MiB，是虚拟地址预留，不是 RSS；关闭顺序修复已生效 | 不是本次 OOM 源 |
| Chrome | 36–58 个进程，单进程 VSZ 约 1.5 TiB（V8 sandbox 预留），RSS 合计 4–7 GiB；`oom_score_adj=300` | 受害者/放大器 |
| 代码无界 | Owner mailbox 默认无界；diagnostics 明细日志只按时间保留、无条数上限 | 真实风险，已修 |

## 3. 热点清单

### 3.1 已修复

| # | 文件/位置 | 问题 | 修复 |
|---:|---|---|---|
| 1 | `deps.edn` 的 `:dev`/`:browser-test`/`:test`/`:server`/`:cljs`/`:shadow` | 所有程序 JVM 无 `-Xmx`，默认上限为物理内存 1/4（16.7 GiB） | 统一 `-Xmx1g -XX:MaxDirectMemorySize=512m -XX:+ExitOnOutOfMemoryError` |
| 2 | `test/hc/test_jvm.clj`（新增）；`test/hc/server/app/restart_smoke_test.clj`、`test/hc/server/recovery/restart_smoke_test.clj`、`test/hc/server/storage/disk_smoke_test.clj`、`test/hc/server/extensions/support.clj` | 测试用 `shell/sh` 直接 spawn `java -cp ...`，子 JVM 无 `-Xmx`；restart-smoke 有 5 个场景 | 共享 helper 给所有子 JVM `-Xmx512m -XX:MaxDirectMemorySize=256m -XX:+ExitOnOutOfMemoryError -XX:TieredStopAtLevel=1`；场景保持串行，不减少覆盖 |
| 3 | `src/hc/compose/owner/mailbox.clj` | 默认 `LinkedBlockingQueue` 无界；handler 卡住时 provider 回调/命令可无限堆积 | 默认 `default-capacity 4096`，满时返回 `:MAILBOX_FULL`；新增 `default-queue-is-bounded` 测试 |
| 4 | `src/hc/server/diagnostics/log.clj` | 明细日志只按 7 天保留，没有条数上限；定时捕获可持续增长 Owner state 与导出 | 新增 `max-entries 2000`，`expire`/`record` 都保留最新 2000 条；新增两个有界性测试 |
| 5 | `test/e2e/playwright.config.mjs` | Chrome 未限制共享内存/V8 堆 | 加 `--disable-dev-shm-usage`、`--js-flags=--max-old-space-size=512`；保留 `--no-sandbox`、`workers: 1` |
| 6 | `test/e2e/support/app.mjs` | E2E app 已用 1g/512m，但缺少 OOM 快速失败 | 加 `-J-XX:+ExitOnOutOfMemoryError`（当前文件由其他工人调为 768m，仍低于 1g cap） |
| 7 | `shadow-cljs.edn` | npm shadow-cljs 启动自己的 JVM，可能不继承 deps.edn 别名 | 顶层 `:jvm-opts ["-Xmx1g" "-XX:MaxDirectMemorySize=512m"]` |
| 8 | `test/e2e/support/app.mjs` | Playwright 超时后 `finally` 未执行，app JVM 成为 PPID 1 遗留进程 | 增加 detached watchdog：父 Node 消失后检查命令行并 TERM/KILL 遗留 app |
| 9 | `src/hc/server/storage/view.clj`、`src/hc/server/storage.clj`、`src/hc/server/blob_store/coordinator.clj` | `export-state` 为拿 blob refs 也复制所有文档/义务 | 新增 `:sections`；blob 清理只读 `#{:blob-refs}`；`export_sections_test` 覆盖 docs/obligations 为 nil |
| 10 | `src/hc/server/diagnostics/bundle.clj`、`src/hc/server/diagnostics/service.clj` | writer 路径先 `pr-str` 整个 bundle，再 `getBytes`，同时持有字符串和字节数组 | 新增 `write-local-artifact!`，按顶层键/每个 section 分块写 `OutputStream`；service writer 路径改用流式；bundle 测试验证 EDN 等价 |
| 11 | `src/hc/server/browser/cdp.clj` | Chrome 启动失败时 `slurp` 整个 `chrome.log` | 新增 `read-log-tail`，最多 64 KiB；`cdp_test` 验证截断与短文件 |
| 12 | `src/hc/client/conversation/attachments/storage.cljc` | `memory-cache` 按 session/client 无界累积 | LRU：20 项 / 64 MiB，`cache-stats` 可观测；新增 3 个 LRU 测试 |

### 3.2 已确认有界/无需改

- `src/hc/compose/owner/runtime.clj:70`：`:notifications` 已用 `take-last 64` 封顶。
- `src/hc/server/app/system.clj` 的 publisher：`previous-calls`、`previous-message-lengths` 每轮 `prune-to-live`，只保留 live session；快照本身是当前状态投影，不是历史累积。
- `src/hc/client/workbench/extensions_bridge.cljs:155,184`：`seen-receipts`/`queried-receipts` 在同步/重连时清空，单连接内有界。
- `src/hc/client/speech/playback.cljs`、`sync_bridge.cljs`：订阅函数返回 unsubscribe，`ensure-event-listener!` 按 key 去重；未发现无界注册。
- `src/hc/server/storage/engine/datalevin.clj`：`mapsize` 128 MiB 是 LMDB 虚拟地址预留，不是匿名 RSS；关闭顺序与 shutdown hook 修复未改。

### 3.3 剩余热点（未改，记录风险）

| 文件/位置 | 原因 | 影响 | 建议 |
|---|---|---|---|
| `src/hc/server/storage.clj:666`、`src/hc/server/storage/view.clj:237` | `export-state` 默认仍导出所有分区文档/义务/blob-ref；只有 blob 清理改成 refs-only | 备份/诊断导出仍与 store 大小成正比；维护路径，不在 500 ms 热路径 | 备份若需低峰值，下一步做分区流式导出；当前先保证不并发跑备份与全量测试 |
| `src/hc/server/diagnostics/bundle.clj:461` | `local-artifact`（无 writer 的返回路径）仍 `pr-str` 整串给客户端 | writer 路径已流式；无 writer 路径受客户端协议约束仍需字符串 | 若客户端改为分块下载，再移除最后一份整串 |
| `src/hc/client/conversation/attachments/storage.cljc:19` | LRU 只淘汰内存缓存；localStorage 仍是持久副本 | 未落盘且 localStorage 不可用时，淘汰会丢内存队列 | 已保留 `clear-queue!` 与 localStorage 兜底；后续可给未持久化项加保护 |
| `src/hc/server/app/system.clj:2297` | publisher 两层 baseline-cache：第一层 key = session `[id revision]` + owner version + active + command epoch + `:models` hash + `:materials/revision`；第二层 per-session conversation-view/history/summary-row 子块，按 revision/epoch 复用并每 tick 剪掉不可见 session。`publish-state!` 无变化跳过 diff、≤16 KiB delta 跳过完整序列化、known-size 复用 event-bytes | 第一层 100/1000 sessions 全量 31.99/48.61 MB、195.78/487.56 MB；第二层单 session 变化 1000 sessions 82.74 ms/224.55 MB（无缓存 211.96/580.56），100 sessions 11.71/22.88（无缓存 32.32/57.89）；全命中 12.43/56.00 ms、12.69/125.85 MB；下一热点 `component-snapshot` 与全局消息表合并 | **W11 负责，W15 不改**；W15 的 app-check/真实 app 复测在第一层后，见 §1.8 |

### 3.4 其他工人的审查结论（交叉引用）

| 工人 | 领域 | 结论 |
|---|---|---|
| W1 | settings.clj | 只有有界 config/document map 与单 atom；无只增缓存/事件日志/大字符串/全量 slurp |
| W3 | history / speech | history append 只保留回答正文，400×200KB chunk 实测堆增量约 0.001 MB；`plans/memory-audit-w3.md`。W14 另报告 speech playback 未清 `audio.src/load`、synthesize 无 AbortController（在途 TTS 可缓冲到 50 MiB），归 W3，W15 未代改 |
| W9 | extensions | 命令账本保留 100 条（pending 不修剪）；`:resolved-skills` 只留 id/name/version/blockers；outbound skill summary 只取 id/name/version；`seen-receipts` 随命令增长但命令已限 100 |
| W10 | E2E mock | mock 请求 200 条、单 body 1 MiB、单服务总字节 16 MiB；900 请求 benchmark heapDelta≈20.8 MiB、RSSDelta≈72 MiB |
| W13 | 测试基础设施 | `hc.test-runner` 无缓存/atom 累积；restart-smoke 子 JVM 逐个退出；建议门禁打开 `-Dhc.test.assert-no-leaked-stores=true` |
| W14 | E2E evidence | 本轮只改 plans/evidence；body 截断 4000 字符，证据 JSON <10 KiB；无新增无界结构 |
| W11 | publisher | 已 stable：`publish-state!` 无变化跳过 diff、≤16 KiB delta 跳过完整序列化、known-size 复用 event-bytes；`previous-calls`/`previous-messages` 每 tick prune-to-live；两层 baseline-cache（第一层整体 baseline，第二层 per-session conversation-view/history/summary-row）单 session 变化 1000 sessions 82.74 ms/224.55 MB（无缓存 211.96/580.56），全命中 12.43/56.00 ms；下一热点 `component-snapshot` 与全局消息表合并。W15 的 app-check 复测在第一层后，见 §1.8 |

## 4. 修复文件清单

- `deps.edn`：六个程序别名统一 JVM cap。
- `test/hc/test_jvm.clj`：子 JVM 统一 cap + `clojure.main` 启动 helper。
- `test/hc/server/app/restart_smoke_test.clj`、`test/hc/server/recovery/restart_smoke_test.clj`、`test/hc/server/storage/disk_smoke_test.clj`、`test/hc/server/extensions/support.clj`：改用 helper。
- `src/hc/compose/owner/mailbox.clj`、`test/hc/compose/owner/mailbox_test.clj`：默认队列有界 + 测试。
- `src/hc/server/diagnostics/log.clj`、`test/hc/server/diagnostics/log_test.clj`：明细日志条数有界 + 测试。
- `test/e2e/playwright.config.mjs`、`test/e2e/support/app.mjs`：Chrome/V8 与 app JVM 上限。
- `test/e2e/support/app.mjs`：E2E app 孤儿 watchdog。
- `shadow-cljs.edn`：编译器 JVM 上限。
- `src/hc/server/storage/view.clj`、`src/hc/server/storage.clj`、`src/hc/server/blob_store/coordinator.clj`：`export-state :sections` + refs-only blob 清理。
- `src/hc/server/diagnostics/bundle.clj`、`src/hc/server/diagnostics/service.clj`：`write-local-artifact!` 分块 writer 路径。
- `src/hc/server/browser/cdp.clj`：`read-log-tail` 64 KiB。
- `src/hc/client/conversation/attachments/storage.cljc`：附件缓存 LRU 20 项/64 MiB。
- 测试：`export_sections_test.clj`、`export_benchmark_test.clj`、`bundle_test.clj`、`cdp_test.clj`、`attachments_storage_test.clj`。

## 5. 复检命令

```bash
cd /home/kunweiz/Desktop/vibe/Herdchestra

# 1) 确认 app JVM 实际参数
.pixi/envs/default/bin/clojure -M:server -e \
  '(println (filter #(re-find #"Xmx|MaxDirect|ExitOnOutOfMemory" %)
                    (.getInputArguments (java.lang.management.ManagementFactory/getRuntimeMXBean))))'

# 2) 确认 test JVM 实际参数（运行任意测试时另开终端）
jcmd <pid> VM.flags | tr ' ' '\n' | grep -E 'MaxHeapSize|MaxDirectMemory'

# 3) 堆/类直方图
jcmd <pid> GC.heap_info
jcmd <pid> GC.class_histogram | head -30

# 4) 测试子 JVM
.pixi/envs/default/bin/clojure -M:test:compose:server:client \
  -n hc.server.app.restart-smoke-test

# 5) 有界性单测
.pixi/envs/default/bin/clojure -M:test:compose:server:client \
  -n hc.compose.owner.mailbox-test \
  -n hc.server.diagnostics.log-test

# 6) app-check（串行，结束后检查 free/hs_err）
.pixi/envs/default/bin/clojure -M:server -m hc.server.app --check --port 0

# 7) 一次短 E2E（串行；结束立即停 app/Chrome）
NODE_PATH=node_modules npx playwright test \
  test/e2e/e2e-01-empty-install.spec.mjs \
  --config test/e2e/playwright.config.mjs

# 7.1) JS 语法检查
node --check test/e2e/support/app.mjs
node --check test/e2e/playwright.config.mjs

# 7.2) 四项热点测试
.pixi/envs/default/bin/clojure -M:test:compose:server:client \
  -n hc.server.storage.export-sections-test \
  -n hc.server.diagnostics.bundle-test \
  -n hc.server.browser.cdp-test \
  -n hc.client.conversation.attachments-storage-test

# 7.3) 1000 会话 export 基准（默认跳过）
JAVA_TOOL_OPTIONS='-Dhc.bench.export=true' \
  .pixi/envs/default/bin/clojure -M:test:compose:server:client \
  -n hc.server.storage.export-benchmark-test

# 8) 全量测试（稳定树、串行、无其他 app/E2E）
.pixi/envs/default/bin/clojure -X:test:compose:server:client

# 9) 资源前后
free -h
ps -eo pid,rss,etime,args --sort=-rss | head -20
ls hs_err_pid*.log 2>/dev/null | wc -l
```

## 6. 剩余风险

1. **1 GiB 是安全上限，不是性能目标**：极大 session、全量测试中的大 namespace、PDF/OCR 可能接近上限；出现 `OutOfMemoryError` 时先记录 `jcmd GC.heap_info`，再按证据调到 1.5–2 GiB，不要回到默认 15.6 GiB。
2. **环境变量可覆盖**：`JAVA_TOOL_OPTIONS`/`JAVA_OPTS`/`JDK_JAVA_OPTIONS` 仍可能被外部设置为更大堆；命令行 `-Xmx` 在 `JAVA_TOOL_OPTIONS` 之后生效，但 `JAVA_OPTS` 的顺序取决于启动方式。门禁前用 `jcmd VM.flags` 抽查。
3. **并发仍可叠加**：16 个 1 GiB JVM 仍约 16–24 GiB。必须保持“同一时刻最多 1 个重资源 app JVM + 1 个 Chrome；全量测试与 E2E 串行”，不能只依赖 cap。
4. **外部 OOM 守门**：`memfuckilld` 新版本 `anon_cap=41,485 MiB` 仍会在整机 anon 过高时杀进程；应用侧 cap 降低触发概率，但不能替代串行。
5. **Datalevin `mapsize` 128 MiB**：这是虚拟地址预留，不是 RSS；但数据增长到 map 上限会出现 `MDB_MAP_FULL`，与 OOM 是不同故障，需要单独监控。
6. **全量拷贝**：blob 清理已改为 refs-only；备份和无 writer 的诊断返回路径仍可能构建完整 `export-state`/字符串。不要与全量测试或 E2E 并行。
7. **客户端附件缓存**：已加 LRU（20 项/64 MiB）；localStorage 是持久副本，若 localStorage 不可用且队列未落盘，淘汰会丢内存队列。后续可给未持久化项加保护。
8. **Chrome V8 512 MiB**：极重页面可能触发 renderer OOM；E2E 若出现页面崩溃，先确认是否该 cap，再决定是否提高，不能直接去掉。
9. **全局锁队列**：`/tmp/hc-app-single.lock` 只包有限 app+Chrome 段；当前视觉/E2E 等待队列仍可能让 E2E09 饿死。holder 必须 trap 清理子进程，等待者用有界 `-w`，非必要 audit 不要排队。
10. **publisher 下一热点**：W11 baseline-cache 已命中复用；`component-snapshot` 仍每 500 ms 构造 materials/diagnostics/scheduler 全量，归 W11。
11. **diagnostics 流式权衡**：writer 路径峰值临时字符串从 2.38 MB 降到一个 section，但总 alloc 从 30.9 MiB 升到 38.5 MiB；若后续更在意总分配，可再合并 section 写入或复用 byte buffer。
