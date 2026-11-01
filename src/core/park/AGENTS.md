// @herdchestra/core/park — package specification.
//
// Files: `program.mjs`, `receipt.mjs`, `control.mjs`, `clock.mjs`, `errors.mjs`.
//
// ## States
//
// `running` → `waiting` → `parked` → `running`; terminal `completed` / `failed`.
// `draining` and `settling` are transitions, not stored states.
//
// - `acceptsExpressions` (getter) is true only while running or waiting, and
//   false while `draining`: a parked program is suspended, a terminal one is
//   closed, and a draining one takes no new expression (§3.3). `draining` means
//   a `drainedPromise` waiter is outstanding — not that operations are open.
// - `moveTo(next)` moves between `running` and `waiting` and clears the parked
//   site when it leaves `parked`. A terminal target and `parked` are refused:
//   a terminal state is reached only through `finish`, and `parked` only
//   through `park(site)`. On a terminal program it is a no-op returning the
//   current state, so a `finally` that runs after `finish` cannot reopen the
//   run.
// - `park(site)` refuses a terminal program, a program that already has a
//   site, and a site that is not a non-null object with one of the four known
//   reasons; it sets `parked`, counts the park and cancels the run's deadline.
// - `takeSite({ deadline })` consumes the site exactly once and sets `running`;
//   a second call is `STALE_CONTROL`. A resume that names no deadline clears
//   the old one, so a spent deadline cannot re-expire at once.
// - `finish(state, result)` sets a terminal state, clears the site, records the
//   result, cancels the deadline and resolves every `drainedPromise` waiter. A
//   second `finish` throws `INVALID_ARGUMENT`.
// - A program carries its own capability set (`capabilities`, fixed at
//   construction); that set, not a caller's argument, is what a controller must
//   hold to resume it.
//
// ## Registry (`program.mjs`)
//
// - `begin(name, { deadline })` creates a `ProgramState`; a live name throws
//   `NAME_TAKEN`.
// - `record(program, callId, payload, compute)` replays the stored receipt for
//   the same callId and payload, or throws `CALL_REPLAY_MISMATCH`.
// - `retire(name)` frees a terminal program's name; its replay records survive
//   beside every later generation retired under the same name, and
//   `replayRetired(callId, payload)` answers from the newest one that booked the
//   call id, or throws `PROGRAM_UNKNOWN`.
// - `live(name)` is the lookup; `list()` enumerates live programs in insertion
//   order.
// - `beginOperation`/`endOperation(id)`/`drainedPromise(before)` implement
//   draining. `beginOperation` returns an id; `drainedPromise(before)` resolves
//   when every operation with a smaller id has settled. A caller inside the
//   program therefore names its predecessors by identity: it never waits for its
//   own operation (the `call` self-deadlock) and never skips an earlier one.
//   `endOperation(id)` requires the id — there is no "newest open" fallback,
//   because operations complete in any order and a guess settles the wrong one.
//   `finish` releases every remaining waiter; a finished run takes no new
//   expression, so nothing it waits for can still arrive. `endOperation` after
//   `finish` is allowed so a straggling operation settles.
//
// ## Receipts (`receipt.mjs`)
//
// Every receipt has: `name`, `state`, `reason`, `payload`, `source`,
// `pendingOperations`, `command`, `delta`, `remainingWaitMs`. `reason` is one of
// `explicit`, `ask`, `command`, `sdk`. `source` is the full source with line
// numbers, or null. `command` is null when no command runs, otherwise a view
// `{ stdout, stderr, stdoutBytes, stderrBytes, durationMs, running, exitCode }`.
// `CommandView#view()` takes no arguments: it reads the view's own state, so a
// caller cannot pass a second, contradictory copy of the same facts. `end`
// freezes `durationMs`; a second `end` is `INVALID_ARGUMENT`.
// `programView` takes the reason and payload from the program's own site.
//
// **The nine keys are the contract.** `RECEIPT_FIELDS` names them in spec
// order, and a receipt never has a tenth enumerable key. Facts a host needs but
// the shape does not carry go on with `withExtra(receipt, key, value)`, which
// keeps the key non-enumerable: `Object.keys`, spread and deep equality see the
// nine fields, a caller that knows the key still reads it. Today those facts are
// `value` (the value a resumed expression receives) and `result` (the run's
// result on a final receipt).
//
// ## Resume (`control.mjs`)
//
// `ControlSurface.open(controllerId)` starts a request's observation ledger;
// `observe(controllerId, name)` records that the controller saw the target.
// `handles(controllerId)` lists that controller's unsettled parks in lexical
// order and resumes nothing.
// `unpark({ controllerId, name, value, capabilities, targetCapabilities,
// deadline })` checks observation (`STALE_CONTROL`), self-target
// (`INVALID_ARGUMENT`), existence (`PROGRAM_UNKNOWN`), capability set
// (`CAPABILITY_DENIED`), still parked (`STALE_CONTROL`), then consumes the site.
// The observation check comes first, so an unobserved self-target is
// `STALE_CONTROL`. The required set is the target program's own; a
// `targetCapabilities` argument is an override that must equal it, so a
// controller cannot state an empty set and pass. The controller MUST hold every
// capability the target requires; extra capabilities are allowed. An explicit
// park, a `where` ambiguity and an `ask` all take the injected `value`:
// `this.ask` IS `this.park({ question })` plus `interrupt`, so the operator's
// reply travels through `unpark(name, answer)` like any other injected value.
// Only a `command` site keeps its own result (`site.value`) — a command receipt
// is produced by the command, and letting a controller inject one would forge an
// execution that never happened. An `ask` site that recorded an answer and is
// resumed with none falls back to that answer; otherwise the injected value is
// what the expression receives, and an omitted value is `null` (§3.3).
//
// **The order is user-visible and fixed.** Capability is checked before
// parked-state, because §3.3 changes the state only after every check passes
// ("全部通过后把目标从 `parked` 切回 `running`"). A controller that does not hold
// the set therefore always gets `CAPABILITY_DENIED`, even for a program that has
// already resumed: it must not learn a program's state by telling
// `CAPABILITY_DENIED` from `STALE_CONTROL`.
//
// The answer is a **receipt of the resumed target**, not the parked site: the
// target's state after the resume (`running`), the consumed site's `reason` and
// `payload`, the controlling request's `remainingWaitMs`, and the value the
// parked expression receives as `value`.
//
// **Core owns the one consumption.** A host that also releases its own park
// promise must not call `takeSite` a second time: the site is already gone, and
// the second call is `STALE_CONTROL` — thrown inside a resumed body, that fails
// a run that was legitimately resumed. Release the promise and let core's
// `unpark` be the only consumer of the site.
//
// ## Deadline (`clock.mjs`)
//
// `deadlineFor(intent)` is 50 ms per UTF-8 byte of the intent, computed once at
// admission and owned by the program (`begin(name, { deadline })`). A park
// cancels it; `ControlSurface.unpark({ deadline })` hands the target the
// controlling request's new deadline, never the old one. A receipt's
// `remainingWaitMs` is what is left of that deadline (0 when cancelled).
