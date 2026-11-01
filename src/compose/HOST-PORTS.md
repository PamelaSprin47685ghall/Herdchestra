// Compose — host hand-off sheet (interfaces this layer now relies on).
//
// Every item below is a change on the host side of an existing port. None of
// them changes a method name or an argument: only what a step reports back. A
// host that keeps its current shape still runs; the steps it cannot perform are
// reported as failures instead of being read as success.
//
// ## fission build steps (spec §4.2)
//
// A step that cannot be performed must say so. Two shapes are accepted, and
// both fail the whole group (`abortLanes` for the group id):
//
//   throw Object.assign(new Error("..."), { code: "REASON" })
//   return { ok: false, reason: "REASON" }
//
// `{ ok: true }` or any non-receipt value means the step happened. For
// `abortLanes`, report what could not be undone:
//
//   return { ok: false, released: [agentId], orphaned: [{ laneId, agentId, jobId, reason }] }
//
// Order (spec §4.2 steps 2-6), unchanged from before:
//
//   ownerView(owner) -> createLane(lane)* -> persistGroup(group)
//   -> startLane(laneId)* -> replacePresent(group)
//
// `ownerView` returns a string or null. Anything else is a host bug and fails
// the group before a lane exists.
//
// Every `lane` handed to `createLane` (and carried in the `lanes` array handed
// to `persistGroup` and `replacePresent`) is
//
//   { index, laneId, prompt, laneCount, groupId, owner, view }
//
// `laneCount` is the group's lane count (spec §4.2 step 3): the same number in
// every lane of one group. A host prints it in the lane's start context and
// does NOT count its own lane table or use `index`/`index + 1` for it.
//
// ## relay (spec §2.4 step 3)
//
// `handOver(decision)` must report the three parts it actually performed, not
// just `ok`:
//
//   { ok: true, retired: true, barrier: true, successor: <session id or record> }
//
// `{ ok: true }` without `retired`, `barrier` and a non-empty `successor` is
// treated as a failed relay: the ledger stays with the incumbent and the review
// receipt is `{ ok: false, rejection: "RELAY_UNAVAILABLE" }`.
//
// `startManager(draft)` (spec §2.2) reports whether a Manager session exists.
// `draft` is the canonical saved draft and nothing else: a review carries no
// text, so there is no note to fall back on and no substitute for a missing
// draft.
//
//   { ok: true, sessionId: "..." }
//
// `{ ok: false, reason: "REASON" }` or no answer at all fails the handoff:
// the review receipt is `{ ok: false, kind: "handoff", rejection:
// "HANDOFF_UNAVAILABLE", started }`, the Plan stays at 「待执行」, and the next
// empty review retries. A host must not report success for a session it did not
// create.
//
// ## control (spec §3.3)
//
// `unpark(request)` receives `{ controllerId, name, value, capabilities,
// deadline }`. `capabilities` is the CONTROLLER program's own set; the required
// set is the target program's, held by core. A port that replaces the
// controller's set with `[]` removes the check: pass it through.
//
// The port returns what core's `ControlSurface#unpark` returns: the target's
// receipt (nine fields, `state: "running"`) with the value the parked
// expression receives as `value`. Read the value from `value`, not from `site`.
//
// ## fission steps have no separate time limit
//
// Composition adds no clock of its own: the only deadline is the request's,
// fixed from `intent` at admission (spec §3.3). A step that does not answer is
// a host that does not answer; composition waits and reports what the host
// reports, and never reports a step as failed because composition timed it out.
