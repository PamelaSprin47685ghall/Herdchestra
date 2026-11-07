# hc.client.miniapps — 工作区小应用与按应用隔离存储

> 权威顺序：根 `AGENTS.md` §6.6、§7.3 > `src/AGENTS.md` > `src/hc/client/AGENTS.md` > 本文件。

## 1 责任

1. 把工作区中的 HTML 文件识别为小应用，并给予稳定 `app-id`。
2. 用 IndexedDB 按 `app-id` 隔离键值数据与源版本；普通浏览器、其他小应用、其他工作区互不可见。
3. 文件删除或外部移动不自动清空数据；未关联应用保留数据，用户可重新关联、导出或清空。
4. 文件更新先保留旧源与旧数据；声明数据版本变化只提示兼容风险，不自动迁移或清空。
5. 小应用在 `hc.client.sandbox` 的 iframe 中运行；外部链接、下载、表单、模型与工作区文件访问都经用户确认。
6. 提供数据页：本地数据大小、最近使用/写入、导出、清空、文件授权范围与源版本。

## 2 文件

| 文件 | 责任 |
|---|---|
| `model.cljc` | 应用记录、内容指纹、声明数据版本、升级决策、授权范围、数据摘要/导出、外部请求、网络状态 |
| `store.cljs` | IndexedDB 适配；`apps`、`kv`、`versions` 三个 store；按 app-id 读写与清理 |
| `api.cljs` | 只读 `/api/workspace/root|list|stat|read` GET |
| `view.cljs` | 自包含 React 面板：应用列表、添加/重新关联、沙箱、数据页、文件选择器、确认面板 |

工作台挂载：`workbench/views/materials.cljs` 的 `:miniapps` 页签调用
`(react/createElement hc.client.miniapps.view/miniapps-panel)`。面板不读写 Fulcro 状态，不需要修改 app/db 查询。

## 3 身份与存储

```text
apps      keyPath app_id                  -> 应用记录
kv        keyPath [app_id key]            -> {value updated-at}
versions  keyPath [app_id version]        -> {text digest data-version saved-at}
```

- IndexedDB 的 key path 不能包含连字符，因此存储边界把 `:app-id` 转成 `app_id`；其他字段保持 kebab-case。
- 应用身份是 `app-id`，不是文件名。同一路径再次打开复用同一 app-id。列表按当前 `workspace-root` 过滤；根身份未读取时不显示任何应用。
- 同一工作区中内容相同但路径不同的文件默认是独立应用；只有**未关联**、同一 `workspace-root` 且内容指纹唯一匹配时才提示用户关联，绝不按文件名自动合并。重关联会更新 `workspace-root`，不跨工作区复用数据。
- 删除工作区文件只把记录标为 `:unlinked`；`kv` 与 `versions` 保留。IndexedDB 升级不删除旧 store；不兼容的旧 schema 显示读取错误，数据留在磁盘。
- 清空数据只删除该 `app-id` 的 `kv` 行；其他应用与普通浏览器不受影响。
- 复制工作区文件不复制数据；新路径创建新应用。

## 4 升级与恢复

- 文件内容指纹变化时，先把新源写入 `versions`，保留旧源，最多保留 3 个版本。
- 新源声明 `<meta name="herdchestra-data-version" content="N">`：
  - 高于当前数据版本 → `:unknown`，提示“无法确认兼容”，数据保留；
  - 低于当前数据版本 → `:downgrade`，提示检查记录；
  - 相同 → `:compatible`。
- 数据不会被自动迁移、清空或截断。用户可以导出 JSON、查看旧版 HTML，或把旧版恢复为本应用的**本地读取源**；工作区文件不会被改写，也不绕过受保护写入合同 $W$。
- 本地恢复把 `:restored-version` 写入应用记录；重新打开仍使用该旧源，数据继续读取同一 `app-id`。`回到当前版本` 清除恢复标记并重新读取工作区文件。
- 页面重新加载从 IndexedDB 读取同一份数据。同步 `localStorage` shim 在 **8 MiB 硬上限**内注入全部键；超过 512 KiB 只显示“数据较大”提示，不再省略数据。
- 超过 8 MiB 时**不自动打开小应用**，避免用空 `localStorage` 冒充“没有记录”。宿主显示数据大小、导出、清空和“仍然打开（不同步 localStorage）”的显式选择；选择后 `Herdchestra.storageOmitted`/`storageTooLarge` 为真，应用必须使用 `Herdchestra.storage` 读取完整数据。

## 5 文件访问授权

- 小应用只能读取用户在选择器中明确选中的工作区文件或目录。
- 授权方式：`仅本次`、`本次打开期间`、`长期关联（保存在本应用）`。
- 授权按路径保存；目录授权只匹配该目录及其子路径，不做字符串前缀误配。`:session` 授权带 `:app-id`，只对当前小应用生效；切换应用不继承。选择器确认时校验文件/目录类型与请求一致。
- 更新应用版本不会改变已有授权，也不会自动扩大范围。
- 每次请求都会校验当前授权；没有授权时打开选择器，用户取消则请求失败。
- 不提供设备目录、秘密变量或会话完全访问模式的入口。

## 6 网络与外部动作

- 默认 CSP `connect-src 'none'`；外部域名列在详情中，用户逐域名允许后才进入 CSP。
- 允许联网会重新加载小应用；页面内未保存状态可能丢失，已写入的 `localStorage`/`Herdchestra.storage` 保留。
- 离线时显示在线状态与最近写入时间；已有数据仍可读，不以空列表表示“没有记录”。
- 外链、下载、表单、模型调用都使用 `sandbox/ui` 的确认面板；模型调用没有桥接 API。
- 同步或外部上传不是默认能力，不因 `.html` 后缀开启。

## 7 测试

```bash
clojure -M:test -n hc.client.miniapps.model-test
clojure -M:test -n hc.client.sandbox.model-test
clojure -M:test -n hc.client.preview.model-test
```

覆盖：路径安全、内容指纹、声明数据版本、升级兼容、授权范围边界、合并/撤销授权、数据摘要与大快照省略、导出、网络状态、应用身份。

真实浏览器冒烟必须覆盖：

1. 创建两个 HTML 小应用，各自写入同名键，确认互不可见；
2. 重新加载后数据仍在；
3. 删除/移动文件后记录为未关联，数据仍可导出；
4. 重新关联后同一数据可读；
5. 外部链接/下载/表单请求只出现确认面板，不自动执行；
6. 未允许域名的 `fetch` 被 CSP 阻止；
7. 清空一个应用不影响另一个。
8. >512 KiB 数据仍完整注入同步 `localStorage`；>8 MiB 默认不打开并显示显式选择。
9. 本地恢复旧版后重新加载仍使用该旧源，数据保留，工作区文件未改写；`回到当前版本` 恢复当前文件。

独立烟测 harness 已于 2026-11-05 删除：`src/hc/client/miniapps/smoke.cljs`、
临时 `:smoke` build 与 `resources/public/smoke/` 旧产物都不再存在，当前没有
可复现的浏览器烟测入口。需要浏览器烟测时，先补一个可复现的 shadow-cljs
build 与脚本，再执行本节第 1–9 条。

### 7.1 当前验证状态（2026-11-03）

已在真实 Chromium（mock `/api/workspace`，静态 workbench bundle）确认：

1. 小应用页签可见，IndexedDB 数据库 `herdchestra-miniapps` 版本 2 创建出 `apps`、`kv`、`versions` 三个 store，keyPath 为合法的 `app_id`/`[app_id key]`/`[app_id version]`。
2. 直接调用编译后的 `hc.client.miniapps.store/list_apps` 会正常解析为空向量。
3. 首次发现并修复了一个 React/CLJS 互操作缺陷：把多参数 `assoc`/`update` 直接传给 React setter 时，额外参数被 setter 丢弃，状态永远不更新；现在 `miniapps.view` 与 `preview.view` 的 setter 包装器会先把额外参数应用到上一状态。列表加载另加 6 秒 `Promise.race` 超时，超时显示错误而不是无限加载。
4. Devops 复跑三个模型测试：`hc.client.sandbox.model-test` + `hc.client.preview.model-test` + `hc.client.miniapps.model-test` = 25 tests / 141 assertions，0 failures/errors。

**NOT RUN / 待 devops 复验**：浏览器中的两个应用同名键隔离、刷新后持久化、>512 KiB 完整注入、>8 MiB 阻止与显式不同步打开、本地旧版恢复、删除/移动后的未关联恢复、重新关联、导出、外部链接/下载/表单确认、CSP 阻止未允许域名、清空一个不影响另一个。真实服务端启动被另一个模块的 `restore_supervisor.clj` 符号错误阻塞；浏览器烟测改用独立 mock 工作区与静态 harness（该 harness 已于 2026-11-05 删除），这不构成对真实 Sente/服务器路径的验证。

## 8 明确限制

1. 工作区文件 API 是只读的；面板不能把旧版写回工作区，也不能重命名/移动文件。本地恢复只改变本应用读取的源，不触碰文件；如需替换工作区文件，必须走受保护提交（stamp + $W$ + dry-run），当前面板不绕过它。
2. 工作区文件选择器按目录逐层读取；不递归扫描整个工作区。
3. 目录授权只提供只读读取/列表，不提供写入。
4. 没有数据导入；只提供导出、重新关联、旧版查看与本地恢复。
5. 没有跨设备同步或外部上传；这些需要单独显式能力。
6. IndexedDB 不可用（隐私模式/配额）时面板显示失败，不伪装已保存。
7. 屏幕阅读器、移动端软键盘与所有浏览器组合未全部运行；未运行项标 `NOT RUN`。
8. 同步 `localStorage` 的硬上限为 8 MiB；超过时默认不打开应用，需用户显式选择“不同步打开”。这不是静默降级。
