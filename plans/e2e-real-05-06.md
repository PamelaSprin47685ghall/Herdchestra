# Herdchestra E2E 05 / 06 实跑报告（2026-11-05）

> 真实 app + 真实 Chrome + 真实 Ollama（`qwen3.6:27b`）。未修改产品源码；
> 未执行 git 写操作。证据在 `/tmp/hc-e2e-harness/e2e05/`。

## 1 E2E 05 压缩后继续与撤销压缩

### 已做（真实 UI）

- 启动 app，打开已有会话 `E2E 22 已保存会话`（含一条用户消息与附件）。
- 点击“压缩当前发送范围”，UI 出现：
  - 可编辑摘要 textarea（aria-label `压缩摘要`）；
  - 消息选择 `选择消息 m1`；
  - “准备压缩”“取消”。
- 填写摘要：
  `已压缩的历史摘要。事实：用户发送了一条迁移前历史消息。假设（未确认）：用户可能偏好蓝色主题。`
  勾选 m1，点击“准备压缩”。

### 修复后重跑（真实 UI，云模型）

- 使用 A-data 副本与云端 `deepseek-v4.1-flash:1017`（max-output 256，
  reasoning-effort none）重跑；`准备压缩` 不再报 epoch 错误。
- 压缩 m1，摘要填写：
  `事实：用户发送了一条迁移前历史消息。假设（未确认）：用户可能偏好蓝色主题。`
  - `准备压缩` 成功，出现“压缩 draft：使用摘要”；
  - `使用摘要` 成功，上下文变为 `发送 0 条 · 摘要 1`；
  - 原消息 `E2E 22 迁移前历史消息` 仍可读，附件仍在；
  - 摘要文本保留“假设（未确认）”，未把假设写成事实。
- 压缩后继续一轮 `请只回答 OK1。`：assistant 回复出现，消息计数
  `发送 2 条 · 摘要 1`；原消息仍可读。
- 点击“撤销压缩”：上下文变为 `发送 3 条 · 摘要 0`，原消息仍可读；
  撤销后又发送了一条消息（`发送 3 条`），但本轮没有等到该条 assistant
  回复。

### 未完全覆盖

- 只完成一轮继续，第二轮未完成；`继续时不同时带入摘要与被替代历史`
  没有从 outbound 投影逐项核对（上下文面板只显示摘要 1/阅读 3）。
- 撤销后第三条回答未等到；不能声称“后续回答不丢”的完整链路。

### 证据

- `/tmp/hc-e2e-harness/e2e05/full.json`
- `/tmp/hc-e2e-harness/e2e05/full.png`
- `/tmp/hc-e2e-harness/e2e05/compress-flow-fixed4.json`（epoch 修复证据）

### 结论

**PARTIAL / PASS（核心压缩 UI）**。准备/采用/撤销、摘要文本、原消息
可读、一轮继续有真实 UI 证据；第二轮继续与 outbound 不同时带入摘要+
历史、撤销后回答未完整验证。

### 证据

- `/tmp/hc-e2e-harness/e2e05/probe.json`
- `/tmp/hc-e2e-harness/e2e05/compress-flow.json`
- `/tmp/hc-e2e-harness/e2e05/compress.png`
- `/tmp/hc-e2e-harness/e2e05/compress-flow.png`

### 结论

**BLOCKED**。压缩 UI 与范围/摘要编辑可打开；准备压缩因种子会话缺命令
epoch 被拒。不能标 PASS。

## 2 E2E 06 附件能力不匹配与部分读取

### 模型与 fixture

- 文字模型：云端 `deepseek-v4.1-flash:1017`（`https://ollama.com/v1`，
  `OLLAMA_API_KEY`，`:max-output-tokens 256`，`:reasoning-effort "none"`）。
- 视觉模型：本地 `qwen3.6:27b`（`http://127.0.0.1:11434/v1`，声明
  `:vision` 与 `:tools`）。
- 4 页扫描 PDF：`/tmp/hc-e2e-harness/e2e06/fixture/scan-4pages.pdf`
  - 第 1 页清晰表格；第 2 页倾斜文字；第 3 页噪点不可识别；第 4 页清晰正文。
- 图片：`/tmp/hc-e2e-harness/e2e06/images/p1.png`。

### 已通过（真实 UI）

1. 文字模型会话添加图片 `p1.png`：
   - 卡片显示 `需要选择处理方式 · 模型不接受原格式，可提取文字或换模型`；
   - 动作：`提取文字`、`排除`、`移除`；
   - 发送按钮因“仍有附件未准备好”被阻塞。
   - 换模型路径通过设置页“模型与角色 → 用于下一轮”完成（不是卡片内按钮）。
2. 切到视觉模型 `Qwen3.6 27B`，添加 4 页 PDF：
   - 卡片显示 `PDF · 89.1 KiB · 等待选择处理方式`；
   - 点击“提取文字”弹出 `输入页码（如 2-5，留空表示全部）`；
   - 输入 `2-3` 后卡片变为 `发送提取文本 / 可发送 · 发送已确认的提取文本`；
   - 发送被接受，用户消息包含 `scan-4pages.pdf` 附件。

### 2 页 fixture 与脚本桩复跑

- fixture：`/tmp/hc-e2e-harness/e2e06/fixture2/scan-2pages.pdf`
  （2 页，96 DPI，74.8 KiB；第 1 页清晰表格，第 2 页噪点不可识别）。
- 真实本地 `qwen3.6:27b` 视觉模型：选第 2 页发送后 85s 只生成一个
  “第”字，300s 内没有完整答案；记为“本地 27B 视觉推理超时/截断”。
- 改用脚本化模型桩（`e2e06/stub.js`，端口 18098）返回固定文本：
  `第 2 页不可识别。材料：scan-2pages.pdf。引用页码：2。`
  UI 断言在真实 app/Chrome 中完成，文本明确来自桩。

### 通过（真实 UI + 桩文本）

- 视觉模型连接（stub）绑定会话；附件 PDF 选页 `2` 后“可发送 ·
  发送已确认的提取文本”。
- 发送后 assistant 文本出现：`第 2 页不可识别。材料：scan-2pages.pdf。
  引用页码：2。`——不可读页为第 2 页，引用页码是 2 而不是 1，并指向
  `scan-2pages.pdf`。
- 把原 fixture 改名移走后重载历史：附件 `scan-2pages.pdf` 仍显示可打开，
  assistant 文本仍在；本地副本可读。
- 未出现“外部引用重新定位”提示，因为该 PDF 是上传到 blob 的本地副本，
  不是外部工作区路径引用；这一子项以“无外部引用”记录，不能伪造提示。

### 证据

- `/tmp/hc-e2e-harness/e2e06/pdf2-stub.json`
- `/tmp/hc-e2e-harness/e2e06/pdf2-answer.png`
- `/tmp/hc-e2e-harness/e2e06/pdf2-after-move.png`
- `/tmp/hc-e2e-harness/e2e06/stub.js`
- `/tmp/hc-e2e-harness/e2e06/config-stub.edn`
- `/tmp/hc-e2e-harness/e2e06/fixture2/scan-2pages.pdf`
- 真实模型截断证据：`/tmp/hc-e2e-harness/e2e06/pdf2-flow.json`

### 结论

**PASS（UI 断言，文本来自桩；视觉真实能力由不兼容提示与选页证据覆盖）**。
真实 27B 视觉模型在本机超时/截断，不能作为真实答案证据；桩文本完成了
不可读页、原页码 2、材料指向与移走原文件后的本地副本断言。外部引用
重新定位未触发，因为附件是上传副本而非外部路径引用。

### 证据

- `/tmp/hc-e2e-harness/e2e06/attach-text.json`
- `/tmp/hc-e2e-harness/e2e06/text-image.png`
- `/tmp/hc-e2e-harness/e2e06/full-flow.json`
- `/tmp/hc-e2e-harness/e2e06/full-flow.png`
- `/tmp/hc-e2e-harness/e2e06/pdf-extract.json`
- `/tmp/hc-e2e-harness/e2e06/pdf-extract.png`
- `/tmp/hc-e2e-harness/e2e06/pdf-full2.json`
- `/tmp/hc-e2e-harness/e2e06/pdf-answer2.png`
- `/tmp/hc-e2e-harness/e2e06/fixture/scan-4pages.pdf`
- `/tmp/hc-e2e-harness/e2e06/config.edn`

### 结论

**PARTIAL / BLOCKED**。文字模型图片不兼容提示与 OCR/移除/换模型路径
有真实 UI 证据；视觉模型 PDF 选页 2-3 与提取发送有真实 UI 证据。
模型答案超时，未验证不可读页列表/原页码引用/具体材料指向；原文件移走
后的本地副本与重新定位未跑。

## 3 总结

| E2E | 结论 | 阻塞 |
|---|---|---|
| 05 | PARTIAL / PASS（核心压缩 UI） | 准备/采用/撤销 PASS；一轮继续 PASS；第二轮/outbound 去重/撤销后回答未完整验证 |
| 06 | PASS（UI 断言，答案文本来自桩） | 文字模型不兼容提示 PASS；视觉 PDF 选页 2/发送 PASS；桩答案列出第 2 页不可识别并引用页码 2；移走原文件后本地副本仍可读；真实 27B 视觉模型超时/截断 |

未覆盖：E2E 05 的第二轮继续、outbound 摘要/历史去重、撤销后回答；
E2E 06 的真实 27B 视觉答案（超时/截断）与外部路径引用重定位。

## 2026-11-06 E2E 06 最终复跑（独立实例 + 真实本地 Ollama）

环境：独立 data-dir `/tmp/hc-w8/data13`、workspace `/tmp/hc-w8/work13`、端口 42071；
模型为真实本地 Ollama `qwen3.6:27b`，两个连接声明不同能力：`Qwen Text`
（`#{:text :streaming :tools}`）与 `Qwen Vision`（增加 `:vision`）。真实浏览器为
Playwright 驱动的系统 Chrome（`--disable-dev-shm-usage`）。Ollama context
16384、NUM_PARALLEL=1。答案由 Qwen 真实生成（`Page 2`/`Page 4` 与
`Unreadable Pages`），不是脚本桩。
证据：`plans/evidence/e2e-05-06/e2e06-final.json` 与四张截图
（`e2e06-final-text-image.png`、`-pdf-extract.png`、`-answer.png`、
`-after-move.png`、`-workspace.png`）。

### 断言

| # | 断言 | 结果 | 证据 |
|---:|---|---|---|
| 1 | 文字模型加入图片，卡片说明“需要选择处理方式”，点击发送后消息数不增加 | PASS | `textImageCard` 含“模型不接受原格式”；`messagesBeforeBlockedSend=0`、`messagesAfterBlockedSend=0`、`blockedNotice=true` |
| 2 | 切到视觉模型后才可继续 | PASS | `modelAfterSwitch="模型：Qwen Vision"` |
| 3 | 不可读扫描页显式列出，不包装成已完整阅读 | PASS | 提取后卡片“不可读：第 3 页、第 5 页”；历史附件条同样显示 |
| 4 | PDF 选 2–5 页后引用保持原页码 | PASS | 卡片“第 2–5 页，共 4 页”；答案“引用页码：第 2–5 页”；未出现“第 1–4 页” |
| 5 | 答案指向具体材料 | PASS | Qwen 答案列出 `Page 2`/`Page 4` 原文并给出 `Unreadable Pages`；附件卡同时列出第 3/5 页 |
| 6 | 移走原文件后已保存副本仍可读 | PASS | `moved=true`；消息附件条“已保存副本”；`/blobs/...` 返回 200、1,194,731 B；回复仍在 |
| 7 | 外部引用提示重新定位 | PASS | 关联 `docs/scan4.pdf` 后卡片“外部引用 · docs/scan4.pdf · 打开前请确认原文件仍在该位置；找不到时请重新定位” |
| 8 | 不产生隐藏 OCR 请求 | PASS | 附件请求仅：上传 p1、上传 PDF、一次显式 `/attachments/extract`、一次工作区关联；发送后 `requestsAfterSend=[]` |
| 9 | 关联文件未保存副本时不得静默外发 | PASS | 点击发送前后消息数均为 2（被策略阻止） |

### 实现修复（本次）

1. `attachments/card` 与历史附件条投影页范围、不可读页、已保存副本/外部引用；
   页事实只在用户显式选择提取路线后显示，上传时的文本探测不再冒充 OCR 结果。
2. `conversation/view.cljs` 的历史附件条改用 `:attachment-refs`，避免展示投影
   丢弃 `:local-copy?`、`:extraction`、`:workspace`。
3. 新增 `POST /attachments/workspace` 与“关联工作区文件”入口；关联保持
   `:local-copy? false`，发送策略要求先保存副本。
4. `attachments/model.clj` 的文本路线发送 `第 N 页：` 标注，PDF 选页不再被
   重编号。
5. 测试：`hc.server.attachments.model-test`、`hc.client.conversation.attachments-test`、
   `hc.server.documents.*`、`hc.server.compression-summary-test` 共 99 tests /
   499 assertions，0 failures。

### 结论

**PASS（真实本地 Ollama + 真实浏览器）**。答案由 Qwen3.6 27B 真实生成并引用
`Page 2`/`Page 4`；`answerArrived` 的“无正在生成”条件因无人值守自动继续未在
240 s 内满足，但助手文本已在页面出现，故以文本证据判定。不可读页、原页码、
材料指向、副本可读、重定位提示与无隐藏 OCR 均已覆盖。工作区重定位提示的
截图证据来自同一 UI 的 06a3 运行（`e2e06a3.json` 的 `workspaceBody`）；本轮
`workspaceCard` 选择器取到空节点，但 `workspaceGuidance` 的文案与此前一致。


## 3 E2E 05（W3，2026-11-06）

### 3.1 已通过（stub 主模型，真实 UI）

| 断言 | 结果 |
|---|---|
| 原消息仍可读 | PASS（事实/假设/已取消要求仍在） |
| 压缩预览可编辑 | PASS（摘要 textarea + 消息选择） |
| 准备压缩 | PASS（draft 行出现） |
| 使用摘要 | PASS（applied 行出现） |
| 摘要不升级假设 | PASS（摘要保留“假设（未确认）”，UI 未改写） |
| 撤销压缩 | PASS（applied 行消失，原消息仍可读） |
| 新会话辨认摘要来源 | PASS（“【压缩摘要 · 来源会话《New session》】”，无旧用户消息） |

### 3.2 未验证

- 继续两轮：stub 下用户消息可追加，但助手回复未出现（Plan 程序不接受
  stub 的纯文本回复），因此“继续时不同时带摘要与被替代历史”的
  outbound 面板（摘要 1 / 发送消息数）未取得。
- 撤销后不删之后回答：没有助手回答可验证；只验证了撤销后原消息仍可读。
- 真实 Ollama 复跑：在 OOM 前未完成；OOM 后 `flock
  /tmp/hc-app-single.lock` 被视觉审查工人长期占用，`flock -w 900`
  等待约 500s 后被 SIGTERM（exit 143），未取得锁，未另起实例。

### 3.3 证据

`plans/evidence/e2e-05/e2e05.json`、`e2e05.png`。
结论：**PARTIAL**（压缩预览/编辑/使用/撤销/新会话摘要来源 PASS；
继续两轮与 outbound 对照、撤销后回答保留未证）。


### 3.4 真实 Ollama 复跑启动阻塞（2026-11-06）

- 冻结 hash 变迁：`8b210896`（12:17:19）→ `ea9e5732`（13:02:42）→ `0c05b7d1`（13:37:25）→ `072587a4`（13:51:04；源码树 a97c68f7；55e4e8ea/dd968793 作废）。E2E05 尚未在 `ea9e5732`/`0c05b7d1`/`072587a4` 下取得证据；stub 轮与 W34 的 8b210896 轮均为前冻结。
- `flock -w 3600 /tmp/hc-app-single.lock` 内启动 app 的三次尝试均失败：
  - 服务取得锁并打印 `LOCK_ACQUIRED`/`STARTING_APP` 后，`app.log` 为 0 字节；
  - 约 4–9 分钟后服务 exit 1，未打印 `APP_START_TIMEOUT`/`APP_EXITED`；
  - 相同 768m/512m 的 `--check --config` 在锁外可正常 `:ok true`（18s），
    说明配置与堆上限可用，失败点在 flock 服务内启动 app 的环境。
- 未启动 Chrome，未产生 E2E05 真实证据；未在锁外另起实例。
- 结论：E2E05 仍 **PARTIAL**；真实复跑需在锁内可正常启动 app 的窗口进行
  （或由协调者提供可用的启动方式）。

## 4 E2E 05 W34 收口（2026-11-06，8b210896 前冻结）

> 注：8b210896 → ea9e5732（13:02:42）→ 0c05b7d1（13:37:25）→
> 072587a4（13:51:04，3,011,162 B，源码树 a97c68f7，CSS 332f9fd3 未变）。
> 本节证据只作前冻结记录，不得作为最终冻结 PASS 证据。
> 当前冻结 072587a4 未跑 E2E05（W28 待补跑，运行时以 pageBundle 实际 hash 为准）。

### 4.1 环境与启动

- bundle `8b210896…b9b85`（3,009,689 B，12:17:19），CSS `332f9fd3…`；
  `pageBundle` 由页面资源实测得到同一 hash（不是只读磁盘）。
- 真实本地 Ollama `qwen3.6:27b`，日志代理 18089；app 以
  `flock -w 1800 /tmp/hc-app-single.lock /tmp/pixi run clojure
  -J-Xmx768m -J-XX:MaxDirectMemorySize=512m … --config …` 启动；
  `-J` 位于 deps.edn `:jvm-opts` 之后，最终堆为 768m（命令行可见
  `-Xmx1g -Xmx768m`）。

### 4.2 启动失败根因（W3 的 4–9 分钟 exit 1）

- 不是 app 启动失败，而是 **flock 等锁超时**：`flock -w 240/300/540`
  在 app-single 被其他工人占用时等待到期后 exit 1；app 从未启动，
  `app.log` 因 `: >` 清空而为 0 字节，也不会打印
  `APP_START_TIMEOUT`/`APP_EXITED`。W34 的 `flock -w 600` 在 12:00
  同样以 600 s 超时 exit 1 复现了该模式。
- 另一个独立隐患：`run_locked.sh` 的 `set -euo pipefail` 下
  `URL=$(grep … app.log)` 在日志尚无 listening 行时 grep 返回 1，
  赋值语句立即退出；即使拿到锁也会在 app 启动早期退出。
- 因此锁内启动本身可用；11:52、11:54、11:55 与 W34 的 12:16 均
  成功 listening。

### 4.3 首条消息 `call-1 failed` 根因（D-15）

- `attachments/model.clj` 的 `native-capability` 曾把 `:text` 判为原生
  能力；`route` 的 native 分支先于文本分支，text/plain 得到
  `{:route :native :send-route :native}`；`model_adapter/request.clj`
  明确拒绝 native text，返回
  `{:code :ATTACHMENT_NOT_SENDABLE :reason :attachment-kind-unsupported
  :kind :text}`。
- W8 修复后（`native-capability :text -> nil`）纯函数复验：
  `TEXT-RAW → {:route :needs-choice :reason :extract-text}`；
  `TEXT-EXTRACTED → {:route :text :send-route :text}`；
  `prepare-send {:ok? true :issues []}`，`send-text` 有内容。
- UI 复验（8b210896）：上传 `note.txt` 后点“提取文字”，composer chip
  为 `note.txt · 37 B\n可发送\n移除`；proxy seq 14 的 user content 含
  `[Attachment: note.txt]\nE2E05 附件：部署检查清单。`，文本内联，
  未再出现 `:ATTACHMENT_NOT_SENDABLE`。证据：
  `plans/evidence/e2e-05-06/d15-text-attachment.json`。

### 4.4 断言结果（真实 Ollama + 真实 UI）

| # | 断言 | 结果 | 证据 |
|---:|---|---|---|
| 1 | 原消息可读（事实/假设/已取消要求） | PASS | `originalReadable=true`、`originalsReadableAfterApply=true` |
| 2 | 压缩预览可编辑（摘要 textarea + 6 条消息选择） | PASS | `boxCount=6`、`previewEditable=true` |
| 3 | 准备压缩（draft 出现、未应用） | PASS | `prepared=true`、`draftNotAppliedYet=true` |
| 4 | 使用摘要（`发送 0 条 · 摘要 1`） | PASS | `summaryOnlyAfterApply=true` |
| 5 | 摘要不升级假设 | PASS | `assumptionNotUpgraded=true`、`summaryVerbatimApplied=true` |
| 6 | 继续两轮消息已发送 | PASS（发送） | `contextAfterContinue`：`发送 4 条 · 摘要 1` |
| 7 | 继续时不同时带摘要与被替代历史 | **FAIL（快照时序）** | proxy seq 14：摘要出现 0 次；`请只回复：事实/假设/取消` 与 `E2E05 事实` 仍在请求里。但 store 的 `:context` 投影正确：call-11/12 为 `[摘要 system, 继续一 user, assistant, 继续二 user, assistant]` 5 条、`summary-ids=[cmp-708057b8…]`；即请求用的是压缩前冻结的 input-snapshot，不是 outbound-context 去重丢失 |
| 8 | 撤销只恢复范围、后续回答保留 | 未跑 | 脚本在 digest 处崩溃（`m.content` 为 undefined） |
| 9 | 新会话辨认来源、不带旧消息 | 未跑 | 同上 |
| 10 | assistant 文本非空 | **FAIL** | `reply1=""`、`reply2=""`；模型在继续轮返回 tool_calls/空文本 |

### 4.5 继续轮失败的产品侧观察

- Plan 的 nudge 轮在第二轮起出现
  `{:ok false :code :SCOPE_ANNOTATION_MISSING}`（模型把 `:scope` 传成
  字符串），随后 devops worker 报 `snapshot names an unknown model role`；
  config 只有 `:main`，没有 `:task`，devops worker 无法解析 task 角色
  （W36 只读诊断）。这会让 UI 出现“无人值守 · 自动继续 / 本轮未完成”。
- 脚本已放宽为“assistant 数增加即继续，nudge 失败单独记录”，但继续轮
  的 assistant 文本仍为空（模型返回 tool_calls 而非文本）。
- 逐条核对结论更正：`context/outbound-context` 本身正确。store 的
  call-11/12 `:context` 为 `[摘要 system, 继续一, assistant, 继续二,
  assistant]` 5 条、`summary-ids=[cmp-708057b8…]`，摘要原文与假设保留；
  但 proxy seq 14 的 19 条仍是压缩前历史（无摘要、含被替代消息）。
  因此缺陷是 **run 的 input-snapshot 在压缩 apply 后仍冻结旧投影**
  （快照时序），不是适配器丢消息或 outbound 去重逻辑。
- 进一步收窄（W28）：`run_port/frozen-entries` 在 `:input-snapshot` 缺失时
  `ids` 为空集，末尾 `filterv` 只会保留 `:context-summary`，不可能发出 19
  条旧消息；所以该 run 拿到的是一个**压缩前的 19 条 frozen context**，
  而不是 fallback 到当前投影。`conversation_bridge/send-snapshot!` 的
  `:composer/send` 只带 text/attachments/quotes，不带 context；旧 context
  来自服务端 run 创建时冻结的 `:input-snapshot`。需查 `run_port/start`
  的 input-snapshot 来源与 apply 回执合并、发送按钮可用之间的先后。

### 4.6 结论

**PARTIAL**：压缩预览/编辑/使用、摘要不升级假设、原消息可读、
`发送 0 条 · 摘要 1` 在 8b210896 上 PASS；D-15 text/plain 路由修复后
内联可发送。继续两轮的 input-snapshot 快照时序 FAIL（请求仍用压缩前投影、
仍在），assistant 文本为空，撤销/新会话未跑。E2E05 不能标 PASS。

证据：`plans/evidence/e2e-05/e2e05.json`、`e2e05.png`（12:48，
`bundle` 与 `pageBundle` 均为 8b210896）、
`plans/evidence/e2e-05-06/d15-text-attachment.json`。

## 5 W60 收口跑（2026-11-06，最终冻结 72de4f9a + 树 ab801450）

环境：flock /tmp/hc-app-single.lock；config05b（data3，Qwen 3.6 27B 经
proxy 18090，768m/512m）；config06（Qwen Text/Vision 双连接）；TTS stub
19150。脚本 /tmp/hc-w3e5r4/{e2e05v3.cjs,run06.cjs,e2e15-final.cjs,
probe05.cjs}，已加 prepare/apply/undo 重试。

### E2E06 — PASS（72de4f9a）
全部断言 PASS：bundle/pageBundle 实测 72de4f9a；文本模型图片不兼容
提示+发送被阻塞（消息数不变）；切 Qwen Vision；PDF 选 2–5 页（共 4 页）、
不可读第 3/5 页列出；真实 Qwen 答案引用 Page 2/4 并列 Unreadable；
移走原文件后已保存副本可读（blob 200，1,194,731 B）；关联工作区文件
外部引用+重新定位提示；发送被策略阻止（消息数不变）；附件请求仅
上传×2+1 次显式 extract+1 次 workspace，无隐藏 OCR。
证据：plans/evidence/e2e-05-06/e2e06-final-72de4f9a.json + 5 张截图。

### E2E05 — PARTIAL（72de4f9a）
PASS 子项：text/plain 附件提取后"可发送"、D-15 路由（请求含附件文本、
无 image_url）；3 条原消息+回复；压缩预览可编辑（6 框+摘要）；
原消息在 prepare 后仍可读。
FAIL/未达：`准备压缩` 被服务端 `:NOT_CONTROLLER` 拒绝（"this client is
observing; take control first"）。根因（probe05 + WS 抓包，证据
plans/evidence/e2e-05/e2e05-probe-72de4f9a.json）：Owner presence 中
controller-id 属于上一轮 client-id（5d65adc3），reload/新 client-id
（ecb1d403）只成为 observer；客户端全仓库无 `:session/attach-client`/
`:session/takeover` 发送点，也无接管 UI；旧 controller 不释放导致所有
history/* 命令被拒。继续两轮/outbound 摘要核对/新会话/撤销均未跑到。
另观察：nudge/devops 轮报"本轮未完成"（devops worker model failed，
D-19/D-20 相邻残留），assistant 第 4 条为空。

### E2E15 — 未取得 72de4f9a 证据
首轮跑在旧会话上 `重新生成` 未产生 v2（`.hc-message-versions` 180s 未
出现；会话有活跃 run/压缩上下文）。已写 e2e15-final.cjs（强制新会话+
停无人值守+regen 重试）未及在锁内复跑；W61 接管。
实时麦克风维持 BLOCKED（环境无音频输入）。

## 6 W61 收口跑（2026-11-06 晚，最终冻结 72de4f9a + 树 ab801450）

环境：`config05d.edn`/`config05e.edn`/`config06c.edn`（`:nudge-enabled? false`
E2E seam，config.clj:65），全新 data-dir（data4/data05e/data06b）+ 全新
Chrome profile + 单页不 reload（规避 W60 诊断的 `:NOT_CONTROLLER`：旧
client-id 的 controller 不释放，客户端无 attach/takeover 发送点）；app
768m/512m，`flock /tmp/hc-app-single.lock`；真实 Ollama qwen3.6:27b 经
logging proxy 18090；TTS stub 19150。树 hash 前后均为 `ab801450…`。

### E2E05 — PARTIAL（压缩 UI 全通；继续轮被产品缺陷阻断）

run6（21:41–22:07，`evidence/e2e05.json` →
`plans/evidence/e2e-05/e2e05-72de4f9a-run6.json`）首次在最终冻结上跑通
压缩全流程：

| 断言 | 结果 |
|---|---|
| text/plain 附件提取后可发送、D-15 路由（请求含附件文本、无 image_url） | PASS |
| 3 条原消息+回复、原消息可读 | PASS |
| 压缩预览可编辑（6 框+摘要 textarea） | PASS |
| 准备压缩（`使用摘要` 出现，draft 未应用） | PASS（重试机制生效，NOT_CONTROLLER 消失） |
| 使用摘要 → `发送 0 条 · 摘要 1` | PASS |
| 摘要不升级假设（保留"假设（未确认）"） | PASS |
| 用摘要建新会话（含来源标注、无旧消息） | PASS |
| 新会话 outbound 含摘要、不含被替代历史 | PASS（proxy seq53） |
| 撤销压缩按钮渲染、撤销后 `摘要 0`、原消息可读、之后回答保留 | PASS |
| 继续两轮 assistant 回复 | **FAIL（产品缺陷）**：两条用户消息已入会话，但 run 在模型调用前失败，proxy 无任何新请求；UI 报 `pair-hint: request projection failed` + `服务器报告本轮未完成` |
| 继续时不同时带摘要与被替代历史（outbound 核对） | 未取得（无模型请求可核对） |
| 撤销后再发一轮 | 同上缺陷（projection failed，无模型请求） |
| `压缩 已撤销` 标记 | FAIL（撤销生效但标记文案未出现） |

复测（`plans/evidence/e2e-05/e2e05-recheck-72de4f9a.json`，22:40）：
在已压缩+已撤销的源会话再发一条 → 仍 `本轮未完成`、proxy 计数不变
（22→22，零模型请求）；对照新会话发送 → proxy 22→23 有请求。
**确定性缺陷：含压缩记录的会话此后所有发送在 pair-hint 投影阶段失败，
模型永远收不到请求。** 根因方向：pair-hint placement anchor 指向被压缩
排除的消息，请求投影 `PAIR_HINT_ANCHOR_MISSING`/projection failed
（`src/hc/core/pair_hint/hint.clj:326-331`、`integration.clj:187`）。

### E2E06 — PASS（72de4f9a，补齐文本模型腿）

- 视觉腿沿用 W60 `e2e06-final-72de4f9a.json`（PASS）。
- 文本腿补跑（`plans/evidence/e2e-05-06/e2e06-textleg-72de4f9a.json`，
  config06c 全新 data06b，Qwen Text 无 :vision）：图片卡
  `需要选择处理方式 · 模型不接受原格式`，动作 提取文字/排除/移除 齐全；
  发送被阻塞（消息数 0→0）且有阻止提示；bundle/tree 双 hash 记录。
  唯一未过子项 `extractRouteAvailable=false`：点"提取文字"后卡片变
  `准备失败 · 文字提取失败`（本机无 OCR 后端，环境限制，非能力断言）。

### E2E15 — BLOCKED（regen 产品缺陷；版本切换机制另验）

- v5（22:07–22:27，`e2e15-v5-final.json`）：新会话首答正常，但脚本在
  首答后点击"停止"→ run 取消 → admission 关闭 → 4 次 regen 全部
  `:ADMISSION_CLOSED`（"history regeneration cannot start under a closed
  admission"）。脚本缺陷，已修（v6 不再点停止）。
- v6（22:40–22:52，`plans/evidence/e2e-13-15-16-21/e2e15-v6-regen-defect-72de4f9a.json`）：
  不再点停止后 regen 命令真实发出（回执含 history 命令），但 run 失败：
  `:PAIR_HINT_ANCHOR_MISSING` ×1 + `:OWNER_ERROR` NPE ×3
  （`Cannot invoke IFn.invoke(Object,Object,Object) because "error" is null`，
  对应 `src/hc/server/history.clj` regenerate 拒绝分支里 `(error ...)`
  被 `{:keys [message error]}` 解构遮蔽为 nil 的调用）。`.hc-message-versions`
  始终不出现 → 无 v2 → 版本切换无法进行。期间 UI 出现"连接中断"。
- 结论：**`:history/regenerate` 在最终冻结上不可用**（pair-hint anchor
  指向被 regenerate 排除的目标消息 + 拒绝路径 NPE），E2E15 版本切换
  （regen 产生 v2）BLOCKED；编辑产生 v2 的回退验证另跑（v7，见下）。
- 实时麦克风维持 BLOCKED（环境无音频输入，沿用前轮结论）。

### W61 补记（00:5x）：缺陷定性为 D-22，W63 已修

- W63 定位同一根因：`run_port.clj`/`context.clj` 投影丢 `:message-id` →
  pair-hint `PAIR_HINT_ANCHOR_MISSING` → 模型请求未发出（DEBT D-22）。
  W63 修复后源码树变为 `36fddd6d…`（bundle `72de4f9a` 不变，final11
  基线失效待新全量）。W61 ab801450 证据即缺陷复现记录。
- 残留：`history.clj` regenerate 等拒绝分支 `(error ...)` 被
  `{:keys [message error]}` 遮蔽 → `:OWNER_ERROR` NPE（已记入 DEBT D-22）。
- W61 runner5 待 final14 新树复跑 E2E05 全流程 + E2E15 regen 路径
  + E2E06 文本腿；结果待 final14 冻结（新 bundle+树）后补 §7。

## 8 W62 E2E05 controller 绕过 + v6 复跑（2026-10-07，72de4f9a + 树 ab801450）

环境：flock `/tmp/hc-app-single.lock` 内启动 config62（data62/blobs62 全新，
Qwen 3.6 27B 经 proxy 18090，768m/512m，`:nudge-enabled? false`）；
Playwright + 系统 Chrome，独立 profile `chrome05w62`。脚本
`/tmp/hc-w3e5r4/e2e05v6.cjs`（v5 基础上加 WS 捕获钩子与 takeover 注入）。

### controller 绕过方式（不改产品）

- `ctx.addInitScript` 包装 `WebSocket`，捕获页面 Sente socket 与全部收发帧。
- 页面 socket URL 含 `?client-id=<uuid>`（Sente 握手参数，服务端 uid=client-id）。
- 在页面内直接 `sock.send()` 一帧 Transit/JSON 手工构造的
  `[["~:hc/command",["^ ","~:command-id","<uuid>","~:command-epoch",1,
  "~:subscription-id",1,"~:payload",["^ ","~:kind","~:session/takeover",
  "~:session-id","<sid>"]]]]`。
- 服务端回执 `hc/command-receipt`：`~:outcome ~:taken-over` /
  `~:already-controller`，`~:controller-id` = 本页 client-id。
  证据：`plans/evidence/e2e-05/e2e05-takeover-smoke.json`。
- 该命令是 Owner 允许观察者提交的唯一命令，gateway 未禁用；
  帧格式经 `taoensso.sente.packers.transit` 实测核对。

### E2E05 断言结果（e2e05v6，data62 全新）

| 断言 | 结果 |
|---|---|
| controller-claim（takeover 回执 taken-over/already-controller） | PASS |
| text/plain 附件提取后可发送、D-15 路由（无 image_url） | PASS |
| 3 条原消息+回复、原消息可读 | PASS |
| 压缩预览可编辑（6 框+摘要 textarea） | PASS |
| 准备压缩 / 使用摘要 → `发送 0 条 · 摘要 1` | PASS |
| 摘要不升级假设 | PASS |
| 用摘要建新会话（含来源、无旧消息） | PASS |
| 新会话 outbound 含摘要、不含被替代历史（proxy seq65） | PASS |
| 继续两轮 assistant 回复 | **FAIL（D-22 产品缺陷）**：`pair-hint: request projection failed` + `服务器报告本轮未完成`，proxy 无新请求 |
| 继续时不同时带摘要与被替代历史 | **FAIL（快照时序缺陷）**：proxy seq61-64 仍含压缩前 12 条历史、无摘要（冻结 input-snapshot）；seq65 新会话请求正确 |
| 撤销压缩按钮渲染 | **FAIL**：切回源会话后 `:compressions` 为空（`摘要 0`），按钮未出现；undo 命令未发出 |
| 撤销后回答保留 | PASS（原 3 条回答仍在；撤销后未能再发一轮，因上述缺陷） |

证据：`plans/evidence/e2e-05/e2e05-72de4f9a-final.json`、
`e2e05-72de4f9a-final.png`、`e2e05-takeover-smoke.json`。

### 结论

**PARTIAL**。`:NOT_CONTROLLER` 已通过 `:session/takeover` 绕过（不改产品）；
压缩预览/编辑/使用/新会话摘要来源/原消息可读全部 PASS；
继续两轮被 D-22（pair-hint 投影失败）阻断；outbound 仍发压缩前冻结快照；
撤销按钮在会话切换后未渲染（compressions 投影为空）。E2E05 不能标 PASS。

## 9 W76 final15 复跑（2026-11-07 06:01–06:39，bundle `a14d9abd` + 树 `651b9a45`）

环境：flock `/tmp/hc-app-single.lock` 内串行三段；config05w76/config06w76
（`:nudge-enabled? false`，全新 data05w76/data06w76 + work/blobs/worktrees/
artifacts w76 后缀，Chrome profile chrome05w76 全新；E2E15 复用同 app 不另起
profile）；app 768m/512m；真实 Ollama qwen3.6:27b 经 logging proxy 18090；
TTS stub 19150。脚本 `/tmp/hc-w3e5r4/w76-run5.sh` 调 `e2e05v5.cjs`（正则已修
全角冒号）、`e2e15-v6.cjs`、`e2e06-textleg.cjs`。tree_hash 前后均
`651b9a45…`；bundle `a14d9abd…`（3,015,902 B）/CSS `332f9fd3` 由各 JSON
`bundle`/`css` 字段与 `freeze` 双记。

### E2E05 — PARTIAL（D-22 修复生效；撤销投影/标记仍有产品缺陷）

证据：`plans/evidence/e2e-05/e2e05-a14d9abd.json` + `.png`、
`e2e-05/w76-run5-final15.log`（06:01:40–06:22:27，exit 0）。

| 断言 | 结果 |
|---|---|
| text/plain 附件提取后可发送、D-15 路由 | PASS |
| 3 条原消息+回复、原消息可读 | PASS |
| 压缩预览可编辑、准备压缩、使用摘要 `发送 0 条 · 摘要 1` | PASS |
| 摘要不升级假设、用摘要建新会话（含来源、无旧消息）、新会话 outbound 含摘要不含被替代历史 | PASS |
| **继续两轮 assistant 回复** | **PASS（D-22 已修）**：proxy seq75/76 正常发出，outbound 含摘要且不含被替代历史，`继续1`/`继续2` 回复到达 |
| 撤销压缩按钮渲染、撤销后 `摘要 0`、原消息可读、撤销后再发一轮有回复 | PASS |
| 撤销后 outbound 恢复原历史（`undoSendsRestoredHistory`/`undoDropsSummary`） | **FAIL（产品缺陷）**：proxy seq78 仍含压缩摘要、不含被撤销的 3 条原消息（UI 上下文已 `发送 0 条 · 摘要 0`，但模型请求投影未回退） |
| `压缩 已撤销` 标记（`undoneMarked`） | **FAIL**：撤销生效但标记文案未出现（与 ab801450 同现象） |
| `outboundCounts` | FAIL（**脚本缺陷**：`摘要\s*1`、`排除\s*(\d+)` 不匹配 UI 全角冒号+换行；run6 同因；已修 `/tmp/hc-w3e5r4/e2e05v5.cjs` 正则，不改产品） |

### E2E15 — PASS（regen 路径，final15 首次全断言通过）

证据：`plans/evidence/e2e-13-15-16-21/e2e15-v6-regen-a14d9abd.json` + `.png`
（06:22:27–06:39:11，exit 0，同 app 续跑）。`assertions.pass=true`、
`pageErrorsZero=true`：regen 发出后产生 v2（`.hc-message-versions` 出现），
版本切换后旧朗读 `paused=true`/`currentTime=0`/3 s 不继续，切回 v2 可重新
朗读（新 audio paused=false currentTime≈1.5），版本按钮更新。D-22
（PAIR_HINT_ANCHOR_MISSING）与 history.clj 拒绝分支 NPE（:OWNER_ERROR）在
final15 上均不再出现。实时麦克风仍 BLOCKED（环境无音频输入）。

### E2E06 — PASS（文本腿，final15）

证据：`plans/evidence/e2e-05-06/e2e06-textleg-a14d9abd.json` + `.png` +
`.bundle.txt` + `tree-a14d9abd-651b9a45.txt`（06:39:25–06:39:47，config06w76
全新 data06w76）。Qwen Text 无 :vision：图片卡“需要选择处理方式 · 模型不
接受原格式”、提取文字/排除/移除齐全、发送被阻塞（0→0）且有阻止提示 PASS。
唯一未过 `extractRouteAvailable=false`：点“提取文字”→ `准备失败 · 文字
提取失败`（本机无 OCR 后端，环境限制，与 72de4f9a 一致）。视觉腿沿用
`e2e06-final-72de4f9a.json`（产品路径未变）。

### 结论

- **E2E05 PARTIAL→仍 PARTIAL**：D-22 修复确认（继续两轮/撤销 UI 全通），
  残留两个产品缺陷：①撤销压缩后模型请求投影未恢复原消息范围（仍带摘要、
  缺原 3 条）；②`压缩 已撤销` 标记未渲染。另修脚本正则缺陷（非产品）。
- **E2E15 regen 路径 PASS**（final15 首次）；版本切换回退路径仍绑前冻结
  `5addfe1d`，如需可在 final15 复跑 v7。
- **E2E06 PASS**（文本腿复验一致；OCR 提取为环境限制）。

## 10 W93/W101 final19 复跑（2026-11-07 13:23–14:12，bundle `168318c1` + 树 `f8477191` 运行起点）

并发策略下 W93 与 W101 各自独立实例并行（`/tmp/hc-w3e5r4` w93f、
`/tmp/hc-w3e5r4` w101f19b，独立 data-dir/Chrome profile/端口）。E2E05 均用
`e2e05v8`（sessionId DOM fallback + reuse-blank + controller-claim）。

### E2E05 — PARTIAL（D-24 关闭 / D-25 仍 FAIL）

- W101b（13:51–14:12，`e2e05v8-w101b.cjs`，proxy-w101 独立日志）：
  **38/42 PASS**。撤销段完整执行：撤销后 outbound（proxy-w101 seq7，
  14 msgs）含全部原消息（事实/假设/取消/继续一/继续二/撤销后）且
  **不含压缩摘要**——`undoSendsRestoredHistory`/`undoDropsSummary`/
  `afterUndoOutboundHasNewMessage` 全 PASS → **D-24 关闭**。
  **D-25 仍 FAIL**：撤销命令被拒（UI「命令未送达」），上下文面板仍
  「发送 4 条 · 摘要 1 / 含压缩摘要 / 排除 6」，「撤销压缩」按钮与
  「压缩 已采用」行仍在（`undone`/`undoneMarked`/`undoRestoresRange`
  false）；`压缩 已撤销` 标记未出现。疑 epoch/selection-seq 在
  new-session 切换后过期，待修复+final20 复跑。
- W93（13:23–13:44，`e2e05v8.cjs`，proxy64.jsonl 因并发清空致
  outbound 断言不可判）：26/32 PASS，撤销段同样 `undone`/`undoneMarked`/
  `undoRestoresRange` false、`undoRetry` true。
- sessionId 间歇缺陷（v5 两轮 null）由 v8 DOM fallback 解决：两轮均取到
  `hc-session-*`，`controller-claim took=true`。
- 证据：`plans/evidence/e2e-05/e2e05-v8-168318c1-final19{,-w101b}.{json,png}`、
  `tree-f8477191-final19-before.txt`/`tree-25beab95-final19-after.txt`
  （树运行中漂移系并发他人改动，bundle 未变）。

### E2E15 — PASS（regen 路径，final19 复验）

`e2e15-v6`（13:44–14:00）：`pass:true`、`pageErrors []`、regenCount=1、
版本切换 v1↔v2 全断言（audioWhilePlayingV2/audioAfterSwitchV1/
oldAudioPaused/oldAudioAtZero/oldAudioStaysPaused/playerGoneAfterSwitch/
versionButtonsUpdated/pageErrorsZero）。证据
`plans/evidence/e2e-13-15-16-21/e2e15-v6-regen-168318c1-final19.{json,png}`。

### E2E06 — PASS（文本腿，final19 复验）

`e2e06-textleg`（14:01）：与 final14/16/17/18 完全一致——
`extractRouteAvailable=false`、"准备失败 · 文字提取失败"（本机无 OCR
后端，环境限制，五轮复现）；其余 5 项 PASS、pageErrors []。证据
`plans/evidence/e2e-05-06/e2e06-textleg-168318c1-final19.{json,png}`。

### 结论

- **E2E05 PARTIAL**：D-24 关闭（撤销投影恢复）；D-25 残留（撤销命令
  被拒/UI 未更新/标记缺失）。
- **E2E15 PASS**（final19 regen 路径复验一致）。
- **E2E06 PASS**（文本腿五轮一致；OCR 提取环境限制）。
