# `hc.client.conversation` — 会话显示与输入面

> 权威顺序：根 `AGENTS.md` §7.3–§7.4、§8.5 > `src/AGENTS.md` > `src/hc/client/AGENTS.md` > 本文件。
> 本模块只实现浏览器端会话面；不新增服务端协议义务。

## 1 边界

`src/hc/client/conversation` 拥有：

1. 每会话未提交草稿（文本、光标、选区、附件、引用、版本、保存状态）。
2. 输入法安全的 Enter / Shift+Enter / 手机回车策略。
3. 发送门槛、重复提交保护、生成中“停止当前轮并发送 / 继续编辑”决策。
4. 流式跟随、用户上滚脱离、选区保护、返回最新内容。
5. 停止语义：点击即冻结可见正文；迟到文本不追加；迟到工具结果保留并标注。
   只要选中了会话，停止控件就保持挂载；本地流空闲不是服务端无工作的证据
   （无人值守 Nudge 可能在可见流之外继续运行），是否可停由 Owner 判定。
6. `S1` 提问卡：问题、上下文、选项、回答草稿、提交后定格、再次修改形成新记录。
7. 可访问响应式视图模型：`:compact` / `:medium` / `:wide`、焦点返回、Escape 层级、44/32 逻辑像素热区、live region。
8. 时间线投影：消息顺序、过程区、工具“参数完整 ≠ 执行结束”、未闭合代码/表格/公式回退。
9. 历史消息内联编辑的文本与附件草稿：保存为显式新版本载荷，移除附件不碰原版本、原始文件或 composer 草稿。

不拥有：服务器消息、会话、看板实体；Sente 通道；Fulcro 根容器与路由；`command-id` 的最终去重权威；模型/工具执行。服务器仍是当前态 SSOT；客户端不伪造领域终态。

## 2 命名空间

| 命名空间 | 职责 | 纯 CLJC |
|---|---|---|
| `hc.client.conversation` | 总 reducer、命令 outbox、公开 facade | 是 |
| `hc.client.conversation.draft` | 草稿版本、提交版本保护、保存状态、拒绝恢复 | 是 |
| `hc.client.conversation.composer` | IME/Enter、平台默认、发送门槛、停止决策 | 是 |
| `hc.client.conversation.stream` | 跟随/选区/冻结/迟到分类/停止哨兵/滚动请求计数 | 是 |
| `hc.client.conversation.ask` | S1 提问卡与回答记录 | 是 |
| `hc.client.conversation.a11y` | 响应式分档、覆盖层栈、焦点返回、Escape 层级 | 是 |
| `hc.client.conversation.timeline` | 消息顺序、过程区、工具终态、版本工具记录/用量、文本分段 | 是 |
| `hc.client.conversation.history-edit` | 历史消息编辑的文本/附件草稿、显式版本附件载荷 | 是 |
| `hc.client.conversation.export` | 当前版本 Markdown/纯文本、可选来源、附件说明与凭据遮蔽 | 是 |
| `hc.client.conversation.export-plan` | 导出选择（单条/多选/分支、默认当前版本）、取消状态机、长图分段与预览尺寸；不碰 DOM | 是 |
| `hc.client.conversation.minimap` | 话题概览、类型密度、稳定锚点、nearest/locate；不碰 DOM | 是 |
| `hc.client.conversation.surface` | 纯视图模型（标签、禁用原因、修复路径、live region） | 是 |
| `hc.client.conversation.view` | 薄 React 面；不构造命令 ID、不写领域终态 | 否（`.cljs`） |
| `hc.client.conversation.attachments.*` | 附件队列与卡片；独立子包，见 `attachments/AGENTS.md` | 是/否 |

`view` 只调用 `surface`、`composer`、`ask`、`history-edit`、`export` 的纯函数，并把操作者手势交给 workbench 的 callbacks。它不使用 Fulcro 事务，也不 require JVM 命名空间。

附件队列是独立子包 `hc.client.conversation.attachments`，拥有“加入草稿前”的队列、限额、模型兼容与卡片；本包草稿只消费普通 `:attachments` 项。Workbench 应在输入区上方挂载 `attachments.view/attachment-queue`，发送前把 `(attachments/snapshot queue)` 合并进草稿 `:attachments`；`draft/sendable?` 与 `draft/snapshot` 继续过滤 `:excluded?`。详见 `src/hc/client/conversation/attachments/AGENTS.md`。

## 3 与 workbench 的接口

### 3.1 挂载

Workbench 的 `hc.client.workbench.views.session/session-page` 负责路由与外壳。两种安全挂载方式：

```clojure
;; 方式 A：在 workbench 组件 render 内直接调用（推荐，保留 namespaced keys）
(conversation/conversation-page {:session session
                                 :messages messages
                                 :draft draft
                                 :stream stream
                                 :connection connection
                                 :layout layout
                                 :platform platform
                                 :callbacks callbacks})

;; 方式 B：作为 React 元素挂载；必须把 CLJS map 放在单个 :props 属性中。
(react/createElement conversation/conversation-page #js {:props props-map})
```

不要用 `clj->js` 转换整个 props map：ClojureScript 的 `clj->js` 会把 `:message/by-id` 变成 `"by-id"`，丢失 namespace。`conversation-page` 会从 `#js {:props ...}` 中取回原 CLJS map。

### 3.2 Props

```clojure
{:session      {:id "s1" :title "..." :model {...} :assistant {...}
                :workspace {...} :temporary? false :status :running}
 :messages     {:message/by-id {...} :message/order [...]} ; 或已排序 vector
 :draft        draft/empty-draft shape
 :stream       stream/initial shape
 :connection   {:phase :synced|:connecting|:connected-awaiting-sync
                |:interrupted|:disconnected}
 :layout       :compact|:medium|:wide
 :platform     :desktop|:mobile
 :send-key     :enter|:mod-enter
 :ask          ask map（可选，单张）或 {ask-id ask}
 :command      command map（可选；单个 command 或按 command-id 键的 map 都接受）
 :callbacks    {...}}
```

`draft`、`stream`、`ask` 也可以由完整 `:conversation` reducer state 提供；`surface/surface` 同时接受拆分 props 与完整 state。

### 3.3 Callbacks

Workbench 拥有命令构造与去重；view 不生成 `command-id`。

```clojure
{:send!              (fn [{:keys [session-id snapshot]}])
 :stop!              (fn [{:keys [session-id]}])
 :edit-draft!        (fn [{:keys [session-id text cursor selection composing?]}])
 :insert-transcript! (fn [{:keys [session-id text insert-at]}]) ; 可选
 :replace-transcript! (fn [{:keys [session-id start end expected-text text]}]) ; 可选
 :submit-ask!        (fn [{:keys [session-id ask-id answer text selected]}])
 :skip-ask!          (fn [{:keys [session-id ask-id]}])       ; 可选
 :submit-generated-card! (fn [{:keys [card values]}]); 可选
 :scroll!            (fn [{:keys [session-id at-bottom?]}])
 :resync!            (fn [_])
 :fix!               (fn [{:keys [session-id intent]}])       ; 可选
 :cancel-pending-send! (fn [{:keys [session-id]}])            ; 可选
 :restore-rejected!  (fn [{:keys [session-id]}])              ; 可选
 :regenerate!        (fn [{:keys [session-id message-id]}])   ; 可选
 :branch!            (fn [{:keys [session-id message-id]}])   ; 可选
 :edit-message!      (fn [{:keys [session-id message-id]}])   ; 可选（legacy prompt）
 :history/edit-message!       (fn [{:keys [session-id message-id content attachments citations]}]) ; 可选
 :history/select-version!     (fn [{:keys [session-id message-id version-id]}]) ; 可选
 :history/regenerate!         (fn [{:keys [session-id message-id]}]) ; 可选
 :history/branch!             (fn [{:keys [session-id before-message-id]}]) ; 可选
 :history/set-boundary!       (fn [{:keys [session-id message-id]}]) ; 可选
 :history/clear-boundary!     (fn [{:keys [session-id]}]) ; 可选
 :history/prepare-compression! (fn [{:keys [session-id message-ids summary]}]) ; 可选
 :history/apply-compression!   (fn [{:keys [session-id compression-id summary]}]) ; 可选
 :history/undo-compression!    (fn [{:keys [session-id compression-id]}]) ; 可选
 :history/pin-attachment!      (fn [{:keys [session-id attachment-id]}]) ; 可选
 :history/unpin-attachment!    (fn [{:keys [session-id attachment-id]}]) ; 可选
 :copy!              (fn [text])                              ; 可选
 :remove-attachment! (fn [{:keys [session-id attachment-id]}]) ; 可选
 :remove-quote!      (fn [{:keys [session-id quote-id]}])      ; 可选
 :settings!          (fn [_])                                 ; 可选
 :aux!               (fn [_])                                 ; 可选
}
```

缺失 callback 的控件必须可见地禁用并说明原因；view 已这样做。`Escape` 由 workbench 全局处理，view 不注册 Escape，避免双停。

### 3.4 Fulcro 键

Workbench 保证：

- `:ui/selected-session-id`
- `:ui/draft`（按 session id 的 `draft/empty-draft` 形状）
- `:ui/stream`（按 session id 的 `stream/initial` 形状）
- `:ui/command`（按 `command-id`）
- `:ui/connection`
- `:session/by-id`、`:message/by-id`、`:message/order`。服务器快照可使用普通键或 `:message/*` / `:session/*` 命名空间键；workbench 的流式事件创建的是 `:message/id`、`:message/role`、`:message/content` 等命名空间键，`timeline` 两种都读。

本模块 reducer 的完整 state 建议放在 `:ui/conversation`。若 workbench 希望使用 §7.3 的平面键，可在每次 reducer 后镜像：

```clojure
{:ui/draft     (:drafts state)
 :ui/stream    (:streams state)
 :ui/command   (:commands state)
 :ui/conversation state}
```

### 3.5 生成的 HTML 预览

`view.cljs` 把生成内容交给独立模块 `hc.client.preview.view/generated-html-preview`，不在会话面内复制 iframe 或 CSP：

- `text/html` 附件：传 `{:url (:url attachment) :source-label (:name attachment)}`。
- 闭合或流式 ```html 代码段：传 `{:html (:content segment) :source-label "生成的 HTML" :streaming? (not (:closed? segment))}`。
- 预览组件自包含，使用 `hc.client.sandbox` 的 `allow-scripts` iframe、CSP 与 token 消息闸门；外链、下载、表单都需用户确认。
- 预览失败只影响该卡片；原文仍可查看/复制。修复必须由用户发起新的明确轮次，组件不自动扩权。

## 4 Reducer 与命令

```clojure
(require '[hc.client.conversation :as conversation])

(def state conversation/initial-state)
(def next-state (conversation/apply-action state
                                           {:type :composer/send
                                            :session-id "s1"
                                            :at "2026-11-03T10:00:00Z"}))
(def {:keys [commands state]} (conversation/take-outbox next-state))
```

`apply-action` 接受的主要动作：

- `:session/select`、`:session/context`、`:connection/set`、`:platform/set`、`:settings/send-key`、`:viewport/resize`
- `:draft/edit`、`:draft/cursor`、`:draft/composition`、`:draft/save-state`、`:draft/attachments`、`:draft/add-attachment`、`:draft/remove-attachment`、`:draft/quotes`、`:draft/add-quote`、`:draft/remove-quote`、`:draft/clear`、`:draft/undo-clear`、`:draft/restore`、`:draft/restore-rejected`
- `:composer/keydown`、`:composer/send`、`:composer/stop`、`:composer/stop-and-send`、`:composer/continue-editing`、`:composer/cancel-pending-send`、`:composer/ack`
- `:stream/begin`、`:stream/scroll`、`:stream/content-grew`、`:stream/selection`、`:stream/chunk`、`:stream/tool-result`、`:stream/jump`、`:stream/stop-confirmed`、`:stream/finished`、`:stream/interrupted`
- `:ask/show`、`:ask/edit`、`:ask/submit`、`:ask/skip`、`:ask/hide`
- `:a11y/escape`、`:a11y/open-overlay`、`:a11y/close-overlay`、`:a11y/close-overlay-id`、`:a11y/focus`

命令信封：

```clojure
{:command-id "cmd-<uuid>"     ; 全 epoch 唯一，跨页面重载与多客户端不重复
 :sequence 1                 ; 客户端本地顺序，不进入服务器语义
 :command-epoch 1
 :status :pending            ; :pending :accepted :rejected（仅客户端跟踪）
 :kind :send|:stop|:unpark
 :session-id "s1"
 :payload {...}
 :at "..."}
```

- `:send` payload：`{:text :attachments :quotes :draft-version :cursor :selection}`
- `:stop` payload：`{:reason :operator}`
- `:unpark` payload：`{:ask-id :name :value}`

App 网关可把 `:send|:stop|:unpark` 归一化为 `:send-message|:stop-run|:answer-ask`；本模块不依赖该归一化名称。回执用 `{:type :composer/ack :command-id ... :status :accepted|:rejected}` 送回 reducer。

### 4.1 草稿持久化

草稿持久化由 `hc.client.workbench.storage` 拥有（JSON，键 `hc.workbench.v1`）；
本包只提供 `draft/restore` 与 `draft/restore-all` 的恢复语义，不拥有存储适配。
编解码、损坏检测、配额与安全错误由 `workbench.storage` 负责。

- 空会话不落盘；只持久化可序列化字段（`:text`、`:cursor`、`:selection`、
  `:attachments`、`:quotes`、`:version` 等）。
- 解码走 `draft/restore`：清空 `:composing?` 与 `:undo`，钳制光标/选区，版本落后则标 `:unsaved`。
- `draft/restore-all` 接受字符串会话 ID，也接受 JSON 持久化经
  `js->clj :keywordize-keys true` 得到的 keyword 键，并归一化为字符串。
  运行时按字符串寻址会话；把 keyword 键当作非法而丢弃，会让整库草稿静默消失，
  而本地存储里的 blob 看起来仍然完好。
- 配额或安全错误返回 `:quota-exceeded`/`:storage-unavailable`；调用方应派发 `:draft/save-state :failed` 或 `:storage-unavailable`，持续显示“草稿暂未保存”。
- 成功保存后派发 `:draft/save-state :saved`；正常保存不弹提示。
- 服务器 `:drafts` 是按 client-id 的 Owner 状态，不是跨浏览器 profile 的备份。同一 profile 的 reload/重启恢复，新 profile 从空库开始，见 `src/hc/client/workbench/AGENTS.md` §1.1。

### 4.2 关键状态语义

- `:stream/chunk` 的 `:last-outcome :result` 是 `:appended`、`:late-text` 或 `:stale`。只有 `:appended` 可以追加到可见消息；停止后到达的文本只增加 `:late-text-count`。序列号初始为 nil；同号或回退的重复投递都是 `:stale`，不得再次拼接。
- `:stream/tool-result` 的 `:last-outcome :result` 是 `:apply` 或 `:late-tool-result`。迟到结果进入 `:late-tool-results`，带 `:message-id`（缺省取当前活动消息），标签按 `:completed-before-stop?` 选择“停止前已执行，结果后来返回”或“停止时仍未完成，结果后来返回”。新 run 的 `start-run` 保留这些记录，直到对应消息被快照替换。
- 点击停止立即把 `:stop-state` 置 `:stopping` 并冻结可见正文；服务端接受停止回执后为 `:stopped`。同一 run 的迟到 `RUN_STARTED` 不清除 `:stopping`/`:stopped`；只有不同 `:run-id` 才开启新流。停止时若尚未收到 run id，`stream` 记录“未知 run”哨兵，首个迟到 `RUN_STARTED` 被采纳为被停的 run 且仍保持冻结。停止被拒时恢复停止前的 `:streaming?` 并清除 `:stop-run-id`，空闲会话不会永久停在 `:generating`。
- 停止按钮按选中会话常驻，不随 `:streaming?`/Nudge 回合的瞬时状态卸载；停止中只禁用，不隐藏。Workbench 的停止命令只校验连接、会话与 per-session `:command-epoch`，不把本地流空闲当作不可停止的依据。
- `:composer/stop-and-send` 先发 `:stop`，在 `:stream/stop-confirmed` 后才发第二条 `:send`；用户取消则不发。第二条 send 使用决策时捕获的 snapshot，而不是停止回执后的实时草稿；发送只清除版本仍等于捕获版本的草稿，停止期间的新输入保留。
- 草稿只在提交版本仍等于当前版本时清空；发送期间新输入保留。文本、附件与引用的增删都会递增 `:version`，因此发送后新加的附件/引用同样受提交版本保护。发送被拒时，空白草稿恢复提交快照；已有新文本则保留新文本并把快照放入 `:rejected-submission`，由 `:draft/restore-rejected` 显式恢复。
- 未绑定模型是硬门槛：`:model` 缺失、空 map、或只有能力/限额字段的 map 都按未绑定处理。
  发送按钮禁用并显示“先选择一个可用模型，草稿已保留”；header 的模型控件是修复入口。
  header 与发送门槛必须使用同一个模型解析结果，`:model-id`/`:model-label` 不能与空 map 互相矛盾。
- 已绑定模型也可能因连接或模型被停用/移除而不可用。服务端在会话摘要发布
  `:model-availability`，客户端把它投影进 `:model :availability`。当可用性不在
  `:available`/`:not-returned`/`:unconfirmed` 时，发送门槛必须在“输入为空”之前
  给出具体原因（连接已停用、连接已移除、认证失效、模型已停用/移除），并保留草稿。
  会话横幅同时提供“选择其他模型”入口，header 模型标签追加具体状态
  （如“（连接已停用）”“（连接已移除）”）。
  不得把不可用绑定当成普通未绑定模型，也不得只显示“输入为空”。
  真实摘要形状是 `{:model-binding {...} :model {...} :model-availability :connection-removed}`：
  `:model-binding` 是创建时的不可变快照，`:model-availability` 是服务端对当前
  配置的实时事实。客户端不得自行把“连接不在本地设置表”推断为不可用，也不得把
  `:unconfirmed` 当停用；连接仍配置但未测试时它保持可用，输入为空时提示“输入为空”
  是正确行为。启动配置移除连接必须先让服务端的 settings 投影给出
  `:connection-removed`，本模块只消费该事实。消费者回归见
  `test/hc/client/workbench/model_test.clj`
  的 `session-with-model-carries-live-availability`。
- `:draft/save-state :failed|:storage-unavailable` 持续显示“草稿暂未保存”，提供复制与另存；正常未保存不弹“已保存”。

## 5 显示合同

- 时间线顺序优先 `:message/order`；不完整/重复顺序回退到稳定时间/id 排序，不丢消息。
- 过程区只显示服务器提供的摘要；没有推理摘要就不生成模拟思考文字。
- 工具记录同时接受服务器快照的 `:tool-records` 与实时事件的 `:tool-calls`；选中版本自带 `:tool-records`/`:usage` 时优先使用版本条目，切换版本会同步切换工具记录与用量。
- 工具参数完整但领域状态非终态时显示“参数已完整，等待执行结果”；只有 `:succeeded/:failed/:cancelled/:outcome-unknown` 等终态才关闭等待。等待状态只显示一次，不重复同一句。
- 停止的消息保留正文、附件、工具记录；迟到工具结果带 `:message-id`，只挂到对应消息，标签按是否在停止前完成区分，不自动续答。
- 代码块带语言标识，复制只复制代码内容；未闭合代码围栏、表格、`$$` 公式使用可复制的纯文本回退，不反复重置选区。
- 宽表格独立横向滚动；长行可横向滚动；正文 `overflow-wrap:anywhere`。表格单元格内的 `\|` 是字面竖线，不拆列。
- 无消息时显示“还没有消息”，不生成演示回答。
- 服务端仍报告活动 run（`:current-run-id`/`:run-status` 或 `:unattended?`/`:nudge-*`）而可见流空闲时，
  显示“无人值守 · 自动继续”横幅与 live-region 文本；不把本轮误标为正常完成。停止按钮保持挂载，
  标签为“停止无人值守运行”，发送按钮禁用并说明可先停止当前轮。只过滤 nudge 消息，不隐藏真实活动。

### 5.1 历史编辑、版本、压缩与导出

- 用户或助手消息的“编辑”打开独立 per-message 草稿，绝不覆盖底部 composer 草稿；保存调用
  `:history/edit-message!`，在对应 tracked `:ui/command` 达到 `:accepted` 前编辑器保持打开；
  被拒时保留编辑文本与附件草稿并显示错误，不自动发送。
  助手回复的保存由服务端标记 `:manual-edit?`，界面显示“手动编辑”，不冒充模型原始输出。
- S1 卡片把回答记录写成可见标签：选项回答保存 `:label`（选项显示名，不是内部 value），
  文本+选项与多选同样保留可读组合；“跳过”追加 `{:skipped? true}` 记录、显示“跳过”，
  通过 `:unpark` 恢复挂起程序并把卡片定格为 `:skipped`，不是隐藏式关闭。
  视图以 `:text`/`:selected`/`:draft-version` 初始化，只在草稿版本变化时同步，不覆盖操作者输入。
- 内联编辑器列出当前版本的附件，每个附件有“移除附件”控件；保存时总是显式携带
  `:attachments`（全部移除时为空向量），因此服务端追加的新版本不会继承已移除的引用。
  移除只改变新版本，原版本、其他消息、composer 草稿与原始文件都不受影响。
- `:versions` 多于一条时显示版本按钮；点击调用 `:history/select-version!`，不发送模型请求。
  版本按钮以消息的稳定 `:id` 为身份；切换版本会改变正文，浏览器验收必须用
  `.hc-message[data-message-id="…"]` 定位消息，不能再用正文 `hasText` 重新解析，
  否则切到旧版本后定位器会先失效并误报“附件为空”。
- “重新生成”调用 `:history/regenerate!`，命令 pending 时禁用并显示“重新生成中”；不生成客户端假内容。
- 历史消息的每个附件有“固定/取消固定”控件，调用 `:history/pin-attachment!` / `:history/unpin-attachment!`；
  上下文面板显示固定数量、缺失固定附件与超限警告，超限不自动取消固定。
- 上下文面板显示 `:context` 的边界、最近轮次、阅读/发送对照、固定附件、压缩、`outbound` 裁剪
  （Pair Hint/Scope）与可读警告；压缩准备器允许勾选
  `outbound :message-ids` 范围并编辑摘要，只有“准备压缩”调用
  `:history/prepare-compression!`。草稿压缩行可编辑摘要后“使用摘要”；已应用行只提供撤销。
- 每条消息有“选择”复选框；选中后显示“已选 N 条消息”“导出选中”“清除选择”，可导出为本地
  Markdown 或纯文本，不请求模型；原有“导出当前分支”仍导出全部可见消息。
- 导出提供“含来源”开关；默认不含来源。开启时只写标题、URL、站点与发布日期，不写抓取正文。
- 导出范围只来自调用者选定的路径：当前分支导出传该分支全部可见消息，多选导出只传勾选消息；
  两者都不重排、不合并其他会话。已提交消息取 `:current-version` 的正文与附件；正在流式生成或已停止
  的消息取当前可见正文（版本快照可能落后于已到达的文本）；被替换的旧版本、
  工具记录、过程摘要、隐藏指令、blob ID、下载 URL、工作区路径和任意映射都不进入文件。
  附件只写成名称、媒体类型、大小与来源类别，足以让接收者知道缺了什么，不暴露本地内容地址。
- 正文与附件名在导出前经过已知凭据遮蔽：私钥块、Authorization/Basic/Bearer、密钥/密码/令牌赋值、
  URL 用户信息与常见供应商令牌前缀都替换为 `[已隐藏]`。这是对已知模式的遮蔽，不是对任意正文的
  隐私保证；操作者仍需检查自己选择导出的内容。
- `view` 使用真实浏览器 Blob 链接与 `download` 属性完成下载，不伪造文件卡、不声称已写入磁盘。
  Framework7 的 clicks 模块在 document 捕获阶段对所有 `a[href]` 调用 `preventDefault`，会取消程序化下载；
  导出锚点在 `window` 捕获阶段只对该次点击停止传播，默认下载动作仍由浏览器执行，其他链接不受影响。
  对象 URL 与空锚点保留 1 秒后再撤销，避免 Chrome 尚未解析 blob URL 就丢失下载事件；浏览器拒绝创建下载时显示
  “浏览器未能开始下载；导出内容仍在，可重试。”，选择与导出范围保持不变。
- 没有对应 callback 的历史控件不渲染或禁用；不伪造成功。

### 5.2 消息翻译选区

- `surface/translation-context` 从 composer 取选区：优先 `:selection`，否则 `:cursor` 作为零宽选区；夹紧到正文长度，返回 `{:draft-selection {:start :end :text :draft-version} :draft-text}`。
- `message-item` 把 `:selection`、`:draftText` 与 `:onReplace` 传给 `speech-ui/translation-controls`；`onReplace` 载荷为 `{:start :end :expected-text :text}`。
- `surface/translation-replace-action` 只在起止与选中文字仍匹配且存在替换能力（`:replace-transcript!` 或 `:edit-draft!`）时返回 `:replace`；否则回退 `:insert`（当前光标）或 `:copy`。绝不覆盖已变化的范围。
- 有 `:replace-transcript!` 时优先调用它；没有该回调但存在 `:edit-draft!` 时，视图用现有草稿编辑做等价的范围替换（仅在起止与选中文字匹配后计算新正文、光标与选区）。两者都没有时不向面板提供选区/替换入口，只保留复制/插入；不伪造替换按钮。
- `translation-controls` 的“替换当前选区”按钮与 `replace-plan` 判定属于 speech 子包；本包只提供选区快照、回退决策与 workbench 回调。

### 5.3 导出计划与话题概览

- **导出选择**：`export-plan/select-messages` 接受 `:single`/`:multi`/`:branch`；
  默认每个消息的 `:selected-version`，只有 `:include-all-versions? true` 才展开
  全部版本。未选中的工具日志、隐藏指令和 secret 消息排除；正文经现有
  `export/redact-text` 遮蔽。重复消息 id 返回 `:DUPLICATE_MESSAGE_ID`，不静默
  产生重复段。
- **取消状态机**：`:idle → :preparing → :writing → :done|:cancelled|:failed`。
  取消只在 `:preparing` 立即生效；`:writing` 返回“已请求取消，等待当前写入边界”，
  由 `complete` 在写入边界落到 `:cancelled`。失败保留 `:selection`/`:preview`。
  取消文案来自 `hc.client.copy.failures` 的 `:export-cancelled`，不得出现
  “已保存/已保留”。
- **长图**：`segment-plan` 顺序填满当前段，只有 `(+ height h) > :max-height`
  才开新段；每段至少一条消息；单条超高返回 `:SINGLE_MESSAGE_TOO_TALL`；空选择
  返回 `:EMPTY_SELECTION`。`preview-size` 只估算 `:width/:height/:pixels/:bytes`，
  不声称已渲染。
- **话题概览**：`minimap/overview` 返回 `:items`（`:id :label :kind :anchor`）、
  `:density`、`:counts`、`:id->anchor`。`:limit` 只截断显示，所有 id 仍可
  `locate`；追加在目标之后的新消息不改变同一 id 的锚点。重复 id 保留第一条并
  在 `:duplicate-ids` 报告。
- **接线**：`conversation.view` 只在工具栏/侧栏调用纯函数；不把 `:anchor` 当像素，
  滚动位置由视图根据消息 id 重新测量。测试见 `test/hc/client/conversation/export_test.clj`
  与 `minimap_test.clj`。
- **失败模式**：导出未选版本或工具日志、把 `:writing` 取消写成已取消、单条超高
  静默截断、重复 id 让锚点漂移。复检命令：
  `clojure -X:test:client :nses '[hc.client.conversation.export-test
  hc.client.conversation.minimap-test hc.client.copy.failures-test]'`。

## 6 可访问性与响应式

- 断点：`<600 :compact`、`<1024 :medium`、其余 `:wide`（对应 360/820/1280 基准视口）。
- `:compact`/`:medium` 的侧栏与辅助面板由外壳折叠为抽屉/覆盖层；本模块只提供 `:layout-map` 与类名，不另造导航。
- 会话头操作按钮带稳定 id `hc-header-action-<action-id>`；外壳用它作为
  “辅助面板”关闭后的焦点返回目标，不要在渲染时改写该 id。
- 主按钮热区至少 44×44 逻辑像素；桌面次要按钮至少 32×32。
- 文本对比度使用 Framework7 主题变量；错误/警告横幅有独立前景/背景，不只靠颜色。
- 图标控件都有可读 `aria-label`；无纯图标发送/停止按钮。
- 消息列表 `role="log"`；状态与错误使用独立 live region，不逐 token 播报。
- 输入区 textarea 非受控；种子值与恢复读取同一个 `surface/composer-view` 模型
  （`:value`/`:cursor`/`:selection`/`:composing?`），不直接读原始 `:draft` props。
  这样拆分 props 与完整 `:conversation` state 两种挂载方式行为一致。
- 外部草稿只在不会破坏操作者输入时写入 DOM：字段为空（刷新/恢复）、草稿被显式清空
  （发送已接受）、或字段未聚焦（切换会话）。字段已聚焦且已有文字时保留现场，
  不覆盖后续输入。组合态期间既不写值也不移动光标。
- 切换会话或恢复草稿后按该会话 `:cursor`/`:selection` 恢复光标与选区；普通渲染
  不在聚焦的输入框里移动光标，服务器推送不替换正在输入的内容。
- 组合结束与失焦时显式提交当前 textarea 值、光标与选区到该会话草稿，避免切换会话/点击发送时丢失
  尚未触发的最后一个组合字符。
- 弹层打开记录 `:trigger-id`；关闭后 `:focus` 指回触发点。Escape 顺序：最上层弹层 → IME 候选/文本选区 → 停止生成。
- 流式重渲染不得清空消息区的 DOM 选区。React 对增长中的同一文本节点用
  `nodeValue` 整体替换字符数据，Blink 按 character-data mutation 规则重置
  Range 偏移，选区会坍缩；surface 在消息滚动容器内记录非折叠选区的原 DOM
  点与绝对字符偏移，并在每次提交后的 layout effect 中恢复同一文本的 Range。
  恢复不得暂停接收、丢弃块或冻结渲染。主键 `pointerdown`/`mousedown`、Escape
  与无修饰导航键会主动放弃记录；输入控件获得焦点不主动放弃，以便在输入区写
  草稿时保留上方选区。文本已被编辑或切换版本时，恢复前比对选区文本，绝不把
  旧 Range 套到不同正文上。
- IME：`isComposing`/keyCode 229/`Process` 时 Enter 只确认候选，不发送、不截断；`preventDefault?` 为 false。
- 桌面默认 Enter 发送、Shift+Enter 换行；手机默认回车换行、独立发送按钮；发送方式可在设置中改为 Ctrl/Cmd+Enter。
- `:model-label` 只是显示文本，不是模型绑定身份；发送门只用 `:model`/`:model-id` 判断可用模型。
  无绑定时发送禁用并显示“先选择一个可用模型”，模型标签/设置入口仍可打开设置。

## 7 服务端快照期望

`surface`/`timeline` 读取：

- `:session/by-id`：`:id`/`:session/id`、`:title`、`:model`/`:model-id`/`:model-label`、`:assistant`、`:workspace`、`:temporary?`、`:status`。
- `:message/by-id`：`:id`/`:message/id`、`:role`/`:message/role`、`:author`、`:content|:text|:body`/`:message/content`、`:status`/`:message/status`、`:process`、`:tool-records`（服务器快照）或 `:tool-calls`/`:message/tool-calls`（实时事件）、`:usage`、`:attachments`/`:message/attachments`、`:sources`、`:versions`、`:current-version`、`:manual-edit?`、`:error`。
- `timeline/message-view` 在显示投影之外保留原始 `:attachment-refs`，供内联历史编辑
  发送无损的新版本附件向量；`:attachments` 仍是带 `:preview?` 等显示字段的副本。
  当前版本条目自带的 `:attachments`、`:tool-records`、`:usage`、`:manual-edit?` 优先于
  消息级镜像：版本切换后镜像可能落后，而空向量表示该版本确实没有附件，必须保留为空，
  不能回退到旧版本的附件。
- `:message/order`：完整消息 ID 顺序。
- S1 提问：`:ask-id`、`:name`、`:question`、`:context`、`:options [{:value :label}]`、`:multiple?`、`:input?`、`:created-at`；卡片草稿 `:draft {:text :selected :version}`；回答记录 `:value`、`:label`、`:skipped?`、`:status`。视图用 `:text`/`:selected`/`:draft-version` 初始化并在版本变化时同步。
- 模型可用性：会话摘要的 `:model-availability`（或 `:model :availability`）表示当前
  连接/模型事实；缺失表示调用方未提供，不得自行推断为不可用。
- 连接状态来自 `hc.client.sync` 的五阶段，不要求服务端快照携带。

## 8 测试与验证

```bash
clojure -M:test -n hc.client.conversation-test
clojure -M:test -n hc.client.conversation.export-test
clojure -M:test -n hc.client.conversation.minimap-test
clojure -M:test -n hc.client.conversation.attachments-test
clojure -M:test -n hc.client.conversation.stream-test

# CLJS 编译（需要 node_modules）
clojure -M:cljs:client -co '{:target :nodejs
                             :output-to "/tmp/hc-conv.js"
                             :output-dir "/tmp/hc-conv-out"
                             :node-modules-dirs ["node_modules"]
                             :optimizations :none}' \
  -c hc.client.conversation.view

# 真实 React DOM 消费者回归（jsdom，模拟 Blink 的字符数据重置）
./test/js/verify_conversation_selection_dom.sh

# 真实 Chromium 消费者回归（shadow-cljs 聚焦构建 + Playwright）
./test/js/verify_conversation_selection_browser.sh
```

`test/hc/client/conversation_test.clj`、`test/hc/client/conversation/stream_test.clj`
与 `test/hc/client/conversation/export_test.clj` 覆盖：

- 每会话草稿隔离、版本保护、只清已提交版本、拒绝恢复、保存失败文案。
- IME `isComposing`/229/`Process` 不发送；桌面/手机/`mod-enter`/Shift/重复键。
- 空文本、零宽字符、未就绪附件、缺模型、断线、生成中决策、重复命令。
- 空 map 模型（`:model {}`）与只含能力/限额的模型 map 均视为未绑定；header 的
  “尚未选择模型”与发送门槛必须同时成立。`:model-id`/`:model-label` 单独存在时
  header 与发送门槛使用同一个解析结果。
- 停止立即冻结、空闲流也可请求停止、停止按钮按选中会话常驻、迟到文本不追加、迟到工具结果标注、停止被拒恢复、停止后发送。
- 停止早于 run id 时的哨兵：迟到 `RUN_STARTED` 被采纳为被停 run 且仍冻结；空闲流停止被拒后恢复非 generating；同号/回退 seq 丢弃。
- stop-and-send 使用捕获 snapshot；停止期间的新草稿保留并记录提交版本；迟到工具结果带 message-id 且新 run 后仍保留。
- S1 提问卡提交、回执、已答后编辑形成新记录、空回答阻止。
- S1 回答记录保存选项显示名（`:label`），文本+选项与多选可读；跳过写入 `{:skipped? true}` 记录并定格为 `:skipped`；已回答/已跳过不可再次跳过；0→1 卡、已回答定格与跳过均有 DOM 回归。
- 跟随/上滚/返回/选区锁定、程序化滚动不脱离。
- 时间线顺序回退、工具等待与终态、停止消息、未闭合分段。
- 版本条目的 `:tool-records`/`:usage`/`:manual-edit?` 优先；助手消息可编辑；顺序接受 seq。
- 翻译选区快照夹紧到正文长度、折叠光标为零宽选区；起止/选中文字仍匹配才替换，否则回退插入或复制；`translation-controls` 收到 `:selection`/`:onReplace` 的 DOM 回归。
- 响应式分档、热区、Escape 层级、焦点返回。
- 视图模型的禁用原因、修复路径、草稿保存警告、提问卡、消息投影；已绑定但连接
  停用/移除的模型在空草稿下也先解释连接问题，并提供“选择其他模型”入口。
- 导出只含调用者选定路径与 `:current-version`；旧版本正文和附件不混入；附件说明不含 blob URL；
  工具记录/过程摘要不进入文件；已知凭据、Authorization、私钥块、URL 用户信息与供应商令牌被遮蔽。
- 导出纯文本与 `:include-sources?` 开关；来源只含标题、URL、站点与发布日期。
- `draft/restore`/`restore-all` 的恢复语义、keyword 会话键归一化、光标/选区钳制；存储编解码、损坏检测与配额由 `workbench.storage` 的测试覆盖。
- `test.check` 属性：草稿版本不倒退、IME Enter 不产生命令、停止后不追加文本、命令 ID 唯一。
- `selection_dom_test` 挂载真实 `conversation-page`，选中消息段落、追加流式正文、
  模拟 Blink 的 Range 坍缩并断言选区恢复；同时断言流仍在增长、草稿未被覆盖，
  且操作者主动 `pointerdown` 或正文已被替换时旧选区不会复活。
- `selection_browser_test` 用 shadow-cljs `:conversation-selection-test` 在真实
  Chromium 中重复同一消费者路径；`test/js/run_conversation_selection_browser.cjs`
  另外检查滚动位置与草稿。Playwright 是外部测试依赖，路径由 `PLAYWRIGHT_PATH`
  提供。该浏览器回归与 E2E03 重跑都是放行门槛；本次改动未在本机运行它们。

### 8.1 实际 UI 冒烟（2026-11-03 已运行）

在仓库根目录、使用 workbench 的 `node_modules` 和临时 `/tmp/hc-ui-smoke` harness：

1. CLJS 编译 `hc.client.conversation.view`：通过。
2. React `renderToString` 渲染完整面：输出 9147 字符；确认出现标题、发送/停止、草稿未保存、工具等待、迟到工具结果、S1 问题、`hc-message`、`hc-composer`、`aria-live`。同一渲染使用 workbench 形状的 `:message/id`、`:message/role`、`:message/content`、`:message/tool-calls`，确认命名空间键也能显示。
3. jsdom + React 18 交互：
   - 挂载 4 个按钮，composer `aria-label` 为“消息输入”，live region 为 `polite`。
   - 输入事件产生 `:edit-draft!`，文本/光标/选区正确。
   - IME Enter 不产生发送。
   - 生成中 Enter 显示“停止当前轮并发送 / 继续编辑”，不发送。
   - 停止按钮产生 `:stop!`。
   - 选择 S1 选项后提交产生 `:submit-ask!`，值为 `{:text "" :selected ["v0"]}`。
   - 消息区上滚产生 `:scroll! {:at-bottom? false}`。
   - 非生成状态点击发送产生 `:send!`，snapshot 为当前草稿。
4. 草稿持久化的 localStorage 冒烟已随 `workbench.storage` 迁移；本包只验证 `draft/restore` 语义。

**未运行/限制**：

- 未在真实浏览器（Chromium/WebKit/Firefox）中跑完整 E2E 01–22；§8.2 只覆盖
  草稿恢复、未绑定模型发送门槛与流式选区保持三项。真实 IME 组合事件、
  真实触屏热区、屏幕阅读器、真实 Framework7 `App` 上下文仍未验证。
- jsdom 冒烟不能证明视觉布局、对比度、安全区或滚动锚定；这些需要 workbench 的真实浏览器烟测。
- SSR 渲染会因 Fulcro DOM 的 `useLayoutEffect` 打印开发警告；本面以客户端挂载为准。
- 未接 Sente/AG-UI 网关；回执与命令去重由 workbench/app 负责。
- 未实现附件上传、OCR、语音、备份、设置页；这些属于其他工作区。

### 8.2 行为回归（2026-11-04 真实 Chromium）

真实 Chromium 驱动在隔离 workbench 中复现三项操作者可见缺陷；本节固定其行为契约，
后续改动必须保持。

1. **刷新后草稿必须回到输入框。** 已保存草稿在 `reload` 后必须显示原文，
   随后的输入在原文之后继续，不得只保留新字符并覆盖旧草稿。
   根因与修复：
   - workbench 的 JSON 存储用 `js->clj :keywordize-keys true` 读取，
     `:drafts` 的会话 ID 变成 keyword；`draft/restore-all` 只接受字符串键，
     整库草稿被静默丢弃，这是本次空输入框的直接原因。现在 keyword 键归一化为字符串。
   - textarea 的恢复副作用读取原始 `:draft` props，而不是种子值同源的
     `surface/composer-view` 模型；完整 `:conversation` 挂载方式下两者不一致。
     这是同一恢复路径上的潜在缺陷；现在恢复读取 composer 模型，并按 §6 的规则
     决定是否写入 DOM。
2. **未绑定模型必须同时关闭发送。** 会话 header 显示“尚未选择模型”时，
   发送按钮必须禁用并给出“先选择一个可用模型，草稿已保留”的修复路径；
   不得因 `:model {}` 是真值而放行。空 map 与只含能力/限额的 map 都按未绑定处理。
3. **流式重渲染不得清空消息区选区。** 真实 E2E03 在长回复继续流式到达时，
   读者选中的段落被清空。根因不是滚动锁定，也不是停止接收流：React 用
   `textNode.nodeValue = newText` 更新增长中的同一文本节点，Blink 的
   character-data mutation 规则会把 Range 偏移重置，`window.getSelection()`
   因此坍缩为空。修复由 `conversation-surface` 持有：记录消息滚动容器内非折叠
   选区的原 DOM 点与绝对字符偏移，每次 React 提交后在 `useLayoutEffect` 中
   检测选区是否被清空/改写，并用同一文本重建 Range。它不暂停接收、不丢弃块、
   不冻结渲染；`pointerdown`、Escape 和无修饰导航键主动放弃记录，正文被编辑或
   切换版本时不复活旧 Range。

验收断言（真实浏览器）：在流式回复中选中含“第一段”的文本后等待多个块到达，
`window.getSelection().toString()` 仍包含“第一段”；消息区 `innerText` 继续增长；
输入区草稿仍等于操作者写入的文本；`scrollTop` 不被拉回底部；停止按钮仍在
（流没有为了稳定选区而停止）。

验收断言（真实浏览器）：保存草稿后刷新，`textarea[aria-label="消息输入"]`
的值等于保存文本；未绑定会话中该 textarea 非空时，
`button[aria-label="发送消息"]` 的 `disabled` 为 true，且
`#hc-composer-hint` 包含“先选择一个可用模型，草稿已保留”。

## 9 非目标

- 不实现 Sente 通道、命令去重权威、服务器领域状态。
- 不实现历史版本/分支编辑器本身；只渲染其显示副本并提供可选操作入口。
- 不实现 Markdown 全解析器；只保证代码/表格/公式的稳定回退与复制。
- 不把大附件、日志或导出包塞进实时状态树。
