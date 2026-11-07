# `src/hc/server/documents` — 安全文档提取与本地 OCR

> 权威顺序：根 `AGENTS.md` §7.4 旅程 6.6（多模态附件、OCR、PDF 选页与来源身份）、§7.1、§8.5 > `src/AGENTS.md` > `src/hc/server/AGENTS.md` > 本文件。
> 本文件只细化实现接口与拒绝语义；根规格冲突时以根规格为准，并立即修正本文件与代码。

## 0 这个包做什么

`hc.server.documents` 把用户材料转换成**可核对**的文本或预处理图像，供附件发送、OCR 对照修订与来源引用使用。它只做适配，不拥有附件队列、草稿、消息、模型能力或 UI。

它提供：

- 文本文件：严格编码解码，报告编码、行数、字符数；无效字节拒绝，绝不静默替换字符。
- PDF：Apache PDFBox 3 读取页数、逐页文本、加密密码与权限；页码保持原始 1-based 身份。
- 图像：EXIF 方向、长边缩放、透明通道保留/显式压平、像素上限。
- OCR：本地 CPU Tesseract（JavaCPP 预编译原生库），内置 `eng` 与 `chi_sim` 数据；逐页返回文本或明确的空页/失败页。
- 显式处理选择：`:native` / `:text` / `:ocr` 三选一；不可用就拒绝，绝不自动换路线。

它不做：DOCX/PPTX/EPUB/RTF 解析、云 OCR 客户端、视觉模型调用、附件持久化、UI 渲染、模型能力判断、密码保存。RTF 是带控制字的文档格式，字节可被 UTF-8 解码也不等于已提取正文；检测到 `{\rtf` 时返回 `:UNSUPPORTED_MIME`。需要云 OCR 时由调用方注入实现同一 `:recognize!` 协议的 engine；本包不伪造其可用性。

## 1 依赖与运行环境

`deps.edn` 的 `:server` 别名提供：

| 依赖 | 用途 |
|---|---|
| `org.apache.pdfbox/pdfbox` 3.0.5 | PDF 页、文本、加密、渲染 |
| `com.drewnoakes/metadata-extractor` 2.19.0 | EXIF orientation |
| `org.bytedeco/javacpp` 1.5.14 | 原生库装载 |
| `org.bytedeco/tesseract` 5.5.3-1.5.14 + 平台 classifier | Tesseract C API |
| `org.bytedeco/leptonica` 1.87.0-1.5.14 + 平台 classifier | 图像读取 |

平台 classifier 覆盖 `linux-x86_64`、`linux-arm64`、`macosx-x86_64`、`macosx-arm64`、`windows-x86_64`。OCR 不需要系统 `tesseract`、不需要 Pixi 额外包、不需要 GPU。

语言数据在 `resources/tessdata/`：`eng.traineddata`、`chi_sim.traineddata`（tessdata_fast）。首次调用时复制到 `java.io.tmpdir/herdchestra-tessdata-5.5.3`；可用系统属性 `herdchestra.tessdata` 或环境变量 `HERDCHESTRA_TESSDATA` 指向显式目录。缺少数据是 `:OCR_DATA_MISSING`，不会退化为空文本。

语言数据来自 `tesseract-ocr/tessdata_fast`（Apache-2.0）；原生库来自 JavaCPP Presets（Apache-2.0）。升级任一版本时同时更新 `version` 常量、缓存目录名与 `HACK.md` 实测记录。

## 2 公共接口

```clojure
(hc.server.documents/extract! request)              ; -> result map
(hc.server.documents/preprocess-image bytes opts)   ; -> image result
(hc.server.documents/parse-pages selection opts)    ; -> {:ok? true :pages ...}
(hc.server.documents/validate-pages pages n opts)
(hc.server.documents/select-route selection)
(hc.server.documents/processing route ocr-info)
(hc.server.documents/tesseract-engine opts)
(hc.server.documents/ocr-availability engine)
(hc.server.documents/ocr-status)
(hc.server.documents/available?)      ; boolean convenience probe
(hc.server.documents/service)         ; plain map for application assembly
```

### 2.1 `extract!` request

```clojure
{:source {:id "stable-id"            ; required
          :name "a.pdf"              ; optional hint
          :mime "application/pdf"    ; optional hint
          :size 12345}               ; optional declared size
 :path "/abs/file"                   ; exactly one of :path / :bytes / :stream
 :bytes byte-array
 :stream java.io.InputStream         ; caller owns and closes it
 :password "..."                     ; encrypted PDF only; never echoed
 :max-bytes 104857600                ; default 100 MiB
 :max-pages 5000                     ; default 5000
 :selection {:route :text           ; :native | :text | :ocr, required
             :pages [2 3 4 5]        ; or "2-5" / "第 2–5 页"; optional
             :encoding "UTF-8"       ; text only
             :ocr {:engine :tesseract ; map or :tesseract
                   :languages ["eng"] ; default ["chi_sim" "eng"]
                   :psm 3             ; 0-13; 0/1 need osd, not bundled
                   :dpi 200           ; PDF render DPI, max 300
                   :max-edge 2048
                   :timeout-ms 120000}
             :image {:max-edge 2048
                     :format :auto     ; :auto | :png | :jpeg
                     :quality 0.9
                     :flatten-alpha? false}}
 :ocr-engine <engine map>            ; optional explicit engine
 :ocr {...}                          ; shorthand for :selection :ocr
 :image {...}                        ; shorthand for :selection :image
 :max-bytes 104857600}
```

`:route` 是必填项。`:native` 表示“把原始字节交给模型”，`extract!` 不读取 `:path`/`:bytes`/`:stream`，只回显 source 与 processing。其余路线才读取字节；`:stream` 由调用方持有并在调用外关闭，本包只读到 `:max-bytes` 为止。

### 2.2 `extract!` result

成功：

```clojure
{:ok? true
 :route :text | :ocr | :native
 :source {:id "..." :name "..." :mime "..." :size 123 :sha256 "..."}
 :detected {:kind :text|:pdf|:image :mime "..." :format :... :declared-mime "..."}
 :processing {:route :text :location :device :description "..."
              :sends-original? false :sends-text? true}
 :page-count 3
 :selected-pages [2 3]
 :pages [{:page 2 :status :text|:no-text|:error
          :text "..." :source {:id "..." :name "..." :mime "..."}
          :error {...}}]              ; :error only on :status :error
 :text "..."                          ; concatenated pages / text body
 :warnings [{:code :KEYWORD :message "..." ...}]}
```

失败：

```clojure
{:ok? false
 :route :text | :ocr
 :source {...}
 :detected {...}                      ; when bytes were read
 :error {:code :KEYWORD :message "..." ...}
 :page-count 3                        ; when known
 :pages [...]                         ; page evidence, when known
 :warnings [...]}
```

期望拒绝是值，不是异常。调用方按 `[:error :code]` 分支。失败结果不包含部分 `:text`，也不把未读页伪装成已读。

## 3 处理选择与来源身份

- 路线不自动降级：扫描 PDF 走 `:text` 得到 `:NO_TEXT` 与 `:suggested-route :ocr`，不会自动 OCR；OCR 不可用得到 `:OCR_UNAVAILABLE`，不会自动改 `:native`。
- `:native` 与检测内容无关：即使字节是未知容器，只要用户明确选择原生发送，选择层允许；是否适合模型由调用方的能力检查决定。
- `:source :id` 必填。每个页结果带 `{:source {:id ...} :page n}`；PDF 页码不重排，选 `第 2–5 页` 后结果仍是 2、3、4、5。
- 文本源没有页：`:pages []`、`:page-count nil`；不伪造 `page 1`。图像 OCR 的页身份是图像序号，当前为 1。
- 声明的 `:mime`/扩展名只是提示。检测以字节为准；不一致产生 `:MIME_MISMATCH` 警告，并按检测类型解析。`a.pdf` 里是文本就按文本处理，`a.txt` 里是 PDF 就按 PDF 处理。
- 声明 `:size` 与实际读取不一致产生 `:SOURCE_SIZE_CHANGED` 警告；`:source` 回填实际 `:size` 与 SHA-256。

## 4 文本

- 支持 UTF-8（含 BOM）、UTF-16LE/BE（BOM）、UTF-32LE/BE（BOM）；无 BOM 默认严格 UTF-8。
- 显式 `:encoding` 优先；BOM 与显式编码冲突时产生 `:BOM_IGNORED` 警告。
- 解码使用 `CodingErrorAction/REPORT`；无效字节是 `:ENCODING_UNKNOWN`，绝不替换为 `�`。
- 无 BOM 且包含 NUL 的字节是 `:NOT_TEXT`，不按文本硬解。
- `:line-count`：换行数；非空且不以换行结尾时再加一。空文本为 0。
- 空文本是成功但 `:empty? true`，带 `:EMPTY_TEXT` 警告；不是“模型已读到内容”。

## 5 PDF

- 页数、逐页文本由 PDFBox 读取；每页单独 `PDFTextStripper`，一页失败不掩盖其他页。
- 页状态只有：`:text`（有非空文本）、`:no-text`（无可提取文本，常见于扫描页）、`:error`（该页解析失败）。`:skipped` 不会出现在结果里，因为只返回选中的页。
- 全部选中页都无文本时整体 `:NO_TEXT`，附 `:pages` 与 `:suggested-route :ocr`；部分页无文本时整体成功，但警告 `:PAGES_WITHOUT_TEXT` 列出页码。
- 加密 PDF 只在显式 `:password` 下打开；无密码是 `:NEEDS_PASSWORD`，错误密码是 `:WRONG_PASSWORD`。密码不进入结果、警告或日志。
- 加密 PDF 的 `AccessPermission/canExtractContent` 为 false 时返回 `:PDF_EXTRACTION_FORBIDDEN`，不绕过权限。
- OCR 路线把选中页用 `PDFRenderer` 渲染为 RGB，再按图像规则缩放/OCR；页码仍是原 PDF 页码。默认 200 DPI，上限 300；单页渲染像素超过 40M 是 `:PAGE_TOO_LARGE`。
- 单文档页数上限默认 5000；超出是 `:PDF_TOO_MANY_PAGES`。

## 6 图像

- 解码前先读头部尺寸；超过 `:max-pixels`（默认 40,000,000）是 `:IMAGE_TOO_LARGE`，不先分配大位图。
- EXIF orientation 1–8 在缩放前应用；`:orientation` 记录实际值。PNG/GIF/BMP/TIFF/JPEG 由 ImageIO 读取；WebP 等无解码器时是 `:IMAGE_UNREADABLE`，不按扩展名假装支持。
- 长边默认 2048，只缩小不放大；`:scale` 是实际比例，缩小时警告 `:IMAGE_SCALED`。
- `:format :auto`：有透明通道输出 PNG，否则 JPEG。显式 `:jpeg` 遇到透明通道必须同时给 `:flatten-alpha? true`，否则 `:ALPHA_UNSUPPORTED`；压平时警告 `:ALPHA_FLATTENED` 并记录背景。
- 动图只处理第一帧，警告 `:ANIMATED_FIRST_FRAME`。
- 输出结果含 `:bytes`、`:mime`、`:width`、`:height`、`:source-width`、`:source-height`、`:source-format`、`:source-alpha?`、`:alpha?`、`:orientation`、`:scale`、`:quality`、`:warnings`。
- OCR 内部强制 PNG 与白底压平，避免透明背景影响识别；这是 OCR 预处理，不是发送格式。

## 7 OCR

- engine 是普通 map：

```clojure
{:id :tesseract
 :engine-label "Tesseract (local CPU)"
 :location :device
 :availability (fn [] {:available? true ...})
 :recognize! (fn [{:keys [png-bytes languages psm timeout-ms]}] ...)}
```

- `hc.server.documents.ocr/recognize!` 先问 availability；不可用直接 `:OCR_UNAVAILABLE`，不调用 engine，不产生文本。
- 本地 Tesseract 每次调用新建 `TessBaseAPI`，结束后 `End`/`close`；语言、PSM 显式校验。请求的语言不在数据目录时 `:OCR_LANGUAGE_UNAVAILABLE` 并列出可用语言。
- `:psm` 0/1 需要 `osd.traineddata`；本包未内置，返回 `:OCR_UNSUPPORTED_PSM`。默认 PSM 3。
- 识别结果为空：单页标记 `:no-text`；全部选中页为空时整体 `:OCR_EMPTY`，附页码证据。OCR 失败（初始化、原生库、超时）是 `:OCR_*` 错误，不包装成空成功。
- 超时默认 120 秒；超时返回 `:OCR_TIMEOUT`。原生调用无法被强制杀死，守护线程被中断，结果仍如实标记超时。
- 不输出伪造的“精确置信度”。需要置信度时由调用方另行提供有来源的模型/服务结果。
- `processing` 对 `:ocr` 标 `:review-required? true`：数字、单位、表格与手写内容应由用户对照原图核对；本包不声称识别结果无需复核。
- 云端 OCR 由调用方注入 engine；`:location :service` 时 `processing` 会显示服务来源。本包不内置网络客户端。

## 8 拒绝码

| 代码 | 含义 |
|---|---|
| `:SOURCE_REQUIRED` / `:SOURCE_ID_REQUIRED` / `:SOURCE_AMBIGUOUS` | 缺少 source / 稳定 id / 同时给了 `:bytes` 与 `:path` |
| `:SOURCE_UNREADABLE` / `:SOURCE_TOO_LARGE` | 文件不可读 / 超过字节上限 |
| `:UNSUPPORTED_MIME` | 字节不属于文本、PDF、可解码图像 |
| `:ROUTE_REQUIRED` / `:ROUTE_UNKNOWN` / `:ROUTE_UNAVAILABLE` | 未选路线 / 未知路线 / 路线不适用 |
| `:PAGE_RANGE` | 页码非正、倒序、越界或展开过多 |
| `:NOT_TEXT` / `:ENCODING_UNKNOWN` | 二进制或无效编码 |
| `:PDF_UNREADABLE` / `:PDF_TOO_MANY_PAGES` | PDF 打不开 / 页数超限 |
| `:NEEDS_PASSWORD` / `:WRONG_PASSWORD` / `:PDF_EXTRACTION_FORBIDDEN` | 加密与权限 |
| `:NO_TEXT` | PDF 选中页没有文本层；附 `:suggested-route :ocr` |
| `:PAGE_EXTRACTION_FAILED` / `:PAGE_RENDER_FAILED` / `:PAGE_TOO_LARGE` | 单页证据 |
| `:IMAGE_UNREADABLE` / `:IMAGE_TOO_LARGE` / `:IMAGE_FORMAT_UNSUPPORTED` | 图像边界 |
| `:ALPHA_UNSUPPORTED` / `:IMAGE_ENCODE_FAILED` / `:IMAGE_QUALITY` | 图像输出边界 |
| `:OCR_UNAVAILABLE` / `:OCR_NATIVE_UNAVAILABLE` / `:OCR_DATA_MISSING` | OCR 运行条件 |
| `:OCR_LANGUAGE_UNAVAILABLE` / `:OCR_UNSUPPORTED_PSM` / `:OCR_INVALID_PSM` | OCR 参数 |
| `:OCR_INIT_FAILED` / `:OCR_FAILED` / `:OCR_TIMEOUT` / `:OCR_EMPTY` | OCR 执行结果 |

警告不改变成功/失败，但必须随结果返回：`:MIME_MISMATCH`、`:SOURCE_SIZE_CHANGED`、`:BOM_IGNORED`、`:EMPTY_TEXT`、`:PAGES_WITHOUT_TEXT`、`:PAGES_UNREADABLE`、`:PDF_ENCRYPTED_OPENED`、`:IMAGE_SCALED`、`:ALPHA_FLATTENED`、`:ANIMATED_FIRST_FRAME`。

## 9 安全边界

- 读取有界：默认 100 MiB，按实际读取字节计数；文件在读取中增长也拒绝。
- 图像先查头部尺寸，默认 40M 像素上限；不先分配巨图。
- PDF 页数默认上限 5000；单页渲染像素上限 40M。
- 不执行 PDF 中的脚本、链接或表单提交；PDFBox 只做解析/渲染。
- 不把密码写入结果、异常消息、警告或日志。`error/safe-message` 只返回固定文案与异常类名，不拼接库异常消息。
- 不按扩展名决定解析器；不把解析失败说成“文件损坏”或“模型无法接收”，只给具体拒绝码。
- 不自动 OCR、不自动改路线、不自动把空文本当成功。

## 10 装配与调用方

本包无生命周期、无全局单例、无 I/O 副作用缓存（除 OCR 语言数据复制）。应用层可直接把函数放进 system map：

```clojure
{:extract! hc.server.documents/extract!
 :preprocess! hc.server.documents/preprocess-image
 :parse-pages hc.server.documents/parse-pages
 :ocr-availability hc.server.documents/available?}
```

HTTP 上传二进制走独立 HTTP/Blob 通道；Sente 只传元数据与结果，不把大字节塞进实时通道。密码在独立本地框输入，不进入聊天日志。附件层负责本地副本、队列、模型能力与 UI；本包只回答“这段材料实际能提取出什么”。

## 11 测试

```bash
clojure -M:test:server -d test/hc/server/documents
```

测试使用真实生成的文件：

- 文本：UTF-8/UTF-16 BOM、显式 Latin-1、无效 UTF-8、NUL 二进制、空文本。
- PDF：三页混合 PDF（文本/扫描图/文本）、全扫描 PDF、选页、越界、加密（无密码/错密码/正确密码）、权限禁止、损坏 PDF。
- 图像：3000×1000 透明 PNG 缩放、不放大、EXIF orientation 6 像素与尺寸、JPEG 透明拒绝/显式压平、像素炸弹、无解码器。
- OCR：真实 Tesseract 读取生成图与 PDF 扫描页、空图、缺语言、非法 PSM、不可用 engine、超时。
- 编排：缺失/未知路线、`:native` 不读文件、MIME 不匹配、PDF 伪装文本、源大小变化、超限读取、失败带 route 与页码证据。

`smoke-test` 是真实样例冒烟：真实 UTF-8 文件、真实 PDFBox 解析、真实 PDF 页面渲染与真实 Tesseract 识别；不使用假 extractor。

## 12 非目标与后续边界

- DOCX/PPTX/EPUB/RTF、OCR 版面分析、表格结构恢复、手写专用模型不在本包当前范围；不要用“扩展名支持”暗示已支持。
- 不内置云 OCR、视觉模型或联网请求；调用方注入 engine。
- 不决定“原始发送还是提取文本”的产品默认；这是用户的显式选择，本包只执行与拒绝。
