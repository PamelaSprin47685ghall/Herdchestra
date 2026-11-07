# hc.server.artifacts — 产物登记与交付

> 权威顺序：根 `AGENTS.md` §7.1、§7.2、§8.6 > `src/AGENTS.md` >
> `src/hc/server/AGENTS.md` > 本文件。
> 本包只负责“已生成的交付物如何成为用户可用的产物”，不负责生成文件、不
> 负责用户附件摄取，也不把临时路径冒充交付物。

## 1 四步提交流程

根 `AGENTS.md` §7.2 规定产物必须经历：

1. 任务工作区生成文件；
2. 完整性与可打开检查；
3. 登记产物元数据（名称、类型、内容 ID、来源任务与输入引用、保存位置、
   检查结果、状态）；
4. 向用户宣告可用。

本包实现第 2–4 步。第 1 步由调用方完成；`register!` 只接受真实文件或已
finalize 的受管 Blob 内容 ID。

根 §7.2 的例外必须保留：LLM 写的报告（join 结果、Plan 报告、子任期终态
报告）全文内联，不截断、不摘要、不登记为 artifact。只有非 LLM 的原始
工具输出（Shell stdout/stderr、二进制、超大日志）才进入产物提交流程。

## 2 责任边界

- **字节**：`hc.server.blob-store` 拥有 staging、内容 ID、原子 rename、
  校验与清理。本包不在别处复制一套内容寻址。
- **引用**：产物文档与 `:blob-refs {:add ...}` 在同一次
  `storage/commit-migration!` 中提交，整段持有
  `blob-store/with-store-lock`。清理器要么看到引用，要么看到未引用对象；
  不存在“文档已可见但引用尚未建立”的窗口。
- **分区**：产物是会话级的。默认分区为 `session/<session-id>`，Blob 引用
  也在同一分区；存储的 `:blob-refs` 是分区级表，不假设全局引用。
- **通道**：实时通道只传产物元数据、状态与 `/blobs/<content-id>` URL。
  字节永远走独立 HTTP 通道；本包不 require 网关，也不发送事件。调用方用
  `announcement` 构造 `CUSTOM hc/artifact-available`。
- **附件**：用户附件摄取属于 `hc.server.attachments` 等层。它可以用
  `:content-id` 调用本包登记为交付物，但本包不接管草稿/消息附件状态。

## 3 文件与命名空间

```text
src/hc/server/artifacts.clj            门面：service、register!、open!、download、verify!
src/hc/server/artifacts/check.clj      源文件观测、媒体类型、UTF-8 与 Blob 检查
src/hc/server/artifacts/registry.clj   元数据文档、持久化、列表、状态转换
src/hc/server/artifacts/error.clj      稳定错误码与分类
```

## 4 登记接口

```clojure
(require '[hc.server.artifacts :as artifacts])

(def svc (artifacts/service {:store store :blob-root blob-root}))

(def result
  (artifacts/register!
   svc {:session-id "s1"
        :file "/work/out/report.txt"
        :name "report.txt"
        :kind :file
        :source-task-id "task-1"
        :source-call-id "call-1"
        :project-id "project-1"
        :workspace-root "/work"
        :relative-path "out/report.txt"
        :inputs [{:kind :file :path "data.csv"
                  :stamp {:mtime-sec 1 :mtime-nsec 2 :size 3}}]
        :producer {:kind :tool :name "shell"}}))
```

返回：

```clojure
{:ok? true
 :artifact {:id "art-..." :content-id "sha256-..." :size 123
            :status :available :download-url "/blobs/sha256-...?filename=report.txt"
            :checks [...] :origin {...} ...}
 :announcement {:type "CUSTOM" :name "hc/artifact-available" :value {...}}
 :checks [...] :blob {:id ... :size ... :sha256 ...} :replayed? false}
```

`register!` 是幂等的：相同 `:artifact-id`（或默认派生 ID）且相同来源、
名称、任务与输入引用时返回已有产物并置 `:replayed? true`；默认 ID 由
内容 ID/路径、名称与类型派生，不再是随机值。同一 ID 携带不同内容或
元数据时返回 `:ARTIFACT_REGISTRATION_CONFLICT`，绝不静默替换已交付
内容。带 `:producer` 的工具产物必须提供 `:source-task-id`。截断标记、截断原因与来源总字节数也属于登记身份；把完整声明
改成截断声明（或反向）是冲突，不是重放。

`register!` 也接受 `:content-id`（已 finalize 的 Blob）而不接受
`:file`。两者同时给出会被拒绝，避免“到底交付了哪份字节”含糊。

应用接线用 `hc.server.artifacts.integration/register-executor-result!`：
它从 Owner 执行器结果中取 Shell 捕获或通用 `:artifact`，登记后调用
`publish!` 广播 announcement，成功后才 `mark-announced!`。Shell 结果也
可直接用 `register-shell-output!`：它取
`[:output :artifact]` 的 `:path`，拒绝缺失路径或 `:error`，并在
`:truncated?` 为 true 时强制登记截断原因。调用方仍提供 session/task/call
与输入引用。若捕获含二进制字节，调用方应显式传
`:media-type "application/octet-stream"` 与合适的 `:kind`，不要让默认
文本检查把真实二进制误判为不可打开。

## 5 检查

`check.clj` 产生可审计的检查项：

- `:source-exists` / `:source-regular`：源必须是常规文件；符号链接默认拒绝，
  只有显式 `:follow-links? true` 才跟随。
- `:non-empty`：默认拒绝 0 字节文件；显式 `:allow-empty? true` 可接受。
- `:source-stable`：复制前后 `(mtime-sec, mtime-nsec, size)` 必须相同。
- `:blob-exists` / `:size-matches` / `:digest-matches`：
  内容 ID、大小与 SHA-256 一致。
- `:content-addressable`：`blob/verify!` 重算摘要，证明对象未被替换。
- `:blob-readable`：能从 finalize 对象打开并读取前缀。
- `:encoding`：声明为文本的产物必须按 UTF-8 解码；二进制不强制。
- `:media-type`：显式类型优先，其次扩展名，最后按前缀嗅探。

检查失败不写产物文档。已 finalize 但未登记的 Blob 没有引用，由清理器在
安全窗口后回收；本包不会按内容 ID 盲目删除，因为同一内容可能已被其他
产物引用。

## 6 打开、下载与状态

```clojure
(artifacts/download svc artifact)
;; => {:url "/blobs/sha256-...?filename=report.txt"
;;     :content-id ... :filename ... :media-type ... :size ... :etag ...}

(with-open [in (:stream (artifacts/open! svc artifact))]
  ...)

(artifacts/read-bytes! svc artifact {:max-bytes (* 1024 1024)})
(artifacts/verify! svc artifact)
(artifacts/refresh-status! svc {:session-id "s1" :artifact-id id}
                           {:persist? true})
(artifacts/retract! svc {:session-id "s1" :artifact-id id})
```

- 只有 `:status :available` 的产物才能 `open!`、`download` 或出现在
  `:actions` 中。
- `:download-url` 带 ASCII 安全文件名，网关据此返回
  `Content-Disposition: attachment`；`:open-url` 是不带 `?filename=` 的
  裸 `/blobs/<content-id>`，网关按媒体类型内联呈现。两个 URL 不能混用：
  把下载 URL 当作打开 URL 会让浏览器直接下载而不是显示。
- `list-artifacts` 在生成快照时逐个核对 Blob 是否仍存在、大小是否与文档
  一致。文档写着 `:available` 但对象已消失时，快照投影为 `:missing`；
  大小不符或读取失败投影为 `:corrupt`；缺少规范内容 ID 投影为
  `:incomplete`。三者都清空下载/打开 URL，并带 `:missing-reason`。
  同尺寸的摘要损坏仍由 `verify!`、`download`、`open!` 或实际打开时发现。
- `verify!` 重新检查 Blob；`refresh-status!` 可把 `:missing` 或 `:corrupt`
  持久化。缺失或损坏的对象保留引用作为证据，不伪装成“已删除”。
- 幂等重放不是“只看文档”：`register!` 在返回已有产物前重新核对 Blob 的
  存在、大小与摘要；`mark-announced!` 也先核对字节，再允许宣告。
- `download`、`open!` 与 `read-bytes!` 在交付前重新核对 Blob 的内容 ID、
  大小、摘要与文本可解码性；它们还要求 Blob 的实际元数据与产物文档的
  `:size`/`:sha256` 一致。文档过期不会被当成可交付字节。快照列表只做
  存在与大小检查，因此列表中的“可用”仍可能在点击时因同尺寸损坏而失败；
  `verify!` 是显式全量核对入口。
- `retract!` 在同一次迁移中删除产物文档并按需释放 Blob 引用；同一分区
  还有其他产物共享该内容 ID 时保留引用。字节不在删除时移除，由清理器在
  安全窗口后回收。
- `download-url` 只由规范内容 ID 生成。ASCII 安全文件名用于 HTTP
  `Content-Disposition`；原始名称留在元数据中，供客户端 `download`
  属性使用。网关对非 ASCII 文件名会省略 disposition，这是已知边界。
- 调用方交付被截断的工具输出时必须显式传 `:truncated? true`、
  `:truncated-reason` 与可选的 `:source-total-bytes`。产物仍可下载，
  但文档与卡片都标记 `:complete? false`，绝不把截断日志说成完整成果。

## 7 元数据形状

产物文档使用无命名空间键，客户端可直接读取：

```clojure
{:id "art-..."
 :name "report.txt" :filename "report.txt" :download-filename "report.txt"
 :kind :file :media-type "text/plain"
 :content-id "sha256-..." :sha256 "..." :size 123
 :save-location {:kind :managed-blob-store :content-id "sha256-..." :size 123}
 :status :available :environment :instance
 :source-task-id "task-1" :source-call-id "call-1" :project-id "p1"
 :workspace-root "/work" :relative-path "out/report.txt"
 :source-path "/work/out/report.txt"
 :source-stamp {:mtime-sec ... :mtime-nsec ... :size ...}
 :source-modified-at "2026-11-03T..."
 :origin {:workspace-root "/work" :path "..." :real-path "..."
          :relative-path "..." :stamp {...} :modified-at "..."}
 :inputs [{:kind :file :path "data.csv" :stamp {...}}]
 :producer {:kind :tool :name "shell"}
 :truncated? false :complete? true
 :checks [{:check :blob-exists :ok? true} ...]
 :registered-at "..." :updated-at "..." :version 1 :announced? false
 :storage/version 42 :storage/partition "session/s1" :storage/session-id "s1"}
```

`public-artifact` 追加 `:download-url`、`:open-url`、`:openable?`、
`:previewable?`、`:downloadable?`、`:complete?`；这些是派生字段，不进入
持久文档。`:complete?` 由 `:truncated?` 取反，不可在文档里单独伪造。

登记只产生 `:announcement` 事件，不声称事件已经送达。调用方真正广播后调用
`mark-announced!`，把 `:announced?` 持久化为 true；该转换幂等，且不改动
Blob 引用。

## 8 错误

所有预期失败是带 `:error` 与 `:artifacts/expected? true` 的
`ExceptionInfo`：

```text
:ARTIFACT_INVALID_REQUEST        :ARTIFACT_INVALID_CONTENT_ID
:ARTIFACT_INVALID_MEDIA_TYPE     :ARTIFACT_SOURCE_MISSING
:ARTIFACT_SOURCE_NOT_REGULAR     :ARTIFACT_SOURCE_UNSTABLE
:ARTIFACT_EMPTY                  :ARTIFACT_LIMIT_EXCEEDED
:ARTIFACT_SESSION_LIMIT          :ARTIFACT_SIZE_MISMATCH
:ARTIFACT_INTEGRITY              :ARTIFACT_ENCODING
:ARTIFACT_BLOB_MISSING           :ARTIFACT_NOT_FOUND
:ARTIFACT_NOT_AVAILABLE          :ARTIFACT_REGISTRATION_CONFLICT
:ARTIFACT_REGISTRATION_FAILED    :ARTIFACT_STATUS_UPDATE_FAILED
:ARTIFACT_OUTPUT_UNAVAILABLE
```

`expected-error?`、`error-code`、`error-describe` 用于分类；不要把错误消息
当协议。

## 9 测试

```bash
clojure -M:test:server -n hc.server.artifacts-test
clojure -M:test:server -n hc.server.artifacts-smoke-test
clojure -M:test:server -n hc.server.artifacts-app-smoke-test
```

`artifacts-test` 使用真实临时 Datalevin 环境与真实文件，覆盖登记、重开、
幂等、来源拒绝、UTF-8、已有内容 ID、状态持久化、截断标记、Shell 输出
助手与引用感知清理。

`artifacts-smoke-test` 启动 JDK HTTP 宿主，经真实 socket 下载已登记产物，
覆盖 GET、HEAD、Range、ETag、Content-Disposition 与 404；不使用模拟字节。
它还通过真实 Shell 进程生成 `out/report.txt` 与 Shell 输出捕获，再把两份
文件登记为产物并经 HTTP 下载比对；生成、检查、登记、传输是同一条链路。

`artifacts-app-smoke-test` 启动完整应用，使用系统装配的 artifact 组件登记
工作区生成文件，从绑定 HTTP 端口下载，并核对应用快照中的 `:artifacts`
条目；它覆盖 `system.clj` 装配、快照与网关 URL，而不是手写服务。

## 10 应用装配

`hc.server.artifacts.integration/attach` 把 store、blob root 与配置装配成
应用可用的薄接口：

```clojure
(require '[hc.server.artifacts.integration :as artifacts-app])

(def artifacts
  (artifacts-app/attach {:store store
                         :blob-root (:blob-root config)
                         :config config}))

;; => {:service ... :register! ... :register-shell-output! ...
;;     :retract! ... :mark-announced! ... :list ... :get ...
;;     :download ... :open! ... :verify! ... :content-type-fn ... :snapshot ...}
```

`(:snapshot artifacts session-id)` 返回 `{:artifacts [...] :version n}`，可直接
并入会话快照。`(:content-type-fn artifacts)` 是内容 ID → 媒体类型的缓存
适配器，供网关 `/blobs` 路由使用；缓存由登记与列表读取填充，重启后随会话
快照重新建立。应用装配不新增 HTTP 路由，也不自行发布事件。

## 11 已知缺口

以下条目是明确未完成或未验证的部分，不把设计成立当作运行证据：

1. **HTTP 组件装配已通过冒烟**：`system.clj` 已接入 `attach`、快照
   `:artifacts` 与 `:content-type-fn`；`artifacts-app-smoke-test` 启动完整
   应用、经系统 artifact 组件登记生成文件并从绑定端口下载，1 test /
   11 assertions 通过（2026-11-05 复核）。该冒烟只证明 HTTP 组件与下载
   链路，不证明业务调用方已接入，见下一条。
2. **业务调用方未接入**：Owner / 工具结果还没有在真实任务中调用
   `register!` 或 `register-shell-output!`。本包不自动把 Shell 输出或
   临时路径变成产物，调用方必须显式提交。因此“组件冒烟通过”不等于
   “产物功能已端到端交付”。
3. **真实浏览器冒烟未运行**：`shadow-cljs compile app`、
   `test/js/verify_artifacts_ui.sh` 的真实 React 静态渲染均通过；真实浏览器
   `#/materials` 点击下载/打开的冒烟标 `NOT RUN`，由 workbench 执行。
4. **文本编码检查只覆盖前缀**：默认检查前 1 MiB（`:text-check-bytes`）。
   超大文本后半段若编码损坏，当前不会在登记时发现；完整 Blob 的 SHA-256
   仍然覆盖全部字节。另外，直接访问网关 `/blobs/<id>` 时网关只流式发送
   字节，不逐请求重算摘要；同尺寸介质损坏由 `verify!`、`download` 或
   `open!` 的校验发现。
5. **清理调度不在本包**：`retract!` 只释放引用；`blob/cleanup!` 的定期
   调度与 `manage-blob-refs!` 快照读取由应用/备份层负责。
6. **非 ASCII 下载名**：网关只接受 `[A-Za-z0-9._-]{1,128}`，非 ASCII 名称
   使用 ASCII 回退；原始名称保留在元数据与客户端 `download` 属性中。
7. **Windows 冒烟未运行**：真实生成冒烟使用 POSIX `sh`；Windows 路径与
   MSYS2 下的生成/下载链路标 `NOT RUN`。
