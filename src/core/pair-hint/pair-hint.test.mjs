// Language binding, resource pairs and the presentation/injection path.
//
// These tests check the form rules of spec §6.1: two languages, pairs complete,
// one binding per session, one pair per request, no byte drift inside an epoch,
// and a cold start that rebuilds the same bytes without re-reading resources.

import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import fc from "fast-check";

import {
  LANGUAGES,
  RESOURCE_NAMES,
  resolveLanguage,
  bindSession,
  LanguageBinding,
  ResourceLibrary,
  RESOURCE_IDS,
  loadPair,
  renderPresentation,
  parseHint,
  factFor,
  syntheticMessages,
  findInjection,
  callIdParts,
  canonicalKey,
  digestOf,
  Chain,
  PairHintInjector,
  INJECTION_MODES,
  PairHint,
  MemoryJournal,
  JournalStore,
  foldRecords,
  PairHintState,
  RECORD_KINDS,
  REFUSAL_SHAPE_LIMIT,
  PAIR_HINT_CODES,
  isPairHintError,
} from "./index.mjs";
import { fcRuns } from "../test-runs.mjs";

const codes = (fn) => {
  try {
    fn();
  } catch (error) {
    assert.ok(isPairHintError(error), `expected PairHintError, got ${error}`);
    return error.code;
  }
  assert.fail("expected a refusal");
};

const asyncCode = async (promise) => {
  try {
    await promise;
  } catch (error) {
    assert.ok(isPairHintError(error), `expected PairHintError, got ${error}`);
    return error.code;
  }
  assert.fail("expected a refusal");
};

const tempRoot = async () => await fs.mkdtemp(path.join(os.tmpdir(), "pair-hint-"));

// The books of one language, keyed by resource id, in presentation order.
const loadedBlocks = async (language) => {
  const pairs = await new ResourceLibrary().loadAll();
  const blocks = {};
  for (const pair of pairs) blocks[pair.id] = pair.book(language);
  return blocks;
};

const renderFor = async (language, handles = []) =>
  renderPresentation({
    language,
    entries: [...RESOURCE_IDS],
    blocks: await loadedBlocks(language),
    handles,
  });

test("the ladder takes the first usable rung and records its source", () => {
  assert.deepEqual(resolveLanguage({ explicit: "SimplifiedChinese" }), {
    language: LANGUAGES.SIMPLIFIED_CHINESE,
    source: "env",
  });
  assert.deepEqual(resolveLanguage({ explicit: "SimplifiedChinese", host: "English", locale: "en-US" }), {
    language: LANGUAGES.SIMPLIFIED_CHINESE,
    source: "env",
  });
  assert.deepEqual(resolveLanguage({ host: "zh-CN", locale: "en-US" }), {
    language: LANGUAGES.SIMPLIFIED_CHINESE,
    source: "host",
  });
  assert.deepEqual(resolveLanguage({ host: "fr-FR", locale: "en-GB" }), {
    language: LANGUAGES.ENGLISH,
    source: "locale",
  });
  assert.deepEqual(resolveLanguage({}), { language: LANGUAGES.ENGLISH, source: "default" });
});

test("a present but unusable explicit language fails instead of falling through", () => {
  assert.equal(codes(() => resolveLanguage({ explicit: "Klingon", locale: "zh-CN" })), PAIR_HINT_CODES.INVALID_LANGUAGE);
  assert.equal(codes(() => resolveLanguage({ explicit: "" })), PAIR_HINT_CODES.INVALID_LANGUAGE);
});

test("traditional Chinese locales are not passed off as Simplified Chinese", () => {
  assert.equal(resolveLanguage({ locale: "zh-TW" }).source, "default");
  assert.equal(resolveLanguage({ locale: "zh-Hant-TW" }).source, "default");
  assert.equal(resolveLanguage({ locale: "zh-Hans-CN" }).language, LANGUAGES.SIMPLIFIED_CHINESE);
  assert.equal(resolveLanguage({ locale: "zh-CN,en;q=0.8" }).language, LANGUAGES.SIMPLIFIED_CHINESE);
  // POSIX values carry a codeset and a modifier that are not part of the tag:
  // the region still decides, and `zh.UTF-8` is plain `zh`.
  assert.equal(resolveLanguage({ locale: "zh_TW.UTF-8" }).source, "default");
  assert.equal(resolveLanguage({ locale: "zh_HK.UTF-8@euro" }).source, "default");
  assert.equal(resolveLanguage({ locale: "zh.UTF-8" }).language, LANGUAGES.SIMPLIFIED_CHINESE);
  assert.equal(resolveLanguage({ locale: "en_US.UTF-8" }).language, LANGUAGES.ENGLISH);
});

test("a fork copies the owner's language and is independent afterwards", () => {
  const owner = bindSession({ sessionId: "owner", ladder: { locale: "zh-CN" } });
  const child = owner.fork("child");
  assert.equal(child.language, owner.language);
  assert.equal(child.source, "fork");
  assert.equal(child.ownerSessionId, "owner");
  assert.equal(child.fork("grandchild").language, owner.language);
  assert.equal(codes(() => owner.fork("owner")), PAIR_HINT_CODES.INVALID_ARGUMENT);
  assert.equal(LanguageBinding.fromRecord(child.toRecord()).language, owner.language);
});

test("a binding cannot be changed after it is made", () => {
  const binding = bindSession({ sessionId: "frozen", ladder: { explicit: "English" } });
  assert.throws(() => {
    binding.language = LANGUAGES.SIMPLIFIED_CHINESE;
  });
  assert.throws(() => {
    binding.sessionId = "other";
  });
  assert.equal(binding.language, LANGUAGES.ENGLISH);
  assert.equal(binding.sessionId, "frozen");
});

test("every required resource is a complete pair", async () => {
  assert.deepEqual([...RESOURCE_IDS], [
    "repo-aware-rules",
    "pair-programming",
    "token-economy",
    "wait-cost",
    "context-limit",
    "budget",
  ]);
  for (const id of RESOURCE_IDS) {
    const pair = await loadPair(id);
    assert.equal(pair.complete, true, id);
    assert.ok(pair.book(LANGUAGES.ENGLISH).trim().length > 0, `${id}/en.md`);
    assert.ok(pair.book(LANGUAGES.SIMPLIFIED_CHINESE).trim().length > 0, `${id}/zh-CN.md`);
  }
});

test("a missing book fails and never substitutes the other language", async () => {
  const shipped = await loadPair("wait-cost");
  assert.equal(shipped.complete, true);
  const library = new ResourceLibrary({ ids: ["half"] });
  assert.equal(await asyncCode(library.load("half")), PAIR_HINT_CODES.RESOURCE_PAIR_INCOMPLETE);
  const empty = new ResourceLibrary({ ids: [] });
  assert.equal(await asyncCode(empty.load("budget")), PAIR_HINT_CODES.RESOURCE_UNKNOWN);
});

test("a rendered hint carries every resource and puts the handles last", async () => {
  const zh = await renderFor(LANGUAGES.SIMPLIFIED_CHINESE, ["zeta", "alpha"]);
  for (const id of RESOURCE_IDS) assert.ok(zh.includes(`# ${id}`), id);
  assert.ok(zh.startsWith("# language\n\n"), "the language rule comes first");
  assert.ok(zh.includes("- alpha\n- zeta"), "handles are sorted");
  assert.ok(zh.trimEnd().endsWith("系统也不会替你恢复任何一处。"), "the handles section is last");
  const en = await renderFor(LANGUAGES.ENGLISH);
  assert.ok(!en.includes("思考过程与全部对外输出"), "the English hint does not carry the Chinese rule");
  assert.ok(en.includes("None: no park of this session is unsettled."));
});

test("a rendered hint parses back into the same resources", async () => {
  const text = await renderFor(LANGUAGES.ENGLISH, ["b", "a"]);
  const parsed = parseHint(text);
  assert.deepEqual(parsed.entries, [...RESOURCE_IDS]);
  assert.ok(parsed.blocks["pair-programming"].startsWith("# pair-programming"));
  assert.match(parsed.handles, /- a\n- b/);
  // Re-rendering from the parse yields the same bytes: this is the cold-start
  // contract, and it does not touch the resource files.
  const again = renderPresentation({
    language: LANGUAGES.ENGLISH,
    entries: parsed.entries,
    blocks: parsed.blocks,
    handles: ["a", "b"],
  });
  assert.equal(again, text);
});

test("the synthetic pair carries the empty tool name and the same call id on replay", async () => {
  const text = await renderFor(LANGUAGES.ENGLISH);
  const fact = factFor({ epoch: 0, text, handles: [] });
  const [call, result] = syntheticMessages({ fact, text });
  assert.equal(call.content[0].toolName, "");
  assert.equal(result.content[0].toolName, "");
  assert.equal(call.content[0].toolCallId, result.content[0].toolCallId);
  assert.equal(result.content[0].output.value, text);
  assert.equal(codes(() => syntheticMessages({ fact, text: `${text} changed` })), PAIR_HINT_CODES.INVALID_ARGUMENT);
});

test("injection places once, replays on the same fact, and refuses drift", async () => {
  const text = await renderFor(LANGUAGES.ENGLISH);
  const injector = new PairHintInjector();
  const fact = factFor({ epoch: 0, text, handles: [] });
  const base = [{ role: "user", content: "hello" }];

  const placed = injector.inject({ messages: base, fact, text });
  assert.equal(placed.mode, INJECTION_MODES.PLACED);
  assert.equal(placed.messages.length, 3);
  assert.equal(placed.fact.after, 1, "the first placement decides the placement");
  assert.equal(base.length, 1, "the input array is not mutated");
  assert.equal(findInjection(placed.messages).index, 1);

  // The host rebuilds the next request without our pair; the pair goes back
  // after the same number of messages, so the copy still starts with the
  // earlier bytes.
  const rebuilt = [...base, { role: "user", content: "next" }];
  const extended = injector.inject({ messages: rebuilt, fact: placed.fact, text });
  assert.equal(extended.mode, INJECTION_MODES.PLACED);
  assert.equal(extended.fact.after, 1);
  assert.equal(findInjection(extended.messages).index, 1);
  assert.doesNotThrow(() => injector.assertPrefix({ previous: placed.messages, next: extended.messages }));

  const again = injector.inject({ messages: extended.messages, fact: extended.fact, text });
  assert.equal(again.mode, INJECTION_MODES.REPLAYED);
  assert.equal(again.messages.length, extended.messages.length);

  const changedText = `${text}\n\nmore`;
  const changedFact = factFor({ epoch: 0, text: changedText, handles: [] });
  assert.equal(
    codes(() => injector.inject({ messages: extended.messages, fact: changedFact, text: changedText })),
    PAIR_HINT_CODES.PREFIX_DRIFT,
  );

  assert.equal(
    codes(() => injector.assertPrefix({ previous: placed.messages, next: [{ role: "user", content: "other" }] })),
    PAIR_HINT_CODES.PREFIX_DRIFT,
  );
});

test("the placement is a count of messages, not an index into one request", () => {
  // Two turns of one conversation. The host's own history grows by two messages
  // per turn, so an absolute index would land in a different place each time;
  // the count keeps the pair between the same two messages.
  const injector = new PairHintInjector();
  const fact = factFor({ epoch: 3, text: "hint", handles: [] });
  const history = [
    { role: "user", content: "one" },
    { role: "assistant", content: [{ type: "text", text: "two" }] },
    { role: "user", content: "three" },
  ];

  const placed = injector.inject({ messages: history, fact, text: "hint" });
  assert.equal(placed.fact.after, 3);
  assert.equal(placed.fact.inserted, 3);
  assert.equal(findInjection(placed.messages).index, 3);

  const longer = [...history, { role: "user", content: "four" }];
  const next = injector.inject({ messages: longer, fact: placed.fact, text: "hint" });
  assert.equal(next.fact.after, 3);
  assert.equal(findInjection(next.messages).index, 3);
  assert.deepEqual(next.messages.slice(0, 3), placed.messages.slice(0, 3));
  // Messages are two per pair: pair at [3, 4], so "four" lands at index 5.
  assert.deepEqual(next.messages[5], longer[3]);

  // A placement beyond the copy is refused, never clamped silently.
  assert.equal(
    codes(() => injector.inject({ messages: history, fact: placed.fact.at({ after: 9, inserted: 9 }), text: "hint" })),
    PAIR_HINT_CODES.PREFIX_DRIFT,
  );
});

test("a branch clamps the placement on the new-epoch path too", () => {
  const injector = new PairHintInjector();
  const oldFact = factFor({ epoch: 1, text: "old hint", handles: [], after: 1, inserted: 1 });
  const oldPair = syntheticMessages({ fact: oldFact, text: "old hint" });
  const host = { role: "user", content: "branch" };
  // The new epoch already recorded a placement counted on a longer main copy;
  // this ephemeral branch carries one of those messages plus the old pair.
  const newText = "new hint";
  const newFact = factFor({ epoch: 2, text: newText, handles: [], after: 5, inserted: 5 });

  const placed = injector.inject({ messages: [...oldPair, host], fact: newFact, text: newText, branch: true });
  assert.equal(placed.fact.after, 1, "the branch clamps to its own end");
  assert.equal(findInjection(placed.messages).index, 1);
  assert.equal(placed.messages.length, 3, "the old pair is replaced, never doubled");

  // The same copy on a main request is drift: a clamp is a branch's allowance.
  assert.equal(
    codes(() => injector.inject({ messages: [...oldPair, host], fact: newFact, text: newText })),
    PAIR_HINT_CODES.PREFIX_DRIFT,
  );
});

test("a new epoch drops the old pair and places the new one at the new placement", async () => {
  const hint = new PairHint({ store: new MemoryJournal() });
  await hint.bind({ sessionId: "s", ladder: { explicit: "English" } });
  const first = await hint.project({ messages: [{ role: "user", content: "a" }] });
  assert.equal(first.mode, INJECTION_MODES.PLACED);
  assert.equal(first.fact.after, 1);

  hint.setHandles(["h2", "h1"]);
  // The epoch froze its text, so a changed handle list no longer changes the
  // bytes of this epoch: the request is a plain replay of the same bytes.
  const replayed = await hint.project({ messages: first.messages });
  assert.equal(replayed.mode, INJECTION_MODES.REPLAYED);
  assert.equal(replayed.text, first.text, "a frozen epoch ignores later handle changes");

  // A rewritten message inside the epoch is still drift (§6.1: shown bytes never
  // change). The copy keeps this epoch's pair so the pair-free comparison is
  // about the host's own messages, and the rewritten first message is refused.
  const carried = first.messages.map((message) => ({ ...message }));
  carried[0] = { role: "user", content: "rewritten" };
  await assert.rejects(
    hint.project({ messages: carried }),
    (error) => error.code === PAIR_HINT_CODES.PREFIX_DRIFT,
    "a rewritten prefix is still drift",
  );

  await hint.newEpoch();
  const second = await hint.project({ messages: [{ role: "user", content: "a" }] });
  assert.equal(second.mode, INJECTION_MODES.PLACED);
  assert.equal(second.messages.length, 3);
  assert.ok(second.text.includes("- h1\n- h2"));
  const pairs = second.messages.filter(
    (message) => message.role === "assistant" && message.content[0]?.toolName === "",
  );
  assert.equal(pairs.length, 1, "exactly one pair after a new epoch");
  assert.equal(findInjection(second.messages).index, 1);

  // A retry that still carries the old epoch's pair: the old one leaves and the
  // new one is placed, never two pairs in one copy.
  const retried = await hint.project({ messages: first.messages });
  assert.equal(retried.mode, INJECTION_MODES.PLACED);
  assert.equal(retried.messages.length, 3);
  assert.equal(findInjection(retried.messages).index, 1);
  assert.ok(retried.messages[2].content[0].output.value.includes("- h1\n- h2"));
});

test("bind happens once and a restart keeps the language", async () => {
  const root = await tempRoot();
  const first = new PairHint({ store: new JournalStore({ root, sessionId: "s1" }) });
  await first.bind({ ladder: { explicit: "SimplifiedChinese" } });
  assert.equal(first.language, LANGUAGES.SIMPLIFIED_CHINESE);
  assert.equal((await first.bind()).language, LANGUAGES.SIMPLIFIED_CHINESE);
  assert.equal(await asyncCode(first.bind({ sessionId: "s2" })), PAIR_HINT_CODES.INVALID_ARGUMENT);

  const restarted = await PairHint.open({ root, sessionId: "s1" });
  assert.equal(restarted.language, LANGUAGES.SIMPLIFIED_CHINESE);
  await restarted.bind({ ladder: { explicit: "English" } });
  assert.equal(restarted.language, LANGUAGES.SIMPLIFIED_CHINESE, "a restart cannot move a bound session");
  await fs.rm(root, { recursive: true, force: true });
});

test("a cold start rebuilds the same pair bytes", async () => {
  const root = await tempRoot();
  const live = new PairHint({ store: new JournalStore({ root, sessionId: "cold" }) });
  await live.bind({ ladder: { explicit: "SimplifiedChinese" } });
  live.setHandles(["b", "a"]);
  const history = [{ role: "user", content: "x" }];
  const before = await live.project({ messages: history });

  const rebuilt = await PairHint.open({ root, sessionId: "cold" });
  assert.equal(rebuilt.epoch, live.epoch);
  assert.deepEqual(rebuilt.handles, ["a", "b"]);
  assert.equal(rebuilt.fact.after, before.fact.after, "the placement is a stored fact");
  assert.equal(rebuilt.text, before.text, "the hint text is stored, not re-rendered");
  const after = await rebuilt.project({ messages: history });
  // A cold start that is asked for the very bytes it presented is a replay, not
  // a placement: the host rebuilt the same request and nothing changed.
  assert.equal(after.mode, INJECTION_MODES.REPLAYED);
  assert.equal(after.text, before.text);
  assert.deepEqual(after.messages, before.messages);
  await fs.rm(root, { recursive: true, force: true });
});

test("a cold start reproduces the stored hint even after the resources change", async () => {
  // The journal keeps the bytes of the epoch. A resource file rewritten between
  // two processes must not move them (§6.1 step 6).
  const root = await tempRoot();
  const live = new PairHint({ store: new JournalStore({ root, sessionId: "frozen" }) });
  await live.bind({ ladder: { explicit: "English" } });
  const before = await live.project({ messages: [{ role: "user", content: "x" }] });

  // A library whose books all differ: a rebuild that re-read resources would
  // show different bytes and would be refused as drift.
  const changed = {
    async loadAll() {
      return RESOURCE_IDS.map((id) => ({
        id,
        book: () => `# ${id}\n\nrewritten after the epoch began`,
      }));
    },
  };
  const restarted = await PairHint.open({ root, sessionId: "frozen", library: changed });
  const after = await restarted.project({ messages: [{ role: "user", content: "x" }] });
  // Same request, same frozen bytes: a replay. What this test pins is that the
  // bytes did NOT move to the rewritten resources.
  assert.equal(after.mode, INJECTION_MODES.REPLAYED);
  assert.equal(after.text, before.text);
  assert.deepEqual(after.messages, before.messages);

  // A new epoch does pick up the changed resources: only the epoch freezes.
  await restarted.newEpoch();
  const next = await restarted.project({ messages: [{ role: "user", content: "x" }] });
  assert.notEqual(next.text, before.text);
  assert.ok(next.text.includes("rewritten after the epoch began"));
  await fs.rm(root, { recursive: true, force: true });
});

test("a cold start replays a request that already carries the pair", async () => {
  const root = await tempRoot();
  const live = new PairHint({ store: new JournalStore({ root, sessionId: "replay" }) });
  await live.bind({ ladder: { explicit: "English" } });
  const before = await live.project({ messages: [{ role: "user", content: "x" }] });

  const retried = await PairHint.open({ root, sessionId: "replay" });
  const after = await retried.project({ messages: before.messages });
  assert.equal(after.mode, INJECTION_MODES.REPLAYED);
  assert.equal(after.messages.length, before.messages.length);
  assert.deepEqual(after.messages, before.messages);
  await fs.rm(root, { recursive: true, force: true });
});

test("the presented anchor survives a restart and catches a rewritten prefix", async () => {
  const root = await tempRoot();
  const live = new PairHint({ store: new JournalStore({ root, sessionId: "anchor" }) });
  await live.bind({ ladder: { explicit: "English" } });
  const first = await live.project({ messages: [{ role: "user", content: "x" }] });

  const restarted = await PairHint.open({ root, sessionId: "anchor" });
  assert.ok(
    restarted.chain.equals(Chain.fromMessages(first.messages, { pairIndex: first.fact.inserted })),
    "the chain resumes from digests, not a stored copy",
  );
  assert.equal(
    await asyncCode(
      restarted.project({
        messages: [
          { role: "user", content: "rewritten" },
          { role: "user", content: "x" },
        ],
      }),
    ),
    PAIR_HINT_CODES.PREFIX_DRIFT,
  );
  await fs.rm(root, { recursive: true, force: true });
});

test("an epoch record survives a restart", async () => {
  const root = await tempRoot();
  const live = new PairHint({ store: new JournalStore({ root, sessionId: "epoch" }) });
  await live.bind({ ladder: { explicit: "English" } });
  await live.project({ messages: [{ role: "user", content: "x" }] });
  await live.newEpoch();

  const restarted = await PairHint.open({ root, sessionId: "epoch" });
  assert.equal(restarted.epoch, 1);
  assert.equal(restarted.fact, null, "a new epoch has no rendered fact yet");
  assert.deepEqual(restarted.handles, []);
  assert.equal(restarted.text, null, "a new epoch has no frozen text yet");
  const second = await restarted.project({ messages: [{ role: "user", content: "x" }] });
  assert.equal(second.mode, INJECTION_MODES.PLACED);
  assert.equal(findInjection(second.messages).index, 1);
  await fs.rm(root, { recursive: true, force: true });
});

test("overlapping requests of one session are serialized, not raced into drift", async () => {
  const hint = new PairHint({ store: new MemoryJournal() });
  await hint.bind({ sessionId: "race", ladder: { explicit: "English" } });
  const base = [{ role: "user", content: "a" }];
  const [first, second] = await Promise.all([
    hint.project({ messages: base }),
    hint.project({ messages: [...base, { role: "user", content: "b" }] }),
  ]);
  assert.equal(first.mode, INJECTION_MODES.PLACED);
  assert.equal(second.mode, INJECTION_MODES.PLACED);
  assert.equal(findInjection(second.messages).index, 1);
  assert.doesNotThrow(() => hint.injector.assertPrefix({ previous: first.messages, next: second.messages }));
});

test("the journal cache never misses a record when appends overlap", async () => {
  const root = await tempRoot();
  const store = new JournalStore({ root, sessionId: "overlap" });
  await store.append({
    kind: RECORD_KINDS.BIND,
    binding: { sessionId: "overlap", language: "English", source: "env", ownerSessionId: null },
  });
  await Promise.all([
    store.append({ kind: RECORD_KINDS.EPOCH, epoch: 1 }),
    store.append({ kind: RECORD_KINDS.EPOCH, epoch: 2 }),
  ]);
  const reread = await new JournalStore({ root, sessionId: "overlap" }).read();
  assert.equal(reread.epoch, 2);
  assert.equal(reread.binding.language, LANGUAGES.ENGLISH);
  await fs.rm(root, { recursive: true, force: true });
});

test("a copy that carries the pair twice is refused, never repaired", async () => {
  const text = await renderFor(LANGUAGES.ENGLISH);
  const fact = factFor({ epoch: 0, text, handles: [] });
  const injector = new PairHintInjector();
  const once = injector.inject({ messages: [{ role: "user", content: "q" }], fact, text });
  const doubled = [...once.messages, ...once.messages.slice(-2)];
  assert.equal(
    codes(() => injector.inject({ messages: doubled, fact, text })),
    PAIR_HINT_CODES.PREFIX_DRIFT,
  );
});

test("a call id that is not ours cannot be read as an epoch", () => {
  assert.deepEqual(callIdParts("pair-hint:7:0123abcd"), { epoch: 7, digest: "0123abcd" });
  assert.equal(callIdParts("pair-hint:x:0123abcd"), null);
  assert.equal(callIdParts("pair-hint:7"), null);
  assert.equal(callIdParts(undefined), null);
});

test("the fold refuses a language change and a backward epoch", () => {
  const bound = foldRecords([
    { kind: RECORD_KINDS.BIND, binding: { sessionId: "s", language: "English", source: "env", ownerSessionId: null } },
  ]);
  assert.equal(
    codes(() =>
      bound.apply({
        kind: RECORD_KINDS.BIND,
        binding: { sessionId: "s", language: "SimplifiedChinese", source: "env", ownerSessionId: null },
      }),
    ),
    PAIR_HINT_CODES.FACT_CORRUPT,
  );
  const epoched = bound.apply({ kind: RECORD_KINDS.EPOCH, epoch: 4 });
  assert.equal(codes(() => epoched.apply({ kind: RECORD_KINDS.EPOCH, epoch: 3 })), PAIR_HINT_CODES.FACT_CORRUPT);
  const fact = factFor({ epoch: 5, text: "hint", handles: [] });
  assert.equal(
    codes(() =>
      epoched.apply({
        kind: RECORD_KINDS.PRESENTED,
        messages: [{ role: "user", content: "x" }],
        length: 1,
        fact: fact.toRecord(),
      }),
    ),
    PAIR_HINT_CODES.FACT_CORRUPT,
  );
});

test("the fold refuses a presented fact whose text does not match its digest", () => {
  const fact = factFor({ epoch: 0, text: "hint", handles: [], entries: ["budget"], freeze: true });
  const record = fact.toRecord();
  record.text = `${record.text} changed`;
  assert.equal(
    codes(() =>
      new PairHintState().apply({
        kind: RECORD_KINDS.PRESENTED,
        messages: [{ role: "user", content: "x" }],
        length: 1,
        fact: record,
      }),
    ),
    PAIR_HINT_CODES.FACT_CORRUPT,
  );
});

test("an unbound session refuses to render", async () => {
  const hint = new PairHint({ store: new MemoryJournal() });
  assert.equal(await asyncCode(hint.render()), PAIR_HINT_CODES.INVALID_LANGUAGE);
  assert.equal(await asyncCode(hint.project({ messages: [] })), PAIR_HINT_CODES.INVALID_LANGUAGE);
});

test("the journal tolerates a torn last line and rejects corruption elsewhere", async () => {
  const root = await tempRoot();
  const store = new JournalStore({ root, sessionId: "j" });
  await store.append({
    kind: RECORD_KINDS.BIND,
    binding: { sessionId: "j", language: "English", source: "env", ownerSessionId: null },
  });
  await fs.appendFile(store.file, '{"kind":"presented"');
  const reopened = await PairHint.open({ root, sessionId: "j" });
  assert.equal(reopened.language, LANGUAGES.ENGLISH);

  await fs.appendFile(store.file, "\n{\"kind\":\"presented\"\n{\"kind\":\"bind\",\"binding\":{}}\n");
  assert.equal(
    await asyncCode(PairHint.open({ root, sessionId: "j" })),
    PAIR_HINT_CODES.FACT_CORRUPT,
  );
  await fs.rm(root, { recursive: true, force: true });
});

test("the state fold is pure and the last record of a kind wins", () => {
  const empty = new PairHintState();
  const bound = empty.apply({
    kind: RECORD_KINDS.BIND,
    binding: { sessionId: "s", language: "English", source: "env", ownerSessionId: null },
  });
  assert.equal(empty.bound, false);
  assert.equal(bound.binding.language, LANGUAGES.ENGLISH);
  const text = "hint";
  const fact = factFor({ epoch: 0, text, handles: [], after: 1, inserted: 1, entries: ["budget"], freeze: true });
  const messages = [{ role: "user", content: "q" }, { role: "assistant", content: [] }];
  const rendered = foldRecords([
    { kind: RECORD_KINDS.BIND, binding: { sessionId: "s", language: "English", source: "env", ownerSessionId: null } },
    { kind: RECORD_KINDS.PRESENTED, messages, length: messages.length, fact: fact.toRecord() },
  ]);
  assert.equal(rendered.fact.digest, digestOf(text));
  assert.equal(rendered.fact.after, 1);
  assert.ok(rendered.chain.equals(Chain.fromMessages(messages)));
  const epoched = foldRecords([
    { kind: RECORD_KINDS.BIND, binding: { sessionId: "s", language: "English", source: "env", ownerSessionId: null } },
    { kind: RECORD_KINDS.PRESENTED, messages, length: messages.length, fact: fact.toRecord() },
    { kind: RECORD_KINDS.EPOCH, epoch: 3 },
  ]);
  assert.equal(epoched.epoch, 3);
  assert.equal(epoched.fact, null);
  assert.equal(epoched.chain, null);
  assert.equal(epoched.binding.language, LANGUAGES.ENGLISH);
});

test("the injector is idempotent on any request that already carries the pair", async () => {
  const text = await renderFor(LANGUAGES.ENGLISH);
  const fact = factFor({ epoch: 2, text, handles: ["h"] });
  const injector = new PairHintInjector();
  const once = injector.inject({ messages: [{ role: "user", content: "q" }], fact, text });
  const twice = injector.inject({ messages: once.messages, fact: once.fact, text });
  assert.equal(twice.mode, INJECTION_MODES.REPLAYED);
  assert.equal(twice.messages.length, once.messages.length);
  assert.equal(once.fact.after, 1, "the placement records where the pair went");
  assert.equal(twice.fact.after, 1, "the replay keeps that placement");
});

test("the prefix rule holds while the host keeps rebuilding its own request", async () => {
  // The real shape of a turn: the host keeps its own history, which never
  // contains our pair, and hands a fresh copy in every time.
  const hint = new PairHint({ store: new MemoryJournal() });
  await hint.bind({ sessionId: "host", ladder: { explicit: "English" } });

  const hostHistory = [{ role: "user", content: "start" }];
  const requests = [];
  for (const turn of ["one", "two", "three"]) {
    hostHistory.push({ role: "assistant", content: [{ type: "text", text: turn }] });
    hostHistory.push({ role: "user", content: `turn ${turn}` });
    requests.push(await hint.project({ messages: [...hostHistory] }));
  }

  assert.deepEqual(
    requests.map((request) => request.mode),
    [INJECTION_MODES.PLACED, INJECTION_MODES.PLACED, INJECTION_MODES.PLACED],
  );
  // The first request decides the placement (its own end, after three messages),
  // and every later request keeps the pair there instead of pushing it to its
  // own end. That is what makes each request start with the previous one.
  assert.deepEqual(
    requests.map((request) => findInjection(request.messages).index),
    [3, 3, 3],
  );
  assert.deepEqual(
    requests.map((request) => request.fact.after),
    [3, 3, 3],
  );
  for (let index = 1; index < requests.length; index += 1) {
    assert.doesNotThrow(() =>
      hint.injector.assertPrefix({
        previous: requests[index - 1].messages,
        next: requests[index].messages,
      }),
    );
  }
});

test("a cold start keeps the same placement for the next request", async () => {
  const root = await tempRoot();
  const live = new PairHint({ store: new JournalStore({ root, sessionId: "keep" }) });
  await live.bind({ ladder: { explicit: "English" } });
  const first = await live.project({
    messages: [
      { role: "user", content: "a" },
      { role: "assistant", content: [{ type: "text", text: "b" }] },
    ],
  });
  assert.equal(first.fact.after, 2);

  const restarted = await PairHint.open({ root, sessionId: "keep" });
  const next = await restarted.project({
    messages: [
      { role: "user", content: "a" },
      { role: "assistant", content: [{ type: "text", text: "b" }] },
      { role: "user", content: "c" },
    ],
  });
  assert.equal(next.fact.after, 2);
  assert.equal(findInjection(next.messages).index, 2);
  assert.doesNotThrow(() =>
    restarted.injector.assertPrefix({ previous: first.messages, next: next.messages }),
  );
  await fs.rm(root, { recursive: true, force: true });
});

test("a replay at the same placement that rewrites a presented message is refused", async () => {
  const hint = new PairHint({ store: new MemoryJournal() });
  await hint.bind({ sessionId: "replay-drift", ladder: { explicit: "English" } });
  const first = await hint.project({
    messages: [
      { role: "user", content: "a" },
      { role: "assistant", content: [{ type: "text", text: "b" }] },
    ],
  });
  assert.equal(first.fact.after, 2);

  const tampered = [...first.messages];
  tampered[0] = { role: "user", content: "a rewritten" };
  const injectorSays = hint.injector.inject({
    messages: tampered,
    fact: first.fact,
    text: first.text,
  });
  assert.equal(injectorSays.mode, INJECTION_MODES.REPLAYED, "the pair itself did not change");
  assert.equal(findInjection(injectorSays.messages).index, 2, "and it stayed where it was");

  assert.equal(
    await asyncCode(hint.project({ messages: tampered })),
    PAIR_HINT_CODES.PREFIX_DRIFT,
    "the request path must still refuse it",
  );
});

test("a replay that drops a presented message is refused", async () => {
  const hint = new PairHint({ store: new MemoryJournal() });
  await hint.bind({ sessionId: "replay-short", ladder: { explicit: "English" } });
  const first = await hint.project({
    messages: [
      { role: "user", content: "a" },
      { role: "assistant", content: [{ type: "text", text: "b" }] },
    ],
  });
  const shortened = [first.messages[1], first.messages[2]];
  assert.equal(
    await asyncCode(hint.project({ messages: shortened })),
    PAIR_HINT_CODES.PREFIX_DRIFT,
  );
});

test("an unchanged replay still passes the prefix check", async () => {
  const hint = new PairHint({ store: new MemoryJournal() });
  await hint.bind({ sessionId: "replay-ok", ladder: { explicit: "English" } });
  const first = await hint.project({ messages: [{ role: "user", content: "a" }] });
  const again = await hint.project({ messages: first.messages });
  assert.equal(again.mode, INJECTION_MODES.REPLAYED);
  assert.deepEqual(again.messages, first.messages);
});

// ---------------------------------------------------------------------------
// Property tests: the prefix chain under the shapes a host really produces
// (§6.1 step 4, §8.2 property-based testing)
// ---------------------------------------------------------------------------

// One host message, as a plain object. Only the shape matters: the chain is
// compared message for message, never by content.
const hostMessage = (index, text) =>
  index % 2 === 0
    ? { role: "user", content: text }
    : { role: "assistant", content: [{ type: "text", text }] };

// A host history of `length` messages, then `extra` more appended: the request
// copy the host rebuilds for the next turn.
const history = (length, extra = 0) =>
  Array.from({ length: length + extra }, (_, index) => hostMessage(index, `m${index}`));

test("any number of turns keeps one pair at one placement and one chain", async () => {
  await fc.assert(
    fc.asyncProperty(fc.array(fc.nat({ max: 3 }), { minLength: 1, maxLength: 6 }), async (grows) => {
      const hint = new PairHint({ store: new MemoryJournal() });
      await hint.bind({ sessionId: "turns", ladder: { explicit: "English" } });

      let length = 1;
      let previous = null;
      let placed = null;
      for (const grow of grows) {
        length += grow;
        // The host rebuilds its own copy and never stores our pair: every
        // request is the history alone, and the hint places or replays.
        const request = await hint.project({ messages: history(length) });
        const injection = findInjection(request.messages);
        assert.ok(injection !== null, "every request carries the pair");
        // The first request is placed at its own end; later requests replay it
        // there, and the placement never moves with the growing history.
        if (placed === null) placed = injection.index;
        assert.equal(injection.index, placed, "the first placement decides the count");
        if (previous !== null) {
          assert.doesNotThrow(() => hint.injector.assertPrefix({ previous, next: request.messages }));
        }
        previous = request.messages;
      }
    }),
    { ...fcRuns(40) },
  );
});

test("a copy that already starts with the presented request is never drift", async () => {
  await fc.assert(
    fc.asyncProperty(
      fc.nat({ max: 6 }),
      fc.nat({ max: 4 }),
      fc.boolean(),
      async (length, extra, withPair) => {
        const hint = new PairHint({ store: new MemoryJournal() });
        await hint.bind({ sessionId: "extend", ladder: { explicit: "English" } });
        const first = await hint.project({ messages: history(length) });

        // The host handed back the request it sent, optionally with the pair
        // still in it and optionally grown by later messages.
        const next = withPair
          ? [...first.messages, ...history(length, extra).slice(length)]
          : history(length + extra);
        const projected = await hint.project({ messages: next });

        assert.doesNotThrow(() =>
          hint.injector.assertPrefix({ previous: first.messages, next: projected.messages }),
        );
        const injection = findInjection(projected.messages);
        assert.ok(injection !== null);
        // Whatever the copy showed is now the placement, so the next copy
        // without the pair lands between the same two messages.
        const bare = await hint.project({ messages: history(length + extra + 1) });
        assert.equal(findInjection(bare.messages).index, injection.index);
      },
    ),
    { ...fcRuns(40) },
  );
});

test("property: any request sequence keeps a digest chain, and a rewrite reports its first index", async () => {
  await fc.assert(
    fc.asyncProperty(
      fc.integer({ min: 1, max: 8 }),
      fc.integer({ min: 0, max: 7 }),
      async (length, rawIndex) => {
        const index = rawIndex % length;
        const hint = new PairHint({ store: new MemoryJournal() });
        await hint.bind({ sessionId: "drift-index", ladder: { explicit: "English" } });
        const request = history(length);
        await hint.project({ messages: request });

        // The chain is exactly the pair-free request's digests plus this
        // epoch's pair; no copy is kept.
        assert.equal(hint.chain.length, length + 2);
        assert.equal(hint.chain.pairIndex, length);
        assert.ok(hint.chain.withoutPair().equals(Chain.fromMessages(request)));

        const tampered = request.map((message, at) =>
          at === index ? { ...message, content: `rewritten-${index}` } : message,
        );
        const error = await hint.project({ messages: tampered }).then(
          () => assert.fail("expected a refusal"),
          (caught) => caught,
        );
        assert.equal(error.code, PAIR_HINT_CODES.PREFIX_DRIFT);
        assert.equal(error.details.index, index, "the first differing index is exact");
      },
    ),
    { ...fcRuns(40) },
  );
});

test("property: a cold fold rebuilds the same chain and verifies the same prefix", async () => {
  await fc.assert(
    fc.asyncProperty(fc.array(fc.string({ maxLength: 12 }), { minLength: 1, maxLength: 8 }), async (texts) => {
      const journal = new MemoryJournal();
      const hint = new PairHint({ store: journal });
      await hint.bind({ sessionId: "cold-fold", ladder: { explicit: "English" } });
      const request = texts.map((text, index) => hostMessage(index, text));
      await hint.project({ messages: request });

      const folded = foldRecords(journal.records);
      assert.ok(folded.chain.equals(hint.chain), "the fold rebuilds the chain from digests");
      assert.equal(folded.fact.text, hint.text, "the frozen presentation comes back");

      const cold = new PairHint({ store: new MemoryJournal(journal.records) });
      await cold.load();
      const replay = await cold.project({ messages: request });
      assert.ok(findInjection(replay.messages) !== null, "the cold hint can still place the pair");
      const tampered = request.map((message, index) =>
        index === 0 ? { ...message, content: "rewritten" } : message,
      );
      assert.equal(
        await asyncCode(cold.project({ messages: tampered })),
        PAIR_HINT_CODES.PREFIX_DRIFT,
      );
    }),
    { ...fcRuns(30) },
  );
});

test("property: new records carry digests, and an old-shape record folds to the same chain", async () => {
  await fc.assert(
    fc.asyncProperty(fc.array(fc.string({ maxLength: 10 }), { minLength: 1, maxLength: 6 }), async (texts) => {
      const journal = new MemoryJournal();
      const hint = new PairHint({ store: journal });
      await hint.bind({ sessionId: "shape", ladder: { explicit: "English" } });
      const request = texts.map((text, index) => hostMessage(index, text));
      await hint.project({ messages: request });

      const records = journal.records;
      const presented = records.filter((record) => record.kind === RECORD_KINDS.PRESENTED);
      assert.equal(presented.length, 1);
      assert.equal(presented[0].messages, undefined, "no full copy is written");
      assert.equal(presented[0].digests.length, presented[0].length);
      assert.match(presented[0].digest, /^[0-9a-f]{64}$/);
      assert.equal(
        records.filter((record) => record.kind === RECORD_KINDS.PRESENTATION).length,
        1,
        "the hint text is stored once",
      );
      assert.equal(
        records.some((record) => record.fact !== undefined && record.fact.text !== undefined),
        false,
        "no presented fact repeats the hint text",
      );

      // The same chain, written by the old version as a full copy.
      const fact = factFor({
        epoch: 0,
        text: hint.text,
        handles: [],
        after: request.length,
        inserted: request.length,
        entries: [...RESOURCE_IDS],
        freeze: true,
      });
      const oldMessages = [...request, ...syntheticMessages({ fact, text: hint.text })];
      const old = foldRecords([
        { kind: RECORD_KINDS.BIND, binding: hint.binding.toRecord() },
        { kind: RECORD_KINDS.PRESENTED, messages: oldMessages, length: oldMessages.length, fact: fact.toRecord() },
      ]);
      assert.ok(
        old.chain.equals(Chain.fromMessages(oldMessages, { pairIndex: request.length })),
        "the old shape folds to the same chain",
      );
      assert.equal(old.fact.text, hint.text);
    }),
    { ...fcRuns(30) },
  );
});

test("a changed handle list opens a new epoch, an unchanged one does not", async () => {
  await fc.assert(
    fc.asyncProperty(fc.array(fc.stringMatching(/^[a-z][a-z0-9-]{0,5}$/), { maxLength: 4 }), async (handles) => {
      const hint = new PairHint({ store: new MemoryJournal() });
      await hint.bind({ sessionId: "handles", ladder: { explicit: "English" } });
      const first = await hint.project({ messages: history(1) });
      const startEpoch = hint.epoch;

      // Same list again: the epoch and its bytes stay. The copy is the one the
      // first request presented, so the pair replays where it stands.
      hint.setHandles([...handles]);
      const same = await hint.project({ messages: first.messages });
      assert.equal(hint.epoch, startEpoch);
      assert.equal(same.text, first.text);

      // A different list needs a new epoch before it can be shown.
      const other = [...handles, "late-park"];
      const changed = [...hint.handles].sort().join("\n") !== [...other].sort().join("\n");
      if (changed) {
        await hint.newEpoch();
        hint.setHandles(other);
        const shown = await hint.project({ messages: first.messages });
        assert.ok(shown.text.includes("- late-park"), "the new epoch lists the new handle");
        assert.equal(shown.mode, INJECTION_MODES.PLACED);
      }
    }),
    { ...fcRuns(30) },
  );
});

// --------------------------------------------------- epoch and prefix chains

test("an epoch change keeps the last request of the old epoch as a prefix", async () => {
  const hint = new PairHint({ store: new MemoryJournal() });
  await hint.bind({ sessionId: "epoch-prefix", ladder: { explicit: "English" } });
  const first = await hint.project({ messages: history(2) });
  const second = await hint.project({ messages: history(2, 1) });
  assert.doesNotThrow(() =>
    hint.injector.assertPrefix({ previous: first.messages, next: second.messages }),
  );

  // The handle list changed, so the host opens an epoch before the next request.
  hint.setHandles(["parked-program"]);
  await hint.newEpoch();
  // The first request of the new epoch is the SAME request as the last one of
  // the old epoch, except for the pair: the epoch changes the pair's bytes,
  // never its place. The messages before the pair are byte-identical, which is
  // the prefix §6.1 asks for (a new epoch is the one legal point where the pair
  // itself changes; §6.1 forbids rewriting shown bytes *inside* an epoch).
  const third = await hint.project({ messages: history(2, 1) });
  assert.equal(third.mode, INJECTION_MODES.PLACED);
  assert.ok(third.text.includes("- parked-program"));
  assert.equal(findInjection(third.messages).index, 2, "the new pair takes the old pair's place");
  assert.deepEqual(third.messages.slice(0, 2), second.messages.slice(0, 2));
  assert.equal(third.messages.length, second.messages.length);
  // The next request of the new epoch grows the copy and extends this one.
  const fourth = await hint.project({ messages: history(2, 2) });
  assert.equal(findInjection(fourth.messages).index, 2);
  assert.doesNotThrow(() =>
    hint.injector.assertPrefix({ previous: third.messages, next: fourth.messages }),
  );
});

test("a chain anchored on a successor's copy keeps the host's copy and the pair's place", async () => {
  const hint = new PairHint({ store: new MemoryJournal() });
  await hint.bind({ sessionId: "anchor-pair", ladder: { explicit: "English" } });
  const before = await hint.project({ messages: history(3) });

  // The relay barrier replaces the copy: the successor's first request starts
  // with the operator's words instead of the retired conversation.
  const fenced = [{ role: "user", content: "successor input" }];
  hint.anchor(fenced);
  assert.deepEqual(hint.handles, [], "the anchor changes the chain, not the facts");
  assert.equal(hint.epoch, before.fact.epoch, "the anchor keeps the epoch");
  // The anchor is the copy with this epoch's pair after it: the prefix rule
  // compares two requests that BOTH carry the pair, so the anchor must carry it
  // too, and the placement is decided here (`after = fenced.length`).
  assert.equal(hint.chain.length, fenced.length + 2, "the anchor is the copy plus this epoch's pair");

  // The successor's first request must extend the anchored copy.
  const opened = await hint.project({ messages: [...fenced] });
  assert.doesNotThrow(() =>
    hint.injector.assertPrefix({ previous: hint.chain, next: opened.messages }),
  );
  assert.equal(findInjection(opened.messages).index, 1);

  // And so must every later one. The host stores its own copy of that request —
  // without our pair — and appends the successor's answer; the next request is
  // that copy, and the pair must go after it, exactly once.
  const stored = [...fenced, hostMessage(1, "the successor's answer")];
  const later = await hint.project({ messages: stored });
  // The pair keeps the place the anchor decided — after the copy the anchor was
  // given — because that place is where the model already read it. The host's
  // later messages follow the pair, exactly as in every other request of the
  // epoch (§6.1: the placement is a count, and it does not walk to the end).
  assert.equal(findInjection(later.messages).index, fenced.length, "the pair stays where the chain put it");
  assert.equal(later.messages.length, stored.length + 2, "exactly one pair, no second copy of it");
  assert.doesNotThrow(() =>
    hint.injector.assertPrefix({ previous: opened.messages, next: later.messages }),
  );
});

test("an anchor before the epoch's first request leaves the chain to that request", async () => {
  const hint = new PairHint({ store: new MemoryJournal() });
  await hint.bind({ sessionId: "anchor-cold", ladder: { explicit: "English" } });
  hint.anchor([{ role: "user", content: "only input" }]);
  assert.equal(hint.chain, null);
  const placed = await hint.project({ messages: [{ role: "user", content: "only input" }] });
  assert.equal(placed.mode, INJECTION_MODES.PLACED);
  assert.equal(findInjection(placed.messages).index, 1);
});

test("an anchored copy starts a chain the next request continues", async () => {
  // The caller anchors because a fact the host owns replaced the request copy.
  // The request after the anchor is the first of a new chain, and the one after
  // that must extend it.
  const hint = new PairHint({ store: new MemoryJournal() });
  await hint.bind({ sessionId: "anchor-opens", ladder: { explicit: "English" } });
  const retired = [
    { role: "user", content: "retired one" },
    { role: "assistant", content: "retired two" },
    { role: "user", content: "retired three" },
  ];
  await hint.project({ messages: retired });

  // The barrier's copy: the notice and the operator's words.
  const fenced = [
    { role: "user", content: "you are the successor" },
    { role: "user", content: "operator words" },
  ];
  hint.anchor(fenced);
  const opened = await hint.project({ messages: [...fenced] });
  assert.equal(opened.mode, INJECTION_MODES.PLACED, "the request after the anchor is sent");
  assert.equal(findInjection(opened.messages).index, fenced.length, "the pair follows the anchored copy");

  // The chain is live from here: the host grows its own copy and the next
  // request must extend the one the model just read.
  const grown = [...fenced, { role: "assistant", content: "answer" }];
  const next = await hint.project({ messages: grown });
  assert.doesNotThrow(() =>
    hint.injector.assertPrefix({ previous: opened.messages, next: next.messages }),
  );

  // The retired conversation does not come back into the chain: a copy that
  // still carries it is not an extension of what the successor read.
  await assert.rejects(
    hint.project({ messages: [...retired, ...grown] }),
    (error) => error.code === PAIR_HINT_CODES.PREFIX_DRIFT,
  );
});

test("the chain after an anchor is held to the anchored bytes", async () => {
  // Opening the chain is a one-time break, not a permanent exemption: the
  // request AFTER the anchored one is compared with what the model already
  // read. That comparison is also why the anchor must take the same message
  // shape the projection uses — a raw host copy compared with a normalized one
  // drifts on a conversation nobody changed (the OMP adapter measured that at
  // index 0, 2026-11-01).
  const raw = [{ role: "user", content: "successor input" }];
  const normalized = raw.map((message) => ({ ...message, content: [{ type: "text", text: message.content }] }));

  const shaped = new PairHint({ store: new MemoryJournal() });
  await shaped.bind({ sessionId: "anchor-shape", ladder: { explicit: "English" } });
  shaped.anchor(normalized);
  await shaped.project({ messages: normalized });
  const grown = [...normalized, { role: "assistant", content: [{ type: "text", text: "answer" }] }];
  const next = await shaped.project({ messages: grown });
  assert.doesNotThrow(() =>
    shaped.injector.assertPrefix({ previous: shaped.chain, next: next.messages }),
  );

  // Same conversation, one message rewritten: still drift.
  await assert.rejects(
    shaped.project({
      messages: [normalized[0], { role: "assistant", content: [{ type: "text", text: "rewritten" }] }],
    }),
    (error) => error.code === PAIR_HINT_CODES.PREFIX_DRIFT,
  );
});

test("any number of epochs keeps every request an extension of the one before", async () => {
  await fc.assert(
    fc.asyncProperty(
      // `0` grows the copy (a later turn inside one epoch), `1` opens a new
      // epoch on the same copy, `2` does both. Opening an epoch without growing
      // is the shape the three hosts produce (a changed handle list at a turn
      // boundary).
      //
      // The invariant is the one §6.1 states: inside one epoch every request
      // extends the one before it, byte for byte. Across an epoch boundary the
      // PAIR changes by design, so the invariant there is weaker and exact: the
      // messages before the pair are unchanged and the pair keeps its place.
      fc.array(fc.integer({ min: 0, max: 2 }), { minLength: 1, maxLength: 8 }),
      async (steps) => {
        const hint = new PairHint({ store: new MemoryJournal() });
        await hint.bind({ sessionId: "epoch-chain", ladder: { explicit: "English" } });
        let length = 1;
        let previous = await hint.project({ messages: history(length) });
        for (let index = 0; index < steps.length; index += 1) {
          const step = steps[index];
          const opensEpoch = step !== 0;
          if (opensEpoch) {
            hint.setHandles([`park-${index}`]);
            await hint.newEpoch();
          }
          if (step !== 1) length += 1;
          const next = await hint.project({ messages: history(length) });
          if (opensEpoch) {
            const before = findInjection(previous.messages).index;
            const now = findInjection(next.messages).index;
            assert.equal(now, before, `step ${index}: the new pair keeps the old pair's place`);
            assert.deepEqual(
              next.messages.slice(0, now),
              previous.messages.slice(0, now),
              `step ${index}: the messages before the pair are unchanged`,
            );
          } else {
            assert.doesNotThrow(
              () =>
                hint.injector.assertPrefix({ previous: previous.messages, next: next.messages }),
              `step ${index} (${step}) broke the chain`,
            );
          }
          previous = next;
        }
      },
    ),
    { ...fcRuns(60) },
  );
});

test("the placement a new epoch opens with survives a restart", async () => {
  const root = await tempRoot();
  const live = new PairHint({ store: new JournalStore({ root, sessionId: "epoch-restart" }) });
  await live.bind({ ladder: { explicit: "English" } });
  const first = await live.project({ messages: history(2) });
  await live.newEpoch();

  const restarted = await PairHint.open({ root, sessionId: "epoch-restart" });
  const next = await restarted.project({ messages: history(2) });
  // The epoch record clears the stored fact; the frozen presentation is what
  // says where the old pair stood, so a restarted epoch opens at the same place
  // instead of pushing the pair behind messages the old one preceded.
  assert.equal(findInjection(next.messages).index, 2);
  assert.deepEqual(next.messages.slice(0, 2), first.messages.slice(0, 2));
  await fs.rm(root, { recursive: true, force: true });
});

// A refusal used to leave only a sentence in the host's log. These tests pin
// what the journal must hold after one: enough to find the session, the epoch,
// the first differing index and the sizes, and nothing that could reconstruct
// the conversation.
const refusingPairHint = async (root, sessionId = "refused") => {
  const hint = new PairHint({ store: new JournalStore({ root, sessionId }) });
  await hint.bind({ ladder: { explicit: "English" } });
  return hint;
};

const journalRecords = async (root, sessionId = "refused") =>
  (await fs.readFile(new JournalStore({ root, sessionId }).file, "utf8"))
    .split("\n")
    .filter((line) => line.trim() !== "")
    .map((line) => JSON.parse(line));

test("a refused request writes one diagnostic record and raises the same error", async () => {
  const root = await tempRoot();
  const hint = await refusingPairHint(root);
  const first = await hint.project({ messages: history(3) });
  const presented = first.messages;

  // A host that rebuilds its own request and rewrites one message it already
  // sent: the prefix rule must refuse, and the refusal must be recorded.
  const rewritten = presented.map((message, index) =>
    index === 2 ? { ...message, content: "rewritten by the host" } : message,
  );
  const error = await hint.project({ messages: rewritten }).then(
    () => assert.fail("expected a refusal"),
    (caught) => caught,
  );
  assert.equal(error.code, PAIR_HINT_CODES.PREFIX_DRIFT);

  const records = await journalRecords(root);
  const refusals = records.filter((record) => record.kind === RECORD_KINDS.REFUSED);
  assert.equal(refusals.length, 1, "one refusal, one record");
  const record = refusals[0];
  assert.equal(record.code, PAIR_HINT_CODES.PREFIX_DRIFT);
  assert.equal(record.epoch, 0);
  assert.equal(record.index, 2, "the first message the rule disagreed on");
  assert.equal(record.previous.count, 3);
  assert.equal(record.next.count, 3);
  // The chain side has no copy, so it names the message by digest alone. The
  // request side still carries the full shape it was handed.
  assert.deepEqual(Object.keys(record.previous.shapes[2]), ["digest"]);
  assert.equal(record.next.shapes[2].role, presented[2].role);
  assert.ok(Array.isArray(record.next.shapes[2].parts));
  assert.notEqual(record.previous.shapes[2].digest, record.next.shapes[2].digest);
  // The recorded length and digest are over the SAME text the prefix rule
  // compared — the canonical identity of the message (canonical.mjs), not the
  // host's raw object. That is what lets a reader check a refusal against a
  // later request line: equal digests mean the same conversation, and a
  // differing digest at index N is the drift.
  assert.equal(
    record.next.shapes[2].length,
    canonicalKey(rewritten[2]).length,
    "the recorded length is the compared text's length",
  );
  assert.equal(record.previous.shapes[2].digest, digestOf(canonicalKey(presented[2])));
  assert.equal(record.next.shapes[2].digest, digestOf(canonicalKey(rewritten[2])));
  // The unchanged messages agree digest for digest: a reader can tell "one
  // message changed" from "the conversation is another one".
  for (const index of [0, 1]) {
    assert.equal(record.previous.shapes[index].digest, record.next.shapes[index].digest);
  }
  await fs.rm(root, { recursive: true, force: true });
});

test("a refusal record never carries the conversation, its tool I/O or its text", async () => {
  const root = await tempRoot();
  const hint = await refusingPairHint(root, "secrets");
  const secret = "sk-live-DO-NOT-RECORD-0123456789";
  const operatorText = "the operator's private words about the incident";
  const first = await hint.project({ messages: history(2) });
  // The request now carries user prose, a tool input and a tool output, all
  // secret. One of the presented messages is rewritten so the refusal fires.
  const withSecrets = [
    { role: "user", content: [{ type: "text", text: operatorText }] },
    ...first.messages.slice(1).map((message, index) =>
      index === 0
        ? {
            ...message,
            content: [
              {
                type: "tool-call",
                toolName: "js-worker",
                toolCallId: "c1",
                input: { apiKey: secret, prompt: operatorText },
              },
            ],
          }
        : message,
    ),
    {
      role: "tool",
      content: [
        {
          type: "tool-result",
          toolName: "js-worker",
          toolCallId: "c1",
          output: { type: "text", value: secret },
        },
      ],
    },
  ];
  await hint.project({ messages: withSecrets }).catch(() => {});

  const raw = await fs.readFile(new JournalStore({ root, sessionId: "secrets" }).file, "utf8");
  assert.equal(raw.includes(secret), false, "no tool input or output value reaches the journal");
  assert.equal(raw.includes(operatorText), false, "no message text reaches the journal");
  assert.equal(raw.includes("private words"), false);

  const refusal = (await journalRecords(root, "secrets")).filter((record) => record.kind === RECORD_KINDS.REFUSED).at(-1);
  assert.ok(refusal, "the refusal was recorded");
  assert.deepEqual(
    Object.keys(refusal).sort(),
    ["code", "epoch", "index", "kind", "message", "next", "previous"],
    "the record has one fixed shape, not a copy of the request",
  );
  for (const side of [refusal.previous, refusal.next]) {
    for (const shape of side.shapes) {
      const keys = Object.keys(shape).sort();
      assert.ok(
        (keys.length === 1 && keys[0] === "digest") ||
          keys.join(",") === "calls,digest,length,parts,results,role",
        "a shape is either a digest alone or the full message shape",
      );
      assert.match(shape.digest, /^[0-9a-f]{64}$/);
      for (const part of shape.parts ?? []) assert.match(part, /^[a-z-]+$/);
      // The ids are HANDLES, not content: the secret is the value the call
      // carried and the value the result returned, and neither is an id.
      for (const id of [...(shape.calls ?? []), ...(shape.results ?? [])]) assert.match(id, /^[A-Za-z0-9_.:-]+$/);
    }
  }
  await fs.rm(root, { recursive: true, force: true });
});

test("a cold start reads a refusal record without moving the chain", async () => {
  const root = await tempRoot();
  const hint = await refusingPairHint(root, "cold-refusal");
  const first = await hint.project({ messages: history(3) });
  const rewritten = first.messages.map((message, index) =>
    index === 1 ? { ...message, content: "changed" } : message,
  );
  await hint.project({ messages: rewritten }).catch(() => {});

  // A fresh process folds the journal: the refusal is readable, the chain and
  // the placement are exactly what the last successful request left.
  const reopened = await PairHint.open({ root, sessionId: "cold-refusal" });
  assert.equal(reopened.refusal?.code, PAIR_HINT_CODES.PREFIX_DRIFT);
  assert.equal(reopened.refusal?.index, 1);
  assert.equal(reopened.epoch, hint.epoch);
  assert.ok(
    reopened.chain.equals(Chain.fromMessages(first.messages, { pairIndex: first.fact.inserted })),
    "the last presented chain is the anchor",
  );
  assert.equal(reopened.fact.after, first.fact.after, "the placement did not move");

  // And the next request that extends the anchor still goes through: a
  // refusal is a record, not a poisoned journal.
  const next = await reopened.project({ messages: [...first.messages, { role: "user", content: "next" }] });
  assert.ok(next.messages.length > first.messages.length);
  await fs.rm(root, { recursive: true, force: true });
});

test("a malformed refusal record is corruption, not a silent skip", async () => {
  const root = await tempRoot();
  const store = new JournalStore({ root, sessionId: "bad-refusal" });
  await store.append({
    kind: RECORD_KINDS.BIND,
    binding: { sessionId: "bad-refusal", language: "English", source: "env", ownerSessionId: null },
  });
  for (const bad of [
    { kind: RECORD_KINDS.REFUSED, code: "", epoch: 0, index: null, previous: null, next: null },
    { kind: RECORD_KINDS.REFUSED, code: "PREFIX_DRIFT", epoch: -1, index: null, previous: null, next: null },
    { kind: RECORD_KINDS.REFUSED, code: "PREFIX_DRIFT", epoch: 0, index: 1.5, previous: null, next: null },
    {
      kind: RECORD_KINDS.REFUSED,
      code: "PREFIX_DRIFT",
      epoch: 0,
      index: 0,
      previous: { count: 1, shapes: [{ role: "user", parts: ["text"], length: 2, digest: "not-a-digest" }] },
      next: null,
    },
  ]) {
    assert.throws(() => new PairHintState().apply(bad), (error) => error.code === PAIR_HINT_CODES.FACT_CORRUPT);
  }
  // A well-formed record with unknown extra fields is still readable.
  const state = new PairHintState().apply({
    kind: RECORD_KINDS.REFUSED,
    code: "PREFIX_DRIFT",
    epoch: 3,
    index: null,
    previous: null,
    next: null,
    futureField: "ignored",
  });
  assert.equal(state.refusal.code, "PREFIX_DRIFT");
  assert.equal(state.refusal.epoch, 3);
  await fs.rm(root, { recursive: true, force: true });
});

test("the refusal record caps how many message shapes it keeps", async () => {
  const root = await tempRoot();
  const hint = await refusingPairHint(root, "long-refusal");
  const messages = Array.from({ length: 200 }, (_, index) => ({ role: "user", content: `m${index}` }));
  await hint.project({ messages });
  const rewritten = messages.map((message, index) => (index === 0 ? { ...message, content: "x" } : message));
  await hint.project({ messages: rewritten }).catch(() => {});
  const refusal = (await journalRecords(root, "long-refusal")).filter((record) => record.kind === RECORD_KINDS.REFUSED).at(-1);
  assert.equal(refusal.previous.count, 200, "the true length is kept");
  assert.equal(refusal.previous.shapes.length, REFUSAL_SHAPE_LIMIT, "the shapes are capped");
  await fs.rm(root, { recursive: true, force: true });
});

// A `presented` record used to store the whole request copy, and every request
// re-wrote it: 33 requests of a 67-message session produced 34 MB, and the
// repository's journals reached 133 MB. New records carry digests, and the hint
// text lives once per epoch/digest; compaction also migrates an old journal.
test("compaction keeps no request copy and stores the hint text once", async () => {
  const root = await tempRoot();
  const previousBudget = JournalStore.COMPACT_BYTES;
  JournalStore.COMPACT_BYTES = 4096;
  try {
    const store = new JournalStore({ root, sessionId: "compact" });
    const hint = new PairHint({ store });
    await hint.bind({ ladder: { explicit: "English" } });
    const body = { role: "user", content: "x".repeat(2000) };
    const history = (length) => Array.from({ length }, () => ({ ...body }));
    let last = null;
    for (let length = 1; length <= 10; length += 1) last = await hint.project({ messages: history(length) });

    const records = await journalRecords(root, "compact");
    const presented = records.filter((record) => record.kind === RECORD_KINDS.PRESENTED);
    assert.equal(presented.length, 10, "no request is lost from the fold");
    assert.equal(
      presented.filter((record) => Array.isArray(record.messages)).length,
      0,
      "no request copy is stored",
    );
    assert.equal(presented.at(-1).length, last.messages.length);
    assert.equal(presented.at(-1).digests.length, presented.at(-1).length);
    assert.match(presented.at(-1).digest, /^[0-9a-f]{64}$/);
    const presentations = records.filter((record) => record.kind === RECORD_KINDS.PRESENTATION);
    assert.equal(presentations.length, 1, "one presentation for the epoch/digest");
    assert.equal(presentations[0].text, hint.text);
    assert.equal(
      presented.every((record) => record.fact === undefined || record.fact.text === undefined),
      true,
      "no fact repeats the frozen text",
    );

    // A cold start folds to exactly the state the live hint held.
    const reopened = await PairHint.open({ root, sessionId: "compact" });
    assert.ok(reopened.chain.equals(hint.chain), "the chain resumes from its digests");
    assert.equal(reopened.fact.after, hint.fact.after, "the placement survives compaction");
    assert.equal(reopened.text, hint.text, "the frozen presentation survives compaction");
    // And the chain continues: the next request extends what was presented.
    const next = await reopened.project({ messages: history(11) });
    assert.doesNotThrow(() => reopened.injector.assertPrefix({ previous: hint.chain, next: next.messages }));
  } finally {
    JournalStore.COMPACT_BYTES = previousBudget;
    await fs.rm(root, { recursive: true, force: true });
  }
});

test("compaction migrates an old journal's copies and hoists its text", async () => {
  const root = await tempRoot();
  const previousBudget = JournalStore.COMPACT_BYTES;
  JournalStore.COMPACT_BYTES = 1;
  try {
    const store = new JournalStore({ root, sessionId: "old-shape" });
    const text = await renderFor(LANGUAGES.ENGLISH);
    const firstFact = factFor({
      epoch: 0,
      text,
      handles: [],
      after: 1,
      inserted: 1,
      entries: [...RESOURCE_IDS],
      freeze: true,
    });
    const first = [{ role: "user", content: "a" }, ...syntheticMessages({ fact: firstFact, text })];
    const secondFact = factFor({
      epoch: 0,
      text,
      handles: [],
      after: 2,
      inserted: 2,
      entries: [...RESOURCE_IDS],
      freeze: true,
    });
    const second = [
      { role: "user", content: "a" },
      { role: "assistant", content: [{ type: "text", text: "b" }] },
      ...syntheticMessages({ fact: secondFact, text }),
    ];
    await store.append({
      kind: RECORD_KINDS.BIND,
      binding: { sessionId: "old-shape", language: "English", source: "env", ownerSessionId: null },
    });
    await store.append({ kind: RECORD_KINDS.PRESENTED, messages: first, length: first.length, fact: firstFact.toRecord() });
    // The append that crosses the threshold runs compaction.
    await store.append({ kind: RECORD_KINDS.PRESENTED, messages: second, length: second.length, fact: secondFact.toRecord() });

    const records = await journalRecords(root, "old-shape");
    const presented = records.filter((record) => record.kind === RECORD_KINDS.PRESENTED);
    assert.equal(presented.length, 2, "both presented records survive the fold");
    assert.equal(
      records.some((record) => Array.isArray(record.messages)),
      false,
      "the old copies are gone",
    );
    const presentations = records.filter((record) => record.kind === RECORD_KINDS.PRESENTATION);
    assert.equal(presentations.length, 1, "the same epoch/digest stores its text once");
    assert.equal(presentations[0].text, text);
    assert.equal(
      presented.every((record) => record.fact?.text === undefined),
      true,
      "no fact repeats the frozen text",
    );

    const reopened = await PairHint.open({ root, sessionId: "old-shape" });
    assert.ok(
      reopened.chain.equals(Chain.fromMessages(second, { pairIndex: 2 })),
      "the migrated chain is the old copy's chain",
    );
    assert.equal(reopened.text, text, "the hoisted presentation rebuilds the same bytes");
  } finally {
    JournalStore.COMPACT_BYTES = previousBudget;
    await fs.rm(root, { recursive: true, force: true });
  }
});

test("a superseded presented record still folds its fact, and corruption is still refused", () => {
  const fact = { epoch: 0, digest: digestOf("h"), handles: [], after: 1, inserted: 1, text: "h", entries: ["repo-aware-rules"] };
  const state = new PairHintState().apply({
    kind: RECORD_KINDS.PRESENTED,
    length: 3,
    fact,
    // no `messages`: this is what compaction leaves behind
  });
  assert.equal(state.fact.after, 1, "the fact is still folded");
  assert.equal(state.chain, null, "no chain was invented");

  // A length that is not a number is still corruption.
  assert.throws(
    () => new PairHintState().apply({ kind: RECORD_KINDS.PRESENTED }),
    (error) => error.code === PAIR_HINT_CODES.FACT_CORRUPT,
  );
  // A copy whose length disagrees with the record is still corruption.
  assert.throws(
    () => new PairHintState().apply({ kind: RECORD_KINDS.PRESENTED, length: 3, messages: [{}] }),
    (error) => error.code === PAIR_HINT_CODES.FACT_CORRUPT,
  );
  // Messages of a non-array shape are corruption too.
  assert.throws(
    () => new PairHintState().apply({ kind: RECORD_KINDS.PRESENTED, length: 1, messages: "nope" }),
    (error) => error.code === PAIR_HINT_CODES.FACT_CORRUPT,
  );
});

// ---------------------------------------------------------------------------
// The ids a refusal records (2026-11-01, incident of the same day)
// ---------------------------------------------------------------------------

// A refusal that says "message 3 changed" leaves the reader to re-read the
// host's own database to learn WHAT changed. The class that took a live session
// down is a half-pair: a result whose call left the copy, or a call whose
// result did. Those are named by the tool-call ids and nothing else, so the
// shape carries them. They are opaque tokens — the host mints them, the model
// never sees them as content — which is why recording them cannot leak the
// conversation.
test("a refusal names the tool calls and results of the request it compared", async () => {
  const root = await tempRoot();
  const hint = await refusingPairHint(root, "ids");
  const withCall = [
    { role: "user", content: [{ type: "text", text: "go" }] },
    {
      role: "assistant",
      content: [
        { type: "text", text: "working" },
        { type: "tool-call", toolName: "js-worker", toolCallId: "c6", input: { code: "1" } },
      ],
    },
    {
      role: "tool",
      content: [{ type: "tool-result", toolName: "js-worker", toolCallId: "c6", output: { type: "text", value: "done" } }],
    },
  ];
  const first = await hint.project({ messages: withCall });

  // The host rebuilds its request with the result for a call that is no longer
  // there: the half-pair shape the record has to be able to name.
  const orphaned = first.messages.slice(0, 2).concat([
    {
      role: "tool",
      content: [{ type: "tool-result", toolName: "js-worker", toolCallId: "c7", output: { type: "text", value: "x" } }],
    },
  ]);
  await hint.project({ messages: orphaned }).catch(() => {});

  const refusal = (await journalRecords(root, "ids")).filter((record) => record.kind === RECORD_KINDS.REFUSED).at(-1);
  assert.ok(refusal, "the refusal was recorded");
  // The chain side is digests only; the request side still carries the ids,
  // which are handles rather than content.
  assert.deepEqual(Object.keys(refusal.previous.shapes[1]), ["digest"]);
  const callShape = refusal.next.shapes[1];
  const resultShape = refusal.next.shapes[2];
  assert.deepEqual(callShape.calls, ["c6"], "the call the assistant message issued");
  assert.deepEqual(callShape.results, []);
  assert.deepEqual(resultShape.results, ["c7"], "the result the request carries");
  assert.deepEqual(resultShape.calls, []);
  // The two sides are named in the same vocabulary, so a reader can ask the one
  // question the digest cannot answer: is every result's id present in some
  // call of the same side?
  const issued = new Set(refusal.next.shapes.flatMap((shape) => shape.calls));
  const answered = refusal.next.shapes.flatMap((shape) => shape.results);
  assert.equal(answered.every((id) => issued.has(id)), false, "the request carries the orphan");
  const nextIssued = new Set(refusal.next.shapes.flatMap((shape) => shape.calls));
  assert.equal(
    refusal.next.shapes.flatMap((shape) => shape.results).some((id) => !nextIssued.has(id)),
    true,
    "the other side is the one carrying the orphan",
  );
  await fs.rm(root, { recursive: true, force: true });
});

test("ids are sorted and repeat-free, so two records of one message compare equal", async () => {
  const root = await tempRoot();
  const hint = await refusingPairHint(root, "sorted");
  const many = [
    { role: "user", content: [{ type: "text", text: "go" }] },
    {
      role: "assistant",
      content: [
        { type: "tool-call", toolName: "js-worker", toolCallId: "c9", input: {} },
        { type: "tool-call", toolName: "js-worker", toolCallId: "c1", input: {} },
      ],
    },
    {
      role: "tool",
      content: [
        { type: "tool-result", toolName: "js-worker", toolCallId: "c9", output: { type: "text", value: "a" } },
        { type: "tool-result", toolName: "js-worker", toolCallId: "c1", output: { type: "text", value: "b" } },
      ],
    },
  ];
  const first = await hint.project({ messages: many });
  const rewritten = first.messages.map((message, index) =>
    index === 0 ? { ...message, content: [{ type: "text", text: "changed" }] } : message,
  );
  await hint.project({ messages: rewritten }).catch(() => {});
  const refusal = (await journalRecords(root, "sorted")).filter((record) => record.kind === RECORD_KINDS.REFUSED).at(-1);
  assert.deepEqual(refusal.next.shapes[1].calls, ["c1", "c9"], "sorted, so order is never the difference");
  assert.deepEqual(refusal.next.shapes[2].results, ["c1", "c9"]);
  await fs.rm(root, { recursive: true, force: true });
});

test("a record written before the ids existed still folds, and is not read as empty", async () => {
  // The journal is the only thing a cold start needs, so a reader must survive a
  // record from the version before this one. An absent list means "nobody
  // recorded it"; an empty list means "this message carries no tool call". The
  // two must not be collapsed, or a stale record would look like evidence.
  const old = {
    kind: RECORD_KINDS.REFUSED,
    code: PAIR_HINT_CODES.PREFIX_DRIFT,
    epoch: 0,
    index: 1,
    previous: { count: 1, shapes: [{ role: "user", parts: ["text"], length: 2, digest: digestOf("x") }] },
    next: { count: 1, shapes: [{ role: "user", parts: ["text"], length: 2, digest: digestOf("y") }] },
  };
  const state = new PairHintState().apply(old);
  assert.equal(state.refusal?.index, 1, "an older record is still a refusal");
  assert.equal("calls" in state.refusal.previous.shapes[0], false, "a missing list stays missing");
  assert.equal("results" in state.refusal.previous.shapes[0], false);

  // A list that IS present must be well formed: sorted strings only.
  for (const bad of [["b", "a"], [1], "c1"]) {
    assert.throws(
      () =>
        new PairHintState().apply({
          ...old,
          previous: { count: 1, shapes: [{ role: "user", parts: ["text"], calls: bad, length: 2, digest: digestOf("x") }] },
        }),
      (error) => error.code === PAIR_HINT_CODES.FACT_CORRUPT,
      `a malformed id list is corruption: ${JSON.stringify(bad)}`,
    );
  }
});

test("EVERY refusal leaves a record — the journal is written before the error is raised", async () => {
  // A refusal that writes nothing is the incident this whole diagnostic exists
  // for: a live session refused every request and left no trace of which one.
  // So the record is written for every refusal code, not only for a drift, and
  // the error is still raised unchanged.
  const root = await tempRoot();
  const hint = await refusingPairHint(root, "every");
  const first = await hint.project({ messages: history(3) });

  // 1. A rewrite of a message the model read.
  const rewritten = first.messages.map((message, index) =>
    index === 2 ? { ...message, content: "rewritten" } : message,
  );
  const drift = await hint.project({ messages: rewritten }).then(
    () => null,
    (error) => error,
  );
  // 2. A copy that carries the pair twice: a different code, the same duty.
  const doubled = [...first.messages, first.messages[first.messages.length - 2]];
  const twice = await hint.project({ messages: doubled }).then(
    () => null,
    (error) => error,
  );
  assert.ok(drift !== null, "the rewrite was refused");
  assert.ok(twice !== null, "the doubled pair was refused");

  const refusals = (await journalRecords(root, "every")).filter((record) => record.kind === RECORD_KINDS.REFUSED);
  assert.deepEqual(
    refusals.map((record) => record.code),
    [drift.code, twice.code],
    "one record per refusal, in the order they happened",
  );
  for (const record of refusals) {
    assert.equal(typeof record.message, "string", "the reason is kept, so a reader need not guess");
    assert.ok(Number.isInteger(record.epoch), "the epoch is kept");
  }
  await fs.rm(root, { recursive: true, force: true });
});
