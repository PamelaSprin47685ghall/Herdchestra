# hc.client.search.history — 全局历史搜索的状态与入口

> 权威顺序：根 `AGENTS.md` §7.4 旅程 5.1、§7.1 第 1 条 > `src/AGENTS.md` > `src/hc/client/AGENTS.md` > `src/hc/client/search/AGENTS.md` > 本文件。

## 1 边界

全局历史搜索消费服务端可重建全文索引，不拥有索引。服务端负责候选、主库回验、片段、计数与截断；客户端只投影已确认命令结果。

- `history.cljc`：纯状态与归一，可在 CLJ 中测试。
- `history_view.cljs`：React 覆盖层、快捷键、焦点、跳转；命名空间 `hc.client.search.history-view`，避免与 `history.cljc` 的命名空间冲突。
- 不修改服务器会话、消息或索引；`:history/reindex` 不接任何用户可见按钮。浏览器的重建 handler 始终拒绝。
- 命中保留 `:session-id`、`:message-id`、`:version-id`、`:snippet`、`:historical-version?`。会话缺失时只报告不可访问，绝不打开同名会话。

## 2 状态

```clojure
{:open? false
 :query ""
 :mode :history
 :status :idle|:loading|:loaded|:error
 :hits []
 :count 0
 :truncated? false
 :error nil
 :selected-index 0
 :filters {}       ; :assistant-id :status :from-ms :to-ms
                   ; :include-versions? :has-attachments?
 :index nil}       ; 只读 :history/search-status 投影
```

规则：

- `apply-result` 只保留有 `:session-id` 与 `:message-id` 的命中；`:count` 与 `:truncated?` 原样来自服务端。
- `apply-error` 清空 hits 与 count，保留错误；视图显示中文原因与重试。
- `move-selection` 到边界即停，不环绕；空结果安全。
- `set-filters` 不清空 query；单个 nil 精确移除一个条件，`clear-filters` 清空全部。
- `format-hit-context` 对当前版本输出「当前版本」、对历史版本输出「历史版本」徽标，二者不同。

## 3 命令

均为 app scope，复用 `commands/app-scoped-command!` 的连接/纪元门槛与 `track!`；纪元未发布时不发送。

```clojure
[:hc/command {:command-id .. :command-epoch ..
              :payload {:kind :history/search
                        :query "..."
                        :assistant-id "a1"          ; 可选
                        :status :active             ; 可选 keyword
                        :from-ms 1700000000000      ; 可选
                        :to-ms 1800000000000        ; 可选
                        :include-versions? true     ; 可选
                        :has-attachments? true}}]   ; 可选
[:hc/command {:command-id .. :command-epoch ..
              :payload {:kind :history/search-status}}]
```

`history_view` 注册 `sync-bridge/ensure-event-listener!`，按 `command-id` 匹配
`hc/command-receipt`。结果既接受网关包装
`{:receipt {:status :accepted :result {:status :ok :payload {..}}}}`，也接受
handler 错误 `{:status :error :error {..}}`；`:not-found` 不回退、不伪造。

## 4 入口与焦点

- 导航栏按钮 `#hc-history-search` 与 `Ctrl/Cmd+Shift+F` 打开；`Ctrl/Cmd+F` 仍聚焦会话过滤。
- `Escape` 在面板打开时先关闭面板，不进入停止生成的分支；面板内焦点循环，关闭后回到原触发点。
- `↑/↓` 移动选中命中；Enter 在关键词框内发起搜索，在结果区打开选中结果。
- 点击历史版本命中先显示确认条，确认后才发送 `:history/select-version` 并定位；命中会话不可访问时显示可读说明。
- 定位通过消息节点的 `data-message-id` 滚动；命中不在当前可见窗口时明确提示加载更早消息，不假装已经定位。

## 5 测试

`test/hc/client/search/history_test.clj` 覆盖：状态形状、`apply-result` 归一与截断、错误清空、边界选择、版本徽标、筛选保留 query 与干净清除、receipt 包装提取。视图与快捷键不在此测试中，需浏览器或人工验证。

## 6 契约完备性（0.0.3）

### (a) 公开 API

```clojure
(initial-state)                 ; => state
(open state) / (close state) / (open? state)
(set-query state query)         ; query 非字符串按空串处理
(set-filters state filters)     ; nil 值删除单个条件
(clear-filters state)           ; 保留 query
(begin-search state)            ; 清旧结果，进入 :loading
(apply-result state payload)    ; 只保留有 session-id/message-id 的 hit
(apply-error state error)       ; 清 hits/count，保留错误
(apply-index-status state payload) / (apply-index-error state error)
(move-selection state delta)    ; 边界不环绕
(format-hit-context state hit)  ; 版本/时间/assistant 展示
(receipt-command-id value) / (receipt-status value)
(receipt-payload value) / (receipt-error value)
(error-label error) / (index-status-label index)
```

返回都是纯 state/展示值；命令通过 §3 的信封发送，不在本 ns 内发送。

### (b) 失败/拒绝码

| code | 触发 |
|---|---|
| `:SEARCH_QUERY_INVALID` | 服务端拒绝空白/非字符串 query |
| `:SEARCH_LIMIT_INVALID` | 服务端拒绝非正整数 limit |
| `:SEARCH_SESSION_INVALID` | scoped 搜索的 session-id 非法 |
| `:HISTORY_SEARCH_FAILED` | 索引查询失败 |
| `:HISTORY_SEARCH_STATUS_FAILED` | 索引状态读取失败 |
| `:REINDEX_REQUIRES_TRUSTED_CALLER` | 浏览器请求重建 |
| `:connection-not-ready` / `:command-epoch-unknown` / `:transport-not-open` | 客户端连接/纪元/通道未就绪，命令未发送 |
| `:query-invalid` | 客户端本地空白 query |

未知 code 保留服务器 message，不改写成成功。

### (c) 不变量

保证：纯投影；旧查询结果不会覆盖新查询；`apply-result` 丢弃无
session/message 身份的 hit；`:count`/`:truncated?` 原样来自服务端；错误清空
确认 hits；移动选择不环绕；关闭面板不取消服务端搜索；`:history/reindex`
永不接用户按钮。

不保证：不拥有索引/搜索算法；不保证命中已在当前可见窗口（会提示加载更
早消息）；不保证 `:history/select-version` 的跳转一定成功（会话不可访问时
只说明）；不保证 app router 已装配——当前服务端未注入 `:history-search`，
命令会得到 `:HISTORY_SEARCH_UNAVAILABLE`（D-32）。

### (d) 验证

```bash
clojure -M:test:server -n hc.client.search.history-test
```

代码事实，未复测：9 tests / 74 个 `is`。视图/快捷键不在该 ns，需浏览器或
人工验证。
