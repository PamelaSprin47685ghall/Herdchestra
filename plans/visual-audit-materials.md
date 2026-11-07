# 材料 / 记忆 / 世界书 / 指令卡 / 技能 / 小应用 / 助手：真实视觉审查（0.0.1）

日期：2026-11-06。审查人：w18-visual-materials。
状态：**已按协调者要求暂停 flock 排队，转为离线整理**。本报告区分「真实截图证据」「真实 DOM/几何 JSON 证据」「静态代码审查」三类，不把静态判断写成浏览器观察。

> **2026-11-06 11:27 追加（W25，12:5x 校正 hash）**：§11 是 bundle `dd968793`（11:19:25，3,007,964 B）/ CSS `dda62554` 下的真实 Chrome 复验；W21 11:50 重建为 `55e4e8ea`，W33 12:17 再冻结为 `8b210896`（3,009,689 B）/ CSS `332f9fd3`（未变）。`dd968793` 与 `55e4e8ea` 均已作废，是**中间 hash**。共 21 张 1280 截图：9 张已同步（5 深色 + 4 浅色）、12 张首次连接未完成（只证明壳层）、4 张文件名含 200% 但实测为 100%。820/360、真 200%、扩展/小应用内容仍未覆盖；`8b210896` 的最终复跑因全局锁排队未取得（见 §11.6）。§4.2 的 IC-01「无调用者」结论已被当前源码推翻，见 §11.4。

统计（按清单条目，允许重叠）：真实截图 10 张（小应用 9、助手 1）；真实几何/DOM 证据覆盖材料页、小应用页、助手页；静态审查覆盖长期记忆、世界书、指令卡、技能/扩展。记录 bug/ugly 约 50 项；已修 31 项（跨页 3、材料 4、扩展 15、小应用/助手 9）；未修/未覆盖约 35 项，其中 P1 为助手 CRUD 缺失、指令卡发送前冲突未接线。

## 1. 结论摘要

- 真实 Chrome 截图共 10 张，全部为 1280×900 浅色：小应用 9 张、助手 1 张。
- 真实 1280 DOM 几何证据覆盖材料页、小应用页、助手页；据此定位并修复了一个跨页 P1 根因：Framework7 的裸 `button{width:100%}` 让所有标签与按钮纵向堆叠。
- 材料页的长期记忆 / 世界书 / 指令卡没有取得真实截图（全局 app 单实例锁排队 + 协调者暂停），只有静态审查与 1280 主列宽度实测（692px）。世界书三列过窄的修复依据来自该实测宽度，不是截图。
- 助手 CRUD（列表/编辑/继承/复制/停用）在全仓库不存在，按大问题报告，未新增假控件。
- 指令卡的发送前冲突提示存在 `send-guard`，但会话发送路径没有调用它；这是功能缺口，未在客户端修复。

## 2. 环境、运行时与证据

### 2.1 真实 app / Chrome 运行时（来自证据 JSON）

| 证据批次 | app JVM PID | app RSS | Chrome PID | Chrome RSS |
|---|---|---|---|---|
| 小应用 01 | 239194 | 975 MiB | 未记录 | — |
| 小应用 02–09 | 239194 | 982 MiB | 246210 | 230–231 MiB |
| 助手 90 | 239194 | 981 MiB | 245694 | 223 MiB |

`jcmd 239194 VM.flags` 关键行：`-XX:MaxHeapSize=1073741824`、`-XX:MaxDirectMemorySize=536870912`、`-XX:+ExitOnOutOfMemoryError`。
`jcmd 239194 GC.heap_info`：`garbage-first heap total reserved 1048576K, committed 376832K, used 319455K`。
app 与 Chrome 均由对应脚本停止；未杀他人进程。

### 2.2 证据清单

真实截图 + 同名 JSON（元素边界、字号、前景/背景、对比度、滚动溢出、runtime）：

- `plans/evidence/visual-audit/materials/miniapps/01…09-*.png|json`
  - 空态、长文件名、打开 iframe、数据面板、清空确认、清空后、生成面板、生成请求中、真实 503 失败。
- `plans/evidence/visual-audit/materials/assistants/90-probe-assistants-1280-light.png|json`
  - 助手与角色页（模型归属）。

过程与问题清单：

- `plans/evidence/visual-audit/materials/miniapps-assistants-notes.md`（B1–B17、U1）
- `plans/evidence/visual-audit/materials/extensions-notes.md`（E-FIX-1..15、EXT-01..14、WB-01..08、IC-01..09）

未取得的证据：材料页长期记忆/世界书/指令卡/扩展的真实截图；360/820、深色、80%/200% 字号的所有真实截图；候选批准/拒绝、技能安装、发送前冲突的真实 UI 流程。原因见 §7。

### 2.3 Bundle / 样式哈希

| 时点 | main.js | workbench.css |
|---|---|---|
| 审查基线 | `67da1cceac3367858dc2bc5103e5b7daf94b082e69c020575a5dd8ea09e768b6` | 修复前 |
| 本轮第一次 release | `6a82af90991e76978b2addf835f431cd366afd50b1ffacc1eb2362e910466f5e` | `3328e8ff65d0aaacd5bda1720227d18231a3b78e390e482644ff0c5c6fd856c3` |
| 最终冻结（W9 11:26 确认） | `dd968793cc74919e139f31bb290a5dfb68309c6d9a78acfd766a4d984f2fda4b` | `dda62554c9bb0b2643c0c437a1368f56b021f716001bcefa3f5cf92a32fa44ca` |

`framework7-bundle.css` 未变：`77da0dd42f787c7f9b8012df796006d19353b6c22af717018ab6a65d6fff2a57`。
`dd968793…`（2026-11-06 11:19:25，3,007,964 bytes）曾是 W9 11:26 确认的冻结候选，含扩展 E-FIX 与 `button{width:auto}`；W21 于 11:50 重建为 `55e4e8ea…`（3,008,937 B）；W33 于 12:17 冻结为 `8b210896…`（3,009,689 B）/ CSS `332f9fd3…`。`dd968793` 与 `55e4e8ea` 均作废。15 分钟冻结窗口（11:26–11:41）内不写 `src/**`/`test/**`、不重建、不跑 E2E。W25 的材料截图绑定 `dd968793/dda62554`，不声称绑定 `8b210896`；四个标记在 `dd968793` 实测各 1，`hc-miniapp-confirm-backdrop`=2、`绑定目标助手`=2、`最近确认`=2。

## 3. 跨页 P1：Framework7 裸 button 宽度

**证据（真实 1280 几何）**：材料页 8 个标签每个 692px 宽，纵向堆叠成 330px 高；小应用「刷新/全屏」各 118px 且竖排；「打开为小应用」占满 240px；助手页「配置/更换」355–433px 宽，角色描述被挤到约 150px；`.wb-list` scrollWidth 704 > clientWidth 692。

**根因**：`resources/public/css/framework7-bundle.css` 的 `button{appearance:none;width:100%}`；`.wb-button` / `.wb-tab` / `.hc-button` / `.hc-sandbox-button` 都没有重置 width。

**已修**：
- `workbench.css` 末尾 `button { width: auto; }`（元素级特异性；`.wb-list-button`、`.wb-nav-item` 等 `width:100%` 类仍胜出）。
- `.wb-list-button { box-sizing: border-box; }`，修正 704>692 的 12px 横向溢出。
- `.wb-page-title { flex-wrap: wrap; }` 与 `min-width:0`，窄屏标题区不再挤压。
- `@media (max-width:599px)` 给 `.wb-tab` 44px 触控高度。

**复验状态**：没有 post-fix 真实截图。CSS 为静态文件，app 重新加载即生效；修复依据是真实几何与 CSS 级联，不是猜测。

## 4. 材料区（长期记忆 / 世界书 / 指令卡）

### 4.1 已修

| 编号 | 问题 | 修复 |
|---|---|---|
| MAT-01 | 世界书三列布局在 1280 主列仅 692px 时每列约 177/221/266px，编辑器表单过窄；带详情栏时更窄。 | `materials/view.cljc` 改为两行两列：书列表+书编辑器一行，条目列表+条目编辑器一行。依据为真实 692px 主列宽度。 |
| MAT-02 | `.hc-mat-split` 用固定两列，字号 200% 时不塌陷。 | 改为 `repeat(auto-fit, minmax(min(100%,22em),1fr))`；`em` 随用户字号缩放，200% 时自动单列。 |
| MAT-03 | `.hc-mat-grid-3` 固定三列，200% 时数字输入约 90px。 | 改为 `repeat(auto-fit, minmax(min(100%,7.5em),1fr))`。 |
| MAT-04 | `整理记忆` 失败时工具栏小字只有 `hc-mat-error`，无样式；且直接显示服务端英文 `MEMORY_SUGGEST_*` message。 | 新增 `.hc-mat-error{color:var(--wb-danger)}`；`materials/model.cljc` 新增 `error-label`，映射 `MEMORY_SUGGEST_MODEL_UNCONFIGURED/UNAVAILABLE/DISABLED/MODEL_FAILED/TIMEOUT/CANCELLED/NO_MESSAGES/RANGE_EMPTY/TEMPORARY_SESSION` 为中文；工具栏与全局错误条统一使用。 |

### 4.2 未修（静态审查）

- **WB-01**：世界书列表/编辑器显示原始 `assistant-id`，而绑定 select 显示助手名；用户看到 `asst_01H…`。
- **WB-02**：`当前会话计数` 外层 `.hc-mat-runtime`，`命中但未提供` 内层又套一个 `.hc-mat-runtime`，双边框/双 padding。
- **WB-03**：`位置深度` 无条件显示；只有 `:between-recent` 使用深度。
- **WB-04 / IC-04**：世界书列表与编辑器各有一个“新建”；指令卡同样重复。
- **WB-05 / IC-05**：删除确认按钮与“保存”同处一个 `.hc-mat-actions` 行，误触风险。
- **WB-06**：“移动到”选项包含当前书自身。
- **IC-02**：“输出格式”是自由文本，无枚举/datalist；`JSON`/`md` 等服务端才拒绝。
- **IC-03**：卡片列表/勾选清单显示 `(name format)` 原始英文 `json`/`text`，与中文界面混排。
- **IC-06**：仅本轮/本会话持续各维护集合，切换后另一集合不可见，无数量提示。
- **IC-09**：pending 时“仅本轮/本会话持续”仍可切换。
- **IC-01（高，功能缺口）**：`workbench/model.cljc` 的 `send-guard` 已实现格式冲突/必填占位符校验，但全仓没有调用者；`conversation/view.cljs` 的 `send-disabled?` 只看 `:disabled?` 与 fix-intent。材料页写着“发送前必须只保留一种格式”，实际发送前不提示、不阻止，直到服务端组装上下文才拒绝。未改，因跨 conversation 模块。

### 4.3 证据缺口

长期记忆候选卡（正文/类别/范围/来源/假设/截断）、批准/拒绝/改范围/归档、临时会话只读、世界书条目/关键词/持续冷却/预算/绑定助手、指令卡本轮/会话持续/冲突提示，均**没有真实截图**。已用服务端领域 API 在 `/tmp/hc-visual-materials/data` 预置 5 条记忆（候选/生效+旧版本/归档/拒绝/敏感）、2 本世界书、3 个条目、3 张指令卡（json/markdown/停用），并已重建 bundle；只差一次 1280/820/360 + 深色 + 80/200% 的截图段。脚本已备：`/tmp/hc-materials-populated.cjs`（覆盖记忆候选批准/拒绝、世界书、指令卡冲突、技能安装、小应用、助手、深色/200%/360）。

## 5. 扩展 / 技能

扩展页没有真实截图；以下为静态审查。已改 `extensions/view.cljc`（E-FIX-1..15，已进入当前 bundle）：

- 助手下拉补空选项；绑定/解绑在目标为空时禁用并给出 title；绑定数为 0 时禁用解绑。
- 文件大小缺失时不再输出“ B · 文本”。
- 两个文件选择器补 aria-label。
- 修复 `diff-row` 对 nil status 调用 `name` 的 NPE。
- 顶层错误按 `SKILL_*` 与非技能错误分流；三处 `sort-by :name` 改安全排序。
- 未同步不再显示 `catalog v0`；`连接 N` 改 `MCP 连接 N`。
- 所有 disabled 按钮的 title 覆盖“未同步/提交中/输入缺失”，不再渲染 `"true"`。
- “本轮实际可用”改“当前会话实际可用”；两处“尚无技能”去重；安装表单标签改“绑定目标助手”。

未修高/中项：

- **EXT-01/EXT-02（高）**：技能删除、MCP 删除立即提交，无二次确认；世界书/指令卡有确认态。
- **EXT-03**：每张技能卡内嵌完整“上游更新与三方对比”表单，技能多时页面极长、标题重复。
- **EXT-05**：会话技能 blocked/excluded 只显示数量，不列名称。
- **EXT-07**：`.hc-ext-check` 复选框热区约 18–20px，低于 32px。
- **EXT-11**：技能卡只显示绑定数量，不显示绑定到哪些助手。
- **EXT-13**：MCP 导入预览没有选择/全选，直接导入全部候选。
- **EXT-14**：扩展顶层错误无关闭按钮。
- 文本安装没有预览步骤；URL 只校验非空；压缩包越界路径由服务端负责，未真实构造。

## 6. 小应用与助手

真实截图 10 张，均为 1280 浅色、修复前按钮宽度；详细 B1–B17/U1 见 `miniapps-assistants-notes.md`。要点：

- **B1（P1，已修）**：跨页 button 宽度根因，见 §3。
- **B2（P1，已修）**：清空确认框 `y=872 h=194`，900 视口只露 28px，焦点仍在触发按钮，无 dialog 语义；改为固定遮罩居中 `role=dialog`、自动聚焦取消、Esc/遮罩取消。
- **B3/B4（P2，已修）**：生成面板默认控件（textarea 182×42、input 167×21、无间距、summary 23px）；改为统一宽度/高度/间距，移动端 44px。
- **B5（P2，已修）**：列表“打开/数据/检查文件”可访问名称重复；aria-label 追加应用名。
- **B6（P3，已修）**：长标题无 `overflow-wrap`；已加 `anywhere` 与 `min-width:0`。
- **B7（P2，已修）**：清空/确认清空用中性色/主色，危险语义反了；新增危险按钮变体。
- **B8（P2，已修）**：助手角色描述 `nowrap+ellipsis`，1280 下已截断且无 title；允许换行并补 title。
- **B9（P2，已修）**：`.wb-list-button` 缺 `box-sizing`，704>692；已加。
- **B10（P3，已修）**：助手整行 hover/pointer 但行不可点；行容器 `cursor:default`。
- **B12（P2，已修）**：助手“最近确认”原始 epoch `1793933888214`；`assistants.cljs` 加 `synced-label` 转本地时间。
- **B11（P1，未修，功能缺口）**：全仓库无助手 CRUD（复制仅角色/选择内容、停用、继承编辑、专属记忆/短语副本）。助手页只有“模型归属/供应商连接”只读投影；未新增假控件。
- **B13（P3，未修）**：503 `MODEL_NOT_CONFIGURED` 仍显示“刷新已有文件”，无“去设置选择模型”入口。
- **B14（P3，未修）**：0 条记录仍可点“清空数据”。
- **B15（P2，未修，沙箱层）**：小应用 iframe 缺 `allow-forms`，控制台 `Blocked form submission`，表单 submit 处理器不生效。
- **B16（P3，未修）**：数据面板“关闭”与详情头“收起数据”重复。
- **B17（P3，未修）**：每次加载一条 404，疑 favicon。

## 7. 覆盖矩阵与未覆盖原因

| 区域 | 1280 浅色 | 360/820 | 深色 | 80%/200% |
|---|---|---|---|---|
| 小应用 | 真实截图（修复前） | 未拍 | 未拍 | 未拍 |
| 助手 | 真实截图（修复前） | 未拍 | 未拍 | 未拍 |
| 长期记忆 | 无截图；真实主列 692px 几何 + 静态 | 无 | 无 | 无 |
| 世界书 | 无截图；真实主列 692px 几何 + 静态 | 无 | 无 | 无 |
| 指令卡 | 无截图；静态 | 无 | 无 | 无 |
| 技能/扩展 | 无截图；静态 | 无 | 无 | 无 |

原因：全局 `/tmp/hc-app-single.lock` 长时间被其他有限段占用，视觉队列 5–8 深；协调者要求暂停排队，避免饿死 E2E05/07/09/11/12。暂停时已主动撤下本工人的 flock 等待作业，并清理自己启动后遗留的 app JVM（PPID=1，已 TERM），未杀他人进程。

恢复后的一次 post-fix 有限段（`/tmp/hc-materials-quick.cjs`，80–87 号截图）在会话选择处超时失败：脚本用 `chromium.connectOverCDP` 连接手工启动的 Chrome，未设置 viewport，实际窗口为 800×600，`.wb-session-list` 在 ≤1023px 被隐藏，`新建会话` 按钮存在但不可见。修复方式：启动 Chrome 加 `--window-size=1280,900`，或在连接后 `page.setViewportSize({width:1280,height:900})`。该段没有产出 80–87 截图，app/Chrome 已停止并释放锁；材料视觉收口已转交 W25，本报告不声称已复验。

## 8. 待办（恢复锁后按有限段执行）

1. 第一段（≤5 分钟）：`/tmp/hc-materials-populated.cjs` 只跑长期记忆 + 世界书 + 指令卡 1280 浅色；重点候选卡正文/类别/范围/来源/假设/截断、批准/拒绝、世界书两行两列、指令卡冲突与必填占位符。
2. 第二段（≤5 分钟）：同脚本的扩展 + 小应用 + 助手 post-fix 1280；确认标签横排、按钮并排、技能安装/启用/绑定、助手角色行不横向溢出。
3. 第三段（≤5 分钟）：360/820、深色、80%/200%；重点 `.hc-mat-split` 自动塌陷、`.wb-tab` 44px、长文案截断、页面标题换行。
4. 仍需产品/服务端决定：助手 CRUD、IC-01 发送前冲突拦截、EXT-01/02 删除确认、B15 沙箱 `allow-forms`。

## 9. 本轮改动文件

- `resources/public/css/workbench.css`：`button{width:auto}`、`.wb-list-button{box-sizing:border-box}`、`.wb-page-title` 换行、`.wb-tab` 移动端 44px、`.hc-mat-split`/`.hc-mat-grid-3` 的 em 自适应、`.hc-mat-error` 颜色。
- `src/hc/client/materials/view.cljc`：世界书两行两列；错误文案走 `model/error-label`。
- `src/hc/client/materials/model.cljc`：`error-labels` / `error-label`。
- `src/hc/client/workbench/views/assistants.cljs`：`synced-label`（本地时间）。
- 扩展与小应用的修复分别见 `extensions-notes.md`、`miniapps-assistants-notes.md`，均已进入当前 bundle `cce553…`。

## 10. 诚实边界

- 本报告没有把静态推断写成截图观察；所有“未拍”均明确标注。
- 世界书两行两列、em 断点、错误中文映射、`synced-label` 均未经过真实浏览器复验；只经过源码/几何推理与 release 编译。
- 编译存在既有告警 `hc.client.conversation.view/re-matcher` 未声明 Var，非本轮文件；若运行时触发内联 Markdown 路径，可能报错，建议会话审查负责人确认。

---

## 11. W25 bundle `dd968793` 复验（2026-11-06 11:27；该 hash 已被 11:50 的 `55e4e8ea` 取代）

### 11.1 运行时与证据边界

- app：`/tmp/pixi run clojure -M:server -m hc.server.app --port 0 --data-dir /tmp/hc-visual-materials/data --workspace-root /tmp/hc-visual-materials/work --static-root resources/public`；Java pid 322157，RSS 1,144,792 KiB；Chrome headless，`--disable-dev-shm-usage`；两者于 11:25:49 前停止，`/tmp/hc-app-single.lock` 已释放。
- bundle：`main.js dd968793cc74919e139f31bb290a5dfb68309c6d9a78acfd766a4d984f2fda4b`（11:19:25，3,007,964 B）、`workbench.css dda62554c9bb0b2643c0c437a1368f56b021f716001bcefa3f5cf92a32fa44ca`。W25 未重建。排队时 hash 还是临时 `4652ef26`；实际截图运行在 `dd968793`。此后 W21 11:50 重建 `55e4e8ea`，W33 12:17 冻结 `8b210896` / CSS `332f9fd3`。**本轮 21 张截图未在最终 `8b210896/332f9fd3` 上重拍**；材料区结论对 `dd968793/dda62554` 成立，最终 hash 需抽查深色底、触摸目标与 tab 高度。
- 产物：21 张 PNG + 21 个同名 JSON，位于 `plans/evidence/visual-audit/materials/`。
- **证据分级**（文件名已自描述）：
  - 已同步 1280（9 张，可用）：`w25-40…44-1280-dark`、`w25-50…53-1280-200-actual100`。
  - 未同步 1280（12 张，只证明壳层）：`w25-01…14-1280-light-unsynced`。页面显示“未连接”“尚未与服务器同步，命令不可提交”，材料数据为空，不能作为内容证据。
  - `w25-50…53-…-200-actual100` 是**误命名的 100% 浅色**：实测 `html` 字号 15px、`--wb-font-scale: 1`。原因是用 localStorage 注入 `font-scale:200` 后 reload 未生效；同一方法设置 `theme:dark` 生效，说明不是主题路径问题。不得把这 4 张当 200% 证据。
- 未完成原因：首次连接在 12 张浅色截图期间一直未就绪（约 30 秒）；820/360 与真 200% 被 400 秒包装器截止时间截断；按用户要求优先释放锁，未再排第二段。

### 11.2 复验通过（真实几何 / DOM / 截图）

| 项 | 证据 | 结论 |
|---|---|---|
| B1 跨页裸 button 宽度 | 8 个材料主标签同一 `y=230`，宽 56–101px、高 36px；页面内没有 >320px 的可见 button；助手页“配置/更换”横排；小应用“刷新/全屏”横排 | 1280 下已修 |
| MAT-01 世界书两行两列 | 两个 `.hc-mat-split`，`y=371` 与 `y=742`，各宽 677px；列表/编辑器各约 306px | 已修，真实截图确认 |
| MAT-02 `.hc-mat-split` em 自适应 | 1280/100% 下 677px 内为两列；22em≈330px，符合 auto-fit | 源码与几何一致；200% 未验 |
| B8 助手角色描述换行 | `.wb-list-main small` 宽 542px、高 19px，1280 下完整显示，无省略号；行宽 677px | 已修 |
| B12 “最近确认” epoch | 显示 `2026/11/6 11:24:05`，非 13 位数字 | 已修 |
| 横向溢出 | 所有采样 `.wb-content-inner/.hc-mat-pane/.wb-list/.hc-mat-editor` 的 `scrollWidth == clientWidth` | 1280 下无溢出 |
| 深色主题 | 面板背景 `rgb(24,32,41)`、文字 `rgb(238,244,249)` | 深色变量生效 |
| 长期记忆内容 | 5 条：3 待审、1 生效、1 已归档、0 已拒绝；候选显示“待审”“来源片段”“假设（未确认）”、范围 `assistant-writer`、更新序号 5 | 候选/来源/假设可读；种子里的“临时密码 hunter2”未被服务端敏感规则命中（规则要求 `password:` 形式），因此**没有**“含敏感内容”徽章，敏感徽章本轮未验证 |
| 小应用空态 | 工作区为空时显示“还没有工作区小应用”，生成面板折叠，刷新/全屏横排 | 壳层正常；无内容可验 |

### 11.3 复验未通过 / 仍未修（有证据或静态确认）

| 编号 | 证据 | 状态 |
|---|---|---|
| WB-01 原始 `assistant-id` | 世界书列表显示“绑定 assistant-writer”（真实 DOM）；编辑器“已绑定：”与绑定按钮同用原始 ID（源码同源，未在截图中展开已选书） | 未修 |
| WB-03 位置深度无条件显示 | 世界书条目编辑器在“位置=背景”时仍显示“位置深度”输入 | 未修，真实 DOM 确认 |
| WB-04 / IC-04 重复“新建” | 世界书列表头“新建”+编辑器头“新建”；条目列表“新建条目”+编辑器头“新建”；指令卡列表头“新建”+编辑器头“新建” | 未修，截图可见 |
| IC-03 原始格式名 | 指令卡列表/勾选清单显示 `json`、`markdown` 徽章，与中文界面混排 | 未修，截图可见 |
| WB-02 嵌套 `.hc-mat-runtime` | `materials/view.cljc` 外层与“命中但未提供”内层仍各套一个 `.hc-mat-runtime` | 静态确认；该段在首屏下方，未截到 |
| EXT-01/02 删除无确认 | `extensions/view.cljc` 的 MCP/技能“删除”仍直接发命令 | 静态确认；扩展数据为空，未做真实点击 |
| EXT-03/05/07/11/14 | 上游更新整块常驻、blocked/excluded 只给数量、复选框热区、绑定只给数量、顶层错误无关闭 | 静态确认；本轮未改 |
| B2 清空确认模态 | 工作区 `/tmp/hc-visual-materials/work` 没有小应用，无法打开数据页 | 未复验（W18 的修复代码仍在） |
| B3 生成面板样式 | 同上，未展开有内容的生成流程 | 未复验 |
| B11 助手 CRUD | 助手页仍只有模型归属/供应商连接只读投影 | 功能缺口，未修 |
| B13/B14/B16 | 失败动作仍为“刷新已有文件”；0 条记录仍可点“清空数据”；数据面板“关闭”与“收起数据”重复 | 静态确认，未修 |
| B15 `allow-forms` | `src/hc/client/sandbox/AGENTS.md` 明确写“绝不添加 allow-forms”，且 `form-action 'none'` 双重阻止；这是安全边界 | **改判：不是缺陷**；文档应说明表单只经桥接上报，不原生提交 |

补充观察（未定级）：首次加载后的约 30 秒内页面一直显示“未连接”，12 张浅色截图都带“尚未与服务器同步，命令不可提交”；`page.reload()` 后立即显示“已同步”。无法从本轮数据区分是启动竞态、Sente 重连退避，还是测试脚本等待不足。建议 W16 在最终 bundle 上用同一数据目录复现一次；若是重连退避，属于启动可用性问题。

### 11.4 结论修正

- **IC-01 源码已接入发送路径**：`src/hc/client/workbench/views/session.cljs:352` 在 `:send!` 回调里先跑 `model/send-guard`，命中后 `show-notice!` 并阻止发送。该文件 mtime 11:14:19，早于 `dd968793` 的 11:19:25 构建；按增量构建顺序应已进入该 bundle（后续 `55e4e8ea` 同源重建，理论上也在），但本轮没有取得“已选两张冲突卡→发送→toast”的运行时证据，因此 bundle 包含关系标为 `[INFERENCE]`。无论如何，“全仓没有调用者”的旧结论不再成立。
- **B15 改判**：沙箱设计明确禁止 `allow-forms`，表单提交被阻止是预期行为；若要支持应用内表单，应走既有 bridge 字段上报，不应加 `allow-forms`。

### 11.5 W25 覆盖矩阵

| 区域 | 1280 浅色 | 1280 深色 | 1280 200% | 820 | 360 |
|---|---|---|---|---|---|
| 长期记忆 | 仅未同步壳层 | 真实内容 | 误为 100% | 未拍 | 未拍 |
| 世界书 | 仅未同步壳层 | 真实内容 | 误为 100% | 未拍 | 未拍 |
| 指令卡 | 仅未同步壳层 | 真实内容 | 误为 100% | 未拍 | 未拍 |
| 技能/扩展 | 仅未同步壳层 | 未拍 | 未拍 | 未拍 | 未拍 |
| 小应用 | 仅未同步壳层 | 空态 | 未拍 | 未拍 | 未拍 |
| 助手 | 仅未同步壳层 | 真实内容 | 误为 100% | 未拍 | 未拍 |

### 11.6 待补（下一段按此顺序，≤5 分钟）

1. 等页面出现“已同步”徽章或 `.wb-toast` 消失后再截图；不要以固定 2 秒代替同步等待。
2. 用设置页 `#hc-appearance-font-scale` range 输入设 200%（或直接 `document.documentElement.style.setProperty('--wb-font-scale','2')`），先断言 `getComputedStyle(document.documentElement).fontSize == '32px'` 再截图；不要用 localStorage 注入。
3. 小应用改用 `/tmp/hc-visual-materials/mini-work`（W18 的 mini-data 有应用），复验 B2 确认框、B3 生成面板。
4. 扩展需要预置至少 1 个技能 + 1 个 MCP 服务器，才能复验 E-FIX 的绑定/解绑、删除确认、错误关闭。
5. 发送前冲突：在已同步会话勾选 `card-json` + `card-md`（本会话持续），到会话页发送，断言 toast 文本以“指令卡格式冲突：”开头且没有消息发出。
6. 解冻并获准重建后，最小修复建议（均未在本轮改源码，避免与冻结 bundle 分叉）：
   - `materials/view.cljc`：WB-01 用 `assistant-options` 的 `:label` 映射绑定 ID；WB-03 仅在 `:between-recent` 渲染“位置深度”；WB-04/IC-04 去掉编辑器头的重复“新建”；IC-03 加 `format-label`（json/markdown/text，未知回退 `name`）；WB-02 去掉内层 `.hc-mat-runtime` 类。
   - `extensions/view.cljc` + `model.cljc`：EXT-01/02 增加 `:confirm-delete` 两段式；EXT-14 顶层错误加“关闭”并接 `:extensions/dismiss-error`。
   - `workbench.css`：`.hc-ext-check{min-height:32px}`。

### 11.7 截图清单

已同步：`w25-40-worldbook-1280-dark`、`w25-41-cards-1280-dark`、`w25-42-memory-1280-dark`、`w25-43-miniapps-1280-dark`、`w25-44-assistants-1280-dark`、`w25-50-worldbook-1280-200-actual100`、`w25-51-cards-1280-200-actual100`、`w25-52-memory-1280-200-actual100`、`w25-53-assistants-1280-200-actual100`。

未同步壳层：`w25-01-memory`、`02-memory-candidate`、`04-worldbook`、`05-worldbook-selected`、`06-worldbook-entry`、`07-cards`、`08-card-json`、`09-cards-conflict`、`10-extensions`、`11-miniapps`、`13-assistants`、`14-send-guard`，均为 `1280-light-unsynced`。

### 11.8 最终 hash 复跑：本轮跳过（前冻结；无 `8b210896`/`ea9e5732` 证据）

- 目标：在 `8b210896`（12:17:19）上补 1280/820/360、深色、真 200%（html 32px）、扩展 1 技能 + 1 MCP、mini-work 小应用；13:02 后目标 hash 又被 `ea9e5732`（3,010,646 B，13:02:42，CSS `332f9fd3` 未变）取代。
- 实际：**没有产生任何 `w25b-*` 截图**；只有一份 `w25b-failure.json` 诊断。不得把 §11 的 `dd968793/dda62554` 证据写成 `8b210896` 或 `ea9e5732` 证据。
- 尝试记录：
  1. `flock -w 1800` 排队 30 分钟超时（E2E05 app 长期持有）。
  2. 一次 106 秒的等待被 SIGTERM。
  3. 获得授权的 0.2 秒 `flock -n` 循环一度抢到锁并启动 app，但 W25 为缩短轮询间隔误杀自己的循环，node/app 一并退出；**无证据**，锁随即被 W3 取得。
  4. 改回 `flock -w 1800` 阻塞等待后，按 Main 指示撤下 waiter；当时 holder 为 E2E09/E2E01/E2E12 队列，W15 判断“完全空闲且无阻塞 waiter”可能很久。
  5. 13:02:32 抢到锁并启动 app，但服务端日志的 base URL 带尾斜杠，脚本拼成 `//`，页面 `replaceState` 抛 `SecurityError`，等待“已同步”90 秒超时；无截图，app 已停。
  6. 13:06 再次抢到锁并启动 app（此时 bundle 已变为 `ea9e5732`），运行 13 秒后按 W15/Main 的“让 W22/W36 继续 E2E”裁决主动停止；无截图。
- 结论：按裁决记录为“跳过、证据前冻结（无证据）”。最终 hash 的材料视觉复跑仍待一次干净的 5 分钟锁窗口；本报告不伪造该段结论。已确认 app/Chrome 停止、锁释放（13:10:22）。

## 12. final11 重绑（bundle `72de4f9a` / 源码树 `7dfcf0d7`（final12；原 ab801450 已前冻结），CSS `332f9fd3` 未变）

- §11 全部 21 张 `w25-*` 截图绑 `dd968793/dda62554`（中间 hash，已作废）；§11.8 的 `8b210896`/`ea9e5732` 复跑无截图、标“前冻结（无证据）”。
- final11 复验（证据 `plans/evidence/visual-audit/final11/`，bundle `72de4f9a` + 树 `7dfcf0d7`，CSS `332f9fd3`）：材料区 1280 两张（`shots/30-materials-1280`、`31-materials-nav-1280`）。实测壳层：材料与扩展导航、分区 tab（工作区文件/产物/长期记忆/世界书/指令卡/浏览器/扩展/小应用）渲染正常、已同步徽标可见；数据目录为复用 conversation 段的稀疏数据，长期记忆/世界书/指令卡/扩展/小应用内容为空态，深色底、触摸目标、tab 高度、真 200%、冲突卡发送守卫均未覆盖，不宣称 PASS。
