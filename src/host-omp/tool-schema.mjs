// Schema builders and tool-result helpers for OMP tools (spec §7.3).
//
// OMP validates tool parameters against a schema supplied by the extension. It
// accepts an injected builder (`pi.zod`, `pi.arktype`) or a plain JSON Schema
// object (`TSchema = Type | TJsonSchema`). This adapter uses plain JSON Schema
// because the code is pure `.mjs` with no build step: no builder package is
// imported, and nothing about the schema depends on a host version.
//
// A tool result is `{ content: [{type: "text", text}], details?, isError? }`
// (`AgentToolResult`). `textResult` and `errorResult` build both shapes.
//
// What the model reads is CUE, not JSON (spec §6.3). `content[].text` is the
// only part of a result the host puts in the request (`session/messages.ts`
// converts a `toolResult` message with its `content` alone), so that text is
// the presentation, and it is rendered by `toCue`. `details` stays the plain
// object: the host keeps it beside the call, never sends it, and a caller that
// wants the structured receipt reads it there instead of parsing the text.
//
// The renderer never sees a non-enumerable key (`JSON.stringify` did not either
// — that is why a receipt's `value`/`result` is spread into a new object before
// it is presented).

import { toCue } from "../core/cue.mjs";

// A receipt as a plain object, including the fields core attaches without
// enumerating them.
//
// `core/receipt.mjs` `withExtra` defines `value` and `result` as NON-enumerable
// on purpose: the nine spec fields are the shape, and a key count that grows
// with every convenience field stops being a contract. Both `JSON.stringify`
// and `{ ...receipt }` therefore drop them — a spread is not enough, which is
// the trap this function exists to close. The model must still see the value a
// parked expression received and the result a finished run produced, so those
// two keys are read explicitly and placed after the enumerable ones.
//
// Only these two are lifted. Reading every own property would put whatever else
// a receipt carries into the model's view without anyone deciding it belongs
// there; the spec names these two as travelling for the host.
const RECEIPT_EXTRAS = Object.freeze(["value", "result"]);

export const receiptObject = (receipt) => {
  if (receipt === null || typeof receipt !== "object") return receipt;
  const plain = { ...receipt };
  for (const key of RECEIPT_EXTRAS) {
    if (Object.hasOwn(receipt, key) && !Object.hasOwn(plain, key)) plain[key] = receipt[key];
  }
  return plain;
};

// The text of one structured value, as the model reads it.
//
// A value that cannot be rendered — a cycle, a function, a bigint — must not
// turn a good tool result into a tool failure. It is presented as the same data
// with the offending branch named, so the model sees what happened and the call
// still succeeds. The fallback is a string field, never raw JSON: §6.3 forbids
// the escapes this adapter's receipts used to carry.
export const present = (value) => {
  try {
    return toCue(value);
  } catch (error) {
    const detail = error instanceof Error ? error.message : String(error);
    // The refusal is itself CUE: an inline string, fenced only if the message
    // holds a quote or a backslash. Building it with `JSON.stringify` would put
    // the very escapes §6.3 forbids inside the one message that explains a
    // rendering failure.
    return `{\n  presentable: false\n  detail: ${inlineCueString(detail)}\n}`;
  }
};

// One string, inline, per the §6.3 ladder: plain `"..."` when the body needs no
// quoting, otherwise a `#` fence deep enough to contain the body's own quotes.
const inlineCueString = (text) => {
  if (!text.includes('"') && !text.includes("\\")) return `"${text}"`;
  let level = 1;
  while (text.includes(`"${"#".repeat(level)}`)) level += 1;
  const fence = "#".repeat(level);
  return `${fence}"${text}"${fence}`;
};

// The same presentation for a tool the ADAPTER did not write.
//
// §6.3 covers every structured value that reaches a model, and a host tool's
// text is one: OMP's own `read`, `bash`, `grep` and `task` answer with JSON when
// they have a structured result, and that text goes into the request verbatim
// (`session/messages.ts` converts a `toolResult` with its `content` alone).
// This adapter cannot change those tools, but it does see their result first:
// `tool_result` runs before the host folds the result into the message.
//
// Only a text block that is entirely ONE JSON value is rewritten. A tool that
// answered prose, a diff, a numbered file, or several blocks keeps every byte —
// rewriting what cannot be recognized as structured data would be the adapter
// editing the host's answer, which is not its business. A value that is not an
// object or an array (a bare number, a quoted string) is left alone as well:
// `present` renders it identically to JSON, so the rewrite would buy nothing
// and would still touch the host's bytes.
export const presentToolText = (text) => {
  if (typeof text !== "string" || text.length < 2) return null;
  const head = text.trimStart()[0];
  if (head !== "{" && head !== "[") return null;
  let value;
  try {
    value = JSON.parse(text);
  } catch {
    return null;
  }
  if (value === null || typeof value !== "object") return null;
  return present(value);
};

export const STRING = Object.freeze({ type: "string" });
export const BOOLEAN = Object.freeze({ type: "boolean" });
export const ANY = Object.freeze({});

export const objectSchema = (properties, required = Object.keys(properties)) =>
  Object.freeze({
    type: "object",
    properties: Object.freeze({ ...properties }),
    required: Object.freeze([...required]),
    additionalProperties: true,
  });

export const arrayOf = (items) => Object.freeze({ type: "array", items });

export const textResult = (text, details = undefined) => ({
  content: [{ type: "text", text: typeof text === "string" ? text : present(text) }],
  ...(details === undefined ? {} : { details }),
});

export const errorResult = (text, details = undefined) => ({
  ...textResult(text, details),
  isError: true,
});

// A receipt is what our members return: `{ ok, ... }` or a plain object. A
// failed receipt becomes an `isError` tool result so the model sees the
// refusal instead of a success. A thrown error becomes the same shape.
export const receiptResult = (receipt) => {
  const failed = receipt !== null && typeof receipt === "object" && receipt.ok === false;
  return failed ? errorResult(present(receipt), receipt) : textResult(present(receipt), receipt);
};

export const thrownResult = (error) => {
  const code = typeof error?.code === "string" ? error.code : error?.name || "ERROR";
  const message = error instanceof Error ? error.message : String(error);
  return errorResult(`${code}: ${message}`, {
    code,
    ...(error?.details === undefined ? {} : { details: error.details }),
    // Facts an error carries beside its message survive to the model. A refusal
    // that reported what it cleaned up would otherwise lose that report at this
    // boundary, and a caller could not tell a clean rollback from a silent one
    // (measured: the fission rollback vanished here).
    ...extraFactsOf(error),
  });
};

// The enumerable own properties of an error that are not part of its identity.
// `message`, `stack` and the code are already reported; everything else is a
// fact the thrower chose to attach, so it travels with the refusal.
//
// `extraFactsOf` is exported because a program's failure record is built in
// another module (`program.mjs`) and must keep the same facts: a fission
// rollback that vanished at the tool boundary vanished a second time there.
const IDENTITY_KEYS = new Set(["name", "message", "stack", "code", "details"]);
export const extraFactsOf = (error) => {
  if (error === null || typeof error !== "object") return {};
  const facts = {};
  for (const key of Object.keys(error)) {
    if (IDENTITY_KEYS.has(key)) continue;
    facts[key] = error[key];
  }
  return facts;
};

// Run `fn` and turn both outcomes into a tool result. Every tool in this
// adapter goes through here, so a refusal never escapes as a host-level throw
// unless it is a programming error the adapter itself made.
export const guard = async (fn) => {
  try {
    return await fn();
  } catch (error) {
    return thrownResult(error);
  }
};
