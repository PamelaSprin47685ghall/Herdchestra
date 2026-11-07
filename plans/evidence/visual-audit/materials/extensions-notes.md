# 材料页扩展能力区：世界书 / 指令卡 / 技能 审查笔记

日期：2026-11-06
审查人：w18-visual-materials.MaterialsExtensions
范围：`src/hc/client/materials/view.cljc`（只读审查）、`src/hc/client/extensions/view.cljc`（可改）、`src/hc/client/extensions/model.cljc`（只读）、`src/hc/client/workbench/model.cljc`（只读）。

## 0. 证据状态（必须先读）

**本轮没有产出真实截图。** `plans/evidence/visual-audit/materials/extensions/` 为空。原因：

1. 第一次启动被全局 app 单实例锁挡住（`/tmp/hc-app-single.lock` 被其他 worker 持有），300 秒超时。
2. 第二次启动因未设置独立 `-Djava.io.tmpdir`，与默认 app 的 `instance.lock` 冲突，app 不打印 listening URL，再次超时。
3. 修正 tmpdir 后排队等待全局锁约 7 分钟，主 agent 于 11:0x 明确要求撤下排队作业、停止启动 app/Chrome，改为静态审查。

因此下文除特别注明外，全部是**静态代码审查**，不是浏览器观察结果。凡涉及“看起来/宽度/颜色/热区”的判断均标注 `[静态]`，不得当作截图证据。主 agent 已用真实 1280 数据完成世界书/指令卡的修复；本文件中的世界书/指令卡条目是**修复前代码**的静态清单，若与当前文件不一致，以当前文件为准。

已做的验证：`hc.client.extensions.view` 在 JVM 下 `require` 通过（`:compiled-ok`）；`clojure -M:test -n hc.client.extensions-test` 运行 34 个测试、116 个断言，0 失败 0 错误。本轮未单独重建 CLJS bundle；W17 最终重建的 `dd968793` 已包含本轮 `extensions/view.cljc` 修改，w9 已将其确认为最终冻结（见第 6 节）。行号基于 2026-11-06 11:05 工作区版本（`materials/view.cljc` 941 行，`extensions/view.cljc` 706 行）。

## 1. 已修项（`src/hc/client/extensions/view.cljc`）

| 编号 | 问题 | 修复 |
|---|---|---|
| E-FIX-1 | 技能安装表单的“助手”下拉没有空选项，`draft :assistant-id` 初始为 `""`。HTML 会显示第一个助手，但 state 仍是空；用户点卡片“绑定助手”时 reducer 报“先填写助手 ID”。 | 下拉前置 `["选择助手…" ""]`；绑定/解绑按钮在目标为空时禁用，并在 title 说明。 |
| E-FIX-2 | 技能卡片只显示“绑定 N”，N=0 时仍可点“解除绑定”，且按钮没有目标提示。 | N=0 显示“未绑定”并禁用解绑；绑定/解绑 title 显示目标助手；绑定数改为“绑定 N 个”。 |
| E-FIX-3 | `(str (text (:size meta)) " B · " ...)` 在 size 缺失时输出“ B · 文本”。 | size 存在时才输出大小。 |
| E-FIX-4 | 两处 `<input type="file">` 没有可读名称，屏幕阅读器只读“选择文件”。 | 加 `aria-label`：“选择技能压缩包”“选择上游技能压缩包”。 |
| E-FIX-5 | `diff-row` 的 `(get labels status (name status))` 中默认值参数会被急切求值；status 为 nil 时 `(name nil)` 抛 NPE。 | 先算安全 label，再取 tone。 |
| E-FIX-6 | 顶层错误一律走 `skill-source-error-label`，MCP 错误没有 message 时会显示“技能源无法读取”。 | 新增 `panel-error-label`：只有 `:code` 以 `SKILL_` 开头才用技能源文案，其余保留原 message。 |
| E-FIX-7 | 三处 `(sort-by :name ...)` 在 name 为 nil 时 `compare` 抛异常。 | 新增 `by-name`，用 `(str/lower-case (str (:name item)))`。 |
| E-FIX-8 | 未同步时 `:version` 为 0，toolbar 仍显示“catalog v0”。 | 只在 `:ready?` 且 version 为整数时显示。 |
| E-FIX-9 | toolbar 的“连接 N”语义不清（N 是 MCP 连接数）。 | 改为“MCP 连接 N”。 |
| E-FIX-10 | “新增服务器”“预览导入”“导入并保持停用”“比较更新”“安装技能”“应用并保留本地”“采用上游”在 disabled 时 title 未覆盖 pending/未就绪/源为空；其中 `cond` 分支返回布尔 true，React 会把 title 渲染成 "true"。 | 所有 disabled 按钮的 title 统一用 `cond` 覆盖“未同步 / 命令提交中 / 输入缺失”三种原因，并显式 `:else nil`。 |
| E-FIX-11 | “本轮实际可用”不准确：技能按会话生效，不是按轮次。 | 改为“当前会话实际可用”。 |
| E-FIX-12 | 会话技能区与技能区都显示“尚无技能”，重复。 | 会话技能区改为“没有可选的技能 / 安装并启用技能后，这里会出现可勾选项”。 |
| E-FIX-13 | `button` 的 `:title` 只在 disabled 时设置，非禁用态无法说明绑定目标。 | `:title` 无条件透传；调用方自己用 `when` 控制。 |
| E-FIX-14 | “助手（绑定/解绑）”放在安装表单里，容易被理解为安装时绑定。 | 改标签为“绑定目标助手”，hint 写明只用于卡片上的绑定/解绑。 |
| E-FIX-15 | “未选择会话”空态没有告诉用户去哪选。 | 改为“在工作台左侧选择一个会话后，这里会显示技能勾选。” |

## 2. 扩展页（技能 / MCP）未修问题

| 编号 | 页面/位置 | 复现与判断 | 严重度 | 状态 |
|---|---|---|---|---|
| EXT-01 | 技能卡片“删除” | 点“删除”立即提交 `:extensions/skill-delete`，没有二次确认；对比世界书/指令卡都有 `confirm-delete?`。误触会直接删技能目录。`view.cljc:485-488`，`model.cljc:698`。 | 高 | 未修（需要 model 增加本地确认状态，超出“小问题”） |
| EXT-02 | MCP 服务器卡片“删除” | 同样立即提交 `:extensions/mcp-remove`，无确认。`view.cljc:213-216`。 | 高 | 未修（同上） |
| EXT-03 | 每张技能卡片内的“上游更新与三方对比” | 每张卡都渲染完整表单（来源 select + 文本框 + 三个按钮 + diff 列表）。技能多时页面极长、重复标题。`view.cljc:357-435`、`skill-card` 末尾。 | 中 | 未修（建议 `<details>` 折叠；需要真实视觉确认展开态） |
| EXT-04 | “采用上游”按钮 | 用 `danger?` 红色，与“删除”同色；它只是覆盖本地文件，不是删除。`view.cljc:415-419`。 | 低 | 未修（颜色语义，CSS/设计决定） |
| EXT-05 | 会话技能区 blocked/excluded | 只显示“条件未满足：N 个”“未生效：N 个”，不列名称，用户不知道是哪个技能。`view.cljc:598-604`。 | 中 | 未修 |
| EXT-06 | 技能文件列表 | `.hc-ext-files` 无 max-height/滚动，文件多时卡片很长。`view.cljc:487-502`。 | 低 | 未修（CSS） |
| EXT-07 | 复选框热区 | `.hc-ext-check` 只有 6px gap + 13px 复选框 + 0.84em 文本，`[静态]` 高度约 18-20px，低于 32px 最小触控目标。`workbench.css:1795-1800`。 | 中 | 未修（CSS 归主 agent） |
| EXT-08 | 360 宽卡片按钮 | `.hc-ext-card-actions` 是 `justify-content:flex-end` + `flex-wrap`，换行后按钮右对齐、左侧留白参差。`workbench.css:1730-1736`。 | 低 | 未修（CSS） |
| EXT-09 | 更新对比结果 | 比较后没有“清除对比”入口；应用后 `applied?` 常驻“已应用，待复核”，直到下一次 view。`model.cljc:512-516`。 | 低 | 未修 |
| EXT-10 | MCP 卡片“测试/打开” | 文案把两个动作挤在一个按钮；实际只发 `:extensions/mcp-open`。`view.cljc:201-204`。 | 低 | 未修（需确认产品语义） |
| EXT-11 | 技能绑定信息 | 卡片只显示绑定数量，不显示绑定到哪些助手；解绑需要用户记得目标 ID。`view.cljc:457-463`。 | 中 | 未修 |
| EXT-12 | 会话技能行 | `reason` 同时作为 checkbox 的 title 和下方段落，重复朗读/重复占位。`view.cljc:507-540`。 | 低 | 未修 |
| EXT-13 | MCP 导入 | 预览候选列表没有“选择/全选”，`mcp-import-apply` 会导入全部候选。`view.cljc:262-282`。 | 中 | 未修（功能设计） |
| EXT-14 | 顶层错误 | 没有“关闭”按钮；只有下一次成功 view 才清 `last-error`。`view.cljc:684-688`。 | 低 | 未修（model 已有 dismiss，未接线到扩展面板） |
| EXT-15 | 扩展页切换会话 | 客户端“切换会话清空上一会话 scope”的修复源码时间为 11:15:28，已包含在最终冻结 `dd968793`（11:19:25）。需真实复验：切换会话后会话技能区是否立即清空上一会话的 `:session-scope`/`:resolved-skills`，而不是短暂显示旧勾选。 | 中 | 已冻结；待视觉复验 |

### 2.1 技能安装流程静态核对

| 检查项 | 结论 |
|---|---|
| 来源切换（文本/链接/压缩包） | 正确。`skill-form` 用 `case kind` 分别渲染 SKILL.md textarea、URL input、file input；`skill-update-section` 同样。`model/source-kind` 把 select 字符串归一为 keyword。 |
| 文本安装预览 | **没有预览。** `:extensions/skill-install` 直接入队安装；UI 没有“预览”按钮或候选区。对比 MCP 导入有 preview/apply 两段。若产品要求先预览 SKILL.md 解析结果，这是功能缺口。 |
| 安装后默认未启用 | UI 只根据服务端 `:enabled?` 显示；客户端不伪造启用。静态无法验证服务端默认值，需真实快照。 |
| 空提交 | “安装技能”在 `ready-source?` 为 false 时 disabled，title 提示“先填写技能来源”；不会发出空命令。 |
| 非法 URL | 客户端只校验非空（`skill-draft->source`），不校验 scheme/host；非法 URL 会发到服务端，由 `:SKILL_SOURCE_PRIVATE_ADDRESS` 等错误回执显示。 |
| 压缩包越界路径 | UI 只把文件读成 base64，不解析 zip；越界路径拦截属于服务端 `skill/install` 的责任，本轮未做真实构造。 |
| 更新对比入口 | 每张技能卡片内都有“上游更新与三方对比”；比较后显示 diff 行与冲突数。服务端无上游更新时不会显示“无更新”空态，只显示空 diff 列表（`paths` 为空则整块不渲染）。 |

## 3. 世界书（只读，修复前静态清单）

| 编号 | 位置 | 问题 | 严重度 |
|---|---|---|---|
| WB-01 | `materials/view.cljc:445-447`、`493` | 列表和编辑器显示原始 `assistant-id`，而表单 select 有 `:label`；用户看到 `asst_01H...` 而不是助手名。 | 中 |
| WB-02 | `530` 与 `552` | “当前会话计数”外层是 `.hc-mat-runtime`，内层“命中但未提供”又套一个 `.hc-mat-runtime`；`[静态]` 会形成双边框/双 padding。 | 低 |
| WB-03 | `682-689` | “位置深度”无条件显示；只有 `:between-recent` 位置使用深度，其他位置是无关字段。 | 低 |
| WB-04 | `424` 与 `464` | 世界书列表标题和编辑器标题各有一个“新建”，两个按钮同 action，重复。 | 低 |
| WB-05 | `519-525` | 删除确认按钮与“保存世界书”同处一个 `.hc-mat-actions` 行；确认态只多一个“取消”，误触风险高。 | 中 |
| WB-06 | 条目编辑器“移动到” | 选项包含当前书自身，允许“移动到同一本书”。 | 低 |
| WB-07 | `.hc-mat-list`（CSS 1423） | 列表 `max-height:34rem` 独立滚动；三列布局下与页面滚动叠加成嵌套滚动。 | 低 |
| WB-08 | `527-528` | 绑定/解绑按钮与“保存世界书”同一行；已绑定时“解绑”没有确认。 | 低 |

已核实不是 bug：`selected-book-assistant-id` 会回退到已绑定/第一个可选助手，select 显示值与绑定按钮传值一致（`materials/model.cljc:670-690`）。

## 4. 指令卡（只读，修复前静态清单）

| 编号 | 位置 | 问题 | 严重度 |
|---|---|---|---|
| IC-01 | `workbench/model.cljc:950-974`、`conversation/view.cljs:1619` | `send-guard` 已实现格式冲突/必填占位符校验，但**全仓没有调用者**；`composer-view` 的 `send-disabled?` 只看 `(:disabled? send)` 与 fix-intent。结果是材料页写着“发送前必须只保留一种格式”，实际发送前不提示、不阻止，直到服务端组装上下文才拒绝。 | 高 |
| IC-02 | `materials/view.cljc:900-906` | “输出格式”是自由文本 input，没有枚举/datalist；用户输入 `JSON`、`md` 等非法值只能等服务端拒绝。 | 中 |
| IC-03 | `768`、`793` | 卡片列表和勾选清单显示 `(name format)` 原始英文（`json`/`text`），与中文界面混排。 | 低 |
| IC-04 | `745` 与 `876` | 指令卡列表和编辑器各一个“新建”，重复。 | 低 |
| IC-05 | `913-919` | 删除确认与“保存指令卡”同一行，误触风险。 | 中 |
| IC-06 | `840-864` | “仅本轮”和“本会话持续”各自维护勾选集合；切换后另一集合不可见，也没有数量提示。文案有解释，但用户容易以为选择丢了。 | 低 |
| IC-07 | `800-815` | 占位符输入 label 直接拼卡片名；长名称在窄屏会被截断。 | 低 |
| IC-08 | `851-857` | 冲突/缺失提示在 `.hc-mat-selection` 底部；卡片列表长时需要滚动才能看到，没有 sticky。 | 低 |
| IC-09 | `775-785` | “仅本轮/本会话持续”按钮在 pending 时不 disabled（materials 的 mode-button 没有 pending 参数），仍可切换。 | 低 |

已核实：占位符解析支持 `key | label | required`，第三列 `true/yes/1/!/required` 均识别（`materials/model.cljc:978-996`）。

## 5. 复验建议（主 agent）

1. 最终冻结 `dd968793`（11:19:25）已包含本轮 `extensions/view.cljc` 修改，以及 11:15:28 的“切换会话清空上一会话 scope”修复；w9 已于 11:26 确认冻结（11:26–11:41 窗口内不写 `src/**`/`test/**`、不重建、不跑 E2E）。W17 已核对 `绑定目标助手`=2、`当前会话实际可用`=1；会话区第二段证据仍绑定 `a8acf565`（w21 要求不绑定 `4652ef26`），扩展页不受此限制。后续若再改源码，重建前按 w9 的冻结请求在 IRC 公布新 hash，并确认 `该会话的命令 epoch 尚未同步`、`本会话可用`、`select-skills`、`当前会话实际可用` 四个标记仍在。
2. 至少复验扩展页 1280 与 360：技能卡片“绑定助手”在未选目标时应禁用并有 title；选择目标后 title 显示“绑定到 <id>”；“解除绑定”在 0 绑定时禁用。
3. 复验世界书/指令卡的 1280/820/360、浅/深色、80%/200% 字号；重点看 `.hc-mat-split-3` 三列在 1280 的宽度、`.hc-mat-runtime` 嵌套边框、`.wb-tabs` 8 个标签在 360 的换行高度。
4. 复验发送前提示：勾选两张不同 output-format 的卡，在会话页发送；预期当前仍无客户端提示（IC-01），服务端拒绝后才出现错误。
5. 复验 `.hc-ext-check` 的实际热区高度与 360 下卡片按钮换行（EXT-07/08）。

## 6. Bundle 哈希

- 任务给出的旧基线：main.js `67da1cceac3367858dc2bc5103e5b7daf94b082e69c020575a5dd8ea09e768b6`，CSS `77da0dd42f787c7f9b8012df796006d19353b6c22af717018ab6a65d6fff2a57`。
- 最终冻结（W17 重建，w9 于 11:26 确认）：main.js `dd968793cc74919e139f31bb290a5dfb68309c6d9a78acfd766a4d984f2fda4b`（mtime 11:19:25，size 3,007,964），CSS `dda62554c9bb0b2643c0c437a1368f56b021f716001bcefa3f5cf92a32fa44ca`（mtime 11:13:59）。
- 包含关系：`extensions/view.cljc` mtime 11:05:36，早于本次构建；在 `main.js` 中可 grep 到 `选择助手…`、`绑定目标助手`、`该技能尚未绑定助手`、`先填写要解绑的助手 ID`、`没有可选的技能`、`MCP 连接`、`当前会话实际可用`、`已应用，待复核`，说明 E-FIX-1..15 已进入最终冻结。本轮**未单独重建**；不存在“源码已改但 bundle 未构建”的缺口。
- 已过时的中间 hash（仅用于说明基线漂移）：`4652ef26…`（11:17:42）、`a8acf565…`（11:13:53）、`5d36ac81…`（11:07:16）、`88e1167f…`、CSS `b10efb75…`、`3328e8ff…`。
