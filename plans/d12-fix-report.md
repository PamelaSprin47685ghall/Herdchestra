# D-12 修复报告：手动模型连接与发送门禁

日期：2026-11-06。范围：新建本地模型连接、手动填写模型标识、启用/绑定后的
composer 可用性与发送。不涉及根 AGENTS.md，不执行 git 写。

## 根因

D-12 不是单点，而是三层状态共同造成：

1. **保存层**：`settings/draft.cljc` 的 `add-manual-model` / `ensure-model-entry`
   与 `settings/catalog.cljc` 的 `manual-model` 把手动模型标为
   `:availability :unknown`；`settings/connection.cljc` 的 `server-models`
   手动分支不回传可用性。
2. **composer 门禁**：`conversation/composer.cljc` 的 `model-unavailable?`
   只放行 `:available` / `:not-returned` / `:unconfirmed`，`:unknown` 被判
   不可用，发送按钮禁用。
3. **选择门禁**：`settings/view.cljc` 的 `model-row` 把 `:not-returned` 也当作
   不可选，手动模型无法“用于下一轮”；当前会话没有模型绑定，发送仍被
   “先选择一个可用模型”阻止。

## 修复

| 文件 | 改动 |
|---|---|
| `src/hc/client/settings/draft.cljc` | 手动模型两处改为 `:not-returned`（列表未返回，不是不可用） |
| `src/hc/client/settings/catalog.cljc` | `manual-model` 改为 `:not-returned` |
| `src/hc/client/settings/connection.cljc` | `server-models` 手动模型显式 `:not-returned` |
| `src/hc/client/settings/view.cljc` | `selectable?` 只排除 `:disabled` / `:unavailable`，允许 `:not-returned` |
| `src/hc/client/conversation/composer.cljc` | `model-unavailable?` 放行 `:unknown`；label “尚未确认”；message 说明可直接发送 |
| `src/hc/client/conversation/surface.cljc` | header 对 `:unknown` 显示“（尚未确认）”而不禁用 |
| `src/hc/compose/owner/handlers.clj` | 用户显式声明 `:tools true` 优先于探测 `false`（探测看不到能力时不覆盖声明） |

## 测试

- `hc.client.conversation.composer-test`（新增）：`:unknown` 不再禁用、label 与
  message 文案、真实失败仍阻断。
- `hc.client.settings.draft-test`：手动模型 availability = `:not-returned`。
- `hc.client.settings.catalog-test`：`manual-model` = `:not-returned`，并更新
  既有“manual model ids are allowed”期望。
- `hc.client.settings.connection-test` 仍通过。

命令：

```bash
clojure -J-Xmx1g -J-XX:MaxDirectMemorySize=512m -M:test \
  -n hc.client.conversation.composer-test \
  -n hc.client.settings.draft-test \
  -n hc.client.settings.catalog-test \
  -n hc.client.settings.connection-test
```

结果：**40 tests / 229 assertions / 0 failures**。

## E2E01 新建连接表单结果

聚焦脚本（真实 app + 真实 Chrome + mock 模型；手动 id `manual-only` 故意不在
mock 的 `/v1/models` 列表中，复现“服务不能列模型”）：

- 模型标签：`模型：manual-only`；
- 发送按钮：`disabled=false`（D-12 的 composer 门禁已解）；
- 点击发送后：服务端返回
  `The selected model does not declare tool support; Plan and Reviewer require
  their program tool...`（`MODEL_TOOLS_UNAVAILABLE`），助手回复未出现；
- mock 只收到连接测试请求，未收到聊天请求。

证据：`plans/evidence/e2e-auto/e2e01-new-connection.json`
（`ok=false`，`modelLabelBeforeSend=模型：manual-only`，
`sendDisabledBeforeSend=false`）。

结论：**D-12 的 composer/选择门禁已修复；完整 E2E01 仍被 Plan 工具面阻断**。
剩余缺口：手动模型在设置页声明“工具：声明支持”后，会话 binding 的
capabilities 仍未带 `:tools`（或 live 探测 false 覆盖），Plan 角色拒绝发送。
这需要服务端会话绑定/能力投影 owner 决定：手动模型绑定后允许普通发送，或
确保用户声明进入 binding 并优先于探测。

## Bundle

`resources/public/js/main.js` sha256：
`55e4e8ea73279d58a0dbceba22ee8c815c2b24b2de600fd2e1f21e2eaa27c857`
（W9 最终冻结，2026-11-06 11:50:01，3,008,937 B；含 D-12 客户端修复。
此前的 `dd968793…` 已作废）。
注意：`shell.cljs:94` 有未闭合括号（W21 修复中），下一次重建会产生新 hash；
重建后需重新核对 D-08 的 4 个标记。

### 能力保存链验证

纯客户端投影已用 REPL 验证：`add-manual-model` → `set-model-capability
:tools :known` → `connection/draft-payload` 的 `:fields :models` 为
`{:capabilities {:tools true}}`。状态机测试
`capability-edit-reaches-the-save-payload` 进一步证明：补齐 location 后，
`:settings/save-connection` 入队命令的 `:payload :fields :models` 含
`{:tools true}`（`hc.client.settings.model-test` 34 tests / 140 assertions，
0 failures）。

因此客户端保存链正确；保存后 config 中 `manual-only :capabilities {}` 说明
E2E 的 UI 能力编辑没有触发（Playwright 的 `selectOption` 未让 React 受控
select 的 reducer 收到值）。已把测试驱动改为原生 `value` setter + 冒泡
`change` 事件；恢复 E2E 后重跑验证。

### 证据状态说明

`plans/evidence/e2e-auto/e2e01-new-connection.json` 当前记录的是被用户要求
暂停视觉审查时中止的运行（`Target page, context or browser has been closed`），
不是 D-12 的结论。D-12 的有效结论见本报告上一节（`model manual-only`、
`sendDisabledBeforeSend=false`、服务端 `MODEL_TOOLS_UNAVAILABLE`）；恢复 E2E
后应重跑并覆盖该 JSON。
