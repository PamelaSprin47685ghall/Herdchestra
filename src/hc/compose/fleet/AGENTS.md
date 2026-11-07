# `hc.compose.fleet` — disjoint worktree fork/join

Status: implemented. Root `AGENTS.md` §2.2 and §5.3 are authoritative.

## Purpose

`hc.compose.fleet` is the mechanical fork/join runtime for a Steward mission.
It admits slices, gives every slice one worktree swarm, and returns completed
results one at a time. It does not decide what the mission should be, split
individual tasks inside a swarm, or write product code.

The package is independent of the server and client layers. It depends only on
Clojure, `hc.compose.fleet.state`, and the explicit Git adapter in
`hc.compose.fleet.git`.

## Files

| File | Responsibility |
|---|---|
| `slice.clj` | Validate the slice protocol: ids, required fields, known dependencies, acyclicity. No path policing. |
| `state.clj` | Pure fleet state machine: admission, worktree readiness, start, completion queue, join, abandon, failure, dependency propagation, fleet rows. |
| `git.clj` | `GitPort` plus the real `git-cli` adapter. All operations run the actual `git` executable. |
| `control.clj` | Effectful orchestration: worktree creation, launcher calls, blocking `join!`, master integration lock, conflict handling, retention GC. |
| `runtime.clj` | One isolated runtime per worktree: fresh `$W$`, swarm STM kernel/board (fresh or restored from a persisted board), `workspace/make-runtime` rooted at the worktree, fleet-control actor, root task. `restore!` adopts a persisted board and validates its invariants. |
| `actors.clj` | Real `hc.compose.mission.actors` lifecycle bound to one worktree: registers actual @task slots on that board, emits real `:model/start` effects with the worktree workspace, accepts model events back, and reserves tool calls before their side effects. `snapshot-state` strips live runtime objects; `restore!` reattaches persisted slot identities/contexts and fixes interrupted attempts as `:outcome-unknown`. |
| `launcher.clj` | Thin `:launch-swarm!` adapter that forwards real `:model/start` effects to an injected `:start-effect!`; it never claims to run tools. |
| `supervisor.clj` | Fleet-owned launch/event/conflict loop: indexes attempts, routes chunks/completions through actor state, appends tool results and starts the next turn, wakes idle slots, and abandons only the named swarm when no holder resolution exists. |
| `conflict.clj` | Holder conflict resolver: creates a real `:needs-token` conflict task on the worktree board, wakes the live holder, waits for the holder to finish it, then stages that worktree with real `git add -A`; only no-holder/rejection/timeout/failure abandons that swarm. |
| `router.clj` | Pure `:fleet/*` command routing to the service; no transport, epoch, Owner, or W ownership. |
| `restore.clj` | Persistable control state plus per-worktree board/actor/write-gate evidence, atomic EDN file store, structural validation, board and attempt recovery, and reattach plan. |
| `service.clj` | Application-facing per-repository/mission service: Git preflight, control, per-swarm runtimes/actors, `fork!`/`join!`/`complete!`/`abandon!`/`gc!`, synchronous runtime persistence, real fleet rows and normalized snapshot tables. |

## Slice shape

```clojure
{:slice-id "auth-core"
 :goal "Implement the authentication core"
 :inputs ["src/hc/auth.clj"]
 :depends-on []
 :done-when "auth tests pass"}
```

`slice.clj` checks only what the protocol can decide:

- `:slice-id` is a non-blank string or keyword and is unique in the fleet.
- `:goal` and `:done-when` are non-blank strings.
- `:inputs` is present.
- `:depends-on` is a sequential collection of known ids with no self-edge and
  no cycle.

Disjointness is a work-assignment decision. Different slices must not own the
same work, but the runtime does **not** inspect `:paths`, `:inputs`, or goals
to prove that. The worktree is the boundary. This is deliberate: a path check
would produce false positives and would pretend to be a sandbox that the
system does not provide.

## State machine

A swarm is always in one of the five public statuses from root AGENTS.md:

`:queued` → `:running` → `:joined`

with `:abandoned` or `:failed` as the other terminal outcomes.

- `fork` admits all slices as `:queued`. The control creates every worktree
  first, then marks the worktrees ready and starts only swarms whose
  dependencies are already `:joined`.
- `complete!` stores the full report and appends the swarm to the completion
  queue exactly once. A second completion is rejected.
- `join!` takes the queue head in completion order. The queue is FIFO by
  completion sequence, never by slice order, wall clock, or race outcome.
- `join!` blocks while the queue is empty and at least one swarm is still
  running. It returns `:none` when the queue is empty, no swarm is running,
  and at least one result joined. If no swarm in the latest fork joined and
  at least one ended abandoned or failed, it returns `:no-results`, as root
  AGENTS.md §2.2 requires; an earlier fork's joined result cannot mask a
  later fork's total failure. An allocation in progress is a blocking state,
  not `:none`:
  `fork!` holds a control-level allocation flag across worktree creation,
  publication, and launch, and `join!` waits on the same monitor. The flag
  is cleared and waiters are notified in `fork!`'s `finally`, including
  failure and cleanup paths. `control-test/join-blocks-while-fork-
  allocation-is-in-progress` proves the block with a deliberately stalled
  worktree creation.
  The FIFO head is also a blocking state while another `join!` holds the
  integration lock for it; `state/join-state` reports `:blocked` with the
  `:integrating` head rather than handing the same result to a second
  integrator.
- `joined-swarm` is the only transition that advances `:master-version`. It
  requires a non-blank `:ff-commit` produced by a verified fast-forward.
- `abandon!` and `fail!` name exactly one swarm. They remove that swarm's
  queued result, if any, and fail only queued dependents whose dependency can
  no longer join. They never cancel a running sibling. There is no
  `:sibling-accepted` transition and no winner/loser rule. These transitions
  change control state; the named swarm's holder owns terminating its own
  execution resource. The control sends no signal to any sibling.
- `:queued-count` is `1` while that swarm's result waits in the join queue.

## Git integration

The control never pretends Git succeeded.

- `hc.compose.fleet.git/GitPort` is the narrow interface: head lookup,
  checked-out branch lookup, ancestor check, worktree create/remove, branch
  delete for fork rollback, rebase start/continue/abort, and fast-forward
  merge.
- `git-cli` runs the real executable through `ProcessBuilder` with no shell.
  A missing executable, timeout, non-zero exit, unreadable head, or
  unverified fast-forward is structured failure data.
- `create-worktree!` verifies the new workspace's `HEAD` after
  `git worktree add`.
- `current-branch` asks `git symbolic-ref --short -q HEAD` inside the
  worktree. A detached HEAD is reported as `{:detached? true}` with no
  branch; a missing or unreadable name is structured failure data, never an
  empty branch string.
- `ff-merge!` verifies that the configured master ref is actually checked out
  and that the branch head is an ancestor of the new master head. If either
  check fails, the operation fails and master is not reported as advanced.
- `join!` runs rebase and fast-forward under one master integration lock.
  It rebases onto the latest `:master-ref` immediately before merging. If
  master moved in between, including while a holder resolves a conflict,
  it retries within `:max-rebase-rounds`.
  The lock belongs to one control instance; run one control per master
  worktree. Before rebasing, `join!` reads the worktree `HEAD`, the
  worktree's checked-out branch, and the recorded swarm branch head. The
  checked-out branch must equal the recorded swarm branch. A detached HEAD
  or a foreign branch is `:WORKTREE_BRANCH_MISMATCH` even when it points at
  the same commit as the swarm branch; a mismatch between worktree `HEAD`
  and branch head is the same failure. No rebase or fast-forward runs after
  any of these failures. Commit equality alone is not branch identity:
  `git rebase` would move the other ref and leave the swarm branch stale.
  This keeps the queue from merging a stale branch after an external
  checkout or detach changed the worktree.
- A rebase conflict calls the configured `:resolve-conflict!` callback.
  `conflict/make-board-resolver` gives the live token holder a real board
  task and waits for it to finish; after `:resolved` the control stages the
  worktree with `git add -A` and continues the rebase. A resolver does not
  have to stage. No live holder, task rejection, timeout, holder failure, or
  stage failure abandons only that swarm. Join then continues with the next
  queued result. An interrupted holder wait returns
  `:HOLDER_CONFLICT_INTERRUPTED` instead of spinning.
- A Git failure marks that swarm `:failed` and `join!` returns
  `{:fleet/error {...}}`. It does not return a result and does not invent an
  `:ff-commit`. A thread interruption is not a Git failure: it returns
  `:JOIN_INTERRUPTED`, clears only the integration claim, and leaves the
  result at the FIFO head for a later join.
- A verified fast-forward is persisted before it is published. When that
  write fails, `join!` returns
  `{:fleet/error {:code :FLEET_STATE_PERSIST_FAILED :stage :join-commit
  :git-merged? true :join-pending? true ...}}`, starts no dependent, and
  keeps the FIFO head, its report, and the real `:ff-commit` live under
  `:join-recovery`. `abandon!` and `fail!` refuse that pending join because
  the Git merge cannot be undone (`:SWARM_INTEGRATING` while the live claim
  is set, `:SWARM_JOIN_PENDING` after a restart cleared it). A later `join!`
  retries only the write; it does not rebase or fast-forward again, and each
  dependent is dispatched at most once. After a restart, a completed queue
  head whose branch head already equals the master head is finalized from
  that evidence without repeating Git. The shortcut requires a persisted
  `:join-intent` or `:join-recovery` marker; branch equality alone is not
  evidence, so a fresh branch with no commits goes through the normal
  rebase/ff path and a dirty worktree fails instead of being accepted.
- The persisted joined state marks dependents `:running` before
  `launch-many!` runs. A crash in that window can fail a never-started
  dependent explicitly as `:SWARM_IN_FLIGHT_OUTCOME_UNKNOWN` on restart.
  This is the deliberate at-most-once choice: persisting before launch never
  dispatches a dependent twice; a launch-then-persist order would.
- A failed fork never publishes its worktrees. Rollback removes each created
  worktree and the branch created for it, then reports any cleanup failure.
  A successful join or abandon keeps its branch; its worktree is retained
  until GC.
- `gc!` remains the explicit sweep. The application host owns a periodic
  scheduler (`hc.server.app.fleet-gc`) that calls it at a fixed delay; the
  default retention is seven days and the default sweep interval is one hour.
  A candidate must be a terminal swarm registered in fleet state with an
  explicit `:gc-after` deadline that has elapsed, and its workspace must
  equal the exact path this fleet allocated under `:worktree-root`. A
  missing deadline, an active swarm, or a shared path is never removed. A
  terminal record does not have to be `:worktree-ready?`; a legacy managed
  path is still collectable. A failed removal is reported and retried on the
  next sweep; the worktree is not reported as removed.

## Current-state persistence and restart

`service/start!` accepts `:state-file` (the application derives
`<data-dir>/fleet/state.edn`) or an explicit
`hc.compose.fleet.restore/SnapshotStore`. Without either it stays
in-memory; the application always supplies the file.

The snapshot is the complete authoritative control state: slices, swarms,
the FIFO completion queue and its completion sequence, the master version,
full reports, and a bounded tail of the event log (1000 events by default).
Envelope version 2 also stores `:runtimes {swarm-id runtime}`. Each runtime
carries:

- the worktree board as plain EDN: tasks, actors, claims, actions, the token
  slot (holder, term, state), waiters, inputs, ledger, comments, requests,
  counters, board version, and a bounded event tail;
- the pure actor state: slot identities, generations, private contexts, and
  active model calls (`snapshot-state` strips the live runtime, effects, and
  terminal call history);
- the last observable write-gate status (`:held?`, `:owner`, `:queue`) as
  recovery evidence; a held status is enforced by the recovery write barrier
  below, not restored as a live permit.

The live `$W$` semaphore, workspace runtime, evaluator, model handles, and
effects are not serializable and are never written. A process restart
rebuilds them; a previously held gate is recorded as
`:write-gate-recovery {:outcome-unknown? true ...}` instead of pretending a
permit survived.

Version-1 snapshots are still accepted and reported as `:legacy? true`; they
carry no runtime state. The next successful write is version 2. History
trimming never drops a swarm, a queued report, a completion sequence, the
master version, a task, a claim, an action, a token slot, or an active
model call.

Every successful control transition is persisted synchronously before the
next caller can rely on it. `fork!` is stricter: it persists the published
fork before exposing it and rolls back created worktrees when the write
fails. Join is stricter in the same way: the joined transition is persisted
before it is published, and a failed write leaves the result pending with
`:join-recovery` instead of returning `:joined`. Other transitions remain
live when a write fails and return `:persisted? false` plus
`:persist-error`; `service/status` keeps the last error. A failed
persistence never deadlocks join on a stale `:integrating?` flag: a
`:join-recovery` queue head is ready for the write-only retry.

Board and actor changes are not fleet-control transitions, but they use the
same snapshot store. `service/accept-actor-event!` (except `:chunk`),
`wake-actors!`, `continue-actor-after-tool!`, and `claim-tool-call!` persist
after the actor state and board are synchronized. Every actor
read-modify-write is serialized per swarm by the service's actor monitor;
the monitor is released before persistence, and persistence never runs
inside a model, tool, or file side effect. Direct board commands from the
conflict resolver and worktree program execution commit through the kernel's
post-CAS `:on-events` hook, which persists the same snapshot. A persistence
failure is recorded as `:persist-error`; it does not roll back a committed
board or actor transition, but the kernel command reports
`:BOARD_EVENT_PERSIST_FAILED` and `:persisted? false`. A file action never
proceeds past `:applying` on an unwritten write-ahead record. There is no cross-object transaction spanning
control, board, and actor: the control transition is the swarm-status
authority, the board is the public responsibility authority, and actor state
is reconciled to board actors on restore.

Snapshot construction and the atomic file write happen while holding the
control monitor. Board-event persistence reads the current control state and
writes under that same monitor (`persist-current-state!`), so an older
board-event snapshot cannot wait behind a newer control transition and then
overwrite it. Recovery publishes the recovered control state and runtime map
under the monitor as one critical section.

`restore/file-store` writes EDN through a temporary file, fsyncs the file and
directory, and publishes with one atomic rename. A filesystem without atomic
moves returns `:FLEET_STATE_ATOMIC_MOVE_UNSUPPORTED`; there is no partial
write fallback.

Restart runs before `start!` returns:

1. Load and validate the snapshot envelope version, mission id, worktree
   root, master ref, slice index, FIFO order, worktree descriptors, and every
   persisted board's swarm invariants.
2. Clear `:integrating?` because the old process's lock and rebase are gone;
   the service aborts a leftover rebase for a completed queue head.
3. Recover each persisted board before reattaching it. An action in
   `:applying` or `:running` becomes `:uncertain` with
   `:recovery {:state :outcome-unknown ...}`; an action in `:claimed`,
   `:preparing`, or `:waiting-gate` is removed because it never reached a
   side effect; an existing `:uncertain` action stays uncertain. Park
   registrations are cleared with one `:wake-all` event. No action is
   replayed.
4. Recover actor state. A slot or Plan worker left `:running` becomes idle,
   its call is fixed as `:outcome-unknown`, and a system note tells the next
   turn to verify the worktree and board before repeating any tool call,
   Shell session, or file application. The private context is retained;
   terminal call history is trimmed from the snapshot. Slot identities are
   reconciled with live board actors: a board actor is adopted only when the
   slot has no live identity, and adoption starts an empty context. A
   missing actor state creates fresh slots over the recovered public board;
   that is a rebirth from public work, not a replay of private memory.
5. A `:running` swarm without a completed result whose runtime snapshot and
   worktree survived stays `:running` with `:recovery {:state
   :outcome-unknown ...}`. Its restored board and actors are resumed by
   waking the live slots; the old in-flight attempt is never replayed.
   Without a recoverable runtime (legacy v1, missing snapshot, or missing
   worktree) it becomes `:failed` with
   `:SWARM_IN_FLIGHT_OUTCOME_UNKNOWN` and its queued dependents fail through
   normal dependency propagation.
6. A `:running` swarm whose result is already in the queue stays joinable;
   its board is restored as evidence but its slots are not woken, so the
   completed worktree cannot change before Git integration.
7. An active swarm whose worktree disappeared fails explicitly with
   `:SWARM_WORKTREE_MISSING`, except a queue head whose `:join-recovery`
   marker proves a real fast-forward: its result stays joinable because
   master already holds the merge. A terminal swarm whose worktree is gone
   is marked removed instead of retried forever.
8. Every swarm with a persisted runtime gets that runtime back, including
   terminal and joinable rows. Only a queued swarm with no runtime snapshot
   (legacy v1 or a missing runtime entry) gets a fresh runtime, explicitly
   marked `:runtime-source :recreated`. Queued swarms then pass through
   `control/resume-ready!`; running resumed swarms are woken directly.
   If a crash lands between the recovery write and live reattach, the
   recovered snapshot has `:write-gate-recovery` but no live `:write-gate`;
   the next `recover-runtimes` preserves that evidence instead of dropping
   the only record of an old held gate.

### Recovery write barrier and write-ahead persistence

A restored runtime does not treat the fresh process-local `$W$` as free just
because the old semaphore is gone. If the persisted board has any
`:uncertain` action or the old gate status was held, `runtime/restore!`
acquires the new `$W$` as `:fleet-recovery-hold` and installs an admission
predicate. `write-gate-status` reports `:recovery-blocked? true`, the reasons,
the pending action ids, and the old-gate evidence under
`:recovery-write-gate`; after acknowledgement it reports
`:recovery-acknowledgement` with the persisted evidence, actor, and board
version. Every new file commit or managed Shell run is refused with
`:RECOVERY_UNRESOLVED` before it queues. The old action is never replayed,
resolved, or marked succeeded by the barrier.

`runtime/acknowledge-recovery!` (or the `:acknowledge-recovery` board
command) records a non-blank evidence string only after every uncertain
action has been explicitly resolved. It never changes an action phase. The
runtime releases the hold only after the service's board-event hook confirms
the acknowledgement was persisted; a failed persist leaves the hold and the
error. Gate-only recovery evidence (an old held gate with no action) needs the
same explicit acknowledgement. A snapshot written while that acknowledgement
was being persisted can record the hold marker itself; restore recognizes the
marker as its own barrier when the board already carries the acknowledgement
and no action is pending, so an acknowledged recovery does not re-block on
every later start.

File actions are write-ahead: the board event hook's return value is
observed. A `begin-action` or `:applying` transition whose event cannot be
persisted returns `:BOARD_EVENT_PERSIST_FAILED` / `:persisted? false`; the
workspace bridge does not enter apply, and the same request-id replays the
failed result instead of writing. A publication retry that finds the action
already `:finished` in memory sends a `:checkpoint` event, so it cannot claim
a durable publication from memory alone. Deterministic tests live in
`test/hc/compose/workspace_write_ahead_test.clj` and the recovery cases in
`test/hc/compose/fleet/runtime_test.clj`.

The recovery barrier gates new `$W$` acquisitions, not Git integration. A
completed swarm whose result is already in the FIFO queue stays joinable; the
completed report remains the join authority, and `join!` verifies the recorded
branch and fast-forwards only what that branch committed. An unresolved
action cannot authorize a new write, and a dirty or divergent worktree fails
Git explicitly instead of being silently accepted.

A corrupt, unsupported, or mission/worktree-mismatched file fails the start
with `:FLEET_STATE_CORRUPT`, `:INVALID_SNAPSHOT_VERSION`, or
`:FLEET_STATE_MISMATCH`. It is never treated as an empty fleet and never
overwritten.

## Automatic retention GC

`hc.server.app.fleet-gc` is the application-owned timer. One scheduler lives
while the app system is live; it calls `service/gc!` with a fixed delay, so a
wall-clock jump cannot produce a burst of sweeps. Ticks are serialized by one
lock, and a failed sweep is recorded without stopping the timer.

The app stops this scheduler before the fleet supervisor and the fleet
service. `stop!` flips the stopped flag, shuts the executor down, and waits
for an in-flight sweep; no new tick starts after it returns. `service/gc!`
also refuses a sweep after `service/stop!` set `:closed?`, so a late timer
cannot remove a worktree during shutdown. An interrupted sweep stops at the
current candidate, marks only that worktree for retry, and never reports it
as removed.

Configuration lives under `:fleet`:

```clojure
{:fleet {:master-ref "master"
         :retention-ms 604800000      ; seven days
         :gc-interval-ms 3600000      ; one hour
         :gc-run-immediately? true}}
```

`retention-ms` is the deadline after join/abandon. `gc-interval-ms` is the
fixed delay between sweeps. `gc-run-immediately?` runs one sweep at app start
so a worktree that expired while the process was down is reclaimed without
waiting a full interval.

## Per-swarm runtime and application service

`hc.compose.fleet.runtime/start!` creates the authority a swarm actually
owns:

- a fresh `hc.core.workspace-gate` write gate `$W$`, never the session's
  global gate;
- a fresh `hc.core.swarm-stm` kernel and board;
- a `hc.compose.workspace/make-runtime` rooted at that swarm's worktree;
- one fleet-control actor and one root task derived from the slice; the
  token slot starts `:free`.

The runtime does not launch a model worker and does not mark a swarm running.
That distinction is deliberate: a worktree, board, token, and `$W$` are not
evidence that an executor is alive.

`runtime/restore!` takes the persisted board and adopts it through
`swarm/create {:initial-board ...}` after checking the board invariants. It
derives the fleet-control actor and root task from that board instead of
creating them, rebuilds the process-local `$W$` and workspace runtime, and
carries the old gate status as `:write-gate-recovery` evidence. `start!` and
`restore!` both accept `:on-board-event!`; the service uses it to persist
direct board commands committed by the conflict resolver or worktree program
execution.

Runtime board publication is compare-and-set against the board read before
the mission transition: `actors/wake-all`, `accept-event`, and
`continue-after-tool!` retry the pure transition on a bounded CAS conflict,
so a concurrent direct board command is never overwritten; the fleet adapter
no longer calls `reset-board!`.

The board CAS protects only the board. Actor state is private and has no
board compare-and-set, so the service serializes every actor
read-modify-write with one monitor per swarm (`:actor-locks`).
`accept-actor-event!`, `wake-actors!`, `continue-actor-after-tool!`,
`claim-tool-call!`, and the restored wake in `launch!` re-read the current
actor state after acquiring that monitor, then publish the board CAS and the
actor replacement together in memory. The monitor map is process-local and
is never serialized. It covers no persistence and no model, tool, or file
I/O; snapshots are written after release. It is bookkeeping only:
`wake-all` still derives work from the public board, so no
central task selector appears. Concurrent completions therefore cannot lose
each other's slot context, and concurrent wakes cannot emit duplicate
`:model/start` effects for one idle slot.
A terminal for an attempt that no longer owns the slot (a newer turn, or a
turn that ended in a tool call) is recorded as `:stale-terminal` and never
clears the current attempt. `claim-tool-call!` records
`:tool-call-status :executing` before the supervisor runs the external tool;
a duplicate tool-call event is rejected as `:TOOL_CALL_ALREADY_CLAIMED`, and
a replayed tool result is `:STALE_ATTEMPT` or a no-op, never a second
`:model/start`.

`launch!` publishes the runtime and its actor before the external
launcher runs, so an asynchronous model event can find the swarm.  Its
success and failure bookkeeping updates runtime status/error keys only; it
preserves a newer actor state written by a concurrent event instead of
restoring the launch-time snapshot.

`hc.compose.fleet.service/start!` is the application-level seam. One service
belongs to one repository/mission at application assembly, not to an
individual session Owner. It preflights the real Git executable and
`:master-ref`; a missing Git repository returns an explicit error and no
service. It wraps `control` and creates a runtime before calling the
application launcher:

```clojure
(service/start!
 {:mission-id "steward"
  :repo "/srv/master-worktree"
  :worktree-root "/var/lib/herdchestra/worktrees"
  :master-ref "master"
  :launch-swarm! (fn [{:keys [swarm-id workspace slice runtime actor]}]
                   ;; runtime already owns W/board/token/workspace.
                   ;; actor carries real @task slot state and :model/start
                   ;; effects when :models was supplied.
                   ;; Start a real worker/gateway and return true or a
                   ;; non-error map.
                   ...)
  :models {:main "main-id" :task "task-id"}
  :slot-ids [:slot-a :slot-b]
  :stop-swarm! (fn [{:keys [swarm-id runtime reason]}]
                 ;; stop only this named worker, never siblings
                 ...)
  :resolve-conflict! ...
  :clock #(System/currentTimeMillis)})
```

If `:launch-swarm!` is absent or reports failure, the swarm is marked
`:failed` with `:SWARM_EXECUTOR_UNAVAILABLE`; it is never marked `:running`.
When `:models` is supplied, the service builds the actor state before the
launcher call. The launcher receives `:actor` with real `:model/start`
effects. Model chunks/terminals return through
`service/accept-actor-event!`; `service/continue-actor-after-tool!` appends
a tool result and starts the next turn; `service/wake-actors!` starts the
next turn for idle slots. Every effect returned by `service/wake-actors!`
carries its `:swarm-id`, so a caller that only has the effects (the holder
conflict resolver) can hand them to `supervisor` `:start-effects!` without
losing the swarm routing. The service map exposes `:runtime`,
`:board-snapshot`, `:wake-actors!`, `:accept-actor-event!`,
`:continue-actor-after-tool!`, `:claim-tool-call!`, and `:actor-state` for
the supervisor's `service-ref` seam. It also exposes `:gc!` so the app timer
and the router call the same service operation. The runtime board is
synchronized by the real `mission.actors` transitions. `service/abandon!`
and `service/fail!` call
`:stop-swarm!` for the named swarm only.

On restore, `reattach-runtimes!` restores every swarm with a persisted
runtime, including terminal and joinable rows. A queued swarm with a
persisted actor state reuses that state; `launch!` wakes it with
`actors/wake-all` instead of creating fresh slots. A running swarm whose
board survived is resumed the same way, while a running completed swarm is
not woken. Only a queued swarm with no runtime snapshot (legacy v1 or a
missing entry) gets `runtime/start!` and a fresh board, explicitly marked
`:runtime-source :recreated`. Runtime board/actor transitions persist through
`persist-runtime!`; `:restored` reports `:reattached`, `:resumed`,
`:runtime-errors`, `:legacy?`, `:runtime-recovery`, and the final
`:persisted?` result.

The supervisor now treats a false or error-shaped `:start-effect!` result as
`SWARM_MODEL_START_FAILED`; it does not report a launch as successful when
the model gateway returned no real handle. The app's fleet start effect
returns that failure for a thrown start or a nil handle.
The supervisor and launcher also forward `:model/cancel` effects emitted by
`mission.actors/sync-tenure` when a dead slot is reborn. The cancel function
defaults to `:start-effect!` so one host function may branch on `:type`; the
app passes the same fleet start effect for both keys. A refused cancel is
returned as `:cancel-errors` evidence but does not turn a successful start
into a launch failure, and it never synthesizes a call terminal: the
executor still owns the real settlement.
The supervisor also rejects an actor with nil state and no effects as
`SWARM_ACTOR_MISSING`, so a service started without `:models` cannot leave a
swarm marked `:running` with no real slot or model start.
A `:tool-call` event is reserved with `claim-tool-call!` only when the
attempt resolves to a live call whose `:attempt-id`, `:slot-id`, and
`:agent-id` match a running slot on a live board actor. The reservation
happens before the executor runs; a duplicate claim is rejected as
`:TOOL_CALL_ALREADY_CLAIMED`, and an unknown or stale attempt as
`:STALE_ATTEMPT` or `:UNKNOWN_ATTEMPT` before any tool, Shell, or write side
effect. A completed event that carries `:published-call-ids` is deferred for
the same reason: it does not clear the slot before the tool result is
recorded. After restore or slot rebirth, a late tool-call cannot run under a
dead identity.

The launcher receives `:runtime`; the app can also fetch it with
`service/runtime` by swarm id or `service/runtime-for-workspace` by request
workspace. The runtime map contains `:workspace-runtime` for
`workspace/run-program!`, `:kernel`, `:write-gate`, `:board`, `:token-slot`,
and `:actor`. `service/worktree-runtimes` returns the `tenure-id -> runtime`
map expected by `hc.compose.mission.worktree/execute-program!`; that adapter
selects by tenure/workspace and rejects a mismatch instead of falling back to
master.

`service/observation` returns:

```clojure
{:mission-id ... :fleet-version N :master-version N
 :swarms [row ...] :events [...]}
```

Each row is the spec fleet row plus `:board`, `:token-slot`, `:write-gate`,
and `:runtime-status`. `service/snapshot-tables` returns
`:swarm/by-id` (with each board nested), `:token-slot/by-id`,
`:write-gate/by-id`, `:fleet/version`, `:fleet/master-version`, and
`:fleet/events`. Per-board task/action/agent ids are not flattened because
they collide across worktrees; the client reads them from the nested board.
No row is synthesized when no service or no admitted slice exists.

## API

```clojure
(require '[hc.compose.fleet.control :as fleet]
         '[hc.compose.fleet.git :as fleet-git])

(def c (fleet/new-control
        {:mission-id "mission-1"
         :worktree-root "/var/lib/herdchestra/worktrees"
         :repo "/srv/herdchestra"
         :git (fleet-git/git-cli "/srv/herdchestra")
         :launch-swarm! (fn [{:keys [swarm-id workspace slice]}]
                          (start-swarm! swarm-id workspace slice))
         :resolve-conflict! (fn [{:keys [swarm-id workspace]}]
                              (ask-holder-to-resolve! swarm-id workspace))
         :clock #(System/currentTimeMillis)}))

(fleet/fork! c slices)
;; => {:ok? true
;;     :state ...
;;     :swarms [{:swarm-id "swarm-1" :slice-id "auth-core"
;;               :workspace "..." :branch "hc/mission-1/swarm-1"
;;               :status :running}]}

;; Spec-shaped wrapper: returns [{:swarm-id :slice-id :workspace} ...] or
;; :INSUFFICIENT_WORKTREES; other failures throw ex-info with full data.
(fleet/fork c slices)

;; A swarm later reports its full LLM-written report.
(fleet/complete! c "swarm-1" full-report {:board-version 42})

;; The Steward calls join until :none or :no-results.
(fleet/join! c)
;; => {:swarm-id "swarm-1" :slice-id "auth-core" :workspace "..."
;;     :report "..." :ff-commit "<real sha>" :rebase-rounds 1}
```

`join!` returns `:no-results` when every swarm in the latest fork ended
without a joined result; an earlier fork's join cannot mask a later fork's
total failure. `join!` accepts `{:timeout-ms n}` only for tests and bounded
callers. The default is unbounded, as the protocol requires. The timeout uses
monotonic time; `:clock` is for recorded timestamps and retention. A timeout
returns `{:fleet/error {:code :JOIN_TIMEOUT ...}}`; it does not fabricate
`:none`.

`fork` and `join` are the spec-shaped wrappers. `fork!` and `join!` are the
effectful forms that return complete control data. `launcher/make` builds the
thin `:launch-swarm!` adapter from an injected `:start-effect!`; it forwards
only real `:model/start` effects and reports a gateway rejection as
`:SWARM_MODEL_START_FAILED`. `supervisor/make` adds the fleet-owned event
loop: it indexes attempts, routes `:chunk`/`:completed`/`:failed` through
`service/accept-actor-event!`, wakes the next idle turns, delegates
`:tool-call` to an injected worktree executor, and abandons only the named
swarm when the holder cannot resolve. Neither namespace runs a model or a
tool by itself. `launch-swarm!` must be
supplied. It signals failure by throwing, returning `false`, or returning a
map with `:status :failed` or a non-nil `:error`. A synchronous launcher
failure marks that swarm `:failed` and closes its attempt; other swarms keep
running. The control will not mark a swarm `:running` merely because a
worktree exists, and a launcher that returns `true` after its swarm already
ended is reported as a failed launch, not success. `launch-swarm!` and
`:resolve-conflict!` must not call `join!` synchronously; they run on the
control's launch or integration path. They may call `complete!` later from
the swarm's own thread.

## Fleet observation

`fleet-rows` returns the fields the Steward's Pair Hint view expects:

```clojure
{:swarm-id ... :slice-id ... :worktree ... :status ...
 :root-ready ... :root-waiting ... :root-done ...
 :queued-count ... :last-event ... :master-version ...}
```

A joined row adds the full `:report` verbatim. Reports are never truncated,
summarized, or moved to an artifact. A failed row adds `:error`; an abandoned
row adds `:abandon-reason`. `:master-version` is the current fleet master
version; `:swarm-master-version` is the last master version that swarm
integrated.

`service/observation-summary` is the bounded Steward view: it keeps only the
row fields above, the full report for joined rows, and the event-log tail.
It never carries a nested board, task, action, or agent. The raw
`service/observation` remains the application snapshot source.

`complete!` stores the full report in the fleet state before enqueueing it.
If the host needs crash recovery, the host storage layer persists that state;
the control does not externalize or shorten the report.

Events include `:fork`, `:worktree-ready`, `:swarm-started`,
`:result-queued` (with `:board-version`), `:joined` (with `:ff-commit` and
`:rebase-rounds`), `:abandoned`, and `:failed`. Each event carries
`:event-id` and `:fleet-version`.

## Invariants

1. One slice id maps to one swarm id; one swarm id maps to one worktree.
2. A result enters the completion queue at most once.
3. `join!` takes exactly the queue head.
4. A swarm becomes `:joined` only after a real, verified fast-forward of its
   recorded branch from a worktree checked out on that same branch.
5. A terminal or failed swarm never cancels an unrelated running sibling.
6. A queued dependent starts only after every dependency is `:joined`.
7. A dependency that ends `:abandoned` or `:failed` fails only its queued
   dependents; running and unrelated swarms keep their state.
8. Reports are stored and returned in full.

A fleet instance never reuses a slice id. If every result is abandoned and
the Steward decides to re-fork the same work, it creates a new fleet/mission
identity (or admits new slice ids); this keeps one slice id mapped to one
swarm and avoids reusing a retained worktree path or branch.

## Non-goals

- Proving that slices are semantically disjoint.
- Policing writes across worktrees.
- Scheduling individual tasks inside a swarm.
- Choosing a conflict resolution strategy. The holder owns that decision.
- Reusing a swarm id or a branch after termination.
- Pretending a Git operation succeeded when the executable disagreed.

## App command surface

`hc.server.app.commands` owns the transport and epoch envelope around the
pure `hc.compose.fleet.router/handle` seam:

- All `:fleet/*` kinds are app-scoped. They use the app command epoch and the
  app receipt scope; a replay after restart returns the stored receipt
  instead of forking or joining again.
- The browser gateway forbids every fleet kind. The router accepts them only
  from `hc.server.app.commands/steward-command-envelope`, whose request has
  `:program-role :steward`, `:origin :steward-tool`, and no browser
  `:subscription-id` or `:uid`. A direct request without that envelope is
  refused as `:FLEET_FORBIDDEN`.
- `:fleet/join` blocks on a worker thread. The router returns immediately,
  stores a `:dispatching` placeholder, and completes the receipt when the
  join returns. `submit-steward-command!` waits on the Steward tool thread,
  never on an Owner mailbox.
- `:fleet/fork` returns the compact `[{:swarm-id :slice-id :workspace} ...]`
  vector; a resource shortage is an explicit `:INSUFFICIENT_WORKTREES` error.
  `:fleet/complete`/`:fleet/abandon`/`:fleet/fail` return only the control
  receipt and persistence evidence; `:fleet/gc` returns only the sweep
  summary. `:fleet/state` returns the bounded observation summary and status,
  never the full control state or a per-swarm board.
- A replay while join is in flight returns the `:dispatching` placeholder; it
  never starts a second join.

## Remaining application integration gaps

The fleet package is complete at its boundary. App assembly now merges
`service/snapshot-tables` into the component snapshot, so real fleet rows,
per-swarm boards, token slots, and write gates reach the client when they
exist. This app-side item remains:

1. `mission.actors` is reused through a minimal synthetic mission tenure in
   `fleet.actors`; a direct board-provider seam would be cleaner but is not
   required for the current lifecycle.

No item above is hidden by a synthetic fleet row: when the launcher or app
wiring is missing, the swarm is `:failed` or absent, never `:running`.

## Tests

Focused tests:

```bash
clojure -M:dev -e "(require 'hc.compose.fleet.slice-test 'hc.compose.fleet.state-test 'hc.compose.fleet.git-test 'hc.compose.fleet.control-test 'hc.compose.fleet.runtime-test 'hc.compose.fleet.actors-test 'hc.compose.fleet.launcher-test 'hc.compose.fleet.supervisor-test 'hc.compose.fleet.conflict-test 'hc.compose.fleet.router-test 'hc.compose.fleet.restore-test 'hc.compose.fleet.service-test 'hc.compose.fleet.e2e-conflict-test 'hc.compose.fleet.smoke-test) (clojure.test/run-tests 'hc.compose.fleet.slice-test 'hc.compose.fleet.state-test 'hc.compose.fleet.git-test 'hc.compose.fleet.control-test 'hc.compose.fleet.runtime-test 'hc.compose.fleet.actors-test 'hc.compose.fleet.launcher-test 'hc.compose.fleet.supervisor-test 'hc.compose.fleet.conflict-test 'hc.compose.fleet.router-test 'hc.compose.fleet.restore-test 'hc.compose.fleet.service-test 'hc.compose.fleet.e2e-conflict-test 'hc.compose.fleet.smoke-test)"
```

Focused suite: 174 tests / 1088 assertions, 0 failures, 0 errors.

The smoke namespace creates real temporary Git repositories, worktrees, a
conflicting rebase, and a fast-forward merge. It is skipped only if the host
has no `git` executable; the adapter itself reports that condition as
`:unavailable`. The runtime/service tests also use real Git worktrees and
prove that two swarms have distinct boards, token slots, and `$W$` gates,
that real `mission.actors` slots bind to the per-worktree board and emit
task-model requests with that workspace, and that a missing executor fails
explicitly instead of producing a synthetic `:running` row.
`smoke-test` proves a dirty no-commit worktree is not silently joined;
`control-test` proves a fresh branch at master goes through rebase, an
interrupted join keeps its FIFO result, and a master move during holder
resolution starts another round. `conflict-test` proves an interrupted
holder wait abandons without spinning. `state-test` covers the latest-fork
`:no-results` rule, the bounded event tail, terminal report clearing, and
the current/swarm master-version split. `router-test` proves `:fleet/state`
has no board detail and the command receipts never expose control state.
`actors-test` proves a late terminal cannot clear the next attempt and a
tool call is claimed once. `service-test` proves concurrent completions keep
both slot contexts, concurrent wakes emit one `:model/start` per idle slot,
and a supervisor `:tool-call` resolves the real `[:actors :tenures ...]`
actor shape before the executor runs. The same namespace proves a
launcher-time actor event survives launch publication. `supervisor-test`
proves a completed event that published tool calls is deferred without
clearing the slot or starting a turn. The interleaving tests hold one thread
inside the transition and assert the second thread is queued on the swarm's
actor monitor, so they do not depend on wall-clock race odds.

Branch identity is covered at two levels. `git-test` proves the adapter
reports a real attached branch, a detached HEAD, a foreign checkout, and an
unreadable directory as distinct results. `control-test` proves a detached
or foreign checkout at the same commit as the recorded branch fails before
rebase or fast-forward, and `smoke-test` repeats the detached case with real
Git and proves master does not advance.

`restore-test` covers the durable store round trip (including a report with
quotes, backslashes, newlines, and Chinese, plus a persisted runtime board),
corrupt-file refusal without overwrite, v1-as-legacy acceptance, FIFO-order
and master-version validation, invalid-board refusal, board recovery
(`:applying`/`:running` actions become `:uncertain`, pre-side-effect actions
are removed, waiters are cleared), runtime gate evidence, and persistence
failure that leaves the live control untouched. A running swarm with a
persisted runtime stays running with an `:outcome-unknown` marker; the same
swarm without a runtime snapshot still fails explicitly. `service-test`
consumes the restored state through `join!`: a persisted queue of two
completed results joins in FIFO order and advances the master version, while
a legacy in-flight swarm without runtime state becomes an explicit failure,
its dependent fails, and an independent queued swarm is reattached and
resumed. A real-Git restart test proves the board, token holder, actor
context, and in-flight call survive; the old call becomes
`:outcome-unknown`, the holder remains held, a fresh model turn starts, and
the old attempt is never replayed. `runtime-test` proves that a restored
uncertain action or held-gate evidence blocks new writes with
`:RECOVERY_UNRESOLVED` until the action is explicitly resolved and a
non-blank acknowledgement is persisted, and that the acknowledged hold does
not re-block a later start. `workspace_write_ahead_test` proves a failed
`begin-action` persistence never reaches the file applier.
`control-test` proves a fork whose
publication cannot be persisted rolls back its worktrees, and a later write
failure leaves the live fleet visible with `:persisted? false`.
`control-test` and `state-test` also prove that a verified fast-forward whose
joined write fails keeps the FIFO head, report, and real `:ff-commit`
pending, starts no dependent, and retries only the write without a second
rebase or fast-forward; `restore-test` validates and recovers that pending
marker.

`hc.server.app.fleet-gc-test` proves the app-owned timer ticks, serializes
timer and manual sweeps, records a failed sweep without stopping the timer,
and terminates on `stop!`. `hc.server.app.system-test` proves the app starts
the scheduler with the fleet service and stops it before shutdown, with
executor termination in the stop evidence.
