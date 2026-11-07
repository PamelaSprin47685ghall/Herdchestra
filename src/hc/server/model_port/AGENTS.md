# `hc.server.model-port` — low-level model port

Authority: root `AGENTS.md` §5.4 and decision D16. This package adapts one
model attempt to the kernel without letting a provider own history, tool
loops, or retry policy.

## Contract

`contract/ModelPort` exposes exactly two operations:

```clojure
(start-request! [port request receiver])
(cancel-request! [port handle])
```

A `request` is built by `contract/request` and carries `:attempt-id`,
`:call-id`, a deep-frozen `:snapshot`, and `:required-capabilities`. The
snapshot contains persistent Clojure data only; mutable or opaque objects are
rejected. Compiling the snapshot from session state, Pair Hint, and Scope is
the caller's job.

A port delivers `:chunk`, `:tool-call`, `:completed`, `:failed`, or
`:cancelled` events through `contract/Receiver`. Every event carries its
`:attempt-id`; a tool-call event also carries the project `:call-id` and
`:output-index`. Transport callbacks only copy data and enqueue. They never
write storage, start tasks, or block on I/O.

`contract/resolve-capabilities` returns an explicit error for an unsupported
capability, or a declared degradation supplied by the caller. No input field
is dropped silently.

## Call identity

`call-identity` owns project call ids. Identity is anchored on
`[attempt-id output-index]`; a provider id is an alias for the case where the
output index is absent. The adapter does **not** deduplicate by tool name plus
argument hash: two legitimate calls may have identical arguments.

- A complete tool request allocates `<attempt-id>/tool-<output-index>` once;
  an alias-only call uses `<attempt-id>/tool-alias-<n>`. The attempt id is the
  uniqueness boundary, so a project id never collides with an Owner model call
  such as `call-1`.
- A repeated complete callback or final-response entry with the same identity
  and payload merges into the existing call.
- A changed payload, changed provider id at the same identity, or ambiguous
  provider alias returns an explicit conflict.
- Passing the previous `:call-id` keeps a business call stable across
  attempts. A retry may present a new provider id.
- `merge-response` is all-or-nothing: one conflict rejects the whole final
  response and leaves the registry unchanged.

## Usage

`usage` records input, output, cache-read, and cache-write tokens, finish
reason, reasoning summary, and opaque continuity data.

- A missing value is `:unknown`, never `0`.
- Streaming partial and final reports merge by coverage. A known final value
  replaces the partial value; values are never added twice.
- Every attempt remains in the ledger, including failed and cancelled ones.
- An aggregate field is an integer only when every attempt reported it;
  otherwise it is `:unknown` and names the unknown attempts.
- Reasoning text is stored as given. Continuity data is marked
  `{:opaque? true :editable? false}` and is never treated as user prose.

## Retry

`retry` separates business continuation from upstream retry.

- The attempt cap (`max-attempts`, including the first try), retry cap
  (`max-retries`, excluding it), absolute deadline, and fixed `:retry-at` live
  in `hc.compose.retry.planner`. This package owns the shared vocabulary and
  does not keep a second budget implementation.
- Network, timeout, 429, and 5xx are retryable. Authentication, permission,
  insufficient quota, invalid model, overlong input, and content refusal fail
  immediately. Tool-argument failure is a new attempt, unknown external
  outcome is reconciled, and user cancellation is never retried.
- `validate-transport` requires the adapter to declare implicit HTTP retries
  disabled. `nil` is rejected because an implicit retry would double the
  budget.

## Streaming and delivery

`stream` assembles argument fragments privately and publishes a `:tool-call`
only when the adapter marks the call complete and the injected parser returns
a map. The parser is provider-specific; this package assumes neither JSON nor
EDN. The final response passes through the same identity registry, so a call
seen in both the complete callback and the final response produces one event.

`receiver/bounded-receiver` is the optional bounded merge point between a
transport thread and the Owner. Overflow and closed states are explicit
results, never silent loss. `merge-chunks` coalesces adjacent text chunks
without reordering events.

## Non-goals and limits

- No provider implementation, no HTTP client, and no LangChain4j import in
  this package. Tests use recording doubles, not fake providers.
- No `AiServices`, no `ChatMemory`, and no automatic tool loop. History and
  context compilation stay outside the port.
- No persistence, timers, or background threads. Retry timers and Owner
  delivery belong to `hc.compose` and the server runtime.
- The transport adapter must attach provider ids and output indices, call the
  receiver only with lightweight events, and close the underlying HTTP client
  retry policy.
- Provider-specific request rendering and tool-schema validation remain in
  the adapter; this package validates completeness and identity, not every
  tool's semantic arguments.

## Layout and verification

```text
src/hc/server/model_port/
├── contract.clj        protocol, frozen request, capability check, events
├── call_identity.clj   project call ids, provider aliases, final merge
├── usage.clj           coverage merge, ledger, aggregate
├── retry.clj           classification and transport retry declaration
├── stream.clj          private fragment assembly, one-time publication
└── receiver.clj        bounded delivery, adjacent chunk merge
```

Tests live under `test/hc/server/model_port/` and use `clojure.test` plus
`clojure.test.check` properties. The package needs no server alias:

```bash
clojure -X:test :dirs '["test/hc/server/model_port"]'
```

Full-suite runs are owned by the integration pass; this package's scoped run
is the smoke gate.
