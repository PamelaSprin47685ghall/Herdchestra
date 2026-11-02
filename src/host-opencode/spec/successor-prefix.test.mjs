// A successor's first request opens its own prefix chain (根 §2.4 第 3 步, §6.1).
//
// Live evidence (`/tmp/hc-devops/LIVE4-SMOKE.md` §4): the successor's first
// request reached the provider twice, then the session failed 300+ times with
// `ContextRefusal: PREFIX_DRIFT: the Pair Hint could not be placed`, while every
// hint-journal row carried `after:2, inserted:2`.
//
// The cause is not a retry loop in the hint. The relay barrier replaces the
// successor's copy with the operator's input plus the succession notice, so the
// copy no longer begins with the messages the hint presented for the
// predecessor's session. §6.1 makes one epoch one prefix chain; 根 §2.4 第 3 步 makes
// the barrier a legitimate cut of the request copy. Both hold only if the hint
// re-anchors on the successor's first request, which is what these cases pin.
//
// These drive the real hook (`applyContextHook`) with a real `PairHint` journal
// and a real relay record on disk, and compare the request the hook produces
// against what the provider would receive on the next turn.
//
// Run with: `node --test "src/host-opencode/**/*.test.mjs"`.

import test from "node:test";
import assert from "node:assert/strict";
import path from "node:path";

import { applyContextHook, opensPrefixChain } from "./context-hook.mjs";
import { openPairHint } from "./pair-hint-request.mjs";
import { toHintMessages } from "./message-adapter.mjs";
import { messageDigest } from "../../core/pair-hint/chain.mjs";
import { SessionState } from "./session-state.mjs";
import { RELAY_GATE, RELAY_PHASES, relayFile, relayRecord, writeRelay } from "./relay-record.mjs";
import { tempRoot as tempRootDir } from "./test-temp.mjs";

// One workspace per test; the registry removes it when the process exits, so no
// file can forget one.
const tempRoot = (prefix) => tempRootDir(prefix);

const text = (value) => [{ type: "text", text: value }];

// The retired conversation as the host still stores it, and the successor's
// own first prompt on top of it. A successor session inherits the predecessor's
// transcript in the host's view; the barrier is what cuts it.
const retiredConversation = [
  { role: "user", content: text("ship the parser fix") },
  { role: "assistant", content: text("working on it") },
  { role: "user", content: text("keep going") },
  { role: "assistant", content: text("halfway") },
];

const successorRequest = (tail = []) => [...retiredConversation, ...tail];

const request = (sessionID, messages) => ({
  sessionID,
  agent: "Manager",
  tools: { "js-manager": {} },
  system: [],
  messages,
});

// A record that really retires `incumbent` and opens `successor`, written where
// both gates read it (the incumbent's file).
const seedRelay = (root, { incumbent, successor }) => {
  const record = relayRecord({
    sessionID: incumbent,
    incumbent,
    successor,
    tenure: 1,
    milestone: "execute",
    phase: RELAY_PHASES.SENT,
    notice: "You continue work a previous tenure started.",
    operatorInput: "ship the parser fix",
    fenceThrough: retiredConversation.length,
    requestGate: RELAY_GATE.HOST,
    toolGate: RELAY_GATE.HOST,
  });
  const written = writeRelay(relayFile(root, incumbent), record);
  assert.equal(written.ok, true);
  // The successor reads its own copy, not a directory scan (root §1.1 item 1).
  // The real hand-over writes both; a test that wrote only the incumbent's file
  // would describe a successor that never learned it inherited a relay.
  const pointer = writeRelay(relayFile(root, successor), record);
  assert.equal(pointer.ok, true);
  return record;
};

const openSession = async (root, sessionID) => {
  const state = new SessionState({ sessionID, root });
  const hint = await openPairHint({ root, sessionId: sessionID });
  await hint.bind({ sessionId: hint.sessionId, ladder: { explicit: "English" } });
  return { state, hint };
};

test("a successor's first request starts a new chain instead of drifting", async () => {
  const root = tempRoot();
  const incumbent = "ses_prefix_incumbent";
  const successor = "ses_prefix_successor";
  seedRelay(root, { incumbent, successor });

  const { state, hint } = await openSession(root, successor);

  // The first request of the successor's tenure. Before the fix this threw
  // PREFIX_DRIFT, because the barrier's cut does not begin with the bytes the
  // hint presented for the predecessor's conversation.
  const first = request(successor, successorRequest());
  const applied = await applyContextHook(first, { state, hint });
  assert.equal(applied.hint, "placed", "the first request places the pair");

  // What the provider received, in the hint's own vocabulary. The chain keeps
  // digests, not the copy (root §1.1 item 1): the barrier's single opening
  // message is the pair-free chain, and its digest names the notice plus the
  // operator's request.
  const sent = hint.chain;
  assert.ok(sent !== null, "the chain has an anchor");
  const opening =
    "You continue work a previous tenure started.\n\n# 操作员的原始请求\n\nship the parser fix";
  const expected = messageDigest(toHintMessages([{ role: "user", content: text(opening) }])[0]);
  assert.deepEqual(sent.pairFreeDigests(), [expected]);
});

test("the next request of the same tenure extends the successor's chain", async () => {
  const root = tempRoot();
  const incumbent = "ses_prefix_incumbent";
  const successor = "ses_prefix_successor";
  seedRelay(root, { incumbent, successor });
  const { state, hint } = await openSession(root, successor);

  const first = request(successor, successorRequest());
  await applyContextHook(first, { state, hint });
  const afterFirst = hint.chain;

  // The host rebuilds its own history and adds the turn's messages; the barrier
  // cuts the same prefix again, so the second request must start with the first.
  const second = request(successor, successorRequest([{ role: "assistant", content: text("answer") }]));
  const applied = await applyContextHook(second, { state, hint });
  assert.equal(applied.hint, "placed");
  const afterSecond = hint.chain;
  assert.ok(afterSecond.length >= afterFirst.length);
  assert.deepEqual(
    afterSecond.pairFreeDigests().slice(0, afterFirst.pairFreeDigests().length),
    afterFirst.pairFreeDigests(),
    "the second request begins with every message of the first",
  );

  // A third request still extends the same chain: the anchor happened once, at
  // the barrier, and every later request of the tenure is held to it.
  const third = request(
    successor,
    successorRequest([
      { role: "assistant", content: text("answer") },
      { role: "user", content: text("and again") },
    ]),
  );
  await applyContextHook(third, { state, hint });
  assert.deepEqual(
    hint.chain.pairFreeDigests().slice(0, afterSecond.pairFreeDigests().length),
    afterSecond.pairFreeDigests(),
  );
});

test("the anchor opens once per relay record", async () => {
  const root = tempRoot();
  const incumbent = "ses_prefix_incumbent";
  const successor = "ses_prefix_successor";
  const record = seedRelay(root, { incumbent, successor });
  const { state } = await openSession(root, successor);

  // The first request of the tenure opens the chain; the barrier cuts every
  // request, so a marker is what keeps the second one on the normal path.
  assert.equal(state.anchorPrefix(record), true);
  assert.equal(state.anchorPrefix(record), false, "the same record anchors once");
});

// The anchor's job is to OPEN the chain, not to be checked against the copy it
// replaced. This is the barrier's shape without the barrier: a session that
// presented its conversation, then sends a copy that is not an extension of it.
// The request after the anchor must be sent; the one after THAT must extend it,
// and a rewrite from there on is still drift.
test("an anchored copy opens the chain the next request continues", async () => {
  const root = tempRoot();
  const sessionID = "ses_prefix_anchored";
  const { state, hint } = await openSession(root, sessionID);

  await applyContextHook(request(sessionID, retiredConversation), { state, hint });
  // The barrier's copy: the operator's input and the notice, nothing of the
  // conversation before it.
  const cut = [{ role: "user", content: text("You continue work a previous tenure started.") }];
  hint.anchor(toHintMessages(cut));

  const first = await applyContextHook(request(sessionID, structuredClone(cut)), { state, hint });
  assert.equal(first.hint, "placed", "the request after the anchor is sent");

  // The host grows its own copy with the answer, and the chain continues.
  const grown = [...structuredClone(cut), { role: "assistant", content: text("answer") }];
  const next = await applyContextHook(request(sessionID, grown), { state, hint });
  assert.equal(next.hint, "placed");
  // The anchored bytes are what the model read: the next request starts with
  // them, pair or no pair.
  const withoutPair = (messages) =>
    messages.filter(
      (message) =>
        !message.content?.some?.((part) => String(part?.toolCallId ?? part?.id ?? "").startsWith("pair-hint:")),
    );
  assert.deepEqual(
    withoutPair(next.event.messages).slice(0, withoutPair(first.event.messages).length),
    withoutPair(first.event.messages),
  );

  // The strict rule is back from the second request on: a rewritten message is
  // still drift.
  await assert.rejects(
    applyContextHook(
      request(sessionID, [
        { role: "user", content: text("You continue work a previous tenure started.") },
        { role: "assistant", content: text("rewritten") },
      ]),
      { state, hint },
    ),
    (error) => error.code === "PREFIX_DRIFT",
  );
});

test("a retired tenure still has no request at all", async () => {
  const root = tempRoot();
  const incumbent = "ses_prefix_incumbent";
  const successor = "ses_prefix_successor";
  seedRelay(root, { incumbent, successor });
  const { state, hint } = await openSession(root, incumbent);
  await assert.rejects(
    applyContextHook(request(incumbent, retiredConversation), { state, hint }),
    (error) => error.code === "RELAY_RETIRED",
  );
});

test("a session no record names keeps the strict prefix rule", async () => {
  const root = tempRoot();
  const sessionID = "ses_prefix_plain";
  const { state, hint } = await openSession(root, sessionID);

  const first = request(sessionID, [{ role: "user", content: text("hello") }]);
  await applyContextHook(first, { state, hint });
  // The host presents different bytes for a message already shown: this is the
  // drift §6.1 forbids, and re-anchoring must not excuse it.
  const changed = request(sessionID, [{ role: "user", content: text("hello, changed") }]);
  await assert.rejects(
    applyContextHook(changed, { state, hint }),
    (error) => error.code === "PREFIX_DRIFT",
  );
});

test("opensPrefixChain names only the successor of a usable record", () => {
  const record = { incumbent: "a", successor: "b" };
  assert.equal(opensPrefixChain(record, "b"), true);
  assert.equal(opensPrefixChain(record, "a"), false);
  assert.equal(opensPrefixChain(record, "c"), false);
  assert.equal(opensPrefixChain(null, "b"), false);
  assert.equal(opensPrefixChain({ corrupt: true, successor: "b" }, "b"), false);
});

// The consumer-visible regression for `ses_e6b5fa739ffef04j5obpcnk3Ze`
// (2026-11-01): a tool call is issued, and the NEXT request is built before its
// result exists. The provider must receive the same assistant message both
// times — the call may not appear or disappear between requests, or the model
// reads two versions of one message and the hint refuses (PREFIX_DRIFT at the
// index of that message).
test("a tool call in flight does not change the message the model already read", async () => {
  const root = tempRoot();
  const sessionID = "ses_prefix_inflight";
  const { state, hint } = await openSession(root, sessionID);
  const call = { type: "tool-call", id: "c9", name: "js-manager", toolCallId: "c9", input: { code: "1" } };

  const first = request(sessionID, [{ role: "user", content: text("go") }]);
  await applyContextHook(first, { state, hint });

  // The turn answers with prose and a call whose result has not arrived yet.
  const second = request(sessionID, [
    { role: "user", content: text("go") },
    { role: "assistant", content: [...text("working"), call] },
  ]);
  await applyContextHook(second, { state, hint });
  const sent = second.messages;

  // The result arrives: the same assistant message must come back unchanged,
  // with the call still in it.
  const third = request(sessionID, [
    { role: "user", content: text("go") },
    { role: "assistant", content: [...text("working"), call] },
    { role: "tool", content: [{ type: "tool-result", id: "c9", name: "js-manager", toolCallId: "c9", result: { type: "text", value: "done" } }] },
  ]);
  await applyContextHook(third, { state, hint });

  const assistantBefore = sent.find((message) => message.role === "assistant" && message.content.some((part) => part?.type === "tool-call"));
  const assistantAfter = third.messages.find((message) => message.role === "assistant" && message.content.some((part) => part?.type === "tool-call"));
  assert.ok(assistantBefore, "the first copy carries the call");
  assert.ok(assistantAfter, "the next copy carries it too");
  assert.deepEqual(
    assistantAfter.content.filter((part) => part?.type === "tool-call"),
    assistantBefore.content.filter((part) => part?.type === "tool-call"),
    "the call the model read is the call it reads again",
  );
});
