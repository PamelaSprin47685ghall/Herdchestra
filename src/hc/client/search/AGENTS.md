# hc.client.search AGENTS.md — 联网搜索控件与来源卡

> 权威顺序：根 `AGENTS.md` > `src/AGENTS.md` > `src/hc/client/AGENTS.md` > 本文件。
> 支持条款：根 §5.5、§7.4 旅程 6.1（搜索）、§8.5 E2E 07/20/21。

## 1 责任

`hc.client.search` 只负责搜索选择的本地状态与来源展示：

1. **模式控件**：`关闭 / 自动 / 本轮必须搜索` 三态，默认关闭；控件旁说明当前模式含义。
2. **服务选择**：只列服务器 `:search` 快照中的独立搜索服务；停用、配置无效或凭据不可用的服务保留并显示原因，不自动改选其他服务。
3. **发送前校验**：非关闭模式必须选中可用服务；缺服务时阻止发送并显示原因，草稿与附件不动。
4. **来源卡**：显示标签/编号、标题、站点、链接、发布日期（未知写“未知”）、抓取时间（缺失也写“未知”）、摘要与内容状态；不显示全文，不把抓取时间当发布日期。来源按 canonical URL 合并，保留首次引用编号并按编号稳定排序；服务器报告的 `:omitted-source-count`/`:omitted :sources` 必须显示为“另有 N 条来源未显示”，不得静默截断。
5. **消息来源**：从消息 `:sources` 或 `:citations` 读取服务器已确认的来源，按首次引用编号展示。

本包不做：真实搜索请求、搜索服务健康轮询、查询历史、全文抓取、模型调用、搜索结果持久化。所有事实来自服务器。

## 2 文件

| 文件 | 责任 |
|---|---|
| `model.cljc` | 纯状态：模式、服务归一、选择错误、发送 payload、来源卡、消息来源 |
| `view.cljs` | `SearchControls` 与 `source-cards` React 渲染 |

## 3 状态

```clojure
{:mode :off|:auto|:required
 :service-id "brave-main"|nil
 :open? false
 :last-run nil
 :notice nil}
```

- `model/initial-state` 是 map，不是函数。
- `model/state-for db session-id` 从 `:ui/search-state` 的会话条目读取；缺省回到关闭。一个会话的模式/服务选择不泄漏到另一个会话。
- `model/services db` 从服务器 `:search :config :services` 归一；每项含 `:id :name :label :selectable? :reason :last-test`。
- `model/selection-error db state`：关闭返回 nil；否则返回第一个具体原因。
- `model/command-payload state text`：关闭返回 `{:mode :off}`；其他返回 `{:mode ... :service-id ... :queries [{:query-id "q1" :text text}]}`。调用方必须先检查 `selection-error`。
- `model/source-cards state`：最近运行来源（合并去重、稳定排序，不截断）；`model/message-sources message`：消息 `:sources`/`:citations`；`model/omitted-source-count state`：服务器省略的来源数。
- `model/run-status-label` / `run-error-label` / `must-search-failed?`：只映射服务器事实，不推断成功。

## 4 视图

`SearchControls` props：

```clojure
{:state search-state
 :services search-services
 :error selection-error-or-nil
 :disabled? (not (:synced? connection))
 :on-change! (fn [changes] ...)}
```

`on-change!` 接收 `{:mode :auto}`、`{:service-id "..."}` 或 `{:open? true}`。控件包含：

- 三态按钮，`role="radiogroup"` 与 `aria-pressed`；
- 服务 `<select>`，不可用项 `disabled` 并显示原因；
- 选择错误 `role="alert"`；
- 最近运行状态、必搜失败标记与来源卡。

`source-cards` 接受已归一来源；`message-sources` 把消息 `:citations` 的 `:citation-number` 作为 `[n]` 标签。发布日期为 nil 时显示“发布：未知”，抓取时间缺失时显示“抓取：未知”，两者分开。

## 5 Workbench 接缝

- `db.cljs`：`:ui/search-state {}`；每个会话首次使用时由 `search-model/state-for` 提供关闭初值。保留原 `:ui/search` 字符串。
- `app.cljs` Root query 增加 `:ui/search-state`。
- `mutations.cljs`：`update-search-state` 接收 `{:session-id ... :changes ...}`，把 `:changes` 合并进 `[:ui/search-state session-id]`。
- `session-page`：
  - 读取 `search-state`、`search-services`、`search-error`；
  - `:send!` 在非关闭模式且有选择错误时 `commands/show-notice!` 并停止；否则把 `(search-model/command-payload search-state text)` 作为 `:search` 附到 snapshot；
  - 在会话挂载点上方渲染 `SearchControls`。
- `commands/send-snapshot!` 必须把 snapshot 的 `:search` 原样放进 `:send-message` payload。

## 6 Conversation 接缝

- `conversation/view.cljs` 的消息来源只调用 `(search-view/source-cards (search-model/message-sources message))`。
- 若 composer 收到 `:search` props，渲染 `SearchControls`；不要同时在工作台外再渲染第二套来源卡。
- 消息 `:citations` 是服务器已分配的来源；客户端不自行编号，不把模型文本里的 `[1]` 当作已验证引用。

## 7 测试

```bash
clojure -M:test -n hc.client.search.model-test
```

React 服务端渲染 smoke：

```bash
clojure -Sdeps '{:aliases {:t {:extra-paths ["test"]}}}' \
  -M:cljs:client:t -co '{:target :nodejs
                         :output-to "target/search-ui/main.cjs"
                         :output-dir "target/search-ui"
                         :node-modules-dirs ["./node_modules"]}' \
  -m hc.client.search.ui-smoke
```

覆盖：三态与关闭 payload、显式服务选择/停用/缺凭据、日期未知、消息引用编号与排序、重复 URL 合并、来源不截断、遗漏计数、全部错误码、控件渲染与来源卡。

## 8 已知边界

1. 不保存搜索查询历史；选择只属于当前会话界面，查询随消息由服务器保存。
2. 不展示全文；全文读取与引用由服务器 `hc.server.search`/materials 负责。
3. 服务列表来自服务器快照；快照未到或没有服务时显示未选择，不伪造默认服务。
4. 最近运行来源只在服务器把运行结果交给客户端时出现；客户端不从模型文本推断来源。
