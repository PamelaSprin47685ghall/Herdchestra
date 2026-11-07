# 响应式 / 无障碍 / 空态错误态 / 跨页面一致性视觉审查（0.0.1）

状态：**最终冻结 `072587a4`（13:51:04，CSS `332f9fd3`）已通过 final7 单树全量**。最终视觉证据采用 W29 的 `audit3-072587a4.json` + `shots3-072587a4/`（360/820/1280/200%/暗色）；本目录 13 张 `r2-final-*` 为前冻结对照。第一轮 24 项与 13 项修复见 §2–§3，第二轮见 §8；残留见 §8.6。

- 审查人：W21（visual-responsive）
- 日期：2026-11-06
- **新冻结构建**：`072587a4690b6953bae9cae3a06eaaa815a38b13eee9b98dc5ce98e72a54824f`（3,011,162 B，mtime 2026-11-06 13:51:04）；CSS `332f9fd3f3e759f5ef215dbe1ff9307e774d7f56b262d0ce9839d861e8609c4b`。`ea9e5732` 的 13 张 + 2 JSON 为前冻结；待 final6 窗口结束后重绑。
- 环境：真实 `hc.server.app` + 系统 `/usr/bin/google-chrome` 153（Playwright 1.64.1，headless），`--disable-dev-shm-usage`
- 约束：单段 `flock /tmp/hc-app-single.lock`；单 app JVM + 单 Chrome；app `JAVA_TOOL_OPTIONS=-Xmx768m -XX:MaxDirectMemorySize=512m -Djava.io.tmpdir=/tmp/hc-visual-responsive/tmp`；结束立即停 app/Chrome。当前无本人工 app/Chrome 残留。

## 1 结论（先看这一段）

已记录 24 项问题（16 bug、6 ugly、1 a11y、1 bug/ugly）：其中内部状态/时间戳泄漏、桌面死菜单、无模型空态入口不足已有真实截图或 DOM 文本证据；字号缩放、首屏空白、触摸热区、焦点样式、深色横幅、看板英文等由静态审查确认。最严重的是：

1. **字号 80%–200% 只缩放了 `body`，所有 `rem` 文本不缩放**（会话标题、元信息、提示、按钮字号在 200% 时仍是 100% 大小），层级和可读性同时被破坏。
2. **会话头与会话列表直接显示内部状态词/时间戳**：`:active`、`active · 1793934179647`；辅助面板还显示 `system`、`available`。
3. **手机触摸热区系统性不足**：顶栏状态胶囊约 22px 高，`.wb-button-small` 32px，`.wb-tab` 36px，会话头/横幅/消息操作大量 inline `minHeight 32`。
4. **桌面 1280 仍显示汉堡菜单**，但左侧导航是固定列，按钮无可见效果（死控件）。
5. **首屏在 JS 加载前是纯空白**，没有 AGENTS §7.4 1.1 要求的“稳定页面骨架 + 正在打开本地数据”。
6. **Swarm 看板整套内部标签是英文**（Running/Queued/Ready/Waiting/Done、Slot、Token holder、not reported 等），与其余中文界面断裂。

D-08/E2E13 的 bundle 冻结解除后，本轮已应用 shell/session/common/index.html/workbench.css/mutations 的响应式修复并重建；`conversation/view.cljs` 由 W17 负责，未覆盖。新 bundle 不含 W17 在 11:17:45 的最后一笔改动，会话区需 W17 重建后再复验。

## 2 问题清单（含已确认与静态确认）

| ID | 类别 | 严重度 | 问题 | 证据 | 状态 |
|---|---|---|---|---|---|
| R-01 | bug | 高 | 会话头把 `:status` 关键字原样拼成文本，显示 `:active`；应映射为中文或不显示 `active/idle` | `src/hc/client/conversation/view.cljs:2375` `(dom/span nil (str status))`；w17 截图 `模型: Visual Stub :active`；smoke 文本 | 已转交 w17（view.cljs 由其修改） |
| R-02 | bug | 高 | 会话列表行显示内部状态与 13 位毫秒时间戳：`active · 1793934152874`；同页助手视图已用本地化时间，跨页不一致 | `src/hc/client/workbench/views/session.cljs:73`；w17 `crop-left.png`；smoke 文本 | 待修 |
| R-03 | bug | 中 | 窄屏导航抽屉与辅助面板“状态”行显示 `active` | `src/hc/client/workbench/views/shell.cljs:165,243` | 待修 |
| R-04 | bug | 中 | 辅助面板“本地状态”显示 `system`、`available`，内部英文 token 直接暴露 | `src/hc/client/workbench/views/shell.cljs:304,306`；w20 `01-settings.png`、`03-diag-stats.png` | 待修 |
| R-05 | bug | 高 | 字号 80%–200% 只作用于 `body`；`rem` 仍锚定 16px。200% 时会话标题 1.1rem≈17.6px、元信息 .82rem≈13.1px、提示 .78rem≈12.5px，均未放大 | `resources/public/css/workbench.css:66`；`conversation/view.cljs` 内联 CSS 大量 `rem`；`appearance.cljs` 只设 `--wb-font-scale` | 待修（方案见 §3.A） |
| R-06 | bug | 高 | 首屏空白：`index.html` 的 `#app` 在 bundle 执行前没有骨架/加载文案；慢盘或大 bundle 时用户看到纯白 | `resources/public/index.html:11-13`；AGENTS §7.4 1.1 | 待修（§3.B） |
| R-07 | bug/ugly | 中 | 1280 桌面顶栏仍有 `☰`，但 ≥1024 导航是固定列，点击只改不可见的 drawer 状态；视觉上像坏按钮 | `shell.cljs:57-69`；W26 后续规则 `.wb-topbar .wb-menu-button{display:none}` | 已修（最终 CSS） |
| R-08 | bug | 高 | 触摸热区不足：`.wb-badge`、`.wb-button-small`、`.wb-tab`、`.hc-tab`、`.hc-button--quiet`、`.wb-session-search`、上下文/消息操作 | 最终 CSS 后 360/820 实测均 44；range 仍 40 | 已修（range 低风险残留） |
| R-09 | bug | 中 | 键盘焦点样式缺失/不一致：`.wb-badge`、`.wb-tab`、`.hc-tab`、`.wb-nav-session`、`.wb-session-search` 无显式 `:focus-visible`；依赖浏览器默认轮廓 | `workbench.css` 对应选择器 | 待修（§3.A） |
| R-10 | ugly | 中 | 深色主题下 `.hc-banner-error/warning/info`、`.hc-late-result`、`.hc-context-warnings`、`.hc-blocked` 仍是浅色硬编码背景，形成刺眼的“白色孤岛” | `conversation/view.cljs:46,78-80,89,97` | 已转交 w17 |
| R-19 | bug | 中 | 未按主题设置 `color-scheme`；深色系统下显式选浅色主题时，原生滚动条/表单控件仍可能按深色绘制 | `resources/public/css/workbench.css:1-64`；`index.html` 的 `meta color-scheme` 是 `light dark` | 待修 |
| R-20 | bug | 中 | 顶栏没有 `env(safe-area-inset-top/left/right)`；`viewport-fit=cover` 下刘海/圆角屏会压住菜单或状态胶囊 | `workbench.css:95-101`；`index.html` viewport meta | 待修 |
| R-21 | bug | 中 | 移动端整页用 `height:100%` 链，浏览器地址栏收放时输入区可能被挡住；建议加 `100dvh` 回退 | `workbench.css:50-58` | 待修 |
| R-22 | bug | 高 | 会话输入条 `.hc-composer{z-index:60}` 高于导航/辅助抽屉（z 40）与遮罩（z 30）；窄屏打开抽屉时输入条会盖在抽屉和遮罩上 | `conversation/view.cljs:67`；`workbench.css:847-887,754` | 已转交 w17 |
| R-23 | bug | 中 | 窄屏可同时打开导航抽屉和辅助抽屉；两者宽度 82vw+88vw 在 360px 下重叠，且打开一个不会关另一个 | `mutations.cljs:100-160`；`shell.cljs:57-91` | 待修（mutations.cljs） |
| R-24 | a11y | 低 | 顶层工作区导航用 `<aside>`（complementary landmark）而不是 `<nav>`；屏幕阅读器导航地标缺失 | `shell.cljs:94` | 待修 |
| R-17 | bug | 低 | 迟到工具结果直接 `(name status)`，可能显示英文 `failed` | `conversation/view.cljs:1328` | 已转交 w17 |
| R-11 | ugly | 高 | Swarm 看板整体英文：`Running/Queued/Joined/Abandoned/Failed`、`Ready/Waiting/Done`、`Slot`、`Token holder`、`not reported`、`No board tasks...`；页头却是中文 | `src/hc/client/swarm/model.cljc:47-86`；`src/hc/client/swarm/ui.cljs:457-780` | 报告（建议 w19 统一） |
| R-12 | bug | 中 | 无模型空态只有输入框下方一行小字提示和“模型：尚未选择模型”小按钮；消息区没有主操作“选择模型”，与 AGENTS §1.4 的入口要求不符 | `conversation/surface.cljc` `banners` 无 model-missing 分支；smoke 文本 | 待修（§3.F） |
| R-13 | ugly | 低 | 宽屏会话列表每行都常驻“重命名/置顶/归档/删除”四个 32px 按钮，列表密度高、视觉噪音大 | `sessions/controls/ui.cljs:184-190`；`workbench.css:2162` | 报告/可后续 |
| R-14 | bug | 低 | 辅助面板/材料页的 `role=tablist` 没有方向键与 roving tabindex，只能逐个 Tab；不是完整 ARIA tabs 交互 | `views/common.cljs:113-123` | 报告 |
| R-15 | ugly | 低 | `.wb-toast-stack` 重复声明 `display:flex; flex-direction:column`（死 CSS） | `workbench.css:804-812` | 已确认当前文件无重复，关闭 |
| R-16 | ugly | 低 | 服务端默认会话标题是英文 `New session`，中文界面里混排 | `src/hc/server/app.clj:251,277`；smoke 文本 | 报告（服务端） |
| R-18 | ugly | 低 | 消息详情里的工具读写、附件 kind、估算来源用 `(name ...)` 显示英文 token | `conversation/view.cljs:1003,1034,1044` | 报告/可后续 |

| R-26 | bug | 中 | 1024–1199 三栏挤压：1024 下会话列仅 260px，1100 下 336px | r2-part3 摘要 `widths.conversation` | 最终 CSS 已将 1024–1199 导轨缩到 200/260、会话列 min 190，未重拍确认 |

## 3 已应用修复（2026-11-06 11:17 重建）

### A. 全局字号、顶栏、焦点与触摸热区（`resources/public/css/workbench.css`）

1. **字号根锚点**：把 `html` 的根字号接到同一变量，`body` 改为 rem，避免双重缩放：
   ```css
   html { font-size: calc(16px * var(--wb-font-scale)); }
   body { font-size: 0.9375rem; } /* 15px @100% */
   ```
   这样 200% 时 `1.1rem` 标题=35.2px、`.78rem` 提示=25px，全部按比例放大。
2. **顶栏品牌可收缩**：`.wb-brand{flex:0 1 auto;min-width:0;overflow:hidden}`；`.wb-brand strong{white-space:nowrap;overflow:hidden;text-overflow:ellipsis}`。避免 200% 窄屏顶栏溢出。
3. **桌面隐藏死菜单**：`@media (min-width:1024px){.wb-menu-button{display:none}}`。导航列常驻，菜单无意义；`#wb-menu` 仍保留在 DOM，窄屏与测试不受影响。
4. **状态胶囊**：`button.wb-badge{font:inherit;cursor:pointer;min-height:32px;padding:4px 10px}`；≤1023 时 `min-height:44px`。
5. **触摸热区**：在 `@media (max-width:1023px)` 增加 `.wb-button-small,.wb-tab,.hc-tab,.wb-session-search{min-height:44px}`；`.hc-version-button{min-height:44px;min-width:44px}`。
6. **焦点样式**：
   ```css
   .wb-badge:focus-visible,.wb-tab:focus-visible,.hc-tab:focus-visible,
   .wb-nav-session:focus-visible,.wb-session-search:focus-visible{
     outline:2px solid var(--wb-accent);outline-offset:1px;
   }
   ```
7. 清理 `.wb-toast-stack` 重复声明。

### B. 首屏骨架（`resources/public/index.html`）

在 `#app` 内放静态骨架，React 挂载会替换：
```html
<div id="app">
  <div class="hc-boot" role="status" aria-live="polite">
    <strong>Herdchestra</strong>
    <span>正在打开本地数据…</span>
  </div>
  <noscript>Herdchestra 工作台需要 JavaScript。</noscript>
</div>
```
并在 `workbench.css` 增加 `.hc-boot` 居中骨架样式（`prefers-reduced-motion` 下停用脉冲）。同时加 `<link rel="icon" href="data:,">` 消除 favicon 404（smoke 控制台唯一 error）。

### C. 状态词与时间（`views/common.cljs` + `views/session.cljs` + `views/shell.cljs`）

- 新增 `session-status-label`：`running/starting/streaming/dispatching/applying → 进行中`，`stopping → 正在停止`，`failed/error → 未交付`，`archived → 已归档`，`deleted → 已删除`，`completed → 已交付`；`active/idle/finished` 返回 nil（不显示）。
- 新增 `time-label`：数字或 ISO 字符串用 `js/Date` + `toLocaleString("zh-CN",{hour12:false,...})`；解析失败回退原值。助手页现有 `synced-label` 迁到这里复用。
- 会话行 details 改为 `(filter seq [status-label time-label model-label])` 用 ` · ` 连接，空则省略。
- 导航抽屉、辅助面板“状态”用同一 label；`主题`/`草稿存储` 映射 `跟随系统/浅色/深色`、`可用/不可用/未知`。

### D. 会话头（`conversation/view.cljs`）

- 删除 `(str status)`；改为 `:status-label`，只显示有意义的状态（进行中/未交付/已归档），`active/idle` 不渲染。
- 头部“模型：…”按钮的 inline `minHeight/minWidth 32` 改为 `(get-in view-model [:a11y :target-size] 44)`。
- 横幅动作按钮、消息操作按钮、附件移除、版本按钮同样改用目标尺寸；或在 ≤599 媒体查询里覆盖到 44 并移除 inline 32。

### E. 深色主题横幅（`conversation/view.cljs` 内联 CSS，已转交 w17）

用 token + `color-mix` 替换硬编码浅色，保留旧声明作回退：
```css
.hc-banner-error{border-color:var(--wb-danger,#c62828);
  background:#ffebee;
  background:color-mix(in srgb,var(--wb-danger,#c62828) 10%,var(--wb-surface,#fff));
  color:var(--wb-danger,#7f0000)}
```
warning/info/late-result/context-warnings/blocked 同理。这样浅色/深色/高对比都跟随 token。

### F. 无模型空态（`conversation/surface.cljc`，待 w17 确认归属）

`banners` 增加：
```clojure
(composer/model-missing? model)
(conj {:kind :info
       :text "先选择一个可用模型；草稿与历史仍保留。"
       :actions [{:id :select-model :label "选择模型"}]})
```
渲染层已有 `:select-model` → `:settings!` 的回调，无需新增管线。

### G. 抽屉互斥与输入条层级（`mutations.cljs` + w17 的 `view.cljs`）

- `toggle-sidebar` 在 overlay 布局打开时同时 `assoc :ui/aux-open false`；`toggle-aux` 打开时同时 `assoc :ui/sidebar-open false`。避免 360px 下两个抽屉重叠。
- `.hc-composer` 的 z-index 从 60 降到 20，让抽屉（40）和遮罩（30）盖住输入条。

### H. 大问题（不在本包直接修）

- Swarm 看板英文标签：`swarm/model.cljc` 的 `status-labels/task-state-labels/role-labels/life-state-labels/status-details/recovery-reason-labels` 与 `swarm/ui.cljs` 的可见文案统一中文化；建议 w19 处理，避免与看板布局改动冲突。
- 服务端默认标题 `New session`：由 server 侧改为中文或客户端仅在“标题等于默认值”时显示“新会话”；需服务端 owner 确认。
- 1024–1199 宽度：导航 224 + 辅助 320 后主区过窄，会话列 30% 会把对话压到 ~260px。建议把 wide 断点从 1024 提高到 1200（CSS 与 `a11y/layout-for-width` 同步），或该区间把辅助列改为抽屉。待真机截图确认后再定。

## 3.1 本轮已应用清单

| 文件 | 改动 |
|---|---|
| `resources/public/css/workbench.css` | `html` 根字号接 `--wb-font-scale`、`body` 改 `0.9375rem`；`color-scheme` 按主题；`100dvh`；顶栏/抽屉 safe-area；品牌可收缩；≥1024 隐藏 `#wb-menu`；`button.wb-badge` 字号/32px/焦点；≤1023 触摸目标 44px；`.wb-tab/.hc-tab/.wb-nav-session/.wb-session-search` 焦点环；`.hc-boot` 首屏骨架；删除重复 toast 声明（已无）。 |
| `resources/public/index.html` | `#app` 内加“正在打开本地数据…”骨架；`data:` favicon 消除 404。 |
| `views/common.cljs` | 新增 `session-status-label`、`time-label`、`session-detail`、`theme-label`、`draft-storage-label`。 |
| `views/session.cljs` | 会话行改用 `common/session-detail`，不再显示 `active` 和 13 位毫秒。 |
| `views/shell.cljs` | 顶层导航 `<aside>`→`<nav>`；抽屉会话行、辅助面板“状态”、主题/草稿存储改中文；补回 `nav` 会话块缺失的 `)`。 |
| `mutations.cljs` | 窄屏打开导航时关闭辅助抽屉，反之亦然。 |

构建验证：`flock /tmp/hc-client-release.lock -c 'npx shadow-cljs release :app'` EXIT=0（仅 infer-warning）。D-08 四标记各 1；`AbortController`=19；W17 `hc-markdown`=1；W18 `hc-miniapp-confirm-backdrop`=2、`hc-assistants`=5、`hc-sandbox-button-danger`=5、`绑定目标助手`=2、`最近确认`=2；扩展会话清空源码 mtime 11:15:28 早于构建。

## 3.2 前冻结复验（0c05b7d1；最终 072587a4 待短复验）

`verify-b14.cjs` 在真实 app 上记录 `SERVED_HASH=0c05b7d1…`（前冻结），结果：

| 项 | 结果 |
|---|---|
| B14 抽屉关闭 | 顶栏 `z-index:50` 高于抽屉 `z-index:40`，菜单按钮仍可见；点击后 `data-open=false`，焦点回 `#wb-menu`；抽屉内另有 `#wb-nav-close`。 |
| B15 会话列宽 | 360 视口下 `.wb-conversation`/`.hc-conversation-page` 均 360，`.hc-message-stream` 340；注入 840px 宽块后仅 `.hc-conversation-messages` 内部 `scrollW=850`，整页 `docOverflowX=0`，发送按钮在视口内。 |
| 深色内容底 | 360 深色 board：`.wb-content` 背景 `rgb(17,22,28)`，h1 颜色 `rgb(238,244,249)`，与 body 同底，不再出现“浅色纸 + 浅色标题”。 |
| 触摸目标 | 360/820：`.hc-tab` 44、`.hc-button--quiet` 44、`.wb-tab` 44、`.wb-nav .wb-session-search` 44；range 仍 40（低风险）。 |

证据：`plans/evidence/visual-audit/responsive/final-0c05b7d1-phone-360-light-nav-close.png`、`final-0c05b7d1-phone-360-light-width.png`、`final-0c05b7d1-phone-360-dark-board-bg.png`、`final-0c05b7d1-b14-b15.json`。

## 4 视口 / 页面矩阵（基线 dd968793 + dda62554；最终 hash 仅复验 B14/B15/深色）

| 视口 | 浅色 | 深色 | 页面 |
|---|---|---|---|
| 360×800 窄手机 | 已跑 | 已跑 | 会话空态/草稿、导航抽屉、辅助抽屉四页签、看板、助手、材料、设置四分区、外观 200% |
| 430×932 大屏手机 | 已跑 | 已跑 | 同上（重点输入区/发送/停止） |
| 820×1180 平板 | 已跑 | 已跑 | 同上（重点折叠导航/侧栏） |
| 1280×800 桌面 | 已跑 | 已跑 | 三栏、辅助四页签、设置全部页签、键盘 Tab 顺序、断线态 |
| 360px 宽桌面窄窗 | 已跑 | 已跑 | 抽屉、焦点返回、草稿不丢 |
| 字号 80/100/150/200 | 已跑（html 12.8/16/24/32px） | 200% 抽查 | 会话、外观设置、导航抽屉 |
| 键盘/焦点 | 已跑（Tab 序列/抽屉 Escape/焦点返回） | — | Tab 顺序、抽屉进入/返回、焦点环、Escape 层级 |
| 空态/加载/错误/断线/无模型/材料未就绪 | 已跑首屏骨架/断线/无模型/空态 | — | 首屏空白已确认；断线态待跑；无模型态已确认缺主操作 |

## 5 已跑证据与运行记录

- `plans/evidence/visual-audit/responsive/desktop-1280-light-sessions-smoke.png`：1280×800 浅色首屏（真实 app，bundle `a8acf565`（修复前基线））。
- `plans/evidence/visual-audit/responsive/w17-session-list-raw-status.png`：w17 真实模型会话左侧列表，含 `active · 1793934179647`。
- `plans/evidence/visual-audit/responsive/w17-conversation-raw-status.png`：w17 会话头 `模型: Visual Stub :active`。
- `plans/evidence/visual-audit/responsive/w20-settings-raw-local-state.png`、`w20-diagnostics-raw-local-state.png`：w20 设置/诊断页右侧“主题 system / 草稿存储 available”。
- 首屏 smoke：app PID 256587，URL `http://127.0.0.1:32991`，Chrome headless；smoke 结束后 app 已停止。该次未记录 RSS；后续每段用 `jcmd VM.flags`、`GC.heap_info`、`ps -o rss` 写入 `logs/`。
- 控制台唯一 error：favicon 404（`Failed to load resource: 404`；新 index.html 已加 `data:` favicon）。
- 重建记录：`4652ef26…`，11:17:42，3,007,766 B，EXIT=0；`conversation/view.cljs` 11:17:45 晚于构建，W17 最新改动不在该 bundle。
- 当前无本人工 app/Chrome；`/tmp/hc-visual-responsive/run-once.sh` 已加 `flock -w 300`、`timeout 210`、`jcmd`/`ps` 采样，单段 ≤5 分钟。

## 6 未覆盖 / 风险

1. 本轮没有可用模型，真实助手消息、流式、长表格/代码、S1 卡片、停止后迟到结果未在视觉上复跑；w17 截图只覆盖了生成中的一段。
2. 真实屏幕阅读器（Orca/NVDA）与真实触摸设备未跑；键盘结论将来自 Playwright Tab 序列。
3. 200% 下顶栏、输入区、辅助抽屉的挤压程度需截图确认；静态判断是顶栏品牌可收缩、按钮会换行。
4. 1024–1199 桌面窄窗的三栏挤压是结构性风险，需单独视口确认。
5. bundle 冻结期间不应用源码修复，避免其他 worker 重建时带入未复验改动。

## 7 下一步

1. 等协调者通知 E2E 让出锁后，按 part1（1280 浅/深 + 键盘 + 断线）、part2（360/430）、part3（820/窄桌面）、part4（字号）四段各跑一次，每段结束立即释放。
2. 冻结解除后应用 §3 的 A–F，重建 bundle，先核对 D-08 的四个标记，再跑 part1/part2/part4 复验。
3. 把 R-11（看板英文）与 R-16（默认标题）交给对应 owner；R-13/R-14 视时间决定是否本轮修。

## 8 第二轮：矩阵结果、B14/B15 与重建

### 8.1 新构建

| 项 | 值 |
|---|---|
| bundle（最终冻结，待视觉重绑） | `072587a4690b6953bae9cae3a06eaaa815a38b13eee9b98dc5ce98e72a54824f`，3,011,162 B，mtime 2026-11-06 13:51:04 |
| 前冻结构建 | `ea9e5732…`（13:02:42）的 B14/B15 视觉复验已通过；`55e4e8ea…`/`8b210896…` 亦为前冻结对照；最终证据须重绑 `0c05b7d1` |
| CSS | `332f9fd3f3e759f5ef215dbe1ff9307e774d7f56b262d0ce9839d861e8609c4b`，48,202 B，mtime 11:48:17 |
| 构建 | 我的 `55e4e8ea` 构建 `flock /tmp/hc-client-release.lock -c 'npx shadow-cljs release :app'` EXIT=0；最终 `ea9e5732` 由 W5 在含 13:00–13:01 src 修复的树上重建，B14/B15 标记仍在 |
| 标记 | D-08 四标记各 1；`AbortController`=19；`session-scope`=2；`hc-markdown`=1；`hc-message-stream`=2；`hc-conversation-messages{overflow-x:hidden}`=1；`hc-table table{width:max-content`=1；`hc-miniapp-confirm-backdrop`=2；`hc-assistants`=5；`hc-sandbox-button-danger`=5；`绑定目标助手`/`最近确认`=2/2；`wb-nav-close`/`wb-nav-head`=1/1 |
| DOM 测试 | `test/js/verify_workbench_shell_dom.sh`：7 tests / 31 assertions，0 failures / 0 errors |

### 8.2 B14 抽屉关闭

- 现象：360 下抽屉打开后盖住 `#wb-menu`，点击中心落在抽屉上，只能 Escape 关闭。
- 修复：`views/shell.cljs` 在抽屉内新增 `#wb-nav-close`（仅 overlay 布局渲染），点击走 `close-overlay :nav`，焦点回到 `#wb-menu`；宽屏不渲染该按钮。新增 shell DOM 回归 1 条（含宽屏无该按钮）。
- 复验（ea9e5732 前冻结；072587a4 由 W29 交叉确认）：360/820 均有 `#wb-nav-close`，点击后 `navOpen=false`、焦点回 `#wb-menu`；点击右侧 backdrop 同样关闭（backdrop 在 55e4e8ea 前冻结复验；8b210896 上 close 按钮与焦点返回已复验）。截图 `r2-final-360-nav-open.png`、`r2-b14-tablet-820-nav-open.png`；DOM 测试 7/31 通过。

### 8.3 B15 窄屏宽度

- 根因：`.wb-conversation-mount` 是默认 `row` 的 flex 容器，会话页成为收缩项；消息流 `max-width:820px` 加内边距把整列撑到 840/852px，`.wb-main{overflow:hidden}` 裁掉右侧，发送按钮落在视口外。
- 修复：mount 改 `flex-direction:column`；mount/会话页/会话/输入区/输入行全链 `min-width:0`、`max-width:100%`；`.hc-message-stream` 改 `max-width:min(820px,100%)`；附件根容器改列 flex。
- 复验（ea9e5732/0c05b7d1 前冻结；072587a4 由 W29 交叉确认）：360/430/820/1024/1280 与 360 窄桌面暗色下，`conversation/composer` 均等于视口宽，`send.right` 分别为 350/420/804/748/944/350，均 ≤ 视口；`docScroll` 等于视口；1024 会话列从 260 提升到 374。

### 8.4 矩阵结果（旧构建 `dd968793` / CSS `dda62554`）

- 共 65 张截图、64 条 metrics（part1 27、part2 9、part3 14、part4 14）；四段均在 `flock` 内单 app + 单 Chrome，EXIT=0，无 pageerror。
- 已确认修复：首屏骨架 `Herdchestra / 正在打开本地数据…`；字号根锚点 80/100/150/200 时 `html` 为 12.8/16/24/32px，正文与标题同步；状态词不再出现 `:active`、13 位时间戳、`system`、`available`；`color-scheme` 浅/深正确；抽屉互斥（开导航关辅助，反之亦然）；抽屉焦点进入 `#wb-nav-drawer`、Escape 返回 `#wb-menu`；nav 地标 1；断线态可渲染。
- 新发现并已修：桌面 `#wb-menu` 仍 `display:flex`（`.wb-icon-button` 后声明覆盖），改为 `.wb-topbar .wb-menu-button` 提高特异性；暗色看板/助手/材料内容区为白色（`.wb-main/.wb-content` 补 token 背景）；1024 三栏下会话列仅 260px（1024–1199 收窄为 nav 200/aux 260、列表 min 190）；抽屉内容盒宽 241/346（补 `box-sizing:border-box`）。
- 仍未修：R-11 看板英文（W19）、R-12 无模型主操作（`conversation/surface.cljc`，非本文件范围）、R-16 服务端默认标题、R-18 消息详情英文 token（W17）；R-14 ARIA tabs 已在本轮补方向键/roving tabindex 并在 ea9e5732 复验。

### 8.5 最终证据（`072587a4` / CSS `332f9fd3`）

- 最终视觉证据（W29，绑 072587a4）：`plans/evidence/visual-audit/conversation/audit3-072587a4.json`、`shots3-072587a4/`；覆盖 360/820/1280、200%、暗色、B14/B15。本目录 13 张 `r2-final-*.png` + 2 JSON 取得于 `ea9e5732`/`0c05b7d1`，标“前冻结”对照。
- 构建沿革：13:00–13:01 的 `export.cljc`/`conversation/view.cljs`/`history/context.clj`/`app/system.clj` 改动使 `8b210896` 失效；`ea9e5732`（13:02:42）含这些修复；后续 W5 重建 `0c05b7d1`、`072587a4`，W35 final7 在 072587a4 上 3749/24310/0F/0E/380s，全量门禁条件 5 满足。
- 视口：360×800、430×932、820×1180、1280×800、360 宽桌面窄窗（暗色）；字号 100/200；1024×800 三栏；1280 暗色看板。
- 实测（072587a4）：360 `conversation/composer=360`，`send.right=350`；820 `=820`，`send.right=804`；1280 会话列 515，`send.right=944`；200% 时 1280 `send 90×65 right=944`、messages 81px、rowOverflow 0；360 200% `conversation 360×499`、`send 90×65 right=350`；docOverflow 均为 0。前冻结 0c05b7d1 的 430/1024/窄桌面数据为 420/748/350。
- B14：360/820 `#wb-nav-close` 可点，关闭后焦点回 `#wb-menu`；backdrop 右侧条同样可关闭。
- 桌面死菜单：1024/1280 `#wb-menu` computed `display:none`；820/360 仍 `flex`。
- 暗色内容区：1280 看板 `.wb-content` 背景 `rgb(17,22,28)`、文字 `rgb(238,244,249)`，无白色孤岛。
- ARIA tabs：1280 详情页签 `ArrowRight` 后选中/焦点从“详情”移到“文件”，roving tabindex 为 0/-1/-1/-1。
- 前冻结对照：`8b210896` 的 13 张、`55e4e8ea` 的 9 张与 W29 audit3 数据保留为“前冻结”；`dd968793` 的 65 张矩阵只作历史，不再作为最终证据。

### 8.6 最终残留

1. **820 触摸热区**：消息操作按钮实测 41px、版本按钮 v1/v2 32px，低于 44px；≤1023 的 CSS 44px 规则被 inline `minHeight 32` 压过，且非 ≤599 的 `!important` 分支。CSS 已冻结，未修。
2. **1280 消息可视高度**：100% 时上下文面板折叠 300px、展开 104px；200% 时仅 81px（send 90×65 可见、rowOverflow 0）。内容可滚动、发送/关闭可达，归 ugly/中，未改布局。
3. 原报告未修：看板英文（W19）、无模型空态主操作（`conversation/surface.cljc`）、服务端默认标题、消息详情英文 token（W17）。

## 9 final11 重绑（bundle `72de4f9a` / 源码树 `7dfcf0d7`（final12；原 ab801450 已前冻结），CSS `332f9fd3` 未变）

- 旧证据绑定关系：§8.5 的 072587a4 结论为 W29 产物，已是作废中间冻结；本目录 `r2-final-*.png`（13 张）绑 `ea9e5732`/`0c05b7d1` 前冻结；`r2-*.png`/`final-*.json`/phone-* 等 30+ 张绑 `dd968793`/`a8acf565`/`4652ef26` 旧构建。最终冻结为 `72de4f9a`（3,014,943 B）+ 树 `7dfcf0d7`。
- final11 复验（证据 `plans/evidence/visual-audit/final11/`，bundle `72de4f9a` + 树 `7dfcf0d7`，CSS `332f9fd3`）：会话区 360/820/1280、暗色、200% 及材料/看板/设置各截图已拍（`shots/` 11 张 + `final11-r4.json`）。实测：1280 conversation 515×605、messages 515/515、`docOverflow=0`、`sendRect.right=944`；820 conversation/messages=820、`sendRect.right=804`；360 conversation/messages=360、`sendRect.right=350`；暗色 body `rgb(17,22,28)`/`rgb(238,244,249)`；200%（localStorage `hc.workbench.v1` `font-scale=200` 后 reload）messages clientHeight 263→208、send 仍 60×44 右缘 944，bodyFont 仍报 15px（根字号变量未直接改 body，200% 生效以版面收缩为准，ugly 判定沿用 W26）。B14/B15 宽度链结论在新冻结上复验一致；820 41/32px 触摸热区残留未在本段量测，不宣称已修。
