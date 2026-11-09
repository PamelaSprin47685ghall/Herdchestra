# hc.client.workbench — Fulcro + Framework7 工作台外壳

> 权威顺序：根 `AGENTS.md` §7.3–§7.4 > `src/AGENTS.md` > `src/hc/client/AGENTS.md` > 本文件。
> 本包只负责浏览器外壳、连接桥与命令封装；会话、设置、Swarm 视图由相邻包拥有。

## 1 责任

1. **单一挂载**：`main/init` 只挂载一次 Fulcro 根；根包裹 Framework7 `App`/`View`/`Page`。路由只有 `:ui/route` 一个权威，由 `routing` 读写 URL hash。`hashchange` 不因程序内导航而被抑制；重复应用同一路由是幂等的，直接改 hash、浏览器前进/后退与侧栏按钮走同一条路径。
   `#/swarm/:id` 是 `#/board/:id` 的只读别名：`model/parse-hash` 把第一段
   `swarm` 映射为 `:board`，第二段由 `views.board` 作为 `swarm/selected-id`
   交给看板；路由本身不新增字段。
2. **连接桥**：`sync-bridge` 持有 `hc.client.sync` 纯适配器状态；收到 `STATE_SNAPSHOT`/`STATE_DELTA`/`MESSAGES_SNAPSHOT` 时替换 Fulcro 的服务器显示副本。非状态事件进入 `events` 归约，再交给会话/设置组件。
3. **命令封装**：`commands` 构造 `command-id` 与 `command-epoch`，先写入 `:ui/command`，再交给 Sente。只有服务器回执才清草稿或关闭轮次；点击本身从不宣布成功。
4. **诚实不可用**：`model/availability` 给出每个按钮的 `:enabled?` 与 `:reason`。视图必须显示原因，不保留无效果按钮。
5. **本地状态**：`storage` 持久化每会话草稿与外观；失败时显示“草稿暂未保存”，绝不假报已保存。
6. **不暴露调试句柄**：`app.cljs` 不把 Fulcro app 或 state atom 挂到 `window`。`window.hc_app`/`window.hc_app_state` 会暴露完整数据库、未保存草稿与密钥输入，而浏览器控制台调试不是用户功能。需要临时调试时用本地补丁，不提交。
7. **客户端在场附着**：`transport/stable-client-id` 生成稳定握手身份，存储键为
   `hc.client-id`，按 `sessionStorage → localStorage → 随机 UUID` 三级回退；
   同一标签页 reload 后仍沿用原身份。`presence-bridge` 只在已确认基线没有
   控制器时，按 `(session-id, subscription-id)` 发送一次
   `:session/attach-client`；发送前先记忆该键，回执、重绘或重复基线都不会
   变成循环。服务器已报告控制器时绝不抢占。观察者发送业务命令被
   `:NOT_CONTROLLER` 拒绝时，命令回执带 `:action :takeover`，界面只提供
   显式“接管控制”按钮；接管仍是操作员的显式动作，绝不由打字或吞掉拒绝
   自动触发。

### 1.1 草稿持久化的浏览器边界

`storage` 把每会话草稿写入 `localStorage` 的 `hc.workbench.v1` 键，只在同一 origin、同一浏览器 profile 内可见。页面 reload、服务器重启、同一 Playwright context 内的导航都会恢复；新建浏览器 context/profile、清空站点数据或换设备是新的本地库，首屏草稿为空是预期行为，不是服务器持久化丢失，也不是“选错会话”。

`storage/draft-signature` 覆盖 `:text`、`:cursor`、`:selection`、`:attachments`、`:quotes` 与 `:version`；光标、附件或引用单独变化也会在 350ms 去抖后落盘。`:saved-version`、`:save-state`、`:composing?`、`:undo`、`:updated-at` 是派生或瞬态字段，不触发二次保存。

服务器 session record 的 `:drafts` 是按 `client-id` 的 Owner 状态，不进入 `STATE_SNAPSHOT`，也不作为跨 profile 的草稿备份。E2E01 的“退出重开”必须复用同一个 Playwright context，或显式传入 `storageState`；只复用服务器 `--data-dir` 不够。`page.reload()` 会恢复当前 hash（例如 `#/settings`），所以重开后要先导航到 `#/sessions` 再等 `.wb-session-list`；只等会话列表而不改路由，会把“设置页仍在”误报成启动失败。

## 2 文件与相邻组件

| 文件 | 责任 |
|---|---|
| `main.cljs` | 启动、外观、持久化 watch、全局 Escape、hash 路由、Sente 启动 |
| `app.cljs` | Fulcro 根与查询；挂载 `views.shell/workbench` |
| `db.cljs` | 初始 `:ui/*`；不预置任何服务器实体 |
| `model.cljc` | 纯投影：路由、连接、实体列表、命令/草稿视图、可用性 |
| `events.cljc` | 纯运行事件：文本块、工具调用、回执、S1 ask |
| `conversation_state.cljc` | 会话 reducer 的 workbench 适配：`:ui/conversation`、平面键镜像、服务器事件 → reducer 动作 |
| `conversation_bridge.cljs` | 操作员手势 → reducer → outbox 排水 → Sente；回执监听 |
| `commands.cljs` | 发送/停止/回答委托给 conversation-bridge；应用级命令与重同步仍在此 |
| `sync_bridge.cljs` | 纯适配器 ↔ Fulcro ↔ Sente；事件监听器注册 |
| `presence_bridge.cljs` | 一次性 `:session/attach-client`；稳定握手身份；不抢占已有控制器 |
| `settings_bridge.cljs` | settings-ui 状态、服务器快照、回执、命令 outbox |
| `materials_bridge.cljs` | `:ui/materials`、材料快照/回执、启动权威读取与陈旧事务守卫 |
| `extensions_bridge.cljs` | `:ui/extensions`、MCP/技能视图、回执与 `extensions/*` 命令 outbox |
| `browser_bridge.cljs` | `:ui/browser`、受管浏览器快照/回执与 `browser/*` 命令 outbox |
| `transport.cljs` | Sente 客户端；不解释业务事件 |
| `views/session.cljs` | 会话列表 + 分支来源条 + 挂载 `hc.client.conversation.attachments.surface/conversation-with-attachments`（内含 conversation-page） |
| `views/board.cljs` | 挂载 `hc.client.swarm.ui/SwarmDashboardFromDb` |
| `views/settings.cljs` | 挂载 settings-ui `settings-element`；仅在 `:data` 分区追加 diagnostics 与 backup |
| `views/assistants.cljs` | 从 `:ui/settings` 投影角色归属与供应商连接；提供进入真实设置编辑器的入口 |
| `views/materials.cljs` | 八分区：文件浏览器、产物、记忆/世界书/指令卡、受管浏览器、MCP/技能、小应用；每个面板由对应模块自己的 bridge 驱动 |
| `views/shell.cljs` | 顶栏、五工作区导航、宽屏列/窄屏抽屉、覆盖层与焦点返回、通知 |

## 3 组件接口

### 会话状态所有权

`hc.client.conversation` 的 reducer state 是会话区唯一所有者，存放在
`:ui/conversation`。`conversation_state/mirror` 把它派生到平面键：
`:ui/draft`（`:drafts`）、`:ui/stream`（`:streams`）、`:ui/ask`（按 ask-id
展开的 `:asks`）、`:ui/command`（传输镜像，保留应用级命令）。这些平面键
只读；操作员手势走 `conversation_bridge`（`:draft/edit`、`:composer/send`、
`:composer/stop`、`:ask/submit`），服务器事件由 `conversation_bridge` 的
监听器在 `events` 归约消息/通知之后送入 reducer，再刷新镜像。不要在视图或
其它桥里直接写这四个平面键；否则会重新出现第二份状态。

发送命令由 reducer 的 outbox 排水：`conversation_bridge` 把 `:send`/`:stop`/
`:unpark` 翻译成 `:send-message`/`:stop-run`/`:answer-ask`，附上该会话的
`:command-epoch` 后交给 Sente。回执经 `events` 到达监听器，统一送
`:composer/ack`；`:not-found` 在触碰 `:ui/command` 前忽略。应用级命令
（`session/create`、`session/list`、元数据、history）仍由 `commands.cljs`
直接构造并跟踪。

### 外壳、抽屉与焦点

`views/shell` 的 `workbench-content` 是 Framework7 包装内的纯 DOM 外壳，
`workbench` 只负责 `App/View/Page` 包装。宽屏（逻辑宽度 ≥1024px）把导航和
详情作为常驻网格列；窄屏（≤1023px）才把它们变成抽屉。这个分档与
`conversation.a11y/layout-for-width` 和 CSS 的 `1023px` 断点必须一致。

- `:ui/sidebar-open` / `:ui/aux-open` 在宽屏是列状态，不是模态层。宽屏不得
  渲染 `.wb-drawer-backdrop`，`model/overlay-stack` 也必须返回空栈；否则固定
  覆盖层会盖住常驻列，拦截“关闭详情”等真实控件，Escape 也会抢走文本选区。
- 窄屏打开抽屉时才渲染 `.wb-drawer-backdrop`。CSS 层级固定为覆盖层 `30`、
  抽屉 `40`；抽屉关闭后使用 `visibility: hidden`，不能把屏幕外的控件留在
  键盘 Tab 顺序里。
- 打开抽屉后焦点进入抽屉：导航抽屉聚焦 `#wb-nav-drawer`，详情抽屉聚焦
  `#wb-aux-close`。关闭（关闭按钮、覆盖层、Escape）后焦点回到真实开启者：
  `:ui/sidebar-return-id` / `:ui/aux-return-id`。会话头的“辅助面板”按钮 id
  为 `hc-header-action-open-aux`，点击语义是打开并聚焦，不是切换；顶部栏的
  `#wb-aux-toggle` 才是切换。焦点意图写入 `:ui/focus-target` 并递增
  `:ui/focus-seq`，由外壳的 `FocusEffect` 在 React 提交后执行；不要在点击
  处理器里用定时器抢在抽屉可见之前聚焦。
- 宽屏初始 `:ui/aux-open` 为真，详情列可见；会话切换和路由变化只在窄屏关闭
  抽屉。视口进入窄屏时关闭两个抽屉，进入宽屏时恢复详情列。
- 窄屏隐藏会话列（CSS `display: none`）时，导航抽屉必须同时提供会话过滤、
  可见会话列表和“新建会话”。只隐藏列表而不给替代入口会让 `360px` 窄窗
  无法切换或新建会话。抽屉里的选择与会话页共用 `views.session/select-session!`，
  关闭抽屉后焦点回到 `:ui/sidebar-return-id`。
- 宽屏第三列是真实侧栏，不是只有事实面板：`详情`、`文件`、`浏览器`、`来源`
  四个页签分别挂载会话事实、工作区文件、受管浏览器与服务器发布的引用/来源。
  来源缺失时显示“服务器未报告该会话的来源”，不得编造。
- 全局快捷键只在 `main` 实现：`Ctrl/Cmd+,` 打开设置、`Ctrl/Cmd+N` 新建会话、
  `Ctrl/Cmd+F` 聚焦会话过滤、`Ctrl/Cmd+Shift+F` 打开全局历史搜索覆盖层
  （`hc.client.search.history-view`，客户端入口已接）。服务端 app router
  尚未注入 `:history-search`，命令会显示索引不可用；这是 D-32 的装配缺口，
  不是客户端入口缺失。设置页的“快捷键”分区只展示真实存在的映射。

### 会话

`views.session` 向附件组合面传 `:conversation-props`（再交给 `conversation-page`）与完整 `:model`（含 `:capabilities`/`:attachment-limits`），后者决定附件队列的原生兼容性。

```clojure
{:session-id "s-1" :session {...} :messages [...]
 :draft {...} :stream {...} :ask {ask-id ask} :command {command-id command}
 :connection {:phase :synced ...} :layout :wide :platform :desktop
 :callbacks {:send! (fn [{:keys [session-id snapshot]}])
             :stop! (fn [{:keys [session-id]}])
             :edit-draft! (fn [change])
             :submit-ask! (fn [{:keys [session-id ask-id text selected]}])
             :scroll! (fn [{:keys [session-id at-bottom?]}])
             :resync! (fn [_])}}
```

使用 `(react/createElement conversation-page #js {:props props})`。不要 `clj->js` 整个 map；命名空间键会坍缩。`:platform` 是输入模式，不是视口宽度；窄桌面窗口仍为 `:desktop`。`:layout` 才由宽度决定。

分支会话在会话内容顶部显示来源条。`model/branch-origin` 从已提交的
`:branch-of`（或 `:origin-session-id`）投影来源；只有来源仍在当前可见
`:session/by-id` 中时才渲染可点击的“来源会话：标题”按钮，点击后写入
`:ui/selected-session-id` 并导航到 `:sessions/<id>`。来源已删除、归档或
不在基线时只显示“已删除或不可见”，不保留无效果链接。来源跳转与普通会话
选择都只改变本地选择；服务器只为 active session 启动时建 Owner，其余会话
懒加载。选择会话（含来源跳转）时先调 `commands/ensure-session-owner!`
（`:session/ensure`，应用纪元作用域，Owner 已存在时是 cheap no-op），其
accepted 回执把 `:command-epoch` 写到 `:session/by-id` 行（行不存在则等
基线），供 `model/session-command-epoch` 授权后续会话命令。

基线历史是窗口化的：每个会话只发布最新 `history-window-size`（默认 200）
条消息实体，`:message-ids`/`:message-count` 仍是全量可见列表。更早的消息
经 `commands/fetch-history-window!`（`:history/messages`，read-only 作用域
不走纪元门）按 `:before`（当前最旧已加载消息 id，exclusive）分页拉取；
回执把 `:messages` 实体并入 `:message/by-id`、`:message-order` 并入全局
`:message/order`，并把全量 `:message-ids`、`:has-older?`、`:command-epoch`
写到 `:session/history/<sid>`（`:session/by-id` 行存在时同步写）。客户端
判定：`model/missing-session-messages?` 只在“摘要有消息但本地完全没有实体”
时为真，此时 `ensure-session-messages!` 走 ensure-owner + fetch-window 而
不是 resync（resync 拿到的也是窗口化基线）；`model/session-messages-unloaded?`
（已加载数 < `:message-count`）是正常的 older 未加载状态，会话页据此显示
“加载更早的消息”入口，`model/has-older-messages?` 读取回执记录的
`:has-older?`，已知为 false 时不再显示入口。
来源跳转和手动切换都会递增 `:ui/selection-seq`。`:history/branch` 在提交时
捕获该序号；接受回执只在这个序号仍等于当前值时才选中新分支。基线恢复会
重查 `:accepted` 回执，所以分支回执必须幂等，迟到的重放不得把操作员从
来源会话或任何更新的选择拉回空分支。

### 设置

`settings_bridge/settings-element` 读取 Fulcro `:ui/settings`，把 `dispatch!` 接到 settings-ui 的纯 reducer；outbox 经同一 Sente 通道提交。服务器快照在 `:ui/server-revision` 改变时投影，但投影 mutation 在执行时对**当前** `:ui/settings` 归约，并携带 `:server-revision` 守卫：迟到的旧基线不得覆盖点击后的分区、角色选择或已确认的连接。缺少 `:settings/by-id` 不是权威的空连接列表，桥不得合成空 `:server` 区域。

`views/session` 必须向 conversation surface 传入 `:settings!` 与 `:aux!`：
`:settings!` 调用现有 `routing/navigate!` 到 `:settings`，`:aux!` 打开辅助面板
并把焦点移到其关闭控件；宽屏详情列已可见时该操作是幂等的。
`:fix!` 的 `:select-model` 分支先派发 `:settings/select-section :models`，
再导航到设置页并保留草稿；不可用连接的横幅入口因此直接落到模型列表，
不再只显示提示或落到无关分区。
模型标签在 `:settings!` 可用时渲染为按钮（可发现入口）；不可用时退回只读文本。
这些回调只导航，不重建 settings 状态，因此不会清掉未保存草稿或密钥输入。

### 助手与角色

`views/assistants` 只读取 `:ui/settings` 这一份已提交的设置投影：
`model/role-cards` 与 `model/connection-cards` 委托 `hc.client.settings.model`
的纯投影，不读取服务器从不发布的 `:profile/by-id` 或 `:connection/by-id`。
角色行包含 `:main`（Steward / Plan / Reviewer）与 `:task`（Plan worker 与
Swarm 普通个体/持令者）；未选择模型时显示“未设置”，不借用其他角色或编造模型。
`:utility` / `:vision` 的 `:follow-main` 只在主模型真实存在时显示来源，否则
显示“跟随来源缺失”。连接行显示名称、实际主机、服务类型、状态、最近测试与
模型数，同名连接/模型不合并。

“打开模型设置”“配置/更换”按钮先向 `settings-bridge` 派发
`:settings/select-section`（以及角色或连接选择），再导航到 `:settings`；
它们打开的是真实编辑器，不提交伪造的保存。保存、回执与快照仍由
`settings-bridge` 处理；本视图没有第二条设置状态。

### Swarm

`(react/createElement swarm-ui/SwarmDashboardFromDb #js {:db db})`。视图只读，不认领、不写文件。
服务器当前若只发布 `:task/by-id`、`:action/by-id`、`:agent/by-id` 与
`:board/version`，面板显示一条 `:fleet-reported? false` 的 board-only 行：
任务树、等待链和槽位来自同步的 board 记录；fleet 行、join 队列与 worktree
显示“未报告”。`:token-slot/by-id`、`:write-gate/by-id`、`:slot/by-id` 有真实
记录时显示 holder/W/slot，缺失时明确“未报告”。不得把 `:board/version` 标成
`:master-version`，也不得在 W 未报告时声称队列为空或 holder 未持 W。
`:write-gate/by-id` 的 `:recovery-blocked?`、`:recovery-reasons`、
`:recovery-actions`、`:recovery-write-gate` 与 `:recovery-acknowledgement`
原样进入 W 视图；恢复屏障的原因、未决动作 ID、旧 W 证据与确认证据显示在
带标签的文字区，不能只靠颜色或数量表达。

### 材料

`materials_bridge/dispatch!` 接收 `hc.client.materials` 的纯动作，把 outbox 交给 Sente，并把
回执交回纯模型。`materials-element` 读取 `:ui/materials`，不读取第二份业务真相。网关启动基线
可能早于材料写入，因此首次 `:synced` 后发送一次 `:materials/snapshot`；每次接受的材料写入再
追加一条。回执与后续快照都在 Sente 接收回调之外归约。`transact-state!` 拒绝较旧
`:materials/revision` 覆盖较新状态，防止迟到基线把刚保存的记忆、世界书或指令卡复原。
它只写 `:ui/materials`，经 `mutations/set-materials-state` 提交；守卫只有这一处实现。
`sync-from-db!` 与 `dispatch!` 都以 Fulcro 数据库中的 `:ui/materials` 为准，不把桥的
`last-state` 当成第二份真相——否则数据库拒绝的乐观选择会被下一次投影复活。`install!`
用 `sync-bridge/ensure-event-listener!` 以固定键注册回执监听，热重载或通道重启不会重复归约
同一回执。
仅本轮指令卡由 `attach-turn-selection` 附加到发送快照；发送回执接受后，只有当前选择
仍匹配提交时的 `:turn-selection-version` 才清除，等待回执期间的新选择保留。
客户端不自动发送 `turn/stage`，避免同一张卡进入下一次发送。

## 4 连接与命令合同

- Sente packer 默认 Transit/JSON。客户端 classpath 的 Sente 1.20 暴露 `get-transit-packer`；服务端 Sente 1.22 暴露 `get-packer`，两者产生同一 Transit/JSON 格式。改变任一侧必须同时改另一侧。
- Sente 事件：`[:hc/subscribe {:subscription-id ... :epoch ... :seq ...}]`、`[:hc/command {:command-id ... :command-epoch ... :subscription-id ... :payload {:kind ...}}]`、`[:hc/receipt {:command-id ... :session-id ... :subscription-id ...}]`、`[:hc/resync {:subscription-id ... :epoch ... :seq ... :reason ...}]`。回执查询带 `:session-id` 时只查该会话作用域；应用级命令省略它。
- `:subscription-id` 是推荐的订阅代号（默认携带）：由 `transport/subscribe!` 记住，`command!`/`ack!`/`request-resync!` 默认携带，关闭/错误时清除。网关允许省略（使用该 uid 当前订阅）；显式提供时必须等于当前值，旧值返回 `STALE_SUBSCRIPTION`，非整数/负数返回 `SUBSCRIPTION_ID_REQUIRED`。客户端继续显式发送以获得最强迟到过滤。例外：`receipt!` 只在调用方显式给出 `:subscription-id` 时才携带——回执查询是幂等读，网关在 uid 当前订阅上应答；附带一个可能过期的代际会让 `subscription-check` 以 `STALE_SUBSCRIPTION` 拒绝，而 fire-and-forget 发送没有 reply-fn，该拒绝被静默丢弃（D-26）。
- 已应用状态事件后发送 `[:hc/ack {:subscription-id ... :seq applied-seq}]`；只确认已应用的序号。
- `CUSTOM hc/resync-required` 由 `sync-bridge` 直接调用 `resync!`，不进入运行事件归约，也不显示“未处理事件”通知。
- `CUSTOM hc/sync-heartbeat` 只触发 `recover-pending-commands!` 查询未决命令回执，不进入运行事件归约、不改变同步阶段，也不携带状态树。
- 每次 `STATE_SNAPSHOT` 基线后，`sync-bridge/recover-pending-commands!` 对 `:submitting`/`:sent`/`:accepted`/`:unconfirmed` 的 `:ui/command` 发送 `[:hc/receipt ...]`；不重发命令本身。回执重放必须幂等：创建/分支回执只有在提交时捕获的 `:selection-seq` 仍匹配时才改变选择，来源跳转或手动切换后到达的旧回执只能更新命令状态。
- 材料命令的 pending 锁在 `:ui/materials :commands`，不在 `:ui/command`，因此有独立清扫：`materials-bridge` 在 `install!` 安装 5 秒 `setInterval`，每拍读取当前 `:ui/materials` 并对 `:queued`/`:submitting` 命令发送 `[:hc/receipt ...]`（2 秒节流），`dispatch!`/`sync-from-db!` 之后也触发一次。清扫不依赖 sync 事件——传输层丢失的命令（`send!` 返回 true 但服务端 durable gate 无 receipt）只能靠这条路径拿到 `:not-found` 并释放锁。清扫的查询不带 subscription-id（`receipt!` 只在调用方显式提供时代际），发送失败按拍记 `log/warn`；网关允许省略 subscription-id。网关的传输层 `{:status :submitted}` ack 不是领域回执：`materials/model` 的 `receipt-status` 保留 `:submitted` 为非终态，`apply-receipt` 忽略它，pending 锁保持到领域回执或 `:not-found`；`:not-found` 映射为 `:refused` 释放锁并触发 reconcile snapshot。
- `:command-epoch` 有两个作用域：应用级命令使用全局 `:sync/:command-epoch`（app 门签发）；会话命令只使用该会话自己的 Owner epoch。`bridge/command-epoch-for-session` 先读 Fulcro 显示库的 `:session/by-id/<sid>/:command-epoch`，再按同一 `sid` 读 `:ui/session-list` 中已删除行的 `:command-epoch`，最后才回退到 adapter 的已确认服务端视图；已删除行不在 adapter 的 `:session/by-id` 中。全局 app epoch、feed epoch 与其它会话的 epoch 绝不给会话命令兜底；缺少 per-session epoch 时禁用命令并显示 `:command-epoch-unknown`。AG-UI feed `:epoch` 只用于同步显示，不用于命令。
- 停止命令只校验连接、选中会话与 per-session epoch；本地流空闲、Nudge 回合间隙或 `:ui/run` 已清空都不构成拒绝理由。无人值守 Nudge 可能在可见流之外继续请求模型，停止按钮必须按选中会话保持挂载，由 Owner 接受或拒绝。停止 payload 携带该会话已知的 `:run-id`（`session/current-run-id` 或匹配的 `:ui/run`），Owner 停确切 run。停止回执 `:accepted/:applied` 按回执 `:result/:run-id` 与本地流 `:run-id`/`:stop-run-id` 匹配后才 `stop-confirmed`；同一 run 的迟到 `RUN_STARTED` 必须保留 `:stopping`，不同 run 才开启新流。`:rejected` 恢复 `:idle`，绝不假装已停止。
- 流式期间的 DOM 选区保护由 `conversation-surface` 持有；workbench 不得为稳定选区暂停接收、丢弃运行事件，也不得在流式期间改变 `ConversationWithAttachments` 的 key 触发重挂载。选区恢复只重建 DOM Range，不写命令状态、不改草稿、不请求重同步。
- 事件监听器按桥注册一次：`add-event-listener!` 是无条件追加，只供一次性安装使用；`install!` 可能在热重载或通道重启后再跑的桥改用 `ensure-event-listener!` 加固定键，同一键只投递一次，避免同一回执被归约两遍。
- 取消终局不是失败：`RUN_FINISHED`/`RUN_ERROR` 的 `:status :cancelled` 或错误 `kind/code/reason :cancelled|:user-cancel` 必须把流置 `:stopped`、目标消息置 `:stopped`，保留正文且不弹“服务器报告本轮未完成”；只有真正的失败才进入错误恢复。
- Nudge 的 `RUN_STARTED` 只更新 `:ui/nudge` 与 `:ui/stream`，不写 `:ui/run`，也不借用可见用户 run 的 `:message-id` 改消息状态；发布器的 Nudge 开始事件没有 message-id，其文本也被过滤在可见流之外。`start-run` 的 Nudge 分支在 `->` 中调用 `update-stream` 时不得再显式传入 `db`，否则 `apply` 收到字符串 session-id，浏览器抛 `TypeError: a.call is not a function`，整条运行事件被丢弃且界面停在旧状态。
- 服务器回执：`CUSTOM` `{:event :hc/receipt :command-id ... :status :accepted|:applied|:rejected|:not-found|:unknown ...}`；客户端也接受 `:hc/command-receipt`。网关回执可能嵌套为 `{:command-id ... :receipt {:status ...}}`，`apply-receipt` 解包后再判定。`:not-found` 是回执查询的瞬态答复，必须在更新 `:ui/command` 前忽略，不能覆盖后续 `:accepted`。只有 `:send-message` 回执可以清/恢复草稿；`stop-run`、`answer-ask`、`session-create` 等不得触碰草稿或 `:ui/draft-reset`。发送前用同步 `send-claims` 原子门与草稿版本守卫，双击/回车重复只产生一条命令。`COMMAND_EPOCH_RETIRED`/`COMMAND_EPOCH_UNKNOWN`/`COMMAND_EPOCH_INVALID`/`STALE_SUBSCRIPTION` 触发重同步，普通校验拒绝不触发。
- 会话 kind：`:send-message`、`:stop-run`、`:answer-ask`、`:session/create`、`:history/*`，payload 内带 `:session-id`。`commands/create-session!` 由会话列表“新建”按钮调用；普通 New 发送 `:reuse-blank? true`，显式模型/连接或临时会话才创建新记录。接受回执携带 `:session-id` 且选择序号守卫仍匹配时选中该会话；过期 `:ui/selected-session-id` 回退到第一个可见会话。会话列表另有 `临时` 创建（`:temporary? true`）；选中临时会话时显示 `转为普通`（`:session/transfer`）与 `结束临时`（`:session/delete`，软删除且不进已删除区）。`:session/transfer` 需要 app 的 `metadata-kinds` 放行。
- 会话面 `:settings!` 导航到设置路由，`:aux!` 打开辅助面板并把焦点移到关闭控件（宽屏已可见时幂等）；模型标签也是 `:settings!` 按钮，是未绑定模型的可发现修复入口。未绑定模型（`:model` 缺失、空 map 或只有能力/限额）时发送按钮必须禁用并显示“先选择一个可用模型，草稿已保留”，不得因 `:model {}` 为真值而放行。禁用发送按钮不等于删除 `:fix! :select-model` 合同：任何调用仍必须导航到设置页并保留草稿，而不是只显示死提示。已绑定模型因连接停用/移除而不可用时，`hc.client.workbench.model/session-with-model`（纯投影，浏览器与 JVM 测试共用）把会话摘要的 `:model-availability` 投影进 `:model :availability`，发送门槛据此显示连接解释并提供“选择其他模型”入口，不得退化成“输入为空”。该函数不发明可用性：摘要没有 `:model-availability` 时不写 `:availability`，`:unconfirmed` 仍按可用处理。真实 wire shape 是 `{:model-binding {...} :model {...} :model-availability :connection-removed}`；`:model-availability` 的值由服务端 settings 投影决定，启动配置移除连接必须先在服务端生效（见 `src/hc/server/app/AGENTS.md`），客户端只如实消费。
- `:session/create` 或 `:history/branch` 的接受回执只在其提交时捕获的 `:selection-seq` 仍等于当前选择序号时才更新 `:ui/selected-session-id`；仅当当前路由是 `:sessions`（或未设置）时才切回会话页，绝不把已在设置/看板的操作员拉回会话列表，也绝不覆盖其后发生的来源跳转或手动切换。
- React `useEffect` 必须返回 `js/undefined` 或真正的 cleanup 函数；CLJS `nil` 会编译成 JS `null`，React 卸载时会把它当 destroy 调用并抛 `d is not a function`。会话控件、诊断等卸载路径尤其要检查。
- 会话元数据控件：`hc.client.sessions.controls` 通过 `commands/session-metadata!` 发送 `:session/rename`、`:session/pin`、`:session/archive`、`:session/restore`、`:session/delete`、`:session/purge`，使用 per-session epoch 并在 `:ui/command` 跟踪；控件只读 `model/session-command` 的元数据 kind，发送/停止等命令不得禁用或改写这些控件。重命名/置顶/归档/删除不做本地乐观更新，只有回执或下一份快照改变显示。已删除会话由 `commands/session-list!` 的 `:session/list {:include-deleted? true}` 回执写入 Fulcro 显示库的 `:ui/session-list`；该列表不在 adapter 的已确认服务端视图中，`bridge/command-epoch-for-session` 必须读 Fulcro 显示库并按同一 `sid` 取行，`model/session-command-epoch` 再回退到该列表，因此恢复与永久删除使用同一 per-session epoch。`events/apply-receipt` 接受 `:session/list` 并移除已恢复或已永久删除行。永久删除对任何已删除会话立即可用（30 天是默认保留期，不是删除禁令），需第二次确认并发送 `:confirm? true`；服务端在删除记录前停止并排空该会话 Owner，绝不删除工作区文件。临时会话不进入已删除区。
- **会话选择 SSOT**：客户端只有 `:ui/selected-session-id` 一个选择权威；手动切换只改它并递增 `:ui/selection-seq`，绝不写服务器展示键 `:session/active-id`。`:session/active-id` 是服务器进程级展示指针，只用于首次基线 bootstrap；它不能作为命令目标，也不能在本地选择失效时充当回退（失效选择回退到第一个可见会话）。所有会话命令（发送、停止、回答、history、临时会话操作）都携带显式 `:session-id`；服务器 router 不以 `active-session-id` 补缺。`install-sync` 只替换服务器区，`:ui/*` 在迟到快照后仍保留。`:session/create` 回执只在命令捕获的 selection seq 仍匹配时选中新会话，避免迟到回执覆盖用户切回 live 会话。
- 服务器 `STATE_SNAPSHOT` 的 active conversation 消息带显式 `:session-id`；客户端不得按 `:session/active-id` 猜测无 session-id 消息的归属。nudge 消息按普通键与 `:message/nudge?`/`:message/nudge-id` 双重过滤，不把 nudge 回合渲染成正常 assistant 气泡，也不隐藏真实活动。
- `:session/create` 与 `:history/branch` 的接受回执只在当前路由为 `:sessions`
  或未设置时改变选择并递增 `:ui/selection-seq`；设置/看板页收到迟到回执时
  保持原选择与路由，不静默改写“当前会话”。
- 会话输入区发送前先跑 `model/send-guard`：当前 `:ui/materials` 的启用卡片
  输出格式互斥或缺少必填占位符时，只提示并停止发送，不改草稿、附件或材料
  选择；搜索选择错误仍优先于材料检查。
- 语音转写插入走 `model/insert-transcript-change`：`session-id` 为空或空白、
  文本为空时不产生任何草稿键，避免写入 `[:ui/draft nil]` 后可见草稿不动而
  录音状态被清空。合法会话只改该会话草稿，插入位置钳制在文本长度内。
- S1 问题卡的“跳过”是可见回答：`conversation_bridge/skip-ask!` 走
  `:ask/skip`，生成 `{:skipped? true}` 的 unpark 命令；连接或 per-session
  epoch 不满足时与正常回答一样阻止并提示，视图按钮显示“跳过通道未就绪”。
- `:run-error` 是服务器 active 会话的错误。只有 `:session/active-id` 等于本地
  `:ui/selected-session-id` 时才显示/恢复它；其它会话只读自己的
  `:session/by-id/<sid>/:last-error`。
- Escape 层级：先关最上层抽屉（一次只关一层），再交输入法候选或文本选区
  （含 `<textarea>`/`<input>` 内的选区），最后才在 `:sessions` 路由停止当前
  生成。无人值守运行由服务器 `:run-status`/`:current-run-id` 判定，不因本地
  流空闲而拒绝停止。
- 五阶段只由 `hc.client.sync` 决定；`:command-allowed?` 仅在 `:synced` 且 epoch/seq 非空时为真。
- 断线只显示“最后确认副本”，不取消服务器任务。

### 客户端在场（presence-bridge）

**(a) API**

```clojure
(transport/stable-client-id) ; => string；sessionStorage → localStorage → UUID
(presence-bridge/local-client-id) ; => 当前 channel 的 client-id 或稳定身份
(presence-bridge/attach-decision
 {:session-id .. :synced? .. :controller-id .. :local-client-id ..
  :subscription-id .. :attempted #{..}})
;; => {:attach? bool :reason keyword ... :session-id .. :key [sid sub-id]}
(presence-bridge/attempt!)  ; 至多发送一次 attach，失败只记录
(presence-bridge/install! app)
```

attach 命令是 `:session/attach-client`；接管命令是 `:session/takeover`。

**(b) 失败/拒绝码**

| code/reason | 触发 |
|---|---|
| `:no-session` | 基线没有选中会话 |
| `:not-synced` | 尚未进入 `:synced` |
| `:no-subscription` | 没有 subscription-id |
| `:no-client-id` | 两个 storage 都拒绝且无法生成身份 |
| `:controlled` | 服务端已报告 controller，绝不抢占 |
| `:already-attempted` | 该 `(session-id, subscription-id)` 已尝试 |
| `:free` | 无 controller，允许一次 attach |
| `:NOT_CONTROLLER`（回执） | 观察者业务命令被拒；命令状态带 `:action :takeover` |

**(c) 不变量**

保证：每个 `(session-id, subscription-id)` 至多 attach 一次；发送前先记忆
键，回执/重绘/重复基线不形成循环；已有 controller 绝不自动抢占；关闭标签
不取消 run；`NOT_CONTROLLER` 只提供显式接管入口。

不保证：不保证 attach 回执一定到达或 controller 永久保持；不保证其他
客户端不接管；不发送业务命令；不重试已尝试的键（新 subscription 才会重新
尝试）；不改变草稿、消息或 run 状态。

**(d) 验证**

CLJS 测试不在 JVM 分片：

```bash
clojure -M:cljs:client -re node
# REPL:
# (require 'hc.client.workbench.presence-test)
# (cljs.test/run-tests 'hc.client.workbench.presence-test)
```

代码事实，未复测：6 tests / 19 个 `is`。

## 5 构建与冒烟

```bash
npm run build:css
npx shadow-cljs compile :app   # 开发；需要 deps.edn 的 :cljs 含 shadow-cljs
npx shadow-cljs release :app   # 产物 resources/public/js/main.js
```

浏览器冒烟（静态资源由任意 HTTP 服务器提供）：

```bash
python3 -m http.server 8765 --directory resources/public
google-chrome --headless=new --no-sandbox --disable-gpu \
  --virtual-time-budget=9000 --dump-dom 'http://127.0.0.1:8765/#/settings'
```

2026-11-03 实测：五条路由均可渲染；`#/board` 挂载 swarm-ui 空态；`#/settings` 挂载 settings-ui；无服务器时连接进入 `:interrupted` 并保留最后副本说明。**未运行**：真实 Sente 服务器快照/回执、真实会话消息、真实输入法、屏幕阅读器。未连接时 `#/sessions` 不挂载会话组件，因此会话组件的真实数据路径仍待服务器联调。

## 6 测试

```bash
clojure -M:test -n hc.client.workbench.model-test
clojure -M:test -n hc.client.workbench.events-test
clojure -M:test -n hc.client.workbench.materials-bridge-test
clojure -M:test -n hc.client.workbench.conversation-bridge-test
```

覆盖：路由 roundtrip、连接阶段/命令门槛、空服务器不生成占位实体、任务树环、可用性禁用原因、本地流空闲时停止仍可发送（服务端活跃事实不由客户端流推断）、文本块追加、停止后迟到文本不追加、工具参数完整≠终态、停止回执关闭/恢复冻结、回执只清匹配版本草稿、拒绝回执恢复快照、S1 ask 创建、Nudge 开始不覆盖用户 run 或可见消息；`overlay-stack` 只在抽屉布局产生 Escape 目标，宽屏常驻列不进入覆盖层栈。
分支回执另有三条行为测试：选择序号仍匹配时选中新分支；来源跳转后重放的旧回执不改变选择，且源会话消息仍可读；没有捕获序号的历史回执不得凭回执自行选中分支。
`model-test` 还覆盖助手工作区的真实投影：`:main` 与 `:task` 从
`:ui/settings` 读取已提交的角色绑定；空设置只显示“未设置”的固定角色行，
不生成模型或连接实体；连接行的模型数来自同一设置投影。
同一套测试覆盖分支来源投影（可见来源带标题，删除/不可见来源不产生链接）
、消息表与 session 摘要不一致时的缺失投影，以及永久删除回执清理
（删除行和过期显示副本一起移除）。
`materials-bridge-test` 覆盖会话切换投影：清空服务器派生表、递增本轮选择版本、保留命令队列。
`test/js/verify_materials_bridge.sh` 用 headless Fulcro app 与注入式 `send-receipt!` 覆盖 D-26：无 sync 事件时清扫仍查询回执、`:not-found` 释放 pending 锁、`:submitted` ack 不提前释放。
桥本身是 ClojureScript，release 构建的调用可见性由 `npx shadow-cljs release :app` 验证。
`test/js/verify_workbench_session_commands.sh` 用真实命令 helper 与 headless
Fulcro app 覆盖已删除会话的 epoch 来源：行只存在于 `:ui/session-list` 时
`:session/purge` 仍取得该 `sid` 的 Owner epoch；缺失会话阻止发送，不借用
app 或其它会话的 epoch。

`bash test/js/verify_workbench_draft_storage.sh` 用 Node 内存 localStorage 驱动真实
`workbench.storage`：同一 profile 保存后恢复每会话文本、光标、选区、附件、
引用与版本，并证明持久化签名覆盖这些字段；新建 profile 是成功空库而非损坏；
JSON keyword 会话键归一化为字符串；写入失败如实返回 `:storage-unavailable`。
它固化“同一浏览器重开恢复”
与“新浏览器 context 为空”的边界，避免把 E2E 新 profile 的空草稿误判为产品丢草稿。

`bash test/js/verify_workbench_shell_dom.sh` 用真实 React DOM 在 jsdom 挂载
`shell/workbench-content`，覆盖抽屉层：宽屏即使 `:ui/aux-open` 为真也不渲染
`.wb-drawer-backdrop`，详情关闭按钮仍会提交 `:ui/aux-open false`；窄屏打开
抽屉才渲染覆盖层，打开时焦点进入 `#wb-aux-close`，关闭时回到顶部栏或会话头
的开启者。该 harness 用 `test/js/f7_lite_shim.js` 满足 `views/common` 加载期
的 `Framework7.use` 调用，不启动真实 Framework7 路由。
同一 harness 还覆盖窄窗抽屉的会话入口：`360px` 下抽屉提供
`#wb-nav-session-search`、`#wb-nav-new-session` 与可点击的会话行；以及
`close-overlay` 一次只关最上层，导航抽屉保持打开并把焦点交回真实开启者。

`views/settings.cljs` 的分区挂载由
`test/js/verify_backup_password_dom.sh` 的真实 React DOM 回归覆盖：
供应商、模型与外观分区不挂载备份/诊断面板；数据中心输入密码后切到模型
分区，未提交的 DOM 草稿先写回 `:ui/backup`，切回后仍显示；面板隐藏期间
到达的备份回执仍更新命令状态并在重新挂载后显示终态。`settings-page` 的
分区 key 只响应操作员分区变化，服务器投影不会重挂载或清空草稿。
该 harness 用 `test/js/react_dom_client_shim.js` 提供 Fulcro 编译期需要的
`react-dom/client`，runner 注入真实模块；每次运行使用独立输出目录，不删除
共享产物。

## 7 非目标

- 不实现 Sente 服务端、模型供应商、存储或备份。
- 不伪造服务器实体、命令成功、工具终态、费用或未知结果。
- 不编辑根 `AGENTS.md`。
