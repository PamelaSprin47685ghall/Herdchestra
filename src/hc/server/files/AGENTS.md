# `hc.server.files` — 工作区文件只读 API

> 权威顺序：根 `AGENTS.md` §6.1、§7.3、§7.4 旅程 3.5、6.5（工作区文件与小应用） > `src/AGENTS.md` > `src/hc/server/AGENTS.md` > 本文件。

## 1 责任

本包为浏览器工作台提供工作区文件的只读观察面。所有读取都经过 `hc.core.workspace-gate` 的无锁读取器：

- `stat` 返回存在性、类型与 `(mtime-sec, mtime-nsec, size)`；
- `read` 返回 UTF-8 文本与整文件 `stamp`；读取前后 `stamp` 变化时返回 `:FILE_CONFLICT`，不把不稳定内容当成快照；
- `list` 用纯探查目录列表，再对每个条目单独 `stat`；目录时间从不解释为子树版本；
- `search` 支持 `:name`（默认）与 `:content` 两种模式，带目录数、条目数、
  深度与结果上限；达到上限时返回 `:truncated? true`。内容模式读取普通
  UTF-8 文件，返回首个命中行与文件内匹配数。

本包不写文件、不删除、不移动、不创建目录，也不提供“忽略 stamp 继续”的入口。浏览器路径空间被限制在工作区根内，这一点比代理侧 `workspace-gate` 更窄：绝对路径、`..`、NUL、盘符路径与解析到根外的符号链接一律拒绝。

## 2 公开接口

```clojure
(require '[hc.server.files :as files]
         '[hc.server.files.http :as files-http])

(def svc (files/service {:root "/workspace"}))
(files/root-info svc)
(files/list-entries svc "sub")
(files/stat-path svc "sub/a.txt")
(files/read-text svc "sub/a.txt")
(files/search svc {:query "a" :mode :name})
(files/search svc {:query "needle" :mode :content})
(files/dispatch svc :list {:path "sub"})
```

`service` 选项：`:root`（必填）、`:max-file-bytes`（默认 2 MiB）、`:max-entries`（2000）、`:max-search-results`（200）、`:max-search-dirs`（500）、`:max-search-entries`（10000）、`:max-search-depth`（12）。内容搜索另有固定上限：`:max-search-content-file-bytes`（524288）、`:max-search-content-total-bytes`（8388608）、`:max-search-content-files`（2000）与 `:max-search-snippet-chars`（160）。根不存在时服务仍可构造，读取返回普通拒绝，不使整个应用启动失败。

成功结果带 `:ok? true`；拒绝结果带 `:ok? false :error {:code :message :details}`。
浏览器路径只接受 `/` 分隔符；反斜杠按 `:INVALID_PATH` 拒绝，不做平台翻译。
搜索 `limit` 必须是正整数，`0` 或负数返回 `:INVALID_QUERY`。

| 代码 | 含义 |
|---|---|
| `:INVALID_PATH` / `:ABSOLUTE_PATH` / `:PATH_ESCAPE` | 路径不是工作区相对路径 |
| `:OUTSIDE_WORKSPACE` | 符号链接解析到根外 |
| `:NOT_FOUND` | 路径或文件不存在 |
| `:NOT_A_DIRECTORY` / `:NOT_A_FILE` | 类型不符 |
| `:FILE_TOO_LARGE` / `:LIMIT_EXCEEDED` | 超出单文件或搜索上限 |
| `:BINARY_FILE` | 不是可显示的规范 UTF-8 文本 |
| `:FILE_CONFLICT` | 读取期间 `stamp` 变化 |
| `:UNSUPPORTED_OPERATION` | 二进制或无法无损解码的内容读取；不伪装成已执行 |
| `:INVALID_QUERY` | 空搜索词或非法 `limit` |
| `:IO_ERROR` | 操作系统拒绝读取或发生其他 I/O 失败 |

条目形状：

```clojure
{:name "a.txt" :path "sub/a.txt" :kind :file :exists? true
 :symlink? false :broken? false :outside? false :stamp-applicable? true :previewable? true
 :size 42 :mtime-sec 1700000000 :mtime-nsec 123000000
 :stamp {:mtime-sec 1700000000 :mtime-nsec 123000000 :size 42}}
```

目录条目 `:stamp` 为 `nil` 且 `:stamp-applicable? false`；指向根外的符号链接为 `:kind :symlink-outside`，不暴露目标大小或时间。排序为目录优先，其次按名称不区分大小写。

## 3 HTTP

`files-http/handler` 对 `/api/workspace` 前缀返回 Ring 响应，其他路径返回 `nil`，由应用与网关/静态回退组合。

| 方法 | 路径 | 参数 |
|---|---|---|
| GET/HEAD | `/api/workspace` | — |
| GET/HEAD | `/api/workspace/list` | `path` |
| GET/HEAD | `/api/workspace/stat` | `path` |
| GET/HEAD | `/api/workspace/read` | `path` |
| GET/HEAD | `/api/workspace/search` | `q`、可选 `limit`、`mode=name|content` |

响应为 `application/edn`，`cache-control: no-store`。写方法返回 405；未知 `/api/workspace/*` 返回 404，绝不落入 SPA 回退。状态码映射：路径错误 400、根外 403、不存在 404、stamp 冲突 409、过大 413、二进制 415、未实现能力 501。

## 4 应用装配

`hc.server.app.system/start!` 在网关之前挂载：

```clojure
files-handler (files-http/handler
               (files/service {:root (:workspace-root config)}))
ring-handler (fn [request]
               (or (files-handler request)
                   (if (= "/healthz" (:uri request))
                     ((health/handler (health-system)) request)
                     ((gateway/handler gateway-handle) request))))
```

本包不拥有 socket、网关或静态资源。`/api/workspace` 只承载小文本预览；大对象仍走独立 Blob 通道。

## 5 浏览器合同

`hc.client.files` 只消费本 API。UI 必须展示相对路径、类型、大小、修改时间与文件 `stamp`；目录明确标注“目录无内容 stamp”。差异来自用户实际看过的上一次文本与新读取文本，并同时展示旧/新 `stamp`。没有基线时不得生成差异，只显示当前文本或冲突 stamp。删除、重命名、新建、下载与恢复按钮在本只读面不存在，不以禁用按钮伪装已交付能力。

## 6 测试

- `test/hc/server/files_test.clj`：根/嵌套列表、目录优先、相对路径、精确 stamp、`FILE_CONFLICT`（含 HTTP 409）、二进制、大小上限、路径逃逸、根外符号链接、名称搜索截断、非法 limit、反斜杠拒绝、HTTP 状态与 `nil` 回退。
- `test/hc/client/files/diff_test.clj`：精确 LCS、上下文 hunk、粗粒度回退、过大差异不伪造。
- `test/hc/client/files/model_test.clj`：列表/读取/搜索状态、失败保留旧预览、导航清理、stamp 与大小格式化。
- `test/hc/client/files/ui_smoke.cljs` 与 `test/js/verify_files_ui.sh`：真实 React 元素树的服务端渲染检查。

```bash
clojure -M:test -n hc.server.files-test
clojure -M:test -n hc.client.files.diff-test -n hc.client.files.model-test
bash test/js/verify_files_ui.sh
```

## 7 边界

- `(mtime, size)` 相同不证明内容相同（F08）；本包不增加隐藏哈希。
- 目录列表不是全工作区一致快照；读取期间其他提交或外部编辑器仍可改变文件。
- 符号链接按操作系统默认跟随；根外目标只显示链接本身。
- 搜索是尽力而为的有界遍历，不宣称完整。内容模式对二进制/无法无损解码
  记 `:skipped :binary`，读取期 `:FILE_CONFLICT`/`:STRUCTURE_CONFLICT` 记
  `:skipped :unstable`，单文件超限记 `:skipped :too-large`；总量或文件数
  预算耗尽时只返回截断结果。根外符号链接不跟随。
- HTTP 响应在内存中组装整段文本，受单文件 2 MiB 默认上限约束。
