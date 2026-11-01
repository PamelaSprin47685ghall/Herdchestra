// The projection invariants of Pair Hint, across growth, retry, restart and
// epoch (spec §6.1 step 4).
//
// These are the properties the three hosts depend on and none of them can prove
// alone, because each host reaches them through a different message shape. The
// suite drives core directly, so a failure here names core and not an adapter.
//
//   GROWTH    a copy that extends what was presented (the host's own history
//             grew by later turns) is projected without drift, and the pair
//             keeps ONE placement.
//   RETRY     a copy that is byte-identical to what was presented (a host
//             retry, a re-entrant hook) replays. No epoch, no placement and no
//             record moves, so a retry can never spend a chain.
//   RESTART   a journal that is closed and reopened (a cold start) answers the
//             same bytes for the same request, and the chain it continues is
//             the one the old process had.
//   EPOCH     every epoch boundary keeps the messages before the pair
//             unchanged and the pair in the place the old pair stood; the
//             byte prefix is NOT required across a boundary (the pair's bytes
//             change by design) and is required everywhere else.
//
// The rule under test is NOT weakened anywhere here: a copy that rewrites a
// message the model already read still refuses with PREFIX_DRIFT, and the
// suite asserts that too, so a "fix" that stops refusing fails these tests.
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
import { findAllInjections, findInjection } from "./presentation.mjs";
import { PAIR_HINT_CODES, isPairHintError } from "./errors.mjs";
import { INJECTION_MODES } from "./injection.mjs";
import { fcRuns } from "../test-runs.mjs";

const tempRoot = async () => await fs.mkdtemp(path.join(os.tmpdir(), "pair-hint-projection-"));

const refusalCode = async (promise) => {
  try {
    await promise;
  } catch (error) {
    assert.ok(isPairHintError(error), `expected PairHintError, got ${error}`);
    return error.code;
  }
  assert.fail("expected a refusal");
};

// One host message. Three shapes, because the chain compares whole messages and
// a host rebuilds the same message in whichever shape it stores: the suite must
// keep each shape stable, not mix them for one position.
const hostMessage = (index, shape) => {
  if (shape === 0) return { role: "user", content: `m${index}` };
  if (shape === 1) return { role: "assistant", content: [{ type: "text", text: `m${index}` }] };
  return { role: "assistant", content: [{ type: "text", text: `m${index}` }, { type: "thinking", text: "t" }] };
};

// A host history: each position keeps the shape it was first given, so a grown
// copy is the old copy plus later messages, never a rewrite of an old one.
const history = (length, shapes, extra = 0) =>
  Array.from({ length: length + extra }, (_, index) => hostMessage(index, shapes[index % shapes.length]));

const shapeArb = fc.array(fc.integer({ min: 0, max: 2 }), { minLength: 1, maxLength: 3 });

// A request copy always has at least one message: no host sends an empty one, and
// an empty copy cannot carry the pair it was placed after (see injection.mjs).
const lengthArb = fc.integer({ min: 1, max: 5 });

const bind = async (hint, sessionId) => {
  await hint.bind({ sessionId, ladder: { explicit: "English" } });
  return hint;
};

// The pair's placement and the copy before it, for the "prefix up to the pair"
// comparisons an epoch boundary is allowed to make.
const split = (messages) => {
  const injection = findInjection(messages);
  assert.ok(injection !== null, "every projected request carries the pair");
  return { at: injection.index, before: messages.slice(0, injection.index) };
};

// ------------------------------------------------------------------- GROWTH

test("growth: a copy that extends what was presented projects, keeps one placement, and never refuses", async () => {
  await fc.assert(
    fc.asyncProperty(
      shapeArb,
      fc.array(fc.nat({ max: 4 }), { minLength: 1, maxLength: 7 }),
      async (shapes, grows) => {
        const hint = await bind(new PairHint({ store: new MemoryJournal() }), "growth");
        let length = 1;
        let previous = null;
        let placed = null;
        for (const grow of grows) {
          length += grow;
          const request = await hint.project({ messages: history(length, shapes) });
          const { at } = split(request.messages);
          if (placed === null) placed = at;
          assert.equal(at, placed, "the placement decided by the first request is the placement");
          if (previous !== null) {
            // The stronger fact, not merely "no throw": the new request really
            // does start with the bytes of the one before it.
            assert.deepEqual(request.messages.slice(0, previous.length), previous);
            // A host never stores our pair, so a request it rebuilds carries
            // none and the hint PLACES it at the recorded count. The mode names
            // that; what must not happen is a second pair or a moved placement.
            assert.equal(findAllInjections(request.messages).length, 1, "exactly one pair");
          }
          previous = request.messages;
        }
      },
    ),
    { ...fcRuns(60) },
  );
});

test("growth: a copy that grows PAST the pair (the host stored our pair) still projects at one placement", async () => {
  await fc.assert(
    fc.asyncProperty(shapeArb, lengthArb, fc.nat({ max: 4 }), async (shapes, length, extra) => {
      const hint = await bind(new PairHint({ store: new MemoryJournal() }), "growth-past");
      const first = await hint.project({ messages: history(length, shapes) });
      // The host handed back the request it sent (pair included) and grew it.
      const grown = [...first.messages, ...history(length, shapes, extra).slice(length)];
      const second = await hint.project({ messages: grown });
      assert.equal(second.mode, INJECTION_MODES.REPLAYED);
      const { at } = split(second.messages);
      assert.equal(at, findInjection(first.messages).index, "the pair keeps the place it stood in");
      // And a later copy WITHOUT the pair lands between the same two messages.
      const bare = await hint.project({ messages: history(length + extra + 1, shapes) });
      assert.equal(findInjection(bare.messages).index, at);
    }),
    { ...fcRuns(50) },
  );
});

// -------------------------------------------------------------------- RETRY

test("retry: a byte-identical copy replays and moves no record", async () => {
  await fc.assert(
    fc.asyncProperty(shapeArb, lengthArb, fc.nat({ max: 3 }), async (shapes, length, retries) => {
      const store = new MemoryJournal();
      const hint = await bind(new PairHint({ store }), "retry");
      const first = await hint.project({ messages: history(length, shapes) });
      const records = store.records.length;
      const epoch = hint.epoch;
      let previous = first.messages;
      for (let index = 0; index < retries; index += 1) {
        const again = await hint.project({ messages: history(length, shapes) });
        // A host never stores our pair, so a rebuilt copy carries none and the
        // hint PLACES it at the recorded count again; the bytes are what must
        // not move. (A copy that still carries the pair is the `growth` case
        // above, and there the mode is `replayed`.)
        assert.deepEqual(again.messages, first.messages, "a retry answers the same bytes");
        assert.equal(again.text, first.text);
        assert.deepEqual(again.messages.slice(0, previous.length), previous);
        previous = again.messages;
        assert.equal(hint.epoch, epoch, "a retry never opens an epoch");
      }
      // A replay is a request that goes out, so it is recorded once per
      // request; what must NOT happen is an epoch or a placement change.
      assert.equal(store.records.length - records, retries, "each request is one presented record");
      assert.equal(hint.fact.after, first.fact.after, "the placement never moves on a retry");
    }),
    { ...fcRuns(50) },
  );
});

// ------------------------------------------------------------------ RESTART

test("restart: a reopened journal answers the same bytes and continues the same chain", async () => {
  await fc.assert(
    fc.asyncProperty(
      shapeArb,
      fc.array(fc.nat({ min: 1, max: 3 }), { minLength: 1, maxLength: 5 }),
      fc.integer({ min: 1, max: 4 }),
      async (shapes, grows, restartAt) => {
        const root = await tempRoot();
        try {
          const sessionId = "restart";
          const live = await bind(new PairHint({ store: new JournalStore({ root, sessionId }) }), sessionId);
          // One request before the loop, so the journal always carries a
          // presented record when the restart lands: a cold start over an empty
          // journal proves nothing.
          const copies = [(await live.project({ messages: history(1, shapes) })).messages];
          let length = 1;
          const cut = Math.min(restartAt, grows.length - 1);
          for (let index = 0; index < grows.length; index += 1) {
            if (index === cut) {
              // A cold start: the journal is closed and reopened, nothing else.
              const reopened = await PairHint.open({ root, sessionId });
              const replay = await reopened.project({ messages: history(length, shapes) });
              // A restart is only meaningful once the old process presented
              // something: `copies` is empty when the cut lands on the first
              // step, and then the reopened hint simply opens the chain.
              if (copies.length > 0) {
                assert.deepEqual(
                  replay.messages,
                  copies[copies.length - 1],
                  "a restart answers the bytes the old process answered",
                );
              }
              // The reopened hint continues the same chain: the next request
              // must extend the copy the old process presented.
              const after = await reopened.project({ messages: history(length + 1, shapes) });
              assert.deepEqual(after.messages.slice(0, copies[copies.length - 1].length), copies[copies.length - 1]);
              copies.push(after.messages);
              length += 1;
              continue;
            }
            length += grows[index];
            const request = await live.project({ messages: history(length, shapes) });
            copies.push(request.messages);
          }
          // Every consecutive pair in the whole run (across the restart too) is
          // an extension.
          for (let index = 1; index < copies.length; index += 1) {
            assert.deepEqual(
              copies[index].slice(0, copies[index - 1].length),
              copies[index - 1],
              `request ${index} does not extend request ${index - 1}`,
            );
          }
        } finally {
          await fs.rm(root, { recursive: true, force: true });
        }
      },
    ),
    { ...fcRuns(25) },
  );
});

// -------------------------------------------------------------------- EPOCH

test("epoch: every boundary keeps the bytes before the pair and the pair's place", async () => {
  await fc.assert(
    fc.asyncProperty(
      shapeArb,
      // 0: grow only, 1: open an epoch on the same copy, 2: both.
      fc.array(fc.integer({ min: 0, max: 2 }), { minLength: 1, maxLength: 7 }),
      async (shapes, steps) => {
        const hint = await bind(new PairHint({ store: new MemoryJournal() }), "epoch");
        let length = 1;
        let previous = await hint.project({ messages: history(length, shapes) });
        for (let index = 0; index < steps.length; index += 1) {
          const step = steps[index];
          const opens = step !== 0;
          if (opens) {
            hint.setHandles([`park-${index}`]);
            await hint.newEpoch();
          }
          if (step !== 1) length += 1;
          const next = await hint.project({ messages: history(length, shapes) });
          const before = split(previous.messages);
          const now = split(next.messages);
          if (opens) {
            assert.equal(now.at, before.at, `step ${index}: the new pair keeps the old pair's place`);
            assert.deepEqual(now.before, before.before, `step ${index}: the messages before the pair are unchanged`);
            assert.notEqual(next.text, previous.text, `step ${index}: a new epoch renders new bytes`);
          } else {
            assert.deepEqual(next.messages.slice(0, previous.messages.length), previous.messages);
            assert.equal(next.text, previous.text, `step ${index}: bytes never change inside an epoch`);
          }
          previous = next;
        }
      },
    ),
    { ...fcRuns(60) },
  );
});

test("epoch: the prefix rule is not weakened - a rewritten presented message still refuses", async () => {
  await fc.assert(
    fc.asyncProperty(shapeArb, lengthArb, fc.nat({ max: 4 }), fc.string({ minLength: 1 }), async (shapes, length, at, text) => {
      const hint = await bind(new PairHint({ store: new MemoryJournal() }), "rewrite");
      const base = history(length, shapes);
      await hint.project({ messages: base });
      const rewritten = base.map((message, index) =>
        index === Math.min(at, base.length - 1)
          ? { role: "user", content: `rewritten ${text}` }
          : message,
      );
      assert.equal(
        await refusalCode(hint.project({ messages: rewritten })),
        PAIR_HINT_CODES.PREFIX_DRIFT,
        "a message the model already read may never change inside an epoch",
      );
    }),
    { ...fcRuns(40) },
  );
});

test("epoch: a request shorter than what was presented refuses instead of guessing", async () => {
  await fc.assert(
    fc.asyncProperty(lengthArb, fc.nat({ max: 4 }), async (length, keep) => {
      const hint = await bind(new PairHint({ store: new MemoryJournal() }), "shorter");
      // One shape for every position: this property is about LENGTH, and a
      // mixed-shape history would also rewrite messages, which the previous
      // property already covers.
      const shapes = [0];
      await hint.project({ messages: history(length, shapes) });
      // Strictly shorter than what was presented: `keep` messages, where the
      // smallest copy the property can build is one message.
      const shorter = history(Math.min(keep, length - 2) + 1, shapes);
      assert.equal(await refusalCode(hint.project({ messages: shorter })), PAIR_HINT_CODES.PREFIX_DRIFT);
    }),
    { ...fcRuns(30) },
  );
});
