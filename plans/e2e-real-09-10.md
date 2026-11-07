# E2E 09 / 10 真实复跑与修复报告（0.0.1，2026-11-05）

- 结论：**E2E 09 PARTIAL；E2E 10 PASS（真实 shell 在途 + 重启 UI 核对 + 无重放）。**
- E2E 10 的 UI 未知卡是该轮 in-flight model call（`call-6`），不是 shell
  action 的独立动作卡；真实 shell（`sleep 300`，pid 3905544）在 kill 时
  确实在途，恢复未重放，报告如实标注。
- E2E 09 的冲突语义、stamp 回执、重读重试已有确定性证据；真实 UI 的
  worker 受保护写入仍被 board 残留 action 阻断，未写成 PASS。

## 1. 修复点（term / token / worker-program）

| 文件 | 修复 |
|---|---|
| `src/hc/server/program/shell_port.clj` | 新增 `:term-fn`；host 解析当前 holder term，调用者显式 `:token-term` 仍优先（旧 term 交 board 拒）。nil extra 不覆盖已解析 term。 |
| `src/hc/server/app/system.clj` | 主 workspace 与 worktree 的 `shell-call-port` 注入 `:term-fn`（owner board / kernel 的 `[:token-slot :term-id]`）；`worker-run-program-fn` 把服务端解析的 term 放进 `:context`，客户端/模型不需要传。 |
| `src/hc/server/program/runtime.clj` | `control-context` 只把 `:context` 的 `:token-term` 传入 call port。 |
| `src/hc/server/shell/board.clj` | holder 的旧 term / 排队期间 term 变化返回 `:TOKEN_TERM_STALE`；非 holder 仍 `:TOKEN_REQUIRED`。 |
| `src/hc/compose/owner.clj` | `:board-snapshot` 在单 board 投影为 nil 时回退到最新 active execution tenure 的 board，修复多 tenure 下 term 解析为 nil。 |
| `src/hc/compose/owner/handlers.clj` | `tenure-id-for` 先按 `:actor-id` 在 mission boards 中解析 tenure，修复 `:UNKNOWN_TENURE {:tenure-id nil}`。 |

## 2. 测试数字

| 套件 | 结果 |
|---|---|
| shell / shell-port / shell-owner-port | 46 tests / 287 assertions / 0 |
| runtime + shell + handlers + shell-port（term 修复后） | 140 / 772 / 0 |
| owner handlers + board | 58 / 277 / 0 |
| compact | 2 / 25 / 0 |
| owner-state-size | 4 / 20 / 0 |
| workspace（冲突/重试） | 31 / 157 / 0 |
| conflict stamp | 1 / 8 / 0 |
| snapshot unresolved calls | 2 / 17 / 0 |
| swarm JVM | 22 / 208 / 0 |
| swarm UI DOM | 15 / 122 / 0 |
| client release（flock `/tmp/hc-client-release.lock`） | EXIT 0；`main.js` 2,953,574 bytes，sha256 `ec5ceea2…`（W4 于 15:29 重建）。 |

新增：holder 程序 call 自动带 term；显式旧 term 不被替换；runtime
`control-context` 携带 host term；旧 term 状态 `:TOKEN_TERM_STALE`。

## 3. E2E 09 断言

| 断言 | 结果 | 证据 |
|---|---|---|
| 外部修改后旧补丁写前拒绝 | **PASS（确定性）** | workspace-test 31/157；真实文件保留外部内容 |
| 冲突回执含原/当前 stamp | **PASS（确定性）** | conflict-stamp-test：`:expected` 旧 / `:actual` 新 / `:stamp-mismatch` |
| 重读后提交成功 | **PASS（确定性）** | 同 workspace-test：`:APPLIED`、动作 `:finished` |
| 真实命令链 Plan→Reviewer→S1/S2/S3→pending→execution→swarm | **PASS（服务端）** | `:ledger-updated`→`:relayed`→`:promoted`×3→`:execution-started`→`:swarm-started` |
| 2 MB 守卫不再阻断 register/claim | **PASS（真实 app）** | `:register-actor` agent-4/5；`:claim` claim-1/2/3 |
| 真实 UI worker 受保护写入/文件卡 | **NOT COMPLETED** | task-2 旧 shell action `:running` 残留，`reject-in-flight!` 使 task-3 settle 返回 `:ACTION_IN_FLIGHT`；未再取得干净 token 任务 |
| 窄视口 | **NOT RUN** | 核心写入未完成 |

## 4. E2E 10 断言

| 断言 | 结果 | 证据 |
|---|---|---|
| 真实 shell 子进程在途 | **PASS** | token task-2 + claim-2；`sleep 300` pid 3905544（父 app pid 3900300） |
| kill app 后同 data-dir 重启 | **PASS** | stop_app 进程组 SIGTERM；shell 随组结束；重启 pid 3906384 |
| UI 显示“结果待确认”与核对入口 | **PASS** | `e2e10-observe.json`：面板 1 未结，call-6 `:outcome-unknown`，两个按钮；`e2e10-restart-board.png` |
| 点“确认已成功”后结清 | **PASS** | `e2e10-ack.json`：0 未结 / 1 已核对，call-6 阶段 `succeeded`，显示“已确认成功（succeeded）” |
| 无自动重放 | **PASS** | 核对前后模型请求数 109→109，delta 0；无新 `sleep 300` |
| 迟到结果不追加 | **PASS** | 重启后无新 shell 进程/新模型请求；UI 未出现新工具结果 |

## 5. 剩余阻塞

1. E2E 09 的 board 残留 shell action（task-2 `:running`）阻止同一 actor 的
   新受保护提交；需要 `:resolve-action`/恢复核对路径或新的干净 swarm。
2. E2E 09 真实 UI 的冲突卡/文件卡未在浏览器中完成一次
   read→external edit→conflict→reread→applied。
3. E2E 10 的 UI 未知卡是该轮 model call；shell action 未单独发布为动作卡。
4. 窄视口 360×800 未测。

证据：`plans/evidence/e2e-09-10/`（observe/ack JSON、截图、state-term、
worker 日志、steps）；诊断：`plans/e2e-09-10-diagnosis.md`。

## 6. E2E 09 干净实例复跑（2026-11-05 下午，W5）

- 新 data-dir `/tmp/hc-w5-final/data`、workspace `/tmp/hc-w5-final/work`，
  文件 `e2e09.txt` 初始 `original`；bundle sha256
  `ec5ceea29bb3697e8da2b80edea7caa2659c44b46204c4464cd8b4e3d97fc908`
  （2,953,574 bytes）。
- 真实 app 命令链再次走通：Plan review→draft→清账→S1/S2/S3→pending→
  execution（tenure-3）→execution review→swarm（root task-1）。
- **新发现的 board 缺陷**：token slot `:free` 时调用
  `:take-or-die`，回执为 `:result :attempt-won` 但
  `:retired? true :retired-agent-id` 等于发起者；随后 claim 返回
  `ACTOR_INACTIVE`。同一行为在 agent-4/agent-6 复现。结果是无法在干净
  swarm 中获得 holder 来执行 `call`/token task，真实 UI 的
  read→external edit→FILE_CONFLICT→reread→APPLIED 仍未完成。
- 冲突语义、原/当前 stamp、重读重试仍由
  `workspace-test`（31/157）与 `workspace-conflict-stamp-test`（1/8）
  确定性覆盖；真实 UI 窄视口 360×800 未测。
- 该 board 行为属 compose/mission，不在 W5 storage/compact/call
  所有权内；建议登记 DEBT 并由 W2/W4 修 token 轮转。

## 7. TOKEN_TASK_REQUIRED 与回归（2026-11-06）

- 产品：空 token 且无未完成 `:needs-token true` 任务时 `:take-or-die`
  返回 `:TOKEN_TASK_REQUIRED`；有 token task 时 `:attempt-won` +
  `:retired? false`。
- 测试前提：先 split/create token task，再 take-or-die；空 token 无 token
  task 断言拒绝/actor 存活/token free。
- 数字：swarm-stm 50/259/0/0；property 9/9/0/0；actor-smoke 8/118/0/0；
  host 24/296/0/0；owner-actor-runtime 仍 5F（旧“空 holder take-or-die
  退休”前提，需改为 claim+finish）。
- E2E 09 的 Board 冲突卡投影已实现（settle `:expected`/`:actual`），单测
  `snapshot-unresolved-calls-test` 3/23/0；真实 UI 冲突卡/窄视口在 OOM
  清理后未重跑。

## 8. 2026-11-06 收口尝试

- `owner-actor-runtime-test` 已按新语义重写：split token task →
  take-or-die（`:attempt-won`/`:retired? false`）→ claim → finish token
  task → R(a) 为空 → 同事务退休/rebirth。结果 **2 tests / 40 assertions /
  0F / 0E**；mission 6 ns 合跑 **53 / 571 / 0 / 0**。
- `append-bounded` 达上限后直接返回原字符串，不再复制；边界测试
  `append-bounded-stops-copying-at-the-limit` 通过（shell-port 8/39/0/0）。
- E2E 09 真实 UI 本轮 **NOT COMPLETED**：`flock /tmp/hc-app-single.lock`
  单实例、`-Xmx768m -XX:MaxDirectMemorySize=512m` 启动后 app 监听
  44393、RSS 约 949 MB；但 `--send-command` 在 180 s 内未返回，随后 app
  JVM 退出（app.log 0 字节），未能建立干净 swarm。未伪造 UI 证据。
- 证据：`/tmp/hc-w5c/evidence/`（若存在）、本报告；master 09 仍 PARTIAL。

## 9. E2E09 启动阻塞定位（2026-11-06）

- `--send-command` 超时与 app.log 0 字节的根因不是 stdout 缓冲：
  `/tmp/hc-app-single.lock` 被其他工人的 app 实例持有（`fuser` 显示多个
  flock 等待者，持有者 `flock -w 3600 ... /tmp/hc-w4/...` 与
  `/tmp/hc-vi...`），我的 `flock /tmp/hc-app-single.lock` 一直排队，
  java 子进程根本没有启动；`--send-command` 在 singleton 等待/锁等待中
  超时。
- 未杀其他工人进程，未取得干净实例；E2E09 真实 UI 仍 NOT COMPLETED。
- 已完成的组件证据：owner-actor-runtime 2/40/0/0、mission 合跑
  53/571/0/0、shell-port 8/39/0/0；冲突/stamp/重读确定性 PASS；Board
  settle 冲突投影单测 3/23。

## 10. 2026-11-06 锁队列复跑（第二次）

- W17 audit 释放后，W5 以 `flock -w 1800 /tmp/hc-app-single.lock`
  （PID 265283）入队；app 日志出现 `listening on 43375`，但
  `/tmp/hc-w5e/tmp/herdchestra-kunweiz/` 只有 `instance.lock`，没有
  `instance.edn`（control endpoint 未发布），singleton TCP 命令无法发送。
- 期间进程表出现多个 `-Xmx768m` app（含其他工人）；W15 按用户授权于
  11:07:01 TERM 了 W5 遗留 PID 265283/267822，锁释放。W5 未取得干净
  可控实例，E2E09 真实 UI 仍 **NOT COMPLETED**。
- 已确认 snapshot.clj 当前可加载（`require` :ok，mtime 11:04:59），
  11:04 的 Nested #() 报错是并发编辑中间态。


## 11. instance.edn 根因与修复（2026-11-06）

- 根因：**不是 instance.edn 发布逻辑缺失**。`singleton/start-server!` 在
  绑定 loopback ServerSocket 后先 `write-state!`（temp + ATOMIC_MOVE /
  REPLACE_EXISTING），再启动 accept 线程；`run-primary!` 在
  `start-system!` 成功后调用它。W5 的 app 日志出现
  `listening on 43375` 后，W15 按用户授权于 11:07:01 TERM 了 PID
  265283/267822；shutdown hook 调用 `singleton/release!`，按设计删除
  `instance.edn`，所以检查时只剩 `instance.lock`。这是清理后的正常状态，
  不是发布失败。
- 最小修：`write-state!` 第二次 move 失败时删除 temp 并抛出
  `:CONTROL_ENDPOINT_PUBLISH_FAILED`，不留下半写文件；app 的 catch 仍
  转成 `START_FAILED` 并释放锁。
- 测试：`hc.server.app.singleton-test` **4 tests / 14 assertions / 0F /
  0E**；新增 `published-endpoint-is-atomic-and-has-no-temp-residue`
  （启动后 state 可读、port/token/pid 正确、无 `.instance-*` 残留、
  stop 后 state 删除）。
- E2E09 真实 UI 仍未重跑：`/tmp/hc-app-single.lock` 当前 holder
  268104（E2E01），队列含 W2、另一个 E2E09 run.sh（273310）、W22；
  W5 未再入队以避免重复占锁。

## 12. 视觉优先与 E2E09 排队（2026-11-06）

- 用户授权 W17→W21 视觉序列优先。W15 于 11:16:20 TERM 了 W5 的排队
  flock 304761 及子脚本 306402（当时只有 run.sh，无 app/Chrome），锁交给
  W17 305550。E2E09 本轮 **未完成**，等视觉序列结束后重排。
- 自动化 E2E09 工装 `/tmp/hc-e2e09/run.sh` + `driver.cjs` 已确认覆盖：
  split token task → take-or-die → claim → 外部编辑 → FILE_CONFLICT
  （原/当前 stamp、stamp-mismatch）→ 重读 → APPLIED → 冲突卡 → 窄视口，
  并记录 RSS；下次锁空闲可直接
  `flock -w 3600 /tmp/hc-app-single.lock bash /tmp/hc-e2e09/run.sh`。
- singleton 修复与测试、owner-actor 0F/0E、append-bounded 边界测试均完成。

## 13. Bundle hash 状态（2026-11-06）

- 临时构建 `4652ef2641b8a7fef8186af84e8b76285b54f8e9ad0f01f7aa52f4e4489ca66b`
  （11:17:42，3,007,766 B），**最终待定**；`a8acf565` 与 `4652ef26` 均
  不再作为最终冻结。W17 的 `conversation/view.cljs` 11:17:45 晚于构建，
  等 W17 最后一次重建后统一冻结。E2E09 若在冻结前重跑，报告会注明临时
  hash；不自行重建 bundle。

## 14. 最终 bundle hash（2026-11-06 11:19）

- W17 最终重建：`main.js dd968793cc74919e139f31bb290a5dfb68309c6d9a78acfd766a4d984f2fda4b`
  （3,007,964 B，11:19:25），CSS `dda62554…`。D-08 四标记、speech、
  W17/W18 标记均已在构建中核对。E2E09 若重跑应绑定此 hash（W9 最终
  冻结前注明“临时最终”）。
- 锁仍由视觉序列（visual-ops/visual-board）持有，W5 的 E2E09 未再入队；
  E2E09 真实 UI 仍 NOT COMPLETED。

## 15. 自动化 E2E09 真实结果（2026-11-06 12:08–12:10，bundle 55e4e8ea）

- 工装：`/tmp/hc-e2e09/run.sh` + driver（真实 app 768m + mock 模型 + 系统 Chrome）。
- PASS：bundleFrozen（55e4e8ea）、Plan 首审/底稿/relay、S1/S2/S3→pending→
  execution→swarm、register actor、split token task（task-2）、
  take-or-die（`:attempt-won`、`:retired? false`）、claim（claim-1）、
  冲突程序 scheduled、外部内容保留（`外部编辑,42`）、服务端 stat/stamp 最终。
- FAIL：
  - `conflictCard=false`：Board DOM 未出现 `.hc-protected-program`；服务端
    `snapshot/baseline` 已正确发布 `:worker-programs`（冲突 path、原/当前
    stamp、`:stamp-mismatch`，离线脚本复验 2 条），问题在客户端 Board 投影/
    同步（bundle 含面板代码，但 `programRows` 为空）。未在冻结窗口内改 bundle。
  - `retryApplied=false`、`workerContentOnDisk=false`：重试 job 的 settle 为
    `:BOARD_TIMEOUT`（Owner 未在超时内报告 board command），文件仍是外部
    内容；不是重放旧补丁。
  - 窄视口：`narrowNoHorizontalOverflow=true`（scrollWidth=clientWidth=360），
    但 `narrowCardVisible=false`、`narrowRetryButtonUsable=false`（无卡片）。
- RSS：after_health 1,096,176 KB；after_driver 1,372,032 KB。
- 证据：`plans/evidence/e2e-09-10/e2e09-final.json`。
- 结论：**E2E 09 PARTIAL**；受保护提交核心/冲突语义 PASS，冲突卡 UI 与
  重试 APPLIED 未通过。

## 16. 客户端投影/DOM 复验（2026-11-06）

- 新增 fixture `:worker-programs`（冲突 path、原/当前 stamp、:stamp-mismatch、
  FILE_CONFLICT）并加测试：
  - `hc.client.swarm-test` 23 tests / 217 assertions / 0F / 0E；
  - `worker-programs-render-conflict-card-and-retry` 通过：`.hc-protected-program`
    渲染、`原 stamp:`/`当前 stamp:`/`stamp-mismatch`、`重读并重试` 按钮。
  - `verify_swarm_ui.sh` 16 tests / 130 assertions / 2F：仅
    `fleet-overview-renders` 的 aria-live/role=status 两个既有断言失败
    （当前 `dashboard-element` 在 `:empty?` 时省略 summary；非本次
    worker-programs 改动）。
- 真实 E2E09 仍 `conflictCard=false`：服务端 `snapshot/baseline` 已发布
  `:worker-programs`（离线复验 2 条），DOM 测试也证明给定该字段会渲染；
  剩余差异在 55e4e8ea bundle 的运行时同步/Board 投影，需解冻后重建
  bundle 复跑，冻结窗口内未改客户端源码/未重建。
- 重试 job settle `:BOARD_TIMEOUT`：Owner 未在 5s 内报告 board command，
  属服务端等待/回执超时，不是旧补丁重放；需 Owner/board 超时链路单独修。

## 17. 2026-11-06 真实 app 命令链复跑（55e4e8ea）与 8b210896 启动阻塞

### 11.1 真实命令链（PASS）

新 data-dir `/tmp/hc-e2e09/data`、workspace `/tmp/hc-e2e09/work`，
bundle `55e4e8ea…`（当时冻结），app `-Xmx768m
-XX:MaxDirectMemorySize=512m`，session
`hc-session-bbc8c664-5232-4e06-bcd9-206da89e9822`。经 loopback 控制通道
（非 `--send-command`）驱动：

| 步骤 | 回执 |
|---|---|
| Plan 首评非空 | `:ledger-updated` |
| `:plan/write-draft` | `:draft-updated`，53 字符 |
| Plan 清账 | `:relayed`，successor `tenure-2` |
| 空 review ×3 | `:promoted` S1→S2→S3→pending |
| pending 空 review | `:execution-started`，`tenure-3` |
| execution review 非空 | `:swarm-started`，board root `:task-1` |
| `:register-actor` | `:agent-4` |
| `:split :task-1` | `:task-2`，`:needs-token true` |
| `:take-or-die :agent-4` | `:attempt-won`，`:retired? false` |
| `:claim :task-2` | `:claim-1` |
| 冲突 worker program | `:scheduled`；外部编辑落盘，settle `:FILE_CONFLICT`/`:stamp-mismatch` |
| 重试 worker program | `:scheduled`，但 settle 在 `:final-check` 返回 `:BOARD_TIMEOUT` |

真实文件：初始 `name,value\n原稿,1\n`（20 B）；外部编辑后
`name,value\n原稿,1\n外部编辑,42\n`（36 B）；重试未应用，文件仍保留
外部内容。窄视口 360×800：`scrollWidth 360 / clientWidth 360`，无水平
溢出。RSS：启动后 1,096,176 KB，driver 结束 1,372,032 KB。

### 11.2 离线 owner-state 诊断

直接只读打开 `/tmp/hc-e2e09/data/db` 的 session 分区，两个 worker job
均为 `:finished :failed`：

- 冲突 job：`:error :FILE_CONFLICT`，settle `:stamp-mismatch`；
  `:expected` 同时含原始 20 B 与外部 36 B，`:actual` 36 B。
- 重试 job：`:BOARD_TIMEOUT`（Owner 未在 5 s 内回复 board command），
  文件未写。
- `snapshot/baseline` 能发布两个 `:worker-programs`，但客户端
  `keyword` 收到 map 形态的 `:program-status`（BOARD_TIMEOUT）会抛异常，
  整个 dashboard 渲染失败，所有卡片消失。这是 UI 卡未出现的根因。

### 11.3 修复（服务端投影，不改客户端 bundle）

`src/hc/server/app/snapshot.clj`：

1. `worker-program-view` 把 `:program-status` 统一为 keyword；状态是
   map 时取 `:code`，避免客户端 `keyword` 异常拖垮整个 dashboard。
2. `settle-conflict` 从 `:expected` 中选择与 `:actual` 不同的 stamp 作为
   “原 stamp”，避免 read-set 同时含原始与外部 stamp 时把“原 stamp”
   显示成当前值。

验证：`require hc.server.app.snapshot` → `:snapshot-ok`；离线
`snapshot/baseline` 对真实 owner state 投影 2 个 worker-programs，
冲突 `expected size 20 / actual size 36`。修复已记入 D-07。

### 11.4 8b210896 复跑阻塞

冻结 bundle 更新为 `8b210896…` 后重排；app 启动即失败：

```
Syntax error compiling at (hc/server/app/system.clj:537:32).
No such var: history-context/model-messages
```

这是 W32 history 缓存改动引入的编译错误，不在 E2E09 所有权内；
未取得 8b210896 的真实 UI 证据，也未伪造。锁已释放，证据保留。

### 11.5 结论

E2E 09 仍 **PARTIAL**：受保护提交语义、Board 命令链、原/当前 stamp、
外部内容保留、窄视口无溢出 PASS；真实 UI 冲突卡/重试按钮/重读后
APPLIED 未在冻结 bundle 上完成。剩余阻塞：

1. `system.clj:537` 的 `history-context/model-messages` 编译错误（W32）。
2. 重试 job 的 `:BOARD_TIMEOUT`（Owner 邮箱 5 s 超时）。
3. 8b210896 下需重跑并验证 `snapshot.clj` 修复后的卡片渲染。

证据：`plans/evidence/e2e-09-10/e2e09-final.json`、
`e2e09-55e4e8ea-run.json`、4 张截图。

### 17.6 ea9e5732 新树复跑（2026-11-06 13:06–13:09）

W32 修复 `system.clj:537` 后，`require hc.server.app.system` → `:system-ok`；
在冻结 bundle `ea9e5732…`（3,010,646 B，13:02:42）上重跑：

- 命令链：Plan 首评→底稿→清账→S1/S2/S3→pending→execution→swarm、
  `:register-actor`、`:split :task-1`→`:task-2`、`:take-or-die`
  （`:attempt-won`/`:retired? false`）、`:claim`→`:claim-1` 全部 PASS。
- 冲突 job：`:FILE_CONFLICT`/`:stamp-mismatch`，外部编辑
  `外部编辑,42` 保留；真实文件未写入旧补丁。
- 重试 job 共 3 次：前两次 `:BOARD_TIMEOUT`，第三次 APPLIED；
  文件最终为 `worker,7\n`，`/api/workspace/stat` 的 stamp 与磁盘一致。
- 窄视口 360×800：`scrollWidth 360 / clientWidth 360`，无水平溢出。
- RSS：启动后 1,119,612 KB，driver 结束 1,380,712 KB。
- **UI 未同步**：页面显示“已连接，等待同步”“通道已打开，正在等待原子
  基线快照”“尚未准入任何 swarm”。Sente 连接已建立但初始基线快照未
  到达，dashboard 为空，`.hc-protected-program` 卡、原/当前 stamp、
  重试按钮与窄视口卡片布局均无法验证。这不是投影字段问题（离线
  `snapshot/baseline` 已投影 2 个 job），而是初始快照未送达客户端。
- 结论：E2E 09 仍 **PARTIAL**；文件级受保护提交链路已 PASS，UI 卡验证
  被初始快照同步阻塞。证据：
  `plans/evidence/e2e-09-10/e2e09-ea9e5732-run.json`、
  `e2e09-final.json`。

## 17. ea9e5732 复跑（2026-11-06 13:0x）

- bundle `ea9e5732656e79e738883ba384b1a62b4fe9461d396b83612ad4624dd81a39c2`
  （3,010,646 B，13:02:42）。
- PASS：bundleFrozen、Plan 首审/底稿/relay、S1/S2/S3→pending→execution→
  swarm、register、split token task、take-or-die（:attempt-won、
  :retired? false）、claim、冲突程序 scheduled、外部内容保留、
  **retryApplied=true / workerContentOnDisk=true**（BOARD_TIMEOUT 30s 修复
  生效）、服务端 stat/stamp 最终、窄视口无横向溢出。
- FAIL：`conflictCard=false`（Board DOM 仍无 `.hc-protected-program`；
  `conflictExpectedShown/ActualShown/ReasonShown=false`）、
  `narrowCardVisible=false`、`narrowAppliedCard=false`。
- 离线复验：服务端 `snapshot/baseline` 发布 2 条 `:worker-programs`（含原/
  当前 stamp、`:stamp-mismatch`）；客户端 `normalize/to-db` +
  `merge-into-db` + `dashboard-from-db` 对同一 snapshot 返回 1 条并渲染
  `.hc-protected-program`（DOM 测试通过）。真实运行时 bundle 仍不显示，
  剩余差异在 Board 页拿到的 Fulcro db/同步路径，需在页面注入 db 探针定位。
- RSS：after_health 1,096,176 KB；after_driver 1,372,032 KB（前次）。

### 17.7 0c05b7d1 最终冻结树复跑（2026-11-06 13:46–13:50）

W5 重建（`__HC_DB__` 只读入口、`hc-protected-program` 14 处）后，在
bundle `0c05b7d1…`（3,011,096 B，13:37:25）+ 源码树 `7667323a` 上复跑：

- 文件级链路仍 PASS：Plan→Reviewer→swarm、register-actor、
  split `:task-2`、take-or-die（`:attempt-won`/`:retired? false`）、
  claim `:claim-1`；冲突 `:FILE_CONFLICT`/`:stamp-mismatch`，外部编辑
  保留；3 次重试后 APPLIED，`worker,7` 落盘，server stat stamp 一致。
- 窄视口 360×800：`scrollWidth 360 / clientWidth 360`，无水平溢出。
- RSS：启动后 1,102,348 KB，driver 结束 1,299,912 KB。
- **UI 初始快照仍未到达**：页面仍显示“已连接，等待同步”
  “通道已打开，正在等待原子基线快照”“尚未准入任何 swarm”，
  `.hc-protected-program` 为 0。`__HC_DB__` 已加入但本段未读取；
  说明 W5 的 `__HC_DB__` 入口没有解决 Sente 初始基线快照发布。
- 结论：E2E 09 仍 **PARTIAL**；文件链路 PASS，UI 卡验证继续被初始
  快照同步阻塞。证据：
  `plans/evidence/e2e-09-10/e2e09-0c05b7d1-run.json`、`e2e09-final.json`。

### 17.8 `window.__HC_DB__` 只读探针（2026-11-06 13:57，bundle 072587a4）

在 072587a4 上启动 app（端口 36077，现有 data-dir），打开 `/#/board`
9 秒后读取只读 `window.__HC_DB__`（W5 加入，仅 identity/counts）：

```json
{"keys":["extensions","conversation","files","notices","server-revision",
         "app-id","settings","sidebar-open","reduced-motion","platform",
         "materials","session-list","session-filter","viewport-width",
         "diagnostics","connection","font-scale","ask","draft-storage",
         "search","aux-open","theme","backup","assistants-tab","aux-tab",
         "contrast","command","route","focus-target","board-expanded",
         "find-seq","browser","search-state","aux-return-id","focus-seq",
         "stream","draft-reset","active-remotes","selected-session-id",
         "draft","materials-tab","session-limit","sidebar-return-id"],
 "sessionIds":[], "activeId":null, "dashboard":false, "workerPrograms":{}}
```

- `keys` 全是客户端本地 UI 键，**没有 `:session/by-id`、`:session/active-id`、
  `:swarm/dashboard`**；`sessionIds []`、`activeId null`、`dashboard false`、
  `workerPrograms {}`、`.hc-protected-program` 0。
- 页面文本：“已连接，等待同步”“通道已打开，正在等待原子基线快照”。
- `pageErrors []`；app.log 无异常；`/healthz :ok true`。
- 结论：不是 board 投影字段问题（离线 `snapshot/baseline` 已投影 2 个
  worker-programs），而是**服务端初始基线快照未到达/未合并到客户端
  Fulcro db**。需要 W5 检查 gateway on-open 的 `:snapshot` 发布与客户端
  `normalize/to-db` 的接收路径；`__HC_DB__` 本身可用（typeof function）。
- 证据：`plans/evidence/e2e-09-10/e2e09-db-probe.json`、
  `e2e09-db-probe.png`；RSS 1,157,624 KB。

## 18. 服务端 worker-jobs 合并修复与探针（2026-11-06 14:0x）

- 根因（D-16）：客户端 `__HC_DB__` 探针显示 `:session/by-id` 有 session，但
  `workerPrograms` 计数 0；服务端离线 `baseline` 用完整 owner state 有 2 条。
  真实 app 的 `prefer-owner-snapshot` 在 router revision 更高时选中只带
  session context 的 router snapshot，丢了 app owner 的 `:worker-jobs`。
- 修复：`src/hc/server/app/system.clj` 的 `prefer-owner-snapshot` 在选中
  snapshot 缺 `:worker-jobs` 时从另一份合并。测试 control-port + snapshot
  14/79/0/0。bundle 仍 072587a4（服务端改动），树 d1d8d08d。
- 复跑（072587a4 + d1d8d08d）：文件链路/重试 APPLIED PASS；但
  `__HC_DB__` 的 workerPrograms 仍为 0，`conflictCard=false`。说明
  baseline 的 session summary 仍未携带 `:worker-programs`，或
  `worker-program-summaries` 在真实 owner-snapshots 形状下未取到
  `:worker-jobs`；需在服务端 `baseline` 加一次性日志/直接读取真实
  `owner-snapshots` 验证。E2E09 仍 PARTIAL。

### 17.9 W5 system.clj 修复后复跑（2026-11-06 14:52–14:55，072587a4 / d1d8d08d）

W5 在 `system.clj` 的 `prefer-owner-snapshot` 合并 `:worker-jobs` 后，
final8 单树全量 PASS。E2E09 复跑结果：

- 文件链路仍 PASS：冲突 `:FILE_CONFLICT`/`:stamp-mismatch`、外部编辑
  保留、3 次重试后 APPLIED、`worker,7` 落盘、server stat stamp 一致。
- 窄视口 360×800：`scrollWidth 360 / clientWidth 360`，无水平溢出。
- RSS：1,115,396 → 1,352,840 KB。
- **UI 仍“已连接，等待同步”**：“通道已打开，正在等待原子基线快照”
  “尚未准入任何 swarm”，`.hc-protected-program` 为 0。
- 结论：W5 的 `:worker-jobs` 合并修复没有解决初始基线快照未到达；
  根因仍在 gateway on-open 的 `:snapshot` 发布或客户端
  `normalize/to-db` 接收路径。E2E09 仍 **PARTIAL**。
- 证据：`plans/evidence/e2e-09-10/e2e09-072587a4-run.json`、
  `e2e09-final.json`、`e2e09-db-probe.json`。

## 19. D-16 服务端发送日志定位（2026-11-06 15:2x）

- 在 `gateway.clj` 的 `handle-subscribe!` send-fn 加临时 `:DBG-hc-send` 日志后，
  真实 E2E09 app.log 显示：`STATE_SNAPSHOT :subscription-id 1 :bytes 26441`
  多次发送，另有 STATE_DELTA/CUSTOM；**服务端确实发送了初始快照**。
- 客户端 `__HC_DB__` 仍只有本地 UI keys，无 `:session/by-id`，说明客户端
  `sync/receive` 丢弃或未处理该事件。`receive-state-snapshot` 要求
  `:phase ∈ #{:connected-awaiting-sync :synced}` 且 epoch/seq 有效；
  `on-event!` 的 `:phase`/`:subscription` 时序是下一检查点。
- 临时日志已保留在源码（`gateway.clj`），待 D-16 修完后删除；源码树因此
  变化，需 W35 在新树重跑全量。E2E09 仍 PARTIAL。

### 17.10 D-16 修复后 UI 同步成功（2026-11-06 15:44，072587a4 / 3fa10fd7）

W41 的 `snapshot.clj baseline` 出口 `normalize-entity-tables`（`*/by-id`
keyword keys → string）修复后，E2E09 复跑首次看到完整 UI：

- **冲突卡出现**：`task-2 · claim-1 · 文件冲突，未写入`；文件
  `/tmp/hc-e2e09/work/e2e09.txt`；`stamp-mismatch`；
  `原 stamp: mtime 1793951086.751686865 · size 20`；
  `当前 stamp: mtime 1793951090.428879014 · size 36`；
  `错误 :FILE_CONFLICT · Protected commit did not apply`；`重读并重试`。
- **窄视口 360×800**：卡片可见，重试按钮 101×44、right 140；
  `scrollWidth 360 / clientWidth 360`，无水平溢出。
- **文件链路**：外部编辑保留；重试后 `worker,7\n` 落盘；
  `/api/workspace/stat` 的 stamp 与磁盘一致；RSS 1,106,152→1,213,792 KB。
- **唯一剩余缺陷**：重试 job 的卡片 `:program-status` 显示 `STAGED`
  （程序最后 `write` 的 staged 值），settle 的 `:APPLIED` 被 `value`
  覆盖。文件本身已 APPLIED。修复：`snapshot.clj` 的 `:program-status`
  改为 `(or (:status settle-result) (:status value))`，一行优先级。
- 结论：D-16 修复有效；E2E09 在修该投影优先级后可 PASS。
- 证据：`plans/evidence/e2e-09-10/e2e09-d16-run.json`、截图。

## 20. final10 复跑（2026-11-06 16:0x，bundle 072587a4 / 树 6aa694f1）

- UI 冲突卡/窄视口/重试按钮已全部出现：
  `conflictCard=true`、`conflictStatus=true`、`conflictExpectedShown=true`、
  `conflictActualShown=true`、`conflictReasonShown=true`、
  `originalStampUnchangedInCard=true`、`currentStampShownInCard=true`、
  `narrowNoHorizontalOverflow=true`、`narrowCardVisible=true`、
  `narrowRetryButtonUsable=true`、`narrowAppliedCard=true`。
- 文件链路：`finalText="worker,7\n"`、`workerContentOnDisk=true`、
  `serverStatOk=true`、`serverStampIsFinal=true`。
- 唯一 FAIL：`retryApplied=false`，UI 重试卡 `data-status="STAGED"`。
  离线用同一 owner state 调 `snapshot/baseline`，retry job 的
  `:program-status` 为 `:APPLIED`（settle `:status :APPLIED`，value
  `:status :STAGED`）。说明真实 app 的初始 snapshot 在 job 完成前发布
  STAGED，之后的 STATE_DELTA 未刷新 session summary 的 `:worker-programs`；
  客户端保留旧 summary。
- 建议：worker/program-result 提交后发布包含 `:session/by-id` 的
  STATE_DELTA（或让 summary 的 worker-programs 进入 delta diff），
  使重试卡从 STAGED 更新为 APPLIED。E2E09 仍 PARTIAL（仅此一项）。

### 17.11 E2E09 最终 PASS（2026-11-06 16:10–16:11，072587a4 / 6aa694f1）

W41 的 `normalize-entity-tables`（D-16）与 W23 的 `:program-status`
settle 优先修复后，最终冻结树 `6aa694f1` + bundle `072587a4` 复跑
**全部断言 PASS**：

| 断言 | 结果 | 证据 |
|---|---|---|
| Plan 首评/底稿/relay/S1→S2→S3→pending→execution→swarm | PASS | 回执 |
| register-actor / split `:task-2` / take-or-die（`:attempt-won`/`:retired? false`）/ claim `:claim-1` | PASS | 回执 |
| 外部编辑后旧补丁写前拒绝 | PASS | 冲突卡 `:FILE_CONFLICT`/`:stamp-mismatch`；文件保留 `外部编辑,42` |
| 冲突回执原/当前 stamp | PASS | 原 `mtime 1793952632.642215028 · size 20`；当前 `mtime 1793952636.81245463 · size 36` |
| 重读后新修改落盘 | PASS | 重试卡 `APPLIED`；文件 `worker,7\n`；server stat stamp `mtime 1793952663.744488438 · size 9` |
| 文件卡指向真实结果/运行位置 | PASS | `文件 /tmp/hc-e2e09/work/e2e09.txt` |
| 窄视口 360×800 | PASS | `scrollWidth 360 / clientWidth 360`；冲突卡与 APPLIED 卡均可见；重试按钮 101×44、right 140 |
| 运行 RSS | 记录 | 启动后 1,078,308 KB；driver 结束 1,121,804 KB |

证据：`plans/evidence/e2e-09-10/e2e09-final.json`（PASS 原始 JSON）、
`e2e09-final-pass-072587a4.json`、4 张截图。bundle
`072587a4` + 源码树 `6aa694f1`（final10 3758/24351/0F/0E）。
