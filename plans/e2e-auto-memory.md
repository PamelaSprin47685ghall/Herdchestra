# E2E 工装内存审查（0.0.1）

- 日期：2026-11-06
- 范围：`test/e2e/` 自动化工装与 mock 服务；不包含产品 JVM 的内部缓存。
- 背景：机器发生 OOM 全杀；本报告记录本领域的内存热点、已做限制与前后测量。

## 1. 进程与资源限制

- 当前 E2E 不持有 app/test JVM、Chrome 或 mock 进程；最后一次 E2E03 结束后实例已关闭。
- 所有后续 `startApp` 启动的 JVM 带 `-J-Xmx1g -J-XX:MaxDirectMemorySize=512m`。
- E2E09 的 Clojure driver 带 `-J-Xmx512m -J-XX:MaxDirectMemorySize=256m`。
- Chrome 启动参数增加 `--disable-dev-shm-usage`；Playwright `workers: 1`，不并行。
- 同一时刻最多 1 个 app JVM + 1 个 Chrome；E2E 与全量测试串行，不并发。

## 2. 热点与修复

| 文件 | 热点 | 风险 | 修复 |
|---|---|---|---|
| `test/e2e/support/mock-model.mjs` | 流式回复先构造完整 `chunks` 数组，每个请求复制整段文本 | 长回复按请求数放大 | 改为按 `cursor` 切片，不再保留 chunk 数组 |
| `test/e2e/support/mock-model.mjs` | `state.requests` 只增不减 | 长时间/高频请求无界 | 只保留最近 200 条；`chatCompletions` 独立计数 |
| `test/e2e/support/mock-model.mjs` | `readBody` 无上限拼接请求体 | 大请求体可占满堆 | 上限 1 MiB，超限返回 413 |
| `test/e2e/support/mock-services.mjs` | `state.requests` 无界 | 同上 | 最近 200 条 |
| `test/e2e/support/mock-services.mjs` | WebDAV/S3 请求体与对象无上限 | 8 MiB/对象 × 无界对象数 | 单请求 1 MiB；单服务总字节 16 MiB；对象/文件 200 个 |
| `test/e2e/support/app.mjs` | `waitForListening` 的 stdout/stderr 无界拼接 | 启动日志长时占堆 | 各保留最后 256 KiB |
| `test/e2e/support/app.mjs` | `logs` 无界拼接 | 同上 | 内存副本上限 2 MiB（文件仍写证据） |
| `test/e2e/e2e-09-protected-commit.spec.mjs` | driver stdout/stderr 无界 | 同上 | 各上限 1 MiB |
| `test/e2e/playwright.config.mjs` | Chrome 共享内存 | 容器/低内存机器崩溃 | `--disable-dev-shm-usage` |

## 3. 基准

`test/e2e/memory.spec.mjs`（`@memory`）启动 mock 模型与 mock 外部服务，各发 300 次请求，断言：

- mock 模型请求历史 ≤ 200，`chatCompletions` 仍为 300；
- WebDAV/S3 请求历史 ≤ 200，各只保留 1 个对象、1 KiB；
- 进程 `heapUsed` 增量 < 128 MiB；
- 结果写入 `plans/evidence/e2e-auto/memory/memory-result.json`。

运行：`npm run e2e:memory`。2026-11-06 实测（900 次请求：300 chat + 300 WebDAV PUT + 300 S3 PUT）：

- `heapUsed`：43,429,904 B → 64,236,888 B，增量 20,806,984 B（约 19.8 MiB）。
- `rss`：150,777,856 B → 222,801,920 B，增量 72,024,064 B（约 68.7 MiB）。
- 耗时 354 ms。
- mock 模型历史保留 200 条，`chatCompletions` 仍为 300；WebDAV/S3 各保留 200 条请求、1 个对象、1 KiB。
- 证据：`plans/evidence/e2e-auto/memory/memory-result.json`。

## 4. E2E01 页面关闭诊断（2026-11-06 11:11）

- 现象：E2E01 在 11:11:03 报 `page.waitForTimeout: Target page, context or browser has been closed`，截图停在 11:07 的 `e2e01-06-draft-restored`。
- 判定：Playwright test timeout（240s）后 teardown 关闭 page/context，测试里仍有挂起的 `waitForTimeout`，因此报“页面已关闭”。不是 Chrome renderer OOM，也不是 app JVM OOM；app/Chrome 随后已清理，无遗留。
- Chrome V8 512m 未参与：页面是简单工作台，截图与 DOM 快照完整；保留 `--disable-dev-shm-usage` 与 512m cap。
- 已修：E2E01 增加 `page.on('crash'/'close'/'pageerror'/'console')` 与 `browser.on('disconnected')` 日志（`plans/evidence/e2e-auto/e2e01/browser-events.log`）；发送后 15s 内必须出现 `.hc-message-out`，否则记录 notices/body 并 fail-fast；assistant 等待从 120s 缩到 60s；test timeout 420s。

## 5. 未处理 / 边界

- 证据截图与 Playwright trace 仍可能占磁盘（失败时保留 trace），不占进程堆；每次运行结束清理临时目录。
- 产品 JVM 的内部缓存（会话历史、快照、事件）不在本工装所有权内；若 OOM 来自产品侧，需要对应 owner 处理。
- 真实模型/真实浏览器 E2E 仍受宿主内存影响；本工装只保证自身不无界增长，并限制并发。
