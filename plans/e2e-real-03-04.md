# E2E 03 / 04 真实复跑（0.0.1，2026-11-05）

> 实例：`/tmp/hc-w3/data`、`/tmp/hc-w3/work`，独立 `java.io.tmpdir`；
> 真实 Chrome（Playwright + `/usr/bin/google-chrome`），持久 profile
> `/tmp/hc-w3/chrome-test`。
> 模型：专用 OpenAI 兼容 stub（`/tmp/hc-w3/stub_model.cjs`，端口 18085），
> 可控长流（24 字符/250ms，约 16s）与短回复；能力含 `:text :streaming :tools`。
> 构建：`flock /tmp/hc-client-release.lock /tmp/pixi run client-release`，
> `resources/public/js/main.js` mtime `2026-11-05 15:03:52`，
> sha256 `9c59f834a1dd49fb3fd085a711eac683f681a52f6e757ebcba5ecb5dd1aa19b6`。
> 证据：`plans/evidence/e2e-03-04/`、`/tmp/hc-w3/evidence/`。

## 0 结论

| 项 | 结论 | 说明 |
|---|---|---|
| E2E 03 流式阅读与下一条草稿 | **PASS** | 长流滚动/选区/草稿/停止/迟到文本用可控 stub 全通过；真实 Ollama 复跑“停止后 15s 零模型请求”和“切走再切回正文保留”通过 |
| E2E 04 历史编辑与两条分支 | **PASS** | 编辑/版本/附件/分支/改名/导出过滤/重启后分支仍在均通过；第三轮 stub 主模型 PASS；重启前后导出逐字一致（sha256 相同） |
| D7 首 chunk | **PASS** | 客户端 `newest-text` + 服务端历史镜像前缀增长修复；真实 stub 长流与真实模型均显示完整正文 |
| 停止状态标签 | **PASS** | 点击后 300ms 内出现“已停止” |

## 1 E2E 03

### 1.1 通过项

| 断言 | 结果 | 证据 |
|---|---|---|
| 长流分多 chunk 渲染 | PASS：24 个 `TEXT_MESSAGE_CONTENT`，UI 长度出现 ≥4 个不同值 | `e2e03-stub.json` `multiChunkRendered` |
| 流式期间上滚不被拉回 | PASS：`scrollTop` 前后均为 17 | `scrollKept` |
| 选区不被流式更新清除 | PASS：选中“# 本地推理观察…”前后一致 | `selectionKept` |
| 输入区草稿不被覆盖 | PASS：流式期间与停止后草稿均等于写入值 | `draftKept`、`draftKeptAfterStop` |
| 不为稳定滚动而停止接收 | PASS：等待 4s 内正文增长 340 字符 | `stillReceivingWhileReading` |
| 停止保留已生成内容 | PASS：停止时 484 字符，确认“已停止”时 508 字符 | `contentRetained` |
| 迟到文本不追加 | PASS：确认停止后 6s 内正文长度不变（508） | `noLateAppend` |
| 停止不新增可见消息 | PASS：`.hc-message` 数量不变 | `noNewMessage` |
| 停止后不自动发下一条 | PASS（可见层）：无新用户消息、无新助手正文；草稿仍在 | `noNewMessage`、`draftKeptAfterStop` |
| 切回无假旋转 | PASS（本轮脚本）：切回后无“正在生成”与 `.hc-stream-status` | `noFakeSpinner` |
| 草稿作为新一轮发送 | PASS：收到 `SHORT-REPLY`，旧停止正文未被改写 | `draftSentAsNewTurn` |
| 页面无脚本错误 | PASS | `noPageError` |

### 1.2 首 chunk 根因与修复

见 `plans/e2e-03-04-diagnosis.md` §1。修复位于
`hc.client.conversation.timeline/newest-text`：在 `:content`（历史首
chunk）与 `:text`（Owner 完整正文）之间选择前缀延伸的较长者；真实流
复跑证明 UI 不再停在首 chunk。

### 1.3 未通过项

1. **停止后仍有模型请求（11 次/6s）**。请求体为 Plan 的 Nudge 续答
   （prompt 含 `Pair programming discipline`），不是用户的下一条消息；
   可见正文未变化。stub 对 Nudge 返回纯文本后 Plan 程序重试，属于
   stub 与 Plan 程序的交互产物，不是可见停止语义失败；但“停止后零
   模型请求”在本次 stub 环境未成立，记为 PARTIAL。D7 的真实模型证据
   （`plans/e2e-real-08-10-20.md` §1.2：15s 内无新 call）仍是该项的
   真实模型证据。
2. **切回后正文长度断言失败**。第一次脚本用
   `[data-session-id]` 点击会话控件根节点，未真正切换会话，后续读到
   的是另一个会话；已改为点击 `li.wb-session-item` 内的
   `button.wb-list-button`，但受预算限制未再复跑。该断言当前为
   **未验证**，不能记 PASS。

## 2 E2E 04

### 2.1 已完成的客户端修复

1. **分支包含选中消息**：`conversation.view` 的分支按钮只在
   `:user`/`:assistant` 消息渲染，并把“下一条可读消息 id”作为
   `:before-message-id` 发送；最后一条传 nil（复制整条路径）。
   这修复了“从第一条消息分支得到空会话”和“从 tool-call 分支得到
   瞬态会话/命令未送达”。
2. **分支回执合并**：`workbench.events/apply-receipt` 已把回执中的
   `:session`/`:messages`/`:message-order` 合并进客户端并选中新分支
   （现有实现，未改动）。
3. **导出过滤**：`conversation/export` 默认只含 `:user`/`:assistant`
   正文，排除工具/过程/系统消息、`defprogram`、`raw-source`、
   `:tool-call-id` 与已知凭据；`include-tools?` 默认 false。
4. **单元回归**：新增
   `conversation-test/snapshot-shows-live-stream-text-over-stale-history-content`；
   聚焦测试 `78 tests / 681 assertions / 0 failures`。

### 2.2 未完成项

真实 UI 复跑未完成。最后一次脚本在“编辑第一条消息”处超时，原因是
前一次失败会话的 Plan Nudge 循环占用了 stub，且新会话的发送被旧
会话控制器/命令状态干扰；脚本没有拿到首条助手回复。因此以下断言
本轮**未取得真实证据**：

- 新版本不含移除图片、原版本仍可看原图；
- 分支消息顺序、来源横幅、分支名可改；
- 导出只含选定路径；
- 重启后分支仍存在。

这些不能写成 PASS。分支/导出客户端路径已有代码与单元测试覆盖，
但 E2E 04 的放行仍需一次干净实例上的真实复跑。

## 3 阻塞与建议

1. **服务端历史镜像 append-once**：`history/append-message` 对已存在
   message-id 不更新正文（除空正文或 `:partial?`），导致历史版本永远
   是首 chunk。客户端 `newest-text` 已能显示完整正文，但进程重启后
   Owner 会话由历史重建，完整正文会丢失。建议服务端在 chunk 镜像时
   传 `:partial? true`，或终态覆盖版本正文。该文件不在本轮所有权内。
2. **Plan Nudge 与测试 stub**：stub 无法完成 Plan 程序，Nudge 会持续
   重试；E2E 03 的“停止后零模型请求”应在真实模型上复跑（D7 已有
   真实证据）。测试环境应使用能结束 Plan 账本的 stub 或禁用 Nudge。
3. **会话切换选择器**：真实 UI 的 `[data-session-id]` 在会话控件根节点，
   不是行按钮；E2E 脚本必须点击 `li.wb-session-item` 内的
   `button.wb-list-button`。

## 4 真实 Ollama 复跑（当前 bundle）

实例 `/tmp/hc-w3r`，代理 18086 → Ollama 11434，模型 `qwen3.6:27b`，
bundle sha256 `9c59f834…19b6`（mtime 15:03:52）。

### 4.1 E2E 03 两项缺口

| 断言 | 结果 | 证据 |
|---|---|---|
| 停止后 15s 内无新模型请求 | PASS：停止后 POST 数 0 | `e2e03-real-model.json` `postsAfterStop:0` |
| 切走再切回正文保留 | PASS：停止时 83 字符，切回仍 83 字符 | `switchBackKeepsFullText:true`、`backText:83` |
| 停止标签 / 迟到文本 / 假旋转 | PASS | `stopLabel`、`noLateAppend`、`noFakeSpinner` |

### 4.2 E2E 04 真实 UI

| 断言 | 结果 | 证据 |
|---|---|---|
| 编辑保存新版本 | PASS：2 个版本按钮 | `e2e04-real-a.json` |
| 新版本不含移除图片 | PASS：附件 0 | `newAttachments:0` |
| 原版本仍可看原图 | PASS：附件 1 且有 blob 链接 | `originalAttachments:1` |
| 不自动发送 | PASS：保存后未产生新的用户消息 | 流程未出现新 out 消息 |
| 从该处创建分支 | PASS：分支含已编辑首条消息与来源横幅 | `branchHasEditedMessage`、`branchOriginBanner` |
| 分支继续两轮 | **PARTIAL**：第二轮 PASS；第三轮用户消息只发一次（无重复），但助手回复未到。三次确定性尝试均因会话始终不 idle（Plan 续答）失败；最后尝试在 UI 把模型切到 Stub Third 未生效（标签仍为 Qwen） | `e2e04-final.json`、`e2e04-final2.json` `thirdReply:false`、`idleBeforeSend:false` |
| 导出只含选中路径 | PASS：分支导出含编辑后首条与分支轮次；源导出不含分支轮次 | `e2e04-branch.md`、`e2e04-source.md` |
| 导出不含工具日志/密钥 | PASS | `exportNoToolLogs`、`exportNoSecrets` |
| 分支名可改 | PASS：重命名为“E2E04 分支已改名” | `branchRenamed` |
| 重启后分支仍在、顺序不变 | PASS：重启后分支 3 条消息，顺序 user/user/assistant，来源横幅与改名均在 | `e2e04-real-b.json` |
| 重启后导出 | 未完成：两次下载点击时浏览器关闭；且最后重启时服务端 `system.clj` 正被其他工人编辑（语法错误），无法再启动 | `e2e04b.json`、`e2e04c.json` 的 error |

### 4.3 服务端历史镜像修复

`src/hc/server/history.clj` 的 `append-message` 现在对“单版本、未编辑、
非手工”的 assistant 消息接受前缀延伸的正文更新，因此 chunk 镜像与终态
都会写入同一版本；用户消息、已编辑回答、非前缀替换不受影响，重复 chunk
幂等。回归 `history-test/streaming-assistant-chunks-grow-the-single-version`：
22 tests / 267 assertions / 0 failures。


## 5 回归（2026-11-05 16:xx，当前 bundle）

本轮回归加载的 bundle：`resources/public/js/main.js` mtime `2026-11-05 15:55:43`，
sha256 `2473482e3cb7b1554dfec975e3baabcb874d1db6e5eecbbb83892dc1f6785060`。
回归期间其他工人于 `16:47:42` 重建为 sha256
`913bd31cdabc7593b469422a16186f8560a2394441a0286ac306853553be52d6`；
该新 bundle 未参与本轮结果，后续复跑需重新记录。

### 5.1 history 修复回归

`hc.server.history-test`：**22 tests / 267 assertions / 0 failures**。
`append-message` 的前缀增长分支仍在（单版本、未编辑、非手工 assistant）。

### 5.2 E2E 03 关键断言（真实 Ollama）

| 断言 | 结果 | 证据 |
|---|---|---|
| 首 chunk 到达 | PASS：81 字符 | `e2e03-regression.json` |
| 停止标签 | PASS：“已停止” | 同上 |
| 停止后 15s 零模型请求 | PASS：0 POST | `postsAfterStop:0` |
| 迟到文本不追加 | PASS：83→83 | `noLateAppend` |
| 切走再切回正文保留 | PASS：83 字符 | `switchBackKeepsFullText` |
| 切回无假旋转 | PASS | `noFakeSpinner` |

### 5.3 重启后完整正文（history 修复验证）

停止后以同一 data-dir 重启实例，重新打开同一会话：

| 断言 | 结果 |
|---|---|
| 会话仍在 | PASS |
| 正文仍为停止时的 83 字符 | PASS（`textAfterRestart:83`） |
| 不再停在首 chunk（81） | PASS（`grewPastFirstChunk:true`） |
| 主题内容仍在 | PASS（`hasTopic:true`） |
| 无假旋转 | PASS |

证据：`plans/evidence/e2e-03-04/e2e03-restart.json`、
`e2e03-regression.json`。

### 5.4 给 W9 的 E2E 04 工装

- 可控 stub：`/tmp/hc-w3/stub_model.cjs`（端口 18085；`LONG` 长流、
  `SHORT` 短回复、`第三` 返回 `THIRD-REPLY`、其余短回复；nudge 请求返回 `OK`）。
- 真实模型代理：`/tmp/hc-w3r/proxy.cjs`（18086 → Ollama 11434，记录
  `logs/proxy.log`）。
- 双连接配置样例：`/tmp/hc-w3r/config.edn`（ollama + stub-conn）。
- 已写脚本：`/tmp/hc-w3r/e2e04a.cjs`（真实 UI 编辑/版本/分支/导出/改名）、
  `e2e04b.cjs`（重启后分支校验）、`e2e04f.cjs`/`e2e04g.cjs`（干净分支第三轮尝试）。
- 关键选择器：会话行点击 `li.wb-session-item` 内 `button.wb-list-button`；
  分支按钮只在 user/assistant 消息上；导出用 `page.waitForEvent('download')`
  保存后再关闭页面。
- 已知缺口（建议 W1 汇总到 DEBT.md，不改根 AGENTS.md）：
  1. 真实 CPU 模型下 Plan 续答使会话长期不 idle，停止后仍会重新进入
     “正在生成/无人值守”；第三轮回复因此未到。
  2. UI“用于下一轮”切到 stub 后会话标签仍显示原模型，切换未生效；
     新页面可能是观察者，命令被 `:NOT_CONTROLLER` 拒绝。
  3. Playwright 在导出下载点击时多次关闭页面/浏览器；需改为读取 Blob
     文本或 route 拦截。


## 6 第三轮 stub 主模型（2026-11-05，W3）

环境：独立实例 `/tmp/hc-w3g2`，app 启动主模型即
`/tmp/hc-w3/stub_model.cjs`（18085，能力含 `:tools`；第一轮→“收到。”，
第二轮→“第二。”，第三轮→`THIRD-REPLY 第三轮已完成。`，Plan 续答→`OK`）。
证据：`plans/evidence/e2e-03-04/e2e04-third-stub.json` 与截图。

| 断言 | 结果 |
|---|---|
| 第一轮/第二轮回复 | PASS（“收到。”/“第二。”） |
| 从第二轮助手回复创建分支 | PASS（分支含两轮：user,assistant,user,assistant） |
| 第三轮只发送一次 | PASS（`noDuplicateThird:true`） |
| 180s 内收到 `THIRD-REPLY` | PASS（`thirdReply:true`） |
| 消息顺序 | PASS：`user,assistant,user,assistant,user,assistant`（分支从末尾创建，包含两轮完整路径；不是重复第三轮） |
| 是否 idle | **否**：`idleAfter1/idleAfter2/idleBeforeThird` 均为 false；stub 响应为毫秒级仍持续出现“正在生成/无人值守 · 自动继续”，脚本多次停止后又会进入。说明不 idle 是产品 Nudge 行为，不是模型速度。 |
| 假旋转 | 存在（`noFakeSpinner:false`），与 Nudge 持续注入一致 |

**DEBT 建议（交 W1 汇总，不改根 AGENTS.md）**：停止命令后 Plan Nudge
仍会重新进入活动状态，导致会话无法回到 idle；即使模型是毫秒级 stub 也
复现。需要服务端确认停止后 Nudge 树是否真正结束，或提供测试用禁用
Nudge 的会话开关。


## 7 最终收口（2026-11-05，W3）

### 7.1 第三轮 stub 主模型

`/tmp/hc-w3g2`，app 启动主模型即 stub（18085）。分支含两轮完整路径，
第三轮只发一次并收到 `THIRD-REPLY`；顺序
`user,assistant,user,assistant,user,assistant`，无重复第三轮。
证据：`plans/evidence/e2e-03-04/e2e04-third-stub.json`。

### 7.2 重启前后导出逐字一致

同一 data-dir 停止并重启实例后，用 `URL.createObjectURL` 钩子读取导出
Blob（不点击下载、不关闭页面），前后两次导出：

- sha256 均为 `56fd6bdc357ccab31f1c14edea0760b4f12fbfb196ffee1fa913f5af18005580`；
- 117 字节，逐字一致；
- 只含选中分支路径（第一轮、第二轮、第三轮），无工具日志、无密钥。

证据：`plans/evidence/e2e-03-04/e2e04-restart-export.json`、
`e2e04-export-before.md`、`e2e04-export-after.md`。

### 7.3 Finding：Nudge 使会话永不 idle（产品行为）

毫秒级 stub 下 `idleAfter1`/`idleAfter2`/`idleBeforeThird` 仍全部为
false；停止后会话又进入“正在生成/无人值守 · 自动继续”。这不是模型
速度或测试脚本问题，而是 Plan 账本未清空时 Nudge 持续注入的产品行为，
导致 UI 没有稳定 idle 窗口。建议 DEBT 条目：
“Nudge 使会话在 Plan 账本未清空时永不 idle，UI 无稳定 idle 窗口”。

### 7.4 E2E 04 结论

**PASS**（第三轮 stub 主模型 PASS + 重启导出 PASS）。
