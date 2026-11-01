// Output schemas of the adapter's tools, as JSON Schema (draft 2020-12).
//
// The host encodes the value `execute` returns against these, so a member that
// returns a receipt shape the spec does not name is rejected at the boundary
// instead of reaching the model. The receipts themselves are built by core and
// compose; this file only states their shape.
//
// A park receipt has exactly the nine fields of §3.3; absent facts are null
// (or "" for `delta`), never missing keys.

const RECEIPT = {
  type: "object",
  properties: {
    name: { type: "string" },
    state: { type: "string", enum: ["running", "waiting", "parked", "completed", "failed"] },
    reason: { type: ["string", "null"] },
    payload: {},
    source: { type: ["string", "null"] },
    pendingOperations: { type: "integer" },
    command: { type: ["object", "null"] },
    delta: { type: "string" },
    remainingWaitMs: { type: "number" },
  },
  required: [
    "name",
    "state",
    "reason",
    "payload",
    "source",
    "pendingOperations",
    "command",
    "delta",
    "remainingWaitMs",
  ],
  additionalProperties: true,
};

// One `js-*` call answers with the program's receipt: parked, running view, or
// a terminal result. `value` is the program's return value when it completed.
// A Plan receipt also carries `draft`: the canonical draft path of that session
// (根 §2.2, "系统会告知确切路径"). It is declared rather than left to
// `additionalProperties` so the model's contract names the field it must write.
const PROGRAM_RESULT = {
  type: "object",
  properties: {
    ...RECEIPT.properties,
    value: {},
    error: { type: ["object", "null"] },
    draft: { type: "string" },
  },
  required: RECEIPT.required,
  additionalProperties: true,
};

const FORK_RECEIPT = {
  type: "object",
  properties: {
    name: { type: "string" },
    steered: { type: "boolean" },
    started: { type: "boolean" },
    rejection: { type: ["string", "null"] },
  },
  required: ["name", "steered", "started"],
  additionalProperties: true,
};

const JOIN_RECEIPT = {
  type: "object",
  properties: {
    ok: { type: "boolean" },
    record: { type: ["object", "null"] },
    rejection: { type: ["string", "null"] },
  },
  required: ["ok"],
  additionalProperties: true,
};

export const TOOL_OUTPUTS = Object.freeze({
  "js-manager": Object.freeze(PROGRAM_RESULT),
  "js-worker": Object.freeze(PROGRAM_RESULT),
  "js-review": Object.freeze(PROGRAM_RESULT),
  plan: Object.freeze(PROGRAM_RESULT),
  fork: Object.freeze(FORK_RECEIPT),
  join: Object.freeze(JOIN_RECEIPT),
});
