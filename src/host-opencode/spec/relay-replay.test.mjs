// The relay replay rule (根 §2.4 第 3 步): a persisted row is replayed as far as the
// transitions it describes really happened.
//
// Run with: `node --test "src/host-opencode/**/*.test.mjs"`.

import test from "node:test";
import assert from "node:assert/strict";

import { restoreLedger, RECORD_TYPES, RECORD_VERSION } from "./session-state.mjs";

const review = (milestone, obligations = [], extra = {}) => ({
  v: RECORD_VERSION,
  type: RECORD_TYPES.REVIEW,
  sessionID: "ses_replay",
  kind: "manager",
  milestone,
  tenure: 0,
  reviews: 1,
  obligations,
  successor: false,
  ...extra,
});

test("a record that claims a milestone past a relay stops at the relay", () => {
  // A Manager reaches `close` only through a relay. The replay must not invent a
  // successor tenure for a row that skipped it: it stops at `execute`.
  const ledger = restoreLedger("manager", [review("close")]);
  assert.equal(ledger.milestone, "execute");
  assert.equal(ledger.tenure, 0);
  assert.equal(ledger.successor, false);
});

test("a record below the relay is replayed as it was written", () => {
  // `execute` is the first milestone: nothing to replay, and the obligations the
  // row carries are still applied.
  const ledger = restoreLedger("manager", [review("execute", [{ key: "tests", value: "add tests" }])]);
  assert.equal(ledger.milestone, "execute");
  assert.equal(ledger.tenure, 0);
  assert.deepEqual(ledger.obligations, [{ key: "tests", value: "add tests" }]);
});

test("the row's review count survives the program that wrote it", () => {
  // 根 §2.2 reads the count to decide whether Plan's draft may be written, and
  // the adapter builds a fresh surface per call: the count is a fact of the
  // TENURE. A review that recorded an obligation and then cleared it leaves the
  // same empty ledger as a tenure that never reviewed, so the row's `reviews` is
  // the only place the count survives.
  const plan = (obligations, reviews) => ({ ...review("S1", obligations, { reviews }), kind: "plan" });
  const never = restoreLedger("plan", [plan([], 0)]);
  assert.equal(never.reviewCount, 0);
  // Two reviews, neither of which left an obligation behind.
  const twice = restoreLedger("plan", [plan([], 2)]);
  assert.equal(twice.reviewCount, 2, "the row's count is restored");
  assert.deepEqual(twice.obligations, [], "nothing is invented in the ledger");
  // The obligations the row carries are applied as they are, and they are not
  // counted twice: the replay reaches the count by booking empty reviews, which
  // record nothing.
  const recorded = restoreLedger("plan", [plan([{ key: "open", value: "x" }], 1)]);
  assert.equal(recorded.reviewCount, 1);
  assert.deepEqual(recorded.obligations, [{ key: "open", value: "x" }]);
});

test("a successor's row restores the tenure the relay opened", () => {
  // `openLedger("manager", { at })` writes the step before `at` under the
  // successor's tenure: the successor's first empty review is the `confirm` that
  // moves it onto `at`. The row carries no obligations, so the new tenure judges
  // from an empty ledger (根 §2.3).
  const ledger = restoreLedger("manager", [review("execute", [], { tenure: 1, reviews: 0 })]);
  assert.equal(ledger.milestone, "execute");
  assert.deepEqual(ledger.obligations, []);
  assert.equal(ledger.reviewCount, 0);
  assert.equal(ledger.tenure, 1, "the new tenure is the one the row names");
  assert.equal(ledger.successor, true, "the relay's successor flag survives the restart");
  const advanced = ledger.review([]);
  assert.equal(advanced.kind, "confirm");
  assert.equal(ledger.milestone, "close");
});
