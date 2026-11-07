# Pair Hint 生产接线计划

状态：只写计划，不改 `src/hc/server/app/system.clj` 或其他 server/app 文件。核心层修复已在 `src/hc/core/pair_hint/**` 完成；本文覆盖审查中的接线偏差 #3、#4、#7、#8、#9、#10。`system.clj` 正被其他工人编辑，下列行号是当前快照，落地时以函数名和结构为准。

## 0. 共同约束

- 所有出站请求都必须经过 `hc.core.pair-hint/wrap-model-gateway`；不允许在 `model-gateway` 之后另开一条裸通道。
- `:render` 一律传 `hc.core.ufss/write-str`；核心层不再接受 `pr-str` 回退。
- 失败诊断进入 Owner 的 outbox/diagnostic 或结构化日志；不得用 `catch Throwable` 静默重置链。
- 接线改动只消费核心层已提供的事实：`resolve-language`、`child-session`、`observe-board`、`observe-fleet`、`scope-boundary`、`project-request` 的 `:needs-snapshot`。

## 1. #3 七项注入中的 (5)(6)(7) 事实

目标：`src/hc/server/app/system.clj` 的 `session-owner-factory` 中主 wrapper 的 `:observations` 同级增加 `:context` 函数；当前两处装配约在 1346/4761 附近（`gateway`），devops wrapper 约在 1393/4808 附近。

`:context` 必须是一个 `(fn [request session] ...)`，由 `integration/projection-opts` 以 `[request session]` 调用。字段来源：

| 字段 | 来源 | 缺失时 |
|---|---|---|
| `:context-window` | 当前请求所选模型的 `:context-window`（`model-adapter/config` 或 settings 的 model entry）；请求 snapshot 已冻结 `:connection`/`:model` 时按该 identity 读 | 传 `nil`，由资源渲染为 `unknown`；不要猜一个数字 |
| `:turns-used` | Owner state 中已结算的 model call 数（优先 `:calls` 里 `:kind :model` 的 completed/failed 计数；不要用 `hint-count`，重放不增加回合） | 传 `nil` |
| `:turns-remaining` | 配置的回合预算（若存在）减去 used；没有预算时传 `nil`，不要编造 | 传 `nil` |
| `:wall-clock-seconds` | `(now - session-created-at)` 的整秒数；`now` 用 Owner 的 `:now` 或注入时钟 | 传 `nil` |
| `:parked-handles` | Owner 程序状态中 `:state :parked` 的程序名列表（只取 `.park` 句柄名；不要包含已 completed/failed 的程序） | 传 `[]` |

实现建议：在 app 层加一个纯函数 `pair-hint-context [snapshot request]`，从 `(:state (:snapshot owner))`、`session-record`、settings model entry 取上述事实；wrapper 的 `:context` 直接调用它。`parked-handles` 若不是字符串列表，核心层会用注入的 UFSS renderer 渲染，不要传 `pr-str` 结果。

测试：`test/hc/server/app/system_test.clj` 或现有 owner smoke 中断言请求 hint 文本包含真实 `context-window`/`turns-used`/`wall-clock`，且 `parked-handles` 为 `["build"]` 而不是 `[]`。

## 2. #4 蜂群/fleet 请求接入 Pair Hint

目标：`system.clj` 的 `fleet-gateway`（当前约 2775/6190）与 `fleet-start-ref` 的 `:model/start` 分支（当前约 2837/6252）。`fleet-gateway` 目前是裸 `model-gateway`。

推荐做法：

1. 保留 `fleet-gateway` 作为真实 provider gateway；在 `:model/start` 分支外面加一层 per-swarm 的 Pair Hint 路由器。
2. 以 `swarm-id` 为键维护 `swarm-pair-sessions`（atom）。首次见到某个 swarm 时，从主 Owner 的 pair session 派生：
   `(pair-hint/child-session parent-pair-session nil {:session-id (str "fleet-" swarm-id) :task-id attempt-id})`。
   `child-session` 只继承语言和资源，不继承主链；蜂群槽位不能改语言。
3. 为每个 swarm 创建 `(pair-hint/wrap-model-gateway fleet-gateway {:state (atom {:session child}) :render ufss/write-str :tools-supported? true :placement (fn [request _] {:call-id (or (:call-id request) (:attempt-id request))}) ...})`，缓存并按 `swarm-id` 选择。task 角色确实需要工具，`:tools-supported? true` 与 `resolve-tools-supported` 的 task 规则一致；若 task 模型声明无 tools，adapter 仍按 `:required-capabilities [:tools]` 明确失败。
4. 持久化：把每个 swarm 的 pair session 写入 Owner 的 `:mission/extensions`，键建议 `:pair-hint-fleet-<swarm-id>`；或至少在进程内保留并在 swarm 结束后清理。重启续链优先持久化，避免每次 fleet 尝试都新开 Epoch。
5. `:observations` 对 swarm 不传看板全量；蜂群只需要自己的任务上下文。若未来需要 board delta，按 #7 的 reader 路径传 delta。

注意：`fleet-gateway` 的 `request` 是 `{:snapshot (assoc actor-request :session-id "fleet" :role :task ...)}`；包装层要在 `:snapshot :messages` 上追加合成对，不能改 actor-request 的语义字段。`placement` 用 `:call-id`/`:attempt-id`，重试同一 attempt 只重放。

测试：fleet 集成测试中启动一个 fake provider，断言 `:model/start` 捕获的请求 roles 含 `:assistant`/`:tool`、空工具名、`pair-hint` 标记；同一 attempt 重试不增加 hint；不同 swarm 的链互不串。

## 3. #7 看板 writer/reader 区分

目标：`system.clj` 主 wrapper 的 `:observations`（当前约 1346/4761）。现在无条件：

```clojure
{:board {:observation {:kind :snapshot
                       :board-version ...
                       :snapshot board}
         :opts {:writer? true}}}
```

这会让每次 board-version 变化都开新 epoch 并注入整张看板，读者 delta 路径从未使用。

需要的事实：

- 当前请求的 agent 身份：`(get-in request [:snapshot :agent-id])` 或 `:actor-id`；main/Steward 也要有稳定 id。
- 最近一次 board 变更的写者：board STM 或 Owner snapshot 提供 `:last-writer-id`/`:last-actor-id`，以及 `:board-version` 和可用的 `:changes`。
- 如果 board 只暴露完整快照、不暴露 changes，则 reader 无法构造合法 delta；这是前置依赖，不能靠“把完整快照塞进 delta”冒充。

策略：

1. 若 `last-writer-id == request-agent-id`：`{:kind :snapshot :board-version N :snapshot board}` + `:opts {:writer? true}`，开 clean-break。
2. 否则：`{:kind :delta :board-version N :changes changes}`；`changes` 必须来自 board STM 的增量事实。若没有 changes，先补 board STM 的 delta 输出，再接线；不要回退成 writer 全量。
3. 同一版本重复观察由核心层 replay；版本落后由核心层报 stale；`project-request` 的 `:projection :needs-snapshot` 为 true 时，app 必须取当前版本快照并在下一次投影传入 `:snapshot`（带 `:board-version`），或明确诊断“board snapshot unavailable”。
4. clean-break 后旧 delta 消息的清理：核心层清 placements/chain；app 的 base messages 若包含上一轮 projected request，应在边界后从 base 中剥离旧 board pair，或保证 base 始终是 raw conversation（推荐后者，和 Owner 现有 `conversation-tail` 一致）。

测试：app 集成测试分别扮演 writer 和 reader：writer 版本变化得到 clean-break 与新 epoch；reader 版本变化得到 append delta，链继续；reader 超阈值且无快照得到 `:needs-snapshot`；下一次带当前快照的投影得到 clean-break。

## 4. #8 Steward fleet 视图

目标：主 wrapper 的 `:observations` 对 `:role :steward` 的请求追加 `:fleet`；或在 `system.clj` 建一个专用 `steward-gateway`，与主 gateway 共享 provider 但独立 pair session。

事实来源：

- `fleet-service` / `fleet-supervisor` 的 swarm 运行时：`swarm-id`、`slice-id`、`worktree`、`status`、`root-ready`、`root-waiting`、`root-done`、`queued-count`、`last-event`、`master-version`。
- 版本：优先 `:master-version`，否则 fleet service 自己的单调快照版本。
- 事件：只放 `:result-queued`；核心层现在还会按 `fleet-event-fields` 过滤字段，但 app 不应把 report/task 详情塞进事件。
- `:report`：§6.2 的 fleet 摘要不列 `:report`；根 AGENTS.md 第 105 行要求 joined 行带全文 report。两者冲突，建议裁决为：fleet 摘要保持有界；report 通过独立的 join 结果/`result-queued` 通知交付，不塞进每个 swarm 行。若裁决相反，再扩展 `fleet-row-fields` 并补测试。

接入方式：

- 在 `:observations` 函数里按 `(:role (:snapshot request))` 分支：Steward 返回 `{:board ... :fleet {:observation {:kind :snapshot :fleet-version v :swarms rows :events events}}}`；其他角色只返回 board。
- 若 Steward 与 main 共用一个 wrapper，它们的链会混在一起；建议给 Steward 独立 pair session（`create-session` 继承同一语言），或至少按 role 分链。
- `apply-fleet-observation` 的 clean-break 会开 `:board-clean-break` 边界；确认 Steward 的 Scope/board 事实不会与它冲突（见 #7 的边界合并规则）。

测试：Steward 请求的 hint 文本含 fleet 行和 `:result-queued`；不含 `:report`/task 详情；fleet 版本变化发 delta，超阈值 clean-break；非 Steward 请求不含 fleet 段。

## 5. #9 语言解析调用点

目标：会话创建路径，`src/hc/server/sessions.clj` 的 `create-session`（当前 `:or {language :en}` 约 235 行）与 `src/hc/server/app/commands.clj` 的 `handle-create!`（约 314 行）；`system.clj` 的两处 `initial-pair-session`（当前约 1328/4743）不应再硬编码 `:en`。

步骤：

1. 在 session 创建前调用 `pair-hint/resolve-language`：
   - `:env`：`(System/getenv)`（或注入的 env map；测试可注入）。
   - `:config`：settings 的 `:language`（若已存在）。
   - `:ide-locale` / `:locale`：可用的 IDE 语言设置与 `java.util.Locale/getDefault`。
   - 返回 `{:language :source :raw :undetermined? :recorded?}`。
2. 把结果写入 session record 的 `:language`、`:language-source`、`:language-raw`、`:language-recorded?`；非法显式值直接拒绝创建（`LANGUAGE_INVALID`），不要回退。
3. `system.clj` 的 `initial-pair-session` 用 session record 的 `:language` 和 `:language-source` 构造；如果 record 没有（旧数据），调用 `resolve-language` 并记录，不要 `(or ... :en)` 冒充显式。
4. `pair-hint/create-session` 接受 `:explicit-language` 时仍会把它记为 `:source :explicit`；如果 session record 的来源是 `:config`/`:locale`/`:default`，应把来源一并传给 pair session（或在 `create-session` 后 assoc `:source`/`:language-source`），保证审计字段与真实阶梯一致。
5. `resume-session` 只信持久化语言；若 session record 与 persisted pair session 语言不一致，保留 persisted（lifetime binding），记录诊断，不要静默改语言。

测试：app 层分别注入 env/config/locale/default，断言 session 与 pair session 的语言和 `:source`/`:language-source`；非法 env 值导致创建失败；重启后 resume 不重跑阶梯。

## 6. #10 持久链失败的诊断策略

目标：`system.clj` 两处：

```clojure
(catch Throwable _ initial-pair-session)
```

替换为 `restore-pair-session` 助手：

1. `resume-session` 成功：正常续链。
2. 捕获 `ExceptionInfo`，按 `:error`/`:code` 分类：
   - `:PAIR_HINT_CHAIN_INVALID`、`:PREFIX_DRIFT`、`:PREFIX_TRUNCATED`：属于链不兼容或漂移；记录 `{:code ... :message ... :persisted-version ...}` 到 Owner diagnostic/outbox，并明确选择“丢弃旧链、新开 session”（pre-1.0 允许），不要静默。
   - `:PAIR_HINT_LANGUAGE_INVALID`、`:PAIR_HINT_RESOURCE_MISSING`、`:PAIR_HINT_RESOURCE_MISMATCH`：属于配置/资源硬错误；Owner 启动失败或进入可见的 degraded 状态，不要用新 session 掩盖。
   - 其他 `Throwable`：记录 class/message 与栈摘要，上抛或 degraded；不要 `catch Throwable _`。
3. 丢弃旧链时使用 `create-session` 并记录 `:reason :pair-hint-chain-reset`；不要伪造一个合法边界（核心层 GATE 1/4）。
4. 诊断要能从 UI/日志看到，且包含 `session-id`、旧链 `:count`/`:boundary`、错误码。

测试：注入损坏的 persisted pair（root 不匹配、非法语言、缺资源），断言 Owner 启动行为与诊断内容；断言没有静默新 Epoch。

## 7. 实施顺序与测试门槛

1. #3 `:context`（纯 app 函数，风险最低）。
2. #9 语言解析（session 创建路径，先于 #10）。
3. #10 失败诊断（依赖 #9 的错误分类）。
4. #7 writer/reader（需要 board STM 暴露 delta/last-writer；先补事实，再接线）。
5. #8 Steward fleet（需要 fleet service 快照/版本）。
6. #4 fleet 包装（依赖 #9 的语言继承和 #10 的持久化策略）。

每步完成后跑：`clojure -M:test:compose:server:client -n hc.core.pair-hint-test -n hc.core.pair-hint-ufss-integration-test`，再加对应的 app/owner 集成测试。核心层当前基线：63 tests / 393 assertions / 0 failures / 0 errors。
