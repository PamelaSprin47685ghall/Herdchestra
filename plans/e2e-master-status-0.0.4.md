# E2E 01–22 状态台账（0.0.4）

- 日期：2026-11-12
- **终局 bundle**：`resources/public/js/main.js` sha256
  `9739f28723c4295ec725adaca0db196245f6d1d66b07f821dd14bf571eba986c`，
  **3,154,519 B**，前缀 `9739f287`。
- **提交**：`15f6522`（`release: 0.0.4`）。
- **总判定：PASS 21 / SKIPPED 1（按 22 个 spec）。**
  E2E 01 有 first journey 与 OAuth journey 两条证据行，均为 PASS，故下表
  共有 **22 行 PASS 证据 + 1 行 SKIP**，对应 22 个 spec。
- **跳过项**：E2E 18（磁盘满注入）在 macOS 上无 `unshare -Ur -m`，记
  `blockedReason: "disk-full injection needs Linux; running on darwin"`。
  这是平台限制，不是产品失败。上一次在 Linux 上的 PASS 见
  `plans/e2e-master-status-0.0.3.md`（39/39）。
- **运行方式**：`npx playwright test --config test/e2e/playwright.config.mjs --workers=1`，
  单进程串行，真实浏览器 + 真实 JVM + mock 模型/外部服务。
  Playwright 汇总：`25 passed / 0 failed / 1 skipped`，墙钟 20.6 分钟。
- 所有 E2E 均在 `TMPDIR=/var/tmp/hc-e2e` 下运行；`/tmp` 为 tmpfs，
  写满会产生假「连接中断」（见 `HACK.md` §17.24）。
- **并发约束**：两个 Playwright 进程同时写
  `plans/evidence/e2e-auto/playwright-artifacts` 会互删 trace，产生
  `ENOENT ... recording5-pwnetcopy-1.network` 假失败。需并行时必须用
  `HC_PW_SHARD=<name>` 隔离 `outputDir`。
- 状态定义：`PASS` = 在本轮 bundle 上 `ok=true` 且所有 checks true；
  未实测项不写 PASS。

## 1. 状态表

| E2E | spec 文件 | tag | 证据 JSON | 状态 | checks / 备注 |
|---:|---|---|---|---|---|
| 01 | `test/e2e/e2e-01-empty-install.spec.mjs` | `@core` | `plans/evidence/e2e-auto/e2e01/e2e01-result.json` | `PASS` | first journey；all true |
| 01 OAuth | `test/e2e/e2e-01-empty-install.spec.mjs` | `@core` | `plans/evidence/e2e-auto/e2e01-oauth/e2e01-oauth-result.json` | `PASS` | OAuth journey；all true |
| 02 | `test/e2e/e2e-02.spec.mjs` | `@core` | `plans/evidence/e2e-02/e2e02-result.json` | `PASS` | all true |
| 03 | `test/e2e/e2e-03-streaming-draft.spec.mjs` | `@core` | `plans/evidence/e2e-auto/e2e03/e2e03-result.json` | `PASS` | all true |
| 04 | `test/e2e/e2e-04.spec.mjs` | `@core` | `plans/evidence/e2e-04/e2e04-result.json` | `PASS` | all true |
| 05 | `test/e2e/e2e-05.spec.mjs` | `@core` | `plans/evidence/e2e-05/e2e05-result.json` | `PASS` | all true |
| 06 | `test/e2e/e2e-06.spec.mjs` | `@core` | `plans/evidence/e2e-06/e2e06-result.json` | `PASS` | all true |
| 07 | `test/e2e/e2e-07.spec.mjs` | `@core` | `plans/evidence/e2e-07/e2e07-result.json` | `PASS` | all true |
| 08 | `test/e2e/e2e-08.spec.mjs` | `@core` | `plans/evidence/e2e-07-08/e2e08-9739f287-377bb599-result.json` | `PASS` | all true |
| 09 | `test/e2e/e2e-09-protected-commit.spec.mjs` | `@core` | `plans/evidence/e2e-auto/e2e09/e2e09-result.json` | `PASS` | all true |
| 10 | `test/e2e/e2e-10-tool-interrupt.spec.mjs` | `@core` | `plans/evidence/e2e-10/e2e-10-result.json` | `PASS` | all true；46.9s |
| 11 | `test/e2e/e2e-11.spec.mjs` | `@e2e` | `plans/evidence/e2e-11/e2e-11-result.json` | `PASS` | **31/31**；本版第 6 处修复的直接验收点 |
| 12 | `test/e2e/e2e-12.spec.mjs` | `@core` | `plans/evidence/e2e-12/e2e12-result.json` | `PASS` | **67/67** |
| 13 | `test/e2e/e2e-13.spec.mjs` | `@e2e` | `plans/evidence/e2e-13/e2e-13-result.json` | `PASS` | 25/25 |
| 14 | `test/e2e/e2e-14.spec.mjs` | `@e2e` | `plans/evidence/e2e-14/e2e-14.json` | `PASS` | all true |
| 15 | `test/e2e/e2e-15.spec.mjs` | `@core` | `plans/evidence/e2e-15/e2e-15.json` | `PASS` | all true |
| 16 | `test/e2e/e2e-16.spec.mjs` | `@e2e` | `plans/evidence/e2e-16/e2e-16.json` | `PASS` | all true |
| 17 | `test/e2e/e2e-17.spec.mjs` | `@e2e` | WebDAV `plans/evidence/e2e-17/e2e-17.json`；S3 `plans/evidence/e2e-17/e2e-17-s3.json` | `PASS` | WebDAV 17/17 + S3 13/13 |
| 18 | `test/e2e/e2e-18-disk-full.spec.mjs` | `@core @disk` | `plans/evidence/e2e-auto/e2e18/e2e18-result.json` | `SKIP` | macOS 无 `unshare -Ur -m`；平台限制 |
| 19 | `test/e2e/e2e-19-ime-a11y.spec.mjs` | `@core @a11y` | `plans/evidence/e2e-auto/e2e19/e2e19-result.json` | `PASS` | all true |
| 20 | `test/e2e/e2e-20.spec.mjs` | `@core` | `plans/evidence/e2e-20/e2e20-result.json` | `PASS` | **20/20** |
| 21 | `test/e2e/e2e-21.spec.mjs` | `@core` | `plans/evidence/e2e-21/e2e21-result.json` | `PASS` | all true |
| 22 | `test/e2e/e2e-22.spec.mjs` | `@e2e` | `plans/evidence/e2e-22/e2e-22.json` | `PASS` | all true |
| mock | `test/e2e/mock-services.spec.mjs` | `@mock` | `plans/evidence/e2e-auto/mock-services/mock-services-result.json` | `PASS` | 26/26；OAuth/WebDAV/S3/search 端点 |
| memory | `test/e2e/memory.spec.mjs` | `@memory` | `plans/evidence/e2e-auto/memory/memory-result.json` | `PASS` | 9/9；mock 请求与 body 有界 |

## 2. 与 0.0.3 的差异

| 项 | 0.0.3 | 0.0.4 |
|---|---|---|
| 判定标准 | 四条主链路手工可跑 | 22 条 E2E 全绿 + 全量 JVM 干净 |
| E2E 18 | `PASS 39/39`（Linux） | `SKIP`（macOS 无 mount 能力） |
| E2E 11 | `PASS 31/31`，靠 1500ms 轮询兜底 | `PASS 31/31`，产品侧明确重对齐 |
| 全量 JVM | 4501 / 33056 | **4502 / 33065** |

E2E 11 的差别不是数字而是机制：0.0.3 时面板靠客户端轮询「碰巧」恢复；
0.0.4 在产品侧修好了三条叠加缺陷（见
`plans/release-notes-0.0.4.md` 第 6 条），并给静默丢弃留下
`:last-rejected` 诊断出口。

## 3. 本版新增的回归测试

| 测试 | 固定住什么 |
|---|---|
| `materials-component-cache-keys-on-the-session-too`（`hc.server.app.system-test`） | 同 revision 不同会话必须重建快照 |
| `cross-session-snapshot-is-ignored`（扩充断言，`hc.client.materials.model-test`） | 丢弃时必须留下理由与两个会话 id |
| `a-stale-snapshot-is-recorded-as-stale`（新增） | 更旧 revision 被拒，且写明 `:stale` |
| `startup-identity-is-observable`（改为从 `version/version` 派生） | 版本号不再钉死，发布不再需要改测试 |

## 4. 复现命令

```bash
cd /Users/Licw/Desktop/git/Herdchestra

# 全量 E2E（单进程）
PATH=/private/tmp/hc-e2e-venv/bin:$PATH \
  CHROME_PATH="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" \
  TMPDIR=/var/tmp/hc-e2e \
  npx playwright test --config test/e2e/playwright.config.mjs --workers=1

# 全量 JVM
./.pixi/envs/default/bin/clojure -X:test:compose:server:client
```