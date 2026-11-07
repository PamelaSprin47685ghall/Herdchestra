# `hc.server.model-adapter` — LangChain4j low-level provider adapter

Authority: root `AGENTS.md` §5.4, `src/AGENTS.md`, and
`src/hc/server/AGENTS.md`. This package implements the provider side of
`hc.server.model-port.contract/ModelPort`. It does not own history, Scope,
Pair Hint, retry policy, storage, or the Owner loop.

## Contract

The application builds one port and passes it to the Owner:

```clojure
(require '[hc.server.model-adapter :as adapter])

(adapter/model-configured? config)   ; structural only
(def port (adapter/create-model-port config))
(adapter/stop-port! port)            ; idempotent
(adapter/stats port)                 ; counters, optional
(adapter/await-attempt port attempt-id timeout-ms) ; optional
```

`create-model-port` returns an object satisfying
`hc.server.model-port.contract/ModelPort`:

```clojure
(start-request! [port request receiver])
(cancel-request! [port handle])
```

It throws `ex-info` with `{:error/kind :model-not-configured}` when the
configuration has no connection. It never returns a fake port and never
substitutes a test double for a real provider. A missing provider module,
invalid configuration, or failed provider construction is an explicit error.

Only `ChatModel`, `StreamingChatModel`, and `ToolSpecification` are used. There
is no `AiServices`, no `ChatMemory`, and no automatic tool loop.

## Configuration

The application owns connections and secrets. The adapter accepts:

```clojure
{:connections [{:id "local"
                :kind :openai-compatible ; :openai | :openai-compatible | :ollama | :custom
                :base-url "http://127.0.0.1:11434/v1"
                :api-key "..."           ; or :api-key-env "NAME"
                :timeout-ms 120000
                :max-retries 0           ; absent or 0; the adapter owns retry
                :headers {"X-..." "..."}
                :credentials {"c1" {:alias "default" :secret-ref "vault://..."}}
                :key-strategy :priority
                :models [{:id "qwen"
                          :model-name "qwen3"
                          :capabilities #{:tools :streaming}
                          :max-output-tokens 4096
                          :temperature 0.2
                          :options {...}}]}]
 :roles {:main {:connection "local" :model "qwen"}
         :task {:connection "local" :model "qwen"}}
 :default-role :main
 :parse-arguments :json          ; :json | :ufss | :auto | :edn | function
 :json-key-mode :keyword         ; :keyword | :string | function
 :attachment-loader (fn [attachment] {:base64 "..."}) ; or {:bytes ..}/{:url ..}
 :credential-resolver (fn [{:keys [connection]}]
                        {:ok? true :secret "..."})     ; per request
 :executor-threads 4
 :close-models? false}
```

- `:kind :openai` uses the official OpenAI endpoint unless `:base-url` is set.
  `:openai-compatible` requires `:base-url` by convention (or a
  `:default-base-url`/`:effective-base-url`/`:custom-base-url` alias).
- `:kind :oauth` and `:kind :account-login` are accepted as
  `:openai-compatible`; the model-auth injection supplies the provider base
  URL and the per-request bearer token.
- `:kind :ollama` uses native Ollama and rejects API keys.
- `:kind :custom` takes real `:chat-model` and/or `:streaming-chat-model`
  instances and requires explicit `:capabilities`. It rejects provider fields
  the adapter would otherwise ignore, including `:max-output`, and requires
  `:implicit-retries 0`. Known providers reject connection-level injected
  models instead of silently ignoring them; custom models may use
  connection-level injection.
- `:api-key-env` is resolved explicitly at construction. A missing variable is
  an error, not an empty credential.
- Multi-key connections keep `:credentials`, `:keys`, and `:key-strategy`.
  `:credential-resolver` is called once per request and may return
  `{:ok? true :secret "..."}` or `{:ok? true :connection {:api-key "..."}}`.
  The adapter rebuilds only that request's provider profile; the secret is not
  stored in port state, logs, events, or error data. A multi-key connection
  without a resolver fails `:credential-resolver-missing` before HTTP. A
  resolver that returns no secret fails `:credential-resolution-failed`.
- Application display metadata (`:name`, `:display-name`, `:pricing`,
  `:availability`, `:notes`, `:capability-sources`, `:capability-baseline`,
  `:credential-order`, `:updated-at`, and similar
  fields) is accepted and ignored by the adapter. `:connection-id`/`:model-id`
  and `:connection`/`:model` are aliases; role bindings may also carry
  `:mode` and `:confirmed?`.
- A `:main` request may name a `:connection`/`:model` that differs from the
  main role target. The target is the default for a request without an
  explicit identity; this is how a session keeps its selected model when the
  global main role changes. A `:task` request may not name a different
  connection or a different model: the adapter returns
  `:role-connection-mismatch` or `:role-model-mismatch` so a worker cannot
  borrow the main model or another task model.
- `:enabled? false` or `:removed? true` on a connection or model excludes it
  from provider construction. A role that names a disabled target is rejected.
- Role targets must build. Other enabled models are built best-effort so a
  main session can select them; a broken unused model is omitted instead of
  blocking the port. Selecting it later fails explicitly.
- Only `:main` and `:task` roles belong to the model port. Other application
  roles (for example `:utility` or `:vision`) are ignored here; their own
  components own them.
- When both roles are configured and no explicit `:default-role` is set, every
  request snapshot must carry `:role :main` or `:role :task`; otherwise the
  attempt fails `:role-required`. This prevents a task worker from silently
  borrowing the main model. A single-role configuration defaults to that role.
- Capabilities may be a set/sequence of keywords, or the client's map form
  such as `{:tools true :vision :unknown}`; only truthy, non-`:unknown`
  entries become declared capabilities. The adapter honours `:tools`,
  `:streaming`, `:reasoning`, `:json-schema`, `:vision`, `:document`, `:audio`,
  and `:video`. A request that requires any other capability fails before the
  provider is called.
- `:max-retries` other than 0 is rejected for adapter-built profiles. The
  adapter sets LangChain4j's non-streaming `maxRetries` to 0 and records
  `:implicit-retries 0`. Streaming providers have no implicit retry setting in
  LangChain4j 1.23.0. A custom injected model must explicitly declare
  `:implicit-retries 0`; missing or nonzero values are rejected because the
  adapter cannot inspect an injected object.
- `:close-models? true` closes models in configured port profiles that
  implement `AutoCloseable`. The default is false because Ollama and other
  endpoints may be shared. Profile `:close-fns` (including an explicit
  model-level `:close-fn`) run once each; configured profiles run at
  `close-port!`. A request-time credential profile runs when its attempt
  reaches a terminal event and closes its provider models unconditionally,
  because that profile was built for one attempt.
- If `:roles` is omitted, a single connection with a single model becomes both
  `:main` and `:task`. Multiple models require explicit roles.

Provider-specific `:options` are validated; unknown keys are rejected rather
than dropped.

- OpenAI/OpenAI-compatible: `:log-requests?`, `:log-responses?`,
  `:strict-tools?`, `:strict-json-schema?`, `:parallel-tool-calls?`,
  `:reasoning-effort`, `:send-thinking?`, `:return-thinking?`,
  `:max-tokens-field` (`:max-tokens` or `:max-completion-tokens`),
  `:streaming-buffer-size`, `:user`, `:seed`.
- Ollama: `:log-requests?`, `:log-responses?`, `:num-ctx`, `:num-predict`,
  `:seed`, `:repeat-penalty`, `:repeat-last-n`, `:think`, `:return-thinking`,
  `:truncate`, `:min-p`, `:mirostat`, `:mirostat-tau`, `:mirostat-eta`.

## Request snapshot

The Owner compiles a frozen snapshot. The adapter accepts:

```clojure
{:role :main
 :model "qwen"                  ; optional override inside the role connection
 :messages [{:role :system :text "..."}
            {:role :user :text "..."
             :quotes [{:message-id "m1" :text "quoted"}]
             :citations [{:label "source"}]
             :attachments [{:attachment-id "a1" :name "a.png"
                            :type :image
                            :send-route :native
                            :send-blob-id "sha256-..."
                            :send-mime "image/png"}]}
            {:role :assistant :text "..." :thinking "..."
             :tool-calls [{:id "provider-id" :name "cat" :arguments {...}}]}
            {:role :tool :tool-call-id "provider-id" :name "cat"
             :text "..." :error? false}]
 :tools [{:name "cat" :description "..."
          :parameters {:type "object" :properties {...} :required [...]}
          :strict? false :metadata {...}}]
 :temperature 0.2
 :max-tokens 4096
 :top-p 0.9
 :stop ["..."]
 :tool-choice :auto              ; :auto | :required | :none
 :response-format :text          ; :text | :json | JSON-schema map
 :required-capabilities #{:tools}
 :stream? true}                  ; default: streaming when available
```

Unknown fields, duplicate tool names, incomplete messages, and unparsable
tool arguments are rejected. The snapshot is never mutated. If it repeats
`:attempt-id` or `:call-id`, they must equal the contract request fields.

Synthetic Pair Hint messages may carry `:pair-hint true` (or
`:meta {:pair-hint true}`) and an empty tool name; ordinary blank tool names
remain rejected.

Tool argument strings accept JSON, standard EDN, and EDN+UFSS through
`hc.core.ufss/read-input` (or `read-str` for `:edn`). String keys are
recursively keywordized by default; a non-map or a key collision is an
explicit failure with parser position. Quotes and citations are flattened into
the user text, never dropped. Attachments must carry `:send-route`:
`:text` uses confirmed `:send-text`; `:native` uses `:send-blob-id` and
`:send-mime` and is resolved through `:attachment-loader` before dispatch.
Missing route/bytes/capability fails `:attachment-unresolved`,
`:attachment-loader-missing`, or `:unsupported-capability` with zero provider
requests. Attachments are never stripped silently. The attachment schema is
closed: a descriptor may carry the stable `:url` blob link as reference
metadata, but draft-only fields such as `:preferred-route`,
`:status-label`, or `:remove-label` are rejected before dispatch. The
application normalizes a draft into `hc.server.attachments.model`'s sendable
descriptor before the Owner freezes the request.

## Streaming, identity, and cancellation

- `StreamingChatResponseHandler` context callbacks capture LangChain4j's
  `StreamingHandle`. `cancel-request!` calls it, cancels the dispatch future,
  and normally emits exactly one `:cancelled` event. If the receiver refuses
  that terminal event, the attempt becomes `:failed` as described below. Late
  callbacks are dropped and counted in `stats :late-events`.
- Partial tool arguments stay private. A `:tool-call` event is published only
  after the complete callback parses to a map. The final response passes
  through the same `call_identity` registry, so a call seen in both the
  complete callback and the final response produces one project `call-id`.
- When a final response repeats a streamed provider id and payload at a
  different list position, the streamed output index wins. Sparse or reordered
  final indices therefore merge into one project call, not a second call.
- A provider id reused for a different payload, repeated in the final
  response, or mapped to more than one streamed call, fails
  `:provider-id-ambiguous` with `:code :PROVIDER_ID_AMBIGUOUS`; it never
  becomes a second project call.
- Allocated project call ids are globally unique across model and tool
  identities and anchored on `[attempt-id output-index]`:
  `<attempt-id>/tool-<output-index>`, or `<attempt-id>/tool-alias-<n>` when no
  index is known. The attempt id is the uniqueness boundary, so an allocated
  id can never collide with an Owner model call such as `call-1`. Two
  legitimate calls with identical names and arguments remain two calls
  because they occupy different output indices. Content-only matching is
  used only when both the final batch and the attempt contain exactly one
  call; otherwise a new provider id becomes a new project call.
- A failed stream reports `:published-call-ids` and `:partial? true`, so the
  Owner can reconcile already-claimed tools before retrying.
- Failed events also expose the retry classification at the top level:
  `:retryable?`, `:disposition`, and `:category`. The Owner still owns the
  retry budget; the adapter never retries.
- Failed and cancelled events also carry `:published-calls` with the full
  project call-id, output index, tool name, and parsed arguments. The Owner
  uses that data to reconcile or query external effects before another
  attempt; the adapter never replays a tool call.
- Non-streaming requests run on the port executor. `:stream? true` without a
  `StreamingChatModel` and `:stream? false` without a `ChatModel` are explicit
  failures, not silent downgrades.
- `cancel-request!` uses `:user-cancel`; `stop-port!` uses `:port-close`.
  Both deliver one terminal event (normally `:cancelled`; `:failed` when the
  receiver refuses it) before calling potentially blocking transport hooks,
  then stop accepting callbacks.

## 0.0.1 known limits

- Partial tool-call callbacks require a non-negative integer output index.
  A null or negative partial index fails the attempt; the adapter does not
  invent an index.
- A complete callback reuses a unique unpublished partial with the same
  provider id even when its index changed. If several partials share that
  provider id, the adapter does not guess; the call may be rejected or become
  a separate project call.
- Context style is tracked per channel. Once a context-style callback arrives
  for text, reasoning, or tools, later ordinary callbacks on that same channel
  are suppressed; other channels continue. A provider that mixes styles on the
  same channel can still lose the ordinary callback.
- A custom model must declare `:implicit-retries 0`. This is a declaration,
  not an inspected fact; an injected model that retries despite the
  declaration can exceed the Owner's retry budget.
- The port retains at most 64 terminal attempts for diagnostics and
  `await-attempt`; older terminal attempts and their call-identity entries are
  pruned. Active attempts are never evicted. `close-port!` clears the retained
  attempt table and registry.

## Usage and errors

- Missing token counts are `:unknown`, never `0`. The base LangChain4j
  `TokenUsage` has no cache fields; when the provider returns
  `OpenAiTokenUsage`, `events/usage-report` reads `cachedTokens` and
  `cacheWriteTokens` through reflection. A missing value stays `:unknown`.
- `events/usage-report` emits the normalized final report; the Owner owns the
  attempt ledger.
- Provider-confirmed usage is copied onto `:completed` and, when a final
  response was parsed before a terminal failure or refusal, onto `:failed` /
  `:cancelled`. If the provider reported none, the field is omitted; the
  adapter never synthesizes zero.
- `errors/classify` maps LangChain4j and transport failures to the categories
  in `hc.server.model-port.retry`. The adapter never retries. Authentication,
  invalid model, input-too-long, and content refusal fail immediately;
  network, timeout, 413 (input too long), 425, 429, and 5xx are classified by
  the shared table. Error messages are redacted for bearer tokens, `sk-` keys,
  and key=value secrets before entering an event; the exception class and
  HTTP status remain.
- A refused receiver event is a delivery failure. The attempt becomes
  `:failed` with `:error {:kind :delivery-error :code <receiver code or
  :MODEL_EVENT_DELIVERY_FAILED> :delivery <overflow|closed|delivery-error>}`.
  A refused terminal completion/cancellation never leaves the attempt looking
  completed; `cancel!` returns `{:delivery-failed? true :event <failed-event>}`.
  Exactly one terminal event reaches the attempt promise.
- The adapter implements no capability degradation. A required capability
  that the profile does not declare, or a declared degradation that would
  require dropping input, is an explicit `:unsupported-capability` failure.

## Dependencies and verification

The package itself needs only `langchain4j-core` plus the LangChain4j umbrella
artifact already in the `:server` alias. Real providers need their modules:

```clojure
dev.langchain4j/langchain4j-open-ai {:mvn/version "1.23.0"}
dev.langchain4j/langchain4j-ollama   {:mvn/version "1.23.0"}
```

They are loaded lazily; a missing module is `:provider-module-missing`.

Run the scoped tests with the server alias:

```bash
clojure -M:test:server -n hc.server.model-adapter.request-test
clojure -M:test:server -n hc.server.model-adapter.config-test
clojure -M:test:server -n hc.server.model-adapter.port-test
clojure -M:test:server -n hc.server.model-adapter.events-test
clojure -M:test:server -n hc.server.model-adapter.errors-test
clojure -M:test:server -n hc.server.model-adapter.openai-smoke-test
```

The smoke test starts a deterministic local OpenAI-compatible SSE server and
uses the real `OpenAiStreamingChatModel`. It needs no credentials or public
network. It covers text chunks, streamed tool calls, final-response identity
merging, usage, and real `StreamingHandle` cancellation. A separate test
constructs the native Ollama models without connecting.

Measured on 2026-11-03 with the Pixi JDK 21 environment and the standard
`:test:server` alias:

| Namespace | Tests | Assertions |
|---|---:|---:|
| `hc.server.model-adapter.request-test` | 11 | 45 |
| `hc.server.model-adapter.config-test` | 3 | 10 |
| `hc.server.model-adapter.port-test` | 30 | 168 |
| `hc.server.model-adapter.events-test` | 3 | 15 |
| `hc.server.model-adapter.errors-test` | 1 | 10 |
| `hc.server.model-adapter.openai-smoke-test` | 10 | 39 |

`port-test` and `events-test` were measured on 2026-11-04 at the current tree
(33 tests / 183 assertions, 0 failures, 0 errors) after the
sparse-index, port-close, provider-id, role-override, index-shift, and
refusal-contract additions. The other rows are earlier measurements in the
same session; `config-test` gained two cases (4 tests / 11 assertions) that
DevOps reruns separately. The smoke test used a loopback HTTP server, not a
public endpoint;
no credentials were available or needed. It verifies the real request body
contains `image_url`/data URI for a declared `:vision` model, contains
extracted text for a `:text` route, sends zero HTTP requests for an undeclared
capability or unprepared attachment, and sends
`Authorization: Bearer rotated-key` from a per-request credential resolver.

## Layout

```text
src/hc/server/model_adapter.clj          public factory and control API
src/hc/server/model_adapter/config.clj   connection/role normalization
src/hc/server/model_adapter/providers.clj lazy provider registry, capability checks
src/hc/server/model_adapter/providers/openai.clj
src/hc/server/model_adapter/providers/ollama.clj
src/hc/server/model_adapter/request.clj  snapshot -> ChatRequest/ToolSpecification
src/hc/server/model_adapter/events.clj   LangChain4j response -> port events
src/hc/server/model_adapter/errors.clj   failure classification
src/hc/server/model_adapter/json.clj     EDN <-> JSON bridge for tool arguments
src/hc/server/model_adapter/port.clj     ModelPort implementation and state
```
