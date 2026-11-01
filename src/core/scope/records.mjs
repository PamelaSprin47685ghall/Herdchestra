// Persistence records (spec §6.2, §1).
//
// A Scope record is a plain JSON value with a schema version and an explicit
// discriminator. Writing is an append to `.Herdchestra/{session}.jsonl`; this
// module owns only the record shapes and their reading, so a store can be a
// file, memory, or a test double.
//
// A persisted `resetScope` proves an old event existed. It does not prove the
// current budget. Only a `budget` record establishes the budget of this
// process; until one is read after a cold start, the Scope stays
// uninitialized and every request is told to resetScope.
//
// An erase record carries the erased beat's `callId` and the spin count at the
// time of the erase. Without the callId a cold start could not rebuild the
// same request copy, so a `removeLast` without one is not recorded at all.

import { invalidArgument } from "./errors.mjs";

export const RECORD_VERSION = 1;

export const RECORD_TYPES = Object.freeze({
  RESET: "reset",
  BUDGET: "budget",
  KEEP: "keep",
  REMOVE: "remove",
  METOO: "meToo",
});

const isNonBlankString = (value) => typeof value === "string" && value.trim().length > 0;

const isNonNegativeInteger = (value) => Number.isInteger(value) && value >= 0;

export const record = (type, fields) =>
  Object.freeze({ v: RECORD_VERSION, type, ...fields });

// `callId` is the physical call that opened the Scope. The projection needs it
// to find the boundary after a restart, because the request copy alone cannot
// tell a settled reset from a refused one.
export const resetRecord = ({ turnId, callId = null, goal, budget, remaining }) =>
  record(RECORD_TYPES.RESET, { turnId, callId, goal, budget, remaining });

// The current budget as established in this process.
export const budgetRecord = ({ remaining }) => record(RECORD_TYPES.BUDGET, { remaining });

export const keepRecord = ({ turnId, remaining }) =>
  record(RECORD_TYPES.KEEP, { turnId, remaining });

export const removeRecord = ({ turnId, callId, replacement, spin }) =>
  record(RECORD_TYPES.REMOVE, { turnId, callId, replacement, spin });

export const meTooRecord = ({ turnId }) => record(RECORD_TYPES.METOO, { turnId });

// The per-session facts a cold start needs. `resets` keeps every reset in
// order; the last one is the newest Scope. `budget` is null when no budget
// record was read, and carries the number of resets seen when it was written so
// a budget from an older Scope cannot pass for the current one. `erase` is the
// erased-beat list the projection consumes.
//
// `spent` is the remaining budget a `keep` record left behind, or null when no
// `keep` was read. A `keep` spends one unit and the record carries the number
// that spend produced, so folding the newest one tells a cold start what the
// previous process had actually spent — which the `budget` record cannot say,
// because it is written only by a reset. `spent` never establishes the budget:
// it is a spend fact, and §6.2 still requires a `resetScope` in this process
// before any `keep` or `remove` is admitted.
export class ColdState {
  #resets;
  #budget;
  #spent;
  #spin;
  #erase;
  #confirmed;

  constructor({ resets = [], budget = null, spent = null, spin = 0, erase = [], confirmed = null } = {}) {
    this.#resets = resets;
    this.#budget = budget;
    this.#spent = spent;
    this.#spin = spin;
    this.#erase = erase;
    this.#confirmed = confirmed;
  }

  static empty() {
    return new ColdState();
  }

  get resets() {
    return this.#resets;
  }

  get newestReset() {
    return this.#resets.length === 0 ? null : this.#resets[this.#resets.length - 1];
  }

  get budget() {
    return this.#budget;
  }

  // The remaining budget the newest `keep` record left, or null. `scope` is
  // how many resets had been seen when the spend happened: a spend from an
  // older Scope says nothing about the current one, exactly like `budget`.
  get spent() {
    return this.#spent;
  }

  // The remaining budget this record stream proves, or null when it proves
  // none. The newest fact wins: a spend recorded after the newest reset is
  // newer than that reset's budget record, and a budget record written after
  // the spend is newer still.
  get remaining() {
    const budget = this.budgetKnown ? this.#budget : null;
    const spent = this.#spent !== null && this.#spent.scope === this.#resets.length ? this.#spent : null;
    if (budget === null && spent === null) return null;
    if (spent === null) return budget.remaining;
    if (budget === null) return spent.remaining;
    return spent.order > budget.order ? spent.remaining : budget.remaining;
  }

  get spin() {
    return this.#spin;
  }

  get erase() {
    return this.#erase;
  }

  // True only when a `budget` record was read **after** the newest reset. A
  // reset alone is not enough (spec §6.2), and a budget that belongs to an
  // older Scope says nothing about the current one. A reset that settled in
  // this process confirms the budget too.
  get budgetKnown() {
    if (this.#confirmed === true) return true;
    return this.#budget !== null && this.#budget.scope === this.#resets.length;
  }

  // Record that this process established the budget itself.
  confirm() {
    this.#confirmed = true;
    return this;
  }

  // Whether the request must carry a resetScope to be admitted at all.
  get mustReset() {
    return !this.budgetKnown;
  }
}

const expectRecord = (raw, index) => {
  if (raw === null || typeof raw !== "object" || Array.isArray(raw)) {
    throw invalidArgument(`record ${index} is not an object`);
  }
  if (raw.v !== RECORD_VERSION) {
    throw invalidArgument(`record ${index} has version ${String(raw.v)}, expected ${RECORD_VERSION}`);
  }
  if (typeof raw.type !== "string") {
    throw invalidArgument(`record ${index} has no type`);
  }
  return raw;
};

// Read persisted records into a ColdState. A malformed line is an error, never
// a silent skip: a lost record would silently change the budget or the copy.
export const readColdState = (records) => {
  if (!Array.isArray(records)) throw invalidArgument("records must be an array");
  const resets = [];
  const erase = [];
  let budget = null;
  let spent = null;
  let spin = 0;
  records.forEach((raw, index) => {
    const entry = expectRecord(raw, index);
    switch (entry.type) {
      case RECORD_TYPES.RESET: {
        if (!isNonBlankString(entry.goal)) {
          throw invalidArgument(`record ${index}: reset goal must be a non-blank string`);
        }
        if (!Number.isInteger(entry.budget) || entry.budget <= 0) {
          throw invalidArgument(`record ${index}: reset budget must be a positive integer`);
        }
        resets.push(
          Object.freeze({
            callId: typeof entry.callId === "string" ? entry.callId : null,
            goal: entry.goal,
            budget: entry.budget,
            remaining: isNonNegativeInteger(entry.remaining) ? entry.remaining : entry.budget,
          }),
        );
        break;
      }
      case RECORD_TYPES.BUDGET: {
        if (!isNonNegativeInteger(entry.remaining)) {
          throw invalidArgument(`record ${index}: budget remaining must be a non-negative integer`);
        }
        budget = Object.freeze({ remaining: entry.remaining, scope: resets.length, order: index });
        break;
      }
      case RECORD_TYPES.KEEP: {
        // A `keep` spends exactly one unit and records the number it left.
        // A record without that number is still a valid spend; it simply says
        // nothing about the remaining budget, and the older fact stands.
        if (isNonNegativeInteger(entry.remaining)) {
          spent = Object.freeze({ remaining: entry.remaining, scope: resets.length, order: index });
        }
        break;
      }
      case RECORD_TYPES.REMOVE: {
        if (!isNonBlankString(entry.replacement)) {
          throw invalidArgument(`record ${index}: remove replacement must be a non-blank string`);
        }
        if (entry.callId === null || entry.callId === undefined) {
          throw invalidArgument(`record ${index}: remove needs the erased callId`);
        }
        if (!isNonNegativeInteger(entry.spin) || entry.spin === 0) {
          throw invalidArgument(`record ${index}: remove spin must be a positive integer`);
        }
        spin = entry.spin;
        erase.push(
          Object.freeze({
            callId: entry.callId,
            replacement: entry.replacement,
            spin,
          }),
        );
        break;
      }
      case RECORD_TYPES.METOO:
        break;
      default:
        throw invalidArgument(`record ${index}: unknown type ${entry.type}`);
    }
  });
  return new ColdState({ resets, budget, spent, spin, erase });
};

// The notice a request carries while the budget is not established (spec
// §6.2): the model must resetScope, and no in-memory default may stand in
// for the missing fact.
export const coldStartNotice = (cold) => {
  if (!cold.mustReset) return null;
  return Object.freeze({
    kind: "scopeColdStart",
    reason: "SCOPE_UNINITIALIZED",
    previousResets: cold.resets.length,
    message:
      "The current retention budget is not established; the next tool call must carry resetScope.",
  });
};
