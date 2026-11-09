# steward_model — Steward / Meditator model adapter

Local specification for `src/hc/server/app/steward_model`.  Authority:
root `AGENTS.md` §2.2, §5.4, §6.3 and `src/AGENTS.md`.

## Purpose

Turn the two Steward runtime effects into bounded model calls on the existing
low-level model port:

```clojure
{:type :model/meditate :role :meditator ...}
{:type :model/decide   :role :steward   ...}
```

The public constructor is:

```clojure
(steward-model/model-fn
 {:port port
  :prompts ... :roles ... :model-id ... :connection ...
  :evidence-reader ... :cba-reader ... :frontier-reader ...
  :max-tokens ... :max-attempts 2 :timeout-ms 60000
  :cache-size 64 :retry-policy ... :clock ...})
```

It returns `(fn [effect])` matching the runtime contract:

```clojure
{:ok? true  :value result :usage usage}
{:ok? false :error {...} :usage usage}
```

The adapter never creates an HTTP client and never uses LangChain4j
`AiServices`.  It only constructs a request snapshot, starts one attempt, and
parses the final text.

## Port shapes

Two injected port shapes are accepted:

1. **Gateway** — `{:start! (fn [request deliver!] handle) :cancel! (fn [handle])}`.
   The request uses the semantic snapshot.  The gateway runs the normal
   `hc.server.app.prompts/decorate-request` prompt and program-tool
   decoration.  This is the normal application path.
2. **Raw ModelPort** — `hc.server.model-port.contract/ModelPort`.  A raw port
   does not decorate.  The adapter sends a provider snapshot: `:role` becomes
   `:model-role` (normally `:main`) and `:program-role`/`:model-role` are
   removed before the provider request validator sees them.  The snapshot
   keeps exactly the derived capability tool face; a raw port does not get
   the canonical `clj-steward` program tool.  Use a gateway port when the
   standard prompt/program-tool decoration is required.

Gateway detection is structural: a map with callable `:start!` and `:cancel!`.
The adapter does not guess a raw port's private role table.

## Snapshot contract

Every semantic snapshot carries:

```clojure
{:role         :meditator | :steward
 :program-role :clj-meditator | :clj-steward
 :model-role   :main
 :messages     [{:role :system ...} {:role :user ...}]
 :tools        [] for the Meditator
               one spec per :clj-steward capability for the Steward
 :stream?      false
 ;; :model, :connection, :max-tokens when configured
 }
```

`program-role` is required by `decorate-request`.  Without it the gateway
adds neither the role prompt nor the program tool.

Steward tools are derived from
`hc.server.program.capability/roles` (`:clj-steward` capabilities).  A tool
name is the capability name, so a capability-set change does not require a
second hard-coded list:

```clojure
:clj-steward -> #{:cat :stat :ls :where :whereAll :park :unpark
                  :fork :join :fleet-state :assume}
```

The Meditator has no tool face: `:tools []`.

The role system message starts with `[[hc-role-prompt]]`, the marker
`decorate-request` recognizes.  This prevents a second role prompt when the
gateway decorates the snapshot.  The adapter still builds the prompt itself so
a raw port (no decoration) also receives it.

The marker text has one owner: `hc.server.app.prompts/role-prompt-marker`.
`steward_model` writes `(str prompts/role-prompt-marker "\n" prompt ...)` and
never a local literal; the test compares the snapshot's system-message prefix
against that public constant.

## System prompts

* Meditator: `hc.server.app.prompts/meditator-prompt` (`<Meditation>`).
* Steward: `hc.server.app.prompts/steward-prompt` (`<Stewardship>`).

The constructor may override a prompt with `:prompts`:

```clojure
{:prompts {:meditator "..." :steward "..."}}
```

A short suffix repeats the output contract: exactly one EDN map, no prose, no
code fence, no program execution.  The prompt is already marked, so a gateway
does not add a second copy.

## User payload

The user message is exactly `(hc.core.ufss/write-str payload)`.  Never
`pr-str`; never append prose after the EDN.

Meditate:

```clojure
{:task :meditate :role :meditator :program-role :clj-meditator
 :prompt-version 1
 :evidence [...] :evidence-ids [...] :evidence-count n :evidence-seq n
 :cba {...} :frontier {...}
 :output-shape {:revision {...}}}
```

Decide:

```clojure
{:task :decide :role :steward :program-role :clj-steward
 :prompt-version 1
 :evidence [...] :evidence-ids [...]
 :cba {...} :frontier {...} :candidates-hint {...}
 :candidate-shape {...}
 :output-shape {:request-id "..." :candidates [...] :policy {...}?}}
```

Readers are optional and receive the effect map:

```clojure
:evidence-reader (fn [effect]) -> [evidence ...]
:cba-reader      (fn [effect]) -> cba map
:frontier-reader (fn [effect]) -> frontier map
```

A reader may return `{:ok? true :value ...}` / `{:ok? false :error ...}`.
For decide, the effect's own `:cba`/`:frontier` win.  Without an evidence
reader, evidence summaries already cited by the CBA are used.  Evidence is
bounded to the requested ids and at most 64 records.  Each evidence record
must carry `:evidence-id`; records for other ids are dropped.

## Parsing and shapes

Model text is read with:

```clojure
(ufss/read-str-safe text {:json true})
```

`read-str-safe` enforces exactly one form; trailing data is a failure.  JSON
conveniences (`null`, `"key": value`, `\/`) are enabled, and JSON string keys
are keywordized before shape validation.

Meditate result:

```clojure
{:revision
 {:summary "..." :frontier-of-thought "..." :confidence 0..1
  :assumptions [...] :claims [...] :questions [...]}}
```

Only `:revision` is required at the top level and it must be a map.  Optional
fields are checked for type when present; the core `revise-best-answer`
enforces the deeper evidence/claim discipline.

Decide result:

```clojure
{:request-id "..." :candidates [{:candidate-id ... :title ... :goal ...
                                 :consequence ... :evidence ...
                                 :reversibility ... :dependency ...
                                 :opportunity ... :demand ...
                                 :evidence-ids [...] ...}]
 :policy {...}?}
```

The compact candidate shape is normalized to the core candidate fields:

| model field | core field |
|---|---|
| `:evidence` (number) | `:evidence-strength` |
| `:dependency` | `:dependency-cost` |
| `:opportunity` | `:opportunity-cost` |
| `:goal` | `:summary` when no `:summary` |

Missing `:evidence-ids` defaults to the CBA evidence ids.  The decision gets
the effect's `:frontier-id`; a model-supplied id cannot move it.  A missing or
blank `:request-id` becomes `model/<frontier-id>`.

Errors:

* `:STEWARD_MODEL_UNPARSEABLE` — not exactly one EDN/JSON map.  Details carry
  the raw text truncated to 2000 characters.
* `:STEWARD_MODEL_INVALID_SHAPE` — missing or unexpected top-level keys, or a
  candidate field that does not match `:candidate-shape`.
* `:STEWARD_MODEL_READER_FAILED` — a host reader refused or returned garbage.
* `:STEWARD_MODEL_PROMPT_MISSING` — no prompt for the effect role.

Unparseable text is never treated as success.

## Retry budget

`hc.compose.retry.policy` provides the backoff clock and caps.
`hc.compose.retry.error` classifies the failed event.

* `:max-attempts` default `2`, including the first.
* `:timeout-ms` default `60000`, a total deadline for all attempts.
* Retries only for `:transient` (429, 5xx, timeout, network).  Backoff is
  capped by the remaining budget.
* Authentication, permission, missing model, invalid request, and context
  overflow are permanent: no retry.
* A timeout returns `:STEWARD_MODEL_TIMEOUT`.
* Exhausted transient retries return `:STEWARD_MODEL_RETRY_EXHAUSTED`.
* `:retry-policy` may override base/max delay and jitter; tests set
  `:base-delay-ms 0 :max-delay-ms 0 :jitter-ms 0`.

A raw port failure that throws is classified from its ex-data, so a thrown
network/timeout error can still retry.

## Usage

Every result carries:

```clojure
{:prompt-tokens n|:unknown
 :completion-tokens n|:unknown
 :source :provider|:unknown}
```

The port's report-shaped usage is flattened.  An unknown token count stays
`:unknown`; the adapter never writes `0` for missing usage.  Parse/shape
failures keep the usage of the completed model call.

Usage rules:

* A missing or nil token field is `:unknown`, never `0`, `nil`, or `"0"`.
  A provider-confirmed `0` remains a real `0`.
* `:source` is only `:provider` or `:unknown`.  Reported token provenance and
  a flat legacy usage map count as provider; estimated, missing, or unknown
  provenance is `:unknown`.  The adapter never emits `:estimated`.
* A timeout, reader failure, or pre-call rejection has unknown usage.  A
  completed call that fails parsing/shape keeps that call's usage.
* Retries report the final attempt's usage.  A successful final attempt with
  no usage is unknown; failed-attempt usage is not summed.  An exception path
  may carry the last failed attempt's usage map, but no token arithmetic is
  applied.
* An LRU hit returns the original successful envelope unchanged, including
  its usage.  It makes no model call and adds no tokens.

## Idempotence

`effect-fingerprint` is public:

```clojure
[:model/meditate role evidence-seq evidence-count evidence-ids prompt-version]
[:model/decide   role frontier-id request-id prompt-version]
```

This is a **model-only** LRU key, not a general effect fingerprint.  It
accepts only `:model/meditate` and `:model/decide`.  Any other `:type` throws
`ex-info` with `:code :EFFECT_OUTSIDE_MODEL_DOMAIN` and
`:details {:type t :allowed #{:model/meditate :model/decide}}`; it never
returns `[:unknown t]`.  General host de-duplication belongs to
`hc.compose.steward.loop/effect-fingerprint`.

A small LRU (`:cache-size`, default 64) stores **successful** result envelopes
only.  A repeated fingerprint returns the cached envelope without calling the
port.  Failures are not cached: the runtime releases a failed effect so a
later tick can retry it.

## Tests

`test/hc/server/app/steward_model_test.clj` uses a fake raw ModelPort and a
fake gateway.  It covers:

* valid meditate/decide EDN;
* bare JSON and escaped JSON strings;
* two forms rejected;
* missing `:revision`/`:candidates`, unexpected keys, invalid candidate fields;
* model-only `effect-fingerprint` domain guard and unchanged keys;
* 429 retry success; 401, missing model, and 413 no retry;
* bounded timeout;
* repeated fingerprint with one port call;
* usage `:unknown` versus reported usage;
* prompt/system assertions and strict user EDN;
* reader enrichment and refusal;
* semantic snapshot fields `:program-role`, `:role`, `:model-role`, and
  `:tools` (empty for Meditator, capability set for Steward).

Run:

```bash
clojure -X:test :nses '[hc.server.app.steward-model-test]'
```

## Limitations

* The adapter does not execute tool calls.  It consumes the final text.  The
  snapshot tool face is for the gateway/program surface; the model is told to
  answer with one EDN map.
* A gateway port is required for standard prompt/tool decoration.  A raw
  ModelPort gets the provider snapshot and the adapter's own prompt, but not
  the canonical program tool.
* Readers are host state accessors.  The adapter does not read the Steward
  store or the Sphinx state itself.
* The LRU is per `model-fn` instance, not global across processes.

## 0.0.3 契约完备性补注

### (a) API

```clojure
(model-fn opts)
;; opts 见上文；=> (fn [effect] -> result)
;; 成功: {:ok? true :value <parsed EDN map> :usage {...}}
;;       （cache hit 返回同一形状；attempts 只在部分失败 map 上出现）
;; 失败: {:ok? false :usage {...} :attempts n :error {:code .. :message .. :details? ..}}
(effect-fingerprint effect) ; => vector
```

### (b) 失败码

| code | 触发 |
|---|---|
| `:STEWARD_MODEL_INVALID_OPTIONS` | `model-fn` 缺少 ModelPort/gateway、retry policy 或数值选项非法（构造时抛 ex-info） |
| `:STEWARD_MODEL_INVALID_EFFECT` | effect 不是 map，或 role 与 task 不匹配 |
| `:STEWARD_MODEL_UNKNOWN_EFFECT` | effect type 不是 `:model/meditate`/`:model/decide` |
| `:STEWARD_MODEL_PROMPT_VERSION` | effect 的 prompt-version 与适配器不一致 |
| `:STEWARD_MODEL_PROMPT_MISSING` | role 没有配置系统提示 |
| `:STEWARD_MODEL_INVALID_SHAPE` | 模型返回的 EDN map 不符合输出 schema |
| `:STEWARD_MODEL_UNPARSEABLE` | 模型文本不是恰好一个 EDN map |
| `:STEWARD_MODEL_READER_FAILED` | evidence/cba/frontier reader 拒绝或抛异常 |
| `:STEWARD_MODEL_TIMEOUT` | 总 deadline 用尽 |
| `:STEWARD_MODEL_CANCELLED` | 调用或重试等待被取消/中断 |
| `:STEWARD_MODEL_CALL_FAILED` | 模型端口返回失败/未知终态，或不可分类异常 |
| `:STEWARD_MODEL_INVALID_REQUEST` / `:STEWARD_MODEL_PORT_INVALID` | 端口拒绝请求/快照，或端口/receiver 非法 |
| `:STEWARD_MODEL_CONTEXT_TOO_LONG` | 上游类别为 context 超限 |
| `:STEWARD_MODEL_AUTH` | permanent auth 或 HTTP 401/403 |
| `:STEWARD_MODEL_NOT_FOUND` | permanent model-not-found/invalid-model |
| `:STEWARD_MODEL_RETRY_EXHAUSTED` | transient 故障且重试预算耗尽 |
| `:STEWARD_MODEL_INTERNAL` | 适配器自身未分类异常 |

### (c) 不变量

保证：每次调用固定 prompt/snapshot/role；模型文本只按一个 EDN map 解析；
成功才写 LRU；同一 `effect-fingerprint` 的成功结果可复用；重试保留同一
call 身份并走 bounded backoff；usage 未知时不伪造 0；不执行工具。

不保证：不保证模型输出语义正确；不保证 reader 返回的 host state 完整；不
保证跨进程缓存；不保证所有供应商都支持 tool/prompt 装饰（原始 ModelPort
只有适配器自带 prompt）；不保证取消能立刻终止已发出的 HTTP 请求。

### (d) 验证

```bash
clojure -M:test:server -n hc.server.app.steward-model-test
```

代码事实，未复测（2026-11-09 写时文件，仍在变动）：17 tests / 115 个 `is`。
