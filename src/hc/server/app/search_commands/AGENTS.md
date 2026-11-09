# hc.server.app.search-commands — 历史全文索引的命令适配面

> 权威顺序：根 `AGENTS.md` §7.1 第 1 条、§7.4 旅程 5.1 > `src/AGENTS.md` > `src/hc/server/AGENTS.md` > 本文件。
> 本文件只描述这一组命令适配器的局部契约，不新增产品义务。

## 1 边界

本命名空间把 `hc.server.history.index` 包装成 app 命令 handler：

- 只使用注入的 `:store`、`:read-session-fn`、`:list-sessions-fn`；不 require `hc.compose.*`，不持有全局状态，不启动线程。
- 不实现搜索算法；候选、主库回验、snippet 与 rebuild 都属于 `hc.server.history.index`。
- `hc.server.app.commands` 已 require 本 ns，并在 dispatch 中构造/使用
  handler map；**系统装配尚未把 `:history-search` opts 传进 router**，因此
  运行时命令仍返回 `:HISTORY_SEARCH_UNAVAILABLE`（见 §7、D-32）。
- 所有失败返回数据，不向调用方抛出。

## 2 公开 API

```clojure
(search-handler opts)        ;; opts: {:store :read-session-fn :limit}
(search-status-handler opts) ;; opts: {:store}
(reindex-handler opts)       ;; opts: {:store :list-sessions-fn :allow-reindex?
                             ;;        :session-ids(保留)}
(command-kinds)              ;; #{:history/search :history/search-status
                             ;;   :history/reindex}
(handlers opts)              ;; kind -> handler
```

每个 handler 是 `(fn [payload] ...)`。payload 是普通 map；未知键被忽略。

## 3 返回形状

成功：

```clojure
{:status :ok :payload {..}}
```

失败：

```clojure
{:status :error
 :error {:code :SEARCH_QUERY_INVALID
         :message "query must be a non-blank string"
         :details {:query "   "}}}   ; details 可选
```

`failure-from-throwable` 在命令边界捕获 `Throwable`：若 ex-info 带
`:search/error`，其数据作为 details（去掉标志位）；若有 `:code` 则沿用。
其余异常使用各 handler 的 fallback code。这样索引损坏或注入函数缺失不会
击穿传输 dispatcher。

## 4 `:history/search`

payload 白名单：

```clojure
[:query :assistant-id :status :include-versions? :has-attachments?
 :from-ms :to-ms :limit :session-id]
```

- `:query` 必须是非空白字符串，否则 `:SEARCH_QUERY_INVALID`。
- `:limit` 必须是正整数；0、负数、非整数拒绝（`:SEARCH_LIMIT_INVALID`），
  超出 `index/max-limit` 时钳到上限。payload 的 `:limit` 优先于构造 opts 的
  `:limit`；两者都没有时不传 limit，由索引默认值处理。
- 只把 `:query :assistant-id :status :include-versions? :has-attachments?
  :from-ms :to-ms`（以及解析后的 `:limit`）传给 `index/search`。
  `:kind`、`:session-id`、任何未知键绝不透传。
- `:read-session-fn` 总是传给 `index/search`，主库回验不可绕过。
- `:session-id` 在本命令面做后置过滤。因为 `index/search` 没有 session 谓词，
  scoped 搜索会请求 `index/max-limit` 窗口，再按请求的 limit 截取；若窗口可能
  仍藏有该会话命中，`truncated?` 保守为 true。
- 成功 payload：

```clojure
{:mode :search
 :query "<trimmed query>"
 :hits [...]
 :count n
 :truncated? bool
 :index-generation g}
```

`:index-generation` 来自 `index/index-meta`；控制文档不存在时为 0。

## 5 `:history/search-status`

忽略 payload。返回：

```clojure
{:state :ready | :building | :missing
 :generation g
 :session-count n
 :message-count n
 :built-at ms
 :last-error nil | value}
```

- `:state`、`:generation`、`:built-at`（索引 meta 的 `:updated-at`）与
  `:last-error` 来自 `index/index-meta`；`:rebuilding` 映射为 `:building`。
- 空库/恢复后未 rebuild 时是 `:missing`，不是错误。
- `:session-count` 是 `"search"` 分区中的 `:search-session` 文档数；
  `:message-count` 是按 message ID 去重后的已索引消息数。
- 计数来自对 `"search"` 分区的只读 `storage/export-state` 扫描；不读正文。

## 6 `:history/reindex`

- 默认拒绝，错误码固定为 `:REINDEX_REQUIRES_TRUSTED_CALLER`。
- 只有构造 opts 时显式 `:allow-reindex? true` 才调用 `index/rebuild!`。
  payload 字段不能打开该开关；浏览器侧永远使用默认拒绝的 handler。
- 成功 payload：`{:generation g :session-count n}`。
- `:session-ids` 为将来增量调用保留。本 slice 只做完整 rebuild；如果把它
  当作源列表过滤，rebuild 清空整个派生分区后会删掉其他会话，因此不使用。
- `:list-sessions-fn` 必须返回带完整 `:messages` 的权威会话（或
  `{:sessions [..]}` 包装）。

## 7 在 `commands.clj` 的 dispatch 形状与 0.0.3 实际状态

`commands.clj` 已接入 handler map 与 dispatch；下列代码是当前形状的参考。
**实际缺口在系统装配**：`system.clj` 未向 router 传 `:history-search`，所以
`history-search` opts 为 nil，浏览器命令命中 `:HISTORY_SEARCH_UNAVAILABLE`。

```clojure
;; 1. require
[hc.server.app.search-commands :as history-search]

;; 2. 系统装配时构造 handler map，存到 router state 或新字段
(history-search/handlers
 {:store store
  :read-session-fn (fn [session-id]
                     (session-ports/read-session persistence session-id))
  :list-sessions-fn (fn [] full-session-list)
  :allow-reindex? false}) ; 浏览器面

;; 3. dispatch! 中，在通用 :history/* Owner 分支之前
(contains? (history-search/command-kinds) kind)
(let [handler (get history-search-handlers kind)]
  (if handler
    (reply reply-fn (handler payload))
    (reply reply-fn
           (result-error :HISTORY_SEARCH_UNAVAILABLE
                         "history search handlers are not assembled"
                         {:kind kind}))))

;; 4. 通用兜底必须留在后面
(or (contains? conversation-kinds kind)
    (= "history" (namespace kind)))
```

`scope-for` 当前把未列入 session 的命令归入 app scope，因此这三个 kind 会走
app command epoch / app receipt。可信的 rebuild 入口必须使用单独的
`:allow-reindex? true` handler map，并且只从服务器/受信程序路径调用，不能与
浏览器 handler map 共用开关。

如果 `commands.clj` 的既有 `reply` 契约要求 `:ok?`/`:receipt`，接线处负责
把本命令面的结果投影一次：

```clojure
(if (= :ok (:status result))
  {:ok? true :receipt {:status :accepted :result (:payload result)}}
  {:ok? false :error (:error result)})
```

## 8 测试

`test/hc/server/app/search_commands_test.clj` 使用真实临时 Datalevin store 与
`index/index-session!` 造数据，覆盖：

- 命中、`:count`、`:truncated?`；
- 编辑消息后旧词经命令面 0 命中；
- limit 钳到 `index/max-limit`，0/负数拒绝；
- 空白 query 拒绝；
- status 空库 `:missing`、索引后 `:ready` 与计数；
- reindex 默认拒绝、受信时 generation 递增；
- `index` 抛异常时返回结构化错误；
- 未知 payload 键不透传。

## 9 契约完备性（0.0.3）

### (a) 公开 API

`search-handler`、`search-status-handler`、`reindex-handler`、`command-kinds`、
`handlers` 的签名见 §2；每个 handler 是 `(fn [payload]) -> {:status :ok|:error ...}`。

### (b) 失败/拒绝码

| code | 触发 |
|---|---|
| `:SEARCH_QUERY_INVALID` | `:query` 缺失、非字符串或空白 |
| `:SEARCH_LIMIT_INVALID` | `:limit` 存在但不是正整数 |
| `:SEARCH_SESSION_INVALID` | scoped 搜索的 `:session-id` 不是非空字符串 |
| `:HISTORY_SEARCH_FAILED` | `index/search` 抛异常且未携带自己的 code |
| `:HISTORY_SEARCH_STATUS_FAILED` | `index/index-meta`/分区扫描抛异常 |
| `:HISTORY_REINDEX_FAILED` | `index/rebuild!` 抛异常 |
| `:REINDEX_REQUIRES_TRUSTED_CALLER` | 浏览器 handler 或未显式 `:allow-reindex? true` |
| `:HISTORY_SEARCH_UNAVAILABLE` | app router 未注入 handler map（当前装配缺口） |

### (c) 不变量

保证：浏览器 handler 永远不能重建索引；`index/search` 总是收到
`read-session-fn` 并回主库核对；payload 白名单过滤，未知键不进入索引；
scoped 搜索的 `truncated?` 保守为 true；失败在命令边界变成数据。

不保证：不实现搜索算法；不维护索引（由调用方 index/remove/rebuild）；不
保证重建期间搜索一致（rebuild 要求 writer quiescent）；不保证 app router
已接线——当前 `system.clj` 未向 router 注入 `:history-search`，浏览器命令会
得到 `:HISTORY_SEARCH_UNAVAILABLE`（D-32）。

### (d) 验证

```bash
clojure -M:test:server -n hc.server.app.search-commands-test
```

代码事实，未复测：12 tests / 68 个 `is`。
