# hc.server.history.index — 可重建的全局历史全文索引

> 权威顺序：根 `AGENTS.md` §7.1 第 1 条与 §7.4 旅程 5.1 > `src/AGENTS.md` > `src/hc/server/AGENTS.md` > `src/hc/server/history/AGENTS.md` > 本文件。
> 本文件只说明局部契约与实现选择，不新增产品义务。

## 1 边界

本命名空间实现根规格的“可重建派生层”：

- 全文索引是当前态的派生物，存放在独立的分区 `"search"`；删除整个分区不会丢失任何用户数据。
- 索引不保存消息正文。会话文档只保存当前已索引的版本身份与词项集合；倒排文档只保存 `(session-id, message-id)` 到版本身份的映射。
- 查询命中后必须调用调用方提供的 `read-session-fn` 回主数据库读取权威会话，重新核对会话、消息、版本与正文。索引里的词项只用于缩小候选范围，绝不作为事实来源。
- 本命名空间不修改 `storage`、不做后台调度、不启动线程。写入统一经 `storage/commit-migration!`，读取统一经 `storage/read-document` / `storage/export-state`。

## 2 文档模型

所有文档位于分区 `"search"`。

### 2.1 控制文档

```clojure
{:kind :search-index
 :id "meta"
 :doc {:schema-version 1
       :tokenizer-version 1
       :state :ready          ; :ready | :building
       :generation 3
       :updated-at 1760000000000
       :session-count 12
       :message-count 340}}
```

分区级（`session-id` 为 nil）。`generation` 必须是正整数；`schema-version`
与 `tokenizer-version` 必须和当前常量一致。`rebuild!` 先写
`:building`，清空旧派生文档，再逐个索引，最后连同真实
`:session-count`/`:message-count` 写 `:ready`。崩溃后控制文档保持
`:building`，不会谎称索引完整。旧版本的 `:rebuilding` 拼写只作为
`:building-leftover` 兼容识别。

### 2.2 会话索引文档

```clojure
{:kind :search-session
 :id "<session-id>"
 :doc {:session-id "<session-id>"
       :session-revision 12
       :assistant-id "a-1"
       :title "..."
       :status :active
       :temporary? false
       :messages {"m-1" {:version-id "m-1-v2"
                         :role :user
                         :created-at 1000
                         :terms #{"上海" "上" "海" ...}}}}}
```

会话级（`session-id` 为该会话）。文档自身也带 `:session-id`，与 `storage/erase-session-derived!` 对 `"search"` 分区的会话作用域删除规则一致。`include-history-versions?` 为 true 时，非当前版本另以 `["m-1" "m-1-v1"]` 为键保存，并标记 `:historical-version? true`；默认只保存当前选中版本。文档中没有任何正文。

### 2.3 倒排词项文档

```clojure
{:kind :search-posting
 :id "上海"
 :doc {:term "上海"
       :postings {["s-1" "m-1"] {:session-id "s-1"
                                 :message-id "m-1"
                                 :version-id "m-1-v2"
                                 :session-revision 12}}}}
```

分区级。当前版本使用 `[session-id message-id]` 键；显式索引的历史版本使用 `[session-id message-id version-id]` 键，以免覆盖当前版本。`rebuild!` 可以随时删除并重建这些文档。

## 3 分词

`tokenize` 是纯函数，输出排序去重的词元向量：

- ASCII 字母与数字组成小写词元，长度 2–200；单字母与单数字不索引。
- 汉字连续段生成单字词元与双字词元；单字查询仍可命中。bigram 只在同一连续 Han run 内产生，相邻两字即成一个 bigram，因此会跨自然词边界（例如「上海北京」产生「海北」）。本实现不引入词边界词典；这是确定的 bigram 契约。
- 其他字符只作分隔符，不生成词元。
- 索引与查询使用同一个函数，因此语言、区域设置与平台不影响结果。

不引入第三方分词器，也不把正文或语言模型输出写进索引。

## 4 写入契约

`index-session!`：

1. 会话 `:temporary? true`、`:status :deleted`、`:deleted? true`，或状态不属于 `:active`/`:archived` 时，立即调用 `remove-session!`，绝不索引。
2. 其余会话比较旧 `:search-session` 文档与新投影：被删除、折叠、编辑（`:version-id` 或词项变化）的消息，其旧 posting 立即移除；新选中版本写入当前词元。
3. 先提交 posting 与 meta（分区级），再提交会话文档（会话级）。两个提交之间崩溃时，下一次索引或删除仍能依据旧会话文档清理已知词项；极端窗口内新增词项的 posting 可能暂时失去引用，但查询的主库校验会丢弃任何陈旧命中，`rebuild!` 会回收这些残留。
4. 返回计数与 `:committed?`，不把“无需变化”误报成写入。

`index-session!` 与 `remove-session!` 在进程内对同一个 store 监视器串行，
避免多个会话同时读改写共享词项的 posting 文档而丢失条目；单次调用只覆盖
一个会话。`rebuild!` 另有私有的 per-store rebuild 锁串行化整轮重建，
但只在写 `:building` 标记、清空分区和写 `:ready` 时持有 store 监视器；
逐会话的 `index-session!` 各自短锁，可与重建交错。`search` 只读，不加这些
锁。

`remove-session!`：

- 依据会话文档的消息词表移除全部 posting，再删除会话文档；重复调用幂等。
- 如果旧 posting 因崩溃窗口失去会话文档引用，查询仍会因主库校验而返回零命中；`rebuild!` 会回收这些残留。

`rebuild!`：

- 调用方提供 `list-sessions-fn`，返回主库当前的全部会话（或其 `{:sessions [..]}` 包装）。
- 可选的第三参数会传给 `index-session!`；需要重建历史版本 posting 时传 `:include-history-versions? true`。
- 取消/停机谓词（opts）：`:cancelled?` 和 `:store-open?`，可为函数、IDeref
  或布尔值；谓词抛异常按“已取消”处理。写入标记前、每个会话
  `index-session!` 前、写 `:ready` 前都检查。谓词为真时立即返回
  `{:ok? false :cancelled? true :indexed n :generation g|nil :sessions n
  :skipped n}`，不写 `:ready`；若已越过 `:building` 标记，meta 保持
  `:building`，由下一次 `ensure-ready!` 以 `:building-leftover` 重试。
  取消粒度是一个 `index-session!`；单会话内的写入不会被中途打断。
- 清空派生文档、遍历索引、最后标记 `:ready`；临时/删除会话经同一入口被移除。
- 每次重建前先取一次 `list-sessions-fn` 验证源；清空后再取一次，以纳入
  重建窗口内已 committed 的会话（刷新失败时回退到第一次列表并报告
  `:list-refreshed? false`）。
- 逐会话若 `current-session-fn` 返回更新 revision 的权威会话，则索引该
  当前态；会话消失则跳过。`index-session!` 本身在 store 锁内比较
  `:session-revision` 与快照 revision，快照更旧时返回
  `:stale-snapshot`，不覆盖增量写入。
- 同一批输入重复重建产生相同命中集合；`generation` 每次递增。

## 5 查询契约

`search` 的候选来源只限 posting 的**交集**：查询的每个词元都必须在索引中出现，才成为候选。

对每个候选会话只调用一次 `read-session-fn`，再逐条核对：

1. 会话存在、非临时、非删除，且匹配 `:assistant-id` / `:status` 过滤。
2. 消息存在，且非 `:deleted?`、非 `:folded?`。
3. 默认 `:include-versions? false`：消息当前选中版本的 `:version-id` 必须与 posting 一致；不一致直接丢弃。
4. 显式 `:include-versions? true`：允许旧版本命中，但该版本必须仍存在于主库，并标记 `:historical-version? true`。
5. 用主库正文重新分词，查询的全部词元必须仍出现在当前（或显式允许的旧）版本正文中；否则丢弃。
6. `:from-ms` / `:to-ms` 使用主库版本时间；`:has-attachments?` 使用主库版本附件（缺省回退到消息级镜像）。
7. `snippet` 从主库正文实时截取第一个命中词前后各 60 个字符。

排序为时间倒序，再按会话、消息、版本 ID 稳定排序；`:limit` 默认 50，上限 200。返回值同时报告 `:total` 与 `:stale-dropped`，便于调用方区分“没有命中”和“候选被主库核对淘汰”。

## 5.1 健康与启动自愈

```clojure
(index-health store)
;; => {:state :ready|:building|:missing|:incompatible|:corrupt
;;     :generation g|nil
;;     :session-count n :message-count n
;;     :built-at ms|nil
;;     :needs-rebuild? bool
;;     :reason kw|nil}

(ensure-ready! store {:list-sessions-fn f :force? false})
;; => {:ok? true :action :none|:rebuilt :generation g :health {...}}
;;  | {:ok? false :error {:code ... :message ...} :health {...}}
```

`index-health` 从实际的 `"search"` 分区重新统计会话/消息数，不信任 stale
meta 的计数；meta 的 `:session-count` 只用于 `:index-empty` 不一致检查。
任何读取失败返回 `:corrupt`/`:corrupt-meta`，绝不当作空索引。

必须 rebuild 的判定（保守、明确）：

| 条件 | state | reason |
|---|---|---|
| meta 缺失 | `:missing` | `:no-meta` |
| meta 缺失但派生文档仍存在 | `:missing` | `:no-meta` |
| `:state :building`（含旧 `:rebuilding`） | `:building` | `:building-leftover` |
| schema version 不一致 | `:incompatible` | `:schema-version-mismatch` |
| tokenizer version 不一致 | `:incompatible` | `:tokenizer-version-mismatch` |
| generation 空/非正 | `:corrupt` | `:generation-missing` |
| meta 计数 > 0 但实际 session 数为 0 | `:corrupt` | `:index-empty` |
| 未知 meta 形状/状态 | `:corrupt` | `:corrupt-meta` |

`:ready` 且版本一致、generation 为正时 `needs-rebuild? false`。主库有新提交
**不**触发全量重建；新鲜度属于 `index-session!` 增量钩子。

`ensure-ready!`：

- `needs-rebuild? false` 且 `:force?` 非 true：返回 `:none`；不调用
  `list-sessions-fn`，不写任何文档。
- 需要重建或显式 `:force? true`：先调用并校验 `list-sessions-fn`，然后
  `rebuild!`。成功返回 `:rebuilt`，`generation` 为重建后的代数，`health`
  为重建后的复检结果。
- `:cancelled?` / `:store-open?` 在列出会话和 `rebuild!` 之前也检查；此时
  返回 `:INDEX_REBUILD_CANCELLED`（`cancelled? true`），不调用列表、不写入。
- `list-sessions-fn` 缺失、抛异常或返回无法识别的值：返回
  `:INDEX_REBUILD_LIST_FAILED`，**不执行任何重建写入**，原索引保持可搜索；
  error `:details` 保留下层 cause。
- `rebuild!` 本身失败：返回 `:INDEX_REBUILD_FAILED`，不抛。若失败发生在
  写 `:building` 标记之后，控制文档有意保留 `:building`；下一次
  `ensure-ready!` 会以 `:building-leftover` 重试。这样不会把部分世代谎称
  为 `:ready`。若失败发生在标记之前，原索引保持原状。error `:details`
  保留下层 cause。

`:INDEX_REBUILD_LIST_FAILED` / `:INDEX_REBUILD_FAILED` 的 `:details` 形状：
`:cause` 取下层 `:code` 或 `:error`（缺失时为 `:unknown`）；
`:cause-details` 为去掉 `:error`、`:code`、`:storage/expected?` 后的
ex-data（为空时给 `{:class "<throwable class>"}`）。顶层 `:code`/
`:message`/`:ok?` 不变。

## 6 导出与备份

`hc.server.storage.export/derived-partitions` 登记 `"search"`。`export/validate` 与 `export/restore-ops` 默认移除该分区：

- 备份的 `neutral-state` 只复制业务类别，从不携带原始 search 分区；
- 整库恢复不复制旧索引，恢复后由调用方 `rebuild!`；
- 即使调用方把 `export-state` 的原始包直接交给 `restore-state!`，验证阶段也会丢掉 `"search"`，不会复活旧一代词项。

## 7 测试

`test/hc/server/history/index_test.clj` 使用真实临时 Datalevin 库，覆盖：

- 中英文分词、排序确定性、大小写与数字长度规则；
- 命中与 snippet 来自主库；
- 编辑消息后旧词立即零命中；
- 折叠/删除消息后立即零命中；
- 主库版本漂移但索引未更新时，默认查询拦截陈旧命中；显式 `:include-versions? true` 才允许历史版本；
- 临时/已删除会话不进入索引，状态变为删除后旧词立即零命中；
- `remove-session!` 清空 posting；
- `rebuild!` 清理已删除会话、重复重建命中一致、generation 递增；
- `rebuild!` 并发交错：后写增量胜出、旧快照 `:stale-snapshot`、重建失败保留
  已写 posting、并发重建 generation 唯一单调、取消在下一会话前停止且不写
  `:ready`、`store-open? false` 零写入；
- `ensure-ready!` 的 `:INDEX_REBUILD_LIST_FAILED` / `:INDEX_REBUILD_FAILED`
  保留下层 `:details :cause`；
- `export/validate` 与 `restore-ops` 排除 `"search"`；
- `defspec` 属性验证编辑后的旧词在任意修订与消息组合下都不复活。

## 8 契约完备性（0.0.3）

### (a) 公开 API

```clojure
(tokenize text)                         ; => sorted distinct vector of terms
(index-meta index)                      ; => control doc | nil
(index-session! index session-doc)
(index-session! index session-doc {:include-history-versions? bool})
;; => {:ok? true :indexed? bool :skipped? bool :reason ..
;;     :postings-put n :postings-deleted n :committed? bool}
(remove-session! index session-id)
;; => {:ok? true :removed? bool :postings-put n :postings-deleted n ...}
(search index opts read-session-fn)
;; => {:ok? true :query .. :terms [..] :hits [..] :count n :total n
;;     :limit n :stale-dropped n}
(index-health store)
;; => {:state .. :generation .. :session-count .. :message-count ..
;;     :built-at .. :needs-rebuild? .. :reason ..}
(ensure-ready! store {:list-sessions-fn f :force? bool})
;; => {:ok? true :action :none|:rebuilt :generation g :health {..}}
;;  | {:ok? false :error {:code :INDEX_REBUILD_LIST_FAILED
;;                        | :INDEX_REBUILD_FAILED :message .. :details {..}}
;;     :health {..}}
(rebuild! index list-sessions-fn)
(rebuild! index list-sessions-fn opts)
;; opts: {:include-history-versions? bool
;;        :current-session-fn f
;;        :cancelled? (fn [] bool) | IDeref | bool
;;        :store-open? (fn [] bool) | IDeref | bool}
;; => {:ok? true :generation n :state :ready :sessions n :indexed n
;;     :skipped n :cleared n :postings-put n :postings-deleted n}
;;  | {:ok? false :cancelled? true :indexed n :generation g|nil
;;     :sessions n :skipped n}
```

`index` 可以是裸 store 或 `{:store store}`；`search` 的 `opts` 见 §5。

### (b) 失败/拒绝码

本命名空间不在成功/失败 map 里使用稳定 `:code`；参数/形状错误抛
`ex-info`，带 `:search/error true` 与可读 `:message`/`ex-data`（non-blank
session ID、session map、`read-session-fn`、`list-sessions-fn` 等）。存储层
异常原样透传。命令适配层在边界映射为
`:HISTORY_SEARCH_FAILED`/`:HISTORY_SEARCH_STATUS_FAILED`/
`:HISTORY_REINDEX_FAILED`（见 `app/search_commands/AGENTS.md`）。
`ensure-ready!` 自身把重建失败收敛为 `:INDEX_REBUILD_LIST_FAILED` 或
`:INDEX_REBUILD_FAILED`，不向启动装配抛异常。

### (c) 不变量

保证：不保存正文，只保存版本身份与词项；每个查询候选都回主库核对；
临时/删除会话立即移除；重建先写 `:building`，完成才写 `:ready`；
`"search"` 不进入备份/恢复；同一会话写入在 store 监视器下串行。
`index-health` 在实际分区为空而 meta 仍声称有会话时保守要求重建；`ensure-ready!`
健康时零写入，重建前先验证会话列表，列表失败不改索引。

不保证：不保证跨会话事务或 rebuild 期间搜索一致（调用方必须让 writer
quiescent）；不保证词项是语义分词（当前是确定性 ASCII/汉字规则）；不保证
索引命中即事实（必须主库回验）；不保证编辑/删除后旧 posting 零残留
（查询会丢弃，`rebuild!` 回收）；重建中途失败可能留下 `:building` 残留，
由下一次 `ensure-ready!` 重试，不伪装为完整索引。

### (d) 验证

```bash
clojure -M:test:server -n hc.server.history.index-test
```

代码事实：新增 health/ensure-ready 测试覆盖空库、ready 零写入、building/
schema/tokenizer/generation 损坏、meta/实际计数不一致、列表失败保留旧索引、
force 重建。当前文件规模（2026-11-09 写时，仍在变动）：21 deftest +
3 defspec（24 个测试入口）/ 213 个 `is`；实际 assertion 以运行报告为准。
