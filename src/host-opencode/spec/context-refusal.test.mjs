// The request hook's failure branches (§6.1, §6.2).
//
// The host gives `session.hook("context")` no failure channel: `hooks.trigger`
// runs the callback inside the session drain, so a throw becomes a defect of
// the drain and the host records a failed execution instead of sending the
// request (`packages/core/src/plugin/hooks.ts`, `packages/core/src/session/
// execution.ts`). That is the only way this adapter can refuse a request, and
// §6.1 requires the refusal: a missing resource book, an unbound language or a
// prefix that would drift must fail, never turn into a request without the hint.
//
// These tests call the hook the way the host does — a real `setup` context, a
// real event object — and assert that the failure propagates and that the event
// was not altered on the way out.
//
// Run with: `node --test "src/host-opencode/**/*.test.mjs"`.

import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs/promises";
import path from "node:path";

import { setup } from "./plugin.mjs";
import { applyContextHook, ContextRefusal } from "./context-hook.mjs";
import { openPairHint } from "./pair-hint-request.mjs";
import { SessionState } from "./session-state.mjs";
import { tempRoot as tempRootDir } from "./test-temp.mjs";

// One workspace per test; the registry removes it when the process exits, so no
// file can forget one.
const tempRoot = (prefix) => tempRootDir(prefix);

// A fake host that records the context hook, exactly like the plugin registers
// it. `sessions` is only needed for the hook's own session domain.
const fakeHost = async ({ directory } = {}) => {
  const dir = directory ?? (await tempRoot());
  const contextHooks = [];
  const ctx = {
    location: { directory: dir },
    options: {},
    agent: { transform: async (callback) => callback({ list: () => [], update() {} }) },
    tool: {
      transform: async (callback) => callback({ add() {}, update() {}, remove() {} }),
      hook: async () => {},
    },
    session: {
      hook: async (name, callback) => {
        if (name === "context") contextHooks.push(callback);
      },
    },
    event: {
      subscribe: () => ({
        [Symbol.asyncIterator]: () => ({ next: async () => ({ done: true }) }),
        return: async () => ({ done: true }),
      }),
    },
  };
  const cleanup = await setup(ctx);
  return { dir, contextHooks, cleanup };
};

const event = (messages = [{ role: "user", content: [{ type: "text", text: "hello" }] }]) => ({
  sessionID: "ses_refuse",
  agent: "Manager",
  tools: { "js-manager": {} },
  system: [],
  messages,
});

test("a hint with no language binding fails the request instead of dropping the hint", async () => {
  const root = await tempRoot();
  const state = new SessionState({ sessionID: "ses_refuse", root });
  const hint = await openPairHint({ root, sessionId: "ses_refuse" });
  assert.equal(hint.bound, false);
  const request = event();
  const before = JSON.stringify(request.messages);
  await assert.rejects(
    applyContextHook(request, { state, hint }),
    (error) => error instanceof ContextRefusal && error.code === "PAIR_HINT_FAILED",
  );
  // Nothing was sent, and the copy is the one the host handed in.
  assert.equal(JSON.stringify(request.messages), before);
});

test("a missing resource book fails the request", async () => {
  const root = await tempRoot();
  const state = new SessionState({ sessionID: "ses_refuse", root });
  const library = {
    loadAll: async () => {
      throw Object.assign(new Error("resource budget is missing zh-CN.md; a pair is required"), {
        code: "RESOURCE_PAIR_INCOMPLETE",
      });
    },
  };
  const hint = await openPairHint({ root, sessionId: "ses_refuse", library });
  await hint.bind({ sessionId: hint.sessionId, ladder: { explicit: "English" } });
  // The hint's own code is kept: a missing book is a resource failure, not a
  // generic hint failure, and the code is what a reader looks for in the log.
  await assert.rejects(
    applyContextHook(event(), { state, hint }),
    (error) =>
      error instanceof ContextRefusal &&
      error.code === "RESOURCE_PAIR_INCOMPLETE" &&
      /missing/.test(error.details.detail),
  );
});

test("a request that would move the presented bytes fails instead of being re-placed", async () => {
  const root = await tempRoot();
  const state = new SessionState({ sessionID: "ses_refuse", root });
  const hint = await openPairHint({ root, sessionId: "ses_refuse" });
  await hint.bind({ sessionId: hint.sessionId, ladder: { explicit: "English" } });
  // The first request places the pair; the second presents different bytes for
  // a message the model already read.
  await applyContextHook(event(), { state, hint });
  const moved = [
    { role: "user", content: [{ type: "text", text: "changed" }] },
    { role: "assistant", content: [{ type: "text", text: "answer" }] },
  ];
  await assert.rejects(
    applyContextHook(event(moved), { state, hint }),
    (error) => error instanceof ContextRefusal && error.code === "PREFIX_DRIFT",
  );
});

// The host logs the thrown value as the drain's cause, so whatever the refusal
// carries is what an operator can read later. The live drift of 2026-11-01 was
// diagnosable only from the host's own database because the refusal carried a
// sentence and nothing else.
test("a drift refusal tells the host log where it drifted, and no message text", async () => {
  const root = await tempRoot();
  const state = new SessionState({ sessionID: "ses_refuse", root });
  const hint = await openPairHint({ root, sessionId: "ses_refuse" });
  await hint.bind({ sessionId: hint.sessionId, ladder: { explicit: "English" } });
  const secret = "sk-live-DO-NOT-LOG-0123456789";
  await applyContextHook(event(), { state, hint });
  const moved = [
    { role: "user", content: [{ type: "text", text: `the operator's words ${secret}` }] },
    { role: "assistant", content: [{ type: "text", text: "answer" }] },
  ];
  const error = await applyContextHook(event(moved), { state, hint }).then(
    () => assert.fail("expected a refusal"),
    (caught) => caught,
  );
  assert.equal(error.code, "PREFIX_DRIFT");
  assert.equal(error.details.epoch, 0, "the epoch the refusal happened in");
  assert.ok(Number.isInteger(error.details.index), "the first differing index");
  const { previous, next } = error.details.compared;
  assert.ok(previous && next, "both compared sides are reported");
  // The chain side carries only digests (root §1.1 item 1: the full copy is not
  // persisted), while the request side still carries its shapes. Both name the
  // same first difference.
  assert.equal(previous.count, 1);
  assert.equal(next.count, 2);
  assert.equal(error.details.index, 0);
  assert.notEqual(previous.shapes[0].digest, next.shapes[0].digest, "the two sides differ at the reported index");
  assert.match(next.shapes[0].digest, /^[0-9a-f]{64}$/);
  assert.deepEqual(next.shapes[0].parts, ["text"]);
  assert.deepEqual(Object.keys(previous.shapes[0]), ["digest"], "the chain side keeps no text");
  // What the host logs must not be the conversation: shapes, lengths, digests.
  const logged = JSON.stringify(error.details);
  assert.equal(logged.includes(secret), false, "no message text reaches the host log");
  assert.equal(logged.includes("the operator's words"), false);
  // The shape a refusal carries is core's (`pair-hint/facts.mjs` `isShape`): the
  // tool-call ids and result ids of one message travel with it, because a
  // pairing that moved is exactly what a drift refusal has to name.
  assert.deepEqual(Object.keys(next.shapes[0]).sort(), ["calls", "digest", "length", "parts", "results", "role"]);
});

test("a projection refusal stops the request and names its code", async () => {
  const root = await tempRoot();
  const state = new SessionState({ sessionID: "ses_refuse", root });
  const hint = await openPairHint({ root, sessionId: "ses_refuse" });
  await hint.bind({ sessionId: hint.sessionId, ladder: { explicit: "English" } });
  // A call whose result disagrees with it on retention is what core refuses; the
  // adapter does not repair it and does not send the request anyway.
  const refusing = {
    scopeOf: () => ({
      admit: () => ({ ok: true }),
      settle: async () => ({ ok: true }),
      closeTurn: async () => null,
      project: () => ({ ok: false, code: "SCOPE_FIELD_INVALID", message: "tool call and its result disagree" }),
    }),
  };
  await assert.rejects(
    applyContextHook(event(), { state: refusing, hint }),
    (error) => error instanceof ContextRefusal && error.code === "SCOPE_FIELD_INVALID",
  );
});

test("a host message the adapter cannot read fails the request", async () => {
  const root = await tempRoot();
  const state = new SessionState({ sessionID: "ses_refuse", root });
  const hint = await openPairHint({ root, sessionId: "ses_refuse" });
  await hint.bind({ sessionId: hint.sessionId, ladder: { explicit: "English" } });
  await assert.rejects(
    applyContextHook(event([{ role: "user", content: "not an array" }, null]), { state, hint }),
    (error) => error instanceof ContextRefusal && error.code === "MESSAGE_COPY_FAILED",
  );
});

test("the registered hook lets the failure out to the host", async () => {
  const host = await fakeHost();
  // A journal that cannot be read is a §6.1 failure at open time.
  await fs.mkdir(path.join(host.dir, ".Herdchestra"), { recursive: true });
  const { journalKey } = await import("./pair-hint-request.mjs");
  await fs.writeFile(
    path.join(host.dir, ".Herdchestra", journalKey("ses_refuse")),
    '{"kind":"bind"}\n{"kind":"presented","messages":[1],"length":1}\n',
    "utf8",
  );
  await assert.rejects(host.contextHooks[0](event()), (error) => {
    assert.equal(error.name, "ContextRefusal");
    return true;
  });
  await host.cleanup();
});
