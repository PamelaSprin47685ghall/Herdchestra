// The turn-exit controller: a natural stop of Plan or Manager becomes a nudge
// (根 §2.2, §2.3).
//
// The rule is compose's (`nudge.mjs`). This module is the host half: it reads
// the session's own facts, decides with the pure policy, and delivers the nudge
// through the host's own prompt queue. It never invents a state:
//
//   - the role comes from the session's agent (`roleOf`), never from the event,
//     because a completion event names the session and not the identity that
//     ended it;
//   - the ledger is the session's live `ReviewLedger` when one is open, and its
//     last durable row when none is (`rowOf`). A row that cannot name its
//     obligations is reported as unreadable, not as empty;
//   - the question is `activation.asks`: a pending `S1` ask is the one exit the
//     operator grants, and its round is never nudged;
//   - one round is booked once (`#rounds`), so the host's several completion
//     events for one turn produce one nudge.
//
// A nudge the host refuses is reported as refused. The adapter does not retry
// behind the operator's back: the receipt says what happened, and the next exit
// of that session is judged on its own facts.

import { readRecords, RECORD_TYPES } from "./session-state.mjs";
import { roleOf } from "./agent-role.mjs";
import { HOLD_REASONS, STOP_REASONS, turnExit } from "./nudge.mjs";

// The completion events that can end a model turn. `session.created` is not
// one: a new session has not worked yet.
export const TURN_END_EVENTS = Object.freeze([
  "session.execution.succeeded",
  "session.execution.failed",
  "session.execution.interrupted",
]);

// The last review row of one kind in a session's record, or null. The row is
// the ledger's durable half, so a session whose ledger has not been opened yet
// still answers what it owes.
export const rowOf = (records, kind) => {
  let saved = null;
  for (const record of records) {
    if (record?.type !== RECORD_TYPES.REVIEW) continue;
    if (record.kind !== kind) continue;
    saved = record;
  }
  return saved;
};

// What one completion event means for one session.
export const handleTurnEnd = async ({ activation, sessionID, event }) => {
  // An execution the OPERATOR stopped is not a turn exit to judge: §2.2's nudge
  // answers a model that stopped on its own, and answering a human interrupt
  // with "you are unattended, keep going" overrides the one person the standing
  // order tells the model never to wait for. The rule is compose's
  // (`evaluateTurnExit({ interrupted })`); this is the host half that refuses to
  // ask. Measured on omp 18.14.2 (2026-11-01): the same hole nudged 24 ms after
  // an operator interrupt.
  if (event?.type === "session.execution.interrupted") {
    return { nudged: false, hold: HOLD_REASONS.INTERRUPTED, role: null, sessionID };
  }
  const agent = activation.agents?.get(sessionID) ?? null;
  const role = roleOf(agent);
  if (role !== "plan" && role !== "manager") {
    return { nudged: false, hold: HOLD_REASONS.NOT_USER_FACING, role, sessionID };
  }
  // A partial activation — a test that builds only the tables it needs — must
  // not throw here: a completion event for a session this activation has no
  // state for is "unknown obligations", which the branch below already says.
  const state = activation?.states?.get?.(sessionID) ?? null;
  if (state === null) {
    // No state means no ledger and no record: the session has never made a
    // request this activation served, so its obligations are unknown and it is
    // not nudged on a guess.
    return { nudged: false, hold: HOLD_REASONS.ROUND_UNDECIDED, role, sessionID };
  }
  const kind = role === "plan" ? "plan" : "manager";
  // The live ledger is the authority while this process holds it. A side whose
  // ledger was never opened is read from its last row.
  const ledger = state.hasLedger(kind) ? state.ledgerFor(kind) : null;
  const row = ledger === null ? rowOf(readRecords(state.file), kind) : null;
  const decision = turnExit({
    role,
    ledger,
    row,
    event,
    // No ask channel means no pending question, and no round table means no
    // round was booked: both are facts a partial activation legitimately lacks.
    asked: activation?.asks?.hasPending?.(sessionID) === true,
    lastRound: activation?.nudgedRounds?.get?.(sessionID) ?? null,
  });
  if (decision.hold !== null) {
    return { nudged: false, hold: decision.hold, role, sessionID, round: decision.round, reason: decision.reason };
  }
  if (decision.stopAllowed === true) {
    // The round is booked even when the stop is earned: a later event of the
    // same turn must not be judged again.
    activation?.nudgedRounds?.set?.(sessionID, decision.round);
    return { nudged: false, role, sessionID, round: decision.round, reason: decision.reason, stopped: true };
  }
  activation?.nudgedRounds?.set?.(sessionID, decision.round);
  const sessions = activation.sessions ?? null;
  if (sessions === null || typeof sessions.prompt !== "function") {
    return {
      nudged: false,
      role,
      sessionID,
      round: decision.round,
      reason: decision.reason,
      nudge: decision.nudge,
      delivered: { ok: false, reason: "NO_SESSION_PROMPT" },
    };
  }
  try {
    // `queue` is the delivery a relay brief uses (根 §4.1): the nudge is a
    // standing instruction, not a steer of work already running, so it waits
    // for the turn the host just ended.
    await sessions.prompt({ sessionID, text: decision.nudge, delivery: "queue" });
  } catch (error) {
    return {
      nudged: false,
      role,
      sessionID,
      round: decision.round,
      reason: decision.reason,
      nudge: decision.nudge,
      delivered: { ok: false, reason: "NUDGE_NOT_DELIVERED", detail: String(error?.message ?? error) },
    };
  }
  return {
    nudged: true,
    role,
    sessionID,
    round: decision.round,
    reason: decision.reason,
    nudge: decision.nudge,
    delivered: { ok: true },
  };
};

export { STOP_REASONS, HOLD_REASONS };
