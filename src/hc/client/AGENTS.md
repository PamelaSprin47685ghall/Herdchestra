# hc.client AGENTS.md — 浏览器端瘦客户端

> 权威顺序：根 `AGENTS.md` > `src/AGENTS.md` > 本文件。
> 本文件定义 Fulcro + Framework7 React 客户端边界；交互合同以根 §7.3、§7.4 为准。

## 1 责任

1. **Fulcro 状态库**：保存服务器实体的归一化显示副本与浏览器本地状态。组件从状态库读取数据，不各自维护第二份业务真相。
2. **AG-UI 接收适配器**：接收 Sente 上的 AG-UI 事件，统一应用 `STATE_SNAPSHOT`、`STATE_DELTA`、`MESSAGES_SNAPSHOT` 与项目 `CUSTOM` 事件，并把结果一次性转换为 Fulcro 归一化实体更新。
3. **Framework7 React 视图**：提供会话与分支、Swarm 看板与 fleet、助手配置、材料与扩展、设置与数据中心五类工作区。业务组件通过薄包装处理命令提交态与合法性禁用，不重造整套样式方言。
4. **草稿与命令跟踪**：每条会话独立保存文本、待发附件、引用卡片与光标位置；命令携带 `command-id` 与 `command-epoch`，在服务器确认前只显示“正在提交 / 已请求停止”。

## 2 状态五区

1. **服务器实体副本**：`:session/by-id`、`:swarm/by-id`、`:message/by-id`、`:task/by-id`、`:action/by-id` 等。
2. **本地视图状态**：`:ui/selected-panel`、`:ui/expanded-tools`、弹层目标 ID。绝不混入不可序列化的 DOM、编辑器实例或组件句柄。
3. **未提交草稿**：`:ui/draft`、编辑中消息草稿与待传附件；记录所提交的草稿版本号。确认后只清理已提交的那一版。
4. **命令跟踪表**：`:ui/command`，按 `command-id` 保存提交状态与回执。
5. **会话选择**：`:ui/selected-session-id` 是唯一客户端选择；`:session/active-id` 是服务器进程级展示/首屏 bootstrap，不是命令目标，也不由本地选择写入。所有会话命令显式携带 `:session-id`。

## 3 铁律

1. **单一权威**：Fulcro 数据库只是服务器当前态的显示副本加本地草稿。任何业务命令都发往服务器 Owner；客户端不伪造领域终态。
2. **快照替换防复活**：服务器因永久折叠、Scope 修剪或接力屏障更新集合时，替换对应集合引用并清理已移除实体。禁止无条件浅层 `merge` 让旧记录复活。
3. **卸载不取消任务**：关闭标签页或卸载组件只释放观察订阅，绝不取消服务器正在运行的任务。
4. **五阶段连接状态**：`:disconnected` → `:connecting` → `:connected-awaiting-sync` → `:synced` → `:interrupted`。不用单一 `online?` 布尔值压平。
5. **命令去重**：同一 `command-id` 在 `command-epoch` 有效窗口内重发直接返回已接受结果；epoch 退休后未知旧命令不自动当新工作执行。
6. **IME 安全**：中文/日文等输入法处于组合或候选确认态（`isComposing`）时，`Enter` 只确认候选词，绝不发送消息或截断末尾字符。双击或连按快捷键只产生一条用户消息。
7. **草稿保护**：服务器推送、他端更新、切换会话、旋转屏幕或重启应用都不得冲掉正在输入的光标、选区与 IME 组合态。空间不足无法落盘时持续显示“草稿暂未保存”并提供复制与另存。
8. **流式阅读**：用户在底部时跟随输出；用户主动上滚时停止强拉并显示“返回最新内容”。流式更新不清除已选中的文本。
9. **工具参数结束不等于执行结束**：收到工具参数流结束只表示调用描述完整，必须等服务器领域状态返回工具终态才关闭等待展示。
10. **边界类型**：跨语言协议使用字符串形式的稳定 ID；严格区分字段缺省、空字符串 `""` 与 `null`。单条 Sente 消息不超过 1 MiB，大对象走独立 HTTP/Blob 通道；超限的 `STATE_SNAPSHOT` / `STATE_DELTA` 以 `/sync-objects/<token>` 引用传输，客户端校验域分隔摘要后才原子应用。
11. **无障碍与输入法**：焦点顺序符合视觉顺序；弹层关闭后焦点回到触发点；图标有可读名称；正文对比度至少 4.5:1；触摸热区至少 44×44 逻辑像素；`Escape` 先关最上层弹层，再交输入法候选/文本选区，最后才停止生成。

## 4 命名空间

当前：`hc.client.sync.*`（`adapter`、`connection`、`json-patch`、`messages`、`normalize`）、
`hc.client.artifacts.*`（产物卡片、来源披露与真实打开/下载动作）、
`hc.client.conversation.*`（纯草稿/输入法/流/时间线/ask/导出、`export_plan` 选择/取消/长图计划、`minimap` 话题概览与锚点，以及 React 会话面）、
`hc.client.copy.*`（24 类失败文案单一事实来源与可执行动作）、
`hc.client.materials.*`（长期记忆、世界书、指令卡模型与视图）、
`hc.client.search.*`（联网搜索三态控件、服务选择、来源卡与发送 payload）、
`hc.client.extensions.*`（MCP/技能配置、启停、草稿与真实命令）、
`hc.client.settings.*`（设置状态、草稿、命令 outbox 与 React 设置树）、
`hc.client.backup.*`（加密备份/恢复状态、命令 outbox、冲突预览、恢复点与 React 面板）、
`hc.client.swarm.*`（fleet/任务树投影与 React 看板）、
`hc.client.sessions.*`（会话重命名/置顶/归档/恢复/删除控件、真实命令回执与已删除列表）、
`hc.client.files.*`（工作区文件浏览器、stamp 展示与行差异）、
`hc.client.diagnostics.*`（用量/费用统计、详细日志、脱敏诊断包与反馈草稿）、
`hc.client.browser.*`（受管浏览器标签、观察、接管与页面卡）、
`hc.client.miniapps.*`（工作区小应用注册、隔离存储与受限预览）、
`hc.client.sandbox.*`（沙箱文档、CSP、消息闸门与外部动作确认）、
`hc.client.preview.*`（生成的 HTML 受限预览与版本切换）、
`hc.client.schedule.*`（定时任务状态、时区预览、命令 outbox 与 React 面板）、
`hc.client.speech.*`（录音/转写草稿、朗读播放器、语音服务设置、独立翻译页与选区安全替换）以及
`hc.client.workbench.*`（Fulcro 根、Sente 桥、命令封装、五工作区外壳）。
局部规格见对应包的 `AGENTS.md`；失败文案契约见
`src/hc/client/copy/AGENTS.md`；导出/话题概览见
`src/hc/client/conversation/AGENTS.md`；工作台契约见
`src/hc/client/workbench/AGENTS.md`。

路由只选单一权威来源。应用根容器由单一启动入口挂载：Fulcro 根包裹 Framework7 `App` / `View` / `Page`。禁止两套导航 watcher 互相监听同步。

## 5 构建与测试

客户端使用 ClojureScript 编译，Framework7 React 是 npm 依赖，放在仓库根 `package.json`（当前客户端包清单）中，不写入 `deps.edn`。ClojureScript 编译器与 Fulcro 依赖在 `:cljs:client` 别名中。浏览器构建使用 `shadow-cljs.edn` 的 `:app` 构建；`src/hc/client/swarm/AGENTS.md` 记录 dashboard 的 Node 渲染验证命令。

```bash
clojure -M:cljs:client -re node
```

`:cljs` 别名已提供 `:main-opts ["-m" "cljs.main"]`；命令行不要再重复 `-m cljs.main`。

测试：

1. 归一化实体更新与快照替换：随机事件序列下，已移除实体不复活，未声明字段不被误清空。
2. 命令去重：同一 `command-id` 重发只产生一次效果；epoch 退休后旧命令返回不确定提示。
3. 草稿与 IME：随机输入/推送交错下，草稿版本号只清理已提交版本，组合态不被服务器更新打断。
4. 连接恢复：断线、重连、旧订阅增量、慢客户端积压都落到正确连接状态。
5. E2E 01–22 中与客户端相关的剧本按根 §8.5 执行；未运行就标 `NOT RUN`。
6. Swarm/fleet dashboard：JVM 投影测试覆盖任务树、等待释放与父项不自动完成、槽位生命周期、holder/W 区分、完整 join 报告；CLJS 渲染测试用 `react-dom/server` 渲染真实 React/Framework7 元素树并检查可访问标签与响应式 CSS。
7. 会话元数据控件：纯模型测试覆盖动作门控、命令 payload、回执文案与已删除列表归一化；`test/js/verify_session_controls_dom.sh` 在 jsdom 中挂载真实 `SessionControls`，点击重命名/置顶/归档/恢复/删除/永久删除，断言回调收到 keyword `:action` 与真实 `:session/*` kind，删除与永久删除需要第二次显式确认，永久删除只携带 `{:confirm? true}` 且不含工作区路径，回执未决时已打开的表单或确认框不能再次发送；`test/js/verify_workbench_session_commands.sh` 用真实命令 helper 证明已删除行只存在于 Fulcro 显示库 `:ui/session-list` 时仍按同一 `sid` 取得 Owner epoch，缺失会话不会借用 app 或其它会话 epoch；浏览器冒烟再验证只有服务器回执或下一份快照改变界面，已删除行不可选中且临时会话不进入已删除区。
8. 工作台抽屉层：`model/overlay-stack` 只在 ≤1023px 抽屉布局把导航/详情当作 Escape 覆盖层；`test/js/verify_workbench_shell_dom.sh` 在 jsdom 挂载真实 `shell/workbench-content`，断言宽屏详情列打开时没有 `.wb-drawer-backdrop`、关闭按钮仍可提交，窄屏才渲染覆盖层，打开时焦点进入 `#wb-aux-close`、关闭时回到实际开启者。CSS 固定覆盖层 `z-index:30`、抽屉 `40`，关闭抽屉使用 `visibility:hidden` 退出 Tab 顺序。
9. 失败文案与纯逻辑导出：`hc.client.copy.failures-test` 覆盖 24 类、三口径
   互异、`:saved?` 两分支、未知用量不写 0；`conversation.export-test` 覆盖
   选择/版本/取消/长图/重复 id；`conversation.minimap-test` 覆盖空列表、混合
   类型、截断与锚点稳定。测试命令见 `conversation/AGENTS.md`。
