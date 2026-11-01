// The unattended-turn rule on the OpenCode host (根 §2.2, §2.3).
//
// compose owns the rule (`compose/nudge.mjs`): a user-facing agent — Plan or
// Manager — has exactly two exits. Plan's `S1` `this.ask` parks the program and
// interrupts the turn, handing control to the operator; verified completion is
// the tenure that judges the previous one accepting it. Every other turn exit is
// a stop the agent did not earn, and the system answers it with a nudge.
//
// What this module owns is the two facts the host decides, and they are pure:
//
//   openObligations(ledger)  what the review ledger still owes, in the ledger's
//                            own words, from a ledger or a durable row.
//   turnExit(...)            what one natural turn exit means, with one decision
//                            per round: the second exit of the same round is
//                            the nudge already delivered, not a second one.
//
// The second fact is what keeps the controller honest. The host emits several
// completion events for one round (a failed execution, a retried one, an
// interrupt), and a nudge for each would turn one stop into a loop. A round is
// the assistant message id of the turn (`gaps.mjs PROMPT_TURN_ID`), so the
// identity is the host's own and not a counter beside it.
//
// A row that cannot name its obligations (a record from an older build, a
// record this build cannot read) is not silently read as "nothing owed": the
// nudge says the ledger could not be read, which is the truth the model needs.

import { PLAN_MILESTONES } from "../../core/review.mjs";
import { STOP_REASONS, evaluateTurnExit, verifiedCompletion } from "../../compose/nudge.mjs";

// Plan's `S1`: the one milestone an `ask` belongs to (根 §2.2).
export const ASK_MILESTONE = PLAN_MILESTONES[0];

// Why a turn exit is not even a candidate for a nudge.
export const HOLD_REASONS = Object.freeze({
  NOT_USER_FACING: "not-user-facing",
  ROUND_UNDECIDED: "round-undecided",
  ASKED: "asked",
  // The operator ended the execution (`session.execution.interrupted`): the
  // operator taking control, which §2.2's nudge must never answer.
  INTERRUPTED: "operator-interrupt",
});

const text = (value) => (typeof value === "string" ? value : null);

// The milestone of a completion event that says where the turn ended, or null.
// The host reports a failure's cause and an interrupt's reason under one key,
// so both spellings are read rather than one being assumed.
export const eventMilestone = (event) => {
  if (event === null || typeof event !== "object") return null;
  return (
    text(event.milestone) ??
    text(event.data?.milestone) ??
    text(event.data?.reason) ??
    text(event.reason) ??
    null
  );
};

// The obligations a ledger still owes, as `{ key, value }` pairs.
//
// A `ReviewLedger` answers `obligations` directly. A durable row stores the
// same pairs, and a row without the list is reported as unreadable instead of
// as empty: "no obligations" and "the obligations are unknown" are two facts,
// and a nudge that confuses them tells the model the work is done.
export const openObligations = (ledger) => {
  if (ledger === null || ledger === undefined) {
    return { ok: false, reason: "NO_LEDGER", obligations: [] };
  }
  const stored = ledger.obligations;
  if (Array.isArray(stored)) {
    return {
      ok: true,
      obligations: stored
        .filter((entry) => entry !== null && typeof entry === "object")
        .map((entry) => ({ key: String(entry.key), value: String(entry.value) })),
    };
  }
  if (Array.isArray(ledger.entries)) {
    return {
      ok: true,
      obligations: ledger.entries
        .filter((entry) => Array.isArray(entry) && entry.length >= 2)
        .map(([key, value]) => ({ key: String(key), value: String(value) })),
    };
  }
  return { ok: false, reason: "OBLIGATIONS_UNKNOWN", obligations: [] };
};

// The ledger-shaped view a completion event is judged against.
//
// The event's milestone wins over the row's, because the event is the fact the
// turn ended under. A successor's judgement (`successor` at the completion
// milestone) is only visible in the ledger, so it comes from the ledger or the
// row and never from the event.
export const ledgerViewOf = ({ ledger = null, row = null, milestone = null } = {}) => {
  const source = ledger !== null && ledger !== undefined ? ledger : (row ?? null);
  if (source === null) return null;
  const seen = openObligations(source);
  return {
    kind: text(source.kind),
    milestone: milestone ?? text(source.milestone),
    tenure: Number.isInteger(source.tenure) ? source.tenure : 0,
    successor: source.successor === true,
    obligations: seen.obligations,
    obligationsKnown: seen.ok === true,
  };
};

// One turn exit, decided once per round.
//
//   role     the agent that is ending a turn ("plan" | "manager" | ...)
//   ledger   the live `ReviewLedger` of that side, or null
//   row      the durable record of the same ledger, for a ledger not open yet
//   event    the host's completion event: `{ id?, messageID?, milestone? }`
//   asked    a legal `S1` `ask` of this session is still pending
//   lastRound  the round the previous exit of this session was booked under
//
// Returns `{ hold, round, stopAllowed, reason, nudge }`. `hold` says no exit was
// booked (a worker, an undecidable round, a pending question), and a hold never
// carries a nudge.
export const turnExit = ({
  role,
  ledger = null,
  row = null,
  event = null,
  asked = false,
  lastRound = null,
} = {}) => {
  // A blank id is not a name: the host sometimes reports whitespace where it
  // reports an id elsewhere, and a blank would slip past the "already nudged"
  // book as a distinct round every time.
  const named = (candidate) => (typeof candidate === "string" && candidate.trim().length > 0 ? candidate : null);
  // The host's completion event carries its id under `data` (the shape
  // `ctx.event.subscribe` delivers), and a caller that has already unwrapped it
  // passes it at the top level. Both spellings name the same round; reading only
  // one made every real event "round-undecided" and silenced the nudge.
  const round =
    named(event?.data?.id) ??
    named(event?.data?.messageID) ??
    named(event?.id) ??
    named(event?.messageID) ??
    null;
  if (role !== "plan" && role !== "manager") {
    return { hold: HOLD_REASONS.NOT_USER_FACING, round, stopAllowed: true, reason: STOP_REASONS.NOT_USER_FACING, nudge: null };
  }
  // A round the host did not name cannot be booked, so the de-duplication book
  // cannot apply. The EXIT is still decided: reporting an unnamed round as a
  // permitted stop would silently disable the nudge for a whole session, which
  // is the one outcome 根 §2.2 forbids.
  if (round !== null && round === lastRound) {
    return { hold: HOLD_REASONS.ROUND_UNDECIDED, round, stopAllowed: true, reason: STOP_REASONS.IN_FLIGHT, nudge: null };
  }
  const view = ledgerViewOf({ ledger, row, milestone: eventMilestone(event) });
  // The gate is the ledger's answer, not the caller's flag. A question only
  // exists in Plan's `S1` (根 §2.2), so a session that reports one anywhere else
  // is not holding a legal ask — and holding the exit for it would silently
  // disable the nudge for a whole tenure.
  if (asked === true && view !== null && view.kind === "plan" && view.milestone === ASK_MILESTONE) {
    return { hold: HOLD_REASONS.ASKED, round, stopAllowed: true, reason: STOP_REASONS.ASK_S1, nudge: null };
  }
  const decision = evaluateTurnExit({
    role,
    ledger: view,
    // Plan's exit is the hand-off: the `待执行` empty review that starts the
    // Manager. That review is a `handoff` receipt, and the host records it, so
    // the same row proves the hand-off was submitted.
    handedOff: view !== null && view.kind === "plan" && verifiedCompletion(view, { handedOff: true }),
    // An execution the OPERATOR stopped is control taken, not work abandoned.
    // The host says so in the event TYPE (`session.execution.interrupted`), which
    // `compose/nudge.mjs` `operatorInterruptOf` reads; the event's payload is not
    // a status and must not be mistaken for one. The controller never asks for a
    // nudge on that event, and a caller that does gets the same answer: the rule
    // lives in one place.
    interrupted: event?.type,
  });
  return {
    // An unnamed round is reported as such, so the caller knows the decision
    // could not be booked, while still carrying the decision itself.
    hold: round === null ? HOLD_REASONS.ROUND_UNDECIDED : null,
    round,
    stopAllowed: decision.stopAllowed,
    reason: decision.reason,
    nudge: decision.nudge,
  };
};

export { STOP_REASONS, verifiedCompletion };
