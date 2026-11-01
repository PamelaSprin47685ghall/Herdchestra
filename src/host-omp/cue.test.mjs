// What the OMP adapter shows the model, and what it keeps for itself
// (spec §6.3, root `AGENTS.md`).
//
// §6.3 splits one tool result in two:
//
//   `content[].text`  what the model reads, presented as CUE;
//   `details`         the plain object the host keeps beside the call.
//
// The host converts a `toolResult` message with its `content` alone
// (`session/messages.ts`), so `details` never reaches a request: it is the
// structured receipt, and it is where a caller reads one now.
//
// These tests pin the boundary, not the renderer's bytes: `toCue` is core's and
// its exact layout belongs to core. What belongs here is that this adapter
// routes every structured value through it, that a host tool's own JSON text is
// presented too, and that a result the adapter did not author keeps every byte.

import test from "node:test";
import assert from "node:assert/strict";
import fc from "fast-check";

import { toCue } from "../core/cue.mjs";
import {
  present,
  presentToolText,
  receiptObject,
  textResult,
  errorResult,
  receiptResult,
  thrownResult,
} from "./tool-schema.mjs";
import { programTool } from "./program-tools.mjs";
import { forkTool, joinTool } from "./worker-port.mjs";
import { createToolResultHandler } from "./hooks.mjs";
import { ParkTable } from "./program.mjs";
import { ProgramState } from "../core/program.mjs";
import { fcRuns } from "../core/test-runs.mjs";

// A JSON-shaped value: what a receipt is made of, and nothing a renderer cannot
// present (no cycles, no functions, no bigints).
const jsonValue = (depth = 0) =>
  fc.letrec((tie) => ({
    value:
      depth > 3
        ? fc.oneof(
            fc.string(),
            fc.integer(),
            fc.boolean(),
            fc.constant(null),
          )
        : fc.oneof(
            fc.string(),
            fc.integer(),
            fc.boolean(),
            fc.constant(null),
            fc.array(tie("value"), { maxLength: 3 }),
            fc.dictionary(fc.string({ minLength: 1 }), tie("value"), { maxKeys: 3 }),
          ),
  })).value;

// The invariant §6.3 exists for: a multi-line body is a block, not a one-liner
// full of `\n`. That is what made a park receipt's line-numbered source
// unreadable, and it is the shape a receipt actually carries.
//
// Escaping is NOT banned outright. CUE escapes a quote in a string, and a
// fenced string containing `\` legitimately holds `\"` (an escaped backslash).
// Demanding zero escapes would assert core's ladder rather than this adapter's
// duty, and would fail on values that are correct.
//
// A newline is escaped in exactly two texts, both CUE's own rules and both
// pinned in `core/cue.test.mjs`: a text ENDING in a newline (a block would
// discard the last byte) and a text holding a bare CR (a block would drop it).
// So the rule below holds for a rendering whose strings a block can hold.
const carriesBlockableNewline = (value) => {
  if (typeof value === "string") return value.includes("\n") && !value.endsWith("\n") && !value.includes("\r");
  if (value === null || typeof value !== "object") return false;
  return (Array.isArray(value) ? value : Object.values(value)).some(carriesBlockableNewline);
};

const assertNoEscapedNewlines = (text) => {
  // Inside a block the newline is a line break, so an escape can only sit on a
  // single line. A rendering whose blockable strings are all blocks has no
  // `\n` left in it.
  assert.equal(text.includes("\\n"), false, `a newline was escaped instead of opened as a block: ${text}`);
};

test("every JSON value is presented as CUE, and the text carries no JSON escapes", () => {
  fc.assert(
    fc.property(jsonValue(), (value) => {
      const text = present(value);
      assert.equal(text, toCue(value));
      // A value whose strings all stay inline by CUE's own rules (a trailing
      // newline, a bare CR) legitimately carries the `\n` escape, so the check
      // is "no escaped newline beside those values", not "none at all".
      if (carriesBlockableNewline(value)) assertNoEscapedNewlines(text);
    }),
    { ...fcRuns(60) },
  );
});

test("a multi-line string is a CUE block aligned under its field, never an escaped one-liner", () => {
  const source = " 1 | class Js extends JsProgram {\n 2 |   async run() {}\n 3 | }";
  const text = present({ name: "call-1", source });
  assertNoEscapedNewlines(text);
  // The body keeps its own bytes, one line per line, under the field.
  for (const line of source.split("\n")) assert.equal(text.includes(line), true, `missing line: ${line}`);
  // A block opens on the field's line. Where the closing delimiter sits, and
  // which fence depth core picks, is core's decision and verified against the
  // real `cue eval`; pinning it here would test core's bytes instead of this
  // adapter's duty. What must hold is that the body is a block, indented past
  // the field so the common indent is stripped rather than eaten.
  assert.match(text, /^ {2}source: #*"""/m);
  assert.match(text, /^ {4,}"""#*$/m);
  assert.match(text, /^ {4,}1 \| class Js/m);
});

test("a string that needs a raw block gets one, and the fence grows until nothing needs escaping", () => {
  const body = 'plain\nwith """ and a \\ backslash and \\(interpolation)';
  const text = present({ stdout: body });
  assert.match(text, /stdout: #+"""/);
  assert.match(text, /"""#+\n/);
  assert.equal(text.includes("\\n"), false);
});

test("a receipt's non-enumerable value is lifted, because a spread would drop it", () => {
  // Core attaches `value`/`result` non-enumerably (`core/receipt.mjs`
  // `withExtra`), so that the nine spec fields stay the shape. Neither
  // `JSON.stringify` nor `{ ...receipt }` sees them — a spread is NOT enough,
  // and assuming it was is how the parked value silently vanished.
  const receipt = { name: "call-1", state: "completed" };
  Object.defineProperty(receipt, "value", { value: [7, 8], enumerable: false });

  assert.equal(present(receipt).includes("value"), false);
  assert.equal(Object.hasOwn({ ...receipt }, "value"), false, "a spread drops the non-enumerable key");

  const lifted = receiptObject(receipt);
  assert.equal(lifted.value[0], 7);
  // The nine spec fields keep their order; the lifted key comes after them.
  assert.deepEqual(Object.keys(lifted), ["name", "state", "value"]);
  // The width is pinned so the object is one field per line whatever core's
  // default is; the fact under test is that the key is visible at all.
  assert.match(toCue(lifted, { width: 20 }), /^\s+value: \[7, 8\]$/m);

  // An already-enumerable key is not duplicated.
  assert.deepEqual(receiptObject({ name: "n", value: 1 }), { name: "n", value: 1 });
  // A non-object is passed through untouched.
  assert.equal(receiptObject(null), null);
});

test("a value the renderer cannot present becomes a stated refusal, not a thrown tool", () => {
  const cyclic = { name: "call-1" };
  cyclic.self = cyclic;
  const text = present(cyclic);
  assert.match(text, /presentable: false/);
  assert.match(text, /detail:/);
  assertNoEscapedNewlines(text.replace(/detail: .*/, ""));
});

test("textResult, errorResult and receiptResult present CUE and keep the structured receipt", () => {
  const receipt = { ok: true, name: "w1", steered: false, started: true };
  const ok = textResult(receipt);
  assert.equal(ok.content[0].text, toCue(receipt));

  const failed = receiptResult({ ok: false, rejection: "TooFewLanes" });
  assert.equal(failed.isError, true);
  assert.equal(failed.content[0].text, toCue({ ok: false, rejection: "TooFewLanes" }));
  assert.deepEqual(failed.details, { ok: false, rejection: "TooFewLanes" });

  const succeeded = receiptResult(receipt);
  assert.equal(succeeded.isError, undefined);
  assert.equal(succeeded.details, receipt, "details is the same object, not a copy of the text");

  assert.equal(errorResult({ code: "X" }).content[0].text, toCue({ code: "X" }));
});

test("an error keeps its message line and its attached facts", () => {
  const error = Object.assign(new Error("replacePresent was refused"), {
    code: "PRESENT_NOT_REPLACED",
    rollback: { ok: true, released: ["lane#0"] },
  });
  const result = thrownResult(error);
  assert.equal(result.isError, true);
  assert.match(result.content[0].text, /PRESENT_NOT_REPLACED: replacePresent was refused/);
  // The facts ride in `details`, which is what the model-facing text never was.
  assert.deepEqual(result.details.rollback, { ok: true, released: ["lane#0"] });
});

test("a host tool's JSON text is presented as CUE, and prose is left byte-for-byte", () => {
  assert.equal(presentToolText('{"stdout":"ok\\nnext","exitCode":0}'), toCue({ stdout: "ok\nnext", exitCode: 0 }));
  assert.equal(presentToolText('[{"a":1}]'), toCue([{ a: 1 }]));

  // Not structured data: every byte stays.
  for (const text of [
    "1|hello\n2|world",
    "diff --git a/x b/x\n",
    'not json {"a":1}',
    "",
    "42",
    '"a string"',
    "null",
  ]) {
    assert.equal(presentToolText(text), null, `rewrote text it should have left alone: ${text}`);
  }
});

test("the tool_result handler presents host results and leaves this adapter's own alone", async () => {
  const handler = createToolResultHandler({});

  const hostResult = await handler({
    toolName: "bash",
    content: [{ type: "text", text: '{"stdout":"line one\\nline two","exitCode":0}' }],
  });
  assert.equal(hostResult.content[0].text, toCue({ stdout: "line one\nline two", exitCode: 0 }));
  assertNoEscapedNewlines(hostResult.content[0].text);

  // Our own tools presented their text at the definition; a second pass is not
  // a replacement, and OMP treats any returned `content` as one.
  const own = await handler({ toolName: "join", content: [{ type: "text", text: "{\n  ok: true\n}" }] });
  assert.equal(own, undefined);

  // An unrecognized text block is the host's answer, not ours to rewrite.
  const prose = await handler({ toolName: "read", content: [{ type: "text", text: "1|hello\n2|world" }] });
  assert.equal(prose, undefined);
});

test("an image block survives the host presentation in place", async () => {
  const handler = createToolResultHandler({});
  const image = { type: "image", mimeType: "image/png", data: "AAAA" };
  const out = await handler({
    toolName: "read",
    content: [image, { type: "text", text: '{"kind":"screenshot"}' }],
  });
  assert.equal(out.content[0], image);
  assert.equal(out.content[1].text, toCue({ kind: "screenshot" }));
});

test("the Scope notice reaches the model as CUE, and a refusal stays one line", async () => {
  const notice = {
    kind: "scopeColdStart",
    reason: "SCOPE_UNINITIALIZED",
    previousResets: 0,
    message: "The current retention budget is not established; the next tool call must carry resetScope.",
  };
  const settled = createToolResultHandler({ scopeSettle: async () => ({ ok: true, notice }) });
  assert.equal((await settled({ toolName: "read", content: [] })).additionalContext, toCue(notice));

  const refused = createToolResultHandler({
    scopeSettle: async () => ({ ok: false, code: "SCOPE_METOO_ONLY", message: "the turn staged meToo only" }),
  });
  assert.equal(
    (await refused({ toolName: "read", content: [] })).additionalContext,
    "scope: SCOPE_METOO_ONLY: the turn staged meToo only",
  );

  // A settled call with nothing to add adds nothing.
  const quiet = createToolResultHandler({ scopeSettle: async () => ({ ok: true, notice: null }) });
  assert.equal(await quiet({ toolName: "read", content: [] }), undefined);
});

test("the notice and the host presentation arrive in the same answer", async () => {
  const handler = createToolResultHandler({
    scopeSettle: async () => ({ ok: true, notice: { kind: "scopeColdStart", reason: "SCOPE_UNINITIALIZED" } }),
  });
  const out = await handler({ toolName: "bash", content: [{ type: "text", text: '{"exitCode":1}' }] });
  assert.equal(out.content[0].text, toCue({ exitCode: 1 }));
  assert.equal(out.additionalContext, toCue({ kind: "scopeColdStart", reason: "SCOPE_UNINITIALIZED" }));
});

test("a fork and a join present their receipts and keep them structured", async () => {
  const worker = {
    async fork(name) {
      return { name, steered: false, started: true };
    },
    async join() {
      return { ok: true, name: "w1", text: "first line\nsecond line" };
    },
  };
  const fork = await forkTool({ port: worker }).execute("c1", { name: "w1", brief: "role: coder — go" });
  assert.equal(fork.content[0].text, toCue({ name: "w1", steered: false, started: true }));
  assert.deepEqual(fork.details, { name: "w1", steered: false, started: true });

  const join = await joinTool({ port: worker }).execute("c2", {});
  assert.equal(join.content[0].text, toCue({ ok: true, name: "w1", text: "first line\nsecond line" }));
  assert.equal(join.content[0].text.includes("\\n"), false, "a worker's report is a block, not an escaped string");
  assert.equal(join.details.text, "first line\nsecond line");

  // No session: the refusal is presented like every other receipt.
  const none = await forkTool({ port: null }).execute("c3", { name: "w1" });
  assert.equal(none.content[0].text, toCue({ name: "w1", steered: false, started: false, reason: "NO_SESSION" }));
});

test("a park receipt presents its line-numbered source as a block, and its value", async () => {
  const source = " 1 | class Js extends JsProgram {\n 2 |   async run() {\n 3 |     return this.park('why');\n 4 |   }\n 5 | }";
  const receipt = {
    name: "call-park",
    state: "parked",
    reason: "explicit",
    payload: { why: "wait" },
    source,
    pendingOperations: 0,
    command: null,
    delta: "",
    remainingWaitMs: 0,
  };

  // A member that parks exactly as compose's do: the program state parks and
  // the member returns a receipt, which the runtime's guard turns into the
  // pending promise the body waits on. That is what makes the tool answer with
  // the park receipt instead of a completion.
  const program = new ProgramState("call-park");
  const parks = new ParkTable();
  const parkMember = (payload) => {
    program.park({ reason: "explicit", payload });
    return receipt;
  };
  const surface = {
    program,
    settle: async (state, result) => {
      if (!program.terminal) program.finish(state, result);
      return program;
    },
    // Compose's `ProgramSurface#receipt` always attaches `value`
    // non-enumerably; a fixture that omitted it would test a receipt no host
    // produces.
    receipt: () => {
      const view = { ...receipt };
      Object.defineProperty(view, "value", { value: null, enumerable: false, configurable: true, writable: true });
      return view;
    },
    finalReceipt: () => ({ ...receipt, result: program.result }),
    surface: () => ({ park: parkMember }),
  };
  const result = await programTool({
    surfaceName: "js-manager",
    label: "manager",
    description: "d",
    surfaceFor: () => ({ surface, program, parks }),
  }).execute("call-park", {
    source: "class Js extends JsProgram { async run() { return await this.park('why') } }",
  });

  const text = result.content[0].text;
  assertNoEscapedNewlines(text);
  // The model counts lines in the receipt's own numbering; each line is intact.
  for (const line of source.split("\n")) assert.equal(text.includes(line), true, `missing line: ${line}`);
  assert.match(text, /source: #*"""/);

  // The value a parked call already received is visible, and the same object
  // travels in `details` for a caller that reads structure instead of text.
  assert.equal(text.includes("value:"), true);
  assert.equal(result.details.result.state, "parked");
  assert.equal(Object.hasOwn(result.details.result, "value"), true);
});

test("a parked call shows the value the resumed expression received", async () => {
  // The park receipt carries `value` non-enumerably. A spread alone would drop
  // it, and the model would be told a program parked without ever seeing what
  // it got — the exact field a caller needs to decide whether to `unpark`.
  const receipt = { name: "call-park", state: "parked", reason: "explicit", payload: null, source: null };
  const program = new ProgramState("call-park");
  const parks = new ParkTable();
  const parkMember = (payload) => {
    program.park({ reason: "explicit", payload });
    return receipt;
  };
  const surface = {
    program,
    settle: async (state, result) => {
      if (!program.terminal) program.finish(state, result);
      return program;
    },
    // Exactly what compose's `ProgramSurface#receipt` answers: the view plus a
    // non-enumerable `value`.
    receipt: () => {
      const view = { ...receipt };
      Object.defineProperty(view, "value", { value: [7, 8], enumerable: false, configurable: true, writable: true });
      return view;
    },
    finalReceipt: () => ({ ...receipt, result: program.result }),
    surface: () => ({ park: parkMember }),
  };
  const result = await programTool({
    surfaceName: "js-manager",
    label: "manager",
    description: "d",
    surfaceFor: () => ({ surface, program, parks }),
  }).execute("call-park", {
    source: "class Js extends JsProgram { async run() { return await this.park('why') } }",
  });

  assert.match(result.content[0].text, /value: \[7, 8\]/);
  assert.deepEqual(result.details.result.value, [7, 8]);
});
