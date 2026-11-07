# `hc.server.diagnostics` — usage/cost statistics and diagnostic redaction

Authority: root `AGENTS.md` §5.4, §7.4 旅程 8.6（导出诊断包）, §7.5, §7.7, and §8.5. Root
`AGENTS.md` wins on conflict.

This package prepares statistics views, cost estimates, detailed-log
policies, and diagnostic previews. It is pure data preparation:

- No storage read or write.
- No network request.
- No background thread.
- No upload entry point.

The caller supplies records, persists the returned policy, and writes a
diagnostic artifact only to a destination the user selected.

## Layout

```text
src/hc/server/diagnostics.clj          public facade
src/hc/server/diagnostics/
├── usage.clj       request identity, cache scope, token totals, provenance
├── pricing.clj     token and non-token price records, partial estimates
├── stats.clj       time range, counts, heatmap, model rows, turns, cost
├── redact.clj      credential fields, patterns, stable placeholders
├── log.clj         detailed-log policy, retention, redaction at write time
├── bundle.clj      local-only preview, line removal, local export
├── store.clj       partition-scoped persistence through hc.server.storage
├── service.clj     application-facing composition and command handler
├── integration.clj application shell attach, blob export, baseline
├── capture.clj     model-port decorator that records terminal attempts
└── service_usage.clj explicit provider-usage extractors; absent -> nil
```

## Usage records

`usage/request` normalizes one model request. The only required field is
`:request-id`. Useful identity fields are `:turn-id`, `:session-id`,
`:assistant-id`, `:service-id`, `:service-name`, `:model`, `:purpose`,
`:result`, `:retry-of`, and `:recovery-of`. `:at` is an epoch millisecond
value, `Instant`, `Date`, or ISO-8601 string. Display fields are kept
unchanged.

`:usage` is normalized by `hc.server.model-port.usage`. Every token field is
a non-negative integer or `:unknown`. The statistics layer adds
`:cache-mode`.

### Cache scope

- `:input-subset` (default): `:cache-read-tokens` and
  `:cache-write-tokens` are already inside `:input-tokens`.
  `:total-tokens` is `input + output`. The cache fields are detail only.
- `:additional-input`: cache tokens are separate input-side quantities.
  `:total-tokens` is `input + output + cache-read + cache-write`.

In `:input-subset` mode, a record whose cache total exceeds its input is
rejected with `:invalid-usage` / `:cache-not-subset`. The adapter must
normalize provider-specific fields into one of these two modes before it
records a request.

A cache value is never added to a total twice. A cached-input subset remains
visible in `:cache-read-tokens` and `:cache-total`.
`:cache-subset?` is `:not-applicable` in `:additional-input` mode.

### Provenance and unknowns

Each token field carries provenance from the model port: `:reported`,
`:estimated`, or `:unknown`. `:total-provenance` follows the same rule for
the total.

A collection field is an integer only when every request reported it.
Otherwise it is `:unknown`, and `:unknown-requests-by-field` names the
requests that prevented a total. Fields with no unknown request are omitted,
so an empty map means every field is fully known. No confirmed zero is invented. A failed or
cancelled request stays in the ledger when it has usage. A retry is a new
request with `:retry-of`; a resumed service request is the same request with
`:recovery-of`.

`usage/summary` returns counts of reported, estimated, and unknown requests.
`:complete?` means no request has an unknown token field. `:all-reported?`
means every request is fully reported.

## Statistics

`stats/summary` takes a snapshot and a query.

Snapshot keys: `:requests`, `:user-sends`, `:tool-calls`, optional
`:last-updated`, `:cached?`, and `:read-error`.

Query keys: `:from`, `:to`, `:all`, `:zone`, `:zone-mode`, `:top-n`, `:now`,
and any of `:assistant-id`, `:model`, `:service-id`, `:purpose`, `:result`,
`:session-id`.

Rules:

1. Counts are distinct facts: `:user-sends`, `:model-requests`, and
   `:tool-calls`. One send can contain several model requests. Editing
   history without sending and switching a reply version create no request
   record.
2. The default range is the last 30 days, including today. Explicit `:from`
   and `:to` dates are inclusive. `:from` after `:to` is
   `:range-reversed`. `:all` cannot be combined with `:from` or `:to`.
3. Day buckets use one timezone for the whole query. `:zone-mode :record`
   uses each record's `:occurred-zone` instead. A record without a usable
   timestamp goes to `:unknown-time`; it is never assigned to an import day
   or to today. An explicit range cannot prove it belongs inside the range,
   so it is also reported in `:excluded-unknown-time`. `:all` includes it in
   totals.
4. Every chart, model row, turn detail, and cost summary uses the same
   selected records. Filters cannot split the range.
5. The same model name on two services stays two rows. The group key is
   `[service-id model]`.
6. Duplicate delivery is counted once: requests by `:request-id`, sends by
   `:send-id` or `:turn-id`, tools by `:tool-call-id` or
   `[turn-id name index]`.
7. No data is `:no-data` with zero counts, but `:tokens` and `:cost` are nil.
   A read failure is `:read-failed` with `:retry? true` and no zero curve.
   An invalid query is `:invalid-query` with an error code.
8. Turn details keep only non-sensitive metadata. Chat text, tool arguments,
   and other content are not copied into the statistics view.

`:turns` keeps every request visible. A retry is not folded into the original
request. Each turn carries `:user-send`, `:requests`, `:tool-calls`,
`:counts`, `:retry-count`, `:failed-count`, `:cancelled-count`, `:usage`, and
`:cost`.

## Pricing and cost

`pricing/price` requires a non-blank `:currency` and fixes `:unit` at
`:per-million-tokens`. `:input`, `:output`, `:cache-read`, and
`:cache-write` are non-negative BigDecimal rates or nil when unknown.
`:source` and `:source-date` are display facts; this package has no built-in
price table.

`pricing/estimate` returns `:complete`, `:partial`, or `:unestimated`:

- `:complete`: every billable component is known.
- `:partial`: `:amount` covers known components only; `:missing` names why
  another component could not be priced.
- `:unestimated`: no component can be priced; `:amount` is nil.

A positive quantity with an unknown rate is unknown, never zero. Zero tokens
cost zero even when the rate is unknown. In `:input-subset` mode the
uncached remainder pays the input rate and cached tokens pay their own rates.
`pricing/unit-preview` uses a fixed 1,000,000-token example so a per-million
rate is not mistaken for a per-thousand rate.

An invalid price record is reported as `:invalid-price-requests`; it does not
abort the statistics view.

### Non-token service billing

`pricing/service-price` accepts `:per-image`, `:per-second`, `:per-call`,
`:per-request`, `:per-item`, `:per-character`, `:per-megabyte`, and
`:per-credit`.
`pricing/estimate-service` multiplies one non-negative quantity by the rate
in that unit. A missing rate or quantity is `:unestimated`, never zero.
`pricing/service-unit-preview` shows one unit at the stated rate.

Statistics accepts `:service-usages` records with `:usage-id`, `:unit`,
`:quantity`, `:quantity-source`, `:price`, and optional `:billed`.
`:service-cost` groups estimated and actual amounts by currency, preserves
quantities by unit, and counts unestimated usages. `:quantity-source` is
`:provider-reported`, `:estimated`, or `:unknown`; the cost view counts each
class separately. Service units are never converted to tokens or summed
across currencies. `:service-usage-count` and each turn's `:service-usages`
keep the billing detail visible without changing the three message counts.

`service_usage.clj` is the automatic-hook boundary. `stt-duration` accepts
only an explicit provider `:usage` seconds/duration; local recording time is
ignored. `search-credits` accepts only explicit provider credits. A missing
provider field returns nil and the caller records nothing. `provider-reported`
rejects a non-numeric quantity and preserves the provider's numeric value;
statistics converts to BigDecimal only when summing.
Artifacts never infer billing from file size or producer metadata; a producer
that sees a real provider response calls `record-service-usage!` directly.

Provider wiring as verified with the owning workers:

- Speech STT uses explicit provider duration; TTS uses explicit
  `x-usage-*` headers; translation uses explicit seconds/characters/calls.
  Local duration, bytes, and text length are ignored. Speech tests pass
  27/165.
- Web search emits `:service-usages` only for Tavily `usage.credits`
  (`:per-credit`); Brave rate-limit headers and SearXNG produce no record.
  Search tests pass 54/397.
- The app router records each search result's `:service-usages`, and the
  speech component's `:usage-port` calls `record-service-usage!`. Artifacts
  keeps `:producer :usage` opaque and does not record billing.

Statistics cost is grouped by currency in `:by-currency`. There is no
cross-currency total. `:estimated-amount` and `:actual-amount` are separate:
the first is a local estimate, the second is a service-reported charge. They
are never added together. `:unestimated-requests` counts requests with no
estimate and no actual charge. `:unknown-currency-requests` counts requests
whose price or charge has no currency. `:partial-requests` counts partial
estimates. `:estimated-requests` and `:actual-requests` show coverage.
`:request-count` is the number of requests in the cost view. A request whose
estimate currency differs from its service-reported currency puts each amount
in its own currency bucket and increments `:currency-mismatch-requests`; the
two amounts are never added.

A request carries its own `:price` snapshot. `service/record-request!` fills
that snapshot from the stored `[service-id model]` price at record time.
Statistics also accepts snapshot `:prices` as a fallback for old records that
have no snapshot; it never replaces a snapshot that is already present. A
caller that wants a new analysis view must build it explicitly and keep the
original view. `stats/reprice` returns a new request vector with catalog
prices; it does not mutate the original requests.

## Redaction

`redact/redact` returns `{:value :findings :redacted? :placeholder-count}`.
Findings contain `:path`, `:kind`, `:category`, and `:placeholder`; they
never contain the original value.

Credential field names are matched after lower-casing and replacing
non-alphanumeric runs with `-`. Token statistics such as `:input-tokens`,
`:output-tokens`, `:max-tokens`, and `:token-count` are not secrets.
`:authorization`, `:x-api-key`, `:client-secret`, `:refresh-token`,
`:password`, and similar names are secrets.

Free-text patterns cover bearer and basic tokens, OpenAI keys, GitHub tokens,
Slack tokens, AWS access keys, Google API keys, JWTs, private-key blocks, URL
userinfo, and `key = value` forms. Add caller patterns through
`:extra-patterns`.

A known credential value is replaced by `<redacted:secret:N>`. The same value
gets the same placeholder in one pass; different values get different
numbers. Placeholder numbers continue after any placeholder already present,
so a second pass does not collide with the first. Redaction is idempotent.

`:mask-hosts?` replaces service domains with `<redacted:host:N>` and keeps
the URL scheme. `:mask-paths?` replaces home-directory paths with
`<redacted:path:N>`. `:rename` replaces user-selected nicknames before secret
matching.

## Detailed logs

`log/new-policy` returns a disabled policy with seven-day retention.

- `enable` defaults to `:next-request`: one accepted entry, then disabled.
- `:timed` requires `:days` from 1 to 7.
- `:temporary? true` entries are never recorded.
- A paused policy records nothing and keeps existing entries.
- `expire` drops entries older than the retention window and closes an
  expired timed window.
- `clear` removes detailed entries only. It does not touch chats,
  assistants, or usage summaries, which the caller owns elsewhere.
- Every entry is redacted before it is stored. `record` returns
  `:recorded?`, `:reason`, `:entry`, `:redaction`, and `:policy`.
- `service/record-request!` writes one redacted detailed entry when the
  policy is capturing; duplicate request ids are idempotent.
- `service/log-status` calls the storage expiry path, so the seven-day
  retention is a delete guarantee, not only a read filter. Status includes
  `:bytes` (serialized entry size) and `:entry-count`.

The package performs no I/O. The caller persists the policy and the entries.

## Persistence and application service

`store.clj` writes only to the `diagnostics` partition. Records are
partition-scoped: writes omit `:session-id`, and reads use
`(storage/read-session-view store {:partition "diagnostics"})`. Kinds are
`:diagnostics-request`, `:diagnostics-user-send`, `:diagnostics-tool-call`,
`:diagnostics-service-usage`, `:diagnostics-price`,
`:diagnostics-log-policy`, and `:diagnostics-log-entry`.

Append-only records use a deterministic migration ID derived from the domain
ID, so replay is idempotent and a changed payload is a storage conflict.
Policy and price updates use a UUID. A log entry and its policy change commit
in one migration; clear and expire delete entries and update the policy in
one migration. Stored log policies never duplicate the entries themselves;
the snapshot reconstructs the retained entries from their records.

`service.clj` composes storage, statistics, log policy, redaction, and
export. Its command kinds are `:diagnostics/statistics`,
`:diagnostics/log-status`, `:diagnostics/log-enable`,
`:diagnostics/log-disable`, `:diagnostics/log-pause`,
`:diagnostics/log-resume`, `:diagnostics/log-clear`,
`:diagnostics/bundle-preview`, `:diagnostics/bundle-remove-lines`,
`:diagnostics/bundle-rename`, `:diagnostics/bundle-hide-hosts`,
`:diagnostics/bundle-hide-paths`, `:diagnostics/bundle-export`, and
`:diagnostics/save-price`. The application router dispatches these payloads
to `service/handle!`; the receipt carries the result.

`integration.clj` is the application-shell bridge. `attach` reads `:store`,
`:blob-root`, and `:app-version`, then returns the service. With a blob root,
`bundle-export` writes the EDN artifact through
`hc.server.blob-store/put-bytes!` and returns only the content ID, filename,
and size; the existing `/blobs/<id>` route serves it. Without a blob root it
returns the local artifact map. No path uploads.

The app baseline should carry only `(integration/baseline diagnostics)` (log
status); full statistics are on demand. Record calls happen in the Owner
serial step after the corresponding domain event, never in a model callback
thread.

`capture/recording-port` wraps the real model port. It forwards every event
unchanged and records `:completed`, `:failed`, and `:cancelled` terminal
events on one owned daemon thread, so provider callbacks never block on
storage. Request id is the attempt id, turn id is the call id, and usage,
finish reason, and error stay as reported. The application must pass
`(:port wrapped)` to the Owner and call `(:stop! wrapped)` during shutdown.
Tool and non-token service-usage hooks remain application calls after the
executor result.

## Diagnostic bundle

`service/prepare-bundle` merges a client-selected `:detailed-log` material
by `:id`: the server keeps its own title, kind, content, and default purpose;
the request may only set `:include?` and a non-blank purpose. A client-sent
`:content` is ignored. `bundle/prepare` builds a local preview. The service
wraps it with a server-issued `:bundle-id` and records the preview digest. `bundle-export`
accepts only a bundle whose digest still matches; export without a preview,
or after a tamper, is refused. `remove-lines` removes both the section line
and the corresponding value from `:content`, so export cannot retain a line
the user deleted. The default sections are application,
time range, error, services, and redaction. The default exclusions are chat
body, attachments, secrets, private paths, and full request. Keys such as
`:chat`, `:chat-body`, `:attachments`, `:request-body`, `:prompt`, and
`:response` are ignored and reported in `:ignored-fields`.

Extra materials are excluded by default. A material is included only when
`:include?` is true and `:purpose` is non-blank. Forbidden kinds such as
`:chat-body`, `:attachment`, `:secret`, `:private-path`, and
`:full-request` are rejected even when selected; they appear in
`:rejected-materials` without content.

The error category is one of the 24 contract categories. An unknown category
becomes `:unclassified` and keeps `:reported-category`; the package does not
invent a root cause. A raw provider error is opt-in and redacted.
The error detail text is also opt-in through `:include-error-detail?`; the
default preview shows `not included`.

`remove-lines` deletes preview lines by id. `rename` replaces nicknames.
`hide-hosts` and `hide-paths` re-redact the preview without renumbering an
existing placeholder class. `export-view` returns the serializable map.

`:transfer` is always:

```clojure
{:mode :local-only
 :automatic-upload? false
 :network? false
 :requires-user-action? true
 :destination :user-selected}
```

`local-artifact` returns EDN text with `:uploaded? false` and
`:automatic-upload? false`. There is no URL and no upload function. The
caller writes the artifact only after the user chooses a destination.

### 0.0.1 truthfulness audit

- A cancelled or failed request remains in the statistics ledger whenever it
  has usage. Its confirmed tokens and charge stay in the totals; missing fields
  stay `:unknown`, and a missing price keeps the request in
  `:unestimated-requests`.
- A record with no usable timestamp is excluded from an explicit range but
  remains in `:unknown-time` and `:excluded-unknown-time`; `:all` includes it.
  It is never assigned to an import day or to today.
- Detailed-log entries are redacted before they enter the policy. Status reads
  expose counts and retention, not entry content.
- Bundle previews disclose `:rejected-materials`, `:ignored-fields`, and
  `:excluded`. A preview with no redaction findings is not a guarantee about
  ordinary prose; the client states that.
- `:transfer` remains local-only and has no automatic upload entry point. The
  export result keeps `:uploaded? false` unless a caller supplies contrary
  evidence, which this package does not provide.

## Application wiring

The Owner serial step must call `service/record-user-send!` for an accepted
user message and `service/record-tool-call!` for a non-model executor
terminal. The app system supplies these as `:on-user-send!` and
`:on-call-terminal!` callbacks in the Owner `:config`; `record-request!`
already covers model attempts and detailed-log capture. Until that system
patch is applied, the three-count statistics remain empty in a live app even
though the storage/statistics path is complete.

## Verification

The package test gate is:

```bash
clojure -X:test:server :dirs '["test/hc/server/diagnostics"]'
```

The suite covers:

- missing usage stays `:unknown`, never `0`;
- cached input is a subset and is not added twice;
- additional cache mode adds cache exactly once;
- provenance aggregation and unknown-request names;
- failed, cancelled, and retry records stay countable;
- inclusive ranges, timezone buckets, record zones, and unknown-time
  exclusion;
- distinct send/request/tool counts, filters, duplicate delivery, and
  per-turn drilldown;
- per-currency cost, partial estimates, actual charges, and price snapshots;
- credential fields, free-text patterns, stable placeholders, host/path
  masking, rename-before-redact, idempotence, and findings without values;
- detailed-log default, next-request, seven-day cap, temporary sessions,
  pause, retention, clear, and redaction at write time;
- bundle exclusions, forbidden materials, purpose requirement, line removal,
  rename, host masking, unclassified errors, raw-error opt-in, and
  local-only transfer;
- an end-to-end smoke path from `hc.server.model-port.usage` through
  statistics, cost, redaction, and detailed logging;
- real-store persistence: commit requests/sends/tools/service usage/log
  policy/entry, close, reopen, and read the same statistics and redacted log;
- application bridge: attach with a blob root, dispatch statistics, preview
  and export a bundle, read the stored bytes, and read the baseline log
  status;
- actual app path: start `hc.server.app.system`, dispatch diagnostics through
  its real router, record a request, read statistics, edit a preview, export
  through the app blob root, and stop the app;
- model capture: completed and failed terminal events produce request records
  off the transport thread;
- unknown-field omission: fields with no unknown request are absent from
  `:unknown-requests-by-field`;
- retry/cache accumulation: original plus retry sum to 1650 total tokens and
  USD 0.005940M, with the retry still a separate request row.

The last stable run before these behavior fixes was 100 tests / 459
assertions; the app-path test now has 20 assertions after adding persistence
status checks. DevOps must rerun the gate after the unknown/retry/cache
changes; this file does not claim those new tests passed. The full repository suite,
E2E 01–22, and other packages' gates are **NOT RUN** by this package.
