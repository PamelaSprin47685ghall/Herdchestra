// Retention bookkeeping for one model turn (spec §6.2, §6.2, §6.2).
//
// Retention is a capacity, not a count of turns or calls, and it has no upper
// bound. Only `keepLast` spends one. `removeLast` and `meToo` never spend.
//
// One model turn settles at most once, and exactly one path settles it: the
// first true annotation (`resetScope` | `keepLast` | `removeLast`). A `meToo`
// that arrives first is only staged; when the true annotation arrives the turn
// settles once and the staging is cleared. A turn that only ever saw `meToo`
// is refused with SCOPE_METOO_ONLY when the turn closes, and nothing changes.
//
// The book reads only parsed records (annotation.mjs): `goal`, `budget` and
// `replacement` are normalized names, never the model's own keys.

import { SCOPE_CODES, SCOPE_KINDS, invalidArgument, refuse } from "./errors.mjs";
import { isKeep, isMeToo, isRemove, isReset } from "./annotation.mjs";

const isNonBlank = (value) => typeof value === "string" && value.trim().length > 0;

const isPositiveInteger = (value) => Number.isInteger(value) && value > 0;

export const TURN_PHASES = Object.freeze({
  OPEN: "open",
  SETTLED: "settled",
});

// The budget of the current Scope. `established` is false before a
// `resetScope` in this process, and after a cold start until a persisted
// budget record is read (spec §6.2).
export class Retention {
  #established;
  #remaining;

  constructor({ established = false, remaining = 0 } = {}) {
    this.#established = established;
    this.#remaining = remaining;
  }

  get established() {
    return this.#established;
  }

  get remaining() {
    return this.#remaining;
  }

  get spent() {
    return this.#established && this.#remaining === 0;
  }

  // Replace the budget wholesale. Returns the new remaining value.
  establish(budget) {
    this.#established = true;
    this.#remaining = budget;
    return this.#remaining;
  }

  // Spend one unit. A spent budget stays at zero; the caller decides whether
  // the call is admitted.
  spend() {
    if (this.#remaining > 0) this.#remaining -= 1;
    return this.#remaining;
  }

  view() {
    return Object.freeze({
      established: this.#established,
      remaining: this.#remaining,
      spent: this.spent,
    });
  }

  // A copy for a transaction: settling a clone leaves this budget untouched
  // until the caller commits.
  clone() {
    return new Retention({ established: this.#established, remaining: this.#remaining });
  }

  // Put a committed clone's values back into this instance.
  adopt(other) {
    this.#established = other.#established;
    this.#remaining = other.#remaining;
    return this;
  }
}

// One model turn. The host names the turn; the tracker knows only whether the
// turn is open and what it staged.
export class TurnLedger {
  #turnId;
  #phase;
  #staged;

  constructor(turnId) {
    this.#turnId = turnId;
    this.#phase = TURN_PHASES.OPEN;
    this.#staged = null;
  }

  get turnId() {
    return this.#turnId;
  }

  get settled() {
    return this.#phase === TURN_PHASES.SETTLED;
  }

  get staged() {
    return this.#staged;
  }

  // Record a `meToo` that arrived before any true annotation. Staging is
  // idempotent: a second `meToo` in the same turn changes nothing.
  stage(annotation) {
    if (this.#phase !== TURN_PHASES.OPEN) return false;
    this.#staged = annotation;
    return true;
  }

  // Close the turn on a true annotation.
  close(annotation) {
    this.#phase = TURN_PHASES.SETTLED;
    this.#staged = null;
    return annotation;
  }

  clone() {
    const copy = new TurnLedger(this.#turnId);
    copy.#phase = this.#phase;
    copy.#staged = this.#staged;
    return copy;
  }
}

// The receipt shape of every settle. `outcome` is `settled`, `confirmed` or
// `staged`; a refusal is a ScopeRefusal.
const settled = (fields) => ({ ok: true, outcome: "settled", ...fields });

// What the hook reports after one annotation.
export class ScopeBook {
  #retention;
  #turn;
  #lastReset;
  #settlements;
  #spin;
  #lastErase;
  #resetBeats;
  #erases;

  constructor({ retention = new Retention() } = {}) {
    this.#retention = retention;
    this.#turn = null;
    this.#lastReset = null;
    this.#settlements = 0;
    this.#spin = 0;
    this.#lastErase = null;
    this.#resetBeats = [];
    this.#erases = [];
  }

  // A transaction copy. Settling the copy changes nothing here; `adopt` puts
  // the copy's facts in only after the records were persisted, so a failed
  // append cannot leave memory ahead of the persisted facts.
  clone() {
    const copy = new ScopeBook({ retention: this.#retention.clone() });
    copy.#turn = this.#turn === null ? null : this.#turn.clone();
    copy.#lastReset = this.#lastReset;
    copy.#settlements = this.#settlements;
    copy.#spin = this.#spin;
    copy.#lastErase = this.#lastErase;
    copy.#resetBeats = [...this.#resetBeats];
    copy.#erases = [...this.#erases];
    return copy;
  }

  adopt(other) {
    this.#retention.adopt(other.#retention);
    this.#turn = other.#turn;
    this.#lastReset = other.#lastReset;
    this.#settlements = other.#settlements;
    this.#spin = other.#spin;
    this.#lastErase = other.#lastErase;
    this.#resetBeats = other.#resetBeats;
    this.#erases = other.#erases;
    return this;
  }

  // The erased beats, in the order they were committed. The projection reads
  // this list, so a beat whose record was never written is never applied.
  get erases() {
    return this.#erases;
  }

  // Register one erased beat on the transaction copy.
  noteErase({ callId, replacement, spin }) {
    this.#erases.push(Object.freeze({ callId, replacement, spin }));
    return this.#erases;
  }

  get retention() {
    return this.#retention.view();
  }

  get turnId() {
    return this.#turn === null ? null : this.#turn.turnId;
  }

  get turnSettled() {
    return this.#turn === null || this.#turn.settled;
  }

  // True while a `meToo` is staged and no true annotation has arrived.
  get turnStaged() {
    return this.#turn !== null && !this.#turn.settled && this.#turn.staged !== null;
  }

  get settlements() {
    return this.#settlements;
  }

  // Number of `removeLast` settlements: each one erased one earlier beat
  // (spec §6.2 "记一次空转").
  get spinCount() {
    return this.#spin;
  }

  get lastReset() {
    return this.#lastReset;
  }

  // The most recent erase, `{ turnId, replacement, spin }`, or null.
  get lastErase() {
    return this.#lastErase;
  }

  // Every reset that actually replaced the budget, in order: `{ turnId,
  // callId, goal, budget }`. A refused reset or a reset that only confirmed an
  // already settled turn never appears here, so the projection can name the
  // one beat that opened the current Scope (spec §6.2).
  get resetBeats() {
    return this.#resetBeats;
  }

  // The beat that opened the current Scope, or null before any settled reset.
  get lastResetBeat() {
    return this.#resetBeats.length === 0
      ? null
      : this.#resetBeats[this.#resetBeats.length - 1];
  }

  // Register the physical call that carried a settled reset. The book settles
  // the turn; only the caller knows the callId.
  noteResetBeat(callId) {
    const reset = this.#lastReset;
    if (reset === null) return null;
    if (this.#resetBeats.length > 0) {
      const last = this.#resetBeats[this.#resetBeats.length - 1];
      if (last.turnId === reset.turnId && last.goal === reset.goal && last.budget === reset.budget) {
        return last;
      }
    }
    const beat = Object.freeze({
      turnId: reset.turnId,
      callId,
      goal: reset.goal,
      budget: reset.budget,
    });
    this.#resetBeats.push(beat);
    return beat;
  }

  // Restore the newest reset beat from a persisted record. A cold start has
  // no in-memory turn, so the record is the whole fact.
  restoreResetBeat({ turnId = null, callId = null, goal, budget }) {
    const beat = Object.freeze({ turnId, callId, goal, budget });
    this.#resetBeats.push(beat);
    this.#lastReset = Object.freeze({ turnId, goal, budget });
    return beat;
  }

  // Restore the spin count from the record stream. `removeLast` records the
  // count at the moment of the erase, so the newest one IS the count this
  // process had; without it a restarted process answers zero and the
  // `removeLast` projection would rewrite a beat with a smaller number than
  // the one the model already read (spec §6.2 "记一次空转").
  restoreSpin(count) {
    if (!Number.isInteger(count) || count < 0) {
      throw invalidArgument(`spin count must be a non-negative integer, got ${String(count)}`);
    }
    this.#spin = count;
    return this.#spin;
  }

  // Restore the budget a `keep` record spent. The budget itself is established
  // only by a `budget` record or a reset that settled in this process (spec
  // §6.2), so this changes the REMAINING number and never `established`: a cold
  // start still asks for `resetScope` and still refuses a `keep` or `remove`
  // before one settles.
  restoreRemaining(remaining) {
    if (!Number.isInteger(remaining) || remaining < 0) {
      throw invalidArgument(`remaining must be a non-negative integer, got ${String(remaining)}`);
    }
    this.#retention.adopt(new Retention({ established: this.#retention.established, remaining }));
    return remaining;
  }

  // A new model turn. A previous turn that never settled is closed as it
  // stands; its staging is discarded, because a new turn starts fresh.
  openTurn(turnId) {
    this.#turn = new TurnLedger(turnId);
    return this.#turn;
  }

  // Close the current turn. Returns null when a true annotation already
  // settled it, otherwise the refusal for a turn that only staged `meToo`.
  closeTurn() {
    const turn = this.#turn;
    if (turn === null || turn.settled) return null;
    if (turn.staged !== null) {
      return refuse(SCOPE_CODES.SCOPE_METOO_ONLY, "the turn has only meToo annotations", {
        turnId: turn.turnId,
      });
    }
    return null;
  }

  #currentTurn(turnId) {
    if (this.#turn === null || this.#turn.turnId !== turnId) return this.openTurn(turnId);
    return this.#turn;
  }

  #settleReset(turnId, annotation, turn, observed) {
    // Closing the turn is part of settling, so it happens only once the
    // settlement itself is known to be valid. A reset that fails validation
    // must leave the turn open and unbooked (spec §6.2: state unchanged).
    if (!isNonBlank(annotation.goal) || !isPositiveInteger(annotation.budget)) {
      return refuse(SCOPE_CODES.SCOPE_FIELD_INVALID, "resetScope needs a goal and a budget", {
        turnId: turn.turnId,
      });
    }
    turn.close(annotation);
    this.#settlements += 1;
    const remaining = this.#retention.establish(annotation.budget);
    this.#lastReset = Object.freeze({
      turnId,
      goal: annotation.goal,
      budget: annotation.budget,
    });
    return settled({
      kind: SCOPE_KINDS.RESET,
      goal: annotation.goal,
      budget: annotation.budget,
      remaining,
      observed,
    });
  }

  #settleKeep(annotation, turn, observed) {
    // The turn closes on the first true annotation whether or not the beat is
    // admitted (spec §6.2). The count records that this turn's single
    // settlement happened; it is not a count of successful spends.
    if (!this.#retention.established) {
      // A beat the book cannot book leaves the turn open, so the reset the
      // cold-start notice asks for still settles this same turn.
      return refuse(
        SCOPE_CODES.SCOPE_UNINITIALIZED,
        "the budget is not established; resetScope is required",
        { kind: annotation.kind, turnId: turn.turnId },
      );
    }
    turn.close(annotation);
    this.#settlements += 1;
    const remaining = this.#retention.spend();
    return settled({ kind: SCOPE_KINDS.KEEP, remaining, observed });
  }

  #settleRemove(annotation, turn, observed) {
    if (!isNonBlank(annotation.replacement)) {
      return refuse(SCOPE_CODES.SCOPE_FIELD_INVALID, "removeLast needs a replacement", {
        turnId: turn.turnId,
      });
    }
    if (!this.#retention.established) {
      // Same as keep: an unbookable beat leaves the turn open.
      return refuse(
        SCOPE_CODES.SCOPE_UNINITIALIZED,
        "the budget is not established; resetScope is required",
        { kind: annotation.kind, turnId: turn.turnId },
      );
    }
    turn.close(annotation);
    this.#settlements += 1;
    this.#spin += 1;
    this.#lastErase = Object.freeze({
      turnId: turn.turnId,
      replacement: annotation.replacement,
      spin: this.#spin,
    });
    return settled({
      kind: SCOPE_KINDS.REMOVE,
      remaining: this.#retention.remaining,
      spin: this.#spin,
      replacement: annotation.replacement,
      observed,
    });
  }

  // Settle one parsed annotation. `turnId` identifies the model turn.
  settle(turnId, annotation, { observed = null } = {}) {
    if (turnId === undefined || turnId === null) {
      return refuse(SCOPE_CODES.SCOPE_TURN_MISSING, "a settle needs the model turn id");
    }
    const turn = this.#currentTurn(turnId);

    if (isMeToo(annotation)) {
      if (turn.settled) return { ok: true, outcome: "confirmed", kind: SCOPE_KINDS.METOO };
      if (!this.#retention.established) {
        return refuse(
          SCOPE_CODES.SCOPE_UNINITIALIZED,
          "a meToo cannot arrive before the budget is established",
          { kind: SCOPE_KINDS.METOO, turnId },
        );
      }
      if (this.#retention.spent) {
        return refuse(
          SCOPE_CODES.SCOPE_BUDGET_SPENT,
          "the budget is spent; a meToo cannot open the turn",
          { kind: SCOPE_KINDS.METOO, turnId },
        );
      }
      turn.stage(annotation);
      return { ok: true, outcome: "staged", kind: SCOPE_KINDS.METOO };
    }

    if (turn.settled) return { ok: true, outcome: "confirmed", kind: annotation.kind };
    if (isReset(annotation)) return this.#settleReset(turnId, annotation, turn, observed);
    if (isKeep(annotation)) return this.#settleKeep(annotation, turn, observed);
    if (isRemove(annotation)) return this.#settleRemove(annotation, turn, observed);
    return refuse(SCOPE_CODES.SCOPE_KIND_INVALID, `unknown scope kind: ${String(annotation?.kind)}`);
  }
}

export const isSettledOutcome = (result) => result.ok === true && result.outcome === "settled";
export const isRefusedOutcome = (result) => result.ok === false;
