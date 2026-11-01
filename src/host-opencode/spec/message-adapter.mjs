// The request copy: host `Message` values in, the same values out (§6.2, §6.1).
//
// The host hands `session.hook("context")` messages shaped by
// `packages/ai/src/schema/messages.ts`:
//
//   { role: "user" | "assistant" | "tool" | "system",
//     content: [
//       { type: "text", text }                       text
//       { type: "tool-call", id, name, input }       a call the model made
//       { type: "tool-result", id, name, result }    its result, in its own message
//       { type: "reasoning", text }                  thinking
//       { type: "media", media }                     an attachment
//       { type: "compaction", ... } { type: "effort", ... }   provider-side markers
//     ] }
//
// Two other modules read these values, and both were written against the
// `@opencode/ai` value before this adapter did: core's Pair Hint places its
// synthetic pair as `{ type: "tool-call", toolName, toolCallId, input }` and
// matches the result by `toolCallId`, and core's Scope reasons over `kind`
// items. So this module keeps three views and never guesses:
//
//   host     the host's own shape, which is what the request must carry out
//   hint     core's pair shape (`toolName` / `toolCallId` / `output`)
//   items    core's Scope items (`kind`)
//
// A host part the adapter does not recognise is carried through untouched: it is
// the host's own message, and rewriting it would change bytes the provider
// already saw.
//
// A `tool-result` part without `result` is the one shape that must not pass: the
// host's own request builder reads `part.result.type` without a guard
// (`packages/core/src/session/model-request.ts` `replaceMedia`), so such a part
// crashes the whole model request. It is repaired to the value the provider
// expects — a text result — instead of being forwarded.

import { textContent } from "./relay-record.mjs";

export const KIND = Object.freeze({
  MESSAGE: "message",
  TOOL_CALL: "toolCall",
  TOOL_RESULT: "toolResult",
  THINKING: "thinking",
  PROVIDER: "provider",
});

const isObject = (value) => value !== null && typeof value === "object" && !Array.isArray(value);

// The result values the host's own history uses (`ToolResultValue` in
// `packages/ai/src/schema/messages.ts`). A value of any other shape is wrapped as
// text: the provider must never read a bare object, and the host's request
// builder dereferences `result.type` without a guard.
export const RESULT_TYPES = Object.freeze(["json", "text", "error", "content"]);

export const resultValue = (value) => {
  if (isObject(value) && RESULT_TYPES.includes(value.type)) return value;
  if (typeof value === "string") return { type: "text", value };
  if (value === undefined || value === null) return { type: "text", value: "" };
  return { type: "json", value };
};

// Host part -> hint part. The host's `id` / `name` stay: the hint matches its
// own pair by `toolCallId`, and a result by the call id of the part it answers.
// The key order is the host's own with the hint's two views inserted after
// `name`, so a round trip (`toHintMessages` then `toHostMessages`) rebuilds the
// host's part key for key: the hint compares stored messages with
// `JSON.stringify`, and a reordered key would read as a changed message.
const HINT_KEYS = Object.freeze(["toolCallId", "toolName", "output"]);

const ordered = (part, entries) => {
  const out = {};
  for (const [key, value] of entries) {
    if (value === undefined) continue;
    out[key] = value;
    if (key === "name") {
      for (const extra of HINT_KEYS) {
        if (part[extra] === undefined) continue;
        out[extra] = part[extra];
      }
    }
  }
  return out;
};

const toHintPart = (part) => {
  if (part.type !== "tool-call" && part.type !== "tool-result") return part;
  const entries = Object.entries(part);
  const callId = part.id ?? part.toolCallId;
  const toolName = part.name ?? part.toolName;
  // `output` is the host's `result`, copied and NOT reinterpreted.
  //
  // The hint compares presented bytes with `JSON.stringify`, so the copy it
  // compares must be a function of the host's part alone — every key either
  // present in the host's part or absent. Two separate live drifts came from
  // getting this wrong (2026-11-01):
  //
  //   - inventing `output: {type:"text", value:""}` for a part with no
  //     `result` added a key the host's copy did not have
  //     (`ses_e6b13bab3ffebkFehZo4iHmZzz`, drift at index 1);
  //   - re-tagging `result` through `resultValue` turned the host's untagged
  //     failure value `{error, content}` into `{type:"json", value:{...}}`,
  //     where the same part had been presented as `{type:"error", value:{...}}`
  //     (`ses_e6afd28daffejUDMAY3gZtNF0W`, drift at pair-free index 30).
  //
  // `resultValue` belongs to the OUTBOUND direction (`toHostPart`), where a
  // part without a tagged `result` would crash the host's request builder
  // (`part.result.type`). Inbound, the host's own value is the fact.
  const output = part.output ?? (part.type === "tool-result" ? part.result : undefined);
  const filled = entries.map(([key, value]) =>
    key === "id" ? [key, callId] : key === "name" ? [key, toolName] : [key, value],
  );
  const views = { toolCallId: callId, toolName };
  if (part.type === "tool-result" && output !== undefined) views.output = output;
  const base = ordered(
    views,
    filled.filter(([key]) => !HINT_KEYS.includes(key)),
  );
  // Keep any hint-only key that was already there (a re-entered copy).
  for (const key of HINT_KEYS) {
    if (part[key] !== undefined && base[key] === undefined) base[key] = part[key];
  }
  return base;
};

// Hint part -> host part. The hint's own pair carries an empty tool name, which
// the host accepts (`ToolCallPart.name` is any string) and which the provider
// adapter lowercases like every other call. The hint's extra keys are dropped,
// and `result` is always a tagged value the host's request builder can read.
const toHostPart = (part) => {
  if (part.type !== "tool-call" && part.type !== "tool-result") return part;
  const out = {};
  for (const [key, value] of Object.entries(part)) {
    if (HINT_KEYS.includes(key)) continue;
    if (key === "id") out.id = value ?? part.toolCallId;
    else if (key === "name") out.name = value ?? part.toolName ?? "";
    else out[key] = value;
  }
  if (out.id === undefined) out.id = part.toolCallId;
  if (out.name === undefined) out.name = part.toolName ?? "";
  if (part.type === "tool-result") out.result = resultValue(part.result ?? part.output);
  return out;
};

const mapContent = (message, map) => {
  if (!Array.isArray(message.content)) return message;
  return { ...message, content: message.content.map(map) };
};

// Host messages -> the same messages in the hint's shape. A message without
// `content` (a shape the host does not send) is carried as it is.
export const toHintMessages = (messages) => {
  if (!Array.isArray(messages)) throw new TypeError("messages must be an array");
  return messages.map((message) => (isObject(message) ? mapContent(message, toHintPart) : message));
};

// The hint's shape -> host messages, with every part normalized back to the
// host's vocabulary. `toHostPart` drops the hint's extra keys.
export const toHostMessages = (messages) => {
  if (!Array.isArray(messages)) throw new TypeError("messages must be an array");
  return messages.map((message) => (isObject(message) ? mapContent(message, toHostPart) : message));
};

const textOf = (parts) =>
  parts
    .filter((part) => part?.type === "text" && typeof part.text === "string")
    .map((part) => part.text)
    .join("");

// Host messages -> Scope items. A tool call and its result live in different
// messages on this host (the call inside the assistant message, the result in a
// `tool` message), and Scope pairs them by `callId`, so the order of emission
// carries no meaning.
export const itemsFromMessages = (messages) => {
  if (!Array.isArray(messages)) throw new TypeError("messages must be an array");
  const items = [];
  for (const message of messages) {
    if (!isObject(message)) throw new TypeError("each message must be an object");
    const role = message.role;
    const content = Array.isArray(message.content) ? message.content : [];
    const text = textOf(content);
    if (role === "user") {
      items.push({ kind: KIND.MESSAGE, role: "operator", text, message });
      continue;
    }
    if (role === "system") {
      items.push({ kind: KIND.MESSAGE, role: "assistant", text, message });
      continue;
    }
    if (role === "tool") {
      const results = content.filter((part) => part?.type === "tool-result");
      // A `tool` message that carries NO result part is still a message the host
      // sent. Emitting nothing for it made it invisible to the projection and
      // then gone from the copy — a message the model read silently dropped,
      // which is the one thing §6.1 forbids.
      if (results.length === 0) {
        items.push({ kind: KIND.TOOL_RESULT, member: undefined, callId: undefined, part: content[0], source: message });
        continue;
      }
      for (const part of results) {
        // `source` is the host's own message, so a projection that keeps this
        // result returns that object instead of building a new one.
        // A part may spell its id `id` (the host) or `toolCallId` (the hint's
        // own dialect, which a re-entered copy carries). Reading only `id` made
        // a call invisible to Scope while its result stayed visible.
        items.push({ kind: KIND.TOOL_RESULT, member: part.name ?? part.toolName, callId: part.id ?? part.toolCallId, part, source: message });
      }
      continue;
    }
    if (role !== "assistant") continue;
    // An assistant message is emitted once, and its calls follow it, so the
    // rebuild puts them back into the same message instead of opening a second
    // assistant turn between two tool calls.
    // A message is emitted WHOLE unless it is call-only — that is, unless every
    // part it has is a tool call. Reasoning counts as a part of the message, so
    // `{content:[reasoning, tool-call]}` is emitted once, with both parts, and
    // its call does not then open a second message beside it. Reading this as
    // "has prose, or has no call" emitted the reasoning alone and let the call
    // rebuild the message around itself — one message in, two out (found by the
    // boundary property below).
    const callOnly = content.length > 0 && content.every((part) => part?.type === "tool-call");
    if (!callOnly) {
      items.push({ kind: KIND.MESSAGE, role: "assistant", text, message });
    }
    // That `MESSAGE` item carries the whole message, reasoning parts included,
    // so a reasoning part of the SAME message must not be emitted again as its
    // own `THINKING` item: `messagesFromItems` would push the message and then
    // append its reasoning a second time, doubling every part. A reasoning part
    // is emitted on its own only when the message itself was NOT emitted, i.e.
    // a call-only message.
    const emittedWhole = !callOnly;
    for (const part of content) {
      if (part?.type === "tool-call") {
        // `source` is the assistant message that holds this call, so a call-only
        // message is carried whole rather than rebuilt around one part.
        items.push({ kind: KIND.TOOL_CALL, member: part.name ?? part.toolName, callId: part.id ?? part.toolCallId, part, source: message });
      } else if (part?.type === "reasoning" && !emittedWhole) {
        items.push({ kind: KIND.THINKING, text: part.text, part });
      } else if (part?.type === "media") {
        items.push({ kind: KIND.PROVIDER, part });
      }
    }
  }
  return items;
};

// Scope items -> host messages. A kept item contributes its original part; items
// of one source message stay together. A call whose result was removed leaves
// with it, so the provider never sees half a pair.
//
// ## A kept message is the HOST'S OWN OBJECT
//
// Scope decides WHICH messages survive, not what they say. So a message the
// projection kept is returned as the exact object the host handed over, never
// rebuilt: `messagesFromItems` used to reconstruct every message from its items,
// which produced equal-looking copies with a different key order and, for an
// assistant message, re-derived `content`. `JSON.stringify` compares key order,
// so a rebuilt message read as a rewritten one to the Pair Hint chain — a
// `PREFIX_DRIFT` on a conversation nobody changed, and the same class of defect
// the other two adapters fixed by filtering originals (`selectKept`).
//
// The one case that must still be built is a message the projection itself
// WROTE (a `removeLast` lesson, whose item carries `erasedCallId`): it has no
// original to return, and it is new bytes by definition, so the caller
// re-anchors the chain on the copy it produces.
export const messagesFromItems = (items) => {
  if (!Array.isArray(items)) throw new TypeError("items must be an array");
  const calls = new Set();
  const results = new Set();
  for (const item of items) {
    if (item.kind === KIND.TOOL_CALL) calls.add(item.callId);
    if (item.kind === KIND.TOOL_RESULT) results.add(item.callId);
  }
  const messages = [];
  // The host messages already placed, whole. A message is emitted once: its
  // `MESSAGE` item carries the whole object, and the `THINKING` / `TOOL_CALL`
  // items beside it describe parts of that same object. Pushing those again
  // opened a second assistant message for one the host sent once.
  const placed = new Set();
  for (const item of items) {
    if (item.kind === KIND.MESSAGE) {
      // A host `tool` message carries only results; its text (if any) is not a
      // message of its own, so it is left with the results below.
      if (item.message?.role === "tool") continue;
      // A projection-written message (`erasedCallId`) has no original.
      if (item.erasedCallId !== undefined) {
        messages.push({ role: item.role, content: textContent(item.text) });
        continue;
      }
      // The host's own object, untouched. Its parts are in their original order
      // because nothing here re-derives them: reordering is a rewrite of a
      // message the model already read, and `{content:[call, text]}` came back
      // as `{content:[text, call]}` when this function rebuilt it.
      messages.push(item.message);
      placed.add(item.message);
      continue;
    }
    // A part item of a message already carried whole adds nothing: it is
    // describing a message the copy already holds.
    if (item.source !== undefined && placed.has(item.source)) continue;
    // A call without a result is NOT dropped. The host hands the request over
    // with the turn's assistant message already in it, and that message may
    // carry a call whose result has not been recorded yet (a call issued in the
    // same step the request is built for). Dropping it rewrites a message the
    // model already read on the previous request, which is the one thing §6.1
    // forbids: live, `ses_e6b5fa739ffef04j5obpcnk3Ze` presented
    // `{content:[{type:"text",text:"go"}]}` and the next request wanted
    // `{content:[{type:"text",text:"go"},{type:"tool-call",id:"c9"}]}` — a
    // PREFIX_DRIFT on a message the adapter itself had shortened.
    //
    // A result without a call is still dropped: the provider rejects a result
    // it never asked for, and no host sends one. That is the half-pair the
    // guard exists for, and it is the only half that is unsafe.
    // A `tool` message with NO result part (`itemsFromMessages` emits it with
    // `callId: undefined` on purpose) is kept whole. The guard below is for a
    // REAL result whose call is missing — the provider rejects a result it never
    // asked for — and `calls` never contains `undefined`, so applying it to the
    // resultless shape dropped exactly the message the producer preserved.
    if (item.kind === KIND.TOOL_RESULT && item.callId !== undefined && !calls.has(item.callId)) continue;
    const last = messages[messages.length - 1];
    if (item.kind === KIND.TOOL_RESULT) {
      // The result's own message, the host's object, when the item carries it.
      const message = item.source ?? { role: "tool", content: [item.part] };
      messages.push(message);
      placed.add(message);
      continue;
    }
    // A call whose assistant message was already pushed with its parts in place
    // is already in the copy. Only a call from a message that was NOT emitted
    // (a call-only message) opens or joins an assistant message here.
    // A call whose assistant message was already pushed — whole, by the
    // `MESSAGE` item that carried its prose or reasoning — is already in the
    // copy. Pushing its `source` again would open a second assistant message
    // for one the host sent once.
    const alreadyPlaced =
      item.source !== undefined
        ? placed.has(item.source)
        : last?.role === "assistant" && last.content.some((part) => part?.type === "tool-call" && part.id === item.callId);
    if (alreadyPlaced) continue;
    // Only a call whose message the host did NOT emit whole (a call-only
    // message) opens one, and it opens as that very message.
    const opened = item.source ?? { role: "assistant", content: [item.part] };
    messages.push(opened);
    placed.add(opened);
  }
  // Adjacent `tool` messages are NOT merged, and the count of messages is not
  // ours to change.
  //
  // The host builds one `tool` message per result: `session/runner/
  // to-llm-message.ts` ends `assistant()` with `.map(Message.tool)`, so a turn
  // of five calls becomes five messages. Merging them here (the previous
  // behaviour) turned the request copy into a different SHAPE from the one the
  // host rebuilds next time, and the Pair Hint compares presented messages with
  // `JSON.stringify` message by message: the next request was refused with
  // `PREFIX_DRIFT` at the first index where the counts diverged. Live:
  // `ses_e6afd9d23ffewVMIMyguNze0IJ` (2026-11-01), five calls in one assistant
  // message, five results; presented as one merged `tool` message, rebuilt as
  // five.
  //
  // The Scope projection pairs a call with its result by `callId`, so it never
  // needed the merge. Keeping the host's own message boundaries is what makes
  // the projection a function of the request rather than a re-layout of it.
  return messages;
};
