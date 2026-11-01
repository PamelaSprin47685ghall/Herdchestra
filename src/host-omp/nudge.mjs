// The unattended stop rule for a user-facing OMP session (root spec §2.2, §2.4,
// §5, §6), and the two host actions it needs.
//
// The RULE is compose's (`src/compose/nudge.mjs` `evaluateTurnExit`), and this
// module does not restate it: a second encoding would drift from the first the
// moment either changed, which is the defect class this adapter has already paid
// for twice. What lives here is the translation from an OMP session's own facts
// to the arguments that evaluator takes, one per-turn booking so a single turn
// cannot be nudged twice, and the delivery through the host's own queue.
//
// Nothing here mutates the ledger. A verdict is a value, so asking twice costs
// nothing and can never change a decision.

import { evaluateTurnExit, operatorInterruptOf, STANDING_ORDER } from "../compose/nudge.mjs";

// Whether a session faces the operator. A worker's turn ending is its report to
// its Manager and a lane's is its answer to `join`; neither may be nudged.
export const facesOperator = (role) => role === "plan" || role === "manager";

// One nudge per turn. A turn that ends twice (a host re-emitting `agent_end`, a
// resume, a retry) must not be nudged twice: the second nudge would read as the
// system nagging about work the model is already doing.
export class NudgeBook {
  #booked;

  constructor() {
    this.#booked = new Set();
  }

  get size() {
    return this.#booked.size;
  }

  has(turnId) {
    return this.#booked.has(turnId);
  }

  book(turnId) {
    if (this.#booked.has(turnId)) return false;
    this.#booked.add(turnId);
    return true;
  }
}

// What this turn's end means. `turnId` names the turn; `nudged` is the book
// above, and a turn already nudged answers `booked: false` so the caller does
// not deliver a second time.
//
// `askedInS1` is the one fact the evaluator cannot see for itself: a Plan that
// put a question to the operator in S1 has handed the turn back ON PURPOSE, and
// that is a legal stop.
export const turnExit = ({
  role,
  ledger = null,
  handedOff = false,
  askedInS1 = false,
  turnId = null,
  nudged = null,
  hasInFlightToolCalls = false,
  // The host's record of how the turn ended (the `turn_end` message), or
  // `true`/`false` from a caller that already read it. An operator interrupt is
  // never nudged: §2.2's nudge answers a model that stopped on its own.
  interrupted = null,
} = {}) => {
  const decision = evaluateTurnExit({
    role,
    ledger,
    handedOff,
    askedInTurn: askedInS1 === true,
    hasInFlightToolCalls,
    interrupted: typeof interrupted === "boolean" ? interrupted : operatorInterruptOf(interrupted),
  });
  if (decision.stopAllowed === true) return { ...decision, booked: false, turnId };
  if (nudged !== null && turnId !== null && nudged.has(turnId)) {
    return { ...decision, booked: false, turnId };
  }
  return { ...decision, booked: true, turnId };
};

// Deliver a nudge through the host's own queue: `pi.sendUserMessage` with
// `deliverAs: "followUp"`, so it arrives as the session's NEXT input rather
// than as a tool result inside a turn that has already ended. A host with no
// such channel answers `{ ok: false, reason }` — never a silent success, because
// a nudge nobody received is a session that stopped.
export const deliverNudge = ({ send, text }) => {
  if (typeof send !== "function") return { ok: false, reason: "NO_DELIVERY_CHANNEL" };
  if (typeof text !== "string" || text.length === 0) return { ok: false, reason: "EMPTY_NUDGE" };
  try {
    const answer = send(text, { deliverAs: "followUp" });
    if (answer !== null && typeof answer === "object" && answer.ok === false) {
      return { ok: false, reason: typeof answer.reason === "string" ? answer.reason : "HOST_REFUSED" };
    }
    return { ok: true };
  } catch (error) {
    return { ok: false, reason: typeof error?.message === "string" ? error.message : String(error) };
  }
};

// The sentence every nudge repeats, re-exported so a test asserts the exact text
// the model reads rather than a paraphrase of it.
export { STANDING_ORDER, operatorInterruptOf };
