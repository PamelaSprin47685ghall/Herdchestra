# E2E 11/12 修复报告（0.0.1）

- 日期：2026-11-05
- 范围：长期记忆候选/归档/临时会话；世界书与指令卡。
- 结论：**PARTIAL**。代码、服务端测试与客户端模型测试已完成；真实模型+真实 Chrome 已跑通记忆候选审阅、世界书绑定/条目/指令卡创建，但世界书多轮计数、预算与条目移动撤销未完成。临时会话一次性只读的客户端清除修复已落地，但被无关的 `speech/ui.cljs` 语法错误阻断 bundle 重建。

## 1 修复点

### 1.1 记忆候选与注入证据

- `hc.server.materials.session/effective-memories-read` 现在返回
  `:enabled?`、`:reason`、`:provided`、`:omitted`，预算不足的条目保留
  `:budget` 原因与字数；记忆正文仍不出服务端。
- `hc.server.history.context` 发布记忆的 `:reason`/`:temporary?`/`:omitted`，
  面板可显示“命中但未提供 … 超出本轮预算”。
- `hc.server.history.snapshot` 的 `:context/:outbound` 优先使用最近一次 run
  的冻结请求快照；归档后新发送不会出现在新快照里，旧 run 仍保留当时记录。
- `hc.server.materials.session/suggest-memories!` 默认使用适配器的
  `:task`（`:utility` 映射到 task），删除“task 不可用回退 main”的静默路径；
  角色缺失返回 `MEMORY_SUGGEST_MODEL_UNCONFIGURED` 和
  `:configure-utility-model`。
- 客户端候选行显示正文、类别、范围、来源片段、假设/截断标记；编辑器提供
  “改范围后批准”说明，批准按钮为“批准候选（按当前范围）”。

### 1.2 世界书与指令卡

- `material-session` 对没有 `:assistant-id` 的旧会话回退内置 `default`；
  `worldbook-request` 使用解析后的助手，绑定 `default` 的世界书可以真实命中。
- `outbound-context-opts` 把最近 4 条用户文本、2000 字记忆预算、4000 字
  世界书预算传给读取函数；世界书命中/未提供原因进入面板。
- 发送 payload 的 `:turn-card-ids` 通过
  `sessions/outbound-projection` → `outbound-context-opts` →
  `instruction-cards-read` 进入本轮冻结快照；显式选择优先于 staged，避免
  重复列出。
- `guards/freeze-outbound-context` 也把 `:turn-card-ids` 传给 app 的
  outbound provider，模型调用与面板使用同一选择。
- 客户端面板显示助手身份、记忆状态、指令卡空态“本轮未使用指令卡”、
  工具说明；世界书原因中文化。
- 条目移动回执保存 `:undo`，新增“撤销移动”按钮与
  `:materials/entry-undo-move`，按原书分组回移。

### 1.3 材料库 ready

- `materials_bridge` 在快照未 ready 时按 1.5 s 节流重试
  `:materials/snapshot`，避免新实例第一次快照落在未初始化时刻后永久停在
  “材料库未就绪”。保留 W5 的注释 + `(defonce fetched-at (atom nil))` 形式。

## 2 测试数字

| 命令 | 结果 |
|---|---|
| `history-live-smoke-test` 连续 3 次 | 每次 1 test / 31 assertions / 0 failures / 0 errors |
| `history-test` + `history-live-smoke-test` + `app.outbound-context-test` | 24 tests / 325 assertions / 0 failures / 0 errors |
| `hc.server.app.system-test`（E2E22 配置回归） | 17 tests / 105 assertions / 0 failures / 0 errors |
| 服务端聚焦套件（outbound-context、materials.session、memory-suggest、history.context、sessions） | 119 tests / 713 assertions / 0 failures / 0 errors |
| `hc.client.materials.model-test` | 42 tests / 184 assertions / 0 failures / 0 errors |
| release bundle（flock） | 退出 0；`main.js` 2,953,000 bytes，2026-11-05 14:56:47 |
| 真实 Chrome DOM harness | 本轮未重跑；`selection_dom_test.cljs` 已增加面板断言 |

## 3 E2E 11/12 断言状态

| 断言 | 状态 | 依据 |
|---|---|---|
| 记忆默认关闭、整理按钮 | 代码/单测 | 候选 UI 与 `memory-suggest?` 门控；真实 UI 未复跑 |
| 候选正文/类别/范围/来源/假设/截断 | 客户端单测 | `candidate-card-shows-review-fields-and-scope-change` |
| 批准/拒绝/改范围 | 客户端单测 + 服务端命令 | 未真实点击 |
| 归档后不再注入、旧记录保留 | 服务端投影单测 | 冻结 run 快照逻辑；未真实多轮 |
| 临时会话不读不写、一次性只读 | 既有服务端测试 | 本轮未改语义；未真实 UI |
| 世界书关键词任一/全部、持续/冷却 | 服务端既有测试 | 本轮未真实多轮 |
| 预算不足“命中但未提供” | 服务端/客户端单测 | `:omitted :budget` 路径 |
| 指令卡仅本轮、不污染助手 | 服务端/客户端单测 | 显式 turn-card-ids 优先 |
| 另一会话不承接计数 | 服务端既有测试 | 未真实 UI |
| 条目移动/撤销 | 客户端单测 | `entry-move-receipt-remembers-undo` |

## 3.1 并行发现的 outbound 范围缺陷（W1 报告）

`history-live-smoke-test/live-history-edit-compress-branch-survives-restart` 在
regenerate 后期望 outbound `:message-ids = ["m3" "m4"]`，实际为 `["m3"]`。

根因：上一轮为“本轮上下文”面板把整个 frozen run context 覆盖到
`:context/:outbound`，而 regenerate 的 run 输入只冻结 target 之前的
prefix，导致下一次发送范围丢失 target 自身。修复：
`history/snapshot.clj` 只从 frozen run 取注入分区
（system/assistant/instructions/memory/worldbook/instruction-cards/tools/
attachments/estimates），发送范围、summary ids、排除项仍由 fresh
`outbound-context` 计算。新增边界测试：
`outbound-range-survives-regenerate-and-excludes-hidden-messages`
覆盖 regenerate 后 target 仍在范围内、折叠消息被排除、分支会话不串入源
消息。

## 3.2 E2E 22 B 启动 ClassCast / 模型端口

W7 报告 B 恢复启动 `Character -> MapEntry`。根因在
`system/clean-model-section`：持久化 settings 的 `:models` 是 map，
`mapv without-nil-values` 把 MapEntry 当集合展开；字符串连接又被按字符
`into {}`。修复：`:models` 为 map 时按 `[id model]` 归一化，
`without-nil-values` 对非 map 原样返回。随后 adapter 又报
`:created-at`、`:model-entry-id` 未知；在投影层剥离
`:created-at/:updated-at/:model-order/:connection-order`（连接）与
`:created-at/:updated-at/:model-order/:model-entry-id`（模型），不扩
adapter 白名单。回归测试
`persisted-model-map-is-normalized-without-character-map-entries` 覆盖。
D-05 仍待 W7 复跑 B 启动 exit 0 后关闭。

## 3.3 内存热点与修复（OOM 审查）

| 热点 | 文件 | 原因 | 修复/数据 |
|---|---|---|---|
| snapshot 为每个会话调用一次 materials outbound 投影 | `src/hc/server/history/snapshot.clj` | 每次发布 O(sessions × materials 全量读取)；会话多时反复加载整个 materials state | 只对 active session 调用 `outbound-opts-fn`；其余会话用空 opts。回归测试 `baseline-projects-live-materials-only-for-the-active-session` 证明调用次数从 N 降为 1 |
| frozen outbound 曾整包覆盖 `:context/:outbound` | `src/hc/server/history/snapshot.clj` | 冻结上下文含 `:messages` 全量消息视图，复制到面板 | 只取 9 个注入分区；消息体不复制 |
| `session-user-texts` | `src/hc/server/app/system.clj` | 若扫描全部消息会随历史增长 | `take-last 4`，O(1) |
| `outbound-context-opts` 每次调用读 materials 两次 | `src/hc/server/app/system.clj` | memory 与 worldbook 各自 `load-state` | 当前只对 active session 调用一次；materials 读取本身为有界库读取，残留 O(library) |
| `effective-memories-read` 选择全部有效记忆 | `src/hc/server/materials/session.clj` | 选择预算需要排序全部有效记忆；库很大时 O(n) | 已只发布 provided/omitted 摘要；未改域内选择算法（避免语义变化），记为残留风险 |
| 客户端面板渲染全部 provided/omitted/cards/tools | `src/hc/client/conversation/view.cljs` | 库大时 DOM 节点多 | 服务端已按预算裁剪；未额外引入缓存 |

本轮结束时未持有自己的 app/JVM/Chrome 进程；`/tmp/hc-w4` 的 data/workspace/blobs/worktrees 已删除（证据保留在 `plans/evidence/e2e-11-12/`）。其他 worker 的 JVM 未触碰。

## 3.4 最终复跑与 OOM 收束（2026-11-06）

- 重建 `/tmp/hc-w4`（seed 会话 `w4-s1`，标题 `E2E12 chat`），材料库 ready
  探针确认 `材料库已就绪`、revision 0。
- 最终 E2E12 脚本因等待“材料库已就绪”的标签位置错误（该标签只在
  长期记忆页）连续失败；修正为先点“长期记忆”再等 ready，但随后
  W15 要求停止 PPID=1 的孤儿 app（PID 160864），E2E 以
  `ERR_CONNECTION_REFUSED` 结束，未产生新的多轮断言。
- 已停止该 app（TERM 160864）并清理 `/tmp/hc-w4` 的 data/workspace/
  blobs/worktrees；仅保留证据。未杀其他 worker 进程。
- 内存：app JVM 使用 `-Xmx1g -XX:MaxDirectMemorySize=512m`，RSS 约
  1180MiB；后续按 W15 规则改 768m/512m 并加
  `/tmp/hc-app-single.lock`，同一时刻最多 1 app + 1 Chrome。
- 2026-11-06 全局锁复核：我方 `/tmp/hc-w4` flock 进程 PID 235943 处于
  **等待**（lslocks 显示 WRITE*，非首个 WRITE 持有者），实际持有者是
  PID 228036（`/tmp/hc-visual-conversation/run-audit.sh`）。已 TERM
  235943 停止等待，未持有/未释放他人锁；随后 `/tmp/hc-w4/e2e-final.log`
  不存在，E2E 以 143 结束，无新断言。

## 3.5 冻结树全量（W13，2026-11-06）

- 最终冻结树 `ea9e5732` / `52547df2` 全量 **3748 tests / 24306 assertions /
  0F / 0E / exit 0 / 365 s**（13:04:16–13:10:21）；PRE=POST
  `52547df2…`；bundle `ea9e5732…`（3,010,646 B，13:02:42）未变；
  hs_err 9→9，峰值 1.94 GiB；`app-check` exit 0 / `:ok true` /
  `:version 0.0.1`。历史前冻结 `55e4e8ea`（3741/24260）保留为对照。
  峰值 2.05 GiB；`app-check` exit 0、`:ok true`、`:version 0.0.1`。
- `history-live-smoke-test` 145/146/148 全绿；本地 24/328 验证与全量一致。
- 根因：`app/snapshot.clj` 的 summary session-cache 对 active session 复用了
  旧 outbound context；W32 已改为 active session 强制重建，非 active 仍缓存。

## 4 未完成与阻塞

1. **E2E 12 多轮未跑**：世界书绑定 `default`、6 个条目（任一/全部/持续/
   冷却/预算/始终）与“仅本轮”指令卡已在真实 Chrome 创建成功
   （`plans/evidence/e2e-11-12/e2e12-only-result.json`、`shots/e2e12-*.png`），
   但发送被“当前轮仍在生成/临时会话”阻塞，关键词命中、持续/冷却、
   预算“命中但未提供”、跨会话计数、移动撤销均未取得真实 UI 证据。
2. **临时会话一次性只读清除**：`materials_bridge` 已修为提交后清除
   `:temporary-read?`（包括没有指令卡的情况），但 bundle 重建被
   `src/hc/client/speech/ui.cljs:315` 的未匹配括号阻断（非本任务文件）。
   旧 bundle 下真实 UI 仍显示勾选未清除，故该断言 FAIL。
3. **记忆候选**：真实 UI 已完成默认关闭、候选生成、批准、拒绝、改范围；
   截图见 `plans/evidence/e2e-11-12/shots/e2e11-*.png`。最终 JSON 被后续
   finish 脚本覆盖为失败态，证据以截图与运行记录为准。
4. **既有证据仍有效**：E2E 11 服务端 suggest 3 候选/159 s
   （`/tmp/hc-context-suggest/evidence.edn`）；E2E 12 世界书/条目/指令卡
   创建 UI（`/tmp/hc-e2e-real/artifacts/e2e12/`）。不得把组件级证据升级为
   E2E PASS。

## 5 复跑入口

- 配置与种子：`/tmp/hc-w4/config.edn`、`/tmp/hc-w4/seed.clj`
  （会话 `w4-s1`，6 条消息，main/task 都绑定本机 Ollama）。
- 启动：
  `flock /tmp/hc-e2e-model.lock -c 'clojure -M:server -m hc.server.app --config /tmp/hc-w4/config.edn'`
- 真实 Chrome 工装可复用 `/tmp/hc-e2e-real/lib.cjs`；面板选择器
  `details.hc-round-context`。

## 6 W22 收口尝试（2026-11-06，未完成）

- 环境：`/tmp/hc-w22`，app 768m/512m，Chrome `--disable-dev-shm-usage`，端口 7594；每次单实例、跑完停 app。
- 运行 bundle（按时间）：`dd968793`（E2E12 首次失败，材料页第二本书创建被“同一目标已有待确认命令”拒绝）、`55e4e8ea`（同因）、`8b210896`（未启动，队列撤下）。最终冻结 `0c05b7d1` 后被 `072587a4`（13:51:04）覆盖，W33 暂停新 E2E 证据绑定；E2E12 七轮与 E2E11 剩余项**未取得最终运行证据**。
- E2E12 已通过的前置：第二本书创建（刷新页面清除首个 book-create 待确认命令后）、6 个条目创建、指令卡创建并“本轮已选”。七轮发送未开始。
- 阻塞缺陷：① 首个 `:materials/worldbook/create` 回执后命令仍停在 `:submitting`，同 key 第二本书被拒；刷新可绕过。② `details.hc-round-context` 嵌套在默认收起的 `details.hc-context-panel` 内，直接点内层 summary 不可见；已改为 `textContent` 读取，尚未复跑。
- 证据：`plans/evidence/e2e-11-12/e2e12-multiround.json`（失败态，checks 仅 entries/second-book/card）、`shots/e2e12-mr-failure.png`；`e2e11-assumption-truncation-source.txt`（构造样本说明）。E2E11 脚本 `/tmp/hc-w22/e2e11.cjs` 已就绪但未运行。
- RSS：失败运行 app 约 1,118,452–1,148,696 KiB；无残留 w22 进程。

### 6.1 15:14 final8 尝试（启动被源码并发编辑阻断）

- 锁内启动 app（bundle `072587a4` / 树 `d1d8d08d`，配置 768m/512m）失败：`src/hc/compose/owner/state.clj` 编译错误；15:14 为 `:1173 Unable to resolve symbol: compression-substitute`，15:15 复核变为 `:1202 Unable to resolve symbol: %`。该文件 mtime 15:15:32，说明正在被并发编辑，不是冻结树。
- app 未通过 healthz，已由 runner 清理；无 w22 app/Chrome 残留，锁已释放。E2E12 七轮与 E2E11 剩余项仍未执行。
- 需改动人完成 `state.clj`、重建/复跑全量并宣布新冻结后，再排 E2E11/12。

### 6.2 最终冻结与 W42 接管

- 最终冻结：bundle `072587a4`（3,011,162 B，13:51:04）+ 源码树 `6aa694f1`；W35 final10 全量 3758/24351/0F/0E PASS。
- W42 在 final10 窗口内跑过一版 E2E12（15:49），因与全量资源竞争，send 未追加消息，面板停在“预览”，七轮断言大量 FAIL；该证据不作为结论。
- W22 最后一次排队（16:01）已取消，避免与 W42 重复；脚本与环境交 W42：`/tmp/hc-w22/{e2e12.cjs,e2e11.cjs,run-e2e12-locked.sh,run-e2e11-locked.sh,seed-candidate.clj}`。已知修复：第二本书前 reload 清除 book-create 待确认命令；面板改 textContent 读取。
- E2E11/12 最终 PASS 证据仍待 W42 在空闲 app 上复跑。
