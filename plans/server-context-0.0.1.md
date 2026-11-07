# Server context data plane (0.0.1)

Authority: root `AGENTS.md` §5.3, §7.4 旅程 6（扩展能力）. Scope: the two data-plane
gaps the client audit reported as BLOCKED. No client, compose, backup, or
`app/system.clj` file is changed here.

## A. Outbound context snapshot

### Interface

`hc.server.history.context/outbound-context` keeps its existing keys and now
merges the sections produced by `context-snapshot`:

```clojure
{:system {:prompt-bytes n | :unknown
          :modules [string ...]}
 :assistant {:name string | :unknown
             :revision n | :unknown}
 :instructions {:session-override? boolean
                :text-preview string | nil}   ; at most 200 code points
 :memory {:enabled? boolean
          :items [{:id string
                   :summary string            ; at most 160 code points
                   :scope scope-map
                   :version n | :unknown}]
          :archived-excluded n | :unknown}
 :worldbook {:enabled-books [book-id ...]
             :provided [{:book book-id :entry entry-id :reason keyword}]
             :hit-not-provided [{:entry entry-id :reason keyword}]}
 :instruction-cards [{:name string :scope :turn|:session :version n | :unknown}]
 :tools [{:name string :source keyword :read-write :read|:write|:read-write|:unknown
          :enabled? boolean}]
 :history {:range {:message-ids [...] :summary-ids [...] :rounds n
                   :boundary {:message-id ... :effective? boolean}
                   :first-message-id ... :last-message-id ...}
           :compressed? boolean
           :visible-count n
           :excluded-count n}
 :attachments [{:attachment-id ... :name ... :kind ...
                :pages [page ...] | :unknown
                :page-count n | :unknown}]
 :estimates {:tokens n | :unknown
             :kind :estimated | :unknown}}
```

Rules:

- The send range is `:history :range`; the whole readable history is
  `:history :visible-count`. They are never the same field.
- Memory publishes only `:summary` and `:version`; `:body` never leaves the
  server. `:version` is `1 + (count :versions)` unless the record supplies an
  explicit `:version`.
- `:archived-excluded` counts archived records from a supplied `:memories`
  map, or takes an explicit count, or stays `:unknown`.
- A worldbook entry that hit but did not fit keeps its exact `:reason`
  (`:budget`, `:cooldown`, ...).
- `:enabled-books` is used verbatim when supplied. Otherwise it is inferred
  from the materials book bindings for the current assistant (an explicit
  `:assistant-id`, else `:materials :assistant-id`), then from the books
  that participated in this turn (`:selected` plus `:omitted`), then `[]`.
  An explicit empty vector means no enabled book and is not overridden.
- A turn-only instruction card is reported with `:scope :turn`; a persisted
  session selection is reported with `:scope :session`. An absent card list
  is `[]`.
- `:read-write` is one of `:read`, `:write`, `:read-write`; any other value
  is `:unknown`, never guessed.
- Attachment pages are the selected version's explicit page numbers, sorted
  and deduplicated. A bare page count is published as `:page-count` and does
  not fabricate page numbers.
- Unknown estimates are `{:tokens :unknown :kind :unknown}`. Zero is kept
  only when the caller supplied a real zero estimate.

### Implementation

- `src/hc/server/history/context.clj`
  - new `context-snapshot` (pure projection; no materials state read, no
    model call),
  - `outbound-context` computes the range, visible/excluded counts, and the
    attachments actually sent, then merges the snapshot.
- The caller supplies `:system`, `:assistant`, `:instructions`, `:memory`,
  `:worldbook`, `:instruction`, `:tools`, `:estimates`. Sections may also be
  nested under `:materials`. Missing sections produce empty arrays or
  `:unknown`, never a fabricated value.

## B. Memory suggestion (`memory/suggest`)

### Interface

Command `:materials/memory/suggest` (session-scoped, so the existing
`session-namespaces` entry already routes it; `commands.clj` is untouched).

Payload:

```clojure
{:kind :materials/memory/suggest
 :session-id string
 :messages [{:message-id string :role keyword :content string} ...] ; optional
 :source-range {:recent-rounds n} | {:message-ids [...]}   ; default 10 rounds
 :categories [:identity-background :working-style
              :expression-preference :long-term-requirement] ; optional subset
 :scope :global | {:kind :assistant :assistant-id ...} | {:kind :project ...}
 :model-binding {:role :main|:task :connection ... :model ...} ; default :task
 :timeout-ms n                                               ; optional
 :max-candidates n}                                          ; default 20
```

`:messages` is optional. When it is absent, the service reads the session
record's `:message-order` and `:messages`, skips deleted/folded/nudge
messages, and uses each message's current version. `:source-range` then
keeps the last N user rounds; tool traffic adds no round. An explicit
non-empty `:messages` vector overrides the session read; an empty vector is
treated as absent so a client without a message projection still gets the
server-side read.
`:max-transcript-chars` bounds the prompt and the receipt reports how many
messages were dropped as `:dropped-message-count`.

Receipt result:

```clojure
{:candidates [{:id string
               :text string                 ; at most 2000 code points
               :category keyword
               :scope scope-map
               :status :pending-review      ; persisted domain state is :candidate
               :source {:message-id string
                        :span [start end] | :unknown
                        :excerpt string}      ; bounded source excerpt
               :confidence number | :unknown
               :assumption? boolean
               :truncated? true}]           ; only when truncated
 :skipped n
 :skipped-reasons {:sensitive n :archived n :deleted n
                   :already-rejected n :already-active n
                   :already-candidate n :refused n
                   :empty n :category-filtered n}
 :included-message-ids [...]                 ; extra, for review evidence
 :dropped-message-count n                    ; character-budget evidence
 :attempts 1|2                               ; one repair attempt at most
 :truncated? boolean                         ; candidate list over the limit
 :dropped-candidates n}                      ; over-limit candidates not reviewed
```

Parse failure (after the one repair attempt):

```clojure
{:ok? false
 :status :parse-failed
 :remedy :retry
 :attempts 2
 :error {:code :MEMORY_SUGGEST_RESPONSE_INVALID
         :message "..."
         :details {:parse-error "reader message with position"
                   :parse-error-kind :unreadable
                   :raw-excerpt "bounded 500-code-point fragment"
                   :redacted? true}}}   ; raw-excerpt omitted when sensitive
```

Service entry: `hc.server.materials.session/suggest-memories!`. It accepts
an optional explicit `:model-port` (tests, or an Owner-side injection) and
otherwise uses the port passed to `session/service`.

### Behavior

- The `:memory-suggest?` switch gates generation; the default is closed.
- One initial `hc.server.model-port.contract/ModelPort` attempt runs, plus at
  most one repair attempt when the first response cannot be parsed. The
  receiver consumes `:chunk`/`:completed`/`:failed`/`:cancelled`; there is no
  AiServices loop and no tool round-trip.
- The model attempt runs outside the service lock. Only review and
  persistence run inside the store transaction.
- Candidates are stored as `:candidate`. Approval uses the existing
  `:materials/memory/approve`; rejection uses `:materials/memory/reject`.
- Sensitive content (credential patterns, bearer tokens, known key prefixes,
  private keys, Luhn-valid 13–19 digit account numbers) is filtered before
  persistence and counted under `:sensitive`.
- An assumption stays `:assumption? true`; approval does not promote it to
  fact.
- Bodies longer than 2000 code points are truncated and marked
  `:truncated? true`.
- Duplicate review compares category+scope and then either the exact body
  hash or the body folded to lowercase with whitespace and common
  punctuation removed. Symbols such as `+`, `#`, `%`, `@`, and `&` stay
  significant. A punctuation-only or spacing-only difference is suppressed
  as `:already-candidate`, `:already-active`, `:already-rejected`,
  `:archived`, or `:deleted`. An archived record is never revived by a new
  candidate.
- A run that skips every candidate commits no revision and reports a replay.
- Accepted response shapes: a bare JSON array, `{:candidates [...]}`, and
  either shape wrapped in a Markdown code fence or surrounded by prose.
- A malformed first response triggers one repair request carrying the
  previous output (bounded to 4000 code points) and the reader's error
  message. A successful repair reports `:attempts 2`. A failed repair returns
  `:status :parse-failed`, `:remedy :retry`, `:attempts 2`, and
  `:MEMORY_SUGGEST_RESPONSE_INVALID`; it never throws and never creates a
  candidate.
- A parse error carries `:parse-error` (reader message with position when
  available) and a 500-code-point `:raw-excerpt`. The excerpt is omitted and
  marked `:redacted? true` when it contains sensitive data.
- At most `:max-candidates` (default 20) normalized candidates are reviewed.
  Over-limit candidates are not silently discarded: the receipt reports
  `:truncated? true` and `:dropped-candidates n`.

### Implementation

- `src/hc/server/materials/memory_suggest.clj` (new): `messages-from-session`,
  `prepare`, `complete`, `port-completer`, `parse`, `repair-prepared`,
  `review`, `review-parsed`, `generate`.
- `src/hc/server/materials/session.clj`: optional `model-port` on `Service`,
  `suggest-memories!`, the `:materials/memory/suggest` command branch, and
  the receipt summary keys.

## Test counts

Required focused run:

```
clojure -M:test:server -n hc.server.history.context-test \
  -n hc.server.materials.memory-suggest-test \
  -n hc.server.materials.session-test
Ran 45 tests containing 260 assertions.
0 failures, 0 errors.
EXIT=0
```

After the parse-robustness increment:

```
Ran 49 tests containing 291 assertions.
0 failures, 0 errors.
EXIT=0
```

After the E2E binding/readiness fixes:

```
Ran 57 tests containing 324 assertions.
0 failures, 0 errors.
EXIT=0
```

After the outbound-snapshot verification (history, materials session/suggest,
worldbook, instruction):

```
Ran 97 tests containing 711 assertions.
0 failures, 0 errors.
EXIT=0
```

After adding the collection sources:

```
Ran 102 tests containing 724 assertions.
0 failures, 0 errors.
EXIT=0
```

After adding the read-only outbound views:

```
Ran 105 tests containing 747 assertions.
0 failures, 0 errors.
EXIT=0
```

Full suite (`clojure -X:test:server`), after the E2E binding/readiness fixes:

```
Ran 3588 tests containing 23160 assertions.
2 failures, 0 errors.
EXIT=1
```

Both failures are in `hc.server.app.restart-smoke-test` and are caused by a
syntax error in `hc.compose.owner.guards` (line 942, unmatched `}`), a file
owned by another worker. The child JVM cannot load the application, so the
restart smoke cannot run. Both new namespaces and
`hc.server.materials.session-test` ran with no failures in the same suite.

An earlier run reported 3583 tests / 23135 assertions with 0 failures and
0 errors, before that compose file changed.

## 采集来源补齐（2026-11-05）

- `:system`：`history/context/prompt-modules-snapshot` 接受
  `{name text}`、`[{:name :text}]` 或 `[name ...]`。显式 `:prompt-bytes`
  优先；否则从 `:text` 或模块文本拼接计算 UTF-8 字节。空文本模块不列出，
  name-only 模块列出；无文本且无字节数时 `:unknown`。
- `:assistant`：`assistant-snapshot` 从实体取 `:name`/`:display-name` 与
  `:revision`/`:v`/`:updated-seq`；无实体或名称为 `:unknown`。
- `:tools`：`tools-snapshot` 生成 `{:name :source :read-write :enabled?}`。
  读写性质依次取显式 `:read-write`、`:read-only?`、annotations
  (`:readOnlyHint`/`:destructiveHint`)、capability set，最后按名称保守回退；
  未知保持 `:unknown`。`source` 取 `:source`/`:origin`/`:kind`。
- `:estimates`：`estimates-snapshot` 按 4 字符/token；显式 tokens 优先；
  正字符数至少 1 token；空或缺失为 `:unknown`，不写 0。
- `:instructions`：预览截断 200 码点；命中 `memory/sensitive-kinds` 时
  `:text-preview nil` 且 `:redacted? true`。

这些函数已由 `context-snapshot` 使用；`system.clj` 的接线由 server-audit
完成。当前真实 app 仍未传 opts，因此上面的 E2E 表仍是旧行为。

## 只读出站视图（2026-11-05）

`session/effective-memories-read`、`worldbook-selection-read`、
`instruction-cards-read` 是 server-audit 构造 outbound 快照的只读入口：

- 只调用 `store/load-state`，不 `transact!`、不 `initialize!`、不
  `state/prepare`；store revision 不变；空库返回空集合，不自动初始化。
- `effective-memories-read` → `{:memories [{:id :summary :scope :version}]
  :archived-excluded n}`；summary 截断 160 码点，正文不出站。
- `worldbook-selection-read` → `{:enabled-books [...] :provided
  [{:book :entry :reason}] :hit-not-provided [{:entry :reason}]}`；使用
  `worldbook/preview-turn`，不推进 turn/sticky/cooldown/delay；真正消费
  用户轮次只能用 `context-for-turn!`。
- `instruction-cards-read` → `[{:name :scope :version}]`；staged `:turn` 与
  会话 `:session` 合并，同一卡以 `:turn` 出现一次。
- `tools-read` 跳过：工具面在 `app/prompts` 与模型请求，不在 materials；
  使用 `history/context/tools-snapshot` 投影角色工具列表。
- 测试：`read-views-do-not-change-revision-or-advance-counters`、
  `worldbook-selection-read-keeps-hit-not-provided-reason`、
  `read-views-empty-library-return-empty-collections`；本轮定点 105 tests /
  747 assertions / 0 failures / 0 errors / EXIT=0。

## Residual gaps (NOT COVERED)

1. **No model picker for memory suggestions.** The client still sends
   `:model-binding {:role :task}`. The service falls back from an unavailable
   task/utility role to the main model, but there is no UI to choose a
   non-default model for one suggestion run. `system.clj` now injects the
   live model-port provider, so the former "no model port" gap is closed.
2. **One repair attempt only.** There is no provider-specific JSON mode, no
   multi-step self-correction, and no cross-model fallback; a second parse
   failure returns `:remedy :retry` to the caller.
3. **Duplicate folding is lexical, not semantic.** Exact hashes and
   whitespace/punctuation-folded bodies are compared; a paraphrase of the
   same fact is still a separate candidate.
4. **Sensitive filtering is conservative pattern matching.** It is not a
   complete DLP scanner and does not inspect binary attachments.
5. **Book inference needs binding data.** When the caller supplies only the
   turn's `:selected`/`:omitted` projection, `:enabled-books` falls back to
   those participating books. A full enabled list requires
   `:materials :books` with `:assistant-ids`, or an explicit
   `:enabled-books`.
6. **Session read uses the current version only.** The suggestion reader
   does not expand branches, compressed ranges, or superseded versions; it
   reads the session record the caller supplies.
7. **Over-limit candidates are counted, not itemized.** The receipt reports
   `:dropped-candidates n`; the dropped items are not returned for review.
8. **No client rendering.** The client audit owns the panel and button.

## 客户端消费（2026-11-05）

- **本轮上下文面板**：`conversation/view.cljs` 新增 `round-context-section`，消费 `outbound` 的 10 个分区：`:system`、`:assistant`、`:instructions`、`:memory`、`:worldbook`、`:instruction-cards`、`:tools`、`:history`、`:attachments`、`:estimates`。记忆只显示 `:summary`/`:version`；世界书分 `:provided` 与 `:hit-not-provided`（含 reason）；指令卡标 `:turn`/`:session`；工具标 `:read-write`；历史显示发送/摘要/轮次/可见/排除；附件显示页范围；`:unknown` 一律显示“未知”。无数据时显示“本轮尚未发送”。DOM 测试 `round-context-panel-shows-sections-and-unknowns` 覆盖。
- **记忆候选入口**：`materials/view.cljc` 的“整理记忆”按钮（仅在 `:memory-suggest?` 开启时可用）发送 `:materials/memory/suggest`；`materials/model.cljc` 新增 action，payload 含 `:messages []`、`:source-range {:recent-rounds 10}`、四类 `:categories`、`:scope :global`、`:model-binding {:role :task}`。回执候选由服务端持久化为 `:candidate`，现有 memory list 显示并支持 approve/reject。
- **缺口**：payload 的 `:messages` 为空现在等价于省略，服务端从 `:session` 读取最近轮次；客户端可移除空向量。`skipped-reasons`、候选的来源片段/assumption?/truncated?、`:attempts`、`:dropped-candidates` 与批量接受前的范围分布尚未渲染；需客户端继续补。

### 客户端候选接线补充（2026-11-05 第二轮）

- `:materials/memory/suggest` payload 已去掉空 `:messages`，只传 `:session-id`（由 queue 自动加）、`:source-range {:recent-rounds 10}`、四类 `:categories`、`:scope :global`、`:model-binding {:role :task}`；服务端自行读取最近轮次。
- `materials/model.cljc` 的 `apply-receipt` 把 `:candidates/:skipped/:skipped-reasons/:dropped-message-count` 存入 `:memory-suggest-result`。
- `materials/view.cljc` 新增“本次整理：候选 N · 跳过 M”汇总行、`skipped-reasons` 明细、超出预算丢弃条数；候选列表显示来源片段、`assumption?`（“假设，待确认”）、`truncated?`（“已截断至 2000 字”）。改写/改范围复用现有 `memory-select` + `memory-edit-field` + `memory-save`，尚未加候选专用按钮。
- 测试：`clojure -X:test:client :dirs '["test/hc/client"]'` → 685 tests / 4187 assertions / 0 failures。

## 真实 E2E 修复（2026-11-05）

### 1 记忆整理模型绑定

- 应用装配把 `(fn [] @model-port-ref)` 传给 materials service；设置保存后
  `apply-models!` 重建的 port 对下一次整理立即生效，不需要重启，也不把启动
  快照固化进 service。
- 绑定解析顺序：显式 `:model-binding` → 会话记录 `:model-binding`
  （`:connection-id`/`:model-entry-id`）→ 主模型。`:utility` 映射到 adapter 的
  `:task` 角色。显式 binding 也接受 `:connection-id`/`:model-entry-id`。
- 没有可用 port 或可用绑定时返回 `:MEMORY_SUGGEST_MODEL_UNCONFIGURED`，消息
  提示“先配置主模型，或为记忆整理选择模型”，details 带
  `:action :configure-model`。
- 显式 `:task`（含客户端仍发送的 `:model-binding {:role :task}`）在端口没有
  task 角色时回落一次到主模型；该回落是 preflight 重试，不计入
  `:attempts`。
- 默认模型尝试超时从 60s 提高到 180s：27B 本地模型在冷启动/首次加载时会超过
  60s，60s 会误报 `:MEMORY_SUGGEST_TIMEOUT`。

### 2 材料库 readiness

- `session/ensure-loaded`：库缺失时用 `store/initialize!` 的 CAS 初始化一次；
  并发创建者获胜后读取其结果。种子或恢复后首个快照即可 ready，首个 materials
  命令成功。
- `store/load-state` 在加载时调用 `state/normalize-loaded`，补齐旧版本 state
  缺失的顶层键（现有值优先，不伪造记录）；真正损坏的 state 仍拒绝。
- `handle-materials!` 对 registry 尚未加载的种子会话使用
  `{:session-id session-id}` 作为作用域 fallback，不再以 `:SESSION_NOT_FOUND`
  永久拒绝材料命令。
- 测试：`first-command-initializes-an-empty-store`、
  `snapshot-initializes-once-and-stays-ready`、
  `older-state-without-new-keys-loads-ready`。

### 3 真实复跑

- fresh data-dir + `qwen3.6:27b` @ `http://127.0.0.1:11434/v1`；
  `:options {:reasoning-effort "none"}`、`max-output-tokens 512`。
- 证据：`/tmp/hc-context-suggest/evidence.edn`、`summary.txt`、`run.log`。
- 结果：材料快照 `:ready? true`、revision 0；settings 命令 accepted；
  `:materials/memory/suggest` `:ok? true`、`:attempts 1`、候选 3 条、
  `:skipped 0`、耗时 159071 ms。
- 候选：Clojure 偏好（expression-preference）、重构前先写测试
  （working-style）、Clojure 文件用 tab（expression-preference）。
- 159s 说明 60s 默认会误报超时；180s 默认在本次通过，但 27B 冷启动仍接近
  上限。

## 本轮上下文快照真实 E2E（2026-11-05）

### 状态与实测

真实 app + Ollama 启动成功。materials 配置全部 accepted：active memory
（revision 2）、worldbook `wb-1` + entry `wbe-1`（revision 3–4）、
instruction `card-1`（revision 5）、staged turn card（revision 6）。
`:send-message` 返回 accepted，但 `:outcome :message-appended`、`run? false`：
当前发送路径只追加用户消息，没有创建模型 run，因此没有 run 冻结的 outbound
context。读取 session snapshot 的 `:context :outbound`
（`history/snapshot/context-view`）得到：

| 字段 | 实测 |
|---|---|
| `:system` | `{:prompt-bytes :unknown :modules []}` |
| `:assistant` | `{:name :unknown :revision :unknown}` |
| `:instructions` | `{:session-override? false :text-preview nil}` |
| `:memory` | `{:enabled? false :items [] :archived-excluded :unknown}` |
| `:worldbook` | `{:enabled-books [] :provided [] :hit-not-provided []}` |
| `:instruction-cards` | `[]` |
| `:tools` | `[]` |
| `:history` | `visible-count 3`、range 3 条、rounds 2、`compressed? false` |
| `:attachments` | `[]` |
| `:estimates` | `{:tokens :unknown :kind :unknown}` |

只有 `:history` 有值；`:estimates` 保持 `:unknown`（正确，不写 0）。其余段空。

### 静态定位（不是数据未收集，是装配未传）

- 数据已收集：materials state 有 active memory、`wb-1`/`wbe-1`、staged
  `card-1`（命令回执 revision 2–6）；缺的是把 state 投影进 outbound。
- `src/hc/server/app/system.clj:846-847`：`:outbound-context
  history/outbound-context` 只传 session（单 arity），没有传
  `:system`、`:assistant`、`:instructions`、`:memory`、`:worldbook`、
  `:instruction`、`:tools`、`:estimates`。`history/context.clj` 的数据面已经
  支持这些 opts；默认值按设计是 `:unknown`/空数组。
- `src/hc/server/history/snapshot.clj:79`：`context-view` 原先单 arity 且
  `:outbound` 只 select 旧字段；已改为双 arity（可接收 opts）并补
  `:system`/`:assistant`/`:instructions`/`:memory`/`:worldbook`/
  `:instruction-cards`/`:tools`/`:history`/`:attachments`/`:estimates`。
  `session-history` 同步改为双 arity。调用方仍需传 opts。
- `src/hc/compose/owner/handlers.clj:700-713`：真实 `:send-message` 只执行
  `mirror-history-message!` 并返回 `:outcome :message-appended`，不创建模型
  run。因此 `sessions.clj:1079` 的 run/outbound 分支没有执行，`run? false`，
  也没有 run 冻结的 `:context`。客户端只能看到 session snapshot 的
  `:outbound`。
- `:system`/`:assistant`/`:tools` 没有收集源：角色提示、助手实体、工具面
  没有进入 outbound projection。`:estimates` 没有估算器，保持 `:unknown`
  是正确的（不写 0）。

### 最小修复（system.clj，报告给所有者）

把 `:outbound-context` 从函数别名改为组装函数：

```clojure
:outbound-context
(fn [session]
  (history/outbound-context
   session
   {:system {:prompt-bytes ... :modules ...}
    :assistant {:name ... :revision ...}
    :instructions {:session-override? ... :text ...}
    :memory {:enabled? ... :provided ... :memories ...}
    :worldbook {:selected ... :omitted ...}
    :instruction {:cards ... :scope :turn}
    :tools ...}))
```

数据来源：materials service 的 `effective-memories`、`preview-turn`/已 staged
指令卡；session 的 `:assistant-id` 与角色提示；本轮请求的工具面。没有估算器
时保持 `:estimates {:tokens :unknown :kind :unknown}`，不得写 0。发送路径还需
在 append 用户消息后创建 run，或在该时点冻结 outbound projection；这属于
compose/owner 与 system 的装配，不在本 worker 所有权内。

## 协同收口：领取时冻结（compose/owner）

server-audit 在 `system.clj` 的 `start-owner!` 顶层注入
`:outbound-context-fn`；compose 在 `claim-model-request` 的 `claim-call*`
调用并冻结。此前 provider 只加到了 `ctx`，没加到 `guard-ctx`，所以
provider 从未被调用；已在 `runtime/guard-ctx` 补齐。冻结结果进入
`[:calls <call-id> :context]` / `:context-attempt-id`，并镜像到
`[:runs <call-id>#<attempt-id> :context]`；绑定 Owner attempt-id，重试复用，
下一轮重算。

真实 app 冒烟（真实 Ollama + 真实 store，`--send-command` 发送一轮后停止
读取 `call-1`）：`:context? true`，`context-attempt-id` 等于 call 的
`:attempt-id`，8 段齐全：

| 段 | 形状 | bytes |
|---|---|---:|
| system | map | 47 |
| assistant | map | 36 |
| instructions | map | 63 |
| memory | map | 49 |
| worldbook | map | 55 |
| instruction-cards | vector | 2 |
| tools | vector | 71 |
| history | map | 326 |
| estimates | map | 34 |

测试：`hc.compose.owner.runtime-test` 29/158 0/0；
`hc.compose.nudge.owner-integration-test` 32/208 0/0；owner 回归 108/594 0/0。
`hc.server.app.outbound-context-test` 1/26/0（server-audit）。
