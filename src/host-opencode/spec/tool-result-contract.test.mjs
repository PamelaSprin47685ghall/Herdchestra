// The host's tool-result contract: a declared `output` schema requires an
// `output` key on the executor's result.
//
// `packages/core/src/tool/runtime.ts` `execute` answers
// `new Tool.Error({ message: "Tool did not return its declared output" })` when
// a tool declares `output` and the result has no `output` key, and only then
// encodes it against the schema. `tool-registrations.mjs` therefore wraps every
// executor, and these tests run the **real** executors (through `toolInfo`) and
// the **real** schemas (through the same effect JSON-Schema codec the host
// builds), because a hand-written `{ output }` fixture is exactly what hid the
// missing wrapper.
//
// Run with: `node --test "src/host-opencode/**/*.test.mjs"`.

import test from "node:test";
import assert from "node:assert/strict";
import { Effect, JsonSchema, Schema, SchemaRepresentation } from "effect";

import { toolInfo } from "./tool-registrations.mjs";
import { TOOL_OUTPUTS } from "./tool-outputs.mjs";
import { refusal } from "./refusals.mjs";
import { toCue } from "../../core/cue.mjs";

// The codec the host builds from a JSON Schema tool output
// (`packages/core/src/tool/runtime.ts` `jsonSchema`).
const codecOf = (schema) => {
  const draft =
    (typeof schema.$schema === "string" && schema.$schema.includes("draft-07")) || "definitions" in schema
      ? JsonSchema.fromSchemaDraft07(schema)
      : JsonSchema.fromSchemaDraft2020_12(schema);
  return Schema.make(SchemaRepresentation.fromJsonSchemaDocument(draft).ast);
};

const receipt = (overrides = {}) => ({
  name: "call_1",
  state: "completed",
  reason: null,
  payload: null,
  source: null,
  pendingOperations: 0,
  command: null,
  delta: "",
  remainingWaitMs: 0,
  value: 2,
  error: null,
  ...overrides,
});

// The host's own check, in the host's own order.
const hostAccepts = async (name, executor, input = {}) => {
  const info = toolInfo(name, executor);
  assert.notEqual(info.output, undefined, `${name} declares an output schema`);
  const result = await info.execute(input, { progress: async () => {} });
  assert.equal(
    Object.prototype.hasOwnProperty.call(result, "output"),
    true,
    `${name} must answer an "output" key; the host refuses a declared-output tool without it`,
  );
  assert.equal(Array.isArray(result.content), true, `${name} answers model-visible content`);
  const encoded = await Effect.runPromise(Schema.encodeUnknownEffect(codecOf(TOOL_OUTPUTS[name]))(result.output));
  return { result, encoded };
};

test("a program receipt reaches the host as output plus content", async () => {
  const { result, encoded } = await hostAccepts("js-worker", async () => receipt());
  assert.equal(encoded.state, "completed");
  assert.equal(encoded.value, 2);
  // §6.3: the model reads CUE, never raw JSON. `output` stays the JSON value
  // the schema encodes, so the two readers get two renderings of one receipt.
  assert.equal(result.content[0].text, toCue(receipt()));
  assert.equal(result.content[0].text.includes('"name":'), false, "no JSON field syntax reaches the model");
  assert.equal(result.output.name, "call_1");
});

test("a refusal receipt encodes unchanged and stays model-visible", async () => {
  // The declared schema is the park-receipt shape, so a refusal answers it with
  // the receipt fields it knows (`state: "failed"`, `reason` = the refusal code)
  // and keeps the refusal's own fields beside them.
  const refusal = {
    name: "call_1",
    state: "failed",
    reason: "ROLE_DENIED",
    payload: null,
    source: null,
    pendingOperations: 0,
    command: null,
    delta: "",
    remainingWaitMs: 0,
    ok: false,
    rejection: "ROLE_DENIED",
    message: "This tool does not belong to your role.",
  };
  const { result, encoded } = await hostAccepts("js-worker", async () => refusal);
  assert.equal(encoded.rejection, "ROLE_DENIED");
  assert.equal(encoded.state, "failed");
  assert.equal(result.content[0].text, toCue(result.output));
  assert.match(result.content[0].text, /This tool does not belong to your role\./);
});

// §6.3's reason for existing: a receipt's `source` is a line-numbered program
// and a command's stdout is multi-line. Raw JSON turned both into one escaped
// line; CUE shows them as blocks, so the model can count lines and read offsets.
test("a multi-line source and command stdout reach the model as CUE blocks", async () => {
  const parked = receipt({
    state: "parked",
    reason: "explicit",
    // A program's numbered source ends on its last line: a trailing newline is
    // not part of it (the renderer would keep such a string inline, which is
    // exact but not what a park receipt carries).
    source: '1 | const a = "q";\n2 | return a;',
    command: { stdout: "first\nsecond", stderr: "", stdoutBytes: 12, stderrBytes: 0, durationMs: 1, running: false, exitCode: 0 },
  });
  const { result } = await hostAccepts("js-worker", async () => parked);
  const text = result.content[0].text;
  assert.equal(text.includes('\\n'), false, "no escaped newline in the model's text");
  assert.equal(text.includes('\\"'), false, "no escaped quote in the model's text");
  // A `"""` block, or the raw `#"""` form when the body itself needs a literal
  // fence or carries `\r`; both keep the lines intact and unescaped.
  assert.match(text, /source: #?"""/);
  assert.match(text, /stdout: #?"""/);
  assert.equal(text, toCue(result.output));
});

test("fork, join and plan receipts pass the same contract", async () => {
  await hostAccepts("fork", async () => ({ name: "w1", steered: false, started: true }));
  // A refusal is still a tool result: it carries the receipt's own fields so the
  // declared schema encodes it, and its refusal fields ride along.
  await hostAccepts("fork", async () => ({
    name: "w1",
    steered: false,
    started: false,
    ok: false,
    rejection: "DEVOPS_BUSY",
    message: "A devops worker is already in flight; exactly one may run.",
  }));
  await hostAccepts("join", async () => ({ ok: true, record: { name: "w1", role: "coder" }, rejection: null }));
  await hostAccepts("plan", async () =>
    receipt({ name: "plan_1", state: "parked", reason: "ask", payload: { question: "which file?" }, ask: { ok: true } }),
  );
  await hostAccepts("js-manager", async () => receipt({ value: { kind: "relay", tenure: 1 } }));
  await hostAccepts("js-review", async () => receipt({ value: [[0, 4]] }));
});

test("a result that is already in the host's shape is not wrapped twice", async () => {
  const shaped = { output: receipt(), content: [{ type: "text", text: "given" }] };
  const { result } = await hostAccepts("js-worker", async () => shaped);
  assert.equal(result.content[0].text, "given");
});

test("a refusal from any tool is a valid result of its own declared schema", async () => {
  const refusals = [
    ["js-worker", "ROLE_DENIED"],
    ["js-manager", "SCOPE"],
    ["js-review", "BUSY"],
    ["plan", "GAP"],
    ["fork", "DEVOPS_BUSY"],
    ["join", "PROGRAM_BUSY"],
  ];
  for (const [name, code] of refusals) {
    const info = toolInfo(name, async () => refusal(code));
    const result = await info.execute({}, { progress: async () => {} });
    const encoded = await Effect.runPromise(Schema.encodeUnknownEffect(codecOf(TOOL_OUTPUTS[name]))(result.output));
    assert.equal(encoded.rejection, code, `${name} keeps its refusal code`);
    // The receipt fields the schema requires are filled with the refusal's own
    // facts, never left missing: the host would reject a missing one.
    if (encoded.state !== undefined) assert.equal(encoded.state, "failed", name);
    if (encoded.reason !== undefined) assert.equal(encoded.reason, code, name);
    assert.equal(result.content[0].text, toCue(result.output), name);
    assert.equal(result.content[0].text.includes(`rejection: ${JSON.stringify(code)}`), true, name);
  }
});

test("a failing executor rejects, and the host maps it to its own Tool.Error", async () => {
  const info = toolInfo("js-worker", async () => {
    throw new Error("boom");
  });
  await assert.rejects(info.execute({}, { progress: async () => {} }), /boom/);
});
