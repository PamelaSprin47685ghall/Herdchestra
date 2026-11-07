# 首次启动 / 模型连接 / 设置 视觉审查（0.0.1）

> 状态：**进行中（锁暂停）**。已完成离线代码对照与既有截图复核；实时截图与修复后复验等待协调者释放锁。本文所有结论都标注证据来源；未截图的项目明确写“待复验”，不冒充已看过。

## 1 环境与基线

> 注：视觉段顺序 W16→W17→W18→W19→W20→W21。本区域修复为纯 CSS，不重建 bundle；若后续有人重建，以新 hash 为准。

| 项 | 值 |
|---|---|
| 仓库 | `/home/kunweiz/Desktop/vibe/Herdchestra` |
| 客户端 bundle | `resources/public/js/main.js` |
| bundle SHA-256 | `dd968793cc74919e139f31bb290a5dfb68309c6d9a78acfd766a4d984f2fda4b`（W9 最终冻结，2026-11-06 11:19:25，3,007,964 B；11:26–11:41 冻结窗口；W9 已确认 W16 收到，窗口内不写 src/test、不重建、不跑 E2E） |
| 样式表 SHA-256（当前，补丁未应用） | `dda62554c9bb0b2643c0c437a1368f56b021f716001bcefa3f5cf92a32fa44ca` |
| 审查范围 | 首次启动空安装、引导/连接三步、设置供应商、模型选择、价格表单、诊断/用量、外观、快捷键 |
| 视口计划 | 1280×800、820×1180、360×800；浅色/深色；字号 80%/100%/200% |
| 运行时约束 | app JVM `-Xmx768m -XX:MaxDirectMemorySize=512m`；同时 1 app + 1 Chrome；整段 `flock /tmp/hc-app-single.lock`；结束即停 |

## 2 页面与证据清单

| 页面 | 现状证据 | 实时截图 | 结论 |
|---|---|---|---|
| 空安装首页 | `plans/evidence/no-model/no-model.png`、`no-model-dom.html`、`plans/evidence/bundle-health/home.png` | 待补 | 无引导卡、无三示例；有两条重复英文错误 |
| 首次配置/引导 | `plans/evidence/e2e-auto/e2e01/e2e01-03-onboarding.png`（1440×1000，2026-11-06 11:07） | 待补 | 4 项检查表，不是三步向导；默认有序列表标记与标签自带编号叠加；当前步骤无高亮 |
| 供应商连接（空/已保存） | `plans/evidence/e2e-auto/e2e01/e2e01-04-connection-saved.png`、`e2e01-05-connection-tested.png`、`plans/evidence/e2e-regression-01-02-22/e2e01-settings.png` | 待补 | 卡片/测试区可用；重复 toast；状态“未测试”重复 |
| 模型与角色 | `plans/evidence/e2e-auto/e2e01/e2e01-05-model-bound.png` | 待补 | 角色卡为未加样式默认块流（无边框/背景/间距）；模型行未在截图中出现 |
| 外观 | 静态代码（`appearance/view.cljc`）+ CSS 对照 | 待补 | 主题选中态类名拼写不一致；大量类名无样式 |
| 数据中心/诊断/用量 | 静态代码 + `diagnostics/view.cljs` CSS 对照 | 待补 | 三口径结构存在；需实时看层级与深色 |
| 快捷键 | 静态代码（`workbench/views/settings.cljs`） | 待补 | 表格有样式；需实时看窄屏 |

## 3 Bug 清单

| ID | 严重度 | 位置 | 现象 | 证据 | 修复建议 | 状态 |
|---|---|---|---|---|---|---|
| B-01 | 高 | 会话首页 | 空安装没有“连接模型 / 恢复已有资料 / 稍后设置”引导卡，也没有“解释一个概念 / 整理一段文字 / 分析一个文件”三个示例；`settings/view.cljc` 却写“首次启动会在会话页显示连接引导” | `no-model-dom.html` 全文无这些文本；`home.png` 目视无卡片 | 在会话页增加引导卡；示例点击只写入草稿；或先改掉不实文案 | 未修（功能缺口，超出纯 CSS） |
| B-02 | 高 | 设置 → 供应商 | 连接流程是单页长表单，没有“选择连接方式 / 填写连接信息 / 选择一个可用模型”三步标题与逐步返回；首次配置面板是 4 项检查表 | `e2e01-03-onboarding.png`、`settings/view.cljc` 的 `editor`/`onboarding-panel` | 实现三步向导；返回保留字段；未保存密钥不算连接 | 未修（需 JS 改动，bundle 冻结） |
| B-03 | 中 | 无模型输入区 | 提示只有“先选择一个可用模型，草稿已保留”，缺“或所属连接已停用 / 原模型已不可用”，也没有“选择模型 / 打开连接设置”入口 | `no-model-dom.html` 的 composer hint | 按 §7.4 1.4 补全文案与两个入口 | 未修（需 JS 改动） |
| B-04 | 中 | 空安装会话页 | 出现两条相同英文提示 `managed browser is not connected for this session`，遮挡内容 | `no-model.png`、`no-model-dom.html` | 空安装不自动触发浏览器命令；服务端错误本地化并去重 | 未修（涉及 browser bridge / server 文案） |
| B-05 | 中 | 设置 → 外观 | 没有界面语言、背景、气泡设置；只有主题/字号/对比度/动态效果 | `appearance/view.cljc` 全文 | 按 §7.4-16 补齐，或修订规格 | 未修（功能缺口） |
| B-06 | 中 | 模型详情 → 价格 | 有输入/输出价格、币种、单位，但没有示例预览；未知价格与 0 的区分只靠留空 | `settings/view.cljc` 的 `model-detail` | 增加“示例预览”，缺价格/单位时明确“无法估算” | 未修（需 JS 改动） |
| B-07 | 低 | 设置通知 | 保存草稿后同一提示重复 3 次，遮挡连接卡片与侧栏 | `e2e01-04-connection-saved.png` | 同一文本合并为一条；或只在失败时提示 | 未修（需 JS 改动） |

## 4 Ugly 清单

| ID | 严重度 | 位置 | 现象 | 证据 | 修复 | 状态 |
|---|---|---|---|---|---|---|
| U-01 | 高 | 模型与角色 / 外观 / 首次配置 | 42 个视图类名在 `workbench.css` 中没有任何规则：`hc-model-row`、`hc-role-grid`、`hc-role-card`、`hc-onboarding`、`hc-appearance*`、`hc-badges`、`hc-model-meta` 等；角色卡与模型行退回默认块流，无边框/间距/层级 | 静态类名对照（`static-findings.json`）；`e2e01-05-model-bound.png` 目视角色卡无边框、默认块流 | 纯 CSS 补齐布局（补丁已备） | 待应用 |
| U-02 | 中 | 外观 | `appearance/view.cljc` 用 `hc-button--primary`，样式表只有 `hc-button-primary`；选中主题没有视觉反馈 | 代码对照 | CSS 别名 + `[aria-pressed="true"]` | 待应用 |
| U-03 | 中 | 设置标签 | 外层“设置/定时任务/语音与朗读/独立翻译/快捷键”与内层“供应商与连接/模型与角色/数据中心/外观”同为同款 pill，层级相同 | `bundle-health/settings.png`、`e2e01-settings.png` | 外层改下划线标签，内层保留 pill | 待应用 |
| U-04 | 中 | 窄屏设置 | 密钥表 6 列、模型详情表 4 列，容器无横向滚动；360px 会挤压或溢出 | 静态结构 | 容器 `overflow-x:auto` + 表 `min-width` | 待应用 |
| U-05 | 低 | 首次配置 | `<ol>` 默认标记 `1.–4.` 与标签自带的“1/2/3/4”叠加；当前步骤无高亮；步骤间无卡片/分隔 | `e2e01-03-onboarding.png`（2026-11-06 11:07） | `list-style:none` + 当前/完成态边框 | 待应用（随 U-01） |
| U-06 | 低 | 供应商卡片 | “状态：未测试”和“最近测试：未测试”重复占两行 | `e2e01-04-connection-saved.png` | 合并为一行，或最近测试只在有结果时显示 | 未修（需 JS 改动） |
| U-07 | 低 | 设置窄屏 | 外层标签与内层标签换行后占据较多首屏高度；需实时 360px 复核 | 待复验 | 视截图决定是否折叠外层标签 | 待复验 |

## 5 已准备的修复（纯 CSS，不改 bundle）

补丁文件：`/tmp/hc-visual-startup/settings-visual-fixes.css`（583 行，SHA-256 `cf29c6c1…`，**未应用**；等视觉锁窗口）。

单段执行脚本：`/tmp/hc-visual-startup/run-segment.sh`（SHA-256 `ff13bf33…`）。它在一个 flock 段内应用补丁、启动 app（`-Xmx768m`、固定 7598、隔离 `java.io.tmpdir`）、记录 PID/RSS/jcmd、跑截图脚本、停止 app；失败自动恢复 CSS。

覆盖：
- U-01：模型行、角色卡、模型组、能力编辑器、模型详情、徽章、草稿状态、首次配置、外观控件的布局与间距。
- U-02：`hc-button--primary` 别名 + 外观按钮 `aria-pressed` 选中态。
- U-03：`[data-settings-section] > .wb-tabs` 外层下划线标签，内层 pill 不变。
- U-04：`.hc-keys` / `.hc-test-panel` / `.hc-model-detail` 横向滚动与表最小宽度。
- U-05：首次配置列表去默认编号，当前/完成态可见。
- 移动端热区：`.hc-input`/`.hc-select`/文本域在 360px 下最小高度 44px（原 40px）。
- 键盘焦点：`.hc-tab`、外观滑块、复选框、外层标签的 `:focus-visible`。

不改：`settings/view.cljc`、`appearance/view.cljc`、bundle。理由是 W9 已冻结 `5d36ac81`；CSS 由服务端直接提供，重建 bundle 不会改变 hash。

## 6 未修项与原因

1. B-01/B-02/B-03/B-05/B-06/B-07：需要 JS/视图改动，当前 bundle 冻结；且 B-01/B-02/B-05 是功能缺口，不是纯样式。
2. B-04：错误来自 server `commands.clj` 的英文文案与 browser bridge 自动请求；不在本区域文件范围。
3. U-06：需要视图条件渲染。
4. 实时截图与 360/820/200% 复验：等待锁协调；不在锁内启动 app/Chrome。

## 7 复验计划（拿到锁后，单段 ≤5 分钟）

1. 启动 app（`-Xmx768m`，固定端口，`-Djava.io.tmpdir` 隔离单例），记录 PID/RSS、`jcmd VM.flags`、`GC.heap_info`。
2. 一个 Chrome，1280×800 浅色：首页 → 设置供应商 → 首次配置 → 新建本地连接 → 添加密钥 → 保存 → 模型与角色 → 外观（浅/深、80/100/200%）→ 数据中心 → 快捷键。
3. 360×800 与 820×1180 各一张设置页；记录横向溢出元素。
4. 停止 app/Chrome，释放 flock；检查 PPID=1 遗留。
5. 复验后把截图写入 `plans/evidence/visual-audit/startup/`，更新本文状态与 CSS hash。

## 8 证据文件

- `plans/evidence/visual-audit/startup/static-findings.json`：bundle/CSS 哈希、42 个缺失类名、静态发现。
- 既有截图：`plans/evidence/bundle-health/home.png`、`plans/evidence/bundle-health/settings.png`、`plans/evidence/no-model/no-model.png`、`plans/evidence/no-model/no-model-dom.html`、`plans/evidence/e2e-auto/e2e01/e2e01-03-onboarding.png`、`e2e01-04-connection-saved.png`、`e2e01-05-connection-tested.png`、`e2e01-06-model-bound.png`、`plans/evidence/e2e-regression-01-02-22/e2e01-settings.png`。

## 9 final11 重绑（bundle `72de4f9a` / 源码树 `7dfcf0d7`（final12；原 ab801450 已前冻结），CSS `332f9fd3` 未变）

- §2/§8 的既有截图（bundle-health、no-model、e2e01-*）均绑更早构建（`5d36ac81`/`a8acf565` 时代），无最终冻结证据。
- final11 复验（证据 `plans/evidence/visual-audit/final11/`，bundle `72de4f9a` + 树 `7dfcf0d7`，CSS `332f9fd3`）：设置 1280 一张（`shots/40-settings-1280`），含外观分区入口。首页引导、供应商三步向导、360/820/200% 复验仍未跑，不宣称 PASS。
