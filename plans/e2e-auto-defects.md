# E2E 自动化缺陷报告（0.0.1）

- 日期：2026-11-05
- 范围：`test/e2e/` 自动化首轮。报告只记录测试发现，不改产品行为。
- 结论：E2E 01 的“新建本地模型服务连接”路径被一个真实缺陷阻断；测试改用预配置但停用的 mock 连接覆盖其余旅程。

## D-AUTO-01 手动模型可用性回写成 `:unknown`，发送被永久判为“不可用”

- **触发路径**：设置 → 供应商与连接 → 新建本地模型服务连接 → 填写显示名/基础地址 → 加入手动模型 → 保存 → 模型与角色 → 勾选“在连接中启用” → 保存连接 → 选为当前角色 → 返回会话发送。
- **实际行为**：会话模型标签显示 `模型：mock-fast（不可用）`；输入区提示“所选连接或模型当前不可用；请选择其他模型，草稿已保留”；发送按钮 `disabled`。服务端状态增量为 `model-availability :unknown`。
- **证据**：
  - `plans/evidence/e2e-auto/e2e01/e2e01-result.json`（失败运行，`error` 为发送按钮禁用超时）。
  - `plans/evidence/e2e-auto/playwright-artifacts/e2e-01-empty-install--core-5ce21-stall---first-savable-reply/trace.zip` 中 `model-availability :unknown` 的状态增量。
  - 连接测试本身通过（mock 的 `/v1/models` 与最小请求均返回），所以不是网络或 mock 问题。
- **根因（推断，代码级）**：
  1. `src/hc/client/settings/draft.cljc:325-357` 的 `add-manual-model` 把新模型写成 `:availability :unknown`。
  2. `src/hc/client/settings/connection.cljc:539-580` 的 `server-models` 保留 `:availability` 并随保存命令发给服务端。
  3. 服务端保存后，`src/hc/server/app/snapshot.clj:480-495` 的 `binding-availability` 返回模型上的 `:availability`，即 `:unknown`。
  4. `src/hc/client/conversation/composer.cljc:212-220` 的 `model-unavailable?` 只放行 `:available`、`:not-returned`、`:unconfirmed`，于是 `:unknown` 被当成不可用。
- **影响**：用户按正常 UI 添加手动模型并启用后，仍无法发送；现有 UI 没有把 `:unknown` 变成可用状态的入口（“刷新模型列表”只返回配置中的模型，不做实时列举）。E2E 01 的“新建连接”路径无法通过。
- **建议修复（未实施，交协调）**：保存手动模型时不要发送 `:availability`，或把 `:unknown` 规范为 `:unconfirmed`；同时补一个“手动模型启用后可发送”的客户端/服务端回归测试。若产品意图是“未知即不可用”，则 UI 必须提供可操作的确认入口，且不能把“尚未确认”同时显示为可用性未知。
- **测试侧处理**：E2E 01 保留完整的新建连接表单路径，不做预配置连接规避；测试在发送门禁检测到“不可用”提示后以 D-12 失败并写入证据。缺陷修复后该测试应转 PASS。


## D-AUTO-02 手动模型声明 tools 后，会话绑定仍无 tools，Plan 拒绝发送

- **触发**：E2E01 在最终 bundle `ea9e5732` 上新建本地模型连接（`manual-only`），在模型行把“工具能力”设为“声明支持”并保存；行文本显示 `工具：已声明支持`、`能力设置 · 用户设置`，`bind.toolsDeclared=true`。
- **实际行为**：返回会话发送时，Plan 返回 `MODEL_TOOLS_UNAVAILABLE`：“The selected model does not declare tool support; Plan and Reviewer require their program tool.” 没有产生用户消息；E2E01 以 `errorStep=send-no-user-message` 失败。
- **证据**：`plans/evidence/e2e-auto/e2e01/e2e01-result.json`（`clientBundleSha256=ea9e5732…`、`bind.toolsDeclared=true`、`bind.rowText` 含 `工具：已声明支持`、`sendNotices` 两条 `MODEL_TOOLS_UNAVAILABLE`）。
- **根因候选**：能力声明只更新了客户端草稿/徽标，未写入会话绑定读取的模型 `:capabilities`；或 `save-connection` 回执清空草稿后，服务端模型仍只有 `:capability-sources` 而没有 `:tools`。需要检查 `connection.cljc` 的 `server-models`、`settings/save-connection` 与服务端 `model-binding-snapshot` 的能力合并。
- **影响**：用户在 UI 中明确声明 tools 后仍无法用 Plan 发送；E2E01 的新建连接路径无法到达首条回复。
- **建议**：保存连接时把 `:known` 能力写入模型 `:capabilities`，并让会话绑定快照带上同一能力；补“声明 tools 后 Plan 可发送”的服务端/客户端回归。E2E01 的 helper 还应等待草稿清空/服务端快照，而不是只看客户端徽标。
