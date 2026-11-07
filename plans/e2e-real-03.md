# Herdchestra E2E 03 流式多 chunk 复跑（2026-11-05）

> 真实 app + 真实 Chrome + 长流 stub（207 chunk × 250ms）。未修改产品源码；
> 未执行 git 写操作。

## 1 准备

- `client-release` 后 bundle：`resources/public/js/main.js`（2026-11-05
  13:37:43）。
- **重建后重启 app**，再启动新 Chrome 页面；不复用旧 tab。
- stub：`/tmp/hc-e2e-real/stub-long.js`，`PORT=18083`、`CHUNK_MS=250`、
  `REPEAT=4` → `chunks=207`，纯文本 SSE。
- app 配置：`stub-long` OpenAI-compatible 连接，main/task 均指向它；
  `/healthz :ok true`。
- 测试：`e2e03stop.cjs`，模型标签 `Stub Long (E2E03 UI)`。

## 2 结果

| 断言 | 结果 | 证据 |
|---|---|---|
| 新会话绑定模型 | PASS | `modelBound: true`，模型标签正确 |
| UI 文本随时间增长 | **FAIL** | `chunksRendered: 1`，`textAtSelection: 12`，`textGrowthDuringWait: 0` |
| 多 chunk 渲染 | **FAIL** | `multiChunkRendered: false`；文本停在首个 12 字 chunk |
| 选中保留 | PASS | `selectionKept: true` |
| 上滚保留 | PASS | `scrollKept: true` |
| 草稿保留 | PASS | `draftKept: true`、`draftKeptAfterStop: true` |
| 停止状态 | PASS | 观察到“已停止” |
| 停止后内容保留 | PASS | `charsAtStop: 12`、`charsAfterStop: 12` |
| 迟到不追加 | PASS | `noLateAppend: true` |
| 停止后无新请求 | PASS | `lateRequests: 0`（15s 窗口） |
| 停止后无新消息 | PASS | `noNewMessage: true` |

## 3 结论

- **停止路径 PASS**：停止可见、状态到“已停止”、内容保留、迟到不追加、
  15s 内 0 个新模型请求；选中/滚动/草稿均保留。
- **多 chunk 渲染 FAIL**：真实 bundle 下仍只渲染第一个 12 字 chunk，
  `multiChunkRendered: false`。服务端 stub 发送 207 帧，但 UI 文本不增长。
  本轮不能把“delta 处理已修（687/4195）”当作真实 UI 已验证。
- 可能原因：bundle 中流事件路径仍把后续 delta 当完整替换/丢弃，或
  客户端只收到首个 `TEXT_MESSAGE_CONTENT`；测试的 WebSocket 帧计数也为 0，
  说明该测试对 Sente 帧的观测方式可能失效，但 UI 文本 12 字是直接证据。
  需在 dev bundle 下检查 `TEXT_MESSAGE_CONTENT` 的实际 payload 形状。

## 4 证据

- `/tmp/hc-e2e-real/artifacts/e2e03/e2e03-stop-result.json`
- `/tmp/hc-e2e-real/artifacts/e2e03/e2e03-stop-final.png`
- `/tmp/hc-e2e-real/stub-long.log`
- `/tmp/hc-e2e-real/config-stub-long.edn`
- `/tmp/hc-e2e-real/e2e03stop.cjs`
- `/tmp/hc-e2e-real/stub-long.js`

## 5 bundle 重建规则

已写入 `/tmp/hc-e2e-harness/README.md`：`client-release` 重建
`resources/public/js/main.js` 后，必须重启 app 并使用新的 Chrome 页面；
旧 tab 可能继续使用内存中的旧 bundle。静态文件虽从磁盘读取，但浏览器
缓存/旧页面不会自动替换。

## 6 帧级证据（2026-11-05）

- 方法：在 `page.addInitScript` 里包装 `window.WebSocket`，记录所有收到的
  帧（时间、长度、原始 data）；同时每 500ms 采样
  `.hc-message-in:not(.hc-message-process) .hc-message-body` 的文本长度。
- 原始文件：`/tmp/hc-e2e-real/e2e03/frames.json`
  （含 first10/last10、tokenCounts、24 个文本长度样本）。
- stub：207 chunk × 250ms；app 在 bundle 重建后重启，新 Chrome 页面。

### 帧统计（104 帧）

| token | 帧数 |
|---|---|
| `TEXT_MESSAGE_CONTENT` | 48 |
| `TEXT_MESSAGE_START` | 0 |
| `TEXT_MESSAGE_END` | 0 |
| `delta` | 50 |
| `text` | 101 |
| `content` | 53 |
| `seq` | 51 |
| `replace` | 50 |
| `append` | 3 |
| `snapshot` | 52 |

### 首帧内容

第一条 `TEXT_MESSAGE_CONTENT`（frame 8）含：

```text
~:type "TEXT_MESSAGE_CONTENT"
~:delta "本地模型推理的第一件事，"   ; 12 字
```

同一帧还带 `STATE_DELTA`（seq 4）。后续帧仍出现
`TEXT_MESSAGE_CONTENT`，但 UI 文本不再增长。

### UI 文本长度序列

24 个 500ms 样本全部为 **12**：

```text
[12,12,12,12,12,12,12,12,12,12,12,12,
 12,12,12,12,12,12,12,12,12,12,12,12]
```

### 结论

**只应用第一帧**。帧级数据确认服务端持续发送 `TEXT_MESSAGE_CONTENT`
（48 条）与 `delta`，但 UI 从第一条 12 字 delta 后不再增长。问题在客户端
消费路径：后续 `TEXT_MESSAGE_CONTENT` 的 `:delta` 未被追加，或同帧
`STATE_DELTA` 的 `replace` 覆盖为旧全文。需要 client-audit 按
`frames.json` 对比 `:delta` 与 `STATE_DELTA :op/:path` 的实际应用顺序。

### 帧文件

- `/tmp/hc-e2e-real/e2e03/frames.json`
- `/tmp/hc-e2e-real/e2e03/frames.png`
- `/tmp/hc-e2e-real/frame_probe.cjs`

## 6 帧级取证与修复（2026-11-05）

`/tmp/hc-e2e-real/e2e03/frames.json`：104 帧；`TEXT_MESSAGE_CONTENT` 48、`delta` 50、`replace` 50、`append` 3、`seq` 51、`snapshot` 52。首个 `TEXT_MESSAGE_CONTENT`（frame 8）`:delta` 为 12 字“本地模型推理的第一件事，”，同帧还带 `STATE_DELTA seq 4`；后续每帧都是 TEXT + STATE_DELTA 成对。

修复：

- `transport/dispatch-event!` 现在处理 Sente 的**嵌套事件数组**：对 `?data` 中每个 `[:hc/event payload]` 逐个分发，不再把整帧当未知事件丢弃。
- `sync/adapter/merge-into-db` 在合并服务器 `:message/by-id` 时，如果本地消息是 `:streaming` 且 content 更长，保留本地 content，避免 `STATE_DELTA` 用旧全文覆盖已追加的流式文本。
- `events/text-delta` 已区分 `:delta` 追加 / 完整 `:text` 替换，无 message-id 用 active-message-id。

仍失败：`e2e03-stop-result.json` 的 `chunksRendered=1`、`textAtStop=12`、`textAfterStop=12`。剩余怀疑：同一帧内 TEXT 的 `apply-run-event` 与 STATE_DELTA 的 `install-sync` 事务顺序/旧 db 快照，使 STATE 仍覆盖；或 `events/apply-event` 未收到 TEXT。需要在这两个 mutation 前后打印 `:message/by-id` content 长度确认。
