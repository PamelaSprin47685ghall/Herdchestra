// The `scope` annotation carried by one tool call (spec §6.2, §6.2).
//
// The parser turns the raw value the model wrote into one of four frozen
// records, or into a refusal. It is total: it never throws for model input.
// "The remaining fields are not accepted" (§6.2) is enforced per kind, not
// as a shared allow-list, so an unknown key on any kind is a refusal.
//
// The returned record is the only shape the book reads: `goal` and `budget`
// for a reset, `replacement` for an erase. A raw model object never reaches
// the book, so the model's own key names cannot leak into the facts.

import { SCOPE_CODES, SCOPE_KINDS, SCOPE_KIND_VALUES, refuse } from "./errors.mjs";

const isObject = (value) => value !== null && typeof value === "object" && !Array.isArray(value);

const isNonBlank = (value) => typeof value === "string" && value.trim().length > 0;

const isPositiveInteger = (value) => Number.isInteger(value) && value > 0;

const unexpected = (kind, field) =>
  refuse(SCOPE_CODES.SCOPE_FIELD_UNEXPECTED, `scope.${kind} does not accept "${field}"`, {
    kind,
    field,
  });

const invalid = (kind, field, why) =>
  refuse(SCOPE_CODES.SCOPE_FIELD_INVALID, `scope.${kind}: ${why}`, { kind, field });

const reset = (raw) => {
  for (const field of Object.keys(raw)) {
    if (field !== "kind" && field !== "newScopeGoal" && field !== "newScopeRetentionBudget") {
      return unexpected(SCOPE_KINDS.RESET, field);
    }
  }
  if (!isNonBlank(raw.newScopeGoal)) {
    return invalid(SCOPE_KINDS.RESET, "newScopeGoal", "newScopeGoal must be a non-blank string");
  }
  if (!isPositiveInteger(raw.newScopeRetentionBudget)) {
    return invalid(
      SCOPE_KINDS.RESET,
      "newScopeRetentionBudget",
      "newScopeRetentionBudget must be a positive integer",
    );
  }
  return Object.freeze({
    kind: SCOPE_KINDS.RESET,
    goal: raw.newScopeGoal,
    budget: raw.newScopeRetentionBudget,
  });
};

const remove = (raw) => {
  for (const field of Object.keys(raw)) {
    if (field !== "kind" && field !== "replacement") {
      return unexpected(SCOPE_KINDS.REMOVE, field);
    }
  }
  if (!isNonBlank(raw.replacement)) {
    return invalid(SCOPE_KINDS.REMOVE, "replacement", "replacement must be a non-blank string");
  }
  return Object.freeze({ kind: SCOPE_KINDS.REMOVE, replacement: raw.replacement });
};

const bare = (kind) => (raw) => {
  for (const field of Object.keys(raw)) {
    if (field !== "kind") return unexpected(kind, field);
  }
  return Object.freeze({ kind });
};

const PARSERS = Object.freeze({
  [SCOPE_KINDS.RESET]: reset,
  [SCOPE_KINDS.KEEP]: bare(SCOPE_KINDS.KEEP),
  [SCOPE_KINDS.REMOVE]: remove,
  [SCOPE_KINDS.METOO]: bare(SCOPE_KINDS.METOO),
});

// Returns a frozen annotation record or a ScopeRefusal. Never throws.
export const parseAnnotation = (raw) => {
  if (raw === undefined || raw === null) {
    return refuse(SCOPE_CODES.SCOPE_ANNOTATION_MISSING, "the tool call has no scope annotation");
  }
  if (!isObject(raw)) {
    return refuse(SCOPE_CODES.SCOPE_ANNOTATION_MISSING, "scope annotation must be an object");
  }
  const kind = raw.kind;
  if (!SCOPE_KIND_VALUES.includes(kind)) {
    return refuse(SCOPE_CODES.SCOPE_KIND_INVALID, `unknown scope kind: ${String(kind)}`, { kind });
  }
  return PARSERS[kind](raw);
};

export const isReset = (annotation) => annotation?.kind === SCOPE_KINDS.RESET;
export const isKeep = (annotation) => annotation?.kind === SCOPE_KINDS.KEEP;
export const isRemove = (annotation) => annotation?.kind === SCOPE_KINDS.REMOVE;
export const isMeToo = (annotation) => annotation?.kind === SCOPE_KINDS.METOO;
export const isTrueAnnotation = (annotation) =>
  isReset(annotation) || isKeep(annotation) || isRemove(annotation);
