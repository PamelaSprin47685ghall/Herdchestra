// The prefix rule, proved as a property of the LIFECYCLE (spec §6.1 step 4).
//
// The rule the three hosts depend on has two halves, and both are asserted here
// as behaviours over arbitrary sequences, not as a restatement of the code:
//
//   1. NO FALSE POSITIVE. Every legitimate transition — the host finalising a
//      streamed message, stamping its own metadata, reordering object keys,
//      Scope keeping / erasing / resetting its beats, a retry, a cold restart, a
//      relay barrier — projects without PREFIX_DRIFT.
//   2. NO FALSE NEGATIVE. Any change to what the model READ in a message the
//      chain already presented — a word, a role, a tool's arguments, a result —
//      refuses with PREFIX_DRIFT.
//
// The two halves are complementary by construction: (1) proves the comparison
// ignores everything that is not conversation, (2) proves it ignores nothing
// that is. Together they pin the canonical rule (canonical.mjs) from both sides,
// which a blacklist of host fields never could: a new host field cannot create a
// false positive, because the allowlist never looks at it.
//
// Run with: `node --test "src/core/pair-hint/*.test.mjs"`.

import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import fc from "fast-check";

import { PairHint, MemoryJournal } from "./pair-hint.mjs";
import { JournalStore } from "./facts.mjs";
import { Chain } from "./chain.mjs";
import { findAllInjections, findInjection } from "./presentation.mjs";
import { canonicalKey, canonicalMessage } from "./canonical.mjs";
import { PAIR_HINT_CODES, isPairHintError } from "./errors.mjs";
import { REBASE_REASONS, REQUEST_KINDS } from "./pair-hint.mjs";
import { projectRequest } from "../scope/projection.mjs";
import { ITEM } from "../scope/projection.mjs";
import { fcRuns } from "../test-runs.mjs";

const tempRoot = async () => await fs.mkdtemp(path.join(os.tmpdir(), "pair-hint-canonical-"));

const isDrift = async (promise) => {
  try {
    await promise;
    return false;
  } catch (error) {
    assert.ok(isPairHintError(error), `expected PairHintError, got ${error}`);
    return error.code === PAIR_HINT_CODES.PREFIX_DRIFT;
  }
};

// ---------------------------------------------------------------------------
// 1. Canonicalisation is an equivalence with exactly the right classes
// ---------------------------------------------------------------------------

// Host bookkeeping a message may carry. Every one of these is added, changed or
// removed between two requests of a real session, and none of it is conversation.
const HOST_METADATA = Object.freeze([
  "timestamp",
  "usage",
  "duration",
  "ttft",
  "responseId",
  "completedAt",
  "contextSnapshot",
  "id",
  "providerExecuted",
  "metadata",
  "herdchestraSuccession",
  "someFutureField",
]);

const metaArb = fc.dictionary(
  fc.constantFrom(...HOST_METADATA),
  fc.oneof(fc.integer(), fc.string(), fc.boolean(), fc.constant(null), fc.array(fc.integer(), { maxLength: 2 })),
  { maxKeys: 4 },
);

// A semantic message, in one host's shape.
const semanticArb = fc.oneof(
  fc.record({
    kind: fc.constant("user"),
    text: fc.string({ minLength: 1, maxLength: 20 }),
  }),
  fc.record({
    kind: fc.constant("assistant"),
    text: fc.string({ minLength: 1, maxLength: 20 }),
  }),
  fc.record({
    kind: fc.constant("call"),
    callId: fc.string({ minLength: 1, maxLength: 8 }),
    toolName: fc.constantFrom("cat", "write", "js-manager", ""),
    input: fc.dictionary(fc.string({ minLength: 1, maxLength: 6 }), fc.oneof(fc.string(), fc.integer()), { maxKeys: 3 }),
  }),
  fc.record({
    kind: fc.constant("result"),
    callId: fc.string({ minLength: 1, maxLength: 8 }),
    text: fc.string({ maxLength: 20 }),
  }),
  fc.record({ kind: fc.constant("reasoning"), text: fc.string({ minLength: 1, maxLength: 20 }) }),
);

// The same semantic message rendered the way one host writes it. `variant` picks
// the host dialect: key names, envelopes, key insertion order, metadata.
const render = (semantic, variant, meta) => {
  const shuffled = (object) => {
    const keys = Object.keys(object).sort();
    if (variant % 2 === 1) keys.reverse();
    const out = {};
    for (const key of keys) out[key] = object[key];
    return out;
  };
  const withMeta = (message) => (variant % 3 === 0 ? { ...message, ...meta } : message);
  switch (semantic.kind) {
    case "user":
      return withMeta(
        variant % 2 === 0
          ? shuffled({ role: "user", content: semantic.text })
          : shuffled({ role: "operator", content: [{ type: "text", text: semantic.text }] }),
      );
    case "assistant":
      return withMeta(shuffled({ role: "assistant", content: [{ type: "text", text: semantic.text }] }));
    case "call": {
      const part =
        variant % 2 === 0
          ? { type: "tool-call", toolCallId: semantic.callId, toolName: semantic.toolName, input: semantic.input }
          : { type: "toolCall", id: semantic.callId, name: semantic.toolName, args: shuffled(semantic.input) };
      return withMeta(shuffled({ role: "assistant", content: [part] }));
    }
    case "result": {
      const part =
        variant % 3 === 0
          ? { type: "tool-result", toolCallId: semantic.callId, toolName: "cat", output: { type: "text", value: semantic.text } }
          : variant % 3 === 1
            ? { type: "toolResult", id: semantic.callId, name: "cat", result: { type: "json", value: semantic.text } }
            : { type: "tool-result", callId: semantic.callId, output: semantic.text };
      return withMeta(shuffled({ role: "tool", content: [part] }));
    }
    default:
      return withMeta(
        variant % 2 === 0
          ? shuffled({ role: "assistant", content: [{ type: "reasoning", text: semantic.text }] })
          : shuffled({ role: "assistant", content: [{ type: "thinking", text: semantic.text }] }),
      );
  }
};

test("canonical: two host renderings of one message have one identity, whatever the metadata or key order", () => {
  fc.assert(
    fc.property(semanticArb, metaArb, metaArb, fc.integer({ min: 0, max: 5 }), fc.integer({ min: 0, max: 5 }), (semantic, a, b, va, vb) => {
      const left = render(semantic, va, a);
      const right = render(semantic, vb, b);
      assert.equal(canonicalKey(left), canonicalKey(right), `${JSON.stringify(left)} != ${JSON.stringify(right)}`);
    }),
    { ...fcRuns(300) },
  );
});

test("canonical: a change to any semantic field changes the identity", () => {
  const fields = [
    ["user", { text: "hello" }, { text: "hello!" }],
    ["user", { text: "hello" }, { text: "hello " }],
    ["call", { callId: "c1", toolName: "cat", input: { a: 1 } }, { callId: "c2", toolName: "cat", input: { a: 1 } }],
    ["call", { callId: "c1", toolName: "cat", input: { a: 1 } }, { callId: "c1", toolName: "write", input: { a: 1 } }],
    ["call", { callId: "c1", toolName: "cat", input: { a: 1 } }, { callId: "c1", toolName: "cat", input: { a: 2 } }],
    ["result", { callId: "c1", text: "ok" }, { callId: "c1", text: "ok." }],
    ["reasoning", { text: "hmm" }, { text: "hmmm" }],
  ];
  for (const [kind, before, after] of fields) {
    assert.notEqual(
      canonicalKey(render({ kind, ...before }, 0, {})),
      canonicalKey(render({ kind, ...after }, 0, {})),
      `${kind}: ${JSON.stringify(before)} vs ${JSON.stringify(after)}`,
    );
  }
  // A different role is a different message even with identical content.
  assert.notEqual(
    canonicalKey({ role: "user", content: "same" }),
    canonicalKey({ role: "assistant", content: "same" }),
  );
});

test("canonical: a host's bookkeeping is ignored by construction, not by a list", () => {
  const base = { role: "assistant", content: [{ type: "tool-call", toolCallId: "c1", toolName: "cat", input: { a: 1 } }] };
  const key = canonicalKey(base);
  fc.assert(
    fc.property(metaArb, fc.string({ minLength: 1, maxLength: 8 }), (meta, name) => {
      const noisy = { ...base, ...meta, [name]: { anything: true } };
      noisy.content = base.content.map((part) => ({ ...part, ...meta, [name]: "x", partialArgs: "{}", streamIndex: 3 }));
      assert.equal(canonicalKey(noisy), key, `a field named ${name} changed the identity`);
    }),
    { ...fcRuns(200) },
  );
});

// ---------------------------------------------------------------------------
// 2. The lifecycle: legitimate transitions never drift
// ---------------------------------------------------------------------------

// A host request copy built from semantic messages, one per line.
const copyOf = (semantics, variant, meta) => semantics.map((semantic) => render(semantic, variant, meta));

// The PROVIDER shape `@cline/core` 0.2.0 converts the runtime's `AgentMessage[]`
// into before the request leaves the process (`agent-message-codec.ts:237-306`,
// `apihandler-agent-model-adapter.ts:147`): a call is a `tool_use` block of an
// assistant message, and a result is a `tool_result` block of a `role:"user"`
// message. `beforeModel` sees the runtime shape, so the chain must read both as
// one conversation — a host upgrade that starts handing the wire shape to the
// hook must not read as a rewrite of every message already presented.
const renderProvider = (semantic, meta) => {
  switch (semantic.kind) {
    case "user":
      return { role: "user", content: [{ type: "text", text: semantic.text }], ...meta };
    case "assistant":
      return { role: "assistant", content: [{ type: "text", text: semantic.text }], ...meta };
    case "call":
      return {
        role: "assistant",
        content: [{ type: "tool_use", id: semantic.callId, name: semantic.toolName, input: semantic.input }],
        ...meta,
      };
    case "result":
      // The provider shape writes the result's text on `content` and the codec
      // also passes the runtime's `output` through; both name one result.
      return {
        role: "user",
        content: [{ type: "tool_result", tool_use_id: semantic.callId, name: "cat", content: semantic.text, is_error: false, output: semantic.text }],
        ...meta,
      };
    default:
      // `text` is the field `canonicalPart` reads for both spellings of a
      // reasoning part; a provider block names it `thinking`, which is not the
      // field this chain compares.
      return { role: "assistant", content: [{ type: "thinking", text: semantic.text }], ...meta };
  }
};

test("canonical: the runtime shape and the provider wire shape of one message are one identity", () => {
  fc.assert(
    fc.property(semanticArb, metaArb, metaArb, fc.integer({ min: 0, max: 5 }), (semantic, runtimeMeta, providerMeta, variant) => {
      const runtime = render(semantic, variant, runtimeMeta);
      const provider = renderProvider(semantic, providerMeta);
      assert.equal(
        canonicalKey(runtime),
        canonicalKey(provider),
        `${JSON.stringify(runtime)} != ${JSON.stringify(provider)}`,
      );
    }),
    { ...fcRuns(300) },
  );
});

test("canonical: a result block inside a user message keeps its call id and is the result role", () => {
  fc.assert(
    fc.property(
      fc.string({ minLength: 1, maxLength: 8 }),
      fc.string({ maxLength: 20 }),
      fc.boolean(),
      (callId, text, isError) => {
        // The provider shape names the result's call `tool_use_id`, and the codec
        // passes the runtime's `output` through beside it.
        const part = { type: "tool_result", tool_use_id: callId, name: "cat", content: text, is_error: isError, output: text };
        const canonical = canonicalMessage({ role: "user", content: [part] });
        assert.equal(canonical.role, "tool");
        assert.equal(canonical.parts.length, 1);
        assert.equal(canonical.parts[0].type, "tool-result");
        // The call id survives: a result that lost it answers no call, and the
        // pair rule would then read every such result as the same message.
        assert.equal(canonical.parts[0].callId, callId);
        assert.equal(canonical.parts[0].isError, isError);
        // The runtime's own result part is the same message.
        assert.equal(
          canonicalKey({ role: "tool", content: [{ type: "tool-result", toolCallId: callId, toolName: "cat", output: text, isError }] }),
          canonicalKey({ role: "user", content: [part] }),
        );
      },
    ),
    { ...fcRuns(200) },
  );
});

test("canonical: a user message that mixes a result with the operator's words keeps the user role", () => {
  const mixed = {
    role: "user",
    content: [
      { type: "tool_result", tool_use_id: "c1", name: "cat", content: "file" },
      { type: "text", text: "and now the operator speaks" },
    ],
  };
  const canonical = canonicalMessage(mixed);
  assert.equal(canonical.role, "user");
  // A result alone is not the operator's turn: the two roles stay distinct, so a
  // result that lost its call cannot pass for an operator beat.
  assert.notEqual(canonicalKey({ role: "user", content: [{ type: "tool_result", tool_use_id: "c1", content: "x" }] }), canonicalKey({ role: "user", content: "x" }));
});

// The Scope projection over a copy, in core's item vocabulary. It is the real
// projection, so an erase or a reset really shortens or rewrites the copy.
const scopeOf = (semantics, { resetCallId = null, erase = [] } = {}) => {
  const items = semantics.map((semantic, index) => {
    if (semantic.kind === "user") return { kind: ITEM.MESSAGE, role: "operator", text: semantic.text, at: index };
    if (semantic.kind === "assistant") return { kind: ITEM.MESSAGE, role: "assistant", text: semantic.text, at: index };
    if (semantic.kind === "reasoning") return { kind: ITEM.THINKING, at: index };
    if (semantic.kind === "call") {
      return {
        kind: ITEM.TOOL_CALL,
        member: semantic.toolName,
        callId: semantic.callId,
        at: index,
        ...(semantic.scope === undefined ? {} : { scope: semantic.scope }),
      };
    }
    return { kind: ITEM.TOOL_RESULT, member: "cat", callId: semantic.callId, at: index };
  });
  const projected = projectRequest(items, { resetCallId, erase, pinned: new Set(["cat", "write", "", "assume"]) });
  if (projected.ok === false) return null;
  // Map back to the host messages by the semantic index, and drop the pair-free
  // bookkeeping. A replacement beat (from `removeLast`) becomes an assistant
  // prose message, which is what the host would see in its copy.
  return projected.map((item) => {
    if (item.at === undefined) return { role: "assistant", content: [{ type: "text", text: item.text }] };
    return semantics[item.at];
  });
};

const lifecycleArb = fc.record({
  // The semantic conversation, in order. Call ids are unique by position.
  turns: fc.array(fc.oneof(fc.constant("user"), fc.constant("assistant"), fc.constant("call"), fc.constant("result"), fc.constant("reasoning")), {
    minLength: 1,
    maxLength: 8,
  }),
  // How many messages the host has grown the copy by, per request.
  grows: fc.array(fc.nat({ max: 2 }), { minLength: 1, maxLength: 4 }),
  // Which request finalises a streamed placeholder, which one stamps metadata,
  // which one reorders keys: each is a legitimate host transition.
  variant: fc.integer({ min: 0, max: 5 }),
  restarts: fc.array(fc.boolean(), { maxLength: 3 }),
  retries: fc.array(fc.boolean(), { maxLength: 3 }),
});

const buildSemantics = (turns) => {
  let callSeq = 0;
  return turns.map((kind, index) => {
    if (kind === "user") return { kind, text: `ask ${index}` };
    if (kind === "assistant") return { kind, text: `answer ${index}` };
    if (kind === "reasoning") return { kind, text: `thinking ${index}` };
    if (kind === "call") {
      callSeq += 1;
      return { kind, callId: `c${callSeq}`, toolName: "cat", input: { index } };
    }
    return { kind, callId: `c${Math.max(1, callSeq)}`, text: `out ${index}` };
  });
};

test("lifecycle: growth, finalisation, metadata, key order, retry and restart never drift", async () => {
  await fc.assert(
    fc.asyncProperty(lifecycleArb, async ({ turns, grows, variant, restarts, retries }) => {
      const root = await tempRoot();
      try {
        const sessionId = "lifecycle";
        let hint = await PairHint.open({ store: new JournalStore({ root, sessionId }) });
        await hint.bind({ sessionId, ladder: { explicit: "English" } });
        const semantics = buildSemantics(turns);
        let visible = 1;
        let previous = null;
        for (let step = 0; step < grows.length; step += 1) {
          if (restarts[step % restarts.length] === true) {
            hint = await PairHint.open({ root, sessionId });
          }
          visible = Math.min(semantics.length, visible + grows[step]);
          const copy = copyOf(semantics.slice(0, visible), (variant + step) % 6, { timestamp: step });
          const projected = await hint.project({ messages: copy });
          assert.equal(findAllInjections(projected.messages).length, 1, "exactly one pair, always");
          if (previous !== null) {
            assert.equal(
              await isDrift(Promise.resolve().then(() => hint.injector.assertPrefix({ previous, next: projected.messages }))),
              false,
              `step ${step} drifted on a legitimate copy`,
            );
          }
          previous = projected.messages;
          if (retries[step % retries.length] === true) {
            const retry = await hint.project({ messages: copy });
            assert.deepEqual(retry.messages, projected.messages, "a retry answers the same copy");
          }
        }
      } finally {
        await fs.rm(root, { recursive: true, force: true });
      }
    }),
    { ...fcRuns(220) },
  );
});

test("lifecycle: a Scope projection that erases or resets is accepted once the adapter re-anchors", async () => {
  await fc.assert(
    fc.asyncProperty(
      fc.array(fc.oneof(fc.constant("user"), fc.constant("assistant"), fc.constant("call"), fc.constant("result")), {
        minLength: 3,
        maxLength: 9,
      }),
      fc.nat({ max: 3 }),
      fc.boolean(),
      async (turns, eraseAt, reset) => {
        const hint = new PairHint({ store: new MemoryJournal() });
        await hint.bind({ sessionId: "scope", ladder: { explicit: "English" } });
        const semantics = buildSemantics(turns);
        const first = await hint.project({ messages: copyOf(semantics, 0, {}) });
        assert.equal(findAllInjections(first.messages).length, 1);

        // The Scope projection shortens or rewrites the copy: a reset keeps only
        // the newest Scope, an erase replaces the beat before the eraser.
        const eraser = semantics[Math.min(eraseAt + 1, semantics.length - 1)];
        const erase = eraser.kind === "call" ? [{ callId: eraser.callId, replacement: "lesson", spin: 1 }] : [];
        const resetCallId = reset && semantics.some((item) => item.kind === "call") ? semantics.find((item) => item.kind === "call").callId : null;
        const shortened = scopeOf(semantics, { resetCallId, erase });
        if (shortened === null) return;
        const rebuilt = copyOf(shortened.filter((item) => typeof item === "object" && item.kind !== undefined), 1, {});

        // The adapter re-anchors on the copy Scope produced, because Scope's
        // rewrite is a fact the host owns (it is the request that will be sent).
        hint.anchor(rebuilt);
        const projected = await hint.project({ messages: rebuilt });
        assert.equal(findAllInjections(projected.messages).length, 1, "the re-anchored copy carries one pair");
        // And the next request extends what was actually sent.
        const next = [...rebuilt, { role: "user", content: [{ type: "text", text: "next" }] }];
        const after = await hint.project({ messages: next });
        assert.deepEqual(after.messages.slice(0, projected.messages.length), projected.messages);
      },
    ),
    { ...fcRuns(120) },
  );
});

// ---------------------------------------------------------------------------
// 3. The lifecycle: genuine tampering always drifts
// ---------------------------------------------------------------------------

test("tampering: any semantic change to a presented message refuses PREFIX_DRIFT", async () => {
  await fc.assert(
    fc.asyncProperty(
      fc.array(fc.oneof(fc.constant("user"), fc.constant("assistant"), fc.constant("call"), fc.constant("result")), {
        minLength: 1,
        maxLength: 6,
      }),
      fc.nat({ max: 5 }),
      fc.integer({ min: 0, max: 3 }),
      fc.string({ minLength: 1, maxLength: 6 }),
      async (turns, at, tamper, word) => {
        const hint = new PairHint({ store: new MemoryJournal() });
        await hint.bind({ sessionId: "tamper", ladder: { explicit: "English" } });
        const semantics = buildSemantics(turns);
        await hint.project({ messages: copyOf(semantics, 0, {}) });
        const target = Math.min(at, semantics.length - 1);
        const changed = semantics.map((semantic, index) => {
          if (index !== target) return semantic;
          switch (tamper) {
            case 0:
              return { ...semantic, text: `${semantic.text ?? ""}${word}` };
            case 1:
              return semantic.kind === "call" ? { ...semantic, input: { ...semantic.input, tampered: word } } : { ...semantic, text: word };
            case 2:
              return semantic.kind === "user"
                ? { ...semantic, kind: "assistant", text: `${semantic.text}${word}` }
                : { ...semantic, text: `${semantic.text ?? ""}${word}` };
            default:
              return semantic.kind === "call" ? { ...semantic, toolName: `cat-${word}` } : { ...semantic, text: `${semantic.text ?? ""}${word}` };
          }
        });
        // Only a tampering that actually changes the semantics counts; a random
        // word appended to an empty field still changes it, so this holds.
        const before = canonicalKey(copyOf(semantics, 0, {})[target]);
        const after = canonicalKey(copyOf(changed, 0, {})[target]);
        if (before === after) return;
        assert.equal(
          await isDrift(hint.project({ messages: copyOf(changed, 0, {}) })),
          true,
          `tampering ${tamper} at ${target} was not refused`,
        );
      },
    ),
    { ...fcRuns(220) },
  );
});

test("tampering: an unapproved shortening refuses PREFIX_DRIFT", async () => {
  await fc.assert(
    fc.asyncProperty(fc.integer({ min: 2, max: 7 }), fc.nat({ max: 5 }), async (length, keep) => {
      const hint = new PairHint({ store: new MemoryJournal() });
      await hint.bind({ sessionId: "short", ladder: { explicit: "English" } });
      const semantics = buildSemantics(Array.from({ length }, (_, index) => (index % 2 === 0 ? "user" : "assistant")));
      await hint.project({ messages: copyOf(semantics, 0, {}) });
      // STRICTLY shorter: `keep` capped at `length - 1` items, never the same
      // copy (which is a legitimate replay, not a shortening).
      const shorter = copyOf(semantics.slice(0, Math.min(keep, length - 2) + 1), 0, {});
      assert.ok(shorter.length < semantics.length, "the generator must shorten");
      assert.equal(await isDrift(hint.project({ messages: shorter })), true, "a shorter copy without an anchor is drift");
    }),
    { ...fcRuns(100) },
  );
});

// ---------------------------------------------------------------------------
// 4. Scope and Pair Hint agree about what a rewrite is
// ---------------------------------------------------------------------------

test("scope: a rewrite it performs is a rewrite Pair Hint sees, and only a re-anchor accepts it", async () => {
  // The division of labour, stated once: Scope may legitimately shorten or
  // rewrite the copy (spec §6.2), and Pair Hint may not silently accept that —
  // the adapter re-anchors, which is a declared chain break. Without the
  // anchor the same copy is drift, which is what keeps an unapproved rewrite
  // from slipping through under the name of a Scope operation.
  const semantics = [
    { kind: "user", text: "ask" },
    { kind: "call", callId: "c1", toolName: "cat", input: {} },
    { kind: "result", callId: "c1", text: "out" },
    { kind: "call", callId: "c2", toolName: "write", input: {} },
    { kind: "result", callId: "c2", text: "lesson" },
  ];
  const copy = copyOf(semantics, 0, {});

  const anchored = new PairHint({ store: new MemoryJournal() });
  await anchored.bind({ sessionId: "a", ladder: { explicit: "English" } });
  await anchored.project({ messages: copy.slice(0, 2) });
  anchored.anchor(copy);
  assert.equal(findAllInjections((await anchored.project({ messages: copy })).messages).length, 1);

  // The decidable case, and the one that matters: a message the model READ is
  // changed. With no anchor, the chain refuses. (Growing a copy past the
  // placement is a legitimate extension — the pair simply moves to the end of
  // the new copy — so growth is not the tampering to test here.)
  const rewritten = copy.map((message, index) =>
    index === 0 ? { role: "user", content: [{ type: "text", text: "a different question" }] } : message,
  );
  const bare = new PairHint({ store: new MemoryJournal() });
  await bare.bind({ sessionId: "b", ladder: { explicit: "English" } });
  await bare.project({ messages: copy });
  assert.equal(await isDrift(bare.project({ messages: rewritten })), true, "an unanchored rewrite is drift");
});

test("canonical: the injected pair is recognised by the chain, not by its host shape", () => {
  const hint = new PairHint({ store: new MemoryJournal() });
  // A pair rendered twice, with different metadata and key order, is one pair.
  const pair = (meta) => ({
    role: "assistant",
    ...meta,
    content: [{ type: "tool-call", toolCallId: "pair-hint:0:abc", toolName: "", input: { epoch: 0, after: 1 } }],
  });
  const left = canonicalMessage(pair({ timestamp: 1, usage: { in: 1 } }));
  const right = canonicalMessage(pair({ timestamp: 2 }));
  assert.equal(canonicalKey(pair({ timestamp: 1 })), canonicalKey(pair({ timestamp: 2 })));
  assert.equal(left.parts[0].toolName, "");
  assert.equal(right.parts[0].input, JSON.stringify({ after: 1, epoch: 0 }));
  void hint;
});

test("canonical: a still-streaming placeholder is the same message once finalised", () => {
  const streaming = {
    role: "assistant",
    content: [{ type: "tool-call", toolCallId: "c1", toolName: "cat", input: {}, partialArgs: '{"a":', streamIndex: 2 }],
    usage: { input: 0, output: 0 },
    stopReason: "stop",
  };
  const finalised = {
    role: "assistant",
    content: [{ type: "tool-call", toolCallId: "c1", toolName: "cat", input: {}, arguments: "{}" }],
    usage: { input: 12, output: 3 },
    responseId: "r-1",
    duration: 1200,
    completedAt: 1,
    stopReason: "toolUse",
  };
  assert.equal(canonicalKey(streaming), canonicalKey(finalised), "streaming bookkeeping is not conversation");
  // But a different argument IS.
  const different = { ...finalised, content: [{ type: "tool-call", toolCallId: "c1", toolName: "cat", input: { a: 1 } }] };
  assert.notEqual(canonicalKey(finalised), canonicalKey(different));
});

test("canonical: an empty placeholder message is not the finalised one", () => {
  // The host builds `{role:"assistant", content:[]}` before the provider answers.
  // Empty content and the finalised content are DIFFERENT messages: the model
  // read nothing the first time and words the second, so the adapter must not
  // present the placeholder as if it were final (host-omp normalises before the
  // chain; the chain itself refuses the change, which is the safe direction).
  const placeholder = { role: "assistant", content: [] };
  const finalised = { role: "assistant", content: [{ type: "text", text: "answer" }] };
  assert.notEqual(canonicalKey(placeholder), canonicalKey(finalised));
});

// ---------------------------------------------------------------------------
// 5. A re-base names its cause, and a cold start reads it back
// ---------------------------------------------------------------------------

test("re-base: the journal names the projection that replaced the chain, and a cold start reads it", async () => {
  await fc.assert(
    fc.asyncProperty(
      fc.constantFrom(REBASE_REASONS.RELAY, REBASE_REASONS.SCOPE_RESET, REBASE_REASONS.SCOPE_REMOVE),
      fc.integer({ min: 1, max: 4 }),
      async (reason, length) => {
        const root = await tempRoot();
        try {
          const sessionId = "rebase";
          const hint = await PairHint.open({ store: new JournalStore({ root, sessionId }) });
          await hint.bind({ sessionId, ladder: { explicit: "English" } });
          const semantics = buildSemantics(Array.from({ length }, (_, index) => (index % 2 === 0 ? "user" : "assistant")));
          await hint.project({ messages: copyOf(semantics, 0, {}) });
          // The projection the spec owns replaces the copy.
          hint.anchor(copyOf(semantics, 1, {}), { reason });
          const after = await hint.project({ messages: copyOf(semantics, 1, {}) });
          assert.equal(findAllInjections(after.messages).length, 1);

          // A cold start reads the same provenance from the record stream.
          const reopened = await PairHint.open({ store: new JournalStore({ root, sessionId }) });
          assert.equal(reopened.rebased, reason, "the re-base is a persisted fact");
          // A reason the module does not know is a caller bug, refused before
          // anything is written.
          assert.throws(() => hint.anchor([], { reason: "panic" }), (error) => error.code === PAIR_HINT_CODES.INVALID_ARGUMENT);
        } finally {
          await fs.rm(root, { recursive: true, force: true });
        }
      },
    ),
    { ...fcRuns(30) },
  );
});

// ---------------------------------------------------------------------------
// 6. Ephemeral requests are branches of the chain, never steps of it
// ---------------------------------------------------------------------------
//
// The live failure this models (2026-11-01, session `01a19547`): OMP fires its
// `<title-request>` side turn through the same `context` hook 4 ms before the
// main turn's tool calls finish. The side copy extended the pair-free prefix,
// the chain accepted it, the chain state became the side turn, and the main turn's
// very next request differed at index 1 — every request of the session refused.
//
// The property is stated as three facts about ANY ephemeral copy a host can
// build: it gets the pair, the chain does not move, and the next main request is
// still compared against the last real one.

// The copy OMP's `#buildEphemeralSnapshot` builds: the conversation, the
// in-flight assistant message truncated to its text, a `developer` reminder, and
// the side prompt as a `user` message.
const ephemeralCopy = (semantics, { streamedText = null, prompt = "<title-request>" } = {}) => {
  const copy = copyOf(semantics, 0, {});
  if (streamedText !== null) {
    const partial = { role: "assistant", content: [{ type: "text", text: streamedText }] };
    const last = copy[copy.length - 1];
    if (last?.role === "assistant") copy[copy.length - 1] = partial;
    else copy.push(partial);
  }
  copy.push({ role: "developer", content: [{ type: "text", text: "Ephemeral side-channel turn; reuses current conversation context." }] });
  copy.push({ role: "user", content: [{ type: "text", text: prompt }] });
  return copy;
};

test("ephemeral: a side turn gets the pair, records nothing and never moves the chain", async () => {
  await fc.assert(
    fc.asyncProperty(
      fc.array(fc.oneof(fc.constant("user"), fc.constant("assistant"), fc.constant("call"), fc.constant("result")), {
        minLength: 1,
        maxLength: 6,
      }),
      fc.string({ maxLength: 12 }),
      fc.string({ minLength: 1, maxLength: 12 }),
      async (turns, streamedText, prompt) => {
        const hint = new PairHint({ store: new MemoryJournal() });
        await hint.bind({ sessionId: "eph", ladder: { explicit: "English" } });
        const semantics = buildSemantics(turns);
        const main = await hint.project({ messages: copyOf(semantics, 0, {}) });
        const before = { chain: hint.chain, after: hint.fact.after, epoch: hint.epoch };

        const side = await hint.project({
          messages: ephemeralCopy(semantics, { streamedText, prompt }),
          kind: REQUEST_KINDS.EPHEMERAL,
        });
        // It carries the pair: the side request is there to keep the cache warm.
        assert.equal(findAllInjections(side.messages).length, 1, "the side turn still carries the pair");
        assert.equal(side.text, main.text, "the side turn shows the epoch's frozen bytes");
        // And the chain is exactly where it was.
        assert.equal(hint.chain, before.chain, "the side turn did not become the chain");
        assert.equal(hint.fact.after, before.after, "the placement did not move");
        assert.equal(hint.epoch, before.epoch, "the side turn opened no epoch");
        // The next main request is still measured against the last real one.
        const next = await hint.project({ messages: copyOf(semantics, 0, {}) });
        assert.deepEqual(next.messages, main.messages, "the next main request answers the same bytes");
      },
    ),
    { ...fcRuns(120) },
  );
});

test("ephemeral: an earlier message rewritten inside the branch is still drift", async () => {
  // The branch may diverge only where the host's in-flight state made it
  // diverge. A rewrite of a message the model already read as final is refused,
  // whether the request calls itself ephemeral or not.
  const hint = new PairHint({ store: new MemoryJournal() });
  await hint.bind({ sessionId: "eph2", ladder: { explicit: "English" } });
  const semantics = buildSemantics(["user", "assistant", "user", "assistant", "user"]);
  await hint.project({ messages: copyOf(semantics, 0, {}) });
  const tampered = copyOf(semantics, 0, {});
  // Index 0 is well before the turn that could still be streaming, so it is a
  // message the model read as FINAL. The host never rewrites one of those in a
  // side snapshot (it only replaces the message it is still writing), so this
  // is a tampering, not a lifecycle.
  tampered[0] = { role: "user", content: [{ type: "text", text: "a different question" }] };
  tampered.push({ role: "developer", content: [{ type: "text", text: "side" }] });
  assert.equal(
    await isDrift(hint.project({ messages: tampered, kind: REQUEST_KINDS.EPHEMERAL })),
    true,
    "an unapproved rewrite may not hide behind the ephemeral label",
  );
});

test("ephemeral: a branch that deletes from the middle is refused", async () => {
  const hint = new PairHint({ store: new MemoryJournal() });
  await hint.bind({ sessionId: "eph3", ladder: { explicit: "English" } });
  const semantics = buildSemantics(["user", "assistant", "user", "assistant"]);
  await hint.project({ messages: copyOf(semantics, 0, {}) });
  // The branch keeps the FIRST and LAST messages and drops what was between
  // them. No host trims the middle of a request: a side turn appends, a
  // compaction keeps a suffix, an in-flight snapshot replaces the last message.
  // A gap in the middle is a deletion of messages the model read as final.
  const whole = copyOf(semantics, 0, {});
  const gapped = [whole[0], whole[3], { role: "user", content: [{ type: "text", text: "<title>" }] }];
  assert.equal(
    await isDrift(hint.project({ messages: gapped, kind: REQUEST_KINDS.EPHEMERAL })),
    true,
    "a branch with a gap in the middle is drift",
  );
});

test("ephemeral: a branch may drop a TAIL (a per-call compaction), and the pair clamps to its end", async () => {
  // Cline's per-call compaction hands the hook a SHORTER copy and never stores
  // it, so the chain keeps its placement and the branch simply cannot carry the
  // pair that far. Clamping is legal ONLY here: a MAIN request that is shorter
  // than the placement still refuses (see the shortening properties).
  const hint = new PairHint({ store: new MemoryJournal() });
  await hint.bind({ sessionId: "eph5", ladder: { explicit: "English" } });
  const semantics = buildSemantics(["user", "assistant", "user", "assistant", "user"]);
  const whole = copyOf(semantics, 0, {});
  await hint.project({ messages: whole });
  const after = hint.fact.after;

  // Keep only a prefix: every message the branch carries is one the model read,
  // unchanged and in order.
  const head = whole.slice(0, 2);
  const side = await hint.project({ messages: head, kind: REQUEST_KINDS.EPHEMERAL });
  assert.equal(findAllInjections(side.messages).length, 1, "the truncated branch still carries the pair");
  assert.equal(side.fact.after, head.length, "the pair sits at the branch's own end");
  assert.equal(hint.fact.after, after, "the chain's placement did not move");
  assert.ok(
    hint.chain.withoutPair().equals(Chain.fromMessages(whole)),
    "the chain still holds the conversation, not the branch",
  );

  // The same shortening on a MAIN request is still refused: clamping is a
  // branch's allowance, never the chain's.
  assert.equal(await isDrift(hint.project({ messages: head })), true, "a main request may not clamp");
});

test("ephemeral: an unknown request kind is refused before anything moves", async () => {
  const hint = new PairHint({ store: new MemoryJournal() });
  await hint.bind({ sessionId: "eph4", ladder: { explicit: "English" } });
  await assert.rejects(
    hint.project({ messages: [], kind: "sideways" }),
    (error) => error.code === PAIR_HINT_CODES.INVALID_ARGUMENT,
  );
  assert.equal(hint.chain, null, "a bad kind recorded nothing");
});
