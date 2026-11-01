import test from "node:test";
import assert from "node:assert/strict";
import fc from "fast-check";

import { ReviewLedger } from "../../core/review.mjs";
import { ReviewMember } from "./review.mjs";
import { fcRuns } from "../../core/test-runs.mjs";

const recorder = () => {
  const calls = [];
  return {
    calls,
    handOver: async (decision) => {
      calls.push(["handOver", decision]);
      // 根 §2.4 第 3 步： a host that performed the hand-over reports all three parts.
      return { ok: true, retired: true, successor: "m1", barrier: true };
    },
    startManager: async (draft) => {
      calls.push(["startManager", draft]);
      return { sessionId: "m1" };
    },
    askOperator: async (question) => {
      calls.push(["askOperator", question]);
      return "do the thing";
    },
  };
};

const member = (kind, port) => new ReviewMember({ ledger: new ReviewLedger({ kind }), port });

// Walk a Plan to `待执行` the way 根 §2.4 says it is reached: the first empty
// review is the `S1` question, and each later step is a relay (retirement,
// barrier, successor) followed by the successor's own empty verdict. The member
// opens the successor itself once the host reports a complete hand-over, so the
// walk is exactly what a live Plan does.
const planToReady = async (review) => {
  const asked = await review.review([], "the draft");
  assert.equal(asked.kind, "askUser");
  for (let step = 0; step < 3; step += 1) {
    assert.equal((await review.review([], "the draft")).kind, "relay");
    assert.equal(review.ledger.successor, true);
    assert.equal((await review.review([], "the draft")).kind, "confirm");
  }
  assert.equal(review.ledger.milestone, "待执行");
};

test("a non-empty ledger is recorded and performs no side effect", async () => {
  const port = recorder();
  const review = member("manager", port);
  const receipt = await review.review([{ key: "tests", value: "add tests" }]);
  assert.equal(receipt.kind, "recorded");
  assert.deepEqual(port.calls, []);
});

test("a receipt with no host effect is returned synchronously", async () => {
  // 根 §2.4: the ledger decides synchronously, so `const r = review(f)` must
  // read the same fields `await review(f)` would. Only relay/handoff/askUser
  // answer with a promise.
  const port = recorder();
  const review = member("manager", port);
  const recorded = review.review([{ key: "tests", value: "add tests" }]);
  assert.equal(typeof recorded?.then, "undefined");
  assert.equal(recorded.ok, true);
  assert.equal(recorded.kind, "recorded");

  const confirmed = review.review([{ key: "tests", value: null }]);
  // Clearing the ledger at `execute` relays; that one needs the host.
  assert.equal(typeof confirmed?.then, "function");
  assert.equal((await confirmed).kind, "relay");

  // A fresh manager's first empty review is its `confirm`, decided alone.
  const fresh = member("manager", recorder());
  const first = fresh.review([]);
  assert.equal(typeof first?.then, "undefined");
  assert.equal(first.kind, "confirm");
  assert.equal((await fresh.review([])).kind, "relay");
});

test("an empty ledger relays once and opens the successor in the same step", async () => {
  const port = recorder();
  const review = member("manager", port);
  await review.review([{ key: "tests", value: "add tests" }]);
  const receipt = await review.review([{ key: "tests", value: null }]);
  assert.equal(receipt.kind, "relay");
  // The findings travel with the decision: a host that cannot perform the relay
  // inside this turn records them with its deferred intent, so the later step
  // acts on the obligation this review actually established.
  assert.deepEqual(port.calls, [
    ["handOver", { retire: true, openSuccessor: true, barrier: true, findings: [{ key: "tests", value: null }] }],
  ]);
  assert.equal(review.ledger.tenure, 1);
  assert.equal(review.ledger.successor, true);
});

test("a relay the host cannot perform opens no successor and is not accepted", async () => {
  const calls = [];
  const port = {
    ...recorder(),
    handOver: async (decision) => {
      calls.push(["handOver", decision]);
      return { ok: false, reason: "NO_SESSION_CONTROL" };
    },
  };
  const review = member("manager", port);
  await review.review([{ key: "tests", value: "add tests" }]);
  const receipt = await review.review([{ key: "tests", value: null }]);
  // Retirement, barrier and successor open in one step (根 §2.4 第 3 步): the host
  // did none of them, so the ledger does none of them.
  assert.equal(receipt.ok, false);
  assert.equal(receipt.kind, "relay");
  assert.equal(receipt.rejection, "RELAY_UNAVAILABLE");
  assert.deepEqual(receipt.handedOver, { ok: false, reason: "NO_SESSION_CONTROL" });
  assert.equal(calls.length, 1);
  assert.equal(review.ledger.tenure, 0);
  assert.equal(review.ledger.successor, false);
  assert.equal(review.ledger.milestone, "execute");
  assert.equal(review.ledger.empty, true);
  // The incumbent stays in place and the next empty review retries the relay.
  const retried = await review.review([]);
  assert.equal(retried.rejection, "RELAY_UNAVAILABLE");
  assert.equal(calls.length, 2);
  assert.equal(review.ledger.tenure, 0);
});

test("a partial hand-over result is a failure, not a relay", async () => {
  // A host that reports `{retired:false, successor:null}` did not perform the
  // hand-over; accepting it would advance the tenure while nothing changed.
  for (const partial of [
    { retired: false, successor: null },
    { ok: true, retired: false, successor: null },
    { recorded: true },
    {},
  ]) {
    const port = { ...recorder(), handOver: async () => partial };
    const review = member("manager", port);
    await review.review([{ key: "tests", value: "add tests" }]);
    const receipt = await review.review([{ key: "tests", value: null }]);
    assert.equal(receipt.ok, false, JSON.stringify(partial));
    assert.equal(receipt.rejection, "RELAY_UNAVAILABLE", JSON.stringify(partial));
    assert.equal(review.ledger.tenure, 0, JSON.stringify(partial));
    assert.equal(review.ledger.successor, false, JSON.stringify(partial));
  }
});

test("Plan's first empty review in S1 asks the operator; a Manager's never does", async () => {
  // 根 §2.2, §2.4 第 1 步: the question belongs to Plan's S1. The relay port is
  // reached with the question, and the receipt carries the interrupt.
  const planPort = recorder();
  const plan = member("plan", planPort);
  const receipt = await plan.review([]);
  assert.equal(receipt.kind, "askUser");
  assert.equal(receipt.interrupt, true);
  assert.deepEqual(planPort.calls, [["askOperator", "我需要干什么？"]]);

  // A Manager runs unattended: its first empty review verifies and advances,
  // and no question is put to anyone.
  const managerPort = recorder();
  const manager = member("manager", managerPort);
  const verified = await manager.review([]);
  assert.equal(verified.kind, "confirm");
  assert.deepEqual(managerPort.calls, []);
});

test("a plan handoff starts a manager only from the saved draft", async () => {
  const port = recorder();
  const review = member("plan", port);
  await planToReady(review);
  // Three relays happened, each retired one tenure and started the next.
  assert.equal(review.ledger.tenure, 3);
  assert.equal(port.calls.filter(([name]) => name === "handOver").length, 3);
  assert.deepEqual(port.calls[0], ["askOperator", "我需要干什么？"]);
  const rejected = await review.review([], "");
  assert.equal(rejected.kind, "rejected");
  assert.equal(rejected.rejection, "DRAFT_MISSING");
  const handed = await review.review([], "the saved draft");
  assert.equal(handed.kind, "handoff");
  assert.deepEqual(port.calls.at(-1), ["startManager", "the saved draft"]);
  assert.deepEqual(handed.started, { sessionId: "m1" });
});

test("a Plan relays below 待执行 and reaches its exit only through a successor", async () => {
  const port = recorder();
  const review = member("plan", port);
  // The first tenure's empty review asks; it never advances.
  await review.review([], "the draft");
  assert.equal(review.ledger.milestone, "S1");
  // An empty review by the incumbent relays: retire + barrier + successor, at
  // the SAME milestone (根 §2.4 第 3 步).
  const relayed = await review.review([], "the draft");
  assert.equal(relayed.kind, "relay");
  assert.equal(relayed.milestone, "S1");
  assert.deepEqual(port.calls.at(-1)[1], {
    retire: true,
    openSuccessor: true,
    barrier: true,
    findings: [],
  });
  assert.equal(review.ledger.milestone, "S1");
  assert.equal(review.ledger.successor, true);
  assert.equal(review.ledger.tenure, 1);
  // The successor's first empty review promotes it, with no new tenure
  // (根 §2.4 第 5 步).
  const promoted = await review.review([], "the draft");
  assert.equal(promoted.kind, "confirm");
  assert.equal(review.ledger.milestone, "S2");
  assert.equal(review.ledger.tenure, 1);
  assert.equal(review.ledger.successor, false);
  // 待执行 refuses a non-empty ledger: the draft is done, and the only next
  // step is the Manager.
  await planToReadyFrom(review);
  const refused = await review.review([{ key: "more", value: "work" }], "the draft");
  assert.equal(refused.ok, false);
  assert.equal(refused.rejection, "ALREADY_READY");
  assert.deepEqual(review.ledger.obligations, []);
});

// Finish a walk that already stood at `S2` as a promoted successor.
const planToReadyFrom = async (review) => {
  while (review.ledger.milestone !== "待执行") {
    assert.equal((await review.review([], "the draft")).kind, "relay");
    assert.equal((await review.review([], "the draft")).kind, "confirm");
  }
};

test("a finished work reopens only for a real operator message", async () => {
  const port = recorder();
  const review = member("manager", port);
  const ledger = review.ledger;
  // Execute: a non-empty review, then an empty one relays to a successor.
  ledger.review([{ key: "a", value: "x" }]);
  assert.equal(ledger.review([{ key: "a", value: null }]).kind, "relay");
  ledger.openSuccessor();
  // The successor's first empty review advances to close.
  assert.equal(ledger.review([]).kind, "confirm");
  assert.equal(ledger.milestone, "close");
  // Close: an empty review closes the work.
  assert.equal(ledger.review([]).kind, "closed");
  assert.equal(ledger.milestone, "done");
  assert.equal(review.reopen({ operator: false }).ok, false);
  assert.equal(review.reopen({ operator: true }).ok, true);
});

// ------------------------------------------ physical-call replay (§2.4 第 6 步)

test("a replayed review performs no second relay, even after the tenure moved", async () => {
  const port = recorder();
  const review = member("manager", port);
  await review.review([{ key: "tests", value: "add tests" }], null, { callId: "call-1" });
  const relayed = await review.review([{ key: "tests", value: null }], null, { callId: "call-2" });
  assert.equal(relayed.kind, "relay");
  assert.equal(port.calls.filter(([name]) => name === "handOver").length, 1);
  // The host opens the successor after the relay, as it does between turns.
  review.ledger.openSuccessor();
  const tenure = review.ledger.tenure;
  const calls = port.calls.length;

  // §2.4 第 6 步 is unconditional: the same physical call answers with the receipt
  // it produced, and performs nothing — not a second relay, not a second
  // successor, not even a fresh decision now that another tenure is judging.
  const again = await review.review([{ key: "tests", value: null }], null, { callId: "call-2" });
  assert.equal(again.replayed, true);
  assert.equal(again.kind, "relay");
  assert.equal(again.tenure, 0, "the receipt of that call");
  assert.equal(review.ledger.tenure, tenure, "no second tenure");
  assert.equal(port.calls.length, calls, "no side effect on a replay");
});

test("a replayed review after a later call added obligations still performs nothing", async () => {
  const port = recorder();
  const review = member("manager", port);
  const asked = await review.review([], null, { callId: "call-1" });
  assert.equal(asked.kind, "confirm");
  // A different call books an obligation afterwards.
  await review.review([{ key: "b", value: "later work" }], null, { callId: "call-2" });
  const calls = port.calls.length;
  const obligations = review.ledger.obligations;

  const again = await review.review([], null, { callId: "call-1" });
  assert.equal(again.replayed, true);
  assert.equal(again.kind, "confirm");
  // The receipt is the one that call produced. A replay returns the ledger's
  // receipt, which is what §2.4 第 6 步 promises, and reaches no port again.
  assert.equal(again.milestone, asked.milestone);
  assert.deepEqual(review.ledger.obligations, obligations);
  assert.equal(port.calls.length, calls, "the operator is not asked twice");
});

test("a replayed handoff starts no second Manager", async () => {
  const port = recorder();
  const review = member("plan", port);
  // A Plan handoff happens at `待执行` with an empty ledger, reached by three
  // relays and their successors' verdicts (根 §2.4 第 1–5 步).
  await planToReady(review);
  const first = await review.review([], "# draft", { callId: "plan-handoff" });
  assert.equal(first.kind, "handoff");
  assert.equal(port.calls.filter(([name]) => name === "startManager").length, 1);
  // The same physical call arrives again: the Manager it started is already
  // running, so it answers with that receipt and starts nobody.
  const again = await review.review([], "# draft", { callId: "plan-handoff" });
  assert.equal(again.replayed, true);
  assert.equal(again.kind, "handoff");
  assert.equal(port.calls.filter(([name]) => name === "startManager").length, 1);
});

test("the same call id with another payload is refused before any side effect", async () => {
  const port = recorder();
  const review = member("manager", port);
  await review.review([{ key: "a", value: null }], null, { callId: "call-1" });
  const callsBefore = port.calls.length;
  const refused = await review.review([{ key: "b", value: "other" }], null, { callId: "call-1" });
  assert.equal(refused.ok, false);
  assert.equal(refused.rejection, "REPLAY_MISMATCH");
  assert.equal(port.calls.length, callsBefore);
  assert.deepEqual(review.ledger.obligations, []);
});

// ------------------------------------------------ properties (§2.4 第 6 步)

test("property: one physical review call performs its side effect at most once", async () => {
  await fc.assert(
    fc.asyncProperty(
      fc.array(
        fc.record({
          key: fc.string({ minLength: 1, maxLength: 8 }),
          value: fc.option(fc.string({ maxLength: 12 }), { nil: null }),
        }),
        { maxLength: 4 },
      ),
      fc.array(fc.string({ minLength: 1, maxLength: 6 }), { minLength: 1, maxLength: 4 }),
      fc.boolean(),
      async (findings, callIds, handOverOk) => {
        const port = recorder();
        if (!handOverOk) {
          port.handOver = async (decision) => {
            port.calls.push(["handOver", decision]);
            return { ok: false, reason: "NO_SESSION_CONTROL" };
          };
        }
        const review = member("manager", port);
        const unique = [...new Set(callIds)];
        const receipts = [];
        for (const callId of unique) {
          receipts.push(await review.review(findings, null, { callId }));
        }
        const relays = receipts.filter((receipt) => receipt.kind === "relay").length;
        assert.equal(
          port.calls.filter(([name]) => name === "handOver").length,
          relays,
          "each relay performs exactly one hand-over",
        );
        const callsBefore = port.calls.length;
        for (const [index, callId] of unique.entries()) {
          const again = await review.review(findings, null, { callId });
          assert.equal(again.replayed, true, callId);
          assert.equal(again.kind, receipts[index].kind, callId);
        }
        assert.equal(port.calls.length, callsBefore, "a replay performs no port call");

        // The same physical id with a different payload is refused before any
        // side effect, whatever state the ledger is in.
        const mismatched = await review.review(
          [...findings, { key: "property-other", value: "x" }],
          null,
          { callId: unique[0] },
        );
        assert.equal(mismatched.ok, false);
        assert.equal(mismatched.rejection, "REPLAY_MISMATCH");
        assert.equal(port.calls.length, callsBefore, "a mismatch performs no port call");
      },
    ),
    { ...fcRuns(60) },
  );
});

test("property: one physical handoff call starts at most one Manager", async () => {
  await fc.assert(
    fc.asyncProperty(
      fc.string({ minLength: 1, maxLength: 12 }),
      fc.array(fc.string({ minLength: 1, maxLength: 6 }), { minLength: 1, maxLength: 3 }),
      fc.boolean(),
      async (draft, callIds, startOk) => {
        const port = recorder();
        if (!startOk) {
          port.startManager = async (bytes) => {
            port.calls.push(["startManager", bytes]);
            return { ok: false, reason: "NO_CAPACITY" };
          };
        }
        const ledger = new ReviewLedger({ kind: "plan" });
        const review = new ReviewMember({ ledger, port });
        // Walk to 待执行 the way 根 §2.4 does: the S1 question, then three
        // relay-plus-successor verdict pairs.
        assert.equal(ledger.review([]).kind, "askUser");
        for (let step = 0; step < 3; step += 1) {
          assert.equal(ledger.review([]).kind, "relay");
          ledger.openSuccessor();
          assert.equal(ledger.review([]).kind, "confirm");
        }
        const unique = [...new Set(callIds)];
        const receipts = [];
        for (const callId of unique) {
          receipts.push(await review.review([], draft, { callId }));
        }
        assert.equal(
          port.calls.filter(([name]) => name === "startManager").length,
          unique.length,
          "each distinct physical hand-off starts exactly one Manager",
        );
        const callsBefore = port.calls.length;
        for (const [index, callId] of unique.entries()) {
          const again = await review.review([], draft, { callId });
          assert.equal(again.replayed, true, callId);
          assert.equal(again.kind, receipts[index].kind, callId);
        }
        assert.equal(port.calls.length, callsBefore, "a replayed hand-off starts nobody");
      },
    ),
    { ...fcRuns(40) },
  );
});
