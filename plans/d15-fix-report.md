# D-15 修复报告：text/plain 附件不再走原生附件路线

日期：2026-11-06。范围：`text/plain` 附件的路由与发送。不涉及根 AGENTS.md，不执行 git 写。

## 根因

`src/hc/server/attachments/model.clj` 的 `native-capability` 对 `:text` 返回
模型声明的文本能力（`text-capabilities`），于是 `route` 先命中 native 分支，
给出 `:send-route :native`。`src/hc/server/model_adapter/request.clj` 的
`validate-attachment` 明确拒绝 native `:text`（“a native attachment needs
image, PDF, audio, or video kind”），因此声明文本能力的模型 + text/plain
附件必然失败。

## 修复

- `native-capability` 对 `:text` 返回 `nil`：文本文件不是不透明原生附件，
  适配器以文本块发送。`route` 随即命中 `(= kind :text)` 分支，使用已确认的
  本地提取文本（`:send-route :text`）或要求先提取。
- image/PDF/audio/video 的 native 语义不变。
- 适配器的 `:text` 路线（`validate-attachment` 要求 `send-text`，
  `attachment-content` 生成 `TextContent`）已是正确接收方，不需要改。

## 测试

- `hc.server.attachments.model-test`：**28 tests / 118 assertions / 0 failures**。
  新增 `text-attachments-use-the-text-route-not-native`：声明
  `#{:text :text-input}` 的模型 + `text/plain` 附件 → `:route :text`、
  `:send-route :text`、`send-text` 为已确认文本，且 `:native?` 为 false。
- `hc.server.model-adapter.request-test`：**15 tests / 56 assertions / 0 failures**
  （适配器接受文本路线）。

## E2E 回归

W34 的 E2E05（bundle 8b210896）已提供 UI/outbound 证据
`plans/evidence/e2e-05-06/d15-text-attachment.json`：

- `ui.chipRaw = "note.txt · 37 B\n可发送\n移除"`，`sendable=true`，
  `nativeOriginal=false`（未出现“发送原文件”）。
- `outbound.userContentIncludes = "[Attachment: note.txt]\nE2E05 附件：部署检查清单。"`，
  `inlineText=true`，`nativeTextRejectionSeen=false`。
- `pure`：TEXT-RAW → `needs-choice`（不再 native）；TEXT-EXTRACTED →
  `:send-route :text`；`prepare-send ok? true`，`send-text` 有内容；
  native text rejection 未触发。
- 缺口：该脚本读的是 composer chip，没有读 `.hc-attachment-card` 的
  “发送提取文本”文案；这不是发送失败，卡片文案可后续补读。

我自己的 `/tmp/hc-w8/d15-check.mjs` 三次排队（flock -w 600/900/300）均未获
app-single 锁，未单独重跑；W34 的证据已覆盖 D-15 的用户可见路径。

## 结论

D-15 的代码、单测与 UI/outbound 回归均已完成。W34 可以去掉为 text/plain
添加的 PNG workaround（文本附件现在稳定走文本块）。

UI 证据的运行时 bundle 为
`8b2108963aca76226146ba275409d5fe95f851d5877e1f73a6ab7466d43b9b85`
（2026-11-06 12:17:19，现标“前冻结”）。当前冻结为
`072587a4690b6953bae9cae3a06eaaa815a38b13eee9b98dc5ce98e72a54824f`
（13:51:04；`0c05b7d1`、`ea9e5732`、`8b210896` 均作废）。新冻结包含相同的 D-15 源码
（服务端 `attachments/model.clj` 12:02、客户端 `attachments.cljc` 12:08），
但 D-15 证据尚未在该 hash 上重跑。final6 全量 PASS 后我又尝试排队
`flock -w 300` 重跑，未获 app-single 锁（其他 E2E 持续占用），故证据继续
标“前冻结”；D-15 本身已由 W34 的 E2E05 与单测关闭，不阻塞发布。
