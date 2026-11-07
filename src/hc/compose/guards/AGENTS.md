# hc.compose.guards — pure guarded-command scheduler

Specification: root `AGENTS.md` §5.2.

This package is the independent, pure scheduler for Dijkstra guarded
commands. It holds no threads, atoms, clocks, storage handles, or model
clients. The host owns the committed domain state and the atomic commit
boundary. Every public function takes values and returns values. Construction
and round-boundary functions reject invalid use with `ExceptionInfo`.

## Files

| File | Responsibility |
|---|---|
| `priority.clj` | P0–P4 rank constants, rank parsing, and same-rank rotation. |
| `claim.clj` | Claim-before-dispatch plan validation and normalization. |
| `scheduler.clj` | Wake coalescing, candidate selection, quota rounds, the commit/dispatch handshake, and the P4 blocked assertion. |

The package has no dependency on another `hc` package. `deps.edn` already
places `src` on the classpath; the `:compose` alias exists for later
Missionary supervision, not for this package.

## Guard contract

Build a guard with `scheduler/guard` or supply an equivalent map to
`scheduler/catalog`.

```clojure
{:id       :claim-tool-call
 :enabled? (fn [state] ...)   ; or (fn [state ctx] ...)
 :prepare  (fn [state] ...)}  ; or (fn [state ctx work attempt-id] ...)
```

- The one-argument forms match root `AGENTS.md` §5.2. The two-argument and
  four-argument forms add read-only context and the scheduler's attempt id.
- `:enabled?` returns nil or false when the guard is disabled. It returns a
  work description when the guard is enabled. It must not read mutable state
  or start external work.
- `:prepare` returns a plan. It must not start a future, write storage, or
  call a model.
- Guard ids are unique keywords.
- An exception from `:enabled?` or `:prepare` propagates. The host treats it
  as a guard bug, not as a rejected plan. A plan that returns data but fails
  validation becomes a `:rejected` result and consumes quota.

The catalog assigns priority. Use the map form:

```clojure
(scheduler/catalog
 {:p0-control [cancel-guard]
  :p1-closure [accept-result-guard]
  :p2-retry   [plan-retry-guard]
  :p3-progress [claim-model-guard]
  :p4-blocked [domain-dead-end-guard]})
```

A flat vector is also accepted. A guard in the vector may carry `:priority`;
without one it defaults to `:p3-progress`. A map group overrides the
guard's own `:priority`. Priority is `:p0`…`:p4` or an integer 0…4. Lower
ranks run first.

`catalog` rejects an invalid catalog with `ExceptionInfo` and
`:code :INVALID_CATALOG`. An empty catalog is valid. The scheduler then
reports a blocked assertion when the tenure is active.

## Plan contract

A state-only micro-action:

```clojure
{:state (assoc state :nudged? true)}
```

A claim that dispatches external work:

```clojure
{:state state'
 :claim {:path         [:calls "c1" :state]
         :work-id      "c1"
         :attempt-path [:calls "c1" :attempt-id]
         :from         :ready
         :to           :dispatching}
 :dispatch [{:kind :model :payload {...}}]}
```

`claim/validate-plan` enforces these rules:

1. `:state` is a map.
2. `:dispatch` is sequential. It defaults to `[]`.
3. A plan that changes nothing is rejected with `:NO_PROGRESS`.
4. A dispatch requires a claim. The error is `:DISPATCH_WITHOUT_CLAIM`.
5. `:from` is `:ready` or `:pending`. `:to` is `:claimed` or
   `:dispatching`.
6. The committed state has `:from` at `:path`. The prepared state has `:to`
   at `:path`.
7. The prepared state records the scheduler's attempt id at `:attempt-path`.
   A one-argument prepare cannot know the id. The scheduler writes the id
   into the prepared state when the path is missing or nil. An existing
   different id is rejected with `:CLAIM_ATTEMPT_NOT_RECORDED`.
8. Every dispatch effect is a map with a `:kind`. The scheduler adds the
   claim's attempt id to every effect. A conflicting id is rejected with
   `:DISPATCH_ATTEMPT_MISMATCH`.

The scheduler assigns attempt ids and transition ids. It never reuses them
within one scheduler value.

## Host protocol

1. Call `wake` when committed state changes or an external message arrives.
   Repeated wakes coalesce. The first reason wins.
2. Call `step` with the committed domain state and a read-only context.
3. If `step` returns `:prepared`, commit the transition `:state` and the
   returned scheduler value in one atomic transaction. Complete the storage
   read barrier before dispatch.
4. Call `after-commit` with the transition id. This is the only call that
   returns dispatch effects. Hand them to the execution layer.
5. Call `ack-dispatch` after the execution layer accepts the effects.
6. Call `finish-round` after `:drained`, `:idle`, `:blocked`, or
   `:quota-exhausted`.
7. If the atomic commit fails, discard the transition and the returned
   scheduler value. Re-read the committed state and call `step` again.

`step` retains the prepared candidate state in `:pending-dispatch`. If the
host returns with `:awaiting-commit`, it may commit that candidate when the
store version still matches; a version conflict discards the handshake.
`:awaiting-dispatch` means the commit succeeded but the ack did not; the
host replays `after-commit` and `ack-dispatch`, then hands the same effects
to the idempotent execution layer. `abandon-pending!` is the explicit
recovery for a handshake the host cannot finish; it clears the pending
dispatch and starts a new round, and the level-triggered guard re-prepares
the work from committed state. The host must not abandon a handshake after
a successful dispatch; use `ack-dispatch` then.

`step` refuses to prepare another micro-action while a commit or dispatch is
pending. This keeps dispatch order identical to state order.

`after-commit` is idempotent before `ack-dispatch`. A repeated call returns
the same effects with `:replayed? true`. The host must make dispatch
idempotent by `:attempt-id` because the handshake is at-least-once.

## Quota semantics

- `:quota` is a positive integer. The default is 32.
- One `step` prepares at most one micro-action.
- `:used` counts attempted micro-actions in the current round. A rejected
  plan consumes quota too, so a broken guard cannot spin.
- When `:used` reaches `:quota` and a selectable guard remains, `step`
  returns `{:status :quota-exhausted :continue? true}` and sets a
  `:quota-requeue` wakeup. The host must re-enqueue the scheduler. It must
  not sleep.
- When the quota is spent and no selectable guard remains, `step` returns
  `:drained`, or `:blocked` when the tenure is an active dead end.
- `:drained` also means the round prepared work and no guard remains.
  `:idle` means the round never had enabled work and no dead end is present.
- `finish-round` resets `:used` and the per-round rejected set. It refuses
  to run while a commit or dispatch is pending.

The quota prevents one scheduler turn from running without bound. The
same-rank cursor prevents one guard from starving its peers.

## Priority and fairness

The scheduler evaluates ranks in ascending order. It never selects a lower
rank while a higher rank is enabled. It also does not call lower-rank
`:enabled?` functions while a higher rank has an enabled candidate. This is
intentional: P0 control and cancellation preempt progress.

Inside one rank, `priority/rotate-after` starts after the guard that ran
last. Two always-enabled P3 guards alternate. A rejected guard is skipped
for the rest of the round. `finish-round` clears the skip set.

## P4 blocked assertion

`step` returns `:blocked` with `:code :BLOCKED` only when the caller says all
of these are true:

- `:tenure-terminal?` is exactly `false`.
- `:inflight?` is not `true`.
- `:timed-wait?` is not `true`.
- No guard is enabled, or every enabled guard rejected its plan.

An absent context key does not assert a dead end. The scheduler never turns
silence into a blocked diagnosis. The Owner composes `:timed-wait?` from
call retry waits and live dispatcher timers, so a pending report redelivery
or late-node timer is a legal wait, not a dead end. The diagnostic names
the reason (`:no-enabled-guard` or `:all-guards-rejected`), the round, the
used quota, and the rejected guard ids. The host must wake its handling
path. The scheduler does not wake itself, because that would spin.

## Owner integration audit

The real Owner loop lives in `hc.compose.owner.runtime`. These checks were
run against it, not only against the pure scheduler:

- `run-guards!` loops until the scheduler returns a stop status. It has no
  hidden second step cap. With `:quota 600`, one round processed 600
  terminal messages; the old 512-iteration cap would have left 88 queued.
- With `:quota 32`, 200 terminal messages were processed across visible
  `:wake :quota-requeue` messages. A rejected requeue is recorded, not
  silently dropped.
- A prepared transition commits before `after-commit` releases its dispatch
  effects. The integration test captures the model gateway start and
  verifies that the call is already `:dispatching` with the same
  `:attempt-id`.
- A prepared call effect is discarded when an operator stop is already
  pending. The guard is skipped for the rest of the round and the runtime
  emits `:dispatch/skipped` with `:reason :stop-pending`; it does not commit
  or start the claim.
- `drain-outbox!` advances `:outbox-drained` only after every pending entry
  has been processed. If an entry throws, the index stays behind and the
  idempotent cancel/release/timer-rearm effects are retried.
- A rejected guard records `:guard-rejected` through the runtime error path
  instead of silently incrementing a counter.
- Timer dispatch is idempotent by attempt id. Replaying the same handshake
  does not cancel and re-arm the timer at a later instant; a new attempt id
  replaces the old timer.
- Model snapshots carry the adapter role, not the mission role: actor turns
  use `:role :task` + `:mission-role (:worker/:holder)`; central turns use
  `:role :main` + `:mission-role (:plan/:reviewer)`. The Owner call record
  keeps the mission role.
- An active tenure with no enabled guard, no in-flight work, and no timer
  commits one `:BLOCKED` diagnostic. The diagnostic is current state, not a
  transient log line. The runtime also emits `:owner/blocked` through
  `:on-effect!`, so the handling path is woken without re-enqueueing the
  same scheduler (which would spin).
- `hc.compose.owner.mailbox/await-idle!` waits for two stable idle
  observations. A single observation could see the microsecond window after
  the mailbox polled a message but before `processing?` became true, and
  report idle while work remained.

The Owner guard catalog maps the §5.2 micro-actions as follows:

| §5.2 micro-action | Owner owner |
|---|---|
| `:accept-user-message`, `:accept-model-chunk`, `:accept-tool-request` | Message handlers create the `:ready` call; the four-step claim remains a P3 guard. |
| `:claim-model-request`, `:claim-tool-call` | P3 claim guards. |
| `:accept-call-terminal`, `:deliver-child-report`, `:trigger-relay-barrier` | P1 guards. |
| `:plan-retry`, `:claim-due-retry` | P2 guards. |
| `:inject-unattended-nudge` | P3 `:settle-turn` guard. |
| `:accept-operator-cancel` | P0 guard. |
| `:project-tool-result` | P1 guard for program/shell/workspace receipts. |

`:retire-empty-holder` is not a second Owner guard. Every board command ends
in `hc.core.swarm-stm.commands/finalize`, which applies
`maybe-retire-holder` in the same immutable board value; a guard would
duplicate that authority. `:reclaim-resources` is handled by
`dispatch/settle!`, timer removal, and `dispatch/stop!`; persistent
cancellation nodes stay as evidence by design.

Tool and report payloads are rendered with the injected
`:tool-result-renderer` when the host supplies one (app assembly can pass
`hc.server.program/render`), or with `hc.core.ufss/write-str` by default.
They are never rendered with `pr-str`. `:project-tool-result` appends
`{:role :tool :text <EDN+UFSS> :call-id ... :tool-call-id ... :member ...
 :name ... :status ... :error? ...}` and marks the call
`:result-projected?` so a replayed terminal cannot append twice. A map
result renders as a whole EDN+UFSS value even when it has a `:text` field;
only an already-string result bypasses the structured renderer. `:error?`
is true for any non-`:succeeded` state.

`:project-accepted-report` does not emit an orphan `:tool` result.  It
appends a model-legal `:user` message whose `:text` is the report's raw
`:text` (or EDN+UFSS when no text exists), with `:terminal`, `:payload`, and
`:payload-edn` attached, then marks the acceptance projected in the same
commit.  `:cancel/resource` entries prefer `:node-kind` over the legacy
`:kind`, route by `:executor-kind`/`:resource-kind`, and preserve
`:resource-id`.

## Non-goals

This package does not:

- define the session, call, attempt, or board schemas;
- own persistence, timers, or Missionary tasks;
- implement the specific micro-action catalog of §5.2;
- decide when a tenure retires or a relay barrier fires.

Those responsibilities stay with the owner and the domain guards. This
package only enforces the scheduling contract around them.

## Tests

`test/hc/compose/guards/scheduler_test.clj` contains deterministic tests and
`clojure.test.check` properties. They cover priority preemption, same-rank
fairness, quota requeue and drain, wake coalescing, claim-before-dispatch,
attempt-id uniqueness, dispatch/claim binding, and the blocked assertion.
They also cover one-argument guard compatibility, rejection diagnostics, and
state-only handshakes.

`test/hc/compose/guards/owner_integration_test.clj` starts the real Owner
mailbox, store, and guard loop. It covers quota requeue, the absence of the
512 cap, committed-before-dispatch, the blocked diagnostic, report UFSS
fallback, real shell-result projection, and a prepared call effect blocked
by a pending operator stop. `test/hc/compose/owner/dispatch_test.clj` covers
timer idempotency by attempt id and a nil model-gateway handle posting a
failed terminal. `scheduler_test.clj` covers the retained pending candidate
state and `abandon-pending!`.

Run only this package:

```bash
clojure -M:dev -e "(require 'hc.compose.guards.scheduler-test :reload-all) \
  (let [r (clojure.test/run-tests 'hc.compose.guards.scheduler-test)] \
    (System/exit (if (zero? (+ (:fail r) (:error r))) 0 1)))"
```

Run the Owner integration checks:

```bash
clojure -X:test:compose :nses '[hc.compose.guards.owner-integration-test]'
```

Run the repository test alias:

```bash
clojure -X:test
```
