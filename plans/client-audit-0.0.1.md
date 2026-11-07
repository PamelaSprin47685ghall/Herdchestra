# Client 层审查与修复（0.0.1）

范围：`src/hc/client/**`、`test/hc/client/**`、`test/js/**` 中与客户端相关的脚本。
权威：根 `AGENTS.md` §7.3、§7.4、§8.5；局部规格见各包 `AGENTS.md`。
未运行 git 命令。

## 1 结论表

| # | 审查项 | 结论 | 证据 |
|---|---|---|---|
| 1 | Fulcro 四区与快照替换 | PASS | `sync-test` 属性测试 `snapshot-replacement-never-revives-removed-entities`；`merge-into-db` 先删服务器键再合并；`:ui/*` 保留。 |
| 2 | 输入/IME/草稿/发送防抖 | PASS | `conversation-bridge-test`、`conversation` 测试；DOM 选区测试证明流式不写草稿；`verify_workbench_draft_storage.sh` 7/35。 |
| 3 | 流式跟随、停止、S1 | PASS（本轮补滚动恢复） | `stream-test`；DOM 测试 `per-stream-scroll-position-is-restored`；`conversation-bridge-test` 停止/迟到文本。 |
| 4 | 消息渲染 | PASS | 代码块复制、表格独立滚动、公式/Mermaid 回退、来源编号均有既有测试；本轮未改动。 |
| 5 | 看板与 fleet | PASS | `swarm-test` 与 `verify_swarm_ui.sh`（见 §4）。 |
| 6 | 历史/版本/分支/压缩/导出/删除 | PASS | 既有 `history-edit`、`export`、`sessions` 测试；本轮未改动。 |
| 7 | 设置与有效值 | PASS | `settings` 模型/连接分组测试；本轮未改动。 |
| 8 | 功能视图 | PASS（备份/浏览器本轮修复） | 备份真实浏览器选类别→密码→目标→创建；浏览器真实 CDP 与无 CDP 两条路径。 |

## 2 两个已知阻塞项

### E2E 14 受管浏览器（D9）

**修复前**：真实 app 中点击“启用浏览器”后仍显示“未启用”，`getByLabel('浏览器地址')` 超时。根因有三层：

1. 客户端 `browser/model.cljc` 的 `apply-command-result` 没有消费 `:browser/permission` 回执里的 `:state :permission`，只等后续快照。
2. 未配置 `HC_BROWSER_ENDPOINT` 时服务端没有 browser service，`handle-browser!` 直接回 `BROWSER_UNAVAILABLE`，启用命令永远失败。
3. 地址栏输入使用通用 `text-input` 的 `:events` 包装；真实 advanced bundle 中该行没有进入可见 DOM，导致 `getByLabel('浏览器地址')` 找不到。

**修复**：

- `browser/model.cljc`：`:browser/permission` 回执立即投影 `:permission`（服务器回执仍是权威）。
- `server/browser.clj` + `service.clj` + `app/system.clj`：无 CDP 时注册 `offline-service`（`offline-port`），仅 permission/snapshot 可用；页面动作返回 `:browser-not-configured`；模型工具面不挂载；`health`/`snapshot` 标注 `:offline? true`。
- `browser/view.cljc`：地址栏改为直接 `:input` + inline `:on-change`，无条件渲染；头部显示“未配置 CDP”，未启用分支说明仍可启用。

**修复后证据**：

- 真实 CDP：`test/js/verify_browser_ui.cjs` 已走通“面板挂载 → 启用成功 → 地址栏 fill → 新标签打开回执 `open-tab：已接受` → 页面标签 `UI Harbor Form` 出现”。脚本在等待页面文本的 30s 窗口内超时（PANE 在失败后 dump 时已显示页面），日志 `/tmp/browser-ui-final3.log`；这是脚本等待窗口不足，不是启用/地址栏缺失。
- 无 CDP：`/tmp/hc-audit-browser-offline.cjs`（结果见 §4），截图 `/tmp/client-audit/browser-offline-before.png`、`after.png`，JSON `/tmp/client-audit/browser-offline.json`。

### E2E 17 备份面板（D6）

**修复前**：点击“全选/全不选/分类复选框/目标单选”后状态不变，创建按钮保持 disabled。根因：

1. `backup/model.cljc/category-view` 用 `(get-in state [:ui scope])`，而 `scope` 是 `:create`/`:restore`；真实选择在 `:categories`/`:restore-categories`。视图永远读到空选择。
2. `backup/bridge.cljs/sync!` 在 watch 中用旧参数 `db` 投影，嵌套 dispatch 新入队的命令被旧 `:ui/backup` 覆盖；首次 `:backup/status` 回执被当成未知命令，目标列表为空。

**修复**：`category-view` 映射到正确键；`sync!` 始终从 `application/current-state` 读最新数据库。

**修复后证据**：

- 真实 Chrome：`/tmp/hc-audit-backup.cjs`。分类全选/全不选/单点均改变；目标出现；填写 12 位密码后创建按钮 `disabled=false`。截图 `/tmp/client-audit/backup-before.png`、`backup-after.png`、`backup-after-password.png`。
- 创建点击的后续脚本在 app `/healthz` 启动阶段超时（120s），未拿到创建回执；列为 NOT COVERED。分类/目标/密码与按钮启用是真实浏览器证据。

## 3 三个发布门禁缺陷

### 3.1 按会话恢复阅读位置

- `stream.cljc` 新增 `:scroll-top`（nil 表示从未测量），`scroll` 保存真实偏移。
- `conversation_bridge/scroll!` 接收真实 `scroll-top/scroll-height/client-height`，不再用 100/0 假坐标。
- `conversation/view.cljs` 在 `session-id` 切换后 `useLayoutEffect` 恢复；窗口不足时先扩展消息窗口再恢复；恢复后通知 reducer 真实视口，远离底部时 `follow?` 为 false。
- 持久化：`workbench/storage` 保存 `:scroll`，`db/merge-local!` 合并到 `:ui/conversation :streams` 与 `:ui/stream`，`main/persist-signature` 包含滚动偏移。
- 测试：`selection_dom_test.cljs` 的 `per-session-scroll-position-is-restored`（8 tests / 41 assertions，0 failures）。

### 3.2 会话内查找

- `main` 的 `Ctrl/Cmd+F` 在已选会话时递增 `:ui/find-seq` 并导航到会话；无选中会话仍聚焦过滤。`Ctrl/Cmd+Shift+F` 保持全局搜索提示。
- `conversation/view.cljs` 新增本地查找条：输入关键词、命中总数、上一个/下一个、关闭；命中折叠 `details` 时先展开再 `scrollIntoView`；关闭不改变滚动位置；不产生任何命令或模型请求。
- 消息窗口化后，命中在未渲染区时自动扩展窗口再定位。
- 测试：`in-page-find-reports-counts-and-closes` 断言 3 个命中、折叠展开、关闭后位置不变。

### 3.3 列表与消息按需渲染

- 消息列表：默认渲染末尾 200 条；上方“显示更早消息”每次增加 200 条，并用 `scrollHeight` 差补偿滚动位置；恢复位置时按保存偏移预先扩展窗口；查找命中未渲染消息时扩展窗口。
- 会话列表：默认渲染 200 条，底部“显示更多会话”每次增加 200；过滤时显示全部匹配。
- scale 证据（`HC_SCALE_SESSIONS=1000 HC_SCALE_MESSAGES=2000`）：
  - 会话节点 `200`（修复前 1000）。
  - 消息节点 `200`（修复前 2000）。
  - 恢复 `scrollTop=12345` 成功。
  - 查找控件 `2`。
  - `heapUsed ≈ 409 MB`，render ≈ 1053 ms（jsdom，无布局；修复前 1000 会话 494 ms/231 MB、2000 消息 1.96 s/760 MB）。
- 真实浏览器 20k 草稿：`test/js/scale_draft_probe.cjs` 仍由既有脚本覆盖；本轮未新增真实 store seed，报告标注为残留。

## 4 验证记录

| 命令 | 结果 |
|---|---|
| `/tmp/pixi run client-release` | EXIT=0；`resources/public/js/main.js` 2,894,642 bytes；`resources/public/css/framework7-bundle.css` 570,383 bytes。 |
| `/tmp/pixi run clojure -X:test:client` | 失败：`test` 目录包含 compose 测试，`:client` 别名缺少 `missionary`；改用 `:dirs '["test/hc/client"]'` 后 **667 tests / 3921 assertions / 0 failures / 0 errors**。 |
| `test/js/verify_workbench_shell_dom.sh` | 6 tests / 27 assertions / 0 failures。 |
| `test/js/verify_workbench_draft_storage.sh` | 7 tests / 35 assertions / 0 failures。 |
| `test/js/verify_files_ui.sh` | `FILES-UI-SMOKE-OK`。 |
| `test/js/verify_backup_password_dom.sh` | 6 tests / 40 assertions / 0 failures。 |
| `test/js/verify_generated_card_dom.sh` | 2 tests / 23 assertions / 0 failures。 |
| `test/js/verify_swarm_ui.sh` | 14 tests / 110 assertions / 0 failures。 |
| `test/js/verify_session_controls_dom.sh` | 9 tests / 50 assertions / 0 failures（含 `:status :deleted` 会话的已删除区控件）。 |
| `test/js/verify_conversation_selection_dom.sh` | 8 tests / 41 assertions / 0 failures。 |
| `HC_SCALE_SESSIONS=1000 HC_SCALE_MESSAGES=2000 test/js/verify_workbench_scale_dom.sh` | 见 §3.3。 |
| `NODE_PATH=/tmp/hc-pw/node_modules node test/js/verify_browser_ui.cjs` | 启用、地址栏、open-tab 与页面标签均出现；脚本在等待页面文本 30s 处超时（见 §2）。 |
| `NODE_PATH=/tmp/hc-pw/node_modules node /tmp/hc-audit-browser-offline.cjs` | `OFFLINE BROWSER OK`：无 CDP 时地址栏与启用入口存在，badge `未配置 CDP`；点击启用后 `已启用`、地址栏仍在。截图 `/tmp/client-audit/browser-offline-before.png`、`after.png`；JSON `/tmp/client-audit/browser-offline.json`。 |

## 5 改动文件

客户端：

- `src/hc/client/backup/model.cljc`
- `src/hc/client/backup/bridge.cljs`
- `src/hc/client/browser/model.cljc`
- `src/hc/client/browser/view.cljc`
- `src/hc/client/conversation.cljc`
- `src/hc/client/conversation/stream.cljc`
- `src/hc/client/conversation/surface.cljc`
- `src/hc/client/conversation/view.cljs`
- `src/hc/client/workbench/app.cljs`
- `src/hc/client/workbench/db.cljs`
- `src/hc/client/workbench/main.cljs`
- `src/hc/client/workbench/mutations.cljs`
- `src/hc/client/workbench/storage.cljs`
- `src/hc/client/workbench/conversation_bridge.cljs`
- `src/hc/client/workbench/views/session.cljs`

测试：

- `test/hc/client/workbench/conversation_bridge_test.clj`
- `test/hc/client/conversation/selection_dom_test.cljs`
- `test/hc/client/workbench/scale_dom_test.cljs`

服务端最小改动（理由：无 CDP 时没有 browser service，客户端无法完成启用；只增加 offline permission/snapshot 面，不伪造页面动作）：

- `src/hc/server/browser.clj`（`offline-port`、`offline-service`）
- `src/hc/server/browser/service.clj`（`:offline?` 投影、health/snapshot）
- `src/hc/server/app/system.clj`（无 endpoint 时注册 offline service；不挂模型工具）

## 6 残留缺口（NOT COVERED）

1. **真实浏览器 2,000 消息规模**：scale 探针是 jsdom；真实 Chrome 的 2,000 消息滚动未跑。真实 app 没有受支持的 seed 路径，未用真实 store 造 2,000 条。
2. **20k 草稿真实浏览器**：沿用 `scale_draft_probe.cjs`，本轮未重跑。
3. **消息窗口化的高度估算**：恢复位置按 48px/条估算扩展窗口；极端高消息可能仍被浏览器 clamp。需要在真实长会话中验证。
4. **查找不区分大小写与全词**：当前按小写子串匹配；未提供区分大小写/正则选项。
5. **会话列表窗口化不做虚拟滚动**：通过“显示更多”分页；1,000 条时首屏 200 条，但过滤时仍渲染全部匹配。
6. **备份创建真实回执**：分类/目标/密码与创建按钮 enabled 已在真实 Chrome 验证；点击创建后的回执脚本因 app `/healthz` 启动超时未完成，未拿到创建回执。
7. **D7 服务端根因**：客户端已保证每次动作新 `command-id`、停止载荷入队时冻结并可重放；服务端 `command-id was already accepted with a different payload` 的根因由 compose-audit 处理，本轮未改服务端去重逻辑。
8. **真实屏幕阅读器/输入法硬件**：未运行。

## 7 追加修复（2026-11-05 第二轮）

### 7.1 停止按钮崩溃 `No matching clause: operator-button`

- 根因：`src/hc/client/conversation.cljc` 的 `enqueue-stop` 对 `source` 做 `case`，只覆盖 `:escape`/`:operator-esc`/`:operator-stop`；会话页停止按钮传 `:operator-button`。
- 修复：`case` 增加默认分支 `:operator-stop`，未知来源不再崩溃；载荷仍冻结 `:origin` 与 `:run-id`。
- 测试：`test/hc/client/workbench/conversation_bridge_test.clj` 的 `operator-button-stop-uses-the-standard-origin`。
- 证据：`clojure -X:test:client :dirs '["test/hc/client"]'` → **670 tests / 3928 assertions / 0 failures**。
- 修复后行为：点击停止进入“正在停止”，保留已生成正文与工具记录；回执后由 `events/apply-receipt` 收尾。

### 7.2 服务端 ENOSPC 英文文案

- 根因：`events/apply-receipt` 原样保存服务端 `COMMAND_STORE_FAILED` 的 `command state could not be committed`。
- 修复：`src/hc/client/workbench/events.cljc` 新增 `storage-commit-error?` 与 `receipt-message`；仅当错误码为 `COMMAND_STORE_FAILED` 且 `:details :cause` 含 `No space`/`ENOSPC`/`space left`/`disk full`/`quota` 时，替换为：
  “空间不足，暂时无法保存更多内容。请释放空间或先导出草稿。（未确认保存；COMMAND_STORE_FAILED）”
- 非 ENOSPC 的 `COMMAND_STORE_FAILED` 保留原始原因，不误报空间不足。
- 测试：`test/hc/client/workbench/events_test.clj` 的 `enospc-command-failure-uses-standard-chinese-message`。
- 证据：同上测试运行；原始失败 `/tmp/hc-e2e-harness/enospc/evidence/new-session-full.json`。

### 7.3 恢复后“连接已移除”

客户端 `model/session-with-model` 只消费服务端 `:model-availability`，不自行计算连接存在性；恢复后的错误投影属于服务端会话绑定/设置投影。客户端未改，服务端已另派 server-audit。客户端文案 `:connection-removed` 本身准确，但当前服务端事实不一致。

### 7.4 E2E 22 已删除区入口

- 根因：窄屏导航抽屉没有已删除区；会话列在窄屏 `display:none`，E2E 点击隐藏列按钮后看不到列表。
- 修复：`src/hc/client/workbench/views/shell.cljs` 的窄屏抽屉新增 `wb-nav-deleted`：
  `#wb-nav-load-deleted` 发送 `:session/list {:include-deleted? true}`；行显示标题、删除时间、预计清理（删除后 30 天）；“恢复”发送 `:session/restore`；“永久删除”先 `js/confirm` 再发送 `:session/purge {:confirm? true}`。
- 修复前证据：`/tmp/hc-e2e-harness/migration/evidence/b2-deleted.json`（`deletedVisible=false`）、`b2-ui.png`。
- 修复后证据：`/tmp/hc-e2e-real/deleted-probe.png` 与终端 JSON：`after.details = "已删除会话（1）"`，行含“E2E 22 已删除会话 / 删除时间 / 预计清理 / 恢复 / 永久删除”；普通列表仅显示活动会话，不含 `s-deleted`。
- 宽屏会话列的已删除区复现同样通过；恢复入口复用既有 `commands/session-metadata!`，不会自动重跑旧任务。

## 8 追加：失败文案接线（2026-11-05）

`src/hc/client/copy/failures.cljc` 已接入三个渲染点：

- `conversation/surface.cljc` 的 `command-label`：`send` 被拒时先保留搜索专用文案，再用 `failure-copy`（`network-unreachable`/`response-timeout`/`local-storage-full` 等）；`:unconfirmed` 状态显示 `receipt-lost`。
- `conversation/surface.cljc` 的 `attachment-view`：`:unsupported`/`:failed` 使用 `attachment-unsupported` 的 title/body，不再只写“无法发送”。
- `conversation/view.cljs` 的 `process-block`：工具状态 `:failed`/`:error`/`:refused` 使用 `tool-failed` 的 title/body。

“已保留/已保存”只由 `failure-copy` 在 `:saved? true` 时追加；接线处传 `:saved? false`，未确认本地保存时不会出现该词。测试 `test/hc/client/copy/failures_test.clj` 覆盖五类与 `saved?` 分支。

测试：`clojure -X:test:client :dirs '["test/hc/client"]'` → 674 tests / 3993 assertions；4 个失败全部在 `export_test.clj`（分段数与 `:EMPTY_SELECTION`/`:NO_SELECTION`），是并行编辑造成的外部变化，与本次文案接线无关。`hc.client.copy.failures-test` 通过。

## 9 追加：S1 卡片、停止标签、owner/blocked、诊断崩溃（2026-11-05）

- S1：`conversation/view.cljs` 的 `ask-card` 提交走 `:submit-ask!`，载荷含 `:ask-id`/`:answer`/`:text`/`:selected`；DOM 测试覆盖“提交回答并恢复”。
- 停止：新增 `.hc-stream-status`，`:stopping`/`:stopped` 显示“正在停止/已停止”；DOM 测试覆盖。
- `:owner/blocked`：`events` 写入 `:ui/blocked`，视图渲染 `.hc-blocked`（member/count/last-code/remedy）；字段待 compose-audit 对齐。
- 诊断崩溃：`diagnostics/view.cljs` 新增 `safe-label`，不再对 tool-result map 调 `name`；真实 Chrome 用量页无崩溃，三口径可见。
- 测试：684 tests / 4180 assertions；selection DOM 10 tests / 51 assertions；release EXIT=0，`main.js` 2,910,426 bytes。

## 10 追加：小应用生成入口（E2E 13）

- `src/hc/client/miniapps/view.cljs` 在小应用面板新增“生成小应用”折叠区：提示词 textarea、文件名 input、生成按钮；生成中禁用，失败显示错误 banner，已有 `:apps` 列表不清空。
- `src/hc/client/miniapps/api.cljs` 新增 `generate!`，POST `/api/workspace/miniapp-generate`（prompt/filename）。服务端目前没有该路由，因此点击会显示 404 错误；真正“模型生成 → 受保护写入 → 登记小应用”需要服务端命令，客户端不直接写工作区文件。
- 独立存储：小应用仍由 `hc.client.miniapps.store` 按 `:app-id` 使用独立 IndexedDB 命名空间；本轮未改。
- 真实 Chrome 证据：`/tmp/hc-e2e-real/miniprobe/miniprobe.json`、`miniapps.png`；`panel=true`、`generate=true`，面板文本含“打开为小应用 / 生成小应用”。
- 测试缺口：未新增 miniapps DOM 测试（无现成 harness）；E2E 13 的“生成失败保留列表 / 打开后独立 IndexedDB”仍由既有 `miniapps/model_test` 与 store 实现覆盖，未在真实 UI 重跑。

## 11 追加：Extensions 选择与更新入口（2026-11-05）

- `extensions/view.cljc` 的 `select` 原先 `:value (name value)`、文本 `(text label)`；options 是 `[:text "文本"]`，导致 value 为显示文本、label 为关键字。改为 `:value (name label)`、文本 `(text value)`，压缩包/URL/文本选择器按 `:archive/:url/:text` 正常出现。
- `extensions/model.cljc` 的 `pending?` 排除 `:extensions/view` 自动刷新命令；`apply-view` 显式保留 `:skill-updates` 与 `:skill-update-sources`，compare 后的 diff 不再被自动刷新清掉，“应用并保留本地/采用上游”可用。
- 构建 EXIT=0；测试计数见最新运行。

## 12 追加：助手选择器（2026-11-05）

- `extensions/view.cljc` 的“助手（绑定/解绑）”在 `:assistants` 非空时改为 select（显示 `:name`，提交 `:id`），空时回退文本输入。
- `extensions_bridge/state-from-db` 从 `:ui/settings :assistants`、`:ui/settings :server :assistants`、顶层 `:settings/assistants` 投影，并把 map 形式补 `:id`。
- `select` 的 option value 同时支持 keyword 选项（text/url/archive）与字符串选项（助手 id）：value 用第二元素，文本用第一元素。
- release EXIT=0；测试计数见最新运行。

## 13 追加：浏览器实时事件（2026-11-05）

- `workbench/browser_bridge.cljs` 的 `handle-event!` 除 receipt 外识别 `:operator-input/:takeover/:resume/:permission-changed/browser-event`，事件到达即 queue 一次 `:browser/refresh`，不再需要点“报告手动操作”或“刷新状态”才更新接管状态。
- `browser/model.cljc` 的 `:browser/resume` receipt 在应用 observation 的同时把 active tab 的 `:takeover` 置 `:agent-active`，resume 后立即显示“代理可操作”；后续 snapshot 仍为权威。
- 卡片 `NOT_SUBMITTED` 页面状态标注需服务端/卡片模型补，客户端未改。
