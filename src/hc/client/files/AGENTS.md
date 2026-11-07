# `hc.client.files` — 工作区文件浏览器

> 权威顺序：根 `AGENTS.md` §6.1、§7.3、§7.4 旅程 3.5、6.5（工作区文件与小应用） > `src/AGENTS.md` > `src/hc/client/AGENTS.md` > 本文件。

## 1 责任

本包在“材料与扩展 → 工作区文件”页签内渲染只读工作区浏览器。数据只来自 `hc.server.files` 的 HTTP 读取面；客户端不扫描本机目录，不缓存未由服务器确认的条目，不伪造删除/新建/重命名/下载动作。

| 文件 | 责任 |
|---|---|
| `api.cljs` | GET `/api/workspace/list|stat|read|search`，EDN 解析；只读，无本地回退 |
| `model.cljc` | 纯状态：路径、面包屑、条目归一化、读取/搜索结果、diff 基线、格式化 |
| `diff.cljc` | 纯行差异：精确 LCS、上下文 hunk、粗粒度回退、过大差异显式拒绝 |
| `mutations.cljs` | Fulcro `:ui/files` 状态迁移与 request-id 守卫 |
| `ui.cljs` | 纯 React 渲染器；无副作用，可服务端渲染 |
| `browser.cljs` | Fulcro 控制器：挂载加载、导航、预览、刷新、搜索、复制 |

## 2 状态

`:ui/files` 是本地显示副本，包含：

```clojure
{:status :idle|:loading|:loaded|:error
 :path "" :parent nil :workspace nil
 :entries [] :total-entries 0 :truncated? false :error nil
 :request-id 0
 :selected-path nil
 :preview nil :preview-status :idle|:loading|:loaded|:error
 :preview-error nil :preview-request-id 0
 :baseline nil :diff nil
 :search-query ""
 :search {:status :idle :query "" :mode :name :results []
          :truncated? false :error nil :request-id 0}}
```

每个请求带递增 `request-id`；旧响应在 id 不匹配时被丢弃，不能覆盖新目录或新预览。导航清空预览、基线与 diff；读取失败保留上一次成功预览与基线，并显示错误。

`baseline` 只在用户实际看到文本后建立。`apply-read` 用旧基线文本与新文本计算 diff，并附旧/新 `stamp`；没有基线或 `stamp` 相同时不生成差异。`stamp` 变化但文本相同时仍展示 `stamp` 对照，明确写“文本未变化”。读取结果的 `:path` 必须与请求路径一致；不一致按 `:PATH_MISMATCH` 拒绝，不用同名文件替代。

## 3 界面合同

1. **路径**：面包屑从工作区根到当前目录；行内名称是相对路径的最后一段；页脚显示服务器根位置。返回上级在根目录禁用并说明原因。
2. **条目**：显示名称、类型、大小、修改时间；普通文件显示精确 `(mtime, size)` stamp。目录显示“目录无内容 stamp”。根外符号链接显示“链接（工作区外）”，不可打开。
3. **预览**：只显示服务器读取的 UTF-8 文本；超限、二进制、缺失与 stamp 冲突分别显示稳定错误码。冲突显示原/当前 stamp 与“重新读取”，不显示不稳定文本。读取失败时错误下方继续显示上一次成功读取的完整文本，不只写“上方仍显示”。
4. **差异**：增删行带可见 `+`/`-` 与可访问标签“新增行/删除行”，不只靠颜色；显示 hunk 头、旧行号与新行号。粗粒度差异明确说明不是逐行最小差异；超过显示上限时不生成伪造 hunk。stamp-only 变化也渲染对照区。
5. **搜索**：只按名称搜索，明确标注“按名称”；结果被截断时说明不完整。内容搜索未实现，不提供对应开关。
6. **动作**：导航、刷新、读取、复制与名称搜索是真实动作；没有删除、新建、重命名、下载、恢复入口。只读说明始终可见。

## 4 集成

工作台根查询加入：

```clojure
{:ui/files (comp/get-query hc.client.files.browser/FilesPane)}
```

初始数据库使用 `(hc.client.files.browser/initial-state)`。材料页签只调用：

```clojure
(files/files-pane this (:ui/files props))
```

不要在其他视图复制一份文件状态，也不要直接调用 API 后跳过 `:ui/files`。

## 5 测试

```bash
clojure -M:test -n hc.client.files.diff-test -n hc.client.files.model-test
bash test/js/verify_files_ui.sh
```

`test/js/verify_files_ui.sh` 用 `react-dom/server` 渲染真实 React 元素树，检查标题、面包屑、条目、精确 stamp、目录无 stamp、根外链接、差异标记与可访问标签、冲突 stamp、只读说明，并确认不存在删除/新建假动作。

## 6 边界

- 客户端不持有文件内容历史，只为当前预览保留一份基线文本。
- 目录列表不是全工作区一致快照；刷新才更新。
- 只读面不实现可恢复删除；该动作需要受保护提交，不能由浏览器直接执行。
- 搜索不递归读取文件内容，也不把目录时间当作子树版本。
