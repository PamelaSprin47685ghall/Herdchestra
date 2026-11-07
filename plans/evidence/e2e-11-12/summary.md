# E2E 11/12 修复与验证证据（2026-11-05）

## 1 代码修复（本轮实际落地）

| 区域 | 文件 | 修复 |
|---|---|---|
| outbound 投影 | `src/hc/server/history/context.clj` | 记忆新增 `:reason`/`:temporary?`/`:omitted`（含 `:budget` 原因与字数）；助手新增 `:id`；指令卡新增 `:body-preview`；工具新增 `:description`；世界书同时接受 `:selected/:omitted` 与 `:provided/:hit-not-provided` 两种形状 |
| 面板数据源 | `src/hc/server/history/snapshot.clj` | `:context/:outbound` 优先使用最近一次 run 的 `:input-snapshot/:context`（真实发送快照），无 run 时回退预览；`context-panel` 因此能区分“上次发送”与“预览” |
| outbound 组装 | `src/hc/server/app/system.clj` | 解析默认助手；把最近 4 条用户文本与 2000/4000 预算传给记忆/世界书读取；把发送 payload 的 `:turn-card-ids` 传给指令卡读取；记忆使用 `:provided/:omitted`；世界书使用 `:provided/:hit-not-provided`；`optional-materials-read` 兼容旧两参读取函数 |
| 发送快照 | `src/hc/server/sessions.clj` | `outbound-projection` 接受 `:turn-card-ids/:instruction-values` 并写入 run 冻结上下文；旧一参投影函数仍兼容 |
| 冻结调用 | `src/hc/compose/owner/guards.clj` | 把当前 call 的 `:turn-card-ids/:instruction-values` 传给 `:outbound-context-fn` |
| 记忆整理端口 | `src/hc/server/materials/session.clj` | 默认绑定改为适配器 `:task`（utility 映射到 task）；删除“task 不可用回退 main”的静默路径；角色缺失返回 `MEMORY_SUGGEST_MODEL_UNCONFIGURED` + `:configure-utility-model` |
| 助手解析 | 同上 | 新增 `built-in-assistant-id`/`resolved-assistant-id`；`material-session` 对旧会话回退 `default`；`worldbook-request` 使用解析后的助手 id |
| 记忆读取 | 同上 | `effective-memories-read` 新增 `:enabled?/:reason/:provided/:omitted`，保留 `:memories` 兼容；预算不足保留 `:budget` 原因 |
| 世界书读取 | 同上 | `worldbook-selection-read` 使用解析后的助手，命中集合可真实返回 |
| 指令卡读取 | 同上 | 接受 `:turn-card-ids`；显式发送选择优先于 staged，避免重复 |
| 记忆候选 UI | `src/hc/client/materials/view.cljc` | 候选行增加 `hc-memory-candidate`/正文/来源/范围/假设/截断标记；编辑器增加“改范围后批准”说明；按钮改为“批准候选（按当前范围）”；整理失败显示错误 |
| 条目撤销 | `src/hc/client/materials/model.cljc`、`view.cljc` | 记录移动回执的 `:undo`；新增 `:materials/entry-undo-move` 与“撤销移动”按钮 |
| 上下文面板 | `src/hc/client/conversation/view.cljs` | 显示“上次发送/预览”、助手身份、记忆状态与“命中但未提供”、指令卡空态、工具说明；世界书原因中文化 |
| 材料 ready 重试 | `src/hc/client/workbench/materials_bridge.cljs` | 快照未 ready 时按 1.5 s 节流重试 `:materials/snapshot`（保留 W5 的注释 + `(defonce fetched-at (atom nil))` 形式） |

## 2 自动化验证（实际运行）

| 命令 | 结果 |
|---|---|
| `clojure -M:test:server -n hc.server.app.outbound-context-test -n hc.server.materials.session-test -n hc.server.materials.memory-suggest-test -n hc.server.history.context-test -n hc.server.sessions.sessions-test` | **119 tests / 713 assertions / 0 failures / 0 errors**（含 property seed `1793861720609`） |
| `clojure -M:test:client -n hc.client.materials.model-test` | **42 tests / 184 assertions / 0 failures / 0 errors** |
| `flock /tmp/hc-client-release.lock -c 'npm run release'` | 退出 0；`resources/public/js/main.js` 更新于 2026-11-05 14:56:47，2,953,000 bytes。构建输出有 `browser_bridge.cljs` 的既有 `str/*` undeclared-var 警告，不在本轮修改文件内 |
| 真实 Chrome DOM（`test/hc/client/conversation/selection_dom_test.cljs` 扩展） | **未在本轮重新运行**；新增断言已写入，等待浏览器 harness 复跑 |

## 3 真实 E2E 复跑状态

**未执行。** 原因：

1. 独立实例 `/tmp/hc-w4` 的配置、种子会话（`w4-s1`，6 条消息）、真实 Chrome 工装已准备；
2. 应用以 `flock /tmp/hc-e2e-model.lock` 启动时，锁被其他 worker 的模型实例持有（`fuser` 显示 PID 3695933/3695934 持锁），本实例等待 2 分 39 秒仍未启动；
3. 随后收到 200 请求预算收束通知，停止等待并关闭实例，未进行真实模型轮次。

因此 E2E 11/12 在本轮不能标记 PASS。此前已有的真实证据仍有效：

- E2E 11 服务端 suggest：`/tmp/hc-context-suggest/evidence.edn`，3 候选 / 159 s；
- E2E 12 世界书/条目/指令卡创建 UI：`/tmp/hc-e2e-real/artifacts/e2e12/`。

## 4 待真实复跑的最小断言

### E2E 11

- 两个记忆开关默认关闭；开启后“整理记忆”产生候选。
- 候选卡显示正文/类别/范围/来源/假设/截断；批准、拒绝、改范围后批准均写入。
- 归档后新发送的“本轮上下文”不再列出该条；旧 run 仍保留当时记录。
- 临时会话不读不写记忆；一次性只读关闭后不扩展到下一临时会话。

### E2E 12

- 关键词任一/全部命中；持续/冷却按用户轮次，工具往返不消耗。
- 预算不足时面板出现“命中但未提供 … 超出本轮预算”。
- 单次指令卡只出现在本轮；另一会话不承接计数。
- 条目移动后“撤销移动”恢复原书。
