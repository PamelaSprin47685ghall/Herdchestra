// Refusal codes of the Scope package (spec §6.2, §6.2).
//
// A refusal is a value, not a thrown error: the hook hands it to the host and
// the state does not change. Every code below is a whole class of refusal the
// spec names; `details` carries the concrete fact (member, field, kind).

export const SCOPE_CODES = Object.freeze({
  // The annotation is absent or is not an object.
  SCOPE_ANNOTATION_MISSING: "SCOPE_ANNOTATION_MISSING",
  // `kind` is not one of the four enum values.
  SCOPE_KIND_INVALID: "SCOPE_KIND_INVALID",
  // A required field is absent, empty, or of the wrong type.
  SCOPE_FIELD_INVALID: "SCOPE_FIELD_INVALID",
  // A field the chosen kind does not accept is present.
  SCOPE_FIELD_UNEXPECTED: "SCOPE_FIELD_UNEXPECTED",
  // A `meToo` with no true annotation before it in the same model turn
  // (spec §6.2 "全 meToo"). The turn stays open; nothing was booked.
  SCOPE_METOO_ONLY: "SCOPE_METOO_ONLY",
  // No budget is established in this process (spec §6.2).
  SCOPE_UNINITIALIZED: "SCOPE_UNINITIALIZED",
  // The budget is spent and the tool is not exempt (spec §6.2).
  SCOPE_BUDGET_SPENT: "SCOPE_BUDGET_SPENT",
  // A per-session Scope is written twice in one process.
  SCOPE_OWNER_TAKEN: "SCOPE_OWNER_TAKEN",
  // The record store refused the append; the turn stays settled, persistence
  // does not.
  SCOPE_RECORD_FAILED: "SCOPE_RECORD_FAILED",
  // A settle needs a model turn id.
  SCOPE_TURN_MISSING: "SCOPE_TURN_MISSING",
  // A snapshot query (`negotiate`, `askPreviousScope`) was asked for a member
  // that has no snapshot. Spec §6.2 exempts those two members and no others, so
  // any other name is a field error of the query, not a new member.
  SCOPE_MEMBER_UNKNOWN: "SCOPE_MEMBER_UNKNOWN",
  // A snapshot query arrived while another call is queued or settling. The
  // answer must come from the newest committed facts (§6.2 "临时最新快照"), so
  // it is refused rather than answered from a half-book; the caller asks again.
  SCOPE_SNAPSHOT_BUSY: "SCOPE_SNAPSHOT_BUSY",
});

export class ScopeRefusal {
  constructor(code, message, details = null) {
    this.ok = false;
    this.code = code;
    this.message = message;
    this.details = details;
    Object.freeze(this);
  }
}

export const refuse = (code, message, details) => new ScopeRefusal(code, message, details);

// A wiring or programming error. Unlike a refusal it is thrown: the caller
// passed something the package cannot interpret, and continuing would hide it.
export class ScopeArgumentError extends TypeError {
  constructor(message) {
    super(message);
    this.name = "ScopeArgumentError";
  }
}

export const invalidArgument = (message) => new ScopeArgumentError(message);

export const isScopeRefusal = (value) =>
  value instanceof ScopeRefusal && typeof value.code === "string";

export const SCOPE_KINDS = Object.freeze({
  RESET: "resetScope",
  KEEP: "keepLast",
  REMOVE: "removeLast",
  METOO: "meToo",
});

export const SCOPE_KIND_VALUES = Object.freeze(Object.values(SCOPE_KINDS));

// Spec §6.2: exempt members neither spend retention nor require a reset when
// the budget is spent. They answer from the newest snapshot, synchronously.
export const DEFAULT_EXEMPT_MEMBERS = Object.freeze(["negotiate", "askPreviousScope"]);

// Spec §6.2: members whose calls and paired results survive every reset.
export const DEFAULT_PINNED_MEMBERS = Object.freeze(["assume", "negotiate", "askPreviousScope"]);

// The member name that closes a Scope. `resetScope` is both an annotation kind
// and the call the annotation rides on; the projection must recognize it.
export const RESET_MEMBER = "resetScope";

// The annotation kind that erases the beat before its call.
export const REMOVE_MEMBER = "removeLast";
