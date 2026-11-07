# Retry Planner (`hc.compose.retry`)

Status: implemented.  The root `AGENTS.md` §5.2 (P2 guarded commands) and
§5.4 (layered retry budget) are authoritative when this note and the root
spec disagree.

## Purpose

This package is the pure decision component for **upstream-fault retry**.
It provides pure guard inputs for two P2 micro-actions:

- `:plan-retry` — a retryable failure arrives, and the call has no plan yet.
- `:claim-due-retry` — the fixed retry time has arrived.

The package does not dispatch, sleep, read a clock, generate UUIDs, write
storage, or call a model or tool.  The Owner supplies `now`, the failure,
and the next `:attempt-id`; it commits the returned state and only then
dispatches.

A **business continuation round** is not a retry.  When the board still has
work and the system starts another normal model round, do not call this
planner.  The planner accepts only an upstream failure.

## Files

| File | Responsibility |
|---|---|
| `policy.clj` | Policy validation, independent budgets, capped exponential backoff, deterministic jitter. |
| `error.clj` | Conservative error classification and remedies. |
| `planner.clj` | Call record, retry planning, due-retry claiming, guard candidates. |

## Call record

A call record is an immutable map.  The planner returns a new map.

| Key | Meaning |
|---|---|
| `:call-id` | Stable business call identity. |
| `:attempt-id` | Current attempt identity.  A retry gets a fresh value. |
| `:phase` | `:ready`, `:dispatching`, `:running`, `:retry-wait`, `:succeeded`, `:failed`, `:cancelled`, or `:outcome-unknown`. |
| `:attempts-used` | Attempts already started, including the first; 0 only while `:phase :ready`. |
| `:retries-used` | Retries already started, excluding the first. |
| `:max-attempts` | Persisted attempt cap, copied from the policy at `init-call`. |
| `:max-retries` | Persisted retry cap, copied from the policy at `init-call`. |
| `:started-at` | Epoch milliseconds when the first attempt started. |
| `:deadline` | Absolute call-wide deadline in epoch milliseconds.  Persisted recovery-facing name. |
| `:deadline-at` | Compatibility alias of `:deadline`; both always agree. |
| `:retry-at` | Absolute fixed retry time, or nil. |
| `:failure` | Terminal failure record, or nil.  Recovery reads `{:kind ... :reason ... :error ...}`. |
| `:last-error` | Last upstream failure map, for diagnostics. |
| `:decision-reason` | Last planner reason keyword. |
| `:effects` | Side-effect evidence for the failed attempt. |

`:effects` is a map:

```clojure
{:status :none | :settled | :pending | :unknown
 :repeat-safe? true | false}
```

- `:none` — no external action happened.
- `:settled` — external effects are known and complete.
- `:pending` — an effectful action is still in flight.
- `:unknown` — the external outcome is not known.

A missing or malformed `:effects` map means `{:status :unknown
:repeat-safe? false}`.  Silence is not evidence of safety.

## Policy

```clojure
{:max-attempts 4          ; includes the first attempt
 :max-retries 3           ; excludes the first attempt
 :deadline-ms 120000      ; total call deadline
 :base-delay-ms 500
 :max-delay-ms 30000
 :jitter-ms 250
 :retry-unknown-effects? false}
```

- Both attempt caps are independent.  A call can spend attempts on
  non-retry work, such as a corrected tool request.  When a caller supplies
  only one cap, the other is derived by the one-to-one relation.
- The caps and the absolute deadline are copied into the call state at
  `init-call`.  Later policy changes cannot extend an existing call.  The
  persisted `:max-attempts`, `:max-retries`, `:deadline`, and `:retry-at`
  are the recovery contract.
- Either cap exhausted means a fixed `:failed` decision.  The planner never
  resets a budget.
- The deadline is absolute and call-wide.  A retry that cannot start before
  the deadline fails now with `:deadline-before-retry`; it is not clamped.
- Backoff is `base * 2^(retry-number - 1)`, capped by `:max-delay-ms`.
  A zero base delay stays zero; the loop stops at the cap or when the
  remaining exponent is exhausted, so a huge persisted retry count cannot
  spin the pure planner.
- Jitter is deterministic by default: the hash of the printed
  `[call-id attempt-id retry-number]` modulo `:jitter-ms`.  The caller may
  pass `:jitter-offset-ms` to use a host-generated value instead.  A pure
  guard should omit that option or pass a stable value, so repeated
  evaluation of the same state gives the same candidate.
- The host MUST disable implicit HTTP-client retries.  Otherwise the
  provider can multiply the budgets that this planner believes it owns.

## Error classes

| Category | Examples | Planner action |
|---|---|---|
| `:transient` | network, timeout, 429, short 5xx | Plan a bounded retry. |
| `:permanent` | bad credentials, missing model, bad request | Fail now when effects are clean.  Do not hide a configuration error behind retries. |
| `:context` | input or context too long | Fail now when effects are clean; remedy `:adjust-context`. |
| `:model-correction` | invalid tool arguments | Fail now when effects are clean; remedy `:model-correction`.  The model may issue a new call. |
| `:reconcile` | external outcome unknown | Return `:outcome-unknown`; reconcile before any retry. |
| `:cancelled` | user cancel | Cancel; never retry. |
| `:unknown` | unclassified | Fail now when effects are clean; never retry automatically. |

Classification precedence is a partial-effect marker, then an explicit
semantic `:category`, an upstream category alias, HTTP `:status`, keyword
`:status`/`:outcome`, `:kind`, `:disposition`, and finally a declared
`:retryable? true`.  An invalid explicit category becomes `:unknown`.
HTTP 401/403/501/505 veto an optimistic transient category or alias
(`:source :status-veto`), because authentication, permission, and
unimplemented-capability failures must not be hidden by a retry.

The LangChain4j adapter emits the upstream vocabulary directly:
`:rate-limited`, `:server-error`, `:network`, `:timeout`, `:auth`,
`:permission`, `:insufficient-quota`, `:invalid-model`, `:input-too-long`,
`:content-refused`, `:tool-arguments-invalid`, `:outcome-unknown`, and
`:user-cancel`, plus `:disposition` and `:retryable?`.  The planner accepts
both that vocabulary and its semantic categories.

A failure that carries `:partial? true`, a non-empty `:published-call-ids`,
or a non-empty `:published-calls` is `:reconcile` even when the transport
error looks transient.  A model stream can publish tool calls before it
fails; repeating the model request would publish them again.

## Decisions

| Decision | State effect | Meaning |
|---|---|---|
| `:retry-wait` | `:phase :retry-wait`, fixed `:retry-at` | A retry is scheduled.  Attempt counters do not change yet. |
| `:failed` | `:phase :failed`, `:retry-at nil` | Fixed failure: non-retryable error, unknown error, budget exhausted, or deadline exhausted. |
| `:outcome-unknown` | `:phase :outcome-unknown`, `:retry-at nil` | Pending or unknown effects, or an outcome-unknown error.  The call result is fixed; reconcile externally. |
| `:cancelled` | `:phase :cancelled`, `:retry-at nil` | Operator cancellation. |
| `:error` | state unchanged | Invalid input, terminal call, or a retry plan that already exists. |

A terminal `:succeeded` or `:failed` call cannot be replanned.  A late
cancel does not rewrite a fixed success or failure.  `:outcome-unknown` is
also a fixed terminal result.  Reconcile the external state; if work must
continue, open a new call with a new `:call-id`.  The planner never
resumes a terminal call.

Cancellation normally returns `:cancelled`.  If the failed attempt has
pending or unknown effects, cancellation returns `:outcome-unknown` with
`:cancelled-with-pending-effects` or `:cancelled-with-unknown-effects`.
If the failure carries a partial-effect marker, cancellation returns
`:outcome-unknown` with `:cancelled-with-published-effects`.  The external
state stays visible; cancellation still never retries.  `resume-retry-wait`
applies the same effects-first order on restart.

## Side-effect rules

1. `:pending` always returns `:outcome-unknown` with
   `:side-effects-pending`.  Coordinate the early-started action first.
2. `:unknown` returns `:outcome-unknown` with `:side-effects-unknown` by
   default, even when `:repeat-safe?` is true.
3. Automatic retry of unknown effects is allowed only when both
   `:repeat-safe? true` and policy `:retry-unknown-effects? true`.  The
   opt-in is explicit because “idempotent” is a claim, not an observation.
4. `:none` and `:settled` permit retry when the error is transient.
5. An `:outcome-unknown` error category returns `:outcome-unknown`
   regardless of the error's apparent retryability.

## API

```clojure
(require '[hc.compose.retry.planner :as retry]
         '[hc.compose.retry.policy :as retry-policy])

(retry-policy/normalize-policy {...})
;; => {:ok? true :policy ...}

(retry/init-call {:call-id "call-1"
                  :attempt-id "attempt-1"
                  :now 1000
                  :policy {...}})
;; => {:ok? true :state ... :policy ...}

(retry/plan-candidate {:state state
                       :policy policy
                       :failure {:kind :timeout}
                       :now 1000})
;; => {:enabled? true :decision :retry-wait :reason :transient-error ...}

(retry/plan-retry {:state state
                   :policy policy
                   :failure {:kind :timeout}
                   :now 1000})
;; => {:ok? true :decision :retry-wait :state ... :retry {...}}

(retry/claim-candidate {:state scheduled :policy policy :now 1100})
;; => {:enabled? true :decision :claimed ...}

(retry/claim-due {:state scheduled
                  :policy policy
                  :now 1100
                  :attempt-id "attempt-2"})
;; => {:ok? true :decision :claimed :state ... :attempt {...}}

(retry/resume-retry-wait {:state scheduled
                          :policy policy
                          :now 1050})
;; => {:ok? true :action :rearm-timer :retry-at 1100 ...}
```

Guard functions are pure.  `:enabled?` is true only when the corresponding
action can proceed.  All other outcomes carry `:reason` and, for misuse,
`:error`.

## Invariants

1. A call has at most one fixed `:retry-at`.  A duplicate failure signal
   returns `:RETRY_ALREADY_PLANNED`; it cannot move the time.
2. `:deadline-at` never changes after `init-call`.  A retry never extends it.
3. `:attempts-used` and `:retries-used` increment only in `claim-due`, by
   exactly one each.
4. `claim-due` requires a non-blank `:attempt-id` different from the current
   one.
5. Unknown or pending effects never auto-retry, except for the explicit
   repeat-safe opt-in for unknown effects.  Pending effects have no opt-in.
6. Cancellation never schedules a retry and never rewrites a terminal call.
7. A non-retryable or unknown error never retries; with clean effects it
   fixes `:failed`, and with pending or unknown effects it fixes
   `:outcome-unknown`.
8. The planner is deterministic: no clock, no random source, no I/O.
9. `:succeeded`, `:failed`, `:cancelled`, and `:outcome-unknown` are
   terminal.  `:outcome-unknown` fixes the result; reconciliation and any
   further work happen outside that call.
10. The persisted obligation fields are `:attempts-used`, `:max-attempts`,
    `:max-retries`, `:deadline`, `:retry-at`, and `:failure`.  A recovery
    pass must not rewrite `:retry-at`; it re-arms a timer for a future
    instant, claims a due one, and fixes an exhausted one as `:failed`.
11. `resume-retry-wait` is the pure restart decision.  It never moves
    `:retry-at` and never silently keeps a `:retry-wait` whose finite budget
    is spent.
12. `claim-due` resets `:effects` to `{:status :none :repeat-safe? true}`
    for the new attempt.  Evidence from the previous attempt never leaks
    across a claimed retry.
13. `plan-retry` and `plan-candidate` require a non-empty failure map and
    return `:INVALID_FAILURE` otherwise.

## Tests

Focused smoke, without loading sibling packages:

```bash
clojure -M:dev -e "(require 'clojure.test 'hc.compose.retry.policy-test 'hc.compose.retry.error-test 'hc.compose.retry.planner-test) (let [r (clojure.test/run-tests 'hc.compose.retry.policy-test 'hc.compose.retry.error-test 'hc.compose.retry.planner-test)] (System/exit (if (zero? (+ (:fail r) (:error r))) 0 1)))"
```

Focused suite: 57 tests / 289 assertions, 0 failures, 0 errors.

The test suite covers:

- policy validation, derivation, independent budgets, capped backoff,
  deterministic jitter, and the zero-base bounded loop;
- error classification by kind, status, explicit category, adapter category
  aliases, disposition, partial-effect markers, permanent 501/505, and the
  hard-status veto over an optimistic category;
- one fixed retry per failure and duplicate-plan refusal;
- non-retryable, context, model-correction, unknown, and cancelled errors;
- pending, unknown, missing, and settled effects;
- independent attempt and retry exhaustion;
- absolute deadline and deadline-before-retry;
- due-retry claiming, fresh attempt identity, and terminal closure;
- claimed-retry effect reset and invalid failure input;
- persisted `:max-attempts`/`:max-retries`/`:deadline`/`:failure` and
  `resume-retry-wait` actions, including cancelled pending/unknown effects
  and cancelled published tool calls;
- `test.check` properties for bounded fixed retry time, single counter
  increments, and unknown-effect refusal.

## Owner and model-adapter integration

The Owner call record stores the planner state under `:retry`.  The P2
`:plan-retry` guard passes that state and `:last-error` to `plan-retry`;
the P3 claim passes a fresh scheduler attempt id to `claim-due`.

The model adapter emits upstream category names and, on a failed stream,
top-level `:partial?`, `:published-call-ids`, and `:published-calls`.  The
Owner must merge those markers into the terminal error before P2.  The
`owner/handlers.clj` failed and cancelled model-event branches now do this;
the classifier then returns `:reconcile` and no model retry occurs.

Focused integration smoke (completed Plan tenure avoids the unattended
Nudge loop; all checks returned `true`):

- transient 503: two model attempts, second succeeds, call `:succeeded`,
  retry state `{:attempts-used 2 :retries-used 1}` with persisted caps and
  deadline;
- failed stream with `:partial? true` and a published tool call: one
  attempt, call `:outcome-unknown`, no retry, failure error retains
  `:published-call-ids ["attempt-xyz/tool-0"]`;
- persisted future retry across startup: `:retry-at` and `:deadline` are
  preserved before due, the re-armed timer fires, the retry succeeds, and
  the new scheduler attempt id differs from the persisted old attempt id;
- exhausted persisted retry: startup fixes the call `:failed` with
  `:failure {:kind :retry-budget-exhausted :reason :attempts-exhausted}`
  and clears `:retry-at`.

Owner-runtime owns the state/runtime seam and has applied the two
planner-facing patches:

1. `state/recover-state` now calls `resume-retry-wait`, preserves
   `:retry-at`, re-arms a future timer through `:retry/rearm`, claims a due
   retry, and fixes an exhausted retry as `:failed`.
2. `runtime/start!` now uses a per-runtime scheduler attempt prefix, so a
   restarted scheduler cannot reissue a persisted attempt id.

### Storage boundary

`hc.server.recovery.classify` uses the same persisted field names
(`:attempts-used`, `:max-attempts`, `:deadline`, `:retry-at`) but is not a
retry scheduler.  The owner-store adapter now mirrors every call in
`:retry-wait` into a durable `:retry-call` obligation.  Recovery returns those
indexes in `:pending-retries`; startup starts the owning session, whose
runtime owns the request, effects, `:retry-at`, timer, and terminal outcome.
The owner-state commit removes the index when the call leaves `:retry-wait`.
A legacy `:retry-wait` obligation without `:retry-call` is kept open and
returned in `:blocked-retries`; a future non-owner producer must add a real
timer, claimer, and terminal closure together.  A wake alone is not a claim.

The `owner/handlers.clj` failed/cancelled model-event merge is applied and
compiles.  The focused Owner handler suite passes (18 tests / 76 assertions).

Reports are exempt: `:report-pending` is re-delivered until the parent
acknowledges and has no finite retry cap.
