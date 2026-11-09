# hc.client.preview — 生成的 HTML 受限预览

> 权威顺序：根 `AGENTS.md` §6.6、§4.2、§7.3 > `src/AGENTS.md` > `src/hc/client/AGENTS.md` > 本文件。

## 1 责任

1. 把一条助手消息中的 HTML（内联片段、代码围栏或产物 URL）渲染成受限预览。
2. 默认阻止外部网络；列出将要访问的域名，只有用户按域名允许后才进入 CSP。
3. 外部链接、下载、表单提交和模型调用都是可见请求；用户确认目标后才执行。
4. 失败时保留错误摘要、原文与复制入口；修复必须由用户发起新的明确轮次。
5. 生成中以操作回执显示“正在生成”（不新增用户侧交付状态，仍是进行中）；
   用户已输入时新版本先提示，用户选择后才切换。

## 2 文件

| 文件 | 责任 |
|---|---|
| `model.cljc` | 预览状态、版本切换、网络许可、外部请求归一化、大小与错误文案 |
| `view.cljs` | 自包含 React 组件；读取 URL、渲染 `sandbox/frame`、来源面板与确认面板 |

## 3 接口

```clojure
(require '[hc.client.preview.view :as preview])

(preview/generated-html-preview
 {:html "<h1>生成内容</h1>"
  :source-label "生成的 HTML"
  :streaming? false
  :error nil
  :on-fix! (fn [{:keys [source error]}] ...)})

(preview/generated-html-preview
 {:url "/blobs/sha256-..."          ; 或 :content-id
  :source-label "report.html"})
```

- `:html` 优先于 `:url`/`:content-id`。
- `:streaming?` 为真时只读内容可看，外部动作被拒绝；同一轮内不按 token 重载 iframe。
- `:error` 是宿主提供的生成/读取失败；组件保留原文并显示重试。
- `:on-fix!` 可选。没有回调时不显示假的“修复”按钮。
- 组件不写 Fulcro 状态，也不调用模型。

## 4 版本与输入保护

- 第一次内容直接显示。
- 流式生成期间，后续 token 先挂在 `:pending-version`，避免每个 token 重载 iframe；流结束后自动安装。
- 用户产生输入后（`hc/input`），新版本只提示，不替换；用户点击“切换到新版本”才重载。
- 新版本会清空旧版本的网络许可，避免修复自动扩权。
- 页面重新加载不会清空小应用数据；预览本身不持久化表单。URL 加载带递增请求代号，迟到响应不得覆盖新目标；Escape 关闭最上层全屏预览。

## 5 外部动作

| 请求 | 行为 |
|---|---|
| `hc/external-link` | 显示完整 URL 与目标站点；仅 http/https 可在浏览器打开 |
| `hc/download` | 显示文件名与大小；内联 `data:` 需再次点击；URL 交给浏览器 |
| `hc/external-submit` | 显示方法、目标与字段名；**不自动提交**，只可打开页面 |
| `hc/network-request` | 未允许的 origin 被 CSP 阻止，并提示用户可显式允许 |
| 模型调用 | 没有桥接 API；只显示“请在聊天输入区提出” |

## 6 测试

```bash
clojure -M:test -n hc.client.preview.model-test
```

覆盖：初始关闭、首段流式内容可见、后续 token 挂起、dirty 版本提示、接受/保留、网络许可重置、pending origin、外链/下载/表单策略、内容 ID URL、错误文案。

## 7 明确限制

1. 相对资源无法在 `srcdoc` 中解析；外部资源需要按域名允许。
2. 页面直接改 `location.href` 的导航不能完全拦截；sandbox 仍阻止同源读取和顶层导航。
3. `unsafe-eval` 未开启；使用 `eval` 的页面会失败。
4. 预览不保存表单或 `localStorage`；刷新即失去页面内状态。
5. 真实浏览器冒烟必须记录实际运行范围；未运行的屏幕阅读器、移动端手势与跨浏览器差异标 `NOT RUN`。

### 7.1 当前验证状态（2026-11-03）

- 纯模型测试已通过：首段流式内容可见、后续 token 挂起、dirty 版本提示、接受/保留、网络许可重置、外链/下载/表单策略、错误文案。
- 源码已由 `conversation-ui` 接入 `text/html` 附件与 ```html 代码段路径；组件使用 `allow-scripts` iframe、CSP 与 token 消息闸门。
- **NOT RUN**：真实浏览器中的预览渲染、外链确认、下载确认、表单拒绝、CSP 阻止未允许域名、预览内 localStorage 仅内存、流式版本切换与移动端布局。真实服务端当时被另一个模块的语法错误阻塞；这些项交 devops 复验。
