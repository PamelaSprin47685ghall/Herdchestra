// Resume, receipt shape and ledger atomicity (spec §3.3, §5).
//
// Each test names the sentence of the spec it holds the code to, and fails on
// the behaviour, not on the spelling of an error message.

import test from "node:test";
import assert from "node:assert/strict";

import { ProgramRegistry, ProgramState, STATES } from "./program.mjs";
import { ControlSurface, resumeValue } from "./control.mjs";
import { PARK_REASONS, RECEIPT_FIELDS, buildReceipt, programView, withExtra } from "./receipt.mjs";
import { ReviewLedger, REVIEW_REJECTIONS, REVIEW_KINDS } from "./review.mjs";
import { Deadline, fakeClock } from "./clock.mjs";

const parkedTarget = (registry, name = "t", capabilities = []) => {
  const program = registry.begin(name, { capabilities });
  program.park({ reason: PARK_REASONS.EXPLICIT, payload: { needle: "x" } });
  return program;
};

test("a receipt has exactly the nine fields of §3.3", () => {
  const receipt = buildReceipt({ name: "p", state: STATES.RUNNING });
  assert.deepEqual(Object.keys(receipt), [...RECEIPT_FIELDS]);
  assert.equal(RECEIPT_FIELDS.length, 9);
  // A field the shape does not carry rides along without becoming a tenth key.
  const carried = withExtra(receipt, "value", [1, 2]);
  assert.deepEqual(Object.keys(carried), [...RECEIPT_FIELDS]);
  assert.deepEqual(carried.value, [1, 2]);
  assert.deepEqual({ ...carried }, receipt);
});

test("a resume answers with the target's receipt, not with the parked site", () => {
  const registry = new ProgramRegistry();
  const target = parkedTarget(registry);
  const control = new ControlSurface(registry);
  control.open("c");
  control.observe("c", "t");
  const answer = control.unpark({ controllerId: "c", name: "t", value: [7, 9] });
  // The controller reads the state its resume produced, with the nine fields
  // intact and the value it injected.
  assert.deepEqual(Object.keys(answer), [...RECEIPT_FIELDS]);
  assert.equal(answer.name, "t");
  assert.equal(answer.state, STATES.RUNNING);
  assert.equal(answer.reason, PARK_REASONS.EXPLICIT);
  assert.deepEqual(answer.payload, { needle: "x" });
  assert.deepEqual(answer.value, [7, 9]);
  // The site itself is gone: a second resume is stale.
  assert.equal(target.site, null);
  assert.throws(() => control.unpark({ controllerId: "c", name: "t" }), (e) => e.code === "STALE_CONTROL");
});

test("core alone is single-shot; a host that consumes the site twice is refused", () => {
  // A host adapter that resumes through the park table first and then lets the
  // resumed body settle can call `takeSite` again. Core must refuse the second
  // consumption even when the program has already moved on, so the resume
  // cannot happen twice. (Adapters should let core's `unpark` own the one
  // consumption; see `src/core/park/AGENTS.md`.)
  const registry = new ProgramRegistry();
  const target = parkedTarget(registry);
  const control = new ControlSurface(registry);
  control.open("c");
  control.observe("c", "t");
  control.unpark({ controllerId: "c", name: "t" });
  assert.throws(() => target.takeSite(), (error) => error.code === "STALE_CONTROL");
});

test("the resumed run continues on the controller's deadline, and the receipt says so", () => {
  const clock = fakeClock(0);
  const registry = new ProgramRegistry();
  const target = registry.begin("t", { deadline: Deadline.forIntent(clock, "ab") });
  target.park({ reason: PARK_REASONS.EXPLICIT, payload: null });
  const control = new ControlSurface(registry);
  control.open("c");
  control.observe("c", "t");
  const controller = Deadline.forIntent(clock, "0123456789"); // 500 ms
  const answer = control.unpark({ controllerId: "c", name: "t", deadline: controller });
  assert.equal(answer.remainingWaitMs, 500);
  assert.equal(target.deadline, controller);
});

test("a controller cannot lower the bar by stating the target's capabilities itself", () => {
  const registry = new ProgramRegistry();
  const target = parkedTarget(registry, "guarded", ["write-draft"]);
  const control = new ControlSurface(registry);
  control.open("c");
  control.observe("c", "guarded");
  // A controller that does not hold the target's set is refused, even though
  // it named no required set of its own.
  assert.throws(
    () => control.unpark({ controllerId: "c", name: "guarded", capabilities: ["read"] }),
    (error) => error.code === "CAPABILITY_DENIED",
  );
  // The controller names an empty set for the target: refused, because it is
  // not the target's set.
  assert.throws(
    () => control.unpark({ controllerId: "c", name: "guarded", targetCapabilities: [] }),
    (error) => error.code === "CAPABILITY_DENIED",
  );
  // A controller holding the set passes.
  const resumed = control.unpark({
    controllerId: "c",
    name: "guarded",
    capabilities: ["write-draft", "read"],
  });
  assert.equal(resumed.state, STATES.RUNNING);
  assert.equal(target.state, STATES.RUNNING);
});

test("an ask site takes the injected value; only a command site keeps its own", () => {
  // 根 §2.2, §3.3: `this.ask` IS `this.park({ question })` + `interrupt`, so the
  // operator's reply arrives as the injected value — the same rule an explicit
  // park and a `where` ambiguity follow.
  assert.equal(resumeValue({ reason: PARK_REASONS.ASK }, "fallback"), "fallback");
  // A host that recorded the answer on the site is still answered when nothing
  // is injected; an injected value is what the caller actually passed.
  assert.equal(resumeValue({ reason: PARK_REASONS.ASK, value: "yes" }, "fallback"), "fallback");
  assert.equal(resumeValue({ reason: PARK_REASONS.ASK, value: "yes" }, null), "yes");
  // A command receipt is produced by the command, never injected.
  assert.equal(resumeValue({ reason: PARK_REASONS.COMMAND }, { exitCode: 0 }), null);
  assert.deepEqual(resumeValue({ reason: PARK_REASONS.COMMAND, value: { exitCode: 0 } }, "x"), {
    exitCode: 0,
  });
  // Only explicit parks and `where` ambiguities receive the injected value.
  assert.deepEqual(resumeValue({ reason: PARK_REASONS.EXPLICIT }, [1, 2]), [1, 2]);
  assert.deepEqual(resumeValue({ reason: PARK_REASONS.EXPLICIT }, null), null);
  assert.equal(resumeValue({ reason: PARK_REASONS.EXPLICIT }, undefined), undefined);
  // An omitted value is null, not undefined (§3.3).
  const registry = new ProgramRegistry();
  const target = parkedTarget(registry);
  const control = new ControlSurface(registry);
  control.open("c");
  control.observe("c", "t");
  assert.equal(control.unpark({ controllerId: "c", name: "t" }).value, null);
});

test("a program's view and the program itself never disagree", () => {
  const registry = new ProgramRegistry();
  const target = parkedTarget(registry);
  const view = programView(target);
  assert.equal(view.reason, PARK_REASONS.EXPLICIT);
  assert.deepEqual(view.payload, { needle: "x" });
  assert.deepEqual(Object.keys(view), [...RECEIPT_FIELDS]);
});

test("a refused successor leaves the ledger exactly as it was", () => {
  const ledger = new ReviewLedger({ kind: "manager" });
  ledger.review([{ key: "a", value: "x" }]);
  ledger.review([{ key: "a", value: null }]); // relay
  ledger.openSuccessor();
  ledger.review([]); // confirm -> close
  ledger.review([]); // closed -> done
  const before = {
    tenure: ledger.tenure,
    milestone: ledger.milestone,
    successor: ledger.successor,
    obligations: ledger.obligations,
  };
  assert.throws(() => ledger.openSuccessor(), (error) => error.code === "INVALID_ARGUMENT");
  assert.deepEqual(
    {
      tenure: ledger.tenure,
      milestone: ledger.milestone,
      successor: ledger.successor,
      obligations: ledger.obligations,
    },
    before,
  );
});

test("a second operator message does not reopen a work that is already in progress", () => {
  const ledger = new ReviewLedger({ kind: "manager" });
  ledger.review([{ key: "a", value: "x" }]);
  ledger.review([{ key: "a", value: null }]); // relay
  ledger.openSuccessor();
  ledger.review([]); // confirm -> close
  ledger.review([]); // closed -> done
  assert.equal(ledger.reopen({ operator: true }).kind, REVIEW_KINDS.REOPENED);
  ledger.review([{ key: "tests", value: "rerun" }]);
  // The work is back in progress: a second message must not erase the ledger.
  assert.deepEqual(ledger.reopen({ operator: true }), {
    ok: false,
    rejection: REVIEW_REJECTIONS.NOT_FINISHED,
  });
  assert.deepEqual(ledger.obligations, [{ key: "tests", value: "rerun" }]);
});

test("a relayed successor that records obligations returns to normal work, and its next empty review relays", () => {
  // Root §2.4 第 5 步: the successor's FIRST review decides. A non-empty one is
  // the normal working period and spends the flag; when that tenure later
  // empties the ledger, the empty review is a RELAY again, not a promotion.
  // The tenure that found the work never certifies it.
  const ledger = new ReviewLedger({ kind: "manager" });
  ledger.review([{ key: "a", value: "x" }]);
  ledger.review([{ key: "a", value: null }]); // relay
  ledger.openSuccessor();
  assert.equal(ledger.successor, true);
  assert.equal(ledger.review([{ key: "tests", value: "rerun" }]).kind, REVIEW_KINDS.RECORDED);
  assert.equal(ledger.successor, false, "the successor's first review spends the flag");
  const relayed = ledger.review([{ key: "tests", value: null }]);
  assert.equal(relayed.kind, REVIEW_KINDS.RELAY);
  assert.equal(ledger.milestone, "execute", "the relay keeps the milestone");
  assert.equal(ledger.tenure, 1);
  // The relay opened the next tenure; its own first empty review promotes.
  ledger.openSuccessor();
  assert.equal(ledger.review([]).kind, REVIEW_KINDS.CONFIRM);
  assert.equal(ledger.milestone, "close");
});

test("a draft-less handoff does not spend the tenure's first-review credit", () => {
  const ledger = new ReviewLedger({ kind: "plan" });
  ledger.review([]); // askUser, first review of the first tenure
  // S1 → S2 → S3 → 待执行, each step a relay plus its successor's verdict.
  for (let step = 0; step < 3; step += 1) {
    ledger.review([]); // relay
    ledger.openSuccessor();
    ledger.review([]); // confirm
  }
  assert.equal(ledger.milestone, "待执行");
  const count = ledger.reviewCount;
  assert.equal(ledger.review([]).rejection, REVIEW_REJECTIONS.DRAFT_MISSING);
  assert.equal(ledger.reviewCount, count, "a refusal is not a review");
  assert.equal(ledger.milestone, "待执行");
  // The handoff itself also leaves the count alone and the Plan at 待执行.
  assert.equal(ledger.review([], "draft body").kind, REVIEW_KINDS.HANDOFF);
  assert.equal(ledger.reviewCount, count);
  assert.equal(ledger.milestone, "待执行");
  assert.equal(ledger.review([], "again").kind, REVIEW_KINDS.HANDOFF);
});

test("the plan's first review credit survives a refusal at 待执行", () => {
  // A Plan that reaches 待执行 without ever having a countable review would
  // otherwise turn its later reviews into `askUser`; the refusal must not do it.
  const ledger = new ReviewLedger({ kind: "plan" });
  ledger.review([]); // askUser (first tenure, first review)
  ledger.review([{ key: "k", value: "v" }]); // recorded
  ledger.review([{ key: "k", value: null }]); // relay
  for (let step = 0; step < 3; step += 1) {
    ledger.openSuccessor();
    ledger.review([]); // confirm: the successor promotes the milestone
    if (ledger.milestone !== "待执行") ledger.review([]); // relay for the next step
  }
  assert.equal(ledger.review([]).rejection, REVIEW_REJECTIONS.DRAFT_MISSING);
  assert.equal(ledger.review([], "draft").kind, REVIEW_KINDS.HANDOFF);
});

// ------------------------------------------- physical-call replay (§2.4 第 6 步)

test("a replayed review returns its own receipt instead of booking twice", () => {
  const ledger = new ReviewLedger({ kind: "manager" });
  const first = ledger.review([], null, { callId: "call-1" });
  // 根 §2.2: a Manager's first empty review is its own verification, not a
  // question — it never waits for a human.
  assert.equal(first.kind, REVIEW_KINDS.CONFIRM, "the first empty review verifies");
  const before = { reviews: ledger.reviewCount, tenure: ledger.tenure, milestone: ledger.milestone };

  // The same physical call arrives again with the same payload: the answer is
  // the one it already produced, and nothing is booked a second time.
  const again = ledger.review([], null, { callId: "call-1" });
  assert.equal(again.replayed, true);
  assert.equal(again.kind, first.kind);
  assert.deepEqual(
    { reviews: ledger.reviewCount, tenure: ledger.tenure, milestone: ledger.milestone },
    before,
  );

  // A new call with the same payload is a new beat, not a replay: the second
  // empty review of this tenure is the first real judgement, so it relays.
  const next = ledger.review([], null, { callId: "call-2" });
  assert.equal(next.replayed, undefined);
  assert.equal(next.kind, REVIEW_KINDS.RELAY);
  assert.equal(ledger.reviewCount, 2);
});

test("the same call id with another payload is refused and changes nothing", () => {
  const ledger = new ReviewLedger({ kind: "manager" });
  ledger.review([], null, { callId: "call-1" });
  const before = { reviews: ledger.reviewCount, tenure: ledger.tenure, milestone: ledger.milestone };
  const refused = ledger.review([{ key: "x", value: "do it" }], null, { callId: "call-1" });
  assert.equal(refused.ok, false);
  assert.equal(refused.rejection, REVIEW_REJECTIONS.REPLAY_MISMATCH);
  assert.deepEqual(
    { reviews: ledger.reviewCount, tenure: ledger.tenure, milestone: ledger.milestone },
    before,
  );
  assert.deepEqual(ledger.obligations, []);
});

test("a replay answers with its own receipt after a relay moved the tenure", () => {
  // §2.4 第 6 步 is a hard rule: the same physical call books once and every later
  // arrival answers with the receipt it produced. The ledger moving on — here a
  // relay that opens a successor — does not turn the repeat into a new call.
  const ledger = new ReviewLedger({ kind: "manager" });
  ledger.review([{ key: "a", value: "x" }], null, { callId: "relay-1" });
  const relayed = ledger.review([{ key: "a", value: null }], null, { callId: "relay-2" });
  assert.equal(relayed.kind, REVIEW_KINDS.RELAY);
  assert.equal(relayed.tenure, 0);
  ledger.openSuccessor();
  assert.equal(ledger.tenure, 1);

  const before = { reviews: ledger.reviewCount, tenure: ledger.tenure, milestone: ledger.milestone };
  const again = ledger.review([{ key: "a", value: null }], null, { callId: "relay-2" });
  assert.equal(again.replayed, true);
  assert.equal(again.kind, REVIEW_KINDS.RELAY);
  assert.equal(again.tenure, 0, "the receipt of THAT call, not a description of the ledger now");
  assert.deepEqual(
    { reviews: ledger.reviewCount, tenure: ledger.tenure, milestone: ledger.milestone },
    before,
    "a replay books nothing",
  );
});

test("a replay answers with its own receipt after later calls added obligations", () => {
  const ledger = new ReviewLedger({ kind: "manager" });
  const first = ledger.review([{ key: "a", value: null }], null, { callId: "call-1" });
  assert.equal(first.kind, REVIEW_KINDS.CONFIRM);
  // A different call books obligations afterwards.
  const second = ledger.review([{ key: "b", value: "later work" }], null, { callId: "call-2" });
  assert.equal(second.kind, REVIEW_KINDS.RECORDED);
  assert.deepEqual(ledger.obligations, [{ key: "b", value: "later work" }]);

  const before = { reviews: ledger.reviewCount, obligations: ledger.obligations };
  const again = ledger.review([{ key: "a", value: null }], null, { callId: "call-1" });
  assert.equal(again.replayed, true);
  assert.equal(again.kind, first.kind);
  assert.deepEqual(
    { reviews: ledger.reviewCount, obligations: ledger.obligations },
    before,
    "the later obligations are untouched and nothing is booked",
  );
});

test("a replay after a milestone moved still answers its own receipt", () => {
  const ledger = new ReviewLedger({ kind: "plan" });
  // The first empty review asks the operator; each later step is a relay whose
  // successor verifies, walking S1 → S2 → S3 → 待执行. The next empty review is
  // the handoff.
  ledger.review([], "# draft", { callId: "plan-first" });
  for (let step = 0; step < 3; step += 1) {
    assert.equal(ledger.review([], "# draft", { callId: `plan-relay-${step}` }).kind, REVIEW_KINDS.RELAY);
    ledger.openSuccessor();
    assert.equal(ledger.review([], "# draft", { callId: `plan-verdict-${step}` }).kind, REVIEW_KINDS.CONFIRM);
  }
  assert.equal(ledger.milestone, "待执行");
  const handoff = ledger.review([], "# draft", { callId: "plan-handoff" });
  assert.equal(handoff.kind, REVIEW_KINDS.HANDOFF);
  const before = ledger.reviewCount;
  const again = ledger.review([], "# draft", { callId: "plan-handoff" });
  assert.equal(again.replayed, true);
  assert.equal(again.kind, REVIEW_KINDS.HANDOFF);
  assert.equal(again.draft, "# draft");
  assert.equal(ledger.reviewCount, before);
});

test("a review without a call id is booked exactly as before", () => {
  const ledger = new ReviewLedger({ kind: "manager" });
  const first = ledger.review([]);
  const second = ledger.review([]);
  assert.equal(first.replayed, undefined);
  assert.equal(second.replayed, undefined);
  assert.equal(ledger.reviewCount, 2);
});
