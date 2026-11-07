# hc.compose.reports — cross-Owner report delivery

This package is the pure state machine for the `:report-pending` protocol in
root `AGENTS.md` §5.3.3. It also implements the `:deliver-child-report` guard
from §5.2 and the duplicate-delivery invariant (8) from §5.2.

## Boundary

The package has no I/O, no Missionary task, no scheduler, and no store. It
models only immutable data and pure transitions. The Owner commits the
returned state. The supervisor performs the returned actions.

| Namespace | Responsibility |
|---|---|
| `hc.compose.reports.report` | Report and acknowledgement shapes. Full-inline payload rules. |
| `hc.compose.reports.delivery` | Child and supervisor side. `:report-pending` until acknowledgement. Durable record reclamation after acknowledgement. |
| `hc.compose.reports.acceptance` | Parent side. Exactly one acceptance per `:parent-call-id`. Stable duplicate acknowledgement. |

## Report record

```clojure
{:report-id "report-17"              ; non-blank string, unique
 :parent-call-id "call-9"            ; non-blank string
 :payload {:kind :llm-report         ; or :raw-tool-output
           :text "full report text"}
 :parent-session-id "s1"             ; optional cross-Owner routing
 :terminal {:status :completed}}     ; optional terminal payload
```

An LLM-written report is delivered in full. Validation refuses any truthy
`:truncated?` marker and every artifact/summary key (`:artifact-ref`,
`:artifact`, `:artifact-id`, `:artifact-uri`, `:summary`) for
`:llm-report`. Raw tool output may be truncated, but then it must state
`:original-bytes` and `:delivered-bytes`; the delivered count must equal the
UTF-8 byte length of `:text` and may not exceed the original count. Payload
validation keeps only the protocol fields, so unrelated metadata cannot
enter the durable record.

The protocol deliberately carries no live resource handle. The child fixes
its terminal state and releases runtime resources before it registers the
report. Post-ack reclamation removes the durable report record; it does not
release a process, thread, or socket. A legacy `:resource-ref` is ignored,
not stored. If a host must keep a resource until acknowledgement, that
resource belongs to the child terminal/cancel lifecycle, not this record.

When `:terminal` is present it must be a map with a keyword `:status` and
stable EDN data (no functions, threads, sockets, or atoms). The field stays
optional because the child's terminal state is fixed outside the report;
the payload carries the result text.

`:parent-session-id` is optional routing metadata. When present, the report
belongs to the Owner for that session. The sink adds `:child-session-id`
before delivery, and the parent returns it in the acknowledgement. The child
Owner uses it to route the ack back. Neither field changes the report
content or the `:parent-call-id` idempotency key.

## Child and supervisor

```clojure
(require '[hc.compose.reports.delivery :as delivery])

(def result (delivery/register delivery/empty-state report))
(def state (:state result))
(delivery/next-action state)
;; => {:action :deliver :report report}

(delivery/apply-ack state ack)
;; => {:ok? true
;;     :outcome :acknowledged
;;     :state state'
;;     :action {:action :reclaim
;;              :report-id "report-17"
;;              :parent-call-id "call-9"}}

(delivery/mark-reclaimed state' "report-17")
;; => {:ok? true :outcome :reclaimed :state state''}
```

`next-action` returns `:reclaim` before `:deliver`. Durable record
reclamation is a P1 closure duty, so it wins over new delivery.
`next-action` returns `:none` when no report responsibility remains.

Registration is idempotent for the same report. It rejects a different report
with the same `:report-id`, and it rejects a second report for the same
`:parent-call-id`. The parent accepts one report per call. A second report
for that call could never close. After acknowledgement and reclamation, the
report-id and parent-call-id become tombstones; a late registration is a
terminal refusal (`:REPORT_ID_CLOSED` or `:PARENT_CALL_CLOSED` with
`:terminal? true`), not a new pending report that the parent can only reject
as a conflict.

`apply-ack` moves a pending report to `:acknowledged`. The pending obligation
is then cleared, but the record remains until `mark-reclaimed`. This closes
the crash window between clearing `:report-pending` and removing the record.
A repeated acknowledgement with the same `:report-id`, `:parent-call-id`,
and `:status` is `:duplicate`; routing metadata such as
`:child-session-id` is merged separately and never changes the idempotency
key. An acknowledgement for an already reclaimed report is
`:unknown-report`, which is a safe no-op.

## Parent

```clojure
(require '[hc.compose.reports.acceptance :as acceptance])

(def result (acceptance/accept-report acceptance/empty-state report))
;; => {:ok? true
;;     :outcome :accepted
;;     :append? true
;;     :ack {:report-id "report-17"
;;           :parent-call-id "call-9"
;;           :status :accepted}
;;     :state parent-state}
```

The first acceptance stores the full payload and returns `:append? true`. A
duplicate delivery returns the same acknowledgement and `:append? false`, so
a tool result is never appended twice. A different report for the same
parent call returns `:REPORT_CONFLICT` and leaves the state unchanged. The
child keeps its pending record, so the report is not lost. A redelivery with
the same `:report-id` but a changed payload or terminal is also
`:REPORT_CONFLICT` (`:reason :report-content-mismatch`); idempotency never
hides changed content.

Pass `{:call-active? false}` for a call that no longer exists. A closed call
must not receive a late result. The parent returns `:parent-terminated` and
stores the report for audit only. `acceptance/terminate` closes the whole
tenure and skips every pending projection. A call whose owning run is
`:cancelling` or terminal is also closed to new reports; the Owner computes
that from the call and run nodes before calling `accept-report`.

The acceptance transition is the durable decision. Commit it before the
acknowledgement leaves the Owner. The conversation append is a separate
projection:

```clojure
(acceptance/pending-projections parent-state)
;; => [{:parent-call-id "call-9" :payload {...} ...}]

(acceptance/mark-projected parent-state "call-9")
;; => {:ok? true :outcome :projected :state parent-state'}
```

After a crash between acceptance and append, `pending-projections` lists the
exact payloads to project. Do not drop an acceptance entry until the child
has received the acknowledgement. A redelivery needs the stored ack.
Commit the append and `mark-projected` in one state transition, or make the
append idempotent by `:report-id`. Otherwise a crash between append and
`mark-projected` can append the same report twice.

The projected message must keep the report `:text` verbatim. A host may add
structured metadata beside it, but it must not replace the text with an
EDN+UFSS rendering that only embeds it: exact-text consumers (restart
recovery tests and audit) rely on the raw field. The message must also be a
shape the Scope projection keeps and the model adapter accepts; a nameless
`:tool` message satisfies neither.

## Owner integration

`hc.compose.owner/report-bridge` builds the two injected functions:

```clojure
(def bridge
  (owner/report-bridge
   (fn [] @owner-ref)                 ; this Owner, or nil while starting
   (fn [session-id] (owner-for session-id))))

(owner/start-owner! {:session-id "s1"
                     :report-sink! (:report-sink! bridge)
                     :on-effect! (:on-effect! bridge)
                     ...})
```

The sink routes a report to the Owner named by `:parent-session-id`, or to
this Owner when that field is absent. It attaches `:child-session-id`. The
parent's `:report/ack` outbox entry carries that field, and the effect
handler submits `{:type :report/ack :ack ...}` back to the child Owner. A
missing parent Owner is a retryable sink failure, never a dropped report.
When a report is redelivered after the parent already accepted it, the
parent returns the stored ack and commits that ack to the outbox even
though the acceptance state does not change. The Owner must emit this
duplicate acknowledgement; otherwise the child would wait forever.

The app assembly in `hc.server.app.system` injects this bridge for every
session Owner and passes no `:deliver-report` to startup recovery. Pending
reports therefore stay open in storage and are redelivered by their Owner.

## Retry and restart

A report sink failure does not end delivery. `apply-report-delivered` moves
the claim to `:retry-wait` with a bounded exponential `:retry-at` (250 ms to
5 s). `schedule-report-retry` arms one timer; `wake-report-retry` makes the
claim pending when the timer fires. There is no attempt cap and no
`:failed` report phase. `:report-pending` remains open until the parent
acknowledges.

A successful sink call moves the claim to `:delivered` and arms an
acknowledgement timeout (5 s). If the ack does not arrive, the wake guard
returns the claim to `:pending` and the report is redelivered. The parent
returns its stored ack, so the duplicate delivery cannot append twice.

`hc.compose.owner.state/recover-state` rebuilds a `:pending` claim for every
report that is still pending. It resets a claim left in `:dispatching`,
`:delivered`, or `:retry-wait`, and removes claims for acknowledged,
reclaimed, or absent reports. A crash after the parent accepted but before
the child applied the ack therefore redelivers immediately. The parent
returns its stored acknowledgement and does not append again.

`hc.server.app.owner-store` mirrors every pending report into the storage
obligation channel in the same migration as the Owner state:

```clojure
{:obligation/id (str "report/" report-id) ; hashed when too long for a key
 :obligation/kind :report
 :open? true
 :phase :report-pending
 :parent-call-id "call-9"
 :report <full report record>
 :executor-generation 0
 :updated-at <epoch-ms>}
```

The acknowledgement commit removes the obligation with
`:obligations {:remove [...]}`. Startup recovery can therefore see an open
report even before its Owner starts. A missing parent Owner leaves the
obligation open; the Owner retries with backoff.

## Crash matrix

| Crash point | Restart behavior |
|---|---|
| Before delivery | The pending record is still in child state. `next-action` delivers it. |
| After delivery, before parent commit | The parent accepted nothing. Redelivery is the first acceptance. |
| After parent commit, before child ack | The child still holds `:report-pending`. Redelivery returns the stored ack. |
| After sink failure | The claim is `:retry-wait`; the report stays pending. A timer wakes the next attempt. |
| After sink success, before ack | The claim is `:delivered`; the ack timeout redelivers and the parent returns the stored ack. |
| After child ack commit, before reclamation | The child holds `:acknowledged`. `next-action` reclaims. |
| After reclamation, before the supervisor records it | The child still holds `:acknowledged`. Reclamation must be idempotent. |
| After parent acceptance, before conversation append | `pending-projections` returns the full payload. |
| After parent accepted but before the child ack commit | The storage obligation stays open. Restart resets the claim and redelivers. |

## Invariants

1. A report is `:pending` until a valid acknowledgement arrives. A restart
   never drops it.
2. A parent call accepts at most one report. Duplicate delivery returns the
   same acknowledgement.
3. `:append?` is true at most once per accepted report.
4. A report is never reclaimed before acknowledgement.
5. The full LLM report text is preserved from registration through
   acceptance. No step truncates, summarizes, or replaces it with a
   reference.
6. A terminated parent still acknowledges. The child can always close.
   A cancelling or terminal parent run is treated as terminated and never
   appends a late report to its conversation.
7. A sink failure never makes a report terminal. Redelivery is unbounded
   and backed off; only an acknowledgement clears `:report-pending`.
8. A restart never leaves a pending report without a `:pending` claim and
   never keeps a claim for an acknowledged, reclaimed, or absent report.
9. Cross-Owner routing metadata survives validation, acceptance, and the
   acknowledgement. It never changes the report content.

## Tests

```bash
clojure -X:test:compose:server
```

Run only the report path:

```bash
clojure -X:test:compose:server :nses '[hc.compose.reports.report-test
                                      hc.compose.reports.delivery-test
                                      hc.compose.reports.acceptance-test
                                      hc.compose.reports.protocol-test
                                      hc.compose.owner.report-integration-test
                                      hc.server.app.owner-report-restart-test]'
```

`owner-report-restart-test` uses the real Datalevin store and the real Owner
runtime. It stops the first Owner after the parent accepted and projected the
report but before the child applied the acknowledgement. The second Owner
recovers the open obligation, redelivers it, receives the stored ack, and
clears the report without appending it twice.
