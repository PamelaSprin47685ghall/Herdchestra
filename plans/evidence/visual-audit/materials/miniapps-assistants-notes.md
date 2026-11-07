# 小应用与助手页真实视觉审查（2026-11-06）

审查人：w18-visual-materials.MaterialsMiniApps。真实 Chrome（`/usr/bin/google-chrome`，Playwright 1.64.1，headless）+ 真实 app（`/tmp/pixi run clojure -M:server`，`--workspace-root /tmp/hc-visual-materials/mini-work`，`--data-dir /tmp/hc-visual-materials/mini-data`，静态目录 `resources/public`）。

## 1. 证据与运行时

截图与同名 JSON（元素边界、字号、前景/背景对比度、滚动溢出、`runtime`）位于：

- `plans/evidence/visual-audit/materials/miniapps/`
  - `01-miniapps-empty-1280-light.png|json` 空态（修复前，含 F7 按钮宽度缺陷）
  - `02-miniapps-long-name-1280-light` 长文件名列表/标题
  - `03-miniapps-open-1280-light` 打开 iframe（520px 高）
  - `04-miniapps-data-1280-light` 数据面板
  - `05-miniapps-clear-confirm-1280-light` 清空确认（确认框在视口外）
  - `06-miniapps-after-clear-1280-light` 清空后
  - `07-miniapps-generate-panel-1280-light` 生成面板（默认样式）
  - `08-miniapps-generating-1280-light` 生成请求中
  - `09-miniapps-generate-error-1280-light` 真实失败回执
- `plans/evidence/visual-audit/materials/assistants/`
  - `90-probe-assistants-1280-light.png|json` 助手页（模型归属）

运行时记录（JSON `runtime`）：

| 证据 | app JVM PID | app RSS | Chrome PID | Chrome RSS |
|---|---|---|---|---|
| 01 空态 | 239194 | 975 MiB | 未记录（pgrep 参数缺陷） | — |
| 02–09 | 239194 | 982 MiB | 246210 | 230–231 MiB |
| 90 助手页 | 239194 | 981 MiB | 245694 | 223 MiB |

JVM 事实（`jcmd 239194 VM.flags` / `GC.heap_info`，启动时）：
`-XX:MaxHeapSize=1073741824 -XX:MaxDirectMemorySize=536870912 -XX:+ExitOnOutOfMemoryError`；
`garbage-first heap total reserved 1048576K, committed 376832K, used 319455K`。
说明：deps.edn 的 `-Xmx1g` 覆盖了 `JAVA_TOOL_OPTIONS` 里的 768m；该 app 实测 RSS ≈ 0.96 GiB，未 OOM。app 与 Chrome 已全部停止（239194 由 parent 按 P0 TERM，Chrome 由脚本 `ctx.close()` 结束）。本 worker 未杀任何他人进程。

字体缩放与深色/窄屏：因全局锁排队（6 个 peer 在等）与 parent 叫停，仅完成 1280 浅色实测；360/820、深色、80%/200% 未取得真实截图，属未覆盖项（下方 U1）。

## 2. 真实功能回执（不是伪造）

- 空提示词生成：HTTP 400，banner 文案 `INVALID_PROMPT · 请描述要生成的小应用`（`刷新已有文件` 动作）。
- 有提示词 + 文件名生成：HTTP 503，banner 文案 `MODEL_NOT_CONFIGURED · 尚未配置模型连接；请先在设置中选择模型`；已有 2 个小应用列表保持不变（`ROWS_AFTER 2`）。
- 生成面板按钮在 503 返回前恢复为可用（回执过快，未捕捉到禁用帧；代码路径 `:generating? true` + `:disabled` 存在）。
- 数据：`reading-log.html` 内表单提交被沙箱阻止（`Blocked form submission ... 'allow-forms' permission is not set`），因此本轮未能写入本地记录，数据面板显示 `0 条记录`；清空确认/清空后状态仍已实测（`已清空此小应用的数据；其他小应用与普通浏览器数据未受影响。`）。
- 生成成功后 `load-app!` 路径未实测（无模型）。

## 3. Bug / Ugly 清单

严重度：P1 阻塞核心任务或误导；P2 明显可用性/可读性缺陷；P3 观感/一致性。

### B1（P1，跨页根因，已由 parent 修复）Framework7 reset 让所有裸 `<button>` 占满整行
- 复现：打开材料页 → 小应用；或助手页。
- 实测：8 个材料标签每个 692px 宽、纵向堆叠 330px 高；小应用「刷新/全屏」各 118px 且竖排；「打开为小应用」240px 满行；助手页「配置/更换」355–433px 宽，角色描述被挤到约 150px；`.wb-list` scrollWidth 704 > 692。
- 根因：`resources/public/css/framework7-bundle.css` 的 `button{appearance:none;width:100%}`；`.wb-button`/`.wb-tab`/`.hc-button`/`.hc-sandbox-button` 均未重置 width。
- 修复：parent 在 `workbench.css` 末尾加 `button{width:auto}`。本报告 01/02/90 为修复前证据；post-fix 复验由 parent 补拍。

### B2（P1，小应用，已修）清空确认框落在视口外，且焦点不进入确认区
- 复现：小应用详情 → 数据 → 清空数据。
- 实测：`.hc-miniapp-confirm` rect `y=872 h=194`，视口 900；仅顶部 28px 可见，`inViewport=false`，`activeElement=BUTTON:清空数据`，无 `role=dialog`/`aria-modal`。
- 影响：破坏性操作确认不可见，用户可能误以为按钮无效；键盘用户仍在触发按钮上。
- 修复：改为固定定位遮罩 + 居中对话框（`role="dialog" aria-modal="true"`），遮罩点击取消，`取消` 自动聚焦，Esc 关闭；背景滚动不再影响定位。

### B3（P2，小应用，已修）生成面板是浏览器默认控件，无间距
- 复现：左侧「生成小应用」展开。
- 实测：textarea 182×42（默认字体、默认 resize），文件名 input 167×21，按钮与输入框之间无间距；details 无边框/内边距；summary 高 23.3px（<44 移动热区）。
- 修复：details 加边框/背景/内边距；summary 36px（移动 44px）；textarea/input 100% 宽、`box-sizing:border-box`、统一 40/76px 高与 8px 间距；保留可垂直 resize。

### B4（P2，小应用，已修）移动端按钮/输入热区不足
- 实测：`.hc-sandbox-button` 37.25px 高、summary 23.3px；均低于 44px 触控建议。
- 修复：`@media(max-width:599px)` 下按钮、summary、路径输入最小 44px。

### B5（P2，小应用，已修）列表行三个按钮的可访问名称重复
- 复现：多个小应用时，读屏依次读到「打开」「数据」「检查文件」，无法区分应用。
- 修复：`aria-label` 追加应用名，如「打开 reading-log.html」。

### B6（P3，小应用，已修）长文件名标题无强制换行
- 实测：带连字符的 103 字符文件名在 440px 内换 2 行（h=50.2）未溢出；但无空格长串（如纯中文/长 UUID）仍可能撑破。
- 修复：`.hc-miniapp-app-header h2{overflow-wrap:anywhere}`，标题容器 `min-width:0`。

### B7（P2，小应用，已修）破坏性按钮与普通按钮同色
- 实测：「清空数据」使用中性样式，「确认清空」使用主色（蓝），与危险语义相反。
- 修复：新增 `hc-sandbox-button-danger`（红边红字）用于「清空数据」与「确认清空」。

### B8（P2，助手，已修）角色行文字被单行省略号截断且无 title
- 实测：`.wb-list-main small` 为 `nowrap + ellipsis`；1280 下描述「Plan dedicated worker…」已被截断（pre-fix 按钮占宽时仅剩约 150px）。
- 修复：助手页局部样式允许 strong/small 换行（`overflow-wrap:anywhere`），并给 strong/small 加 `title` 以便悬停读全文；连接行同理。

### B9（P2，助手，未修——属 workbench.css）`.wb-list-button` 横向溢出 12px
- 实测：`.wb-list` scrollWidth 704 vs clientWidth 692；`.wb-list-button{width:100%;padding:12px 6px}` 且无 `box-sizing:border-box`。
- 建议：`.wb-list-button{box-sizing:border-box}`（会同时修正会话列表）。不在本 worker 可改范围。

### B10（P3，助手，未修）整行 hover 高亮但行本身不可点
- `.wb-list-button` 是 `div`，带 `cursor:pointer` 与 hover/focus-visible 背景；实际只有右侧「配置/更换」可点。
- 建议：助手页把该容器改为非指针光标（本 worker 已做局部 `cursor:default`），或共享样式区分可点击行。

### B11（P1，助手，未修——功能缺口，按大问题报告）助手 CRUD 不存在
- 助手页只有「模型归属 / 供应商连接」两个只读投影；设置页只有「模型与角色」角色卡（选择模型、跟随主模型、确认跟随、保存）。
- 全仓库 `src/` 未找到助手实体、复制（仅角色 / 选择内容）、停用、继承编辑、专属记忆/短语副本等入口；不存在可复现的「复制助手」按钮。
- 结论：不新增假控件；该项作为未交付功能报告。

### B12（P2，助手，已由 parent 修复）「最近确认」显示原始 epoch
- 实测：`1793933888214`。parent 已在 `assistants.cljs` 加 `synced-label` 转本地时间。

### B13（P3，小应用，未修）生成失败 banner 的动作与原因不匹配
- 503 `MODEL_NOT_CONFIGURED` 下仍显示「刷新已有文件」；刷新不改变模型配置，且没有「去设置选择模型」入口。建议按错误码给动作（本 worker 未改，避免扩大范围）。

### B14（P3，小应用，未修）0 条记录时仍可点「清空数据」
- 数据面板空态下按钮可用，点击后弹确认；无害但多余，可禁用或隐藏。

### B15（P2，沙箱，未修——不在可改范围）表单提交被 `allow-forms` 阻止
- 实测控制台：`Blocked form submission to '' because the form's frame is sandboxed and the 'allow-forms' permission is not set.`；`submit` 处理器未生效，应用内表单不可用。
- 与文档「表单提交是可见请求」不一致；需沙箱层决定是否改用 bridge 拦截 `submit`（`preventDefault` + 外部确认面板）。本轮未能验证 localStorage bridge 是否独立可用。

### B16（P3，小应用，未修）数据面板「关闭」与详情头「收起数据」重复
- 两个入口都能收起同一面板；保留其一即可。

### B17（P3，跨页，未修）控制台 404
- 每次页面加载出现一条 `Failed to load resource: 404`，疑为 favicon；未定位到具体 URL。

### U1（未覆盖）360/820、深色、80%/200% 字号
- 原因：全局 app 单实例锁排队（6 个 peer）+ parent 叫停，避免继续占内存。post-fix 由 parent 在同一次材料审查补拍。
- 代码层面已确认：`miniapp-css` 有 900px 单列断点与 599px 触控规则；`.hc-miniapp-fullscreen` 有独立 iframe 高度；深色依赖 `--wb-surface/--wb-line` 变量，但 `.hc-sandbox-frame-wrap`/`.hc-sandbox-frame` 固定白底（iframe 内容自带白底，属预期）。

## 4. 已修（本 worker）

文件：`src/hc/client/miniapps/view.cljs`、`src/hc/client/workbench/views/assistants.cljs`（仅样式/可访问性/确认框结构，无新业务控件）。

1. 生成面板完整样式（B3）与移动端 44px 热区（B4）。
2. 清空确认改为模态对话框：遮罩、居中、`role=dialog`、自动聚焦取消、Esc、点击遮罩取消（B2）。
3. 危险按钮变体用于清空/确认清空（B7）。
4. 列表按钮 `aria-label` 带应用名（B5）；长标题强制换行（B6）。
5. 助手页允许角色描述换行、补 `title`、行容器 `cursor:default`、移动端小按钮 44px（B8/B10）。

## 5. 未修

B9（workbench.css 盒模型）、B11（助手 CRUD 功能缺口）、B13、B14、B15、B16、B17、U1。B1/B12 已由 parent 修复。

## 6. Bundle

- 审查时（01–09、90 证据）：`main.js 67da1cceac3367858dc2bc5103e5b7daf94b082e69c020575a5dd8ea09e768b6`；`framework7-bundle.css 77da0dd42f787c7f9b8012df796006d19353b6c22af717018ab6a65d6fff2a57`；`workbench.css` 为 parent 修复前版本。
- 本 worker 修改 `src/hc/client/miniapps/view.cljs` 与 `src/hc/client/workbench/views/assistants.cljs` 后执行 `flock /tmp/hc-client-release.lock -c 'npx shadow-cljs release :app'`（36.98s，成功）：
  - 本 worker 构建 `main.js b0fd061f…`（2,994,193 bytes，11:04）；此后团队构建：`a8acf565…`（3,005,212 B，11:13:53）→ `4652ef2641b8a7fef8186af84e8b76285b54f8e9ad0f01f7aa52f4e4489ca66b`（3,007,766 B，11:17:42，临时）→ `dd968793cc74919e139f31bb290a5dfb68309c6d9a78acfd766a4d984f2fda4b`（3,007,964 B，11:19:25，**W9 已冻结**）。三者均含本 worker 标记串（按出现次数）：`hc-miniapp-confirm-backdrop`(2)、`hc-assistants`(5)、`hc-sandbox-button-danger`(5)。`5d36ac81` 已过时。
  - 11:17:42 构建不含 W17 11:17:45 的会话改动，故会话区证据不得绑定 `4652ef26`；小应用/助手页 post-fix 截图应由 parent 绑定实际拍摄时的 hash；若 W17 最后重建产生新 hash，以新 hash 为准。
  - `workbench.css dda62554c9bb0b2643c0c437a1368f56b021f716001bcefa3f5cf92a32fa44ca`（11:13:59，含 parent 的 `button{width:auto}` 与 `.wb-list-button` 修正）
  - `framework7-bundle.css 77da0dd42f787c7f9b8012df796006d19353b6c22af717018ab6a65d6fff2a57`（未变）
- 编译告警中有既有的 `hc.client.conversation.view/re-matcher` 未声明 Var 与若干 target-type 推断告警，均非本次改动文件；release 退出成功，bundle 已包含新串 `hc-miniapp-confirm-backdrop`、`hc-assistants`、`hc-sandbox-button-danger`。
- post-fix 复验（小应用空态、生成面板、助手页）由 parent 用新 bundle 补拍；本 worker 按 P0 未再启动 app/Chrome。
