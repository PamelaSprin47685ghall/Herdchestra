// The Scope and Pair Hint request path (spec §6.1, §6.2).
//
// Cline exposes the request to a plugin through `beforeModel`; this module turns
// the runtime's `AgentMessage[]` into the request copy Herdchestra presents. It
// owns no host concept: it takes items and returns items.
//
// The shape `beforeModel` sees is the runtime's own (`@cline/shared`
// `agent.ts:122`), and that is the shape written here: a call is a
// `{ type: "tool-call", toolCallId, toolName, input }` part of an assistant
// message, and a result is a `{ type: "tool-result", toolCallId, toolName,
// output, isError }` part of a `role: "tool"` message. The provider conversion
// runs AFTER the hooks (`agent-message-codec.ts:237-306`), so the copy is
// returned in this shape and the host converts it.
//
// The provider wire shape is read as well, because it is what the conversion
// produces and what a capture shows: a call is a `{ type: "tool_use", id, name,
// input }` block of an assistant message, and a result is a `{ type:
// "tool_result", tool_use_id, name, content, is_error }` block of a `role:
// "user"` message. A copy in that shape is projected without loss — the call id
// and the result text are read from their provider names — and returned as it
// was written.
//
// The item shape Scope understands (`src/core/scope/AGENTS.md`) is
//   { kind: "message", role, text }      role: "operator" | "assistant" | "other"
//   { kind: "toolCall" | "toolResult", member, callId }
//   { kind: "thinking" }
//   { kind: "provider", text }           the host's own injected context
// `toItems` folds a Cline message list into that shape. Scope is a filter, so
// the adapter keeps the original messages whose items survived, matched by
// `source`; see `selectKept`.
//
// `role: "user"` is not the same thing as "the operator said this". Cline puts
// hook context, compaction summaries and display-only notices into the
// transcript as user messages; `hostUserMessageKind` names those, and they
// travel as `provider` items so Scope can trim them and nothing reads them as an
// operator turn (根 §6.1, §6.2).
//
// One more thing happens here: `toCueToolResults` rewrites the MODEL-VISIBLE
// form of every tool result (§6.3). The host sends a result it produced itself
// as a tagged value (`{ type: "json", value: {...} }` for a refused call,
// `{ type: "text", value }` for text) and its provider layer turns the tagged
// value into a JSON string — so a refusal reached the model as
// `{"error":"js-manager is not available to this session"}`. A structured
// result is rendered as CUE instead, and a text result keeps its bytes.

import { toCue } from "../core/cue.mjs";
import { DEFAULT_PINNED_MEMBERS } from "../core/scope/errors.mjs";
import { SURFACE_MEMBERS } from "../compose/program/surface.mjs";

// What the Scope projection must never trim from a request copy (§6.2, §6.1).
//
// Scope keeps the pinned members and their paired results from before the last
// `resetScope`. Two families are ours, not the model's work:
//   ""        the Pair Hint's synthetic pair, whose tool name is the empty
//             string (§6.1). Without it here, a `resetScope` would delete the
//             hint from the copy and the next request would present a new
//             placement — a prefix drift the model never caused.
//   js-review the Manager's evaluation face (§2.3), not a beat of any Scope.
//             The TOOL name is what a host call's item carries (`toItems` reads
//             `toolCall.toolName`), so pinning the tool is what keeps the
//             evaluation channel across a `resetScope`; the face's member names
//             are pinned beside it for a host that names the member.
// The rest is core's default (`assume`, `negotiate`, `askPreviousScope`).
export const PINNED_MEMBERS = Object.freeze([...DEFAULT_PINNED_MEMBERS, "", "js-review", ...SURFACE_MEMBERS["js-review"]]);

// Which semantic part a content block is, in either shape the request path can
// carry: the runtime's `tool-call`/`tool-result` parts, or the provider's
// `tool_use`/`tool_result` blocks (`agent-message-codec.ts:283-306`). A block
// of neither kind is not a pair part and is left to the reader that owns it.
export const TOOL_CALL_TYPES = Object.freeze(["tool-call", "tool_use"]);
export const TOOL_RESULT_TYPES = Object.freeze(["tool-result", "tool_result"]);

// The call a tool-call block names, and the tool it names. The provider writes
// `id`/`name`; the runtime writes `toolCallId`/`toolName`.
const callIdOf = (part) => part?.toolCallId ?? part?.id;
const callNameOf = (part) => part?.toolName ?? part?.name;

// The call a tool-result block answers. The provider nests a result in a user
// message and names its call `tool_use_id`.
const resultCallIdOf = (part) => part?.toolCallId ?? part?.tool_use_id ?? part?.id;
const resultNameOf = (part) => part?.toolName ?? part?.name;

// Whether a block is a result of either shape.
const isResultPart = (part) => TOOL_RESULT_TYPES.includes(part?.type);

// Whether a message is a result message of either shape: the runtime's
// `role:"tool"`, or a `role:"user"` message made only of result blocks.
const isResultMessage = (message) =>
  message?.role === "tool" ||
  (message?.role === "user" && Array.isArray(message.content) && message.content.length > 0 && message.content.every(isResultPart));

const textOf = (content) => {
  if (typeof content === "string") return content;
  if (!Array.isArray(content)) return "";
  return content
    .filter((part) => part?.type === "text" && typeof part.text === "string")
    .map((part) => part.text)
    .join("");
};

// One key read from a host value, or `undefined` when reading it throws. The
// host hands over whatever a tool returned, so an enumerable key can be an
// accessor that throws; every read of a host value goes through here, so such a
// value takes the render refusal instead of failing the request.
const readKey = (value, key) => {
  try {
    return value[key];
  } catch {
    return undefined;
  }
};

// A tool result's structured value, or `undefined` when the part carries text.
//
// The runtime's part carries `output`: a bare value (a string, or any value an
// in-process caller passed) or a tagged `{ type, value }` — `{type:"text"}` for
// text, `{type:"json"}` for a refusal receipt or a program receipt. The
// provider block carries its content on `content`.
//
// ONLY the host's two tags are envelopes. A bare object is the tool's own
// result and is rendered whole: a program receipt carries `name`, `state`,
// `source`, `reason`, `payload` and `value`, and unwrapping it because it
// happens to have a `value` property stripped the receipt the model is owed
// (§3.3). A `{ type: "text", value }` wrapper is not structure either: its
// value IS the model's text, and rendering it as CUE would put quotes around
// output the tool already wrote as prose.
const structuredValueOf = (value) => {
  if (value === null || value === undefined || typeof value !== "object") return undefined;
  const tag = readKey(value, "type");
  if (tag === "text") return undefined;
  if (tag === "json") {
    const inner = readKey(value, "value");
    return inner === undefined ? value : inner;
  }
  return value;
};

// What the model reads when a structured result cannot be rendered as CUE.
//
// `toCue` refuses a value that is not JSON data (a cycle, a function, a symbol,
// a bigint, a getter that throws). The old answer was the JSON the host would
// have sent, which is the one shape 根 §6.3 forbids — escape noise the model
// then misreads. A refused render fails closed instead: the content is
// withheld, the refusal says so in CUE like every other receipt, and the tool
// call is still answered, so the turn continues.
export const UNRENDERABLE_TOOL_RESULT =
  "UNRENDERABLE_TOOL_RESULT: this tool result cannot be rendered as CUE, so its content is withheld; re-run the call with a smaller or plain-data result";

// The refusal receipt, rendered by the same renderer as every other result. The
// receipt holds one plain string, so this cannot throw; if it ever does, the
// prose goes out unwrapped rather than as JSON.
const refusalText = () => {
  try {
    return toCue({ error: UNRENDERABLE_TOOL_RESULT });
  } catch {
    return UNRENDERABLE_TOOL_RESULT;
  }
};

// The model-visible text of a structured value. `null` means "not renderable":
// the caller sends the refusal instead.
const cueOrRefusal = (value) => {
  try {
    return toCue(value);
  } catch {
    return null;
  }
};

// The text a tool result shows the model: CUE for structure, the tool's own
// bytes for text, and `null` when the part already reads as the model's text
// (or carries no result at all).
//
// The live host does not hand the tagged value over intact. `beforeModel` sees
// the runtime's `AgentMessage` (`agent-message-codec.ts:296-303`), and that
// codec persists a result through `toPersistedToolResultContent`: a string
// output stays a string, anything else becomes `JSON.stringify(output)`. So a
// refused call reaches this hook as the STRING `{"type":"text","value":"…"}`,
// not as a wrapper. A string that parses as one of our own tags is that tag —
// the model must read the value, not the envelope — and a string that is
// already CUE is left alone. Everything else is the tool's own text.
const toolResultText = (part) => {
  if (part === null || typeof part !== "object") return null;
  // The provider block's content, in the place the runtime's part names `output`.
  const raw = readKey(part, "output") ?? readKey(part, "content");
  if (typeof raw === "string") {
    let parsed;
    try {
      parsed = JSON.parse(raw);
    } catch {
      // The tool's own prose, or CUE this adapter already wrote: either way its
      // bytes are what the model should read, so the part stays as it is.
      return null;
    }
    if (parsed === null || typeof parsed !== "object" || Array.isArray(parsed)) return raw;
    const tag = readKey(parsed, "type");
    // Only the host's own two tags are envelopes. A JSON object the TOOL wrote
    // (a command's stdout `{"type":"event","value":1}`) is the tool's own
    // bytes: unwrapping it would replace them with one field of it.
    if (tag !== "text" && tag !== "json") return raw;
    const value = readKey(parsed, "value");
    if (tag === "text") {
      // The host's text envelope, persisted as a string: its value IS the
      // model's text, so the envelope leaves and the bytes stay. A value that is
      // not a string is structure by another name and is rendered as CUE.
      if (typeof value === "string") return value;
      return cueOrRefusal(value ?? null) ?? refusalText();
    }
    return cueOrRefusal(value ?? null) ?? refusalText();
  }
  if (raw !== null && typeof raw === "object" && readKey(raw, "type") === "text") {
    // A `{ type: "text", value }` wrapper is the host's text, and a string
    // value IS the model's text. The host nests these: the provider's own
    // conversion wraps the codec's persisted string, so the value arrives as
    // `{"type":"text","value":"{\"type\":\"text\",\"value\":…}"}`. The
    // envelope is peeled until the value is the text itself, and a part that
    // already reads as that text is left byte-identical.
    const value = readKey(raw, "value");
    if (typeof value !== "string") return cueOrRefusal(value ?? null) ?? refusalText();
    const text = toolResultText({ output: value });
    return text === null ? null : text;
  }
  const structured = structuredValueOf(raw);
  if (structured !== undefined) return cueOrRefusal(structured) ?? refusalText();
  // A result part with no result at all: the host builds one this way when it
  // has nothing to hand over, and an empty text result is what it would send.
  return null;
};

// Rewrite every `tool-result` part's model-visible text (§6.3).
//
// Only the parts that need it are rebuilt, and only the result's own field
// changes (`output` on the runtime's part, `content` on the provider's block):
// `toolCallId`, `toolName` and every key a host put beside them stay, because
// Pair Hint compares stored messages by their semantic content
// (`canonical.mjs`), and a changed call identity would read as a changed
// message. Text results are left untouched for the same reason: their bytes
// already are what the model should read.
//
// The rewrite is idempotent: the rendered text is CUE, which `toolResultText`
// recognises and leaves alone, so a projected copy that reaches this function
// again (a caller re-projecting, a test comparing two passes) is returned as it
// stands.
// A result part in the provider's wire shape, whose model-visible text lives on
// `content` rather than on the runtime's `output`. A mixed `role: "user"`
// message can carry both kinds, so the part's own keys decide, never the
// message's role.
const isProviderResult = (part) => readKey(part, "output") === undefined && readKey(part, "content") !== undefined;

export const toCueToolResults = (messages) => {
  const list = Array.isArray(messages) ? messages : [];
  return list.map((message) => {
    if (message === null || typeof message !== "object") return message;
    // A result part is rewritten wherever it sits. A provider user message that
    // mixes text and `tool_result` blocks is not a "result message" by
    // `isResultMessage`'s all-parts test, and skipping it left that result as
    // raw JSON — invisible to the CUE rule and to Scope's pairing.
    if (!Array.isArray(message.content) || !message.content.some(isResultPart)) return message;
    let changed = false;
    const content = message.content.map((part) => {
      if (!isResultPart(part)) return part;
      // `toolResultText` is the whole decision: `null` means the part already
      // reads as text the model should see unchanged (a string result, a
      // tagged text value, CUE, or no result at all), and anything else —
      // structure, or a `text` tag whose value is not a string — is rendered as
      // CUE. Asking `structuredValueOf` first would skip the last case and
      // leave a JSON object in the request.
      const text = toolResultText(part);
      if (text === null) return part;
      changed = true;
      // The rewrite stays in the message's OWN shape: a provider block keeps
      // `content`, and the runtime's part keeps `output`.
      if (isProviderResult(part)) return { ...part, content: text };
      return { ...part, output: { type: "text", value: text } };
    });
    return changed ? { ...message, content } : message;
  });
};

export const toItems = (messages) => {
  const items = [];
  for (const message of Array.isArray(messages) ? messages : []) {
    if (message === null || typeof message !== "object") continue;
    if (isResultMessage(message)) {
      const parts = Array.isArray(message.content) ? message.content : [];
      const results = parts.filter(isResultPart);
      // A `tool` message that carries NO result part is still a message the host
      // sent. Emitting nothing for it made it invisible to the projection and
      // then gone from the copy — a message the model read silently dropped,
      // which is the one thing §6.1 forbids. It is emitted with no call id, so
      // the projection sees it and the adapter's half-pair rule decides about it
      // deliberately.
      if (results.length === 0) {
        items.push({ kind: "toolResult", member: undefined, callId: undefined, source: message });
        continue;
      }
      for (const part of results) {
        items.push({
          kind: "toolResult",
          member: resultNameOf(part),
          callId: resultCallIdOf(part),
          source: message,
        });
      }
      continue;
    }
    if (message.role === "assistant") {
      const content = Array.isArray(message.content) ? message.content : [];
      const hasText = content.some((part) => part?.type === "text" && typeof part.text === "string");
      const hasThinking = content.some((part) => part?.type === "thinking" || part?.type === "reasoning");
      if (hasText) {
        items.push({ kind: "message", role: "assistant", text: textOf(content), source: message });
      }
      if (hasThinking && !hasText) {
        items.push({ kind: "thinking", source: message });
      }
      for (const part of content) {
        if (TOOL_CALL_TYPES.includes(part?.type)) {
          items.push({ kind: "toolCall", member: callNameOf(part), callId: callIdOf(part), source: message });
        }
      }
      continue;
    }
    if (message.role === "user") {
      // A provider-shaped user message may mix its own text with `tool_result`
      // blocks. Each result is a pair Scope must see and CUE must render, and
      // the text is still a message: reading only the text made the result
      // invisible, and reading only the results dropped the operator's words.
      const content = Array.isArray(message.content) ? message.content : [];
      const results = content.filter(isResultPart);
      if (results.length > 0) {
        const hostKind = hostUserMessageKind(message);
        const text = textOf(content);
        if (text.trim().length > 0) {
          // A user message that carries results is a tool-result turn of the
          // provider's own making, so its text is host context, not the
          // operator's words: Scope keeps it wherever it sits and never counts
          // it as a beat, and no `ask` is answered with it.
          items.push({ kind: "provider", text, source: message, ...(hostKind === null ? {} : { hostKind }) });
        }
        for (const part of results) {
          items.push({
            kind: "toolResult",
            member: resultNameOf(part),
            callId: resultCallIdOf(part),
            source: message,
          });
        }
        continue;
      }
      // Root §6.1/§6.2: a user message the HOST wrote (hook context, a
      // compaction summary, a display-only notice) is not the operator's words.
      // Scope keeps provider context wherever it sits and never counts it as a
      // beat, so it travels as `provider` — the same item kind a system message
      // becomes. Reading it as `operator` let a nudge become a beat Scope had to
      // admit, and let `lastOperatorText` answer an `ask` with this adapter's
      // own text.
      const hostKind = hostUserMessageKind(message);
      if (hostKind !== null) {
        items.push({ kind: "provider", text: textOf(message.content), source: message, hostKind });
        continue;
      }
      items.push({ kind: "message", role: "operator", text: textOf(message.content), source: message });
      continue;
    }
    // Any other role in the request is the host's own injected context (a
    // system message, a summarised memory block): it is provider output, which
    // Scope keeps wherever it sits (spec §6.2), not an operator turn.
    items.push({ kind: "provider", text: textOf(message.content), source: message });
  }
  return items;
};

// The source of the first item AFTER `at` that the projection kept, or null.
const nextKeptSource = (items, at, kept) => {
  for (let index = at + 1; index < items.length; index += 1) {
    const source = items[index]?.source;
    if (source !== undefined && kept.has(source)) return source;
  }
  return null;
};

// Which original messages survived the projection. A message survives when any
// of its items survived; the order is the original order, which is also the
// projection's order.
export const selectKept = (messages, projectedItems) => {
  const list = Array.isArray(messages) ? messages : [];
  const kept = new Set();
  // A message the PROJECTION wrote (`erasedCallId`, a `removeLast` lesson) has
  // no source: it is new bytes the spec decided, and it must reach the copy.
  const written = [];
  for (const item of Array.isArray(projectedItems) ? projectedItems : []) {
    if (item?.erasedCallId !== undefined) {
      written.push(item);
      continue;
    }
    if (item?.source !== undefined) kept.add(item.source);
  }
  // A result whose call this copy no longer carries is half a pair: it answers
  // a call the model never made, and the provider rejects the request. A Scope
  // beat removes a call and its result together, so this happens only when a
  // projection dropped the assistant message that held the call, or when the
  // host hands the result over without its call.
  const surviving = new Set();
  for (const message of list) {
    if (!kept.has(message)) continue;
    if (message?.role !== "assistant" || !Array.isArray(message.content)) continue;
    for (const part of message.content) {
      if (TOOL_CALL_TYPES.includes(part?.type) && callIdOf(part) !== undefined) surviving.add(callIdOf(part));
    }
  }
  // An assistant message is re-emitted with every tool-call part it had, never
  // with fewer. The host hands the request over with the turn's assistant
  // message already holding a call whose result has not arrived yet; dropping
  // that call rewrites a message the model has just read, and the next request
  // (which carries the call again) is then refused as a prefix drift (§6.1).
  // Nothing is added either: a call the model did not read stays out.
  //
  // A message whose every item was dropped is still dropped, so an old Scope's
  // beats leave the copy as §6.2 requires.
  //
  // A result message is judged by the CALL it answers, in both directions. The
  // provider refuses a result whose call the copy no longer carries AND a call
  // whose result left, so a result whose call survived is kept even when the
  // projection dropped it (the assistant message survived for its prose), and a
  // kept result whose call is gone leaves — with the message's own words, if it
  // has any.
  const survivors = [];
  for (const message of list) {
    const content = Array.isArray(message.content) ? message.content : null;
    const resultParts = content === null ? [] : content.filter(isResultPart);
    if (resultParts.length === 0) {
      if (kept.has(message)) survivors.push(message);
      continue;
    }
    const answersKeptCall = resultParts.some((part) => {
      const id = resultCallIdOf(part);
      return id !== undefined && surviving.has(id);
    });
    if (!kept.has(message)) {
      if (!answersKeptCall) continue;
      // The projection dropped the message; the call it answers is still in the
      // copy, so the result half is restored and nothing else is.
      const parts = content.filter((part) => isResultPart(part) && surviving.has(resultCallIdOf(part)));
      survivors.push(parts.length === content.length ? message : { ...message, content: parts });
      continue;
    }
    const hasOrphan = resultParts.some((part) => {
      const id = resultCallIdOf(part);
      return id !== undefined && !surviving.has(id);
    });
    if (!hasOrphan) {
      survivors.push(message);
      continue;
    }
    const parts = content.filter((part) => {
      if (!isResultPart(part)) return true;
      const id = resultCallIdOf(part);
      return id === undefined || surviving.has(id);
    });
    if (parts.length === 0) continue;
    survivors.push({ ...message, content: parts });
  }
  // A projection-written message goes where the projection put it: right before
  // the next message it kept.
  for (const item of written) {
    const at = Array.isArray(projectedItems) ? projectedItems.indexOf(item) : -1;
    const after = at < 0 ? null : nextKeptSource(projectedItems, at, kept);
    const index = after === null ? -1 : survivors.findIndex((message) => message === after);
    survivors.splice(index < 0 ? survivors.length : index, 0, {
      role: item.role ?? "assistant",
      content: [{ type: "text", text: typeof item.text === "string" ? item.text : "" }],
    });
  }
  return survivors;
};

// The last operator message of a list: the relay barrier's cut (根 §2.4 第 4 步).
// Everything up to and including it belongs to the retired tenure.
export const lastOperatorIndex = (messages) => {
  const list = Array.isArray(messages) ? messages : [];
  for (let index = list.length - 1; index >= 0; index -= 1) {
    if (list[index]?.role !== "user") continue;
    // A user message the HOST wrote is not an operator turn
    // (`hostUserMessageKind`). The relay barrier cuts on this index, and a copy
    // that began with hook context would hand the successor this adapter's own
    // notices as though the operator had said them.
    if (hostUserMessageKind(list[index]) !== null) continue;
    return index;
  }
  return -1;
};

// Where the barrier may cut: the index of the last operator message that is a
// TURN of its own, or -1.
//
// `lastOperatorIndex` answers where the operator's words are; this answers
// where a copy may begin. A `user` message that answers a tool call belongs to
// the call before it — the host appends the tool's own output as a user turn —
// and a copy that starts there begins with an answer to a call it does not
// carry. The provider rejects such a copy, and the successor's first request is
// exactly where it would happen (its first request after a tool ran under the
// incumbent). So the cut walks back over every user message that follows an
// assistant turn, and stops at the nearest one that does not.
//
// `-1` means no operator turn of the successor's own exists in this copy; the
// caller sends the barrier's own message alone, never the retired conversation.
export const barrierCutIndex = (messages) => {
  const list = Array.isArray(messages) ? messages : [];
  for (let index = lastOperatorIndex(list); index >= 0; index = lastOperatorIndex(list.slice(0, index))) {
    const before = list[index - 1];
    if (before === undefined || before?.role !== "assistant") return index;
  }
  return -1;
};

// Whether the copy carries Cline's own compaction summary.
//
// Cline's context pipeline replaces the conversation with a summary plus its
// unchanged tail and SAVES that state, so every later turn is built from the
// compacted projection rather than the full transcript
// (`projectSessionCompactionState` in `session/models/session-compaction.ts`
// returns `[...compactedMessages, ...sourceMessages.slice(count)]`, and
// `prepareTurnForModelRequest` persists it through `saveState`).
//
// That makes compaction a DURABLE rewrite of history by a part of the spec that
// owns it — the same situation as §6.2's `resetScope` prune, and the chain must
// re-anchor on it. Unlike a prune, it does not arrive as a Scope item the
// projection decided, so the adapter has to recognise it. The host marks it:
// `isCompactionSummaryMessage` (`extensions/context/compaction-shared.ts:224`)
// reads `message.metadata.kind === "compaction_summary"`. `metadata` is a plain
// string-keyed object, so it survives whatever cloning the host does.
//
// A copy WITHOUT that mark is the conversation continuing, and is checked
// exactly as before — this does not weaken the prefix rule for it.
export const isCompactionSummary = (message) =>
  message !== null &&
  typeof message === "object" &&
  message.metadata !== null &&
  typeof message.metadata === "object" &&
  message.metadata.kind === "compaction_summary";

// Whether this copy is the host's compacted history: it carries a compaction
// summary somewhere, and its first message is one (the projection always opens
// with the summary — `projectSessionCompactionState` puts the compacted messages
// first and the tail after them).
export const isCompactedCopy = (messages) => {
  if (!Array.isArray(messages) || messages.length === 0) return false;
  if (!isCompactionSummary(messages[0])) return false;
  return messages.some(isCompactionSummary);
};

// WHICH compaction a copy is built from, or null.
//
// A copy's compaction is not identified by the summary being present — every
// request after a compaction carries one. It is identified by the summary's own
// text: the host merges a later compaction into new text, so new text means a
// new rewrite, while two requests built from the same compacted history carry
// the same text and are the same rewrite.
//
// Without this, the adapter reported `history-compacted` on every request and
// the boundary re-based each time, blessing an unauthorised rewrite inside the
// compacted history (found by `compose/prefix-state-machine.test.mjs`).
export const compactionIdentity = (messages) => {
  if (!isCompactedCopy(messages)) return null;
  const summary = messages[0];
  const parts = Array.isArray(summary.content) ? summary.content : [];
  return parts.map((part) => (typeof part?.text === "string" ? part.text : "")).join("");
};

// The notice a successor reads before its own operator turn, as a message.
//
// The other two adapters put it in front of the copy, and the successor's whole
// §2.4 step-3 view is "the notice plus the operator's own words". Without it the
// successor's first request begins with a bare operator turn, which is also the
// retired tenure's first turn when the relay answers it: the model cannot tell
// it is continuing somebody else's work.
//
// It is one text part, not a bare string, because Pair Hint compares whole
// messages and the two shapes are two messages to it.
export const successionNotice = (fence) => {
  const text = typeof fence?.notice === "string" && fence.notice.length > 0 ? fence.notice : null;
  return text === null ? [] : [{ role: "user", content: [{ type: "text", text }] }];
};

// A user message the HOST wrote, not the operator (root §6.1, §6.2).
//
// Cline puts three things into the transcript as `user` messages that nobody
// typed into the chat:
//
//   hookContext   the aggregated `appendContext` of this adapter's own hooks and
//                 the host's, flushed as one `<hook_context ...>` message
//                 (`agent-runtime.ts` `formatHookContextBlock`,
//                 `flushPendingHookContexts`);
//   compaction    the context pipeline's summary
//                 (`metadata.kind === "compaction_summary"`);
//   notice        any message the host marked display-only system/status/error
//                 text (`metadata.displayRole`, the same field the host's own
//                 `gT` reads).
//
// The distinction is load-bearing. Scope trims beats by role, and an operator
// turn is never trimmed; and an `ask` is resumed with the operator's words, so a
// nudge that happened to land as the last user message would answer a question
// the operator never saw. The markers are the host's own, not an inference from
// the text: a message is host-generated exactly when the host says so.
export const HOST_MESSAGE_KINDS = Object.freeze({
  HOOK_CONTEXT: "hook-context",
  COMPACTION: "compaction",
  NOTICE: "notice",
});

// The host's `displayRole` values that are not the operator's words
// (`agent-runtime.ts:1108-1111`, `@cline/core` `gT`).
const HOST_DISPLAY_ROLES = Object.freeze(["system", "status", "error"]);

// `null` when this user message is the operator's, otherwise which host fact
// wrote it.
export const hostUserMessageKind = (message) => {
  if (message === null || typeof message !== "object" || message.role !== "user") return null;
  if (isCompactionSummary(message)) return HOST_MESSAGE_KINDS.COMPACTION;
  const metadata = message.metadata;
  if (metadata !== null && typeof metadata === "object") {
    if (typeof metadata.displayRole === "string" && HOST_DISPLAY_ROLES.includes(metadata.displayRole.trim().toLowerCase())) {
      return HOST_MESSAGE_KINDS.NOTICE;
    }
  }
  // The host writes the block's own opening tag; a tool result the operator's
  // work produced may QUOTE such a block, so only a message that STARTS with
  // the tag is the host's. `formatHookContextBlock` trims its body and opens
  // with `<hook_context source="...">`.
  const text = textOf(message.content).trimStart();
  if (/^<hook_context\b/i.test(text)) return HOST_MESSAGE_KINDS.HOOK_CONTEXT;
  return null;
};

// The operator's words in a request: the text of the LAST user message the
// OPERATOR wrote, or `null` when the request carries none. This is what an `ask`
// is resumed with — a host-written nudge or compaction summary is not an answer.
export const lastOperatorText = (messages) => {
  const list = Array.isArray(messages) ? messages : [];
  for (let index = list.length - 1; index >= 0; index -= 1) {
    if (list[index]?.role !== "user") continue;
    if (hostUserMessageKind(list[index]) !== null) continue;
    // A user message that carries a tool result is the host's turn, not the
    // operator's — whether it is all results or a provider message mixing text
    // and results. A trailing tool result must not hide the answer above it.
    const content = Array.isArray(list[index].content) ? list[index].content : null;
    if (content !== null && content.some(isResultPart)) continue;
    const words = textOf(list[index].content).trim();
    // An image-only or empty user message is not an answer either. The scan
    // continues to the operator's earlier words instead of answering the
    // question with nothing.
    if (words.length === 0) continue;
    return words;
  }
  return null;
};

// Pair Hint's message shape. A tool call is a content part inside an assistant
// message and the result is a `tool` message; that is the pair shape once the
// parts keep `toolName`, `toolCallId` and `output`.
//
// A copy in the provider's wire shape keeps ITS names (`id`/`name` on a
// `tool_use`, `tool_use_id`/`name` on a `tool_result`): the canonical rule reads
// both spellings as one message (`canonical.mjs`), and rewriting one into the
// other would change bytes the host built for no gain.
//
// The chain compares exactly this copy: `session.mjs#projectMessages` hands the
// projection's answer to `PairHint.project`, which journals it as the
// `presented` record and measures the next request against it. The PROVIDER
// reads the same copy after the host's own conversion, which re-wraps a
// non-string tool output (`toPersistedToolResultContent` →
// `{"type":"text","value":…}`), so the two differ in envelope only: the
// `presented` record carries `{type:"text", value:"{ok: true, value: 2}"}`, the
// capture the model read carries `"{ok: true, value: 2}"`. §6.3 is about the
// latter, so a check of what the model read reads the capture, never the journal.
export const toPairHintMessages = (messages) =>
  (Array.isArray(messages) ? messages : []).map((message) => {
    if (message?.role !== "assistant" || !Array.isArray(message.content)) return message;
    return {
      ...message,
      content: message.content.map((part) =>
        part?.type === "tool-call"
          ? { type: "tool-call", toolName: part.toolName, toolCallId: part.toolCallId, input: part.input }
          : part,
      ),
    };
  });
