# E2E 11/12 诊断（0.0.1，2026-11-05）

> 范围：记忆候选/归档/临时会话（E2E 11）与世界书/指令卡（E2E 12）。
> 本文件只记录可核对的事实与代码定位；修复结论另见
> `plans/e2e-real-11-12.md`。

## 1 outbound-context 当前字段与真实缺口

`src/hc/server/history/context.clj:712` 的 `outbound-context` 已经通过
`context-snapshot`（同文件 `:676` 起）发布 10 个分区：

| 字段 | 当前来源 | 现状 |
|---|---|---|
| `:system` | `prompts/role-prompt-context` | 有提示字节/模块；默认按 `:clj-plan` 计算 |
| `:assistant` | `outbound-context-opts` 用 `(get-in config [:assistants (:assistant-id session)])` | 会话 `:assistant-id` 为空/未知时得到 `{:name :unknown}` |
| `:instructions` | `outbound-context-opts` 只看指令卡列表 | `:session-override?` 有，`:text-preview` 恒 nil |
| `:memory` | `effective-memories-read` | 只发布 `:items`；`:enabled?` 被硬写成 true；没有“未提供”原因 |
| `:worldbook` | `worldbook-selection-read` | 调用时没有传 `:texts`/`:budget`，命中集合永远为空 |
| `:instruction-cards` | `instruction-cards-read` | 只读 materials 里已 stage 的卡；发送 payload 的 `:turn-card-ids` 从未 stage，故为空 |
| `:tools` | `tools-snapshot [devops-tool-spec]` | 有 name/source/read-write；没有说明文字 |
| `:history` | `outbound-context` 自身 | 正常 |
| `:attachments` | 发送消息里的附件 | 正常 |
| `:estimates` | 调用方 | 恒 `:unknown`，面板显示“未知” |

关键事实：

1. `history/snapshot.clj:123` 的 `baseline` 调用 `session-history` 时不传
   `opts`，所以 `:session/history/:context/:outbound` 里的
   system/assistant/memory/worldbook 都是“空投影”。面板看到的是
   `context-snapshot` 的默认值，不是本轮真正发送的内容。
2. 真正冻结的本轮快照在 session `:runs/<run-id>/:input-snapshot/:context`
   （`src/hc/server/sessions/runs.clj:98` 起）。它由
   `sessions/apply-command` 的 `:session/send-message` 分支
   （`src/hc/server/sessions.clj:1074`）写入，包含
   `outbound-context-opts` 的结果。面板目前不读它。
3. `outbound-context-opts`（`src/hc/server/app/system.clj:332`）已经构造
   8 个分区，但 `worldbook-selection-read` 没有收到用户文本；memory 读
   没有预算；assistant 没有默认助手回退。
4. 指令卡对模型生效，是因为 `material-injection`
   （`src/hc/server/app/system.clj:402`）从请求的
   `:snapshot :turn-card-ids` 读取；outbound 冻结发生在同一请求的更早
   阶段，只读 materials state，所以面板缺少 `:instruction-cards`。

## 2 记忆 suggest 的端口来源

- `materials-session/service` 在 `src/hc/server/app/system.clj:3197` 构造，
  第二个参数是 `(fn [] @model-port-ref)`。
- `model-port-ref` 是 `model-adapter/create-model-port` 生成的**单个**
  ModelPort，按请求快照的 `:role` 选择 `:main` 或 `:task`。
- `suggest-memories!`（`src/hc/server/materials/session.clj:401`）：
  - 端口：`(:model-port request)` → service 的 provider；
  - 绑定：显式 `:model-binding` → 会话 `:model-binding`（role 固定
    `:main`）→ 默认 `{:role :main}`。
- 因此当前行为是“静默用主模型”。当主模型未配置时，
  `MEMORY_SUGGEST_MODEL_UNCONFIGURED` 的消息仍写“Configure a main
  model”；`/tmp/hc-e2e-harness/e2e07` 的失败
  `no model port is available for memory suggestions` 即此路径。
- `memory-suggest/normalize-binding` 已把 `:utility` 映射到适配器的
  `:task`，但调用方从未传 `:utility`/`:task`。

## 3 assistant-id 来源

- 新建会话：`sessions/new-session`（`src/hc/server/sessions.clj:236`）用
  `(or assistant-id (config/default-assistant-id config))`。空字符串在
  Clojure 里为真，会绕过默认值；旧数据/脚本种子会话可以没有该字段。
- 读取：
  - `materials/session/material-session`（`:101`）只读
    `session :assistant-id` 或 `:materials :assistant-id`，没有默认助手。
  - `outbound-context-opts` 用同一个原始 id 查
    `config :assistants`，缺失即 `:unknown`。
  - `worldbook-request` 用原始 `session :assistant-id`，nil 时
    `bound-book?`（`src/hc/server/materials/worldbook.clj:978`）对任何书都
    返回 false，世界书永远不注入。
- 客户端 `materials` 快照的 `:materials/session` 直接来自
  `material-session`；因此页面显示“助手范围 未声明”，而世界书绑定选择器
  能选到 `default`（来自 settings 投影），二者不一致。

## 4 materials ready 条件

- `materials/session/snapshot`（`:686`）：
  - store 读取失败 → `:materials/ready? false` + `:materials/error`；
  - store 未初始化（`:found? false`）→ `:materials/ready? false`；
  - 否则 `:materials/ready? true`，并带 `:materials/revision`。
- `snapshot-initializes-once-and-stays-ready` 已证明：空 store 第一次
  snapshot 会初始化并保持 ready。
- 客户端 `materials_bridge/sync-from-db!`
  （`src/hc/client/workbench/materials_bridge.cljs:190` 起）在
  `:ui/connection :command-allowed?` 且拿到 session command epoch 后，
  每个 session 只请求一次 `:materials/snapshot`（`fetched-session`）。
  如果这次请求发生在 materials service 尚未 ready、或快照返回
  `ready? false`，之后不会重试，页面会一直停在“材料库未就绪”。
- 另外 `model/command-allowed?` 要求 `:sync :phase = :synced` 且
  `:command-epoch` 为整数；这就是“连接尚未完成同步；命令未发送”的来源，
  是启动时序问题，不是 materials 写入缺陷。

## 5 结论

E2E 11/12 的真实阻塞不是“世界书/记忆功能不存在”，而是：

1. 面板读错了投影（history baseline 而非本轮冻结快照）；
2. outbound 构造时缺少用户文本、预算、显式轮次指令卡与默认助手；
3. 记忆整理绑定静默落在主模型；
4. 客户端 materials 快照只拉一次，ready 失败不重试。

修复必须保持：记忆正文不出服务端（只发摘要）；未知值不写成 0；临时会话
默认不读不写；世界书预算不足保留“命中但未提供”原因。
