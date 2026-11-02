// Input schemas of the adapter's tools, as JSON Schema (draft 2020-12).
//
// `@opencode/schema/tool` accepts a JSON Schema for `input`, so the adapter
// stays pure mjs and needs no Effect schema. The host decodes the model's
// arguments against these before `execute` runs; a mismatch is reported by the
// host, not by us.
//
// Every js-* tool takes one `code` string: the whole program. The adapter
// evaluates it against the surface from compose (§3.1) and returns the program
// receipt. `scope` is the §6.2 annotation the tool hook reads; it is declared
// here so the model knows to write it, and never rewritten.
//
// The annotation's shape comes from `scope-schema.mjs`, not a bare object. A bare
// `{ type: "object" }` told the model only that the field exists, so on the real
// host (live6, 2026-11-01) it wrote `{"kind":"resetScope"}` with no goal and no
// budget and the book refused every call. The schema now names each kind's
// fields; the refusal itself is unchanged.

import { SCOPE_SCHEMA } from "./scope-schema.mjs";

const CODE = {
  type: "object",
  properties: {
    code: {
      type: "string",
      description:
        "The program. Use `return` for the value you want back. Only the members this role has exist.",
    },
    scope: SCOPE_SCHEMA,
  },
  required: ["code"],
  additionalProperties: true,
};

// §4.1: `fork(name, verboseIntent)` — two arguments, and no third. The worker's
// identity is not an argument: it is declared once inside the verboseIntent
// (`role: coder` or `role: devops`), because a verboseIntent is the only thing a
// worker receives whole and §2.1 requires every subagent to state its identity.
// A `role` property here would offer the model a second, contradictory channel
// the adapter does not read; the refusal for a verboseIntent that names none or
// both comes from the verboseIntent. There is no `model` property either: the
// task model (or the parent's) is chosen by the adapter, never by the model
// (根 §1.1 item 6).
const FORK = {
  type: "object",
  properties: {
    name: {
      type: "string",
      description:
        "Worker name. Reusing a live name steers that worker; the receipt says which happened.",
    },
    verboseIntent: {
      type: "string",
      description:
        "Self-contained task description: goal, constraints, interfaces, acceptance, what to report, and the worker's identity declared once as `role: coder` or `role: devops`. The worker never sees this conversation. A coder task must forbid bash; exactly one devops may be in flight.",
    },
    scope: SCOPE_SCHEMA,
  },
  required: ["name", "verboseIntent"],
  additionalProperties: true,
};

const JOIN = {
  type: "object",
  properties: { scope: SCOPE_SCHEMA },
  additionalProperties: true,
};

// Plan writes one draft. `path` is fixed by the task, so the tool takes only the
// program; the member table is compose's `plan` surface.
const PLAN = {
  type: "object",
  properties: {
    code: {
      type: "string",
      description:
        "The program. Use `return` for the value you want back. Members: this.cat, this.write, this.ask; primitives: devops, review. Write only `plans/<name>.md`.",
    },
    scope: SCOPE_SCHEMA,
  },
  required: ["code"],
  additionalProperties: true,
};

export const TOOL_INPUTS = Object.freeze({
  "js-manager": Object.freeze(CODE),
  "js-worker": Object.freeze(CODE),
  "js-review": Object.freeze(CODE),
  plan: Object.freeze(PLAN),
  fork: Object.freeze(FORK),
  join: Object.freeze(JOIN),
});

// The `scope` member is appended to a program's surface by the adapter, not by
// compose: it is the §6.2 annotation slot every non-exempt tool call carries.
export const SCOPE_MEMBER = "scope";
