# `hc.client.workbench.views` — 工作台视图挂载层

本目录只做“读 Fulcro db → 挂载对应模块组件”，不放业务规则、不发网络请求。
状态与命令归各自的 bridge / model；本层把 dispatcher 交给下层。

## 1 八个 view 文件

| 文件 | 公开入口 | 职责 |
|---|---|---|
| `shell.cljs` | `workbench-content`、`workbench` | 顶栏、五工作区导航、宽屏列/窄屏抽屉、覆盖层与焦点返回、通知栈。 |
| `session.cljs` | `session-page` | 会话列表、分支来源条，挂载 `hc.client.conversation.attachments.surface/conversation-with-attachments`（内含 conversation-page）。 |
| `board.cljs` | `board-page` | 挂载 `hc.client.swarm.ui/SwarmDashboardFromDb`。 |
| `materials.cljs` | `materials-page` | 材料页分区：文件浏览器、产物、记忆/世界书/指令卡、受管浏览器、MCP/技能、小应用；每个面板由对应 bridge 驱动。 |
| `browser.cljs` | `browser-pane` | 读 `:ui/browser`，把 `hc.client.workbench.browser-bridge/dispatch!` 交给 `hc.client.browser/browser-element`。 |
| `settings.cljs` | `settings-page`、`settings-workspace-element`、`data-section?` | 挂载 settings-ui 的 `settings-element`；仅在 `:data` 分区追加 diagnostics 与 backup。 |
| `assistants.cljs` | `assistants-page` | 从 `:ui/settings` 投影角色归属与供应商连接；提供进入真实设置编辑器的入口。 |
| `common.cljs` | `badge`、`icon`、`empty-state`、`facts`、`copy-text!`、`copy-button`、`focus-id!`、`notice-stack` 及 Framework7 React 包装 | 共享展示助手与 `react-factory` 包装；不重建 Framework7 的视觉语言。 |

## 2 边界

- view 只读 db 并委托；命令、回执、草稿、附件、同步状态都归各自模块。
- 不直接调用 Sente、HTTP 或 localStorage；需要动作时调用对应 bridge、`commands`
  或 `mutations` 的 dispatcher。
- `common.cljs` 的 Framework7 包装是唯一允许直接依赖 `framework7-react` 的共享层；其他 view 通过它组合。
- 会话面的选中/草稿/IME 规则以 `hc.client.conversation` 为准，本层不复制判断。

## 3 测试

| 覆盖 | 脚本/测试 |
|---|---|
| 外壳 DOM、导航、焦点与通知 | `test/js/verify_workbench_shell_dom.sh`（`shell.cljs`） |
| 会话命令与草稿交互 | `test/js/verify_workbench_session_commands.sh`（`session.cljs`） |
| 草稿持久化 | `test/hc/client/workbench/draft_storage_test.cljs`（`storage.cljs`，非本目录） |
| 模型/事件 | `test/hc/client/workbench/model_test.clj`、`events_test.clj`（非本目录） |

截至 2026-11-05，`board`、`browser`、`materials`、`assistants`、`settings` 五个 view 没有专属的 `test/js` DOM 脚本；它们的下层 model 由各自模块测试覆盖。补测试时优先加 DOM 脚本，而不是在本层塞业务断言。

## 4 非目标

- 不在本层实现新的面板或业务状态机。
- 不把 bridge 的回执归约逻辑搬进 view。
- 不用禁用按钮伪装尚未接线的能力；缺失能力由对应模块给出显式说明。
