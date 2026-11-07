# E2E 07 / 08 修复与真实复跑报告（2026-11-05，W2）

- 仓库：`/home/kunweiz/Desktop/vibe/Herdchestra`
- 实例：`w2-08`（data `/tmp/hc-w2/data-08`，端口 44569）、`w2-07`（data `/tmp/hc-w2/data-07`，端口 44831）
- 模型：本机 Ollama `qwen3.6:27b`（经 18082 代理，`reasoning-effort none`）
- 浏览器：Playwright + 真实 Chrome；证据目录 `plans/evidence/e2e-07-08/`
- 结论：**E2E 08 = PARTIAL**（S1 卡片真实出现；回答后未解除 park；Esc 本轮未跑到）。**E2E 07 = BLOCKED**（搜索链路未在真实模型运行中执行；本轮没有搜索请求）。不把组件测试当作 E2E PASS。

## 1 修复点（代码）

### 1.1 `:hc/ask` 从未到达浏览器（E2E 08 主阻塞）

两处确定性缺陷，都在 `src/hc/server/app/system.clj`：

1. **广播用了错误的网关**：`session-owner-factory` 里的局部绑定 `gateway` 是模型网关
   （`pair-hint/wrap-model-gateway`，`system.clj:1592`），不是 AG-UI 网关。
   `(gateway/broadcast! gateway ...)` 在模型网关上没有 `:feed`，抛错后被
   `catch Throwable _ nil` 吞掉，所以服务端 outbox 有 `:hc/ask`、浏览器 141 条
   CUSTOM 里却没有它。
   修复：新增 `forward-owner-event!`，每次从 `(:gateway @system-ref)` 解析真正的
   AG-UI 网关（`system.clj:3770` 写入），Owner 在网关创建前启动也不影响。
2. **事件名丢了命名空间**：`(name :hc/ask)` 得到 `"ask"`，客户端按完整
   `"hc/ask"` 分派，即使到达也只会显示“未处理的服务器事件”。
   修复：`owner-event-name` 保留命名空间（`:hc/ask` → `"hc/ask"`，
   `:worker/program-result` → `"worker/program-result"`）。

测试：`test/hc/server/app/owner_event_broadcast_test.clj`
- `parked-ask-reaches-the-ag-ui-feed`：真实 feed 订阅，`CUSTOM name="hc/ask"`，`ask-id/question/name/thread-id` 正确。
- `ask-resolved-and-worker-results-also-reach-the-feed`。
- `unrelated-effects-do-not-consume-the-feed`。
- `a-missing-gateway-is-not-an-error`（含模型网关形状不抛错）。
- `owner-outbox-reaches-the-browser-feed`：真实 Owner 种子 tool call（parked）→ outbox → `:on-effect!` → 真实 gateway → 订阅者收到事件。
- 结果：**5 tests / 22 assertions / 0 failures / 0 errors**。

### 1.2 拒绝回执带修正提示（E2E 07 devops scope）

`devops-tool-spec` 已含 `:scope`（required，与 `clj-plan` 同形）；准入拒绝已带
`:expected`（`reset-scope/keep-last/remove-last/me-too`、`new-scope-goal`、
`new-scope-retention-budget`、`replacement`）与 `:hint`。

测试：`hc.compose.nudge.owner-integration-test/admission-refusal-injects-one-nudge`
增补断言 `:expected`、`reset-scope`、`new-scope-goal`、
`new-scope-retention-budget`、`:hint` 都出现在模型可见的 tool 结果里。
结果：**33 tests / 231 assertions / 0 failures / 0 errors**。

### 1.3 搜索阶段状态从不更新（E2E 07 数据链路）

`search-model/record-run` 在客户端没有调用者，`RUN_FINISHED` 的
`:search-status` 只用于降级提示，搜索面板永远显示“尚未搜索”。

修复：`src/hc/client/workbench/events.cljc` 新增 `record-search-run`，在
`finish-run`/`error-run`/`cancelled-run` 把真实 `:search-status`（以及事件确实
携带的 `:search-context`/`:source-cards`/`:search-error`）写入
`[:ui/search-state session-id :last-run]`；没有搜索状态的事件不触碰面板。

测试：`hc.client.workbench.events-test`
- `run-finished-records-the-real-search-status`（`:ok`/`:skipped`/无状态三态）。
- `failed-search-run-keeps-the-source-list`（失败仍保留来源卡，`published-at`
  不被 `fetched-at` 替换的数据形状）。
- 结果：**42 tests / 160 assertions / 0 failures / 0 errors**。

## 2 E2E 08 真实 UI 结果（2026-11-06 10:3x，最终复跑）

实例 `hc-app-08j`（`/tmp/hc-w2/data-08i`，端口 42080，`/tmp/pixi` + `JAVA_TOOL_OPTIONS=-Xmx1g -XX:MaxDirectMemorySize=512m`），
真实 Chrome 153（Playwright 1.64.1，`--disable-dev-shm-usage`），Ollama `qwen3.6:27b`。
bundle：该轮实际使用 `resources/public/js/main.js` 2,966,442 bytes，
mtime 2026-11-06 10:11:04，sha256
`b895baa224f60f90cb9367347dba50fded04b9beb546618d4d6f1d5c65104ff6`。
此后冻结 hash 先后更新为 `a8acf565…`、`4652ef26…`，最终冻结为
`dd968793cc74919e139f31bb290a5dfb68309c6d9a78acfd766a4d984f2fda4b`
（2026-11-06 11:19:25，3,007,964 B）；E2E 08 证据**不是**在该冻结 hash 下取得，
报告不冒充。E2E 07 恢复时将以当时实际 main.js hash 记录。
证据：`plans/evidence/e2e-07-08/e2e08-*.png`；结果 `/tmp/hc-w2/e2e08j-result.json`。

| 断言 | 结果 | 证据 |
|---|---|---|
| 常授权模式不弹逐次工具审批窗 | PASS | 截图 |
| 页面加载/同步/模型绑定 | PASS（`●已同步`、`模型：qwen3.6:27b`、零 pageerror） | 结果 JSON |
| `Plan` 在 S1 调用 `.ask` 后真实 UI 出现 `.hc-ask` | **PASS**：卡片文本含“需要你回答 / E2E-08 请给出一个数字” | `e2e08-ask-card.png` |
| CUSTOM 事件名正确 | **PASS**：WebSocket 帧中 `hc/ask` 出现 2 次；`"name":"ask"` 0 次 | 结果 JSON `wire` |
| 填写并提交回答 | PASS（提交按钮 1 个，点击后卡片显示“已提交回答”） | `e2e08-answered.png` |
| 提交后服务端 unpark，同一 run 继续 | **PASS**：页面出现“正在生成 / 无人值守 · 自动继续”，助手文本包含 “I received the user's response \"42\"” | `e2e08-unparked.png`、结果 JSON |
| ask 后 composer 恢复可用 | **FAIL**：180 s 观察窗内发送按钮一直 disabled；继续中的 clj-plan 程序占用 composer（页面显示 “An active program already uses …”） | 结果 JSON `composerRecoveredAfterAsk=false` |
| Esc 取消整棵会话树并保留已生成内容 | **PARTIAL**：Esc 在“unpark 后的继续 run”上触发，页面显示“已停止”，内容保留、5 s 内无迟到追加；但未能先发送长文本再 Esc（composer 未恢复），因此 `escFirstChunk` 不适用 | `e2e08-esc-after.png`、结果 JSON |
| 非 S1 ask 拒 `:ASK_FORBIDDEN_OUTSIDE_S1` | 契约 PASS（`test/hc/server/program/capability_test.clj`） | 测试 |
| 普通个体 call 拒 `:TOKEN_REQUIRED` | 契约 PASS（同上） | 测试 |

### 2.1 unpark 失败根因（已修复）

- 服务端 `:hc/ask` 事件原先只带 `ask-id/name/question`，没有 tenure 身份；
  客户端 `ask/new-ask` 只挑选固定字段，`:lane`/`:tenure-id` 即使有也会被丢掉；
  `:unpark` 载荷因此只有 `{:ask-id :name :value}`。
- 实际处理 `:answer-ask` 的是 `handlers.clj` 的 `answer-ask`（不是
  `user-unpark`）。它按 `:lane`/`:tenure-id` 过滤 `awaiting-tenures`：
  无候选拒绝，候选多于一个 `:AMBIGUOUS_ASK`，恰好一个才 `mission/resume`。
- 修复：
  1. `guards.clj/parked-ask` 从 tool call 复制 `:lane`/`:tenure-id` 到 `:hc/ask`；
  2. `ask.cljc/new-ask` 保留这两个字段；
  3. `conversation.cljc` 的 submit/skip 载荷回传它们；
  4. `answer-ask` 对“显式指名但不在 parked 集合”的回答返回
     `:ASK_TENURE_MISMATCH`（附 parked 列表），多 parked 仍 `:AMBIGUOUS_ASK`，
     不静默选错；`:hc/ask-resolved` 回执带上 lane/tenure-id。
  5. 真实 UI 复跑发现程序 park 从未把 relay tenure 标成 `:awaiting-user?`
     （`relay/ask!` 只服务空 review 的 ask）。新增
     `hc.core.relay/mark-awaiting` + mission `:op :mark-awaiting`，由
     `project-tool-result-guard` 在投影 `:hc/ask` 的同一提交里标记 tenure；
     否则 `answer-ask` 永远找不到候选，回答必然“未送达”。
- 测试：`answer-ask-emits-one-resolved-event`、`answer-ask-infers-the-only-parked-tenure`、
  `answer-ask-rejects-a-stale-tenure`、`answer-ask-requires-an-explicit-tenure-when-several-are-parked`，
  以及 `parked-ask-projects-one-event` 新增的“park 标记 awaiting + 同一 tenure
  resume”断言（36 tests / 254 assertions，0/0）；客户端 `s1-ask-card-lifecycle`
  断言载荷携带 `:lane :plan` / `:tenure-id "tenure-1"`（49 tests / 512 assertions，0/0）。

### 2.2 Esc 未跑到的原因

ask 之后第二次发送（Esc 场景提示）时“发送消息”按钮一直 `disabled`，
`aria-disabled=true`。可能原因是 parked/awaiting 状态仍占住 composer 或
草稿未进入 reducer；这是同一个 unpark 缺陷的下游表现，不是 Esc 自身失败。
`e2e08-failed-run.json` 记录了完整超时日志。

## 3 E2E 07 真实复跑结果（2026-11-06 11:43，最终）

实例：`run07_locked.sh`（整段 `flock /tmp/hc-app-single.lock`），app
`JAVA_TOOL_OPTIONS=-Xmx768m -XX:MaxDirectMemorySize=512m`，端口 42081，
`/tmp/hc-w2/data-07j`；真实 Chrome 153；Ollama `qwen3.6:27b`；
bundle `55e4e8ea73279d58a0dbceba22ee8c815c2b24b2de600fd2e1f21e2eaa27c857`
（2026-11-06 11:50:01，3,008,937 B，最终冻结）。
搜索 stub `127.0.0.1:18095` + 文档站 `18096`，**非真实联网供应商**。
证据：`plans/evidence/e2e-07-08/e2e07-result-55e4e8ea.json`、`e2e07-panel.png`、
`e2e07-answer.png`、`e2e07-export.png`、`e2e07-offline.png`、
`search-stub-requests.log`。

| 断言 | 结果 | 证据 |
|---|---|---|
| 搜索模式 关闭/自动/本轮必须搜索 | **PASS** | `e2e07-panel.png`、结果 `modesPresent=true` |
| 选择搜索服务 `local-stub` | **PASS**（select value=`local-stub`，无 `.hc-search-error`） | 同上 |
| 本轮必须搜索模式 | **PASS**（`aria-pressed=true`） | 同上 |
| 搜索实际执行 | **PASS**：stub 收到 3 次 `W2E2E07` 查询 | `search-stub-requests-55e4e8ea.log` |
| 模型使用搜索上下文 | **PASS**：正文 “ALPHA-ONE 来自文档 Doc One [S1]” | `e2e07-answer.png` |
| 来源卡出现 | **FAIL**：`.hc-source-card` 数量 0 | 结果 `sourceCards=false` |
| 同一 URL 去重 | **FAIL**（无来源卡可核对） | 同上 |
| 未知发布日期不被抓取时间替代 | **FAIL**（无来源卡） | 同上 |
| 正文编号与来源列表对应 | **FAIL**：正文仍是 `[S1]`，未渲染为 `[1]`（`answerHasNumber=false`） | 同上 |
| 失效来源保留标题/地址 | **FAIL**（无来源卡） | 同上 |
| 导出纯文本含完整链接 | **FAIL**：导出面板选择器未命中（`.hc-header-action` 不存在于当前导航），未取得导出文本 | `e2e07-export.png`、`exportError` |
| 搜索阶段状态 | **PASS**：面板显示“已搜索”（`record-search-run` 生效） | 结果 `body` |
| 断网重看不发起隐式搜索 | **PASS**：离线前后 stub 计数均为 3 | 结果 `offlineNoImplicitSearch=true` |
| 断网重看保留来源卡 | **FAIL**（本来没有来源卡） | 同上 |
| 页面无异常 | PASS（pageErrors=[]） | 结果 JSON |

55e4e8ea 复跑（11:5x）与 dd968793 结论一致；`sendEnabled=true`、stub 3 次请求、
面板显示“已搜索”。根因（已定位，未修）：

1. Plan 在 S1 回答搜索问题后继续调用 `clj-plan`，工具因
   `:SCOPEFIELDINVALID "new-scope-retention-budget must be a positive integer"`
   失败；最终 turn 以工具失败收尾。搜索上下文注入成功，模型也输出了
   `[S1]`，但 `wrap-deliver!` 的 `apply-citations` 只在最终 `:completed`
   文本上运行，工具失败后该段答案没有进入带 `:citations` 的 assistant
   消息，因此来源卡与编号全部缺失。
2. `sendEnabled=false` 是采样时机误判：按钮在点击后立即变 disabled；
   用户消息已实际发送（页面“发送 2 条”）。
3. 导出入口在当前工作台导航中不是 `.hc-header-action`，步骤选择器未命中；
   这是工装问题，不是导出功能结论。

## 4 测试数字汇总

| 命令 | 结果 |
|---|---|
| `clojure -M:dev:server:test -n hc.compose.nudge.owner-integration-test -n hc.server.app.owner-event-broadcast-test` | 41 tests / 272 assertions / 0 failures / 0 errors |
| `clojure -M:dev:client:test -n hc.client.conversation-test -n hc.client.workbench.events-test` | 91 tests / 672 assertions / 0 failures / 0 errors |
| 其中 `owner-event-broadcast-test` | 5 tests / 22 assertions |
| 其中 `owner-integration-test` | 36 tests / 250 assertions |
| 其中 `conversation-test` | 49 tests / 512 assertions |
| 其中 `events-test` | 42 tests / 160 assertions |

## 5 构建与 bundle

- 15:24:59 用 `flock /tmp/hc-client-release.lock /tmp/pixi run client-release`
  串行重建（本次改动含 ask 载荷回传）：`resources/public/js/main.js`
  2,953,410 bytes，sha256
  `9c59f834a1dd49fb3fd085a711eac683f681a52f6e757ebcba5ecb5dd1aa19b6`。
- 15:02:43 另一次构建产物（2,953,410 bytes，sha256 `6466aede...`）未包含
  ask 载荷修复，已弃用。
- 14:47:24 的 bundle（2,939,270 bytes，sha256 `0038c945...`）只用于首轮
  `.hc-ask` 卡片取证；当时服务端广播修复已生效，卡片出现可归因于该修复。
- 构建日志有一条来自 `src/hc/client/workbench/browser_bridge.cljs:182`
  的 `Use of undeclared Var str/split` 警告（非本轮文件），未影响产物生成；
  记录待其所有者修复。

## 6 文件改动

- `src/hc/server/app/system.clj`：`owner-broadcast-types`、`owner-event-name`、
  `forward-owner-event!`；`report-bridge :forward-effect!` 改用该函数。
- `src/hc/client/workbench/events.cljc`：`record-search-run`，接入
  `finish-run`/`error-run`/`cancelled-run`。
- `test/hc/server/app/owner_event_broadcast_test.clj`：新增。
- `test/hc/compose/nudge/owner_integration_test.clj`：拒绝回执 expected/hint 断言。
- `test/hc/client/workbench/events_test.clj`：搜索运行状态两条测试。
- `plans/e2e-07-08-diagnosis.md`：逐跳诊断。

## 6.1 模型锁事件（2026-11-05 15:00–15:34）

- 15:00:56 W4 以 `flock -w 1800 /tmp/hc-e2e-model.lock bash -c exec clojure
  -M:server -m hc.server.app --config /tmp/hc-w4/config.edn` 包住一个**长驻
  app**，锁被占 30+ 分钟；另有 W8 的 3776075（15:08 起，无 node 子进程）、
  W7 的 3880496 在排队。
- 15:26–15:33 按裁决每 30s 检查，持续 busy；15:33:48 尝试终止授权 PID
  3757761/3771205 时，二者已自行退出（`kill` 报 no such process），未实际
  发送信号。
- 释放后 W7 的排队进程 3926936 立即取得锁并运行 `run_e2e01.sh`；按裁决
  不终止它。W2 的 E2E 08 以 `flock -w 1800` 排在其后。
- 结论：锁本身工作正常；“把长驻 app 放进模型锁”是错误的持有方式，应由
  各 worker 用有限命令 + 显式 `-w` 排队。

## 6.2 内存审查与 OOM 限制（2026-11-06）

热点与修复（仅列我负责/改过的文件）：

| 文件 | 热点 | 处理 |
|---|---|---|
| `src/hc/client/workbench/events.cljc`（`record-search-run`） | 每个搜索轮次把整份 `:search-context`（citation-map、document-summaries、source-cards）复制进 `:ui/search-state` 的 `last-run` | 只保留 `:status/:searched?/:must-search-failed?/:source-cards/:omitted-source-count/:error`；删除 `:provenance` 全量拷贝。测试 `failed-search-run-keeps-the-source-list` 断言 `:provenance` 为 nil |
| `src/hc/server/app/system.clj`（`forward-owner-event!`） | 每事件一次 `assoc` 小拷贝 | 无累积状态；广播不保留引用 |
| `src/hc/compose/owner/handlers.clj`（`answer-ask`/`awaiting-tenures`） | `concat` + `filter` + `mapv` 每次回答一次 | tenure 数量有界（槽位数）；无缓存 |
| `src/hc/compose/owner/guards.clj`（park 投影） | 每次 park 一次 mission 事务 | 无日志/事件累积 |
| `src/hc/core/relay/core.clj`、`src/hc/compose/mission/host.clj`（`mark-awaiting`） | 一次持久化 map assoc（结构共享） | 不复制全量历史 |

资源限制：

- app JVM：`-Xmx1g -XX:MaxDirectMemorySize=512m`（`/tmp/hc-w2/app08.sh`、`app07.sh`）。
- Playwright Chrome：`--no-sandbox --disable-dev-shm-usage --disable-gpu --disable-software-rasterizer`。
- 同一时刻最多 1 个 app JVM + 1 个 Chrome；聚焦测试与 E2E 不并行；E2E 结束立即停实例。
- OOM 盘点：PID 86083 不存在；PID 85370 属 W13 的全量测试（`JAVA_TOOL_OPTIONS` 已含 1g/512m 上限），未杀。
- 10:5x W15 审计发现 E2E07 在 `/tmp/hc-app-single.lock` 外并发。已立即停止：
  - driver/flock/timeout：PID 203437 / 203436 / 203435（SIGTERM，Chrome 随 node 退出）；
  - app07：PID 201682（服务停止）。
- 之后整段“启动 app + Chrome + E2E + 停止”用
  `flock -w 1800 /tmp/hc-app-single.lock bash /tmp/hc-w2/run07_locked.sh`；
  脚本内部 app 用 `JAVA_TOOL_OPTIONS=-Xmx768m -XX:MaxDirectMemorySize=512m`，
  driver 用 `flock -w 1800 /tmp/hc-e2e-model.lock`，Chrome
  `--disable-dev-shm-usage --disable-gpu`，`trap` 在退出时 TERM app。
- 当前常驻：search stub 85463（≈50M）、docs 86780（≈23M）；无 app JVM、无 Chrome。

## 6.3 E2E 07 段中止与锁让位（2026-11-06 11:15）

- 用户要求视觉工人优先：立即停止我的 E2E 07 有限段并释放
  `/tmp/hc-app-single.lock`，暂停 10 分钟。
- 已停止：driver 280753、timeout 280752、model flock 280751、脚本 bash 279934、
  app-single flock 268132、app07 java 279936（端口 42081）。
- 结果：`/tmp/hc-w2/e2e07j-result.json` = `ok:false`，错误为
  “Target page, context or browser has been closed”（由本次主动停止造成）；
  已保留 `plans/evidence/e2e-07-08/e2e07-panel.png`（搜索面板：模式/服务选择）。
- 复核：279936/280753 在 TERM 前已不存在；排队 271726 也不存在；
  42081 无监听；我的 app/driver/flock 全部退出。`fuser -v` 中只剩其他 worker 的
  283521/304761/305375/305379/305550，不是我的进程。我的常驻仅
  search stub 85463（≈52M）与 docs 86780（≈23M）。
- 暂停 10 分钟，不启动 app/Chrome，等待协调者通知后恢复 E2E 07。
- E2E 07 仍未 PASS：搜索发送后的来源卡/去重/日期/编号/失效来源/导出/断网断言
  均未取得本轮证据。

## 7 剩余阻塞（按优先级）

1. **unpark 载荷缺 `:lane`/`:tenure-id`**（§2.1）——S1 回答后不恢复；修完重跑
   E2E 08 的“提交 → unpark → Esc”半段。
2. **E2E 07 未执行真实搜索旅程**——先确认 14:56 bundle 含最新客户端代码，
   再跑 `w2-07` 的搜索步骤；stub 与文档站已就绪。
3. Esc 场景依赖 1；ask 之后 composer 一直 disabled 的下游原因也要在 1 修完后复核。

## 8 E2E 07 收口（2026-11-06，W27；bundle 8b210896）

- bundle：`8b2108963aca76226146ba275409d5fe95f851d5877e1f73a6ab7466d43b9b85`
  （3,009,689 B，12:17:19；运行前后 hash 相同，见
  `plans/evidence/e2e-07-08/e2e07-8b210896-bundle.txt`）。
- 环境：真实 Ollama `qwen3.6:27b`；本地 stub `127.0.0.1:18095` + 文档站
  `127.0.0.1:18096`（非真实联网供应商）；真实 Chrome（Playwright，headless）。
- 证据：`plans/evidence/e2e-07-08/e2e07-8b210896-result.json`、
  `e2e07-8b210896-{panel,answer,export,dead-source,offline}.png`、
  `search-stub-requests-8b210896.log`。脚本：`/tmp/hc-w2/e2e07k_steps.cjs`。
- 结论：**PARTIAL**。除“导出纯文本含完整链接”外，本轮要求的搜索旅程断言全部 PASS。

### 8.1 修复（服务端）

根因：搜索答案以 `[S1]` 流式展示后，Plan 继续发起工具调用；模型终态
`:completed` 的 `:text` 为空，`search-ctx/wrap-deliver!` 的
`apply-citations` 没有可改写的文本，assistant 消息因此既没有 `[1]` 编号，
也没有 `:citations`。修复分三处：

1. `src/hc/server/app/system.clj`（模型网关 `deliver''`）：终态
   （`:completed/:failed/:cancelled`）携带
   `search-ctx/public-context`（有界：source-cards、labels、citation-map、
   queries 等；不含用户文本、refs、文档正文）。
2. `src/hc/compose/owner/handlers.clj`（`:completed`）：从
   `:search-context` 取 `:source-cards`，在事件没有 `:citations` 时把它们作为
   来源列表写入 `extra`/assistant 消息与历史镜像；终态 payload 保留
   public context。`:failed/:cancelled` 也保留 context。
3. `src/hc/server/app/system.clj`（publisher）：`RUN_FINISHED` 增加
   `:search-context`（优先 call/`:result`/`:terminal`），客户端
   `record-search-run` 因此能显示“已搜索”与面板来源卡。

客户端（源码级，已测试；不依赖本轮 bundle 生效）：`events.cljc` 的
`attach-search-sources` 在 `RUN_FINISHED` 把 source-cards 幂等挂到含
`[S1]` 标注的 assistant 消息，保证下一次构建的导出/消息投影也带来源。

### 8.2 测试

| 命令 | 结果 |
|---|---|
| `clojure -M:dev:client:test -n hc.client.workbench.events-test` | 44 tests / 165 assertions / 0F / 0E |
| `clojure -M:dev:server:test -n hc.compose.owner.runtime-test` | 30 tests / 161 assertions / 0F / 0E |
| `clojure -M:dev:server:test -n hc.server.app.owner-event-broadcast-test` | 5 tests / 22 assertions / 0F / 0E |

新增：`finished-search-attaches-source-cards-to-the-answer-message`（客户端）、
`completed-model-call-carries-public-search-context`（Owner 终态 payload 与消息来源）。

### 8.3 E2E 07 断言（真实 UI，8b210896）

| 断言 | 结果 | 证据 |
|---|---|---|
| 搜索模式 关闭/自动/本轮必须搜索 | PASS：`['关闭','自动','本轮必须搜索']`，required `aria-pressed=true` | 结果 JSON `modes`/`mustSearchMode`、`e2e07-8b210896-panel.png` |
| 选择独立搜索服务 | PASS：`local-stub`，无“请选择搜索服务”错误 | `serviceSelected=true` |
| 发送与搜索实际执行 | PASS：发送按钮可用；stub 新增 1 次 `W2E2E07` 请求 | `sendEnabled=true`、`newRequests=1` |
| 搜索阶段状态 | PASS：面板显示“已搜索”“来源” | `panelStatusAfter`、`e2e07-8b210896-answer.png` |
| 模型使用搜索上下文 | PASS：回答引用 Doc One / ALPHA-ONE | `answerUsesSearchContext=true` |
| 来源卡 | PASS：3 张 `[S1][S2][S3]`，含标题/站点/发布/抓取/摘要/链接 | `cards` |
| 同一 URL 去重 | PASS：doc1.html 只出现 1 张卡 | `dedupSameUrl=true` |
| 未知发布日期不冒充抓取时间 | PASS：Doc One/Missing 显示“发布：未知”，同时保留“抓取：2026-11-06…” | `unknownDate=true` |
| 有发布日期保留 | PASS：Doc Two “发布：2025-01-02T00:00:00Z” | `publishedDateKept=true` |
| 正文编号与来源列表一一对应 | PASS：正文 `[S1][S2][S3]` 与卡片标签逐项对应 | `numberingMatches=true`、`numberMap` |
| 失效来源保留标题/地址 | PASS：Missing Source 卡片保留标题与 missing.html；点击后主页面 URL 未跳转（F7 吞链，见 §8.4） | `deadSourceCardKept=true`、`e2e07-8b210896-dead-source.png` |
| 导出纯文本含完整链接 | **FAIL**：勾选“含来源”后导出的文本仍只有对话，没有“来源：”与 3 个完整 URL；`sourceBoxChecked=true`，导出路径未取得消息来源列表 | `exportText`、`e2e07-8b210896-export.png` |
| 断网重看不发起隐式搜索 | PASS：离线导航后 3 张来源卡仍在，stub 请求 5→5 | `offlineCardsKept=true`、`offlineNoImplicitSearch=true` |
| 页面错误 | PASS：0 pageerror | `noPageErrors=true` |

### 8.4 剩余阻塞与产品缺口

1. **导出纯文本缺来源列表**（本轮唯一未 PASS 断言）。冻结 bundle 的导出
   `source-lines` 只读消息的 `:sources`/`:citations`；本轮消息来源由
   `RUN_FINISHED` public context 进入客户端，消息投影未把该列表交给导出。
   源码已加 `attach-search-sources` 并有单测；需要下一次构建把 public context
   接入导出（或让导出直接读 `:ui/search-state` 的 source-cards）后复跑。
2. **来源卡外链点击不打开**：F7 clicks 模块对非 `.external` 的 `<a>`
   `preventDefault` 后走内部路由，`target="_blank"` 也不会新开标签。正文
   `[n]` 仅为纯文本。记录为产品缺口，未把“点击”写成 PASS。
3. **Plan 在 S1 搜索问答后继续发起工具调用**（本轮出现 devops 失败/继续生成）：
   这是“普通回答路径 vs 工具路径”的产品决定项，不在本轮强改 Plan 语义；
   建议产品明确：搜索问答命中后是否允许同一轮继续工具调用。
4. E2E 08 的 Esc 在新 bundle 上未复跑（08m 断言前浏览器被外部关闭）；08 证据
   仍绑前冻结 55e4e8ea，详见 master 表 08 行。

## 9 E2E 08 复跑（2026-11-06，W27；bundle ea9e5732）

- bundle：`ea9e5732656e79e738883ba384b1a62b4fe9461d396b83612ad4624dd81a39c2`
  （3,010,646 B，13:02:42；运行前后 hash 相同）。
- 证据：`plans/evidence/e2e-07-08/e2e08-ea9e5732-result.json`、
  `e2e08-ea9e5732-{ask-card,answered,unparked,composer-recovered,esc-before,esc-after}.png`、
  `e2e08-ea9e5732-bundle.txt`。
- 结论：**PARTIAL**。S1 提问/回答/服务端 unpark/同一 run 继续/composer 恢复 PASS；
  Esc 停住 run 且内容保留、无迟到追加，但“新长文本生成中触发”的前提未满足
  （Plan 走了工具路径，见 §8.4 第 3 条）。

| 断言 | 结果 | 证据 |
|---|---|---|
| 常授权模式不弹逐次审批 | PASS（页面无审批弹窗，S1 ask 卡直接出现） | `e2e08-ea9e5732-ask-card.png` |
| `Plan` 在 S1 调 `.ask` 后 UI 出现 `.hc-ask` | PASS：问题文本“E2E-08 请给出一个数字” | 同上、结果 JSON |
| CUSTOM 事件名正确 | PASS：`hc/ask` 帧 2 条；裸 `"name":"ask"` 0 条 | 结果 JSON `wire` |
| 填写并提交回答 | PASS：提交按钮 1 个，提交后卡片“已回答” | `e2e08-ea9e5732-answered.png` |
| 服务端 unpark，同一 run 继续 | PASS：`e2e-08-resume` 程序 `(unpark {:name "e2e-08-ask" :value "42"})` `:status :COMPLETED`，parked 程序 `:value "42"`；无需重发 | `e2e08-ea9e5732-unparked.png`、结果 JSON `unparkEvidence` |
| ask 后 composer 恢复可用 | PASS：run idle 15.1 s 后填草稿发送按钮可用、无 blocker；空草稿禁用属 `:empty` 设计 | `e2e08-ea9e5732-composer-recovered.png`、`composerIdleHint` |
| Esc 取消在途会话树并保留已生成内容 | **PARTIAL**：`escStopped`/`escRunInactiveAfterStop`/`escContentPreserved`/`escNoLateAppend` 均 true；但 `escFirstChunk` 取得的是新 run 的 tool-call 计划源（1473 字符），不是 assistant 长文本；`escStreamSeen=false` | `e2e08-ea9e5732-esc-before.png`、`-esc-after.png`、结果 JSON |
| 非 S1 `.ask` 拒 `:ASK_FORBIDDEN_OUTSIDE_S1`；普通个体 `.call` 拒 `:TOKEN_REQUIRED` | 契约 PASS（`test/hc/server/program/capability_test.clj`），真实 UI 未覆盖 | 测试 |

Esc 未取得“长文本生成中”证据的原因：同一 S1 轮里 Plan 在收到长文本请求后继续走
`clj-plan` 工具路径（与 §8.4 第 3 条同一产品决定项），新消息体是程序源而不是
assistant 文本；本轮不强行改 Plan 语义，因此按 PARTIAL 记录。

## 10 最终冻结 072587a4 复跑（2026-11-06，W27）

- 最终冻结：`main.js` = `072587a4690b6953bae9cae3a06eaaa815a38b13eee9b98dc5ce98e72a54824f`
  （3,011,162 B，13:51:04；CSS `332f9fd3…`）；E2E07 的 app 于 14:10:48 启动，
  晚于 `src/hc/server/app/system.clj` 14:08:18 的修复，故服务端树为
  `d1d8d08d…`。E2E08 的 app 于 13:46–13:50 启动，服务端树为 `a97c68f7…`。
- 证据：`e2e07-072587a4-result.json`、`e2e07-072587a4-*.png`、
  `search-stub-requests-072587a4.log`、`e2e07-072587a4-bundle.txt`；
  `e2e08-072587a4-result.json`、`e2e08-072587a4-*.png`、`e2e08-072587a4-bundle.txt`。

### 10.1 E2E 07（13/13 PASS）

搜索模式/服务/本轮必须搜索/发送/搜索执行（stub +1）/阶段“已搜索”/模型上下文/
3 张来源卡/URL 去重/未知日期/发布日期/编号对应（`[S1][S2][S3]`）/失效来源保留/
断网重看不发隐式搜索/**导出纯文本“来源：”+ 3 个完整 URL** 全部 PASS，0 pageerror。
导出文本实见：
`来源：\n- Doc One — http://127.0.0.1:18096/doc1.html（…未知发布日期）\n- Missing Source — …missing.html…\n- Doc Two — …doc2.html（…2025-01-02T00:00:00Z）`。
来源卡外链点击仍被 F7 吞掉（`deadSourceOpened=false`），单列产品缺口，不改变本场景 PASS。

### 10.2 E2E 08（PARTIAL）

S1 `.hc-ask`、问题文本、提交“42”、服务端 unpark（`e2e-08-resume` `:status :COMPLETED`，
parked 程序 `:value "42"`）、同一 run 继续、composer 15 s 恢复均 PASS；`hc/ask` 帧 2、
裸 `name":"ask"` 0。Esc：`escStopped`/`escRunInactiveAfterStop`/`escContentPreserved`/
`escNoLateAppend` 均 true，但 `escFirstChunk` 取到的是新 run 的 tool-call 计划源
（1473 字符），`escStreamSeen=false`，未触发 assistant 长文本流；Plan 工具路径
（§8.4 第 3 条产品决定项）使“长文本生成中触发”的前提未满足，故 08 保持 PARTIAL。

### 10.3 前冻结说明

`8b210896`、`ea9e5732`、`0c05b7d1` 及更旧的 E2E 证据均为前冻结；`0c05b7d1` 上
E2E07 也是 13/13 PASS，但按最终冻结口径只以 `072587a4` 的 §10.1 为准。

## 11 最终冻结 6aa694f1 复跑（2026-11-06，W43；bundle 072587a4 + 树 6aa694f1）

- 最终冻结：`main.js` = `072587a4690b6953bae9cae3a06eaaa815a38b13eee9b98dc5ce98e72a54824f`
  （3,011,162 B，13:51:04；CSS `332f9fd3`）；源码树
  `6aa694f1223187ab3aa3fda90d61d3313c2aa25410d14a1009c89cc5f4c10035`
  （W35 final10 单树全量 PASS 3758/24351/0F/0E/365 s）。
- 运行：`flock -w 3600 /tmp/hc-app-single.lock bash /tmp/hc-w2/run07u_locked.sh`；
  app 端口 42086，`JAVA_TOOL_OPTIONS=-Xmx768m -XX:MaxDirectMemorySize=512m`，
  data `/tmp/hc-w2/data-07u`；真实 Chrome（Playwright headless）；Ollama
  `qwen3.6:27b`（reasoning-effort none）；搜索 stub `127.0.0.1:18095` +
  文档站 `18096`（非真实联网供应商）。bundle 与树 hash 在 run 前后各记一次，
  两者均一致（见 `e2e07-6aa694f1-bundle.txt`）。
- 证据：`plans/evidence/e2e-07-08/e2e07-6aa694f1-result.json`、
  `e2e07-6aa694f1-{panel,answer,export,dead-source,offline}.png`、
  `search-stub-requests-6aa694f1.log`；脚本 `/tmp/hc-w2/e2e07u_steps.cjs`、
  `run07u_locked.sh`；原始结果 `/tmp/hc-w2/e2e07u-result.json`。
- 前置说明：任务原定树 `e465f626`（final9）已随 final10 作废；首次 u 前的
  t 轮（`e2e07t-result.json`）因 stub 计数使用了静态快照文件导致
  `searchRan=false`（工装缺陷，非产品缺陷），u 轮改用主日志计数并全部 PASS。

### 11.1 断言（27 项全 true，ok=true，0 pageerror）

| 断言 | 结果 | 证据 |
|---|---|---|
| 模型已绑定 qwen3.6:27b | PASS | `modelSelected=true` |
| 搜索模式 关闭/自动/本轮必须搜索 | PASS：`modesPresent=true` | `e2e07-6aa694f1-panel.png` |
| 选择独立搜索服务 local-stub | PASS：`serviceSelected=true`、`noSelectionError=true` | 同上 |
| 本轮必须搜索 aria-pressed | PASS：`mustSearchMode=true` | 同上 |
| 发送按钮可用 | PASS：`sendEnabled=true` | 结果 JSON |
| 搜索实际执行 | PASS：stub 主日志新增 1 次 W2E2E07（beforeAnswer=9→10） | `search-stub-requests-6aa694f1.log`、`stub.newRequests=1` |
| 来源卡出现 | PASS：3 张 `[S1][S2][S3]` | `cards`、`e2e07-6aa694f1-answer.png` |
| 模型使用搜索上下文 | PASS：正文 ALPHA-ONE/Doc One/BETA-TWO/2025-01-02 | `answerUsesSearchContext=true`、`answerText` |
| 正文编号与来源一一对应 | PASS：`numberingMatches=true`，numberMap 三项 label/title/link 对应 | 结果 JSON |
| 同一 URL 去重 | PASS：`dedupSameUrl=true`（doc1.html 仅 1 卡） | 同上 |
| 未知发布日期不冒充抓取时间 | PASS：`unknownDate=true`、`publishedDateKept=true` | 同上 |
| 失效来源保留标题/地址 | PASS：`missingKeepsTitleUrl=true`、`deadSourceCardKept=true`；外链点击仍被 F7 吞（`deadSourceOpened=false`，finding） | `e2e07-6aa694f1-dead-source.png` |
| 搜索阶段状态 | PASS：面板“已搜索”“来源” | `panelStatusAfter` |
| 导出纯文本含完整链接 | PASS：`exportHasFullLinks=true`；导出文本含“来源：”+ doc1/missing/doc2 三个完整 URL 与发布/未知日期 | `e2e07-6aa694f1-export.png`、`exportText` |
| 断网重看不发隐式搜索 | PASS：`offlineNoImplicitSearch=true`（stub 10→10） | `e2e07-6aa694f1-offline.png` |
| 断网重看保留来源卡 | PASS：`offlineCardsKept=true` | 同上 |
| 页面无异常 | PASS：`noPageErrors=true`、`pageErrors=[]` | 结果 JSON |

结论：E2E07 在最终冻结 bundle `072587a4` + 源码树 `6aa694f1` 上 **13/13 场景断言 PASS**，证据双记 bundle/树 hash。

## 11 E2E 08 最终冻结 Esc 复跑（2026-11-06 18:02，W39；bundle 72de4f9a + 树 ab801450）

- 最终冻结：`main.js` = `72de4f9a3d452e697e4d8a0311af6954621d373124d378964eee1f24b7ac0726`
  （3,014,943 B，17:45:52；CSS `332f9fd3` 未变）；源码树 `ab801450…`（final11
  PRE=POST 单树）。app 18:02:07 启动，运行时树 hash 记录于
  `app08s.log.tree` = `ab801450…`，与冻结一致。
- 证据：`plans/evidence/e2e-07-08/e2e08-72de4f9a-result.json`、
  `e2e08-72de4f9a-{ask-card,answered,unparked,composer-recovered,esc-before,esc-after}.png`、
  `e2e08-72de4f9a-bundle.txt`（含 bundle+tree 双 hash）。脚本
  `/tmp/hc-w2/e2e08s_steps.cjs`（assistant-scoped 探针 + 残留 run 停止）。
- 前置事件：15:28/15:50 两轮在 final9/final10 中间树上运行，因冻结切换主动
  中止或被残留 run 阻塞（`askSendEnabled=false`），均未计入证据；16:14–18:02
  锁被 W36 孤儿 app java 1580864（PPID 1）占用，经裁决 `kill -TERM` 后释放。

### 11.1 断言（真实 UI，全部 PASS）

| 断言 | 结果 | 证据 |
|---|---|---|
| 残留 run 停止（前一轮中止遗留） | PASS：`residualRunStopped=true` | 结果 JSON `phases` |
| `Plan` 在 S1 调 `.ask` 后 UI 出现 `.hc-ask` | PASS：问题文本“E2E-08 请给出一个数字” | `e2e08-72de4f9a-ask-card.png` |
| CUSTOM 事件名正确 | PASS：`hc/ask` 帧存在；裸 `"name":"ask"` 0 | 结果 JSON `wire` |
| 填写并提交回答 | PASS：提交按钮 1 个，提交后“已回答” | `e2e08-72de4f9a-answered.png` |
| 服务端 unpark，同一 run 继续 | PASS：`resumeProgramSeen=true`、`parkedProgramCompleted=true` | `e2e08-72de4f9a-unparked.png`、`unparkEvidence` |
| ask 后 composer 恢复 | PASS：run idle 32.1 s 后可发送 | `e2e08-72de4f9a-composer-recovered.png` |
| Esc 前提：assistant 长文本流 | **PASS（首次达成）**：`escFirstChunk` 捕获助手正文 “Why Unmanned Agent…”，`escToolCallPath=false`；`:chat?` 修复后 Plan 走普通回答路径而非 clj-plan 工具路径 | `e2e08-72de4f9a-esc-before.png` |
| Esc 停止整棵会话树 | PASS：`escStopped=true`、`escRunInactiveAfterStop=true` | `e2e08-72de4f9a-esc-after.png` |
| 内容保留 | PASS：`escContentPreserved=true`（18→26 字符增长后保留） | 结果 JSON |
| 迟到文本不追加 | PASS：`escNoLateAppend=true`（5 s 后长度不变） | 结果 JSON |
| 页面无异常 | PASS：`noPageErrors=true` | 结果 JSON |
| 非 S1 `.ask` 拒 `:ASK_FORBIDDEN_OUTSIDE_S1`；普通个体 `.call` 拒 `:TOKEN_REQUIRED` | 契约 PASS（`capability_test.clj`），真实 UI 未覆盖 | 测试 |

### 11.2 结论

**E2E 08 = PASS**。`:chat? true` 普通回答路径修复（final11 树）使 Esc 场景的
“assistant 长文本生成中触发”前提首次在真实 UI 满足；停止、内容保留、迟到
不追加三项断言全部通过。残留缺口仅剩非 S1/普通个体拒码的真实 UI 回执
（契约已覆盖），不构成场景阻断。

## 12 最终冻结 final11 复跑（2026-11-06，W43；bundle 72de4f9a + 树 ab801450）

- 最终冻结：`main.js` = `72de4f9a3d452e697e4d8a0311af6954621d373124d378964eee1f24b7ac0726`
  （3,014,943 B，17:45:52；CSS `332f9fd3`）；源码树
  `ab8014505efefe7e69fa686689499c876c3836091b4832569d119c0b43beba2c`
  （W35 final11 单树全量 PASS 3792/24578/0F/0E/360 s）。final10
  （`072587a4`/`6aa694f1`）降为历史基线。
- 运行：`flock -w 3600 /tmp/hc-app-single.lock bash /tmp/hc-w2/run07v_locked.sh`；
  app 端口 42087，`JAVA_TOOL_OPTIONS=-Xmx768m -XX:MaxDirectMemorySize=512m`，
  data `/tmp/hc-w2/data-07v`；真实 Chrome（Playwright headless）；Ollama
  `qwen3.6:27b`（reasoning-effort none）；搜索 stub `127.0.0.1:18095` +
  文档站 `18096`（非真实联网供应商）。bundle 与树 hash 在 run 前后各记一次，
  两者均一致（见 `e2e07-ab801450-bundle.txt`）。
- 证据：`plans/evidence/e2e-07-08/e2e07-ab801450-result.json`、
  `e2e07-ab801450-{panel,answer,export,dead-source,offline}.png`、
  `search-stub-requests-ab801450.log`；脚本 `/tmp/hc-w2/e2e07v_steps.cjs`、
  `run07v_locked.sh`；原始结果 `/tmp/hc-w2/e2e07v-result.json`。

### 12.1 断言（27 项全 true，ok=true，0 pageerror）

| 断言 | 结果 | 证据 |
|---|---|---|
| 模型已绑定 qwen3.6:27b | PASS | `modelSelected=true` |
| 搜索模式 关闭/自动/本轮必须搜索 | PASS：`modesPresent=true` | `e2e07-ab801450-panel.png` |
| 选择独立搜索服务 local-stub | PASS：`serviceSelected=true`、`noSelectionError=true` | 同上 |
| 本轮必须搜索 aria-pressed | PASS：`mustSearchMode=true` | 同上 |
| 发送按钮可用 | PASS：`sendEnabled=true` | 结果 JSON |
| 搜索实际执行 | PASS：stub 主日志新增 1 次 W2E2E07（beforeAnswer=11→12） | `search-stub-requests-ab801450.log`、`stub.newRequests=1` |
| 来源卡出现 | PASS：3 张 `[S1][S2][S3]` | `cards`、`e2e07-ab801450-answer.png` |
| 模型使用搜索上下文 | PASS：正文 ALPHA-ONE/Doc One/BETA-TWO/2025-01-02 | `answerUsesSearchContext=true`、`answerText` |
| 正文编号与来源一一对应 | PASS：`numberingMatches=true`，numberMap 三项 label/title/link 对应 | 结果 JSON |
| 同一 URL 去重 | PASS：`dedupSameUrl=true`（doc1.html 仅 1 卡） | 同上 |
| 未知发布日期不冒充抓取时间 | PASS：`unknownDate=true`、`publishedDateKept=true` | 同上 |
| 失效来源保留标题/地址 | PASS：`missingKeepsTitleUrl=true`、`deadSourceCardKept=true`；外链点击仍被 F7 吞（`deadSourceOpened=false`，finding） | `e2e07-ab801450-dead-source.png` |
| 搜索阶段状态 | PASS：面板“已搜索”“来源” | `panelStatusAfter` |
| 导出纯文本含完整链接 | PASS：`exportHasFullLinks=true`；导出文本含“来源：”+ doc1/missing/doc2 三个完整 URL 与发布/未知日期 | `e2e07-ab801450-export.png`、`exportText` |
| 断网重看不发隐式搜索 | PASS：`offlineNoImplicitSearch=true`（stub 12→12） | `e2e07-ab801450-offline.png` |
| 断网重看保留来源卡 | PASS：`offlineCardsKept=true` | 同上 |
| 页面无异常 | PASS：`noPageErrors=true`、`pageErrors=[]` | 结果 JSON |

结论：E2E07 在最终冻结 bundle `72de4f9a` + 源码树 `ab801450` 上 **13/13 场景断言 PASS**，证据双记 bundle/树 hash。
