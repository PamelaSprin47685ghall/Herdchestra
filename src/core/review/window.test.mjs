// The evaluation window of `js-review` (根 §2.3), as a behavioural property.
//
// §2.3 states three facts, and every gate in every adapter reads them:
//
//   1. `js-review` is open only for the Manager, and only inside a round;
//   2. a round opens at its first request and shuts at the round's FIRST
//      accepted review;
//   3. a refused review leaves the window as it was, because the Manager has not
//      judged.
//
// The tests below drive the window with random sequences of the four events a
// host can produce (request, accepted review, refused review, relay) and check
// the invariants after every step, rather than the outcome of one hand-written
// script. A gate that is always shut passes a single happy-path test; it cannot
// pass these.

import test from "node:test";
import assert from "node:assert/strict";
import fc from "fast-check";

import { ReviewWindow, WINDOW_STATES } from "../review.mjs";
import { fcRuns } from "../test-runs.mjs";

const manager = () => new ReviewWindow({ kind: "manager" });

// The four things a host does to a window, as one alphabet.
const EVENT = fc.oneof(
  // A request boundary: `before_agent_start`, `beforeModel`, or a program call.
  fc.record({ kind: fc.constant("request"), round: fc.option(fc.integer({ min: 1, max: 6 }), { nil: undefined }) }),
  // A review the ledger accepted.
  fc.record({
    kind: fc.constant("accepted"),
    receipt: fc.oneof(
      fc.constant({ ok: true, kind: "recorded" }),
      fc.constant({ ok: true, kind: "nextReview" }),
      fc.constant({ ok: true, kind: "confirm" }),
      fc.constant({ ok: true, kind: "relay" }),
    ),
  }),
  // A review the ledger refused.
  fc.record({
    kind: fc.constant("refused"),
    receipt: fc.oneof(
      fc.constant({ ok: false, kind: "rejected", rejection: "ALREADY_DONE" }),
      fc.constant({ ok: false, kind: "relay", rejection: "RELAY_UNAVAILABLE" }),
      fc.constant(null),
      fc.constant(undefined),
      fc.constant("not a receipt"),
    ),
  }),
  // A relay: the successor judges afresh.
  fc.record({ kind: fc.constant("relay"), tenure: fc.integer({ min: 1, max: 5 }) }),
);

// Apply one event; the window's own API decides what it means.
const apply = (window, event) => {
  if (event.kind === "request") {
    if (event.round === undefined) window.beginRound();
    else window.beginRound(event.round);
    return;
  }
  if (event.kind === "accepted" || event.kind === "refused") {
    window.noteReview(event.receipt);
    return;
  }
  window.relay(event.tenure);
};

test("open is a boolean property, never the round-starting method", () => {
  // The bug this asserts against: a method named `open` shadows the getter of
  // the same name, so every `window.open === true` in every adapter compared a
  // function and answered "shut". A gate that never opens is invisible at
  // runtime, so the shape is asserted directly.
  const window = manager();
  assert.equal(typeof window.open, "boolean");
  assert.equal(window.open, false);
  assert.equal(typeof window.beginRound, "function");
  window.beginRound();
  assert.equal(window.open, true);
  assert.equal(window.state, WINDOW_STATES.OPEN);
});

test("a fresh window is shut, and a Plan's window is never open", () => {
  assert.equal(manager().open, false);
  assert.equal(manager().state, WINDOW_STATES.CLOSED);
  const plan = new ReviewWindow({ kind: "plan" });
  plan.beginRound();
  plan.noteReview({ ok: true });
  assert.equal(plan.open, false);
  assert.equal(plan.state, WINDOW_STATES.NOT_MANAGER);
});

test("property: a refused review never changes the window", () => {
  fc.assert(
    fc.property(fc.array(EVENT, { minLength: 1, maxLength: 40 }), fc.constantFrom(null, undefined, { ok: false }), (events, receipt) => {
      const window = manager();
      for (const event of events) apply(window, event);
      const before = window.fingerprint();
      const state = window.state;
      const round = window.round;
      const reviews = window.reviews;
      window.noteReview(receipt);
      assert.equal(window.fingerprint(), before, "a refused receipt left a trace");
      assert.equal(window.state, state);
      assert.equal(window.round, round);
      assert.equal(window.reviews, reviews);
    }),
    { ...fcRuns(120) },
  );
});

test("property: a review closes the window only while it is open, and only once per round", () => {
  fc.assert(
    fc.property(fc.array(EVENT, { minLength: 1, maxLength: 40 }), fc.array(fc.boolean(), { minLength: 1, maxLength: 8 }), (events, accepted) => {
      const window = manager();
      for (const event of events) apply(window, event);
      const wasOpen = window.open;
      const round = window.round;
      let acceptedSeen = 0;
      for (let index = 0; index < accepted.length; index += 1) {
        const before = window.open;
        window.noteReview(accepted[index] ? { ok: true } : { ok: false });
        // A refused review is invisible; an accepted one shuts the window.
        if (accepted[index] === false) {
          assert.equal(window.open, before, "a refused review moved the window");
        } else {
          acceptedSeen += 1;
          assert.equal(window.open, false, "an accepted review left the window open");
          if (before === true) assert.equal(acceptedSeen, 1, "a second acceptance shut the round again");
        }
      }
      // `wasOpen` is read BEFORE the reviews: an open round cannot survive one
      // accepted review, so it must be shut afterwards.
      if (wasOpen && acceptedSeen > 0) assert.equal(window.open, false, "an open round stayed open after an accepted review");
      assert.equal(window.round, round, "a review never starts a round");
    }),
    { ...fcRuns(120) },
  );
});

test("property: an explicit round moves the window forward, and the same round never reopens it", () => {
  fc.assert(
    fc.property(fc.array(EVENT, { minLength: 0, maxLength: 30 }), fc.integer({ min: 1, max: 6 }), (events, round) => {
      const window = manager();
      for (const event of events) apply(window, event);
      const start = window.round;
      // A round at or before the current one is the round in progress: it does
      // not move the window and does not reopen it.
      window.beginRound(start === 0 ? round : start);
      window.noteReview({ ok: true });
      assert.equal(window.open, false);
      window.beginRound(start === 0 ? round : start);
      assert.equal(window.open, false, "the same round reopened a window a review had shut");
      // A LATER round is a new evaluation.
      window.beginRound(window.round + 1);
      assert.equal(window.open, true);
    }),
    { ...fcRuns(120) },
  );
});

test("property: a bare request starts the next round only after a review shut one", () => {
  fc.assert(
    fc.property(fc.array(EVENT, { minLength: 0, maxLength: 30 }), (events) => {
      const window = manager();
      for (const event of events) apply(window, event);
      // A host that prepares one request more than once repeats `beginRound()`
      // with no argument; the round must not advance under an open window.
      const wasOpen = window.open;
      const round = window.round;
      window.beginRound();
      assert.equal(window.open, true);
      window.beginRound();
      // The same request: no second round. A request that followed an accepted
      // review is a genuinely new round, and its own first request is that
      // round.
      const started = wasOpen ? round : round + 1;
      assert.equal(window.round, started, "a repeated request started a second round");
      // After a review, the next request is a genuinely new round.
      window.noteReview({ ok: true });
      window.beginRound();
      assert.equal(window.open, true);
      assert.equal(window.round, started + 1);
    }),
    { ...fcRuns(120) },
  );
});

test("property: rounds only move forward, and a bad round is refused", () => {
  fc.assert(
    fc.property(fc.integer({ min: 1, max: 20 }), fc.array(fc.integer({ min: -2, max: 25 }), { maxLength: 15 }), (first, later) => {
      const window = manager();
      window.beginRound(first);
      let round = first;
      for (const candidate of later) {
        if (candidate < 1) {
          assert.throws(() => window.beginRound(candidate), { code: "INVALID_ARGUMENT" });
          assert.equal(window.round, round);
          continue;
        }
        window.beginRound(candidate);
        assert.ok(window.round >= round, "a round went backwards");
        assert.equal(window.round, Math.max(round, candidate));
        round = window.round;
      }
    }),
    { ...fcRuns(80) },
  );
});

test("property: a relay shuts the window and hands the successor a fresh one", () => {
  fc.assert(
    fc.property(fc.array(EVENT, { minLength: 1, maxLength: 30 }), fc.integer({ min: 1, max: 5 }), (events, tenure) => {
      const window = manager();
      for (const event of events) apply(window, event);
      window.relay(tenure);
      assert.equal(window.open, false, "a successor starts with the face shut");
      assert.equal(window.tenure, tenure);
      // The successor's first request is a round of its own.
      window.beginRound();
      assert.equal(window.open, true);
      window.noteReview({ ok: true });
      assert.equal(window.open, false);
    }),
    { ...fcRuns(100) },
  );
});

test("property: the window's state is fully described by its fingerprint", () => {
  fc.assert(
    fc.property(fc.array(EVENT, { minLength: 0, maxLength: 30 }), (events) => {
      const one = manager();
      const other = manager();
      for (const event of events) {
        apply(one, event);
        apply(other, event);
      }
      assert.equal(one.fingerprint(), other.fingerprint(), "the same events produced different windows");
      // The fingerprint is a stable JSON string that names every fact the
      // gates read: the round, whether it is open, the round that closed it and
      // the tenure.
      const saved = JSON.parse(one.fingerprint());
      assert.equal(saved.round, one.round);
      assert.equal(saved.open, one.open);
      assert.equal(saved.tenure, one.tenure);
      assert.equal(saved.reviews, one.reviews);
      assert.equal(saved.kind, "manager");
    }),
    { ...fcRuns(100) },
  );
});
