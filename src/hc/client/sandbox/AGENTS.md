# hc.client.sandbox — 沙箱文档、消息闸门与外部动作确认

> 权威顺序：根 `AGENTS.md` §6.6、§7.3 > `src/AGENTS.md` > `src/hc/client/AGENTS.md` > 本文件。

## 1 责任

本包只做三件事：

1. 把模型生成或工作区来源的 HTML 包装成一份可放进 `srcdoc` 的受限文档；
2. 在文档启动前注入 Content-Security-Policy 与桥接脚本；
3. 校验来自 iframe 的 `postMessage`，把存储、工作区读取与外部动作请求交给宿主确认。

它不生成 HTML，不执行模型请求，不打开外部网址，不提交表单，不下载文件，也不持有任何密钥。

## 2 文件

| 文件 | 责任 |
|---|---|
| `model.cljc` | origin 归一化、外部域名扫描、CSP、HTML 注入、桥接脚本、消息类型与 token 校验 |
| `frame.cljs` | React iframe 宿主；`sandbox="allow-scripts"`；按 `contentWindow` + token 过滤消息；`reply!` 回执 |
| `ui.cljs` | 受限预览与小应用共用的外部动作确认面板、来源面板与样式 |

## 3 安全边界

1. iframe 只带 `allow-scripts`。**绝不**添加 `allow-same-origin`、`allow-forms`、`allow-popups`、`allow-downloads`、`allow-modals`、`allow-top-navigation`。因此页面拿不到产品 origin 的 cookie、localStorage、IndexedDB、父文档 DOM 或设备权限。
2. 默认 CSP：

   ```text
   default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline';
   img-src data: blob:; font-src data:; media-src data: blob:;
   connect-src 'none'; frame-src 'none'; object-src 'none';
   base-uri 'none'; form-action 'none'; worker-src 'none'; manifest-src 'none'
   ```

   只有用户明确允许的域名会追加到 `script-src`、`style-src`、`img-src`、`font-src`、`media-src` 与 `connect-src`。不添加 `*`、`self`、`unsafe-eval` 或 scheme 通配。
3. 每条消息必须同时满足：来源是当前 iframe 的 `contentWindow`、token 与注入值一致、type 在已知集合内。其他窗口或页面的消息一律忽略。
   注入幂等标记是每实例 `data-hc-sandbox-token`；页面自己写 `data-hc-sandbox="1"` 不能跳过注入。查找 `<head>` 前先遮蔽注释、脚本体与样式体，避免页面用假 `<head>` 把 CSP 挤出真实文档头。
4. 桥接脚本拦截用户点击的链接、带 `download` 的链接、表单提交与 `window.open`。它只发出请求；宿主显示完整目标后才决定是否打开或下载。
5. `form-action 'none'` 与缺少 `allow-forms` 双重阻止原生表单提交。下载缺少 `allow-downloads`，弹窗缺少 `allow-popups`。表单桥接只上报字段名、值长度与 `:sensitive?`；密码、验证码、支付字段的值不进入宿主确认面板。
6. 页面内写“系统授权成功”不会改变任何真实权限；宿主只认自己的状态。

## 4 桥接 API

沙箱页面可以使用：

```js
Herdchestra.storage.get(key) / set(key, value) / remove(key) / keys() / clear()
Herdchestra.workspace.request(path, kind, mode)
Herdchestra.workspace.read(path) / list(path)
Herdchestra.external.open(url) / download(filename, data) / submit(payload)
Herdchestra.on(event, handler)
Herdchestra.storageOmitted / Herdchestra.storageTooLarge
```

`localStorage` 与 `sessionStorage` 被替换为内存 shim；app 模式下写入经带 request-id 的 `hc/storage-set` 持久化，失败会触发 `storage-error` 事件并让宿主显示提示；预览模式只保留在页面内。同步 shim 的初始值来自宿主快照；512 KiB 以上仍完整注入，只有超过 8 MiB 硬上限时宿主才显式阻止自动打开，并要求用户选择“不同步打开”。不会用空 shim 冒充没有记录。

## 5 明确限制

1. `srcdoc` 内的相对资源无法按工作区路径加载；外部资源默认被 CSP 阻止，需要用户按域名允许。
2. 脚本直接调用 `location.href` 的导航不能被桥接完全拦截；sandbox 仍阻止顶层导航与同源访问。需要打开外部页面时使用桥接入口。
3. `allow-modals` 被关闭，页面内的 `alert`/`confirm`/`prompt` 不会正常工作。
4. `unsafe-eval` 未开启，使用 `eval`/`new Function` 的页面会失败并显示运行时错误。
5. 页面请求的 `data:` 下载需要用户在确认面板再次点击；宿主不自动下载。
