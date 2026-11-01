// Same-epoch prefix stability, across all three host request projections
// (根 §6.1, §6.2, §6.3).
//
// One rule holds for every host: the second request of a tenure begins with
// every message the first request sent, byte for byte. A message the model has
// read may not change under it, and no tool call may appear or disappear
// between two requests that describe the same conversation.
//
// The rule is the same; the ways to break it are host-shaped, and each one was
// found live:
//
//   CUE transformation (§6.3)  a structured tool result is rendered as CUE. The
//                              render must be a function of the value alone, so
//                              the next request renders the same bytes —
//                              including a value the renderer refuses.
//   Scope (§6.2)               the projection drops beats. A message it keeps
//                              is kept whole, every tool call it had stays, and
//                              a result leaves with the call it answers: half a
//                              pair is not a request.
//   Tool states                a call in flight, its result arriving, a call the
//                              host never executed, a result the host built
//                              without one.
//   Retries and restart        a session that restarts rebuilds its facts from
//                              the journal; the chain it continues is the one
//                              it presented before the restart.
//   Relay barrier (§2.4 第 3 步) the successor's first request is a legitimate new
//                              chain; its second one must extend it, and the
//                              cut may never leave a tool answer first.
//
// Each case drives the REAL projection of the adapter it names — with a real
// PairHint and a real journal on disk — and compares consecutive requests the
// way the hint compares them: JSON.stringify, message for message, with the
// synthetic pair removed from both sides (`PairHint#project` compares the
// pair-free copies, so that is what "the same prefix" means here).
//
// Run with: `node --test "src/host-omp/prefix-stability.test.mjs"`.

import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import fc from "fast-check";

import { PairHint } from "../core/pair-hint/pair-hint.mjs";
import { JournalStore } from "../core/pair-hint/facts.mjs";
import { barrierCutIndex, successionNotice, toCueToolResults, toItems as clineItems, selectKept as clineKept } from "../host-cline/request.mjs";
import { createContextHandler } from "./hooks.mjs";
import { selectKept as ompKept, toItems as ompItems, toPairHintMessages as ompHintMessages } from "./request.mjs";
import { canonicalKey } from "../core/pair-hint/canonical.mjs";
import { requestRecord } from "./drift-log.mjs";
import { fcRuns } from "../core/test-runs.mjs";

// ---------------------------------------------------------------- the rule

// Two requests stand in one prefix chain when the later one begins with the
// earlier one, message for message. This is `PairHint#project`'s comparison,
// stated once so every case below reports the same way.
const firstDifference = (previous, next) => {
  for (let index = 0; index < Math.min(previous.length, next.length); index += 1) {
    if (JSON.stringify(previous[index]) !== JSON.stringify(next[index])) return index;
  }
  // A later request that is longer is the normal case: every earlier message
  // matched, so the later one extends it. Only a SHORTER one breaks the prefix.
  return next.length < previous.length ? next.length : -1;
};

const assertExtends = (previous, next, what) => {
  const index = firstDifference(previous, next);
  assert.equal(
    index,
    -1,
    `${what}: the next request does not begin with the previous one (first difference at ${index}; ${previous.length} vs ${next.length} messages)\nprevious: ${JSON.stringify(previous[index])}\nnext:     ${JSON.stringify(next[index])}`,
  );
};

// ------------------------------------------------------------- fixtures

// Every root this file creates is removed once at exit. The property runs make
// one root per case, so a single sweep is the only cleanup that cannot miss one.
const ROOTS = [];
process.once("exit", () => {
  for (const root of ROOTS) {
    try {
      fs.rmSync(root, { recursive: true, force: true });
    } catch {}
  }
});
const tempRoot = () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "herd-prefix-"));
  ROOTS.push(root);
  return root;
};

// A PairHint over a real journal under a fresh root, as every adapter opens it.
// `root` is passed in so one case can reopen the same journal.
const openHint = async (root, sessionId) => {
  const hint = await PairHint.open({ root, sessionId, store: new JournalStore({ root, sessionId }) });
  await hint.bind({ ladder: { explicit: "English" } });
  return hint;
};

// The pair-free copy: what `PairHint#project` compares. The pair is
// presentation, and the fact it carries names its own placement, so two
// requests of one epoch legitimately spell the pair's `input` differently.
const pairFree = (hint, messages) => hint.injector.withoutInjection(messages);

// The chain rule as `PairHint#project` applies it: both requests are made
// pair-free first, then the later one must begin with the earlier one.
const assertChain = (hint, previous, next, what) =>
  assertExtends(pairFree(hint, previous), pairFree(hint, next), what);

// The OMP adapter's projection, as `SessionState#pairHintProject` composes it:
// the chain's shape in, the request copy out.
const ompProject = (hint) => async (messages) => (await hint.project({ messages: ompHintMessages(messages) })).messages;

// The Cline projection's two stages in the adapter's order (§6.3, then §6.2).
const clineProject = (messages) => clineKept(messages, clineItems(toCueToolResults(messages)));

// A structured value a tool may return. `toCue` refuses a few on purpose (a
// cycle, a bigint, a function, a symbol), which is the interesting half: a
// refusal must be as stable as a render.
const jsonValue = fc.letrec((tie) => ({
  value: fc.oneof(
    { depthSize: "small", withCrossShrink: true },
    fc.string({ maxLength: 12 }),
    fc.integer(),
    fc.double({ noNaN: true, noDefaultInfinity: true }),
    fc.boolean(),
    fc.constant(null),
    fc.array(tie("value"), { maxLength: 3 }),
    fc.dictionary(fc.string({ minLength: 1, maxLength: 6 }), tie("value"), { maxKeys: 3 }),
  ),
})).value;

const operator = (text) => ({ role: "user", content: [{ type: "text", text }] });

// The OMP host's own message shape: a `tool-call` part inside an assistant
// message, a `tool-result` part inside a `tool` message. These are the names
// `toItems` and the Pair Hint chain read (a `toolCall` part is neither).
const hostTurn = (parts) => {
  const messages = [operator("go")];
  let call = 0;
  let prose = 0;
  for (const part of parts) {
    if (part === "prose") {
      prose += 1;
      messages.push({ role: "assistant", content: [{ type: "text", text: `step ${prose}` }] });
    } else if (part === "thinking") {
      messages.push({ role: "assistant", content: [{ type: "thinking", text: "hmm" }] });
    } else if (part === "call") {
      call += 1;
      messages.push({
        role: "assistant",
        content: [{ type: "tool-call", toolName: "js-worker", toolCallId: `c${call}`, input: { n: call } }],
      });
    } else if (part === "result") {
      messages.push({
        role: "tool",
        content: [{ type: "tool-result", toolName: "js-worker", toolCallId: `c${call}`, output: { type: "text", value: `r${call}` } }],
      });
    }
  }
  return messages;
};

// Whether the copy still carries the call a result message answers.
const hasCall = (messages, result) => {
  const ids = new Set(
    messages.flatMap((message) =>
      message.role === "assistant" && Array.isArray(message.content)
        ? message.content.filter((part) => part?.type === "tool-call").map((part) => part.toolCallId)
        : [],
    ),
  );
  return (result.content ?? []).some((part) => ids.has(part.toolCallId));
};

const turnPart = fc.constantFrom("prose", "call", "result", "thinking");
const turn = fc.array(turnPart, { maxLength: 6 });

// ------------------------------------------------------------ CUE (§6.3)

test("a structured tool result renders to the same bytes on every request", () => {
  // The projection runs once per request over a history the host rebuilds, so
  // the same value must produce the same text every time — and the render must
  // be idempotent, because the copy that reached the hint is the copy the next
  // request carries.
  fc.assert(
    fc.property(jsonValue, fc.string({ maxLength: 8 }), (value, id) => {
      const message = {
        role: "tool",
        content: [{ type: "tool-result", toolName: "js-worker", toolCallId: id, output: { type: "json", value } }],
      };
      const first = toCueToolResults([message]);
      assert.deepEqual(toCueToolResults([message]), first, "the same result renders the same message");
      assert.deepEqual(toCueToolResults(first), first, "the render is idempotent");
      // The result's identity is not part of the rewrite: the pair matches its
      // call by `toolCallId`, so a rewrite that dropped it would silently
      // unanswer the call.
      const part = first[0].content[0];
      assert.equal(part.toolCallId, id);
      assert.equal(part.toolName, "js-worker");
      assert.equal(part.type, "tool-result");
      // The model reads a string, never JSON syntax wrapped around one.
      assert.equal(typeof part.output.value, "string");
    }),
    { ...fcRuns(200) },
  );
});

test("a value CUE refuses becomes the same refusal on every request", () => {
  const cyclic = { name: "loop" };
  cyclic.self = cyclic;
  for (const value of [cyclic, { big: 10n }, { fn: () => 1 }, { sym: Symbol("s") }]) {
    const message = {
      role: "tool",
      content: [{ type: "tool-result", toolName: "js-worker", toolCallId: "c", output: { type: "json", value } }],
    };
    const first = toCueToolResults([message]);
    assert.deepEqual(toCueToolResults([message]), first, "a refused render is a stable message too");
    assert.match(first[0].content[0].output.value, /UNRENDERABLE_TOOL_RESULT/);
  }
});

test("the CUE rewrite leaves a text result byte for byte", () => {
  // A text result already is what the model should read: rewriting it would put
  // one request's rendering against another's original.
  const message = {
    role: "tool",
    content: [{ type: "tool-result", toolName: "js-worker", toolCallId: "c", output: { type: "text", value: "{a: 1}" } }],
  };
  assert.equal(toCueToolResults([message])[0], message, "an unchanged message is the same object");
});

// ------------------------------------------------------------ Scope (§6.2)

test("a Scope projection keeps every message it keeps whole", () => {
  // The defect this pins: the filter used to re-emit an assistant message with
  // only the calls the projection kept. A call in flight is not in the
  // projection's answer (it has no result yet), so the copy lost it — and the
  // next request, which carries the call again, no longer began with the bytes
  // the model had read.
  fc.assert(
    fc.property(turn, (parts) => {
      const messages = hostTurn(parts);
      const items = ompItems(messages);
      // The full projection keeps a call in flight and its answer; a result
      // whose call is not in the copy is half a pair and leaves (asserted
      // below, on its own).
      const whole = messages.filter((message) => message.role !== "tool" || hasCall(messages, message));
      assert.deepEqual(ompKept(messages, items), whole, "a projection that drops nothing keeps everything it may send");
      // A projection that keeps a strict subset keeps each survivor unchanged,
      // in the original order, and never re-cuts one.
      for (let drop = 0; drop < items.length; drop += 1) {
        const kept = ompKept(messages, items.filter((_, index) => index !== drop));
        for (const message of kept) {
          assert.ok(messages.includes(message), "a kept message is one of the original objects, unchanged");
        }
        assert.deepEqual(kept, messages.filter((message) => kept.includes(message)), "kept messages keep the original order");
      }
    }),
    { ...fcRuns(120) },
  );
});

test("a tool result never survives the call it answers", () => {
  // Half a pair is not a request: the provider rejects an answer to a call the
  // model never made. Both adapters drop such a result rather than send it.
  const messages = hostTurn(["call", "result", "prose"]);
  const kept = ompKept(messages, ompItems(messages).filter((item) => item.kind !== "toolCall"));
  assert.equal(kept.some((message) => message.role === "tool"), false, "the result left with its call");
  const clineMessages = messages.map((message) =>
    message.role === "tool" ? { ...message, content: message.content.map((part) => ({ ...part, type: "tool-result" })) } : message,
  );
  const clineLeft = clineKept(clineMessages, clineItems(clineMessages).filter((item) => item.kind !== "toolCall"));
  assert.equal(clineLeft.some((message) => message.role === "tool"), false);
});

test("a tool result whose call the projection kept stays in the copy", () => {
  // The other half of the rule: a result is not dropped for being a result.
  const messages = hostTurn(["call", "result"]);
  assert.deepEqual(ompKept(messages, ompItems(messages)), messages);
  const clineMessages = messages.map((message) =>
    message.role === "tool" ? { ...message, content: message.content.map((part) => ({ ...part, type: "tool-result" })) } : message,
  );
  assert.deepEqual(clineKept(clineMessages, clineItems(clineMessages)), clineMessages);
});

// ------------------------------------------- tool states, retries, restart

test("any turn the host grows extends the request before it", async () => {
  // The property this file exists for, on the OMP path: whatever the host
  // stores between two requests — a call in flight, its result, prose, a second
  // call — the next request begins with the previous one.
  await fc.assert(
    fc.asyncProperty(turn, turn, async (first, grown) => {
      const root = tempRoot();
      const hint = await openHint(root, "ses_grow");
      const project = ompProject(hint);
      const base = [operator("go")];
      const sent = await project(base);
      const next = await project([...base, ...hostTurn(first).slice(1), ...hostTurn(grown).slice(1)]);
      assertChain(hint, sent, next, "a grown turn");
    }),
    { ...fcRuns(30) },
  );
});

test("a call in flight, its result, and a failed call are one chain", async () => {
  const root = tempRoot();
  const hint = await openHint(root, "ses_states");
  const project = ompProject(hint);
  const call = { type: "tool-call", toolName: "js-worker", toolCallId: "c1", input: { code: "1" } };
  const answered = { type: "tool-result", toolName: "js-worker", toolCallId: "c1", output: { type: "text", value: "done" } };
  const issued = { type: "tool-call", toolName: "js-worker", toolCallId: "c2", input: {} };
  // 1. The call is issued; the request is built before its result exists.
  const first = await project([operator("go"), { role: "assistant", content: [{ type: "text", text: "working" }, call] }]);
  // 2. The result arrives.
  const second = await project([
    operator("go"),
    { role: "assistant", content: [{ type: "text", text: "working" }, call] },
    { role: "tool", content: [answered] },
  ]);
  assertChain(hint, first, second, "the result arrived");
  // 3. A second call is issued, with no result yet.
  const third = await project([
    operator("go"),
    { role: "assistant", content: [{ type: "text", text: "working" }, call] },
    { role: "tool", content: [answered] },
    { role: "assistant", content: [issued] },
  ]);
  assertChain(hint, second, third, "a call was issued");
  // 4. That call failed: the host records a result with no output at all.
  const fourth = await project([
    operator("go"),
    { role: "assistant", content: [{ type: "text", text: "working" }, call] },
    { role: "tool", content: [answered] },
    { role: "assistant", content: [issued] },
    { role: "tool", content: [{ type: "tool-result", toolName: "js-worker", toolCallId: "c2" }] },
  ]);
  assertChain(hint, third, fourth, "the failed call was answered");
});

test("a restarted session continues the chain it presented", async () => {
  // A restart rebuilds the hint from its journal: same epoch, same frozen text,
  // same placement. The first request after the restart must extend the last
  // request before it, not re-place the pair somewhere else.
  const root = tempRoot();
  const sessionId = "ses_restart";
  const history = [operator("go"), { role: "assistant", content: [{ type: "text", text: "a" }] }];
  const before = await openHint(root, sessionId);
  const sent = await ompProject(before)(history);
  assert.ok(fs.existsSync(new JournalStore({ root, sessionId }).file), "the journal is on disk");
  // A new process: the same journal, a new hint object, nothing in memory.
  const after = await openHint(root, sessionId);
  assert.equal(after.epoch, before.epoch, "a restart keeps the epoch");
  assert.equal(after.text, before.text, "a restart keeps the frozen presentation");
  const next = await ompProject(after)([...history, operator("again")]);
  assertChain(after, sent, next, "after a restart");
});

test("a restarted session does not anchor the chain away", async () => {
  // The restart must not be mistaken for a barrier: anchoring would open a new
  // chain at the restart's own copy, and the drift it hides would be the one a
  // rewritten history produces.
  const root = tempRoot();
  const sessionId = "ses_restart_drift";
  const before = await openHint(root, sessionId);
  await ompProject(before)([operator("go")]);
  const after = await openHint(root, sessionId);
  await assert.rejects(
    ompProject(after)([operator("go, changed")]),
    (error) => error.code === "PREFIX_DRIFT",
    "a rewritten message is still drift after a restart",
  );
});

test("the same message in two shapes is one message to the chain", () => {
  // The chain compares CANONICAL content (core's `canonical.mjs`): role plus
  // ordered semantic parts, with tool ids/names normalised across host dialects
  // and every other key ignored by construction. So a message the host re-spells
  // is the same message, and a message whose words changed is not. The adapter
  // itself only maps part names, because core's injector locates its own pair by
  // reading `tool-call` / `tool-result` literally.
  const key = (message) => canonicalKey(ompHintMessages([message])[0]);
  const part = { role: "user", content: [{ type: "text", text: "ship it" }] };
  assert.equal(key({ role: "user", content: "ship it" }), key(part), "a bare string and one text part are one message");
  assert.equal(
    key({ ...part, timestamp: 1, usage: { total: 3 }, responseId: "r1", duration: 12 }),
    key(part),
    "host bookkeeping is not conversation",
  );
  const streamed = {
    role: "assistant",
    content: [{ type: "tool-call", toolName: "js-worker", toolCallId: "c1", input: {}, partialArgs: '{"a"', streamIndex: 2 }],
  };
  const finalised = { role: "assistant", content: [{ type: "tool-call", toolName: "js-worker", toolCallId: "c1", input: {}, arguments: { a: 1 } }] };
  assert.equal(key(streamed), key(finalised), "a stream that settled is the same message");
  assert.notEqual(key({ role: "user", content: [{ type: "text", text: "ship it now" }] }), key(part), "a changed word is still a change");
  // A changed tool identity is a change too: the chain must not treat two calls
  // as one because their arguments happen to match.
  assert.notEqual(
    key({ role: "assistant", content: [{ type: "tool-call", toolName: "js-worker", toolCallId: "c1", input: {} }] }),
    key({ role: "assistant", content: [{ type: "tool-call", toolName: "js-worker", toolCallId: "c2", input: {} }] }),
  );
});

test("the OMP context handler sends one chain, whatever Scope answers", async () => {
  // The handler applies the projection's answer through the identity filter. A
  // Scope that keeps everything yields the host's own copy; the next request
  // must extend it, and the gate must stay open.
  const root = tempRoot();
  const hint = await openHint(root, "ses_handler");
  const history = hostTurn(["call", "result", "prose"]);
  const handler = createContextHandler({
    scopeProject: async ({ items }) => ({ ok: true, messages: items }),
    pairHintProject: ompProject(hint),
  });
  const first = await handler({ messages: history });
  assert.ok(Array.isArray(first?.messages), "the handler answered a copy");
  const second = await handler({ messages: [...history, operator("again")] });
  assert.ok(Array.isArray(second?.messages));
  assertChain(hint, first.messages, second.messages, "the handler's two copies");
  assert.equal(handler.requestRefusal(), null, "a clean request leaves the gate open");
});

test("the Cline projection sends one chain as the host grows its history", () => {
  fc.assert(
    fc.property(turn, fc.array(turnPart, { maxLength: 4 }), (first, grown) => {
      const history = hostTurn(first);
      const before = clineProject(history);
      const after = clineProject([...history, ...hostTurn(grown).slice(1)]);
      assert.deepEqual(after.slice(0, before.length), before, "the later copy begins with the earlier one");
    }),
    { ...fcRuns(120) },
  );
});

// ------------------------------------------------------- relay (§2.4 第 3 步)

test("a successor's first request opens a chain the second one extends", async () => {
  // The barrier is the one legitimate break. After it the same rule applies:
  // the successor's second request begins with its first.
  const root = tempRoot();
  const hint = await openHint(root, "ses_successor");
  const notice = { role: "user", content: [{ type: "text", text: "You continue work a previous tenure started." }] };
  hint.anchor(ompHintMessages([notice, operator("ship the parser fix")]));
  const project = ompProject(hint);
  const first = await project([notice, operator("ship the parser fix")]);
  const second = await project([notice, operator("ship the parser fix"), { role: "assistant", content: [{ type: "text", text: "on it" }] }]);
  assertChain(hint, first, second, "the successor's second request");
});

test("the Cline barrier never begins the successor's copy with a tool answer", () => {
  // Cline appends a tool's own output as a user turn, so the last operator
  // message of a retired conversation is often the answer to a call. A cut
  // there sends the successor a copy that begins with an answer to a call it
  // does not carry, which the provider rejects.
  const retired = [
    operator("do the thing"),
    { role: "assistant", content: [{ type: "tool-call", toolName: "js-manager", toolCallId: "c1", input: {} }] },
    operator("tool output: done"),
  ];
  assert.equal(barrierCutIndex(retired), 0, "the cut lands on the operator's own turn");
  // A conversation whose only operator messages answer calls has no turn of the
  // successor's own: the copy is the barrier's own message alone.
  assert.equal(barrierCutIndex(retired.slice(1)), -1);
  // Over any history: the cut never follows an assistant turn.
  fc.assert(
    fc.property(turn, (parts) => {
      const history = hostTurn(parts);
      const index = barrierCutIndex(history);
      if (index < 0) return;
      assert.notEqual(history[index - 1]?.role, "assistant", "the cut does not follow an assistant turn");
    }),
    { ...fcRuns(120) },
  );
});

test("a successor reads the succession notice before its own words", () => {
  assert.deepEqual(successionNotice({ notice: "You continue work a previous tenure started." }), [
    { role: "user", content: [{ type: "text", text: "You continue work a previous tenure started." }] },
  ]);
  assert.deepEqual(successionNotice({ notice: "" }), [], "no notice, no invented one");
  assert.deepEqual(successionNotice(null), []);
});

// ---------------------------------------------------------------------------
// The request record: who shortened the copy, and was it a compaction
// ---------------------------------------------------------------------------

// A drift record that says "the copy got shorter" cannot tell the spec deciding
// (a Scope prune) from the adapter deciding (a half-pair drop). That ambiguity is
// what let a live session wedge while every unit test passed: the drop was the
// adapter's, the boundary treated it as a projection, and the chain re-based on a
// copy the host's next request did not extend. The record now names the dropped
// messages and whether the copy was built from a host rewrite.
test("a request record names the messages the adapter dropped and the compaction that produced them", async () => {
  const root = tempRoot();
  const hint = await openHint(root, "ses_record");
  const project = ompProject(hint);
  const call = { type: "tool-call", toolName: "js-worker", toolCallId: "c1", input: { code: "1" } };
  const answered = { type: "tool-result", toolName: "js-worker", toolCallId: "c1", output: { type: "text", value: "done" } };
  const history = [operator("go"), { role: "assistant", content: [call] }, { role: "tool", content: [answered] }];
  const sent = await project(history);

  // The same request, settled: nothing left, nothing dropped.
  const clean = requestRecord({ phase: "settled", sessionId: "ses_record", host: "18.14.2", incoming: history, presented: sent });
  assert.deepEqual(clean.dropped, [], "an unchanged copy drops nothing");
  assert.equal(clean.pair.summary, null, "the copy was not built from a host rewrite");
  assert.equal(typeof clean.pair.index, "number", "the pair's own placement is still recorded");

  // The adapter's half-pair rule: a compaction folded the assistant message
  // that issued `c1` into a summary, so the copy still holds the result and no
  // longer holds the call. `selectKept` removes that result, and the record must
  // name the message that left — with the call id, which is the only fact that
  // says WHY it left.
  const summaryFirst = [{ role: "user", content: [{ type: "text", text: "summary" }] }, { role: "tool", content: [answered] }];
  const kept = ompKept(summaryFirst, ompItems(summaryFirst));
  assert.equal(kept.length, 1, "the orphan result left the copy");
  const halfPair = requestRecord({
    phase: "settled",
    sessionId: "ses_record",
    incoming: summaryFirst,
    presented: kept,
  });
  assert.equal(halfPair.dropped.length, 1, "exactly the message that left");
  assert.deepEqual(halfPair.dropped[0].results, ["c1"], "the dropped message is named by the call it answered");
  assert.equal(halfPair.dropped[0].role, "tool");
  assert.equal(halfPair.dropped[0].at, 0, "and by its position among the dropped messages");

  // A copy built from a compaction is the ONE case where a shorter request is
  // legitimate, and the record has to say so without reading the conversation.
  const summary = { role: "compactionSummary", summary: "earlier work", timestamp: 1 };
  const compacted = requestRecord({
    phase: "settled",
    sessionId: "ses_record",
    incoming: [summary, ...history],
    presented: [summary, ...sent],
  });
  assert.equal(compacted.pair.summary, 0, "the host's rewrite marker is named by index");
  assert.deepEqual(compacted.dropped, [], "a compaction that keeps the tail drops nothing here");
  assert.equal(compacted.allowed, true);
  await fs.rmSync(root, { recursive: true, force: true });
});
