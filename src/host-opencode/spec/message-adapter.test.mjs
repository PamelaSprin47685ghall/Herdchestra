// The request copy against the host's real `Message` shape (§6.2, §6.1).
//
// The shapes here are copied from `packages/ai/src/schema/messages.ts`
// (`ToolCallPart`, `ToolResultPart`, `ToolResultValue`) at v2.1.4. The host's
// request builder reads `part.result.type` without a guard, so a rebuilt part
// without `result` crashes the whole model request; that is what these tests
// pin, together with the hint's prefix rule, which compares messages by
// `JSON.stringify` and therefore notices any key reordering.
//
// Run with: `node --test "src/host-opencode/**/*.test.mjs"`.

import test from "node:test";
import assert from "node:assert/strict";
import fc from "fast-check";

import {
  itemsFromMessages,
  messagesFromItems,
  resultValue,
  RESULT_TYPES,
  toHostMessages,
  toHintMessages,
} from "./message-adapter.mjs";
import { extractPair, withPair, pairOf } from "./context-hook.mjs";
import { fcRuns } from "../../core/test-runs.mjs";

const CALL = (id, name = "js-worker") => ({ type: "tool-call", id, name, input: { code: "x" } });
const RESULT = (id, name = "js-worker", value = "ok") => ({
  type: "tool-result",
  id,
  name,
  result: { type: "text", value },
});

// The host's own boundaries: one assistant message may carry several calls, but
// `session/runner/to-llm-message.ts` emits ONE `tool` message per result
// (`.map(Message.tool)`), never a combined one.
const HOST = [
  { role: "user", content: [{ type: "text", text: "hi" }] },
  { role: "assistant", content: [{ type: "text", text: "working" }, CALL("c1"), CALL("c2")] },
  { role: "tool", content: [RESULT("c1")] },
  { role: "tool", content: [RESULT("c2", "js-worker", "second")] },
];

test("a host request survives the hint's shape and comes back key for key", () => {
  const there = toHintMessages(HOST);
  // The hint's two views exist for its own matching...
  assert.equal(there[1].content[1].toolCallId, "c1");
  assert.equal(there[1].content[1].toolName, "js-worker");
  assert.deepEqual(there[2].content[0].output, { type: "text", value: "ok" });
  // ...and the host's part is rebuilt exactly as it was: same keys, same order,
  // same tagged result value.
  assert.deepEqual(toHostMessages(there), HOST);
  assert.equal(JSON.stringify(toHostMessages(there)), JSON.stringify(HOST));
});

test("a tool result always carries a tagged value", () => {
  assert.deepEqual(resultValue(undefined), { type: "text", value: "" });
  assert.deepEqual(resultValue("plain"), { type: "text", value: "plain" });
  assert.deepEqual(resultValue({ ok: true }), { type: "json", value: { ok: true } });
  const kept = { type: "content", value: [{ type: "text", text: "x" }] };
  assert.equal(resultValue(kept), kept);
  // A part whose `result` went missing is repaired rather than forwarded: the
  // host's request builder dereferences `result.type`.
  const repaired = toHostMessages([
    { role: "tool", content: [{ type: "tool-result", id: "c1", name: "js-worker", output: "lost" }] },
  ]);
  assert.deepEqual(repaired[0].content[0].result, { type: "text", value: "lost" });
});

test("the Scope projection keeps both calls of one turn with their results", () => {
  const items = itemsFromMessages(HOST);
  assert.deepEqual(
    items.map((item) => item.kind),
    ["message", "message", "toolCall", "toolCall", "toolResult", "toolResult"],
  );
  const back = messagesFromItems(items);
  // The projection keeps the host's own message count and boundaries: one
  // `tool` message per result. Merging them (the previous behaviour) made the
  // next rebuilt request a different shape and the Pair Hint refused it with
  // `PREFIX_DRIFT` (`ses_e6afd9d23ffewVMIMyguNze0IJ`, 2026-11-01).
  assert.deepEqual(back, HOST);
  assert.equal(JSON.stringify(back), JSON.stringify(HOST));
});

// The consumer-visible regression for `ses_e6afd9d23ffewVMIMyguNze0IJ`: one
// assistant message with five calls, five separate result messages.
test("a turn of many calls keeps one tool message per result", () => {
  const calls = [1, 2, 3, 4, 5].map((i) => CALL("c" + i));
  const host = [
    { role: "user", content: [{ type: "text", text: "go" }] },
    { role: "assistant", content: calls },
    ...[1, 2, 3, 4, 5].map((i) => ({ role: "tool", content: [RESULT("c" + i, "js-worker", "r" + i)] })),
  ];
  const back = messagesFromItems(itemsFromMessages(host));
  assert.equal(back.length, host.length, "no message is merged away");
  assert.equal(JSON.stringify(back), JSON.stringify(host));
  assert.deepEqual(
    back.map((message) => message.role),
    ["user", "assistant", "tool", "tool", "tool", "tool", "tool"],
  );
});

test("the synthetic pair is found, removed and put back where it stood", () => {
  const pair = [
    { role: "assistant", content: [{ type: "tool-call", id: "pair-hint:0:abc", name: "", input: {} }] },
    { role: "tool", content: [{ type: "tool-result", id: "pair-hint:0:abc", name: "", result: { type: "text", value: "hint" } }] },
  ];
  const request = [HOST[0], ...pair, { role: "assistant", content: [{ type: "text", text: "after" }] }];
  const carried = extractPair(request);
  assert.equal(carried.at, 1);
  assert.deepEqual(carried.pair, pair);
  assert.deepEqual(carried.rest, [HOST[0], request[3]]);
  const rebuilt = [HOST[0], { role: "assistant", content: [{ type: "text", text: "after" }] }];
  assert.deepEqual(withPair(rebuilt, carried), request);
  // The hint's own copy after a placement carries the pair: no second one.
  assert.notEqual(pairOf(request), null);
  assert.equal(pairOf(carried.rest), null);
});

// The consumer-visible regression for `ses_e6b5fa739ffef04j5obpcnk3Ze`
// (2026-11-01). The model read an assistant message carrying prose plus a tool
// call whose result had not arrived yet; the next request rebuilt that same
// message with the call removed, and the Pair Hint refused with PREFIX_DRIFT at
// that index. What the provider receives is the assertion here, not the helper.
test("an in-flight tool call stays in the request copy the provider receives", () => {
  const history = [
    { role: "user", content: [{ type: "text", text: "go" }] },
    { role: "assistant", content: [{ type: "text", text: "working" }, CALL("c9")] },
  ];
  // The call has no result yet: the host is building the request for its own
  // step, and `c9` must still be in the assistant message the model reads.
  const copy = messagesFromItems(itemsFromMessages(history));
  const assistant = copy.find((message) => message.role === "assistant");
  assert.ok(assistant, "the assistant message survives");
  assert.deepEqual(
    assistant.content.filter((part) => part?.type === "tool-call").map((part) => part.id),
    ["c9"],
    "the pending call is not dropped from the copy",
  );

  // And once the result arrives, the same message still carries the call: the
  // bytes the model already read do not change.
  const withResult = [...history, { role: "tool", content: [RESULT("c9")] }];
  const after = messagesFromItems(itemsFromMessages(withResult));
  const assistantAfter = after.find((message) => message.role === "assistant");
  assert.deepEqual(assistantAfter.content, assistant.content, "the presented message is unchanged");

  // The half-pair guard still holds: a result whose call was removed never
  // reaches the provider.
  const orphan = messagesFromItems([
    { kind: "toolResult", member: "js-worker", callId: "gone", part: RESULT("gone") },
  ]);
  assert.deepEqual(orphan, [], "a result with no call is not sent");
});

// The consumer-visible regression for `ses_e6b13bab3ffebkFehZo4iHmZzz`
// (2026-11-01). Turn 1's `js-worker` call failed before it ran
// (`SCOPE_REFUSED`), so the host stored `state.status: "error"` with
// `executed: false`; `session/runner/to-llm-message.ts` then builds that call
// with NO result part at all. Turn 2 issued the retried call. The next request
// carried a `tool-result` part for the failed call with no `result`, and the
// adapter invented `output: {type:"text", value:""}` for it. The hint compares
// presented messages by `JSON.stringify`, so that one added key read as a
// rewritten message and the Pair Hint refused with `PREFIX_DRIFT` at index 1 —
// the failure that ended the session.
test("a result part the host built without `result` keeps no invented output", () => {
  // Exactly what the host hands the context hook: no `result`, no `output`.
  const failed = { type: "tool-result", id: "c1", name: "js-worker" };
  const there = toHintMessages([{ role: "tool", content: [failed] }]);
  assert.deepEqual(there[0].content[0], {
    type: "tool-result",
    id: "c1",
    name: "js-worker",
    toolCallId: "c1",
    toolName: "js-worker",
  });
  assert.equal("output" in there[0].content[0], false, "no key the host's copy does not have");

  // The prefix rule compares the two requests as the hint sees them. The first
  // request presented the failed result, the second carries the same part: the
  // bytes must be equal, or the next request is refused.
  const first = toHintMessages([{ role: "user", content: [{ type: "text", text: "go" }] }, { role: "tool", content: [failed] }]);
  const next = toHintMessages([{ role: "user", content: [{ type: "text", text: "go" }] }, { role: "tool", content: [failed] }]);
  assert.equal(JSON.stringify(first), JSON.stringify(next));

  // The outbound repair is untouched: a part that reaches the host without a
  // tagged `result` still gets one, because the host's request builder
  // dereferences `result.type`.
  const back = toHostMessages([{ role: "tool", content: [{ type: "tool-result", id: "c1", name: "js-worker", toolCallId: "c1", toolName: "js-worker" }] }]);
  assert.deepEqual(back[0].content[0].result, { type: "text", value: "" });
});

test("a failed call, its retry and the following request stay one prefix chain", () => {
  // Turn 1 fails: call only, no result part. The host then opens a second
  // assistant message for the retry, which succeeds with a tagged text result.
  const turn1 = [
    { role: "user", content: [{ type: "text", text: "audit" }] },
    { role: "assistant", content: [CALL("c1")] },
  ];
  const turn2 = [
    ...turn1,
    { role: "tool", content: [{ type: "tool-result", id: "c1", name: "js-worker" }] },
    { role: "assistant", content: [CALL("c2")] },
    { role: "tool", content: [RESULT("c2", "js-worker", "ok")] },
  ];
  const presented = toHintMessages(messagesFromItems(itemsFromMessages(turn1)));
  const next = toHintMessages(messagesFromItems(itemsFromMessages(turn2)));
  assert.equal(
    JSON.stringify(presented),
    JSON.stringify(next.slice(0, presented.length)),
    "the next request extends the presented one message for message",
  );
});

// The two live drifts of 2026-11-01 were both "the copy we presented is not the
// copy the host rebuilds". Both were found by reading production journals, and
// both are structural, so they are worth properties rather than examples.
//
// Property 1: the Scope projection is a function of the request, not a re-layout
// of it. For any host request, `messagesFromItems(itemsFromMessages(x))` keeps
// the message count and every message's role and part types.
//
// Property 2: the hint's copy never invents a key. For any host `tool-result`,
// the hint part carries `output` exactly when the host part carries `result`,
// and the value is the host's own value (no re-tagging).

const partArbitrary = fc.oneof(
  // The host never emits an empty text/reasoning part: `to-llm-message.ts`
  // filters `part.text !== ""` (`meaningful`) before the request is built. A
  // property over empty parts would test a shape no request can carry.
  fc.record({ type: fc.constant("text"), text: fc.string({ minLength: 1 }) }),
  fc.record({ type: fc.constant("reasoning"), text: fc.string({ minLength: 1 }) }),
  fc.record({ type: fc.constant("tool-call"), id: fc.uuid(), name: fc.constant("js-worker"), input: fc.anything() }),
);

const resultValueArbitrary = fc.oneof(
  fc.record({ type: fc.constant("text"), value: fc.string() }),
  fc.record({ type: fc.constant("json"), value: fc.anything() }),
  fc.record({ type: fc.constant("error"), value: fc.anything() }),
);

test("property: the projection keeps the host's message boundaries", () => {
  fc.assert(
    fc.property(
      fc.array(partArbitrary, { maxLength: 4 }),
      fc.array(fc.tuple(fc.uuid(), fc.string()), { maxLength: 4 }),
      (assistantParts, results) => {
        // Every result must answer a call in the request. A result with no call
        // is the half-pair the projection is REQUIRED to drop (the provider
        // rejects a result it never asked for), so a property that included one
        // would test the drop, not the boundaries.
        const request = [
          { role: "user", content: [{ type: "text", text: "go" }] },
          { role: "assistant", content: assistantParts },
          ...results.map(([id, value]) => ({
            role: "tool",
            content: [{ type: "tool-result", id, name: "js-worker", result: { type: "text", value } }],
          })),
        ];
        const callIds = new Set(assistantParts.filter((part) => part.type === "tool-call").map((part) => part.id));
        const answered = request.filter(
          (message) => message.role !== "tool" || callIds.has(message.content[0].id),
        );
        const back = messagesFromItems(itemsFromMessages(answered));
        assert.equal(back.length, answered.length, "no message is merged or dropped");
        assert.deepEqual(
          back.map((message) => message.role),
          answered.map((message) => message.role),
        );
        assert.deepEqual(
          back.map((message) => message.content.map((part) => part.type)),
          answered.map((message) => message.content.map((part) => part.type)),
        );
      },
    ),
    { ...fcRuns(60) },
  );
});

test("property: the hint copy invents no key on a tool result", () => {
  fc.assert(
    fc.property(
      fc.uuid(),
      fc.oneof(fc.constant(undefined), resultValueArbitrary),
      fc.option(fc.string(), { nil: undefined }),
      (id, result, output) => {
        const host = { type: "tool-result", id, name: "js-worker" };
        if (result !== undefined) host.result = result;
        if (output !== undefined) host.output = output;
        const [there] = toHintMessages([{ role: "tool", content: [host] }]).map((m) => m.content[0]);
        // The hint's view is a copy of the host's value, never a rewrite of it.
        if (output !== undefined) assert.deepEqual(there.output, output);
        else if (result !== undefined) assert.deepEqual(there.output, result);
        else assert.equal("output" in there, false, "no output when the host had no result");
        // And the round trip back to the host keeps the host's tagged value.
        const [back] = toHostMessages([{ role: "tool", content: [there] }]).map((m) => m.content[0]);
        assert.ok(back.result !== undefined, "the outbound part always carries a tagged result");
        assert.ok(RESULT_TYPES.includes(back.result.type));
      },
    ),
    { ...fcRuns(80) },
  );
});

test("property: a host result survives the hint shape and comes back unchanged", () => {
  fc.assert(
    fc.property(fc.uuid(), resultValueArbitrary, (id, result) => {
      const host = [{ role: "tool", content: [{ type: "tool-result", id, name: "js-worker", result }] }];
      const back = toHostMessages(toHintMessages(host));
      assert.equal(JSON.stringify(back), JSON.stringify(host), "key order and values are preserved");
    }),
    { ...fcRuns(80) },
  );
});
