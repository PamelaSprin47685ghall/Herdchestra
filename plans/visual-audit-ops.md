# 备份/恢复、语音、定时、诊断 视觉审查（0.0.1）

> 状态：**进行中（1280 浅色已拍，360/820/深色/200% 待补）**。已完成：四区域源码通读、既有截图复核、静态类名/CSS 对照、修复补丁、11 张 1280 浅色实时截图。F 段在 2:40 时因 360 诊断定位卡住，为释放锁主动停止；截图已保留。本文只把真正拍过的页面写成“已拍”，其余明确标“待拍/待复验”。

## 1 环境与基线

| 项 | 值 |
|---|---|
| 仓库 | `/home/kunweiz/Desktop/vibe/Herdchestra` |
| 客户端 bundle | `resources/public/js/main.js` |
| bundle SHA-256 | `dd968793cc74919e139f31bb290a5dfb68309c6d9a78acfd766a4d984f2fda4b`（2026-11-06 11:19:25，3,007,964 B；W17 最终重建，含本文全部修复与 W9 的 4 个标记） |
| 审查范围 | 设置→数据中心：用量/费用/日志/诊断包/反馈；加密备份与恢复（创建/恢复预览/本地恢复点/目标与状态）；语音输入、朗读、独立翻译；定时任务列表/表单/详情/运行记录 |
| 视口计划 | 1280×900、820×1180、360×800；浅色/深色（显式 `data-theme` 与系统深色）；字号 100%/200% |
| 运行时约束 | app JVM `-Xmx768m -XX:MaxDirectMemorySize=512m`（或 deps 默认 1g/512m）；同一时刻 1 app + 1 Chrome；整段 `flock /tmp/hc-app-single.lock`；结束即停 app/Chrome 并检查 PPID=1 |
| 当前锁状态 | 已按协调者要求停止排队；未持有 `/tmp/hc-app-single.lock` |

### 1.1 bundle 状态与构建阻塞（必须知道）

`dd968793` 构建于 11:19:25，已用 grep 复核包含本文全部修复（`hc-diag-facts`、`hc-diag-check`、`hc-diag-span`、`hc-backup-section-heading`、`hc-schedule-detail__header`、数据中心标题文案、时区校验文案、可读测试结果）。W9 的 4 个标记各出现 1 次。

W9 已确认 `dd968793` 为最终冻结客户端 hash（11:19:25，3,007,964 B；CSS `dda62554…`），D-08 四标记各 1，W17/W18/speech 标记在。`shell.cljs` 的未闭合括号已由 W21 修复，W17 随后完成最终重建 `dd968793`。本区域四个目录最后修改时间为 11:04–11:11，均早于构建；11 张 1280 截图在 app 于 11:19:31 启动后拍摄，服务端从磁盘读取的是 `dd968793`，因此证据绑定最终 hash。

## 2 页面与证据清单

| 页面 | 已拍证据 | 实时截图 | 结论 |
|---|---|---|---|
| 设置→数据中心顶部 | `plans/evidence/visual-audit/ops/01-datacenter-top-before.png` | 有（修复前） | 修复前显示“首次配置”清单 + “数据中心”占位段 + 供应商页脚，和下方“统计与诊断”重复；已改源码 |
| 统计与诊断：用量与费用 | `02-diag-stats-before.png`、`03-diag-stats-mid-before.png`、`f-01-diag-stats-1280.png` | 有（1280 浅色） | 三口径卡片/筛选/空态存在；修复后事实列表/筛选栏已复验 |
| 详细日志 | `f-02-diag-logs-1280.png` | 有（1280 浅色） | 策略/保留/清空可见；五个动作按钮未按状态禁用 |
| 诊断包 | `f-03-diag-bundle-1280.png` | 有（1280 浅色） | 请求表单、预览入口、导出可见；长表单密度待 360/200% 复验 |
| 反馈草稿 | 无 | 待拍 | 源码有复制/保存/邮件入口；“打开邮件草稿”原先无按钮样式，已修 |
| 备份：创建备份 | `f-04-backup-create-1280.png` | 有（1280 浅色） | 目标摘要、10 类勾选、选项、密码区可见；自动刷新“备份状态已更新”信息条是噪音（U-07） |
| 备份：恢复与预览 | `f-05-backup-restore-1280.png`、`f-04b-backup-result-1280.png`、`f-05b-backup-verified-1280.png` | 有（1280 浅色） | 备份已真实创建并通过校验；列表/密码/合并替换/类别可见 |
| 备份：本地恢复点 | 无 | 待拍 | 源码有 7+4+3 清理、回退、离线命令、空间/恢复点错误态 |
| 备份：目标与状态 | 无 | 待拍 | 原先用 `<pre>` 输出 storage map；已改为“路径/已用/可用”事实，并增加最近成功备份 |
| 语音与朗读：语音输入 | `f-06-speech-empty-1280.png`、`f-07-speech-form-1280.png` | 有（1280 浅色） | 空态与添加表单可见；服务卡/测试结果需保存服务后复验 |
| 语音与朗读：朗读 | 无 | 待拍 | 源码有朗读路径/音色/格式；播放器有暂停/继续/停止/速度/下载 |
| 语音与朗读：翻译 | 无 | 待拍 | 源码有翻译路径；独立翻译页有原文/译文分离、选区替换、插入光标、撤销 |
| 定时任务：列表/空态 | `f-08-schedule-empty-1280.png` | 有（1280 浅色） | 空态、示例、新建入口可见；平台/同步/策略三块提示占首屏 |
| 定时任务：新建/编辑 | `f-09-schedule-form-1280.png` | 有（1280 浅色） | 名称/提示词/助手/模型/执行方式/重复规则/时区/预览/保存方式可见 |
| 定时任务：详情/运行记录 | 无 | 待拍 | 源码有运行记录、当前运行、停止/删除确认、时区变更提示、结果文件 |

## 3 Bug 清单

| ID | 严重度 | 位置 | 现象 | 证据 | 修复 | 状态 |
|---|---|---|---|---|---|---|
| B-01 | 高 | 全局字号 80%–200% | `workbench.css` 只把 `--wb-font-scale` 用在 `body` 的 `font-size`；四区域大量 `font-size`/间距用 `rem`，`rem` 相对 `<html>` 根字号，200% 时这些文字不变大。实际效果是部分文字放大、部分不动，层级错乱 | `resources/public/css/workbench.css:66`；`backup/view.cljc` `.88rem`、`diagnostics/view.cljs` `.9rem`、`schedule/view.cljs` `.85rem` | 根字号改为 `html { font-size: calc(16px * var(--wb-font-scale)) }`，`body` 改回 `0.9375rem`，让 rem/em 只缩放一次 | 已由 workbench.css 所有者修复（CSS `dda62554…`，11:13:59）；B-01 关闭 |
| B-02 | 高 | 备份/定时/诊断深色 | 三个面板只写 `@media (prefers-color-scheme: dark)`，不读 `html[data-theme]`。用户显式选深色而系统浅色时面板仍为浅色；显式浅色而系统深色时面板变深 | `backup/view.cljc:26`、`schedule/view.cljs:1094`、`diagnostics/view.cljs:31` 修复前 | 增加 `html[data-theme="dark"]` 与 `auto/system` 媒体查询双路径 | 已修（在冻结 bundle） |
| B-03 | 高 | 设置→数据中心 | 数据中心页顶部出现“首次配置”四步清单、“数据中心”占位段和“保存连接不自动触发聊天”页脚；这些属于供应商页，与下方统计/备份重复且上下文错误 | `01-datacenter-top-before.png` 目视；`settings/view.cljc` 无条件渲染 onboarding/footer | 只在 `:providers`/`:models` 显示首次配置；`:data` 不再渲染占位段；页脚在 `:data` 隐藏；标题描述按分区切换 | 已修（在冻结 bundle） |
| B-04 | 中 | 备份→恢复与预览列表 | “大小”直接把字节数 `str` 出来，例如 `2995724`，没有用已有的 `format-bytes` | `backup/view.cljc` 的 `backup-row` facts | 数字走 `format-bytes`，字符串原样，缺失写“未提供” | 已修（待重建） |
| B-05 | 中 | 备份→目标与状态 | 存储信息用 `<pre>(pr-str storage)</pre>` 整段输出；目标摘要没有“最近成功备份/占用/可用空间/最近恢复点” | `backup/view.cljc` 的 `status-section`/`target-summary`；服务端 status 已提供 `:last-backup` 与 `:storage` | 改为事实列表；目标摘要增加最近成功备份、本机恢复点、数据占用、可用空间 | 已修（待重建） |
| B-06 | 中 | 定时任务→保存 | 跟随设备时区时仍要求 `:zone` 非空，浏览器拿不到 IANA 时区时误报“固定时区需要一个 IANA 时区名称” | `schedule/model.cljc` 的 `validate-form` | 仅 `:fixed` 模式校验 zone | 已修（待重建） |
| B-07 | 中 | 定时任务深色主按钮 | 深色 accent `#9ec1ff` 上仍用白字，对比度约 1.7:1，低于 4.5:1 | `schedule/view.cljs` 的 `.hc-schedule-button--primary` | 深色下文字改 `#08131b` | 已修（待重建） |
| B-08 | 中 | 诊断→日志/诊断包/轮次 | `.hc-diag-facts` 没有任何 CSS，`<dd>` 使用浏览器默认 40px 缩进，事实列表退化成默认 `<dl>` | `diagnostics/view.cljs` 全文无 `.hc-diag-facts` 规则 | 增加两列网格；窄屏单列；`dd` 去缩进 | 已修（待重建） |
| B-09 | 中 | 诊断→诊断包表单 | 复选框字段复用 `.hc-diag-field` 的 `display:grid`，复选框与标签上下堆叠，不是一行 | `checkbox-field` 使用 `hc-diag-field` | 新增 `.hc-diag-check` 横排 + 44px 热区 | 已修（待重建） |
| B-10 | 中 | 语音设置→服务测试 | 测试结果显示 `(pr-str layers)`，用户看到整段 EDN map | `speech/ui.cljs` 服务卡测试分支 | 按“地址可达/身份可用/转写/测试音频/音频合成/翻译”逐行显示状态与原因 | 已修（待重建） |
| B-11 | 中 | 语音/翻译/播放器深色 | 服务卡边框 `#d8d8dc`、次要文字 `#555`、播放器背景 `#fff`、错误 `#a11`、警告 `#8a4b00` 等硬编码浅色；深色主题下刺眼或对比不足 | `speech/ui.cljs`、`translation.cljs`、`playback.cljs` 的 hex 颜色 | 全部改用 `--wb-line`/`--wb-ink-muted`/`--wb-surface`/`--wb-danger`/`--wb-warn`/`--wb-accent` | 已修（待重建） |
| B-12 | 低 | 独立翻译页 | 页面 wrapper 额外 `padding:12px`，与定时/语音/诊断页的左边缘不对齐 | `translation.cljs` wrapper | 去掉额外 padding | 已修（待重建） |
| B-13 | 低 | 备份→恢复与预览 | `.hc-backup-section-heading` 无样式，“刷新列表”按钮掉到“选择要恢复的备份”标题下方，而不是同一行右侧 | `backup/view.cljc` 类名无 CSS | flex 两端对齐 + 换行 | 已修（待重建） |
| B-14 | 低 | 诊断→反馈草稿 | “打开邮件草稿”是 `<a class="hc-diag-button">`，但 `.hc-diag-button` 无样式；显示为默认蓝色下划线链接，与相邻按钮不一致 | `diagnostics/view.cljs` 的 `feedback-tab` | 补 `.hc-diag-button` 按钮外观 | 已修（待重建） |
| B-15 | 低 | 诊断→筛选栏 | 时间范围按钮、自定义日期、刷新/清除动作都只占 grid 的一个单元格，与“时区/助手/模型/服务”字段混排，出现空单元格和不对齐 | `diagnostics/view.cljs` 的 `filter-bar` | 三类动作加 `.hc-diag-span` 跨整行 | 已修（待重建） |
| B-16 | 低 | 诊断→热力图 | 日期 `YYYY-MM-DD` 在 `min-width:4.2rem` 的按钮里可能断行，叠加 3 行计数变成 5 行高 | `diagnostics/view.cljs` 的 `.hc-diag-day` | `min-width:5.2rem` + 日期 `white-space:nowrap` | 已修（待重建，需有数据时复验） |

## 4 Ugly 清单

| ID | 严重度 | 位置 | 现象 | 证据 | 修复 | 状态 |
|---|---|---|---|---|---|---|
| U-01 | 中 | 设置页标签层级 | 外层“设置/定时任务/语音与朗读/独立翻译/快捷键”和内层“供应商与连接/模型与角色/数据中心/外观”都是同款 pill；数据中心页首屏出现两层几乎相同的标签 | `01-datacenter-top-before.png`；与 `visual-audit-startup.md` U-03 同源 | 外层改下划线标签或降低对比；内层保留 pill | 未修（跨区域，w16 已备 CSS） |
| U-02 | 中 | 数据中心页 | 统计与诊断 + 加密备份与恢复同页，页面很长；诊断的“价格”表单和备份的“密码/类别”都在同一滚动条里，首屏之后容易失去位置感 | 源码结构 + 既有截图 | 给两个区域加锚点/吸顶子导航，或拆成两个设置子页 | 未修（结构改动，超出纯视觉） |
| U-03 | 低 | 备份→目标与状态 | “当前目标”事实与上方“已配置目标”列表重复展示目录/地址；存储状态又是第三块 | 源码 | 合并为一块“目标与存储”，列表只保留未选目标 | 未修（需视图重构） |
| U-04 | 低 | 语音设置服务卡 | 保存后的服务卡一行放 7 个按钮（测试/设为默认/禁用/编辑/上移/下移/删除），1280 下已经很密，360 下换行占三行 | 源码 | 折叠为“更多”菜单，或图标 + 文字 | 未修（需 JS 改动） |
| U-05 | 低 | 定时任务页 | 平台说明、同步状态、策略说明三个提示块依次出现，首屏被说明占满；新建任务按钮在空态和工具栏重复 | 源码 | 合并平台/同步为一行状态；空态只保留一个主按钮 | 未修（需视图改动） |
| U-06 | 低 | 数据中心页标题 | 进入“定时任务/语音与朗读/独立翻译”后，设置页大标题仍是“设置与数据中心”，与内容不符 | `workbench/views/settings.cljs` | 标题随当前标签切换 | 未修（跨区域） |
| U-07 | 低 | 备份→创建备份 | 面板自动刷新状态后立刻显示“备份状态已更新。”信息条和“关闭”按钮；用户没有点刷新，首屏被这条自述状态占位 | `f-04-backup-create-1280.png`；`backup/model.cljc` `apply-status` | 成功刷新不弹 message；只保留目标事实更新，或在用户点“刷新状态”时才提示 | 未修（需重建） |

## 5 已应用的源码修复

| 文件 | 修复 | 是否在 `dd968793` |
|---|---|---|
| `settings/view.cljc` | 数据中心页去掉首次配置/占位段/供应商页脚；标题描述按分区切换 | 是 |
| `backup/view.cljc` | 深色主题显式 `data-theme`；section heading/row header/result/verify 样式；备份列表大小格式化；目标摘要与状态页事实化 | 是 |
| `backup/model.cljc` | `target-view` 增加 `:last-backup`/`:storage` | 是 |
| `schedule/view.cljs` | 深色主题显式 `data-theme`；深色主按钮对比；详情标题/徽章同行 | 是 |
| `schedule/model.cljc` | 跟随设备时区不再误报固定时区错误 | 是 |
| `diagnostics/view.cljs` | 深色主题显式 `data-theme`；事实列表网格；复选框横排；筛选栏跨行；热力图日期；`hc-diag-button` 样式 | 是 |
| `speech/ui.cljs` | 硬编码浅色改 `--wb-*`；测试结果可读化；服务卡换行；44px 复选框；转写框/下拉主题色 | 是 |
| `speech/translation.cljs` | 深色主题色；去双重 padding；复选框热区；textarea box-sizing | 是 |
| `speech/playback.cljs` | 播放器深色主题色 | 是 |

## 6 未修项与原因

1. ~~B-01 字号缩放~~：已由 `workbench.css` 根字号策略修复（`html` 用 `calc(16px * var(--wb-font-scale))`，`body` 用 `0.9375rem`），不再列为未修。
2. **U-01 标签层级**：跨设置页外层/内层，w16 已有补丁；避免重复修改。
3. **U-02/U-03/U-04/U-05/U-06**：需要视图结构或交互改动，不是纯 CSS；且会扩大 bundle 变更面。
4. **备份“预计大小”**：服务端没有打包前估算接口，只有打包后的 `payload-size`；不做假数字。
5. **WebDAV/S3 配置表单与测试连接**：当前 UI 明确只展示服务器已配置目标；没有配置表单。这是功能缺口，不是样式问题。
6. **诊断页没有“常见问题”**：四个标签只有用量、日志、诊断包、反馈；§8.5 的按动作组织 FAQ（无法发送/图片不支持/账户过期/后台任务被结束/导入失败）没有入口。
7. **恢复密码在未选备份时仍可编辑**：可用但无意义；未改，避免影响“先输密码再选备份”的操作习惯。
8. **复制密码反馈在页首**：滚动到密码区时看不到；需要 toast 或就地反馈，涉及桥接层。
9. **详细日志五个动作按钮未按状态禁用**：可用但容易误点；需要按 `capturing?/paused?/enabled?` 条件渲染。

## 7 复验计划（单段 ≤5 分钟，四区域一次跑完）

协调者要求把四区域合并为一个有限段，等 W17/W18/W19 释放锁后执行。

| 段 | 覆盖 | 脚本 | 截图 |
|---|---|---|---|
| F | 诊断统计/日志/诊断包、备份创建/恢复、语音空态/添加表单、定时空态/表单；1280/820/360；显式深色；200% 字号 | `plans/evidence/visual-audit/ops/segment-ops-final.js`（sha256 `afcfc7260168b94107e94dc86ded9b2e6bd25c49ed2fb242092697b57ec492ce`） | 已拍 `f-01`…`f-09`、`f-04b`、`f-05b`（1280 浅色）；`f-10`…`f-25` 未拍 |

原 S1–S4 脚本保留作单区域深挖备份；F 段只拍关键页，不创建/恢复真实备份（避免把 5 分钟耗在等待回执）。

每段流程：
1. `flock -w 240 /tmp/hc-app-single.lock bash -c '/tmp/hc-visual-ops/run.sh <script>'`；拿不到锁就退出，不无限等待。
2. app 启动参数 `-Xmx768m -XX:MaxDirectMemorySize=512m -Djava.io.tmpdir=/tmp/hc-visual-ops/tmp`，记录 PID、`ps RSS`、`jcmd VM.flags`、`GC.heap_info`。
3. 1 个 Chrome；脚本结束立即 `browser.close()`。
4. 停止 app JVM（先 TERM，20s 后 KILL），检查 PPID=1 遗留；释放 flock。
5. 截图写入 `plans/evidence/visual-audit/ops/`，更新本文“已拍”列与状态。

## 8 证据文件

- `plans/evidence/visual-audit/ops/01-datacenter-top-before.png`：数据中心顶部（修复前，显示首次配置/占位段）。
- `02-diag-stats-before.png`、`03-diag-stats-mid-before.png`：统计与诊断首屏/中段（修复前）。
- `static-findings.json`：bundle 哈希、源码 mtime、未样式化类名、功能缺口、逐项修复状态。
- `memory-start-1g-run.txt` / `memory-end-1g-run.txt`：第一次 app 运行（1g 上限，已被后续 768m 标准取代）的 PID/RSS/VM.flags/GC.heap_info；后续每段按 768m 记录。
- `run.sh`、`segment1-diagnostics.js`、`segment2-backup.js`、`segment3-speech.js`、`segment4-schedule.js`：可复跑的有限段脚本。
- 本轮 1280×900 截图（均绑定 `dd968793`，sha256 见 `static-findings.json#screenshot_sha256`）：`f-01-diag-stats-1280.png`、`f-02-diag-logs-1280.png`、`f-03-diag-bundle-1280.png`、`f-04-backup-create-1280.png`、`f-04b-backup-result-1280.png`、`f-05-backup-restore-1280.png`、`f-05b-backup-verified-1280.png`、`f-06-speech-empty-1280.png`、`f-07-speech-form-1280.png`、`f-08-schedule-empty-1280.png`、`f-09-schedule-form-1280.png`。

## 9 结论（当前）

- 已识别视觉/交互缺陷：16 项；全部已修（15 项由本轮源码修复并进入 `dd968793`；B-01 由 `workbench.css` 根字号策略修复，CSS `dda62554…`）。
- 未修 bug：0 项；仍存 6 项功能缺口/反馈问题（备份预计大小、WebDAV/S3 表单、诊断 FAQ、恢复密码可编辑、复制密码反馈、日志按钮状态）。
- Ugly：7 项，均未修（结构/跨区域）。
- 实时截图：11 张 1280 浅色（诊断统计/日志/诊断包、备份创建/结果/恢复/校验、语音空态/表单、定时空态/表单）；360/820/深色/200% 未拍。F 段在 2:40 时因 360 诊断定位卡住，为释放锁主动停止；已保留截图。
- 当前 bundle 为 `dd968793`（11:19:25），含全部修复与 W9 的 4 个标记；本轮 11 张 1280 截图已绑定该 hash。

## 10 冻结窗口（11:26–11:41）

- 窗口内不写 `src/**`/`test/**`、不重建、不跑 E2E；只读/截图/报告允许。
- W13 串行全量测试；本区域视觉段不抢锁，待窗口结束且协调者给短段后再补 360/820/深色/200%。
- 最终 hash 仍为 `dd968793`；若窗口后有人重建，以 W9 公布的新 hash 为准并复核 4 个标记。

## 11 final11 重绑（bundle `72de4f9a` / 源码树 `7dfcf0d7`（final12；原 ab801450 已前冻结），CSS `332f9fd3` 未变）

- §8/§9 的 11 张 `f-0*.png` 与 `static-findings.json` 均绑 `dd968793`（已作废中间冻结）；360/820/深色/200% 自始未拍。
- final11 复验（证据 `plans/evidence/visual-audit/final11/`，bundle `72de4f9a` + 树 `7dfcf0d7`，CSS `332f9fd3`）：设置页 1280 一张（`shots/40-settings-1280`）。实测壳层：外层 tab（设置/定时任务/语音与朗读/独立翻译/快捷键）与内层分区（供应商与连接/模型与角色/数据中心/外观）渲染、已同步徽标可见；诊断/备份/语音/定时各分区在最终冻结上仍无新截图，360/820/深色/200% 未拍，不宣称 PASS。
