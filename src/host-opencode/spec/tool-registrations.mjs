// The tools the adapter adds (§7.1, §6).
//
// Registration is `ctx.tool.transform((editor) => editor.add(info))`. The `id`
// of a registered tool is its effective name, which is what `remove` and
// `update` take; the adapter removes the host's own tools by that same id when a
// role must not see them (narrowing.mjs runs per request, this list is the
// static half).
//
// `options.codemode` is `false`: a program is already code, and wrapping it in
// the host's code-mode tool would nest two evaluators.
//
// ## The host's result contract
//
// A tool that declares an `output` schema must answer `{ output }`; the host
// encodes `output` against the schema and derives the model-visible `content`
// itself (`packages/core/src/tool/runtime.ts` `execute`: `if (!("output" in
// result)) return new Tool.Error({ message: "Tool did not return its declared
// output" })`, then `normalizeContent(result.content, output)`). Our executors
// answer a receipt object, so the wrapping happens here, once, for every tool:
//
//   { output: receipt, content: [{ type: "text", text: toCue(receipt) }] }
//
// The two halves serve different readers and are deliberately not the same
// text. `output` is the JSON value the host encodes against the schema and
// never shows to the model, so it stays a plain object. `content` is the only
// thing the model reads, and §6.3 forbids raw JSON there: it is the same
// receipt rendered as CUE (core `toCue`), where a line-numbered `source` or a
// command's stdout is a `"""` block instead of one escaped line. The schema
// keeps guarding the receipt at the boundary, which is the reason
// `TOOL_OUTPUTS` exists. A refusal receipt carries extra keys (`rejection`,
// `gap`, `ask`, …); the schemas allow additional properties, so it encodes
// unchanged.

import { TOOL_INPUTS } from "./tool-inputs.mjs";
import { TOOL_OUTPUTS } from "./tool-outputs.mjs";
import { TOOL_DESCRIPTIONS } from "./tool-descriptions.mjs";
import { TOOL_NAMES } from "./capabilities.mjs";
import { toCue } from "../../core/cue.mjs";

export const ADAPTER_TOOL_NAMES = Object.freeze(Object.values(TOOL_NAMES));

// The host's output schemas state the fields each tool always answers: a program
// receipt's nine, a fork receipt's `name`/`steered`/`started`, a join receipt's
// `ok`. A refusal still has to answer them, so the tool's own defaults fill what
// the refusal lacks — `state: "failed"` and `reason` = the refusal code for a
// program, "nothing was delivered" for a fork, "nothing was collected" for a
// join. Nothing already present is overwritten.
const REFUSAL_DEFAULTS = Object.freeze({
  [TOOL_NAMES.manager]: Object.freeze({
    name: "",
    state: "failed",
    payload: null,
    source: null,
    pendingOperations: 0,
    command: null,
    delta: "",
    remainingWaitMs: 0,
  }),
  [TOOL_NAMES.worker]: Object.freeze({
    name: "",
    state: "failed",
    payload: null,
    source: null,
    pendingOperations: 0,
    command: null,
    delta: "",
    remainingWaitMs: 0,
  }),
  [TOOL_NAMES.review]: Object.freeze({
    name: "",
    state: "failed",
    payload: null,
    source: null,
    pendingOperations: 0,
    command: null,
    delta: "",
    remainingWaitMs: 0,
  }),
  [TOOL_NAMES.plan]: Object.freeze({
    name: "",
    state: "failed",
    payload: null,
    source: null,
    pendingOperations: 0,
    command: null,
    delta: "",
    remainingWaitMs: 0,
  }),
  [TOOL_NAMES.fork]: Object.freeze({ name: "", steered: false, started: false }),
  [TOOL_NAMES.join]: Object.freeze({ ok: false, record: null }),
});

const refusalReceipt = (name, refusal) => {
  const receipt = { ...(REFUSAL_DEFAULTS[name] ?? {}) };
  if ("state" in receipt) receipt.reason = refusal.rejection ?? null;
  for (const [key, value] of Object.entries(receipt)) {
    if (refusal[key] !== undefined) receipt[key] = refusal[key];
  }
  return { ...refusal, ...receipt };
};

// The model-visible text of a tool result (§6.3). `toCue` refuses a value it
// cannot render (a cycle, a function, a BigInt); such a value is not a receipt
// we produce, but the failure must not become a tool crash, so it falls back to
// `String`, exactly as the `JSON.stringify` fallback it replaces did.
const textOf = (value) => {
  try {
    return toCue(value);
  } catch {
    return String(value);
  }
};

// A Promise executor as the host's `Tool.Info.execute`. The returned value is
// the host's `Tool.Result` shape: `output` for the declared schema and `content`
// for the model. A thrown error is left to propagate — the host maps it into its
// own `Tool.Error` (`packages/core/src/tool/runtime.ts`).
export const asHostExecutor = (execute, name = null) => async (input, context) => {
  const result = await execute(input, context);
  if (result !== null && typeof result === "object" && "output" in result && "content" in result) return result;
  const output =
    result !== null && typeof result === "object" && result.ok === false ? refusalReceipt(name, result) : result;
  return { output, content: [{ type: "text", text: textOf(output) }] };
};

export const toolInfo = (name, execute) => {
  const input = TOOL_INPUTS[name];
  const output = TOOL_OUTPUTS[name];
  if (input === undefined || output === undefined) throw new TypeError(`no schema for tool ${name}`);
  return {
    name,
    description: TOOL_DESCRIPTIONS[name],
    input,
    output,
    options: { codemode: false },
    execute: asHostExecutor(execute, name),
  };
};

// `executors` maps every adapter tool name to its async executor. A missing one
// is a wiring error at setup, not a surprise at first call.
export const registerTools = (editor, executors) => {
  for (const name of ADAPTER_TOOL_NAMES) {
    const execute = executors[name];
    if (typeof execute !== "function") throw new TypeError(`no executor for tool ${name}`);
    editor.add(toolInfo(name, execute));
  }
};
