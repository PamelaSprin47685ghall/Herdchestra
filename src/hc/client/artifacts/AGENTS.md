# hc.client.artifacts — 产物卡片与来源呈现

> 权威顺序：根 `AGENTS.md` §8.5、§8.6、§7.3 > `src/AGENTS.md` >
> `src/hc/client/AGENTS.md` > 本文件。
> 本包只把服务器产物元数据变成可读卡片与真实动作；不生成文件、不伪造
> 下载链接、不把建议文件名当作已交付产物。

## 1 责任

1. `hc.client.artifacts` 是纯 CLJC 投影：把 `:artifact/by-id` 或紧凑
   `:artifacts` 集合转成卡片、来源行与动作。
2. `hc.client.artifacts.view` 是薄 React 渲染层，消费卡片值，不重新解释
   业务状态。
3. 卡片必须显示根 §8.5 要求的字段：名称、类型、所属项目/工作区、完整
   路径、大小、修改时间、保存状态、版本/来源任务。缺失字段不猜。
4. 只有 `:status :available`、存在规范内容 ID，且服务器没有报告
   `:blob-present? false` 时，才产生打开/下载动作。缺失、损坏、拒绝或
   没有内容 ID 的产物只保留可读说明，不出现空链接。
5. 来源披露按需展开，列出源任务、源调用、项目、工作区、完整/相对路径、
   环境、内容 ID、内容/存储版本、注册时间、原始 `stamp`、输入引用与逐项
   检查结果。

## 2 卡片形状

```clojure
(hc.client.artifacts/card artifact)
;; => {:id ... :name ... :kind-label "文件" :type-label "text/plain"
;;     :size-label "2 KiB" :status-label "可用" :status-tone :ok
;;     :environment-label "实例工作区" :device "laptop-1"
;;     :project-id "p1" :workspace-root "/work"
;;     :path "/work/out/report.txt" :relative-path "out/report.txt"
;;     :modified-label "100.200 · 2 KiB"
;;     :source-task-id "task-1" :content-id "sha256-..."
;;     :download-url "/blobs/sha256-...?filename=report.txt"
;;     :open-url "/blobs/sha256-..."
;;     :openable? true
;;     :actions [{:action :open ...} {:action :download ...}
;;               {:action :copy-content-id ...}]
;;     :provenance [["来源任务" "task-1" true] ...]})
```

动作：

- `:open`：浏览器可显示的类型（text、image、audio、video、PDF）在新标签
  打开裸 `/blobs/<content-id>`。该 URL 不带 `?filename=`，因此网关不会加
  `Content-Disposition: attachment`，浏览器可以真正内联显示。
- `:download`：URL 带 ASCII 安全文件名，网关返回
  `Content-Disposition: attachment`；元素同时使用 `:download` 属性与原始
  文件名。
- `:copy-content-id`：复制规范内容 ID。

没有内容 ID，或内容 ID 不是 `sha256-` 加 64 位小写十六进制时，
`download-url` 为 nil，`actions` 为空。客户端不把任意字符串拼成
`/blobs/...` 链接。服务器提供的 `:download-url`/`:open-url` 只有仍指向
同一 `/blobs/<content-id>`（下载可带 `?filename=`）时才采用，否则回退到
规范 URL。`missing`/`corrupt`/`incomplete` 时 URL 为 nil，只保留内容 ID
（若存在）与原因。

## 3 状态与标签

- `:available` → “可用”，tone `:ok`
- `:missing` → “内容缺失”，tone `:error`
- `:corrupt` → “内容损坏”，tone `:error`
- `:incomplete` → “缺少内容 ID”，tone `:error`
- `:rejected` → “已拒绝”，tone `:warn`
- 未知状态 → “状态未知”，不伪造成功

即使服务器文档写 `:available`，缺少规范内容 ID 时客户端也投影为
`:incomplete`；服务器报告 `:blob-present? false` 时投影为 `:missing`。
两者都使 `saved?` 为 false，且不产生打开/下载动作。

截断的产物仍是可下载的真实字节，但卡片显示 `:truncated? true`、
`:complete? false` 与“已截断”来源行；不把部分日志说成完整成果。

未知类型、环境、大小同样显示明确的“未知”，不使用占位成功文案。

## 4 状态投影

```clojure
(hc.client.artifacts/from-state db)
```

接受：

- 归一化 `:artifact/by-id` 表；
- 字符串键 `"artifact/by-id"`；
- 紧凑 `:artifacts` 集合。

每次投影从当前状态重新构建，不把旧表 merge 进新表；服务器移除实体后，
下一次投影自然消失。`announcement-artifact` 从
`CUSTOM hc/artifact-available` 事件取值，供同步适配器把新产物写入
`:artifact/by-id`。

## 5 渲染

```clojure
(hc.client.artifacts.view/artifacts-list
 {:artifacts cards
  :on-copy (fn [content-id] ...)})
```

`artifacts-list` 渲染 `wb-list` 列表；`artifact-card` 渲染单张卡片。工作台
`views/materials.cljs` 的产物标签只负责把 `from-state` 结果与复制回调传给
这个渲染器，不另造动作或第二份状态。

## 6 测试

```bash
clojure -M:test -n hc.client.artifacts-test
test/js/verify_artifacts_ui.sh
```

测试覆盖完整卡片字段、来源行、打开/下载/复制动作、缺失与无内容 ID 时
不产生假链接、非法内容 ID、二进制只下载、截断标记、状态投影替换与未知
标签。

`verify_artifacts_ui.sh` 用 `cljs.main` 编译真实渲染器，再由 Node 的
`react-dom/server` 渲染 `artifacts-list`。它检查可见文件名、完整路径、
修改时间、大小、状态、来源任务、来源披露、真实 Blob URL，以及缺失/无
内容 ID 时没有下载链接。该脚本只用于验证，生产构建仍是 shadow-cljs
`:app`。

## 7 已知缺口

1. **真实浏览器冒烟未运行**：`test/js/verify_artifacts_ui.sh` 用
   `react-dom/server` 渲染真实元素树，`shadow-cljs compile app` 也通过；
   `#/materials` 中实际点击下载/打开与移动端布局的浏览器冒烟标 `NOT RUN`。
2. **重新生成/重新定位/撤回入口**：视图接受可选的 `:on-retry`、
   `:on-relocate`、`:on-retract` 回调；只有宿主提供真实命令面时才显示
   按钮。没有回调时显示“需要服务器命令面支持”，不放无副作用的假按钮。
3. **预览使用新标签页**：文本、图片、音视频与 PDF 的“打开”是不带
   `?filename=` 的裸 `/blobs/<content-id>` 新标签页；网关对 HTML/SVG/XML
   加 `Content-Security-Policy: sandbox`。本包不内嵌 PDF/视频预览器，也
   不把下载失败说成实例内原件丢失。
4. **删除/撤回未接 UI**：服务器提供 `retract!`，但材料页没有删除按钮；
   删除确认、共享内容提示与引用释放流程需要后续命令面接入。
5. **跨源下载属性**：`download` 文件名属性依赖同源或浏览器允许的下载
   语义；跨源部署时浏览器可能忽略该属性，但 URL 仍是真实内容地址。
