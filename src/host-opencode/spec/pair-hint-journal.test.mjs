// The Pair Hint journal key and per-session isolation (§6.1, §6.1, §6.1).
//
// The key must map every distinct host session id to its own journal file: two
// ids that sanitize to the same file-safe spelling would otherwise share one
// epoch, one language and one presentation. These tests use the real filesystem
// journal, so they check the fact the host depends on, not a helper's shape.
//
// Run with: `node --test "src/host-opencode/**/*.test.mjs"`.

import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";

import { bindingIdFor, journalKey, journalKeyOf, openPairHint } from "./pair-hint-request.mjs";

// The binding name a session writes into its journal. The journal is keyed by a
// digest, so a caller either supplies the host's real id or lets the key stand
// in; the tests use the same helper the adapter uses.
const bindId = (sessionId) => bindingIdFor({ sessionId });

const tempRoot = async () => fs.mkdtemp(path.join(os.tmpdir(), "hc-hint-"));

test("ids that differ only in unsafe characters get different journal keys", () => {
  // The old sanitizing key turned both of these into `a_b.hint`.
  const pairs = [
    ["a/b", "a?b"],
    ["a:b", "a b"],
    ["x".repeat(120), "x".repeat(121)],
    ["ses_01ABC", "ses_01abc"],
  ];
  for (const [left, right] of pairs) {
    assert.notEqual(journalKey(left), journalKey(right), `${left} vs ${right}`);
  }
  // Deterministic: the same id always finds the same file after a restart.
  assert.equal(journalKey("ses_01ABC"), journalKey("ses_01ABC"));
  // File-safe for core's `assertSessionId`.
  assert.match(journalKey("a/b?c d"), /^[A-Za-z0-9._-]+$/);
});

test("a non-string or empty session id is refused, never coerced", () => {
  for (const bad of [undefined, null, "", 0, {}]) {
    assert.throws(() => journalKey(bad), TypeError);
  }
});

test("two sessions that share a file-safe spelling keep separate journals", async () => {
  const root = await tempRoot();
  const first = await openPairHint({ root, sessionId: "a/b" });
  await first.bind({ sessionId: bindId("a/b"), ladder: { explicit: "English" } });
  await first.project({ messages: [{ role: "user", content: "one" }] });
  await first.newEpoch();

  const second = await openPairHint({ root, sessionId: "a?b" });
  // A fresh journal: no binding, no epoch, no chain.
  assert.equal(second.bound, false);
  assert.equal(second.epoch, 0);
  assert.equal(second.chain, null);
  await second.bind({ sessionId: bindId("a?b"), ladder: { explicit: "SimplifiedChinese" } });
  await second.project({ messages: [{ role: "user", content: "two" }] });

  // The first session's facts did not move when the second one wrote.
  const rereadFirst = await openPairHint({ root, sessionId: "a/b" });
  assert.equal(rereadFirst.sessionId, journalKeyOf("a/b"));
  assert.equal(rereadFirst.language, "English");
  assert.equal(rereadFirst.epoch, 1);
  // The first session presented two requests; the second session's write did
  // not touch that journal.
  assert.equal((await fs.readdir(path.join(root, ".Herdchestra"))).length, 2);

  const rereadSecond = await openPairHint({ root, sessionId: "a?b" });
  assert.equal(rereadSecond.sessionId, journalKeyOf("a?b"));
  assert.equal(rereadSecond.language, "SimplifiedChinese");
  assert.equal(rereadSecond.epoch, 0);

  // Two files, not one.
  const files = await fs.readdir(path.join(root, ".Herdchestra"));
  assert.equal(files.filter((name) => name.endsWith(".jsonl")).length, 2, files.join(","));

  await fs.rm(root, { recursive: true, force: true });
});

test("a cold start rebuilds the same epoch, language and presented copy", async () => {
  const root = await tempRoot();
  const messages = [{ role: "user", content: "hello" }];

  const first = await openPairHint({ root, sessionId: "ses/cold" });
  await first.bind({ sessionId: bindId("ses/cold"), ladder: { explicit: "English" } });
  const placed = await first.project({ messages });
  await first.newEpoch();
  const next = await first.project({ messages: [...messages, { role: "user", content: "again" }] });

  // A new process reads the journal, not the previous object.
  const restarted = await openPairHint({ root, sessionId: "ses/cold" });
  assert.equal(restarted.sessionId, journalKeyOf("ses/cold"));
  assert.equal(restarted.language, "English");
  assert.equal(restarted.epoch, 1);
  // Same chain for the request the previous process last sent: the journal
  // keeps digests, not a copy of the conversation (root §1.1 item 1).
  assert.deepEqual(restarted.chain.digests, first.chain.digests);
  assert.equal(restarted.chain.length, first.chain.length);
  assert.equal(restarted.chain.root, first.chain.root);
  assert.equal(restarted.chain.pairIndex, first.chain.pairIndex);
  assert.equal(placed.mode, "placed");
  // The new epoch places the pair again, at the new fact's position.
  assert.equal(next.mode, "placed");

  // The restarted hint keeps the bytes the previous process sent. The host
  // resends the same request copy, which does not carry the pair: the hint
  // places it at the recorded position, and the result equals what was shown.
  const again = await restarted.project({ messages: [...messages, { role: "user", content: "again" }] });
  assert.deepEqual(again.messages, next.messages);
  assert.equal(again.fact.digest, restarted.fact.digest);

  await fs.rm(root, { recursive: true, force: true });
});

test("the journal binding names the host's session, not the key", async () => {
  const root = await tempRoot();
  const hint = await openPairHint({ root, sessionId: "ses/owner" });
  const binding = await hint.bind({ sessionId: bindId("ses/owner"), ladder: { explicit: "English" } });

  // The file is named by the digest; the binding record carries the name the
  // caller supplied, which is the host's real id when the adapter passes one.
  assert.equal(binding.sessionId, journalKeyOf("ses/owner"));
  const file = path.join(root, ".Herdchestra", journalKey("ses/owner"));
  const lines = (await fs.readFile(file, "utf8")).trim().split("\n").map((line) => JSON.parse(line));
  assert.equal(lines[0].binding.sessionId, journalKeyOf("ses/owner"));

  await fs.rm(root, { recursive: true, force: true });
});

test("a copy the host hands back as it sent it is extended, not refused", async () => {
  const root = await tempRoot();
  const hint = await openPairHint({ root, sessionId: "ses/echo" });
  await hint.bind({ sessionId: bindId("ses/echo"), ladder: { explicit: "English" } });

  const first = await hint.project({ messages: [{ role: "user", content: "one" }] });
  // The host replays the request it just sent, pair and all, and grows it by
  // the turn that followed. That copy starts with what the model already read,
  // so it extends the chain instead of drifting from it.
  const echoed = [...first.messages, { role: "user", content: "two" }];
  const again = await hint.project({ messages: echoed });
  assert.equal(again.mode, "replayed");
  assert.deepEqual(again.messages.slice(0, first.messages.length), first.messages);
  // The placement follows the pair the host showed, so a copy without the pair
  // still lands between the same two messages.
  const bare = await hint.project({ messages: [{ role: "user", content: "one" }, { role: "user", content: "two" }] });
  assert.equal(bare.messages.length, 4);
  assert.deepEqual(bare.messages.slice(0, 1), [{ role: "user", content: "one" }]);

  await fs.rm(root, { recursive: true, force: true });
});
