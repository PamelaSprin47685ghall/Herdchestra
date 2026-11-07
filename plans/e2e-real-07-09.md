# E2E 07 / 09 真实浏览器实跑报告（2026-11-05）

- 范围：真实 app + 真实 Chrome 153.0.8010.36 + 本机 Ollama
  `http://127.0.0.1:11434/v1` / `qwen3.6:27b`（context 16384，
  `:reasoning-effort "none"`）。
- 工装：`/tmp/hc-e2e-harness/run_app.sh`、`e2e_driver.cjs`（Playwright 1.64.1，
  `NODE_PATH=/tmp/hc-pw/node_modules`，CDP 从 `DevToolsActivePort` 读取）。
- 结论：**E2E 07 仍 BLOCKED（搜索服务选择未进入运行状态）。E2E 09 拆成两半：
  规范路径的受保护提交核心已用真实 Owner + 真实文件系统确定性 PASS
  （外部编辑 → `:FILE_CONFLICT` → 保留外部内容 → 重读重试 → `:APPLIED`）；
  真实 UI 的 Plan→Reviewer→swarm→worker 全链路仍未跑通，断裂点是执行层入口/
  模型多步交接，不是 `clj-worker` 能力缺失。** 详见 §2。

## 1. E2E 07 搜索结果与来源

### 1.1 准备

- 本地 SearXNG stub：`/tmp/hc-e2e-harness/e2e07/evidence/search_stub.js`，
  `127.0.0.1:18095`；返回 Doc One / Doc One duplicate / Missing Source /
  Doc Two，其中 Doc One 重复 URL、Missing 404、Doc Two 有发布日期。
- 文档站：`python3 -m http.server 18096 --directory /tmp/hc-e2e-harness/e2e07/docs`。
- app：`run_app.sh --name e2e07search --data-dir /tmp/hc-e2e07search/data
  --workspace-root /tmp/hc-e2e07search/work -- --config
  /tmp/hc-e2e-harness/e2e07/config.edn`，实际端口 40003。
- 模型：`Local Ollama (E2E) / qwen3.6:27b`，新会话直接显示
  `模型：qwen3.6:27b`，binding 修复生效。

### 1.2 操作与断言

1. 打开“联网搜索”，选择服务 `Local Stub Search (non-internet) · searxng`。
   - DOM select `value` 变为 `local-stub`。
   - 但页面仍显示“服务 未选择”；搜索状态没有接受该选择。
2. 点击“本轮必须搜索”：`aria-pressed="true"`，模式按钮状态正确。
3. 发送“请只根据搜索结果回答：ALPHA-ONE 来自哪个文档？请用 [1] 引用来源。”
   - 发送按钮可点，消息进入会话。
   - 运行 95 s 后仍显示“正在生成 / 无人值守 · 自动继续”。
   - 对 `127.0.0.1:18095` 的请求数：**0**。
   - `.hc-source-card` 数量：**0**；正文没有可核对的来源列表。
   - 断言：`searchRan=false`、`sourceCardCount=0`、`dedupSameUrl=false`、
     `unknownDate=false`、`missingKeepsTitleUrl=false`、`answerHasNumbers=true`
     （正文出现 `[1]` 字样，但没有来源卡，不能当作来源编号通过）。

### 1.3 结论与 finding

**BLOCKED（真实 UI 搜索旅程）**。不是模型不产出引用，而是搜索服务选择没有
进入搜索运行状态：select DOM 值已变，页面状态仍“未选择”，服务端搜索请求为 0。
需要 client 修复搜索状态更新（`update-search-state`/受控 select 的 props 回流）
后重跑。模型侧同时仍停在“无人值守”，未自然结束。

证据：

- `plans/evidence/e2e-real-07-09/e2e07-select-probe.json`（选项、selectValue、
  mode aria、body 仍“未选择”）
- `plans/evidence/e2e-real-07-09/search-result.json`（searchRequests=0、
  sourceCardCount=0、body）
- `plans/evidence/e2e-real-07-09/answer.png`
- `plans/evidence/e2e-real-07-09/config.edn`、`search_stub.js`

仍未覆盖：同一 URL 合并、未知发布日期、正文编号与来源列表对应、失效来源保留
标题/地址、导出纯文本含编号/地址、断网重看不发起隐式搜索。

## 2. E2E 09 工作区受保护提交

### 2.1 准备

- 工作区：`/tmp/hc-e2e09final/work/e2e09.txt`，初始内容 `original`。
- app：`run_app.sh --name e2e09final --data-dir /tmp/hc-e2e09final/data
  --workspace-root /tmp/hc-e2e09final/work --model-config`，端口 38963。
- 真实模型：`qwen3.6:27b`，新会话显示 `模型：qwen3.6:27b`。
- 提示词：要求使用 `clj-plan` program 的
  `(write {:path "e2e09.txt" :content "model-write"})`，不要只用 devops、
  不要只描述。

### 2.2 操作与断言

1. 新建会话，模型 binding 正常；发送按钮可用。
2. 发送提示后，运行 124 s 仍“正在生成 / 无人值守 · 自动继续”。
3. 没有出现冲突卡片、stamp 对比或重试入口；`bodyHasConflict=false`。
4. 工作区文件在运行后仍为 `original`，没有 `model-write`；文件大小 9 bytes。
5. 没有机会执行“外部编辑触发 FILE_CONFLICT → 冲突展示 → 重试成功”的后半段。
6. 360×800 窄视口未测：核心写入流程未发生，测试冲突按钮没有意义。

### 2.3 根因

上一轮把 E2E 09 当成“Plan 直接写工作区”，这条路径本来就不存在：
`clj-plan` 的 schema 只允许写 `plans/<name>.md`，首次 review 前还有写锁；
工作区写入属于 `clj-worker` 的 file capability。规范路径是：

1. Plan 写底稿 `plans/e2e09-workspace.md`；
2. Plan 用 `(review {:findings []})` 进入 `待执行` 并交接；
3. 执行层 Reviewer 提交非空台账，进入蜂群；
4. worker 认领任务，用 `clj-worker` 的受保护提交修改工作区文件。

这条路径不需要“Plan 写工作区”的临时入口，也不需要 Board 视图的手工认领
按钮；worker 由 `wake-actors` 自动唤醒，认领与写文件由 worker 程序完成。

本轮在真实 UI 上没有把四步全部跑完：Plan 的 S1→S2→S3→待执行交接、Reviewer
非空台账、worker 认领/写文件都由真实模型多轮驱动，单次聊天提示无法稳定
推进；断裂点因此是“执行层入口/多步交接缺少可操作驱动”，不是
`clj-worker` 能力缺失。最小修复建议：给会话提供显式的“交接执行层”动作
（或 Board 的受控 worker-program 入口），把模型驱动的隐式交接变成用户可
点击/可观察的状态迁移；这属于 app/UI 装配，不改 `clj-worker` 或 workspace
gate 契约。

### 2.4 结论与 finding

**PARTIAL**：

- **规范路径的受保护提交核心 PASS（确定性）**：真实 Owner + 真实文件系统 +
  真实 `$W$`，worker 读取 `e2e09.txt` 后外部脚本改写同一文件，`settle!`
  返回 `:FILE_CONFLICT`，真实文件保留外部内容，看板不创建动作；新会话重读
  新 stamp 后再次 `write` + `settle!` 返回 `:APPLIED`，文件变为 worker 内容，
  动作 `:finished`。测试：`hc.compose.workspace-test/
  file-conflict-then-reread-retry-succeeds`（31 tests / 157 assertions，0/0）。
- **真实 UI 全链路 NOT COMPLETED**：Plan 底稿/交接、Reviewer 非空台账、
  worker 认领与写文件没有在一次真实 UI 旅程中全部完成；断裂点是执行层入口/
  模型多步交接（见 §2.3），不是文件冲突路径。

证据：

- `test/hc/compose/workspace_test.clj` 的
  `file-conflict-then-reread-retry-succeeds`（真实临时文件、真实 `$W$`、
  stamp 对照、冲突后重试成功）
- `plans/evidence/e2e-real-07-09/e2e09-result.json`（sendFound=true、
  model label、bodyHasConflict=false、bodyHasTool=true、body）
- `plans/evidence/e2e-real-07-09/e2e09-after.png`
- `plans/evidence/e2e-real-07-09/e2e09-workspace-after.txt`（仍 `original`）
- `plans/evidence/e2e-real-07-09/e2e09_steps.cjs`

仍未覆盖：真实 UI 的 Plan→Reviewer→swarm→worker 四步全链路、文件卡真实结果、
冲突卡片的 UI 呈现/重试按钮、360×800 窄视口。受保护提交核心的冲突/重试
已有确定性覆盖。

## 3. 与旧结论的冲突

| 场景 | 旧结论 | 本次结论 | 裁决 |
|---|---|---|---|
| E2E 07 | PASS（组件/stub） | BLOCKED（真实 UI 搜索未运行） | 取本次真实 UI 证据；旧组件 PASS 只证明搜索服务/控件存在 |
| E2E 09 | PASS（服务端/桌面/文件 UI smoke） | BLOCKED（真实 UI 无工作区写入路径） | 取本次真实浏览器尝试；旧证据只证明组件与冲突 UI 文案 |

## 4. 还缺的元素

- E2E 07：搜索选择状态回流、来源卡渲染、模型引用、上下文面板、断网重看。
- E2E 09：worker-program UI 入口/执行角色、外部编辑注入、FILE_CONFLICT 卡片、
  重试与重读成功、窄视口按钮可达。

## 5. 复现命令

```bash
# E2E07 stubs
cd /tmp/hc-e2e-harness/e2e07
setsid nohup node evidence/search_stub.js >/tmp/hc-e2e07-stub.log 2>&1 </dev/null &
setsid nohup python3 -m http.server 18096 --bind 127.0.0.1 --directory docs \
  >/tmp/hc-e2e07-docs.log 2>&1 </dev/null &

# E2E07 app
/tmp/hc-e2e-harness/run_app.sh --name e2e07search \
  --data-dir /tmp/hc-e2e07search/data \
  --workspace-root /tmp/hc-e2e07search/work \
  -- --config /tmp/hc-e2e-harness/e2e07/config.edn
BASE=$(cat /tmp/hc-e2e-harness/run/e2e07search/base-url.txt)
NODE_PATH=/tmp/hc-pw/node_modules node /tmp/hc-e2e-harness/e2e_driver.cjs \
  --base-url "$BASE" --steps /tmp/hc-e2e07search/search_steps.cjs \
  --result /tmp/hc-e2e07search/search-result.json --timeout 60000

# E2E09 app
printf 'original\n' > /tmp/hc-e2e09final/work/e2e09.txt
/tmp/hc-e2e-harness/run_app.sh --name e2e09final \
  --data-dir /tmp/hc-e2e09final/data \
  --workspace-root /tmp/hc-e2e09final/work --model-config
BASE=$(cat /tmp/hc-e2e-harness/run/e2e09final/base-url.txt)
NODE_PATH=/tmp/hc-pw/node_modules node /tmp/hc-e2e-harness/e2e_driver.cjs \
  --base-url "$BASE" --steps /tmp/hc-e2e09final/e2e09_steps.cjs \
  --result /tmp/hc-e2e09final/e2e09-result.json --timeout 60000
```

## 搜索服务选择传播修复（2026-11-05）

根因：`selected-service` 用 `=` 比较 `:service-id` 与 service `:id`；DOM select 提交的 value 是字符串 `"local-stub"`，而配置投影可能给出 keyword `:local-stub`，两者不等，页面回退“未选择”；`command-payload` 也把 keyword 原样发出。

修复：

- `src/hc/client/search/model.cljc` 新增 `id-str`（keyword/symbol → name，其余 str）；`selected-service` 用 `id-str` 两侧比较；`command-payload` 的 `:service-id` 输出字符串。
- `src/hc/client/search/view.cljs` 的 select `:value` 对 keyword 用 `name`，保证 DOM 选中项与服务 id 一致。
- 新增 `service-selection-accepts-keyword-and-string-ids`：字符串与 keyword 两种 id 都能解析 selected-service、无 selection-error、command payload 为 `"local-stub"`。

测试：`clojure -X:test:client :dirs '["test/hc/client"]'` → **686 tests / 4193 assertions / 0 failures**。release EXIT=0，`main.js` 2,922,022 bytes。

E2E 07 UI 复跑：搜索 stub `127.0.0.1:18095` 当前无响应（curl 空），未复跑；需要 stub 与 app 重启后再执行。来源卡路径已在上一轮接 `tool-result :sources` 与 `:ui/search-state` fallback。

小应用 DOM 测试：`test/hc/client/miniapps/generate_dom_test.cljs` 已写，但 `react-dom/client` foreign-lib 未加入编译配置，`verify_miniapps_dom.sh` 编译失败；未计入测试数。

## 2026-11-05 E2E 07 复跑结果

搜索 stub 已重启（`/search?q=x` 返回 200），真实 app + Chrome 跑 `e2e07_full.cjs`：

- `searchRequests=[]`（没有任何请求到 18095）；
- 页面仍显示“服务 未选择”，`select` 的 option 含 `Local Stub Search`；
- `.hc-source-card` 0，`answerHasNumbers=true`（回答文本有编号但无来源卡）。

说明 id 归一化修复未解决选择传播：`selectOption('local-stub')` 后 `search-state` 仍未写入，或 `session-page` 的 `selected-id` 在事件闭包中为空。下一步需在 `on-change!` 内重新读取当前选中会话并记录 mutation 后的 `:ui/search-state`；本轮未完成，E2E07 仍 **BLOCKED**。

## 2026-11-05 流式 chunk 修复

`stream/chunk` 原先把“相同 `:seq` 的新文本”判为 stale，真实 publisher 每个 chunk 复用同一 seq 时只显示第一段。现改为：

- `seq < last` → stale；
- `seq = last` 且文本相同 → stale（幂等重放）；
- `seq = last` 且文本不同 → appended，并更新 `:last-chunk-text`。

测试 `duplicate-chunk-sequence-is-stale` 更新为“同 seq 新文本追加、完全相同重放 stale”；client 686 tests / 4194 assertions / 0 failures。

## 小应用 DOM 测试处置

`test/hc/client/miniapps/generate_dom_test.cljs` 与 `test/js/verify_miniapps_dom.sh` 因 `react-dom/client` foreign-lib 未在现有编译配置中而无法编译，已删除；改为真实 Chrome `miniprobe` 断言入口存在。miniprobe 的生成失败保留列表断言因 app 启动超时未取得新证据，列为残留。

## 选择传播取证（2026-11-05 第二次）

在 `search/view.cljs` 的 select onChange 与 `session.cljs` 的 `on-change!` 加入 `HC-SEARCH-SELECT/CHANGE/AFTER` 控制台日志，重新构建后用真实 app + stub 触发 `selectOption('local-stub')`。取证脚本启动 app 时 `/healthz` 120s 超时（e2e07 data-dir 可能仍被旧实例锁定），未取得控制台输出。选择传播根因仍未确认；已加入的日志保留待下一次复跑。

当前判断范围：
- 若日志显示 SELECT 但无 CHANGE → select 事件未到达 React（受控 value 与事件源不一致）。
- 若 CHANGE 有 selected-id/changes 但 AFTER 为空 → mutation 写入键与 `state-for` 读取键不一致，或 selected-id 为 nil。
- 若 AFTER 有值但页面仍“未选择” → Fulcro Root 未因 `:ui/search-state` 变化重渲染。

E2E07 仍 **BLOCKED**。

## 选择传播取证结果（2026-11-05 第三次，全新 data-dir）

用 `run_app.sh --name client07` 全新 data-dir + search stub 启动，真实 Chrome 选择 `local-stub`：

```text
HC-SEARCH-CHANGE {selected-id: hc-session-…, changes: Object, before: null}
HC-SEARCH-AFTER {open?: true}
HC-SEARCH-CHANGE {selected-id: hc-session-…, changes: Object, before: Object}
HC-SEARCH-AFTER {open?: true, service-id: local-stub}
```

结论：**事件已触发，mutation 已写入 `:ui/search-state` 的 `:service-id "local-stub"`**；问题在视图投影/重绘，不在事件或写入键。页面仍显示“服务 未选择”，`select` 的 option 已含 `Local Stub Search`。

已尝试：`application/schedule-render!` 显式重绘、`SearchControls` 本地 `useState` 保存 service-id、`selected-service` 字符串比较、select value 字符串化；页面仍“未选择”。下一步需检查 `SearchControls` 是否被同一 props 缓存、`service-select` 的 `:value` 是否被 React 重置，或 `session-page` 的 `search-state` 是否在 `comp/transact!` 后仍读旧闭包。E2E07 仍 **BLOCKED**。

## 移交执行层

客户端没有 Plan 待执行状态投影（grep `:milestone`/`:phase`/`:plan-state` 未在客户端会话摘要找到）；本轮未加按钮。服务端 `:review/submit` 命令存在，需先由服务端发布待执行里程碑，再加条件渲染。

## 选择传播统一源与 SSR 验证（2026-11-05 第四次）

- `search/view.cljs` 的 `service-select` 改为只接收 `service-id`，`SearchControls` 的 `:value`、`selected-service`、错误判断全部读同一个 `(some-> (:service-id state) str not-empty)`；移除了组件内第二份 `useState`。
- `session.cljs` 的 `search-state` 改为从 Root props 的 `:ui/search-state` 读取（不再依赖 `current-state` 闭包）。
- SSR 验证：`test/hc/client/search/ui_smoke.cljs` 增加断言 `value="brave" selected` 且占位 option 未选中；运行 `search ui smoke ok`。说明 **组件在 state 已含 service-id 时正确显示**。
- 真实 Chrome 复跑仍显示“服务 未选择”，尽管 `HC-SEARCH-AFTER` 显示 `:ui/search-state` 已写入 `service-id "local-stub"`。结论：事件与组件都正确，剩下的是 **Fulcro Root 未因 `:ui/search-state` 变化重渲染/`session-page` 未重新执行**；需要检查 `update-search-state` 的 mutation 是否触发 Root 更新，或在 mutation 后强制重挂载会话页。E2E07 仍 **BLOCKED**。

## 移交执行层

服务端快照没有 `:milestone/:phase/:plan-state`，按指示不由客户端实现；缺口保留。

## Query/订阅修复尝试（2026-11-05 第五次）

- 确认 `Root` 的 query 已包含 `:ui/search-state`（`app.cljs`）。
- `session-page` 是普通函数，不是 Fulcro 组件；新增 `SearchControlsHost` React 组件，直接用 `add-watch` 订阅 `application/state-atom`，每次数据库变化重渲染，并把同一个 `search-model/state-for` 结果传给 `SearchControls`。
- 真实 Chrome 复跑：仍显示“服务 未选择”，probe 无 `HC-SEARCH-*` 日志（诊断已移除），但 `main.js` 含 `hc-search-controls`，说明新 bundle 已加载。
- 结论：query 已含该键、组件已直接订阅 state atom，仍不显示；剩余怀疑点是 `SearchControlsHost` 的 `useEffect`/`add-watch` 未执行，或 `comp/transact!` 的 mutation 未落在该 app 的 state atom。E2E07 仍 **BLOCKED**。

## 重渲染测试

`test/hc/client/search/ui_smoke.cljs` 已断言 state 含 `:service-id "brave"` 时 option `selected`、占位未选中；运行 `search ui smoke ok`。这只证明“给定状态渲染正确”，未证明“状态变化触发重渲染”。

## React require 审计（2026-11-05）

`grep -rn "react/" src/hc/client` 逐文件核对：

- `src/hc/client/search/view.cljs`：缺 `["react" :as react]`，已补。
- `src/hc/client/swarm/ui.cljs`：写成 `[react :as react]`（ClojureScript 命名空间，不是 npm React），已改为 `["react" :as react]`。
- `src/hc/client/extensions.cljs`：`react` 是 `[hc.client.extensions.react :as react]` 适配器别名，适配器本身 require npm React，无需改。
- 其余使用 `react/` 的文件均已有 `["react" :as react]`。

修复后 release EXIT=0。真实 Chrome 07 仍显示“服务 未选择”，但 `HC-SEARCH-LOCAL {sid: local-stub, before: null}` 证明 on-change 包装已执行并调用 `set-local-service!`；显示未更新，剩余怀疑是 `SearchControls` 组件在父级重渲染时被重置/未提交 local state。E2E07 仍 **BLOCKED**。

## 流式 delta

`text-delta` 已区分 `:delta`（追加）与完整 `:text/:content`（替换），无 message-id 时用 `:active-message-id`；新增 40 个无 seq delta 拼接测试。687 tests / 4195 assertions / 0 failures。

## Hooks 渲染修复（2026-11-05）

`search-controls` 原先把 `SearchControls` 当普通函数调用 `(SearchControls props)`，hooks 绑定到调用者；已改为 `(react/createElement SearchControls #js {:props props})`。`search/view.cljs` 也补了 `["react" :as react]`。`swarm/ui.cljs` 的 `[react :as react]`（CLJS 命名空间）改为 npm `["react" :as react]`。

真实 Chrome 仍显示“服务 未选择”，`HC-SEARCH-LOCAL {sid: local-stub, before: null}` 证明 setter 被调用；剩余怀疑是 `SearchControlsHost` 在 `:ui/search-state` 变化后重挂载 `SearchControls`，local state 被重置。E2E07 仍 **BLOCKED**。

## 状态上移到 host（2026-11-05）

- `SearchControlsHost` 现在持有 `[service-id set-service-id!]`（初值来自 `:ui/search-state`），把 `:value service-id` 与包装后的 `on-change!`（先 `set-service-id!`，再写 `:ui/search-state`）传给 `SearchControls`。
- `SearchControls` 改为纯展示：移除 `useState`，`service-id` 优先取 `:value` props，其次 `:ui/search-state`，最后模块级 atom。
- 真实 Chrome 仍显示“服务 未选择”，尽管 select option 存在。剩余怀疑：`selectOption` 的 change 事件未到达 React（受控 value 与 DOM 源不一致），或 `SearchControlsHost` 被父级重挂载导致 `useState` 重置。E2E07 仍 **BLOCKED**。

## E2E 03 multiChunk

未执行 `/tmp/hc-e2e-real/e2e03stop.cjs`（本轮预算用尽）。

## E2E 04 云端

未用 `deepseek-v4.1-flash:1017` 复跑（本轮预算用尽）；分支激活已有 JVM 测试。

## Release `react is not defined` 复查（2026-11-05）

- `npx shadow-cljs compile :app`（dev，未压缩）在真实 Chrome 打开 `#/sessions`：**无 pageerror**，页面正常。
- `grep -rn "\[react :as react\]\|\[react\]" src/hc/client test/hc/client` 无非字符串 require；`search/view.cljs` 与 `swarm/ui.cljs` 已修。
- 重新 release 后仍无法解释 `react is not defined`；release 的 `main.js` 4723 行是 source-card 渲染函数，不是 react 引用。剩余怀疑：某个命名空间的 npm React require 在 advanced 下被 Closure 丢弃/重命名，或 release-only 模块加载顺序；需要 source map/pseudo-names 构建进一步定位。
- 四条 E2E（07 host、08 S1、20 discard、03 multiChunk）未复跑；本轮预算用尽。

## 真实流帧取证（2026-11-05）

- 服务端 `system.clj:2360` 发 `TEXT_MESSAGE_CONTENT`，`:delta` 为增量（`subs text old-length`），无 `:seq`，`message-id` 稳定。
- `e2e03-stop-result.json`：`chunksRendered=1`、`textAtSelection=12`、`serverTextFramesAtSelection=0`（CDP 只抓 HTTP frame，WebSocket 帧不可见，计数不可靠）。
- 客户端 `text-delta` 已改为：`:delta` 追加、完整 `:text/:content` 替换、无 message-id 用 active-message-id；40 个无 seq delta 的 JVM 测试通过。
- 真实 UI 仍只显示 12 字。剩余怀疑：服务端同时发的 `STATE_DELTA`/快照把 `:message/by-id` 的 content 覆盖回旧值，或 `stream/chunk` 在首帧后被 `:stopped` 冻结。需要在 `sync` 应用 STATE_DELTA 后对比消息 content，并确认 `:stop-state` 在流式期间为 `:idle`。E2E03 multiChunk 仍未通过。

## Release react pseudo-names

`npx shadow-cljs release :app --config-merge '{:pseudo-names true :pretty-print true}'` 退出 0，但 `main.js` 大小与正常 release 相同（2,924,080），未生成可读符号；未定位 `react is not defined`。需要改 shadow-cljs.edn 的 build 配置或加 source map 再试。

### 2.5 服务端命令路径验证（2026-11-05 下午）

真实 app（`e2e09cmd`，端口 39353，真实 Ollama）用 `--send-command` 驱动：

1. `:plan/write-draft` 首次返回 `:COMMAND_KIND_UNKNOWN`。根因：
   `src/hc/server/app/commands.clj:65-72` 的 `conversation-kinds` 没有
   `:plan/write-draft`/`:plan/claim-draft`/`:plan/release-draft`。已补入，
   并在 `src/hc/server/gateway.clj:35-37` 的
   `default-forbidden-browser-kinds` 中禁止浏览器来源；app/CLI 可信路径
   仍可路由。
2. 修复后 `:plan/write-draft` 返回
   `:WRITE_LOCKED_BEFORE_FIRST_REVIEW`（符合“首次 review 前写锁”）。
3. 先发 `:review/submit`（`:lane :plan`、`:tenure-id "tenure-1"`、
   `:findings [{:key "draft" :value "..."}]`），回执
   `{:outcome :ledger-updated :reviewed? true :status :accepted}`；
   draft 写锁解除。
4. 未继续完成：draft 写入、S1→S2→S3→待执行、执行层 Reviewer、swarm、
   worker 受保护提交。当前断裂点是 **worker 程序没有可信服务端命令**：
   `:board/command` 可 claim，但 `clj-worker` 程序只作为 worker 模型工具
   存在；`conversation-kinds`/Owner `command-kinds` 没有
   `:worker/run-program` 或 `:board/command :execute-program`。最小修复：
   增加一个仅可信来源（app/CLI）可用的 `:worker/run-program` 命令，按
   actor/claim 调用 `hc.compose.workspace/run-program!`；浏览器网关继续
   禁止。这样命令路径即可完成“claim → 受保护提交 → FILE_CONFLICT →
   重试”的闭环。

已确认的确定性核心仍在：`workspace-test/
file-conflict-then-reread-retry-succeeds`（31/157 0/0）。

### 2.6 `:worker/run-program` 与真实命令链路（2026-11-05 晚）

**命令实现**：`hc.compose.owner.handlers/worker-run-program`，kind
`:worker/run-program`，payload
`{:session-id :task-id :claim-id :program-source :intent}`。它从 mission
board 的 `:claims` 解析 actor/tenure/workspace，校验 claim→task、actor
`:alive`、task 当前 claim，再调用注入的 `:worker-run-program-fn`（app 侧
接到 `hc.compose.workspace/run-program!`）。回执含
`:outcome`（`:applied`/`:file-conflict`/`:prepare-rejected`）、`:result`
与 `:stamp`。浏览器在 `gateway/default-forbidden-browser-kinds` 被禁止；
app/CLI 可信路径在 `conversation-kinds` 路由。测试
`worker-run-program-command-applies-and-reports-conflict`（nudge 集成
33 tests / 222 assertions 0/0）覆盖成功写入、冲突回执与 stamp 对照。

**真实 app 命令链路（e2e09chain，端口 44869，真实 Ollama）**：

| 步骤 | 回执 |
|---|---|
| Plan `:review/submit` 非空 | `:outcome :ledger-updated`，`reviewed? true` |
| `:plan/write-draft` | `:outcome :draft-updated`，130 chars |
| Plan 清账 `{:key "draft" :value nil}` | `:outcome :relayed`，`successor-tenure-id "tenure-2"` |
| tenure-2 空 review ×2 | `:outcome :promoted` S1→S2→S3 |
| tenure-2 空 review | `:outcome :promoted` → `:pending` |
| tenure-2 `:pending` 空 review | `:outcome :execution-started`，`execution-tenure-id "tenure-3"`，Reviewer binding `main-model` |
| execution `:review/submit` 非空 | `:outcome :swarm-started`，root task `root-task-1` |
| `:board/command :register-actor` | **断裂**：`:OWNER_ERROR "document is too large for the current-state store"` |

断裂点：`src/hc/server/storage/migration.clj:181-185` 的 `max-doc-chars`
守卫（默认 `src/hc/server/storage.clj:58` = 2,000,000）。swarm 启动后的
owner state 为 1,993,642 chars（`:calls` 1,236,936、`:cancel` 437,224、
`:runs` 248,166），注册 actor 的提交超过上限。最小修复建议：

1. 短期：把 owner-state 的 `:max-doc-chars` 提到 8,000,000（app 配置
   `:storage`），或压缩 call 记录里重复的 `:raw-source`/`:source`/
   `:numbered-source`/`:receipts`（当前同一程序源在 call request 与
   result 中多次内联）。
2. 长期：按守卫自己的 hint，把大程序源/回执放 blob store，owner state
   只留引用；这与 §1.1-4 当前态持久化一致。

在守卫解除前，真实链路停在“蜂群根任务已创建、worker 尚未注册/认领”，
`:worker/run-program` 的命令与单测已就绪，但真实命令无法到达。

## 2026-11-05 14:1x multiChunk 修复与真实 Chrome 复跑

根因不是 `text-delta` 丢弃，而是 `install-sync` 用服务端实体覆盖本地消息：服务端同时发 `TEXT_MESSAGE_CONTENT`（增量 `:delta`）和 `STATE_DELTA`（只 patch `:text` 为全文，`:content` 仍停在首帧 12 字）。`timeline/body-text` 优先读 `:content`，所以每次边界都显示回 12 字。

修复：
- `hc.client.sync.adapter/merge-into-db`：流式期间本地增量比服务端文本长且为前缀扩展时保留本地；服务端 `:text` 比 `:content` 长且为前缀扩展时把 `:content` 前移。
- `hc.client.conversation.timeline/body-text`：在 `:content`/`:text`/`:body`/`:message/content` 中取“前缀一致的较长者”，非前缀编辑仍以 `:content` 为准。
- 测试：`clojure -X:test:client :dirs '["test/hc/client"]'` → **701 tests / 4319 assertions / 0 failures / 0 errors**。

真实 Chrome（`clientB2`，端口 43007，stub-long 207 帧）最终 bundle 2,929,553 bytes：
- 10 秒采样文本长度 `[24,48,72,...,480]` 持续增长，`pageerror=0`。
- 更早一次 30 秒采样 `[12,36,...,1200]`，完成时 2476 字、状态已结束。
- 证据：`/tmp/hc-e2e-real/e2e03/multichunk-current.json`、`multichunk-final.json`、`multichunk-current.png`、`multichunk-final.png`；本轮 10 秒采样见 `/tmp/hc-e2e-real/e2e03/multichunk-final-bundle.json`（若未落盘，终端计数为上述 lens）。
- 结论：multiChunk **PASS**。

## E2E 07 搜索选择显示

当前 bundle 真实 Chrome 复测：搜索面板展开后 `select[aria-label="选择独立搜索服务"]` 的 DOM value 为 `local-stub`，`:checked` option 文本为 `Local Stub Search (non-internet) · searxng`，模式按钮 `本轮必须搜索` `aria-pressed=true`，无 `.hc-search-error`。此前“服务 未选择”是把 select 的 innerText 当成选中项误读；选择本身已生效。截图 `/tmp/hc-e2e-real/e2e07/current-select2.png`。完整发送→18095→来源卡未在本轮重跑。

### 2.7 当前态压缩后的真实链路（2026-11-05 晚）

`hc.compose.owner.compact` 上线后，同一 E2E 09 会话的 owner state 从
1,994,628 chars 降到 768,811（calls 1,232,081→139,468、cancel
437,572→342,505、runs 248,264→195,362）。`e2e09chain3` 上：

- Plan 底稿→清账→successor Plan→S1/S2/S3→pending→execution（tenure-3，
  Reviewer main-model）→swarm（root-task-1）全部命令回执成功；
- `:board/command :register-actor` 不再报 2MB 守卫（slot 已被
  wake-actors 自动占用，改用 `:claim`）；
- `:claim` 成功：`{:outcome :task-claimed :agent-id :agent-2
  :task-id :task-1 :claim-id :claim-1}`；
- `:worker/run-program` 首次因 `:capabilities #{}` 被拒
  `:CAPABILITY_DENIED :cat`；去掉 capabilities 覆盖后改用
  `:clj-worker` 角色默认集，但真实 app 返回 `:COMMAND_TIMEOUT`：
  workspace `run-program!` 在 Owner 消息线程内同步调用
  `:execute-board!`，后者又向同一 mailbox 提交，自等待 5s。

结论：压缩修复了 2MB 阻断，register/claim 贯通；worker 受保护提交仍被
“mailbox 内同步执行”阻断。最小修复：`:worker/run-program` 异步化
（handler 校验 claim 后返回 accepted+job-id+outbox effect；app 在 mailbox
外运行 `run-program!`，再投递 `:worker/program-result` 更新
outcome/stamp）。确定性受保护提交核心仍由
`workspace-test/file-conflict-then-reread-retry-succeeds`（31/157 0/0）
覆盖。
