// The Scope and Pair Hint request path (spec §6.1, §6.2).
//
// OMP replaces request messages in `context` handlers and the system prompt in
// `before_agent_start`. This module is the one place that turns those two host
// events into a Herdchestra request copy. It owns no host concept: it takes
// item arrays and returns item arrays.
//
// The item shape Scope understands (src/core/scope/AGENTS.md §3.4) is
//   { kind: "message", role, text }
//   { kind: "toolCall" | "toolResult", member, callId }
//   { kind: "thinking" }
//   { kind: "provider" }
// `toItems` folds an OMP message list into that shape and `fromItems` is not
// provided: Scope is a filter, so the adapter keeps the original messages that
// the projection kept, matched by index. See `selectKept`.

const roleOf = (message) => {
  if (message?.role === "user") return "operator";
  if (message?.role === "assistant") return "assistant";
  return "other";
};

const textOf = (content) => {
  if (typeof content === "string") return content;
  if (!Array.isArray(content)) return "";
  return content
    .filter((part) => part?.type === "text" && typeof part.text === "string")
    .map((part) => part.text)
    .join("");
};

// One OMP message becomes zero or more Scope items. A tool call is its own
// item; the tool result that answers it is the next item, so the pairing rule
// of the Scope projection (call followed by result) holds.
export const toItems = (messages) => {
  const items = [];
  for (const message of Array.isArray(messages) ? messages : []) {
    if (message === null || typeof message !== "object") continue;
    // OMP spells a tool result TWO ways, and both reach the hook:
    //
    //   { role: "toolResult", toolCallId, toolName, content: [...] }   its own
    //                                                                  message
    //   { role: "tool", content: [{ type: "toolResult", … }] }          a part
    //
    // Only the second was read, so the first became ASSISTANT PROSE (it fell
    // through to the generic branch) and the second produced NO item at all
    // (its part type was never matched). Either way the result left the
    // projection's view while the call stayed in it: Scope then saw a call with
    // no result, and `selectKept` — correctly — refused to send half a pair, so
    // the message vanished from the copy and the next request drifted.
    if (message.role === "toolResult") {
      items.push({
        kind: "toolResult",
        member: message.toolName,
        callId: message.toolCallId ?? message.id,
        source: message,
      });
      continue;
    }
    if (message.role === "tool") {
      const parts = Array.isArray(message.content) ? message.content : [];
      const results = parts.filter((part) => part?.type === "tool-result" || part?.type === "toolResult");
      // A `tool` message that carries NO result part is still a message the host
      // sent. Emitting nothing for it made it invisible to the projection and
      // then gone from the copy — a message the model read silently dropped,
      // which is the one thing §6.1 forbids. It is emitted as a result item with
      // no call id, so the projection sees it, and the adapter's half-pair rule
      // is what decides to drop it (deliberately, and only when it answers a
      // call the copy no longer has).
      if (results.length === 0) {
        items.push({ kind: "toolResult", member: undefined, callId: undefined, source: message });
        continue;
      }
      for (const part of results) {
        items.push({
          kind: "toolResult",
          member: part.toolName,
          callId: part.toolCallId ?? part.id,
          source: message,
        });
      }
      continue;
    }
    if (message.role === "assistant") {
      const content = Array.isArray(message.content) ? message.content : [];
      // A call part is `toolCall` as OMP emits it and `tool-call` in the hint's
      // own dialect; both name the same part. Reading only one spelling made the
      // call invisible to Scope while its result stayed visible — a half pair,
      // which the adapter then had to drop.
      const calls = content.filter((part) => part?.type === "toolCall" || part?.type === "tool-call");
      const hasText = content.some((part) => part?.type === "text" && typeof part.text === "string");
      const hasThinking = content.some((part) => part?.type === "thinking" || part?.type === "reasoning");
      if (hasText) {
        items.push({ kind: "message", role: "assistant", text: textOf(content), source: message });
      }
      if (hasThinking && !hasText) {
        items.push({ kind: "thinking", source: message });
      }
      for (const part of calls) {
        items.push({ kind: "toolCall", member: part.toolName ?? part.name, callId: part.toolCallId ?? part.id, source: message });
      }
      continue;
    }
    if (message.role === "user") {
      items.push({ kind: "message", role: "operator", text: textOf(message.content), source: message });
      continue;
    }
    items.push({ kind: "message", role: "other", text: textOf(message.content), source: message });
  }
  return items;
};

// The source of the first item AFTER `at` that the projection kept, or null.
// A projection-written message takes the place the projection gave it: right
// before the next message it kept.
const nextKeptSource = (items, at, kept) => {
  for (let index = at + 1; index < items.length; index += 1) {
    const source = items[index]?.source;
    if (source !== undefined && kept.has(source)) return source;
  }
  return null;
};

// Which original messages survived the projection. The Scope projection keeps
// or drops items; an item's `source` is the message it came from, so a message
// survives when any of its items survived. Order is the original order, which
// is also the projection's order.
export const selectKept = (messages, projectedItems) => {
  const list = Array.isArray(messages) ? messages : [];
  const kept = new Set();
  // A message the PROJECTION wrote (`erasedCallId`, a `removeLast` lesson) has
  // no source: it is new bytes the spec decided, and it must reach the copy.
  // Without this the lesson was computed and then silently dropped, so the
  // `removeLast` replacement never reached the model at all.
  const written = [];
  for (const item of Array.isArray(projectedItems) ? projectedItems : []) {
    if (item?.erasedCallId !== undefined) {
      written.push(item);
      continue;
    }
    if (item?.source !== undefined) kept.add(item.source);
  }
  // A kept message is kept WHOLE. Scope decides which messages survive; it does
  // not decide which parts of one message survive, and it must not: the model
  // has already read that message, so re-emitting it with a call removed
  // rewrites history (§6.1). A call in flight (no result yet, so no item Scope
  // could keep) is the live case that made this load-bearing.
  //
  // A message whose every item was dropped is still dropped, because `kept`
  // holds only messages some item came from: an old Scope's beats leave the
  // copy as §6.2 requires.
  // The call ids this copy carries, as the parts spell them. OMP emits the
  // camelCase spelling (`toolCall`/`toolResult`) and the hint's own dialect uses
  // the hyphenated one; `toItems` above already reads both, and this half-pair
  // rule must agree with it or a result whose call the projection dropped is
  // kept as an orphan (or a hyphen-spelled result is dropped) in one dialect.
  const CALL_PART_TYPES = new Set(["tool-call", "toolCall"]);
  const RESULT_PART_TYPES = new Set(["tool-result", "toolResult"]);
  // The id is spelled `toolCallId` by the hint's own dialect and `id` by the
  // host's assistant parts (`{type:"toolCall", id:"call_…"}` on 18.14.2).
  // Reading only the first spelling made every host-spelled call invisible
  // here: `surviving` stayed empty, the rule below dropped the result whose
  // call the copy still carried, and the adapter's filter — not Scope — removed
  // a message. `projectionBoundary` saw that as `filtered`, refused to re-base,
  // and the next request drifted. `toItems` already read both spellings; this
  // rule must answer with the same ids it does.
  const callIdOf = (part) => part?.toolCallId ?? part?.id;
  const callIds = (content) =>
    content
      .filter((part) => CALL_PART_TYPES.has(part?.type) && callIdOf(part) !== undefined)
      .map(callIdOf);
  const resultIds = (message) => {
    // A tool result is its own message under `role: "toolResult"` on OMP, and a
    // part of a `tool` message in the hint's dialect. Both answer a call.
    const own = message?.toolCallId ?? message?.id;
    if (message?.role === "toolResult" && own !== undefined) return [own];
    if (message?.role !== "tool" || !Array.isArray(message.content)) return [];
    return message.content
      .filter((part) => RESULT_PART_TYPES.has(part?.type) && callIdOf(part) !== undefined)
      .map(callIdOf);
  };
  // A result the host handed over WITHOUT its call is not part of the copy at
  // all: the call it answers is not in the request, so there is nothing for it
  // to be the half of. This is the one case where a result leaves for a reason
  // other than the projection — and it is decided by the ORIGINAL copy, not by
  // what the projection kept, so a call in flight never loses its result.
  const orphan = new Set();
  for (const id of callIds(list.flatMap((message) => (message?.role === "assistant" && Array.isArray(message.content) ? message.content : [])))) {
    orphan.add(id);
  }
  // A result whose call this copy no longer carries is the half of a pair the
  // provider rejects: it answers a call the model never made. A Scope beat
  // removes a call and its result together, so this happens only when a
  // projection dropped the assistant message that held the call (a pinned
  // result, a hand-edited book) or when the host hands the result over without
  // its call. Dropping the result is the one direction that cannot rewrite a
  // message the model already read: a call without its result is a legitimate
  // in-flight shape, and a result without its call is not a request at all.
  const surviving = new Set();
  for (const message of list) {
    if (!kept.has(message)) continue;
    if (message?.role === "assistant" && Array.isArray(message.content)) {
      for (const id of callIds(message.content)) surviving.add(id);
    }
  }
  // An assistant message keeps every tool-call part it had.
  //
  // It is dropped only when NOTHING of it survived, which is the only case the
  // projection decided. Removing one call from a message the model already read
  // rewrites it (§6.1): the host hands the request over with the turn's
  // assistant message holding a call whose result has not arrived, and a later
  // request carries the same call again. Nothing is added either: a call the
  // model did not read stays out.
  const survivors = list.filter((message) => {
    if (!kept.has(message)) return false;
    const results = resultIds(message);
    if (results.length === 0) return true;
    // A result whose call the ORIGINAL copy never carried is the host half of a
    // pair the request cannot answer: it leaves before the projection is asked.
    if (results.every((id) => !orphan.has(id))) return false;
    return results.some((id) => surviving.has(id));
  });
  // The projection's own messages go where the projection put them: before the
  // first kept message that followed them in its answer.
  for (const item of written) {
    const at = Array.isArray(projectedItems) ? projectedItems.indexOf(item) : -1;
    const after = at < 0 ? null : nextKeptSource(projectedItems, at, kept);
    const index = after === null ? survivors.length : Math.max(0, survivors.findIndex((message) => message === after));
    survivors.splice(index < 0 ? survivors.length : index, 0, {
      role: item.role ?? "assistant",
      content: [{ type: "text", text: typeof item.text === "string" ? item.text : "" }],
    });
  }
  return survivors;
};

// OMP's own mark on a message it added for ONE request is
// `agent.message.perCallContext` — a SYMBOL, and `emitContext` structuredClones
// the messages before the hook sees them, so the mark is gone by the time we
// could read it (verified: structuredClone drops symbols). The side turn
// therefore has to be recognised by what it CONTAINS, and the host's own
// builder says exactly what that is.
//
// Measured on 18.14.2 (2026-11-01, session 01a19547): OMP fires an ephemeral
// title request through the SAME `context` hook while the main turn is still
// streaming. `#buildEphemeralSnapshot` (`session/agent-session.ts:12929`)
// appends two messages a conversation never carries:
//
//   - `sideChannelNoToolsReminder`: `role: "developer"`, `attribution: "agent"`.
//     No real turn of this host sends a `developer` message to the hook, and the
//     system prompt is a separate field.
//   - the side prompt: `role: "user"`, `attribution: "agent"`, text
//     `sideChannelNoToolsReminder` is followed by the title/card prompt. An
//     operator message carries `attribution: "user"`; a hook-written notice
//     carries none.
//
// Either signal alone is a guess; together they are the host's own shape. A copy
// that matches neither is the conversation and is checked exactly as before —
// the prefix rule is not weakened for it.
const isHostAgentMessage = (message) =>
  message !== null && typeof message === "object" && message.attribution === "agent";

export const isSideChannelRequest = (messages) => {
  if (!Array.isArray(messages)) return false;
  const reminder = messages.some((message) => message?.role === "developer" && isHostAgentMessage(message));
  const sidePrompt = messages.some((message) => message?.role === "user" && isHostAgentMessage(message));
  return reminder && sidePrompt;
};

// The pair-hint channel in the same request.
//
// ## What this maps, and what it no longer decides
//
// The chain's COMPARISON is core's: `pair-hint/canonical.mjs` reads a message as
// role + ordered semantic parts and ignores every other key by construction, so
// `timestamp`, `usage`, `responseId`, `duration`, `partialArgs`, `streamIndex`
// and any future key the host invents are already outside what the chain reads.
// The blacklist that used to live here (`CHAIN_EXCLUDED`) was a second encoding
// of that rule, and two encodings drift — the host added a field, the blacklist
// did not know it, and a conversation nobody changed was refused. It is gone.
//
// What stays is the PART NAME. Core's injector locates the synthetic pair by
// reading `type: "tool-result"` and `type: "tool-call"` literally
// (`presentation.mjs` `findAllInjections`), and OMP spells the same parts
// `toolResult` and `toolCall`. Handing the host's own part names to the injector
// makes it blind to its own pair: it then places a second pair beside the first,
// and the next request carries two. So the two part names are mapped, and
// nothing else is touched — no key is added, dropped or reordered.
export const toPairHintMessages = (messages) =>
  (Array.isArray(messages) ? messages : []).map((message) => {
    if (message === null || typeof message !== "object") return message;
    if (!Array.isArray(message.content)) return message;
    const content = message.content.map((part) => {
      if (part?.type === "toolResult") return { ...part, type: "tool-result" };
      if (part?.type === "toolCall") return { ...part, type: "tool-call" };
      return part;
    });
    return content.every((part, index) => part === message.content[index]) ? message : { ...message, content };
  });

// The relay barrier (spec §5.3.2 step 3): the successor sees the operator's own
// input and the succession notice, never the conversation that triggered the
// relay. Only the request copy changes; the host's stored history is untouched.
//
// `fenceThrough` is how many leading messages of the conversation were already
// on screen when the fence was written. An operator message before that point
// was answered by the incumbent, so it goes with the conversation it belongs
// to; an operator message after it is what the successor is answering.
export const barrierFor = (messages, fence) => {
  if (fence === null || fence === undefined) return messages;
  // A fence without a notice cannot say who the successor is. Building the
  // notice anyway produced a text part with no `text` — a message that claims to
  // be the succession notice and says nothing, which is worse than a reported
  // failure: the successor would read a marker-bearing empty notice and never
  // learn that the barrier could not speak. The caller decides what a fence that
  // cannot be applied leaves (hooks.mjs keeps the operator's own message and
  // names the failure).
  if (typeof fence.notice !== "string" || fence.notice.length === 0) {
    throw new TypeError("the relay fence carries no succession notice");
  }
  const list = Array.isArray(messages) ? messages : [];
  const through = Number.isInteger(fence.fenceThrough) && fence.fenceThrough > 0 ? fence.fenceThrough : 0;
  let lastOperator = -1;
  for (let index = through; index < list.length; index += 1) {
    if (list[index]?.role === "user") lastOperator = index;
  }
  const kept = lastOperator < 0 ? [] : list.slice(lastOperator);
  // The barrier's own copy goes in front, and it is the ONLY copy this
  // function adds. A message already in the request is never removed for its
  // TEXT.
  //
  // An earlier version dropped every message whose whole text equalled the
  // notice. That was wrong in principle, not just in edge cases: an operator
  // may legitimately type anything, including exactly those bytes, and the
  // barrier exists to preserve the operator's own input (§5.3.2 step 3). Two
  // messages with the same words are still two facts — one written by the
  // operator, one by this adapter — and only an adapter-written one may be
  // dropped. Content is not identity.
  //
  // So a duplicate is recognised by the marker this adapter owns: a message
  // that carries `herdchestraSuccession` in its own metadata is ours and is
  // dropped; anything else is kept verbatim, even when its bytes match the
  // notice. Ambiguity resolves toward preserving the operator's words.
  const withoutAdapterCopy = kept.filter((message) => !isAdapterSuccessionCopy(message));
  // The notice is one text part, not a bare string, and it carries its marker.
  //
  // The shape matters to the Pair Hint: it compares whole messages for its
  // prefix chain (§6.1), so the notice must read the same on every request.
  // `toPairHintMessages` reads a bare string and one text part as the same
  // message, which is what keeps the successor's own second request an extension
  // of its first.
  return [
    {
      role: "user",
      content: [{ type: "text", text: fence.notice }],
      herdchestraSuccession: true,
    },
    ...withoutAdapterCopy,
  ];
};

// Whether a request message was written by THIS adapter as a succession copy.
//
// The marker is metadata, never text: `herdchestraSuccession` on the message or
// on the host's `custom_message` entry beside it. A message without the marker
// is kept whatever it says — content is not identity, and an operator may type
// the notice's own words. There is deliberately NO fallback to matching text:
// such a fallback deletes the operator's message, which is exactly what this
// guards against. Ambiguity resolves toward preserving the operator's words.
export const isAdapterSuccessionCopy = (message) => {
  if (message === null || typeof message !== "object") return false;
  if (message.herdchestraSuccession === true) return true;
  const details = message.details;
  return details !== null && typeof details === "object" && details.herdchestraSuccession === true;
};

