# compose 层协议审查与修复（0.0.1）

> 范围：`src/hc/compose/**` 与 `test/hc/compose/**`；另有两处最小客户端改动（D7，见 §3），理由与文件单列。
> 权威：根 `AGENTS.md` §1.1、§2.5、§3.5、§5.1、§5.2、§5.3，其次 `src/hc/compose/AGENTS.md` 与各子目录 `AGENTS.md`。
> 约束：未执行任何 git 命令；未放宽服务器命令去重规则；未删除既有测试。
> 运行环境：`/tmp/pixi` 0.83.0，Clojure 1.12.6，JDK 21（pixi env `default`）。

## 0 结论摘要

1. **Owner 串行、四步领取、P0–P4、Nudge、report-pending、分层重试、fleet join** 的实现与协议一致；关键不变量都有真实线程/真实文件/真实 Owner 测试覆盖。
2. 审查发现并修复 **两个 compose 缺陷**：
   * **取消域漏掉模型流**：`:dispatching`/`:running` 的模型 attempt 在取消树里没有 `:resource-alive?`，旧 `abort-effect` 只对 live resource 发 abort，导致 Esc 后模型 HTTP 流继续运行，直到 Owner 停止才被 `dispatch/stop!` 收掉。修复后每次模型 attempt 取消都产生一条 `:abort-resource`（`:scope :model-stream`），由 dispatcher 调用 gateway 的 `:cancel!`。
   * **非副作用节点的 live 标记推导错误**：`cancel.model/add-node` 只按状态推导 `:resource-alive?`，会把纯看板 action 和模型 attempt 标成 live，可能永久阻塞 `finish-run`。修复后只有 `:side-effecting?` 非 false 的节点才按状态推导。
3. **D7（停止命令被服务器拒绝）根因在客户端**：会话 reducer 用顺序 `cmd-N` 作为 `command-id`。页面重载或第二个客户端会从 `cmd-1` 重新计数，而 Owner 的 receipt 按 epoch 保留；同一 id 换停止载荷时服务器按 §5.1 正确拒收。修复为每次命令生成 `cmd-<uuid>`，并在入队时冻结停止载荷的 `:run-id`。服务器去重规则未改。
4. 全量测试最终结果见 §5；`-X:test:compose` 单独运行会因缺少 server 依赖在加载 `hc.server.storage.engine.datalevin` 时失败，因此使用仓库 `pixi.toml` 的 `test` 等价别名集 `:test:compose:server:client`。

## 1 八项审查结论表

| # | 审查项 | 结论 | 关键证据 |
|---|---|---|---|
| 1 | Owner 串行：每 session 唯一状态入口；命令按服务器接受顺序；commit 后重新评估守卫；不存在多写者 | **PASS** | `owner/mailbox.clj:76` 单队列单线程；`owner/runtime.clj:560` 每轮只从已提交状态 step，`runtime.clj:608` commit 成功后才 `after-commit` 并递归；`owner/commands.clj:92-115` 按 `command-id` 幂等；`test/hc/compose/owner/mailbox_test.clj`、`guards/owner_integration_test.clj`（claim 先提交后派发） |
| 2 | 守卫四步：`:enabled?` 纯读；`:prepare` 纯数据；提交后派发；领取即写 `:dispatching`/`:claimed` + 唯一 `attempt-id` | **PASS** | `guards/scheduler.clj:108` guard 契约；`:420` step 只返回 `:state`/`:claim`；`:460` after-commit 才释放 dispatch；`guards/claim.clj:175` 校验 state 已记录 attempt-id、无 claim 不得 dispatch；`guards.clj` 无 `swap!`/`future`/I/O；`guards/scheduler_test.clj`（`dispatch-waits-for-commit`、`claim-must-not-record-a-different-attempt-id`、`dispatch-effects-carry-the-claimed-attempt`） |
| 3 | P0–P4 完整；P1 含 holder 无责退休与接力屏障；每轮配额与配额耗尽重入队 | **PASS** | `guards/priority.clj:17-21`；`owner/guards.clj:2233` 目录（P0 取消/迟到节点，P1 终态/报告/接力，P2 重试，P3 Nudge/领取）；`guards/scheduler.clj:446` quota-exhausted + `:quota-requeue`；`owner/runtime.clj:668-677` 重入队；`runtime.clj:720-730` 显式 `:BLOCKED`；holder 退休在 `mission/host.clj:1128-1155` 的同一 board 转换中完成（`mission/host_test.clj:761`） |
| 4 | 取消域递归传播；`:applying` 最小提交完成；不穿透 DB/FileChannel；五种原因分类 | **PASS（修复后）** | `cancel/reason.clj:10-22` 五种原因；`cancel/model.clj:368` 广度优先；`cancel/transition.clj:237` `:applying` 只记 `:cancel-after-apply?`；`:246-258` 模型流也发 abort；`owner/runtime.clj` 无 `Thread/interrupt`；`test/hc/compose/cancel_test.clj` 新增模型流 abort 测试；`workspace_test.clj:756` 应用期取消仍落盘并发布 |
| 5 | Nudge：非出口自然停止必拦截；S1/初任空账/终局不注入；worker 用自身责任+全局可推进；`:awaiting-user` 抑制 | **PASS** | `nudge/policy.clj:186` legal-exit；`:241` worker-message 只列责任与可推进原因；`:483` decide；`:100` observation 让 committed `:awaiting-user?` 优先；`owner/guards.clj:2071` settle-turn；`nudge/owner_integration_test.clj` 24 tests/162 assertions（含 `plan-s1-ask-suppresses-real-nudge`、`execution-initial-ask-suppresses-real-nudge`、`ordinary-natural-stops-keep-nudging`） |
| 6 | report-pending 零丢失：子方持久化终态+report-id+parent-call-id；父方幂等接受一次并确认；父终止也确认；重启重投；LLM 全文内联 | **PASS** | `reports/report.clj:77-130` 拒绝截断/摘要/artifact；`reports/delivery.clj:63` register、`:163` apply-ack、`:222` reclaim；`reports/acceptance.clj:88` accept-report 按 parent-call-id 幂等，终止父返回 `:parent-terminated`；`owner/guards.clj:1034-1165` 投递/重试/唤醒；`reports/protocol_test.clj:127` ack 丢失后重投只 append 一次 |
| 7 | 分层重试：max-attempts 含首次 / max-retries 不含；`:retry-wait` + 抖动 `:retry-at`；次数+总截止双预算；永久错误立即失败；已发布工具副作用先协调；用户取消不重试；`:outcome-unknown` 不自动重跑 | **PASS** | `retry/policy.clj:15-30` 两个独立上限；`retry/planner.clj:350-395` 双预算与固定 `:retry-at`；`:143` delay+jitter；`retry/error.clj:165` 分类（auth/quota/input-too-long 永久或 context；`partial?`/`published-call-ids` → reconcile）；`planner.clj:270-300` 取消不重试；`:320-336` outcome-unknown；`retry/planner_test.clj` |
| 8 | 装配一致性：fleet/mission/scheduler/workspace 与 §5.3 fork/join 队列语义 | **PASS** | `fleet/state.clj:660` join-state（FIFO/blocked/none/no-results）；`fleet/control.clj:1015` join 每次取队首、abandon 后 `recur`、队列空且有 running 时 `:blocked`；`:1153` fork 的 `:INSUFFICIENT_WORKTREES`；`mission/worktree.clj` 只按 tenure/workspace 精确选运行时；`scheduler/service.clj` 与 `workspace.clj` 的装配边界；`fleet/control_test.clj`（`join-is-fifo...`、`join-blocks-until...`、`join-returns-no-results...`） |

## 2 逐项证据

### 2.1 Owner 串行与命令顺序

- **单写者**：`hc.compose.owner.mailbox` 的 `submit!`（`owner/mailbox.clj:76`）只把消息放入 `LinkedBlockingQueue`；`run-one!`（`:135`）只在一个 mailbox 线程上调用 handler；`stop!`（`:175`）不中断线程。`owner/runtime.clj:921` 的 `start!` 为每个 Owner 建一个 mailbox，`runtime.clj:1056` 的 `submit!` 是唯一入口。
- **命令按接受顺序**：`submit-command!`（`runtime.clj:1064`）把 envelope 投进同一 mailbox 并等待私有 promise；`owner/commands.clj:92-115` 在 Owner 事务内比较 `:receipts`，同 id 同载荷返回原回执（`:replayed? true`），同 id 不同载荷 `:COMMAND_ID_PAYLOAD_CONFLICT`。多客户端共享同一 session Owner，因此顺序就是服务器接受顺序。
- **commit 后重新评估**：`run-guards!`（`runtime.clj:560`）每次 `scheduler/step` → `store/commit!`（`:608`）→ `after-commit` → `ack-dispatch`，然后 `recur` 重新从已提交状态评估；版本冲突时 `reload!` 并重放（最多 8 次），不派发未提交状态。
- **实跑**：`hc.compose.owner.stop-command-test` 的 `same-id-same-payload-replays-the-original-receipt` 断言同载荷重放 `:replayed? true` 且会话只追加一次；`hc.compose.guards.owner-integration-test/claim-is-committed-before-model-dispatch` 断言 gateway 被调用时 call 已是 `:dispatching` 且 attempt-id 一致。

### 2.2 守卫四步与领取原子性

- `scheduler/guard`（`guards/scheduler.clj:108`）只接受函数形式的 `:enabled?`/`:prepare`；`step`（`:420`）只返回 `:state` 与 `:claim`，dispatch 效果保存在 `:pending-dispatch` 中，`after-commit`（`:460`）才释放。
- `claim/validate-plan`（`guards/claim.clj:175`）检查：`state'` 的 `:path` 已变为 `:claimed`/`:dispatching`，`:attempt-path` 已写入 scheduler 分配的 attempt-id，dispatch 效果携带同一 attempt-id；无 claim 的 dispatch 直接 `:DISPATCH_WITHOUT_CLAIM`；状态与 dispatch 都不变的 plan 被 `:NO_PROGRESS` 拒绝。
- 守卫纯度：`grep -n "swap!|reset!|future|Thread|io/|dispatch/|store/|System/|random-uuid|shuffle" src/hc/compose/owner/guards.clj src/hc/compose/guards/*.clj` 无命中；`guards/owner_integration_test.clj` 的 `no-hidden-512-step-cap`、`quota-requeue-processes-every-round` 覆盖运行时配额。
- **实跑**：`hc.compose.guards.scheduler-test` + `hc.compose.guards.owner-integration-test` + `hc.compose.fleet.control-test` 共 87 tests / 428 assertions，0 failures / 0 errors。

### 2.3 P0–P4、配额与责任闭合

- 优先级常量与旋转：`guards/priority.clj:17-21`、`:56`。
- Owner 目录：`owner/guards.clj:2233-2261`。P0：`accept-operator-cancel`、`cancel-late-nodes`；P1：`accept-call-terminal`、`recover-lost-model-terminal`、`project-tool-result`、`finish-cancelled-run`、报告四守卫、`relay-barrier`；P2：`plan-retry`、`claim-due-retry`；P3：`wake-actors`、`open-successor-run`、`open-user-turn`、`claim-model-request`、`claim-tool-call`、`settle-turn`。P4 由 scheduler 在“任期非终态、无在途、无定时等待、无启用守卫”时合成（`guards/scheduler.clj:360-395`）。
- 配额：`scheduler/step` 在 `:used >= :quota` 且仍有候选时返回 `:quota-exhausted` + `:quota-requeue`（`guards/scheduler.clj:446`）；`runtime.clj:668-677` 调用 `finish-round!` 并 `submit-wake! :quota-requeue`，不睡死。
- 死胡同：`runtime.clj:720-730` 记录 `:BLOCKED` 诊断与 `:owner/blocked`，并按签名去重（`blocked-signature` `:488`）。
- holder 无责退休：`mission/host.clj:1128-1155` 在同一 board 结果里标记 `:dead` 并发出 `:swarm/holder-retired`；`mission/host_test.clj:761` 断言空 holder 在同一转换退休。接力屏障：`owner/guards.clj:1214` 调 `mission/apply-command {:op :maybe-relay}`。
- **实跑**：`guards/owner_integration_test.clj` 的 `quota-requeue-processes-every-round`、`no-hidden-512-step-cap`、`blocked-diagnostic-is-committed`、`prepared-dispatch-is-blocked-by-pending-stop` 全部通过（含在上面的 87/428 中）。

### 2.4 取消域（含本次修复）

- 原因分类：`cancel/reason.clj:10-22` 固定五种；操作员来源仅 `:operator-esc`/`:operator-stop`；程序化 `:park`/`:ask`/`:relay-retirement`/`:slot-reincarnation` 不是取消。
- 递归：`cancel/model.clj:368` 广度优先；`cancel.clj:397` `accept-operator-cancel` 关闭 admission、记录意图、传播 run 子树；每个节点保留 `:cancel-root-reason`，后代 `:parent-terminated`（`cancel/transition.clj:122`）。
- `:applying`：`cancel/transition.clj:237` 保持 `:applying` 并记 `:cancel-after-apply?`，不发 abort；`workspace_test.clj:756` 的 `cancel-during-apply-completes-the-fixed-batch` 在真实文件系统上断言 `:APPLIED`、文件内容为 `new`、动作 `:finished`（已发布）、`$W$` 已释放。
- **修复 1：模型流 abort**。旧代码 `abort-effect` 只在 `:resource-alive? true` 时发 abort，而模型 call/attempt 的该标记恒为 false（模型是流不是持久资源），所以 `accept-operator-cancel` 对模型 attempt 不产生任何效果。实跑复现：停止后 run 已 `:cancelled`、call 记录已 `:cancelled`，但 dispatcher `:live` 仍为 1，gateway `:cancel!` 从未被调用。修复：`cancel/transition.clj:89-115` 新增 `model-stream-effect`，对 `:side-effecting? false` 且 `:dispatching`/`:running` 的 attempt 节点发一条 `:abort-resource`（`:scope :model-stream`，携带 `:call-id` 与 `:attempt-id`）；`cancel-call-like` 的 abort 取 `(or (abort-effect node) (model-stream-effect node))`。`cancel/transition.clj:246-258` 同时不再用 `allow-retry-wait?` 给非副作用 call 伪造 `:resource-alive?`，因此模型取消不会阻塞 `finish-run`。
- **修复 2：非副作用节点推导**。`cancel/model.clj:294-304` 的 `add-node` 现在只在 `:side-effecting?` 非 false 时按状态推导 `:resource-alive?`，避免纯 board action / 模型 attempt 被误标为 live。
- **隔离**：`owner` 包内没有 `Thread/interrupt`；`owner/dispatch.clj:754` 的 `cancel!` 只取消 Missionary task/调用 gateway `:cancel!`；`owner/mailbox.clj:1-10` 明确 stop 不中断 mailbox；DB 提交在 mailbox 线程、workspace `FileChannel` 写入在 `workspace-gate` 的独立路径，取消只发协作式 `request-stop!`。
- **实跑**：
  * `hc.compose.cancel-test` 新增 `a-live-model-attempt-is-aborted-without-blocking-quiescence`：断言模型 attempt 收到 `:abort-resource`（`:model-stream`、`call-id`、`attempt-id`、不 dispose、不 interrupt），call 节点不重复发，run 可静默关闭。
  * `hc.compose.cancel-test` + `hc.compose.owner.stop-command-test`：40 tests / 346 assertions，0/0。
  * `hc.compose.cancel-integration-test` + `hc.compose.owner.runtime-test`：27 tests / 163 assertions，0/0（真实受管 Shell 停止、run 关闭）。
  * `hc.compose.workspace-test`：30 tests / 149 assertions，0/0。

### 2.5 Nudge

- 合法出口：`nudge/policy.clj:186-240` 的 `legal-exit` 覆盖 `:terminal-completed`、`:handoff-complete`、`:plan-s1-ask`、`:execution-initial-ask`、`:user-wait`；`:awaiting-user?` 优先（`:100-160`）。
- 非出口自然停止：`decide`（`:483`）在角色/车道合法、任期 active、无合法出口、无 in-flight、Reviewer 不在 swarm 窗口时，central 走 `central-decision`，worker/holder 走 `worker-decision`；worker 文本只用 `board/responsibility` 与 `board/progressable-reasons`（`:241-270`），不读 review 台账。
- Owner 拦截：`owner/guards.clj:2071` 的 `settle-turn` 在 `:turn.ended?`、无 pending turn/cancel、无该 agent 的 pending/live call 时启用；`:prepare` 调 `nudge/decide`，合法出口/取消/关闭 admission 都清 turn 并记 `:turn-history`；enabled decision 创建 `:nudge?` 模型 call。
- **实跑**：`hc.compose.nudge.owner-integration-test` 24 tests / 162 assertions，0/0；其中 `plan-turn-end-dispatches-nudge`（非出口自然停止被拦截并注入）、`plan-s1-ask-suppresses-real-nudge`、`execution-initial-ask-suppresses-real-nudge`、`worker-awaiting-user-suppresses-real-nudge`、`ordinary-natural-stops-keep-nudging` 直接覆盖清单。

### 2.6 report-pending 零丢失

- 子方记录：`reports/report.clj:149` 校验并只保留协议字段；`:77-130` 对 `:llm-report` 拒绝 `:truncated?`、`:artifact*`、`:summary`，全文必须内联；raw tool output 才允许带字节数的显式截断。
- 投递状态机：`reports/delivery.clj:63` `register` 幂等（同 report 同载荷返回 `:existing`；同 id 不同内容 `:REPORT_ID_CONFLICT`；同 parent-call-id 第二份 `:PARENT_CALL_CONFLICT`）；`:133` `next-action` 先 reclaim 后 deliver；`:163` `apply-ack` 首个 ack 转 `:acknowledged`，重复 ack 幂等，`:222` `mark-reclaimed` 只接受已 ack。
- 父方：`reports/acceptance.clj:88` 按 `:parent-call-id` 只接受一次；重复投递返回同一 ack 且 `:append? false`；父已 `terminate` 或 `:call-active? false` 返回 `:parent-terminated` 并仍给 ack；`mark-projected` 幂等。
- 重投：`owner/guards.clj:1093-1165` 用 `:retry-wait` + 指数 `:retry-at`（`owner/state.clj:41-47`，250ms 起、5s 上限，无次数上限），成功投递后 5s ack 超时回到 `:pending`；启动恢复重建 pending claim（`owner/state.clj` 的 `recover-state` 报告 claim 段）。
- **实跑**：`hc.compose.reports.protocol-test`（3 tests / 9 assertions，0/0，含 `ack-loss-redelivers-and-appends-once`、`a-terminated-parent-lets-the-child-close`）；`reports.delivery-test` + `reports.acceptance-test` + `retry.*` 共 77 tests / 392 assertions，0/0。

### 2.7 分层重试

- 双上限：`retry/policy.clj:15-30` 默认 `:max-attempts 4` / `:max-retries 3`；`:38-120` 只给一个时按一一关系推导另一个，两个都给则独立保留。
- 计划：`retry/planner.clj:481` `plan-retry` 检查 terminal/cancelled/ready/已计划；`:350-395` 先查 attempts、retries、总 deadline，再算 jitter（`:143` `deterministic-jitter` + `delay-ms`），`retry-at >= deadline` 直接失败；`:retry-at` 固定后重复失败不移动。
- 分类：`retry/error.clj:165` 的 `classify` 把 `:auth`/`:permission`/`:insufficient-quota`/`:invalid-model` 等映射为 permanent，`:input-too-long`/`:context-length-exceeded` 映射为 context，`:outcome-unknown` 映射为 reconcile；`partial-effects?`（`:155-163`）让带 `:partial?`/`:published-call-ids`/`:published-calls` 的失败成为 reconcile，阻止重放已发布的工具副作用。
- 取消与未知：`planner.clj:270-300` 用户取消返回 `:cancelled`（有 pending/unknown effects 时返回 `:outcome-unknown`）；`:320-336` reconcile 错误返回 `:outcome-unknown`，不自动重跑；`:claim-due`（`:613`）再次检查双预算、deadline、到期时间与 effects 状态。
- **实跑**：`hc.compose.retry.planner-test`、`error-test`、`policy-test` 与报告测试合计 77 tests / 392 assertions，0/0；planner 测试含 `published-tool-calls-block-model-retry`、`cancellation-never-retries`、`retry-at-is-bounded-and-fixed`。

### 2.8 装配一致性

- fleet join：`fleet/state.clj:660` 的 `join-state` 依次判断 FIFO 队首（`:ready`）、integrating 中的队首（`:blocked`）、orphan（`:deadlock`）、active running（`:blocked`）、未就绪 worktree（`:blocked`）、队列（`:deadlock`）、无 joined 但 terminal（`:no-results`）、否则 `:none`。`fleet/control.clj:1015` 的 `join!` 每次只 integrate 队首；`:abandoned (recur)` 丢弃该结果继续；`:joined` 返回真实 ff 结果；`:none` 只在无 running 时出现。`fork`（`:1153`）资源不足返回 `:INSUFFICIENT_WORKTREES`；`abandon-swarm`（`state.clj:423`）不入 completion queue。
- mission/worktree：`mission/worktree.clj` 按 `:tenure-id` → 精确 `:workspace` → 唯一运行时选择，拒绝 `:WORKSPACE_MISMATCH`，holder 强制 `:clj-worker`。
- scheduler：`scheduler/service.clj` 的 `:start-run`/`:stop-run`/`:notify` 只在提交后派发；`scheduler/core.clj` 的错过不补跑、重叠 `:skipped`、preflight 失败 `:needs-attention` 均按其 AGENTS 与测试。
- workspace：`workspace.clj` 的 `make-runtime` 只接受成对的 Owner 看板句柄；`$W$` 由整个 swarm 共享；`finish` 的非空 read-set 在 `$W$` 内复核；`board/owner-adapter` 通过 Owner 邮箱而不是第二内核写入。
- **实跑**：`hc.compose.fleet.control-test`（含在 87/428 中）覆盖 FIFO、blocked、`:none`、`:no-results`、abandon 只影响自身、fork 不足；`hc.compose.scheduler.core-test` + `service-test` + `smoke-test` + `hc.compose.workspace-integration-test` 共 47 tests / 433 assertions，0/0。

## 3 D7 专项：停止命令被服务器拒绝

### 3.1 现象与证据

- 现象：点击“停止无人值守运行/停止当前生成”后提示“停止请求未送达”；服务器回执 `command-id was already accepted with a different payload`；会话保持“正在生成”，Nudge 继续发请求。
- 原始证据：`/tmp/hc-e2e/20261105-0811-exec/E2E-01/stop-command.log`、`e2e01-stop.png`、`e2e01-reopen.png`；`plans/e2e-0.0.1-results.md` §4 D7。

### 3.2 根因

客户端会话 reducer 的 `enqueue-command` 用 `(str "cmd-" n)` 生成 id，`n` 来自本地 `:next-command-id`，初始为 1。Owner 的 receipt 按 `command-id` 保存在整个 active `command-epoch` 内（`owner/commands.clj:92-115`），而页面重载或第二个浏览器会重新从 `cmd-1` 计数。于是：

1. 客户端 A 先发送 `cmd-1`（`send-message` 载荷），Owner 接受并保存 receipt。
2. 页面重载/客户端 B 的第一次停止仍是 `cmd-1`，但载荷是 `stop-run`。
3. 服务器按 §5.1 正确拒收“同 id 不同载荷”（`:COMMAND_ID_PAYLOAD_CONFLICT`），停止没有进入 `:pending-cancels`，run 继续运行，Nudge 继续。
4. 另一个放大因素：停止 wire payload 的 `:run-id` 在 drain 时从当前 db 读取，同一命令若被重发可能得到不同载荷；虽然当前客户端不主动重发，但这会让“幂等重放”依赖外部时序。

这不是服务端缺陷；放宽去重会掩盖客户端 id 冲突并可能重放错误回执。

### 3.3 修复

- `src/hc/client/conversation.cljc:101-121`：`command-id` 改为 `(str "cmd-" (random-uuid))`。`:sequence` 仍是本地顺序，不进入服务器语义。UUID 在页面重载、多客户端、多 session 之间都不重复。
- `src/hc/client/conversation.cljc:250-270`：`enqueue-stop` 在入队时把当前 stream 的 `:run-id` 冻结进命令 payload。
- `src/hc/client/workbench/conversation_bridge.cljs:103-126`：`:stop` 的 wire payload 只使用命令中冻结的 `:run-id`，不再在 drain 时重读 db；`wire-payload`/`send-envelope!` 去掉不再使用的 `db` 参数，并移除未使用的 `model` require。
- 服务端 `src/hc/compose/owner/commands.clj` **未改**：同 id 同载荷返回原回执，同 id 不同载荷拒收。

### 3.4 验证

- `test/hc/compose/owner/stop_command_test.clj`（新增）：
  * `stop-after-send-needs-a-fresh-command-id`：真实 Owner + 真实 mailbox + 持有不返回的 model gateway。先以 `cmd-send` 发送并等待模型 attempt 注册；再用同一 `cmd-send` 发 `:stop-run`，断言 `:refused` 且 `:COMMAND_ID_PAYLOAD_CONFLICT`（规则未被放宽）；再用 `cmd-stop` 发停止，断言 `:accepted`、run 进入 `:cancelling`/`:cancelled`、gateway `:cancel!` 被调用；最后提交 `:turn/ended`，断言没有 `:nudge?` call，且 `:turn-history` 记录 `:operator-cancelled` 或 `:admission-closed`。
  * `same-id-same-payload-replays-the-original-receipt`：同 id 同载荷返回 `:replayed? true`，会话不重复追加。
- `test/hc/client/conversation_test.clj`：`command-envelope-is-stable` 改为断言 `cmd-<uuid>` 形状与 `:sequence`；新增 `command-ids-do-not-repeat-across-client-instances`，用两个独立 reducer 实例（模拟重载/第二客户端）分别产生 send 与 stop，断言 id 不同。
- 实跑：`hc.compose.owner.stop-command-test` 2 tests / 15 assertions，0/0；`hc.client.conversation-test` 47 tests / 503 assertions，0/0。

## 4 改动文件

| 文件 | 改动 | 理由 |
|---|---|---|
| `src/hc/compose/cancel/transition.clj` | 新增 `model-stream-effect`；`:dispatching`/`:running` 的模型 attempt 发 `:abort-resource`；取消时不再给非副作用 call 伪造 `:resource-alive?` | 取消域必须中断模型流；模型流不应阻塞 `finish-run` |
| `src/hc/compose/cancel/model.clj` | `add-node` 只对 `:side-effecting?` 非 false 的节点按状态推导 `:resource-alive?` | 纯 board action/模型 attempt 不是持久资源 |
| `src/hc/client/conversation.cljc` | `command-id` 改 UUID；停止命令冻结 `:run-id` | D7 根因；保证 §5.1 幂等 |
| `src/hc/client/workbench/conversation_bridge.cljs` | 停止 wire payload 用冻结的 `:run-id`；清理未用参数/require | D7 次因；避免同一 id 的载荷漂移 |
| `src/hc/client/conversation/AGENTS.md` | 命令信封示例改为 `cmd-<uuid>` | 文档与实现一致 |
| `test/hc/compose/cancel_test.clj` | 新增模型流 abort 测试 | 覆盖修复后的取消域 |
| `test/hc/compose/owner/stop_command_test.clj` | 新增 D7 服务器路径回归 | 证明停止被接受、run 关闭、Nudge 抑制，同时保留冲突拒收 |
| `test/hc/compose/workspace_test.clj` | `cancel-during-apply-completes-the-fixed-batch` 增加“动作已 `:finished` 发布”断言 | 交错场景 (a) 的发布证据 |
| `test/hc/client/conversation_test.clj` | 更新 id 断言；新增跨客户端唯一性测试 | D7 客户端侧回归 |
| `plans/compose-audit-0.0.1.md` | 本报告 | 审查产出 |

客户端改动是 D7 的必要最小修复（`src/hc/compose/**` 无法改变客户端的 id 生成）；未改 `src/hc/server/**`。

## 5 验证记录

### 5.1 别名集说明

- 任务给出的 `clojure -X:test:compose` 单独运行会在加载 `hc.server.storage.engine.datalevin` 时失败：`FileNotFoundException ... datalevin/core__init.class`。`test` 别名扫描整个 `test/`，其中 server 测试需要 `:server` 依赖。
- 因此使用 `pixi.toml` 的 `test` 等价别名集：`/tmp/pixi run clojure -X:test:compose:server:client`（与 `pixi run test` 相同）。定向测试用 `-M:test:compose` 或 `-M:test:client` 加 `-n`。

### 5.2 全量

| 命令 | tests | assertions | failures | errors | 退出码 | 备注 |
|---|---:|---:|---:|---:|---:|---|
| `/tmp/pixi run clojure -X:test:compose:server:client`（改动前基线，2026-11-05） | 3406 | 22121 | 0 | 0 | 0 | 运行 416.21s；期间源码仍在编辑，作为基线参考 |
| `/tmp/pixi run clojure -M:test:compose:server:client`（最终，2026-11-05） | 3419 | 22196 | 0 | 0 | 0 | 365.24s；首次 `-X` 复跑在 `hc.server.storage.engine-lifecycle-test` 的“Datalevin stores still open before the test”处中止（泄漏目录来自 `hc.server.app.system-test`/`restore-supervisor-integration-test` 的临时库）；该两命名空间单独运行 19 tests/135 assertions 0/0，随后全量 `-M` 复跑 3419/22196 0/0 通过。判定为既有全量测试的存储清理抖动，与 compose 改动无关 |

`-X` 入口的 `hc.test-runner/test` 返回结果 map，`clojure -X` 按进程约定退出 0；失败以结果 map 的 `:fail`/`:error` 为准。定向命令使用 `-M` 的 Cognitect runner（失败时非零退出），下表记录了实际退出码。

### 5.3 定向证据

| 命令/命名空间 | tests | assertions | failures | errors | 退出码 | 覆盖 |
|---|---:|---:|---:|---:|---:|---|
| `hc.compose.cancel-test` + `hc.compose.owner.stop-command-test` | 40 | 346 | 0 | 0 | 0 | 取消域 + D7 服务器路径 |
| `hc.client.conversation-test` | 47 | 503 | 0 | 0 | 0 | D7 客户端 id + 停止语义 |
| `hc.compose.workspace-test` | 30 | 149 | 0 | 0 | 0 | (a) `:applying` 取消仍落盘并发布 |
| `hc.compose.reports.protocol-test` | 3 | 9 | 0 | 0 | 0 | (b) ack 丢失后重投只接受一次 |
| `hc.compose.nudge.owner-integration-test` | 24 | 162 | 0 | 0 | 0 | (c) 非出口自然停止被 Nudge 拦截；S1/初任空账/终局抑制 |
| `hc.compose.cancel-integration-test` + `hc.compose.owner.runtime-test` | 27 | 163 | 0 | 0 | 0 | 真实受管 Shell 停止、run 关闭、停止后再发送 |
| `hc.compose.fleet.control-test` + `hc.compose.guards.scheduler-test` + `hc.compose.guards.owner-integration-test` | 87 | 428 | 0 | 0 | 0 | 四步、配额、P4、join 队列 |
| `hc.compose.reports.delivery-test` + `acceptance-test` + `retry.*` | 77 | 392 | 0 | 0 | 0 | 报告状态机、父方幂等、重试双预算 |
| `hc.compose.scheduler.core-test` + `service-test` + `smoke-test` + `hc.compose.workspace-integration-test` | 47 | 433 | 0 | 0 | 0 | 装配一致性、真实文件程序路径 |

### 5.4 CLJS 编译检查

```bash
/tmp/pixi run clojure -M:cljs:client -d /tmp/cljs-check \
  -c hc.client.workbench.conversation-bridge
```

退出码 0；只有仓库既有的 `hc.client.conversation.export` 保留字警告。

## 6 残留缺口（NOT COVERED）

1. **真实供应商模型流中断**：compose 只验证 Owner → `:model-gateway` 的 `:cancel!` 信号与 dispatcher 的 attempt 取消；没有真实 LangChain4j/HTTP 流被中断的集成测试。适配器层需另测。
2. **DB 提交/FileChannel 线程隔离的故障注入**：结构上 Owner 不使用 `Thread/interrupt`，mailbox 停止不中断线程，workspace 停止是协作式；但没有在真实 Datalevin 提交或 `FileChannel` 写入中途注入取消的测试。此项按代码结构与既有 workspace 测试判定，未做运行时注入。
3. **真实多客户端 Sente 双标签页**：D7 的服务器路径与客户端 id 唯一性已单测；未重跑真实浏览器双客户端旅程。
4. **Owner + 真实文件系统的 Esc 应用期取消**：`:applying` 取消由 `workspace_test`（真实临时文件 + 真实 `$W$`）与 `cancel_test`（纯状态机）覆盖；没有把 Esc 命令、真实 Owner mailbox、真实 FS 应用串成一条端到端测试。
5. **D7 真实浏览器停止按钮**：未重跑 Playwright/真实 app 的“发送→点击停止”旅程；本次以真实 Owner mailbox 的服务器路径与客户端 reducer 单测作为证据。
6. **fleet 真实 swarm 进程**：`fleet.control-test` 使用真实临时 Git 仓库与真实 worktree/rebase/ff，但 swarm 的模型进程与 launch 是测试替身；真实多 worktree 蜂群施工未跑。
7. **scheduler（定时任务）域**：只运行其自身测试与装配边界检查，未重审 DST/时区/真实设备切换等细节；该域不在本次 compose 协议八项的核心路径内。
8. **性能/规模**：未做长时无人值守、配额高压或大报告体积的基准测试。

## 7 复现命令

```bash
# 全量（仓库 test 别名集）
/tmp/pixi run clojure -X:test:compose:server:client

# 定向
/tmp/pixi run clojure -M:test:compose -n hc.compose.cancel-test \
  -n hc.compose.owner.stop-command-test
/tmp/pixi run clojure -M:test:client -n hc.client.conversation-test
/tmp/pixi run clojure -M:test:compose -n hc.compose.workspace-test
/tmp/pixi run clojure -M:test:compose -n hc.compose.reports.protocol-test
/tmp/pixi run clojure -M:test:compose -n hc.compose.nudge.owner-integration-test

# CLJS 编译检查
/tmp/pixi run clojure -M:cljs:client -d /tmp/cljs-check \
  -c hc.client.workbench.conversation-bridge
```

## 8 多客户端 tmux 语义（根 §5.1）

> 本轮以 `src/hc/compose/**` 与测试为主；另有两处最小跨层改动，均记录在 §8.6：客户端把 `:NOT_CONTROLLER` 映射为可读提示；服务端 app router 的 `conversation-kinds` 增加三个 presence kind，否则浏览器命令到不了 Owner。

### 8.1 五项状态

| # | 要求 | 状态 | 证据 |
|---|---|---|---|
| 1 | 会话控制者：`:session/controller-id`、观察者集合；第一个客户端控制；接管转移；原控制者被拒并可显示“控制已转移” | **已实现** | `owner/state.clj:179-180` 新增 `:controller-id`/`:observer-ids`；`owner/handlers.clj:1027-1160` 的 attach/takeover/detach；`:1257-1264` 的 `:NOT_CONTROLLER` 门；客户端 `conversation.cljc:344-346,369-373,393-395` 把 `:NOT_CONTROLLER` 映射为“另一客户端正在控制本会话；请先接管控制” |
| 2 | 命令去重与 epoch：同 id 同载荷回原回执；同 id 不同载荷拒收；epoch 退休返回 `:COMMAND_EPOCH_RETIRED` 且不自动执行 | **既有实现，已补多客户端验证** | `owner/commands.clj:92-115`；多客户端测试 `epoch-retirement-refuses-old-commands-without-executing`、`command-id-replay-and-conflict-include-the-client` |
| 3 | 关闭页面 ≠ 停止：卸载订阅不取消 run；重开同步真实状态；另一窗口已停止时显示停止结果而不是重启 | **已实现（presence-only）** | `detach-client` 只改 presence，不产生 cancel/outbox；`detach-never-stops-and-reopen-reads-the-real-run` 断言 run 仍 `:running`、gateway 未收到 cancel、重开 attach receipt 报 `:run-state`，停止后重开报 `:cancelled` 且模型请求数仍为 1 |
| 4 | 观察者越权：观察模式不能提交发送/停止/接管/批准；明确拒绝而非静默丢弃 | **已实现** | `controller-allowed?`（`owner/handlers.clj:1141-1152`）只放行三种 presence 命令；`observers-cannot-submit-session-work` 覆盖 stop/answer/review/board/issue-epoch，全部 `:NOT_CONTROLLER` 且状态不变 |
| 5 | 缺失时补最小完整闭环：状态字段 + 守卫/命令入口校验 + 测试 | **已补齐** | 状态字段、命令入口校验、7 个多客户端测试；`owner/AGENTS.md` §5.0 与不变量 13 记录契约 |

关于“接管”：观察者不能通过普通发送/停止/审批命令隐式取得控制权；唯一的控制转移入口是显式的 `:session/takeover`，这是 §5.1 “明确接管后控制权转移”的实现。所有其他观察者命令都在命令门前返回 `:NOT_CONTROLLER`。

### 8.2 控制者门与状态字段

- `:client-id` 来自网关 envelope（`src/hc/server/gateway.clj:470-477` 已传 `:client-id` 与 `:uid`）；Owner 优先用 `:client-id`，回退 `:uid`，两者都没有时视为内部/旧版命令，不做 presence 围栏。
- 第一个提交会话工作的客户端在同一笔提交中被提升为 `:controller-id`；后续客户端进入 `:observer-ids`。
- `:session/attach-client`：首个客户端成为控制者；已控制者幂等返回 `:controller`；其他客户端成为观察者并返回 `:observer`；receipt 含 `:controller-id`、`:observer-ids`、`:run-id`、`:run-state`。
- `:session/takeover`：把控制权转给调用者；原控制者插到观察者队列首位；receipt 含 `:previous-controller-id`。
- `:session/detach-client`：从 presence 移除；控制者离开时提升第一位观察者，没有观察者时控制者为 nil；**不取消 run、不撤回命令、不改变业务状态**。
- 命令门 payload 现在包含 `:client-id`：不同客户端复用同一 `command-id` 会得到 `:COMMAND_ID_PAYLOAD_CONFLICT`，不会重放另一客户端的回执。
- 重启恢复（`state/restore`）清空 `:controller-id`/`:observer-ids`：presence 是连接范围状态，不是业务状态；旧控制者 id 不能围栏重启后第一个重连客户端。`restart-clears-connection-scoped-presence` 断言该行为。

### 8.3 命令门与 epoch

- 同 id 同载荷：返回原回执并标 `:replayed? true`（`owner/commands.clj:92-102`）。
- 同 id 不同载荷：`:COMMAND_ID_PAYLOAD_CONFLICT`，状态不变（`:105-112`）。
- epoch 退休：`:COMMAND_EPOCH_RETIRED`，details 含 `:current-epoch` 与 `:action :resync-state`；命令不会执行（`owner/commands.clj:69-80`）。
- presence 命令在重放时把当前 presence/run 快照合并进回执，避免旧回执让客户端误判当前控制者。

### 8.4 关闭页面与重开

- `detach` 与订阅卸载都不触碰 `:cancel`、`:calls`、`:outbox`；模型 attempt 的 gateway `:cancel!` 不会被调用。
- 重开 `:session/attach-client` 的 receipt 直接带 `:run-id`/`:run-state`；测试断言停止后的重开看到 `:cancelled`，且模型请求数不变（没有重启）。
- 观察者不能停止；只有当前控制者能停止。另一窗口停止后，观察者重开读到的是已停止状态。

### 8.5 观察者越权

- `controller-allowed?` 在命令门之前运行；观察者的普通命令返回 `{:code :NOT_CONTROLLER :details {:client-id ... :controller-id ... :action :takeover}}`。
- 拒绝发生在 gate 之前，因此不写 receipt、不改变 `:commands`、不产生业务副作用；测试逐项比较了拒绝前后的 committed state。
- 允许的三种 presence 命令是 attach/takeover/detach；takeover 是唯一的控制转移路径。

### 8.6 改动文件（本轮）

| 文件 | 改动 | 理由 |
|---|---|---|
| `src/hc/compose/owner/state.clj` | 初始状态新增 `:controller-id`、`:observer-ids`；`restore` 清空连接范围 presence | presence 权威状态；重启不围栏新客户端 |
| `src/hc/compose/owner/handlers.clj` | 新增三种 presence 命令、控制者门、`client-id` 进入 gate payload、扁平化 `:UNKNOWN_COMMAND_KIND`/`:NOT_CONTROLLER` 错误形状 | 最小完整闭环 |
| `src/hc/compose/owner/AGENTS.md` | §5.0 多客户端契约、状态字段、命令表、不变量 13 | 文档与实现一致 |
| `test/hc/compose/owner/multi_client_test.clj` | 新增 7 个真实 Owner 测试 | 五项验收 |
| `src/hc/client/conversation.cljc` | 把 `:NOT_CONTROLLER` 回执映射为“另一客户端正在控制本会话；请先接管控制”/“控制权在另一客户端；停止未发送” | 满足“界面可显示控制已转移”；最小客户端改动 |
| `test/hc/client/conversation_test.clj` | 新增 `observer-receipts-show-the-control-transfer` | 客户端文案回归 |
| `src/hc/server/app/commands.clj` | `conversation-kinds` 增加 `:session/attach-client`/`:session/takeover`/`:session/detach-client` | 最小路由改动；否则 app router 返回 `:COMMAND_KIND_UNKNOWN`，Owner 的新命令不可达 |
| `plans/compose-audit-0.0.1.md` | 本节 | 审查产出 |

服务端只改了这一行集合；未改 Owner、会话存储、快照投影或权限模型。

### 8.7 验证

| 命令/命名空间 | tests | assertions | failures | errors | 退出码 |
|---|---:|---:|---:|---:|---:|
| `hc.compose.owner.multi-client-test` | 7 | 77 | 0 | 0 | 0 |
| `hc.compose.owner.handlers-test` + `runtime-test` + `stop-command-test` + `multi-client-test` | 88 | 488 | 0 | 0 | 0 |
| `hc.compose.nudge.owner-integration-test` + `guards.owner-integration-test` | 41 | 257 | 0 | 0 | 0 |
| `hc.client.conversation-test` | 48 | 506 | 0 | 0 | 0 |
| 全量 `-M:test:compose:server:client` | 3439 | 22290 | 0 | 0 | 0 |

### 8.8 全量

本轮改动后的全量运行结果记录如下（命令：`/tmp/pixi run clojure -M:test:compose:server:client`；计数以 runner 输出为准）：

```bash
/tmp/pixi run clojure -M:test:compose:server:client
```

- tests: **3439**
- assertions: **22290**
- failures: **0**
- errors: **0**
- exit: **0**
- wall: **333.02s**

命令使用 `-M` 的 Cognitect runner（失败时非零退出），以便记录真实退出码；`-X` 入口的 `hc.test-runner/test` 返回结果 map，`clojure -X` 按进程约定退出 0。上一轮（多客户端改动前）全量为 3419 tests / 22196 assertions，0/0；本轮增加 20 tests / 94 assertions，来自 presence 命令、控制者门、重启清理与客户端文案测试。

### 8.9 残留缺口（NOT COVERED）

1. **服务端快照投影**：`:controller-id`/`:observer-ids` 已在 Owner committed state 与 attach/takeover 回执中，但 `src/hc/server/app/snapshot.clj` 的 session summary 尚未把它们投影成客户端 adapter 已声明的 `:session/controller-id`/`:session/observer-ids`。本轮只改了 app router 的 `conversation-kinds` 路由；快照投影未改。客户端在收到 `:NOT_CONTROLLER` 回执时已能显示控制转移提示。
2. **客户端自动 attach/takeover**：客户端尚未在连接时发送 `:session/attach-client`，也没有“接管控制”按钮；服务端命令契约与拒绝语义已完整，UI 入口属于后续客户端工作。未来客户端发送 presence 命令时必须在 payload 中带 `:session-id`，因为 app router 的 `handle-owner-command!` 用它定位 Owner。
3. **真实双标签页 Sente 旅程**：本轮用真实 Owner mailbox 与真实命令 envelope 验证，未跑两个真实浏览器标签页。
4. **控制者心跳/超时**：没有自动把长期离线控制者降级为观察者的超时策略；当前只有显式 detach 或 takeover。协议未要求，记为未实现策略。
5. **观察者只读命令**：Owner 的客户端命令都是写命令；观察者没有“只读查询”命令入口。快照/订阅仍可读，未新增只读命令。

## 9 工具准入拒绝后的回合收口（真实模型 E2E 07 线索）

### 9.1 现象与根因

真实模型调用 `devops` 时未带 `:scope`，`hc.core.scope.admission` 返回
`:SCOPE_ANNOTATION_MISSING`；Owner 的拒绝分支把 synthetic `:tool-call` +
`:tool` 结果写入 conversation，但没有 call 记录、没有计数，也没有终态诊断。
模型回合收到 `:completed` 后 `settle-turn` 仍会 Nudge，因此 UI 一直显示
“正在生成 / 无人值守 · 自动继续”；若模型始终不补 `:scope`，Nudge 会无限
重复，用户看不到可操作错误，也没有可诊断终态。

### 9.2 修复点

| 位置 | 改动 |
|---|---|
| `owner/state.clj` | 初始状态新增 `:tool-refusals {[tenure-id agent-id member] {:count n :last-code kw}}`；重启恢复保留该字段（它是当前态，不是连接态） |
| `owner/handlers.clj` | 拒绝分支对同一 key 累加计数；成功准入清除同一 key。拒绝仍只写 synthetic tool-call/tool 对，不创建可执行 call、不做 Scope settlement |
| `owner/guards.clj` | 新增 `tool-admission-refusal-limit`（3）与 `tool-refusal-limit`；`settle-turn` 在连续 3 次拒绝后记录 `:TOOL_ADMISSION_REFUSED_LIMIT`（`:remedy :fix-request`、member/count/last-code/可操作 message），清 turn、发 `:owner/blocked`，不再 Nudge |
| `owner/AGENTS.md` | 记录 `:tool-refusals`、拒绝不悬挂、3 次上限与可操作诊断 |

单次拒绝：模型回合结束后仍按 §2.5 注入一次 Nudge；Nudge 请求的 messages
携带 refusal 文本（`SCOPE_ANNOTATION_MISSING`），模型可据此补 `:scope`。
连续 3 次拒绝：前两次各注入一次 Nudge，第三次转为 blocked 诊断，不再发
第 4 次模型请求。`:awaiting-user` 仍只在真实 `ask` 时成立；拒绝不设置它。

### 9.3 测试证据

新增在 `test/hc/compose/nudge/owner_integration_test.clj`：

- `admission-refusal-injects-one-nudge`：脚本 gateway 发出无 `:scope` 的
  `devops` tool-call + `:completed`；断言 refusal 是可见 tool 结果、计数为 1、
  Nudge 存在且其请求 messages 含 `SCOPE_ANNOTATION_MISSING`。
- `repeated-admission-refusals-end-in-a-diagnostic`：每次请求都发拒绝；断言
  `:TOOL_ADMISSION_REFUSED_LIMIT`、`:count 3`、`:member "devops"`、
  `:last-code :SCOPE_ANNOTATION_MISSING`、`:remedy :fix-request`、
  `:owner/blocked`、turn 清空、Nudge 仅 2 个、无第 4 次请求。

实跑：

| 命令 | tests | assertions | failures | errors | 退出码 |
|---|---:|---:|---:|---:|---:|
| `hc.compose.nudge.owner-integration-test` | 29 | 192 | 0 | 0 | 0 |
| owner 回归（handlers/runtime/stop-command/multi-client/guards owner） | 105 | 583 | 0 | 0 | 0 |
| 全量 `-M:test:compose:server:client`（最终） | 3560 | 22972 | 3 | 0 | 1 |

全量 3 个失败都在 `hc.compose.scheduler.service-test` /
`scheduler.smoke-test`（`:missed` 计数为 0），属于
`src/hc/compose/scheduler/core.clj` 在 12:30 被并行编辑后的调度域问题；
不是 owner/refusal/actor 改动。`hc.compose.fleet.actors-test` 与
`fleet.service-test` 的 4 参数 `wake-all` 兼容问题已在最终运行前修复，
28 tests / 237 assertions 0/0。compose 定向套件全部 0/0。

### 9.4 与 server-audit 的分工

server-audit 负责给 `devops` schema 增加 `:scope` 并改进拒绝回执字段
（`:field`/`:expected`/`:hint`）；compose 负责“拒绝后不悬挂”：计数、
Nudge、3 次上限与可诊断终态。两处互补，不重复。

### 9.5 残留缺口（NOT COVERED）

1. **actor-owned 工具拒绝**：`settle-turn` 不处理 actor-owned turn（由
   `wake-actors` 负责）；当前上限只覆盖中心化 Plan/Reviewer 回合。worker
   的连续拒绝需要在 `wake-actors` 或 actor 生命周期中另加上限。
2. **UI 可操作错误展示**：`:owner/blocked` 已发出，`diagnostics` 含完整
   message/remedy；客户端是否把它渲染成醒目的“下一步”提示未在真实浏览器
   断言。
3. **真实模型回归**：本轮确定性测试用脚本 gateway 覆盖；真实 `devops`
   schema 修复后未重跑同一真实模型场景（server-audit 负责 schema）。

### 9.6 worker/holder 准入拒绝上限（本轮补齐）

中心化 Plan/Reviewer 的 `settle-turn` 上限已覆盖；worker/holder 由
`wake-actors` 结算，之前没有上限。本轮补：

| 位置 | 改动 |
|---|---|
| `mission/actors.clj` | 保留 3 参 `wake-all`（现有 host/测试的 `with-redefs` 兼容）；新增 `wake-all-skipping`，被上限封禁的 actor 保持 `:idle`，不把槽位标成 `:running` 去等待一个不会派发的 effect |
| `owner/guards.clj` | `wake-actors` 在 prepare 前用 `refusal-limit-for` 找出封禁 actor，传给 `wake-all-skipping`；仍为每个封禁 actor 记录一次 `:TOOL_ADMISSION_REFUSED_LIMIT`（`:source :wake-actors`、`:remedy :fix-request`、member/count/last-code、可操作 message）并发出 `:owner/blocked`；`enabled?` 在所有 idle actor 都已封禁且已报告时不再启用，避免每轮重复诊断 |
| `owner/handlers.clj` | 成功准入清除 `:tool-refusals` key 与 `:actor-refusal-reported` 标记；计数 map 只保留最新 64 个 key，避免当前态无界增长 |
| `owner/state.clj` | 新增 `:actor-refusal-reported #{}` |
| `owner/AGENTS.md` / `mission/AGENTS.md` | 记录 actor 上限、`wake-all-skipping` 与 `:owner/blocked` 精确载荷 |

“取消/死亡/新任期清零”的实现口径：计数 key 含 `tenure-id` 与
`agent-id`，新任期/新 agent 天然不共享计数；成功准入清除同一 key；
死亡/重生由 `sync-tenure` 分配新 `agent-id`，旧 key 不再匹配。当前态 map
另有 64 key 上限，旧 key 会被淘汰。操作员取消（P0）在同一提交中清空
`:tool-refusals` 与 `:actor-refusal-reported`；
`operator-cancel-clears-tool-refusal-streaks` 断言该行为。

测试（`hc.compose.nudge.owner-integration-test` 新增
`wake-actors-blocks-after-three-refusals`）：

- 同一 actor 的 `"clj-worker"` 连续 3 次拒绝：该 actor 不再产生新
  model call，另一个 actor 仍启动，诊断/`:owner/blocked`/报告标记齐全。
- 同一 actor 的 `"shell"` 只有 2 次拒绝：不触发上限（不同 member 独立）。
- 清除计数（成功准入）后该 actor 重新可唤醒。
- 所有 idle actor 都封禁且已报告时，`wake-actors` 的 `:enabled?` 返回
  nil，不空转。

实跑：

| 命令 | tests | assertions | failures | errors | 退出码 |
|---|---:|---:|---:|---:|---:|
| `hc.compose.nudge.owner-integration-test`（含 actor 上限） | 29 | 192 | 0 | 0 | 0 |
| `hc.compose.mission.actor-smoke-test` + `owner-actor-runtime-test` | 10 | 149 | 0 | 0 | 0 |
| `hc.compose.fleet.actors-test` + `fleet.service-test`（`wake-all` 3 参兼容） | 28 | 237 | 0 | 0 | 0 |
| owner 回归（handlers/runtime/stop-command/multi-client/guards owner） | 105 | 583 | 0 | 0 | 0 |

### 9.7 `:owner/blocked` 载荷（客户端渲染字段）

```clojure
{:type :owner/blocked
 :source :nudge | :wake-actors | :tool-result
 :diagnostic {:code :TOOL_ADMISSION_REFUSED_LIMIT
              :severity :error
              :message "Tool devops was refused at admission 3 times (...). Add the missing :scope annotation or stop calling it; do not repeat the same request."
              :remedy :fix-request
              :role :plan | :reviewer | :worker | :holder
              :lane :plan | :execution
              :tenure-id "tenure-1"
              :agent-id :plan | :worker-id
              :member "devops" | "clj-worker"
              :count 3
              :last-code :SCOPE_ANNOTATION_MISSING
              :at 1793851349694
              :turn-id "call-1"}   ; central turns only
 :at 1793851349694}
```

客户端最小字段清单：

- 标题/正文：`:diagnostic :message`（醒目 banner，不能只做 toast）
- 下一步：`:diagnostic :remedy`（`:fix-request` → “修正工具请求/补 :scope 后重试”）
- 细节：`:diagnostic :member`、`:count`、`:last-code`
- 归属：`:diagnostic :role`、`:lane`、`:tenure-id`、`:agent-id`
- 稳定键：`:diagnostic :code`、`:source`、`:at`（用于去重/排序）
- 中心化 turn 可选：`:diagnostic :turn-id`

注意：这是事件载荷，不是持久状态字段；客户端不得把它折叠成临时 toast，
也不得自造 remedy。

## 10 出站上下文冻结绑定（与 server-context 协同）

### 10.1 缺陷

`freeze-outbound-context` 已在 `claim-call*` 调用，但 provider 只被加到了
`ctx`（消息上下文），没有加到 `guard-ctx`。领取守卫拿到的 ctx 没有
`:outbound-context-fn`，所以 provider 从未被调用，`:calls <call-id>
:context` 一直是 nil（server-audit 的 runtime 测试 3 failures；真实冒烟
6 个 call 均无 :context）。

### 10.2 修复

- `runtime/guard-ctx` 增加 `:outbound-context-fn (:outbound-context-fn
  (:config runtime))`，与 `ctx` 一致。
- `claim-call*` 的 `freeze-outbound-context` 返回的 `current` 进入
  `state/put-call`，最终写入 `[:calls <call-id> :context]` 与
  `:context-attempt-id`；`state/start-run` 把同一 context 镜像到
  `[:runs <call-id>#<attempt-id> :context]`。
- 快照绑定 Owner 的 `attempt-id`（scheduler 领取 id），不是模型 gateway
  返回的测试 handle；重试复用，下一轮重新计算。
- 测试 `outbound-context-provider-is-frozen-on-the-model-call` 的第三断言
  改为比较 call 自己的 `:attempt-id`，而不是 gateway 的 `"a1"`。

### 10.3 真实 app 冒烟（真实 Ollama / 真实 store）

命令：`--send-command {:kind :send-message :text "hello context smoke"}`，
停止 app 后从 store 读取 `call-1`：

```
{:call-id "call-1", :state :dispatching,
 :attempt-id :attempt-24bb...--2,
 :context-attempt-id :attempt-24bb...--2,
 :context? true,
 :context-keys ["assistant" "attachments" "attempt-id" "boundary" "clips"
                "estimates" "excluded" "history" "instruction-cards"
                "instructions" "memory" "message-count" "message-ids"
                "messages" "missing-pinned-attachments" "pinned-attachments"
                "rounds" "summary-count" "summary-ids" "system" "tools"
                "version-ids" "warnings" "worldbook"]}
```

8 段（脱敏，仅形状/字节）：

| 段 | 形状 | bytes | 键 |
|---|---|---:|---|
| system | map | 47 | modules, prompt-bytes |
| assistant | map | 36 | name, revision |
| instructions | map | 63 | redacted?, session-override?, text-preview |
| memory | map | 49 | archived-excluded, enabled?, items |
| worldbook | map | 55 | enabled-books, hit-not-provided, provided |
| instruction-cards | vector | 2 | — |
| tools | vector | 71 | — |
| history | map | 326 | compressed?, excluded-count, range, visible-count |
| estimates | map | 34 | kind, tokens |

### 10.4 测试

| 命令 | tests | assertions | failures | errors | 退出码 |
|---|---:|---:|---:|---:|---:|
| `hc.compose.owner.runtime-test` | 29 | 158 | 0 | 0 | 0 |
| `hc.compose.nudge.owner-integration-test` | 32 | 208 | 0 | 0 | 0 |
| owner 回归（handlers/runtime/stop-command/multi-client/guards owner） | 108 | 594 | 0 | 0 | 0 |

### 10.5 与 server-audit 的分工

server-audit 在 `system.clj` 的 `start-owner!` 顶层注入
`:outbound-context-fn`（`history/outbound-context` + 真实 materials 纯读）；
compose 负责在 `claim-model-request` 调用并冻结。app provider 单测
`hc.server.app.outbound-context-test` 1/26/0 已通过；真实 store 冒烟
确认 8 段齐全并绑定 attempt-id。

## 11 受信任 worker 程序命令

### 11.1 接口

`:worker/run-program`（Owner command-kind；app `conversation-kinds` 路由；
gateway `default-forbidden-browser-kinds` 禁止浏览器）：

```clojure
{:kind :worker/run-program
 :session-id "..."
 :task-id :task-1
 :claim-id :claim-...
 :program-source "(defprogram ...)"
 :intent "..."}
```

`worker-run-program` 从 mission board 的 `:claims` 解析 tenure/actor/
workspace，校验 claim→task、actor `:life-state :alive`、task 当前
`:claim-id`；随后调用注入的 `:worker-run-program-fn`（app 接到
`hc.compose.workspace/run-program!`，携带 agent-id/claim-id/task-id/
attempt-id/call-id/role `:clj-worker`/lane `:execution`/holder?）。
回执：

```clojure
{:status :accepted
 :outcome :applied | :file-conflict | :prepare-rejected | :failed
 :task-id ... :claim-id ... :agent-id ...
 :result <workspace run-program! result>
 :stamp <stamp 或冲突 details>}
```

### 11.2 测试

`hc.compose.nudge.owner-integration-test/
worker-run-program-command-applies-and-reports-conflict`：真实 Owner +
真实 mission board 注册/认领；fake runner 断言请求携带正确
agent-id/claim-id，回执 `:applied` + stamp；冲突模式断言
`:file-conflict` 与 expected/actual stamp。nudge 集成 33 tests / 222
assertions，0/0。

### 11.3 真实链路断裂

真实 app（`e2e09chain`）已完成 Plan 底稿→清账→successor Plan→S1/S2/S3→
pending→`:execution-started`（tenure-3，Reviewer main-model）→
`:swarm-started`（root-task-1）。随后 `:board/command :register-actor`
返回 `:OWNER_ERROR "document is too large for the current-state store"`：
owner state 1,993,642 chars，默认 `max-doc-chars` 2,000,000
（`src/hc/server/storage.clj:58`，守卫 `src/hc/server/storage/migration.clj:181-185`）。
最小修复：短期把 owner-state 上限提到 8,000,000，或压缩 call 记录中重复
内联的 `:raw-source`/`:source`/`:numbered-source`/`:receipts`；长期把大
程序源/回执放 blob store 并只留引用。详见
`plans/e2e-real-07-09.md` §2.6。

## 12 Owner 当前态压缩（2MB 守卫）

### 12.1 策略

`hc.compose.owner.compact` 把大内容移出当前态：

- 程序源/工具回执字段（`:raw-source`/`:source`/`:numbered-source`/
  `:program-source`/`:receipts`/`:message`/`:hint`/`:stack`）中超过 4 KiB
  的字符串替换为
  `{:source-ref :source-hash :source-bytes :source-preview(≤4KiB)}`；
- **终态 call**（`:succeeded/:failed/:cancelled/:outcome-unknown`）：
  `:retry` 只留预算字段（attempts/max/deadline/retry-at/last-error-code），
  大 `:request` 变成 `{:request-ref :request-hash :request-bytes
  :request-preview :messages-count}`；`:last-error`/`:last-failure`/`:error`
  只留 `:code/:message(≤4KiB)/:phase`；
- `:cancel` 节点与 `:runs` 的 receipts 递归套用同一引用规则；普通对话/
  run 文本保持内联。
- 压缩幂等；`memory-blob-store` 用于测试，app 注入磁盘 blob store
  （`blob-store/put-stream!`/`read-bytes`）。
- 提交前 `commit-message!` 在状态超过 `:compact-threshold`（默认 1,000,000
  字符）时自动压缩；`:owner/compact` 可信命令可显式压缩并返回
  `{:outcome :compacted :stats {:before :after :saved :refs}}`。
- `max-doc-chars` 守卫保留；`owner_store` 把超限错误映射为
  `{:code :OWNER_STATE_TOO_LARGE :size :limit :remedy :compact-or-raise
  :hint}`。

### 12.2 真实大小

真实 E2E 09 链（`e2e09chain3`，同一会话，压缩前 1,994,628 chars）：

| 键 | 压缩前 | 压缩后 |
|---|---:|---:|
| total | 1,994,628 | 768,811 |
| calls | 1,232,081 | 139,468 |
| cancel | 437,572 | 342,505 |
| runs | 248,264 | 195,362 |

压缩后 `:board/command :register-actor` 与 `:claim` 均成功
（claim-id `:claim-1`），不再触发 2MB 守卫。

### 12.3 测试

| 命令 | tests | assertions | failures | errors | 退出码 |
|---|---:|---:|---:|---:|---:|
| `hc.compose.owner.compact-test` | 2 | 21 | 0 | 0 | 0 |
| `hc.server.app.owner-store-retry-test` | 3 | 18 | 0 | 0 | 0 |
| `hc.compose.nudge.owner-integration-test` | 33 | 222 | 0 | 0 | 0 |
| owner 回归（handlers/runtime/stop-command/multi-client/guards owner/compact） | 110 | 615 | 0 | 0 | 0 |

### 12.4 真实 worker 命令的剩余断裂

压缩解除后，`:worker/run-program` 命令已到达 handler，但真实 app 返回
`:COMMAND_TIMEOUT`：workspace `run-program!` 在 Owner 消息线程内同步调用
`:execute-board!`，后者又向同一 Owner mailbox 提交，形成自等待，5s 后
命令超时。最小修复：把 `:worker/run-program` 改成异步——
handler 只校验 claim 并返回 `:accepted` + job-id + outbox effect；app 在
mailbox 外运行 `run-program!`，再向 Owner 投递
`:worker/program-result`（带 outcome/stamp）更新回执/事件。不要在 mailbox
线程内同步执行受保护提交。
