# UI 合同审计（0.0.1）

- 日期：2026-11-05
- 范围：根 `AGENTS.md` §7.4 旅程 8（设置与诊断）（含 §7.7 的 24 类失败文案）与其他硬性 UI 合同。
- 约束：只读审计；未改 `src/`；未执行 Git 写操作。只新增本报告。
- 判定：**符合** = 有标准文案与主操作；**偏差** = 有部分文案/操作但不符合合同或只覆盖单一路径；**缺失** = 除诊断类别标签外没有用户可见文案/入口。

## 1. 24 类失败文案矩阵

统计：**符合 10 / 偏差 13 / 缺失 1**（6）。9、10、24 已从缺失移到偏差：设置测试等待、容量决策层与导出取消纯逻辑已接线，但聊天发送/等待与导出视图仍待接线。

| # | 类别 | 实现位置 | 实际用户可见文案 | 主操作 | 判定 |
|---:|---|---|---|---|---|
| 1 | 尚未选模型 | `src/hc/client/conversation/composer.cljc:334`；`surface.cljc:180` | “先选择一个可用模型，草稿已保留” | 选择模型 | 符合 |
| 2 | 供应商未启用 | `composer.cljc:240` | “所选连接已停用；请启用连接或选择其他模型，草稿已保留” | 启用连接/选择模型 | 符合 |
| 3 | 模型被移除 | `composer.cljc:246`；`settings/catalog.cljc:610` | “所选模型已被移除；请选择其他模型，草稿已保留”；列表“原模型已不可用（…）；历史仍保留” | 选择替代模型 | 符合 |
| 4 | 凭据无效 | `settings/connection.cljc:56`；服务端 `app/settings.clj:371-380` | “认证失败”；测试层另有英文 `authentication rejected` | 更新凭据/测试连接 | 偏差：文案不是“服务没有接受这组凭据”，且中英混用 |
| 5 | 账号授权过期 | `client/model_auth.cljc:396,662` | “授权已过期，请重新开始。”/“账号登录未完成” | 重新开始授权 | 符合 |
| 6 | 额度不足 | 仅 `diagnostics/view.cljs:1305` 类别标签；搜索有 `search/model.cljc:67` | 模型供应商侧没有专用文案 | 无 | 缺失：模型额度只落到通用错误/诊断标签 |
| 7 | 服务限流 | `settings/connection.cljc:54-55,366-370` | “限流冷却中；下次可尝试 …”；同时认证失败会并列 | 等待后重试 | 偏差：没有“已保留本轮内容”，也不是对话内文案 |
| 8 | 网络不可达 | `composer.cljc:337`；服务端 `app/settings.clj:371-380` | “网络尚未连接，草稿已保留”；测试层英文 `model request timed out` 等 | 检查连接/稍后重试 | 偏差：把“传输未连接”和“服务不可达”混在同一句，服务层文案为英文 |
| 9 | 响应迟迟未到 | `settings/model.cljc` 的 `test-view` 等待文案；`diagnostics/view.cljs:1308` 类别标签 | “服务尚未返回内容；可以继续等待或停止当前生成。”并显示已等待秒数 | 停止当前测试/继续等待 | 偏差：设置测试等待已接线，聊天等待未接线 |
| 10 | 输入超出容量 | `settings/catalog.cljc:272-330` 的 `capacity-warning` | `failure-copy :input-too-large`：“输入超出容量”/“发送前会阻止”；接近上限时“内容接近模型容量” | 调整内容范围/压缩历史/换模型（返回 actions 与 `:block?`） | 偏差：决策层已接线，聊天发送路径仍待接线 |
| 11 | 附件类型不支持 | `conversation/attachments.cljc:685-695` | 卡片“不支持”/“无法发送”；无“当前模型不能接收这类附件” | 换模型/改用提取文本/排除 | 偏差 |
| 12 | 部分资料不可读 | `attachments.cljc:695-740` | 卡片“准备失败”/“文字提取失败”；无“已读入部分内容，仍有资料未成功处理” | 查看缺失部分 | 偏差：只有单项失败，没有部分成功汇总 |
| 13 | 本地空间不足 | `workbench/events.cljc:254-263` | “空间不足，暂时无法保存更多内容。请释放空间或先导出草稿。（未确认保存；COMMAND_STORE_FAILED）” | 释放空间/导出草稿 | 符合 |
| 14 | 本地资料不可读 | `backup/model.cljc:390-391`；启动阻塞在 `server/app.clj:76-90` | “维护日志无法读取；不能判断恢复结果…”；启动是 CLI blocked，不是独立恢复页 | 查看恢复选项 | 偏差：没有 §7.3 要求的独立恢复页 |
| 15 | 外部文件已移动 | `artifacts/view.cljs:113`“重新定位”；`backup/view.cljc:317,854`“需要重新定位” | 没有“找不到原位置的文件” | 重新定位/移除链接 | 偏差 |
| 16 | 权限被撤销 | `browser/model.cljc:623`“当前标签页未授权…”；`miniapps/view.cljs:853`“尚未授权…” | 没有“这项操作需要的权限已关闭” | 恢复权限/选择其他方式 | 偏差 |
| 17 | 工具执行失败 | `conversation/view.cljs:370-381` 过程块显示状态 | 只有状态标签，无“这一步没有完成，已完成的步骤仍保留” | 查看原因/后续选择 | 偏差 |
| 18 | 结果回执丢失 | `backup/model.cljc:466-472`“提交结果未知…检查目标状态”；`surface.cljc:95`“停止请求未送达” | 备份有正确文案；普通命令说“未送达”，可能被理解为未执行 | 检查目标状态 | 偏差：只覆盖备份，普通命令语义偏“未执行” |
| 19 | 任务错过时间 | `schedule/model.cljc:68,1127`；`schedule/view.cljs:286` | “错过的计划记为未运行，不会集中补跑”；“立即运行”是独立手动记录 | 手动运行/看下次时间 | 符合 |
| 20 | 备份密码不正确 | `backup/model.cljc:295-297` | “无法解锁此备份，当前资料没有改变。请重新输入密码。” | 重新输入密码 | 符合 |
| 21 | 导入格式不支持 | `backup/model.cljc:300-307` | “无法识别这份资料的格式/容器。当前资料没有改变。” | 查看支持范围 | 符合 |
| 22 | 恢复发生冲突 | `backup/model.cljc:435-437` | “恢复发生冲突”/“冲突选项无效；没有写入任何资料…” | 查看冲突并决定 | 符合 |
| 23 | 版本过新 | `backup/model.cljc:310-312` | “此资料由较新的格式保存。请更新应用后再打开；原文件没有被改写。” | 更新应用/保留原文件 | 符合 |
| 24 | 导出被取消 | `conversation/export_plan.cljc` 的取消状态机；`diagnostics/view.cljs:1323` 类别标签 | “导出被取消”/“没有生成最终导出文件”；`:preparing` 立即取消，`:writing` 等待写入边界 | 重新导出/返回 | 偏差：纯逻辑已实现，导出视图未接线 |

### 1.1 三类混淆检查

- **认证失败 / 无权限 / 找不到模型**：设置连接用 `认证失败`，模型列表用 `原模型已不可用`，服务端测试层能区分 401/403/404，但测试结果详情直接使用英文 `authentication rejected`/`access denied`/`model or endpoint not found`。结论：分类逻辑存在，用户文案未统一，属于**偏差**。
- **未知用量显示为 0**：诊断页明确“未知用量/未知单价不会显示为 0”，见 `diagnostics/view.cljs:7-8,570-571,611`；结论：**符合**。
- **失败写成成功**：备份恢复文案反复声明“当前资料没有改变/没有宣称保存成功”，见 `backup/model.cljc:231,456`；结论：**符合**。但 composer 的“草稿已保留”在本地存储尚未确认时也会出现，可能把“仍在输入框”写成“已保留”，属于**偏差**，建议改为“草稿仍在输入框”或等 `:save-state :saved` 后再用“已保留”。

## 2. 其他硬性 UI 合同

| 合同 | 实现位置 | 状态 | 说明 |
|---|---|---|---|
| Ctrl/Cmd+F 页内查找 | `workbench/main.cljs:226-240`；`conversation/view.cljs:1490-1504,1992-2055` | **已实现** | 打开查找条、显示 `N 个命中`、上一个/下一个、关闭；无命中显示 `0 个命中` |
| Ctrl/Cmd+Shift+F 全局搜索 | `workbench/main.cljs:226-240` | **部分** | 只导航会话并聚焦当前会话过滤，同时提示“全局搜索尚未接入服务器索引”；不是真正的全局索引 |
| Escape 层级 | `conversation/a11y.cljc:113-137`；`workbench/main.cljs:255-300` | **已实现** | 最上层弹层 → IME 组合 → 文本/流选区 → 停止生成；设置/看板保留自己的 Escape |
| 备份 7+4+3 恢复点 | `server/backup/recovery.clj:42-115`；`client/backup/view.cljc:1062,1209,1215` | **已实现** | 最近 7 个成功点 + 4 周 + 3 月代表副本；运行中依赖的恢复点受保护 |
| 诊断包脱敏预览 | `client/diagnostics/view.cljs:1400-1440`；`server/diagnostics/bundle.clj:308-352` | **已实现** | 预览列出脱敏字段/占位符、可删行、隐藏域名/路径、昵称替换；不自动上传 |
| 对比度 4.5:1 / 3:1 | `client/appearance.cljs:15-33`；`appearance/model.cljc:10` | **部分** | 有高对比度开关与颜色变量，但没有实测比率或自动校验；不能宣称已验证达标 |
| 字号 80%–200% | `appearance/model.cljc:12-13,48-53`；`appearance/view.cljc:92-119` | **已实现** | 滑杆/加减按钮、ARIA 值与百分比显示 |
| 触摸热区 44×44 / 桌面 32×32 | `resources/public/css/workbench.css:900-905` 等多处 44/32 规则 | **部分** | 多个关键控件有 44/32 规则，但没有逐控件审计；不能宣称全部满足 |
| 导出纯文本 / Markdown | `conversation/view.cljs:551-578,1955-1983`；`conversation/export.cljc:223-255` | **已实现** | 当前分支/选中消息的纯文本与 Markdown 导出；下载失败有可见提示 |
| 导出长图 | 全仓库未找到长图/整段截图导出实现 | **缺失** | 只有浏览器页面卡截图，不是会话长图导出 |
| 迷你地图 / 话题概览 | 全仓库未找到 minimap/topic overview 实现 | **缺失** | 长会话定位合同未实现；只有页内查找与“回到最新” |

## 3. 最小修复建议（按层）

| 缺口 | 层 | 最小修复 |
|---|---|---|
| 6/9/10/24 缺标准文案 | client | 建立与 §7.7 一一对应的错误码→文案/动作表；模型配额、响应超时、输入超容量、导出取消都走同一张表 |
| 4/8/17/18 文案偏差 | client + server | 服务端测试/模型错误返回结构化 code，client 只显示标准中文；普通命令“未送达”区分“未执行”与“可能已执行” |
| 10 容量发送门未接线 | client | 在 composer/send 路径调用 `catalog/capacity-warning` 的 `:block?`/`:copy`，把 `:actions` 渲染为压缩/调整/换模型 |
| 14 独立恢复页 | client + server | 启动 blocked 结果提供浏览器恢复入口；展示恢复点、备份、导出诊断、重试与“保留原文件” |
| 迷你地图/话题概览 | client | 基于消息锚点/上下文边界生成可键盘操作的话题列表，点击定位到消息而非像素 |
| 长图导出 | client | 新增整段会话导出（离屏渲染或分段 canvas），并复用下载失败提示 |
| 全局搜索 | client + server | 若未接服务器索引，快捷键文案改为“会话过滤”或接入真实全局索引；不能把过滤冒充全局搜索 |
| 对比度/热区 | client + docs | 用自动对比度检查与关键控件清单验证 4.5/3 与 44/32，把实测结果写入 HACK/发布说明 |

## 4. 最需要修的前 5 项

1. **24 类错误文案统一表**：6 仍缺失，9/10/24 只完成设置/纯逻辑层接线，4/8/17/18 仍有偏差；当前大量类别只有诊断标签。
2. **输入超容量警告接线**：`capacity-warning` 已写好但没有 UI 调用方，用户看不到“发送前会阻止”。
3. **本地资料不可读的独立恢复页**：目前只有备份维护日志/CLI blocked，缺少 §7.3 的浏览器恢复入口。
4. **迷你地图/话题概览**：长会话定位合同完全缺失。
5. **长图导出与导出取消**：纯文本/Markdown 已有，长图和取消都没有用户入口。

## 5. 证据索引

- `src/hc/client/conversation/composer.cljc`、`surface.cljc`、`conversation.cljc`、`draft.cljc`
- `src/hc/client/settings/connection.cljc`、`settings/catalog.cljc`、`settings/view.cljc`
- `src/hc/client/model_auth.cljc`
- `src/hc/client/diagnostics/view.cljs`、`server/diagnostics/bundle.clj`
- `src/hc/client/backup/model.cljc`、`backup/view.cljc`、`server/backup/recovery.clj`
- `src/hc/client/schedule/model.cljc`、`schedule/view.cljs`
- `src/hc/client/workbench/events.cljc`、`workbench/main.cljs`
- `src/hc/client/conversation/a11y.cljc`
- `src/hc/client/appearance.cljs`、`appearance/model.cljc`、`appearance/view.cljc`
- `resources/public/css/workbench.css`
- `src/hc/client/conversation/export.cljc`、`conversation/view.cljs`

## 6. 发布说明复核

`plans/release-notes-0.0.1.md` 已加入属性测试现状：85/104 模块有至少一个属性测试文件引用（60 个属性测试文件，134 个 defspec + 36 个 quick-check），其余 8 个确定性模块与 11 个无直接测试模块按 `plans/property-test-coverage-0.0.1.md` 分类，并明确“不宣称全部覆盖”。无需再改发布说明的覆盖数字；若后续代码变化，按该报告重算。

## 7. 24 类文案单一事实来源与接线清单

新增模块：`src/hc/client/copy/failures.cljc`（纯函数 `failure-copy`）。
输入 `{:code <error-code> :context {...}}`，输出
`{:title :body :primary-action {:label :command} :secondary-action ... :severity}`。
覆盖 24 类；认证失败、无权限、模型移除是三个独立 kind；未知用量使用
“用量未知，不按零计算”；`已保留` 只在 `:saved? true` 时追加。
测试：`test/hc/client/copy/failures_test.clj`，`5 tests / 179 assertions`，
`-X:test:client :nses '[hc.client.copy.failures-test]'` 退出 0。
组合验证（failures + settings copy/catalog/view/model + backup copy/model +
diagnostics copy）：`118 tests / 712 assertions`，0 failures / 0 errors，退出 0。

接线时**不要**改现有 view/events 文件（client-audit 正在改）；以下是交给
接线者的替换位置与上下文：

| 类别 | 当前文案位置（待替换/调用） | `:context` 字段 | 目标命令 |
|---|---|---|---|
| 未选模型 | `conversation/composer.cljc:334`；`conversation/surface.cljc:180` | `{:saved? draft-saved?}` | `:settings/select-model` |
| 供应商停用 | `composer.cljc:240` | `{:saved? :connection-id}` | `:settings/open-connection` |
| 模型移除 | `composer.cljc:246`；`settings/catalog.cljc:610` | `{:saved? :connection-id :model-id}` | `:settings/select-model` |
| 凭据无效 | `settings/connection.cljc:56`；`server/app/settings.clj:371-380` | `{:connection-id :credential-id}` | `:settings/update-credentials` |
| 授权过期 | `model_auth.cljc:396,662` | `{:connection-id :provider-id}` | `:model-auth/restart` |
| 额度不足 | `diagnostics/view.cljs:1305`；`search/model.cljc:67` | `{:connection-id :unknown-usage?}` | `:settings/open-connection` |
| 服务限流 | `settings/connection.cljc:366-370` | `{:retry-at :saved?}` | `:command/retry-after` |
| 网络不可达 | `composer.cljc:337`；`server/app/settings.clj:371-380` | `{:saved? :endpoint}` | `:settings/test-connection` |
| 响应未到 | `settings/model.cljc` 的 `test-view`（设置测试等待已接线）；聊天等待待接线 | `{:run-id :elapsed-ms}` | `:command/stop-run` |
| 输入超容量 | `settings/catalog.cljc:272` 的 `capacity-warning`（决策层已接线；发送门待接线） | `{:estimated-tokens :capacity :saved?}` | `:composer/adjust-content` |
| 附件不支持 | `conversation/attachments.cljc:685-695` | `{:mime :model-id :saved?}` | `:settings/select-model` |
| 部分不可读 | `attachments.cljc:695-740` | `{:read-count :missing-count :saved?}` | `:attachments/inspect-missing` |
| 空间不足 | `workbench/events.cljc:254-263` | `{:saved? false}` | `:app/free-space` |
| 资料不可读 | `backup/model.cljc:390-391`；启动 blocked `server/app.clj:76-90` | `{:data-dir :recovery-points}` | `:app/open-recovery` |
| 外部文件移动 | `artifacts/view.cljs:113`；`backup/view.cljc:317,854` | `{:path :workspace-id}` | `:workspace/relocate` |
| 权限撤销 | `browser/model.cljc:623`；`miniapps/view.cljs:853` | `{:permission :scope}` | `:permissions/restore` |
| 工具失败 | `conversation/view.cljs:370-381` | `{:tool-name :call-id :completed-steps}` | `:conversation/open-process` |
| 回执丢失 | `backup/model.cljc:466-472`；`surface.cljc:95` | `{:command-id :target}` | `:command/check-target` |
| 错过时间 | `schedule/model.cljc:68,1127`；`schedule/view.cljs:286` | `{:schedule-id :next-run}` | `:schedule/run-now` |
| 备份密码错 | `backup/model.cljc:295-297`；`error-view` 已接共享 copy | `{:backup-id}` | `:backup/reenter-password` |
| 导入格式不支持 | `backup/model.cljc:300-307`；`error-view` 已接共享 copy | `{:format :source}` | `:backup/supported-formats` |
| 恢复冲突 | `backup/model.cljc:435-437`；`error-view` 已接共享 copy | `{:conflict-count}` | `:backup/review-conflicts` |
| 版本过新 | `backup/model.cljc:310-312`；`error-view` 已接共享 copy | `{:format-version}` | `:app/update` |
| 导出取消 | `diagnostics/view.cljs:1323`（无实际 UI） | `{:export-id}` | `:export/restart` |

### 7.1 已接线与仍待接线

已接线：

- **容量决策**：`settings/catalog.cljc` 的 `capacity-warning` 现在返回
  `:block?`、`:copy`（`failure-copy :input-too-large`）与 actions；容量已知
  且超限时 `:block? true`，接近 90% 时 `:near? true` 并给温和提示。
- **设置测试等待**：`settings/model.cljc` 的 `test-view` 返回
  `:waiting-copy`、`:elapsed-ms`、`:elapsed-label`；`settings/view.cljc` 的
  测试面板渲染“服务尚未返回内容…已等待 N 秒”，不再写“正在思考”，并提供
  取消测试按钮。
- **备份错误**：`backup/model.cljc` 的 `error-view` 对备份密码错、格式不支持、
  恢复冲突、版本过新使用共享 copy 的 title/body/action，并保留 `:copy`。

仍待接线：

1. **聊天发送的容量门**：`conversation/composer.cljc`/`workbench` 的 send
   决策需要调用 `capacity-warning` 的 `:block?` 与 `:copy`，把
   `:composer/adjust-content` 渲染为“调整内容范围”；本次按约束未改
   conversation/workbench。
2. **导出取消**：当前 `conversation/view.cljs` 的导出是同步下载，没有取消
   状态。接入点在导出状态机：增加 `:export/cancel` 动作与
   `{:export-id}` 上下文；取消后调用 `failure-copy` 的
   `:export-cancelled`，主操作 `:export/restart`，次操作 `:app/back`。
3. **聊天等待**：`conversation/surface.cljc` 的流等待状态仍需接
   `:response-timeout` 的 copy；本次只在设置测试面板接线，聊天等待仍可能
   只显示状态词。

### 7.2 上下文与保存措辞

- 所有带 `:draft? true` 的 kind 只在 `:saved? true` 时追加“草稿已保留。”；
  `:saved? false` 追加“草稿仍在输入框，尚未确认本地保存。”。
- `:unknown-usage? true` 时统一追加“用量未知，不按零计算。”，任何分支都
  不得输出 `0` 作为未知用量。
- `:code` 可传关键字或字符串；模块内把大写关键字/字符串规范到小写 kind，
  并支持 `:BACKUP_WRONG_PASSWORD`、`:SEARCH_TIMEOUT`、
  `:COMMAND_STORE_FAILED`、`:BACKUP_VERSION_UNSUPPORTED` 等别名。

## 8. 导出与迷你地图纯逻辑接线说明

### 8.1 命名冲突说明

`src/hc/client/conversation/export.cljc` 已存在（280 行 Markdown/脱敏导出）。
本轮没有覆盖它，而是新增纯逻辑层
`src/hc/client/conversation/export_plan.cljc`；测试仍使用任务指定的
`test/hc/client/conversation/export_test.clj`，并同时覆盖现有 Markdown
导出约定（选择/版本/排除/取消/长图计划）。

### 8.2 导出视图挂载点

在会话工具栏/导出对话框（`conversation/view.cljs`，本次未改）挂载：

1. **选择**：把当前消息列表和选项传给
   `export-plan/select-messages`。消息字段：
   `:id :kind :branch-id :selected-version :versions :text :attachments
   :hidden? :tool? :secret? :author :created-at :source`。
   选项：`:selected-id`（单条）、`:selected-ids`（多选）、`:branch-id`
   （当前分支）、`:include-all-versions?`、`:include-tools?`、
   `:include-hidden-instructions?`。默认导出每个消息的当前
   `:selected-version`；`include-all-versions? true` 才展开全部版本。
2. **取消状态机**：导出开始调用 `initial` → `begin` → `start-writing`；
   完成调用 `complete`；失败调用 `fail`（保留 `:selection`/`:preview`）。
   取消按钮调用 `cancel`：`:preparing` 立即 `:cancelled`；`:writing`
   返回 `:notice "已请求取消，等待当前写入边界。"`，随后 `complete`
   落到 `:cancelled`。取消文案使用
   `hc.client.copy.failures` 的 `:export-cancelled`。
3. **长图**：把选择后的消息与选项传给 `segment-plan`；选项
   `:max-height`（默认 4000）、`:theme`、`:include-author?`、
   `:include-time?`、`:include-source?`。空选择返回 `:EMPTY_SELECTION`；
   单条超过上限返回 `:SINGLE_MESSAGE_TOO_TALL`；成功返回
   `:segments [{:index :message-ids :estimated-height}]` 与
   `:segment-count`。`preview-size` 给出 `:width/:height/:pixels/:bytes`。
4. **导出取消 UI**：当前导出是同步下载；需要新增导出状态（`idle/
   preparing/writing/done/cancelled/failed`）与取消按钮。取消后渲染
   `failure-copy :export-cancelled` 的 title/body，主操作
   `:export/restart`，次操作 `:app/back`。

### 8.3 迷你地图挂载点

在会话侧栏/迷你地图（`conversation/view.cljs`，本次未改）挂载：

- 输入消息字段：`:id :kind`（`:user`/`:assistant`/`:tool`/`:attachment`/
  `:context-boundary`）、`:text` 或 `:label`；可选 `:limit`。
- 调用 `minimap/overview` 得到 `:items [{:id :label :kind :anchor}]`、
  `:density`、`:counts`、`:truncated?`、`:id->anchor`。
- 点击条目后调用 `minimap/locate` 得到滚动锚点序号；滚动位置用
  `:anchor`，不要按像素猜测。
- 新内容追加后重新调用 `overview`；`locate` 对同一 id 的锚点保持稳定
  （追加在目标之后时序号不变）。
- 超长列表可传 `:limit`（默认 200）；显示可截断，但 `:id->anchor` 保留
  全部 id，`nearest-anchor` 返回目标之前最近的已显示条目。

### 8.4 本轮新增文件

- `src/hc/client/conversation/export_plan.cljc`
- `test/hc/client/conversation/export_test.clj`
- `src/hc/client/conversation/minimap.cljc`
- `test/hc/client/conversation/minimap_test.clj`

测试：`-X:test:client :nses '[hc.client.conversation.export-test
hc.client.conversation.minimap-test hc.client.copy.failures-test]'`
→ **17 tests / 261 assertions，0 failures / 0 errors，退出 0**。

### 8.5 分段边界与重复 id 修正

- 报告的全量失败 `expected [["a" "b"] ["c"]] / actual [["a"] ["b" "c"]]`
  对应测试的 `:max-height` 与当前算法边界不一致。算法本身按
  “顺序填满当前段，只有 `(+ height h) > max-height` 才开新段”，且每段至少
  一条消息；单条超高在 `segment-plan` 入口直接报
  `:SINGLE_MESSAGE_TOO_TALL`。修法是把测试改成**精确边界**：
  `a+b = 300 = max-height` 必须同段，`c` 开新段；不是把期望改成实际。
- 重复 id：`select-messages` 与 `segment-plan` 返回
  `:DUPLICATE_MESSAGE_ID`，不静默产生重复段；`minimap/overview` 保留第一条、
  返回 `:duplicate-ids` 与 `:input-count`，`locate` 仍指向保留条目。
- 空选择仍返回 `:EMPTY_SELECTION`；单条超高仍返回
  `:SINGLE_MESSAGE_TOO_TALL`；截断概览仍保留全部 `:id->anchor`。

## 9. 剩余 13 个 deviation 类的可执行补丁清单

以下清单供 client-audit 直接应用；当前 `src/hc/client/copy/failures.cljc` 已提供
对应 copy（含新增的 `:quota-exhausted`、`:transport-disconnected`、`:auth-invalid`/
`:permission-denied`/`:model-missing` 别名）。本清单不修改这些 view 文件。

| # | 类别 | 文件:行 | 当前文案 | 替换为 | `:context` 字段 | 应改测试 |
|---:|---|---|---|---|---|---|
| 4 | 凭据无效 | `src/hc/client/settings/connection.cljc:56`；`src/hc/server/app/settings.clj:371-380` | “认证失败”；测试层英文 `authentication rejected` | `failure-copy :invalid-credentials` 的 title/body/action | `{:connection-id :credential-id}` | `settings/copy_wiring_test.clj`、`settings/connection_test.clj` |
| 7 | 服务限流 | `src/hc/client/settings/connection.cljc:366-370` | “限流冷却中；下次可尝试 …”，无“已保留本轮内容” | `failure-copy :rate-limited`；`:saved? true` 才出现“已保留本轮内容”，否则“尚未确认本地保存” | `{:retry-at :saved?}` | `copy/failures_test.clj`、`settings/copy_wiring_test.clj` |
| 8 | 网络不可达 | `src/hc/client/conversation/composer.cljc:337`；`src/hc/server/app/settings.clj:371-380` | “网络尚未连接，草稿已保留”；测试层英文 | 服务不可达用 `:network-unreachable`，传输断开用 `:transport-disconnected` | `{:saved? :endpoint}` / `{:transport :saved?}` | `copy/failures_test.clj`、`settings/copy_wiring_test.clj` |
| 9 | 响应迟迟未到 | `src/hc/client/conversation/surface.cljc` 等待状态；设置测试面板已接 | 设置面板已显示等待 copy；聊天等待仍只有状态词 | 聊天等待调用 `failure-copy :response-timeout`，追加 `已等待 N 秒` | `{:run-id :elapsed-ms}` | `copy/failures_test.clj`、`settings/copy_wiring_test.clj` |
| 10 | 输入超出容量 | `src/hc/client/conversation/composer.cljc` send 决策；`settings/catalog.cljc:272` 已接 | 决策层已返回 `:block?`/`:copy`/actions；聊天发送未调用 | 发送前调用 `capacity-warning`，`block? true` 时渲染 `:copy` 与 actions | `{:estimated-tokens :capacity :saved?}` | `settings/copy_wiring_test.clj`、`settings/catalog_test.clj` |
| 11 | 附件类型不支持 | `src/hc/client/conversation/attachments.cljc:685-695` | “不支持”/“无法发送” | `failure-copy :attachment-unsupported` 的 title/body/action | `{:mime :model-id :saved?}` | `conversation/attachments_test.clj`、`copy/failures_test.clj` |
| 12 | 部分资料不可读 | `src/hc/client/conversation/attachments.cljc:695-740` | “准备失败”/“文字提取失败” | `failure-copy :partial-materials`，保留缺失计数 | `{:read-count :missing-count :saved?}` | `conversation/attachments_test.clj`、`copy/failures_test.clj` |
| 14 | 本地资料不可读 | `src/hc/client/backup/model.cljc:390-391`；启动 blocked `src/hc/server/app.clj:76-90` | “维护日志无法读取…”；启动只有 CLI blocked | `failure-copy :local-data-unreadable` + 独立恢复页 | `{:data-dir :recovery-points}` | `backup/copy_wiring_test.clj`、`backup/model_test.clj` |
| 15 | 外部文件已移动 | `src/hc/client/artifacts/view.cljs:113`；`src/hc/client/backup/view.cljc:317,854` | “重新定位”/“需要重新定位” | `failure-copy :external-file-moved` 的 title/body/action | `{:path :workspace-id}` | `artifacts` view test、`backup/view_test.clj` |
| 16 | 权限被撤销 | `src/hc/client/browser/model.cljc:623`；`src/hc/client/miniapps/view.cljs:853` | “当前标签页未授权…”/“尚未授权…” | `failure-copy :permission-revoked` | `{:permission :scope}` | `browser/model_test.clj`、`miniapps` view test |
| 17 | 工具执行失败 | `src/hc/client/conversation/view.cljs:370-381` | 只有过程状态标签 | `failure-copy :tool-failed`，显示已完成步骤仍保留 | `{:tool-name :call-id :completed-steps}` | `conversation/view_test.cljs`、`copy/failures_test.clj` |
| 18 | 结果回执丢失 | `src/hc/client/conversation/surface.cljc:95`；`src/hc/client/backup/model.cljc:466-472` | 普通命令“停止请求未送达”；备份“提交结果未知…” | `failure-copy :receipt-lost`，区分“未执行”与“可能已执行” | `{:command-id :target :may-have-run?}` | `conversation/surface_test.clj`、`backup/copy_wiring_test.clj` |
| 24 | 导出被取消 | `src/hc/client/conversation/export_plan.cljc` 纯逻辑；导出视图未接 | 逻辑已有“导出被取消”；视图仍同步下载 | 导出视图调用 `cancel`/`complete`，渲染 `failure-copy :export-cancelled` | `{:export-id}` | `conversation/export_test.clj`、`copy/failures_test.clj` |

### 9.1 当前 copy 模块已补齐

- `:quota-exhausted`：文案“这家服务报告额度不足，本次未继续”，主操作
  `:settings/open-connection`（查看服务或换模型），次操作 `:command/stop-run`；
  与 `:rate-limited`、`:auth-invalid`、`:permission-denied`、`:model-missing`
  的 title/body 均不同。
- `:export-cancelled`：文案“没有生成最终导出文件”，主操作 `:export/restart`，
  次操作 `:app/back`；即使 `:saved? true` 也不出现“已保存/已保留”。
- `:rate-limited`：仅 `:saved? true` 出现“已保留本轮内容”；false 时为
  “本轮内容仍在输入框，尚未确认本地保存”。
- `:network-unreachable` 与 `:transport-disconnected` 是独立分支，后者主操作
  `:app/reconnect`。
- 别名：`:auth-invalid`、`:permission-denied`、`:model-missing` 分别解析到
  凭据无效、权限撤销、模型移除，不与额度不足混用。
