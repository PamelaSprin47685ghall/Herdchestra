// The chain's notion of "the same message" (spec §6.1 step 4).
//
// ## Why this file exists
//
// The prefix rule says a later request must begin with the bytes of the earlier
// one. Comparing the host's own message objects for that is a bug generator, and
// it produced a drift on every host, repeatedly:
//
//   - a host stamps `timestamp`, `usage`, `duration`, `responseId` and friends on
//     messages it stores, and rewrites them as a stream settles;
//   - the same tool call arrives as `tool-call` on one host and `toolCall` on
//     another, with `toolCallId`, `id` or `callId`, and `input`, `args` or
//     `arguments`;
//   - the same tool result arrives tagged `{type:"text",value}` on one path and
//     as a bare string on another, and an error as `{type:"error",value}` or as
//     `isError: true`;
//   - the same tool call is a `tool-call` PART of an assistant message on one
//     host and a `tool_use` BLOCK of one on another (Anthropic's own wire
//     shape), and the same result is a `role:"tool"` message on one host and a
//     `tool_result` block inside a `role:"user"` message on another — which is
//     what `@cline/core` 0.2.0 converts the runtime's `AgentMessage[]` into
//     after the hooks, before the provider sees it
//     (`agent-message-codec.ts:237-306`);
//   - object key insertion order differs between a message the adapter built and
//     the one the host rebuilt from its journal.
//
// Each of those is the same conversation to the model, so each of them must be
// the same message to the rule. The adapters answered it with a blacklist
// (`CHAIN_EXCLUDED`, per-part key rewriting), and a blacklist is always one host
// field behind: the next field a host adds drifts again.
//
// ## The fix: a positive allowlist
//
// `canonicalMessage` extracts ONLY the semantic content the model reads — the
// role, and for each part its type and that type's semantic fields — and sorts
// every object's keys. Anything not named here is ignored by construction, so a
// host field can never cause a drift, whatever it is called, however often it
// changes, and whatever order it is inserted in.
//
// The canonical form is what the chain compares, digests and records. The host's
// own message objects are never rewritten: `PairHint` still returns the caller's
// copies, with the pair placed in them, byte for byte.
//
// ## What is NOT canonicalised
//
// Nothing that changes what the model reads. Text is compared exactly (no
// trimming, no case folding); a different word is a different message. A
// different role is a different message. A different tool name, call id or
// input is a different message. Only bookkeeping is dropped, and only fields
// this file has never heard of are dropped by omission — never a field it names.
//
// `input` and `output` are canonicalised recursively through `stableJson`, so a
// key reordered inside a tool argument is not a change and a changed value is.

import { stableJson } from "../util.mjs";

// The semantic part types. Every other part type is host bookkeeping (a
// provider marker, an effort note, an attachment envelope): it is not text the
// model reasons over, and it is not compared.
export const CANONICAL_PART = Object.freeze({
  TEXT: "text",
  TOOL_CALL: "tool-call",
  TOOL_RESULT: "tool-result",
  REASONING: "reasoning",
});

const TYPE_ALIASES = Object.freeze({
  text: CANONICAL_PART.TEXT,
  "tool-call": CANONICAL_PART.TOOL_CALL,
  toolCall: CANONICAL_PART.TOOL_CALL,
  tool_call: CANONICAL_PART.TOOL_CALL,
  // The provider wire name for a call (`{type:"tool_use", id, name, input}`).
  tool_use: CANONICAL_PART.TOOL_CALL,
  "tool-result": CANONICAL_PART.TOOL_RESULT,
  toolResult: CANONICAL_PART.TOOL_RESULT,
  tool_result: CANONICAL_PART.TOOL_RESULT,
  reasoning: CANONICAL_PART.REASONING,
  thinking: CANONICAL_PART.REASONING,
});

// The role names hosts use for one speaker. `developer` is a system message on
// the hosts that split the prompt in two; `operator` is a user message.
const ROLE_ALIASES = Object.freeze({
  user: "user",
  operator: "user",
  human: "user",
  assistant: "assistant",
  model: "assistant",
  ai: "assistant",
  system: "system",
  developer: "system",
  tool: "tool",
  toolResult: "tool",
  function: "tool",
});

const isObject = (value) => value !== null && typeof value === "object" && !Array.isArray(value);

const textOf = (value) => (typeof value === "string" ? value : "");

// The first defined value among `keys`, so a host may use any of its own names
// for one field. `undefined` when none is present: a missing field is not the
// same as an empty one for the fields where that matters (a call id), and the
// caller decides.
const pick = (source, keys) => {
  for (const key of keys) {
    if (source[key] !== undefined && source[key] !== null) return source[key];
  }
  return undefined;
};

// The call id of a tool result part, whatever its host calls it. A result block
// the provider shape nests inside a user message names its call `tool_use_id`
// (`{type:"tool_result", tool_use_id, name, content, is_error}`), and the
// runtime's own part names it `toolCallId`; both are the same call.
const resultCallIdOf = (part) => String(pick(part, ["toolCallId", "tool_use_id", "id", "callId"]) ?? "");

// A tool call's argument object, canonicalised. A host may hand the arguments as
// an object, or as the JSON string a provider streamed; both are the same
// arguments, so both canonicalise to the same text.
const canonicalInput = (value) => {
  if (value === undefined || value === null) return null;
  if (typeof value === "string") {
    try {
      return stableJson(JSON.parse(value));
    } catch {
      return stableJson(value);
    }
  }
  return stableJson(value);
};

// The semantic text of a tool result, whatever envelope the host used:
//   { type: "text", value } | { type: "json", value } | { type: "error", value }
//   { type: "content", value: [...] }   a provider content block list
//   a bare string
//   undefined                     no result was recorded yet
// A tagged value and the same value untagged are one result, because the model
// reads the value either way.
const canonicalOutput = (value) => {
  if (value === undefined) return { kind: "absent" };
  if (value === null) return { kind: "null" };
  if (typeof value === "string") return { kind: "text", text: value };
  if (Array.isArray(value)) return { kind: "json", text: stableJson(value) };
  if (!isObject(value)) return { kind: "json", text: stableJson(value) };
  if (value.type === "content" && Array.isArray(value.value)) {
    const text = value.value
      .map((block) => (isObject(block) && typeof block.text === "string" ? block.text : stableJson(block)))
      .join("\n");
    return { kind: "text", text };
  }
  if (value.type === "text" && typeof value.value === "string") return { kind: "text", text: value.value };
  // A host may wrap a plain string as `{type:"json", value:"..."}` (cline hands
  // a text result through a structured port exactly this way). The model reads
  // the string, not the envelope, so it is text. Structure is a non-string value.
  if (value.type === "json" && typeof value.value === "string") return { kind: "text", text: value.value };
  if (value.type === "error") {
    // An error and a text result carrying the same message are the same thing
    // the model reads. The error FLAG is kept so a caller can tell them apart
    // when it wants to; the chain treats the text as the content.
    return { kind: "text", text: typeof value.value === "string" ? value.value : stableJson(value.value) };
  }
  // Any other tagged value (`json`, or a host's own tag) is structure.
  return { kind: "json", text: stableJson(value.value === undefined ? value : value.value) };
};

const canonicalPart = (part) => {
  if (typeof part === "string") return { type: CANONICAL_PART.TEXT, text: part };
  if (!isObject(part)) return null;
  const type = TYPE_ALIASES[part.type];
  if (type === undefined) return null;
  switch (type) {
    case CANONICAL_PART.TEXT:
      return { type, text: textOf(part.text) };
    case CANONICAL_PART.REASONING:
      return { type, text: textOf(part.text) };
    case CANONICAL_PART.TOOL_CALL:
      return {
        type,
        callId: String(pick(part, ["toolCallId", "id", "callId"]) ?? ""),
        toolName: String(pick(part, ["toolName", "name"]) ?? ""),
        input: canonicalInput(pick(part, ["input", "args", "arguments"])),
      };
    case CANONICAL_PART.TOOL_RESULT: {
      // A host may put the result's own content parts directly on the result
      // part (`{type:"toolResult", content:[{type:"text",text}]}` is how OMP
      // stores one), and another may nest them under `output`. Both are the
      // same result to the model, so the content-part list is read as one
      // output value before the envelope is decided.
      const direct = Array.isArray(part.content) ? part.content : undefined;
      const raw = direct === undefined
        ? pick(part, ["output", "result"])
        : pick(part, ["output", "result"]) ?? { type: "content", value: direct };
      const output = canonicalOutput(raw);
      // An error tag anywhere on the envelope is the error flag, whether the
      // host wrote `isError`, `{type:"error"}`, or neither.
      const isError =
        part.isError === true ||
        part.is_error === true ||
        part.output?.type === "error" ||
        part.result?.type === "error" ||
        output.kind === "error";
      return { type, callId: resultCallIdOf(part), isError, output };
    }
    default:
      return null;
  }
};

// One host message, as the chain compares it. The result is a plain JSON value
// whose `stableJson` is the message's identity: two messages with the same
// semantic content have the same text, whatever their host shape.
export const canonicalMessage = (message) => {
  if (!isObject(message)) return { role: null, parts: [] };
  const role = ROLE_ALIASES[message.role] ?? (typeof message.role === "string" ? message.role : null);
  // A tool result is sometimes the MESSAGE (`{role:"toolResult", toolCallId,
  // content:[{type:"text",...}]}` is how OMP stores one) and sometimes a part
  // inside a `tool` message. The model reads the same thing either way, so a
  // `tool` message whose content is not itself made of tool-result parts is
  // read as one result part built from the message.
  // A `tool` message whose content is NOT made of tool-result parts is a
  // result ENVELOPE: OMP stores one as `{role:"toolResult", toolCallId,
  // content:[{type:"text",text}]}`. `toolCallId` is the call it answers, never a
  // part type, so the content parts are the result's own content.
  if (role === "tool" && !(Array.isArray(message.content) && message.content.some((part) => isObject(part) && TYPE_ALIASES[part.type] === CANONICAL_PART.TOOL_RESULT))) {
    const canonical = canonicalPart({ ...message, type: CANONICAL_PART.TOOL_RESULT });
    return { role, parts: canonical === null ? [] : [canonical] };
  }
  // The provider shape carries tool results as `tool_result` blocks of a `user`
  // message. A message whose content holds nothing but results is the same
  // message as the runtime's `role:"tool"` one — the model reads a result either
  // way — so its role is the result role, and the chain compares one message
  // with one message. A user message that MIXES a result with the operator's own
  // text is not that shape (the operator's words are their own message), and its
  // role stays `user`.
  const resultRole =
    role === "user" &&
    Array.isArray(message.content) &&
    message.content.length > 0 &&
    message.content.every((part) => isObject(part) && TYPE_ALIASES[part.type] === CANONICAL_PART.TOOL_RESULT);
  if (resultRole) {
    const parts = [];
    for (const part of message.content) {
      const canonical = canonicalPart(part);
      if (canonical !== null) parts.push(canonical);
    }
    return { role: "tool", parts };
  }
  const raw = Array.isArray(message.content) ? message.content : typeof message.content === "string" ? [message.content] : [];
  const parts = [];
  for (const part of raw) {
    const canonical = canonicalPart(part);
    if (canonical !== null) parts.push(canonical);
  }
  return { role, parts };
};

export const canonicalMessages = (messages) => {
  if (!Array.isArray(messages)) throw new TypeError("canonicalMessages needs an array");
  return messages.map(canonicalMessage);
};

// The identity text of one message: what the chain compares and what a refusal
// record digests. Sorted keys, so insertion order never matters.
export const canonicalKey = (message) => stableJson(canonicalMessage(message));
