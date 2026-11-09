# `hc.client.diagnostics` — statistics, cost, logs, and local diagnostic previews

Authority: root `AGENTS.md` §7.4 旅程 8.6（导出诊断包） and §7.4 旅程 8.5
（排障与十类未交付理由）, `src/AGENTS.md`, `src/hc/client/AGENTS.md`. Root
wins on conflict.

## 1 Boundary

This package owns the browser display state for the settings **data** section:
usage statistics, estimated cost, detailed-log policy, the diagnostic bundle
preview, and the feedback draft. It does not own storage, model pricing
authority, log persistence, or bundle generation. The server
(`hc.server.diagnostics.service`) owns those facts.

The package has no upload path. It never posts a bundle to a remote service,
never opens a public issue, and never submits a feedback draft. A preview is
generated locally by the server and rendered in the browser. Export is a
user-clicked command; the resulting local artifact is offered through the
browser's download mechanism.

```text
model.cljc   pure state, queries, projections, reducer, receipt application
view.cljs    React component; no network or storage side effects
bridge.cljs  Sente command envelopes, receipt routing, local download/copy
diagnostics.cljs  public integration facade
```

## 2 Honest statistics

The UI follows the server's four states: `:ok`, `:no-data`, `:read-failed`,
and `:invalid-query`.

- A read failure keeps an explicit retry and shows no zero curve. When an
  earlier successful result exists, the view keeps that result and its
  `:last-updated` timestamp, records the failure in `:stats-error`, and shows
  the old data under a failure banner with the retry.
- `:no-data` is an empty state with metric definitions.
- The summary uses the range and filters carried by the returned statistics,
  not the pending query. Changing the preset, dates, zone, zone mode, filters,
  or top-N marks the displayed result stale and the view says
  `筛选已更改，请刷新统计` until a new result arrives.
- Counts are named separately: user sends, model requests, tool calls, active
  days. One send may contain several requests. A retry stays a separate
  request row; totals sum each attempt once and never overwrite the original.
- Tokens keep their source: `服务返回`, `估算`, `未提供`. Cache read/write are
  input details. In `:input-subset` mode they are never added to the total a
  second time.
- Cost is grouped by currency. There is no cross-currency total. Estimated
  and service-reported actual amounts are separate fields.
- A request with unknown usage or unknown price is counted as unestimated,
  never as zero. Coverage shows how many requests have an estimate. The
  "unknown field" warning appears only for fields that actually list unknown
  requests; its wording follows `:total-provenance`, so a known total is not
  mislabeled unknown.
- Cancelled, timed-out, and failed requests stay visible with their confirmed
  usage. A cancelled request is a warning, not a hidden row; its confirmed
  tokens and cost remain in the totals, while unknown fields stay `未提供`.
- The empty state still renders the unknown-time panel when records exist but
  cannot be placed in the selected range. "No records in range" never hides
  time-unknown records.
- `hide amounts` applies to the per-currency cost cards, request details, and
  the compact model-distribution labels. Hiding amounts never hides usage
  counts.
- Service usage preserves quantity provenance: `服务返回`, `估算`, `未提供`
  counts are shown separately. A fractional quantity is not truncated.
- Service usage shows estimated and actual amounts, currency, and source in
  separate fields. An absent actual amount is `无实收记录`; only an absent
  estimate is `未估算`. Unit totals are also listed with their currency; a
  usage without a currency keeps its quantity under the unit summary and is
  reported as a warning instead of being added to a currency total.
- Price edits are per service + model, per 1,000,000 tokens, with currency,
  source, and source date. The fixed example uses 1,000 input tokens, 200
  cache-hit tokens (not the rate), 0 cache writes, and 100 output tokens so a
  per-1,000 price is visibly wrong. A missing cache-hit rate prices only the
  known parts and labels the result partial; a missing cache-write rate is
  irrelevant when the example writes no cache. Negative rates are invalid,
  never a negative estimate.
- The heatmap renders only buckets the server returned. Selecting a day is a
  button action, keyboard reachable, and shows the counts plus assistant IDs
  when the turn rows carry them. Unknown-time records are reported separately;
  they are not assigned to an import day or to "today".

## 3 State

State lives at `:ui/diagnostics` in the Fulcro database.

```clojure
{:status :idle | :loading | :ok | :no-data | :read-failed | :invalid-query | :error
 :loaded? false
 :stats nil
 :stats-stale? false
 :stats-error nil
 :error nil
 :message nil
 :query {:preset :last-30-days :from nil :to nil :zone nil
         :zone-mode :query :top-n 8
         :filters {:assistant-id nil :model nil :service-id nil}}
 :filter-options {:assistants [] :models [] :services []}
 :selected-day nil
 :hide-amounts? false
 :tab :statistics
 :can-submit? false
 :log nil
 :log-loaded? false
 :log-days 1
 :clear-log-confirm? false
 :pending {command-id {:kind ... :sent-at ...}}
 :bundle {:status :idle :value nil :error nil
          :request {...} :removed-lines #{} :export nil}
 :price {...}
 :feedback {...}}
```

A receipt, not a click, changes statistics, log status, bundle content, or
price-save status. `:pending` is only for routing receipts; it is not a domain
result. A `:not-found` receipt keeps its pending entry and marks the command
`:unknown` with `未找到回执，状态未知`; it applies no domain result, clears no
pending entry, clears the optimistic loading/saving status to a neutral value,
and downloads nothing.

If a command cannot be sent, the bridge restores the optimistic status by kind:
statistics leave `:loading`; preview and export return to the previous preview
or an explicit error; a price save leaves `:saving`. The failure message stays
visible. A rejected line removal restores the `:removed-lines` set recorded
before the click, not the optimistic set.

## 4 Commands and receipts

The bridge sends the normal workbench envelope:

```clojure
{:command-id "..." :command-epoch 7
 :payload {:kind :diagnostics/statistics :query {...}}}
```

Used kinds:

- `:diagnostics/statistics`
- `:diagnostics/log-status`, `:diagnostics/log-enable`,
  `:diagnostics/log-disable`, `:diagnostics/log-pause`,
  `:diagnostics/log-resume`, `:diagnostics/log-clear`
- `:diagnostics/bundle-preview`
- `:diagnostics/bundle-remove-lines`
- `:diagnostics/bundle-rename`
- `:diagnostics/bundle-hide-hosts`
- `:diagnostics/bundle-hide-paths`
- `:diagnostics/bundle-export`
- `:diagnostics/save-price`

Receipts are CUSTOM `:hc/receipt` or `:hc/command-receipt` events. The
receipt's `:receipt` map carries `:statistics`, `:log`, `:bundle`, or
`:artifact`. The bridge applies a receipt only when its `:command-id` is in
`:pending`; a replay or unrelated receipt is ignored.

## 5 Diagnostic preview and export

The default preview request contains only explicit choices:

- application version, platform, OS, time range, error category, and redacted
  service display facts;
- error detail and raw provider error are opt-in;
- extra materials are listed but excluded by default; each needs an explicit
  include flag and a purpose;
- chat bodies, attachments, secrets, private paths, and full requests are
  never added by this package.

Material entries are merged by id with the server's preview description. The
client copies `id`, `title`, `kind`, `purpose`, and `content` when present, and
sends the full material list. The server preview returns `:included?`; the
client keeps one `:include?` key for the checkbox. The toggle preserves the
server title, kind, and purpose instead of inventing a log-only material.

The server returns `:transfer {:mode :local-only :automatic-upload? false
:network? false :requires-user-action? true}`. The view displays those facts
literally. Preview line removal, nickname replacement, and host/path masking
go back through the server's preview-edit commands so placeholder numbering
stays consistent. The user may remove a line locally as well; export sends the
reviewed bundle.

Preview and export messages, the page header, and the export panel are derived
from `:transfer`, `:uploaded?`, and `:automatic-upload?`. A remote or uploaded
receipt is never described as "only local" or "not uploaded".

The "local only" heading is derived from the receipt, not hardcoded. If a
future receipt reports a remote or automatic transfer, the view says so and
shows `已上传` separately. When redaction finds no known credential pattern,
the preview says that this is not a guarantee; the user must still review
ordinary prose for personal information.

Export calls `:diagnostics/bundle-export` with the reviewed bundle. When the
receipt contains a full local artifact, the bridge offers a browser download.
When the server has a blob writer, the receipt contains only the content ID
and filename; the bridge does not invent a download URL. In both cases
`:uploaded?` is false and no automatic transfer exists.

## 6 Integration seam

`hc.client.diagnostics` exposes:

```clojure
(install! app)            ; receipt listener + app ref; idempotent
(sync! db)                ; project server log status and connection gate
(diagnostics-element db)  ; React element for the settings :data section
(ensure-stats! db)        ; optional load-once helper
(initial-state)           ; fresh state for db.cljs
```

The workbench mounts `diagnostics-element` when
`(= :data (get-in db [:ui/settings :ui :section]))`. The view requests
statistics once when it is mounted, the channel is synced, and state is idle.
A read failure is retried only by the explicit retry button.

## 7 Accessibility and responsive rules

- Tabs use `role="tablist"` / `role="tab"` with `aria-selected`.
- Heatmap days are real buttons with counts in `aria-label`; selection uses
  `aria-pressed`.
- Turn details use native `<details>` / `<summary>`.
- Controls are at least 44px high; focus-visible outlines are explicit.
- Tables scroll inside their own container; long values wrap anywhere.
- Dark mode is scoped through `prefers-color-scheme`; the view carries its own
  stylesheet so it does not depend on a host theme bundle.
- The feedback draft lives only in the current browser session's Fulcro state.
  Switching tabs keeps it; refreshing or closing the application loses it. The
  view says this and offers copy and local-save before the user leaves.

## 8 Tests

JVM model tests:

```bash
clojure -M:test -d test/hc/client/diagnostics -n hc.client.diagnostics.model-test
```

CLJS render tests (real React element tree, no source-string checks):

```bash
rm -rf target/diag-ui
clojure -Sdeps '{:paths ["src" "resources" "test"]}' -M:cljs:client \
  -co '{:target :nodejs
        :output-to "target/diag-ui/main.cjs"
        :output-dir "target/diag-ui"
        :main hc.client.diagnostics.ui-test/-main
        :node-modules-dirs ["node_modules"]}' \
  -c hc.client.diagnostics.ui-test
printf '{"type":"commonjs"}\n' > target/diag-ui/package.json
node target/diag-ui/main.cjs
```

The render tests cover distinct counts, token provenance, separate currencies,
unestimated values, empty/read-failed states, logs, preview local-only facts,
redaction placeholders, editable preview lines, extra-material defaults,
feedback draft, and scoped responsive CSS. They also cover the corrected
unknown-field warning (only actual gaps; wording follows total provenance),
mixed cache modes, retry rows, fractional service quantities, quantity
provenance, cancelled requests with unknown usage, unknown-time records in an
otherwise empty range, non-local transfer receipts, and the no-findings
redaction warning. They also cover material field merging and checkbox state,
stale-query warnings, cached statistics under a read failure, separate estimate
and actual service amounts, unit summaries with unknown currency, explicit
`未记录` durations, receipt-derived transfer text, and removal rollback.

Earlier full-suite verification before the 0.0.1 truthfulness audit was green:
2473 tests / 16341 assertions, 0 failures / 0 errors, stable tree hash
ff39b03dba37dd493839f5c8ceba6726ca3ee571. The audit edits below were not
executed in the no-shell pass; rerun the two commands above before release.

Smoke runner (prints FOUND/MISSING and full HTML):

```bash
clojure -Sdeps '{:paths ["src" "resources" "test"]}' -M:cljs:client \
  -co '{:target :nodejs
        :output-to "target/diag-smoke/main.cjs"
        :output-dir "target/diag-smoke"
        :main hc.client.diagnostics.ui-smoke/-main
        :node-modules-dirs ["node_modules"]}' \
  -c hc.client.diagnostics.ui-smoke
printf '{"type":"commonjs"}\n' > target/diag-smoke/package.json
node target/diag-smoke/main.cjs
```

Prior integration verification reached a live `:synced` Sente session against
`hc.server.app` and observed diagnostics receipts. That run also exposed a
workbench `sync_bridge` string-reference error before the receipt listeners
ran; the client-sync owner fixed it separately. The 0.0.1 audit edits in this
file were made in a no-shell pass, so they were not re-run against a live
server. The full workbench browser build, a real diagnostic bundle export, and
E2E 01-22 remain the integration owner's gate.

### 0.0.1 truthfulness audit

- Cancelled, timed-out, and failed requests keep their confirmed usage and
  remain visible; they are warnings, not omitted rows.
- Unknown usage and unknown price are never coerced to zero. Empty ranges
  still show unknown-time records instead of hiding them behind "no data".
- `hide amounts` covers compact model-distribution labels as well as cost
  cards and request details.
- Diagnostic preview transfer claims are derived from `:transfer`; the view
  no longer hardcodes "local only" when a receipt says otherwise.
- A no-findings redaction result is described as "no known pattern", not as a
  guarantee that the prose contains no personal information.
- Rejected preview materials are listed instead of silently disappearing.
- Logs-page text now says that detailed entries are viewed through an
  explicitly included diagnostic material, not directly on the policy page.
- Feedback draft lifetime is stated accurately: it survives tab switches in
  the current browser session but not refresh or close.
- A failed log or bundle command no longer overwrites a loaded statistics view
  with a false "statistics read failed" state; the failure is shown in its own
  panel and message.
- Pending messages are cleared or replaced by the receipt: a completed
  statistics read does not keep saying "正在读取", and log/preview success
  replaces its pending text.
- Request estimates and actual charges show their currency beside the amount;
  a partial service estimate is labelled as partial.
- Editing a price clears the previous "saved" status, and starting a new
  preview or export clears the previous export receipt.
- A failed bundle edit keeps the previous preview visible under an explicit
  "not completed" panel; an optimistic line removal is rolled back.

## 9 Non-goals

- No provider or storage client.
- No hardcoded "latest price" table.
- No conversion between currencies.
- No automatic upload, public issue, or feedback submission.
- No fake zero for unknown usage, unknown price, or failed reads.
- No second source of truth for server statistics or log policy.
