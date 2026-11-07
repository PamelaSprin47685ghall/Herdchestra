# E2E 03 / 04 诊断（D7 首 chunk、停止标签、分支与导出）

> 范围：客户端流式投影、停止状态、历史编辑版本、分支创建/持久化、导出过滤。
> 证据目录：`plans/evidence/e2e-03-04/`；真实运行记录见 `plans/e2e-real-03-04.md`。
> 未执行 git 写操作；本仓库只有 `AGENTS.md` 被跟踪，`git diff` 对源码文件恒为空，
> 因此改动前以读取当前实现与文件 mtime 为准，并避开 W4/W8 正在编辑的函数。

## 1 D7：UI 只显示首 chunk

### 1.1 事实链

1. Owner 会话的助手消息只维护 `:text`：每个模型 chunk 由
   `hc.compose.owner.state/append-run-text` 追加，再经 `put-assistant`
   写回 `:conversation`。Owner 状态里文本是完整的（D7 的
   `d7-owner-state.txt` 已有 120+ 字完整回答）。
2. Owner 同时把每个 chunk 镜像进会话历史
   （`hc.compose.owner.handlers/mirror-history-message!` →
   `:history/append-message`）。该命令是 **append-once**：
   `hc.server.history/append-message` 对已存在的 message-id 只在
   当前版本正文为空、或载荷带 `:partial? true` 时升级正文
   （`history.clj` 的 `upgrade?`）。chunk 镜像没有带 `:partial?`，
   所以历史版本永远停在第一个 chunk。
3. 发布快照时 `hc.server.app.snapshot/baseline` 把实时会话投影与历史投影
   合并，`:content` 优先取非空的 **history-content**，而 `:text` 取
   实时会话的完整文本：

   ```clojure
   {:content (or history-content conversation-content)
    :text    (or (:text conversation) (:text history))}
   ```

   于是同一个消息实体里出现“`content` = 首 chunk、`text` = 完整回答”。
4. 客户端 `timeline/message-view` 的 `body-text` 原先按字段顺序取第一个
   非空值，`content` 排在 `text` 前面，因此渲染出首 chunk。刷新后仍是
   首 chunk，因为快照本身就携带这份旧 `content`；不是流被停止，也不是
   DOM 丢块。
5. 修复（`src/hc/client/conversation/timeline.cljc` 的
   `newest-text`）：在消息的多个显示副本中，若一个值是另一个值的
   前缀延伸，取更长的那个；非前缀关系视为真实编辑，保留先出现的
   （选中版本）值。该修复不暂停接收、不丢弃 chunk、不冻结渲染。
   回归：`conversation-test/snapshot-shows-live-stream-text-over-stale-history-content`。

### 1.2 历史镜像修复（2026-11-05 收口）

历史镜像原先对已存在 message-id 只在正文为空或 `:partial? true` 时
升级，chunk 镜像没有 `:partial?`，所以历史版本停在首 chunk；进程重启
后 Owner 会话由历史重建，完整正文丢失。

修复（`src/hc/server/history.clj` 的 `append-message`）：对“单版本、
未编辑、非手工”的 assistant 消息，当新正文是当前正文的前缀延伸时原地
升级同一版本；用户消息、已编辑回答、非前缀替换不走该路径，重复同一
chunk 幂等。消息 id、顺序与版本数不变。回归
`history-test/streaming-assistant-chunks-grow-the-single-version`
（22 tests / 267 assertions）覆盖 chunk 增长、终态全文、重复幂等、
用户消息不被覆盖、已编辑回答不被迟到 chunk 改写。

## 2 停止状态标签

`conversation.view` 已有 `.hc-stream-status`（`role=status`），条件是
stream 状态为 `:stopping`/`:stopped`；`stream/status-label` 提供
“正在停止/已停止”。D7 未看到字面标签，需要区分三种可能：

1. 停止命令未进入 reducer（连接未同步 / epoch 缺失 / 已被
   `:already-stopping` 忽略）→ 状态从未离开 `:idle`；
2. 回执被 `stop-receipt-stopped?` 判为不匹配 → 状态停在 `:stopping`
   （仍应显示“正在停止”）；
3. 选中会话的 `:ui/stream` 与视图读取的键不一致。

真实复跑按 300ms 采样 `正在停止/已停止` 文本，并同时记录命令回执与
`.hc-stream-status` 是否出现；结论写入 `plans/e2e-real-03-04.md`。

## 3 E2E 04：分支

### 3.1 分支为空

服务端 `sessions/branch-session` 的语义是“复制分支点 **之前** 的消息”：

```clojure
prefix (take-while #(not= before-message-id %) order)
```

客户端视图把按钮所在消息的 id 直接当作 `:before-message-id` 发送，
所以从第一条用户消息分支时前缀为空，分支显示“还没有消息”。
根 AGENTS §4.7-1 要求“包含选中位置之前的当前路径及选中消息”，
因此这是客户端映射错误，而不是“空分支是产品语义”。

修复（仅客户端）：视图在渲染分支按钮时传入 **下一条可见消息 id**
（最后一条传 nil，服务端语义为复制全部），使选中消息本身进入分支；
同时只在 `:user`/`:assistant` 角色上显示分支按钮，避免从 tool-call
等过程消息分支时服务端回 `MESSAGE_NOT_FOUND`，产生“命令未送达”
横幅与瞬态会话。

### 3.2 分支瞬态/未持久化

分支回执合并已在 `workbench.events/apply-receipt` 实现：接受回执携带
`:session`、`:messages`、`:message-order` 时合并实体并选中新分支。
服务端 `registry/branch-session!` 走 `commit-and-register!` 持久化
会话记录。真实复跑验证两点：

- 分支创建后消息立即可见（不是空会话）；
- 停止实例再以同一 data-dir 启动，分支仍可从会话列表打开，消息与
  来源横幅仍在。

若重启后仍为空，则缺口在服务端提交/重建，不在客户端回执合并。

## 4 E2E 04：导出

`conversation/export` 默认只放行 `:user`/`:assistant` 可见正文，
排除 `:tool`/`:activity`/`:reasoning`/`:system`、`defprogram`、
`raw-source`、工具结果映射；`include-tools?` 默认 false，只有操作者
显式勾选“包含工具详情”才渲染过程区。导出范围来自调用者：

- “导出当前分支”使用当前选中会话的可见路径消息；
- 多选导出只使用勾选消息。

真实复跑断言：导出文本不含 `:tool-call-id`、`defprogram`、
`raw-source`、Authorization/Bearer/sk- 前缀，且不含另一分支的正文。

## 5 结论状态（2026-11-05 复跑后）

| 项 | 状态 | 说明 |
|---|---|---|
| D7 首 chunk（刷新前后） | **PASS（客户端）** | `timeline/newest-text` + 真实 stub 长流：24 帧、UI 文本持续增长 |
| 停止标签 | **PASS** | 点击后 300ms 内出现“已停止”，`.hc-stream-status` 可见 |
| 分支包含选中消息 | **已实现，真实 UI 未复跑** | 视图传下一条可读消息 id；最后一条传 nil |
| 分支回执合并 | **已实现，真实 UI 未复跑** | `events/apply-receipt` |
| 分支持久化 | **未验证** | 服务端已提交；需要干净实例重启复跑 |
| 导出过滤 | **已实现，单元测试通过** | 默认排除工具/隐藏字段；真实导出未复跑 |
| 停止后零模型请求 | **PARTIAL** | 可见正文不追加、无新消息；Plan Nudge 在 stub 下重试（见 `plans/e2e-real-03-04.md` §1.3） |
| 切回会话内容保留 | **未验证** | 首次脚本选择器错误；已改为行内按钮，未再复跑 |

真实运行细节与逐条断言见 `plans/e2e-real-03-04.md`；证据在
`plans/evidence/e2e-03-04/`。
