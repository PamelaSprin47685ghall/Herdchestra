# E2E 05 / 06 诊断（2026-11-05）

范围：压缩后继续与撤销压缩（E2E 05）、附件能力不匹配与部分读取（E2E 06）。
依据：根 AGENTS.md §7.4 旅程 5（历史与知识）、§6.7、§8.5 E2E 05/06；`plans/e2e-master-status-0.0.1.md`；
`plans/e2e-real-05-06.md`；`plans/e2e-real-04-05-06-19.md`；源码与现有测试。

## 1 已有事实

### E2E 05

- 服务端 `hc.server.history.compression` 已实现 prepare / update-draft / apply / undo / cancel。
  - 原消息不被删除；`undo` 只把状态改回 `:reverted` 并从 `:compression-order` 移除。
  - `context/outbound-context` 用 `range-cover` 把完全落在发送范围内的压缩范围替换为
    `:context-summary` 条目，被替代消息进入 `:excluded`（`:replaced-by-context-summary`），
    不会与摘要同时发送。
  - 测试 `compression-is-reviewed-reversible-and-never-deletes-messages` 已覆盖
    “继续消息保留、撤销恢复范围、原消息可读”。
- 客户端 `conversation/view.cljs` 已有可编辑摘要、`使用摘要`、`撤销压缩`。
- 真实 UI 已证：准备/使用/撤销、原消息可读、一轮继续。
- 未证：
  1. 继续第二轮；
  2. 从 outbound 投影逐项核对“摘要 + 后续消息，且不带被替代历史”；
  3. 撤销后已产生的后续回答仍在；
  4. “从摘要建新会话”——服务端与客户端均无此入口。

根因（4）：没有 `summary-session` 路径；压缩行只有“撤销压缩”，没有“用摘要建新会话”。

### E2E 06

- 服务端文档层已具备：
  - `documents/pages.clj` 解析 1-based 页范围且不重编号；
  - `documents/extract.clj` 的 OCR 结果带 `:pages`（每页 `:status` 与原 `:page`）与
    `:PAGES_WITHOUT_TEXT` / `:PAGES_UNREADABLE` 警告，警告里列出原始页号；
  - `attachments/model.clj` 的 `compatibility` 对未知能力不当 yes，不兼容时给出
    `:needs-choice` / `:unsupported` 与修复路径，发送前 `prepare-send` 再次拒绝；
  - `attachments/ingest.clj` 支持本地副本（`/blobs/<sha256>`）与工作区关联
    （`workspace-attachment!` / `refresh-workspace!` / `snapshot-workspace!` / `verify!`）。
- 客户端已证：文字模型加图片时卡片显示“需要选择处理方式”，发送被阻塞；
  PDF 选页 2-3 后发送提取文本；上传副本在移走原文件后仍可打开。
- 未证 / 缺口：
  1. 附件卡不显示“第 2–5 页，共 4 页”；
  2. 附件卡不列出不可读页（服务端警告未进入卡片）；
  3. 历史消息的附件条不显示不可读页、页码范围与来源；
  4. 历史消息的附件条不区分“已保存本地副本”与“外部工作区引用”，
     外部引用被移动后没有“重新定位”提示；
  5. 真实视觉模型答案超时（环境限制），需用可核对材料/桩文本完成 UI 断言，
     并明确区分真实能力证据与桩文本。

根因（1–4）：`attachments/card` 只输出状态与路由；`conversation/view.cljs`
的 `attachment-list` 只输出名称、大小、打开链接与固定按钮。服务端已有的
`:extraction :pages` / `:extraction :selected-pages` / `:extraction :warnings`
与 `:workspace` 字段没有投影到卡片。

## 2 修复点

### E2E 05

1. 服务端新增 `sessions/summary-session`：从源会话的已应用压缩生成独立新会话，
   只带一条 `:system` 摘要消息；不复制原消息；摘要原文不改写。
2. `sessions/registry/create-summary-session!`：读取源会话、提交并注册新 Owner。
3. 服务端命令 `:session/create-from-summary`。
4. 客户端压缩行（applied）新增“用摘要建新会话”，复用 `:session-create` 命令类型，
   使既有回执 reducer 选中新会话。
5. 测试：
   - 新会话只有摘要消息、原会话消息不被复制、摘要原文保留“假设（未确认）”；
   - 新会话 outbound 只含摘要（不带旧消息）；
   - 压缩后继续两轮：outbound 同时含摘要与两轮新消息，不含被替代消息；
   - 撤销后：后续两轮回答仍可读，outbound 恢复原范围。

### E2E 06

1. `attachments/card` 增加：
   - `:page-label`（选页时“第 2–5 页，共 4 页”）；
   - `:unreadable-pages` / `:unreadable-label`（来自 `:extraction :pages` 的
     `:no-text`/`:error` 页与 `:PAGES_WITHOUT_TEXT`/`:PAGES_UNREADABLE` 警告）；
   - `:selected-pages`。
2. `attachments/view.cljs` 在卡片上渲染页范围与不可读页。
3. `conversation/view.cljs` 的 `attachment-list`：
   - 显示不可读页与页码范围；
   - 对 `:workspace` 且非本地副本的附件显示“外部引用 · 相对路径 · 打开前核对；
     找不到时请重新定位”；
   - 对本地副本显示“已保存副本”。
4. 测试：卡片标签、历史附件提示、不兼容时不进入 sendable。

## 3 复跑断言

E2E 05：长会话含事实、未确认假设、附件、已取消要求；预览→编辑→使用；
继续两轮；断言原消息仍可读、摘要不改写假设、outbound 不同时含摘要与被替代历史、
撤销后后续回答仍在、从摘要建新会话只带摘要且标明来源。

E2E 06：文字模型加图片→发送前说明且不发送；切视觉模型；不可读扫描页显式列出；
PDF 选 2–5 页后引用保持原页码；移走原文件后本地副本可读、外部引用提示重新定位；
不产生隐藏 OCR 请求（无显式选 OCR 时不调用识别）。
