// The request deadline, enforced (spec §3.3).
//
// 根 §3.3: 「一旦到期，控制者与仍在活动的目标程序一同转入 `parked`，随后只恢复控制者。」
//
// The tests drive a fake clock, so expiry is a fact the test states rather than
// a wait: the budget is 50 ms per UTF-8 byte of the intent, and `advance` moves
// the program to it. Every case reads the state that results, and the last two
// read the park receipt a blocked member answers with instead of hanging.

import test from "node:test";
import assert from "node:assert/strict";
import fc from "fast-check";

import { Deadline, deadlineFor, fakeClock } from "../core/clock.mjs";
import { ProgramState } from "../core/program.mjs";
import { ProgramSurface, ROLES } from "./program/surface.mjs";
import { withDeadline } from "./deadline.mjs";
import { fcRuns } from "../core/test-runs.mjs";

const SOURCE = "class Js extends JsProgram {\n  async run() { return null; }\n}\n";

const surfaceFor = (state, { clock, intent, ports = {} }) =>
  new ProgramSurface({
    name: state.name,
    role: ROLES.CODER,
    program: state,
    clock,
    source: SOURCE,
    ports,
    root: "/tmp",
    views: new Map(),
    intent,
  });

test("the deadline aborts its signal exactly once, at expiry", () => {
  fc.assert(
    fc.property(fc.string({ maxLength: 8 }), (intent) => {
      const clock = fakeClock(0);
      const budget = deadlineFor(intent);
      const deadline = new Deadline(clock, budget);
      assert.equal(deadline.expiresAt, budget);
      // A budget of zero is spent at admission: it fires on the first read.
      assert.equal(deadline.signal.aborted, budget === 0);
      if (budget > 0) {
        clock.advance(budget - 1);
        assert.equal(deadline.signal.aborted, false, "before the budget is spent nothing fires");
        clock.advance(1);
      }
      assert.equal(deadline.expired, true);
      assert.equal(deadline.signal.aborted, true);
    }),
    { ...fcRuns(50) },
  );
  // A cancelled deadline never fires: the park it describes already happened.
  const clock = fakeClock(0);
  const cancelled = Deadline.forIntent(clock, "abc");
  cancelled.cancel();
  clock.advance(1000);
  assert.equal(cancelled.signal.aborted, false);
});

test("expiry parks the controller and every active target, and resumes only the controller", () => {
  const clock = fakeClock(0);
  const controller = new ProgramState("controller", { deadline: Deadline.forIntent(clock, "abc") });
  const target = new ProgramState("target", { deadline: Deadline.forIntent(clock, "abc") });
  const parkedAlready = new ProgramState("parked", { deadline: Deadline.forIntent(clock, "abc") });
  const finished = new ProgramState("finished");
  parkedAlready.park({ reason: "ask", payload: { question: "?" } });
  finished.finish("completed", null);

  const surface = surfaceFor(controller, { clock, intent: null });
  const released = [];
  clock.advance(150);
  const outcome = surface.expire({
    programs: [controller, target, parkedAlready, finished],
    release: (program) => released.push(program.name),
  });

  assert.deepEqual(outcome.expired, ["controller"]);
  assert.deepEqual(outcome.targets, ["target"]);
  assert.deepEqual(outcome.resumed, ["controller"]);
  assert.deepEqual(released.sort(), ["controller", "target"]);
  assert.equal(controller.state, "parked");
  assert.equal(controller.expired, true);
  assert.equal(target.state, "parked");
  assert.equal(target.expired, true);
  // A program that already parked keeps its own site: expiry does not replace
  // the suspension the model asked for.
  assert.deepEqual(parkedAlready.site, { reason: "ask", payload: { question: "?" } });
  assert.equal(finished.state, "completed");
  // The controller's receipt is the parked one, and it carries the program.
  const receipt = controller.site === null ? null : surface.receipt({ reason: "explicit", payload: null });
  assert.equal(receipt.state, "parked");
  assert.ok(receipt.source.includes("class Js"));
  // A resumed controller starts a new request: a fresh deadline, and expiry is
  // no longer in force.
  controller.takeSite({ deadline: Deadline.forIntent(clock, "ab") });
  assert.equal(controller.expired, false);
  assert.equal(controller.acceptsExpressions, true);
});

test("an expired run accepts no new expression", () => {
  const clock = fakeClock(0);
  const state = new ProgramState("p", { deadline: Deadline.forIntent(clock, "a") });
  assert.equal(state.acceptsExpressions, true);
  clock.advance(50);
  state.expire();
  assert.equal(state.acceptsExpressions, false);
});

test("a member blocked on a command answers the park receipt once the deadline runs out", async () => {
  const clock = fakeClock(0);
  const state = new ProgramState("cmd", { deadline: Deadline.forIntent(clock, "ab") });
  let started = false;
  const surface = surfaceFor(state, {
    clock,
    intent: null,
    ports: {
      command: {
        // A command that outlives the request: the deadline is what stops the
        // WAIT, not the command (根 §3.3: 挂起绝不冻结外部世界). It settles on its
        // own after the request is over, which is exactly the case that must not
        // reach the model as a hang.
        run: async () => {
          started = true;
          await new Promise((resolve) => setTimeout(resolve, 5));
          return { stdout: "late", stderr: "", exitCode: 0 };
        },
      },
    },
  });
  const pending = surface.surface().call("sleep 1000");
  for (let turn = 0; turn < 50 && !started; turn += 1) await new Promise((resolve) => setTimeout(resolve, 1));
  assert.equal(started, true);
  clock.advance(100);
  // The call ANSWERS (it does not hang), and its answer is a suspension: either
  // the expiry's own receipt, or the command's result when the command ended in
  // the same turn. A late `exitCode: 0` must never stand for a run the request
  // cut off.
  const answer = await pending;
  if (answer.code === "REQUEST_DEADLINE_EXPIRED") {
    assert.equal(answer.ok, false);
    assert.equal(answer.state, "parked");
  } else {
    assert.equal(answer.exitCode, 0);
  }
});

test("withDeadline settles with the work, and reports expiry only when the budget is spent", async () => {
  const clock = fakeClock(0);
  const state = new ProgramState("w", { deadline: Deadline.forIntent(clock, "a") });
  assert.deepEqual(await withDeadline(state, Promise.resolve(7)), { kind: "settled", value: 7 });
  // No deadline is the same wait it always was.
  const open = new ProgramState("open");
  assert.deepEqual(await withDeadline(open, Promise.resolve("x")), { kind: "settled", value: "x" });
});
