// `tool.hook("execute.before")` and `tool.hook("execute.after")` (§6.2-§6.2).
//
// Before a call runs, four facts are decided and nothing is written:
//   - the session still holds its tenure: a retired predecessor may not call a
//     tool at all, and an unreadable relay record refuses everybody (根 §2.4 第 3 步);
//   - the role may not use this tool at all (§7.1 layer two);
//   - the call's scope annotation is admissible (§6.2);
//   - a worker's `fission` origin is known, which the surface then re-checks.
// A refusal fails the call before its executor is reached.
//
// After a call runs, the turn settles once. The settle is bookkeeping: its
// refusal is recorded and never replaces the tool's own answer, because the
// model must still read what the tool said.
//
// The Scope book of a session is opened lazily by `state.scope()`: the first
// hook that sees a session starts it from the records on disk (§6.2).

import { refusedForRole } from "./narrowing.mjs";
import { roleOf } from "./agent-role.mjs";
import { ownsTool } from "./role-tools.mjs";
import { admitCall, settleCall } from "./scope-bridge.mjs";
import { REFUSALS, refusal } from "./refusals.mjs";
import { ADAPTER_TOOL_NAMES } from "./tool-registrations.mjs";

// Members core's `Admission` treats as exempt (§6.2). The set is listed here
// only to skip *settle*: an exempt member is not booked and writes no record.
// Admission itself is asked for every call, exempt or not: core owns the exempt
// semantics, and skipping `admit` here would turn a legal exempt call into
// `SCOPE_BUDGET_SPENT` once the budget is spent.
export const EXEMPT_TOOLS = Object.freeze(["negotiate", "askPreviousScope"]);

const bookable = (event) => !EXEMPT_TOOLS.includes(event.tool);

// The retirement gate (根 §2.4 第 3 步). It comes before every other decision: a session
// whose tenure was retired may not work, and an unreadable record means we
// cannot tell who was retired, so nobody works. `reason` is the record's own
// sentence, which names the successor and the tenure; it travels in `message`
// as well as in `reason`, because the refusal table's generic sentence ("the
// call did not run") is the one a reader sees first.
export const relayRefusal = (state, sessionID) => {
  const gate = state?.relay?.gate(sessionID) ?? null;
  if (gate === null) return null;
  return { ...refusal(gate.code, { reason: gate.reason }), message: gate.reason };
};

// The replay gate (根 §2.4 第 6 步): the same physical call id with a DIFFERENT
// payload is refused, and nothing runs.
//
// It lives here, with every other refusal, because this hook is the only seam
// that runs BEFORE the tool's executor: a refusal answered from inside `execute`
// would be a receipt that looks like a result, while a replay that changed its
// payload is not a call the adapter may perform at all. `event.input` is the
// host's undecoded input, the same shape `admitCall` reads.
//
// Only a finished call id is compared. A live one is `PROGRAM_BUSY` (the call is
// still running) and an unknown one is simply a new call.
//
// The answer is one of three facts:
//
//   "replay"   the same call id with the same payload: it performs nothing and
//              answers the original receipt, so NO gate may refuse it
//              (§2.4 第 6 步, unconditionally);
//   null       not a replay: a new call, or (with `record: false`) an id whose
//              durable line is written only once the other gates pass;
//   a refusal  the id was used differently, or cannot be shown at all.
const replayVerdict = (event, state, { record = true } = {}) => {
  if (!ADAPTER_TOOL_NAMES.includes(event.tool)) return null;
  if (typeof event.id !== "string" || event.id.length === 0) return null;
  const calls = state?.calls ?? null;
  if (calls === null) return null;
  // In this process: compare the payload.
  if (calls.retired(event.id)) {
    if (calls.matchesPayload(event.id, event.input)) return "replay";
    return refusal(REFUSALS.REPLAY_MISMATCH, { callId: event.id, tool: event.tool });
  }
  // In an earlier process: the receipt and payload lived in that process, so
  // the call can be neither replayed nor re-run (§2.4 第 6 步). The id is
  // recorded on disk before the call runs, so a crash mid-call is covered too.
  const before = calls.restoreFrom({
    id: event.id,
    // The payload is written WITH the id, so a cold start can compare it and
    // answer the mismatch exactly as this process would.
    recordCall:
      record === true && typeof state.noteCall === "function" ? (id) => state.noteCall(id, event.input) : null,
  });
  if (before.unreadable !== undefined) {
    return refusal(REFUSALS.REPLAY_LOST, {
      callId: event.id,
      tool: event.tool,
      detail: before.unreadable,
    });
  }
  if (before.restored === true) {
    // The record now holds this call: compare it like any other replay.
    if (calls.matchesPayload(event.id, event.input)) return "replay";
    return refusal(REFUSALS.REPLAY_MISMATCH, { callId: event.id, tool: event.tool });
  }
  if (before.used === true && before.receipt === null) {
    // Spent, but the call never finished: there is nothing to replay and
    // re-running would perform a call that already happened.
    return refusal(REFUSALS.REPLAY_LOST, {
      callId: event.id,
      tool: event.tool,
      detail: "the call id was recorded but its call never finished",
    });
  }
  if (before.unwritable !== undefined) {
    // A call the adapter cannot remember must not run: a restart would perform
    // it a second time, which is the one thing the rule forbids.
    return refusal(REFUSALS.REPLAY_LOST, {
      callId: event.id,
      tool: event.tool,
      detail: `the call record could not be written: ${before.unwritable}`,
    });
  }
  return null;
};

// The refusal that stops a call, or null. `role` is null for host agents the
// adapter does not own: they are left alone.
export const beforeCall = (event, { state }) => {
  // §2.4 第 6 步 first, before every other gate. A replayed call id with the
  // same payload performs nothing and answers the ORIGINAL receipt, so neither a
  // retirement (第 3 步 forbids ACTING, not replaying) nor a role change nor a
  // spent budget may turn the same physical call into a refusal. The check is
  // read-only here: a new id is recorded only after the gates below pass.
  const replay = replayVerdict(event, state, { record: false });
  if (replay === "replay") return null;
  if (replay !== null) return replay;
  // A retired tenure is more fundamental than a spent budget or a role: both
  // describe what a session may do with work it still holds.
  const retired = relayRefusal(state, event.sessionID);
  if (retired !== null) return retired;
  const role = roleOf(event.agent);
  if (role === null) return null;
  if (refusedForRole(role, event.tool)) {
    return refusal(REFUSALS.ROLE, { tool: event.tool, role });
  }
  if (ADAPTER_TOOL_NAMES.includes(event.tool) && !ownsTool(role, event.tool)) {
    return refusal(REFUSALS.ROLE, { tool: event.tool, role });
  }
  // A new call id is recorded before admission, so a crash mid-call still leaves
  // the fact that it was spent. A record that cannot be written refuses the call:
  // a restart would otherwise perform it a second time.
  const recorded = replayVerdict(event, state, { record: true });
  if (recorded === "replay") return null;
  if (recorded !== null) return recorded;
  // Every call is admitted; `Admission` decides that an exempt member needs no
  // annotation and no budget.
  return admitCall(state.scope(), event);
};

// The settle of a finished call. `turnId` is the assistant message id
// (gaps.mjs PROMPT_TURN_ID).
// The settle result is returned, not swallowed: the plugin reports it so a
// staged `meToo` and its later refusal are visible to whoever reads the call.
export const afterCall = async (event, { state, turnId, observed = null }) => {
  const role = roleOf(event.agent);
  if (role === null) return null;
  if (ADAPTER_TOOL_NAMES.includes(event.tool) && !ownsTool(role, event.tool)) return null;
  // Exempt members are not booked: core answers `exempt` and writes nothing.
  if (!bookable(event)) return null;
  return settleCall(state.scope(), event, { turnId, callId: event.id, observed });
};
