import test from "node:test";
import assert from "node:assert/strict";
import fc from "fast-check";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import {
  ProgramRegistry,
  ProgramState,
  ControlSurface,
  STATES,
  CoreError,
  findSpans,
  whereParkPayload,
  nearestLine,
  TextMembers,
  ReviewLedger,
  ReviewWindow,
  WINDOW_STATES,
  REVIEW_REJECTIONS,
  REVIEW_KINDS,
  PLAN_MILESTONES,
  FileMembers,
  atomicWriteFile,
  deadlineFor,
  Deadline,
  fakeClock,
  CommandView,
  buildReceipt,
  programView,
  resumeValue,
  PARK_REASONS,
  normalizePath,
  isInsideRoot,
  stableJson,
  fakeCommandRunner,
  nodeCommandRunner,
  decodeUtf8,
} from "./index.mjs";
import { fcRuns } from "./test-runs.mjs";

const tempRoot = () => fs.mkdtemp(path.join(os.tmpdir(), "herd-core-"));

test("spans are exact, left to right, non-overlapping", () => {
  const text = "a a\na ";
  assert.deepEqual(findSpans(text, "a"), [[0, 1], [2, 3], [4, 5]]);
  assert.deepEqual(findSpans(text, "a "), [[0, 2], [4, 6]]);
  assert.deepEqual(findSpans(text, "A"), []);
  assert.deepEqual(findSpans("aaaa", "aa"), [[0, 2], [2, 4]]);
  // Nothing is trimmed or folded.
  assert.deepEqual(findSpans("x\r\ny", "x\n"), []);
  assert.throws(() => findSpans("x", ""), TypeError);
});

test("where parks on zero and several matches, answers on one", () => {
  const parked = [];
  const text = new TextMembers({ park: (payload) => parked.push(payload) });
  assert.deepEqual(text.where("x\ny", "y"), [2, 3]);
  assert.equal(parked.length, 0);
  text.where("yy", "y");
  assert.equal(parked.length, 1);
  assert.equal(parked[0].candidates.length, 2);
  text.where("z", "y");
  assert.deepEqual(parked[1].candidates, []);
  assert.ok("nearest" in parked[1]);
  assert.deepEqual(text.whereAll("yy", "y"), [[0, 1], [1, 2]]);
  assert.throws(() => text.where(1, "y"), (error) => error.code === "INVALID_ARGUMENT");
});

test("payload keeps nearest only for zero matches, bounded", () => {
  const payload = whereParkPayload("const a = 1;", "const b = 2;", []);
  assert.equal(payload.candidates.length, 0);
  assert.equal(payload.nearest.text, "const a = 1;");
  assert.equal(payload.nearest.distance, 2);
  const many = whereParkPayload("x\nx\nx\nx\nx\nx\nx\nx\nx\nx", "q", []);
  assert.equal(many.nearest.text, "x");
  // Only the first 2000 non-empty lines are considered. A needle 1500 lines
  // down is still found; the same needle 500 lines further is out of reach and
  // the best of the first 2000 lines is reported instead.
  const near = `${"z\n".repeat(1500)}needle-ish\n`;
  assert.equal(nearestLine(near, "needle-ish").distance, 0);
  const far = `${"z\n".repeat(2500)}needle-ish\n`;
  const found = nearestLine(far, "needle-ish");
  assert.notEqual(found.distance, 0);
  assert.equal(found.text, "z");
  assert.deepEqual(found.span, [0, 1]);
  assert.equal(nearestLine("a\n\nneedle-ish\n", "needle-ish").text, "needle-ish");
});

test("strict utf-8 decoding reports bad bytes", () => {
  assert.equal(decodeUtf8(Buffer.from("中", "utf8")), "中");
  assert.throws(() => decodeUtf8(Buffer.from([0xff, 0xfe])), (error) => error.code === "INVALID_UTF8");
});

test("registry refuses a live name and replays a settled call", () => {
  const registry = new ProgramRegistry();
  const program = registry.begin("p");
  assert.throws(() => registry.begin("p"), (error) => error.code === "NAME_TAKEN");
  const first = registry.record(program, "c1", { x: 1 }, () => ({ ok: true }));
  assert.equal(first.replayed, false);
  const second = registry.record(program, "c1", { x: 1 }, () => ({ ok: false }));
  assert.deepEqual(second.receipt, { ok: true });
  assert.equal(second.replayed, true);
  assert.throws(
    () => registry.record(program, "c1", { x: 2 }, () => null),
    (error) => error.code === "CALL_REPLAY_MISMATCH",
  );
  program.finish(STATES.COMPLETED, 1);
  assert.equal(registry.retire("p"), program);
  assert.equal(registry.begin("p").state, STATES.RUNNING);
});

test("a retired name replays its old receipt instead of starting again", () => {
  const registry = new ProgramRegistry();
  const program = registry.begin("r");
  registry.record(program, "call-1", { code: "x" }, () => ({ state: "completed", value: 1 }));
  program.finish(STATES.COMPLETED, 1);
  assert.equal(registry.retire("r"), program);
  assert.deepEqual(registry.replayRetired("call-1", { code: "x" }), {
    receipt: { state: "completed", value: 1 },
    replayed: true,
  });
  assert.throws(
    () => registry.replayRetired("call-1", { code: "y" }),
    (error) => error.code === "CALL_REPLAY_MISMATCH",
  );
  assert.throws(
    () => registry.replayRetired("call-2", { code: "x" }),
    (error) => error.code === "PROGRAM_UNKNOWN",
  );
  const next = registry.begin("r");
  assert.notEqual(next, program);
  assert.deepEqual(registry.list().map((item) => item.name), ["r"]);
  assert.equal(registry.live("r"), next);
  assert.equal(registry.live("nope"), null);
});

test("resume checks in order and is single-shot", () => {
  const registry = new ProgramRegistry();
  // The required set is the target's own (spec §3.3); a target that requires
  // nothing would make every controller pass and hide the subset rule below.
  const target = registry.begin("t", { capabilities: ["a", "b"] });
  const control = new ControlSurface(registry);
  control.open("c");
  // 1. Observation first: an unobserved self-target is STALE_CONTROL.
  assert.throws(
    () => control.unpark({ controllerId: "c", name: "t" }),
    (error) => error.code === "STALE_CONTROL",
  );
  assert.throws(
    () => control.unpark({ controllerId: "c", name: "c" }),
    (error) => error.code === "STALE_CONTROL",
  );
  control.observe("c", "t");
  control.observe("c", "c");
  // 2. Self-target, 3. existence.
  assert.throws(
    () => control.unpark({ controllerId: "c", name: "c" }),
    (error) => error.code === "INVALID_ARGUMENT",
  );
  assert.throws(
    () => control.unpark({ controllerId: "c", name: "ghost" }),
    (error) => error.code === "STALE_CONTROL",
  );
  control.observe("c", "ghost");
  assert.throws(
    () => control.unpark({ controllerId: "c", name: "ghost" }),
    (error) => error.code === "PROGRAM_UNKNOWN",
  );
  target.park({ reason: PARK_REASONS.EXPLICIT, payload: null });
  // 4a. A stated set that is not the target's own is refused: a controller
  // cannot lower the bar by naming a smaller required set.
  assert.throws(
    () => control.unpark({ controllerId: "c", name: "t", capabilities: ["a"], targetCapabilities: ["b"] }),
    (error) => error.code === "CAPABILITY_DENIED",
  );
  // 4b. With the target's real set required, a controller holding only part of
  // it is denied by the subset test itself.
  assert.throws(
    () => control.unpark({ controllerId: "c", name: "t", capabilities: ["a"] }),
    (error) => error.code === "CAPABILITY_DENIED",
  );
  const resumed = control.unpark({
    controllerId: "c",
    name: "t",
    value: [3, 4],
    capabilities: ["a", "b", "c"],
  });
  assert.deepEqual(resumed.value, [3, 4]);
  assert.equal(target.state, STATES.RUNNING);
  // 5. Still parked is checked last, after the capability set. Spec §3.3 fixes
  // this order ("全部通过后把目标从 `parked` 切回 `running`"), so a controller
  // that does not hold the set is told so even about a program that has already
  // resumed: it never learns the program's state it is not entitled to.
  assert.throws(
    () => control.unpark({ controllerId: "c", name: "t", value: [3, 4] }),
    (error) => error.code === "CAPABILITY_DENIED",
  );
  // A controller holding the set reaches the state check, which is what makes
  // the resume single-shot: the second resume of one site is STALE_CONTROL.
  assert.throws(
    () =>
      control.unpark({
        controllerId: "c",
        name: "t",
        value: [3, 4],
        capabilities: ["a", "b", "c"],
      }),
    (error) => error.code === "STALE_CONTROL",
  );
});

test("handles lists only this controller's unsettled parks", () => {
  const registry = new ProgramRegistry();
  const control = new ControlSurface(registry);
  const parked = registry.begin("b");
  parked.park({ reason: PARK_REASONS.EXPLICIT, payload: null });
  registry.begin("a").park({ reason: PARK_REASONS.EXPLICIT, payload: null });
  registry.begin("live");
  control.open("c");
  control.observe("c", "b");
  assert.deepEqual(control.handles("c"), ["b"]);
  assert.deepEqual(control.handles("nobody"), []);
});

test("resume value routing: only a command site keeps its own result", () => {
  assert.deepEqual(resumeValue({ reason: PARK_REASONS.EXPLICIT }, [1, 2]), [1, 2]);
  // `this.ask` is `this.park` + `interrupt`: the operator's reply is the
  // injected value, exactly as for an explicit park.
  assert.equal(resumeValue({ reason: PARK_REASONS.ASK }, "the answer"), "the answer");
  // A host that recorded the answer on the site still answers when no value is
  // injected, and an injected value wins over the recorded one.
  assert.equal(resumeValue({ reason: PARK_REASONS.ASK, value: "recorded" }, null), "recorded");
  assert.equal(resumeValue({ reason: PARK_REASONS.ASK, value: "recorded" }, "injected"), "injected");
  // A command receipt is produced by the command; an injected one would forge
  // an execution that never happened.
  assert.equal(resumeValue({ reason: PARK_REASONS.COMMAND, value: { exitCode: 0 } }, "x").exitCode, 0);
  assert.equal(resumeValue({ reason: PARK_REASONS.SDK }, "injected"), "injected");
  const registry = new ProgramRegistry();
  const control = new ControlSurface(registry);
  const target = registry.begin("v");
  target.park({ reason: PARK_REASONS.EXPLICIT, payload: null });
  control.open("c");
  control.observe("c", "v");
  assert.equal(control.unpark({ controllerId: "c", name: "v" }).value, null);
});

test("draining resolves when the last operation ends", async () => {
  const program = new ProgramState("d");
  let drained = false;
  const first = program.beginOperation();
  const second = program.beginOperation();
  const waiting = program.drainedPromise().then(() => {
    drained = true;
  });
  program.endOperation(first);
  await Promise.resolve();
  assert.equal(drained, false);
  program.endOperation(second);
  await waiting;
  assert.equal(drained, true);
  // An id is required: no call may guess which operation it means.
  assert.throws(() => program.endOperation(), (error) => error.code === "INVALID_ARGUMENT");
  assert.throws(() => program.endOperation(first), (error) => error.code === "INVALID_ARGUMENT");
});

test("a drain names its predecessors by id: it skips none of them and waits for none of its own", async () => {
  // The self-deadlock this prevents: an expression running while another
  // operation is open waits for the earlier work only. Counting would have to
  // guess which open operations are earlier; an id states it.
  const program = new ProgramState("lvl");
  const earlier = program.beginOperation(); // an SDK operation the caller did not start
  const self = program.beginOperation(); // the caller's own operation
  let drained = false;
  const waiting = program.drainedPromise(self).then(() => {
    drained = true;
  });
  // The caller's own operation is not waited for, and the earlier one still is.
  await Promise.resolve();
  assert.equal(drained, false, "the earlier operation is a predecessor");
  program.endOperation(earlier);
  await waiting;
  assert.equal(drained, true);
  // The caller settles its own operation; only then can anything newer proceed.
  program.endOperation(self);
  assert.equal(program.pendingOperations, 0);
  // A later operation is not a predecessor of anything newer: with nothing open
  // before it, a drain at its own id is satisfied at once.
  const later = program.beginOperation();
  let second = false;
  const afterLater = program.drainedPromise(later).then(() => {
    second = true;
  });
  await afterLater;
  assert.equal(second, true, "nothing earlier than the later operation is open");
  assert.equal(program.pendingOperations, 1);
  program.endOperation(later);
  assert.throws(() => program.drainedPromise(0), (error) => error.code === "INVALID_ARGUMENT");
  assert.throws(() => program.endOperation(self), (error) => error.code === "INVALID_ARGUMENT");
});

test("finishing a run releases every drain waiter", async () => {
  const program = new ProgramState("rel");
  const first = program.beginOperation();
  const second = program.beginOperation();
  let released = false;
  const waiting = program.drainedPromise().then(() => {
    released = true;
  });
  program.finish(STATES.COMPLETED, 1);
  await waiting;
  assert.equal(released, true);
  // The operations that started earlier still settle, and do not throw.
  program.endOperation(first);
  program.endOperation(second);
});

test("a park cancels the run's deadline and a resume replaces it", () => {
  const clock = fakeClock(0);
  const registry = new ProgramRegistry();
  const first = Deadline.forIntent(clock, "abc"); // 150 ms
  const target = registry.begin("t", { deadline: first });
  clock.advance(100);
  assert.equal(target.remainingWaitMs(), 50);
  target.park({ reason: PARK_REASONS.EXPLICIT, payload: null });
  assert.equal(target.remainingWaitMs(), 0);
  assert.equal(first.cancelled, true);

  const control = new ControlSurface(registry);
  control.open("c");
  control.observe("c", "t");
  const controller = Deadline.forIntent(clock, "0123456789");
  control.unpark({ controllerId: "c", name: "t", value: 1, deadline: controller });
  assert.equal(target.remainingWaitMs(), 500);
  clock.advance(200);
  assert.equal(target.remainingWaitMs(), 300);
  target.park({ reason: PARK_REASONS.EXPLICIT, payload: null });
  control.observe("c", "t");
  control.unpark({ controllerId: "c", name: "t", value: 2 });
  assert.equal(target.remainingWaitMs(), 0);
});

test("a parked or finished program accepts no new expressions", () => {
  const program = new ProgramState("s");
  assert.equal(program.acceptsExpressions, true);
  program.beginOperation();
  // An open operation alone is not draining: that operation is why the program
  // may continue, and counting it here would deadlock the caller.
  assert.equal(program.acceptsExpressions, true);
  assert.equal(program.draining, false);
  program.finish(STATES.COMPLETED, 1);
  assert.equal(program.acceptsExpressions, false);
  const parked = new ProgramState("p");
  parked.park({ reason: PARK_REASONS.EXPLICIT, payload: null });
  assert.equal(parked.acceptsExpressions, false);
  parked.takeSite();
  assert.equal(parked.acceptsExpressions, true);
});

test("a draining program accepts no new expression (spec §3.3)", async () => {
  const program = new ProgramState("dr");
  const earlier = program.beginOperation();
  const self = program.beginOperation();
  // The drain names the operations before `self`, so it excludes the caller's
  // own operation and waits for the one that preceded it.
  let drained = false;
  const waiting = program.drainedPromise(self).then(() => {
    drained = true;
  });
  // Draining is now observable: the program takes no new expression while an
  // expression waits for the earlier work.
  assert.equal(program.draining, true);
  assert.equal(program.acceptsExpressions, false);
  assert.equal(program.state, STATES.RUNNING);
  program.endOperation(earlier);
  await waiting;
  assert.equal(drained, true);
  assert.equal(program.draining, false);
  assert.equal(program.acceptsExpressions, true);
  program.endOperation(self);
});

test("a finished program keeps its result and refuses to move", () => {
  const program = new ProgramState("f");
  const straggler = program.beginOperation();
  program.finish(STATES.COMPLETED, { ok: true });
  assert.deepEqual(program.result, { ok: true });
  assert.equal(program.drained, false);
  program.endOperation(straggler);
  assert.equal(program.drained, true);
  assert.equal(program.acceptsExpressions, false);
  assert.throws(() => program.park({ reason: PARK_REASONS.EXPLICIT }), (e) => e.code === "INVALID_ARGUMENT");
  assert.equal(program.state, STATES.COMPLETED);
  assert.deepEqual(program.result, { ok: true });
  // A straggling transition is a no-op, so a late `call` finally cannot reopen it.
  assert.equal(program.moveTo(STATES.RUNNING), STATES.COMPLETED);
  assert.equal(program.state, STATES.COMPLETED);
  assert.throws(() => program.finish(STATES.FAILED, null), (e) => e.code === "INVALID_ARGUMENT");
  const clock = fakeClock(0);
  const deadline = Deadline.forIntent(clock, "ab");
  const timed = new ProgramState("g", { deadline });
  timed.finish(STATES.COMPLETED, null);
  assert.equal(deadline.cancelled, true);
  assert.equal(timed.remainingWaitMs(), 0);
});

test("waiting returns to running and only a park settles a site", () => {
  const program = new ProgramState("w");
  program.moveTo(STATES.WAITING);
  assert.equal(program.state, STATES.WAITING);
  program.moveTo(STATES.RUNNING);
  assert.equal(program.state, STATES.RUNNING);
  program.park({ reason: PARK_REASONS.COMMAND, payload: null, value: { exitCode: 0 } });
  assert.throws(() => program.park({ reason: PARK_REASONS.EXPLICIT }), (e) => e.code === "INVALID_ARGUMENT");
  const site = program.takeSite();
  assert.deepEqual(site.value, { exitCode: 0 });
  assert.throws(() => program.takeSite(), (e) => e.code === "STALE_CONTROL");
});

test("the review window exposes `open` as a boolean, never as a method", () => {
  const window = new ReviewWindow({ kind: "manager" });
  // The property is the gate every adapter reads: a method of the same name
  // would shadow it and make `window.open === true` answer "shut" forever.
  assert.equal(typeof window.open, "boolean");
  assert.equal(window.open, false, "a window starts shut");
  assert.equal(window.state, WINDOW_STATES.CLOSED);

  // `beginRound` is the round boundary and is idempotent within a round.
  assert.equal(window.beginRound(), WINDOW_STATES.OPEN);
  assert.equal(window.open, true);
  assert.equal(window.beginRound(), WINDOW_STATES.OPEN);
  assert.equal(window.round, 1);

  // A refused review leaves it open; the round's first accepted one shuts it.
  window.noteReview({ ok: false, kind: "rejected" });
  assert.equal(window.open, true);
  window.noteReview({ ok: true, kind: "recorded" });
  assert.equal(window.open, false);
  assert.equal(window.reviews, 1);
  // A second acceptance in the same round changes nothing.
  window.noteReview({ ok: true, kind: "recorded" });
  assert.equal(window.open, false);
  assert.equal(window.reviews, 2);

  // A new round opens it again, and its first acceptance shuts it again.
  assert.equal(window.beginRound(), WINDOW_STATES.OPEN);
  assert.equal(window.open, true);
  window.noteReview({ ok: true, kind: "recorded" });
  assert.equal(window.open, false);
});

test("a plan window never opens and a relay closes the round it names", () => {
  const plan = new ReviewWindow({ kind: "plan" });
  plan.beginRound();
  assert.equal(plan.state, WINDOW_STATES.NOT_MANAGER);
  assert.equal(plan.open, false);

  const manager = new ReviewWindow({ kind: "manager" });
  manager.beginRound();
  assert.equal(manager.relay(1), WINDOW_STATES.CLOSED);
  assert.equal(manager.open, false);
  assert.equal(manager.tenure, 1);
  // The round that the relay closed cannot be reopened by a review, only by a
  // later round.
  manager.noteReview({ ok: true, kind: "recorded" });
  assert.equal(manager.open, false);
  assert.equal(manager.beginRound(), WINDOW_STATES.OPEN);
  assert.equal(manager.open, true);
});

test("review relay, confirm, close and reopen", () => {
  const ledger = new ReviewLedger({ kind: "manager" });
  assert.equal(ledger.review([{ key: "a", value: "x" }]).kind, "recorded");
  const relay = ledger.review([{ key: "a", value: null }]);
  assert.equal(relay.kind, "relay");
  assert.equal(relay.retire && relay.openSuccessor && relay.barrier, true);
  ledger.openSuccessor();
  assert.equal(ledger.milestone, "execute");
  assert.equal(ledger.review([]).kind, "confirm");
  assert.equal(ledger.milestone, "close");
  assert.equal(ledger.review([]).kind, "closed");
  assert.equal(ledger.review([]).rejection, REVIEW_REJECTIONS.ALREADY_DONE);
  assert.throws(() => ledger.openSuccessor(), (error) => error.code === "INVALID_ARGUMENT");
  assert.deepEqual(ledger.reopen({ operator: false }), {
    ok: false,
    rejection: REVIEW_REJECTIONS.AGENT_MESSAGE,
  });
  assert.equal(ledger.reopen({ operator: true }).kind, "reopened");
});

test("manager closing waits for real resources", () => {
  let settled = false;
  const ledger = new ReviewLedger({ kind: "manager", resourcesSettled: () => settled });
  ledger.review([{ key: "a", value: "x" }]);
  ledger.review([{ key: "a", value: null }]);
  ledger.openSuccessor();
  ledger.review([]); // confirm -> close
  const pending = ledger.review([]);
  assert.equal(pending.kind, "nextReview");
  assert.equal(pending.reason, "RESOURCES_UNSETTLED");
  assert.equal(ledger.milestone, "close");
  settled = true;
  assert.equal(ledger.review([]).kind, "closed");
});

test("plan walks the same relay timeline as manager and hands off only with the saved draft", () => {
  // 根 §2.4 第 1–5 步 apply to BOTH sides. Plan's first empty review is the `S1`
  // question and moves nothing; from then on an empty review is a RELAY, and
  // the successor's first empty review promotes it one milestone.
  const ledger = new ReviewLedger({ kind: "plan" });
  assert.equal(ledger.review([]).kind, "askUser");
  assert.equal(ledger.milestone, "S1");
  // S1 -> S2: the incumbent relays, the successor verifies.
  const s1 = ledger.review([]);
  assert.equal(s1.kind, "relay");
  assert.equal(s1.milestone, "S1");
  assert.equal(ledger.successor, false, "the ledger is not a successor until the host opens one");
  ledger.openSuccessor();
  assert.equal(ledger.successor, true);
  assert.equal(ledger.review([]).kind, "confirm");
  assert.equal(ledger.milestone, "S2");
  // A non-empty ledger is the normal work period: the milestone never moves.
  ledger.review([{ key: "k", value: "v" }]);
  assert.equal(ledger.milestone, "S2");
  // S2 -> S3, the same two steps.
  assert.equal(ledger.review([{ key: "k", value: null }]).kind, "relay");
  assert.equal(ledger.milestone, "S2");
  ledger.openSuccessor();
  assert.equal(ledger.review([]).kind, "confirm");
  assert.equal(ledger.milestone, "S3");
  // S3 -> 待执行: the successor's verification is what reaches the ready stage.
  assert.equal(ledger.review([]).kind, "relay");
  ledger.openSuccessor();
  assert.equal(ledger.successor, true);
  assert.equal(ledger.review([]).kind, "confirm");
  assert.equal(ledger.milestone, "待执行");
  assert.equal(ledger.successor, false, "the promotion consumed the successor's first review");
  assert.equal(ledger.tenure, 3);
  // 待执行 accepts the hand-off and nothing else.
  assert.equal(ledger.review([], "").rejection, REVIEW_REJECTIONS.DRAFT_MISSING);
  assert.equal(ledger.milestone, "待执行");
  const handoff = ledger.review([], "the draft");
  assert.equal(handoff.kind, "handoff");
  assert.equal(handoff.draft, "the draft");
  assert.equal(ledger.milestone, "待执行");
});

test("a plan at 待执行 refuses a non-empty ledger and changes nothing", () => {
  const ledger = new ReviewLedger({ kind: "plan" });
  ledger.review([]); // askUser
  for (let step = 0; step < 3; step += 1) {
    ledger.review([]);
    ledger.openSuccessor();
    ledger.review([]);
  }
  assert.equal(ledger.milestone, "待执行");
  const before = { reviews: ledger.reviewCount, tenure: ledger.tenure, obligations: ledger.obligations };
  const refused = ledger.review([{ key: "more", value: "work" }]);
  assert.equal(refused.ok, false);
  assert.equal(refused.kind, REVIEW_KINDS.REJECTED);
  assert.equal(refused.rejection, REVIEW_REJECTIONS.ALREADY_READY);
  assert.deepEqual(
    { reviews: ledger.reviewCount, tenure: ledger.tenure, obligations: ledger.obligations },
    before,
    "a refused review leaves no obligation and books nothing",
  );
  // The hand-off is still the only way forward, and it still works.
  assert.equal(ledger.review([], "draft").kind, REVIEW_KINDS.HANDOFF);
});

test("a handoff sends the saved draft and nothing else", () => {
  const saved = "# draft\n\nsaved body\n";
  const atReady = () => {
    const ledger = new ReviewLedger({ kind: "plan" });
    ledger.review([]); // askUser (S1, first tenure)
    // S1 → S2 → S3 → 待执行, one relay and one successor verdict per step.
    for (let step = 0; step < 3; step += 1) {
      assert.equal(ledger.review([]).kind, REVIEW_KINDS.RELAY);
      ledger.openSuccessor();
      assert.equal(ledger.review([]).kind, REVIEW_KINDS.CONFIRM);
    }
    assert.equal(ledger.milestone, "待执行");
    return ledger;
  };

  // The saved draft goes byte for byte, spaces and newlines included.
  const spaced = "  spaced draft  \n";
  const handed = atReady().review([], spaced);
  assert.equal(handed.kind, REVIEW_KINDS.HANDOFF);
  assert.equal(handed.draft, spaced);

  // An empty saved draft is not a draft: the handoff is refused, and nothing
  // is substituted for it.
  const empty = atReady().review([], "");
  assert.equal(empty.kind, REVIEW_KINDS.REJECTED);
  assert.equal(empty.rejection, REVIEW_REJECTIONS.DRAFT_MISSING);

  // A source that is not a string is not a draft either.
  for (const bogus of [0, false, {}, []]) {
    const receipt = atReady().review([], bogus);
    assert.equal(receipt.rejection, REVIEW_REJECTIONS.DRAFT_MISSING);
  }

  // No source at all: nothing to send, so the handoff is refused.
  assert.equal(atReady().review([]).rejection, REVIEW_REJECTIONS.DRAFT_MISSING);
});

// ------------------------------------------------- Plan's §2.4 timeline, as properties
//
// 根 §2.2 gives Plan and Manager each their own ledger and says the relay rules
// apply to both; §2.4 states one timeline. These properties assert the timeline
// itself rather than a hand-walked path, so a shortcut that advances on an
// incumbent's empty review fails here whatever sequence of findings produced
// it.

// One finding as a `review` argument: a key with a value, or the same key
// released.
const findingArb = fc.record({
  key: fc.stringMatching(/^[a-z][a-z0-9-]{0,8}$/),
  value: fc.oneof(fc.stringMatching(/^[a-z][a-z0-9 .,-]{0,24}$/), fc.constant(null)),
});

// A sequence of reviews. Empty ones are the interesting ones (they are the
// decision point), so they are drawn as often as recorded findings.
const planReviewArb = fc.array(fc.oneof(fc.constant([]), fc.array(findingArb, { minLength: 1, maxLength: 3 })), {
  minLength: 1,
  maxLength: 24,
});

// One review of a Plan, plus the host's own step when the receipt asks for it:
// a relay is performed by `openSuccessor`, exactly as `compose/relay/review.mjs`
// does after a host reports a complete hand-over.
const planReview = (ledger, findings) => {
  const receipt = ledger.review(findings, "the draft");
  if (receipt.ok === true && receipt.kind === REVIEW_KINDS.RELAY) ledger.openSuccessor();
  return receipt;
};

test("property: a Plan's milestone advances only through a successor's first empty review", () => {
  fc.assert(
    fc.property(planReviewArb, (reviews) => {
      const ledger = new ReviewLedger({ kind: "plan" });
      let previous = ledger.milestoneIndex;
      for (const findings of reviews) {
        const wasSuccessor = ledger.successor;
        const sizeBefore = ledger.obligations.length;
        const receipt = planReview(ledger, findings);
        const now = ledger.milestoneIndex;
        // A milestone never moves backwards, and never by more than one.
        assert.ok(now >= previous && now - previous <= 1, `${previous} -> ${now}`);
        if (now !== previous) {
          // The ONLY transition that moves a milestone is a successor's first
          // empty review: a `confirm` by a tenure that was the successor, on an
          // EMPTY ledger. "Empty" is the ledger's own fact — `review([])` on a
          // non-empty ledger changes nothing (`recorded`, `added: 0`), and a
          // finding that deletes an absent key leaves it empty too — so the
          // size before the call is what proves it, not the findings' shape.
          assert.equal(wasSuccessor, true, "only a successor promotes");
          assert.equal(receipt.kind, REVIEW_KINDS.CONFIRM);
          assert.equal(sizeBefore, 0, "only an empty ledger promotes");
        }
        // Nothing else advances: a relay keeps the milestone (it copies it to
        // the successor), and a recorded review is the normal work period.
        if (receipt.ok === true && receipt.kind === REVIEW_KINDS.RELAY) {
          assert.equal(now, previous, "a relay keeps the milestone");
        }
        if (receipt.kind === REVIEW_KINDS.RECORDED) assert.equal(now, previous);
        previous = now;
      }
    }),
    { ...fcRuns(300) },
  );
});

test("property: a Plan relay always opens a successor at the same milestone", () => {
  fc.assert(
    fc.property(planReviewArb, (reviews) => {
      const ledger = new ReviewLedger({ kind: "plan" });
      for (const findings of reviews) {
        const before = { milestone: ledger.milestone, tenure: ledger.tenure };
        const wasSuccessor = ledger.successor;
        const receipt = planReview(ledger, findings);
        if (receipt.ok !== true || receipt.kind !== REVIEW_KINDS.RELAY) continue;
        // §2.4 第 3 步: one atomic step — retirement, barrier, successor.
        assert.deepEqual(
          { retire: receipt.retire, openSuccessor: receipt.openSuccessor, barrier: receipt.barrier },
          { retire: true, openSuccessor: true, barrier: true },
        );
        // The relay only ever belongs to an incumbent: a successor's first
        // empty review promotes instead.
        assert.equal(wasSuccessor, false, "a successor promotes, it does not relay");
        // The milestone is copied unchanged and the tenure is the next one.
        assert.equal(receipt.milestone, before.milestone);
        assert.equal(ledger.milestone, before.milestone);
        assert.equal(ledger.successor, true);
        assert.equal(ledger.tenure, before.tenure + 1);
        assert.equal(ledger.empty, true);
        assert.equal(ledger.reviewCount, 0, "the successor starts with a fresh review count");
      }
    }),
    { ...fcRuns(300) },
  );
});

test("property: a Plan at 待执行 refuses every non-empty ledger and changes nothing", () => {
  // The ready stage is reached only the legal way, so the property starts from a
  // real ready ledger: a successor's promotion out of S3.
  const atReady = () => {
    const ledger = new ReviewLedger({ kind: "plan" });
    ledger.review([]); // askUser (S1, first tenure)
    for (let step = 0; step < 3; step += 1) {
      assert.equal(ledger.review([], "the draft").kind, REVIEW_KINDS.RELAY);
      ledger.openSuccessor();
      assert.equal(ledger.review([], "the draft").kind, REVIEW_KINDS.CONFIRM);
    }
    assert.equal(ledger.milestone, PLAN_MILESTONES.at(-1));
    return ledger;
  };
  fc.assert(
    fc.property(fc.array(findingArb, { minLength: 1, maxLength: 4 }), (findings) => {
      const ledger = atReady();
      const before = { reviews: ledger.reviewCount, tenure: ledger.tenure, obligations: ledger.obligations };
      const receipt = ledger.review(findings, "the draft");
      // The rule is about the LEDGER, not the payload: a list of `null`s that
      // releases keys nobody holds leaves the ledger empty, and an empty ledger
      // at 待执行 is the hand-off, whatever was written in the call.
      const applied = new ReviewLedger({ kind: "plan" });
      applied.apply(findings);
      if (applied.empty) {
        assert.equal(receipt.kind, REVIEW_KINDS.HANDOFF);
        assert.equal(receipt.draft, "the draft");
      } else {
        assert.equal(receipt.ok, false);
        assert.equal(receipt.kind, REVIEW_KINDS.REJECTED);
        assert.equal(receipt.rejection, REVIEW_REJECTIONS.ALREADY_READY);
        assert.equal(receipt.milestone, PLAN_MILESTONES.at(-1));
      }
      assert.deepEqual(
        { reviews: ledger.reviewCount, tenure: ledger.tenure, obligations: ledger.obligations },
        before,
        "a refusal leaves no obligation and books nothing",
      );
      // The hand-off still works afterwards: the refusal did not spend the
      // ready stage.
      assert.equal(ledger.review([], "the draft").kind, REVIEW_KINDS.HANDOFF);
    }),
    { ...fcRuns(100) },
  );
});

test("property: Plan's exit is reachable, and only after a successor's promotion", () => {
  // 根 §2.2: Plan's exit is the successor's verification of the previous tenure
  // (the promotion into 待执行) plus the submitted hand-off. Core must not import
  // compose, so the predicate is restated here as the two facts the ledger
  // produces; `src/compose/nudge.test.mjs` pins `verifiedCompletion` itself.
  //
  // The verification is the TENURE, not the `successor` flag: the promotion IS
  // the successor's first review, and that review is the one that clears the
  // flag. `tenure >= 1` says a tenure after the first one is judging, which is
  // exactly what 根 §2.4 第 5 步 produces.
  const planExit = ({ milestone, tenure, handedOff }) =>
    milestone === PLAN_MILESTONES.at(-1) && tenure >= 1 && handedOff === true;
  fc.assert(
    fc.property(planReviewArb, (reviews) => {
      const ledger = new ReviewLedger({ kind: "plan" });
      let handedOff = false;
      const seen = [];
      for (const findings of reviews) {
        const wasSuccessor = ledger.successor;
        const before = ledger.milestoneIndex;
        const receipt = planReview(ledger, findings);
        const promoted = ledger.milestoneIndex !== before && wasSuccessor;
        if (receipt.kind === REVIEW_KINDS.HANDOFF) handedOff = true;
        const state = { milestone: ledger.milestone, tenure: ledger.tenure, handedOff };
        // 待执行 is unreachable for the first tenure: every empty review below it
        // relays, and only a successor's verdict promotes.
        if (ledger.milestone === PLAN_MILESTONES.at(-1)) {
          assert.ok(ledger.tenure >= 1, "待执行 is only reached by a tenure after the first");
        }
        if (promoted) assert.ok(ledger.tenure >= 1);
        // The hand-off is only ever submitted from the ready stage.
        if (handedOff) assert.equal(ledger.milestone, PLAN_MILESTONES.at(-1));
        // A stop is legal only in the exit state; below it there is no exit.
        if (ledger.milestone !== PLAN_MILESTONES.at(-1)) {
          assert.equal(planExit(state), false, "a Plan below 待执行 has no exit");
        }
        seen.push(state);
      }
      // If the sequence ever reached the ready stage with a hand-off, the exit
      // predicate answered true there and nowhere earlier.
      for (const state of seen) {
        if (planExit(state)) assert.equal(state.milestone, PLAN_MILESTONES.at(-1));
      }
    }),
    { ...fcRuns(300) },
  );

  // And it IS reachable: the one legal path produces it.
  const ledger = new ReviewLedger({ kind: "plan" });
  ledger.review([]); // askUser
  for (let step = 0; step < 3; step += 1) {
    assert.equal(ledger.review([], "the draft").kind, REVIEW_KINDS.RELAY);
    ledger.openSuccessor();
    assert.equal(ledger.review([], "the draft").kind, REVIEW_KINDS.CONFIRM);
  }
  assert.equal(ledger.milestone, PLAN_MILESTONES.at(-1));
  assert.equal(ledger.successor, false, "the promotion consumed the successor's first review");
  assert.equal(ledger.review([], "the draft").kind, REVIEW_KINDS.HANDOFF);
  assert.equal(ledger.milestone, PLAN_MILESTONES.at(-1));
  assert.equal(planExit({ milestone: ledger.milestone, tenure: ledger.tenure, handedOff: true }), true);
});

test("a finished side has no successor to open", () => {
  // A relay never happens at the last milestone: Plan hands off, Manager
  // closes. Both refusals are decided before anything is written.
  const plan = new ReviewLedger({ kind: "plan" });
  plan.review([]);
  for (let step = 0; step < 3; step += 1) {
    plan.review([]);
    plan.openSuccessor();
    plan.review([]);
  }
  assert.equal(plan.milestone, PLAN_MILESTONES.at(-1));
  assert.throws(() => plan.openSuccessor(), (error) => error.code === "INVALID_ARGUMENT");
  assert.throws(
    () => plan.adoptSuccessor({ tenure: 4, milestone: PLAN_MILESTONES.at(-1) }),
    (error) => error.code === "INVALID_ARGUMENT",
  );
  assert.equal(plan.tenure, 3, "a refused adoption writes nothing");
});

test("write replaces atomically and leaves no temp file", async () => {
  const root = await tempRoot();
  const files = new FileMembers({ root });
  assert.deepEqual(await files.stat("a.txt"), { exists: false });
  await files.write("a.txt", "one");
  await files.write("a.txt", "two");
  assert.equal((await files.cat("a.txt")).text, "two");
  assert.deepEqual((await files.ls("")).entries, ["a.txt"]);
  await files.mkdir("dir");
  assert.deepEqual((await files.ls("")).entries, ["a.txt", "dir/"]);
  assert.equal((await files.mv("a.txt", "dir/b.txt")).ok, true);
  assert.equal((await files.cat("dir/b.txt")).text, "two");
  assert.equal((await files.rm("dir", { recursive: true })).ok, true);
  assert.equal((await files.cat("dir/b.txt")).ok, false);
  await fs.rm(root, { recursive: true, force: true });
});

test("a stale temp file is never reused and the target is replaced", async () => {
  const root = await tempRoot();
  const target = path.join(root, "new.txt");
  assert.equal((await atomicWriteFile(target, "first")).ok, true);
  assert.equal(await fs.readFile(target, "utf8"), "first");
  assert.equal((await atomicWriteFile(target, "second")).ok, true);
  assert.equal(await fs.readFile(target, "utf8"), "second");
  assert.deepEqual((await fs.readdir(root)).sort(), ["new.txt"]);
  const missing = await atomicWriteFile(path.join(root, "gone", "x.txt"), "third");
  assert.equal(missing.ok, false);
  assert.equal(missing.code, "ENOENT");
  assert.deepEqual((await fs.readdir(root)).sort(), ["new.txt"]);
  await fs.rm(root, { recursive: true, force: true });
});

test("a reader never sees a half-written file", async () => {
  const root = await tempRoot();
  const files = new FileMembers({ root });
  const small = "a";
  const large = "b".repeat(200000);
  await files.write("race.txt", small);
  const seen = new Set();
  let running = true;
  const reader = (async () => {
    while (running) {
      const text = await fs.readFile(path.join(root, "race.txt"), "utf8");
      seen.add(text === small ? "small" : text === large ? "large" : "torn");
      await new Promise((resolve) => setImmediate(resolve));
    }
  })();
  for (let i = 0; i < 20; i += 1) {
    await files.write("race.txt", i % 2 === 0 ? large : small);
  }
  running = false;
  await reader;
  assert.equal(seen.has("torn"), false, `torn reads: ${[...seen].join(",")}`);
  assert.deepEqual((await fs.readdir(root)).sort(), ["race.txt"]);
  await fs.rm(root, { recursive: true, force: true });
});

test("guard refuses before any access and onWrite sees the program path", async () => {
  const root = await tempRoot();
  const writes = [];
  const files = new FileMembers({
    root,
    guard: (target) => (target.startsWith("secret") ? { ok: false, code: "EACCES", path: target } : null),
    onWrite: (target) => writes.push(target),
  });
  assert.equal((await files.write("secret.txt", "x")).code, "EACCES");
  assert.deepEqual(await files.stat("secret.txt"), { ok: false, code: "EACCES", path: "secret.txt" });
  await files.write("ok.txt", "x");
  assert.deepEqual(writes, ["ok.txt"]);
  assert.equal((await files.cat(path.join(root, "ok.txt"))).text, "x");
  await fs.rm(root, { recursive: true, force: true });
});

test("invalid utf-8 is reported, never replaced", async () => {
  const root = await tempRoot();
  await fs.writeFile(path.join(root, "bad.bin"), Buffer.from([0xff, 0xfe]));
  const receipt = await new FileMembers({ root }).cat("bad.bin");
  assert.equal(receipt.ok, false);
  assert.equal(receipt.code, "INVALID_UTF8");
  await fs.rm(root, { recursive: true, force: true });
});

test("deadline budget is 50ms per utf-8 byte and cancel stops it", () => {
  const clock = fakeClock(0);
  assert.equal(deadlineFor("中文"), 300);
  assert.equal(deadlineFor(""), 0);
  const deadline = Deadline.forIntent(clock, "abc");
  clock.advance(100);
  assert.equal(deadline.remainingMs(), 50);
  deadline.cancel();
  assert.equal(deadline.expired, false);
  assert.equal(deadline.remainingMs(), 0);
});

test("receipts always carry the nine fields and a live command view", () => {
  const clock = fakeClock(0);
  const view = new CommandView({ clock });
  assert.deepEqual(view.view(), {
    stdout: "",
    stderr: "",
    stdoutBytes: 0,
    stderrBytes: 0,
    durationMs: 0,
    running: true,
    exitCode: null,
  });
  view.append("stdout", "中");
  view.append("stderr", "e");
  clock.advance(5);
  const live = view.view();
  assert.equal(live.stdoutBytes, 3);
  assert.equal(live.durationMs, 5);
  assert.throws(() => view.append("other", "x"), (error) => error.code === "INVALID_ARGUMENT");
  view.end(0);
  assert.throws(() => view.end(1), (error) => error.code === "INVALID_ARGUMENT");
  clock.advance(100);
  const ended = view.view();
  assert.equal(ended.durationMs, 5);
  assert.equal(ended.exitCode, 0);
  assert.equal(ended.running, false);

  const receipt = buildReceipt({ name: "p", state: STATES.WAITING, command: ended, delta: "hi" });
  assert.deepEqual(Object.keys(receipt).sort(), [
    "command",
    "delta",
    "name",
    "payload",
    "pendingOperations",
    "reason",
    "remainingWaitMs",
    "source",
    "state",
  ]);
  assert.equal(receipt.source, null);
  assert.equal(receipt.remainingWaitMs, 0);
  assert.throws(() => buildReceipt({ name: "p", state: "bogus" }), (e) => e.code === "INVALID_ARGUMENT");
  assert.throws(() => buildReceipt({ name: "p", state: STATES.RUNNING, reason: "bogus" }), (e) => e.code === "INVALID_ARGUMENT");
});

test("a program view reports its own site, source and clock", () => {
  const clock = fakeClock(0);
  const program = new ProgramState("v", { deadline: Deadline.forIntent(clock, "ab") });
  program.park({ reason: PARK_REASONS.EXPLICIT, payload: { needle: "x" } });
  const view = programView(program, { source: "a\nb" });
  assert.equal(view.state, STATES.PARKED);
  assert.equal(view.reason, PARK_REASONS.EXPLICIT);
  assert.deepEqual(view.payload, { needle: "x" });
  assert.equal(view.source, "1 | a\n2 | b");
  assert.equal(view.remainingWaitMs, 0);
});

test("command runners report text, codes and stream callbacks", async () => {
  const fake = fakeCommandRunner((command) => ({ stdout: `${command}!`, stderr: "e", exitCode: 3 }));
  const chunks = [];
  const receipt = await fake("hi", { onData: (event) => chunks.push(event) });
  assert.deepEqual(receipt, { stdout: "hi!", stderr: "e", exitCode: 3 });
  assert.deepEqual(chunks.map((event) => event.stream), ["stdout", "stderr"]);
  assert.equal(fake.calls.length, 1);

  const ok = await nodeCommandRunner("printf 'a'; printf 'b' 1>&2; exit 0");
  assert.deepEqual(ok, { stdout: "a", stderr: "b", exitCode: 0 });
  const bad = await nodeCommandRunner("exit 4");
  assert.equal(bad.exitCode, 4);
});

test("paths normalize lexically and root containment is explicit", () => {
  assert.equal(normalizePath("a/./b/../c"), "a/c");
  assert.equal(normalizePath("/a/../../b"), "/b");
  assert.equal(normalizePath(""), ".");
  assert.equal(isInsideRoot("/r", "/r/a/b"), true);
  assert.equal(isInsideRoot("/r", "/r/a"), true);
  assert.equal(isInsideRoot("/r", "/r2/a"), false);
  assert.equal(isInsideRoot("/r", "/other"), false);
  assert.equal(stableJson({ b: 1, a: [2, { d: 3, c: 4 }] }), '{"a":[2,{"c":4,"d":3}],"b":1}');
});

test("CoreError carries a code and details", () => {
  const error = new CoreError("NAME_TAKEN", "taken", { name: "p" });
  assert.equal(error.code, "NAME_TAKEN");
  assert.deepEqual(error.details, { name: "p" });
  assert.ok(error instanceof Error);
});
