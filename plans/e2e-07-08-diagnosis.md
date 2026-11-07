# E2E 07 / 08 事件链路诊断（2026-11-05）

- 范围：E2E 08 的 `:hc/ask` 服务端到浏览器链路；E2E 07 的搜索意图与来源数据链路。
- 方法：读代码逐跳定位；每处“已证”都附文件与行号；修复后用聚焦测试和真实 app 复跑。
- 结论先行：**E2E 08 的 `.hc-ask` 缺失有两处确定性缺陷**（模型网关被当成 AG-UI 网关、事件名丢了命名空间），均已修复并有测试。E2E 07 的搜索服务选择在客户端事件/写入层已正确；搜索请求为 0 的原因需要用真实 app 复跑定位，本轮不把旧推断写成结论。

## 1 E2E 08：`:hc/ask` 的每一跳

| # | 跳 | 代码位置 | 事实 |
|---|---|---|---|
| 1 | Plan 程序 park | `src/hc/compose/mission/devops.clj` / program executor | 真实运行中 tool 结果 `{:state :parked :payload {:question ...} :s1? true}`（`/tmp/hc-compose-e2e/evidence/e2e08e-owner-state.txt`） |
| 2 | 工具结果投影追加 outbox | `src/hc/compose/owner/guards.clj:800-819`（`parked-ask`）、`:930-945`（`state/append-outbox [{... :type :hc/ask ...}]`） | `parked-ask` 只接受 `:state :parked` 且 `:payload :question` 是字符串；`result-projected?` 保证同一 park 点只发一次 |
| 3 | 提交后排空 outbox | `src/hc/compose/owner/runtime.clj:160-203`（`drain-outbox!`）、`:67-78`（`record-event!` → `:on-effect!`） | 非 `:worker/run-program` 条目走 `record-event!`，调用注入的 `:on-effect!` |
| 4 | Owner 启动装配 | `src/hc/compose/owner.clj:107-122`（`report-bridge/on-effect!`） | `:report/ack` 之外的事件转给 `:forward-effect!` |
| 5 | app 转发 | `src/hc/server/app/system.clj:1722-1725`（旧代码）；现 `:1196-1230`（`forward-owner-event!`） | **缺陷 A**：旧代码把局部绑定 `gateway`（模型网关，`system.clj:1592` 的 `pair-hint/wrap-model-gateway`）传给 `gateway/broadcast!`。模型网关没有 `:feed`，`broadcast!` 抛错后被 `catch Throwable _ nil` 吞掉，浏览器永远收不到。**缺陷 B**：`(name :hc/ask)` 得到 `"ask"`，而客户端按完整 `"hc/ask"` 分派。 |
| 6 | AG-UI 网关广播 | `src/hc/server/gateway.clj:781`、`src/hc/server/gateway/feed.clj:466` | `feed/broadcast!` 给每个活跃订阅复制事件并调用 Sente `send-fn`；不消耗 seq |
| 7 | 客户端收事件 | `src/hc/client/workbench/sync_bridge.cljs`（`on-event!` else 分支） | 非状态事件先 `mutations/apply-run-event`，再依次调用 `event-listeners` |
| 8 | 会话桥接 | `src/hc/client/workbench/conversation_bridge.cljs:38-47`（`install!` + `ensure-event-listener!`） | `handle-event!` 调 `conversation-state/apply-event-db`，有变化才 `write!` |
| 9 | 纯归约 | `src/hc/client/workbench/conversation_state.cljc:232-250`（`ask-action`） | `:hc/ask` → `{:type :ask/show :ask value}`；`apply-event-db` 写 `:ui/ask` |
| 10 | 渲染 | `src/hc/client/conversation/view.cljs`（W3 所有） | `.hc-ask` 卡片；DOM 测试 `s1-ask-card-submits-an-unpark-answer` 已覆盖提交回调 |

**修复 A**：`forward-owner-event!` 从 `(:gateway @system-ref)` 取真正的 AG-UI 网关。`system-ref` 在 `system.clj:3770` 写入 `:gateway gateway-handle`；Owner 在网关创建前启动，因此每次调用时解析，而不是启动时捕获。
**修复 B**：`owner-event-name` 保留命名空间：`:hc/ask` → `"hc/ask"`，`:worker/program-result` → `"worker/program-result"`。

测试：
- `test/hc/server/app/owner_event_broadcast_test.clj`：真实 feed 订阅 + 真实 Owner outbox（种子 tool call 结果 park）→ 断言订阅者收到 `CUSTOM name="hc/ask"`，且 `ask-id/question/thread-id` 正确；无关事件不占用 feed；缺网关/模型网关形状不抛错。
- `test/hc/compose/nudge/owner_integration_test.clj` 的 `admission-refusal-injects-one-nudge` 增补：拒绝回执必须含 `:expected`、`reset-scope`、`new-scope-goal`、`new-scope-retention-budget`、`:hint`。

## 2 E2E 08：S1 回答与解除

| 跳 | 代码位置 | 事实 |
|---|---|---|
| 客户端提交回答 | `src/hc/client/conversation.cljc`（submit/skip 载荷）+ `conversation_bridge.cljs` `wire-payload` | 载荷必须携带 `:ask-id`、`:lane`、`:tenure-id`、`:value`；卡片字段来自 `ask/new-ask` 保留的服务端事件字段 |
| 服务端接收 | `src/hc/compose/owner/handlers.clj` 的 `answer-ask`（`awaiting-tenures` 过滤） | 按 `:lane`/`:tenure-id` 选唯一 parked tenure；无候选拒 `:ASK_TENURE_MISMATCH`（显式指名时）或 `:NOT_AWAITING_ANSWER`，多候选 `:AMBIGUOUS_ASK`；成功 `mission/resume` + 新 pending turn + `:hc/ask-resolved`，回执 `:outcome :answer-accepted` |
| 广播 | 同 §1 修复 A/B | `hc/ask-resolved` 现在也能到达客户端 |
| 拒码 | `src/hc/core/scope/admission.clj`、`test/hc/server/program/capability_test.clj` | `:ASK_FORBIDDEN_OUTSIDE_S1`、`:TOKEN_REQUIRED` 是契约测试覆盖；本轮不把契约 PASS 当 UI PASS |

## 3 E2E 07：搜索意图与来源

| # | 跳 | 代码位置 | 事实 |
|---|---|---|---|
| 1 | 控件选择 | `src/hc/client/search/view.cljs`、`src/hc/client/workbench/views/session.cljs:281-320` | `service-id` 统一为字符串；`update-search-state` 写 `[:ui/search-state session-id]` |
| 2 | 发送载荷 | `src/hc/client/workbench/views/session.cljs:361-372` + `src/hc/client/search/model.cljc:167-180` | `:off` 显式发送；非 `:off` 带 `:service-id` 与 `:queries` |
| 3 | 命令到达 Owner | `src/hc/compose/owner/handlers.clj:660-700` | `pending-turn` 保存 `:search` |
| 4 | 请求快照 | `src/hc/compose/owner/guards.clj:2064-2065` | `:search` 进入 `[:snapshot :search]` |
| 5 | 搜索执行 | `src/hc/server/app/system.clj:740-780` + `src/hc/server/search/integration.clj` | 只在 Owner 接受并提交后、模型调用前执行；`:required` 失败阻断模型 |
| 6 | 来源卡上下文 | `src/hc/server/search/context.clj:82-110`（`card`/`source-cards`）、`:285-408`（`turn-context`） | 卡片 `S1..Sn`；`published-at` 与 `fetched-at` 分开；`published-at-unknown?` 只在无发布日时 true |
| 7 | 引用编号 | `src/hc/server/search/context.clj:435-515`（`citation-labels`/`apply-citations`） | 首次引用顺序分配 `[1..n]`；未知标签留在正文并列入 `:unresolved` |
| 8 | 消息持久化 | `src/hc/compose/owner/handlers.clj:2080-2130`、`:454-479`（`mirror-assistant-history!`） | completed 事件的 `:citations`/`:search-refs` 写入助手消息版本 |
| 9 | 快照投影 | `src/hc/server/app/snapshot.clj:490-560` | 会话消息与历史消息 merge；历史字段（含 `:citations`）保留 |
| 10 | 客户端来源卡 | `src/hc/client/search/model.cljc:262-335`（`source-cards`/`message-sources`） | 同一 URL 先 `merge-sources` 再排序；`:published-at` 不被 `:fetched-at` 替换 |

**已确认的缺口**：`search-model/record-run` 在客户端没有调用者，`RUN_FINISHED` 的 `:search-status` 只用于降级提示，`[:ui/search-state :last-run]` 永远为空。来源卡目前只能依赖“助手消息上的 `:citations`”；这是设计允许的路径（`message-sources` 优先读消息），但运行状态（已搜索/跳过/失败）没有进入搜索面板。本轮先按真实复跑取证，再决定是否在 `events.cljc`（我方可改）里补 `record-run`。

## 4 真实复跑顺序

1. E2E 08：`w2-08`（data `/tmp/hc-w2/data-08`，workspace `/tmp/hc-w2/work-08`），真实 Ollama `qwen3.6:27b`，真实 Chrome。
2. E2E 07：`w2-07`（data `/tmp/hc-w2/data-07`），本地 SearXNG stub `127.0.0.1:18095` + 文档站 `18096`；**非真实联网供应商**。
3. 证据目录 `plans/evidence/e2e-07-08/`；模型运行串行用 `flock /tmp/hc-e2e-model.lock`。
