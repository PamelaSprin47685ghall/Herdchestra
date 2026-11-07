# 会话 / 消息 / 历史真实视觉审查（0.0.1，第一段）

审查人：W17（visual-conversation）。日期：2026-11-06。
范围：`src/hc/client/conversation/**`、`src/hc/client/workbench/views/session.cljs`；旅程 2（日常对话）、3（长任务中的会话面）、5（历史与知识）。
依据：根 `AGENTS.md` §7.3、§7.4 旅程 2/3/5；`plans/client-audit-0.0.1.md`。

## 0. 结论摘要

- 第一段在真实 app + 真实 Chrome 中跑了 14 个状态/步骤，落 15 张截图；其中 10 个步骤成功，4 个失败（1 个功能失败、3 个因释放锁被中止）。
- 确认 **bug 8 项**、**ugly 6 项**；已在源码修复 11 项：
  - 已进冻结 bundle `5d36ac81` 的 6 项：Markdown、宽表滚动、操作栏/导出布局、压缩状态中文、深色主题桥接、图片缩略图/附件 chip。
  - **源码已改、尚未进 bundle** 的 5 项（按要求不重建）：B7 版本号 1 基、B8 上下文面板默认折叠/窄屏覆盖、B11 消息字号（根因：workbench.css 的 `.hc-message` 碰撞）、U5 发送按钮短标签、U6 消息列最大宽度居中。
- **未修/待诊断 3 项**：B9 重新生成 60s 未出 v2、B10 上下文“阅读 24 条”与可见 2 条不一致、Mermaid/公式无渲染器（功能缺口）；B14/B15 已在 `072587a4` 通过；U6 宽度跳变与 1280 会话列 515×605/消息区 300px 经 W26 判定为预期三栏/ugly 低；820 41/32px 为 R-08 未修残留；`ea9e5732` 的 200% 81px 经 W26 判定 ugly/中（非阻断）；`072587a4` 的 200% 已生效（bodyFont 30px）：1280 conversation 515×564、messages 81px、send 90×65，与 W26 判定一致；360 200% 用 W26 数据（conversation 360×499、send 90×65）。
- 第二段真实复验（2026-11-06 11:16–11:17，bundle `a8acf565`）新增 12 张截图：B7/B8/B11/U5（1280）通过，暗色/对比度通过；发现 **B12 消息区横向溢出**、**B13 会话头仍显示 `:active`**、**B14 360 抽屉打开后菜单按钮被遮住无法关闭**，并确认压缩状态 `reverted` 英文泄漏。
- 第三段（2026-11-06 11:26:33–11:26:50，bundle `dd968793`，已作废）9 张截图：B12/B13/已撤销/暗色 PASS；B14/B15 FAIL（详见 §14，旧 hash 证据保留）。
- 第四段（2026-11-06 12:06:31–12:06:40，`55e4e8ea`，前冻结）9 张截图：**B14/B15 PASS**（360/820 会话列/消息区/输入区宽=视口 360/820，`sendRect.right` 350/804；`#wb-nav-close` 44×44，`closedBy=nav-close`）；**B12/B13/已撤销/暗色 PASS**。1280 会话列 515×605 经 W26 判定为 nav224+list221+aux320 的预期三栏；300/104 为消息区高度（展开偏挤归 ugly/低）；820 41/32px 为 R-08 触摸热区残留，CSS `332f9fd3` 已冻结，下次构建修。13:00–13:01 `conversation/export.cljc` 与 `view.cljs`（487f0c0c → 94bed295）有源码改动，`8b210896` 不含；本节结论仅作前冻结对照，待新 bundle 重建后复验。
- bundle：最终冻结 `072587a4690b6953bae9cae3a06eaaa815a38b13eee9b98dc5ce98e72a54824f`（3,011,162 B，13:51:04；CSS `332f9fd3…`；源码树 `a97c68f7`）；`0c05b7d1`/`ea9e5732`/`8b210896`/`55e4e8ea`/`dd968793` 均作废（旧 audit3 标前冻结）。final7 单树全量 13:53:52–14:00:12 PASS（3749/24310/0F/0E/exit 0）。W29 已在 `072587a4` 复跑 audit3：B12/B13/B14/B15/已撤销/暗色 PASS；200% 生效（bodyFont 30px，1280 messages 81px）。详见 §18。
- `072587a4` 证据：`audit3-072587a4.json`、`shots3-072587a4/`（9 张）、`process-evidence3-072587a4.txt`、`audit3-run-072587a4.log`；`0c05b7d1`/`ea9e5732`/`55e4e8ea` 前冻结证据见对应 `-hash` 副本。
- 锁：`/tmp/hc-app-single.lock` 已释放；app/Chrome 已停止；无 W17 遗留进程。第一段因锁公平性在 11:05 主动结束，第二段截图按协调者通知再排队。

## 1. 环境与资源证据

| 项 | 值 | 证据 |
|---|---|---|
| app 命令 | `HC_CONFIG=/tmp/hc-visual-conversation/config.edn /tmp/pixi run clojure -M:server -m hc.server.app --host 127.0.0.1 --port 0 --data-dir /tmp/hc-visual-conversation/data --workspace-root /tmp/hc-visual-conversation/work --static-root resources/public` | `/tmp/hc-visual-conversation/process-evidence.txt` |
| app PID / URL | 257332 / `http://127.0.0.1:39249` | 同上 |
| app JVM | `-Xmx1g -XX:MaxDirectMemorySize=512m -XX:+ExitOnOutOfMemoryError` | `jcmd 257332 VM.flags`，同上 |
| app heap | committed 482304K，used 142957K（采样时） | `jcmd 257332 GC.heap_info`，同上 |
| app RSS | 1,095,512 KB（ps，采样时；含 JVM 映射，非堆占用） | 同上 |
| Chrome | Playwright 启动 `/usr/bin/google-chrome --headless`，主进程 258022，renderer 258116/258125/258127；RSS 约 227MB / 150MB / 75MB / 268MB | `/tmp/hc-visual-conversation/chrome-rss.log` |
| 模型 stub | Node `127.0.0.1:19160/v1`，OpenAI-compatible SSE；不访问外网 | `/tmp/hc-visual-conversation/stub.cjs`、`stub.log` |
| 锁 | 整段 `flock /tmp/hc-app-single.lock bash -c '/tmp/hc-visual-conversation/run-audit.sh'`；结束 kill app/stub，释放锁 | 释放后 `fuser -v` 无 W17 PID |
| bundle（前） | `67da1cceac3367858dc2bc5103e5b7daf94b082e69c020575a5dd8ea09e768b6` | 审查开始时 |
| bundle（后） | `5d36ac81757bee2101493288be1fdf8d9aca51dc36d54385410d49173ce3d0ae` | release 构建后，W9 冻结 |

说明：`ps RSS` 包含 JVM 映射与 DirectMemory，不能当成堆使用量；堆使用以 `jcmd GC.heap_info` 的 142,957K 为准。Chrome 只保留一个实例；采样日志里 225863 等 PID 属于其他 worker，不计入本段。

## 2. 截图与状态

目录：`plans/evidence/visual-audit/conversation/shots/`。

| 截图 | 状态 | 结果 |
|---|---|---|
| `01-empty-1280.png` | 空会话 1280×800 | 成功；空态、输入区、上下文面板 |
| `02-rich-1280.png` | 富文本助手消息 1280 | 成功；原始 Markdown、宽表、代码、公式、mermaid 回退 |
| `02b-rich-conversation-1280.png` | 会话区元素截图 | 成功；滚到底部后的可见区 |
| `03-rich-table-1280.png` | 表格滚入视口 | 成功；表格未横向滚动 |
| `05a-edit-open.png` | 编辑历史消息打开 | 成功 |
| `05b-edited-1280.png` | 保存编辑后 | 成功；`手动编辑 · 版本 1` |
| `06-context-panel.png` / `06b-context-panel-element.png` | 上下文面板 | 成功 |
| `07-compression-prep.png` | 压缩范围/摘要编辑 | 成功 |
| `08-compression-draft.png` | 压缩草稿待采用 | 成功；显示“压缩 draft：” |
| `09-compression-applied.png` | 使用摘要后 | 成功 |
| `10-compression-undone.png` | 撤销压缩后 | 成功 |
| `11-find.png` | 页内查找“配置” | 成功；2 个命中 |
| `12-branch.png` | 从此处创建分支 | 成功；来源横幅 |
| `FAIL-regenerate-version.png` | 重新生成等待 v2 超时 | 失败；60s 未出现 v2 |
| （未生成） | 流式/停止/返回最新、360/820/暗色/缩放/焦点/已删除区/对比度 | 因 11:05 释放锁中止 |

DOM/度量证据：`plans/evidence/visual-audit/conversation/audit.json`。

## 3. Bug 清单

| ID | 严重度 | 位置 | 现象与证据 | 修复 | 状态 |
|---|---|---|---|---|---|
| B1 | 高 | `conversation/view.cljs` `segment-block` `:text` | 助手正文原样露出 `## 配置迁移结论`、`**强调**`、`[链接](https://example.com)`、`- 列表`、`> 引用`。`audit.json` 的 `rich-markdown-source` 四项均为 true；违反旅程 2.6“标题/强调/列表/引用/链接”。 | 新增保守 Markdown 渲染：块级标题/列表/引用/分隔线/段落，行内粗体/斜体/行内代码/链接/图片；未识别标记保留原文。 | 已改，已编译进 `5d36ac81`；**视觉复验待第二段** |
| B2 | 高 | `.hc-table` | 20 列表格在 568px 容器内 `scrollWidth=568`、`max=0`，列被压窄换行，没有独立横向滚动；违反 AC-09-003。 | `.hc-table table{width:max-content;min-width:100%}`；单元格 `max-width:36rem`，长文本仍可换行。 | 已改；**复验待第二段** |
| B3 | 中 | `.hc-message-actions`、`.hc-export-controls` | 操作栏没有 flex/gap，按钮紧贴；“导出当前分支导出纯文本”两个按钮文字连在一起（`context-panel` 文本证据）；“选择”标签独占整行（248/444px）。 | 操作栏 flex + gap + 32px 最小高度；导出控件 flex + 分隔线；多选导出条样式。 | 已改；**复验待第二段** |
| B4 | 低 | `compression-row` | 压缩状态直接 `(name state)`，中文界面出现“压缩 draft：”“applied”等内部英文。 | 映射为“压缩 草稿/已采用/未知”。 | 已改；**复验待第二段** |
| B5 | 高 | `.hc-conversation` 全套 F7 变量 | 深色模式只设 `html[data-theme]`，F7 的暗色变量只在 `.dark` 类下生效；会话区仍用 `--f7-page-bg-color` 等浅色值，可能整块发白。静态代码确认；暗色截图未完成。 | 主题桥接：颜色/边框/背景优先用 `--wb-*`，保留 F7 回退；用户气泡用 `--wb-accent/--wb-accent-ink`。 | 已改；**暗色复验待第二段** |
| B6 | 中 | `.hc-message-out .hc-message-body` | 旧用户气泡 `#007aff` + 白字，15px 正文对比度约 4.02:1（`#0a84ff` 约 3.65:1），低于 4.5:1；`audit.json` 记录气泡字号 13.2px。 | 用户气泡改用 `--wb-accent`（浅色 `#0b5f8a`，白字 6.96:1；暗色 `#6cc4ee` + `#08131b` 9.62:1）。 | 已改；**对比度复验待第二段** |
| B7 | 中 | `message-item` 元信息 | `:current-version` 是 0 基索引，元信息直接打印：第一版显示“版本 0”，第二版显示“版本 1”；版本选择器却显示 `v1/v2`。编辑后实测 `助手 · 手动编辑 · 版本 1`，与 `v2` 矛盾。 | 已改为 1 基：`(if (number? version) (inc version) version)`；`v1` 对应“版本 1”。 | **源码已修，未进 `5d36ac81`**（等统一重建） |
| B8 | 高 | `context-panel` 位置 | 上下文面板 + 压缩/导出控件常驻消息区上方；1280×800 下 `messages.clientHeight=194`，一屏只能看约 194px 消息。面板内容默认折叠（`detailsOpen:[false]`），但外层壳仍占约 250px。 | 已改为 `<details>` 默认折叠（summary 显示“上下文 · 发送 N 条 · 摘要 M · 警告 N”）；≤599px 展开时 `position:fixed` 覆盖层（top 64px，max-height 70vh），收起后回流行内。 | **源码已修，未进 bundle**；可视高度复验待第二段 |
| B9 | 高 | 重新生成 | 点击“重新生成”后 60s 未出现 v2；`FAIL-regenerate-version.png`。可能原因：发送流尚未结束，重新生成被拒；或命令/回执路径失败。未拿到错误 banner 文本。 | 第二段在流结束后重试，读取 `.hc-message-status`/通知/命令状态；若仍失败转服务端命令路径。 | **未修/待诊断** |
| B10 | 中 | 上下文面板计数 | 面板显示“发送 2 条 · 摘要 0”“阅读 24 条 · 已删除 0”，而可见消息只有 2 条。`阅读 24` 的来源未解释，易让用户以为有 24 条历史。 | 第二段核对服务端 `:reading` 投影；若包含隐藏/过程记录，需在 UI 标注口径。 | **未修/待诊断** |
| B11 | 中 | 会话消息字号/盒子 | `audit.json` 记录助手气泡 `font-size:13.2px`，而 body 为 15px。根因：`resources/public/css/workbench.css:1244` 的全局 `.hc-message`（`font-size:.88em` + border/padding/background）撞上会话消息类名。 | 在 conversation CSS 内用 `.hc-conversation .hc-message` 重置 border/padding/background/font-size；不改 `workbench.css`，避免影响 materials。 | **源码已修，未进 bundle**；字号/盒子复验待第二段 |
| B12 | 高 | `.hc-conversation-messages` | 1280 下 `scrollWidth=1091`、`clientWidth=852`；200% 下 `1796/852`；80% 下 `950/852`。表格容器被内层 `width:max-content` 撑到 1047，整条会话可横向滚动，违反“整条会话绝不会被撑宽”。 | 源码：`.hc-conversation-messages{overflow-x:hidden}`；`.hc-message`/`.hc-message-body`/`.hc-table` 加 `max-width:100%;min-width:0`，把横向滚动限制在表格自身。 | **已复验 PASS（`dd968793` / `55e4e8ea`）**：`dd968793` 1280 messages 852/852、table 732/1047；`55e4e8ea` 1280 messages 515/515、table 455/1047，360/820/200% 均 messages=视口、table 内部滚动；docOverflow 0。 |
| B13 | 中 | 会话头状态 | `headerMeta` 实测 `模型：Visual Stub\n:active`；内部关键字直接出现在头部。W21 已确认同一点。 | 源码：`session-status-label` 隐藏 `active/idle`，归档/删除/临时显示中文。 | **已复验 PASS（`dd968793` / `55e4e8ea`）**：`headerMeta` 仅 `模型：Visual Stub`，无 `:active`。 |
| B14 | 高 | 360 抽屉 | 360 下打开导航抽屉后，再点 `#wb-menu` 关闭时 30s 超时；日志显示 `<div class="wb-nav-section">` 拦截点击。抽屉/遮罩盖住了顶栏菜单按钮。`composerZ` 实测仍为 60（W21 的 z-index 修复未进 bundle）。 | 属于 `shell.cljs`/`workbench.css` 范围：顶栏或关闭按钮需高于遮罩/抽屉；W21 处理。 | **已复验 PASS（`55e4e8ea`）**：抽屉内 `#wb-nav-close` 44×44 可见（x171,y66），`closedBy=nav-close`，关闭后 `navOpen=false`、`aria-expanded=false`；`dd968793` 上曾 FAIL（仅 Escape）。 |
| B15 | 高 | `.wb-main` / `.wb-session-layout` / `.hc-conversation` 宽度链 | 360 下 `conversation=840`、`messages.clientWidth=840`（视口 360）；820 下 `conversation=852`、`messages=852`（视口 820）。`docOverflow=0` 是 `.wb-main{overflow:hidden}` 把超出部分裁掉，不是布局收缩。360 截图正文暗像素到达 x=359，右侧被切；发送按钮在 840 宽输入行末端，视口内不可见（按 flex 布局推断，未单独量 x）。 | 未修。会话侧建议 `.hc-conversation-messages{min-width:0}` + `.hc-message-stream{max-width:min(820px,100%)}`；shell 侧需让 `.wb-body`/`.wb-session-layout`/`.wb-conversation`/`.wb-conversation-mount` 的 `min-width:0` 链真正生效，并确认 ≤1023/≤599 媒体查询命中；不能只靠 `.wb-main` 裁切。 | **已复验 PASS（`55e4e8ea`）**：360/820 conversation/messages/composer 宽=视口（360/820）；`sendRect.right` 350/804 ≤ 视口；360 截图无右侧裁切（暗像素 max x=349）。`dd968793` 上 FAIL（840/852 裁切）。 |

## 4. Ugly 清单

| ID | 位置 | 现象 | 修复 | 状态 |
|---|---|---|---|---|
| U1 | 附件卡 | 图片附件只有名称/大小，没有缩略图；违反旅程 2.2“图片给缩略图”。 | `image/*` 且有 `:url` 时渲染最大 160px 缩略图，`alt` 带名称。 | 已改；未在真实附件流程复验 |
| U2 | `.hc-attachments .hc-chip` | `justify-content:space-between` 把名称、大小、状态、按钮分散到两端，多个字段时很散。 | 改为 `flex-start` + `flex-wrap`。 | 已改 |
| U3 | `.hc-round-context` / `.hc-round-section` | 本轮上下文详情没有样式，纯文本堆叠；`details` 默认三角与密集行。 | 新增分节左边线、浅底、字号、warning 色。 | 已改；面板默认折叠，展开复验待第二段 |
| U4 | `.hc-message-more` | “显示更早消息”按钮没有居中/间距。 | flex 居中 + 8px 上下留白。 | 已改 |
| U5 | 输入区发送按钮 | 流式期间标签“发送（需先停止当前轮）”使按钮宽 195px，1280 下输入框被压到 317px；360 下可能溢出。 | 已改：`send-label` 固定“发送”，禁用原因保留在 hint 与按钮 `title`；360 复验待第二段。 | 源码已修，未进 bundle |
| U6 | 空会话布局 | 同一 1280 视口，空会话 `conversation=368px`，有消息后 `conversation=650px`；相差约 282px，疑似辅助面板/首屏布局切换。 | 已加 `.hc-message-stream{max-width:820px;margin:0 auto}` 统一阅读列；容器 368→650 的根因仍未定位，第二段记录 `.wb-body`/`.wb-aux` 状态。 | 部分修复（阅读列），根因待诊断 |
| U7 | 版本/元信息 | 见 B7；元信息与选择器编号体系不一致，属于“看起来像 bug”的文案。 | 同 B7。 | 源码已修，未进 bundle |

## 5. 未覆盖 / 未修（诚实清单）

1. **流式与停止**：生成中、正在停止、已停止、迟到文本、返回最新内容、选区保护、草稿——未截图（第一段在 long-stream 前被中止）。
2. **视口**：`55e4e8ea` 已完成 360×800、820×1180、1280 暗色与 200% 字号；B15 修复，360/820 通过。1280 会话列 515×605、折叠消息区 300px、展开 104px，待 W26 判定是否为会话列表/页头的预期布局。80% 字号与焦点顺序沿用第二段结果（`39-zoom-200`/`40-zoom-80`/`focus-order`），未重跑。
3. **已删除区**：普通删除 → 已删除 → 恢复/永久删除，未截图。
4. **长会话按需渲染**：200 条窗口、显示更早消息、页内查找跨未渲染区，未在真实浏览器复验。
5. **Mermaid**：当前只作为带语言标签的代码块显示，没有缩放、原文/导出图片；仓库没有 Mermaid 依赖。属于功能缺口，不是小样式。
6. **公式**：当前只显示 TeX 原文 + 复制按钮，没有 KaTeX/MathJax 渲染；属于功能缺口。
7. **来源卡 / 生成卡 / 图片附件 / 搜索**：未在真实 UI 造出数据。
8. **重新生成 / 版本切换**：B9 未诊断；v2 选择器与来源/工具/用量同步未验证。
9. **完整客户端测试**：release 编译 EXIT=0；已跑 `verify_conversation_selection_dom.sh`（13 tests / 67 assertions / 0 failures）与 `clojure -X:test:client :dirs '["test/hc/client/conversation"]'`（67 tests / 358 assertions / 0 failures）。未跑 `test/hc/client` 全量；由主代理统一验证。
10. **根因未定**：B10 计数、U6 容器宽度跳变。

## 6. 第二段复验计划（严格 ≤5 分钟，单段单区）

1. `flock -w 300 /tmp/hc-app-single.lock` 启动 app + stub，复用 `/tmp/hc-visual-conversation/data`；记录 PID/RSS/jcmd。
2. 打开已有富文本会话，`scrollTop=0`，截“消息顶部”（B1/B2/B3 修复后）。
3. 展开上下文面板，截“展开态”（U3/B8）；记录 `messages.clientHeight`。
4. 360×800、820×1180、暗色、字号 80%/200% 各一张；记录横向溢出、按钮热区、对比度。
5. 新建会话发“慢速长回复”，截生成中/停止中/已停止/返回最新；验证迟到文本不追加。
6. 页内查找、已删除区、焦点顺序各一张。
7. 结束立即 kill app/stub/Chrome，`fuser` 确认锁释放，记录新截图与 bundle hash（不重建）。
8. 若必须改 B7/B8，先向 W9 公布新 hash 并确认 D-08 四个标记仍在；否则只报告。

## 7. 改动文件

- `src/hc/client/conversation/view.cljs`
  - `css` 字符串：新增 workbench 主题桥接、操作栏/导出/内联编辑/上下文分节/附件缩略图/宽表/行内 Markdown 样式。
  - 新增 `inline-markdown-pattern`、`inline-nodes`、`markdown-block-start?`、`block-markdown?`、`markdown-blocks`、`block-nodes`。
  - `segment-block` 的 `:text` 分支改为 Markdown 渲染，纯文本仍走单 `<p class="hc-text">`（保持选区测试的 DOM 形状）。
  - `attachment-list` 增加图片缩略图。
  - `compression-row` 状态中文化。
  - 第二轮：`message-item` 版本号 1 基；`context-panel` 改为默认折叠 `<details>` + 窄屏覆盖层；`.hc-conversation .hc-message` 碰撞重置；消息列 `.hc-message-stream` 最大 820px 居中；发送按钮 `title` 带禁用原因。
- `src/hc/client/conversation/composer.cljc`
  - `send-label` 固定为“发送”，不再使用长括号标签挤压窄屏输入区。
- 未改 `timeline.cljc`、`session.cljs`、`workbench.css`、根 `AGENTS.md`；未 git 写。

## 8. 证据路径

- 截图：`plans/evidence/visual-audit/conversation/shots/`
- 步骤/度量：`plans/evidence/visual-audit/conversation/audit.json`
- 运行日志：`/tmp/hc-visual-conversation/audit-run.log`
- 进程/JVM：`/tmp/hc-visual-conversation/process-evidence.txt`
- Chrome RSS：`/tmp/hc-visual-conversation/chrome-rss.log`
- stub 日志：`/tmp/hc-visual-conversation/stub.log`
- 配置：`/tmp/hc-visual-conversation/config.edn`

## 9. 复验后必须回答的问题

- B1 修复后，`##`/`**`/链接是否不再露出，且未识别标记仍保留原文？
- B2 修复后，20 列表格 `scrollWidth > clientWidth` 且正文不被撑宽？
- B5 暗色下会话区背景/文字/输入框是否与 shell 一致，无白底白字？
- B6 用户气泡对比度是否 ≥4.5:1（13.2px 正文按普通文本要求）？
- B8 上下文面板折叠后，消息区可视高度是否恢复到可用范围（目标 > 350px @800 高）？
- B9 流结束后重新生成是否出现 v2，且版本切换不触发新请求？
- B7 第一版是否显示“版本 1”且与 `v1` 一致，第二版“版本 2”与 `v2` 一致？
- B11 助手气泡是否回到 15px，且消息不再出现 workbench 的额外边框/内边距？
- U5 360px 下发送按钮是否不再挤压输入框，禁用原因是否仍可见（hint/title）？
- U6 消息列在 1280 下是否居中且不超过 820px；368→650 的容器跳变是否仍出现？

## 10. 第二轮离线修复（2026-11-06 11:20，源码，未重建）

按用户要求，E2E 使用冻结 bundle 期间不重建；以下改动只在源码，等协调者统一重建后再做视觉复验。

| 项 | 改动 | 测试/证据 |
|---|---|---|
| B7 | `message-item` 版本号改为 1 基 | 代码审阅；conversation 测试 67/358 |
| B8 | 上下文面板 `<details>` 默认折叠；≤599px 展开为 fixed 覆盖层 | selection DOM 13/67（面板 textContent 仍含全部字段） |
| B11 | 根因 `workbench.css` 全局 `.hc-message` 碰撞；会话内 scoped 重置 | 代码审阅；未改 workbench.css |
| U5 | `send-label` 固定“发送”；按钮 `title` 带禁用原因 | conversation 测试 67/358 |
| U6 | `.hc-message-stream` 最大 820px、居中 | selection DOM 13/67（消息节点仍可查询） |

构建/测试记录：

| 命令 | 结果 |
|---|---|
| `npx shadow-cljs release :app`（第二轮前） | EXIT=0；bundle `5d36ac81`；未再重建 |
| `test/js/verify_conversation_selection_dom.sh` | 13 tests / 67 assertions / 0 failures |
| `clojure -X:test:client :dirs '["test/hc/client/conversation"]'` | 67 tests / 358 assertions / 0 failures |

未重建原因：W9 已冻结 `5d36ac81` 供 E2E13/D-08 交叉验证；本段不公布新 hash，等协调者统一重建后再复验。

## 11. 第二段真实复验（bundle `a8acf565`，12 张截图，2026-11-06 11:16–11:17）

环境：app PID 307203，`-Xmx768m -XX:MaxDirectMemorySize=512m`（`MaxHeapSize=805306368`），采样 RSS 约 1.04GB，堆 used 188,455K；Chrome 主进程 308273，renderer 308261 RSS 约 152MB/151MB；CSS `dda62554c9bb0b2643c0c437a1368f56b021f716001bcefa3f5cf92a32fa44ca`。整段 104 秒，结束即停 app/stub/Chrome 并释放锁。

| 项 | 结果 | 证据 |
|---|---|---|
| B7 版本号 | **PASS**：v1 → `助手 · 版本 1`；v2 → `助手 · 手动编辑 · 版本 2` | `audit2.json` `version-labels`；`34-version-meta.png` |
| B8 上下文面板 | **PASS**：默认折叠时消息区 426px；展开后 230px；summary 显示“发送 2 条 · 摘要 0” | `30-rich-top-1280.png`、`32-context-collapsed.png`、`33-context-open.png` |
| B11 字号/盒子 | **PASS**：`.hc-message` border 0 / padding 0 / 透明底 / 15px；正文 15px；对比度 14.49:1 | `audit2.json` metrics/contrast |
| U5 发送标签 | **PASS（1280）**：按钮文字“发送”，宽 60px；输入框 684px。360 截图 `35-360.png` 已落，但该步在关闭抽屉时超时，360 度量未写入 | `30-rich-top-1280.png`、`35-360.png` |
| Markdown | **PASS**：5 个标题、1 个列表、1 个引用、1 个链接、1 个 strong；无 `##`/`**`/`](` 原文 | `30-rich-top-1280.png`；`markdown` 度量 |
| 暗色 | **PASS**：body 背景 `rgb(32,43,54)`、文字 `rgb(238,244,249)`；会话区不再发白 | `38-dark-1280.png`；`dark-1280` 度量 |
| 对比度 | **PASS**：助手 14.49:1、用户气泡 6.96:1、meta 6.42:1 | `contrast` 度量 |
| 200% / 80% | 字号 30px / 12px；页面无横向溢出，输入行无溢出；但消息区横向溢出 1796/852、950/852（B12） | `39-zoom-200.png`、`40-zoom-80.png` |
| B12 横向溢出 | **FAIL**：1280 下消息区 1091/852；表格容器被撑到 1047；违反“整条会话不会被撑宽” | `30-rich-top-1280.png`；`messages`/`table` 度量 |
| B13 头部状态 | **FAIL**：`headerMeta` 仍为 `模型：Visual Stub\n:active` | `30-rich-top-1280.png`；`headerMeta` |
| B14 360 抽屉 | **FAIL**：抽屉打开后点 `#wb-menu` 关闭超时 30s，`.wb-nav-section` 拦截点击；`composerZ` 仍为 60 | `36-360-drawer.png`、`FAIL-viewport-360.png` |
| 压缩状态 | **FAIL（已源码修）**：展开面板显示“压缩 reverted：”，`reverted` 英文泄漏 | `33-context-open.png`；`context-open` |
| 360 / 820 | 360 有 `35-360.png`、`36-360-drawer.png`；820 的 `37-820.png` 因抽屉未关闭而受污染，不作为干净 820 证据 | `shots2/` |

结论：B7/B8/B11/U5（1280）/Markdown/暗色/对比度已通过；B12/B13/B14 与 `reverted` 是最新源码已修或待 W21 修、但不在 `a8acf565` 的项。`4652ef26`（11:17:42）由 W21 重建，**不含**本文件 11:17:45 之后的 B12/`reverted` 修复；W21 明确要求会话视觉证据不绑定 `4652ef26`。下一次统一重建后需复验 B12/B13/B14 + 360 干净截图。


## 12. 最终重建与标记核对（2026-11-06 11:19:25）

注：W33 于 11:50:01 冻结 `55e4e8ea`（CSS `332f9fd3…`），`dd968793` 作废；本节的标记核对仅对 `dd968793` 有效，最终视觉结论见 §15。

- 命令：`flock /tmp/hc-client-release.lock -c 'npx shadow-cljs release :app'`，EXIT=0。
- `main.js`：`dd968793cc74919e139f31bb290a5dfb68309c6d9a78acfd766a4d984f2fda4b`，3,007,964 B，mtime 11:19:25。
- `workbench.css`：`dda62554c9bb0b2643c0c437a1368f56b021f716001bcefa3f5cf92a32fa44ca`（未变）。
- 已广播给 Main/W2/W3/W8/W9/W10/W14/W18/W19/W20/W21/W22/W23/W25 等 28 个 peer；在 W9 核对冻结前，其他人不得再重建。

| 标记 | 计数 | 说明 |
|---|---|---|
| `该会话的命令 epoch 尚未同步` | 1 | D-08 |
| `本会话可用` | 1 | D-08 |
| `select-skills` | 1 | D-08 |
| `当前会话实际可用` | 1 | D-08 |
| `AbortController` | 19 | speech |
| `session-scope` | 2 | 扩展会话清空相关投影 |
| `hc-markdown` | 1 | W17 Markdown |
| `hc-message-stream` | 2 | W17 消息列 |
| `hc-conversation-messages{overflow-x:hidden}` | 1 | W17 B12 |
| `hc-table table{width:max-content` | 1 | W17 宽表 |
| `已撤销` / `已归档` | 7 / 9 | W17 压缩/状态文案 |
| `hc-miniapp-confirm-backdrop` | 2 | W18 |
| `hc-assistants` | 5 | W18 |
| `hc-sandbox-button-danger` | 5 | W18 |
| `绑定目标助手` / `最近确认` | 2 / 2 | W18 |

W19 未提供独立字符串标记；本次构建基于 `4652ef26` 同一工作树，未回退其源码。

W24 只读校验（11:20 后）与上述 hash/size/CSS 完全一致；W9 核对后统一冻结。

## 13. 最终段脚本（已执行，结果见 §14）

- `audit3.cjs`：`697b8f28ef5ea78798f5ec35f98e0e3d501dff1b2415132e28f47c90a44e852f`
- `run-audit3.sh`：`9912c62192084fd26ea6cc8e6faaa5f672875037bebcc7d9e84350e0a65704cf`
- 最新 `view.cljs`：`487f0c0cc11a78f1fb5fc24520b14cfad677bbddc4ccd2a23462f551e877fe21`（已进 `dd968793`）
- 计划：视觉序列 W20→W25→W21 结束后，`flock -w 300 /tmp/hc-app-single.lock bash -c '/tmp/hc-visual-conversation/run-audit3.sh'`，≤3 分钟；复验 B12（表格内部滚动/页面不横向溢出）、B13（头部无 `:active`）、B14（360 抽屉可关闭 + 干净 360/820）、压缩“已撤销”、暗色。结束立即停 app/Chrome 并释放锁。
- 实际：2026-11-06 11:25 取得锁；11:26:33–11:26:50 执行 `audit3.cjs`，整段 91.5 秒；结束即停 app/stub/Chrome 并释放锁，未写 `src/**`/`test/**`、未重建。
- `55e4e8ea` 复跑：`audit3.cjs` `8ca4a76911936f9c04eaa2dc76af9801644aec5c6d94cf3c6d299004c5e90703`（新增 `#wb-nav-close` 关闭路径、`sendRect`/`conversationRect`/`composerRect`/`messagesRect` 度量）；`run-audit3.sh` 未变（`9912c621…`）。结果见 §15。

## 14. 第三段复验（bundle `dd968793`，已作废；9 张截图，2026-11-06 11:26:33–11:26:50）

W33 于 11:50:01 冻结 `55e4e8ea73279d58a0dbceba22ee8c815c2b24b2de600fd2e1f21e2eaa27c857`，`dd968793` 作废；本节保留为旧 hash 证据，最终结论见 §15。

环境：app PID 330074，`-Xmx768m -XX:MaxDirectMemorySize=512m`（`MaxHeapSize=805306368`），启动 RSS 943,628 KB、结束 RSS 966,608 KB，堆 used 245,845K → 280,863K；stub PID 330073；URL `http://127.0.0.1:40031`；Chrome 主进程 330493（Playwright，`--disable-dev-shm-usage`）。`main.js` `dd968793…`，`workbench.css` `dda62554…`；`process-evidence3.txt` 在 app 启动后立即记录这两个 hash，截图绑定该 hash。整段 91.5 秒；`pageErrors=[]`、`consoleErrors=[]`；结束即停 app/stub/Chrome，锁释放，无本段遗留进程。

说明：执行落在 W9 的 11:26–11:41 冻结窗口内，但只读复验冻结 bundle，未写 `src/**`/`test/**`、未重建、未跑 E2E；W13 串行全量测试不受本段文件写入影响（仍有约 90 秒的 app/Chrome 资源占用）。

截图：`plans/evidence/visual-audit/conversation/shots3-dd968793/`；度量：`plans/evidence/visual-audit/conversation/audit3-dd968793.json`；进程：`plans/evidence/visual-audit/conversation/process-evidence3-dd968793.txt`；日志：`plans/evidence/visual-audit/conversation/audit3-run-dd968793.log`。

| 步骤 | 结果 | 证据 |
|---|---|---|
| B12 1280 | **PASS**：messages `clientWidth=852`、`scrollWidth=852`、`overflowX=hidden`；`scrollLeft=80` 后仍为 0；table `clientWidth=732`、`scrollWidth=1047`、`overflowX=auto`，内部可滚 315px；`docOverflow=0` | `50-rich-top-1280.png`、`51-table-contained.png`；`select-rich-v1`/`b12-contain` |
| B12 200% | **PASS**：messages 852/852；table 732/1752，内部滚动；`docOverflow=0`；发送按钮 90×65 | `57-zoom-200.png`；`zoom-200` |
| B13 头部 | **PASS**：`headerMeta` = `模型：Visual Stub`；`hasActiveKeyword=false` | `50-rich-top-1280.png`；`b13-header` |
| 压缩“已撤销” | **PASS**：面板文本 `压缩 已撤销：`；`hasRevertedEnglish=false` | `52-context-open.png`；`context-open` |
| 暗色 | **PASS**：body 背景 `rgb(17,22,28)`、文字 `rgb(238,244,249)`；会话区主背景 `rgb(24,32,41)`（像素采样） | `58-dark-1280.png`；`dark` |
| 上下文折叠 | **PASS**：默认折叠，消息区 426px；展开 230px；再折叠 426px | `50-rich-top-1280.png`、`52-context-open.png`；`context-open`/`context-close` |
| 360 干净 | **FAIL（B15）**：`conversation=[840,735]`、messages `clientWidth=840`（视口 360）；`docOverflow=0` 是 `.wb-main{overflow:hidden}` 裁切；截图正文暗像素到达 x=359，右侧被切；发送按钮在 840 宽输入行末端，视口内不可见（按 flex 布局推断） | `53-360-clean.png`；`360-clean` |
| 360 抽屉 | **FAIL（B14）**：打开后 navZ 40 / backdropZ 30 / composerZ 20；`#wb-menu` 与 backdrop 中心两次点击均失败（上一段日志显示 `.wb-nav-section` 拦截）；`closedBy=escape`；关闭后 `navOpen=false`、`aria-expanded=false` | `54-360-drawer.png`、`55-360-closed.png`；`360-drawer` |
| 820 干净 | **FAIL（B15）**：`conversation=[852,1111]`、messages `clientWidth=852`（视口 820）；右侧裁切 32px；`docOverflow=0` | `56-820-clean.png`；`820-clean` |
| 360 触摸热区 | **PASS**：`smallTargets=[]`（会话内按钮均 ≥44） | `360-clean` 度量 |

结论：

1. **B12/B13/压缩“已撤销”/暗色在 `dd968793` 上通过**；B12 的修复在 200% 字号下仍成立，表格横向滚动被限制在表格自身。
2. **B14 仍失败**：360 下抽屉打开后，顶栏 `#wb-menu` 与 backdrop 中心两次点击均失败（上一段日志显示导航层拦截）；只有 Escape 能关闭。backdrop 右侧可见窄条未单独点击，不能算作主操作路径。需 W21 让顶栏/关闭按钮高于 nav，或在抽屉内提供可见关闭按钮。
3. **新发现 B15（高）**：360/820 下会话列宽保持 840/852，不随视口收缩；`.wb-main{overflow:hidden}` 只是裁切，不是响应式布局。360 下正文右侧被切、发送按钮在视口外；820 下右侧裁切 32px。属 shell/session 布局范围（W21），与 U6 的容器宽度跳变可能同源。下一次重建后必须复验 B14/B15。
4. 本段未改源码、未重建；不新增 bundle hash。B9/B10/U6 根因、流式/停止、已删除区、Mermaid/公式等仍未覆盖（见 §5）。

## 15. 前冻结复验（bundle `55e4e8ea`，9 张截图，2026-11-06 12:06:31–12:06:40）

W33 于 12:17:19 冻结 `8b210896`（3,009,689 B；CSS 仍 `332f9fd3`），`55e4e8ea` 作废。`8b210896` 构建时 `conversation/view.cljs` 为 487f0c0c…、`composer.cljc` b2e5ecf3…、`session.cljs` dabb7a43…、`shell.cljs` 139140c9…，与 `55e4e8ea` 相同；但 13:00–13:01 `conversation/export.cljc`（13:00:55）与 `conversation/view.cljs`（13:01:43，487f0c0c → 94bed295）已改，`8b210896` 不含。故本节证据仅作前冻结对照，B12/B14/B15/已撤销/暗色需在新 bundle 重建后复验。文件级注意：`audit3.json`/`shots3` 已被 13:24 的 ea9e5732 复跑覆盖；本节保留 55e4e8ea 的文字结论，文件证据以 §16 的 `-ea9e5732` 副本为准。

环境：app PID 429348，`-Xmx768m -XX:MaxDirectMemorySize=512m`（`MaxHeapSize=805306368`），启动 RSS 969,232 KB、结束 992,156 KB，堆 used 154,973K → 158,128K；stub PID 429347；URL `http://127.0.0.1:36699`；Chrome Playwright（`--disable-dev-shm-usage`）。`main.js` `55e4e8ea…`（3,008,937 B，11:50:01），`workbench.css` `332f9fd3…`；`process-evidence3.txt` 在 app 启动时记录这两个 hash，截图绑定该 hash。整段 580 秒（大部分为锁排队；audit 本身约 9 秒）；`pageErrors=[]`、`consoleErrors=[]`；结束即停 app/stub/Chrome，锁释放，无本段遗留进程。未写 `src/**`/`test/**`、未重建。

截图：`plans/evidence/visual-audit/conversation/shots3/`；度量：`plans/evidence/visual-audit/conversation/audit3.json`；进程：`plans/evidence/visual-audit/conversation/process-evidence3.txt`；日志：`plans/evidence/visual-audit/conversation/audit3-run.log`。

| 步骤 | 结果 | 证据 |
|---|---|---|
| B12 1280 | **PASS**：messages `clientWidth=515`、`scrollWidth=515`、`overflowX=hidden`；table `clientWidth=455`、`scrollWidth=1047`、`overflowX=auto`，内部可滚 592px；`docOverflow=0` | `50-rich-top-1280.png`、`51-table-contained.png`；`select-rich-v1`/`b12-contain` |
| B12 360/820/200% | **PASS**：360 messages 360/360、table 312/1047；820 messages 820/820、table 732/1047；200% messages 515/515、table 455/1047；均内部滚动，`docOverflow=0` | `53-360-clean.png`、`56-820-clean.png`、`57-zoom-200.png`；`360-clean`/`820-clean`/`zoom-200` |
| B13 头部 | **PASS**：`headerMeta` = `模型：Visual Stub`；`hasActiveKeyword=false` | `50-rich-top-1280.png`；`b13-header` |
| 压缩“已撤销” | **PASS**：面板文本 `压缩 已撤销：`；`hasRevertedEnglish=false` | `52-context-open.png`；`context-open` |
| 暗色 | **PASS**：body 背景 `rgb(17,22,28)`、文字 `rgb(238,244,249)`；会话区主背景 `rgb(17,22,28)`/`rgb(24,32,41)`（像素采样） | `58-dark-1280.png`；`dark` |
| 上下文折叠 | **PASS（功能）**：默认折叠 300px；展开 104px；再折叠 300px | `50-rich-top-1280.png`、`52-context-open.png`；`context-open`/`context-close` |
| B14 360 抽屉 | **PASS**：`#wb-nav-close` 在抽屉内 44×44、可见（x171,y66）；`closedBy=nav-close`；关闭后 `navOpen=false`、`aria-expanded=false` | `54-360-drawer.png`、`55-360-closed.png`；`360-drawer` |
| B15 360 | **PASS**：conversation/composer/messages 宽均 360（`right=360`）；`sendRect` x290、right350、60×44；截图无右侧裁切（暗像素 max x=349） | `53-360-clean.png`；`360-clean` |
| B15 820 | **PASS**：conversation/composer/messages 宽均 820（`right=820`）；`sendRect` right804、60×44 | `56-820-clean.png`；`820-clean` |
| 1280 布局观察 | **W26 判定（非阻断）**：515=nav224+list221+aux320 的预期三栏；`body.width 483`、表格 457 可读；300/104 是消息区高度（上下文面板折叠/展开），展开偏挤但用户主动且可滚动，归 ugly/低，不判 B15 回归 | `50-rich-top-1280.png`；`select-rich-v1`/`context-open` |
| 820 触摸热区 | **W26 判定（R-08 残留）**：41px 消息操作、32px v1/v2 是真实触摸热区残留；≤1023 的 44px 规则被 inline `minHeight 32` 压过，且非 ≤599 `!important` 分支；CSS `332f9fd3` 已冻结，本轮不改，上报 W33/W9，下次 CSS 构建补 `!important` 或移除 inline 32 | `820-clean` 度量 |

结论：

1. **B12/B13/B14/B15/压缩“已撤销”/暗色在 `55e4e8ea` 上全部 PASS**。B14 由抽屉内 `#wb-nav-close`（44×44）修复；B15 由 `.wb-conversation-mount` 系列 `min-width:0`/`max-width` 规则修复；360/820 的会话列、消息区、输入区均与视口同宽，发送按钮 right 350/804 在视口内。
2. **1280 观察（W26 判定）**：515=nav224+list221+aux320 的预期三栏；300/104 是消息区高度（上下文面板折叠/展开），展开偏挤但用户主动且可滚动，归 ugly/低，不阻断，不判 B15 回归。
3. **820 触摸热区（W26 判定）**：41px 消息操作、32px v1/v2 是 R-08 真实残留（inline `minHeight 32` 压过 ≤1023 的 44px 规则，且非 ≤599 `!important` 分支）；CSS `332f9fd3` 已冻结，本轮不改，上报 W33/W9，下次 CSS 构建修。
4. 本段未改源码、未重建；证据绑定 `55e4e8ea`（前冻结），`8b210896` 亦不含 13:00–13:01 的 `view.cljs`/`export.cljc` 改动；会话视觉结论待新 bundle 复验。`dd968793` 旧证据保留在 `*-dd968793`。B9/B10/U6 根因、流式/停止、已删除区、Mermaid/公式等仍未覆盖（见 §5）。

## 16. 前冻结复验（bundle `ea9e5732`，9 张截图，2026-11-06 13:24:13–13:24:22）

W33 于 13:37:25 冻结 `0c05b7d1`，`ea9e5732` 作废；本节标前冻结，最终复验见 §17。

环境：app PID 765566，`-Xmx768m -XX:MaxDirectMemorySize=512m`（`MaxHeapSize=805306368`），启动 RSS 1,003,780 KB、结束 1,019,652 KB，堆 used 140,082K → 338,247K；stub PID 765565；URL `http://127.0.0.1:45879`；`main.js` `ea9e5732…`（3,010,646 B，13:02:42），`workbench.css` `332f9fd3…`；audit 24.7 秒；`pageErrors=[]`、`consoleErrors=[]`；结束即停 app/stub/Chrome，锁释放，无本段遗留进程。未写 `src/**`/`test/**`、未重建。

证据：`audit3-ea9e5732.json`、`shots3-ea9e5732/`（9 张）、`process-evidence3-ea9e5732.txt`、`audit3-run-ea9e5732.log`。

| 项 | 结果 |
|---|---|
| B12 | **PASS**：1280 messages 515/515、table 455/1047 内部滚 592；360 360/360、table 312/1047；820 820/820、table 732/1047；200% 515/515、table 455/1752；`docOverflow=0` |
| B13 | **PASS**：`headerMeta` 仅“模型：Visual Stub” |
| B14 | **PASS**：`#wb-nav-close` 44×44 @x171,y66，`closedBy=nav-close`，`navOpen=false`；W26 补充焦点回 `wb-menu`、顶栏 z50 > 抽屉 z40 |
| B15 | **PASS**：360/820 conversation/composer/messages 宽=视口；`sendRect.right` 350/804；W26 补充 430/1024/1280/窄桌面暗色 `send.right` 420/748/944/350，`docScroll`=视口 |
| 已撤销 | **PASS**：“压缩 已撤销：”，无 `reverted` |
| 暗色 | **PASS**：body `rgb(17,22,28)` / `rgb(238,244,249)`；会话区主背景深色 |
| 200% | **W26 判定（ugly/中，非阻断）**：conversation 515×564，messages 81px；内容仍可滚动、send 90×65 与关闭均可见、`rowOverflow=0`，无功能丢失；但 81px 约两行，可读性差，下次布局调整压缩 200% 附件/搜索行或收紧 composer |
| 1280 | 515×605，messages 300/104（W26 判定预期三栏/ugly 低） |
| 820 触摸 | 41/32px R-08 残留（W26 判定，待下次 CSS 构建修） |

结论：B12/B13/B14/B15/已撤销/暗色在 `ea9e5732` 上 PASS；W26 的 360/430/820/1024/1280/窄桌面暗色数据与本节一致；200% 消息区 81px 经 W26 判定为 ugly/中（非阻断），记录待下次布局调整；未改源码、未重建。

## 17. 前冻结复验（bundle `0c05b7d1`，9 张截图，2026-11-06 13:46:10–13:46:19）

W33 于 13:51:04 冻结 `072587a4`，`0c05b7d1` 作废；本节标前冻结，最终复验见 §18。

环境：app PID 829589，`-Xmx768m -XX:MaxDirectMemorySize=512m`（`MaxHeapSize=805306368`），启动 RSS 970,456 KB、结束 988,956 KB，堆 used 151,353K → 148,776K；stub PID 829588；URL `http://127.0.0.1:45829`；`main.js` `0c05b7d1…`（3,011,096 B，13:37:25），`workbench.css` `332f9fd3…`；audit 25.9 秒；`pageErrors=[]`、`consoleErrors=[]`；结束即停 app/stub/Chrome，锁释放，无本段遗留进程。未写 `src/**`/`test/**`、未重建。

证据：`audit3-0c05b7d1.json`、`shots3-0c05b7d1/`（9 张）、`process-evidence3-0c05b7d1.txt`、`audit3-run-0c05b7d1.log`。

| 项 | 结果 |
|---|---|
| B12 | **PASS**：1280 messages 515/515、table 455/1047 内部滚 592；360 360/360、table 312/1047；820 820/820、table 732/1047；`docOverflow=0` |
| B13 | **PASS**：`headerMeta` 仅“模型：Visual Stub” |
| B14 | **PASS**：`#wb-nav-close` 44×44 @x171,y66，`closedBy=nav-close`，`navOpen=false` |
| B15 | **PASS**：360/820 conversation/composer/messages 宽=视口；`sendRect.right` 350/804 |
| 已撤销 | **PASS**：“压缩 已撤销：”，无 `reverted` |
| 暗色 | **PASS**：body `rgb(17,22,28)` / `rgb(238,244,249)` |
| 200% | **W26 有效数据（audit3 脚本失效）**：audit3 在加载后才设 `--wb-font-scale`，根字号不重算（`bodyFont=15px`、send 60×44、messages 300）；W26 预置 localStorage `hc.workbench.v1.settings.font-scale=200` 后加载，html 32px；360 conversation 360×499、send 90×65、docScroll=360。1280 messages 高度待 W26 补 |
| 1280/820 | 1280 515×605、messages 300/104；820 820×985、messages 668；W26 判定 1280 三栏预期、820 41/32px R-08 残留 |

结论：B12/B13/B14/B15/已撤销/暗色在 `0c05b7d1` 上 PASS；200% 由 W26 的预置 font-scale 方法验证（360 conversation 360×499、send 90×65、docScroll=360；1280 待补）；audit3 的加载后设 `--wb-font-scale` 无效；未改源码、未重建。

## 18. 最终冻结复验（bundle `072587a4`，9 张截图，2026-11-06 14:01:28–14:01:37）

环境：app PID 910161，`-Xmx768m -XX:MaxDirectMemorySize=512m`（`MaxHeapSize=805306368`），启动 RSS 953,680 KB、结束 974,872 KB，堆 used 151,496K → 150,031K；stub PID 910160；URL `http://127.0.0.1:38179`；`main.js` `072587a4…`（3,011,162 B，13:51:04），`workbench.css` `332f9fd3…`；audit 25.9 秒；`pageErrors=[]`、`consoleErrors=[]`；结束即停 app/stub/Chrome，锁释放，无本段遗留进程。未写 `src/**`/`test/**`、未重建。

证据：`audit3-072587a4.json`、`shots3-072587a4/`（9 张）、`process-evidence3-072587a4.txt`、`audit3-run-072587a4.log`。

| 项 | 结果 |
|---|---|
| B12 | **PASS**：1280 messages 515/515、table 455/1047 内部滚 592；360 360/360、table 312/1047；820 820/820、table 732/1047；200% 515/515、table 455/1752；`docOverflow=0` |
| B13 | **PASS**：`headerMeta` 仅“模型：Visual Stub” |
| B14 | **PASS**：`#wb-nav-close` 44×44 @x171,y66，`closedBy=nav-close`，`navOpen=false` |
| B15 | **PASS**：360/820 conversation/composer/messages 宽=视口；`sendRect.right` 350/804 |
| 已撤销 | **PASS**：“压缩 已撤销：”，无 `reverted` |
| 暗色 | **PASS**：body `rgb(17,22,28)` / `rgb(238,244,249)` |
| 200% | **生效（ugly/中）**：`bodyFont=30px`、conversation 515×564、messages 81px、send 90×65、`rowOverflow=0`；1280 81px 与 W26 判定一致；360 200% 用 W26 数据（conversation 360×499、send 90×65） |
| 1280/820 | 1280 515×605、messages 300/104；820 820×985、messages 668；W26 判定 1280 三栏预期、820 41/32px R-08 残留 |

结论：B12/B13/B14/B15/已撤销/暗色在 `072587a4` 上 PASS；200% 布局生效（bodyFont 30px），1280 messages 81px 与 W26 的 ugly/中判定一致；未改源码、未重建。

## 19. final11 重绑（bundle `72de4f9a` / 源码树 `7dfcf0d7`（final12；原 ab801450 已前冻结），CSS `332f9fd3` 未变）

- §18 的 `072587a4` 已被作废（中间冻结）；§14–§17 的 `dd968793`/`55e4e8ea`/`ea9e5732`/`0c05b7d1` 均为前冻结对照，证据文件保留在 `shots3-*`/`shots2`/`shots`。
- final11 复验（证据 `plans/evidence/visual-audit/final11/`，bundle `72de4f9a` + 树 `7dfcf0d7`，CSS `332f9fd3`）：会话区 360/820/1280、暗色、200% 已拍（`shots/10–16` 共 7 张 + `final11-r4.json`）。B12 宽度链复验：1280/820/360 `messages.clientWidth==scrollWidth==视口宽`、`overflowX:hidden`、`docOverflow=0`；B15：360/820 conversation/composer/messages 宽=视口、`sendRect.right` 350/804；1280 会话列 515×605（预期三栏）；暗色 body `rgb(17,22,28)`/`rgb(238,244,249)`。B13 头部 meta、B14 抽屉关闭、已撤销文案、真 200%（localStorage font-scale=200，messages 高度 263→208）本段未逐项断言，不宣称 PASS，仅宽度/溢出/暗色底色实测一致。
