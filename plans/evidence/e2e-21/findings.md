# E2E-21 续轮不收敛定位（w172-e2e21-converge）

## 根因归属：① mock/剧本缺陷（非产品收尾缺陷）

证据链（`e2e21-result.json` + trace + `/tmp/hc-e2e-*`）：

- turn1 首个 priced 请求是非 nudge 的 `open-user-turn` 调用，原 mock 只回
  `devops` tool_call、无正文 → 该 run 无可见 assistant 文本。
- 之后每次续轮都是 `settle-turn` → `nudge/decide` 的 nudge 调用（请求体含
  ledger 提醒 system 消息）。nudge 回复被标 `:nudge?`/`：nudge-id`，在
  `snapshot.clj`、`conversation-view`、`history` 三处投影一致过滤 → 用户
  永远看不到「E2E21 priced reply #N」。
- mock 永远只回纯文本、从不走 `clj-plan` 程序路径，ledger 永不收敛；产品按
  §2.5/D-13 设计打到 `:round-boundary`（默认 8 轮）后转空闲。这是无人值守
  协议的预期行为，不是收尾缺陷。
- e2e-02/04 能过是因为它们 mock 首轮直接回文本（非 nudge 调用），可见回复
  来自第一个调用；e2e-21 首轮是 tool_call，唯一可见出口没有文本。

## 已落地改动

1. `test/e2e/e2e-21.spec.mjs`
   - mock 首个 priced 响应同时携带正文文本与 devops tool_call（content +
     tool_calls 同帧，真实 provider 允许）。
   - `prompt_tokens_details` 补 `cache_write_tokens: 0`：否则
     `request-class` 因任一未知 token 字段把整个请求归为 unknown，
     「服务返回」计数恒为 0。
   - `sendTurn` 不再用 `ensureIdle`（会在 turnLive 时点「停止」）。改为等
     assistant body 数增加 → `waitTurnSettled`（无活跃指示 + mock 请求数
     稳定）。
   - `refreshStats(page, waitText)`：等刷新后的统计真正带上目标币种，
     避免读到价格保存前的缓存快照。
   - 计数正则改 `\d+`（原 `\S+` 把句号「。」粘进数字 → NaN）。
   - `searchUsed` 判定修正（`requests` 是数字不是数组）。
   - 断言前三个 send 前 `selectFirstSession` 打开会话再读 `userTexts`。
   - `nudgeRoundBudget: 2`。
2. `test/e2e/support/app.mjs`：透传 `:nudge-round-budget`。
3. `test/e2e/support/ui.mjs`：`assistantBodies` 加 `:has(*)`，排除
   `:tool-call` 伪消息的空 `.hc-message-body`。
4. `src/hc/server/model_port/usage.clj`：新增 `flat-usage`（report →
   扁平 `:input-tokens`/`:output-tokens`/`:source`，report 保留）。
5. `src/hc/server/history/message.clj`：`normalize-version` 应用
   `flat-usage`（读取侧归一化）。

注：usage 写入侧的丢失（version `:usage` 为 nil）由 w175 在
`handlers.clj` mirror 处修复（把 completed event 的 `:usage` report 写入
history）；本工人的 `flat-usage` 负责把 report 投影成客户端
`usage-label` 读取的扁平形状，两者互补，缺一不可。

## 测试结果

- 第 8/9 次运行（默认 outputDir）：19 项 check 全过、`ok: true`，但
  Playwright 收尾报 `browserContext.close: ENOENT ...trace`——共享
  `playwright-artifacts/.playwright-artifacts-0` 临时目录被并发 e2e
  清理的基础设施 flake，与断言无关。
- 第 10 次运行（`--output /tmp/pw-e2e21-artifacts` 私有目录）：
  **`1 passed`，干净 PASS**（6.5m）。`e2e21-result.json` 记录
  `ok: true`，19/19 checks 全过：
  turn1Replied / searchUsed / toolRoundTrip / messageUsageLabel /
  hasSourceCounts / reportedNonZero / unknownNonZero / noEstimateBefore /
  unknownNotZeroText / usdEstimated / unknownModelUnestimated /
  coverageLine / usdAndEurSeparated / noCrossCurrencyTotal / priceNote /
  requestCountMatchesMock / requestProvenanceLabels / perRequestCurrencies /
  oldPriceSnapshotKept / sendsRecorded。
