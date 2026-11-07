# hc.compose.nudge — unattended Nudge policy

> Authority: root `AGENTS.md` > `src/AGENTS.md` > `src/hc/compose/AGENTS.md` > this file.

Pure policy for the `:inject-unattended-nudge` guarded command in AGENTS.md
§2.5 and §5.2. The namespace is `hc.compose.nudge.policy`; the file is
`policy.clj`.

The package plan names the namespace `hc.compose.nudge`. This implementation
refines that name to `hc.compose.nudge.policy`, matching the sibling
multi-file compose modules (`retry`, `cancel`, `guards`, `reports`).

## Boundary

The policy reads committed state and host facts. It has no I/O, no clock, no
randomness, and no mutable state. It returns data only:

- `decide` is the guard. It returns `:enabled?`, the candidate Nudge, and a
  `:prepare` plan when enabled. When disabled, it returns a `:reason` and,
  for a legal stop, an `:exit` classification.
- `prepare` returns `{:transition ... :effects [...]}`. The host commits the
  transition in the Owner transaction, then dispatches the model request.
  An enabled plan carries `:agent-id` in the transition and the
  `:model/request` effect, so a worker Nudge cannot lose its identity.
- `observation` normalizes a relay state and host facts. A committed tenure's
  `:awaiting-user?` always wins over a caller-supplied false value.
- `observation` accepts `:tenure` or `:tenure-id` for Reviewer/worker/holder
  calls. Without either, several execution tenures produce
  `:ambiguous-tenure`; the policy never guesses.
- The execution lane is a map of tenure-id -> tenure. `observation` exposes
  `:execution-tenures`; when several handoffs exist, the host must pass the
  specific `:tenure` or `:tenure-id` for a Reviewer, worker, or holder. Plan
  handoff uses the set, not an arbitrary member.
- The host must set `:in-flight?` true while a model or tool attempt is live.
  The policy then defers Nudge instead of starting a duplicate request.

The policy never edits the relay state, the board, or the session. It does
not depend on `hc.compose.guards` or any server/client namespace.
It depends on `hc.core.relay` for centralized text and on
`hc.core.swarm-stm.board` for Phase B facts.
Centralized wording is delegated to `hc.core.relay/central-nudge-message`;
the policy keeps only the Phase B worker directive.

Enabled decision shape:

```clojure
{:enabled? true
 :kind :central | :worker
 :reason :ledger-open | :milestone-open | :global-progressable
 :message "..."
 :nudge {...}
 :prepare {:transition {...} :effects [...]}}
```

Disabled decision shape:

```clojure
{:enabled? false
 :reason <suppression-or-exit-kind>
 :exit <legal-exit-map-or-nil>
 :details <optional-map>}
```

`:nudge-id` is deterministic and includes the selected tenure id. Phase B
workers and holders also include `:agent-id` because several agents share
one execution tenure. Pass `:nudge-seq` or `:turn-id` when one tenure can
produce more than one Nudge.

## Roles and legal exits

| Role | Lane | Behavior |
|---|---|---|
| `:plan` | `:plan` | Centralized Plan. Nudge from the Plan ledger. |
| `:reviewer` | `:execution` | Centralized Reviewer. Nudge from the execution ledger. |
| `:worker` | `:execution` | Phase B swarm individual. Nudge from the board. |
| `:holder` | `:execution` | Phase B token holder. Same board policy with token responsibility. |

Plan dedicated workers, Steward, and other orchestration roles are outside
the task cycle. `decide` returns `:unsupported-role` for them.

The legal stop exits are:

- `:terminal-completed` — the tenure or milestone is `:completed`.
- `:handoff-complete` — Plan is at `:pending` and an execution tenure exists.
- `:plan-s1-ask` — Plan is at `:S1` and `:awaiting-user?` is true.
- `:execution-initial-ask` — execution initial tenure, first review, and
  `:awaiting-user?` is true, and the ledger is empty. The relay has already
  checked the workspace precondition; the policy classifies the committed
  wait and refuses to call a non-empty review the empty-ledger exit.
- `:user-wait` — any other committed `:awaiting-user?` state.

A retired tenure is not an exit. It is inactive and has a successor. An
explicit user wait is authoritative; the policy does not fight it even when
its shape is not one of the two named asks.

## Centralized Nudge

The ledger comes from the selected tenure. Plan never reads any execution
ledger, and a Reviewer reads only its own execution tenure's ledger.

`central-message` delegates to `hc.core.relay/central-nudge-message`. The
relay builder:

- starts with the §2.5 hard directive;
- lists every open ledger entry under `未完成事项：` in key order;
- for an empty ledger, states that the current milestone still needs review,
  successor acceptance, or handoff.

The policy does not own this text, so the relay and compose layers cannot
drift.

## Owner integration

The Owner is the only writer. It commits a turn record, then `settle-turn`
reads that committed record and calls `nudge/decide`. The host facts must be:

- `:role` and the exact `:agent-id` of the turn; do not replace a keyword
  identity with a role-name string.
- `:tenure-id` when more than one execution tenure exists.
- `:board` for the selected execution tenure.
- `:in-flight?` true while the same agent has a live model or tool attempt,
  or a retry planned/due for that agent. A Nudge model request must not
  start beside an unfinished tool or outrank a due retry.
- `:nudge-seq` incremented per turn, and `:turn-ended?` true only after the
  model attempt terminal has been accepted.

An enabled decision creates a ready model call for the same role, lane,
tenure, and agent. The normal claim and dispatch path owns the request. A
disabled decision clears the turn and records its exit or suppression
reason. `settle-turn` must also respect the Owner admission gate: a
cancelled or closed run never creates a Nudge call.

The Owner accepts a `:nudge-suppress` runtime hook (a function, never
committed state) for tests and automation.  `settle-turn` consults it only
after the policy enabled a Nudge and after every earlier stop gate already
answered; legal exits, blocks, and deferrals never reach it.  A non-nil
return suppresses the call: the turn is cleared and `:turn-history` records
`:reason :nudge-suppressed` with `:suppress-reason`, which is the observable
idle boundary a suite can wait on instead of `run-status`.  Suppression is
level-triggered — the next ended turn is judged again — and a throwing hook
suppresses nothing.  The app maps `:nudge-enabled? false` (config key or
`HC_NUDGE_ENABLED=false`) to this hook; the default keeps §2.5 semantics.

That gate is about dispatch, not about eligibility. A closed admission or a
cancelling run makes every call — Nudge included — undispatchable, and the
Owner records `:admission-closed` / `:operator-cancelled` in
`:turn-history` and clears the turn. Everywhere else, an active tenure that
has not reached a legal exit keeps getting Nudge, including the turn that a
previous Nudge produced. Continuation is level-triggered: the delivered
Nudge advanced `:nudge-seq`, so the next candidate has a new deterministic
id even when the ledger and the board are unchanged. No call marker may
suppress the decision.

The Owner's `settle-turn` gate decides readiness, not the policy's answer: it
checks only the ended turn plus the pending/live-call and pending-input
gates, and `:prepare` runs `decide` once. Keeping the decision out of the
enabled predicate matters because the scheduler re-evaluates it after every
commit; deciding there re-answered a legal exit once per round until the
history cap.

`settle-turn` enables on readiness, not on its own decision: it runs for a
turn that has ended, whose agent owns no pending or live call, with no
waiting user turn or uncommitted cancel. That keeps every stop answerable —
gating on the decision left non-nudgeable stops on `:turn` forever — and it
keeps the decision in `:prepare`, the only place that commits an answer.
A disabled decision therefore always clears the turn, whether the reason is
a legal exit, a dispatch gate, or a suppression, and it records that reason
once. A turn whose role or tenure the policy cannot place in a task cycle
never owed a Nudge, so it is cleared without a recorded answer.

The model request snapshot contains the visible conversation plus the Nudge
message. The Nudge is an injected system message; it does not replace the
context that the turn already had.

`accept-call-terminal-guard` sets the model turn atomically when a model
terminal is accepted. The P3 `settle-turn` guard then runs in the same Owner
round. A host may also commit an explicit `:turn/ended` record, which is the
form the manual transition tests use.

A failed model call in `:retry-wait` is still pending work for that agent.
It must count as in-flight or otherwise defer the turn; a Nudge model call
must not outrank a due retry. The automatic-path test covers the successful
terminal case; the retry ordering is an Owner-runtime responsibility.

A permanent or context failure produces `:blocked? true` with a
`:diagnostic`. The Owner appends that diagnostic, emits one
`:owner/blocked` event with `:source :nudge`, clears the turn, and creates
no model call. `hc.server.model-adapter.port/normalize-error` removes
`:error/kind` and maps an `:invalid-snapshot` reason to `:kind`/`:reason`
(for example `:unknown-field`), so `failure-class` recognizes both the
adapter ex-data shape and the normalized shape.

`:deadline-exceeded` is a deadline, not a configuration failure, and HTTP
409 is a conflict, not a permanent status; neither blocks Nudge. The retry
planner owns their budget and backoff.

An anti-loop budget applies only to turns that carry a `:failure`, is keyed
by `[tenure-id agent-id]`, and is capped at three. Exhaustion emits
`:NUDGE_BUDGET_EXHAUSTED` with the last failure and an `:owner/blocked`
event. Ordinary natural stops carry no failure, are not budgeted, and keep
receiving Nudge.

## Phase B worker Nudge

A worker Nudge is enabled only when all hold:

1. The execution tenure is in Phase B (`:swarm-phase :active`).
2. The tenure is not `:awaiting-user?`.
3. No attempt is in flight.
4. The board's progressable reasons are non-empty (the same predicate as
   `board/global-progressable?`).

The worker's own responsibility set (`board/responsibility`) is context, not
an independent trigger. A worker that owns only a waiting task or a blocked
action has no legal next step; nudging it would loop. The global progressable
gate follows §3.5: if global work exists, no worker may park.

Board facts come from the authoritative `hc.core.swarm-stm.board` functions,
not the `:derived` cache.

The message lists the worker's responsibility and the global progressable
reasons. It never mentions the review ledger. A holder is labeled `持令者`.

The pure decision carries `:agent-id`, the worker's board facts, and the
worker message. A host that creates the model call must pass `:agent-id`
into `surface/program-context`, use the worker's private actor context (not
the central `conversation-tail`), and carry the task-model role. It must
also bind the call to the actor slot (`:actor-attempt-id`) so `wake-actors`
cannot start a second concurrent turn for the same worker. A host that
prefers `wake-actors` for Phase B may use the policy message there and keep
`settle-turn` for centralized roles only.

## Suppression reasons

| Reason | Meaning |
|---|---|
| `:turn-not-ended` | The guard was evaluated before the model turn ended. |
| `:unsupported-role` | Not a Plan, Reviewer, worker, or holder. |
| `:role-lane-mismatch` | The role and lane disagree. |
| `:ambiguous-tenure` | Several execution tenures exist and no specific `:tenure` was supplied. |
| `:inactive-tenure` | The tenure is retired or absent. |
| `:in-flight-work` | A model or tool attempt is live; retry after its terminal. |
| `:reviewer-suspended-in-swarm` | Reviewer is suspended during Phase B. |
| `:not-in-swarm` | A worker role was used outside Phase B. |
| `:missing-board` | Phase B policy needs a board snapshot. |
| `:missing-agent-id` | Phase B policy needs the worker identity. |
| `:no-progressable-work` | The board has no non-blocking step; parking is legal. |

## Invariants

- No Nudge at a legal exit or while a tenure is awaiting a human answer.
- No sequence of Nudge turns ends the prompt to continue. Each settled turn,
  Nudge-authored or not, is judged again; admission and an accepted cancel
  are the only reasons a Nudge is not dispatched.
- An active centralized tenure with no legal exit, no in-flight attempt, and
  no Reviewer suspension always yields a Nudge.
- A Phase B worker yields a Nudge exactly when the tenure is active in
  Phase B, the board has global progressable work, no attempt is in flight,
  and the tenure is not awaiting a human answer.
- Central Nudge text is exactly `hc.core.relay/central-nudge-message` output,
  so it lists every open ledger entry and includes the §2.5 hard directive.
  Worker text never mentions the review ledger.
- The same observation yields the same decision and the same `:prepare` plan.

## Tests

`test/hc/compose/nudge/policy_test.clj` covers role exits, ledger isolation,
worker/holder responsibility, every suppression reason, the pure transition
plan, tenure ambiguity, and `clojure.test.check` properties for the
invariants above. Central text tests compare against
`hc.core.relay/central-nudge-message` instead of duplicating its wording.

`test/hc/compose/nudge/owner_integration_test.clj` drives a real Owner
runtime through committed `:turn/ended` records:

- Plan emits a dispatched Nudge call with the Plan tenure id;
- the S1 ask is a legal exit and creates no call;
- Reviewer emits a centralized Nudge before swarm and is suppressed during
  swarm;
- a worker with a claimed root task emits a Phase B Nudge whose recorded
  responsibility contains that task.

```sh
clojure -X:test:compose :nses '[hc.compose.nudge.policy-test hc.compose.nudge.owner-integration-test]'
```
