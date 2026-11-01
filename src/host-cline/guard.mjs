// The unattended-exit guard on Cline (根 §2.2, §2.3).
//
// `compose/nudge.mjs` decides what a turn exit MEANS; this module says what the
// Cline hooks do about that decision, and it is the only place that does. The
// decision is pure (role + ledger + this turn's facts), so the hooks cannot
// disagree about whether a turn may stop.
//
// Which host channel can actually continue a turn was measured, not assumed:
//
//   completionGuard   the natural channel for "do not finish this run". The host
//                     reads it from `completionPolicy`, which only its OWN
//                     runtime builder writes (`runtime-builder.ts:855`, from
//                     team state and `submit_and_exit`); a plugin cannot set it,
//                     and a tool object's own field is never read.
//   afterRun result   `callAfterRunHooks` discards every hook's answer.
//   afterModel        read as `AgentStopControl` only: `{ stop, reason }` can
//                     end a run, never continue one.
//   beforeModel       `AgentBeforeModelResult.appendContext` is collected into
//                     `pendingHookContexts` and flushed as one user message at
//                     the next iteration (`flushPendingHookContexts`). This is
//                     the in-run channel that keeps the model working.
//
// So the guard is split in two, and each half does the one thing its channel
// can do:
//
//   turnExit   called from `afterModel` with the assistant message it just read.
//              A model answer with no tool call is a natural-stop attempt. When
//              the decision refuses it, the nudge is RECORDED, and its text is
//              what `beforeModel` will append.
//   requestNotice   called from `beforeModel`. It appends the recorded nudge to
//              this request — and re-decides first, so a nudge whose exit was
//              reached in the meantime (a review emptied the ledger, a hand-off
//              was submitted) is dropped instead of nagging a finished work.
//   runExit    called from `afterRun` when the run ended anyway (the host
//              stopped after `afterModel`'s turn, a worker-style finish, a
//              guard the host never consulted). It answers the decision and the
//              text, which the hook sends as a real prompt.
//
// What is NOT an exit is as important as what is:
//
//   - a worker's turn ending is its report (`not-user-facing`);
//   - a Plan's legal `S1` `ask` parks a body and hands the turn to the operator
//     (`ask-s1`), the one stop a human decides;
//   - verified completion (`verified-completion`) is the successor's own
//     judgement: Plan at 「待执行」 as a successor that handed off, Manager at
//     「已完成」.

import { evaluateTurnExit, operatorInterruptOf, STOP_REASONS } from "../compose/nudge.mjs";
import { ROLES } from "../compose/program/surface.mjs";

// The roles that face the user, by this adapter's own role names.
export const USER_FACING_ROLES = Object.freeze([ROLES.PLAN, ROLES.MANAGER]);

const userFacing = (role) => USER_FACING_ROLES.includes(role);

// The decision for this session right now, from the facts the hooks recorded.
//
// `ledger` is read fresh every time: a review in this turn may have emptied it,
// relayed it or handed off, and a decision reading a copy taken earlier would
// nudge for work already done. `askedInTurn` is the turn that is ending, so the
// caller says which turn it means — `afterModel` asks about the turn it just
// saw, `beforeModel` about the turn before this request.
export const decide = ({ session, role = session.declaredRole, askedInTurn = session.askedInTurn, interrupted = null }) => {
  const ledger = session.tenure.ledger;
  // The SIDE is the ledger's fact, not a second guess: a session whose role was
  // adopted late could otherwise be judged against the other ledger's
  // milestones, and `done` would never read as an exit. The role still decides
  // whether the agent faces the user at all.
  const side = ledger.kind === "plan" ? ROLES.PLAN : ROLES.MANAGER;
  return evaluateTurnExit({
    role: role === ROLES.PLAN || role === ROLES.MANAGER ? side : role,
    ledger,
    handedOff: session.handedOff,
    askedInTurn,
    // Both callers ask after a turn stopped: its tools have settled, and the
    // in-flight exemption would only hide the stop that already happened.
    hasInFlightToolCalls: false,
    // The operator ended this turn (a cancelled/aborted run status, or an
    // aborted assistant message). Compose decides what that means; this host
    // only says what its own record holds.
    interrupted,
  });
};

// The parts of an assistant message this module reads.
const toolCallCount = (message) => {
  if (message === null || typeof message !== "object") return 0;
  if (!Array.isArray(message.content)) return 0;
  return message.content.filter((part) => part?.type === "tool-call").length;
};

// A model answer with no tool call ends its turn. That is a natural-stop
// attempt, and 根 §2.2 refuses it unless the turn is an exit.
//
// Returns `{ attempted, decision }`. `attempted` is false when the turn still
// has work to run (a tool call) or the role does not face the user, in which
// case nothing was attempted and nothing is recorded.
export const turnExit = ({ session, role = session.declaredRole, assistantMessage = null }) => {
  if (!userFacing(role)) return { attempted: false, decision: null };
  // An assistant message the host marked aborted was stopped BY THE OPERATOR
  // (`stopReason: "aborted"` with the interrupt reason). It is an attempt, and
  // the decision says the stop is the operator's own: no nudge, and any nudge
  // recorded for the previous turn is cleared.
  const interrupted = operatorInterruptOf(assistantMessage);
  if (!interrupted && toolCallCount(assistantMessage) > 0) return { attempted: false, decision: null };
  const decision = decide({ session, role, askedInTurn: session.askedInTurn, interrupted });
  // A turn that reached an exit owes no nudge. The record from an earlier
  // refused turn may still be sitting there (a `requestNotice` refreshed it at
  // the last request, and the work was completed after that request began — the
  // hand-off, the closing review). Leaving it would make a finished work read
  // as owed, and `requestNotice` only clears it on the NEXT request, which a
  // legal stop never sends.
  if (decision.stopAllowed) {
    session.clearNudge();
    return { attempted: true, decision };
  }
  session.noteNudge({ role, reason: decision.reason, text: decision.nudge });
  return { attempted: true, decision };
};

// The nudge this request should carry, or null.
//
// A recorded nudge is only owed while the exit is still refused: the model may
// have reached it since (the empty review that advances a milestone, the
// hand-off), and nagging a finished work is the one thing worse than a silent
// stop. Re-deciding here is what makes that impossible.
//
// `askedInTurn` is the ENDED turn's fact, passed in by `beforeModel` because the
// new turn has already cleared the session's own flag: re-deciding with the new
// turn's `false` would nag a turn that legally asked.
export const requestNotice = ({ session, role = session.declaredRole, askedInTurn = session.askedInTurn }) => {
  const recorded = session.nudge;
  if (recorded === null || recorded.text === null) return null;
  const decision = decide({ session, role, askedInTurn });
  if (decision.stopAllowed) {
    session.clearNudge();
    return null;
  }
  // The record is refreshed with what is actually owed now: a reader of
  // `session.nudge` must not see a milestone or an obligation list the ledger
  // has since left behind.
  session.noteNudge({ role, reason: decision.reason, text: decision.nudge });
  return { text: decision.nudge, reason: decision.reason };
};

// What an ended run means, and the text that answers it.
//
// A run that failed is not a stop the agent chose: the host already reported it,
// and a nudge would bury the failure under an instruction the model cannot act
// on. A run the operator cancelled is the operator's own act.
//
// Every answer that allows the stop CLEARS a recorded nudge. A nudge recorded
// earlier in the same run (a natural-stop attempt `afterModel` refused) would
// otherwise survive an operator interrupt or a host failure, and
// `requestNotice` would replay it into the operator's next request — a claim
// about a stop the operator made.
export const runExit = ({ session, role = session.declaredRole, result = null }) => {
  if (!userFacing(role)) {
    session.clearNudge();
    return { stopAllowed: true, reason: STOP_REASONS.NOT_USER_FACING, nudge: null };
  }
  const status = typeof result?.status === "string" ? result.status : null;
  if (status !== null && status !== "completed") {
    // An operator cancel is named for what it is, so a reader can tell it from a
    // host failure; both stop without a nudge. (`operatorInterruptOf` reads the
    // same spelling, so `decide` would answer identically.)
    const reason = operatorInterruptOf({ status }) ? STOP_REASONS.OPERATOR_INTERRUPT : `run-${status}`;
    session.clearNudge();
    return { stopAllowed: true, reason, nudge: null };
  }
  const decision = decide({ session, role, askedInTurn: session.askedInTurn });
  if (decision.stopAllowed) session.clearNudge();
  return decision;
};

// The stop a parked `ask` owes its run (根 §2.2, §7.2 「中断（交还控制权）」).
//
// `this.ask` is `this.park` + `interrupt`. On this host the park answers its tool
// call with a receipt carrying `interrupt: true`, and that receipt is not by
// itself a stop: the host runs every tool call of the turn, then asks the model
// again. A model that emits a second call in the same turn (a second program, a
// `join`) would therefore keep the run going after the question was asked, and
// the operator's answer would arrive while the agent was still working — the
// opposite of "本回合没有后续动作，run 收尾即交还控制权".
//
// The host's own stop control is `AgentStopControl` (`{ stop, reason }`), read
// from `afterTool` and `beforeTool` and applied by throwing a
// `ControlledStopError` that ends the run with status `aborted`
// (`agent-runtime.ts` `applyStopControl`). It is used here, once, at the
// boundary the park created:
//
//   parkTurnEnded   the turn that parked has finished its tools; the run must
//                   stop now.
//   stopReceipt     the answer that stops it.
//
// The stop is claimed ONCE. The host may consult several hooks for the same
// turn (every registered `afterTool`, then the next `beforeModel`), and a second
// claim is not a second stop: it is the same turn asking twice.
export const PARK_STOP_REASON = "PARKED_ASK";

// The stop this request owes, or null. Called from `beforeModel`, which the host
// runs before every model request of the run — including the request that would
// otherwise follow the parked turn.
export const parkStop = ({ session }) => {
  if (session.parkStopOwed !== true) return null;
  if (session.claimParkStop() !== true) return null;
  return { stop: true, reason: PARK_STOP_REASON };
};

export { STOP_REASONS };
