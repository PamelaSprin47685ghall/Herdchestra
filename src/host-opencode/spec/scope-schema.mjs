// The `scope` annotation every non-exempt tool declares (spec §6.2).
//
// Root §6.2 makes `scope` mandatory on each non-exempt tool call and names the
// fields each kind requires. The book in `core/scope/annotation.mjs` refuses a
// `resetScope` without `newScopeGoal` and `newScopeRetentionBudget`. That refusal
// is correct and stays: a reset with no goal cannot open a Scope.
//
// What was wrong is that nothing told the model. A free-form
// `{ type: "object" }` says a `scope` field exists and nothing about its
// language, so on the real host (live6, 2026-11-01) the model wrote
// `{"kind":"resetScope"}` — the only reading a bare object permits — and every
// call was refused before it ran. The sibling adapters already declare the
// per-kind shape (`host-cline/scope-schema.mjs`, `host-omp/program-tools.mjs`
// `SCOPE_PROPERTY`); this module gives this host the same answer from the same
// source.
//
// The schema is `oneOf` over the four kinds, each branch closed
// (`additionalProperties: false`), matching the parser's per-kind allow-list: a
// field the chosen kind does not take is not writable, so the model sees the
// rule before spending a round trip on a refusal.
//
// This is a description of the annotation, not a second admission rule. The book
// still decides: the schema cannot know whether a budget is established or
// spent, nor whether this call is its turn's true annotation.

import { SCOPE_KINDS, SCOPE_KIND_VALUES } from "../../core/scope/errors.mjs";

// `core/scope/annotation.mjs` refuses a goal or replacement that is blank after
// trimming. The schema says `minLength: 1`, so the empty string never reaches the
// book; a whitespace-only string still does, and the book refuses it there.
//
// `pattern` is deliberately absent. The host compiles an input schema with
// `JsonSchema.fromSchemaDraft2020_12` and its default `patterns: "error"`, which
// THROWS on any `pattern` keyword; `packages/core/src/tool/runtime.ts` wraps that
// in `Effect.try(...).orElseSucceed(() => undefined)`, so one pattern would
// silently turn the tool's whole input validation off. `host-cline` can use
// `pattern: "\\S"` because it validates in its own `beforeTool`; this host cannot.
const NON_BLANK = Object.freeze({
  type: "string",
  minLength: 1,
});

const RESET_BRANCH = Object.freeze({
  type: "object",
  properties: Object.freeze({
    kind: Object.freeze({ type: "string", enum: [SCOPE_KINDS.RESET] }),
    newScopeGoal: Object.freeze({
      ...NON_BLANK,
      description: "What this new Scope aims at. Non-blank.",
    }),
    newScopeRetentionBudget: Object.freeze({
      type: "integer",
      minimum: 1,
      description:
        "How many substantive `keepLast` beats this goal still needs. Positive, no upper bound.",
    }),
  }),
  required: Object.freeze(["kind", "newScopeGoal", "newScopeRetentionBudget"]),
  additionalProperties: false,
});

const REMOVE_BRANCH = Object.freeze({
  type: "object",
  properties: Object.freeze({
    kind: Object.freeze({ type: "string", enum: [SCOPE_KINDS.REMOVE] }),
    replacement: Object.freeze({
      ...NON_BLANK,
      description: "The lesson that replaces the previous beat. Non-blank.",
    }),
  }),
  required: Object.freeze(["kind", "replacement"]),
  additionalProperties: false,
});

const bareBranch = (kind) =>
  Object.freeze({
    type: "object",
    properties: Object.freeze({ kind: Object.freeze({ type: "string", enum: [kind] }) }),
    required: Object.freeze(["kind"]),
    additionalProperties: false,
  });

// The prose the model reads first. The example is the shape that actually runs:
// a bare `resetScope` is the one the book refuses.
export const SCOPE_DESCRIPTION =
  "Scope annotation for this call (spec §6.2), required on every non-exempt tool. " +
  'First call of a session: {"kind":"resetScope","newScopeGoal":"...","newScopeRetentionBudget":N}. ' +
  "Then one true annotation per model turn: {\"kind\":\"keepLast\"}, " +
  '{"kind":"removeLast","replacement":"..."}, or {"kind":"resetScope",...}; ' +
  'every other call of the same turn: {"kind":"meToo"}. ' +
  "A kind accepts only the fields listed here.";

export const SCOPE_SCHEMA = Object.freeze({
  type: "object",
  description: SCOPE_DESCRIPTION,
  oneOf: Object.freeze([
    RESET_BRANCH,
    bareBranch(SCOPE_KINDS.KEEP),
    REMOVE_BRANCH,
    bareBranch(SCOPE_KINDS.METOO),
  ]),
});

// The schema never invents a kind: if the book's enum grows, this list grows
// with it, and the assertion fails instead of the schema silently describing a
// smaller language than the parser accepts.
const SCHEMA_KINDS = Object.freeze(
  SCOPE_SCHEMA.oneOf.map((branch) => branch.properties.kind.enum[0]),
);
if (SCHEMA_KINDS.length !== SCOPE_KIND_VALUES.length) {
  throw new Error(
    `scope schema describes ${SCHEMA_KINDS.length} kinds, the book has ${SCOPE_KIND_VALUES.length}`,
  );
}
