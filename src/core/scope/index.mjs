// The package entry point. Everything a composition layer needs is here.
//
// `src/core/scope` imports nothing outside its own directory: it carries its
// own refusal type, argument error, annotation parser and UTF-8 rules. The
// composition layer wires it to a host; the core package index does not have
// to know about it.

export {
  SCOPE_CODES,
  SCOPE_KINDS,
  SCOPE_KIND_VALUES,
  DEFAULT_EXEMPT_MEMBERS,
  DEFAULT_PINNED_MEMBERS,
  RESET_MEMBER,
  FIRST_INCUMBENT_QUESTION,
  ScopeRefusal,
  ScopeArgumentError,
  isScopeRefusal,
  refuse,
  invalidArgument,
} from "./errors.mjs";
export {
  parseAnnotation,
  isReset,
  isKeep,
  isRemove,
  isMeToo,
  isTrueAnnotation,
} from "./annotation.mjs";
export { Retention, TurnLedger, ScopeBook, TURN_PHASES, isSettledOutcome, isRefusedOutcome } from "./ledger.mjs";
export { Admission } from "./admission.mjs";
export {
  RECORD_VERSION,
  RECORD_TYPES,
  ColdState,
  readColdState,
  coldStartNotice,
  resetRecord,
  budgetRecord,
  keepRecord,
  removeRecord,
  meTooRecord,
} from "./records.mjs";
export { ITEM, projectRequest, newestResetIndex, spinLine } from "./projection.mjs";
export { Scope, ScopeTable } from "./scope.mjs";
export { ScopeHooks } from "./hooks.mjs";
