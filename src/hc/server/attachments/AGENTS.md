# `hc.server.attachments` — 附件摄取、校验与模型兼容

> 权威顺序：根 `AGENTS.md` §3.4、§6.7、§7.3、§7.4 旅程 6.6（多模态附件） > `src/AGENTS.md` > `src/hc/server/AGENTS.md` > 本文件。
> 本包只负责操作员附件的摄取与策略，不负责生成产物溯源（`hc.server.artifacts`）或文档 OCR/PDF 提取（`hc.server.documents`）。

## 1 责任

1. **真实本地副本**：文件字节进入 `hc.server.blob-store`，得到稳定 `sha256-...` 内容 ID。原始文件只读，绝不修改。
2. **产品限额**：单草稿最多 `20` 个附件（失败项也占位），单个本地导入文件最多 `100 MiB`。摄取时边读边计数，超限在提交前拒绝。
3. **媒体事实**：用魔数判定有效 MIME，不轻信文件名或客户端声明；图片读取真实宽高、帧数、EXIF 方向；文本严格 UTF-8 解码，非法字节拒绝而不是替换成 U+FFFD。
4. **图片发送副本**：默认长边 `2048`，保留透明通道；有 EXIF 方向时先旋转/翻转到与相机预览一致，再缩放。原图仍保留在 blob 区。动画图片明确标为首帧。
5. **模型兼容**：`hc.server.attachments.model/compatibility` 只把已声明能力当作支持；未知能力是 `:unknown`，不是“可以”。原生发送优先，已确认的真实提取文本可作为替代路线。
6. **本地副本 vs 工作区关联**：默认保存应用内副本；选择关联工作区文件时记录 `root-id`、相对路径与 `(mtime-sec, mtime-nsec, size)` stamp。发送前 stamp 变化必须让用户选择“使用最新内容”或“保留已预览版本”，不得静默替换。
7. **不伪造提取**：本包只做严格 UTF-8 文本预览。PDF、扫描图、音频、视频的文本/OCR 必须来自真实的 `:extractor`（`hc.server.documents` 适配）；失败或 `:OCR_UNAVAILABLE` 以 `:failed`/`:needs-service` 暴露，绝无假文本。`hc.server.documents` 要求显式 `:selection`：摄取时只对 text/PDF 传 `{:route :text}`；图片/音频/视频不自动 OCR，只返回 `:not-attempted :needs-operator-choice`，由操作员调用 `extract-attachment!` 并显式选择 `{:route :ocr}`。

## 2 命名空间

| 命名空间 | 职责 | I/O |
|---|---|---|
| `hc.server.attachments.model` | 限额、描述符校验、模型能力、发送准备 | 无 |
| `hc.server.attachments.media` | 魔数 MIME、图片检查/缩放、EXIF、严格文本、PDF/媒体事实 | 仅内存/流 |
| `hc.server.attachments.ingest` | blob 摄取、可选真实提取、工作区关联/快照、校验 | 是 |
| `hc.server.attachments.http` | `POST /attachments` 独立 HTTP 入口 | 是 |
| `hc.server.attachments` | 公共门面 | 否 |

## 3 附件描述符

```clojure
{:attachment-id "att-..."
 :id "att-..."
 :name "report.pdf"
 :mime "application/pdf"
 :declared-mime "application/pdf"
 :sniffed-mime "application/pdf"
 :mime-mismatch? false
 :size 12345
 :blob-id "sha256-..."
 :sha256 "..."
 :source :local-import            ; :clipboard :camera :workspace ...
 :local-copy? true
 :status :ready                   ; :adding :ready :needs-choice :unsupported :failed
 :ready? true
 :excluded? false
 :media {:kind :pdf :readable? true :encrypted? :unknown :page-count nil}
 :prepared {:blob-id "sha256-..." :mime "image/png" :size 1000
            :width 2048 :height 683 :resized? true :orientation 1}
 :prepared-status {:status :prepared}
 :extraction {:status :ready|:failed|:needs-service|:not-attempted
              :text "..." :page-count 1 :pages [...]
              :selected-pages [2 3] :route :text|:ocr
              :suggested-route :ocr|:text
              :source {:kind :local-utf8|...} :warnings [...]}
 :preferred-route :native|:text   ; optional explicit operator route
 :workspace {:root-id "..." :root-path "/abs/path"
             :relative-path "a/b.txt"
             :stamp {:mtime-sec 1 :mtime-nsec 2 :size 3}}
 :created-at 1700000000000
 :limits {:max-items 20 :max-bytes 104857600 :max-pages nil}}
```

`descriptor-issues` 是结构诚实性检查：本地副本必须有 canonical blob ID；工作区关联必须有合法 stamp；`status` 必须是支持的卡片状态；`:preferred-route` 只能是 `:native`/`:text`；已 ready 的提取若带 `:source :id`，必须与附件 blob ID 一致，防止把另一份材料的提取文本挂到当前卡片。

`verify!` 对本地副本先比较 blob 大小，再重新读取字节计算 SHA-256，与 `:sha256`（缺失时从 canonical blob ID 推导）比对；同尺寸替换也会得到 `:ATTACHMENT_INTEGRITY`。工作区关联仍用记录 stamp 校验。

## 4 模型兼容

`compatibility` 返回：

```clojure
{:route :native|:text|:needs-choice|:unsupported|:unknown
 :send-route :native|:text|nil
 :kind :image|:pdf|:text|:audio|:video|:archive|:file
 :required-capability :vision|:document|:audio|:video|:text
 :capabilities-known? true|false
 :native? true|false
 :text-capable? true|false
 :preferred-route :native|:text|nil
 :extractable? true|false
 :extraction-ready? true|false
 :reason :model-supports-native|:local-text|:extracted-text|:preferred-text
         |:extract-text|:capability-unknown|:no-compatible-route|:service-limit
         |:workspace-reference-unverified|:local-copy-missing
 :detail "面向操作员的中文说明"
 :repairs [:select-model :extract-text :snapshot :retry :remove]
 :limits {...}}
```

`:preferred-route :text` 在模型同时接受 native 时优先选择已确认的提取文本；它不新增能力，只在现有 native/text 路线间切换。提取失败或不可用时返回 `:needs-choice`/`:unsupported`，绝不静默改发原文件。服务端在 `validate-draft` 中再次校验。页数限制使用 `:extraction :selected-pages` 的数量；没有该字段时才回退整份 `:page-count`。

能力集合来自模型/连接的 `:capabilities`（或 `:input-types`）。常用别名：

- 图片：`:vision`、`:image`、`:image-input`、`:multimodal`、`:visual`
- 文档/PDF/文件：`:document`、`:pdf`、`:file`、`:attachment`
- 音频：`:audio`、`:speech`、`:transcription`
- 视频：`:video`
- 文本：`:text`

缺失声明是 `:unknown`，显式空集合是“已知没有能力”。服务可用 `:attachment-limits {:max-items :max-bytes :max-pages}` 设置更低限制；产品上限不可被放宽。

`validate-draft` 把结构问题、未就绪状态、兼容问题和模型限额合并为 `:issues`，并返回 `:sendable`、`:blocked`、`:excluded` 与 `:summary`。失败项仍计入总数；只有显式 `:excluded? true` 才能让其余项发送。被排除项自身的结构/限额问题不进入阻塞性 `:ok?` 判断，否则一个已排除的坏卡片仍会阻止其余附件。

`prepare-send` 只返回可发送项，并为原生图片选择 `:prepared` 副本（除非 `:send-original? true`），为文本路线带上已确认的 `:send-text`。

`prepare-send` 的 `:sendable` 是封闭的稳定描述符，不是 composer 卡片。它只保留 `sendable-attachment-keys` 中的字段：草稿选择 `:preferred-route` 已解析成 `:send-route`，卡片标签 `:status-label`/`:remove-label` 等只属于界面，不得写入消息或模型请求。`:url` 是 `/blobs/<id>` 的不可变显示链接，保留给历史视图；模型适配器把它当参考元数据接受，但字节仍只通过 loader 读取。

Owner/模型适配器直接消费 `prepare-send` 输出：文本路线带 `:send-text`，原生路线带 `:send-blob-id`/`:send-mime`；快照不复制字节。原生路线必须有本地副本；仅验证 stamp 的工作区关联必须先 `snapshot-workspace!`，否则返回 `:needs-choice`，不得生成 `:send-blob-id` 为 nil 的可发送项。模型适配器必须显式处理这些描述符；缺失 route、缺失 loader、排除项、空文本和未声明能力都必须失败，不能静默丢弃。适配器对附件 map 使用封闭 schema；任何草稿专用字段泄漏进来都会在 provider 请求前显式失败。Owner 对原生附件需要 `:attachment-contract {:resolve? true}`，由应用注入真实 blob loader。应用还应向 Owner 注入 `:attachment-policy`，在追加消息/开模型调用前用当前会话模型执行 `prepare-send`；拒绝返回 `:ATTACHMENT_NOT_SENDABLE`，保留草稿与状态。

## 5 HTTP 入口

```text
POST /attachments?name=<display-name>&draft-count=<n>
content-type: <declared media type>
content-length: <bytes>
x-attachment-name: <display-name>        ; 可选，替代 ?name
x-draft-count: <n>                       ; 可选，替代 ?draft-count

201 {:ok? true :attachment {...} :url "/blobs/sha256-..."}
400/409/413/503 {:ok? false :error :CODE :message "..." :details {...}}

200 {:ok? true :attachment {...} :extraction {...}}
400/409/413/415/422/503 {:ok? false :error :CODE ...}
```

字节只走这条独立 HTTP 通道，Sente 通道只传描述符与状态。应用应在静态 fallback 之前挂载 `hc.server.attachments.http/handle`，并与网关共享同一个 `BlobStorePort`。

宿主 HTTP listener 的 `:max-body` 必须高于产品上限；`hc.server.app.http` 默认只有 2 MiB，会在请求到达本 handler 之前拒绝 100 MiB 附件。应用装配应传 `:max-body (+ attachments/max-attachment-bytes (* 1024 1024))`（101 MiB），本 handler 仍独立执行精确的 100 MiB 检查。Sente 的 1 MiB 边界不受影响。

### 5.1 提取器端口

`service` 的 `:extractor` 可以是函数，也可以是 `{:extract! f}`。请求形状：

```clojure
{:source {:id blob-id :name "a.pdf" :mime "application/pdf" :size 123}
 :stream InputStream
 :selection {:route :text}                 ; 或 {:route :ocr :ocr {...}}
 :max-bytes 104857600
 :password "..."                           ; 仅显式传入时；不进入描述符
}
```

`hc.server.documents/extract!` 要求显式 `:selection`。摄取时 text/PDF 传 `{:route :text}`；图片/音频/视频不自动调用，只标记 `:not-attempted :needs-operator-choice` 与 `:suggested-route`。操作员选择 OCR 时调用 `extract-attachment!`（HTTP `POST /attachments/extract`）。成功保留 `:text/:page-count/:pages/:selected-pages/:route/:source/:warnings`；文本源的本地提取返回 `:page-count nil` 与 `:pages []`，不伪造第 1 页。失败保留 `:error/:pages/:selected-pages/:suggested-route`，其中 `:suggested-route` 同时从 `:error` 内提升到 extraction 顶层。`NO_TEXT`/`OCR_EMPTY` 是失败，不是文本。密码只进请求，不进入描述符、日志或响应；extractor 异常只返回固定文案与异常类名。

## 6 工作区文件

```clojure
(workspace-attachment! service
  {:workspace-root "/repo" :relative-path "docs/a.md"
   :snapshot? true})   ; 默认 true：保存应用内副本
```

`snapshot? false` 只建立关联，不复制字节；`refresh-workspace!` 用 stamp 检测变化；未变化时返回 `:status :ready`。`snapshot-workspace!` 只在文件未变化时把关联转为本地副本。文件已变化时没有保存过预览版本字节，因此拒绝并返回 `:WORKSPACE_FILE_CHANGED`，绝不把新字节冒充旧预览。路径必须是工作区内相对路径，绝对路径、`..` 逃逸与符号链接逃逸都会被拒绝。

## 7 测试

```bash
clojure -M:test:server -n hc.server.attachments.model-test
clojure -M:test:server -n hc.server.attachments.ingest-test
```

覆盖：20/21 项边界、100 MiB 边界、真实 PNG 缩放与透明通道、EXIF 方向解析、严格 UTF-8、MIME 魔数不匹配、PDF 加密未知、真实提取器成功/拒绝、工作区 stamp 变化与快照、路径逃逸、HTTP 201/409/413。

新增覆盖：`:preferred-route :text` 在 native 可用时仍选择文本路线；`:selected-pages` 驱动服务页数限制；`:suggested-route` 从 `:error` 提升到 extraction 顶层；`verify!` 重新计算 SHA-256 并拒绝同尺寸替换；`ATTACHMENT_EXTRACTION_SOURCE_MISMATCH` 拒绝跨 blob 提取文本；客户端按标签页 `client-id` 隔离队列并恢复失败卡为 `:UPLOAD_INTERRUPTED`。

实际 smoke（2026-11-03）：真实 PNG 3000×1000 → 2048×683 发送副本且原图仍在；真实 JPEG + EXIF orientation 6 → 50×100 像素发送副本；真实文本 → 本地副本与提取文本；100 MiB+1 流 → `:ATTACHMENT_TOO_LARGE`；工作区关联 → 修改后 `:WORKSPACE_FILE_CHANGED`，快照拒绝冒充旧预览；真实 http-kit 监听上 `POST /attachments` → 201，随后 `GET /blobs/<id>` 字节一致，`draft-count=20` → 409。

真实集成监听（`hc.server.app`，127.0.0.1:17777，宿主 `:max-body` 101 MiB）另测：5 MiB 文件 → 201；**恰好 100 MiB 文件 → 201** 并保存本地副本；100 MiB+1 → EDN 413 `:ATTACHMENT_TOO_LARGE`；下载字节一致。另在 17778 以真实 `hc.server.documents` 适配器上传文本，返回 `:extraction {:status :ready :route :text :encoding "UTF-8" :line-count 1 :source {...}}`。完整应用路径 `hc.server.documents.integration-test` 通过：真实 `system/start!` → 真实 PNG 上传 201 → `POST /attachments/extract` 200 → 真实 Tesseract OCR 文本与页来源；5 tests / 38 assertions。路由同时容忍 origin URL 尾斜杠造成的 `//attachments`，避免落回静态 405。未运行：真实模型供应商附件上传、浏览器文件选择器。

## 8 非目标

- 不实现 OCR、PDF 文本提取、音频转写、视频抽帧；这些由 `hc.server.documents` 提供真实适配。
- 不实现生成产物的完整性/溯源注册；那是 `hc.server.artifacts`。
- 不删除用户原文件；移除草稿附件只移除引用。blob 回收由引用感知清理器负责。
- 不把附件字节塞进 Sente 或会话状态；会话只保存描述符。
