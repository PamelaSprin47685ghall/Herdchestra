// The `scope` parameter every non-exempt Cline tool declares (spec §6.2, §1.1
// item 5).
//
// Root §6.2 makes `scope` mandatory on each non-exempt tool call, and the
// adapter's `beforeTool` refuses a call without it (`core/scope/admission.mjs`).
// A tool whose `inputSchema` is `additionalProperties: false` and does not name
// `scope` therefore makes every compliant call impossible: the host validates
// the model's arguments against the schema before `beforeTool` ever runs, so the
// one shape the spec requires is the one shape the schema forbids.
//
// The schema is `oneOf` over the four kinds, each branch closed
// (`additionalProperties: false`). The parser refuses a stray field per kind
// (`SCOPE_FIELD_UNEXPECTED`), and the schema says the same thing earlier, in the
// model's own view: a field the chosen kind does not take is not writable.
//
// The long explanation of WHY Scope exists belongs to the system prompt, which
// is written once; every tool schema would copy it otherwise. So the schema
// carries a short pointer and the field shape, and nothing else. This is a
// description of the annotation, not a second admission rule: the book in
// `core/scope` still decides whether a budget is established, spent, or whether
// this call is the turn's true annotation.

// The four kind values, read from the book rather than typed twice.
import { SCOPE_KINDS, SCOPE_KIND_VALUES } from "../core/scope/errors.mjs";

// `core/scope/annotation.mjs` refuses a goal or a replacement that is blank
// after trimming. JSON Schema has no "non-blank" keyword, so the two branches
// say the same thing twice: `minLength: 1` keeps the empty string out, and
// `pattern` keeps a whitespace-only string out. Without the pattern the schema
// would admit a value the book then refuses, and the model would spend a round
// trip discovering a rule the schema already knew.
const NON_BLANK = Object.freeze({
  type: "string",
  minLength: 1,
  pattern: "\\S",
});

const RESET_BRANCH = Object.freeze({
  type: "object",
  properties: Object.freeze({
    kind: Object.freeze({ type: "string", enum: [SCOPE_KINDS.RESET] }),
    newScopeGoal: Object.freeze({
      ...NON_BLANK,
      description: "Goal of the new Scope.",
    }),
    newScopeRetentionBudget: Object.freeze({
      type: "integer",
      minimum: 1,
      description: "Substantive `keepLast` beats the goal still needs.",
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
      description: "Lesson that replaces the previous beat.",
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

export const SCOPE_SCHEMA = Object.freeze({
  type: "object",
  description:
    "Required on every non-exempt tool. The system prompt's Scope section explains why and when. " +
    'Shape: {"kind":"resetScope","newScopeGoal","newScopeRetentionBudget"} | ' +
    '{"kind":"keepLast"} | {"kind":"removeLast","replacement"} | {"kind":"meToo"}. ' +
    "Each kind accepts only the fields listed for it.",
  oneOf: Object.freeze([
    RESET_BRANCH,
    bareBranch(SCOPE_KINDS.KEEP),
    REMOVE_BRANCH,
    bareBranch(SCOPE_KINDS.METOO),
  ]),
});

// The schema never invents a kind: if the book's enum grows, this list grows
// with it, and the assertion below fails instead of the schema silently
// describing a smaller language than the parser accepts.
const SCHEMA_KINDS = Object.freeze(
  SCOPE_SCHEMA.oneOf.map((branch) => branch.properties.kind.enum[0]),
);
if (SCHEMA_KINDS.length !== SCOPE_KIND_VALUES.length) {
  throw new Error(
    `scope schema describes ${SCHEMA_KINDS.length} kinds, the book has ${SCOPE_KIND_VALUES.length}`,
  );
}

// A tool's `inputSchema`, with `scope` added and the object still closed. The
// caller passes the properties it already had and the ones it demands; `scope` is
// demanded too, because every tool here is non-exempt. A property the caller
// lists but does not require stays optional: `fork`'s `role` is one.
//
// Keeping this one function means a new tool cannot forget the closing rule: it
// goes through `withScope`, so it either declares `scope` or the schema refuses
// every call the spec asks for.
export const withScope = (properties, required = Object.keys(properties)) =>
  Object.freeze({
    type: "object",
    properties: Object.freeze({ ...properties, scope: SCOPE_SCHEMA }),
    required: Object.freeze([...required, "scope"]),
    additionalProperties: false,
  });
