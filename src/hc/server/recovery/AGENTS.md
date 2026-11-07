# Restart Recovery

Local specification for `hc.server.recovery`. Root `AGENTS.md` §7.1 (restart
windows) and §5.3 (report delivery) are authoritative. This package classifies
open obligations at startup and applies only the safe state transitions. It
never re-runs an operation that may already have happened.

## Entry point

```clojure
(recovery-entry opts)
```

Opens the store with `:create? false` unless the caller explicitly passes
`:create? true`. It verifies the full key/envelope/receipt/index integrity by
default (`:verify-integrity? true`); a corrupt store is closed, its lock is
released, and the result is blocked. It returns:

```clojure
{:status :opened :store store}
```

or, for an expected storage failure:

```clojure
{:status :blocked
 :error {:error :STORAGE_CORRUPT :message ".." :data {..}}
 :path "/data/store"
 :hint "Keep the directory for inspection and restore from a backup; it was not replaced."
 :recovery-entry {:action :operator :options [:inspect-directory :restore-from-backup :choose-another-path]}}
```

A blocked entry is the recovery entry required by §7.1. The application must
stop side-effect dispatch and show the hint. It must not initialize a new
database over the old one.

`(blocked-entry result)` converts a failed `recover-all!` result into the same
blocked shape. Use it when recovery itself fails after the store opened, so
the operator still gets `:error`, `:hint` and `:recovery-entry` instead of a
bare exception.

## Generation

`(new-generation)` returns one process-generation string. Use the same value
for `recover-all!`'s `:current-generation` and for every action obligation's
`:executor-generation` in that process. Recovery compares with `=`. A
different value means the obligation belongs to an older process and is
stale. Reports keep `:executor-generation 0` and are not generation-probed.

## Obligation contract

Recovery reads the storage obligation channel. The document is:

```clojure
{:obligation/id "call-1"          ; required
 :obligation/kind :call | :report | :retry-call
 :open? true
 :phase :ready | :dispatching | :started-unobserved | :running | :retry-wait
        | :report-pending | :report-accepted | :outcome-unknown | :closed
 :call-id ".." :attempt-id ".."
 :executor-generation 7          ; process generation that held it
 :idempotent? false              ; :dispatching may be re-dispatched
 :safe-to-repeat? false          ; re-dispatch has no duplicate side effects
 :report {:report-id .. :parent-call-id .. :payload ..}
 :retry-at 123                   ; :retry-call index or legacy retry
 :due-at 123 :updated-at 123}
```

Storage attaches `:storage/session-id` to every obligation it returns. Recovery
uses that scope to write each update back under the original key.

An owner-owned retry index carries `:obligation/kind :retry-call`,
`:phase :retry-wait` or `:ready`, `:session-id`, `:call-id`, and the retry
budget.  It is a durable index, not a self-contained retry: the owner-state
document still holds the request and effects.  Recovery returns it in
`:pending-retries`; startup starts the owning session, and the Owner runtime
re-arms or resolves it and removes the index in the same owner-state commit.

A legacy `:retry-wait` without `:retry-call` is unsupported.  Recovery keeps
it open and returns it in `:blocked-retries` (also `:attention`); it never
rewrites the phase or invents `:failed`.  The caller must block or surface an
explicit diagnostic.  A future non-owner producer must add the producer, a
real timer, a claimer, and the terminal closure together.

## Six windows

`hc.server.recovery.classify/classify` is pure. It takes the obligation and a
context of probe/delivery results.

| Phase | Condition | Action | Stored change |
|---|---|---|---|
| `:ready` | claimed, not dispatched | `:dispatch` | none; the guard claims it |
| `:dispatching` | `:idempotent? true` | `:redispatch` | `:phase :ready` |
| `:dispatching` | `:idempotent? false` | `:interrupt` | `:phase :outcome-unknown` |
| `:started-unobserved` | probe terminal | `:finished` | `:open? false`, `:phase :closed` |
| `:started-unobserved` | probe not started, safe to repeat | `:redispatch` | `:phase :ready` |
| `:started-unobserved` | otherwise | `:outcome-unknown` | `:phase :outcome-unknown` |
| `:running` | same executor generation | `:live` | none |
| `:running` | stale, probe terminal | `:finished` | `:open? false`, `:phase :closed` |
| `:running` | stale, probe not started, safe to repeat | `:redispatch` | `:phase :ready` |
| `:running` | stale, otherwise | `:outcome-unknown` | `:phase :outcome-unknown` |
| `:report-pending` | delivery accepted | `:close-report` | `:open? false`, `:phase :report-accepted` |
| `:report-pending` | not accepted | `:redeliver` | none; returned to the supervisor |
| `:report-accepted` | delivery accepted | `:close-report` | `:open? false` |
| `:ready` / `:retry-wait` | `:obligation/kind :retry-call` | `:await-retry` | none; owner-owned index, returned in `:pending-retries` |
| `:ready` | legacy due retry without `:retry-call` | `:inspect` | none; kept open, `:error :STORAGE_RETRY_UNSUPPORTED` |
| `:retry-wait` | legacy storage retry without `:retry-call` | `:inspect` | none; kept open, `:error :STORAGE_RETRY_UNSUPPORTED` |
| `:outcome-unknown` | — | `:inspect` | none; operator reconciliation |
| `:closed` / `:done` / `:failed` / `:cancelled` | — | `:none` | none |
| unknown | — | `:inspect` | none; `:UNKNOWN_OBLIGATION_PHASE` |

Rules that must survive refactoring:

1. A possibly repeated side effect becomes `:outcome-unknown` unless the
   obligation is explicitly `:safe-to-repeat?`.
2. A probe that says `:running` is not treated as `:not-started`; it becomes
   `:outcome-unknown`.
3. A stale `:running` never stays `:running`. Every branch either closes it,
   returns it to `:ready` with an explicit safety claim, or records
   `:outcome-unknown`.
4. A report is re-delivered until the parent confirms. Closing it is a
   separate, idempotent state change.
5. An unknown phase is reported for inspection, not guessed.
6. A finite retry budget that is spent is a terminal failure, not a permanent
   orphan. `:attempts-used >= :max-attempts`, or `now >= :deadline`, closes a
   `:ready` or `:dispatching` obligation as `:phase :failed`, `:open? false`,
   with `:failure {:kind :retry-budget-exhausted ...}`, except an owner-owned
   `:retry-call` index, which the Owner runtime resolves.  A `:retry-call`
   obligation is an owner-owned index: recovery leaves it in `:retry-wait`,
   returns it in `:pending-retries`, and startup starts the owning session.
   The Owner runtime owns the request, effects, `:retry-at`, timer, and
   terminal outcome, and its owner-state commit removes the index.  A legacy
   storage `:retry-wait` without `:retry-call` has no claimer or dispatcher,
   so it stays open and is reported in `:blocked-retries`.  Reports are exempt:
   `:report-pending` is re-delivered until the parent acknowledges, without a
   finite attempt count.
7. A terminal phase (`:closed`, `:done`, `:failed`, `:cancelled`) with
   `:open? true` is an inconsistent record; recovery sets `:open? false`
   instead of leaving a permanent open entry.
8. Recovery writes no `:storage/*` keys into the domain document. It preserves
   the producer's `:updated-at` (normally numeric) and records its own instant
   under `:recovery/at`. `:storage/partition` and `:storage/updated-at` are
   envelope fields, not domain fields.
9. Recovery-entry options follow the failure code. `:STORAGE_LOCKED` offers
   `:stop-other-process`/`:retry`, never `:restore-from-backup` before the
   other process stops.

## Probe and delivery

The caller may supply:

```clojure
:probe          (fn [obligation] {:state :succeeded|:failed|:cancelled|:running|:not-started|:unknown
                                  :evidence ..})
:deliver-report (fn [obligation] {:accepted? true|false})
```

Both are optional. Without a probe, the result is `:unknown` and the
conservative branch is chosen. Without a delivery function, reports stay
pending and are returned in `:pending-reports` for the supervisor.

A probe or delivery exception is captured as `:unknown`; it does not abort
recovery. The obligation then follows the conservative branch.

## Scan and apply

```clojure
(scan store {:partition "p" :current-generation 8 :now-ms .. :probe f})
=> {:status :ok :scanned n :findings [decision ..] :version partition-version
    :updates-by-scope {[partition session-id] [doc ..]}
    :applied [finding ..] :pending-reports [..] :pending-retries [..]
    :blocked-retries [..]
    :outcome-unknown [..] :attention [..]}

(recover! store opts)
=> {:status :ok :scanned n :applied [..] :pending-reports [..]
    :pending-retries [..] :blocked-retries [..] :outcome-unknown [..]
    :attention [..]
    :commits [..] :version n'}

(recover-all! store opts)
=> same shape with :status :ok, or {:status :error :error description :path ..}
```

`scan` classifies without writing. Without `:partition` (or with
`:all-partitions? true`) it scans every initialized partition. A session scan
includes partition-scoped obligations unless `:include-partition-scope? false`.
`recover!` calls `scan`, then commits each `[partition session-id]` group
through `storage/commit-migration!` with reason `:startup-recovery`. Groups
are separate transactions because the obligation key includes its partition
and session scope; each group is atomic. A crash between groups leaves the
remaining obligations open for the next recovery; no group is half-applied.

`recover-all!` is the startup wrapper. It catches expected storage failures
and returns `:status :error` with `:hint` and `:recovery-entry`; unexpected
bugs still throw. The application must stop side-effect dispatch on `:error`.

A non-empty `:pending-retries` contains owner-owned `:retry-call` indexes.
Startup must start the owning session; its runtime owns the request, effects,
`:retry-at`, timer, and terminal outcome, and its commit removes the index.
A non-empty `:blocked-retries` means the store contains a legacy storage retry
that no component can claim or dispatch.  The application must refuse to start
side-effect work and show the operator the blocked entry; the obligation is
left unchanged.  `recover-all!` does not schedule timers itself.

`(blocked-retries result)` returns the retained list and
`(blocked-retries? result)` is its predicate.  They are the reusable interface
for a future supervisor; they do not mutate or close anything.

`recover!` runs before the application accepts work. It does not open the
store; the caller uses `recovery-entry` or `open-store` first.

For a full startup scan use `recover-all!` with no `:partition` and no
`:session-id`. `:current-generation` must be the new process generation;
obligations from an older generation are the ones that may be stale.

## Application integration (current)

`hc.server.app.system/start!` opens through `recovery-entry` (integrity scan
included) and calls `recovery/recover-all!` once, before the gateway, the
HTTP listener and any session Owner.  The three-JVM
`hc.server.app.restart-smoke-test` proves this on a real process boundary:
seed (start, commit, stop), verify (start again; stale running obligations
become `:outcome-unknown`, the report obligation stays open), blocked (corrupt
one envelope; the app returns a blocked result with no gateway or URL). On `{:status :error ...}` it closes the
store and throws `:recovery-failed`; no side-effect work is dispatched. The
owner-store adapter mirrors every `:pending` child report into one
`:report-pending` obligation in the same migration as the owner-state commit,
and removes it in the same migration that records the parent ack.  It also
mirrors every call in `:retry-wait` into one `:retry-call` index and removes
it in the same commit that moves the call out of `:retry-wait`.  Startup starts
the owning session for each `:retry-call` obligation returned in
`:pending-retries`; the Owner runtime then re-arms the persisted retry timer
and claims it through the normal P2/P3 guards.  The owner report bridge
supplies `:report-sink!`/`:on-effect!`; report delivery has no finite attempt
cap.

Exact remaining limits:

- the app passes no `:probe` and no `:deliver-report` to `recover-all!`.
  `:retry-call` indexes are started by session; a legacy `:retry-wait`
  obligation without that kind is returned in `:blocked-retries` and left
  unchanged.  Owner call retries are recovered by
  `hc.compose.owner.state/recover-state` and re-armed through the Owner's
  `:retry/rearm` outbox; the owner-store commit removes the index.  A future
  non-owner retry producer must add a real timer, claimer, and terminal
  closure together.
- the report bridge returns `:PARENT_OWNER_UNAVAILABLE` when no owner exists;
  the owner claim goes to retry-wait with backoff and the storage obligation
  stays `:report-pending`.  If a bridge-shaped function is passed as
  `:deliver-report`, map that result to `{:accepted? false :reason :no-owner}`.
  The reports durable restart test proves ack removal and single delivery.
  Recovery therefore records `:outcome-unknown` for unobservable stale
  actions and leaves reports pending for the owner bridge. If no owner is
  started for a session, its report stays pending (not lost) until the owner
  is created or a `:deliver-report` returns `{:accepted? false :reason
  :no-owner}` and the supervisor queues it.
- the app should use `(new-generation)` once and store that exact value in
  every action obligation's `:executor-generation`; a different value is
  treated as stale.
- a recovery failure closes the store and throws; it does not return the
  `{:blocked? true ...}` map used for an open/integrity failure. The caller
  must surface `:recovery-failed` with the same operator options.
- `recover-all!` catches expected storage failures; an unexpected exception
  still escapes and must also block startup.

## Known limitations

1. Recovery does not itself contact the outside world. Without `:probe` every
   external state is `:unknown`, so possibly repeated actions become
   `:outcome-unknown` and are never re-run. Without `:deliver-report` reports
   stay pending and are returned to the supervisor.
2. `recover-all!` catches expected storage failures; an unexpected exception
   still escapes. The application must treat both as a startup block.
3. The integrity scan is linear in the current state. It runs at
   `recovery-entry`; a very large current state pays a startup cost.
4. Recovery commits one transaction per `[partition session-id]` scope. It is
   not a single global transaction across partitions; a crash between groups
   is handled by the next recovery.
5. The application must persist report-pending obligations and pass a
   `:deliver-report` function. Storage and recovery provide the durable
   channel and the redelivery decision, not the parent-side acknowledgement.
   Report delivery has no finite retry budget; a report stays `:report-pending`
   until the parent accepts (or reports its responsibility terminated).
6. Storage recovery does not retry and does not rewrite an owner-owned
   `:retry-call` index.  It returns the index in `:pending-retries`; startup
   starts the owning session, and `hc.compose.owner.state/recover-state`
   re-arms or resolves the retry.  A legacy `:retry-wait` without
   `:retry-call` is returned in `:blocked-retries`; the app blocks startup
   until an operator inspects it or restores a compatible backup.
7. An obligation without `:executor-generation` is treated as stale at
   startup. An obligation without `:idempotent?`/`:safe-to-repeat?` is treated
   as unsafe to repeat.

## Tests

```text
clojure -M:test:server -n hc.server.recovery-test
```

The tests cover every window, the conservative no-probe branch, grouping by
session, accepted and pending reports, and the blocked entry for a missing
path and a foreign directory. The foreign-directory test also proves that no
file is written or removed.  The retry tests cover owner-owned `:retry-call`
indexes in `:retry-wait` and `:ready`, including an exhausted budget that is
left for the Owner, plus legacy `:retry-wait` records that are blocked,
including a due retry already moved to `:ready`.
