# `hc.client.conversation.attachments` — 附件队列与卡片

> 权威顺序：根 `AGENTS.md` §3.4、§6.7、§7.4 旅程 6.6（多模态附件） > `src/AGENTS.md` > `src/hc/client/AGENTS.md` > `src/hc/client/conversation/AGENTS.md` > 本文件。
> 本组件独立于会话面：会话面继续拥有草稿文本、光标与发送门槛；本组件拥有“加入草稿前”的附件队列。

## 1 边界

拥有：

1. 单草稿最多 `20` 个附件（失败项、已排除项也占位），单个本地导入文件最多 `100 MiB`。
2. 卡片状态：`:adding`、`:ready`、`:needs-choice`、`:unsupported`、`:failed`，以及显式 `:excluded?`。
3. 真实摄取结果采用：只有服务端返回 canonical `sha256-...` 本地副本后才 `:ready`；客户端占位符绝不冒充就绪。
4. 模型兼容显示：已声明能力才显示原生路线；未知能力显示“尚未确认”；真实提取文本才允许“发送提取文本”。
5. 批量加入提示“已加入 N 个，M 个需要处理”；失败项保持可见，直到移除或排除。
6. 重复添加提示：同名同大小同指纹标记 `:duplicate-of`，但仍保留副本，绝不静默双倍上传或静默丢弃。
7. 卡片视图模型与 React 组件：名称、类型、大小、状态、路线、移除/重试/排除/预览动作，44×44 热区与可读 `aria-label`。

不拥有：文件选择器、HTTP 上传、命令 ID、服务器领域终态、OCR/PDF 提取、消息历史附件。移除只移除草稿引用，不删除用户原文件或已发送消息中的副本。

## 2 命名空间

| 命名空间 | 职责 | 纯 CLJC |
|---|---|---|
| `hc.client.conversation.attachments` | 队列状态机、限额、兼容显示、卡片/发送摘要 | 是 |
| `hc.client.conversation.attachments.view` | 薄 React 面，只调用纯函数并上报手势 | 否（`.cljs`） |
| `hc.client.conversation.attachments.surface` | 组合队列与 `conversation-page`，合并 snapshot 到草稿 | 否（`.cljs`） |
| `hc.client.conversation.attachments.storage` | 按会话持久化队列；中断上传显式失败 | 是 |

## 3 队列状态

```clojure
{:items [{:id "att-1"
          :attachment-id "att-1"
          :name "a.png"
          :mime "image/png"
          :size 2048
          :source :local-import
          :status :ready
          :ready? true
          :excluded? false
          :local-copy? true
          :blob-id "sha256-..."
          :workspace {...}
          :extraction {:status :ready :text "..."}
          :prepared {:blob-id "sha256-..." :mime "image/png" :width 2048}
          :route :native
          :send-route :native
          :detail "将发送原文件"}]
 :next-seq 1
 :limits {:max-items 20 :max-bytes 104857600 :max-pages nil}
 :model {...}}
```

主要函数：

- `(initial model)` — 空队列。
- `(add-file queue {:name :mime :size :source :fingerprint})` — 加入一张 `:adding` 卡片；拒绝超限/非法名称/非法大小。
- `(add-files queue requests)` — 按顺序加入，保留每个拒绝原因与批量提示。
- `(mark-uploaded queue id descriptor)` — 采用真实摄取描述符；无 canonical blob ID 时保持失败。
- `(mark-failed queue id reason)`、`(mark-extracted queue id extraction)`。
- `(apply-model queue model)` — 重新计算每张卡片的路线与可发送状态，保留本地副本和提取结果。
- `(remove-item queue id)` — 移除草稿引用；不删除源文件。
- `(exclude queue id)` / `(include queue id)` / `(retry queue id)`。
- `(cards queue)`、`(send-summary queue)`、`(snapshot queue)`。

`send-summary` 的 `:total` 包括失败与已排除项；`:blocked` 只统计未排除且未就绪项。`:sendable?` 表示附件策略没有未结阻塞（空队列或全部已排除也为 true，纯文字仍可发送）；`:has-ready?` 才表示确实有可发送附件。`snapshot` 只输出未排除项，并只在 `:send-route :text` 时携带 `:send-text`（来自真实 `:extraction`，绝不使用预览冒充确认文本）。

## 4 与 conversation-ui / workbench 的接口

工作台推荐只挂一个组件：

```clojure
(attachments-surface/conversation-with-attachments
 {:conversation-props props
  :queue queue
  :on-queue-change! (fn [next-queue] ...)
  :on-retry! (fn [{:keys [attachment-id card]}] ...)
  :on-preview! (fn [{:keys [attachment-id card]}] ...)
  :client-id "stable-tab-id"})  ; optional; defaults to sessionStorage per-tab id
```

`conversation-with-attachments` 在 `conversation-page` 上方渲染队列、文件入口与拖放区，并把 `(attachments/snapshot queue)` 合并进 `:draft :attachments` 后再渲染。组件用 `:model`（或 `conversation-props :session :model`）的 `:capabilities`/`:attachment-limits` 对队列执行 `apply-model`；未知能力不会被当作支持，图片/PDF 在确认前保持阻塞。队列为空且草稿已有附件时保留原草稿附件，避免打开旧会话时误清空。`from-draft` 可把已有草稿附件接管进队列。

发送确认后只清除本次已发送的附件：组件包装 `:send!`，在 `:ui/command` 出现同一会话的 `:send` `:accepted` 回执时调用 `clear-sent`；`:rejected`/`:failed` 保留全部卡片。这样发送期间新加的附件不会被误删，被拒发送也不会丢草稿。

队列按“客户端标签页 + 会话”持久化到内存缓存与 localStorage（`hc.attachments.v1:<client-id>:<session-id>`）。`client-id` 默认来自每标签页的 `sessionStorage`，因此同一会话的两个标签页不会互相覆盖；切换会话、同一标签页刷新后恢复同一队列；上传中关闭页面/刷新时该项恢复为 `:failed :UPLOAD_INTERRUPTED`，绝不冒充 ready。首次打开会话且无本地队列时用 `from-draft` 接管服务器/本地草稿附件。队列一旦建立就是该会话附件的权威：发送/移除的 id 记入 `:tombstones`，即使服务器/本地草稿迟到地仍列出这些附件，也不会被 `merge-draft` 重新塞回，避免重复发送；草稿中从未见过的合法附件仍可接管。localStorage 不可用时内存缓存仍支持当前标签页内的会话切换，并显示“附件队列暂未保存到本地”。

发送开始时把本次附件 id 写入队列的 `:pending-send` 并持久化。发送确认（`:accepted`）后清除这些项；拒绝/失败后清除标记但保留卡片。上传入口用同步 ref 串行化，`add-file` 跳过已存在的 `att-N`；上传回调只允许更新仍在 `:adding` 的卡片，不能覆盖已确认的提取、排除或路线状态。若发送期间切换会话或刷新，重新挂载时用当前草稿附件与已接受命令核对：草稿已无这些附件则视为已发送并清除，草稿仍有则保留，避免丢附件或重复发送。上传回调与提取回调都合并到最新队列（`merge-items`），不会把期间移除/更新的卡片用旧快照覆盖回来。


`conversation-ui` 的草稿仍使用普通 `:attachments` vector。Workbench 建议：

1. 在输入区上方挂载 `attachments.view/attachment-queue`，传入 `:cards`、`:summary` 与回调。
2. 文件选择/拖放/粘贴后调用 `add-file`；通过 `POST /attachments` 上传字节。
3. 上传成功后 `mark-uploaded`；真实文档提取成功后 `mark-extracted`；模型变化时 `apply-model`。
4. 发送前用 `send-summary` 判断是否可提交；不可提交时显示修复路径，不静默丢附件。
5. 把 `(snapshot queue)` 合并进草稿 `:attachments`；会话面的 `draft/sendable?` 与 `draft/snapshot` 会继续过滤 `:excluded?`。

`snapshot` 只产出会随消息发送的稳定字段，并保留上传描述符里的 `:url`（`/blobs/<id>` 不可变链接），让发送后的历史视图仍能打开原图。`:status-label`、`:remove-label`、`:size-label` 等是 `card` 的界面标签，不得进入 `snapshot`、草稿消息或模型请求；`:preferred-route` 是草稿选择，服务端解析成 `:send-route` 后丢弃。

组件 Props：

```clojure
{:cards (attachments/cards queue)
 :summary (attachments/send-summary queue)
 :on-remove!  (fn [{:keys [attachment-id card]}])
 :on-retry!   (fn [...])
 :on-exclude! (fn [...])
 :on-preview! (fn [...])
 :on-extract! (fn [...])
 :on-route!   (fn [{:keys [attachment-id preferred-route]}])}
```

React 挂载与 conversation-ui 一致：直接作为函数调用，或 `react/createElement` 时把 CLJS map 放在 `#js {:props props}`。组件不生成命令 ID，不上传，不写领域终态。

## 5 显示合同

- 卡片固定显示名称、类型、大小、状态、实际路线与可理解详情。
- `:ready` 显示“可发送”；`:adding` 显示“准备中”；`:needs-choice` 显示“需要选择处理方式”；`:unsupported`/`:failed` 显示“不支持”/“准备失败”。
- 图片有预览 URL 时显示缩略图；预览失败不能把附件标成损坏。
- 失败项可重试或移除；其他项继续保留。排除项仍显示在队列中。
- 提取成功的卡片可显式切换“发送原文件 / 发送提取文本”；`:preferred-route` 随快照提交，服务端按现有 native/text 能力重新校验。
- 部分可读 PDF（已有文本但含 `:no-text`/`:error` 页）仍显示“提取文字”，可输入页码只 OCR 选中页；页数限制按 `:selected-pages` 计算。
- `client-id` 与队列存储键绑定；Workbench 若已有稳定窗口身份应显式传入，否则使用 sessionStorage 的标签页身份。
- 按钮最小 44×44；图标按钮有 `aria-label`；状态文本不只靠颜色。

## 6 测试与验证

```bash
clojure -M:test -n hc.client.conversation.attachments-test
clojure -M:test -n hc.client.conversation.attachments-storage-test
```

覆盖：20/21 边界、100 MiB 边界、重复提示、真实描述符门槛、失败/排除/移除、模型未知与不支持、发送摘要总数、快照只含真实文本、与服务端兼容路线逐例一致、随机加入不超 20；队列 EDN 往返不保存 File/预览、损坏存储不当空成功、上传中断恢复为显式失败、内存存储跨会话切换。

新增覆盖：标签页 `client-id` 隔离同一会话的两个队列；恢复时非 canonical blob 不得 ready；存储 limits 不得超过产品上限；`add-file` 跳过已存在 `att-N`；旧上传回调不得覆盖 ready/排除/路线；部分可读 PDF 仍可 OCR；`:preferred-route :text` 在 native 可用时选择文本路线；失败卡在真实提取成功后恢复 ready。

CLJS 实际渲染 smoke（2026-11-03）：编译 `hc.client.conversation.attachments.view` 通过；`react-dom/server` 渲染 3 张卡片（可发送文本、未知模型图片、失败 PDF），HTML 3341 字符，确认名称、状态、批量提示、移除 `aria-label`、`hc-attachment-card` 均出现。未运行：真实浏览器文件选择器、拖放、触屏与读屏。

## 7 非目标

- 不实现上传通道；字节走 `hc.server.attachments.http`。
- 不实现 OCR/PDF/音视频提取；只消费真实 `:extraction`。
- 不拥有消息历史附件渲染；会话面继续负责已发送消息。
