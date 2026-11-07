# 视觉审查：看板 / fleet、浏览器、文件 / 产物、搜索

> 状态：完成一轮有限视觉段（app + fixture，约 98 秒）；真实截图 53 张，看板 rich/搜索来源夹具因夹具运行错误未取得有效画面；静态审查与既有截图复核完成。
> 审查对象：Herdchestra 0.0.1 工作台。
> 基线 bundle：`5d36ac81757bee2101493288be1fdf8d9aca51dc36d54385410d49173ce3d0ae`（2026-11-06 11:07:16，2995724 bytes）。视觉修复尚未重建，最终 hash 待补。

## 1 方法与环境

- 真实 app：`/tmp/pixi run clojure -M:server -m hc.server.app --host 127.0.0.1 --port 0 --data-dir /tmp/hc-visual-board/data --workspace-root /tmp/hc-visual-board/work --static-root resources/public`。
- Chrome：系统 `/usr/bin/google-chrome`，Playwright `NODE_PATH=/tmp/hc-pw/node_modules`，`--disable-dev-shm-usage`。
- 单实例：整段 app + Chrome + 截图 + 停止用 `flock /tmp/hc-app-single.lock` 包住；每段只跑一个区域，结束立即停止并释放。
- 数据依赖状态：服务器没有已准入 Mission 时，看板/浏览器/文件/产物/搜索的空态由真实 app 截图；fleet、任务树、槽位、令牌、W、join 报告、页面卡、文件差异、产物卡、搜索来源等需要种子状态，使用同一生产组件的浏览器夹具渲染，夹具源码与截图脚本在 `/tmp/hc-visual-board/`，不进入交付 bundle。
- 视口：1280×900、820×1180、360×800；浅色/深色；字号 100%。

## 2 已有截图复核

- `plans/evidence/e2e-09-10/e2e10-restart-board.png`（780×493）：空 fleet 直接显示 `0 swarm row(s): ...` 英文长句，和中文页头断裂；`Swarm fleet` 小节后接英文状态说明；空态没有中文解释。已修：空态不再渲染英文 summary，改为中文空态。
- 同图：`请求重同步` 与 `已同步` 按钮在 32px 高度，桌面可接受；窄屏需 44px，已补移动端规则。
- 同图：看板卡片背景为纯白，页面背景浅灰；层级可辨，但英文长句使首屏显得像日志而不是看板。

## 3 静态审查问题清单

### bug（已修，待截图复验）

1. **看板忽略显式主题**：`dashboard-css` 只有 `prefers-color-scheme`，工作台 `html[data-theme="dark"]` 时看板仍是浅色。已补 `html[data-theme="light|dark"]` 与 `html[data-contrast="high"]` 覆盖。
2. **Framework7 卡片/列表配色不跟随工作台主题**：F7 `card`/`list` 使用自己的 `--f7-*` 变量，手动深色时可能深底深字。已给 `.hc-swarm-row`、`.item-content`、`.item-title` 等加 `--hc-*` 覆盖，并去掉 Card 默认外边距。
3. **搜索控件硬编码浅色 fallback**：`.hc-search-controls` 等使用 `--f7-card-bg-color,#fafafa` 与 `--f7-label-color,#555`，深色主题下可能浅底浅字。已改用 `--wb-*` 变量。
4. **文件表窄屏溢出**：5 列表格在 360px 会撑破页面。已加 `.hc-files-table-wrap` 横向滚动容器，并只在 ≤599px 设 `min-width:600px`，桌面不产生多余滚动。
5. **移动端小按钮不足 44px**：`wb-button-small` 在 360px 仍是 32px；已加入 ≤599px 的 44px 规则。
6. **产物状态徽章没有样式**：`artifacts/view.cljs` 输出 `wb-badge-ok/error` 类，但 CSS 只认 `data-tone`，所有产物状态徽章都退化为默认灰色。已改为 `data-tone`，`:error` 映射为 `danger`。
7. **产物完整路径被省略号截断**：卡片内 `wb-code` 继承 `.wb-list-main small` 的 nowrap/ellipsis，§8.5 要求的完整路径不可见。已允许 `.hc-artifact-card .wb-code` 换行。
8. **搜索来源列表无高度上限**：来源多时会把输入区推得很远。已给 `.hc-search-sources` 40vh 滚动上限。
9. **浏览器重复“受管浏览器”标题**：页面 h1 与权限卡 h2 同名；已把权限卡改为“浏览器权限”，页面标题改为 h2 以避免材料页两个 h1。
10. **浏览器重复“停止代理”按钮**：接管卡与地址栏各一个；已移除接管卡中的重复按钮。
11. **浏览器权限卡重复“刷新状态”**：页头已有全局刷新；已移除权限卡内的重复按钮。

### ugly（待截图确认 / 部分未修）

1. **看板大量英文文案**：`Swarm fleet`、`Running/Queued/Joined/Abandoned/Failed`、`Root tasks`、`Worktree`、`Join queue`、`Token holder`、`Workspace write entrance W`、`No board tasks...` 等与中文页头断裂。`swarm/model.cljc` 的标签和 `swarm/ui.cljs` 的句子均有。完整中文化会牵动 `ui_test.cljs`、`ui_smoke.cljs`、`swarm_test.clj` 的大量英文断言，属于文案/测试联动改动，未在本轮直接改；已先修空态英文长句。
2. **看板状态说明像日志**：summary 是一整段英文计数句，首屏信息密度高；建议改成中文短句 + 计数徽章。
3. **fleet 行的根任务计数与 master 版本并排**：在 820px 两栏布局下可能挤压；待 820 截图确认。
4. **槽位 F7 ListItem 的 `after` 文本**：`Worker · Dead (logical)` 等英文且可能单行溢出；已允许 `item-after` 换行，文案未中文化。
5. **浏览器页面卡截图失败态**：`screenshot` 仅在缺少 id 时显示“截图不可用”；若 blob 404，会显示破图。未修，需要视图层错误回退。
6. **浏览器权限事实值**：`范围：current-tab`、`接收模型服务：openai-compatible` 直接显示内部关键字，用户不可读；未修。
7. **文件表在 600–900px**：单列布局下 5 列仍可能拥挤；待 820 截图确认。
8. **字号 80%–200%**：看板/搜索/浏览器大量使用 `rem`。并行工作已把 `html` 的 `font-size` 改为 `calc(16px * var(--wb-font-scale))`，并把 `body` 改为 `0.9375rem`，`rem` 现在会随全局缩放；待 200% 截图复验。
9. **1024–1199 三栏挤压**：工作台在 1024px 仍有 224px 导航 + 320px 详情栏，主内容约 480px；但 `.hc-board-columns` 的 820px 断点和 `.hc-files-layout` 的 900px 断点按视口判断，仍会排成两栏。看板任务树/槽位和文件列表/预览可能被压到不可读。未改，待截图确认后建议把文件断点提到 1279px、看板在 1024–1199 单列。
9. **看板 summary 的 `role=status`**：每次渲染整段英文都会进入 live region，读屏会重复播报长句；中文化/缩短后再评估。
10. **浏览器 200% 字号局部裁切**：标签栏与部分按钮文字被裁切；200% 下应允许换行或增高。
11. **浏览器深色输入框浅色底**：深色截图中地址栏/文本输入仍出现浅色底，与周围深色卡片割裂；需确认是否 UA/F7 样式覆盖。
12. **“已同步”重复**：顶部连接徽章与浏览器面板各有一个“已同步”，同屏重复。

## 4 未修项

- 看板英文文案完整中文化（需同步测试断言）。
- 浏览器页面卡 blob 404 的图片回退。
- 浏览器权限事实值的人类可读映射。
- 1024–1199 三栏下看板/文件两栏断点按视口判断造成的挤压。
- 看板空态以外的英文状态说明。

## 5 截图结果（有限段）

- 成功：app 看板空态 1280/360/深色；app 浏览器离线 1280/360；app 文件空态；app 产物空态；app 搜索关闭/展开；fixture 文件 rich/conflict/error/search/empty/820/360/深色/200%；fixture 浏览器 offline/permission/takeover/360/深色/200%；fixture 产物 rich/360/深色；fixture 看板 empty（仅空态）；fixture 搜索全部失败（closed/error/empty 也为错误页）。
- 失败：fixture 看板 rich/joined/failed/board-only/recovery/long/820/360/深色/200% 与 fixture 搜索 sources/closed/error/empty 在静态夹具页报 `TypeError: Cannot read properties of undefined (reading 'once'/'off')`、`Invalid arity: 0`，截图是错误页，不作为视觉证据。失败原因是夹具没有完整 F7 App 上下文/搜索夹具接线；修复版夹具已构建但重跑被锁超时，未取得新图。
- 有效截图约 26 张；错误页截图保留在证据目录，文件名含 `board-rich-*`、`search-sources-*`，报告中明确不采用。
- 已核验：文件 360/820/200% 无横向溢出，表格可读；文件深色无浅色残留；产物 360 路径完整换行、深色下“可用/内容缺失”徽章分别为绿/红；浏览器 360 无溢出；看板空态 360 标题/按钮正常换行；看板空态夹具（无 F7 行）正常。
- 未通过/待修：浏览器 200% 下标签与部分按钮文字裁切；浏览器深色下输入框出现浅色底；顶部与浏览器面板各有一个“已同步”徽章（重复）；看板 rich/搜索来源无有效截图。

## 6 本轮交付限制

- 视觉段已按 ≤5 分钟执行并释放；app/Chrome 已停止，无我方残留。
- 看板 rich 与搜索来源夹具失败，是夹具上下文/接线问题，不是产品页面结论；修复版夹具已构建，但第二次短段等待锁超时，未重跑。
- 生产 bundle 已由 W17 重建为 `dd968793…`；截图绑定临时 `4652ef26`。最终 hash 以 W9 复核为准。
- 临时夹具源码 `src/hc/visual_fixture.cljs` 已删除，不进入交付；夹具构建与脚本在 `/tmp/hc-visual-board/` 与证据目录 `tools/`。

## 7 计数

- 目标区域：5（看板/fleet、浏览器、文件、产物、搜索）。
- 本轮新增真实浏览器截图：53 张（含错误页）；有效约 30 张；复核既有截图：1。
- bug：12 项，已修 12，未修 0（均已静态修复，待截图复验）。
- ugly：12 项，已修 2（槽位 `item-after` 换行、字号缩放由并行 `html` 根字号修复），未修/待确认 10。
- 未修项：看板英文文案完整中文化、浏览器截图 404 回退、权限事实值映射、1024–1199 断点、空态以外英文说明、live region 长句、浏览器 200% 局部裁切、深色下输入框浅色底、顶部/浏览器“已同步”重复徽章。

## 8 待补截图

- app：board 空态 1280/360；browser 离线 1280/360；files 空态 1280/360；artifacts 空态 1280；session 搜索关闭/展开 1280。
- 夹具：board rich/joined-open/failed-open/empty/board-only/recovery/long/820/360/dark；browser offline/permission/takeover/360/dark；files rich/conflict/error/search/empty/820/360/dark；artifacts rich/360/dark；search sources/error/empty/closed/360/dark。
- 每张截图记录 app/Chrome PID、RSS、`jcmd VM.flags`、`GC.heap_info`。

## 9 证据与 bundle

- 证据目录：`plans/evidence/visual-audit/board/`（当前仅 `pre-existing-e2e10-board.png`）。
- W17 最终重建（W9 复核前）：`dd968793cc74919e139f31bb290a5dfb68309c6d9a78acfd766a4d984f2fda4b`（11:19:25，3007964 bytes）；CSS `dda62554c9bb0b2643c0c437a1368f56b021f716001bcefa3f5cf92a32fa44ca`。
- 截图时 app 实际加载临时 `4652ef26…`；证据不冒充最终 hash。
- 注意：`shell.cljs:94` 未闭合括号已由 W21 修复；下一次重建后必须重新核对 D-08 四个标记。
- 夹具构建：`/tmp/hc-visual-board/fixture-public/js/main.js`，`ba93251ec12138868bbc8a31083c35e8e5613655682cef2362ec77045b0a1ada`。
- 复验脚本：`/tmp/hc-visual-board/verify-all.cjs`，`d8f5e8b85619c4f8223bfb8743bb1bee179424e8d146ce548934f0c4d11696b1`。
- 运行脚本：`/tmp/hc-visual-board/run-all.sh`，`84d2c81c19ebabad6ee7c5d3baae09f809a37594045bf20f1a96c51aaed66563`。
- 视觉修复重建后必须公布新 hash，并确认 D-08 四个标记仍在。

## 10 final11 重绑（bundle `72de4f9a` / 源码树 `7dfcf0d7`（final12；原 ab801450 已前冻结），CSS `332f9fd3` 未变）

- §5/§9 的 53 张截图：真实 app 部分绑临时 `4652ef26`（不冒充最终 hash）；夹具截图绑 fixture bundle `ba93251e`，非生产 bundle。§9 的 `dd968793`/`dda62554` 已作废。
- final11 复验（证据 `plans/evidence/visual-audit/final11/`，bundle `72de4f9a` + 树 `7dfcf0d7`，CSS `332f9fd3`）：看板 1280 空态一张（`shots/20-board-1280`），实测“Swarm 看板 / fleet 总览、任务树、等待链与固定运行槽 / 尚未准入任何 swarm”空态文案中文化、已同步徽标可见。fleet/任务树/槽位/join 报告/搜索来源等种子态仍无最终冻结证据，不宣称 PASS。
